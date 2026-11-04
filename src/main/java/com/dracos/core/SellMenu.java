package com.dracos.core;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

import java.util.Map;

/** /sell - hrac hodi veci do okna, po zavreti dostane peniaze. */
public final class SellMenu {
    private SellMenu() {}

    public static void open(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, p) -> new Handler(syncId, playerInv, inv),
                Text.literal("Sell - put items here and close").formatted(Formatting.DARK_GREEN)));
    }

    public static void sellHand(ServerPlayerEntity player) {
        ItemStack stack = player.getMainHandStack();
        if (stack.isEmpty()) {
            player.sendMessage(Text.literal("You are not holding anything.").formatted(Formatting.RED), false);
            return;
        }
        Map<String, Double> prices = Money.sellPrices();
        Double each = priceOf(stack, prices);
        if (each == null) {
            player.sendMessage(Text.literal("This item can't be sold (empty it first, or use /ah sell <price>).").formatted(Formatting.RED), false);
            return;
        }
        long total = (long) Math.floor(each * stack.getCount());
        int count = stack.getCount();
        player.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
        pay(player, total, count);
    }

    /**
     * Every item can be sold. Items listed in sell_prices.json / /shop use that price,
     * everything else gets a fallback price based on rarity and stack size.
     * Returns null only for things that must not be sold (legendary items, filled shulkers/bundles).
     */
    static Double priceOf(ItemStack stack, Map<String, Double> prices) {
        if (stack.isEmpty() || LegendaryItems.idOf(stack) != null) return null;
        var container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null && container.stream().anyMatch(s -> !s.isEmpty())) return null;
        var bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null && !bundle.isEmpty()) return null;

        Double listed = prices.get(Registries.ITEM.getId(stack.getItem()).toString());
        double price = listed != null ? listed : fallback(stack);

        // damaged tools/armor are worth less
        if (stack.isDamageable() && stack.getMaxDamage() > 0) {
            double left = 1.0 - (double) stack.getDamage() / stack.getMaxDamage();
            price *= Math.max(0.1, left);
        }
        return price * SellPrices.multiplier();
    }

    private static double fallback(ItemStack stack) {
        double p = SellPrices.defaultPrice();
        int max = stack.getMaxCount();
        if (max == 1) p *= 20;          // tools, armor, single items
        else if (max <= 16) p *= 3;     // eggs, pearls, signs...
        p *= switch (stack.getRarity()) {
            case UNCOMMON -> 5;
            case RARE -> 25;
            case EPIC -> 100;
            default -> 1;
        };
        return p;
    }

    static void pay(ServerPlayerEntity player, long total, int count) {
        if (total <= 0) {
            player.sendMessage(Text.literal("None of these items can be sold.").formatted(Formatting.RED), false);
            return;
        }
        Ranks.Rank rank = Ranks.of(player);
        long bonus = total * rank.sellBonus / 100;
        total += bonus;
        Money.add(player, total);
        var msg = Text.literal("Sold " + count + " item(s) for ").formatted(Formatting.GREEN)
                .append(Text.literal(Money.fmt(total)).formatted(Formatting.GOLD, Formatting.BOLD));
        if (bonus > 0) msg.append(Text.literal(" (+" + rank.sellBonus + "% " + rank.title + " bonus)").formatted(rank.color));
        player.sendMessage(msg, false);
        player.getEntityWorld().playSound(null, player.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.PLAYERS, 0.8f, 1.2f);
    }

    static class Handler extends GenericContainerScreenHandler {
        private final SimpleInventory sellInv;
        private boolean done = false;

        Handler(int syncId, PlayerInventory playerInv, SimpleInventory inv) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, playerInv, inv, 6);
            this.sellInv = inv;
        }

        @Override
        public void onClosed(PlayerEntity player) {
            super.onClosed(player);
            if (done || !(player instanceof ServerPlayerEntity sp)) return;
            done = true;

            Map<String, Double> prices = Money.sellPrices();
            double total = 0;
            int sold = 0;
            for (int i = 0; i < sellInv.size(); i++) {
                ItemStack stack = sellInv.removeStack(i);
                if (stack.isEmpty()) continue;
                Double each = priceOf(stack, prices);
                if (each == null) {
                    sp.getInventory().offerOrDrop(stack); // nepredajne vratime
                } else {
                    total += each * stack.getCount();
                    sold += stack.getCount();
                }
            }
            if (sold == 0) {
                sp.sendMessage(Text.literal("Nothing was sold.").formatted(Formatting.GRAY), false);
                return;
            }
            pay(sp, (long) Math.floor(total), sold);
        }
    }
}
