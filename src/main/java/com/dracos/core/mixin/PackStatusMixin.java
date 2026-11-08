package com.dracos.core.mixin;

import net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs what each client does with the server texture pack (accepted / downloaded / failed / declined). */
@Mixin(ServerCommonNetworkHandler.class)
public abstract class PackStatusMixin {
    @Inject(method = "onResourcePackStatus", at = @At("HEAD"), require = 0)
    private void dracos$logPack(ResourcePackStatusC2SPacket packet, CallbackInfo ci) {
        String who = ((Object) this) instanceof ServerPlayNetworkHandler h ? h.player.getName().getString() : "?";
        com.dracos.core.DracosCore.LOGGER.info("[Pack] {} -> {} ({})", who, packet.status(), packet.id());
    }
}
