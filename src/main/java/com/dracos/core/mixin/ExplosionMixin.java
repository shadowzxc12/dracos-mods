package com.dracos.core.mixin;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.explosion.ExplosionImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Explosions (creepers, TNT, end crystals...) never break blocks at spawn. */
@Mixin(ExplosionImpl.class)
public abstract class ExplosionMixin {
    @Shadow @Final private ServerWorld world;

    @Inject(method = "getBlocksToDestroy", at = @At("RETURN"), require = 0)
    private void dracos$protectSpawn(CallbackInfoReturnable<List<BlockPos>> cir) {
        List<BlockPos> list = cir.getReturnValue();
        if (list == null || list.isEmpty()) return;
        try { list.removeIf(p -> com.dracos.core.Hub.inHubPublic(world, p)); } catch (UnsupportedOperationException ignored) {}
    }
}
