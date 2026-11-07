package com.dracos.core.mixin;

import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fishing skill XP: use() returns 1 when a fish/loot was reeled in. */
@Mixin(FishingBobberEntity.class)
public abstract class FishingMixin {
    @Inject(method = "use", at = @At("RETURN"))
    private void dracos$caught(ItemStack usedItem, CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValue() == 1 && ((FishingBobberEntity) (Object) this).getPlayerOwner() instanceof ServerPlayerEntity p)
            com.dracos.core.Skills.onFish(p);
    }
}
