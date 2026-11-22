package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.fogofwarlabs.sc4j.engine.lockstep.LockstepTransport;
import org.fogofwarlabs.sc4j.engine.lockstep.PeerTurn;
import org.fogofwarlabs.sc4j.engine.lockstep.TurnRate;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.NetworkPeerUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.List;

/**
 * The turn stream of a network game the local player is alone in: every turn sent comes back as that player's turn, in order. With it a
 * {@link LiveGame} is a network game ({@link LiveGame#attachNetwork}) as a LAN game with one human is, without a socket. This follows the
 * scr-game test fixture {@code LiveSessionScenario.Loopback}, outside the test sources.
 */
final class LoopbackTransport implements LockstepTransport {
    /**
     * The turns per second and provider turns in transit of the LAN provider ({@code ProviderSession}).
     */
    private static final int TURNS_PER_SECOND = 24;
    private static final int PROVIDER_TURNS_IN_TRANSIT = 2;

    private final @NotNull ArrayDeque<byte[]> sent;

    /**
     * Creates a {@link LoopbackTransport} instance without turns in transit.
     */
    LoopbackTransport() {
        this.sent = new ArrayDeque<>();
    }

    /**
     * @return the local turns sent and not yet received back
     */
    @Override
    public int getTurnsInTransit() {
        return this.sent.size();
    }

    /**
     * @return the turn rate of a LAN game's transport: no fixed network turn rate, {@value #TURNS_PER_SECOND} turns per second and
     * {@value #PROVIDER_TURNS_IN_TRANSIT} provider turns in transit ({@code LanTurnTransport}), stated here rather than left to the default
     */
    @Override
    public @NotNull TurnRate getTurnRate() {
        return new TurnRate(0, TURNS_PER_SECOND, PROVIDER_TURNS_IN_TRANSIT);
    }

    /**
     * Sends the local player's next turn.
     *
     * @param turn the turn bytes
     */
    @Override
    public void sendTurn(byte @NotNull [] turn) {
        this.sent.addLast(turn);
    }

    /**
     * Takes the oldest local turn not yet received.
     *
     * @return the local player's turn, or {@code null} while none is in transit
     */
    @Override
    public @Nullable List<@NotNull PeerTurn> receiveTurns() {
        byte[] turn = this.sent.pollFirst();

        return turn == null ? null : List.of(new PeerTurn(NetworkPeerUtils.getPeer(LiveGame.HUMAN_PLAYER_ID), turn));
    }
}
