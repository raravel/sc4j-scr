package org.fogofwarlabs.sc4j.screnhanced.memory;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemorySink;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A classic dialog loaded from its dialog file: the dialog entry and its control entries, {@link #ENTRY_SIZE} bytes each, one after the other, and
 * the strings after them, at the address a fixed pointer holds. SC:R serves a node of the dialog file's size there; the structure replaces it.
 * <p>
 * An entry holds the address of the next entry at {@link #NEXT_OFFSET}, its rectangle as four signed WORDs, left, top, right and bottom, both
 * edges included, at {@link #RECTANGLE_OFFSET}, its picture as WORD width and WORD height at {@link #PICTURE_SIZE_OFFSET} and the address of its
 * pixels at {@link #PICTURE_OFFSET}, the address of its text at {@link #TEXT_OFFSET}, its flags at {@link #FLAGS_OFFSET}, its control identifier
 * at {@link #IDENTIFIER_OFFSET} and its control type at {@link #TYPE_OFFSET}. The dialog's rectangle is in the screen, a control's in the dialog;
 * the dialog holds its first control at {@link #FIRST_CONTROL_OFFSET}, a control its dialog at {@link #DIALOG_OFFSET}. The dialog's next entry and
 * picture pixels stay what SC:R serves; every text is an empty string. The controls are the SD dialog definitions' controls in their order, as
 * many as the file size holds.
 */
public final class ClassicDialogStructure implements ClassicStructure {
    /**
     * The size of a dialog or control entry.
     */
    public static final int ENTRY_SIZE = 0x56;
    /**
     * The offset of the next entry's address in an entry.
     */
    public static final int NEXT_OFFSET = 0x00;
    /**
     * The offset of the rectangle in an entry.
     */
    public static final int RECTANGLE_OFFSET = 0x04;
    /**
     * The offset of the picture width and height in an entry.
     */
    public static final int PICTURE_SIZE_OFFSET = 0x0C;
    /**
     * The offset of the picture's pixel address in an entry.
     */
    public static final int PICTURE_OFFSET = 0x10;
    /**
     * The offset of the text's address in an entry.
     */
    public static final int TEXT_OFFSET = 0x14;
    /**
     * The offset of the flags DWORD in an entry.
     */
    public static final int FLAGS_OFFSET = 0x18;
    /**
     * The offset of the control identifier WORD in an entry.
     */
    public static final int IDENTIFIER_OFFSET = 0x20;
    /**
     * The offset of the control type WORD in an entry.
     */
    public static final int TYPE_OFFSET = 0x22;
    /**
     * The offset of the dialog's address in a control entry.
     */
    public static final int DIALOG_OFFSET = 0x32;
    /**
     * The offset of the first control's address in the dialog entry.
     */
    public static final int FIRST_CONTROL_OFFSET = 0x46;
    /**
     * The flag of a shown entry.
     */
    public static final int VISIBLE_FLAG = 0x8;

    /**
     * The command card: nine buttons and the replay texts.
     */
    public static final @NotNull ClassicDialogStructure COMMAND_CARD = new ClassicDialogStructure(0x68C148, 0x4BE, 496, 354, 144, 126, new int[]{
            2, 1, 9, 4, 36, 34,
            2, 2, 55, 4, 36, 34,
            2, 3, 101, 4, 36, 34,
            2, 4, 9, 44, 36, 34,
            2, 5, 55, 44, 36, 34,
            2, 6, 101, 44, 36, 34,
            2, 7, 9, 84, 36, 34,
            2, 8, 55, 84, 36, 34,
            2, 9, 101, 84, 36, 34,
            11, -45, 0, 8, 142, 14,
            9, 10, 16, 24, 108, 9,
            11, -46, 0, 40, 142, 14,
            11, -47, 0, 56, 142, 14
    });

    /**
     * The selection panel.
     */
    public static final @NotNull ClassicDialogStructure SELECTION = new ClassicDialogStructure(0x68C1F0, 0x1388, 138, 388, 270, 92, new int[]{
            9, 1, 30, 0, 64, 64,
            11, -5, 102, 0, 152, 14,
            11, -7, 10, 64, 100, 12,
            11, -8, 10, 76, 100, 12,
            11, -15, 142, 21, 108, 14,
            2, 2, 104, 14, 39, 36,
            2, 3, 104, 53, 39, 36,
            2, 4, 143, 53, 39, 36,
            2, 5, 182, 53, 39, 36,
            2, 6, 221, 53, 39, 36,
            9, 7, 144, 39, 107, 9,
            9, 8, 104, 50, 39, 3,
            11, -35, 142, 21, 108, 14,
            2, 15, 104, 14, 39, 36,
            9, 14, 144, 39, 107, 9,
            11, -10, 143, 30, 111, 14,
            11, -11, 143, 44, 111, 14,
            11, -12, 143, 58, 111, 14,
            11, -13, 143, 72, 111, 14,
            11, -20, 106, 16, 144, 14,
            11, -21, 106, 30, 144, 14,
            9, 9, 104, 51, 36, 36,
            9, 10, 143, 51, 36, 36,
            9, 11, 182, 51, 36, 36,
            9, 12, 221, 51, 36, 36,
            11, -30, 102, 21, 152, 14,
            9, 13, 125, 39, 107, 9,
            11, -40, 142, 21, 108, 14,
            2, 17, 104, 14, 39, 36,
            9, 16, 144, 39, 107, 9,
            2, 18, 104, 14, 69, 71,
            2, 19, 176, 14, 69, 71,
            2, 20, 140, 14, 69, 71,
            2, 21, 104, 14, 33, 71,
            2, 22, 140, 14, 33, 71,
            2, 23, 176, 14, 33, 71,
            2, 24, 212, 14, 33, 71,
            2, 25, 104, 14, 33, 34,
            2, 26, 104, 51, 33, 34,
            2, 27, 140, 14, 33, 34,
            2, 28, 140, 51, 33, 34,
            2, 29, 176, 14, 33, 34,
            2, 30, 176, 51, 33, 34,
            2, 31, 212, 14, 33, 34,
            2, 32, 212, 51, 33, 34,
            2, 33, 30, 8, 33, 34,
            2, 34, 30, 45, 33, 34,
            2, 35, 66, 8, 33, 34,
            2, 36, 66, 45, 33, 34,
            2, 37, 102, 8, 33, 34,
            2, 38, 102, 45, 33, 34,
            2, 39, 138, 8, 33, 34,
            2, 40, 138, 45, 33, 34,
            2, 41, 174, 8, 33, 34,
            2, 42, 174, 45, 33, 34,
            2, 43, 210, 8, 33, 34,
            2, 44, 210, 45, 33, 34
    });

    /**
     * The resource display.
     */
    public static final @NotNull ClassicDialogStructure RESOURCES = new ClassicDialogStructure(0x68C234, 0x31A, 220, 0, 420, 20, new int[]{
            9, 1, 352, 0, 66, 20,
            9, 2, 284, 0, 66, 20,
            9, 3, 216, 0, 66, 20,
            9, 4, 148, 0, 66, 20,
            9, 5, 80, 0, 66, 20,
            9, 6, 9, 0, 69, 20,
            11, -10, 0, 0, 80, 20,
            11, -11, 216, 0, 198, 20
    });

    /**
     * The portrait.
     */
    public static final @NotNull ClassicDialogStructure PORTRAIT = new ClassicDialogStructure(0x68AC8C, 0x10E, 408, 408, 88, 72, new int[]{
            2, 1, 5, 2, 60, 56,
            9, -10, 0, 0, 1, 1
    });

    /**
     * The minimap and its buttons.
     */
    public static final @NotNull ClassicDialogStructure MINIMAP = new ClassicDialogStructure(0x59CB5C, 0x1B6, 0, 315, 138, 165, new int[]{
            5, 1, 6, 33, 128, 128,
            2, 2, 3, 5, 64, 24,
            2, 3, 76, 5, 52, 24,
            2, 4, 3, 5, 64, 24
    });

    /**
     * The menu button.
     */
    public static final @NotNull ClassicDialogStructure MENU = new ClassicDialogStructure(0x68C224, 0xB8, 408, 388, 88, 20, new int[]{
            2, 1, 8, 0, 64, 20
    });

    /**
     * The text box of the chat.
     */
    public static final @NotNull ClassicDialogStructure TEXT_BOX = new ClassicDialogStructure(0x68C140, 0x2DE, 170, 330, 280, 18, new int[]{
            8, 7, 0, 2, 280, 16,
            10, 1, 0, 2, 1, 16,
            10, 2, 0, 2, 1, 16,
            10, 3, 0, 2, 1, 16,
            10, 5, 0, 2, 1, 16,
            9, -10, 0, 0, 1, 1,
            10, 4, 0, 2, 1, 16
    });

    /**
     * The dialogs, in the order of {@link ClassicMemory#createStructures}.
     */
    public static final @NotNull List<@NotNull ClassicDialogStructure> DIALOGS = List.of(COMMAND_CARD, SELECTION, RESOURCES, PORTRAIT, MINIMAP, MENU,
                                                                                         TEXT_BOX);

    /**
     * The values of one control in a control table: type, identifier, left and top in the dialog, width and height.
     */
    private static final int CONTROL_VALUES = 6;
    private static final int CONTROL_TYPE = 0;
    private static final int CONTROL_IDENTIFIER = 1;
    private static final int CONTROL_LEFT = 2;
    private static final int CONTROL_TOP = 3;
    private static final int CONTROL_WIDTH = 4;
    private static final int CONTROL_HEIGHT = 5;
    /**
     * The control type of the dialog entry.
     */
    private static final int DIALOG_TYPE = 0;

    /**
     * @param control the control index, from {@code 0}
     * @return the offset of the control's entry in the dialog
     */
    public static int getControlOffset(int control) {
        return (control + 1) * ENTRY_SIZE;
    }

    private final int pointer;
    private final int size;
    private final int left;
    private final int top;
    private final int width;
    private final int height;
    private final int @NotNull [] controls;

    /**
     * Creates a {@link ClassicDialogStructure} instance.
     *
     * @param pointer  the classic address of the pointer to the dialog
     * @param size     the size of the dialog file
     * @param left     the screen column of the dialog's left edge
     * @param top      the screen row of the dialog's top edge
     * @param width    the dialog's width
     * @param height   the dialog's height
     * @param controls the controls, {@value #CONTROL_VALUES} values each: type, identifier, left and top in the dialog, width and height
     * @throws IllegalArgumentException if the entries and a string do not fit the size
     */
    public ClassicDialogStructure(int pointer, int size, int left, int top, int width, int height, int @NotNull [] controls) {
        if (controls.length % CONTROL_VALUES != 0 || (controls.length / CONTROL_VALUES + 1) * ENTRY_SIZE >= size) {
            throw Exceptions.newIllegalArgumentException("The dialog at 0x{0} does not fit {1} bytes.", Integer.toHexString(pointer), size);
        }

        this.pointer  = pointer;
        this.size     = size;
        this.left     = left;
        this.top      = top;
        this.width    = width;
        this.height   = height;
        this.controls = controls.clone();
    }

    /**
     * @return the classic address of the pointer to the dialog
     */
    public int getPointer() {
        return this.pointer;
    }

    /**
     * @return the size of the dialog file
     */
    public int getSize() {
        return this.size;
    }

    /**
     * @return the number of controls
     */
    public int getControlCount() {
        return this.controls.length / CONTROL_VALUES;
    }

    /**
     * @param control the control index, from {@code 0}
     * @return the control's identifier
     */
    public int getControlIdentifier(int control) {
        return this.controls[control * CONTROL_VALUES + CONTROL_IDENTIFIER];
    }

    /**
     * Adds the dialog over the node the pointer names, keeping the node's next entry and picture pixel addresses. Nothing is added when the pointer
     * or the node cannot be read.
     *
     * @param sink the sink that adds the regions
     * @param heap the heap, which the dialog does not use
     */
    @Override
    public void provide(@NotNull ClassicMemorySink sink, @NotNull ClassicHeap heap) {
        byte[] value = new byte[Integer.BYTES];

        if (sink.read(this.pointer, value, 0, value.length) != value.length) {
            return;
        }

        int base = LittleEndianUtils.readInt32(value, 0);
        byte[] node = new byte[this.size];

        if (base == 0 || sink.read(base, node, 0, node.length) != node.length) {
            return;
        }

        int next = LittleEndianUtils.readInt32(node, NEXT_OFFSET);
        int picture = LittleEndianUtils.readInt32(node, PICTURE_OFFSET);

        sink.add(new ClassicMemoryBlock(base, this.createImage(base, next, picture)));
    }

    /**
     * Creates the bytes of the dialog file laid out at a classic address.
     *
     * @param base    the classic address of the dialog
     * @param next    the address of the entry after the dialog
     * @param picture the address of the dialog's picture pixels
     * @return the bytes of the dialog file, {@link #getSize()} of them
     */
    public byte @NotNull [] createImage(int base, int next, int picture) {
        byte[] image = new byte[this.size];
        int count = this.getControlCount();
        int text = base + getControlOffset(count);

        writeEntry(image, 0, next, this.left, this.top, this.width, this.height, text, 0, DIALOG_TYPE);
        LittleEndianUtils.writeInt16(image, PICTURE_SIZE_OFFSET, this.width);
        LittleEndianUtils.writeInt16(image, PICTURE_SIZE_OFFSET + Short.BYTES, this.height);
        LittleEndianUtils.writeInt32(image, PICTURE_OFFSET, picture);

        if (count > 0) {
            LittleEndianUtils.writeInt32(image, FIRST_CONTROL_OFFSET, base + getControlOffset(0));
        }

        for (int control = 0; control < count; control++) {
            int offset = getControlOffset(control);
            int values = control * CONTROL_VALUES;

            writeEntry(image, offset, control + 1 < count ? base + getControlOffset(control + 1) : 0,
                       this.controls[values + CONTROL_LEFT], this.controls[values + CONTROL_TOP],
                       this.controls[values + CONTROL_WIDTH], this.controls[values + CONTROL_HEIGHT],
                       text, this.controls[values + CONTROL_IDENTIFIER], this.controls[values + CONTROL_TYPE]);
            LittleEndianUtils.writeInt32(image, offset + DIALOG_OFFSET, base);
        }

        return image;
    }

    private static void writeEntry(byte @NotNull [] image, int offset, int next,
                                   int left, int top, int width, int height,
                                   int text, int identifier, int type) {
        LittleEndianUtils.writeInt32(image, offset + NEXT_OFFSET, next);
        LittleEndianUtils.writeInt16(image, offset + RECTANGLE_OFFSET, left);
        LittleEndianUtils.writeInt16(image, offset + RECTANGLE_OFFSET + Short.BYTES, top);
        LittleEndianUtils.writeInt16(image, offset + RECTANGLE_OFFSET + 2 * Short.BYTES, left + width - 1);
        LittleEndianUtils.writeInt16(image, offset + RECTANGLE_OFFSET + 3 * Short.BYTES, top + height - 1);
        LittleEndianUtils.writeInt32(image, offset + TEXT_OFFSET, text);
        LittleEndianUtils.writeInt32(image, offset + FLAGS_OFFSET, VISIBLE_FLAG);
        LittleEndianUtils.writeInt16(image, offset + IDENTIFIER_OFFSET, identifier);
        LittleEndianUtils.writeInt16(image, offset + TYPE_OFFSET, type);
    }
}
