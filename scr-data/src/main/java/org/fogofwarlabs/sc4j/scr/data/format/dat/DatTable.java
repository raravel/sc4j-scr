package org.fogofwarlabs.sc4j.scr.data.format.dat;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.type.ArrayDatColumn;
import org.fogofwarlabs.sc4j.scr.data.type.DatColumn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Represents a DAT file as a set of named columns.
 * <p>
 * Each field's values are held by a {@link DatColumn}: a parsed file keeps arrays ({@link ArrayDatColumn}), and a game's
 * tables keep the game's engine content ({@link #createOver}), so a write through any table of a game reaches the one
 * store of the value. Every write narrows the value to the field's width and sign first.
 * <p>
 * A lookup by name of a field that the schema lacks throws {@link IllegalArgumentException}. The typed tables of {@code data.type} read a column
 * through {@link #getColumnOrEmpty(String)}, which gives an empty column for a field that a partial schema (for example a test schema) lacks, so
 * their accessors read every id outside the column instead.
 * <p>
 * {@link #readNativeElement(String, int)} reads an element at any index through the DAT memory layout: the column blocks follow each other in file
 * order in the game's memory, each one aligned as the schema sets.
 */
public final class DatTable {
    private static final @NotNull DatColumn EMPTY_COLUMN = new ArrayDatColumn(new int[0]);
    /**
     * The slots per field of the open-addressing name table, at least: a load factor of one quarter or less.
     */
    private static final int SLOTS_PER_FIELD = 4;
    /**
     * The shift that folds the high half of a name hash into the low bits that select a slot of the name table.
     */
    private static final int HASH_SPREAD_SHIFT = 16;
    /**
     * The mask that keeps an unsigned {@code 8}-bit value.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask that keeps an unsigned {@code 16}-bit value.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * Narrows a value to the width and sign of a field type.
     *
     * @param type  the field type
     * @param value the value
     * @return the value as a field of the type stores it
     */
    private static int narrowValue(@NotNull DatType type, int value) {
        return switch (type) {
            case U8 -> value & BYTE_MASK;
            case S8 -> (byte) value;
            case U16 -> value & WORD_MASK;
            case S16 -> (short) value;
            case U32 -> value;
        };
    }

    /**
     * Wraps parsed column arrays in array columns.
     *
     * @param arrays the column arrays in field order, used and not copied
     * @return the columns in field order
     */
    private static @NotNull DatColumn @NotNull [] wrapColumns(int @NotNull [] @NotNull [] arrays) {
        DatColumn[] columns = new DatColumn[arrays.length];

        for (int fieldIndex = 0; fieldIndex < arrays.length; fieldIndex++) {
            columns[fieldIndex] = new ArrayDatColumn(arrays[fieldIndex]);
        }

        return columns;
    }

    /**
     * Reads every value of a column into a new array.
     *
     * @param column the column
     * @return the values in element order
     */
    private static int @NotNull [] readValues(@NotNull DatColumn column) {
        int[] values = new int[column.getLength()];

        for (int element = 0; element < values.length; element++) {
            values[element] = column.get(element);
        }

        return values;
    }


    private final @NotNull List<DatSchema.Field> fields;
    private final @NotNull DatColumn @NotNull [] columns;
    /**
     * The field names and indices as an open-addressing table keyed by name hash: {@link #get} runs in simulation hot paths, so the lookup avoids
     * boxing and node traversal. A repeated name maps to its last field.
     */
    private final @Nullable String @NotNull [] fieldNameSlots;
    private final int @NotNull [] fieldIndexSlots;
    /**
     * The field indices of each column block in file order; an interleaved group is one block.
     */
    private final int @NotNull [] @NotNull [] blocks;
    /**
     * The column block of each field.
     */
    private final int @NotNull [] fieldBlocks;

    /**
     * The memory layout of the column blocks in the game, or {@code null} if it is not known.
     */
    private final @Nullable DatNativeLayout nativeLayout;
    /**
     * The offset of each column block in the game's memory from the start of the first block, or {@code null} without a known memory layout.
     */
    private final int @Nullable [] nativeBlockOffsets;
    /**
     * The size of each column block in the game's memory.
     */
    private final int @NotNull [] nativeBlockSizes;
    /**
     * Whether each column block holds references to loaded data in the game's memory instead of the file values.
     */
    private final boolean @NotNull [] nativeReferenceBlocks;
    /**
     * The size of the column blocks in the game's memory, alignment gaps included.
     */
    private final int nativeSize;

    /**
     * Creates a {@link DatTable} instance over parsed column arrays.
     *
     * @param fields       the field definitions
     * @param columns      the parsed columns in field order
     * @param nativeLayout the memory layout of the column blocks in the game, or {@code null} if it is not known
     */
    DatTable(@NotNull List<DatSchema.Field> fields, int @NotNull [] @NotNull [] columns, @Nullable DatNativeLayout nativeLayout) {
        this(fields, wrapColumns(columns), nativeLayout);
    }

    /**
     * Creates a {@link DatTable} instance.
     *
     * @param fields       the field definitions
     * @param columns      the columns in field order, each with the field's element count
     * @param nativeLayout the memory layout of the column blocks in the game, or {@code null} if it is not known
     */
    private DatTable(@NotNull List<DatSchema.Field> fields, @NotNull DatColumn @NotNull [] columns, @Nullable DatNativeLayout nativeLayout) {
        this.fields          = fields;
        this.columns         = columns;
        this.fieldNameSlots  = new String[Integer.highestOneBit(Math.max(1, fields.size()) * SLOTS_PER_FIELD - 1)];
        this.fieldIndexSlots = new int[this.fieldNameSlots.length];

        List<List<Integer>> groups = new ArrayList<>();
        int[] fieldBlocks = new int[fields.size()];

        for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++) {
            String name = fields.get(fieldIndex).getName();
            int mask = this.fieldNameSlots.length - 1;
            int hash = name.hashCode();
            int slot = (hash ^ hash >>> HASH_SPREAD_SHIFT) & mask;

            while (this.fieldNameSlots[slot] != null && !this.fieldNameSlots[slot].equals(name)) {
                slot = slot + 1 & mask;
            }

            this.fieldNameSlots[slot]  = name;
            this.fieldIndexSlots[slot] = fieldIndex;

            if (fields.get(fieldIndex).getElementOffset() == 0) {
                groups.add(new ArrayList<>());
            }

            groups.get(groups.size() - 1).add(fieldIndex);
            fieldBlocks[fieldIndex] = groups.size() - 1;
        }

        this.blocks = new int[groups.size()][];

        for (int block = 0; block < groups.size(); block++) {
            this.blocks[block] = groups.get(block).stream().mapToInt(Integer::intValue).toArray();
        }

        this.fieldBlocks = fieldBlocks;

        int[] nativeBlockOffsets = null;
        int[] nativeBlockSizes = new int[this.blocks.length];
        boolean[] nativeReferenceBlocks = new boolean[this.blocks.length];
        int nativeCursor = 0;

        if (nativeLayout != null) {
            nativeBlockOffsets = new int[this.blocks.length];

            for (int block = 0; block < this.blocks.length; block++) {
                DatSchema.Field first = fields.get(this.blocks[block][0]);
                int alignment = nativeLayout.getAlignment(first.getName());

                nativeReferenceBlocks[block]                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 = nativeLayout.isReference(first.getName());
                nativeBlockSizes[block]                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          = nativeReferenceBlocks[block]
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            ? first.getCount() * first.getStride() * DatNativeLayout.REFERENCE_SIZE
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            : this.getBlockSize(block);
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   nativeCursor = (nativeCursor + alignment - 1) / alignment * alignment;
                nativeBlockOffsets[block]                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        = nativeCursor;

                nativeCursor += nativeBlockSizes[block];
            }
        }

        this.nativeLayout          = nativeLayout;
        this.nativeBlockOffsets    = nativeBlockOffsets;
        this.nativeBlockSizes      = nativeBlockSizes;
        this.nativeReferenceBlocks = nativeReferenceBlocks;
        this.nativeSize            = nativeCursor;
    }

    /**
     * Returns the value of the given field and element.
     *
     * @param fieldName    the field name
     * @param elementIndex the element index
     * @return the value
     * @throws IndexOutOfBoundsException if the element index is out of range
     */
    public int get(@NotNull String fieldName, int elementIndex) {
        int fieldIndex = this.getFieldIndex(fieldName);

        return this.requireColumn(fieldIndex, elementIndex).get(elementIndex);
    }

    /**
     * Returns the value of the given field and element.
     *
     * @param fieldIndex   the field index from {@link #getFieldIndex(String)}
     * @param elementIndex the element index
     * @return the value
     * @throws IndexOutOfBoundsException if the field or element index is out of range
     */
    public int get(int fieldIndex, int elementIndex) {
        return this.requireColumn(fieldIndex, elementIndex).get(elementIndex);
    }

    /**
     * Returns the index of a field for {@link #get(int, int)}, which loops use to read one field of many elements.
     *
     * @param fieldName the field name
     * @return the field index
     * @throws IllegalArgumentException if the field does not exist
     */
    public int getFieldIndex(@NotNull String fieldName) {
        int fieldIndex = this.findField(fieldName);

        if (fieldIndex < 0) {
            throw Exceptions.newIllegalArgumentException("The field does not exist: {0}", fieldName);
        }

        return fieldIndex;
    }

    /**
     * Returns the element count of the given field.
     *
     * @param fieldName the field name
     * @return the element count
     * @throws IllegalArgumentException if the field does not exist
     */
    public int getCount(@NotNull String fieldName) {
        int fieldIndex = this.getFieldIndex(fieldName);

        return this.fields.get(fieldIndex).getCount();
    }

    /**
     * @return the number of column blocks: the fields in file order, an interleaved group counting once
     */
    public int getBlockCount() {
        return this.blocks.length;
    }

    /**
     * @param block the column block index
     * @return the block's size in bytes, as in the file and in the classic memory array
     */
    public int getBlockSize(int block) {
        DatSchema.Field first = this.fields.get(this.blocks[block][0]);

        return first.getCount() * first.getStride() * first.getType().getSize();
    }

    /**
     * Sets the value of the given field and element; the value keeps the field's width and sign.
     *
     * @param fieldName    the field name
     * @param elementIndex the element index
     * @param value        the value
     * @throws IndexOutOfBoundsException if the element index is out of range
     */
    public void set(@NotNull String fieldName, int elementIndex, int value) {
        int fieldIndex = this.getFieldIndex(fieldName);

        this.requireColumn(fieldIndex, elementIndex).set(elementIndex, narrowValue(this.fields.get(fieldIndex).getType(), value));
    }

    /**
     * Returns the column of a field for DAT initialization within this package.
     *
     * @param fieldName the field name
     * @return the column
     */
    @NotNull DatColumn getColumn(@NotNull String fieldName) {
        int fieldIndex = this.getFieldIndex(fieldName);

        return this.columns[fieldIndex];
    }

    /**
     * Returns the column of a field for a typed table of this package, or an empty column if the table lacks the field (a partial schema); a typed
     * table then reads every id of that column outside the column.
     *
     * @param fieldName the field name
     * @return the column, or an empty column
     */
    @NotNull DatColumn getColumnOrEmpty(@NotNull String fieldName) {
        int fieldIndex = this.findField(fieldName);

        return fieldIndex < 0 ? EMPTY_COLUMN : this.columns[fieldIndex];
    }


    /**
     * Returns an independent copy of a column.
     *
     * @param fieldName the field name
     * @return the copied values in element order
     */
    public int @NotNull [] copyColumn(@NotNull String fieldName) {
        return readValues(this.getColumn(fieldName));
    }

    /**
     * Creates a {@link DatTable} instance that shares this table's fields and unchanged columns and uses copies of the supplied replacement columns.
     * <p>
     * This table is not modified, so a shared installed table can serve as the base of per-game overrides.
     *
     * @param replacementColumns the replacement values by field name, each with the field's element count
     * @return the created {@link DatTable} instance
     * @throws IllegalArgumentException if a field does not exist or a replacement has a different element count
     */
    public @NotNull DatTable createWithColumns(@NotNull Map<String, int @NotNull []> replacementColumns) {
        DatColumn[] columns = this.columns.clone();

        for (Map.Entry<String, int[]> entry : replacementColumns.entrySet()) {
            int fieldIndex = this.getFieldIndex(entry.getKey());

            if (entry.getValue().length != columns[fieldIndex].getLength()) {
                throw Exceptions.newIllegalArgumentException("The replacement column has a different element count: {0}", entry.getKey());
            }

            columns[fieldIndex] = new ArrayDatColumn(entry.getValue().clone());
        }

        return new DatTable(this.fields, columns, this.nativeLayout);
    }

    /**
     * Creates a {@link DatTable} instance whose copied columns can change independently of this table.
     *
     * @return the created {@link DatTable} instance
     */
    public @NotNull DatTable copy() {
        DatColumn[] copied = new DatColumn[this.columns.length];

        for (int index = 0; index < copied.length; index++) {
            copied[index] = new ArrayDatColumn(readValues(this.columns[index]));
        }

        return new DatTable(this.fields, copied, this.nativeLayout);
    }

    /**
     * Creates a {@link DatTable} instance with this table's fields and memory layout over other columns, such as the
     * columns of a game's engine content. This table is not modified.
     *
     * @param columns supplies the column of each field by name, with the field's element count
     * @return the created {@link DatTable} instance
     * @throws IllegalArgumentException if a supplied column has a different element count than its field
     */
    public @NotNull DatTable createOver(@NotNull Function<@NotNull String, @NotNull DatColumn> columns) {
        DatColumn[] supplied = new DatColumn[this.columns.length];

        for (int fieldIndex = 0; fieldIndex < supplied.length; fieldIndex++) {
            DatSchema.Field field = this.fields.get(fieldIndex);
            DatColumn column = columns.apply(field.getName());

            if (column.getLength() != field.getCount()) {
                throw Exceptions.newIllegalArgumentException("The column of {0} has {1} elements, the field has {2}.",
                                                             field.getName(), column.getLength(), field.getCount());
            }

            supplied[fieldIndex] = column;
        }

        return new DatTable(this.fields, supplied, this.nativeLayout);
    }

    /**
     * Writes every value of another table of the same fields into this table's columns, field by field in element order.
     *
     * @param source a table of the same schema
     * @throws IllegalArgumentException if the source has other fields
     */
    public void copyValuesFrom(@NotNull DatTable source) {
        if (source.fields != this.fields) {
            throw Exceptions.newIllegalArgumentException("The source table has other fields.");
        }

        for (int fieldIndex = 0; fieldIndex < this.columns.length; fieldIndex++) {
            DatColumn target = this.columns[fieldIndex];
            DatColumn values = source.columns[fieldIndex];

            for (int element = 0; element < target.getLength(); element++) {
                target.set(element, values.get(element));
            }
        }
    }

    /**
     * Reads one byte of a column block in its file (and classic memory) layout.
     *
     * @param block  the column block index
     * @param offset the byte offset inside the block, below {@link #getBlockSize(int)}
     * @return the unsigned byte
     */
    public int readBlockByte(int block, int offset) {
        DatSchema.Field first = this.fields.get(this.blocks[block][0]);
        int size = first.getType().getSize();
        int slot = size * first.getStride();
        int fieldIndex = this.blocks[block][offset % slot / size];

        return this.columns[fieldIndex].get(offset / slot) >>> (offset % size) * Byte.SIZE & BYTE_MASK;
    }

    /**
     * Stores one byte of a column block in its file (and classic memory) layout; the element keeps its type's width and sign.
     *
     * @param block  the column block index
     * @param offset the byte offset inside the block, below {@link #getBlockSize(int)}
     * @param value  the byte in the low eight bits
     */
    public void writeBlockByte(int block, int offset, int value) {
        DatSchema.Field first = this.fields.get(this.blocks[block][0]);
        DatType type = first.getType();
        int size = type.getSize();
        int slot = size * first.getStride();
        int fieldIndex = this.blocks[block][offset % slot / size];
        int element = offset / slot;
        int shift = offset % size * Byte.SIZE;
        int merged = this.columns[fieldIndex].get(element) & ~(BYTE_MASK << shift) | (value & BYTE_MASK) << shift;

        this.columns[fieldIndex].set(element, narrowValue(type, merged));
    }

    /**
     * Reads an element of a field at any index through the DAT memory layout, without a bound check.
     * <p>
     * An element outside the array reads the bytes at its position in the game's memory, where the column blocks follow each other in file order with
     * the schema's alignments: a byte of another block is that block's byte, and a byte of an alignment gap is zero. The value keeps the field's
     * width and sign. Without a known memory layout the read is the array read.
     *
     * @param fieldName    the field name
     * @param elementIndex the element index, inside or outside the field's array
     * @return the value
     * @throws IllegalArgumentException       if the field does not exist
     * @throws ArrayIndexOutOfBoundsException if the schema has no known memory layout and the element lies outside the array
     * @throws UnsupportedOperationException  if the element lies outside the memory of the column blocks or reads a block
     *                                        of references to loaded data
     */
    public int readNativeElement(@NotNull String fieldName, int elementIndex) {
        int fieldIndex = this.getFieldIndex(fieldName);
        DatColumn column = this.columns[fieldIndex];
        int[] blockOffsets = this.nativeBlockOffsets;

        if (blockOffsets == null || (elementIndex >= 0 && elementIndex < column.getLength())) {
            return column.get(elementIndex);
        }

        int block = this.fieldBlocks[fieldIndex];

        if (this.nativeReferenceBlocks[block]) {
            throw Exceptions.newUnsupportedOperationException("The element reads references to loaded data: {0}[{1}]", fieldName, elementIndex);
        }

        DatSchema.Field field = this.fields.get(fieldIndex);
        DatType type = field.getType();
        int size = type.getSize();
        long address = blockOffsets[block] + ((long) elementIndex * field.getStride() + field.getElementOffset()) * size;

        if (address < 0 || address + size > this.nativeSize) {
            throw Exceptions.newUnsupportedOperationException("The element lies outside the DAT memory: {0}[{1}]", fieldName, elementIndex);
        }

        int value = 0;

        for (int byteIndex = 0; byteIndex < size; byteIndex++) {
            value |= this.readNativeByte(blockOffsets, (int) address + byteIndex) << byteIndex * Byte.SIZE;
        }

        return switch (type) {
            case S8 -> (byte) value;
            case S16 -> (short) value;
            case U8, U16, U32 -> value;
        };
    }


    /**
     * Returns the column of a field after checking an element index.
     *
     * @param fieldIndex   the field index
     * @param elementIndex the element index
     * @return the column of the field
     * @throws IndexOutOfBoundsException if the field or element index is out of range
     */
    private @NotNull DatColumn requireColumn(int fieldIndex, int elementIndex) {
        DatColumn column = this.columns[fieldIndex];

        if (elementIndex < 0 || elementIndex >= column.getLength()) {
            throw Exceptions.newIndexOutOfBoundsException("The element {0} lies outside the {1} elements of field {2}.",
                                                          elementIndex, column.getLength(), this.fields.get(fieldIndex).getName());
        }

        return column;
    }

    /**
     * Finds a field by name in the open-addressing name table.
     *
     * @param fieldName the field name
     * @return the index of the field, or {@code -1} if it does not exist
     */
    private int findField(@NotNull String fieldName) {
        String[] names = this.fieldNameSlots;
        int mask = names.length - 1;
        int hash = fieldName.hashCode();

        for (int slot = (hash ^ hash >>> HASH_SPREAD_SHIFT) & mask; ; slot = slot + 1 & mask) {
            String name = names[slot];

            if (name == null) {
                return -1;
            }

            if (name == fieldName || name.equals(fieldName)) {
                return this.fieldIndexSlots[slot];
            }
        }
    }

    /**
     * Reads one byte of the column blocks in the game's memory.
     *
     * @param blockOffsets the offset of each block in the game's memory
     * @param address      the byte offset from the start of the first block, below {@link #nativeSize}
     * @return the unsigned byte; zero in an alignment gap
     * @throws UnsupportedOperationException if the byte lies in a block of references to loaded data
     */
    private int readNativeByte(int @NotNull [] blockOffsets, int address) {
        for (int block = 0; block < this.blocks.length; block++) {
            int offset = address - blockOffsets[block];

            if (offset < 0 || offset >= this.nativeBlockSizes[block]) {
                continue;
            }

            if (this.nativeReferenceBlocks[block]) {
                throw Exceptions.newUnsupportedOperationException("The DAT memory byte lies in references to loaded data: {0}", address);
            }

            return this.readBlockByte(block, offset);
        }

        return 0;
    }
}
