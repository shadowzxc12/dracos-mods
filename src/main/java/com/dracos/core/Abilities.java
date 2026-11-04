package com.dracos.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Schopnosti legendarnych veci. */
public final class Abilities {
    private Abilities() {}

    private static boolean breaking = false; // ochrana pred rekurziou

    public static void register() {
        // ---- Shard Pickaxe 3x3 + Lava Axe tree felling
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (breaking || !(player instanceof ServerPlayerEntity sp)) return;
            ItemStack tool = sp.getMainHandStack();
            String id = LegendaryItems.idOf(tool);
            if (id == null) return;
            try {
                breaking = true;
                if ((id.equals(LegendaryItems.SHARD_PICKAXE) || id.equals(LegendaryItems.EARTH_DRILL)) && !sp.isSneaking()) {
                    mine3x3(sp, world, pos);
                } else if (id.equals(LegendaryItems.LAVA_AXE) && state.isIn(BlockTags.LOGS) && !sp.isSneaking()) {
                    fellTree(sp, world, pos);
                }
            } finally {
                breaking = false;
            }
        });

        // ---- on-hit effects
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient() || !(entity instanceof LivingEntity living) || !(world instanceof ServerWorld sw)) {
                return ActionResult.PASS;
            }
            String id = LegendaryItems.idOf(player.getMainHandStack());
            if (id == null) return ActionResult.PASS;
            switch (id) {
                case LegendaryItems.BLOOD_KATANA -> {
                    player.heal(6f * player.getAttackCooldownProgress(0.5f));
                    living.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 60, 0));
                    Effects.impact(sw, living, Effects.BLOOD_DUST);
                    Effects.p(sw, Effects.BLOOD_DUST, living.getX(), living.getBodyY(0.5), living.getZ(), 14, 0.35, 0);
                    sw.playSound(null, living.getBlockPos(), SoundEvents.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, SoundCategory.PLAYERS, 0.6f, 0.7f);
                }
                case LegendaryItems.FROST_TRIDENT -> {
                    living.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 80, 3));
                    living.setFrozenTicks(Math.max(living.getFrozenTicks(), 220));
                    Effects.p(sw, ParticleTypes.SNOWFLAKE, living.getX(), living.getBodyY(0.5), living.getZ(), 25, 0.4, 0.03);
                    sw.playSound(null, living.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 0.5f, 1.6f);
                }
                case LegendaryItems.SHADOW_DAGGER -> {
                    Vec3d facing = living.getRotationVector().multiply(1, 0, 1).normalize();
                    Vec3d toAttacker = new Vec3d(player.getX() - living.getX(), 0, player.getZ() - living.getZ()).multiply(1, 0, 1).normalize();
                    if (facing.dotProduct(toAttacker) < -0.3) { // attacker is behind the target
                        living.timeUntilRegen = 0;
                        living.damage(sw, sw.getDamageSources().playerAttack(player), 15f);
                        Effects.p(sw, ParticleTypes.SMOKE, living.getX(), living.getBodyY(0.5), living.getZ(), 20, 0.3, 0.02);
                        sw.playSound(null, living.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 0.8f, 0.6f);
                    }
                    Effects.p(sw, ParticleTypes.SQUID_INK, living.getX(), living.getBodyY(0.5), living.getZ(), 6, 0.3, 0.01);
                }
                case LegendaryItems.LAVA_AXE -> {
                    living.setOnFireFor(8f);
                    Effects.impact(sw, living, ParticleTypes.FLAME);
                    Effects.p(sw, ParticleTypes.LAVA, living.getX(), living.getBodyY(0.5), living.getZ(), 4, 0.3, 0);
                }
                case LegendaryItems.DRAGON_BLADE -> {
                    living.setOnFireFor(5f);
                    Effects.impact(sw, living, Effects.PINK_DUST);
                    Effects.p(sw, ParticleTypes.FLAME, living.getX(), living.getBodyY(0.5), living.getZ(), 10, 0.3, 0.02);
                }
                case LegendaryItems.BONE_REAPER -> {
                    living.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 80, 1));
                    living.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1));
                    Effects.impact(sw, living, Effects.BONE_DUST);
                    Effects.p(sw, Effects.BLOOD_DUST, living.getX(), living.getBodyY(0.5), living.getZ(), 8, 0.3, 0);
                    Effects.p(sw, ParticleTypes.WHITE_ASH, living.getX(), living.getBodyY(0.5), living.getZ(), 20, 0.4, 0.05);
                    sw.playSound(null, living.getBlockPos(), SoundEvents.ENTITY_SKELETON_HURT, SoundCategory.PLAYERS, 0.6f, 0.6f);
                }
                case LegendaryItems.VOID_SCYTHE -> {
                    if (player.getAttackCooldownProgress(0.5f) > 0.8f) {
                        Box box = living.getBoundingBox().expand(3.5, 1.0, 3.5);
                        for (LivingEntity other : sw.getEntitiesByClass(LivingEntity.class, box,
                                o -> o != player && o != living && o.isAlive() && !isProtected(o))) {
                            other.damage(sw, sw.getDamageSources().playerAttack(player), 14f);
                            Effects.impact(sw, other, Effects.VOID_DUST);
                        }
                    }
                    player.heal(6f);
                    Effects.ring(sw, Effects.VOID_DUST, player.getX(), player.getY() + 1.0, player.getZ(), 2.5, 24, player.getYaw());
                    Effects.impact(sw, living, Effects.VOID_DUST);
                    Effects.p(sw, ParticleTypes.REVERSE_PORTAL, living.getX(), living.getBodyY(0.5), living.getZ(), 25, 0.8, 0.05);
                    sw.playSound(null, living.getBlockPos(), SoundEvents.ENTITY_PHANTOM_BITE, SoundCategory.PLAYERS, 0.7f, 0.5f);
                    sw.spawnParticles(ParticleTypes.SWEEP_ATTACK, living.getX(), living.getBodyY(0.5), living.getZ(),
                            1, 0, 0, 0, 0);
                }
                default -> {}
            }
            return ActionResult.PASS;
        });

        // ---- right-click abilities
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            String id = LegendaryItems.idOf(stack);
            if (id == null) return ActionResult.PASS;
            boolean has = switch (id) {
                case LegendaryItems.DRAGON_BLADE, LegendaryItems.VOID_SCYTHE, LegendaryItems.BONE_REAPER,
                     LegendaryItems.THUNDER_HAMMER -> true;
                default -> false;
            };
            if (!has) return ActionResult.PASS;
            if (world.isClient()) return ActionResult.SUCCESS;
            if (!(player instanceof ServerPlayerEntity sp) || !(world instanceof ServerWorld sw)) return ActionResult.PASS;
            if (player.getItemCooldownManager().isCoolingDown(stack)) return ActionResult.FAIL;

            switch (id) {
                case LegendaryItems.DRAGON_BLADE -> {
                    Vec3d look = player.getRotationVector();
                    player.addVelocity(look.x * 2.2, 0.4, look.z * 2.2);
                    sp.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(sp));
                    player.fallDistance = 0;
                    player.getItemCooldownManager().set(stack, 40);
                    sw.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.PLAYERS, 1.0f, 1.3f);
                    sw.spawnParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 1, player.getZ(), 40, 0.4, 0.6, 0.4, 0.3);
                    Effects.ring(sw, Effects.PINK_DUST, player.getX(), player.getY() + 0.1, player.getZ(), 1.2, 16, 0);
                    Effects.dashTrail(sp);
                }
                case LegendaryItems.VOID_SCYTHE -> {
                    Box box = player.getBoundingBox().expand(10);
                    int n = 0;
                    for (LivingEntity t : sw.getEntitiesByClass(LivingEntity.class, box,
                            o -> o != player && o.isAlive() && !isProtected(o))) {
                        Vec3d dir = new Vec3d(player.getX() - t.getX(), player.getY() - t.getY(), player.getZ() - t.getZ()).normalize().multiply(1.4);
                        t.addVelocity(dir.x, 0.35, dir.z);
                        t.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 80, 0));
                        t.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 80, 1));
                        if (t instanceof ServerPlayerEntity tp) tp.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(tp));
                        Effects.line(sw, Effects.VOID_DUST, new Vec3d(t.getX(), t.getBodyY(0.5), t.getZ()),
                                new Vec3d(player.getX(), player.getBodyY(0.5), player.getZ()), 0.4);
                        n++;
                    }
                    player.getItemCooldownManager().set(stack, 160);
                    sw.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.8f, 0.7f);
                    sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1, player.getZ(), 200, 5, 1, 5, 0.1);
                    Effects.shockwave(sw, Effects.VOID_DUST, player.getX(), player.getY() + 0.2, player.getZ(), 10);
                    sw.spawnParticles(ParticleTypes.SONIC_BOOM, player.getX(), player.getY() + 1, player.getZ(), 1, 0, 0, 0, 0);
                    sp.sendMessage(Text.literal("Void Pull! (" + n + " pulled)").formatted(Formatting.DARK_PURPLE), true);
                }
                case LegendaryItems.BONE_REAPER -> {
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 100, 2));
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 400, 3));
                    player.getItemCooldownManager().set(stack, 500);
                    sw.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_SKELETON_AMBIENT, SoundCategory.PLAYERS, 1.0f, 0.6f);
                    sw.spawnParticles(ParticleTypes.WHITE_ASH, player.getX(), player.getY() + 1, player.getZ(), 120, 0.8, 1, 0.8, 0.05);
                    Effects.shockwave(sw, Effects.BONE_DUST, player.getX(), player.getY() + 0.2, player.getZ(), 4);
                    Effects.boneShield(sp);
                    sp.sendMessage(Text.literal("Bone Shield!").formatted(Formatting.WHITE, Formatting.BOLD), true);
                }
                case LegendaryItems.THUNDER_HAMMER -> {
                    HitResult hit = player.raycast(40.0, 0f, false);
                    Vec3d pos = hit.getPos();
                    LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(sw, SpawnReason.TRIGGERED);
                    if (bolt != null) {
                        bolt.refreshPositionAfterTeleport(pos);
                        bolt.setChanneler(sp);
                        sw.spawnEntity(bolt);
                    }
                    Box box = new Box(pos, pos).expand(4);
                    for (LivingEntity t : sw.getEntitiesByClass(LivingEntity.class, box,
                            o -> o != player && o.isAlive() && !isProtected(o))) {
                        t.damage(sw, sw.getDamageSources().playerAttack(player), 12f);
                        t.addVelocity(0, 1.1, 0);
                        if (t instanceof ServerPlayerEntity tp) tp.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(tp));
                    }
                    player.getItemCooldownManager().set(stack, 120);
                    sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y + 1, pos.z, 120, 2, 1, 2, 0.4);
                    Effects.shockwave(sw, Effects.YELLOW_DUST, pos.x, pos.y + 0.2, pos.z, 5);
                    Effects.shockwave(sw, ParticleTypes.CLOUD, pos.x, pos.y + 0.1, pos.z, 4);
                    sw.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y + 0.5, pos.z, 1, 0, 0, 0, 0);
                    Effects.line(sw, ParticleTypes.ELECTRIC_SPARK, new Vec3d(player.getX(), player.getEyeY() - 0.3, player.getZ()), pos, 0.6);
                    sw.playSound(null, BlockPos.ofFloored(pos), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 1.5f, 1.2f);
                }
                default -> {}
            }
            return ActionResult.SUCCESS;
        });

        // ---- Void Bow - sipy bez gravitacie a +50% damage
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PersistentProjectileEntity arrow
                    && arrow.getOwner() instanceof PlayerEntity shooter
                    && arrow.age < 2
                    && (LegendaryItems.is(shooter.getMainHandStack(), LegendaryItems.VOID_BOW)
                    || LegendaryItems.is(shooter.getOffHandStack(), LegendaryItems.VOID_BOW))) {
                arrow.setNoGravity(true);
                arrow.setVelocity(arrow.getVelocity().multiply(2.0));
                arrow.addCommandTag(VOID_ARROW);
                Effects.trackArrow(arrow);
            }
        });

        // ---- Void Bow hit -> levitation
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (source.getSource() != null && source.getSource().getCommandTags().contains(VOID_ARROW)) {
                entity.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 40, 1));
                if (entity.getEntityWorld() instanceof ServerWorld w) {
                    Effects.impact(w, entity, Effects.VOID_DUST);
                    Effects.ring(w, Effects.VOID_DUST, entity.getX(), entity.getY() + 0.1, entity.getZ(), 1.0, 12, 0);
                }
            }
        });

        // ---- ambient visuals: weapon particles, set auras, void arrow trails
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int t = server.getTicks();
            if (t % 4 != 0) return;
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (p.isSpectator() || p.isInvisible()) continue;
                ServerWorld w = (ServerWorld) p.getEntityWorld();
                double x = p.getX(), y = p.getY(), z = p.getZ();
                String held = LegendaryItems.idOf(p.getMainHandStack());
                if (held != null) {
                    double yaw = Math.toRadians(p.getYaw() + 40);
                    double hx = x - Math.sin(yaw) * 0.5, hz = z + Math.cos(yaw) * 0.5, hy = y + 1.0;
                    switch (held) {
                        case LegendaryItems.VOID_SCYTHE -> { w.spawnParticles(ParticleTypes.REVERSE_PORTAL, hx, hy, hz, 4, 0.15, 0.3, 0.15, 0.01);
                            w.spawnParticles(ParticleTypes.WITCH, hx, hy + 0.3, hz, 1, 0.1, 0.1, 0.1, 0); }
                        case LegendaryItems.THUNDER_HAMMER -> w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, hx, hy, hz, 3, 0.15, 0.25, 0.15, 0.05);
                        case LegendaryItems.BONE_REAPER -> { w.spawnParticles(ParticleTypes.WHITE_ASH, hx, hy, hz, 4, 0.2, 0.3, 0.2, 0.01);
                            w.spawnParticles(ParticleTypes.SOUL, hx, hy + 0.2, hz, 1, 0.1, 0.1, 0.1, 0.01); }
                        case LegendaryItems.DRAGON_BLADE -> { w.spawnParticles(ParticleTypes.PORTAL, hx, hy, hz, 4, 0.15, 0.3, 0.15, 0.2);
                            w.spawnParticles(ParticleTypes.SMALL_FLAME, hx, hy + 0.3, hz, 1, 0.05, 0.1, 0.05, 0); }
                        case LegendaryItems.LAVA_AXE -> { w.spawnParticles(ParticleTypes.FLAME, hx, hy, hz, 1, 0.1, 0.2, 0.1, 0.01);
                            if (t % 12 == 0) w.spawnParticles(ParticleTypes.LAVA, hx, hy, hz, 1, 0.1, 0.1, 0.1, 0); }
                        case LegendaryItems.SHARD_PICKAXE -> w.spawnParticles(ParticleTypes.END_ROD, hx, hy + 0.2, hz, 1, 0.15, 0.2, 0.15, 0.005);
                        case LegendaryItems.VOID_BOW -> w.spawnParticles(ParticleTypes.REVERSE_PORTAL, hx, hy, hz, 2, 0.15, 0.3, 0.15, 0.01);
                        default -> {}
                    }
                }
                // rotating aura ring for full sets
                net.minecraft.particle.ParticleEffect aura = fullSet(p, LegendaryItems.VOID_SET) ? ParticleTypes.REVERSE_PORTAL
                        : fullSet(p, LegendaryItems.BONE_SET) ? ParticleTypes.SOUL_FIRE_FLAME
                        : fullSet(p, LegendaryItems.DRACO_SET) ? ParticleTypes.FLAME : null;
                if (aura != null) {
                    for (int k = 0; k < 3; k++) {
                        double a = (t * 0.15) + k * (Math.PI * 2 / 3);
                        w.spawnParticles(aura, x + Math.cos(a) * 0.8, y + 0.1 + (k * 0.6), z + Math.sin(a) * 0.8, 1, 0, 0, 0, 0);
                    }
                }
            }
            // void arrow trails
            for (ServerWorld w : server.getWorlds()) {
                for (PersistentProjectileEntity arrow : w.getEntitiesByType(net.minecraft.util.TypeFilter.instanceOf(PersistentProjectileEntity.class),
                        a -> a.getCommandTags().contains(VOID_ARROW) && a.age < 200)) {
                    w.spawnParticles(ParticleTypes.REVERSE_PORTAL, arrow.getX(), arrow.getY(), arrow.getZ(), 6, 0.05, 0.05, 0.05, 0.02);
                    w.spawnParticles(ParticleTypes.WITCH, arrow.getX(), arrow.getY(), arrow.getZ(), 1, 0, 0, 0, 0);
                }
            }
        });

        // ---- set bonuses + held effects
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 20 != 0) return;
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (fullSet(p, LegendaryItems.DRACO_SET)) {
                    effect(p, StatusEffects.SPEED, 1);
                    effect(p, StatusEffects.FIRE_RESISTANCE, 0);
                    effect(p, StatusEffects.HASTE, 1);
                }
                if (fullSet(p, LegendaryItems.VOID_SET)) {
                    effect(p, StatusEffects.STRENGTH, 1);
                    effect(p, StatusEffects.RESISTANCE, 0);
                    effect(p, StatusEffects.SPEED, 0);
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 300, 0, true, false, true));
                }
                if (fullSet(p, LegendaryItems.BONE_SET)) {
                    effect(p, StatusEffects.REGENERATION, 1);
                    effect(p, StatusEffects.RESISTANCE, 0);
                    effect(p, StatusEffects.JUMP_BOOST, 1);
                }
                if (LegendaryItems.is(p.getMainHandStack(), LegendaryItems.SHARD_PICKAXE)
                        || LegendaryItems.is(p.getMainHandStack(), LegendaryItems.EARTH_DRILL)) {
                    effect(p, StatusEffects.HASTE, 1);
                }
                if (LegendaryItems.is(p.getMainHandStack(), LegendaryItems.TITAN_SHIELD)
                        || LegendaryItems.is(p.getOffHandStack(), LegendaryItems.TITAN_SHIELD)) {
                    effect(p, StatusEffects.RESISTANCE, 0);
                }
                if (LegendaryItems.is(p.getEquippedStack(EquipmentSlot.CHEST), LegendaryItems.PHOENIX_ELYTRA)) {
                    effect(p, StatusEffects.FIRE_RESISTANCE, 0);
                }
            }
        });
    }

    private static final String VOID_ARROW = "dracos_void_arrow";

    private static void effect(ServerPlayerEntity p, RegistryEntry<StatusEffect> e, int amp) {
        StatusEffectInstance cur = p.getStatusEffect(e);
        if (cur != null && cur.getAmplifier() > amp) return;
        p.addStatusEffect(new StatusEffectInstance(e, 60, amp, true, false, true));
    }

    private static boolean fullSet(ServerPlayerEntity p, List<String> set) {
        return LegendaryItems.is(p.getEquippedStack(EquipmentSlot.HEAD), set.get(0))
                && LegendaryItems.is(p.getEquippedStack(EquipmentSlot.CHEST), set.get(1))
                && LegendaryItems.is(p.getEquippedStack(EquipmentSlot.LEGS), set.get(2))
                && LegendaryItems.is(p.getEquippedStack(EquipmentSlot.FEET), set.get(3));
    }

    /** Don't hit creative/spectator players with area abilities. */
    private static boolean isProtected(LivingEntity e) {
        return e instanceof PlayerEntity pl && (pl.isCreative() || pl.isSpectator());
    }

    private static Direction hitFace(ServerPlayerEntity player) {
        HitResult hit = player.raycast(6.0, 0f, false);
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) return bhr.getSide();
        return Direction.UP;
    }

    private static final java.util.Map<java.util.UUID, Long> PHOENIX = new java.util.concurrent.ConcurrentHashMap<>();

    /** Titan Shield reflect, Phoenix rebirth, Shadow Dagger kill bonus. */
    public static void registerExtras() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity p && p.isBlocking()
                    && LegendaryItems.is(p.getActiveItem(), LegendaryItems.TITAN_SHIELD)
                    && source.getAttacker() instanceof LivingEntity att && att != p
                    && p.getEntityWorld() instanceof ServerWorld sw) {
                att.damage(sw, sw.getDamageSources().thorns(p), Math.max(2f, amount * 0.5f));
                Vec3d push = new Vec3d(att.getX() - p.getX(), 0, att.getZ() - p.getZ()).multiply(1, 0, 1).normalize().multiply(1.2);
                att.addVelocity(push.x, 0.35, push.z);
                att.velocityModified = true;
                Effects.p(sw, ParticleTypes.CRIT, att.getX(), att.getBodyY(0.5), att.getZ(), 15, 0.3, 0.1);
                sw.playSound(null, p.getBlockPos(), SoundEvents.ITEM_SHIELD_BLOCK.value(), SoundCategory.PLAYERS, 1f, 0.6f);
            }
            return true;
        });

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity p)) return true;
            if (!LegendaryItems.is(p.getEquippedStack(EquipmentSlot.CHEST), LegendaryItems.PHOENIX_ELYTRA)) return true;
            long now = System.currentTimeMillis();
            Long last = PHOENIX.get(p.getUuid());
            if (last != null && now - last < 600_000L) return true;
            PHOENIX.put(p.getUuid(), now);
            p.setHealth(10f);
            p.clearStatusEffects();
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 200, 2));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 600, 2));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 1200, 0));
            if (p.getEntityWorld() instanceof ServerWorld sw) {
                Effects.p(sw, ParticleTypes.FLAME, p.getX(), p.getBodyY(0.5), p.getZ(), 80, 0.8, 0.15);
                Effects.p(sw, ParticleTypes.LAVA, p.getX(), p.getBodyY(0.5), p.getZ(), 20, 0.6, 0);
                sw.playSound(null, p.getBlockPos(), SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 1f, 0.8f);
            }
            p.sendMessage(Text.literal("🔥 Phoenix Rebirth! (next in 10 min)").formatted(Formatting.GOLD, Formatting.BOLD), false);
            return false;
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (source.getAttacker() instanceof ServerPlayerEntity p
                    && LegendaryItems.is(p.getMainHandStack(), LegendaryItems.SHADOW_DAGGER)) {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 80, 0));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 80, 2));
            }
        });
    }

    private static void mine3x3(ServerPlayerEntity player, World world, BlockPos center) {
        Direction face = hitFace(player);
        Direction.Axis axis = face.getAxis();
        ItemStack tool = player.getMainHandStack();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                BlockPos p = switch (axis) {
                    case Y -> center.add(a, 0, b);
                    case X -> center.add(0, a, b);
                    case Z -> center.add(a, b, 0);
                };
                BlockState s = world.getBlockState(p);
                if (s.isAir() || s.getHardness(world, p) < 0 || !tool.isSuitableFor(s)) continue;
                player.interactionManager.tryBreakBlock(p);
                if (player.getMainHandStack().isEmpty()) return;
            }
        }
    }

    private static void fellTree(ServerPlayerEntity player, World world, BlockPos start) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        int broken = 0;
        while (!queue.isEmpty() && broken < 128) {
            BlockPos cur = queue.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = 0; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = cur.add(dx, dy, dz);
                        if (!seen.add(n)) continue;
                        if (!world.getBlockState(n).isIn(BlockTags.LOGS)) continue;
                        player.interactionManager.tryBreakBlock(n);
                        broken++;
                        queue.add(n);
                        if (player.getMainHandStack().isEmpty()) return;
                    }
        }
    }
}
