package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceLink;

/**
 * Requests the pause and the resume of the game menu for the local player of a live game: the Pause and Resume commands, which go into the
 * outgoing turn and run in the turn on every peer. A Pause command pauses only a running game whose sender may pause: always in single player, in
 * a network game a human player with pauses left, which the pause then takes one of. A live game supplies its requester when its session starts
 * ({@link InterfaceLink#getPauseRequester()}); a replay has none. The methods run on the thread that runs and draws the game.
 */
public interface ScrPauseRequester {
    /**
     * Queues a Pause command while the game is running.
     */
    void requestPause();

    /**
     * Queues a Resume command while the game is paused.
     */
    void requestResume();
}
