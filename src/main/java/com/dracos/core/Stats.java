package com.dracos.core;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Special stats shown on legendary items: stat block in the lore, crit chance, kill counter. */
public final class Stats {
    private Stats() {}

    public static final String KILLS = "dracos_kills";
    private static final String KILL_PREFIX = "☠ Kills: ";

    /** Crit chance (0..1) and crit multiplier per weapon. */
    public static final Map<String, double[]> CRIT = Map.of(
            LegendaryItems.VOID_SCYTHE, new double[]{0.25, 1.75},
            LegendaryItems.THUNDER_HAMMER, new double[]{0.20, 2.0},
            LegendaryItems.BONE_REAPER, new double[]{0.30, 1.6},
            LegendaryItems.DRAGON_BLADE, new double[]{0.20, 1.8},
            LegendaryItems.LAVA_AXE, new double[]{0.15, 1.7},
            LegendaryItems.VOID_BOW, new double[]{0.20, 1.5});

    /** Stat lines: label -> value. */
    public static final Map<String, Map<String, String>> LINES = new LinkedHashMap<>();
    static {
        w(LegendaryItems.VOID_SCYTHE, "28", "1.2", "+14 to all nearby", "3 hearts / hit");
        w(LegendaryItems.THUNDER_HAMMER, "22", "1.0", "Lightning 12 dmg (4 blocks)", "-");
        w(LegendaryItems.BONE_REAPER, "20", "2.0", "Wither II + Slowness", "-");
        w(LegendaryItems.DRAGON_BLADE, "18", "1.7", "Dragon fire 5s", "-");
        w(LegendaryItems.LAVA_AXE, "24", "1.0", "Fire 8s", "-");
        w(LegendaryItems.VOID_BOW, "x2 arrow", "-", "Levitation 2s", "-");
        Map<String, String> pick = new LinkedHashMap<>();
        pick.put("⛏ Mining", "3x3 + Haste II");
        pick.put("✦ Efficiency", "X");
        pick.put("✦ Fortune", "V");
        pick.put("∞ Durability", "Unbreakable");
        LINES.put(LegendaryItems.SHARD_PICKAXE, pick);
        a(LegendaryItems.VOID_SET, new int[]{6, 14, 11, 6}, 6, 3, "+2 dmg per piece");
        a(LegendaryItems.BONE_SET, new int[]{5, 12, 10, 5}, 5, 4, "Regen II set");
        a(LegendaryItems.DRACO_SET, new int[]{5, 11, 9, 5}, 4, 2, "Speed II set");
    }

    private static void w(String id, String dmg, String speed, String special, String lifesteal) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("⚔ Damage", dmg);
        if (!speed.equals("-")) m.put("⚡ Attack Speed", speed);
        double[] c = CRIT.get(id);
        if (c != null) m.put("✦ Crit", (int) (c[0] * 100) + "% for x" + c[1]);
        m.put("☄ Special", special);
        if (!lifesteal.equals("-")) m.put("❤ Lifesteal", lifesteal);
        m.put("∞ Durability", "Unbreakable");
        LINES.put(id, m);
    }

    private static void a(List<String> set, int[] armor, int tough, int hearts, String extra) {
        for (int i = 0; i < 4; i++) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("🛡 Armor", "+" + armor[i]);
            m.put("◆ Toughness", "+" + tough);
            m.put("❤ Health", "+" + hearts + " hearts");
            m.put("✦ Bonus", extra);
            m.put("✦ Protection", "VI");
            m.put("∞ Durability", "Unbreakable");
            LINES.put(set.get(i), m);
        }
    }

    public static boolean isWeapon(String id) {
        return LegendaryItems.WEAPONS.contains(id) && !id.equals(LegendaryItems.SHARD_PICKAXE);
    }

    /** Lore lines for the stat block (added by LegendaryItems.build). */
    public static List<Text> lore(String id, int kills) {
        List<Text> out = new ArrayList<>();
        Map<String, String> m = LINES.get(id);
        if (m == null) return out;
        out.add(Text.literal("STATS").styled(s -> s.withColor(0x6FE3FF).withBold(true).withItalic(false)));
        for (Map.Entry<String, String> e : m.entrySet()) {
            MutableText line = Text.literal(" " + e.getKey() + ": ").styled(s -> s.withColor(Formatting.DARK_GRAY).withItalic(false))
                    .append(Text.literal(e.getValue()).styled(s -> s.withColor(Formatting.WHITE).withItalic(false)));
            out.add(line);
        }
        if (isWeapon(id)) out.add(killLine(kills));
        return out;
    }

    private static Text killLine(int kills) {
        return Text.literal(" " + KILL_PREFIX).styled(s -> s.withColor(Formatting.DARK_GRAY).withItalic(false))
                .append(Text.literal(String.valueOf(kills)).styled(s -> s.withColor(Formatting.RED).withBold(true).withItalic(false)));
    }

    private static boolean critting = false;

    public static void register() {
        // crits: extra hit right after the normal one
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (critting || blocked || taken <= 0) return;
            if (!(source.getAttacker() instanceof ServerPlayerEntity p)) return;
            String id = LegendaryItems.idOf(p.getMainHandStack());
            if (id == null) id = LegendaryItems.idOf(p.getOffHandStack());
            double[] c = id == null ? null : CRIT.get(id);
            if (c == null || ThreadLocalRandom.current().nextDouble() >= c[0]) return;
            if (!(entity.getEntityWorld() instanceof ServerWorld w) || !entity.isAlive()) return;
            try {
                critting = true;
                entity.timeUntilRegen = 0;
                entity.damage(w, source, (float) (taken * (c[1] - 1.0)));
            } finally {
                critting = false;
            }
            w.spawnParticles(ParticleTypes.CRIT, entity.getX(), entity.getBodyY(0.6), entity.getZ(), 25, 0.4, 0.5, 0.4, 0.4);
            w.spawnParticles(ParticleTypes.ENCHANTED_HIT, entity.getX(), entity.getBodyY(0.6), entity.getZ(), 15, 0.4, 0.5, 0.4, 0.3);
            w.playSound(null, entity.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1f, 0.8f);
            p.sendMessage(Text.literal("✦ CRITICAL HIT! ✦").formatted(Formatting.GOLD, Formatting.BOLD), true);
        });

        // kill counter on the weapon
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(source.getAttacker() instanceof ServerPlayerEntity p)) return;
            ItemStack stack = p.getMainHandStack();
            String id = LegendaryItems.idOf(stack);
            if (id == null || !isWeapon(id)) return;
            NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
            NbtCompound nbt = data != null ? data.copyNbt() : new NbtCompound();
            int kills = nbt.getInt(KILLS, 0) + 1;
            nbt.putInt(KILLS, kills);
            stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
            LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore != null) {
                List<Text> lines = new ArrayList<>(lore.lines());
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).getString().contains(KILL_PREFIX)) {
                        lines.set(i, killLine(kills));
                        break;
                    }
                }
                stack.set(DataComponentTypes.LORE, new LoreComponent(lines));
            }
        });
    }
}
