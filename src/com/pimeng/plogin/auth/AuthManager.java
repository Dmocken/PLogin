package com.pimeng.plogin.auth;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket;
import com.pimeng.plogin.Messages;
import com.pimeng.plogin.PLogin;
import com.pimeng.plogin.config.Config;
import com.pimeng.plogin.util.Ui;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;

/**
 * 离线玩家的认证状态机（未注册 / 未登录 / 已登录）以及登录保护。
 */
public final class AuthManager {
    public enum State {
        PENDING_REGISTER,
        PENDING_LOGIN,
        AUTHENTICATED
    }

    public static final class Session {
        public volatile State state;
        public volatile boolean frozen;
        public volatile boolean spectator;
        public final long joinTime = System.currentTimeMillis();
        public final double x;
        public final double y;
        public final double z;
        public final float yaw;
        public final float pitch;
        public final boolean prevInvulnerable;
        public final boolean prevNoGravity;
        public final GameType prevGameType;
        public final String ip;
        public int attempts;
        public volatile boolean busy;
        public long lastBlockedCommand;
        public long lastActionBar;
        public long lastEffect;
        public long lastBlockedChat;

        Session(net.minecraft.server.level.ServerPlayer player, State state, boolean frozen) {
            this.state = state;
            this.frozen = frozen;
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
            this.yaw = player.getYRot();
            this.pitch = player.getXRot();
            this.prevInvulnerable = player.isInvulnerable();
            this.prevNoGravity = player.isNoGravity();
            this.prevGameType = player.gameMode();
            String address = player.getIpAddress();
            this.ip = address == null ? "" : address;
        }
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> ipBans = new ConcurrentHashMap<>();

    /** 跨连接的失败计数（key -> [count, lastFailureAt]，窗口过期清零，重连不会重置）。 */
    private final Map<String, long[]> failuresByIp = new ConcurrentHashMap<>();
    private final Map<String, long[]> failuresByAccount = new ConcurrentHashMap<>();
    /** 账号级临时锁定（爆破防护），到期自动解锁。 */
    private final Map<String, Long> accountLocks = new ConcurrentHashMap<>();
    private long lastFailureSweep;

    public void onJoin(ServerPlayer player) {
        Config config = PLogin.config();
        if (config == null || !config.enabled) {
            return;
        }
        MinecraftServer server = PLogin.server();
        if (server == null) {
            PLogin.LOGGER.warn("[PLogin] 服务器尚未初始化，跳过 {} 的认证处理", player.getGameProfile().name());
            return;
        }
        long now = System.currentTimeMillis();
        String ip = safeIp(player);
        Long bannedUntil = ipBans.get(ip);
        if (bannedUntil != null) {
            if (bannedUntil > now) {
                disconnect(player, Messages.get("ip.banned", (bannedUntil - now + 999) / 1000));
                return;
            }
            ipBans.remove(ip);
        }

        GameProfile profile = player.getGameProfile();
        String name = profile.name();
        boolean offline = HybridAuth.isOfflineProfile(profile);
        boolean backupLogin = HybridAuth.isBackupLogin(profile);
        boolean verifiedBackup = HybridAuth.isVerifiedBackup(profile);
        boolean treatOffline = offline || backupLogin;

        // 账号数据损坏：默认拒绝认证，不给“空账号库上重新注册/抢注”的机会。
        // 正版验证通过的玩家不依赖本服账号数据，仍可正常进入。
        if (PLogin.users() != null && PLogin.users().isCorrupted() && (treatOffline || verifiedBackup)) {
            PLogin.LOGGER.error("[PLogin] 账号数据损坏，拒绝 {} 的离线认证流程", name);
            disconnect(player, Messages.get("auth.corrupt"));
            return;
        }

        // 账号级锁定（跨连接爆破防护）：重连拿不到新的尝试次数。
        Long lockedUntil = accountLocks.get(toKey(name));
        if (lockedUntil != null) {
            if (lockedUntil > now) {
                disconnect(player, Messages.get("login.account_locked", (lockedUntil - now + 999) / 1000));
                return;
            }
            accountLocks.remove(toKey(name));
        }

        if (verifiedBackup) {
            UserRecord record = PLogin.users().find(name);
            if (record == null || !record.premiumBackup || !profile.id().toString().equals(record.uuid)) {
                disconnect(player, Messages.get("conflict"));
                return;
            }
            // 这里不再踢正版：令牌只在密码验证成功时发放，验证时已经踢过；
            // 如果正版又抢先上线，拒绝这次重连，避免来回互踢。
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (other == player || !other.getName().getString().equalsIgnoreCase(name)) {
                    continue;
                }
                if (!HybridAuth.isOfflineProfile(other.getGameProfile())
                        && !HybridAuth.isBackupLogin(other.getGameProfile())
                        && !HybridAuth.isVerifiedBackup(other.getGameProfile())) {
                    disconnect(player, Messages.get("premium.backup.premium_online"));
                    return;
                }
            }
            PLogin.users().touchLogin(name, ip);
            player.sendSystemMessage(Messages.get("premium.backup.welcome", name));
            Ui.title(player,
                    Component.literal(Messages.raw("title.backup")).withStyle(ChatFormatting.GREEN),
                    Component.literal(Messages.raw("subtitle.backup")).withStyle(ChatFormatting.GRAY));
            PLogin.LOGGER.info("[PLogin] 备份验证重连成功: {} ({})，使用正版 UUID/存档", name, profile.id());
            return;
        }

        if (!treatOffline && config.premiumBypass) {
            PLogin.users().rememberPremium(name, profile.id());
            // 正版玩家上线时，踢掉同名的离线/离线备份玩家（备份玩家现在也用正版 UUID）
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (other != player && other.getName().getString().equalsIgnoreCase(name)) {
                    disconnect(other, Messages.get("premium.name.kick"));
                }
            }
            player.sendSystemMessage(Messages.get("welcome.premium", name));
            Ui.title(player,
                    Component.literal(Messages.raw("title.premium")).withStyle(ChatFormatting.GREEN),
                    Component.literal(Messages.raw("subtitle.premium")).withStyle(ChatFormatting.GRAY));
            PLogin.LOGGER.info("[PLogin] 正版玩家 {} ({}) 直接进入服务器", name, profile.id());
            return;
        }

        // 正版同名在线时：
        // - 普通离线号不能同时进来；
        // - 带离线备份的待验证号可以先进来冻结，等密码通过后才踢正版。
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other == player || !other.getName().getString().equalsIgnoreCase(name)) {
                continue;
            }
            boolean otherPremium = !HybridAuth.isOfflineProfile(other.getGameProfile())
                    && !HybridAuth.isBackupLogin(other.getGameProfile())
                    && !HybridAuth.isVerifiedBackup(other.getGameProfile());
            if (backupLogin && otherPremium) {
                continue;
            }
            disconnect(player, Messages.get("premium.name.kick"));
            return;
        }

        UserRecord record = PLogin.users().find(name);
        boolean hasBackup = record != null && record.premiumBackup && record.offlineUuid != null;

        if (config.premiumNameProtection) {
            UUID knownPremium = PLogin.users().premiumUuid(name);
            // 有离线备份密码的正版名允许离线进入（进来后仍需 /login 密码）。
            if (knownPremium != null && !knownPremium.equals(profile.id()) && !hasBackup) {
                disconnect(player, Messages.get("premium.name"));
                PLogin.LOGGER.warn("[PLogin] 拒绝离线玩家 {} ({})：该名字属于正版账号 {}",
                        name, profile.id(), knownPremium);
                return;
            }
        }

        if (record != null) {
            boolean uuidMatch = record.uuid != null && record.uuid.equals(profile.id().toString());
            boolean backupMatch = record.premiumBackup && record.offlineUuid != null
                    && record.offlineUuid.equals(profile.id().toString());
            if (!uuidMatch && !backupMatch) {
                disconnect(player, Messages.get("conflict"));
                PLogin.LOGGER.warn("[PLogin] 玩家 {} 的 UUID 与已注册记录不一致（记录 {} / 离线备份 {} / 当前 {}）",
                        name, record.uuid, record.offlineUuid, profile.id());
                return;
            }
        }
        if (record == null && !config.requireRegistration) {
            PLogin.LOGGER.info("[PLogin] 离线玩家 {} 未注册，但 requireRegistration=false，直接放行", name);
            LimboManager.returnToReal(player);
            revealInTab(player);
            return;
        }

        // 兼容上次未登录就断线/崩溃后保存下来的 Invulnerable / NoGravity 标记：
        // 这些是 PLogin 冻结时设置的，不应该永久留在玩家数据里。
        if (player.isInvulnerable()) {
            player.setInvulnerable(false);
        }
        if (player.isNoGravity() && player.gameMode() != GameType.SPECTATOR) {
            player.setNoGravity(false);
        }

        State state = record == null ? State.PENDING_REGISTER : State.PENDING_LOGIN;
        Session session = new Session(player, state, config.freezeMovement);
        sessions.put(player.getUUID(), session);
        freeze(player, session);
        sendPrompt(player, record == null);
        PLogin.LOGGER.info("[PLogin] 离线玩家 {} ({}) 加入，状态={}，IP={}", name, profile.id(), state, ip);
    }

    public void onDisconnect(ServerPlayer player) {
        LimboManager.restoreBeforeSave(player);
        Session session = sessions.remove(player.getUUID());
        if (session == null) {
            return;
        }
        // 在玩家数据落盘前恢复实体的持久状态，避免下次进服一直无敌/悬浮。
        player.setInvulnerable(false);
        player.setNoGravity(player.gameMode() == GameType.SPECTATOR);
        player.removeEffect(MobEffects.BLINDNESS);
        if (session.spectator && player.gameMode() == GameType.SPECTATOR && session.prevGameType != GameType.SPECTATOR) {
            player.setGameMode(session.prevGameType);
        }
    }

    public void tick(MinecraftServer server) {
        Config config = PLogin.config();
        if (config == null || !config.enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!ipBans.isEmpty()) {
            ipBans.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        sweepFailureTracking();
        if (sessions.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                sessions.remove(entry.getKey());
                continue;
            }
            Session session = entry.getValue();
            if (session.state == State.AUTHENTICATED) {
                sessions.remove(entry.getKey());
                continue;
            }

            int timeout = config.loginTimeoutSeconds;
            if (timeout > 0 && (now - session.joinTime) / 1000L >= timeout) {
                if (config.kickOnTimeout) {
                    sessions.remove(entry.getKey());
                    disconnect(player, Messages.get("timeout"));
                    continue;
                }
                if (!session.spectator) {
                    session.spectator = true;
                    session.frozen = false;
                    if (player.gameMode() != GameType.SPECTATOR) {
                        player.setGameMode(GameType.SPECTATOR);
                    }
                    player.setNoGravity(false);
                    player.setInvulnerable(false);
                    player.removeEffect(MobEffects.BLINDNESS);
                    player.sendSystemMessage(Messages.get("timeout.spectator"));
                }
            }

            if (session.frozen) {
                player.setNoGravity(true);
                player.setInvulnerable(true);
                if (player.getY() < -100.0) {
                    double targetY = session.y < -100.0 ? 100.0 : session.y;
                    player.connection.teleport(session.x, targetY, session.z, session.yaw, session.pitch);
                    player.setHealth(player.getMaxHealth());
                }
                if (config.blindness && now - session.lastEffect > 4000L) {
                    player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 120, 0, false, false, false));
                    session.lastEffect = now;
                }
                if (now - session.lastActionBar > 1000L) {
                    long remaining = timeout <= 0 ? -1 : Math.max(0, timeout - (now - session.joinTime) / 1000L);
                    if (remaining >= 0) {
                        String key = session.state == State.PENDING_REGISTER ? "actionbar.register" : "actionbar.login";
                        if (remaining <= 10) {
                            String action = session.state == State.PENDING_REGISTER ? "注册" : "登录";
                            Ui.actionBar(player, Messages.raw("actionbar.urgent", action, remaining));
                        } else {
                            Ui.actionBar(player, Messages.raw(key, remaining));
                        }
                    }
                    session.lastActionBar = now;
                }
            }
        }
    }

    public boolean isPending(UUID uuid) {
        Session session = sessions.get(uuid);
        return session != null && session.state != State.AUTHENTICATED;
    }

    public boolean isPending(ServerPlayer player) {
        return isPending(player.getUUID());
    }

    /** 登录成功后，把之前从 Tab 列表隐藏的玩家重新广播给所有人，并补一条进入消息。 */
    private void revealInTab(ServerPlayer player) {
        MinecraftServer server = PLogin.server();
        if (server == null || player.connection == null) {
            return;
        }
        boolean wasHidden = HiddenPlayers.reveal(player.getUUID());
        server.getPlayerList().broadcastAll(
                ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
        if (wasHidden) {
            server.getPlayerList().broadcastSystemMessage(
                    Component.translatable("multiplayer.player.joined", player.getDisplayName())
                            .withStyle(ChatFormatting.YELLOW), false);
        }
    }

    /** /logout 后立即把玩家从其他人的 Tab 列表移除，并补一条离开消息。 */
    private void hideFromTab(ServerPlayer player) {
        MinecraftServer server = PLogin.server();
        if (server == null) {
            return;
        }
        HiddenPlayers.mark(player.getUUID());
        ClientboundPlayerInfoRemovePacket remove =
                new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID()));
        Component quit = Component.translatable("multiplayer.player.left", player.getDisplayName())
                .withStyle(ChatFormatting.YELLOW);
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other != player && other.connection != null) {
                other.connection.send(remove);
                other.sendSystemMessage(quit);
            }
        }
    }

    public boolean isFrozen(ServerPlayer player) {
        Session session = sessions.get(player.getUUID());
        return session != null && session.frozen;
    }

    public Session session(ServerPlayer player) {
        return sessions.get(player.getUUID());
    }

    public void requestRegister(ServerPlayer player, String password, String confirm) {
        Config config = PLogin.config();
        if (config == null) {
            return;
        }
        if (PLogin.users() != null && PLogin.users().isCorrupted()) {
            player.sendSystemMessage(Messages.get("auth.corrupt"));
            return;
        }
        boolean offlineProfile = HybridAuth.isOfflineProfile(player.getGameProfile());
        UUID uuid = player.getUUID();
        Session session = sessions.get(uuid);
        // 只有“没有 pending session 的正版 UUID”才是真正通过正版验证的玩家。
        // 离线备份登录虽然也用正版 UUID，但处于 PENDING_LOGIN，不允许无密码重置备份密码。
        boolean premiumAuthenticated = !offlineProfile && session == null;
        if (premiumAuthenticated && config.premiumBypass) {
            // 正版已验证：允许设置/更新离线备份密码，不再当作普通离线注册。
            if (!config.premiumPasswordBackup) {
                player.sendSystemMessage(Messages.get("premium.password_disabled"));
                return;
            }
            requestPremiumBackup(player, password, confirm);
            return;
        }
        String name = player.getGameProfile().name();
        UserRecord existing = PLogin.users().find(name);
        boolean existingBackup = existing != null && existing.premiumBackup;
        if (config.premiumNameProtection) {
            UUID knownPremium = PLogin.users() == null ? null : PLogin.users().premiumUuid(name);
            if (knownPremium != null && !knownPremium.equals(player.getUUID()) && !existingBackup) {
                player.sendSystemMessage(Messages.get("register.premium_name"));
                return;
            }
        }
        if (session == null) {
            player.sendSystemMessage(Messages.get("login.already"));
            return;
        }
        if (session.state == State.PENDING_LOGIN) {
            player.sendSystemMessage(Messages.get(existingBackup ? "premium.password_exists" : "register.exists"));
            return;
        }
        if (session.busy) {
            player.sendSystemMessage(Messages.get("register.busy"));
            return;
        }
        if (password.length() < config.passwordMinLength || password.length() > config.passwordMaxLength) {
            player.sendSystemMessage(Messages.get("register.length", config.passwordMinLength, config.passwordMaxLength));
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

        session.busy = true;
        player.sendSystemMessage(Messages.get("register.busy"));
        MinecraftServer server = PLogin.server();
        String ip = safeIp(player);
        int iterations = config.passwordIterations;
        PLogin.executor().submit(() -> {
            String salt = PasswordHasher.newSalt();
            String hash = PasswordHasher.hash(password, salt, iterations);
            server.execute(() -> {
                ServerPlayer online = server.getPlayerList().getPlayer(uuid);
                if (online != player) {
                    return;
                }
                Session current = sessions.get(uuid);
                if (current != session || current.state != State.PENDING_REGISTER) {
                    session.busy = false;
                    return;
                }
                long now = System.currentTimeMillis();
                UserRecord record = new UserRecord(name, player.getGameProfile().id().toString(), salt, hash,
                        iterations, now, now, ip);
                boolean duplicate = PLogin.users().find(name) != null;
                if (!PLogin.users().register(record)) {
                    session.busy = false;
                    player.sendSystemMessage(Messages.get(duplicate ? "register.exists" : "register.save_failed"));
                    return;
                }
                PLogin.LOGGER.info("[PLogin] 玩家 {} 注册成功", name);
                authenticate(player, "register.ok", "register");
            });
        });
    }

    /**
     * 正版玩家设置/更新离线备份密码。失败时正版验证不可用，仍可用该密码离线登录。
     */
    private void requestPremiumBackup(ServerPlayer player, String password, String confirm) {
        Config config = PLogin.config();
        if (config == null) {
            return;
        }
        String name = player.getGameProfile().name();
        if (password.length() < config.passwordMinLength || password.length() > config.passwordMaxLength) {
            player.sendSystemMessage(Messages.get("register.length", config.passwordMinLength, config.passwordMaxLength));
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

        boolean hadBackup = PLogin.users().hasPremiumBackup(name);
        player.sendSystemMessage(Messages.get("register.busy"));
        MinecraftServer server = PLogin.server();
        UUID premiumUuid = player.getUUID();
        UUID offlineUuid = UUIDUtil.createOfflinePlayerUUID(name);
        String ip = safeIp(player);
        int iterations = config.passwordIterations;
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
                boolean saved = PLogin.users().setPremiumBackup(name, premiumUuid.toString(), offlineUuid.toString(),
                        salt, hash, iterations, now, ip);
                if (!saved) {
                    player.sendSystemMessage(Messages.get("register.save_failed"));
                    return;
                }
                player.sendSystemMessage(Messages.get(hadBackup ? "premium.password_updated" : "premium.password_set"));
                PLogin.LOGGER.info("[PLogin] 正版玩家 {} 设置了离线备份密码（正版 UUID {} / 离线 UUID {}）",
                        name, premiumUuid, offlineUuid);
            });
        });
    }

    public void requestLogin(ServerPlayer player, String password) {
        Config config = PLogin.config();
        if (PLogin.users() != null && PLogin.users().isCorrupted()) {
            player.sendSystemMessage(Messages.get("auth.corrupt"));
            return;
        }
        UUID uuid = player.getUUID();
        Session session = sessions.get(uuid);
        // 离线备份登录使用正版 UUID，但会带 plogin:offline_backup 标记并创建 PENDING_LOGIN session；
        // 没有 session 的正版 UUID 才是已经通过正版验证的玩家。
        if (config != null && config.premiumBypass
                && !HybridAuth.isOfflineProfile(player.getGameProfile()) && session == null) {
            player.sendSystemMessage(Messages.get("premium.online"));
            return;
        }
        UserRecord record = PLogin.users().find(player.getGameProfile().name());
        if (record == null) {
            player.sendSystemMessage(Messages.get("login.not_registered"));
            return;
        }
        if (session == null) {
            player.sendSystemMessage(Messages.get("login.already"));
            return;
        }
        if (session.state != State.PENDING_LOGIN) {
            player.sendSystemMessage(Messages.get("login.already"));
            return;
        }
        if (session.busy) {
            player.sendSystemMessage(Messages.get("login.busy"));
            return;
        }

        session.busy = true;
        player.sendSystemMessage(Messages.get("login.busy"));
        MinecraftServer server = PLogin.server();
        PLogin.executor().submit(() -> {
            boolean ok = PasswordHasher.verify(password, record.salt, record.iterations, record.hash);
            server.execute(() -> {
                ServerPlayer online = server.getPlayerList().getPlayer(uuid);
                if (online != player) {
                    return;
                }
                Session current = sessions.get(uuid);
                if (current != session || current.state != State.PENDING_LOGIN) {
                    session.busy = false;
                    return;
                }
                if (!ok) {
                    recordFailure(player, session);
                    return;
                }
                String name = player.getGameProfile().name();
                String ip = safeIp(player);
                if (record.premiumBackup) {
                    // 密码验证通过之后才踢正版同名玩家。
                    for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                        if (other == player || !other.getName().getString().equalsIgnoreCase(name)) {
                            continue;
                        }
                        if (!HybridAuth.isOfflineProfile(other.getGameProfile())
                                && !HybridAuth.isBackupLogin(other.getGameProfile())
                                && !HybridAuth.isVerifiedBackup(other.getGameProfile())) {
                            disconnect(other, Messages.get("premium.backup.kick_premium"));
                        }
                    }
                    // 发放与连接绑定的一次性重连凭据（Cookie 下发给客户端，
                    // 同名的其他连接没有凭据就不能顶替消费）。
                    byte[] secret = BackupVerification.issue(name, ip);
                    if (secret == null) {
                        player.sendSystemMessage(Messages.get("premium.backup.overloaded"));
                        player.connection.disconnect(Messages.get("premium.backup.relogin"));
                        return;
                    }
                    player.connection.send(new ClientboundStoreCookiePacket(HybridAuth.BACKUP_COOKIE_KEY, secret));
                    PLogin.users().touchLogin(name, ip);
                    PLogin.LOGGER.info("[PLogin] {} 备份密码验证成功，正版同名已下线；断开等待重连（凭据经 Cookie 下发）", name);
                    player.connection.disconnect(Messages.get("premium.backup.relogin"));
                    return;
                }
                PLogin.users().touchLogin(name, ip);
                authenticate(player, "login.ok", "login");
            });
        });
    }

    public void requestLogout(ServerPlayer player) {
        Config config = PLogin.config();
        if (PLogin.users() != null && PLogin.users().isCorrupted()) {
            player.sendSystemMessage(Messages.get("auth.corrupt"));
            return;
        }
        if (config != null && config.premiumBypass && !HybridAuth.isOfflineProfile(player.getGameProfile())) {
            player.sendSystemMessage(Messages.get("logout.premium"));
            return;
        }
        UserRecord record = PLogin.users().find(player.getGameProfile().name());
        if (record == null) {
            player.sendSystemMessage(Messages.get("login.not_registered"));
            return;
        }
        if (sessions.containsKey(player.getUUID())) {
            player.sendSystemMessage(Messages.get("logout.not_logged"));
            return;
        }
        Session session = new Session(player, State.PENDING_LOGIN, config != null && config.freezeMovement);
        sessions.put(player.getUUID(), session);
        freeze(player, session);
        LimboManager.prepare(player);
        hideFromTab(player);
        sendPrompt(player, false);
        player.sendSystemMessage(Messages.get("logout.ok"));
    }

    public void requestChangePassword(ServerPlayer player, String oldPassword, String newPassword) {
        Config config = PLogin.config();
        if (PLogin.users() != null && PLogin.users().isCorrupted()) {
            player.sendSystemMessage(Messages.get("auth.corrupt"));
            return;
        }
        if (config != null && config.premiumBypass && !HybridAuth.isOfflineProfile(player.getGameProfile())) {
            player.sendSystemMessage(Messages.get("changepw.premium"));
            return;
        }
        UserRecord record = PLogin.users().find(player.getGameProfile().name());
        if (record == null) {
            player.sendSystemMessage(Messages.get("login.not_registered"));
            return;
        }
        if (sessions.containsKey(player.getUUID())) {
            player.sendSystemMessage(Messages.get("changepw.not_logged"));
            return;
        }
        if (config != null && (newPassword.length() < config.passwordMinLength || newPassword.length() > config.passwordMaxLength)) {
            player.sendSystemMessage(Messages.get("register.length", config.passwordMinLength, config.passwordMaxLength));
            return;
        }
        MinecraftServer server = PLogin.server();
        UUID uuid = player.getUUID();
        int iterations = config == null ? 120000 : config.passwordIterations;
        PLogin.executor().submit(() -> {
            boolean oldOk = PasswordHasher.verify(oldPassword, record.salt, record.iterations, record.hash);
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
                if (!PLogin.users().setPassword(player.getGameProfile().name(), salt, hash, iterations)) {
                    player.sendSystemMessage(Messages.get("register.save_failed"));
                    return;
                }
                player.sendSystemMessage(Messages.get("changepw.ok"));
            });
        });
    }

    /**
     * 管理员注销/重置后，把在线玩家重新拉回"未注册"状态。
     */
    public void forceState(ServerPlayer player, State state) {
        if (PLogin.users() != null && PLogin.users().isCorrupted()) {
            player.sendSystemMessage(Messages.get("auth.corrupt"));
            return;
        }
        sessions.remove(player.getUUID());
        Config config = PLogin.config();
        Session session = new Session(player, state, config != null && config.freezeMovement);
        sessions.put(player.getUUID(), session);
        freeze(player, session);
        sendPrompt(player, state == State.PENDING_REGISTER);
    }

    private void authenticate(ServerPlayer player, String messageKey, String how) {
        Session session = sessions.remove(player.getUUID());
        if (session == null) {
            return;
        }
        LimboManager.returnToReal(player);
        revealInTab(player);
        // 离线玩家正常应该是存活/冒险模式；无敌和悬浮只用于未登录冻结，登录后必须清掉。
        boolean keepNoGravity = player.gameMode() == GameType.SPECTATOR;
        player.setInvulnerable(false);
        player.setNoGravity(keepNoGravity);
        player.removeEffect(MobEffects.BLINDNESS);
        if (session.spectator && player.gameMode() == GameType.SPECTATOR && session.prevGameType != GameType.SPECTATOR) {
            player.setGameMode(session.prevGameType);
        }
        player.setHealth(player.getMaxHealth());
        player.sendSystemMessage(Messages.get(messageKey));
        Ui.title(player, Messages.raw("title.ok"), Messages.raw("subtitle.ok"));
        PLogin.LOGGER.info("[PLogin] 玩家 {} 已登录（{}），uuid={}", player.getGameProfile().name(), how, player.getUUID());
    }

    /** 记录一次失败到跨连接计数器；返回窗口内的累计次数。 */
    private long bumpFailure(Map<String, long[]> map, String key, long now, long windowMs) {
        long[] counters = map.get(key);
        if (counters == null) {
            counters = new long[2];
            map.put(key, counters);
        } else if (now - counters[1] > windowMs) {
            counters[0] = 0; // 窗口过期重新计数
        }
        counters[1] = now;
        return ++counters[0];
    }

    private void recordFailure(ServerPlayer player, Session session) {
        Config config = PLogin.config();
        if (config == null) {
            return;
        }
        session.busy = false;
        session.attempts++;
        long now = System.currentTimeMillis();
        long windowMs = Math.max(60, config.failureWindowSeconds) * 1000L;
        String name = toKey(player.getGameProfile().name());

        // 1) IP 维度：与旧行为一致（单会话达到阈值时封禁）
        long ipFailures = session.ip.isEmpty() ? 0 : bumpFailure(failuresByIp, session.ip, now, windowMs);
        if (config.ipBanSeconds > 0 && ipFailures >= config.maxLoginAttempts) {
            ipBans.put(session.ip, now + config.ipBanSeconds * 1000L);
            sessions.remove(player.getUUID());
            PLogin.LOGGER.warn("[PLogin] IP {} 在窗口内累计 {} 次密码错误，临时封禁 {} 秒",
                    session.ip, ipFailures, config.ipBanSeconds);
            disconnect(player, Messages.get("login.banned", config.ipBanSeconds));
            return;
        }

        // 2) 账号维度：跨连接累计，重连换新 Session 不能重置。
        long accountFailures = bumpFailure(failuresByAccount, name, now, windowMs);
        if (config.accountLockSeconds > 0 && accountFailures >= config.maxAccountFailures) {
            accountLocks.put(name, now + config.accountLockSeconds * 1000L);
            sessions.remove(player.getUUID());
            PLogin.LOGGER.warn("[PLogin] 账号 {} 在窗口内累计 {} 次密码错误，临时锁定 {} 秒",
                    name, accountFailures, config.accountLockSeconds);
            disconnect(player, Messages.get("login.account_locked", config.accountLockSeconds));
            return;
        }

        player.sendSystemMessage(Messages.get("login.wrong", Math.max(1, config.maxLoginAttempts - session.attempts)));
    }

    private static String toKey(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /** 定期清掉过期的失败计数/账号锁，避免长跑服缓慢积内存。 */
    private void sweepFailureTracking() {
        long now = System.currentTimeMillis();
        if (now - lastFailureSweep < 30_000L) {
            return;
        }
        lastFailureSweep = now;
        long windowMs = Math.max(60, PLogin.config() == null ? 1800 : PLogin.config().failureWindowSeconds) * 1000L;
        if (!failuresByIp.isEmpty()) {
            failuresByIp.entrySet().removeIf(entry -> now - entry.getValue()[1] > windowMs);
        }
        if (!failuresByAccount.isEmpty()) {
            failuresByAccount.entrySet().removeIf(entry -> now - entry.getValue()[1] > windowMs);
        }
        if (!accountLocks.isEmpty()) {
            accountLocks.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
    }

    private void freeze(ServerPlayer player, Session session) {
        Config config = PLogin.config();
        if (config == null || !config.freezeMovement) {
            player.setInvulnerable(true);
            return;
        }
        player.setInvulnerable(true);
        player.setNoGravity(true);
        player.setDeltaMovement(0.0, 0.0, 0.0);
        player.stopRiding();
        player.closeContainer();
        player.setHealth(player.getMaxHealth());
        if (config.blindness) {
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 120, 0, false, false, false));
            session.lastEffect = System.currentTimeMillis();
        }
    }

    private void sendPrompt(ServerPlayer player, boolean register) {
        String name = player.getGameProfile().name();
        player.sendSystemMessage(Messages.get(register ? "welcome.register" : "welcome.login", name));
        player.sendSystemMessage(Messages.get("freeze.notice"));
        Ui.title(player,
                Messages.raw(register ? "title.register" : "title.login"),
                Messages.raw(register ? "subtitle.register" : "subtitle.login"));
    }

    public void notifyBlockedChat(ServerPlayer player) {
        Session session = sessions.get(player.getUUID());
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - session.lastBlockedChat > 1500L) {
            session.lastBlockedChat = now;
            player.sendSystemMessage(Messages.get("chat.blocked"));
        }
    }

    public void notifyBlockedCommand(ServerPlayer player) {
        Session session = sessions.get(player.getUUID());
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - session.lastBlockedCommand > 1500L) {
            session.lastBlockedCommand = now;
            player.sendSystemMessage(Messages.get("command.blocked"));
        }
    }

    public int attemptCount(ServerPlayer player) {
        Session session = sessions.get(player.getUUID());
        return session == null ? 0 : session.attempts;
    }

    public static boolean isAllowedAuthCommand(String command) {
        if (command == null) {
            return false;
        }
        String text = command.trim();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        if (text.isEmpty()) {
            return false;
        }
        String name = text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return switch (name) {
            case "register", "reg", "login", "l", "log", "logout", "changepassword", "cp" -> true;
            default -> false;
        };
    }

    public static void disconnect(ServerPlayer player, net.minecraft.network.chat.Component reason) {
        try {
            player.connection.disconnect(reason);
        } catch (Exception e) {
            PLogin.LOGGER.debug("[PLogin] 断开 {} 失败", player.getGameProfile().name(), e);
        }
    }

    private static String safeIp(ServerPlayer player) {
        String ip = player.getIpAddress();
        return ip == null ? "" : ip;
    }

    public boolean isIpBanned(String ip) {
        Long until = ipBans.get(ip);
        return until != null && until > System.currentTimeMillis();
    }
}
