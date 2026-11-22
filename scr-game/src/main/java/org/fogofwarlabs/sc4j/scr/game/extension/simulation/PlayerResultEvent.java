package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.fogofwarlabs.sc4j.scr.game.player.Player;

/**
 * A player received a result: the triggers ended its game, or it left.
 */
public final class PlayerResultEvent extends SimulationEvent {
    private final int playerIndex;
    private final int victoryState;

    /**
     * Creates a {@link PlayerResultEvent} instance.
     *
     * @param playerIndex  the player slot
     * @param victoryState the victory state BYTE ({@link Player#getVictoryState()})
     */
    public PlayerResultEvent(int playerIndex, int victoryState) {
        this.playerIndex  = playerIndex;
        this.victoryState = victoryState;
    }

    /**
     * @return the player slot
     */
    public int getPlayerIndex() {
        return this.playerIndex;
    }

    /**
     * @return the victory state BYTE ({@link Player#getVictoryState()})
     */
    public int getVictoryState() {
        return this.victoryState;
    }
}
