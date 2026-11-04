package com.pimeng.plogin.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;

public final class Ui {
    private Ui() {
    }

    public static void title(ServerPlayer player, String title, String subtitle) {
        title(player, Component.literal(title), Component.literal(subtitle));
    }

    public static void title(ServerPlayer player, Component title, Component subtitle) {
        if (player.connection == null) {
            return;
        }
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        player.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    public static void actionBar(ServerPlayer player, String text) {
        if (player.connection != null) {
            player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(text)));
        }
    }
}
