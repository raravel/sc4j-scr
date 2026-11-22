package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

import org.jetbrains.annotations.NotNull;

/**
 * Fills the owner pointers of the classic console area table of the console race when an address space is created.
 * <p>
 * Each console race has a table of {@code 12}-byte entries: the position and size WORDs, then the owner pointer DWORD. The
 * entries of the selected table receive the value of the owner list pointer.
 */
public final class ClassicConsoleAreasUtils {
    /**
     * The console area tables: {table address, entry count} rows, indexed by console race.
     */
    private static final int @NotNull [] TABLES = {
            0x005152A8, 6, 0x00515300, 5, 0x00515348, 5, 0x00515388, 7
    };
    /**
     * The number of values of one row of {@link #TABLES}.
     */
    private static final int TABLE_ROW_SIZE = 2;
    /**
     * The row value of {@link #TABLES} holding the entry count.
     */
    private static final int TABLE_ROW_COUNT = 1;
    /**
     * The table of a console race without its own table.
     */
    private static final int DEFAULT_TABLE = 1;

    /**
     * The size of a table entry.
     */
    private static final int ENTRY_SIZE = 12;
    /**
     * The entry DWORD holding the owner pointer.
     */
    private static final int ENTRY_OWNER = 8;

    /**
     * The classic address of the pointer whose value the entries receive.
     */
    private static final int OWNER_POINTER = 0x0068C234;

    private static final int BYTE_MASK = 0xFF;

    /**
     * Stores the value of the owner list pointer into the owner DWORD of every entry of a console race's table. Does nothing
     * when the space has no region for the table or the pointer.
     *
     * @param space       the address space
     * @param consoleRace the console race byte; values from {@code 4} select the Terran table
     */
    public static void fill(@NotNull ClassicAddressSpace space, int consoleRace) {
        int race = consoleRace & BYTE_MASK;
        int row = (race < TABLES.length / TABLE_ROW_SIZE ? race : DEFAULT_TABLE) * TABLE_ROW_SIZE;
        int table = TABLES[row];
        int count = TABLES[row + TABLE_ROW_COUNT];

        if (space.findRegion(OWNER_POINTER) == null || space.findRegion(table) == null) {
            return;
        }

        for (int entry = 0; entry < count; entry++) {
            space.writeDword(table + entry * ENTRY_SIZE + ENTRY_OWNER, space.readDword(OWNER_POINTER));
        }
    }

    private ClassicConsoleAreasUtils() {
    }
}
