package com.pimeng.plogin.auth;

import com.pimeng.plogin.PLogin;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 离线备份密码验证通过后的短时一次性重连凭据。
 *
 * 旧实现只按用户名发放令牌：任何同名的第二条离线连接都能抢先消费令牌，
 * 顶替真正输对密码的客户端拿到正版 UUID（身份冒用）。
 *
 * 现在凭据是一个 16 字节 SecureRandom 随机数，只有真正输对密码的那个客户端拿得到：
 * <ul>
 *   <li>密码验证成功时生成，经 Cookie 通道（{@code ClientboundStoreCookiePacket}）下发给客户端；</li>
 *   <li>客户端重连时，服务端在登录阶段发送 {@code ClientboundCookieRequestPacket}，
 *       客户端经 {@code ServerboundCookieResponsePacket} 带回该随机数（见 BackupLoginState）；</li>
 *   <li>消费时凭据必须精确匹配、未过期、用户名一致，且（默认）要求 IP 一致；一次性使用。</li>
 * </ul>
 * 没有 Cookie 的连接停留在“待验证”状态，只能重新输入密码，无法冒领正版 UUID。
 */
final class BackupVerification {
    /** 凭据有效期（毫秒）。 */
    static final long TTL_MS = 120_000L;

    private record Pending(String name, String ip, long expireAt) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of().withLowerCase();
    private static final int SECRET_BYTES = 16;
    /** 凭据数量上限：超过时不再发放，要求玩家稍后重新 /login，防止内存被刷爆。 */
    private static final int MAX_PENDING = 4096;
    private static final Map<String, Pending> TOKENS = new ConcurrentHashMap<>();

    private BackupVerification() {
    }

    /**
     * 在备份密码验证成功时发放一次性凭据。
     *
     * @return 下发给客户端的随机数；系统过载返回 {@code null}，调用方必须让玩家重新 /login。
     */
    static byte[] issue(String name, String ip) {
        cleanup();
        if (TOKENS.size() >= MAX_PENDING) {
            return null;
        }
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        TOKENS.put(HEX.formatHex(secret), new Pending(
                name == null ? "" : name.toLowerCase(Locale.ROOT),
                ip == null ? "" : ip,
                System.currentTimeMillis() + TTL_MS));
        return secret;
    }

    /**
     * 一次性消费凭据：cookie 必须精确匹配、未过期、用户名一致；IP 绑定由 {@code requireIpMatch} 决定。
     * 只要 secret 匹配（不管后续校验是否通过）都会销毁，防止重放。
     */
    static boolean consume(String name, String ip, byte[] cookie, boolean requireIpMatch) {
        if (name == null || cookie == null || cookie.length != SECRET_BYTES) {
            return false;
        }
        cleanup();
        Pending pending = TOKENS.remove(HEX.formatHex(cookie));
        if (pending == null) {
            return false;
        }
        if (pending.expireAt() <= System.currentTimeMillis()) {
            PLogin.LOGGER.info("[PLogin] 备份重连凭据已过期: {} ({})", name, ip);
            return false;
        }
        if (!pending.name().equals(name.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (requireIpMatch && !pending.ip().isEmpty() && !pending.ip().equals(ip == null ? "" : ip)) {
            PLogin.LOGGER.warn("[PLogin] 备份重连凭据的 IP 与验证时不一致: 发放={} 当前={}", pending.ip(), ip);
            return false;
        }
        return true;
    }

    static void clear() {
        TOKENS.clear();
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        TOKENS.values().removeIf(pending -> pending.expireAt() <= now);
    }
}
