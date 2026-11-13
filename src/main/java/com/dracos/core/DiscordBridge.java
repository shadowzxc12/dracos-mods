package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * One-way Discord bridge via a channel webhook: server start/stop, joins/leaves, chat, deaths, boss kills.
 * Config: config/dracos/discord.json  {"webhook": "https://discord.com/api/webhooks/...", "chat": true, ...}
 */
public final class DiscordBridge {
    private DiscordBridge() {}

    static class Cfg {
        String webhook = "";
        boolean chat = true, joins = true, deaths = true, bosses = true, status = true;
        String name = "Dracos SMP";
    }

    static Cfg CFG = new Cfg();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("discord.json");
    }

    static void load() {
        try {
            Path f = file();
            if (Files.exists(f)) CFG = new Gson().fromJson(Files.readString(f), Cfg.class);
            else {
                Files.createDirectories(f.getParent());
                Files.writeString(f, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(CFG));
            }
            if (CFG == null) CFG = new Cfg();
        } catch (Exception e) {
            DracosCore.LOGGER.warn("discord.json load failed", e);
            CFG = new Cfg();
        }
    }

    static boolean on() {
        return CFG.webhook != null && CFG.webhook.startsWith("https://discord.com/api/webhooks/");
    }

    /** Sends a message (async, never blocks the server thread). Mentions are disabled. */
    static void send(String user, String avatarUuid, String content) {
        if (!on() || content == null || content.isBlank()) return;
        JsonObject o = new JsonObject();
        o.addProperty("content", content.length() > 1900 ? content.substring(0, 1900) : content);
        o.addProperty("username", user == null ? CFG.name : user);
        if (avatarUuid != null) o.addProperty("avatar_url", "https://mc-heads.net/avatar/" + avatarUuid + "/64");
        JsonObject am = new JsonObject();
        am.add("parse", new JsonArray());
        o.add("allowed_mentions", am);
        HttpRequest req = HttpRequest.newBuilder(URI.create(CFG.webhook))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(o.toString())).build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally(t -> {
            DracosCore.LOGGER.warn("Discord webhook failed: {}", t.toString());
            return null;
        });
    }

    private static String clean(String s) {
        return s.replace("@", "@​").replace("`", "'");
    }

    public static void register() {
        load();
        ServerLifecycleEvents.SERVER_STARTED.register(s -> { if (CFG.status) send(null, null, "🟢 **Server je online!** `dracossmpx.kinetichosting.gg`"); });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { if (CFG.status) send(null, null, "🔴 **Server sa vypína.**"); });
        ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
            if (!CFG.joins) return;
            ServerPlayerEntity p = h.player;
            int online = server.getCurrentPlayerCount() + 1;
            send(null, p.getUuidAsString(), "➕ **" + clean(p.getName().getString()) + "** sa pripojil (" + Math.min(online, server.getMaxPlayerCount()) + "/" + server.getMaxPlayerCount() + ")");
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
            if (!CFG.joins) return;
            send(null, h.player.getUuidAsString(), "➖ **" + clean(h.player.getName().getString()) + "** sa odpojil");
        });
        ServerMessageEvents.CHAT_MESSAGE.register((msg, sender, params) -> {
            if (!CFG.chat) return;
            send(sender.getName().getString(), sender.getUuidAsString(), clean(msg.getSignedContent()));
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity p) {
                if (CFG.deaths) send(null, p.getUuidAsString(), "💀 " + clean(source.getDeathMessage(p).getString()));
            } else if (CFG.bosses && entity instanceof LivingEntity le && BossRewards.isBoss(le)) {
                String who = source.getAttacker() instanceof ServerPlayerEntity k ? clean(k.getName().getString()) : "Niekto";
                send(null, null, "🐉 **" + who + "** porazil bossa **" + clean(le.getName().getString()) + "**!");
            }
        });
    }
}
