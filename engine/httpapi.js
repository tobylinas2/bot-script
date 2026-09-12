// httpapi.js — HTTP 控制通道（CAPABILITIES §17：宿主常开能力，非配置项）
// 只绑 127.0.0.1；默认端口冲突自动顺延；token 启动时自动生成并打印到控制台。
// 绑定覆盖仅经宿主启动参数（env：BOTSCRIPT_HTTP_PORT / BOTSCRIPT_HTTP_TOKEN）。
// 无 /eval：只有观察、params、命令（同一命令总线、同一 authority 鉴权）。
import http from 'node:http';
import crypto from 'node:crypto';

const DEFAULT_PORT = 25580;
const MAX_BODY = 1 << 20;

export function startHttpApi(engine, { log = console.log } = {}) {
  const portEnv = Number(process.env.BOTSCRIPT_HTTP_PORT);
  const basePort = Number.isFinite(portEnv) && portEnv > 0 ? portEnv : DEFAULT_PORT;
  const token = process.env.BOTSCRIPT_HTTP_TOKEN
    || crypto.randomUUID().replaceAll('-', '').slice(0, 24);

  // 结构化日志环（/logs 增量拉取）
  const logRing = [];
  const origLog = engine.log.bind(engine);
  engine.log = (level, msg) => {
    logRing.push({ ts: Date.now(), level, msg });
    if (logRing.length > 500) logRing.shift();
    origLog(level, msg);
  };

  const json = (res, code, obj) => {
    const body = JSON.stringify(obj);
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(body);
  };

  const readBody = (req) => new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) { reject(new Error('body too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });

  const server = http.createServer(async (req, res) => {
    try {
      const u = new URL(req.url, 'http://127.0.0.1');
      const route = u.pathname.replace(/\/+$/, '') || '/';
      // 鉴权：Authorization: Bearer <token> 或 ?token=
      const auth = req.headers.authorization ?? '';
      const qToken = u.searchParams.get('token') ?? '';
      if (auth !== `Bearer ${token}` && qToken !== token) {
        return json(res, 401, { error: 'unauthorized' });
      }

      switch (route) {
        case '/state': {
          const s = engine.self;
          return json(res, 200, {
            name: engine.name,
            host: 'mineflayer',
            session: engine.sessionState,
            session_info: engine.sessionInfo,
            paused: engine.paused,
            pause_reason: engine.pauseReason,
            self: s ? {
              pos: s.pos, yaw: s.yaw, pitch: s.pitch,
              health: s.health, food: s.food, gamemode: s.gamemode,
              held: s.held ?? null,
            } : null,
            tasks: engine.taskListFull(),
            window: engine.currentWindowId,
            caps: engine.driver.caps?.() ?? {},
          });
        }

        case '/params': {
          if (req.method === 'GET') {
            return json(res, 200, {
              schema: Object.fromEntries(engine.paramsSchema),
              values: Object.fromEntries(engine.paramValues),
            });
          }
          if (req.method === 'POST') {
            const body = JSON.parse((await readBody(req)) || '{}');
            const changed = {};
            for (const [k, v] of Object.entries(body)) {
              try {
                engine.setParam(k, v);
                changed[k] = engine.paramValues.get(k);
              } catch (e) {
                return json(res, 400, { error: 'param.rejected', key: k, detail: String(e?.detail ?? e?.message ?? e) });
              }
            }
            return json(res, 200, { ok: true, changed });
          }
          return json(res, 405, { error: 'method not allowed' });
        }

        case '/cmd': {
          if (req.method !== 'POST') return json(res, 405, { error: 'method not allowed' });
          const body = JSON.parse((await readBody(req)) || '{}');
          const cmd = String(body.cmd ?? '');
          if (!cmd.trim()) return json(res, 400, { error: 'cmd required' });
          // token 持有者 = 实例操作者：console 级权限进同一命令总线
          const matched = engine.dispatchCommand(cmd, null, true);
          return json(res, 200, { ok: true, matched });
        }

        case '/eval': {
          // 一次性 Lua（2026-09-10 用户改拍板）：代码作为具名任务进入运行时，同环境同边界
          if (req.method !== 'POST') return json(res, 405, { error: 'method not allowed' });
          const body = JSON.parse((await readBody(req)) || '{}');
          const code = String(body.code ?? '');
          if (!code.trim()) return json(res, 400, { error: 'code required' });
          const name = String(body.name ?? '').trim() || `eval-${Date.now() % 100000}`;
          try {
            await engine.evalOnce(name, code);
            return json(res, 200, { ok: true, task: name });
          } catch (e) {
            return json(res, 400, { error: 'lua.compile', detail: String(e?.message ?? e) });
          }
        }

        case '/tasks':
          return json(res, 200, { tasks: engine.taskListFull() });

        case '/tasks/cancel': {
          if (req.method !== 'POST') return json(res, 405, { error: 'method not allowed' });
          const body = JSON.parse((await readBody(req)) || '{}');
          if (!body.name) return json(res, 400, { error: 'name required' });
          return json(res, 200, engine.cancelTask(String(body.name)));
        }

        case '/logs': {
          const since = Number(u.searchParams.get('since') ?? 0) || 0;
          return json(res, 200, {
            logs: logRing.filter((e) => e.ts > since),
            actions: engine.actionLog.filter((e) => e.ts > since),
          });
        }

        case '/persist': {
          const table = u.searchParams.get('table');
          const kv = u.searchParams.get('kv');
          try {
            if (table) return json(res, 200, { table, rows: engine.persist.all(table) });
            if (kv) return json(res, 200, { kv, values: engine.persist.kvAll(kv) });
            return json(res, 200, engine.persist.listTables());
          } catch (e) {
            return json(res, 400, { error: 'persist.query', detail: String(e?.message ?? e) });
          }
        }

        case '/caps': {
          return json(res, 200, {
            host: 'mineflayer',
            runtime: `node ${process.version}`,
            flags: engine.driver.caps?.() ?? {},
            manifest: {
              name: engine.cfg.name ?? engine.name,
              scripts: engine.cfg.scripts ?? [],
              params: engine.cfg.params ?? {},
              persist: engine.cfg.persist ?? null,
            },
          });
        }

        default:
          return json(res, 404, { error: 'not found' });
      }
    } catch (e) {
      return json(res, 400, { error: 'bad request', detail: String(e?.message ?? e) });
    }
  });

  return new Promise((resolve, reject) => {
    let port = basePort;
    let attempts = 0;
    const tryListen = () => {
      attempts++;
      server.once('error', (e) => {
        if (e.code === 'EADDRINUSE' && attempts <= 20) {
          port++;
          tryListen();
        } else {
          reject(e);
        }
      });
      server.listen(port, '127.0.0.1', () => {
        server.removeAllListeners('error');
        log('info', `[http] 控制通道 http://127.0.0.1:${port} token=${token}`);
        resolve({ port, token, server });
      });
    };
    tryListen();
  });
}
