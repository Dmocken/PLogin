package com.pimeng.plogin.auth;

import com.pimeng.plogin.PLogin;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 备份密码通过后发放的一次性 transfer 凭据。
 *
 * <p>同一个服务端的 transfer 不需要 JWE：随机 16 字节 secret 经原版 Cookie 通道
 * 下发给客户端，客户端 transfer 重连时再带回；服务端按 secret 找到这次转移对应的
 * 正版 UUID。凭据一次性、短时、默认绑定 IP。
 */
final class TransferVerification {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of().withLowerCase();
    private static final int SECRET_BYTES = 16;
    private static final int MAX_PENDING = 4096;
    private static final Map<String, Pending> TOKENS = new ConcurrentHashMap<>();

    record Pending(String name, UUID premiumUuid, String ip, boolean bindIp, long expireAt) {
    }

    private TransferVerification() {
    }

    static byte[] issue(String name, UUID premiumUuid, String ip, int ttlSeconds, boolean bindIp) {
        cleanup();
        if (TOKENS.size() >= MAX_PENDING) {
            return null;
        }
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        TOKENS.put(HEX.formatHex(secret), new Pending(
                name == null ? "" : name.toLowerCase(Locale.ROOT),
                premiumUuid,
                ip == null ? "" : ip,
                bindIp,
                System.currentTimeMillis() + Math.max(10, ttlSeconds) * 1000L));
        return secret;
    }

    static Pending consume(byte[] secret, String name, String ip) {
        if (secret == null || secret.length != SECRET_BYTES) {
            return null;
        }
        cleanup();
        Pending pending = TOKENS.remove(HEX.formatHex(secret));
        if (pending == null || pending.expireAt() <= System.currentTimeMillis()) {
            return null;
        }
        if (!pending.name().equals(name == null ? "" : name.toLowerCase(Locale.ROOT))) {
            PLogin.LOGGER.warn("[PLogin] transfer 凭据的用户名不匹配：发放={}，当前={}", pending.name(), name);
            return null;
        }
        if (pending.bindIp() && !pending.ip().isEmpty()
                && !pending.ip().equals(ip == null ? "" : ip)) {
            PLogin.LOGGER.warn("[PLogin] transfer 凭据的 IP 与发放时不一致：发放={}，当前={}", pending.ip(), ip);
            return null;
        }
        return pending;
    }

    static void clear() {
        TOKENS.clear();
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        TOKENS.values().removeIf(pending -> pending.expireAt() <= now);
    }
}
