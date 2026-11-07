package com.dracos.core;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Set;

/**
 * Sneak + right-click a mob with an empty hand to pick it up - it goes into your inventory as a "Captured" item
 * that keeps everything (villager trades, pet owner, name, health...). Right-click a block with it to put it down.
 * (Vanilla does not allow anything to ride a player, so the mob is carried as an item.)
 */
public final class MobCarry {
    private MobCarry() {}

    private static final String TAG = "dracos_captured";
    // the dragon is a multi-part boss and breaks the End fight if moved; everything else can be carried
    private static final Set<EntityType<?>> BLOCKED = Set.of(EntityType.ENDER_DRAGON);

    static NbtCompound captured(ItemStack s) {
        if (s == null || s.isEmpty()) return null;
        NbtComponent d = s.get(DataComponentTypes.CUSTOM_DATA);
        if (d == null) return null;
        NbtCompound n = d.copyNbt();
        return n.contains(TAG) ? n.getCompoundOrEmpty(TAG) : null;
    }

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity p) || hand != Hand.MAIN_HAND || !p.isSneaking()
                    || !p.getMainHandStack().isEmpty()) return ActionResult.PASS;
            if (!(entity instanceof MobEntity m) || BLOCKED.contains(m.getType()) || !m.isAlive()) return ActionResult.PASS;
            if (Combat.inCombat(p)) {
                p.sendMessage(Text.literal("You can't pick up mobs in combat!").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }
            if (m.hasPassengers()) m.removeAllPassengers();
            if (m.hasVehicle()) m.stopRiding();
            NbtWriteView view = NbtWriteView.create(ErrorReporter.EMPTY, world.getRegistryManager());
            if (!m.saveSelfData(view)) return ActionResult.FAIL;
            NbtCompound data = view.getNbt();

            Identifier typeId = Registries.ENTITY_TYPE.getId(m.getType());
            String name = m.hasCustomName() ? m.getCustomName().getString() : m.getType().getName().getString();
            ItemStack item = new ItemStack(Items.PAPER);
            item.set(DataComponentTypes.MAX_STACK_SIZE, 1);
            Identifier egg = Identifier.of(typeId.getNamespace(), typeId.getPath() + "_spawn_egg");
            item.set(DataComponentTypes.ITEM_MODEL, Registries.ITEM.containsId(egg) ? egg : Identifier.of("minecraft", "lead"));
            item.set(DataComponentTypes.ITEM_NAME, Text.literal("Captured " + name).formatted(Formatting.AQUA, Formatting.BOLD));
            item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    Text.literal("Right-click a block to put it down").styled(s -> s.withColor(Formatting.GRAY).withItalic(false)),
                    Text.literal("Keeps trades, name, owner and health").styled(s -> s.withColor(Formatting.DARK_GRAY).withItalic(false)))));
            item.set(DataComponentTypes.RARITY, Rarity.RARE);
            item.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            NbtCompound tag = new NbtCompound();
            tag.put(TAG, data);
            item.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));

            m.discard();
            p.setStackInHand(Hand.MAIN_HAND, item);
            world.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.8f, 1.3f);
            p.sendMessage(Text.literal("Picked up " + name + "! Right-click a block to put it down.").formatted(Formatting.GREEN), true);
            return ActionResult.SUCCESS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity p)) return ActionResult.PASS;
            ItemStack held = p.getStackInHand(hand);
            NbtCompound data = captured(held);
            if (data == null) return ActionResult.PASS;
            BlockPos at = hit.getBlockPos().offset(hit.getSide());
            ServerWorld sw = (ServerWorld) world;
            if (!sw.getWorldBorder().contains(at) || !p.canModifyAt(sw, at)) return ActionResult.FAIL;
            Vec3d pos = Vec3d.ofBottomCenter(at);
            Entity e = EntityType.loadEntityWithPassengers(NbtReadView.create(ErrorReporter.EMPTY, world.getRegistryManager(), data),
                    world, SpawnReason.LOAD, ent -> {
                        ent.refreshPositionAndAngles(pos.x, pos.y, pos.z, p.getYaw() + 180, 0);
                        return ent;
                    });
            if (e == null || !sw.spawnNewEntityAndPassengers(e)) {
                p.sendMessage(Text.literal("Could not place it here.").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }
            p.setStackInHand(hand, ItemStack.EMPTY);
            world.playSound(null, at, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.8f, 0.7f);
            return ActionResult.SUCCESS;
        });

        // captured mobs can't be used in the air (no accidental behaviour)
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) ->
                captured(player.getStackInHand(hand)) != null ? ActionResult.FAIL : ActionResult.PASS);
    }
}
