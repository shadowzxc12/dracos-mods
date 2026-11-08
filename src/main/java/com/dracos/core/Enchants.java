package com.dracos.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Block;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Custom enchantments. They are real enchantments (data/dracos_core/enchantment/*.json) so they show up in the
 * enchanting table, on books and in anvils for normal clients. Most work purely from data (Lifesteal, Venom,
 * Thunderlord, Swift, Vitality...); the ones below need code:
 *   Telekinesis - block drops go straight into your inventory
 *   Autosmelt   - ores/sand/cobble drop smelted
 *   Wisdom      - extra XP from ores
 *   Clarity     - night vision while wearing the helmet
 *   Nourish     - slowly refills hunger while wearing the helmet
 */
public final class Enchants {
    private Enchants() {}

    public static final List<String> ALL = List.of("lifesteal", "venom", "thunderlord", "frostbite", "blinding", "withering",
            "thunder_arrows", "hunter", "swift", "leaper", "vitality", "molten", "clarity", "nourish", "telekinesis", "autosmelt", "wisdom");

    static final Map<Item, Item> SMELT = Map.ofEntries(
            Map.entry(Items.RAW_IRON, Items.IRON_INGOT), Map.entry(Items.RAW_GOLD, Items.GOLD_INGOT), Map.entry(Items.RAW_COPPER, Items.COPPER_INGOT),
            Map.entry(Items.IRON_ORE, Items.IRON_INGOT), Map.entry(Items.DEEPSLATE_IRON_ORE, Items.IRON_INGOT),
            Map.entry(Items.GOLD_ORE, Items.GOLD_INGOT), Map.entry(Items.DEEPSLATE_GOLD_ORE, Items.GOLD_INGOT),
            Map.entry(Items.COPPER_ORE, Items.COPPER_INGOT), Map.entry(Items.DEEPSLATE_COPPER_ORE, Items.COPPER_INGOT),
            Map.entry(Items.ANCIENT_DEBRIS, Items.NETHERITE_SCRAP), Map.entry(Items.COBBLESTONE, Items.STONE),
            Map.entry(Items.COBBLED_DEEPSLATE, Items.DEEPSLATE), Map.entry(Items.SAND, Items.GLASS), Map.entry(Items.RED_SAND, Items.GLASS),
            Map.entry(Items.NETHERRACK, Items.NETHER_BRICK), Map.entry(Items.CLAY_BALL, Items.BRICK), Map.entry(Items.KELP, Items.DRIED_KELP),
            Map.entry(Items.CACTUS, Items.GREEN_DYE), Map.entry(Items.WET_SPONGE, Items.SPONGE), Map.entry(Items.STONE_BRICKS, Items.CRACKED_STONE_BRICKS),
            Map.entry(Items.OAK_LOG, Items.CHARCOAL), Map.entry(Items.SPRUCE_LOG, Items.CHARCOAL), Map.entry(Items.BIRCH_LOG, Items.CHARCOAL),
            Map.entry(Items.JUNGLE_LOG, Items.CHARCOAL), Map.entry(Items.ACACIA_LOG, Items.CHARCOAL), Map.entry(Items.DARK_OAK_LOG, Items.CHARCOAL),
            Map.entry(Items.MANGROVE_LOG, Items.CHARCOAL), Map.entry(Items.CHERRY_LOG, Items.CHARCOAL));

    static Optional<RegistryEntry.Reference<Enchantment>> entry(ServerWorld w, String id) {
        return w.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT)
                .getOptional(RegistryKey.of(RegistryKeys.ENCHANTMENT, Identifier.of(DracosCore.MOD_ID, id)));
    }

    static int level(ServerWorld w, ItemStack s, String id) {
        if (s == null || s.isEmpty()) return 0;
        return entry(w, id).map(e -> EnchantmentHelper.getLevel(e, s)).orElse(0);
    }

    /** A random custom enchanted book (used as a crate reward). */
    public static ItemStack randomBook(ServerWorld w) {
        Random r = new Random();
        for (int tries = 0; tries < 10; tries++) {
            var e = entry(w, ALL.get(r.nextInt(ALL.size())));
            if (e.isEmpty()) continue;
            int lvl = 1 + r.nextInt(e.get().value().getMaxLevel());
            ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
            ItemEnchantmentsComponent.Builder b = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
            b.add(e.get(), lvl);
            book.set(net.minecraft.component.DataComponentTypes.STORED_ENCHANTMENTS, b.build());
            return book;
        }
        return new ItemStack(Items.EXPERIENCE_BOTTLE, 16);
    }

    public static void register() {
        // Telekinesis / Autosmelt: we break the block ourselves so we can redirect / change the drops
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp) || sp.isCreative()) return true;
            ItemStack tool = sp.getMainHandStack();
            int tele = level(sw, tool, "telekinesis"), smelt = level(sw, tool, "autosmelt");
            if (tele == 0 && smelt == 0) return true;
            if (!sp.canHarvest(state) || state.getHardness(sw, pos) < 0) return true;
            List<ItemStack> drops = new ArrayList<>(Block.getDroppedStacks(state, sw, pos, be, sp, tool));
            if (smelt > 0) for (int i = 0; i < drops.size(); i++) {
                ItemStack d = drops.get(i);
                Item out = SMELT.get(d.getItem());
                if (out != null) drops.set(i, new ItemStack(out, d.getCount()));
            }
            state.getBlock().onBreak(sw, pos, state, sp);
            if (!sw.removeBlock(pos, false)) return true;
            state.getBlock().onBroken(sw, pos, state);
            tool.postMine(sw, state, pos, sp);
            sp.incrementStat(net.minecraft.stat.Stats.MINED.getOrCreateStat(state.getBlock()));
            sp.addExhaustion(0.005f);
            state.onStacksDropped(sw, pos, tool, true);    // ore XP
            for (ItemStack d : drops) {
                if (d.isEmpty()) continue;
                if (tele > 0) sp.getInventory().offerOrDrop(d);
                else Block.dropStack(sw, pos, d);
            }
            // quests, skills, leaderboards etc. still see the break
            PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(sw, sp, pos, state, be);
            return false;
        });
        // Wisdom: extra XP from ores
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (!(world instanceof ServerWorld sw) || player.isCreative()) return;
            String id = Registries.BLOCK.getId(state.getBlock()).getPath();
            if (!id.endsWith("_ore") && !id.equals("ancient_debris")) return;
            int lvl = level(sw, player.getMainHandStack(), "wisdom");
            if (lvl > 0) ExperienceOrbEntity.spawn(sw, Vec3d.ofCenter(pos), lvl * 3);
        });
        // helmet enchants
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 40 != 0) return;
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (!(p.getEntityWorld() instanceof ServerWorld sw)) continue;
                ItemStack head = p.getEquippedStack(EquipmentSlot.HEAD);
                if (head.isEmpty()) continue;
                if (level(sw, head, "clarity") > 0)
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 20 * 15, 0, true, false, true));
                if (level(sw, head, "nourish") > 0 && p.getHungerManager().getFoodLevel() < 20)
                    p.getHungerManager().add(1, 0.5f);
            }
        });
    }
}
