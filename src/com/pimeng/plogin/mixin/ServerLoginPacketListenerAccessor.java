package com.pimeng.plogin.mixin;

import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读取登录监听器的 transferred 标记：true 表示本次是原版 transfer 重连。 */
@Mixin(ServerLoginPacketListenerImpl.class)
public interface ServerLoginPacketListenerAccessor {
    @Accessor("transferred")
    boolean plogin$transferred();
}
