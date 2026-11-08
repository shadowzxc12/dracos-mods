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

    /** Grows the cane/cactus column at pos by one block. */
    static boolean grow(ServerWorld w, BlockPos pos) {
        var block = w.getBlockState(pos).getBlock();
        if (block != Blocks.SUGAR_CANE && block != Blocks.CACTUS) return false;
        BlockPos bottom = pos;
        while (w.getBlockState(bottom.down()).isOf(block)) bottom = bottom.down();
        BlockPos top = pos;
        while (w.getBlockState(top.up()).isOf(block)) top = top.up();
        BlockPos up = top.up();
        if (top.getY() - bottom.getY() + 1 >= MAX || !w.getBlockState(up).isAir() || !block.getDefaultState().canPlaceAt(w, up))
            return false;
        w.setBlockState(up, block.getDefaultState());
        w.syncWorldEvent(1505, pos, 15);                       // bone meal particles
        w.playSound(null, pos, SoundEvents.ITEM_BONE_MEAL_USE, SoundCategory.BLOCKS, 1f, 1f);
        return true;
    }

    static void register() {
        // dispensers with bone meal grow cane/cactus in front of them too; everything else keeps the vanilla behaviour
        net.minecraft.block.dispenser.DispenserBehavior vanilla = net.minecraft.block.DispenserBlock.BEHAVIORS.get(Items.BONE_MEAL);
        net.minecraft.block.DispenserBlock.registerBehavior(Items.BONE_MEAL, (pointer, stack) -> {
            BlockPos target = pointer.pos().offset(pointer.state().get(net.minecraft.block.DispenserBlock.FACING));
            if (grow(pointer.world(), target)) {
                stack.decrement(1);
                pointer.world().syncWorldEvent(1000, pointer.pos(), 0);   // dispenser click
                return stack;
            }
            return vanilla != null ? vanilla.dispense(pointer, stack) : stack;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(world instanceof ServerWorld w)) return ActionResult.PASS;
            var stack = player.getStackInHand(hand);
            if (!stack.isOf(Items.BONE_MEAL)) return ActionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            var block = w.getBlockState(pos).getBlock();
            if (block != Blocks.SUGAR_CANE && block != Blocks.CACTUS) return ActionResult.PASS;
            if (PlayerWarps.protector(player, w, pos.getX() + 0.5, pos.getZ() + 0.5) != null) return ActionResult.PASS;
            if (!grow(w, pos)) return ActionResult.PASS;
            if (!player.isCreative()) stack.decrement(1);
            return ActionResult.SUCCESS;
        });
    }
}
