package com.dracos.core;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
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
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /rankshop - better items, every row unlocked by a rank. Paid with in-game Balance. */
public final class RankShop {
    private RankShop() {}

    record Offer(Ranks.Rank rank, Item item, int count, long price) {}

    private static final Map<Integer, Offer> OFFERS = new HashMap<>();

    private static void row(int row, Ranks.Rank rank, Offer... offers) {
        for (int i = 0; i < offers.length; i++) OFFERS.put(row * 9 + 2 + i, offers[i]);
    }

    private static Offer o(Ranks.Rank r, Item item, int count, long price) { return new Offer(r, item, count, price); }

    static {
        Ranks.Rank V = Ranks.Rank.VIP, M = Ranks.Rank.MVP, L = Ranks.Rank.LEGEND, D = Ranks.Rank.DRACO;
        row(1, V, o(V, Items.GOLDEN_APPLE, 16, 20_000), o(V, Items.EXPERIENCE_BOTTLE, 64, 15_000),
                o(V, Items.SHULKER_BOX, 1, 25_000), o(V, Items.ENDER_PEARL, 16, 10_000),
                o(V, Items.DIAMOND_BLOCK, 4, 40_000), o(V, Items.FIREWORK_ROCKET, 64, 8_000));
        row(2, M, o(M, Items.ENCHANTED_GOLDEN_APPLE, 2, 60_000), o(M, Items.TOTEM_OF_UNDYING, 1, 50_000),
                o(M, Items.NETHERITE_INGOT, 2, 80_000), o(M, Items.ELYTRA, 1, 250_000),
                o(M, Items.SHULKER_BOX, 4, 80_000), o(M, Items.EXPERIENCE_BOTTLE, 256, 50_000));
        row(3, L, o(L, Items.ENCHANTED_GOLDEN_APPLE, 8, 200_000), o(L, Items.TOTEM_OF_UNDYING, 4, 160_000),
                o(L, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2, 90_000), o(L, Items.NETHER_STAR, 1, 150_000),
                o(L, Items.BEACON, 1, 200_000), o(L, Items.NETHERITE_BLOCK, 1, 300_000));
        row(4, D, o(D, Items.HEAVY_CORE, 1, 300_000), o(D, Items.DRAGON_HEAD, 1, 150_000),
                o(D, Items.ENCHANTED_GOLDEN_APPLE, 32, 700_000), o(D, Items.TOTEM_OF_UNDYING, 16, 550_000),
                o(D, Items.NETHERITE_BLOCK, 4, 1_000_000), o(D, Items.BEACON, 2, 400_000));
    }

    public static void open(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        ItemStack pane = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        pane.set(DataComponentTypes.ITEM_NAME, Text.literal(" "));
        for (int i = 0; i < 54; i++) inv.setStack(i, pane.copy());
        Ranks.Rank mine = Ranks.of(player);
        Item[] badges = {Items.LIME_DYE, Items.LIGHT_BLUE_DYE, Items.ORANGE_DYE, Items.MAGENTA_DYE};
        Ranks.Rank[] tiers = {Ranks.Rank.VIP, Ranks.Rank.MVP, Ranks.Rank.LEGEND, Ranks.Rank.DRACO};
        for (int t = 0; t < 4; t++) {
            Ranks.Rank r = tiers[t];
            ItemStack b = new ItemStack(badges[t]);
            b.set(DataComponentTypes.ITEM_NAME, Text.literal(r.title.toUpperCase() + " items").formatted(r.color, Formatting.BOLD));
            b.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.literal(mine.atLeast(r) ? "Unlocked" : "Locked - needs " + r.title + " rank")
                    .styled(s -> s.withColor(mine.atLeast(r) ? Formatting.GREEN : Formatting.RED).withItalic(false)))));
            inv.setStack((t + 1) * 9 + 1, b);
        }
        for (Map.Entry<Integer, Offer> e : OFFERS.entrySet()) {
            Offer of = e.getValue();
            ItemStack s = new ItemStack(of.item(), Math.min(of.count(), of.item().getMaxCount()));
            List<Text> lore = new ArrayList<>();
            lore.add(Text.literal("Amount: " + of.count()).styled(st -> st.withColor(Formatting.GRAY).withItalic(false)));
            lore.add(Text.literal("Price: " + Money.fmt(of.price())).styled(st -> st.withColor(Formatting.GOLD).withBold(true).withItalic(false)));
            boolean ok = mine.atLeast(of.rank());
            lore.add(Text.literal(ok ? "Click to buy" : "Needs " + of.rank().title + " rank (/ranks)")
                    .styled(st -> st.withColor(ok ? Formatting.GREEN : Formatting.RED).withItalic(false)));
            s.set(DataComponentTypes.LORE, new LoreComponent(lore));
            inv.setStack(e.getKey(), s);
        }
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, p) -> new Handler(syncId, playerInv, inv),
                Text.literal("Rank Shop").formatted(Formatting.GOLD, Formatting.BOLD)));
    }

    static class Handler extends GenericContainerScreenHandler {
        Handler(int syncId, PlayerInventory playerInv, SimpleInventory inv) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, playerInv, inv, 6);
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (player instanceof ServerPlayerEntity sp && actionType == SlotActionType.PICKUP) {
                Offer of = OFFERS.get(slotIndex);
                if (of != null) buy(sp, of);
            }
            this.syncState();
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }

        private void buy(ServerPlayerEntity sp, Offer of) {
            if (!Ranks.of(sp).atLeast(of.rank())) {
                sp.sendMessage(Text.literal("You need the " + of.rank().title + " rank for this! See /ranks").formatted(Formatting.RED), false);
                sp.getEntityWorld().playSound(null, sp.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1f, 1f);
                return;
            }
            if (!Money.has(sp, of.price())) {
                sp.sendMessage(Text.literal("Not enough money! You need " + Money.fmt(of.price())
                        + ", you have " + Money.fmt(Money.get(sp)) + ".").formatted(Formatting.RED), false);
                sp.getEntityWorld().playSound(null, sp.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1f, 1f);
                return;
            }
            Money.add(sp, -of.price());
            int left = of.count();
            while (left > 0) {
                int n = Math.min(left, of.item().getMaxCount());
                sp.getInventory().offerOrDrop(new ItemStack(of.item(), n));
                left -= n;
            }
            sp.sendMessage(Text.literal("Bought " + of.count() + "x for " + Money.fmt(of.price()) + "!").formatted(Formatting.GREEN), false);
            sp.getEntityWorld().playSound(null, sp.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8f, 1.2f);
        }
    }
}
