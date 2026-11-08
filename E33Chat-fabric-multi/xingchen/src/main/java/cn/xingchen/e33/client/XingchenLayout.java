package cn.xingchen.e33.client;

/** Layout for the Xingchen chat panel: readable at every GUI scale, proportional to the window. */
public final class XingchenLayout {
    private XingchenLayout() { }

    /**
     * The Xingchen client keeps this layout before the optional server bridge
     * handshake too. The lobby has no XC-Core channel yet, but its chat screen
     * still needs the same readable width as the formal server.
     */
    public static boolean useForVariant() {
        return true;
    }

    /**
     * A legacy physical-pixel preference must never collapse the conversation
     * into a narrow column again. The panel keeps at least 60% of the space
     * beside the sidebar and never exceeds 80%, so some of the world stays
     * visible while messages have a normal reading width.
     */
    public static int panelWidth(int physical, double scale, int width, int sidebar, boolean fullscreen) {
        int available = Math.max(1, width - sidebar);
        if (fullscreen) return available;
        double safeScale = Double.isFinite(scale) && scale > .01 ? scale : 1;
        int preferred = (int) Math.round(Math.max(0, physical) / safeScale);
        int floor = Math.min(available, Math.max(360, (int) Math.round(available * .60)));
        int cap = Math.min(available, Math.max(floor, (int) Math.round(available * .80)));
        return Math.max(1, Math.min(cap, Math.max(floor, preferred)));
    }
}
