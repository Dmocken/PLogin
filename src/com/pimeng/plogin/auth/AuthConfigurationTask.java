package com.pimeng.plogin.auth;

import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ConfigurationTask;

/**
 * 插在 {@code SynchronizeRegistriesTask} 之后、{@code PrepareSpawnTask} 之前的配置任务。
 *
 * <p>{@code start()} 发送登录/注册对话框；{@code tick()} 返回 true 时原版
 * {@code ServerConfigurationPacketListenerImpl#finishCurrentTask} 会继续执行
 * PrepareSpawn / JoinWorld，玩家随后以正常方式进入世界。
 */
public final class AuthConfigurationTask implements ConfigurationTask {
    public static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type("plogin:auth");

    private final ConfigAuth.Session session;

    public AuthConfigurationTask(ConfigAuth.Session session) {
        this.session = session;
    }

    @Override
    public void start(Consumer<Packet<?>> sender) {
        session.showDialog();
    }

    @Override
    public boolean tick() {
        return session.tick();
    }

    @Override
    public ConfigurationTask.Type type() {
        return TYPE;
    }
}
