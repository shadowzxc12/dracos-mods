package com.dracos.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.TextColor;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Custom swing for legendary weapons: a coloured slash arc that sweeps across over a few ticks + impact burst + sound. */
public final class SwingFx {
    private SwingFx() {}

    private static final String[] BIG = {"scythe", "reaper", "greatsword", "hammer", "mace", "cleaver", "axe", "earthshaker", "trident"};

    private record Arc(ServerWorld w, Vec3d eye, float yaw, float pitch, int color, boolean big, boolean flip, int[] tick) {}

    private static final List<Arc> ARCS = new ArrayList<>();
    private static int swingCounter = 0;
    private static final java.util.Map<java.util.UUID, Long> LAST = new java.util.concurrent.ConcurrentHashMap<>();

    private static int colorOf(ItemStack s) {
        TextColor c = s.getName().getStyle().getColor();
        if (c == null && !s.getName().getSiblings().isEmpty()) c = s.getName().getSiblings().get(0).getStyle().getColor();
        return c == null ? 0xB45CFF : c.getRgb();
    }

    private static boolean big(String id) {
        for (String b : BIG) if (id.contains(b)) return true;
        return false;
    }

    public static void register() {
        DracosCore.LOGGER.info("[SwingFx] legendary swing effects enabled");
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient() || !(world instanceof ServerWorld sw) || !(entity instanceof LivingEntity target)) return ActionResult.PASS;
            ItemStack st = player.getMainHandStack();
            String id = LegendaryItems.idOf(st);
            if (id == null || st.isEmpty()) return ActionResult.PASS;
            long now = sw.getTime();
            Long last = LAST.get(player.getUuid());
            if (last != null && now - last < 6) return ActionResult.PASS; // anti-spam
            LAST.put(player.getUuid(), now);
            int color = colorOf(st);
            boolean big = big(id);
            synchronized (ARCS) {
                if (ARCS.size() < 200)
                    ARCS.add(new Arc(sw, player.getEyePos(), player.getYaw(), player.getPitch(), color, big, (swingCounter++ & 1) == 1, new int[]{0}));
            }
            impact(sw, player, target, color, big);
            return ActionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            synchronized (ARCS) {
                Iterator<Arc> it = ARCS.iterator();
                while (it.hasNext()) {
                    Arc a = it.next();
                    drawStep(a);
                    if (++a.tick[0] >= 4) it.remove();
                }
            }
        });
    }

    /** One quarter of the crescent per tick, so the slash visibly sweeps across. */
    private static void drawStep(Arc a) {
        double r = a.big ? 2.4 : 1.7;
        int steps = a.big ? 12 : 9;
        double yawR = Math.toRadians(a.yaw), pitR = Math.toRadians(a.pitch);
        Vec3d fwd = new Vec3d(-Math.sin(yawR) * Math.cos(pitR), -Math.sin(pitR), Math.cos(yawR) * Math.cos(pitR));
        Vec3d right = new Vec3d(-Math.cos(yawR), 0, -Math.sin(yawR));
        Vec3d up = right.crossProduct(fwd).normalize();
        // tilted diagonal slash: from upper-left to lower-right (or mirrored on alternate swings)
        double tilt = a.flip ? -0.55 : 0.55;
        DustParticleEffect core = new DustParticleEffect(a.color, a.big ? 1.6f : 1.2f);
        DustParticleEffect glow = new DustParticleEffect(0xFFFFFF, 0.7f);
        int t = a.tick[0];
        for (int i = 0; i < steps; i++) {
            double f = (t + i / (double) steps) / 4.0;           // 0..1 across the whole swing
            double ang = Math.toRadians(-75 + 150 * f);          // sweep 150 degrees
            double side = Math.sin(ang), depth = Math.cos(ang);
            for (int layer = 0; layer < 3; layer++) {
                double rr = r - layer * 0.18;
                Vec3d p = a.eye.add(fwd.multiply(depth * rr * 0.8 + 0.4))
                        .add(right.multiply(side * rr))
                        .add(up.multiply(side * rr * tilt - 0.35));
                a.w.spawnParticles(layer == 0 ? glow : core, p.x, p.y, p.z, 1, 0, 0, 0, 0);
            }
        }
        if (t == 1) {
            Vec3d mid = a.eye.add(fwd.multiply(r * 0.9));
            a.w.spawnParticles(ParticleTypes.SWEEP_ATTACK, mid.x, mid.y - 0.3, mid.z, a.big ? 2 : 1, 0.3, 0.1, 0.3, 0);
        }
    }

    private static void impact(ServerWorld w, PlayerEntity p, LivingEntity t, int color, boolean big) {
        double x = t.getX(), y = t.getBodyY(0.55), z = t.getZ();
        w.spawnParticles(new DustParticleEffect(color, 1.8f), x, y, z, big ? 26 : 16, 0.35, 0.4, 0.35, 0);
        w.spawnParticles(ParticleTypes.CRIT, x, y, z, big ? 14 : 8, 0.3, 0.3, 0.3, 0.35);
        if (big) w.spawnParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0, 0, 0, 0);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.9f, big ? 0.6f : 1.1f);
        w.playSound(null, t.getBlockPos(), big ? SoundEvents.ITEM_TRIDENT_HIT : SoundEvents.ENTITY_PLAYER_ATTACK_STRONG,
                SoundCategory.PLAYERS, 0.7f, big ? 0.7f : 1.3f);
    }
}
