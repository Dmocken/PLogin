package com.pimeng.plogin.mixin;

import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.pimeng.plogin.PLogin;
import com.pimeng.plogin.auth.HybridAuth;
import com.pimeng.plogin.auth.TransferState;
import com.pimeng.plogin.auth.UserStore;
import com.pimeng.plogin.config.Config;
import java.lang.reflect.Field;
import java.net.InetAddress;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 目标：原版登录线程里的 hasJoinedServer 调用。
 *
 * <p>正版验证成功 -> 原样返回（UUID/皮肤属性都来自 Mojang）。
 * 无会话 / 会话服务器不可用 -> 返回 OfflinePlayer:&lt;name&gt; 的离线档案。
 *
 * <p>如果是我们发起的 transfer 重连（handshake intention=TRANSFER + 有离线备份密码），
 * 在 hasJoined 之前向客户端索要一次性 transfer Cookie；凭据有效时 HybridAuth 直接返回
 * 记录里的正版 UUID，不再走 hasJoined，也不要求客户端有正版会话。
 */
@Mixin(targets = "net.minecraft.server.network.ServerLoginPacketListenerImpl$1")
public abstract class LoginThreadMixin {
    @Redirect(
            method = "run",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/authlib/minecraft/MinecraftSessionService;hasJoinedServer(Ljava/lang/String;Ljava/lang/String;Ljava/net/InetAddress;)Lcom/mojang/authlib/yggdrasil/ProfileResult;"
            )
    )
    private ProfileResult plogin$hybridVerify(MinecraftSessionService service, String name, String digest, InetAddress address)
            throws AuthenticationUnavailableException {
        ServerLoginPacketListenerImpl listener = plogin$outer(this);
        boolean transferRequested = false;
        byte[] transferSecret = null;
        String transferIp = "";
        if (listener != null) {
            boolean transferred = ((ServerLoginPacketListenerAccessor) listener).plogin$transferred();
            UserStore users = PLogin.users();
            Config config = PLogin.config();
            if (transferred && users != null && config != null && config.enabled
                    && users.hasPremiumBackup(name)) {
                Connection connection = TransferState.connectionOf(listener);
                transferIp = TransferState.remoteIp(connection);
                transferSecret = TransferState.requestAndWait(connection, config.transferTokenWaitMs);
                transferRequested = true;
            }
        }
        return HybridAuth.verify(service, name, digest, address, transferSecret, transferRequested, transferIp);
    }

    @Unique
    private static ServerLoginPacketListenerImpl plogin$outer(Object thread) {
        try {
            Field outer = thread.getClass().getDeclaredField("this$0");
            outer.setAccessible(true);
            return (ServerLoginPacketListenerImpl) outer.get(thread);
        } catch (ReflectiveOperationException | RuntimeException e) {
            PLogin.LOGGER.warn("[PLogin] 无法获取登录监听器，transfer 凭据不可用", e);
            return null;
        }
    }
}
