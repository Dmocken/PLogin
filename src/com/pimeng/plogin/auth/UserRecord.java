package com.pimeng.plogin.auth;

/**
 * users.json 中的一条离线账号记录。
 */
public final class UserRecord {
    public String name;
    public String uuid;
    public String salt;
    public String hash;
    public int iterations;
    public long createdAt;
    public long lastLoginAt;
    public String lastIp;
    /** true = 正版玩家设置的离线备份密码；uuid 存正版 UUID，offlineUuid 存离线 UUID。 */
    public boolean premiumBackup;
    /** 正版账号对应的 OfflinePlayer:<name> UUID，离线备份登录时使用。 */
    public String offlineUuid;

    public UserRecord() {
    }

    public UserRecord(String name, String uuid, String salt, String hash, int iterations, long createdAt, long lastLoginAt, String lastIp) {
        this.name = name;
        this.uuid = uuid;
        this.salt = salt;
        this.hash = hash;
        this.iterations = iterations;
        this.createdAt = createdAt;
        this.lastLoginAt = lastLoginAt;
        this.lastIp = lastIp;
    }
}
