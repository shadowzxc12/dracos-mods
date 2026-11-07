package com.dracos.core.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No crop trampling: jumping on farmland (players or mobs) never turns it back to dirt. */
@Mixin(FarmlandBlock.class)
public abstract class FarmlandMixin {
    @Inject(method = "setToDirt", at = @At("HEAD"), cancellable = true, require = 1)
    private static void dracos$noTrample(Entity entity, BlockState state, World world, BlockPos pos, CallbackInfo ci) {
        if (entity != null) ci.cancel();
    }
}
