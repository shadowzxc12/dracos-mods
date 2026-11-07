package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * /bank - safe savings with interest: 1% per day (paid by the hour), only on the first $10M,
 * so the most you earn is $100k a day. Money in the bank is not lost on death.
 */
public final class Bank {
    private Bank() {}

    static final double DAILY_RATE = 0.01;
    static final long INTEREST_CAP = 10_000_000;
    private static final long HOUR = 3_600_000L;

    static final class Acc { long bal; long last; }

    private static final Gson GSON = new Gson();
    private static Map<String, Acc> DATA = new HashMap<>();

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("bank.json"); }

    public static void load() {
        try {
            if (Files.exists(file())) {
                Map<String, Acc> m = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, Acc>>() {}.getType());
                if (m != null) DATA = new HashMap<>(m);
            }
        } catch (Exception e) { DracosCore.LOGGER.warn("bank.json unreadable", e); }
    }

    private static void save() {
        try { Files.createDirectories(file().getParent()); Files.writeString(file(), GSON.toJson(DATA)); }
        catch (Exception e) { DracosCore.LOGGER.warn("bank.json save failed", e); }
    }

    /** Adds the interest earned since last time (whole hours only). */
    static Acc acc(ServerPlayerEntity p) {
        Acc a = DATA.computeIfAbsent(p.getUuidAsString(), k -> { Acc n = new Acc(); n.last = System.currentTimeMillis(); return n; });
        long now = System.currentTimeMillis();
        long hours = (now - a.last) / HOUR;
        if (hours > 0) {
            hours = Math.min(hours, 24L * 30); // at most 30 days at once
            long earned = (long) Math.floor(Math.min(a.bal, INTEREST_CAP) * DAILY_RATE / 24.0 * hours);
            a.bal += earned;
            a.last += hours * HOUR;
            if (earned > 0) p.sendMessage(pre().append(Text.literal("You earned " + Money.fmt(earned) + " interest!").formatted(Formatting.GREEN)), false);
            save();
        }
        return a;
    }

    private static net.minecraft.text.MutableText pre() { return Text.literal("Bank » ").formatted(Formatting.GOLD, Formatting.BOLD); }

    private static void msg(ServerPlayerEntity p, String s, Formatting f) {
        p.sendMessage(Text.empty().append(pre()).append(Text.literal(s).formatted(f)), false);
    }

    static Long parse(String raw, long all) {
        String r = raw.toLowerCase().replace(",", "").replace("$", "");
        if (r.equals("all")) return all;
        double mul = 1;
        if (r.endsWith("k")) { mul = 1e3; r = r.substring(0, r.length() - 1); }
        else if (r.endsWith("m")) { mul = 1e6; r = r.substring(0, r.length() - 1); }
        else if (r.endsWith("b")) { mul = 1e9; r = r.substring(0, r.length() - 1); }
        try { long v = (long) Math.floor(Double.parseDouble(r) * mul); return v > 0 ? v : null; }
        catch (NumberFormatException e) { return null; }
    }

    public static void commands(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("bank").executes(ctx -> info(ctx.getSource().getPlayerOrThrow()))
                .then(CommandManager.literal("deposit").then(CommandManager.argument("amount", StringArgumentType.word())
                        .executes(ctx -> deposit(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "amount")))))
                .then(CommandManager.literal("withdraw").then(CommandManager.argument("amount", StringArgumentType.word())
                        .executes(ctx -> withdraw(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "amount"))))));
    }

    private static int info(ServerPlayerEntity p) {
        Acc a = acc(p);
        long perDay = (long) (Math.min(a.bal, INTEREST_CAP) * DAILY_RATE);
        msg(p, "Balance in bank: " + Money.fmt(a.bal), Formatting.YELLOW);
        msg(p, "Interest: 1% per day (max " + Money.fmt((long) (INTEREST_CAP * DAILY_RATE)) + "/day) - you get " + Money.fmt(perDay) + "/day", Formatting.GRAY);
        msg(p, "/bank deposit <amount|all>   /bank withdraw <amount|all>", Formatting.GRAY);
        return 1;
    }

    private static int deposit(ServerPlayerEntity p, String raw) {
        Acc a = acc(p);
        Long v = parse(raw, Money.get(p));
        if (v == null || v <= 0) { msg(p, "Use a number like 5000, 25k, 1.5m or all.", Formatting.RED); return 0; }
        if (!Money.has(p, v)) { msg(p, "You don't have " + Money.fmt(v) + ".", Formatting.RED); return 0; }
        Money.add(p, -v);
        a.bal += v;
        save();
        msg(p, "Deposited " + Money.fmt(v) + ". Bank: " + Money.fmt(a.bal), Formatting.GREEN);
        p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_CHAIN_PLACE, SoundCategory.PLAYERS, 0.7f, 1.4f);
        return 1;
    }

    private static int withdraw(ServerPlayerEntity p, String raw) {
        Acc a = acc(p);
        Long v = parse(raw, a.bal);
        if (v == null || v <= 0) { msg(p, "Use a number like 5000, 25k, 1.5m or all.", Formatting.RED); return 0; }
        if (v > a.bal) { msg(p, "Your bank only has " + Money.fmt(a.bal) + ".", Formatting.RED); return 0; }
        a.bal -= v;
        save();
        Money.add(p, v);
        msg(p, "Withdrew " + Money.fmt(v) + ". Bank: " + Money.fmt(a.bal), Formatting.GREEN);
        p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.7f, 1.2f);
        return 1;
    }
}
