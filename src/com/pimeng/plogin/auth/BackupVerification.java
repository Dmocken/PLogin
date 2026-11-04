package com.pimeng.plogin.auth;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 离线备份密码验证通过后的短时令牌。
 *
 * 验证通过时先踢掉正版同名玩家，然后让离线客户端断开重连；
 * 重连时 HybridAuth 凭这个令牌把 UUID 切到正版 UUID，避免在待验证阶段
 * 就因为同 UUID 被原版 PlayerList 提前踢掉正版玩家。
 */
final class BackupVerification {
    private static final long TTL_MS = 120_000L;
    private static final Map<String, Long> TOKENS = new ConcurrentHashMap<>();

    private BackupVerification() {
    }

    static void mark(String name, String ip) {
        cleanup();
        // 令牌只按名字绑定：客户端重连时源 IP 可能变化/为空，短 TTL 已足够。
        TOKENS.put(key(name), System.currentTimeMillis() + TTL_MS);
    }

    static boolean consume(String name, String ip) {
        cleanup();
        Long until = TOKENS.remove(key(name));
        return until != null && until > System.currentTimeMillis();
    }

    static void clear(String name) {
        if (name == null) {
            return;
        }
        TOKENS.remove(name.toLowerCase(Locale.ROOT));
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        TOKENS.entrySet().removeIf(e -> e.getValue() <= now);
    }
}
