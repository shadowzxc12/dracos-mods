package com.dracos.core;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;

/** Merchant's Axe: left-click a chest/barrel to sell every sellable item inside (same prices + rank bonus as /sell). */
public final class SellAxe {
    private SellAxe() {}

    static final String ID = "merchant_axe";
    private static final Map<UUID, Long> LAST = new java.util.concurrent.ConcurrentHashMap<>();

    public static void register() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> {
            if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity p)) return ActionResult.PASS;
            if (!ID.equals(LegendaryItems.idOf(p.getMainHandStack()))) return ActionResult.PASS;
            if (!(sw.getBlockEntity(pos) instanceof Inventory)) return ActionResult.PASS;
            long now = sw.getTime();
            Long last = LAST.get(p.getUuid());
            if (last != null && now - last < 10) return ActionResult.FAIL; // one sell per 0.5s
            LAST.put(p.getUuid(), now);
            if (Hub.inHubPublic(sw, pos) || !allowed(p, sw, pos)) {
                p.sendMessage(Text.literal("You can only sell chests in your own claim or unclaimed land.").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }
            var state = sw.getBlockState(pos);
            Inventory inv = state.getBlock() instanceof net.minecraft.block.ChestBlock cb
                    ? net.minecraft.block.ChestBlock.getInventory(cb, state, sw, pos, true) : null; // whole double chest
            if (inv == null) inv = (Inventory) sw.getBlockEntity(pos);
            sell(p, sw, pos, inv);
            return ActionResult.FAIL; // don't damage / break the chest
        });
    }

    private static void sell(ServerPlayerEntity p, ServerWorld sw, BlockPos pos, Inventory inv) {
        Map<String, Double> prices = Money.sellPrices();
        double total = 0;
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (st.isEmpty()) continue;
            Double each = SellMenu.priceOf(st, prices);
            if (each == null) continue;
            total += each * st.getCount();
            count += st.getCount();
            inv.setStack(i, ItemStack.EMPTY);
        }
        inv.markDirty();
        if (count == 0) {
            p.sendMessage(Text.literal("Nothing sellable in this chest.").formatted(Formatting.GRAY), true);
            return;
        }
        SellMenu.pay(p, (long) Math.floor(total), count);
        sw.playSound(null, pos, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 1f, 1.2f);
        sw.spawnParticles(net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 12, 0.3, 0.2, 0.3, 0);
    }

    /** Open Parties and Claims (via reflection): only unclaimed chunks or the player's own claims. Fails closed. */
    private static boolean allowed(ServerPlayerEntity p, ServerWorld sw, BlockPos pos) {
        if (p.hasPermissionLevel(2)) return true;
        try {
            Class<?> api = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
            Object inst = api.getMethod("get", net.minecraft.server.MinecraftServer.class).invoke(null, sw.getServer());
            Object claims = api.getMethod("getServerClaimsManager").invoke(inst);
            Object claim = null;
            for (var m : claims.getClass().getMethods()) {
                if (m.getName().equals("get") && m.getParameterCount() == 3 && m.getParameterTypes()[1] == int.class) {
                    m.setAccessible(true);
                    claim = m.invoke(claims, sw.getRegistryKey().getValue(), pos.getX() >> 4, pos.getZ() >> 4);
                    break;
                }
            }
            if (claim == null) return true;
            var gm = claim.getClass().getMethod("getPlayerId");
            gm.setAccessible(true);
            Object owner = gm.invoke(claim);
            return p.getUuid().equals(owner);
        } catch (ClassNotFoundException e) {
            return true; // claims mod not installed
        } catch (Throwable t) {
            DracosCore.LOGGER.warn("[SellAxe] claim check failed: {}", t.toString());
            return false;
        }
    }
}
