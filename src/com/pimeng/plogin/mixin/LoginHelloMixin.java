package com.pimeng.plogin.mixin;

import com.pimeng.plogin.Messages;
import com.pimeng.plogin.PLogin;
import com.pimeng.plogin.config.Config;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 关键修复：离线客户端在收到 Encryption Request 后，如果 shouldAuthenticate=true，
 * 会拿本地令牌调用 Mojang joinServer；离线令牌无效时客户端直接报
 * “登录失败：无效会话”，在发送 Encryption Response 之前就断开。
 *
 * 对 profileId 等于 OfflinePlayer:<name>（或 version 3）的客户端，把 shouldAuthenticate
 * 设为 false：客户端仍然完成 RSA/AES 加密并发送 key，但跳过 joinServer。
 * 服务端照常执行 hasJoined，失败后由 HybridAuth 回退到离线 UUID。
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class LoginHelloMixin {
    @Unique
    private boolean plogin$skipClientAuth;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void plogin$inspectLoginStart(ServerboundHelloPacket packet, CallbackInfo ci) {
        this.plogin$skipClientAuth = false;

        Config config = PLogin.config();
        if (config == null || !config.enabled || !config.skipClientAuthForOfflineClients) {
            return;
        }

        String name = packet.name();
        UUID profileId = packet.profileId();
        UUID offlineId = UUIDUtil.createOfflinePlayerUUID(name);
        boolean offlineProfileId = profileId != null
                && (profileId.equals(offlineId) || profileId.version() == 3);

        // 已经记录过的正版名字：如果 premiumNameProtection=true，即使客户端谎报离线 UUID，
        // 也强制走正版验证。设为 false 时允许离线玩家用正版名（需注册密码），
        // 并且 SkinService 可以给该名字取 Mojang 正版皮肤。
        UUID knownPremium = PLogin.users() == null ? null : PLogin.users().premiumUuid(name);
        boolean hasBackup = PLogin.users() != null && PLogin.users().hasPremiumBackup(name);
        // 有离线备份密码的正版名：即使 protection=true，也允许离线客户端跳过 joinServer，
        // 进来后在登录对话框输入备份密码；没有备份才强制正版验证。
        boolean protectPremiumName = config.premiumNameProtection && knownPremium != null && !hasBackup;
        boolean skip = offlineProfileId && !protectPremiumName;

        // 离线客户端 + 已记录的正版名 + 没有离线备份密码：原版会要求客户端 joinServer，
        // 离线令牌无效时客户端只显示“无效会话”，完全看不到服务端本来要拒绝的原因。
        // 这里直接在登录阶段断开并发送明确原因，避免把“正版名保护”伪装成客户端故障。
        if (offlineProfileId && protectPremiumName) {
            PLogin.LOGGER.warn("[PLogin] 拒绝离线客户端 {} ({}): 该名字属于正版账号且没有离线备份密码，"
                    + "已直接断开（避免客户端显示“无效会话”）", name, profileId);
            ((ServerLoginPacketListenerImpl) (Object) this).disconnect(Messages.get("premium.name"));
            ci.cancel();
            return;
        }

        this.plogin$skipClientAuth = skip;
        PLogin.LOGGER.info("[PLogin] Login Start: name={}, profileId={}, offlineProfileId={}, knownPremium={}, hasBackup={}, protectPremiumName={}, skipClientAuth={}",
                name, profileId, offlineProfileId, knownPremium != null, hasBackup, protectPremiumName, skip);
    }

    @ModifyArg(
            method = "handleHello",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/login/ClientboundHelloPacket;<init>(Ljava/lang/String;[B[BZ)V"
            ),
            index = 3
    )
    private boolean plogin$modifyShouldAuthenticate(boolean original) {
        return this.plogin$skipClientAuth ? false : original;
    }
}
