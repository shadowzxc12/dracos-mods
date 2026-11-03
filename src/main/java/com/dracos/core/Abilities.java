package com.dracos.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Schopnosti legendarnych veci. */
public final class Abilities {
    private Abilities() {}

    private static boolean breaking = false; // ochrana pred rekurziou

    public static void register() {
        // ---- Shard Pickaxe 3x3 + Lava Axe tree felling
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (breaking || !(player instanceof ServerPlayerEntity sp)) return;
            ItemStack tool = sp.getMainHandStack();
            String id = LegendaryItems.idOf(tool);
            if (id == null) return;
            try {
                breaking = true;
                if (id.equals(LegendaryItems.SHARD_PICKAXE) && !sp.isSneaking()) {
                    mine3x3(sp, world, pos);
                } else if (id.equals(LegendaryItems.LAVA_AXE) && state.isIn(BlockTags.LOGS) && !sp.isSneaking()) {
                    fellTree(sp, world, pos);
                }
            } finally {
                breaking = false;
            }
        });

        // ---- Lava Axe zapali ciel
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient() && entity instanceof LivingEntity living
                    && LegendaryItems.is(player.getMainHandStack(), LegendaryItems.LAVA_AXE)) {
                living.setOnFireFor(5f);
            }
            return ActionResult.PASS;
        });

        // ---- Dragon Blade dash
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (!LegendaryItems.is(stack, LegendaryItems.DRAGON_BLADE)) return ActionResult.PASS;
            if (world.isClient()) return ActionResult.SUCCESS;
            if (player.getItemCooldownManager().isCoolingDown(stack)) return ActionResult.FAIL;

            Vec3d look = player.getRotationVector();
            player.addVelocity(look.x * 1.8, 0.35, look.z * 1.8);
            if (player instanceof ServerPlayerEntity sp) sp.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(sp));
            player.fallDistance = 0;
            player.getItemCooldownManager().set(stack, 60);

            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_ENDER_DRAGON_FLAP,
                    SoundCategory.PLAYERS, 1.0f, 1.3f);
            if (world instanceof ServerWorld sw) {
                sw.spawnParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 1, player.getZ(),
                        40, 0.4, 0.6, 0.4, 0.3);
            }
            return ActionResult.SUCCESS;
        });

        // ---- Void Bow - sipy bez gravitacie a +50% damage
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PersistentProjectileEntity arrow
                    && arrow.getOwner() instanceof PlayerEntity shooter
                    && arrow.age < 2
                    && (LegendaryItems.is(shooter.getMainHandStack(), LegendaryItems.VOID_BOW)
                    || LegendaryItems.is(shooter.getOffHandStack(), LegendaryItems.VOID_BOW))) {
                arrow.setNoGravity(true);
                arrow.setVelocity(arrow.getVelocity().multiply(1.5));
            }
        });

        // ---- Draco Set bonus
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 20 != 0) return;
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (LegendaryItems.is(p.getEquippedStack(EquipmentSlot.HEAD), LegendaryItems.DRACO_HELMET)
                        && LegendaryItems.is(p.getEquippedStack(EquipmentSlot.CHEST), LegendaryItems.DRACO_CHESTPLATE)
                        && LegendaryItems.is(p.getEquippedStack(EquipmentSlot.LEGS), LegendaryItems.DRACO_LEGGINGS)
                        && LegendaryItems.is(p.getEquippedStack(EquipmentSlot.FEET), LegendaryItems.DRACO_BOOTS)) {
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 60, 0, true, false, true));
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 60, 0, true, false, true));
                }
            }
        });
    }

    private static Direction hitFace(ServerPlayerEntity player) {
        HitResult hit = player.raycast(6.0, 0f, false);
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) return bhr.getSide();
        return Direction.UP;
    }

    private static void mine3x3(ServerPlayerEntity player, World world, BlockPos center) {
        Direction face = hitFace(player);
        Direction.Axis axis = face.getAxis();
        ItemStack tool = player.getMainHandStack();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                BlockPos p = switch (axis) {
                    case Y -> center.add(a, 0, b);
                    case X -> center.add(0, a, b);
                    case Z -> center.add(a, b, 0);
                };
                BlockState s = world.getBlockState(p);
                if (s.isAir() || s.getHardness(world, p) < 0 || !tool.isSuitableFor(s)) continue;
                player.interactionManager.tryBreakBlock(p);
                if (player.getMainHandStack().isEmpty()) return;
            }
        }
    }

    private static void fellTree(ServerPlayerEntity player, World world, BlockPos start) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        int broken = 0;
        while (!queue.isEmpty() && broken < 128) {
            BlockPos cur = queue.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = 0; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = cur.add(dx, dy, dz);
                        if (!seen.add(n)) continue;
                        if (!world.getBlockState(n).isIn(BlockTags.LOGS)) continue;
                        player.interactionManager.tryBreakBlock(n);
                        broken++;
                        queue.add(n);
                        if (player.getMainHandStack().isEmpty()) return;
                    }
        }
    }
}
