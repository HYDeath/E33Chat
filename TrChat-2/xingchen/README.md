# E33Chat for Xingchen

This is a small, separate Paper/Folia companion, not TrChat and not a replacement for XC-Chat. Install it **alongside the modified XC-Chat build**; it never reads a client-supplied guild/party member list and never chooses message recipients. The existing XC-Core/XC-Chat services remain authoritative for world, guild, party and private delivery, mute/color/filter policy, and chat prompts.

The companion only transports accepted per-recipient deliveries to E33 Fabric clients. Vanilla clients keep receiving the original Adventure chat. It depends on the `cn.xingchen.chat.api` event/provider contract compiled into XC-Chat; do not also install the separate 1.12 TrChat distribution as a chat owner.

The contract sources are under `api/src/main/java`. Copy those three source files into `plugins/XC-Chat/src/main/java/cn/xingchen/chat/api/`, then add the small hooks described in `integration/XC-Chat-hooks.md`. This intentionally does **not** vendor or publish the rest of the private Xingchen server project. Build the updated XC-Chat JAR from the Xingchen workspace first.

## Build

From this directory, use the Java 25 / Gradle 9.6 wrapper shipped with `E33Chat-fabric-multi` and the current XC-Core JAR:

```powershell
..\..\E33Chat-fabric-multi\gradlew.bat -p . -PxcCoreJar=E:\path\to\XC-Core.jar build --no-daemon
```

The resulting `build/libs/E33Chat-Xingchen-26.3-plugin.jar` does not bundle XC-Core or XC-Chat.

## Security and compatibility

- Client payloads carry only a protocol hello. They do not carry text, recipients, group names, member lists, or commands to deliver.
- The E33 client sends channel markers through the normal XC-Chat chat event. The server resolves current guild/party members and validates the private target using its existing code.
- Messages are forwarded independently on each recipient's entity scheduler. If a structured delivery cannot fit the bounded plugin message, that recipient receives XC-Chat's original formatted message instead.
- E33-only state is per online player and is cleared on quit/disable. Existing E33 tab filtering keeps world/system, guild, party, and each private thread separated. The adapter does not touch player data, configuration, databases, maps, or deployment.
- Guild and party tabs are advertised only when XC-Core reports the player's current membership; losing membership removes the tab and the server still revalidates every send.
- The standalone Fabric target and bounded protocol live in `E33Chat-fabric-multi/versions/26.3-xingchen-fabric/` and `E33Chat-fabric-multi/xingchen/`. It is separate from the generic Fabric 26.3 artifact.

## Verification record

The 26.3 companion builds against the current XC-Core JAR; its direct protocol tests pass. The Fabric target compiles and a real 26.3 client GameTest has exercised screen replacement, channel filtering, message-ID deduplication, clear behavior, and all channel markers using fixture deliveries. That test is a client/UI fixture, not a claim of online multi-player or production-server acceptance. Do not install/restart a live server solely from this candidate; validate a private test server first.
