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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /legendary - klikni na vec a kupis ju za Balance. */
public final class LegendaryShop {
    private LegendaryShop() {}

    // 6-row window: weapons on row 2, one armor set per row below, set info in the middle
    private static final Map<Integer, String> LAYOUT = new HashMap<>();
    static {
        int[] w = {10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < w.length; i++) LAYOUT.put(w[i], LegendaryItems.WEAPONS.get(i));
        putSet(19, LegendaryItems.VOID_SET);
        putSet(28, LegendaryItems.BONE_SET);
        putSet(37, LegendaryItems.DRACO_SET);
        int[] x = {46, 47, 48, 50, 51, 52};
        for (int i = 0; i < x.length; i++) LAYOUT.put(x[i], LegendaryItems.EXTRAS.get(i));
    }

    private static void putSet(int rowStart, List<String> set) {
        int[] off = {1, 2, 4, 5};
        for (int i = 0; i < 4; i++) LAYOUT.put(rowStart + off[i], set.get(i));
    }

    public static void open(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        ItemStack pane = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        pane.set(DataComponentTypes.ITEM_NAME, Text.literal(" "));
        for (int i = 0; i < 54; i++) inv.setStack(i, pane.copy());
        inv.setStack(22, info(Items.ECHO_SHARD, "Void Set", 0x9D4DFF, LegendaryItems.voidLore()));
        inv.setStack(31, info(Items.BONE, "Bone Set", 0xF2E8C9, LegendaryItems.boneLore()));
        inv.setStack(40, info(Items.BLAZE_POWDER, "Draco Set", 0xFFB000, LegendaryItems.dracoLore()));

        var lookup = player.getEntityWorld().getRegistryManager();
        for (Map.Entry<Integer, String> en : LAYOUT.entrySet()) {
            String id = en.getValue();
            ItemStack display = LegendaryItems.create(id, lookup);
            LoreComponent old = display.get(DataComponentTypes.LORE);
            List<Text> lore = new ArrayList<>(old != null ? old.lines() : List.of());
            lore.add(Text.empty());
            lore.add(Text.literal("Price: " + Money.fmt(LegendaryItems.PRICES.get(id)))
                    .styled(s -> s.withColor(Formatting.GOLD).withBold(true).withItalic(false)));
            lore.add(Text.literal("Click to buy").styled(s -> s.withColor(Formatting.GREEN).withItalic(false)));
            display.set(DataComponentTypes.LORE, new LoreComponent(lore));
            inv.setStack(en.getKey(), display);
        }

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, p) -> new Handler(syncId, playerInv, inv),
                Text.literal("Legendary Shop").formatted(Formatting.DARK_PURPLE, Formatting.BOLD)));
    }

    private static String idAtSlot(int slot) {
        return LAYOUT.get(slot);
    }

    private static ItemStack info(net.minecraft.item.Item item, String name, int color, List<String> lines) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponentTypes.ITEM_NAME, Text.literal(name + " Bonus").styled(st -> st.withColor(color).withBold(true).withItalic(false)));
        List<Text> lore = new ArrayList<>();
        for (String l : lines) lore.add(Text.literal(l).styled(st -> st.withColor(Formatting.GRAY).withItalic(false)));
        s.set(DataComponentTypes.LORE, new LoreComponent(lore));
        s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }

    static class Handler extends GenericContainerScreenHandler {
        Handler(int syncId, PlayerInventory playerInv, SimpleInventory inv) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, playerInv, inv, 6);
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            // nic sa neda vybrat z okna - len kupit
            if (player instanceof ServerPlayerEntity sp && slotIndex >= 0 && slotIndex < 54
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
                sp.sendMessage(Text.literal("Not enough money! You need " + Money.fmt(price)
                        + ", you have " + Money.fmt(Money.get(sp)) + ".").formatted(Formatting.RED), false);
                sp.getEntityWorld().playSound(null, sp.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO,
                        SoundCategory.PLAYERS, 1f, 1f);
                return;
            }
            Money.add(sp, -price);
            sp.getInventory().offerOrDrop(LegendaryItems.create(id, sp.getEntityWorld().getRegistryManager()));
            sp.sendMessage(Text.literal("You bought a legendary item for " + Money.fmt(price) + "!")
                    .formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), false);
            sp.getEntityWorld().playSound(null, sp.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                    SoundCategory.PLAYERS, 0.7f, 1.2f);
        }
    }
}
