package com.dracos.core;

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
    public static final String MOD_ID = "dracos_core";
    public static final Logger LOGGER = LoggerFactory.getLogger("DracosCore");

    @Override
    public void onInitialize() {
        Abilities.register();
        Combat.register();
        RandomRespawn.register();

        // load /sell prices and put the Legendary category into /shop
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SellPrices.load();
            ShopSync.sync(server);
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
                        ShopSync.sync(ctx.getSource().getServer());
                        ctx.getSource().sendFeedback(() -> Text.literal("Dracos Core reloaded (sell prices + Legendary shop).")
                                .formatted(Formatting.GREEN), true);
                        return 1;
                    }))
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
