package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.ClassicStorageRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds classic archive regions from embedded records and an optional map MPQ.
 * Each archive appends its header, block table, hash table and list node in allocation order. Map parsing searches for
 * an MPQ header, decrypts its tables, adjusts block offsets and stores the path, sector size and file size in the object.
 * The list head and node links have separate storage; archive object bytes are shared with their regions.
 */
public final class ClassicStormArchives {
    /**
     * The mask of the low BYTE.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * The classic address of the list head.
     */
    public static final int HEAD_BASE = 0x1505ADF8;

    private static final int CONSTANT_BASE = 0x4FE544;

    private static final int CONSTANT_VALUE = 0x15021A00;

    private static final int HEAD_SIZE = 12;

    /**
     * The head DWORD holding the last node's link.
     */
    private static final int HEAD_LAST_LINK = 1;

    /**
     * The head DWORD holding the first node's link.
     */
    private static final int HEAD_FIRST_LINK = 2;

    private static final int NODE_SIZE = 0x144;

    private static final int OBJECT_SIZE = 0x188;

    private static final int LINKS_SIZE = 8;

    private static final int OBJECT_SECTOR_SIZE = 0x118;

    private static final int OBJECT_ARCHIVE_OFFSET = 0x124;

    private static final int OBJECT_HEADER_ADDRESS = 0x128;

    private static final int OBJECT_BLOCK_ADDRESS = 0x12C;

    private static final int OBJECT_HASH_ADDRESS = 0x130;

    private static final int OBJECT_FILE_SIZE = 0x134;

    private static final int OBJECT_HEADER = 0x13C;

    private static final int PATH_LIMIT = 0x103;

    private static final int TABLE_ENTRY_SIZE = 16;

    /**
     * The number of archives whose records the executable embeds.
     */
    private static final int EMBEDDED_ARCHIVES = 4;

    /**
     * The divisor that turns a chunk tag into its part.
     */
    private static final int TAG_PART_MODULUS = 10;

    /**
     * The chunk part of an MPQ header.
     */
    private static final int PART_HEADER = 1;

    /**
     * The chunk part of a block table.
     */
    private static final int PART_BLOCK_TABLE = 2;

    /**
     * The chunk part of a hash table.
     */
    private static final int PART_HASH_TABLE = 3;

    private static final int HEADER_SIZE = 0x20;

    private static final int HEADER_STEP = 0x200;

    private static final int MPQ_MAGIC = 0x1A51504D;

    /**
     * The MPQ header field holding the header size DWORD.
     */
    private static final int MPQ_HEADER_SIZE_FIELD = 0x04;

    /**
     * The MPQ header field holding the sector size shift WORD.
     */
    private static final int MPQ_SECTOR_SHIFT_FIELD = 0x0E;

    /**
     * The MPQ header field holding the hash table offset DWORD.
     */
    private static final int MPQ_HASH_TABLE_FIELD = 0x10;

    /**
     * The MPQ header field holding the block table offset DWORD.
     */
    private static final int MPQ_BLOCK_TABLE_FIELD = 0x14;

    /**
     * The MPQ header field holding the hash table entry count DWORD.
     */
    private static final int MPQ_HASH_COUNT_FIELD = 0x18;

    /**
     * The MPQ header field holding the block table entry count DWORD.
     */
    private static final int MPQ_BLOCK_COUNT_FIELD = 0x1C;

    private static final int HASH_COUNT_MASK = 0x0FFFFFFF;

    /**
     * The list head's twelve bytes of storage.
     */
    private final class Head implements ClassicRegion {
        /**
         * @return the classic base address
         */
        @Override
        public int getBase() {
            return HEAD_BASE;
        }

        /**
         * @return the size in bytes
         */
        @Override
        public int getSize() {
            return HEAD_SIZE;
        }


        /**
         * Reads the unsigned byte at an offset.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @return the unsigned byte at the offset
         */
        @Override
        public int readByte(int offset) {
            return ClassicStormArchives.this.head[offset / Integer.BYTES] >>> (offset % Integer.BYTES) * Byte.SIZE & BYTE_MASK;
        }

        /**
         * Stores one byte, changing the domain state that holds it.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @param value  the byte value in the low eight bits
         */
        @Override
        public void writeByte(int offset, int value) {
            int[] head = ClassicStormArchives.this.head;
            int shift = (offset % Integer.BYTES) * Byte.SIZE;

            head[offset / Integer.BYTES] = LittleEndianUtils.replaceByte(head[offset / Integer.BYTES], shift, value);
        }
    }

    /**
     * An archive node: the links are fields of the region, the bytes from {@code +8} are the object's.
     */
    private static final class Node implements ClassicRegion {
        private final int base;
        private final byte @NotNull [] object;

        private int previous;
        private int next;

        Node(int base, byte @NotNull [] object) {
            this.base   = base;
            this.object = object;

            this.previous = 0;
            this.next     = 0;
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
            return NODE_SIZE;
        }


        /**
         * Reads the unsigned byte at an offset.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @return the unsigned byte at the offset
         */
        @Override
        public int readByte(int offset) {
            if (offset < LINKS_SIZE) {
                return (offset < Integer.BYTES ? this.previous : this.next) >>> (offset % Integer.BYTES) * Byte.SIZE & BYTE_MASK;
            }

            return this.object[offset - LINKS_SIZE] & BYTE_MASK;
        }

        /**
         * Stores one byte, changing the domain state that holds it.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @param value  the byte value in the low eight bits
         */
        @Override
        public void writeByte(int offset, int value) {
            if (offset < LINKS_SIZE) {
                int shift = (offset % Integer.BYTES) * Byte.SIZE;

                if (offset < Integer.BYTES) {
                    this.previous = LittleEndianUtils.replaceByte(this.previous, shift, value);
                } else {
                    this.next = LittleEndianUtils.replaceByte(this.next, shift, value);
                }

                return;
            }

            this.object[offset - LINKS_SIZE] = (byte) value;
        }
    }

    /**
     * A region over part of another buffer.
     */
    private static final class Slice implements ClassicRegion {
        private final int base;

        private final byte @NotNull [] bytes;

        private final int start;

        private final int size;

        Slice(int base, byte @NotNull [] bytes, int start, int size) {
            this.base  = base;
            this.bytes = bytes;
            this.start = start;
            this.size  = size;
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
            return this.bytes[this.start + offset] & BYTE_MASK;
        }

        /**
         * Stores one byte, changing the domain state that holds it.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @param value  the byte value in the low eight bits
         */
        @Override
        public void writeByte(int offset, int value) {
            this.bytes[this.start + offset] = (byte) value;
        }
    }

    /**
     * Creates the regions of the Storm archive list.
     *
     * @param embedded  the embedded archive records ({@link NativeCodeConstants#getStormArchives()})
     * @param mapPath   the map path bytes the game context holds, or {@code null} when the game has no map file
     * @param mapFile   the map file bytes, or {@code null} when the game has no map file
     * @param firstBase the classic address the allocator gives the first region (after the trigger nodes)
     * @return the regions: the constant, the head, then per archive the header, block table, hash table and node
     * @throws IllegalStateException if an embedded chunk is not the expected part
     */
    public static @NotNull List<ClassicRegion> create(byte @NotNull [] embedded, byte @Nullable [] mapPath, byte @Nullable [] mapFile, int firstBase) {
        ClassicStormArchives list = new ClassicStormArchives(firstBase);
        byte[] constant = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(CONSTANT_VALUE).array();

        list.regions.add(new ClassicStorageRegion(CONSTANT_BASE, constant));
        list.regions.add(list.new Head());

        ByteBuffer records = ByteBuffer.wrap(embedded).order(ByteOrder.LITTLE_ENDIAN);

        for (int archive = 0; archive < EMBEDDED_ARCHIVES; archive++) {
            byte[] object = new byte[OBJECT_SIZE];
            byte[] header = readChunk(records, PART_HEADER);

            System.arraycopy(header, 0, object, OBJECT_HEADER, HEADER_SIZE);

            byte[] blocks = readChunk(records, PART_BLOCK_TABLE);
            byte[] hashes = readChunk(records, PART_HASH_TABLE);

            list.append(object, blocks, hashes);
        }

        if (mapPath != null && mapFile != null) {
            byte[] object = new byte[OBJECT_SIZE];
            byte[][] tables = parseMapArchive(mapPath, mapFile, object);

            if (tables != null) {
                list.append(object, tables[0], tables[1]);
            }
        }

        return list.regions;
    }

    /**
     * Tells whether an address lies in one of the list's fixed regions, the constant and the list head, which exist only
     * with the list.
     *
     * @param address a classic address
     * @return {@code true} if the constant or the list head holds the address, {@code false} otherwise
     */
    static boolean isFixedAddress(int address) {
        return Integer.compareUnsigned(address - CONSTANT_BASE, Integer.BYTES) < 0 || Integer.compareUnsigned(address - HEAD_BASE, HEAD_SIZE) < 0;
    }

    /**
     * Reads the next embedded chunk: a tag byte whose value modulo 10 names the part, a DWORD length and the bytes.
     *
     * @param records the embedded records, positioned at the chunk
     * @param part    the expected part
     * @return the chunk's bytes
     * @throws IllegalStateException if the chunk is not the expected part
     */
    private static byte @NotNull [] readChunk(@NotNull ByteBuffer records, int part) {
        int tag = records.get() & BYTE_MASK;
        int length = records.getInt();

        if (tag % TAG_PART_MODULUS != part) {
            throw Exceptions.newIllegalStateException("The embedded Storm archive record has part {0} where {1} is expected.",
                                                      tag % TAG_PART_MODULUS, part);
        }

        byte[] bytes = new byte[length];

        records.get(bytes);

        return bytes;
    }

    /**
     * Parses the map's archive into its object.
     *
     * @param path   the map path bytes
     * @param file   the map file bytes
     * @param object receives the archive object
     * @return the block table and the hash table, or {@code null} when no MPQ header is found
     */
    private static byte @Nullable [] @NotNull [] parseMapArchive(byte @NotNull [] path, byte @NotNull [] file, byte @NotNull [] object) {
        ByteBuffer data = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        int archiveOffset = 0;

        while (true) {
            if (archiveOffset + HEADER_SIZE > file.length || archiveOffset < 0) {
                return null;
            }

            if (data.getInt(archiveOffset) == MPQ_MAGIC &&
                Integer.compareUnsigned(data.getInt(archiveOffset + MPQ_HEADER_SIZE_FIELD), HEADER_SIZE) >= 0) {
                break;
            }

            archiveOffset += HEADER_STEP;
        }

        System.arraycopy(file, archiveOffset, object, OBJECT_HEADER, HEADER_SIZE);

        ByteBuffer view = ByteBuffer.wrap(object).order(ByteOrder.LITTLE_ENDIAN);

        view.putInt(OBJECT_SECTOR_SIZE, HEADER_STEP << (object[OBJECT_HEADER + MPQ_SECTOR_SHIFT_FIELD] & BYTE_MASK));
        view.putInt(OBJECT_ARCHIVE_OFFSET, archiveOffset);

        int hashCount = view.getInt(OBJECT_HEADER + MPQ_HASH_COUNT_FIELD) & HASH_COUNT_MASK;

        view.putInt(OBJECT_HEADER + MPQ_HASH_COUNT_FIELD, hashCount);

        int blockCount = view.getInt(OBJECT_HEADER + MPQ_BLOCK_COUNT_FIELD);

        StormCrypt crypt = new StormCrypt();
        byte[] hashes = readTable(file, view.getInt(OBJECT_HEADER + MPQ_HASH_TABLE_FIELD) + archiveOffset, hashCount * TABLE_ENTRY_SIZE);

        crypt.decrypt(hashes, crypt.hashTableKey("(hash table)"));

        byte[] blocks = readTable(file, view.getInt(OBJECT_HEADER + MPQ_BLOCK_TABLE_FIELD) + archiveOffset, blockCount * TABLE_ENTRY_SIZE);

        crypt.decrypt(blocks, crypt.hashTableKey("(block table)"));

        if (archiveOffset > 0) {
            ByteBuffer blockView = ByteBuffer.wrap(blocks).order(ByteOrder.LITTLE_ENDIAN);

            for (int entry = 0; entry < blockCount; entry++) {
                int position = blockView.getInt(entry * TABLE_ENTRY_SIZE);

                if (position != 0) {
                    blockView.putInt(entry * TABLE_ENTRY_SIZE, position + archiveOffset);
                }
            }
        }

        view.putInt(OBJECT_FILE_SIZE, file.length);

        int pathLength = Math.min(path.length, PATH_LIMIT);

        System.arraycopy(path, 0, object, 0, pathLength);

        object[pathLength] = 0;

        return new byte[][]{blocks, hashes};
    }

    /**
     * Reads a table as the partial read the archive accepts: the bytes inside the file, zeros after.
     *
     * @param file     the map file bytes
     * @param position the file offset of the table
     * @param size     the table size
     * @return the table
     */
    private static byte @NotNull [] readTable(byte @NotNull [] file, int position, int size) {
        byte[] table = new byte[size];

        if (position >= 0 && position < file.length) {
            System.arraycopy(file, position, table, 0, Math.min(size, file.length - position));
        }

        return table;
    }


    private final @NotNull List<ClassicRegion> regions;

    private final int @NotNull [] head;

    private @Nullable Node lastNode;

    private int nextBase;

    private ClassicStormArchives(int firstBase) {
        this.regions  = new ArrayList<>();
        this.head     = new int[HEAD_SIZE / Integer.BYTES];
        this.lastNode = null;
        this.nextBase = firstBase;

        this.head[HEAD_LAST_LINK]  = HEAD_BASE + Integer.BYTES;
        this.head[HEAD_FIRST_LINK] = ~(HEAD_BASE + Integer.BYTES);
    }

    /**
     * Allocates the record's regions and appends its node.
     *
     * @param object the archive object
     * @param blocks the block table
     * @param hashes the hash table
     */
    private void append(byte @NotNull [] object, byte @NotNull [] blocks, byte @NotNull [] hashes) {
        ByteBuffer view = ByteBuffer.wrap(object).order(ByteOrder.LITTLE_ENDIAN);

        int headerBase = this.allocate(HEADER_SIZE);

        this.regions.add(new Slice(headerBase, object, OBJECT_HEADER, HEADER_SIZE));
        view.putInt(OBJECT_HEADER_ADDRESS, headerBase);

        int blockBase = this.allocate(blocks.length);

        this.regions.add(new ClassicStorageRegion(blockBase, blocks));
        view.putInt(OBJECT_BLOCK_ADDRESS, blockBase);

        int hashBase = this.allocate(hashes.length);

        this.regions.add(new ClassicStorageRegion(hashBase, hashes));
        view.putInt(OBJECT_HASH_ADDRESS, hashBase);

        Node node = new Node(this.allocate(NODE_SIZE), object);
        int link = HEAD_BASE + Integer.BYTES;

        node.next = ~link;

        if (this.head[HEAD_FIRST_LINK] == ~link) {
            this.head[HEAD_FIRST_LINK] = node.base;
        }

        node.previous = this.head[HEAD_LAST_LINK];

        if (this.lastNode != null) {
            this.lastNode.next = node.base;
        }

        this.head[HEAD_LAST_LINK] = node.base;

        this.lastNode = node;

        this.regions.add(node);
    }

    private int allocate(int size) {
        int base = this.nextBase;

        this.nextBase = NativeRegionLayout.alignAddress(base + size);

        return base;
    }
}
