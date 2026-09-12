package com.botscript.mod.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** P1 无头回归：用真实 bootstrap.lua/bslib.lua/json.lua 验证调度协议与桥语义。 */
class LuaHostTest {

    // ---- 工具 ----

    static final class Clock implements LongSupplier {
        long now = 1_000_000;
        @Override public long getAsLong() { return now; }
        void advance(long ms) { now += ms; }
    }

    static final class CaptureLog implements LuaHost.LogSink {
        final List<String> lines = new ArrayList<>();
        @Override public void log(String level, String msg) {
            // 剥掉 "[botname] " 前缀，断言直接匹配消息本体
            String m = msg;
            int close = m.indexOf("] ");
            if (m.startsWith("[") && close > 0) m = m.substring(close + 2);
            lines.add(level + "|" + m);
        }
        List<String> infos() {
            return lines.stream().filter(l -> l.startsWith("info|")).map(l -> l.substring(5)).toList();
        }
    }

    private LuaHost newHost(Clock clock, CaptureLog logSink) {
        LuaHost host = new LuaHost("test-bot", logSink, clock);
        host.bootRuntimeLibs();
        return host;
    }

    private static LuaHost.ScriptFile script(String name, String src) {
        return new LuaHost.ScriptFile(name, src, true);
    }

    // ---- 1) 生命周期 + task.spawn + time.sleep ----

    @Test
    void onStartSpawnSleepLog() {
        Clock clock = new Clock();
        CaptureLog logs = new CaptureLog();
        LuaHost host = newHost(clock, logs);
        host.loadScripts(List.of(script("hello.lua", """
                on_start(function()
                  log.info("hello %d", 1)
                  task.spawn(function()
                    log.info("before sleep")
                    time.sleep(100)
                    log.info("after sleep")
                  end)
                end)
                """)));
        host.start();
        assertTrue(logs.infos().contains("hello 1"), String.valueOf(logs.lines));
        assertTrue(logs.infos().contains("before sleep"));
        assertFalse(logs.infos().contains("after sleep"), String.valueOf(logs.lines));

        clock.advance(50);
        host.tick();
        assertFalse(logs.infos().contains("after sleep"), String.valueOf(logs.lines));   // 未到期

        clock.advance(50);                                    // 累计 100ms
        host.tick();
        assertTrue(logs.infos().contains("after sleep"), String.valueOf(logs.lines));
    }

    // ---- 2) 动作 token：同步完成 + 异步完成 + 未知动作错误交付 ----

    @Test
    void actionTokenRoundtrip() {
        Clock clock = new Clock();
        CaptureLog logs = new CaptureLog();
        LuaHost host = newHost(clock, logs);

        host.registerAction("echo", (token, args, taskId) ->
                new String[]{"{\"got\":" + args.get("v") + "}", ""});
        int[] slowToken = {0};
        host.registerAction("slow", (token, args, taskId) -> {   // 异步：测试手动结算
            slowToken[0] = token;
            return null;
        });
        host.loadScripts(List.of(script("act.lua", """
                on_start(function()
                  task.spawn(function()
                    local r = call_action("__act_echo", { v = 7 })
                    log.info("echo=%d", r.got)
                  end)
                  task.spawn(function()
                    local ok = pcall(function() return call_action("__act_slow", {}) end)
                    log.info("slow settled")
                  end)
                  task.spawn(function()
                    -- 未注册动作：_G["__act_nope"] 为 nil => 与引擎一致的 "attempt to call nil" 字符串错误
                    local ok, err = pcall(function() return call_action("__act_nope", {}) end)
                    log.info("unknown failed=%s type=%s", tostring(ok), type(err))
                  end)
                end)
                """)));
        host.start();
        assertTrue(logs.infos().contains("echo=7"), String.valueOf(logs.lines));
        assertTrue(logs.infos().contains("unknown failed=false type=string"), String.valueOf(logs.lines));
        assertFalse(logs.infos().contains("slow settled"), String.valueOf(logs.lines));
        // 模拟异步完成：slow handler 记录了自己的 token，测试直接结算
        assertTrue(slowToken[0] > 0);
        host.settle(slowToken[0], "{\"ok\":true}", "");
        assertTrue(logs.infos().contains("slow settled"), String.valueOf(logs.lines));
    }

    // ---- 3) chat 事件 + rex 命名分组 + await_chat + on_chat ----

    @Test
    void chatRexAwaitAndHandler() {
        Clock clock = new Clock();
        CaptureLog logs = new CaptureLog();
        LuaHost host = newHost(clock, logs);
        host.loadScripts(List.of(script("chat.lua", """
                on_start(function()
                  task.spawn(function()
                    local m = await_chat(rex"balance[:=]\\\\s*(?<bal>\\\\d+)", 2000)
                    if m then log.info("await bal=%s", m.bal) else log.info("await timeout") end
                  end)
                  on_chat(rex"hello from (?<who>[A-Za-z]+)", function(m)
                    log.info("chat who=%s", m.who)
                  end)
                end)
                """)));
        host.start();
        host.event("chat", "{\"text\":\"balance: 123\",\"raw\":\"<p> balance: 123\",\"sender\":\"p\",\"sender_kind\":\"player\",\"ts\":1}");
        assertTrue(logs.infos().contains("await bal=123"), String.valueOf(logs.lines));
        host.event("chat", "{\"text\":\"hello from MixToby\",\"raw\":\"x\",\"sender\":\"MixToby\",\"sender_kind\":\"player\",\"ts\":2}");
        assertTrue(logs.infos().contains("chat who=MixToby"), String.valueOf(logs.lines));
    }

    // ---- 4) race / with_timeout + params 声明与读取 ----

    @Test
    void raceAndParams() {
        Clock clock = new Clock();
        CaptureLog logs = new CaptureLog();
        LuaHost host = newHost(clock, logs);
        host.loadScripts(List.of(script("race.lua", """
                params { n = { type = "number", default = 5 } }
                on_start(function()
                  log.info("n=%d", params.n)
                  task.spawn(function()
                    local v = race({
                      function() time.sleep(10) return "fast" end,
                      function() time.sleep(10000) return "slow" end,
                    })
                    log.info("won=%s", v)
                  end)
                  task.spawn(function()
                    local ok, err = pcall(function()
                      return with_timeout(50, function() time.sleep(10000) return "x" end)
                    end)
                    log.info("timeout kind=%s", err.kind)
                  end)
                end)
                """)));
        host.bindParams(Map.<String, JsonElement>of());
        host.start();
        assertTrue(logs.infos().contains("n=5"), String.valueOf(logs.lines));
        clock.advance(10);
        host.tick();
        assertTrue(logs.infos().contains("won=fast"), String.valueOf(logs.lines));
        clock.advance(50);
        host.tick();
        assertTrue(logs.infos().contains("timeout kind=timeout"), String.valueOf(logs.lines));
    }

    // ---- 5) on_timer / after 由 tick 驱动 ----

    @Test
    void timersViaTick() {
        Clock clock = new Clock();
        CaptureLog logs = new CaptureLog();
        LuaHost host = newHost(clock, logs);
        host.loadScripts(List.of(script("timers.lua", """
                on_start(function()
                  on_timer(100, function() log.info("tick-timer") end)
                  after(150, function() log.info("once-after") end)
                end)
                """)));
        host.start();
        for (int i = 0; i < 5; i++) { clock.advance(100); host.tick(); }
        long n = logs.infos().stream().filter(s -> s.equals("tick-timer")).count();
        assertEquals(5, n, String.valueOf(logs.lines));
        assertTrue(logs.infos().contains("once-after"));
    }

    // ---- 6) pause 冻结 + resume 重交付（runtime.paused 错误注入） ----

    @Test
    void pauseFreezesDeliveries() {
        Clock clock = new Clock();
        CaptureLog logs = new CaptureLog();
        LuaHost host = newHost(clock, logs);
        host.registerAction("hang", (token, args, taskId) -> null);   // 永不完成
        host.loadScripts(List.of(script("pause.lua", """
                on_start(function()
                  task.spawn(function()
                    local ok, err = pcall(function() return call_action("__act_hang", {}) end)
                    log.info("resumed err=%s", err.kind)
                  end)
                end)
                """)));
        host.start();
        System.out.println("[after-start] " + logs.lines);
        host.pause("fence");
        assertTrue(host.isPaused(), "paused");
        System.out.println("[after-pause] " + logs.lines);
        host.resume();
        System.out.println("[after-resume] " + logs.lines);
        assertTrue(logs.infos().stream().anyMatch(s -> s.contains("resumed err=runtime.paused")),
                String.valueOf(logs.lines));
    }

    // ---- 7) 查询桥：params 类型改写 + provider 注册 ----

    @Test
    void queryProviderAndParamCoercion() {
        Clock clock = new Clock();
        CaptureLog logs = new CaptureLog();
        LuaHost host = newHost(clock, logs);
        host.setQuery("self", args -> "{\"pos\":{\"x\":1,\"y\":2,\"z\":3},\"health\":20}");
        Map<String, JsonElement> params = new HashMap<>();
        params.put("source", com.google.gson.JsonParser.parseString("[119,151,-306]"));
        host.loadScripts(List.of(script("q.lua", """
                params { source = { type = "blockpos", required = true } }
                on_start(function()
                  local p = params.source
                  log.info("src=%d %d %d", p.x, p.y, p.z)
                  local s = self.pos()
                  log.info("pos=%.0f %.0f %.0f", s.x, s.y, s.z)
                end)
                """)));
        host.bindParams(params);
        host.start();
        assertTrue(logs.infos().contains("src=119 151 -306"), String.valueOf(logs.lines));
        assertTrue(logs.infos().stream().anyMatch(x -> x.startsWith("pos=1")), String.valueOf(logs.lines));
    }
}
