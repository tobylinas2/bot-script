package com.botscript.mod;

import com.botscript.mod.runtime.BotConfig;
import com.botscript.mod.runtime.CommandBus;
import com.botscript.mod.runtime.LuaHost;
import com.botscript.mod.runtime.PersistStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * HTTP 控制通道（CAPABILITIES §17：宿主常开能力，非配置项；mineflayer 宿主 engine/httpapi.js 对应物）。
 * 只绑 127.0.0.1；默认端口冲突自动顺延；token 启动时自动生成并打印到日志；
 * 覆盖仅经宿主启动参数（env：BOTSCRIPT_HTTP_PORT / BOTSCRIPT_HTTP_TOKEN）。
 * 无 /eval：观察、params、命令（同一命令总线、同一 authority 鉴权）；写操作以 console 级身份进入总线。
 * 端点与载荷 schema 与 engine 宿主完全一致（MCP 工具不感知宿主差异）。
 *
 * 线程模型：HTTP 线程只做解析/鉴权，所有游戏/Lua 访问经 MinecraftClient.submit 编组到客户端线程。
 */
public final class HttpApi {

    private static final int DEFAULT_PORT = 25580;
    private static final int MAX_BODY = 1 << 20;

    /** 结构化日志环（/logs 增量拉取；由宿主 LogSink 喂入） */
    public record LogEntry(long ts, String level, String msg) {}

    private final java.util.function.Supplier<LuaHost> hostRef;
    private final java.util.function.Supplier<CommandBus> busRef;
    private final java.util.function.Supplier<PersistStore> persistRef;
    private final java.util.function.Supplier<BotConfig> cfgRef;
    private final java.util.function.Supplier<String> pkgNameRef;
    private final java.util.function.Supplier<McRuntime> runtimeRef;
    /** 运行时控制（热重载/包列表）由 mod 提供：只有 mod 知道 inWorld 与 config 写回 */
    private volatile java.util.function.Function<String, JsonObject> reloadFn;
    private volatile java.util.function.Supplier<JsonObject> packagesFn;
    private final List<LogEntry> logRing = new ArrayList<>();

    private HttpServer server;
    private String token;
    private int port;

    public HttpApi(java.util.function.Supplier<LuaHost> hostRef,
                   java.util.function.Supplier<CommandBus> busRef,
                   java.util.function.Supplier<PersistStore> persistRef,
                   java.util.function.Supplier<BotConfig> cfgRef,
                   java.util.function.Supplier<String> pkgNameRef,
                   java.util.function.Supplier<McRuntime> runtimeRef) {
        this.hostRef = hostRef;
        this.busRef = busRef;
        this.persistRef = persistRef;
        this.cfgRef = cfgRef;
        this.pkgNameRef = pkgNameRef;
        this.runtimeRef = runtimeRef;
    }

    /** 宿主日志喂入口（LogSink tee）。 */
    public synchronized void recordLog(String level, String msg) {
        logRing.add(new LogEntry(System.currentTimeMillis(), level, msg));
        if (logRing.size() > 500) logRing.remove(0);
    }

    /** mod 侧注册运行时控制：reload 参数=目标包名（null/空=当前包），返回结果 JSON */
    public void setRuntimeControl(java.util.function.Function<String, JsonObject> reloadFn,
                                  java.util.function.Supplier<JsonObject> packagesFn) {
        this.reloadFn = reloadFn;
        this.packagesFn = packagesFn;
    }

    /** 常开：mod 初始化即启动（与是否进世界无关）。 */
    public void start(org.slf4j.Logger log) throws IOException {
        String portEnv = System.getenv("BOTSCRIPT_HTTP_PORT");
        int base = 0;
        try { base = Integer.parseInt(portEnv); } catch (Exception ignored) { }
        if (base <= 0) base = DEFAULT_PORT;
        token = System.getenv("BOTSCRIPT_HTTP_TOKEN");
        if (token == null || token.isEmpty()) {
            token = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", base), 0);
        port = server.getAddress().getPort();
        server.createContext("/", this::route);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        log.info("botscript HTTP 控制通道 http://127.0.0.1:{} token={}（env BOTSCRIPT_HTTP_PORT/TOKEN 可覆盖）",
                port, token);
    }

    public int port() { return port; }
    public String token() { return token; }

    // ================= 路由（HTTP 线程） =================

    private void route(HttpExchange ex) throws IOException {
        try {
            Map<String, String> query = new HashMap<>();
            String rawQ = ex.getRequestURI().getRawQuery();
            if (rawQ != null) {
                for (String kv : rawQ.split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) query.put(urlDecode(kv.substring(0, eq)), urlDecode(kv.substring(eq + 1)));
                }
            }
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            boolean authorized = (auth != null && auth.equals("Bearer " + token))
                    || token.equals(query.get("token"));
            if (!authorized) {
                send(ex, 401, obj("error", "unauthorized").toString());
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String method = ex.getRequestMethod();
            JsonObject body = new JsonObject();
            if (method.equals("POST")) {
                byte[] bytes = ex.getRequestBody().readNBytes(MAX_BODY);
                String rawTxt = new String(bytes, StandardCharsets.UTF_8);
                if (!rawTxt.isBlank()) body = JsonParser.parseString(rawTxt).getAsJsonObject();
            }
            JsonObject out = dispatch(path, method, body, query);
            send(ex, out.has("__status") ? out.remove("__status").getAsInt() : 200, out.toString());
        } catch (Exception e) {
            JsonObject err = obj("error", "bad request");
            err.addProperty("detail", String.valueOf(e.getMessage()));
            try { send(ex, 400, err.toString()); } catch (Exception ignored) { }
        } finally {
            ex.close();
        }
    }

    private JsonObject dispatch(String path, String method, JsonObject body, Map<String, String> query) {
        return switch (path) {
            case "/state" -> state();
            case "/params" -> params(method, body);
            case "/cmd" -> cmd(method, body);
            case "/eval" -> eval(method, body);
            case "/tasks" -> obj("tasks", tasksArray());
            case "/tasks/cancel" -> cancel(method, body);
            case "/logs" -> logs(Long.parseLong(query.getOrDefault("since", "0")));
            case "/persist" -> persist(query);
            case "/caps" -> caps();
            case "/reload" -> reload(method, body);
            case "/packages" -> packages();
            default -> status(404, obj("error", "not found"));
        };
    }

    /**
     * GUI 进程内入口（§16.5：GUI 只消费与 HTTP 同源的接口，页面层零业务逻辑）。
     * 客户端线程调用（从渲染线程 submit 会内联执行，无死锁）；不做鉴权。
     */
    public synchronized JsonObject localCall(String path, String method, JsonObject body) {
        try {
            JsonObject out = dispatch(path, method, body == null ? new JsonObject() : body, Map.of());
            out.remove("__status");
            return out;
        } catch (Exception e) {
            JsonObject err = obj("error", "local call failed");
            err.addProperty("detail", String.valueOf(e.getMessage()));
            return err;
        }
    }

    // ================= 端点（客户端线程编组） =================

    private <T> T onClient(java.util.function.Supplier<T> fn) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.isOnThread()) return fn.get();   // 渲染线程调用（GUI localCall）：内联执行
        try {
            return mc.submit(fn).get(6, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("client thread 编组失败: " + c.getMessage(), c);
        }
    }

    private JsonObject state() {
        return onClient(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("name", pkgNameRef.get());
            o.addProperty("host", "fabric-mod");
            var p = MinecraftClient.getInstance().player;
            boolean playing = p != null;
            o.addProperty("session", playing ? "playing" : "idle");
            JsonObject info = new JsonObject();
            if (playing) {
                info.addProperty("brand", MinecraftClient.getInstance().getCurrentServerEntry() != null
                        ? "server" : "singleplayer");
                info.addProperty("dimension", MinecraftClient.getInstance().world != null
                        ? MinecraftClient.getInstance().world.getRegistryKey().getValue().toString() : null);
            }
            o.add("session_info", info);
            LuaHost host = hostRef.get();
            o.addProperty("paused", host != null && host.isPaused());
            o.addProperty("pause_reason", host != null && host.isPaused() ? "paused" : null);
            if (playing) {
                JsonObject self = new JsonObject();
                JsonObject pos = new JsonObject();
                pos.addProperty("x", p.getX());
                pos.addProperty("y", p.getY());
                pos.addProperty("z", p.getZ());
                self.add("pos", pos);
                self.addProperty("yaw", p.getYaw());
                self.addProperty("pitch", p.getPitch());
                self.addProperty("health", p.getHealth());
                self.addProperty("food", p.getHungerManager().getFoodLevel());
                String mode = MinecraftClient.getInstance().interactionManager != null
                        ? MinecraftClient.getInstance().interactionManager.getCurrentGameMode().name()
                            .toLowerCase(java.util.Locale.ROOT) : null;
                self.addProperty("gamemode", mode);
                String heldId = p.getMainHandStack().isEmpty() ? null
                        : Registries.ITEM.getId(p.getMainHandStack().getItem()).toString();
                self.addProperty("held", heldId);
                o.add("self", self);
            } else {
                o.add("self", null);
            }
            o.add("tasks", tasksArray());
            McRuntime rt = runtimeRef.get();
            o.addProperty("window", rt != null ? rt.currentWindowId() : null);
            o.add("caps", capsFlags());
            return o;
        });
    }

    private JsonObject params(String method, JsonObject body) {
        if (method.equals("GET")) {
            LuaHost host = hostRef.get();
            if (host == null) {
                // 未进世界：至少回包清单 schema（无法给生效值）
                JsonObject values = new JsonObject();
                BotConfig cfg = cfgRef.get();
                if (cfg != null) {
                    for (var e : cfg.params.entrySet()) values.add(e.getKey(), e.getValue());
                }
                JsonObject snap = obj("schema", manifestSchema());
                snap.add("values", values);
                return snap;
            }
            String snap = onClient(host::paramsSnapshotJson);
            return JsonParser.parseString(snap).getAsJsonObject();
        }
        if (method.equals("POST")) {
            LuaHost host = hostRef.get();
            if (host == null) return status(400, obj("error", "not booted"));
            JsonObject changed = new JsonObject();
            for (var e : body.entrySet()) {
                String err = onClient(() -> host.setParam(e.getKey(), e.getValue()));
                if (err != null) {
                    JsonObject rejection = JsonParser.parseString(err).getAsJsonObject();
                    rejection.addProperty("key", e.getKey());
                    return status(400, rejection);
                }
                changed.addProperty(e.getKey(), true);
            }
            JsonObject ok = obj("ok", true);
            ok.add("changed", changed);
            return ok;
        }
        return status(405, obj("error", "method not allowed"));
    }

    private JsonObject cmd(String method, JsonObject body) {
        if (!method.equals("POST")) return status(405, obj("error", "method not allowed"));
        String text = body.has("cmd") ? body.get("cmd").getAsString() : "";
        if (text.isBlank()) return status(400, obj("error", "cmd required"));
        CommandBus bus = busRef.get();
        if (bus == null) return status(400, obj("error", "not booted"));
        boolean matched = onClient(() -> bus.dispatch(text, null, true));
        JsonObject ok = obj("ok", true);
        ok.addProperty("matched", matched);
        return ok;
    }

    /** 一次性 Lua（2026-09-10 用户改拍板）：代码作为具名任务进入运行时，同环境同边界。 */
    private JsonObject eval(String method, JsonObject body) {
        if (!method.equals("POST")) return status(405, obj("error", "method not allowed"));
        String code = body.has("code") ? body.get("code").getAsString() : "";
        if (code.isBlank()) return status(400, obj("error", "code required"));
        LuaHost host = hostRef.get();
        if (host == null) return status(400, obj("error", "not booted"));
        String name = body.has("name") && !body.get("name").getAsString().isBlank()
                ? body.get("name").getAsString() : "eval-" + System.currentTimeMillis() % 100000;
        try {
            onClient(() -> {
                host.evalOnce(name, code);
                return true;
            });
        } catch (RuntimeException e) {
            JsonObject err = obj("error", "lua.compile");
            err.addProperty("detail", String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            return status(400, err);
        }
        JsonObject ok = obj("ok", true);
        ok.addProperty("task", name);
        return ok;
    }

    private JsonArray tasksArray() {
        LuaHost host = hostRef.get();
        if (host == null) return new JsonArray();
        String j = onClient(() -> host.callStringFn("__task_list_full_json", null));
        if (j == null || j.isEmpty()) return new JsonArray();
        return JsonParser.parseString(j).getAsJsonArray();
    }

    /** POST /reload {"package": "..."}（可选）：热重载脚本包，可选切换包（写回 config） */
    private JsonObject reload(String method, JsonObject body) {
        if (!method.equals("POST")) return status(405, obj("error", "method not allowed"));
        var fn = reloadFn;
        if (fn == null) return status(409, obj("error", "reload handler 未注册"));
        String pkg = body != null && body.has("package") && !body.get("package").isJsonNull()
                ? body.get("package").getAsString() : null;
        return onClient(() -> fn.apply(pkg));   // 编组到客户端线程：stop/boot 必须在 tick 线程
    }

    /** GET /packages：botscript/ 下可切换的脚本包列表 + 当前包 */
    private JsonObject packages() {
        var fn = packagesFn;
        if (fn == null) return status(409, obj("error", "packages handler 未注册"));
        return onClient(fn);
    }

    private JsonObject cancel(String method, JsonObject body) {
        if (!method.equals("POST")) return status(405, obj("error", "method not allowed"));
        if (!body.has("name")) return status(400, obj("error", "name required"));
        LuaHost host = hostRef.get();
        if (host == null) return status(400, obj("error", "not booted"));
        String j = onClient(() -> host.callStringFn("__task_cancel_by_name", body.get("name").getAsString()));
        if (j == null) {
            JsonObject nf = obj("ok", false);
            nf.addProperty("found", false);
            return nf;
        }
        return JsonParser.parseString(j).getAsJsonObject();
    }

    private synchronized JsonObject logs(long since) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (LogEntry e : logRing) {
            if (e.ts() > since) {
                JsonObject l = new JsonObject();
                l.addProperty("ts", e.ts());
                l.addProperty("level", e.level());
                l.addProperty("msg", e.msg());
                arr.add(l);
            }
        }
        o.add("logs", arr);
        o.add("actions", new JsonArray());   // schema 对齐 engine 宿主（mod 动作流水见 /logs 日志环）
        return o;
    }

    private JsonObject persist(Map<String, String> query) {
        PersistStore store = persistRef.get();
        if (store == null) return status(400, obj("error", "not booted"));
        String table = query.get("table");
        String kv = query.get("kv");
        if (table != null) {
            try {
                String rows = onClient(() -> store.all(table));
                JsonObject o = obj("table", table);
                o.add("rows", rows.isEmpty() ? new JsonArray() : JsonParser.parseString(rows).getAsJsonArray());
                return o;
            } catch (Exception e) {
                JsonObject err = obj("error", "persist.query");
                err.addProperty("detail", String.valueOf(e.getMessage()));
                return status(400, err);
            }
        }
        if (kv != null) {
            String values = onClient(() -> store.kvAll(kv));
            JsonObject o = obj("kv", kv);
            o.add("values", values.isEmpty() ? new JsonObject() : JsonParser.parseString(values).getAsJsonObject());
            return o;
        }
        return onClient(store::listNames);
    }

    private JsonObject caps() {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("name", pkgNameRef.get());
        BotConfig cfg = cfgRef.get();
        JsonObject params = new JsonObject();
        if (cfg != null) for (var e : cfg.params.entrySet()) params.add(e.getKey(), e.getValue());
        manifest.add("params", params);
        JsonObject o = obj("host", "fabric-mod");
        o.addProperty("runtime", "luaj (embedded)");
        o.add("flags", capsFlags());
        o.add("manifest", manifest);
        o.add("boundary", boundaryView());
        return o;
    }

    /** 边界只读视图（GUI 边界页数据源）：当前实例授权了什么（§16.5） */
    private JsonObject boundaryView() {
        BotConfig cfg = cfgRef.get();
        JsonObject b = new JsonObject();
        b.addProperty("configured", cfg != null && cfg.boundaryConfigured);
        if (cfg == null) return b;
        if (cfg.fenceMin != null) {
            JsonArray fence = new JsonArray();
            JsonArray a = new JsonArray(); a.add(cfg.fenceMin[0]); a.add(cfg.fenceMin[1]);
            JsonArray c = new JsonArray(); c.add(cfg.fenceMax[0]); c.add(cfg.fenceMax[1]);
            fence.add(a); fence.add(c);
            b.add("fence", fence);
        }
        b.addProperty("blocks_dig", cfg.blocksDig != null ? cfg.blocksDig : "未授权");
        b.addProperty("blocks_place", cfg.blocksPlace != null ? cfg.blocksPlace : "未授权");
        b.addProperty("combat_targets", cfg.combatTargets != null ? cfg.combatTargets : "未授权");
        if (cfg.combatMaxEngage != null) b.addProperty("combat_max_engage", cfg.combatMaxEngage);
        b.addProperty("say_rate", cfg.chatSayRate);
        StringBuilder wl = new StringBuilder();
        for (String w : cfg.chatCommandsWhitelist) wl.append(w.isEmpty() ? w : " ").append(w);
        b.addProperty("chat_commands", wl.toString().trim());
        StringBuilder own = new StringBuilder();
        for (String w : cfg.authorityOwner) own.append(w.isEmpty() ? w : " ").append(w);
        b.addProperty("authority_owner", own.toString().trim());
        StringBuilder op = new StringBuilder();
        for (String w : cfg.authorityOp) op.append(w.isEmpty() ? w : " ").append(w);
        b.addProperty("authority_op", op.toString().trim());
        return b;
    }

    /** 与 McRuntime caps 查询同源的 flags（+ui.gui，§16.5） */
    private JsonObject capsFlags() {
        JsonObject f = new JsonObject();
        f.addProperty("nav.pathfinder", false);
        f.addProperty("world_stream", false);
        f.addProperty("entity.equipment", true);
        f.addProperty("entity.holding", true);
        f.addProperty("input.move", true);
        f.addProperty("window.click_authentic", true);
        f.addProperty("hud.full", true);
        f.addProperty("ui.gui", true);
        return f;
    }

    /** 包清单 schema 的 Gson 形态（未进世界时 /params GET 的兜底） */
    private JsonObject manifestSchema() {
        JsonObject schema = new JsonObject();
        BotConfig cfg = cfgRef.get();
        if (cfg != null) {
            for (var e : cfg.params.entrySet()) {
                if (e.getValue() != null && e.getValue().isJsonObject()
                        && e.getValue().getAsJsonObject().has("type")) {
                    schema.add(e.getKey(), e.getValue());
                }
            }
        }
        return schema;
    }

    // ================= JSON 小工具 =================

    private static JsonObject obj(String k, Object v) {
        JsonObject o = new JsonObject();
        if (v instanceof String s) o.addProperty(k, s);
        else if (v instanceof Boolean b) o.addProperty(k, b);
        else if (v instanceof Number n) o.addProperty(k, n);
        else o.add(k, (com.google.gson.JsonElement) v);
        return o;
    }

    private static JsonObject status(int code, JsonObject o) {
        o.addProperty("__status", code);
        return o;
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
