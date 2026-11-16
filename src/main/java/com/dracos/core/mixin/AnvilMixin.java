package com.dracos.core.mixin;

import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.Property;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No "Too Expensive!": the 40-level limit is removed and the cost is capped at 39 levels. */
@Mixin(AnvilScreenHandler.class)
public abstract class AnvilMixin {
    @Shadow @Final private Property levelCost;

    @ModifyConstant(method = "updateResult", constant = @Constant(intValue = 40), require = 0)
    private int dracos$noLimit(int original) {
        return Integer.MAX_VALUE;
    }

    @Inject(method = "updateResult", at = @At("TAIL"))
    private void dracos$cap(CallbackInfo ci) {
        if (this.levelCost.get() > 39) this.levelCost.set(39);
    }
}
