package com.dracos.core;

import eu.pb4.polydex.api.v1.recipe.PolydexEntry;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.Set;

/**
 * Polydex normally lists recipes per base item, so a custom katana made from a netherite sword
 * hides under "Netherite Sword". This gives every recipe result that is a custom item (custom name,
 * model, ...) its own Polydex entry, so players can search for it and see how to craft it.
 * Only loaded when Polydex is installed.
 */
public final class PolydexCompat {
    private PolydexCompat() {}

    public static void register() {
        PolydexEntry.registerProvider((server, consumer) -> {
            Set<String> seen = new HashSet<>();
            int n = 0;
            for (var entry : server.getRecipeManager().values()) {
                try {
                    for (var display : entry.value().getDisplays()) {
                        if (!(display.result() instanceof SlotDisplay.StackSlotDisplay s)) continue;
                        ItemStack stack = s.stack();
                        if (stack.isEmpty() || stack.getComponentChanges().isEmpty()) continue;
                        String key = stack.getItem().toString() + stack.getComponentChanges();
                        if (!seen.add(key)) continue;
                        Identifier rid = entry.id().getValue();
                        Identifier id = Identifier.of(DracosCore.MOD_ID, "custom/" + rid.getNamespace() + "/" + rid.getPath());
                        consumer.accept(PolydexEntry.of(id, stack.copy()));
                        n++;
                    }
                } catch (Throwable ignored) {}
            }
            DracosCore.LOGGER.info("[Polydex] added {} custom crafted items", n);
        });
    }
}
