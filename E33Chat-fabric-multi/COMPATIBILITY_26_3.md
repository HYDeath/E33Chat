# E33Chat Fabric 2.4.15 for Minecraft 26.x

This directory contains the 26.1.2, 26.2, and 26.3 Fabric ports of upstream
Chat-Mod-E 2.4.15. The 26.x artifacts use version `2.4.15+compat.21`. Older
Minecraft targets retain the existing 2.3.15 source and version.

## Source and scope

- Base: `NoWordz/Chat-Mod-E`, tag `v2.4.15` (`8f4d4b5`), shared code and its
  1.21.1 Fabric implementation. Every upstream Java source file and all
  non-manifest resources are present under `src/v2_4_15`, with platform
  adaptations for Minecraft 26.x. Each version has its own `fabric.mod.json`.
- Includes upstream changes from 2.3.16 and 2.4.0 through 2.4.15: redesigned
  chat UI and settings, groups, local and server history, media including
  animated images, parsing/classification safeguards, bounded networking,
  and the 2.4.15 history delivery/merge fixes.
- Preserves this fork's TrChat/Paper bridge, CustomNameplates bubble skin,
  CraftEmoji catalog, Mod Menu entry point, and compact vanilla HUD mirroring.
- Uses Fabric Loom's official-name variant and Java 25. The source adaptation
  includes 26.x screen input events, render extraction, registries, codecs,
  networking, and mixin targets. Each JAR declares its exact Minecraft version.

The upstream 1.21.1 Fabric network protocol was adapted for 26.x. This is a
Fabric port; it is not guaranteed to connect to the upstream Forge/NeoForge
wire protocol or to older E33Chat mod-server builds. The optional TrChat
plugin bridge retains this fork's own payload protocol. The `compat.21` client
decodes the plugin's older history and server-settings packets. Install
TrChat `2.5.3+e33compat.14` for the updated settings-save packet. TrChat does
not implement upstream E33 group chat; that feature requires the Fabric server mod.

## Build

Run each target separately to avoid configuring the older Minecraft projects:

```powershell
.\gradlew.bat '-De33.targetVersion=26.1-fabric' :26.1-fabric:build --offline
.\gradlew.bat '-De33.targetVersion=26.2-fabric' :26.2-fabric:build --offline
.\gradlew.bat '-De33.targetVersion=26.3-fabric' :26.3-fabric:build --offline
```

Output: `versions/<target>/build/libs/e33chat-Fabric-26.X-2.4.15+compat.21.jar`.
The Gradle JVM must use Java 25. This multi-version build currently disables
`compileTestJava` for version projects, so `build` verifies compilation,
resources, and packaging, but does not run the upstream unit tests.

`compat.21` restores the bundled color Emoji font, gives the local-image and
CraftEngine tabs separate responsive labels, and uses a native image-only file
picker. The 26.3 JAR bundles the file-picker library and its platform natives,
which Minecraft 26.3 no longer supplies.

## Verification boundary

Client launches for all three targets reached Minecraft's resource and sound
loading without a mod or mixin startup error. The 26.3 development server
loaded Fabric and stopped at its unaccepted EULA, as intended. Builds cover
all three targets. Gameplay flows still require an in-game check: opening chat/settings, sending and
receiving messages, group/history/media transfer, IME and clipboard input,
and the optional TrChat/CustomNameplates integrations on their actual servers.
