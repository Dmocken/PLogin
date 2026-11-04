package com.pimeng.plogin.mixin;

import com.pimeng.plogin.PLogin;
import com.pimeng.plogin.auth.HiddenPlayers;
import com.pimeng.plogin.auth.HybridAuth;
import com.pimeng.plogin.auth.LimboManager;
import com.pimeng.plogin.config.Config;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 离线待验证玩家在 Login 包发出前切到 limbo 虚空维度。
 * 这样客户端从一开始就只看到 limbo 的坐标，不会暴露主世界真实位置。
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void plogin$limbo(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        Config config = PLogin.config();
        if (config == null || !config.enabled) {
            return;
        }
        if (!HybridAuth.isOfflineProfile(player.getGameProfile())) {
            return;
        }
        if (HybridAuth.isVerifiedBackup(player.getGameProfile())) {
            return;
        }
        LimboManager.prepare(player);
    }

    @Redirect(
            method = "placeNewPlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"
            )
    )
    private void plogin$hideJoinMessage(PlayerList list, Component message, boolean overlay,
                                        Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        if (HiddenPlayers.isHidden(player.getUUID())) {
            return;
        }
        list.broadcastSystemMessage(message, overlay);
    }
}
