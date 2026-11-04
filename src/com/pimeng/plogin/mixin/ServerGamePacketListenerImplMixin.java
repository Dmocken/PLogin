package com.pimeng.plogin.mixin;

import com.pimeng.plogin.PLogin;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code /debugconfig config} 会把已登录玩家退回配置阶段；用连接标记避免再次弹出登录框。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Inject(method = "switchToConfig", at = @At("HEAD"))
    private void plogin$markReconfiguring(CallbackInfo ci) {
        if (PLogin.configAuth() != null) {
            PLogin.configAuth().markReconfiguring((ServerCommonPacketListenerImpl) (Object) this);
        }
    }
}
