package org.example.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.example.state.FocusModeCoordinator;
import org.example.state.FocusModeCoordinator.Origin;
import org.example.state.FocusModeCoordinator.Severity;

import java.util.function.Consumer;

/**
 * Quiet-mode aware toast overlay, stacked in the bottom-right corner above the workspace.
 *
 * <p>Two entry points: {@link #post(Severity, String)} asks the active {@link FocusModeCoordinator} first (quiet mode
 * lets {@link Severity#CRITICAL} and, depending on the policy, the results of the user's own actions through, and
 * counts the rest), while {@link #accept(String)} shows unconditionally
 * — it is the sink handed to {@code ExtendedWorkbenchController#setNotifier}, which has already applied the same gate.
 * At most {@link #MAX_VISIBLE} toasts exist at once; older ones are dropped, never queued, so a burst of events cannot
 * pile up nodes.</p>
 */
public final class ToastNotifier extends VBox implements Consumer<String> {

    private static final int MAX_VISIBLE = 3;
    private static final int MAX_CHARS = 280;
    private static final Duration SHOW_FOR = Duration.seconds(3.5);
    private static final Duration FADE = Duration.millis(180);

    private volatile FocusModeCoordinator gate;

    public ToastNotifier() {
        getStyleClass().add("toast-stack");
        setSpacing(6);
        setPadding(new Insets(0, 16, 16, 0));
        setAlignment(Pos.BOTTOM_RIGHT);
        setPickOnBounds(false);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
    }

    /** The coordinator of the active project, or {@code null} while no project is bound. */
    public void setGate(FocusModeCoordinator gate) {
        this.gate = gate;
    }

    /**
     * Shows {@code message} unless quiet mode suppresses {@code severity}. Treated as
     * {@link FocusModeCoordinator.Origin#BACKGROUND}: use {@link #post(Severity, String, Origin)} for the result of
     * something the user just did.
     *
     * @return whether the toast was shown
     */
    public boolean post(Severity severity, String message) {
        return post(severity, message, Origin.BACKGROUND);
    }

    /**
     * Shows {@code message} unless quiet mode suppresses a notification of this {@code severity} from this
     * {@code origin}.
     *
     * @return whether the toast was shown
     */
    public boolean post(Severity severity, String message, Origin origin) {
        FocusModeCoordinator g = gate;
        if (g != null && !g.allowNotification(severity, origin)) {
            return false;
        }
        show(severity, message);
        return true;
    }

    /** Ungated sink for notifications another component already filtered. */
    @Override
    public void accept(String message) {
        show(Severity.INFO, message);
    }

    private void show(Severity severity, String message) {
        String text = message == null ? "" : message.length() > MAX_CHARS
                ? message.substring(0, MAX_CHARS - 1) + "…" : message;
        Fx.run(() -> add(severity, text));
    }

    private void add(Severity severity, String text) {
        while (getChildren().size() >= MAX_VISIBLE) {
            getChildren().removeFirst();
        }
        Label toast = new Label(text);
        toast.getStyleClass().addAll("toast", switch (severity) {
            case CRITICAL -> "toast-critical";
            case WARNING -> "toast-warning";
            default -> "toast-info";
        });
        toast.setWrapText(true);
        toast.setMaxWidth(360);
        toast.setOpacity(0);
        getChildren().add(toast);

        FadeTransition in = new FadeTransition(FADE, toast);
        in.setToValue(1);
        FadeTransition out = new FadeTransition(FADE, toast);
        out.setToValue(0);
        SequentialTransition life = new SequentialTransition(in, new PauseTransition(SHOW_FOR), out);
        life.setOnFinished(e -> getChildren().remove(toast));
        toast.setOnMouseClicked(e -> {
            life.stop();
            getChildren().remove(toast);
        });
        life.play();
    }

    /** Removes every toast immediately (project switch, shutdown). */
    public void clear() {
        Fx.run(() -> getChildren().clear());
    }
}
