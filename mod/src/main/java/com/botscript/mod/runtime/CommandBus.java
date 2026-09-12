package com.botscript.mod.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 命令总线（engine.js dispatchCommand/parseCommandArgs 对应物）。
 * 内建 pause/resume/status + 脚本 on_command（字面量前缀最长优先、类型化参数、权限分级）。
 */
public final class CommandBus {

    public static final class Decl {
        public String pattern;
        public List<Object> tokens = new ArrayList<>();   // String(字面量) 或 String[]{name,type}
        public String perm = "player";
        public int index;
    }

    public interface Builtin {
        int level();

        void run();
    }

    public interface EventSink {
        /** 脚本命令命中：发 command 事件（args 已含 sender） */
        void command(int index, JsonObject args);

        void say(String text);
    }

    private final BotConfig cfg;
    private final EventSink sink;
    private final List<Decl> commands = new ArrayList<>();
    private final java.util.function.Supplier<String> statusSupplier;
    private final Runnable pauseRun, resumeRun;

    public CommandBus(BotConfig cfg, EventSink sink,
                      java.util.function.Supplier<String> statusSupplier,
                      Runnable pauseRun, Runnable resumeRun) {
        this.cfg = cfg;
        this.sink = sink;
        this.statusSupplier = statusSupplier;
        this.pauseRun = pauseRun;
        this.resumeRun = resumeRun;
    }

    /** __command_register："<词> <x:blockpos> <n:number>" → tokens */
    public void register(String patternJson) {
        JsonObject o = JsonParser.parseString(patternJson).getAsJsonObject();
        Decl d = new Decl();
        d.pattern = o.get("pattern").getAsString();
        d.perm = o.has("perm") && !o.get("perm").isJsonNull() ? o.get("perm").getAsString() : "player";
        d.index = o.get("index").getAsInt();
        for (String t : d.pattern.trim().split("\\s+")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^<(\\w+):(\\w+)>$").matcher(t);
            d.tokens.add(m.matches()
                    ? new String[]{m.group(1), m.group(2)}
                    : t);
        }
        commands.add(d);
    }

    /** 聊天命令入口。返回 true = 已消费（不再作为普通 chat 事件分发）。 */
    public boolean dispatch(String text, String sender, boolean fromConsole) {
        String[] words = text.trim().split("\\s+");
        List<String> w = new ArrayList<>();
        for (String x : words) if (!x.isEmpty()) w.add(x);
        if (w.isEmpty()) return false;
        int senderLevel = fromConsole ? 100 : authority(sender);

        // 内建（owner 列表缺省时 op 兼任 owner 级）
        String head = w.get(0);
        if (w.size() == 1) {
            switch (head) {
                case "pause" -> {
                    if (senderLevel >= 80) pauseRun.run();
                    else deny(head, sender);
                    return true;
                }
                case "resume" -> {
                    if (senderLevel >= 80) resumeRun.run();
                    else deny(head, sender);
                    return true;
                }
                case "status" -> {
                    if (senderLevel >= 80) {
                        String s = statusSupplier.get();
                        if (fromConsole) sink.say(s);
                        else sink.say(s);
                    } else deny(head, sender);
                    return true;
                }
                default -> { }
            }
        }

        // 脚本命令：字面量前缀最长优先
        List<Decl> cands = new ArrayList<>();
        for (Decl c : commands) {
            if (!c.tokens.isEmpty() && c.tokens.get(0) instanceof String lit && w.get(0).equals(lit)) {
                cands.add(c);
            }
        }
        cands.sort((a, b) -> b.tokens.size() - a.tokens.size());
        for (Decl c : cands) {
            JsonObject args = parseArgs(c, w);
            if (args == null) continue;
            int need = Policy.levelName(c.perm);
            if (senderLevel < need) {
                sink.say("权限不足");
                return true;
            }
            args.addProperty("sender", sender == null ? "" : sender);
            sink.command(c.index, args);
            return true;
        }
        return false;
    }

    private void deny(String cmd, String sender) {
        // engine: 仅记日志拒绝（聊天侧静默）
    }

    private int authority(String sender) {
        return new Policy(cfg, id -> null, () -> null).authorityLevel(sender);
    }

    /** engine.js parseCommandArgs 对应物；不匹配返回 null */
    static JsonObject parseArgs(Decl c, List<String> words) {
        JsonObject args = new JsonObject();
        int wi = 0;
        for (Object t : c.tokens) {
            if (t instanceof String lit) {
                if (wi >= words.size() || !words.get(wi).equals(lit)) return null;
                wi++;
            } else {
                String[] tok = (String[]) t;
                String name = tok[0], type = tok[1];
                switch (type) {
                    case "blockpos", "pos" -> {
                        if (wi + 2 >= words.size() + 0 && wi + 3 > words.size()) return null;
                        if (wi + 3 > words.size()) return null;
                        try {
                            double x = Double.parseDouble(words.get(wi));
                            double y = Double.parseDouble(words.get(wi + 1));
                            double z = Double.parseDouble(words.get(wi + 2));
                            JsonObject p = new JsonObject();
                            p.addProperty("x", (int) Math.round(x));
                            p.addProperty("y", (int) Math.round(y));
                            p.addProperty("z", (int) Math.round(z));
                            args.add(name, p);
                        } catch (NumberFormatException e) {
                            return null;
                        }
                        wi += 3;
                    }
                    case "number" -> {
                        if (wi >= words.size()) return null;
                        try {
                            args.addProperty(name, Double.parseDouble(words.get(wi)));
                        } catch (NumberFormatException e) {
                            return null;
                        }
                        wi++;
                    }
                    case "duration" -> {
                        if (wi >= words.size()) return null;
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("^(\\d+(?:\\.\\d+)?)(ms|s|m)?$").matcher(words.get(wi));
                        if (!m.matches()) return null;
                        long mult = "s".equals(m.group(2)) ? 1000 : "m".equals(m.group(2)) ? 60_000 : 1;
                        args.addProperty(name, (long) (Double.parseDouble(m.group(1)) * mult));
                        wi++;
                    }
                    default -> {   // player / item / filter / string
                        if (wi >= words.size()) return null;
                        args.addProperty(name, words.get(wi));
                        wi++;
                    }
                }
            }
        }
        if (wi != words.size()) return null;
        return args;
    }
}
