package com.botscript.mod.runtime;

import com.google.gson.JsonObject;

/**
 * 策略层（engine.js policyCheck/checkChatPolicy/authorityLevel 对应物）。
 * 全部纯函数 + BotConfig；动作门控由 LuaHost.execAction 调用。
 */
public final class Policy {

    public interface EntityLookup {
        /** 实体快照（含 type/pos），不存在返回 null */
        JsonObject get(String id);
    }

    public interface SelfPos {
        double[] get();   // x,y,z 或 null
    }

    private final BotConfig cfg;
    private final EntityLookup entities;
    private final SelfPos selfPos;
    private final java.util.Deque<Long> chatTokens = new java.util.ArrayDeque<>();
    private int budgetCount = 0;

    public Policy(BotConfig cfg, EntityLookup entities, SelfPos selfPos) {
        this.cfg = cfg;
        this.entities = entities;
        this.selfPos = selfPos;
    }

    // ---- 权限 ----
    public int authorityLevel(String sender) {
        if (sender == null) return 100; // console
        if (sender.isEmpty()) return 0;
        if (cfg.authorityOwner.contains(sender)) return 80;
        if (cfg.authorityOp.contains(sender)) {
            return cfg.authorityOwner.isEmpty() ? 80 : 60;   // 未显式配 owner 时 op 兼任
        }
        if (cfg.authorityWhitelist.contains(sender)) return 20;
        return 10;
    }

    public static int levelName(String perm) {
        return switch (perm == null ? "player" : perm) {
                case "console" -> 100;
                case "owner" -> 80;
                case "op" -> 60;
                case "whitelist" -> 20;
                default -> 10;
        };
    }

    // ---- 预算 ----
    /** 返回 true 表示预算恰好耗尽（触发一次自动 pause） */
    public boolean actionCount() {
        budgetCount++;
        return cfg.budgetActionsPerHour != null && budgetCount == cfg.budgetActionsPerHour;
    }

    // ---- 聊天策略：通过返回 null，拒绝返回错误 JSON ----
    public String checkChatPolicy(String text) {
        if (text.startsWith("/")) {
            String first = text.substring(1).split("\\s+")[0];
            String ns = first.contains(":") ? first.substring(first.indexOf(':') + 1) : first;
            boolean ok = cfg.chatCommandsWhitelist.contains(first)
                    || cfg.chatCommandsWhitelist.contains("/" + ns);
            if (!ok) return LuaHost.errJson("permission.denied", "命令白名单不含 /" + ns);
            return null;
        }
        // say 令牌桶
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d+)/(\\w+)$").matcher(cfg.chatSayRate);
        int limit = m.matches() ? Integer.parseInt(m.group(1)) : 10;
        long windowMs = m.matches() && m.group(2).equals("min") ? 60_000 : 1000;
        long now = System.currentTimeMillis();
        chatTokens.removeIf((t) -> now - t >= windowMs);
        if (chatTokens.size() >= limit) {
            return LuaHost.errJson("permission.denied", "say 限速（" + cfg.chatSayRate + "）");
        }
        chatTokens.add(now);
        return null;
    }

    // ---- 动作门控：通过返回 null，拒绝返回错误 JSON ----
    public String checkAction(String name, JsonObject args) {
        // 观察模式（DESIGN §5.1 默认全拒）：实例未配置 boundary -> 一切动作被拒
        if (!cfg.boundaryConfigured) {
            return LuaHost.errJson("permission.denied", "实例未配置 boundary（观察模式）：查询与 params 可用，动作全拒");
        }
        // 授权形态：true / "allow"（或任意非 deny 值）；未配置 = 未授即禁
        switch (name) {
            case "nav_goto", "nav_follow" -> {
                if (cfg.fenceMin != null && args.has("target") && args.getAsJsonObject("target").has("x")) {
                    JsonObject t = args.getAsJsonObject("target");
                    if (!cfg.inFence(t.get("x").getAsDouble(), t.get("z").getAsDouble())) {
                        return LuaHost.errJson("permission.denied", "目标 " + t + " 在围栏外");
                    }
                }
            }
            case "dig" -> {
                if (!granted(cfg.blocksDig)) {
                    return LuaHost.errJson("policy.blocklist", "dig 未授权（boundary.blocks.dig）");
                }
            }
            case "use_block" -> {
                if (args.has("place") && !args.get("place").isJsonNull() && !granted(cfg.blocksPlace)) {
                    return LuaHost.errJson("policy.blocklist", "place 未授权（boundary.blocks.place）");
                }
            }
            case "use_entity" -> {
                if (!args.has("kind") || !"attack".equals(args.get("kind").getAsString())) return null;
                if (cfg.combatTargets == null) {
                    return LuaHost.errJson("policy.blocklist", "combat 未授权（boundary.combat.targets）");
                }
                JsonObject e = entities.get(args.has("id") ? args.get("id").getAsString() : "");
                if (e == null) return LuaHost.errJson("entity.gone", args.has("id") ? args.get("id").getAsString() : "");
                String type = e.has("type") ? e.get("type").getAsString() : "";
                if (!type.contains(cfg.combatTargets)) {
                    return LuaHost.errJson("policy.blocklist",
                            "combat.targets=" + cfg.combatTargets + " 不含 " + type);
                }
                if (cfg.combatMaxEngage != null) {
                    double[] sp = selfPos.get();
                    if (sp != null && e.has("pos")) {
                        JsonObject p = e.getAsJsonObject("pos");
                        double d = dist(sp[0], sp[1], sp[2], p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble());
                        if (d > cfg.combatMaxEngage) {
                            return LuaHost.errJson("policy.blocklist",
                                    "超过接战距离 " + String.format("%.1f", d) + " > " + cfg.combatMaxEngage);
                        }
                    }
                }
            }
            default -> { }
        }
        return null;
    }

    /** 授权判定：true / "allow" / 任意非 deny 值 = 授权；null / "deny" / false = 未授即禁 */
    private static boolean granted(String v) {
        return v != null && !"deny".equals(v) && !"false".equalsIgnoreCase(v);
    }

    public static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static double dist2d(double ax, double az, double bx, double bz) {
        double dx = ax - bx, dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static String posKey(double x, double y, double z) {
        return Math.round(x) + "," + Math.round(y) + "," + Math.round(z);
    }
}
