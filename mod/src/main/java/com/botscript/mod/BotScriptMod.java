package com.botscript.mod;

import com.botscript.mod.runtime.BotConfig;
import com.botscript.mod.runtime.CommandBus;
import com.botscript.mod.runtime.LuaHost;
import com.botscript.mod.runtime.PersistStore;
import com.botscript.mod.runtime.ScriptPackageLoader;
import com.botscript.mod.runtime.Policy;
import com.botscript.mod.gui.BotGuiScreen;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * bot-script 客户端 mod（v2 standalone）：
 * mod 内嵌 Lua 运行时（LuaJ + bootstrap.lua）并直接加载执行
 * <游戏目录>/botscript/<包名>/ 下的脚本包，不需要外部 Node 引擎进程。
 *
 * v2 三层分离（DESIGN §6）：
 * - 脚本包 bot.yaml = 能力自述（name/scripts/params/persist），由 ScriptPackageLoader 加载；
 * - 实例边界（boundary）= 授权，来自游戏目录 config/botscript-mod.json（实例部署配置），
 *   未配置即观察模式（动作默认全拒，§5.1）；
 * - 连接 = 玩家本人的游戏会话（随登录器），mod 不持有凭据。
 *
 * - runtime 包：调度/token/桥（纯 Java，无 MC 依赖）
 * - McRuntime：MC 接线（窗口/实体跟踪、导航/容器状态机、全部动作实现）
 * - HttpApi：§17 HTTP 控制通道（常开，与 engine 宿主同 schema）
 * - gui 包：§16.5 管理/配置界面（与 HTTP 同源的进程内 API）
 * - 本类：生命周期 + 配置 + 聊天捕获 + 命令总线
 */
@Environment(EnvType.CLIENT)
public class BotScriptMod implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("botscript");
    private static MinecraftClient mc;

    private String activePackage = "hello";
    private JsonObject instanceBoundary = null;   // config/botscript-mod.json 的 boundary（实例部署配置）
    private boolean wasInWorld;
    private LuaHost host;
    private McRuntime mcruntime;
    private CommandBus commandBus;
    private PersistStore persist;
    private HttpApi httpApi;
    private BotConfig cfg = new BotConfig();
    private net.minecraft.client.option.KeyBinding keyToggle;   // 一键启停（§16 快捷键）

    @Override
    public void onInitializeClient() {
        mc = MinecraftClient.getInstance();
        loadConfig();
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientReceiveMessageEvents.GAME.register(this::onGameMessage);
        // HTTP 控制通道常开（CAPABILITIES §17）：宿主能力，不是可配置项
        httpApi = new HttpApi(() -> host, () -> commandBus, () -> persist, () -> cfg,
                () -> activePackage, () -> mcruntime);
        httpApi.setRuntimeControl(this::reloadRuntime, this::packagesJson);
        try {
            httpApi.start(LOG);
        } catch (Exception e) {
            LOG.error("HTTP 控制通道启动失败: {}", e.getMessage());
        }
        // 一键启停脚本（默认 K，可在控制设置改键）
        keyToggle = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
                new net.minecraft.client.option.KeyBinding("key.botscript.toggle",
                        net.minecraft.client.util.InputUtil.Type.KEYSYM,
                        org.lwjgl.glfw.GLFW.GLFW_KEY_K, "category.botscript"));
        // 外部文件拖入游戏窗口 = 导入脚本（.lua 入当前包；含 bot.yaml 的文件夹入为独立包）。
        // 窗口在 mod init 之后才创建，挂到 CLIENT_STARTED（late render 线程）注册 drop callback。
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            try {
                org.lwjgl.glfw.GLFW.glfwSetDropCallback(client.getWindow().getHandle(), (handle, count, ptr) -> {
                    List<Path> dropped = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        String p = org.lwjgl.glfw.GLFWDropCallback.getName(ptr, i);
                        if (p != null) dropped.add(java.nio.file.Path.of(p));
                    }
                    client.execute(() -> importDropped(dropped));
                });
            } catch (Exception e) {
                LOG.warn("拖拽导入初始化失败: {}", e.getMessage());
            }
        });
        // GUI 入口（§16.5）：未装 Mod Menu 也可用 /bsclient 打开管理界面
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("bsclient").executes(ctx -> {
                    mc.send(() -> mc.setScreen(new BotGuiScreen(httpApi)));
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                })));
        LOG.info("botscript mod 初始化完成（standalone 运行时），脚本包 = {}", activePackage);
    }

    // ================= 配置 =================

    private void loadConfig() {
        try {
            Path cfgFile = FabricLoader.getInstance().getConfigDir().resolve("botscript-mod.json");
            JsonObject template = new JsonObject();
            template.addProperty("package", activePackage);
            if (Files.exists(cfgFile)) {
                JsonObject o = JsonParser.parseString(Files.readString(cfgFile)).getAsJsonObject();
                if (o.has("package")) activePackage = o.get("package").getAsString();
                if (o.has("boundary") && o.get("boundary").isJsonObject()) {
                    instanceBoundary = o.getAsJsonObject("boundary");
                }
            } else {
                Files.createDirectories(cfgFile.getParent());
                Files.writeString(cfgFile, "{\n  \"package\": \"" + activePackage
                        + "\",\n  \"boundary\": {\"authority\": {\"owner\": [\"你的玩家名\"]}}\n}\n");
            }
        } catch (Exception e) {
            LOG.error("配置读取失败: {}", e.getMessage());
        }
    }

    private Path packageDir() {
        return FabricLoader.getInstance().getGameDir().resolve("botscript").resolve(activePackage);
    }

    // ================= 会话 / tick =================

    private void onTick(MinecraftClient client) {
        boolean inWorld = client.player != null && client.world != null;
        if (inWorld && !wasInWorld) bootRuntime();
        if (!inWorld && wasInWorld) stopRuntime();
        wasInWorld = inWorld;
        while (keyToggle.wasPressed()) {   // 一键启停：pause/resume 与 GUI 急停同路径
            if (host != null) {
                if (host.isPaused()) {
                    host.resume();
                    LOG.info("[hotkey] 脚本已恢复（resume）");
                } else {
                    host.pause("hotkey");
                    LOG.info("[hotkey] 脚本已急停（pause，按 {} 恢复）", keyToggle.getBoundKeyLocalizedText().getString());
                }
            }
        }
        if (host != null) host.tick();
    }

    private void bootRuntime() {
        try {
            Path dir = packageDir();
            if (!Files.isDirectory(dir)) {
                LOG.error("脚本包目录不存在: {}（config/botscript-mod.json 的 package 配置有误？）", dir);
                return;
            }
            // bot.yaml 清单加载（无清单则回退：字典序首个 .lua 为入口）
            ScriptPackageLoader.Loaded loaded = ScriptPackageLoader.load(dir, activePackage);
            cfg = loaded.config;
            List<LuaHost.ScriptFile> files = loaded.scripts;

            host = new LuaHost(activePackage, new McLogSink(), System::currentTimeMillis);
            host.setConfig(cfg);
            host.bootRuntimeLibs();

            // 实例边界（boundary）：来自游戏目录实例配置，未配置 = 观察模式（默认全拒）
            if (instanceBoundary != null) cfg.applyBoundary(instanceBoundary);
            if (!cfg.boundaryConfigured) {
                LOG.warn("实例未配置 boundary（config/botscript-mod.json）：观察模式，动作全拒（DESIGN §5.1）");
            }

            // 策略 + 持久化 + 命令总线 + MC 侧动作
            host.setPolicy(new Policy(cfg, id -> mcruntimeEntity(id), () -> {
                var p = mc.player;
                return p == null ? null : new double[]{p.getX(), p.getY(), p.getZ()};
            }));
            persist = new PersistStore(FabricLoader.getInstance().getGameDir()
                    .resolve("botscript").resolve("data").resolve(activePackage + ".json"));
            host.attachPersist(persist);
            commandBus = new CommandBus(cfg, new CommandBus.EventSink() {
                @Override public void command(int index, JsonObject args) {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("index", index);
                    payload.add("args", args);
                    host.event("command", payload.toString());
                }

                @Override public void say(String text) {
                    var p = mc.player;
                    if (p != null) p.networkHandler.sendChatMessage(text);
                    else LOG.info("[status] {}", text);
                }
            }, this::statusLine, () -> host.pause("command"), () -> host.resume());
            host.setCommandRegistrar(commandBus::register);

            mcruntime = new McRuntime(mc, host, cfg);
            mcruntime.attach();
            host.addMachine(mcruntime);

            // L1 方块更新流（P3 Mixin）：world.block_changed 事件
            BlockEventsHook.setListener((x, y, z, blockId) -> {
                JsonObject o = new JsonObject();
                o.addProperty("x", x);
                o.addProperty("y", y);
                o.addProperty("z", z);
                o.addProperty("name", blockId);
                host.event("world.block_changed", o.toString());
            });

            host.loadScripts(files);
            host.bindParams(cfg.params);
            host.start();
            LOG.info("脚本包 [{}] 已加载：入口 {}，共 {} 个脚本", activePackage, loaded.entryName, files.size());
        } catch (Exception e) {
            LOG.error("脚本包 [{}] 加载失败", activePackage, e);
            host = null;
        }
    }

    private JsonObject mcruntimeEntity(String id) {
        return mcruntime != null ? mcruntime.entitySnapshot(id) : null;
    }

    private String statusLine() {
        var p = mc.player;
        String pos = p != null ? Math.round(p.getX()) + " " + Math.round(p.getY()) + " " + Math.round(p.getZ()) : "?";
        return "state=" + (p != null ? "playing" : "idle") + " paused=" + host.isPaused()
                + " pos=" + pos;
    }

    private void stopRuntime() {
        BlockEventsHook.setListener(null);
        if (host != null) LOG.info("离开世界，脚本包 [{}] 停止", activePackage);
        host = null;
        mcruntime = null;
        commandBus = null;
    }

    // ================= 运行时控制：热重载 / 包切换 / 拖拽导入（CAPABILITIES §16.6） =================

    /**
     * 热重载脚本包，可选切换包名（写回 config）。必须在客户端线程执行（reloadFn 由 HttpApi onClient 编组）。
     * 不在世界内时只落配置，下次进世界自然加载新包。
     */
    private JsonObject reloadRuntime(String pkg) {
        JsonObject out = new JsonObject();
        try {
            if (pkg != null && !pkg.isBlank() && !pkg.equals(activePackage)) {
                Path dir = FabricLoader.getInstance().getGameDir().resolve("botscript").resolve(pkg);
                if (!Files.isDirectory(dir) || pkg.contains("..") || pkg.contains("/") || pkg.contains("\\")) {
                    out.addProperty("error", "package.missing");
                    out.addProperty("detail", "botscript/" + pkg + " 不存在");
                    return out;
                }
                activePackage = pkg;
                saveConfigPackage(pkg);
                out.addProperty("switched", pkg);
            }
            boolean inWorld = mc.player != null && mc.world != null;
            if (inWorld) {
                stopRuntime();
                bootRuntime();
                out.addProperty("reloaded", host != null);
            } else {
                out.addProperty("reloaded", false);
                out.addProperty("detail", "不在世界内：配置已写入，下次进世界加载 " + activePackage);
            }
            out.addProperty("package", activePackage);
        } catch (Exception e) {
            out.addProperty("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    /** botscript/ 下的可选包（含 bot.yaml 或任意 .lua 的目录）+ 当前包 */
    private JsonObject packagesJson() {
        JsonObject out = new JsonObject();
        out.addProperty("current", activePackage);
        JsonArray list = new JsonArray();
        Path root = FabricLoader.getInstance().getGameDir().resolve("botscript");
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory).filter(d -> {
                if (Files.exists(d.resolve("bot.yaml"))) return true;
                try (var s = Files.list(d)) {
                    return s.anyMatch(f -> f.getFileName().toString().endsWith(".lua"));
                } catch (Exception e) { return false; }
            }).sorted().forEach(d -> list.add(d.getFileName().toString()));
        } catch (Exception e) {
            out.addProperty("error", String.valueOf(e.getMessage()));
        }
        out.add("packages", list);
        return out;
    }

    /** 把 package 字段写回 config/botscript-mod.json（boundary 原样保留） */
    private void saveConfigPackage(String pkg) {
        try {
            Path cfgFile = FabricLoader.getInstance().getConfigDir().resolve("botscript-mod.json");
            JsonObject o = Files.exists(cfgFile)
                    ? JsonParser.parseString(Files.readString(cfgFile)).getAsJsonObject() : new JsonObject();
            o.addProperty("package", pkg);
            Files.writeString(cfgFile, o.toString());
            LOG.info("config package -> {} 已写回", pkg);
        } catch (Exception e) {
            LOG.error("config 写回失败: {}", e.getMessage());
        }
    }

    /** 拖拽导入：.lua 拷入当前包；含 bot.yaml 的文件夹拷为独立包。改动后热重载当前包。 */
    private void importDropped(List<Path> dropped) {
        Path root = FabricLoader.getInstance().getGameDir().resolve("botscript");
        boolean changed = false;
        try {
            for (Path src : dropped) {
                String name = src.getFileName().toString();
                if (Files.isDirectory(src)) {
                    if (!Files.exists(src.resolve("bot.yaml"))) {
                        LOG.warn("拖入的文件夹 [{}] 没有 bot.yaml，忽略（可作为 .lua 文件逐个拖入）", name);
                        continue;
                    }
                    copyRecursive(src, root.resolve(name));
                    LOG.info("已导入脚本包 [{}]（bot.yaml 定义入口；用 GUI「脚本」页或 /reload 切换）", name);
                    changed = true;
                } else if (name.endsWith(".lua")) {
                    Files.createDirectories(root.resolve(activePackage));
                    Files.copy(src, root.resolve(activePackage).resolve(name),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    LOG.info("已导入脚本 {} -> botscript/{}/（热重载生效）", name, activePackage);
                    changed = true;
                } else {
                    LOG.warn("忽略拖入文件 [{}]（仅支持 .lua 或含 bot.yaml 的文件夹）", name);
                }
            }
        } catch (Exception e) {
            LOG.error("拖拽导入失败: {}", e.getMessage());
            return;
        }
        if (changed && mc.player != null && mc.world != null) {
            stopRuntime();
            bootRuntime();
            LOG.info("拖拽导入完成，脚本包 [{}] 已热重载", activePackage);
        }
    }

    private void copyRecursive(Path src, Path dst) throws Exception {
        if (Files.isDirectory(src)) {
            Files.createDirectories(dst);
            try (var s = Files.list(src)) {
                for (Path f : s.toList()) copyRecursive(f, dst.resolve(f.getFileName().toString()));
            }
        } else {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ================= 聊天捕获（命令总线优先，chat 事件始终广播） =================

    private void onGameMessage(net.minecraft.text.Text message, Boolean overlay) {
        if (host == null || Boolean.TRUE.equals(overlay)) return;
        String raw = message.getString();
        if (raw.isEmpty()) return;
        ClientPlayerEntity p = mc.player;
        if (p != null && raw.startsWith("<" + p.getGameProfile().getName() + ">")) return; // 过滤自身回显
        JsonObject o = new JsonObject();
        String sender = null;
        String text = raw;
        Matcher m = Pattern.compile("^[<\\[]([^\\]>\\s]+)[>\\]]\\s*(.*)$", Pattern.DOTALL).matcher(raw);
        if (m.matches()) {
            sender = m.group(1);
            text = m.group(2);
        } else {
            Matcher w = Pattern.compile("^(\\S+) (?:whispers to you:|whispers:)\\s*(.*)$", Pattern.DOTALL).matcher(raw);
            if (w.matches()) { sender = w.group(1); text = w.group(2); }
        }
        o.addProperty("text", text);
        o.addProperty("raw", raw);
        o.addProperty("sender", sender);
        o.addProperty("sender_kind", sender != null ? "player" : "system");
        o.addProperty("ts", System.currentTimeMillis());
        commandBus.dispatch(text, sender, false);
        host.event("chat", LuaHost.toJson(o));
    }

    private final class McLogSink implements LuaHost.LogSink {
        @Override public void log(String level, String msg) {
            if (httpApi != null) httpApi.recordLog(level, msg);   // /logs 增量拉取的数据源
            switch (level) {
                case "error" -> LOG.error("{}", msg);
                case "warn" -> LOG.warn("{}", msg);
                default -> LOG.info("{}", msg);
            }
        }
    }
}
