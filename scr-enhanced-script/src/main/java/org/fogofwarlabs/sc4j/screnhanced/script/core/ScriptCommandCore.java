package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.LocalSelection;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.screnhanced.script.sync.ScriptCommandMessage;
import org.fogofwarlabs.sc4j.screnhanced.script.sync.ScriptCommandOutbox;
import org.fogofwarlabs.sc4j.screnhanced.script.sync.ScriptCommands;
import org.fogofwarlabs.sc4j.screnhanced.script.sync.ScriptValueUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Defines {@code core.command}: the script commands, mod commands that carry one value from a peer to the scripts of every peer.
 * <p>
 * {@code submit(value)} encodes the value ({@link ScriptValueUtils}) and queues its commands for the local player's outgoing turn
 * ({@link ScriptCommandOutbox}): when the turn is full they wait for the next one, and in a replay or for an observer they are dropped. It
 * changes no game state and works anywhere; shared code, which runs on every peer, submits once per human peer. The mod commands of the
 * players join into values ({@link ScriptCommands}); each frame start ({@link #startFrame()}) makes the values that completed since the
 * previous one the frame's commands, in the order they ran. {@code getExecuted()} returns them as new records
 * {@code {player, value, selection, selectionGenerations}}: the sending player's slot id, a new copy of the value, and the ids and generations
 * of the live units the player had selected when the command that completed the value ran, on every call; it works in shared code only.
 */
final class ScriptCommandCore {
    /**
     * A value of the script commands with the selection of its player when the command that completed it ran.
     */
    private static final class ExecutedCommand {
        private final @NotNull ScriptCommandMessage message;
        private final int @NotNull [] units;
        private final int @NotNull [] generations;

        private ExecutedCommand(@NotNull ScriptCommandMessage message, int @NotNull [] units, int @NotNull [] generations) {
            this.message     = message;
            this.units       = units;
            this.generations = generations;
        }
    }

    private final @NotNull ScriptGame scriptGame;

    private final @NotNull ScriptCommandOutbox outbox;
    private final @NotNull ScriptCommands commands;
    private @NotNull List<ExecutedCommand> waiting;
    private @NotNull List<ExecutedCommand> frameCommands;

    /**
     * Creates a {@link ScriptCommandCore} instance without commands.
     *
     * @param scriptGame the game binding
     */
    ScriptCommandCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame = scriptGame;

        this.outbox        = new ScriptCommandOutbox();
        this.commands      = new ScriptCommands();
        this.waiting       = new ArrayList<>();
        this.frameCommands = new ArrayList<>();
    }

    /**
     * @return the commands that wait for the local player's outgoing turn, the same instance for the whole game
     */
    @NotNull ScriptCommandOutbox getOutbox() {
        return this.outbox;
    }

    /**
     * Reads a mod command of a player ({@link ScriptCommands#read}).
     *
     * @param player the slot id of the issuing player
     * @param data   the command data
     * @return the value the command completed, or {@code null} if it completed none
     */
    @Nullable ScriptCommandMessage read(int player, byte @NotNull [] data) {
        return this.commands.read(player, data);
    }

    /**
     * Adds a completed value to the values that wait for the next frame start, with the current selection of its player.
     *
     * @param message the value
     */
    void queue(@NotNull ScriptCommandMessage message) {
        Game game = this.scriptGame.getGame();
        UnitSelection selection = game.getSelection();
        UnitPool pool = game.getUnitSystem().getUnitPool();
        int player = message.getPlayer();
        int[] units = new int[UnitSelection.MAXIMUM_SELECTED];
        int count = 0;

        if (selection != null && pool != null && player < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
                int id = selection.getSelectedPoolIndexRaw(player, position);

                if (id == LocalSelection.EMPTY_SLOT) {
                    break;
                }

                if (ScriptUnitCore.isLive(pool, id)) {
                    units[count++] = id;
                }
            }
        }

        int[] generations = new int[count];

        for (int index = 0; index < count; index++) {
            generations[index] = pool.getGeneration(units[index]);
        }

        this.waiting.add(new ExecutedCommand(message, Arrays.copyOf(units, count), generations));
    }

    /**
     * Makes the waiting values the frame's commands.
     */
    void startFrame() {
        List<ExecutedCommand> messages = this.frameCommands;

        messages.clear();

        this.frameCommands = this.waiting;
        this.waiting       = messages;
    }

    /**
     * Forgets every command, also those that wait in the outbox.
     */
    void clear() {
        this.waiting.clear();
        this.frameCommands.clear();
        this.outbox.clear();
    }

    /**
     * Defines the functions of {@code core.command}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "submit", 1, (context, scope, thisObject, arguments) -> {
            byte[] value = ScriptValueUtils.encode(arguments.length > 0 ? arguments[0] : Context.getUndefinedValue(), global);

            this.outbox.add(ScriptCommands.split(value));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getExecuted", 0, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkShared();

            Object[] records = new Object[this.frameCommands.size()];

            for (int index = 0; index < records.length; index++) {
                ExecutedCommand command = this.frameCommands.get(index);
                Scriptable record = context.newObject(global);

                ScriptableObject.putProperty(record, "player", (double) command.message.getPlayer());
                ScriptableObject.putProperty(record, "value", ScriptValueUtils.decode(context, global, command.message.getValue()));
                ScriptableObject.putProperty(record, "selection", createNumbers(context, global, command.units));
                ScriptableObject.putProperty(record, "selectionGenerations", createNumbers(context, global, command.generations));

                records[index] = record;
            }

            return context.newArray(global, records);
        });
    }

    private static @NotNull Scriptable createNumbers(@NotNull Context context, @NotNull Scriptable global, int @NotNull [] values) {
        Object[] numbers = new Object[values.length];

        for (int index = 0; index < values.length; index++) {
            numbers[index] = (double) values[index];
        }

        return context.newArray(global, numbers);
    }
}
