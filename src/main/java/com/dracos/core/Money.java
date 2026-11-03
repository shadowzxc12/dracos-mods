package com.dracos.core;

import com.burger.eco.Scoreboards;
import com.burger.eco.balance.BalanceManager;
import com.burger.eco.shop.ShopConfig;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;

/** Most medzi Dracos Core a Burger Economy - ten isty Balance ako /ah a /shop. */
public final class Money {
    private Money() {}

    public static long get(ServerPlayerEntity p) {
        return BalanceManager.get(p.getUuid());
    }

    public static boolean has(ServerPlayerEntity p, long amount) {
        return BalanceManager.has(p.getUuid(), amount);
    }

    public static void add(ServerPlayerEntity p, long amount) {
        BalanceManager.add(p.getUuid(), amount);
        BalanceManager.save();
        try {
            Scoreboards.update(p);
        } catch (Throwable t) {
            DracosCore.LOGGER.warn("Scoreboard update failed", t);
        }
    }

    public static String fmt(long amount) {
        return "$" + String.format("%,d", amount);
    }

    /** Price per item: config/dracos/sell_prices.json + everything sellable in /shop (highest wins). */
    public static Map<String, Double> sellPrices() {
        Map<String, Double> prices = new HashMap<>(SellPrices.get());
        for (Object co : ShopConfig.categories()) {
            if (!(co instanceof ShopConfig.Category cat) || cat.items == null) continue;
            for (Object eo : cat.items) {
                if (!(eo instanceof ShopConfig.Entry e)) continue;
                if (!e.canSell || e.sellPrice <= 0 || e.id == null) continue;
                if (e.components != null && e.components.size() > 0) continue; // custom veci nie
                int q = Math.max(1, e.quantity);
                double each = (double) e.sellPrice / q;
                prices.merge(e.id, each, Math::max);
            }
        }
        return prices;
    }
}
