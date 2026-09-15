-- relay.lua — 平台开放 API 事件桥（通用管道；TSL 业务逻辑全部在平台侧）
-- 脚本 → 平台：POST /api/v1/instances/<instance_id>/report（X-Api-Key = params.api_key）
--   tsl_cmd   {player, text}  bot 私聊指令（平台识别 TSL 指令，回复走响应 actions）
--   chat_line {raw}           经济/系统原文（[TSL 前缀行；转账识别在平台侧）
-- 平台 → 脚本：事件 {type='actions', data={actions=[...]}} → msg/say/command
-- 凭据：api_key（实例参数）；instance_id 平台部署时自动注入；任一为空 = 桥禁用

local json = require "json"

local function ready()
  return tostring(params.api_base or '') ~= ''
     and tostring(params.api_key or '') ~= ''
     and tostring(params.instance_id or '') ~= ''
end

local function apply(actions)
  for _, a in ipairs(actions or {}) do
    if a.type == 'msg' and a.to then
      chat.msg(tostring(a.to), tostring(a.text or ''))
    elseif a.type == 'say' then
      chat.say(tostring(a.text or ''))
    elseif a.type == 'command' then
      chat.run_command(tostring(a.text or ''))
    else
      log.warn('relay 未知 action: %s', json.encode(a))
    end
  end
end

local function report(payload)
  local status, data = net.jrequest({
    url = tostring(params.api_base) .. '/api/v1/instances/' .. tostring(params.instance_id) .. '/report',
    method = 'POST',
    headers = { ['X-Api-Key'] = tostring(params.api_key), ['Content-Type'] = 'application/json' },
    body = json.encode(payload or {}),
  })
  if status ~= 200 then
    log.warn('relay report 失败: HTTP %s %s', tostring(status), json.encode(data or {}))
    return
  end
  apply(data and data.actions)
end

-- 括号私聊（zenoxs 全系统消息：[发送者 ➥ 接收者] 内容；箭头字形不参与匹配）
local function parse_private(msg)
  local head, text = (msg.raw or ''):match('^%[([^%]]+)%]%s*(.+)$')
  if not head then return nil end
  local sender, arrow, recipient = head:match('^(%S+)%s+(%S+)%s+(%S+)$')
  if sender and arrow and recipient == tostring(params.bot_name) and text ~= '' then
    return sender, text:match('^%s*(.-)%s*$')
  end
  return nil
end

on_chat(rex'^', function(msg)
  if not ready() then return end
  local raw = msg.raw or ''
  if raw:match('^%[TSL') then
    log.info('TSL 原文: %s', raw)   -- 经济消息审计（平台 pattern 校准用）
    local ok, err = pcall(report, { type = 'chat_line', data = { raw = raw } })
    if not ok then log.error('relay chat_line 异常: %s', tostring(err)) end
    return
  end
  local sender, text = parse_private(msg)
  if sender then
    local ok, err = pcall(report, { type = 'tsl_cmd', data = { player = sender, text = text } })
    if not ok then log.error('relay tsl_cmd 异常: %s', tostring(err)) end
  end
end)

-- 平台下发事件：{type='actions', data={actions=…}}；其余类型留日志观察
task('relay_events', { single = true }, function()
  while true do
    local ev = events.next(30000)
    if ev then
      if ev.type == 'actions' then
        local ok, err = pcall(apply, ev.data and ev.data.actions)
        if not ok then log.error('relay 事件动作异常: %s', tostring(err)) end
      else
        log.warn('relay 收到未支持事件类型: %s', tostring(ev.type))
      end
    end
  end
end)

on_start(function()
  task.spawn('relay_events')
  log.info('relay 桥: %s（api_base=%s）',
    ready() and '启用' or '禁用（缺 api_key/instance_id）',
    tostring(params.api_base))
end)
