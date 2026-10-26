// Fail closed on upstream changes instead of publishing a partially applied overlay.
def edit = { String path, Closure patch ->
    File f = new File(generated, 'com/niuqu/chatbubble/' + path)
    f.setText(patch(f.getText('UTF-8')), 'UTF-8')
}
def replace = { String source, String from, String to ->
    if (source.count(from) != 1) throw new IllegalStateException('Missing/ambiguous Xingchen anchor: ' + from.take(100))
    source.replace(from, to)
}
edit('ChatBubbleScreen.java') { s ->
    s = replace(s, '&& minecraft.getSingleplayerServer() == null;',
        '&& (cn.xingchen.e33.client.XingchenBridge.ready() || minecraft.getSingleplayerServer() == null);')
    s = replace(s, 'return Component.translatable("e33chat.sidebar.public").getString();',
        'return cn.xingchen.e33.client.XingchenBridge.ready() ? "星辰纪元 · 聊天" : Component.translatable("e33chat.sidebar.public").getString();')
    s = replace(s, '        if (whisperPartner != null) return whisperPartner;',
        '        if (whisperPartner != null) return whisperPartner;\n        if (cn.xingchen.e33.client.XingchenBridge.ready() && "#private".equals(com.niuqu.chatbubble.chat.GroupChannelState.active())) return cn.xingchen.e33.client.XingchenBridge.recipient();')
    s = replace(s, '        tabs.add(new Object[]{cx[0], TAB_H - 2, "+", "+"});',
        '        if (!cn.xingchen.e33.client.XingchenBridge.ready()) tabs.add(new Object[]{cx[0], TAB_H - 2, "+", "+"});')
    s = replace(s, '        int overhead = 0;',
        '''        if (cn.xingchen.e33.client.XingchenBridge.ready()
                && (whisperPartner == null || cn.xingchen.e33.client.XingchenBridge.prompt()))
            return com.niuqu.chatbubble.ui.ChatEmojiPanel.CHAT_PACKET_LIMIT - cn.xingchen.e33.client.XingchenBridge.overhead();
        int overhead = 0;''')
    s = replace(s, '        if (whisperPartner != null && !text.startsWith("/")) {',
        '        if (whisperPartner != null && !text.startsWith("/")\n                && !(cn.xingchen.e33.client.XingchenBridge.ready() && cn.xingchen.e33.client.XingchenBridge.prompt())) {')
    s = replace(s, '        String groupTarget = null;',
        '''        boolean xc = cn.xingchen.e33.client.XingchenBridge.ready();
        boolean xcPrompt = xc && cn.xingchen.e33.client.XingchenBridge.prompt();
        if (xc && (whisperPartner == null || xcPrompt) && !text.startsWith("/")) {
            try { text = cn.xingchen.e33.client.XingchenBridge.outgoing(text); }
            catch (IllegalArgumentException ex) {
                minecraft.player.sendSystemMessage(Component.literal("系统频道只读，请切换世界、公会或队伍频道"));
                return;
            }
        }
        String groupTarget = null;''')
    s = replace(s, 'if (whisperPartner == null && !text.startsWith("/") && com.niuqu.chatbubble.chat.GroupChannelState.supported()) {',
        'if (!xc && whisperPartner == null && !text.startsWith("/") && com.niuqu.chatbubble.chat.GroupChannelState.supported()) {')
    s = replace(s, 'boolean localBubble = !text.startsWith("/") || whisperTarget != null;',
        'boolean localBubble = !xc && (!text.startsWith("/") || whisperTarget != null);')
    s = replace(s, '        if (whisperTarget != null) ChatMessageStore.markPendingWhisperEcho(whisperTarget);',
        '        if (!xc && whisperTarget != null) ChatMessageStore.markPendingWhisperEcho(whisperTarget);')
    s = replace(s, '        if (whisperPartner != null) {\n            messages = ChatMessageStore.getWhisperMessages(whisperPartner);\n        } else {',
        '''        if (whisperPartner != null || "#private".equals(com.niuqu.chatbubble.chat.GroupChannelState.active())) {
            String partner = whisperPartner == null ? cn.xingchen.e33.client.XingchenBridge.recipient() : whisperPartner;
            messages = partner.isBlank() ? java.util.List.of() : ChatMessageStore.getWhisperMessages(partner);
        } else {''')
    s = replace(s, '            com.niuqu.chatbubble.chat.GroupChannelState.TAB_SYSTEM);',
        '''            com.niuqu.chatbubble.chat.GroupChannelState.TAB_SYSTEM);
        if (cn.xingchen.e33.client.XingchenBridge.ready() && !cn.xingchen.e33.client.XingchenBridge.recipient().isBlank())
            add.accept("私聊", "#private");''')
    s
}
edit('chat/GroupChannelState.java') { s ->
    s = replace(s, 'return TAB_WORLD.equals(tab) || TAB_SYSTEM.equals(tab);',
        'return TAB_WORLD.equals(tab) || TAB_SYSTEM.equals(tab) || "#private".equals(tab);')
    s
}
edit('store/ChatMessageStore.java') { s ->
    s = replace(s, '&& last.content().getString().equals(content.getString())) {',
        '''&& java.util.Objects.equals(last.group(), group)
                && last.whisper() == whisper && java.util.Objects.equals(last.whisperPartner(), whisperPartner)
                && last.content().getString().equals(content.getString())) {''')
    String anchor = '    public static void addBridgeMessage(com.niuqu.chatbubble.network.BridgeChatPayload payload) {'
    s = replace(s, anchor, '''    public static void addXingchenMessage(cn.xingchen.e33.protocol.XcProtocol.Message packet) {
        if (bridgeSeen.putIfAbsent(packet.id(), Boolean.TRUE) != null) return;
        Component display = HistoryStore.componentFromJson(packet.displayJson());
        Component body = HistoryStore.componentFromJson(packet.bodyJson());
        Component rendered = HistoryStore.componentFromJson(packet.renderedJson());
        if (display == null) display = Component.literal(packet.account());
        if (body == null) return;
        if (BlockList.isPlayerBlocked(packet.account(), display, ChatBubbleClientSetup.config().blockedPlayers())) return;
        var local = localPlayerSupplier.get();
        boolean own = local != null && packet.sender().equals(local.getUUID());
        boolean pm = "pm".equals(packet.channel());
        String partner = pm ? (own ? packet.recipient() : packet.account()) : null;
        String group = switch (packet.channel()) { case "guild" -> "公会"; case "party" -> "队伍"; default -> null; };
        addMessage(body, packet.sender(), display, false, packet.account(), pm, partner, false, group);
        var mc = Minecraft.getInstance();
        if (mc.gui != null && mc.gui.hud != null && rendered != null) {
            projectingBridgeHud = true;
            try { mc.gui.hud.getChat().addClientSystemMessage(rendered); }
            finally { projectingBridgeHud = false; }
        }
    }

    public static void clearXingchenView() {
        messages.clear(); backlogKeys.clear(); unreadCount = 0;
        hasUnreadMentionFlag = false; unreadWhisperPartners.clear(); historyDirty = true;
    }

''' + anchor)
    s
}
