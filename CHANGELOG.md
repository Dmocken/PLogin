# 变更记录

本项目的重要变更都记录在这里，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.1] - 2026-11-04

### 安全

- **修复：正版备份登录的重连凭据只按用户名记录**。旧实现任何同名的第二条离线连接都能在
  有效期内抢先消费令牌，顶替真正输对密码的客户端拿到正版 UUID。现在改为一次性 16 字节
  SecureRandom 凭据，经原版 Cookie 通道（`ClientboundStoreCookiePacket` → 客户端存储 → 登录阶段
  `ClientboundCookieRequestPacket`/`ServerboundCookieResponsePacket`）下发并取回；凭据默认同时
  绑定 登录 IP（`backupTokenBindIp`），消费一次即销毁，带 Cookie 的同名连接才允许进入正版 UUID。
- **修复：防爆破机制可被重连规避**。失败计数此前只存放在单次连接的 Session 里，攻击者
  断线重连即可重置次数。现在在 IP 维度之外增加账号维度的跨连接计数（`maxAccountFailures`、
  `failureWindowSeconds`、`accountLockSeconds`），触发后临时锁定账号，重连不再重置任何计数。
- **修复：账号数据文件损坏时不再继续认证**。`users.json` / `premium-names.json` 无法解析时，
  服务器进入保护模式：拒绝一切离线注册/登录，防止在空账号库上抢注账号；正版验证不受影响。
- **修复：登记/改密落盘失败不再报成功**。所有变更先写盘后确认，写盘失败会回滚内存中的
  变更并向玩家提示保存失败（`register.save_failed`）。
- **修复：皮肤查询移出登录关键路径**。离线皮肤查询在专用 `PLogin-Skin` 线程池执行，登录线程
  最多等待 `offlineSkinMaxWaitMs`（默认 3 秒，0 = 不等待）；超时的查询会在后台继续补缓存，
  第三方皮肤 API 变慢不再拖慢或超时整个登录流程。

### 新增

- 配置项：`failureWindowSeconds`、`maxAccountFailures`、`accountLockSeconds`、`backupRequireCookie`、
  `backupTokenWaitMs`、`backupTokenBindIp`、`offlineSkinMaxWaitMs`。
- 新增 14 号服务端 Mixin `LoginCookieMixin`（拦截并路由备份重连 Cookie 响应）。

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
- Windows 版构建脚本 `build.ps1`，与 `build.sh` 行为一致。
- 一键依赖脚本 `scripts/fetch-deps.sh` / `scripts/fetch-deps.ps1`：自动下载 26.2 服务端 jar、Mojang 库、Fabric Loader 与 Fabric API，拆分 Fabric API 的嵌套模块，可加 `--build` / `-Build` 直接编译。
- 构建脚本支持自动探测 `MC_SERVER`（`MC_SERVER` 环境变量、`.mcserver/`、`../minecraft-bingo`、`$HOME/mc/minecraft-bingo`）。
