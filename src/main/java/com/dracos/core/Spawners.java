package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** /spawners - buy mob spawners. Place them anywhere, mine them back with any pickaxe. */
public final class Spawners {
    private Spawners() {}

    private static final String TAG = "dracos_spawner";

    record Offer(String id, String name, Item icon, long price) {}

    static final List<Offer> OFFERS = List.of(
            new Offer("minecraft:pig", "Pig", Items.PORKCHOP, 40_000),
            new Offer("minecraft:chicken", "Chicken", Items.FEATHER, 40_000),
            new Offer("minecraft:cow", "Cow", Items.LEATHER, 60_000),
            new Offer("minecraft:sheep", "Sheep", Items.WHITE_WOOL, 60_000),
            new Offer("minecraft:zombie", "Zombie", Items.ROTTEN_FLESH, 120_000),
            new Offer("minecraft:spider", "Spider", Items.STRING, 140_000),
            new Offer("minecraft:skeleton", "Skeleton", Items.BONE, 160_000),
            new Offer("minecraft:cave_spider", "Cave Spider", Items.SPIDER_EYE, 160_000),
            new Offer("minecraft:creeper", "Creeper", Items.GUNPOWDER, 250_000),
            new Offer("minecraft:slime", "Slime", Items.SLIME_BALL, 300_000),
            new Offer("minecraft:magma_cube", "Magma Cube", Items.MAGMA_CREAM, 300_000),
            new Offer("minecraft:witch", "Witch", Items.GLOWSTONE_DUST, 400_000),
            new Offer("minecraft:blaze", "Blaze", Items.BLAZE_ROD, 500_000),
            new Offer("minecraft:enderman", "Enderman", Items.ENDER_PEARL, 600_000),
            new Offer("minecraft:iron_golem", "Iron Golem", Items.IRON_INGOT, 2_000_000));

    private static final Gson GSON = new Gson();
    private static Map<String, String> PLACED = new HashMap<>(); // "world|pos" -> entity id
    private static boolean dirty;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("spawners.json");
    }

    public static void load() {
        try {
            if (Files.exists(file())) {
                Map<String, String> m = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, String>>() {}.getType());
                if (m != null) PLACED = new HashMap<>(m);
            }
        } catch (Exception e) {
            DracosCore.LOGGER.warn("spawners.json unreadable", e);
        }
    }

    static void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(PLACED));
        } catch (Exception e) {
            DracosCore.LOGGER.warn("spawners.json save failed", e);
        }
    }

    private static String key(World w, BlockPos pos) {
        return w.getRegistryKey().getValue() + "|" + pos.asLong();
    }

    static Offer offer(String id) {
        for (Offer o : OFFERS) if (o.id.equals(id)) return o;
        return null;
    }

    public static ItemStack item(String entityId) {
        Offer o = offer(entityId);
        String name = o != null ? o.name : entityId.replace("minecraft:", "");
        ItemStack s = new ItemStack(Items.SPAWNER);
        s.set(DataComponentTypes.ITEM_NAME, Text.literal(name + " Spawner").formatted(Formatting.GOLD, Formatting.BOLD));
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("Place it to spawn " + name + "s").styled(st -> st.withColor(Formatting.GRAY).withItalic(false)),
                Text.literal("Mine it with any pickaxe to pick it up").styled(st -> st.withColor(Formatting.DARK_GRAY).withItalic(false)))));
        s.set(DataComponentTypes.RARITY, Rarity.EPIC);
        NbtCompound nbt = new NbtCompound();
        nbt.putString(TAG, entityId);
        s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        return s;
    }

    static String idOf(ItemStack s) {
        if (s == null || !s.isOf(Items.SPAWNER)) return null;
        NbtComponent d = s.get(DataComponentTypes.CUSTOM_DATA);
        if (d == null) return null;
        String id = d.copyNbt().getString(TAG, "");
        return id.isEmpty() ? null : id;
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            ItemStack held = player.getStackInHand(hand);
            // no changing spawners with spawn eggs in survival
            if (held.getItem() instanceof SpawnEggItem && world.getBlockState(hit.getBlockPos()).isOf(Blocks.SPAWNER)
                    && !player.isCreative()) return ActionResult.FAIL;
            String id = idOf(held);
            if (id == null) return ActionResult.PASS;
            EntityType<?> type = Registries.ENTITY_TYPE.containsId(Identifier.of(id)) ? Registries.ENTITY_TYPE.get(Identifier.of(id)) : null;
            if (type == null) return ActionResult.FAIL;
            BlockPos pos = hit.getBlockPos();
            if (!world.getBlockState(pos).isReplaceable()) pos = pos.offset(hit.getSide());
            BlockState st = Blocks.SPAWNER.getDefaultState();
            ServerWorld sw = (ServerWorld) world;
            if (!world.getBlockState(pos).isReplaceable() || !world.getWorldBorder().contains(pos)
                    || !sp.canModifyAt(sw, pos) || !world.canPlace(st, pos, ShapeContext.of(player))) return ActionResult.FAIL;
            world.setBlockState(pos, st, 3);
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof MobSpawnerBlockEntity msb) {
                msb.setEntityType(type, world.getRandom());
                msb.markDirty();
                world.updateListeners(pos, st, st, 3);
            }
            PLACED.put(key(world, pos), id);
            dirty = true;
            if (!player.isCreative()) held.decrement(1);
            world.playSound(null, pos, BlockSoundGroup.METAL.getPlaceSound(), SoundCategory.BLOCKS, 1f, 0.8f);
            return ActionResult.SUCCESS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (world.isClient() || !state.isOf(Blocks.SPAWNER)) return true;
            String id = PLACED.get(key(world, pos));
            if (id == null) return true; // natural spawner: vanilla behaviour
            if (!player.getMainHandStack().isIn(ItemTags.PICKAXES)) {
                player.sendMessage(Text.literal("Use a pickaxe to pick up this spawner!").formatted(Formatting.RED), true);
                return false;
            }
            PLACED.remove(key(world, pos));
            dirty = true;
            world.breakBlock(pos, false, player); // no XP, no vanilla drops
            if (!player.isCreative()) player.getInventory().offerOrDrop(item(id));
            return false;
        });
    }

    // ------------------------------------------------------------------ shop
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 31};

    public static void open(ServerPlayerEntity p) {
        SimpleInventory inv = new SimpleInventory(45);
        ItemStack pane = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        pane.set(DataComponentTypes.ITEM_NAME, Text.literal(" "));
        for (int i = 0; i < 45; i++) inv.setStack(i, pane.copy());
        Map<Integer, Offer> map = new LinkedHashMap<>();
        for (int i = 0; i < OFFERS.size() && i < SLOTS.length; i++) {
            Offer o = OFFERS.get(i);
            ItemStack s = new ItemStack(Items.SPAWNER);
            s.set(DataComponentTypes.ITEM_NAME, Text.literal(o.name + " Spawner").formatted(Formatting.GOLD, Formatting.BOLD));
            List<Text> lore = new ArrayList<>();
            lore.add(Text.literal("Drops: ").styled(st -> st.withColor(Formatting.GRAY).withItalic(false))
                    .append(Text.translatable(o.icon.getTranslationKey()).styled(st -> st.withColor(Formatting.WHITE).withItalic(false))));
            lore.add(Text.literal("Price: " + Money.fmt(o.price)).styled(st -> st.withColor(Formatting.GOLD).withBold(true).withItalic(false)));
            lore.add(Text.literal("Click to buy").styled(st -> st.withColor(Formatting.GREEN).withItalic(false)));
            s.set(DataComponentTypes.LORE, new LoreComponent(lore));
            inv.setStack(SLOTS[i], s);
            map.put(SLOTS[i], o);
        }
        ItemStack info = new ItemStack(Items.BOOK);
        info.set(DataComponentTypes.ITEM_NAME, Text.literal("How spawners work").formatted(Formatting.AQUA, Formatting.BOLD));
        info.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("Place it like a block").styled(st -> st.withColor(Formatting.GRAY).withItalic(false)),
                Text.literal("Mobs spawn when you are close").styled(st -> st.withColor(Formatting.GRAY).withItalic(false)),
                Text.literal("Kill them and /sell the drops").styled(st -> st.withColor(Formatting.GRAY).withItalic(false)),
                Text.literal("Mine with a pickaxe to move it").styled(st -> st.withColor(Formatting.GRAY).withItalic(false)))));
        inv.setStack(40, info);
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, pi, pl) -> new Handler(sync, pi, inv, map),
                Text.literal("Spawner Shop").formatted(Formatting.DARK_RED, Formatting.BOLD)));
    }

    static class Handler extends GenericContainerScreenHandler {
        final Map<Integer, Offer> map;

        Handler(int sync, PlayerInventory pi, SimpleInventory inv, Map<Integer, Offer> map) {
            super(ScreenHandlerType.GENERIC_9X5, sync, pi, inv, 5);
            this.map = map;
        }

        @Override
        public void onSlotClick(int slot, int button, SlotActionType type, PlayerEntity player) {
            if (player instanceof ServerPlayerEntity sp && type == SlotActionType.PICKUP && map.containsKey(slot)) {
                Offer o = map.get(slot);
                if (!Money.has(sp, o.price)) {
                    sp.sendMessage(Text.literal("Not enough money! You need " + Money.fmt(o.price) + ", you have "
                            + Money.fmt(Money.get(sp)) + ".").formatted(Formatting.RED), false);
                    sp.getEntityWorld().playSound(null, sp.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1f, 1f);
                } else {
                    Money.add(sp, -o.price);
                    sp.getInventory().offerOrDrop(item(o.id));
                    sp.sendMessage(Text.literal("Bought a " + o.name + " Spawner for " + Money.fmt(o.price) + "!").formatted(Formatting.GREEN), false);
                    sp.getEntityWorld().playSound(null, sp.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8f, 1.2f);
                }
            }
            this.syncState();
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }
    }
}
