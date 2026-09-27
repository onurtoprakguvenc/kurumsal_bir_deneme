package org.yazi.desktop;

import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yazi.model.Span;
import org.yazi.prose.ModelCatalog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds the real window off-screen (never shown), checks that it assembles and styles without errors, and
 * writes screenshots to {@code desktop/build/screenshots/} for a visual check.
 */
class MainWindowSmokeTest {

    private static final String SAMPLE = """
            Quarterly update

            The migration finished two days early. Most of the time went into the export scripts, which now run \
            in under ten minutes instead of an hour. We still have to decide who owns the nightly report.

            Next week the focus moves to the billing module.""";

    @BeforeAll
    static void toolkit() {
        Fx.start();
    }

    @Test
    void windowBuildsAndRendersIdleAndPreviewStates() throws IOException {
        Path dir = Path.of("build", "screenshots");
        Files.createDirectories(dir);

        Fx.run(() -> {
            Stage stage = new Stage();
            MainWindow window = new MainWindow(stage, new KeyedGateway(null), ModelCatalog.defaults());
            Scene scene = window.buildScene();
            stage.setScene(scene);

            javafx.scene.control.MenuBar menus = (javafx.scene.control.MenuBar)
                    ((javafx.scene.layout.BorderPane) scene.getRoot()).getTop();
            assertEquals(List.of("File", "Edit", "View", "Assist", "Compile", "Help"),
                    menus.getMenus().stream().map(javafx.scene.control.Menu::getText).toList());
            javafx.scene.control.Menu compile = menus.getMenus().stream()
                    .filter(m -> m.getText().equals("Compile")).findFirst().orElseThrow();
            assertEquals(List.of("Video prompt from selection…", "Image prompt from selection…",
                            "Meta-prompt from selection…"),
                    compile.getItems().stream().map(javafx.scene.control.MenuItem::getText).toList());

            EditorPane editor = window.editor();
            editor.load(SAMPLE);
            assertTrue(stage.getTitle().contains("Untitled"), stage.getTitle());
            save(scene.snapshot(null), dir.resolve("idle.png"));

            int start = SAMPLE.indexOf("We still have");
            int end = SAMPLE.indexOf("report.") + "report.".length();
            editor.beginPreview(new Span(start, end));
            editor.appendGhost(" Ownership of the nightly report is still open.");
            save(scene.snapshot(null), dir.resolve("rewrite-preview.png"));

            editor.endPreview();
            assertEquals(SAMPLE, editor.area().getText());

            window.applyTheme(UiSettings.Theme.DARK);
            save(scene.snapshot(null), dir.resolve("idle-dark.png"));
        });
        assertTrue(Files.size(dir.resolve("idle.png")) > 0);
    }

    private static void save(WritableImage image, Path target) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        try {
            ImageIO.write(out, "png", target.toFile());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
