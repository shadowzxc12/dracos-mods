package com.dracos.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Spinning animations for /coinflip and /jackpot. The result is decided first; the payout runs when the animation ends. */
public final class GambleAnim {
    private GambleAnim() {}

    record Who(UUID uuid, String name, long amount) {}

    private abstract static class Anim {
        final MinecraftServer server;
        final List<UUID> viewers;
        final Runnable done;
        final SimpleInventory inv = new SimpleInventory(27);
        int tick;
        boolean finished;

        Anim(MinecraftServer server, List<UUID> viewers, Runnable done, String title) {
            this.server = server; this.viewers = viewers; this.done = done;
            ItemStack pane = blank(Items.BLACK_STAINED_GLASS_PANE);
            for (int i = 0; i < 27; i++) inv.setStack(i, pane.copy());
            for (UUID u : viewers) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(u);
                if (p != null) p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, pi, pl) -> new Handler(sync, pi, inv),
                        Text.literal(title).formatted(Formatting.GOLD, Formatting.BOLD)));
            }
        }

        abstract boolean step(); // false = finished

        void sync() {
            for (UUID u : viewers) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(u);
                if (p != null && p.currentScreenHandler instanceof Handler h && h.inv == inv) h.syncState();
            }
        }

        void sound(net.minecraft.sound.SoundEvent e, float pitch) {
            for (UUID u : viewers) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(u);
                if (p != null) p.getEntityWorld().playSound(null, p.getBlockPos(), e, SoundCategory.PLAYERS, 0.5f, pitch);
            }
        }

        void finish() {
            if (finished) return;
            finished = true;
            try { done.run(); } catch (Exception e) { DracosCore.LOGGER.warn("Gamble payout failed", e); }
        }

        void closeLater() {
            for (UUID u : viewers) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(u);
                if (p != null && p.currentScreenHandler instanceof Handler h && h.inv == inv) p.closeHandledScreen();
            }
        }
    }

    private static final List<Anim> RUNNING = new ArrayList<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (RUNNING.isEmpty()) return;
            Iterator<Anim> it = RUNNING.iterator();
            while (it.hasNext()) {
                Anim a = it.next();
                a.tick++;
                if (!a.step()) {
                    a.finish();
                    it.remove();
                }
            }
        });
        // never lose a payout to a restart
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { for (Anim a : RUNNING) a.finish(); RUNNING.clear(); });
    }

    // ------------------------------------------------------------------ helpers
    private static ItemStack blank(Item i) {
        ItemStack s = new ItemStack(i);
        s.set(DataComponentTypes.ITEM_NAME, Text.literal(" "));
        return s;
    }

    static ItemStack head(MinecraftServer server, Who w, String extra, Formatting color) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(w.uuid());
        if (p != null) try { s.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(p.getGameProfile())); } catch (Throwable ignored) {}
        s.set(DataComponentTypes.ITEM_NAME, Text.literal(w.name()).formatted(color, Formatting.BOLD));
        if (extra != null) s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(extra).styled(st -> st.withColor(Formatting.GRAY).withItalic(false)))));
        return s;
    }

    // ------------------------------------------------------------------ coinflip
    /** Delays (ticks) between flips: fast at first, then slowing down. */
    private static final int[] FLIP_AT = {0, 2, 4, 6, 8, 10, 12, 15, 18, 21, 25, 29, 34, 40, 47, 55, 64};

    public static void coinflip(MinecraftServer server, Who a, Who b, boolean aWins, Runnable payout) {
        Anim anim = new Anim(server, List.of(a.uuid(), b.uuid()), payout, "Coinflip") {
            int flips = 0;
            final int total = FLIP_AT.length;
            // the side shown first is chosen so the last flip lands on the winner
            final boolean startA = (total % 2 == 1) == aWins;

            {
                inv.setStack(10, head(server, a, "Bet " + Money.fmt(a.amount()), Formatting.YELLOW));
                inv.setStack(16, head(server, b, "Bet " + Money.fmt(b.amount()), Formatting.AQUA));
                inv.setStack(11, blank(Items.YELLOW_STAINED_GLASS_PANE));
                inv.setStack(15, blank(Items.LIGHT_BLUE_STAINED_GLASS_PANE));
                sync();
            }

            @Override boolean step() {
                if (flips < total && tick >= FLIP_AT[flips]) {
                    boolean showA = (flips % 2 == 0) == startA;
                    inv.setStack(13, head(server, showA ? a : b, null, showA ? Formatting.YELLOW : Formatting.AQUA));
                    inv.setStack(4, blank(showA ? Items.YELLOW_STAINED_GLASS_PANE : Items.LIGHT_BLUE_STAINED_GLASS_PANE));
                    inv.setStack(22, blank(showA ? Items.YELLOW_STAINED_GLASS_PANE : Items.LIGHT_BLUE_STAINED_GLASS_PANE));
                    flips++;
                    sound(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.8f + flips * 0.06f);
                    sync();
                    return true;
                }
                if (flips >= total && tick == FLIP_AT[total - 1] + 8) {
                    Who w = aWins ? a : b;
                    ItemStack win = head(server, w, "WINNER!", Formatting.GREEN);
                    win.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                    inv.setStack(13, win);
                    for (int s : new int[]{3, 4, 5, 12, 14, 21, 22, 23}) inv.setStack(s, blank(Items.LIME_STAINED_GLASS_PANE));
                    sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
                    sync();
                    finish();
                }
                if (tick >= FLIP_AT[total - 1] + 50) { closeLater(); return false; }
                return true;
            }
        };
        RUNNING.add(anim);
    }

    // ------------------------------------------------------------------ jackpot roulette
    public static void jackpot(MinecraftServer server, List<Who> entries, int winner, Runnable payout) {
        long pot = 0;
        for (Who w : entries) pot += w.amount();
        // weighted strip of heads; the winner sits exactly where the strip stops
        Random r = new Random();
        List<Who> strip = new ArrayList<>();
        int stopAt = 44;
        for (int i = 0; i < stopAt + 10; i++) {
            long roll = (long) (r.nextDouble() * pot), acc = 0;
            Who pick = entries.get(entries.size() - 1);
            for (Who w : entries) { acc += w.amount(); if (roll < acc) { pick = w; break; } }
            strip.add(pick);
        }
        strip.set(stopAt, entries.get(winner));
        long fPot = pot;
        List<UUID> viewers = new ArrayList<>();
        for (Who w : entries) viewers.add(w.uuid());
        Anim anim = new Anim(server, viewers, payout, "Jackpot  " + Money.fmt(pot)) {
            int pos = 0;
            int next = 0;
            final int last = stopAt - 4;

            {
                inv.setStack(4, named(Items.HOPPER, "▼"));
                inv.setStack(22, named(Items.HOPPER, "▲"));
                draw();
            }

            ItemStack named(Item i, String n) {
                ItemStack s = new ItemStack(i);
                s.set(DataComponentTypes.ITEM_NAME, Text.literal(n).formatted(Formatting.GOLD, Formatting.BOLD));
                return s;
            }

            void draw() {
                for (int c = 0; c < 9; c++) {
                    Who w = strip.get(pos + c);
                    String pct = String.format(java.util.Locale.ROOT, "%.1f%% chance", 100.0 * w.amount() / fPot);
                    inv.setStack(9 + c, head(server, w, pct, c == 4 ? Formatting.GOLD : Formatting.WHITE));
                }
                sync();
            }

            @Override boolean step() {
                if (pos < last && tick >= next) {
                    pos++;
                    int left = last - pos;
                    next = tick + (left > 20 ? 1 : left > 10 ? 2 : left > 5 ? 4 : left > 2 ? 7 : 11);
                    draw();
                    sound(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 1.0f + Math.min(1f, pos / 40f));
                    return true;
                }
                if (pos >= last && !finished && tick >= next + 6) {
                    ItemStack win = head(server, strip.get(stopAt), "WINNER!", Formatting.GREEN);
                    win.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                    inv.setStack(13, win);
                    for (int s : new int[]{3, 5, 21, 23}) inv.setStack(s, blank(Items.LIME_STAINED_GLASS_PANE));
                    sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
                    sync();
                    finish();
                    next = tick;
                }
                if (finished && tick >= next + 50) { closeLater(); return false; }
                return true;
            }
        };
        RUNNING.add(anim);
    }

    static class Handler extends GenericContainerScreenHandler {
        final SimpleInventory inv;
        Handler(int sync, PlayerInventory pi, SimpleInventory inv) { super(ScreenHandlerType.GENERIC_9X3, sync, pi, inv, 3); this.inv = inv; }
        @Override public void onSlotClick(int slot, int button, SlotActionType type, PlayerEntity player) { this.syncState(); }
        @Override public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
    }
}
