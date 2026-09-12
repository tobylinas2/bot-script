package com.botscript.mod;

import com.botscript.mod.runtime.BotConfig;
import com.botscript.mod.runtime.GameTables;
import com.botscript.mod.runtime.LuaHost;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MC 侧运行时接线（v2）：窗口/实体跟踪 + 导航/容器状态机 + 全部动作实现。
 * 本类是除 BotScriptMod 胶水外唯一触碰 Yarn 映射的类（换 MC 版本只改这里）。
 * 全部逻辑跑在客户端线程（LuaHost.tick 由 END_CLIENT_TICK 驱动）。
 */
public final class McRuntime implements LuaHost.Tickable {
    private static final org.slf4j.Logger DBG = org.slf4j.LoggerFactory.getLogger("botscript-action");
    private final MinecraftClient mc;
    private final LuaHost host;
    private final BotConfig cfg;

    public McRuntime(MinecraftClient mc, LuaHost host, BotConfig cfg) {
        this.mc = mc;
        this.host = host;
        this.cfg = cfg;
    }

    // ================= 每 tick 驱动 =================

    /** 实体快照（策略层 combat 检查用）；不存在返回 null */
    /** 控制通道/GUI 只读：当前打开的容器窗口 id（null = 未开） */
    public String currentWindowId() {
        return currentWindowId;
    }

    public JsonObject entitySnapshot(String id) {
        return entities.get(id);
    }


    private int tickCounter = 0;
    private int hopReleaseTick = -1;
    private long lastAttack = 0;
    private String lastAttackWeapon = null;
    private float lastHealth = -1;

    // ---- 主线程队列：Lua 协程线程注册的动作一律投递到渲染线程执行 ----
    private final java.util.Deque<Runnable> mainQueue = new java.util.ArrayDeque<>();

    /** 动作包装：MC 工作全部投递到渲染线程；handler 返回 null = 异步结算（统一） */
    private LuaHost.ActionHandler onClient(LuaHost.ActionHandler fn) {
        return (token, args, taskId) -> {
            mainQueue.add(() -> {
                String[] r;
                try {
                    r = fn.run(token, args, taskId);
                } catch (Exception e) {
                    r = new String[]{"", LuaHost.errJson("driver.error", String.valueOf(e.getMessage()))};
                }
                if (r != null) host.settle(token, r[0], r[1]);
            });
            return null;   // 一律异步：结算发生在渲染线程
        };
    }

    @Override
    public void tick() {
        Runnable task;
        while ((task = mainQueue.poll()) != null) task.run();
        tickCounter++;
        if (hopReleaseTick > 0 && tickCounter >= hopReleaseTick) {
            if (gotoMachine == null) mc.options.jumpKey.setPressed(false);
            hopReleaseTick = -1;
        }
        trackWindow();
        if (tickCounter % 2 == 0) trackEntities();   // 10Hz
        var p0 = player();
        if (p0 != null) {
            float h = p0.getHealth();
            if (lastHealth >= 0 && h < lastHealth) {
                JsonObject d = new JsonObject();
                d.addProperty("amount", lastHealth - h);
                d.addProperty("health", h);
                host.event("self.damaged", d.toString());
            }
            lastHealth = h;
        }
        if (gotoMachine != null) gotoMachine.tick();
        if (followMachine != null) followMachine.tick();
        if (openMachine != null) openMachine.tick();
        if (clickMachine != null) clickMachine.tick();
    }

    private ClientPlayerEntity player() { return mc.player; }

    // ================= 窗口跟踪 =================

    private static final class WindowState {
        String id;
        String type;
        int size;
        int revision = 0;
        Map<Integer, JsonObject> slots = new HashMap<>();   // 引擎布局 index -> item(可空)
        JsonObject cursor;
        String sig = "";
    }

    private final Map<String, WindowState> windows = new HashMap<>();
    private String currentWindowId;   // null = 未开容器（背包永远可用，不占此位）

    private void trackWindow() {
        ClientPlayerEntity p = player();
        if (p == null) return;
        ScreenHandler handler = p.currentScreenHandler;
        if (handler == null) return;

        // 容器窗口：Paper 在点击后会重发 OpenScreen 递增 syncId（3→4...），
        // 故以"首个打开时的 syncId"为逻辑窗口 id（保持稳定），syncId==0 视为关闭。
        int syncId = handler.syncId;
        if (syncId == 0) {
            if (currentWindowId != null) {
                windows.remove(currentWindowId);
                host.event("container.closed", "{\"id\":\"" + currentWindowId + "\"}");
                currentWindowId = null;
            }
        } else {
            if (currentWindowId == null) currentWindowId = String.valueOf(syncId);
            refreshWindow(currentWindowId, handler, false);
        }

        // 背包窗口（'0'）：始终跟踪（引擎布局 0-8 hotbar / 9-35 main）
        refreshInventory(p);
    }

    private void refreshWindow(String id, ScreenHandler handler, boolean isPlayer) {
        WindowState w = windows.computeIfAbsent(id, (k) -> {
            WindowState nw = new WindowState();
            nw.id = id;
            nw.type = isPlayer ? "inventory" : "chest";
            nw.size = isPlayer ? 36 : Math.max(0, handler.getStacks().size() - 36);
            return nw;
        });
        Map<Integer, JsonObject> slots = new HashMap<>();
        var stacks = handler.getStacks();
        for (int i = 0; i < stacks.size(); i++) {
            var st = stacks.get(i);
            JsonObject item = itemJson(st);
            if (item == null) continue;
            int e;
            if (isPlayer) {   // 玩家界面布局 -> 引擎布局
                if (i >= 36 && i <= 44) e = i - 36;
                else if (i >= 9 && i <= 35) e = i;
                else continue;
            } else {
                e = i;
            }
            slots.put(e, item);
        }
        var cursor = handler.getCursorStack();
        JsonObject cursorJson = itemJson(cursor);
        String sig = slots.toString() + "|" + (cursorJson == null ? "-" : cursorJson.toString());
        w.slots = slots;
        w.cursor = cursorJson;
        if (!sig.equals(w.sig)) {
            w.sig = sig;
            w.revision++;
        }
    }

    private void refreshInventory(ClientPlayerEntity p) {
        WindowState w = windows.computeIfAbsent("0", (k) -> {
            WindowState nw = new WindowState();
            nw.id = "0";
            nw.type = "inventory";
            nw.size = 36;
            return nw;
        });
        Map<Integer, JsonObject> slots = new HashMap<>();
        var inv = p.getInventory();
        for (int e = 0; e < 36; e++) {
            // 引擎 0-8 = hotbar；9-35 = 主背包。玩家 Inventory：0-8 hotbar，9-35 main
            JsonObject item = itemJson(inv.getStack(e));
            if (item != null) slots.put(e, item);
        }
        String sig = slots.toString();
        w.slots = slots;
        w.cursor = null;
        if (!sig.equals(w.sig)) {
            w.sig = sig;
            w.revision++;
        }
    }

    // ================= 实体跟踪 =================

    private final Map<String, JsonObject> entities = new LinkedHashMap<>();
    private final Set<String> knownEntities = new HashSet<>();

    private void trackEntities() {
        ClientPlayerEntity p = player();
        if (p == null || mc.world == null) return;
        Map<String, JsonObject> fresh = new LinkedHashMap<>();
        for (var e : mc.world.getEntities()) {
            if (e == p || e.isRemoved()) continue;
            String id = String.valueOf(e.getId());
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            boolean isPlayer = e instanceof ClientPlayerEntity || e.getClass().getSimpleName().contains("Player");
            o.addProperty("type", isPlayer ? "player" : "minecraft:" + e.getType().toString().replace("entity.minecraft.", "")
                    .replaceFirst("^minecraft\\.", ""));
            o.addProperty("name", isPlayer ? e.getName().getString() : null);
            JsonObject pos = new JsonObject();
            pos.addProperty("x", e.getX());
            pos.addProperty("y", e.getY());
            pos.addProperty("z", e.getZ());
            o.add("pos", pos);
            o.addProperty("alive", e.isAlive());
            if (e instanceof net.minecraft.entity.LivingEntity le) {
                JsonObject hand = itemJson(le.getMainHandStack());
                JsonObject eq = new JsonObject();
                eq.add("hand", hand != null ? hand : com.google.gson.JsonNull.INSTANCE);
                o.add("equipment", eq);
            }
            fresh.put(id, o);
        }
        entities.clear();
        entities.putAll(fresh);
        for (String id : fresh.keySet()) {
            if (!knownEntities.contains(id)) {
                knownEntities.add(id);
                host.event("entity.appeared", "{\"id\":\"" + id + "\"}");
            }
        }
        for (String id : new HashSet<>(knownEntities)) {
            if (!fresh.containsKey(id)) {
                knownEntities.remove(id);
                host.event("entity.gone", "{\"id\":\"" + id + "\"}");
            }
        }
    }

    // ================= 导航（直线退化 + 卡住跳） =================

    private GotoState gotoMachine;
    private FollowState followMachine;

    private void moveInput(int fwd, int strafe, int jump, int sneak, int sprint) {
        var opt = mc.options;
        opt.forwardKey.setPressed(fwd != 0);
        opt.backKey.setPressed(false);
        opt.leftKey.setPressed(strafe < 0);
        opt.rightKey.setPressed(strafe > 0);
        opt.jumpKey.setPressed(jump != 0);
        opt.sneakKey.setPressed(sneak != 0);
        opt.sprintKey.setPressed(sprint != 0);
    }

    private void stopMove() { moveInput(0, 0, 0, 0, 0); }

    private static double dist2d(double ax, double az, double bx, double bz) {
        double dx = ax - bx, dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double dist3(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double[] selfPos(MinecraftClient mc) {
        var p = mc.player;
        return p == null ? null : new double[]{p.getX(), p.getY(), p.getZ()};
    }

    private static void rotTo(MinecraftClient mc, double yaw, double pitch) {
        var p = mc.player;
        if (p == null) return;
        p.setYaw((float) (((yaw % 360) + 540) % 360 - 180));
        p.setPitch((float) Math.max(-90, Math.min(90, pitch)));
    }

    private static double yawTo(double fromX, double fromZ, double toX, double toZ) {
        return Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ));
    }

    private class GotoState implements LuaHost.Tickable {
        final double[] target;
        final double arrive;
        final long deadline;
        final int token;
        final boolean emitFail;
        double[] last;
        long lastProgress;
        boolean stopped;
        int phase = 0;              // 0=正常, 1=跳越窗口
        long jumpUntil;

        GotoState(double[] target, double arrive, long timeoutMs, int token, boolean emitFail) {
            this.target = target;
            this.arrive = arrive;
            this.deadline = System.currentTimeMillis() + timeoutMs;
            this.token = token;
            this.emitFail = emitFail;
            double[] p = selfPos(mc);
            this.last = p != null ? p : target;
            this.lastProgress = System.currentTimeMillis();
        }

        @Override public void tick() {
            if (stopped || tickCounter % 2 != 0) return;   // 100ms 步进
            if (host.isPaused()) { stop("paused"); return; }
            double[] p = selfPos(mc);
            if (p == null) return;
            double d = dist2d(p[0], p[2], target[0], target[2]);
            if (d <= arrive) { stop("ok"); return; }
            long now = System.currentTimeMillis();
            if (now > deadline) { stop("timeout"); return; }
            if (dist3(p[0], p[1], p[2], last[0], last[1], last[2]) < 0.15) {
                if (now - lastProgress > 6000) { stop("stuck"); return; }
                // 卡住：跳一格（250ms 窗口，跨 tick 释放）
                if (phase == 0) {
                    moveInput(1, 0, 1, 0, 0);
                    jumpUntil = now + 250;
                    phase = 1;
                } else if (now > jumpUntil) {
                    moveInput(1, 0, 0, 0, 0);
                    phase = 0;
                }
            } else {
                last = p;
                lastProgress = now;
                phase = 0;
            }
            rotTo(mc, yawTo(p[0], p[2], target[0], target[2]), 0);
            moveInput(1, 0, phase, 0, 0);   // jump 阶段保持跳
        }

        void stop(String how) {
            if (stopped) return;
            stopped = true;
            if (token != 0) DBG.info("[smoke] goto how={}", how);
            stopMove();
            if (gotoMachine == this) gotoMachine = null;
            if ("ok".equals(how)) {
                host.settle(token, "{\"ok\":true}", "");
            } else {
                JsonObject f = new JsonObject();
                f.addProperty("reason", "cancel".equals(how) ? "interrupted" : how);
                // target 序列化
                JsonObject t = new JsonObject();
                t.addProperty("x", target[0]);
                t.addProperty("y", target[1]);
                t.addProperty("z", target[2]);
                f.add("target", t);
                if (emitFail) host.event("nav.failed", f.toString());
                host.settle(token, "{\"ok\":false,\"reason\":\"" + f.get("reason").getAsString() + "\"}", "");
            }
        }
    }

    private final class FollowState implements LuaHost.Tickable {
        final String entityId;
        final double want;
        final int token;
        boolean stopped;

        FollowState(String entityId, double want, int token) {
            this.entityId = entityId;
            this.want = want;
            this.token = token;
        }

        @Override public void tick() {
            if (stopped || tickCounter % 10 != 0) return;   // 500ms 决策
            if (host.isPaused()) { stop(); return; }
            JsonObject cur = entities.get(entityId);
            if (cur == null || (cur.has("alive") && !cur.get("alive").getAsBoolean())) { stop(); return; }
            double[] sp = selfPos(mc);
            if (sp == null) return;
            JsonObject pos = cur.getAsJsonObject("pos");
            double d = dist3(sp[0], sp[1], sp[2], pos.get("x").getAsDouble(), pos.get("y").getAsDouble(), pos.get("z").getAsDouble());
            if (d > want + 1) {
                startGotoInner(new double[]{pos.get("x").getAsDouble(), pos.get("y").getAsDouble(), pos.get("z").getAsDouble()},
                        want, 5000, 0, false);
            } else {
                stopMove();
            }
        }

        void stop() {
            if (stopped) return;
            stopped = true;
            stopMove();
            if (followMachine == this) followMachine = null;
            host.settle(token, "{}", "");
        }
    }

    /** 内部走位：直达机器（token=0 表示 fire-and-forget） */
    private void startGotoInner(double[] target, double arrive, long timeoutMs, int token, boolean emitFail) {
        if (gotoMachine != null) gotoMachine.stop("cancel");
        gotoMachine = new GotoState(target, arrive, timeoutMs, token, emitFail);
        host.addMachine(gotoMachine);
    }

    // ================= 容器开/关/点击 =================

    private ContainerOpenState openMachine;
    private ClickState clickMachine;

    /** 开箱机器：goto 贴脸 → aim(+150ms) → 交互(3 tick 延迟) → 等窗口 → 等 revision>1，带重试阶梯 */
    private final class ContainerOpenState implements LuaHost.Tickable {
        final double[] target;
        final int token;
        int phase = 0;              // 0 goto / 1 aim / 2 pendingUse / 3 waitOpen / 4 waitSlots
        int retry = 0;              // 0 首试 / 1 走近重试 / 2,3 对角方位
        long phaseUntil;
        int pendingTicks;
        double[] gotoTo;            // 当前走位目标（null = 直达）
        Double arriveOverride;
        GotoState innerGoto;

        ContainerOpenState(double[] target, int token) {
            this.target = target;
            this.token = token;
        }

        @Override public void tick() {
            if (host.isPaused()) { fail("runtime.paused"); return; }
            long now = System.currentTimeMillis();
            double[] p = selfPos(mc);
            if (p == null) return;
            switch (phase) {
                case 0 -> {   // 贴脸走位
                    double d = dist3(p[0], p[1], p[2], target[0], target[1], target[2]);
                    double arrive = arriveOverride != null ? arriveOverride : 1.2;
                    if (d <= Math.max(2.0, arrive)) { phase = 1; return; }
                    if (innerGoto == null) {
                        double[] gt = gotoTo != null ? gotoTo : target;
                        innerGoto = newGotoFire(gt, arrive, 8000);
                        return;
                    }
                    if (innerGoto.stopped) {
                        innerGoto = null;
                        phase = 1;   // 走不到位也试一次（engine 重试语义）
                    }
                }
                case 1 -> {   // aim + 150ms 定视线
                    aimAt(target, p);
                    phase = 2;
                    pendingTicks = 3;   // PendingUse 同款：等 Look 包到服务器
                }
                case 2 -> {   // 交互
                    if (--pendingTicks > 0) return;
                    interactBlock(target);
                    phase = 3;
                    phaseUntil = now + 6000;
                }
                case 3 -> {   // 等窗口打开
                    if (currentWindowId != null) { phase = 4; phaseUntil = now + 1500; return; }
                    if (now > phaseUntil) { nextRetry(now); }
                }
                case 4 -> {   // 等 slot 数据（revision > 1）
                    WindowState w = windows.get(currentWindowId);
                    if (w != null && w.revision > 1) {
                        finish();
                        return;
                    }
                    if (now > phaseUntil) {
                        WindowState w2 = windows.get(currentWindowId);
                        if (w2 != null) { finish(); return; }   // 有快照也继续（v1 宽容语义）
                        nextRetry(now);
                    }
                }
                default -> fail("container.missing");
            }
        }

        private void nextRetry(long now) {
            retry++;
            if (retry == 1) {
                // 走近重试
                double[] p2 = selfPos(mc);
                if (p2 != null && dist3(p2[0], p2[1], p2[2], target[0], target[1], target[2]) > 4) {
                    closeCurrentWindow();
                    gotoTo = target;
                    arriveOverride = 2.0;
                    phase = 0;
                    innerGoto = null;
                    return;
                }
            }
            if (retry >= 2 && retry <= 3) {
                // 换方位：对角偏移点接近
                double[][] offs = {{2, 2}, {-2, -2}};
                double[] off = offs[retry - 2];
                closeCurrentWindow();
                gotoTo = new double[]{target[0] + off[0], target[1], target[2] + off[1]};
                arriveOverride = 1.2;
                phase = 0;
                innerGoto = null;
                return;
            }
            fail("container.missing");
        }

        private void finish() {
            if (openMachine != this) return;
            openMachine = null;
            DBG.info("[smoke] open_done win={}", currentWindowId);
            WindowState w = windows.get(currentWindowId);
            JsonObject r = new JsonObject();
            r.addProperty("id", w != null ? w.id : currentWindowId);
            r.addProperty("type", w != null ? w.type : "chest");
            r.addProperty("size", w != null ? w.size : 0);
            host.settle(token, r.toString(), "");
        }

        private void fail(String kind) {
            if (openMachine != this) return;
            openMachine = null;
            stopMove();
            DBG.info("[smoke] open_fail kind={} retry={}", kind, retry);
            host.settle(token, "", LuaHost.errJson(kind, target[0] + " " + target[1] + " " + target[2]));
        }

        void failQuiet() { fail("container.missing"); }
    }

    private String lastGotoResult = "";

    private GotoState newGotoFire(double[] target, double arrive, long timeoutMs) {
        // fire-and-forget 走位：结果写入 lastGotoResult
        GotoState g = new GotoState(target, arrive, timeoutMs, 0, false) {
            @Override void stop(String how) {
                lastGotoResult = how;
                super.stop(how);
            }
        };
        // 匿名子类 stop 与字段 stopped 的可见性：直接使用
        gotoMachine = g;
        host.addMachine(g);
        return g;
    }

    private void aimAt(double[] t, double[] p) {
        double dx = t[0] + 0.5 - p[0];
        double dy = (t[1] + 0.5) - (p[1] + 1.62);
        double dz = t[2] + 0.5 - p[2];
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1e-6) horiz = 1e-6;
        rotTo(mc, Math.toDegrees(Math.atan2(-dx, dz)), Math.toDegrees(Math.atan2(-dy, horiz)));
    }

    private void interactBlock(double[] t) {
        ClientPlayerEntity p = player();
        if (p == null) return;
        BlockPos pos = BlockPos.ofFloored(t[0], t[1], t[2]);
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        boolean sneak = p.isSneaking();
        p.setSneaking(false);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
        p.setSneaking(sneak);
    }

    private void closeCurrentWindow() {
        ClientPlayerEntity p = player();
        if (p == null) return;
        if (p.currentScreenHandler != null && p.currentScreenHandler.syncId != 0) {
            p.closeHandledScreen();
        }
        if (currentWindowId != null) {
            windows.remove(currentWindowId);
            currentWindowId = null;
        }
    }

    /** 窗口点击机器：clickSlot + 槽位内容签名确认（跨 tick 重试，替代旧实现里的 Thread.sleep） */
    private final class ClickState implements LuaHost.Tickable {
        final String handlerId;    // "0" = 玩家 ScreenHandler；否则容器 syncId
        final int engineSlot;      // 引擎布局槽
        final int button;
        final int mode;
        final int token;
        int attempts = 0;
        int settleTicks = 0;
        String lastSig = null;

        ClickState(String handlerId, int engineSlot, int button, int mode, int token) {
            this.handlerId = handlerId;
            this.engineSlot = engineSlot;
            this.button = button;
            this.mode = mode;
            this.token = token;
        }

        @Override public void tick() {
            ClientPlayerEntity p = player();
            if (p == null) { fail("container.closed"); return; }
            ScreenHandler handler = resolveHandler(handlerId);
            if (handler == null) { fail("container.closed"); return; }
            int rawSlot = toRawSlot(handlerId, engineSlot);
            if (rawSlot < 0 || rawSlot >= handler.getStacks().size()) { fail("container.closed"); return; }

            if (settleTicks > 0) {
                settleTicks--;
                if (settleTicks == 0) {
                    String sig = slotSig(handler, rawSlot);
                    if (!sig.equals(lastSig)) {   // 服务器状态变化 => 点击被接受
                        done();
                        return;
                    }
                    if (++attempts > 4) { done(); return; }   // 多次重试未变化：有的点击本就不改变内容，放行
                    sendClick(handler, rawSlot);
                    settleTicks = 2;
                }
                return;
            }
            lastSig = slotSig(handler, rawSlot);
            sendClick(handler, rawSlot);
            settleTicks = 2;
        }

        private void done() {
            if (clickMachine != this) return;
            clickMachine = null;
            DBG.info("[smoke] click_done slot={} win={}", engineSlot, handlerId);
            host.settle(token, "{}", "");
        }

        private void fail(String kind) {
            if (clickMachine != this) return;
            clickMachine = null;
            DBG.info("[smoke] click_fail kind={} win={} sync_now={}", kind, handlerId, player() != null ? player().currentScreenHandler.syncId : -1);
            host.settle(token, "", LuaHost.errJson(kind, handlerId));
        }
    }

    private static String slotSig(ScreenHandler h, int slot) {
        var st = h.getStacks().get(slot);
        return st == null || st.isEmpty() ? "-" : Registries.ITEM.getId(st.getItem()) + "x" + st.getCount();
    }

    private void sendClick(ScreenHandler handler, int rawSlot) {
        ClientPlayerEntity p = player();
        if (p == null) return;
        SlotActionType type = switch (modeOfPendingClick) {
            case 1 -> SlotActionType.QUICK_MOVE;
            case 2 -> SlotActionType.SWAP;
            case 3 -> SlotActionType.CLONE;
            case 4 -> SlotActionType.THROW;
            case 5 -> SlotActionType.QUICK_CRAFT;
            case 6 -> SlotActionType.PICKUP_ALL;
            default -> SlotActionType.PICKUP;
        };
        mc.interactionManager.clickSlot(handler.syncId, rawSlot, buttonOfPendingClick, type, p);
    }

    private int modeOfPendingClick;
    private int buttonOfPendingClick;

    private ScreenHandler resolveHandler(String id) {
        // syncId 会被 Paper 重同步递增：容器点击一律取"当前打开的 handler"
        ClientPlayerEntity p = player();
        if (p == null) return null;
        if ("0".equals(id)) return p.playerScreenHandler;
        return (currentWindowId != null && p.currentScreenHandler.syncId != 0) ? p.currentScreenHandler : null;
    }

    /** 引擎布局槽 -> ScreenHandler 原始槽 */
    private int toRawSlot(String handlerId, int engineSlot) {
        ClientPlayerEntity p = player();
        if (p == null) return -1;
        ScreenHandler handler = resolveHandler(handlerId);
        if (handler == null) return -1;
        if ("0".equals(handlerId)) {
            // 玩家 ScreenHandler：0 合成格 / 5-8 盔甲 / 9-35 主背包 / 36-44 快捷栏
            return engineSlot < 9 ? engineSlot + 36 : engineSlot;   // 9-35 相同，0-8 -> 36-44
        }
        // 容器窗口：引擎槽就是容器 handler 槽（window_click 已做过 背包->玩家区 映射）
        return engineSlot;
    }

    // ================= 查询构造 =================

    private JsonObject selfJson() {
        ClientPlayerEntity p = player();
        if (p == null) return null;
        JsonObject o = new JsonObject();
        JsonObject pos = new JsonObject();
        pos.addProperty("x", p.getX());
        pos.addProperty("y", p.getY());
        pos.addProperty("z", p.getZ());
        o.add("pos", pos);
        o.addProperty("yaw", p.getYaw());
        o.addProperty("pitch", p.getPitch());
        o.addProperty("health", p.getHealth());
        o.addProperty("food", p.getHungerManager().getFoodLevel());
        o.addProperty("gamemode", mc.interactionManager.getCurrentGameMode().asString());
        JsonObject held = itemJson(p.getMainHandStack());
        if (held != null) o.add("held", held);
        o.addProperty("heldSlot", p.getInventory().getSelectedSlot());
        o.addProperty("held_slot", p.getInventory().getSelectedSlot());
        return o;
    }

    private static JsonObject itemJson(net.minecraft.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        JsonObject o = new JsonObject();
        o.addProperty("id", Registries.ITEM.getId(stack.getItem()).toString());
        o.addProperty("count", stack.getCount());
        return o;
    }

    private String blockName(double x, double y, double z) {
        if (mc.world == null) return "";
        var state = mc.world.getBlockState(BlockPos.ofFloored(x, y, z));
        return Registries.BLOCK.getId(state.getBlock()).toString();
    }

    private JsonArray scanContainers(double[] min, double[] max, int limit) {
        JsonArray list = new JsonArray();
        if (mc.world == null) return list;
        int n = 0;
        for (BlockPos pos : BlockPos.iterate(
                (int) Math.floor(min[0]), (int) Math.floor(min[1]), (int) Math.floor(min[2]),
                (int) Math.floor(max[0]), (int) Math.floor(max[1]), (int) Math.floor(max[2]))) {
            String name = Registries.BLOCK.getId(mc.world.getBlockState(pos).getBlock()).toString();
            if (GameTables.isContainerName(name)) {
                JsonObject o = new JsonObject();
                o.addProperty("x", pos.getX());
                o.addProperty("y", pos.getY());
                o.addProperty("z", pos.getZ());
                o.addProperty("name", name);
                list.add(o);
                if (++n >= limit) break;
            }
        }
        return list;
    }

    // ================= 动作 / 查询注册 =================

    public void attach() {
        registerQueries();
        registerActions();
    }

    private void registerQueries() {
        host.setQuery("self", args -> {
            JsonObject s = selfJson();
            return s == null ? "{}" : s.toString();
        });
        host.setQuery("look", args -> {
            var p = player();
            if (p == null) return "{}";
            double yaw = ((p.getYaw() % 360) + 360) % 360;
            JsonObject o = new JsonObject();
            o.addProperty("yaw", yaw);
            o.addProperty("pitch", p.getPitch());
            return o.toString();
        });
        host.setQuery("entities", args -> {
            JsonArray list = new JsonArray();
            entities.values().forEach(list::add);
            return list.toString();
        });
        host.setQuery("entity_snap", args -> {
            JsonObject e = entities.get(args.has("id") ? args.get("id").getAsString() : "");
            return e == null ? "" : e.toString();
        });
        host.setQuery("block", args -> {
            JsonObject o = new JsonObject();
            if (args.has("pos")) {
                JsonObject pos = args.getAsJsonObject("pos");
                String name = blockName(pos.get("x").getAsDouble(), pos.get("y").getAsDouble(), pos.get("z").getAsDouble());
                o.addProperty("name", name);
                o.addProperty("is_container", GameTables.isContainerName(name));
                o.addProperty("ts", System.currentTimeMillis());
            }
            return o.toString();
        });
        host.setQuery("staleness", args -> "0");
        host.setQuery("distance", args -> {
            double[] sp = selfPos(mc);
            if (sp == null || !args.has("pos")) return "null";
            JsonObject pos = args.getAsJsonObject("pos");
            return String.valueOf(dist3(sp[0], sp[1], sp[2],
                    pos.get("x").getAsDouble(), pos.get("y").getAsDouble(), pos.get("z").getAsDouble()));
        });
        host.setQuery("can_reach", args -> {
            double[] sp = selfPos(mc);
            if (sp == null || !args.has("pos")) return "false";
            JsonObject pos = args.getAsJsonObject("pos");
            double d = dist3(sp[0], sp[1], sp[2],
                    pos.get("x").getAsDouble(), pos.get("y").getAsDouble(), pos.get("z").getAsDouble());
            return String.valueOf(d <= 64);
        });
                host.setQuery("current_window", args -> currentWindowId == null
                ? "" : '"' + currentWindowId + '"');   // "" => nil（json.lua 的 "null" 会解码为 truthy 标记）
        host.setQuery("window", args -> {
            String id = args.has("window") ? String.valueOf(args.get("window").getAsString()) : "0";
            WindowState w;
            if ("0".equals(id)) {
                WindowState cont = currentWindowId != null ? windows.get(currentWindowId) : null;
                if (cont != null) {
                    // 合并视图：容器打开时背包 = 容器窗口的玩家区
                    return mergedInventoryJson(cont).toString();
                }
                w = windows.get("0");
                if (w == null) return "{}";
                return windowJson(w).toString();
            }
            w = windows.get(id);
            return w == null ? "{}" : windowJson(w).toString();
        });
        host.setQuery("cooldown", args -> {
            long cd = GameTables.weaponCooldownMs(lastAttackWeapon);
            long left = lastAttack + cd - System.currentTimeMillis();
            return String.valueOf(Math.max(0, left));
        });
        host.setQuery("session_state", args -> player() != null ? "\"playing\"" : "\"idle\"");
        host.setQuery("session_info", args -> "{\"state\":\"" + (player() != null ? "playing" : "idle") + "\"}");
        host.setQuery("caps", args -> {
            String flag = args.has("flag") ? args.get("flag").getAsString() : "";
            // fabric：无寻路器/无方块流；点击为真游戏内点击
            boolean v = switch (flag) {
                    case "nav.pathfinder", "world_stream" -> false;
                    case "entity.equipment", "entity.holding", "input.move", "window.click_authentic", "hud.full" -> true;
                    default -> false;
            };
            return String.valueOf(v);
        });
        host.setQuery("server_version", args -> mc.getCurrentServerEntry() != null
                ? "\"" + (mc.getCurrentServerEntry().version != null ? mc.getCurrentServerEntry().version : "1.21.8") + "\""
                : "\"1.21.8\"");
        host.setQuery("stack_size", args -> String.valueOf(
                GameTables.stackSizeOf(args.has("id") ? args.get("id").getAsString() : null)));
        host.setQuery("weapon_score", args -> {
            String id = args.has("id") ? args.get("id").getAsString() : null;
            return id != null && GameTables.WEAPONS.containsKey(id)
                    ? String.valueOf(GameTables.WEAPONS.get(id)) : "";
        });
        host.setQuery("dimension", args -> mc.world != null
                ? "\"" + mc.world.getRegistryKey().getValue() + "\"" : "null");
    }

    private JsonObject windowJson(WindowState w) {
        JsonObject o = new JsonObject();
        o.addProperty("id", w.id);
        o.addProperty("type", w.type);
        o.addProperty("title", "");
        o.addProperty("size", w.size);
        JsonArray slots = new JsonArray();
        for (int i = 0; i < w.size + 36; i++) {
            JsonObject sl = new JsonObject();
            sl.addProperty("index", i);
            JsonObject item = w.slots.get(i);
            sl.add("item", item != null ? item : com.google.gson.JsonNull.INSTANCE);
            slots.add(sl);
        }
        o.add("slots", slots);
        o.add("cursor", w.cursor != null ? w.cursor : com.google.gson.JsonNull.INSTANCE);
        o.addProperty("revision", w.revision);
        return o;
    }

    /** 容器打开时的背包合并视图（engine __q_window '0' 对应物） */
    private JsonObject mergedInventoryJson(WindowState cont) {
        JsonObject o = new JsonObject();
        o.addProperty("id", "0");
        o.addProperty("type", "inventory");
        o.addProperty("title", "背包");
        o.addProperty("size", 36);
        JsonArray slots = new JsonArray();
        JsonObject[] inv = new JsonObject[36];
        for (var e : cont.slots.entrySet()) {
            int idx = e.getKey();
            int invIdx = -1;
            if (idx >= cont.size && idx < cont.size + 27) invIdx = idx - cont.size + 9;
            else if (idx >= cont.size + 27 && idx < cont.size + 36) invIdx = idx - cont.size - 27;
            if (invIdx >= 0) inv[invIdx] = e.getValue();
        }
        for (int i = 0; i < 36; i++) {
            JsonObject sl = new JsonObject();
            sl.addProperty("index", i);
            sl.add("item", inv[i] != null ? inv[i] : com.google.gson.JsonNull.INSTANCE);
            slots.add(sl);
        }
        o.add("slots", slots);
        o.add("cursor", cont.cursor != null ? cont.cursor : com.google.gson.JsonNull.INSTANCE);
        o.addProperty("revision", cont.revision);
        return o;
    }

    private void registerActions() {
        // 所有动作经 onClient 包装：MC 工作在渲染线程执行，token 异步结算
        java.util.function.BiConsumer<String, LuaHost.ActionHandler> register = (name, fn) ->
                host.registerAction(name, onClient(fn));

        // ---- chat ----
        register.accept("chat_send", (token, args, taskId) -> {
            String text = args.has("text") ? args.get("text").getAsString() : "";
            String polErr = host.policy() != null ? host.policy().checkChatPolicy(text) : null;
            if (polErr != null) return new String[]{"", polErr};
            var p = player();
            if (p == null) return new String[]{"", LuaHost.errJson("session.offline", "未进世界")};
            if (text.startsWith("/")) p.networkHandler.sendChatCommand(text.substring(1));
            else p.networkHandler.sendChatMessage(text);
            return new String[]{"{}", ""};
        });
        register.accept("chat_reply", (token, args, taskId) -> {
            String text = args.has("text") ? args.get("text").getAsString() : "";
            String polErr = host.policy() != null ? host.policy().checkChatPolicy(text) : null;
            if (polErr != null) return new String[]{"", polErr};
            var p = player();
            if (p == null) return new String[]{"", LuaHost.errJson("session.offline", "未进世界")};
            String to = args.has("to") && !args.get("to").isJsonNull() ? args.get("to").getAsString() : null;
            String msgCmd = cfg.msgCommand;
            String out;
            if (to != null && msgCmd != null) out = msgCmd + " " + to + " " + text;
            else if (to != null) out = to + " " + text;
            else out = text;
            if (out.startsWith("/")) p.networkHandler.sendChatCommand(out.substring(1));
            else p.networkHandler.sendChatMessage(out);
            return new String[]{"{}", ""};
        });
        // ---- rot / hop / nav ----
        register.accept("rot", (token, args, taskId) -> {
            if (openMachine != null) return new String[]{"{}", ""};   // rotHold：开箱关键段持有视角
            var p = player();
            if (p == null) return new String[]{"", LuaHost.errJson("session.offline", "未进世界")};
            if (args.has("yaw") && !args.get("yaw").isJsonNull()) {
                float yaw = args.get("yaw").getAsFloat();
                p.setYaw(((yaw % 360) + 540) % 360 - 180);
            }
            if (args.has("pitch") && !args.get("pitch").isJsonNull()) {
                float pitch = args.get("pitch").getAsFloat();
                p.setPitch(Math.max(-90f, Math.min(90f, pitch)));
            }
            return new String[]{"{}", ""};
        });
        register.accept("hop", (token, args, taskId) -> {
            moveInput(0, 0, 1, 0, 0);
            hopReleaseTick = tickCounter + 5;   // 5 tick 后由 tick() 释放（nav 期间不释放，避免打断跳跃）
            return new String[]{"{}", ""};
        });
        register.accept("nav_stop", (token, args, taskId) -> {
            if (gotoMachine != null) gotoMachine.stop("cancel");
            if (followMachine != null) followMachine.stop();
            stopMove();
            return new String[]{"{}", ""};
        });
        register.accept("nav_goto", (token, args, taskId) -> {
            double[] target = targetOf(args);
            if (target == null) {
                JsonObject ent = entities.get(entityIdOf(args));
                if (ent == null) return new String[]{"", LuaHost.errJson("entity.gone", "nav.walk 目标实体不存在")};
                JsonObject pos = ent.getAsJsonObject("pos");
                target = new double[]{pos.get("x").getAsDouble(), pos.get("y").getAsDouble(), pos.get("z").getAsDouble()};
            }
            JsonObject opts = args.has("opts") && args.get("opts").isJsonObject() ? args.getAsJsonObject("opts") : new JsonObject();
            double arrive = opts.has("arrive") ? opts.get("arrive").getAsDouble() : 1.5;
            long timeout = opts.has("timeout") ? (long) (opts.get("timeout").getAsDouble() * 1000) : 30_000;
            if (followMachine != null) followMachine.stop();
            GotoState g = new GotoState(target, arrive, timeout, token, true);
            if (gotoMachine != null) gotoMachine.stop("cancel");
            gotoMachine = g;
            host.addMachine(g);
            return null;   // 自管 token：机器结算
        });
        register.accept("nav_follow", (token, args, taskId) -> {
            String eid = args.has("entity_id") ? String.valueOf(args.get("entity_id").getAsString()) : "";
            JsonObject opts = args.has("opts") && args.get("opts").isJsonObject() ? args.getAsJsonObject("opts") : new JsonObject();
            double want = opts.has("distance") ? opts.get("distance").getAsDouble() : 2;
            if (!entities.containsKey(eid)) {
                return new String[]{"{\"ok\":false,\"reason\":\"entity.gone\"}", ""};
            }
            if (gotoMachine != null) gotoMachine.stop("cancel");
            if (followMachine != null) followMachine.stop();
            followMachine = new FollowState(eid, want, token);
            host.addMachine(followMachine);
            return null;   // 自管
        });
        // ---- world ----
        register.accept("world_scan", (token, args, taskId) -> {
            if (!args.has("region")) return new String[]{"{\"list\":[]}", ""};
            var region = args.getAsJsonObject("region");
            double[] min = new double[3], max = new double[3];
            fillCorner(region, "corner1", min);
            fillCorner(region, "corner2", max);
            for (int i = 0; i < 3; i++) {
                double lo = Math.min(min[i], max[i]), hi = Math.max(min[i], max[i]);
                min[i] = lo;
                max[i] = hi;
            }
            JsonObject r = new JsonObject();
            r.add("list", scanContainers(min, max, 200));
            return new String[]{r.toString(), ""};
        });
        // ---- 容器 ----
        register.accept("container_open", (token, args, taskId) -> {
            double[] target = targetOf(args);
            if (target == null) return new String[]{"", LuaHost.errJson("container.missing", String.valueOf(args))};
            // 窗口互斥：先关再开
            closeCurrentWindow();
            if (openMachine != null) openMachine.failQuiet();
            ContainerOpenState st = new ContainerOpenState(target, token);
            double[] p = selfPos(mc);
            if (p != null && dist3(p[0], p[1], p[2], target[0], target[1], target[2]) > 2.5) {
                st.gotoTo = target;
                st.arriveOverride = 1.2;
            } else {
                st.phase = 1;   // 已贴近：直接 aim
            }
            openMachine = st;
            host.addMachine(st);
            return null;   // 自管
        });
        register.accept("container_close", (token, args, taskId) -> {
            closeCurrentWindow();
            return new String[]{"{}", ""};
        });
        register.accept("window_click", (token, args, taskId) -> {
            String winId = args.has("window") ? String.valueOf(args.get("window").getAsString()) : "0";
            int slot = args.get("slot").getAsInt();
            int button = args.has("button") ? args.get("button").getAsInt() : 0;
            int mode = args.has("mode") ? args.get("mode").getAsInt() : 0;
            String targetId = winId;
            if ("0".equals(winId) && currentWindowId != null) {
                WindowState cont = windows.get(currentWindowId);
                if (cont != null) {
                    // 背包槽位 -> 容器窗口玩家区
                    int size = cont.size;
                    slot = slot >= 9 ? size + (slot - 9) : size + 27 + slot;
                    targetId = cont.id;
                }
            }
            modeOfPendingClick = mode;
            buttonOfPendingClick = button;
            ClickState st = new ClickState(targetId, slot, button, mode, token);
            if (clickMachine != null) clickMachine.fail("container.closed");
            clickMachine = st;
            host.addMachine(st);
            return null;   // 自管
        });
        // ---- 背包 ----
        register.accept("inv_equip", (token, args, taskId) -> {
            int slot;
            if (args.has("slot") && args.get("slot").isJsonPrimitive()) {
                slot = args.get("slot").getAsInt();
            } else {
                JsonObject filter = args.has("slot") && args.get("slot").isJsonObject()
                        ? args.getAsJsonObject("slot") : new JsonObject();
                slot = -1;
                WindowState inv = windows.get("0");
                if (inv != null) {
                    for (int i = 0; i <= 35; i++) {
                        JsonObject it = inv.slots.get(i);
                        if (it == null) continue;
                        if (!filter.has("id") || filter.get("id").getAsString().equals(it.get("id").getAsString())) {
                            slot = i;
                            break;
                        }
                    }
                }
                if (slot < 0) return new String[]{"{\"ok\":false}", ""};
            }
            JsonObject self = selfJson();
            int heldSlot = self != null && self.has("heldSlot") ? self.get("heldSlot").getAsInt() : 0;
            if (slot == heldSlot) return new String[]{"{\"ok\":true}", ""};
            // 通过 window_click 机器串行执行（P2：直接同步发点击，内容确认由机器版本覆盖关键路径）
            if (slot >= 0 && slot <= 8) {
                enqueueClicks(new int[][]{{slot, heldSlot, 2}});
            } else {
                enqueueClicks(new int[][]{{slot, 0, 0}, {heldSlot, 0, 0}, {slot, 0, 0}});
            }
            return new String[]{"{\"ok\":true}", ""};
        });
        register.accept("inv_drop", (token, args, taskId) -> new String[]{"{}", ""});
        // ---- 交互 ----
        register.accept("use_entity", (token, args, taskId) -> {
            var p = player();
            if (p == null || mc.world == null) return new String[]{"", LuaHost.errJson("session.offline", "未进世界")};
            var target = findEntity(args.has("id") ? args.get("id").getAsString() : "");
            if (target == null) return new String[]{"", LuaHost.errJson("entity.gone", String.valueOf(args))};
            String kind = args.has("kind") ? args.get("kind").getAsString() : "attack";
            if ("attack".equals(kind)) {
                lastAttack = System.currentTimeMillis();
                JsonObject self = selfJson();
                lastAttackWeapon = self != null && self.has("held") ? self.getAsJsonObject("held").get("id").getAsString() : null;
                mc.interactionManager.attackEntity(p, target);
            } else {
                mc.interactionManager.interactEntity(p, target, Hand.MAIN_HAND);
            }
            p.swingHand(Hand.MAIN_HAND);
            return new String[]{"{}", ""};
        });
        register.accept("use_block", (token, args, taskId) -> {
            var p = player();
            if (p == null) return new String[]{"", LuaHost.errJson("session.offline", "未进世界")};
            JsonObject pos = args.getAsJsonObject("pos");
            BlockPos bp = BlockPos.ofFloored(pos.get("x").getAsDouble(), pos.get("y").getAsDouble(), pos.get("z").getAsDouble());
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(bp), Direction.UP, bp, false);
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
            return new String[]{"{}", ""};
        });
        register.accept("use_item", (token, args, taskId) -> {
            var p = player();
            if (p == null) return new String[]{"", LuaHost.errJson("session.offline", "未进世界")};
            long dur = args.has("duration") ? (long) args.get("duration").getAsDouble() : 0;
            mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
            mc.options.useKey.setPressed(true);
            long finalDur = Math.max(0, dur);
            host.addMachine(new LuaHost.Tickable() {
                long until = System.currentTimeMillis() + finalDur;
                boolean done;

                @Override public void tick() {
                    if (done) return;
                    if (System.currentTimeMillis() >= until) {
                        done = true;
                        mc.options.useKey.setPressed(false);
                        host.settle(token, "{}", "");
                    }
                }
            });
            return finalDur == 0 ? new String[]{"{}", ""} : null;   // 有时长：自管等 release
        });
        register.accept("dig", (token, args, taskId) -> {
            var p = player();
            if (p == null) return new String[]{"", LuaHost.errJson("session.offline", "未进世界")};
            JsonObject pos = args.getAsJsonObject("pos");
            BlockPos bp = BlockPos.ofFloored(pos.get("x").getAsDouble(), pos.get("y").getAsDouble(), pos.get("z").getAsDouble());
            mc.interactionManager.attackBlock(bp, Direction.UP);
            return new String[]{"{}", ""};
        });
    }

    private void enqueueClicks(int[][] clicks) {
        // 简化：逐个同步发出（inv_equip 非关键路径；窗口点击关键路径走 ClickState 机器）
        var p = player();
        if (p == null) return;
        ScreenHandler handler = p.playerScreenHandler;
        for (int[] c : clicks) {
            int raw = c[0] < 9 ? c[0] + 36 : c[0];
            if (raw >= handler.getStacks().size()) continue;
            mc.interactionManager.clickSlot(handler.syncId, raw, c[1], c[2] == 2 ? SlotActionType.SWAP : SlotActionType.PICKUP, p);
        }
    }

    private net.minecraft.entity.Entity findEntity(String id) {
        if (mc.world == null) return null;
        try {
            int eid = Integer.parseInt(id);
            return mc.world.getEntityById(eid);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double[] targetOf(JsonObject args) {
        if (!args.has("target")) return null;
        var t = args.get("target");
        if (t.isJsonObject() && t.getAsJsonObject().has("x")) {
            JsonObject p = t.getAsJsonObject();
            return new double[]{Math.round(p.get("x").getAsDouble()), Math.round(p.get("y").getAsDouble()),
                    Math.round(p.get("z").getAsDouble())};
        }
        return null;
    }

    private static String entityIdOf(JsonObject args) {
        if (!args.has("target")) return "";
        var t = args.get("target");
        if (t.isJsonObject() && t.getAsJsonObject().has("id")) return t.getAsJsonObject().get("id").getAsString();
        if (t.isJsonPrimitive()) return t.getAsString();
        return "";
    }

    private static void fillCorner(JsonObject region, String key, double[] out) {
        if (!region.has(key)) return;
        var c = region.getAsJsonObject(key);
        out[0] = c.get("x").getAsDouble();
        out[1] = c.get("y").getAsDouble();
        out[2] = c.get("z").getAsDouble();
    }

}
