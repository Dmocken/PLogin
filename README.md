# PLogin

**Minecraft 26.1 / 26.2 / 26.3 · Fabric 服务端模组 · 正版与离线玩家共存 · 客户端零模组**

服务器继续开着 `online-mode=true`，正版玩家照旧通过验证直接进服，UUID、皮肤、披风全部来自 Mojang；
用离线启动器的玩家也能进，但会在原版的 **Configuration（配置）阶段** 收到一个登录对话框：
注册或输入密码之后才继续加载世界。没有虚拟世界（limbo）、没有冻结、没有数据包过滤，客户端也不需要装模组。

[快速开始](#快速开始) · [常用配置](#常用配置) · [命令](#命令) · [常见问题](#常见问题) · [技术文档](docs/TECHNICAL.md)

---

## 这个模组解决什么问题

很多服主卡在同一个地方：开 `online-mode=false`，正版玩家的 UUID 就没了，皮肤失效、存档对不上；
开 `online-mode=true`，离线玩家又完全进不来。二选一，总有一半玩家被挡在门外。

PLogin 把两条路合成一条：同一条登录流程里先做正版验证，成功就是正版玩家；
失败（没有有效会话）就按离线玩家处理，在配置阶段弹对话框注册 / 登录。客户端不需要装任何模组。

| 方案 | 正版玩家 | 离线玩家 | 皮肤 | 客户端 |
|---|---|---|---|---|
| 纯正版服 `online-mode=true` | ✅ 直接进 | ❌ 进不来 | 正常 | 原版 |
| 纯离线服 `online-mode=false` | ⚠️ 丢失正版 UUID，皮肤失效 | ✅ 随便进 | 基本没有 | 原版 |
| 离线服 + 认证插件 | ⚠️ 同上 | ✅ 需要注册 | 基本没有 | 原版 |
| **PLogin** | ✅ 直接进，UUID / 皮肤 / 披风保留 | ✅ 对话框注册后进入 | 正版与离线都能显示 | 原版，零模组 |

---

## 特性

- **正版零打扰**：验证通过直接进入，不弹登录框、不要求输密码，UUID 与 Mojang 一致。
- **配置阶段对话框**：离线玩家在原版 Configuration 阶段看到注册 / 登录对话框，输入密码后才会被放进世界。
  玩家在以登录状态进入世界之前，根本不会出现在 PlayerList、Tab、在线列表和世界里，因此不需要
  limbo 维度、位置冻结、失明、隐藏玩家或数据包白名单。
- **正版名保护**：记录成功验证过的正版名，离线玩家无法冒用，正版一上线就踢掉同名离线号。
- **离线备份密码 + 原版 transfer**：正版玩家可以给自己设一个密码；Mojang 验证不可用/离线启动时，
  在对话框验证通过后由服务端清掉 dialog，再用原版 transfer 把连接转回正版 UUID/存档。
- **离线也能有皮肤**：正版名直接取 Mojang 签名皮肤；离线名走 LittleSkin + MineSkin 签名。
- **防爆破**：PBKDF2-HMAC-SHA256 加盐存储，输错多次临时封禁 IP；对话框有超时和「退出服务器」按钮。
- **中英双语**：配置里一行切换 `zh_cn` / `en_us`。

---

## 快速开始

### 1. 准备环境

| 项目 | 要求 |
|---|---|
| Minecraft | 26.1 / 26.2 / 26.3（每个版本一个 jar，不能混用） |
| Fabric Loader | 0.19.5 或更高 |
| Fabric API | 对应 Minecraft 版本的 Fabric API，放在 `mods/` |
| Java | 25（Minecraft 26.x 的最低要求） |

### 2. 安装

把对应 Minecraft 版本的 `PLogin-<Minecraft 版本>-v1.1.1.jar`（例如 26.2 用 `PLogin-26.2-v1.1.1.jar`；
从仓库 Releases 下载，或按[构建](#构建)自行编译）放进服务器的 `mods/` 目录，
和 `fabric-api-*.jar` 放在一起，然后重启。

> 客户端什么都不用装。正版客户端直接连；离线启动器客户端也能连，并在配置阶段看到注册对话框。

### 3. 确认 `online-mode=true`

`server.properties` 里必须是 `online-mode=true`。这是混合登录的前提：
PLogin 正是靠「先按正版验证、失败再按离线处理」来区分两类玩家的。

同时建议设置 `accepts-transfers=true`：离线备份密码登录成功后需要用原版 transfer 切到正版 UUID/存档，
未开启时服务端启动会输出一条 WARN，备份密码登录会回退为离线 UUID 登录。

### 4. 启动，确认模组已加载

日志里应该出现：

```text
[PLogin] 已加载 v1.1.1，数据目录：./config/plogin
[PLogin] 混合登录已启用（online-mode=true）：正版玩家验证后直接进入，离线玩家在配置阶段通过对话框注册/登录
```

配置和账号数据会生成在 `config/plogin/` 下，首次启动自动创建。

### 5. 告诉玩家怎么进服

玩家不再需要先执行 `/register` / `/login`，连接后按对话框操作即可。
[docs/文案.md](docs/文案.md) 里有开服公告和给玩家的说明，整段复制到群公告或服务器简介即可。

---

## 常用配置

配置文件：`config/plogin/config.json`（首次启动自动生成，缺字段会自动补全）。
改完执行 `/plogin reload` 即可生效，不需要重启。

服主最常改的几项：

| 配置 | 默认 | 说明 |
|---|---|---|
| `premiumBypass` | `true` | 正版玩家跳过注册 / 登录，直接进服 |
| `premiumPasswordBackup` | `true` | 允许正版玩家用 `/register` 设置离线备份密码 |
| `premiumNameProtection` | `true` | 禁止离线玩家冒用已记录的正版名 |
| `fallbackToOfflineWhenAuthServerDown` | `true` | Mojang 会话服务器挂掉时，是否放行正版玩家为离线身份 |
| `loginTimeoutSeconds` | `120` | 登录对话框停留多久后处理，0 = 不超时 |
| `transferEnabled` | `true` | 备份密码登录后是否用原版 transfer 切换到正版 UUID/存档 |
| `transferHost` | 空 | transfer 目标主机；空 = 使用客户端连接时使用的 host |
| `transferPort` | `0` | transfer 目标端口；0 = 服务器端口 |
| `transferTokenSeconds` / `transferBindIp` | `120` / `true` | 一次性 transfer 凭据有效期 / 绑定 IP |
| `kickOnTimeout` | `true` | `true` = 超时踢出；`false` = 不超时，保留对话框继续等待（会一直占用这条连接） |
| `maxLoginAttempts` / `ipBanSeconds` | `5` / `300` | 密码错误几次后临时封禁 IP，封多久 |
| `language` | `zh_cn` | `zh_cn` 或 `en_us` |
| `offlineSkinsEnabled` | `true` | 离线皮肤总开关 |
| `offlineSkinSiteRoot` | `https://littleskin.cn/csl/` | 皮肤站 CustomSkinAPI 根地址 |

完整字段说明（含皮肤、哈希迭代、缓存等）见
[技术文档 §6](docs/TECHNICAL.md#6-配置)。

### 建议的上线顺序

1. 先保持 `premiumNameProtection=false` 让正版玩家进服，用 `/register <密码> <确认密码>` 设置离线备份密码；
2. 主要正版玩家都设置完之后，把 `premiumNameProtection` 改成 `true`，执行 `/plogin reload`；
3. 之后离线冒用正版名会被明确拒绝，不会出现「请先注册」和「已经注册」来回循环。

> `premiumNameProtection=false` 期间，离线玩家可以用和正版玩家一样的名字。
> 真实正版上线时会踢掉离线号，但这段时间存在冒名风险，正式服不建议长期保持 `false`。

---

## 命令

注册 / 登录已经移到连接阶段的对话框，玩家进服后不需要再输入密码。

### 玩家

| 命令 | 说明 |
|---|---|
| `/register <密码> <确认密码>` | 仅正版玩家：设置 / 更新离线备份密码 |
| `/logout` | 登出：断开连接，重新连接后会再次看到登录对话框 |
| `/changepassword <旧密码> <新密码>` | 修改密码（离线账号或正版离线备份密码） |

别名：`/reg`、`/cp`（如果没被其他模组占用）。

### 管理员（OP）

| 命令 | 说明 |
|---|---|
| `/plogin reload` | 重载配置与语言 |
| `/plogin list` | 已注册用户数 |
| `/plogin info <玩家>` | 查看 UUID、账号类型、登录状态、最后登录时间 |
| `/plogin unregister <玩家>` | 删除这条注册记录（不动世界存档；在线玩家重新连接后生效） |
| `/plogin resetpassword <玩家> <新密码>` | 重置离线密码 |

---

## 数据与备份

全部在 `config/plogin/` 下：

| 文件 | 内容 |
|---|---|
| `config.json` | 配置 |
| `users.json` | 离线账号（只存 PBKDF2 盐和哈希，不存明文密码） |
| `premium-names.json` | 已验证过的正版名 → 正版 UUID，用于离线冒名保护 |

**建议把整个 `config/plogin/` 目录纳入日常备份**，它决定了谁能用哪个名字登录。
换机器迁移服主数据时，把这个目录一起搬走即可。

---

## 从 1.0.x 升级

1.0.x 使用 `plogin:limbo` 虚拟维度隔离未登录玩家；1.1.0 改为配置阶段对话框，不再需要 limbo。

- 直接替换 `mods/` 里的 jar 并重启即可；`config/plogin/` 下的账号与配置完整保留。
- 旧世界的 `level.dat` / `server.properties` 可能仍启用了名为 `plogin` 的数据包。1.1.0 在 jar 里保留了一个
  空的数据包目录，启动时只会出现 `Found new data pack plogin`，不会报 `Missing data pack plogin`，可以忽略。
- 如果某位玩家的 playerdata 仍停留在 limbo 维度坐标（旧版本崩溃时才可能出现），原版会因为找不到
  `plogin:limbo` 而回退到主世界出生点，不会卡住登录。
- 旧版本写入的 `Invulnerable` / `NoGravity` 冻结标记会在进服时自动清理。
- 不再提供 `/login`：登录在连接阶段完成；`/logout` 会断开连接，重新连接后再走一次对话框登录。
- **离线备份密码改为“对话框验证 + 原版 transfer”**：密码通过后先清 dialog，再 transfer 回同一服务端；
  重连时用一次性 Cookie 凭据换成正版 UUID/存档。需要在 `server.properties` 设置 `accepts-transfers=true`。
- 正版同名在线时，备份密码登录会被拒绝并提示等待（不会踢人），请先让对方下线。
- 客户端在 transfer 时会看到原版“正在转移到另一个服务器”的加载界面，这是正常流程。

---

## 常见问题

**为什么进服后先看到一个对话框？**
这是原版 Configuration 阶段的登录对话框。首次进入输入两次密码注册，之后输入一次密码登录；
点击「退出服务器」会断开连接，超时会按配置踢出或继续等待。

**离线玩家提示「无效会话（请尝试重启游戏及启动器）」**
说明启动器在登录握手时发了随机 UUID 而不是离线 UUID。服务端日志里能看到
`登录开始 ... 离线 profileId=false`。PCL2、HMCL 默认是正常的，遇到这种情况请换启动器或换版本。

**离线客户端提示「该用户名已被正版账号占用」**
这说明这个 ID 之前已经用正版账号验证过，服务端在 `premium-names.json` 里记录了它，而当前离线连接没有离线备份密码。
这是 `premiumNameProtection` 的预期行为（1.1.0 会在登录阶段直接显示这条原因，不再让客户端只报“无效会话”）。三种处理方式：

1. 用正版账号进服，执行 `/register <密码> <确认密码>` 设置离线备份密码；之后离线客户端会看到登录对话框；
2. 把 `config.json` 的 `premiumNameProtection` 改为 `false`，执行 `/plogin reload`；离线可以注册该名字，真实正版上线时会踢掉离线号；
3. 换一个离线游戏 ID。

**正版玩家进服后 UUID 变成离线、皮肤也没了**
两个常见原因：启动脚本里带了 `-Djava.net.preferIPv6Addresses=true`（删掉即可），
或者当时 Mojang 会话服务器不可达（`fallbackToOfflineWhenAuthServerDown` 放行了离线）。

**离线玩家没有皮肤**
正版名直接取 Mojang 皮肤；离线名需要皮肤站上有同名皮肤，再由 MineSkin 签名。
MineSkin 游客限流较严（约 10 次/分钟），人多时建议申请 API Key 填到 `offlineSkinMineSkinApiKey`。

**备份密码登录的流程是什么？**
离线客户端先在 Configuration 对话框输入备份密码；通过后服务端清掉 dialog，并发送原版 transfer 包。
客户端带着一次性 Cookie 重新连接到同一服务端，登录阶段换成正版 UUID，直接进入正版存档。
因此需要在 `server.properties` 开启 `accepts-transfers=true`；客户端会看到一次原版 transfer 加载界面。
如果正版同名在线，会提示等待其下线，不会踢人。

**玩家忘记密码**
OP 执行 `/plogin resetpassword <玩家> <新密码>`。整条记录不要了就用 `/plogin unregister <玩家>`。

**能不能只允许正版玩家进？**
那就直接用原版 `online-mode=true`，不需要这个模组。PLogin 的价值在于让两类玩家共存。

**能和其他认证插件一起用吗？**
不建议。其他认证插件也会拦截登录 / 注册流程，和 PLogin 的配置阶段对话框会互相打架，只保留一个。

**升级到新版本 Minecraft 怎么办？**
这是依赖特定版本内部实现的模组（Mixin 直接改配置阶段任务队列），换版本必须重新编译适配，
不能直接把旧 jar 丢进新版本的 `mods/`。

更多排查细节和边界情况见[技术文档 §14 已知限制](docs/TECHNICAL.md#14-已知限制)。

---

## 安全提醒

PLogin 会改变服务器的安全边界，上线前请确认这几点：

- **密码在对话框里输入**，不经过聊天栏，也不会进入命令日志。
- **离线备份密码是真实凭据**。泄露后，别人可以在 Mojang 验证不可用时用你的正版名进入。
  请引导玩家使用强密码，不要和其他账号共用。
- **transfer 凭据**是一次性 16 字节随机数，经原版 Cookie 通道下发/取回，默认绑定 IP、120 秒过期、
  消费一次即销毁；它只用于把这次连接切到 `users.json` 里记录的正版 UUID，不包含正版会话令牌。
- **为了离线玩家能聊天，模组会强制 `enforce-secure-profile` 有效值为 `false`**。
  该设置控制的是服务器是否**强制要求**玩家提供带签名的实名公钥/签名配置，并不等于剥除
  已有聊天签名：正版玩家本就带公钥，其聊天消息仍然可以带 Mojang 签名；受影响的只有
  无法提供公钥的离线玩家。介意的话可以关掉 `forceDisableSecureProfile`，但离线玩家将无法聊天。
- **IP 封禁只存在内存里**，重启后清空；账号与正版名记录是持久化的。

发现安全问题请按 [SECURITY.md](SECURITY.md) 的方式私下反馈，不要开公开 issue。

---

## 已知限制

- 某个正版名**第一次**登录之前，离线玩家理论上可以抢注同名（正版一上线就会被踢并永久记录）。
- 旧离线 UUID 产生的世界存档**不会自动合并**到正版 UUID，需要手动迁移（步骤见技术文档）。
- 离线备份密码登录依赖原版 transfer（`accepts-transfers=true`）和 Cookie；服务端无法确定 transfer
  目标地址时会回退为离线 UUID 登录（日志会输出 WARN）。
- 正版同名玩家在线时，离线备份密码登录会被拒绝（不会踢人），需要等对方下线后再试。
- 离线 UUID 对名字大小写敏感，请引导玩家保持固定的游戏 ID 大小写。
- 皮肤站披风无法被 MineSkin 签名，只有本机可见（除非使用正版账号的签名属性）。
- 未登录玩家仍是一条配置阶段的连接，服务器日志和连接列表能看到；但他们不在 PlayerList、Tab 或世界中。
- `kickOnTimeout=false` 时，未完成登录的连接会一直占用一个玩家槽位，直到玩家自己退出或断线。
- 对话框依赖 26.x 客户端的原版 Dialog 界面；客户端版本必须与服务端一致（原版只接受同协议客户端）。
- 原版 Dialog 没有密码遮罩控件，登录/注册的密码框输入时可见；请提醒玩家注意周围环境。

---

## 构建

仓库不附带 Minecraft 服务端 jar（Mojang 版权产物）。一条命令就能把 26.2 的依赖全部拉下来并编译
（PowerShell 用 `-McVersion 26.3`、bash 用 `--mc 26.3` 可以拉其他版本）：

```bash
./scripts/fetch-deps.sh --build      # Linux / macOS
```

```powershell
.\scripts\fetch-deps.ps1 -Build      # Windows
```

脚本会自动完成：解析 Mojang 版本元数据 → 下载服务端 bundler 并解出内层 jar → 下载 131 个库 →
解析 Fabric Loader 与 Fabric API 版本 → 拆分 Fabric API 的嵌套模块 → 调用构建。

产物：`build/plogin-1.1.1.jar`，依赖落在 `.mcserver/`（不进入版本库）。

发布用的多版本 jar 用按版本构建脚本生成，产物命名为 `PLogin-<Minecraft 版本>-v<模组版本>.jar`：

```powershell
.\scripts\build-version.ps1 -McVersion 26.1
.\scripts\build-version.ps1 -McVersion 26.3
```

各版本差异（例如 26.3 的 authlib 10 适配）集中在该脚本里，细节见[版本适配可行性](docs/版本适配可行性.md)。

已经装好服务端、只想编译的话，直接用 `./build.sh` 或 `.\build.ps1`，
用 `MC_SERVER` 指向服务端目录即可。细节见[技术文档 §4](docs/TECHNICAL.md#4-构建)。

---

## 文档 / 目录

| 位置 | 内容 |
|---|---|
| [docs/TECHNICAL.md](docs/TECHNICAL.md) | 实现原理、Mixin 清单、认证状态机、数据格式、实测记录 |
| [docs/文案.md](docs/文案.md) | 宣传文案、玩家提示、开服公告模板、命令说明 |
| [scripts/fetch-deps.sh](scripts/fetch-deps.sh) / [.ps1](scripts/fetch-deps.ps1) | 一键拉取依赖并构建（默认 26.2） |
| [scripts/build-version.ps1](scripts/build-version.ps1) | 按 Minecraft 版本构建发布 jar |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 开发环境、代码结构、提交规范 |
| [SECURITY.md](SECURITY.md) | 安全问题反馈方式 |
| [CHANGELOG.md](CHANGELOG.md) | 版本变更记录 |

---

## 许可证

本项目以 **GNU Affero General Public License v3.0** 发布，全文见 [LICENSE](LICENSE)。

简单说：

- 你可以自由使用、修改、分发这个模组，包括开商业服；
- 分发修改版时，必须同样以 AGPL 提供完整源码；
- **如果你把修改版作为网络服务提供给玩家（例如开一个公开服务器），也需要向玩家提供源码。**

Minecraft 及其相关类的版权归 Mojang Studios 所有。
