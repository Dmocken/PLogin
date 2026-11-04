package com.pimeng.plogin.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读取 {@code ServerCommonPacketListenerImpl.connection}，用于按连接隔离认证状态。 */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonPacketListenerAccessor {
    @Accessor("connection")
    Connection plogin$connection();
}
