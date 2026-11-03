package com.dracos.core;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Visual effects: held-item auras, set auras, ability trails, kill bursts. */
public final class Effects {
    private Effects() {}

    public static final DustParticleEffect VOID_DUST = new DustParticleEffect(0xB45CFF, 1.2f);
    public static final DustParticleEffect BONE_DUST = new DustParticleEffect(0xF2E8C9, 1.1f);
    public static final DustParticleEffect BLOOD_DUST = new DustParticleEffect(0xFF2A2A, 1.0f);
    public static final DustParticleEffect GOLD_DUST = new DustParticleEffect(0xFFB000, 1.1f);
    public static final DustParticleEffect CYAN_DUST = new DustParticleEffect(0x6FE3FF, 1.0f);
    public static final DustParticleEffect YELLOW_DUST = new DustParticleEffect(0xFFF35A, 1.2f);
    public static final DustParticleEffect PINK_DUST = new DustParticleEffect(0xFF5CF0, 1.1f);

    private static final Map<UUID, Integer> DASH = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SHIELD = new ConcurrentHashMap<>();
    private static final Set<PersistentProjectileEntity> ARROWS = ConcurrentHashMap.newKeySet();

    public static void dashTrail(ServerPlayerEntity p) { DASH.put(p.getUuid(), 12); }
    public static void boneShield(ServerPlayerEntity p) { SHIELD.put(p.getUuid(), 100); }
    public static void trackArrow(PersistentProjectileEntity a) { ARROWS.add(a); }

    // ------------------------------------------------------------------ helpers
    public static void p(ServerWorld w, ParticleEffect e, double x, double y, double z, int n, double s, double speed) {
        w.spawnParticles(e, x, y, z, n, s, s, s, speed);
    }

    public static void ring(ServerWorld w, ParticleEffect e, double x, double y, double z, double r, int n, double rot) {
        for (int i = 0; i < n; i++) {
            double a = rot + i * Math.PI * 2 / n;
            w.spawnParticles(e, x + Math.cos(a) * r, y, z + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    public static void line(ServerWorld w, ParticleEffect e, Vec3d from, Vec3d to, double step) {
        Vec3d d = to.subtract(from);
        double len = d.length();
        if (len < 0.01) return;
        Vec3d u = d.multiply(1 / len);
        for (double t = 0; t <= len; t += step) {
            w.spawnParticles(e, from.x + u.x * t, from.y + u.y * t, from.z + u.z * t, 1, 0, 0, 0, 0);
        }
    }

    /** Shockwave: several expanding rings at once. */
    public static void shockwave(ServerWorld w, ParticleEffect e, double x, double y, double z, double maxR) {
        for (double r = 1; r <= maxR; r += 1) ring(w, e, x, y, z, r, (int) (r * 10), r * 0.3);
    }

    private static Vec3d hand(ServerPlayerEntity p) {
        Vec3d look = p.getRotationVector();
        double yaw = Math.toRadians(p.getYaw());
        // slightly right & forward of the eyes, where the item is
        return new Vec3d(p.getX() - Math.cos(yaw) * 0.4 + look.x * 0.5, p.getEyeY() - 0.45, p.getZ() - Math.sin(yaw) * 0.4 + look.z * 0.5);
    }

    private static boolean fullSet(ServerPlayerEntity p, List<String> set) {
        return LegendaryItems.is(p.getEquippedStack(EquipmentSlot.HEAD), set.get(0))
                && LegendaryItems.is(p.getEquippedStack(EquipmentSlot.CHEST), set.get(1))
                && LegendaryItems.is(p.getEquippedStack(EquipmentSlot.LEGS), set.get(2))
                && LegendaryItems.is(p.getEquippedStack(EquipmentSlot.FEET), set.get(3));
    }

    // ------------------------------------------------------------------ register
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int t = server.getTicks();
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (p.isSpectator() || p.isInvisible()) continue;
                ServerWorld w = (ServerWorld) p.getEntityWorld();

                // held item aura (every 3 ticks)
                if (t % 3 == 0) {
                    String id = LegendaryItems.idOf(p.getMainHandStack());
                    if (id != null) {
                        Vec3d h = hand(p);
                        switch (id) {
                            case LegendaryItems.VOID_SCYTHE -> {
                                p(w, VOID_DUST, h.x, h.y, h.z, 2, 0.25, 0);
                                p(w, ParticleTypes.REVERSE_PORTAL, h.x, h.y, h.z, 2, 0.2, 0.02);
                            }
                            case LegendaryItems.THUNDER_HAMMER -> {
                                p(w, ParticleTypes.ELECTRIC_SPARK, h.x, h.y, h.z, 2, 0.25, 0.05);
                                if (t % 9 == 0) p(w, YELLOW_DUST, h.x, h.y, h.z, 2, 0.2, 0);
                            }
                            case LegendaryItems.BONE_REAPER -> {
                                p(w, ParticleTypes.WHITE_ASH, h.x, h.y, h.z, 3, 0.25, 0.01);
                                if (t % 9 == 0) p(w, BLOOD_DUST, h.x, h.y, h.z, 1, 0.15, 0);
                            }
                            case LegendaryItems.DRAGON_BLADE -> {
                                p(w, PINK_DUST, h.x, h.y, h.z, 2, 0.25, 0);
                                if (t % 6 == 0) p(w, ParticleTypes.PORTAL, h.x, h.y, h.z, 3, 0.2, 0.2);
                            }
                            case LegendaryItems.SHARD_PICKAXE -> {
                                p(w, CYAN_DUST, h.x, h.y, h.z, 1, 0.2, 0);
                                if (t % 9 == 0) p(w, ParticleTypes.END_ROD, h.x, h.y, h.z, 1, 0.15, 0.01);
                            }
                            case LegendaryItems.LAVA_AXE -> {
                                p(w, ParticleTypes.SMALL_FLAME, h.x, h.y, h.z, 1, 0.2, 0.01);
                                if (t % 12 == 0) p(w, ParticleTypes.LAVA, h.x, h.y, h.z, 1, 0.1, 0);
                            }
                            case LegendaryItems.VOID_BOW -> p(w, VOID_DUST, h.x, h.y, h.z, 1, 0.25, 0);
                            default -> {}
                        }
                    }
                }

                // set auras: rotating ring at the feet (every 4 ticks)
                if (t % 4 == 0) {
                    double rot = t * 0.15;
                    if (fullSet(p, LegendaryItems.VOID_SET)) {
                        ring(w, VOID_DUST, p.getX(), p.getY() + 0.1, p.getZ(), 0.8, 8, rot);
                        p(w, ParticleTypes.REVERSE_PORTAL, p.getX(), p.getY() + 1, p.getZ(), 1, 0.4, 0.02);
                    } else if (fullSet(p, LegendaryItems.BONE_SET)) {
                        ring(w, BONE_DUST, p.getX(), p.getY() + 0.1, p.getZ(), 0.8, 8, rot);
                        p(w, ParticleTypes.WHITE_ASH, p.getX(), p.getY() + 1, p.getZ(), 2, 0.4, 0.01);
                    } else if (fullSet(p, LegendaryItems.DRACO_SET)) {
                        ring(w, GOLD_DUST, p.getX(), p.getY() + 0.1, p.getZ(), 0.8, 8, rot);
                        if (t % 8 == 0) p(w, ParticleTypes.FLAME, p.getX(), p.getY() + 0.2, p.getZ(), 1, 0.3, 0.01);
                    }
                }

                // dragon dash trail
                Integer d = DASH.get(p.getUuid());
                if (d != null) {
                    p(w, PINK_DUST, p.getX(), p.getY() + 0.8, p.getZ(), 6, 0.3, 0);
                    p(w, ParticleTypes.PORTAL, p.getX(), p.getY() + 0.8, p.getZ(), 6, 0.3, 0.3);
                    p(w, ParticleTypes.FLAME, p.getX(), p.getY() + 0.3, p.getZ(), 2, 0.2, 0.01);
                    if (d <= 1) DASH.remove(p.getUuid()); else DASH.put(p.getUuid(), d - 1);
                }

                // bone shield: bones orbiting the chest
                Integer s = SHIELD.get(p.getUuid());
                if (s != null) {
                    if (t % 2 == 0) {
                        ring(w, BONE_DUST, p.getX(), p.getY() + 1.1, p.getZ(), 1.0, 6, t * 0.25);
                        ring(w, ParticleTypes.WHITE_ASH, p.getX(), p.getY() + 0.5, p.getZ(), 1.0, 4, -t * 0.25);
                    }
                    if (s <= 1) SHIELD.remove(p.getUuid()); else SHIELD.put(p.getUuid(), s - 1);
                }
            }

            // void arrow trails
            for (Iterator<PersistentProjectileEntity> it = ARROWS.iterator(); it.hasNext(); ) {
                PersistentProjectileEntity a = it.next();
                if (a.isRemoved() || a.age > 200 || a.getVelocity().lengthSquared() < 0.01) {
                    it.remove();
                    continue;
                }
                ServerWorld w = (ServerWorld) a.getEntityWorld();
                p(w, VOID_DUST, a.getX(), a.getY(), a.getZ(), 2, 0.05, 0);
                p(w, ParticleTypes.REVERSE_PORTAL, a.getX(), a.getY(), a.getZ(), 1, 0.05, 0.01);
            }
        });

        // kill burst with legendary weapons
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(source.getAttacker() instanceof ServerPlayerEntity killer)) return;
            String id = LegendaryItems.idOf(killer.getMainHandStack());
            if (id == null) return;
            ServerWorld w = (ServerWorld) entity.getEntityWorld();
            double x = entity.getX(), y = entity.getBodyY(0.5), z = entity.getZ();
            ParticleEffect col = switch (id) {
                case LegendaryItems.VOID_SCYTHE, LegendaryItems.VOID_BOW -> VOID_DUST;
                case LegendaryItems.BONE_REAPER -> BONE_DUST;
                case LegendaryItems.THUNDER_HAMMER -> YELLOW_DUST;
                case LegendaryItems.DRAGON_BLADE -> PINK_DUST;
                default -> GOLD_DUST;
            };
            p(w, col, x, y, z, 40, 0.5, 0);
            p(w, ParticleTypes.SOUL, x, y, z, 15, 0.4, 0.05);
            p(w, ParticleTypes.SCULK_SOUL, x, y, z, 6, 0.3, 0.05);
            if (entity instanceof ServerPlayerEntity) {
                p(w, ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 60, 0.5, 0.4);
                w.playSound(null, entity.getBlockPos(), SoundEvents.ENTITY_EVOKER_CAST_SPELL, SoundCategory.PLAYERS, 1f, 0.6f);
            }
        });
    }

    /** Big hit effect used by abilities. */
    public static void impact(ServerWorld w, LivingEntity t, ParticleEffect e) {
        p(w, e, t.getX(), t.getBodyY(0.5), t.getZ(), 12, 0.35, 0);
        p(w, ParticleTypes.ENCHANTED_HIT, t.getX(), t.getBodyY(0.5), t.getZ(), 8, 0.3, 0.2);
    }
}
