package com.dracos.core.mixin;

import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every arm swing (also into the air) -> legendary slash effect. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class SwingMixin {
    @Inject(method = "onHandSwing", at = @At("TAIL"), require = 0)
    private void dracos$swing(HandSwingC2SPacket packet, CallbackInfo ci) {
        try {
            com.dracos.core.SwingFx.onSwing(((ServerPlayNetworkHandler) (Object) this).player);
        } catch (Throwable ignored) {}
    }
}
