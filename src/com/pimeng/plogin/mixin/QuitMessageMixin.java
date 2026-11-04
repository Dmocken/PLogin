package com.pimeng.plogin.mixin;

import com.pimeng.plogin.auth.HiddenPlayers;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 未登录玩家从未在正常玩家视角里出现过，退出时也不应该只发一条
 * “xxx 退出了游戏”。已经在 Tab 隐藏的玩家，退出消息直接吞掉。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class QuitMessageMixin {
    @Shadow
    public ServerPlayer player;

    @Redirect(
            method = "removePlayerFromWorld",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"
            )
    )
    private void plogin$hideQuitMessage(PlayerList list, Component message, boolean overlay) {
        if (HiddenPlayers.consume(player.getUUID())) {
            return;
        }
        list.broadcastSystemMessage(message, overlay);
    }
}
