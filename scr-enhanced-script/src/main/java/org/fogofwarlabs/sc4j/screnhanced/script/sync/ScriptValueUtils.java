package org.fogofwarlabs.sc4j.screnhanced.script.sync;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.*;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes the values that the scripts submit as script commands and decodes them back into script values.
 * <p>
 * A value is {@code null}, a boolean, a number, a string, an array of values or a plain object (an object whose prototype is
 * {@code Object.prototype} or {@code null}) with values in its own enumerable string-keyed properties; symbol keys are skipped. Each value
 * starts with a tag byte: {@code 0} null, {@code 1} false, {@code 2} true, {@code 3} an integer of the signed 32-bit range as four
 * little-endian bytes, {@code 4} any other number as eight little-endian bytes of its IEEE 754 double, {@code 5} a string as its UTF-8 length
 * and bytes, {@code 6} an array as its length and elements, {@code 7} an object as its property count and pairs of a key (as a string without
 * its tag) and a value. Lengths and counts are unsigned LEB128 numbers. Values nest at most {@value #MAXIMUM_DEPTH} levels.
 */
public final class ScriptValueUtils {
    /**
     * The deepest nesting of arrays and objects in a value.
     */
    public static final int MAXIMUM_DEPTH = 64;

    private static final int NULL_TAG = 0;
    private static final int FALSE_TAG = 1;
    private static final int TRUE_TAG = 2;
    private static final int INTEGER_TAG = 3;
    private static final int DOUBLE_TAG = 4;
    private static final int STRING_TAG = 5;
    private static final int ARRAY_TAG = 6;
    private static final int OBJECT_TAG = 7;

    private static final int BYTE_MASK = 0xFF;
    private static final int DECIMAL_RADIX = 10;
    private static final int VARIABLE_LENGTH_BITS = 7;
    private static final int VARIABLE_LENGTH_MASK = 0x7F;
    private static final int VARIABLE_LENGTH_MORE = 0x80;
    /**
     * The bits a variable-length number holds at most: those of a non-negative {@code int}.
     */
    private static final int VARIABLE_LENGTH_LIMIT = Integer.SIZE - 1;

    /**
     * Reads the bytes of an encoded value.
     */
    private static final class Reader {
        private final byte @NotNull [] data;

        private int position;

        private Reader(byte @NotNull [] data) {
            this.data = data;

            this.position = 0;
        }

        private int readByte() {
            if (this.position >= this.data.length) {
                throw Exceptions.newIllegalArgumentException("The value ends at byte {0} before its end.", this.position);
            }

            return this.data[this.position++] & BYTE_MASK;
        }

        private long readLittleEndian(int byteCount) {
            long value = 0;

            for (int index = 0; index < byteCount; index++) {
                value |= (long) this.readByte() << index * Byte.SIZE;
            }

            return value;
        }

        /**
         * Reads a length or count, which cannot exceed the bytes left since every element takes one byte at least.
         */
        private int readLength() {
            int value = 0;

            for (int shift = 0; shift < VARIABLE_LENGTH_LIMIT; shift += VARIABLE_LENGTH_BITS) {
                int next = this.readByte();

                value |= (next & VARIABLE_LENGTH_MASK) << shift;

                if ((next & VARIABLE_LENGTH_MORE) == 0) {
                    if (value < 0 || value > this.data.length - this.position) {
                        throw Exceptions.newIllegalArgumentException("The length {0} at byte {1} exceeds the value.", value, this.position);
                    }

                    return value;
                }
            }

            throw Exceptions.newIllegalArgumentException("The length at byte {0} is too long.", this.position);
        }

        private @NotNull String readString() {
            int length = this.readLength();
            String text = new String(this.data, this.position, length, StandardCharsets.UTF_8);

            this.position += length;

            return text;
        }
    }


    /**
     * Encodes a script value. The value and the values inside it must be of the encoded kinds, arrays without holes, nested at most
     * {@value #MAXIMUM_DEPTH} levels and not into themselves.
     *
     * @param value  the value
     * @param global the global scope, whose {@code Object.prototype} plain objects have
     * @return the encoded bytes
     * @throws EcmaError if the value cannot be encoded
     */
    public static byte @NotNull [] encode(@Nullable Object value, @NotNull Scriptable global) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writeValue(output, value, ScriptableObject.getObjectPrototype(global), new IdentityHashMap<>());

        return output.toByteArray();
    }

    private static void writeValue(@NotNull ByteArrayOutputStream output, @Nullable Object value, @NotNull Scriptable objectPrototype,
                                   @NotNull Map<Object, Boolean> containers) {
        switch (value) {
            case null -> output.write(NULL_TAG);
            case Boolean bool -> output.write(bool ? TRUE_TAG : FALSE_TAG);
            case Number number when !(number instanceof BigInteger) -> writeNumber(output, number.doubleValue());
            case CharSequence text -> {
                output.write(STRING_TAG);
                writeString(output, text.toString());
            }
            case NativeArray array when array.getClass() == NativeArray.class -> {
                enterContainer(array, containers);
                writeArray(output, array, objectPrototype, containers);
                containers.remove(array);
            }
            case NativeObject object when object.getClass() == NativeObject.class &&
                                          (object.getPrototype() == null || object.getPrototype() == objectPrototype) -> {
                enterContainer(object, containers);
                writeObject(output, object, objectPrototype, containers);
                containers.remove(object);
            }
            default -> throw ScriptRuntime.typeError(Exceptions.format("A value of type {0} cannot be sent; only null, booleans, numbers, " +
                                                                       "strings, arrays and plain objects can.", ScriptRuntime.typeof(value)));
        }
    }

    private static void enterContainer(@NotNull Object container, @NotNull Map<Object, Boolean> containers) {
        if (containers.containsKey(container)) {
            throw ScriptRuntime.typeError("A value that contains itself cannot be sent.");
        }

        if (containers.size() >= MAXIMUM_DEPTH) {
            throw ScriptRuntime.typeError(Exceptions.format("A value nested deeper than {0} levels cannot be sent.", MAXIMUM_DEPTH));
        }

        containers.put(container, Boolean.TRUE);
    }

    private static void writeNumber(@NotNull ByteArrayOutputStream output, double number) {
        int integer = (int) number;

        if (integer == number && (integer != 0 || Double.doubleToRawLongBits(number) == 0L)) {
            output.write(INTEGER_TAG);
            writeLittleEndian(output, integer, Integer.BYTES);
        } else {
            output.write(DOUBLE_TAG);
            writeLittleEndian(output, Double.doubleToRawLongBits(number), Double.BYTES);
        }
    }

    private static void writeArray(@NotNull ByteArrayOutputStream output, @NotNull NativeArray array, @NotNull Scriptable objectPrototype,
                                   @NotNull Map<Object, Boolean> containers) {
        long length = array.getLength();

        if (length > Integer.MAX_VALUE) {
            throw ScriptRuntime.typeError(Exceptions.format("An array of {0} elements cannot be sent.", length));
        }

        output.write(ARRAY_TAG);
        writeLength(output, (int) length);

        for (int index = 0; index < length; index++) {
            Object element = array.get(index, array);

            if (element == Scriptable.NOT_FOUND) {
                throw ScriptRuntime.typeError(Exceptions.format("An array with a hole at {0} cannot be sent.", index));
            }

            writeValue(output, element, objectPrototype, containers);
        }
    }

    private static void writeObject(@NotNull ByteArrayOutputStream output, @NotNull NativeObject object, @NotNull Scriptable objectPrototype,
                                    @NotNull Map<Object, Boolean> containers) {
        List<String> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        for (Object id : object.getIds()) {
            if (id instanceof String key) {
                keys.add(key);
                values.add(ScriptableObject.getProperty(object, key));
            } else if (id instanceof Integer index) {
                keys.add(index.toString());
                values.add(ScriptableObject.getProperty(object, index));
            }
        }

        output.write(OBJECT_TAG);
        writeLength(output, keys.size());

        for (int index = 0; index < keys.size(); index++) {
            writeString(output, keys.get(index));
            writeValue(output, values.get(index), objectPrototype, containers);
        }
    }

    private static void writeString(@NotNull ByteArrayOutputStream output, @NotNull String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        writeLength(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeLength(@NotNull ByteArrayOutputStream output, int length) {
        int rest = length;

        while (rest > VARIABLE_LENGTH_MASK) {
            output.write(rest & VARIABLE_LENGTH_MASK | VARIABLE_LENGTH_MORE);
            rest >>>= VARIABLE_LENGTH_BITS;
        }

        output.write(rest);
    }

    private static void writeLittleEndian(@NotNull ByteArrayOutputStream output, long value, int byteCount) {
        for (int index = 0; index < byteCount; index++) {
            output.write((int) (value >>> index * Byte.SIZE) & BYTE_MASK);
        }
    }


    /**
     * Decodes an encoded value into new script values.
     *
     * @param context the current context
     * @param global  the global scope of the new arrays and objects
     * @param data    the encoded bytes
     * @return the value: {@code null}, a {@link Boolean}, a {@link Double}, a {@link String} or a new array or object
     * @throws IllegalArgumentException if the bytes are not one well-formed value
     */
    public static @Nullable Object decode(@NotNull Context context, @NotNull Scriptable global, byte @NotNull [] data) {
        Reader reader = new Reader(data);
        Object value = readValue(context, global, reader, 0);

        if (reader.position != data.length) {
            throw Exceptions.newIllegalArgumentException("The value ends at byte {0} of {1}.", reader.position, data.length);
        }

        return value;
    }

    private static @Nullable Object readValue(@NotNull Context context, @NotNull Scriptable global, @NotNull Reader reader, int depth) {
        int tag = reader.readByte();

        if ((tag == ARRAY_TAG || tag == OBJECT_TAG) && depth >= MAXIMUM_DEPTH) {
            throw Exceptions.newIllegalArgumentException("The value nests deeper than {0} levels.", MAXIMUM_DEPTH);
        }

        return switch (tag) {
            case NULL_TAG -> null;
            case FALSE_TAG -> Boolean.FALSE;
            case TRUE_TAG -> Boolean.TRUE;
            case INTEGER_TAG -> (double) (int) reader.readLittleEndian(Integer.BYTES);
            case DOUBLE_TAG -> Double.longBitsToDouble(reader.readLittleEndian(Double.BYTES));
            case STRING_TAG -> reader.readString();
            case ARRAY_TAG -> {
                Object[] elements = new Object[reader.readLength()];

                for (int index = 0; index < elements.length; index++) {
                    elements[index] = readValue(context, global, reader, depth + 1);
                }

                yield context.newArray(global, elements);
            }
            case OBJECT_TAG -> {
                int count = reader.readLength();
                Scriptable object = context.newObject(global);

                for (int index = 0; index < count; index++) {
                    String key = reader.readString();
                    Object value = readValue(context, global, reader, depth + 1);

                    putProperty(object, key, value);
                }

                yield object;
            }
            default -> throw Exceptions.newIllegalArgumentException("The tag {0} at byte {1} is no value.", tag, reader.position - 1);
        };
    }

    /**
     * Puts a property, under its array index when the key is the canonical text of one, as the script's own property access does.
     */
    private static void putProperty(@NotNull Scriptable object, @NotNull String key, @Nullable Object value) {
        int index = findIndex(key);

        if (index >= 0) {
            ScriptableObject.putProperty(object, index, value);
        } else {
            ScriptableObject.putProperty(object, key, value);
        }
    }

    /**
     * Finds the array index a key names.
     *
     * @return the index, or {@code -1} if the key is not the canonical text of a non-negative {@code int}
     */
    private static int findIndex(@NotNull String key) {
        if (key.isEmpty() || key.length() > String.valueOf(Integer.MAX_VALUE).length() || key.length() > 1 && key.charAt(0) == '0') {
            return -1;
        }

        long index = 0;

        for (int position = 0; position < key.length(); position++) {
            char digit = key.charAt(position);

            if (digit < '0' || digit > '9') {
                return -1;
            }

            index = index * DECIMAL_RADIX + (digit - '0');
        }

        return index <= Integer.MAX_VALUE ? (int) index : -1;
    }

    private ScriptValueUtils() {
    }
}
