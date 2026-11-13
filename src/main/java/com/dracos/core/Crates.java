package com.dracos.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Function;

/** Crates at the hub: right-click with the matching key to spin, left-click (or no key) to preview the loot. */
public final class Crates {
    private Crates() {}

    static final String KEY_TAG = "dracos_key";

    /** A reward: weight, how it looks in the GUI, and what the player gets. */
    record Reward(int weight, Function<ServerPlayerEntity, ItemStack> icon, Consumer<ServerPlayerEntity> give, boolean announce) {}

    static Reward money(int w, long amount) {
        return new Reward(w, p -> named(Items.GOLD_INGOT, Money.fmt(amount), Formatting.GOLD), p -> Money.add(p, amount), amount >= 500_000);
    }

    static Reward item(int w, Item it, int count, boolean announce) {
        return new Reward(w, p -> new ItemStack(it, count), p -> p.getInventory().offerOrDrop(new ItemStack(it, count)), announce);
    }

    static Reward spawner(int w, String entity) {
        return new Reward(w, p -> Spawners.item(entity), p -> p.getInventory().offerOrDrop(Spawners.item(entity)), true);
    }

    static Reward key(int w, String type, int n) {
        return new Reward(w, p -> keyItem(type, n), p -> giveKey(p, type, n), false);
    }

    static Reward legendary(int w) {
        return new Reward(w, p -> named(Items.NETHER_STAR, "Random Legendary Item", Formatting.LIGHT_PURPLE),
                p -> {
                    List<String> ids = LegendaryItems.IDS;
                    String id = ids.get(new Random().nextInt(ids.size()));
                    ItemStack s = LegendaryItems.create(id, p.getRegistryManager());
                    if (s != null && !s.isEmpty()) p.getInventory().offerOrDrop(s);
                }, true);
    }

    static Reward book(int w) {
        return new Reward(w, p -> named(Items.ENCHANTED_BOOK, "Custom Enchant Book", Formatting.LIGHT_PURPLE),
                p -> p.getInventory().offerOrDrop(Enchants.randomBook((ServerWorld) p.getEntityWorld())), false);
    }

    static final Map<String, List<Reward>> LOOT = Map.of(
            "common", List.of(
                    money(30, 15_000), money(20, 40_000), money(8, 100_000),
                    item(14, Items.DIAMOND, 8, false), item(10, Items.IRON_BLOCK, 8, false), item(10, Items.GOLDEN_APPLE, 4, false),
                    item(8, Items.EXPERIENCE_BOTTLE, 32, false), item(6, Items.ENDER_PEARL, 16, false),
                    spawner(3, "minecraft:zombie"), key(4, "rare", 1), book(6)),
            "rare", List.of(
                    money(25, 100_000), money(14, 250_000), money(5, 750_000),
                    item(12, Items.DIAMOND_BLOCK, 4, false), item(8, Items.NETHERITE_INGOT, 1, true), item(8, Items.ENCHANTED_GOLDEN_APPLE, 2, true),
                    item(6, Items.TOTEM_OF_UNDYING, 1, false), item(5, Items.SHULKER_BOX, 1, false),
                    spawner(6, "minecraft:skeleton"), spawner(4, "minecraft:blaze"), book(14)),
            "spawner", List.of(
                    spawner(20, "minecraft:pig"), spawner(18, "minecraft:cow"), spawner(16, "minecraft:zombie"), spawner(14, "minecraft:skeleton"),
                    spawner(10, "minecraft:spider"), spawner(8, "minecraft:creeper"), spawner(6, "minecraft:witch"), spawner(5, "minecraft:blaze"),
                    spawner(4, "minecraft:enderman"), spawner(2, "minecraft:iron_golem")),
            "legendary", List.of(
                    money(22, 500_000), money(12, 1_500_000), money(4, 5_000_000),
                    item(10, Items.NETHERITE_BLOCK, 1, true), item(8, Items.ENCHANTED_GOLDEN_APPLE, 8, true),
                    item(5, Items.BEACON, 1, true), spawner(8, "minecraft:iron_golem"), spawner(6, "minecraft:creeper"),
                    legendary(1), key(3, "legendary", 2), book(14)));

    static String title(String type) {
        return switch (type) { case "rare" -> "Rare"; case "legendary" -> "Legendary"; case "spawner" -> "Spawner"; default -> "Common"; };
    }

    static Formatting color(String type) {
        return switch (type) { case "rare" -> Formatting.AQUA; case "legendary" -> Formatting.GOLD; case "spawner" -> Formatting.DARK_PURPLE; default -> Formatting.GREEN; };
    }

    static ItemStack named(Item i, String name, Formatting c) {
        ItemStack s = new ItemStack(i);
        s.set(DataComponentTypes.ITEM_NAME, Text.literal(name).formatted(c, Formatting.BOLD));
        return s;
    }

    // ------------------------------------------------------------------ keys
    public static ItemStack keyItem(String type, int n) {
        ItemStack s = new ItemStack(Items.TRIPWIRE_HOOK, n);
        s.set(DataComponentTypes.ITEM_NAME, Text.literal(title(type) + " Crate Key").formatted(color(type), Formatting.BOLD));
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("Right-click the " + title(type) + " crate at /spawn").styled(st -> st.withColor(Formatting.GRAY).withItalic(false)))));
        s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        s.set(DataComponentTypes.RARITY, type.equals("legendary") ? Rarity.EPIC : type.equals("rare") ? Rarity.RARE : Rarity.UNCOMMON);
        NbtCompound nbt = new NbtCompound();
        nbt.putString(KEY_TAG, type);
        s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        return s;
    }

    static String keyType(ItemStack s) {
        if (s == null || !s.isOf(Items.TRIPWIRE_HOOK)) return null;
        NbtComponent d = s.get(DataComponentTypes.CUSTOM_DATA);
        if (d == null) return null;
        String t = d.copyNbt().getString(KEY_TAG, "");
        return t.isEmpty() ? null : t;
    }

    public static void giveKey(ServerPlayerEntity p, String type, int n) {
        p.getInventory().offerOrDrop(keyItem(type, n));
    }

    /** Crate type at this block (hub world only), or null. */
    static String at(BlockPos pos) {
        for (var e : Hub.CFG.crates.entrySet()) {
            int[] c = e.getValue();
            if (c[0] == pos.getX() && c[1] == pos.getY() && c[2] == pos.getZ()) return e.getKey();
        }
        return null;
    }

    // ------------------------------------------------------------------ events
    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity p)) return ActionResult.PASS;
            if (!Hub.inHub(sw, hit.getBlockPos())) return ActionResult.PASS;
            String type = at(hit.getBlockPos());
            if (type == null) return ActionResult.PASS;
            if (hand != net.minecraft.util.Hand.MAIN_HAND) return ActionResult.FAIL;
            ItemStack held = p.getMainHandStack();
            if (type.equals(keyType(held))) {
                if (RUNNING.stream().anyMatch(a -> a.player.equals(p.getUuid()))) return ActionResult.FAIL;
                held.decrement(1);
                spin(p, type);
            } else {
                preview(p, type);
                p.sendMessage(Text.literal("You need a " + title(type) + " Crate Key. Get keys in the AFK zone, from quests and crates.")
                        .formatted(Formatting.GRAY), true);
            }
            return ActionResult.FAIL;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> {
            if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity p) || !Hub.inHub(sw, pos)) return ActionResult.PASS;
            String type = at(pos);
            if (type == null) return ActionResult.PASS;
            preview(p, type);
            return ActionResult.FAIL;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (RUNNING.isEmpty()) return;
            Iterator<Spin> it = RUNNING.iterator();
            while (it.hasNext()) { Spin s = it.next(); s.tick++; if (!s.step(server)) it.remove(); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { for (Spin s : RUNNING) s.payout(server); RUNNING.clear(); });
        // leaving mid-spin still gives the prize
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
            for (Spin s : RUNNING) if (s.player.equals(h.player.getUuid())) s.payoutTo(h.player, server);
        });
    }

    public static void commands(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("crate")
                .then(CommandManager.literal("preview").then(CommandManager.argument("type", StringArgumentType.word())
                        .suggests((c, b) -> { for (String t : LOOT.keySet()) b.suggest(t); return b.buildFuture(); })
                        .executes(ctx -> { String t = StringArgumentType.getString(ctx, "type"); if (LOOT.containsKey(t)) preview(ctx.getSource().getPlayerOrThrow(), t); return 1; })))
                .then(CommandManager.literal("give").requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .then(CommandManager.argument("type", StringArgumentType.word())
                                        .suggests((c, b) -> { for (String t : LOOT.keySet()) b.suggest(t); return b.buildFuture(); })
                                        .then(CommandManager.argument("amount", IntegerArgumentType.integer(1, 64)).executes(ctx -> {
                                            String t = StringArgumentType.getString(ctx, "type");
                                            if (!LOOT.containsKey(t)) return 0;
                                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                            giveKey(target, t, IntegerArgumentType.getInteger(ctx, "amount"));
                                            ctx.getSource().sendFeedback(() -> Text.literal("Gave keys to " + target.getName().getString()), false);
                                            return 1;
                                        }))))));
    }

    // ------------------------------------------------------------------ preview
    static void preview(ServerPlayerEntity p, String type) {
        List<Reward> loot = LOOT.get(type);
        int total = loot.stream().mapToInt(Reward::weight).sum();
        SimpleInventory inv = new SimpleInventory(27);
        ItemStack pane = named(Items.BLACK_STAINED_GLASS_PANE, " ", Formatting.GRAY);
        for (int i = 0; i < 27; i++) inv.setStack(i, pane.copy());
        int slot = 0;
        for (Reward r : loot) {
            ItemStack s = r.icon().apply(p).copy();
            List<Text> lore = new ArrayList<>();
            lore.add(Text.literal(String.format(java.util.Locale.ROOT, "Chance: %.1f%%", 100.0 * r.weight() / total))
                    .styled(st -> st.withColor(Formatting.YELLOW).withItalic(false)));
            s.set(DataComponentTypes.LORE, new LoreComponent(lore));
            inv.setStack(slot++, s);
        }
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, pi, pl) -> new View(sync, pi, inv),
                Text.literal(title(type) + " Crate").formatted(color(type), Formatting.BOLD)));
    }

    // ------------------------------------------------------------------ spin animation
    static final List<Spin> RUNNING = new ArrayList<>();

    static Reward roll(String type) {
        List<Reward> loot = LOOT.get(type);
        int total = loot.stream().mapToInt(Reward::weight).sum(), r = new Random().nextInt(total);
        for (Reward x : loot) { r -= x.weight(); if (r < 0) return x; }
        return loot.get(0);
    }

    static void spin(ServerPlayerEntity p, String type) {
        Spin s = new Spin(p, type);
        RUNNING.add(s);
    }

    static final class Spin {
        final java.util.UUID player;
        final String type;
        final Reward win;
        final List<Reward> strip = new ArrayList<>();
        final SimpleInventory inv = new SimpleInventory(27);
        final int stopAt = 40;
        int tick, pos, next;
        boolean paid;

        Spin(ServerPlayerEntity p, String type) {
            this.player = p.getUuid();
            this.type = type;
            this.win = roll(type);
            for (int i = 0; i < stopAt + 10; i++) strip.add(roll(type));
            strip.set(stopAt, win);
            ItemStack pane = named(Items.GRAY_STAINED_GLASS_PANE, " ", Formatting.GRAY);
            for (int i = 0; i < 27; i++) inv.setStack(i, pane.copy());
            inv.setStack(4, named(Items.HOPPER, "▼", Formatting.GOLD));
            inv.setStack(22, named(Items.HOPPER, "▲", Formatting.GOLD));
            draw(p);
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, pi, pl) -> new View(sync, pi, inv),
                    Text.literal("Opening " + title(type) + " Crate...").formatted(color(type), Formatting.BOLD)));
        }

        void draw(ServerPlayerEntity p) {
            for (int c = 0; c < 9; c++) inv.setStack(9 + c, strip.get(pos + c).icon().apply(p).copy());
        }

        boolean step(net.minecraft.server.MinecraftServer server) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(player);
            int last = stopAt - 4;
            if (pos < last && tick >= next) {
                pos++;
                int left = last - pos;
                next = tick + (left > 18 ? 1 : left > 9 ? 2 : left > 4 ? 4 : left > 1 ? 7 : 10);
                if (p != null) {
                    draw(p);
                    if (p.currentScreenHandler instanceof View v && v.inv == inv) v.syncState();
                    p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.PLAYERS, 0.5f, 1f + pos / 40f);
                }
                return true;
            }
            if (pos >= last && !paid && tick >= next + 6) {
                payout(server);
                paid = true;
                if (p != null) {
                    for (int s : new int[]{3, 5, 21, 23}) inv.setStack(s, named(Items.LIME_STAINED_GLASS_PANE, " ", Formatting.GREEN));
                    if (p.currentScreenHandler instanceof View v && v.inv == inv) v.syncState();
                }
                next = tick;
            }
            if (paid && tick >= next + 40) {
                if (p != null && p.currentScreenHandler instanceof View v && v.inv == inv) p.closeHandledScreen();
                return false;
            }
            return true;
        }

        void payout(net.minecraft.server.MinecraftServer server) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(player);
            if (p != null) payoutTo(p, server);
        }

        void payoutTo(ServerPlayerEntity p, net.minecraft.server.MinecraftServer server) {
            if (paid) return;
            paid = true;
            win.give().accept(p);
            ItemStack icon = win.icon().apply(p);
            Text name = icon.getName();
            p.sendMessage(Text.literal("You won ").formatted(Formatting.GREEN)
                    .append(name.copy().formatted(Formatting.BOLD)).append(Text.literal(icon.getCount() > 1 ? " x" + icon.getCount() : "")), false);
            p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.8f, 1.2f);
            if (win.announce() || type.equals("legendary")) {
                server.getPlayerManager().broadcast(Text.literal("✦ ").formatted(Formatting.GOLD)
                        .append(Text.literal(p.getName().getString()).formatted(Formatting.YELLOW, Formatting.BOLD))
                        .append(Text.literal(" won ").formatted(Formatting.GRAY)).append(name.copy().formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal(" from a " + title(type) + " Crate!").formatted(Formatting.GRAY)), false);
            }
        }
    }

    /** Read-only GUI. */
    static class View extends GenericContainerScreenHandler {
        final SimpleInventory inv;
        View(int sync, PlayerInventory pi, SimpleInventory inv) { super(ScreenHandlerType.GENERIC_9X3, sync, pi, inv, 3); this.inv = inv; }
        @Override public void onSlotClick(int slot, int button, SlotActionType type, PlayerEntity player) { this.syncState(); }
        @Override public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
    }
}
