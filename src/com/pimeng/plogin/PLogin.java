package com.pimeng.plogin;

import com.pimeng.plogin.auth.AuthManager;
import com.pimeng.plogin.auth.UserStore;
import com.pimeng.plogin.config.Config;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PLogin 全局状态。服务端专用，客户端不需要安装本模组。
 */
public final class PLogin {
    public static final String MOD_ID = "plogin";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LoggerFactory.getLogger("PLogin");

    private static Config config;
    private static UserStore users;
    private static AuthManager auth;
    private static ExecutorService executor;
    private static Path dataDir;
    private static volatile MinecraftServer server;

    private PLogin() {
    }

    public static void init() {
        dataDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        config = Config.load(dataDir.resolve("config.json"));
        Messages.setLanguage(config.language);

        users = new UserStore(dataDir);
        users.load();

        executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "PLogin-Auth");
            thread.setDaemon(true);
            return thread;
        });

        auth = new AuthManager();
        LOGGER.info("PLogin {} 已加载，数据目录: {}", VERSION, dataDir);
    }

    public static void setServer(MinecraftServer minecraftServer) {
        server = minecraftServer;
    }

    public static void onServerStarted(MinecraftServer minecraftServer) {
        server = minecraftServer;
        if (minecraftServer.usesAuthentication()) {
            LOGGER.info("[PLogin] 混合登录已启用（online-mode=true）：正版玩家验证后直接进入，离线玩家使用离线 UUID 并需要注册/登录");
        } else {
            LOGGER.info("[PLogin] 服务器 online-mode=false：所有玩家都按离线身份处理，必须注册/登录");
        }
        boolean effectiveSecureProfile = minecraftServer.enforceSecureProfile();
        LOGGER.info("[PLogin] enforce-secure-profile 有效值: {}（配置 forceDisableSecureProfile={}）",
                effectiveSecureProfile, config.forceDisableSecureProfile);
        if (effectiveSecureProfile) {
            LOGGER.warn("[PLogin] enforce-secure-profile 仍为 true，离线玩家可能无法发送聊天；"
                    + "请检查 forceDisableSecureProfile 或 server.properties");
        }
        if (!minecraftServer.usesAuthentication() && config.premiumBypass) {
            LOGGER.info("[PLogin] 提示：online-mode=false 时无法认证正版身份，所有玩家都需要注册");
        }
    }

    public static void reloadConfig() {
        if (dataDir == null) {
            return;
        }
        config = Config.load(dataDir.resolve("config.json"));
        Messages.setLanguage(config.language);
        LOGGER.info("[PLogin] 配置已重新加载");
    }

    public static void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(2L, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (users != null) {
            users.saveAll();
        }
    }

    public static Config config() {
        return config;
    }

    public static UserStore users() {
        return users;
    }

    public static AuthManager auth() {
        return auth;
    }

    public static ExecutorService executor() {
        return executor;
    }

    public static Path dataDir() {
        return dataDir;
    }

    public static MinecraftServer server() {
        return server;
    }
}
