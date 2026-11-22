package org.fogofwarlabs.sc4j.scr.game.trigger.eud.region;

import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicRegion;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Classic image, bullet, order and auxiliary regions backed by local byte storage. Table regions start at zero;
 * regions created over host buffers retain those buffers.
 */
public final class ClassicStorageRegion implements ClassicRegion {
    /**
     * The classic image table.
     */
    public static final int IMAGE_TABLE = 0x52F568;
    /**
     * The size of a classic image entry.
     */
    public static final int IMAGE_ENTRY_SIZE = 0x40;
    /**
     * The classic bullet table.
     */
    public static final int BULLET_TABLE = 0x64B2E8;
    /**
     * The size of a classic bullet entry.
     */
    public static final int BULLET_ENTRY_SIZE = 0x70;
    /**
     * The classic 20-byte table of region type {@code 0x3F}, the order entries.
     */
    public static final int TYPE_3F_TABLE = 0x6416A0;
    /**
     * The size of an entry of the 20-byte table, a classic order entry.
     */
    public static final int TYPE_3F_ENTRY_SIZE = 0x14;

    /**
     * The number of entries of the classic image table.
     */
    private static final int IMAGE_ENTRY_COUNT = 5000;
    /**
     * The number of entries of the classic bullet table.
     */
    private static final int BULLET_ENTRY_COUNT = 100;
    /**
     * The number of entries of the 20-byte table.
     */
    private static final int TYPE_3F_ENTRY_COUNT = 2000;
    /**
     * The number of values per emulator storage record: the base and the size.
     */
    private static final int STORAGE_RECORD_LENGTH = 2;

    /**
     * The storage region bases whose writes are ignored.
     */
    private static final int @NotNull [] WRITE_PROTECTED = {0x51F2A8, 0x52E5C8, 0x51CED0, 0x666778, 0x667B00, 0x668AA0, 0x66A210, 0x66B1B0, 0x66C538,
                                                            0x66D8C0, 0x68C238, 0x6CEFF0, 0x6D0F24};

    /**
     * The terrain contour tables of the contour builder (read only while a map loads, so a write during a game changes
     * nothing it uses): {base, host bytes}, the bytes that hold the constants of {@link TerrainContours}.
     */
    private static final Object @NotNull [] @NotNull [] TERRAIN_CONTOURS = {
            {0x515AF8, "ff000100020001000300010002000100"}, // Initial directions
            {0x515B08, "ff010101010101010100010001000100"}, // Initial turns
            {0x515B18, "ffffffff0800ffff08000800ffff08000000ffff0800000007000800ffff0700"}, // Start positions
            {0x515B38, "0800ffff08000800ffff0800ffffffff0700ffff0800070000000800ffff0000"}, // End positions
            {0x515B58, "00fc01000004ffff"}, // Steps
            {0x515B60, "00ff01000001ff00"}, // Horizontal and vertical steps
            {0x515B68, "01ff0101ff01ffff"} // Horizontal and vertical corners
    };

    /**
     * The bases of the 4-byte self regions of the emulation's container objects.
     */
    private static final int @NotNull [] CONTAINER_SELVES = {
            0xA141A0, 0xA67830, 0xA67840, 0xA67850, 0xA67860, 0xA67870, 0x190F4920, 0x19184640, 0x19184650
    };

    /**
     * {base, size} of the regions served from the emulation's own storage.
     */
    private static final int @NotNull [] EMULATOR_STORAGE = {
            0x5145BC, 0x4, 0x5152FC, 0x4, 0x515384, 0x4, 0x515D64, 0x4, 0x515E34, 0x4, 0x515E9C, 0x4,
            0x515F54, 0x4, 0x5160D4, 0x4, 0x516164, 0x4, 0x51626C, 0x4, 0x51639C, 0x4, 0x51642C, 0x4,
            0x5164E4, 0x4, 0x51659C, 0x4, 0x51662C, 0x4, 0x5167AC, 0x4, 0x516814, 0x4, 0x5168CC, 0x4,
            0x516984, 0x4, 0x516ADC, 0x4, 0x516B44, 0x4, 0x516BAC, 0x4, 0x516D04, 0x4, 0x516DBC, 0x4,
            0x516E74, 0x4, 0x516FA4, 0x4, 0x517034, 0x4, 0x5170C4, 0x4, 0x517154, 0x4, 0x5171E4, 0x4,
            0x5172EC, 0x4, 0x517354, 0x4, 0x51763C, 0x4, 0x5176A4, 0x4, 0x5177D4, 0x4, 0x5178DC, 0x4,
            0x517994, 0x4, 0x517A4C, 0x4, 0x517AB4, 0x4, 0x517B6C, 0x4, 0x517D14, 0x4, 0x517DA4, 0x4,
            0x518144, 0x4, 0x51842C, 0x4, 0x51850C, 0x4, 0x51863C, 0x4, 0x5186CC, 0x4, 0x51F2A8, 0x4E0C,
            0x52E4C0, 0x4, 0x52E5C8, 0xF9C, 0x51CED0, 0xF9C,
            // Seven images.dat columns (GRP, shield, attack, damage, special, landing dust and lift-off overlays) that the
            // emulation points at its own storage, not at the game's images.dat: always 0.
            0x668AA0, 0xF9C, 0x66C538, 0xF9C, 0x66B1B0, 0xF9C, 0x66A210, 0xF9C, 0x667B00, 0xF9C, 0x666778, 0xF9C, 0x66D8C0, 0xF9C, 0x640B5C, 0x4,
            0x64169C, 0x4, 0x6556E4, 0x4, 0x6557BC, 0x4,
            0x65583C, 0x4, 0x6559BC, 0x4, 0x655B7C, 0x4, 0x65637C, 0x4, 0x6566F4, 0x4, 0x65677C, 0x4,
            0x656884, 0x4, 0x65698C, 0x4, 0x656A14, 0x4, 0x656CA4, 0x4, 0x656FB4, 0x4, 0x65703C, 0x4,
            0x6570C4, 0x4, 0x6571CC, 0x4, 0x657254, 0x4, 0x6572DC, 0x4, 0x6573E4, 0x4, 0x65746C, 0x4,
            0x65790C, 0x4, 0x6610AC, 0x4, 0x661514, 0x4, 0x6617C4, 0x4, 0x661FBC, 0x4, 0x662094, 0x4,
            0x66217C, 0x4, 0x662264, 0x4, 0x662E9C, 0x4, 0x662F84, 0x4, 0x663234, 0x4, 0x66331C, 0x4,
            0x663404, 0x4, 0x6636B4, 0x4, 0x66379C, 0x4, 0x663884, 0x4, 0x663C0C, 0x4, 0x663CE4, 0x4,
            0x663DCC, 0x4, 0x6644F4, 0x4, 0x6645DC, 0x4, 0x6646C4, 0x4, 0x6647AC, 0x4, 0x664894, 0x4,
            0x66497C, 0x4, 0x66FD0C, 0x4, 0x68C238, 0x4, 0x6CEFF0, 0x8, 0x6D0F24, 0x4, 0x6D1234, 0x4,
            // Zero-initialized storage that only the emulation's initializer refers to.
            0x5193A0, 0xAB0, 0x597238, 0x4, 0x68C1E0, 0x4, 0x6D5E1C, 0x4, 0x19046134, 0x4, 0x190913D8, 0x4
    };


    /**
     * Creates a {@link ClassicStorageRegion} instance over the retained host buffer.
     *
     * @param base the classic base
     * @param host the host bytes, retained (writes change them)
     * @return the created {@link ClassicStorageRegion} instance
     */
    public static @NotNull ClassicStorageRegion createHostRegion(int base, byte @NotNull [] host) {
        return new ClassicStorageRegion(base, host, false);
    }

    /**
     * Creates {@link ClassicRegion} instances for the seven terrain contour table regions with their stored bytes.
     *
     * @return the regions
     */
    public static @NotNull List<ClassicRegion> createTerrainContourRegions() {
        List<ClassicRegion> regions = new ArrayList<>();

        for (Object[] table : TERRAIN_CONTOURS) {
            regions.add(new ClassicStorageRegion((Integer) table[0], HexFormat.of().parseHex((String) table[1])));
        }

        return regions;
    }

    /**
     * Creates a {@link ClassicStorageRegion} instance for the image table region.
     *
     * @return the created {@link ClassicStorageRegion} instance
     */
    public static @NotNull ClassicStorageRegion createImageTableRegion() {
        return new ClassicStorageRegion(IMAGE_TABLE, IMAGE_ENTRY_COUNT * IMAGE_ENTRY_SIZE);
    }

    /**
     * Creates a {@link ClassicStorageRegion} instance for the bullet table region.
     *
     * @return the created {@link ClassicStorageRegion} instance
     */
    public static @NotNull ClassicStorageRegion createBulletTableRegion() {
        return new ClassicStorageRegion(BULLET_TABLE, BULLET_ENTRY_COUNT * BULLET_ENTRY_SIZE);
    }

    /**
     * Creates a {@link ClassicStorageRegion} instance for the 20-byte table region of type {@code 0x3F}.
     *
     * @return the created {@link ClassicStorageRegion} instance
     */
    public static @NotNull ClassicStorageRegion createOrderTableRegion() {
        return new ClassicStorageRegion(TYPE_3F_TABLE, TYPE_3F_ENTRY_COUNT * TYPE_3F_ENTRY_SIZE);
    }

    /**
     * Creates {@link ClassicRegion} instances for the regions backed by the emulation's own zero-initialized storage.
     *
     * @return the storage regions
     */
    public static @NotNull List<ClassicRegion> createEmulatorStorageRegions() {
        List<ClassicRegion> regions = new ArrayList<>();

        for (int index = 0; index < EMULATOR_STORAGE.length; index += STORAGE_RECORD_LENGTH) {
            int base = EMULATOR_STORAGE[index];
            boolean writeIgnored = Arrays.stream(WRITE_PROTECTED).anyMatch(protectedBase -> protectedBase == base);

            regions.add(new ClassicStorageRegion(base, new byte[EMULATOR_STORAGE[index + 1]], writeIgnored, true));
        }

        return regions;
    }

    /**
     * Creates a {@link ClassicStorageRegion} instance of the emulation's own storage ({@link #isEmulatorStorage()}) over the given storage.
     *
     * @param base    the classic base
     * @param storage the bytes the region serves, retained (writes change them)
     * @return the created {@link ClassicStorageRegion} instance
     */
    public static @NotNull ClassicStorageRegion createEmulatorStorageRegion(int base, byte @NotNull [] storage) {
        return new ClassicStorageRegion(base, storage, false, true);
    }

    /**
     * Creates {@link ClassicRegion} instances for the 4-byte self regions of the emulation's container objects (element-pointer arrays and entry
     * tables): the base is a dummy allocation and the host the object's own zeroed QWORD; nothing else writes it.
     *
     * @return the self regions, reading {@code 0} until written
     */
    public static @NotNull List<ClassicRegion> createContainerSelfRegions() {
        List<ClassicRegion> regions = new ArrayList<>();

        for (int base : CONTAINER_SELVES) {
            regions.add(new ClassicStorageRegion(base, new byte[Integer.BYTES], false));
        }

        return regions;
    }


    private final int base;
    private final byte @NotNull [] storage;
    private final boolean writeIgnored;
    private final boolean emulatorStorage;


    /**
     * Creates a {@link ClassicStorageRegion} instance over new zeroed storage.
     *
     * @param base the classic base
     * @param size the size in bytes
     */
    public ClassicStorageRegion(int base, int size) {
        this(base, new byte[size], false);
    }

    /**
     * Creates a {@link ClassicStorageRegion} instance over the given storage.
     *
     * @param base    the classic base
     * @param storage the bytes the region serves, retained (writes change them)
     */
    public ClassicStorageRegion(int base, byte @NotNull [] storage) {
        this(base, storage, false);
    }

    /**
     * Creates a {@link ClassicStorageRegion} instance over the given storage, write-ignored or not.
     *
     * @param base         the classic base
     * @param storage      the bytes the region serves, retained
     * @param writeIgnored whether writes to the region are ignored
     */
    public ClassicStorageRegion(int base, byte @NotNull [] storage, boolean writeIgnored) {
        this(base, storage, writeIgnored, false);
    }

    private ClassicStorageRegion(int base, byte @NotNull [] storage, boolean writeIgnored, boolean emulatorStorage) {
        this.base            = base;
        this.storage         = storage;
        this.writeIgnored    = writeIgnored;
        this.emulatorStorage = emulatorStorage;
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
        return this.storage.length;
    }

    /**
     * Tells whether the address space accepts writes without calling the region or changing its state.
     *
     * @return {@code true} if writes are ignored, {@code false} otherwise
     */
    @Override
    public boolean isWriteIgnored() {
        return this.writeIgnored;
    }

    /**
     * Tells whether the region is served from the emulation's own storage ({@link #createEmulatorStorageRegions()}).
     *
     * @return {@code true} if the region is emulation storage, {@code false} otherwise
     */
    @Override
    public boolean isEmulatorStorage() {
        return this.emulatorStorage;
    }


    /**
     * Reads the unsigned byte at an offset.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @return the unsigned byte at the offset
     */
    @Override
    public int readByte(int offset) {
        return this.storage[offset] & BYTE_MASK;
    }

    /**
     * Stores one byte, changing the domain state that holds it.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @param value  the byte value in the low eight bits
     */
    @Override
    public void writeByte(int offset, int value) {
        this.storage[offset] = (byte) value;
    }
}
