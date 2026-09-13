-- tsl_relay.lua — TSL 游戏内 ↔ 平台 绑定/充值中继（与 afk.lua 同实例运行）
-- /msg bot 登录<验证码>  → 控制面绑定角色 + 一次性网页登录链接
-- 转账监听：系统消息命中 transfer_pattern（默认 "X 向 <bot> 转账 N"）→ 平台入账，/msg 回执
-- 全部回复走 /msg 私聊；出站仅限 api_base（bot.yaml net 申明 ∩ 实例边界授权）

local json = require "json"

local function reply(to, text)
  if not to or text == nil or text == '' then return end
  chat.msg(to, tostring(text))
end

local function api(path, payload)
  local status, data = net.jrequest({
    url = tostring(params.api_base) .. path,
    method = 'POST',
    headers = { ['X-Service-Token'] = tostring(params.service_token),
                ['Content-Type'] = 'application/json' },
    body = json.encode(payload or {}),
  })
  if status ~= 200 then return nil, ('HTTP %d %s'):format(status, json.encode(data or {})) end
  return data
end

-- 括号私聊（zenoxs 全系统消息：[发送者 ➥ 接收者] 内容；箭头字形不参与匹配）
local function parse_bracket(msg)
  local head, text = (msg.raw or ''):match('^%[([^%]]+)%]%s*(.+)$')
  if not head then return nil, nil end
  local sender, arrow, recipient = head:match('^(%S+)%s+(%S+)%s+(%S+)$')
  if sender and arrow and recipient == tostring(params.bot_name) and text ~= '' then
    return sender, text:match('^%s*(.-)%s*$')
  end
  return nil, nil
end

on_chat(rex('^'), function(msg)
  local raw = msg.raw or ''
  if raw:match('^%[TSL') then log.info('TSL 原文: %s', raw) end   -- 经济消息审计（识别 pattern 用）
  local sender, text = parse_bracket(msg)
  if sender then
    if not (text:match('^登录') or text:match('^绑定') or text:match('^余额') or text:match('^充值') or text:match('^索取兑换码')) then return end   -- 其余指令归 afk.lua
    if tostring(params.service_token) == '' then
      return reply(sender, '平台服务未就绪（service_token 未配置），请联系管理员')
    end
    local ok, data_or_err = pcall(api, '/api/tsl/cmd', { player = sender, text = text })
    if not ok then
      log.error('tsl cmd 失败: %s', tostring(data_or_err))
      return reply(sender, '平台暂不可用，请稍后再试')
    end
    return reply(sender, data_or_err.reply or '已处理')
  end

  local pat = tostring(params.transfer_pattern)
  if pat == '' then return end
  local payer, amount
  if raw:match('你已支付') then
    -- 付款人视角副本：金额给 bot，但付款人名不在文本里，跳过（等收款人副本）
    return
  end
  payer, amount = raw:match(pat)
  if not payer or not amount then return end
  if tostring(params.service_token) == '' then return end
  local ok, data_or_err = pcall(api, '/api/tsl/transfer', { player = payer, amount = tonumber(amount) })
  if not ok then return log.error('tsl transfer 失败: %s', tostring(data_or_err)) end
  if data_or_err and data_or_err.reply then reply(payer, data_or_err.reply) end
end)

on_start(function()
  if tostring(params.service_token) == '' then
    log.warn('tsl_relay: service_token 未配置（实例参数里设置），登录/充值不生效')
  else
    log.info('tsl_relay 就绪: api=%s', tostring(params.api_base))
  end
end)
