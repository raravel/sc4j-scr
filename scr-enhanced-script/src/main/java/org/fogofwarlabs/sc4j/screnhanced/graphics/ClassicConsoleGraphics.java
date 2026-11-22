package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsoleArt;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsolePlacement;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrScreenOverlay;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemoryView;
import org.fogofwarlabs.sc4j.screnhanced.memory.ClassicConsoleAreaStructure;
import org.fogofwarlabs.sc4j.screnhanced.memory.ClassicDialogStructure;
import org.fogofwarlabs.sc4j.screnhanced.memory.ClassicMemory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Supplier;

/**
 * The classic console of one game, as a map changes it: the console dialogs and the text box moved through their rectangles, their controls moved
 * through their own rectangles or hidden through their flags, the dialog pictures a map draws, and the console transparency that decides where the
 * console and its dialogs are shown over the game screen.
 * <p>
 * The dialogs are the dialog files of {@link ClassicDialogStructure#DIALOGS} at the addresses their pointers hold. A dialog whose rectangle was
 * written is moved to its left and top; a dialog whose picture size was written shows that picture, one palette index per pixel, rows from the
 * top, at its rectangle instead of its controls. A control whose rectangle differs from its loaded one is moved to its left and top in its dialog;
 * a control without {@link ClassicDialogStructure#VISIBLE_FLAG} is hidden. The owners of the console areas
 * ({@link ClassicConsoleAreaStructure}) show their pictures the same way once their picture sizes were written. The console transparency
 * ({@link ClassicMemory#CONSOLE_TRANSPARENCY_DATA}) holds, for every screen row, pairs of BYTE counts of skipped and shown pixels and a zero pair at
 * the row's end; once written, the console, its dialogs and the pictures are shown only at its shown pixels.
 * <p>
 * The resource icons are the GRP at the address {@link ClassicMemory#RESOURCE_ICONS} holds ({@link ClassicResourceIcons}); once written, the
 * console draws its frames instead of the installed icons.
 * <p>
 * The console settings of the game's map script ({@link ConsoleSettings}) are merged item by item: what the script set for an item wins over
 * EUD memory, and EUD memory still drives every item the script never set. The items are the console as a whole (the script can hide it), each
 * dialog's visibility (a script setting wins over a dialog that EUD memory hides by its picture size), each dialog's position (wins over the
 * dialog's written rectangle), each control's visibility and position (win over the control's flags and rectangle), each console art file
 * ({@link ScrConsoleArt}; a script picture is drawn over the installed file's place, and a replaced frame file of the shown graphics shows the
 * whole frame instead of the transparency-masked art, the transparency still masking the dialogs) and each resource icon (a script picture wins
 * over the frame of the GRP in memory). The dialog pictures and the transparency come from EUD memory alone.
 * <p>
 * The plugin observes the writes through the game's memory view and never changes the game.
 */
public final class ClassicConsoleGraphics implements ScrGamePlugin {
    /**
     * The placement names of the dialogs of {@link ClassicDialogStructure#DIALOGS}, in their order.
     */
    public static final ScrConsolePlacement.Dialog @NotNull [] PLACED_DIALOGS = {
            ScrConsolePlacement.Dialog.COMMAND_CARD, ScrConsolePlacement.Dialog.SELECTION, ScrConsolePlacement.Dialog.RESOURCES,
            ScrConsolePlacement.Dialog.PORTRAIT, ScrConsolePlacement.Dialog.MINIMAP, ScrConsolePlacement.Dialog.MENU,
            ScrConsolePlacement.Dialog.TEXT_BOX
    };
    /**
     * The size of the picture buffer of a dialog.
     */
    public static final int PICTURE_BUFFER_SIZE = ClassicConsoleAreaStructure.PICTURE_BUFFER_SIZE;

    private static final int TOP_OFFSET = ClassicDialogStructure.RECTANGLE_OFFSET + Short.BYTES;
    private static final int HEIGHT_OFFSET = ClassicDialogStructure.PICTURE_SIZE_OFFSET + Short.BYTES;
    private static final int BYTE_SHIFT = 8;
    private static final int BYTE_MASK = 0xFF;
    private static final int TABLE_ROW_SIZE = 2;
    private static final int TRANSPARENCY_PAIR_SIZE = 2;

    /**
     * One observed dialog or console area owner: its loaded and current bytes, the bytes of its picture buffer and what the map wrote.
     */
    private static final class ObservedDialog {
        private final ScrConsolePlacement.@Nullable Dialog dialog;
        private final @Nullable ClassicDialogStructure structure;
        private final int base;
        private final byte @NotNull [] loaded;
        private final byte @NotNull [] image;
        private final int pictureBase;
        private final byte @NotNull [] picture;

        private boolean rectangleWritten;
        private boolean pictureWritten;
        private boolean controlWritten;

        ObservedDialog(ScrConsolePlacement.@Nullable Dialog dialog, @Nullable ClassicDialogStructure structure, int base, byte @NotNull [] image) {
            this.dialog      = dialog;
            this.structure   = structure;
            this.base        = base;
            this.loaded      = image.clone();
            this.image       = image;
            this.pictureBase = LittleEndianUtils.readInt32(image, ClassicDialogStructure.PICTURE_OFFSET);
            this.picture     = new byte[PICTURE_BUFFER_SIZE];

            this.rectangleWritten = false;
            this.pictureWritten   = false;
            this.controlWritten   = false;
        }

        /**
         * @return the screen column of the dialog's left edge
         */
        int getLeft() {
            return (short) LittleEndianUtils.readUnsigned16(this.image, ClassicDialogStructure.RECTANGLE_OFFSET);
        }

        /**
         * @return the screen row of the dialog's top edge
         */
        int getTop() {
            return (short) LittleEndianUtils.readUnsigned16(this.image, TOP_OFFSET);
        }
    }

    private final @NotNull Supplier<@Nullable ConsoleSettings> settingsSource;
    private @Nullable ConsoleSettings settings;
    private long settingsChange;

    private final @NotNull List<ObservedDialog> dialogs;
    private final @NotNull ScrConsolePlacement placement;
    private final byte @NotNull [] transparency;
    private final boolean @NotNull [] mask;
    private final byte @NotNull [] pixels;
    private final boolean @NotNull [] shown;

    private boolean written;
    private boolean transparencyWritten;
    private boolean changed;

    private final byte @NotNull [] icons;
    private int iconsBase;
    private boolean iconsWritten;
    private boolean iconsChanged;
    private @Nullable SpriteSheet iconSheet;

    /**
     * Creates a {@link ClassicConsoleGraphics} instance with the installed console.
     *
     * @param settingsSource finds the console settings of the game's map script, {@code null} while the game has none
     */
    public ClassicConsoleGraphics(@NotNull Supplier<@Nullable ConsoleSettings> settingsSource) {
        int screenPixels = ClassicMemory.SCREEN_WIDTH * ClassicMemory.SCREEN_HEIGHT;

        this.settingsSource = settingsSource;
        this.settings       = null;
        this.settingsChange = -1;

        this.dialogs      = new ArrayList<>();
        this.placement    = new ScrConsolePlacement(ClassicMemory.SCREEN_WIDTH, ClassicMemory.SCREEN_HEIGHT);
        this.transparency = new byte[ClassicMemory.TRANSPARENCY_DATA_SIZE];
        this.mask         = new boolean[screenPixels];
        this.pixels       = new byte[screenPixels];
        this.shown        = new boolean[screenPixels];

        this.written             = false;
        this.transparencyWritten = false;
        this.changed             = false;

        this.icons        = new byte[ClassicMemory.RESOURCE_ICONS_SIZE];
        this.iconsBase    = 0;
        this.iconsWritten = false;
        this.iconsChanged = false;
        this.iconSheet    = null;
    }

    /**
     * Hears that the game's EUD address space was created: observes the dialogs, their picture buffers, the console transparency and the resource
     * icons.
     *
     * @param memory the read-only view of the address space
     */
    @Override
    public void onAddressSpaceCreated(@NotNull ClassicMemoryView memory) {
        byte[] pointer = new byte[Integer.BYTES];

        for (int index = 0; index < ClassicDialogStructure.DIALOGS.size(); index++) {
            ClassicDialogStructure structure = ClassicDialogStructure.DIALOGS.get(index);

            if (memory.read(structure.getPointer(), pointer, 0, pointer.length) != pointer.length) {
                continue;
            }

            int base = LittleEndianUtils.readInt32(pointer, 0);
            byte[] image = new byte[structure.getSize()];

            if (base == 0 || memory.read(base, image, 0, image.length) != image.length) {
                continue;
            }

            this.observe(memory, new ObservedDialog(PLACED_DIALOGS[index], structure, base, image));
        }

        this.observeConsoleAreas(memory);

        memory.observeWrites(ClassicMemory.CONSOLE_TRANSPARENCY_DATA, ClassicMemory.TRANSPARENCY_DATA_SIZE, this::onTransparencyWrite);

        if (memory.read(ClassicMemory.RESOURCE_ICONS, pointer, 0, pointer.length) == pointer.length) {
            int base = LittleEndianUtils.readInt32(pointer, 0);

            if (base != 0 && memory.read(base, this.icons, 0, this.icons.length) == this.icons.length) {
                this.iconsBase = base;

                memory.observeWrites(base, this.icons.length, this::onIconWrite);
            }
        }
    }

    /**
     * Observes the owners of the console areas that no observed dialog is.
     *
     * @param memory the read-only view of the address space
     */
    private void observeConsoleAreas(@NotNull ClassicMemoryView memory) {
        for (int row = 0; row < ClassicConsoleAreaStructure.TABLES.length; row += TABLE_ROW_SIZE) {
            byte[] table = new byte[ClassicConsoleAreaStructure.TABLES[row + 1]];

            if (memory.read(ClassicConsoleAreaStructure.TABLES[row], table, 0, table.length) != table.length) {
                continue;
            }

            for (int offset = 0; offset + ClassicConsoleAreaStructure.ENTRY_SIZE <= table.length; offset += ClassicConsoleAreaStructure.ENTRY_SIZE) {
                int owner = LittleEndianUtils.readInt32(table, offset + ClassicConsoleAreaStructure.OWNER_OFFSET);
                byte[] image = new byte[ClassicDialogStructure.ENTRY_SIZE];

                if (owner != 0 && !this.isObserved(owner) && memory.read(owner, image, 0, image.length) == image.length) {
                    this.observe(memory, new ObservedDialog(null, null, owner, image));
                }
            }
        }
    }

    /**
     * Finds the observed console dialog of a placement name.
     *
     * @param dialog the placement name of the dialog
     * @return the dialog, or {@code null} if it is not observed
     */
    private @Nullable ObservedDialog findObserved(ScrConsolePlacement.@NotNull Dialog dialog) {
        for (ObservedDialog observed : this.dialogs) {
            if (observed.dialog == dialog) {
                return observed;
            }
        }

        return null;
    }

    private boolean isObserved(int base) {
        for (ObservedDialog observed : this.dialogs) {
            if (observed.base == base) {
                return true;
            }
        }

        return false;
    }

    private void observe(@NotNull ClassicMemoryView memory, @NotNull ObservedDialog observed) {
        this.dialogs.add(observed);
        memory.observeWrites(observed.base, observed.image.length, (address, value) -> this.onDialogWrite(observed, address, value));

        if (observed.pictureBase != 0) {
            memory.observeWrites(observed.pictureBase, PICTURE_BUFFER_SIZE, (address, value) -> this.onPictureWrite(observed, address, value));
        }
    }

    /**
     * Finds the console settings of the game's map script, once they exist.
     *
     * @return the settings, or {@code null} while the game has no map script
     */
    private @Nullable ConsoleSettings findSettings() {
        if (this.settings == null) {
            this.settings = this.settingsSource.get();
        }

        return this.settings;
    }

    /**
     * @return {@code true} if the map script hid the console, {@code false} otherwise
     */
    @Override
    public boolean isConsoleHidden() {
        ConsoleSettings current = this.findSettings();

        return current != null && !current.isConsoleVisible();
    }

    /**
     * Finds the picture the console draws instead of an installed art file of the shown race console, over the file's place and size. The same
     * picture is returned while the art is unchanged.
     * <p>
     * The picture is the one the map script set for the file.
     *
     * @param art the art file
     * @return the picture, or {@code null} for the installed file
     */
    @Override
    public @Nullable Picture findConsoleArt(@NotNull ScrConsoleArt art) {
        ConsoleSettings current = this.findSettings();

        return current == null ? null : current.getArt(art);
    }

    /**
     * Finds the picture the console draws for a resource icon instead of its installed icon and of the frame of {@link #findResourceIcons}: fitted
     * into the installed icon's box. The same picture is returned while the icon is unchanged.
     * <p>
     * The picture is the one the map script set for the icon.
     *
     * @param icon the resource icon frame: {@code 0} minerals, {@code 1} to {@code 3} gas and {@code 4} to {@code 6} supply of the Zerg, Terran and Protoss
     * @return the picture, or {@code null} for the other icons
     */
    @Override
    public @Nullable Picture findResourceIconPicture(int icon) {
        ConsoleSettings current = this.findSettings();

        return current == null || icon < 0 || icon >= ConsoleSettings.RESOURCE_ICON_COUNT ? null : current.getResourceIcon(icon);
    }

    /**
     * Finds the resource icons the map wrote.
     *
     * @return the indexed sheet of the written icons, or {@code null} before the map wrote them or while they are not a GRP
     */
    @Override
    public @Nullable SpriteSheet findResourceIcons() {
        if (!this.iconsWritten) {
            return null;
        }

        if (this.iconsChanged) {
            this.iconsChanged = false;
            this.iconSheet    = ClassicResourceIcons.parse(this.icons);
        }

        return this.iconSheet;
    }

    /**
     * Finds how the map placed the console, its map script's dialog settings merged in.
     *
     * @return the placement, or {@code null} while the map wrote no dialog and no transparency and its script set no dialog
     */
    @Override
    public @Nullable ScrConsolePlacement findConsolePlacement() {
        ConsoleSettings current = this.findSettings();
        boolean scripted = current != null && current.hasDialogSettings();

        if (!this.written && !scripted) {
            return null;
        }

        long change = current == null ? -1 : current.getChangeCount();

        if (this.changed || change != this.settingsChange) {
            this.changed        = false;
            this.settingsChange = change;
            this.update(current);
        }

        return this.placement;
    }

    /**
     * Brings the placement to the written dialogs, controls, transparency and pictures and to the script's dialog settings: the minimap size and
     * the dialog anchors are the script's; a dialog is hidden when the script hid it, or when it did not set its visibility and EUD memory gave
     * it a picture; a shown dialog is moved to the script's position, else to its written rectangle, else restored to its installed place; its
     * controls are placed the same way ({@link #placeControls}).
     *
     * @param current the console settings of the map script, or {@code null} without a script
     */
    private void update(@Nullable ConsoleSettings current) {
        this.placement.setMinimapSize(current == null ? ScrConsolePlacement.MINIMAP_SIZE : current.getMinimapSize());

        for (ScrConsolePlacement.Dialog dialog : PLACED_DIALOGS) {
            ObservedDialog observed = this.findObserved(dialog);

            this.placement.setDialogAnchor(dialog, current == null ? ScrConsolePlacement.Anchor.CENTER : current.getDialogAnchor(dialog));

            boolean hidden = current != null && current.isDialogVisibilitySet(dialog)
                             ? !current.isDialogVisible(dialog)
                             : observed != null && observed.pictureWritten;

            if (hidden) {
                this.placement.hideDialog(dialog);
            } else if (current != null && current.isDialogPositionSet(dialog)) {
                this.placement.moveDialog(dialog, current.getDialogLeft(dialog), current.getDialogTop(dialog));
            } else if (observed != null && observed.rectangleWritten) {
                this.placement.moveDialog(dialog, observed.getLeft(), observed.getTop());
            } else {
                this.placement.restoreDialog(dialog);
            }

            this.placeControls(dialog, observed, current);
        }

        if (this.transparencyWritten) {
            decodeTransparency(this.transparency, ClassicMemory.SCREEN_WIDTH, this.mask);

            for (int offset = 0; offset < this.mask.length; offset++) {
                this.placement.setMaskPixel(offset, this.mask[offset]);
            }
        }

        Arrays.fill(this.shown, false);

        for (ObservedDialog observed : this.dialogs) {
            if (observed.pictureWritten) {
                this.drawPicture(observed);
            }
        }

        ScrScreenOverlay surfaces = this.placement.getSurfaces();

        for (int offset = 0; offset < this.shown.length; offset++) {
            if (this.shown[offset]) {
                surfaces.setPixel(offset, this.pixels[offset]);
            } else {
                surfaces.hidePixel(offset);
            }
        }
    }

    /**
     * Places the controls of a dialog, the script's setting winning for each item it set. A control of written control entries without the visible
     * flag is hidden, and one whose rectangle differs from its loaded one is moved to its left and top in the dialog's written rectangle; a control
     * the script hid is hidden, and one the script placed is moved to its position; any other control keeps, or is restored to, its place in its
     * dialog.
     *
     * @param dialog   the placement name of the dialog
     * @param observed the observed dialog, or {@code null} if it is not observed
     * @param current  the console settings of the map script, or {@code null} without a script
     */
    private void placeControls(ScrConsolePlacement.@NotNull Dialog dialog, @Nullable ObservedDialog observed, @Nullable ConsoleSettings current) {
        ClassicDialogStructure structure = observed == null || !observed.controlWritten ? null : observed.structure;
        Set<Integer> placed = new HashSet<>();

        if (structure != null) {
            for (int control = 0; control < structure.getControlCount(); control++) {
                int offset = ClassicDialogStructure.getControlOffset(control);
                int identifier = structure.getControlIdentifier(control);
                int flags = LittleEndianUtils.readInt32(observed.image, offset + ClassicDialogStructure.FLAGS_OFFSET);
                int left = (short) LittleEndianUtils.readUnsigned16(observed.image, offset + ClassicDialogStructure.RECTANGLE_OFFSET);
                int top = (short) LittleEndianUtils.readUnsigned16(observed.image, offset + TOP_OFFSET);
                boolean moved = left != (short) LittleEndianUtils.readUnsigned16(observed.loaded, offset + ClassicDialogStructure.RECTANGLE_OFFSET) ||
                                top != (short) LittleEndianUtils.readUnsigned16(observed.loaded, offset + TOP_OFFSET);
                boolean hidden = current != null && current.isControlVisibilitySet(dialog, identifier)
                                 ? !current.isControlVisible(dialog, identifier)
                                 : (flags & ClassicDialogStructure.VISIBLE_FLAG) == 0;

                placed.add(identifier);

                if (hidden) {
                    this.placement.hideControl(dialog, identifier);
                } else if (current != null && current.isControlPositionSet(dialog, identifier)) {
                    this.placement.moveControl(dialog, identifier,
                                               current.getControlLeft(dialog, identifier), current.getControlTop(dialog, identifier));
                } else if (moved) {
                    this.placement.moveControl(dialog, identifier, observed.getLeft() + left, observed.getTop() + top);
                } else {
                    this.placement.restoreControl(dialog, identifier);
                }
            }
        }

        for (int identifier : current == null ? new int[0] : current.getControls(dialog)) {
            if (!placed.add(identifier)) {
                continue;
            }

            if (!current.isControlVisible(dialog, identifier)) {
                this.placement.hideControl(dialog, identifier);
            } else if (current.isControlPositionSet(dialog, identifier)) {
                this.placement.moveControl(dialog, identifier, current.getControlLeft(dialog, identifier), current.getControlTop(dialog, identifier));
            } else {
                this.placement.restoreControl(dialog, identifier);
            }
        }

        for (int identifier : this.placement.getPlacedControls(dialog)) {
            if (!placed.contains(identifier)) {
                this.placement.restoreControl(dialog, identifier);
            }
        }
    }

    /**
     * Draws the picture of a dialog at its rectangle into the composed pixels, where the console is shown and the pixel is not {@code 0}.
     *
     * @param observed the dialog
     */
    private void drawPicture(@NotNull ObservedDialog observed) {
        int left = observed.getLeft();
        int top = observed.getTop();
        int width = LittleEndianUtils.readUnsigned16(observed.image, ClassicDialogStructure.PICTURE_SIZE_OFFSET);
        int height = LittleEndianUtils.readUnsigned16(observed.image, HEIGHT_OFFSET);

        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int source = row * width + column;
                int x = left + column;
                int y = top + row;

                if (source >= observed.picture.length) {
                    return;
                }

                if (observed.picture[source] != 0 && this.placement.isShown(x, y)) {
                    int offset = y * ClassicMemory.SCREEN_WIDTH + x;

                    this.pixels[offset] = observed.picture[source];
                    this.shown[offset]  = true;
                }
            }
        }
    }

    /**
     * Decodes a console transparency into a mask of the shown pixels.
     *
     * @param data  the transparency data
     * @param width the screen width in pixels
     * @param mask  the mask of one screen, rows from the top, set to the shown pixels
     */
    static void decodeTransparency(byte @NotNull [] data, int width, boolean @NotNull [] mask) {
        Arrays.fill(mask, false);

        int index = 0;

        for (int row = 0; row < mask.length / width; row++) {
            int column = 0;

            while (index + 1 < data.length) {
                int skipped = data[index] & BYTE_MASK;
                int copied = data[index + 1] & BYTE_MASK;

                index += TRANSPARENCY_PAIR_SIZE;

                if (skipped == 0 && copied == 0) {
                    break;
                }

                column += skipped;

                for (int pixel = 0; pixel < copied; pixel++, column++) {
                    if (column < width) {
                        mask[row * width + column] = true;
                    }
                }
            }
        }
    }

    private void onDialogWrite(@NotNull ObservedDialog observed, int address, int value) {
        int offset = address - observed.base;

        for (int index = 0; index < Integer.BYTES; index++) {
            int byteOffset = offset + index;

            if (byteOffset < 0 || byteOffset >= observed.image.length) {
                continue;
            }

            observed.image[byteOffset] = (byte) (value >>> index * BYTE_SHIFT & BYTE_MASK);

            if (byteOffset >= ClassicDialogStructure.ENTRY_SIZE) {
                observed.controlWritten = true;
            } else if (byteOffset >= ClassicDialogStructure.RECTANGLE_OFFSET && byteOffset < ClassicDialogStructure.PICTURE_SIZE_OFFSET) {
                observed.rectangleWritten = true;
            } else if (byteOffset >= ClassicDialogStructure.PICTURE_SIZE_OFFSET && byteOffset < ClassicDialogStructure.PICTURE_OFFSET) {
                observed.pictureWritten = true;
            }
        }

        this.written = true;
        this.changed = true;
    }

    private void onPictureWrite(@NotNull ObservedDialog observed, int address, int value) {
        int offset = address - observed.pictureBase;

        for (int index = 0; index < Integer.BYTES; index++) {
            if (offset + index >= 0 && offset + index < observed.picture.length) {
                observed.picture[offset + index] = (byte) (value >>> index * BYTE_SHIFT & BYTE_MASK);
            }
        }

        this.changed |= observed.pictureWritten;
    }

    private void onIconWrite(int address, int value) {
        int offset = address - this.iconsBase;

        for (int index = 0; index < Integer.BYTES; index++) {
            if (offset + index >= 0 && offset + index < this.icons.length) {
                this.icons[offset + index] = (byte) (value >>> index * BYTE_SHIFT & BYTE_MASK);
            }
        }

        this.iconsWritten = true;
        this.iconsChanged = true;
    }

    private void onTransparencyWrite(int address, int value) {
        int offset = address - ClassicMemory.CONSOLE_TRANSPARENCY_DATA;

        for (int index = 0; index < Integer.BYTES; index++) {
            if (offset + index >= 0 && offset + index < this.transparency.length) {
                this.transparency[offset + index] = (byte) (value >>> index * BYTE_SHIFT & BYTE_MASK);
            }
        }

        this.transparencyWritten = true;
        this.written             = true;
        this.changed             = true;
    }
}
