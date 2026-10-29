import cn.xingchen.e33.client.XingchenLayout;

/** Dependency-free check, runnable with the target's Java toolchain. */
public final class XingchenLayoutCheck {
    public static void main(String[] args) {
        int checks = 0;
        for (int width : new int[]{320, 427, 640, 960, 1280}) {
            for (double scale : new double[]{0, 1, 2, 3, 4, 6, 8, Double.NaN}) {
                for (int physical : new int[]{0, 100, 500, 1000, 4000}) {
                    int panel = XingchenLayout.panelWidth(physical, scale, width, 90, false);
                    int floor = Math.min(300, (int) Math.round(width * .56));
                    if (panel < floor || panel + 90 > width) throw new AssertionError("Width/scale regression");
                    if (XingchenLayout.panelWidth(physical, scale, width, 90, true) != width - 90)
                        throw new AssertionError("Fullscreen regression");
                    checks++;
                }
            }
        }
        System.out.println("PASS: " + checks + " physical width / GUI scale cases");
    }
}
