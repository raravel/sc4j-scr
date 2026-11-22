package org.fogofwarlabs.sc4j.scr.net.beaconlink;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.net.DatagramLink;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.fogofwarlabs.sc4j.engine.net.lobby.JoinRefusal;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanDiscovery;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanGameInformation;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanRecord;
import org.fogofwarlabs.sc4j.scr.net.lan.NetworkProvider;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.fogofwarlabs.sc4j.scr.net.proxy.GameProxySocket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Holds the Beacon.Link network of SC:R games: a Beacon.Link session of the SC:R mod's identity, whose room list is the game list (rooms of
 * other mods are never listed), whose published room is the hosted game, and whose overlay carries the game socket's datagrams unchanged on
 * its own port ({@value #STORM_PORT}). There is no LAN discovery and no broadcast.
 * <p>
 * The provider session addresses its peers by IPv4 socket address, and its player information names the address of every player, so that
 * the players of a game reach each other. Over Beacon.Link every participant of a game has one address of the shared address space
 * {@code 100.64.0.0/10} and a port, the same on every machine ({@link StormAddressBook}): the host's is derived from its key
 * ({@link #getAddress}), and the host assigns each joiner the first address derived from the joiner's key that is free, so no participant can
 * take another's address. The game socket maps these addresses to the participants of the game and back; a datagram for an address of no
 * participant of the game is lost.
 * <p>
 * The host of a game adds every participant whose join its room accepted to its overlay, and sends the room's members with their addresses
 * on the room port ({@value #ROOM_PORT}) together with the game's record (the attributes the LAN discovery advertises). Every member takes
 * the member list as the whole truth: it adds every listed member to its overlay (a full mesh, in which a pair without a direct path finds a
 * relay among the members) and removes the members the host no longer lists. Every member reports to the host the members its channels are
 * open to, and the host's game may start only when every member pair is connected, directly or through a relay ({@link #isReadyToStart()}).
 * A joiner's host is reachable once its channel is open and its record arrived. The provider owns the session and closes it; it is used
 * under the client's lock.
 */
public final class BeaconLinkProvider implements NetworkProvider {
    /**
     * The overlay port of the SC:R Storm session: the game socket's datagrams. A member is its participant's wire form, and its Storm address
     * derives from it, so that two connections of one account have two addresses.
     */
    public static final int STORM_PORT = LinkOverlay.FIRST_MOD_PORT;
    /**
     * The overlay port of the room messages from the host of a game to its members.
     */
    public static final int ROOM_PORT = LinkOverlay.FIRST_MOD_PORT + 1;

    private static final @NotNull Logger LOGGER = Logger.of(BeaconLinkProvider.class);

    /**
     * The time between two room searches while the game list is open, and between two room messages of a host to its members.
     */
    private static final long SEARCH_INTERVAL_MILLISECONDS = 1000L;
    private static final long ANNOUNCE_INTERVAL_MILLISECONDS = 500L;
    private static final long NANOSECONDS_PER_MILLISECOND = 1_000_000L;
    /**
     * The time of the last search before the first one, so that the first update searches.
     */
    private static final long INITIAL_SEARCH_TIME = Long.MIN_VALUE / 2;
    /**
     * The data a listed game shows for a room without {@link ScrRoomData}: no type, subtype, speed or map size.
     */
    private static final @NotNull ScrRoomData NO_ROOM_DATA = new ScrRoomData(0, 0, 0, 0, 0);

    /**
     * A game datagram the game link holds for the game socket, with its sender's address in the game.
     */
    private static final class HeldDatagram {
        private final @NotNull InetSocketAddress source;
        private final byte @NotNull [] payload;

        private HeldDatagram(@NotNull InetSocketAddress source, byte @NotNull [] payload) {
            this.source  = source;
            this.payload = payload;
        }
    }

    /**
     * Carries the game socket's datagrams on the Storm port, addressed by the participants' addresses.
     */
    private final class GameLink implements DatagramLink<@NotNull InetSocketAddress> {
        private final @NotNull DatagramLink<@NotNull LinkParticipant> stormPort;
        private final @NotNull DatagramLink<@NotNull LinkParticipant> roomPort;
        /**
         * The datagrams taken from the Storm port before their senders left the game, which the game socket receives first.
         */
        private final @NotNull Deque<@NotNull HeldDatagram> held;

        private GameLink(@NotNull DatagramLink<@NotNull LinkParticipant> stormPort, @NotNull DatagramLink<@NotNull LinkParticipant> roomPort) {
            this.stormPort = stormPort;
            this.roomPort  = roomPort;
            this.held      = new ArrayDeque<>();
        }

        /**
         * Sends one datagram: the remaining bytes of the buffer.
         *
         * @param datagram the datagram, read from its position to its limit
         * @param target   the receiver's address
         * @throws IOException if the link is closed or the datagram cannot be sent
         * @implSpec A datagram for an address of no participant of the game is lost, as on a network without that address.
         */
        @Override
        public void send(@NotNull ByteBuffer datagram, @NotNull InetSocketAddress target) throws IOException {
            synchronized (BeaconLinkProvider.this) {
                LinkParticipant participant = BeaconLinkProvider.this.book.findParticipant(target);

                if (participant == null) {
                    return;
                }

                if (datagram.remaining() > LinkOverlay.MAXIMUM_DATAGRAM_LENGTH) {
                    throw Exceptions.newIOException("A game datagram of {0} bytes is longer than the overlay carries.", datagram.remaining());
                }

                this.stormPort.send(datagram, participant);
            }
        }

        /**
         * Receives the next waiting datagram into the buffer, from its position; a datagram longer than the remaining space is cut. Returns at
         * once when none is waiting.
         *
         * @param buffer the buffer that receives the datagram
         * @return the sender's address, or {@code null} if no datagram is waiting
         * @throws IOException if the link is closed
         */
        @Override
        public @Nullable InetSocketAddress receive(@NotNull ByteBuffer buffer) throws IOException {
            synchronized (BeaconLinkProvider.this) {
                HeldDatagram first = this.held.pollFirst();

                if (first != null) {
                    buffer.put(first.payload, 0, Math.min(first.payload.length, buffer.remaining()));

                    return first.source;
                }

                int start = buffer.position();

                while (true) {
                    LinkParticipant sender = this.stormPort.receive(buffer);

                    if (sender == null) {
                        return null;
                    }

                    InetSocketAddress address = BeaconLinkProvider.this.book.findAddress(sender);

                    if (address != null) {
                        return address;
                    }

                    buffer.position(start);
                }
            }
        }

        /**
         * Closes the link; it receives and sends nothing afterwards.
         *
         * @implSpec Ends the game's use of the network: removes the game's participants from the overlay, closes both ports and withdraws a hosted
         * room.
         */
        @Override
        public void close() {
            synchronized (BeaconLinkProvider.this) {
                BeaconLinkProvider.this.closeGame(this);
            }
        }

        /**
         * Takes every datagram waiting at the Storm port, the datagrams the closed channels of leaving participants still hold included, into
         * the link's queue with its sender's address, while the senders still have their addresses.
         *
         * @throws IOException if the Storm port is closed
         */
        private void holdWaitingDatagrams() throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(LinkOverlay.MAXIMUM_DATAGRAM_LENGTH);

            for (LinkParticipant sender = this.stormPort.receive(buffer); sender != null; sender = this.stormPort.receive(buffer.clear())) {
                InetSocketAddress address = BeaconLinkProvider.this.book.findAddress(sender);

                if (address != null) {
                    this.held.addLast(new HeldDatagram(address, Arrays.copyOf(buffer.array(), buffer.position())));
                }
            }
        }
    }

    /**
     * Returns the address a participant has in the SC:R games over Beacon.Link it hosts, and the one it asks for when it joins: derived from
     * its key, the same on every machine. The host of a game gives a joiner whose address is taken its next derived address.
     *
     * @param participant the participant
     * @return the address
     */
    public static @NotNull InetSocketAddress getAddress(@NotNull LinkParticipant participant) {
        return StormAddressBook.deriveAddress(participant, 0);
    }

    /**
     * Cuts a text to the longest prefix of whole characters whose UTF-8 form fits a room name.
     *
     * @param text the text
     * @return the prefix
     */
    private static @NotNull String limitRoomText(@NotNull String text) {
        String limited = text;

        while (limited.getBytes(StandardCharsets.UTF_8).length > RoomSettings.MAXIMUM_NAME_LENGTH) {
            limited = limited.substring(0, limited.offsetByCodePoints(limited.length(), -1));
        }

        return limited;
    }

    /**
     * Creates the listed game of a room: its settings and its {@link ScrRoomData} at the host's address.
     *
     * @param room the room
     * @return the created {@link LanDiscovery.Game} instance
     */
    private static LanDiscovery.@NotNull Game createGame(@NotNull FoundRoom room) {
        RoomSettings settings = room.getSettings();
        ScrRoomData data = ScrRoomData.decode(settings.getModData());
        ScrRoomData shown = data == null ? NO_ROOM_DATA : data;
        LanGameInformation information = new LanGameInformation(settings.getRoomName(), "",
                                                                shown.getGameType(), shown.getSubtype(),
                                                                shown.getSpeed(), 0,
                                                                settings.getPlayers(), settings.getCapacity(), 0,
                                                                0, 0,
                                                                0,
                                                                0,
                                                                0, 0,
                                                                false, false,
                                                                0L,
                                                                settings.getMapName(), "",
                                                                0L, 0L,
                                                                "",
                                                                0,
                                                                shown.getMapWidth(), shown.getMapHeight());

        return new LanDiscovery.Game(information, getAddress(room.getHost()), room.getNumber());
    }

    private static long getCurrentMilliseconds() {
        return System.nanoTime() / NANOSECONDS_PER_MILLISECOND;
    }


    private final @NotNull BeaconLinkSession session;

    private @Nullable RoomSearch search;
    private long searchTime;
    private @NotNull List<@NotNull FoundRoom> rooms;
    private @NotNull List<@NotNull FoundRoom> otherRooms;

    private @Nullable GameLink link;
    private final @NotNull StormAddressBook book;
    private final @NotNull ByteBuffer roomBuffer;

    private @NotNull String password;

    private @Nullable HostedRoom hostedRoom;
    private @Nullable LanRecord hostedRecord;
    private final @NotNull List<@NotNull LinkParticipant> members;
    private final @NotNull Map<@NotNull LinkParticipant, @NotNull Set<@NotNull LinkParticipant>> reports;
    /**
     * The addresses of the members that left the hosted game, until the client takes them ({@link #pollDeparture()}).
     */
    private final @NotNull Deque<@NotNull InetSocketAddress> departures;
    private long announceTime;

    private @Nullable FoundRoom joinedRoom;
    private @Nullable ParticipantChannel hostChannel;
    private @Nullable LanRecord hostRecord;
    private LanDiscovery.@Nullable Game connectedGame;
    private ProviderSession.@Nullable JoinState connectionFailure;

    /**
     * Creates a {@link BeaconLinkProvider} instance on a session, which it owns and closes.
     *
     * @param session the session of the SC:R mod's identity
     */
    public BeaconLinkProvider(@NotNull BeaconLinkSession session) {
        this.session = session;

        this.search     = null;
        this.searchTime = INITIAL_SEARCH_TIME;
        this.rooms      = List.of();
        this.otherRooms = List.of();

        this.link       = null;
        this.book       = new StormAddressBook();
        this.roomBuffer = ByteBuffer.allocate(LinkOverlay.MAXIMUM_DATAGRAM_LENGTH);

        this.password = "";

        this.hostedRoom   = null;
        this.hostedRecord = null;
        this.members      = new ArrayList<>();
        this.reports      = new LinkedHashMap<>();
        this.departures   = new ArrayDeque<>();
        this.announceTime = 0L;

        this.joinedRoom        = null;
        this.hostChannel       = null;
        this.hostRecord        = null;
        this.connectedGame     = null;
        this.connectionFailure = null;
    }

    /**
     * @return the provider's session
     */
    public @NotNull BeaconLinkSession getSession() {
        return this.session;
    }

    /**
     * @return the Beacon.Link session the network runs on, whose community part (presence, channels, whispers) the multiplayer screens show,
     * or {@code null} for a network without one
     * @implSpec Returns the provider's session.
     */
    @Override
    public @NotNull BeaconLinkSession getBeaconLinkSession() {
        return this.session;
    }

    /**
     * Finds the participant of the game that an address of the game names.
     *
     * @param address the address, as the provider session knows its peers
     * @return the participant, or {@code null} if the address names no participant of the game
     */
    public synchronized @Nullable LinkParticipant findParticipant(@NotNull InetSocketAddress address) {
        return this.book.findParticipant(address);
    }

    /**
     * Finds the channel that carries a game participant's datagrams, whose delay and relay state the game's network report shows.
     *
     * @param address the participant's address, as the provider session knows its peers
     * @return the channel, or {@code null} for a network without channels or an address of no participant of the game
     * @implSpec Returns the overlay's channel to the participant that the address names.
     */
    @Override
    public synchronized @Nullable ParticipantChannel findChannel(@NotNull InetSocketAddress address) {
        LinkParticipant participant = this.book.findParticipant(address);

        return participant == null ? null : this.session.getOverlay().getChannel(participant);
    }

    /**
     * Lists the games found on the network, except the one this client hosts.
     *
     * @return the games
     * @implSpec Lists the rooms of the last complete search by the SC:R mod's identity, without the session's own, at the host's address: the room
     * name, the map name, the player counts and the room's {@link ScrRoomData} (game type, subtype, speed and map size; zero when a room
     * carries none); the other attributes arrive with the join.
     */
    @Override
    public synchronized @NotNull List<LanDiscovery.Game> getGames() {
        List<LanDiscovery.Game> games = new ArrayList<>(this.rooms.size());

        for (FoundRoom room : this.rooms) {
            games.add(createGame(room));
        }

        return games;
    }

    /**
     * @return the rooms found on a network of rooms, except the one this client hosts: first the joinable ones, which {@link #getGames()}
     * lists as games, then those of another version or content of the mod, each with the refusal its host gives this run; empty for a
     * network without rooms
     * @implSpec Returns the rooms of the last complete search by the SC:R mod's identity; each room's settings carry its {@link ScrRoomData} as the mod
     * data, and {@link #getGames()} lists the joinable ones at the hosts' addresses ({@link #getAddress}).
     */
    @Override
    public synchronized @NotNull List<@NotNull FoundRoom> getRooms() {
        List<FoundRoom> found = new ArrayList<>(this.rooms.size() + this.otherRooms.size());

        found.addAll(this.rooms);
        found.addAll(this.otherRooms);

        return found;
    }

    /**
     * @return the game whose host {@link #connect} made reachable, with the host's own game attributes, or {@code null} while the connection
     * is pending, has failed or was never started
     */
    @Override
    public synchronized LanDiscovery.@Nullable Game getConnectedGame() {
        return this.connectedGame;
    }

    /**
     * @return why the connection {@link #connect} started failed ({@link ProviderSession.JoinState#REJECTED} or
     * {@link ProviderSession.JoinState#TIMED_OUT}), or {@code null} while it is pending or reached the host
     */
    @Override
    public synchronized ProviderSession.@Nullable JoinState getConnectionFailure() {
        return this.connectionFailure;
    }

    /**
     * @return why the host refused the connection {@link #connect} started, when {@link #getConnectionFailure()} is
     * {@link ProviderSession.JoinState#REJECTED} and the network names a reason, otherwise {@code null}
     * @implSpec Returns the refusal of the channel to the joined room's host, for example a wrong password of a private room.
     */
    @Override
    public synchronized @Nullable JoinRefusal getJoinRefusal() {
        ParticipantChannel channel = this.hostChannel;

        return this.connectionFailure == ProviderSession.JoinState.REJECTED && channel != null ? channel.getJoinRefusal() : null;
    }

    /**
     * Sets the password of the games this network hosts from now on and of the game {@link #connect} joins next: a hosted game with a
     * password is private, and a private game's host refuses a joiner without its password. A network without private games ignores it.
     *
     * @param password the password, empty for none
     * @implSpec A room published from now on is private with the password (the room's advertisement says so; the password is never sent), and the
     * next join proves the password to a private room's host.
     */
    @Override
    public synchronized void setPassword(@NotNull String password) {
        this.password = password;
    }


    /**
     * @return {@code true} if the hosted game may start as far as the network goes (every pair of its players can reach each other) or this
     * client hosts no game, {@code false} otherwise (a pair of players is not connected yet)
     * @implSpec Hosting, the host's channel to every member must be open and every member must have reported a channel open to every other member.
     */
    @Override
    public synchronized boolean isReadyToStart() {
        if (this.link == null || this.hostedRoom == null) {
            return true;
        }

        for (LinkParticipant member : this.members) {
            ParticipantChannel channel = this.session.getOverlay().getChannel(member);
            Set<LinkParticipant> report = this.reports.get(member);

            if (channel == null || channel.getState() != ChannelState.OPEN) {
                return false;
            }

            for (LinkParticipant other : this.members) {
                if (!other.equals(member) && (report == null || !report.contains(other))) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * @return why the network cannot list, host or join games, as a text to show, or {@code null} while it can or is still trying
     * @implSpec Returns the description of the session's failure, for example that no node answered.
     */
    @Override
    public synchronized @Nullable String getFailureText() {
        LinkFailure failure = this.session.getFailure();

        return failure == null ? null : failure.getDescription();
    }


    /**
     * Sets the player's presence record, publishing it or updating it, on a network that shows presences.
     *
     * @param record the presence record
     * @implSpec Does nothing: Beacon.Link shows no presences.
     */
    @Override
    public void setPresence(@NotNull LanRecord record) {
    }

    /**
     * Sets the hosted game's record, publishing the game or updating it.
     *
     * @param record the game record
     * @param port   the game socket's local port ({@link GameProxySocket#getLocalPort()})
     * @implSpec Publishes the room of the game at the first call and updates it afterwards: the game name, the map name (both cut to
     * {@value RoomSettings#MAXIMUM_NAME_LENGTH} UTF-8 bytes), the players, the open player slots and the {@link ScrRoomData} of the game as
     * the room's mod data. The members receive the record with the next room message.
     */
    @Override
    public synchronized void setGame(@NotNull LanRecord record, int port) {
        LanGameInformation information = LanGameInformation.of(record);

        if (this.link == null || information == null || information.getName().isEmpty()) {
            return;
        }

        int capacity = Math.max(1, information.getPlayersMaximum() - information.getClosedSlots());
        RoomSettings settings = new RoomSettings(limitRoomText(information.getName()), limitRoomText(information.getMapName()),
                                                 Math.clamp(information.getPlayersCurrent(), 0, capacity), capacity,
                                                 ScrRoomData.of(information).encode());
        HostedRoom room = this.hostedRoom;

        this.hostedRecord = record;

        if (room == null) {
            this.hostedRoom = this.session.publishRoom(settings, this.password);

            LOGGER.info("BEACON.LINK ROOM PUBLISHED: " + settings);
        } else if (room.getState() != HostedRoomState.WITHDRAWN) {
            room.update(settings);
        }
    }

    /**
     * Withdraws the hosted game of the given name.
     *
     * @param name the game name
     * @implSpec Withdraws the room; its members stay in the overlay until the game socket closes.
     */
    @Override
    public synchronized void withdraw(@NotNull String name) {
        HostedRoom room = this.hostedRoom;

        if (room != null) {
            room.withdraw();
        }
    }

    /**
     * Opens the socket of a hosted or joined game on this network; closing the socket ends the game's use of the network.
     *
     * @param port the local UDP port on the local network, {@link GameProxySocket#GAME_PORT} for a game
     * @return the opened socket
     * @throws IOException if the socket cannot be opened
     * @implSpec Opens the Storm and room ports of the session's overlay; the port is not used. The socket's local port is the session participant's.
     */
    @Override
    public synchronized @NotNull GameProxySocket openGameSocket(int port) throws IOException {
        GameLink previous = this.link;

        if (previous != null) {
            this.closeGame(previous);
        }

        LinkOverlay overlay;
        DatagramLink<LinkParticipant> stormPort;

        try {
            overlay   = this.session.getOverlay();
            stormPort = overlay.openPort(STORM_PORT);
        } catch (IllegalStateException exception) {
            throw Exceptions.newIOException(exception, "The Beacon.Link session cannot carry a game: {0}", exception.getMessage());
        }

        DatagramLink<LinkParticipant> roomPort;

        try {
            roomPort = overlay.openPort(ROOM_PORT);
        } catch (IllegalStateException exception) {
            stormPort.close();

            throw Exceptions.newIOException(exception, "The Beacon.Link session cannot carry a game: {0}", exception.getMessage());
        }

        GameLink opened = new GameLink(stormPort, roomPort);

        this.link = opened;

        return GameProxySocket.open(opened, getAddress(this.session.getParticipant()).getPort());
    }

    /**
     * Starts making the host of a listed game reachable for the game socket; {@link #getConnectedGame()} returns the game once it is.
     *
     * @param game the game, as {@link #getGames()} listed it
     * @implSpec Joins the listed room whose host has the game's address: the host checks the engine version and the mod identity, a refusal ends the
     * connection with {@link ProviderSession.JoinState#REJECTED}, every other failure of the channel with
     * {@link ProviderSession.JoinState#TIMED_OUT}.
     */
    @Override
    public synchronized void connect(LanDiscovery.@NotNull Game game) {
        FoundRoom found = null;

        for (FoundRoom room : this.rooms) {
            if (getAddress(room.getHost()).equals(game.getAddress())) {
                found = room;
            }
        }

        this.connectedGame     = null;
        this.connectionFailure = null;
        this.hostRecord        = null;

        if (found == null || this.link == null) {
            LOGGER.warning("BEACON.LINK JOIN FAILED: no listed room of " + game.getAddress());

            this.connectionFailure = ProviderSession.JoinState.TIMED_OUT;

            return;
        }

        try {
            this.hostChannel = this.session.joinRoom(found, this.password);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            LOGGER.warning("BEACON.LINK JOIN FAILED: " + exception.getMessage());

            this.connectionFailure = ProviderSession.JoinState.TIMED_OUT;

            return;
        }

        this.joinedRoom = found;
        this.addParticipant(found.getHost(), getAddress(found.getHost()));
    }

    /**
     * Carries out the network's due work without waiting.
     *
     * @implSpec Polls the session; without a game it searches the rooms once a second, a host takes its room's joined participants and sends the room
     * messages, and a joiner reads them and follows its channel to the host.
     */
    @Override
    public synchronized void update() {
        try {
            this.session.poll();
        } catch (IOException exception) {
            LOGGER.debug("BEACON.LINK POLL FAILED: " + exception.getMessage());
        }

        long now = getCurrentMilliseconds();
        GameLink game = this.link;

        if (game == null) {
            this.updateSearch(now);
        } else if (this.hostedRoom != null) {
            this.updateHostedRoom(game, now);
        } else if (this.joinedRoom != null) {
            this.updateJoinedRoom(game, now);
        }
    }

    /**
     * Takes the address of the next player of the hosted game whose connection the network saw end, in the order the network saw them.
     *
     * @return the player's address in the game, or {@code null} if none is waiting or the network does not see connections end
     * @implSpec Returns the address of the next member whose channel to the host closed; the datagrams the member sent before were taken for
     * the game socket first.
     */
    @Override
    public synchronized @Nullable InetSocketAddress pollDeparture() {
        return this.departures.pollFirst();
    }

    private void updateSearch(long now) {
        RoomSearch current = this.search;

        if (current != null && current.isComplete()) {
            this.rooms      = this.withoutOwnRooms(current.getRooms());
            this.otherRooms = this.withoutOwnRooms(current.getOtherRooms());
            this.search     = null;
        }

        if (this.search == null && now - this.searchTime >= SEARCH_INTERVAL_MILLISECONDS) {
            this.search     = this.session.searchRooms();
            this.searchTime = now;
        }
    }

    private @NotNull List<@NotNull FoundRoom> withoutOwnRooms(@NotNull List<@NotNull FoundRoom> rooms) {
        List<FoundRoom> found = new ArrayList<>(rooms.size());

        for (FoundRoom room : rooms) {
            if (!room.getHost().equals(this.session.getParticipant())) {
                found.add(room);
            }
        }

        return List.copyOf(found);
    }

    private void updateHostedRoom(@NotNull GameLink game, long now) {
        HostedRoom room = this.hostedRoom;
        boolean changed = false;

        if (room == null) {
            return;
        }

        // A member whose channel to the host closed leaves the room, before a new channel of the same participant joins it again.
        for (LinkParticipant member : List.copyOf(this.members)) {
            ParticipantChannel channel = this.session.getOverlay().getChannel(member);

            if (channel != null && channel.getState() != ChannelState.CLOSED) {
                continue;
            }

            LOGGER.info("BEACON.LINK MEMBER LEFT: " + member);

            // The game socket still receives what the member sent before its channel closed, such as its leave.
            try {
                game.holdWaitingDatagrams();
            } catch (IOException exception) {
                LOGGER.debug("BEACON.LINK GAME DATAGRAMS NOT HELD: " + exception.getMessage());
            }

            InetSocketAddress address = this.book.findAddress(member);

            if (address != null) {
                this.departures.addLast(address);
            }

            this.members.remove(member);
            this.reports.remove(member);
            this.removeParticipant(member);

            changed = true;
        }

        for (ParticipantChannel accepted = room.acceptChannel(); accepted != null; accepted = room.acceptChannel()) {
            LinkParticipant peer = accepted.getPeer();

            if (this.members.contains(peer)) {
                continue;
            }

            InetSocketAddress address = this.book.findFreeAddress(peer, getAddress(this.session.getParticipant()));

            if (this.members.size() >= StormRoomMessage.MAXIMUM_MEMBERS || address == null || !this.addParticipant(peer, address)) {
                accepted.close();

                continue;
            }

            LOGGER.info("BEACON.LINK MEMBER JOINED: " + peer);
            this.members.add(peer);

            changed = true;
        }

        this.drainRoomPort(game, null);

        if (changed || now - this.announceTime >= ANNOUNCE_INTERVAL_MILLISECONDS) {
            this.announceTime = now;
            this.announce(game);
        }
    }

    /**
     * Sends the game's record and the members with their addresses to every member.
     */
    private void announce(@NotNull GameLink game) {
        LanRecord record = this.hostedRecord;
        byte[] gameMessage = null;

        if (record != null) {
            try {
                gameMessage = StormRoomMessage.encodeGame(record);
            } catch (IllegalArgumentException exception) {
                LOGGER.warning("BEACON.LINK GAME RECORD NOT SENT: " + exception.getMessage());
            }
        }

        Map<LinkParticipant, InetSocketAddress> listed = new LinkedHashMap<>();

        for (LinkParticipant member : this.members) {
            InetSocketAddress address = this.book.findAddress(member);

            if (address != null) {
                listed.put(member, address);
            }
        }

        byte[] membersMessage = StormRoomMessage.encodeMembers(listed);

        for (LinkParticipant member : this.members) {
            try {
                if (gameMessage != null) {
                    game.roomPort.send(ByteBuffer.wrap(gameMessage), member);
                }

                game.roomPort.send(ByteBuffer.wrap(membersMessage), member);
            } catch (IOException exception) {
                LOGGER.debug("BEACON.LINK ROOM MESSAGE NOT SENT: " + exception.getMessage());
            }
        }
    }

    private void updateJoinedRoom(@NotNull GameLink game, long now) {
        FoundRoom room = this.joinedRoom;
        ParticipantChannel channel = this.hostChannel;

        if (room == null || channel == null) {
            return;
        }

        this.drainRoomPort(game, room.getHost());

        if (now - this.announceTime >= ANNOUNCE_INTERVAL_MILLISECONDS && channel.getState() == ChannelState.OPEN) {
            this.announceTime = now;
            this.reportChannels(game, room.getHost());
        }

        if (this.connectedGame != null || this.connectionFailure != null) {
            return;
        }

        LanRecord record = this.hostRecord;
        LanGameInformation information = record == null ? null : LanGameInformation.of(record);

        if (channel.getState() == ChannelState.CLOSED) {
            ChannelFailure failure = channel.getFailure();

            LOGGER.warning("BEACON.LINK JOIN FAILED: " + (failure == null ? "the channel closed" : failure.getDescription()));

            this.connectionFailure = failure == ChannelFailure.JOIN_REFUSED ? ProviderSession.JoinState.REJECTED
                                                                            : ProviderSession.JoinState.TIMED_OUT;
        } else if (channel.getState() == ChannelState.OPEN && record != null && information != null) {
            LOGGER.info("BEACON.LINK HOST REACHED: " + room.getHost() + " (" + information.getName() + ")");

            this.connectedGame = new LanDiscovery.Game(information, getAddress(room.getHost()), record.getRevision());
        }
    }

    /**
     * Tells the host which members this joiner's channels are open to.
     */
    private void reportChannels(@NotNull GameLink game, @NotNull LinkParticipant host) {
        List<LinkParticipant> connected = new ArrayList<>();

        for (LinkParticipant participant : this.book.getParticipants()) {
            ParticipantChannel channel = this.session.getOverlay().getChannel(participant);

            if (!participant.equals(host) && channel != null && channel.getState() == ChannelState.OPEN &&
                connected.size() < StormRoomMessage.MAXIMUM_MEMBERS) {
                connected.add(participant);
            }
        }

        try {
            game.roomPort.send(ByteBuffer.wrap(StormRoomMessage.encodeChannels(connected)), host);
        } catch (IOException exception) {
            LOGGER.debug("BEACON.LINK ROOM MESSAGE NOT SENT: " + exception.getMessage());
        }
    }

    /**
     * Reads the room port: a joiner takes the host's messages, a host its members' channel reports.
     *
     * @param game the game link
     * @param host the host whose messages count, {@code null} on the host
     */
    private void drainRoomPort(@NotNull GameLink game, @Nullable LinkParticipant host) {
        ByteBuffer buffer = this.roomBuffer;

        while (true) {
            LinkParticipant sender;

            buffer.clear();

            try {
                sender = game.roomPort.receive(buffer);
            } catch (IOException exception) {
                return;
            }

            if (sender == null) {
                return;
            }

            boolean fromMember = host == null && this.members.contains(sender);
            StormRoomMessage message = sender.equals(host) || fromMember ? StormRoomMessage.parse(Arrays.copyOf(buffer.array(), buffer.position()))
                                                                         : null;

            if (message == null) {
                continue;
            }

            if (fromMember) {
                if (message.getKind() == StormRoomMessage.Kind.CHANNELS) {
                    this.reports.put(sender, Set.copyOf(message.getConnected()));
                }
            } else if (message.getKind() == StormRoomMessage.Kind.GAME) {
                this.hostRecord = message.getRecord();
            } else {
                this.applyMembers(message.getMembers(), host);
            }
        }
    }

    /**
     * Takes the host's member list as the whole truth: removes the members it no longer lists, moves a member to the address the host gives
     * it, and adds the listed members this joiner does not have yet.
     *
     * @param members the members with their addresses
     * @param host    the host, which stays
     */
    private void applyMembers(@NotNull Map<@NotNull LinkParticipant, @NotNull InetSocketAddress> members, @NotNull LinkParticipant host) {
        LinkParticipant self = this.session.getParticipant();

        for (LinkParticipant known : this.book.getParticipants()) {
            if (!known.equals(host) && !members.containsKey(known)) {
                LOGGER.info("BEACON.LINK MEMBER LEFT: " + known);
                this.removeParticipant(known);
            }
        }

        for (Map.Entry<LinkParticipant, InetSocketAddress> member : members.entrySet()) {
            LinkParticipant participant = member.getKey();
            InetSocketAddress current = this.book.findAddress(participant);

            if (participant.equals(self) || participant.equals(host)) {
                continue;
            }

            if (member.getValue().equals(current)) {
                this.reopenChannel(participant);

                continue;
            }

            if (current == null) {
                this.addParticipant(participant, member.getValue());
            } else if (!this.book.put(participant, member.getValue())) {
                LOGGER.warning("BEACON.LINK MEMBER ADDRESS IGNORED: " + participant + " at " + member.getValue());
            }
        }
    }

    /**
     * Opens a new channel to a member whose channel closed.
     *
     * @param participant the member
     */
    private void reopenChannel(@NotNull LinkParticipant participant) {
        ParticipantChannel channel = this.session.getOverlay().getChannel(participant);

        if (channel != null && channel.getState() != ChannelState.CLOSED) {
            return;
        }

        try {
            // noinspection resource
            this.session.getOverlay().addParticipant(participant);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            LOGGER.warning("BEACON.LINK CHANNEL NOT REOPENED: " + participant + ": " + exception.getMessage());
        }
    }

    /**
     * Adds a participant to the game: to the overlay and to the game socket's addresses.
     *
     * @param participant the participant
     * @param address     the participant's address in the game
     * @return {@code true} if the participant was added, {@code false} otherwise (the address is another participant's or the overlay holds
     * the most channels it allows)
     */
    private boolean addParticipant(@NotNull LinkParticipant participant, @NotNull InetSocketAddress address) {
        LinkParticipant holder = this.book.findParticipant(address);

        if (holder != null && !holder.equals(participant)) {
            LOGGER.warning("BEACON.LINK PARTICIPANT IGNORED: " + participant + " has the address of " + holder);

            return false;
        }

        try {
            this.session.getOverlay().addParticipant(participant);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            LOGGER.warning("BEACON.LINK PARTICIPANT IGNORED: " + participant + ": " + exception.getMessage());

            return false;
        }

        this.book.put(participant, address);

        return true;
    }

    private void removeParticipant(@NotNull LinkParticipant participant) {
        this.book.remove(participant);
        this.session.getOverlay().removeParticipant(participant);
    }

    /**
     * Ends a game's use of the network.
     *
     * @param game the game's link; nothing happens unless it is the current one
     */
    private void closeGame(@NotNull GameLink game) {
        if (this.link != game) {
            return;
        }

        HostedRoom room = this.hostedRoom;

        if (room != null) {
            room.withdraw();
        }

        for (LinkParticipant participant : this.book.getParticipants()) {
            this.removeParticipant(participant);
        }

        try {
            game.roomPort.close();
            game.stormPort.close();
        } catch (IOException exception) {
            LOGGER.debug("BEACON.LINK PORT CLOSE FAILED: " + exception.getMessage());
        }

        this.link = null;

        game.held.clear();

        this.hostedRoom   = null;
        this.hostedRecord = null;
        this.members.clear();
        this.reports.clear();
        this.departures.clear();

        this.joinedRoom    = null;
        this.hostChannel   = null;
        this.hostRecord    = null;
        this.connectedGame = null;
    }


    /**
     * Closes the network: it withdraws what it published and closes its sockets.
     *
     * @implSpec Ends the current game's use of the network and closes the session.
     */
    @Override
    public synchronized void close() {
        GameLink game = this.link;

        if (game != null) {
            this.closeGame(game);
        }

        this.session.close();
    }
}
