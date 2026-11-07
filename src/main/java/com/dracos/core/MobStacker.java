package com.dracos.core;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mob stacking: same-type mobs close together merge into one ("Zombie x24").
 * Killing a stack kills one mob - the rest stay as a stack with one less. Cuts lag from farms and spawners a lot.
 */
public final class MobStacker {
    private MobStacker() {}

    static final int MAX_STACK = 100;
    static final double RADIUS = 5.0;
    private static final String TAG = "dracos_stack:";
    private static final Set<EntityType<?>> NEVER = Set.of(EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN,
            EntityType.ELDER_GUARDIAN, EntityType.VILLAGER, EntityType.WANDERING_TRADER, EntityType.ARMOR_STAND,
            EntityType.SHULKER, EntityType.ALLAY, EntityType.PIGLIN_BRUTE);

    static int count(Entity e) {
        for (String t : e.getCommandTags()) if (t.startsWith(TAG)) {
            try { return Math.max(1, Integer.parseInt(t.substring(TAG.length()))); } catch (NumberFormatException ignored) {}
        }
        return 1;
    }

    static void setCount(MobEntity m, int n) {
        for (String t : new ArrayList<>(m.getCommandTags())) if (t.startsWith(TAG)) m.removeCommandTag(t);
        if (n <= 1) {
            m.setCustomName(null);
            m.setCustomNameVisible(false);
            return;
        }
        m.addCommandTag(TAG + n);
        m.setCustomName(Text.literal(m.getType().getName().getString() + " ").formatted(Formatting.YELLOW)
                .append(Text.literal("x" + n).formatted(Formatting.GOLD, Formatting.BOLD)));
        m.setCustomNameVisible(true);
    }

    static boolean stackable(Entity e) {
        if (!(e instanceof MobEntity m) || !m.isAlive() || m.isRemoved()) return false;
        if (NEVER.contains(m.getType()) || m instanceof MerchantEntity || m instanceof SlimeEntity) return false;
        if (m instanceof TameableEntity t && t.isTamed()) return false;
        if (m.isPersistent() || m.isLeashed() || m.hasVehicle() || m.hasPassengers() || m.isBaby()) return false;
        // a name tag (not our own stack name) means the player wants this mob kept on its own
        return !m.hasCustomName() || count(m) > 1;
    }

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (world.getTime() % 100 != 0) return;
            merge(world);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof MobEntity m) || !(m.getEntityWorld() instanceof ServerWorld w)) return;
            int n = count(m);
            if (n <= 1) return;
            Entity next = m.getType().create(w, SpawnReason.NATURAL);
            if (!(next instanceof MobEntity nm)) return;
            nm.refreshPositionAndAngles(m.getX(), m.getY(), m.getZ(), m.getYaw(), m.getPitch());
            w.spawnEntity(nm);
            setCount(nm, n - 1);
        });
    }

    private static void merge(ServerWorld world) {
        List<MobEntity> mobs = new ArrayList<>();
        for (Entity e : world.iterateEntities()) if (stackable(e)) mobs.add((MobEntity) e);
        if (mobs.size() < 2) return;
        Set<MobEntity> gone = new HashSet<>();
        for (MobEntity leader : mobs) {
            if (gone.contains(leader) || !leader.isAlive()) continue;
            int total = count(leader);
            if (total >= MAX_STACK) continue;
            Box box = leader.getBoundingBox().expand(RADIUS);
            List<MobEntity> near = world.getEntitiesByClass(MobEntity.class, box,
                    o -> o != leader && o.getType() == leader.getType() && !gone.contains(o) && stackable(o));
            boolean changed = false;
            for (MobEntity o : near) {
                int c = count(o);
                if (total + c > MAX_STACK) continue;
                total += c;
                gone.add(o);
                o.discard();
                changed = true;
            }
            if (changed) setCount(leader, total);
        }
    }
}
