package org.fogofwarlabs.sc4j.scr.net.provider;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Maintains reliable LAN provider packet channels, joins, keep-alives, messages and turns.
 * {@link #receive(InetSocketAddress, byte[])} handles incoming packets; {@link #update()} advances
 * retransmission and acknowledgement timers. Joins advance through {@link #beginJoin} and {@link #pollJoin()}.
 * <p>
 * Time comes from the supplied 32-bit millisecond clock and elapsed comparisons use unsigned differences.
 * Player information carries a {@code 16}-byte IPv4 provider address so remote peers can communicate directly.
 * This mutable session requires one owning thread or external synchronization.
 */
public final class ProviderSession {
    /**
     * The provider message limit after its {@code 16}-byte reserve.
     */
    public static final int MAXIMUM_MESSAGE_SIZE = 496;
    /**
     * The provider's bandwidth in bytes per second ({@code 'UDPA'} caps).
     */
    public static final int BYTES_PER_SECOND = 100000;
    /**
     * The provider's latency in milliseconds ({@code 'UDPA'} caps).
     */
    public static final int DEFAULT_LATENCY = 50;
    /**
     * The provider's default turns per second ({@code 'UDPA'} caps).
     */
    public static final int DEFAULT_TURNS_PER_SECOND = 24;
    /**
     * The provider's default turns in transit ({@code 'UDPA'} caps).
     */
    public static final int DEFAULT_TURNS_IN_TRANSIT = 2;
    /**
     * The default keep-alive interval of a session, which a lobby keeps, in milliseconds.
     */
    public static final int DEFAULT_KEEP_ALIVE_INTERVAL = 5000;

    /**
     * Event: the game data of subtype {@code 9} arrived; the data is the game's data, the identifier the local player's.
     */
    public static final int EVENT_GAME_DATA = 1;
    /**
     * Event: a player joined; the identifier is the new player's.
     */
    public static final int EVENT_PLAYER_JOINED = 2;
    /**
     * Event: a departed player reached its departure turn; the data is the departure reason (DWORD).
     */
    public static final int EVENT_PLAYER_LEFT = 3;

    /**
     * Turn status: the slot has a player.
     */
    public static final int TURN_PRESENT = 0x10000;
    /**
     * Turn status: the player's turn is ready.
     */
    public static final int TURN_READY = 0x20000;
    /**
     * Turn status: the player is responsive: the local player, heard from within the rejoin window, or departed with its turn ready.
     */
    public static final int TURN_RESPONSIVE = 0x40000;

    /**
     * Game state bit: the game has a password.
     */
    public static final int STATE_PASSWORD = 1;
    /**
     * Game state bit: the game is full.
     */
    public static final int STATE_FULL = 2;
    /**
     * Game state bit set once the game is advertised.
     */
    public static final int STATE_ADVERTISED = 4;
    /**
     * Game state bit: the game started; joins are refused.
     */
    public static final int STATE_STARTED = 8;

    /**
     * Control subtype: the join request; it always has sequence {@code 0}.
     */
    public static final int SUBTYPE_JOIN_REQUEST = 1;
    /**
     * Control subtype: the host's answer to the join request.
     */
    public static final int SUBTYPE_JOIN_ACCEPT = 2;
    /**
     * Control subtype: the joiner's confirmation of the answer.
     */
    public static final int SUBTYPE_JOIN_CONFIRM = 3;
    /**
     * Control subtype: a keep-alive ping.
     */
    public static final int SUBTYPE_PING = 4;
    /**
     * Control subtype: the answer to a keep-alive ping.
     */
    public static final int SUBTYPE_PONG = 5;
    /**
     * Control subtype: a player's information.
     */
    public static final int SUBTYPE_PLAYER_INFORMATION = 6;
    /**
     * Control subtype: the joiner's request for a player slot (name, statistics, password).
     */
    public static final int SUBTYPE_PLAYER_REQUEST = 7;
    /**
     * Control subtype: the game information sent to a joiner.
     */
    public static final int SUBTYPE_GAME_INFORMATION = 8;
    /**
     * Control subtype: the game data sent to a joiner.
     */
    public static final int SUBTYPE_GAME_DATA = 9;
    /**
     * Control subtype: the host refused the player.
     */
    public static final int SUBTYPE_REFUSAL = 10;
    /**
     * Control subtype: the sender leaves the game.
     */
    public static final int SUBTYPE_LEAVE = 11;
    /**
     * Control subtype: departure information about another player.
     */
    public static final int SUBTYPE_DEPARTURE = 12;
    /**
     * Control subtype: the sender announces itself as host.
     */
    public static final int SUBTYPE_HOST = 13;
    /**
     * Control subtype: the host's new game state bits.
     */
    public static final int SUBTYPE_GAME_STATE = 14;
    /**
     * Control subtype: the game value of {@link #createGame}.
     */
    public static final int SUBTYPE_GAME_VALUE = 15;

    private static final @NotNull Logger LOGGER = Logger.of(ProviderSession.class);

    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;

    /**
     * The message target of {@link #sendMessage} that names every player including the local one.
     */
    private static final int TARGET_EVERY_PLAYER = -1;

    private static final int MAXIMUM_KEEP_ALIVE_INTERVAL = 20000;
    private static final int UNKNOWN_PEER_TIMEOUT = 50000;
    private static final int MILLISECONDS_PER_SECOND = 1000;

    private static final int SUBTYPE_COUNT = 16;
    private static final int NAME_LIMIT = 127;

    /**
     * The turn acknowledgement delay is this many latencies, at least {@link #MINIMUM_ACKNOWLEDGEMENT_DELAY_TURNS}.
     */
    private static final int ACKNOWLEDGEMENT_DELAY_TURNS_FACTOR = 5;
    private static final int MINIMUM_ACKNOWLEDGEMENT_DELAY_TURNS = 250;
    /**
     * The handshake timeout is this many latencies, at least {@link #MINIMUM_HANDSHAKE_TIMEOUT}.
     */
    private static final int HANDSHAKE_TIMEOUT_FACTOR = 12;
    private static final int MINIMUM_HANDSHAKE_TIMEOUT = 5000;
    /**
     * The rejoin window is this many latencies, at least {@link #MINIMUM_REJOIN_WINDOW}.
     */
    private static final int REJOIN_WINDOW_FACTOR = 4;
    private static final int MINIMUM_REJOIN_WINDOW = 1000;
    private static final int MINIMUM_ACKNOWLEDGEMENT_DELAY = 25;
    private static final int MINIMUM_REQUEST_INTERVAL = 50;
    /**
     * The bytes added to the provider's message limit and the packet size in the retransmission timeout.
     */
    private static final int RESEND_SIZE_ALLOWANCE = 0x80;
    /**
     * The milliseconds added to the request interval in the retransmission timeout.
     */
    private static final int RESEND_INTERVAL_ALLOWANCE = 100;
    /**
     * The number of padded request intervals in the retransmission timeout.
     */
    private static final int RESEND_INTERVAL_FACTOR = 2;
    /**
     * The bytes added to a packet's size when the send budget advances.
     */
    private static final int TRANSMIT_OVERHEAD = 0x40;

    /**
     * The class {@code 0} sequence both sides reset to on a join; the join request itself has sequence {@code 0}.
     */
    private static final int JOIN_CONTROL_SEQUENCE = 1;
    /**
     * The DWORD the join request and its answer carry.
     */
    private static final int JOIN_VALUE = 1;
    private static final int PLAYER_REQUEST_CAPACITY = 64;

    /**
     * Player information: size, identifier, host flag, flags and turn DWORDs, the provider address, then the strings.
     */
    private static final int PLAYER_INFORMATION_IDENTIFIER_OFFSET = 4;
    private static final int PLAYER_INFORMATION_HOST_OFFSET = 8;
    private static final int PLAYER_INFORMATION_FLAGS_OFFSET = 0x0C;
    private static final int PLAYER_INFORMATION_TURN_OFFSET = 0x10;
    private static final int PLAYER_INFORMATION_ADDRESS_OFFSET = 0x14;
    private static final int PLAYER_INFORMATION_HEADER = 0x24;
    private static final int PLAYER_INFORMATION_CAPACITY = 64;

    /**
     * Game information: identifier, player limit, turn, state and elapsed seconds DWORDs, then the strings.
     */
    private static final int GAME_INFORMATION_PLAYER_LIMIT_OFFSET = 4;
    private static final int GAME_INFORMATION_TURN_OFFSET = 8;
    private static final int GAME_INFORMATION_STATE_OFFSET = 12;
    private static final int GAME_INFORMATION_ELAPSED_OFFSET = 16;
    private static final int GAME_INFORMATION_HEADER = 20;
    private static final int GAME_INFORMATION_CAPACITY = 128;

    /**
     * Leave (subtype {@code 11}): the turn and the reason DWORDs.
     */
    private static final int LEAVE_REASON_OFFSET = 4;
    private static final int LEAVE_SIZE = 8;
    /**
     * Departure (subtype {@code 12}): the player, the turn and the reason DWORDs.
     */
    private static final int DEPARTURE_TURN_OFFSET = 4;
    private static final int DEPARTURE_REASON_OFFSET = 8;
    private static final int DEPARTURE_SIZE = 12;

    /**
     * The provider address: a {@code sockaddr_in} with the family WORD, the port and the IPv4 address, zero padded.
     */
    private static final int ADDRESS_SIZE = 16;
    private static final int ADDRESS_FAMILY_INET = 2;
    private static final int ADDRESS_PORT_OFFSET = 2;
    private static final int ADDRESS_IPV4_OFFSET = 4;
    private static final int IPV4_SIZE = 4;

    /**
     * Sends provider packets.
     */
    @FunctionalInterface
    public interface Transport {
        /**
         * Sends one provider packet.
         *
         * @param address the destination
         * @param packet  the packet
         */
        void send(@NotNull InetSocketAddress address, @NotNull ProviderPacket packet);
    }

    /**
     * The state of a join started with {@link #beginJoin}.
     */
    public enum JoinState {
        /**
         * No join is in progress.
         */
        IDLE,
        /**
         * Waiting for subtype {@code 2} after the join request (subtype {@code 1}).
         */
        CONNECTING,
        /**
         * Waiting for subtype {@code 9} or {@code 10} after the player's request (subtype {@code 7}).
         */
        REQUESTING,
        /**
         * Joined: the game data arrived and the local player has an identifier.
         */
        JOINED,
        /**
         * The host refused the player (subtype {@code 10}).
         */
        REJECTED,
        /**
         * A wait exceeded its time limit.
         */
        TIMED_OUT
    }

    /**
     * A session event for the game layer.
     */
    public static final class Event {
        private final int type;
        private final int playerIdentifier;
        private final byte @NotNull [] data;

        private Event(int type, int playerIdentifier, byte @NotNull [] data) {
            this.type             = type;
            this.playerIdentifier = playerIdentifier;
            this.data             = data;
        }

        /**
         * @return {@link #EVENT_GAME_DATA}, {@link #EVENT_PLAYER_JOINED} or {@link #EVENT_PLAYER_LEFT}
         */
        public int getType() {
            return this.type;
        }

        /**
         * @return the player identifier
         */
        public int getPlayerIdentifier() {
            return this.playerIdentifier;
        }

        /**
         * @return a copy of the data
         */
        public byte @NotNull [] getData() {
            return this.data.clone();
        }
    }

    /**
     * The turns of one turn step ({@link #receiveTurns}).
     */
    public static final class TurnBatch {
        private final byte @NotNull [] @Nullable [] turns;
        private final int @NotNull [] status;
        private final boolean complete;

        private TurnBatch(byte @NotNull [] @Nullable [] turns, int @NotNull [] status, boolean complete) {
            this.turns    = turns;
            this.status   = status;
            this.complete = complete;
        }

        /**
         * @param slot the slot, {@code 0..count-1}
         * @return a copy of the slot's turn, or {@code null} if the slot has no player or nothing was consumed
         */
        public byte @Nullable [] getTurn(int slot) {
            byte[] turn = this.turns[slot];

            return turn == null ? null : turn.clone();
        }

        /**
         * @param slot the slot, {@code 0..count-1}
         * @return the slot's status bits ({@link #TURN_PRESENT}, {@link #TURN_READY}, {@link #TURN_RESPONSIVE})
         */
        public int getStatus(int slot) {
            return this.status[slot];
        }

        /**
         * @return {@code true} if every present player's turn was ready and the turn was consumed, {@code false} otherwise
         */
        public boolean isComplete() {
            return this.complete;
        }
    }

    /**
     * A received game message (class {@code 1}).
     */
    public static final class Message {
        private final int sender;
        private final byte @NotNull [] data;

        private Message(int sender, byte @NotNull [] data) {
            this.sender = sender;
            this.data   = data;
        }

        /**
         * @return the sender's player identifier
         */
        public int getSender() {
            return this.sender;
        }

        /**
         * @return a copy of the message
         */
        public byte @NotNull [] getData() {
            return this.data.clone();
        }
    }

    /**
     * Encodes a {@code 16}-byte IPv4 provider address: family {@code 2} in little-endian order, the port and IPv4 address
     * in network byte order, and eight trailing zero bytes.
     *
     * @param address the address, {@code null} for none
     * @return the {@code 16} bytes, zeros without an IPv4 address
     */
    static byte @NotNull [] encodeAddress(@Nullable InetSocketAddress address) {
        byte[] encoded = new byte[ADDRESS_SIZE];

        if (address == null || address.getAddress() == null || address.getAddress().getAddress().length != IPV4_SIZE) {
            return encoded;
        }

        encoded[0]                       = ADDRESS_FAMILY_INET;
        encoded[ADDRESS_PORT_OFFSET]     = (byte) (address.getPort() >> Byte.SIZE);
        encoded[ADDRESS_PORT_OFFSET + 1] = (byte) address.getPort();

        System.arraycopy(address.getAddress().getAddress(), 0, encoded, ADDRESS_IPV4_OFFSET, IPV4_SIZE);

        return encoded;
    }

    /**
     * Decodes the address of {@link #encodeAddress}.
     *
     * @param data the player-information bytes
     * @return the address, or {@code null} unless the family is {@code 2} and port and address are not zero
     */
    static @Nullable InetSocketAddress decodePlayerInformationAddress(byte @NotNull [] data) {
        if (data.length < PLAYER_INFORMATION_ADDRESS_OFFSET + ADDRESS_SIZE ||
            LittleEndianUtils.readUnsigned16(data, PLAYER_INFORMATION_ADDRESS_OFFSET) != ADDRESS_FAMILY_INET) {
            return null;
        }

        int port = (data[PLAYER_INFORMATION_ADDRESS_OFFSET + ADDRESS_PORT_OFFSET] & BYTE_MASK) << Byte.SIZE |
                   data[PLAYER_INFORMATION_ADDRESS_OFFSET + ADDRESS_PORT_OFFSET + 1] & BYTE_MASK;
        byte[] addressBytes = Arrays.copyOfRange(data, PLAYER_INFORMATION_ADDRESS_OFFSET + ADDRESS_IPV4_OFFSET,
                                                 PLAYER_INFORMATION_ADDRESS_OFFSET + ADDRESS_IPV4_OFFSET + IPV4_SIZE);
        int addressBits = 0;

        for (byte value : addressBytes) {
            addressBits |= value;
        }

        if (port == 0 || addressBits == 0) {
            return null;
        }

        try {
            return new InetSocketAddress(InetAddress.getByAddress(addressBytes), port);
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    /**
     * Finds a turn in the queue, else in the history, as the resend paths search.
     *
     * @param channel  the turn channel to search
     * @param sequence the unsigned 16-bit sequence of the turn
     * @return the turn, or {@code null} if neither the queue nor the history holds it
     */
    private static @Nullable ProviderPacket findTurn(@NotNull ProviderChannel channel, int sequence) {
        for (ProviderChannel.Received received : channel.getReceivedPackets()) {
            if (received.getPacket().getSequence() == sequence) {
                return received.getPacket();
            }
        }

        for (ProviderPacket packet : channel.getHistoryPackets()) {
            if (packet.getSequence() == sequence) {
                return packet;
            }
        }

        return null;
    }

    /**
     * Encodes subtype {@code 6} about a player (also the host's own entry): size, identifier, host flag, flags, turn, the
     * peer's {@code 16}-byte provider address (zero for the local player), name, statistics.
     *
     * @param peer the described player
     * @param turn the turn the player information announces
     * @return the encoded player information
     */
    private static byte @NotNull [] encodePlayerInformation(@NotNull ProviderPeer peer, int turn) {
        ByteArrayOutputStream information = new ByteArrayOutputStream(PLAYER_INFORMATION_CAPACITY);

        writeInt32(information, 0);
        writeInt32(information, peer.getIdentifier());
        writeInt32(information, peer.isHost() ? 1 : 0);
        writeInt32(information, peer.getFlags());
        writeInt32(information, turn);
        information.writeBytes(encodeAddress(peer.getAddress()));
        writeString(information, peer.getName());
        writeString(information, peer.getStatistics());

        byte[] encoded = information.toByteArray();

        LittleEndianUtils.writeInt32(encoded, 0, encoded.length);

        return encoded;
    }

    private static @NotNull String limitTextBytes(@NotNull String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        return bytes.length <= NAME_LIMIT ? text : new String(bytes, 0, NAME_LIMIT, StandardCharsets.UTF_8);
    }

    private static int findZeroByteIndex(byte @NotNull [] data, int start) {
        for (int index = start; index < data.length; index++) {
            if (data[index] == 0) {
                return index;
            }
        }

        return data.length;
    }

    /**
     * Encodes the departure information of subtype {@code 12}: the player, the turn and the reason DWORDs.
     *
     * @param player the departed player's identifier
     * @param turn   the departure turn
     * @param reason the departure reason
     * @return the encoded departure information
     */
    private static byte @NotNull [] encodeDeparture(int player, int turn, int reason) {
        ByteBuffer departure = ByteBuffer.allocate(DEPARTURE_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        departure.putInt(player).putInt(turn).putInt(reason);

        return departure.array();
    }

    /**
     * Checks whether a player's starting turn lies ahead of the local player's next turn to consume within the unsigned
     * half range, so that the player joins late.
     *
     * @param turn        the player's starting turn
     * @param ownExpected the local player's next turn to consume
     * @return {@code true} if the player's turns start later, {@code false} otherwise
     */
    private static boolean isTurnAhead(int turn, int ownExpected) {
        return turn != ownExpected && ((turn - ownExpected) & WORD_MASK) < ProviderChannel.SEQUENCE_HALF_RANGE;
    }

    private static int readInt32(byte @NotNull [] data, int offset) {
        return offset + Integer.BYTES <= data.length ? LittleEndianUtils.readInt32(data, offset) : 0;
    }

    private static byte @NotNull [] createInt32Bytes(int value) {
        byte[] bytes = new byte[Integer.BYTES];

        LittleEndianUtils.writeInt32(bytes, 0, value);

        return bytes;
    }

    private static void writeInt32(@NotNull ByteArrayOutputStream output, int value) {
        output.writeBytes(createInt32Bytes(value));
    }

    private static void writeString(@NotNull ByteArrayOutputStream output, @NotNull String text) {
        output.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        output.write(0);
    }


    private final @NotNull Transport transport;
    private final @NotNull IntSupplier clock;

    private final @NotNull ProviderPeer self;
    private final @NotNull List<ProviderPeer> peers;
    private final boolean @NotNull [] receivedSubtypes;
    private final @NotNull ArrayDeque<Event> events;
    private int localIdentifier;
    private boolean joiningAnyPeer;

    private int acknowledgementDelayTurns;
    private int handshakeTimeout;
    private int rejoinWindow;
    private int acknowledgementDelay;
    private int requestInterval;
    private int keepAliveInterval;
    private int sendBudget;

    private @NotNull String gameName;
    private @NotNull String gameStatistics;
    private @NotNull String password;
    private int maximumPlayers;
    private int reservedSlots;
    private int maximumObservers;
    private int observers;
    private int gameState;
    private int gameValue;
    private byte @NotNull [] gameData;
    private int startTime;

    private @NotNull JoinState joinState;
    private @Nullable ProviderPeer joinPeer;
    private int joinPhaseTime;
    private @NotNull String joinName;
    private @NotNull String joinStatistics;
    private @NotNull String joinPassword;

    /**
     * Creates a {@link ProviderSession} instance with the provider's default latency.
     *
     * @param transport the packet sender
     * @param clock     the millisecond tick
     */
    public ProviderSession(@NotNull Transport transport, @NotNull IntSupplier clock) {
        this.transport = transport;
        this.clock     = clock;

        this.self             = new ProviderPeer(null, clock.getAsInt());
        this.peers            = new ArrayList<>();
        this.receivedSubtypes = new boolean[SUBTYPE_COUNT];
        this.events           = new ArrayDeque<>();
        this.localIdentifier  = ProviderPeer.UNKNOWN_IDENTIFIER;
        this.joiningAnyPeer   = false;

        this.setLatency(DEFAULT_LATENCY);
        this.keepAliveInterval = DEFAULT_KEEP_ALIVE_INTERVAL;
        this.sendBudget        = 0;

        this.gameName         = "";
        this.gameStatistics   = "";
        this.password         = "";
        this.maximumPlayers   = 0;
        this.reservedSlots    = 0;
        this.maximumObservers = 0;
        this.observers        = 0;
        this.gameState        = 0;
        this.gameValue        = 0;
        this.gameData         = new byte[0];
        this.startTime        = 0;

        this.joinState      = JoinState.IDLE;
        this.joinPeer       = null;
        this.joinPhaseTime  = 0;
        this.joinName       = "";
        this.joinStatistics = "";
        this.joinPassword   = "";
    }

    /**
     * Sets the latency and the timeouts derived from it.
     *
     * @param latency the latency in milliseconds
     */
    public void setLatency(int latency) {
        this.acknowledgementDelayTurns = Math.max(latency * ACKNOWLEDGEMENT_DELAY_TURNS_FACTOR, MINIMUM_ACKNOWLEDGEMENT_DELAY_TURNS);
        this.handshakeTimeout          = Math.max(latency * HANDSHAKE_TIMEOUT_FACTOR, MINIMUM_HANDSHAKE_TIMEOUT);
        this.rejoinWindow              = Math.max(latency * REJOIN_WINDOW_FACTOR, MINIMUM_REJOIN_WINDOW);
        this.acknowledgementDelay      = Math.max(latency >>> 1, MINIMUM_ACKNOWLEDGEMENT_DELAY);
        this.requestInterval           = Math.max(latency, MINIMUM_REQUEST_INTERVAL);
    }

    /**
     * Sets the keep-alive interval: an interval above {@code 20} seconds (compared unsigned) sets {@code 20} seconds; {@code 0} leaves the
     * interval unchanged.
     *
     * @param interval the interval in milliseconds
     */
    public void setKeepAliveInterval(int interval) {
        if (interval == 0) {
            return;
        }

        this.keepAliveInterval = Integer.compareUnsigned(interval, MAXIMUM_KEEP_ALIVE_INTERVAL) < 0 ? interval : MAXIMUM_KEEP_ALIVE_INTERVAL;
    }

    /**
     * @return the session clock's current time in milliseconds (the time packets and peers are stamped with)
     */
    public int getTime() {
        return this.clock.getAsInt();
    }

    /**
     * @return the local player's identifier, {@link ProviderPeer#UNKNOWN_IDENTIFIER} outside a game
     */
    public int getLocalIdentifier() {
        return this.localIdentifier;
    }

    /**
     * @return the local player's record
     */
    public @NotNull ProviderPeer getSelf() {
        return this.self;
    }

    /**
     * @return the remote peers, including those without a player slot
     */
    public @NotNull List<ProviderPeer> getPeers() {
        return List.copyOf(this.peers);
    }

    /**
     * @return the game state bits ({@link #STATE_PASSWORD}, {@link #STATE_FULL}, ...)
     */
    public int getGameState() {
        return this.gameState;
    }

    /**
     * Sets the game state as host and sends it to every player (subtype {@code 14}).
     *
     * @param state the new state bits
     */
    public void setGameState(int state) {
        if (!this.self.isHost()) {
            return;
        }

        this.gameState = state;

        if ((state & STATE_PASSWORD) == 0) {
            this.password = "";
        }

        for (ProviderPeer peer : List.copyOf(this.peers)) {
            if (peer.getIdentifier() != ProviderPeer.UNKNOWN_IDENTIFIER) {
                this.send(peer, ProviderPacket.CONTROL, SUBTYPE_GAME_STATE, createInt32Bytes(state));
            }
        }
    }

    /**
     * @return the game name
     */
    public @NotNull String getGameName() {
        return this.gameName;
    }

    /**
     * @return the game's statistics string
     */
    public @NotNull String getGameStatistics() {
        return this.gameStatistics;
    }

    /**
     * Sets the game's statistics string that the game information (subtype {@code 8}) gives every later joiner. At most
     * {@code 127} bytes are kept, as {@link #createGame} keeps.
     *
     * @param statistics the game's statistics string
     */
    public void setGameStatistics(@NotNull String statistics) {
        this.gameStatistics = limitTextBytes(statistics);
    }

    /**
     * @return the maximum number of players
     */
    public int getMaximumPlayers() {
        return this.maximumPlayers;
    }

    /**
     * @return the players the game counts: the local player and every remote player with an identifier, less the observers
     * present, plus the reserved slots
     */
    public int getCurrentPlayers() {
        return this.countPresentPlayers() - this.observers + this.reservedSlots;
    }

    /**
     * @return the observers present: those of {@link #setSlotCounts}, plus the observers joined since
     */
    public int getObservers() {
        return this.observers;
    }

    /**
     * @return the observer limit: the maximum number of observers of {@link #createGame} or the limit of {@link #setSlotCounts}
     */
    public int getMaximumObservers() {
        return this.maximumObservers;
    }

    /**
     * Sets the game's lobby slot counts: the reserved player slots (the computer slots: the join request holds that many of
     * the highest free player identifiers back, so a player joining a lobby whose open slots are all taken gets an observer
     * identifier from {@code 0x80}), the observers present and the observer limit. The closed slots reach the game information through the
     * game statistics.
     *
     * @param reserved      the reserved player slots
     * @param observers     the observers present
     * @param observerLimit the observer limit
     */
    public void setSlotCounts(int reserved, int observers, int observerLimit) {
        this.reservedSlots    = reserved;
        this.observers        = observers;
        this.maximumObservers = observerLimit;
    }

    /**
     * @return a copy of the game data (subtype {@code 9})
     */
    public byte @NotNull [] getGameData() {
        return this.gameData.clone();
    }

    /**
     * @return the state of the current join
     */
    public @NotNull JoinState getJoinState() {
        return this.joinState;
    }

    /**
     * @return the local turns sent and not yet received back: the own turn channel's next outgoing sequence minus its expected sequence
     */
    public int getTurnsInTransit() {
        ProviderChannel ownTurns = this.self.getChannel(ProviderPacket.TURN);

        return (ownTurns.getNextOutgoingSequence() - ownTurns.getExpectedSequence()) & WORD_MASK;
    }


    /**
     * Removes the oldest session event.
     *
     * @return the event, or {@code null} if none is pending
     */
    public @Nullable Event pollEvent() {
        return this.events.pollFirst();
    }

    /**
     * Hosts a game: the local player becomes identifier {@code 0} and host.
     *
     * @param name             the game name
     * @param statistics       the game's statistics string
     * @param gamePassword     the password, empty for none
     * @param playerLimit      the maximum number of players
     * @param observerLimit    the maximum number of observers
     * @param state            the initial game state bits from the game
     * @param value            the value sent with subtype {@code 15} (meaning unverified)
     * @param data             the game data sent with subtype {@code 9}
     * @param playerName       the local player's name
     * @param playerStatistics the local player's statistics string
     */
    public void createGame(@NotNull String name, @NotNull String statistics, @NotNull String gamePassword,
                           int playerLimit, int observerLimit,
                           int state, int value, byte @NotNull [] data,
                           @NotNull String playerName, @NotNull String playerStatistics) {
        this.maximumPlayers   = playerLimit;
        this.reservedSlots    = 0;
        this.observers        = 0;
        this.maximumObservers = observerLimit;
        this.gameName         = limitTextBytes(name);
        this.gameStatistics   = limitTextBytes(statistics);
        this.password         = limitTextBytes(gamePassword);
        this.startTime        = this.clock.getAsInt();
        this.gameState        = state;

        if (!gamePassword.isEmpty()) {
            this.gameState |= STATE_PASSWORD;
        }

        if (Integer.compareUnsigned(playerLimit, 1) <= 0) {
            this.gameState |= STATE_FULL;
        }

        this.gameValue       = value;
        this.gameData        = data.clone();
        this.localIdentifier = 0;

        this.self.setIdentifier(0);
        this.self.setFlags(0);
        this.self.setHost(true);
        this.self.setName(limitTextBytes(playerName));
        this.self.setStatistics(limitTextBytes(playerStatistics));

        this.gameState |= STATE_ADVERTISED;
    }

    /**
     * Starts joining a game: the join request (class {@code 0} subtype {@code 1}) goes out three times, then
     * {@link #pollJoin()} waits for the host's answers.
     *
     * @param host             the host's address
     * @param playerName       the local player's name
     * @param playerStatistics the local player's statistics string
     * @param gamePassword     the password, empty for none
     */
    public void beginJoin(@NotNull InetSocketAddress host,
                          @NotNull String playerName, @NotNull String playerStatistics,
                          @NotNull String gamePassword) {
        this.joiningAnyPeer = true;

        ProviderPeer peer = this.findOrCreatePeer(host);

        peer.setJoining(true);
        peer.getChannel(ProviderPacket.CONTROL).reset(JOIN_CONTROL_SEQUENCE);

        ProviderPacket request = this.send(peer, ProviderPacket.CONTROL, SUBTYPE_JOIN_REQUEST, createInt32Bytes(JOIN_VALUE));

        if (request != null) {
            this.resend(peer, request);
            this.resend(peer, request);
        }

        peer.getChannel(ProviderPacket.CONTROL).removeAcknowledged(JOIN_CONTROL_SEQUENCE, this.clock.getAsInt());
        Arrays.fill(this.receivedSubtypes, false);

        this.joinState      = JoinState.CONNECTING;
        this.joinPeer       = peer;
        this.joinPhaseTime  = this.clock.getAsInt();
        this.joinName       = limitTextBytes(playerName);
        this.joinStatistics = limitTextBytes(playerStatistics);
        this.joinPassword   = limitTextBytes(gamePassword);
    }

    /**
     * Advances a join started with {@link #beginJoin} by checking received subtypes and phase timeouts.
     *
     * @return the join state
     */
    public @NotNull JoinState pollJoin() {
        ProviderPeer peer = this.joinPeer;

        if (peer == null) {
            return this.joinState;
        }

        int now = this.clock.getAsInt();

        switch (this.joinState) {
            case CONNECTING -> {
                if (this.receivedSubtypes[SUBTYPE_JOIN_ACCEPT]) {
                    ByteArrayOutputStream request = new ByteArrayOutputStream(PLAYER_REQUEST_CAPACITY);

                    writeString(request, this.joinName);
                    writeString(request, this.joinStatistics);
                    writeString(request, this.joinPassword);
                    this.send(peer, ProviderPacket.CONTROL, SUBTYPE_PLAYER_REQUEST, request.toByteArray());
                    Arrays.fill(this.receivedSubtypes, false);

                    this.joinState     = JoinState.REQUESTING;
                    this.joinPhaseTime = now;
                } else if (Integer.compareUnsigned(now - this.joinPhaseTime, this.handshakeTimeout) >= 0) {
                    this.finishJoin(peer, JoinState.TIMED_OUT);
                }
            }
            case REQUESTING -> {
                if (this.receivedSubtypes[SUBTYPE_GAME_DATA] || this.receivedSubtypes[SUBTYPE_REFUSAL]) {
                    if (this.localIdentifier == ProviderPeer.UNKNOWN_IDENTIFIER) {
                        this.finishJoin(peer, JoinState.REJECTED);
                    } else {
                        this.self.setIdentifier(this.localIdentifier);
                        this.self.setName(this.joinName);
                        this.self.setStatistics(this.joinStatistics);
                        this.finishJoin(peer, JoinState.JOINED);
                    }
                } else if (Integer.compareUnsigned(now - this.joinPhaseTime, this.handshakeTimeout) >= 0) {
                    this.finishJoin(peer, JoinState.TIMED_OUT);
                }
            }
            default -> {
            }
        }

        return this.joinState;
    }

    private void finishJoin(@NotNull ProviderPeer peer, @NotNull JoinState state) {
        peer.setJoining(false);

        this.joiningAnyPeer = false;
        this.joinState      = state;
        this.joinPeer       = null;

        if (state != JoinState.JOINED) {
            LOGGER.info("LAN JOIN FAILED: " + state);
        }
    }

    /**
     * Sends a game message (class {@code 1}).
     *
     * @param target the player identifier, {@code -1} for every player including the local one, {@code -2} for every other player
     * @param data   the message
     * @return {@code true} if the target exists, {@code false} otherwise
     */
    public boolean sendMessage(int target, byte @NotNull [] data) {
        if (this.localIdentifier == ProviderPeer.UNKNOWN_IDENTIFIER) {
            return false;
        }

        if (target >= 0) {
            // The player's peer, the local player's own included (its message goes to its own queue).
            ProviderPeer peer = target == this.localIdentifier ? this.self : this.findPeerByIdentifier(target);

            if (peer == null) {
                return false;
            }

            this.send(peer, ProviderPacket.MESSAGE, 0, data);

            return true;
        }

        for (ProviderPeer peer : List.copyOf(this.peers)) {
            if (peer.getIdentifier() != ProviderPeer.UNKNOWN_IDENTIFIER) {
                this.send(peer, ProviderPacket.MESSAGE, 0, data);
            }
        }

        if (target == TARGET_EVERY_PLAYER) {
            this.send(this.self, ProviderPacket.MESSAGE, 0, data);
        }

        return true;
    }

    /**
     * Receives the next game message: of the players whose next message is in order, the one whose queue has waited
     * longest. The local queue is considered last and a later candidate wins an equal wait.
     *
     * @return the message, or {@code null} if none is ready
     */
    public @Nullable Message receiveMessage() {
        if (this.localIdentifier == ProviderPeer.UNKNOWN_IDENTIFIER) {
            return null;
        }

        int now = this.clock.getAsInt();
        ProviderPeer chosen = null;
        int longestWait = 0;
        List<ProviderPeer> candidates = new ArrayList<>(this.peers);

        candidates.add(this.self);

        for (ProviderPeer peer : candidates) {
            if (peer.getIdentifier() == ProviderPeer.UNKNOWN_IDENTIFIER || (peer.getFlags() & ProviderPeer.FLAG_JOINED_LATE) != 0) {
                continue;
            }

            ProviderChannel channel = peer.getChannel(ProviderPacket.MESSAGE);

            if (!channel.hasExpectedPacket()) {
                continue;
            }

            List<ProviderChannel.Received> received = channel.getReceivedPackets();
            int oldest = received.getFirst().getArrivalTime();

            for (int index = 1; index < received.size(); index++) {
                int arrival = received.get(index).getArrivalTime();

                oldest = Integer.compareUnsigned(oldest - arrival, ProviderChannel.TICK_HALF_RANGE) >= 0 ? oldest : arrival - 1;
            }

            int waitTime = now - oldest;

            if (Integer.compareUnsigned(waitTime, longestWait) >= 0) {
                longestWait = waitTime;
                chosen      = peer;
            }
        }

        if (chosen == null) {
            return null;
        }

        ProviderPacket packet = chosen.getChannel(ProviderPacket.MESSAGE).pollExpectedPacket();

        return packet == null ? null : new Message(chosen.getIdentifier(), packet.getPayloadBytes());
    }

    /**
     * Sends the local player's turn (class {@code 2}) to every player whose next turn is the local player's, then to the local
     * player's own queue.
     *
     * @param data the turn's commands
     */
    public void sendTurn(byte @NotNull [] data) {
        if (this.localIdentifier == ProviderPeer.UNKNOWN_IDENTIFIER) {
            return;
        }

        int nextTurn = this.self.getChannel(ProviderPacket.TURN).getNextOutgoingSequence();

        for (ProviderPeer peer : List.copyOf(this.peers)) {
            if (peer.getIdentifier() != ProviderPeer.UNKNOWN_IDENTIFIER &&
                peer.getChannel(ProviderPacket.TURN).getNextOutgoingSequence() == nextTurn) {
                this.send(peer, ProviderPacket.TURN, 0, data);
            }
        }

        this.send(this.self, ProviderPacket.TURN, 0, data);
    }

    /**
     * Receives the next turn of every slot.
     * <p>
     * Before the barrier it applies reached departures, lets players who joined late take part from the turn they joined
     * at and maintains the turn history. Slot {@code index} maps to player {@code firstPlayer + index}; the last
     * {@code observerSlots} slots map to observer identifiers from {@link PlayerTable#FIRST_OBSERVER_SLOT}. When a present
     * player's turn is missing nothing is consumed and the player is asked for it at most every request interval
     * ({@link ProviderPacket#FLAG_RESEND_REQUEST} to the player and, with the player's identifier, to everyone else for a
     * relay).
     *
     * @param firstPlayer   the first slot's player identifier
     * @param count         the number of slots
     * @param observerSlots the number of slots at the end that map to observers
     * @return the batch; {@link TurnBatch#isComplete()} tells whether the turn was consumed
     */
    public @NotNull TurnBatch receiveTurns(int firstPlayer, int count, int observerSlots) {
        byte[][] turns = new byte[count][];
        int[] status = new int[count];

        if (this.localIdentifier == ProviderPeer.UNKNOWN_IDENTIFIER) {
            return new TurnBatch(turns, status, false);
        }

        this.processDepartures();

        ProviderChannel ownTurns = this.self.getChannel(ProviderPacket.TURN);
        int turn = ownTurns.getExpectedSequence();

        for (ProviderPeer peer : this.peers) {
            if ((peer.getFlags() & ProviderPeer.FLAG_JOINED_LATE) != 0 && peer.getChannel(ProviderPacket.TURN).getExpectedSequence() == turn) {
                peer.setFlags(peer.getFlags() & ~ProviderPeer.FLAG_JOINED_LATE);
                this.events.addLast(new Event(EVENT_PLAYER_JOINED, peer.getIdentifier(), new byte[0]));
            }
        }

        this.pruneTurnHistory(turn);

        int now = this.clock.getAsInt();
        int observerStart = count - observerSlots;
        ProviderPeer[] players = new ProviderPeer[count];
        boolean ready = true;

        for (int slot = 0; slot < count; slot++) {
            int identifier = slot >= observerStart ? PlayerTable.FIRST_OBSERVER_SLOT + slot - observerStart : firstPlayer + slot;
            ProviderPeer peer = identifier == this.localIdentifier ? this.self : this.findPeerByIdentifier(identifier);

            if (peer == null || (peer.getFlags() & ProviderPeer.FLAG_JOINED_LATE) != 0) {
                continue;
            }

            players[slot] = peer;

            int slotStatus = TURN_PRESENT;
            List<ProviderChannel.Received> queued = peer.getChannel(ProviderPacket.TURN).getReceivedPackets();

            if (!queued.isEmpty() && queued.getFirst().getPacket().getSequence() == turn) {
                slotStatus |= TURN_READY;
            } else {
                ready = false;
            }

            if (peer == this.self || Integer.compareUnsigned(now - peer.getLastReceiveTime(), this.rejoinWindow) < 0 ||
                ((peer.getFlags() & ProviderPeer.FLAG_DEPARTED) != 0 && (slotStatus & TURN_READY) != 0)) {
                slotStatus |= TURN_RESPONSIVE;
            }

            status[slot] = slotStatus;
        }

        if (!ready) {
            for (int slot = 0; slot < count; slot++) {
                ProviderPeer peer = players[slot];

                if (peer != null && peer != this.self && (status[slot] & TURN_READY) == 0) {
                    this.requestOverdueTurn(peer, now);
                }
            }

            return new TurnBatch(turns, status, false);
        }

        for (int slot = 0; slot < count; slot++) {
            ProviderPeer peer = players[slot];

            if (peer == null) {
                continue;
            }

            ProviderChannel channel = peer.getChannel(ProviderPacket.TURN);
            ProviderPacket packet = channel.consumeTurn(turn);

            turns[slot] = packet.getPayloadBytes();
        }

        ownTurns.setExpectedSequence(turn + 1);

        return new TurnBatch(turns, status, true);
    }

    /**
     * Asks for a player's overdue turn: at most every request interval, a header-only
     * {@link ProviderPacket#FLAG_RESEND_REQUEST} packet to every known peer, carrying the player's identifier for everyone
     * but the player itself. The request counts for the player ({@link ProviderPeer#takeTurnResendRequestsSent()}).
     *
     * @param owner the player whose turn is overdue
     * @param now   the current tick
     */
    private void requestOverdueTurn(@NotNull ProviderPeer owner, int now) {
        int stallTime = owner.getStallTime();

        if (stallTime != 0 && (Integer.compareUnsigned(now - stallTime, this.requestInterval) < 0 ||
                               Integer.compareUnsigned(now - stallTime, ProviderChannel.TICK_HALF_RANGE) > 0)) {
            return;
        }

        owner.setStallTime(now);
        owner.countTurnResendRequestSent();

        int sequence = owner.getChannel(ProviderPacket.TURN).getExpectedSequence();

        for (ProviderPeer peer : List.copyOf(this.peers)) {
            if (peer.getIdentifier() == ProviderPeer.UNKNOWN_IDENTIFIER || owner.getIdentifier() == ProviderPeer.UNKNOWN_IDENTIFIER) {
                continue;
            }

            byte[] payload = peer == owner ? new byte[0] : new byte[]{(byte) owner.getIdentifier()};

            this.transmit(peer, ProviderPacket.create(sequence, peer.getChannel(ProviderPacket.TURN).getAcknowledgement(),
                                                      ProviderPacket.TURN, 0,
                                                      this.self.getIdentifier(),
                                                      ProviderPacket.FLAG_RESEND_REQUEST,
                                                      payload));
        }
    }

    /**
     * Applies departures whose turn is reached: event {@link #EVENT_PLAYER_LEFT}, the record becomes anonymous. When no
     * host remains and the local player has the lowest identifier, it announces itself (subtype {@code 13}; the host flag
     * only once the game started). A host clears the full bit when a slot freed.
     */
    private void processDepartures() {
        for (ProviderPeer peer : List.copyOf(this.peers)) {
            if ((peer.getFlags() & ProviderPeer.FLAG_DEPARTED) == 0 || peer.getIdentifier() == ProviderPeer.UNKNOWN_IDENTIFIER) {
                continue;
            }

            int departureDistance = (peer.getChannel(ProviderPacket.TURN).getExpectedSequence() - peer.getDepartureTurn()) & WORD_MASK;

            if (departureDistance > ProviderChannel.SEQUENCE_HALF_RANGE) {
                continue;
            }

            this.events.addLast(new Event(EVENT_PLAYER_LEFT, peer.getIdentifier(), createInt32Bytes(peer.getDepartureReason())));
            peer.clearIdentifier();

            if (this.localIdentifier != ProviderPeer.UNKNOWN_IDENTIFIER && !this.self.isHost()) {
                int lowest = -1;

                for (ProviderPeer other : this.peers) {
                    if (other.getIdentifier() == ProviderPeer.UNKNOWN_IDENTIFIER) {
                        continue;
                    }

                    if (other.isHost()) {
                        lowest = 0;
                    } else if (Integer.compareUnsigned(other.getIdentifier(), lowest) < 0) {
                        lowest = other.getIdentifier();
                    }
                }

                if (Integer.compareUnsigned(this.localIdentifier, lowest) < 0) {
                    if ((this.gameState & STATE_STARTED) != 0) {
                        this.self.setHost(true);
                    }

                    for (ProviderPeer other : List.copyOf(this.peers)) {
                        if (other.getIdentifier() != ProviderPeer.UNKNOWN_IDENTIFIER) {
                            this.send(other, ProviderPacket.CONTROL, SUBTYPE_HOST, createInt32Bytes(this.localIdentifier));
                        }
                    }
                }
            }

            if (this.self.isHost() && !this.isFull()) {
                this.gameState &= ~STATE_FULL;
            }
        }
    }

    /**
     * Maintains the turn history: the oldest turn any peer may still request is the smallest turn acknowledgement
     * received from a peer, at most the local player's next turn.
     *
     * @param turn the local player's next turn to consume
     */
    private void pruneTurnHistory(int turn) {
        int oldestNeeded = turn;

        for (ProviderPeer peer : this.peers) {
            int acknowledgement = peer.getTurnAcknowledgement();

            if (((oldestNeeded - acknowledgement) & WORD_MASK) < ProviderChannel.SEQUENCE_HALF_RANGE) {
                oldestNeeded = acknowledgement;
            }
        }

        this.self.getChannel(ProviderPacket.TURN).pruneTurns(turn, oldestNeeded);

        for (ProviderPeer peer : this.peers) {
            peer.getChannel(ProviderPacket.TURN).pruneTurns(turn, oldestNeeded);
        }
    }


    /**
     * Leaves the game: subtype {@code 11} with the local player's next turn and the reason goes to every player three times, then
     * the local player's identifier is cleared.
     *
     * @param reason the departure reason
     */
    public void leave(int reason) {
        for (ProviderPeer peer : List.copyOf(this.peers)) {
            if (peer.getIdentifier() == ProviderPeer.UNKNOWN_IDENTIFIER) {
                continue;
            }

            ByteBuffer payload = ByteBuffer.allocate(LEAVE_SIZE).order(ByteOrder.LITTLE_ENDIAN);

            payload.putInt(peer.getChannel(ProviderPacket.TURN).getNextOutgoingSequence()).putInt(reason);

            ProviderPacket packet = this.send(peer, ProviderPacket.CONTROL, SUBTYPE_LEAVE, payload.array());

            if (packet != null) {
                this.resend(peer, packet);
                this.resend(peer, packet);
            }
        }

        this.localIdentifier = ProviderPeer.UNKNOWN_IDENTIFIER;
        this.gameState       = 0;
    }

    /**
     * Drops another player: the player is marked departed at its expected turn with the reason, and every other known
     * player gets the departure information (subtype {@code 12}: player, turn and reason). The departure takes effect when
     * the turn is reached, as for a leave.
     *
     * @param player the player identifier
     * @param reason the departure reason
     * @return {@code true} if a game is in progress and the player is a known remote player, {@code false} otherwise
     */
    public boolean dropPlayer(int player, int reason) {
        if (this.localIdentifier == ProviderPeer.UNKNOWN_IDENTIFIER || player == ProviderPeer.UNKNOWN_IDENTIFIER || player == this.localIdentifier) {
            return false;
        }

        ProviderPeer dropped = this.findPeerByIdentifier(player);

        if (dropped == null) {
            return false;
        }

        int turn = dropped.getChannel(ProviderPacket.TURN).getExpectedSequence();

        dropped.setDeparture(turn, reason);

        byte[] payload = encodeDeparture(player, turn, reason);

        for (ProviderPeer peer : List.copyOf(this.peers)) {
            if (peer != dropped && peer.getIdentifier() != ProviderPeer.UNKNOWN_IDENTIFIER) {
                this.send(peer, ProviderPacket.CONTROL, SUBTYPE_DEPARTURE, payload);
            }
        }

        return true;
    }

    /**
     * Processes one datagram payload from the provider. Malformed packets are dropped.
     *
     * @param source  the sender's address
     * @param encoded the provider packet bytes
     */
    public void receive(@NotNull InetSocketAddress source, byte @NotNull [] encoded) {
        ProviderPacket packet;

        try {
            packet = ProviderPacket.parse(encoded);
        } catch (IOException exception) {
            LOGGER.debug("PROVIDER PACKET DROPPED: " + exception.getMessage());

            return;
        }

        int now = this.clock.getAsInt();
        int flags = packet.getFlags();
        ProviderPeer peer = (flags & ProviderPacket.FLAG_RELAYED) != 0
                            ? this.findPeerByIdentifier(packet.getSenderIdentifier())
                            : this.findOrCreatePeer(source);

        if (peer == null) {
            return;
        }

        if (!this.joiningAnyPeer && packet.getSenderIdentifier() != ProviderPeer.UNKNOWN_IDENTIFIER && (flags & ProviderPacket.FLAG_RELAYED) == 0 &&
            this.findPeerByIdentifier(packet.getSenderIdentifier()) != peer) {
            return;
        }

        int packetClass = packet.getType();

        if (packetClass == ProviderPacket.CONTROL) {
            if (packet.getSubtype() == SUBTYPE_PING && (peer.getFlags() & ProviderPeer.FLAG_KNOWN) == 0 && !this.self.isHost()) {
                return;
            }

            if (packet.getSubtype() == SUBTYPE_JOIN_REQUEST) {
                this.receiveJoinRequest(source, packet, now);

                return;
            }
        }

        ProviderChannel channel = peer.getChannel(packetClass);

        if ((flags & ProviderPacket.FLAG_RELAYED) == 0) {
            peer.setLastReceiveTime(now);
            channel.removeAcknowledged(packet.getAcknowledgement(), now);

            if (packetClass == ProviderPacket.TURN) {
                peer.setTurnAcknowledgement(packet.getAcknowledgement());
            }
        }

        if ((flags & ProviderPacket.FLAG_ACKNOWLEDGEMENT) != 0) {
            return;
        }

        if ((flags & ProviderPacket.FLAG_RESEND_REQUEST) != 0) {
            this.receiveResendRequest(peer, packet);

            return;
        }

        ProviderChannel.ReceiveResult result = channel.insertReceived(packet, now, now - this.requestInterval + this.acknowledgementDelay);

        if (result == ProviderChannel.ReceiveResult.STALE) {
            if (packetClass != ProviderPacket.TURN) {
                channel.requestAcknowledgementRepeat(now);
            }

            return;
        }

        if (result == ProviderChannel.ReceiveResult.DUPLICATE) {
            return;
        }

        if (packetClass != ProviderPacket.TURN) {
            if (channel.advanceAcknowledgement()) {
                channel.markAcknowledgementDue(now);
            }
        } else {
            this.updateTurnAcknowledgement(peer, now);
        }

        if (packetClass == ProviderPacket.CONTROL) {
            this.dispatchControl(peer, source);
        }
    }

    /**
     * Handles class {@code 0} subtype {@code 1}: a fresh join request resets the peer and is answered with subtype
     * {@code 2}; repeats within the rejoin window are ignored. A player still present at the address leaves ({@link #removeReplacedPeer}).
     *
     * @param source the requester's address
     * @param packet the join request
     * @param now    the current tick
     */
    private void receiveJoinRequest(@NotNull InetSocketAddress source, @NotNull ProviderPacket packet, int now) {
        ProviderPeer existing = this.findOrCreatePeer(source);
        boolean recent = Integer.compareUnsigned(now - existing.getJoinTime(), this.rejoinWindow) < 0;

        if (!recent) {
            this.removeReplacedPeer(existing, ProviderPeer.UNKNOWN_IDENTIFIER);
        }

        byte[] payload = packet.getPayloadBytes();

        if (packet.getSize() != ProviderPacket.HEADER_SIZE + Integer.BYTES || readInt32(payload, 0) != JOIN_VALUE || recent) {
            return;
        }

        ProviderPeer peer = this.findOrCreatePeer(source);

        peer.setJoining(true);
        peer.getChannel(ProviderPacket.CONTROL).reset(JOIN_CONTROL_SEQUENCE);
        peer.setJoinTime(now);
        this.send(peer, ProviderPacket.CONTROL, SUBTYPE_JOIN_ACCEPT, createInt32Bytes(JOIN_VALUE));
    }

    /**
     * Removes a peer that a join or another player's information at its address replaces. A player of another identifier that has not
     * left yet leaves now: event {@link #EVENT_PLAYER_LEFT} with its departure reason, and a host clears the full bit.
     *
     * @param existing   the replaced peer
     * @param identifier the identifier of the player that takes the address, {@link ProviderPeer#UNKNOWN_IDENTIFIER} for a joiner
     */
    private void removeReplacedPeer(@NotNull ProviderPeer existing, int identifier) {
        this.peers.remove(existing);

        int former = existing.getIdentifier();

        if (former == ProviderPeer.UNKNOWN_IDENTIFIER || former == identifier) {
            return;
        }

        this.events.addLast(new Event(EVENT_PLAYER_LEFT, former, createInt32Bytes(existing.getDepartureReason())));

        if (this.self.isHost() && !this.isFull()) {
            this.gameState &= ~STATE_FULL;
        }
    }

    /**
     * Handles {@link ProviderPacket#FLAG_RESEND_REQUEST}: resends the requested packet. Without a payload, or with the
     * local identifier as payload, the request is for this side's own packet: class {@code 0} or {@code 1} from the
     * requester's outgoing queue, class {@code 2} from the local turn queue. With another player's identifier, that
     * player's turn is relayed with {@link ProviderPacket#FLAG_RELAYED}, preceded by its departure information (subtype
     * {@code 12}) when the player has left. A turn request counts for the requester ({@link ProviderPeer#takeTurnResendRequestsReceived()}).
     *
     * @param requester the peer that sent the request
     * @param request   the resend request
     */
    private void receiveResendRequest(@NotNull ProviderPeer requester, @NotNull ProviderPacket request) {
        int packetClass = request.getType();
        int sequence = request.getSequence();
        byte[] payload = request.getPayloadBytes();

        if (packetClass == ProviderPacket.TURN) {
            requester.countTurnResendRequestReceived();
        }

        if (payload.length == 0 || (payload[0] & BYTE_MASK) == this.localIdentifier) {
            if (packetClass == ProviderPacket.TURN) {
                ProviderPacket turn = findTurn(this.self.getChannel(ProviderPacket.TURN), sequence);

                if (turn != null) {
                    this.resend(requester, turn);
                }

                return;
            }

            for (ProviderChannel.Outgoing outgoing : requester.getChannel(packetClass).getOutgoingPackets()) {
                if (outgoing.getPacket().getSequence() == sequence) {
                    this.resend(requester, outgoing.getPacket());

                    return;
                }
            }

            return;
        }

        if (packetClass != ProviderPacket.TURN) {
            return;
        }

        int player = payload[0] & BYTE_MASK;
        ProviderPeer owner = this.findPeerByIdentifier(player);

        if (owner == null && player != ProviderPeer.UNKNOWN_IDENTIFIER) {
            for (ProviderPeer peer : this.peers) {
                if (peer.getIdentifier() == ProviderPeer.UNKNOWN_IDENTIFIER && peer.getFormerIdentifier() == player) {
                    owner = peer;

                    break;
                }
            }
        }

        if (owner == null) {
            return;
        }

        if (owner.getIdentifier() == ProviderPeer.UNKNOWN_IDENTIFIER && owner.getFormerIdentifier() == player) {
            byte[] departure = encodeDeparture(player, owner.getDepartureTurn(), owner.getDepartureReason());

            this.send(requester, ProviderPacket.CONTROL, SUBTYPE_DEPARTURE, departure);
        }

        ProviderPacket turn = findTurn(owner.getChannel(ProviderPacket.TURN), sequence);

        if (turn != null) {
            byte[] relayed = turn.getEncodedBytes();

            relayed[ProviderPacket.FLAGS_OFFSET] |= ProviderPacket.FLAG_RELAYED;

            try {
                ProviderPacket copy = ProviderPacket.create(turn.getSequence(), turn.getAcknowledgement(),
                                                            turn.getType(), turn.getSubtype(),
                                                            turn.getSenderIdentifier(),
                                                            relayed[ProviderPacket.FLAGS_OFFSET] & BYTE_MASK,
                                                            turn.getPayloadBytes());

                this.resend(requester, copy);
            } catch (IllegalArgumentException exception) {
                LOGGER.debug("PROVIDER RELAY SKIPPED: " + exception.getMessage());
            }
        }
    }

    /**
     * Updates the shared acknowledgement of class {@code 2} after a queued turn. The calculation starts from the receiving player's
     * channel, or, when that player joined late, from the channel of a player that takes part in the turn being consumed, so that the
     * acknowledgement never passes a turn that player has not sent yet.
     *
     * @param receiver the peer whose turn was queued
     * @param now      the current tick
     */
    private void updateTurnAcknowledgement(@NotNull ProviderPeer receiver, int now) {
        ProviderChannel[] channels = new ProviderChannel[this.peers.size()];
        int[] flags = new int[this.peers.size()];
        ProviderChannel receiving = receiver.getChannel(ProviderPacket.TURN);
        boolean late = (receiver.getFlags() & ProviderPeer.FLAG_JOINED_LATE) != 0;

        for (int index = 0; index < channels.length; index++) {
            ProviderPeer peer = this.peers.get(index);

            channels[index] = peer.getIdentifier() == ProviderPeer.UNKNOWN_IDENTIFIER ? null : peer.getChannel(ProviderPacket.TURN);
            flags[index]    = peer.getFlags();

            if (late && channels[index] != null && (flags[index] & ProviderPeer.FLAG_JOINED_LATE) == 0) {
                receiving = channels[index];
                late      = false;
            }
        }

        int acknowledgement = ProviderChannel.calculateSharedAcknowledgement(receiving, receiving.getAcknowledgement(), channels, flags);

        for (ProviderPeer peer : this.peers) {
            ProviderChannel channel = peer.getChannel(ProviderPacket.TURN);

            if (acknowledgement == channel.getAcknowledgement() || peer.getIdentifier() == ProviderPeer.UNKNOWN_IDENTIFIER) {
                continue;
            }

            if ((peer.getFlags() & ProviderPeer.FLAG_JOINED_LATE) != 0 && acknowledgement < channel.getExpectedSequence()) {
                continue;
            }

            channel.setAcknowledgement(acknowledgement);
            channel.markAcknowledgementDue(now);
        }
    }

    /**
     * Lowers the shared acknowledgement of class {@code 2} to a player's first turn when it is ahead of it: a player that takes part in the
     * next turn to consume holds the acknowledgement back until its turns arrive. Players that joined late keep theirs.
     *
     * @param player the player that takes part from its first turn on
     * @param turn   the player's first turn
     */
    private void awaitTurns(@NotNull ProviderPeer player, int turn) {
        for (ProviderPeer peer : this.peers) {
            ProviderChannel channel = peer.getChannel(ProviderPacket.TURN);

            if (peer != player && (peer.getFlags() & ProviderPeer.FLAG_JOINED_LATE) == 0 && isTurnAhead(channel.getAcknowledgement(), turn)) {
                channel.setAcknowledgement(turn);
            }
        }
    }

    /**
     * Dispatches the in-order class {@code 0} packets of a peer: marks the subtype as received and runs its handler.
     *
     * @param peer   the peer whose control packets are dispatched
     * @param source the address the packets came from
     */
    private void dispatchControl(@NotNull ProviderPeer peer, @NotNull InetSocketAddress source) {
        ProviderChannel channel = peer.getChannel(ProviderPacket.CONTROL);

        while (channel.hasExpectedPacket()) {
            ProviderPacket packet = channel.getReceivedPackets().getFirst().getPacket();
            int subtype = packet.getSubtype();

            if (subtype < SUBTYPE_COUNT) {
                this.receivedSubtypes[subtype] = true;
            }

            this.handleControl(source, packet);
            channel.pollExpectedPacket();
        }
    }

    private void handleControl(@NotNull InetSocketAddress source, @NotNull ProviderPacket packet) {
        byte[] payload = packet.getPayloadBytes();

        switch (packet.getSubtype()) {
            case SUBTYPE_JOIN_ACCEPT -> {
                if (payload.length == Integer.BYTES && readInt32(payload, 0) == JOIN_VALUE) {
                    this.send(this.findOrCreatePeer(source), ProviderPacket.CONTROL, SUBTYPE_JOIN_CONFIRM, payload);
                }
            }
            case SUBTYPE_PING -> this.send(this.findOrCreatePeer(source), ProviderPacket.CONTROL, SUBTYPE_PONG, payload);
            case SUBTYPE_PONG -> this.findOrCreatePeer(source).receivePong(payload.length == Integer.BYTES ? readInt32(payload, 0) : 0,
                                                                           payload.length == Integer.BYTES, this.clock.getAsInt());
            case SUBTYPE_PLAYER_INFORMATION -> this.receivePlayerInformation(source, packet);
            case SUBTYPE_PLAYER_REQUEST -> this.receivePlayerRequest(source, payload);
            case SUBTYPE_GAME_INFORMATION -> this.receiveGameInformation(payload);
            case SUBTYPE_GAME_DATA -> this.receiveGameData(payload);
            case SUBTYPE_LEAVE -> {
                ProviderPeer peer = this.findOrCreatePeer(source);

                if (peer.getIdentifier() != ProviderPeer.UNKNOWN_IDENTIFIER && payload.length >= LEAVE_SIZE) {
                    peer.setDeparture(readInt32(payload, 0) & WORD_MASK, readInt32(payload, LEAVE_REASON_OFFSET));
                }
            }
            case SUBTYPE_DEPARTURE -> {
                if (payload.length >= DEPARTURE_SIZE) {
                    ProviderPeer peer = this.findPeerByIdentifier(readInt32(payload, 0));
                    int turn = readInt32(payload, DEPARTURE_TURN_OFFSET) & WORD_MASK;

                    // A departure before the player's first turn is a former player's of the same identifier.
                    if (peer != null && peer.getIdentifier() != ProviderPeer.UNKNOWN_IDENTIFIER && !isTurnAhead(peer.getFirstTurn(), turn)) {
                        peer.setDeparture(turn, readInt32(payload, DEPARTURE_REASON_OFFSET));
                    }
                }
            }
            case SUBTYPE_HOST -> {
                this.self.setHost(false);

                for (ProviderPeer peer : this.peers) {
                    peer.setHost(false);
                }

                ProviderPeer peer = payload.length >= Integer.BYTES ? this.findPeerByIdentifier(readInt32(payload, 0)) : null;

                if (peer != null) {
                    peer.setHost(true);
                }
            }
            case SUBTYPE_GAME_STATE -> {
                if (payload.length >= Integer.BYTES) {
                    this.gameState = readInt32(payload, 0);

                    if ((this.gameState & STATE_PASSWORD) == 0) {
                        this.password = "";
                    }
                }
            }
            case SUBTYPE_GAME_VALUE -> {
                if (payload.length >= Integer.BYTES) {
                    this.gameValue = readInt32(payload, 0);
                }
            }
            default -> {
                // Subtype 3 only sets its received flag; subtype 10 (refusal) is handled by a game callback.
            }
        }
    }

    /**
     * Handles subtype {@code 6}, a player's information. The sender's own entry (the host describes itself with a zero
     * address) takes the packet's source; another player's entry names its address, whose peer is made afresh (a peer of
     * that address is deleted and created again): the players then exchange turns and messages directly. The player counts as joined late
     * when its first turn lies ahead of the local player's next turn to consume, whatever the sender's flags say; a first turn that was
     * consumed already becomes the next turn to consume.
     *
     * @param source the address the packet came from
     * @param packet the player information packet
     */
    private void receivePlayerInformation(@NotNull InetSocketAddress source, @NotNull ProviderPacket packet) {
        byte[] payload = packet.getPayloadBytes();

        if (payload.length < PLAYER_INFORMATION_HEADER) {
            return;
        }

        int identifier = readInt32(payload, PLAYER_INFORMATION_IDENTIFIER_OFFSET);
        InetSocketAddress address = source;

        if (identifier != packet.getSenderIdentifier()) {
            address = decodePlayerInformationAddress(payload);

            if (address == null) {
                LOGGER.warning("LAN PLAYER INFORMATION IGNORED: player " + identifier + " without an address");

                return;
            }

            for (ProviderPeer existing : List.copyOf(this.peers)) {
                if (address.equals(existing.getAddress())) {
                    this.removeReplacedPeer(existing, identifier);
                }
            }

            LOGGER.info("LAN PLAYER INFORMATION: player " + identifier + " at " + address + " from player " + packet.getSenderIdentifier());
        }

        ProviderPeer peer = this.findOrCreatePeer(address);

        peer.setIdentifier(identifier);
        // Whether the player joined late follows from the local turns below, not from the sender's flags.
        peer.setFlags(readInt32(payload, PLAYER_INFORMATION_FLAGS_OFFSET) & ~ProviderPeer.FLAG_JOINED_LATE);
        peer.setHost(readInt32(payload, PLAYER_INFORMATION_HOST_OFFSET) != 0);

        int announced = readInt32(payload, PLAYER_INFORMATION_TURN_OFFSET) & WORD_MASK;
        int ownExpected = this.self.getChannel(ProviderPacket.TURN).getExpectedSequence();
        // A player announced after its first turn was consumed here takes part from the next turn to consume.
        int turn = isTurnAhead(ownExpected, announced) ? ownExpected : announced;
        ProviderChannel channel = peer.getChannel(ProviderPacket.TURN);

        channel.setExpectedAndAcknowledgement(turn);
        channel.setNextOutgoingSequence(this.self.getChannel(ProviderPacket.TURN).getNextOutgoingSequence());
        peer.setFirstTurn(announced);

        if (isTurnAhead(turn, ownExpected)) {
            peer.setFlags(peer.getFlags() | ProviderPeer.FLAG_JOINED_LATE);
        } else {
            this.awaitTurns(peer, turn);
        }

        int nameEnd = findZeroByteIndex(payload, PLAYER_INFORMATION_HEADER);

        peer.setName(limitTextBytes(new String(payload, PLAYER_INFORMATION_HEADER, nameEnd - PLAYER_INFORMATION_HEADER, StandardCharsets.UTF_8)));

        int statisticsStart = Math.min(nameEnd + 1, payload.length);
        int statisticsEnd = findZeroByteIndex(payload, statisticsStart);

        peer.setStatistics(limitTextBytes(new String(payload, statisticsStart, statisticsEnd - statisticsStart, StandardCharsets.UTF_8)));
        peer.setFlags(peer.getFlags() | ProviderPeer.FLAG_KNOWN);

        for (ProviderPacket history : this.self.getChannel(ProviderPacket.TURN).getHistoryPackets()) {
            if (((history.getSequence() - announced) & WORD_MASK) <= ProviderChannel.SEQUENCE_HALF_RANGE) {
                this.resend(peer, history);
            }
        }

        for (ProviderChannel.Received queued : this.self.getChannel(ProviderPacket.TURN).getReceivedPackets()) {
            if (((queued.getPacket().getSequence() - announced) & WORD_MASK) <= ProviderChannel.SEQUENCE_HALF_RANGE) {
                this.resend(peer, queued.getPacket());
            }
        }

        if (this.localIdentifier != ProviderPeer.UNKNOWN_IDENTIFIER && (peer.getFlags() & ProviderPeer.FLAG_JOINED_LATE) == 0) {
            this.events.addLast(new Event(EVENT_PLAYER_JOINED, identifier, new byte[0]));
        }
    }

    /**
     * Handles subtype {@code 7} on the host: assigns an identifier and sends game information ({@code 8}), player
     * information of the host ({@code 6}) and every other player ({@code 6}), subtype {@code 15}, the game data
     * ({@code 9}), then announces the new player to the others and sends it the queued local turns; refuses with subtype
     * {@code 10}.
     *
     * @param source  the requester's address
     * @param payload the request: name, statistics and password strings
     */
    private void receivePlayerRequest(@NotNull InetSocketAddress source, byte @NotNull [] payload) {
        if (this.localIdentifier == ProviderPeer.UNKNOWN_IDENTIFIER) {
            return;
        }

        int nameEnd = findZeroByteIndex(payload, 0);
        int statisticsStart = Math.min(nameEnd + 1, payload.length);
        int statisticsEnd = findZeroByteIndex(payload, statisticsStart);
        int passwordStart = Math.min(statisticsEnd + 1, payload.length);
        int passwordEnd = findZeroByteIndex(payload, passwordStart);
        String name = new String(payload, 0, nameEnd, StandardCharsets.UTF_8);
        String statistics = new String(payload, statisticsStart, statisticsEnd - statisticsStart, StandardCharsets.UTF_8);
        String givenPassword = new String(payload, passwordStart, passwordEnd - passwordStart, StandardCharsets.UTF_8);
        int identifier = -1;

        if (!this.isFull() && (this.gameState & STATE_STARTED) == 0 && (this.password.isEmpty() || this.password.equals(givenPassword))) {
            List<Integer> candidates = new ArrayList<>();

            for (int player = 0; player < this.maximumPlayers; player++) {
                if (this.findPeerByIdentifier(player) == null) {
                    candidates.add(player);
                }
            }

            int held = Math.min(candidates.size(), this.reservedSlots);

            candidates.subList(candidates.size() - held, candidates.size()).clear();

            for (int observer = PlayerTable.FIRST_OBSERVER_SLOT; observer < PlayerTable.FIRST_OBSERVER_SLOT + this.maximumObservers; observer++) {
                if (observer != ProviderPeer.UNKNOWN_IDENTIFIER && this.findPeerByIdentifier(observer) == null) {
                    candidates.add(observer);
                }
            }

            if (!candidates.isEmpty()) {
                identifier = candidates.getFirst();
            }
        }

        ProviderPeer peer = this.findOrCreatePeer(source);

        if (identifier < 0) {
            this.send(peer, ProviderPacket.CONTROL, SUBTYPE_REFUSAL, new byte[0]);

            return;
        }

        peer.setIdentifier(identifier);
        peer.setFlags(0);
        peer.setJoining(false);
        peer.setName(limitTextBytes(name));
        peer.setStatistics(limitTextBytes(statistics));

        ProviderChannel ownTurns = this.self.getChannel(ProviderPacket.TURN);
        int turn = ownTurns.getNextOutgoingSequence();
        ProviderChannel turns = peer.getChannel(ProviderPacket.TURN);

        turns.setExpectedAndAcknowledgement(turn);
        turns.setNextOutgoingSequence(turn);
        peer.setFirstTurn(turn);

        if (isTurnAhead(turn, ownTurns.getExpectedSequence())) {
            peer.setFlags(peer.getFlags() | ProviderPeer.FLAG_JOINED_LATE);
        } else {
            this.awaitTurns(peer, turn);
        }

        if (this.isFull()) {
            this.gameState |= STATE_FULL;
        }

        if (identifier >= PlayerTable.FIRST_OBSERVER_SLOT && identifier < PlayerTable.FIRST_OBSERVER_SLOT + this.maximumObservers) {
            this.observers++;
        }

        ByteArrayOutputStream information = new ByteArrayOutputStream(GAME_INFORMATION_CAPACITY);

        writeInt32(information, identifier);
        writeInt32(information, this.maximumPlayers);
        writeInt32(information, turn);
        writeInt32(information, this.gameState);
        writeInt32(information, Integer.divideUnsigned(this.clock.getAsInt() - this.startTime, MILLISECONDS_PER_SECOND));
        writeString(information, this.gameName);
        writeString(information, this.gameStatistics);
        writeString(information, this.password);
        this.send(peer, ProviderPacket.CONTROL, SUBTYPE_GAME_INFORMATION, information.toByteArray());

        this.send(peer, ProviderPacket.CONTROL, SUBTYPE_PLAYER_INFORMATION, encodePlayerInformation(this.self, turn));

        for (ProviderPeer other : List.copyOf(this.peers)) {
            if (other != peer && other.getIdentifier() != ProviderPeer.UNKNOWN_IDENTIFIER) {
                this.send(peer, ProviderPacket.CONTROL, SUBTYPE_PLAYER_INFORMATION, encodePlayerInformation(other, turn));
            }
        }

        this.send(peer, ProviderPacket.CONTROL, SUBTYPE_GAME_VALUE, createInt32Bytes(this.gameValue));
        this.send(peer, ProviderPacket.CONTROL, SUBTYPE_GAME_DATA, this.gameData);

        for (ProviderPeer other : List.copyOf(this.peers)) {
            if (other != peer && other.getIdentifier() != ProviderPeer.UNKNOWN_IDENTIFIER) {
                this.send(other, ProviderPacket.CONTROL, SUBTYPE_PLAYER_INFORMATION, encodePlayerInformation(peer, turn));
            }
        }

        for (ProviderChannel.Received queued : ownTurns.getReceivedPackets()) {
            this.resend(peer, queued.getPacket());
        }

        if ((peer.getFlags() & ProviderPeer.FLAG_JOINED_LATE) == 0) {
            this.events.addLast(new Event(EVENT_PLAYER_JOINED, identifier, new byte[0]));
        }
    }

    /**
     * Handles subtype {@code 8} on the joiner: the local identifier, the turn to start at, the game's limits, state, start
     * time, name, statistics and password.
     *
     * @param payload the game information
     */
    private void receiveGameInformation(byte @NotNull [] payload) {
        if (payload.length < GAME_INFORMATION_HEADER) {
            return;
        }

        int identifier = payload[0] & BYTE_MASK;

        this.self.setIdentifier(identifier);

        int turn = readInt32(payload, GAME_INFORMATION_TURN_OFFSET) & WORD_MASK;
        ProviderChannel turns = this.self.getChannel(ProviderPacket.TURN);

        turns.setExpectedAndAcknowledgement(turn);
        turns.setNextOutgoingSequence(turn);

        this.maximumPlayers = readInt32(payload, GAME_INFORMATION_PLAYER_LIMIT_OFFSET);
        this.gameState      = readInt32(payload, GAME_INFORMATION_STATE_OFFSET);
        this.startTime      = this.clock.getAsInt() - readInt32(payload, GAME_INFORMATION_ELAPSED_OFFSET) * MILLISECONDS_PER_SECOND;

        int nameEnd = findZeroByteIndex(payload, GAME_INFORMATION_HEADER);

        this.gameName = limitTextBytes(new String(payload, GAME_INFORMATION_HEADER, nameEnd - GAME_INFORMATION_HEADER, StandardCharsets.UTF_8));

        int statisticsStart = Math.min(nameEnd + 1, payload.length);
        int statisticsEnd = findZeroByteIndex(payload, statisticsStart);

        this.gameStatistics = limitTextBytes(new String(payload, statisticsStart, statisticsEnd - statisticsStart, StandardCharsets.UTF_8));

        int passwordStart = Math.min(statisticsEnd + 1, payload.length);
        int passwordEnd = findZeroByteIndex(payload, passwordStart);

        this.password = limitTextBytes(new String(payload, passwordStart, passwordEnd - passwordStart, StandardCharsets.UTF_8));
    }

    /**
     * Handles subtype {@code 9} on the joiner: the local identifier becomes final and the game data arrives.
     *
     * @param payload the game data
     */
    private void receiveGameData(byte @NotNull [] payload) {
        this.localIdentifier = this.self.getIdentifier();
        this.gameData        = payload.clone();

        if (payload.length > 0) {
            this.events.addLast(new Event(EVENT_GAME_DATA, this.localIdentifier, payload.clone()));
        }
    }


    /**
     * @return {@code true} if the players and the reserved slots fill the player and observer limits, {@code false} otherwise
     */
    private boolean isFull() {
        return this.reservedSlots + this.countPresentPlayers() >= this.maximumObservers + this.maximumPlayers;
    }

    /**
     * Counts the players present: the local player and every remote player with an identifier.
     *
     * @return the number of players present
     */
    private int countPresentPlayers() {
        int count = 1;

        for (ProviderPeer peer : this.peers) {
            if (peer.getIdentifier() != ProviderPeer.UNKNOWN_IDENTIFIER) {
                count++;
            }
        }

        return count;
    }


    /**
     * Runs the timer routine; call it after each receive pass.
     */
    public void update() {
        int now = this.clock.getAsInt();

        for (ProviderPeer peer : List.copyOf(this.peers)) {
            if (peer.getIdentifier() == ProviderPeer.UNKNOWN_IDENTIFIER) {
                if (Integer.compareUnsigned(now - peer.getLastReceiveTime(), UNKNOWN_PEER_TIMEOUT) >= 0) {
                    this.peers.remove(peer);

                    continue;
                }

                if (!peer.isJoining()) {
                    continue;
                }
            }

            this.sendKeepAlive(peer, now);

            for (int packetClass = 0; packetClass < ProviderPacket.CLASS_COUNT; packetClass++) {
                if (packetClass != ProviderPacket.UNRELIABLE) {
                    this.requestMissing(peer, packetClass, now);
                }
            }

            for (int packetClass = ProviderPacket.CONTROL; packetClass <= ProviderPacket.MESSAGE; packetClass++) {
                ProviderChannel.Outgoing head = peer.getChannel(packetClass).getOutgoingPackets().peekFirst();

                if (head == null) {
                    continue;
                }

                int resendSize = MAXIMUM_MESSAGE_SIZE + RESEND_SIZE_ALLOWANCE + head.getPacket().getSize();
                int timeout = resendSize * MILLISECONDS_PER_SECOND / BYTES_PER_SECOND +
                              RESEND_INTERVAL_FACTOR * (this.requestInterval + RESEND_INTERVAL_ALLOWANCE);

                if (head.getResendTime() == 0 || Integer.compareUnsigned(now - head.getResendTime(), timeout) >= 0) {
                    head.setResendTime(now);
                    this.resend(peer, head.getPacket());
                }
            }

            for (int packetClass = 0; packetClass < ProviderPacket.CLASS_COUNT; packetClass++) {
                ProviderChannel channel = peer.getChannel(packetClass);

                if (packetClass == ProviderPacket.UNRELIABLE || channel.getSentAcknowledgement() == channel.getAcknowledgement() ||
                    channel.getAcknowledgementDueTime() == 0) {
                    continue;
                }

                int delay = packetClass == ProviderPacket.TURN ? this.acknowledgementDelayTurns : this.acknowledgementDelay;

                if (Integer.compareUnsigned(now - channel.getAcknowledgementDueTime(), delay) >= 0) {
                    int acknowledgement = channel.getAcknowledgement();

                    this.transmit(peer, ProviderPacket.create(acknowledgement, acknowledgement,
                                                              packetClass, 0,
                                                              this.self.getIdentifier(),
                                                              ProviderPacket.FLAG_ACKNOWLEDGEMENT,
                                                              new byte[0]));
                }
            }
        }
    }

    /**
     * Sends the keep-alive ping once the last ping was answered and the interval passed.
     *
     * @param peer the peer to ping
     * @param now  the current tick
     */
    private void sendKeepAlive(@NotNull ProviderPeer peer, int now) {
        if (peer.isJoining() || peer.getPongIdentifier() != peer.getPingIdentifier() || this.keepAliveInterval == 0 ||
            Integer.compareUnsigned(now - peer.getPingTime(), this.keepAliveInterval) < 0) {
            return;
        }

        this.send(peer, ProviderPacket.CONTROL, SUBTYPE_PING, createInt32Bytes(peer.startPing(now)));
    }

    /**
     * Requests the first missing packet of a class at most every request interval. A turn request counts for the peer
     * ({@link ProviderPeer#takeTurnResendRequestsSent()}).
     *
     * @param peer        the peer whose packets are missing
     * @param packetClass the packet class
     * @param now         the current tick
     */
    private void requestMissing(@NotNull ProviderPeer peer, int packetClass, int now) {
        ProviderChannel channel = peer.getChannel(packetClass);
        int previous = (channel.getExpectedSequence() - 1) & WORD_MASK;

        for (ProviderChannel.Received received : channel.getReceivedPackets()) {
            int sequence = received.getPacket().getSequence();

            if (sequence - previous <= 1) {
                previous = sequence;

                continue;
            }

            if (received.getRequestTime() != 0 && Integer.compareUnsigned(now - received.getRequestTime(), this.requestInterval) < 0) {
                return;
            }

            received.setRequestTime(now);

            if (packetClass == ProviderPacket.TURN) {
                peer.countTurnResendRequestSent();
            }

            this.transmit(peer, ProviderPacket.create((previous + 1) & WORD_MASK, channel.getAcknowledgement(),
                                                      packetClass, 0,
                                                      this.self.getIdentifier(),
                                                      ProviderPacket.FLAG_RESEND_REQUEST,
                                                      new byte[0]));

            return;
        }
    }

    /**
     * Sends a new packet: the local record queues it directly; a remote packet is transmitted before it is retained until
     * acknowledgement, except for class {@code 3}.
     *
     * @param peer        the destination peer, the local record included
     * @param packetClass the packet class
     * @param subtype     the packet subtype
     * @param payload     the payload
     * @return the packet, or {@code null} for the local record
     */
    private @Nullable ProviderPacket send(@NotNull ProviderPeer peer, int packetClass, int subtype, byte @NotNull [] payload) {
        ProviderChannel channel = peer.getChannel(packetClass);
        int sequence = channel.takeOutgoingSequence(packetClass, subtype);
        ProviderPacket packet = ProviderPacket.create(sequence, channel.getAcknowledgement(),
                                                      packetClass, subtype,
                                                      this.self.getIdentifier(),
                                                      0,
                                                      payload);

        if (peer == this.self) {
            channel.appendReceived(packet, this.clock.getAsInt());

            return null;
        }

        this.transmit(peer, packet);

        if (packetClass != ProviderPacket.UNRELIABLE) {
            channel.addOutgoing(packet, this.sendBudget);
        }

        return packet;
    }

    /**
     * Retransmits a kept packet with the current acknowledgement.
     *
     * @param peer   the destination peer
     * @param packet the kept packet
     */
    private void resend(@NotNull ProviderPeer peer, @NotNull ProviderPacket packet) {
        this.transmit(peer, packet.copyWithAcknowledgement(peer.getChannel(packet.getType()).getAcknowledgement()));
    }

    /**
     * Hands a packet to the provider: the send budget advances by the packet's transfer time and the packet's
     * acknowledgement counts as sent.
     *
     * @param peer   the destination peer
     * @param packet the packet to hand over
     */
    private void transmit(@NotNull ProviderPeer peer, @NotNull ProviderPacket packet) {
        InetSocketAddress address = peer.getAddress();

        if (address == null) {
            return;
        }

        int now = this.clock.getAsInt();
        int budget = Integer.compareUnsigned(now - this.sendBudget, ProviderChannel.TICK_HALF_RANGE) < 0 ? now : this.sendBudget;

        this.sendBudget = budget + (packet.getSize() + TRANSMIT_OVERHEAD) * MILLISECONDS_PER_SECOND / BYTES_PER_SECOND;

        this.transport.send(address, packet);
        peer.getChannel(packet.getType()).recordSentAcknowledgement(packet.getAcknowledgement());
    }

    /**
     * Finds the first remote peer with a player identifier, in the order of {@link #getPeers()}.
     *
     * @param identifier the player identifier
     * @return the peer, or {@code null} if no remote peer has the identifier
     */
    public @Nullable ProviderPeer findPeer(int identifier) {
        for (ProviderPeer peer : this.peers) {
            if (peer.getIdentifier() == identifier) {
                return peer;
            }
        }

        return null;
    }

    private @Nullable ProviderPeer findPeerByIdentifier(int identifier) {
        if (identifier == ProviderPeer.UNKNOWN_IDENTIFIER) {
            return null;
        }

        if (this.self.getIdentifier() == identifier) {
            return this.self;
        }

        return this.findPeer(identifier);
    }

    private @NotNull ProviderPeer findOrCreatePeer(@NotNull InetSocketAddress address) {
        for (ProviderPeer peer : this.peers) {
            if (address.equals(peer.getAddress())) {
                return peer;
            }
        }

        ProviderPeer peer = new ProviderPeer(address, this.clock.getAsInt());

        this.peers.add(peer);

        return peer;
    }
}
