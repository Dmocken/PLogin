package com.pimeng.plogin.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pimeng.plogin.PLogin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 离线账号与"已知正版用户名"的持久化存储。
 * users.json      : 离线玩家注册信息（按小写名字索引）
 * premium-names.json : 成功通过正版验证的名字 -> 正版 UUID（用于防止冒名）
 */
public final class UserStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path usersFile;
    private final Path premiumNamesFile;
    private final Map<String, UserRecord> users = new ConcurrentHashMap<>();
    private final Map<String, String> premiumNames = new ConcurrentHashMap<>();

    public UserStore(Path dataDir) {
        this.usersFile = dataDir.resolve("users.json");
        this.premiumNamesFile = dataDir.resolve("premium-names.json");
    }

    public void load() {
        synchronized (this) {
            users.clear();
            premiumNames.clear();
            try {
                Files.createDirectories(usersFile.getParent());
                if (Files.exists(usersFile)) {
                    JsonObject root = JsonParser.parseString(Files.readString(usersFile, StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject list = root.has("users") ? root.getAsJsonObject("users") : new JsonObject();
                    for (String key : list.keySet()) {
                        UserRecord record = GSON.fromJson(list.get(key), UserRecord.class);
                        if (record != null && record.name != null && record.uuid != null) {
                            users.put(key.toLowerCase(Locale.ROOT), record);
                        }
                    }
                }
                if (Files.exists(premiumNamesFile)) {
                    JsonObject root = JsonParser.parseString(Files.readString(premiumNamesFile, StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject names = root.has("names") ? root.getAsJsonObject("names") : new JsonObject();
                    for (String key : names.keySet()) {
                        premiumNames.put(key.toLowerCase(Locale.ROOT), names.get(key).getAsString());
                    }
                }
                PLogin.LOGGER.info("[PLogin] 已加载 {} 个离线账号、{} 个正版用户名", users.size(), premiumNames.size());
            } catch (Exception e) {
                PLogin.LOGGER.error("[PLogin] 读取账号数据失败", e);
            }
        }
    }

    public synchronized UserRecord find(String name) {
        if (name == null) {
            return null;
        }
        return users.get(name.toLowerCase(Locale.ROOT));
    }

    public synchronized boolean register(UserRecord record) {
        String key = record.name.toLowerCase(Locale.ROOT);
        if (users.containsKey(key)) {
            return false;
        }
        // 是否允许离线注册正版名由 AuthManager 按 premiumNameProtection 决定；
        // 数据层不再拦截，否则 protection=false 时会出现“注册提示已注册、登录提示未注册”的死循环。
        users.put(key, record);
        saveUsersLocked();
        return true;
    }

    public synchronized boolean remove(String name) {
        if (name == null) {
            return false;
        }
        boolean removed = users.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            saveUsersLocked();
        }
        return removed;
    }

    public synchronized boolean hasPremiumBackup(String name) {
        if (name == null) {
            return false;
        }
        UserRecord record = users.get(name.toLowerCase(Locale.ROOT));
        return record != null && record.premiumBackup && record.offlineUuid != null;
    }

    /**
     * 正版玩家设置/更新离线备份密码。
     * 如果之前存在同名的普通离线记录（被抢注），这里会覆盖为正版备份记录。
     */
    public synchronized boolean setPremiumBackup(String name, String premiumUuid, String offlineUuid,
                                                String salt, String hash, int iterations, long now, String ip) {
        String key = name.toLowerCase(Locale.ROOT);
        UserRecord old = users.get(key);
        UserRecord record = new UserRecord(name, premiumUuid, salt, hash, iterations,
                old == null ? now : old.createdAt, now, ip);
        record.premiumBackup = true;
        record.offlineUuid = offlineUuid;
        users.put(key, record);
        saveUsersLocked();
        return true;
    }

    public synchronized boolean setPassword(String name, String salt, String hash, int iterations) {
        UserRecord record = users.get(name.toLowerCase(Locale.ROOT));
        if (record == null) {
            return false;
        }
        record.salt = salt;
        record.hash = hash;
        record.iterations = iterations;
        saveUsersLocked();
        return true;
    }

    public synchronized void touchLogin(String name, String ip) {
        UserRecord record = users.get(name.toLowerCase(Locale.ROOT));
        if (record != null) {
            record.lastLoginAt = System.currentTimeMillis();
            record.lastIp = ip;
            saveUsersLocked();
        }
    }

    public synchronized UUID premiumUuid(String name) {
        String uuid = premiumNames.get(name.toLowerCase(Locale.ROOT));
        if (uuid == null) {
            return null;
        }
        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 记录一个通过 Mojang hasJoined 验证的正版用户名。
     * 可能由登录线程调用，因此内部同步写文件。
     */
    public synchronized void rememberPremium(String name, UUID id) {
        String key = name.toLowerCase(Locale.ROOT);
        String value = id.toString();
        String old = premiumNames.put(key, value);
        if (!value.equals(old)) {
            savePremiumNamesLocked();
        }
    }

    public synchronized int userCount() {
        return users.size();
    }

    public synchronized void saveAll() {
        saveUsersLocked();
        savePremiumNamesLocked();
    }

    private void saveUsersLocked() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonObject list = new JsonObject();
            for (Map.Entry<String, UserRecord> entry : users.entrySet()) {
                list.add(entry.getKey(), GSON.toJsonTree(entry.getValue()));
            }
            root.add("users", list);
            writeAtomic(usersFile, GSON.toJson(root));
        } catch (Exception e) {
            PLogin.LOGGER.error("[PLogin] 保存 users.json 失败", e);
        }
    }

    private void savePremiumNamesLocked() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonObject names = new JsonObject();
            for (Map.Entry<String, String> entry : premiumNames.entrySet()) {
                names.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("names", names);
            writeAtomic(premiumNamesFile, GSON.toJson(root));
        } catch (Exception e) {
            PLogin.LOGGER.error("[PLogin] 保存 premium-names.json 失败", e);
        }
    }

    private static void writeAtomic(Path target, String content) throws Exception {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
