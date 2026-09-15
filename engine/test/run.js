// engine/test/run.js — 无服务器冒烟测试（mock 驱动全栈：Lua VM + 调度 + persist + 策略 + bslib）
// 用法：node engine/test/run.js
// 注：wasmoon 的 global.get 对 Lua table 返回不可靠 POJO —— 测试脚本一律把结果
// 写成 __R_* 标量全局（string/number/boolean），JS 侧直接读。
import assert from 'node:assert';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { BotEngine } from '../engine.js';
import { MockDriver } from './mock-driver.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TMP = path.join(__dirname, 'tmp');
const TEST_DIR = path.join(TMP, 'scripts');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

function script(name, src) {
  mkdirSync(TEST_DIR, { recursive: true });
  writeFileSync(path.join(TEST_DIR, name), src);
}

async function makeEngine({ yaml = {}, scripts = [], driver, boundary } = {}) {
  const d = driver ?? new MockDriver();
  const engine = new BotEngine({
    name: 'test-bot',
    ...yaml,
    scripts,
  }, d, {
    scriptDir: TEST_DIR,
    // v2：边界经 opts.boundary（实例部署层，DESIGN §6）；缺省给空 boundary（已授权实例），
    // 观察模式（默认全拒）由用例显式传 boundary: null 验证
    boundary: boundary !== undefined ? boundary : (yaml.boundary ?? yaml.policy ?? {}),
    connect: yaml.connect ?? yaml.driver ?? {},
  });
  await engine.start();
  return { engine, driver: d };
}

async function waitFor(cond, timeout = 5000, step = 25) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await cond()) return true;
    await new Promise((r) => setTimeout(r, step));
  }
  return false;
}

const g = (engine, name) => engine.lua.global.get(name);

// ============================================================
// 1. 调度核心
// ============================================================

test('任务与组合子：race 吞兄弟取消、parallel 全完成、with_timeout、cancel 清理钩', async () => {
  script('t1.lua', `
    __R_race_winner = nil
    __R_p1, __R_p2 = nil, nil
    __R_timeout_kind = nil
    __R_cleaned = false
    task("parent", function()
      local w = race({
        function() time.sleep(10) return "fast" end,
        function() time.sleep(10000) return "slow" end,
      })
      __R_race_winner = w
      local rs = parallel({
        function() time.sleep(10) return 1 end,
        function() time.sleep(20) return 2 end,
      })
      __R_p1, __R_p2 = rs[1], rs[2]
      local ok, err = pcall(function()
        with_timeout(50, function() time.sleep(5000) end)
      end)
      __R_timeout_kind = (ok == false) and err.kind or nil
      task.spawn(function()
        on_cancel(function() nav.stop(); __R_cleaned = true end)
        time.sleep(10000)
      end)
      time.sleep(30)
      task.cancel_all("parent-end")
    end)
    on_start(function() task.spawn("parent") end)
  `);
  const { engine } = await makeEngine({ scripts: ['t1.lua'] });
  assert.ok(await waitFor(() => g(engine, '__R_race_winner') === 'fast'), 'race 应快分支胜');
  assert.ok(await waitFor(() => g(engine, '__R_p1') === 1 && g(engine, '__R_p2') === 2), 'parallel 应全完成');
  assert.ok(await waitFor(() => g(engine, '__R_timeout_kind') === 'timeout'), 'with_timeout 应抛 timeout');
  assert.ok(await waitFor(() => g(engine, '__R_cleaned') === true), 'on_cancel 清理钩应执行');
  await engine.stop();
});

test('single 防重入：重复触发被丢弃', async () => {
  script('t2.lua', `
    __R_runs = 0
    task("solo", { single = true }, function()
      __R_runs = __R_runs + 1
      time.sleep(200)
    end)
    on_start(function()
      task.spawn("solo")
      task.spawn("solo")
    end)
  `);
  const { engine } = await makeEngine({ scripts: ['t2.lua'] });
  await new Promise((r) => setTimeout(r, 60));
  assert.strictEqual(g(engine, '__R_runs'), 1, 'single 任务期间重复 spawn 应丢弃');
  await engine.stop();
});

// ============================================================
// 2. persist
// ============================================================

test('persist：adjust_if 防双花、txn 回滚、log mark、kv、seen 去重', async () => {
  script('t3.lua', `
    persist.table "Account" { player = "string:key", balance = "number" }
    persist.log  "Intent"
    persist.kv   "meta"
    on_start(function()
      task.spawn(function()
        Account.upsert{ player = "alice", balance = 10 }
        local a = Account.adjust_if("alice", function(ac) return ac.balance >= 8 end, -8)
        local b = Account.adjust_if("alice", function(ac) return ac.balance >= 8 end, -8)
        __R_cond_a = a
        __R_cond_b = b
        __R_cond_balance = Account.find{ player = "alice" }.balance
        Account.adjust("bob", 5)
        __R_bob = Account.find{ player = "bob" }.balance
        txn(function() Account.upsert{ player = "carol", balance = 1 } end)
        pcall(function()
          txn(function()
            Account.upsert{ player = "dave", balance = 3 }
            error{ kind = "boom" }
          end)
        end)
        __R_carol = Account.find{ player = "carol" } ~= nil
        __R_dave = Account.find{ player = "dave" } ~= nil
        local id = Intent.append{ kind = "pay", player = "alice", amount = 8, state = "pending" }
        Intent.mark(id, "done")
        __R_log_rows = #Intent.find{ player = "alice", kind = "pay", state = "done" }
        meta.set("k", { v = 42 })
        __R_kv = meta.get("k").v
        __R_seen1 = persist.seen("dep:hello", 5000)
        __R_seen2 = persist.seen("dep:hello", 5000)
      end)
    end)
  `);
  const { engine } = await makeEngine({ scripts: ['t3.lua'] });
  assert.ok(await waitFor(() => g(engine, '__R_seen2') !== undefined), '场景未跑完');
  assert.deepStrictEqual([g(engine, '__R_cond_a'), g(engine, '__R_cond_b')], [true, false], '第二笔扣减必须失败');
  assert.strictEqual(g(engine, '__R_cond_balance'), 2);
  assert.strictEqual(g(engine, '__R_bob'), 5, 'adjust 无则建户');
  assert.strictEqual(g(engine, '__R_carol'), true, 'txn 提交应保留');
  assert.strictEqual(g(engine, '__R_dave'), false, 'txn 回滚不应留痕');
  assert.strictEqual(g(engine, '__R_log_rows'), 1);
  assert.strictEqual(g(engine, '__R_kv'), 42);
  assert.deepStrictEqual([g(engine, '__R_seen1'), g(engine, '__R_seen2')], [false, true], 'seen 应去重同文');
  await engine.stop();
});

test('persist.txn_yielded：事务体内挂起点 => 回滚并抛类型化错误', async () => {
  script('t3b.lua', `
    persist.table "T" { k = "string:key", v = "number" }
    on_start(function()
      task.spawn(function()
        local ok, err = pcall(function()
          txn(function()
            T.upsert{ k = "x", v = 1 }
            time.sleep(10)
            T.upsert{ k = "y", v = 2 }
          end)
        end)
        __R_err_kind = (not ok) and err.kind or nil
      end)
    end)
  `);
  const { engine } = await makeEngine({ scripts: ['t3b.lua'] });
  assert.ok(await waitFor(() => g(engine, '__R_err_kind') !== undefined));
  assert.strictEqual(g(engine, '__R_err_kind'), 'persist.txn_yielded');
  assert.strictEqual(engine.persist.find('T', { k: 'x' }), null, '事务应已回滚');
  await engine.stop();
});

// ============================================================
// 3. 聊天 / 命令 / 权限
// ============================================================

test('on_chat 命名分组 + sender_kind 过滤 + await_chat 命中/超时', async () => {
  script('t4.lua', `
    __R_hit1 = nil
    __R_await_hit = nil
    __R_await_miss_hit = false
    on_chat(rex[[^收到来自 (?<from>\\w+) (?<amount>\\d+) c$]], { source = "system" }, function(m)
      __R_hit1 = m.from .. "|" .. m.amount .. "|" .. m.sender_kind
    end)
    task("await_test", function()
      local hit = await_chat(rex[[^转账 (?<to>\\w+)$]], 1000)
      __R_await_hit = hit and hit.to or nil
      local miss = await_chat(rex[[^never_match_xyz$]], 50)
      __R_await_miss_hit = miss ~= nil
    end)
    on_start(function() task.spawn("await_test") end)
  `);
  const { engine, driver } = await makeEngine({ scripts: ['t4.lua'] });
  await new Promise((r) => setTimeout(r, 50));
  driver.serverSay('收到来自 Alice 10 c');
  driver.playerSay('Bob', '转账 Carol');
  assert.ok(await waitFor(() => g(engine, '__R_hit1') !== undefined));
  assert.strictEqual(g(engine, '__R_hit1'), 'Alice|10|system', '命名分组 + system 源过滤');
  assert.ok(await waitFor(() => g(engine, '__R_await_hit') !== undefined));
  assert.strictEqual(g(engine, '__R_await_hit'), 'Carol', 'await_chat 应命中并解出命名分组');
  await new Promise((r) => setTimeout(r, 120));
  assert.strictEqual(g(engine, '__R_await_miss_hit'), false, 'await_chat 超时应返回 nil');
  await engine.stop();
});

test('命令总线：权限拒绝与放行 + 内建 pause/resume', async () => {
  script('t5.lua', `
    __R_ran = false
    on_command("guard stop", { perm = "op" }, function() __R_ran = true end)
  `);
  const { engine, driver } = await makeEngine({
    scripts: ['t5.lua'],
    yaml: { boundary: { authority: { op: ['Steve'] } } },
  });
  await new Promise((r) => setTimeout(r, 30));
  driver.playerSay('Eve', 'guard stop');
  await new Promise((r) => setTimeout(r, 50));
  assert.strictEqual(g(engine, '__R_ran'), false, '无权限命令不得执行');
  driver.playerSay('Steve', 'guard stop');
  await new Promise((r) => setTimeout(r, 50));
  assert.strictEqual(g(engine, '__R_ran'), true, 'op 命令应执行');
  driver.playerSay('Steve', 'pause');
  assert.strictEqual(engine.paused, true, '内建 pause 应生效');
  driver.playerSay('Eve', 'resume');
  assert.strictEqual(engine.paused, true, '无权限 resume 不得生效');
  driver.playerSay('Steve', 'resume');
  assert.strictEqual(engine.paused, false, 'resume 应生效');
  await engine.stop();
});

// ============================================================
// 4. 窗口点击协议 + bslib 复合
// ============================================================

test('container + bslib：withdraw 精确取出、deposit、move 多趟', async () => {
  script('t6.lua', `
    task("haul", function()
      local A = { x = 5, y = 64, z = 5 }
      local B = { x = 10, y = 64, z = 5 }
      local w = container.open(A)
      __R_type = w:type()
      __R_size = w:size()
      local got = bslib.withdraw(w, { id = "minecraft:pink_wool" }, 30)
      __R_withdrawn = got
      container.close(w)
      __R_inv_after_w = inv.count{ id = "minecraft:pink_wool" }
      local w2 = container.open(B)
      local put = bslib.deposit(w2, { id = "minecraft:pink_wool" }, got)
      __R_deposited = put
      container.close(w2)
      __R_moved = bslib.move(A, B, { id = "minecraft:pink_wool" })
    end)
    on_start(function() task.spawn("haul") end)
  `);
  const driver = new MockDriver();
  driver.setContainer({ x: 5, y: 64, z: 5 }, [[0, 'minecraft:pink_wool', 64], [1, 'minecraft:pink_wool', 64]]);
  driver.setContainer({ x: 10, y: 64, z: 5 }, []);
  const { engine } = await makeEngine({ scripts: ['t6.lua'], driver });
  assert.ok(await waitFor(() => {
    const m = g(engine, '__R_moved');
    return m !== undefined && m !== null;
  }, 10000), 'move 未完成');
  assert.strictEqual(g(engine, '__R_type'), 'chest');
  assert.strictEqual(g(engine, '__R_size'), 27);
  assert.strictEqual(g(engine, '__R_withdrawn'), 30, '精确取出 30');
  assert.strictEqual(g(engine, '__R_inv_after_w'), 30);
  assert.strictEqual(g(engine, '__R_deposited'), 30);
  assert.strictEqual(g(engine, '__R_moved'), 98, 'A 剩余 98 块应全部转移');
  assert.strictEqual(driver.containerSlots.get('5,64,5').reduce((n, s) => n + (s?.count ?? 0), 0), 0, 'A 应空');
  assert.strictEqual(driver.containerSlots.get('10,64,5').reduce((n, s) => n + (s?.count ?? 0), 0), 128, 'B 应收齐 128');
  await engine.stop();
});

// ============================================================
// 5. 暂停语义
// ============================================================

test('pause/resume：动作以 runtime.paused 失败（恢复时于冻结点重抛），钩子执行', async () => {
  script('t7.lua', `
    __R_paused = false
    __R_resumed = false
    __R_paused_err = false
    __R_wrong_err = nil
    on_pause(function() __R_paused = true end)
    on_resume(function() __R_resumed = true end)
    task("walker", function()
      local ok, err = pcall(function()
        nav.walk({ x = 50, y = 64, z = 50 }, { timeout = 30000 })
      end)
      if not ok and type(err) == "table" and err.kind == "runtime.paused" then
        __R_paused_err = true
      else
        __R_wrong_err = (not ok) and tostring(err.kind or err) or "no_err"
      end
    end)
    on_start(function() task.spawn("walker") end)
  `);
  const driver = new MockDriver();
  driver.gotoDelay = 400;
  const { engine } = await makeEngine({ scripts: ['t7.lua'], driver });
  await new Promise((r) => setTimeout(r, 80));
  engine.pause('command');
  assert.strictEqual(engine.paused, true);
  await new Promise((r) => setTimeout(r, 50));
  assert.strictEqual(g(engine, '__R_paused'), true, 'on_pause 应执行');
  assert.strictEqual(g(engine, '__R_paused_err'), false, '暂停期间错误应冻结于门');
  engine.resume();
  assert.ok(await waitFor(() => g(engine, '__R_paused_err') === true), '恢复后应在冻结点重抛 runtime.paused');
  assert.strictEqual(g(engine, '__R_resumed'), true, 'on_resume 应执行');
  assert.ok(g(engine, '__R_wrong_err') == null, '不应是其它错误');
  await engine.stop();
});

// ============================================================
// 6. 示例脚本
// ============================================================

const EXAMPLES = path.resolve(__dirname, '..', '..', 'examples');

test('bank：入账/去重/转账/取款挂账/回执核销/私聊余额 全链路', async () => {
  const driver = new MockDriver();
  const engine = new BotEngine({
    name: 'bank-1',
    scripts: [path.join(EXAMPLES, 'bank', 'bank.lua')],
  }, driver, {
    scriptDir: path.join(EXAMPLES, 'bank'),
    boundary: { authority: { op: ['Steve'] }, chat: { commands: ['/pay'], msg_command: '/msg' } },
  });
  await engine.start();
  await new Promise((r) => setTimeout(r, 50));

  driver.serverSay('收到来自 Alice 10 c');
  await new Promise((r) => setTimeout(r, 60));
  assert.strictEqual(engine.persist.find('Account', { player: 'Alice' })?.balance, 10, '入账 10');

  driver.serverSay('收到来自 Alice 10 c');
  await new Promise((r) => setTimeout(r, 60));
  assert.strictEqual(engine.persist.find('Account', { player: 'Alice' })?.balance, 10, '重复同文应被 seen 去重');

  driver.playerSay('Alice', '转账 Bob');
  await new Promise((r) => setTimeout(r, 60));
  assert.strictEqual(engine.persist.find('Account', { player: 'Bob' })?.balance, 10, 'Bob 收到划转');
  assert.strictEqual(engine.persist.find('Account', { player: 'Alice' })?.balance, 0);

  driver.playerSay('Bob', '取款 6');
  await new Promise((r) => setTimeout(r, 60));
  assert.ok(driver.outgoingCommands.some((c) => c === '/pay Bob 6'), '应发出白名单内 /pay');
  assert.strictEqual(engine.persist.find('Account', { player: 'Bob' })?.balance, 4, '扣款即时生效');
  assert.strictEqual(engine.persist.logFind('Intent', { state: 'pending' }).length, 1, '回执未到：意图 pending');
  assert.ok(await waitFor(() => driver.outgoingChat.some((m) => m.includes('支付确认超时')), 8000), '5s 超时后挂账告示');
  assert.strictEqual(engine.persist.logFind('Intent', { state: 'pending' }).length, 1, '超时不回滚：保持挂账');

  driver.serverSay('已向 Bob 6 c');
  await new Promise((r) => setTimeout(r, 80));
  assert.strictEqual(engine.persist.logFind('Intent', { state: 'pending' }).length, 0, '迟到回执应收口挂账');

  driver.playerSay('Steve', 'balance Alice');
  await new Promise((r) => setTimeout(r, 60));
  assert.ok(driver.outgoingCommands.some((c) => c.startsWith('/msg Steve')), 'balance 应走私聊给操作者');

  await engine.stop();
});

test('organizer：冷启动扫描 + organize once + 输入箱分流', async () => {
  const driver = new MockDriver();
  driver.setContainer({ x: 5, y: 64, z: 5 }, [[0, 'minecraft:pink_wool', 30], [1, 'minecraft:cobblestone', 12]]);
  driver.setContainer({ x: 10, y: 64, z: 5 }, []);
  driver.setContainer({ x: 12, y: 64, z: 5 }, [[3, 'minecraft:pink_wool', 40]]);
  const engine = new BotEngine({
    name: 'organizer-1',
    scripts: [path.join(EXAMPLES, 'organizer', 'organizer.lua')],
    params: {
      // 离线测试：实例坐标以包默认层注入（线上等价于运行期设参后持久化）
      input: [5, 64, 5],
      stock_zone: [[9, 62, 3], [14, 66, 7]],
      partial_chests: [[10, 64, 5]],
    },
  }, driver, {
    scriptDir: path.join(EXAMPLES, 'organizer'),
    boundary: { authority: { op: ['Steve'] }, blocks: { dig: 'deny', place: 'deny' } },
  });
  await engine.start();
  await new Promise((r) => setTimeout(r, 2200));   // 等 session.ready 冷启动扫描
  driver.playerSay('Steve', 'organize once');
  const dumpSlots = (k) => k === 'inv'
    ? driver.inv.filter(Boolean).map((s) => `${s.id.split(':')[1]}x${s.count}`).join(',') || '(空)'
    : driver.containerSlots.get(k).map((s, i) => s ? `${i}:${s.id.split(':')[1]}x${s.count}` : null).filter(Boolean).join(' ') || '(空)';
  assert.ok(await waitFor(() => {
    const input = driver.containerSlots.get('5,64,5');
    return input.every((s) => !s);
  }, 20000), '输入箱应被清空');
  const countIn = (k, id) => driver.containerSlots.get(k)
    .reduce((n, s) => n + (s?.id === id ? s.count : 0), 0);
  console.log('  [dump] chest10 =', dumpSlots('10,64,5'), '| chest12 =', dumpSlots('12,64,5'), '| inv =', dumpSlots('inv'));
  const woolTotal = countIn('10,64,5', 'minecraft:pink_wool') + countIn('12,64,5', 'minecraft:pink_wool') + driver.invCount('minecraft:pink_wool');
  assert.strictEqual(woolTotal, 70, `wool 守恒（实际 ${woolTotal}）`);
  const cobbleTotal = countIn('10,64,5', 'minecraft:cobblestone') + countIn('12,64,5', 'minecraft:cobblestone') + driver.invCount('minecraft:cobblestone');
  assert.strictEqual(cobbleTotal, 12, `圆石守恒（实际 ${cobbleTotal}）`);
  await engine.stop();
});

test('coal_guard：举煤触发追击、放下即停、guard stop 取消', async () => {
  const driver = new MockDriver();
  const engine = new BotEngine({
    name: 'coal-guard-1',
    scripts: [path.join(EXAMPLES, 'coal_guard', 'coal_guard.lua')],
  }, driver, {
    scriptDir: path.join(EXAMPLES, 'coal_guard'),
    boundary: { authority: { op: ['Steve'] }, combat: { targets: 'player', max_engage: 24 } },
  });
  await engine.start();
  await new Promise((r) => setTimeout(r, 80));

  driver.addPlayer('Griefer', { x: 10, y: 64, z: 0 }, { id: 'minecraft:coal', count: 1 });
  assert.ok(await waitFor(() => engine.actionLog.some((a) => a.action === 'use_entity'), 8000), '应发起追击攻击');
  await new Promise((r) => setTimeout(r, 300));

  driver.setPlayerHeld('Griefer', null);
  assert.ok(await waitFor(() => driver.outgoingChat.some((m) => m.includes('已放下煤炭')), 5000), '应通报放过');
  const attacksAfter = engine.actionLog.filter((a) => a.action === 'use_entity').length;
  await new Promise((r) => setTimeout(r, 600));
  assert.strictEqual(engine.actionLog.filter((a) => a.action === 'use_entity').length, attacksAfter, '放下煤炭后应停止攻击');

  driver.playerSay('Steve', 'guard stop');
  assert.ok(await waitFor(() => driver.outgoingChat.some((m) => m.includes('警戒已停止')), 3000), 'stop 应回复');
  driver.setPlayerHeld('Griefer', { id: 'minecraft:coal', count: 1 });
  await new Promise((r) => setTimeout(r, 800));
  assert.strictEqual(engine.actionLog.filter((a) => a.action === 'use_entity').length, attacksAfter, '取消后不应再攻击');
  await engine.stop();
});

// ============================================================
// 7. v2 边界与部署（DESIGN §5.1 / §6）
// ============================================================

test('观察模式：无 boundary 一切动作被拒、查询与 params 可用', async () => {
  script('t8.lua', `
    __R_nav_err = nil
    __R_dig_err = nil
    task("mover", function()
      local ok, err = pcall(function() nav.walk({ x = 30, y = 64, z = 30 }, { timeout = 5 }) end)
      if not ok and type(err) == "table" then __R_nav_err = err.kind end
      local ok2, err2 = pcall(function() combat.dig({ x = 1, y = 64, z = 1 }) end)
      if not ok2 and type(err2) == "table" then __R_dig_err = err2.kind end
    end)
    on_start(function() task.spawn("mover") end)
  `);
  const driver = new MockDriver();
  const { engine } = await makeEngine({ scripts: ['t8.lua'], driver, boundary: null });
  assert.ok(await waitFor(() => g(engine, '__R_nav_err') !== undefined, 5000), 'nav 应被拒');
  assert.strictEqual(g(engine, '__R_nav_err'), 'permission.denied', '无 boundary：nav 必须 permission.denied');
  assert.ok(await waitFor(() => g(engine, '__R_dig_err') !== undefined, 5000), 'dig 应被拒');
  assert.strictEqual(g(engine, '__R_dig_err'), 'permission.denied', '无 boundary：dig 必须 permission.denied');
  // 查询与 params 不受观察模式限制（self 缓存即查询数据面）
  assert.ok(engine.self?.pos, '观察模式下查询数据面仍可用');
  await engine.stop();
});

test('未授即禁：blocks.dig / combat 未授权即拒，显式授权后放行', async () => {
  script('t9.lua', `
    __R_dig_err = nil
    __R_attack_err = nil
    task("actor", function()
      local ok, err = pcall(function() combat.dig({ x = 2, y = 64, z = 2 }) end)
      if not ok and type(err) == "table" then __R_dig_err = err.kind end
      local e = entity.nearest{ type = "player", alive = true, within = 10 }
      if e then
        local ok2, err2 = pcall(function() combat.attack(e) end)
        if not ok2 and type(err2) == "table" then __R_attack_err = err2.kind end
      end
    end)
    on_start(function() task.spawn("actor") end)
  `);
  // boundary = {}（有配置但未授 dig/combat）→ 均拒
  const driver = new MockDriver();
  driver.addPlayer('Victim', { x: 3, y: 64, z: 3 }, null);
  const { engine } = await makeEngine({ scripts: ['t9.lua'], driver, boundary: {} });
  assert.ok(await waitFor(() => g(engine, '__R_dig_err') !== undefined, 5000), 'dig 应被拒');
  assert.strictEqual(g(engine, '__R_dig_err'), 'policy.blocklist', '未授权 dig 必须 policy.blocklist');
  assert.strictEqual(g(engine, '__R_attack_err'), 'policy.blocklist', '未授权 combat 必须 policy.blocklist');
  await engine.stop();

  // 显式授权后放行
  const driver2 = new MockDriver();
  driver2.addPlayer('Victim', { x: 3, y: 64, z: 3 }, null);
  const { engine: engine2 } = await makeEngine({
    scripts: ['t9.lua'], driver: driver2,
    boundary: { blocks: { dig: 'allow' }, combat: { targets: 'player', max_engage: 16 } },
  });
  assert.ok(await waitFor(
    () => engine2.actionLog.some((a) => a.action === 'dig'), 5000), '授权后 dig 应放行');
  assert.ok(await waitFor(
    () => engine2.actionLog.some((a) => a.action === 'use_entity'), 5000), '授权后 attack 应放行');
  await engine2.stop();
});

test('v2 包清单：params schema+默认值形态、required 未设不阻塞启动', async () => {
  script('t10.lua', `
    __R_p1 = nil
    __R_p2 = "unset"
    on_start(function()
      __R_p1 = params.num_opt
      if params.must_set == nil then __R_p2 = "missing" else __R_p2 = params.must_set end
    end)
  `);
  const { engine } = await makeEngine({
    scripts: ['t10.lua'],
    yaml: {
      params: {
        num_opt: { type: 'number', default: 7, help: '清单 schema 形态' },
        must_set: { type: 'number', required: true },
      },
    },
  });
  await new Promise((r) => setTimeout(r, 60));
  assert.strictEqual(g(engine, '__R_p1'), 7, '清单 default 应生效');
  assert.strictEqual(g(engine, '__R_p2'), 'missing', 'required 未设应为空但不阻塞启动');
  // 运行期设置后持久化（重启即实例事实）
  engine.setParam('must_set', 42);
  assert.strictEqual(engine.paramPersist.get('must_set'), 42, '显式设置应进入持久化层');
  await engine.stop();
});

// ============================================================
// 8. HTTP 控制通道（CAPABILITIES §17）
// ============================================================

test('httpapi：鉴权/状态/params/cmd/tasks/logs/persist/caps 全链', async () => {
  const driver = new MockDriver();
  const { engine } = await makeEngine({
    scripts: [],
    driver,
    boundary: { authority: { op: ['Steve'] }, chat: { commands: ['/pay'] } },
    yaml: { params: { num_opt: { type: 'number', default: 7 } } },
  });
  process.env.BOTSCRIPT_HTTP_PORT = '0';
  const { startHttpApi } = await import('../httpapi.js');
  const { port, token } = await startHttpApi(engine, { log: () => {} });
  const base = `http://127.0.0.1:${port}`;
  const hdr = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

  assert.strictEqual((await fetch(base + '/state')).status, 401, '无 token 401');
  assert.strictEqual((await fetch(base + '/state', { headers: { Authorization: 'Bearer nope' } })).status, 401);

  const state = (await (await fetch(base + '/state', { headers: hdr })).json());
  assert.strictEqual(state.name, 'test-bot');
  assert.strictEqual(state.host, 'mineflayer');
  assert.ok('nav.pathfinder' in state.caps);

  // params：schema 拒绝未知键；合法键写入并持久化
  assert.strictEqual((await fetch(base + '/params', { method: 'POST', headers: hdr, body: '{"nope":1}' })).status, 400);
  const pset = await (await fetch(base + '/params', { method: 'POST', headers: hdr, body: '{"num_opt":9}' })).json();
  assert.strictEqual(pset.changed.num_opt, 9);
  assert.strictEqual(engine.paramValues.get('num_opt'), 9);

  // cmd：同一命令总线（console 级）
  const cmd = await (await fetch(base + '/cmd', { method: 'POST', headers: hdr, body: '{"cmd":"status"}' })).json();
  assert.strictEqual(cmd.matched, true, '内建 status 应命中命令总线');

  // tasks + cancel
  const tasks = (await (await fetch(base + '/tasks', { headers: hdr })).json()).tasks;
  assert.ok(Array.isArray(tasks));
  const cancel = (await (await fetch(base + '/tasks/cancel', { method: 'POST', headers: hdr, body: '{"name":"ghost"}' })).json());
  assert.strictEqual(cancel.found, false);

  // logs：增量（since 过滤）
  const l1 = (await (await fetch(base + '/logs', { headers: hdr })).json());
  assert.ok(l1.logs.length > 0, '日志环应有启动日志');
  const l2 = (await (await fetch(base + `/logs?since=${Date.now() + 1000}`, { headers: hdr })).json());
  assert.strictEqual(l2.logs.length, 0, 'since 未来时刻应无增量');

  // persist：未声明表 → 400；声明后可查
  engine.persist.declareTable('T', { k: 'string:key', v: 'number' });
  engine.persist.upsert('T', { k: 'a', v: 1 });
  const rows = (await (await fetch(base + '/persist?table=T', { headers: hdr })).json()).rows;
  assert.strictEqual(rows.length, 1);
  assert.strictEqual((await fetch(base + '/persist?table=Nope', { headers: hdr })).status, 400);

  // caps：包清单回显
  const capsBody = (await (await fetch(base + '/caps', { headers: hdr })).json());
  assert.strictEqual(capsBody.host, 'mineflayer');
  assert.strictEqual(capsBody.manifest.name, 'test-bot');

  // eval：一次性 Lua 作为具名任务（同环境；返回值入日志；编译错误 400；运行失败进任务日志）
  const ev = await (await fetch(base + '/eval', { method: 'POST', headers: hdr, body: '{"name":"t1","code":"return 42"}' })).json();
  assert.strictEqual(ev.ok, true);
  assert.strictEqual(ev.task, 't1');
  const msgs = async () => (await (await fetch(base + '/logs', { headers: hdr })).json()).logs.map((l) => l.msg);
  assert.ok((await msgs()).some((m) => m.includes('[eval:t1] => 42')), 'eval 返回值应入日志');
  assert.strictEqual(
    (await fetch(base + '/eval', { method: 'POST', headers: hdr, body: '{"code":"return ~"}' })).status,
    400, '编译错误应 400',
  );
  await (await fetch(base + '/eval', { method: 'POST', headers: hdr, body: '{"name":"t2","code":"error(\\"boom\\")"}' })).json();
  assert.ok((await msgs()).some((m) => m.includes('t2') && m.includes('失败')), 'eval 运行失败应进日志');

  await engine.stop();
});

// ============================================================
// 9. 平台事件入站（/event → events.next）
// ============================================================

test('events：pushEvent 直付等待者、FIFO 排队、空队超时 nil', async () => {
  script('t11.lua', `
    __R_order = nil
    task("evt_loop", { single = true }, function()
      local order = {}
      local ev = events.next(2000)   -- 等待中被直付
      order[#order + 1] = ev and ev.type or "nil"
      time.sleep(50)                 -- 让后续事件入队（无等待者）
      for i = 1, 4 do
        local e = events.next(120)
        order[#order + 1] = e and e.type or "timeout"
      end
      __R_order = table.concat(order, ",")
    end)
    on_start(function() task.spawn("evt_loop") end)
  `);
  const { engine } = await makeEngine({ scripts: ['t11.lua'] });
  assert.ok(await waitFor(() => engine.eventWaiters.length === 1), '事件循环应挂起等待');
  engine.pushEvent('deploy', { k: 42 });
  engine.pushEvent('say', {});
  engine.pushEvent('cmd', {});
  engine.pushEvent('third', {});
  assert.ok(await waitFor(() => g(engine, '__R_order') === 'deploy,say,cmd,third,timeout', 5000),
    `消费序应为 直付+FIFO+超时（实际 ${g(engine, '__R_order')}）`);
  assert.strictEqual(engine.eventQueue.length, 0);
  await engine.stop();
});

// ============================================================

const started = Date.now();
let failed = 0;
for (const [name, fn] of tests) {
  process.stdout.write(`· ${name}\n`);
  try {
    await fn();
    console.log(`  PASS (${((Date.now() - started) / 1000).toFixed(1)}s 累计)`);
  } catch (e) {
    failed++;
    console.error(`  FAIL: ${e.message}`);
  }
}
rmSync(TMP, { recursive: true, force: true });
console.log(failed === 0 ? `\n全部 ${tests.length} 项通过` : `\n${failed}/${tests.length} 项失败`);
process.exit(failed === 0 ? 0 : 1);
