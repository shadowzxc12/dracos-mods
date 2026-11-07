package com.dracos.core.mixin;

import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.Property;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No "Too Expensive!": anvil jobs never cost more than 39 levels. */
@Mixin(AnvilScreenHandler.class)
public abstract class AnvilMixin {
    @Shadow @Final private Property levelCost;

    @Inject(method = "updateResult", at = @At("TAIL"))
    private void dracos$capCost(CallbackInfo ci) {
        if (levelCost.get() > 39) levelCost.set(39);
    }
}
