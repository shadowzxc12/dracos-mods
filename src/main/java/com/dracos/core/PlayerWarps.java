package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
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
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** /pwarp - public player warps (shops, bases, farms). GUI list sorted by visits. */
public final class PlayerWarps {
    private PlayerWarps() {}

    static final long CREATE_COST = 50_000;

    static final class Warp {
        String name, owner, ownerName, world, icon = "minecraft:ender_pearl", desc = "";
        double x, y, z; float yaw, pitch;
        int visits; long created;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Warp> WARPS = new LinkedHashMap<>();

    // ------------------------------------------------------------------ protection around warps
    /** Visitors can't hurt animals/villagers, break, place or open anything within this many blocks of a warp. */
    static final int PROTECT = 30;

    /** The warp protecting this spot from this player, or null if they may act here. */
    static Warp protector(net.minecraft.entity.player.PlayerEntity actor, net.minecraft.world.World w, double x, double z) {
        if (actor == null || actor.isCreative()) return null;
        if (actor instanceof ServerPlayerEntity sp && Ranks.of(sp) == Ranks.Rank.OWNER) return null;
        String wid = w.getRegistryKey().getValue().toString();
        for (Warp wp : WARPS.values()) {
            if (!wid.equals(wp.world) || Math.abs(wp.x - x) > PROTECT || Math.abs(wp.z - z) > PROTECT) continue;
            if (wp.owner.equals(actor.getUuidAsString())) return null;
            try {
                var mine = Clans.of(actor.getUuid());
                if (mine != null && mine == Clans.of(java.util.UUID.fromString(wp.owner))) return null;
            } catch (Exception ignored) {}
            return wp;
        }
        return null;
    }

    static void deny(net.minecraft.entity.player.PlayerEntity p, Warp w) {
        p.sendMessage(Text.literal("This area is protected by " + w.ownerName + "'s warp.").formatted(Formatting.RED), true);
    }

    static boolean protectedEntity(net.minecraft.entity.Entity e) {
        return !(e instanceof net.minecraft.entity.player.PlayerEntity) && !(e instanceof net.minecraft.entity.mob.Monster);
    }

    public static void register() {
        // hitting animals, villagers, golems, armor stands, item frames...
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient() || !protectedEntity(entity)) return net.minecraft.util.ActionResult.PASS;
            Warp w = protector(player, world, entity.getX(), entity.getZ());
            if (w == null) return net.minecraft.util.ActionResult.PASS;
            deny(player, w);
            return net.minecraft.util.ActionResult.FAIL;
        });
        // arrows, tridents, potions... thrown by a visitor
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!protectedEntity(entity) || !(source.getAttacker() instanceof net.minecraft.entity.player.PlayerEntity att)) return true;
            Warp w = protector(att, entity.getEntityWorld(), entity.getX(), entity.getZ());
            if (w == null) return true;
            deny(att, w);
            return false;
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            Warp w = protector(player, world, pos.getX() + 0.5, pos.getZ() + 0.5);
            if (w == null) return true;
            deny(player, w);
            return false;
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient()) return net.minecraft.util.ActionResult.PASS;
            var pos = hit.getBlockPos();
            Warp w = protector(player, world, pos.getX() + 0.5, pos.getZ() + 0.5);
            if (w == null) return net.minecraft.util.ActionResult.PASS;
            var b = world.getBlockState(pos).getBlock();
            boolean harmless = b instanceof net.minecraft.block.DoorBlock || b instanceof net.minecraft.block.TrapdoorBlock
                    || b instanceof net.minecraft.block.FenceGateBlock || b instanceof net.minecraft.block.ButtonBlock
                    || b instanceof net.minecraft.block.CraftingTableBlock || b instanceof net.minecraft.block.EnderChestBlock;
            if (harmless && !player.isSneaking()) return net.minecraft.util.ActionResult.PASS;
            deny(player, w);
            return net.minecraft.util.ActionResult.FAIL;
        });
        // trading with villagers is fine, but not taking stuff off armor stands / item frames, leashing, shearing...
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient() || !protectedEntity(entity) || entity instanceof net.minecraft.entity.passive.MerchantEntity) return net.minecraft.util.ActionResult.PASS;
            Warp w = protector(player, world, entity.getX(), entity.getZ());
            if (w == null) return net.minecraft.util.ActionResult.PASS;
            deny(player, w);
            return net.minecraft.util.ActionResult.FAIL;
        });
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient()) return net.minecraft.util.ActionResult.PASS;
            var it = player.getStackInHand(hand).getItem();
            boolean griefy = it instanceof net.minecraft.item.BucketItem || it == net.minecraft.item.Items.FLINT_AND_STEEL
                    || it == net.minecraft.item.Items.FIRE_CHARGE || it == net.minecraft.item.Items.TNT_MINECART || it == net.minecraft.item.Items.END_CRYSTAL;
            if (!griefy) return net.minecraft.util.ActionResult.PASS;
            Warp w = protector(player, world, player.getX(), player.getZ());
            if (w == null) return net.minecraft.util.ActionResult.PASS;
            deny(player, w);
            return net.minecraft.util.ActionResult.FAIL;
        });
    }

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("pwarps.json"); }

    public static void load() {
        try {
            if (Files.exists(file())) {
                Map<String, Warp> m = GSON.fromJson(Files.readString(file()), new TypeToken<LinkedHashMap<String, Warp>>() {}.getType());
                if (m != null) WARPS = m;
            }
        } catch (Exception e) { DracosCore.LOGGER.warn("pwarps.json unreadable", e); }
    }

    private static void save() {
        try { Files.createDirectories(file().getParent()); Files.writeString(file(), GSON.toJson(WARPS)); }
        catch (Exception e) { DracosCore.LOGGER.warn("pwarps.json save failed", e); }
    }

    static int limit(ServerPlayerEntity p) {
        return switch (Ranks.of(p)) {
            case MEMBER -> 1; case VIP -> 2; case MVP -> 3; case LEGEND -> 4; case DRACO -> 6; default -> 50;
        };
    }

    static long owned(ServerPlayerEntity p) { return WARPS.values().stream().filter(w -> w.owner.equals(p.getUuidAsString())).count(); }

    private static void msg(ServerPlayerEntity p, String s, Formatting f) {
        p.sendMessage(Text.literal("Warps » ").formatted(Formatting.DARK_AQUA, Formatting.BOLD).append(Text.literal(s).formatted(f)), false);
    }

    public static void commands(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("pwarp").executes(ctx -> { open(ctx.getSource().getPlayerOrThrow(), 0); return 1; })
                .then(CommandManager.literal("set").then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> set(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "name")))))
                .then(CommandManager.literal("delete").then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> delete(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "name")))))
                .then(CommandManager.literal("desc").then(CommandManager.argument("name", StringArgumentType.word())
                        .then(CommandManager.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> desc(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "name"),
                                        StringArgumentType.getString(ctx, "text"))))))
                .then(CommandManager.literal("icon").then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> icon(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "name")))))
                .then(CommandManager.literal("help").executes(ctx -> help(ctx.getSource().getPlayerOrThrow())))
                .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> tp(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "name")))));
        d.register(CommandManager.literal("pwarps").executes(ctx -> { open(ctx.getSource().getPlayerOrThrow(), 0); return 1; }));
    }

    private static int help(ServerPlayerEntity p) {
        msg(p, "/pwarp - list   /pwarp <name> - go", Formatting.GRAY);
        msg(p, "/pwarp set <name> - create here (" + Money.fmt(CREATE_COST) + ", you can have " + limit(p) + ")", Formatting.GRAY);
        msg(p, "/pwarp desc <name> <text>   /pwarp icon <name> (item in hand)   /pwarp delete <name>", Formatting.GRAY);
        return 1;
    }

    private static int set(ServerPlayerEntity p, String name) {
        if (!name.matches("[A-Za-z0-9_]{2,16}")) { msg(p, "Name must be 2-16 letters/numbers.", Formatting.RED); return 0; }
        String key = name.toLowerCase(Locale.ROOT);
        Warp old = WARPS.get(key);
        if (old != null && !old.owner.equals(p.getUuidAsString())) { msg(p, "That warp name is taken.", Formatting.RED); return 0; }
        if (old == null && owned(p) >= limit(p)) { msg(p, "You can have " + limit(p) + " warp(s). Better ranks get more: /ranks", Formatting.RED); return 0; }
        if (Combat.inCombat(p)) { msg(p, "Not in combat!", Formatting.RED); return 0; }
        if (old == null) {
            if (!Money.has(p, CREATE_COST)) { msg(p, "A warp costs " + Money.fmt(CREATE_COST) + ".", Formatting.RED); return 0; }
            Money.add(p, -CREATE_COST);
        }
        Warp w = old != null ? old : new Warp();
        w.name = name; w.owner = p.getUuidAsString(); w.ownerName = p.getName().getString();
        w.world = p.getEntityWorld().getRegistryKey().getValue().toString();
        w.x = p.getX(); w.y = p.getY(); w.z = p.getZ(); w.yaw = p.getYaw(); w.pitch = p.getPitch();
        if (old == null) w.created = System.currentTimeMillis();
        WARPS.put(key, w);
        save();
        msg(p, (old == null ? "Created" : "Moved") + " warp " + name + "! Others can use /pwarp " + name, Formatting.GREEN);
        return 1;
    }

    private static Warp mine(ServerPlayerEntity p, String name) {
        Warp w = WARPS.get(name.toLowerCase(Locale.ROOT));
        if (w == null) { msg(p, "No warp called " + name + ".", Formatting.RED); return null; }
        if (!w.owner.equals(p.getUuidAsString()) && Ranks.of(p) != Ranks.Rank.OWNER) { msg(p, "That is not your warp.", Formatting.RED); return null; }
        return w;
    }

    private static int delete(ServerPlayerEntity p, String name) {
        Warp w = mine(p, name);
        if (w == null) return 0;
        WARPS.remove(name.toLowerCase(Locale.ROOT));
        save();
        msg(p, "Deleted warp " + w.name + ".", Formatting.YELLOW);
        return 1;
    }

    private static int desc(ServerPlayerEntity p, String name, String text) {
        Warp w = mine(p, name);
        if (w == null) return 0;
        w.desc = text.length() > 60 ? text.substring(0, 60) : text;
        save();
        msg(p, "Description set.", Formatting.GREEN);
        return 1;
    }

    private static int icon(ServerPlayerEntity p, String name) {
        Warp w = mine(p, name);
        if (w == null) return 0;
        ItemStack hand = p.getMainHandStack();
        if (hand.isEmpty()) { msg(p, "Hold the item you want as icon.", Formatting.RED); return 0; }
        w.icon = Registries.ITEM.getId(hand.getItem()).toString();
        save();
        msg(p, "Icon set.", Formatting.GREEN);
        return 1;
    }

    private static int tp(ServerPlayerEntity p, String name) {
        Warp w = WARPS.get(name.toLowerCase(Locale.ROOT));
        if (w == null) { msg(p, "No warp called " + name + ". See /pwarp", Formatting.RED); return 0; }
        if (Combat.inCombat(p)) { msg(p, "You can't teleport in combat!", Formatting.RED); return 0; }
        ServerWorld world = p.getEntityWorld().getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(w.world)));
        if (world == null) { msg(p, "That warp's world is missing.", Formatting.RED); return 0; }
        p.teleport(world, w.x, w.y, w.z, Set.of(), w.yaw, w.pitch, true);
        if (!w.owner.equals(p.getUuidAsString())) { w.visits++; save(); }
        msg(p, "Welcome to " + w.name + " by " + w.ownerName + "!", Formatting.GREEN);
        world.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.6f, 1.2f);
        return 1;
    }

    // ------------------------------------------------------------------ GUI
    static void open(ServerPlayerEntity p, int page) {
        List<Warp> all = new ArrayList<>(WARPS.values());
        all.sort((a, b) -> Integer.compare(b.visits, a.visits));
        int pages = Math.max(1, (all.size() + 44) / 45);
        int pg = Math.max(0, Math.min(page, pages - 1));
        SimpleInventory inv = new SimpleInventory(54);
        Map<Integer, String> map = new HashMap<>();
        for (int i = 0; i < 45 && pg * 45 + i < all.size(); i++) {
            Warp w = all.get(pg * 45 + i);
            Identifier iid = Identifier.tryParse(w.icon);
            Item item = iid != null && Registries.ITEM.containsId(iid) ? Registries.ITEM.get(iid) : Items.ENDER_PEARL;
            ItemStack s = new ItemStack(item);
            s.set(DataComponentTypes.ITEM_NAME, Text.literal(w.name).formatted(Formatting.AQUA, Formatting.BOLD));
            List<Text> lore = new ArrayList<>();
            if (!w.desc.isEmpty()) lore.add(Text.literal(w.desc).styled(t -> t.withColor(Formatting.WHITE).withItalic(false)));
            lore.add(Text.literal("By " + w.ownerName).styled(t -> t.withColor(Formatting.GRAY).withItalic(false)));
            lore.add(Text.literal(w.visits + " visits").styled(t -> t.withColor(Formatting.DARK_GRAY).withItalic(false)));
            lore.add(Text.literal("» Click to teleport").styled(t -> t.withColor(Formatting.GREEN).withItalic(false)));
            s.set(DataComponentTypes.LORE, new LoreComponent(lore));
            inv.setStack(i, s);
            map.put(i, w.name);
        }
        ItemStack pane = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        pane.set(DataComponentTypes.ITEM_NAME, Text.literal(" "));
        for (int i = 45; i < 54; i++) inv.setStack(i, pane.copy());
        ItemStack info = new ItemStack(Items.WRITABLE_BOOK);
        info.set(DataComponentTypes.ITEM_NAME, Text.literal("Make your own warp").formatted(Formatting.GOLD, Formatting.BOLD));
        info.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("/pwarp set <name>  (" + Money.fmt(CREATE_COST) + ")").styled(t -> t.withColor(Formatting.GRAY).withItalic(false)),
                Text.literal("/pwarp desc <name> <text>").styled(t -> t.withColor(Formatting.GRAY).withItalic(false)),
                Text.literal("/pwarp icon <name>  (item in hand)").styled(t -> t.withColor(Formatting.GRAY).withItalic(false)))));
        inv.setStack(49, info);
        if (pg > 0) { ItemStack a = new ItemStack(Items.ARROW); a.set(DataComponentTypes.ITEM_NAME, Text.literal("← Previous").formatted(Formatting.YELLOW)); inv.setStack(45, a); }
        if (pg < pages - 1) { ItemStack a = new ItemStack(Items.ARROW); a.set(DataComponentTypes.ITEM_NAME, Text.literal("Next →").formatted(Formatting.YELLOW)); inv.setStack(53, a); }
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, pi, pl) -> new Handler(sync, pi, inv, map, pg),
                Text.literal("Player Warps (" + WARPS.size() + ")").formatted(Formatting.DARK_AQUA, Formatting.BOLD)));
    }

    static class Handler extends GenericContainerScreenHandler {
        final Map<Integer, String> map; final int page;
        Handler(int sync, PlayerInventory pi, SimpleInventory inv, Map<Integer, String> map, int page) {
            super(ScreenHandlerType.GENERIC_9X6, sync, pi, inv, 6); this.map = map; this.page = page;
        }
        @Override public void onSlotClick(int slot, int button, SlotActionType type, PlayerEntity player) {
            if (player instanceof ServerPlayerEntity sp && type == SlotActionType.PICKUP) {
                if (map.containsKey(slot)) { sp.closeHandledScreen(); tp(sp, map.get(slot)); return; }
                if (slot == 45 && page > 0) { open(sp, page - 1); return; }
                if (slot == 53) { open(sp, page + 1); return; }
            }
            this.syncState();
        }
        @Override public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
    }
}
