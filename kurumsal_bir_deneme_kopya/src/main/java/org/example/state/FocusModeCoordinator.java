package org.example.state;

import org.example.state.PanelStateCoordinator.Panel;
import org.example.state.PanelStateCoordinator.PanelState;
import org.example.state.ViewModeCoordinator.ViewMode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Distraction-free ("zen") presentation state and quiet mode.
 *
 * <p><b>Focus mode</b> reduces the window to the search bar and the evidence stream: every other
 * {@link Element} is reported as hidden, the dockable panels are hidden through the {@link PanelStateCoordinator}
 * (so they leave the scene graph instead of being merely invisible) and results fall back to the simple view so no
 * telemetry is rendered. The exact prior state — each panel's visibility and the view mode — is captured on entry
 * and restored on exit, so leaving focus mode never loses a layout the user arranged.</p>
 *
 * <p><b>Quiet mode</b> is independent (and implied by focus mode unless disabled): the notification gate passes
 * {@link Severity#CRITICAL} events plus whatever the {@link NotificationPolicy} allows, the log gate
 * {@link Severity#WARNING} and above, and both count what they swallowed, so the user can be told "7 notifications
 * suppressed" afterwards. Under the default policy the results of the user's own actions — an import they started —
 * still reach them, while the folder watcher and telemetry stay silent; a caller that does not state an
 * {@link Origin} is treated as background. The audit trail is deliberately not routed through this gate —
 * compliance records are never muted by a presentation preference.</p>
 *
 * <p>All of this governs only what this application's own window shows. Notifications belonging to other
 * applications are never touched.</p>
 *
 * <p>Pure state with listener hooks; a JavaFX view binds each chrome node's {@code managed/visible} to
 * {@link #isVisible(Element)} on change events.</p>
 */
public final class FocusModeCoordinator {

    /** UI regions the focus state decides about. */
    public enum Element {
        SEARCH_BAR(true), EVIDENCE_STREAM(true),
        TOOLBAR(false), STATUS_BAR(false), TELEMETRY(false), BADGES(false), FILE_TREE(false), TERMINAL(false),
        PREVIEW_PANE(false), NOTIFICATIONS(false);

        private final boolean essential;

        Element(boolean essential) {
            this.essential = essential;
        }

        /** Essential elements stay visible in focus mode. */
        public boolean essential() {
            return essential;
        }
    }

    public enum Severity {
        DEBUG, INFO, WARNING, CRITICAL
    }

    /**
     * Who caused a notification. {@link #USER_ACTION} is reserved for the visible result of something the user just
     * did in this window — an import they started by dropping files, pressing the import shortcut or typing the
     * command. Anything a background thread decided on its own (the folder watcher, telemetry, health reports) is
     * {@link #BACKGROUND}, which is what the default policy suppresses in quiet mode.
     */
    public enum Origin {
        USER_ACTION, BACKGROUND
    }

    /**
     * What quiet mode lets through besides {@link Severity#CRITICAL}.
     *
     * <ul>
     *   <li>{@link #USER_ACTIONS_ONLY} (default) — results of the user's own actions still appear, so an import can
     *       report what it added and why anything was refused, while the watcher and telemetry stay silent;</li>
     *   <li>{@link #ALL} — quiet mode stops gating notifications entirely, for users who would rather see
     *       everything.</li>
     * </ul>
     *
     * <p>This decides only what this window shows. Notifications from other applications are none of its
     * business.</p>
     */
    public enum NotificationPolicy {
        USER_ACTIONS_ONLY, ALL;

        /** Lower-case id used in the project settings file and the {@code focus} command. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<NotificationPolicy> parse(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String v = raw.strip().toLowerCase(Locale.ROOT).replace('-', '_');
            for (NotificationPolicy p : values()) {
                if (p.id().equals(v)) {
                    return Optional.of(p);
                }
            }
            return Optional.empty();
        }
    }

    /** Notified when {@link #setNotificationPolicy} actually changes the policy, so a view can persist it. */
    @FunctionalInterface
    public interface PolicyListener {
        void policyChanged(NotificationPolicy policy);
    }

    /**
     * Immutable view of the presentation state.
     *
     * @param focus  focus (zen) mode active
     * @param quiet  quiet mode active (notifications/logging gated to critical)
     * @param hidden elements currently suppressed
     */
    public record FocusState(boolean focus, boolean quiet, Set<Element> hidden) {
        public FocusState {
            hidden = Collections.unmodifiableSet(hidden.isEmpty() ? EnumSet.noneOf(Element.class)
                    : EnumSet.copyOf(hidden));
        }

        public boolean visible(Element e) {
            return !hidden.contains(e);
        }
    }

    @FunctionalInterface
    public interface Listener {
        void focusChanged(FocusState previous, FocusState current);
    }

    private static final Map<Panel, Element> PANEL_ELEMENTS = Map.of(
            Panel.FILE_TREE, Element.FILE_TREE, Panel.TERMINAL, Element.TERMINAL);

    private final PanelStateCoordinator panels;
    private final ViewModeCoordinator view;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PolicyListener> policyListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong suppressedNotifications = new AtomicLong();
    private final AtomicLong suppressedLogs = new AtomicLong();
    private volatile NotificationPolicy notificationPolicy = NotificationPolicy.USER_ACTIONS_ONLY;

    private FocusState state = new FocusState(false, false, EnumSet.noneOf(Element.class));
    private boolean quietBeforeFocus;
    private boolean quietWithFocus = true;
    private Map<Panel, PanelStateCoordinator.Visibility> savedPanels = Map.of();
    private ViewMode savedMode;

    public FocusModeCoordinator(PanelStateCoordinator panels, ViewModeCoordinator view) {
        this.panels = Objects.requireNonNull(panels, "panels must not be null");
        this.view = Objects.requireNonNull(view, "view must not be null");
    }

    public synchronized FocusState state() {
        return state;
    }

    public boolean focusActive() {
        return state().focus();
    }

    public boolean quiet() {
        return state().quiet();
    }

    public boolean isVisible(Element element) {
        return state().visible(element);
    }

    /** Whether entering focus mode also turns quiet mode on (default true). */
    public synchronized void setQuietWithFocus(boolean enabled) {
        this.quietWithFocus = enabled;
    }

    /** What quiet mode lets through besides {@link Severity#CRITICAL}; {@link NotificationPolicy#USER_ACTIONS_ONLY} by default. */
    public NotificationPolicy notificationPolicy() {
        return notificationPolicy;
    }

    /** Sets the policy and notifies {@link #onNotificationPolicyChanged} listeners when it actually changed. */
    public void setNotificationPolicy(NotificationPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        if (notificationPolicy == policy) {
            return;
        }
        notificationPolicy = policy;
        for (PolicyListener l : policyListeners) {
            l.policyChanged(policy);
        }
    }

    public Subscription onNotificationPolicyChanged(PolicyListener listener) {
        policyListeners.add(Objects.requireNonNull(listener));
        return () -> policyListeners.remove(listener);
    }

    // ================================================================== transitions

    /** Enters focus mode; returns false when already active. */
    public boolean enter() {
        FocusState previous;
        FocusState next;
        synchronized (this) {
            if (state.focus()) {
                return false;
            }
            previous = state;
            EnumMap<Panel, PanelStateCoordinator.Visibility> saved = new EnumMap<>(Panel.class);
            for (Map.Entry<Panel, PanelState> e : panels.snapshot().entrySet()) {
                saved.put(e.getKey(), e.getValue().visibility());
            }
            savedPanels = saved;
            savedMode = view.mode();
            quietBeforeFocus = state.quiet();
            EnumSet<Element> hidden = EnumSet.noneOf(Element.class);
            for (Element e : Element.values()) {
                if (!e.essential()) {
                    hidden.add(e);
                }
            }
            next = new FocusState(true, quietBeforeFocus || quietWithFocus, hidden);
            state = next;
        }
        // Side effects outside the lock: coordinators notify their own listeners. The evidence stream is docked
        // first so hiding the peripheral panels can never be refused as "last docked panel".
        panels.show(Panel.RESULTS);
        for (Panel p : PANEL_ELEMENTS.keySet()) {
            panels.hide(p);
        }
        view.setMode(ViewMode.SIMPLE);
        fire(previous, next);
        return true;
    }

    /** Leaves focus mode and restores the captured panels and view mode; returns false when not active. */
    public boolean exit() {
        FocusState previous;
        FocusState next;
        Map<Panel, PanelStateCoordinator.Visibility> restorePanels;
        ViewMode restoreMode;
        synchronized (this) {
            if (!state.focus()) {
                return false;
            }
            previous = state;
            restorePanels = savedPanels;
            restoreMode = savedMode;
            savedPanels = Map.of();
            savedMode = null;
            next = new FocusState(false, quietBeforeFocus, EnumSet.noneOf(Element.class));
            state = next;
        }
        // Peripheral panels first, results last: restoring a hidden results panel needs another docked panel.
        restorePanels.forEach((panel, visibility) -> {
            if (panel != Panel.RESULTS) {
                panels.set(panel, visibility);
            }
        });
        PanelStateCoordinator.Visibility results = restorePanels.get(Panel.RESULTS);
        if (results != null) {
            panels.set(Panel.RESULTS, results);
        }
        if (restoreMode != null) {
            view.setMode(restoreMode);
        }
        fire(previous, next);
        return true;
    }

    public boolean toggle() {
        return focusActive() ? exit() : enter();
    }

    /** Sets quiet mode independently of focus mode. */
    public void setQuiet(boolean quiet) {
        FocusState previous;
        FocusState next;
        synchronized (this) {
            if (state.quiet() == quiet) {
                return;
            }
            previous = state;
            next = new FocusState(state.focus(), quiet, state.hidden());
            state = next;
            if (state.focus()) {
                // An explicit choice while focused also decides what remains after leaving focus mode.
                quietBeforeFocus = quiet;
            }
        }
        fire(previous, next);
    }

    // ================================================================== gates

    /**
     * True when a notification of {@code severity} may be shown; suppressed ones are counted. Callers that cannot
     * say where the event came from are treated as {@link Origin#BACKGROUND}, the stricter of the two.
     */
    public boolean allowNotification(Severity severity) {
        return allowNotification(severity, Origin.BACKGROUND);
    }

    /**
     * True when a notification of {@code severity} from {@code origin} may be shown; suppressed ones are counted.
     * Quiet mode always passes {@link Severity#CRITICAL}; beyond that the {@link NotificationPolicy} decides, so the
     * result of an import the user just asked for is not swallowed along with the watcher's chatter.
     */
    public boolean allowNotification(Severity severity, Origin origin) {
        if (!quiet() || severity == Severity.CRITICAL) {
            return true;
        }
        boolean allowed = switch (notificationPolicy) {
            case ALL -> true;
            case USER_ACTIONS_ONLY -> origin == Origin.USER_ACTION;
        };
        if (allowed) {
            return true;
        }
        suppressedNotifications.incrementAndGet();
        return false;
    }

    /** True when a (non-audit) log line of {@code severity} should be emitted; suppressed ones are counted. */
    public boolean allowLog(Severity severity) {
        if (!quiet() || severity.compareTo(Severity.WARNING) >= 0) {
            return true;
        }
        suppressedLogs.incrementAndGet();
        return false;
    }

    /** Returns and resets the suppressed-notification counter (for a summary after leaving quiet mode). */
    public long drainSuppressedNotifications() {
        return suppressedNotifications.getAndSet(0);
    }

    public long suppressedLogs() {
        return suppressedLogs.get();
    }

    public Subscription onChange(Listener listener) {
        listeners.add(Objects.requireNonNull(listener));
        return () -> listeners.remove(listener);
    }

    private void fire(FocusState previous, FocusState next) {
        for (Listener l : listeners) {
            l.focusChanged(previous, next);
        }
    }
}
