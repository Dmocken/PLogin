package com.pimeng.plogin.mixin;

import com.pimeng.plogin.PLogin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 未登录玩家不能被伤害/杀死，避免死亡后重生回主世界。 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerGuardMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void plogin$blockHurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (PLogin.auth() != null && PLogin.auth().isPending((ServerPlayer) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void plogin$blockDeath(DamageSource source, CallbackInfo ci) {
        if (PLogin.auth() != null && PLogin.auth().isPending((ServerPlayer) (Object) this)) {
            ci.cancel();
        }
    }
}
