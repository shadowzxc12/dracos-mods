package com.dracos.core.mixin;

import com.dracos.core.Combat;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class CommandMixin {
    @Shadow public ServerPlayerEntity player;

    @Inject(method = "onCommandExecution", at = @At("HEAD"), cancellable = true, require = 0)
    private void dracos$blockInCombat(CommandExecutionC2SPacket packet, CallbackInfo ci) {
        if (Combat.shouldBlock(player, packet.command())) {
            player.sendMessage(Text.literal("You can't use that command in combat!").formatted(Formatting.RED), false);
            ci.cancel();
        }
    }
}
