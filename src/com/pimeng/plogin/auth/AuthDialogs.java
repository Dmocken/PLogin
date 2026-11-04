package com.pimeng.plogin.auth;

import com.pimeng.plogin.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.TextInput;

/**
 * 构建“配置阶段”登录/注册对话框。
 *
 * <p>对话框在 {@code Configuration} 状态下发（{@code ClientboundShowDialogPacket}），
 * 玩家点击按钮后客户端回 {@code ServerboundCustomClickActionPacket}，载荷里带着输入框内容。
 * 这里只做静态对话框：不做逐字打字动画，也不需要 JWE/转账等逻辑。
 */
public final class AuthDialogs {
    /** 登录按钮 / 注册按钮 / 退出按钮的动作 id，前缀固定为 plogin。 */
    public static final Identifier ACTION_LOGIN = Identifier.fromNamespaceAndPath("plogin", "login");
    public static final Identifier ACTION_REGISTER = Identifier.fromNamespaceAndPath("plogin", "register");
    public static final Identifier ACTION_EXIT = Identifier.fromNamespaceAndPath("plogin", "disconnect");

    private static final int BODY_WIDTH = 280;
    private static final int BUTTON_WIDTH = 150;
    private static final int INPUT_WIDTH = 220;

    private AuthDialogs() {
    }

    /** 已有账号：只填密码。 */
    public static Dialog login(String playerName, String error, int maxLength, int timeoutSeconds) {
        List<DialogBody> body = new ArrayList<>(3);
        addError(body, error);
        body.add(message(Messages.raw("dialog.login.body", playerName), ChatFormatting.WHITE));
        if (timeoutSeconds > 0) {
            body.add(message(Messages.raw("dialog.timeout", timeoutSeconds), ChatFormatting.GRAY));
        }
        List<Input> inputs = List.of(passwordInput("password",
                Messages.raw("dialog.input.password"), maxLength));
        return confirmation(Messages.raw("dialog.login.title"), body, inputs,
                Messages.raw("dialog.button.login"), ACTION_LOGIN,
                Messages.raw("dialog.button.exit"), ACTION_EXIT);
    }

    /** 新玩家：密码 + 确认密码。 */
    public static Dialog register(String playerName, String error, int minLength, int maxLength,
                                  int timeoutSeconds) {
        List<DialogBody> body = new ArrayList<>(4);
        addError(body, error);
        body.add(message(Messages.raw("dialog.register.body", playerName), ChatFormatting.WHITE));
        body.add(message(Messages.raw("dialog.register.rules", Math.max(1, minLength),
                Math.max(minLength, maxLength)), ChatFormatting.GRAY));
        if (timeoutSeconds > 0) {
            body.add(message(Messages.raw("dialog.timeout", timeoutSeconds), ChatFormatting.GRAY));
        }
        List<Input> inputs = List.of(
                passwordInput("password", Messages.raw("dialog.input.password"), maxLength),
                passwordInput("password_confirm", Messages.raw("dialog.input.confirm"), maxLength));
        return confirmation(Messages.raw("dialog.register.title"), body, inputs,
                Messages.raw("dialog.button.register"), ACTION_REGISTER,
                Messages.raw("dialog.button.exit"), ACTION_EXIT);
    }

    private static Dialog confirmation(String title, List<DialogBody> body, List<Input> inputs,
                                       String yesLabel, Identifier yesAction,
                                       String noLabel, Identifier noAction) {
        CommonDialogData common = new CommonDialogData(
                Component.literal(Messages.strip(title)),
                Optional.empty(),
                false,                 // can_close_with_escape
                false,                 // pause
                DialogAction.NONE,     // 点击后不自动关闭，由服务端决定下一帧
                body,
                inputs);
        return new ConfirmationDialog(common, button(yesLabel, yesAction), button(noLabel, noAction));
    }

    private static Input passwordInput(String key, String label, int maxLength) {
        TextInput control = new TextInput(
                INPUT_WIDTH,
                Component.literal(Messages.strip(label)),
                true,
                "",
                Math.max(1, maxLength),
                Optional.empty());
        return new Input(key, control);
    }

    private static ActionButton button(String label, Identifier action) {
        return new ActionButton(
                new CommonButtonData(Component.literal(Messages.strip(label)), BUTTON_WIDTH),
                Optional.of(new CustomAll(action, Optional.empty())));
    }

    private static PlainMessage message(String text, ChatFormatting color) {
        return new PlainMessage(Component.literal(Messages.strip(text)).withStyle(color), BODY_WIDTH);
    }

    private static void addError(List<DialogBody> body, String error) {
        if (error != null && !error.isBlank()) {
            body.add(0, message(error, ChatFormatting.RED));
        }
    }
}
