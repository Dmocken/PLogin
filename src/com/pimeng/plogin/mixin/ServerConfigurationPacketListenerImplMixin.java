package com.pimeng.plogin.mixin;

import com.pimeng.plogin.PLogin;
import com.pimeng.plogin.auth.ConfigAuth;
import java.util.Queue;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把登录对话框任务插到配置任务队列里：排在同步注册表/资源包之后、
 * PrepareSpawn / JoinWorld 之前，因此认证完成前玩家不会进入世界。
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ServerConfigurationPacketListenerImplMixin {
    @Shadow
    @Final
    private Queue<ConfigurationTask> configurationTasks;

    @Inject(method = "returnToWorld", at = @At("HEAD"), cancellable = true)
    private void plogin$enqueueAuthTask(CallbackInfo ci) {
        if (PLogin.configAuth() == null) {
            return;
        }
        ConfigAuth.StartResult result =
                PLogin.configAuth().begin((ServerConfigurationPacketListenerImpl) (Object) this);
        if (result.task() != null) {
            this.configurationTasks.add(result.task());
        } else if (result.abort()) {
            // 已在 begin() 里断开连接，不再继续 PrepareSpawn/JoinWorld
            ci.cancel();
        }
    }
}
