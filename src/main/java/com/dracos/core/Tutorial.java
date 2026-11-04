package com.dracos.core;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/** Tutorial book: given on the first join, again with /tutorial or from /settings. */
public final class Tutorial {
    private Tutorial() {}

    private static final String[] PAGES = {
            "§6§lDracos SMP§r\n\n§0Welcome! This book explains everything on the server.\n\n§8Pages:\n§02 Money\n§03 Homes & travel\n§04 Legendary items\n§05 Ranks\n§06 Commands\n§07 Rules",
            "§6§lMoney§r\n\n§0Earn money by selling what you farm and mine:\n\n§2/sell§0 - open the sell window\n§2/sell hand§0 - sell held item\n§2/shop§0 - buy items\n§2/ah§0 - auction house\n§2/daily§0 - free money every day\n§0Your balance is on the scoreboard.",
            "§6§lHomes & travel§r\n\n§2/sethome <name>\n/home <name>\n/delhome <name>\n/homelimit§0\n\nMembers get 5 homes, ranks get up to 50.\n\n§2/spawn  /tpa <player>\n/rtp§0 - random teleport\n\n§cNo teleporting in combat!",
            "§6§lLegendary items§r\n\n§0Open §2/legendary§0 to see 25 legendary weapons, tools and armor sets.\n\nThey never break and have special abilities. Right-click swords for powers. Full armor sets give bonus effects.\n\nBuy them with money you earn.",
            "§6§lRanks§r\n\n§0Ranks are monthly: §aVIP§0, §bMVP§0, §6LEGEND§0, §dDRACO§0.\n\nMore homes, bigger /sell bonus, /kit, /craft, /ec, /rankshop and more.\n\n§2/ranks§0 - all perks\n§2/store§0 - buy a rank",
            "§6§lCommands§r\n\n§2/settings§0 - turn effects and messages on/off\n§2/rankshop§0 - rank items\n§2/kit§0 - daily rank kit\n§2/hat§0 - wear any block\n§2/bounty <player> <$>§0 - price on a head\n§2/tutorial§0 - this book again",
            "§6§lRules§r\n\n§01. No cheats or hacked clients\n2. No griefing or stealing\n3. Be respectful in chat\n4. No spam or advertising\n5. Combat logging = death\n\n§8Have fun and good luck!\n§6- Dracos team",
    };

    public static ItemStack book() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        for (String p : PAGES) pages.add(RawFilteredPair.of(Text.literal(p)));
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
