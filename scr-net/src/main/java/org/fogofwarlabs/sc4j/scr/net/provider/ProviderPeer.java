package org.fogofwarlabs.sc4j.scr.net.provider;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;

/**
 * Retains one provider participant with per-class sequence state, identity, timing and departure information.
 * The local participant can have no network address; newly constructed channels start at sequence zero.
 */
public final class ProviderPeer {
    /**
     * The identifier of a peer that has no player slot yet.
     */
    public static final int UNKNOWN_IDENTIFIER = 0xFF;

    /**
     * Flag: the peer joined after the turn the others are at; its turns start later.
     */
    public static final int FLAG_JOINED_LATE = 4;
    /**
     * Flag: the peer announced its departure (subtype {@code 11}) or departure information was relayed (subtype {@code 12}).
     */
    public static final int FLAG_DEPARTED = 8;
    /**
     * Flag: the peer's player information (subtype {@code 6}) was received.
     */
    public static final int FLAG_KNOWN = 0x10;

    private final @Nullable InetSocketAddress address;
    private final @NotNull ProviderChannel @NotNull [] channels;

    private int identifier;
    private int formerIdentifier;
    private int flags;
    private @NotNull String name;
    private @NotNull String statistics;

    private boolean host;
    private boolean joining;
    private int joinTime;
    private int lastReceiveTime;

    private int pingTime;
    private int pingIdentifier;
    private int pongIdentifier;
    private int roundTripTime;

    private int turnAcknowledgement;
    private int firstTurn;
    private int departureReason;
    private int departureTurn;
    private int stallTime;

    /**
     * The resend requests sent for this player's turns since the last {@link #takeTurnResendRequestsSent()}.
     */
    private int turnResendRequestsSent;
    /**
     * The turn resend requests received from this peer since the last {@link #takeTurnResendRequestsReceived()}.
     */
    private int turnResendRequestsReceived;

    /**
     * Creates a {@link ProviderPeer} instance with an unknown identifier and all sequences at {@code 0}.
     *
     * @param address      the provider address, or {@code null} for the local player's own record
     * @param creationTime the creation tick (stored as the last receive tick)
     */
    ProviderPeer(@Nullable InetSocketAddress address, int creationTime) {
        this.address  = address;
        this.channels = new ProviderChannel[ProviderPacket.CLASS_COUNT];

        for (int packetClass = 0; packetClass < this.channels.length; packetClass++) {
            this.channels[packetClass] = new ProviderChannel(0, 0);
        }

        this.identifier       = UNKNOWN_IDENTIFIER;
        this.formerIdentifier = UNKNOWN_IDENTIFIER;
        this.flags            = 0;
        this.name             = "";
        this.statistics       = "";

        this.host            = false;
        this.joining         = false;
        this.joinTime        = 0;
        this.lastReceiveTime = creationTime;

        this.pingTime       = 0;
        this.pingIdentifier = 0;
        this.pongIdentifier = 0;
        this.roundTripTime  = 0;

        this.turnAcknowledgement = 0;
        this.firstTurn           = 0;
        this.departureReason     = 0;
        this.departureTurn       = 0;
        this.stallTime           = 0;

        this.turnResendRequestsSent     = 0;
        this.turnResendRequestsReceived = 0;
    }

    /**
     * @return the provider address, or {@code null} for the local player's own record
     */
    public @Nullable InetSocketAddress getAddress() {
        return this.address;
    }

    /**
     * @param packetClass the packet class, {@link ProviderPacket#CONTROL} to {@link ProviderPacket#UNRELIABLE}
     * @return the class's sequence state
     */
    public @NotNull ProviderChannel getChannel(int packetClass) {
        return this.channels[packetClass];
    }

    /**
     * @return the player identifier, {@link #UNKNOWN_IDENTIFIER} before the peer has a slot
     */
    public int getIdentifier() {
        return this.identifier;
    }

    /**
     * Sets the player identifier; a known identifier also becomes the former identifier.
     *
     * @param identifier the player identifier, {@link #UNKNOWN_IDENTIFIER} for none
     */
    void setIdentifier(int identifier) {
        this.identifier = identifier;

        if (identifier != UNKNOWN_IDENTIFIER) {
            this.formerIdentifier = identifier;
        }
    }

    int getFormerIdentifier() {
        return this.formerIdentifier;
    }

    /**
     * @return the flags, a combination of {@link #FLAG_JOINED_LATE}, {@link #FLAG_DEPARTED} and {@link #FLAG_KNOWN}
     */
    public int getFlags() {
        return this.flags;
    }

    void setFlags(int flags) {
        this.flags = flags;
    }

    /**
     * @return the player name
     */
    public @NotNull String getName() {
        return this.name;
    }

    void setName(@NotNull String name) {
        this.name = name;
    }

    /**
     * @return the player's statistics string
     */
    public @NotNull String getStatistics() {
        return this.statistics;
    }

    void setStatistics(@NotNull String statistics) {
        this.statistics = statistics;
    }

    /**
     * @return {@code true} if this peer hosts the game, {@code false} otherwise
     */
    public boolean isHost() {
        return this.host;
    }

    void setHost(boolean host) {
        this.host = host;
    }

    boolean isJoining() {
        return this.joining;
    }

    void setJoining(boolean joining) {
        this.joining = joining;
    }

    int getJoinTime() {
        return this.joinTime;
    }

    void setJoinTime(int joinTime) {
        this.joinTime = joinTime;
    }

    /**
     * @return the tick of the last packet received from this peer directly (relayed packets do not count)
     */
    public int getLastReceiveTime() {
        return this.lastReceiveTime;
    }

    void setLastReceiveTime(int lastReceiveTime) {
        this.lastReceiveTime = lastReceiveTime;
    }

    int getPingTime() {
        return this.pingTime;
    }

    int getPingIdentifier() {
        return this.pingIdentifier;
    }

    int getPongIdentifier() {
        return this.pongIdentifier;
    }

    /**
     * @return the last measured round trip in milliseconds
     */
    public int getRoundTripTime() {
        return this.roundTripTime;
    }

    int getTurnAcknowledgement() {
        return this.turnAcknowledgement;
    }

    void setTurnAcknowledgement(int turnAcknowledgement) {
        this.turnAcknowledgement = turnAcknowledgement;
    }

    /**
     * @return the first turn the player takes part in, as its join or its player information named it
     */
    public int getFirstTurn() {
        return this.firstTurn;
    }

    /**
     * Sets the first turn the player takes part in.
     *
     * @param firstTurn the unsigned 16-bit turn
     */
    void setFirstTurn(int firstTurn) {
        this.firstTurn = firstTurn;
    }

    /**
     * @return the departure reason of subtype {@code 11} or {@code 12}
     */
    public int getDepartureReason() {
        return this.departureReason;
    }

    /**
     * @return the turn of departure of subtype {@code 11} or {@code 12}
     */
    public int getDepartureTurn() {
        return this.departureTurn;
    }

    /**
     * Sets the departure turn and reason and marks the player departed.
     *
     * @param turn   the departure turn
     * @param reason the departure reason
     */
    void setDeparture(int turn, int reason) {
        this.flags |= FLAG_DEPARTED;
        this.departureTurn   = turn;
        this.departureReason = reason;
    }

    int getStallTime() {
        return this.stallTime;
    }

    /**
     * Sets the tick of the last resend request for this player's overdue turn.
     *
     * @param stallTime the tick of the last resend request
     */
    void setStallTime(int stallTime) {
        this.stallTime = stallTime;
    }

    /**
     * @return {@code true} if a round trip was measured (a keep-alive went out and an identified answer came back), {@code false} otherwise
     */
    public boolean hasRoundTripTime() {
        return this.pingTime != 0 && this.pongIdentifier != 0;
    }


    /**
     * Takes the number of resend requests sent for this player's turns since the previous call: the requests for an overdue turn and for a
     * turn missing ahead of a received one.
     *
     * @return the number of requests
     */
    public int takeTurnResendRequestsSent() {
        int requests = this.turnResendRequestsSent;

        this.turnResendRequestsSent = 0;

        return requests;
    }

    /**
     * Takes the number of turn resend requests received from this peer since the previous call.
     *
     * @return the number of requests
     */
    public int takeTurnResendRequestsReceived() {
        int requests = this.turnResendRequestsReceived;

        this.turnResendRequestsReceived = 0;

        return requests;
    }

    /**
     * Counts a resend request sent for this player's turns.
     */
    void countTurnResendRequestSent() {
        this.turnResendRequestsSent++;
    }

    /**
     * Counts a turn resend request received from this peer.
     */
    void countTurnResendRequestReceived() {
        this.turnResendRequestsReceived++;
    }

    /**
     * Starts the next keep-alive ping.
     *
     * @param now the current tick
     * @return the new ping identifier
     */
    int startPing(int now) {
        this.pingIdentifier++;
        this.pingTime = now;

        return this.pingIdentifier;
    }

    /**
     * Measures a keep-alive round trip from the last ping.
     * A supplied identifier must match the last ping; an absent identifier still updates the measured time.
     *
     * @param identifier        the ping identifier of the answer, ignored when absent
     * @param identifierPresent {@code true} if the answer carries a ping identifier
     * @param now               the current tick
     */
    void receivePong(int identifier, boolean identifierPresent, int now) {
        if (identifierPresent) {
            if (identifier != this.pingIdentifier) {
                return;
            }

            this.pongIdentifier = identifier;
        }

        int roundTrip = now - this.pingTime;

        this.roundTripTime = roundTrip;
    }

    /**
     * Turns a departed player into an anonymous record once its departure turn is reached: flags cleared, identifier kept
     * as the former identifier, identifier unknown.
     */
    void clearIdentifier() {
        this.flags            = 0;
        this.formerIdentifier = this.identifier;
        this.identifier       = UNKNOWN_IDENTIFIER;
    }
}
