# PLogin 技术文档

> 面向开发者与高级运维：记录登录流程改造、Mixin 清单、认证状态机、皮肤注入、数据格式与实测结论。
> 只想把模组装到服务器上的服主，请先读 [README](../README.md)。

**环境基线**：Minecraft 26.2 · Fabric Loader 0.19.5+ · Fabric API · Java 25

> 配套文案（宣传语、玩家提示、公告模板、命令说明）：[文案.md](文案.md)

---

## 目录

- [0. 当前状态](#0-当前状态2026-11-04)
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

## 0. 当前状态（2026-11-04）

| 项目 | 结果 |
|---|---|
| 模组版本 | PLogin 1.0.0（Minecraft 26.2 / Fabric Loader 0.19.5 / Java 27） |
| 正版 UUID | ✅ 通过本地伪装会话服务器实测：UUID = `069a79f4-44e9-4726-a5be-fca90e38aaf5` |
| 正版皮肤属性 | ✅ `textures` 属性随 `LoginFinished` 下发（测试客户端已打印 value + signature） |
| 正版跳过验证 | ✅ 直接进入；`/register` 返回“正版账号已验证，无需注册/登录” |
| 离线玩家加入 | ✅ `online-mode=true` 下正常进入；离线 UUID 客户端下发 `shouldAuthenticate=false`，不再报“无效会话” |
| 离线 UUID 修复 | ✅ `OfflinePlayer:<name>`（UUID v3），实测 `FinalTest -> 7933546b-96dd-38a6-bbf6-c8e78cf0fcaf` |
| 离线注册/登录 | ✅ `/register` 自动登录，`/login` 成功；错误密码提示剩余次数 |
| 未登录保护 | ✅ 移动包被丢弃、聊天/命令/交互/容器包被过滤；测试服实测位置保持在出生点不变 |
| 客户端无模组 | ✅ 测试客户端使用原版协议完成 握手 → RSA/AES → 压缩 → 配置 → Play，未安装任何客户端模组 |
| 离线皮肤 | ✅ 正版名取 Mojang signed textures；非正版名 LittleSkin + MineSkin signed；测试客户端已收到 value+signature |
| 披风/鞘翅 | ✅ Mojang signed 属性里的 CAPE/ELYTRA 原样保留；离线 PremiumTester 实测 `[SKIN, CAPE]` |
| 正版离线备份密码 | ✅ 正版登录时可 `/register` 设置；正版不可用时离线 `/login`，并使用正版 UUID（存档同步已实测） |
| 正版欢迎标题 | ✅ 正版验证成功显示 “正版验证成功，欢迎” + 副标题 “已自动登录” |
| Limbo 隔离 | ✅ 未登录玩家在 Login 前切到 `plogin:limbo` (0.5,65,0.5)，登录后回原位置 |
| 未登录隐藏 | ✅ Tab 列表/主世界不可见；进出消息隐藏，登录后才补发；/tp、/kill、/damage、死亡重生受保护 |
| 测试服 | ✅ 2 核 / 2 GB 堆，纯 Fabric API + PLogin，独立端口实测通过 |

> 唯一没有用真实 GUI 客户端验证的是“人眼看到皮肤/画面”，但服务端下发的 `GameProfile`
> 已包含 Mojang `textures` 属性；原版客户端就是靠这个属性渲染皮肤，见第 10 节。
> 离线玩家现在也会注入皮肤：正版名取 Mojang signed textures，非正版名走 LittleSkin + MineSkin（见 §2.4）。

---

## 1. 功能

- **混合登录**：`online-mode=true` 时仍接受离线玩家。
  - 先用服务器 RSA 公钥完成原版加密握手（客户端无需模组）。
  - 然后照常调用 Mojang `hasJoinedServer`：
    - 成功 → 正版玩家，UUID 直接用 Mojang 返回值，`textures` 属性保留，直接进入服务器。
    - 返回空（无有效会话）→ 离线玩家，使用 `UUIDUtil.createOfflineProfile(name)` 的离线 UUID。
    - 会话服务器不可达/超时 → 可配置为按离线玩家放行（默认开启，便于离线/内网环境）。
- **离线注册/登录**：`/register <密码> <确认密码>`、`/login <密码>`、`/logout`、`/changepassword`。
- **未登录保护**：
  - 丢弃移动、破坏/放置、攻击、交互、使用物品、容器点击、丢弃物品等数据包；
  - 禁止普通聊天；
  - 只允许认证命令；
  - 未登录时冻结位置、关闭界面、清除火焰/补满血、失明（可配置），并周期显示提示。
- **密码安全**：PBKDF2-HMAC-SHA256（JDK 自带，默认 120000 次迭代、每个账号独立 16 字节盐）。
- **防爆破**：连续错误 N 次后临时封禁 IP；认证命令 1.5 秒内只处理一次，后台哈希线程池只有 2 个线程。
- **正版用户名保护**：记录成功验证过的正版名字，离线玩家不能再冒用该名字；正版玩家上线时也会踢掉同名的在线离线账号。
- **双语言**：`config.json` 的 `language` 可切 `zh_cn` / `en_us`，消息以字面量组件发送，客户端不装模组也能看。
- **管理员命令**：`/plogin reload|list|info|unregister|resetpassword`。

---

## 2. 工作原理

### 2.1 原版登录流程（26.2）

`ServerLoginPacketListenerImpl`：

1. `handleHello`：`usesAuthentication() == true` → 发送 `ClientboundHelloPacket`（RSA 公钥 + challenge）。
2. `handleKey`：解密客户端发来的 AES 密钥和 challenge，启用加密，开一个线程执行
   `server.services().sessionService().hasJoinedServer(name, digest, ip)`。
3. 成功 → 用 Mojang 返回的 `GameProfile`（含 `textures`）；失败 → 原版直接断开
   `multiplayer.disconnect.unverified_username`。

### 2.2 PLogin 的改动点

**核心 Mixin：`LoginThreadMixin`**

```java
@Mixin(targets = "net.minecraft.server.network.ServerLoginPacketListenerImpl$1")
@Redirect(method = "run", at = @At(value = "INVOKE",
    target = "Lcom/mojang/authlib/minecraft/MinecraftSessionService;hasJoinedServer(...)..."))
private ProfileResult plogin$hybridVerify(...) {
    return HybridAuth.verify(service, name, digest, address);
}
```

- `HybridAuth.verify` 先调用原版 `hasJoinedServer`。
- 成功：原样返回 `ProfileResult` → UUID、`textures`、`profileActions` 全部保留。
- 返回 null：`new ProfileResult(UUIDUtil.createOfflineProfile(name))` → 离线 UUID。
- `AuthenticationUnavailableException`：若配置允许，则同样回退离线；否则重新抛出，保持原版行为。

**其他 Mixin**

| Mixin | 作用 |
|---|---|
| `LoginHelloMixin` | 对离线 profileId 的客户端把 `Encryption Request` 的 `shouldAuthenticate` 设为 `false`，避免客户端 `joinServer` 报“无效会话” |
| `ConnectionMixin` | 在 `Connection.channelRead0` 入口丢弃未登录玩家的危险数据包（白名单在 `PacketGate`） |
| `CommandsMixin` | 未登录玩家只允许 `/register /login /logout /changepassword` 及别名 |
| `ServerGamePacketListenerChatMixin` | 未登录玩家不能发普通聊天 |
| `DedicatedServerMixin` | `forceDisableSecureProfile=true` 时强制 `enforceSecureProfile()=false`，否则离线玩家无法聊天 |

**为什么在 `Connection` 层过滤？**
26.2 的交互/容器/攻击/丢弃等入口分散在 `ServerGamePacketListenerImpl` 的几十个方法里；
在数据包分发入口统一白名单，能覆盖全部动作，避免漏掉新版本新增的包。
白名单里保留了 keep-alive、聊天/命令、客户端信息、资源包、cookie、传送确认、
chunk batch、player_loaded、tick_end 等必要包，其余全部丢弃。

**认证状态机**（`AuthManager`）

```
JOIN
 ├─ 正版 profile（UUID 不是 OfflinePlayer:name）→ 直接 AUTHENTICATED
 └─ 离线 profile
     ├─ users.json 无记录 → PENDING_REGISTER（冻结 + 提示 /register）
     └─ users.json 有记录 → PENDING_LOGIN（冻结 + 提示 /login）
              │
              ├─ /register 成功 → 保存 PBKDF2 → AUTHENTICATED
              ├─ /login 成功    → AUTHENTICATED
              └─ 超时           → 踢出（或切旁观者，可配置）
```

登录成功后：恢复 `invulnerable` / `noGravity`、移除失明、必要时恢复游戏模式、补满血。
之后所有数据包都由原版处理。

### 2.3 离线客户端“无效会话”修复（`LoginHelloMixin`）

**现象**：正版客户端能进，离线客户端报
`登录失败：无效会话（请尝试重启游戏及启动器）`，服务端日志只有
`PremiumTester (/IP:port) lost connection: Disconnected`，没有 `UUID of player`。

**原因**：原版 `Encryption Request`（`ClientboundHelloPacket`）里有
`Should authenticate` 布尔字段。服务端默认发 `true`，客户端就会拿本地令牌调用
Mojang `joinServer`。离线启动器的令牌无效，客户端在收到 401 后直接断开，
**根本不会发送 `Encryption Response`**，所以服务端的 `hasJoined` 回退根本没有机会执行。

**修复**：对 `ServerboundHelloPacket.profileId()` 是离线 UUID 的客户端，
把 `Should authenticate` 改成 `false`：

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

- `profileId == UUIDUtil.createOfflinePlayerUUID(name)` 或 `profileId.version() == 3` → 离线客户端 → `false`；
- 否则（正版 v4 UUID / 随机 UUID）→ 保持 `true`；
- 如果这个名字已在 `premium-names.json` 里记录为正版 → 强制 `true`，防止离线冒名。

客户端收到 `false` 后仍然完成 RSA/AES 加密并发送 key，只是跳过 `joinServer`；
服务端照常执行 `hasJoined`，失败后由 `HybridAuth` 回退到离线 UUID。

**注意**：这依赖启动器在 Login Start 里发送**离线 UUID**。PCL2/HMCL 等常见离线启动器
通常使用 `OfflinePlayer:<name>`（UUID v3）。如果某个启动器发送随机 v4 UUID，
服务端日志会显示 `offlineProfileId=false, skipClientAuth=false`，客户端仍可能报无效会话；
这种情况需要再加“按名字查询 Mojang 是否为正版账号”的异步预判（当前未做）。


### 2.4 离线皮肤（参考 SkinsRestorer / MCCustomSkinLoader）

**原版限制**：客户端对“其他玩家”的皮肤要求签名有效。

```
PlayerInfo.createSkinLookup:
    requireSecure = !minecraft.isLocalPlayer(profile.id())

SkinManager.createLookup(profile, requireSecure):
    skin.secure() == (textures.signatureState() == SIGNED)
```

- Mojang 的 signed `textures` → `SIGNED`，所有玩家可见；
- 皮肤站自己的签名（客户端不信任其 key）或未签名属性 → `INVALID/UNSIGNED`，
  其他玩家会回退默认皮肤（本机玩家自己可能还能看到）。

MCCustomSkinLoader 是**客户端模组**，在客户端绕过这个限制；
SkinsRestorer 是服务端方案，做法是：

1. Mojang 正版名 → 直接取 signed `textures`；
2. 自定义 URL → 交给 MineSkin 生成 signed value/signature
   （MineSkin 用正版账号池把图片换成 `textures.minecraft.net` 的图片）。

PLogin 采用同样的服务端思路（客户端零模组）：

1. `api.mojang.com/users/profiles/minecraft/<name>` 查名字；
   是正版账号 → `sessionserver/.../profile/<uuid>?unsigned=false` 取 signed `textures`；
2. 否则请求 `offlineSkinSiteRoot + <name>.json`（LittleSkin / Blessing Skin 的 CustomSkinAPI），
   取 `<root>textures/<hash>`；
3. 用 MineSkin v2 `/generate` 把该 URL 签成 Mojang 可验证的 `textures`；
4. 无皮肤/MineSkin 失败 → 默认皮肤；可选择未签名 fallback（通常只有本机可见）。

调用点在 `HybridAuth.verify` 的离线分支，必须在 `LoginFinished` 发送前完成：

```java
offlineProfile = SkinService.applyOfflineSkin(UUIDUtil.createOfflineProfile(name));
return new ProfileResult(offlineProfile);
```

**披风 / 鞘翅**：

- Mojang 的 signed `textures` 可以同时包含 `SKIN`、`CAPE`、`ELYTRA`；
  PLogin **原样返回整个属性**，不裁剪。离线 `PremiumTester` 实测 `textures=[SKIN, CAPE]`。
- LittleSkin CustomSkinAPI 的 `cape` / `elytra` 会被解析，并写入未签名属性；
  但 MineSkin 只对皮肤图片签名，**不支持任意披风 PNG 签名**，所以皮肤站披风
  只有在 `offlineSkinAllowUnsignedFallback=true` 时才会下发（通常只有本机玩家可见）。
- 想让所有玩家看到皮肤站披风：使用一个带该披风的正版账号（其 signed 属性同时含皮肤和披风），
  或使用客户端 CustomSkinLoader；服务端无法凭空给任意披风签 Mojang 的签名。

> 测试服为了演示“离线玩家用正版名 + 正版皮肤”，把 `premiumNameProtection` 设成了 `false`；
> 这允许离线玩家注册正版名，真实正版登录时会踢掉离线账号。生产环境请按安全需求决定。

### 2.5 正版离线备份密码

目的：正版玩家事先设置一个密码；当**正版验证不可用/他自己用离线启动**时，
仍能用同一个名字离线进入，并用密码登录。

**设置（正版验证成功时）**：

```
/register <密码> <确认密码>
```

- 正版玩家本来无需注册，所以这个命令被重新定义为“设置/更新离线备份密码”；
- 写入 `users.json`：
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
| 正版客户端 / hasJoined 成功 | 原版流程，正版 UUID + 皮肤 + 披风，**不需要密码** |
| 正版不可用 / 离线客户端 | 先用**离线 UUID**进入并冻结；`/login` **密码通过后**才踢正版同名，然后断开重连；重连后使用正版 UUID/存档 |
| 真实正版上线时 | 踢掉同名的在线离线号，正版优先 |

**踢人提示**：

- 离线备份验证成功、正版被挤下线 → 正版看到：
  `当前用户名异地登录成功，如果不是本人行为请检查密码是否泄露！`
- 正版登录成功、离线被挤下线 → 离线看到：
  `你当前使用的用户名被正版用户登录成功，请检查你所使用的用户ID是否有正版玩家使用！`

**关键点**：

- `LoginHelloMixin` 看到该名字有 `premiumBackup` 时，即使
  `premiumNameProtection=true`，也会给离线客户端下发 `shouldAuthenticate=false`，
  让客户端能跳过客户端侧 `joinServer`；
- **待验证阶段绝不使用正版 UUID**：`HybridAuth` 先返回 OfflinePlayer UUID，
  只注入 `plogin:offline_backup` 标记。这样原版 `PlayerList.disconnectAllPlayersWithProfile`
  不会在登录阶段就把正版同名玩家踢掉；
- 密码通过后才遍历同名玩家，把正版玩家断开；然后给离线客户端发一个
  `BackupVerification` 短时令牌（120 秒），断开并提示重连；
- 重连时 `HybridAuth` 消费令牌，把 UUID 切到记录里的正版 UUID，并注入
  `plogin:backup_verified`；`AuthManager` 直接放行，不再要求密码；
- 此时客户端 `LocalPlayer`、服务端玩家、playerdata/stats/advancements 才是正版 UUID；
- 如果正版玩家在重连前又抢先上线，重连会被拒绝，不会来回互踢；
- 没设置备份密码的正版名，在 `premiumNameProtection=true` 时仍然禁止离线进入；
- 备份密码使用 PBKDF2，和普通离线密码一样受错误次数/IP 封禁保护。

> **为什么必须重连**：Minecraft 客户端/服务端的 GameProfile UUID 在 Login 阶段固定；
> 会话中途改 UUID 会影响实体、PlayerList、playerdata、stats、advancements、客户端本地 profile。
> 虚拟世界/传送可以隔离待验证玩家，但**不能**替代 UUID 切换所需的新连接。

> **旧离线存档不会自动合并**：待验证阶段仍使用 OfflinePlayer UUID；密码通过并重连后
> 才切到正版 UUID。之前旧离线 UUID 的独立存档仍然存在，但不会自动合并。
> 需要迁移旧进度的话，可以手动把旧 `data/<offline-uuid>.dat` 复制成
> `<premium-uuid>.dat`（先备份）。

**推荐的上线顺序**：

1. 先保持 `premiumNameProtection=false`，让正版玩家用正版登录后 `/register` 设置备份密码；
2. 确认主要正版玩家都设置完成后，把 `premiumNameProtection` 改为 `true` 并 `/plogin reload`；
3. 之后离线冒用正版名会被明确拒绝，不会进入“待注册 / 未注册”的死循环。

> 待验证玩家现在会被隔离到 **plogin:limbo 虚空维度**，不再在主世界登录点冻结；
> 这个维度不能省掉 UUID 切换所需的重连，但能隐藏真实坐标、隔离主世界。
> 自定义维度可能被其他模组遍历到，这是它的代价。

### 2.6 正版登录欢迎标题

正版 `hasJoined` 成功、直接进入服务器的分支会发送：

- 聊天欢迎：`[PLogin] 正版验证通过，欢迎回来，<名字>！`
- 屏幕标题：**正版验证成功，欢迎**
- 副标题：**已自动登录**

标题使用 `Ui.title`（与登录提示标题同一套 `ClientboundSetTitleTextPacket`），
绿色主标题 + 灰色副标题；离线备份登录/正版不可用时不会显示这个标题，
而是显示 `请先登录` 的提示标题。

### 2.7 Limbo 虚空维度（隐藏真实坐标）

新增维度：

```
data/plogin/dimension/limbo.json
data/plogin/dimension_type/limbo.json
```

`plogin:limbo` 使用自定义维度类型 + `minecraft:flat` 空层 + `minecraft:the_void` 生物群系，
生成一个没有方块的虚空世界。

> **为什么不用 `minecraft:the_end`**：原版 `ServerLevel` 构造函数会检查
> `dimensionType().hasEnderDragonFight()`，为 true 时初始化 `EndDragonFight`。
> 一开始复用 `the_end` 类型，limbo 里直接刷出末影龙并会吐龙息；现在自定义
> `plogin:limbo` 维度类型，显式设置 `has_ender_dragon_fight: false`，
> 并清掉旧的 limbo 维度存档。

**关键时序**：

- `PlayerListMixin` 在 `PlayerList.placeNewPlayer` 的 **HEAD** 阶段，
  也就是客户端 `Login` 包发出之前，把离线玩家切到 `plogin:limbo`：
  ```java
  player.setServerLevel(limbo);
  player.setPos(0.5, 65.0, 0.5);
  ```
- 客户端收到的第一个位置就是 `(0.5, 65.0, 0.5)`，**不会先看到主世界和真实坐标**；
- `LimboManager` 在切换前保存原世界/原坐标；
- 普通离线玩家 `/login` 成功后传回原世界/原坐标；
- 待验证玩家断线时，`onDisconnect` 会先把位置恢复回原世界再保存 playerdata，
  避免 limbo 的 0,65,0 覆盖真实位置；
- 备份密码验证成功仍然走“断开重连 → 正版 UUID/存档”，重连后直接进主世界，
  不需要从 limbo 传回。

`/logout` 也会把玩家放进 limbo，重新 `/login` 后再传回原位置。

> 代价：`plogin:limbo` 是一个真实注册的自定义维度，其他模组可能遍历到；
> 它只做隔离和坐标隐藏，不改变 UUID，也不能替代验证后的重连。

**未登录隐藏状态**：

- `PlayerInfoFilterMixin` 拦截发往每个客户端的 `ClientboundPlayerInfoUpdatePacket`：
  - 普通玩家看不到任何未登录玩家的 Tab 项；
  - 未登录玩家自己那一项保留（客户端需要自己的信息）；
  - 登录成功后 `AuthManager` 重新广播 `createPlayerInitializing`，正常玩家这时才看到；
  - `/logout` 会发 `ClientboundPlayerInfoRemovePacket` 把玩家从其他人的 Tab 移走。
- `PlayerListMixin` 的 redirect 会跳过未登录玩家的 “joined the game” 系统消息；
- `QuitMessageMixin` 会吞掉未登录玩家的 “left the game” 消息，避免“只看到退出、没看到进入”；
- 登录成功后补发 “joined the game”；`/logout` 时补发 “left the game” 并隐藏；
- 未登录玩家在 limbo 里，主世界玩家本来就没有实体/区块可见性；
- `/tp`、`/kill`、`/damage` 和死亡/重生都有保护：
  - 未登录玩家不能被当作 `/tp` 的目标，也不能被 `/tp` 移出 limbo；
  - `/kill` 直接拒绝；
  - `hurtServer`/`die`/`kill` 被拦截，不会出现死亡界面/重生回主世界；
  - 提示：`该玩家尚未登录，无法传送或攻击。`

> 注意：`/list`、在线人数、服务器日志仍然会看到未登录玩家；Tab 和世界视图已隐藏。
> 其他模组如果自己遍历 PlayerList/维度，仍可能看到他们，这是后端方案的边界。

---

## 3. 目录结构

```
PLogin/
├── README.md                       ← 面向服主的使用说明
├── LICENSE                         ← AGPL-3.0
├── build.sh                        ← 无 Gradle 的直接 javac 构建脚本
├── docs/
│   ├── TECHNICAL.md                ← 本文件
│   └── 文案.md                      ← 宣传/玩家提示/公告/命令文案汇总
├── src/
│   ├── fabric.mod.json
│   ├── plogin.mixins.json
│   ├── data/plogin/dimension/limbo.json        ← 虚空维度
│   ├── data/plogin/dimension_type/limbo.json
│   └── com/pimeng/plogin/
│       ├── PLogin.java / PLoginMod.java / Messages.java
│       ├── auth/  (AuthManager, HybridAuth, LimboManager, BackupVerification,
│       │            PasswordHasher, UserStore, UserRecord, PacketGate)
│       ├── command/AuthCommands.java
│       ├── config/Config.java
│       ├── skin/SkinService.java   ← Mojang / LittleSkin / MineSkin 皮肤
│       ├── mixin/  (12 个 Mixin，含 PlayerList/PlayerInfo/伤害/传送保护)
│       └── util/Ui.java
└── build/                          ← 构建产物（不入库）
    ├── plogin-1.0.0.jar
    └── classes/
```

---

## 4. 构建

环境：Java 25（Minecraft 26.2 的最低要求），Minecraft 26.2 服务端 jar（Mojang mappings），Fabric API。

```bash
./build.sh        # Linux / macOS
```

```powershell
.\build.ps1       # Windows
```

产物：`build/plogin-1.0.0.jar`

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

未设置 `MC_SERVER` 时，脚本依次尝试仓库下的 `.mcserver/`、`../minecraft-bingo`、
`$HOME/mc/minecraft-bingo`；换机器构建时显式指定：

```bash
MC_SERVER=/path/to/minecraft-server ./build.sh
```

不需要 Gradle/Loom；本环境 Fabric Loader 使用 Mojang 官方名（启动日志 `Mappings not present!`），
因此源码直接用 `net.minecraft.server.level.ServerPlayer` 这类 Mojang 名。

> 仓库不附带 Minecraft 服务端 jar（Mojang 版权产物），`versions/`、`build/` 已在 `.gitignore` 中排除。

---

## 5. 部署

1. **服务端**：
   - Fabric Loader >= 0.19.5，Fabric API；
   - 把 `build/plogin-1.0.0.jar` 放进 `mods/`；
   - `server.properties` 保持 `online-mode=true`（混合登录的前提）。
2. **客户端**：什么都不用装。正版客户端直接连；离线启动器客户端也能连并注册。
3. **强烈建议**：
   - 删除 JVM 参数里的 `-Djava.net.preferIPv6Addresses=true`（原因见第 11 节）；
   - `enforce-secure-profile` 建议 `false`；即使设为 `true`，模组在
     `forceDisableSecureProfile=true`（默认）时也会让它有效值为 `false`（已实测）；
   - 备份 `config/plogin/`。
4. 重启服务器，日志应出现：
   ```
   PLogin 1.0.0 已加载，数据目录: ./config/plogin
   [PLogin] 混合登录已启用（online-mode=true）：...
   ```

---

## 6. 配置

首次启动自动生成，缺字段自动补全。

```json
{
  "enabled": true,
  "premiumBypass": true,
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
  "blindness": true,
  "freezeMovement": true,
  "language": "zh_cn",
  "premiumNameProtection": true,
  "premiumPasswordBackup": true,
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
| `skipClientAuthForOfflineClients` | 对离线 profileId 的客户端把 `shouldAuthenticate` 设为 false，避免客户端 `joinServer` 报“无效会话”（默认 true） |
| `forceDisableSecureProfile` | 强制 `enforce-secure-profile=false`，避免离线玩家聊天被踢 |
| `requireRegistration` | 离线玩家是否必须注册 |
| `passwordIterations` | PBKDF2 迭代次数（10000–2000000） |
| `loginTimeoutSeconds` | 未登录超时，0 = 不超时 |
| `kickOnTimeout` | 超时踢出；false = 切旁观者继续等待 |
| `maxLoginAttempts` / `ipBanSeconds` | 错误次数 / IP 临时封禁秒数 |
| `blindness` / `freezeMovement` | 未登录失明 / 冻结移动 |
| `language` | `zh_cn` 或 `en_us` |
| `premiumNameProtection` | 已记录的正版名字禁止离线冒用；设为 false 可让离线玩家用正版名（有安全取舍） |
| `premiumPasswordBackup` | 允许正版玩家 `/register` 设置离线备份密码；正版不可用时凭密码离线登录 |
| `offlineSkinsEnabled` | 离线皮肤总开关 |
| `offlineSkinProvider` | `auto` / `mojang` / `littleskin` / `none` |
| `offlineSkinSiteRoot` | CustomSkinAPI 根地址，默认 LittleSkin `https://littleskin.cn/csl/` |
| `offlineSkinMineSkinApiKey` | MineSkin API Key（可空；游客 10 次/分钟） |
| `offlineSkinMineSkinSigning` | 用 MineSkin 把皮肤站图片签成 Mojang 可验证属性（否则其他玩家看不到） |
| `offlineSkinAllowUnsignedFallback` | MineSkin 失败时是否仍下发未签名属性（通常只有本机可见，默认 false） |
| `offlineSkinTimeoutSeconds` / `offlineSkinCacheMinutes` | 查询超时 / 缓存时间 |

---

## 7. 命令

### 玩家

| 命令 | 说明 |
|---|---|
| `/register <密码> <确认密码>` | 未注册的离线玩家注册，成功后自动登录 |
| `/login <密码>` | 已注册离线玩家登录 |
| `/logout` | 登出并重新冻结 |
| `/changepassword <旧密码> <新密码>` | 修改密码 |
| 别名 | `/reg`、`/l`、`/cp`（若未被其他模组占用） |

未登录时只允许以上命令（及正常聊天外的保活包）。

### 管理员（permission 3 / OP）

| 命令 | 说明 |
|---|---|
| `/plogin reload` | 重载配置与语言 |
| `/plogin list` | 已注册用户数 |
| `/plogin info <玩家>` | UUID / 类型（premium/offline）/ 状态 / 最后登录 |
| `/plogin unregister <玩家>` | 删除注册记录；离线玩家在线时重新进入未注册状态 |
| `/plogin resetpassword <玩家> <新密码>` | 重置离线密码 |

---

## 8. 数据文件

- `config.json`：配置。
- `users.json`：离线账号。
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
2. **不信任客户端 UUID**：离线 UUID 由服务端用 `OfflinePlayer:<name>` 计算，不采用客户端 `ServerboundHelloPacket` 里携带的 UUID。
3. **正版名字保护**：
   - 正版验证成功后记录名字；
   - 之后再有人用同名离线登录 → 直接踢出；
   - 正版玩家上线时，若同名的离线账号在线，也会被踢掉；
   - 已知局限：某个正版名字**第一次**登录前，离线玩家理论上可以抢注同名（但正版一登录就会被踢，之后被永久阻止）。
4. **未登录隔离**：入口级数据包白名单 + 聊天/命令 Mixin + 位置冻结 + 失明 + 无敌。
5. **密码哈希**：PBKDF2-HMAC-SHA256，120000 次，16 字节盐，`MessageDigest.isEqual` 常数时间比较。
6. **防爆破**：错误计数 + IP 临时封禁 + 认证命令忙碌标记（同一玩家并发请求只会有一个哈希任务）。
7. **关闭 secure profile**：离线玩家没有 Mojang 签名的聊天密钥；强制 `enforceSecureProfile=false` 让所有人可以正常聊天（代价：没有签名聊天/聊天举报链）。

---

## 10. 正版皮肤为什么能正常显示

- 原版 `hasJoinedServer` 成功的响应包含
  `properties: [{"name":"textures","value":"...","signature":"..."}]`。
- PLogin 的 Mixin **原样返回**这个 `ProfileResult`，没有重建/裁剪 `GameProfile`。
- 服务端在 `ClientboundLoginFinishedPacket` 和 `ClientboundPlayerInfoUpdatePacket`
  中把 `GameProfile`（含 `textures`）发给客户端。
- 原版客户端根据 `textures` 里的签名 URL 下载并渲染皮肤；**客户端不需要 PLogin**。

测试中本地伪装会话服务器返回了带 `textures` 的档案，测试客户端打印到：

```
[client] login finished: name=PremiumTester uuid=069a79f4-44e9-4726-a5be-fca90e38aaf5
[client] textures property value=eyJ0aW1lc3RhbXAiOiAxNzkzNzY2MDAwMDAwLCAicHJvZmlsZUlkIjog...
[client] textures property signature=present
```

离线玩家由 `SkinService` 注入皮肤（见 §2.4）：

- 正版名 → Mojang signed textures，所有玩家可见；
- 非正版名 → LittleSkin CustomSkinAPI → MineSkin 签名，所有玩家可见；
- 找不到皮肤 / MineSkin 失败 → 默认皮肤；
- 未签名 fallback 默认关闭，因为原版客户端对其他玩家要求 `SIGNED`，其他人会看到默认皮肤。

---

## 11. JVM 的 IPv6 优先参数

本机排查发现：

```bash
java -Djava.net.preferIPv6Addresses=true NetTest   # sessionserver/api 全部 “网络不可达”
java NetTest                                       # hasJoined 204 / publickeys 200 / profiles 200
```

**如果 `run.sh` 里有 `-Djava.net.preferIPv6Addresses=true`，正版玩家的 `hasJoined` 会失败，
PLogin 会按离线玩家处理 —— UUID 变成离线的、皮肤丢失。**

- 检查你的启动脚本（`run.sh` / systemd unit / 面板启动参数）里是否还有这一行；
- 有的话删除后重启服务端；
- 删掉后 `hasJoined` 就能正常返回，正版玩家的 UUID、皮肤、披风都会恢复。

---

## 12. 测试结果

测试环境：Minecraft 26.2、Fabric Loader 0.19.5、Java 27 (Zulu 27.28)、
2 核实机（`taskset -c 4-5`）+ `-Xms1G -Xmx2G -XX:ActiveProcessorCount=2`。

> 测试服为纯 Fabric API + PLogin 的原版服（无其他模组）。
> 下面 12.1 起的记录是开发过程中的逐步验证；最终配置下已复测：
> `FinalTest` 登录成功、`VanillaTest` 注册成功（随后已注销）、未登录冻结位置不变。

### 12.1 离线注册（真实 Mojang 环境）

- 客户端：`FinalTest`，未装任何模组，走完整原版协议；
- 服务端日志：`FinalTest 没有有效的正版会话，按离线玩家处理`（sessionserver 返回 204）；
- `login_finished` UUID：`7933546b-96dd-38a6-bbf6-c8e78cf0fcaf`（= `OfflinePlayer:FinalTest`）；
- 收到欢迎/注册提示；
- `/register final1234 final1234` → `注册成功，已自动登录`；
- 服务端写入 `users.json`（见第 8 节）。

### 12.2 离线登录 / 错误密码

- `/login test1234` → 登录成功；
- `/login wrongpass` → `密码错误，还可以尝试 4 次。`。

### 12.3 正版分支（本地伪装会话服务器）

通过 JVM 参数把 authlib 指向本地：

```
-Dminecraft.api.session.host=http://127.0.0.1:25999
-Dminecraft.api.services.host=http://127.0.0.1:25999
-Dminecraft.api.profiles.host=http://127.0.0.1:25999
```

- `PremiumTester` 的 `hasJoined` 返回正版 UUID + `textures`；
- 客户端 `login_finished` 得到 `069a79f4-44e9-4726-a5be-fca90e38aaf5`，textures 属性存在；
- PLogin 直接放行，`/register` 提示“该账号已通过正版验证，无需注册/登录”；
- 记录到 `premium-names.json`；
- 停掉伪装服务器后再用 `PremiumTester` 离线连接 → 被踢：
  `该用户名已被正版账号占用，无法以离线身份登录。`

### 12.4 未登录冻结

- 未登录时客户端连续发送移动到 `(50,17,1.5)` 的包；
- RCON `execute as @a run data get entity @s Pos` 仍为出生点 `[-29.5, 71.0, -42.5]`（原版测试服实测）；
- `/plogin info` 显示 `pending_login`；
- 登录后 `/plogin info` 显示 `authenticated`；RCON `tp` 后位置保留（服务器可正常移动玩家）。

> 登录后的“客户端自己走动”在本测试客户端里受传送确认包时序影响，不能作为可靠结论；
> 但模组在认证成功后已解除冻结（`noGravity`/无敌恢复、过滤放行），真实客户端由原版处理。

### 12.5 无客户端模组

测试客户端只依赖服务端 jar 的协议类，没有任何客户端 mod；这等价于“原版协议客户端”。
真实 GUI 客户端直接连即可。

### 12.6 secure profile 覆盖

测试服 `server.properties` 特意设置成 `enforce-secure-profile=true`：

```
[PLogin] 混合登录已启用（online-mode=true）...
[PLogin] enforce-secure-profile 有效值: false（配置 forceDisableSecureProfile=true）
```

即 `DedicatedServerMixin` 的强制关闭生效，离线玩家聊天不会被签名校验踢掉。

### 12.7 未登录断线后的状态清理

未登录玩家断线时 `AuthManager.onDisconnect` 会恢复 `Invulnerable`/`NoGravity`、移除失明、
必要时恢复游戏模式；即使服务器崩溃导致状态写进玩家数据，下次进服也会先归一化再冻结。
实测：

- 未登录在线：`Invulnerable=1b`、`NoGravity=1b`（正常冻结）；
- `/login` 成功后：`Invulnerable=0b`、`NoGravity` 为默认 false、`Health=20.0f`。

### 12.8 离线客户端“无效会话”修复实测

- `FinalTest`（profileId = 离线 UUID）：
  服务端 `Login Start: offlineProfileId=true, skipClientAuth=true`；
  客户端收到 `<- hello ... auth=false`；登录成功。
- `RandomProfile`（profileId = 随机 UUID）：
  服务端 `offlineProfileId=false, skipClientAuth=false`；
  客户端收到 `<- hello ... auth=true`，仍然要求正版验证（用于正版客户端）。
- `PCL2User`（模拟 PCL2 原始的自定义 v3 离线 UUID，不是标准 OfflinePlayer 但 version=3）：
  服务端仍判定 `offlineProfileId=true`，客户端 `auth=false`，注册/登录成功。
- `PremiumTester` 已记录为正版名：默认 `premiumNameProtection=true` 时会强制 `auth=true`；
  测试服为了演示皮肤设成了 `false`，离线 PremiumTester 可以注册并拿到 Mojang 正版皮肤，真实正版登录时会踢掉离线号。

> 如果启动器发送的是随机 v4 UUID，日志会显示 `offlineProfileId=false`，第一次登录仍可能报无效会话；
> 把该日志行发来，可再加“按名字调用 Mojang API 判正版”的异步预判。

### 12.9 离线皮肤实测

- `PremiumTester` 离线（测试服 `premiumNameProtection=false`）：
  服务端 `离线皮肤: PremiumTester <- mojang:PremiumTester (signed, textures=[SKIN, CAPE])`；
  客户端 `textures property value=... signature=present`、`textures keys=[SKIN, CAPE]`。
- `LittleSkin` 离线、强制 `offlineSkinProvider=littleskin`：
  服务端 `离线皮肤: LittleSkin <- mineskin:https://littleskin.cn/csl/textures/... (signed)`；
  客户端同样收到 value + signature。
- 用 authlib `MinecraftSessionService.unpackTextures` 校验 MineSkin 返回值：
  `signatureState=SIGNED`，原版客户端对其他玩家也会使用该皮肤（不是只在本机显示）。

> 原因：`PlayerInfo.createSkinLookup` 对非本机玩家 `requireSecure=true`，
> `SkinManager` 会过滤 `secure=false` 的皮肤；LittleSkin 自己的签名客户端不信任，
> 必须经过 MineSkin 换成 Mojang 签名，这也是 SkinsRestorer 的做法。

### 12.10 正版名离线注册死循环修复

**现象**（截图）：

```
[PLogin] 欢迎 PremiumTester！请先注册...
[PLogin] 正在处理，请稍候...
[PLogin] 该用户名已经注册，请使用 /login <密码>。
[PLogin] 你还没有注册，请使用 /register <密码> <确认密码> 注册。
```

**原因**：正版 `PremiumTester` 先进过服务器，`premium-names.json` 记录了它的正版 UUID；
随后离线 `PremiumTester`（离线 UUID 不同）进入：

1. `AuthManager.onJoin` 看到 `users.json` 没有记录 → `PENDING_REGISTER`（提示注册）；
2. `UserStore.register` 里还有一条“premium-names 的 UUID 必须相同”的检查，
   离线 UUID 不同 → 返回 false；
3. `AuthManager` 把 false 当成“用户名已注册”，提示去 `/login`；
4. `/login` 查 `users.json` 又没有记录 → 提示还没注册，形成死循环。

**修复**：

- `UserStore.register` 只检查 `users.json` 是否重复，不再拦截正版名；
- 是否允许离线注册正版名由 `AuthManager.requestRegister` 按
  `premiumNameProtection` 决定：
  - `false`（测试服）→ 允许，正常注册；
  - `true` → 明确提示“该用户名属于正版账号，无法用离线身份注册”；
- 另外，不管开关如何，**正版同名账号当前在线时**，离线号都会被拒绝/踢出，
  避免两个同名字不同 UUID 的玩家并存。

**实测**：离线 `PremiumTester`（`premium-names` 已有正版记录）：
`/register p1meng123 p1meng123` → 注册成功；`/login p1meng123` → 登录成功；
皮肤/披风仍是 `[SKIN, CAPE]`。测试账号已用 `/plogin unregister PremiumTester` 清理。

### 12.11 正版离线备份密码实测

用本地伪装会话服务器模拟正版：

1. `PremiumTester` 以正版身份进入（UUID `069a79f4-44e9-4726-a5be-fca90e38aaf5`）；
2. `/register backup123 backup123`：
   ```json
   {
     "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
     "offlineUuid": "c050ddd0-5143-3291-abd5-3cefdef7b190",
     "premiumBackup": true
   }
   ```
3. 停掉伪装服务器，切回真实 Mojang，并把 `premiumNameProtection` 设为 `true`；
4. 离线客户端**第一次连接**：仍使用离线 UUID，冻结等待密码：
   ```
   [PLogin] Login Start: ... offlineProfileId=true, knownPremium=true, hasBackup=true,
                         protectPremiumName=false, skipClientAuth=true
   [client] login finished: name=PremiumTester uuid=c050ddd0-...
   [PLogin] 离线玩家 PremiumTester (c050ddd0-...) 加入，状态=PENDING_LOGIN
   /login backup123
   [client] DISCONNECTED: 备份密码验证成功，正版同名玩家已下线。请重新连接...
   ```
   密码错误时正版不会被踢，离线可以继续重试；只有 `ok` 之后才执行踢人。
5. 客户端**重连**：令牌生效，服务端才切正版 UUID：
   ```
   [client] login finished: name=PremiumTester uuid=069a79f4-44e9-4726-a5be-fca90e38aaf5
   [PLogin] 备份验证成功重连: PremiumTester 使用正版 UUID 069a...（与正版存档共用）
   [PLogin] 备份验证重连成功: PremiumTester (069a...)，使用正版 UUID/存档
   ```

说明备份密码流程可用：待验证阶段绝不使用正版 UUID，所以不会提前踢正版；
密码通过后才踢，并断开重连；重连时不再需要密码。

**存档同步实测**：重连后的 `LoginFinished` 才是正版 UUID；登录后
`world/players/data/069a....dat` 被更新，而旧的 `c050....dat` 保持之前的时间。
待验证第一次连接不会写正版存档。

### 12.12 正版欢迎标题实测

伪装正版 `PremiumTester` 登录，客户端收到聊天：

```
[PLogin] 正版验证通过，欢迎回来，PremiumTester！
```

服务端日志：

```
[PLogin] 正版玩家 PremiumTester (069a79f4-...) 直接进入服务器
```

同时 `Ui.title` 发送：

```
ClientboundSetTitleTextPacket("正版验证成功，欢迎")
ClientboundSetSubtitleTextPacket("已自动登录")
```

标题是原版 Title 包，客户端不需要装模组；测试客户端只验证了聊天/登录流程，
真实 GUI 上的标题由用户客户端最终确认。

### 12.13 Limbo 坐标隔离实测

离线 `FinalTest` 登录：

```
[client] <- player_position id=1 pos=(0.5, 65.0, 0.5)   ← limbo，真实坐标未暴露
[PLogin] 未登录玩家 FinalTest 已隔离到 plogin:limbo (0.5, 65, 0.5)，原位置已隐藏
/login final1234
[client] <- player_position id=2 pos=(-29.5, 71.0, -42.5) ← 登录成功后回原位
[PLogin] 玩家 FinalTest 登录成功，已从 limbo 传回 minecraft:overworld (-29.5, 71.0, -42.5)
```

待验证状态下直接断线、再重新登录，仍然回到 `(-29.5, 71.0, -42.5)`，
说明 `onDisconnect` 的“先恢复原位置再保存 playerdata”生效，limbo 坐标没有覆盖真实存档。

### 12.14 未登录隐藏/保护实测

- 正常玩家 `FinalTest` 在线时，未登录 `HiddenTest` 进入 limbo：
  - 正常玩家收到的 `PlayerInfoUpdate` 只有 `PremiumTester`、`FinalTest`，没有 `HiddenTest`；
  - 未登录玩家自己能看到 `PremiumTester`、`FinalTest` 和自己，其他未登录玩家互相隐藏。
- `/kill HiddenTest`：
  ```
  [PLogin] 该玩家尚未登录，无法传送或攻击。
  ```
- `/tp FinalTest HiddenTest`（正版玩家去未登录玩家）被拒绝。
- `/tp HiddenTest 100 65 100`（移动未登录玩家）被拒绝，位置仍为 `[0.5, 65, 0.5]`。
- `/damage HiddenTest 100` 不造成伤害，Health 仍为 `20.0f`。
- `/login` 成功后，正常玩家才收到 `IdleTester` 的 PlayerInfo add 和 “IdleTester joined the game”，Tab 重新显示；
  退出后正常玩家收到 “IdleTester left the game”。
- 未登录 `HiddenTest` 进出时，正常玩家日志里**既没有 joined 也没有 left**；不会出现“只收到退出包”。
- 正常玩家自己的 join/quit 消息不受影响，例如 `FinalTest joined the game` 正常显示。

---

## 13. 测试环境

- 版本：Minecraft 26.2 / Fabric Loader 0.19.5 / Java 27
- 模组：只有 `fabric-api` 与 `plogin`（没有优化或功能类模组，避免干扰变量）
- 服务端配置：`online-mode=true`、`enforce-secure-profile=true`（PLogin 强制有效值 false）、
  `max-players=20`、`view-distance=6`、`simulation-distance=4`
- 资源：2 核（`taskset -c 4-5`）、`-Xms1G -Xmx2G`、`ActiveProcessorCount=2`
- 世界：全新生成的原版世界
- 启动耗时：2 核上首次生成原版世界约 24 秒；等日志出现 `[PLogin] 混合登录已启用` 后再进服
- 皮肤：`offlineSkinsEnabled=true`、`offlineSkinProvider=auto`、
  `offlineSkinSiteRoot=https://littleskin.cn/csl/`、`offlineSkinMineSkinSigning=true`；
  MineSkin API Key 为空（游客 10 次/分钟）
- `premiumNameProtection=false`：演示用，允许离线玩家用正版名并取正版皮肤；正式服建议改回 true
- 正版备份密码：`premiumPasswordBackup=true`
- 测试账号：`FinalTest`（离线 UUID 7933546b-…）等一次性账号，验证完成后已注销

> 复现测试时建议使用独立目录和独立端口，测试账号不要与真实玩家数据混用。

---

## 14. 已知限制

1. **会话服务器不可达时默认放行离线**（`fallbackToOfflineWhenAuthServerDown=true`）。
   安全优先的服主可改成 `false`，但 Mojang 宕机时正版玩家也会被拒。
2. **正版名字首次登录前**存在抢注窗口（见第 9 节）；真实正版一登录就会踢掉冒名者并永久记录。
3. **离线皮肤有前提**：正版名由 Mojang 签名；非正版名需要 LittleSkin 上有同名皮肤，
   再用 MineSkin 签名。没有皮肤、MineSkin 限流/失败时会显示默认皮肤。
   MineSkin 游客 10 次/分钟、间隔 6 秒；批量登录建议配置 API Key。
   皮肤站 URL 会发给 MineSkin（第三方），不需要时可关 `offlineSkinMineSkinSigning`。
   Mojang 的披风/鞘翅随 signed 属性保留（PremiumTester 离线实测 `[SKIN, CAPE]`）；
   LittleSkin 的 `cape`/`elytra` 会写入 unsigned fallback（默认关闭），未签名时其他玩家看不到。
4. **`premiumNameProtection=false` 有安全取舍**：离线玩家可注册正版名并取得正版皮肤，
   真实正版该名字登录时会踢掉离线号；若要禁止离线冒名，保持默认 `true`。
   该开关为 `true` 时，离线注册正版名会得到明确的“正版账号无法离线注册”提示，不会死循环。
5. **离线 UUID 对名字大小写敏感**：`OfflinePlayer:FinalTest` 与 `OfflinePlayer:finaltest` 是两个 UUID；
   账号按小写名字匹配，若记录 UUID 与当前 UUID 不一致会提示联系管理员。客户端请保持固定大小写。
6. **secure profile 被强制关闭**：所有玩家的聊天都不再带 Mojang 签名，失去聊天举报链。
7. **密码通过聊天命令输入**：和 AuthMe/QLogin 一样，建议不要开启命令日志到公屏；服务器日志级别默认不会打印完整命令。
8. **IP 封禁只在内存**，重启后清空；用户密码和正版名字持久化。
9. **测试未使用真实 GUI 客户端**：本次用无界面原版协议客户端验证了登录/加密/配置/Play/命令；
   真实客户端渲染（包括皮肤）由原版逻辑保证，建议用户用自己的客户端再确认一次。
10. **原版世界首次生成**在 2 核测试环境约 24 秒，与 PLogin 无关；之后重启会快很多。
11. **离线客户端必须发送离线 UUID**（PCL2/HMCL 默认如此）。如果某启动器在 Login Start 里发随机 v4 UUID，
    服务端会按正版客户端处理（`shouldAuthenticate=true`），客户端仍可能报“无效会话”；
    日志里的 `Login Start ... offlineProfileId=false` 可确认。
12. **正版离线备份密码是真实凭据**：泄露后别人可以在正版验证不可用/离线模式冒用该名字
    （真实正版上线会踢掉离线号）。请使用强密码；忘记可让管理员 `/plogin resetpassword` 或
    `/plogin unregister`。设置备份后再把 `premiumNameProtection` 改为 `true` 更安全。
13. **旧离线存档不会自动合并**：以前用 OfflinePlayer UUID 登录产生的 playerdata/stats/advancements
    仍留在旧 UUID；设置备份后离线登录以正版 UUID 为准。需要旧进度时先备份，再手动复制
    `world/players/data/<offline-uuid>.dat` 到 `<premium-uuid>.dat`；
    stats/advancements 同理。

---

## 15. 参考

- [QLogin](https://github.com/EpochcraftMC/QLogin)（MIT）：注册/登录命令、冻结保护、密码哈希思路。
- [SkinsRestorer](https://github.com/SkinsRestorer/SkinsRestorer)：服务端皮肤属性注入、MineSkin 签名思路。
- [MCCustomSkinLoader](https://github.com/xfl03/MCCustomSkinLoader)：客户端皮肤加载与 CustomSkinAPI 格式。
- [MineSkin](https://mineskin.org/) / [LittleSkin](https://littleskin.cn/)：离线皮肤 URL 与签名服务。
- AuthMe / EasyAuth / nLogin：混合登录、未登录隔离、正版名保护的产品化做法。
- Minecraft 26.2 `ServerLoginPacketListenerImpl` / `ServerConfigurationPacketListenerImpl` / `PlayerList` 反编译源码。

---

## 16. 许可

本项目以 **GNU Affero General Public License v3.0** 发布，全文见 [LICENSE](../LICENSE)。

AGPL 的关键含义（不构成法律意见，以许可证原文为准）：

- 可以自由使用、修改、分发本模组；
- 分发修改版时，必须同样以 AGPL 提供完整源码；
- **把修改版作为网络服务提供给他人使用（例如开一个公开服务器），也必须向使用者提供源码。**

Minecraft 及其相关类的版权归 Mojang；反编译仅用于开发参考。
