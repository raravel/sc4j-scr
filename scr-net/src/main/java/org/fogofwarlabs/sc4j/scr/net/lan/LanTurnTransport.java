package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.lockstep.*;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.ParticipantChannel;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandCodecs;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.session.NetworkPeerUtils;
import org.fogofwarlabs.sc4j.scr.game.session.OutgoingTurnBuffer;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanGameInformation;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderChannel;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderPacket;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderPeer;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Continues a LAN lobby's turn stream through its {@link ProviderSession}. Each receive pumps the network before taking
 * turns for eight player slots and four observer slots. Player turns execute from slot {@code 7} down to slot {@code 0};
 * observer turns are excluded. Session accesses hold the supplied lock shared with {@link LanClient#startNetworkThread()}.
 * <p>
 * Once a second it logs the game's network state at {@code INFO} ({@code LAN GAME}): turns sent and received in that
 * second, the last turn numbers both ways, per peer the expected turn, the queued turns, the time since its last packet, the
 * round trip time of its keep-alive pings, its turns that arrived after their turn step was due ({@link LateTurnCounts}) and the
 * turn resend requests sent for its turns and received from it in that second, and on a network with channels the channel's
 * delay and whether it is relayed; the last Sync command sent, the local player, the packets in and out, the slots a waiting
 * receive misses, and every departure with its reason. A peer that sends nothing for more than {@value #SILENCE_MILLISECONDS} milliseconds is logged
 * ({@code LAN GAME PEER SILENT}).
 * <p>
 * The players a waiting receive misses are the awaited participants of the engine waiting state ({@link #getWaitingPeers()}), whose wait
 * counts from the last receive that took every turn; they are overdue after {@value #WAIT_DELAY_MILLISECONDS} milliseconds. Then the
 * transport advances the waiting dialog's {@link WaitCountdown} (the state's {@link WaitingPeers#getCountdownSeconds()}):
 * {@value #WAIT_COUNTDOWN_SECONDS} seconds (two less than the last countdown when the dialog closed less than two minutes ago), one less each
 * second; at zero the dialog enables its Drop Players button, and only the button's press ({@link #dropWaitingPeers()}) drops the stalled
 * players, or leaves when fewer players respond than stall (as the lobby timeout). The waiting state never drops a participant by itself.
 * <p>
 * A departed participant is an engine departure ({@link #takeDepartures()}), reported at the receive that completes without it; a departed
 * player also gets its Leave Game command ({@code 0x57}, the reason's low byte) in that receive as its own turn, ahead of the turn's commands.
 * <p>
 * The started game pings the peers every {@value #KEEP_ALIVE_INTERVAL_MILLISECONDS} milliseconds instead of the lobby's
 * {@value ProviderSession#DEFAULT_KEEP_ALIVE_INTERVAL} milliseconds: the game's first turn sent sets the session's keep-alive interval, once the
 * game is loaded and initialized.
 */
public final class LanTurnTransport implements LockstepTransport {
    private static final @NotNull Logger LOGGER = Logger.of(LanTurnTransport.class);

    private static final int REPORT_MILLISECONDS = 1000;
    private static final int SILENCE_MILLISECONDS = 2000;
    /**
     * No completed turn for this long opens the waiting dialog.
     */
    private static final int WAIT_DELAY_MILLISECONDS = 2500;
    /**
     * The waiting dialog's countdown.
     */
    private static final int WAIT_COUNTDOWN_SECONDS = 45;
    /**
     * A dialog closed less than this long ago continues the countdown.
     */
    private static final int WAIT_RESUME_MILLISECONDS = 120000;
    /**
     * The seconds a resumed countdown starts below the last one.
     */
    private static final int WAIT_RESUME_REDUCTION_SECONDS = 2;
    /**
     * The time of one countdown step.
     */
    private static final int WAIT_STEP_MILLISECONDS = 1000;
    /**
     * The time a leave keeps the session answering, for the peers to complete the turns before the departure.
     */
    private static final int LEAVE_PUMP_MILLISECONDS = 500;
    /**
     * The pause between two network pumps of a leave.
     */
    private static final int LEAVE_PUMP_INTERVAL_MILLISECONDS = 5;
    /**
     * The session's keep-alive interval in a started game.
     */
    private static final int KEEP_ALIVE_INTERVAL_MILLISECONDS = 2000;
    /**
     * The length of the Keep Alive command that the report keeps as the last Sync command.
     */
    private static final int KEEP_ALIVE_COMMAND_LENGTH = 1;

    private final @NotNull ProviderSession session;
    private final @NotNull Runnable pump;
    private final @NotNull Object lock;
    private final @Nullable LanClient client;

    /**
     * Whether the game sent a turn: the first one sets the session's keep-alive interval.
     */
    private boolean turnSent;

    private int sentInSecond;
    private int receivedInSecond;
    private int waitsInSecond;
    private byte @Nullable [] lastSync;
    private long lastReport;
    private final @NotNull LateTurnCounts lateTurns;

    private volatile long lastExchange;
    private int lastCompleted;
    /**
     * The session clock as a 64-bit time: the unsigned differences of its 32-bit ticks added up since the transport started.
     */
    private long time;
    private int lastTick;

    /**
     * The countdown of the waiting dialog.
     */
    private final @NotNull WaitCountdown countdown;
    /**
     * The participants the last waiting receive missed, counted from the last receive that took every turn.
     */
    private final @NotNull WaitingPeers waitingPeers;
    /**
     * The turn statuses of the last waiting receive, which the drop decides on.
     */
    private final int @NotNull [] waitStatuses;

    private boolean left;
    /**
     * The departures of the session's events, reported at the next receive that takes every turn.
     */
    private final @NotNull List<@NotNull PeerDeparture> pendingDepartures;
    /**
     * The departures reported by the receives since the last {@link #takeDepartures()}.
     */
    private final @NotNull List<@NotNull PeerDeparture> reportedDepartures;
    private final boolean @NotNull [] silent;

    /**
     * Creates a {@link LanTurnTransport} instance.
     *
     * @param session the session the lobby ran on
     * @param pump    reads the socket into the session and runs its update; called before each receive
     */
    public LanTurnTransport(@NotNull ProviderSession session, @NotNull Runnable pump) {
        this(session, pump, new Object(), null);
    }

    /**
     * Creates a {@link LanTurnTransport} instance whose session accesses hold a lock shared with a network thread.
     *
     * @param session the session
     * @param pump    the network pump
     * @param lock    the session lock
     * @param client  the client, for its packet counts in the report, or {@code null}
     */
    public LanTurnTransport(@NotNull ProviderSession session, @NotNull Runnable pump, @NotNull Object lock, @Nullable LanClient client) {
        this.session = session;
        this.pump    = pump;
        this.lock    = lock;
        this.client  = client;

        this.turnSent = false;

        this.sentInSecond     = 0;
        this.receivedInSecond = 0;
        this.waitsInSecond    = 0;
        this.lastSync         = null;
        this.lastReport       = System.currentTimeMillis();
        this.lateTurns        = new LateTurnCounts(LanLobby.PLAYER_IDENTIFIER_COUNT);

        this.lastExchange  = 0;
        this.lastCompleted = session.getTime();
        this.time          = 0L;
        this.lastTick      = this.lastCompleted;

        this.countdown    = new WaitCountdown(WAIT_COUNTDOWN_SECONDS, WAIT_STEP_MILLISECONDS,
                                              WAIT_RESUME_MILLISECONDS, WAIT_RESUME_REDUCTION_SECONDS);
        this.waitingPeers = new WaitingPeers(WAIT_DELAY_MILLISECONDS, WAIT_DELAY_MILLISECONDS + WAIT_COUNTDOWN_SECONDS * WAIT_STEP_MILLISECONDS,
                                             this.countdown);
        this.waitStatuses = new int[LanLobby.RECORDS];

        this.left               = false;
        this.pendingDepartures  = new ArrayList<>();
        this.reportedDepartures = new ArrayList<>();
        this.silent             = new boolean[LanLobby.PLAYER_IDENTIFIER_COUNT];

        // The game's report counts the resend requests from its start, without the lobby's.
        synchronized (this.lock) {
            for (ProviderPeer peer : this.session.getPeers()) {
                peer.takeTurnResendRequestsSent();
                peer.takeTurnResendRequestsReceived();
            }
        }

        LOGGER.info("LAN GAME START: local player " + session.getLocalIdentifier() + ", own turns: next " +
                    this.session.getSelf().getChannel(ProviderPacket.TURN).getNextOutgoingSequence() +
                    ", expected " + this.session.getSelf().getChannel(ProviderPacket.TURN).getExpectedSequence());
    }

    /**
     * @return the local turns sent and not yet received back
     */
    @Override
    public int getTurnsInTransit() {
        synchronized (this.lock) {
            return this.session.getTurnsInTransit();
        }
    }

    /**
     * @return the turn rate of the session and of the network it runs on, {@link TurnRate#UNSPECIFIED} for a transport that names none
     * @implSpec The session's turns per second are the advertised game information's {@code net_turn_rate} ({@code 0} without one); the
     * network's are the provider's caps ({@link ProviderSession#DEFAULT_TURNS_PER_SECOND}, {@link ProviderSession#DEFAULT_TURNS_IN_TRANSIT}).
     */
    @Override
    public @NotNull TurnRate getTurnRate() {
        LanClient owner = this.client;
        LanGameInformation game = owner == null ? null : owner.getGameInformation();

        return new TurnRate(game == null ? 0 : game.getNetworkTurnRate(),
                            ProviderSession.DEFAULT_TURNS_PER_SECOND, ProviderSession.DEFAULT_TURNS_IN_TRANSIT);
    }

    /**
     * @return the waiting state of the all-turns barrier (the awaited and overdue participants, the drop countdown), or {@code null} for a
     * transport that never drops a participant
     * @implSpec The awaited participants are the present players and observers whose turn the last waiting receive missed, each peer the
     * network id; the wait counts from the last receive that took every turn, in milliseconds of the session's clock. The drop countdown
     * is the nominal one ({@value #WAIT_COUNTDOWN_SECONDS} seconds after the overdue time); the dialog's seconds are those of its
     * {@link WaitCountdown}, and only its Drop Players button drops ({@link #dropWaitingPeers()}). Read it on the thread that receives.
     */
    @Override
    public @NotNull WaitingPeers getWaitingPeers() {
        return this.waitingPeers;
    }

    /**
     * @return {@code true} if the local player left the game (a leave, or fewer players responding than stalled), {@code false} otherwise
     */
    public boolean hasLeft() {
        return this.left;
    }

    /**
     * @return when the game last asked for turns ({@link System#currentTimeMillis()}), {@code 0} before the first time
     */
    public long getLastExchange() {
        return this.lastExchange;
    }


    /**
     * Sends the local participant's next turn.
     *
     * @param turn the turn bytes
     */
    @Override
    public void sendTurn(byte @NotNull [] turn) {
        synchronized (this.lock) {
            if (!this.turnSent) {
                this.session.setKeepAliveInterval(KEEP_ALIVE_INTERVAL_MILLISECONDS);

                this.turnSent = true;
            }

            this.session.sendTurn(turn);
        }

        this.sentInSecond++;

        if (turn.length >= OutgoingTurnBuffer.SYNC_COMMAND_LENGTH && (turn[0] == ReplayCommandIds.SYNC || turn[0] == ReplayCommandIds.KEEP_ALIVE)) {
            int commandLength = turn[0] == ReplayCommandIds.SYNC ? OutgoingTurnBuffer.SYNC_COMMAND_LENGTH : KEEP_ALIVE_COMMAND_LENGTH;

            this.lastSync = Arrays.copyOf(turn, commandLength);
        }
    }

    /**
     * Takes the next turn of every player once all of them arrived. A player who left since the last turn comes first with
     * its Leave Game command and reason byte.
     *
     * @return the players' turns in execution order, each peer the sender's network id, or {@code null} while a player's turn
     * is missing
     * @implSpec Runs the network pump first and starts the waiting countdown after {@value #WAIT_DELAY_MILLISECONDS} milliseconds without a turn.
     * A receive that takes every turn reports the departures of the session's events since the last such receive ({@link #takeDepartures()}).
     */
    @Override
    public @Nullable List<@NotNull PeerTurn> receiveTurns() {
        List<PeerTurn> turns = null;

        this.lastExchange = System.currentTimeMillis();

        synchronized (this.lock) {
            this.pump.run();

            int now = this.session.getTime();

            this.processSessionEvents();

            ProviderSession.TurnBatch batch = this.session.receiveTurns(0, LanLobby.RECORDS, PlayerTable.OBSERVER_SLOTS);

            // The departures the receive applied (events dispatched before the barrier) take effect in this turn.
            this.processSessionEvents();

            long elapsed = this.advanceTime(now);

            if (batch.isComplete()) {
                turns = new ArrayList<>();

                for (PeerDeparture departure : this.pendingDepartures) {
                    LockstepPeer peer = departure.getPeer();

                    // The departed player's Leave Game command is recorded as its own.
                    if (peer.getNumber() < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                        byte[] payload = ReplayCommandCodecs.encodeValue(ReplayCommandIds.LEAVE_GAME, departure.getReason());

                        turns.add(new PeerTurn(peer, ReplayCommandCodecs.prefixType(ReplayCommandIds.LEAVE_GAME, payload)));
                    }
                }

                this.reportedDepartures.addAll(this.pendingDepartures);
                this.pendingDepartures.clear();

                for (int slot = PlayerTable.PLAYABLE_PLAYER_SLOTS - 1; slot >= 0; slot--) {
                    byte[] turn = batch.getTurn(slot);

                    if (turn != null) {
                        turns.add(new PeerTurn(NetworkPeerUtils.getPeer(slot), turn));
                    }
                }

                this.receivedInSecond++;
                this.lastCompleted = now;
                this.waitingPeers.clear();

                int count = this.countdown.getCount();

                if (this.countdown.close(elapsed)) {
                    LOGGER.info("LAN GAME WAIT ENDED: countdown " + count + " s");
                }
            } else {
                List<LockstepPeer> awaited = new ArrayList<>();
                long waited = Integer.toUnsignedLong(now - this.lastCompleted);
                int turn = this.session.getSelf().getChannel(ProviderPacket.TURN).getExpectedSequence();

                this.waitsInSecond++;

                for (int slot = 0; slot < LanLobby.RECORDS; slot++) {
                    int status = batch.getStatus(slot);

                    if ((status & ProviderSession.TURN_PRESENT) != 0 && (status & ProviderSession.TURN_READY) == 0) {
                        int identifier = LanLobby.getSlotIdentifier(slot);

                        awaited.add(NetworkPeerUtils.getPeer(identifier));
                        this.lateTurns.recordMissing(identifier, turn);
                    }
                }

                // The wait counts from the last receive that took every turn.
                this.waitingPeers.update(awaited, 0L, waited);

                if (waited >= this.waitingPeers.getOverdueMillis()) {
                    this.waitForPlayers(batch, now, elapsed);
                }
            }

            this.logSilence(now);
            this.report();
        }

        return turns;
    }

    /**
     * Takes the departures of the participants that the receives since the previous call no longer waited for: a departure is reported at
     * the first receive without a turn of the participant, on every machine the same turn step. A transport without departures reports none.
     *
     * @return the departures in the order of the receives, the participants' execution order within one receive; empty if there is none
     * @implSpec A departure is a player or observer that the session reported as left, {@link DepartureKind#DROPPED} with the drop reason
     * {@link LanLobby#DROP_REASON} and {@link DepartureKind#LEFT} otherwise, with the session's departure reason. Within one receive the
     * departures keep the order of the session's events.
     */
    @Override
    public @NotNull List<@NotNull PeerDeparture> takeDepartures() {
        synchronized (this.lock) {
            List<PeerDeparture> departures = List.copyOf(this.reportedDepartures);

            this.reportedDepartures.clear();

            return departures;
        }
    }

    /**
     * Leaves the session: the other participants take the local participant's departure once they received every turn sent
     * before it.
     *
     * @param reason the departure reason, the session's own code
     * @implSpec Leaves through {@link ProviderSession#leave} and pumps pending work for {@value #LEAVE_PUMP_MILLISECONDS} milliseconds.
     * A player that already left only ends the client's game state. An interrupted pump restores the interrupt flag.
     */
    @Override
    public void leave(int reason) {
        boolean leaving;

        synchronized (this.lock) {
            leaving = !this.left && this.session.getLocalIdentifier() != ProviderPeer.UNKNOWN_IDENTIFIER;

            if (leaving) {
                LOGGER.info(String.format("LAN GAME LEAVE: reason 0x%X at own turn %d",
                                          reason,
                                          this.session.getSelf().getChannel(ProviderPacket.TURN).getNextOutgoingSequence()));
                this.session.leave(reason);

                this.left = true;
            }
        }

        long end = System.currentTimeMillis() + LEAVE_PUMP_MILLISECONDS;

        while (leaving && System.currentTimeMillis() < end) {
            synchronized (this.lock) {
                this.pump.run();
            }

            try {
                Thread.sleep(LEAVE_PUMP_INTERVAL_MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();

                break;
            }
        }

        LanClient owner = this.client;

        if (owner != null) {
            owner.endGame(this);
        }
    }

    /**
     * Drops the overdue participants on a person's request, the waiting dialog's drop button. A transport without waiting state ignores it.
     *
     * @implSpec Drops the stalled players or leaves through {@link #dropStalledPlayers} at once, only while the countdown is at zero.
     */
    @Override
    public void dropWaitingPeers() {
        synchronized (this.lock) {
            if (!this.countdown.isExpired() || this.left) {
                return;
            }

            this.dropStalledPlayers(this.waitStatuses);
        }
    }


    /**
     * Logs the events the session queued while the game did not ask for turns (the network thread's call).
     */
    void logPendingEvents() {
        synchronized (this.lock) {
            this.processSessionEvents();
        }
    }

    /**
     * Runs one pass of the waiting dialog's loop: advances its countdown, which opens the dialog and counts down once a second; at zero the
     * dialog enables its Drop Players button, nothing more.
     */
    private void waitForPlayers(ProviderSession.@NotNull TurnBatch batch, int now, long elapsed) {
        if (this.countdown.advance(elapsed)) {
            LOGGER.info(String.format("LAN GAME WAITING FOR PLAYERS: no turn for %d ms, missing 0x%X, countdown %d s",
                                      Integer.toUnsignedLong(now - this.lastCompleted),
                                      this.getWaitingSlots(),
                                      this.countdown.getCount()));
        }

        for (int slot = 0; slot < this.waitStatuses.length; slot++) {
            this.waitStatuses[slot] = batch.getStatus(slot);
        }
    }

    /**
     * Runs the waiting dialog's drop (the same decision as the lobby timeout): over the eight player slots, the present
     * players that do not respond are stalled; when fewer respond than stall the local player leaves, otherwise every
     * stalled player (and observer) is dropped with reason {@link LanLobby#DROP_REASON}.
     */
    private void dropStalledPlayers(int @NotNull [] statuses) {
        int present = 0;
        int stalled = 0;

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            int status = statuses[slot];

            if ((status & ProviderSession.TURN_PRESENT) != 0) {
                present++;

                if ((status & ProviderSession.TURN_RESPONSIVE) == 0) {
                    stalled++;
                }
            }
        }

        if (present - stalled < stalled) {
            LOGGER.info(String.format("LAN GAME LEAVE: %d of %d players stalled", stalled, present));
            this.session.leave(LanLobby.DROP_REASON);

            this.left = true;

            return;
        }

        for (int slot = 0; slot < LanLobby.RECORDS; slot++) {
            int status = statuses[slot];

            if ((status & ProviderSession.TURN_PRESENT) != 0 && (status & ProviderSession.TURN_RESPONSIVE) == 0) {
                int player = LanLobby.getSlotIdentifier(slot);
                boolean dropped = this.session.dropPlayer(player, LanLobby.DROP_REASON);

                LOGGER.info(String.format("LAN GAME DROP PLAYER: player %d, %s", player, dropped ? "dropped" : "not dropped"));
            }
        }
    }

    /**
     * Advances the 64-bit session time to the session clock's tick.
     *
     * @param now the session clock's tick
     * @return the session time
     */
    private long advanceTime(int now) {
        this.time += Integer.toUnsignedLong(now - this.lastTick);
        this.lastTick = now;

        return this.time;
    }

    /**
     * @return the lobby records whose player or observer the waiting state awaits, one bit per record, for the logs
     */
    private int getWaitingSlots() {
        List<LockstepPeer> awaited = this.waitingPeers.getAwaitedPeers();
        int waiting = 0;

        for (int slot = 0; slot < LanLobby.RECORDS; slot++) {
            if (awaited.contains(NetworkPeerUtils.getPeer(LanLobby.getSlotIdentifier(slot)))) {
                waiting |= 1 << slot;
            }
        }

        return waiting;
    }

    /**
     * Logs a peer that sent nothing for more than {@value #SILENCE_MILLISECONDS} milliseconds, and its return.
     */
    private void logSilence(int tick) {
        for (ProviderPeer peer : this.session.getPeers()) {
            int identifier = peer.getIdentifier();

            if (identifier == ProviderPeer.UNKNOWN_IDENTIFIER) {
                continue;
            }

            int silence = tick - peer.getLastReceiveTime();

            if (silence > SILENCE_MILLISECONDS && !this.silent[identifier]) {
                this.silent[identifier] = true;

                LOGGER.info(String.format("LAN GAME PEER SILENT: player %d sent nothing for %d ms (flags 0x%X, expected turn %d, local next %d)",
                                          identifier,
                                          silence,
                                          peer.getFlags(),
                                          peer.getChannel(ProviderPacket.TURN).getExpectedSequence(),
                                          this.session.getSelf().getChannel(ProviderPacket.TURN).getNextOutgoingSequence()));
            } else if (silence <= SILENCE_MILLISECONDS && this.silent[identifier]) {
                this.silent[identifier] = false;

                LOGGER.info("LAN GAME PEER RESUMED: player " + identifier);
            }
        }
    }

    private void processSessionEvents() {
        for (ProviderSession.Event event = this.session.pollEvent(); event != null; event = this.session.pollEvent()) {
            if (event.getType() == ProviderSession.EVENT_PLAYER_LEFT) {
                byte[] data = event.getData();
                int reason = data.length >= Integer.BYTES ? LittleEndianUtils.readInt32(data, 0) : 0;

                LOGGER.info(String.format("LAN GAME PLAYER LEFT: player %d, reason 0x%08X", event.getPlayerIdentifier(), reason));

                DepartureKind kind = reason == LanLobby.DROP_REASON ? DepartureKind.DROPPED : DepartureKind.LEFT;

                this.pendingDepartures.add(new PeerDeparture(NetworkPeerUtils.getPeer(event.getPlayerIdentifier()), kind, reason));
            } else {
                LOGGER.info("LAN GAME EVENT: type " + event.getType() + ", player " + event.getPlayerIdentifier());
            }
        }
    }

    /**
     * Describes a channel for the report: its delay and whether it is relayed.
     *
     * @param channel the channel, or {@code null} for a network without channels
     * @return the description with a leading space, for example {@code " link 48 ms relayed"}, or an empty string without a channel
     */
    private static @NotNull String describeChannel(@Nullable ParticipantChannel channel) {
        if (channel == null) {
            return "";
        }

        long delay = channel.getDelayMillis();

        return " link " + (delay == ParticipantChannel.UNKNOWN_DELAY ? "-" : delay + " ms") + (channel.isRelayed() ? " relayed" : " direct");
    }

    private void report() {
        long now = System.currentTimeMillis();

        if (now - this.lastReport < REPORT_MILLISECONDS) {
            return;
        }

        this.lastReport = now;

        StringBuilder peers = new StringBuilder();
        int tick = LanClient.getCurrentTick();
        LanClient owner = this.client;

        for (ProviderPeer peer : this.session.getPeers()) {
            ProviderChannel turns = peer.getChannel(ProviderPacket.TURN);
            InetSocketAddress address = peer.getAddress();
            ParticipantChannel channel = owner == null || address == null ? null : owner.getNetwork().findChannel(address);

            peers.append(String.format(" [player %d flags 0x%X expected %d queued %d silent %d ms rtt %s late %d/s resends sent %d/s received %d/s%s]",
                                       peer.getIdentifier(),
                                       peer.getFlags(),
                                       turns.getExpectedSequence(),
                                       turns.getReceivedCount(),
                                       tick - peer.getLastReceiveTime(),
                                       peer.hasRoundTripTime() ? peer.getRoundTripTime() + " ms" : "-",
                                       this.lateTurns.take(peer.getIdentifier()),
                                       peer.takeTurnResendRequestsSent(), peer.takeTurnResendRequestsReceived(),
                                       describeChannel(channel)));
        }

        LOGGER.info(
                String.format(
                        "LAN GAME: local %d, sent %d/s (next %d), received %d/s (expected %d), waits %d/s missing 0x%X, sync %s, packets in %d out %d%s",
                        this.session.getLocalIdentifier(),
                        this.sentInSecond, this.session.getSelf().getChannel(ProviderPacket.TURN).getNextOutgoingSequence(),
                        this.receivedInSecond, this.session.getSelf().getChannel(ProviderPacket.TURN).getExpectedSequence(),
                        this.waitsInSecond, this.getWaitingSlots(),
                        this.lastSync == null ? "-" : HexFormat.of().formatHex(this.lastSync),
                        owner == null ? -1 : owner.getPacketsIn(), owner == null ? -1 : owner.getPacketsOut(),
                        peers
                )
        );

        this.sentInSecond     = 0;
        this.receivedInSecond = 0;
        this.waitsInSecond    = 0;
    }
}
