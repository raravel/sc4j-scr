package org.fogofwarlabs.sc4j.scr.net.proxy;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.net.discovery.ProtobufUtils;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderTransportMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Encodes and reads proxy datagrams containing provider payloads and routing fields.
 * This envelope is outside {@link ProviderTransportMessage}.
 * <p>
 * Parsing retains the original bytes, including duplicate fields, and merges every occurrence of the nested {@code AddressInfo}
 * message into its seven fields; malformed address information stays in the retained bytes but cannot be used for routing.
 * Groups and malformed outer fields are rejected by this bounded decoder. This codec builds probe answers, but it does not
 * authenticate a datagram, select its route or establish a network session.
 */
public final class GameProxyDatagram {
    /**
     * The envelope fields: the type, the provider payload, two unnamed scalars, the payload hash, the probe timestamp,
     * the address information and the alternate type. The schema names of the scalars are unverified.
     */
    static final int TYPE_FIELD = 1;
    static final int PAYLOAD_FIELD = 2;
    static final int SCALAR_FIELD_3 = 3;
    static final int SCALAR_FIELD_4 = 4;
    static final int PAYLOAD_HASH_FIELD = 5;
    static final int PROBE_TIMESTAMP_FIELD = 9;
    static final int ADDRESS_INFORMATION_FIELD = 11;
    static final int ALTERNATE_TYPE_FIELD = 12;
    /**
     * The value slots, indexed by field number.
     */
    private static final int FIELD_SLOTS = ALTERNATE_TYPE_FIELD + 1;

    /**
     * The message type of a provider data datagram, the default of both type fields.
     */
    static final int TYPE_DATA = 1;
    /**
     * The message type of a relayed probe, which carries no provider payload.
     */
    static final int TYPE_RELAYED_PROBE = 8;
    /**
     * The message type of a probe answer.
     */
    static final int TYPE_PROBE_ANSWER = 9;
    /**
     * The enum value the type enum does not define; values {@link #TYPE_DATA}..{@link #LAST_TYPE} otherwise are.
     */
    private static final int UNDEFINED_TYPE = 4;
    private static final int LAST_TYPE = 9;

    /**
     * The {@code AddressInfo} fields: the relay route's address and port, the sender's address and port, the relay flag, the
     * destination's address and port. The schema names of the relay route fields are unverified.
     */
    private static final int RELAY_ADDRESS_FIELD = 1;
    private static final int RELAY_PORT_FIELD = 2;
    private static final int SOURCE_ADDRESS_FIELD = 3;
    private static final int SOURCE_PORT_FIELD = 4;
    private static final int RELAYED_FIELD = 5;
    private static final int DESTINATION_ADDRESS_FIELD = 6;
    private static final int DESTINATION_PORT_FIELD = 7;
    /**
     * The {@code AddressInfo} value slots, indexed by field number.
     */
    private static final int ADDRESS_INFORMATION_SLOTS = DESTINATION_PORT_FIELD + 1;

    /**
     * The initial capacities of the encoded messages past their variable parts.
     */
    private static final int DATA_FIELDS_CAPACITY = 32;
    private static final int PROBE_ANSWER_CAPACITY = 24;
    private static final int ADDRESS_INFORMATION_CAPACITY = 24;

    private static final long MAXIMUM_UNSIGNED_INTEGER = 0xFFFFFFFFL;
    private static final long DWORD_MASK = 0xFFFFFFFFL;
    private static final int BYTE_MASK = 0xFF;
    /**
     * The reflected polynomial of the 64-bit payload hash and its byte table.
     */
    private static final long HASH_POLYNOMIAL = 0x95AC9329AC4BC9B5L;
    private static final int HASH_TABLE_SIZE = 256;
    private static final long @NotNull [] RECEIVE_HASH_TABLE = new long[HASH_TABLE_SIZE];

    static {
        for (int tableIndex = 0; tableIndex < RECEIVE_HASH_TABLE.length; tableIndex++) {
            long value = tableIndex;

            for (int bitIndex = 0; bitIndex < Byte.SIZE; bitIndex++) {
                value = (value >>> 1) ^ ((value & 1) != 0 ? HASH_POLYNOMIAL : 0);
            }

            RECEIVE_HASH_TABLE[tableIndex] = value;
        }
    }

    /**
     * Creates a {@link GameProxyDatagram} instance containing only type {@code 1} and a provider payload.
     * <p>
     * This two-field factory omits routing information, probe timestamps and payload hashes.
     *
     * @param message the ordinary provider transport message to carry in field {@code 2}
     * @return the created {@link GameProxyDatagram} instance
     */
    public static @NotNull GameProxyDatagram createData(@NotNull ProviderTransportMessage message) {
        byte[] payload = message.getEncodedBytes();
        ByteArrayOutputStream output = new ByteArrayOutputStream(payload.length + DATA_FIELDS_CAPACITY);

        writeTag(output, TYPE_FIELD, ProtobufUtils.WIRE_TYPE_VARIABLE_INTEGER);
        ProtobufUtils.writeVariableInteger(output, TYPE_DATA);
        writeTag(output, PAYLOAD_FIELD, ProtobufUtils.WIRE_TYPE_LENGTH_DELIMITED);
        ProtobufUtils.writeVariableInteger(output, payload.length);

        int payloadOffset = output.size();
        long[] values = new long[FIELD_SLOTS];

        output.writeBytes(payload);
        values[TYPE_FIELD]           = TYPE_DATA;
        values[ALTERNATE_TYPE_FIELD] = TYPE_DATA;

        return new GameProxyDatagram(values,
                                     1 << TYPE_FIELD,
                                     output.toByteArray(),
                                     payloadOffset, payload.length,
                                     new long[ADDRESS_INFORMATION_SLOTS]);
    }

    /**
     * Creates a {@link GameProxyDatagram} instance of type {@code 1}: field {@code 2} the provider message, field {@code 5} the
     * payload hash (omitted when {@code 0}), field {@code 9} the probe timestamp (omitted when {@code 0}) and field {@code 11} the
     * address information, present even when empty.
     *
     * @param message            the provider message
     * @param payloadHash        {@link #calculatePayloadHash(byte[])} XOR the sender's key, or {@code 0} for none
     * @param probeTimestamp     the probe's Unix time in microseconds, or {@code 0} for an ordinary datagram
     * @param addressInformation the encoded {@code AddressInfo} ({@link #encodeAddressInformation(Integer, Integer, Boolean, Integer, Integer)})
     * @return the created {@link GameProxyDatagram} instance
     */
    public static @NotNull GameProxyDatagram createData(@NotNull ProviderTransportMessage message,
                                                        long payloadHash,
                                                        long probeTimestamp,
                                                        byte @NotNull [] addressInformation) {
        byte[] payload = message.getEncodedBytes();
        ByteArrayOutputStream output = new ByteArrayOutputStream(payload.length + addressInformation.length + DATA_FIELDS_CAPACITY);

        writeTag(output, TYPE_FIELD, ProtobufUtils.WIRE_TYPE_VARIABLE_INTEGER);
        ProtobufUtils.writeVariableInteger(output, TYPE_DATA);
        writeTag(output, PAYLOAD_FIELD, ProtobufUtils.WIRE_TYPE_LENGTH_DELIMITED);
        ProtobufUtils.writeVariableInteger(output, payload.length);

        int payloadOffset = output.size();
        long[] values = new long[FIELD_SLOTS];
        int presentFields = 1 << TYPE_FIELD;

        output.writeBytes(payload);
        values[TYPE_FIELD]           = TYPE_DATA;
        values[ALTERNATE_TYPE_FIELD] = TYPE_DATA;

        if (payloadHash != 0) {
            writeTag(output, PAYLOAD_HASH_FIELD, ProtobufUtils.WIRE_TYPE_VARIABLE_INTEGER);
            ProtobufUtils.writeVariableInteger(output, payloadHash);
            values[PAYLOAD_HASH_FIELD] = payloadHash;

            presentFields |= 1 << PAYLOAD_HASH_FIELD;
        }

        if (probeTimestamp != 0) {
            writeTag(output, PROBE_TIMESTAMP_FIELD, ProtobufUtils.WIRE_TYPE_VARIABLE_INTEGER);
            ProtobufUtils.writeVariableInteger(output, probeTimestamp);
            values[PROBE_TIMESTAMP_FIELD] = probeTimestamp;

            presentFields |= 1 << PROBE_TIMESTAMP_FIELD;
        }

        writeTag(output, ADDRESS_INFORMATION_FIELD, ProtobufUtils.WIRE_TYPE_LENGTH_DELIMITED);
        ProtobufUtils.writeVariableInteger(output, addressInformation.length);
        output.writeBytes(addressInformation);

        return new GameProxyDatagram(values, presentFields, output.toByteArray(), payloadOffset, payload.length, new long[ADDRESS_INFORMATION_SLOTS]);
    }

    /**
     * Creates a {@link GameProxyDatagram} instance answering a probe.
     * <p>
     * The answer carries field {@code 1} set to type {@code 1}, the probe's fields {@code 3}, {@code 4} and {@code 9}, and field
     * {@code 12} set to type {@code 9}. Only an answer to a probe with address information carries field {@code 11}: the probe's
     * relay flag and destination, which is the answering endpoint as the prober addressed it, and for a relayed probe the
     * probe's fields {@code 3} and {@code 4} as the relay route.
     *
     * @param probe the received probe
     * @return the created {@link GameProxyDatagram} instance
     * @throws IOException if the probe's address information is malformed
     */
    public static @NotNull GameProxyDatagram createProbeAnswer(@NotNull GameProxyDatagram probe) throws IOException {
        long[] values = new long[FIELD_SLOTS];
        int presentFields = (1 << TYPE_FIELD) | (1 << SCALAR_FIELD_3) | (1 << SCALAR_FIELD_4) | (1 << PROBE_TIMESTAMP_FIELD) |
                            (1 << ALTERNATE_TYPE_FIELD);
        long[] addressInformation = new long[ADDRESS_INFORMATION_SLOTS];
        ByteArrayOutputStream output = new ByteArrayOutputStream(PROBE_ANSWER_CAPACITY + ADDRESS_INFORMATION_CAPACITY);

        values[TYPE_FIELD]            = TYPE_DATA;
        values[SCALAR_FIELD_3]        = probe.values[SCALAR_FIELD_3];
        values[SCALAR_FIELD_4]        = probe.values[SCALAR_FIELD_4];
        values[PROBE_TIMESTAMP_FIELD] = probe.values[PROBE_TIMESTAMP_FIELD];
        values[ALTERNATE_TYPE_FIELD]  = TYPE_PROBE_ANSWER;

        writeTag(output, TYPE_FIELD, ProtobufUtils.WIRE_TYPE_VARIABLE_INTEGER);
        ProtobufUtils.writeVariableInteger(output, values[TYPE_FIELD]);
        writeTag(output, SCALAR_FIELD_3, ProtobufUtils.WIRE_TYPE_VARIABLE_INTEGER);
        ProtobufUtils.writeVariableInteger(output, values[SCALAR_FIELD_3]);
        writeTag(output, SCALAR_FIELD_4, ProtobufUtils.WIRE_TYPE_VARIABLE_INTEGER);
        ProtobufUtils.writeVariableInteger(output, values[SCALAR_FIELD_4]);
        writeTag(output, PROBE_TIMESTAMP_FIELD, ProtobufUtils.WIRE_TYPE_VARIABLE_INTEGER);
        ProtobufUtils.writeVariableInteger(output, values[PROBE_TIMESTAMP_FIELD]);

        if (probe.hasAddressInformation()) {
            long[] probeAddressInformation = probe.requireAddressInformation();
            int addressFields = (1 << RELAYED_FIELD) | (1 << DESTINATION_ADDRESS_FIELD) | (1 << DESTINATION_PORT_FIELD);

            addressInformation[RELAYED_FIELD]             = probeAddressInformation[RELAYED_FIELD];
            addressInformation[DESTINATION_ADDRESS_FIELD] = probeAddressInformation[DESTINATION_ADDRESS_FIELD];
            addressInformation[DESTINATION_PORT_FIELD]    = probeAddressInformation[DESTINATION_PORT_FIELD];

            if (addressInformation[RELAYED_FIELD] != 0) {
                addressInformation[RELAY_ADDRESS_FIELD] = values[SCALAR_FIELD_3];
                addressInformation[RELAY_PORT_FIELD]    = values[SCALAR_FIELD_4];
                addressFields |= (1 << RELAY_ADDRESS_FIELD) | (1 << RELAY_PORT_FIELD);
            }

            byte[] encodedAddressInformation = encodeAddressInformation(addressInformation, addressFields);

            presentFields |= 1 << ADDRESS_INFORMATION_FIELD;

            writeTag(output, ADDRESS_INFORMATION_FIELD, ProtobufUtils.WIRE_TYPE_LENGTH_DELIMITED);
            ProtobufUtils.writeVariableInteger(output, encodedAddressInformation.length);
            output.writeBytes(encodedAddressInformation);
        }

        writeTag(output, ALTERNATE_TYPE_FIELD, ProtobufUtils.WIRE_TYPE_VARIABLE_INTEGER);
        ProtobufUtils.writeVariableInteger(output, values[ALTERNATE_TYPE_FIELD]);

        return new GameProxyDatagram(values, presentFields, output.toByteArray(), 0, 0, addressInformation);
    }

    /**
     * Encodes an {@code AddressInfo} message in field order; {@code null} leaves a field out. Addresses are IPv4 in the
     * little-endian 32-bit layout (first dotted octet in the low byte).
     *
     * @param sourceAddress      field {@code 3}, the sender's address
     * @param sourcePort         field {@code 4}, the sender's port
     * @param relayed            field {@code 5}, the relay flag
     * @param destinationAddress field {@code 6}, the destination's address
     * @param destinationPort    field {@code 7}, the destination's port
     * @return the encoded message
     */
    public static byte @NotNull [] encodeAddressInformation(@Nullable Integer sourceAddress, @Nullable Integer sourcePort,
                                                            @Nullable Boolean relayed,
                                                            @Nullable Integer destinationAddress, @Nullable Integer destinationPort) {
        long[] values = new long[ADDRESS_INFORMATION_SLOTS];
        int presentFields = 0;

        if (sourceAddress != null) {
            values[SOURCE_ADDRESS_FIELD] = Integer.toUnsignedLong(sourceAddress);
            presentFields |= 1 << SOURCE_ADDRESS_FIELD;
        }

        if (sourcePort != null) {
            values[SOURCE_PORT_FIELD] = Integer.toUnsignedLong(sourcePort);
            presentFields |= 1 << SOURCE_PORT_FIELD;
        }

        if (relayed != null) {
            values[RELAYED_FIELD] = relayed ? 1 : 0;
            presentFields |= 1 << RELAYED_FIELD;
        }

        if (destinationAddress != null) {
            values[DESTINATION_ADDRESS_FIELD] = Integer.toUnsignedLong(destinationAddress);
            presentFields |= 1 << DESTINATION_ADDRESS_FIELD;
        }

        if (destinationPort != null) {
            values[DESTINATION_PORT_FIELD] = Integer.toUnsignedLong(destinationPort);
            presentFields |= 1 << DESTINATION_PORT_FIELD;
        }

        return encodeAddressInformation(values, presentFields);
    }

    /**
     * Encodes an {@code AddressInfo} message, writing the present fields in field number order.
     *
     * @param values        the field values, indexed by field number
     * @param presentFields the bits of the fields to write, indexed by field number
     * @return the encoded message
     */
    private static byte @NotNull [] encodeAddressInformation(long @NotNull [] values, int presentFields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(ADDRESS_INFORMATION_CAPACITY);

        for (int fieldNumber = RELAY_ADDRESS_FIELD; fieldNumber <= DESTINATION_PORT_FIELD; fieldNumber++) {
            if ((presentFields & (1 << fieldNumber)) != 0) {
                writeTag(output, fieldNumber, ProtobufUtils.WIRE_TYPE_VARIABLE_INTEGER);
                ProtobufUtils.writeVariableInteger(output, values[fieldNumber]);
            }
        }

        return output.toByteArray();
    }

    /**
     * Calculates the 64-bit payload hash with polynomial {@code 0x95AC9329AC4BC9B5}, seed {@code 0} and no final XOR.
     *
     * @param data the bytes, the provider message for field {@code 5}
     * @return the hash
     */
    public static long calculatePayloadHash(byte @NotNull [] data) {
        long value = 0;

        for (byte current : data) {
            value = RECEIVE_HASH_TABLE[(int) ((value ^ current) & BYTE_MASK)] ^ (value >>> Byte.SIZE);
        }

        return value;
    }

    /**
     * Creates a {@link GameProxyDatagram} instance from one complete outer protobuf envelope.
     * <p>
     * Correctly typed scalar and payload duplicates use the last value. Enum values {@code 1..3} and {@code 5..9} are
     * recognized; others remain opaque and do not replace the previous value. Fields {@code 3}/{@code 4} retain their low
     * {@code 32} bits. Fields {@code 5}/{@code 9} retain all unsigned 64-bit bits in a Java {@code long}. Absent enums default
     * to {@code 1}.
     *
     * @param encoded the complete envelope without any additional transport prefix
     * @return the created {@link GameProxyDatagram} instance
     * @throws IOException if outer encoding is malformed or unsupported
     */
    public static @NotNull GameProxyDatagram parse(byte @NotNull [] encoded) throws IOException {
        byte[] retainedBytes = encoded.clone();
        ByteBuffer input = ByteBuffer.wrap(retainedBytes);
        long[] values = new long[FIELD_SLOTS];
        int presentFields = 0;
        int payloadOffset = 0;
        int payloadLength = 0;
        long[] addressInformation = new long[ADDRESS_INFORMATION_SLOTS];

        values[TYPE_FIELD]           = TYPE_DATA;
        values[ALTERNATE_TYPE_FIELD] = TYPE_DATA;

        while (input.hasRemaining()) {
            long tag = readVariableInteger(input);

            if (tag <= 0 || tag > MAXIMUM_UNSIGNED_INTEGER || (tag >>> ProtobufUtils.WIRE_TYPE_BITS) == 0) {
                throw Exceptions.newIOException("Invalid game proxy field tag.");
            }

            int fieldNumber = (int) (tag >>> ProtobufUtils.WIRE_TYPE_BITS);
            int wireType = (int) (tag & ProtobufUtils.WIRE_TYPE_MASK);

            switch (wireType) {
                case ProtobufUtils.WIRE_TYPE_VARIABLE_INTEGER -> {
                    long value = readVariableInteger(input);

                    if (fieldNumber == TYPE_FIELD || fieldNumber == ALTERNATE_TYPE_FIELD) {
                        int enumValue = (int) value;

                        if ((enumValue >= TYPE_DATA && enumValue < UNDEFINED_TYPE) || (enumValue > UNDEFINED_TYPE && enumValue <= LAST_TYPE)) {
                            values[fieldNumber] = enumValue;

                            presentFields |= 1 << fieldNumber;
                        }
                    } else if (fieldNumber == SCALAR_FIELD_3 || fieldNumber == SCALAR_FIELD_4 || fieldNumber == PAYLOAD_HASH_FIELD ||
                               fieldNumber == PROBE_TIMESTAMP_FIELD) {
                        values[fieldNumber] = fieldNumber == SCALAR_FIELD_3 || fieldNumber == SCALAR_FIELD_4 ? value & DWORD_MASK : value;
                        presentFields |= 1 << fieldNumber;
                    }
                }
                case ProtobufUtils.WIRE_TYPE_FIXED_64, ProtobufUtils.WIRE_TYPE_FIXED_32 -> {
                    int length = wireType == ProtobufUtils.WIRE_TYPE_FIXED_64 ? Long.BYTES : Integer.BYTES;

                    if (input.remaining() < length) {
                        throw Exceptions.newIOException("Truncated fixed-width game proxy field.");
                    }

                    input.position(input.position() + length);
                }
                case ProtobufUtils.WIRE_TYPE_LENGTH_DELIMITED -> {
                    long length = readVariableInteger(input);

                    if (length < 0 || length > input.remaining()) {
                        throw Exceptions.newIOException("Truncated or oversized game proxy field.");
                    }

                    if (fieldNumber == PAYLOAD_FIELD) {
                        payloadOffset = input.position();
                        payloadLength = (int) length;
                    } else if (fieldNumber == ADDRESS_INFORMATION_FIELD) {
                        presentFields |= 1 << ADDRESS_INFORMATION_FIELD;

                        if (addressInformation != null) {
                            try {
                                readAddressInformation(input.slice(input.position(), (int) length), addressInformation);
                            } catch (IOException exception) {
                                addressInformation = null;
                            }
                        }
                    }

                    input.position(input.position() + (int) length);
                }
                default -> throw Exceptions.newIOException("Unsupported game proxy wire type: {0}", wireType);
            }
        }

        return new GameProxyDatagram(values, presentFields, retainedBytes, payloadOffset, payloadLength, addressInformation);
    }

    private static void writeTag(@NotNull ByteArrayOutputStream output, int fieldNumber, int wireType) {
        ProtobufUtils.writeVariableInteger(output, ProtobufUtils.createTag(fieldNumber, wireType));
    }

    /**
     * Merges one occurrence of an {@code AddressInfo} message into the values read so far: a later field replaces an
     * earlier one, the relay flag is stored as {@code 0} or {@code 1} and the other fields keep their low {@code 32} bits.
     *
     * @param input  the encoded message
     * @param values the merged field values, indexed by field number
     * @throws IOException if the message is malformed or unsupported
     */
    private static void readAddressInformation(@NotNull ByteBuffer input, long @NotNull [] values) throws IOException {
        while (input.hasRemaining()) {
            long tag = readVariableInteger(input);

            if (tag <= 0 || tag > MAXIMUM_UNSIGNED_INTEGER || (tag >>> ProtobufUtils.WIRE_TYPE_BITS) == 0) {
                throw Exceptions.newIOException("Invalid game proxy address information tag.");
            }

            int fieldNumber = (int) (tag >>> ProtobufUtils.WIRE_TYPE_BITS);
            int wireType = (int) (tag & ProtobufUtils.WIRE_TYPE_MASK);

            if (wireType == ProtobufUtils.WIRE_TYPE_VARIABLE_INTEGER) {
                long value = readVariableInteger(input);

                if (fieldNumber == RELAYED_FIELD) {
                    values[RELAYED_FIELD] = value == 0 ? 0 : 1;
                } else if (fieldNumber >= RELAY_ADDRESS_FIELD && fieldNumber <= DESTINATION_PORT_FIELD) {
                    values[fieldNumber] = value & DWORD_MASK;
                }
            } else {
                long length = switch (wireType) {
                    case ProtobufUtils.WIRE_TYPE_FIXED_64 -> Long.BYTES;
                    case ProtobufUtils.WIRE_TYPE_LENGTH_DELIMITED -> readVariableInteger(input);
                    case ProtobufUtils.WIRE_TYPE_FIXED_32 -> Integer.BYTES;
                    default -> throw Exceptions.newIOException("Unsupported game proxy address information wire type: {0}", wireType);
                };

                if (length < 0 || length > input.remaining()) {
                    throw Exceptions.newIOException("Truncated game proxy address information.");
                }

                input.position(input.position() + (int) length);
            }
        }
    }

    private static long readVariableInteger(@NotNull ByteBuffer input) throws IOException {
        long value = 0;

        for (int byteIndex = 0; byteIndex < ProtobufUtils.MAXIMUM_VARIABLE_INTEGER_BYTES; byteIndex++) {
            if (!input.hasRemaining()) {
                throw Exceptions.newIOException("Truncated game proxy variable integer.");
            }

            int current = Byte.toUnsignedInt(input.get());

            if (byteIndex == ProtobufUtils.MAXIMUM_VARIABLE_INTEGER_BYTES - 1 && current > 1) {
                throw Exceptions.newIOException("Game proxy variable integer exceeds 64 bits.");
            }

            value |= (long) (current & ProtobufUtils.VARIABLE_INTEGER_MASK) << (byteIndex * ProtobufUtils.VARIABLE_INTEGER_BITS);

            if ((current & ProtobufUtils.VARIABLE_INTEGER_CONTINUATION) == 0) {
                return value;
            }
        }

        throw Exceptions.newIOException("Game proxy variable integer exceeds 64 bits.");
    }


    private final long @NotNull [] values;
    private final int presentFields;

    private final byte @NotNull [] encoded;
    private final int payloadOffset;
    private final int payloadLength;

    private final long @Nullable [] addressInformation;

    private GameProxyDatagram(long @NotNull [] values,
                              int presentFields,
                              byte @NotNull [] encoded,
                              int payloadOffset, int payloadLength,
                              long @Nullable [] addressInformation) {
        this.values        = values;
        this.presentFields = presentFields;

        this.encoded       = encoded;
        this.payloadOffset = payloadOffset;
        this.payloadLength = payloadLength;

        this.addressInformation = addressInformation;
    }

    /**
     * @return the effective message type: field {@code 12} for an empty type {@code 1} payload, else field {@code 1}
     */
    public int getMessageType() {
        return (int) this.values[this.values[TYPE_FIELD] == TYPE_DATA && this.payloadLength == 0 ? ALTERNATE_TYPE_FIELD : TYPE_FIELD];
    }

    /**
     * Reads the merged {@code AddressInfo} field {@code 5} as a boolean.
     * <p>
     * Repeated nested messages merge, so absent flags preserve the preceding value and explicit flags replace it. Opaque
     * malformed nested bytes remain available through {@link #getEncodedBytes()}, but cannot be used to select a receive route.
     * Nested groups are outside this bounded decoder.
     *
     * @return {@code true} if the merged relay flag is nonzero, {@code false} otherwise
     * @throws IOException if nested address information is malformed or unsupported
     */
    public boolean isRelayed() throws IOException {
        return this.requireAddressInformation()[RELAYED_FIELD] != 0;
    }

    /**
     * @return {@code true} if field {@code 11} occurred, {@code false} otherwise
     */
    private boolean hasAddressInformation() {
        return (this.presentFields & (1 << ADDRESS_INFORMATION_FIELD)) != 0;
    }

    /**
     * Reads the merged {@code AddressInfo} fields.
     *
     * @return the merged field values, indexed by field number
     * @throws IOException if nested address information is malformed or unsupported
     */
    private long @NotNull [] requireAddressInformation() throws IOException {
        if (this.addressInformation == null) {
            throw Exceptions.newIOException("Game proxy address information cannot be used for receive routing.");
        }

        return this.addressInformation;
    }

    /**
     * Reads a supported scalar without assigning unverified schema names to the fields.
     *
     * @param fieldNumber the generated field number: {@code 1}, {@code 3}, {@code 4}, {@code 5}, {@code 9} or {@code 12}
     * @return the scalar value, or its generated default when absent
     * @throws IllegalArgumentException if the field number is not a supported scalar
     */
    public long getScalarField(int fieldNumber) {
        if (fieldNumber != TYPE_FIELD && fieldNumber != SCALAR_FIELD_3 && fieldNumber != SCALAR_FIELD_4 && fieldNumber != PAYLOAD_HASH_FIELD &&
            fieldNumber != PROBE_TIMESTAMP_FIELD && fieldNumber != ALTERNATE_TYPE_FIELD) {
            throw Exceptions.newIllegalArgumentException("Not a supported game proxy scalar: {0}", fieldNumber);
        }

        return this.values[fieldNumber];
    }

    /**
     * @return a copy of the last payload field, or an empty array when absent
     */
    public byte @NotNull [] getPayloadBytes() {
        return Arrays.copyOfRange(this.encoded, this.payloadOffset, this.payloadOffset + this.payloadLength);
    }

    /**
     * @return a copy of the exact envelope, including opaque fields and original field order
     */
    public byte @NotNull [] getEncodedBytes() {
        return this.encoded.clone();
    }

    /**
     * Checks whether a correctly typed and recognized scalar occurred in this envelope.
     *
     * @param fieldNumber the generated field number: {@code 1}, {@code 3}, {@code 4}, {@code 5}, {@code 9} or {@code 12}
     * @return {@code true} if the scalar field is present, {@code false} otherwise
     * @throws IllegalArgumentException if the field number is not a supported scalar
     */
    public boolean hasScalarField(int fieldNumber) {
        this.getScalarField(fieldNumber);

        return (this.presentFields & (1 << fieldNumber)) != 0;
    }


    /**
     * Reads a type {@code 1} payload as an ordinary provider message without performing session acceptance.
     *
     * @return the decoded ordinary provider transport message
     * @throws IOException if the effective type is not {@code 1} or the inner provider message is invalid
     */
    public @NotNull ProviderTransportMessage parseProviderMessage() throws IOException {
        if (this.getMessageType() != TYPE_DATA) {
            throw Exceptions.newIOException("Only game proxy type 1 carries an ordinary provider payload through this entry point.");
        }

        return ProviderTransportMessage.parse(this.getPayloadBytes());
    }

    /**
     * Calculates the payload hash over the whole retained envelope for the receive gate.
     *
     * @return the hash
     */
    long calculateReceiveHash() {
        return calculatePayloadHash(this.encoded);
    }
}
