package com.dracos.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** /bounty <player> <amount> - put money on someone's head; whoever kills them takes it all. */
public final class Bounty {
    private Bounty() {}

    public static final long MIN = 1_000;
    private static JsonObject data; // lowercase name -> amount

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("bounties.json"); }

    private static synchronized JsonObject data() {
        if (data == null) {
            try { data = Files.exists(file()) ? JsonParser.parseString(Files.readString(file())).getAsJsonObject() : new JsonObject(); }
            catch (Exception e) { data = new JsonObject(); }
        }
        return data;
    }

    private static synchronized void save() {
        try { Files.createDirectories(file().getParent()); Files.writeString(file(), data().toString()); }
        catch (Exception e) { DracosCore.LOGGER.warn("bounties.json save failed", e); }
    }

    public static long of(String name) {
        String k = name.toLowerCase();
        return data().has(k) ? data().get(k).getAsLong() : 0;
    }

    public static void place(ServerPlayerEntity from, ServerPlayerEntity target, long amount) {
        if (target == from) { from.sendMessage(Text.literal("You can't put a bounty on yourself.").formatted(Formatting.RED), false); return; }
        if (amount < MIN) { from.sendMessage(Text.literal("Minimum bounty is " + Money.fmt(MIN) + ".").formatted(Formatting.RED), false); return; }
        if (!Money.has(from, amount)) {
            from.sendMessage(Text.literal("Not enough money! You have " + Money.fmt(Money.get(from)) + ".").formatted(Formatting.RED), false);
            return;
        }
        Money.add(from, -amount);
        String k = target.getName().getString().toLowerCase();
        long total = of(k) + amount;
        data().addProperty(k, total);
        save();
        MinecraftServer server = from.getEntityWorld().getServer();
        server.getPlayerManager().broadcast(Text.literal("☠ ").formatted(Formatting.DARK_RED)
                .append(Text.literal(from.getName().getString()).formatted(Formatting.WHITE))
                .append(Text.literal(" put " + Money.fmt(amount) + " on ").formatted(Formatting.GRAY))
                .append(Text.literal(target.getName().getString()).formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal("'s head! Total bounty: ").formatted(Formatting.GRAY))
                .append(Text.literal(Money.fmt(total)).formatted(Formatting.GOLD, Formatting.BOLD)), false);
        Settings.sound(target, SoundEvents.ENTITY_WITHER_SPAWN, 0.4f, 1.6f);
    }

    public static void list(ServerPlayerEntity p) {
        List<Map.Entry<String, Long>> top = new ArrayList<>();
        data().entrySet().forEach(e -> top.add(Map.entry(e.getKey(), e.getValue().getAsLong())));
        top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        p.sendMessage(Text.literal("━━━━ Most wanted ━━━━").formatted(Formatting.DARK_RED, Formatting.BOLD), false);
        if (top.isEmpty()) p.sendMessage(Text.literal("No bounties yet. /bounty <player> <amount>").formatted(Formatting.GRAY), false);
        for (int i = 0; i < Math.min(10, top.size()); i++) {
            p.sendMessage(Text.literal((i + 1) + ". ").formatted(Formatting.GRAY)
                    .append(Text.literal(top.get(i).getKey()).formatted(Formatting.RED))
                    .append(Text.literal(" - " + Money.fmt(top.get(i).getValue())).formatted(Formatting.GOLD)), false);
        }
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayerEntity dead) || !(source.getAttacker() instanceof ServerPlayerEntity killer) || killer == dead) return;
            String k = dead.getName().getString().toLowerCase();
            long amount = of(k);
            if (amount <= 0) return;
            data().remove(k);
            save();
            Money.add(killer, amount);
            killer.getEntityWorld().getServer().getPlayerManager().broadcast(Text.literal("☠ ").formatted(Formatting.DARK_RED)
                    .append(Text.literal(killer.getName().getString()).formatted(Formatting.GREEN, Formatting.BOLD))
                    .append(Text.literal(" claimed the ").formatted(Formatting.GRAY))
                    .append(Text.literal(Money.fmt(amount)).formatted(Formatting.GOLD, Formatting.BOLD))
                    .append(Text.literal(" bounty on ").formatted(Formatting.GRAY))
                    .append(Text.literal(dead.getName().getString()).formatted(Formatting.RED)), false);
            Settings.sound(killer, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        });
    }
}
