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
    s = replace(s, '        if (fullscreen) {\n            return Math.max(100, width - panelX);',
        '''        if (cn.xingchen.e33.client.XingchenBridge.ready())
            return cn.xingchen.e33.client.XingchenLayout.panelWidth(physicalW, guiScale, width, panelX, fullscreen);
        if (fullscreen) {
            return Math.max(100, width - panelX);''')
    s = replace(s, '        renderTitleBar(g, mouseX, mouseY, getBarAlpha());',
        '''        renderTitleBar(g, mouseX, mouseY, getBarAlpha());
        if (cn.xingchen.e33.client.XingchenBridge.ready()) {
            int supportX = panelX + panelW - 68;
            g.fill(supportX, titleY + 5, supportX + 48, titleY + 20, 0xEE17364D);
            g.text(font, "联系客服", supportX + 4, titleY + 8, 0xFFFFD68B, false);
        }''')
    s = replace(s, '        // Panel contents are translated by panelOffset during the open/close slide;',
        '''        if (cn.xingchen.e33.client.XingchenBridge.ready() && primaryClick(button)
                && !isPanelSliding() && mouseY >= titleY + 5 && mouseY < titleY + 20
                && mouseX >= panelX + panelW - 68 && mouseX < panelX + panelW - 20) {
            minecraft.gui.setScreen(new cn.xingchen.e33.client.CustomerSupportScreen(this));
            return true;
        }
        // Panel contents are translated by panelOffset during the open/close slide;''')
    s = replace(s, 'int titleX = UiLayout.centerX(panelX, panelW, titleW);',
        'int titleX = cn.xingchen.e33.client.XingchenBridge.ready() ? panelX + 24 : UiLayout.centerX(panelX, panelW, titleW);')
    s = replace(s, 'String time = LocalTime.now().format(TIME_FMT);',
        'String time = cn.xingchen.e33.client.XingchenBridge.ready() ? "" : LocalTime.now().format(TIME_FMT);')
    s = replace(s, 'private static boolean sidebarOpen;', 'private static boolean sidebarOpen = true;')
    s = replace(s, '        this.initialText = initialText;',
        '''        this.initialText = initialText;
        if (cn.xingchen.e33.client.XingchenBridge.ready()
                && "#private".equals(com.niuqu.chatbubble.chat.GroupChannelState.active()))
            followXingchenChannel("pm", cn.xingchen.e33.client.XingchenBridge.recipient());''')
    s = replace(s, '    private void insertAvatarMention(String name) {',
        '''    public void followXingchenChannel(String channel, String recipient) {
        whisperPartner = "pm".equals(channel) && recipient != null && !recipient.isBlank() ? recipient : null;
        if (whisperPartner != null) ChatMessageStore.clearUnreadWhisper(whisperPartner);
        scrollToBottom = true;
    }

    private void insertAvatarMention(String name) {''')
    s = replace(s, 'String publicLabel = Component.translatable("e33chat.sidebar.public").getString();',
        'String publicLabel = cn.xingchen.e33.client.XingchenBridge.ready() ? "综合" : Component.translatable("e33chat.sidebar.public").getString();')
    s = replace(s, 'whisperPartner = null; sidebarSearchBox.setValue(""); setFocused(input); scrollToBottom = true; return true;',
        '''whisperPartner = null;
                if (cn.xingchen.e33.client.XingchenBridge.ready())
                    com.niuqu.chatbubble.chat.GroupChannelState.setActive(com.niuqu.chatbubble.chat.GroupChannelState.TAB_ALL);
                sidebarSearchBox.setValue(""); setFocused(input); scrollToBottom = true; return true;''')
    s = replace(s, '&& minecraft.getSingleplayerServer() == null;',
        '&& (cn.xingchen.e33.client.XingchenBridge.ready() || minecraft.getSingleplayerServer() == null);')
    s = replace(s, 'return Component.translatable("e33chat.sidebar.public").getString();',
        'return cn.xingchen.e33.client.XingchenBridge.ready() ? "星辰纪元 · 聊天" : Component.translatable("e33chat.sidebar.public").getString();')
    s = replace(s, '        if (whisperPartner != null) return whisperPartner;',
        '        if (whisperPartner != null) return whisperPartner;\n        if (cn.xingchen.e33.client.XingchenBridge.ready() && "#private".equals(com.niuqu.chatbubble.chat.GroupChannelState.active())) return cn.xingchen.e33.client.XingchenBridge.recipient();')
    s = replace(s, '        tabs.add(new Object[]{cx[0], TAB_H - 2, "+", "+"});',
        '        if (!cn.xingchen.e33.client.XingchenBridge.ready()) {\n            tabs.add(new Object[]{cx[0], TAB_H - 2, "+", "+"});\n        }')
    s = replace(s, '        for (String g : com.niuqu.chatbubble.chat.GroupChannelState.myGroups) add.accept(g, g);', '')
    s = replace(s, '        int overhead = 0;',
        '''        if (cn.xingchen.e33.client.XingchenBridge.ready())
            return com.niuqu.chatbubble.ui.ChatEmojiPanel.CHAT_PACKET_LIMIT - cn.xingchen.e33.client.XingchenBridge.overhead(whisperPartner);
        int overhead = 0;''')
    s = replace(s, '        if (whisperPartner != null && !text.startsWith("/")) {',
        '        if (whisperPartner != null && !text.startsWith("/")\n                && !cn.xingchen.e33.client.XingchenBridge.ready()) {')
    s = replace(s, '        String groupTarget = null;',
        '''        boolean xc = cn.xingchen.e33.client.XingchenBridge.ready();
        if (xc && !text.startsWith("/")) {
            try { text = cn.xingchen.e33.client.XingchenBridge.outgoing(text, whisperPartner); }
            catch (IllegalArgumentException ex) {
                minecraft.player.sendSystemMessage(Component.literal(ex.getMessage() == null
                        ? "无法切换到该频道" : ex.getMessage()));
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
    s = replace(s, '''        add.accept(Component.translatable("e33chat.group.tab_world").getString(),
            com.niuqu.chatbubble.chat.GroupChannelState.TAB_WORLD);
        add.accept(Component.translatable("e33chat.group.tab_system").getString(),
            com.niuqu.chatbubble.chat.GroupChannelState.TAB_SYSTEM);''',
        '''        add.accept(Component.translatable("e33chat.group.tab_world").getString(),
            com.niuqu.chatbubble.chat.GroupChannelState.TAB_WORLD);
        add.accept("公会", "公会");
        add.accept("队伍", "队伍");
        add.accept(Component.translatable("e33chat.group.tab_system").getString(),
            com.niuqu.chatbubble.chat.GroupChannelState.TAB_SYSTEM);''')
    s
}
edit('chat/GroupChannelState.java') { s ->
    s = replace(s, 'return TAB_WORLD.equals(tab) || TAB_SYSTEM.equals(tab);',
        'return TAB_WORLD.equals(tab) || TAB_SYSTEM.equals(tab) || "公会".equals(tab) || "队伍".equals(tab) || "#private".equals(tab);')
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
        if (!pm && mc.gui != null && mc.gui.hud != null && rendered != null) {
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
