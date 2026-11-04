package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * /sell prices that are NOT shown in /shop.
 * File: config/dracos/sell_prices.json  ->  { "*": 1, "minecraft:diamond": 400, ... }  (price per 1 item)
 * "*" = base price for every item that is not listed (scaled by rarity / stack size).
 */
public final class SellPrices {
    private SellPrices() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Map<String, Double> PRICES = Collections.emptyMap();
    private static volatile double DEFAULT = 1.0;
    private static volatile double MULTIPLIER = 1.5;

    /** Global /sell bonus (key "multiplier" in the file, default 1.5 = +50%). */
    public static double multiplier() {
        return MULTIPLIER;
    }

    /** Base price for items not in the file (key "*"). */
    public static double defaultPrice() {
        return DEFAULT;
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("sell_prices.json");
    }

    public static Map<String, Double> get() {
        return PRICES;
    }

    public static void load() {
        Path f = file();
        Map<String, Double> map = new HashMap<>();
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                JsonObject def = new JsonObject();
                def.addProperty("*", 1);
                def.addProperty("minecraft:diamond", 400);
                def.addProperty("minecraft:rotten_flesh", 5);
                try (Writer w = Files.newBufferedWriter(f)) { GSON.toJson(def, w); }
            }
            try (Reader r = Files.newBufferedReader(f)) {
                JsonElement el = JsonParser.parseReader(r);
                if (el != null && el.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                        try {
                            double v = e.getValue().getAsDouble();
                            if (v > 0) map.put(e.getKey(), v);
                        } catch (Exception ignored) {}
                    }
                }
            }
            DracosCore.LOGGER.info("Loaded {} /sell price(s)", map.size());
        } catch (Exception ex) {
            DracosCore.LOGGER.error("Failed to load sell_prices.json", ex);
        }
        Double mul = map.remove("multiplier");
        MULTIPLIER = mul != null ? mul : 1.5;
        Double d = map.remove("*");
        DEFAULT = d != null ? d : 1.0;
        PRICES = Collections.unmodifiableMap(map);
    }
}
