package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayModCommand;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceLink;
import org.jetbrains.annotations.NotNull;

/**
 * Queues mod commands ({@link ReplayCommandIds#MOD_COMMAND}) of the local player of a live game into the outgoing turn. A queued command runs
 * at the same frame on every peer and in every replay of the game, where the game's simulation plugins receive its data with the local player's
 * record ({@link ScrSimulationPlugin#onModCommand}). A live game supplies its submitter when its session starts
 * ({@link InterfaceLink#getModCommandSubmitter()}) unless the local player observes; a replay has none. The methods run on the thread that runs
 * and draws the game.
 */
public interface ScrModCommandSubmitter {
    /**
     * Queues a mod command of the local player into the outgoing turn. The turn holds a limited number of bytes, so a command is refused while
     * the commands queued for the turn leave no room for it; it can be submitted again for a later turn.
     *
     * @param data the command data, at most {@link ReplayModCommand#MAXIMUM_DATA_LENGTH} bytes; the submitter keeps a copy
     * @return {@code true} if queued, {@code false} if the outgoing turn has no room for it
     * @throws IllegalArgumentException if the data is longer than {@link ReplayModCommand#MAXIMUM_DATA_LENGTH} bytes
     */
    boolean submitModCommand(byte @NotNull [] data);
}
