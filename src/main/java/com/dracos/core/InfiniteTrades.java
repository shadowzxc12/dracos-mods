package com.dracos.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.TradeOffer;

/** Villager trades never lock: every second all loaded villagers get their trade uses reset.
 *  Replaces the old infinite_trades datapack, which re-wrote every villager's whole NBT each second
 *  (slow, and it errored on villagers without trades). */
final class InfiniteTrades {
    private InfiniteTrades() {}

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 20 != 7) return;
            for (ServerWorld w : server.getWorlds()) {
                for (var v : w.getEntitiesByType(EntityType.VILLAGER, e -> true)) {
                    var offers = v.getOffers();
                    if (offers == null) continue;
                    for (TradeOffer o : offers) if (o.getUses() > 0) o.resetUses();
                }
            }
        });
    }
}
