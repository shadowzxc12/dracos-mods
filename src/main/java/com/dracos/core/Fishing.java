package com.dracos.core;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Items;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.LootTables;
import net.minecraft.loot.condition.RandomChanceLootCondition;
import net.minecraft.loot.entry.ItemEntry;
import net.minecraft.loot.function.SetEnchantmentsLootFunction;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;
import net.minecraft.registry.RegistryKeys;

/** Fishing: extra 4% chance per catch for a Mending book. */
public final class Fishing {
    private Fishing() {}

    static final float MENDING_CHANCE = 0.04f;

    public static void register() {
        LootTableEvents.MODIFY.register((key, table, source, registries) -> {
            if (!key.equals(LootTables.FISHING_GAMEPLAY)) return;
            var mending = registries.getOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(Enchantments.MENDING);
            table.pool(LootPool.builder()
                    .rolls(ConstantLootNumberProvider.create(1))
                    .conditionally(RandomChanceLootCondition.builder(MENDING_CHANCE))
                    .with(ItemEntry.builder(Items.ENCHANTED_BOOK)
                            .apply(new SetEnchantmentsLootFunction.Builder().enchantment(mending, ConstantLootNumberProvider.create(1)))));
        });
    }
}
