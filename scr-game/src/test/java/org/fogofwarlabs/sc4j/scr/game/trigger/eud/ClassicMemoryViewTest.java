package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

import org.fogofwarlabs.sc4j.scr.game.map.MapStringSection;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerModifiers;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.ClassicStorageRegion;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checks that the read-only memory view hears the DWORD writes of its observed ranges, write-ignored regions included, without changing what the
 * address space stores or reads, and that its byte reads give what the DWORD reads give and stop where no region serves the address.
 */
final class ClassicMemoryViewTest {
    /**
     * The write-ignored GRP pointer table of the emulator storage and its size.
     */
    private static final int GRP_TABLE = 0x51CED0;
    private static final int GRP_TABLE_SIZE = 0xF9C;
    /**
     * The GRP pointer field of an image entry.
     */
    private static final int IMAGE_GRP_FIELD = 0x2C;
    private static final int IMAGE_SLOT = 4999;
    private static final int IMAGE_COUNT = 5000;
    private static final int POINTER = 0x58A364;
    /**
     * A writable emulator storage DWORD that no observer watches.
     */
    private static final int UNOBSERVED_STORAGE = 0x5145BC;
    /**
     * The size of the map string section of the plain memory read.
     */
    private static final int STRING_SECTION_SIZE = 0x1001;

    private static @NotNull ClassicAddressSpace createSpace() {
        ClassicAddressSpace space = new ClassicAddressSpace();

        space.add(ClassicStorageRegion.createImageTableRegion());
        space.addAll(ClassicStorageRegion.createEmulatorStorageRegions());

        return space;
    }

    private static int getDeathsOperand(int address) {
        return (address - ClassicAddressSpace.DEATH_TABLE_ADDRESS) / Integer.BYTES;
    }

    @Test
    void observers_hear_writes_of_their_ranges_and_leave_the_stored_bytes_unchanged() {
        ClassicAddressSpace space = createSpace();
        ClassicMemoryView view = new ClassicMemoryView(space);
        List<int[]> tableWrites = new ArrayList<>();
        List<int[]> imageWrites = new ArrayList<>();
        int tableEntry = GRP_TABLE + 10 * Integer.BYTES;
        int imageField = ClassicStorageRegion.IMAGE_TABLE + IMAGE_SLOT * ClassicStorageRegion.IMAGE_ENTRY_SIZE + IMAGE_GRP_FIELD;

        view.observeWrites(GRP_TABLE, GRP_TABLE_SIZE, (address, value) -> tableWrites.add(new int[]{address, value}));
        view.observeWrites(ClassicStorageRegion.IMAGE_TABLE, IMAGE_COUNT * ClassicStorageRegion.IMAGE_ENTRY_SIZE,
                           (address, value) -> imageWrites.add(new int[]{address, value}));

        space.writeDword(tableEntry, POINTER);
        space.setExtendedDeaths(getDeathsOperand(imageField), 0, TriggerModifiers.SET_TO, POINTER);
        space.setExtendedDeaths(getDeathsOperand(imageField), 0, TriggerModifiers.ADD, 2);
        space.writeDword(UNOBSERVED_STORAGE, 1);

        Assertions.assertEquals(0, space.readDword(tableEntry));
        Assertions.assertEquals(POINTER + 2, space.readDword(imageField));
        Assertions.assertEquals(1, tableWrites.size());
        Assertions.assertArrayEquals(new int[]{tableEntry, POINTER}, tableWrites.getFirst());
        Assertions.assertEquals(2, imageWrites.size());
        Assertions.assertArrayEquals(new int[]{imageField, POINTER}, imageWrites.get(0));
        Assertions.assertArrayEquals(new int[]{imageField, POINTER + 2}, imageWrites.get(1));
    }

    @Test
    void an_unaligned_write_is_heard_as_the_two_aligned_dwords_it_stores() {
        ClassicAddressSpace space = createSpace();
        ClassicMemoryView view = new ClassicMemoryView(space);
        List<int[]> writes = new ArrayList<>();
        int address = ClassicStorageRegion.IMAGE_TABLE + IMAGE_GRP_FIELD;

        view.observeWrites(ClassicStorageRegion.IMAGE_TABLE, ClassicStorageRegion.IMAGE_ENTRY_SIZE,
                           (written, value) -> writes.add(new int[]{written, value}));

        space.writeDword(address + 1, 0x44332211);

        Assertions.assertEquals(2, writes.size());
        Assertions.assertArrayEquals(new int[]{address, 0x33221100}, writes.get(0));
        Assertions.assertArrayEquals(new int[]{address + Integer.BYTES, 0x44}, writes.get(1));
        Assertions.assertEquals(0x44332211, space.readDword(address + 1));
    }

    @Test
    void read_should_return_bytes_until_no_region_serves_the_address() {
        ClassicAddressSpace space = createSpace();
        ClassicMemoryView view = new ClassicMemoryView(space);
        int last = ClassicStorageRegion.IMAGE_TABLE + IMAGE_COUNT * ClassicStorageRegion.IMAGE_ENTRY_SIZE - Integer.BYTES;
        byte[] bytes = new byte[12];

        space.writeDword(last, 0x04030201);

        Assertions.assertEquals(Integer.BYTES - 1, view.read(last + 1, bytes, 2, bytes.length - 2));
        Assertions.assertArrayEquals(new byte[]{0, 0, 2, 3, 4, 0, 0, 0, 0, 0, 0, 0}, bytes);
    }

    @Test
    void read_should_copy_the_bytes_of_plain_memory_as_the_dword_reads_give_them() {
        ClassicAddressSpace space = createSpace();
        ClassicMemoryView view = new ClassicMemoryView(space);
        byte[] payload = new byte[STRING_SECTION_SIZE];

        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) (index * 7);
        }

        space.addAll(ClassicDynamicRegionsUtils.create(new MapStringSection(payload, false)));

        byte[] bytes = new byte[payload.length - 3];

        Assertions.assertEquals(bytes.length, view.read(ClassicDynamicRegionsUtils.STRING_SECTION_BASE + 1, bytes, 0, bytes.length));
        Assertions.assertArrayEquals(Arrays.copyOfRange(payload, 1, payload.length - 2), bytes);

        int dword = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(Integer.BYTES);

        Assertions.assertEquals(space.readDword(ClassicDynamicRegionsUtils.STRING_SECTION_BASE + 1 + Integer.BYTES), dword);
    }
}
