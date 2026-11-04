package com.pimeng.plogin.auth;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当前在正常玩家视角中“隐藏”的未登录玩家。
 *
 * - 进入 limbo 时 mark；
 * - 登录成功/requireRegistration=false 放行时 reveal；
 * - 未登录断线时保留到 quit 消息被吞掉后再 clear；
 * - /logout 会重新 mark。
 */
public final class HiddenPlayers {
    private static final Set<UUID> HIDDEN = ConcurrentHashMap.newKeySet();

    private HiddenPlayers() {
    }

    public static void mark(UUID uuid) {
        if (uuid != null) {
            HIDDEN.add(uuid);
        }
    }

    /** @return 之前是否处于隐藏状态 */
    public static boolean reveal(UUID uuid) {
        return uuid != null && HIDDEN.remove(uuid);
    }

    public static boolean isHidden(UUID uuid) {
        return uuid != null && HIDDEN.contains(uuid);
    }

    public static boolean consume(UUID uuid) {
        return uuid != null && HIDDEN.remove(uuid);
    }

    public static void clear(UUID uuid) {
        HIDDEN.remove(uuid);
    }
}
