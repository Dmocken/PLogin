package com.pimeng.plogin.mixin;

import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.pimeng.plogin.auth.HybridAuth;
import java.net.InetAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 目标：原版登录线程里的 hasJoinedServer 调用。
 * 正版验证成功 -> 原样返回（UUID/皮肤属性都来自 Mojang）。
 * 无会话 / 会话服务器不可用 -> 返回 OfflinePlayer:<name> 的离线档案。
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
        return HybridAuth.verify(service, name, digest, address);
    }
}
