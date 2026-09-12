package com.botscript.mod.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lua 运行时宿主（mod 内嵌版 engine.js 对应物）。
 *
 * 复用 engine/lua 的 bootstrap.lua（调度器/任务/容器句柄，纯 Lua），本类只提供
 * bootstrap 头部约定的 `__` 桥：token 结算、定时器、params、rex、事务、动作分发。
 * 语义与 engine/engine.js 逐一对齐：settle 时序、pause 冻结、Lua 帧内交付排队。
 *
 * 线程模型：所有入口必须在同一游戏线程上调用（客户端 tick / 事件回调均已在主线程）。
 */
public final class LuaHost {

    public interface LogSink {
        void log(String level, String msg);
    }

    /** 动作处理器：返回 String[]{okj, ej} = 同步完成；返回 null = 异步（稍后 host.settle） */
    public interface ActionHandler {
        String[] run(int token, JsonObject args, int taskId) throws Exception;
    }

    /** 同步查询：返回 JSON 字符串或 ""（nil） */
    public interface QueryFunc {
        String apply(JsonObject args);
    }

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private final String name;
    private final LogSink log;
    private final LongSupplier clock;
    private final org.luaj.vm2.Globals globals;

    // ---- token 结算（engine.js settle/deliver 对应物） ----
    private static final class TokenRec {
        boolean settled;
        boolean waiterRegistered;
        boolean detached;
        int taskId;            // 0 = 无归属（fire-and-forget）
        String kind = "";
        String okj = "", ej = "";
    }

    private final Map<Integer, TokenRec> tokens = new HashMap<>();
    private int nextToken = 1;

    // ---- 定时器（sleep token / interval timer / after） ----
    private record Timer(long at, long interval, boolean once, int timerId, Integer token)
            implements Comparable<Timer> {
        @Override public int compareTo(Timer o) { return Long.compare(at, o.at); }
    }

    private final PriorityQueue<Timer> timers = new PriorityQueue<>();
    private final List<Timer> pendingTimers = new ArrayList<>();   // start() 前注册的 interval/after
    private int nextTimerId = 1;

    // ---- 交付门控 ----
    private record Delivery(int taskId, String okj, String ej) {}
    private final Deque<Delivery> pendingDeliveries = new ArrayDeque<>();   // Lua 帧内到达：帧尾再交付
    private final List<Delivery> frozenDeliveries = new ArrayList<>();      // pause 期间到达：恢复后重交付
    private int luaDepth = 0;
    private boolean paused = false;
    private boolean started = false;

    // ---- params / rex / 命令 ----
    private final Map<String, String> paramValues = new HashMap<>();      // k -> json
    private final Map<String, JsonObject> paramSchema = new HashMap<>();  // k -> schema
    private final Map<Integer, Pattern> rexList = new HashMap<>();
    private int nextRex = 1;
    private final List<JsonObject> commandDecls = new ArrayList<>();

    private final Map<String, ActionHandler> actions = new HashMap<>();
    private final Map<String, QueryFunc> queries = new HashMap<>();
    private boolean inTxn = false;

    // ---- P2 扩展点 ----
    public interface Tickable { void tick(); }
    public interface RawFn { LuaValue apply(Varargs a) throws Exception; }
    public static final LuaValue NONE_VALUE = LuaValue.NIL;

    private final List<Tickable> machines = new ArrayList<>();
    private java.util.function.Consumer<String> commandRegistrar;
    private final Runnable[] txnDelegate = new Runnable[3];
    private Policy policy;
    private BotConfig config = new BotConfig();
    private PersistStore persistStore;   // 控制通道 setParam 的持久化落点

    /** 注册每 tick 驱动的状态机（nav/容器操作等长动作）。 */
    public void addMachine(Tickable t) { machines.add(t); }
    public void setCommandRegistrar(java.util.function.Consumer<String> r) { commandRegistrar = r; }
    public void attachPersist(PersistStore store) {
        txnDelegate[0] = store::txnBegin;
        txnDelegate[1] = store::txnCommit;
        txnDelegate[2] = store::txnRollback;
        this.persistStore = store;
        store.registerBridges(this);
    }
    public void setPolicy(Policy p) { policy = p; }
    public Policy policy() { return policy; }
    public void setConfig(BotConfig c) { config = c; }
    public BotConfig config() { return config; }

    /** 注册不经过动作门控的原生桥（persist 等）。 */
    public void setRawBridge(String fn, RawFn f) {
        B bb = new B();
        bb.raw(fn, f);
    }

    public LuaHost(String name, LogSink log, LongSupplier clock) {
        this.name = name;
        this.log = log;
        this.clock = clock;
        this.globals = JsePlatform.standardGlobals();
    }

    // ================= 装载 =================

    /** 预载运行时库模块（require "json"/"bootstrap" 可用）。返回 chunk 的返回值。 */
    public LuaValue preloadModule(String moduleName, String src) {
        LuaValue chunk = loadChunk(src, moduleName + ".lua");
        LuaValue result = chunk.call();
        LuaValue fixed = result.isnil() ? LuaValue.TRUE : result;
        LuaValue pkg = globals.get("package");
        if (!pkg.isnil()) {
            LuaValue preload = pkg.get("preload");
            if (preload.istable()) preload.set(moduleName, new OneArgFunction() {
                @Override public LuaValue call(LuaValue arg) { return fixed; }
            });
            LuaValue loaded = pkg.get("loaded");
            if (loaded.istable()) loaded.set(moduleName, fixed);
        }
        return result;
    }

    /** 从 classpath 装载随 mod 分发的运行时库（/lua/*.lua）。 */
    public void bootRuntimeLibs() {
        preloadModule("json", resource("/lua/json.lua"));
        preloadModule("bslib", resource("/lua/bslib.lua"));
        preloadModule("bootstrap", resource("/lua/bootstrap.lua"));
        exec("require \"bootstrap\"\nbslib = require \"bslib\"", "=runtime");
        registerDefaultBridges();
    }

    private static String resource(String path) {
        try (var in = LuaHost.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("缺少运行时资源 " + path);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 用户脚本装载：同目录其余 .lua 先行预载（require 同目录模块），再执行入口（声明期）。 */
    public void loadScripts(List<ScriptFile> scripts) {
        for (ScriptFile s : scripts) {
            if (!s.entry) preloadModule(moduleName(s.path), s.src);
        }
        for (ScriptFile s : scripts) {
            if (s.entry) exec(s.src, "@" + s.path);
        }
    }

    public record ScriptFile(String path, String src, boolean entry) {}

    private static String moduleName(String path) {
        String p = path.replace('\\', '/');
        p = p.substring(p.lastIndexOf('/') + 1);
        return p.endsWith(".lua") ? p.substring(0, p.length() - 4) : p;
    }

    // ================= 生命周期 =================

    /**
     * 参数绑定（DESIGN §5.3）：包清单 params = schema+默认值（{type,...} 形态）或纯默认值；
     * Lua params{...} 声明的 schema 优先；持久化值（显式设置过的实例事实）最后覆盖。
     * required 未设不阻塞启动（运行期经控制渠道设置后持久化），仅告警。
     */
    public void bindParams(Map<String, JsonElement> initial) {
        Map<String, JsonElement> values = new HashMap<>(initial == null ? Map.of() : initial);
        Map<String, JsonElement> defaults = new HashMap<>();
        for (var e : new LinkedHashMap<>(values).entrySet()) {
            JsonElement v = e.getValue();
            if (v != null && v.isJsonObject() && v.getAsJsonObject().has("type")) {
                JsonObject schema = v.getAsJsonObject();
                if (!paramSchema.containsKey(e.getKey())) paramSchema.put(e.getKey(), schema);
                values.remove(e.getKey());
                if (schema.has("default") && !schema.get("default").isJsonNull()) {
                    defaults.put(e.getKey(), schema.get("default"));
                }
            }
        }
        for (var e : defaults.entrySet()) values.putIfAbsent(e.getKey(), e.getValue());
        for (var e : paramSchema.entrySet()) {
            JsonObject schema = e.getValue();
            String type = schema.has("type") ? schema.get("type").getAsString() : "";
            JsonElement v = values.containsKey(e.getKey())
                    ? values.get(e.getKey())
                    : (schema.has("default") ? schema.get("default") : null);
            if (v == null || v.isJsonNull()) {
                if (schema.has("required") && schema.get("required").getAsBoolean()) {
                    log("warn", "[" + name + "] 参数 " + e.getKey()
                            + " 必填：暂未设置（运行期经控制渠道设置后持久化）");
                }
                continue;
            }
            values.put(e.getKey(), coerce(v, type));
        }
        // 持久化值优先于清单初值（实例事实住持久化，engine.js paramPersist 同语义）
        if (persistStore != null) {
            String saved = persistStore.kvGet("bot", "params");
            if (saved != null && !saved.isEmpty()) {
                try {
                    JsonObject persisted = parseObj(saved);
                    for (var e : persisted.entrySet()) {
                        String type = paramSchema.containsKey(e.getKey())
                                && paramSchema.get(e.getKey()).has("type")
                                ? paramSchema.get(e.getKey()).get("type").getAsString() : "";
                        values.put(e.getKey(), coerce(e.getValue(), type));
                    }
                } catch (Exception ex) {
                    log("warn", "[" + name + "] 持久化参数损坏，忽略: " + ex.getMessage());
                }
            }
        }
        paramValues.clear();
        for (var e : values.entrySet()) paramValues.put(e.getKey(), GSON.toJson(e.getValue()));
    }

    // ---- 控制通道参数面（HTTP /params、GUI 参数页；engine.js setParam 对应物） ----

    /** @return {"schema":{...},"values":{...}} */
    public synchronized String paramsSnapshotJson() {
        JsonObject schema = new JsonObject();
        for (var e : paramSchema.entrySet()) schema.add(e.getKey(), e.getValue());
        JsonObject values = new JsonObject();
        for (var e : paramValues.entrySet()) {
            try { values.add(e.getKey(), JsonParser.parseString(e.getValue())); }
            catch (Exception ignored) { }
        }
        JsonObject out = new JsonObject();
        out.add("schema", schema);
        out.add("values", values);
        return out.toString();
    }

    /** 控制总线统一入口：schema 校验 + 持久化。成功返回 null，失败返回错误 JSON。 */
    public synchronized String setParam(String k, JsonElement v) {
        JsonObject schema = paramSchema.get(k);
        if (schema == null) return errJson("param.unknown", k);
        String type = schema.has("type") ? schema.get("type").getAsString() : "";
        JsonElement coerced = coerce(v, type);
        paramValues.put(k, GSON.toJson(coerced));
        if (persistStore != null) {
            try {
                JsonObject persisted = parseObj(persistStore.kvGet("bot", "params"));
                persisted.add(k, coerced);
                persistStore.kvSet("bot", "params", persisted.toString());
            } catch (Exception ignored) { }
        }
        log("info", "[" + name + "] param " + k + " = " + coerced);
        return null;
    }

    /** 控制通道用：调用返回 string 的 Lua 全局（如 __task_list_full_json）。宿主线程专用。 */
    public String callStringFn(String fn, String arg) {
        LuaValue f = globals.get(fn);
        if (f.isnil()) return null;
        LuaValue r = arg == null ? f.call() : f.call(LuaValue.valueOf(arg));
        return r.isnil() ? null : r.tojstring();
    }

    @SuppressWarnings("unchecked")
    private static JsonElement coerce(JsonElement v, String type) {
        if (type.equals("blockpos") && v.isJsonArray()) {
            var a = v.getAsJsonArray();
            JsonObject o = new JsonObject();
            o.addProperty("x", a.get(0).getAsDouble());
            o.addProperty("y", a.get(1).getAsDouble());
            o.addProperty("z", a.get(2).getAsDouble());
            return o;
        }
        if (type.equals("region") && v.isJsonArray()) {
            var a = v.getAsJsonArray();
            JsonObject o = new JsonObject();
            o.add("corner1", corner(a.get(0)));
            o.add("corner2", corner(a.get(1)));
            return o;
        }
        if (type.equals("list<blockpos>") && v.isJsonArray()) {
            var out = new com.google.gson.JsonArray();
            for (var e : v.getAsJsonArray()) out.add(e.isJsonArray() ? corner(e) : e);
            return out;
        }
        return v;
    }

    private static JsonElement corner(JsonElement e) {
        var a = e.getAsJsonArray();
        JsonObject o = new JsonObject();
        o.addProperty("x", a.get(0).getAsDouble());
        o.addProperty("y", a.get(1).getAsDouble());
        o.addProperty("z", a.get(2).getAsDouble());
        return o;
    }

    /** 启动序：on_start 生命周期 + 定时器生效（engine.js start 6/7 步）。 */
    public void start() {
        started = true;
        event("lifecycle", "{\"kind\":\"on_start\"}");
        long now = clock.getAsLong();
        for (Timer t : pendingTimers) {
            // interval 字段对 once(after) 携带 delay；统一以注册时刻为基准排期
            scheduleTimer(new Timer(now + t.interval(), t.interval(), t.once(), t.timerId(), t.token()));
        }
        pendingTimers.clear();
        log("info", "[" + name + "] 引擎启动完成 driver=internal paused=" + paused);
    }

    // ================= 每 tick 驱动 =================

    /** 游戏线程每 tick（或测试每步）调用：触发到期定时器。 */
    public void tick() {
        for (Tickable m : new ArrayList<>(machines)) {
            try { m.tick(); } catch (Exception e) { log("error", "[" + name + "] machine 失败: " + e); }
        }
        long now = clock.getAsLong();
        List<Timer> due = new ArrayList<>();
        while (!timers.isEmpty() && timers.peek().at() <= now) due.add(timers.poll());
        for (Timer t : due) {
            if (t.token() != null) {
                settle(t.token(), "", "");           // time.sleep 到期
            } else {
                String kind = t.once() ? "after" : "timer";
                event(kind, "{\"id\":" + t.timerId() + "}");
                if (!t.once()) scheduleTimer(new Timer(now + t.interval(), t.interval(), false, t.timerId(), null));
            }
        }
    }

    private void scheduleTimer(Timer t) { timers.add(t); }

    // ================= token 结算 =================

    public int newToken(String kind) {
        int token = nextToken++;
        TokenRec rec = new TokenRec();
        rec.kind = kind;
        tokens.put(token, rec);
        return token;
    }

    public void settle(int token, String okj, String ej) {
        TokenRec rec = tokens.get(token);
        if (rec == null || rec.settled) return;
        rec.settled = true;
        rec.okj = okj == null ? "" : okj;
        rec.ej = ej == null ? "" : ej;
        if (rec.waiterRegistered) deliverToken(token);
    }

    private void deliverToken(int token) {
        TokenRec rec = tokens.remove(token);
        if (rec == null) return;
        if (rec.detached) return;
        if (rec.taskId == 0) return;
        deliver(rec.taskId, rec.okj, rec.ej);
    }

    private void deliver(int taskId, String okj, String ej) {
        Delivery d = new Delivery(taskId, okj == null ? "" : okj, ej == null ? "" : ej);
        if (paused) { frozenDeliveries.add(d); return; }
        if (luaDepth > 0) { pendingDeliveries.add(d); return; }
        callLua("__core_resume", LuaValue.valueOf(taskId), LuaValue.valueOf(d.okj()), LuaValue.valueOf(d.ej()));
    }

    // ================= 事件 / 生命周期 =================

    public void event(String kind, String payloadJson) {
        callLua("__core_event", LuaValue.valueOf(kind), LuaValue.valueOf(payloadJson));
        flushPending();
    }

    public void pause(String reason) {
        if (paused) return;
        paused = true;
        frozenDeliveries.clear();   // 先清陈旧冻结，再结算在途动作（其交付将重新入冻结队列）
        // 未结算的动作统一以 runtime.paused 交付（await 处抛错，任务可捕获）
        for (var e : new ArrayList<>(tokens.entrySet())) {
            TokenRec rec = e.getValue();
            if ("action".equals(rec.kind) && !rec.settled) {
                settle(e.getKey(), "", errJson("runtime.paused", reason));
            }
        }
        log("warn", "[" + name + "] 暂停: " + reason);
        event("lifecycle", "{\"kind\":\"on_pause\"}");
    }

    public void resume() {
        if (!paused) return;
        paused = false;
        event("lifecycle", "{\"kind\":\"on_resume\"}");
        var list = new ArrayList<>(frozenDeliveries);
        frozenDeliveries.clear();
        for (Delivery d : list) deliver(d.taskId(), d.okj(), d.ej());
        flushPending();
    }

    public boolean isPaused() { return paused; }

    private void flushPending() {
        while (luaDepth == 0 && !pendingDeliveries.isEmpty()) {
            Delivery d = pendingDeliveries.poll();
            callLua("__core_resume", LuaValue.valueOf(d.taskId()), LuaValue.valueOf(d.okj()), LuaValue.valueOf(d.ej()));
        }
    }

    // ================= 桥注册 =================

    private void callLua(String fn, LuaValue... args) {
        luaDepth++;
        try {
            LuaValue f = globals.get(fn);
            if (f.isnil()) throw new IllegalStateException("Lua 全局缺失: " + fn);
            f.invoke(LuaValue.varargsOf(args));
        } catch (LuaError e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            log("error", "[" + name + "] Lua 调用 " + fn + " 失败: " + c.getMessage());
        } finally {
            luaDepth--;
        }
    }

    private LuaValue loadChunk(String src, String chunkName) {
        Reader r = new StringReader(src);
        try {
            return globals.load(r, chunkName);
        } catch (LuaError e) {
            throw new IllegalArgumentException("Lua 编译失败 " + chunkName + ": " + e.getMessage());
        }
    }

    /** 顶层执行用户脚本（编译错误抛出；运行错误降级为日志）。 */
    public void exec(String src, String chunkName) {
        luaDepth++;
        try {
            loadChunk(src, chunkName).call();
        } catch (LuaError e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            log("error", "[" + name + "] 脚本执行失败 " + chunkName + ": " + c.getMessage());
        } finally {
            luaDepth--;
        }
    }

    /** /eval 的 glue（engine.js EVAL_GLUE 与此保持一字不差）：把代码注册为具名任务并 spawn。 */
    static final String EVAL_GLUE = """
        local src, n = __EVAL_CODE, __EVAL_NAME
        __EVAL_CODE, __EVAL_NAME = nil, nil
        local c, err = load(src, "=eval:" .. n)
        if not c then error(err) end
        __DECL.tasks[n] = { opts = {}, fn = function()
          local r = c()
          if r ~= nil then log.info("[eval:" .. n .. "] => " .. tostring(r)) end
        end }
        task.spawn(n)""";

    /**
     * 一次性脚本（HTTP /eval，2026-09-10 用户改拍板：控制面提供 one-shot Lua）：
     * 与包脚本同环境同边界（动作仍经 Policy），作为具名任务运行——可 await 动作、
     * /tasks 可见可取消、返回值/失败走日志。编译失败抛 IllegalArgumentException（HTTP 400）。
     */
    public void evalOnce(String name, String code) {
        loadChunk(code, "=eval:" + name);   // 编译校验：失败直接抛出
        globals.set("__EVAL_CODE", code);
        globals.set("__EVAL_NAME", name);
        exec(EVAL_GLUE, "=eval");
    }

    private void registerDefaultBridges() {
        B b = new B();

        // ---- 基础 ----
        b.one("__log", a -> { log(a.checkjstring(1), "[" + name + "] " + a.checkjstring(2)); return NONE; });
        b.one("__now", a -> LuaValue.valueOf(clock.getAsLong()));
        b.one("__fire_token", a -> {
            TokenRec rec = tokens.get((int) a.checklong(1));
            if (rec != null) rec.detached = true;
            return NONE;
        });

        // ---- 调度 ----
        b.three("__core_register_waiter", a -> {
            TokenRec rec = tokens.get((int) a.checklong(2));
            if (rec == null) return NONE;
            rec.waiterRegistered = true;
            rec.taskId = (int) a.checklong(1);
            if (rec.settled) deliverToken((int) a.checklong(2));
            return NONE;
        });
        b.one("__sleep_register", a -> {
            JsonObject o = parseObj(a.checkjstring(1));
            long ms = o.has("ms") ? o.get("ms").getAsLong() : 0;
            int token = newToken("sleep");
            timers.add(new Timer(Math.max(0, clock.getAsLong() + ms), 0, true, 0, token));
            return LuaValue.valueOf(token);
        });
        b.one("__new_waiter", a -> {
            JsonObject o = parseObj(a.checkjstring(1));
            long timeout = o.has("timeout") ? o.get("timeout").getAsLong() : 5000;
            int token = newToken("waiter");
            timers.add(new Timer(Math.max(0, clock.getAsLong() + timeout), 0, true, 0, token));
            return LuaValue.valueOf(token);
        });
        b.two("__resolve_waiter", a -> {
            int token = (int) a.checklong(1);
            TokenRec rec = tokens.get(token);
            if (rec != null && !rec.settled) settle(token, a.isnil(2) ? "" : a.checkjstring(2), "");
            return NONE;
        });

        // ---- rex（java.util.regex 对应 JS 正则；命名分组 <=> JSON） ----
        b.one("__rex_new", a -> {
            int id = nextRex++;
            rexList.put(id, Pattern.compile(a.checkjstring(1)));
            return LuaValue.valueOf(id);
        });
        b.two("__rex_match", a -> {
            Pattern p = rexList.get((int) a.checklong(1));
            String text = a.checkjstring(2);
            if (p == null || text == null) return LuaValue.valueOf("");
            Matcher m = p.matcher(text);
            if (!m.find()) return LuaValue.valueOf("");
            JsonObject groups = new JsonObject();
            for (String gname : namedGroups(p)) {
                String g = m.group(gname);
                groups.addProperty(gname, g == null ? null : g);
            }
            return LuaValue.valueOf(GSON.toJson(groups));
        });

        // ---- 定时器 ----
        b.one("__timer_register", a -> {
            JsonObject o = parseObj(a.checkjstring(1));
            long interval = Math.max(50, o.has("interval") ? o.get("interval").getAsLong() : 1000);
            int id = nextTimerId++;
            Timer t = new Timer(0, interval, false, id, null);
            if (started) scheduleTimer(new Timer(clock.getAsLong() + interval, interval, false, id, null));
            else pendingTimers.add(t);
            return LuaValue.valueOf(id);
        });
        b.one("__after_register", a -> {
            JsonObject o = parseObj(a.checkjstring(1));
            long delay = Math.max(0, o.has("delay") ? o.get("delay").getAsLong() : 0);
            int id = nextTimerId++;
            Timer t = new Timer(0, delay, true, id, null);   // interval 字段为 once 携带 delay
            if (started) scheduleTimer(new Timer(clock.getAsLong() + delay, 0, true, id, null));
            else pendingTimers.add(t);
            return LuaValue.valueOf(id);
        });

        // ---- params ----
        b.one("__params_decl", a -> {
            JsonObject schema = parseObj(a.checkjstring(1));
            for (String k : schema.keySet()) paramSchema.put(k, schema.getAsJsonObject(k));
            return NONE;
        });
        b.one("__param_get", a -> {
            String j = paramValues.get(a.checkjstring(1));
            return LuaValue.valueOf(j == null ? "" : j);
        });

        // ---- 命令声明 ----
        b.one("__command_register", a -> {
            if (commandRegistrar != null) commandRegistrar.accept(a.checkjstring(1));
            return NONE;
        });

        // ---- 事务（默认标记；attachPersist 后委托 store） ----
        b.zero("__txn_begin", () -> { inTxn = true; if (txnDelegate[0] != null) txnDelegate[0].run(); return NONE; });
        b.zero("__txn_commit", () -> { inTxn = false; if (txnDelegate[1] != null) txnDelegate[1].run(); return NONE; });
        b.zero("__txn_rollback", () -> { inTxn = false; if (txnDelegate[2] != null) txnDelegate[2].run(); return NONE; });

        // ---- 查询分发：注册过的走 provider，未注册的返回 "" ----
        for (String q : List.of("__q_self", "__q_look", "__q_entities", "__q_entity_snap", "__q_block",
                "__q_window", "__q_current_window", "__q_distance", "__q_can_reach", "__q_cooldown",
                "__q_staleness", "__q_dimension", "__q_time_of_day", "__q_weather", "__q_session_state",
                "__q_session_info", "__q_caps", "__q_server_version", "__q_stack_size", "__q_weapon_score")) {
            String name0 = q;
            b.one(q, a -> {
                QueryFunc f = queries.get(name0);
                if (f == null) return LuaValue.valueOf("");
                try {
                    return LuaValue.valueOf(f.apply(parseObj(a.checkjstring(1))));
                } catch (Exception e) {
                    log("error", "[" + name + "] 查询 " + name0 + " 失败: " + e);
                    return LuaValue.valueOf("");
                }
            });
        }
    }

    public void setQuery(String name, QueryFunc f) { queries.put("__q_" + name, f); }

    public void registerAction(String name, ActionHandler h) {
        actions.put(name, h);
        final String actionName = name;
        globals.set("__act_" + actionName, new TwoArgFunction() {
            @Override public LuaValue call(LuaValue argsJ, LuaValue taskId) {
                JsonObject args = parseObj(argsJ.checkjstring());
                int id = taskId.isint() ? taskId.checkint() : 0;
                return LuaValue.valueOf(execAction(actionName, args, id));
            }
        });
    }

    /** engine.js actionEntry 对应物：token 分配 + 动作执行（错误经 token 交付）。 */
    private int execAction(String name, JsonObject args, int taskId) {
        int token = newToken("action");
        // 策略门控（engine.js actionEntry 对应）：权限/围栏/预算/dry_run
        if (policy != null) {
            String polErr = policy.checkAction(name, args);
            if (polErr != null) { settle(token, "", polErr); return token; }
            if (config.dryRun) {
                log("info", "[" + name + "][dry] " + name + " " + args);
                settle(token, "{}", "");
                return token;
            }
            if (policy.actionCount()) pause("budget");
        }
        try {
            ActionHandler h = actions.get(name);
            if (h == null) {
                settle(token, "", errJson("action.unknown", name));
                return token;
            }
            String[] r = h.run(token, args, taskId);
            if (r != null) settle(token, r[0], r[1]);
        } catch (Exception e) {
            settle(token, "", errJson("action.error", String.valueOf(e.getMessage())));
        }
        return token;
    }

    // ================= 小工具 =================

    private final B b = this.new B();

    private final class B {
        interface F1 { LuaValue apply(Varargs a) throws Exception; }
        interface F0 { LuaValue apply() throws Exception; }

        void one(String n, F1 f) { globals.set(n, new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) { return guard(n, () -> f.apply(a)); }
        }); }
        void two(String n, F1 f) { one(n, f); }
        void three(String n, F1 f) { one(n, f); }
        void zero(String n, F0 f) { globals.set(n, new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) { return guard(n, () -> f.apply()); }
        }); }
        void vari(String n, java.util.function.Function<Varargs, LuaValue> f) {
            globals.set(n, new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) { return guard(n, () -> f.apply(a)); }
            });
        }

        void raw(String n, RawFn f) {
            globals.set(n, new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) { return guard(n, () -> f.apply(a)); }
            });
        }

        LuaValue guard(String n, java.util.concurrent.Callable<LuaValue> c) {
            try {
                LuaValue v = c.call();
                return v == null ? NONE : v;
            } catch (LuaError e) {
                throw e;
            } catch (Exception e) {
                log("error", "[bridge:" + n + "] " + e);
                throw new LuaError(e);
            }
        }
    }

    private static final LuaValue NONE = LuaValue.NIL;

    private static final Pattern NAMED_GROUP = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>");

    private static List<String> namedGroups(Pattern p) {
        List<String> out = new ArrayList<>();
        Matcher m = NAMED_GROUP.matcher(p.pattern());
        while (m.find()) if (!out.contains(m.group(1))) out.add(m.group(1));
        return out;
    }

    public static JsonObject parseObj(String j) {
        // json.lua 把空表编码为 "[]"（数组形态）；引擎侧 JS 只是取字段（undefined），
        // 这里等价容忍：非对象一律按空对象处理
        if (j == null || j.isBlank()) return new JsonObject();
        JsonElement e = JsonParser.parseString(j);
        return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    public static String errJson(String kind, String detail) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("detail", detail == null ? "" : detail);
        return GSON.toJson(o);
    }

    public static String toJson(Object o) { return GSON.toJson(o); }

    private void log(String level, String msg) { log.log(level, msg); }

    // 供测试/调试：取 Lua 全局
    public LuaValue global(String name) { return globals.get(name); }
}
