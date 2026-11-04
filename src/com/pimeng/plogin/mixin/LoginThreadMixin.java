package com.pimeng.plogin.mixin;

import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.pimeng.plogin.auth.HybridAuth;
import java.lang.reflect.Field;
import java.net.InetAddress;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 目标：原版登录线程里的 hasJoinedServer 调用。
 * 正版验证成功 -> 原样返回（UUID/皮肤属性都来自 Mojang）。
 * 无会话 / 会话服务器不可用 -> 返回 OfflinePlayer:<name> 的离线档案。
 *
 * 备份重连流程额外多一步：在调用 hasJoined 前向这条连接索取一次性 Cookie 凭据，
 * 让“密码验证成功”这个结果可靠地绑定到刚才输对密码的客户端，而不是只按用户名标记。
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
        // this 即匿名验证线程实例；通过合成字段 this$0 拿回外层登录监听器。
        ServerLoginPacketListenerImpl listener = plogin$outer(this);
        byte[] cookie = listener == null
                ? null
                : HybridAuth.requestBackupCookie(listener, name, com.pimeng.plogin.PLogin.config());
        return HybridAuth.verify(service, name, digest, address, cookie);
    }

    @Unique
    private static ServerLoginPacketListenerImpl plogin$outer(Object thread) {
        try {
            // 服务器 jar 为 Mojang official mapped，运行时无重映射，合成字段名固定为 this$0。
            Field outer = thread.getClass().getDeclaredField("this$0");
            outer.setAccessible(true);
            return (ServerLoginPacketListenerImpl) outer.get(thread);
        } catch (ReflectiveOperationException | RuntimeException e) {
            com.pimeng.plogin.PLogin.LOGGER.warn("[PLogin] 无法获取登录监听器，备份重连凭据不可用（将要求重新 /login）", e);
            return null;
        }
    }
}
