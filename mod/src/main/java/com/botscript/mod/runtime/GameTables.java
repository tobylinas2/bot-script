package com.botscript.mod.runtime;

import java.util.Map;

/** 引擎静态数据表（engine.js 顶部对应物）。 */
public final class GameTables {
    private GameTables() {}

    public static final Map<String, Integer> WEAPONS = Map.ofEntries(
            Map.entry("minecraft:wooden_sword", 4), Map.entry("minecraft:stone_sword", 5),
            Map.entry("minecraft:iron_sword", 6), Map.entry("minecraft:diamond_sword", 7),
            Map.entry("minecraft:netherite_sword", 8), Map.entry("minecraft:wooden_axe", 7),
            Map.entry("minecraft:stone_axe", 9), Map.entry("minecraft:iron_axe", 9),
            Map.entry("minecraft:diamond_axe", 9), Map.entry("minecraft:netherite_axe", 10),
            Map.entry("minecraft:trident", 9), Map.entry("minecraft:mace", 6));

    public static final Map<String, Double> ATTACK_SPEED = Map.ofEntries(
            Map.entry("minecraft:wooden_sword", 1.6), Map.entry("minecraft:stone_sword", 1.6),
            Map.entry("minecraft:iron_sword", 1.6), Map.entry("minecraft:diamond_sword", 1.6),
            Map.entry("minecraft:netherite_sword", 1.6), Map.entry("minecraft:wooden_axe", 0.8),
            Map.entry("minecraft:stone_axe", 0.8), Map.entry("minecraft:iron_axe", 1.0),
            Map.entry("minecraft:diamond_axe", 1.0), Map.entry("minecraft:netherite_axe", 1.0),
            Map.entry("minecraft:trident", 1.1), Map.entry("minecraft:mace", 0.6));

    /** 可堆叠数：16 / 1 / 其余 64（engine.js stackSizeOf 简表） */
    public static int stackSizeOf(String id) {
        if (id == null) return 64;
        if (id.endsWith("_shulker_box") || id.equals("minecraft:white_shulker_box")
                || id.endsWith("shulker_box")) return 1;
        return switch (id) {
                case "minecraft:snowball", "minecraft:egg", "minecraft:honeycomb", "minecraft:pearl",
                     "minecraft:ender_pearl", "minecraft:sign", "minecraft:oak_sign", "minecraft:bucket",
                     "minecraft:iron_bucket_placeholder" -> 16;
                default -> id.endsWith("_banner") || id.endsWith("_boat") || id.endsWith("_chestplate")
                        || id.endsWith("_leggings") || id.endsWith("_helmet") || id.endsWith("_boots")
                        || id.endsWith("_sword") || id.endsWith("_pickaxe") || id.endsWith("_axe")
                        || id.endsWith("_shovel") || id.endsWith("_hoe") ? 1 : 64;
        };
    }

    public static double weaponScore(String id) {
        Integer s = WEAPONS.get(id);
        return s == null ? 1.0 : s;
    }

    public static long weaponCooldownMs(String heldId) {
        double spd = (heldId != null && ATTACK_SPEED.containsKey(heldId)) ? ATTACK_SPEED.get(heldId) : 4.0;
        return (long) Math.ceil(600 / spd);
    }

    public static final java.util.Set<String> CONTAINER_BLOCKS = java.util.Set.of(
            "chest", "trapped_chest", "barrel", "ender_chest", "shulker_box",
            "white_shulker_box", "orange_shulker_box", "magenta_shulker_box", "light_blue_shulker_box",
            "yellow_shulker_box", "lime_shulker_box", "pink_shulker_box", "gray_shulker_box",
            "light_gray_shulker_box", "cyan_shulker_box", "purple_shulker_box", "blue_shulker_box",
            "brown_shulker_box", "green_shulker_box", "red_shulker_box", "black_shulker_box",
            "furnace", "blast_furnace", "smoker", "hopper", "dispenser", "dropper", "brewing_stand");

    public static boolean isContainerName(String namespaced) {
        String n = namespaced == null ? "" : namespaced.replace("minecraft:", "");
        return CONTAINER_BLOCKS.contains(n);
    }
}
