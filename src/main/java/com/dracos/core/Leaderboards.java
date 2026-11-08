package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Floating top-10 boards at the hub: richest players, most player kills, most playtime. Refreshed every minute. */
public final class Leaderboards {
    private Leaderboards() {}

    static final class Data {
        Map<String, Long> kills = new HashMap<>();
        Map<String, Long> minutes = new HashMap<>();
        Map<String, String> names = new HashMap<>();
    }

    private static final Gson GSON = new Gson();
    private static Data DATA = new Data();
    private static boolean dirty;

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("leaderboards.json"); }

    public static void load() {
        try {
            if (Files.exists(file())) {
                Data d = GSON.fromJson(Files.readString(file()), Data.class);
                if (d != null) DATA = d;
                if (DATA.kills == null) DATA.kills = new HashMap<>();
                if (DATA.minutes == null) DATA.minutes = new HashMap<>();
                if (DATA.names == null) DATA.names = new HashMap<>();
            }
        } catch (Exception e) { DracosCore.LOGGER.warn("leaderboards.json unreadable", e); }
    }

    static void save() {
        if (!dirty) return;
        dirty = false;
        try { Files.createDirectories(file().getParent()); Files.writeString(file(), GSON.toJson(DATA)); }
        catch (Exception e) { DracosCore.LOGGER.warn("leaderboards.json save failed", e); }
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity dead && source.getAttacker() instanceof ServerPlayerEntity killer && killer != dead) {
                DATA.kills.merge(killer.getUuidAsString(), 1L, Long::sum);
                dirty = true;
            }
        });
        ServerPlayConnectionEvents.JOIN.register((h, s, server) -> {
            DATA.names.put(h.player.getUuidAsString(), h.player.getName().getString());
            dirty = true;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 1200 != 0) return;      // every minute
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) DATA.minutes.merge(p.getUuidAsString(), 1L, Long::sum);
            dirty = true;
            save();
            refresh(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { dirty = true; save(); });
    }

    // ------------------------------------------------------------------ boards
    static Map<String, String> names(MinecraftServer server) {
        Map<String, String> n = new HashMap<>();
        try {
            Path uc = server.getRunDirectory().resolve("usercache.json");
            if (Files.exists(uc)) {
                JsonArray arr = JsonParser.parseString(Files.readString(uc)).getAsJsonArray();
                for (JsonElement e : arr) {
                    JsonObject o = e.getAsJsonObject();
                    n.put(o.get("uuid").getAsString(), o.get("name").getAsString());
                }
            }
        } catch (Exception ignored) {}
        n.putAll(DATA.names);
        return n;
    }

    static Map<String, Long> balances() {
        try {
            Path f = FabricLoader.getInstance().getConfigDir().resolve("economy").resolve("balances.json");
            if (Files.exists(f)) {
                Map<String, Long> m = GSON.fromJson(Files.readString(f), new TypeToken<Map<String, Long>>() {}.getType());
                if (m != null) return m;
            }
        } catch (Exception e) { DracosCore.LOGGER.warn("Could not read balances for leaderboard", e); }
        return Map.of();
    }

    static List<Map.Entry<String, Long>> top(Map<String, Long> m, Map<String, String> names) {
        List<Map.Entry<String, Long>> l = new ArrayList<>();
        for (var e : m.entrySet()) {
            String name = names.get(e.getKey());
            if (name == null || e.getValue() <= 0) continue;
            l.add(e);
        }
        l.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        return l.subList(0, Math.min(10, l.size()));
    }

    /** Player kills and play time from Minecraft's own statistics (all players ever, online ones live). */
    static void vanillaStats(MinecraftServer server, Map<String, Long> kills, Map<String, Long> minutes) {
        try {
            Path dir = server.getSavePath(net.minecraft.util.WorldSavePath.STATS);
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        String fn = f.getFileName().toString();
                        if (!fn.endsWith(".json")) continue;
                        try {
                            JsonObject custom = JsonParser.parseString(Files.readString(f)).getAsJsonObject()
                                    .getAsJsonObject("stats").getAsJsonObject("minecraft:custom");
                            if (custom == null) continue;
                            String u = fn.substring(0, fn.length() - 5);
                            if (custom.has("minecraft:player_kills")) kills.put(u, custom.get("minecraft:player_kills").getAsLong());
                            if (custom.has("minecraft:play_time")) minutes.put(u, custom.get("minecraft:play_time").getAsLong() / 1200);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) { DracosCore.LOGGER.warn("Could not read stats for leaderboard", e); }
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            var sh = p.getStatHandler();
            kills.put(p.getUuidAsString(), (long) sh.getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.PLAYER_KILLS)));
            minutes.put(p.getUuidAsString(), (long) sh.getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.PLAY_TIME)) / 1200);
        }
    }

    static String time(long minutes) {
        long h = minutes / 60;
        return h >= 24 ? (h / 24) + "d " + (h % 24) + "h" : h + "h " + (minutes % 60) + "m";
    }

    public static void refresh(MinecraftServer server) {
        if (Hub.CFG.radius <= 0 || Hub.CFG.boards.isEmpty()) return;
        Map<String, String> names = names(server);
        Hub.cmd(server, "execute in " + Hub.CFG.world + " run kill @e[type=minecraft:text_display,tag=dracos_lb]");
        board(server, "money", "$ RICHEST PLAYERS", Formatting.GOLD, top(balances(), names), names, Money::fmt);
        Map<String, Long> kills = new HashMap<>(), minutes = new HashMap<>();
        vanillaStats(server, kills, minutes);
        board(server, "kills", "⚔ TOP KILLERS", Formatting.RED, top(kills, names), names, v -> v + " kills");
        board(server, "playtime", "✪ MOST PLAYTIME", Formatting.AQUA, top(minutes, names), names, Leaderboards::time);
    }

    static void board(MinecraftServer server, String id, String title, Formatting color, List<Map.Entry<String, Long>> rows,
                      Map<String, String> names, java.util.function.Function<Long, String> fmt) {
        double[] pos = Hub.CFG.boards.get(id);
        if (pos == null) return;
        MutableText t = Text.empty().append(Text.literal(title + "\n").formatted(color, Formatting.BOLD));
        if (rows.isEmpty()) t.append(Text.literal("No data yet").formatted(Formatting.GRAY));
        for (int i = 0; i < rows.size(); i++) {
            var e = rows.get(i);
            Formatting rank = i == 0 ? Formatting.GOLD : i == 1 ? Formatting.WHITE : i == 2 ? Formatting.RED : Formatting.GRAY;
            t.append(Text.literal("#" + (i + 1) + " ").formatted(rank, Formatting.BOLD))
                    .append(Text.literal(names.get(e.getKey()) + " ").formatted(Formatting.WHITE))
                    .append(Text.literal(fmt.apply(e.getValue())).formatted(Formatting.YELLOW))
                    .append(Text.literal(i < rows.size() - 1 ? "\n" : ""));
        }
        Hub.hologram(server, pos[0], pos[1], pos[2], t, "dracos_lb");
    }
}
