package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/** /quests - 3 random daily quests per player (reset at midnight, Bratislava time). */
public final class Quests {
    private Quests() {}

    enum Kind { MINE, HARVEST, KILL, SELL }

    record Def(String id, String text, Kind kind, Predicate<String> match, int goal, long reward, Item icon) {}

    private static Predicate<String> any(String... ids) {
        Set<String> s = Set.of(ids);
        return s::contains;
    }

    static final List<Def> POOL = List.of(
            new Def("stone", "Mine 500 stone", Kind.MINE, any("minecraft:stone", "minecraft:cobblestone", "minecraft:deepslate",
                    "minecraft:cobbled_deepslate", "minecraft:tuff", "minecraft:andesite", "minecraft:diorite", "minecraft:granite"), 500, 25_000, Items.STONE_PICKAXE),
            new Def("coal", "Mine 64 coal ore", Kind.MINE, any("minecraft:coal_ore", "minecraft:deepslate_coal_ore"), 64, 30_000, Items.COAL),
            new Def("iron", "Mine 32 iron ore", Kind.MINE, any("minecraft:iron_ore", "minecraft:deepslate_iron_ore"), 32, 45_000, Items.RAW_IRON),
            new Def("gold", "Mine 16 gold ore", Kind.MINE, any("minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore"), 16, 45_000, Items.RAW_GOLD),
            new Def("diamond", "Mine 8 diamond ore", Kind.MINE, any("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"), 8, 90_000, Items.DIAMOND),
            new Def("logs", "Chop 128 logs", Kind.MINE, s -> s.endsWith("_log") || s.endsWith("_stem"), 128, 30_000, Items.IRON_AXE),
            new Def("netherrack", "Mine 300 netherrack", Kind.MINE, any("minecraft:netherrack"), 300, 30_000, Items.NETHERRACK),
            new Def("crops", "Harvest 200 crops", Kind.HARVEST, s -> true, 200, 35_000, Items.WHEAT),
            new Def("zombie", "Kill 30 zombies", Kind.KILL, any("minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager"), 30, 30_000, Items.ROTTEN_FLESH),
            new Def("skeleton", "Kill 30 skeletons", Kind.KILL, any("minecraft:skeleton", "minecraft:stray", "minecraft:bogged"), 30, 35_000, Items.BONE),
            new Def("creeper", "Kill 15 creepers", Kind.KILL, any("minecraft:creeper"), 15, 40_000, Items.GUNPOWDER),
            new Def("spider", "Kill 25 spiders", Kind.KILL, any("minecraft:spider", "minecraft:cave_spider"), 25, 30_000, Items.STRING),
            new Def("enderman", "Kill 10 endermen", Kind.KILL, any("minecraft:enderman"), 10, 60_000, Items.ENDER_PEARL),
            new Def("mobs", "Kill 100 mobs", Kind.KILL, s -> !s.equals("minecraft:player"), 100, 60_000, Items.IRON_SWORD),
            new Def("players", "Kill 3 players", Kind.KILL, any("minecraft:player"), 3, 80_000, Items.PLAYER_HEAD),
            new Def("sell", "Earn $150,000 with /sell", Kind.SELL, s -> true, 150_000, 50_000, Items.GOLD_INGOT));

    static final long ALL_DONE_BONUS = 75_000;
    private static final ZoneId ZONE = ZoneId.of("Europe/Bratislava");

    static final class PData {
        String day;
        List<String> ids = new ArrayList<>();
        int[] progress = new int[3];
        boolean[] done = new boolean[3];
    }

    private static final Gson GSON = new Gson();
    private static Map<String, PData> DATA = new HashMap<>();
    private static boolean dirty;
    private static int ticks;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("quests.json");
    }

    public static void load() {
        try {
            if (Files.exists(file())) {
                Map<String, PData> m = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, PData>>() {}.getType());
                if (m != null) DATA = new HashMap<>(m);
            }
        } catch (Exception e) {
            DracosCore.LOGGER.warn("quests.json unreadable", e);
        }
    }

    static void save() {
        if (!dirty) return;
        dirty = false;
        try {
            String today = today();
            DATA.values().removeIf(d -> !today.equals(d.day));
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(DATA));
        } catch (Exception e) {
            DracosCore.LOGGER.warn("quests.json save failed", e);
        }
    }

    static String today() {
        return LocalDate.now(ZONE).toString();
    }

    static Def def(String id) {
        for (Def d : POOL) if (d.id.equals(id)) return d;
        return null;
    }

    static PData data(ServerPlayerEntity p) {
        String u = p.getUuidAsString(), day = today();
        PData d = DATA.get(u);
        if (d == null || !day.equals(d.day) || d.ids.size() != 3 || d.ids.stream().anyMatch(i -> def(i) == null)) {
            d = new PData();
            d.day = day;
            List<Def> pool = new ArrayList<>(POOL);
            Random r = new Random((u + day).hashCode());
            for (int i = 0; i < 3; i++) d.ids.add(pool.remove(r.nextInt(pool.size())).id);
            DATA.put(u, d);
            dirty = true;
        }
        return d;
    }

    static void progress(ServerPlayerEntity p, Kind kind, String target, int amount) {
        PData d = data(p);
        for (int i = 0; i < 3; i++) {
            if (d.done[i]) continue;
            Def q = def(d.ids.get(i));
            if (q.kind != kind || !q.match.test(target)) continue;
            d.progress[i] = (int) Math.min(q.goal, (long) d.progress[i] + amount);
            dirty = true;
            if (d.progress[i] >= q.goal) complete(p, d, i, q);
        }
    }

    private static void complete(ServerPlayerEntity p, PData d, int i, Def q) {
        d.done[i] = true;
        Money.add(p, q.reward);
        p.sendMessage(Text.literal("✔ Quest complete: ").formatted(Formatting.GREEN, Formatting.BOLD)
                .append(Text.literal(q.text).formatted(Formatting.WHITE))
                .append(Text.literal("  +" + Money.fmt(q.reward)).formatted(Formatting.GOLD, Formatting.BOLD)), false);
        p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.8f, 1.2f);
        if (d.done[0] && d.done[1] && d.done[2]) {
            Money.add(p, ALL_DONE_BONUS);
            p.sendMessage(Text.literal("★ All daily quests done! Bonus ").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)
                    .append(Text.literal("+" + Money.fmt(ALL_DONE_BONUS)).formatted(Formatting.GOLD, Formatting.BOLD)), false);
        }
    }

    /** Called from /sell with the money the player just earned. */
    public static void onSell(ServerPlayerEntity p, long earned) {
        if (earned > 0) progress(p, Kind.SELL, "", (int) Math.min(Integer.MAX_VALUE, earned));
    }

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (!(player instanceof ServerPlayerEntity sp) || player.isCreative()) return;
            String id = Registries.BLOCK.getId(state.getBlock()).toString();
            if (isHarvest(state, id)) progress(sp, Kind.HARVEST, id, 1);
            else progress(sp, Kind.MINE, id, 1);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (source.getAttacker() instanceof ServerPlayerEntity sp && entity != sp)
                progress(sp, Kind.KILL, Registries.ENTITY_TYPE.getId(entity.getType()).toString(), 1);
        });
        ServerPlayConnectionEvents.JOIN.register((h, s, server) -> {
            PData d = data(h.player);
            int left = 0;
            for (boolean b : d.done) if (!b) left++;
            if (left > 0) h.player.sendMessage(Text.literal("You have " + left + " daily quest(s) left! ").formatted(Formatting.YELLOW)
                    .append(Text.literal("[/quests]").styled(st -> st.withColor(Formatting.GREEN).withBold(true)
                            .withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/quests")))), false);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks % 400 == 0) Spawners.tick(server);
            if (ticks % 600 == 0) { save(); Spawners.saveIfDirty(); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { dirty = true; save(); Spawners.saveIfDirty(); });
    }

    private static boolean isHarvest(BlockState state, String id) {
        if (state.getBlock() instanceof CropBlock crop) return crop.isMature(state);
        return id.equals("minecraft:melon") || id.equals("minecraft:pumpkin") || id.equals("minecraft:sugar_cane")
                || id.equals("minecraft:cactus") || id.equals("minecraft:cocoa") && state.toString().contains("age=2")
                || id.equals("minecraft:nether_wart") && state.toString().contains("age=3");
    }

    // ------------------------------------------------------------------ GUI
    public static void open(ServerPlayerEntity p) {
        PData d = data(p);
        SimpleInventory inv = new SimpleInventory(27);
        ItemStack pane = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        pane.set(DataComponentTypes.ITEM_NAME, Text.literal(" "));
        for (int i = 0; i < 27; i++) inv.setStack(i, pane.copy());
        int[] slots = {11, 13, 15};
        for (int i = 0; i < 3; i++) {
            Def q = def(d.ids.get(i));
            boolean done = d.done[i];
            ItemStack s = new ItemStack(done ? Items.LIME_DYE : q.icon);
            s.set(DataComponentTypes.ITEM_NAME, Text.literal(q.text).formatted(done ? Formatting.GREEN : Formatting.YELLOW, Formatting.BOLD));
            List<Text> lore = new ArrayList<>();
            int pr = d.progress[i];
            int filled = (int) Math.round(10.0 * pr / q.goal);
            String bar = "■".repeat(filled) + "□".repeat(10 - filled);
            String num = q.kind == Kind.SELL ? Money.fmt(pr) + " / " + Money.fmt(q.goal) : pr + " / " + q.goal;
            lore.add(Text.literal(bar + " ").styled(st -> st.withColor(done ? Formatting.GREEN : Formatting.GOLD).withItalic(false))
                    .append(Text.literal(num).styled(st -> st.withColor(Formatting.WHITE).withItalic(false))));
            lore.add(Text.literal("Reward: " + Money.fmt(q.reward)).styled(st -> st.withColor(Formatting.GOLD).withItalic(false)));
            lore.add(Text.literal(done ? "✔ Completed" : "In progress").styled(st -> st.withColor(done ? Formatting.GREEN : Formatting.GRAY).withItalic(false)));
            s.set(DataComponentTypes.LORE, new LoreComponent(lore));
            inv.setStack(slots[i], s);
        }
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        Duration left = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(ZONE));
        ItemStack clock = new ItemStack(Items.CLOCK);
        clock.set(DataComponentTypes.ITEM_NAME, Text.literal("New quests in " + left.toHours() + "h " + left.toMinutesPart() + "m").formatted(Formatting.AQUA));
        clock.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("Finish all 3 for a " + Money.fmt(ALL_DONE_BONUS) + " bonus!").styled(st -> st.withColor(Formatting.LIGHT_PURPLE).withItalic(false)))));
        inv.setStack(22, clock);
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, pi, pl) -> new Handler(sync, pi, inv),
                Text.literal("Daily Quests").formatted(Formatting.DARK_GREEN, Formatting.BOLD)));
    }

    static class Handler extends GenericContainerScreenHandler {
        Handler(int sync, PlayerInventory pi, SimpleInventory inv) {
            super(ScreenHandlerType.GENERIC_9X3, sync, pi, inv, 3);
        }

        @Override
        public void onSlotClick(int slot, int button, SlotActionType type, PlayerEntity player) {
            this.syncState();
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }
    }
}
