import cn.xingchen.e33.client.XingchenLayout;

/** Dependency-free check, runnable with the target's Java toolchain. */
public final class XingchenLayoutCheck {
    public static void main(String[] args) {
        int checks = 0;
        for (int width : new int[]{320, 427, 640, 960, 1280, 1920}) {
            for (double scale : new double[]{0, 1, 2, 3, 4, 6, 8, Double.NaN}) {
                for (int physical : new int[]{0, 100, 500, 1000, 4000}) {
                    int sidebar = 90;
                    int available = Math.max(1, width - sidebar);
                    int panel = XingchenLayout.panelWidth(physical, scale, width, sidebar, false);
                    int floor = Math.min(available, Math.max(360, (int) Math.round(available * .60)));
                    if (panel < floor || panel + sidebar > width) throw new AssertionError("Width/scale regression");
                    if (XingchenLayout.panelWidth(physical, scale, width, sidebar, true) != available)
                        throw new AssertionError("Fullscreen regression");
                    checks++;
                }
            }
        }
        int common = XingchenLayout.panelWidth(1000, 2, 1280, 90, false);
        if (common < 700) throw new AssertionError("Default 1080p layout is still squeezed: " + common);
        System.out.println("PASS: " + checks + " physical width / GUI scale cases; 1280@2 panel=" + common);
    }
}
