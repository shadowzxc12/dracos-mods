package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Owner tools: /dracos lastpos <player> (click to teleport) and /dracos kill <player> (works offline: dies on next join). */
public final class Admin {
    private Admin() {}

    static final class Pos {
        String name, dim;
        double x, y, z;
        long time;
    }

    static final class Data {
        Map<String, Pos> last = new HashMap<>();
        Set<String> killOnJoin = new HashSet<>();
        Set<String> god = new HashSet<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Data DATA = new Data();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("d.M. HH:mm").withZone(ZoneId.of("Europe/Bratislava"));

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("admin.json");
    }

    public static void load() {
        try {
            if (Files.exists(file())) {
                Data d = GSON.fromJson(Files.readString(file()), new TypeToken<Data>() {}.getType());
                if (d != null) {
                    if (d.last == null) d.last = new HashMap<>();
                    if (d.killOnJoin == null) d.killOnJoin = new HashSet<>();
                    if (d.god == null) d.god = new HashSet<>();
                    DATA = d;
                }
            }
        } catch (Exception e) {
            DracosCore.LOGGER.warn("admin.json unreadable", e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(DATA));
        } catch (Exception e) {
            DracosCore.LOGGER.warn("admin.json save failed", e);
        }
    }

    private static void remember(ServerPlayerEntity p) {
        Pos pos = new Pos();
        pos.name = p.getName().getString();
        pos.dim = p.getEntityWorld().getRegistryKey().getValue().toString();
        pos.x = p.getX(); pos.y = p.getY(); pos.z = p.getZ();
        pos.time = System.currentTimeMillis();
        DATA.last.put(pos.name.toLowerCase(), pos);
    }

    /** /god - owner only: no damage at all (except /kill), mobs stop targeting you. */
    public static int god(ServerPlayerEntity p) {
        if (Ranks.of(p) != Ranks.Rank.OWNER) {
            p.sendMessage(Text.literal("Unknown command.").formatted(Formatting.RED), false);
            return 0;
        }
        String u = p.getUuidAsString();
        boolean on = DATA.god.add(u);
        if (!on) DATA.god.remove(u);
        save();
        p.sendMessage(Text.literal(on ? "God mode ON - nothing can hurt you." : "God mode OFF.").formatted(on ? Formatting.GREEN : Formatting.YELLOW), false);
        return 1;
    }

    static boolean isGod(net.minecraft.entity.Entity e) {
        return e instanceof ServerPlayerEntity p && DATA.god.contains(p.getUuidAsString());
    }

    public static void register() {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !isGod(entity) || source.isOf(net.minecraft.entity.damage.DamageTypes.GENERIC_KILL));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (DATA.god.isEmpty() || server.getTicks() % 20 != 0) return;
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (!isGod(p)) continue;
                p.getHungerManager().setFoodLevel(20);
                p.setAir(p.getMaxAir());
                p.extinguish();
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            remember(handler.player);
            save();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            String key = handler.player.getName().getString().toLowerCase();
            if (DATA.killOnJoin.remove(key)) {
                save();
                server.execute(() -> {
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(handler.player.getUuid());
                    if (p != null) {
                        p.kill((ServerWorld) p.getEntityWorld());
                        DracosCore.LOGGER.info("Killed {} on join (/dracos kill while offline)", p.getName().getString());
                    }
                });
            }
        });
    }

    public static int lastpos(ServerCommandSource src, String name) {
        MinecraftServer server = src.getServer();
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(name);
        Pos pos;
        boolean isOnline = online != null;
        if (isOnline) {
            remember(online);
            pos = DATA.last.get(name.toLowerCase());
        } else pos = DATA.last.get(name.toLowerCase());
        if (pos == null) {
            src.sendError(Text.literal("No saved position for " + name + " (only players who left after this update are tracked)."));
            return 0;
        }
        String dimName = pos.dim.replace("minecraft:", "").replace("the_", "");
        String coords = (int) Math.floor(pos.x) + " " + (int) Math.floor(pos.y) + " " + (int) Math.floor(pos.z);
        String cmd = "/execute in " + pos.dim + " run tp @s " + String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f", pos.x, pos.y, pos.z);
        Text msg = Text.literal(pos.name + (isOnline ? " is online at " : " left at ")).formatted(Formatting.GRAY)
                .append(Text.literal(coords + " (" + dimName + ")").styled(s -> s.withColor(Formatting.AQUA).withUnderline(true)
                        .withClickEvent(new ClickEvent.RunCommand(cmd))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to teleport")))))
                .append(Text.literal(isOnline ? "" : "  " + FMT.format(Instant.ofEpochMilli(pos.time))).formatted(Formatting.DARK_GRAY))
                .append(Text.literal("  [TP]").styled(s -> s.withColor(Formatting.GREEN).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand(cmd))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal(cmd)))));
        src.sendFeedback(() -> msg, false);
        return 1;
    }

    public static int kill(ServerCommandSource src, String name) {
        ServerPlayerEntity p = src.getServer().getPlayerManager().getPlayer(name);
        if (p != null) {
            p.kill((ServerWorld) p.getEntityWorld());
            src.sendFeedback(() -> Text.literal("Killed " + p.getName().getString() + ".").formatted(Formatting.GREEN), true);
            return 1;
        }
        DATA.killOnJoin.add(name.toLowerCase());
        save();
        src.sendFeedback(() -> Text.literal(name + " is offline - they will die as soon as they join (items drop where they logged out).")
                .formatted(Formatting.YELLOW), true);
        return 1;
    }

    public static int unkill(ServerCommandSource src, String name) {
        boolean had = DATA.killOnJoin.remove(name.toLowerCase());
        save();
        src.sendFeedback(() -> Text.literal(had ? "Cancelled the pending kill for " + name + "." : name + " had no pending kill.")
                .formatted(Formatting.GREEN), false);
        return 1;
    }
}
