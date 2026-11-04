package com.pimeng.plogin.mixin;

import com.pimeng.plogin.auth.TransferState;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版登录监听器收到 Cookie 响应会直接断开（“unexpected query”）。
 * 这里拦截 plogin 的 transfer Cookie，把 payload 存到 TransferState（按 Connection 隔离），
 * 供登录线程消费；其他 key 的响应仍交给原版逻辑。
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class LoginCookieMixin {
    @Shadow
    private Connection connection;

    @Inject(method = "handleCookieResponse", at = @At("HEAD"), cancellable = true)
    private void plogin$captureTransferCookie(ServerboundCookieResponsePacket packet, CallbackInfo ci) {
        if (!TransferState.COOKIE_KEY.equals(packet.key())) {
            return;
        }
        try {
            TransferState.store(this.connection, packet.payload());
        } catch (Exception e) {
            com.pimeng.plogin.PLogin.LOGGER.warn("[PLogin] 处理 transfer Cookie 失败", e);
        }
        ci.cancel();
    }

    @Inject(method = "onDisconnect", at = @At("TAIL"))
    private void plogin$clearTransferCookie(DisconnectionDetails details, CallbackInfo ci) {
        try {
            TransferState.clear(this.connection);
        } catch (Exception ignored) {
            // 清理失败不影响断开流程
        }
    }
}
