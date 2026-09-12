package com.botscript.mod.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * bot 配置（bot.yaml 的 Java 对应物）。P4 由 snakeyaml 从脚本包 bot.yaml 填充；
 * 之前由测试/代码直接构造。字段语义与 engine.js cfg 完全一致。
 */
public class BotConfig {
    public String name = "bot";
    public Map<String, JsonElement> params = new java.util.HashMap<>();
    public String msgCommand = null;          // 私聊回复前缀（如 /msg）
    public boolean dryRun = false;

    // ---- policy ----
    public double[] fenceMin = null, fenceMax = null;         // [x,z] 围栏
    public Integer budgetActionsPerHour = null;
    public List<String> chatCommandsWhitelist = new ArrayList<>();
    public String chatSayRate = "10/min";                     // "N/min" | "N/s"
    public List<String> authorityOwner = new ArrayList<>();
    public List<String> authorityOp = new ArrayList<>();
    public List<String> authorityWhitelist = new ArrayList<>();
    public String blocksDig = null;                           // null/未授权 = 禁止；"allow" = 授权
    public String blocksPlace = null;
    public String combatTargets = null;                       // 子串匹配实体 type；null = combat 未授权
    public Double combatMaxEngage = null;
    /** 实例是否配置了 boundary（DESIGN §5.1：false = 观察模式，动作默认全拒） */
    public boolean boundaryConfigured = false;

    public static BotConfig fromJson(JsonObject o) {
        BotConfig c = new BotConfig();
        if (o.has("name")) c.name = o.get("name").getAsString();
        if (o.has("msg_command")) c.msgCommand = o.get("msg_command").getAsString();
        if (o.has("runtime") && o.getAsJsonObject("runtime").has("dry_run")) {
            c.dryRun = o.getAsJsonObject("runtime").get("dry_run").getAsBoolean();
        }
        if (o.has("params")) {
            for (var e : o.getAsJsonObject("params").entrySet()) c.params.put(e.getKey(), e.getValue());
        }
        if (o.has("policy")) c.applyBoundary(o.getAsJsonObject("policy"));
        return c;
    }

    /**
     * 实例边界（boundary，DESIGN §5.1/§6）：来自实例部署配置（fabric = 游戏目录 config），
     * 不来自脚本包清单。调用即视为"已配置 boundary"（否则观察模式：动作全拒）。
     */
    public void applyBoundary(JsonObject p) {
        boundaryConfigured = true;
        if (p.has("fence")) {
            var f = p.getAsJsonArray("fence");
            var a = f.get(0).getAsJsonArray();
            var b = f.get(1).getAsJsonArray();
            fenceMin = new double[]{Math.min(a.get(0).getAsDouble(), b.get(0).getAsDouble()),
                    Math.min(a.get(1).getAsDouble(), b.get(1).getAsDouble())};
            fenceMax = new double[]{Math.max(a.get(0).getAsDouble(), b.get(0).getAsDouble()),
                    Math.max(a.get(1).getAsDouble(), b.get(1).getAsDouble())};
        }
        if (p.has("budget") && p.getAsJsonObject("budget").has("actions_per_hour")) {
            budgetActionsPerHour = p.getAsJsonObject("budget").get("actions_per_hour").getAsInt();
        }
        if (p.has("chat")) {
            JsonObject ch = p.getAsJsonObject("chat");
            if (ch.has("commands")) {
                for (var e : ch.getAsJsonArray("commands")) chatCommandsWhitelist.add(e.getAsString());
            }
            if (ch.has("say_rate")) chatSayRate = ch.get("say_rate").getAsString();
            if (ch.has("msg_command")) msgCommand = ch.get("msg_command").getAsString();
        }
        if (p.has("authority")) {
            JsonObject a = p.getAsJsonObject("authority");
            for (var e : a.entrySet()) {
                for (var v : e.getValue().getAsJsonArray()) {
                    switch (e.getKey()) {
                        case "owner" -> authorityOwner.add(v.getAsString());
                        case "op" -> authorityOp.add(v.getAsString());
                        case "whitelist" -> authorityWhitelist.add(v.getAsString());
                    }
                }
            }
        }
        if (p.has("blocks")) {
            JsonObject b = p.getAsJsonObject("blocks");
            if (b.has("dig")) blocksDig = b.get("dig").getAsString();
            if (b.has("place")) blocksPlace = b.get("place").getAsString();
        }
        if (p.has("combat")) {
            JsonObject cb = p.getAsJsonObject("combat");
            if (cb.has("targets")) combatTargets = cb.get("targets").getAsString();
            if (cb.has("max_engage")) combatMaxEngage = cb.get("max_engage").getAsDouble();
        }
    }

    public boolean inFence(double x, double z) {
        if (fenceMin == null) return true;
        return x >= fenceMin[0] && x <= fenceMax[0] && z >= fenceMin[1] && z <= fenceMax[1];
    }
}
