package com.niuqu.chatbubble.render;

/**
 * Scroll maths for a chat history that is only partly measured.
 * Unknown row heights stay at -1 and use the average of the rows already measured.
 */
public final class LayoutBudget {
    private LayoutBudget() {
    }

    public static int average(int[] body, int fallback) {
        long sum = 0;
        int counted = 0;
        for (int height : body) {
            if (height >= 0) {
                sum += height;
                counted++;
            }
        }
        if (counted == 0) {
            return Math.max(1, fallback);
        }
        return (int) Math.max(1, sum / counted);
    }

    public static int total(int[] extra, int[] body, int average) {
        int sum = 0;
        for (int i = 0; i < body.length; i++) {
            sum += extra[i];
            sum += body[i] >= 0 ? body[i] : average;
        }
        return sum;
    }

    public static boolean provisional(int[] body) {
        for (int height : body) {
            if (height < 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keep the viewport stable when a guessed height is replaced.
     * Pinned views stay on the newest row. Scrolled views keep their distance from the bottom.
     */
    public static int anchor(boolean pinned, int offset, int oldTotal, int newTotal, int viewport) {
        int oldMax = Math.max(0, oldTotal - Math.max(0, viewport));
        int newMax = Math.max(0, newTotal - Math.max(0, viewport));
        if (pinned) {
            return newMax;
        }
        int clamped = Math.max(0, Math.min(offset, oldMax));
        int fromBottom = oldMax - clamped;
        int next = newMax - fromBottom;
        if (next < 0) {
            return 0;
        }
        if (next > newMax) {
            return newMax;
        }
        return next;
    }
}
