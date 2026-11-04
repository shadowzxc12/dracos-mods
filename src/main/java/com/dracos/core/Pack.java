package com.dracos.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/** Sends the Dracos texture pack on join (Minehut resets server.properties, so the mod does it). config/dracos/pack.json: {url, sha1} */
public final class Pack {
    private Pack() {}

    private static final UUID ID = UUID.nameUUIDFromBytes("dracos_pack".getBytes());

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try {
                Path f = FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("pack.json");
                if (!Files.exists(f)) return;
                JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                String url = o.has("url") ? o.get("url").getAsString() : "";
                if (url.isEmpty()) return;
                String sha1 = o.has("sha1") ? o.get("sha1").getAsString() : "";
                handler.sendPacket(new ResourcePackSendS2CPacket(ID, url, sha1, false,
                        Optional.of(Text.literal("Dracos SMP textures for the legendary items").formatted(Formatting.GOLD))));
            } catch (Exception e) {
                DracosCore.LOGGER.warn("Could not send resource pack", e);
            }
        });
    }
}
