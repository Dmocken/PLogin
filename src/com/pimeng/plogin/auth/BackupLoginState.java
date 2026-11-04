package com.pimeng.plogin.auth;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;

/**
 * 登录阶段的一次性 Cookie 凭据交换。
 *
 * 验证线程凭这条连接发送 {@link ClientboundCookieRequestPacket}，然后等待本连接的
 * {@code ServerboundCookieResponsePacket}。响应按 Connection 隔离存储（由 LoginCookieMixin
 * 捕获），所以同名的第二条连接无法用它窃取或覆盖另一个客户端的凭据。
 */
public final class BackupLoginState {
    private static final Map<Connection, byte[]> RESPONSES = new ConcurrentHashMap<>();

    private BackupLoginState() {
    }

    /**
     * 从登录监听器取底层连接。{@code ServerLoginPacketListenerImpl} 的 connection 字段
     * 名单固定（本项目直接按 Mojang mapped 名运行），这里用反射避免 Mixin accessor。
     */
    public static Connection connectionOf(Object listener) {
        if (listener == null) {
            return null;
        }
        try {
            Field field = listener.getClass().getDeclaredField("connection");
            field.setAccessible(true);
            return (Connection) field.get(listener);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** LoginCookieMixin 捕获到响应时写入；按 Connection 隔离。 */
    public static void store(Connection connection, byte[] payload) {
        if (connection == null) {
            return;
        }
        RESPONSES.put(connection, payload == null ? new byte[0] : payload);
    }

    /** 连接断开 / 登录监听器销毁时清理，防止残留。 */
    public static void clear(Connection connection) {
        if (connection == null) {
            return;
        }
        RESPONSES.remove(connection);
    }

    /**
     * 发送 Cookie 请求并等待本次连接的响应。
     *
     * @param waitMs 最多等待毫秒数；超时返回 {@code null}（按“没有凭据”处理）。
     */
    public static byte[] requestAndWait(Connection connection, long waitMs) {
        if (connection == null) {
            return null;
        }
        RESPONSES.remove(connection);
        connection.send(new ClientboundCookieRequestPacket(HybridAuth.BACKUP_COOKIE_KEY));
        long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
        byte[] payload = RESPONSES.get(connection);
        while (payload == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RESPONSES.remove(connection);
            }
            payload = RESPONSES.get(connection);
        }
        return RESPONSES.remove(connection);
    }
}
