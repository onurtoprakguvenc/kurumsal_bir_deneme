package org.yazi.desktop;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UiSettingsTest {

    private static UiSettings over(Map<String, String> map) {
        return new UiSettings(new UiSettings.Store() {
            @Override
            public String get(String key, String fallback) {
                return map.getOrDefault(key, fallback);
            }

            @Override
            public void put(String key, String value) {
                map.put(key, value);
            }
        });
    }

    @Test
    void defaults() {
        UiSettings s = UiSettings.inMemory();
        assertEquals(UiSettings.Theme.LIGHT, s.theme());
        assertEquals(UiSettings.EditorFont.SERIF, s.editorFont());
        assertEquals(UiSettings.DEFAULT_FONT, s.fontSize());
        assertTrue(s.sidePanelVisible());
    }

    @Test
    void valuesRoundTripThroughTheStore() {
        Map<String, String> map = new HashMap<>();
        UiSettings s = over(map);
        s.theme(UiSettings.Theme.DARK);
        s.editorFont(UiSettings.EditorFont.MONO);
        s.fontSize(20);
        s.sidePanelVisible(false);

        UiSettings again = over(map);   // "next session"
        assertEquals(UiSettings.Theme.DARK, again.theme());
        assertEquals(UiSettings.EditorFont.MONO, again.editorFont());
        assertEquals(20, again.fontSize());
        assertFalse(again.sidePanelVisible());
    }

    @Test
    void corruptedValuesFallBackInsteadOfBreakingStartUp() {
        UiSettings s = over(new HashMap<>(Map.of("theme", "PURPLE", "editorFont", "", "fontSize", "huge",
                "sidePanel", "maybe")));
        assertEquals(UiSettings.Theme.LIGHT, s.theme());
        assertEquals(UiSettings.EditorFont.SERIF, s.editorFont());
        assertEquals(UiSettings.DEFAULT_FONT, s.fontSize());
        assertTrue(s.sidePanelVisible());
    }

    @Test
    void fontSizeIsClamped() {
        UiSettings s = UiSettings.inMemory();
        s.fontSize(4);
        assertEquals(UiSettings.MIN_FONT, s.fontSize());
        s.fontSize(400);
        assertEquals(UiSettings.MAX_FONT, s.fontSize());
        assertEquals(UiSettings.DEFAULT_FONT, UiSettings.clampFont(Double.NaN));
        UiSettings stored = over(new HashMap<>(Map.of("fontSize", "99")));
        assertEquals(UiSettings.MAX_FONT, stored.fontSize());
    }

    @Test
    void themeHelpers() {
        assertEquals("theme-dark", UiSettings.Theme.DARK.styleClass());
        assertEquals(UiSettings.Theme.LIGHT, UiSettings.Theme.DARK.toggled());
        assertEquals(UiSettings.Theme.DARK, UiSettings.Theme.LIGHT.toggled());
    }

    @Test
    void fontFamiliesQuoteNamesButNotTheGenericFamily() {
        assertEquals("\"Georgia\", \"Cambria\", serif", UiSettings.EditorFont.SERIF.cssFamily());
        assertTrue(UiSettings.EditorFont.MONO.cssFamily().endsWith(", monospace"));
        assertTrue(UiSettings.EditorFont.SANS.cssFamily().endsWith(", sans-serif"));
    }
}
