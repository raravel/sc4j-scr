package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.map.MapStringSection;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.ClassicStorageRegion;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Creates classic pointer regions, their storage and the optional map string, count-grid and script regions.
 * Unbound buffers read zero and ignore writes; unsupported host buffers reject access. Map-dependent regions follow
 * the string section at aligned addresses. Available game data supplies the three converted wireframe buffers.
 */
public final class ClassicDynamicRegionsUtils {
    /**
     * The classic address of the map string section.
     */
    public static final int STRING_SECTION_BASE = NativeRegionLayout.MAP_REGION_BASE;

    private static final int TYPE_18_SIZE = 0x15260;

    private static final int TYPE_19_SIZE = 0xC758;

    /**
     * Pointer regions with map-independent values: {pointer address, pointee address} rows.
     */
    private static final int @NotNull [] FIXED_POINTERS = {
            0x005993C4, 0x00AD1080, 0x0059CB5C, 0x008A4EE0, 0x00628444, 0x00B31080,
            0x00628454, 0x00A67880, 0x00628458, 0x00E31080, 0x00628494, 0x00AB1080, 0x0068AC8C, 0x00A141B0,
            0x0068C140, 0x00776620, 0x0068C148, 0x006DE000, 0x0068C1F0, 0x190F4930, 0x0068C224, 0x0096D920,
            0x0068C234, 0x0082F280, 0x006D1238, 0x19184660, 0x006D1260, 0x00AF1080, 0x006D5A30, 0x19184660,
            0x006D5E14, 0x00E77080, 0x006D5EC8, 0x00E63080,
            // The buffers built from game data (the wireframe copies).
            0x0068C204, 0x190913E0, 0x0068C1FC, 0x190DC2C0, 0x0068C1F4, 0x190E8110
    };

    /**
     * The pointer to the map string section.
     */
    private static final int STRING_POINTER = 0x005993D4;

    /**
     * The zeroed 8-byte prefix before the map string section, separate from its contents.
     */
    private static final int STRING_PREFIX_SIZE = 8;

    /**
     * The type {@code 0x2A} storage of {@code 0x30} bytes: the classic address and size of a {@code 0x12DA} buffer and screen sizes.
     */
    private static final int TYPE_2A_BASE = 0x00E77080;

    private static final int @NotNull [] TYPE_2A_DWORDS = {
            0, 0, 0x00E770B0, 0x12DA, 0xF80, 0, 0x280, 0x1E0, 0, 0x125, 0x280, 0x1E0
    };

    private static final int TYPE_03_POINTER = 0x006D5CD8;

    private static final int TYPE_18_POINTER = 0x0068C104;

    private static final int TYPE_19_POINTER = 0x0068C108;

    /**
     * The unbound pointee buffers: {base, size} rows.
     */
    private static final int @NotNull [] UNBOUND = {
            0x006DE980, 0x109A0, 0x006EF7E0, 0x109A0, 0x00700640, 0x109A0, 0x007114A0, 0x109A0, 0x00722300, 0x109A0,
            0x00733160, 0x109A0, 0x00743FC0, 0x109A0, 0x00754E20, 0x109A0, 0x00765C80, 0x109A0, 0x00776BE0, 0x109A0,
            0x00787860, 0x109A0, 0x007984E0, 0x109A0, 0x007A9160, 0x109A0, 0x007B9DE0, 0x109A0, 0x007CAA60, 0x109A0,
            0x007DB6E0, 0x109A0, 0x007EC360, 0x109A0, 0x007FCFE0, 0x109A0, 0x0080DC60, 0x109A0, 0x0081E8E0, 0x109A0,
            0x0082F8C0, 0x109A0, 0x00840580, 0x109A0, 0x00851240, 0x109A0, 0x00861F00, 0x109A0, 0x00872BC0, 0x109A0,
            0x00883880, 0x109A0, 0x00894540, 0x109A0, 0x008A5260, 0x109A0, 0x008B5DC0, 0x109A0, 0x008C6920, 0x109A0,
            0x008D7480, 0x109A0, 0x008E7FE0, 0x109A0, 0x008F8B40, 0x109A0, 0x009096A0, 0x109A0, 0x0091A200, 0x109A0,
            0x0092AD60, 0x109A0, 0x0093B8C0, 0x109A0, 0x0094C420, 0x109A0, 0x0095CF80, 0x109A0, 0x0096DAA0, 0x109A0,
            0x0097E500, 0x109A0, 0x0098EF60, 0x109A0, 0x0099F9C0, 0x109A0, 0x009B0420, 0x109A0, 0x009C0E80, 0x109A0,
            0x009D18E0, 0x109A0, 0x009E2340, 0x109A0, 0x009F2DA0, 0x109A0, 0x00A03800, 0x109A0, 0x00A143D0, 0x109A0,
            0x00A24E80, 0x109A0, 0x00A35930, 0x109A0, 0x00A463E0, 0x109A0, 0x00A56E90, 0x109A0, 0x00A67880, 0x49800,
            0x00AB1080, 0x20000, 0x00AD1080, 0x20000, 0x00AF1080, 0x40000, 0x00B31080, 0x300000, 0x00E31080, 0x32000,
            0x00E63080, 0x14000, 0x00E770B0, 0x12DA, 0x190F7050, 0x109A0, 0x19108D80, 0x109A0, 0x1911AAB0, 0x109A0,
            0x1912C7E0, 0x109A0, 0x1913E510, 0x109A0, 0x19150240, 0x109A0, 0x19161F70, 0x109A0, 0x19173CA0, 0x109A0
    };

    /**
     * The type-{@code 0x2C} node lists of seven fixed pointers: {first node, node size, node count} rows.
     */
    private static final int @NotNull [] NODE_LISTS = {
            0x006DE000, 0x4BE, 10, 0x00776620, 0x2DE, 12, 0x0082F280, 0x31A, 8, 0x008A4EE0, 0x1B6, 13,
            0x0096D920, 0xB8, 11, 0x00A141B0, 0x10E, 6, 0x190F4930, 0x1388, 9
    };

    /**
     * The number of values of one row of {@link #NODE_LISTS}.
     */
    private static final int NODE_LIST_ROW_SIZE = 3;

    /**
     * The row value of {@link #NODE_LISTS} holding the node size.
     */
    private static final int NODE_LIST_SIZE = 1;

    /**
     * The row value of {@link #NODE_LISTS} holding the node count.
     */
    private static final int NODE_LIST_COUNT = 2;

    /**
     * The buffer size for each node but the last.
     */
    private static final int NODE_BUFFER_SIZE = 0x109A0;

    /**
     * The node DWORD holding the next node.
     */
    private static final int NODE_NEXT = 0x00;

    /**
     * The node DWORD holding the node's buffer.
     */
    private static final int NODE_BUFFER = 0x10;

    /**
     * The node DWORD holding the first marker.
     */
    private static final int NODE_FIRST_MARKER = 0x14;

    /**
     * The unaligned node DWORD holding the second marker.
     */
    private static final int NODE_SECOND_MARKER = 0x2A;

    /**
     * The unaligned node DWORD holding the third marker.
     */
    private static final int NODE_THIRD_MARKER = 0x2E;

    private static final int FIRST_MARKER = 0xDEADBEE2;

    private static final int SECOND_MARKER = 0xDEADBEEF;

    private static final int THIRD_MARKER = 0xDEADBEE0;

    /**
     * The regions of flags {@code 0x0006} without a host (reads give {@code 0} and writes are ignored: the bound length is
     * {@code 0}), {base, size} rows (each region covers up to the next region's base).
     */
    private static final int @NotNull [] HOSTLESS = {
            0x0050CDC0, 0x101, 0x00512708, 0x4C, 0x006CEFF8, 0x4B0, 0x006D1200, 0x34, 0x1505E670, 0x400
    };

    /**
     * The wireframe files of the buffers of types {@code 0x13}, {@code 0x14} and {@code 0x15} (the last three fixed pointers).
     */
    private static final @NotNull String @NotNull [] WIREFRAME_FILES = {
            "unit/wirefram/wirefram.grp", "unit/wirefram/grpwire.grp", "unit/wirefram/tranwire.grp"
    };

    private static final int @NotNull [] WIREFRAME_SIZES = {0x4AED5, 0xBE4A, 0xC807};

    private static final int @NotNull [] WIREFRAME_BASES = {0x190913E0, 0x190DC2C0, 0x190E8110};

    /**
     * The size of a GRP header: the frame count, width and height WORDs.
     */
    private static final int GRP_HEADER_SIZE = 6;

    /**
     * The size of a GRP frame entry: x, y, width and height bytes and the data offset DWORD.
     */
    private static final int GRP_FRAME_ENTRY_SIZE = 8;

    /**
     * The offset of the data offset DWORD of a GRP frame entry, after the four position and size bytes.
     */
    private static final int GRP_FRAME_DATA_OFFSET = 4;

    /**
     * The bytes each frame entry grows by when its data offset becomes a QWORD.
     */
    private static final int FRAME_ENTRY_GROWTH = Long.BYTES - Integer.BYTES;

    /**
     * A four-byte region over its own DWORD.
     */
    private static final class StorageDword implements ClassicRegion {
        private final int base;

        private int value;

        StorageDword(int base, int value) {
            this.base  = base;
            this.value = value;
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
            return Integer.BYTES;
        }


        /**
         * Reads the unsigned byte at an offset.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @return the unsigned byte at the offset
         */
        @Override
        public int readByte(int offset) {
            return this.value >>> offset * Byte.SIZE & BYTE_MASK;
        }

        /**
         * Stores one byte, changing the domain state that holds it.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @param value  the byte value in the low eight bits
         */
        @Override
        public void writeByte(int offset, int value) {
            int shift = offset * Byte.SIZE;

            this.value = LittleEndianUtils.replaceByte(this.value, shift, value);
        }
    }

    /**
     * A host region whose buffer is not represented: every access stops explicitly.
     */
    private static final class UnrepresentedHost implements ClassicRegion {
        private final int base;

        private final int size;

        private final @NotNull String host;

        UnrepresentedHost(int base, int size, @NotNull String host) {
            this.base = base;
            this.size = size;
            this.host = host;
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
         * Reads the unsigned byte at an offset.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @return the unsigned byte at the offset
         * @throws UnsupportedOperationException if called, because the content is not represented
         */
        @Override
        public int readByte(int offset) {
            throw this.createUnmappedAccessException(offset);
        }

        /**
         * Stores one byte, changing the domain state that holds it.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @param value  the byte value in the low eight bits
         * @throws UnsupportedOperationException if called, because the content is not represented
         */
        @Override
        public void writeByte(int offset, int value) {
            throw this.createUnmappedAccessException(offset);
        }

        private @NotNull UnsupportedOperationException createUnmappedAccessException(int offset) {
            return Exceptions.newUnsupportedOperationException("EUD address 0x{0} is {1}, a generic host region whose content is not represented.",
                                                               String.format("%08X", this.base + offset), this.host);
        }
    }

    /**
     * An unbound pointee buffer: every read is {@code 0} and every write is ignored.
     */
    private static final class Unbound implements ClassicRegion {
        private final int base;

        private final int size;

        Unbound(int base, int size) {
            this.base = base;
            this.size = size;
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
         * Reads the unsigned byte at an offset.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @return the unsigned byte at the offset
         */
        @Override
        public int readByte(int offset) {
            return 0;
        }

        /**
         * Stores one byte, changing the domain state that holds it.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @param value  the byte value in the low eight bits
         */
        @Override
        public void writeByte(int offset, int value) {
        }

        /**
         * Tells whether the region is served from the emulation's own storage.
         *
         * @return {@code true}: the buffer holds zero and no game state
         */
        @Override
        public boolean isEmulatorStorage() {
            return true;
        }
    }

    /**
     * The map string section region (type {@code 0x02}, generic host copy of the loaded section).
     */
    private static final class StringSection implements ClassicRegion {
        private final @NotNull MapStringSection strings;

        StringSection(@NotNull MapStringSection strings) {
            this.strings = strings;
        }

        /**
         * @return the classic base address
         */
        @Override
        public int getBase() {
            return STRING_SECTION_BASE;
        }

        /**
         * @return the size in bytes
         */
        @Override
        public int getSize() {
            return this.strings.getSize();
        }

        /**
         * Tells whether the region's bytes are plain memory: reading them has no effect, and a DWORD read at any offset
         * and a range read give what the byte reads give.
         *
         * @return {@code true} if the region's bytes are plain memory, {@code false} otherwise; always {@code true} for the
         * string section
         */
        @Override
        public boolean isPlainMemory() {
            return true;
        }

        /**
         * Returns a count that grows with every write that changes the region's bytes, for regions that track their
         * changes so readers can keep what they derived from unchanged bytes ({@link #findChangeSince}).
         *
         * @return the change count of the string section
         */
        @Override
        public long getChangeCount() {
            return this.strings.getChangeCount();
        }


        /**
         * Finds the first byte of a range that may have changed after a change count of {@link #getChangeCount()} was
         * observed; a region may answer a byte before the first change, never one after it.
         *
         * @param offset      the first byte offset, with {@code length} bytes inside the region
         * @param length      the number of bytes, at least {@code 1}
         * @param changeCount a change count observed earlier
         * @return the index in the range of that byte, or {@code -1} only if the range surely holds the bytes it held then
         */
        @Override
        public int findChangeSince(int offset, int length, long changeCount) {
            return this.strings.findChangeSince(offset, length, changeCount);
        }

        /**
         * Reads the unsigned byte at an offset.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @return the unsigned byte at the offset
         */
        @Override
        public int readByte(int offset) {
            return this.strings.readByte(offset);
        }

        /**
         * Reads an aligned little-endian DWORD; regions whose domain values are DWORDs override it to read them whole.
         *
         * @param offset the byte offset of the DWORD, with four bytes inside the region
         * @return the DWORD
         */
        @Override
        public int readDword(int offset) {
            return this.strings.readDword(offset);
        }

        /**
         * Stores a little-endian DWORD; regions whose domain values are DWORDs override it to store them whole.
         *
         * @param offset the byte offset of the DWORD, with four bytes inside the region
         * @param value  the DWORD
         */
        @Override
        public void writeDword(int offset, int value) {
            this.strings.writeDword(offset, value);
        }

        /**
         * Stores one byte, changing the domain state that holds it.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @param value  the byte value in the low eight bits
         */
        @Override
        public void writeByte(int offset, int value) {
            this.strings.writeByte(offset, value);
        }

        /**
         * Copies a range of bytes, as consecutive {@link #readByte} calls do; regions backed by a byte array override it.
         *
         * @param offset            the first byte offset, with {@code length} bytes inside the region
         * @param destination       the receiving array
         * @param destinationOffset the first index written
         * @param length            the number of bytes
         */
        @Override
        public void read(int offset, byte @NotNull [] destination, int destinationOffset, int length) {
            this.strings.read(offset, destination, destinationOffset, length);
        }
    }

    /**
     * Returns the classic address of the first trigger node the allocator places after the map's regions.
     *
     * @param stringSectionSize the size of the map string section, {@code 0} without one
     * @return the classic address of the first trigger node
     */
    public static int getFirstTriggerNodeBase(int stringSectionSize) {
        return NativeRegionLayout.alignAddress(getTypeNineteenBase(stringSectionSize) + TYPE_19_SIZE);
    }

    /**
     * Tells whether a region is the map string section (type {@code 0x02}), the only region the free-list commits accept
     * links in.
     *
     * @param region a region
     * @return {@code true} if it is the map string section, {@code false} otherwise
     */
    public static boolean isStringSection(@Nullable ClassicRegion region) {
        return region instanceof StringSection;
    }

    /**
     * Creates the pointer regions, the unbound pointee buffers and, when present, the map string section region.
     *
     * @param strings the map string section, or {@code null}
     * @return the regions
     */
    public static @NotNull List<ClassicRegion> create(@Nullable MapStringSection strings) {
        return create(strings, null);
    }

    /**
     * Creates the pointer regions, the unbound pointee buffers, the map string section region and, with the game data,
     * the three wireframe buffers, the GRP files with 64-bit frame offsets cut at the descriptor's size
     * ({@link #createWireframeCanvas}).
     *
     * @param strings  the map string section, or {@code null}
     * @param gameData the installed game data, or {@code null} to leave the wireframe copies unrepresented
     * @return the regions
     * @throws UncheckedIOException if a wireframe file cannot be read
     */
    public static @NotNull List<ClassicRegion> create(@Nullable MapStringSection strings, @Nullable GameData gameData) {
        return create(strings, gameData, null);
    }

    /**
     * Creates the regions of {@link #create(MapStringSection, GameData)}, with the pointee of the type {@code 0x03}
     * pointer over the flying-unit count grid when it is supplied: the grid of 171 x 171 bytes, which the region reads
     * and writes.
     *
     * @param strings          the map string section, or {@code null}
     * @param gameData         the installed game data, or {@code null} to leave the wireframe copies unrepresented
     * @param flyingUnitCounts the flying-unit count grid, or {@code null} to leave the pointee unrepresented
     * @return the regions
     * @throws UncheckedIOException if a wireframe file cannot be read
     */
    public static @NotNull List<ClassicRegion> create(@Nullable MapStringSection strings,
                                                      @Nullable GameData gameData,
                                                      byte @Nullable [] flyingUnitCounts) {
        return create(strings, gameData, flyingUnitCounts, null, null);
    }

    /**
     * Creates the regions of {@link #create(MapStringSection, GameData, byte[])}, with the pointees of the type
     * {@code 0x18} and {@code 0x19} pointers over the live computer script buffers when they are supplied (the installed
     * {@code aiscript.bin}/{@code bwscript.bin} followed by zeros up to its size plus {@code 0x6000}, rounded down to 4).
     *
     * @param strings          the map string section, or {@code null}
     * @param gameData         the installed game data, or {@code null}
     * @param flyingUnitCounts the flying-unit count grid, or {@code null}
     * @param mainScripts      the aiscript.bin buffer, or {@code null}
     * @param expansionScripts the bwscript.bin buffer, or {@code null}
     * @return the regions
     * @throws UncheckedIOException if a wireframe file cannot be read
     */
    public static @NotNull List<ClassicRegion> create(@Nullable MapStringSection strings,
                                                      @Nullable GameData gameData,
                                                      byte @Nullable [] flyingUnitCounts,
                                                      byte @Nullable [] mainScripts, byte @Nullable [] expansionScripts) {
        int stringSize = strings == null ? 0 : strings.getSize();
        List<ClassicRegion> regions = new ArrayList<>();

        for (int index = 0; index < FIXED_POINTERS.length; index += NativeRegionLayout.REGION_ROW_SIZE) {
            regions.add(new StorageDword(FIXED_POINTERS[index], FIXED_POINTERS[index + 1]));
        }

        int typeThree = NativeRegionLayout.alignAddress(STRING_SECTION_BASE + stringSize);
        int typeEighteen = NativeRegionLayout.alignAddress(typeThree + UnitSpatialIndex.FLYING_GRID_SIZE);

        regions.add(new StorageDword(STRING_POINTER, STRING_SECTION_BASE));
        regions.add(new StorageDword(TYPE_03_POINTER, typeThree));
        regions.add(new StorageDword(TYPE_18_POINTER, typeEighteen));
        regions.add(new StorageDword(TYPE_19_POINTER, getTypeNineteenBase(stringSize)));

        for (int index = 0; index < UNBOUND.length; index += NativeRegionLayout.REGION_ROW_SIZE) {
            regions.add(new Unbound(UNBOUND[index], UNBOUND[index + 1]));
        }

        for (int index = 0; index < HOSTLESS.length; index += NativeRegionLayout.REGION_ROW_SIZE) {
            regions.add(new Unbound(HOSTLESS[index], HOSTLESS[index + 1]));
        }

        for (int index = 0; index < NODE_LISTS.length; index += NODE_LIST_ROW_SIZE) {
            addNodeList(regions, NODE_LISTS[index], NODE_LISTS[index + NODE_LIST_SIZE], NODE_LISTS[index + NODE_LIST_COUNT]);
        }

        if (strings != null && stringSize > 0) {
            regions.add(new StringSection(strings));
            regions.add(new ClassicStorageRegion(STRING_SECTION_BASE - STRING_PREFIX_SIZE, new byte[STRING_PREFIX_SIZE]));
        }

        ByteBuffer typeTwoA = ByteBuffer.allocate(TYPE_2A_DWORDS.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);

        for (int value : TYPE_2A_DWORDS) {
            typeTwoA.putInt(value);
        }

        regions.add(new ClassicStorageRegion(TYPE_2A_BASE, typeTwoA.array()));

        // Bind the count grid and script buffers only when their sizes match the region descriptors.
        if (flyingUnitCounts != null && flyingUnitCounts.length == UnitSpatialIndex.FLYING_GRID_SIZE) {
            regions.add(new ClassicStorageRegion(typeThree, flyingUnitCounts));
        } else {
            regions.add(new UnrepresentedHost(typeThree, UnitSpatialIndex.FLYING_GRID_SIZE, "the 0x7239-byte buffer of 0x6D5CD8"));
        }

        if (mainScripts != null && mainScripts.length == TYPE_18_SIZE) {
            regions.add(new ClassicStorageRegion(typeEighteen, mainScripts));
        } else {
            regions.add(new UnrepresentedHost(typeEighteen, TYPE_18_SIZE, "the aiscript.bin buffer of 0x68C104"));
        }

        if (expansionScripts != null && expansionScripts.length == TYPE_19_SIZE) {
            regions.add(new ClassicStorageRegion(getTypeNineteenBase(stringSize), expansionScripts));
        } else {
            regions.add(new UnrepresentedHost(getTypeNineteenBase(stringSize), TYPE_19_SIZE, "the bwscript.bin buffer of 0x68C108"));
        }

        if (gameData != null) {
            for (int index = 0; index < WIREFRAME_FILES.length; index++) {
                byte[] copy;

                try {
                    copy = createWireframeCanvas(gameData.readInstalledFile(WIREFRAME_FILES[index]), WIREFRAME_SIZES[index]);
                } catch (IOException exception) {
                    throw Exceptions.newUncheckedIOException(exception, "The EUD wireframe copy needs {0}.", WIREFRAME_FILES[index]);
                }

                regions.add(new ClassicStorageRegion(WIREFRAME_BASES[index], copy));
            }
        }

        return regions;
    }

    /**
     * Creates wireframe bytes from a GRP header, frame entries widened to 12 bytes and their frame data.
     * Each data offset becomes a QWORD adjusted by the table growth. The result is cut or padded to the descriptor size.
     *
     * @param file the installed GRP file
     * @param size the buffer size
     * @return the buffer
     * @throws IOException if the file is not a GRP whose frame table fits
     */
    static byte @NotNull [] createWireframeCanvas(byte @NotNull [] file, int size) throws IOException {
        if (file.length < GRP_HEADER_SIZE) {
            throw Exceptions.newIOException("A wireframe GRP needs its 6-byte header.");
        }

        int frames = LittleEndianUtils.readUnsigned16(file, 0);
        int table = GRP_HEADER_SIZE + frames * GRP_FRAME_ENTRY_SIZE;

        if (table > file.length) {
            throw Exceptions.newIOException("The wireframe GRP frame table exceeds the file.");
        }

        ByteBuffer destination = ByteBuffer.allocate(file.length + frames * FRAME_ENTRY_GROWTH).order(ByteOrder.LITTLE_ENDIAN);

        destination.put(file, 0, GRP_HEADER_SIZE);

        ByteBuffer source = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);

        for (int frame = 0; frame < frames; frame++) {
            int entry = GRP_HEADER_SIZE + frame * GRP_FRAME_ENTRY_SIZE;

            destination.put(file, entry, GRP_FRAME_DATA_OFFSET);
            destination.putLong(Integer.toUnsignedLong(source.getInt(entry + GRP_FRAME_DATA_OFFSET)) + frames * (long) FRAME_ENTRY_GROWTH);
        }

        destination.put(file, table, file.length - table);

        return Arrays.copyOf(destination.array(), size);
    }

    /**
     * Adds a list of zeroed node buffers at aligned addresses, as the emulation's own storage. The first two nodes are adjacent; later nodes
     * follow the buffer of the node two positions earlier. Each node except the last holds its next link, buffer address and three marker
     * DWORDs, including two unaligned markers.
     *
     * @param regions receives the node regions
     * @param first   the first node's classic address, which the list pointer holds
     * @param size    the node size
     * @param count   the node count
     */
    static void addNodeList(@NotNull List<ClassicRegion> regions, int first, int size, int count) {
        byte[][] nodes = new byte[count][];
        int[] bases = new int[count];

        bases[0] = first;
        nodes[0] = new byte[size];

        int end = first + size;

        for (int index = 1; index < count; index++) {
            bases[index] = NativeRegionLayout.alignAddress(end);
            nodes[index] = new byte[size];

            int buffer = NativeRegionLayout.alignAddress(bases[index] + size);
            ByteBuffer previous = ByteBuffer.wrap(nodes[index - 1]).order(ByteOrder.LITTLE_ENDIAN);

            previous.putInt(NODE_NEXT, bases[index]);
            previous.putInt(NODE_BUFFER, buffer);
            previous.putInt(NODE_SECOND_MARKER, SECOND_MARKER);
            previous.putInt(NODE_THIRD_MARKER, THIRD_MARKER);
            previous.putInt(NODE_FIRST_MARKER, FIRST_MARKER);

            end = buffer + NODE_BUFFER_SIZE;
        }

        for (int index = 0; index < count; index++) {
            regions.add(ClassicStorageRegion.createEmulatorStorageRegion(bases[index], nodes[index]));
        }
    }

    private static int getTypeNineteenBase(int stringSize) {
        int typeThree = NativeRegionLayout.alignAddress(STRING_SECTION_BASE + stringSize);
        int typeEighteen = NativeRegionLayout.alignAddress(typeThree + UnitSpatialIndex.FLYING_GRID_SIZE);

        return NativeRegionLayout.alignAddress(typeEighteen + TYPE_18_SIZE);
    }


    private ClassicDynamicRegionsUtils() {
    }
}
