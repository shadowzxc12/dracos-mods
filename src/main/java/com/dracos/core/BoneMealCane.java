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
            BlockPos cane = caneFor(pointer.world(), pointer.pos());
            if (cane != null) {
                track(pointer.world(), pointer.pos());
                if (work(pointer.world(), cane)) {
                    stack.decrement(1);
                    pointer.world().syncWorldEvent(1000, pointer.pos(), 0);   // dispenser click
                }
                return stack;
            }
            return vanilla != null ? vanilla.dispense(pointer, stack) : stack;
        }
    }

    /** The cane/cactus this dispenser feeds: in front of it, or standing on the block in front of it. */
    static BlockPos caneFor(ServerWorld w, BlockPos dispenser) {
        var st = w.getBlockState(dispenser);
        if (!st.isOf(Blocks.DISPENSER)) return null;
        BlockPos t = dispenser.offset(st.get(net.minecraft.block.DispenserBlock.FACING));
        return grows(w, t) ? t : grows(w, t.up()) ? t.up() : null;
    }

    /** One farm step: grow the plant by a block, or - once it is fully grown - cut it (drops fall as items).
     *  Returns true when bone meal was used. */
    static boolean work(ServerWorld w, BlockPos cane) {
        var block = w.getBlockState(cane).getBlock();
        BlockPos bottom = cane;
        while (w.getBlockState(bottom.down()).isOf(block)) bottom = bottom.down();
        BlockPos top = bottom;
        while (w.getBlockState(top.up()).isOf(block)) top = top.up();
        if (top.getY() - bottom.getY() + 1 >= MAX) {
            w.breakBlock(bottom.up(), true);                     // harvest everything above the base block
            return false;
        }
        return grow(w, cane);
    }

    // ---- dispensers that keep working while they stay powered (torch, lever...) - vanilla only fires on a new signal
    static final java.util.Set<String> FARMS = new java.util.HashSet<>();
    static boolean farmsDirty;

    static java.nio.file.Path farmsFile() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("cane_dispensers.json");
    }

    static void track(ServerWorld w, BlockPos p) {
        if (FARMS.add(w.getRegistryKey().getValue() + "|" + p.asLong())) farmsDirty = true;
    }

    static void tickFarms(net.minecraft.server.MinecraftServer server) {
        if (FARMS.isEmpty()) return;
        var it = FARMS.iterator();
        while (it.hasNext()) {
            String k = it.next();
            int bar = k.lastIndexOf('|');
            ServerWorld w = server.getWorld(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD,
                    net.minecraft.util.Identifier.of(k.substring(0, bar))));
            BlockPos p = BlockPos.fromLong(Long.parseLong(k.substring(bar + 1)));
            if (w == null) { it.remove(); farmsDirty = true; continue; }
            if (!w.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) continue;
            if (!w.getBlockState(p).isOf(Blocks.DISPENSER)) { it.remove(); farmsDirty = true; continue; }
            if (!w.isReceivingRedstonePower(p)) continue;
            BlockPos cane = caneFor(w, p);
            if (cane == null || !(w.getBlockEntity(p) instanceof net.minecraft.block.entity.DispenserBlockEntity be)) continue;
            int slot = -1;
            for (int i = 0; i < be.size(); i++) if (be.getStack(i).isOf(Items.BONE_MEAL)) { slot = i; break; }
            if (slot < 0) continue;
            if (work(w, cane)) { be.getStack(slot).decrement(1); be.markDirty(); }
        }
        if (farmsDirty) {
            farmsDirty = false;
            try {
                java.nio.file.Files.createDirectories(farmsFile().getParent());
                java.nio.file.Files.writeString(farmsFile(), new com.google.gson.Gson().toJson(FARMS));
            } catch (Exception ignored) {}
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
