package cn.xingchen.e33.client;

/** Prevent legacy physical-pixel preferences from squeezing the conversation at high GUI scales. */
public final class XingchenLayout {
    private XingchenLayout() { }

    public static int panelWidth(int physical, double scale, int width, int sidebar, boolean fullscreen) {
        int available = Math.max(1, width - sidebar);
        if (fullscreen) return available;
        double safeScale = Double.isFinite(scale) && scale > .01 ? scale : 1;
        int preferred = (int) Math.round(Math.max(0, physical) / safeScale);
        int floor = Math.min(300, (int) Math.round(width * .56));
        int cap = Math.min(available, Math.max(floor, (int) Math.round(width * .48)));
        return Math.max(1, Math.min(cap, Math.max(floor, preferred)));
    }
}
