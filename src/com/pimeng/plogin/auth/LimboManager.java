package com.pimeng.plogin.auth;

import com.pimeng.plogin.PLogin;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;

/**
 * 未登录玩家隔离在 plogin:limbo 虚空维度。
 *
 * 关键点：在 PlayerList.placeNewPlayer 的 HEAD 阶段、客户端 Login 包发出之前
 * 把玩家切到 limbo，所以客户端一开始收到的坐标就是 limbo 的 0,65,0，
 * 不会暴露主世界的真实坐标。登录成功后再传送回原来的世界/位置。
 */
public final class LimboManager {
    public static final ResourceKey<Level> LIMBO = ResourceKey.create(
            Registries.DIMENSION, Identifier.fromNamespaceAndPath("plogin", "limbo"));

    private static final Map<UUID, Location> ORIGINS = new ConcurrentHashMap<>();
    private static boolean warnedMissing;

    private LimboManager() {
    }

    private record Location(ServerLevel level, double x, double y, double z, float yRot, float xRot) {
    }

    /** 在玩家正式进入 PlayerList 之前调用。返回 true 表示已切到 limbo。 */
    public static boolean prepare(ServerPlayer player) {
        HiddenPlayers.mark(player.getUUID());
        MinecraftServer server = player.level().getServer();
        ServerLevel limbo = server.getLevel(LIMBO);
        if (limbo == null) {
            if (!warnedMissing) {
                warnedMissing = true;
                PLogin.LOGGER.warn("[PLogin] 找不到 plogin:limbo 维度，未登录玩家将留在主世界");
            }
            return false;
        }

        ServerLevel originLevel = player.level();
        double ox = player.getX();
        double oy = player.getY();
        double oz = player.getZ();
        float oyRot = player.getYRot();
        float oxRot = player.getXRot();

        // 上一次未登录就断线时，位置可能已经保存在 limbo；这种情况回主世界出生点。
        if (originLevel == limbo) {
            ServerLevel overworld = server.overworld();
            net.minecraft.world.level.storage.LevelData.RespawnData respawn = overworld.getRespawnData();
            BlockPos spawn = respawn.pos();
            originLevel = overworld;
            ox = spawn.getX() + 0.5;
            oy = spawn.getY();
            oz = spawn.getZ() + 0.5;
            oyRot = respawn.yaw();
            oxRot = 0.0f;
        }

        ORIGINS.put(player.getUUID(), new Location(originLevel, ox, oy, oz, oyRot, oxRot));

        // 不能调用 teleportTo：placeNewPlayer 之前 connection 还是 null，会 NPE。
        player.setServerLevel(limbo);
        player.setPos(0.5, 65.0, 0.5);
        player.setYRot(0.0f);
        player.setXRot(0.0f);
        player.setNoGravity(true);
        player.setInvulnerable(true);
        PLogin.LOGGER.info("[PLogin] 未登录玩家 {} 已隔离到 plogin:limbo (0.5, 65, 0.5)，原位置已隐藏",
                player.getGameProfile().name());
        return true;
    }

    /** 登录成功后传回原世界/原位置。 */
    public static boolean returnToReal(ServerPlayer player) {
        Location location = ORIGINS.remove(player.getUUID());
        if (location == null) {
            return false;
        }
        player.teleportTo(location.level, location.x, location.y, location.z,
                Set.<Relative>of(), location.yRot, location.xRot, false);
        PLogin.LOGGER.info("[PLogin] 玩家 {} 登录成功，已从 limbo 传回 {} ({}, {}, {})",
                player.getGameProfile().name(), location.level.dimension().identifier(),
                location.x, location.y, location.z);
        return true;
    }

    /**
     * 未登录玩家断线时，在保存 playerdata 之前把位置恢复回原世界，
     * 避免 limbo 的 0,65,0 覆盖真实位置。
     */
    public static void restoreBeforeSave(ServerPlayer player) {
        Location location = ORIGINS.remove(player.getUUID());
        if (location == null) {
            return;
        }
        player.setServerLevel(location.level);
        player.setPos(location.x, location.y, location.z);
        player.setYRot(location.yRot);
        player.setXRot(location.xRot);
    }

    public static boolean isInLimbo(UUID uuid) {
        return ORIGINS.containsKey(uuid);
    }

    public static void clear(UUID uuid) {
        ORIGINS.remove(uuid);
    }
}
