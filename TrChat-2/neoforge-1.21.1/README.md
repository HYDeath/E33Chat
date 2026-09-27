# TrChat NeoForge 1.21.1

服务端聊天模组，版本 `2.5.3+neo.3`。用于 Minecraft **1.21.1 / NeoForge 21.1.x / Java 21**，独立构建，不依赖 Bukkit、TabooLib、PlaceholderAPI 或运行时下载库。编译目标为 NeoForge **21.1.252**。客户端无需安装这个服务端模组，照常使用服务器要求的模组包即可。

## 安装与跨服

1. 将 `TrChat-NeoForge-1.21.1-2.5.3+neo.3.jar` 放进模组服的 `mods/`，更新时替换旧版本，目录中只保留一个 TrChat 模组 JAR。Paper/Folia 服务器继续使用本仓库的 Bukkit TrChat JAR。
2. 首次正常启动后生成 `config/trchat-neoforge.json`。设置 `serverName` 为玩家看到的名称，`directoryId` 为网络内唯一的数字；它不能与其他 NeoForge 节点或 Paper 后端的端口数字重复。
3. 跨服时将 NeoForge 配置的 `redis.enabled` 改为 `true`，填写 Redis 连接信息。Paper 的 TrChat `settings.yml` 中同样启用 `Redis.enabled`，并使用相同 Redis 与发布频道 `trchat-message`。Paper 可保持 `Options.Proxy: AUTO` 或指定 `REDIS`。
4. 执行 `/trchat reload` 重载 NeoForge 配置。Paper 根据其现有流程重载或重启。Velocity/Bungee 只需承担玩家连接；此版本的聊天互通走 Redis，**不使用代理插件消息桥**。
5. 用 `/trchat info` 检查连接，再测试两端公开聊天、双向 `/msg` 与 `/r`。NeoForge 节点在 Redis 连上后立即交换目录；Paper 目录按其既有周期同步。玩家目录每 10 秒更新，超过 30 秒未更新的远端玩家会过期。

配置示例（请替换实际连接信息，不要提交含密码的运行配置）：

```json
{
  "serverName": "模组服",
  "directoryId": 25570,
  "serverPrefix": "&#8AE7B7[&#89E98C模&#87EB61组&#86EC35服&#84EE0A]",
  "publicFormat": "{prefix} {display}: {message}",
  "maxMessageLength": 256,
  "cooldownMillis": 1000,
  "blockRepeatedMessages": true,
  "showJoinLeave": false,
  "itemHover": true,
  "blockedWords": [],
  "groups": ["team", "trade"],
  "redis": {
    "enabled": true,
    "host": "127.0.0.1",
    "port": 6379,
    "username": "",
    "password": "",
    "database": 0,
    "ssl": false,
    "channel": "trchat-message",
    "timeoutMillis": 3000
  }
}
```

`publicFormat` 的字段由模组自身直接填充，**不需要 PAPI 或任何变量扩展**：

- `{prefix}`：读取 `serverPrefix`，支持 `&#RRGGBB` 十六进制与 `&a`、`&l`、`&r` 等传统颜色/格式。渐变前缀只影响前缀；原生玩家名与正文保留各自样式。
- `{server}`：直接读取配置中的 `serverName`，例如 `模组生存服`，无需 `%server_name%`。
- `{display}`：读取 NeoForge 原生 `player.getDisplayName()`。没有昵称模组或队伍前后缀时，正常显示玩家登录账号名；有服务端昵称/称号模组时，沿用其原生显示名与颜色。空显示名回退到账号名。
- `{player}`：读取玩家登录档案 `player.getGameProfile().getName()`，始终使用真实账号名，不受昵称影响。
- `{message}`：当前聊天正文。

例如账号 `Steve`、服务器名 `模组生存服`，配置 `serverPrefix` 为 `[{server}]` 时显示 `[模组生存服] Steve: 你好`。需要始终显示真实账号时，将 `publicFormat` 改为 `[{server}] {player}: {message}`。不需要为每个玩家手填名字，也不需要根据 UUID 联网查询名字。公开聊天、私聊、群聊、玩家目录与 E33 数据使用一致的原生名称来源；私聊目标、屏蔽和回复记录仍用账号定位，避免昵称改变后发错人。私聊与群聊同样保留本服原生显示名的颜色。

NeoForge 1.21.1 的 [官方玩家补丁](https://github.com/neoforged/NeoForge/blob/1.21.1/patches/net/minecraft/world/entity/player/Player.java.patch) 和 [NameFormat 事件](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/event/entity/player/PlayerEvent.java) 实现了上述原生昵称、队伍前后缀处理。只在客户端修改头顶标签的模组，不一定向服务端提供聊天显示名。请勿将 Bukkit 的 `%player_name%`、`%cmi_user_display_name%` 等 PAPI 表达式填入 NeoForge 配置；这里无需这些插件依赖。E33 模板里的 `{display_name}` 等字段属于 E33 客户端自带模板字段，也不依赖 PAPI。

`blockedWords` 按不区分大小写的字面包含关系过滤。Redis 支持密码、ACL 用户名、数据库与 TLS；启用 TLS 时校验证书和主机名。跨服网络须使用同一频道；Redis 数据库不隔离发布订阅。

## 功能与命令

- 公开聊天：普通输入；`/trchat global <消息>` 可从群聊暂时向公开频道发言。支持服务器前缀、显示名、点击链接与跨服 `@账号` / `@唯一昵称` 提及。含空格昵称可写 `@"小 张"`；不区分大小写，避免短名字误匹配、邮箱与链接误触发。提及高亮可点击补全 `/msg`；普通客户端接收提示与声音，兼容 E33 客户端交由客户端提醒。
- 物品展示：在公开聊天、私聊或群聊输入 `[item]` 或 `[i]`，展示当前主手物品与数量，悬停显示物品信息。包含模组物品；对未安装该物品模组的 Paper/客户端，能否完整解码取决于其物品注册表，至少保留名称与数量文本。超大物品数据降级为文字悬停。
- 私聊：`/msg <账号或唯一昵称> <消息>`（含空格或中文昵称使用双引号，如 `/msg "小 张" 你好`），别名 `/tell`、`/w`、`/m`；回复 `/r <消息>` 或 `/reply <消息>`。本服私聊只发送给双方；跨服收到目标节点确认后才显示发送成功，5 秒未确认会提示失败。Redis 故障时拒绝跨服私聊；没有离线私信队列。
- 玩家与服务器信息：`/trchat list`、`/trchat info`。远端 Paper 节点没有发送服务器名称字段时，列表显示其目录数字；聊天中的 Paper 来源名称仍由原有 TrChat 配置决定。
- 屏蔽：`/ignore <账号>` 切换屏蔽，再次输入取消；`/ignore` 查看列表。作用于接收端的公开聊天、私聊、群聊与本地历史显示。使用账号名可屏蔽离线玩家；在线唯一昵称也可解析。
- 群聊：`/trgroup` 列出配置中的固定频道；`/trgroup join <频道>` 加入；之后普通聊天只发给同频道成员；`/trgroup leave` 退出；`/trgroup msg <频道> <消息>` 显式发送。频道成员之间可以跨 NeoForge 节点通信。固定频道开放加入，**不是邀请制私人群组**；一个玩家同时处于一个频道。
- 历史：`/trchat history` 查看当前进程收到的最近 50 条公开消息，私聊与群聊不进入公开历史。本节点公开消息也写入现有 E33 Redis 历史环供 Paper/E33 端使用；NeoForge 当前不回读历史环，避免丢失频道权限信息后重放受限消息。
- 管理：`/trchat mute <在线账号> <秒数>`、`/trchat unmute <在线账号>`；`/trchat globalmute true|false`；`/trchat broadcast <内容>`；`/trchat clear`；`/trchat reload`。禁言同时限制公开聊天、群聊和私聊；清屏只作用于本服普通聊天显示。
- E33：保留可选语义消息、引用、模板同步与管理员 `/e33chat gui`（也可 `/trchat gui`）协议。有兼容 E33 客户端时使用自定义消息，否则使用原版文字聊天。这里没有移植 E33 客户端界面，也不让 Fabric 客户端绕过 NeoForge 模组包要求。

权限使用 NeoForge `PermissionAPI`：`trchat.chat`、`trchat.private`、`trchat.group` 默认允许；`trchat.admin`、`trchat.bypass` 默认 OP 等级 2 允许。支持 NeoForge 权限处理器。来自 Bukkit 的自定义监听权限没有自动映射；未知权限仅 OP 等级 2 可接收，避免公开管理员频道。

## 与 Bukkit 的互通范围

公开聊天、私聊及送达确认、回复目标、玩家目录、公告、全服禁言、E33 消息与模板使用现有 Redis 协议。NeoForge 特有的群聊和单人禁言同步只在 NeoForge 节点生效；屏蔽和群聊选择存储于当前服务器。Paper 玩家可由 Paper 自己的管理体系处理。Redis 不可用时保留本服公开聊天、群聊和私聊，并明确提示跨服失败。

这是独立服务端移植，未声称 Bukkit TrChat 全功能等价。没有移植 PlaceholderAPI/Kether 格式脚本、Bukkit GUI、背包/末影箱打开界面、CustomNameplates/CraftEngine/DiscordSRV 集成、邀请制群组、媒体上传或代理插件消息模式。群聊不会被当作公开消息广播到 Paper。

屏蔽、禁言、群聊选择、回复对象和 E33 显示模板保存于 `<世界目录>/trchat/state.json`，通过临时文件替换保存。单人禁言及全服禁言同时写入共享 Redis 状态；其他 NeoForge 节点接收实时更新。所有在线 NeoForge 节点应保持配置频道一致。

## 构建与验证

在本目录使用 Java 21 执行：

```text
gradlew.bat build
```

产物在 `build/libs/`，安装不带 `-sources` 后缀的 JAR。这里复用上层 Bukkit 源码中的纯 Java E33 协议编解码器；保留完整 `TrChat-2/` 目录结构。构建不会打包 Bukkit 实现。

测试覆盖 Redis FAST_JSON 消息布局、真实 TCP/RESP 双节点消息与私聊确认、断线重连、AUTH/SELECT、UTF-8、畸形包、目录过期、状态持久化，以及公开/私聊/群聊收件人隔离与物品悬停。Windows 中文路径下若 Gradle 测试 JVM 找不到 `GradleWorkerMain`，可用英文目录联接指向仓库，并同时给 Gradle 用户目录设置英文别名，再从别名运行构建。仓库本身无需移动。

尚需真实 NeoForge 模组包与 Paper/Folia 的双服联调、实际 E33 客户端握手和特殊模组物品测试。本次不接受 Minecraft EULA、不启动生产或测试世界。

默认 `showJoinLeave: false`，不向其他服务器同步模组服的加入、退出提示；在线名单仍会同步以支持私信与艾特。原版服务器本地加入/退出提示不由此开关接管。已有配置不会自动覆盖，请将 `serverPrefix` 与 `publicFormat` 改为上方示例，并关闭 `showJoinLeave`。跨服私信回复对象写入共享 Redis 的 `e33chat:v1:reply:<UUID>`，切服后登入或重连会恢复。
