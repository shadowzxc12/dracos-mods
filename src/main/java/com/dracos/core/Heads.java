package com.dracos.core;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Rarity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Head hunting + killstreaks. Every player kill drops the victim's head; sell it with /sell.
 * The head is worth more the higher the victim's killstreak and bounty were.
 */
public final class Heads {
    private Heads() {}

    static final String TAG = "dracos_head_value";
    static final long BASE = 10_000, PER_STREAK = 5_000, MAX = 2_000_000;
    private static final long SAME_VICTIM_MS = 10 * 60_000L;

    private static final Map<UUID, Integer> STREAK = new HashMap<>();
    private static final Map<String, Long> LAST_PAIR = new HashMap<>(); // killer|victim -> time
    private static final Map<UUID, Long> PENDING_BOUNTY = new HashMap<>();

    public static int streak(ServerPlayerEntity p) {
        return STREAK.getOrDefault(p.getUuid(), 0);
    }

    /** Value stored in a head (null if it is not a hunted head). */
    public static Long valueOf(ItemStack s) {
        if (s == null || !s.isOf(Items.PLAYER_HEAD)) return null;
        NbtComponent d = s.get(DataComponentTypes.CUSTOM_DATA);
        if (d == null) return null;
        long v = d.copyNbt().getLong(TAG, 0L);
        return v > 0 ? v : null;
    }

    public static void register() {
        // remember the bounty before Bounty pays it out and removes it
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity dead && source.getAttacker() instanceof ServerPlayerEntity)
                PENDING_BOUNTY.put(dead.getUuid(), Bounty.of(dead.getName().getString().toLowerCase()));
            return true;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayerEntity dead)) return;
            int victimStreak = STREAK.getOrDefault(dead.getUuid(), 0);
            STREAK.remove(dead.getUuid());
            long bounty = PENDING_BOUNTY.getOrDefault(dead.getUuid(), 0L);
            PENDING_BOUNTY.remove(dead.getUuid());
            if (!(source.getAttacker() instanceof ServerPlayerEntity killer) || killer == dead) return;
            var server = killer.getEntityWorld().getServer();

            if (victimStreak >= 5)
                server.getPlayerManager().broadcast(Text.literal("⚔ ").formatted(Formatting.RED)
                        .append(Text.literal(killer.getName().getString()).formatted(Formatting.GREEN, Formatting.BOLD))
                        .append(Text.literal(" ended ").formatted(Formatting.GRAY))
                        .append(Text.literal(dead.getName().getString() + "'s " + victimStreak + " killstreak!").formatted(Formatting.RED, Formatting.BOLD)), false);

            // no head farming: same killer + victim only once per 10 minutes
            String pair = killer.getUuidAsString() + "|" + dead.getUuidAsString();
            long now = System.currentTimeMillis();
            Long last = LAST_PAIR.get(pair);
            boolean farm = last != null && now - last < SAME_VICTIM_MS;
            if (!farm) {
                LAST_PAIR.put(pair, now);
                int ks = STREAK.merge(killer.getUuid(), 1, Integer::sum);
                if (ks == 3 || ks == 5 || ks == 10 || ks == 15 || ks == 20 || ks % 25 == 0) announce(killer, ks);
                long value = Math.min(MAX, BASE + victimStreak * PER_STREAK + bounty / 2);
                drop(dead, value, victimStreak);
            }
        });
    }

    private static void announce(ServerPlayerEntity k, int ks) {
        k.getEntityWorld().getServer().getPlayerManager().broadcast(Text.literal("🔥 ").formatted(Formatting.GOLD)
                .append(Text.literal(k.getName().getString()).formatted(Formatting.WHITE, Formatting.BOLD))
                .append(Text.literal(" is on a ").formatted(Formatting.GRAY))
                .append(Text.literal(ks + " KILLSTREAK").formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal("! Their head is worth " + Money.fmt(Math.min(MAX, BASE + ks * PER_STREAK)) + " now.").formatted(Formatting.GRAY)), false);
        ((ServerWorld) k.getEntityWorld()).playSound(null, k.getBlockPos(), SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.PLAYERS, 0.4f, 1.6f);
    }

    private static void drop(ServerPlayerEntity dead, long value, int streak) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(dead.getGameProfile()));
        head.set(DataComponentTypes.ITEM_NAME, Text.literal(dead.getName().getString() + "'s Head").formatted(Formatting.RED, Formatting.BOLD));
        head.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("Hunted head").styled(s -> s.withColor(Formatting.DARK_RED).withItalic(false)),
                Text.literal("Killstreak: " + streak).styled(s -> s.withColor(Formatting.GRAY).withItalic(false)),
                Text.literal("Worth " + Money.fmt(value) + " - /sell it").styled(s -> s.withColor(Formatting.GOLD).withBold(true).withItalic(false)))));
        head.set(DataComponentTypes.RARITY, Rarity.EPIC);
        NbtCompound nbt = new NbtCompound();
        nbt.putLong(TAG, value);
        head.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        ServerWorld w = (ServerWorld) dead.getEntityWorld();
        ItemEntity it = new ItemEntity(w, dead.getX(), dead.getY() + 0.5, dead.getZ(), head);
        it.setToDefaultPickupDelay();
        w.spawnEntity(it);
    }
}
