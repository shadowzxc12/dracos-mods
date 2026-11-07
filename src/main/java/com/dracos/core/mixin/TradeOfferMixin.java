package com.dracos.core.mixin;

import net.minecraft.village.TradeOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Unlimited trading: using a trade never counts up, so villager trades never lock. */
@Mixin(TradeOffer.class)
public abstract class TradeOfferMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void dracos$noLimit(CallbackInfo ci) {
        ci.cancel();
    }
}
