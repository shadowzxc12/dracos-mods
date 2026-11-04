package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Store ranks. Set with: /dracos rank <player> <member|vip|mvp|legend|draco>  (console / OP, e.g. from Tebex).
 * Perks: tag, trail, more homes, /sell bonus, /rankshop tiers.
 */
public final class Ranks {
    private Ranks() {}

    public enum Rank {
        MEMBER("e", "Member", Formatting.GRAY, null, 5, 0),
        VIP("d", "VIP", Formatting.GREEN, ParticleTypes.HAPPY_VILLAGER, 10, 5),
        MVP("c", "MVP", Formatting.AQUA, ParticleTypes.END_ROD, 15, 10),
        LEGEND("b", "Legend", Formatting.GOLD, ParticleTypes.FLAME, 25, 15),
        DRACO("a", "Draco", Formatting.LIGHT_PURPLE, ParticleTypes.REVERSE_PORTAL, 50, 25);

        final String sort;
        public final String title;
        public final Formatting color;
        final ParticleEffect trail;
        public final int homes;
        public final int sellBonus; // percent

        Rank(String sort, String title, Formatting color, ParticleEffect trail, int homes, int sellBonus) {
            this.homes = homes;
            this.sellBonus = sellBonus;
            this.sort = sort;
            this.title = title;
            this.color = color;
            this.trail = trail;
        }

        public boolean atLeast(Rank r) { return ordinal() >= r.ordinal(); }

        public Text prefix() {
            if (this == MEMBER) return Text.empty();
            MutableText t = Text.literal("[" + title.toUpperCase() + "] ").formatted(color, Formatting.BOLD);
            return t;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Rank> RANKS = new ConcurrentHashMap<>();
    private static final Map<java.util.UUID, double[]> LAST = new ConcurrentHashMap<>();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("ranks.json");
    }

    public static Rank of(ServerPlayerEntity p) {
        return RANKS.getOrDefault(p.getName().getString().toLowerCase(), Rank.MEMBER);
    }

    public static void load(MinecraftServer server) {
        RANKS.clear();
        try {
            Path f = file();
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f)) {
                    JsonElement el = JsonParser.parseReader(r);
                    if (el != null && el.isJsonObject()) {
                        for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                            try {
                                RANKS.put(e.getKey().toLowerCase(), Rank.valueOf(e.getValue().getAsString().toUpperCase()));
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
            DracosCore.LOGGER.info("Loaded {} rank(s)", RANKS.size());
        } catch (Exception ex) {
            DracosCore.LOGGER.error("Failed to load ranks.json", ex);
        }
        for (Rank r : Rank.values()) team(server, r);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) apply(server, p);
    }

    private static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            JsonObject o = new JsonObject();
            RANKS.forEach((k, v) -> o.addProperty(k, v.name().toLowerCase()));
            try (Writer w = Files.newBufferedWriter(f)) { GSON.toJson(o, w); }
        } catch (Exception ex) {
            DracosCore.LOGGER.error("Failed to save ranks.json", ex);
        }
    }

    /** Returns an error message or null. */
    public static String set(MinecraftServer server, String name, String rank) {
        Rank r;
        try {
            r = Rank.valueOf(rank.toUpperCase());
        } catch (Exception e) {
            return "Unknown rank: " + rank + " (member, vip, mvp, legend, draco)";
        }
        if (r == Rank.MEMBER) RANKS.remove(name.toLowerCase()); else RANKS.put(name.toLowerCase(), r);
        save();
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
        if (p != null) {
            apply(server, p);
            if (r != Rank.MEMBER) {
                server.getPlayerManager().broadcast(Text.literal("✦ ").formatted(r.color)
                        .append(Text.literal(p.getName().getString()).formatted(Formatting.WHITE, Formatting.BOLD))
                        .append(Text.literal(" just got the ").formatted(Formatting.GRAY))
                        .append(r.prefix())
                        .append(Text.literal("rank! Thank you for supporting Dracos SMP!").formatted(Formatting.GRAY)), false);
                ((ServerWorld) p.getEntityWorld()).playSound(null, p.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                        SoundCategory.PLAYERS, 1f, 1f);
            }
        }
        return null;
    }

    private static Team team(MinecraftServer server, Rank r) {
        Scoreboard sb = server.getScoreboard();
        String n = "dr_" + r.sort + "_" + r.name().toLowerCase();
        Team t = sb.getTeam(n);
        if (t == null) t = sb.addTeam(n);
        t.setPrefix(r.prefix());
        t.setColor(r.color);
        t.setDisplayName(Text.literal(r.title).formatted(r.color));
        return t;
    }

    private static void apply(MinecraftServer server, ServerPlayerEntity p) {
        Team t = team(server, of(p));
        server.getScoreboard().addScoreHolderToTeam(p.getNameForScoreboard(), t);
    }

    public static void showRanks(ServerPlayerEntity p) {
        p.sendMessage(Text.literal("━━━━━━ Dracos SMP Ranks ━━━━━━").formatted(Formatting.GOLD, Formatting.BOLD), false);
        line(p, Rank.MEMBER, "5 homes");
        line(p, Rank.VIP, "10 homes, +5% /sell, VIP /rankshop, /hat, sparkle trail");
        line(p, Rank.MVP, "15 homes, +10% /sell, MVP /rankshop, glowing trail, join msg");
        line(p, Rank.LEGEND, "25 homes, +15% /sell, LEGEND /rankshop, flame trail");
        line(p, Rank.DRACO, "50 homes, +25% /sell, DRACO /rankshop, void trail, join sound");
        p.sendMessage(Text.literal("Your rank: ").formatted(Formatting.GRAY)
                .append(Text.literal(of(p).title).formatted(of(p).color, Formatting.BOLD)), false);
    }

    private static void line(ServerPlayerEntity p, Rank r, String perks) {
        p.sendMessage(Text.empty().append(r.prefix()).append(Text.literal(perks).formatted(Formatting.GRAY)), false);
    }

    public static void hat(ServerPlayerEntity p) {
        if (!of(p).atLeast(Rank.VIP)) {
            p.sendMessage(Text.literal("/hat is a VIP+ perk. See /ranks").formatted(Formatting.RED), false);
            return;
        }
        ItemStack hand = p.getMainHandStack();
        if (hand.isEmpty()) {
            p.sendMessage(Text.literal("Hold the item you want to wear.").formatted(Formatting.RED), false);
            return;
        }
        ItemStack head = p.getEquippedStack(EquipmentSlot.HEAD);
        ItemStack one = hand.split(1);
        p.equipStack(EquipmentSlot.HEAD, one);
        if (!head.isEmpty()) p.getInventory().offerOrDrop(head);
        p.setStackInHand(Hand.MAIN_HAND, hand);
        p.sendMessage(Text.literal("Nice hat!").formatted(Formatting.GREEN), false);
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            apply(server, p);
            Rank r = of(p);
            if (r.atLeast(Rank.MVP)) {
                server.getPlayerManager().broadcast(Text.empty().append(r.prefix())
                        .append(Text.literal(p.getName().getString()).formatted(r.color))
                        .append(Text.literal(" joined the server!").formatted(Formatting.YELLOW)), false);
            }
            if (r.atLeast(Rank.DRACO)) {
                for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
                    ((ServerWorld) o.getEntityWorld()).playSound(null, o.getBlockPos(), SoundEvents.ENTITY_ENDER_DRAGON_GROWL,
                            SoundCategory.PLAYERS, 0.4f, 1.4f);
                }
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 4 != 0) return;
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                Rank r = of(p);
                if (r.trail == null || p.isSpectator() || p.isInvisible() || p.isSneaking()) continue;
                double[] last = LAST.put(p.getUuid(), new double[]{p.getX(), p.getZ()});
                if (last == null || Math.abs(last[0] - p.getX()) + Math.abs(last[1] - p.getZ()) < 0.1) continue; // only while moving
                ((ServerWorld) p.getEntityWorld()).spawnParticles(r.trail, p.getX(), p.getY() + 0.1, p.getZ(),
                        2, 0.2, 0.05, 0.2, 0.01);
            }
        });
    }
}
