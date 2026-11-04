package com.dracos.core;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.util.Unit;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Legendarne zbrane, naradie a armor. Zaklad je vanilla item, takze hraci nepotrebuju mod. */
public final class LegendaryItems {
    private LegendaryItems() {}

    public static final String TAG = "dracos_item";

    public static final String SHARD_PICKAXE = "shard_pickaxe";
    public static final String DRAGON_BLADE = "dragon_blade";
    public static final String LAVA_AXE = "lava_axe";
    public static final String VOID_BOW = "void_bow";
    public static final String VOID_SCYTHE = "void_scythe";
    public static final String BONE_REAPER = "bone_reaper";
    public static final String THUNDER_HAMMER = "thunder_hammer";
    public static final String FROST_TRIDENT = "frost_trident";
    public static final String BLOOD_KATANA = "blood_katana";
    public static final String SHADOW_DAGGER = "shadow_dagger";
    public static final String EARTH_DRILL = "earth_drill";
    public static final String TITAN_SHIELD = "titan_shield";
    public static final String PHOENIX_ELYTRA = "phoenix_elytra";
    public static final String DRACO_HELMET = "draco_helmet";
    public static final String DRACO_CHESTPLATE = "draco_chestplate";
    public static final String DRACO_LEGGINGS = "draco_leggings";
    public static final String DRACO_BOOTS = "draco_boots";
    public static final String VOID_HELMET = "void_helmet";
    public static final String VOID_CHESTPLATE = "void_chestplate";
    public static final String VOID_LEGGINGS = "void_leggings";
    public static final String VOID_BOOTS = "void_boots";
    public static final String BONE_HELMET = "bone_helmet";
    public static final String BONE_CHESTPLATE = "bone_chestplate";
    public static final String BONE_LEGGINGS = "bone_leggings";
    public static final String BONE_BOOTS = "bone_boots";

    public static final List<String> WEAPONS = List.of(VOID_SCYTHE, THUNDER_HAMMER, BONE_REAPER, DRAGON_BLADE,
            VOID_BOW, SHARD_PICKAXE, LAVA_AXE);
    public static final List<String> EXTRAS = List.of(BLOOD_KATANA, FROST_TRIDENT, SHADOW_DAGGER, PHOENIX_ELYTRA,
            TITAN_SHIELD, EARTH_DRILL);
    public static final List<String> DRACO_SET = List.of(DRACO_HELMET, DRACO_CHESTPLATE, DRACO_LEGGINGS, DRACO_BOOTS);
    public static final List<String> VOID_SET = List.of(VOID_HELMET, VOID_CHESTPLATE, VOID_LEGGINGS, VOID_BOOTS);
    public static final List<String> BONE_SET = List.of(BONE_HELMET, BONE_CHESTPLATE, BONE_LEGGINGS, BONE_BOOTS);

    public static final List<String> IDS;
    static {
        List<String> all = new ArrayList<>(WEAPONS);
        all.addAll(EXTRAS);
        all.addAll(VOID_SET);
        all.addAll(BONE_SET);
        all.addAll(DRACO_SET);
        IDS = List.copyOf(all);
    }

    /** Prices in /shop -> Legendary and /legendary. */
    public static final Map<String, Long> PRICES = new LinkedHashMap<>();
    static {
        PRICES.put(VOID_SCYTHE, 100_000_000L);
        PRICES.put(THUNDER_HAMMER, 75_000_000L);
        PRICES.put(BONE_REAPER, 60_000_000L);
        PRICES.put(DRAGON_BLADE, 50_000_000L);
        PRICES.put(VOID_BOW, 35_000_000L);
        PRICES.put(SHARD_PICKAXE, 25_000_000L);
        PRICES.put(LAVA_AXE, 20_000_000L);
        PRICES.put(BLOOD_KATANA, 70_000_000L);
        PRICES.put(FROST_TRIDENT, 55_000_000L);
        PRICES.put(SHADOW_DAGGER, 45_000_000L);
        PRICES.put(PHOENIX_ELYTRA, 90_000_000L);
        PRICES.put(TITAN_SHIELD, 40_000_000L);
        PRICES.put(EARTH_DRILL, 20_000_000L);
        PRICES.put(VOID_HELMET, 40_000_000L);
        PRICES.put(VOID_CHESTPLATE, 80_000_000L);
        PRICES.put(VOID_LEGGINGS, 70_000_000L);
        PRICES.put(VOID_BOOTS, 40_000_000L);
        PRICES.put(BONE_HELMET, 30_000_000L);
        PRICES.put(BONE_CHESTPLATE, 60_000_000L);
        PRICES.put(BONE_LEGGINGS, 50_000_000L);
        PRICES.put(BONE_BOOTS, 30_000_000L);
        PRICES.put(DRACO_HELMET, 15_000_000L);
        PRICES.put(DRACO_CHESTPLATE, 30_000_000L);
        PRICES.put(DRACO_LEGGINGS, 25_000_000L);
        PRICES.put(DRACO_BOOTS, 15_000_000L);
    }

    public static String idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null) return null;
        String id = data.copyNbt().getString(TAG, "");
        return id.isEmpty() ? null : id;
    }

    public static boolean is(ItemStack stack, String id) {
        return id.equals(idOf(stack));
    }

    public static ItemStack create(String id, RegistryWrapper.WrapperLookup lookup) {
        return switch (id) {
            // ------------------------------------------------------------ weapons & tools
            case VOID_SCYTHE -> build(Items.NETHERITE_SWORD, id, "Void Scythe", 0x9D4DFF, lookup,
                    List.of("28 damage", "Every hit slices all enemies around you", "Lifesteal: heals 3 hearts per hit",
                            "Right-click: Void Pull - drags enemies in (8s cooldown)"),
                    new Ench[]{e(Enchantments.SHARPNESS, 10), e(Enchantments.SWEEPING_EDGE, 5), e(Enchantments.LOOTING, 5),
                            e(Enchantments.FIRE_ASPECT, 2)},
                    weapon(28.0, 1.2), null);
            case THUNDER_HAMMER -> build(Items.MACE, id, "Thunder Hammer", 0xFFE14D, lookup,
                    List.of("22 damage", "Right-click: call lightning where you look", "Blast hits & launches enemies (6s cooldown)"),
                    new Ench[]{e(Enchantments.DENSITY, 5), e(Enchantments.WIND_BURST, 3), e(Enchantments.FIRE_ASPECT, 2)},
                    weapon(22.0, 1.0), null);
            case BONE_REAPER -> build(Items.NETHERITE_SWORD, id, "Bone Reaper", 0xF2E8C9, lookup,
                    List.of("20 damage, very fast", "Hits: Wither II + Slowness", "Right-click: Bone Shield (25s cooldown)"),
                    new Ench[]{e(Enchantments.SHARPNESS, 8), e(Enchantments.LOOTING, 5), e(Enchantments.KNOCKBACK, 2)},
                    weapon(20.0, 2.0), null);
            case DRAGON_BLADE -> build(Items.NETHERITE_SWORD, id, "Dragon Blade", 0xB14BFF, lookup,
                    List.of("18 damage", "Right-click: Dragon Dash (2s cooldown)", "Hits set enemies on dragon fire"),
                    new Ench[]{e(Enchantments.SHARPNESS, 8), e(Enchantments.LOOTING, 4), e(Enchantments.FIRE_ASPECT, 3)},
                    weapon(18.0, 1.7), null);
            case VOID_BOW -> build(Items.BOW, id, "Void Bow", 0x7A3BFF, lookup,
                    List.of("Arrows fly straight - no gravity", "Double arrow speed & damage", "Hits pull targets into the void (Levitation)"),
                    new Ench[]{e(Enchantments.POWER, 10), e(Enchantments.PUNCH, 2), e(Enchantments.FLAME, 1), e(Enchantments.INFINITY, 1)},
                    null, null);
            case SHARD_PICKAXE -> build(Items.NETHERITE_PICKAXE, id, "Shard Pickaxe", 0x6FE3FF, lookup,
                    List.of("Mines 3x3 at once", "Haste II while held", "Hold SHIFT to mine 1 block"),
                    new Ench[]{e(Enchantments.EFFICIENCY, 10), e(Enchantments.FORTUNE, 5)},
                    null, null);
            case LAVA_AXE -> build(Items.NETHERITE_AXE, id, "Lava Axe", 0xFF6A00, lookup,
                    List.of("24 damage", "Sets every target on fire", "Chops the whole tree at once"),
                    new Ench[]{e(Enchantments.EFFICIENCY, 8), e(Enchantments.SHARPNESS, 6)},
                    weapon(24.0, 1.0), null);
            case BLOOD_KATANA -> build(Items.NETHERITE_SWORD, id, "Blood Katana", 0xE0102A, lookup,
                    List.of("21 damage, fast", "Lifesteal: heals 30% of damage dealt", "Hits make enemies bleed (Wither)"),
                    new Ench[]{e(Enchantments.SHARPNESS, 9), e(Enchantments.LOOTING, 4), e(Enchantments.SWEEPING_EDGE, 3)},
                    weapon(21.0, 1.9), null);
            case FROST_TRIDENT -> build(Items.TRIDENT, id, "Frost Trident", 0x7FE8FF, lookup,
                    List.of("19 damage", "Hits freeze: Slowness IV + frost", "Loyalty - always comes back"),
                    new Ench[]{e(Enchantments.IMPALING, 7), e(Enchantments.LOYALTY, 3), e(Enchantments.CHANNELING, 1)},
                    weapon(19.0, 1.1), null);
            case SHADOW_DAGGER -> build(Items.NETHERITE_SWORD, id, "Shadow Dagger", 0x3B3B55, lookup,
                    List.of("15 damage, very fast", "Hits from behind deal double damage", "Kill: invisible + Speed III for 4s"),
                    new Ench[]{e(Enchantments.SHARPNESS, 7), e(Enchantments.LOOTING, 5)},
                    weapon(15.0, 2.6), null);
            case PHOENIX_ELYTRA -> build(Items.ELYTRA, id, "Phoenix Elytra", 0xFF5A1A, lookup,
                    List.of("Unbreakable wings", "Rebirth: survive a deadly hit (10 min cooldown)", "Fire Resistance while worn"),
                    new Ench[]{e(Enchantments.PROTECTION, 6)},
                    null, null);
            case TITAN_SHIELD -> build(Items.SHIELD, id, "Titan Shield", 0xC9A227, lookup,
                    List.of("Blocking reflects 50% damage back", "Knocks attackers away", "Resistance I while held"),
                    new Ench[]{e(Enchantments.THORNS, 3)},
                    null, null);
            case EARTH_DRILL -> build(Items.NETHERITE_SHOVEL, id, "Earth Drill", 0x8B5A2B, lookup,
                    List.of("Digs 3x3 at once", "Haste II while held", "Hold SHIFT to dig 1 block"),
                    new Ench[]{e(Enchantments.EFFICIENCY, 10), e(Enchantments.FORTUNE, 3)},
                    null, null);

            // ------------------------------------------------------------ Void set
            case VOID_HELMET -> armorPiece(Items.NETHERITE_HELMET, id, "Void Helmet", 0x9D4DFF, lookup, voidLore(), "void",
                    EquipmentSlot.HEAD, AttributeModifierSlot.HEAD, 6, 6, 3, e(Enchantments.RESPIRATION, 3), e(Enchantments.AQUA_AFFINITY, 1));
            case VOID_CHESTPLATE -> armorPiece(Items.NETHERITE_CHESTPLATE, id, "Void Chestplate", 0x9D4DFF, lookup, voidLore(), "void",
                    EquipmentSlot.CHEST, AttributeModifierSlot.CHEST, 14, 6, 3, e(Enchantments.THORNS, 5));
            case VOID_LEGGINGS -> armorPiece(Items.NETHERITE_LEGGINGS, id, "Void Leggings", 0x9D4DFF, lookup, voidLore(), "void",
                    EquipmentSlot.LEGS, AttributeModifierSlot.LEGS, 11, 6, 3, e(Enchantments.SWIFT_SNEAK, 5));
            case VOID_BOOTS -> armorPiece(Items.NETHERITE_BOOTS, id, "Void Boots", 0x9D4DFF, lookup, voidLore(), "void",
                    EquipmentSlot.FEET, AttributeModifierSlot.FEET, 6, 6, 3, e(Enchantments.FEATHER_FALLING, 10), e(Enchantments.DEPTH_STRIDER, 3), e(Enchantments.SOUL_SPEED, 5));

            // ------------------------------------------------------------ Bone set
            case BONE_HELMET -> armorPiece(Items.NETHERITE_HELMET, id, "Bone Helmet", 0xF2E8C9, lookup, boneLore(), "bone",
                    EquipmentSlot.HEAD, AttributeModifierSlot.HEAD, 5, 5, 4, e(Enchantments.RESPIRATION, 3), e(Enchantments.AQUA_AFFINITY, 1));
            case BONE_CHESTPLATE -> armorPiece(Items.NETHERITE_CHESTPLATE, id, "Bone Chestplate", 0xF2E8C9, lookup, boneLore(), "bone",
                    EquipmentSlot.CHEST, AttributeModifierSlot.CHEST, 12, 5, 4, e(Enchantments.THORNS, 3));
            case BONE_LEGGINGS -> armorPiece(Items.NETHERITE_LEGGINGS, id, "Bone Leggings", 0xF2E8C9, lookup, boneLore(), "bone",
                    EquipmentSlot.LEGS, AttributeModifierSlot.LEGS, 10, 5, 4, e(Enchantments.SWIFT_SNEAK, 3));
            case BONE_BOOTS -> armorPiece(Items.NETHERITE_BOOTS, id, "Bone Boots", 0xF2E8C9, lookup, boneLore(), "bone",
                    EquipmentSlot.FEET, AttributeModifierSlot.FEET, 5, 5, 4, e(Enchantments.FEATHER_FALLING, 10), e(Enchantments.DEPTH_STRIDER, 3));

            // ------------------------------------------------------------ Draco set
            case DRACO_HELMET -> armorPiece(Items.NETHERITE_HELMET, id, "Draco Helmet", 0xFFB000, lookup, dracoLore(), "draco",
                    EquipmentSlot.HEAD, AttributeModifierSlot.HEAD, 5, 4, 2, e(Enchantments.RESPIRATION, 3));
            case DRACO_CHESTPLATE -> armorPiece(Items.NETHERITE_CHESTPLATE, id, "Draco Chestplate", 0xFFB000, lookup, dracoLore(), "draco",
                    EquipmentSlot.CHEST, AttributeModifierSlot.CHEST, 11, 4, 2);
            case DRACO_LEGGINGS -> armorPiece(Items.NETHERITE_LEGGINGS, id, "Draco Leggings", 0xFFB000, lookup, dracoLore(), "draco",
                    EquipmentSlot.LEGS, AttributeModifierSlot.LEGS, 9, 4, 2);
            case DRACO_BOOTS -> armorPiece(Items.NETHERITE_BOOTS, id, "Draco Boots", 0xFFB000, lookup, dracoLore(), "draco",
                    EquipmentSlot.FEET, AttributeModifierSlot.FEET, 5, 4, 2, e(Enchantments.FEATHER_FALLING, 6));
            default -> ItemStack.EMPTY;
        };
    }

    public static List<String> voidLore() {
        return List.of("Void Set (4 pieces):", " - Strength II", " - Resistance I", " - Speed I + Night Vision",
                " - +3 hearts & +2 damage per piece");
    }

    public static List<String> boneLore() {
        return List.of("Bone Set (4 pieces):", " - Regeneration II", " - Resistance I", " - Jump Boost II",
                " - +4 hearts per piece");
    }

    public static List<String> dracoLore() {
        return List.of("Draco Set (4 pieces):", " - Speed II", " - Fire Resistance", " - Haste II",
                " - +2 hearts per piece");
    }

    // ---------------------------------------------------------------- helpers

    private record Ench(RegistryKey<Enchantment> key, int level) {}

    private static Ench e(RegistryKey<Enchantment> key, int level) {
        return new Ench(key, level);
    }

    private static AttributeModifiersComponent weapon(double damage, double speed) {
        // hrac ma zaklad 1 damage a 4 attack speed
        return AttributeModifiersComponent.builder()
                .add(EntityAttributes.ATTACK_DAMAGE,
                        new EntityAttributeModifier(Identifier.of(DracosCore.MOD_ID, "weapon_damage"), damage - 1.0,
                                EntityAttributeModifier.Operation.ADD_VALUE),
                        AttributeModifierSlot.MAINHAND)
                .add(EntityAttributes.ATTACK_SPEED,
                        new EntityAttributeModifier(Identifier.of(DracosCore.MOD_ID, "weapon_speed"), speed - 4.0,
                                EntityAttributeModifier.Operation.ADD_VALUE),
                        AttributeModifierSlot.MAINHAND)
                .build();
    }

    private static ItemStack armorPiece(Item base, String id, String name, int color, RegistryWrapper.WrapperLookup lookup,
                                        List<String> lore, String asset, EquipmentSlot eq, AttributeModifierSlot slot,
                                        double armor, double toughness, double hearts, Ench... extra) {
        Ench[] ench = new Ench[2 + extra.length];
        ench[0] = e(Enchantments.PROTECTION, 6);
        ench[1] = e(Enchantments.BLAST_PROTECTION, 4);
        System.arraycopy(extra, 0, ench, 2, extra.length);
        String s = slot.asString();
        var b = AttributeModifiersComponent.builder()
                .add(EntityAttributes.ARMOR, mod("armor_" + s, armor), slot)
                .add(EntityAttributes.ARMOR_TOUGHNESS, mod("toughness_" + s, toughness), slot)
                .add(EntityAttributes.KNOCKBACK_RESISTANCE, mod("kb_" + s, 0.2), slot)
                .add(EntityAttributes.MAX_HEALTH, mod("health_" + s, hearts * 2), slot);
        if (asset.equals("void")) b.add(EntityAttributes.ATTACK_DAMAGE, mod("dmg_" + s, 2), slot);
        return build(base, id, name, color, lookup, lore, ench, b.build(), new Equip(eq, asset));
    }

    private record Equip(EquipmentSlot slot, String asset) {}

    private static EntityAttributeModifier mod(String name, double value) {
        return new EntityAttributeModifier(Identifier.of(DracosCore.MOD_ID, name), value,
                EntityAttributeModifier.Operation.ADD_VALUE);
    }

    private static ItemStack build(Item base, String id, String name, int color, RegistryWrapper.WrapperLookup lookup,
                                   List<String> abilities, Ench[] enchants, AttributeModifiersComponent attrs, Equip equip) {
        ItemStack stack = new ItemStack(base);

        stack.set(DataComponentTypes.ITEM_NAME, Text.literal(name)
                .styled(st -> st.withColor(color).withBold(true).withItalic(false)));

        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal("LEGENDARY").styled(st -> st.withColor(0xFFD000).withBold(true).withItalic(false)));
        lore.add(Text.empty());
        for (String a : abilities) {
            MutableText line = Text.literal(a.startsWith(" ") ? a : "» " + a)
                    .styled(st -> st.withColor(Formatting.GRAY).withItalic(false));
            lore.add(line);
        }
        lore.add(Text.empty());
        List<Text> stats = Stats.lore(id, 0);
        if (!stats.isEmpty()) {
            lore.addAll(stats);
            lore.add(Text.empty());
        }
        lore.add(Text.literal("Dracos SMP").styled(st -> st.withColor(0xFF8A00).withItalic(false)));
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));

        NbtCompound nbt = new NbtCompound();
        nbt.putString(TAG, id);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));

        stack.set(DataComponentTypes.RARITY, Rarity.EPIC);
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false); // custom texture from the server pack
        stack.set(DataComponentTypes.UNBREAKABLE, Unit.INSTANCE);
        if (!id.equals(TITAN_SHIELD)) stack.set(DataComponentTypes.ITEM_MODEL, Identifier.of(DracosCore.MOD_ID, id));
        else stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        if (attrs != null) stack.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, attrs);
        if (equip != null) {
            JsonObject json = new JsonObject();
            json.addProperty("slot", equip.slot().asString());
            json.addProperty("asset_id", DracosCore.MOD_ID + ":" + equip.asset());
            json.addProperty("equip_sound", "minecraft:item.armor.equip_netherite");
            EquippableComponent.CODEC.parse(lookup.getOps(JsonOps.INSTANCE), json).result()
                    .ifPresentOrElse(c -> stack.set(DataComponentTypes.EQUIPPABLE, c),
                            () -> DracosCore.LOGGER.warn("Could not build equippable for {}", id));
        }

        RegistryWrapper.Impl<Enchantment> reg = lookup.getOrThrow(RegistryKeys.ENCHANTMENT);
        for (Ench en : enchants) {
            reg.getOptional(en.key()).ifPresent(entry -> stack.addEnchantment(entry, en.level()));
        }
        return stack;
    }
}
