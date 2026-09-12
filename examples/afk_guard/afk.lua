-- ============================================================
-- afk_guard —— 挂机看家 bot
-- 所有交互走 /msg 私聊，永不公屏发言：
--   /msg bot status                任何人：查状态
--   /msg bot admin add <name>      超管：添加管理员
--   /msg bot admin del <name>      超管：删除管理员
--   /msg bot admin list            超管/管理员：列出管理员
--   /msg bot lock | unlock         超管/管理员：锁定后 tpa/tpahere 不再自动接受
-- tpa 自动接受：聊天（系统提示）命中关键词 → /tpaccept <玩家>，并 /msg 告知对方
-- 超管 = params.super_admin；管理员清单与锁定状态持久化（重启保留）
-- 服务端私聊/传送提示格式各异：whisper_marker 与两个关键词均为 params，可在平台可视化编辑
-- ============================================================

params {
  super_admin     = { type = 'string',  default = '',  visible = true, help = '超级管理员玩家名（唯一）' },
  auto_accept_tpa = { type = 'boolean', default = true, visible = true, help = '未锁定时自动接受 tpa/tpahere' },
  anti_afk        = { type = 'boolean', default = true, visible = true, help = '定期微调视角防挂机检测' },
  whisper_marker  = { type = 'string',  default = 'whispers to you:,whispers:', visible = true, help = '私聊识别标记（逗号分隔）' },
  tpa_to_keyword  = { type = 'string',  default = '请求传送到你身边', visible = true, help = 'tpa 请求关键词' },
  tpahere_keyword = { type = 'string',  default = '请求你传送', visible = true, help = 'tpahere 请求关键词' },
  tp_accept_cmd   = { type = 'string',  default = '/tpaccept {player}', visible = true, help = '接受传送命令模板（{player} 占位）' },
  debug_chat      = { type = 'boolean', default = true, visible = true, help = '在日志输出每条收到的聊天（诊断用）' },
}

persist.kv "State"                                        -- State.get/set（locked 等）
persist.table "Admins" { player = 'string:key', added_by = 'string', at = 'number' }

-- ---------- 工具 ----------

local function super() return tostring(params.super_admin or '') end

local function is_admin(name)
  if name == '' then return false end
  if name == super() then return true end
  return Admins.find{ player = name } ~= nil
end

local function locked() return State.get('locked') == true end

local function reply(to, text) chat.msg(to, text) end

local function markers()
  local list = {}
  for m in string.gmatch(tostring(params.whisper_marker or ''), '[^,]+') do
    m = m:match('^%s*(.-)%s*$')
    if m ~= '' then list[#list + 1] = m end
  end
  return list
end

-- 私聊解析：raw 命中任一标记 → 标记后的文本为命令，发送者取 driver 解析结果或标记前的行首词
local function parse_whisper(msg)
  for _, marker in ipairs(markers()) do
    local i = string.find(msg.raw or '', marker, 1, true)
    if i then
      local text = string.match(string.sub(msg.raw, i + #marker), '^%s*(.-)%s*$')
      local sender = msg.sender
      if not sender or sender == '' then
        sender = string.match(string.sub(msg.raw, 1, i - 1) or '', '(%S+)%s*$')
      end
      if text ~= '' and sender and sender ~= '' then return sender, text end
      return nil
    end
  end
  return nil
end

-- ---------- 命令处理 ----------

local function handle_whisper(from, text)
  local words = {}
  for w in string.gmatch(text, '%S+') do words[#words + 1] = w end
  local cmd = words[1]

  if cmd == 'status' then
    local n = 0
    for _ in Admins.all() do n = n + 1 end
    reply(from, string.format('afk bot[%s] | 管理员 %d 人 | tpa自动接受 %s',
      locked() and '已锁定' or '未锁定', n,
      (params.auto_accept_tpa ~= false) and '开' or '关'))

  elseif cmd == 'lock' or cmd == 'unlock' then
    if not is_admin(from) then reply(from, '需要管理员权限') return end
    State.set('locked', cmd == 'lock')
    reply(from, cmd == 'lock' and '已锁定：tpa/tpahere 不再自动接受' or '已解锁：tpa/tpahere 自动接受')

  elseif cmd == 'admin' then
    if from ~= super() then reply(from, '仅超级管理员可管理管理员名单') return end
    local sub, name = words[2], words[3]
    if sub == 'add' and name then
      if Admins.find{ player = name } then reply(from, name .. ' 已经是管理员') return end
      Admins.upsert{ player = name, added_by = from, at = time.now() }
      reply(from, '已添加管理员: ' .. name)
      if name ~= from then reply(name, '你已被 ' .. from .. ' 设为 afk bot 管理员（/msg bot lock|unlock）') end
    elseif sub == 'del' and name then
      if not Admins.find{ player = name } then reply(from, name .. ' 不是管理员') return end
      Admins.del{ player = name }
      reply(from, '已删除管理员: ' .. name)
    elseif sub == 'list' then
      local names = {}
      for row in Admins.all() do names[#names + 1] = row.player end
      reply(from, '超管: ' .. (super() ~= '' and super() or '（未配置）')
        .. ' | 管理员: ' .. (#names > 0 and table.concat(names, ', ') or '无'))
    else
      reply(from, '用法: admin add <name> | admin del <name> | admin list')
    end

  else
    reply(from, 'afk bot 命令: status | lock | unlock | admin add <name> | admin del <name> | admin list')
  end
end

-- ---------- tpa 自动接受 ----------

local function handle_tpa(msg)
  if locked() then return end                          -- 锁定：不自动接受（请求自然超时）
  if params.auto_accept_tpa == false then return end
  local raw = msg.raw or ''
  local kind
  if raw:find(tostring(params.tpa_to_keyword), 1, true) then kind = 'tpa'
  elseif raw:find(tostring(params.tpahere_keyword), 1, true) then kind = 'tpahere' end
  if not kind then return end
  local name = raw:match('^(%S+)')                     -- 请求者名 = 提示行行首词
  if not name or name == '' then return end
  if persist.seen('tpa:' .. name .. ':' .. kind, 8000) then return end   -- 多行提示去重
  chat.run_command((tostring(params.tp_accept_cmd)):gsub('{player}', name))
  reply(name, '已自动接受你的 ' .. kind .. ' 请求')
end

-- ---------- 入口 ----------

on_chat(rex'^', function(msg)
  if params.debug_chat ~= false then
    log.info('chat kind=%s from=%s | raw=%s', tostring(msg.sender_kind), tostring(msg.sender), tostring(msg.raw):sub(1, 90))
  end
  local from, text = parse_whisper(msg)
  if from then
    handle_whisper(from, text)
    return
  end
  handle_tpa(msg)
end)

on_start(function()
  log.info('afk_guard 启动: 超管=%s | tpa自动接受=%s | 防挂机=%s | 锁定=%s',
    super() ~= '' and super() or '未配置',
    tostring(params.auto_accept_tpa ~= false),
    tostring(params.anti_afk ~= false),
    tostring(locked()))
end)

-- 防挂机：每 45s 微调视角（0.2°），锁定与否都执行（挂机是本职）
task('anti_afk', { single = true }, function()
  local flip = false
  while true do
    time.sleep(45000)
    if params.anti_afk ~= false then
      flip = not flip
      look.yaw(flip and 0.2 or -0.2)
    end
  end
end)
