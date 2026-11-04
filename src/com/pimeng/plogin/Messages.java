package com.pimeng.plogin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.network.chat.Component;

/**
 * 服务端发送的字面量消息（不依赖客户端语言文件，客户端不装模组也能看懂）。
 */
public final class Messages {
    private static final String PREFIX = "§b[PLogin] §r";
    private static final Map<String, String> ZH = new HashMap<>();
    private static final Map<String, String> EN = new HashMap<>();
    private static volatile boolean chinese = true;

    static {
        // ---------------- 中文 ----------------
        ZH.put("welcome.register", "§f欢迎 §e%s§f！请先注册：§e/register <密码> <确认密码>§f，注册成功会自动登录。");
        ZH.put("welcome.login", "§f欢迎回来 §e%s§f！请登录：§e/login <密码>");
        ZH.put("welcome.premium", "§a正版验证通过§f，欢迎回来，§e%s§f！");
        ZH.put("title.register", "§e请先注册");
        ZH.put("title.login", "§e请先登录");
        ZH.put("title.ok", "§a登录成功");
        ZH.put("title.premium", "正版验证成功，欢迎");
        ZH.put("subtitle.premium", "已自动登录");
        ZH.put("subtitle.register", "§7使用 /register <密码> <确认密码>");
        ZH.put("subtitle.login", "§7使用 /login <密码>");
        ZH.put("subtitle.ok", "§7欢迎来到服务器");
        ZH.put("actionbar.register", "§e请注册 §8| §7剩余 §f%d§7 秒");
        ZH.put("actionbar.login", "§e请登录 §8| §7剩余 §f%d§7 秒");
        ZH.put("actionbar.urgent", "§c请尽快%s §8| §7剩余 §f%d§7 秒");
        ZH.put("chat.blocked", "§c请先完成登录再聊天。");
        ZH.put("command.blocked", "§c请先完成登录再执行命令。");
        ZH.put("register.ok", "§a注册成功，已自动登录。欢迎来到服务器！");
        ZH.put("register.exists", "§c该用户名已经注册，请使用 §e/login <密码>§c。");
        ZH.put("register.premium_name", "§c该用户名属于正版账号，无法用离线身份注册。请使用正版账号登录或更换游戏 ID。");
        ZH.put("premium.password_set", "§a已设置离线备份密码。正版验证不可用时可离线进入，并用 §e/login <密码>§a 登录。");
        ZH.put("premium.password_updated", "§a已更新离线备份密码。");
        ZH.put("premium.password_disabled", "§7服务器未启用正版账号离线备份密码功能。");
        ZH.put("premium.password_exists", "§7该正版账号已有离线备份密码，请使用 §e/login <密码>§7。");
        ZH.put("premium.backup.relogin", "§a备份密码验证成功，正版同名玩家已下线。请重新连接服务器，以正版 UUID 进入。");
        ZH.put("premium.backup.welcome", "§a离线备份验证成功，欢迎回来，§e%s§a！");
        ZH.put("premium.backup.premium_online", "§c正版同名玩家已重新上线，请等待其下线后再用备份密码登录。");
        ZH.put("pending.command_blocked", "§c该玩家尚未登录，无法传送或攻击。");
        ZH.put("premium.backup.kick_premium", "§c当前用户名异地登录成功，如果不是本人行为请检查密码是否泄露！");
        ZH.put("title.backup", "备份验证成功，欢迎");
        ZH.put("subtitle.backup", "已切换到正版存档");
        ZH.put("register.length", "§c密码长度必须为 %d-%d 个字符。");
        ZH.put("register.mismatch", "§c两次输入的密码不一致。");
        ZH.put("register.same_name", "§c密码不能与用户名相同。");
        ZH.put("register.busy", "§7正在处理，请稍候...");
        ZH.put("login.ok", "§a登录成功，欢迎回来！");
        ZH.put("login.wrong", "§c密码错误，还可以尝试 %d 次。");
        ZH.put("login.not_registered", "§c你还没有注册，请使用 §e/register <密码> <确认密码>§c 注册。");
        ZH.put("login.already", "§7你已经登录过了。");
        ZH.put("login.busy", "§7正在验证密码，请稍候...");
        ZH.put("login.banned", "§c密码错误次数过多，IP 已被临时封禁 %d 秒。");
        ZH.put("ip.banned", "§c你的 IP 因多次登录失败被临时封禁，剩余 %d 秒。");
        ZH.put("timeout", "§c登录超时，已断开连接。");
        ZH.put("timeout.spectator", "§7登录超时，已切换为旁观者模式；随时可以 /login 或 /register。");
        ZH.put("premium.name", "§c该用户名已被正版账号占用，无法以离线身份登录。请更换游戏 ID 或联系管理员。");
        ZH.put("premium.name.kick", "§c你当前使用的用户名被正版用户登录成功，请检查你所使用的用户ID是否有正版玩家使用！");
        ZH.put("premium.required", "§c该用户名属于正版账号，请使用正版账号登录。");
        ZH.put("premium.online", "§7该账号已通过正版验证，无需注册/登录。");
        ZH.put("conflict", "§c该用户名存在不同的 UUID 记录，请联系管理员处理。");
        ZH.put("logout.ok", "§a已登出，请重新登录。");
        ZH.put("logout.not_logged", "§7你当前没有登录。");
        ZH.put("logout.premium", "§7正版账号无需登出。");
        ZH.put("changepw.ok", "§a密码修改成功。");
        ZH.put("changepw.old_wrong", "§c旧密码错误。");
        ZH.put("changepw.not_logged", "§c请先登录后再修改密码。");
        ZH.put("changepw.premium", "§7正版账号没有离线密码。");
        ZH.put("not_player", "§c该命令只能由玩家执行。");
        ZH.put("admin.reload", "§a配置已重新加载。");
        ZH.put("admin.unregistered", "§a已注销用户 §e%s§a。");
        ZH.put("admin.not_found", "§c未找到用户 §e%s§c。");
        ZH.put("admin.info", "§e%s§f: uuid=§7%s§f, 类型=§7%s§f, 状态=§7%s§f, 最后登录=§7%s");
        ZH.put("admin.reset", "§a已重置 §e%s§a 的密码。");
        ZH.put("admin.list", "§7已注册用户数：§e%d");
        ZH.put("freeze.notice", "§7你已被冻结在登录位置，完成登录后即可正常游戏。");
        ZH.put("auth.timeout_warn", "§c请尽快完成登录，否则会被踢出。");
        ZH.put("premium.name.recorded", "§7已记录正版用户名 %s（UUID %s）");

        // ---------------- English ----------------
        EN.put("welcome.register", "§fWelcome §e%s§f! Please register: §e/register <password> <confirm>§f. You will be logged in automatically.");
        EN.put("welcome.login", "§fWelcome back §e%s§f! Please login: §e/login <password>");
        EN.put("welcome.premium", "§aPremium authentication passed§f. Welcome back, §e%s§f!");
        EN.put("title.register", "§ePlease register");
        EN.put("title.login", "§ePlease login");
        EN.put("title.ok", "§aLogged in");
        EN.put("title.premium", "Premium verified, welcome");
        EN.put("subtitle.premium", "Automatically signed in");
        EN.put("subtitle.register", "§7Use /register <password> <confirm>");
        EN.put("subtitle.login", "§7Use /login <password>");
        EN.put("subtitle.ok", "§7Welcome to the server");
        EN.put("actionbar.register", "§ePlease register §8| §7%ds left");
        EN.put("actionbar.login", "§ePlease login §8| §7%ds left");
        EN.put("actionbar.urgent", "§cPlease %s now §8| §7%ds left");
        EN.put("chat.blocked", "§cPlease finish logging in before chatting.");
        EN.put("command.blocked", "§cPlease finish logging in before using commands.");
        EN.put("register.ok", "§aRegistered and logged in. Welcome!");
        EN.put("register.exists", "§cThat name is already registered. Use §e/login <password>§c.");
        EN.put("register.premium_name", "§cThat name belongs to a premium account and cannot be registered offline. Use the premium account or change your ID.");
        EN.put("premium.password_set", "§aOffline backup password set. If premium auth is unavailable, join offline and use §e/login <password>§a.");
        EN.put("premium.password_updated", "§aOffline backup password updated.");
        EN.put("premium.password_disabled", "§7Premium offline backup passwords are disabled on this server.");
        EN.put("premium.password_exists", "§7This premium account already has an offline backup password. Use §e/login <password>§7.");
        EN.put("premium.backup.relogin", "§aBackup password verified; the premium session with this name was disconnected. Please reconnect to enter with the premium UUID.");
        EN.put("premium.backup.welcome", "§aOffline backup verified. Welcome back, §e%s§a!");
        EN.put("premium.backup.premium_online", "§cThe premium account with this name is online again. Wait for it to leave before using the backup password.");
        EN.put("pending.command_blocked", "§cThat player is not authenticated yet and cannot be teleported to or attacked.");
        EN.put("premium.backup.kick_premium", "§cThis username has been logged in from another location. If this was not you, check whether your password has leaked!");
        EN.put("title.backup", "Backup verified, welcome");
        EN.put("subtitle.backup", "Premium save loaded");
        EN.put("register.length", "§cPassword must be %d-%d characters.");
        EN.put("register.mismatch", "§cThe two passwords do not match.");
        EN.put("register.same_name", "§cPassword must not equal your username.");
        EN.put("register.busy", "§7Working, please wait...");
        EN.put("login.ok", "§aLogged in. Welcome back!");
        EN.put("login.wrong", "§cWrong password. %d attempt(s) left.");
        EN.put("login.not_registered", "§cYou are not registered. Use §e/register <password> <confirm>§c.");
        EN.put("login.already", "§7You are already logged in.");
        EN.put("login.busy", "§7Verifying password, please wait...");
        EN.put("login.banned", "§cToo many failures. IP is banned for %d seconds.");
        EN.put("ip.banned", "§cYour IP is temporarily banned for %d more seconds.");
        EN.put("timeout", "§cLogin timed out.");
        EN.put("timeout.spectator", "§7Login timed out; switched to spectator. You can still /login or /register.");
        EN.put("premium.name", "§cThat username belongs to a premium account and cannot join offline. Change your name or contact an admin.");
        EN.put("premium.name.kick", "§cYour current username has been logged in by a premium user. Check whether the user ID you are using belongs to a premium player!");
        EN.put("premium.required", "§cThat name belongs to a premium account. Please use your premium account.");
        EN.put("premium.online", "§7This account is premium-authenticated; no password needed.");
        EN.put("conflict", "§cA different UUID is already stored for this name. Contact an admin.");
        EN.put("logout.ok", "§aLogged out.");
        EN.put("logout.not_logged", "§7You are not logged in.");
        EN.put("logout.premium", "§7Premium accounts do not need to log out.");
        EN.put("changepw.ok", "§aPassword changed.");
        EN.put("changepw.old_wrong", "§cOld password is wrong.");
        EN.put("changepw.not_logged", "§cPlease log in first.");
        EN.put("changepw.premium", "§7Premium accounts have no offline password.");
        EN.put("not_player", "§cThis command can only be used by a player.");
        EN.put("admin.reload", "§aConfiguration reloaded.");
        EN.put("admin.unregistered", "§aUnregistered §e%s§a.");
        EN.put("admin.not_found", "§cUser §e%s§c not found.");
        EN.put("admin.info", "§e%s§f: uuid=§7%s§f, type=§7%s§f, state=§7%s§f, last login=§7%s");
        EN.put("admin.reset", "§aPassword reset for §e%s§a.");
        EN.put("admin.list", "§7Registered users: §e%d");
        EN.put("freeze.notice", "§7You are frozen at the login position until you finish authentication.");
        EN.put("auth.timeout_warn", "§cPlease finish logging in or you will be kicked.");
        EN.put("premium.name.recorded", "§7Recorded premium name %s (UUID %s)");
    }

    private Messages() {
    }

    public static void setLanguage(String language) {
        chinese = !"en_us".equalsIgnoreCase(language);
    }

    public static String raw(String key, Object... args) {
        String template = (chinese ? ZH : EN).getOrDefault(key, key);
        if (args.length == 0) {
            return template;
        }
        try {
            return String.format(Locale.ROOT, template, args);
        } catch (Exception e) {
            return template;
        }
    }

    public static Component get(String key, Object... args) {
        return Component.literal(PREFIX + raw(key, args));
    }

    public static Component plain(String key, Object... args) {
        return Component.literal(raw(key, args));
    }
}
