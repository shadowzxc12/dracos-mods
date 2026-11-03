package com.dracos.core;

import net.minecraft.component.DataComponentTypes;
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
    public static final String DRACO_HELMET = "draco_helmet";
    public static final String DRACO_CHESTPLATE = "draco_chestplate";
    public static final String DRACO_LEGGINGS = "draco_leggings";
    public static final String DRACO_BOOTS = "draco_boots";

    public static final List<String> IDS = List.of(SHARD_PICKAXE, DRAGON_BLADE, LAVA_AXE, VOID_BOW,
            DRACO_HELMET, DRACO_CHESTPLATE, DRACO_LEGGINGS, DRACO_BOOTS);

    /** Ceny v /legendary. */
    public static final Map<String, Long> PRICES = new LinkedHashMap<>();
    static {
        PRICES.put(SHARD_PICKAXE, 250_000L);
        PRICES.put(DRAGON_BLADE, 300_000L);
        PRICES.put(LAVA_AXE, 200_000L);
        PRICES.put(VOID_BOW, 200_000L);
        PRICES.put(DRACO_HELMET, 150_000L);
        PRICES.put(DRACO_CHESTPLATE, 250_000L);
        PRICES.put(DRACO_LEGGINGS, 200_000L);
        PRICES.put(DRACO_BOOTS, 150_000L);
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
            case SHARD_PICKAXE -> build(Items.NETHERITE_PICKAXE, id, "Shard Pickaxe", 0x6FE3FF, lookup,
                    List.of("Mines 3x3 at once", "Hold SHIFT to mine 1 block"),
                    new Ench[]{e(Enchantments.EFFICIENCY, 6), e(Enchantments.UNBREAKING, 5), e(Enchantments.FORTUNE, 3), e(Enchantments.MENDING, 1)},
                    null);
            case DRAGON_BLADE -> build(Items.NETHERITE_SWORD, id, "Dragon Blade", 0xB14BFF, lookup,
                    List.of("12 damage", "Right-click: Dragon Dash (3s cooldown)"),
                    new Ench[]{e(Enchantments.SHARPNESS, 6), e(Enchantments.UNBREAKING, 5), e(Enchantments.LOOTING, 3), e(Enchantments.MENDING, 1)},
                    weapon(12.0, 1.6));
            case LAVA_AXE -> build(Items.NETHERITE_AXE, id, "Lava Axe", 0xFF6A00, lookup,
                    List.of("Sets every target on fire", "Chops the whole tree at once"),
                    new Ench[]{e(Enchantments.EFFICIENCY, 5), e(Enchantments.UNBREAKING, 5), e(Enchantments.MENDING, 1)},
                    weapon(13.0, 1.0));
            case VOID_BOW -> build(Items.BOW, id, "Void Bow", 0x3B2BFF, lookup,
                    List.of("Arrows fly straight - no gravity", "+50% arrow speed & damage"),
                    new Ench[]{e(Enchantments.POWER, 6), e(Enchantments.UNBREAKING, 5), e(Enchantments.INFINITY, 1)},
                    null);
            case DRACO_HELMET -> build(Items.NETHERITE_HELMET, id, "Draco Helmet", 0xFFB000, lookup,
                    setLore(), protection(), armor(AttributeModifierSlot.HEAD, 4, 4, 2));
            case DRACO_CHESTPLATE -> build(Items.NETHERITE_CHESTPLATE, id, "Draco Chestplate", 0xFFB000, lookup,
                    setLore(), protection(), armor(AttributeModifierSlot.CHEST, 10, 4, 4));
            case DRACO_LEGGINGS -> build(Items.NETHERITE_LEGGINGS, id, "Draco Leggings", 0xFFB000, lookup,
                    setLore(), protection(), armor(AttributeModifierSlot.LEGS, 8, 4, 2));
            case DRACO_BOOTS -> build(Items.NETHERITE_BOOTS, id, "Draco Boots", 0xFFB000, lookup,
                    setLore(), new Ench[]{e(Enchantments.PROTECTION, 5), e(Enchantments.UNBREAKING, 5),
                            e(Enchantments.FEATHER_FALLING, 4), e(Enchantments.MENDING, 1)},
                    armor(AttributeModifierSlot.FEET, 4, 4, 2));
            default -> ItemStack.EMPTY;
        };
    }

    // ---------------------------------------------------------------- helpers

    private record Ench(RegistryKey<Enchantment> key, int level) {}

    private static Ench e(RegistryKey<Enchantment> key, int level) {
        return new Ench(key, level);
    }

    private static Ench[] protection() {
        return new Ench[]{e(Enchantments.PROTECTION, 5), e(Enchantments.UNBREAKING, 5), e(Enchantments.MENDING, 1)};
    }

    private static List<String> setLore() {
        return List.of("Draco Set (4 pieces):", " - Speed I", " - Fire Resistance", " - +2 hearts per piece");
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

    private static AttributeModifiersComponent armor(AttributeModifierSlot slot, double armor, double toughness, double hearts) {
        String s = slot.asString();
        return AttributeModifiersComponent.builder()
                .add(EntityAttributes.ARMOR, mod("armor_" + s, armor), slot)
                .add(EntityAttributes.ARMOR_TOUGHNESS, mod("toughness_" + s, toughness), slot)
                .add(EntityAttributes.KNOCKBACK_RESISTANCE, mod("kb_" + s, 0.15), slot)
                .add(EntityAttributes.MAX_HEALTH, mod("health_" + s, hearts * 2), slot)
                .build();
    }

    private static EntityAttributeModifier mod(String name, double value) {
        return new EntityAttributeModifier(Identifier.of(DracosCore.MOD_ID, name), value,
                EntityAttributeModifier.Operation.ADD_VALUE);
    }

    private static ItemStack build(Item base, String id, String name, int color, RegistryWrapper.WrapperLookup lookup,
                                   List<String> abilities, Ench[] enchants, AttributeModifiersComponent attrs) {
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
        lore.add(Text.literal("Dracos SMP").styled(st -> st.withColor(0xFF8A00).withItalic(false)));
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));

        NbtCompound nbt = new NbtCompound();
        nbt.putString(TAG, id);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));

        stack.set(DataComponentTypes.RARITY, Rarity.EPIC);
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        if (attrs != null) stack.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, attrs);

        RegistryWrapper.Impl<Enchantment> reg = lookup.getOrThrow(RegistryKeys.ENCHANTMENT);
        for (Ench en : enchants) {
            reg.getOptional(en.key()).ifPresent(entry -> stack.addEnchantment(entry, en.level()));
        }
        return stack;
    }
}
