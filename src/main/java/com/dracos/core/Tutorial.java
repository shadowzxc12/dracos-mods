package com.dracos.core;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/** Tutorial book: given on the first join, again with /tutorial or from /menu. */
public final class Tutorial {
    private Tutorial() {}

    private static final String[][] PAGES = {
            {"#Dracos SMP", "", "Welcome!", "This book explains", "the server.", "", "2 Money", "3 Spawn & Crates", "4 Homes & Travel", "5 Legendary items", "6 Fun & PvP", "7 Ranks", "8 Commands", "9 Rules"},
            {"#Money", "", "/sell  sell window", "/sell hand", "/autosell", "/shop  buy items", "/ah  auction house", "/orders  buy orders", "/bank  1% a day", "/quests  5 daily", "/daily  free money", "/skills  level up"},
            {"#Spawn", "", "/spawn", "", "Crates: right-click", "with a key, left-", "click to preview.", "", "AFK zone: money", "every 5 min + keys", "", "Talk to the NPCs", "for every menu."},
            {"#Homes & Travel", "", "/sethome <name>", "/home <name>", "/rtp  or the portal", " at spawn", "/tpa <player>", "/pwarp  warps", "/pwarp set <name>", " your own warp", "", "!No teleporting", "!in combat!"},
            {"#Legendary", "", "/legendary", "", "100 weapons, tools,", "staffs and armor.", "They never break", "and have powers.", "Right-click", "for abilities.", "Full armor sets", "give bonus effects."},
            {"#Fun & PvP", "", "/duel <player> [$]", " 1v1, no item loss", "/trade <player>", " safe trading", "/coinflip  /jackpot", "/clan  make a clan", "/bounty <player> <$>", "", "Kill players for", "their heads ($)."},
            {"#Ranks", "", "Monthly ranks:", "VIP  MVP", "LEGEND  DRACO", "", "More homes, more", "sell money, /kit", "more warps.", "", "/ranks  all perks", "/store  buy a rank"},
            {"#Commands", "", "/menu  settings", "/rankshop", "/kit  free kit (1h)", "/backpack  storage", "/hat  wear a block", "/spawners", "/duel  /trade", "/tutorial", " this book again"},
            {"#Rules", "", "1. No cheats", "2. No griefing", "3. No stealing", "4. Be respectful", "5. No spam or ads", "6. Combat logging", "   = death", "", "Have fun!", "- Dracos team"}
    };

    /** "#" = title, "!" = warning, "/" = command (rest of line is the description), others = plain text. */
    private static Text page(String[] lines) {
        MutableText t = Text.empty();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (i > 0) t.append(Text.literal("\n"));
            if (l.startsWith("#")) t.append(Text.literal(l.substring(1)).formatted(Formatting.DARK_RED, Formatting.BOLD));
            else if (l.startsWith("!")) t.append(Text.literal(l.substring(1)).formatted(Formatting.DARK_RED));
            else if (l.startsWith("/")) {
                int sp = l.indexOf("  ");
                String cmd = sp > 0 ? l.substring(0, sp) : l;
                t.append(Text.literal(cmd).formatted(Formatting.DARK_GREEN, Formatting.BOLD));
                if (sp > 0) t.append(Text.literal(l.substring(sp)).formatted(Formatting.BLACK));
            } else t.append(Text.literal(l).formatted(Formatting.BLACK));
        }
        return t;
    }

    public static ItemStack book() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        for (String[] p : PAGES) pages.add(RawFilteredPair.of(page(p)));
        ItemStack b = new ItemStack(Items.WRITTEN_BOOK);
        b.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContentComponent(
                RawFilteredPair.of("Dracos SMP Guide"), "Dracos SMP", 0, pages, true));
        return b;
    }

    public static void give(ServerPlayerEntity p) {
        p.getInventory().offerOrDrop(book());
        p.sendMessage(Text.literal("📖 You got the Dracos SMP Guide! Read it to learn everything.").formatted(Formatting.GOLD), false);
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            var tag = "dracos_tutorial_given";
            if (!p.getCommandTags().contains(tag)) {
                p.addCommandTag(tag);
                give(p);
            }
        });
    }
}
