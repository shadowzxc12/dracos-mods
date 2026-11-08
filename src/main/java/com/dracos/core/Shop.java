package com.dracos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
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
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * DonutSMP-style /shop.
 * Categories from Burger Economy's shop files (config/economy/shop/*.json) plus built-in ones.
 * Item screen: pick any amount (+1/+16/+64, fill inventory), buy, sell or sell all.
 * One window that is redrawn in place, so the cursor never jumps.
 * Buy prices are always above what /sell pays (with the best rank bonus), so nobody can buy and resell for profit.
 */
public final class Shop {
    private Shop() {}

    record Entry(String id, JsonObject components, boolean canBuy, int quantity, long buy) {
        double configuredEach() { return (double) buy / Math.max(1, quantity); }
        boolean custom() { return components != null && components.size() > 0; }
    }

    record Category(String name, Item icon, Formatting color, String desc, List<Entry> items) {}

    // ------------------------------------------------------------------ built-in categories
    private static Entry e(String id, long price) { return new Entry("minecraft:" + id, null, true, 1, price); }

    private static List<Entry> list(Object... idPrice) {
        List<Entry> l = new ArrayList<>();
        for (int i = 0; i + 1 < idPrice.length; i += 2) l.add(e((String) idPrice[i], ((Number) idPrice[i + 1]).longValue()));
        return l;
    }

    private static final String[] COLORS = {"white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow",
            "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink"};

    private static List<Category> builtIn() {
        List<Category> out = new ArrayList<>();
        out.add(new Category("Farming", Items.WHEAT, Formatting.GREEN, "Seeds, crops and saplings", list(
                "wheat_seeds", 10, "beetroot_seeds", 10, "melon_seeds", 20, "pumpkin_seeds", 20, "carrot", 15, "potato", 15,
                "sugar_cane", 15, "cactus", 15, "bamboo", 10, "nether_wart", 40, "cocoa_beans", 30, "sweet_berries", 15,
                "glow_berries", 30, "bone_meal", 15, "hay_block", 120, "oak_sapling", 30, "birch_sapling", 30, "spruce_sapling", 30,
                "jungle_sapling", 40, "acacia_sapling", 30, "dark_oak_sapling", 40, "cherry_sapling", 60, "mangrove_propagule", 60,
                "pale_oak_sapling", 60, "moss_block", 40, "kelp", 10, "lily_pad", 20)));
        out.add(new Category("Food", Items.COOKED_BEEF, Formatting.GOLD, "Never go hungry", list(
                "bread", 30, "apple", 30, "baked_potato", 30, "cooked_beef", 50, "cooked_porkchop", 50, "cooked_mutton", 45,
                "cooked_chicken", 40, "cooked_salmon", 40, "cooked_cod", 35, "golden_carrot", 150, "pumpkin_pie", 60, "cookie", 15,
                "cake", 300, "honey_bottle", 80, "mushroom_stew", 60, "rabbit_stew", 120, "dried_kelp", 10, "suspicious_stew", 100)));
        out.add(new Category("Mob Drops", Items.BONE, Formatting.DARK_RED, "Drops without the grind", list(
                "rotten_flesh", 10, "bone", 20, "string", 15, "spider_eye", 25, "gunpowder", 60, "slime_ball", 80,
                "ender_pearl", 150, "blaze_rod", 200, "breeze_rod", 300, "ghast_tear", 400, "phantom_membrane", 120, "leather", 30,
                "feather", 15, "ink_sac", 25, "glow_ink_sac", 60, "magma_cream", 100, "rabbit_hide", 30, "rabbit_foot", 200,
                "prismarine_shard", 40, "prismarine_crystals", 60, "armadillo_scute", 200, "turtle_scute", 300, "shulker_shell", 1500)));
        out.add(new Category("Ores & Gems", Items.DIAMOND, Formatting.AQUA, "Ingots, gems and minerals", list(
                "coal", 30, "charcoal", 30, "copper_ingot", 30, "iron_ingot", 100, "gold_ingot", 150, "redstone", 30,
                "lapis_lazuli", 40, "quartz", 40, "amethyst_shard", 50, "diamond", 2600, "iron_nugget", 12,
                "gold_nugget", 18, "flint", 20, "clay_ball", 10, "glowstone_dust", 30, "echo_shard", 3000)));
        out.add(new Category("Nether", Items.NETHERRACK, Formatting.RED, "Blocks from the Nether", list(
                "netherrack", 5, "soul_sand", 20, "soul_soil", 20, "basalt", 10, "smooth_basalt", 15, "blackstone", 10,
                "nether_bricks", 20, "red_nether_bricks", 30, "crimson_stem", 30, "warped_stem", 30, "crimson_nylium", 20,
                "warped_nylium", 20, "nether_wart_block", 30, "warped_wart_block", 30, "glowstone", 60, "magma_block", 40,
                "shroomlight", 80, "crying_obsidian", 150, "obsidian", 100, "quartz_block", 160, "gilded_blackstone", 400)));
        out.add(new Category("The End", Items.END_STONE, Formatting.LIGHT_PURPLE, "End stone, purpur and more", list(
                "end_stone", 15, "end_stone_bricks", 20, "purpur_block", 30, "purpur_pillar", 30, "chorus_fruit", 30,
                "chorus_flower", 200, "end_rod", 100, "ender_eye", 400, "ender_chest", 2000, "dragon_breath", 1000)));
        List<Entry> colors = new ArrayList<>();
        for (String c : COLORS) colors.add(e(c + "_concrete", 30));
        for (String c : COLORS) colors.add(e(c + "_wool", 30));
        for (String c : COLORS) colors.add(e(c + "_stained_glass", 30));
        for (String c : COLORS) colors.add(e(c + "_dye", 20));
        for (String c : COLORS) colors.add(e(c + "_concrete_powder", 25));
        for (String c : COLORS) colors.add(e(c + "_glazed_terracotta", 40));
        out.add(new Category("Colors", Items.MAGENTA_WOOL, Formatting.LIGHT_PURPLE, "Concrete, wool, glass and dyes", colors));
        out.add(new Category("Tools & Armor", Items.DIAMOND_PICKAXE, Formatting.YELLOW, "Gear up fast", list(
                "iron_pickaxe", 1500, "iron_axe", 1500, "iron_shovel", 600, "iron_sword", 1200, "iron_hoe", 800,
                "iron_helmet", 2000, "iron_chestplate", 3200, "iron_leggings", 2800, "iron_boots", 1600,
                "diamond_pickaxe", 10000, "diamond_axe", 10000, "diamond_shovel", 4000, "diamond_sword", 8000, "diamond_hoe", 6000,
                "diamond_helmet", 12000, "diamond_chestplate", 20000, "diamond_leggings", 18000, "diamond_boots", 10000,
                "bow", 2000, "crossbow", 3000, "arrow", 10, "spectral_arrow", 40, "shield", 1500, "fishing_rod", 800,
                "shears", 800, "flint_and_steel", 500, "brush", 600, "spyglass", 900, "compass", 600, "clock", 900)));
        out.add(new Category("Utility", Items.CHEST, Formatting.WHITE, "Everything a base needs", list(
                "torch", 10, "lantern", 50, "soul_lantern", 60, "ladder", 15, "scaffolding", 20, "chest", 100, "barrel", 100,
                "crafting_table", 50, "furnace", 100, "smoker", 400, "blast_furnace", 800, "red_bed", 300, "bucket", 500,
                "water_bucket", 600, "lava_bucket", 800, "anvil", 5000, "enchanting_table", 15000, "bookshelf", 500,
                "grindstone", 600, "smithing_table", 600, "cartography_table", 400, "loom", 300, "stonecutter", 400,
                "brewing_stand", 1500, "cauldron", 800, "composter", 200, "hopper", 1000, "minecart", 500, "rail", 30,
                "powered_rail", 200, "tnt", 300, "experience_bottle", 300, "name_tag", 3000, "saddle", 3000, "lead", 300,
                "firework_rocket", 100, "item_frame", 60, "glow_item_frame", 120, "armor_stand", 200, "flower_pot", 30,
                "bundle", 400, "jukebox", 2000, "note_block", 300)));
        return out;
    }

    private static List<Category> cats() {
        List<Category> out = new ArrayList<>();
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("economy").resolve("shop");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
                    try {
                        JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                        List<Entry> items = new ArrayList<>();
                        JsonArray arr = o.has("items") ? o.getAsJsonArray("items") : new JsonArray();
                        for (JsonElement el : arr) {
                            JsonObject x = el.getAsJsonObject();
                            if (x.has("can_buy") && !x.get("can_buy").getAsBoolean()) continue;
                            items.add(new Entry(x.get("id").getAsString(), x.has("components") ? x.getAsJsonObject("components") : null,
                                    true, x.has("quantity") ? x.get("quantity").getAsInt() : 1,
                                    x.has("buy_price") ? x.get("buy_price").getAsLong() : 0));
                        }
                        String name = o.has("category_name") ? o.get("category_name").getAsString() : f.getFileName().toString();
                        Identifier iid = Identifier.tryParse(o.has("category_item") ? o.get("category_item").getAsString() : "minecraft:chest");
                        Item icon = iid != null && Registries.ITEM.containsId(iid) ? Registries.ITEM.get(iid) : Items.CHEST;
                        boolean legend = name.toLowerCase().contains("legend");
                        if (!items.isEmpty()) out.add(new Category(name, icon, legend ? Formatting.DARK_PURPLE : Formatting.GOLD,
                                legend ? "The rarest weapons on the server" : "Blocks and items", items));
                    } catch (Exception ex) {
                        DracosCore.LOGGER.warn("Shop file {} unreadable", f.getFileName());
                    }
                }
            } catch (Exception ex) {
                DracosCore.LOGGER.warn("Shop folder unreadable", ex);
            }
        }
        out.addAll(builtIn());
        return out;
    }

    // ------------------------------------------------------------------ prices
    static ItemStack stackOf(ServerPlayerEntity p, Entry e, int count) {
        if (e.custom()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", e.id);
            j.addProperty("count", 1);
            j.add("components", e.components);
            var ops = p.getEntityWorld().getRegistryManager().getOps(JsonOps.INSTANCE);
            ItemStack s = ItemStack.CODEC.parse(ops, j).result().orElse(ItemStack.EMPTY);
            if (!s.isEmpty()) s.setCount(Math.max(1, Math.min(count, s.getMaxCount())));
            return s;
        }
        Identifier id = Identifier.tryParse(e.id);
        if (id == null || !Registries.ITEM.containsId(id)) return ItemStack.EMPTY;
        ItemStack s = new ItemStack(Registries.ITEM.get(id));
        s.setCount(Math.max(1, Math.min(count, s.getMaxCount())));
        return s;
    }

    /** What /sell pays for one (with this player's rank bonus); null = can't be sold. */
    static Double sellEach(ServerPlayerEntity p, Entry e) {
        if (e.custom()) return null;
        ItemStack s = stackOf(p, e, 1);
        if (s.isEmpty()) return null;
        Double v = SellMenu.priceOf(s, SellPrices.get());
        if (v == null) return null;
        return v * (100 + Ranks.of(p).sellBonus) / 100.0;
    }

    /** Buy price for one: the configured price, but never below 1.3x the best possible /sell value. */
    static double buyEach(ServerPlayerEntity p, Entry e) {
        double cfg = e.configuredEach();
        if (e.custom()) return cfg;
        ItemStack s = stackOf(p, e, 1);
        Double v = s.isEmpty() ? null : SellMenu.priceOf(s, SellPrices.get());
        double floor = v == null ? 0 : v * 1.30 * 1.25; // 1.25 = best rank bonus
        return Math.max(Math.max(cfg, floor), 1);
    }

    static long cost(ServerPlayerEntity p, Entry e, int n) { return (long) Math.ceil(buyEach(p, e) * n); }

    // ------------------------------------------------------------------ window
    interface Click { void run(ServerPlayerEntity p, int button, SlotActionType type); }

    static final class View {
        final SimpleInventory inv = new SimpleInventory(54);
        final Map<Integer, Click> clicks = new HashMap<>();
        Handler handler;
        int amount = 1;

        void reset(Formatting border) {
            clicks.clear();
            Item pane = switch (border) {
                case GREEN -> Items.LIME_STAINED_GLASS_PANE;
                case GOLD, YELLOW -> Items.ORANGE_STAINED_GLASS_PANE;
                case AQUA -> Items.LIGHT_BLUE_STAINED_GLASS_PANE;
                case RED, DARK_RED -> Items.RED_STAINED_GLASS_PANE;
                case LIGHT_PURPLE, DARK_PURPLE -> Items.PURPLE_STAINED_GLASS_PANE;
                case WHITE -> Items.WHITE_STAINED_GLASS_PANE;
                default -> Items.GRAY_STAINED_GLASS_PANE;
            };
            ItemStack dark = blank(Items.BLACK_STAINED_GLASS_PANE), col = blank(pane);
            for (int i = 0; i < 54; i++) {
                int r = i / 9, c = i % 9;
                inv.setStack(i, (r == 0 || r == 5 || c == 0 || c == 8) ? col.copy() : dark.copy());
            }
        }

        void done() { if (handler != null) handler.syncState(); }
    }

    private static ItemStack blank(Item i) {
        ItemStack s = new ItemStack(i);
        s.set(DataComponentTypes.ITEM_NAME, Text.literal(" "));
        return s;
    }

    private static View view(ServerPlayerEntity p) {
        if (p.currentScreenHandler instanceof Handler h) return h.view;
        View v = new View();
        v.reset(Formatting.GRAY);
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, pi, pl) -> {
            Handler h = new Handler(sync, pi, v);
            v.handler = h;
            return h;
        }, Text.literal("✦ Dracos Shop ✦").formatted(Formatting.DARK_GREEN, Formatting.BOLD)));
        return v;
    }

    private static Text line(String s, Formatting f) { return Text.literal(s).styled(st -> st.withColor(f).withItalic(false)); }

    private static ItemStack named(Item item, String name, Formatting f, Text... lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponentTypes.ITEM_NAME, Text.literal(name).formatted(f, Formatting.BOLD));
        if (lore.length > 0) s.set(DataComponentTypes.LORE, new LoreComponent(List.of(lore)));
        return s;
    }

    private static ItemStack head(ServerPlayerEntity p) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        try { s.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(p.getGameProfile())); } catch (Throwable ignored) {}
        s.set(DataComponentTypes.ITEM_NAME, Text.literal(p.getName().getString()).formatted(Formatting.YELLOW, Formatting.BOLD));
        Ranks.Rank r = Ranks.of(p);
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Balance: " + Money.fmt(Money.get(p)), Formatting.GOLD),
                line("Rank: " + r.title + (r.sellBonus > 0 ? "  (+" + r.sellBonus + "% sell)" : ""), Formatting.GRAY))));
        return s;
    }

    private static void sound(ServerPlayerEntity p, SoundEvent e, float pitch) {
        p.getEntityWorld().playSound(null, p.getBlockPos(), e, SoundCategory.PLAYERS, 0.6f, pitch);
    }

    // ------------------------------------------------------------------ main menu
    public static void open(ServerPlayerEntity p) {
        List<Category> cats = cats();
        View v = view(p);
        v.reset(Formatting.GREEN);
        v.inv.setStack(4, head(p));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        for (int i = 0; i < cats.size() && i < slots.length; i++) {
            Category c = cats.get(i);
            int idx = i;
            ItemStack s = named(c.icon, c.name, c.color,
                    line(c.desc, Formatting.GRAY), line(c.items.size() + " items", Formatting.DARK_GRAY), Text.empty(),
                    line("» Click to browse", Formatting.GREEN));
            v.inv.setStack(slots[i], s);
            v.clicks.put(slots[i], (pl, b, t) -> { sound(pl, SoundEvents.UI_BUTTON_CLICK.value(), 1.2f); category(pl, idx, 0); });
        }
        v.inv.setStack(46, named(Items.SPAWNER, "Spawners", Formatting.RED, line("Buy mob spawners", Formatting.GRAY)));
        v.clicks.put(46, (pl, b, t) -> { pl.closeHandledScreen(); Spawners.open(pl); });
        v.inv.setStack(47, named(Items.NETHER_STAR, "Rank Shop", Formatting.LIGHT_PURPLE, line("Rare items for ranks", Formatting.GRAY)));
        v.clicks.put(47, (pl, b, t) -> { pl.closeHandledScreen(); RankShop.open(pl); });
        v.inv.setStack(49, named(Items.BARRIER, "Close", Formatting.RED));
        v.clicks.put(49, (pl, b, t) -> pl.closeHandledScreen());
        v.inv.setStack(51, named(Items.HOPPER, "Auto Sell", Formatting.AQUA, line("Pick items that sell by themselves", Formatting.GRAY)));
        v.clicks.put(51, (pl, b, t) -> { pl.closeHandledScreen(); AutoSell.open(pl, 0); });
        v.inv.setStack(52, named(Items.GOLD_INGOT, "Sell", Formatting.GOLD, line("Open /sell", Formatting.GRAY)));
        v.clicks.put(52, (pl, b, t) -> { pl.closeHandledScreen(); pl.getEntityWorld().getServer().getCommandManager()
                .parseAndExecute(pl.getCommandSource(), "sell"); });
        v.done();
    }

    // ------------------------------------------------------------------ category
    static void category(ServerPlayerEntity p, int catIdx, int page) {
        List<Category> cats = cats();
        if (catIdx >= cats.size()) { open(p); return; }
        Category c = cats.get(catIdx);
        int[] slots = new int[28];
        for (int r = 1, k = 0; r <= 4; r++) for (int col = 1; col <= 7; col++) slots[k++] = r * 9 + col;
        int pages = Math.max(1, (c.items.size() + slots.length - 1) / slots.length);
        int pg = Math.max(0, Math.min(page, pages - 1));
        View v = view(p);
        v.reset(c.color);
        v.inv.setStack(4, named(c.icon, c.name + (pages > 1 ? "  " + (pg + 1) + "/" + pages : ""), c.color, line(c.desc, Formatting.GRAY)));
        for (int i = 0; i < slots.length && pg * slots.length + i < c.items.size(); i++) {
            Entry e = c.items.get(pg * slots.length + i);
            ItemStack s = stackOf(p, e, 1);
            if (s.isEmpty()) continue;
            List<Text> lore = new ArrayList<>(s.getOrDefault(DataComponentTypes.LORE, LoreComponent.DEFAULT).lines());
            if (!lore.isEmpty()) lore.add(Text.empty());
            long one = cost(p, e, 1);
            lore.add(line("Buy: " + Money.fmt(one) + " each", Formatting.GREEN));
            if (s.getMaxCount() >= 64) lore.add(line("      " + Money.fmt(cost(p, e, 64)) + " for 64", Formatting.DARK_GREEN));
            Double sv = sellEach(p, e);
            if (sv != null) lore.add(line("Sell: " + Money.fmt((long) Math.floor(sv)) + " each", Formatting.GOLD));
            lore.add(Text.empty());
            lore.add(line("» Click to choose amount", Formatting.YELLOW));
            if (s.getMaxCount() > 1) lore.add(line("» Right-click: quick buy " + Math.min(64, s.getMaxCount()), Formatting.DARK_GRAY));
            s.set(DataComponentTypes.LORE, new LoreComponent(lore));
            v.inv.setStack(slots[i], s);
            int quick = Math.min(64, s.getMaxCount());
            v.clicks.put(slots[i], (pl, b, t) -> {
                if (b == 1 && quick > 1) { buy(pl, e, quick); refreshHead(pl, v); }
                else { v.amount = 1; amount(pl, catIdx, pg, e); }
            });
        }
        if (pg > 0) {
            v.inv.setStack(45, named(Items.ARROW, "← Previous page", Formatting.YELLOW));
            v.clicks.put(45, (pl, b, t) -> category(pl, catIdx, pg - 1));
        }
        if (pg < pages - 1) {
            v.inv.setStack(53, named(Items.ARROW, "Next page →", Formatting.YELLOW));
            v.clicks.put(53, (pl, b, t) -> category(pl, catIdx, pg + 1));
        }
        v.inv.setStack(48, named(Items.OAK_DOOR, "Back to categories", Formatting.RED));
        v.clicks.put(48, (pl, b, t) -> open(pl));
        v.inv.setStack(49, head(p));
        v.done();
    }

    private static void refreshHead(ServerPlayerEntity p, View v) {
        v.inv.setStack(49, head(p));
        v.done();
    }

    // ------------------------------------------------------------------ amount screen
    static int space(ServerPlayerEntity p, ItemStack like) {
        PlayerInventory inv = p.getInventory();
        int free = 0, max = like.getMaxCount();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) free += max;
            else if (ItemStack.areItemsAndComponentsEqual(s, like)) free += Math.max(0, max - s.getCount());
        }
        return free;
    }

    static int owned(ServerPlayerEntity p, Entry e) {
        if (e.custom()) return 0;
        Item item = Registries.ITEM.get(Identifier.of(e.id));
        int have = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isOf(item) && s.getComponentChanges().isEmpty()) have += s.getCount();
        }
        return have;
    }

    static void amount(ServerPlayerEntity p, int catIdx, int page, Entry e) {
        View v = view(p);
        ItemStack base = stackOf(p, e, 1);
        if (base.isEmpty()) { category(p, catIdx, page); return; }
        int max = base.getMaxCount() == 1 ? 1 : 36 * base.getMaxCount();
        v.amount = Math.max(1, Math.min(v.amount, max));
        int n = v.amount;
        v.reset(Formatting.GOLD);
        v.inv.setStack(4, head(p));

        ItemStack show = base.copy();
        show.setCount(Math.min(n, base.getMaxCount()));
        List<Text> lore = new ArrayList<>(show.getOrDefault(DataComponentTypes.LORE, LoreComponent.DEFAULT).lines());
        lore.add(line("Amount: " + n, Formatting.WHITE));
        lore.add(line("Buy total: " + Money.fmt(cost(p, e, n)), Formatting.GREEN));
        Double sv = sellEach(p, e);
        if (sv != null) lore.add(line("Sell total: " + Money.fmt((long) Math.floor(sv * n)), Formatting.GOLD));
        show.set(DataComponentTypes.LORE, new LoreComponent(lore));
        v.inv.setStack(13, show);

        if (max > 1) {
            int[][] steps = {{19, -64}, {20, -16}, {21, -1}, {23, 1}, {24, 16}, {25, 64}};
            for (int[] st : steps) {
                int d = st[1];
                ItemStack b = named(d < 0 ? Items.RED_STAINED_GLASS_PANE : Items.LIME_STAINED_GLASS_PANE, (d > 0 ? "+" : "") + d,
                        d < 0 ? Formatting.RED : Formatting.GREEN, line("Amount: " + n, Formatting.GRAY));
                b.setCount(Math.abs(d));
                v.inv.setStack(st[0], b);
                v.clicks.put(st[0], (pl, bt, t) -> { v.amount = Math.max(1, Math.min(max, v.amount + d)); sound(pl, SoundEvents.UI_BUTTON_CLICK.value(), d > 0 ? 1.4f : 0.9f); amount(pl, catIdx, page, e); });
            }
            int[][] sets = {{29, 1}, {30, 32}, {31, 64}, {32, 9 * 64}};
            for (int[] st : sets) {
                int to = Math.min(max, st[1]);
                ItemStack b = named(Items.PAPER, "Set " + to, Formatting.WHITE, line(Money.fmt(cost(p, e, to)), Formatting.GRAY));
                b.setCount(Math.min(64, Math.max(1, to / (to > 64 ? 64 : 1))));
                v.inv.setStack(st[0], b);
                v.clicks.put(st[0], (pl, bt, t) -> { v.amount = to; amount(pl, catIdx, page, e); });
            }
            long each = Math.max(1, (long) Math.ceil(buyEach(p, e)));
            int afford = (int) Math.min(max, Money.get(p) / each);
            int fill = Math.max(1, Math.min(afford, space(p, base)));
            v.inv.setStack(33, named(Items.CHEST, "Fill inventory (" + fill + ")", Formatting.AQUA,
                    line("As many as fit and you can pay", Formatting.GRAY)));
            v.clicks.put(33, (pl, bt, t) -> { v.amount = fill; amount(pl, catIdx, page, e); });
        }

        long total = cost(p, e, n);
        boolean can = Money.has(p, total);
        v.inv.setStack(38, named(can ? Items.LIME_CONCRETE : Items.GRAY_CONCRETE, "BUY " + n, can ? Formatting.GREEN : Formatting.DARK_GRAY,
                line("Cost: " + Money.fmt(total), can ? Formatting.GOLD : Formatting.RED),
                line(can ? "» Click to buy" : "Not enough money", can ? Formatting.YELLOW : Formatting.RED)));
        v.clicks.put(38, (pl, bt, t) -> { buy(pl, e, v.amount); amount(pl, catIdx, page, e); });

        if (sv != null) {
            int have = owned(p, e);
            int sellN = Math.min(n, have);
            v.inv.setStack(40, named(Items.GOLD_BLOCK, "SELL ALL (" + have + ")", Formatting.GOLD,
                    line("You get " + Money.fmt((long) Math.floor(sv * have)), Formatting.YELLOW)));
            v.clicks.put(40, (pl, bt, t) -> { sell(pl, e, owned(pl, e)); amount(pl, catIdx, page, e); });
            v.inv.setStack(42, named(sellN > 0 ? Items.RED_CONCRETE : Items.GRAY_CONCRETE, "SELL " + n, sellN > 0 ? Formatting.RED : Formatting.DARK_GRAY,
                    line("You get " + Money.fmt((long) Math.floor(sv * n)), Formatting.GOLD),
                    line("You have " + have, Formatting.GRAY)));
            v.clicks.put(42, (pl, bt, t) -> { sell(pl, e, v.amount); amount(pl, catIdx, page, e); });
        }
        v.inv.setStack(49, named(Items.OAK_DOOR, "Back", Formatting.RED));
        v.clicks.put(49, (pl, bt, t) -> category(pl, catIdx, page));
        v.done();
    }

    // ------------------------------------------------------------------ actions
    private static void buy(ServerPlayerEntity p, Entry e, int n) {
        long cost = cost(p, e, n);
        if (!Money.has(p, cost)) {
            p.sendMessage(Text.literal("Not enough money! You need " + Money.fmt(cost) + ".").formatted(Formatting.RED), true);
            sound(p, SoundEvents.ENTITY_VILLAGER_NO, 1f);
            return;
        }
        ItemStack s = stackOf(p, e, n);
        if (s.isEmpty()) return;
        Money.add(p, -cost);
        int left = n;
        while (left > 0) {
            ItemStack give = s.copy();
            int k = Math.min(left, give.getMaxCount());
            give.setCount(k);
            p.getInventory().offerOrDrop(give);
            left -= k;
        }
        MutableText msg = Text.literal("Bought " + n + "x ").formatted(Formatting.GREEN).append(s.getName().copy().formatted(Formatting.WHITE))
                .append(Text.literal(" for " + Money.fmt(cost)).formatted(Formatting.GREEN));
        p.sendMessage(msg, true);
        sound(p, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.3f);
    }

    private static void sell(ServerPlayerEntity p, Entry e, int n) {
        Double each = sellEach(p, e);
        if (each == null || n <= 0) return;
        Item item = Registries.ITEM.get(Identifier.of(e.id));
        int have = owned(p, e);
        if (have <= 0) {
            p.sendMessage(Text.literal("You don't have any.").formatted(Formatting.RED), true);
            return;
        }
        n = Math.min(n, have);
        int left = n;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36 && left > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isOf(item) || !s.getComponentChanges().isEmpty()) continue;
            int k = Math.min(left, s.getCount());
            s.decrement(k);
            left -= k;
        }
        long gain = (long) Math.floor(each * n);
        Money.add(p, gain);
        Quests.onSell(p, gain);
        p.sendMessage(Text.literal("Sold " + n + "x for " + Money.fmt(gain)).formatted(Formatting.GOLD), true);
        sound(p, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f);
    }

    static class Handler extends GenericContainerScreenHandler {
        final View view;

        Handler(int sync, PlayerInventory pi, View view) {
            super(ScreenHandlerType.GENERIC_9X6, sync, pi, view.inv, 6);
            this.view = view;
        }

        @Override
        public void onSlotClick(int slot, int button, SlotActionType type, PlayerEntity player) {
            if (player instanceof ServerPlayerEntity sp && (type == SlotActionType.PICKUP || type == SlotActionType.QUICK_MOVE)
                    && view.clicks.containsKey(slot)) {
                view.clicks.get(slot).run(sp, button, type);
            }
            this.syncState();
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }
    }
}
