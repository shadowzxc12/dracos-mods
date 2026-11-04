package com.dracos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;

/** /backpack - personal storage that goes everywhere with you. Member 27 slots, VIP+ 54. Saved in config/dracos/backpacks/<uuid>.json */
public final class Backpack {
    private Backpack() {}

    private static Path file(ServerPlayerEntity p) {
        return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("backpacks").resolve(p.getUuidAsString() + ".json");
    }

    public static void open(ServerPlayerEntity p) {
        boolean big = Ranks.of(p).atLeast(Ranks.Rank.VIP);
        int rows = big ? 6 : 3;
        SimpleInventory inv = new SimpleInventory(rows * 9);
        var ops = p.getEntityWorld().getRegistryManager().getOps(JsonOps.INSTANCE);
        try {
            Path f = file(p);
            if (Files.exists(f)) {
                JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                for (var e : o.entrySet()) {
                    int slot = Integer.parseInt(e.getKey());
                    ItemStack.CODEC.parse(ops, e.getValue()).result().ifPresent(st -> {
                        if (slot < inv.size()) inv.setStack(slot, st);
                        else p.getInventory().offerOrDrop(st); // rank expired: give back items that no longer fit
                    });
                }
            }
        } catch (Exception ex) {
            DracosCore.LOGGER.warn("Backpack load failed for {}", p.getName().getString(), ex);
            p.sendMessage(Text.literal("Your backpack could not be loaded. Tell an admin.").formatted(Formatting.RED), false);
            return;
        }
        ScreenHandlerType<GenericContainerScreenHandler> type = big ? ScreenHandlerType.GENERIC_9X6 : ScreenHandlerType.GENERIC_9X3;
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, pi, pl) -> new GenericContainerScreenHandler(type, id, pi, inv, rows) {
            @Override
            public void onClosed(PlayerEntity player) {
                super.onClosed(player);
                save(p, inv);
            }
        }, Text.literal("🎒 Backpack" + (big ? "" : "  (VIP+ = 54 slots)")).formatted(Formatting.DARK_GREEN)));
    }

    private static void save(ServerPlayerEntity p, SimpleInventory inv) {
        var ops = p.getEntityWorld().getRegistryManager().getOps(JsonOps.INSTANCE);
        JsonObject o = new JsonObject();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (st.isEmpty()) continue;
            final int slot = i;
            ItemStack.CODEC.encodeStart(ops, st).result().ifPresent(j -> o.add(String.valueOf(slot), j));
        }
        try {
            Path f = file(p);
            Files.createDirectories(f.getParent());
            Files.writeString(f, o.toString());
        } catch (Exception ex) {
            DracosCore.LOGGER.error("Backpack save failed for {}", p.getName().getString(), ex);
        }
    }
}
