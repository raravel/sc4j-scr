package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsoleArt;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsolePlacement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a map script sets on the console: whether the console is shown, the visibility and the top-left corner of each console dialog and of the
 * controls of a dialog in the classic screen, the interface edge the classic screen of a dialog stands at ({@link ScrConsolePlacement.Anchor}),
 * the size of the minimap area, the pictures drawn instead of the console art files ({@link ScrConsoleArt}) and the pictures of the resource
 * icons. An item the script never set, or restored, is unset, so that the classic console ({@link ClassicConsoleGraphics}) shows what EUD memory
 * gives for it; an item the script set wins over EUD memory from then on. The change count grows with every change.
 */
public final class ConsoleSettings {
    /**
     * The number of resource icon frames: minerals, then the gas and the supply of the Zerg, Terran and Protoss.
     */
    public static final int RESOURCE_ICON_COUNT = 7;

    private static final int DIALOG_COUNT = ScrConsolePlacement.Dialog.values().length;
    private static final int ART_COUNT = ScrConsoleArt.values().length;

    /**
     * What the script set on one control: its visibility, its top-left corner, or both.
     */
    private static final class ControlSetting {
        private boolean visibilitySet;
        private boolean visible;
        private boolean positionSet;
        private int left;
        private int top;

        /**
         * Creates a {@link ControlSetting} instance with nothing set.
         */
        private ControlSetting() {
            this.visibilitySet = false;
            this.visible       = false;
            this.positionSet   = false;
            this.left          = 0;
            this.top           = 0;
        }
    }

    private boolean consoleVisible;

    private final boolean @NotNull [] visibilitySet;
    private final boolean @NotNull [] visible;
    private final boolean @NotNull [] positionSet;
    private final int @NotNull [] lefts;
    private final int @NotNull [] tops;
    private final ScrConsolePlacement.@NotNull Anchor @NotNull [] anchors;
    private final @NotNull Map<ScrConsolePlacement.Dialog, Map<Integer, ControlSetting>> controls;
    private int minimapSize;

    private final @Nullable Picture @NotNull [] arts;
    private final @Nullable Picture @NotNull [] resourceIcons;

    private long changeCount;

    /**
     * Creates a {@link ConsoleSettings} instance with the console shown and nothing else set.
     */
    public ConsoleSettings() {
        this.consoleVisible = true;

        this.visibilitySet = new boolean[DIALOG_COUNT];
        this.visible       = new boolean[DIALOG_COUNT];
        this.positionSet   = new boolean[DIALOG_COUNT];
        this.lefts         = new int[DIALOG_COUNT];
        this.tops          = new int[DIALOG_COUNT];
        this.anchors       = new ScrConsolePlacement.Anchor[DIALOG_COUNT];
        this.controls      = new EnumMap<>(ScrConsolePlacement.Dialog.class);
        this.minimapSize   = ScrConsolePlacement.MINIMAP_SIZE;

        this.arts          = new Picture[ART_COUNT];
        this.resourceIcons = new Picture[RESOURCE_ICON_COUNT];

        this.changeCount = 0;

        Arrays.fill(this.anchors, ScrConsolePlacement.Anchor.CENTER);
    }

    /**
     * @return the number of changes so far
     */
    public long getChangeCount() {
        return this.changeCount;
    }

    /**
     * @return {@code true} if the console is shown, {@code false} if the script hid it
     */
    public boolean isConsoleVisible() {
        return this.consoleVisible;
    }

    /**
     * Sets whether the console is shown.
     *
     * @param consoleVisible whether the console is shown
     */
    public void setConsoleVisible(boolean consoleVisible) {
        if (this.consoleVisible != consoleVisible) {
            this.consoleVisible = consoleVisible;
            this.changeCount++;
        }
    }

    /**
     * @return {@code true} if the script set the visibility, the position or the anchor of a dialog, the visibility or the position of a control,
     * or the minimap size, {@code false} otherwise
     */
    public boolean hasDialogSettings() {
        if (this.minimapSize != ScrConsolePlacement.MINIMAP_SIZE) {
            return true;
        }

        for (int index = 0; index < DIALOG_COUNT; index++) {
            if (this.visibilitySet[index] || this.positionSet[index] || this.anchors[index] != ScrConsolePlacement.Anchor.CENTER) {
                return true;
            }
        }

        for (Map<Integer, ControlSetting> settings : this.controls.values()) {
            if (!settings.isEmpty()) {
                return true;
            }
        }

        return false;
    }

    /**
     * @param dialog the dialog
     * @return {@code true} if the script set whether the dialog is shown, {@code false} otherwise
     */
    public boolean isDialogVisibilitySet(ScrConsolePlacement.@NotNull Dialog dialog) {
        return this.visibilitySet[dialog.ordinal()];
    }

    /**
     * @param dialog the dialog
     * @return {@code true} if the script showed the dialog or never set it, {@code false} if it hid it
     */
    public boolean isDialogVisible(ScrConsolePlacement.@NotNull Dialog dialog) {
        return !this.visibilitySet[dialog.ordinal()] || this.visible[dialog.ordinal()];
    }

    /**
     * Sets whether a dialog is shown.
     *
     * @param dialog  the dialog
     * @param visible whether the dialog is shown
     */
    public void setDialogVisible(ScrConsolePlacement.@NotNull Dialog dialog, boolean visible) {
        int index = dialog.ordinal();

        if (!this.visibilitySet[index] || this.visible[index] != visible) {
            this.visibilitySet[index] = true;
            this.visible[index]       = visible;
            this.changeCount++;
        }
    }

    /**
     * @param dialog the dialog
     * @return the interface edge the classic screen of the dialog's positions stands at, {@link ScrConsolePlacement.Anchor#CENTER} by default
     */
    public ScrConsolePlacement.@NotNull Anchor getDialogAnchor(ScrConsolePlacement.@NotNull Dialog dialog) {
        return this.anchors[dialog.ordinal()];
    }

    /**
     * Sets the interface edge the classic screen of a dialog's positions, and those of its controls, stands at.
     *
     * @param dialog the dialog
     * @param anchor the anchor
     */
    public void setDialogAnchor(ScrConsolePlacement.@NotNull Dialog dialog, ScrConsolePlacement.@NotNull Anchor anchor) {
        if (this.anchors[dialog.ordinal()] != anchor) {
            this.anchors[dialog.ordinal()] = anchor;
            this.changeCount++;
        }
    }

    /**
     * @return the side of the drawn minimap area in interface units, {@link ScrConsolePlacement#MINIMAP_SIZE} by default
     */
    public int getMinimapSize() {
        return this.minimapSize;
    }

    /**
     * Sets the side of the drawn minimap area.
     *
     * @param minimapSize the side in interface units, positive
     */
    public void setMinimapSize(int minimapSize) {
        if (this.minimapSize != minimapSize) {
            this.minimapSize = minimapSize;
            this.changeCount++;
        }
    }

    /**
     * @param dialog the dialog
     * @return {@code true} if the script set the dialog's position, {@code false} otherwise
     */
    public boolean isDialogPositionSet(ScrConsolePlacement.@NotNull Dialog dialog) {
        return this.positionSet[dialog.ordinal()];
    }

    /**
     * @param dialog the dialog
     * @return the classic screen column of the dialog's left edge the script set, {@code 0} if it set none
     */
    public int getDialogLeft(ScrConsolePlacement.@NotNull Dialog dialog) {
        return this.lefts[dialog.ordinal()];
    }

    /**
     * @param dialog the dialog
     * @return the classic screen row of the dialog's top edge the script set, {@code 0} if it set none
     */
    public int getDialogTop(ScrConsolePlacement.@NotNull Dialog dialog) {
        return this.tops[dialog.ordinal()];
    }

    /**
     * Sets the top-left corner of a dialog in the classic screen.
     *
     * @param dialog the dialog
     * @param left   the screen column of its left edge
     * @param top    the screen row of its top edge
     */
    public void setDialogPosition(ScrConsolePlacement.@NotNull Dialog dialog, int left, int top) {
        int index = dialog.ordinal();

        if (!this.positionSet[index] || this.lefts[index] != left || this.tops[index] != top) {
            this.positionSet[index] = true;
            this.lefts[index]       = left;
            this.tops[index]        = top;
            this.changeCount++;
        }
    }

    /**
     * @param dialog the dialog
     * @return the identifiers of the dialog's controls the script set something on, in the order it first set them
     */
    public int @NotNull [] getControls(ScrConsolePlacement.@NotNull Dialog dialog) {
        return this.controls.getOrDefault(dialog, Map.of()).keySet().stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * @param dialog  the dialog
     * @param control the control identifier in the dialog's definition
     * @return {@code true} if the script set whether the control is shown, {@code false} otherwise
     */
    public boolean isControlVisibilitySet(ScrConsolePlacement.@NotNull Dialog dialog, int control) {
        ControlSetting setting = this.findControl(dialog, control);

        return setting != null && setting.visibilitySet;
    }

    /**
     * @param dialog  the dialog
     * @param control the control identifier in the dialog's definition
     * @return {@code true} if the script showed the control or never set it, {@code false} if it hid it
     */
    public boolean isControlVisible(ScrConsolePlacement.@NotNull Dialog dialog, int control) {
        ControlSetting setting = this.findControl(dialog, control);

        return setting == null || !setting.visibilitySet || setting.visible;
    }

    /**
     * Sets whether a control of a dialog is shown.
     *
     * @param dialog  the dialog
     * @param control the control identifier in the dialog's definition
     * @param visible whether the control is shown
     */
    public void setControlVisible(ScrConsolePlacement.@NotNull Dialog dialog, int control, boolean visible) {
        ControlSetting setting = this.placeControl(dialog, control);

        if (!setting.visibilitySet || setting.visible != visible) {
            setting.visibilitySet = true;
            setting.visible       = visible;
            this.changeCount++;
        }
    }

    /**
     * @param dialog  the dialog
     * @param control the control identifier in the dialog's definition
     * @return {@code true} if the script set the control's position, {@code false} otherwise
     */
    public boolean isControlPositionSet(ScrConsolePlacement.@NotNull Dialog dialog, int control) {
        ControlSetting setting = this.findControl(dialog, control);

        return setting != null && setting.positionSet;
    }

    /**
     * @param dialog  the dialog
     * @param control the control identifier in the dialog's definition
     * @return the classic screen column of the control's left edge the script set, {@code 0} if it set none
     */
    public int getControlLeft(ScrConsolePlacement.@NotNull Dialog dialog, int control) {
        ControlSetting setting = this.findControl(dialog, control);

        return setting == null ? 0 : setting.left;
    }

    /**
     * @param dialog  the dialog
     * @param control the control identifier in the dialog's definition
     * @return the classic screen row of the control's top edge the script set, {@code 0} if it set none
     */
    public int getControlTop(ScrConsolePlacement.@NotNull Dialog dialog, int control) {
        ControlSetting setting = this.findControl(dialog, control);

        return setting == null ? 0 : setting.top;
    }

    /**
     * Sets the top-left corner of a control of a dialog in the classic screen.
     *
     * @param dialog  the dialog
     * @param control the control identifier in the dialog's definition
     * @param left    the screen column of its left edge
     * @param top     the screen row of its top edge
     */
    public void setControlPosition(ScrConsolePlacement.@NotNull Dialog dialog, int control, int left, int top) {
        ControlSetting setting = this.placeControl(dialog, control);

        if (!setting.positionSet || setting.left != left || setting.top != top) {
            setting.positionSet = true;
            setting.left        = left;
            setting.top         = top;
            this.changeCount++;
        }
    }

    /**
     * Forgets what the script set on a control of a dialog, so that EUD memory drives it again, or it keeps its place in its dialog.
     *
     * @param dialog  the dialog
     * @param control the control identifier in the dialog's definition
     */
    public void restoreControl(ScrConsolePlacement.@NotNull Dialog dialog, int control) {
        Map<Integer, ControlSetting> settings = this.controls.get(dialog);

        if (settings != null && settings.remove(control) != null) {
            this.changeCount++;
        }
    }

    private @Nullable ControlSetting findControl(ScrConsolePlacement.@NotNull Dialog dialog, int control) {
        return this.controls.getOrDefault(dialog, Map.of()).get(control);
    }

    private @NotNull ControlSetting placeControl(ScrConsolePlacement.@NotNull Dialog dialog, int control) {
        return this.controls.computeIfAbsent(dialog, key -> new LinkedHashMap<>()).computeIfAbsent(control, identifier -> new ControlSetting());
    }

    /**
     * @param art the console art file
     * @return the picture drawn instead of the file, or {@code null} for the installed file
     */
    public @Nullable Picture getArt(@NotNull ScrConsoleArt art) {
        return this.arts[art.ordinal()];
    }

    /**
     * Sets the picture drawn instead of a console art file.
     *
     * @param art     the console art file
     * @param picture the picture, or {@code null} for the installed file
     */
    public void setArt(@NotNull ScrConsoleArt art, @Nullable Picture picture) {
        if (this.arts[art.ordinal()] != picture) {
            this.arts[art.ordinal()] = picture;
            this.changeCount++;
        }
    }

    /**
     * @param icon the resource icon frame, below {@link #RESOURCE_ICON_COUNT}
     * @return the picture drawn for the icon, or {@code null} for the icon of EUD memory or the installed icon
     * @throws IndexOutOfBoundsException if the frame is not a resource icon frame
     */
    public @Nullable Picture getResourceIcon(int icon) {
        return this.resourceIcons[icon];
    }

    /**
     * Sets the picture drawn for a resource icon.
     *
     * @param icon    the resource icon frame, below {@link #RESOURCE_ICON_COUNT}
     * @param picture the picture, or {@code null} for the icon of EUD memory or the installed icon
     * @throws IndexOutOfBoundsException if the frame is not a resource icon frame
     */
    public void setResourceIcon(int icon, @Nullable Picture picture) {
        if (this.resourceIcons[icon] != picture) {
            this.resourceIcons[icon] = picture;
            this.changeCount++;
        }
    }
}
