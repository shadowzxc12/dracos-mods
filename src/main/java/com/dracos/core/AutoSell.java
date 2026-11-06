package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** /autosell - pick which items get sold automatically from your inventory every few seconds. */
public final class AutoSell {
    private AutoSell() {}

    static final class PData {
        boolean on = true;
        Set<String> items = new LinkedHashSet<>();
    }

    private static final Gson GSON = new Gson();
    private static Map<String, PData> DATA = new HashMap<>();
    private static int ticks;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("autosell.json");
    }

    public static void load() {
        try {
            if (Files.exists(file())) {
                Map<String, PData> m = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, PData>>() {}.getType());
                if (m != null) DATA = new HashMap<>(m);
            }
        } catch (Exception e) {
            DracosCore.LOGGER.warn("autosell.json unreadable", e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(DATA));
        } catch (Exception e) {
            DracosCore.LOGGER.warn("autosell.json save failed", e);
        }
    }

    static PData of(ServerPlayerEntity p) {
        return DATA.computeIfAbsent(p.getUuidAsString(), k -> new PData());
    }

    /** Items players can choose: everything with a price in sell_prices.json, sorted by value. */
    static List<Item> choices() {
        List<Map.Entry<String, Double>> list = new ArrayList<>(SellPrices.get().entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<Item> out = new ArrayList<>();
        for (var e : list) {
            Identifier id = Identifier.tryParse(e.getKey());
            if (id == null || !Registries.ITEM.containsId(id)) continue;
            Item it = Registries.ITEM.get(id);
            if (it != Items.AIR) out.add(it);
        }
        return out;
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks % 60 != 0) return; // every 3 seconds
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                PData d = DATA.get(p.getUuidAsString());
                if (d == null || !d.on || d.items.isEmpty() || p.isCreative() || p.isSpectator()) continue;
                run(p, d);
            }
        });
    }

    private static void run(ServerPlayerEntity p, PData d) {
        PlayerInventory inv = p.getInventory();
        Map<String, Double> prices = SellPrices.get();
        double total = 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || !d.items.contains(Registries.ITEM.getId(s.getItem()).toString())) continue;
            Double each = SellMenu.priceOf(s, prices);
            if (each == null) continue;
            total += each * s.getCount();
            count += s.getCount();
            inv.setStack(i, ItemStack.EMPTY);
        }
        if (count == 0) return;
        long money = (long) Math.floor(total);
        Ranks.Rank r = Ranks.of(p);
        money += money * r.sellBonus / 100;
        Money.add(p, money);
        Quests.onSell(p, money);
        p.sendMessage(Text.literal("Auto-sold " + count + " items for ").formatted(Formatting.GREEN)
                .append(Text.literal(Money.fmt(money)).formatted(Formatting.GOLD, Formatting.BOLD)), true);
    }

    // ------------------------------------------------------------------ GUI
    private static final int PER_PAGE = 45;

    public static void open(ServerPlayerEntity p, int page) {
        List<Item> all = choices();
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        PData d = of(p);
        Map<String, Double> prices = SellPrices.get();
        SimpleInventory inv = new SimpleInventory(54);
        Map<Integer, Item> map = new HashMap<>();
        for (int i = 0; i < PER_PAGE && pg * PER_PAGE + i < all.size(); i++) {
            Item it = all.get(pg * PER_PAGE + i);
            String id = Registries.ITEM.getId(it).toString();
            boolean sel = d.items.contains(id);
            ItemStack s = new ItemStack(it);
            Double each = SellMenu.priceOf(s, prices);
            List<Text> lore = new ArrayList<>();
            lore.add(Text.literal("Sell: " + Money.fmt(each == null ? 0 : (long) Math.floor(each)) + " each")
                    .styled(st -> st.withColor(Formatting.GOLD).withItalic(false)));
            lore.add(Text.literal(sel ? "✔ AUTO-SELL ON" : "✘ auto-sell off").styled(st -> st.withColor(sel ? Formatting.GREEN : Formatting.RED)
                    .withBold(sel).withItalic(false)));
            lore.add(Text.literal("Click to toggle").styled(st -> st.withColor(Formatting.DARK_GRAY).withItalic(false)));
            s.set(DataComponentTypes.LORE, new LoreComponent(lore));
            if (sel) s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            inv.setStack(i, s);
            map.put(i, it);
        }
        ItemStack pane = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        pane.set(DataComponentTypes.ITEM_NAME, Text.literal(" "));
        for (int i = 45; i < 54; i++) inv.setStack(i, pane.copy());
        if (pg > 0) inv.setStack(45, named(Items.ARROW, "← Previous page", Formatting.YELLOW));
        if (pg < pages - 1) inv.setStack(53, named(Items.ARROW, "Next page →", Formatting.YELLOW));
        ItemStack master = named(d.on ? Items.LIME_DYE : Items.GRAY_DYE, d.on ? "Auto-sell: ON" : "Auto-sell: OFF",
                d.on ? Formatting.GREEN : Formatting.RED);
        master.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(d.items.size() + " item(s) selected").styled(st -> st.withColor(Formatting.GRAY).withItalic(false)),
                Text.literal("Sells every 3 seconds from your inventory").styled(st -> st.withColor(Formatting.GRAY).withItalic(false)),
                Text.literal("Click to turn " + (d.on ? "off" : "on")).styled(st -> st.withColor(Formatting.DARK_GRAY).withItalic(false)))));
        inv.setStack(49, master);
        inv.setStack(51, named(Items.BARRIER, "Clear selection", Formatting.RED));
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, pi, pl) -> new Handler(sync, pi, inv, map, pg),
                Text.literal("Auto Sell " + (pg + 1) + "/" + pages).formatted(Formatting.DARK_GREEN, Formatting.BOLD)));
    }

    private static ItemStack named(Item item, String name, Formatting f) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponentTypes.ITEM_NAME, Text.literal(name).formatted(f, Formatting.BOLD));
        return s;
    }

    static class Handler extends GenericContainerScreenHandler {
        final Map<Integer, Item> map;
        final int page;

        Handler(int sync, PlayerInventory pi, SimpleInventory inv, Map<Integer, Item> map, int page) {
            super(ScreenHandlerType.GENERIC_9X6, sync, pi, inv, 6);
            this.map = map;
            this.page = page;
        }

        @Override
        public void onSlotClick(int slot, int button, SlotActionType type, PlayerEntity player) {
            if (!(player instanceof ServerPlayerEntity sp) || type != SlotActionType.PICKUP) { this.syncState(); return; }
            PData d = of(sp);
            int reopen = -1;
            if (map.containsKey(slot)) {
                String id = Registries.ITEM.getId(map.get(slot)).toString();
                if (!d.items.remove(id)) d.items.add(id);
                reopen = page;
            } else if (slot == 49) { d.on = !d.on; reopen = page; }
            else if (slot == 51) { d.items.clear(); reopen = page; }
            else if (slot == 45 && page > 0) reopen = page - 1;
            else if (slot == 53) reopen = page + 1;
            if (reopen >= 0) {
                save();
                sp.getEntityWorld().playSound(null, sp.getBlockPos(), SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.5f, 1.2f);
                int pg = reopen;
                sp.getEntityWorld().getServer().execute(() -> open(sp, pg));
            } else this.syncState();
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }
    }
}
