package org.yazi.desktop;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * F6 / Shift+F6 region cycling: editor → find bar → instruction bar → answer → notes → editor. Regions that are
 * hidden or disabled at the moment are skipped. The index logic is FX-free so it can be tested on its own.
 */
final class FocusCycle {

    /** One stop in the cycle. */
    record Region(String name, Runnable focus, BooleanSupplier available, BooleanSupplier focused) {}

    private final List<Region> regions;

    FocusCycle(List<Region> regions) {
        this.regions = List.copyOf(regions);
    }

    /** Moves focus to the next (or previous) available region; returns its name, or null if none is available. */
    String move(boolean backward) {
        int current = -1;
        boolean[] available = new boolean[regions.size()];
        for (int i = 0; i < regions.size(); i++) {
            available[i] = regions.get(i).available().getAsBoolean();
            if (current < 0 && regions.get(i).focused().getAsBoolean()) {
                current = i;
            }
        }
        int target = next(current, available, backward);
        if (target < 0) {
            return null;
        }
        regions.get(target).focus().run();
        return regions.get(target).name();
    }

    /**
     * The index after (or before) {@code current} whose region is available, wrapping; -1 when none is.
     * {@code current} -1 (focus outside every region) starts from the first region, or the last going backward.
     */
    static int next(int current, boolean[] available, boolean backward) {
        int n = available.length;
        if (n == 0) {
            return -1;
        }
        int step = backward ? -1 : 1;
        int start = (current < 0) ? (backward ? 0 : n - 1) : current;
        for (int k = 1; k <= n; k++) {
            int i = Math.floorMod(start + k * step, n);
            if (available[i]) {
                return i;
            }
        }
        return -1;
    }
}
