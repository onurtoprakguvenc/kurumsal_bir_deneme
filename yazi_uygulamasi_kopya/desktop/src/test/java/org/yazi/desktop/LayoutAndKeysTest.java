package org.yazi.desktop;

import javafx.scene.input.KeyCombination;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The pure pieces of layout and keyboard handling: writing column, zoom, shortcut registry, focus cycle. */
class LayoutAndKeysTest {

    // --- writing column --------------------------------------------------------------------------

    @Test
    void wideWindowsCentreAComfortableMeasure() {
        double side = EditorLayout.sidePadding(1600, 16);
        double column = 1600 - 2 * side;
        assertEquals(EditorLayout.MEASURE_EM * 16, column, 1.0);
    }

    @Test
    void narrowWindowsKeepTheMinimumMargin() {
        assertEquals(EditorLayout.MIN_SIDE, EditorLayout.sidePadding(500, 16));
        assertEquals(EditorLayout.MIN_SIDE, EditorLayout.sidePadding(0, 16), "before the first layout pass");
        assertEquals(EditorLayout.MIN_SIDE, EditorLayout.sidePadding(Double.NaN, 16));
    }

    @Test
    void theMeasureScalesWithZoom() {
        assertTrue(EditorLayout.sidePadding(1600, 24) < EditorLayout.sidePadding(1600, 16));
    }

    @Test
    void zoomStepsAndClamps() {
        assertEquals(18, EditorLayout.zoom(16, 1));
        assertEquals(14, EditorLayout.zoom(16, -1));
        assertEquals(12, EditorLayout.zoom(11, 1), "at least 1 px per step");
        assertEquals(UiSettings.MIN_FONT, EditorLayout.zoom(UiSettings.MIN_FONT, -1));
        assertEquals(UiSettings.MAX_FONT, EditorLayout.zoom(UiSettings.MAX_FONT, 1));
    }

    // --- shortcut registry -----------------------------------------------------------------------

    @Test
    void everyShortcutIsUnique() {
        Set<KeyCombination> seen = new HashSet<>();
        for (KeyCombination k : Keys.ALL) {
            assertTrue(seen.add(k), "bound twice: " + k.getDisplayText());
        }
    }

    @Test
    void theCheatSheetListsEveryBoundCombination() {
        Set<KeyCombination> listed = new HashSet<>();
        for (Keys.Entry e : Keys.SHEET) {
            if (e.combination() != null) {
                listed.add(e.combination());
            }
        }
        List<String> missing = new ArrayList<>();
        for (KeyCombination k : Keys.ALL) {
            boolean clipboardBasics = k == Keys.UNDO || k == Keys.REDO || k == Keys.CUT || k == Keys.COPY
                    || k == Keys.PASTE || k == Keys.SELECT_ALL || k == Keys.ZOOM_IN || k == Keys.ZOOM_OUT;
            if (!listed.contains(k) && !clipboardBasics) {
                missing.add(k.getDisplayText());
            }
        }
        assertTrue(missing.isEmpty(), "missing from F1 sheet: " + missing);
    }

    @Test
    void enterIsSpelledOutOnTheSheet() {
        Keys.Entry write = Keys.SHEET.stream().filter(e -> e.combination() == Keys.WRITE).findFirst().orElseThrow();
        assertTrue(write.keyText().endsWith("Enter"), write.keyText());
        assertFalse(write.keyText().contains("↵"));
    }

    @Test
    void everySheetEntryHasKeysAndAnAction() {
        for (Keys.Entry e : Keys.SHEET) {
            assertFalse(e.action().isBlank());
            assertFalse(Objects.requireNonNull(e.keyText(), e.action()).isBlank(), e.action());
        }
    }

    // --- focus cycle -----------------------------------------------------------------------------

    @Test
    void cycleSkipsUnavailableRegionsAndWraps() {
        boolean[] all = {true, false, true, true, false};
        assertEquals(2, FocusCycle.next(0, all, false), "find bar closed: skipped");
        assertEquals(3, FocusCycle.next(2, all, false));
        assertEquals(0, FocusCycle.next(3, all, false), "notes hidden: wraps to editor");
        assertEquals(3, FocusCycle.next(0, all, true), "backwards from editor");
        assertEquals(0, FocusCycle.next(-1, all, false), "focus outside: starts at the first");
        assertEquals(3, FocusCycle.next(-1, all, true), "focus outside, backwards: the last available");
        assertEquals(-1, FocusCycle.next(0, new boolean[]{false, false}, false));
        assertEquals(-1, FocusCycle.next(-1, new boolean[0], false));
        assertEquals(0, FocusCycle.next(0, new boolean[]{true}, false), "a single region stays put");
    }

    @Test
    void moveFocusesTheTargetAndReportsItsName() {
        List<String> focused = new ArrayList<>();
        String[] current = {"b"};
        List<FocusCycle.Region> regions = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            regions.add(new FocusCycle.Region(name, () -> {
                focused.add(name);
                current[0] = name;
            }, () -> true, () -> name.equals(current[0])));
        }
        FocusCycle cycle = new FocusCycle(regions);
        assertEquals("c", cycle.move(false));
        assertEquals("a", cycle.move(false));
        assertEquals("c", cycle.move(true));
        assertEquals(List.of("c", "a", "c"), focused);
    }
}
