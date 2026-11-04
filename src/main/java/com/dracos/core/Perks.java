package com.dracos.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;

/** Rank perks: /daily, /kit, /craft, /ec, /glow. */
public final class Perks {
    private Perks() {}

    private static JsonObject data;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("cooldowns.json");
    }

    private static synchronized JsonObject data() {
        if (data == null) {
            try { data = Files.exists(file()) ? JsonParser.parseString(Files.readString(file())).getAsJsonObject() : new JsonObject(); }
            catch (Exception e) { data = new JsonObject(); }
        }
        return data;
    }

    private static synchronized void save() {
        try { Files.createDirectories(file().getParent()); Files.writeString(file(), data().toString()); }
        catch (Exception e) { DracosCore.LOGGER.warn("cooldowns.json save failed", e); }
    }

    /** Returns ms left on a 24h cooldown, or 0 and starts it. */
    private static long useDaily(ServerPlayerEntity p, String key) {
        String k = p.getUuidAsString() + ":" + key;
        long now = System.currentTimeMillis();
        long last = data().has(k) ? data().get(k).getAsLong() : 0;
        long left = last + 86_400_000L - now;
        if (left > 0) return left;
        data().addProperty(k, now);
        save();
        return 0;
    }

    private static String fmt(long ms) {
        long m = ms / 60000;
        return (m / 60) + "h " + (m % 60) + "m";
    }

    private static boolean need(ServerPlayerEntity p, Ranks.Rank r, String what) {
        if (Ranks.of(p).atLeast(r)) return true;
        p.sendMessage(Text.literal(what + " is a " + r.title + "+ perk. Get it with /store").formatted(Formatting.RED), false);
        return false;
    }

    public static void daily(ServerPlayerEntity p) {
        long left = useDaily(p, "daily");
        if (left > 0) {
            p.sendMessage(Text.literal("Next daily reward in " + fmt(left)).formatted(Formatting.RED), false);
            return;
        }
        long amount = switch (Ranks.of(p)) {
            case MEMBER -> 10_000; case VIP -> 25_000; case MVP -> 50_000; case LEGEND -> 100_000; case DRACO -> 250_000;
        };
        Money.add(p, amount);
        p.sendMessage(Text.literal("Daily reward: ").formatted(Formatting.GREEN)
                .append(Text.literal(Money.fmt(amount)).formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal(" (better rank = bigger reward)").formatted(Formatting.GRAY)), false);
        p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.8f, 1.4f);
    }

    public static void kit(ServerPlayerEntity p) {
        Ranks.Rank r = Ranks.of(p);
        if (!need(p, Ranks.Rank.VIP, "/kit")) return;
        long left = useDaily(p, "kit");
        if (left > 0) {
            p.sendMessage(Text.literal("Next kit in " + fmt(left)).formatted(Formatting.RED), false);
            return;
        }
        give(p, Items.COOKED_BEEF, 32);
        give(p, Items.GOLDEN_APPLE, r == Ranks.Rank.VIP ? 4 : 8);
        give(p, Items.EXPERIENCE_BOTTLE, 32);
        give(p, Items.DIAMOND, r == Ranks.Rank.VIP ? 8 : 16);
        if (r.atLeast(Ranks.Rank.MVP)) { give(p, Items.ENDER_PEARL, 16); give(p, Items.TOTEM_OF_UNDYING, 1); }
        if (r.atLeast(Ranks.Rank.LEGEND)) { give(p, Items.NETHERITE_INGOT, 1); give(p, Items.ENCHANTED_GOLDEN_APPLE, 1); }
        if (r.atLeast(Ranks.Rank.DRACO)) { give(p, Items.NETHERITE_INGOT, 2); give(p, Items.TOTEM_OF_UNDYING, 2); give(p, Items.SHULKER_BOX, 1); }
        p.sendMessage(Text.literal("You received the " + r.title + " kit!").formatted(r.color, Formatting.BOLD), false);
    }

    private static void give(ServerPlayerEntity p, Item item, int n) {
        while (n > 0) { int c = Math.min(n, item.getMaxCount()); p.getInventory().offerOrDrop(new ItemStack(item, c)); n -= c; }
    }

    public static void craft(ServerPlayerEntity p) {
        if (!need(p, Ranks.Rank.MVP, "/craft")) return;
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, pl) ->
                new CraftingScreenHandler(id, inv, ScreenHandlerContext.create(pl.getEntityWorld(), pl.getBlockPos())) {
                    @Override public boolean canUse(net.minecraft.entity.player.PlayerEntity player) { return true; }
                }, Text.literal("Crafting")));
    }

    public static void enderChest(ServerPlayerEntity p) {
        if (!need(p, Ranks.Rank.LEGEND, "/ec")) return;
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, pl) ->
                GenericContainerScreenHandler.createGeneric9x3(id, inv, pl.getEnderChestInventory()), Text.literal("Ender Chest")));
    }

    public static void glow(ServerPlayerEntity p) {
        if (!need(p, Ranks.Rank.DRACO, "/glow")) return;
        if (p.hasStatusEffect(StatusEffects.GLOWING)) {
            p.removeStatusEffect(StatusEffects.GLOWING);
            p.sendMessage(Text.literal("Glow off").formatted(Formatting.GRAY), false);
        } else {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, -1, 0, false, false, true));
            p.sendMessage(Text.literal("Glow on").formatted(Formatting.LIGHT_PURPLE), false);
        }
    }
}
