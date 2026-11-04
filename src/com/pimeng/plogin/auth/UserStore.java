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
 *
 * 安全约定（1.0.1 修复）：
 * - 读取失败/JSON 损坏时进入 {@link #isCorrupted()} 状态：调用方（AuthManager）拒绝
 *   离线注册/登录，防止在“空账号库”上接受新注册导致账号抢注；正版验证不受影响。
 * - 保存失败时内存中的变更回滚，写方法返回 false；调用方不得把失败当成功报给玩家。
 */
public final class UserStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path usersFile;
    private final Path premiumNamesFile;
    private final Map<String, UserRecord> users = new ConcurrentHashMap<>();
    private final Map<String, String> premiumNames = new ConcurrentHashMap<>();
    private volatile boolean corrupted;

    public UserStore(Path dataDir) {
        this.usersFile = dataDir.resolve("users.json");
        this.premiumNamesFile = dataDir.resolve("premium-names.json");
    }

    /**
     * 读取账号数据。返回 false 表示数据存在但无法解析，账号库置空并进入 corrupted 状态。
     */
    public boolean load() {
        synchronized (this) {
            Map<String, UserRecord> parsedUsers = new ConcurrentHashMap<>();
            Map<String, String> parsedNames = new ConcurrentHashMap<>();
            boolean failed = false;
            try {
                Files.createDirectories(usersFile.getParent());
                failed |= parseUsers(parsedUsers);
                failed |= parsePremiumNames(parsedNames);
            } catch (Exception e) {
                PLogin.LOGGER.error("[PLogin] 初始化账号数据目录失败", e);
                failed = true;
            }
            if (failed) {
                // 保持内存为“空 + 拒绝写入”，不能带着半套数据继续认证。
                users.clear();
                premiumNames.clear();
                corrupted = true;
                PLogin.LOGGER.error("[PLogin] 账号数据损坏（{} / {}）。已停用离线注册与登录，"
                                + "请修复文件后重启服务器；正版验证不受影响。",
                        usersFile.toAbsolutePath(), premiumNamesFile.toAbsolutePath());
                return false;
            }
            users.putAll(parsedUsers);
            premiumNames.putAll(parsedNames);
            corrupted = false;
            PLogin.LOGGER.info("[PLogin] 已加载 {} 个离线账号、{} 个正版用户名", users.size(), premiumNames.size());
            return true;
        }
    }

    private boolean parseUsers(Map<String, UserRecord> target) throws Exception {
        if (!Files.exists(usersFile)) {
            return false;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(usersFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject list = root.has("users") ? root.getAsJsonObject("users") : new JsonObject();
            for (String key : list.keySet()) {
                UserRecord record = GSON.fromJson(list.get(key), UserRecord.class);
                if (record != null && record.name != null && record.uuid != null) {
                    target.put(key.toLowerCase(Locale.ROOT), record);
                }
            }
            return false;
        } catch (Exception e) {
            PLogin.LOGGER.error("[PLogin] 解析 {} 失败", usersFile, e);
            return true;
        }
    }

    private boolean parsePremiumNames(Map<String, String> target) throws Exception {
        if (!Files.exists(premiumNamesFile)) {
            return false;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(premiumNamesFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject names = root.has("names") ? root.getAsJsonObject("names") : new JsonObject();
            for (String key : names.keySet()) {
                if (names.get(key).isJsonPrimitive()) {
                    target.put(key.toLowerCase(Locale.ROOT), names.get(key).getAsString());
                }
            }
            return false;
        } catch (Exception e) {
            PLogin.LOGGER.error("[PLogin] 解析 {} 失败", premiumNamesFile, e);
            return true;
        }
    }

    /** true 表示数据文件损坏，所有写操作被拒绝、离线认证被上游拒绝。 */
    public boolean isCorrupted() {
        return corrupted;
    }

    public synchronized UserRecord find(String name) {
        if (name == null) {
            return null;
        }
        return users.get(name.toLowerCase(Locale.ROOT));
    }

    public synchronized boolean exists(String name) {
        return name != null && users.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** 注册成功与否都如实返回；保存失败时不占用用户名。 */
    public synchronized boolean register(UserRecord record) {
        if (corrupted) {
            return false;
        }
        String key = record.name.toLowerCase(Locale.ROOT);
        if (users.containsKey(key)) {
            return false;
        }
        // 是否允许离线注册正版名由 AuthManager 按 premiumNameProtection 决定；
        // 数据层不再拦截，否则 protection=false 时会出现“注册提示已注册、登录提示未注册”的死循环。
        users.put(key, record);
        if (!saveUsersLocked()) {
            users.remove(key); // 回滚，失败不能对外表现为注册成功
            return false;
        }
        return true;
    }

    public synchronized boolean remove(String name) {
        if (name == null || corrupted) {
            return false;
        }
        String key = name.toLowerCase(Locale.ROOT);
        UserRecord old = users.get(key);
        boolean removed = users.remove(key) != null;
        if (removed) {
            if (!saveUsersLocked()) {
                users.put(key, old); // 回滚
                return false;
            }
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
        if (corrupted) {
            return false;
        }
        String key = name.toLowerCase(Locale.ROOT);
        UserRecord old = users.get(key);
        UserRecord record = new UserRecord(name, premiumUuid, salt, hash, iterations,
                old == null ? now : old.createdAt, now, ip);
        record.premiumBackup = true;
        record.offlineUuid = offlineUuid;
        users.put(key, record);
        if (!saveUsersLocked()) {
            if (old == null) {
                users.remove(key);
            } else {
                users.put(key, old); // 回滚
            }
            return false;
        }
        return true;
    }

    public synchronized boolean setPassword(String name, String salt, String hash, int iterations) {
        if (corrupted) {
            return false;
        }
        UserRecord record = users.get(name.toLowerCase(Locale.ROOT));
        if (record == null) {
            return false;
        }
        String oldSalt = record.salt;
        String oldHash = record.hash;
        int oldIterations = record.iterations;
        record.salt = salt;
        record.hash = hash;
        record.iterations = iterations;
        if (!saveUsersLocked()) {
            record.salt = oldSalt; // 回滚
            record.hash = oldHash;
            record.iterations = oldIterations;
            return false;
        }
        return true;
    }

    public synchronized void touchLogin(String name, String ip) {
        if (corrupted) {
            return;
        }
        UserRecord record = users.get(name.toLowerCase(Locale.ROOT));
        if (record != null) {
            record.lastLoginAt = System.currentTimeMillis();
            record.lastIp = ip;
            // 登录时间戳是可再生的元数据，保存失败不影响登录结果，
            // 下次成功请求（touch/save 自动跟随写请求）会补回。
            if (!saveUsersLocked()) {
                PLogin.LOGGER.debug("[PLogin] 只影响 lastLoginAt 的保存失败（可忽略）");
            }
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
        if (corrupted) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        String value = id.toString();
        String old = premiumNames.put(key, value);
        if (!value.equals(old)) {
            if (savePremiumNamesLocked()) {
                return;
            }
            // 正版名记录是安全加固数据：写盘失败回滚并告警，登录流程继续。
            if (old == null) {
                premiumNames.remove(key);
            } else {
                premiumNames.put(key, old);
            }
            PLogin.LOGGER.warn("[PLogin] premium-names.json 写盘失败，正版名记录 {} 回滚", key);
        }
    }

    public synchronized int userCount() {
        return users.size();
    }

    public synchronized void saveAll() {
        saveUsersLocked();
        savePremiumNamesLocked();
    }

    private boolean saveUsersLocked() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonObject list = new JsonObject();
            for (Map.Entry<String, UserRecord> entry : users.entrySet()) {
                list.add(entry.getKey(), GSON.toJsonTree(entry.getValue()));
            }
            root.add("users", list);
            writeAtomic(usersFile, GSON.toJson(root));
            return true;
        } catch (Exception e) {
            PLogin.LOGGER.error("[PLogin] 保存 users.json 失败", e);
            return false;
        }
    }

    private boolean savePremiumNamesLocked() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonObject names = new JsonObject();
            for (Map.Entry<String, String> entry : premiumNames.entrySet()) {
                names.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("names", names);
            writeAtomic(premiumNamesFile, GSON.toJson(root));
            return true;
        } catch (Exception e) {
            PLogin.LOGGER.error("[PLogin] 保存 premium-names.json 失败", e);
            return false;
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
