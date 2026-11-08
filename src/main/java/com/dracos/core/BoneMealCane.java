package com.dracos.core;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

/** Bone meal grows sugar cane (and cactus) by one block, up to the vanilla height of 3. */
final class BoneMealCane {
    private BoneMealCane() {}

    static final int MAX = 3;

    static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(world instanceof ServerWorld w)) return ActionResult.PASS;
            var stack = player.getStackInHand(hand);
            if (!stack.isOf(Items.BONE_MEAL)) return ActionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            var block = w.getBlockState(pos).getBlock();
            if (block != Blocks.SUGAR_CANE && block != Blocks.CACTUS) return ActionResult.PASS;
            BlockPos bottom = pos;
            while (w.getBlockState(bottom.down()).isOf(block)) bottom = bottom.down();
            BlockPos top = pos;
            while (w.getBlockState(top.up()).isOf(block)) top = top.up();
            int height = top.getY() - bottom.getY() + 1;
            BlockPos grow = top.up();
            if (height >= MAX || !w.getBlockState(grow).isAir() || !block.getDefaultState().canPlaceAt(w, grow))
                return ActionResult.PASS;
            if (PlayerWarps.protector(player, w, pos.getX() + 0.5, pos.getZ() + 0.5) != null) return ActionResult.PASS;
            w.setBlockState(grow, block.getDefaultState());
            w.syncWorldEvent(1505, pos, 15);                   // bone meal particles
            w.playSound(null, pos, SoundEvents.ITEM_BONE_MEAL_USE, SoundCategory.BLOCKS, 1f, 1f);
            if (!player.isCreative()) stack.decrement(1);
            return ActionResult.SUCCESS;
        });
    }
}
