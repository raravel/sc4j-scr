package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.SimulationDecision;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.SimulationEvent;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceLink;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ConsoleSettings;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ConsoleSettingsSource;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageReplacementSource;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageReplacements;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptLogOutput;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.util.function.BiConsumer;

/**
 * Runs the script package of one game's scenario in the game's simulation.
 * <p>
 * The plugin creates the {@link ScriptRuntime} with the game API, the map's resources and a seed from the game's setup seed, the same on every
 * peer and in every replay, and starts it at the first frame start.
 * <p>
 * The events of the game go into the runtime's queue with their frame, the events from before the first frame (the units the map and the
 * game start create) as well, and the runtime delivers them at the next frame start, before the triggers. A frame start whose frame number
 * did not advance runs no script while the Pause command of the game menu holds the game, when the triggers do not run either, and runs it
 * with the same frame number while Pause Game holds the game, when the triggers run. Decisions go to the runtime at once. The mod commands of
 * the players go to the runtime, which joins the script commands into values and delivers them at the next frame start. After each frame start
 * the script commands the script submitted go to the local player's outgoing turn ({@link ScriptRuntime#submitCommands}); the game's
 * {@link ScriptInputPlugin} sends those of the local code at once.
 * <p>
 * The classic image graphics of the game draw the image graphics the script replaces ({@link #getImageReplacements()}); every frame start first
 * forgets the replacements of units whose slot was freed. The classic console of the game merges the console settings of the script
 * ({@link #getConsoleSettings()}). A game the plugin runs cannot be saved: a saved game would lose the script's state.
 */
public final class ScriptSimulationPlugin implements ScrSimulationPlugin, ImageReplacementSource, ConsoleSettingsSource {
    private final @NotNull Game game;
    private final @NotNull ScriptRuntime runtime;

    private boolean started;
    private int lastFrame;

    /**
     * Creates a {@link ScriptSimulationPlugin} instance whose script has not started.
     *
     * @param game          the game
     * @param scriptPackage the script package of the game's scenario
     * @param resources     the resources of the game's map
     */
    public ScriptSimulationPlugin(@NotNull Game game, @NotNull ScriptPackage scriptPackage, @NotNull ScriptResources resources) {
        this.game    = game;
        this.runtime = ScriptRuntime.create(scriptPackage, game, resources, Integer.toUnsignedLong(game.getSetup().getSeed()));

        this.started   = false;
        this.lastFrame = 0;
    }

    /**
     * Creates a {@link ScriptSimulationPlugin} instance whose script has not started and whose log messages and script errors go to
     * {@code logOutput} instead of the mod's log (eud-runner: not in SC:R Enhanced).
     *
     * @param game          the game
     * @param scriptPackage the script package of the game's scenario
     * @param resources     the resources of the game's map
     * @param logOutput     where the script's log messages and errors go
     * @param globals       adds the host's globals to the script's global scope, or {@code null}
     */
    public ScriptSimulationPlugin(@NotNull Game game, @NotNull ScriptPackage scriptPackage, @NotNull ScriptResources resources,
                                  @NotNull ScriptLogOutput logOutput, @Nullable BiConsumer<Context, Scriptable> globals) {
        this.game    = game;
        this.runtime = ScriptRuntime.create(scriptPackage, game, resources, Integer.toUnsignedLong(game.getSetup().getSeed()),
                                            ScriptRuntime.INSTRUCTION_BUDGET, logOutput, globals);

        this.started   = false;
        this.lastFrame = 0;
    }

    /**
     * @return the script runtime of the game's script package
     */
    public @NotNull ScriptRuntime getRuntime() {
        return this.runtime;
    }

    /**
     * @return the image graphics the script replaces, the same instance for the whole game
     */
    @Override
    public @NotNull ImageReplacements getImageReplacements() {
        return this.runtime.getImageReplacements();
    }

    /**
     * @return the console settings of the plugin, the same instance for the whole game
     */
    @Override
    public @NotNull ConsoleSettings getConsoleSettings() {
        return this.runtime.getConsoleSettings();
    }


    /**
     * Called once per frame when the frame's trigger step runs, before the triggers, with the random generator enabled as for the triggers; the
     * trigger step also runs while the game is paused.
     * <p>
     * Every call forgets the image replacements of units whose slot was freed; the first call starts the script; the first call, every call
     * whose frame number advanced and every call while Pause Game holds the game, when the triggers run without a frame, run the script's frame;
     * every call then hands the waiting script commands to the game's mod command submitter, which drops them in a replay or for an observer,
     * and plays the sounds the script's local code played since the previous call.
     */
    @Override
    public void onFrameStarted() {
        int frame = this.game.getCurrentFrame();
        boolean first = !this.started;
        TriggerContext triggers = this.game.getTriggerContext();
        boolean triggerPaused = triggers != null && triggers.getPhaseGate() != 0 && triggers.getTriggersEnabledWhileGated() != 0;

        this.runtime.getImageReplacements().removeFreedUnits();

        if (first) {
            this.started = true;

            this.runtime.start();
        }

        if (first || frame != this.lastFrame || triggerPaused) {
            this.lastFrame = frame;

            this.runtime.runFrame(frame);
        }

        this.runtime.submitCommands(this.game.getInterfaceLink().getModCommandSubmitter());
        this.runtime.playLocalSounds();
    }

    /**
     * Called when something happens in the simulation, from inside the routine where it happens.
     * <p>
     * The event goes into the runtime's queue with the current frame.
     *
     * @param event the event; its kind is its class
     */
    @Override
    public void onEvent(@NotNull SimulationEvent event) {
        this.runtime.queueEvent(event, this.game.getCurrentFrame());
    }

    /**
     * Called at a decision point of a simulation routine, which continues with the values the plugins leave in the decision; the plugins decide
     * in extension order, each on the values the previous one left.
     * <p>
     * The script's decision hooks decide with the game read-only.
     *
     * @param decision the decision; its kind is its class
     */
    @Override
    public void decide(@NotNull SimulationDecision decision) {
        this.runtime.decide(decision);
    }

    /**
     * Called when a mod command of a player runs, at its place in the command stream of the frame. The player is the record the command's issuer
     * resolved to, the issuing player's own record also in a team game, where the other commands of every member act for the team's leader. A
     * live game queues the mod commands of the local player through the game's mod command submitter
     * ({@link InterfaceLink#getModCommandSubmitter()}).
     * <p>
     * The data goes to the runtime with the slot of the player ({@link ScriptRuntime#queueModCommand}); the data of a player without a slot is
     * ignored.
     *
     * @param player the issuing player's record
     * @param data   the command data, which every plugin of the game receives; the plugin does not change it
     */
    @Override
    public void onModCommand(@NotNull Player player, byte @NotNull [] data) {
        int slot = this.findSlot(player);

        if (slot >= 0) {
            this.runtime.queueModCommand(slot, data);
        }
    }

    /**
     * Tells whether the game can be saved while this plugin runs it: never, since a saved game would lose the script's state.
     *
     * @return {@code false}
     */
    @Override
    public boolean allowsSave() {
        return false;
    }

    /**
     * Finds the slot of a player record in the game's player table.
     *
     * @return the slot id, or {@code -1} if no slot holds the record
     */
    private int findSlot(@NotNull Player player) {
        PlayerTable table = this.game.getPlayerTable();

        for (int slot = 0; slot < PlayerTable.PLAYER_SLOTS; slot++) {
            if (table.getPlayer(slot) == player) {
                return slot;
            }
        }

        return -1;
    }
}
