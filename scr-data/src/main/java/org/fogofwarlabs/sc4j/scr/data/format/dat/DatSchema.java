package org.fogofwarlabs.sc4j.scr.data.format.dat;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;

/**
 * Represents the column layout of a DAT file.
 * <p>
 * DAT files store their data column-major: every field is a contiguous array of fixed-size elements. A few fields are interleaved in groups, for
 * example the placement box width and height of units.dat alternate within a 4-byte slot.
 */
public final class DatSchema {
    /**
     * The native block alignment of a schema without a known memory layout.
     */
    private static final int UNKNOWN_NATIVE_BLOCK_ALIGNMENT = 0;
    /**
     * The mask that keeps the unsigned value of a file byte.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * Represents a single column of a DAT file.
     */
    public static final class Field {
        private final @NotNull String name;
        private final @NotNull DatType type;
        private final int count;
        private final int stride;
        private final int elementOffset;
        private final int blockOffset;

        /**
         * Creates a {@link Field} instance.
         *
         * @param name          the field name
         * @param type          the element type
         * @param count         the element count
         * @param stride        the element stride in elements; interleaved groups use a stride above {@code 1}
         * @param elementOffset the offset of the first element inside the stride slot
         * @param blockOffset   the offset of the column block inside the file
         */
        private Field(@NotNull String name, @NotNull DatType type, int count, int stride, int elementOffset, int blockOffset) {
            this.name          = name;
            this.type          = type;
            this.count         = count;
            this.stride        = stride;
            this.elementOffset = elementOffset;
            this.blockOffset   = blockOffset;
        }

        /**
         * @return the field name
         */
        public @NotNull String getName() {
            return this.name;
        }

        /**
         * @return the element count
         */
        public int getCount() {
            return this.count;
        }

        /**
         * @return the element type
         */
        public @NotNull DatType getType() {
            return this.type;
        }

        /**
         * @return the element stride in elements, above {@code 1} for interleaved groups
         */
        int getStride() {
            return this.stride;
        }

        /**
         * @return the offset of the first element inside the stride slot
         */
        int getElementOffset() {
            return this.elementOffset;
        }
    }


    private final @NotNull List<Field> fields;

    private int nativeBlockAlignment;
    private final @NotNull Map<String, Integer> nativeBlockAlignments;
    private final @NotNull Set<String> nativeReferenceFields;

    private int blockCursor;

    /**
     * Creates an empty {@link DatSchema} instance to which columns are added.
     */
    public DatSchema() {
        this.fields = new ArrayList<>();

        this.nativeBlockAlignment  = UNKNOWN_NATIVE_BLOCK_ALIGNMENT;
        this.nativeBlockAlignments = new HashMap<>();
        this.nativeReferenceFields = new HashSet<>();

        this.blockCursor = 0;
    }

    /**
     * @return the expected file size of this schema in bytes
     */
    public int getExpectedSize() {
        return this.blockCursor;
    }

    /**
     * @return the fields in file order, an interleaved group in its element order
     */
    public @NotNull List<Field> getFields() {
        return List.copyOf(this.fields);
    }


    /**
     * Sets the alignment of the column blocks in the game's memory: the game keeps the blocks in file order, each one starting at the next multiple
     * of the alignment. {@link DatTable#readNativeElement(String, int)} reads an element outside its array through this layout; a schema without an
     * alignment has no known memory layout.
     *
     * @param alignment the block alignment in bytes
     * @return this instance for chaining
     */
    public @NotNull DatSchema alignNativeBlocks(int alignment) {
        this.nativeBlockAlignment = alignment;

        return this;
    }

    /**
     * Sets the alignment of the column block that starts with a field, for a block that the game aligns unlike the others.
     *
     * @param fieldName the name of the first field of the block
     * @param alignment the block alignment in bytes
     * @return this instance for chaining
     */
    public @NotNull DatSchema alignNativeBlock(@NotNull String fieldName, int alignment) {
        this.nativeBlockAlignments.put(fieldName, alignment);

        return this;
    }

    /**
     * Declares that the game keeps references to loaded data in the array of a field instead of the file values, one reference per element; the
     * memory of that array is not represented.
     *
     * @param fieldName the name of the field
     * @return this instance for chaining
     */
    public @NotNull DatSchema holdNativeReferences(@NotNull String fieldName) {
        this.nativeReferenceFields.add(fieldName);

        return this;
    }

    /**
     * Adds an unsigned 8-bit column.
     *
     * @param name  the field name
     * @param count the element count
     * @return this instance for chaining
     */
    public @NotNull DatSchema fieldU8(@NotNull String name, int count) {
        return this.addField(name, DatType.U8, count, 1, 0);
    }

    /**
     * Adds a signed 8-bit column.
     *
     * @param name  the field name
     * @param count the element count
     * @return this instance for chaining
     */
    public @NotNull DatSchema fieldS8(@NotNull String name, int count) {
        return this.addField(name, DatType.S8, count, 1, 0);
    }

    /**
     * Adds an unsigned 16-bit column.
     *
     * @param name  the field name
     * @param count the element count
     * @return this instance for chaining
     */
    public @NotNull DatSchema fieldU16(@NotNull String name, int count) {
        return this.addField(name, DatType.U16, count, 1, 0);
    }

    /**
     * Adds a signed 16-bit column.
     *
     * @param name  the field name
     * @param count the element count
     * @return this instance for chaining
     */
    public @NotNull DatSchema fieldS16(@NotNull String name, int count) {
        return this.addField(name, DatType.S16, count, 1, 0);
    }

    /**
     * Adds an unsigned 32-bit column.
     *
     * @param name  the field name
     * @param count the element count
     * @return this instance for chaining
     */
    public @NotNull DatSchema fieldU32(@NotNull String name, int count) {
        return this.addField(name, DatType.U32, count, 1, 0);
    }

    /**
     * Adds a pair of interleaved unsigned 8-bit columns.
     *
     * @param nameFirst  the first field name
     * @param nameSecond the second field name
     * @param count      the element count of each field
     * @return this instance for chaining
     */
    public @NotNull DatSchema fieldU8x2(@NotNull String nameFirst, @NotNull String nameSecond, int count) {
        return this.addInterleavedFields(DatType.U8, count, nameFirst, nameSecond);
    }

    /**
     * Adds a pair of interleaved unsigned 16-bit columns.
     *
     * @param nameFirst  the first field name
     * @param nameSecond the second field name
     * @param count      the element count of each field
     * @return this instance for chaining
     */
    public @NotNull DatSchema fieldU16x2(@NotNull String nameFirst, @NotNull String nameSecond, int count) {
        return this.addInterleavedFields(DatType.U16, count, nameFirst, nameSecond);
    }

    /**
     * Adds four interleaved unsigned 16-bit columns.
     *
     * @param nameFirst  the first field name
     * @param nameSecond the second field name
     * @param nameThird  the third field name
     * @param nameFourth the fourth field name
     * @param count      the element count of each field
     * @return this instance for chaining
     */
    public @NotNull DatSchema fieldU16x4(@NotNull String nameFirst, @NotNull String nameSecond, @NotNull String nameThird, @NotNull String nameFourth,
                                         int count) {
        return this.addInterleavedFields(DatType.U16, count, nameFirst, nameSecond, nameThird, nameFourth);
    }


    /**
     * Parses the given DAT file content with this schema.
     *
     * @param data the DAT file content
     * @return the parsed {@link DatTable} instance
     * @throws IOException if the content size mismatches the schema
     */
    public @NotNull DatTable parse(byte @NotNull [] data) throws IOException {
        if (data.length != this.blockCursor) {
            throw Exceptions.newIOException("The DAT file size mismatches the schema: expected {0}, got {1}", this.blockCursor, data.length);
        }

        int[][] columns = new int[this.fields.size()][];

        for (int fieldIndex = 0; fieldIndex < this.fields.size(); fieldIndex++) {
            Field field = this.fields.get(fieldIndex);
            int[] column = new int[field.count];
            int elementSize = field.type.getSize();

            for (int elementIndex = 0; elementIndex < field.count; elementIndex++) {
                int offset = field.blockOffset + field.elementOffset * elementSize + elementIndex * field.stride * elementSize;
                int value = 0;

                for (int byteIndex = elementSize - 1; byteIndex >= 0; byteIndex--) {
                    value = (value << Byte.SIZE) | (data[offset + byteIndex] & BYTE_MASK);
                }

                if (field.type.isSigned()) {
                    if (elementSize == Byte.BYTES) {
                        value = (byte) value;
                    } else if (elementSize == Short.BYTES) {
                        value = (short) value;
                    }
                }

                column[elementIndex] = value;
            }

            columns[fieldIndex] = column;
        }

        DatNativeLayout nativeLayout = this.nativeBlockAlignment == UNKNOWN_NATIVE_BLOCK_ALIGNMENT
                                       ? null
                                       : new DatNativeLayout(this.nativeBlockAlignment, Map.copyOf(this.nativeBlockAlignments),
                                                             Set.copyOf(this.nativeReferenceFields));

        return new DatTable(this.fields, columns, nativeLayout);
    }

    /**
     * Adds interleaved columns: the elements of the fields alternate in one block, in the order of the names.
     *
     * @param type  the element type of every field
     * @param count the element count of each field
     * @param names the field names in slot order
     * @return this instance for chaining
     */
    private @NotNull DatSchema addInterleavedFields(@NotNull DatType type, int count, @NotNull String @NotNull ... names) {
        for (int elementOffset = 0; elementOffset < names.length; elementOffset++) {
            this.addField(names[elementOffset], type, count, names.length, elementOffset);
        }

        return this;
    }

    /**
     * Adds a column: a field at slot offset {@code 0} starts a new block after the previous one, a later field of an interleaved group shares the
     * block of the group's first field.
     *
     * @param name          the field name
     * @param type          the element type
     * @param count         the element count
     * @param stride        the element stride in elements
     * @param elementOffset the offset of the first element inside the stride slot
     * @return this instance for chaining
     */
    private @NotNull DatSchema addField(@NotNull String name, @NotNull DatType type, int count, int stride, int elementOffset) {
        int blockOffset = this.blockCursor;

        if (elementOffset == 0) {
            this.blockCursor += count * type.getSize() * stride;
        } else {
            blockOffset -= count * type.getSize() * stride;
        }

        this.fields.add(new Field(name, type, count, stride, elementOffset, blockOffset));

        return this;
    }
}
