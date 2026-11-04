package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rank based home limit on top of Essential Commands (/sethome, /delhome).
 * We remember which home names each player set; a NEW name is refused when the rank limit is reached.
 */
public final class Homes {
    private Homes() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, Set<String>> HOMES = new ConcurrentHashMap<>();
    private static boolean loaded = false;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("homes.json");
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            try (Reader r = Files.newBufferedReader(f)) {
                JsonElement el = JsonParser.parseReader(r);
                if (el == null || !el.isJsonObject()) return;
                for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                    Set<String> set = ConcurrentHashMap.newKeySet();
                    for (JsonElement n : e.getValue().getAsJsonArray()) set.add(n.getAsString());
                    HOMES.put(UUID.fromString(e.getKey()), set);
                }
            }
        } catch (Exception ex) {
            DracosCore.LOGGER.error("Failed to load homes.json", ex);
        }
    }

    private static synchronized void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            JsonObject o = new JsonObject();
            HOMES.forEach((k, v) -> {
                JsonArray a = new JsonArray();
                v.forEach(a::add);
                o.add(k.toString(), a);
            });
            try (Writer w = Files.newBufferedWriter(f)) { GSON.toJson(o, w); }
        } catch (Exception ex) {
            DracosCore.LOGGER.error("Failed to save homes.json", ex);
        }
    }

    private static Set<String> of(ServerPlayerEntity p) {
        load();
        return HOMES.computeIfAbsent(p.getUuid(), u -> ConcurrentHashMap.newKeySet());
    }

    /** Called for every command a player types. Returns true when the command must be cancelled. */
    public static boolean onCommand(ServerPlayerEntity p, String command) {
        String[] a = command.trim().split("\\s+");
        if (a.length == 0) return false;
        String cmd = a[0].toLowerCase();
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        if (cmd.equals("sethome") || (cmd.equals("home") && a.length >= 3 && a[1].equalsIgnoreCase("set"))) {
            String name = (cmd.equals("sethome") ? (a.length >= 2 ? a[1] : "home") : a[2]).toLowerCase();
            Set<String> set = of(p);
            int limit = Ranks.of(p).homes;
            if (!set.contains(name) && set.size() >= limit) {
                p.sendMessage(Text.literal("You reached your home limit (" + limit + ")! ").formatted(Formatting.RED)
                        .append(Text.literal("Delete one with /delhome <name> or get a better rank: /ranks").formatted(Formatting.GRAY)), false);
                return true;
            }
            set.add(name);
            save();
            p.sendMessage(Text.literal("Homes: " + set.size() + "/" + limit).formatted(Formatting.GRAY), false);
        } else if (cmd.equals("delhome") || cmd.equals("removehome")
                || (cmd.equals("home") && a.length >= 3 && (a[1].equalsIgnoreCase("delete") || a[1].equalsIgnoreCase("remove")))) {
            String name = (cmd.equals("home") ? a[2] : (a.length >= 2 ? a[1] : "home")).toLowerCase();
            if (of(p).remove(name)) save();
        }
        return false;
    }

    public static void info(ServerPlayerEntity p) {
        Set<String> set = of(p);
        Ranks.Rank r = Ranks.of(p);
        p.sendMessage(Text.literal("Homes: " + set.size() + "/" + r.homes + " ").formatted(Formatting.GOLD)
                .append(Text.literal(set.isEmpty() ? "(none)" : String.join(", ", new HashSet<>(set))).formatted(Formatting.GRAY)), false);
    }
}
