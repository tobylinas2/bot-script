-- ============================================================
-- 整理 bot（organizer.lua）—— 入口脚本：声明层 + 任务 + 控制命令
-- 职责：输入箱 → 不满盒（partial）/ 库存箱（stock）分流
-- 包结构：organizer.lua（声明层仅允许在此） + routing.lua（去向决策模块）
-- ============================================================

params {
  input          = { type = "blockpos", required = true, visible = true,
                     help = "待整理物品的输入箱" },
  stock_zone     = { type = "region",   required = true,
                     help = "库存/不满盒所在区域（冷启动扫描范围）" },
  partial_chests = { type = "list<blockpos>", default = {}, visible = true,
                     help = "手工登记的不满盒（控制通道可增删，持久化）" },
  paused         = { type = "bool", default = false, visible = true, help = "暂停整理" },
}

persist.table "ChestNote" {
  pos  = "blockpos:key",   -- 主键：箱子位置
  role = "string",         -- "partial" | "stock"
  item = "string?",        -- 绑定物品 id（stock 必有；partial 首次转移后绑定）
  free = "number?",        -- 剩余空间估算；负数 = 未验证
}

persist.kv "meta"

local routing = require "routing"      -- 本包内模块（package.searchers 已限定实例目录）

-- ---------- 生命周期 ----------
on_start(function()
  if not caps.has("nav.pathfinder") then
    log.warn("当前驱动无寻路器（caps.nav.pathfinder=false），goto 走直线退化路径")
  end
end)

-- 冷启动：等世界缓存灌入（session.ready = playing + 首圈 chunk）再扫描，
-- 避免启动过早扫到空缓存后写 cold_start_done 永久跳过（仅一次）
local cold_start
cold_start = function()
  local n = 0
  for _, pos in ipairs(world.scan(params.stock_zone, { type = "container" }, { limit = 200 })) do
    routing.register_unknown(pos)
    n = n + 1
  end
  for _, pos in ipairs(params.partial_chests) do
    routing.add_partial(pos)
  end
  if n == 0 then
    -- 世界尚未灌入（session.ready 尽力而为）：5s 后重扫，登记过再落闩
    log.warn("冷启动扫描为空：世界可能尚未灌入，5s 后重试")
    after(sec(5), cold_start)
    return
  end
  meta.set("cold_start_done", true)
  log.info("冷启动完成：登记 %d 个容器", n)
end

on_event("session.ready", function()
  if meta.get("cold_start_done") then return end
  task.spawn(cold_start)
end)

on_timer(sec(30), function()
  if params.paused then return end
  task.spawn("organize")                           -- 声明为 single，上一轮未结束则丢弃本轮
end)

on_pause(function() routing.reset_pending() end)   -- 冻结/断线自动 pause：free 全部标记未验证

-- ---------- 控制命令（游戏内 / CLI / WS 同一总线、同一鉴权） ----------
on_command("partial add <pos:blockpos>", { perm = "op" }, function(a)
  routing.add_partial(a.pos)
  chat.reply("已登记不满盒 " .. tostring(a.pos))
end)

on_command("partial del <pos:blockpos>", { perm = "op" }, function(a)
  routing.del(a.pos)
  chat.reply("已移除 " .. tostring(a.pos))
end)

on_command("stock add <pos:blockpos> <item:item>", { perm = "op" }, function(a)
  routing.add_stock(a.pos, a.item)
  chat.reply("已登记库存箱 " .. tostring(a.pos) .. " ← " .. a.item)
end)

on_command("stock del <pos:blockpos>", { perm = "op" }, function(a)
  routing.del(a.pos)
  chat.reply("已移除 " .. tostring(a.pos))
end)

on_command("organize once", { perm = "op" }, function()
  task.spawn("organize")
end)

-- ---------- 主任务 ----------
task("organize", { single = true }, function()
  -- 1) 读取输入箱，按物品种类去重（每种类一轮独立转移）
  local w = container.open(params.input)
  local kinds = {}
  for _, s in ipairs(w:slots()) do
    if s.item then kinds[s.item.id] = true end
  end
  container.close(w)

  -- 2) 逐种类转移；单个失败不影响整轮（try 捕获类型化错误，取消自动重抛）
  for id in pairs(kinds) do
    if params.paused then break end

    local ok, err = bslib.try(function()
      local plan = routing.plan_for(id)            -- 决策在模块：缓存优先，开箱校验兜底
      if not plan then
        log.warn("无去向: %s（请补充库存箱或用 partial add 登记）", id)
        return
      end
      local moved = bslib.move(params.input, plan.pos, { id = id }, {
        -- 多趟复合：走-开-取-关-走-开-放-关 循环；中途箱满则停在 moved<请求
        on_open = function(dst, win, filter) routing.validate(plan.pos, win, filter) end,
      })
      routing.after_transfer(plan, id, moved)      -- 回写缓存（含"不满盒已满"转库存箱）
    end)
    if not ok then
      log.warn("转移 %s 失败: %s", id, err.kind or tostring(err))
    end
  end
  log.info("本轮整理完成")
end)
