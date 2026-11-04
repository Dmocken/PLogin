# PLogin

**Minecraft 26.2 · Fabric 服务端模组 · 正版与离线玩家共存 · 客户端零模组**

服务器继续开着 `online-mode=true`，正版玩家照旧通过验证直接进服，UUID、皮肤、披风全部来自 Mojang；
用离线启动器的玩家也能进，但必须先 `/register` 注册、之后每次 `/login`，登录前被隔离在虚空维度里，
不会暴露主世界坐标，也不会出现在其他玩家的 Tab 列表里。

[快速开始](#快速开始) · [常用配置](#常用配置) · [命令](#命令) · [常见问题](#常见问题) · [技术文档](docs/TECHNICAL.md)

---

## 这个模组解决什么问题

很多服主卡在同一个地方：开 `online-mode=false`，正版玩家的 UUID 就没了，皮肤失效、存档对不上；
开 `online-mode=true`，离线玩家又完全进不来。二选一，总有一半玩家被挡在门外。

PLogin 把两条路合成一条：同一条登录流程里先做正版验证，成功就是正版玩家；
失败（没有有效会话）就按离线玩家处理，走注册 / 登录流程。客户端不需要装任何模组。

| 方案 | 正版玩家 | 离线玩家 | 皮肤 | 客户端 |
|---|---|---|---|---|
| 纯正版服 `online-mode=true` | ✅ 直接进 | ❌ 进不来 | 正常 | 原版 |
| 纯离线服 `online-mode=false` | ⚠️ 丢失正版 UUID，皮肤失效 | ✅ 随便进 | 基本没有 | 原版 |
| 离线服 + 认证插件 | ⚠️ 同上 | ✅ 需要注册 | 基本没有 | 原版 |
| **PLogin** | ✅ 直接进，UUID / 皮肤 / 披风保留 | ✅ 注册后进入 | 正版与离线都能显示 | 原版，零模组 |

---

## 特性

- **正版零打扰**：验证通过直接进入，不弹登录框、不要求输密码，UUID 与 Mojang 一致。
- **离线可注册**：`/register` 自动登录，`/login` 正常进入，`/changepassword` 自助改密。
- **正版名保护**：记录成功验证过的正版名，离线玩家无法冒用，正版一上线就踢掉同名离线号。
- **离线备份密码**：正版玩家可以给自己设一个密码，Mojang 验证不可用时照样用正版名和存档进入。
- **登录前隔离**：未登录玩家待在 `plogin:limbo` 虚空维度，冻结移动、失明、隐藏 Tab、隐藏进出消息，
  并且免疫 `/tp`、`/kill`、`/damage` 和死亡重生。
- **离线也能有皮肤**：正版名直接取 Mojang 签名皮肤；离线名走 LittleSkin + MineSkin 签名。
- **防爆破**：PBKDF2-HMAC-SHA256 加盐存储，输错多次临时封禁 IP。
- **中英双语**：配置里一行切换 `zh_cn` / `en_us`。

---

## 快速开始

### 1. 准备环境

| 项目 | 要求 |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.5 或更高 |
| Fabric API | 对应 26.2 的版本，放在 `mods/` |
| Java | 21 或更高（建议与服务器现有版本一致） |

### 2. 安装

把 `plogin-1.0.0.jar`（从仓库 Releases 下载，或按[构建](#构建)自行编译）放进服务器的 `mods/` 目录，
和 `fabric-api-*.jar` 放在一起，然后重启。

> 客户端什么都不用装。正版客户端直接连，离线启动器客户端也能连并注册。

### 3. 确认 `online-mode=true`

`server.properties` 里必须是 `online-mode=true`。这是混合登录的前提：
PLogin 正是靠「先按正版验证、失败再按离线处理」来区分两类玩家的。

### 4. 启动，确认模组已加载

日志里应该出现：

```text
PLogin 1.0.0 已加载，数据目录: ./config/plogin
[PLogin] 混合登录已启用（online-mode=true）：...
```

配置和账号数据会生成在 `config/plogin/` 下，首次启动自动创建。

### 5. 告诉玩家怎么进服

[docs/文案.md](docs/文案.md) 里有开服公告、给正版玩家的说明、给离线玩家的说明，
整段复制到群公告或服务器简介即可。

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
| `loginTimeoutSeconds` | `120` | 未登录停留多久后踢出（0 = 不超时） |
| `kickOnTimeout` | `true` | `false` 表示超时后转旁观者继续等，而不是踢出 |
| `maxLoginAttempts` / `ipBanSeconds` | `5` / `300` | 密码错误几次后临时封禁 IP，封多久 |
| `blindness` / `freezeMovement` | `true` / `true` | 未登录时失明 / 冻结移动 |
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

### 玩家

| 命令 | 说明 |
|---|---|
| `/register <密码> <确认密码>` | 离线玩家注册（成功后自动登录）；正版玩家用它设置离线备份密码 |
| `/login <密码>` | 已注册玩家登录 |
| `/logout` | 登出，回到未登录隔离状态 |
| `/changepassword <旧密码> <新密码>` | 修改密码 |

别名：`/reg`、`/l`、`/cp`（如果没被其他模组占用）。

### 管理员（OP）

| 命令 | 说明 |
|---|---|
| `/plogin reload` | 重载配置与语言 |
| `/plogin list` | 已注册用户数 |
| `/plogin info <玩家>` | 查看 UUID、账号类型、登录状态、最后登录时间 |
| `/plogin unregister <玩家>` | 删除这条注册记录（不动世界存档） |
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

## 常见问题

**离线玩家提示「无效会话（请尝试重启游戏及启动器）」**
说明启动器在登录握手时发了随机 UUID 而不是离线 UUID。服务端日志里能看到
`Login Start ... offlineProfileId=false`。PCL2、HMCL 默认是正常的，遇到这种情况请换启动器或换版本。

**正版玩家进服后 UUID 变成离线、皮肤也没了**
两个常见原因：启动脚本里带了 `-Djava.net.preferIPv6Addresses=true`（删掉即可），
或者当时 Mojang 会话服务器不可达（`fallbackToOfflineWhenAuthServerDown` 放行了离线）。

**离线玩家没有皮肤**
正版名直接取 Mojang 皮肤；离线名需要皮肤站上有同名皮肤，再由 MineSkin 签名。
MineSkin 游客限流较严（约 10 次/分钟），人多时建议申请 API Key 填到 `offlineSkinMineSkinApiKey`。

**玩家忘记密码**
OP 执行 `/plogin resetpassword <玩家> <新密码>`。整条记录不要了就用 `/plogin unregister <玩家>`。

**能不能只允许正版玩家进？**
那就直接用原版 `online-mode=true`，不需要这个模组。PLogin 的价值在于让两类玩家共存。

**能和其他认证插件一起用吗？**
不建议。同一套命令（`/register`、`/login`）和同一套登录拦截会互相打架，只保留一个。

**升级到新版本 Minecraft 怎么办？**
这是依赖特定版本内部实现的模组（Mixin 直接改登录线程），换版本必须重新编译适配，
不能直接把旧 jar 丢进新版本的 `mods/`。

更多排查细节和边界情况见[技术文档 §17 已知限制](docs/TECHNICAL.md#14-已知限制)。

---

## 安全提醒

PLogin 会改变服务器的安全边界，上线前请确认这几点：

- **离线备份密码是真实凭据**。泄露后，别人可以在 Mojang 验证不可用时用你的正版名进入。
  请引导玩家使用强密码，不要和其他账号共用。
- **为了离线玩家能聊天，模组会强制 `enforce-secure-profile` 有效值为 `false`**，
  代价是所有玩家的聊天都不再带 Mojang 签名，也就失去聊天举报链。介意的话可以关掉
  `forceDisableSecureProfile`，但离线玩家将无法聊天。
- **密码通过聊天命令输入**，请避免把玩家命令记录到公屏或对外公开命令日志。
- **IP 封禁只存在内存里**，重启后清空；账号与正版名记录是持久化的。

发现安全问题请按 [SECURITY.md](SECURITY.md) 的方式私下反馈，不要开公开 issue。

---

## 已知限制

- 某个正版名**第一次**登录之前，离线玩家理论上可以抢注同名（正版一上线就会被踢并永久记录）。
- 旧离线 UUID 产生的世界存档**不会自动合并**到正版 UUID，需要手动迁移（步骤见技术文档）。
- 离线 UUID 对名字大小写敏感，请引导玩家保持固定的游戏 ID 大小写。
- 皮肤站披风无法被 MineSkin 签名，只有本机可见（除非使用正版账号的签名属性）。
- 这是个服务端方案：`/list`、在线人数和服务器日志仍然能看到未登录玩家，其他模组遍历
  PlayerList 时也可能看到，这是后端实现无法回避的边界。

---

## 构建

```bash
./build.sh
# 产物：build/plogin-1.0.0.jar
```

需要一个已安装好的 Minecraft 26.2 服务端目录作为依赖来源，详见
[技术文档 §4](docs/TECHNICAL.md#4-构建) 和 [CONTRIBUTING.md](CONTRIBUTING.md)。

仓库**不包含** Minecraft 服务端 jar（Mojang 版权产物），请自行下载对应版本。

---

## 文档 / 目录

| 位置 | 内容 |
|---|---|
| [docs/TECHNICAL.md](docs/TECHNICAL.md) | 实现原理、Mixin 清单、认证状态机、数据格式、实测记录 |
| [docs/文案.md](docs/文案.md) | 宣传文案、玩家提示、开服公告模板、命令说明 |
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
