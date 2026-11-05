package com.dracos.core;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** DonutSMP-style combat tag: 15s after PvP damage, no teleport commands, combat logging = death. */
public final class Combat {
    private Combat() {}

    public static final int COMBAT_SECONDS = 15;

    /** Commands that are blocked while in combat (first word, without slash). */
    public static final Set<String> BLOCKED = Set.of(
            "spawn", "tpa", "tpahere", "tpaccept", "tpask", "tpyes", "home", "homes", "sethome",
            "rtp", "wild", "warp", "warps", "back", "tp", "teleport", "ah", "auction", "shop",
            "sell", "legendary", "orders", "ec", "enderchest", "trash", "workbench", "craft", "backpack", "bp", "setting");

    private static final Map<UUID, Long> TAGGED = new ConcurrentHashMap<>();

    public static boolean inCombat(ServerPlayerEntity p) {
        Long until = TAGGED.get(p.getUuid());
        return until != null && until > System.currentTimeMillis();
    }

    private static void tag(ServerPlayerEntity p) {
        boolean was = inCombat(p);
        TAGGED.put(p.getUuid(), System.currentTimeMillis() + COMBAT_SECONDS * 1000L);
        if (!was) {
            p.sendMessage(Text.literal("You are now in combat! Don't log out.").formatted(Formatting.RED), false);
        }
    }

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity victim
                    && source.getAttacker() instanceof ServerPlayerEntity attacker
                    && attacker != victim
                    && !attacker.isCreative() && !victim.isCreative()) {
                tag(victim);
                tag(attacker);
            }
            return true;
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity p) TAGGED.remove(p.getUuid());
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 20 != 0) return;
            long now = System.currentTimeMillis();
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                Long until = TAGGED.get(p.getUuid());
                if (until == null) continue;
                long left = (until - now + 999) / 1000;
                if (left <= 0) {
                    TAGGED.remove(p.getUuid());
                    p.sendMessage(Text.literal("You are no longer in combat.").formatted(Formatting.GREEN), true);
                } else {
                    p.sendMessage(Text.literal("⚔ Combat: " + left + "s").formatted(Formatting.RED, Formatting.BOLD), true);
                }
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            if (inCombat(p) && p.isAlive()) {
                TAGGED.remove(p.getUuid());
                try {
                    p.kill((ServerWorld) p.getEntityWorld());
                } catch (Throwable t) {
                    DracosCore.LOGGER.warn("Could not kill combat logger", t);
                }
                server.getPlayerManager().broadcast(Text.literal(p.getName().getString() + " logged out in combat and died!")
                        .formatted(Formatting.RED), false);
            }
        });
    }

    /** Called from the command mixin. Returns true if the command must be blocked. */
    public static boolean shouldBlock(ServerPlayerEntity p, String command) {
        if (!inCombat(p) || command == null) return false;
        String root = command.trim();
        if (root.startsWith("/")) root = root.substring(1);
        int sp = root.indexOf(' ');
        if (sp >= 0) root = root.substring(0, sp);
        int colon = root.indexOf(':');
        if (colon >= 0) root = root.substring(colon + 1);
        return BLOCKED.contains(root.toLowerCase());
    }
}
