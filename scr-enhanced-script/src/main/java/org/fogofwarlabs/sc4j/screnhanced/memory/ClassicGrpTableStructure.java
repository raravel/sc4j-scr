package org.fogofwarlabs.sc4j.screnhanced.memory;

import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemorySink;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The image GRP table: one writable DWORD per {@code images.dat} entry that points to the image's installed GRP, a block of
 * {@link #INSTALLED_GRP_SIZE} zero bytes per image on the heap. Copying an entry's pointer to another entry or to an image's GRP pointer gives that
 * image the installed graphics of the copied entry's image.
 */
public final class ClassicGrpTableStructure implements ClassicStructure {
    /**
     * The image GRP table.
     */
    public static final int GRP_TABLE = 0x51CED0;
    /**
     * The number of entries of the image GRP table, the {@code images.dat} entries.
     */
    public static final int GRP_TABLE_ENTRIES = 999;
    /**
     * The bytes of the heap block each installed GRP pointer names.
     */
    public static final int INSTALLED_GRP_SIZE = 8;

    /**
     * Adds the table and the blocks its pointers name.
     *
     * @param sink the sink that adds the regions
     * @param heap the heap that places the blocks
     */
    @Override
    public void provide(@NotNull ClassicMemorySink sink, @NotNull ClassicHeap heap) {
        int blocks = heap.allocate(GRP_TABLE_ENTRIES * INSTALLED_GRP_SIZE);
        ByteBuffer table = ByteBuffer.allocate(GRP_TABLE_ENTRIES * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);

        for (int image = 0; image < GRP_TABLE_ENTRIES; image++) {
            table.putInt(blocks + image * INSTALLED_GRP_SIZE);
        }

        sink.add(new ClassicMemoryBlock(GRP_TABLE, table.array()));
        sink.add(new ClassicMemoryBlock(blocks, GRP_TABLE_ENTRIES * INSTALLED_GRP_SIZE));
    }
}
