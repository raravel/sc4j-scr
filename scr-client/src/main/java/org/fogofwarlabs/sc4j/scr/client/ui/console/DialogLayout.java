package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogImportUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Holds one installed interface dialog definition ({@code rez/<name>.ui.json}) in the {@code 640} by {@code 480} interface space.
 * <p>
 * The definitions are the SC:R conversions of the classic RC3 dialogs ({@code rez/UISchema.json}): a {@code DIALOG} with a
 * rectangle {@code [x, y, width, height]} and children with a type, content key, identifier, rectangle and flags. Child
 * rectangles are relative to the dialog. This class is a view over the one ui.json loader {@link DialogDefinition} that
 * resolves the top-level controls to absolute interface coordinates (dialog left/top plus control left/top).
 */
public final class DialogLayout {
    private static final int WIDTH_INDEX = 2;
    private static final int HEIGHT_INDEX = 3;

    /**
     * One dialog control.
     */
    public static final class Control {
        private final @NotNull String type;
        private final int identifier;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final @NotNull Set<String> flags;
        private final @NotNull String content;

        private Control(@NotNull String type,
                        int identifier,
                        int x, int y,
                        int width, int height,
                        @NotNull Set<String> flags,
                        @NotNull String content) {
            this.type       = type;
            this.identifier = identifier;
            this.x          = x;
            this.y          = y;
            this.width      = width;
            this.height     = height;
            this.flags      = flags;
            this.content    = content;
        }

        /**
         * @return the control type, for example {@code PUSHBUTTON}, {@code LTEXT} or {@code RECT}
         */
        public @NotNull String getType() {
            return this.type;
        }

        /**
         * @return the control identifier
         */
        public int getIdentifier() {
            return this.identifier;
        }

        /**
         * @return the absolute left coordinate in the {@code 640} by {@code 480} interface space
         */
        public int getX() {
            return this.x;
        }

        /**
         * @return the absolute top coordinate in the {@code 640} by {@code 480} interface space
         */
        public int getY() {
            return this.y;
        }

        /**
         * @return the width
         */
        public int getWidth() {
            return this.width;
        }

        /**
         * @return the height
         */
        public int getHeight() {
            return this.height;
        }

        /**
         * @return the content key, for example {@code gamemenu-1} (resolved through {@code RC3.json}), or an empty string
         */
        public @NotNull String getContent() {
            return this.content;
        }

        /**
         * @return the style flags, for example {@code WS_VISIBLE}
         */
        public @NotNull Set<String> getFlags() {
            return this.flags;
        }
    }

    /**
     * Creates a {@link DialogLayout} instance from an installed SD dialog definition.
     *
     * @param storage the installed game storage
     * @param name    the dialog file name without {@code .ui.json}, for example {@code statbtnt}
     * @return the created {@link DialogLayout} instance
     * @throws IOException if the file is missing or malformed
     */
    public static @NotNull DialogLayout load(@NotNull CascStorage storage, @NotNull String name) throws IOException {
        return of(ScrDialogImportUtils.load(storage, name));
    }

    /**
     * Creates a {@link DialogLayout} instance from the contents of a dialog definition.
     *
     * @param contents the file contents
     * @return the created {@link DialogLayout} instance
     * @throws IOException if the contents are not a dialog definition
     */
    public static @NotNull DialogLayout parse(byte @NotNull [] contents) throws IOException {
        return of(ScrDialogImportUtils.parse(contents, ""));
    }

    /**
     * Creates a {@link DialogLayout} instance that resolves a loaded definition to absolute interface coordinates.
     *
     * @param definition the dialog definition
     * @return the created {@link DialogLayout} instance
     */
    public static @NotNull DialogLayout of(@NotNull DialogDefinition definition) {
        List<Control> controls = new ArrayList<>();

        for (DialogControl control : definition.getControls()) {
            controls.add(new Control(ScrDialogControlUtils.getType(control).name(),
                                     control.getId(),
                                     definition.getX() + control.getX(), definition.getY() + control.getY(),
                                     control.getWidth(), control.getHeight(),
                                     ScrDialogControlUtils.getFlags(control),
                                     ScrDialogControlUtils.getContent(control)));
        }

        int[] rectangle = {definition.getX(), definition.getY(), definition.getWidth(), definition.getHeight()};

        return new DialogLayout(definition, rectangle, controls);
    }

    private final @NotNull DialogDefinition definition;
    private final @NotNull String name;
    private final int @NotNull [] rectangle;
    private final @NotNull List<Control> controls;

    private DialogLayout(@NotNull DialogDefinition definition, int @NotNull [] rectangle, @NotNull List<Control> controls) {
        this.definition = definition;
        this.name       = definition.getName();
        this.rectangle  = rectangle;
        this.controls   = List.copyOf(controls);
    }

    /**
     * @return the loaded definition this view resolves
     */
    public @NotNull DialogDefinition getDefinition() {
        return this.definition;
    }

    /**
     * @return the dialog name
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the dialog left coordinate
     */
    public int getX() {
        return this.rectangle[0];
    }

    /**
     * @return the dialog top coordinate
     */
    public int getY() {
        return this.rectangle[1];
    }

    /**
     * @return the dialog width
     */
    public int getWidth() {
        return this.rectangle[WIDTH_INDEX];
    }

    /**
     * @return the dialog height
     */
    public int getHeight() {
        return this.rectangle[HEIGHT_INDEX];
    }

    /**
     * @return the controls in definition order
     */
    public @NotNull List<Control> getControls() {
        return this.controls;
    }

    /**
     * Returns the first control with an identifier.
     *
     * @param identifier the control identifier
     * @return the control
     * @throws IllegalArgumentException if the dialog has no such control
     */
    public @NotNull Control getControl(int identifier) {
        for (Control control : this.controls) {
            if (control.getIdentifier() == identifier) {
                return control;
            }
        }

        throw Exceptions.newIllegalArgumentException("Dialog {0} has no control {1}.", this.name, identifier);
    }

    /**
     * Creates a {@link DialogLayout} instance with this dialog moved by an offset, controls included.
     *
     * @param offsetX the horizontal offset in interface pixels
     * @param offsetY the vertical offset in interface pixels
     * @return the created {@link DialogLayout} instance
     */
    public @NotNull DialogLayout createMovedLayout(float offsetX, float offsetY) {
        int horizontalShift = Math.round(offsetX);
        int verticalShift = Math.round(offsetY);
        List<Control> movedControls = new ArrayList<>(this.controls.size());

        for (Control control : this.controls) {
            movedControls.add(new Control(control.type, control.identifier,
                                          control.x + horizontalShift, control.y + verticalShift,
                                          control.width, control.height,
                                          control.flags, control.content));
        }

        int[] movedRectangle = {this.rectangle[0] + horizontalShift,
                                this.rectangle[1] + verticalShift,
                                this.rectangle[WIDTH_INDEX],
                                this.rectangle[HEIGHT_INDEX]};

        return new DialogLayout(this.definition, movedRectangle, movedControls);
    }

    /**
     * Creates a {@link DialogLayout} instance with a top-level control at another size and this dialog grown or shrunk by the same amounts, its
     * bottom-left corner kept; every control keeps its offset from the dialog's top-left corner.
     *
     * @param identifier the control identifier
     * @param width      the control's width
     * @param height     the control's height
     * @return the created {@link DialogLayout} instance
     * @throws IllegalArgumentException if the dialog has no such control, or a size becomes negative
     */
    public @NotNull DialogLayout createResizedLayout(int identifier, int width, int height) {
        Control resized = this.getControl(identifier);
        int growthX = width - resized.getWidth();
        int growthY = height - resized.getHeight();
        List<DialogControl> resizedControls = new ArrayList<>(this.definition.getControls().size());

        for (DialogControl control : this.definition.getControls()) {
            if (control.getId() == identifier) {
                resizedControls.add(new DialogControl(control.getId(), control.getKind(),
                                                      control.getX(), control.getY(), width, height,
                                                      control.getText(), control.getTextAlignment(), control.getImage(),
                                                      control.getAttributes(), control.getChildren()));
            } else {
                resizedControls.add(control);
            }
        }

        return of(new DialogDefinition(this.definition.getName(), this.getX(), this.getY() - growthY, this.getWidth() + growthX,
                                       this.getHeight() + growthY, resizedControls));
    }

    /**
     * Creates a {@link DialogLayout} instance with this dialog's top-left corner at a place and some top-level controls at places of their own; the
     * other controls keep their place in the dialog. The definition of the created layout holds the placed controls, so the dialog's runtime state
     * draws and hit-tests them at their places.
     *
     * @param left   the interface column of the dialog's left edge
     * @param top    the interface row of the dialog's top edge
     * @param places the place of a control by identifier: its interface column and row, or {@code null} to keep its place in the dialog
     * @return the created {@link DialogLayout} instance
     */
    public @NotNull DialogLayout createPlacedLayout(int left, int top, @NotNull IntFunction<int @Nullable []> places) {
        List<DialogControl> placedControls = new ArrayList<>(this.definition.getControls().size());

        for (DialogControl control : this.definition.getControls()) {
            int[] place = places.apply(control.getId());

            if (place == null) {
                placedControls.add(control);
            } else {
                placedControls.add(new DialogControl(control.getId(), control.getKind(),
                                                     place[0] - left, place[1] - top, control.getWidth(), control.getHeight(),
                                                     control.getText(), control.getTextAlignment(), control.getImage(),
                                                     control.getAttributes(), control.getChildren()));
            }
        }

        return of(new DialogDefinition(this.definition.getName(), left, top, this.definition.getWidth(), this.definition.getHeight(),
                                       placedControls));
    }
}
