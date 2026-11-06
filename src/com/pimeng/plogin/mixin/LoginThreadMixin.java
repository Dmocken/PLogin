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
import java.lang.reflect.Modifier;
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
        boolean transferRequested = false;
        byte[] transferSecret = null;
        String transferIp = "";
        // 只有“登记过离线备份密码”的名字才可能走 transfer 重连；
        // 普通登录（含所有正版玩家）完全不碰反射，也不向客户端索要 Cookie。
        UserStore users = PLogin.users();
        Config config = PLogin.config();
        if (users != null && config != null && config.enabled && users.hasPremiumBackup(name)) {
            ServerLoginPacketListenerImpl listener = plogin$listener(this);
            if (listener != null
                    && ((ServerLoginPacketListenerAccessor) listener).plogin$transferred()) {
                Connection connection = TransferState.connectionOf(listener);
                transferIp = TransferState.remoteIp(connection);
                transferSecret = TransferState.requestAndWait(connection, config.transferTokenWaitMs);
                transferRequested = true;
            }
        }
        return HybridAuth.verify(service, name, digest, address, transferSecret, transferRequested, transferIp);
    }

    /**
     * 取出登录线程持有的 {@code ServerLoginPacketListenerImpl}。
     *
     * <p>字段名随版本变化，不能按名字找：26.x 是 javac 合成的 {@code this$0}，
     * 1.21.10 的服务端 jar 里这个引用是混淆后带映射的 {@code field_14176}（Mojmap 名同样不是
     * {@code this$0}）。所以按“非静态且类型是 ServerLoginPacketListenerImpl”来找——
     * 两个命名空间下字段类型都会被 remap 成同一个类，判断成立。
     */
    @Unique
    private static ServerLoginPacketListenerImpl plogin$listener(Object thread) {
        Class<?> type = thread.getClass();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (!ServerLoginPacketListenerImpl.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                if (field.get(thread) instanceof ServerLoginPacketListenerImpl listener) {
                    return listener;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                PLogin.LOGGER.debug("[PLogin] 读取登录监听器字段 {} 失败", field.getName(), e);
            }
        }
        PLogin.LOGGER.debug("[PLogin] 登录线程 {} 上没有找到登录监听器字段，transfer 凭据不可用",
                type.getName());
        return null;
    }
}
