package com.dracos.core;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Real rewards for killing bosses (the content-mod bosses only dropped junk like an iron ingot):
 * money, crate keys, XP and a chance at a random legendary item - for every player who fought nearby.
 */
final class BossRewards {
    private BossRewards() {}

    private static final Set<EntityType<?>> VANILLA = Set.of(EntityType.WITHER, EntityType.ENDER_DRAGON,
            EntityType.WARDEN, EntityType.ELDER_GUARDIAN);
    private static final Random RND = new Random();

    static boolean isBoss(LivingEntity e) {
        if (e instanceof ServerPlayerEntity) return false;
        if (VANILLA.contains(e.getType())) return true;
        if (e.getMaxHealth() < 80) return false;                         // minions / summons
        for (String t : e.getCommandTags()) {
            String l = t.toLowerCase(java.util.Locale.ROOT);
            if (l.contains("boss") && !l.contains("pre_spawned") && !l.contains("projectile")) return true;
        }
        return false;
    }

    static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity.getEntityWorld() instanceof ServerWorld w) || !isBoss(entity)) return;
            List<ServerPlayerEntity> fighters = new ArrayList<>();
            for (ServerPlayerEntity p : w.getPlayers())
                if (!p.isSpectator() && p.squaredDistanceTo(entity) <= 48 * 48) fighters.add(p);
            if (source.getAttacker() instanceof ServerPlayerEntity k && !fighters.contains(k)) fighters.add(k);
            if (fighters.isEmpty()) return;

            double hp = entity.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
            boolean big = hp >= 400 || entity.getType() == EntityType.ENDER_DRAGON || entity.getType() == EntityType.WITHER;
            long money = (long) Math.max(250_000, Math.min(3_000_000, hp * 1500));
            String name = entity.getName().getString();
            for (ServerPlayerEntity p : fighters) {
                Money.add(p, money);
                Crates.giveKey(p, "legendary", big ? 2 : 1);
                Crates.giveKey(p, "rare", 2);
                p.addExperience(big ? 3000 : 1500);
                String loot = "";
                if (RND.nextInt(100) < (big ? 50 : 25)) {
                    List<String> ids = LegendaryItems.IDS;
                    String id = ids.get(RND.nextInt(ids.size()));
                    ItemStack s = LegendaryItems.create(id, p.getRegistryManager());
                    if (s != null && !s.isEmpty()) {
                        p.getInventory().offerOrDrop(s);
                        loot = s.getName().getString();
                    }
                }
                p.sendMessage(Text.literal("☠ Boss defeated! ").formatted(Formatting.GOLD, Formatting.BOLD)
                        .append(Text.literal("+" + Money.fmt(money) + ", " + (big ? 2 : 1) + "x Legendary key, 2x Rare key, XP"
                                + (loot.isEmpty() ? "" : ", " + loot)).formatted(Formatting.YELLOW)), false);
                p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1f, 1f);
            }
            String who = fighters.size() == 1 ? fighters.get(0).getName().getString() : fighters.size() + " players";
            w.getServer().getPlayerManager().broadcast(Text.literal("☠ ").formatted(Formatting.DARK_RED)
                    .append(Text.literal(who).formatted(Formatting.YELLOW, Formatting.BOLD))
                    .append(Text.literal(" defeated ").formatted(Formatting.GRAY))
                    .append(Text.literal(name).formatted(Formatting.RED, Formatting.BOLD))
                    .append(Text.literal("!").formatted(Formatting.GRAY)), false);
        });
    }
}
