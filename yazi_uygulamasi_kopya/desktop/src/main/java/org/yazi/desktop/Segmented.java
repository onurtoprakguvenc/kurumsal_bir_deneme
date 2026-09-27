package org.yazi.desktop;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A row of mutually exclusive toggle buttons bound to one value, styled as a segmented control.
 *
 * <p>Exactly one segment is always selected: clicking the selected segment keeps it. With a segment focused,
 * Left/Right (and Home/End) move the selection, like a radio group. FX thread only.</p>
 */
final class Segmented<T> {

    private final HBox root = new HBox();
    private final ToggleGroup group = new ToggleGroup();
    private final Map<T, ToggleButton> buttons = new LinkedHashMap<>();
    private final ObjectProperty<T> value = new SimpleObjectProperty<>();
    private final List<T> items;

    Segmented(List<T> items, Function<T, String> label, T initial) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("A segmented control needs at least one item.");
        }
        this.items = List.copyOf(items);
        root.getStyleClass().add("segmented");
        for (T item : this.items) {
            ToggleButton b = new ToggleButton(label.apply(item));
            b.setToggleGroup(group);
            b.setUserData(item);
            buttons.put(item, b);
            root.getChildren().add(b);
        }
        group.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) {
                group.selectToggle(old);   // never leave the control without a value
            } else {
                value.set(itemOf(now));
            }
        });
        value.addListener((obs, old, now) -> {
            ToggleButton b = buttons.get(now);
            if (b != null && !b.isSelected()) {
                b.setSelected(true);
            }
        });
        root.setOnKeyPressed(e -> {
            int i = this.items.indexOf(value.get());
            int target = switch (e.getCode()) {
                case LEFT, KP_LEFT -> Math.max(0, i - 1);
                case RIGHT, KP_RIGHT -> Math.min(this.items.size() - 1, i + 1);
                case HOME -> 0;
                case END -> this.items.size() - 1;
                default -> -1;
            };
            if (target >= 0) {
                setValue(this.items.get(target));
                buttons.get(this.items.get(target)).requestFocus();
                e.consume();
            }
        });
        setValue(initial != null ? initial : this.items.get(0));
    }

    HBox view() {
        return root;
    }

    ObjectProperty<T> valueProperty() {
        return value;
    }

    T getValue() {
        return value.get();
    }

    void setValue(T item) {
        value.set(item);
    }

    ToggleButton button(T item) {
        return buttons.get(item);
    }

    Segmented<T> tooltip(T item, String text) {
        buttons.get(item).setTooltip(new Tooltip(text));
        return this;
    }

    Segmented<T> id(String id) {
        root.setId(id);
        return this;
    }

    @SuppressWarnings("unchecked")
    private T itemOf(Toggle toggle) {
        return (T) toggle.getUserData();
    }
}
