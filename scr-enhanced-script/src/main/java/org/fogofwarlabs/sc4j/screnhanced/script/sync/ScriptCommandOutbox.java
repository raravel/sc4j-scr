package org.fogofwarlabs.sc4j.screnhanced.script.sync;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrModCommandSubmitter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Keeps the script commands the local scripts submitted until the local player's mod command submitter takes them, in submitting order.
 */
public final class ScriptCommandOutbox {
    private final @NotNull ArrayDeque<byte[]> commands;

    /**
     * Creates an empty {@link ScriptCommandOutbox} instance.
     */
    public ScriptCommandOutbox() {
        this.commands = new ArrayDeque<>();
    }

    /**
     * Adds the commands of a value after the waiting ones.
     *
     * @param commands the data of the commands ({@link ScriptCommands#split})
     */
    public void add(@NotNull List<byte @NotNull []> commands) {
        this.commands.addAll(commands);
    }

    /**
     * Submits the waiting commands in order until the outgoing turn has no room; the rest waits for the next call. Without a submitter, as in a
     * replay or for a local observer, the waiting commands are dropped.
     *
     * @param submitter the local player's mod command submitter, or {@code null} if the game has none
     */
    public void submit(@Nullable ScrModCommandSubmitter submitter) {
        if (submitter == null) {
            this.commands.clear();

            return;
        }

        while (!this.commands.isEmpty() && submitter.submitModCommand(this.commands.peekFirst())) {
            this.commands.removeFirst();
        }
    }

    /**
     * Drops every waiting command.
     */
    public void clear() {
        this.commands.clear();
    }
}
