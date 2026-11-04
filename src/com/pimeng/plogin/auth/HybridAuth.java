package com.pimeng.plogin.auth;

import com.google.common.collect.HashMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.pimeng.plogin.PLogin;
import com.pimeng.plogin.config.Config;
import com.pimeng.plogin.skin.SkinService;
import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

/**
 * 混合登录核心：先走原版 hasJoined 正版验证；失败（无会话 / 会话服务器不可用）
 * 时回退成原版离线 UUID。
 *
 * 正版玩家返回的 GameProfile 包含 textures 属性，服务端会随 LoginFinished /
 * PlayerInfoUpdate 一起发给客户端，因此正版皮肤对所有客户端都正常显示。
 */
public final class HybridAuth {
    /** 离线备份待验证标记；只由服务端注入，客户端无法伪造。 */
    public static final String BACKUP_MARKER = "plogin:offline_backup";
    /** 备份密码已通过、重连切正版 UUID 的标记。 */
    public static final String VERIFIED_MARKER = "plogin:backup_verified";

    private HybridAuth() {
    }

    public static ProfileResult verify(MinecraftSessionService service, String name, String digest, InetAddress address)
            throws AuthenticationUnavailableException {
        Config config = PLogin.config();
        if (config == null || !config.enabled) {
            // 总开关关闭：完全恢复原版行为
            return service.hasJoinedServer(name, digest, address);
        }
        boolean fallback = config.fallbackToOfflineWhenAuthServerDown;
        try {
            ProfileResult result = service.hasJoinedServer(name, digest, address);
            if (result != null) {
                PLogin.users().rememberPremium(name, result.profile().id());
                PLogin.LOGGER.info("[PLogin] 正版验证成功: {} -> {}", name, result.profile().id());
                return result;
            }
            PLogin.LOGGER.info("[PLogin] {} 没有有效的正版会话，按离线玩家处理", name);
        } catch (AuthenticationUnavailableException e) {
            if (!fallback) {
                throw e;
            }
            PLogin.LOGGER.warn("[PLogin] Mojang 会话服务器不可用，{} 按离线玩家放行（fallbackToOfflineWhenAuthServerDown=true）: {}",
                    name, e.getMessage());
        } catch (RuntimeException e) {
            if (!fallback) {
                throw e;
            }
            PLogin.LOGGER.warn("[PLogin] 正版验证异常，{} 按离线玩家放行", name, e);
        }
        GameProfile offlineProfile = UUIDUtil.createOfflineProfile(name);
        // 离线玩家皮肤：Mojang 正版名 -> signed textures；否则 LittleSkin -> MineSkin 签名。
        offlineProfile = SkinService.applyOfflineSkin(offlineProfile);

        // 有离线备份密码：
        // - 还没通过密码验证：保持离线 UUID，避免同 UUID 被原版 PlayerList 提前踢掉正版玩家；
        // - 已通过密码验证并重连：才切到正版 UUID，与正版存档共用。
        UserRecord record = PLogin.users() == null ? null : PLogin.users().find(name);
        if (record != null && record.premiumBackup && record.uuid != null) {
            try {
                UUID premiumId = UUID.fromString(record.uuid);
                String ip = address == null ? "" : address.getHostAddress();
                if (BackupVerification.consume(name, ip)) {
                    offlineProfile = withMarker(premiumId, name, offlineProfile.properties(), VERIFIED_MARKER);
                    PLogin.LOGGER.info("[PLogin] 备份验证成功重连: {} 使用正版 UUID {}（与正版存档共用）", name, premiumId);
                } else {
                    offlineProfile = withMarker(offlineProfile.id(), name, offlineProfile.properties(), BACKUP_MARKER);
                    PLogin.LOGGER.info("[PLogin] 离线备份待验证: {} 先使用离线 UUID {}（验证通过后才切正版 UUID）",
                            name, offlineProfile.id());
                }
            } catch (IllegalArgumentException e) {
                PLogin.LOGGER.warn("[PLogin] 离线备份记录中的正版 UUID 无效: {}", record.uuid);
            }
        }
        return new ProfileResult(offlineProfile);
    }

    private static GameProfile withMarker(UUID id, String name, PropertyMap properties, String marker) {
        HashMultimap<String, Property> map = HashMultimap.create();
        for (Map.Entry<String, Property> entry : properties.entries()) {
            map.put(entry.getKey(), entry.getValue());
        }
        map.put(marker, new Property(marker, "1"));
        return new GameProfile(id, name, new PropertyMap(map));
    }

    public static boolean isBackupLogin(GameProfile profile) {
        return profile != null && profile.properties().containsKey(BACKUP_MARKER);
    }

    public static boolean isVerifiedBackup(GameProfile profile) {
        return profile != null && profile.properties().containsKey(VERIFIED_MARKER);
    }

    public static boolean isOfflineProfile(GameProfile profile) {
        if (profile == null || profile.name() == null || profile.id() == null) {
            return true;
        }
        return UUIDUtil.createOfflinePlayerUUID(profile.name()).equals(profile.id());
    }

}
