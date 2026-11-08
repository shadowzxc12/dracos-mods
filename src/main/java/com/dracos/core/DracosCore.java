package com.dracos.core;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DracosCore implements ModInitializer {
    /** Minehut's proxy owns /settings (network settings, text only). We drop any server /settings and use /setting. */
    @SuppressWarnings("unchecked")
    private static void takeOverSettings(net.minecraft.server.MinecraftServer server) {
        var dispatcher = server.getCommandManager().getDispatcher();
        var root = dispatcher.getRoot();
        try {
            for (String field : new String[]{"children", "literals", "arguments"}) {
                java.lang.reflect.Field f = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField(field);
                f.setAccessible(true);
                java.util.Map<String, ?> m = (java.util.Map<String, ?>) f.get(root);
                m.remove("settings");
                for (String c : new String[]{"rtp", "wild", "randomteleport", "spawn"}) m.remove(c); // Essential Commands' own /rtp and /spawn
            }
            // our /rtp for everyone (no permission needed)
            for (String c : new String[]{"rtp", "wild", "randomteleport"})
                dispatcher.register(net.minecraft.server.command.CommandManager.literal(c).executes(ctx -> {
                    RandomRespawn.rtp(ctx.getSource().getPlayerOrThrow());
                    return 1;
                }));
            // /spawn -> our spawn hub (Essential Commands' /spawn pointed somewhere else)
            dispatcher.register(net.minecraft.server.command.CommandManager.literal("spawn").executes(ctx -> {
                var pl = ctx.getSource().getPlayerOrThrow();
                if (Combat.inCombat(pl)) { pl.sendMessage(net.minecraft.text.Text.literal("You can't use /spawn in combat!").formatted(net.minecraft.util.Formatting.RED), false); return 0; }
                var w = Hub.CFG.radius > 0 ? Hub.world(server) : server.getOverworld();
                if (Hub.CFG.radius > 0) pl.teleport(w, Hub.CFG.cx + 0.5, Hub.CFG.cy, Hub.CFG.cz + 6.5, java.util.Set.of(), 180f, 0f, true);
                else { var sp = w.getSpawnPoint().getPos(); pl.teleport(w, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, java.util.Set.of(), pl.getYaw(), pl.getPitch(), true); }
                return 1;
            }));
            for (var p : server.getPlayerManager().getPlayerList()) server.getCommandManager().sendCommandTree(p);
            LOGGER.info("Removed server /settings; Dracos settings menu is /menu (Minehut proxy owns /settings and /setting)");
        } catch (Exception e) {
            LOGGER.warn("Could not take over /settings", e);
        }
    }

    public static final String MOD_ID = "dracos_core";
    public static final Logger LOGGER = LoggerFactory.getLogger("DracosCore");

    @Override
    public void onInitialize() {
        // hub + duels first: their damage rules must run before combat tagging
        Hub.register();
        Duels.register();
        Crates.register();
        Trade.register();
        Leaderboards.register();
        PlayerWarps.register();
        InfiniteTrades.register();
        Abilities.register();
        Abilities.registerExtras();
        Effects.register();
        Stats.register();
        Ranks.register();
        Tutorial.register();
        Pack.register();
        Bounty.register();
        Announcer.register();
        Combat.register();
        RandomRespawn.register();
        StoreBridge.register();
        Heads.register();
        Gamble.register();
        GambleAnim.register();
        Clans.register();
        Spawners.register();
        Quests.register();
        Admin.register();
        MobCarry.register();
        Fishing.register();
        MobStacker.register();
        Skills.register();
        Legendary3.register();
        AutoSell.register();
        Enchants.register();
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("polydex")) {
            try { PolydexCompat.register(); } catch (Throwable t) { LOGGER.warn("Polydex compat disabled", t); }
        }   // last: its block-break hook must run after all protection checks
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("polymer-core")) {
            try { SellTooltips.register(); } catch (Throwable t) { LOGGER.warn("Sell tooltips disabled", t); }
        }

        // load /sell prices and put the Legendary category into /shop
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SellPrices.load();
            ShopSync.sync(server);
            Ranks.load(server);
            Announcer.load();
            StoreBridge.load();
            Clans.load();
            Spawners.load();
            Quests.load();
            Admin.load();
            AutoSell.load();
            Gamble.load();
            Skills.load();
            PlayerWarps.load();
            Bank.load();
            Hub.load();
            Leaderboards.load();
            takeOverSettings(server);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // /sell  -> okno na predaj
            // /sell hand -> preda vec v ruke
            dispatcher.register(CommandManager.literal("sell")
                    .executes(ctx -> {
                        SellMenu.open(ctx.getSource().getPlayerOrThrow());
                        return 1;
                    })
                    .then(CommandManager.literal("hand").executes(ctx -> {
                        SellMenu.sellHand(ctx.getSource().getPlayerOrThrow());
                        return 1;
                    })));

            // /ranks -> list of ranks and perks, /hat -> wear held item (VIP+)
            dispatcher.register(CommandManager.literal("ranks").executes(ctx -> {
                Ranks.showRanks(ctx.getSource().getPlayerOrThrow());
                return 1;
            }).then(CommandManager.argument("code", StringArgumentType.word()).executes(ctx -> {
                StoreBridge.start(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "code").toUpperCase());
                return 1;
            })));
            // /rank CODE -> activate a rank bought on the web store, /rank -> pick up paid orders for my name
            dispatcher.register(CommandManager.literal("rank").executes(ctx -> {
                StoreBridge.start(ctx.getSource().getPlayerOrThrow(), null);
                return 1;
            }).then(CommandManager.argument("code", StringArgumentType.word()).executes(ctx -> {
                StoreBridge.start(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "code").toUpperCase());
                return 1;
            })));
            dispatcher.register(CommandManager.literal("hat").executes(ctx -> {
                Ranks.hat(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));

            dispatcher.register(CommandManager.literal("rankshop").executes(ctx -> {
                RankShop.open(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            dispatcher.register(CommandManager.literal("daily").executes(ctx -> {
                Perks.daily(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            dispatcher.register(CommandManager.literal("kit").executes(ctx -> {
                Perks.kit(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            dispatcher.register(CommandManager.literal("craft").executes(ctx -> {
                Perks.craft(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            dispatcher.register(CommandManager.literal("ec").executes(ctx -> {
                Perks.enderChest(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            dispatcher.register(CommandManager.literal("enderchest").executes(ctx -> {
                Perks.enderChest(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            dispatcher.register(CommandManager.literal("glow").executes(ctx -> {
                Perks.glow(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            dispatcher.register(CommandManager.literal("bounty")
                    .executes(ctx -> { Bounty.list(ctx.getSource().getPlayerOrThrow()); return 1; })
                    .then(CommandManager.argument("target", EntityArgumentType.player())
                            .then(CommandManager.argument("amount", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
                                    .executes(ctx -> {
                                        Bounty.place(ctx.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(ctx, "target"),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "amount"));
                                        return 1;
                                    }))));
            dispatcher.register(CommandManager.literal("backpack").executes(ctx -> {
                Backpack.open(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            dispatcher.register(CommandManager.literal("bp").executes(ctx -> {
                Backpack.open(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            dispatcher.register(CommandManager.literal("discord").executes(ctx -> {
                ctx.getSource().getPlayerOrThrow().sendMessage(Announcer.discordMessage(), false);
                return 1;
            }));
            Clans.commands(dispatcher);
            dispatcher.register(CommandManager.literal("wild").executes(ctx -> { RandomRespawn.rtp(ctx.getSource().getPlayerOrThrow()); return 1; }));
            dispatcher.register(CommandManager.literal("god").executes(ctx -> Admin.god(ctx.getSource().getPlayerOrThrow())));
            Gamble.commands(dispatcher);
            PlayerWarps.commands(dispatcher);
            Bank.commands(dispatcher);
            Hub.commands(dispatcher);
            Crates.commands(dispatcher);
            Duels.commands(dispatcher);
            Trade.commands(dispatcher);
            dispatcher.register(CommandManager.literal("skills").executes(ctx -> { Skills.open(ctx.getSource().getPlayerOrThrow()); return 1; }));
            dispatcher.register(CommandManager.literal("autosell").executes(ctx -> {
                AutoSell.open(ctx.getSource().getPlayerOrThrow(), 0);
                return 1;
            }));
            dispatcher.register(CommandManager.literal("spawners").executes(ctx -> {
                Spawners.open(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            dispatcher.register(CommandManager.literal("quests").executes(ctx -> {
                Quests.open(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            dispatcher.register(CommandManager.literal("quest").executes(ctx -> {
                Quests.open(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            dispatcher.register(CommandManager.literal("menu").executes(ctx -> {
                Settings.open(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            dispatcher.register(CommandManager.literal("tutorial").executes(ctx -> {
                Tutorial.give(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            dispatcher.register(CommandManager.literal("store").executes(ctx -> {
                Store.show(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            dispatcher.register(CommandManager.literal("homelimit").executes(ctx -> {
                Homes.info(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));

            // /legendary -> obchod s legendarnymi vecami
            dispatcher.register(CommandManager.literal("legendary")
                    .executes(ctx -> {
                        LegendaryShop.open(ctx.getSource().getPlayerOrThrow());
                        return 1;
                    }));

            // /dracos give <hrac> <vec>   (len OP)
            dispatcher.register(CommandManager.literal("dracos")
                    .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                    .then(CommandManager.literal("reload").executes(ctx -> {
                        SellPrices.load();
                        StoreBridge.load();
                        ShopSync.sync(ctx.getSource().getServer());
                        ctx.getSource().sendFeedback(() -> Text.literal("Dracos Core reloaded (sell prices + Legendary shop).")
                                .formatted(Formatting.GREEN), true);
                        return 1;
                    }))
                    .then(CommandManager.literal("lastpos").then(CommandManager.argument("player", StringArgumentType.word())
                            .executes(ctx -> Admin.lastpos(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
                    .then(CommandManager.literal("kill").then(CommandManager.argument("player", StringArgumentType.word())
                            .executes(ctx -> Admin.kill(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
                    .then(CommandManager.literal("unkill").then(CommandManager.argument("player", StringArgumentType.word())
                            .executes(ctx -> Admin.unkill(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
                    .then(CommandManager.literal("homes")
                            .then(CommandManager.argument("player", StringArgumentType.word())
                                    .then(CommandManager.argument("limit", IntegerArgumentType.integer(0, 10000))
                                            .executes(ctx -> {
                                                String n = StringArgumentType.getString(ctx, "player");
                                                int l = IntegerArgumentType.getInteger(ctx, "limit");
                                                Homes.setLimit(n, l);
                                                ctx.getSource().sendFeedback(() -> Text.literal(l <= 0 ? n + " now uses the rank home limit"
                                                        : n + " can now set " + l + " homes").formatted(Formatting.GREEN), true);
                                                return 1;
                                            }))))
                    .then(CommandManager.literal("rank")
                            .then(CommandManager.argument("player", StringArgumentType.word())
                                    .then(CommandManager.argument("rank", StringArgumentType.word())
                                            .suggests((ctx, b) -> {
                                                for (Ranks.Rank r : Ranks.Rank.values()) b.suggest(r.name().toLowerCase());
                                                return b.buildFuture();
                                            })
                                            .then(CommandManager.argument("days", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 3650))
                                                    .executes(ctx -> {
                                                        String name = StringArgumentType.getString(ctx, "player");
                                                        String rank = StringArgumentType.getString(ctx, "rank");
                                                        int days = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "days");
                                                        String err = Ranks.set(ctx.getSource().getServer(), name, rank, days);
                                                        if (err != null) { ctx.getSource().sendError(Text.literal(err)); return 0; }
                                                        ctx.getSource().sendFeedback(() -> Text.literal("Set rank of " + name + " to "
                                                                + rank.toUpperCase() + " for " + days + " days").formatted(Formatting.GREEN), true);
                                                        return 1;
                                                    }))
                                            .executes(ctx -> {
                                                String name = StringArgumentType.getString(ctx, "player");
                                                String rank = StringArgumentType.getString(ctx, "rank");
                                                String err = Ranks.set(ctx.getSource().getServer(), name, rank);
                                                if (err != null) {
                                                    ctx.getSource().sendError(Text.literal(err));
                                                    return 0;
                                                }
                                                ctx.getSource().sendFeedback(() -> Text.literal("Set rank of " + name + " to "
                                                        + rank.toUpperCase()).formatted(Formatting.GREEN), true);
                                                return 1;
                                            }))))
                    .then(CommandManager.literal("give")
                            .then(CommandManager.argument("player", EntityArgumentType.player())
                                    .then(CommandManager.argument("item", StringArgumentType.word())
                                            .suggests((ctx, b) -> {
                                                for (String id : LegendaryItems.IDS) b.suggest(id);
                                                return b.buildFuture();
                                            })
                                            .executes(ctx -> {
                                                ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                                String id = StringArgumentType.getString(ctx, "item");
                                                if (!LegendaryItems.IDS.contains(id)) {
                                                    ctx.getSource().sendError(Text.literal("Unknown item: " + id));
                                                    return 0;
                                                }
                                                target.getInventory().offerOrDrop(
                                                        LegendaryItems.create(id, target.getEntityWorld().getRegistryManager()));
                                                ctx.getSource().sendFeedback(() -> Text.literal("Gave " + id + " to "
                                                        + target.getName().getString()).formatted(Formatting.GREEN), true);
                                                return 1;
                                            })))));
        });

        LOGGER.info("Dracos Core loaded");
    }
}
