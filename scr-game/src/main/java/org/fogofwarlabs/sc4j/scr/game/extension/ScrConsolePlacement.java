package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a plugin places the console of a classic screen: the top-left corners of console dialogs and of their controls moved in the classic screen,
 * the dialogs and controls not shown, the mask of the pixels where the console and its dialogs are shown, and the pictures the dialogs show over it
 * ({@link #getSurfaces()}). A dialog or control that is neither moved nor hidden keeps its place in its dialog; a control is named by its
 * identifier in the dialog's definition. Without a mask the whole console is shown.
 * <p>
 * The classic screen of a dialog's positions stands centered in an interface wider than {@value #CLASSIC_WIDTH} units, or at its left or right
 * edge ({@link #setDialogAnchor}), so that a layout at an edge follows the interface width; the mask and the surfaces stay centered. The minimap
 * area, {@value #MINIMAP_SIZE} units on each side, may be drawn at another size from its top left ({@link #setMinimapSize}); the minimap dialog
 * grows or shrinks with it.
 * <p>
 * The change count grows with every move, every hidden or restored dialog, every hidden or restored control, every anchor and minimap size change
 * and every mask change.
 */
public final class ScrConsolePlacement {
    /**
     * The width of the classic screen in interface units.
     */
    public static final int CLASSIC_WIDTH = 640;
    /**
     * The side of the minimap area in interface units, the installed size of the minimap.
     */
    public static final int MINIMAP_SIZE = 128;
    /**
     * The identifier of the minimap area control in the minimap dialog's definition.
     */
    public static final int MINIMAP_AREA_CONTROL = 1;

    /**
     * A console dialog.
     */
    public enum Dialog {
        /**
         * The command card.
         */
        COMMAND_CARD,
        /**
         * The selection panel.
         */
        SELECTION,
        /**
         * The resource display.
         */
        RESOURCES,
        /**
         * The portrait.
         */
        PORTRAIT,
        /**
         * The minimap and its buttons.
         */
        MINIMAP,
        /**
         * The menu button.
         */
        MENU,
        /**
         * The text box of the chat.
         */
        TEXT_BOX
    }

    /**
     * Where the classic screen of a dialog's positions stands in an interface wider than the classic screen.
     */
    public enum Anchor {
        /**
         * At the interface's left edge.
         */
        LEFT,
        /**
         * Centered, the installed place.
         */
        CENTER,
        /**
         * At the interface's right edge.
         */
        RIGHT;

        /**
         * Computes the interface column of the classic screen's left edge.
         *
         * @param interfaceWidth the interface width in interface units
         * @return the column, {@code 0} for an interface not wider than the classic screen
         */
        public int computeClassicLeft(float interfaceWidth) {
            int spare = Math.max(0, (int) interfaceWidth - CLASSIC_WIDTH);

            return switch (this) {
                case LEFT -> 0;
                case CENTER -> spare / 2;
                case RIGHT -> spare;
            };
        }
    }

    /**
     * The place of one control: moved to a corner, hidden, or both.
     */
    private static final class ControlPlace {
        private boolean moved;
        private boolean hidden;
        private int left;
        private int top;

        /**
         * Creates a {@link ControlPlace} instance that is neither moved nor hidden.
         */
        private ControlPlace() {
            this.moved  = false;
            this.hidden = false;
            this.left   = 0;
            this.top    = 0;
        }
    }

    private final int width;
    private final int height;
    private final @NotNull ScrScreenOverlay surfaces;
    private final boolean @NotNull [] moved;
    private final boolean @NotNull [] hidden;
    private final int @NotNull [] lefts;
    private final int @NotNull [] tops;
    private final @NotNull Anchor @NotNull [] anchors;
    private final boolean @NotNull [] mask;
    private final @NotNull Map<Dialog, Map<Integer, ControlPlace>> controls;

    private boolean masked;
    private int minimapSize;
    private long changeCount;

    /**
     * Creates a {@link ScrConsolePlacement} instance with no moved dialog or control, no mask and no surface pixel.
     *
     * @param width  the screen width in pixels
     * @param height the screen height in pixels
     * @throws IllegalArgumentException if a size is not positive
     */
    public ScrConsolePlacement(int width, int height) {
        this.width    = width;
        this.height   = height;
        this.surfaces = new ScrScreenOverlay(width, height);
        this.moved    = new boolean[Dialog.values().length];
        this.hidden   = new boolean[Dialog.values().length];
        this.lefts    = new int[Dialog.values().length];
        this.tops     = new int[Dialog.values().length];
        this.anchors  = new Anchor[Dialog.values().length];
        this.mask     = new boolean[width * height];
        this.controls = new EnumMap<>(Dialog.class);

        this.masked      = false;
        this.minimapSize = MINIMAP_SIZE;
        this.changeCount = 0;

        Arrays.fill(this.anchors, Anchor.CENTER);
    }

    /**
     * @return the screen width in pixels
     */
    public int getWidth() {
        return this.width;
    }

    /**
     * @return the screen height in pixels
     */
    public int getHeight() {
        return this.height;
    }

    /**
     * @return the number of dialog and control moves, hidden and restored dialogs and controls, anchor and minimap size changes and mask changes
     * so far
     */
    public long getChangeCount() {
        return this.changeCount;
    }

    /**
     * Moves a dialog and shows it.
     *
     * @param dialog the dialog
     * @param left   the screen column of its left edge
     * @param top    the screen row of its top edge
     */
    public void moveDialog(@NotNull Dialog dialog, int left, int top) {
        int index = dialog.ordinal();

        if (this.moved[index] && !this.hidden[index] && this.lefts[index] == left && this.tops[index] == top) {
            return;
        }

        this.moved[index]  = true;
        this.hidden[index] = false;
        this.lefts[index]  = left;
        this.tops[index]   = top;
        this.changeCount++;
    }

    /**
     * Hides a dialog: it is neither drawn nor hit.
     *
     * @param dialog the dialog
     */
    public void hideDialog(@NotNull Dialog dialog) {
        int index = dialog.ordinal();

        if (this.hidden[index]) {
            return;
        }

        this.hidden[index] = true;
        this.changeCount++;
    }

    /**
     * Restores a dialog to its installed place: neither moved nor hidden.
     *
     * @param dialog the dialog
     */
    public void restoreDialog(@NotNull Dialog dialog) {
        int index = dialog.ordinal();

        if (!this.moved[index] && !this.hidden[index]) {
            return;
        }

        this.moved[index]  = false;
        this.hidden[index] = false;
        this.changeCount++;
    }

    /**
     * @param dialog the dialog
     * @return where the classic screen of the dialog's positions stands in the interface
     */
    public @NotNull Anchor getDialogAnchor(@NotNull Dialog dialog) {
        return this.anchors[dialog.ordinal()];
    }

    /**
     * Sets where the classic screen of a dialog's positions, and those of its controls, stands in an interface wider than the classic screen.
     *
     * @param dialog the dialog
     * @param anchor the anchor
     */
    public void setDialogAnchor(@NotNull Dialog dialog, @NotNull Anchor anchor) {
        int index = dialog.ordinal();

        if (this.anchors[index] == anchor) {
            return;
        }

        this.anchors[index] = anchor;
        this.changeCount++;
    }

    /**
     * @return the side of the drawn minimap area in interface units
     */
    public int getMinimapSize() {
        return this.minimapSize;
    }

    /**
     * Sets the side of the drawn minimap area: the minimap, its view frame and its pings are drawn and hit scaled by
     * {@code minimapSize / }{@value #MINIMAP_SIZE} from the area's top left.
     * <p>
     * The minimap dialog grows or shrinks with the area, by {@code minimapSize - }{@value #MINIMAP_SIZE} in width and height, and its other
     * controls keep their offsets from the dialog's top left. A moved dialog keeps its top left; at its installed place the dialog keeps its
     * bottom-left corner, so that the area stays inside the console.
     *
     * @param minimapSize the side in interface units
     * @throws IllegalArgumentException if the side is not positive
     */
    public void setMinimapSize(int minimapSize) {
        if (minimapSize <= 0) {
            throw Exceptions.newIllegalArgumentException("Invalid minimap size {0}", minimapSize);
        }

        if (this.minimapSize == minimapSize) {
            return;
        }

        this.minimapSize = minimapSize;
        this.changeCount++;
    }

    /**
     * @param dialog the dialog
     * @return {@code true} if the dialog is hidden, {@code false} otherwise
     */
    public boolean isDialogHidden(@NotNull Dialog dialog) {
        return this.hidden[dialog.ordinal()];
    }

    /**
     * @param dialog the dialog
     * @return {@code true} if the dialog is moved, a hidden dialog included, {@code false} otherwise
     */
    public boolean isDialogMoved(@NotNull Dialog dialog) {
        return this.moved[dialog.ordinal()];
    }

    /**
     * @param dialog a moved dialog
     * @return the screen column of its left edge
     * @throws IllegalStateException if the dialog is not moved
     */
    public int getDialogLeft(@NotNull Dialog dialog) {
        this.requireMoved(dialog);

        return this.lefts[dialog.ordinal()];
    }

    /**
     * @param dialog a moved dialog
     * @return the screen row of its top edge
     * @throws IllegalStateException if the dialog is not moved
     */
    public int getDialogTop(@NotNull Dialog dialog) {
        this.requireMoved(dialog);

        return this.tops[dialog.ordinal()];
    }

    /**
     * Moves a control of a dialog and shows it.
     *
     * @param dialog  the dialog
     * @param control the control identifier
     * @param left    the screen column of its left edge
     * @param top     the screen row of its top edge
     */
    public void moveControl(@NotNull Dialog dialog, int control, int left, int top) {
        ControlPlace place = this.placeControl(dialog, control);

        if (place.moved && !place.hidden && place.left == left && place.top == top) {
            return;
        }

        place.moved  = true;
        place.hidden = false;
        place.left   = left;
        place.top    = top;
        this.changeCount++;
    }

    /**
     * Hides a control of a dialog: it is neither drawn nor hit.
     *
     * @param dialog  the dialog
     * @param control the control identifier
     */
    public void hideControl(@NotNull Dialog dialog, int control) {
        ControlPlace place = this.placeControl(dialog, control);

        if (place.hidden) {
            return;
        }

        place.hidden = true;
        this.changeCount++;
    }

    /**
     * Restores a control of a dialog to its place in its dialog: neither moved nor hidden, and no longer placed.
     *
     * @param dialog  the dialog
     * @param control the control identifier
     */
    public void restoreControl(@NotNull Dialog dialog, int control) {
        Map<Integer, ControlPlace> places = this.controls.get(dialog);

        if (places == null || places.remove(control) == null) {
            return;
        }

        this.changeCount++;
    }

    /**
     * @param dialog the dialog
     * @return the identifiers of the dialog's moved or hidden controls, in the order they were first placed
     */
    public int @NotNull [] getPlacedControls(@NotNull Dialog dialog) {
        return this.controls.getOrDefault(dialog, Map.of()).keySet().stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * @param dialog  the dialog
     * @param control the control identifier
     * @return {@code true} if the control is hidden, {@code false} otherwise
     */
    public boolean isControlHidden(@NotNull Dialog dialog, int control) {
        ControlPlace place = this.controls.getOrDefault(dialog, Map.of()).get(control);

        return place != null && place.hidden;
    }

    /**
     * @param dialog  the dialog
     * @param control the control identifier
     * @return {@code true} if the control is moved, a hidden control included, {@code false} otherwise
     */
    public boolean isControlMoved(@NotNull Dialog dialog, int control) {
        ControlPlace place = this.controls.getOrDefault(dialog, Map.of()).get(control);

        return place != null && place.moved;
    }

    /**
     * @param dialog  the dialog
     * @param control the identifier of a moved control
     * @return the screen column of its left edge
     * @throws IllegalStateException if the control is not moved
     */
    public int getControlLeft(@NotNull Dialog dialog, int control) {
        return this.requireMoved(dialog, control).left;
    }

    /**
     * @param dialog  the dialog
     * @param control the identifier of a moved control
     * @return the screen row of its top edge
     * @throws IllegalStateException if the control is not moved
     */
    public int getControlTop(@NotNull Dialog dialog, int control) {
        return this.requireMoved(dialog, control).top;
    }

    /**
     * @return {@code true} if the console is shown only where the mask shows it, {@code false} otherwise
     */
    public boolean isMasked() {
        return this.masked;
    }

    /**
     * Sets whether the console is shown at a pixel and makes the console masked.
     *
     * @param offset the pixel offset, {@code row * width + column}
     * @param shown  whether the console is shown there
     * @throws IndexOutOfBoundsException if the offset is outside the screen
     */
    public void setMaskPixel(int offset, boolean shown) {
        if (this.masked && this.mask[offset] == shown) {
            return;
        }

        this.masked       = true;
        this.mask[offset] = shown;
        this.changeCount++;
    }

    /**
     * @param column the screen column
     * @param row    the screen row
     * @return {@code true} if the console is shown at the pixel: inside the screen, and the console is not masked or the mask shows it
     */
    public boolean isShown(int column, int row) {
        if (column < 0 || row < 0 || column >= this.width || row >= this.height) {
            return false;
        }

        return !this.masked || this.mask[row * this.width + column];
    }

    /**
     * @return the pictures the dialogs show over the console, drawn where the console is shown
     */
    public @NotNull ScrScreenOverlay getSurfaces() {
        return this.surfaces;
    }

    private void requireMoved(@NotNull Dialog dialog) {
        if (!this.moved[dialog.ordinal()]) {
            throw Exceptions.newIllegalStateException("The dialog {0} is not moved.", dialog);
        }
    }

    private @NotNull ControlPlace placeControl(@NotNull Dialog dialog, int control) {
        return this.controls.computeIfAbsent(dialog, key -> new LinkedHashMap<>()).computeIfAbsent(control, identifier -> new ControlPlace());
    }

    private @NotNull ControlPlace requireMoved(@NotNull Dialog dialog, int control) {
        ControlPlace place = this.controls.getOrDefault(dialog, Map.of()).get(control);

        if (place == null || !place.moved) {
            throw Exceptions.newIllegalStateException("The control {0} of the dialog {1} is not moved.", control, dialog);
        }

        return place;
    }
}
