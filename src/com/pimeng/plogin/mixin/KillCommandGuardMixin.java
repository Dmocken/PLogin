package com.pimeng.plogin.mixin;

import com.pimeng.plogin.Messages;
import com.pimeng.plogin.PLogin;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.KillCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** /kill 选中未登录玩家时直接拒绝，避免命令显示成功但玩家没死。 */
@Mixin(KillCommand.class)
public abstract class KillCommandGuardMixin {
    @Inject(method = "kill", at = @At("HEAD"), cancellable = true)
    private static void plogin$blockKillCommand(CommandSourceStack source, Collection<? extends Entity> victims, CallbackInfoReturnable<Integer> cir) {
        for (Entity entity : victims) {
            if (entity instanceof ServerPlayer player
                    && PLogin.auth() != null
                    && PLogin.auth().isPending(player)) {
                source.sendFailure(Messages.get("pending.command_blocked"));
                cir.setReturnValue(0);
                return;
            }
        }
    }
}
