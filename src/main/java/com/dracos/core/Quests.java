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

    enum Kind { MINE, HARVEST, KILL, SELL, FISH }

    record Def(String id, String text, Kind kind, Predicate<String> match, int goal, long reward, Item icon) {}

    private static Predicate<String> any(String... ids) {
        Set<String> s = Set.of(ids);
        return s::contains;
    }

    private static Predicate<String> ore(String n) {
        return any("minecraft:" + n + "_ore", "minecraft:deepslate_" + n + "_ore");
    }

    static final int COUNT = 5;
    static final long REROLL_COST = 20_000;

    static final List<Def> POOL = List.of(
            // ---- mining
            new Def("stone", "Mine 500 stone", Kind.MINE, any("minecraft:stone", "minecraft:cobblestone", "minecraft:deepslate",
                    "minecraft:cobbled_deepslate", "minecraft:tuff", "minecraft:andesite", "minecraft:diorite", "minecraft:granite"), 500, 25_000, Items.STONE_PICKAXE),
            new Def("stone2", "Mine 1,500 stone", Kind.MINE, any("minecraft:stone", "minecraft:cobblestone", "minecraft:deepslate",
                    "minecraft:cobbled_deepslate", "minecraft:tuff", "minecraft:andesite", "minecraft:diorite", "minecraft:granite"), 1500, 70_000, Items.IRON_PICKAXE),
            new Def("deepslate", "Mine 400 deepslate", Kind.MINE, any("minecraft:deepslate", "minecraft:cobbled_deepslate"), 400, 35_000, Items.DEEPSLATE),
            new Def("coal", "Mine 64 coal ore", Kind.MINE, ore("coal"), 64, 30_000, Items.COAL),
            new Def("copper", "Mine 64 copper ore", Kind.MINE, ore("copper"), 64, 30_000, Items.RAW_COPPER),
            new Def("iron", "Mine 32 iron ore", Kind.MINE, ore("iron"), 32, 45_000, Items.RAW_IRON),
            new Def("gold", "Mine 16 gold ore", Kind.MINE, any("minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore"), 16, 45_000, Items.RAW_GOLD),
            new Def("redstone", "Mine 24 redstone ore", Kind.MINE, ore("redstone"), 24, 40_000, Items.REDSTONE),
            new Def("lapis", "Mine 12 lapis ore", Kind.MINE, ore("lapis"), 12, 45_000, Items.LAPIS_LAZULI),
            new Def("emerald", "Mine 3 emerald ore", Kind.MINE, ore("emerald"), 3, 70_000, Items.EMERALD),
            new Def("diamond", "Mine 8 diamond ore", Kind.MINE, ore("diamond"), 8, 90_000, Items.DIAMOND),
            new Def("debris", "Mine 4 ancient debris", Kind.MINE, any("minecraft:ancient_debris"), 4, 120_000, Items.ANCIENT_DEBRIS),
            new Def("quartz", "Mine 48 nether quartz ore", Kind.MINE, any("minecraft:nether_quartz_ore"), 48, 40_000, Items.QUARTZ),
            new Def("netherrack", "Mine 300 netherrack", Kind.MINE, any("minecraft:netherrack"), 300, 30_000, Items.NETHERRACK),
            new Def("blackstone", "Mine 200 blackstone or basalt", Kind.MINE, any("minecraft:blackstone", "minecraft:basalt", "minecraft:smooth_basalt"), 200, 35_000, Items.BLACKSTONE),
            new Def("endstone", "Mine 250 end stone", Kind.MINE, any("minecraft:end_stone"), 250, 45_000, Items.END_STONE),
            new Def("obsidian", "Mine 32 obsidian", Kind.MINE, any("minecraft:obsidian", "minecraft:crying_obsidian"), 32, 50_000, Items.OBSIDIAN),
            new Def("dirt", "Dig 300 dirt, sand or gravel", Kind.MINE, any("minecraft:dirt", "minecraft:grass_block", "minecraft:sand", "minecraft:red_sand",
                    "minecraft:gravel", "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:mud"), 300, 25_000, Items.IRON_SHOVEL),
            new Def("clay", "Dig 64 clay", Kind.MINE, any("minecraft:clay"), 64, 30_000, Items.CLAY_BALL),
            new Def("ice", "Mine 64 ice blocks", Kind.MINE, any("minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice"), 64, 35_000, Items.PACKED_ICE),
            new Def("amethyst", "Mine 32 amethyst", Kind.MINE, any("minecraft:amethyst_cluster", "minecraft:amethyst_block", "minecraft:budding_amethyst"), 32, 40_000, Items.AMETHYST_SHARD),
            // ---- woodcutting
            new Def("logs", "Chop 128 logs", Kind.MINE, s -> s.endsWith("_log") || s.endsWith("_stem"), 128, 30_000, Items.IRON_AXE),
            new Def("logs2", "Chop 400 logs", Kind.MINE, s -> s.endsWith("_log") || s.endsWith("_stem"), 400, 75_000, Items.DIAMOND_AXE),
            new Def("oak", "Chop 96 oak or birch logs", Kind.MINE, any("minecraft:oak_log", "minecraft:birch_log"), 96, 25_000, Items.OAK_LOG),
            new Def("spruce", "Chop 96 spruce or dark oak logs", Kind.MINE, any("minecraft:spruce_log", "minecraft:dark_oak_log"), 96, 30_000, Items.SPRUCE_LOG),
            new Def("jungle", "Chop 64 jungle, acacia or mangrove logs", Kind.MINE, any("minecraft:jungle_log", "minecraft:acacia_log", "minecraft:mangrove_log"), 64, 35_000, Items.JUNGLE_LOG),
            new Def("cherry", "Chop 48 cherry logs", Kind.MINE, any("minecraft:cherry_log"), 48, 35_000, Items.CHERRY_LOG),
            new Def("stems", "Chop 64 nether stems", Kind.MINE, any("minecraft:crimson_stem", "minecraft:warped_stem"), 64, 40_000, Items.CRIMSON_STEM),
            new Def("leaves", "Clear 200 leaves", Kind.MINE, s -> s.endsWith("_leaves"), 200, 20_000, Items.SHEARS),
            // ---- farming
            new Def("crops", "Harvest 200 crops", Kind.HARVEST, s -> true, 200, 35_000, Items.WHEAT),
            new Def("crops2", "Harvest 600 crops", Kind.HARVEST, s -> true, 600, 80_000, Items.HAY_BLOCK),
            new Def("wheat", "Harvest 128 wheat", Kind.HARVEST, any("minecraft:wheat"), 128, 30_000, Items.WHEAT_SEEDS),
            new Def("carrot", "Harvest 128 carrots", Kind.HARVEST, any("minecraft:carrots"), 128, 30_000, Items.CARROT),
            new Def("potato", "Harvest 128 potatoes", Kind.HARVEST, any("minecraft:potatoes"), 128, 30_000, Items.POTATO),
            new Def("beet", "Harvest 96 beetroots", Kind.HARVEST, any("minecraft:beetroots"), 96, 30_000, Items.BEETROOT),
            new Def("melon", "Harvest 64 melons or pumpkins", Kind.HARVEST, any("minecraft:melon", "minecraft:pumpkin"), 64, 35_000, Items.MELON_SLICE),
            new Def("cane", "Harvest 128 sugar cane", Kind.HARVEST, any("minecraft:sugar_cane"), 128, 30_000, Items.SUGAR_CANE),
            new Def("cactus", "Harvest 64 cactus", Kind.HARVEST, any("minecraft:cactus"), 64, 30_000, Items.CACTUS),
            new Def("wart", "Harvest 96 nether wart", Kind.HARVEST, any("minecraft:nether_wart"), 96, 40_000, Items.NETHER_WART),
            new Def("cocoa", "Harvest 48 cocoa pods", Kind.HARVEST, any("minecraft:cocoa"), 48, 35_000, Items.COCOA_BEANS),
            // ---- combat
            new Def("zombie", "Kill 30 zombies", Kind.KILL, any("minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager"), 30, 30_000, Items.ROTTEN_FLESH),
            new Def("skeleton", "Kill 30 skeletons", Kind.KILL, any("minecraft:skeleton", "minecraft:stray", "minecraft:bogged"), 30, 35_000, Items.BONE),
            new Def("creeper", "Kill 15 creepers", Kind.KILL, any("minecraft:creeper"), 15, 40_000, Items.GUNPOWDER),
            new Def("spider", "Kill 25 spiders", Kind.KILL, any("minecraft:spider", "minecraft:cave_spider"), 25, 30_000, Items.STRING),
            new Def("enderman", "Kill 10 endermen", Kind.KILL, any("minecraft:enderman"), 10, 60_000, Items.ENDER_PEARL),
            new Def("witch", "Kill 5 witches", Kind.KILL, any("minecraft:witch"), 5, 50_000, Items.GLASS_BOTTLE),
            new Def("slime", "Kill 25 slimes or magma cubes", Kind.KILL, any("minecraft:slime", "minecraft:magma_cube"), 25, 35_000, Items.SLIME_BALL),
            new Def("blaze", "Kill 15 blazes", Kind.KILL, any("minecraft:blaze"), 15, 60_000, Items.BLAZE_ROD),
            new Def("piglin", "Kill 20 piglins", Kind.KILL, any("minecraft:piglin", "minecraft:zombified_piglin", "minecraft:piglin_brute"), 20, 45_000, Items.GOLD_NUGGET),
            new Def("wither_sk", "Kill 8 wither skeletons", Kind.KILL, any("minecraft:wither_skeleton"), 8, 80_000, Items.WITHER_SKELETON_SKULL),
            new Def("ghast", "Kill 3 ghasts", Kind.KILL, any("minecraft:ghast"), 3, 60_000, Items.GHAST_TEAR),
            new Def("phantom", "Kill 6 phantoms", Kind.KILL, any("minecraft:phantom"), 6, 50_000, Items.PHANTOM_MEMBRANE),
            new Def("guardian", "Kill 10 guardians", Kind.KILL, any("minecraft:guardian", "minecraft:elder_guardian"), 10, 60_000, Items.PRISMARINE_SHARD),
            new Def("shulker", "Kill 5 shulkers", Kind.KILL, any("minecraft:shulker"), 5, 90_000, Items.SHULKER_SHELL),
            new Def("pillager", "Kill 10 pillagers or vindicators", Kind.KILL, any("minecraft:pillager", "minecraft:vindicator", "minecraft:evoker", "minecraft:ravager"), 10, 60_000, Items.CROSSBOW),
            new Def("animals", "Kill 40 farm animals", Kind.KILL, any("minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken",
                    "minecraft:rabbit", "minecraft:mooshroom", "minecraft:goat"), 40, 25_000, Items.BEEF),
            new Def("mobs", "Kill 100 mobs", Kind.KILL, s -> !s.equals("minecraft:player"), 100, 60_000, Items.IRON_SWORD),
            new Def("mobs2", "Kill 300 mobs", Kind.KILL, s -> !s.equals("minecraft:player"), 300, 120_000, Items.DIAMOND_SWORD),
            new Def("players", "Kill 3 players", Kind.KILL, any("minecraft:player"), 3, 80_000, Items.PLAYER_HEAD),
            // ---- fishing
            new Def("fish", "Catch 20 fish", Kind.FISH, s -> true, 20, 35_000, Items.FISHING_ROD),
            new Def("fish2", "Catch 60 fish", Kind.FISH, s -> true, 60, 80_000, Items.TROPICAL_FISH),
            // ---- money
            new Def("sell", "Earn $150,000 with /sell", Kind.SELL, s -> true, 150_000, 50_000, Items.GOLD_INGOT),
            new Def("sell2", "Earn $500,000 with /sell", Kind.SELL, s -> true, 500_000, 120_000, Items.GOLD_BLOCK));

    static final long ALL_DONE_BONUS = 150_000;
    private static final ZoneId ZONE = ZoneId.of("Europe/Bratislava");

    static final class PData {
        String day;
        List<String> ids = new ArrayList<>();
        int[] progress = new int[COUNT];
        boolean[] done = new boolean[COUNT];
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
        if (d == null || !day.equals(d.day) || d.ids.size() != COUNT || d.progress == null || d.progress.length != COUNT
                || d.ids.stream().anyMatch(i -> def(i) == null)) {
            d = new PData();
            d.day = day;
            // truly random every day (the old day-based seed gave nearly the same quests each day)
            List<Def> pool = new ArrayList<>(POOL);
            Random r = new Random();
            java.util.Set<Kind> kinds = new java.util.HashSet<>();
            while (d.ids.size() < COUNT && !pool.isEmpty()) {
                Def q = pool.remove(r.nextInt(pool.size()));
                // first pick one of each kind so the day is varied
                if (kinds.size() < 4 && kinds.contains(q.kind) && pool.stream().anyMatch(x -> !kinds.contains(x.kind))) { pool.add(q); continue; }
                kinds.add(q.kind);
                d.ids.add(q.id);
            }
            DATA.put(u, d);
            dirty = true;
        }
        return d;
    }

    static void progress(ServerPlayerEntity p, Kind kind, String target, int amount) {
        PData d = data(p);
        for (int i = 0; i < COUNT; i++) {
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
        boolean all = true;
        for (boolean b : d.done) all &= b;
        if (all) {
            Money.add(p, ALL_DONE_BONUS);
            p.sendMessage(Text.literal("★ All daily quests done! Bonus ").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)
                    .append(Text.literal("+" + Money.fmt(ALL_DONE_BONUS)).formatted(Formatting.GOLD, Formatting.BOLD)), false);
        }
    }

    /** Called from /sell with the money the player just earned. */
    /** Called when a player reels in a catch. */
    public static void onFish(ServerPlayerEntity p) { progress(p, Kind.FISH, "", 1); }

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
        int[] slots = SLOTS;
        for (int i = 0; i < COUNT; i++) {
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
            if (!done) lore.add(Text.literal("Right-click: new quest (" + Money.fmt(REROLL_COST) + ")").styled(st -> st.withColor(Formatting.DARK_GRAY).withItalic(false)));
            s.set(DataComponentTypes.LORE, new LoreComponent(lore));
            inv.setStack(slots[i], s);
        }
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        Duration left = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(ZONE));
        ItemStack clock = new ItemStack(Items.CLOCK);
        clock.set(DataComponentTypes.ITEM_NAME, Text.literal("New quests in " + left.toHours() + "h " + left.toMinutesPart() + "m").formatted(Formatting.AQUA));
        clock.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("Finish all " + COUNT + " for a " + Money.fmt(ALL_DONE_BONUS) + " bonus!").styled(st -> st.withColor(Formatting.LIGHT_PURPLE).withItalic(false)))));
        inv.setStack(22, clock);
        if (p.currentScreenHandler instanceof Handler h) { for (int i = 0; i < 27; i++) h.inv.setStack(i, inv.getStack(i)); h.syncState(); return; }
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, pi, pl) -> new Handler(sync, pi, inv),
                Text.literal("Daily Quests").formatted(Formatting.DARK_GREEN, Formatting.BOLD)));
    }

    static final int[] SLOTS = {11, 12, 13, 14, 15};

    /** Swap an unfinished quest for a different random one. */
    static void reroll(ServerPlayerEntity p, int i) {
        PData d = data(p);
        if (i < 0 || i >= COUNT || d.done[i]) return;
        if (!Money.has(p, REROLL_COST)) {
            p.sendMessage(Text.literal("You need " + Money.fmt(REROLL_COST) + " to get a new quest.").formatted(Formatting.RED), false);
            return;
        }
        List<Def> pool = new ArrayList<>();
        for (Def q : POOL) if (!d.ids.contains(q.id)) pool.add(q);
        if (pool.isEmpty()) return;
        Money.add(p, -REROLL_COST);
        d.ids.set(i, pool.get(new Random().nextInt(pool.size())).id);
        d.progress[i] = 0;
        dirty = true;
        p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 0.8f, 1.0f);
        open(p);
    }

    static class Handler extends GenericContainerScreenHandler {
        final SimpleInventory inv;

        Handler(int sync, PlayerInventory pi, SimpleInventory inv) {
            super(ScreenHandlerType.GENERIC_9X3, sync, pi, inv, 3);
            this.inv = inv;
        }

        @Override
        public void onSlotClick(int slot, int button, SlotActionType type, PlayerEntity player) {
            if (button == 1 && type == SlotActionType.PICKUP && player instanceof ServerPlayerEntity sp)
                for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == slot) { reroll(sp, i); return; }
            this.syncState();
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }
    }
}
