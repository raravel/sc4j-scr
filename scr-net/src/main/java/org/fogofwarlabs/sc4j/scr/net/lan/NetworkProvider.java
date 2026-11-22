package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLinkSession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.FoundRoom;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.ParticipantChannel;
import org.fogofwarlabs.sc4j.engine.net.lobby.JoinRefusal;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanDiscovery;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanRecord;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.fogofwarlabs.sc4j.scr.net.proxy.GameProxySocket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Holds the network a {@link LanClient} lists, hosts and joins its games on: the local network ({@link LocalNetworkProvider}: the LAN
 * discovery and a UDP game socket) or Beacon.Link (the SC:R mod's rooms and the overlay of the participants' channels). The provider session,
 * the lobby and the game run on every network the same way; a network only lists the games, publishes the hosted one, carries the game
 * socket's datagrams and makes the host of a joined game reachable.
 * <p>
 * The client calls a provider from its own thread or under its lock; {@link #update()} runs with every pump of the client.
 */
public interface NetworkProvider extends AutoCloseable {
    /**
     * Lists the games found on the network, except the one this client hosts.
     *
     * @return the games
     */
    @NotNull List<LanDiscovery.Game> getGames();

    /**
     * @return the rooms found on a network of rooms, except the one this client hosts: first the joinable ones, which {@link #getGames()}
     * lists as games, then those of another version or content of the mod, each with the refusal its host gives this run; empty for a
     * network without rooms
     */
    default @NotNull List<@NotNull FoundRoom> getRooms() {
        return List.of();
    }

    /**
     * @return the game whose host {@link #connect} made reachable, with the host's own game attributes, or {@code null} while the connection
     * is pending, has failed or was never started
     */
    LanDiscovery.@Nullable Game getConnectedGame();

    /**
     * @return why the connection {@link #connect} started failed ({@link ProviderSession.JoinState#REJECTED} or
     * {@link ProviderSession.JoinState#TIMED_OUT}), or {@code null} while it is pending or reached the host
     */
    ProviderSession.@Nullable JoinState getConnectionFailure();

    /**
     * @return {@code true} if the hosted game may start as far as the network goes (every pair of its players can reach each other) or this
     * client hosts no game, {@code false} otherwise (a pair of players is not connected yet)
     */
    boolean isReadyToStart();

    /**
     * @return why the network cannot list, host or join games, as a text to show, or {@code null} while it can or is still trying
     */
    @Nullable String getFailureText();

    /**
     * @return the Beacon.Link session the network runs on, whose community part (presence, channels, whispers) the multiplayer screens show,
     * or {@code null} for a network without one
     */
    default @Nullable BeaconLinkSession getBeaconLinkSession() {
        return null;
    }

    /**
     * @return why the host refused the connection {@link #connect} started, when {@link #getConnectionFailure()} is
     * {@link ProviderSession.JoinState#REJECTED} and the network names a reason, otherwise {@code null}
     */
    default @Nullable JoinRefusal getJoinRefusal() {
        return null;
    }

    /**
     * Finds the channel that carries a game participant's datagrams, whose delay and relay state the game's network report shows.
     *
     * @param address the participant's address, as the provider session knows its peers
     * @return the channel, or {@code null} for a network without channels or an address of no participant of the game
     */
    default @Nullable ParticipantChannel findChannel(@NotNull InetSocketAddress address) {
        return null;
    }

    /**
     * Sets the password of the games this network hosts from now on and of the game {@link #connect} joins next: a hosted game with a
     * password is private, and a private game's host refuses a joiner without its password. A network without private games ignores it.
     *
     * @param password the password, empty for none
     */
    default void setPassword(@NotNull String password) {
    }


    /**
     * Sets the player's presence record, publishing it or updating it, on a network that shows presences.
     *
     * @param record the presence record
     */
    void setPresence(@NotNull LanRecord record);

    /**
     * Sets the hosted game's record, publishing the game or updating it.
     *
     * @param record the game record
     * @param port   the game socket's local port ({@link GameProxySocket#getLocalPort()})
     */
    void setGame(@NotNull LanRecord record, int port);

    /**
     * Withdraws the hosted game of the given name.
     *
     * @param name the game name
     */
    void withdraw(@NotNull String name);

    /**
     * Opens the socket of a hosted or joined game on this network; closing the socket ends the game's use of the network.
     *
     * @param port the local UDP port on the local network, {@link GameProxySocket#GAME_PORT} for a game
     * @return the opened socket
     * @throws IOException if the socket cannot be opened
     */
    @NotNull GameProxySocket openGameSocket(int port) throws IOException;

    /**
     * Starts making the host of a listed game reachable for the game socket; {@link #getConnectedGame()} returns the game once it is.
     *
     * @param game the game, as {@link #getGames()} listed it
     */
    void connect(LanDiscovery.@NotNull Game game);

    /**
     * Carries out the network's due work without waiting.
     */
    void update();

    /**
     * Takes the address of the next player of the hosted game whose connection the network saw end, in the order the network saw them.
     *
     * @return the player's address in the game, or {@code null} if none is waiting or the network does not see connections end
     */
    default @Nullable InetSocketAddress pollDeparture() {
        return null;
    }

    /**
     * Closes the network: it withdraws what it published and closes its sockets.
     */
    @Override
    void close();
}
