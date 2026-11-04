package com.pimeng.plogin.mixin;

import com.mojang.brigadier.ParseResults;
import com.pimeng.plogin.PLogin;
import com.pimeng.plogin.auth.AuthManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 未登录玩家只允许执行认证相关命令（/register /login /logout /changepassword 及其别名）。
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void plogin$guardPendingPlayer(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
        CommandSourceStack source = parse.getContext().getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        AuthManager auth = PLogin.auth();
        if (auth == null || !auth.isPending(player)) {
            return;
        }
        if (AuthManager.isAllowedAuthCommand(command)) {
            return;
        }
        auth.notifyBlockedCommand(player);
        ci.cancel();
    }
}
