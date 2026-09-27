package org.yazi.desktop;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Appearance preferences that outlive a session: theme, editor font and size, side panel. Nothing about documents
 * or model requests is stored here; the assistant stays stateless.
 *
 * <p>The desktop app uses {@link #preferences()} (the per-user Java preferences store). Tests and the default
 * window constructor use {@link #inMemory()} so they never touch the real store. Values read back are validated,
 * so a hand-edited or corrupted entry falls back to the default instead of breaking start-up.</p>
 */
final class UiSettings {

    enum Theme {
        LIGHT, DARK;

        String styleClass() {
            return "theme-" + name().toLowerCase(Locale.ROOT);
        }

        Theme toggled() {
            return this == LIGHT ? DARK : LIGHT;
        }
    }

    enum EditorFont {
        SERIF("Georgia", "Cambria", "serif"),
        SANS("Segoe UI", "Inter", "sans-serif"),
        MONO("Cascadia Mono", "Consolas", "monospace");

        private final String[] families;

        EditorFont(String... families) {
            this.families = families;
        }

        /** A CSS font-family list, e.g. {@code "Georgia", "Cambria", serif}; the last (generic) family is bare. */
        String cssFamily() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < families.length; i++) {
                boolean generic = i == families.length - 1;
                out.append(i == 0 ? "" : ", ").append(generic ? families[i] : '"' + families[i] + '"');
            }
            return out.toString();
        }
    }

    static final double MIN_FONT = 11;
    static final double MAX_FONT = 32;
    static final double DEFAULT_FONT = 16;

    /** Where values live. */
    interface Store {
        String get(String key, String fallback);

        void put(String key, String value);
    }

    private final Store store;

    UiSettings(Store store) {
        this.store = store;
    }

    static UiSettings inMemory() {
        Map<String, String> map = new HashMap<>();
        return new UiSettings(new Store() {
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

    static UiSettings preferences() {
        Preferences node = Preferences.userRoot().node("org/yazi/desktop");
        return new UiSettings(new Store() {
            @Override
            public String get(String key, String fallback) {
                try {
                    return node.get(key, fallback);
                } catch (RuntimeException e) {
                    return fallback;   // a broken preferences backend must not stop the editor
                }
            }

            @Override
            public void put(String key, String value) {
                try {
                    node.put(key, value);
                } catch (RuntimeException ignored) {
                    // appearance simply is not remembered
                }
            }
        });
    }

    Theme theme() {
        return parse(Theme.class, store.get("theme", Theme.LIGHT.name()), Theme.LIGHT);
    }

    void theme(Theme value) {
        store.put("theme", value.name());
    }

    EditorFont editorFont() {
        return parse(EditorFont.class, store.get("editorFont", EditorFont.SERIF.name()), EditorFont.SERIF);
    }

    void editorFont(EditorFont value) {
        store.put("editorFont", value.name());
    }

    double fontSize() {
        try {
            return clampFont(Double.parseDouble(store.get("fontSize", Double.toString(DEFAULT_FONT))));
        } catch (NumberFormatException e) {
            return DEFAULT_FONT;
        }
    }

    void fontSize(double value) {
        store.put("fontSize", Double.toString(clampFont(value)));
    }

    boolean sidePanelVisible() {
        return !"false".equals(store.get("sidePanel", "true"));
    }

    void sidePanelVisible(boolean value) {
        store.put("sidePanel", Boolean.toString(value));
    }

    static double clampFont(double size) {
        if (Double.isNaN(size)) {
            return DEFAULT_FONT;
        }
        return Math.max(MIN_FONT, Math.min(MAX_FONT, size));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
