package com.dracos.core.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Stops "Named entity ... died" console spam from stacked mobs (thousands of lines a day). */
@Mixin(LivingEntity.class)
public abstract class NamedDeathLogMixin {
    @Redirect(method = "onDeath", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;hasCustomName()Z"), require = 0)
    private boolean dracos$noNamedDeathLog(LivingEntity self, DamageSource source) {
        return false;
    }
}
