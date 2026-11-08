package com.dracos.core;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
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

    /** Right-click powers: id -> {power, cooldown seconds}. */
    record Power(String kind, int cooldown) {}
    private static final Map<String, Power> POWERS = new LinkedHashMap<>();
    /** Passive "berserk": Strength II while below 40% health. */
    private static final java.util.Set<String> BERSERK = new java.util.HashSet<>();
    private static final Map<String, Long> COOLDOWN = new java.util.HashMap<>();

    record ArmorSet(String key, String name, int color, List<String> bonus, List<Fx> effects) {}
    static final List<ArmorSet> ARMOR = new ArrayList<>();
    private static final Map<String, Long> ARMOR_PRICES = new LinkedHashMap<>();

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

        // ---------------- staffs (right-click powers)
        staff("inferno_staff", "Inferno Staff", 0xFF5A00, 8, 75_000_000L, "Right-click: shoot 3 fireballs", "fireball", 4);
        staff("frost_staff", "Frost Staff", 0x9FE7FF, 8, 70_000_000L, "Right-click: frost nova - freezes everyone near you", "frostnova", 10);
        staff("storm_staff", "Storm Staff", 0x4FA3FF, 8, 90_000_000L, "Right-click: call lightning where you look", "lightning", 6);
        staff("staff_of_life", "Staff of Life", 0x7CFC00, 6, 80_000_000L, "Right-click: heal yourself and nearby friends", "heal", 15);
        staff("void_staff", "Void Staff", 0x9D4DFF, 8, 85_000_000L, "Right-click: blink 10 blocks forward", "blink", 5);
        staff("wither_staff", "Wither Staff", 0x3A3A3A, 9, 95_000_000L, "Right-click: shoot a wither skull", "witherskull", 3);
        staff("nature_staff", "Nature Staff", 0x58D68D, 6, 60_000_000L, "Right-click: Regeneration II aura for 8s", "regen", 20);

        // ---------------- weapons with powers / passives
        power("wind_blade", Items.DIAMOND_SWORD, "Wind Blade", 0xD6F5FF, 17, 2.2, 65_000_000L,
                List.of("17 damage, fast", "Right-click: dash forward"), "dash", 4,
                new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 7)}, List.of(), List.of(fx(StatusEffects.SPEED, 2, 0)));
        power("meteor_hammer", Items.MACE, "Meteor Hammer", 0xFF6F00, 23, 0.8, 110_000_000L,
                List.of("23 damage", "Right-click: shockwave that knocks everyone up"), "shockwave", 8,
                new LegendaryItems.Ench[]{e(Enchantments.DENSITY, 5), e(Enchantments.BREACH, 4)}, List.of(fx(StatusEffects.SLOWNESS, 3, 1)), List.of());
        power("eclipse_scythe", Items.NETHERITE_HOE, "Eclipse Scythe", 0x2C1A4D, 24, 1.2, 100_000_000L,
                List.of("24 damage", "Right-click: dark shockwave + Wither"), "shockwave", 9,
                new LegendaryItems.Ench[]{e(Enchantments.LOOTING, 5)}, List.of(fx(StatusEffects.WITHER, 4, 1)), List.of());
        power("jumper_blade", Items.NETHERITE_SWORD, "Skyjumper Blade", 0x87CEEB, 19, 1.7, 55_000_000L,
                List.of("19 damage", "Right-click: leap high into the air", "No fall damage while held"), "leap", 5,
                new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 7)}, List.of(), List.of(fx(StatusEffects.SLOW_FALLING, 2, 0)));
        power("solar_greatsword", Items.GOLDEN_SWORD, "Solar Greatsword", 0xFFD54F, 26, 0.9, 105_000_000L,
                List.of("26 damage", "Right-click: lightning where you look", "Burns targets"), "lightning", 10,
                new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 8), e(Enchantments.FIRE_ASPECT, 2)}, List.of(), List.of());
        add("berserker_axe", Items.NETHERITE_AXE, "Berserker Axe", 0xC0392B, 24, 1.0, 80_000_000L,
                List.of("24 damage", "Strength II when you are below 40% health"),
                new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 8)}, List.of(), List.of(), 0, 0, 0, 0);
        BERSERK.add("berserker_axe");
        add("guardian_blade", Items.NETHERITE_SWORD, "Guardian Blade", 0xC9D6DF, 18, 1.6, 75_000_000L,
                List.of("18 damage", "Absorption II while held", "Resistance I while held"),
                new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 7)}, List.of(),
                List.of(fx(StatusEffects.ABSORPTION, 2, 1), fx(StatusEffects.RESISTANCE, 2, 0)), 0, 0, 0, 0);
        add("hunter_dagger", Items.DIAMOND_SWORD, "Hunter's Dagger", 0x8E6E53, 14, 2.6, 45_000_000L,
                List.of("14 damage, fastest weapon", "Speed II while held", "Targets glow"),
                new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 7), e(Enchantments.LOOTING, 4)},
                List.of(fx(StatusEffects.GLOWING, 8, 0)), List.of(fx(StatusEffects.SPEED, 2, 1)), 0, 0, 0, 0);
        add("crimson_rapier", Items.NETHERITE_SWORD, "Crimson Rapier", 0xDC143C, 15, 2.4, 60_000_000L,
                List.of("15 damage, extremely fast", "Bleeding (Wither) + lifesteal 8%"),
                new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 8)}, List.of(fx(StatusEffects.WITHER, 3, 0)), List.of(), 0, 0, 0.08, 0);
        add("thunder_spear", Items.TRIDENT, "Thunder Spear", 0xFFE14D, 20, 1.1, 90_000_000L,
                List.of("20 damage", "25% chance to strike lightning"),
                new LegendaryItems.Ench[]{e(Enchantments.IMPALING, 6), e(Enchantments.LOYALTY, 3)}, List.of(), List.of(), 0, 0.25, 0, 0);
        add("executioner_axe", Items.NETHERITE_AXE, "Executioner", 0x4A0000, 30, 0.6, 120_000_000L,
                List.of("30 damage - the strongest hit", "Very slow"),
                new LegendaryItems.Ench[]{e(Enchantments.SHARPNESS, 8)}, List.of(fx(StatusEffects.SLOWNESS, 2, 2)), List.of(), 0, 0, 0, 0);

        // ---------------- armor sets
        armorSet("frost", "Frost Guard", 0x9FE7FF, 60_000_000L,
                List.of("Frost Set (4 pieces):", " - Resistance I + Fire immunity to freezing", " - Attackers get Slowness III + frozen", " - Speed I"),
                List.of(fx(StatusEffects.RESISTANCE, 2, 0), fx(StatusEffects.SPEED, 2, 0)));
        armorSet("inferno", "Inferno Plate", 0xFF4500, 65_000_000L,
                List.of("Inferno Set (4 pieces):", " - Fire Resistance", " - Attackers are set on fire", " - Strength I"),
                List.of(fx(StatusEffects.FIRE_RESISTANCE, 2, 0), fx(StatusEffects.STRENGTH, 2, 0)));
        armorSet("storm", "Storm Rider", 0x4FA3FF, 70_000_000L,
                List.of("Storm Set (4 pieces):", " - Speed II + Jump Boost II", " - 20% chance to strike attackers with lightning", " - No fall damage"),
                List.of(fx(StatusEffects.SPEED, 2, 1), fx(StatusEffects.JUMP_BOOST, 2, 1), fx(StatusEffects.SLOW_FALLING, 2, 0)));
    }

    private static void staff(String id, String name, int color, double dmg, long price, String line, String power, int cd) {
        add(id, Items.STICK, name, color, dmg, 1.2, price, List.of(dmg + " damage", line),
                new LegendaryItems.Ench[]{e(Enchantments.KNOCKBACK, 2)}, List.of(), List.of(), 0, 0, 0, 0);
        POWERS.put(id, new Power(power, cd));
    }

    private static void power(String id, Item base, String name, int color, double dmg, double speed, long price, List<String> lore,
                              String power, int cd, LegendaryItems.Ench[] ench, List<Fx> hit, List<Fx> held) {
        add(id, base, name, color, dmg, speed, price, lore, ench, hit, held, 0, 0, 0, 0);
        POWERS.put(id, new Power(power, cd));
    }

    private static void armorSet(String key, String name, int color, long price, List<String> bonus, List<Fx> effects) {
        ARMOR.add(new ArmorSet(key, name, color, bonus, effects));
        String[] pieces = {"helmet", "chestplate", "leggings", "boots"};
        long[] mult = {50, 100, 85, 50};
        for (int i = 0; i < 4; i++) ARMOR_PRICES.put(key + "_" + pieces[i], price * mult[i] / 100);
    }

    static ArmorSet armorOf(String id) {
        for (ArmorSet a : ARMOR) if (id.startsWith(a.key() + "_") && ARMOR_PRICES.containsKey(id)) return a;
        return null;
    }

    public static final List<String> IDS;
    public static final Map<String, Long> PRICES = new LinkedHashMap<>();
    static {
        List<String> all = new ArrayList<>(SPECS.keySet());
        all.addAll(ARMOR_PRICES.keySet());
        IDS = List.copyOf(all);
        for (Spec s : SPECS.values()) PRICES.put(s.id(), s.price());
        PRICES.putAll(ARMOR_PRICES);
    }

    public static ItemStack create(String id, RegistryWrapper.WrapperLookup lookup) {
        ArmorSet set = armorOf(id);
        if (set != null) {
            String piece = id.substring(set.key().length() + 1);
            String nm = set.name() + " " + Character.toUpperCase(piece.charAt(0)) + piece.substring(1);
            return switch (piece) {
                case "helmet" -> LegendaryItems.armorPiece(Items.NETHERITE_HELMET, id, nm, set.color(), lookup, set.bonus(), set.key(),
                        EquipmentSlot.HEAD, AttributeModifierSlot.HEAD, 6, 6, 3, e(Enchantments.RESPIRATION, 3));
                case "chestplate" -> LegendaryItems.armorPiece(Items.NETHERITE_CHESTPLATE, id, nm, set.color(), lookup, set.bonus(), set.key(),
                        EquipmentSlot.CHEST, AttributeModifierSlot.CHEST, 14, 6, 3, e(Enchantments.THORNS, 4));
                case "leggings" -> LegendaryItems.armorPiece(Items.NETHERITE_LEGGINGS, id, nm, set.color(), lookup, set.bonus(), set.key(),
                        EquipmentSlot.LEGS, AttributeModifierSlot.LEGS, 11, 6, 3, e(Enchantments.SWIFT_SNEAK, 3));
                default -> LegendaryItems.armorPiece(Items.NETHERITE_BOOTS, id, nm, set.color(), lookup, set.bonus(), set.key(),
                        EquipmentSlot.FEET, AttributeModifierSlot.FEET, 6, 6, 3, e(Enchantments.FEATHER_FALLING, 6), e(Enchantments.DEPTH_STRIDER, 3));
            };
        }
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
                var inv = p.getInventory();
                for (int i = 0; i < inv.size(); i++) { LegendaryItems.fixHelmet(inv.getStack(i), p.getRegistryManager()); LegendaryItems.fixBlastProt(inv.getStack(i)); }
                for (var slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
                    LegendaryItems.fixBlastProt(p.getEquippedStack(slot));
                LegendaryItems.fixHelmet(p.getEquippedStack(EquipmentSlot.HEAD), p.getRegistryManager());
                for (ArmorSet a : ARMOR) if (fullSet(p, a)) for (Fx f : a.effects())
                    p.addStatusEffect(new StatusEffectInstance(f.effect(), 50, f.amp(), true, false, true));
                Spec s = held(p);
                if (s == null) continue;
                for (Fx f : s.held()) p.addStatusEffect(new StatusEffectInstance(f.effect(), 50, f.amp(), true, false, true));
                if (BERSERK.contains(s.id()) && p.getHealth() < p.getMaxHealth() * 0.4f)
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 50, 1, true, false, true));
            }
        });
        // armor set reactions when hit
        ServerLivingEntityEvents.AFTER_DAMAGE.register((victim, source, base, taken, blocked) -> {
            if (!(victim instanceof ServerPlayerEntity p) || !(source.getAttacker() instanceof LivingEntity att) || att == p) return;
            if (!(p.getEntityWorld() instanceof ServerWorld w)) return;
            for (ArmorSet a : ARMOR) {
                if (!fullSet(p, a)) continue;
                switch (a.key()) {
                    case "frost" -> { att.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 2), p); att.setFrozenTicks(160); }
                    case "inferno" -> att.setOnFireFor(4);
                    case "storm" -> { if (ThreadLocalRandom.current().nextDouble() < 0.2) bolt(w, att.getX(), att.getY(), att.getZ()); }
                    default -> {}
                }
            }
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> {
            if (victim instanceof ServerPlayerEntity p && source.isOf(net.minecraft.entity.damage.DamageTypes.FALL)) {
                for (ArmorSet a : ARMOR) if (a.key().equals("storm") && fullSet(p, a)) return false;
                Spec s = held(p);
                if (s != null && s.id().equals("jumper_blade")) return false;
            }
            if (victim instanceof ServerPlayerEntity p && source.isOf(net.minecraft.entity.damage.DamageTypes.FREEZE)) {
                for (ArmorSet a : ARMOR) if (a.key().equals("frost") && fullSet(p, a)) return false;
            }
            return true;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity p) || !(world instanceof ServerWorld w)) return ActionResult.PASS;
            String id = LegendaryItems.idOf(p.getStackInHand(hand));
            Power pw = id == null ? null : POWERS.get(id);
            if (pw == null) return ActionResult.PASS;
            String key = p.getUuidAsString() + "|" + id;
            long now = System.currentTimeMillis();
            Long until = COOLDOWN.get(key);
            if (until != null && until > now) {
                p.sendMessage(Text.literal("Cooldown: " + ((until - now) / 1000 + 1) + "s").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }
            COOLDOWN.put(key, now + pw.cooldown() * 1000L);
            usePower(p, w, pw.kind());
            return ActionResult.SUCCESS;
        });
    }

    static boolean fullSet(ServerPlayerEntity p, ArmorSet a) {
        return a.key().equals(armorKey(p.getEquippedStack(EquipmentSlot.HEAD))) && a.key().equals(armorKey(p.getEquippedStack(EquipmentSlot.CHEST)))
                && a.key().equals(armorKey(p.getEquippedStack(EquipmentSlot.LEGS))) && a.key().equals(armorKey(p.getEquippedStack(EquipmentSlot.FEET)));
    }

    private static String armorKey(ItemStack s) {
        String id = LegendaryItems.idOf(s);
        ArmorSet a = id == null ? null : armorOf(id);
        return a == null ? null : a.key();
    }

    private static void bolt(ServerWorld w, double x, double y, double z) {
        LightningEntity b = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.TRIGGERED);
        if (b != null) { b.refreshPositionAfterTeleport(x, y, z); w.spawnEntity(b); }
    }

    private static void push(ServerPlayerEntity p, Vec3d vel) {
        p.setVelocity(vel);
        p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p));
    }

    private static List<LivingEntity> near(ServerPlayerEntity p, double r, boolean enemies) {
        return p.getEntityWorld().getEntitiesByClass(LivingEntity.class, new Box(p.getBlockPos()).expand(r),
                e -> e != p && e.isAlive() && (enemies ? !(e instanceof ServerPlayerEntity sp && (sp.isCreative() || sp.isSpectator()))
                        && !(e instanceof net.minecraft.entity.passive.TameableEntity t && t.isTamed()) && !(e instanceof net.minecraft.entity.passive.VillagerEntity)
                        : e instanceof ServerPlayerEntity));
    }

    private static void usePower(ServerPlayerEntity p, ServerWorld w, String kind) {
        Vec3d look = p.getRotationVector();
        Vec3d eye = p.getEyePos();
        switch (kind) {
            case "fireball" -> {
                for (int i = -1; i <= 1; i++) {
                    Vec3d dir = look.rotateY(i * 0.12f).multiply(1.0);
                    SmallFireballEntity fb = new SmallFireballEntity(w, p, dir);
                    fb.setPosition(eye.x + look.x, eye.y + look.y - 0.1, eye.z + look.z);
                    w.spawnEntity(fb);
                }
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 1f, 1f);
            }
            case "witherskull" -> {
                WitherSkullEntity sk = new WitherSkullEntity(w, p, look);
                sk.setPosition(eye.x + look.x, eye.y + look.y - 0.1, eye.z + look.z);
                w.spawnEntity(sk);
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WITHER_SHOOT, SoundCategory.PLAYERS, 0.8f, 1f);
            }
            case "frostnova" -> {
                for (LivingEntity e : near(p, 6, true)) {
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 3), p);
                    e.setFrozenTicks(240);
                    e.damage(w, w.getDamageSources().playerAttack(p), 4f);
                }
                w.spawnParticles(net.minecraft.particle.ParticleTypes.SNOWFLAKE, p.getX(), p.getY() + 1, p.getZ(), 120, 3, 0.6, 3, 0.05);
                w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1f, 0.7f);
            }
            case "lightning" -> {
                HitResult hit = w.raycast(new RaycastContext(eye, eye.add(look.multiply(30)), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
                Vec3d at = hit.getPos();
                bolt(w, at.x, at.y, at.z);
            }
            case "heal" -> {
                p.heal(8f);
                for (LivingEntity e : near(p, 8, false)) e.heal(6f);
                w.spawnParticles(net.minecraft.particle.ParticleTypes.HEART, p.getX(), p.getY() + 1.5, p.getZ(), 12, 1.5, 0.5, 1.5, 0.1);
                w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 1.4f);
            }
            case "regen" -> {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 160, 1));
                for (LivingEntity e : near(p, 8, false)) e.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 160, 1));
                w.spawnParticles(net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1, p.getZ(), 40, 3, 0.5, 3, 0.1);
            }
            case "blink" -> {
                BlockHitResult hit = w.raycast(new RaycastContext(eye, eye.add(look.multiply(10)), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
                Vec3d to = hit.getType() == HitResult.Type.MISS ? eye.add(look.multiply(10)) : hit.getPos().subtract(look.multiply(0.8));
                p.requestTeleport(to.x, to.y - p.getStandingEyeHeight() + 0.1, to.z);
                p.fallDistance = 0;
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1f, 1.2f);
            }
            case "dash" -> {
                push(p, look.multiply(1.8).add(0, 0.25, 0));
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), SoundCategory.PLAYERS, 1f, 1.2f);
            }
            case "leap" -> {
                push(p, new Vec3d(look.x * 0.6, 1.4, look.z * 0.6));
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 1f, 1f);
            }
            case "shockwave" -> {
                for (LivingEntity e : near(p, 5, true)) {
                    e.damage(w, w.getDamageSources().playerAttack(p), 8f);
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 20, 3), p);
                }
                w.spawnParticles(net.minecraft.particle.ParticleTypes.EXPLOSION, p.getX(), p.getY() + 0.5, p.getZ(), 8, 2.5, 0.2, 2.5, 0);
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.8f, 1.2f);
            }
            default -> {}
        }
    }

    /** For the shop page layout. */
    public static List<String> page(int n) {
        List<String> out = new ArrayList<>();
        for (int i = n * 15; i < Math.min(IDS.size(), n * 15 + 15); i++) out.add(IDS.get(i));
        return out;
    }
}
