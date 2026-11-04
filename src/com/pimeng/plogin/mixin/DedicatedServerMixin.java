package com.pimeng.plogin.mixin;

import com.pimeng.plogin.PLogin;
import com.pimeng.plogin.config.Config;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 离线玩家没有 Mojang 签名的聊天公钥；enforce-secure-profile=true 会让他们无法聊天甚至被踢。
 * 配置 forceDisableSecureProfile=true（默认）时，强制返回 false。
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin {
    @Inject(method = "enforceSecureProfile", at = @At("RETURN"), cancellable = true)
    private void plogin$disableSecureProfile(CallbackInfoReturnable<Boolean> cir) {
        Config config = PLogin.config();
        if (config != null && config.enabled && config.forceDisableSecureProfile) {
            cir.setReturnValue(false);
        }
    }
}
