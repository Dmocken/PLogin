package com.pimeng.plogin.mixin;

import com.pimeng.plogin.auth.HybridAuth;
import com.pimeng.plogin.auth.BackupLoginState;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版登录监听器收到 Cookie 响应会直接断开（“unexpected query”）。
 * 这里拦截属于 PLogin 的 key，把 payload 存到 BackupLoginState（按 Connection 隔离），
 * 供验证线程消费；其他 key 的 Cookie 响应仍交给原版逻辑。
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class LoginCookieMixin {
    @Shadow
    private Connection connection;

    @Inject(method = "handleCookieResponse", at = @At("HEAD"), cancellable = true)
    private void plogin$captureCookie(ServerboundCookieResponsePacket packet, CallbackInfo ci) {
        if (!HybridAuth.BACKUP_COOKIE_KEY.toString().equals(packet.key().toString())) {
            return;
        }
        // 无论 payload 是否有效都交给本轮验证处理并取消原版断开，避免正常玩家被踢。
        try {
            Connection conn = this.connection;
            BackupLoginState.store(conn, packet.payload());
        } catch (Exception e) {
            com.pimeng.plogin.PLogin.LOGGER.warn("[PLogin] 处理备份重连 Cookie 失败", e);
        }
        ci.cancel();
    }

    @Inject(method = "onDisconnect", at = @At("TAIL"))
    private void plogin$clearCookie(net.minecraft.network.DisconnectionDetails details, CallbackInfo ci) {
        try {
            BackupLoginState.clear(this.connection);
        } catch (Exception ignored) {
            // 清理失败不影响断开流程
        }
    }
}
