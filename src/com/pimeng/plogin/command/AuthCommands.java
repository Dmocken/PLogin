package com.pimeng.plogin.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.pimeng.plogin.Messages;
import com.pimeng.plogin.PLogin;
import com.pimeng.plogin.auth.AuthManager;
import com.pimeng.plogin.auth.PasswordHasher;
import com.pimeng.plogin.auth.UserRecord;
import com.pimeng.plogin.auth.HybridAuth;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * /register /login /logout /changepassword 以及管理员命令 /plogin。
 */
public final class AuthCommands {
    private AuthCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("register")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .then(Commands.argument("password", StringArgumentType.word())
                        .then(Commands.argument("confirm", StringArgumentType.word())
                                .executes(AuthCommands::execRegister))));

        dispatcher.register(Commands.literal("login")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .then(Commands.argument("password", StringArgumentType.word())
                        .executes(AuthCommands::execLogin)));

        dispatcher.register(Commands.literal("logout")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(AuthCommands::execLogout));

        dispatcher.register(Commands.literal("changepassword")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .then(Commands.argument("oldPassword", StringArgumentType.word())
                        .then(Commands.argument("newPassword", StringArgumentType.word())
                                .executes(AuthCommands::execChangePassword))));

        registerAlias(dispatcher, "reg", "register");
        registerAlias(dispatcher, "l", "login");
        registerAlias(dispatcher, "cp", "changepassword");

        dispatcher.register(Commands.literal("plogin")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                .then(Commands.literal("reload").executes(AuthCommands::execReload))
                .then(Commands.literal("list").executes(AuthCommands::execList))
                .then(Commands.literal("info")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(AuthCommands::execInfo)))
                .then(Commands.literal("unregister")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(AuthCommands::execUnregister)))
                .then(Commands.literal("resetpassword")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("password", StringArgumentType.word())
                                        .executes(AuthCommands::execResetPassword)))));
    }

    private static void registerAlias(CommandDispatcher<CommandSourceStack> dispatcher, String alias, String canonical) {
        if (dispatcher.getRoot().getChild(alias) != null) {
            PLogin.LOGGER.warn("[PLogin] 命令别名 /{} 已被其他模组占用，跳过注册", alias);
            return;
        }
        if ("register".equals(canonical)) {
            dispatcher.register(Commands.literal(alias)
                    .requires(source -> source.getEntity() instanceof ServerPlayer)
                    .then(Commands.argument("password", StringArgumentType.word())
                            .then(Commands.argument("confirm", StringArgumentType.word())
                                    .executes(AuthCommands::execRegister))));
        } else if ("login".equals(canonical)) {
            dispatcher.register(Commands.literal(alias)
                    .requires(source -> source.getEntity() instanceof ServerPlayer)
                    .then(Commands.argument("password", StringArgumentType.word())
                            .executes(AuthCommands::execLogin)));
        } else if ("changepassword".equals(canonical)) {
            dispatcher.register(Commands.literal(alias)
                    .requires(source -> source.getEntity() instanceof ServerPlayer)
                    .then(Commands.argument("oldPassword", StringArgumentType.word())
                            .then(Commands.argument("newPassword", StringArgumentType.word())
                                    .executes(AuthCommands::execChangePassword))));
        }
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return context.getSource().getPlayerOrException();
    }

    private static int execRegister(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = player(context);
        String password = StringArgumentType.getString(context, "password");
        String confirm = StringArgumentType.getString(context, "confirm");
        PLogin.auth().requestRegister(player, password, confirm);
        return 1;
    }

    private static int execLogin(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = player(context);
        String password = StringArgumentType.getString(context, "password");
        PLogin.auth().requestLogin(player, password);
        return 1;
    }

    private static int execLogout(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        PLogin.auth().requestLogout(player(context));
        return 1;
    }

    private static int execChangePassword(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = player(context);
        String oldPassword = StringArgumentType.getString(context, "oldPassword");
        String newPassword = StringArgumentType.getString(context, "newPassword");
        PLogin.auth().requestChangePassword(player, oldPassword, newPassword);
        return 1;
    }

    private static int execReload(CommandContext<CommandSourceStack> context) {
        PLogin.reloadConfig();
        context.getSource().sendSuccess(() -> Messages.get("admin.reload"), true);
        return 1;
    }

    private static int execList(CommandContext<CommandSourceStack> context) {
        int count = PLogin.users().userCount();
        context.getSource().sendSuccess(() -> Messages.get("admin.list", count), true);
        return count;
    }

    private static int execInfo(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        MinecraftServer server = context.getSource().getServer();
        ServerPlayer online = server.getPlayerList().getPlayer(name);
        UserRecord record = PLogin.users().find(name);
        if (online != null) {
            String type = HybridAuth.isOfflineProfile(online.getGameProfile()) ? "offline" : "premium";
            AuthManager.Session session = PLogin.auth().session(online);
            String state = session == null ? "authenticated" : session.state.name().toLowerCase(Locale.ROOT);
            String lastLogin = record == null ? "-" : String.valueOf(record.lastLoginAt);
            context.getSource().sendSuccess(() -> Messages.get("admin.info",
                    online.getGameProfile().name(), online.getUUID(), type, state, lastLogin), false);
            return 1;
        }
        if (record == null) {
            context.getSource().sendFailure(Messages.get("admin.not_found", name));
            return 0;
        }
        context.getSource().sendSuccess(() -> Messages.get("admin.info",
                record.name, record.uuid, "offline", "offline", String.valueOf(record.lastLoginAt)), false);
        return 1;
    }

    private static int execUnregister(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        MinecraftServer server = context.getSource().getServer();
        boolean removed = PLogin.users().remove(name);
        ServerPlayer online = server.getPlayerList().getPlayer(name);
        boolean premiumBypass = PLogin.config() != null && PLogin.config().premiumBypass;
        if (online != null && removed && (!premiumBypass || HybridAuth.isOfflineProfile(online.getGameProfile()))) {
            PLogin.auth().forceState(online, AuthManager.State.PENDING_REGISTER);
        }
        if (!removed) {
            context.getSource().sendFailure(Messages.get("admin.not_found", name));
            return 0;
        }
        context.getSource().sendSuccess(() -> Messages.get("admin.unregistered", name), true);
        PLogin.LOGGER.info("[PLogin] 管理员注销了用户 {}（在线={}）", name, online != null);
        return 1;
    }

    private static int execResetPassword(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        String password = StringArgumentType.getString(context, "password");
        UserRecord record = PLogin.users().find(name);
        if (record == null) {
            context.getSource().sendFailure(Messages.get("admin.not_found", name));
            return 0;
        }
        int iterations = PLogin.config().passwordIterations;
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(password, salt, iterations);
        PLogin.users().setPassword(record.name, salt, hash, iterations);
        context.getSource().sendSuccess(() -> Messages.get("admin.reset", record.name), true);
        PLogin.LOGGER.info("[PLogin] 管理员重置了用户 {} 的密码", record.name);
        return 1;
    }

}
