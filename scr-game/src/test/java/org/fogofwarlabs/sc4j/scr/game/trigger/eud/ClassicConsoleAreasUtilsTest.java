package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.ClassicStorageRegion;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Checks that the console area table of the console race receives the owner list pointer in its owner DWORDs.
 */
final class ClassicConsoleAreasUtilsTest {
    private static final int OWNER_POINTER = 0x68C234;
    private static final int OWNER_LIST = 0x82F280;
    private static final int ENTRY_SIZE = 12;
    private static final int ENTRY_OWNER = 8;
    private static final int UNCHANGED = 0x5A5A5A5A;

    /**
     * The console area tables: {table address, size, entry count} rows, indexed by console race.
     */
    private static final int @NotNull [] TABLES = {
            0x5152A8, 0x54, 6, 0x515300, 0x48, 5, 0x515348, 0x3C, 5, 0x515388, 0x60, 7
    };

    private static final int TABLE_ROW_SIZE = 3;

    private static @NotNull ClassicAddressSpace createSpace() {
        ClassicAddressSpace space = new ClassicAddressSpace();
        byte[] pointer = new byte[Integer.BYTES];

        space.add(new ClassicStorageRegion(OWNER_POINTER, pointer));
        space.writeDword(OWNER_POINTER, OWNER_LIST);

        for (int row = 0; row < TABLES.length; row += TABLE_ROW_SIZE) {
            space.add(new ClassicStorageRegion(TABLES[row], new byte[TABLES[row + 1]]));

            for (int offset = 0; offset < TABLES[row + 1]; offset += Integer.BYTES) {
                space.writeDword(TABLES[row] + offset, UNCHANGED);
            }
        }

        return space;
    }


    @ParameterizedTest
    @CsvSource({"0,0", "1,1", "2,2", "3,3", "4,1", "6,1", "255,1"})
    void fill_should_store_the_owner_list_in_every_entry_of_the_selected_table(int consoleRace, int selected) {
        ClassicAddressSpace space = createSpace();

        ClassicConsoleAreasUtils.fill(space, consoleRace);

        for (int row = 0; row < TABLES.length; row += TABLE_ROW_SIZE) {
            int table = TABLES[row];
            int count = row / TABLE_ROW_SIZE == selected ? TABLES[row + 2] : 0;

            for (int offset = 0; offset < TABLES[row + 1]; offset += Integer.BYTES) {
                boolean owner = offset % ENTRY_SIZE == ENTRY_OWNER && offset / ENTRY_SIZE < count;

                Assertions.assertEquals(owner ? OWNER_LIST : UNCHANGED, space.readDword(table + offset));
            }
        }
    }
}
