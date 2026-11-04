package com.pimeng.plogin.auth;

import com.mojang.authlib.GameProfile;
import com.pimeng.plogin.Messages;
import com.pimeng.plogin.PLogin;
import com.pimeng.plogin.config.Config;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 玩家已经进入世界后的账号操作。
 *
 * <p>注册/登录已经移到配置阶段的对话框里，这里只保留：
 * 正版玩家设置离线备份密码、修改密码、登出（断开后重连重新认证）。
 */
public final class AccountService {
    private AccountService() {
    }

    /** 正版玩家设置/更新离线备份密码。离线玩家的注册在连接阶段已经完成。 */
    public static void setPremiumBackup(ServerPlayer player, String password, String confirm) {
        Config config = PLogin.config();
        UserStore users = PLogin.users();
        MinecraftServer server = PLogin.server();
        if (config == null || users == null || server == null) {
            return;
        }
        GameProfile profile = player.getGameProfile();
        if (HybridAuth.isOfflineProfile(profile) || HybridAuth.isBackupLogin(profile)) {
            player.sendSystemMessage(Messages.get("register.already"));
            return;
        }
        if (!config.premiumPasswordBackup) {
            player.sendSystemMessage(Messages.get("premium.password_disabled"));
            return;
        }
        if (users.isCorrupted()) {
            player.sendSystemMessage(Messages.get("auth.corrupt"));
            return;
        }
        String name = profile.name();
        if (password.length() < config.passwordMinLength || password.length() > config.passwordMaxLength) {
            player.sendSystemMessage(Messages.get("register.length",
                    config.passwordMinLength, config.passwordMaxLength));
            return;
        }
        if (!password.equals(confirm)) {
            player.sendSystemMessage(Messages.get("register.mismatch"));
            return;
        }
        if (password.equalsIgnoreCase(name)) {
            player.sendSystemMessage(Messages.get("register.same_name"));
            return;
        }

        boolean hadBackup = users.hasPremiumBackup(name);
        player.sendSystemMessage(Messages.get("register.busy"));
        UUID premiumUuid = player.getUUID();
        UUID offlineUuid = UUIDUtil.createOfflinePlayerUUID(name);
        String ip = safeIp(player);
        int iterations = config.passwordIterations;
        try {
            PLogin.executor().submit(() -> {
                String salt = PasswordHasher.newSalt();
                String hash = PasswordHasher.hash(password, salt, iterations);
                server.execute(() -> {
                    ServerPlayer online = server.getPlayerList().getPlayer(premiumUuid);
                    if (online != player) {
                        return;
                    }
                    // 期间如果掉线/换成离线身份，不再写备份
                    if (HybridAuth.isOfflineProfile(player.getGameProfile())) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    boolean saved = users.setPremiumBackup(name, premiumUuid.toString(),
                            offlineUuid.toString(), salt, hash, iterations, now, ip);
                    if (!saved) {
                        player.sendSystemMessage(Messages.get("register.save_failed"));
                        return;
                    }
                    player.sendSystemMessage(Messages.get(hadBackup
                            ? "premium.password_updated" : "premium.password_set"));
                    PLogin.LOGGER.info("[PLogin] 正版玩家 {} 设置了离线备份密码（正版 UUID {} / 离线 UUID {}）",
                            name, premiumUuid, offlineUuid);
                });
            });
        } catch (RejectedExecutionException e) {
            player.sendSystemMessage(Messages.get("register.save_failed"));
        }
    }

    /** 修改当前账号密码（离线账号 / 正版离线备份密码均可）。 */
    public static void changePassword(ServerPlayer player, String oldPassword, String newPassword) {
        Config config = PLogin.config();
        UserStore users = PLogin.users();
        MinecraftServer server = PLogin.server();
        if (config == null || users == null || server == null) {
            return;
        }
        if (users.isCorrupted()) {
            player.sendSystemMessage(Messages.get("auth.corrupt"));
            return;
        }
        GameProfile profile = player.getGameProfile();
        UserRecord record = users.find(profile.name());
        if (record == null) {
            player.sendSystemMessage(Messages.get(HybridAuth.isOfflineProfile(profile)
                    ? "login.not_registered" : "changepw.premium"));
            return;
        }
        if (newPassword.length() < config.passwordMinLength
                || newPassword.length() > config.passwordMaxLength) {
            player.sendSystemMessage(Messages.get("register.length",
                    config.passwordMinLength, config.passwordMaxLength));
            return;
        }
        UUID uuid = player.getUUID();
        int iterations = config.passwordIterations;
        try {
            PLogin.executor().submit(() -> {
                boolean oldOk = PasswordHasher.verify(oldPassword, record.salt,
                        record.iterations, record.hash);
                if (!oldOk) {
                    server.execute(() -> player.sendSystemMessage(Messages.get("changepw.old_wrong")));
                    return;
                }
                String salt = PasswordHasher.newSalt();
                String hash = PasswordHasher.hash(newPassword, salt, iterations);
                server.execute(() -> {
                    if (server.getPlayerList().getPlayer(uuid) != player) {
                        return;
                    }
                    if (!users.setPassword(profile.name(), salt, hash, iterations)) {
                        player.sendSystemMessage(Messages.get("register.save_failed"));
                        return;
                    }
                    player.sendSystemMessage(Messages.get("changepw.ok"));
                });
            });
        } catch (RejectedExecutionException e) {
            player.sendSystemMessage(Messages.get("register.save_failed"));
        }
    }

    /** 登出：断开连接，客户端重连后会重新进入配置阶段的登录对话框。 */
    public static void logout(ServerPlayer player) {
        Config config = PLogin.config();
        GameProfile profile = player.getGameProfile();
        if (config != null && config.premiumBypass
                && !HybridAuth.isOfflineProfile(profile) && !HybridAuth.isBackupLogin(profile)) {
            player.sendSystemMessage(Messages.get("logout.premium"));
            return;
        }
        PLogin.LOGGER.info("[PLogin] 玩家 {} 主动登出，断开后需重新登录", profile.name());
        try {
            player.connection.disconnect(Messages.get("logout.ok"));
        } catch (Exception e) {
            PLogin.LOGGER.debug("[PLogin] 断开登出玩家 {} 失败", profile.name(), e);
        }
    }

    private static String safeIp(ServerPlayer player) {
        String ip = player.getIpAddress();
        return ip == null ? "" : ip;
    }
}
