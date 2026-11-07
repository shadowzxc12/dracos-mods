package com.dracos.core.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Crops grow faster while it rains on them. */
@Mixin(CropBlock.class)
public abstract class CropRainMixin {
    @Shadow public abstract int getMaxAge();
    @Shadow protected abstract int getAge(BlockState state);
    @Shadow public abstract BlockState withAge(int age);

    @Inject(method = "randomTick", at = @At("TAIL"))
    private void dracos$rainGrowth(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        if (!world.isRaining() || !world.hasRain(pos.up()) || random.nextInt(3) != 0) return;
        BlockState now = world.getBlockState(pos);
        if (!now.isOf(state.getBlock())) return;
        int age = getAge(now);
        if (age < getMaxAge()) world.setBlockState(pos, withAge(age + 1), 2);
    }
}
