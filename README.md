# bot-script 运行指南

[![CI](https://github.com/tobyprime/bot-script/actions/workflows/ci.yml/badge.svg)](https://github.com/tobyprime/bot-script/actions/workflows/ci.yml)

设计文档见 `DESIGN.md`（语言/运行时/部署）与 `CAPABILITIES.md`（能力目录/驱动协议/§16 客户端 mod）。
本文档说明如何把实现跑起来。

## 目录结构（实现）

```
engine/            mineflayer 宿主（Lua VM + 调度 + 持久化 + 策略 + 命令总线 + 暂停门控）
  engine.js          引擎核心（wasm Lua 状态机、token/yield 协议、驱动桥）
  httpapi.js         HTTP 控制通道（§17：127.0.0.1 + 自动 token 常开，九端点（含 /eval 一次性 Lua））
  cli.js             运行器：node engine/cli.js <deploy.yaml>；validate 子命令 = 包静态校验
  lua/               bootstrap.lua（调度/声明层/能力包装）+ bslib.lua（行为策略库）+ json.lua
                     + bot-api.d.lua（编辑器补全声明，EmmyLua/LuaLS，不参与运行）
  test/              run.js（15 项冒烟测试，mock 驱动，含边界/HTTP 断言）+ mock-driver.js
drivers/
  mineflayer/        mineflayer 驱动（进程内；L1 通道 + 14 动词 + pathfinder L3）
mod/                fabric 宿主（v2 standalone：Luaj 内嵌运行时 + HttpApi + GUI 六页（状态/任务/参数/边界/日志/脚本）；
                    脚本页：热重载 / 切包（写回 config）/ 拖拽导入提示；快捷键 K 一键启停 key.botscript.toggle；
                    .lua 拖进游戏窗口 = 导入当前包，含 bot.yaml 的文件夹 = 导入为独立包，导入后自动热重载）
examples/           验收场景（bank / organizer / coal_guard / heart / tour 自验证巡检；bot.yaml = 包能力自述）
run/                实例部署配置（package/server/account/boundary；test/live.js 生成）
test/live.js        实连验收（本地 Paper 服务器 + 三个 bot 全链路 + HTTP 断言）
tools/rcon.mjs      最小 RCON 客户端（服务器管理用）
```

## 快速开始

环境：Node >= 24（需 node:sqlite）、Java 21+、可访问互联网（首次 npm install）。

```bash
npm install                      # wasmoon / mineflayer / mineflayer-pathfinder / yaml / ws

# 1) 冒烟测试（无需 Minecraft，mock 驱动全栈）
node engine/test/run.js

# 1.5) 脚本包静态校验（不连服务器、不执行脚本：清单 lint + params 形状 + Lua 5.4 编译）
node engine/cli.js validate examples/tour

#     编辑器补全：把 engine/lua/bot-api.d.lua 加入 VS Code 工作区
#     （Lua Language Server（sumneko）或 EmmyLua 插件即获得全 API 补全与类型提示）

# 2) 跑一个 bot（v2 三层分离：包清单在 examples/bank/，部署配置给连接+边界）
node engine/cli.js run/bank-demo.yaml
#   控制台命令（console 权限）：pause / resume / status / quit
#   HTTP 控制通道自动开启（CAPABILITIES §17）：127.0.0.1 + token，启动日志打印；
#     curl -H "Authorization: Bearer <token>" http://127.0.0.1:<port>/state   # /params /cmd /tasks /logs /persist /caps
#   实例业务参数不在任何配置里：运行期 POST /params 设置后持久化，重启保留。

# 3) fabric 宿主（standalone，无需任何外部进程）
cd mod && gradle build
#   产物：mod/build/libs/botscript-mod-0.1.0.jar -> 放入客户端实例 mods/ 目录
#   脚本包：<游戏目录>/botscript/<包名>/（整包复制，含 bot.yaml 清单）
#   实例配置：<游戏目录>/config/botscript-mod.json:
#     {"package": "bank", "boundary": {"authority": {"owner": ["你的玩家名"]}}}
#   进世界自动加载；游戏内 /bsclient 打开管理界面（§16.5 五页）。
```

## 实连验收（本地 Paper 服务器）

```bash
cd G:/data/minecraft/bot-test-server
java -Xms512M -Xmx1024M -XX:+UseParallelGC -jar paper-1.21.8-60.jar nogui
#   server.properties: online-mode=false, spawn-protection=0, rcon 开启(25575/botscript)
```

```bash
node test/live.js     # 构建场景 + 启动 bank/organizer/coal_guard + 断言（含 HTTP 无头闭环）
```

验收项：bank 入账/seen 去重/转账/取款挂账+回执核销/私聊余额（5 项）；
organizer 冷启动扫描+organize once 分流（2 项）；coal_guard 举煤追击+放下即停+guard stop（2 项）；
内建 pause/resume（1 项）；HTTP 控制通道（§17）鉴权/state/params/cmd/tasks/cancel/logs/persist/caps（3 项）。

## 实现要点（与设计文档的对应）

- 调度：Lua 协程 + 就绪队列；yield 协议 `<token>|<kind>|<txn>`；事件处理器为脱离根
  （完成后不级联取消其 spawn 的任务）；race/parallel 的 join 状态全在 Lua 侧
  （wasmoon 的 JS->Lua 调用是无保护 lua_callk，顶层 Lua 错误会 wasm abort，故入口全部内嵌 pcall）。
- 挂起点交付：`ej` 空串视同无错误（Lua 中空串为真值）；取消交付先跑 on_cancel 清理钩
  （fire 模式，不挂起），再注入 `task.cancelled`。
- 暂停：引擎级门控——pause 后所有挂起点交付冻结于队列；进行中的动作 settle 为
  `runtime.paused`；resume 冲刷队列于冻结点重抛；pause 同时停走 + 取消寻路 + 告警。
- persist：node:sqlite（WAL）；表 schema 支持 `string/number/blockpos` 与 `:key`、`?` 后缀；
  `adjust_if` 在 Lua 侧读-判-写（协程单线程 + 同步原语，无挂起点，天然原子）。
- L1 合帧：mineflayer 驱动 self 200ms / 实体 250ms / 窗口 50ms（全量快照、最后值胜）。
- 窗口布局：驱动/引擎统一把 mineflayer 玩家背包（craft0-4/armor5-8/main9-35/hotbar36-44/off45）
  归一化为引擎布局（hotbar 0-8 / main 9-35 / armor 36-39 / offhand 40）；
  容器窗口 = 容器区 [0,size) + 玩家区（main 在 size..size+26，hotbar 在 size+27..size+35）。
- 物品 id：统一规范名 `minecraft:x`（驱动侧 CANON，脚本侧直接比较）。
- nav 超时单位 = 秒（与设计样例 `timeout = 3` 一致）；walk 失败返回 `{ok=false, reason}`
  而非抛错（样例语义：目标在动，由循环自然重试），nav.failed 事件同步发出。
  API 命名 `nav.walk`（不叫 goto：goto 是 Lua 保留字，点号访问是语法错误）；
  无源码预处理/改写层，用户代码所见即所得，报错行号即真实行号。
- 任务取消全路径留痕：显式取消/父完成级联/race 败者/超时统一记一条
  `任务 [名] 取消（来源）` 日志，静默消失可审计。
- keep_rotation：直线退化模式下引擎不碰朝向；寻路器模式下由 pathfinder 自行控制。

## 已知限制（v1）

- fabric 驱动无 world_stream：`world.block` 走引擎缓存 + block_update 事件（定点查询降级）。
- fabric 驱动 goto 走引擎直线退化（move_input + 跳跃），复杂地形显式 `nav.unreachable`。
- 挖掘以 `attackBlock` 起手、`cancelBlockBreaking` 取消；完成确认的 block_update 事件链为简化版。
- 抢占 `{steal=true}`、看门狗自动取消（P2/后续）；fabric 端热重载已实现（GUI 脚本页 / POST /reload，
  客户端线程 stop+boot 原地重载，连接不断）。
