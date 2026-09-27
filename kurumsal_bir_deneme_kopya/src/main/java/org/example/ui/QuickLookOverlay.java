package org.example.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.Objects;

/**
 * Quick Look style preview: a {@link PreviewPaneView} card centred over the whole window on a translucent scrim
 * (macOS Quick Look / PowerToys Peek).
 *
 * <p>The overlay is only in the scene graph while it is showing — {@link #hide()} removes it from its host, so a
 * closed preview costs no CSS or layout pass. The card is sized to 75% of the window in both directions. Hiding is
 * instant (no exit animation), so the caller can release the previewed document in the same pulse.</p>
 *
 * <p>The overlay never touches the preview service: clicking the scrim only <em>requests</em> a dismissal through
 * {@link #setOnDismissRequest}; the chassis then clears the card, calls {@code PreviewService#dismiss()} and hides
 * the overlay.</p>
 */
public final class QuickLookOverlay extends StackPane {

    private static final double CARD_RATIO = 0.75;
    private static final Duration ENTER = Duration.millis(120);

    private final StackPane host;
    private final PreviewPaneView card;
    private final Region scrim = new Region();
    private Runnable onDismissRequest = () -> { };
    private ParallelTransition entering;
    private Node returnFocus;

    public QuickLookOverlay(StackPane host, PreviewPaneView card) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.card = Objects.requireNonNull(card, "card must not be null");
        getStyleClass().add("quick-look");
        setAlignment(Pos.CENTER);

        scrim.getStyleClass().add("quick-look-scrim");
        scrim.setOnMouseClicked(e -> onDismissRequest.run());

        card.getStyleClass().add("quick-look-card");
        card.maxWidthProperty().bind(widthProperty().multiply(CARD_RATIO));
        card.maxHeightProperty().bind(heightProperty().multiply(CARD_RATIO));
        card.prefWidthProperty().bind(card.maxWidthProperty());
        card.prefHeightProperty().bind(card.maxHeightProperty());
        // Clicks inside the card must not fall through to the scrim.
        card.setOnMouseClicked(e -> e.consume());

        getChildren().addAll(scrim, card);
    }

    /** Invoked when the user clicks outside the card. */
    public void setOnDismissRequest(Runnable action) {
        this.onDismissRequest = Objects.requireNonNull(action);
    }

    public boolean isShowing() {
        return host.getChildren().contains(this);
    }

    /** Mounts the overlay above everything in the host (idempotent) and moves keyboard focus into the card. */
    public void show() {
        if (isShowing()) {
            card.focusBody();
            return;
        }
        Scene scene = host.getScene();
        returnFocus = scene == null ? null : scene.getFocusOwner();
        host.getChildren().add(this);

        FadeTransition fade = new FadeTransition(ENTER, this);
        fade.setFromValue(0);
        fade.setToValue(1);
        ScaleTransition scale = new ScaleTransition(ENTER, card);
        scale.setFromX(0.97);
        scale.setFromY(0.97);
        scale.setToX(1);
        scale.setToY(1);
        entering = new ParallelTransition(fade, scale);
        entering.play();
        card.focusBody();
    }

    /** Unmounts the overlay at once and hands focus back to whatever held it before {@link #show()}. */
    public void hide() {
        if (entering != null) {
            entering.stop();
            entering = null;
        }
        setOpacity(1);
        card.setScaleX(1);
        card.setScaleY(1);
        if (!isShowing()) {
            return;
        }
        host.getChildren().remove(this);
        Node previous = returnFocus;
        returnFocus = null;
        if (previous != null && previous.getScene() != null && !previous.isDisabled()) {
            previous.requestFocus();
        }
    }
}
