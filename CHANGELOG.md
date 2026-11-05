# 变更记录

本项目的重要变更都记录在这里，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.1.1] - 2026-11-05

### 变更

- **玩家文案精简**：对话框与提示去掉冗余措辞和句末句号，例如登录/注册对话框改为
  「欢迎回来，X！请输入密码登录」「欢迎，X！请设置登录密码」，超时提示改为「请在 N 秒内完成操作，超时会被断开」。
  正版名被占用时统一提示「该昵称已被占用，请更换游戏 ID」/「该昵称已被正版玩家占用，请更换昵称」；
  IP 封禁、登录超时提示不再附带具体秒数，转服失败提示改为「传送失败，请稍后重试或联系管理员」。
- **服务端日志统一为简体中文**：74 条日志统一 `[PLogin]` 前缀、`{}` 占位与全角标点，句末不加句号；
  认证状态、欢迎类型不再打印英文常量，改为「待注册 / 待登录 / 已认证」「正版 / 离线备份 / 登录 / 注册」，
  皮肤签名状态由 `signed` / `unsigned` 改为「已签名」/「未签名」。
- `docs/文案.md` 按新文案重写玩家提示表；`README.md`、`docs/TECHNICAL.md` 的示例日志与排错提示同步更新，
  `CONTRIBUTING.md` 补充日志书写约定。

## [1.1.0] - 2026-11-05

### 变更

- **登录改为原版 Configuration 阶段的对话框**。玩家通过正版验证/离线回退进入配置阶段后，
  PLogin 在 `SynchronizeRegistriesTask` 与 `PrepareSpawnTask` 之间插入 `plogin:auth` 任务，
  用原版 `ClientboundShowDialogPacket` 显示「注册账号 / 登录服务器」对话框，
  玩家点击按钮后由 `ServerboundCustomClickActionPacket` 回传密码，认证通过才继续加载世界。
- **完全移除 limbo 虚拟世界方案**：删除 `plogin:limbo` 维度、`LimboManager`、
  冻结/失明/隐藏玩家/数据包白名单/传送与伤害保护等 10 个 Mixin。未登录玩家不再进入世界，
  因此不需要隔离、隐藏或过滤。
- **命令调整**：`/login` 不再提供；`/register` 仅用于正版玩家设置离线备份密码；
  `/logout` 改为断开连接，重连后重新走对话框；`/changepassword` 保留（离线账号与正版备份密码均可改）。
- **配置调整**：移除 `blindness`、`freezeMovement`，以及旧备份重连专用的
  `backupRequireCookie`、`backupTokenWaitMs`、`backupTokenBindIp`；新增
  `transferEnabled`、`transferHost`、`transferPort`、`transferTokenSeconds`、`transferBindIp`、
  `transferTokenWaitMs`；`kickOnTimeout=false` 表示「不超时，保留对话框继续等待」，
  `loginTimeoutSeconds` 表示对话框停留时间。
- **备份密码改为“对话框验证 + 原版 transfer”**：密码通过后先发 `ClientboundStoreCookiePacket`
  下发一次性凭据，再发 `clear_dialog`，50ms 后发送 `ClientboundTransferPacket` 回到同一服务端；
  客户端以 TRANSFER intention 重连并带回 Cookie，登录阶段换成正版 UUID，跳过对话框，直接进入正版存档。
  正版同名在线时拒绝这次备份登录并提示等待（不踢人）。
- **需要 `server.properties` 设置 `accepts-transfers=true`**；未开启时原版会拒绝 TRANSFER 连接，
  服务端启动会输出 WARN，备份密码登录回退为离线 UUID 登录。
- **断开前先清 dialog**：超时、退出按钮、IP 封禁、账号锁定、transfer 等所有配置阶段离开动作，
  都先发送 `ClientboundClearDialogPacket`。修复客户端停在自定义屏幕、按钮无响应、
  只能点客户端强制断开、重连后又要求输密码的循环。
- 对话框为静态内容，不实现逐字动画；同一服务端的 transfer 使用 16 字节随机 Cookie 凭据，
  不引入 JWE/签名令牌格式。

### 新增

- `ConfigAuth`：配置阶段认证状态机（注册/登录/错误重试/IP 封禁/账号锁定/超时/退出/备份密码）。
- `AuthDialogs`：用 `ConfirmationDialog` + `TextInput` + `CustomAll` 构建对话框。
- `AuthConfigurationTask`：配置任务队列中的阻塞任务。
- Mixin：`ServerConfigurationPacketListenerImplMixin`（插入任务）、
  `ServerCommonPacketListenerMixin`（处理按钮回调/清理会话）、
  `ServerGamePacketListenerImplMixin`（debugconfig 重配置标记）、
  `ServerCommonPacketListenerAccessor`（读取底层 Connection）、
  `LoginCookieMixin`（登录阶段 transfer Cookie）、
  `ServerHandshakePacketListenerImplMixin`（记录连接 host）、
  `ServerLoginPacketListenerAccessor`（读取 transferred）。
- `TransferVerification`：一次性 transfer 凭据（16 字节、TTL、IP 绑定、一次性）。
- `TransferState`：按连接隔离的 Cookie 响应与 handshake host。
- 旧世界兼容：jar 内保留空的 `data/plogin` 数据包目录，避免升级后报 `Missing data pack plogin`；
  进服时自动清理旧的 `Invulnerable`/`NoGravity` 冻结标记。

### 安全

- 密码通过对话框提交，不进入聊天栏和命令日志。
- 登录时对空密码/超长密码直接按失败处理，不送进 PBKDF2，避免恶意 payload 拖垮哈希线程。
- 认证成功后立即从会话表移除 Session，避免为每个进入世界的玩家残留配置监听器引用。
- transfer 凭据是 16 字节 SecureRandom，默认 120 秒过期、绑定 IP、一次性消费；正版 UUID 只来自
  `users.json` 的备份记录，不采用客户端输入。

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
