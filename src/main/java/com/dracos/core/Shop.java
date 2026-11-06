package com.dracos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
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
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * DonutSMP-style /shop on top of Burger Economy's shop files (config/economy/shop/*.json):
 * categories -> items -> choose 1 / 32 / 64 (or sell).
 */
public final class Shop {
    private Shop() {}

    record Entry(String id, JsonObject components, boolean canBuy, boolean canSell, int quantity, long buy, long sell) {
        double buyEach() { return (double) buy / Math.max(1, quantity); }
        double sellEach() { return (double) sell / Math.max(1, quantity); }
    }

    record Category(String name, Item icon, List<Entry> items) {}

    private static List<Category> cats() {
        List<Category> out = new ArrayList<>();
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("economy").resolve("shop");
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
                try {
                    JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                    List<Entry> items = new ArrayList<>();
                    JsonArray arr = o.has("items") ? o.getAsJsonArray("items") : new JsonArray();
                    for (JsonElement el : arr) {
                        JsonObject e = el.getAsJsonObject();
                        items.add(new Entry(e.get("id").getAsString(), e.has("components") ? e.getAsJsonObject("components") : null,
                                !e.has("can_buy") || e.get("can_buy").getAsBoolean(), e.has("can_sell") && e.get("can_sell").getAsBoolean(),
                                e.has("quantity") ? e.get("quantity").getAsInt() : 1,
                                e.has("buy_price") ? e.get("buy_price").getAsLong() : 0, e.has("sell_price") ? e.get("sell_price").getAsLong() : 0));
                    }
                    String name = o.has("category_name") ? o.get("category_name").getAsString() : f.getFileName().toString();
                    Identifier iid = Identifier.tryParse(o.has("category_item") ? o.get("category_item").getAsString() : "minecraft:chest");
                    Item icon = iid != null && Registries.ITEM.containsId(iid) ? Registries.ITEM.get(iid) : Items.CHEST;
                    if (!items.isEmpty()) out.add(new Category(name, icon, items));
                } catch (Exception ex) {
                    DracosCore.LOGGER.warn("Shop file {} unreadable", f.getFileName());
                }
            }
        } catch (Exception ex) {
            DracosCore.LOGGER.warn("Shop folder unreadable", ex);
        }
        return out;
    }

    static ItemStack stackOf(ServerPlayerEntity p, Entry e, int count) {
        if (e.components != null && e.components.size() > 0) {
            JsonObject j = new JsonObject();
            j.addProperty("id", e.id);
            j.addProperty("count", 1);
            j.add("components", e.components);
            var ops = p.getEntityWorld().getRegistryManager().getOps(JsonOps.INSTANCE);
            ItemStack s = ItemStack.CODEC.parse(ops, j).result().orElse(ItemStack.EMPTY);
            if (!s.isEmpty()) s.setCount(Math.min(count, s.getMaxCount()));
            return s;
        }
        Identifier id = Identifier.tryParse(e.id);
        if (id == null || !Registries.ITEM.containsId(id)) return ItemStack.EMPTY;
        ItemStack s = new ItemStack(Registries.ITEM.get(id));
        s.setCount(Math.max(1, Math.min(count, s.getMaxCount())));
        return s;
    }

    private static ItemStack named(Item item, String name, Formatting f, String... lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponentTypes.ITEM_NAME, Text.literal(name).formatted(f, Formatting.BOLD));
        List<Text> l = new ArrayList<>();
        for (String x : lore) l.add(Text.literal(x).styled(st -> st.withColor(Formatting.GRAY).withItalic(false)));
        if (!l.isEmpty()) s.set(DataComponentTypes.LORE, new LoreComponent(l));
        return s;
    }

    private static SimpleInventory frame(int size) {
        SimpleInventory inv = new SimpleInventory(size);
        ItemStack pane = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        pane.set(DataComponentTypes.ITEM_NAME, Text.literal(" "));
        for (int i = 0; i < size; i++) inv.setStack(i, pane.copy());
        return inv;
    }

    interface Click { void run(ServerPlayerEntity p, int button); }

    private static void show(ServerPlayerEntity p, SimpleInventory inv, int rows, Text title, Map<Integer, Click> clicks) {
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, pi, pl) -> new Handler(sync, pi, inv, rows, clicks), title));
    }

    // ------------------------------------------------------------------ screens
    public static void open(ServerPlayerEntity p) {
        List<Category> cats = cats();
        SimpleInventory inv = frame(27);
        Map<Integer, Click> clicks = new HashMap<>();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < cats.size() && i < slots.length; i++) {
            Category c = cats.get(i);
            int idx = i;
            inv.setStack(slots[i], named(c.icon, c.name, Formatting.GOLD, c.items.size() + " items", "Click to open"));
            clicks.put(slots[i], (pl, b) -> category(pl, idx, 0));
        }
        show(p, inv, 3, Text.literal("Shop").formatted(Formatting.DARK_GREEN, Formatting.BOLD), clicks);
    }

    static void category(ServerPlayerEntity p, int catIdx, int page) {
        List<Category> cats = cats();
        if (catIdx >= cats.size()) { open(p); return; }
        Category c = cats.get(catIdx);
        int pages = Math.max(1, (c.items.size() + 44) / 45);
        int pg = Math.max(0, Math.min(page, pages - 1));
        SimpleInventory inv = frame(54);
        for (int i = 0; i < 45; i++) inv.setStack(i, ItemStack.EMPTY);
        Map<Integer, Click> clicks = new HashMap<>();
        for (int i = 0; i < 45 && pg * 45 + i < c.items.size(); i++) {
            Entry e = c.items.get(pg * 45 + i);
            ItemStack s = stackOf(p, e, 1);
            if (s.isEmpty()) continue;
            List<Text> lore = new ArrayList<>(s.getOrDefault(DataComponentTypes.LORE, LoreComponent.DEFAULT).lines());
            if (e.canBuy) lore.add(Text.literal("Buy: " + Money.fmt(Math.round(Math.ceil(e.buyEach()))) + " each").styled(st -> st.withColor(Formatting.GREEN).withItalic(false)));
            if (e.canSell) lore.add(Text.literal("Sell: " + Money.fmt((long) Math.floor(e.sellEach())) + " each").styled(st -> st.withColor(Formatting.GOLD).withItalic(false)));
            lore.add(Text.literal("Click to choose amount").styled(st -> st.withColor(Formatting.DARK_GRAY).withItalic(false)));
            s.set(DataComponentTypes.LORE, new LoreComponent(lore));
            inv.setStack(i, s);
            clicks.put(i, (pl, b) -> amount(pl, catIdx, pg, e));
        }
        inv.setStack(49, named(Items.BARRIER, "Back", Formatting.RED));
        clicks.put(49, (pl, b) -> open(pl));
        if (pg > 0) { inv.setStack(45, named(Items.ARROW, "← Previous page", Formatting.YELLOW)); clicks.put(45, (pl, b) -> category(pl, catIdx, pg - 1)); }
        if (pg < pages - 1) { inv.setStack(53, named(Items.ARROW, "Next page →", Formatting.YELLOW)); clicks.put(53, (pl, b) -> category(pl, catIdx, pg + 1)); }
        show(p, inv, 6, Text.literal(c.name + (pages > 1 ? " " + (pg + 1) + "/" + pages : "")).formatted(Formatting.DARK_GREEN, Formatting.BOLD), clicks);
    }

    static void amount(ServerPlayerEntity p, int catIdx, int page, Entry e) {
        SimpleInventory inv = frame(36);
        Map<Integer, Click> clicks = new HashMap<>();
        ItemStack shown = stackOf(p, e, 1);
        inv.setStack(13, shown.copy());
        int max = Math.max(1, shown.getMaxCount());
        int[] amounts = max >= 64 ? new int[]{1, 32, 64} : max >= 16 ? new int[]{1, 8, 16} : new int[]{1};
        int[] buySlots = amounts.length == 3 ? new int[]{19, 20, 21} : new int[]{20};
        int[] sellSlots = amounts.length == 3 ? new int[]{23, 24, 25} : new int[]{24};
        for (int i = 0; i < amounts.length; i++) {
            int n = amounts[i];
            if (e.canBuy) {
                long cost = (long) Math.ceil(e.buyEach() * n);
                ItemStack b = named(Items.LIME_STAINED_GLASS_PANE, "Buy " + n, Formatting.GREEN, "Price: " + Money.fmt(cost), "Click to buy");
                b.setCount(n);
                inv.setStack(buySlots[i], b);
                clicks.put(buySlots[i], (pl, btn) -> { buy(pl, e, n); amount(pl, catIdx, page, e); });
            }
            if (e.canSell) {
                long gain = (long) Math.floor(e.sellEach() * n);
                ItemStack s = named(Items.RED_STAINED_GLASS_PANE, "Sell " + n, Formatting.RED, "You get: " + Money.fmt(gain), "Click to sell");
                s.setCount(n);
                inv.setStack(sellSlots[i], s);
                clicks.put(sellSlots[i], (pl, btn) -> { sell(pl, e, n); amount(pl, catIdx, page, e); });
            }
        }
        inv.setStack(31, named(Items.BARRIER, "Back", Formatting.RED, "Balance: " + Money.fmt(Money.get(p))));
        clicks.put(31, (pl, b) -> category(pl, catIdx, page));
        show(p, inv, 4, Text.literal("Choose amount").formatted(Formatting.DARK_GREEN, Formatting.BOLD), clicks);
    }

    private static void buy(ServerPlayerEntity p, Entry e, int n) {
        long cost = (long) Math.ceil(e.buyEach() * n);
        if (!Money.has(p, cost)) {
            p.sendMessage(Text.literal("Not enough money! You need " + Money.fmt(cost) + ", you have " + Money.fmt(Money.get(p)) + ".").formatted(Formatting.RED), false);
            p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1f, 1f);
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
        p.sendMessage(Text.literal("Bought " + n + "x for " + Money.fmt(cost)).formatted(Formatting.GREEN), true);
        p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.6f, 1.3f);
    }

    private static void sell(ServerPlayerEntity p, Entry e, int n) {
        if (e.components != null && e.components.size() > 0) return;
        Identifier id = Identifier.tryParse(e.id);
        if (id == null) return;
        Item item = Registries.ITEM.get(id);
        PlayerInventory inv = p.getInventory();
        int have = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(item) && s.getComponentChanges().isEmpty()) have += s.getCount();
        }
        if (have < n) {
            p.sendMessage(Text.literal("You only have " + have + ".").formatted(Formatting.RED), true);
            return;
        }
        int left = n;
        for (int i = 0; i < 36 && left > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isOf(item) || !s.getComponentChanges().isEmpty()) continue;
            int k = Math.min(left, s.getCount());
            s.decrement(k);
            left -= k;
        }
        long gain = (long) Math.floor(e.sellEach() * n);
        Money.add(p, gain);
        p.sendMessage(Text.literal("Sold " + n + "x for " + Money.fmt(gain)).formatted(Formatting.GOLD), true);
        p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.6f, 0.9f);
    }

    static class Handler extends GenericContainerScreenHandler {
        final Map<Integer, Click> clicks;

        Handler(int sync, PlayerInventory pi, SimpleInventory inv, int rows, Map<Integer, Click> clicks) {
            super(rows == 3 ? ScreenHandlerType.GENERIC_9X3 : rows == 4 ? ScreenHandlerType.GENERIC_9X4 : ScreenHandlerType.GENERIC_9X6,
                    sync, pi, inv, rows);
            this.clicks = clicks;
        }

        @Override
        public void onSlotClick(int slot, int button, SlotActionType type, PlayerEntity player) {
            if (player instanceof ServerPlayerEntity sp && type == SlotActionType.PICKUP && clicks.containsKey(slot)) {
                Click c = clicks.get(slot);
                sp.getEntityWorld().getServer().execute(() -> c.run(sp, button));
                return;
            }
            this.syncState();
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }
    }
}
