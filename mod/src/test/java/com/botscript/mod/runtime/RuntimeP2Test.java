package com.botscript.mod.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaValue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P2 无头回归：persist 桥（经 Lua 全链路）、命令总线、策略门控。 */
class RuntimeP2Test {

    // ---- 1) persist 桥：表声明/主键 upsert/find/adjust/seen/kv/log（bank bot 语义） ----

    @Test
    void persistBridgeThroughLua() throws Exception {
        Path file = Files.createTempFile("bspersist", ".json");
        Files.deleteIfExists(file);
        LuaHost host = newHost();
        PersistStore store = new PersistStore(file);
        host.attachPersist(store);
        host.loadScripts(List.of(script("""
                on_start(function()
                  persist.table "Account" { name = "string:key", balance = "number" }
                  Account.upsert { name = "MixTobyInjTick", balance = 100 }
                  Account.upsert { name = "Carol", balance = 4 }
                  local a = Account.find { name = "MixTobyInjTick" }
                  log.info("find=%s %s", a.name, tostring(a.balance))
                  local na = Account.adjust("MixTobyInjTick", -30, "name", "balance")
                  log.info("adjust=%s", tostring(na.balance))
                  log.info("seen1=%s seen2=%s", persist.seen("tx:1", 5000) and "hit" or "new",
                           persist.seen("tx:1", 5000) and "hit" or "new")
                  persist.kv "meta"
                  meta.set("opened", true)
                  log.info("kv=%s", tostring(meta.get("opened")))
                  persist.log "Intent"
                  local id = Intent.append { state = "pending", amount = 30 }
                  Intent.mark(id, "done")
                  local done = Intent.find { state = "done" }
                  local pend = Intent.find { state = "pending" }
                  local all = Intent.find {}
                  log.info("log n=%d state=%s | pend=%d all=%d", #done, done[1] and done[1].state or "-", #pend, #all)
                end)
                """)));
        host.start();
        List<String> infos = infos(host);
        assertTrue(infos.contains("find=MixTobyInjTick 100"), String.valueOf(logs(host)));
        assertTrue(infos.contains("adjust=70"), String.valueOf(logs(host)));
        assertTrue(infos.contains("seen1=new seen2=hit"), String.valueOf(logs(host)));
        assertTrue(infos.contains("kv=true"), String.valueOf(logs(host)));
        assertTrue(infos.stream().anyMatch(x -> x.startsWith("log n=1 state=done")), String.valueOf(logs(host)));
        // 重开文件：数据落盘可恢复
        LuaHost host2 = newHost();
        host2.attachPersist(new PersistStore(file));
        host2.loadScripts(List.of(script("""
                on_start(function()
                  persist.table "Account" { name = "string:key", balance = "number" }
                  local a = Account.find { name = "Carol" }
                  log.info("reopen carol=%s", tostring(a.balance))
                end)
                """)));
        host2.start();
        assertTrue(infos(host2).contains("reopen carol=4"), String.valueOf(logs(host2)));
    }

    // ---- 2) 命令总线：类型化参数 + 最长前缀 + 权限 ----

    @Test
    void commandBusParseAndAuthority() {
        BotConfig cfg = new BotConfig();
        cfg.authorityOp.add("opUser");
        List<String> consumed = new java.util.ArrayList<>();
        CommandBus bus = new CommandBus(cfg, new CommandBus.EventSink() {
            @Override public void command(int index, JsonObject args) {
                consumed.add(index + ":" + args);
            }

            @Override public void say(String text) { consumed.add("say:" + text); }
        }, () -> "state=playing paused=false pos=1 2 3", () -> { }, () -> { });

        bus.register("{\"pattern\":\"余额 <who:player>\",\"index\":1}");
        bus.register("{\"pattern\":\"余额\",\"index\":2}");
        bus.register("{\"pattern\":\"走到 <p:blockpos>\",\"index\":3}");
        bus.register("{\"pattern\":\"等 <d:duration>\",\"index\":4}");

        assertTrue(bus.dispatch("余额", "opUser", false));
        // "余额 <who:player>" 需要参数不匹配 → 落到无参的精确模式 2
        assertEquals(2, Integer.parseInt(consumed.get(0).split(":")[0]), consumed.get(0));

        assertTrue(bus.dispatch("余额 MixToby", "opUser", false));
        assertTrue(consumed.get(1).startsWith("1:"), consumed.get(1));   // 有参时最长前缀命中

        assertTrue(bus.dispatch("走到 110 64 -30", "opUser", false));
        assertTrue(consumed.get(2).startsWith("3:"), consumed.get(2));
        assertTrue(consumed.get(2).contains("\"p\":{\"x\":110,\"y\":64,\"z\":-30}"), consumed.get(2));

        assertTrue(bus.dispatch("等 2s", "opUser", false));
        assertTrue(consumed.get(3).startsWith("4:"), consumed.get(3));
        assertTrue(consumed.get(3).contains("2000"), consumed.get(3));

        // 内建权限：whitelist 用户不能 pause
        cfg.authorityWhitelist.add("somebody");
        assertTrue(bus.dispatch("pause", "somebody", false));   // 消费但拒绝（静默）
        assertTrue(bus.dispatch("pause", "opUser", false));     // op(80) 当 owner 用（未配 owner）
        // 未知命令不消费
        assertFalse(bus.dispatch("你好啊", "opUser", false));
    }

    // ---- 3) 策略：围栏 / 白名单命令 / say 限速 / 战斗目标 ----

    @Test
    void policyGates() {
        BotConfig cfg = new BotConfig();
        cfg.boundaryConfigured = true;   // v2：未配置 boundary = 观察模式（全拒），本用例测已配置实例的分面门控
        cfg.fenceMin = new double[]{0, 0};
        cfg.fenceMax = new double[]{100, 100};
        Policy p = new Policy(cfg, id -> {
            JsonObject e = new JsonObject();
            e.addProperty("type", "minecraft:zombie");
            JsonObject pos = new JsonObject();
            pos.addProperty("x", 10);
            pos.addProperty("y", 64);
            pos.addProperty("z", 10);
            e.add("pos", pos);
            return e;
        }, () -> new double[]{5, 64, 5});

        JsonObject navArgs = new JsonObject();
        JsonObject target = new JsonObject();
        target.addProperty("x", 200);
        target.addProperty("z", 50);
        navArgs.add("target", target);
        assertNotNull(p.checkAction("nav_goto", navArgs));      // 栏外拒绝

        target.addProperty("x", 50);
        assertNull(p.checkAction("nav_goto", navArgs));         // 栏内放行

        JsonObject dig = new JsonObject();
        cfg.blocksDig = "deny";
        assertNotNull(p.checkAction("dig", dig));
        cfg.blocksDig = null;            // v2 未授即禁
        assertNotNull(p.checkAction("dig", dig), "未授权 dig 应拒绝");
        cfg.blocksDig = "allow";
        assertNull(p.checkAction("dig", dig), "显式授权 dig 应放行");

        JsonObject atk = new JsonObject();
        atk.addProperty("kind", "attack");
        atk.addProperty("id", "7");
        cfg.combatTargets = "zombie";
        assertNull(p.checkAction("use_entity", atk));
        cfg.combatTargets = "creeper";
        assertNotNull(p.checkAction("use_entity", atk));

        // say 限速：默认 10/min，第 11 次拒绝
        for (int i = 0; i < 10; i++) assertNull(p.checkChatPolicy("hi"));
        assertNotNull(p.checkChatPolicy("hi"));

        // 命令白名单
        cfg.chatCommandsWhitelist.add("/pay");
        assertNull(p.checkChatPolicy("/pay 10"));
        assertNotNull(p.checkChatPolicy("/tp x"));
    }

    // ---- 工具 ----

    private final List<String> allLines = new java.util.ArrayList<>();

    private LuaHost newHost() {
        LuaHost host = new LuaHost("t", (level, msg) -> {
            int close = msg.indexOf("] ");
            String m = msg.startsWith("[") && close > 0 ? msg.substring(close + 2) : msg;
            allLines.add(level + "|" + m);
        }, () -> 1_000_000);
        host.bootRuntimeLibs();
        return host;
    }

    private LuaHost.ScriptFile script(String src) {
        return new LuaHost.ScriptFile("t.lua", src, true);
    }

    private List<String> logs(LuaHost host) { return allLines; }

    private List<String> infos(LuaHost host) {
        return allLines.stream().filter(l -> l.startsWith("info|")).map(l -> l.substring(5)).toList();
    }
}
