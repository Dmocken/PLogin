package com.pimeng.plogin.mixin;

import com.pimeng.plogin.PLogin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** /kill 走 Entity.kill，拦截未登录玩家。 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityGuardMixin {
    @Inject(method = "kill", at = @At("HEAD"), cancellable = true)
    private void plogin$blockKill(ServerLevel level, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player
                && PLogin.auth() != null
                && PLogin.auth().isPending(player)) {
            ci.cancel();
        }
    }
}
