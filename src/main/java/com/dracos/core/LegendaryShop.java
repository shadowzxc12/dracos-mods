package com.dracos.core;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
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
import java.util.List;

/** /legendary - klikni na vec a kupis ju za Balance. */
public final class LegendaryShop {
    private LegendaryShop() {}

    // rozlozenie v 3-riadkovom okne
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 22};

    public static void open(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(27);
        ItemStack pane = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        pane.set(DataComponentTypes.ITEM_NAME, Text.literal(" "));
        for (int i = 0; i < 27; i++) inv.setStack(i, pane.copy());

        var lookup = player.getEntityWorld().getRegistryManager();
        for (int i = 0; i < LegendaryItems.IDS.size() && i < SLOTS.length; i++) {
            String id = LegendaryItems.IDS.get(i);
            ItemStack display = LegendaryItems.create(id, lookup);
            LoreComponent old = display.get(DataComponentTypes.LORE);
            List<Text> lore = new ArrayList<>(old != null ? old.lines() : List.of());
            lore.add(Text.empty());
            lore.add(Text.literal("Cena: " + Money.fmt(LegendaryItems.PRICES.get(id)))
                    .styled(s -> s.withColor(Formatting.GOLD).withBold(true).withItalic(false)));
            lore.add(Text.literal("Klikni pre kupu").styled(s -> s.withColor(Formatting.GREEN).withItalic(false)));
            display.set(DataComponentTypes.LORE, new LoreComponent(lore));
            inv.setStack(SLOTS[i], display);
        }

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, p) -> new Handler(syncId, playerInv, inv),
                Text.literal("Legendary Shop").formatted(Formatting.DARK_PURPLE, Formatting.BOLD)));
    }

    private static String idAtSlot(int slot) {
        for (int i = 0; i < SLOTS.length && i < LegendaryItems.IDS.size(); i++) {
            if (SLOTS[i] == slot) return LegendaryItems.IDS.get(i);
        }
        return null;
    }

    static class Handler extends GenericContainerScreenHandler {
        Handler(int syncId, PlayerInventory playerInv, SimpleInventory inv) {
            super(ScreenHandlerType.GENERIC_9X3, syncId, playerInv, inv, 3);
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            // nic sa neda vybrat z okna - len kupit
            if (player instanceof ServerPlayerEntity sp && slotIndex >= 0 && slotIndex < 27
                    && actionType == SlotActionType.PICKUP) {
                String id = idAtSlot(slotIndex);
                if (id != null) buy(sp, id);
            }
            this.syncState();
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }

        private void buy(ServerPlayerEntity sp, String id) {
            long price = LegendaryItems.PRICES.get(id);
            if (!Money.has(sp, price)) {
                sp.sendMessage(Text.literal("Nemas dost penazi! Potrebujes " + Money.fmt(price)
                        + ", mas " + Money.fmt(Money.get(sp)) + ".").formatted(Formatting.RED), false);
                sp.getEntityWorld().playSound(null, sp.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO,
                        SoundCategory.PLAYERS, 1f, 1f);
                return;
            }
            Money.add(sp, -price);
            sp.getInventory().offerOrDrop(LegendaryItems.create(id, sp.getEntityWorld().getRegistryManager()));
            sp.sendMessage(Text.literal("Kupil si legendarnu vec za " + Money.fmt(price) + "!")
                    .formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), false);
            sp.getEntityWorld().playSound(null, sp.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                    SoundCategory.PLAYERS, 0.7f, 1.2f);
        }
    }
}
