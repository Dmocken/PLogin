package com.pimeng.plogin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * 服务端发送的字面量消息（不依赖客户端语言文件，客户端不装模组也能看懂）。
 *
 * <p>对话框里的文字会先去掉 § 格式码再由 {@code AuthDialogs} 上色，
 * 避免原版库把“§c”当成普通字符显示。
 */
public final class Messages {
    private static final String PREFIX = "§b[PLogin] §r";
    private static final Pattern FORMATTING_CODE = Pattern.compile("§[0-9a-fk-orA-FK-ORx]");
    private static final Map<String, String> ZH = new HashMap<>();
    private static final Map<String, String> EN = new HashMap<>();
    private static volatile boolean chinese = true;

    static {
        // ---------------- 中文 ----------------
        // 配置阶段对话框
        ZH.put("dialog.login.title", "登录服务器");
        ZH.put("dialog.login.body", "欢迎回来，%s！请输入密码登录");
        ZH.put("dialog.register.title", "注册账号");
        ZH.put("dialog.register.body", "欢迎，%s！请设置登录密码");
        ZH.put("dialog.register.rules", "密码长度 %d-%d 位，不能与游戏 ID 相同");
        ZH.put("dialog.timeout", "请在 %d 秒内完成操作，超时会被断开");
        ZH.put("dialog.input.password", "密码");
        ZH.put("dialog.input.confirm", "确认密码");
        ZH.put("dialog.button.login", "登录");
        ZH.put("dialog.button.register", "注册");
        ZH.put("dialog.button.exit", "退出服务器");
        ZH.put("disconnect.closed", "你已退出登录界面");

        // 认证结果 / 欢迎
        ZH.put("welcome.premium", "§a正版验证通过§f，欢迎回来，§e%s§f！");
        ZH.put("premium.backup.welcome", "§a离线备份验证成功，欢迎回来，§e%s§a！");
        ZH.put("register.ok", "§a注册成功，已自动登录。欢迎来到服务器！");
        ZH.put("login.ok", "§a登录成功，欢迎回来！");
        ZH.put("title.ok", "§a登录成功");
        ZH.put("subtitle.ok", "§7欢迎来到服务器");
        ZH.put("title.premium", "正版验证成功，欢迎");
        ZH.put("subtitle.premium", "已自动登录");
        ZH.put("title.backup", "备份验证成功，欢迎");
        ZH.put("subtitle.backup", "已切换到正版存档");

        // 注册 / 登录错误
        ZH.put("register.length", "§c密码长度必须为 %d-%d 个字符。");
        ZH.put("register.mismatch", "§c两次输入的密码不一致。");
        ZH.put("register.same_name", "§c密码不能与用户名相同。");
        ZH.put("register.exists", "§c该用户名已经注册，请在登录框输入密码。");
        ZH.put("register.premium_name", "§c该昵称已被占用，请更换游戏 ID。");
        ZH.put("register.busy", "§7正在处理，请稍候...");
        ZH.put("register.save_failed", "§c保存账号数据失败，请稍后重试；若持续失败请联系管理员。");
        ZH.put("register.already", "§7登录已经在进入服务器前完成；如需修改密码请使用 §e/changepassword§7。");
        ZH.put("login.not_registered", "§c你还没有注册，请先设置密码注册");
        ZH.put("login.wrong", "§c密码错误，还可以尝试 %d 次");
        ZH.put("login.banned", "§c密码错误次数过多，IP 已被临时封禁");
        ZH.put("login.account_locked", "§c该账号因多次密码错误被临时锁定，请 %d 秒后再试。");
        ZH.put("ip.banned", "§c你的 IP 因多次登录失败被临时封禁");
        ZH.put("timeout", "§c登录超时");
        ZH.put("auth.corrupt", "§c账号数据文件损坏，服务器已停用离线注册/登录，请联系管理员修复后重启");
        ZH.put("auth.internal_error", "§c认证服务暂时不可用，请稍后重新连接");
        ZH.put("conflict", "§c该用户名存在不同的 UUID 记录，请联系管理员处理");
        ZH.put("premium.name", "§c该昵称已被正版玩家占用，请更换昵称");
        ZH.put("premium.name.kick", "§c您当前使用的昵称被正版玩家登录，请检查你的昵称是否在正版被占用！");

        // 正版离线备份密码
        ZH.put("premium.password_set", "§a已设置密码。正版验证不可用时可离线进入");
        ZH.put("premium.password_updated", "§a已更新离线备份密码");
        ZH.put("premium.password_disabled", "§7服务器未启用正版账号离线备份密码功能");
        ZH.put("premium.backup.premium_online", "§c同昵称玩家在线，请稍后再试");
        ZH.put("name.online", "§c同昵称玩家在线，请稍后再试");
        ZH.put("transfer.unavailable", "§c传送失败，请稍后重试或联系管理员");
        ZH.put("transfer.failed", "§c传送凭据无效或已过期，请重新连接服务器后再试");

        // 已进入世界后的操作
        ZH.put("logout.ok", "§a已登出");
        ZH.put("logout.premium", "§7正版账号无需登出");
        ZH.put("changepw.ok", "§a密码修改成功");
        ZH.put("changepw.old_wrong", "§c旧密码错误");
        ZH.put("changepw.premium", "§7正版账号没有离线密码");

        // 命令 / 管理
        ZH.put("not_player", "§c该命令只能由玩家执行。");
        ZH.put("admin.reload", "§a配置已重新加载");
        ZH.put("admin.unregistered", "§a已注销用户 §e%s§a");
        ZH.put("admin.not_found", "§c未找到用户 §e%s§c");
        ZH.put("admin.info", "§e%s§f: uuid=§7%s§f, 类型=§7%s§f, 状态=§7%s§f, 最后登录=§7%s");
        ZH.put("admin.reset", "§a已重置 §e%s§a 的密码");
        ZH.put("admin.list", "§7已注册用户数：§e%d");

        // ---------------- English ----------------
        EN.put("dialog.login.title", "Sign in");
        EN.put("dialog.login.body", "Welcome back, %s! Enter your password to sign in.");
        EN.put("dialog.register.title", "Create account");
        EN.put("dialog.register.body", "Welcome, %s! This is your first visit; please set a login password.");
        EN.put("dialog.register.rules", "Password must be %d-%d characters and must not equal your name.");
        EN.put("dialog.timeout", "Please finish within %d seconds or you will be disconnected.");
        EN.put("dialog.input.password", "Password");
        EN.put("dialog.input.confirm", "Confirm password");
        EN.put("dialog.button.login", "Sign in");
        EN.put("dialog.button.register", "Register");
        EN.put("dialog.button.exit", "Disconnect");
        EN.put("disconnect.closed", "You closed the login dialog.");

        EN.put("welcome.premium", "§aPremium authentication passed§f. Welcome back, §e%s§f!");
        EN.put("premium.backup.welcome", "§aOffline backup verified. Welcome back, §e%s§a!");
        EN.put("register.ok", "§aRegistered and logged in. Welcome!");
        EN.put("login.ok", "§aLogged in. Welcome back!");
        EN.put("title.ok", "§aLogged in");
        EN.put("subtitle.ok", "§7Welcome to the server");
        EN.put("title.premium", "Premium verified, welcome");
        EN.put("subtitle.premium", "Automatically signed in");
        EN.put("title.backup", "Backup verified, welcome");
        EN.put("subtitle.backup", "Premium save loaded");

        EN.put("register.length", "§cPassword must be %d-%d characters.");
        EN.put("register.mismatch", "§cThe two passwords do not match.");
        EN.put("register.same_name", "§cPassword must not equal your username.");
        EN.put("register.exists", "§cThat name is already registered. Enter its password in the sign-in dialog.");
        EN.put("register.premium_name", "§cThat name belongs to a premium account and cannot be registered offline. Use the premium account or change your ID.");
        EN.put("register.busy", "§7Working, please wait...");
        EN.put("register.save_failed", "§cFailed to save account data. Try again later; contact an admin if it persists.");
        EN.put("register.already", "§7You already signed in before joining; use §e/changepassword§7 to change it.");
        EN.put("login.not_registered", "§cYou are not registered. Set a password in the registration dialog.");
        EN.put("login.wrong", "§cWrong password. %d attempt(s) left.");
        EN.put("login.banned", "§cToo many failures. IP is banned for %d seconds.");
        EN.put("login.account_locked", "§cThis account is temporarily locked after repeated wrong passwords. Try again in %d seconds.");
        EN.put("ip.banned", "§cYour IP is temporarily banned for %d more seconds.");
        EN.put("timeout", "§cLogin timed out.");
        EN.put("auth.corrupt", "§cAccount data file is corrupted; offline registration and login are disabled. Contact an admin.");
        EN.put("auth.internal_error", "§cAuthentication is temporarily unavailable; please reconnect later.");
        EN.put("conflict", "§cA different UUID is already stored for this name. Contact an admin.");
        EN.put("premium.name", "§cThat username belongs to a premium account and cannot join offline. The owner can join with the premium account and run /register to set an offline backup password, or use another ID.");
        EN.put("premium.name.kick", "§cYour current username has been logged in by a premium user. Check whether the user ID you are using belongs to a premium player!");

        EN.put("premium.password_set", "§aOffline backup password set. If premium auth is unavailable, join offline and enter it; the server will transfer you to the premium UUID/save.");
        EN.put("premium.password_updated", "§aOffline backup password updated.");
        EN.put("premium.password_disabled", "§7Premium offline backup passwords are disabled on this server.");
        EN.put("premium.backup.premium_online", "§cThe premium account with this name is online; the offline backup password cannot be used right now. Wait for it to leave and try again.");
        EN.put("name.online", "§cA player with this username is already online. Try again later.");
        EN.put("transfer.unavailable", "§cCannot start the server transfer (target address is not configured or too many pending credentials). Try again later or contact an admin.");
        EN.put("transfer.failed", "§cThe server transfer credential is missing or expired. Reconnect and try again.");

        EN.put("logout.ok", "§aLogged out. Reconnect to sign in again.");
        EN.put("logout.premium", "§7Premium accounts do not need to log out.");
        EN.put("changepw.ok", "§aPassword changed.");
        EN.put("changepw.old_wrong", "§cOld password is wrong.");
        EN.put("changepw.premium", "§7Premium accounts have no offline password.");

        EN.put("not_player", "§cThis command can only be used by a player.");
        EN.put("admin.reload", "§aConfiguration reloaded.");
        EN.put("admin.unregistered", "§aUnregistered §e%s§a.");
        EN.put("admin.not_found", "§cUser §e%s§c not found.");
        EN.put("admin.info", "§e%s§f: uuid=§7%s§f, type=§7%s§f, state=§7%s§f, last login=§7%s");
        EN.put("admin.reset", "§aPassword reset for §e%s§a.");
        EN.put("admin.list", "§7Registered users: §e%d");
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

    /** 去掉原版 § 格式码，供对话框等组件自行上色。 */
    public static String strip(String text) {
        return text == null ? "" : FORMATTING_CODE.matcher(text).replaceAll("");
    }

    public static String clean(String key, Object... args) {
        return strip(raw(key, args));
    }

    public static Component get(String key, Object... args) {
        return Component.literal(PREFIX + raw(key, args));
    }

    public static Component plain(String key, Object... args) {
        return Component.literal(raw(key, args));
    }
}
