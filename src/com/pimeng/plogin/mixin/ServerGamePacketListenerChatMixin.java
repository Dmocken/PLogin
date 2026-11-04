package com.pimeng.plogin.mixin;

import com.pimeng.plogin.PLogin;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 未登录玩家不能发普通聊天（命令走 CommandsMixin 的白名单）。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerChatMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void plogin$blockChat(ServerboundChatPacket packet, CallbackInfo ci) {
        if (this.player == null) {
            return;
        }
        var auth = PLogin.auth();
        if (auth != null && auth.isPending(this.player)) {
            auth.notifyBlockedChat(this.player);
            ci.cancel();
        }
    }
}
