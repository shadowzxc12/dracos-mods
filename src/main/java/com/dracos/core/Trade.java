package com.dracos.core;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /trade <player> - safe item trading. Left 4 columns are the requester's offer, right 4 the other player's.
 * Both must press READY; any change un-readies both; after a 3 second countdown the offers are swapped.
 * Closing the window (or logging out) cancels and gives everyone their own items back.
 */
public final class Trade {
    private Trade() {}

    static final int READY_A = 45, STATUS = 49, READY_B = 53;
    private static final Map<UUID, UUID> REQUESTS = new HashMap<>();   // target -> requester
    private static final Map<UUID, Long> REQ_TIME = new HashMap<>();
    static final List<Session> ACTIVE = new ArrayList<>();

    static boolean sideA(int slot) { return slot >= 0 && slot < 45 && slot % 9 <= 3; }
    static boolean sideB(int slot) { return slot >= 0 && slot < 45 && slot % 9 >= 5; }

    static void msg(ServerPlayerEntity p, String s, Formatting f) {
        p.sendMessage(Text.literal("Trade » ").formatted(Formatting.GOLD, Formatting.BOLD).append(Text.literal(s).formatted(f)), false);
    }

    public static void commands(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("trade")
                .then(CommandManager.literal("accept").executes(ctx -> accept(ctx.getSource().getPlayerOrThrow())))
                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow(), t = EntityArgumentType.getPlayer(ctx, "player");
                    if (t == p) { msg(p, "You can't trade with yourself.", Formatting.RED); return 0; }
                    if (Combat.inCombat(p) || Duels.inDuel(p)) { msg(p, "Not now - you're fighting.", Formatting.RED); return 0; }
                    REQUESTS.put(t.getUuid(), p.getUuid());
                    REQ_TIME.put(t.getUuid(), System.currentTimeMillis());
                    msg(p, "Trade request sent to " + t.getName().getString() + ".", Formatting.YELLOW);
                    t.sendMessage(Text.literal("Trade » ").formatted(Formatting.GOLD, Formatting.BOLD)
                            .append(Text.literal(p.getName().getString() + " wants to trade with you. ").formatted(Formatting.YELLOW))
                            .append(Text.literal("[ACCEPT]").styled(s -> s.withColor(Formatting.GREEN).withBold(true)
                                    .withClickEvent(new ClickEvent.RunCommand("/trade accept")))), false);
                    return 1;
                })));
    }

    static int accept(ServerPlayerEntity t) {
        UUID from = REQUESTS.remove(t.getUuid());
        Long at = REQ_TIME.remove(t.getUuid());
        if (from == null || at == null || System.currentTimeMillis() - at > 60_000) { msg(t, "No trade request (they expire after 60s).", Formatting.RED); return 0; }
        ServerPlayerEntity a = t.getEntityWorld().getServer().getPlayerManager().getPlayer(from);
        if (a == null) { msg(t, "That player went offline.", Formatting.RED); return 0; }
        if (Combat.inCombat(a) || Combat.inCombat(t) || Duels.inDuel(a) || Duels.inDuel(t)) { msg(t, "Not now - someone is fighting.", Formatting.RED); return 0; }
        Session s = new Session(a, t);
        ACTIVE.add(s);
        s.open(a, 0);
        s.open(t, 1);
        return 1;
    }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
            for (Session s : new ArrayList<>(ACTIVE))
                if (s.a.equals(h.player.getUuid()) || s.b.equals(h.player.getUuid())) s.cancel(server, h.player);
        });
        // server stopping/restarting mid-trade: give everyone their own items back so nothing is lost
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Session s : new ArrayList<>(ACTIVE)) s.cancel(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (ACTIVE.isEmpty()) return;
            for (Session s : new ArrayList<>(ACTIVE)) s.tick(server);
        });
    }

    static ItemStack named(Item i, String n, Formatting f) {
        ItemStack s = new ItemStack(i);
        s.set(DataComponentTypes.ITEM_NAME, Text.literal(n).formatted(f, Formatting.BOLD));
        return s;
    }

    static final class Session {
        final UUID a, b;
        final String nameA, nameB;
        final SimpleInventory inv = new SimpleInventory(54);
        boolean readyA, readyB, done, building;
        int countdown = -1;
        int reopens;
        final long created = System.currentTimeMillis();

        Session(ServerPlayerEntity pa, ServerPlayerEntity pb) {
            a = pa.getUuid(); b = pb.getUuid();
            nameA = pa.getName().getString(); nameB = pb.getName().getString();
            building = true;
            ItemStack pane = named(Items.BLACK_STAINED_GLASS_PANE, " ", Formatting.GRAY);
            for (int r = 0; r < 5; r++) inv.setStack(r * 9 + 4, pane.copy());
            for (int i = 45; i < 54; i++) inv.setStack(i, pane.copy());
            building = false;
            buttons();
            inv.addListener(x -> changed());
        }

        void open(ServerPlayerEntity p, int side) {
            String other = side == 0 ? nameB : nameA;
            Text title = Text.literal(side == 0 ? "You  ⇄  " + other : other + "  ⇄  You").formatted(Formatting.DARK_GREEN, Formatting.BOLD);
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, pi, pl) -> new Handler(sync, pi, this, side), title));
        }

        boolean own(int side, int slot) { return side == 0 ? sideA(slot) : sideB(slot); }

        void changed() {
            if (building || done) return;
            if (readyA || readyB || countdown >= 0) {
                readyA = readyB = false;
                countdown = -1;
                buttons();
            }
        }

        void buttons() {
            building = true;
            inv.setStack(READY_A, readyA ? named(Items.LIME_CONCRETE, nameA + ": READY", Formatting.GREEN) : named(Items.RED_CONCRETE, nameA + ": not ready (click)", Formatting.RED));
            inv.setStack(READY_B, readyB ? named(Items.LIME_CONCRETE, nameB + ": READY", Formatting.GREEN) : named(Items.RED_CONCRETE, nameB + ": not ready (click)", Formatting.RED));
            inv.setStack(STATUS, countdown >= 0 ? named(Items.CLOCK, "Trading in " + (countdown / 20 + 1) + "...", Formatting.YELLOW)
                    : named(Items.PAPER, "Put items on your side, then press READY", Formatting.GRAY));
            building = false;
        }

        void toggle(int side) {
            if (side == 0) readyA = !readyA; else readyB = !readyB;
            countdown = readyA && readyB ? 60 : -1;
            buttons();
        }

        void tick(MinecraftServer server) {
            if (done) { ACTIVE.remove(this); return; }
            if (countdown < 0) return;
            if (countdown % 20 == 0) {
                buttons();
                for (UUID u : new UUID[]{a, b}) {
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(u);
                    if (p != null) p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.PLAYERS, 0.6f, 1.2f);
                }
            }
            if (--countdown < 0) complete(server);
        }

        List<ItemStack> take(boolean left) {
            List<ItemStack> out = new ArrayList<>();
            building = true;
            for (int i = 0; i < 45; i++) {
                if (left ? sideA(i) : sideB(i)) {
                    ItemStack s = inv.getStack(i);
                    if (!s.isEmpty()) out.add(s.copy());
                    inv.setStack(i, ItemStack.EMPTY);
                }
            }
            building = false;
            return out;
        }

        void complete(MinecraftServer server) {
            ServerPlayerEntity pa = server.getPlayerManager().getPlayer(a), pb = server.getPlayerManager().getPlayer(b);
            if (pa == null || pb == null) { cancel(server); return; }
            done = true;
            List<ItemStack> fromA = take(true), fromB = take(false);
            for (ItemStack s : fromA) pb.getInventory().offerOrDrop(s);
            for (ItemStack s : fromB) pa.getInventory().offerOrDrop(s);
            for (ServerPlayerEntity p : new ServerPlayerEntity[]{pa, pb}) {
                if (p.currentScreenHandler instanceof Handler h && h.session == this) p.closeHandledScreen();
                msg(p, "Trade complete!", Formatting.GREEN);
                p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.7f, 1.4f);
            }
            ACTIVE.remove(this);
        }

        void cancel(MinecraftServer server) { cancel(server, null); }

        /** leaving = a player object we already hold (used while they disconnect). */
        void cancel(MinecraftServer server, ServerPlayerEntity leaving) {
            if (done) return;
            done = true;
            ServerPlayerEntity pa = server.getPlayerManager().getPlayer(a), pb = server.getPlayerManager().getPlayer(b);
            if (leaving != null && leaving.getUuid().equals(a)) pa = leaving;
            if (leaving != null && leaving.getUuid().equals(b)) pb = leaving;
            List<ItemStack> fromA = take(true), fromB = take(false);
            give(pa, fromA, a, server);
            give(pb, fromB, b, server);
            for (ServerPlayerEntity p : new ServerPlayerEntity[]{pa, pb}) {
                if (p == null) continue;
                if (p != leaving && p.currentScreenHandler instanceof Handler h && h.session == this) p.closeHandledScreen();
                msg(p, "Trade cancelled - items returned.", Formatting.GRAY);
            }
            ACTIVE.remove(this);
        }

        void give(ServerPlayerEntity p, List<ItemStack> items, UUID owner, MinecraftServer server) {
            if (p != null) { for (ItemStack s : items) p.getInventory().offerOrDrop(s); return; }
            DracosCore.LOGGER.warn("Trade: {} was offline when cancelling, {} stack(s) could not be returned", owner, items.size());
        }
    }

    static class Handler extends GenericContainerScreenHandler {
        final Session session;
        final int side;

        Handler(int sync, PlayerInventory pi, Session s, int side) {
            super(ScreenHandlerType.GENERIC_9X6, sync, pi, s.inv, 6);
            this.session = s;
            this.side = side;
        }

        @Override
        public void onSlotClick(int slot, int button, SlotActionType type, PlayerEntity player) {
            if (session.done || type == SlotActionType.PICKUP_ALL || type == SlotActionType.QUICK_CRAFT || type == SlotActionType.CLONE) {
                syncState();
                return;
            }
            if (slot >= 0 && slot < 54) {
                if (slot == (side == 0 ? READY_A : READY_B)) { session.toggle(side); syncState(); return; }
                if (!session.own(side, slot)) { syncState(); return; }
            }
            super.onSlotClick(slot, button, type, player);
            session.changed();
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int index) {
            if (session.done || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
            Slot from = slots.get(index);
            if (!from.hasStack()) return ItemStack.EMPTY;
            ItemStack stack = from.getStack();
            if (index < 54) {
                if (!session.own(side, index)) return ItemStack.EMPTY;
                insertItem(stack, 54, slots.size(), true);
            } else {
                // into my side: first stack onto same items, then empty slots
                for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
                    for (int i = 0; i < 45 && !stack.isEmpty(); i++) {
                        if (!session.own(side, i)) continue;
                        ItemStack there = session.inv.getStack(i);
                        if (pass == 0 && !there.isEmpty() && ItemStack.areItemsAndComponentsEqual(there, stack)) {
                            int move = Math.min(stack.getCount(), there.getMaxCount() - there.getCount());
                            if (move > 0) { there.increment(move); stack.decrement(move); session.inv.markDirty(); }
                        } else if (pass == 1 && there.isEmpty()) {
                            session.inv.setStack(i, stack.copy());
                            stack.setCount(0);
                        }
                    }
                }
            }
            from.markDirty();
            session.changed();
            return ItemStack.EMPTY;
        }

        @Override
        public void onClosed(PlayerEntity player) {
            super.onClosed(player);
            if (session.done || !(player instanceof ServerPlayerEntity sp)) return;
            var server = sp.getEntityWorld().getServer();
            // a close right after opening (chat screen closing, anticheat, ...) is not the player's choice: reopen
            if (System.currentTimeMillis() - session.created < 1500 && session.reopens < 4 && !sp.isDisconnected()) {
                session.reopens++;
                DracosCore.LOGGER.info("[Trade] {} closed the trade right after it opened - reopening", sp.getName().getString());
                server.execute(() -> { if (!session.done && sp.currentScreenHandler == sp.playerScreenHandler) session.open(sp, side); });
                return;
            }
            DracosCore.LOGGER.info("[Trade] {} closed the trade - cancelled", sp.getName().getString());
            session.cancel(server, sp);
        }
    }
}
