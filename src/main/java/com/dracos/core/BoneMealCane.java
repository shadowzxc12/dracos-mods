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

    record CaneBehavior(net.minecraft.block.dispenser.DispenserBehavior vanilla) implements net.minecraft.block.dispenser.DispenserBehavior {
        @Override
        public net.minecraft.item.ItemStack dispense(net.minecraft.util.math.BlockPointer pointer, net.minecraft.item.ItemStack stack) {
            BlockPos target = pointer.pos().offset(pointer.state().get(net.minecraft.block.DispenserBlock.FACING));
            // aimed at the cane itself, or at the block it grows on (e.g. dispenser pointing at the sand under it)
            BlockPos cane = grows(pointer.world(), target) ? target : grows(pointer.world(), target.up()) ? target.up() : null;
            if (cane != null && grow(pointer.world(), cane)) {
                stack.decrement(1);
                pointer.world().syncWorldEvent(1000, pointer.pos(), 0);   // dispenser click
                return stack;
            }
            return vanilla != null ? vanilla.dispense(pointer, stack) : stack;
        }
    }

    static boolean grows(ServerWorld w, BlockPos p) {
        var s = w.getBlockState(p);
        return s.isOf(Blocks.SUGAR_CANE) || s.isOf(Blocks.CACTUS);
    }

    static void register() {
        // dispensers with bone meal grow cane/cactus in front of them too; everything else keeps the vanilla behaviour
        // registered once the server is up, so it wraps (and isn't overwritten by) vanilla's bone meal behaviour
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            net.minecraft.block.dispenser.DispenserBehavior vanilla = net.minecraft.block.DispenserBlock.BEHAVIORS.get(Items.BONE_MEAL);
            if (vanilla instanceof CaneBehavior) return;
            net.minecraft.block.DispenserBlock.registerBehavior(Items.BONE_MEAL, new CaneBehavior(vanilla));
            DracosCore.LOGGER.info("[BoneMeal] dispensers grow sugar cane/cactus (wrapping {})", vanilla == null ? "nothing" : vanilla.getClass().getSimpleName());
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
