package com.pimeng.plogin.mixin;

import com.pimeng.plogin.PLogin;
import com.pimeng.plogin.auth.AuthManager;
import com.pimeng.plogin.auth.PacketGate;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在数据包分发入口处丢弃未登录玩家的危险数据包。
 * 放在 Connection 层可以覆盖所有交互/容器/攻击/放置/丢弃等动作，
 * 比逐个 hook ServerGamePacketListenerImpl 方法更不容易漏。
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Shadow
    private PacketListener packetListener;

    @Inject(method = "channelRead0", at = @At("HEAD"), cancellable = true)
    private void plogin$filterPendingPlayer(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (!(this.packetListener instanceof ServerGamePacketListenerImpl handler)) {
            return;
        }
        ServerPlayer player = handler.player;
        if (player == null) {
            return;
        }
        AuthManager auth = PLogin.auth();
        if (auth == null) {
            return;
        }
        AuthManager.Session session = auth.session(player);
        if (session == null || session.state == AuthManager.State.AUTHENTICATED) {
            return;
        }
        if (session.spectator) {
            return;
        }
        if (PacketGate.allowed(packet)) {
            return;
        }
        if (!session.frozen && PacketGate.movement(packet)) {
            return;
        }
        ci.cancel();
    }
}
