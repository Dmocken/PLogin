# 参与贡献

感谢你愿意改进 PLogin。无论是报告问题、补充文档，还是提交代码，都很有价值。

- 发现了**安全问题**？请按 [SECURITY.md](SECURITY.md) 私下反馈，不要开公开 issue。
- 使用中遇到问题？请先用 [常见问题](README.md#常见问题) 和 [技术文档](docs/TECHNICAL.md) 自查一遍。
- 想提新功能？先开 issue 描述使用场景，避免写完才发现方向不对。

## 开发环境

| 项目 | 要求 |
|---|---|
| JDK | 25（Minecraft 26.2 的最低要求） |
| Minecraft | 26.2 服务端 jar（Mojang 官方名，非 intermediary） |
| Fabric Loader | 0.19.5 或更高 |
| Fabric API | 对应 26.2 的版本 |

本项目**不使用 Gradle / Loom**，直接 `javac` 编译。源码里写的是 Mojang 官方映射名
（`net.minecraft.server.level.ServerPlayer` 这种），因为运行时 Fabric Loader 日志里是
`Mappings not present!`。

## 构建

第一次构建，让脚本自己把依赖拉全（推荐）：

```bash
./scripts/fetch-deps.sh --build      # Linux / macOS，需要 bash + curl + python3
```

```powershell
.\scripts\fetch-deps.ps1 -Build      # Windows
```

它会下载深 26.2 的服务端 jar、131 个库、Fabric Loader 与 Fabric API 到 `.mcserver/`，
拆分 Fabric API 的嵌套模块，然后调用下面的构建脚本。

已经有服务端目录时，直接构建：

```bash
# 指向一个已安装好 Fabric + Fabric API 的服务端目录
MC_SERVER=/path/to/minecraft-server ./build.sh
# 产物：build/plogin-1.1.0.jar
```

Windows 用等价脚本：

```powershell
$env:MC_SERVER = 'D:\path\to\minecraft-server'
.\build.ps1
```

未设置 `MC_SERVER` 时，两个脚本都会依次尝试仓库下的 `.mcserver/`、`../minecraft-bingo`、
`$HOME/mc/minecraft-bingo`。

目录需要包含：

- `versions/26.2/server-26.2.jar`
- `mods/fabric-api-*.jar`
- `.fabric/processedMods/*.jar`
- `libraries/**/*.jar`

> 请**不要**把服务端 jar、日志、世界存档提交进仓库，`.gitignore` 已经排除了这些路径。

> Fabric API 的发布 jar 是个 bundle，模块以嵌套 jar 形式放在 `META-INF/jars/` 里。
> 首次编译前需要把它们解压到 `.fabric/processedMods/`，否则 javac 找不到
> `net.fabricmc.fabric.api.*`（实际运行服务端时 Fabric Loader 会自动做这件事）。

## 代码结构

| 路径 | 职责 |
|---|---|
| `PLogin.java` / `PLoginMod.java` | 模组入口、初始化、日志 |
| `auth/ConfigAuth.java` | Configuration 阶段认证状态机（注册 / 登录 / 超时 / 封禁 / 备份 transfer） |
| `auth/AuthConfigurationTask.java` | 插入配置任务队列的阻塞任务 |
| `auth/AuthDialogs.java` | 原版 Dialog 模型（ConfirmationDialog + TextInput + CustomAll） |
| `auth/AccountService.java` | 进服后的改密、正版备份密码、登出 |
| `auth/HybridAuth.java` | 正版验证失败后回退离线 UUID / transfer 凭据换正版 UUID |
| `auth/TransferVerification.java` | 一次性 transfer 凭据（TTL/IP/一次性） |
| `auth/TransferState.java` | 按连接隔离的 transfer Cookie 与 handshake host |
| `auth/PasswordHasher.java` / `UserStore.java` | PBKDF2 哈希、账号持久化 |
| `command/AuthCommands.java` | `/register` `/logout` `/changepassword` `/plogin` 等命令 |
| `config/Config.java` | 配置读写与字段收敛 |
| `skin/SkinService.java` | Mojang / LittleSkin / MineSkin 皮肤属性 |
| `mixin/` | 登录阶段混合验证、配置阶段任务注入、对话框回包 |
| `util/Ui.java` | 标题、聊天提示等客户端界面工具 |

改动 Mixin 前请先读 [docs/TECHNICAL.md](docs/TECHNICAL.md) 的 §2–§7，
里面记录了每个注入点的时序原因（尤其是为什么认证任务必须插在
`returnToWorld` 的 HEAD、排在 PrepareSpawn/JoinWorld 之前）。

## 编码约定

- 面向 Java 21，避免引入新依赖；能用的 JDK 标准库优先。
- 注释用中文，写**为什么**而不是**做了什么**；对外提示文案放在 `Messages.java` 并保持中英双语。
- 新增配置字段要有默认值，并保证 `Config.normalize()` 能兜住非法值。
- 日志统一用简体中文：正文以 `[PLogin] ` 开头（原版日志格式是 `[时间] [线程/级别]: 消息`，不打印 logger 名，只能靠正文区分模组），`{}` 占位、全角标点，句末不加句号；级别不要滥用。
- 不要破坏「客户端零模组」这条底线：任何需要玩家装 mod 的方案都不接受。

## 提交信息

参考 Conventional Commits，一句话说清改动：

```text
feat(auth): 支持正版玩家设置离线备份密码
fix(mixin): 修复 limbo 坐标覆盖真实存档的问题
docs: 补充服主配置说明
chore(build): 构建脚本支持 MC_SERVER 自动探测
```

## Pull Request 检查清单

- [ ] `./build.sh` 能编译通过，没有新增告警
- [ ] 在真实服务端上验证过：正版玩家能进、离线玩家能在配置阶段完成对话框注册 / 登录
- [ ] 没有提交 `build/`、`versions/`、`logs/`、`config/`、世界存档
- [ ] 面向服主的行为变化，已同步更新 [README.md](README.md)
- [ ] 实现细节变化，已同步更新 [docs/TECHNICAL.md](docs/TECHNICAL.md)
- [ ] 玩家可见文案变化，已同步更新 [docs/文案.md](docs/文案.md)

## 许可证

提交代码即表示同意以 [AGPL-3.0](LICENSE) 授权你的贡献。
