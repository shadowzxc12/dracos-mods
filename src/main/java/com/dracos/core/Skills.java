package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** mcMMO-style skills: Mining, Woodcutting, Farming, Combat, Fishing. Levels 1-100 with perks. /skills */
public final class Skills {
    private Skills() {}

    enum Skill {
        MINING("Mining", Items.DIAMOND_PICKAXE, Formatting.AQUA, "Double ore drops"),
        WOODCUTTING("Woodcutting", Items.IRON_AXE, Formatting.GOLD, "Double log drops"),
        FARMING("Farming", Items.GOLDEN_HOE, Formatting.GREEN, "Double crop drops"),
        COMBAT("Combat", Items.DIAMOND_SWORD, Formatting.RED, "Extra attack damage"),
        FISHING("Fishing", Items.FISHING_ROD, Formatting.BLUE, "Extra fish");

        final String title; final Item icon; final Formatting color; final String perk;
        Skill(String t, Item i, Formatting c, String p) { title = t; icon = i; color = c; perk = p; }
    }

    static final int MAX = 100;
    private static final Identifier COMBAT_ID = Identifier.of("dracos_core", "combat_skill");

    static final class PData { Map<String, Integer> lvl = new HashMap<>(); Map<String, Long> xp = new HashMap<>(); }

    private static final Gson GSON = new Gson();
    private static Map<String, PData> DATA = new HashMap<>();
    private static boolean dirty;
    private static int ticks;

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("skills.json"); }

    public static void load() {
        try {
            if (Files.exists(file())) {
                Map<String, PData> m = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, PData>>() {}.getType());
                if (m != null) DATA = new HashMap<>(m);
            }
        } catch (Exception e) { DracosCore.LOGGER.warn("skills.json unreadable", e); }
    }

    static void save() {
        if (!dirty) return;
        dirty = false;
        try { Files.createDirectories(file().getParent()); Files.writeString(file(), GSON.toJson(DATA)); }
        catch (Exception e) { DracosCore.LOGGER.warn("skills.json save failed", e); }
    }

    static PData of(ServerPlayerEntity p) { return DATA.computeIfAbsent(p.getUuidAsString(), k -> new PData()); }
    public static int level(ServerPlayerEntity p, Skill s) { return Math.max(1, of(p).lvl.getOrDefault(s.name(), 1)); }
    static long xp(ServerPlayerEntity p, Skill s) { return of(p).xp.getOrDefault(s.name(), 0L); }
    static long need(int level) { return 50L + level * 25L; }

    /** Chance (0..1) for the level perk: 0.5% per level, max 50%. */
    static double chance(ServerPlayerEntity p, Skill s) { return level(p, s) * 0.005; }

    static void give(ServerPlayerEntity p, Skill s, long amount) {
        if (amount <= 0) return;
        PData d = of(p);
        int lvl = level(p, s);
        if (lvl >= MAX) return;
        long x = d.xp.getOrDefault(s.name(), 0L) + amount;
        while (lvl < MAX && x >= need(lvl)) { x -= need(lvl); lvl++; levelUp(p, s, lvl); }
        d.lvl.put(s.name(), lvl);
        d.xp.put(s.name(), lvl >= MAX ? 0 : x);
        dirty = true;
        if (ticks % 2 == 0) p.sendMessage(Text.literal("+" + amount + " " + s.title + " XP").formatted(s.color)
                .append(Text.literal("  (" + d.xp.get(s.name()) + "/" + need(lvl) + ")").formatted(Formatting.DARK_GRAY)), true);
    }

    private static void levelUp(ServerPlayerEntity p, Skill s, int lvl) {
        p.sendMessage(Text.literal("⬆ " + s.title + " level " + lvl + "! ").formatted(s.color, Formatting.BOLD)
                .append(Text.literal(s.perk + ": " + perkText(s, lvl)).formatted(Formatting.GRAY)), false);
        p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.7f, 1.2f);
        if (lvl % 25 == 0)
            p.getEntityWorld().getServer().getPlayerManager().broadcast(Text.literal("★ ").formatted(Formatting.GOLD)
                    .append(Text.literal(p.getName().getString()).formatted(Formatting.WHITE, Formatting.BOLD))
                    .append(Text.literal(" reached " + s.title + " level " + lvl + "!").formatted(s.color)), false);
        if (s == Skill.COMBAT) applyCombat(p, lvl);
    }

    static String perkText(Skill s, int lvl) {
        return s == Skill.COMBAT ? "+" + String.format(java.util.Locale.ROOT, "%.1f", lvl * 0.02) + " damage"
                : String.format(java.util.Locale.ROOT, "%.1f%%", lvl * 0.5) + " chance";
    }

    static void applyCombat(ServerPlayerEntity p, int lvl) {
        EntityAttributeInstance a = p.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE);
        if (a == null) return;
        a.removeModifier(COMBAT_ID);
        if (lvl > 1) a.addPersistentModifier(new EntityAttributeModifier(COMBAT_ID, lvl * 0.02, EntityAttributeModifier.Operation.ADD_VALUE));
    }

    private static boolean silk(ServerPlayerEntity p) {
        var reg = p.getEntityWorld().getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        return EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.SILK_TOUCH), p.getMainHandStack()) > 0;
    }

    static int oreXp(String id) {
        if (id.contains("diamond") || id.contains("emerald") || id.equals("minecraft:ancient_debris")) return 30;
        if (id.contains("gold") || id.contains("lapis") || id.contains("redstone")) return 12;
        if (id.contains("iron") || id.contains("quartz") || id.contains("copper")) return 8;
        return 5;
    }

    private static void doubleDrop(ServerPlayerEntity p, ServerWorld w, net.minecraft.util.math.BlockPos pos, BlockState state,
                                   net.minecraft.block.entity.BlockEntity be, Skill s) {
        if (ThreadLocalRandom.current().nextDouble() >= chance(p, s)) return;
        for (ItemStack drop : Block.getDroppedStacks(state, w, pos, be, p, p.getMainHandStack())) Block.dropStack(w, pos, drop.copy());
    }

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (!(player instanceof ServerPlayerEntity p) || p.isCreative() || !(world instanceof ServerWorld w)) return;
            String id = Registries.BLOCK.getId(state.getBlock()).toString();
            if (id.endsWith("_ore") || state.isOf(Blocks.ANCIENT_DEBRIS)) {
                if (silk(p)) return; // no XP farming with placed ores
                give(p, Skill.MINING, oreXp(id));
                doubleDrop(p, w, pos, state, be, Skill.MINING);
            } else if (state.isIn(BlockTags.LOGS)) {
                give(p, Skill.WOODCUTTING, 4);
                doubleDrop(p, w, pos, state, be, Skill.WOODCUTTING);
            } else if (state.getBlock() instanceof CropBlock crop) {
                if (!crop.isMature(state)) return;
                give(p, Skill.FARMING, 3);
                doubleDrop(p, w, pos, state, be, Skill.FARMING);
            } else if (state.isOf(Blocks.MELON) || state.isOf(Blocks.PUMPKIN) || state.isOf(Blocks.SUGAR_CANE) || state.isOf(Blocks.CACTUS)
                    || state.isOf(Blocks.NETHER_WART) || state.isOf(Blocks.COCOA)) {
                give(p, Skill.FARMING, 2);
                doubleDrop(p, w, pos, state, be, Skill.FARMING);
            } else if (state.isIn(BlockTags.PICKAXE_MINEABLE)) {
                give(p, Skill.MINING, 1);
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(source.getAttacker() instanceof ServerPlayerEntity p) || entity == p) return;
            int n = Math.max(1, MobStacker.count(entity));
            give(p, Skill.COMBAT, (long) (entity instanceof PlayerEntity ? 25 : entity instanceof Monster ? 8 : 4) * n);
        });
        ServerPlayConnectionEvents.JOIN.register((h, s, server) -> applyCombat(h.player, level(h.player, Skill.COMBAT)));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (++ticks % 600 == 0) save(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { dirty = true; save(); });
    }

    /** Called by the fishing mixin when a player reels in a catch. */
    public static void onFish(ServerPlayerEntity p) {
        give(p, Skill.FISHING, 15);
        if (ThreadLocalRandom.current().nextDouble() < chance(p, Skill.FISHING)) {
            Item[] fish = {Items.COD, Items.SALMON, Items.TROPICAL_FISH, Items.COD, Items.SALMON};
            p.getInventory().offerOrDrop(new ItemStack(fish[ThreadLocalRandom.current().nextInt(fish.length)]));
            p.sendMessage(Text.literal("Lucky catch! +1 fish").formatted(Formatting.BLUE), true);
        }
    }

    // ------------------------------------------------------------------ GUI
    public static void open(ServerPlayerEntity p) {
        SimpleInventory inv = new SimpleInventory(27);
        ItemStack pane = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        pane.set(DataComponentTypes.ITEM_NAME, Text.literal(" "));
        for (int i = 0; i < 27; i++) inv.setStack(i, pane.copy());
        int[] slots = {11, 12, 13, 14, 15};
        Skill[] all = Skill.values();
        for (int i = 0; i < all.length; i++) {
            Skill s = all[i];
            int lvl = level(p, s);
            long x = xp(p, s), n = need(lvl);
            int fill = lvl >= MAX ? 10 : (int) (10 * x / n);
            ItemStack st = new ItemStack(s.icon);
            st.set(DataComponentTypes.ITEM_NAME, Text.literal(s.title + " " + lvl).formatted(s.color, Formatting.BOLD));
            List<Text> lore = new ArrayList<>();
            lore.add(Text.literal("■".repeat(fill) + "□".repeat(10 - fill) + "  " + (lvl >= MAX ? "MAX" : x + "/" + n))
                    .styled(t -> t.withColor(s.color).withItalic(false)));
            lore.add(Text.literal(s.perk + ": " + perkText(s, lvl)).styled(t -> t.withColor(Formatting.GRAY).withItalic(false)));
            lore.add(Text.literal(howTo(s)).styled(t -> t.withColor(Formatting.DARK_GRAY).withItalic(false)));
            st.set(DataComponentTypes.LORE, new LoreComponent(lore));
            st.set(DataComponentTypes.MAX_STACK_SIZE, 99);
            st.setCount(Math.min(99, lvl));
            inv.setStack(slots[i], st);
        }
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, pi, pl) -> new Handler(sync, pi, inv),
                Text.literal("Skills").formatted(Formatting.DARK_AQUA, Formatting.BOLD)));
    }

    static String howTo(Skill s) {
        return switch (s) {
            case MINING -> "Mine stone and ores";
            case WOODCUTTING -> "Chop logs";
            case FARMING -> "Harvest grown crops";
            case COMBAT -> "Kill mobs and players";
            case FISHING -> "Catch fish";
        };
    }

    static class Handler extends GenericContainerScreenHandler {
        Handler(int sync, PlayerInventory pi, SimpleInventory inv) { super(ScreenHandlerType.GENERIC_9X3, sync, pi, inv, 3); }
        @Override public void onSlotClick(int slot, int button, SlotActionType type, PlayerEntity player) { this.syncState(); }
        @Override public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
    }
}
