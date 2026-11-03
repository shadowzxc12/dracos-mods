package com.dracos.core;

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
            player.sendMessage(Text.literal("This item can't be sold. Try /ah sell <price>.").formatted(Formatting.RED), false);
            return;
        }
        long total = (long) Math.floor(each * stack.getCount());
        int count = stack.getCount();
        player.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
        pay(player, total, count);
    }

    static Double priceOf(ItemStack stack, Map<String, Double> prices) {
        if (stack.isEmpty() || LegendaryItems.idOf(stack) != null) return null;
        // poskodene veci a veci s vlastnym menom nekupujeme (aby nesli predat enchanty za cenu zakladnej veci)
        if (stack.isDamaged() || stack.hasEnchantments()) return null;
        return prices.get(Registries.ITEM.getId(stack.getItem()).toString());
    }

    static void pay(ServerPlayerEntity player, long total, int count) {
        if (total <= 0) {
            player.sendMessage(Text.literal("None of these items can be sold.").formatted(Formatting.RED), false);
            return;
        }
        Money.add(player, total);
        player.sendMessage(Text.literal("Sold " + count + " item(s) for ").formatted(Formatting.GREEN)
                .append(Text.literal(Money.fmt(total)).formatted(Formatting.GOLD, Formatting.BOLD)), false);
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
