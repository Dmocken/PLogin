# 变更记录

本项目的重要变更都记录在这里，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0] - 2026-11-04

首个公开版本，适配 Minecraft 26.2 / Fabric Loader 0.19.5。

### 新增

- 混合登录：`online-mode=true` 下先走 Mojang 正版验证，无有效会话时回退到离线 UUID。
- 离线注册与登录：`/register` `/login` `/logout` `/changepassword`，含 `/reg` `/l` `/cp` 别名。
- 管理员命令：`/plogin reload|list|info|unregister|resetpassword`。
- 登录前隔离：`plogin:limbo` 虚空维度、冻结移动、失明、隐藏 Tab 与进出消息。
- 未登录保护：`/tp`、`/kill`、`/damage`、死亡重生、容器与交互数据包全部拦截。
- 正版名保护：记录已验证的正版名，阻止离线冒用；正版上线时踢掉同名离线号。
- 正版离线备份密码：正版玩家可设置密码，Mojang 不可用时凭密码离线登录，验证通过后重连切换正版 UUID。
- 离线皮肤：正版名取 Mojang 签名属性；离线名走 LittleSkin CustomSkinAPI + MineSkin 签名。
- 防爆破：PBKDF2-HMAC-SHA256 加盐、失败计数与 IP 临时封禁、认证命令忙碌标记。
- 中英双语提示，可通过配置切换。
- 无 Gradle 的 `javac` 构建脚本，直接对着服务端 jar 编译。
