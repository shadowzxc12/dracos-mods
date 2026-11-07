package com.dracos.core;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static com.dracos.core.LegendaryItems.e;

/** Legendary items 41-70: data-driven, with on-hit and held abilities. */
public final class Legendary3 {
    private Legendary3() {}

    record Fx(RegistryEntry<StatusEffect> effect, int seconds, int amp) {}

    record Spec(String id, Item base, String name, int color, double dmg, double speed, long price,
                List<String> lore, LegendaryItems.Ench[] ench,
                List<Fx> onHit, List<Fx> held, int fireSec, double lightning, double lifesteal, int freezeTicks) {}

    private static Fx fx(RegistryEntry<StatusEffect> e, int s, int a) { return new Fx(e, s, a); }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    private static void add(String id, Item base, String name, int color, double dmg, double speed, long price,
                            List<String> lore, LegendaryItems.Ench[] ench, List<Fx> hit, List<Fx> held,
                            int fire, double lightning, double steal, int freeze) {
        SPECS.put(id, new Spec(id, base, name, color, dmg, speed, price, lore, ench, hit, held, fire, lightning, steal, freeze));
    }

    static {
        add("ruby_saber", Items.NETHERITE_SWORD, "Ruby Saber", 0xE0115F, 21, 1.6, 60_000_000L,
                List.of("21 damage", "Lifesteal 15%"), new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 8), e(Enchantments.LOOTING, 3)},
                List.of(), List.of(), 0, 0, 0.15, 0);
        add("sapphire_edge", Items.DIAMOND_SWORD, "Sapphire Edge", 0x0F52BA, 18, 1.8, 40_000_000L,
                List.of("18 damage", "Slowness II on hit"), new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 7), e(Enchantments.SWEEPING_EDGE, 3)},
                List.of(fx(StatusEffects.SLOWNESS, 3, 1)), List.of(), 0, 0, 0, 0);
        add("emerald_cleaver", Items.NETHERITE_AXE, "Emerald Cleaver", 0x2ECC71, 25, 0.9, 55_000_000L,
                List.of("25 damage", "Weakness II on hit", "Hero of the Village while held"),
                new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 7), e(Enchantments.EFFICIENCY, 6)},
                List.of(fx(StatusEffects.WEAKNESS, 4, 1)), List.of(fx(StatusEffects.HERO_OF_THE_VILLAGE, 2, 0)), 0, 0, 0, 0);
        add("amethyst_rapier", Items.DIAMOND_SWORD, "Amethyst Rapier", 0x9966CC, 16, 2.4, 45_000_000L,
                List.of("16 damage, extremely fast", "Targets glow and float"), new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 8)},
                List.of(fx(StatusEffects.GLOWING, 5, 0), fx(StatusEffects.LEVITATION, 1, 0)), List.of(), 0, 0, 0, 0);
        add("sunfire_blade", Items.GOLDEN_SWORD, "Sunfire Blade", 0xFFB300, 20, 1.6, 50_000_000L,
                List.of("20 damage", "Burns targets for 6 seconds", "Fire Resistance while held"),
                new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 8), e(Enchantments.FIRE_ASPECT, 2)},
                List.of(), List.of(fx(StatusEffects.FIRE_RESISTANCE, 2, 0)), 6, 0, 0, 0);
        add("abyssal_reaper", Items.NETHERITE_HOE, "Abyssal Reaper", 0x1B1464, 24, 1.2, 80_000_000L,
                List.of("24 damage", "Darkness + Wither", "Lifesteal 10%"), new LegendaryItems.Ench[]{e(Enchantments.LOOTING, 5)},
                List.of(fx(StatusEffects.DARKNESS, 4, 0), fx(StatusEffects.WITHER, 4, 0)), List.of(), 0, 0, 0.10, 0);
        add("toxic_cleaver", Items.NETHERITE_AXE, "Toxic Cleaver", 0x7FFF00, 23, 1.0, 50_000_000L,
                List.of("23 damage", "Poison III on hit"), new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 7)},
                List.of(fx(StatusEffects.POISON, 4, 2)), List.of(), 0, 0, 0, 0);
        add("tempest_blade", Items.DIAMOND_SWORD, "Tempest Blade", 0x5DADE2, 19, 1.7, 55_000_000L,
                List.of("19 damage", "15% chance to strike lightning", "Speed I while held"),
                new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 8)},
                List.of(), List.of(fx(StatusEffects.SPEED, 2, 0)), 0, 0.15, 0, 0);
        add("frostbite_dagger", Items.DIAMOND_SWORD, "Frostbite Dagger", 0xA8E6FF, 15, 2.5, 40_000_000L,
                List.of("15 damage, extremely fast", "Freezes targets + Slowness IV"), new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 7)},
                List.of(fx(StatusEffects.SLOWNESS, 2, 3)), List.of(), 0, 0, 0, 200);
        add("dragonbone_axe", Items.NETHERITE_AXE, "Dragonbone Axe", 0xEDE0C8, 26, 0.9, 70_000_000L,
                List.of("26 damage", "Launches targets up"), new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 8), e(Enchantments.EFFICIENCY, 8)},
                List.of(fx(StatusEffects.LEVITATION, 1, 3)), List.of(), 0, 0, 0, 0);
        add("warlord_mace", Items.MACE, "Warlord Mace", 0xD35400, 22, 0.9, 75_000_000L,
                List.of("22 damage", "Weakness + Slowness II"), new LegendaryItems.Ench[]{e(Enchantments.DENSITY, 4), e(Enchantments.BREACH, 4)},
                List.of(fx(StatusEffects.WEAKNESS, 4, 0), fx(StatusEffects.SLOWNESS, 3, 1)), List.of(), 0, 0, 0, 0);
        add("skybreaker_mace", Items.MACE, "Skybreaker Mace", 0xF7E7A1, 19, 1.0, 60_000_000L,
                List.of("19 damage", "Sends targets flying", "Slow Falling while held"),
                new LegendaryItems.Ench[]{e(Enchantments.WIND_BURST, 3), e(Enchantments.DENSITY, 3)},
                List.of(fx(StatusEffects.LEVITATION, 2, 1)), List.of(fx(StatusEffects.SLOW_FALLING, 2, 0)), 0, 0, 0, 0);
        add("kraken_trident", Items.TRIDENT, "Kraken Trident", 0x0B3D5C, 20, 1.1, 65_000_000L,
                List.of("20 damage", "Blindness + Slowness", "Conduit Power while held"),
                new LegendaryItems.Ench[]{e(Enchantments.IMPALING, 6), e(Enchantments.LOYALTY, 3)},
                List.of(fx(StatusEffects.BLINDNESS, 3, 0), fx(StatusEffects.SLOWNESS, 3, 1)), List.of(fx(StatusEffects.CONDUIT_POWER, 2, 0)), 0, 0, 0, 0);
        add("starfall_trident", Items.TRIDENT, "Starfall Trident", 0xFFF5B8, 19, 1.1, 70_000_000L,
                List.of("19 damage", "20% chance to strike lightning", "Targets glow"),
                new LegendaryItems.Ench[]{e(Enchantments.IMPALING, 5), e(Enchantments.LOYALTY, 3)},
                List.of(fx(StatusEffects.GLOWING, 6, 0)), List.of(), 0, 0.20, 0, 0);
        add("vampire_fang", Items.NETHERITE_SWORD, "Vampire Fang", 0x8B0000, 18, 2.0, 90_000_000L,
                List.of("18 damage, fast", "Lifesteal 30%"), new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 8)},
                List.of(), List.of(), 0, 0, 0.30, 0);
        add("plague_scythe", Items.NETHERITE_HOE, "Plague Scythe", 0x6B8E23, 23, 1.2, 70_000_000L,
                List.of("23 damage", "Poison II + Hunger II + Weakness"), new LegendaryItems.Ench[]{e(Enchantments.LOOTING, 5)},
                List.of(fx(StatusEffects.POISON, 5, 1), fx(StatusEffects.HUNGER, 6, 1), fx(StatusEffects.WEAKNESS, 4, 0)), List.of(), 0, 0, 0, 0);
        add("obsidian_greatsword", Items.NETHERITE_SWORD, "Obsidian Greatsword", 0x3C2A4D, 27, 0.8, 95_000_000L,
                List.of("27 damage, slow", "Resistance I while held"), new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 8), e(Enchantments.KNOCKBACK, 2)},
                List.of(), List.of(fx(StatusEffects.RESISTANCE, 2, 0)), 0, 0, 0, 0);
        add("phantom_blade", Items.DIAMOND_SWORD, "Phantom Blade", 0x6C7A89, 17, 2.1, 50_000_000L,
                List.of("17 damage", "Blindness on hit", "Slow Falling while held"), new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 7)},
                List.of(fx(StatusEffects.BLINDNESS, 3, 0)), List.of(fx(StatusEffects.SLOW_FALLING, 2, 0)), 0, 0, 0, 0);
        add("thunderclap_hammer", Items.MACE, "Thunderclap Hammer", 0x3498DB, 21, 0.9, 85_000_000L,
                List.of("21 damage", "30% chance to strike lightning"), new LegendaryItems.Ench[]{e(Enchantments.DENSITY, 4), e(Enchantments.BREACH, 3)},
                List.of(), List.of(), 0, 0.30, 0, 0);
        add("molten_pickaxe", Items.NETHERITE_PICKAXE, "Molten Pickaxe", 0xFF5722, 0, 0, 35_000_000L,
                List.of("Efficiency X + Fortune IV", "Haste III + Fire Resistance while held"),
                new LegendaryItems.Ench[]{e(Enchantments.EFFICIENCY, 10), e(Enchantments.FORTUNE, 4)},
                List.of(), List.of(fx(StatusEffects.HASTE, 2, 2), fx(StatusEffects.FIRE_RESISTANCE, 2, 0)), 0, 0, 0, 0);
        add("titan_pickaxe", Items.NETHERITE_PICKAXE, "Titan Pickaxe", 0x8D6E63, 0, 0, 40_000_000L,
                List.of("Efficiency X + Fortune V", "Haste II while held"),
                new LegendaryItems.Ench[]{e(Enchantments.EFFICIENCY, 10), e(Enchantments.FORTUNE, 5)},
                List.of(), List.of(fx(StatusEffects.HASTE, 2, 1)), 0, 0, 0, 0);
        add("lumberjack_axe", Items.GOLDEN_AXE, "Lumberjack Axe", 0xC0A060, 18, 1.0, 25_000_000L,
                List.of("Efficiency X", "Haste II while held"), new LegendaryItems.Ench[]{e(Enchantments.EFFICIENCY, 10), e(Enchantments.SHARPNESS, 5)},
                List.of(), List.of(fx(StatusEffects.HASTE, 2, 1)), 0, 0, 0, 0);
        add("gaia_hoe", Items.DIAMOND_HOE, "Gaia Hoe", 0x58D68D, 10, 2.0, 20_000_000L,
                List.of("Fortune V for crops", "Saturation while held"), new LegendaryItems.Ench[]{e(Enchantments.FORTUNE, 5), e(Enchantments.EFFICIENCY, 10)},
                List.of(), List.of(fx(StatusEffects.SATURATION, 2, 0)), 0, 0, 0, 0);
        add("quake_shovel", Items.NETHERITE_SHOVEL, "Quake Shovel", 0x9C7A4D, 0, 0, 20_000_000L,
                List.of("Efficiency X", "Haste II while held"), new LegendaryItems.Ench[]{e(Enchantments.EFFICIENCY, 10)},
                List.of(), List.of(fx(StatusEffects.HASTE, 2, 1)), 0, 0, 0, 0);
        add("spirit_sickle", Items.GOLDEN_HOE, "Spirit Sickle", 0x76D7C4, 20, 1.4, 55_000_000L,
                List.of("20 damage", "Wither II + lifesteal 10%"), new LegendaryItems.Ench[]{e(Enchantments.LOOTING, 4)},
                List.of(fx(StatusEffects.WITHER, 4, 1)), List.of(), 0, 0, 0.10, 0);
        add("inferno_mace", Items.MACE, "Inferno Mace", 0xFF3D00, 21, 0.9, 70_000_000L,
                List.of("21 damage", "Burns targets for 8 seconds"), new LegendaryItems.Ench[]{e(Enchantments.DENSITY, 4), e(Enchantments.FIRE_ASPECT, 2)},
                List.of(), List.of(), 8, 0, 0, 0);
        add("glacier_trident", Items.TRIDENT, "Glacier Trident", 0xBFEFFF, 18, 1.1, 55_000_000L,
                List.of("18 damage", "Freezes + Slowness III"), new LegendaryItems.Ench[]{e(Enchantments.IMPALING, 5), e(Enchantments.LOYALTY, 3)},
                List.of(fx(StatusEffects.SLOWNESS, 3, 2)), List.of(), 0, 0, 0, 160);
        add("royal_blade", Items.GOLDEN_SWORD, "Royal Blade", 0xFFD700, 22, 1.5, 100_000_000L,
                List.of("22 damage", "Regeneration I while held", "Lifesteal 10%"), new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 8), e(Enchantments.LOOTING, 4)},
                List.of(), List.of(fx(StatusEffects.REGENERATION, 2, 0)), 0, 0, 0.10, 0);
        add("voidwalker_dagger", Items.NETHERITE_SWORD, "Voidwalker Dagger", 0x5B2C83, 16, 2.5, 65_000_000L,
                List.of("16 damage, extremely fast", "Wither II + Darkness"), new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 8)},
                List.of(fx(StatusEffects.WITHER, 3, 1), fx(StatusEffects.DARKNESS, 3, 0)), List.of(), 0, 0, 0, 0);
        add("nether_king_axe", Items.GOLDEN_AXE, "Nether King Axe", 0xB22222, 24, 1.0, 75_000_000L,
                List.of("24 damage", "Burns for 5 seconds + Weakness"), new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 7), e(Enchantments.EFFICIENCY, 8)},
                List.of(fx(StatusEffects.WEAKNESS, 4, 0)), List.of(fx(StatusEffects.FIRE_RESISTANCE, 2, 0)), 5, 0, 0, 0);
    }

    public static final List<String> IDS = List.copyOf(SPECS.keySet());
    public static final Map<String, Long> PRICES = new LinkedHashMap<>();
    static { for (Spec s : SPECS.values()) PRICES.put(s.id(), s.price()); }

    public static ItemStack create(String id, RegistryWrapper.WrapperLookup lookup) {
        Spec s = SPECS.get(id);
        if (s == null) return ItemStack.EMPTY;
        return LegendaryItems.build(s.base(), s.id(), s.name(), s.color(), lookup, s.lore(), s.ench(),
                s.dmg() > 0 ? LegendaryItems.weapon(s.dmg(), s.speed()) : null, null);
    }

    private static Spec held(LivingEntity e) {
        if (!(e instanceof ServerPlayerEntity p)) return null;
        String id = LegendaryItems.idOf(p.getMainHandStack());
        return id == null ? null : SPECS.get(id);
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((target, source, base, taken, blocked) -> {
            if (blocked || !(source.getAttacker() instanceof ServerPlayerEntity p) || target == p) return;
            Spec s = held(p);
            if (s == null || !(target.getEntityWorld() instanceof ServerWorld w)) return;
            for (Fx f : s.onHit()) target.addStatusEffect(new StatusEffectInstance(f.effect(), f.seconds() * 20, f.amp()), p);
            if (s.fireSec() > 0) target.setOnFireFor(s.fireSec());
            if (s.freezeTicks() > 0) target.setFrozenTicks(Math.max(target.getFrozenTicks(), s.freezeTicks()));
            if (s.lifesteal() > 0 && taken > 0) p.heal(taken * (float) s.lifesteal());
            if (s.lightning() > 0 && ThreadLocalRandom.current().nextDouble() < s.lightning()) {
                LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.TRIGGERED);
                if (bolt != null) {
                    bolt.refreshPositionAfterTeleport(target.getX(), target.getY(), target.getZ());
                    bolt.setCosmetic(false);
                    w.spawnEntity(bolt);
                }
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 20 != 0) return;
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                Spec s = held(p);
                if (s == null) continue;
                for (Fx f : s.held()) p.addStatusEffect(new StatusEffectInstance(f.effect(), 50, f.amp(), true, false, true));
            }
        });
    }

    /** For the shop page layout. */
    public static List<String> page(int n) {
        List<String> out = new ArrayList<>();
        for (int i = n * 15; i < Math.min(IDS.size(), n * 15 + 15); i++) out.add(IDS.get(i));
        return out;
    }
}
