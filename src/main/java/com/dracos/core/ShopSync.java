package com.dracos.core;

import com.burger.eco.shop.ShopConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes a "Legendary" category into Burger Economy's /shop with the exact legendary items. */
public final class ShopSync {
    private ShopSync() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static void sync(MinecraftServer server) {
        try {
            var ops = server.getRegistryManager().getOps(JsonOps.INSTANCE);
            JsonArray items = new JsonArray();
            for (String id : LegendaryItems.IDS) {
                ItemStack stack = LegendaryItems.create(id, server.getRegistryManager());
                JsonElement enc = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
                if (enc == null || !enc.isJsonObject()) {
                    DracosCore.LOGGER.warn("Could not encode legendary item {}", id);
                    continue;
                }
                JsonObject e = new JsonObject();
                e.addProperty("id", Registries.ITEM.getId(stack.getItem()).toString());
                e.addProperty("can_buy", true);
                e.addProperty("can_sell", false);
                e.addProperty("quantity", 1);
                e.addProperty("buy_price", LegendaryItems.PRICES.get(id));
                e.addProperty("sell_price", 0);
                JsonElement comps = enc.getAsJsonObject().get("components");
                if (comps != null) e.add("components", comps);
                items.add(e);
            }
            JsonObject cat = new JsonObject();
            cat.addProperty("category_name", "Legendary");
            cat.addProperty("category_item", "minecraft:netherite_sword");
            cat.add("items", items);

            Path f = FabricLoader.getInstance().getConfigDir().resolve("economy").resolve("shop").resolve("legendary.json");
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f)) { GSON.toJson(cat, w); }

            ShopConfig.load();
            DracosCore.LOGGER.info("Legendary shop category synced ({} items)", items.size());
        } catch (Throwable t) {
            DracosCore.LOGGER.error("Failed to sync Legendary shop category", t);
        }
    }
}
