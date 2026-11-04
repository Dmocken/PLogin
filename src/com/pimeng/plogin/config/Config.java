package com.pimeng.plogin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pimeng.plogin.PLogin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * PLogin 配置。字段缺省时会自动补全并写回文件。
 */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 总开关。关闭后 PLogin 完全不介入登录（online-mode=true 时恢复原版行为）。 */
    public boolean enabled = true;

    /** 正版验证通过的玩家直接进入，不显示注册/登录对话框。 */
    public boolean premiumBypass = true;

    /** 正版验证成功时允许 /register 设置“离线备份密码”；正版不可用时可用它离线登录。 */
    public boolean premiumPasswordBackup = true;

    /** 备份密码登录后，是否用原版 transfer 把离线连接转到正版 UUID/存档。 */
    public boolean transferEnabled = true;
    /** transfer 目标主机；空 = 使用客户端连接时使用的 host（推荐留空）。 */
    public String transferHost = "";
    /** transfer 目标端口；0 = 服务器端口。 */
    public int transferPort = 0;
    /** 一次性 transfer 凭据有效期（秒）。 */
    public int transferTokenSeconds = 120;
    /** transfer 凭据绑定发放时的 IP。 */
    public boolean transferBindIp = true;
    /** 登录阶段等待客户端返回 transfer Cookie 的最长时间（毫秒）。 */
    public int transferTokenWaitMs = 1000;

    /** Mojang 会话服务器不可达/报错时，把玩家按离线身份放行（否则会拒绝登录）。 */
    public boolean fallbackToOfflineWhenAuthServerDown = true;

    /**
     * 对 profileId 为离线 UUID 的客户端，在 Encryption Request 中把 shouldAuthenticate 设为 false。
     * 否则离线启动器会在客户端侧调用 Mojang joinServer，令牌无效时直接报“无效会话”并断开，
     * 根本不会把 Encryption Response 发给服务端。设为 false 后客户端只加密、不 joinServer，
     * 服务端照常 hasJoined，失败后回退离线 UUID。
     */
    public boolean skipClientAuthForOfflineClients = true;

    /** 强制关闭 enforce-secure-profile，避免离线玩家聊天被踢。 */
    public boolean forceDisableSecureProfile = true;

    /** 离线玩家必须注册。 */
    public boolean requireRegistration = true;

    public int passwordMinLength = 4;
    public int passwordMaxLength = 64;
    public int passwordIterations = 120000;

    /** 登录对话框超时（秒），<=0 表示不超时。 */
    public int loginTimeoutSeconds = 120;
    /** 超时后踢出；false = 不超时，保留对话框继续等待（会一直占用这条连接）。 */
    public boolean kickOnTimeout = true;

    /** 连续密码错误达到该次数后临时封禁 IP。 */
    public int maxLoginAttempts = 5;
    /** IP 临时封禁秒数。 */
    public int ipBanSeconds = 300;

    /** 失败计数窗口（秒）：窗口内跨连接累计的表现才参与账号锁定判断，窗口过期清零。 */
    public int failureWindowSeconds = 1800;
    /** 窗口内该账号累计密码失败达到该次数后临时锁定账号（重连/换连接不会重置）。 */
    public int maxAccountFailures = 15;
    /** 账号临时锁定秒数（0 = 关闭账号锁定）。 */
    public int accountLockSeconds = 300;

    /** 登录线程最多等待离线皮肤查询的毫秒数；超时本次登录先不带皮肤（后台继续补缓存）。 */
    public int offlineSkinMaxWaitMs = 3000;

    /** zh_cn / en_us */
    public String language = "zh_cn";

    /** 已知正版用户名保护：正版玩家登录过一次后，离线玩家不能再冒用该名字。 */
    public boolean premiumNameProtection = true;

    /** 离线玩家皮肤总开关。 */
    public boolean offlineSkinsEnabled = true;
    /** auto = 先 Mojang 再皮肤站；mojang = 只用正版名；littleskin = 只用皮肤站；none = 关闭。 */
    public String offlineSkinProvider = "auto";
    /** CustomSkinAPI 根地址（LittleSkin: https://littleskin.cn/csl/）。 */
    public String offlineSkinSiteRoot = "https://littleskin.cn/csl/";
    /** MineSkin API Key（可留空，游客模式有频率限制）。 */
    public String offlineSkinMineSkinApiKey = "";
    /** 用 MineSkin 把皮肤站图片签成 Mojang 可验证的 textures 属性（否则其他玩家看不到）。 */
    public boolean offlineSkinMineSkinSigning = true;
    /** MineSkin 失败时是否仍下发未签名属性（通常只有本机玩家自己能看见）。 */
    public boolean offlineSkinAllowUnsignedFallback = false;
    /** 皮肤查询超时（秒）。 */
    public int offlineSkinTimeoutSeconds = 15;
    /** 皮肤缓存分钟数。 */
    public int offlineSkinCacheMinutes = 360;

    public static Config load(Path file) {
        Config defaults = new Config();
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(defaults), StandardCharsets.UTF_8);
                return defaults;
            }
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject defaultJson = GSON.toJsonTree(defaults).getAsJsonObject();
            for (String key : defaultJson.keySet()) {
                if (!root.has(key)) {
                    root.add(key, defaultJson.get(key));
                }
            }
            Config config = GSON.fromJson(root, Config.class);
            if (config == null) {
                config = defaults;
            }
            config.normalize();
            Files.writeString(file, GSON.toJson(config), StandardCharsets.UTF_8);
            return config;
        } catch (Exception e) {
            PLogin.LOGGER.error("[PLogin] 读取配置失败，使用默认配置: {}", file, e);
            return defaults;
        }
    }

    public void normalize() {
        passwordMinLength = Math.max(1, Math.min(passwordMinLength, 128));
        passwordMaxLength = Math.max(passwordMinLength, Math.min(passwordMaxLength, 256));
        passwordIterations = Math.max(10000, Math.min(passwordIterations, 2000000));
        if (transferHost == null) {
            transferHost = "";
        }
        transferHost = transferHost.trim();
        transferPort = Math.max(0, Math.min(transferPort, 65535));
        transferTokenSeconds = Math.max(10, Math.min(transferTokenSeconds, 600));
        transferTokenWaitMs = Math.max(200, Math.min(transferTokenWaitMs, 5000));
        loginTimeoutSeconds = Math.max(0, Math.min(loginTimeoutSeconds, 86400));
        maxLoginAttempts = Math.max(1, Math.min(maxLoginAttempts, 100));
        ipBanSeconds = Math.max(0, Math.min(ipBanSeconds, 30 * 86400));
        failureWindowSeconds = Math.max(60, Math.min(failureWindowSeconds, 7 * 86400));
        maxAccountFailures = Math.max(1, Math.min(maxAccountFailures, 1000));
        accountLockSeconds = Math.max(0, Math.min(accountLockSeconds, 86400));
        offlineSkinMaxWaitMs = Math.max(0, Math.min(offlineSkinMaxWaitMs, 10000));
        if (language == null || language.isBlank()) {
            language = "zh_cn";
        }
        if (offlineSkinProvider == null || offlineSkinProvider.isBlank()) {
            offlineSkinProvider = "auto";
        }
        offlineSkinProvider = offlineSkinProvider.toLowerCase(Locale.ROOT);
        if (offlineSkinSiteRoot == null || offlineSkinSiteRoot.isBlank()) {
            offlineSkinSiteRoot = "https://littleskin.cn/csl/";
        }
        if (!offlineSkinSiteRoot.endsWith("/")) {
            offlineSkinSiteRoot = offlineSkinSiteRoot + "/";
        }
        offlineSkinTimeoutSeconds = Math.max(3, Math.min(offlineSkinTimeoutSeconds, 60));
        offlineSkinCacheMinutes = Math.max(5, Math.min(offlineSkinCacheMinutes, 1440));
    }
}
