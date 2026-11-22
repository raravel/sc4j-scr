package org.fogofwarlabs.sc4j.scr.game.trigger.eud.region;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.dat.FlingyDat;
import org.fogofwarlabs.sc4j.scr.data.format.dat.ImagesDat;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicRegion;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * A classic DAT array of Remastered's EUD emulation: one column block of units, weapons, flingy, sprites, images, orders,
 * upgrades or techdata, whose host is the game's own array. Each classic array has the element size and count of the
 * game's array; the layout is the file's block layout.
 * <ul>
 *     <li>Plain regions copy the game's bytes, so a write changes the game's DAT value.</li>
 *     <li>Write-protected regions (the three sprites arrays of the bar sprites): a write succeeds and changes
 *     nothing.</li>
 *     <li>Validated regions (units graphics, sprites image, images draw function, images iscript) keep a shadow per
 *     DWORD: a write takes the host DWORD into the shadow unless it is flagged, applies the value, and stores the DWORD
 *     to the game only when every byte is a flingy id (graphics), every WORD an image id (image), every byte a draw
 *     function (draw function) or the DWORD an iscript id (iscript); otherwise the DWORD stays flagged and reads return
 *     it.</li>
 * </ul>
 */
public final class ClassicDatRegion implements ClassicRegion {
    /**
     * The number of draw functions an image may name.
     */
    public static final int DRAW_FUNCTION_LIMIT = 0x12;
    /**
     * The number of iscript ids an image may name.
     */
    public static final int ISCRIPT_LIMIT = 0x19B;
    /**
     * The mask of the low WORD.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * The column of a {@link #REGIONS} row that holds the classic base.
     */
    private static final int ROW_BASE = 0;
    /**
     * The column of a {@link #REGIONS} row that holds the size.
     */
    private static final int ROW_SIZE = 1;
    /**
     * The column of a {@link #REGIONS} row that holds the DAT file.
     */
    private static final int ROW_FILE = 2;
    /**
     * The column of a {@link #REGIONS} row that holds the column block.
     */
    private static final int ROW_BLOCK = 3;
    /**
     * The column of a {@link #REGIONS} row that holds the kind.
     */
    private static final int ROW_KIND = 4;

    private static final int UNITS = 0;
    private static final int WEAPONS = 1;
    private static final int FLINGY = 2;
    private static final int SPRITES = 3;
    private static final int IMAGES = 4;
    private static final int ORDERS = 5;
    private static final int UPGRADES = 6;
    private static final int TECHDATA = 7;

    private static final int PLAIN = 0;
    private static final int READ_ONLY = 1;
    private static final int VALIDATED_FLINGY_BYTES = 2;
    private static final int VALIDATED_IMAGE_WORDS = 3;
    private static final int VALIDATED_DRAW_FUNCTION_BYTES = 4;
    private static final int VALIDATED_ISCRIPT_DWORD = 5;
    /**
     * The first kind whose writes are validated; every kind from it on keeps validated shadows.
     */
    private static final int FIRST_VALIDATED_KIND = VALIDATED_FLINGY_BYTES;

    /**
     * {classic base, size, DAT file, column block, kind}: the classic DAT arrays the EUD emulation serves.
     */
    private static final int @NotNull [] @NotNull [] REGIONS = {
            {0x00655700, 0x3D, UPGRADES, 10, PLAIN},
            {0x00655740, 0x7A, UPGRADES, 0, PLAIN},
            {0x006557C0, 0x7A, UPGRADES, 3, PLAIN},
            {0x00655840, 0x7A, UPGRADES, 2, PLAIN},
            {0x006558C0, 0x7A, UPGRADES, 6, PLAIN},
            {0x00655940, 0x7A, UPGRADES, 5, PLAIN},
            {0x006559C0, 0x7A, UPGRADES, 1, PLAIN},
            {0x00655A40, 0x7A, UPGRADES, 8, PLAIN},
            {0x00655AC0, 0x7A, UPGRADES, 7, PLAIN},
            {0x00655B3C, 0x3D, UPGRADES, 11, PLAIN},
            {0x00655B80, 0x7A, UPGRADES, 4, PLAIN},
            {0x00655BFC, 0x3D, UPGRADES, 9, PLAIN},
            {0x00656198, 0x58, TECHDATA, 4, PLAIN},
            {0x006561F0, 0x58, TECHDATA, 1, PLAIN},
            {0x00656248, 0x58, TECHDATA, 0, PLAIN},
            {0x006562A0, 0x58, TECHDATA, 7, PLAIN},
            {0x006562F8, 0x58, TECHDATA, 5, PLAIN},
            {0x00656350, 0x2C, TECHDATA, 9, PLAIN},
            {0x00656380, 0x58, TECHDATA, 3, PLAIN},
            {0x006563D8, 0x58, TECHDATA, 2, PLAIN},
            {0x00656430, 0x58, TECHDATA, 6, PLAIN},
            {0x00656488, 0x2C, TECHDATA, 8, PLAIN},
            {0x006564B4, 0x2C, TECHDATA, 10, PLAIN},
            {0x006564E0, 0x82, WEAPONS, 17, PLAIN},
            {0x00656568, 0x104, WEAPONS, 22, PLAIN},
            {0x00656670, 0x82, WEAPONS, 8, PLAIN},
            {0x006566F8, 0x82, WEAPONS, 10, PLAIN},
            {0x00656780, 0x104, WEAPONS, 23, PLAIN},
            {0x00656888, 0x104, WEAPONS, 11, PLAIN},
            {0x00656990, 0x82, WEAPONS, 18, PLAIN},
            {0x00656A18, 0x208, WEAPONS, 4, PLAIN},
            {0x00656C20, 0x82, WEAPONS, 21, PLAIN},
            {0x00656CA8, 0x208, WEAPONS, 1, PLAIN},
            {0x00656EB0, 0x104, WEAPONS, 14, PLAIN},
            {0x00656FB8, 0x82, WEAPONS, 16, PLAIN},
            {0x00657040, 0x82, WEAPONS, 9, PLAIN},
            {0x006570C8, 0x104, WEAPONS, 12, PLAIN},
            {0x006571D0, 0x82, WEAPONS, 6, PLAIN},
            {0x00657258, 0x82, WEAPONS, 7, PLAIN},
            {0x006572E0, 0x104, WEAPONS, 0, PLAIN},
            {0x006573E8, 0x82, WEAPONS, 2, PLAIN},
            {0x00657470, 0x208, WEAPONS, 5, PLAIN},
            {0x00657678, 0x104, WEAPONS, 15, PLAIN},
            {0x00657780, 0x104, WEAPONS, 13, PLAIN},
            {0x00657888, 0x82, WEAPONS, 19, PLAIN},
            {0x00657910, 0x82, WEAPONS, 20, PLAIN},
            {0x00657998, 0x104, WEAPONS, 3, PLAIN},
            {0x0065FC18, 0xE4, UNITS, 20, PLAIN},
            {0x0065FD00, 0x1C8, UNITS, 41, PLAIN},
            {0x0065FEC8, 0xE4, UNITS, 27, PLAIN},
            {0x0065FFB0, 0x1C8, UNITS, 30, PLAIN},
            {0x00660178, 0xE4, UNITS, 21, PLAIN},
            {0x00660260, 0x1C8, UNITS, 51, PLAIN},
            {0x00660428, 0x1C8, UNITS, 42, PLAIN},
            {0x006605F0, 0xE4, UNITS, 5, PLAIN},
            {0x006606D8, 0xE4, UNITS, 52, PLAIN},
            {0x006607C0, 0x1C8, UNITS, 1, PLAIN},
            {0x00660988, 0xE4, UNITS, 48, PLAIN},
            {0x00660A70, 0x1C8, UNITS, 43, PLAIN},
            {0x00660C38, 0x1C8, UNITS, 2, PLAIN},
            {0x00660E00, 0x1C8, UNITS, 7, PLAIN},
            {0x00660FC8, 0xE4, UNITS, 10, PLAIN},
            {0x006610B0, 0x390, UNITS, 4, PLAIN},
            {0x00661440, 0xD4, UNITS, 35, PLAIN},
            {0x00661518, 0x1C8, UNITS, 53, PLAIN},
            {0x006616E0, 0xE4, UNITS, 19, PLAIN},
            {0x006617C8, 0x720, UNITS, 38, PLAIN},
            {0x00661EE8, 0xD4, UNITS, 33, PLAIN},
            {0x00661FC0, 0xD4, UNITS, 29, PLAIN},
            {0x00662098, 0xE4, UNITS, 28, PLAIN},
            {0x00662180, 0xE4, UNITS, 26, PLAIN},
            {0x00662268, 0xE4, UNITS, 13, PLAIN},
            {0x00662350, 0x390, UNITS, 8, PLAIN},
            {0x006626E0, 0x180, UNITS, 37, PLAIN},
            {0x00662860, 0x390, UNITS, 36, PLAIN},
            {0x00662BF0, 0x1C8, UNITS, 31, PLAIN},
            {0x00662DB8, 0xE4, UNITS, 23, PLAIN},
            {0x00662EA0, 0xE4, UNITS, 12, PLAIN},
            {0x00662F88, 0x1C8, UNITS, 39, PLAIN},
            {0x00663150, 0xE4, UNITS, 9, PLAIN},
            {0x00663238, 0xE4, UNITS, 24, PLAIN},
            {0x00663320, 0xE4, UNITS, 15, PLAIN},
            {0x00663408, 0x1C8, UNITS, 49, PLAIN},
            {0x006635D0, 0xE4, UNITS, 25, PLAIN},
            {0x006636B8, 0xE4, UNITS, 17, PLAIN},
            {0x006637A0, 0xE4, UNITS, 44, PLAIN},
            {0x00663888, 0x1C8, UNITS, 40, PLAIN},
            {0x00663A50, 0xE4, UNITS, 16, PLAIN},
            {0x00663B38, 0xD4, UNITS, 32, PLAIN},
            {0x00663C10, 0xD4, UNITS, 34, PLAIN},
            {0x00663CE8, 0xE4, UNITS, 46, PLAIN},
            {0x00663DD0, 0xE4, UNITS, 11, PLAIN},
            {0x00663EB8, 0x1C8, UNITS, 50, PLAIN},
            {0x00664080, 0x390, UNITS, 22, PLAIN},
            {0x00664410, 0xE4, UNITS, 47, PLAIN},
            {0x006644F8, 0xE4, UNITS, 0, VALIDATED_FLINGY_BYTES},
            {0x006645E0, 0xE4, UNITS, 18, PLAIN},
            {0x006646C8, 0xE4, UNITS, 45, PLAIN},
            {0x006647B0, 0xE4, UNITS, 6, PLAIN},
            {0x00664898, 0xE4, UNITS, 14, PLAIN},
            {0x00664980, 0xC0, UNITS, 3, PLAIN},
            {0x00664A40, 0xBD, ORDERS, 4, PLAIN},
            {0x00664B00, 0xBD, ORDERS, 1, PLAIN},
            {0x00664BC0, 0xBD, ORDERS, 12, PLAIN},
            {0x00664C80, 0xBD, ORDERS, 11, PLAIN},
            {0x00664D40, 0xBD, ORDERS, 15, PLAIN},
            {0x00664E00, 0xBD, ORDERS, 14, PLAIN},
            {0x00664EC0, 0x17A, ORDERS, 16, PLAIN},
            {0x00665040, 0xBD, ORDERS, 6, PLAIN},
            {0x00665100, 0xBD, ORDERS, 7, PLAIN},
            {0x006651C0, 0xBD, ORDERS, 9, PLAIN},
            {0x00665280, 0x17A, ORDERS, 0, PLAIN},
            {0x00665400, 0xBD, ORDERS, 18, PLAIN},
            {0x006654C0, 0xBD, ORDERS, 10, PLAIN},
            {0x00665580, 0x17A, ORDERS, 17, PLAIN},
            {0x00665700, 0xBD, ORDERS, 8, PLAIN},
            {0x006657C0, 0xBD, ORDERS, 5, PLAIN},
            {0x00665880, 0xBD, ORDERS, 13, PLAIN},
            {0x00665940, 0xBD, ORDERS, 2, PLAIN},
            {0x00665A00, 0xBD, ORDERS, 3, PLAIN},
            {0x00665AC0, 0x183, SPRITES, 4, READ_ONLY},
            {0x00665C48, 0x205, SPRITES, 3, PLAIN},
            {0x00665E50, 0x183, SPRITES, 1, READ_ONLY},
            {0x00665FD8, 0x183, SPRITES, 5, READ_ONLY},
            {0x00666160, 0x40A, SPRITES, 0, VALIDATED_IMAGE_WORDS},
            {0x00666570, 0x205, SPRITES, 2, PLAIN},
            {0x00667718, 0x3E7, IMAGES, 4, PLAIN},
            {0x00669A40, 0x3E7, IMAGES, 6, PLAIN},
            {0x00669E28, 0x3E7, IMAGES, 5, VALIDATED_DRAW_FUNCTION_BYTES},
            {0x0066C150, 0x3E7, IMAGES, 2, PLAIN},
            {0x0066D4D8, 0x3E7, IMAGES, 3, PLAIN},
            {0x0066E860, 0x3E7, IMAGES, 1, PLAIN},
            {0x0066EC48, 0xF9C, IMAGES, 7, VALIDATED_ISCRIPT_DWORD},
            {0x006C9858, 0xD1, FLINGY, 6, PLAIN},
            {0x006C9930, 0x344, FLINGY, 3, PLAIN},
            {0x006C9C78, 0x1A2, FLINGY, 2, PLAIN},
            {0x006C9E20, 0xD1, FLINGY, 4, PLAIN},
            {0x006C9EF8, 0x344, FLINGY, 1, PLAIN},
            {0x006CA240, 0xD1, FLINGY, 5, PLAIN},
            {0x006CA318, 0x1A2, FLINGY, 0, PLAIN}
    };


    /**
     * Creates {@link ClassicRegion} instances for the DAT regions over a game's own tables ({@link GameData#createGameCopy()}).
     *
     * @param gameData the game's DAT tables
     * @return the regions
     * @throws IllegalStateException if a table's block does not have the classic array's size
     */
    public static @NotNull List<ClassicRegion> create(@NotNull GameData gameData) {
        DatTable[] tables = {gameData.getUnits(), gameData.getWeapons(), gameData.getFlingy(), gameData.getSprites(),
                             gameData.getImages(), gameData.getOrders(), gameData.getUpgrades(), gameData.getTechdata()};
        List<ClassicRegion> regions = new ArrayList<>(REGIONS.length);

        for (int[] row : REGIONS) {
            DatTable table = tables[row[ROW_FILE]];

            if (table.getBlockSize(row[ROW_BLOCK]) != row[ROW_SIZE]) {
                throw Exceptions.newIllegalStateException("The DAT block {0} of file {1} has {2} bytes, the classic array {3} has {4}.",
                                                          row[ROW_BLOCK],
                                                          row[ROW_FILE],
                                                          table.getBlockSize(row[ROW_BLOCK]),
                                                          String.format("0x%08X", row[ROW_BASE]),
                                                          row[ROW_SIZE]);
            }

            regions.add(new ClassicDatRegion(row[ROW_BASE], row[ROW_SIZE], table, row[ROW_BLOCK], row[ROW_KIND]));
        }

        return regions;
    }

    private static boolean areBytesBelow(int value, int limit) {
        for (int index = 0; index < Integer.BYTES; index++) {
            if ((value >>> index * Byte.SIZE & BYTE_MASK) >= limit) {
                return false;
            }
        }

        return true;
    }


    private final int base;
    private final int size;
    private final @NotNull DatTable table;
    private final int block;
    private final int kind;

    private final int @NotNull [] shadowValues;
    private final boolean @NotNull [] shadowSet;


    private ClassicDatRegion(int base, int size, @NotNull DatTable table, int block, int kind) {
        this.base  = base;
        this.size  = size;
        this.table = table;
        this.block = block;
        this.kind  = kind;

        int dwords = kind >= FIRST_VALIDATED_KIND ? (size + Integer.BYTES - 1) / Integer.BYTES : 0;

        this.shadowValues = new int[dwords];
        this.shadowSet    = new boolean[dwords];
    }


    /**
     * @return the classic base address
     */
    @Override
    public int getBase() {
        return this.base;
    }

    /**
     * @return the size in bytes
     */
    @Override
    public int getSize() {
        return this.size;
    }

    /**
     * Tells whether the address space accepts writes without calling the region or changing its state.
     *
     * @return {@code true} if writes are ignored, {@code false} otherwise
     */
    @Override
    public boolean isWriteIgnored() {
        return this.kind == READ_ONLY;
    }


    /**
     * Reads the unsigned byte at an offset.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @return the unsigned byte at the offset
     */
    @Override
    public int readByte(int offset) {
        int dword = offset / Integer.BYTES;

        if (this.kind >= FIRST_VALIDATED_KIND && this.shadowSet[dword]) {
            return this.shadowValues[dword] >>> (offset % Integer.BYTES) * Byte.SIZE & BYTE_MASK;
        }

        return this.table.readBlockByte(this.block, offset);
    }

    /**
     * Stores one byte, changing the domain state that holds it.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @param value  the byte value in the low eight bits
     */
    @Override
    public void writeByte(int offset, int value) {
        if (this.kind == READ_ONLY) {
            return;
        }

        if (this.kind == PLAIN) {
            this.table.writeBlockByte(this.block, offset, value);

            return;
        }

        int dword = offset / Integer.BYTES;
        int shift = (offset % Integer.BYTES) * Byte.SIZE;

        this.storeValidated(dword, LittleEndianUtils.replaceByte(this.getHostOrShadow(dword), shift, value));
    }

    /**
     * Stores a little-endian DWORD; regions whose domain values are DWORDs override it to store them whole.
     *
     * @param offset the byte offset of the DWORD, with four bytes inside the region
     * @param value  the DWORD
     */
    @Override
    public void writeDword(int offset, int value) {
        if (this.kind < FIRST_VALIDATED_KIND || (offset & DWORD_OFFSET_MASK) != 0) {
            ClassicRegion.super.writeDword(offset, value);

            return;
        }

        this.storeValidated(offset / Integer.BYTES, value);
    }

    /**
     * Returns the DWORD a validated write starts from.
     *
     * @param dword the DWORD index
     * @return the shadow while flagged, otherwise the host bytes (bytes past the region read {@code 0})
     */
    private int getHostOrShadow(int dword) {
        if (this.shadowSet[dword]) {
            return this.shadowValues[dword];
        }

        int value = 0;

        for (int index = 0; index < Integer.BYTES && dword * Integer.BYTES + index < this.size; index++) {
            value |= this.table.readBlockByte(this.block, dword * Integer.BYTES + index) << index * Byte.SIZE;
        }

        return value;
    }

    private void storeValidated(int dword, int value) {
        this.shadowValues[dword] = value;
        this.shadowSet[dword]    = true;

        if (!this.isValid(value)) {
            return;
        }

        this.shadowSet[dword] = false;

        for (int index = 0; index < Integer.BYTES && dword * Integer.BYTES + index < this.size; index++) {
            this.table.writeBlockByte(this.block, dword * Integer.BYTES + index, value >>> index * Byte.SIZE);
        }
    }

    private boolean isValid(int value) {
        return switch (this.kind) {
            case VALIDATED_FLINGY_BYTES -> areBytesBelow(value, FlingyDat.FLINGY_COUNT);
            case VALIDATED_DRAW_FUNCTION_BYTES -> areBytesBelow(value, DRAW_FUNCTION_LIMIT);
            case VALIDATED_IMAGE_WORDS -> (value & WORD_MASK) < ImagesDat.IMAGE_COUNT && (value >>> Short.SIZE) < ImagesDat.IMAGE_COUNT;
            default -> Integer.compareUnsigned(value, ISCRIPT_LIMIT) < 0;
        };
    }
}
