-- ============================================================================
-- bot-api.d.lua — bot-script Lua API 声明（EmmyLua/LuaLS 注解，仅供编辑器使用）
--
-- 用法（VS Code + Lua Language Server（sumneko）或 EmmyLua 插件）：
--   把本文件加入工作区（或 .luarc.json 的 workspace.library），脚本即获得
--   补全、参数提示与类型检查。本文件不会也不应被运行时加载。
--
-- 运行时真身：engine/lua/bootstrap.lua（HAL）+ engine/lua/bslib.lua（策略库）。
-- 规范方言 Lua 5.4（engine/wasmoon）；fabric 宿主为 LuaJ，差异以 caps 暴露。
-- ============================================================================

-- ==========================================================================
-- 生命周期与声明期
-- ==========================================================================

---世界加入后触发一次（脚本入口）。耗时工作请 spawn 任务或直接在本体内挂起。
---@param fn fun()
function on_start(fn) end

---暂停时触发（游戏菜单/宿主 pause；挂起动作以 runtime.paused 失败，resume 时在冻结点重抛）
---@param fn fun()
function on_pause(fn) end

---恢复时触发
---@param fn fun()
function on_resume(fn) end

---任务体未捕获错误时触发（每错误一次；此后任务 [名] 失败 记入日志）
---@param fn fun(e: {kind: string, detail: any})
function on_error(fn) end

---宿主事件订阅。常见 kind："chat" / "world.block_changed" / "self.damaged" /
---"container.closed" / "entity.appeared" / "entity.gone" / "nav.failed"
---@param name string
---@param fn fun(payload: table)
function on_event(name, fn) end

---声明期定时器：每 interval 毫秒触发 fn（宿主重启后重新声明）
---@param interval number 毫秒
---@param fn fun()
function on_timer(interval, fn) end

---声明期延时：d 毫秒后触发一次 fn
---@param d number 毫秒
---@param fn fun()
function after(d, fn) end

---聊天命令声明（游戏内命令面）。pattern 形如 "balance <player>"；opts.perm 见 CAPABILITIES §11
---@param pattern string
---@param opts? {perm?: string}
---@param fn fun(args: table)
function on_command(pattern, opts, fn) end

---注册聊天监听。rexid 为 rex"正则" 生成的模式 id；命中分组作为 payload 传入
---@param rexid userdata rex"..." 的返回值
---@param opts? {source?: string, from?: string}  sender_kind / 发言者过滤
---@param fn fun(payload: {text: string, raw: string, sender: string, sender_kind: string, ts: number})
function on_chat(rexid, opts, fn) end

---构造聊天匹配模式：rex"^余额 *(%d+)$"
---@param pattern string
---@return userdata
function rex(pattern) end

-- ==========================================================================
-- task（协作式任务）
-- ==========================================================================

---@class task
---声明具名任务：task("organize", { single = true }, function() ... end)
---opts.single = 防重入（同名任务存活时丢弃本次 spawn）
---@param name string
---@param opts? {single?: boolean}
---@param fn fun()
function task(name, opts, fn) end

---启动任务（具名 = 引用声明；匿名 = 传函数）。子任务：父任务结束时级联取消。
---事件/生命周期处理器请用 spawn_detached（脱离根，完成不级联）。
---@param name_or_fn string|fun(...)
---@return table t 任务句柄（t.id/t.name/t.status）
function task.spawn(name_or_fn, ...) end

---启动脱离根任务（on_start/事件处理器形态：完成不级联取消其 spawn 的任务）
---@param fn fun(...)
---@return table t
function task.spawn_detached(fn, ...) end

---协作式取消。target = 任务句柄 | taskid | 具名。取消在下一个挂起点注入。
---@param target table|number|string
---@param source? string 取消来源（日志留痕）
---@return boolean ok
function task.cancel(target, source) end

---取消当前所有存活任务
---@param source? string
function task.cancel_all(source) end

---存活任务列表 [{id, name, status}]
---@return {id: number, name: string, status: string}[]
function task.all() end

---上报任务进度（HTTP /tasks 可见）
---@param delta number
function task.progress(delta) end

-- ==========================================================================
-- 结构化并发组合子
-- ==========================================================================

---并行执行所有 fn，全部完成后返回结果数组；任一真实错误 => 抛首错并取消其余
---@param fns fun()[]
---@return any[] results
function parallel(fns) end

---赛跑：首个完成（ok 或 error）者胜，返回其返回值；其余取消
---@param fns fun()[]
---@return any val
function race(fns) end

---超时包装：d 毫秒未完成则抛 {kind="timeout"} 并取消 fn
---@param d number 毫秒
---@param fn fun()
---@return any
function with_timeout(d, fn) end

---受控捕获：不吞取消（取消穿透），返回 ok, err
---@param fn fun()
---@return boolean ok, any err
function try(fn) end

---断言守卫：cond 为假则（可选跑 fn）抛 {kind="guard.failed"}
---@param cond any
---@param fn? fun()
function guard(cond, fn) end

---轮询等待谓词为真；超时抛 {kind="timeout"}
---@param pred fun(): any
---@param opts? {timeout?: number, poll?: number}  毫秒；poll 默认 100
---@return true
function wait_until(pred, opts) end

-- ==========================================================================
-- time / log
-- ==========================================================================

---@class time
---挂起当前任务 d 毫秒（协作式；取消在此注入）
---@param d number 毫秒
function time.sleep(d) end

---宿主单调时钟（毫秒）
---@return number
function time.now() end

---@class log
---log.info("抵达 %s (%.1f)", name, dist) —— printf 风格
---@param fmt string
function log.info(fmt, ...) end

---@param fmt string
function log.warn(fmt, ...) end

---@param fmt string
function log.error(fmt, ...) end

-- ==========================================================================
-- params（统一参数；只读，经控制总线修改）
-- ==========================================================================

---@class params
---声明期 schema 声明：params{ chest = {type="blockpos", default={...}, visible=true, help="..."} }
---@param schema table
function params(schema) end

---运行期读取：params.chest（值在 on_start/task 内读取；见时序说明）
---@param k string
---@return any
function params.__index(k) end

---参数校验钩（TOB-522）：控制总线 setParam（/params 热更）时同步调用 fn(value)。
---fn 返回 true = 通过；返回 nil/false[, 消息] 或 error = 拒绝（消息回传调用方，旧值继续生效）。
---@param key string
---@param fn fun(value: any): boolean, string?
function params_validator(key, fn) end

-- ==========================================================================
-- persist（实例 SQLite；随实例隔离）
-- ==========================================================================

---@class persist
---限流去重：key 在 window_ms 内已见过则 true（落地即记）
---@param key string
---@param window_ms? number 默认 5000
---@return boolean
function persist.seen(key, window_ms) end

---结构化表：persist.table "Account" { player = "string:key", balance = "number" }
---返回句柄并注入同名全局。find/all/upsert/del/adjust/adjust_if
---@param name string
---@return fun(schema: table): table T
function persist.table(name) end

---KV 存储：persist.kv "Boot" 后 Boot.get(k)/Boot.set(k, v)（并注入全局）
---@param name string
---@return table K
function persist.kv(name) end

---追加日志：persist.log "Ledger" 后 L.append{...}/L.find{...}/L.mark(id, state)
---@param name string
---@return table L
function persist.log(name) end

---事务：txn(function() ... end) 内的 persist 写原子提交；挂起/出错回滚
---@param fn fun()
function txn(fn) end

-- ==========================================================================
-- chat
-- ==========================================================================

---@class chat
---公屏发言（受 boundary.chat.say_rate 令牌桶约束）
---@param text string
function chat.say(text) end

---回复聊天消息：chat.reply(msg, "收到")；单参形式 = 公开回复
---@param m table|string 聊天消息或玩家名
---@param text? string
function chat.reply(m, text) end

---私信别名 = chat.reply(to, text)
---@param to string
---@param text string
function chat.msg(to, text) end

---以命令形式发送（受 boundary.chat.commands 白名单约束）
---@param str string
function chat.run_command(str) end

---挂起等待匹配聊天；命中返回命名分组表，超时返回 nil
---@param rexid userdata
---@param timeout? number 毫秒，默认 5000
---@return table|nil
function chat.await_chat(rexid, timeout) end

---全局别名 = chat.await_chat
---@param rexid userdata
---@param timeout? number
---@return table|nil
function await_chat(rexid, timeout) end

-- ==========================================================================
-- entity（实体句柄：动态字段活读最新缓存）
-- ==========================================================================

---@class entity
---句柄字段：e.id / e.type / e.name / e.pos{x,y,z} / e.health / e.equipment.hand …
---e.snapshot() 取普通表快照；e.exists() 是否仍在视野
---@param e entity
---@return any
function entity.__index(e, k) end

---按谓词找实体列表。pred 见下（type/name/uuid/alive/holding/region/within/function）
---@param pred? table|fun(s: table): boolean
---@return entity[]
function entity.find(pred) end

---最近实体（排除自己）
---@param pred? table|fun(s: table): boolean
---@return entity|nil
function entity.nearest(pred) end

---实体是否存在
---@param e entity|string
---@return boolean
function entity.exists(e) end

-- ==========================================================================
-- world
-- ==========================================================================

---@class world
---方块快照 {name="minecraft:chest", pos=...}；未知方块 => 空表
---@param pos {x: number, y: number, z: number}
---@return table
function world.block(pos) end

---区域扫描：region = [corner1, corner2]；pred 可为 {id="minecraft:*"} 或函数
---@param region {x,y,z}[]
---@param pred? table|fun(b: table): boolean
---@param opts? table
---@return table[] list
function world.scan(region, pred, opts) end

---方块缓存陈旧度（毫秒；-1 未知）
---@param pos {x: number, y: number, z: number}
---@return number
function world.staleness(pos) end

---当前维度 id（如 "minecraft:overworld"）
---@return string|nil
function world.dimension() end

---时间/天气查询（部分宿主未实现，返回 nil —— 用 caps/文档确认）
---@return any
function world.time_of_day() end

---@return any
function world.weather() end

-- ==========================================================================
-- nav（移动；walk 不叫 goto：goto 是 Lua 保留字）
-- ==========================================================================

---@class nav
---寻路走到目标（pos 或 entity）。opts: {arrive=半径(默认1.5), timeout=ms, max_cost, sprint, keep_rotation}
---返回 {ok=bool, reason=?}；失败经 nav.failed 事件与 reason 携带
---@param target {x,y,z}|entity
---@param opts? {arrive?: number, timeout?: number, max_cost?: number, sprint?: boolean, keep_rotation?: boolean}
---@return {ok: boolean, reason?: string}
function nav.walk(target, opts) end

---走到 pos 附近 radius 格内（arrive 语法糖）
---@param pos {x,y,z}
---@param radius number
---@param opts? table
---@return {ok: boolean, reason?: string}
function nav.goto_near(pos, radius, opts) end

---跟随实体直至 stop/取消
---@param e entity|string
---@param opts? table
function nav.follow(e, opts) end

---停止寻路/跟随
function nav.stop() end

---逃离：走到 pos 附近（arrive=2）
---@param pos {x,y,z}
---@param opts? table
function nav.flee(pos, opts) end

---到目标的直线距离
---@param t {x,y,z}|entity
---@return number|nil
function nav.distance_to(t) end

---该位置当前是否可达（可交互）
---@param pos {x,y,z}
---@return boolean
function nav.can_reach(pos) end

---原地跳一下
function nav.hop() end

-- ==========================================================================
-- look（视角；bslib.look.at(t) 可直接瞄准实体/方块）
-- ==========================================================================

---@class look
---@param a number 角度（度）
function look.yaw(a) end

---@param a number
function look.pitch(a) end

---当前视角 {yaw, pitch}
---@return {yaw: number, pitch: number}
function look.current() end

-- ==========================================================================
-- container / 窗口句柄
-- ==========================================================================

---@class win
---win:click(slot, {button="left"|"right", shift=bool})；win.click_hotbar(slot, n)
---win:peek(i) / win:count(filter) / win:find(filter) / win:slots()
---win:size() / win:title() / win:type() / win:cursor() / win:drop_slot(i, {all}) / win:drop_cursor({all})
---@param w win
---@return any
function win.__index(w, k) end

---@class container
---打开容器（箱子/熔炉…），返回窗口句柄；失败返回 nil（经 container.closed/nav.failed 事件）
---@param target {x,y,z}|string
---@return win|nil
function container.open(target) end

---关闭窗口
---@param w win
function container.close(w) end

---当前打开的窗口（无则 nil）
---@return win|nil
function container.current() end

-- ==========================================================================
-- inv（背包；容器打开时 = 引擎合并视图，槽位 0-35 为背包区）
-- ==========================================================================

---@class inv
---背包中匹配物品总数。filter = {id=..., count=最少个数} | 函数 | nil
---@param filter? table|fun(item): boolean
---@return number
function inv.count(filter) end

---@param filter? table|fun(item): boolean
---@return boolean
function inv.has(filter) end

---首个匹配槽位（0-35）；无则 nil
---@param filter? table|fun(item): boolean
---@return number|nil
function inv.find(filter) end

---全部槽位 [{index, item}]
---@return {index: number, item: {id: string, count: number}|nil}[]
function inv.slots() end

---主手物品 {id, count} 或 nil
---@return {id: string, count: number}|nil
function inv.held() end

---底层点击（window "0"；bslib.withdraw/deposit 优先）
---@param slot number
---@param opts? {button?: string|number, shift?: boolean, mode?: number}
function inv.click(slot, opts) end

---光标持物
---@return {id: string, count: number}|nil
function inv.cursor() end

---装备到主手（槽位号或 filter）
---@param slot_or_filter number|table
function inv.equip(slot_or_filter) end

---丢弃（部分宿主为 no-op，用前以 caps/文档确认）
---@param filter table
---@param count? number
function inv.drop(filter, count) end

-- ==========================================================================
-- combat / self / session / caps / data
-- ==========================================================================

---@class combat
---攻击实体（受 boundary.combat 约束：targets/接战距离未授权即拒）
---@param e entity|string
function combat.attack(e) end

---@param e entity|string
function combat.interact_entity(e) end

---攻击冷却 ms（<=0 即可攻击）
---@return number
function combat.cooldown() end

---对方块右键（opts 透传）；place = 用主手物品放置（先 inv.equip）
---@param pos {x,y,z}
---@param opts? table
function combat.use_block(pos, opts) end

---使用/食用主手物品 duration 毫秒
---@param duration? number
function combat.use_item(duration) end

---挖掘方块（受 boundary.blocks.dig 约束）
---@param pos {x,y,z}
function combat.dig(pos) end

---在 pos 上方放置（pos 为支撑面坐标语义见宿主；需主手持材料）
---@param pos {x,y,z}
---@param item? string
function combat.place(pos, item) end

---@class self
---bot 自身用户名（TOB-522 最小暴露；接收门/收款判定默认锚定用）
---@return string|nil
function self.username() end

---@return {x,y,z}|nil
function self.pos() end

---@return number|nil
function self.health() end

---@return number|nil
function self.food() end

---@return {id: string, count: number}|nil
function self.held() end

---@return string|nil
function self.gamemode() end

---@return table|nil
function self.effects() end

---@class session
---"playing" | "paused" | …
---@return string|nil
function session.state() end

---@return table|nil
function session.info() end

---@class caps
---宿主能力位查询（如 caps.has("world_stream")）
---@param flag string
---@return boolean
function caps.has(flag) end

---@return string|nil
function caps.server_version() end

---@class data
---物品堆叠上限（未知默认 64）
---@param id string
---@return number
function data.stack_size(id) end

---武器评分（越高越强；非武器 nil）
---@param id string
---@return number|nil
function data.weapon_score(id) end

-- ==========================================================================
-- bslib（行为策略库；require "bslib" 且全局注入）
-- ==========================================================================

---@class bslib
---瞄准实体/方块位置（含高度补偿）；opts.only = "yaw"|"pitch"
---@param t {pos: {x,y,z}}|entity|{x,y,z}
---@param opts? {only?: string}
function bslib.look.at(t, opts) end

---攻击冷却就绪
---@return boolean
function bslib.is_combat_ready() end

---确保主手持 item_id；nil = 清空主手到背包（满则丢弃）
---@param item_id string|nil
---@return {id: string, count: number}|nil
function bslib.ensure_holding(item_id) end

---装备最优武器（按 data.weapon_score）
---@param kind? string
---@return {id: string, count: number}|nil
function bslib.equip_best(kind) end

---从容器窗口取出 n 个匹配物品（moved<count = 箱满/包满的 bslib 约定）
---@param win win
---@param filter table|fun(item): boolean
---@param n? number 缺省取尽可能多
---@return number moved
function bslib.withdraw(win, filter, n) end

---向容器窗口放入 n 个匹配物品
---@param win win
---@param filter table|fun(item): boolean
---@param n? number
---@return number moved
function bslib.deposit(win, filter, n) end

---多趟跨箱转移（走-开-取-关-走-开-放-关）；返回净转移件数
---@param from {x,y,z}
---@param to {x,y,z}
---@param filter table|fun(item): boolean
---@param opts? {on_open?: fun(pos, win, filter)}
---@return number total
function bslib.move(from, to, filter, opts) end

bslib.try = try
bslib.race = race
bslib.parallel = parallel
bslib.with_timeout = with_timeout
bslib.wait_until = wait_until

-- ==========================================================================
-- 杂项
-- ==========================================================================

---物品过滤匹配（bslib/container/inv 共用语义）
---@param item {id: string, count: number}
---@param filter table|fun(item): boolean|nil
---@return boolean
function item_match(item, filter) end
