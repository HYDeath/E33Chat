package cn.xingchen.e33.protocol;

/** Explicit markers bypass the player's saved /ch channel, never server membership checks. */
public final class ChannelRouting {
    private ChannelRouting() {}
    public static String outgoing(String text, String tab, boolean prompt, String recipient) {
        if (text.startsWith("/") || prompt) return text;
        return switch (tab == null ? "#all" : tab) {
            case "#system" -> "«s»" + text;
            case "公会" -> "«g»" + text;
            case "队伍" -> "«t»" + text;
            case "#private" -> recipient == null || recipient.isBlank()
                    ? throwNoPrivateRecipient() : "«m:" + recipient + "»" + text;
            default -> "«w»" + text;
        };
    }
    private static String throwNoPrivateRecipient() { throw new IllegalArgumentException("请先选择私聊对象"); }
    public static String tab(String channel) {
        return switch (channel) { case "guild" -> "公会"; case "party" -> "队伍"; case "pm" -> "#private"; default -> "#world"; };
    }
}
