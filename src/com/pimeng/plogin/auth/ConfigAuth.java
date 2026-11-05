package com.pimeng.plogin.auth;

import com.mojang.authlib.GameProfile;
import com.pimeng.plogin.Messages;
import com.pimeng.plogin.PLogin;
import com.pimeng.plogin.config.Config;
import com.pimeng.plogin.mixin.ServerCommonPacketListenerAccessor;
import com.pimeng.plogin.util.Ui;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

/**
 * 配置阶段（Configuration）认证。
 *
 * <p>玩家完成原版登录阶段后进入 Configuration 状态，PLogin 在
 * {@code ServerConfigurationPacketListenerImpl#returnToWorld()} 里插入
 * {@link AuthConfigurationTask}，阻塞 PrepareSpawn/JoinWorld，直到玩家在对话框里
 * 完成注册或登录。认证通过后任务返回完成，原版继续把玩家放进世界——
 * 不再需要虚拟世界 limbo、冻结、隐藏玩家或数据包过滤。
 *
 * <p>客户端只需原版 26.2 客户端：对话框是原版 {@code ClientboundShowDialogPacket}，
 * 输入内容由客户端通过 {@code ServerboundCustomClickActionPacket} 回传。
 */
public final class ConfigAuth {
    public enum State {
        PENDING_REGISTER("待注册"),
        PENDING_LOGIN("待登录"),
        AUTHENTICATED("已认证");

        private final String label;

        State(String label) {
            this.label = label;
        }

        /** 日志用的中文名称。 */
        public String label() {
            return label;
        }
    }

    /** 登录成功后要补发的欢迎信息类型。 */
    private enum Welcome {
        NONE("无"),
        PREMIUM("正版"),
        BACKUP("离线备份"),
        LOGIN("登录"),
        REGISTER("注册");

        private final String label;

        Welcome(String label) {
            this.label = label;
        }

        /** 日志用的中文名称。 */
        String label() {
            return label;
        }
    }

    /** returnToWorld 的处理结果：挂任务 / 不处理 / 已断开（跳过原版后续流程）。 */
    public record StartResult(ConfigurationTask task, boolean abort) {
        static StartResult skip() {
            return new StartResult(null, false);
        }

        static StartResult disconnected() {
            return new StartResult(null, true);
        }

        static StartResult wait(ConfigurationTask task) {
            return new StartResult(task, false);
        }
    }

    private final Map<ServerConfigurationPacketListenerImpl, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Welcome> welcomes = new ConcurrentHashMap<>();

    /** 已登录玩家因 debugconfig 重新进入配置阶段时，用连接做一次性标记，避免再次弹登录框。 */
    private final Set<Connection> reconfiguring = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private final Map<String, Long> ipBans = new ConcurrentHashMap<>();
    /** 跨连接的失败计数（key -> [count, lastFailureAt]）。 */
    private final Map<String, long[]> failuresByIp = new ConcurrentHashMap<>();
    private final Map<String, long[]> failuresByAccount = new ConcurrentHashMap<>();
    private final Map<String, Long> accountLocks = new ConcurrentHashMap<>();
    private long lastFailureSweep;

    // ------------------------------------------------------------------ 配置阶段入口

    /**
     * 在 {@code returnToWorld()} 开头调用。返回的 task 需要加入 configurationTasks，
     * 从而排在 SynchronizeRegistries / 资源包之后、PrepareSpawn 之前。
     */
    public StartResult begin(ServerConfigurationPacketListenerImpl listener) {
        Connection connection = connectionOf(listener);
        if (connection == null) {
            return StartResult.skip();
        }
        if (consumeReconfiguring(connection)) {
            PLogin.LOGGER.info("[PLogin] 玩家 {} 重新进入配置阶段，跳过登录对话框",
                    listener.getOwner().name());
            return StartResult.skip();
        }

        Config config = PLogin.config();
        UserStore users = PLogin.users();
        if (config == null || !config.enabled || users == null) {
            return StartResult.skip();
        }

        try {
            return doBegin(listener, connection, config, users);
        } catch (Exception e) {
            PLogin.LOGGER.error("[PLogin] 配置阶段认证初始化失败，断开 {}",
                    listener.getOwner().name(), e);
            disconnect(listener, Messages.get("auth.internal_error"));
            return StartResult.disconnected();
        }
    }

    private StartResult doBegin(ServerConfigurationPacketListenerImpl listener, Connection connection,
                                Config config, UserStore users) {
        GameProfile profile = listener.getOwner();
        String name = profile.name();
        String ip = ipOf(connection);
        long now = System.currentTimeMillis();

        // 1) IP 临时封禁
        if (!ip.isEmpty()) {
            Long bannedUntil = ipBans.get(ip);
            if (bannedUntil != null) {
                if (bannedUntil > now) {
                    disconnect(listener, Messages.get("ip.banned", (bannedUntil - now + 999) / 1000));
                    return StartResult.disconnected();
                }
                ipBans.remove(ip);
            }
        }

        // 2) 账号级临时锁定（跨连接）
        String accountKey = toKey(name);
        Long lockedUntil = accountLocks.get(accountKey);
        if (lockedUntil != null) {
            if (lockedUntil > now) {
                disconnect(listener, Messages.get("login.account_locked", (lockedUntil - now + 999) / 1000));
                return StartResult.disconnected();
            }
            accountLocks.remove(accountKey);
        }

        boolean offline = HybridAuth.isOfflineProfile(profile);
        boolean backupLogin = HybridAuth.isBackupLogin(profile);
        boolean treatOffline = offline || backupLogin;

        // 3) transfer 重连：凭据失败直接给出明确原因；凭据成功则跳过对话框，使用正版 UUID/存档
        if (HybridAuth.isTransferFailed(profile)) {
            PLogin.LOGGER.warn("[PLogin] transfer 重连 {} 的凭据无效或已过期", name);
            disconnect(listener, Messages.get("transfer.failed"));
            return StartResult.disconnected();
        }
        if (HybridAuth.isTransferVerified(profile)) {
            if (users.isCorrupted()) {
                disconnect(listener, Messages.get("auth.corrupt"));
                return StartResult.disconnected();
            }
            UserRecord transferRecord = users.find(name);
            if (transferRecord == null || !transferRecord.premiumBackup
                    || !profile.id().toString().equals(transferRecord.uuid)) {
                disconnect(listener, Messages.get("conflict"));
                return StartResult.disconnected();
            }
            for (ServerPlayer other : PLogin.server().getPlayerList().getPlayers()) {
                if (other == null || !other.getName().getString().equalsIgnoreCase(name)) {
                    continue;
                }
                disconnect(listener, Messages.get("premium.backup.premium_online"));
                return StartResult.disconnected();
            }
            users.touchLogin(name, ip);
            welcomes.put(profile.id(), Welcome.BACKUP);
            PLogin.LOGGER.info("[PLogin] transfer 重连成功：{}（{}），使用正版 UUID/存档", name, profile.id());
            return StartResult.skip();
        }

        // 4) 账号数据损坏时不接受离线认证
        if (users.isCorrupted() && treatOffline) {
            PLogin.LOGGER.error("[PLogin] 账号数据损坏，拒绝 {} 的离线认证流程", name);
            disconnect(listener, Messages.get("auth.corrupt"));
            return StartResult.disconnected();
        }

        // 5) 正版验证通过且允许直通
        if (!treatOffline && config.premiumBypass) {
            users.rememberPremium(name, profile.id());
            for (ServerPlayer other : PLogin.server().getPlayerList().getPlayers()) {
                if (other != null && other.getName().getString().equalsIgnoreCase(name)) {
                    other.connection.disconnect(Messages.get("premium.name.kick"));
                }
            }
            welcomes.put(profile.id(), Welcome.PREMIUM);
            PLogin.LOGGER.info("[PLogin] 正版玩家 {}（{}）直接进入服务器", name, profile.id());
            return StartResult.skip();
        }

        // 6) 同名玩家在线时的处理：不再踢人，直接拒绝新连接，避免两个同名实体/屏幕同时存在。
        for (ServerPlayer other : PLogin.server().getPlayerList().getPlayers()) {
            if (other == null || !other.getName().getString().equalsIgnoreCase(name)) {
                continue;
            }
            if (backupLogin && !treatOffline(other.getGameProfile())) {
                // 正版同名在线：离线备份密码也先不踢人，等对方下线后再登录
                disconnect(listener, Messages.get("premium.backup.premium_online"));
                return StartResult.disconnected();
            }
            disconnect(listener, Messages.get("name.online"));
            return StartResult.disconnected();
        }

        UserRecord record = users.find(name);
        boolean hasBackup = record != null && record.premiumBackup && record.offlineUuid != null;

        // 7) 正版名字保护
        if (config.premiumNameProtection) {
            UUID knownPremium = users.premiumUuid(name);
            if (knownPremium != null && !knownPremium.equals(profile.id()) && !hasBackup) {
                PLogin.LOGGER.warn("[PLogin] 拒绝离线玩家 {}（{}）：该名字属于正版账号 {}",
                        name, profile.id(), knownPremium);
                disconnect(listener, Messages.get("premium.name"));
                return StartResult.disconnected();
            }
        }

        // 8) UUID 一致性
        if (record != null) {
            boolean uuidMatch = record.uuid != null && record.uuid.equals(profile.id().toString());
            boolean backupMatch = record.premiumBackup && record.offlineUuid != null
                    && record.offlineUuid.equals(profile.id().toString());
            if (!uuidMatch && !backupMatch) {
                PLogin.LOGGER.warn("[PLogin] 玩家 {} 的 UUID 与已注册记录不一致（记录 {} / 离线备份 {} / 当前 {}）",
                        name, record.uuid, record.offlineUuid, profile.id());
                disconnect(listener, Messages.get("conflict"));
                return StartResult.disconnected();
            }
        }

        // 9) 未注册且无需注册：直接放行
        if (record == null && !config.requireRegistration) {
            PLogin.LOGGER.info("[PLogin] 离线玩家 {} 未注册，但 requireRegistration=false，直接放行", name);
            welcomes.put(profile.id(), Welcome.NONE);
            return StartResult.skip();
        }

        State state = record == null ? State.PENDING_REGISTER : State.PENDING_LOGIN;
        Session session = new Session(listener, profile, ip, state);
        sessions.put(listener, session);
        PLogin.LOGGER.info("[PLogin] 配置阶段认证：{}（{}），状态={}，IP={}",
                name, profile.id(), state.label(), ip);
        return StartResult.wait(new AuthConfigurationTask(session));
    }

    // ------------------------------------------------------------------ 对话框回包

    /**
     * 处理 {@code ServerboundCustomClickActionPacket}。返回 true 表示已消费，调用方应取消原版处理。
     * 必须在服务端主线程调用（Mixin 已保证非主线程时交给原版 ensureRunningOnSameThread 重新调度）。
     */
    public boolean handleClick(ServerConfigurationPacketListenerImpl listener,
                               ServerboundCustomClickActionPacket packet) {
        Session session = sessions.get(listener);
        if (session == null) {
            return false;
        }
        Identifier id = packet.id();
        if (!"plogin".equals(id.getNamespace())) {
            return false;
        }
        switch (id.getPath()) {
            case "login" -> {
                session.submitLogin(payloadString(packet, "password"));
                return true;
            }
            case "register" -> {
                session.submitRegister(payloadString(packet, "password"),
                        payloadString(packet, "password_confirm"));
                return true;
            }
            case "disconnect" -> {
                session.exitFromDialog();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static String payloadString(ServerboundCustomClickActionPacket packet, String key) {
        Optional<Tag> payload = packet.payload();
        if (payload.isPresent() && payload.get() instanceof CompoundTag tag) {
            return tag.getStringOr(key, "");
        }
        return "";
    }

    // ------------------------------------------------------------------ 生命周期

    /** 连接监听器销毁时清理（配置阶段断开 / 认证成功切换 listener 后不会回调）。 */
    public void onListenerDisconnect(ServerCommonPacketListenerImpl listener) {
        Connection connection = connectionOf(listener);
        if (connection != null) {
            reconfiguring.remove(connection);
            TransferState.clear(connection);
        }
        if (listener instanceof ServerConfigurationPacketListenerImpl configListener) {
            Session session = sessions.remove(configListener);
            if (session != null) {
                session.closed = true;
            }
            welcomes.remove(configListener.getOwner().id());
        }
    }

    /** 玩家真正进入世界后补发欢迎标题（JOIN 事件）。 */
    public void onJoin(ServerPlayer player) {
        // 兼容 1.0.x 的 limbo 冻结标记：旧版本会把 Invulnerable/NoGravity 写进玩家数据，
        // 1.1.0 不再冻结玩家，这里做一次归一化，避免升级后一直无敌/悬浮。
        if (player.isInvulnerable()) {
            player.setInvulnerable(false);
        }
        if (player.isNoGravity() && !player.isSpectator()) {
            player.setNoGravity(false);
        }
        Welcome kind = welcomes.remove(player.getUUID());
        if (kind == null) {
            kind = fallbackWelcome(player);
        }
        String name = player.getGameProfile().name();
        switch (kind) {
            case PREMIUM -> {
                player.sendSystemMessage(Messages.get("welcome.premium", name));
                Ui.title(player,
                        Component.literal(Messages.raw("title.premium")).withStyle(ChatFormatting.GREEN),
                        Component.literal(Messages.raw("subtitle.premium")).withStyle(ChatFormatting.GRAY));
            }
            case BACKUP -> {
                player.sendSystemMessage(Messages.get("premium.backup.welcome", name));
                Ui.title(player,
                        Component.literal(Messages.raw("title.backup")).withStyle(ChatFormatting.GREEN),
                        Component.literal(Messages.raw("subtitle.backup")).withStyle(ChatFormatting.GRAY));
            }
            case LOGIN -> {
                player.sendSystemMessage(Messages.get("login.ok"));
                Ui.title(player,
                        Component.literal(Messages.raw("title.ok")).withStyle(ChatFormatting.GREEN),
                        Component.literal(Messages.raw("subtitle.ok")).withStyle(ChatFormatting.GRAY));
            }
            case REGISTER -> {
                player.sendSystemMessage(Messages.get("register.ok"));
                Ui.title(player,
                        Component.literal(Messages.raw("title.ok")).withStyle(ChatFormatting.GREEN),
                        Component.literal(Messages.raw("subtitle.ok")).withStyle(ChatFormatting.GRAY));
            }
            case NONE -> {
            }
        }
    }

    private Welcome fallbackWelcome(ServerPlayer player) {
        GameProfile profile = player.getGameProfile();
        Config config = PLogin.config();
        if (HybridAuth.isBackupLogin(profile) || HybridAuth.isTransferVerified(profile)) {
            return Welcome.BACKUP;
        }
        if (!HybridAuth.isOfflineProfile(profile) && !HybridAuth.isBackupLogin(profile)
                && config != null && config.premiumBypass) {
            return Welcome.PREMIUM;
        }
        return Welcome.NONE;
    }

    /** 服务端 tick：清理掉线会话、过期 IP 封禁与失败计数。 */
    public void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (!ipBans.isEmpty()) {
            ipBans.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        if (!sessions.isEmpty()) {
            sessions.entrySet().removeIf(entry -> {
                if (entry.getKey().isAcceptingMessages()) {
                    return false;
                }
                entry.getValue().closed = true;
                return true;
            });
        }
        sweepFailureTracking(now);
    }

    public Session session(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        for (Session session : sessions.values()) {
            if (uuid.equals(session.uuid)) {
                return session;
            }
        }
        return null;
    }

    public void markReconfiguring(ServerCommonPacketListenerImpl listener) {
        Connection connection = connectionOf(listener);
        if (connection != null) {
            reconfiguring.add(connection);
        }
    }

    private boolean consumeReconfiguring(Connection connection) {
        return reconfiguring.remove(connection);
    }

    // ------------------------------------------------------------------ 内部工具

    private static boolean treatOffline(GameProfile profile) {
        return HybridAuth.isOfflineProfile(profile) || HybridAuth.isBackupLogin(profile);
    }

    static Connection connectionOf(ServerCommonPacketListenerImpl listener) {
        try {
            return ((ServerCommonPacketListenerAccessor) listener).plogin$connection();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String ipOf(Connection connection) {
        try {
            SocketAddress address = connection.getRemoteAddress();
            if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
                return inet.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
            // 连接可能已经关闭
        }
        return "";
    }

    private static String toKey(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /** 配置阶段断开前先清掉对话框：客户端停在自定义屏幕上时，直接收 disconnect 会卡屏/无法操作。 */
    private static void disconnect(ServerConfigurationPacketListenerImpl listener, Component reason) {
        sendClearDialog(listener);
        disconnectNow(listener, reason);
    }

    private static void sendClearDialog(ServerConfigurationPacketListenerImpl listener) {
        try {
            listener.send(ClientboundClearDialogPacket.INSTANCE);
        } catch (Exception ignored) {
            // 连接可能已经关闭
        }
    }

    private static void disconnectNow(ServerConfigurationPacketListenerImpl listener, Component reason) {
        try {
            listener.disconnect(reason);
        } catch (Exception e) {
            PLogin.LOGGER.debug("[PLogin] 断开 {} 失败", listener.getOwner().name(), e);
        }
    }

    private long bumpFailure(Map<String, long[]> map, String key, long now, long windowMs) {
        long[] counters = map.get(key);
        if (counters == null) {
            counters = new long[2];
            map.put(key, counters);
        } else if (now - counters[1] > windowMs) {
            counters[0] = 0;
        }
        counters[1] = now;
        return ++counters[0];
    }

    private void sweepFailureTracking(long now) {
        if (now - lastFailureSweep < 30_000L) {
            return;
        }
        lastFailureSweep = now;
        Config config = PLogin.config();
        long windowMs = Math.max(60, config == null ? 1800 : config.failureWindowSeconds) * 1000L;
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

    // ------------------------------------------------------------------ 单连接会话

    /** 一条配置阶段连接上的认证会话。 */
    public final class Session {
        private final ServerConfigurationPacketListenerImpl listener;
        private final UUID uuid;
        private final String name;
        private final String ip;
        private final boolean backupLogin;
        private final long startedAt = System.currentTimeMillis();
        private volatile State state;
        private volatile boolean busy;
        private volatile boolean done;
        private volatile boolean closed;
        private volatile String error;
        private int attempts;

        private Session(ServerConfigurationPacketListenerImpl listener, GameProfile profile,
                        String ip, State state) {
            this.listener = listener;
            this.uuid = profile.id();
            this.name = profile.name();
            this.ip = ip;
            this.backupLogin = HybridAuth.isBackupLogin(profile);
            this.state = state;
        }

        /** 配置任务 start() 时调用：发送第一个对话框。 */
        public void showDialog() {
            if (closed || done) {
                return;
            }
            Config config = PLogin.config();
            if (config == null) {
                return;
            }
            Dialog dialog = state == State.PENDING_REGISTER
                    ? AuthDialogs.register(name, error, config.passwordMinLength,
                            config.passwordMaxLength, config.loginTimeoutSeconds)
                    : AuthDialogs.login(name, error, config.passwordMaxLength,
                            config.loginTimeoutSeconds);
            sendDialog(dialog);
        }

        private void sendDialog(Dialog dialog) {
            try {
                listener.send(new ClientboundShowDialogPacket(Holder.direct(dialog)));
            } catch (Exception e) {
                PLogin.LOGGER.warn("[PLogin] 向 {} 发送登录对话框失败", name, e);
            }
        }

        /** 配置任务 tick()：返回 true 表示认证结束，原版继续 PrepareSpawn/JoinWorld。 */
        public boolean tick() {
            if (done) {
                return true;
            }
            if (closed) {
                // 已经决定断开（超时/退出），在延迟断开完成前不要把任务推进到 PrepareSpawn
                return false;
            }
            Config config = PLogin.config();
            if (config == null) {
                closeWith(Messages.get("auth.internal_error"));
                return true;
            }
            if (!config.enabled) {
                // /plogin reload 把总开关关掉时，放行正在等待的玩家，恢复原版行为
                done = true;
                sessions.remove(listener, this);
                try {
                    listener.send(ClientboundClearDialogPacket.INSTANCE);
                } catch (Exception ignored) {
                    // 连接可能恰好断开
                }
                return true;
            }
            int timeout = config.loginTimeoutSeconds;
            if (timeout > 0 && (System.currentTimeMillis() - startedAt) / 1000L >= timeout) {
                if (config.kickOnTimeout) {
                    PLogin.LOGGER.info("[PLogin] 玩家 {} 登录超时", name);
                    closeWith(Messages.get("timeout"));
                }
                // kickOnTimeout=false：继续等待，不清空对话框
                return false;
            }
            return false;
        }

        public State state() {
            return state;
        }

        public String name() {
            return name;
        }

        // -------------------------------------------------------- 动作

        void submitLogin(String password) {
            if (closed || done || busy || state != State.PENDING_LOGIN) {
                return;
            }
            Config config = PLogin.config();
            UserStore users = PLogin.users();
            MinecraftServer server = PLogin.server();
            if (config == null || users == null || server == null) {
                return;
            }
            if (users.isCorrupted()) {
                error = Messages.raw("auth.corrupt");
                showDialog();
                return;
            }
            UserRecord record = users.find(name);
            if (record == null) {
                state = State.PENDING_REGISTER;
                error = Messages.raw("login.not_registered");
                showDialog();
                return;
            }
            if (password.isEmpty() || password.length() > config.passwordMaxLength) {
                // 不把超长/空密码送进 PBKDF2，避免恶意客户端用超大 payload 拖垮哈希线程
                onFailure();
                return;
            }
            busy = true;
            error = null;
            try {
                PLogin.executor().submit(() -> {
                    boolean ok = PasswordHasher.verify(password, record.salt, record.iterations, record.hash);
                    server.execute(() -> finishLogin(ok));
                });
            } catch (RejectedExecutionException e) {
                busy = false;
                error = Messages.raw("register.save_failed");
                showDialog();
            }
        }

        private void finishLogin(boolean ok) {
            if (closed || done || sessions.get(listener) != this) {
                return;
            }
            busy = false;
            if (!ok) {
                onFailure();
                return;
            }
            UserStore users = PLogin.users();
            MinecraftServer server = PLogin.server();
            if (users == null || server == null) {
                return;
            }
            // 哈希期间同名玩家可能上线：不再踢人，直接回到对话框提示（避免两个同名实体/屏幕）
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (other == null || !other.getName().getString().equalsIgnoreCase(name)) {
                    continue;
                }
                error = Messages.raw(backupLogin && !treatOffline(other.getGameProfile())
                        ? "premium.backup.premium_online" : "name.online");
                showDialog();
                return;
            }
            Config config = PLogin.config();
            UserRecord record = users.find(name);
            if (backupLogin && config != null && config.transferEnabled
                    && server.acceptsTransfers()
                    && record != null && record.premiumBackup && record.uuid != null) {
                UUID premiumUuid = null;
                try {
                    premiumUuid = UUID.fromString(record.uuid);
                } catch (IllegalArgumentException e) {
                    PLogin.LOGGER.warn("[PLogin] 离线备份记录中的正版 UUID 无效：{}", record.uuid);
                }
                if (premiumUuid != null) {
                    if (server.getPlayerList().getPlayer(premiumUuid) != null) {
                        error = Messages.raw("premium.backup.premium_online");
                        showDialog();
                        return;
                    }
                    String host = resolveTransferHost();
                    int port = resolveTransferPort();
                    if (host != null && port > 0) {
                        byte[] secret = TransferVerification.issue(name, premiumUuid, ip,
                                config.transferTokenSeconds, config.transferBindIp);
                        if (secret == null) {
                            error = Messages.raw("transfer.unavailable");
                            showDialog();
                            return;
                        }
                        users.touchLogin(name, ip);
                        startTransfer(host, port, secret);
                        return;
                    }
                    PLogin.LOGGER.warn("[PLogin] 无法确定 transfer 目标 host/port，{} 回退为离线 UUID 登录", name);
                }
            }
            users.touchLogin(name, ip);
            succeed(backupLogin ? Welcome.BACKUP : Welcome.LOGIN);
        }

        void submitRegister(String password, String confirm) {
            if (closed || done || busy || state != State.PENDING_REGISTER) {
                return;
            }
            Config config = PLogin.config();
            UserStore users = PLogin.users();
            MinecraftServer server = PLogin.server();
            if (config == null || users == null || server == null) {
                return;
            }
            if (users.isCorrupted()) {
                error = Messages.raw("auth.corrupt");
                showDialog();
                return;
            }
            if (password.length() < config.passwordMinLength || password.length() > config.passwordMaxLength) {
                error = Messages.raw("register.length", config.passwordMinLength, config.passwordMaxLength);
                showDialog();
                return;
            }
            if (!password.equals(confirm)) {
                error = Messages.raw("register.mismatch");
                showDialog();
                return;
            }
            if (password.equalsIgnoreCase(name)) {
                error = Messages.raw("register.same_name");
                showDialog();
                return;
            }
            UserRecord existing = users.find(name);
            if (existing != null) {
                state = State.PENDING_LOGIN;
                error = Messages.raw("register.exists");
                showDialog();
                return;
            }
            if (config.premiumNameProtection) {
                UUID knownPremium = users.premiumUuid(name);
                if (knownPremium != null && !knownPremium.equals(uuid)) {
                    error = Messages.raw("register.premium_name");
                    showDialog();
                    return;
                }
            }

            busy = true;
            error = null;
            int iterations = config.passwordIterations;
            try {
                PLogin.executor().submit(() -> {
                    String salt = PasswordHasher.newSalt();
                    String hash = PasswordHasher.hash(password, salt, iterations);
                    server.execute(() -> finishRegister(salt, hash, iterations));
                });
            } catch (RejectedExecutionException e) {
                busy = false;
                error = Messages.raw("register.save_failed");
                showDialog();
            }
        }

        private void finishRegister(String salt, String hash, int iterations) {
            if (closed || done || sessions.get(listener) != this) {
                return;
            }
            busy = false;
            UserStore users = PLogin.users();
            if (users == null) {
                return;
            }
            if (users.isCorrupted()) {
                error = Messages.raw("auth.corrupt");
                showDialog();
                return;
            }
            long now = System.currentTimeMillis();
            UserRecord record = new UserRecord(name, uuid.toString(), salt, hash, iterations, now, now, ip);
            boolean duplicate = users.find(name) != null;
            if (!users.register(record)) {
                error = Messages.raw(duplicate ? "register.exists" : "register.save_failed");
                if (duplicate) {
                    state = State.PENDING_LOGIN;
                }
                showDialog();
                return;
            }
            PLogin.LOGGER.info("[PLogin] 玩家 {} 在配置阶段注册成功（{}）", name, uuid);
            succeed(Welcome.REGISTER);
        }

        void exitFromDialog() {
            if (closed) {
                return;
            }
            PLogin.LOGGER.info("[PLogin] 玩家 {} 在登录界面选择了退出", name);
            closeWith(Messages.get("disconnect.closed"));
        }

        private void onFailure() {
            Config config = PLogin.config();
            if (config == null) {
                return;
            }
            attempts++;
            long now = System.currentTimeMillis();
            long windowMs = Math.max(60, config.failureWindowSeconds) * 1000L;

            long ipFailures = ip.isEmpty() ? 0 : bumpFailure(failuresByIp, ip, now, windowMs);
            if (config.ipBanSeconds > 0 && ipFailures >= config.maxLoginAttempts) {
                ipBans.put(ip, now + config.ipBanSeconds * 1000L);
                PLogin.LOGGER.warn("[PLogin] IP {} 在窗口内累计 {} 次密码错误，临时封禁 {} 秒",
                        ip, ipFailures, config.ipBanSeconds);
                closeWith(Messages.get("login.banned"));
                return;
            }

            long accountFailures = bumpFailure(failuresByAccount, toKey(name), now, windowMs);
            if (config.accountLockSeconds > 0 && accountFailures >= config.maxAccountFailures) {
                accountLocks.put(toKey(name), now + config.accountLockSeconds * 1000L);
                PLogin.LOGGER.warn("[PLogin] 账号 {} 在窗口内累计 {} 次密码错误，临时锁定 {} 秒",
                        name, accountFailures, config.accountLockSeconds);
                closeWith(Messages.get("login.account_locked", config.accountLockSeconds));
                return;
            }

            error = Messages.raw("login.wrong", Math.max(1, config.maxLoginAttempts - attempts));
            showDialog();
        }

        private void succeed(Welcome kind) {
            done = true;
            busy = false;
            state = State.AUTHENTICATED;
            welcomes.put(uuid, kind);
            // 认证完成后配置监听器会被游戏监听器替换，不会再回调 onDisconnect；
            // 这里主动移出会话表，避免为每个进入世界的玩家残留一条 Session。
            sessions.remove(listener, this);
            sendClearDialog(listener);
            PLogin.LOGGER.info("[PLogin] 玩家 {} 在配置阶段认证成功（{}）", name, kind.label());
        }

        /**
         * 断开当前对话框连接：先发 clear_dialog 关掉客户端自定义屏幕，隔 50ms 再断开。
         * 直接 disconnect 会让客户端停在对话框上无法操作，只能点客户端自带的断开按钮。
         */
        private void closeWith(Component reason) {
            if (closed) {
                return;
            }
            closed = true;
            sendClearDialog(listener);
            MinecraftServer server = PLogin.server();
            if (server == null) {
                disconnectNow(listener, reason);
                return;
            }
            try {
                CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> {
                    try {
                        server.execute(() -> disconnectNow(listener, reason));
                    } catch (Exception ignored) {
                        // 服务器已停止/连接已关闭
                    }
                });
            } catch (Exception e) {
                disconnectNow(listener, reason);
            }
        }

        private String resolveTransferHost() {
            Config config = PLogin.config();
            String host = config == null ? "" : config.transferHost;
            if (host == null || host.isBlank()) {
                TransferState.Target target = TransferState.target(connectionOf(listener));
                host = target == null ? null : target.host();
            }
            if (host == null) {
                return null;
            }
            host = host.split("\u0000", 2)[0].trim();
            return host.isEmpty() ? null : host;
        }

        private int resolveTransferPort() {
            Config config = PLogin.config();
            if (config != null && config.transferPort > 0) {
                return config.transferPort;
            }
            TransferState.Target target = TransferState.target(connectionOf(listener));
            if (target != null && target.port() > 0) {
                return target.port();
            }
            MinecraftServer server = PLogin.server();
            return server == null ? 0 : server.getPort();
        }

        /**
         * 备份密码验证通过后，把离线连接 transfer 到同一个服务端：
         * 先把一次性凭据写进客户端 Cookie，再清 dialog，50ms 后发 transfer 包。
         * 客户端会带着 Cookie 重连（handshake intention=TRANSFER），登录阶段换成正版 UUID/存档。
         */
        private void startTransfer(String host, int port, byte[] secret) {
            try {
                listener.send(new ClientboundStoreCookiePacket(TransferState.COOKIE_KEY, secret));
            } catch (Exception e) {
                PLogin.LOGGER.warn("[PLogin] 向 {} 下发 transfer Cookie 失败", name, e);
                error = Messages.raw("transfer.unavailable");
                showDialog();
                return;
            }
            closed = true;
            sessions.remove(listener, this);
            sendClearDialog(listener);
            MinecraftServer server = PLogin.server();
            if (server == null) {
                return;
            }
            PLogin.LOGGER.info("[PLogin] 玩家 {} 备份密码验证成功，准备 transfer 到 {}:{}（正版 UUID）",
                    name, host, port);
            try {
                CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> {
                    try {
                        server.execute(() -> {
                            try {
                                listener.send(new ClientboundTransferPacket(host, port));
                            } catch (Exception e) {
                                PLogin.LOGGER.warn("[PLogin] 发送 transfer 包失败", e);
                                disconnectNow(listener, Messages.get("transfer.failed"));
                            }
                        });
                    } catch (Exception ignored) {
                        // 服务器已停止/连接已关闭
                    }
                });
            } catch (Exception e) {
                PLogin.LOGGER.warn("[PLogin] 调度 transfer 失败", e);
                disconnectNow(listener, Messages.get("transfer.failed"));
            }
        }
    }
}

