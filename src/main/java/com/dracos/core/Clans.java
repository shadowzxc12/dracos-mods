package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageDecoratorEvent;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileEntity;
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
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** /clan - player clans: tag in chat, clan chat, shared bank, clan home, no friendly fire. */
public final class Clans {
    private Clans() {}

    public static final long CREATE_COST = 250_000;
    static final int MAX_MEMBERS = 15;
    private static final long INVITE_MS = 5 * 60_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static final class Clan {
        String name;
        String leader;                                  // uuid
        Map<String, String> members = new LinkedHashMap<>(); // uuid -> last known name
        long bank;
        long created;
        String homeWorld;
        double hx, hy, hz;
        float hyaw, hpitch;
    }

    private static Map<String, Clan> CLANS = new HashMap<>();      // lower name -> clan
    private static final Map<UUID, String> BY_PLAYER = new HashMap<>();
    private static final Map<UUID, String[]> INVITES = new HashMap<>(); // player -> {clanKey, expiresMs}
    private static final Set<UUID> CLAN_CHAT = new java.util.HashSet<>();

    // ---- clan wars (in memory; a restart cancels a running war)
    static final long WAR_MS = 60 * 60_000L;
    static final int WAR_PRIZE_PERCENT = 25;
    static final class War { String a, b; int ka, kb; long ends; }
    private static final Map<String, String> WAR_REQUESTS = new HashMap<>(); // target clan -> challenger clan
    private static final List<War> WARS = new ArrayList<>();

    static War warOf(String key) {
        for (War w : WARS) if (w.a.equals(key) || w.b.equals(key)) return w;
        return null;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("clans.json");
    }

    public static void load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                Map<String, Clan> m = GSON.fromJson(Files.readString(f), new TypeToken<Map<String, Clan>>() {}.getType());
                if (m != null) CLANS = new HashMap<>(m);
            }
        } catch (Exception e) {
            DracosCore.LOGGER.warn("clans.json unreadable", e);
        }
        BY_PLAYER.clear();
        for (var e : CLANS.entrySet()) for (String u : e.getValue().members.keySet()) BY_PLAYER.put(UUID.fromString(u), e.getKey());
        DracosCore.LOGGER.info("Loaded {} clan(s)", CLANS.size());
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(CLANS));
        } catch (Exception e) {
            DracosCore.LOGGER.warn("clans.json save failed", e);
        }
    }

    static Clan of(UUID u) {
        String k = BY_PLAYER.get(u);
        return k == null ? null : CLANS.get(k);
    }

    // ------------------------------------------------------------------ events
    public static void register() {
        // [CLAN] tag in front of every chat message; clan-chat mode sends to clan only
        ServerMessageDecoratorEvent.EVENT.register(ServerMessageDecoratorEvent.STYLING_PHASE, (sender, message) -> {
            if (sender == null) return message;
            Clan c = of(sender.getUuid());
            if (c == null) return message;
            return Text.empty().append(tag(c)).append(Text.literal(" ")).append(message);
        });
        net.fabricmc.fabric.api.message.v1.ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((msg, sender, params) -> {
            if (!CLAN_CHAT.contains(sender.getUuid()) || of(sender.getUuid()) == null) return true;
            clanChat(sender, msg.getSignedContent());
            return false;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayerEntity dead) || !(source.getAttacker() instanceof ServerPlayerEntity killer)) return;
            String kk = BY_PLAYER.get(killer.getUuid()), dk = BY_PLAYER.get(dead.getUuid());
            if (kk == null || dk == null || kk.equals(dk)) return;
            War w = warOf(kk);
            if (w == null || !(w.a.equals(dk) || w.b.equals(dk))) return;
            if (w.a.equals(kk)) w.ka++; else w.kb++;
            Clan ca = CLANS.get(w.a), cb = CLANS.get(w.b);
            if (ca == null || cb == null) return;
            Text t = Text.literal("⚔ WAR ").formatted(Formatting.RED, Formatting.BOLD).append(tag(ca))
                    .append(Text.literal(" " + w.ka + " : " + w.kb + " ").formatted(Formatting.WHITE, Formatting.BOLD)).append(tag(cb))
                    .append(Text.literal("  (" + killer.getName().getString() + " killed " + dead.getName().getString() + ")").formatted(Formatting.GRAY));
            MinecraftServer server = killer.getEntityWorld().getServer();
            toClan(server, ca, t);
            toClan(server, cb, t);
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (WARS.isEmpty() || server.getTicks() % 20 != 0) return;
            long now = System.currentTimeMillis();
            var it = WARS.iterator();
            while (it.hasNext()) {
                War w = it.next();
                if (now < w.ends) continue;
                it.remove();
                endWar(server, w);
            }
        });
        // no friendly fire inside a clan
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity victim)) return true;
            Entity a = source.getAttacker();
            if (a == null && source.getSource() instanceof ProjectileEntity pr) a = pr.getOwner();
            if (!(a instanceof ServerPlayerEntity attacker) || attacker == victim) return true;
            Clan c = of(victim.getUuid());
            return c == null || c != of(attacker.getUuid());
        });
    }

    static MutableText tag(Clan c) {
        return Text.literal("[" + c.name + "]").formatted(Formatting.AQUA, Formatting.BOLD);
    }

    private static MutableText pre() {
        return Text.literal("Clan » ").formatted(Formatting.DARK_AQUA, Formatting.BOLD);
    }

    private static void msg(ServerPlayerEntity p, String s, Formatting f) {
        p.sendMessage(pre().append(Text.literal(s).formatted(f)), false);
    }

    private static void toClan(MinecraftServer server, Clan c, Text t) {
        for (String u : c.members.keySet()) {
            ServerPlayerEntity m = server.getPlayerManager().getPlayer(UUID.fromString(u));
            if (m != null) m.sendMessage(t, false);
        }
    }

    private static void clanChat(ServerPlayerEntity p, String text) {
        Clan c = of(p.getUuid());
        if (c == null) { msg(p, "You are not in a clan.", Formatting.RED); return; }
        toClan(p.getEntityWorld().getServer(), c, Text.literal("[Clan] ").formatted(Formatting.DARK_AQUA, Formatting.BOLD)
                .append(Text.literal(p.getName().getString() + ": ").formatted(Formatting.AQUA))
                .append(Text.literal(text).formatted(Formatting.WHITE)));
    }

    // ------------------------------------------------------------------ commands
    public static void commands(CommandDispatcher<ServerCommandSource> d) {
        var root = CommandManager.literal("clan").executes(ctx -> help(ctx.getSource().getPlayerOrThrow()))
                .then(CommandManager.literal("help").executes(ctx -> help(ctx.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("create").then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> create(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "name")))))
                .then(CommandManager.literal("invite").then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(ctx -> invite(ctx.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(ctx, "player")))))
                .then(CommandManager.literal("join").then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> join(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "name")))))
                .then(CommandManager.literal("leave").executes(ctx -> leave(ctx.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("kick").then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> kick(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "name")))))
                .then(CommandManager.literal("disband").executes(ctx -> disband(ctx.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("info").executes(ctx -> info(ctx.getSource().getPlayerOrThrow(), null))
                        .then(CommandManager.argument("name", StringArgumentType.word())
                                .executes(ctx -> info(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "name")))))
                .then(CommandManager.literal("list").executes(ctx -> list(ctx.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("top").executes(ctx -> list(ctx.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("sethome").executes(ctx -> sethome(ctx.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("home").executes(ctx -> home(ctx.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("deposit").then(CommandManager.argument("amount", LongArgumentType.longArg(1))
                        .executes(ctx -> deposit(ctx.getSource().getPlayerOrThrow(), LongArgumentType.getLong(ctx, "amount")))))
                .then(CommandManager.literal("withdraw").then(CommandManager.argument("amount", LongArgumentType.longArg(1))
                        .executes(ctx -> withdraw(ctx.getSource().getPlayerOrThrow(), LongArgumentType.getLong(ctx, "amount")))))
                .then(CommandManager.literal("war").executes(ctx -> warStatus(ctx.getSource().getPlayerOrThrow()))
                        .then(CommandManager.literal("accept").then(CommandManager.argument("name", StringArgumentType.word())
                                .executes(ctx -> warAccept(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "name")))))
                        .then(CommandManager.argument("name", StringArgumentType.word())
                                .executes(ctx -> warDeclare(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "name")))))
                .then(CommandManager.literal("chat").executes(ctx -> toggleChat(ctx.getSource().getPlayerOrThrow()))
                        .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> { clanChat(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "message")); return 1; })));
        d.register(root);
        d.register(CommandManager.literal("cc").then(CommandManager.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> { clanChat(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "message")); return 1; })));
    }

    private static int help(ServerPlayerEntity p) {
        p.sendMessage(Text.literal("━━━━━━━ CLANS ━━━━━━━").formatted(Formatting.DARK_AQUA, Formatting.BOLD), false);
        String[][] lines = {
                {"/clan create <name>", "make a clan (" + Money.fmt(CREATE_COST) + ")"},
                {"/clan invite <player>", "invite a player"},
                {"/clan join <name>", "accept an invite"},
                {"/clan info [name]", "members, bank, home"},
                {"/clan home", "teleport to clan home"},
                {"/clan sethome", "set clan home (leader)"},
                {"/clan deposit <$>", "put money in the bank"},
                {"/clan withdraw <$>", "take money (leader)"},
                {"/cc <message>", "clan chat (/clan chat = toggle)"},
                {"/clan war <clan>", "challenge a clan to a 1h war"},
                {"/clan kick <name>", "kick a member (leader)"},
                {"/clan leave", "leave your clan"},
                {"/clan disband", "delete the clan (leader)"},
                {"/clan top", "richest clans"}};
        for (String[] l : lines)
            p.sendMessage(Text.literal(l[0]).styled(s -> s.withColor(Formatting.AQUA)
                            .withClickEvent(new ClickEvent.SuggestCommand(l[0].replaceAll(" [<\\[].*", " "))))
                    .append(Text.literal("  " + l[1]).formatted(Formatting.GRAY)), false);
        return 1;
    }

    private static int create(ServerPlayerEntity p, String name) {
        if (of(p.getUuid()) != null) { msg(p, "You are already in a clan. /clan leave first.", Formatting.RED); return 0; }
        if (!name.matches("[A-Za-z0-9_]{3,10}")) { msg(p, "Name must be 3-10 letters/numbers.", Formatting.RED); return 0; }
        String key = name.toLowerCase(Locale.ROOT);
        if (CLANS.containsKey(key)) { msg(p, "That clan name is taken.", Formatting.RED); return 0; }
        if (!Money.has(p, CREATE_COST)) {
            msg(p, "Creating a clan costs " + Money.fmt(CREATE_COST) + ". You have " + Money.fmt(Money.get(p)) + ".", Formatting.RED);
            return 0;
        }
        Money.add(p, -CREATE_COST);
        Clan c = new Clan();
        c.name = name.toUpperCase(Locale.ROOT);
        c.leader = p.getUuidAsString();
        c.members.put(p.getUuidAsString(), p.getName().getString());
        c.created = System.currentTimeMillis();
        CLANS.put(key, c);
        BY_PLAYER.put(p.getUuid(), key);
        save();
        p.getEntityWorld().getServer().getPlayerManager().broadcast(Text.literal("⚔ ").formatted(Formatting.AQUA)
                .append(Text.literal(p.getName().getString()).formatted(Formatting.WHITE, Formatting.BOLD))
                .append(Text.literal(" founded the clan ").formatted(Formatting.GRAY)).append(tag(c)), false);
        ((ServerWorld) p.getEntityWorld()).playSound(null, p.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1f, 1f);
        msg(p, "Invite friends with /clan invite <player>.", Formatting.GREEN);
        return 1;
    }

    private static boolean leader(ServerPlayerEntity p, Clan c) {
        if (c == null) { msg(p, "You are not in a clan.", Formatting.RED); return false; }
        if (!c.leader.equals(p.getUuidAsString())) { msg(p, "Only the clan leader can do that.", Formatting.RED); return false; }
        return true;
    }

    private static int invite(ServerPlayerEntity p, ServerPlayerEntity t) {
        Clan c = of(p.getUuid());
        if (!leader(p, c)) return 0;
        if (t == p) return 0;
        if (of(t.getUuid()) != null) { msg(p, t.getName().getString() + " is already in a clan.", Formatting.RED); return 0; }
        if (c.members.size() >= MAX_MEMBERS) { msg(p, "Your clan is full (" + MAX_MEMBERS + ").", Formatting.RED); return 0; }
        INVITES.put(t.getUuid(), new String[]{c.name.toLowerCase(Locale.ROOT), String.valueOf(System.currentTimeMillis() + INVITE_MS)});
        msg(p, "Invited " + t.getName().getString() + ".", Formatting.GREEN);
        String cmd = "/clan join " + c.name;
        t.sendMessage(pre().append(Text.literal(p.getName().getString() + " invited you to ").formatted(Formatting.GRAY)).append(tag(c))
                .append(Text.literal("  [JOIN]").styled(s -> s.withColor(Formatting.GREEN).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand(cmd))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal(cmd))))), false);
        return 1;
    }

    private static int join(ServerPlayerEntity p, String name) {
        if (of(p.getUuid()) != null) { msg(p, "You are already in a clan.", Formatting.RED); return 0; }
        String key = name.toLowerCase(Locale.ROOT);
        String[] inv = INVITES.get(p.getUuid());
        if (inv == null || !inv[0].equals(key) || Long.parseLong(inv[1]) < System.currentTimeMillis()) {
            msg(p, "You have no invite from that clan (invites last 5 minutes).", Formatting.RED);
            return 0;
        }
        Clan c = CLANS.get(key);
        if (c == null) { msg(p, "That clan no longer exists.", Formatting.RED); return 0; }
        if (c.members.size() >= MAX_MEMBERS) { msg(p, "That clan is full.", Formatting.RED); return 0; }
        INVITES.remove(p.getUuid());
        c.members.put(p.getUuidAsString(), p.getName().getString());
        BY_PLAYER.put(p.getUuid(), key);
        save();
        toClan(p.getEntityWorld().getServer(), c, pre().append(Text.literal(p.getName().getString() + " joined the clan!").formatted(Formatting.GREEN)));
        return 1;
    }

    private static int leave(ServerPlayerEntity p) {
        Clan c = of(p.getUuid());
        if (c == null) { msg(p, "You are not in a clan.", Formatting.RED); return 0; }
        if (c.leader.equals(p.getUuidAsString())) {
            if (c.members.size() > 1) { msg(p, "You are the leader. Kick everyone or /clan disband.", Formatting.RED); return 0; }
            return disband(p);
        }
        c.members.remove(p.getUuidAsString());
        BY_PLAYER.remove(p.getUuid());
        CLAN_CHAT.remove(p.getUuid());
        save();
        msg(p, "You left " + c.name + ".", Formatting.YELLOW);
        toClan(p.getEntityWorld().getServer(), c, pre().append(Text.literal(p.getName().getString() + " left the clan.").formatted(Formatting.YELLOW)));
        return 1;
    }

    private static int kick(ServerPlayerEntity p, String name) {
        Clan c = of(p.getUuid());
        if (!leader(p, c)) return 0;
        String target = null;
        for (var e : c.members.entrySet()) if (e.getValue().equalsIgnoreCase(name)) target = e.getKey();
        if (target == null || target.equals(c.leader)) { msg(p, "No such member.", Formatting.RED); return 0; }
        c.members.remove(target);
        BY_PLAYER.remove(UUID.fromString(target));
        CLAN_CHAT.remove(UUID.fromString(target));
        save();
        toClan(p.getEntityWorld().getServer(), c, pre().append(Text.literal(name + " was kicked from the clan.").formatted(Formatting.YELLOW)));
        ServerPlayerEntity t = p.getEntityWorld().getServer().getPlayerManager().getPlayer(UUID.fromString(target));
        if (t != null) msg(t, "You were kicked from " + c.name + ".", Formatting.RED);
        return 1;
    }

    private static int disband(ServerPlayerEntity p) {
        Clan c = of(p.getUuid());
        if (!leader(p, c)) return 0;
        MinecraftServer server = p.getEntityWorld().getServer();
        toClan(server, c, pre().append(Text.literal("The clan " + c.name + " was disbanded.").formatted(Formatting.RED)));
        if (c.bank > 0) {
            Money.add(p, c.bank);
            msg(p, "The clan bank (" + Money.fmt(c.bank) + ") was paid to you.", Formatting.GOLD);
        }
        for (String u : c.members.keySet()) { BY_PLAYER.remove(UUID.fromString(u)); CLAN_CHAT.remove(UUID.fromString(u)); }
        CLANS.remove(c.name.toLowerCase(Locale.ROOT));
        save();
        return 1;
    }

    private static int info(ServerPlayerEntity p, String name) {
        Clan c = name == null ? of(p.getUuid()) : CLANS.get(name.toLowerCase(Locale.ROOT));
        if (c == null) { msg(p, name == null ? "You are not in a clan. /clan create <name>" : "No clan with that name.", Formatting.RED); return 0; }
        MinecraftServer server = p.getEntityWorld().getServer();
        p.sendMessage(Text.literal("━━━ ").formatted(Formatting.DARK_AQUA).append(tag(c)).append(Text.literal(" ━━━").formatted(Formatting.DARK_AQUA)), false);
        p.sendMessage(Text.literal("Leader: ").formatted(Formatting.GRAY).append(Text.literal(c.members.getOrDefault(c.leader, "?")).formatted(Formatting.WHITE)), false);
        p.sendMessage(Text.literal("Bank: ").formatted(Formatting.GRAY).append(Text.literal(Money.fmt(c.bank)).formatted(Formatting.GOLD)), false);
        p.sendMessage(Text.literal("Home: ").formatted(Formatting.GRAY).append(Text.literal(c.homeWorld == null ? "not set" : "set").formatted(Formatting.WHITE)), false);
        MutableText m = Text.literal("Members (" + c.members.size() + "/" + MAX_MEMBERS + "): ").formatted(Formatting.GRAY);
        boolean first = true;
        for (var e : c.members.entrySet()) {
            boolean on = server.getPlayerManager().getPlayer(UUID.fromString(e.getKey())) != null;
            if (!first) m.append(Text.literal(", ").formatted(Formatting.DARK_GRAY));
            m.append(Text.literal(e.getValue()).formatted(on ? Formatting.GREEN : Formatting.GRAY));
            first = false;
        }
        p.sendMessage(m, false);
        return 1;
    }

    private static int list(ServerPlayerEntity p) {
        if (CLANS.isEmpty()) { msg(p, "No clans yet. Be the first: /clan create <name>", Formatting.YELLOW); return 1; }
        List<Clan> l = new ArrayList<>(CLANS.values());
        l.sort(Comparator.comparingLong((Clan c) -> c.bank).reversed().thenComparingInt(c -> -c.members.size()));
        p.sendMessage(Text.literal("━━━ TOP CLANS ━━━").formatted(Formatting.DARK_AQUA, Formatting.BOLD), false);
        for (int i = 0; i < Math.min(10, l.size()); i++) {
            Clan c = l.get(i);
            p.sendMessage(Text.literal("#" + (i + 1) + " ").formatted(Formatting.YELLOW).append(tag(c))
                    .append(Text.literal("  " + c.members.size() + " members  ").formatted(Formatting.GRAY))
                    .append(Text.literal(Money.fmt(c.bank)).formatted(Formatting.GOLD)), false);
        }
        return 1;
    }

    private static int sethome(ServerPlayerEntity p) {
        Clan c = of(p.getUuid());
        if (!leader(p, c)) return 0;
        c.homeWorld = p.getEntityWorld().getRegistryKey().getValue().toString();
        c.hx = p.getX(); c.hy = p.getY(); c.hz = p.getZ(); c.hyaw = p.getYaw(); c.hpitch = p.getPitch();
        save();
        msg(p, "Clan home set!", Formatting.GREEN);
        return 1;
    }

    private static int home(ServerPlayerEntity p) {
        Clan c = of(p.getUuid());
        if (c == null) { msg(p, "You are not in a clan.", Formatting.RED); return 0; }
        if (c.homeWorld == null) { msg(p, "Your clan has no home. Leader: /clan sethome", Formatting.RED); return 0; }
        if (Combat.inCombat(p)) { msg(p, "You can't teleport in combat!", Formatting.RED); return 0; }
        ServerWorld w = p.getEntityWorld().getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(c.homeWorld)));
        if (w == null) { msg(p, "Clan home world is missing.", Formatting.RED); return 0; }
        p.teleport(w, c.hx, c.hy, c.hz, Set.of(), c.hyaw, c.hpitch, true);
        msg(p, "Teleported to clan home.", Formatting.GREEN);
        return 1;
    }

    private static int deposit(ServerPlayerEntity p, long amt) {
        Clan c = of(p.getUuid());
        if (c == null) { msg(p, "You are not in a clan.", Formatting.RED); return 0; }
        if (!Money.has(p, amt)) { msg(p, "You don't have " + Money.fmt(amt) + ".", Formatting.RED); return 0; }
        Money.add(p, -amt);
        c.bank += amt;
        save();
        toClan(p.getEntityWorld().getServer(), c, pre().append(Text.literal(p.getName().getString() + " deposited " + Money.fmt(amt)
                + " (bank: " + Money.fmt(c.bank) + ")").formatted(Formatting.GOLD)));
        return 1;
    }

    private static int withdraw(ServerPlayerEntity p, long amt) {
        Clan c = of(p.getUuid());
        if (!leader(p, c)) return 0;
        if (c.bank < amt) { msg(p, "The clan bank only has " + Money.fmt(c.bank) + ".", Formatting.RED); return 0; }
        c.bank -= amt;
        save();
        Money.add(p, amt);
        toClan(p.getEntityWorld().getServer(), c, pre().append(Text.literal(p.getName().getString() + " withdrew " + Money.fmt(amt)
                + " (bank: " + Money.fmt(c.bank) + ")").formatted(Formatting.GOLD)));
        return 1;
    }

    private static int warDeclare(ServerPlayerEntity p, String name) {
        Clan c = of(p.getUuid());
        if (!leader(p, c)) return 0;
        String me = c.name.toLowerCase(Locale.ROOT), them = name.toLowerCase(Locale.ROOT);
        Clan t = CLANS.get(them);
        if (t == null || t == c) { msg(p, "No clan with that name.", Formatting.RED); return 0; }
        if (warOf(me) != null || warOf(them) != null) { msg(p, "One of the clans is already in a war.", Formatting.RED); return 0; }
        WAR_REQUESTS.put(them, me);
        MinecraftServer server = p.getEntityWorld().getServer();
        msg(p, "War declared on " + t.name + "! Their leader must accept.", Formatting.GOLD);
        String cmd = "/clan war accept " + c.name;
        toClan(server, t, pre().append(tag(c)).append(Text.literal(" declared WAR on your clan! 1 hour, most kills wins "
                        + WAR_PRIZE_PERCENT + "% of the loser's bank. ").formatted(Formatting.RED))
                .append(Text.literal("[ACCEPT]").styled(s -> s.withColor(Formatting.GREEN).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand(cmd)).withHoverEvent(new HoverEvent.ShowText(Text.literal(cmd))))));
        return 1;
    }

    private static int warAccept(ServerPlayerEntity p, String name) {
        Clan c = of(p.getUuid());
        if (!leader(p, c)) return 0;
        String me = c.name.toLowerCase(Locale.ROOT), them = name.toLowerCase(Locale.ROOT);
        if (!them.equals(WAR_REQUESTS.get(me)) || !CLANS.containsKey(them)) { msg(p, "That clan did not challenge you.", Formatting.RED); return 0; }
        if (warOf(me) != null || warOf(them) != null) { msg(p, "One of the clans is already in a war.", Formatting.RED); return 0; }
        WAR_REQUESTS.remove(me);
        War w = new War();
        w.a = them; w.b = me; w.ends = System.currentTimeMillis() + WAR_MS;
        WARS.add(w);
        MinecraftServer server = p.getEntityWorld().getServer();
        server.getPlayerManager().broadcast(Text.literal("⚔ CLAN WAR! ").formatted(Formatting.DARK_RED, Formatting.BOLD)
                .append(tag(CLANS.get(them))).append(Text.literal(" vs ").formatted(Formatting.WHITE)).append(tag(c))
                .append(Text.literal("  - 1 hour, most kills wins " + WAR_PRIZE_PERCENT + "% of the loser's clan bank!").formatted(Formatting.GRAY)), false);
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList())
            o.getEntityWorld().playSound(null, o.getBlockPos(), SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.PLAYERS, 0.6f, 1f);
        return 1;
    }

    private static int warStatus(ServerPlayerEntity p) {
        Clan c = of(p.getUuid());
        if (c == null) { msg(p, "You are not in a clan.", Formatting.RED); return 0; }
        War w = warOf(c.name.toLowerCase(Locale.ROOT));
        if (w == null) { msg(p, "No war right now. Leader: /clan war <clan>", Formatting.YELLOW); return 1; }
        long min = Math.max(0, (w.ends - System.currentTimeMillis()) / 60_000);
        Clan ca = CLANS.get(w.a), cb = CLANS.get(w.b);
        if (ca == null || cb == null) return 0;
        p.sendMessage(pre().append(tag(ca)).append(Text.literal(" " + w.ka + " : " + w.kb + " ").formatted(Formatting.WHITE, Formatting.BOLD))
                .append(tag(cb)).append(Text.literal("  " + min + " min left").formatted(Formatting.GRAY)), false);
        return 1;
    }

    private static void endWar(MinecraftServer server, War w) {
        Clan ca = CLANS.get(w.a), cb = CLANS.get(w.b);
        if (ca == null || cb == null) return;
        MutableText t = Text.literal("⚔ WAR OVER ").formatted(Formatting.DARK_RED, Formatting.BOLD).append(tag(ca))
                .append(Text.literal(" " + w.ka + " : " + w.kb + " ").formatted(Formatting.WHITE, Formatting.BOLD)).append(tag(cb));
        if (w.ka == w.kb) t.append(Text.literal("  - it's a draw!").formatted(Formatting.YELLOW));
        else {
            Clan win = w.ka > w.kb ? ca : cb, lose = w.ka > w.kb ? cb : ca;
            long prize = lose.bank * WAR_PRIZE_PERCENT / 100;
            lose.bank -= prize;
            win.bank += prize;
            save();
            t.append(Text.literal("  ").append(tag(win)).append(Text.literal(" wins " + Money.fmt(prize) + "!").formatted(Formatting.GOLD, Formatting.BOLD)));
        }
        server.getPlayerManager().broadcast(t, false);
    }

    private static int toggleChat(ServerPlayerEntity p) {
        if (of(p.getUuid()) == null) { msg(p, "You are not in a clan.", Formatting.RED); return 0; }
        boolean on = CLAN_CHAT.add(p.getUuid());
        if (!on) CLAN_CHAT.remove(p.getUuid());
        msg(p, on ? "Clan chat ON - your messages go to your clan only." : "Clan chat OFF - back to public chat.", Formatting.AQUA);
        return 1;
    }
}
