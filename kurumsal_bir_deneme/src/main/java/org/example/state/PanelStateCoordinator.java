package org.example.state;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Visibility state of the workbench's dockable panels, independent of any toolkit.
 *
 * <p>The coordinator owns the <em>what</em> (docked, hidden or detached, plus the last divider position so a panel
 * comes back at the size the user left it); the view layer owns the <em>how</em>. The recommended JavaFX binding is
 * to remove a hidden panel's node from its {@code SplitPane} (instead of {@code setVisible(false)}), keeping the node
 * instance for re-insertion: a removed node costs no layout or CSS passes and no redraw, while re-adding it does not
 * rebuild its children.</p>
 *
 * <p>Change events are emitted only on an actual transition, so repeated "hide" commands or key repeats never cause
 * redundant relayouts. Listener handles are {@link Subscription}s; a detached window closes its subscription when
 * disposed, so the long-lived coordinator never pins a dead window.</p>
 */
public final class PanelStateCoordinator {

    public enum Panel {
        FILE_TREE("tree"), RESULTS("results"), TERMINAL("terminal");

        private final String alias;

        Panel(String alias) {
            this.alias = alias;
        }

        public String alias() {
            return alias;
        }

        /** Accepts the enum name or its short alias, case-insensitively ({@code tree}, {@code FILE_TREE}). */
        public static Optional<Panel> parse(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String key = raw.strip().toLowerCase(Locale.ROOT).replace('-', '_');
            for (Panel p : values()) {
                if (p.alias.equals(key) || p.name().toLowerCase(Locale.ROOT).equals(key)) {
                    return Optional.of(p);
                }
            }
            return Optional.empty();
        }
    }

    public enum Visibility {
        /** Laid out inside the main window. */
        DOCKED,
        /** Not part of the scene graph at all. */
        HIDDEN,
        /** Shown in its own lightweight window. */
        DETACHED
    }

    /**
     * @param divider last divider position (0..1) the panel had while docked; restored on re-dock
     */
    public record PanelState(Panel panel, Visibility visibility, double divider) {
        public PanelState {
            Objects.requireNonNull(panel);
            Objects.requireNonNull(visibility);
            divider = Double.isFinite(divider) ? Math.max(0.05, Math.min(0.95, divider)) : 0.5;
        }

        public boolean shown() {
            return visibility != Visibility.HIDDEN;
        }

        PanelState with(Visibility v) {
            return new PanelState(panel, v, divider);
        }
    }

    @FunctionalInterface
    public interface PanelListener {
        void panelChanged(PanelState previous, PanelState current);
    }

    private final EnumMap<Panel, PanelState> states = new EnumMap<>(Panel.class);
    private final CopyOnWriteArrayList<PanelListener> listeners = new CopyOnWriteArrayList<>();

    public PanelStateCoordinator() {
        states.put(Panel.FILE_TREE, new PanelState(Panel.FILE_TREE, Visibility.DOCKED, 0.22));
        states.put(Panel.RESULTS, new PanelState(Panel.RESULTS, Visibility.DOCKED, 0.5));
        states.put(Panel.TERMINAL, new PanelState(Panel.TERMINAL, Visibility.HIDDEN, 0.72));
    }

    public synchronized PanelState state(Panel panel) {
        return states.get(panel);
    }

    public synchronized Map<Panel, PanelState> snapshot() {
        return Map.copyOf(states);
    }

    public boolean isShown(Panel panel) {
        return state(panel).shown();
    }

    /**
     * Requests a visibility change. Hiding or detaching the last docked panel is refused, so the main window can
     * never end up empty.
     *
     * @return {@code true} when the state changed
     */
    public boolean set(Panel panel, Visibility visibility) {
        Objects.requireNonNull(panel);
        Objects.requireNonNull(visibility);
        PanelState previous;
        PanelState next;
        synchronized (this) {
            previous = states.get(panel);
            if (previous.visibility() == visibility) {
                return false;
            }
            if (visibility != Visibility.DOCKED && dockedCountExcluding(panel) == 0) {
                return false;
            }
            next = previous.with(visibility);
            states.put(panel, next);
        }
        fire(previous, next);
        return true;
    }

    /** Shown (docked or detached) → hidden; hidden → docked. */
    public boolean toggle(Panel panel) {
        return set(panel, isShown(panel) ? Visibility.HIDDEN : Visibility.DOCKED);
    }

    public boolean show(Panel panel) {
        return set(panel, Visibility.DOCKED);
    }

    public boolean hide(Panel panel) {
        return set(panel, Visibility.HIDDEN);
    }

    public boolean detach(Panel panel) {
        return set(panel, Visibility.DETACHED);
    }

    /** Records a divider drag; not broadcast (the view that moved it already knows), only remembered. */
    public synchronized void rememberDivider(Panel panel, double position) {
        PanelState s = states.get(panel);
        states.put(panel, new PanelState(panel, s.visibility(), position));
    }

    public Subscription onChange(PanelListener listener) {
        listeners.add(Objects.requireNonNull(listener));
        return () -> listeners.remove(listener);
    }

    public int listenerCount() {
        return listeners.size();
    }

    private int dockedCountExcluding(Panel excluded) {
        int n = 0;
        for (PanelState s : states.values()) {
            if (s.panel() != excluded && s.visibility() == Visibility.DOCKED) {
                n++;
            }
        }
        return n;
    }

    private void fire(PanelState previous, PanelState next) {
        for (PanelListener l : listeners) {
            l.panelChanged(previous, next);
        }
    }
}
