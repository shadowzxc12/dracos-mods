package com.dracos.core;

import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/** Shows "Sell: $X" under every item the player can /sell. Display only (Polymer rewrites the client copy). */
final class SellTooltips {
    private SellTooltips() {}

    static void register() {
        // only touch plain vanilla items - modded/Polymer items (backpacks...) keep their own look
        PolymerItemUtils.ITEM_CHECK.register(stack -> !(stack.getItem() instanceof eu.pb4.polymer.core.api.item.PolymerItem) && value(stack) != null);
        PolymerItemUtils.ITEM_MODIFICATION_EVENT.register((original, client, context) -> {
            if (original.getItem() instanceof eu.pb4.polymer.core.api.item.PolymerItem) return client;
            Double each = value(original);
            if (each == null) return client;
            LoreComponent old = client.getOrDefault(DataComponentTypes.LORE, LoreComponent.DEFAULT);
            List<Text> lines = new ArrayList<>(old.lines());
            long one = (long) Math.floor(each);
            Text line = Text.literal("Sell: ").styled(s -> s.withColor(Formatting.GRAY).withItalic(false))
                    .append(Text.literal(Money.fmt(Math.max(one, 0))).styled(s -> s.withColor(Formatting.GOLD).withItalic(false)));
            if (original.getCount() > 1)
                line = line.copy().append(Text.literal("  (x" + original.getCount() + " = " + Money.fmt((long) Math.floor(each * original.getCount())) + ")")
                        .styled(s -> s.withColor(Formatting.DARK_GRAY).withItalic(false)));
            lines.add(line);
            client.set(DataComponentTypes.LORE, new LoreComponent(lines));
            return client;
        });
        DracosCore.LOGGER.info("Sell values shown in item tooltips");
    }

    private static Double value(ItemStack stack) {
        try {
            return SellMenu.priceOf(stack, SellPrices.get());
        } catch (Throwable t) {
            return null;
        }
    }
}
