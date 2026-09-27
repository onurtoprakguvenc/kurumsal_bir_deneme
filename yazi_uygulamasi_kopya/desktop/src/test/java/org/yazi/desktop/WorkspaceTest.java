package org.yazi.desktop;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Dialog;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yazi.gateway.GatewayException;
import org.yazi.gateway.Usage;
import org.yazi.model.Tier;
import org.yazi.prose.ModelCatalog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The whole window as a workspace: theme, layout, zoom, usage, status indicator, keyboard navigation, menus. */
class WorkspaceTest {

    private Stage stage;
    private MainWindow window;
    private Scene scene;
    private UiSettings settings;

    @BeforeAll
    static void toolkit() {
        Fx.start();
    }

    @BeforeEach
    void setUp() {
        settings = UiSettings.inMemory();
        build(settings, Optional.empty());
    }

    private void build(UiSettings s, Optional<UsageMeter.Rates> rates) {
        Fx.run(() -> {
            stage = new Stage();
            window = new MainWindow(stage, new KeyedGateway(null), ModelCatalog.defaults(), s, rates);
            scene = window.buildScene();
            stage.setScene(scene);
            scene.getRoot().applyCss();
        });
    }

    private void press(KeyCode code, boolean shift, boolean control) {
        Node target = scene.getFocusOwner() != null ? scene.getFocusOwner() : scene.getRoot();
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, false));
    }

    private Node focusOwner() {
        return scene.getFocusOwner();
    }

    // --- menus -----------------------------------------------------------------------------------

    @Test
    void editMenuHasFindAndFriends() {
        Fx.run(() -> {
            MenuBar bar = (MenuBar) ((javafx.scene.layout.BorderPane) scene.getRoot()).getTop();
            Menu edit = bar.getMenus().get(1);
            assertEquals("Edit", edit.getText());
            List<String> items = edit.getItems().stream().map(MenuItem::getText).filter(t -> t != null).toList();
            assertEquals(List.of("Undo", "Redo", "Cut", "Copy", "Paste", "Select All", "Find…", "Find Next",
                    "Find Previous"), items);
            assertEquals(Keys.FIND, window.menuItem("Find…").getAccelerator());

            window.editor().load("bir iki bir");
            window.menuItem("Find…").fire();
            assertTrue(window.find().isOpen());
        });
    }

    @Test
    void noTwoMenuItemsShareAnAccelerator() {
        Fx.run(() -> {
            MenuBar bar = (MenuBar) ((javafx.scene.layout.BorderPane) scene.getRoot()).getTop();
            List<KeyCombination> accelerators = new ArrayList<>();
            for (Menu m : bar.getMenus()) {
                collect(m, accelerators);
            }
            Set<KeyCombination> unique = new HashSet<>(accelerators);
            assertEquals(accelerators.size(), unique.size(), accelerators.toString());
            assertTrue(Keys.ALL.containsAll(unique), "every accelerator comes from Keys");
        });
    }

    private static void collect(Menu menu, List<KeyCombination> out) {
        for (MenuItem item : menu.getItems()) {
            if (item.getAccelerator() != null) {
                out.add(item.getAccelerator());
            }
            if (item instanceof Menu sub) {
                collect(sub, out);
            }
        }
    }

    @Test
    void editMenuUndoActsOnTheEditor() {
        Fx.run(() -> {
            window.editor().load("start");
            window.editor().area().appendText(" typed");
            window.focusEditor();
            window.menuItem("Undo").fire();
            assertEquals("start", window.editor().area().getText());
            window.menuItem("Redo").fire();
            assertEquals("start typed", window.editor().area().getText());
        });
    }

    @Test
    void shortcutSheetListsEveryEntry() {
        Fx.run(() -> {
            Dialog<Void> sheet = ShortcutSheet.build(null, UiSettings.Theme.DARK);
            assertEquals(Keys.SHEET.size(), ShortcutSheet.rows(sheet));
            assertTrue(sheet.getDialogPane().getStyleClass().contains("theme-dark"));
        });
    }

    @Test
    void apiKeyDialogNeedsAKey() {
        Fx.run(() -> {
            Dialog<String> d = window.keyDialog();
            assertTrue(UiComponentsTest.run(d).isDisabled());
            ((PasswordField) d.getDialogPane().lookup("#api-key")).setText("AIza-test");
            UiComponentsTest.press(d.getDialogPane(), KeyCode.ENTER, false, true);
            assertEquals("AIza-test", d.getResult());
        });
    }

    // --- theme -----------------------------------------------------------------------------------

    @Test
    void darkThemeToggleRestylesTheWindowAndIsRemembered() {
        Fx.run(() -> {
            assertTrue(scene.getRoot().getStyleClass().contains("theme-light"));
            CheckMenuItem dark = (CheckMenuItem) window.menuItem("Dark theme");
            assertEquals(Keys.TOGGLE_THEME, dark.getAccelerator());
            dark.setSelected(true);
            dark.fire();
            assertTrue(scene.getRoot().getStyleClass().contains("theme-dark"));
            assertFalse(scene.getRoot().getStyleClass().contains("theme-light"));
            assertEquals(UiSettings.Theme.DARK, settings.theme());
        });
        build(settings, Optional.empty());   // next session, same settings
        Fx.run(() -> {
            assertTrue(scene.getRoot().getStyleClass().contains("theme-dark"));
            assertTrue(((CheckMenuItem) window.menuItem("Dark theme")).isSelected());
            window.toggleTheme();
            assertTrue(scene.getRoot().getStyleClass().contains("theme-light"));
        });
    }

    @Test
    void dialogsFollowTheCurrentTheme() {
        Fx.run(() -> {
            window.applyTheme(UiSettings.Theme.DARK);
            assertTrue(window.keyDialog().getDialogPane().getStyleClass().contains("theme-dark"));
        });
    }

    // --- side panel, zoom, font ------------------------------------------------------------------

    @Test
    void sidePanelTogglesAndIsRemembered() {
        Fx.run(() -> {
            assertTrue(window.isSidePanelVisible());
            CheckMenuItem item = (CheckMenuItem) window.menuItem("Side panel");
            assertEquals(Keys.TOGGLE_SIDE_PANEL, item.getAccelerator());
            item.setSelected(false);   // what the accelerator or a click does before firing
            item.fire();
            assertFalse(window.isSidePanelVisible());
            assertFalse(settings.sidePanelVisible());
            item.setSelected(true);
            item.fire();
            assertTrue(window.isSidePanelVisible());
        });
    }

    @Test
    void jumpingToTheAnswerReopensAHiddenSidePanel() {
        Fx.run(() -> {
            window.setSidePanelVisible(false);
            window.focusEditor();
            press(KeyCode.DIGIT3, false, true);   // Ctrl+3
            assertTrue(window.isSidePanelVisible());
            assertSame(window.side().answerArea(), focusOwner());
        });
    }

    @Test
    void toolOutputReopensTheSidePanel() {
        Fx.run(() -> {
            window.setSidePanelVisible(false);
            window.showOutput("Video prompt", "POSITIVE");
            assertTrue(window.isSidePanelVisible());
            assertTrue(window.side().isMonospace());
        });
    }

    @Test
    void zoomChangesTheEditorFontWithinLimits() {
        Fx.run(() -> {
            window.menuItem("Zoom In").fire();
            assertEquals(18, window.fontSize());
            assertTrue(window.editor().area().getStyle().contains("-fx-font-size: 18.0px"));
            assertEquals("Text size 18 px", window.statusText());
            for (int i = 0; i < 20; i++) {
                window.zoom(1);
            }
            assertEquals(UiSettings.MAX_FONT, window.fontSize());
            window.menuItem("Actual Size").fire();
            assertEquals(UiSettings.DEFAULT_FONT, window.fontSize());
            window.menuItem("Zoom Out").fire();
            assertEquals(14, settings.fontSize(), "remembered");
        });
    }

    @Test
    void editorFontCanBeSwitched() {
        Fx.run(() -> {
            RadioMenuItem mono = (RadioMenuItem) window.menuItem("Mono");
            mono.fire();
            assertEquals(UiSettings.EditorFont.MONO, settings.editorFont());
            assertTrue(window.editor().area().getStyle().contains("Cascadia Mono"));
        });
    }

    // --- status bar ------------------------------------------------------------------------------

    @Test
    void usageChipCountsProviderTokensAndResets() {
        Fx.run(() -> {
            assertEquals("No AI calls yet", window.usageText());
            window.cost(new Usage(3_000, 400, 0, 0, 3_400), 8_000);
            window.cost(new Usage(1_000, 100, 0, 0, 1_100), 2_000);
            assertEquals("4.5k tokens · 2 calls", window.usageText());
            assertTrue(window.usageDetails().contains("Last call"), window.usageDetails());
            window.menuItem("Reset usage counter").fire();
            assertEquals("No AI calls yet", window.usageText());
        });
    }

    @Test
    void usageChipShowsACostOnlyWithConfiguredRates() {
        build(UiSettings.inMemory(), Optional.of(new UsageMeter.Rates(1.0, 4.0)));
        Fx.run(() -> {
            window.cost(new Usage(500_000, 250_000, 0, 0, 750_000), 0);
            assertEquals("750k tokens · 1 call · ≈ $1.50", window.usageText());
        });
    }

    @Test
    void statusDotFollowsTheAssistant() {
        Fx.run(() -> {
            assertTrue(window.statusDotClasses().contains("idle"), "no key: idle");
            window.activity(OperationController.Activity.WORKING);
            assertTrue(window.statusDotClasses().contains("working"));
            window.activity(OperationController.Activity.REVIEW);
            assertTrue(window.statusDotClasses().contains("review"));
            assertFalse(window.statusDotClasses().contains("working"));
            window.activity(OperationController.Activity.ERROR);
            assertTrue(window.statusDotClasses().contains("error"));
            window.activity(OperationController.Activity.IDLE);
            assertEquals(1, window.statusDotClasses().stream()
                    .filter(c -> List.of("idle", "ready", "working", "review", "error").contains(c)).count());
        });
    }

    @Test
    void formatLabelShowsEncodingAndLineEndings() {
        Fx.run(() -> assertEquals("UTF-8 · LF", window.formatText()));
    }

    // --- keyboard navigation ---------------------------------------------------------------------

    @Test
    void f6CyclesThroughTheRegionsAndSkipsClosedOnes() {
        Fx.run(() -> {
            window.focusEditor();
            assertSame(window.editor().area(), focusOwner());
            press(KeyCode.F6, false, false);
            assertSame(window.commandField(), focusOwner(), "find bar closed: skipped");
            press(KeyCode.F6, false, false);
            assertSame(window.side().answerArea(), focusOwner());
            press(KeyCode.F6, false, false);
            assertSame(window.side().notesArea(), focusOwner());
            press(KeyCode.F6, false, false);
            assertSame(window.editor().area(), focusOwner(), "wraps");
            press(KeyCode.F6, true, false);
            assertSame(window.side().notesArea(), focusOwner(), "Shift+F6 goes back");
        });
    }

    @Test
    void f6IncludesAnOpenFindBar() {
        Fx.run(() -> {
            window.focusEditor();
            window.find().open();
            window.focusEditor();
            press(KeyCode.F6, false, false);
            assertSame(window.find().queryField(), focusOwner());
        });
    }

    @Test
    void ctrlDigitsJumpStraightToARegion() {
        Fx.run(() -> {
            window.focusEditor();
            press(KeyCode.DIGIT2, false, true);
            assertSame(window.commandField(), focusOwner());
            press(KeyCode.DIGIT4, false, true);
            assertSame(window.side().notesArea(), focusOwner());
            press(KeyCode.DIGIT1, false, true);
            assertSame(window.editor().area(), focusOwner());
        });
    }

    @Test
    void escReturnsToTheTextFromAnyRegion() {
        Fx.run(() -> {
            window.side().notesArea().requestFocus();
            press(KeyCode.ESCAPE, false, false);
            assertSame(window.editor().area(), focusOwner());

            window.find().open();
            press(KeyCode.ESCAPE, false, false);
            assertFalse(window.find().isOpen(), "Esc in the find field closes it");
            assertSame(window.editor().area(), focusOwner());
        });
    }

    @Test
    void tierFollowsTheSegmentedControlAndTheMenu() {
        Fx.run(() -> {
            assertEquals(Tier.BALANCED, window.operations().tierProperty().get());
            window.tierControl().button(Tier.DEEP).fire();
            assertEquals(Tier.DEEP, window.operations().tierProperty().get());
            assertTrue(((RadioMenuItem) window.menuItem("Deep")).isSelected());

            RadioMenuItem fast = (RadioMenuItem) window.menuItem("Fast");
            assertEquals(Keys.TIER_FAST, fast.getAccelerator());
            fast.fire();
            assertEquals(Tier.FAST, window.operations().tierProperty().get());
            assertEquals(Tier.FAST, window.tierControl().getValue());
        });
    }

    // --- recovery messages -----------------------------------------------------------------------

    @Test
    void retryAndFallbackMessagesAreReadable() {
        GatewayException rate = new GatewayException(GatewayException.Kind.RATE_LIMITED, "x", 429, null, null);
        assertEquals("Rate limit reached; retrying in 7 s (attempt 2 of 3)…  Esc to cancel",
                MainWindow.retryMessage(2, 3, Duration.ofSeconds(7), rate));
        GatewayException net = new GatewayException(GatewayException.Kind.NETWORK, "x");
        assertTrue(MainWindow.retryMessage(3, 3, Duration.ofMillis(200), net)
                .startsWith("Network problem; retrying in 1 s"), "sub-second waits read as 1 s");
        GatewayException server = new GatewayException(GatewayException.Kind.HTTP, "x", 503, null, null);
        assertTrue(MainWindow.retryMessage(2, 3, Duration.ofSeconds(2), server).contains("(HTTP 503)"));
        assertEquals("Model “gemini-9” is not available; using “gemini-3.5-flash” instead.",
                MainWindow.fallbackMessage("gemini-9", "gemini-3.5-flash"));
    }

    @Test
    void keyedGatewayFallsBackToTheDefaultFlashModel() {
        assertEquals(Optional.of(ModelCatalog.DEFAULT_FLASH), KeyedGateway.fallbackModel("gemini-9-ultra"));
        assertEquals(Optional.empty(), KeyedGateway.fallbackModel(ModelCatalog.DEFAULT_FLASH));
        KeyedGateway none = new KeyedGateway(" ");
        assertFalse(none.hasKey());
        GatewayException e = assertThrows(GatewayException.class, () -> none.stream(
                org.yazi.gateway.ModelCall.of("m", "s", "u"), null, null));
        assertEquals(GatewayException.Kind.AUTH, e.kind());
        KeyedGateway keyed = new KeyedGateway("test-key");
        assertTrue(keyed.hasKey());
    }
}
