# Xingchen 26.3 variant

This overlay leaves the shared Fabric targets unchanged. Build only
`26.3-xingchen-fabric` using Java 25 and
`-De33.targetVersion=26.3-xingchen-fabric`.

## xc.4

- Keep the original two-pane bubble chat, with a GUI-scale-independent minimum
  conversation width. A legacy narrow physical-pixel preference cannot collapse
  the conversation beside the player sidebar.
- Add a local customer-support poster from the title bar. It displays the supplied
  QR artwork without regenerating or re-encoding it, adds a WeChat scanning hint,
  and returns to the same chat instance.
- Keep the five channel tabs, sidebar private conversations, channel routing,
  deduplication and existing generic-client fallback.

Validation: the dependency-free `tests/XingchenLayoutCheck.java` covers 200
width/scale combinations; the downstream 26.3 real-client regression covers the
existing private/channel features, high GUI scale, the support click, poster and
return action. The fixture test is not a two-player production-server test.

The support poster is in `src/main/resources/assets/e33chat/textures/xingchen/`.
It is local client presentation, not a chat message or an external browser link.
