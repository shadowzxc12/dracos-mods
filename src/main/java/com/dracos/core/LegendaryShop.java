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

    private static final Map<Integer, String> LAYOUT2 = new HashMap<>();
    static {
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 31};
        for (int i = 0; i < LegendaryItems.PAGE2.size() && i < slots.length; i++) LAYOUT2.put(slots[i], LegendaryItems.PAGE2.get(i));
    }

    private static Map<Integer, String> pageLayout(List<String> ids) {
        Map<Integer, String> m = new HashMap<>();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 31};
        for (int i = 0; i < ids.size() && i < slots.length; i++) m.put(slots[i], ids.get(i));
        return m;
    }

    static final int PAGES = 2 + (Legendary3.IDS.size() + 14) / 15;
    private static final Map<Integer, Map<Integer, String>> EXTRA_PAGES = new HashMap<>();

    private static void putSet(int rowStart, List<String> set) {
        int[] off = {1, 2, 4, 5};
        for (int i = 0; i < 4; i++) LAYOUT.put(rowStart + off[i], set.get(i));
    }

    public static void open(ServerPlayerEntity player) { open(player, 1); }

    private static Map<Integer, String> layout(int page) {
        if (page == 1) return LAYOUT;
        if (page == 2) return LAYOUT2;
        return EXTRA_PAGES.computeIfAbsent(page, pg -> pageLayout(Legendary3.page(pg - 3)));
    }

    public static void open(ServerPlayerEntity player, int page) {
        SimpleInventory inv = new SimpleInventory(54);
        ItemStack pane = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        pane.set(DataComponentTypes.ITEM_NAME, Text.literal(" "));
        for (int i = 0; i < 54; i++) inv.setStack(i, pane.copy());
        if (page == 1) inv.setStack(22, info(Items.ECHO_SHARD, "Void Set", 0x9D4DFF, LegendaryItems.voidLore()));
        if (page == 1) inv.setStack(31, info(Items.BONE, "Bone Set", 0xF2E8C9, LegendaryItems.boneLore()));
        if (page == 1) inv.setStack(40, info(Items.BLAZE_POWDER, "Draco Set", 0xFFB000, LegendaryItems.dracoLore()));

        var lookup = player.getEntityWorld().getRegistryManager();
        for (Map.Entry<Integer, String> en : layout(page).entrySet()) {
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

        if (page > 1) {
            ItemStack prev = new ItemStack(Items.ARROW);
            prev.set(DataComponentTypes.ITEM_NAME, Text.literal("⬅ Previous page").styled(st -> st.withColor(Formatting.YELLOW).withBold(true).withItalic(false)));
            inv.setStack(45, prev);
        }
        if (page < PAGES) {
            ItemStack next = new ItemStack(Items.ARROW);
            next.set(DataComponentTypes.ITEM_NAME, Text.literal("Next page ➜").styled(st -> st.withColor(Formatting.YELLOW).withBold(true).withItalic(false)));
            inv.setStack(53, next);
        }
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, p) -> new Handler(syncId, playerInv, inv, page),
                Text.literal("Legendary Shop " + page + "/" + PAGES).formatted(Formatting.DARK_PURPLE, Formatting.BOLD)));
    }

    private static String idAtSlot(int page, int slot) {
        return layout(page).get(slot);
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
        private final int page;

        Handler(int syncId, PlayerInventory playerInv, SimpleInventory inv, int page) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, playerInv, inv, 6);
            this.page = page;
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            // nic sa neda vybrat z okna - len kupit
            if (player instanceof ServerPlayerEntity sp && slotIndex >= 0 && slotIndex < 54
                    && actionType == SlotActionType.PICKUP) {
                if ((slotIndex == 53 && page < PAGES) || (slotIndex == 45 && page > 1)) {
                    int to = slotIndex == 53 ? page + 1 : page - 1;
                    sp.getEntityWorld().getServer().execute(() -> open(sp, to));
                    return;
                }
                String id = idAtSlot(page, slotIndex);
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
