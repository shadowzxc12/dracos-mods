package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /coinflip and /jackpot - player vs player gambling. The server keeps 5% of every win (money sink). */
public final class Gamble {
    private Gamble() {}

    static final int TAX_PERCENT = 5;
    static final long MIN_BET = 1_000, MAX_BET = 100_000_000;
    static final int JACKPOT_SECONDS = 60;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Gson GSON = new Gson();

    static final class Flip { String uuid, name; long amount; long created; }
    static final class Entry { String uuid, name; long amount; }
    static final class Data {
        List<Flip> flips = new ArrayList<>();
        List<Entry> jackpot = new ArrayList<>();
    }

    private static Data DATA = new Data();
    private static long jackpotEnds = 0;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("gamble.json");
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(DATA));
        } catch (Exception e) {
            DracosCore.LOGGER.warn("gamble.json save failed", e);
        }
    }

    /** After a restart every open bet is refunded - nobody loses money to a crash. */
    public static void load() {
        try {
            if (Files.exists(file())) {
                Data d = GSON.fromJson(Files.readString(file()), new TypeToken<Data>() {}.getType());
                if (d != null) {
                    if (d.flips != null) for (Flip f : d.flips) Money.addUuid(UUID.fromString(f.uuid), f.amount);
                    if (d.jackpot != null) for (Entry e : d.jackpot) Money.addUuid(UUID.fromString(e.uuid), e.amount);
                }
            }
        } catch (Exception e) {
            DracosCore.LOGGER.warn("gamble.json refund failed", e);
        }
        DATA = new Data();
        jackpotEnds = 0;
        save();
    }

    private static long tax(long pot) { return pot * TAX_PERCENT / 100; }

    private static MutableText pre(String what) {
        return Text.literal(what + " » ").formatted(Formatting.GOLD, Formatting.BOLD);
    }

    private static void broadcast(MinecraftServer s, Text t) {
        s.getPlayerManager().broadcast(t, false);
    }

    private static Long parse(ServerPlayerEntity p, String raw) {
        String r = raw.toLowerCase().replace(",", "").replace("$", "");
        double mul = 1;
        if (r.endsWith("k")) { mul = 1_000; r = r.substring(0, r.length() - 1); }
        else if (r.endsWith("m")) { mul = 1_000_000; r = r.substring(0, r.length() - 1); }
        else if (r.endsWith("b")) { mul = 1_000_000_000; r = r.substring(0, r.length() - 1); }
        try {
            long v = (long) Math.floor(Double.parseDouble(r) * mul);
            if (v < MIN_BET || v > MAX_BET) {
                p.sendMessage(Text.literal("Bet must be between " + Money.fmt(MIN_BET) + " and " + Money.fmt(MAX_BET) + ".").formatted(Formatting.RED), false);
                return null;
            }
            if (!Money.has(p, v)) {
                p.sendMessage(Text.literal("You don't have " + Money.fmt(v) + ".").formatted(Formatting.RED), false);
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            p.sendMessage(Text.literal("Use a number like 5000, 25k or 1.5m").formatted(Formatting.RED), false);
            return null;
        }
    }

    public static void commands(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("coinflip").executes(ctx -> { openFlips(ctx.getSource().getPlayerOrThrow()); return 1; })
                .then(CommandManager.literal("cancel").executes(ctx -> cancelFlip(ctx.getSource().getPlayerOrThrow())))
                .then(CommandManager.argument("amount", StringArgumentType.word())
                        .executes(ctx -> createFlip(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "amount")))));
        d.register(CommandManager.literal("cf").executes(ctx -> { openFlips(ctx.getSource().getPlayerOrThrow()); return 1; })
                .then(CommandManager.argument("amount", StringArgumentType.word())
                        .executes(ctx -> createFlip(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "amount")))));
        d.register(CommandManager.literal("jackpot").executes(ctx -> jackpotInfo(ctx.getSource().getPlayerOrThrow()))
                .then(CommandManager.argument("amount", StringArgumentType.word())
                        .executes(ctx -> joinJackpot(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "amount")))));
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (jackpotEnds == 0 || server.getTicks() % 20 != 0) return;
            long left = (jackpotEnds - System.currentTimeMillis()) / 1000;
            if (left == 30 || left == 10)
                broadcast(server, pre("Jackpot").append(Text.literal(left + "s left! Pot: " + Money.fmt(pot()) + "  ").formatted(Formatting.YELLOW))
                        .append(Text.literal("[/jackpot <amount>]").styled(s -> s.withColor(Formatting.GREEN)
                                .withClickEvent(new ClickEvent.SuggestCommand("/jackpot ")))));
            if (left <= 0) drawJackpot(server);
        });
    }

    // ------------------------------------------------------------------ coinflip
    private static int createFlip(ServerPlayerEntity p, String raw) {
        for (Flip f : DATA.flips) if (f.uuid.equals(p.getUuidAsString())) {
            p.sendMessage(Text.literal("You already have an open coinflip. /coinflip cancel").formatted(Formatting.RED), false);
            return 0;
        }
        Long amt = parse(p, raw);
        if (amt == null) return 0;
        Money.add(p, -amt);
        Flip f = new Flip();
        f.uuid = p.getUuidAsString(); f.name = p.getName().getString(); f.amount = amt; f.created = System.currentTimeMillis();
        DATA.flips.add(f);
        save();
        broadcast(p.getEntityWorld().getServer(), pre("Coinflip").append(Text.literal(f.name).formatted(Formatting.WHITE, Formatting.BOLD))
                .append(Text.literal(" bets ").formatted(Formatting.GRAY)).append(Text.literal(Money.fmt(amt)).formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal("  [ACCEPT]").styled(s -> s.withColor(Formatting.GREEN).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/coinflip")).withHoverEvent(new HoverEvent.ShowText(Text.literal("Open /coinflip"))))));
        return 1;
    }

    private static int cancelFlip(ServerPlayerEntity p) {
        for (Flip f : DATA.flips) if (f.uuid.equals(p.getUuidAsString())) {
            DATA.flips.remove(f);
            save();
            Money.add(p, f.amount);
            p.sendMessage(pre("Coinflip").append(Text.literal("Cancelled, " + Money.fmt(f.amount) + " refunded.").formatted(Formatting.YELLOW)), false);
            return 1;
        }
        p.sendMessage(Text.literal("You have no open coinflip.").formatted(Formatting.RED), false);
        return 0;
    }

    private static void acceptFlip(ServerPlayerEntity p, Flip f) {
        if (!DATA.flips.contains(f)) { p.sendMessage(Text.literal("That coinflip is gone.").formatted(Formatting.RED), false); return; }
        if (f.uuid.equals(p.getUuidAsString())) { cancelFlip(p); return; }
        if (!Money.has(p, f.amount)) {
            p.sendMessage(Text.literal("You need " + Money.fmt(f.amount) + " to accept.").formatted(Formatting.RED), false);
            return;
        }
        DATA.flips.remove(f);
        Money.add(p, -f.amount);
        save();
        long pot = f.amount * 2, prize = pot - tax(pot);
        boolean creatorWins = RNG.nextBoolean();
        MinecraftServer server = p.getEntityWorld().getServer();
        String winName = creatorWins ? f.name : p.getName().getString();
        String loseName = creatorWins ? p.getName().getString() : f.name;
        if (creatorWins) Money.addUuid(UUID.fromString(f.uuid), prize); else Money.add(p, prize);
        broadcast(server, pre("Coinflip").append(Text.literal(winName).formatted(Formatting.GREEN, Formatting.BOLD))
                .append(Text.literal(" won ").formatted(Formatting.GRAY)).append(Text.literal(Money.fmt(prize)).formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal(" against " + loseName + "!").formatted(Formatting.GRAY)));
        for (String u : new String[]{f.uuid, p.getUuidAsString()}) {
            ServerPlayerEntity o = server.getPlayerManager().getPlayer(UUID.fromString(u));
            if (o == null) continue;
            boolean won = o.getName().getString().equals(winName);
            o.getEntityWorld().playSound(null, o.getBlockPos(), won ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.ENTITY_VILLAGER_NO,
                    SoundCategory.PLAYERS, 0.8f, 1f);
        }
    }

    static void openFlips(ServerPlayerEntity p) {
        SimpleInventory inv = new SimpleInventory(54);
        Map<Integer, Flip> map = new LinkedHashMap<>();
        List<Flip> list = new ArrayList<>(DATA.flips);
        list.sort((a, b) -> Long.compare(b.amount, a.amount));
        for (int i = 0; i < Math.min(45, list.size()); i++) {
            Flip f = list.get(i);
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            try {
                ServerPlayerEntity owner = p.getEntityWorld().getServer().getPlayerManager().getPlayer(UUID.fromString(f.uuid));
                if (owner != null) head.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(owner.getGameProfile()));
            } catch (Throwable ignored) {}
            boolean mine = f.uuid.equals(p.getUuidAsString());
            head.set(DataComponentTypes.ITEM_NAME, Text.literal(f.name).formatted(Formatting.YELLOW, Formatting.BOLD));
            head.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    Text.literal("Bet: " + Money.fmt(f.amount)).styled(s -> s.withColor(Formatting.GOLD).withBold(true).withItalic(false)),
                    Text.literal("Winner gets " + Money.fmt(f.amount * 2 - tax(f.amount * 2))).styled(s -> s.withColor(Formatting.GRAY).withItalic(false)),
                    Text.literal(mine ? "Click to cancel" : "Click to accept - 50/50").styled(s -> s.withColor(mine ? Formatting.RED : Formatting.GREEN).withItalic(false)))));
            inv.setStack(i, head);
            map.put(i, f);
        }
        ItemStack pane = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        pane.set(DataComponentTypes.ITEM_NAME, Text.literal(" "));
        for (int i = 45; i < 54; i++) inv.setStack(i, pane.copy());
        ItemStack info = new ItemStack(Items.SUNFLOWER);
        info.set(DataComponentTypes.ITEM_NAME, Text.literal("Create a coinflip").formatted(Formatting.GOLD, Formatting.BOLD));
        info.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("/coinflip <amount>   e.g. /cf 50k").styled(s -> s.withColor(Formatting.GRAY).withItalic(false)),
                Text.literal("50/50 - winner takes the pot (-" + TAX_PERCENT + "% tax)").styled(s -> s.withColor(Formatting.GRAY).withItalic(false)))));
        inv.setStack(49, info);
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, pi, pl) -> new FlipHandler(sync, pi, inv, map),
                Text.literal("Coinflips (" + DATA.flips.size() + ")").formatted(Formatting.GOLD, Formatting.BOLD)));
    }

    static class FlipHandler extends GenericContainerScreenHandler {
        final Map<Integer, Flip> map;

        FlipHandler(int sync, PlayerInventory pi, SimpleInventory inv, Map<Integer, Flip> map) {
            super(ScreenHandlerType.GENERIC_9X6, sync, pi, inv, 6);
            this.map = map;
        }

        @Override
        public void onSlotClick(int slot, int button, SlotActionType type, PlayerEntity player) {
            if (player instanceof ServerPlayerEntity sp && type == SlotActionType.PICKUP && map.containsKey(slot)) {
                Flip f = map.get(slot);
                sp.closeHandledScreen();
                acceptFlip(sp, f);
                return;
            }
            this.syncState();
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }
    }

    // ------------------------------------------------------------------ jackpot
    private static long pot() {
        long t = 0;
        for (Entry e : DATA.jackpot) t += e.amount;
        return t;
    }

    private static int joinJackpot(ServerPlayerEntity p, String raw) {
        Long amt = parse(p, raw);
        if (amt == null) return 0;
        Money.add(p, -amt);
        Entry mine = null;
        for (Entry e : DATA.jackpot) if (e.uuid.equals(p.getUuidAsString())) mine = e;
        if (mine == null) {
            mine = new Entry();
            mine.uuid = p.getUuidAsString(); mine.name = p.getName().getString();
            DATA.jackpot.add(mine);
        }
        mine.amount += amt;
        if (jackpotEnds == 0) jackpotEnds = System.currentTimeMillis() + JACKPOT_SECONDS * 1000L;
        save();
        long pot = pot();
        broadcast(p.getEntityWorld().getServer(), pre("Jackpot").append(Text.literal(p.getName().getString()).formatted(Formatting.WHITE, Formatting.BOLD))
                .append(Text.literal(" put in ").formatted(Formatting.GRAY)).append(Text.literal(Money.fmt(amt)).formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal("  Pot: " + Money.fmt(pot) + "  (" + chance(mine.amount, pot) + " chance)").formatted(Formatting.YELLOW)));
        return 1;
    }

    private static String chance(long part, long pot) {
        return pot == 0 ? "0%" : String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * part / pot);
    }

    private static int jackpotInfo(ServerPlayerEntity p) {
        if (DATA.jackpot.isEmpty()) {
            p.sendMessage(pre("Jackpot").append(Text.literal("No round running. Start one: /jackpot <amount>").formatted(Formatting.YELLOW)), false);
            return 1;
        }
        long pot = pot(), left = Math.max(0, (jackpotEnds - System.currentTimeMillis()) / 1000);
        p.sendMessage(pre("Jackpot").append(Text.literal("Pot " + Money.fmt(pot) + " - " + left + "s left").formatted(Formatting.YELLOW)), false);
        for (Entry e : DATA.jackpot)
            p.sendMessage(Text.literal("  " + e.name + ": " + Money.fmt(e.amount) + " (" + chance(e.amount, pot) + ")").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static void drawJackpot(MinecraftServer server) {
        jackpotEnds = 0;
        List<Entry> entries = new ArrayList<>(DATA.jackpot);
        DATA.jackpot.clear();
        save();
        if (entries.isEmpty()) return;
        long pot = 0;
        for (Entry e : entries) pot += e.amount;
        if (entries.size() == 1) {
            Entry e = entries.get(0);
            Money.addUuid(UUID.fromString(e.uuid), e.amount);
            broadcast(server, pre("Jackpot").append(Text.literal("Nobody joined " + e.name + "'s jackpot - money refunded.").formatted(Formatting.GRAY)));
            return;
        }
        long roll = (long) (RNG.nextDouble() * pot), acc = 0;
        Entry win = entries.get(entries.size() - 1);
        for (Entry e : entries) { acc += e.amount; if (roll < acc) { win = e; break; } }
        long prize = pot - tax(pot);
        Money.addUuid(UUID.fromString(win.uuid), prize);
        broadcast(server, pre("Jackpot").append(Text.literal(win.name).formatted(Formatting.GREEN, Formatting.BOLD))
                .append(Text.literal(" won the jackpot: ").formatted(Formatting.GRAY)).append(Text.literal(Money.fmt(prize)).formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal(" with a " + chance(win.amount, pot) + " chance!").formatted(Formatting.GRAY)));
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList())
            o.getEntityWorld().playSound(null, o.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.5f, 1.4f);
    }
}
