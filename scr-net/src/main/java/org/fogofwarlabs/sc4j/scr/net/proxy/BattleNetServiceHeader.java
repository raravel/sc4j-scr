package org.fogofwarlabs.sc4j.scr.net.proxy;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.net.discovery.ProtobufUtils;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Encodes request routing fields and reads scalar fields from service headers.
 * Parsing retains exact header bytes, including duplicates, unknown fields and opaque nested messages.
 * Unsupported wire groups and malformed outer fields are rejected; no transport framing is consumed.
 */
public final class BattleNetServiceHeader {
    /**
     * The scalar fields of the header; fields {@code 7} and {@code 10} are messages and fields from {@code 12} on are not read.
     */
    private static final int SERVICE_IDENTIFIER_FIELD = 1;
    private static final int METHOD_IDENTIFIER_FIELD = 2;
    private static final int TOKEN_FIELD = 3;
    private static final int OBJECT_IDENTIFIER_FIELD = 4;
    private static final int PAYLOAD_SIZE_FIELD = 5;
    private static final int STATUS_FIELD = 6;
    private static final int FIRST_MESSAGE_FIELD = 7;
    private static final int TIMEOUT_FIELD = 8;
    private static final int RESPONSE_FIELD = 9;
    private static final int SECOND_MESSAGE_FIELD = 10;
    private static final int SERVICE_HASH_FIELD = 11;
    /**
     * The value slots, indexed by field number.
     */
    private static final int FIELD_SLOTS = SERVICE_HASH_FIELD + 1;

    private static final long MAXIMUM_UNSIGNED_INTEGER = 0xFFFFFFFFL;
    private static final long DWORD_MASK = 0xFFFFFFFFL;
    private static final int REQUIRED_FIELDS = (1 << SERVICE_IDENTIFIER_FIELD) | (1 << TOKEN_FIELD);
    private static final int REQUEST_FIELDS = REQUIRED_FIELDS | (1 << METHOD_IDENTIFIER_FIELD) | (1 << PAYLOAD_SIZE_FIELD) |
                                              (1 << SERVICE_HASH_FIELD);
    /**
     * The longest request: five one-byte tags, four five-byte variable integers and the fixed 32-bit hash.
     */
    private static final int REQUEST_CAPACITY = 29;

    /**
     * Creates a {@link BattleNetServiceHeader} instance with the five supplied request fields present.
     * <p>
     * The caller supplies every encoded value; this method selects no routing identifiers or token.
     *
     * @param serviceIdentifier the unsigned service identifier
     * @param methodIdentifier  the unsigned method identifier
     * @param token             the unsigned request correlation token
     * @param payloadSize       the unsigned message body size
     * @param serviceHash       the unsigned fixed-width service hash
     * @return the created {@link BattleNetServiceHeader} instance
     * @throws IllegalArgumentException if any value is outside the unsigned 32-bit range
     */
    public static @NotNull BattleNetServiceHeader createRequest(long serviceIdentifier, long methodIdentifier,
                                                                long token,
                                                                long payloadSize,
                                                                long serviceHash) {
        long[] values = new long[FIELD_SLOTS];

        values[SERVICE_IDENTIFIER_FIELD] = serviceIdentifier;
        values[METHOD_IDENTIFIER_FIELD]  = methodIdentifier;
        values[TOKEN_FIELD]              = token;
        values[PAYLOAD_SIZE_FIELD]       = payloadSize;
        values[SERVICE_HASH_FIELD]       = serviceHash;

        int presentFields = REQUEST_FIELDS;
        ByteArrayOutputStream output = new ByteArrayOutputStream(REQUEST_CAPACITY);

        for (int fieldNumber = SERVICE_IDENTIFIER_FIELD; fieldNumber < values.length; fieldNumber++) {
            if ((presentFields & (1 << fieldNumber)) == 0) {
                continue;
            }

            long value = values[fieldNumber];

            if (value < 0 || value > MAXIMUM_UNSIGNED_INTEGER) {
                throw Exceptions.newIllegalArgumentException("Header field {0} is outside the unsigned 32-bit range.", fieldNumber);
            }

            int wireType = fieldNumber == SERVICE_HASH_FIELD ? ProtobufUtils.WIRE_TYPE_FIXED_32 : ProtobufUtils.WIRE_TYPE_VARIABLE_INTEGER;

            output.write((int) ProtobufUtils.createTag(fieldNumber, wireType));

            if (fieldNumber == SERVICE_HASH_FIELD) {
                for (int byteIndex = 0; byteIndex < Integer.BYTES; byteIndex++) {
                    output.write((int) (value >>> (byteIndex * Byte.SIZE)));
                }
            } else {
                ProtobufUtils.writeVariableInteger(output, value);
            }
        }

        return new BattleNetServiceHeader(values, presentFields, output.toByteArray());
    }

    /**
     * Creates a {@link BattleNetServiceHeader} instance from one complete service header.
     * <p>
     * Known scalar duplicates use their last correctly typed value. Unsigned 32-bit fields retain their low {@code 32} bits;
     * unsigned 64-bit fields retain all bits in a Java {@code long}. Unknown or differently typed fields remain in the encoded
     * copy. No transport framing is consumed.
     *
     * @param encoded the complete protobuf header bytes
     * @return the created {@link BattleNetServiceHeader} instance
     * @throws IOException if required scalar fields are missing or outer encoding is malformed or unsupported
     */
    public static @NotNull BattleNetServiceHeader parse(byte @NotNull [] encoded) throws IOException {
        byte[] retainedBytes = encoded.clone();
        ByteBuffer input = ByteBuffer.wrap(retainedBytes).order(ByteOrder.LITTLE_ENDIAN);
        long[] values = new long[FIELD_SLOTS];
        int presentFields = 0;

        while (input.hasRemaining()) {
            long tag = readVariableInteger(input);

            if (tag <= 0 || tag > MAXIMUM_UNSIGNED_INTEGER || (tag >>> ProtobufUtils.WIRE_TYPE_BITS) == 0) {
                throw Exceptions.newIOException("Invalid service header field tag.");
            }

            int fieldNumber = (int) (tag >>> ProtobufUtils.WIRE_TYPE_BITS);
            int wireType = (int) (tag & ProtobufUtils.WIRE_TYPE_MASK);
            long value;

            switch (wireType) {
                case ProtobufUtils.WIRE_TYPE_VARIABLE_INTEGER -> {
                    value = readVariableInteger(input);

                    if (fieldNumber <= RESPONSE_FIELD && fieldNumber != FIRST_MESSAGE_FIELD) {
                        values[fieldNumber] = fieldNumber == OBJECT_IDENTIFIER_FIELD || fieldNumber == TIMEOUT_FIELD
                                              ? value
                                              : fieldNumber == RESPONSE_FIELD ? (value == 0 ? 0 : 1) : value & DWORD_MASK;
                        presentFields |= 1 << fieldNumber;
                    }
                }
                case ProtobufUtils.WIRE_TYPE_FIXED_64, ProtobufUtils.WIRE_TYPE_FIXED_32 -> {
                    int length = wireType == ProtobufUtils.WIRE_TYPE_FIXED_64 ? Long.BYTES : Integer.BYTES;

                    if (input.remaining() < length) {
                        throw Exceptions.newIOException("Truncated fixed-width service header field.");
                    }

                    value = wireType == ProtobufUtils.WIRE_TYPE_FIXED_64 ? input.getLong() : Integer.toUnsignedLong(input.getInt());

                    if (fieldNumber == SERVICE_HASH_FIELD && wireType == ProtobufUtils.WIRE_TYPE_FIXED_32) {
                        values[SERVICE_HASH_FIELD] = value;

                        presentFields |= 1 << SERVICE_HASH_FIELD;
                    }
                }
                case ProtobufUtils.WIRE_TYPE_LENGTH_DELIMITED -> {
                    long length = readVariableInteger(input);

                    if (length < 0 || length > input.remaining()) {
                        throw Exceptions.newIOException("Truncated or oversized service header field.");
                    }

                    input.position(input.position() + (int) length);
                }
                default -> throw Exceptions.newIOException("Unsupported service header wire type: {0}", wireType);
            }
        }

        if ((presentFields & REQUIRED_FIELDS) != REQUIRED_FIELDS) {
            throw Exceptions.newIOException("The service header requires service_id and token.");
        }

        return new BattleNetServiceHeader(values, presentFields, retainedBytes);
    }

    private static long readVariableInteger(@NotNull ByteBuffer input) throws IOException {
        long value = 0;

        for (int byteIndex = 0; byteIndex < ProtobufUtils.MAXIMUM_VARIABLE_INTEGER_BYTES; byteIndex++) {
            if (!input.hasRemaining()) {
                throw Exceptions.newIOException("Truncated service header variable integer.");
            }

            int current = Byte.toUnsignedInt(input.get());

            if (byteIndex == ProtobufUtils.MAXIMUM_VARIABLE_INTEGER_BYTES - 1 && current > 1) {
                throw Exceptions.newIOException("Service header variable integer exceeds 64 bits.");
            }

            value |= (long) (current & ProtobufUtils.VARIABLE_INTEGER_MASK) << (byteIndex * ProtobufUtils.VARIABLE_INTEGER_BITS);

            if ((current & ProtobufUtils.VARIABLE_INTEGER_CONTINUATION) == 0) {
                return value;
            }
        }

        throw Exceptions.newIOException("Service header variable integer exceeds 64 bits.");
    }


    private final long @NotNull [] values;
    private final int presentFields;

    private final byte @NotNull [] encoded;

    private BattleNetServiceHeader(long @NotNull [] values, int presentFields, byte @NotNull [] encoded) {
        this.values        = values;
        this.presentFields = presentFields;

        this.encoded = encoded;
    }

    /**
     * @return the unsigned service identifier
     */
    public long getServiceIdentifier() {
        return this.values[SERVICE_IDENTIFIER_FIELD];
    }

    /**
     * @return the unsigned method identifier, or {@code 0} when absent
     */
    public long getMethodIdentifier() {
        return this.values[METHOD_IDENTIFIER_FIELD];
    }

    /**
     * @return the unsigned request correlation token
     */
    public long getToken() {
        return this.values[TOKEN_FIELD];
    }

    /**
     * @return all unsigned object identifier bits, or {@code 0} when absent
     */
    public long getObjectIdentifier() {
        return this.values[OBJECT_IDENTIFIER_FIELD];
    }

    /**
     * @return the unsigned message body size, or {@code 0} when absent
     */
    public long getPayloadSize() {
        return this.values[PAYLOAD_SIZE_FIELD];
    }

    /**
     * @return the unsigned status, or {@code 0} when absent
     */
    public long getStatus() {
        return this.values[STATUS_FIELD];
    }

    /**
     * @return all unsigned timeout bits, or {@code 0} when absent
     */
    public long getTimeout() {
        return this.values[TIMEOUT_FIELD];
    }

    /**
     * @return {@code true} if the response field is present and nonzero, {@code false} otherwise
     */
    public boolean isResponse() {
        return this.values[RESPONSE_FIELD] != 0;
    }

    /**
     * @return the unsigned fixed-width service hash, or {@code 0} when absent
     */
    public long getServiceHash() {
        return this.values[SERVICE_HASH_FIELD];
    }

    /**
     * @return a copy of the exact encoded header, including preserved opaque fields
     */
    public byte @NotNull [] getEncodedBytes() {
        return this.encoded.clone();
    }

    /**
     * Checks whether a correctly typed scalar field occurred in this header.
     *
     * @param fieldNumber the descriptor field number: {@code 1..6}, {@code 8}, {@code 9} or {@code 11}
     * @return {@code true} if the scalar field is present, {@code false} otherwise
     * @throws IllegalArgumentException if the field number is not a supported scalar
     */
    public boolean hasScalarField(int fieldNumber) {
        if (fieldNumber < SERVICE_IDENTIFIER_FIELD || fieldNumber > SERVICE_HASH_FIELD || fieldNumber == FIRST_MESSAGE_FIELD ||
            fieldNumber == SECOND_MESSAGE_FIELD) {
            throw Exceptions.newIllegalArgumentException("Not a supported service header scalar: {0}", fieldNumber);
        }

        return (this.presentFields & (1 << fieldNumber)) != 0;
    }
}
