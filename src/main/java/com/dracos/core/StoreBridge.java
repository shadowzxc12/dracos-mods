package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Connects the web store (dracos-store, PHP) to our own rank system.
 * Paid web order -> activation code DR-XXXX-... -> player types /rank CODE (or just /rank) -> rank + time from the web.
 * Config: config/dracos/store-bridge.json {storeUrl, apiKey}. The apiKey is private: the owner pastes it there himself.
 */
public final class StoreBridge {
    private StoreBridge() {}

    static final String CODE = "DR-(?:[0-9A-HJKMNP-TV-Z]{4}-){4}[0-9A-HJKMNP-TV-Z]{4}";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "dracos-store-bridge");
        t.setDaemon(true);
        return t;
    });
    private static final Set<UUID> BUSY = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    static String storeUrl = "";
    static String apiKey = "";
    static boolean syncOnJoin = true;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("store-bridge.json");
    }

    public static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                JsonObject o = new JsonObject();
                o.addProperty("storeUrl", "");
                o.addProperty("apiKey", "");
                o.addProperty("syncOnJoin", true);
                Files.writeString(f, GSON.toJson(o));
            }
            JsonObject o = GSON.fromJson(Files.readString(f), JsonObject.class);
            storeUrl = o.has("storeUrl") ? o.get("storeUrl").getAsString().trim() : "";
            apiKey = o.has("apiKey") ? o.get("apiKey").getAsString().trim() : "";
            syncOnJoin = !o.has("syncOnJoin") || o.get("syncOnJoin").getAsBoolean();
            if (!storeUrl.isEmpty()) storeUrl = storeUrl.replaceAll("/+$", "") + "/";
            if (ready()) DracosCore.LOGGER.info("Store bridge connected to {}", storeUrl);
            else DracosCore.LOGGER.info("Store bridge off (fill storeUrl + apiKey in config/dracos/store-bridge.json)");
        } catch (Exception e) {
            DracosCore.LOGGER.warn("Could not read store-bridge.json: {}", e.getClass().getSimpleName());
        }
    }

    static boolean ready() {
        return storeUrl.startsWith("https://") && apiKey.matches("[a-f0-9]{64}");
    }

    public static void register() {
        // a pasted code alone in chat is redeemed and NOT shown to other players
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, player, params) -> {
            String txt = message.getSignedContent().trim().toUpperCase(Locale.ROOT);
            if (txt.matches(CODE)) {
                start(player, txt);
                return false;
            }
            return true;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (syncOnJoin && ready()) start(handler.player, null, true);
        });
    }

    public static void start(ServerPlayerEntity p, String code) { start(p, code, false); }

    private static void start(ServerPlayerEntity p, String code, boolean quiet) {
        MinecraftServer server = p.getEntityWorld().getServer();
        UUID uuid = p.getUuid();
        String name = p.getName().getString();
        if (!ready()) {
            if (!quiet) tell(server, uuid, "Rank activation is not connected yet. Your payment is safe - contact the owner on Discord.", Formatting.RED);
            return;
        }
        if (code != null && !code.matches(CODE)) {
            tell(server, uuid, "That code looks wrong. Copy the full /rank DR-... command from your order page.", Formatting.RED);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST.get(uuid);
        if (!quiet && last != null && now - last < 5000) {
            tell(server, uuid, "Please wait a few seconds and try again.", Formatting.RED);
            return;
        }
        if (!BUSY.add(uuid)) return;
        LAST.put(uuid, now);
        if (!quiet) tell(server, uuid, "Checking your store order...", Formatting.GRAY);
        POOL.execute(() -> {
            try {
                if (code != null) {
                    JsonObject res = post("redeem", Map.of("playerUuid", uuid.toString(), "username", name, "code", code));
                    JsonObject grant = res.getAsJsonObject("grant");
                    boolean was = grant.has("delivered") && grant.get("delivered").getAsBoolean();
                    apply(server, uuid, name, grant, was);
                } else {
                    JsonObject res = post("sync", Map.of("playerUuid", uuid.toString(), "username", name));
                    JsonArray grants = res.getAsJsonArray("grants");
                    if (grants == null || grants.isEmpty()) {
                        if (!quiet) tell(server, uuid, "No paid order is waiting for " + name + ". Buy a rank with /store.", Formatting.YELLOW);
                    } else for (JsonElement g : grants) apply(server, uuid, name, g.getAsJsonObject(), false);
                }
            } catch (BridgeError e) {
                if (!quiet) tell(server, uuid, e.getMessage(), Formatting.RED);
            } catch (Exception e) {
                if (!quiet) tell(server, uuid, "Could not reach the store right now. Try /rank again later - your order stays saved.", Formatting.RED);
                DracosCore.LOGGER.warn("Store bridge request failed for {} ({})", uuid, e.getClass().getSimpleName());
            } finally {
                BUSY.remove(uuid);
            }
        });
    }

    /** Puts the rank on the player (on the server thread), then confirms delivery to the web. */
    private static void apply(MinecraftServer server, UUID uuid, String name, JsonObject g, boolean alreadyDelivered) throws Exception {
        String grantId = g.get("grantId").getAsString();
        String rankId = g.get("rankId").getAsString();
        long until = g.get("expiresAt").getAsLong() * 1000L;
        if (!grantId.matches("[a-f0-9]{32}") || !uuid.toString().equalsIgnoreCase(g.get("playerUuid").getAsString()))
            throw new BridgeError("This code belongs to another player.");
        Ranks.Rank rank = switch (rankId) {
            case "vip" -> Ranks.Rank.VIP;
            case "mvp" -> Ranks.Rank.MVP;
            case "legend" -> Ranks.Rank.LEGEND;
            case "draco" -> Ranks.Rank.DRACO;
            default -> throw new BridgeError("Unknown rank in order. Contact the owner.");
        };
        if (until <= System.currentTimeMillis()) {
            if (alreadyDelivered) throw new BridgeError("This code was already used and its time has ended. Buy a new one with /store.");
            throw new BridgeError("This code has expired. Contact the owner on Discord.");
        }
        Boolean ok = server.submit(() -> Ranks.setUntil(server, name, rank, until)).get();
        if (!ok) throw new BridgeError("You already have a higher rank, so this " + rank.title
                + " code was not used. It stays saved - ask the owner on Discord.");
        JsonObject ack = post("ack", Map.of("playerUuid", uuid.toString(), "grantId", grantId));
        if (!ack.has("delivered") || !ack.get("delivered").getAsBoolean()) throw new Exception("not acknowledged");
        long days = Math.max(1, (until - System.currentTimeMillis() + 86_399_999L) / 86_400_000L);
        tell(server, uuid, alreadyDelivered ? "This code is already active: " + rank.title + " (" + days + " days left)."
                : "✔ " + rank.title + " rank activated! " + days + " days left. Thank you for supporting Dracos SMP!", Formatting.GREEN);
        DracosCore.LOGGER.info("Store: delivered {} to {} until {}", rankId, name, until);
    }

    private static JsonObject post(String action, Map<String, String> body) throws Exception {
        URI uri = URI.create(storeUrl).resolve("index.php?route=api/game/" + action);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 422) throw new BridgeError("No paid order with this code exists for your username.");
        if (res.statusCode() != 200) throw new Exception("HTTP " + res.statusCode());
        return GSON.fromJson(res.body(), JsonObject.class);
    }

    private static void tell(MinecraftServer server, UUID uuid, String msg, Formatting color) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) p.sendMessage(Text.literal("[Store] ").formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal(msg).formatted(color)), false);
        });
    }

    static final class BridgeError extends Exception {
        BridgeError(String m) { super(m); }
    }
}
