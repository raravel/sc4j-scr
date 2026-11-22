package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.ClassicStorageRegion;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks that a memory source serves memory only where the SC:R mod serves no game state: it replaces whole emulation storage regions, unbound
 * buffers among them, and covers addresses without a native region; it is refused over game state, part of an emulation storage region, native
 * memory without a region and the map-dependent range; and its bytes enter the state hash.
 */
final class ClassicMemorySinkTest {
    /**
     * A write-protected emulation storage DWORD.
     */
    private static final int PROTECTED_STORAGE = 0x68C238;
    /**
     * The first DWORD of a write-protected emulation storage table wider than a DWORD.
     */
    private static final int STORAGE_TABLE = 0x51F2A8;
    /**
     * An address in the range no native region serves.
     */
    private static final int NATIVE_GAP = 0x0F000000;
    /**
     * An address a native region serves that the test address space leaves without a region.
     */
    private static final int NATIVE_WITHOUT_REGION = 0x6D1200;
    private static final int GAP_SIZE = 0x100;
    /**
     * An unbound buffer followed by addresses without a native region.
     */
    private static final int UNBOUND_BUFFER = 0xE770B0;
    private static final int UNBOUND_SIZE = 0x12DA;
    /**
     * The first node of a node list and its size.
     */
    private static final int FIRST_NODE = 0x0082F280;
    private static final int NODE_SIZE = 0x31A;
    /**
     * The offset of the buffer address in a node.
     */
    private static final int NODE_BUFFER = 0x10;
    private static final int VALUE = 0x12345678;
    private static final int HASH_SEED = 7;

    private static @NotNull ClassicAddressSpace createSpace() {
        ClassicAddressSpace space = new ClassicAddressSpace();

        space.add(ClassicStorageRegion.createImageTableRegion());
        space.addAll(ClassicStorageRegion.createEmulatorStorageRegions());

        return space;
    }

    @Test
    void a_provided_region_replaces_whole_emulation_storage_and_is_writable() {
        ClassicAddressSpace space = createSpace();

        Assertions.assertTrue(space.findRegion(PROTECTED_STORAGE).isEmulatorStorage());

        space.writeDword(PROTECTED_STORAGE, VALUE);

        Assertions.assertEquals(0, space.readDword(PROTECTED_STORAGE));

        new ClassicMemorySink(space).add(new ClassicStorageRegion(PROTECTED_STORAGE, new byte[Integer.BYTES]));
        space.writeDword(PROTECTED_STORAGE, VALUE);

        Assertions.assertEquals(VALUE, space.readDword(PROTECTED_STORAGE));
        Assertions.assertFalse(space.findRegion(PROTECTED_STORAGE).isEmulatorStorage());
    }

    @Test
    void a_provided_region_replaces_an_unbound_buffer_and_grows_past_its_end() {
        ClassicAddressSpace space = new ClassicAddressSpace();

        space.addAll(ClassicDynamicRegionsUtils.create(null));

        Assertions.assertTrue(space.findRegion(UNBOUND_BUFFER).isEmulatorStorage());
        Assertions.assertNull(space.findRegion(UNBOUND_BUFFER + UNBOUND_SIZE));

        new ClassicMemorySink(space).add(new ClassicStorageRegion(UNBOUND_BUFFER, 2 * UNBOUND_SIZE));
        space.writeDword(UNBOUND_BUFFER, VALUE);
        space.writeDword(UNBOUND_BUFFER + UNBOUND_SIZE, VALUE);

        Assertions.assertEquals(VALUE, space.readDword(UNBOUND_BUFFER));
        Assertions.assertEquals(VALUE, space.readDword(UNBOUND_BUFFER + UNBOUND_SIZE));
    }

    @Test
    void a_provided_region_replaces_a_node_and_keeps_what_the_sink_read_from_it() {
        ClassicAddressSpace space = new ClassicAddressSpace();

        space.addAll(ClassicDynamicRegionsUtils.create(null));

        ClassicMemorySink sink = new ClassicMemorySink(space);
        byte[] node = new byte[NODE_SIZE];

        Assertions.assertTrue(space.findRegion(FIRST_NODE).isEmulatorStorage());
        Assertions.assertEquals(NODE_SIZE, sink.read(FIRST_NODE, node, 0, node.length));
        Assertions.assertEquals(0, sink.read(NATIVE_GAP, node, 0, Integer.BYTES));

        int buffer = space.readDword(FIRST_NODE + NODE_BUFFER);

        sink.add(new ClassicStorageRegion(FIRST_NODE, node));
        space.writeDword(FIRST_NODE + Integer.BYTES, VALUE);

        Assertions.assertNotEquals(0, buffer);
        Assertions.assertEquals(buffer, space.readDword(FIRST_NODE + NODE_BUFFER));
        Assertions.assertEquals(VALUE, space.readDword(FIRST_NODE + Integer.BYTES));
    }

    @Test
    void a_provided_region_covers_addresses_without_a_native_region() {
        ClassicAddressSpace space = createSpace();

        new ClassicMemorySink(space).add(new ClassicStorageRegion(NATIVE_GAP, GAP_SIZE));
        space.writeDword(NATIVE_GAP + GAP_SIZE - Integer.BYTES, VALUE);

        Assertions.assertEquals(VALUE, space.readDword(NATIVE_GAP + GAP_SIZE - Integer.BYTES));
    }

    @Test
    void a_provided_region_is_refused_where_the_sc_r_mod_serves_memory() {
        ClassicAddressSpace space = createSpace();
        ClassicMemorySink sink = new ClassicMemorySink(space);

        Assertions.assertThrows(IllegalArgumentException.class,
                                () -> sink.add(new ClassicStorageRegion(ClassicStorageRegion.IMAGE_TABLE, Integer.BYTES)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> sink.add(new ClassicStorageRegion(STORAGE_TABLE, Integer.BYTES)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> sink.add(new ClassicStorageRegion(NATIVE_WITHOUT_REGION, Integer.BYTES)));
        Assertions.assertThrows(IllegalArgumentException.class,
                                () -> sink.add(new ClassicStorageRegion(NativeRegionLayout.MAP_REGION_BASE, Integer.BYTES)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> sink.add(new ClassicStorageRegion(NATIVE_GAP, 0)));
        Assertions.assertTrue(space.findRegion(STORAGE_TABLE).isEmulatorStorage());
    }

    @Test
    void provided_bytes_enter_the_state_hash_and_no_provided_region_leaves_it() {
        ClassicAddressSpace space = createSpace();

        Assertions.assertEquals(HASH_SEED, space.computeProvidedStateHash(HASH_SEED));

        new ClassicMemorySink(space).add(new ClassicStorageRegion(NATIVE_GAP, GAP_SIZE));

        int before = space.computeProvidedStateHash(HASH_SEED);

        space.writeDword(NATIVE_GAP, VALUE);

        Assertions.assertNotEquals(before, space.computeProvidedStateHash(HASH_SEED));
        Assertions.assertEquals(space.computeProvidedStateHash(HASH_SEED), space.computeProvidedStateHash(HASH_SEED));
    }
}
