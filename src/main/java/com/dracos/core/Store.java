package com.dracos.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** /store - clickable store link + rank prices. URL is set in config/dracos/store.json ("url"). */
public final class Store {
    private Store() {}

    private static final String DEFAULT_URL = "https://shadowzxc12.github.io/dracos-store/";

    static String url() {
        Path f = FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("store.json");
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                JsonObject o = new JsonObject();
                o.addProperty("url", DEFAULT_URL);
                Files.writeString(f, o.toString());
                return DEFAULT_URL;
            }
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            return o.has("url") ? o.get("url").getAsString() : DEFAULT_URL;
        } catch (Exception e) {
            DracosCore.LOGGER.warn("store.json unreadable", e);
            return DEFAULT_URL;
        }
    }

    public static void show(ServerPlayerEntity p) {
        String url = url();
        p.sendMessage(Text.literal("━━━━━━ Dracos SMP Store ━━━━━━").formatted(Formatting.GOLD, Formatting.BOLD), false);
        price(p, Ranks.Rank.VIP, "€2.99 / month");
        price(p, Ranks.Rank.MVP, "€4.99 / month");
        price(p, Ranks.Rank.LEGEND, "€7.99 / month");
        price(p, Ranks.Rank.DRACO, "€11.99 / month");
        Text link = Text.literal("➤ CLICK HERE TO OPEN THE STORE").styled(s -> {
            s = s.withColor(Formatting.GREEN).withBold(true).withUnderline(true)
                    .withHoverEvent(new HoverEvent.ShowText(Text.literal(url).formatted(Formatting.GRAY)));
            try { s = s.withClickEvent(new ClickEvent.OpenUrl(URI.create(url))); } catch (Exception ignored) {}
            return s;
        });
        p.sendMessage(link, false);
        p.sendMessage(Text.literal("Perks of every rank: /ranks").formatted(Formatting.GRAY), false);
    }

    private static void price(ServerPlayerEntity p, Ranks.Rank r, String eur) {
        p.sendMessage(Text.empty().append(r.prefix()).append(Text.literal(eur).formatted(Formatting.WHITE, Formatting.BOLD)), false);
    }
}
