package com.pimeng.plogin.auth;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookSeenRecipePacket;
import net.minecraft.network.protocol.game.ServerboundSeenAdvancementsPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;

/**
 * 未登录玩家只允许通过"保活 + 聊天/命令 + 必要的协议包"，其余全部丢弃。
 * 客户端移动包被丢弃，所以服务端视角位置不变；交互/容器/破坏方块包也被丢弃。
 */
public final class PacketGate {
    private PacketGate() {
    }

    public static boolean allowed(Packet<?> packet) {
        return packet instanceof ServerboundKeepAlivePacket
                || packet instanceof ServerboundPongPacket
                || packet instanceof ServerboundPingRequestPacket
                || packet instanceof ServerboundClientInformationPacket
                || packet instanceof ServerboundCustomPayloadPacket
                || packet instanceof ServerboundResourcePackPacket
                || packet instanceof ServerboundCookieResponsePacket
                || packet instanceof ServerboundChatPacket
                || packet instanceof ServerboundChatCommandPacket
                || packet instanceof ServerboundChatCommandSignedPacket
                || packet instanceof ServerboundChatAckPacket
                || packet instanceof ServerboundChunkBatchReceivedPacket
                || packet instanceof ServerboundClientTickEndPacket
                || packet instanceof ServerboundAcceptTeleportationPacket
                || packet instanceof ServerboundPlayerLoadedPacket
                || packet instanceof ServerboundConfigurationAcknowledgedPacket
                || packet instanceof ServerboundContainerClosePacket
                || packet instanceof ServerboundRecipeBookChangeSettingsPacket
                || packet instanceof ServerboundRecipeBookSeenRecipePacket
                || packet instanceof ServerboundSeenAdvancementsPacket
                || packet instanceof ServerboundSetCarriedItemPacket
                || packet instanceof ServerboundCommandSuggestionPacket
                || packet instanceof ServerboundClientCommandPacket
                || packet instanceof ServerboundSwingPacket;
    }

    public static boolean movement(Packet<?> packet) {
        return packet instanceof ServerboundMovePlayerPacket
                || packet instanceof ServerboundMoveVehiclePacket
                || packet instanceof ServerboundPaddleBoatPacket
                || packet instanceof ServerboundPlayerInputPacket;
    }
}
