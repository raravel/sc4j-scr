package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

/**
 * A player left the game or was dropped.
 */
public final class PlayerLeftEvent extends SimulationEvent {
    private final int playerIndex;
    private final int reason;

    /**
     * Creates a {@link PlayerLeftEvent} instance.
     *
     * @param playerIndex the player slot
     * @param reason      the signed departure reason of the Leave Game command
     */
    public PlayerLeftEvent(int playerIndex, int reason) {
        this.playerIndex = playerIndex;
        this.reason      = reason;
    }

    /**
     * @return the player slot
     */
    public int getPlayerIndex() {
        return this.playerIndex;
    }

    /**
     * @return the signed departure reason of the Leave Game command
     */
    public int getReason() {
        return this.reason;
    }
}
