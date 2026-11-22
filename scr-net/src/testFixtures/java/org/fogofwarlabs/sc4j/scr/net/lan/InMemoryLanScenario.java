package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.engine.net.lobby.MapFolders;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.save.SaveGameInformation;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderPacket;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.fogofwarlabs.sc4j.scr.net.proxy.GameProxySocket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/**
 * Provider sessions and their lobbies over in-memory datagrams routed by address, on one millisecond clock: client {@code 0}
 * hosts, the others join it one after another. A client can be silenced (its datagrams vanish both ways, as a player whose
 * network went away) or leave. A datagram arrives {@link #latency} milliseconds after it was sent, up to {@link #jitter}
 * milliseconds later at random, and {@link #lossPercent} percent of them are lost; every client's packets are recorded ({@link #sentPackets}).
 */
final class InMemoryLanScenario {
    private static final int CLOCK_BASE = 10_000_000;

    private static final @NotNull String ADDRESS_PREFIX = "192.168.0.";
    private static final int FIRST_HOST_NUMBER = 10;
    /**
     * The name of the host's game unless a test names it ({@link #gameName}).
     */
    private static final @NotNull String DEFAULT_GAME_NAME = "game";

    private static final int HOST = 0;
    private static final int PERCENT = 100;

    private static final int JOIN_LIMIT_MILLISECONDS = 10_000;
    /**
     * The time a leaving client's provider needs to send its leave, and the time the others need to process the departure.
     */
    private static final int LEAVE_MILLISECONDS = 200;
    private static final int DEPARTURE_MILLISECONDS = 2000;

    private static final class Datagram {
        private final @NotNull InetSocketAddress source;
        private final byte @NotNull [] packet;
        private final int due;
        private final long order;

        private Datagram(@NotNull InetSocketAddress source, byte @NotNull [] packet, int due, long order) {
            this.source = source;
            this.packet = packet;
            this.due    = due;
            this.order  = order;
        }
    }

    final @NotNull InetSocketAddress @NotNull [] addresses;
    final @NotNull ProviderSession @NotNull [] sessions;
    final @Nullable LanLobby @NotNull [] lobbies;
    /**
     * The packets each client sent, in order, a silenced client's too.
     */
    final @NotNull List<List<ProviderPacket>> sentPackets;
    private final @NotNull Map<InetSocketAddress, PriorityQueue<Datagram>> queues;
    private final boolean @NotNull [] silent;
    private long sentDatagrams;

    int now;
    /**
     * The one-way delay of every datagram in milliseconds.
     */
    int latency;
    /**
     * The most milliseconds a datagram arrives later than {@link #latency}, at random: datagrams arrive in another order.
     */
    int jitter;
    /**
     * The percent of datagrams that are lost, at random.
     */
    int lossPercent;
    /**
     * The random source of the losses and the jitter.
     */
    @NotNull Random random;
    /**
     * The name of the game the host creates ({@link #host}).
     */
    @NotNull String gameName;
    /**
     * Runs after every {@link #step()}.
     */
    @NotNull Runnable afterStep;

    /**
     * Creates an {@link InMemoryLanScenario} instance whose clients have their addresses and provider sessions but no lobby yet.
     *
     * @param clients the number of clients, the host first
     */
    InMemoryLanScenario(int clients) {
        this.addresses   = new InetSocketAddress[clients];
        this.sessions    = new ProviderSession[clients];
        this.lobbies     = new LanLobby[clients];
        this.sentPackets = new ArrayList<>();
        this.queues      = new HashMap<>();
        this.silent      = new boolean[clients];

        this.now         = CLOCK_BASE;
        this.latency     = 0;
        this.jitter      = 0;
        this.lossPercent = 0;
        this.random      = new Random(0);
        this.gameName    = DEFAULT_GAME_NAME;
        this.afterStep   = () -> {
        };

        for (int index = 0; index < clients; index++) {
            InetSocketAddress source = new InetSocketAddress(ADDRESS_PREFIX + (FIRST_HOST_NUMBER + index), GameProxySocket.GAME_PORT);
            int sender = index;

            this.addresses[index] = source;
            this.sentPackets.add(new ArrayList<>());
            this.queues.put(source, new PriorityQueue<>(Comparator.comparingInt((Datagram datagram) -> datagram.due - CLOCK_BASE)
                                                                  .thenComparingLong(datagram -> datagram.order)));
            this.sessions[index] = new ProviderSession((address, packet) -> {
                PriorityQueue<Datagram> queue = this.queues.get(address);

                this.sentPackets.get(sender).add(packet);

                if (queue != null && !this.silent[sender] && this.random.nextInt(PERCENT) >= this.lossPercent) {
                    int delay = this.latency + (this.jitter == 0 ? 0 : this.random.nextInt(this.jitter + 1));

                    queue.add(new Datagram(source, packet.getEncodedBytes(), this.now + delay, this.sentDatagrams++));
                }
            }, () -> this.now);
        }
    }

    /**
     * Delivers all due datagrams, including those generated by deliveries, then updates the sessions.
     */
    void deliver() {
        boolean delivered = true;

        while (delivered) {
            delivered = false;

            for (int index = 0; index < this.addresses.length; index++) {
                PriorityQueue<Datagram> queue = this.queues.get(this.addresses[index]);

                while (!queue.isEmpty() && queue.peek().due - this.now <= 0) {
                    Datagram datagram = queue.poll();

                    if (!this.silent[index]) {
                        this.sessions[index].receive(datagram.source, datagram.packet);
                    }

                    delivered = true;
                }
            }
        }

        for (int index = 0; index < this.sessions.length; index++) {
            if (!this.silent[index]) {
                this.sessions[index].update();
            }
        }
    }

    /**
     * Advances the clock by one millisecond: delivers the network, then updates every lobby that has not reached the game.
     */
    void step() {
        this.now++;
        this.deliver();

        for (int index = 0; index < this.lobbies.length; index++) {
            LanLobby lobby = this.lobbies[index];

            if (lobby != null && !this.silent[index] && lobby.getState() != LanLobby.STATE_GAME) {
                lobby.update();
            }
        }

        this.afterStep.run();
    }

    /**
     * Runs until the condition holds.
     *
     * @param limit     the maximum milliseconds
     * @param condition the condition
     * @return the milliseconds it took
     */
    int runUntil(int limit, @NotNull Predicate<InMemoryLanScenario> condition) {
        for (int step = 0; step < limit; step++) {
            if (condition.test(this)) {
                return step;
            }

            this.step();
        }

        return limit;
    }

    /**
     * Advances the clock by the given milliseconds, one {@link #step()} at a time.
     *
     * @param milliseconds the milliseconds to run
     */
    void run(int milliseconds) {
        for (int step = 0; step < milliseconds; step++) {
            this.step();
        }
    }

    /**
     * Stops every datagram from and to the client.
     *
     * @param index the client
     */
    void silence(int index) {
        this.silent[index] = true;
    }

    /**
     * Lets a client leave the game: its provider sends the leave, then the client and its lobby go away (silenced) while
     * the others process the departure.
     *
     * @param index the client
     */
    void leave(int index) {
        this.sessions[index].leave(LanClient.LEAVE_REASON);
        this.run(LEAVE_MILLISECONDS);

        this.lobbies[index] = null;
        this.silence(index);
        this.run(DEPARTURE_MILLISECONDS);
    }

    /**
     * Creates the host's game and lobby.
     *
     * @param setup    the lobby table
     * @param gameData the encoded game options
     * @param map      the map the host shares, or {@code null}
     */
    void host(@NotNull LanLobby.Setup setup, byte @NotNull [] gameData, @Nullable Path map) {
        this.host(setup, gameData, map, PlayerTable.PLAYABLE_PLAYER_SLOTS, 0);
    }

    /**
     * Creates the host's game and lobby with the selected player and observer limits.
     *
     * @param setup          the lobby table
     * @param gameData       the encoded game options
     * @param map            the map the host shares, or {@code null}
     * @param maximumPlayers the maximum players
     * @param observers      the observer limit
     */
    void host(@NotNull LanLobby.Setup setup, byte @NotNull [] gameData, @Nullable Path map, int maximumPlayers, int observers) {
        this.sessions[HOST].createGame(this.gameName, "statistics", "", maximumPlayers, observers, 0, 0, gameData, "host", "");
        this.lobbies[HOST] = new LanLobby(this.sessions[HOST], () -> this.now, setup, "host", new byte[LanLobby.PLAYER_BLOCK_SIZE]);

        if (map != null) {
            this.lobbies[HOST].shareMap(map);
        }
    }

    /**
     * Joins a client and waits until it holds a slot.
     *
     * @param index    the client
     * @param maps     its map folder, or {@code null}
     * @param download its download folder, or {@code null}
     */
    void join(int index, @Nullable Path maps, @Nullable Path download) {
        LanLobby lobby = this.openLobby(index, null);

        lobby.setMapFolders(new MapFolders(maps == null ? List.of() : List.of(maps), download));
        this.waitForSlot(index);
    }

    /**
     * Begins a client's join and runs until the provider joined it and its lobby exists. The lobby then sends its
     * joined-game command once the host's game settings arrive.
     *
     * @param index     the client
     * @param savedGame the saved game the client brings, or {@code null} for a new game
     * @return the client's lobby
     */
    @NotNull LanLobby openLobby(int index, @Nullable SaveGameInformation savedGame) {
        this.sessions[index].beginJoin(this.addresses[HOST], "joiner" + index, "", "");

        for (int step = 0; step < JOIN_LIMIT_MILLISECONDS && this.lobbies[index] == null; step++) {
            this.step();

            if (this.sessions[index].pollJoin() == ProviderSession.JoinState.JOINED) {
                LanLobby joinedLobby = new LanLobby(this.sessions[index], () -> this.now, null, "joiner" + index,
                                                    new byte[LanLobby.PLAYER_BLOCK_SIZE]);

                if (savedGame != null) {
                    joinedLobby.setSavedGame(savedGame);
                }

                this.lobbies[index] = joinedLobby;
            }
        }

        LanLobby lobby = this.lobbies[index];

        Assertions.assertNotNull(lobby, "client " + index + " joined");

        return lobby;
    }

    /**
     * Runs until a joined client's lobby shows the client in a slot ({@link LanLobby#getPlayerSlot}).
     *
     * @param index the client
     */
    void waitForSlot(int index) {
        int player = this.sessions[index].getLocalIdentifier();

        Assertions.assertNotNull(this.lobbies[index]);

        this.runUntil(JOIN_LIMIT_MILLISECONDS, network -> network.hasSlot(index, player));

        Assertions.assertTrue(this.hasSlot(index, player), "client " + index + " holds a slot");
    }

    /**
     * Tells whether a client's lobby shows the client's player in a slot.
     *
     * @param index  the client
     * @param player the client's player identifier
     * @return {@code true} if the client's lobby exists and shows the player in a slot, {@code false} otherwise
     */
    private boolean hasSlot(int index, int player) {
        LanLobby joinedLobby = this.lobbies[index];

        return joinedLobby != null && joinedLobby.getPlayerSlot(player) >= 0;
    }
}
