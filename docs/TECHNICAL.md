# PLogin 技术文档

> 面向开发者与高级运维：记录 1.1.0 的配置阶段对话框登录、Mixin 清单、认证状态机、皮肤注入、数据格式与实测结论。
> 只想把模组装到服务器上的服主，请先读 [README](../README.md)。

**环境基线**：Minecraft 1.21.10 / 26.1 / 26.2 / 26.3（每个版本一个 jar）· Fabric Loader 0.19.5+ ·
Fabric API · Java 21（1.21.10）/ 25（26.x）

> 配套文案（宣传语、玩家提示、公告模板、命令说明）：[文案.md](文案.md)

---

## 目录

- [0. 当前状态](#0-当前状态2026-11-05)
- [1. 功能](#1-功能)
- [2. 工作原理](#2-工作原理)
- [3. 目录结构](#3-目录结构)
- [4. 构建](#4-构建)
- [5. 部署](#5-部署)
- [6. 配置](#6-配置)
- [7. 命令](#7-命令)
- [8. 数据文件](#8-数据文件)
- [9. 安全设计](#9-安全设计)
- [10. 正版皮肤为什么能正常显示](#10-正版皮肤为什么能正常显示)
- [11. JVM 的 IPv6 优先参数](#11-jvm-的-ipv6-优先参数)
- [12. 测试结果](#12-测试结果)
- [13. 测试环境](#13-测试环境)
- [14. 已知限制](#14-已知限制)
- [15. 参考](#15-参考)
- [16. 许可](#16-许可)

---

## 0. 当前状态（2026-11-05）

| 项目 | 结果 |
|---|---|
| 模组版本 | PLogin 1.1.1（Minecraft 26.2 / Fabric Loader 0.19.5 / Java 25） |
| 正版 UUID | ✅ 通过本地伪装会话服务器实测：UUID = `Mojang hasJoined 返回值` |
| 正版皮肤属性 | ✅ `textures` 属性随 LoginFinished 下发 |
| 正版跳过验证 | ✅ 直接进入；不弹对话框 |
| 离线玩家加入 | ✅ `online-mode=true` 下正常进入；在 Configuration 阶段收到注册/登录对话框 |
| 离线 UUID 修复 | ✅ `OfflinePlayer:<name>`（UUID v3） |
| 配置阶段注册 | ✅ 对话框提交 → PBKDF2 → 写入 users.json → 继续 PrepareSpawn/JoinWorld |
| 配置阶段登录 | ✅ 密码错误会重发带错误提示的对话框；正确后进入世界 |
| 超时 / 退出按钮 | ✅ 超时断开（或按配置继续等待）；「退出服务器」立即断开 |
| 防爆破 | ✅ 连续错误达到阈值临时封禁 IP；跨连接的账号失败计数与锁定保留 |
| 客户端无模组 | ✅ 用 26.2 协议机器人走完 握手 → 登录 → 配置 → 对话框 → Play，未安装客户端模组 |
| 离线皮肤 | ✅ 正版名取 Mojang signed textures；非正版名 LittleSkin + MineSkin signed |
| 披风/鞘翅 | ✅ Mojang signed 属性里的 CAPE/ELYTRA 原样保留 |
| 正版离线备份密码 | ✅ 正版登录时可 /register 设置；正版不可用时在对话框验证密码，随后原版 transfer 到正版 UUID/存档 |
| Limbo 维度 | ✅ 已完全移除；不再注册自定义维度、不冻结玩家、不拦截数据包 |
| 旧世界兼容 | ✅ jar 内保留空 data/plogin 数据包目录，避免 Missing data pack；旧 limbo 坐标回退主世界出生点 |

> 本轮验证使用无界面 26.2 协议机器人（见 §12），真实 GUI 客户端的对话框渲染由原版 Dialog 界面保证。

---

## 1. 功能

- **混合登录**：`online-mode=true` 时仍接受离线玩家。
  - 先用服务器 RSA 公钥完成原版加密握手（客户端无需模组）。
  - 然后照常调用 Mojang `hasJoinedServer`：
    - 成功 → 正版玩家，UUID 直接用 Mojang 返回值，`textures` 属性保留，直接进入服务器。
    - 返回空（无有效会话）→ 离线玩家，使用 `UUIDUtil.createOfflineProfile(name)` 的离线 UUID。
    - 会话服务器不可达/超时 → 可配置为按离线玩家放行（默认开启）。
- **配置阶段对话框认证**：
  - 离线玩家进入 Configuration 状态后，PLogin 在注册表同步之后、PrepareSpawn 之前插入认证任务；
  - 用原版 Dialog 要求注册（密码 + 确认密码）或登录（密码）；
  - 密码错误、长度不足、两次不一致等会重发带错误提示的对话框；
  - 「退出服务器」按钮直接断开；超时按配置断开或继续等待。
- **正版零打扰**：`premiumBypass=true` 时正版玩家不弹对话框，直接进入。
- **正版名保护**：记录成功验证过的正版名字，离线玩家不能再冒用；同名在线时按规则踢人。
- **正版离线备份密码 + transfer**：正版玩家可用 /register 设置；离线登录密码通过后先 clear_dialog，再用原版 transfer 重连到同一服务端，登录阶段换成正版 UUID/存档。
- **离线皮肤**：正版名取 Mojang signed textures；非正版名走 LittleSkin + MineSkin 签名。
- **密码安全**：PBKDF2-HMAC-SHA256（JDK 自带，默认 120000 次迭代、每个账号独立 16 字节盐）。
- **防爆破**：IP 维度 + 账号维度跨连接失败计数、临时封禁/锁定；密码错误不会重置。
- **中英双语**：`config.json` 的 `language` 可切 `zh_cn` / `en_us`，对话框文字由服务端下发。
- **管理员命令**：/plogin reload|list|info|unregister|resetpassword。

---

## 2. 工作原理

### 2.1 原版登录流程（26.2）

`ServerLoginPacketListenerImpl`：

1. `handleHello`：`usesAuthentication() == true` → 发送 `ClientboundHelloPacket`（RSA 公钥 + challenge）。
2. `handleKey`：解密客户端发来的 AES 密钥和 challenge，启用加密，开一个线程执行
   `server.services().sessionService().hasJoinedServer(name, digest, ip)`。
3. 成功 → 用 Mojang 返回的 `GameProfile`（含 `textures`）；失败 → 原版直接断开。
4. 客户端发送 `ServerboundLoginAcknowledgedPacket` 后，服务端调用
   `ServerConfigurationPacketListenerImpl#startConfiguration()` 进入 **Configuration** 阶段。

Configuration 阶段的任务队列（原版顺序）：

```
SynchronizeRegistriesTask          // 同步注册表/数据包
ServerCodeOfConduct*               // 可选
ServerResourcePack*                // 可选，服主配置了资源包时
PrepareSpawnTask                   // 读取 playerdata、选出生点、加载出生区块
JoinWorldTask                      // 发送 ClientboundFinishConfigurationPacket
```

客户端回复 `ServerboundFinishConfigurationPacket` 后，`handleConfigurationFinished`
执行 `prepareSpawnTask.spawnPlayer(...)` → `PlayerList.placeNewPlayer` → 玩家真正进入世界。

> Fabric API 的 `ServerConfigurationNetworkAddon` 会在原版 `startConfiguration` 之前
> 先发送一个频道注册包 + `ClientboundPingPacket(16430876)`，收到客户端的 pong/注册应答后
> 才调用真正的 `startConfiguration`（见 `receiveRegistration` / `onPong`）。
> PLogin 的 `returnToWorld` Mixin 在之后运行，因此不需要自己处理这个握手。

### 2.2 PLogin 的改动点

**登录阶段（沿用 1.0.x，未改）**

- `LoginThreadMixin`：把原版 `hasJoinedServer` 的调用替换为 `HybridAuth.verify`。
  - 成功：原样返回 `ProfileResult` → UUID、`textures`、`profileActions` 全部保留。
  - 返回 null：`UUIDUtil.createOfflineProfile(name)` → 离线 UUID。
  - `AuthenticationUnavailableException`：若配置允许，则同样回退离线；否则重新抛出。
  - `transferred=true` 且该名字有离线备份记录时：先向客户端请求 `plogin:transfer` Cookie；
    凭据有效则直接返回记录里的正版 UUID（不走 `hasJoined`），无效则返回 transfer 失败标记。
- `LoginCookieMixin`：登录阶段截获 `plogin:transfer` Cookie 响应，存入 `TransferState`（按 Connection 隔离）。
- `LoginHelloMixin`：对离线 profileId 的客户端把 Encryption Request 的 `shouldAuthenticate` 设为 false，
  避免离线客户端在客户端侧 joinServer 报“无效会话”。
- `DedicatedServerMixin`：`forceDisableSecureProfile=true` 时强制 `enforceSecureProfile()=false`。

**配置阶段（1.1.0 新增）**

`ServerConfigurationPacketListenerImpl#returnToWorld()` 在原版里负责把
`PrepareSpawnTask` 和 `JoinWorldTask` 加入队列。PLogin 在方法 **HEAD** 处注入：

```java
@Inject(method = "returnToWorld", at = @At("HEAD"), cancellable = true)
private void plogin$enqueueAuthTask(CallbackInfo ci) {
    StartResult result = PLogin.configAuth().begin(listener);
    if (result.task() != null) {
        configurationTasks.add(result.task());   // 排在 PrepareSpawn 之前
    } else if (result.abort()) {
        ci.cancel();                              // 已经断开连接，不再继续
    }
}
```

`ConfigAuth.begin` 负责：

1. 从 `Connection` 取远端 IP；
2. 检查 IP 封禁、账号锁定；
3. 判断正版/离线/离线备份/备份已验证；
4. 处理正版名保护、UUID 冲突、同名在线；
5. 正版直通、备份已验证直通、requireRegistration=false 直通；
6. 需要密码时创建 `Session` 并返回 `AuthConfigurationTask`（`type().id() = "plogin:auth"`）；
7. IP 封禁等直接断开时返回 `abort`（Mixin 取消 `returnToWorld`）。

**认证任务**

```java
public final class AuthConfigurationTask implements ConfigurationTask {
    public static final Type TYPE = new Type("plogin:auth");

    @Override public void start(Consumer<Packet<?>> sender) { session.showDialog(); }
    @Override public boolean tick() { return session.tick(); }
    @Override public Type type() { return TYPE; }
}
```

- `start()` 在 SynchronizeRegistriesTask 完成后被调用，此时客户端已收到注册表数据，
  可以安全地显示对话框。
- `tick()` 由 `ServerConfigurationPacketListenerImpl#tick()` 每 tick 调用：
  - 已认证/已断开 → 返回 true，原版 `finishCurrentTask` 继续 PrepareSpawn/JoinWorld；
  - 未完成 → 检查超时，返回 false；
  - 超时且 `kickOnTimeout=true` → 先发 `clear_dialog`，50ms 后 disconnect；tick 返回 false，任务不再前进。
- 认证成功时 `Session#succeed()` 发送 `ClientboundClearDialogPacket.INSTANCE`，
  下一 tick 返回 true，原版继续把玩家放进世界。

### 2.3 对话框协议（原版 Dialog，无动画/JWE）

对话框直接使用 26.2 的服务端 Dialog 模型，编码进 `ClientboundShowDialogPacket`：

```java
CommonDialogData common = new CommonDialogData(
        Component.literal(title),
        Optional.empty(),          // external_title
        false,                     // can_close_with_escape
        false,                     // pause
        DialogAction.NONE,         // after_action
        body,                      // List<DialogBody>，PlainMessage
        inputs);                   // List<Input>，TextInput
new ConfirmationDialog(common, yesButton, noButton);
```

- 按钮动作：`CustomAll(Identifier, Optional.empty())` → 线上编码为 `minecraft:dynamic/custom`；
  客户端点击时把输入框的值写进 payload 的 CompoundTag。
- 动作 id：
  - `plogin:login`：提交登录密码（键 `password`）；
  - `plogin:register`：提交注册（键 `password`、`password_confirm`）；
  - `plogin:disconnect`：退出服务器。
- 客户端回包：`ServerboundCustomClickActionPacket(Identifier id, Optional<Tag> payload)`。
- 服务端在 `ServerCommonPacketListenerImpl#handleCustomClickAction()` 的 HEAD 拦截：
  - 只在服务端主线程处理；网络线程上的包先交给原版 `PacketUtils.ensureRunningOnSameThread` 重新调度
    （`Connection#channelRead0` 会捕获 `RunningOnDifferentThreadException`）；
  - 是 plogin 的 id 且属于当前配置会话 → 消费并 `ci.cancel()`，原版只会打一条 debug 日志。

**为什么不用打字动画**：需求明确不实现对话框动画；每次状态变化直接重发完整对话框，
客户端会自动替换当前界面。**为什么不引入 JWE**：备份密码通过后用原版 transfer 重连到同一服务端，
一次性凭据走客户端 Cookie（16 字节随机数 + 服务端 TTL/IP/一次性校验），不需要额外签名令牌格式。
所有断开/转移动作都先发 `ClientboundClearDialogPacket`，避免客户端停在自定义屏幕上无法操作。

### 2.4 认证状态机（`ConfigAuth`）

```
Configuration 开始
 ├─ 正版验证通过且 premiumBypass=true        → 直通（记正版名、踢同名离线、JOIN 欢迎标题）
 ├─ transfer 凭据有效（TRANSFER_MARKER）       → 直通（校验正版记录、JOIN 备份欢迎）
 ├─ transfer 凭据无效（TRANSFER_FAILED）       → 明确断开，提示重新连接
 ├─ 未注册且 requireRegistration=false        → 直通
 └─ 需要密码
     ├─ 有 users.json 记录 → PENDING_LOGIN  （登录对话框）
     └─ 无记录             → PENDING_REGISTER（注册对话框）
              │
              ├─ plogin:login 密码正确
              │    ├─ 有 premiumBackup 记录 → StoreCookie → clear_dialog → transfer（正版 UUID/存档）
              │    └─ 普通离线账号           → touchLogin → succeed（离线 UUID）
              ├─ 密码错误               → 计数 + 重发带错误提示的对话框
              ├─ 注册成功               → 写 users.json → succeed
              ├─ 退出按钮               → clear_dialog → 50ms 后 disconnect
              └─ 超时                   → clear_dialog → 50ms 后 disconnect（或按配置继续等待）
```

- 会话按 `ServerConfigurationPacketListenerImpl` 实例隔离，点击回包只能落在自己的连接上；
- 哈希在 `PLogin-Auth` 线程池执行，完成后通过 `server.execute` 回到主线程更新状态；
- 认证成功后立即从会话表移除，配置监听器随后会被游戏监听器替换（不会再回调 onDisconnect），
  这样不会为每个进入世界的玩家残留 Session。

### 2.5 离线客户端“无效会话”修复（`LoginHelloMixin`）

**现象**：正版客户端能进，离线客户端报
“登录失败：无效会话（请尝试重启游戏及启动器）”，服务端日志只有
“lost connection: Disconnected”，没有 “UUID of player”。

**原因**：原版 Encryption Request 里有 `Should authenticate` 布尔字段。服务端默认发 true，
客户端就会拿本地令牌调用 Mojang joinServer。离线启动器的令牌无效，客户端在收到 401 后直接断开，
根本不会发送 Encryption Response，所以服务端的 hasJoined 回退根本没有机会执行。

**修复**：对 `ServerboundHelloPacket.profileId()` 是离线 UUID 的客户端，把 `Should authenticate` 改成 false：

```java
@ModifyArg(method = "handleHello",
    at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/login/ClientboundHelloPacket;<init>(Ljava/lang/String;[B[BZ)V"),
    index = 3)
private boolean plogin$modifyShouldAuthenticate(boolean original) {
    return this.plogin$skipClientAuth ? false : original;
}
```

判定：

- `profileId == UUIDUtil.createOfflinePlayerUUID(name)` 或 `profileId.version() == 3` → 离线客户端 → false；
- 否则（正版 v4 UUID / 随机 UUID）→ 保持 true；
- 如果这个名字已在 `premium-names.json` 里记录为正版 → 强制 true，防止离线冒名；
- 有离线备份密码的正版名：允许离线客户端跳过 joinServer，进来后在对话框输入备份密码；
- 已记录的正版名 + 没有离线备份密码：不再发送 Encryption Request，直接在登录阶段
  `disconnect` 并下发 `premium.name` 的明确原因。否则离线令牌 joinServer 失败时，
  客户端只会显示“无效会话”，玩家看不到真正原因。

### 2.6 离线皮肤（参考 SkinsRestorer / MCCustomSkinLoader）

**原版限制**：客户端对“其他玩家”的皮肤要求签名有效。MCCustomSkinLoader 是客户端模组；
SkinsRestorer 是服务端方案，PLogin 采用同样的服务端思路（客户端零模组）：

1. `api.mojang.com/users/profiles/minecraft/<name>` 查名字；
   是正版账号 → `sessionserver/.../profile/<uuid>?unsigned=false` 取 signed `textures`；
2. 否则请求 `offlineSkinSiteRoot + <name>.json`（LittleSkin / Blessing Skin 的 CustomSkinAPI），
   取 `<root>textures/<hash>`；
3. 用 MineSkin v2 `/generate` 把该 URL 签成 Mojang 可验证的 `textures`；
4. 无皮肤/MineSkin 失败 → 默认皮肤；可选择未签名 fallback（通常只有本机可见）。

调用点在 `HybridAuth.verify` 的离线分支，必须在 LoginFinished 发送前完成：

```java
offlineProfile = SkinService.applyOfflineSkin(UUIDUtil.createOfflineProfile(name), maxWaitMs);
return new ProfileResult(offlineProfile);
```

**披风 / 鞘翅**：Mojang signed attributes 可以同时包含 SKIN/CAPE/ELYTRA，PLogin 原样返回；
LittleSkin 的 cape/elytra 会写入未签名属性，MineSkin 不支持任意披风 PNG 签名，
只有 `offlineSkinAllowUnsignedFallback=true` 时才会下发（通常只有本机可见）。

### 2.7 正版离线备份密码

目的：正版玩家事先设置一个密码；当正版验证不可用/他自己用离线启动时，
仍能用同一个名字离线进入，并用密码登录。

**设置（正版验证成功时）**：进服后执行 `/register <密码> <确认密码>`（只有正版玩家可设置）。

写入 `users.json`：

```json
{
  "name": "PremiumTester",
  "uuid": "<正版 UUID>",
  "offlineUuid": "<OfflinePlayer:PremiumTester 的 UUID>",
  "premiumBackup": true,
  "salt": "...",
  "hash": "...",
  "iterations": 120000
}
```

**之后的行为**：

| 登录方式 | 结果 |
|---|---|
| 正版客户端 / hasJoined 成功 | 原版流程，正版 UUID + 皮肤 + 披风，不需要密码 |
| 正版不可用 / 离线客户端 | 使用离线 UUID 进入 Configuration，显示登录对话框 |
| 密码通过（有 premiumBackup 记录） | 发一次性 Cookie → clear_dialog → `ClientboundTransferPacket` → 客户端重连 |
| transfer 重连 | handshake intention=TRANSFER + Cookie → 登录阶段换成正版 UUID → 跳过对话框 → 正版存档 |
| 正版同名在线 | 拒绝这次备份密码登录并提示等待，不踢对方 |

**关键点**：

- `HybridAuth` 先给离线 profile 注入 `plogin:offline_backup` 标记，首次连接仍是离线 UUID；
- 密码在 Configuration 对话框里验证，通过后 `ConfigAuth` 检查记录、正版同名在线、transfer 目标 host/port；
- 发送顺序：`ClientboundStoreCookiePacket(plogin:transfer, secret)` → `clear_dialog` →
  50ms 后 `ClientboundTransferPacket(host, port)`；
- 客户端重连时 handshake intention=TRANSFER（`ServerLoginPacketListenerImpl.transferred=true`）；
  `LoginThreadMixin` 向客户端请求 Cookie，`TransferVerification.consume` 校验并取出记录里的正版 UUID；
- `HybridAuth` 不再调用 `hasJoined`，直接返回带 `plogin:transfer_verified` 标记的正版 profile；
  `ConfigAuth` 跳过对话框，`PrepareSpawnTask` 加载正版 UUID 的 playerdata；
- 凭据是 16 字节 SecureRandom，默认 120 秒过期、绑定 IP、一次性消费；同一服务端的 transfer 不需要 JWE；
- **必须**在 `server.properties` 设置 `accepts-transfers=true`，否则原版会在登录阶段拒绝 TRANSFER 连接；
- transfer 目标 host 优先用客户端连接时的 handshake host（兼容域名/NAT/反代），可用 `transferHost`
  覆盖；端口默认服务器端口，可用 `transferPort` 覆盖；无法确定时回退为离线 UUID 登录并 WARN；
- 正版同名玩家在线时，`ConfigAuth` 直接拒绝这次离线备份登录
  （`premium.backup.premium_online`），等对方下线后再试；
- 没设置备份密码的正版名，在 `premiumNameProtection=true` 时仍然禁止离线进入。

> **为什么用 transfer 而不是直接 disconnect**：26.2 的 GameProfile UUID 在 Login 阶段固定，切换 UUID
> 必须新连接。直接在 Configuration 对话框上 disconnect 会让客户端停在自定义屏幕、Cookie 也可能没写进去，
> 形成“输密码 → 被踢 → 重连还要输密码”的循环。`ClientboundTransferPacket` 让客户端走原版转移界面，
> 干净地重建连接，并把一次性 Cookie 带回来；因此所有断开/转移前都先 `clear_dialog`。

### 2.8 移除 limbo 后的行为

1.0.x 的做法是让未登录玩家进入 `plogin:limbo` 虚空维度，冻结移动、失明、隐藏 Tab、拦截数据包。
1.1.0 把认证前移到 Configuration 阶段，玩家在通过认证前**不会生成 ServerPlayer**，
因此：

- 不需要 limbo 维度（`src/data/plogin/dimension*` 已删除）；
- 不需要冻结/失明/无敌/血量恢复；
- 不需要隐藏 Tab、隐藏 joined/left 消息；
- 不需要在 `Connection#channelRead0` 过滤移动/交互/容器包；
- 不需要拦截 /tp、/kill、/damage 命令和死亡重生；
- 不需要 /logout 后重新进入 limbo；/logout 改为断开连接，重连后重新走对话框。

旧版本遗留的 `Invulnerable`/`NoGravity` 标记会在 `ServerPlayConnectionEvents.JOIN` 时清理；
旧 playerdata 若仍指向 `plogin:limbo`，`PrepareSpawnTask` 找不到该维度时会回退到主世界出生点。

### 2.9 Mixin 清单（1.1.0）

| Mixin | 目标 | 作用 |
|---|---|---|
| `LoginThreadMixin` | `ServerLoginPacketListenerImpl$1#run` | 替换 hasJoinedServer 为 HybridAuth.verify |
| `LoginHelloMixin` | `ServerLoginPacketListenerImpl#handleHello` | 离线客户端 shouldAuthenticate=false |
| `LoginCookieMixin` | `ServerLoginPacketListenerImpl` | 登录阶段截获 transfer Cookie 响应/清理 |
| `ServerLoginPacketListenerAccessor` | `ServerLoginPacketListenerImpl` | 读取 `transferred`（是否 transfer 重连） |
| `ServerHandshakePacketListenerImplMixin` | `ServerHandshakePacketListenerImpl` | 记录客户端连接时使用的 host，供 transfer 回自己 |
| `DedicatedServerMixin` | `DedicatedServer#enforceSecureProfile` | 强制关闭 secure profile |
| `ServerConfigurationPacketListenerImplMixin` | `ServerConfigurationPacketListenerImpl#returnToWorld` | 在 PrepareSpawn 前插入认证任务 |
| `ServerCommonPacketListenerMixin` | `ServerCommonPacketListenerImpl#handleCustomClickAction / onDisconnect` | 处理对话框按钮回包、清理会话 |
| `ServerGamePacketListenerImplMixin` | `ServerGamePacketListenerImpl#switchToConfig` | 标记 debugconfig 重配置连接，避免重复弹框 |
| `ServerCommonPacketListenerAccessor` | `ServerCommonPacketListenerImpl` | 读取底层 Connection（@Accessor） |

---

## 3. 目录结构

```
PLogin/
├── README.md                       ← 面向服主的使用说明
├── LICENSE                         ← AGPL-3.0
├── build.sh / build.ps1            ← 26.x：无 Gradle 的直接 javac 构建脚本
├── build.gradle.kts / settings.gradle.kts / gradlew*
│                                   ← 1.21.x：Loom 构建（源码补丁 + remap 到 intermediary）
├── docs/
│   ├── TECHNICAL.md                ← 本文件
│   └── 文案.md                      ← 宣传/玩家提示/公告/命令文案汇总
├── src/
│   ├── fabric.mod.json
│   ├── plogin.mixins.json
│   ├── data/plogin/README.txt      ← 旧数据包占位（避免 Missing data pack）
│   └── com/pimeng/plogin/
│       ├── PLogin.java / PLoginMod.java / Messages.java
│       ├── auth/  (ConfigAuth, AuthConfigurationTask, AuthDialogs, AccountService,
│       │            HybridAuth, TransferVerification, TransferState,
│       │            PasswordHasher, UserStore, UserRecord)
│       ├── command/AuthCommands.java
│       ├── config/Config.java
│       ├── skin/SkinService.java
│       ├── mixin/  (8 个 Mixin/Accessor)
│       └── util/Ui.java
└── build/                          ← 构建产物（不入库）
    ├── plogin-1.1.1.jar
    └── classes/
```

---

## 4. 构建

环境：Java 25（Minecraft 26.x 的最低要求），Minecraft 26.x 服务端 jar（Mojang mappings），Fabric API。
默认构建目标是 26.2；其他版本见下方「多版本构建」。

```bash
./build.sh        # Linux / macOS
```

```powershell
.\build.ps1       # Windows
```

产物：`build/plogin-1.1.1.jar`

### 多版本构建

发布用的按版本 jar 用 `scripts/build-version.ps1` 构建，产物命名为
`PLogin-<Minecraft 版本>-v<模组版本>.jar`：

```powershell
.\scripts\build-version.ps1 -McVersion 26.1
.\scripts\build-version.ps1 -McVersion 26.3
```

脚本会复制 `src/`、按版本打源码补丁（目前只有 26.3 需要：authlib 10 的
`SessionService` / `services.ProfileResult`、`Entity#setPermanentlyInvulnerable`、
Mixin `@Redirect` 描述符）、改写 `fabric.mod.json` 的版本号，再用对应版本的
`.mcserver-<版本>/` 依赖编译。可行性分析见[版本适配可行性](版本适配可行性.md)。

首次构建可以直接跑一键脚本，它会下载 26.2 服务端 jar、131 个 Mojang 库、Fabric Loader 与 Fabric API
到 `.mcserver/`，并拆分 Fabric API 的嵌套模块，然后自动调用构建：

```bash
./scripts/fetch-deps.sh --build
```

```powershell
.\scripts\fetch-deps.ps1 -Build
```

`build.sh` 需要一个「已安装好的服务端目录」（下称 `MC_SERVER`），从中取：

- `versions/26.2/server-26.2.jar`
- `mods/fabric-api-*.jar`
- `.fabric/processedMods/*.jar`（Fabric API 各子模块）
- `libraries/**/*.jar`

未设置 `MC_SERVER` 时，脚本依次尝试仓库下的 `.mcserver/`、`../minecraft-bingo`、`HOME/mc/minecraft-bingo`；换机器构建时显式指定：

```bash
MC_SERVER=/path/to/minecraft-server ./build.sh
```

不需要 Gradle/Loom；26.x 的 Fabric Loader 使用 Mojang 官方名（启动日志 `Mappings not present!`），
因此源码直接用 `net.minecraft.server.level.ServerPlayer` 这类 Mojang 名。

> 仓库不附带 Minecraft 服务端 jar（Mojang 版权产物），`versions/`、`build/` 已在 `.gitignore` 中排除。

### 1.21.x 构建（Gradle + Loom）

1.21.x 与 26.x 的差别不在业务逻辑，而在**命名空间**：服务端 jar 是混淆的，Fabric 运行时是
intermediary（`net.fabricmc:intermediary:1.21.10`）。所以 `javac` 直编出来的 class 在 1.21.10 上
既找不到 `net.minecraft.server.MinecraftServer`，`@Inject` 里的 Mojmap 方法名也指不到任何东西。

`build.gradle.kts` 的处理顺序：

1. `patchSources`：把 `src/` 复制到 `build/generated/plogin-src-<版本>/`，按版本打命名补丁
   （1.21.9 / 1.21.10：`Identifier` → `ResourceLocation`、
   `source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)` → `source.hasPermission(3)`），
   并改写 `fabric.mod.json` 的 `depends.minecraft` 与版本号；
2. `loom.officialMojangMappings()` 反混淆服务端 jar，`fabric-api` 由 Loom 自动从 intermediary remap；
3. `remapJar` 把产物 class 与 Mixin 注解字符串一起 remap 到 intermediary。Loom 1.19 起不再默认启用
   Mixin 注解处理器，也不再生成 refmap：`plogin.mixins.json` 原样进 jar，注解里的
   `returnToWorld` / `configurationTasks` 这类 Mojmap 名字由 Loom 直接改写成
   `method_52410` / `field_45023`。

```powershell
.\gradlew build                    # 默认 1.21.10（build.gradle.kts 顶部）
.\gradlew build -Pmc_version=1.21.11 -Pfabric_api_version=0.141.6+1.21.11
```

产物 `build/libs/PLogin-1.21.10-v1.1.1.jar`。已实测：Gradle 9.8.2 + Loom 1.19.3 构建通过，
`gradlew runServer` 在 1.21.10 开发服务端上正常加载（Mixin 无 apply failed，
`enforce-secure-profile` 有效值被 `DedicatedServerMixin` 改成 false）。

1.21.10 上额外的两处源码修正，都是「按 Mojmap 名字反射」在 intermediary 运行时失效：

| 位置 | 原实现 | 1.21.10 实际 | 现在 |
|---|---|---|---|
| `TransferState.connectionOf` | `getDeclaredField("connection")` | 字段是 `field_xxxxx` | 走 `ServerCommonPacketListenerAccessor`（Mixin 会被 remap） |
| `LoginThreadMixin.plogin$listener` | `getDeclaredField("this$0")` | `ServerLoginPacketListenerImpl$1` 里持有监听器的字段是带映射的 `field_14176`（`Lnet/minecraft/class_3248;`），根本没有 `this$0` | 扫描声明字段，取「非静态且类型是 `ServerLoginPacketListenerImpl`」的那个；并且只在名字登记过离线备份密码时才查找 |

> 判断字段类型在两个命名空间都成立：我们的 `ServerLoginPacketListenerImpl` 引用和字段类型
> 都会被 remap 到同一个 intermediary 类。

> 1.21.4 / 1.21.5 没有 Dialog 系统，1.21.6–1.21.8 有 Dialog 但 `ConfigurationTask#tick()` 尚未加入，
> 都无法承载「配置阶段对话框等待输入」这套流程。1.21.9 起才齐备，详见
> [版本适配可行性](版本适配可行性.md)。

---

## 5. 部署

1. **服务端**：
   - Fabric Loader >= 0.19.5，Fabric API；
   - 把对应版本的 `PLogin-<Minecraft 版本>-v1.1.1.jar` 放进 `mods/`（本地开发构建为 `build/plogin-1.1.1.jar`）；
   - `server.properties` 保持 `online-mode=true`（混合登录的前提）。
2. **客户端**：什么都不用装。正版客户端直接连；离线启动器客户端也能连，并在配置阶段看到对话框。
3. **备份密码 transfer 需要**：
   - `server.properties` 设置 `accepts-transfers=true`（否则原版会拒绝 TRANSFER 连接，启动日志会 WARN）；
   - 如果服务器在反向代理/端口映射后面，按需设置 `transferHost` / `transferPort`；
     直连服务器通常留空即可（使用客户端连接时的 host + 服务器端口）。
4. **强烈建议**：
   - 删除 JVM 参数里的 `-Djava.net.preferIPv6Addresses=true`（原因见第 11 节）；
   - `enforce-secure-profile` 建议 false；即使设为 true，模组在
     `forceDisableSecureProfile=true`（默认）时也会让它有效值为 false；
   - 备份 `config/plogin/`。
5. 重启服务器，日志应出现：

```text
   [PLogin] 已加载 v1.1.1，数据目录：./config/plogin
   [PLogin] 混合登录已启用（online-mode=true）：正版玩家验证后直接进入，离线玩家在配置阶段通过对话框注册/登录
```

---

## 6. 配置

首次启动自动生成，缺字段自动补全。1.1.0 已移除 `blindness` 与 `freezeMovement`（旧配置里的这两个字段会被忽略）。

```json
{
  "enabled": true,
  "premiumBypass": true,
  "premiumPasswordBackup": true,
  "fallbackToOfflineWhenAuthServerDown": true,
  "skipClientAuthForOfflineClients": true,
  "forceDisableSecureProfile": true,
  "requireRegistration": true,
  "passwordMinLength": 4,
  "passwordMaxLength": 64,
  "passwordIterations": 120000,
  "loginTimeoutSeconds": 120,
  "kickOnTimeout": true,
  "maxLoginAttempts": 5,
  "ipBanSeconds": 300,
  "failureWindowSeconds": 1800,
  "maxAccountFailures": 15,
  "accountLockSeconds": 300,
  "transferEnabled": true,
  "transferHost": "",
  "transferPort": 0,
  "transferTokenSeconds": 120,
  "transferBindIp": true,
  "transferTokenWaitMs": 1000,
  "offlineSkinMaxWaitMs": 3000,
  "language": "zh_cn",
  "premiumNameProtection": true,
  "offlineSkinsEnabled": true,
  "offlineSkinProvider": "auto",
  "offlineSkinSiteRoot": "https://littleskin.cn/csl/",
  "offlineSkinMineSkinApiKey": "",
  "offlineSkinMineSkinSigning": true,
  "offlineSkinAllowUnsignedFallback": false,
  "offlineSkinTimeoutSeconds": 15,
  "offlineSkinCacheMinutes": 360
}
```

| 字段 | 说明 |
|---|---|
| `enabled` | 总开关；关掉后恢复原版登录行为 |
| `premiumBypass` | 正版玩家跳过注册/登录 |
| `fallbackToOfflineWhenAuthServerDown` | 会话服务器不可达时是否按离线放行；关闭则保持原版“验证服务器不可用”踢出 |
| `skipClientAuthForOfflineClients` | 对离线 profileId 的客户端把 shouldAuthenticate 设为 false |
| `forceDisableSecureProfile` | 强制 `enforce-secure-profile=false` |
| `requireRegistration` | 离线玩家是否必须注册 |
| `passwordMinLength/MaxLength/Iterations` | 密码长度与 PBKDF2 迭代次数 |
| `loginTimeoutSeconds` | 登录对话框超时（秒），0 = 不超时 |
| `kickOnTimeout` | true = 超时断开；false = 不超时，保留对话框继续等待 |
| `maxLoginAttempts` / `ipBanSeconds` | IP 维度错误次数 / 临时封禁秒数 |
| `failureWindowSeconds` / `maxAccountFailures` / `accountLockSeconds` | 账号维度跨连接失败计数与锁定 |
| `transferEnabled` | 备份密码通过后是否用原版 transfer 切换到正版 UUID/存档 |
| `transferHost` / `transferPort` | transfer 目标；空/0 = 客户端连接时的 host / 服务器端口 |
| `transferTokenSeconds` / `transferBindIp` / `transferTokenWaitMs` | 一次性凭据有效期 / 绑定 IP / 登录阶段等待 Cookie 毫秒数 |
| `language` | zh_cn 或 en_us |
| `premiumNameProtection` | 已记录正版名禁止离线冒用 |
| `premiumPasswordBackup` | 允许正版玩家 /register 设置离线备份密码 |
| `offlineSkins*` | 离线皮肤开关、来源、MineSkin 签名与缓存 |

---

## 7. 命令

### 玩家

| 命令 | 说明 |
|---|---|
| `/register <密码> <确认密码>` | 仅正版玩家：设置/更新离线备份密码 |
| `/logout` | 断开连接，重连后重新走登录对话框 |
| `/changepassword <旧密码> <新密码>` | 修改密码（离线账号或正版备份密码） |
| 别名 | `/reg`、`/cp`（若未被其他模组占用） |

> 注册/登录在连接阶段完成，不再提供 `/login`。

### 管理员（permission 3 / OP）

| 命令 | 说明 |
|---|---|
| `/plogin reload` | 重载配置与语言 |
| `/plogin list` | 已注册用户数 |
| `/plogin info <玩家>` | UUID / 类型（premium/offline）/ 状态 / 最后登录 |
| `/plogin unregister <玩家>` | 删除注册记录；在线玩家重新连接后生效 |
| `/plogin resetpassword <玩家> <新密码>` | 重置离线密码 |

---

## 8. 数据文件

- `config.json`：配置。
- `users.json`：离线账号与正版备份密码。

```json
  {
    "version": 1,
    "users": {
      "finaltest": {
        "name": "FinalTest",
        "uuid": "7933546b-96dd-38a6-bbf6-c8e78cf0fcaf",
        "salt": "...",
        "hash": "...",
        "iterations": 120000,
        "createdAt": 1793766426453,
        "lastLoginAt": 1793766426453,
        "lastIp": "127.0.0.1"
      }
    }
  }
```

- `premium-names.json`：成功通过 `hasJoined` 的正版名字 → 正版 UUID，用于离线冒名保护。

> 密码只保存 PBKDF2 盐和哈希，不保存明文。

---

## 9. 安全设计

1. **不绕过加密**：即使离线玩家也要完成原版 RSA/AES 握手，再判断 `hasJoined` 结果；协议与正版客户端一致。
2. **不信任客户端 UUID**：离线 UUID 由服务端用 `OfflinePlayer:<name>` 计算，不采用客户端
   `ServerboundHelloPacket` 里携带的 UUID。
3. **正版名字保护**：正版验证成功后记录名字；之后同名离线登录 → 直接踢出；
   正版玩家上线时，若同名的离线账号在线，也会被踢掉。已知局限：某个正版名字第一次登录前，
   离线玩家理论上可以抢注同名（正版一登录就会被踢，之后被永久阻止）。
4. **认证前不进入世界**：配置阶段任务阻塞 PrepareSpawn/JoinWorld，未认证连接没有 ServerPlayer，
   不在 PlayerList/Tab/世界中；不存在“未登录玩家数据包”漏网的问题。
5. **对话框回包隔离**：会话按 `ServerConfigurationPacketListenerImpl` 实例保存，动作只在当前连接内生效；
   客户端伪造同名动作也只能影响自己的登录流程。
6. **断开顺序**：配置阶段任何 disconnect（超时、退出、IP 封禁、账号锁定、同名冲突）都先发送
   `clear_dialog`，50ms 后再断开；否则客户端会停在自定义屏幕，按钮无响应，只能点客户端自带断开。
7. **transfer 凭据**：密码通过后发放 16 字节 SecureRandom，经原版 Cookie 下发；默认 120 秒、
   绑定 IP、一次性消费。登录阶段只有带有效凭据的 TRANSFER 连接才会换成正版 UUID，
   正版 UUID 只来自 `users.json` 的备份记录，不采用客户端输入；正版同名在线时拒绝而不是踢人。
8. **密码哈希**：PBKDF2-HMAC-SHA256，120000 次，16 字节盐，`MessageDigest.isEqual` 常数时间比较。
9. **防爆破**：IP 维度 + 账号维度跨连接计数、临时封禁/锁定；哈希线程池只有 2 个线程；
   空密码/超长密码不进入 PBKDF2。
10. **关闭 secure profile**：离线玩家没有 Mojang 签名的聊天密钥；强制 `enforceSecureProfile=false`
    让所有人可以正常聊天（代价：没有签名聊天/聊天举报链）。

---

## 10. 正版皮肤为什么能正常显示

- 原版 `hasJoinedServer` 成功的响应包含
  `properties: [{"name":"textures","value":"...","signature":"..."}]`。
- PLogin 的 Mixin **原样返回**这个 `ProfileResult`，没有重建/裁剪 `GameProfile`。
- 服务端在 `ClientboundLoginFinishedPacket` 和 `ClientboundPlayerInfoUpdatePacket`
  中把 `GameProfile`（含 textures）发给客户端。
- 原版客户端根据 textures 里的签名 URL 下载并渲染皮肤；**客户端不需要 PLogin**。

离线玩家由 `SkinService` 注入皮肤（见 §2.6）：

- 正版名 → Mojang signed textures，所有玩家可见；
- 非正版名 → LittleSkin CustomSkinAPI → MineSkin 签名，所有玩家可见；
- 找不到皮肤 / MineSkin 失败 → 默认皮肤；
- 未签名 fallback 默认关闭。

---

## 11. JVM 的 IPv6 优先参数

本机排查发现：

```bash
java -Djava.net.preferIPv6Addresses=true NetTest   # sessionserver/api 全部 “网络不可达”
java NetTest                                       # hasJoined 204 / publickeys 200 / profiles 200
```

**如果 `run.sh` 里有 `-Djava.net.preferIPv6Addresses=true`，正版玩家的 `hasJoined` 会失败，
PLogin 会按离线玩家处理 —— UUID 变成离线的、皮肤丢失。**

- 检查启动脚本（run.sh / systemd unit / 面板启动参数）里是否还有这一行；
- 有的话删除后重启服务端。

---

## 12. 测试结果

本轮使用一个基于 26.2 协议类的无界面机器人（不安装客户端模组）验证：

| 场景 | 结果 |
|---|---|
| 服务端启动（online-mode=true / false） | ✅ 模组加载、Mixin 全部应用、无注入错误 |
| Fabric 配置阶段 ping/pong 握手 | ✅ 机器人回 pong 后服务端才调用原版 startConfiguration |
| 注册表同步 → update_tags → show_dialog | ✅ 机器人按顺序收到，对话框在注册表同步后显示 |
| 注册对话框内容 | ✅ 标题/正文/两个 TextInput/注册与退出按钮均正确解码 |
| 注册提交 | ✅ plogin:register → 写入 users.json → clear_dialog → finish_configuration → 玩家加入 |
| 注册校验错误 | ✅ 过短密码收到带“密码长度必须为 4-64 个字符”的新对话框，修正后成功 |
| 登录密码错误 | ✅ 收到“密码错误，还可以尝试 4 次”的新对话框，修正后成功 |
| 防爆破 | ✅ 连续错误达到阈值后 IP 封禁 300 秒并断开，提示原因 |
| 退出按钮 | ✅ 先收到 clear_dialog，再收到 disconnect；提示“你已退出登录界面” |
| 超时 | ✅ loginTimeoutSeconds=3：先收到 clear_dialog，约 3 秒后 disconnect，提示“登录超时” |
| 备份密码 transfer（online-mode=true 加密握手） | ✅ 错误密码 → 错误对话框；正确密码 → StoreCookie → clear_dialog → transfer 包 → TRANSFER 重连 → 正版 UUID → 跳过对话框 → joined |
| transfer 重连的 UUID/存档 | ✅ login finished 与 joined 日志均为 users.json 里的正版 UUID；未再弹对话框 |
| 旧数据包占位 | ✅ 启动显示 Found new data pack plogin，不再 Missing data pack |
| 玩家真正进入世界 | ✅ 服务端日志 BackupBot joined the game；机器人收到 Play 登录包 |

> 正版分支（hasJoined + textures）沿用 1.0.x 的本地伪装会话服务器实测。
> 备份密码 transfer 用本地 users.json 备份记录 + 自写 AES/CFB8 协议机器人完整验证：
> 首次连接走到对话框；密码通过后收到 StoreCookie/clear_dialog/transfer；
> 第二次连接用 TRANSFER intention + Cookie 登录，Login Finished 与 joined 都是正版 UUID。
> 测试服务端开启了 `accepts-transfers=true`；关闭时原版会以“This server does not accept transfers”拒绝。

---

## 13. 测试环境

- 版本：Minecraft 26.2 / Fabric Loader 0.20.1 / Java 25
- 模组：只有 `fabric-api` 与 `plogin`（没有优化或功能类模组）
- 服务端配置：`online-mode` 分别用 true（AES/CFB8 加密机器人）和 false 测过；
  `accepts-transfers=true`（transfer 分支）、`enforce-secure-profile=true`（PLogin 强制有效值 false）、
  `max-players=5`、`view-distance=4` / `simulation-distance=4`
- 资源：2 核 / `-Xms1G -Xmx2G`
- 世界：既有测试世界
- 测试账号：DialogBot1~5、FinalBot1、BackupBot（验证后已删除 playerdata 与 users.json 记录）

---

## 14. 已知限制

1. **会话服务器不可达时默认放行离线**（`fallbackToOfflineWhenAuthServerDown=true`)。
   安全优先的服主可改成 false，但 Mojang 宕机时正版玩家也会被拒。
2. **正版名字首次登录前**存在抢注窗口（见第 9 节）；真实正版一登录就会踢掉冒名者并永久记录。
3. **离线皮肤有前提**：正版名由 Mojang 签名；非正版名需要 LittleSkin 上有同名皮肤，
   再用 MineSkin 签名。没有皮肤、MineSkin 限流/失败时会显示默认皮肤。
   Mojang 的披风/鞘翅随 signed 属性保留；LittleSkin 的披风未签名时其他玩家看不到。
4. **`premiumNameProtection=false` 有安全取舍**：离线玩家可注册正版名并取得正版皮肤，
   真实正版该名字登录时会踢掉离线号；若要禁止离线冒名，保持默认 true。
5. **离线 UUID 对名字大小写敏感**：`OfflinePlayer:FinalTest` 与 `OfflinePlayer:finaltest` 是两个 UUID；
   账号按小写名字匹配，若记录 UUID 与当前 UUID 不一致会提示联系管理员。
6. **secure profile 被强制关闭**：所有玩家的聊天都不再带 Mojang 签名，失去聊天举报链。
7. **IP 封禁只在内存**，重启后清空；用户密码和正版名字持久化。
8. **`kickOnTimeout=false`**：未完成登录的连接会一直占用一个玩家槽位，直到玩家退出或断线。
9. **配置阶段连接**：未认证玩家不在 PlayerList/Tab/世界中，但服务器日志和连接列表仍能看到这条连接；
   其他模组如果遍历 `server.getConnection().getConnections()` 也可能看到。
10. **其他登录/认证模组可能冲突**：同一套登录流程只保留一个认证实现。
11. **旧离线存档不会自动合并**：以前用 OfflinePlayer UUID 登录产生的 playerdata/stats/advancements
    仍留在旧 UUID。1.1.0 的备份密码登录通过 transfer 使用正版 UUID/存档，旧离线 UUID 的存档不会自动合并；
    需要旧进度时手动复制 `world/players/data/<offline-uuid>.dat` 到 `<premium-uuid>.dat`。
12. **对话框依赖 26.2 客户端原版 Dialog 界面**；服务端本来就只接受同协议客户端。
13. **备份密码 transfer 依赖 `accepts-transfers=true`**：未开启时原版拒绝 TRANSFER 连接，
    服务端启动会 WARN，备份密码登录回退为离线 UUID 登录（不切换正版存档）。
14. **transfer 目标**：默认使用客户端连接时的 handshake host + 服务器端口；反代/端口映射环境如果
    客户端无法直连该地址，需要配置 `transferHost`/`transferPort`，否则回退离线 UUID 登录。
13. **原版 Dialog 的文本输入不是密码框**：密码在输入时是可见的（原版没有 password input 控件），
    并且以 NBT 字符串形式随 `ServerboundCustomClickActionPacket` 在已加密的连接中传输。
    旧版本的聊天命令输入同样可见，这一点不是 1.1.0 新增的风险。

---

## 15. 参考

- [QLogin](https://github.com/EpochcraftMC/QLogin)（MIT）：注册/登录命令、冻结保护、密码哈希思路。
- [SkinsRestorer](https://github.com/SkinsRestorer/SkinsRestorer)：服务端皮肤属性注入、MineSkin 签名思路。
- [MCCustomSkinLoader](https://github.com/xfl03/MCCustomSkinLoader)：客户端皮肤加载与 CustomSkinAPI 格式。
- [MineSkin](https://mineskin.org/) / [LittleSkin](https://littleskin.cn/)：离线皮肤 URL 与签名服务。
- SuperLiteLimbo-MVP（本地参考项目，未随仓库分发）：在 Configuration 阶段用原版 Dialog 完成认证的协议参考。
- Minecraft 26.2 `ServerLoginPacketListenerImpl`、`ServerConfigurationPacketListenerImpl`、
  `ServerCommonPacketListenerImpl`、`PlayerList` 反编译源码。

---

## 16. 许可

本项目以 **GNU Affero General Public License v3.0** 发布，全文见 [LICENSE](../LICENSE)。

AGPL 的关键含义（不构成法律意见，以许可证原文为准）：

- 可以自由使用、修改、分发本模组；
- 分发修改版时，必须同样以 AGPL 提供完整源码；
- **把修改版作为网络服务提供给他人使用（例如开一个公开服务器），也必须向使用者提供源码。**

Minecraft 及其相关类的版权归 Mojang；反编译仅用于开发参考。
