import com.niuqu.chatbubble.render.GradientMath;
import com.niuqu.chatbubble.render.LayoutBudget;

public final class ChatLayoutCheck {
    public static void main(String[] args) {
        solidDoesNotMove();
        shortRunDoesNotMove();
        smallGapDoesNotMove();
        smoothRunMoves();
        channelBreakSplitsRuns();
        cycleMatchesOrigin();
        anchorPinsAndKeepsDistance();
        System.out.println("chat layout checks passed");
    }

    private static void solidDoesNotMove() {
        int[] rgb = {0xFF0000, 0xFF0000, 0xFF0000, 0xFF0000, 0xFF0000};
        int[] starts = new int[rgb.length];
        expect(!GradientMath.markRuns(rgb, rgb.length, starts), "solid run must stay");
        for (int start : starts) expect(start < 0, "solid glyph");
    }

    private static void shortRunDoesNotMove() {
        int[] rgb = {0xFF0000, 0xCC0000, 0x990000};
        int[] starts = new int[rgb.length];
        expect(!GradientMath.markRuns(rgb, rgb.length, starts), "three glyphs stay");
    }

    private static void smallGapDoesNotMove() {
        int[] rgb = {0x000000, 0x00000C, 0x000018, 0x000024};
        int[] starts = new int[rgb.length];
        expect(!GradientMath.markRuns(rgb, rgb.length, starts), "channel sum 36 stays");
    }

    private static void smoothRunMoves() {
        int[] rgb = {0x000000, 0x00000C, 0x000018, 0x000025};
        int[] starts = new int[rgb.length];
        expect(GradientMath.markRuns(rgb, rgb.length, starts), "channel sum 37 flows");
        for (int start : starts) expect(start == 0, "one run");
        int mid = GradientMath.colorAt(0x000000, 0xFFFFFF, 1, 3, 0f);
        expect(mid == 0x808080, "midpoint " + Integer.toHexString(mid));
        expect(GradientMath.shift(0L) == 0f, "shift 0");
        expect(GradientMath.shift(1200L) == 0.5f, "shift half");
        expect(GradientMath.shift(2400L) == 0f, "shift lap");
    }

    private static void channelBreakSplitsRuns() {
        int[] rgb = {
            0xFF0000, 0xCC0000, 0x990000, 0x660000,
            0x0000FF, 0x0000CC, 0x000099, 0x000066
        };
        int[] starts = new int[rgb.length];
        expect(GradientMath.markRuns(rgb, rgb.length, starts), "two runs");
        for (int i = 0; i < 4; i++) expect(starts[i] == 0, "left run");
        for (int i = 4; i < 8; i++) expect(starts[i] == 4, "right run");
        expect(!GradientMath.smooth(0x660000, 0x0000FF), "jump is not smooth");
    }

    private static void cycleMatchesOrigin() {
        int origin = GradientMath.colorAt(0xFF0000, 0x0000FF, 0, 4, 0f);
        int lap = GradientMath.colorAt(0xFF0000, 0x0000FF, 0, 4, 1f);
        expect(origin == lap, "full cycle returns");
        expect(origin == 0xFF0000, "start colour");
        // An exact 1 wraps to 0, same as the source client, so the last glyph
        // at shift 0 shows the start colour. Two thirds of the way stays put.
        int end = GradientMath.colorAt(0xFF0000, 0x0000FF, 3, 4, 0f);
        expect(end == 0xFF0000, "exact lap wraps to the start");
        int near = GradientMath.colorAt(0xFF0000, 0x0000FF, 2, 4, 0f);
        expect(near == 0x5500AA, "two thirds " + Integer.toHexString(near));
    }

    private static void anchorPinsAndKeepsDistance() {
        int[] body = {10, -1, 30};
        int[] extra = {0, 2, 4};
        expect(LayoutBudget.average(body, 7) == 20, "average of known rows");
        expect(LayoutBudget.total(extra, body, 20) == 66, "unknown uses average");
        expect(LayoutBudget.provisional(body), "still guessed");
        expect(LayoutBudget.anchor(true, 10, 100, 180, 50) == 130, "pinned follows the bottom");
        expect(LayoutBudget.anchor(false, 10, 100, 180, 50) == 90, "scrolled keeps the gap");
        expect(LayoutBudget.anchor(false, 0, 100, 180, 50) == 80, "top keeps its distance from the bottom");
        expect(LayoutBudget.anchor(false, 0, 10, 20, 50) == 0, "short history");
    }

    private static void expect(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
