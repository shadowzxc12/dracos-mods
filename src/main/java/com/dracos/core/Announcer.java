package com.dracos.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** Chat ads every few minutes (Discord, store, tips). config/dracos/announcer.json: {"discord": "https://discord.gg/...", "minutes": 8} */
public final class Announcer {
    private Announcer() {}

    private static String discord = "";
    private static int minutes = 8;
    private static int next = 0;

    public static void load() {
        Path f = FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("announcer.json");
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, "{\"discord\": \"\", \"minutes\": 8}");
            }
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            discord = o.has("discord") ? o.get("discord").getAsString() : "";
            minutes = o.has("minutes") ? Math.max(1, o.get("minutes").getAsInt()) : 8;
        } catch (Exception e) {
            DracosCore.LOGGER.warn("announcer.json unreadable", e);
        }
    }

    private static Text link(String label, String url, Formatting color) {
        return Text.literal(label).styled(s -> {
            s = s.withColor(color).withBold(true).withUnderline(true)
                    .withHoverEvent(new HoverEvent.ShowText(Text.literal(url).formatted(Formatting.GRAY)));
            try { s = s.withClickEvent(new ClickEvent.OpenUrl(URI.create(url))); } catch (Exception ignored) {}
            return s;
        });
    }

    private static final Text LINE = Text.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━").formatted(Formatting.DARK_GRAY);

    public static Text discordMessage() {
        MutableText t = Text.empty().append(Text.literal("💬 ").formatted(Formatting.BLUE))
                .append(Text.literal("Join our Discord! ").formatted(Formatting.AQUA, Formatting.BOLD))
                .append(Text.literal("Giveaways, updates, support & friends. ").formatted(Formatting.GRAY));
        if (!discord.isEmpty()) t.append(link("[CLICK TO JOIN]", discord, Formatting.BLUE));
        else t.append(Text.literal("Type /discord").formatted(Formatting.BLUE, Formatting.BOLD));
        return t;
    }

    private static Text message(int i) {
        return switch (i % 4) {
            case 0 -> discordMessage();
            case 1 -> Text.empty().append(Text.literal("💎 ").formatted(Formatting.LIGHT_PURPLE))
                    .append(Text.literal("Support the server! ").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD))
                    .append(Text.literal("Ranks from €2.99/month with more homes, kits and sell bonus. ").formatted(Formatting.GRAY))
                    .append(link("[/store]", Store.url(), Formatting.GOLD));
            case 2 -> Text.empty().append(Text.literal("🎁 ").formatted(Formatting.GREEN))
                    .append(Text.literal("Free money every day: ").formatted(Formatting.GREEN, Formatting.BOLD))
                    .append(Text.literal("/daily").formatted(Formatting.YELLOW))
                    .append(Text.literal(", a free kit every hour: ").formatted(Formatting.GRAY))
                    .append(Text.literal("/kit").formatted(Formatting.YELLOW))
                    .append(Text.literal(", backpack: ").formatted(Formatting.GRAY))
                    .append(Text.literal("/backpack").formatted(Formatting.YELLOW));
            default -> Text.empty().append(Text.literal("⚔ ").formatted(Formatting.GOLD))
                    .append(Text.literal(LegendaryItems.IDS.size() + " legendary items ").formatted(Formatting.GOLD, Formatting.BOLD))
                    .append(Text.literal("are waiting for you in ").formatted(Formatting.GRAY))
                    .append(Text.literal("/legendary").formatted(Formatting.YELLOW));
        };
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % (20 * 60 * minutes) != 0 || server.getTicks() == 0) return;
            if (server.getPlayerManager().getPlayerList().isEmpty()) return;
            Text msg = message(next++);
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                p.sendMessage(LINE, false);
                p.sendMessage(msg, false);
                p.sendMessage(LINE, false);
            }
        });
    }
}
