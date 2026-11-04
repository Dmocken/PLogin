package com.pimeng.plogin.mixin;

import com.pimeng.plogin.PLogin;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 Configuration 阶段拦截登录对话框的按钮回调。
 *
 * <p>原版 {@code handleCustomClickAction} 会先调用
 * {@code PacketUtils.ensureRunningOnSameThread}，因此网络线程上的包会先被原版重新调度；
 * 这里只在服务端主线程消费 plogin 的动作，并取消原版（原版只会打一条 debug 日志）。
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerMixin {
    @Inject(method = "handleCustomClickAction", at = @At("HEAD"), cancellable = true)
    private void plogin$handleCustomClickAction(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
        if (!((Object) this instanceof ServerConfigurationPacketListenerImpl listener)) {
            return;
        }
        MinecraftServer server = PLogin.server();
        if (server == null || !server.isSameThread()) {
            // 交给原版 ensureRunningOnSameThread 调度，主线程会再次进入本方法
            return;
        }
        if (PLogin.configAuth() != null && PLogin.configAuth().handleClick(listener, packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "onDisconnect", at = @At("TAIL"))
    private void plogin$cleanupConfigSession(DisconnectionDetails details, CallbackInfo ci) {
        if (PLogin.configAuth() != null) {
            PLogin.configAuth().onListenerDisconnect((ServerCommonPacketListenerImpl) (Object) this);
        }
    }
}
