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
 * <p>正版玩家返回的 GameProfile 包含 textures 属性，服务端会随 LoginFinished /
 * PlayerInfoUpdate 一起发给客户端，因此正版皮肤对所有客户端都正常显示。
 *
 * <p>有离线备份密码的正版名：离线登录时始终使用 OfflinePlayer 离线 UUID，
 * 只在 profile 上打 {@link #BACKUP_MARKER} 标记；密码通过后由 Configuration 阶段
 * 直接放行，不再踢正版同名、不再发 Cookie、不再强制重连切换正版 UUID。
 */
public final class HybridAuth {
    /** 离线备份待验证标记；只由服务端注入，客户端无法伪造。 */
    public static final String BACKUP_MARKER = "plogin:offline_backup";
    /** 已通过 transfer 凭据切换到正版 UUID 的标记。 */
    public static final String TRANSFER_MARKER = "plogin:transfer_verified";
    /** transfer 重连但凭据无效的标记；配置阶段会直接断开并提示重试。 */
    public static final String TRANSFER_FAILED_MARKER = "plogin:transfer_failed";

    private HybridAuth() {
    }

    public static ProfileResult verify(MinecraftSessionService service, String name, String digest, InetAddress address,
                                       byte[] transferSecret, boolean transferRequested, String transferIp)
            throws AuthenticationUnavailableException {
        Config config = PLogin.config();
        if (config == null || !config.enabled) {
            // 总开关关闭：完全恢复原版行为
            return service.hasJoinedServer(name, digest, address);
        }
        if (transferRequested) {
            TransferVerification.Pending pending =
                    TransferVerification.consume(transferSecret, name, transferIp);
            if (pending != null) {
                GameProfile profile = SkinService.applyOfflineSkin(
                        new GameProfile(pending.premiumUuid(), name), config.offlineSkinMaxWaitMs);
                profile = withMarker(profile.id(), name, profile.properties(), TRANSFER_MARKER);
                PLogin.LOGGER.info("[PLogin] transfer 凭据验证成功：{} → 正版 UUID {}（使用正版存档）",
                        name, pending.premiumUuid());
                return new ProfileResult(profile);
            }
            GameProfile failed = UUIDUtil.createOfflineProfile(name);
            failed = withMarker(failed.id(), name, failed.properties(), TRANSFER_FAILED_MARKER);
            PLogin.LOGGER.warn("[PLogin] transfer 重连 {} 没有有效的 transfer 凭据", name);
            return new ProfileResult(failed);
        }
        boolean fallback = config.fallbackToOfflineWhenAuthServerDown;
        try {
            ProfileResult result = service.hasJoinedServer(name, digest, address);
            if (result != null) {
                PLogin.users().rememberPremium(name, result.profile().id());
                PLogin.LOGGER.info("[PLogin] 正版验证成功：{} → {}", name, result.profile().id());
                return result;
            }
            PLogin.LOGGER.info("[PLogin] 玩家 {} 没有有效的正版会话，按离线玩家处理", name);
        } catch (AuthenticationUnavailableException e) {
            if (!fallback) {
                throw e;
            }
            PLogin.LOGGER.warn("[PLogin] Mojang 会话服务器不可用，{} 按离线玩家放行（fallbackToOfflineWhenAuthServerDown=true）：{}",
                    name, e.getMessage());
        } catch (RuntimeException e) {
            if (!fallback) {
                throw e;
            }
            PLogin.LOGGER.warn("[PLogin] 正版验证异常，{} 按离线玩家放行", name, e);
        }
        GameProfile offlineProfile = UUIDUtil.createOfflineProfile(name);
        // 离线玩家皮肤：Mojang 正版名 -> signed textures；否则 LittleSkin -> MineSkin 签名。
        // 有界异步：只等待缓存命中或限时窗口，慢的第三方皮肤 API 不再拖死整个登录线程。
        offlineProfile = SkinService.applyOfflineSkin(offlineProfile, config.offlineSkinMaxWaitMs);

        UserRecord record = PLogin.users() == null ? null : PLogin.users().find(name);
        if (record != null && record.premiumBackup && record.uuid != null) {
            // 只做标记，不改 UUID：这个连接从头到尾都是离线 UUID，
            // 密码在 Configuration 对话框里验证，通过后直接继续进入世界。
            offlineProfile = withMarker(offlineProfile.id(), name, offlineProfile.properties(), BACKUP_MARKER);
            PLogin.LOGGER.info("[PLogin] 离线备份玩家：{} 使用离线 UUID {}（密码通过后直接进入，不切换正版 UUID）",
                    name, offlineProfile.id());
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

    public static boolean isTransferVerified(GameProfile profile) {
        return profile != null && profile.properties().containsKey(TRANSFER_MARKER);
    }

    public static boolean isTransferFailed(GameProfile profile) {
        return profile != null && profile.properties().containsKey(TRANSFER_FAILED_MARKER);
    }

    public static boolean isOfflineProfile(GameProfile profile) {
        if (profile == null || profile.name() == null || profile.id() == null) {
            return true;
        }
        return UUIDUtil.createOfflinePlayerUUID(profile.name()).equals(profile.id());
    }
}
