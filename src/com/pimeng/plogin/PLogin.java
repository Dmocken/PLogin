package com.pimeng.plogin;

import com.pimeng.plogin.auth.ConfigAuth;
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
    public static final String VERSION = "1.1.0";
    public static final Logger LOGGER = LoggerFactory.getLogger("PLogin");

    private static Config config;
    private static UserStore users;
    private static ConfigAuth configAuth;
    private static ExecutorService executor;
    /** 皮肤查询专用线程池：允许被慢速第三方 API 拖住，但绝不占用认证线程。 */
    private static ExecutorService skinExecutor;
    private static Path dataDir;
    private static volatile MinecraftServer server;

    private PLogin() {
    }

    public static void init() {
        dataDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        config = Config.load(dataDir.resolve("config.json"));
        Messages.setLanguage(config.language);

        users = new UserStore(dataDir);
        if (!users.load()) {
            // 数据损坏时进入“默认拒绝认证”模式（见 UserStore.isCorrupted / ConfigAuth.doBegin）。
            // 不让服务器崩溃，但不接受任何离线注册/登录。
            LOGGER.error("[PLogin] 检测到损坏的账号数据文件，已启用保护模式：离线认证暂停");
        }

        executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "PLogin-Auth");
            thread.setDaemon(true);
            return thread;
        });
        skinExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "PLogin-Skin");
            thread.setDaemon(true);
            return thread;
        });

        configAuth = new ConfigAuth();
        LOGGER.info("PLogin {} 已加载，数据目录: {}", VERSION, dataDir);
    }

    public static void setServer(MinecraftServer minecraftServer) {
        server = minecraftServer;
    }

    public static void onServerStarted(MinecraftServer minecraftServer) {
        server = minecraftServer;
        if (minecraftServer.usesAuthentication()) {
            LOGGER.info("[PLogin] 混合登录已启用（online-mode=true）：正版玩家验证后直接进入，离线玩家在配置阶段通过对话框注册/登录");
        } else {
            LOGGER.info("[PLogin] 服务器 online-mode=false：所有玩家都按离线身份处理，在配置阶段通过对话框注册/登录");
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
        if (config.premiumPasswordBackup && config.transferEnabled && !minecraftServer.acceptsTransfers()) {
            LOGGER.warn("[PLogin] 服务器未开启 accepts-transfers，备份密码登录无法 transfer 到正版 UUID/存档；"
                    + "请在 server.properties 设置 accepts-transfers=true 并重启");
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
        if (skinExecutor != null) {
            skinExecutor.shutdown();
        }
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

    public static ConfigAuth configAuth() {
        return configAuth;
    }

    public static ExecutorService executor() {
        return executor;
    }

    /** 皮肤后台查询线程池。慢第三方 API 允许在这里排队，认证线程不受牵连。 */
    public static ExecutorService skinExecutor() {
        return skinExecutor;
    }

    public static Path dataDir() {
        return dataDir;
    }

    public static MinecraftServer server() {
        return server;
    }
}
