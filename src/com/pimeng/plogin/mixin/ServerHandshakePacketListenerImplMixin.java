package com.pimeng.plogin.mixin;

import com.pimeng.plogin.auth.TransferState;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 记录客户端连接时使用的 host，transfer 回自己时优先用它（兼容域名/NAT/反代）。 */
@Mixin(ServerHandshakePacketListenerImpl.class)
public abstract class ServerHandshakePacketListenerImplMixin {
    @Shadow
    @Final
    private Connection connection;

    @Inject(method = "handleIntention", at = @At("HEAD"))
    private void plogin$captureHost(ClientIntentionPacket packet, CallbackInfo ci) {
        TransferState.setTarget(this.connection, packet.hostName(), packet.port());
    }
}
