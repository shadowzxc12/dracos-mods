package com.dracos.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /duel <player> [bet] - 1v1 in the hub arena. Nobody loses items: when a fighter would die the duel ends,
 * both are healed and sent back where they were. The winner gets both bets minus 5%.
 */
public final class Duels {
    private Duels() {}

    record Spot(RegistryKey<net.minecraft.world.World> world, double x, double y, double z, float yaw, float pitch) {
        static Spot of(ServerPlayerEntity p) {
            return new Spot(p.getEntityWorld().getRegistryKey(), p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch());
        }
    }

    record Request(UUID from, UUID to, long bet, long at) {}

    static final class Duel {
        UUID a, b;
        long bet;
        Spot backA, backB;
        int tick;           // ticks since start
        boolean fighting;   // false during countdown
        boolean over;
    }

    private static final Map<UUID, Request> REQUESTS = new HashMap<>();   // key = target
    static Duel CURRENT;
    static final int MAX_TICKS = 20 * 60 * 3;

    public static boolean inDuel(ServerPlayerEntity p) {
        return CURRENT != null && !CURRENT.over && (CURRENT.a.equals(p.getUuid()) || CURRENT.b.equals(p.getUuid()));
    }

    private static void msg(ServerPlayerEntity p, String s, Formatting f) {
        p.sendMessage(Text.literal("Duel » ").formatted(Formatting.RED, Formatting.BOLD).append(Text.literal(s).formatted(f)), false);
    }

    public static void commands(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("duel")
                .then(CommandManager.literal("accept").executes(ctx -> accept(ctx.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("deny").executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                    if (REQUESTS.remove(p.getUuid()) != null) msg(p, "Duel request denied.", Formatting.GRAY);
                    return 1;
                }))
                .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(ctx -> request(ctx.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(ctx, "player"), "0"))
                        .then(CommandManager.argument("bet", StringArgumentType.word())
                                .executes(ctx -> request(ctx.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(ctx, "player"),
                                        StringArgumentType.getString(ctx, "bet"))))));
    }

    static int request(ServerPlayerEntity p, ServerPlayerEntity t, String rawBet) {
        if (Hub.CFG.arena == null) { msg(p, "The duel arena isn't built yet.", Formatting.RED); return 0; }
        if (t == p) { msg(p, "You can't duel yourself.", Formatting.RED); return 0; }
        Long bet = rawBet.equals("0") ? Long.valueOf(0) : Bank.parse(rawBet, Money.get(p));
        if (bet == null || bet < 0) { msg(p, "Bet must be a number like 10k, 1m or 0.", Formatting.RED); return 0; }
        if (bet > 0 && !Money.has(p, bet)) { msg(p, "You don't have " + Money.fmt(bet) + ".", Formatting.RED); return 0; }
        REQUESTS.put(t.getUuid(), new Request(p.getUuid(), t.getUuid(), bet, System.currentTimeMillis()));
        msg(p, "Duel request sent to " + t.getName().getString() + (bet > 0 ? " for " + Money.fmt(bet) : "") + ".", Formatting.YELLOW);
        t.sendMessage(Text.literal("Duel » ").formatted(Formatting.RED, Formatting.BOLD)
                .append(Text.literal(p.getName().getString() + " challenged you to a duel" + (bet > 0 ? " for " + Money.fmt(bet) : "") + "! ").formatted(Formatting.YELLOW))
                .append(Text.literal("[ACCEPT]").styled(s -> s.withColor(Formatting.GREEN).withBold(true).withClickEvent(new ClickEvent.RunCommand("/duel accept"))))
                .append(Text.literal(" "))
                .append(Text.literal("[DENY]").styled(s -> s.withColor(Formatting.RED).withBold(true).withClickEvent(new ClickEvent.RunCommand("/duel deny")))), false);
        return 1;
    }

    static int accept(ServerPlayerEntity t) {
        Request r = REQUESTS.remove(t.getUuid());
        MinecraftServer server = t.getEntityWorld().getServer();
        if (r == null || System.currentTimeMillis() - r.at() > 60_000) { msg(t, "No duel request (they expire after 60s).", Formatting.RED); return 0; }
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(r.from());
        if (p == null) { msg(t, "That player went offline.", Formatting.RED); return 0; }
        if (CURRENT != null && !CURRENT.over) { msg(t, "The arena is busy, try again in a moment.", Formatting.RED); return 0; }
        if (Combat.inCombat(p) || Combat.inCombat(t)) { msg(t, "Can't start a duel while in combat.", Formatting.RED); return 0; }
        long bet = r.bet();
        if (bet > 0 && (!Money.has(p, bet) || !Money.has(t, bet))) { msg(t, "Both players need " + Money.fmt(bet) + ".", Formatting.RED); return 0; }
        if (bet > 0) { Money.add(p, -bet); Money.add(t, -bet); }
        Duel d = new Duel();
        d.a = p.getUuid(); d.b = t.getUuid(); d.bet = bet;
        d.backA = Spot.of(p); d.backB = Spot.of(t);
        CURRENT = d;
        ServerWorld w = Hub.world(server);
        int[] A = Hub.CFG.arenaA, B = Hub.CFG.arenaB;
        p.stopRiding(); t.stopRiding();
        p.teleport(w, A[0] + 0.5, A[1], A[2] + 0.5, Set.of(), -90f, 0f, true);
        t.teleport(w, B[0] + 0.5, B[1], B[2] + 0.5, Set.of(), 90f, 0f, true);
        for (ServerPlayerEntity x : new ServerPlayerEntity[]{p, t}) prep(x);
        server.getPlayerManager().broadcast(Text.literal("⚔ ").formatted(Formatting.RED)
                .append(Text.literal(p.getName().getString() + " vs " + t.getName().getString()).formatted(Formatting.YELLOW, Formatting.BOLD))
                .append(Text.literal(bet > 0 ? " - duel for " + Money.fmt(bet * 2) + "!" : " - duel started!").formatted(Formatting.GRAY)), false);
        return 1;
    }

    static void prep(ServerPlayerEntity x) {
        x.setHealth(x.getMaxHealth());
        x.getHungerManager().setFoodLevel(20);
        x.getHungerManager().setSaturationLevel(10f);
        x.setFireTicks(0);
        x.clearStatusEffects();
        x.onLanding();
    }

    static void title(ServerPlayerEntity p, String big, String small, Formatting c) {
        p.networkHandler.sendPacket(new TitleFadeS2CPacket(0, 20, 5));
        p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(small).formatted(Formatting.GRAY)));
        p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(big).formatted(c, Formatting.BOLD)));
    }

    static void end(MinecraftServer server, UUID winner, String reason) {
        Duel d = CURRENT;
        if (d == null || d.over) return;
        d.over = true;
        DracosCore.LOGGER.info("[Duel] ended: {}", reason);
        ServerPlayerEntity pa = server.getPlayerManager().getPlayer(d.a), pb = server.getPlayerManager().getPlayer(d.b);
        long pot = d.bet * 2;
        if (winner == null) {
            if (d.bet > 0) { Money.addUuid(d.a, d.bet); Money.addUuid(d.b, d.bet); }
        } else if (pot > 0) {
            Money.addUuid(winner, pot - pot * 5 / 100);
        }
        String wn = winner == null ? null : (winner.equals(d.a) ? name(pa, "?") : name(pb, "?"));
        for (ServerPlayerEntity x : new ServerPlayerEntity[]{pa, pb}) {
            if (x == null) continue;
            prep(x);
            Combat.clear(x);
            boolean won = winner != null && winner.equals(x.getUuid());
            title(x, winner == null ? "DRAW" : won ? "VICTORY" : "DEFEAT", reason, winner == null ? Formatting.GRAY : won ? Formatting.GOLD : Formatting.RED);
            x.getEntityWorld().playSound(null, x.getBlockPos(), won ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 0.8f, 1f);
        }
        if (wn != null) server.getPlayerManager().broadcast(Text.literal("⚔ ").formatted(Formatting.RED)
                .append(Text.literal(wn).formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal(" won the duel" + (pot > 0 ? " and " + Money.fmt(pot - pot * 5 / 100) : "") + "! ").formatted(Formatting.YELLOW))
                .append(Text.literal("(" + reason + ")").formatted(Formatting.GRAY)), false);
        d.tick = -60; // send them back 3 seconds later (handled in tick)
    }

    /** Nobody loses just because of their position: anyone outside the arena (pearl, lag, anticheat setback,
     *  vehicle...) is put back on their spawn point instead. During the countdown both stay on their spots. */
    static void keepInArena(MinecraftServer server, Duel d, ServerPlayerEntity pa, ServerPlayerEntity pb) {
        ServerWorld w = Hub.world(server);
        var box = Hub.arenaBox();
        int[][] spots = {Hub.CFG.arenaA, Hub.CFG.arenaB};
        ServerPlayerEntity[] ps = {pa, pb};
        for (int i = 0; i < 2; i++) {
            ServerPlayerEntity x = ps[i];
            if (x == null || spots[i] == null) continue;
            int[] s = spots[i];
            boolean outside = x.getEntityWorld() != w || !box.contains(x.getX(), x.getY(), x.getZ());
            boolean moved = !d.fighting && x.squaredDistanceTo(s[0] + 0.5, s[1], s[2] + 0.5) > 2.25;
            if (outside || moved) {
                if (outside) DracosCore.LOGGER.info("[Duel] {} was outside the arena at {} {} {} in {} - moved back",
                        x.getName().getString(), (int) x.getX(), (int) x.getY(), (int) x.getZ(), x.getEntityWorld().getRegistryKey().getValue());
                if (x.hasVehicle()) x.stopRiding();
                x.teleport(w, s[0] + 0.5, s[1], s[2] + 0.5, Set.of(), i == 0 ? -90f : 90f, 0f, true);
                x.fallDistance = 0;
            }
        }
    }

    static String name(ServerPlayerEntity p, String def) { return p != null ? p.getName().getString() : def; }

    static void sendBack(MinecraftServer server, ServerPlayerEntity p, Spot s) {
        if (p == null || s == null) return;
        ServerWorld w = server.getWorld(s.world());
        if (w == null) w = server.getOverworld();
        p.teleport(w, s.x(), s.y(), s.z(), Set.of(), s.yaw(), s.pitch(), true);
    }

    public static void register() {
        // a fighter "dies": cancel the death, end the duel - no items lost, no head/bounty/kill counted
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity p) || !inDuel(p)) return true;
            Duel d = CURRENT;
            if (!d.fighting) return false;
            // only the opponent (or the environment inside the arena) can hurt you
            if (source.getAttacker() instanceof ServerPlayerEntity att && !inDuel(att)) return false;
            if (amount >= p.getHealth() + p.getAbsorptionAmount() && !p.isCreative()) {
                UUID other = d.a.equals(p.getUuid()) ? d.b : d.a;
                end(p.getEntityWorld().getServer(), other, p.getName().getString() + " was defeated");
                return false;
            }
            return true;
        });
        // safety net: if something kills them anyway (e.g. /kill), the duel still ends
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity p) || !inDuel(p)) return true;
            UUID other = CURRENT.a.equals(p.getUuid()) ? CURRENT.b : CURRENT.a;
            end(p.getEntityWorld().getServer(), other, p.getName().getString() + " was defeated");
            p.setHealth(p.getMaxHealth());
            return false;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
            REQUESTS.remove(h.player.getUuid());
            if (inDuel(h.player)) {
                Duel d = CURRENT;
                UUID other = d.a.equals(h.player.getUuid()) ? d.b : d.a;
                end(server, other, h.player.getName().getString() + " left");
                sendBack(server, h.player, d.a.equals(h.player.getUuid()) ? d.backA : d.backB);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Duel d = CURRENT;
            if (d == null) return;
            d.tick++;
            ServerPlayerEntity pa = server.getPlayerManager().getPlayer(d.a), pb = server.getPlayerManager().getPlayer(d.b);
            if (d.over) {
                if (d.tick >= 0) { sendBack(server, pa, d.backA); sendBack(server, pb, d.backB); CURRENT = null; }
                return;
            }
            if (!d.fighting) {
                if (d.tick % 5 == 0) keepInArena(server, d, pa, pb);
                if (d.tick % 20 == 0) {
                    int n = 5 - d.tick / 20;
                    for (ServerPlayerEntity x : new ServerPlayerEntity[]{pa, pb}) if (x != null) {
                        title(x, n > 0 ? String.valueOf(n) : "FIGHT!", "Duel", n > 0 ? Formatting.YELLOW : Formatting.RED);
                        x.getEntityWorld().playSound(null, x.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.PLAYERS, 1f, n > 0 ? 1f : 2f);
                    }
                    if (n <= 0) { d.fighting = true; d.tick = 0; }
                }
                return;
            }
            if (d.tick > MAX_TICKS) { end(server, null, "Time is up"); return; }
            if (d.tick % 10 == 0) keepInArena(server, d, pa, pb);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (CURRENT != null && !CURRENT.over) end(server, null, "Server restart");
            if (CURRENT != null) {
                sendBack(server, server.getPlayerManager().getPlayer(CURRENT.a), CURRENT.backA);
                sendBack(server, server.getPlayerManager().getPlayer(CURRENT.b), CURRENT.backB);
                CURRENT = null;
            }
        });
    }
}
