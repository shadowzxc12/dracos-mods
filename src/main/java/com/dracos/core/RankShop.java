package com.dracos.core;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /rankshop - rare items, one page per rank. Paid with in-game money.
 * Redrawn in place (cursor stays), expensive buys ask for a second click, and prices are never below what /sell pays.
 */
public final class RankShop {
    private RankShop() {}

    record Offer(Item item, int count, long price) {}

    record Tier(Ranks.Rank rank, Item icon, Item pane, String[] perks, List<Offer> offers) {}

    private static Offer o(Item item, int count, long price) { return new Offer(item, count, price); }

    static final List<Tier> TIERS = List.of(
            new Tier(Ranks.Rank.VIP, Items.EMERALD, Items.LIME_STAINED_GLASS_PANE,
                    new String[]{"10 homes", "+5% on /sell", "/kit vip", "/hat"}, List.of(
                    o(Items.GOLDEN_APPLE, 16, 20_000), o(Items.EXPERIENCE_BOTTLE, 64, 15_000), o(Items.SHULKER_BOX, 1, 35_000),
                    o(Items.ENDER_PEARL, 16, 10_000), o(Items.DIAMOND_BLOCK, 4, 50_000), o(Items.FIREWORK_ROCKET, 64, 15_000),
                    o(Items.EMERALD, 64, 60_000), o(Items.GOLDEN_CARROT, 64, 12_000), o(Items.NAME_TAG, 2, 8_000),
                    o(Items.SADDLE, 1, 6_000), o(Items.BLAZE_ROD, 16, 15_000), o(Items.SLIME_BALL, 32, 10_000),
                    o(Items.GUNPOWDER, 64, 15_000), o(Items.OBSIDIAN, 64, 15_000))),
            new Tier(Ranks.Rank.MVP, Items.DIAMOND, Items.LIGHT_BLUE_STAINED_GLASS_PANE,
                    new String[]{"15 homes", "+10% on /sell", "/craft anywhere", "/kit mvp"}, List.of(
                    o(Items.ENCHANTED_GOLDEN_APPLE, 1, 90_000), o(Items.TOTEM_OF_UNDYING, 1, 60_000), o(Items.NETHERITE_INGOT, 1, 90_000),
                    o(Items.WIND_CHARGE, 32, 35_000), o(Items.SHULKER_BOX, 4, 130_000), o(Items.EXPERIENCE_BOTTLE, 256, 55_000),
                    o(Items.SHULKER_SHELL, 8, 60_000), o(Items.ENDER_CHEST, 2, 25_000), o(Items.WITHER_SKELETON_SKULL, 1, 80_000),
                    o(Items.HEART_OF_THE_SEA, 1, 90_000), o(Items.ECHO_SHARD, 8, 70_000), o(Items.TRIDENT, 1, 150_000),
                    o(Items.BREEZE_ROD, 16, 40_000), o(Items.GHAST_TEAR, 8, 30_000))),
            new Tier(Ranks.Rank.LEGEND, Items.NETHER_STAR, Items.ORANGE_STAINED_GLASS_PANE,
                    new String[]{"25 homes", "+15% on /sell", "/ec anywhere", "/kit legend"}, List.of(
                    o(Items.ENCHANTED_GOLDEN_APPLE, 3, 250_000), o(Items.TOTEM_OF_UNDYING, 4, 230_000),
                    o(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1, 150_000), o(Items.NETHER_STAR, 1, 360_000),
                    o(Items.BEACON, 1, 450_000), o(Items.NETHERITE_BLOCK, 1, 720_000), o(Items.WITHER_SKELETON_SKULL, 3, 220_000),
                    o(Items.CONDUIT, 1, 300_000), o(Items.SNIFFER_EGG, 1, 200_000), o(Items.TRIDENT, 2, 280_000),
                    o(Items.SHULKER_BOX, 9, 280_000), o(Items.TOTEM_OF_UNDYING, 8, 440_000))),
            new Tier(Ranks.Rank.DRACO, Items.DRAGON_HEAD, Items.MAGENTA_STAINED_GLASS_PANE,
                    new String[]{"50 homes", "+25% on /sell", "/glow", "Dragon roar on join"}, List.of(
                    o(Items.DRAGON_HEAD, 1, 540_000), o(Items.ENCHANTED_GOLDEN_APPLE, 8, 660_000), o(Items.TOTEM_OF_UNDYING, 8, 450_000),
                    o(Items.NETHERITE_BLOCK, 4, 2_700_000), o(Items.BEACON, 2, 840_000), o(Items.NETHER_STAR, 3, 1_000_000),
                    o(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 4, 550_000), o(Items.ENCHANTED_GOLDEN_APPLE, 16, 1_250_000),
                    o(Items.SHULKER_BOX, 27, 800_000), o(Items.WITHER_SKELETON_SKULL, 6, 400_000), o(Items.DRAGON_BREATH, 16, 120_000))));

    static final long CONFIRM_FROM = 250_000;

    /** Never cheaper than 1.3x the best possible /sell payout (+25% rank bonus), so nothing can be bought and resold for profit. */
    static long price(Offer of) {
        Double v = SellMenu.priceOf(new ItemStack(of.item()), SellPrices.get());
        long floor = v == null ? 0 : (long) Math.ceil(v * of.count() * 1.30 * 1.25);
        return Math.max(of.price(), floor);
    }

    // ------------------------------------------------------------------ window
    interface Click { void run(ServerPlayerEntity p); }

    static final class View {
        final SimpleInventory inv = new SimpleInventory(54);
        final Map<Integer, Click> clicks = new HashMap<>();
        Handler handler;
        int pendingSlot = -1;
        long pendingUntil = 0;

        void reset(Item border) {
            clicks.clear();
            ItemStack dark = blank(Items.BLACK_STAINED_GLASS_PANE), col = blank(border);
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

    private static Text line(String s, Formatting f) { return Text.literal(s).styled(st -> st.withColor(f).withItalic(false)); }

    private static ItemStack named(Item item, String name, Formatting f, List<Text> lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponentTypes.ITEM_NAME, Text.literal(name).formatted(f, Formatting.BOLD));
        if (!lore.isEmpty()) s.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return s;
    }

    private static ItemStack head(ServerPlayerEntity p) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        try { s.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(p.getGameProfile())); } catch (Throwable ignored) {}
        Ranks.Rank r = Ranks.of(p);
        s.set(DataComponentTypes.ITEM_NAME, Text.literal(p.getName().getString()).formatted(r.color, Formatting.BOLD));
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Rank: " + r.title, r.color), line("Balance: " + Money.fmt(Money.get(p)), Formatting.GOLD))));
        return s;
    }

    private static void sound(ServerPlayerEntity p, SoundEvent e, float pitch) {
        p.getEntityWorld().playSound(null, p.getBlockPos(), e, SoundCategory.PLAYERS, 0.6f, pitch);
    }

    private static View view(ServerPlayerEntity p) {
        if (p.currentScreenHandler instanceof Handler h) return h.view;
        View v = new View();
        v.reset(Items.PURPLE_STAINED_GLASS_PANE);
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, pi, pl) -> {
            Handler h = new Handler(sync, pi, v);
            v.handler = h;
            return h;
        }, Text.literal("✦ Rank Shop ✦").formatted(Formatting.DARK_PURPLE, Formatting.BOLD)));
        return v;
    }

    // ------------------------------------------------------------------ screens
    public static void open(ServerPlayerEntity p) {
        View v = view(p);
        v.reset(Items.PURPLE_STAINED_GLASS_PANE);
        v.pendingSlot = -1;
        v.inv.setStack(4, head(p));
        Ranks.Rank mine = Ranks.of(p);
        int[] slots = {20, 22, 24, 31};
        for (int i = 0; i < TIERS.size(); i++) {
            Tier t = TIERS.get(i);
            boolean ok = mine.atLeast(t.rank());
            List<Text> lore = new ArrayList<>();
            for (String perk : t.perks()) lore.add(line("• " + perk, Formatting.GRAY));
            lore.add(Text.empty());
            lore.add(line(t.offers().size() + " items", Formatting.DARK_GRAY));
            lore.add(line(ok ? "✔ Unlocked - click to open" : "✘ Locked - you need the " + t.rank().title + " rank", ok ? Formatting.GREEN : Formatting.RED));
            ItemStack s = named(ok ? t.icon() : Items.BARRIER, t.rank().title.toUpperCase() + " ITEMS", ok ? t.rank().color : Formatting.DARK_GRAY, lore);
            if (ok) s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            v.inv.setStack(slots[i], s);
            int idx = i;
            v.clicks.put(slots[i], pl -> {
                if (!Ranks.of(pl).atLeast(t.rank())) { locked(pl, t); return; }
                sound(pl, SoundEvents.UI_BUTTON_CLICK.value(), 1.2f);
                tier(pl, idx);
            });
        }
        v.inv.setStack(48, named(Items.EMERALD, "Shop", Formatting.GREEN, List.of(line("Back to /shop", Formatting.GRAY))));
        v.clicks.put(48, Shop::open);
        v.inv.setStack(49, named(Items.BARRIER, "Close", Formatting.RED, List.of()));
        v.clicks.put(49, ServerPlayerEntity::closeHandledScreen);
        v.inv.setStack(50, named(Items.GOLD_INGOT, "Get a rank", Formatting.GOLD, List.of(line("Click for the store link", Formatting.GRAY))));
        v.clicks.put(50, pl -> {
            pl.closeHandledScreen();
            pl.sendMessage(Text.literal("» Ranks: ").formatted(Formatting.GOLD).append(Text.literal("[OPEN STORE]").styled(s -> s
                    .withColor(Formatting.GREEN).withBold(true).withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(Store.url()))))), false);
        });
        v.done();
    }

    private static void locked(ServerPlayerEntity p, Tier t) {
        sound(p, SoundEvents.ENTITY_VILLAGER_NO, 1f);
        p.closeHandledScreen();
        p.sendMessage(Text.literal("✘ The " + t.rank().title + " items need the " + t.rank().title + " rank. ").formatted(Formatting.RED)
                .append(Text.literal("[GET IT]").styled(s -> s.withColor(Formatting.GREEN).withBold(true)
                        .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(Store.url()))))), false);
    }

    static void tier(ServerPlayerEntity p, int idx) {
        Tier t = TIERS.get(idx);
        if (!Ranks.of(p).atLeast(t.rank())) { locked(p, t); return; }
        View v = view(p);
        v.reset(t.pane());
        v.inv.setStack(4, head(p));
        boolean ok = Ranks.of(p).atLeast(t.rank());
        int[] slots = new int[21];
        for (int r = 1, k = 0; r <= 3; r++) for (int c = 1; c <= 7; c++) slots[k++] = r * 9 + c;
        for (int i = 0; i < t.offers().size() && i < slots.length; i++) {
            Offer of = t.offers().get(i);
            int slot = slots[i];
            long price = price(of);
            boolean confirm = v.pendingSlot == slot && System.currentTimeMillis() < v.pendingUntil;
            ItemStack s = new ItemStack(of.item(), Math.min(of.count(), of.item().getMaxCount()));
            List<Text> lore = new ArrayList<>();
            lore.add(line("Amount: " + of.count(), Formatting.GRAY));
            lore.add(line("Price: " + Money.fmt(price), Formatting.GOLD));
            lore.add(Text.empty());
            if (!ok) lore.add(line("✘ Needs " + t.rank().title + " rank", Formatting.RED));
            else if (confirm) lore.add(line("» Click again to confirm!", Formatting.YELLOW));
            else lore.add(line(Money.has(p, price) ? "» Click to buy" : "Not enough money", Money.has(p, price) ? Formatting.GREEN : Formatting.RED));
            s.set(DataComponentTypes.LORE, new LoreComponent(lore));
            if (confirm) s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            v.inv.setStack(slot, s);
            v.clicks.put(slot, pl -> {
                if (!Ranks.of(pl).atLeast(t.rank())) {
                    pl.sendMessage(Text.literal("You need the " + t.rank().title + " rank for this! /store").formatted(Formatting.RED), true);
                    sound(pl, SoundEvents.ENTITY_VILLAGER_NO, 1f);
                    return;
                }
                if (price >= CONFIRM_FROM && !(v.pendingSlot == slot && System.currentTimeMillis() < v.pendingUntil)) {
                    v.pendingSlot = slot;
                    v.pendingUntil = System.currentTimeMillis() + 5000;
                    sound(pl, SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.6f);
                    tier(pl, idx);
                    return;
                }
                v.pendingSlot = -1;
                buy(pl, of, price);
                tier(pl, idx);
            });
        }
        // perks strip
        List<Text> perks = new ArrayList<>();
        for (String perk : t.perks()) perks.add(line("• " + perk, Formatting.GRAY));
        v.inv.setStack(40, named(t.icon(), t.rank().title + " perks", t.rank().color, perks));
        if (idx > 0) {
            int prev = idx - 1;
            v.inv.setStack(45, named(Items.ARROW, "← " + TIERS.get(prev).rank().title, Formatting.YELLOW, List.of()));
            v.clicks.put(45, pl -> tier(pl, prev));
        }
        if (idx < TIERS.size() - 1 && Ranks.of(p).atLeast(TIERS.get(idx + 1).rank())) {
            int next = idx + 1;
            v.inv.setStack(53, named(Items.ARROW, TIERS.get(next).rank().title + " →", Formatting.YELLOW, List.of()));
            v.clicks.put(53, pl -> tier(pl, next));
        }
        v.inv.setStack(49, named(Items.OAK_DOOR, "Back", Formatting.RED, List.of()));
        v.clicks.put(49, RankShop::open);
        v.done();
    }

    private static void buy(ServerPlayerEntity p, Offer of, long price) {
        if (!Money.has(p, price)) {
            p.sendMessage(Text.literal("Not enough money! You need " + Money.fmt(price) + ", you have " + Money.fmt(Money.get(p)) + ".")
                    .formatted(Formatting.RED), true);
            sound(p, SoundEvents.ENTITY_VILLAGER_NO, 1f);
            return;
        }
        Money.add(p, -price);
        int left = of.count();
        while (left > 0) {
            int n = Math.min(left, of.item().getMaxCount());
            p.getInventory().offerOrDrop(new ItemStack(of.item(), n));
            left -= n;
        }
        p.sendMessage(Text.literal("Bought " + of.count() + "x ").formatted(Formatting.GREEN)
                .append(new ItemStack(of.item()).getName().copy().formatted(Formatting.WHITE))
                .append(Text.literal(" for " + Money.fmt(price) + "!").formatted(Formatting.GREEN)), true);
        sound(p, SoundEvents.ENTITY_PLAYER_LEVELUP, 1.6f);
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
                    && view.clicks.containsKey(slot)) view.clicks.get(slot).run(sp);
            this.syncState();
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }
    }
}
