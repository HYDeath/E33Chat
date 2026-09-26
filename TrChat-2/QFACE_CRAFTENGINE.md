# QFace 转换为 CraftEngine 表情包

适配提供的 `craft-engine-paper-plugin-26.9.2-SNAPSHOT.jar`。输入是 ItemsAdder
`QFace/configs/qfaces.yml` 及 `resourcepack/qqnt_sysface_res/textures/qface/*.png`。
转换结果为一个可放入 CraftEngine `resources/` 的包，无需修改或重建 TrChat、E33Chat。

## 安装

1. 解压 `QFace-CE.zip`，将里面的 `qface` 文件夹放到
   `plugins/CraftEngine/resources/qface/`。
2. 原包要求 `qface` 权限，转换后保持这一要求。如果所有普通玩家都可使用，
   LuckPerms 管理员可执行 `/lp group default permission set qface true`。
3. 执行 `/ce reload all`，按服务器原有流程生成并发送更新的资源包，让玩家接受。
4. E33Chat 玩家重新进入服务器，配套 TrChat 会刷新 CE 表情目录。

发送示例：`:qface_0:`、`:qface_100:`。图片调试：
`/ce debug image qqnt_sysface_res:qface_0`。

如果聊天中仍显示短码，先检查玩家权限以及包是否加载；如果短码已变成方框，
检查玩家是否成功加载包含本包的服务器资源包。

## 转换内容

- 376 张 PNG 按原字节复制，原包保留。
- 保留 `qface_N` 编号、原命名空间、U+EE00 起的码点、9 像素高度及 8 像素基线。
- 使用独立字体 `qface:f`，不向 `minecraft:default` 添加码点。
- 每张图片对应一项 CE `images` 和一项 `emoji`，触发词为 `:qface_N:`。
- 使用白色显示图片，避免继承玩家称号或聊天文字的颜色；不添加悬浮提示等额外样式。
- 简短字体 ID 让全部 376 项带图片预览的 E33 目录约为 22.5 KB，低于当前 24,000 字节上限。

## 生成工具

需要 Python 3.9+ 和 PyYAML，输出目录及 ZIP 必须尚不存在：

```powershell
python -m pip install PyYAML
python scripts/convert_qface_to_craftengine.py "E:/path/QFace" "E:/output/QFace-CE"
```

工具检查所有 PNG 的头信息、路径、字符及度量，并逐张比较复制前后的 SHA-256。
生成 `QFace-CE/` 和 `QFace-CE.zip`，ZIP 内顶层目录为 `qface/`。

## 验证范围

2026-09-26 使用提供的 CE JAR 的 EmojiParser 解析全部 376 个配置条目；使用
BitmapImage 构造对应字体图片，ImageIO 成功解码所有 PNG。对每个条目用 Adventure
构造短码替换后的字体组件并通过 TrChat 使用的 JSON 序列化器往返校验，目录为
22,478 字节，376 项均带图片预览。原图与复制文件哈希一致。

这些检查在独立 Java 进程完成，没有启动正式 Paper 服务器。服务器加载、资源包托管、
权限分配及玩家实际收发仍须按上述安装步骤确认。

配置依据：[CE 包目录](https://ce-pre.gtemc.cn/zh-Hans/getting_start/project_structure)、
[图片](https://ce-pre.gtemc.cn/configuration/image)、
[表情](https://ce-pre.gtemc.cn/configuration/emoji)。
