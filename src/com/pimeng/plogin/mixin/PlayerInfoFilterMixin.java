package com.pimeng.plogin.mixin;

import com.pimeng.plogin.PLogin;
import com.pimeng.plogin.auth.LimboManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 未登录玩家只在 limbo 中，不应出现在正常玩家的 Tab 列表。
 * 这里拦截发往每个客户端的 PlayerInfoUpdate，把未登录玩家的 Entry 过滤掉；
 * 玩家自己那一项保留（否则客户端连自己的皮肤/信息都不完整）。
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class PlayerInfoFilterMixin {
    @Shadow
    @Final
    protected Connection connection;

    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void plogin$filterPendingPlayerInfo(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundPlayerInfoUpdatePacket info)) {
            return;
        }
        if (!((Object) this instanceof ServerGamePacketListenerImpl game)) {
            return;
        }
        ServerPlayer recipient = game.player;
        if (recipient == null || PLogin.auth() == null) {
            return;
        }
        MinecraftServer server = recipient.level().getServer();
        List<ServerPlayer> visible = new ArrayList<>(info.entries().size());
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : info.entries()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.profileId());
            if (player == null) {
                continue;
            }
            boolean pending = PLogin.auth().isPending(player) || LimboManager.isInLimbo(player.getUUID());
            if (pending && player != recipient) {
                continue;
            }
            visible.add(player);
        }
        if (visible.size() == info.entries().size()) {
            return;
        }
        ci.cancel();
        if (!visible.isEmpty()) {
            connection.send(new ClientboundPlayerInfoUpdatePacket(info.actions(), visible));
        }
    }
}
