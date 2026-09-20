-- relay.lua — 平台开放 API 事件桥（通用管道；TSL 业务逻辑全部在平台侧）
-- 脚本 → 平台：POST /api/v1/instances/<instance_id>/report（X-Api-Key = params.api_key）
--   tsl_cmd   {player, text}  bot 私聊指令（平台识别 TSL 指令，回复走响应 actions）
--   chat_line {raw}           经济/系统原文（[TSL 前缀行；平台侧留日志用）
--   transfer  {payer, amount} 收款结构化上报（引擎侧判定，TOB-522）
-- 平台 → 脚本：事件 {type='actions', data={actions=[...]}} → msg/say/command
-- 凭据：api_key（实例参数）；instance_id 平台部署时自动注入；任一为空 = 桥禁用

local json = require "json"

local function ready()
  return tostring(params.api_base or '') ~= ''
     and tostring(params.api_key or '') ~= ''
     and tostring(params.instance_id or '') ~= ''
end

-- ---------- 收款判定（TOB-522：热更参数在引擎侧完成，默认参数部署即用） ----------

local TRANSFER_PATTERN_MAX = 256
local TRANSFER_PROBE = '你收到了来自 Steve 的 12.5 C'   -- 探测串：实测收款副本格式

-- 数 Lua pattern 的捕获数：扫描非转义的 '('；跳过 %bxy 与 %f[set] 的后续元素
local function count_captures(pat)
  local n, i, len = 0, 1, #pat
  while i <= len do
    local c = pat:sub(i, i)
    if c == '%' then
      local nx = pat:sub(i + 1, i + 1)
      if nx == 'b' then i = i + 3                     -- %bxy：两个分隔符字符
      elseif nx == 'f' then                            -- %f[set]：跳到集合结束
        local j = pat:find(']', i + 3, true)
        i = j and (j + 1) or (len + 1)
      else i = i + 2 end
    elseif c == '(' then
      n = n + 1
      i = i + 1
    else
      i = i + 1
    end
  end
  return n
end

-- 热更校验（安全硬约束）：长度上限 / 合法 Lua pattern / 恰好 2 个捕获（1=玩家名 2=金额）。
-- 非法值在 /params 更新时被拒绝并回传可读错误，旧值继续生效；空值 = 关闭收款判定。
params_validator('transfer_pattern', function(v)
  v = tostring(v or '')
  if v == '' then return true end
  if #v > TRANSFER_PATTERN_MAX then
    return nil, string.format('过长（>%d 字节），疑似恶意输入', TRANSFER_PATTERN_MAX)
  end
  local ok, payer, amount = pcall(string.match, TRANSFER_PROBE, v)
  if not ok then return nil, '不是合法 Lua pattern: ' .. tostring(payer) end
  local n = count_captures(v)
  if n ~= 2 then
    return nil, string.format('需要恰好 2 个捕获（1=玩家名 2=金额），实际 %d 个', n)
  end
  if payer ~= nil and (payer == '' or amount == nil or amount == '') then
    return nil, '探测串命中但捕获值为空（1=玩家名 2=金额）'
  end
  return true
end)

-- bot 名：显式 bot_name 优先；空 = 锚定 bot 自身用户名（TOB-520 吸收：默认零手改）
local function bot_name_or_self()
  local n = tostring(params.bot_name or '')
  if n ~= '' then return n end
  return tostring(self.username() or '')
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

-- 收款判定：transfer_pattern 命中 → 结构化 {type='transfer', data={payer, amount}} 上报；
-- 与 chat_line 不互斥（原文照旧上报留日志）。金额 ≤0 或解析失败静默跳过。
local function report_transfer(raw)
  local pat = tostring(params.transfer_pattern or '')
  if pat == '' then return end
  local ok, payer, amount = pcall(string.match, raw, pat)
  if not ok then
    log.warn('relay transfer_pattern 运行期异常（已跳过本行）: %s', tostring(payer))
    return
  end
  local n = tonumber(amount)
  if not payer or payer == '' or not n or n <= 0 then return end
  local ok2, err = pcall(report, { type = 'transfer', data = { payer = payer, amount = n } })
  if not ok2 then log.error('relay transfer 异常: %s', tostring(err)) end
end

-- 括号私聊（zenoxs 全系统消息：[发送者 ➥ 接收者] 内容；箭头字形不参与匹配）
local function parse_private(msg)
  local head, text = (msg.raw or ''):match('^%[([^%]]+)%]%s*(.+)$')
  if not head then return nil end
  local sender, arrow, recipient = head:match('^(%S+)%s+(%S+)%s+(%S+)$')
  if sender and arrow and recipient == bot_name_or_self() and text ~= '' then
    return sender, text:match('^%s*(.-)%s*$')
  end
  return nil
end

on_chat(rex'^', function(msg)
  if not ready() then return end
  local raw = msg.raw or ''
  report_transfer(raw)   -- 收款判定（与 chat_line 不互斥：原文照旧上报留日志）
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
