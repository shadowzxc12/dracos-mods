package com.dracos.core;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Sneak + right-click a mob with an empty hand to carry it on your head. Sneak + right-click a block (or the mob) to put it down. */
public final class MobCarry {
    private MobCarry() {}

    private static final Set<EntityType<?>> BLOCKED = Set.of(EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN,
            EntityType.ELDER_GUARDIAN, EntityType.GHAST, EntityType.HAPPY_GHAST, EntityType.RAVAGER);
    private static final Map<UUID, Boolean> AI_BEFORE = new HashMap<>();

    static MobEntity carried(ServerPlayerEntity p) {
        for (Entity e : p.getPassengerList()) if (e instanceof MobEntity m) return m;
        return null;
    }

    private static void sync(ServerPlayerEntity p) {
        p.networkHandler.sendPacket(new EntityPassengersSetS2CPacket(p));
    }

    static void drop(ServerPlayerEntity p, Vec3d at) {
        MobEntity m = carried(p);
        if (m == null) return;
        m.stopRiding();
        Boolean ai = AI_BEFORE.remove(m.getUuid());
        m.setAiDisabled(ai != null && ai);
        if (at != null) m.refreshPositionAndAngles(at.x, at.y, at.z, p.getYaw() + 180, 0);
        sync(p);
        p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.7f, 0.6f);
    }

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity p) || hand != Hand.MAIN_HAND || !p.isSneaking()
                    || !p.getMainHandStack().isEmpty()) return ActionResult.PASS;
            MobEntity have = carried(p);
            if (have != null) {
                if (have == entity) {
                    drop(p, new Vec3d(p.getX(), p.getY(), p.getZ()).add(p.getRotationVector().multiply(1.2, 0, 1.2)));
                    return ActionResult.SUCCESS;
                }
                p.sendMessage(Text.literal("You are already carrying a mob! Sneak + right-click a block to put it down.").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }
            if (!(entity instanceof MobEntity m) || BLOCKED.contains(m.getType()) || m.hasPassengers() || m.hasVehicle()
                    || m.getWidth() > 2.0f || m.getHeight() > 3.0f) return ActionResult.PASS;
            if (Combat.inCombat(p)) {
                p.sendMessage(Text.literal("You can't pick up mobs in combat!").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }
            AI_BEFORE.put(m.getUuid(), m.isAiDisabled());
            m.setAiDisabled(true);
            if (!m.startRiding(p, true, false)) {
                m.setAiDisabled(AI_BEFORE.remove(m.getUuid()));
                return ActionResult.FAIL;
            }
            sync(p);
            p.sendMessage(Text.literal("Picked up! Sneak + right-click a block to put it down.").formatted(Formatting.GREEN), true);
            world.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.7f, 1.4f);
            return ActionResult.SUCCESS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity p) || hand != Hand.MAIN_HAND || !p.isSneaking()
                    || !p.getMainHandStack().isEmpty() || carried(p) == null) return ActionResult.PASS;
            BlockPos on = hit.getBlockPos().offset(hit.getSide());
            drop(p, Vec3d.ofBottomCenter(on));
            return ActionResult.SUCCESS;
        });

        ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> drop(h.player, null));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity p) drop(p, null);
        });
    }
}
