package com.pimeng.plogin.auth;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.resources.Identifier;

/**
 * transfer 相关的连接级状态：
 * <ul>
 *   <li>登录阶段等待客户端返回的 transfer Cookie（按 Connection 隔离）；</li>
 *   <li>客户端连接时使用的 host（由 handshake 记录），用于 transfer 回自己。</li>
 * </ul>
 */
public final class TransferState {
    /** transfer 凭据使用的原版 Cookie key。 */
    public static final Identifier COOKIE_KEY = Identifier.fromNamespaceAndPath("plogin", "transfer");

    private static final Map<Connection, byte[]> RESPONSES = new ConcurrentHashMap<>();
    private static final Map<Connection, Target> TARGETS = Collections.synchronizedMap(new WeakHashMap<>());

    /** 客户端连接时使用的 host/port（transfer 回自己时优先使用）。 */
    public record Target(String host, int port) {
    }

    private TransferState() {
    }

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

    public static void store(Connection connection, byte[] payload) {
        if (connection != null) {
            RESPONSES.put(connection, payload == null ? new byte[0] : payload);
        }
    }

    public static void clear(Connection connection) {
        if (connection != null) {
            RESPONSES.remove(connection);
            TARGETS.remove(connection);
        }
    }

    /** 登录阶段向客户端索要 transfer Cookie，并等待本连接的响应。 */
    public static byte[] requestAndWait(Connection connection, int waitMs) {
        if (connection == null) {
            return null;
        }
        RESPONSES.remove(connection);
        try {
            connection.send(new ClientboundCookieRequestPacket(COOKIE_KEY));
        } catch (Exception e) {
            return null;
        }
        long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
        byte[] payload = RESPONSES.get(connection);
        while (payload == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            payload = RESPONSES.get(connection);
        }
        return RESPONSES.remove(connection);
    }

    /** 登录连接的实际远端 IP（hasJoinedServer 的 address 参数在 prevent-proxy-connections=false 时为 null）。 */
    public static String remoteIp(Connection connection) {
        if (connection == null) {
            return "";
        }
        try {
            java.net.SocketAddress address = connection.getRemoteAddress();
            if (address instanceof java.net.InetSocketAddress inet && inet.getAddress() != null) {
                return inet.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
            // 连接可能已经关闭
        }
        return "";
    }

    public static void setTarget(Connection connection, String host, int port) {
        if (connection == null || host == null) {
            return;
        }
        String clean = host.split("\u0000", 2)[0].trim();
        if (!clean.isEmpty()) {
            TARGETS.put(connection, new Target(clean, port));
        }
    }

    public static Target target(Connection connection) {
        return connection == null ? null : TARGETS.get(connection);
    }
}
