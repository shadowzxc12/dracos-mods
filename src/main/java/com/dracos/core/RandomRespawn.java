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

    // ---- /rtp for everyone
    // inside the pre-generated area (Chunky did 6000) chunks load from disk almost instantly
    public static final int RTP_MIN = 1_000; // max = the whole world inside the border
    private static final long RTP_COOLDOWN_MS = 20_000;
    private static final java.util.Map<java.util.UUID, Long> LAST_RTP = new java.util.HashMap<>();

    public static void rtp(net.minecraft.server.network.ServerPlayerEntity p) {
        DracosCore.LOGGER.info("[RTP] {} used /rtp", p.getName().getString());
        if (Combat.inCombat(p)) {
            p.sendMessage(Text.literal("You can't /rtp in combat!").formatted(Formatting.RED), false);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_RTP.get(p.getUuid());
        if (last != null && now - last < RTP_COOLDOWN_MS && Ranks.of(p) != Ranks.Rank.OWNER) {
            p.sendMessage(Text.literal("Wait " + ((RTP_COOLDOWN_MS - (now - last)) / 1000 + 1) + "s before using /rtp again.").formatted(Formatting.RED), false);
            return;
        }
        LAST_RTP.put(p.getUuid(), now);
        p.sendMessage(Text.literal("Finding a safe spot...").formatted(Formatting.GRAY), true);
        DracosCore.LOGGER.info("[RTP] {} started /rtp", p.getName().getString());
        attempt(p, p.getEntityWorld().getServer().getOverworld(), 0);
    }

    /** Loads the target chunk in the background (no lag spike), then checks it on the server thread. */
    private static void attempt(net.minecraft.server.network.ServerPlayerEntity p, ServerWorld world, int tries) {
        var server = world.getServer();
        BlockPos spawn = world.getSpawnPoint().getPos();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // plain random spot anywhere inside the world border (every place equally likely)
        var wb = world.getWorldBorder();
        int half = (int) Math.max(RTP_MIN + 1000, Math.min(Integer.MAX_VALUE / 2 - 1, wb.getSize() / 2 - 1_000));
        int cx = (int) wb.getCenterX(), cz = (int) wb.getCenterZ();
        int rx, rz;
        do {
            rx = cx + r.nextInt(-half, half + 1);
            rz = cz + r.nextInt(-half, half + 1);
        } while (Math.abs(rx - spawn.getX()) < RTP_MIN && Math.abs(rz - spawn.getZ()) < RTP_MIN);
        final int x = rx, z = rz;
        if (!world.getWorldBorder().contains(x, z) || badBiome(world, x, z)) {
            if (tries < 40) { attempt(p, world, tries + 1); } else retry(p, world, tries);
            return;
        }
        world.getChunkManager().getChunkFutureSyncOnMainThread(x >> 4, z >> 4, net.minecraft.world.chunk.ChunkStatus.FULL, true)
                .thenAcceptAsync(res -> {
                    if (p.isRemoved() || server.getPlayerManager().getPlayer(p.getUuid()) == null) return;
                    BlockPos pos = check(world, x, z);
                    if (pos == null) { retry(p, world, tries); return; }
                    p.teleport(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Set.of(), p.getYaw(), p.getPitch(), true);
                    DracosCore.LOGGER.info("[RTP] {} -> {} {} {} (try {})", p.getName().getString(), pos.getX(), pos.getY(), pos.getZ(), tries + 1);
                    p.sendMessage(Text.literal("Teleported to " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "!").formatted(Formatting.GREEN), false);
                }, server)
                .exceptionally(ex -> {
                    DracosCore.LOGGER.warn("[RTP] chunk load failed for {}: {}", p.getName().getString(), ex.toString());
                    server.execute(() -> retry(p, world, tries));
                    return null;
                });
    }

    /** Checks the biome from the world generator (no chunk load) - skips oceans and rivers before loading anything. */
    private static boolean badBiome(ServerWorld world, int x, int z) {
        try {
            var cm = world.getChunkManager();
            var b = cm.getChunkGenerator().getBiomeSource().getBiome(x >> 2, 63 >> 2, z >> 2, cm.getNoiseConfig().getMultiNoiseSampler());
            return b.isIn(net.minecraft.registry.tag.BiomeTags.IS_OCEAN) || b.isIn(net.minecraft.registry.tag.BiomeTags.IS_DEEP_OCEAN)
                    || b.isIn(net.minecraft.registry.tag.BiomeTags.IS_RIVER);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void retry(net.minecraft.server.network.ServerPlayerEntity p, ServerWorld world, int tries) {
        if (tries >= 50) {
            LAST_RTP.remove(p.getUuid());
            DracosCore.LOGGER.info("[RTP] {} found no safe spot", p.getName().getString());
            p.sendMessage(Text.literal("No safe spot found, try again.").formatted(Formatting.RED), false);
            return;
        }
        attempt(p, world, tries + 1);
    }

    private static BlockPos pick(ServerWorld world, BlockPos center) {
        return pick(world, center, MIN_RADIUS, MAX_RADIUS);
    }

    private static BlockPos pick(ServerWorld world, BlockPos center, int min, int max) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double angle = r.nextDouble() * Math.PI * 2;
        int dist = r.nextInt(min, max + 1);
        int x = center.getX() + (int) (Math.cos(angle) * dist);
        int z = center.getZ() + (int) (Math.sin(angle) * dist);
        if (!world.getWorldBorder().contains(x, z)) return null;
        return check(world, x, z);
    }

    private static BlockPos check(ServerWorld world, int x, int z) {
        // load (or generate) the chunk first - getTopY returns the bottom of the world for unloaded chunks
        var chunk = world.getChunk(x >> 4, z >> 4);
        int y = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
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
