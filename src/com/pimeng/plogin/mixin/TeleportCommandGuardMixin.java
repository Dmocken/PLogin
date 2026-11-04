package com.pimeng.plogin.mixin;

import com.pimeng.plogin.Messages;
import com.pimeng.plogin.PLogin;
import java.util.Collection;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.server.commands.LookAt;
import net.minecraft.server.commands.TeleportCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 未登录玩家既不能被传送，也不能作为 /tp 的目标。 */
@Mixin(TeleportCommand.class)
public abstract class TeleportCommandGuardMixin {
    @Inject(method = "teleportToEntity", at = @At("HEAD"), cancellable = true)
    private static void plogin$blockDestination(CommandSourceStack source, Collection<? extends Entity> entities,
                                                Entity destination, CallbackInfoReturnable<Integer> cir) {
        if (isPending(destination)) {
            source.sendFailure(Messages.get("pending.command_blocked"));
            cir.setReturnValue(0);
            return;
        }
        for (Entity entity : entities) {
            if (isPending(entity)) {
                source.sendFailure(Messages.get("pending.command_blocked"));
                cir.setReturnValue(0);
                return;
            }
        }
    }

    @Inject(method = "teleportToPos", at = @At("HEAD"), cancellable = true)
    private static void plogin$blockPosVictims(CommandSourceStack source, Collection<? extends Entity> entities,
                                               ServerLevel level, Coordinates destination, Coordinates rotation,
                                               LookAt lookAt, CallbackInfoReturnable<Integer> cir) {
        for (Entity entity : entities) {
            if (isPending(entity)) {
                source.sendFailure(Messages.get("pending.command_blocked"));
                cir.setReturnValue(0);
                return;
            }
        }
    }

    private static boolean isPending(Entity entity) {
        return entity instanceof ServerPlayer player
                && PLogin.auth() != null
                && PLogin.auth().isPending(player);
    }

    @Inject(method = "performTeleport", at = @At("HEAD"), cancellable = true)
    private static void plogin$blockVictim(CommandSourceStack source, Entity victim, ServerLevel level,
                                           double x, double y, double z, Set<Relative> relatives,
                                           float yRot, float xRot, LookAt lookAt, CallbackInfo ci) {
        if (victim instanceof ServerPlayer player
                && PLogin.auth() != null
                && PLogin.auth().isPending(player)) {
            source.sendFailure(Messages.get("pending.command_blocked"));
            ci.cancel();
        }
    }
}
