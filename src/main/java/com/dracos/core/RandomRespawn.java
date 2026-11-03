package com.dracos.core;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Players without a bed / respawn anchor respawn at a random safe spot instead of world spawn. */
public final class RandomRespawn {
    private RandomRespawn() {}

    public static final int MIN_RADIUS = 300;
    public static final int MAX_RADIUS = 3000;

    public static void register() {
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (alive) return;                       // end portal return, not a death
            if (newPlayer.getRespawn() != null) return; // has bed / anchor -> vanilla
            ServerWorld world = newPlayer.getEntityWorld().getServer().getOverworld();
            BlockPos spawn = world.getSpawnPoint().getPos();
            for (int i = 0; i < 20; i++) {
                BlockPos pos = pick(world, spawn);
                if (pos != null) {
                    newPlayer.teleport(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                            Set.of(), newPlayer.getYaw(), newPlayer.getPitch(), true);
                    newPlayer.sendMessage(Text.literal("You respawned at a random location.").formatted(Formatting.GRAY), false);
                    return;
                }
            }
        });
    }

    private static BlockPos pick(ServerWorld world, BlockPos center) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double angle = r.nextDouble() * Math.PI * 2;
        int dist = r.nextInt(MIN_RADIUS, MAX_RADIUS + 1);
        int x = center.getX() + (int) (Math.cos(angle) * dist);
        int z = center.getZ() + (int) (Math.sin(angle) * dist);
        if (!world.getWorldBorder().contains(x, z)) return null;
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= world.getBottomY() + 1) return null;
        BlockPos feet = new BlockPos(x, y, z);
        BlockState ground = world.getBlockState(feet.down());
        if (!ground.getFluidState().isEmpty() || !ground.isSolidBlock(world, feet.down())) return null;
        if (!world.getBlockState(feet).isAir() || !world.getBlockState(feet.up()).isAir()) return null;
        return feet;
    }

    @SuppressWarnings("unused")
    private static boolean isOverworld(World w) { return w.getRegistryKey() == World.OVERWORLD; }
}
