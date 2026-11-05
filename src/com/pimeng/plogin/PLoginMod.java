package com.pimeng.plogin;

import com.pimeng.plogin.command.AuthCommands;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public final class PLoginMod implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        PLogin.init();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                AuthCommands.register(dispatcher));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                PLogin.configAuth().onJoin(handler.player));

        ServerTickEvents.END_SERVER_TICK.register(server -> PLogin.configAuth().tick(server));

        ServerLifecycleEvents.SERVER_STARTING.register(PLogin::setServer);
        ServerLifecycleEvents.SERVER_STARTED.register(PLogin::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> PLogin.shutdown());

        PLogin.LOGGER.info("[PLogin] 初始化完成，版本 {}", PLogin.VERSION);
    }
}
