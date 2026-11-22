package org.fogofwarlabs.sc4j.screnhanced.memory;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemorySink;
import org.jetbrains.annotations.NotNull;

/**
 * The classic console area tables, one per console race: {@link #ENTRY_SIZE}-byte entries of an area of the console art, its left, top, width
 * and height as signed WORDs and the address of its owner at {@link #OWNER_OFFSET}, ended by an entry whose left and top are {@code -1}.
 * <p>
 * Every area has an owner of its own: an entry laid out as a dialog entry ({@link ClassicDialogStructure#ENTRY_SIZE} bytes) with the area's
 * rectangle at {@link ClassicDialogStructure#RECTANGLE_OFFSET}, both edges included, the area's size as its picture size at
 * {@link ClassicDialogStructure#PICTURE_SIZE_OFFSET}, the address of a zeroed picture buffer of {@link #PICTURE_BUFFER_SIZE} bytes at
 * {@link ClassicDialogStructure#PICTURE_OFFSET} and {@link ClassicDialogStructure#VISIBLE_FLAG} set. The structure serves the tables whose owners
 * SC:R filled, each entry with a filled owner pointing at its own owner on the heap; the other tables keep what SC:R serves.
 */
public final class ClassicConsoleAreaStructure implements ClassicStructure {
    /**
     * The size of a console area entry.
     */
    public static final int ENTRY_SIZE = 12;
    /**
     * The offset of the owner's address in a console area entry.
     */
    public static final int OWNER_OFFSET = 8;
    /**
     * The console area tables: {table address, size} rows, in console race order.
     */
    public static final int @NotNull [] TABLES = {
            0x5152A8, 0x54, 0x515300, 0x48, 0x515348, 0x3C, 0x515388, 0x60
    };

    /**
     * The size of an owner's picture buffer, that of the dialog picture buffers SC:R serves.
     */
    public static final int PICTURE_BUFFER_SIZE = 0x109A0;

    private static final int TABLE_ROW_SIZE = 2;
    private static final int TOP_OFFSET = 2;
    private static final int WIDTH_OFFSET = 4;
    private static final int HEIGHT_OFFSET = 6;

    /**
     * Adds the tables whose owners are filled, with an owner of its own for every filled entry.
     *
     * @param sink the sink that adds the regions
     * @param heap the heap that places the owners
     */
    @Override
    public void provide(@NotNull ClassicMemorySink sink, @NotNull ClassicHeap heap) {
        for (int row = 0; row < TABLES.length; row += TABLE_ROW_SIZE) {
            byte[] table = new byte[TABLES[row + 1]];

            if (sink.read(TABLES[row], table, 0, table.length) != table.length || !hasOwners(table)) {
                continue;
            }

            int count = table.length / ENTRY_SIZE;
            int owners = heap.allocate(count * ClassicDialogStructure.ENTRY_SIZE);
            byte[] entries = new byte[count * ClassicDialogStructure.ENTRY_SIZE];

            for (int entry = 0; entry < count; entry++) {
                int offset = entry * ENTRY_SIZE;

                if (LittleEndianUtils.readInt32(table, offset + OWNER_OFFSET) == 0) {
                    continue;
                }

                int picture = heap.allocate(PICTURE_BUFFER_SIZE);

                writeOwner(entries, entry * ClassicDialogStructure.ENTRY_SIZE, table, offset, picture);
                sink.add(new ClassicMemoryBlock(picture, PICTURE_BUFFER_SIZE));
                LittleEndianUtils.writeInt32(table, offset + OWNER_OFFSET, owners + entry * ClassicDialogStructure.ENTRY_SIZE);
            }

            sink.add(new ClassicMemoryBlock(TABLES[row], table));
            sink.add(new ClassicMemoryBlock(owners, entries));
        }
    }

    private static boolean hasOwners(byte @NotNull [] table) {
        for (int offset = 0; offset + ENTRY_SIZE <= table.length; offset += ENTRY_SIZE) {
            if (LittleEndianUtils.readInt32(table, offset + OWNER_OFFSET) != 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Writes the owner of an area: its rectangle, its picture and the shown flag.
     *
     * @param entries the owner entries
     * @param offset  the offset of the owner's entry
     * @param table   the console area table
     * @param area    the offset of the area's entry in the table
     * @param picture the address of the owner's picture buffer
     */
    private static void writeOwner(byte @NotNull [] entries, int offset, byte @NotNull [] table, int area, int picture) {
        int left = (short) LittleEndianUtils.readUnsigned16(table, area);
        int top = (short) LittleEndianUtils.readUnsigned16(table, area + TOP_OFFSET);
        int width = (short) LittleEndianUtils.readUnsigned16(table, area + WIDTH_OFFSET);
        int height = (short) LittleEndianUtils.readUnsigned16(table, area + HEIGHT_OFFSET);
        int rectangle = offset + ClassicDialogStructure.RECTANGLE_OFFSET;

        LittleEndianUtils.writeInt16(entries, rectangle, left);
        LittleEndianUtils.writeInt16(entries, rectangle + Short.BYTES, top);
        LittleEndianUtils.writeInt16(entries, rectangle + 2 * Short.BYTES, left + width - 1);
        LittleEndianUtils.writeInt16(entries, rectangle + 3 * Short.BYTES, top + height - 1);
        LittleEndianUtils.writeInt16(entries, offset + ClassicDialogStructure.PICTURE_SIZE_OFFSET, width);
        LittleEndianUtils.writeInt16(entries, offset + ClassicDialogStructure.PICTURE_SIZE_OFFSET + Short.BYTES, height);
        LittleEndianUtils.writeInt32(entries, offset + ClassicDialogStructure.PICTURE_OFFSET, picture);
        LittleEndianUtils.writeInt32(entries, offset + ClassicDialogStructure.FLAGS_OFFSET, ClassicDialogStructure.VISIBLE_FLAG);
    }
}
