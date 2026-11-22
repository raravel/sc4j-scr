package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.core.GameSettings;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayHeaderBuilder;
import org.fogofwarlabs.sc4j.scr.game.frame.GameFrame;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.state.ContextExpansion;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

import java.util.function.ToIntFunction;

/**
 * Defines the functions of {@code core.game} over the game speed, the game settings and the cheat flags.
 * <p>
 * The speed: {@code getSpeed()} (the speed index from {@code 0} slowest to {@value GameSpeed#FASTEST} fastest), {@code getSpeedDuration(speed)}
 * and {@code setSpeedDuration(speed, milliseconds)} (the frame duration table that EUD writes change; the frame duration reads it at once, a
 * speed override of the game name wins over it, and the turn timeouts of a network game keep the durations of their last fill).
 * <p>
 * The settings the game started with, read-only: {@code isMultiplayer()} (the multiplayer session byte is not {@code 0}), {@code getGameType()}
 * and {@code getGameSubtype()} (the WORDs of the game header), {@code getVictoryCondition()}, {@code getResourceType()},
 * {@code getStartingMinerals()}, {@code getStartingUnits()} and {@code getTournamentMode()} (the game options), {@code areCheatsEnabled()}
 * (the game option that permits the cheat command). {@code getExpansion()} and {@code setExpansion(value)} read and store the expansion byte of
 * the game context ({@code 0} StarCraft), which the upgrade, technology, requirement, speed and computer routines test.
 * <p>
 * The cheat flags ({@link org.fogofwarlabs.sc4j.scr.game.state.GameCheats}): {@code getCheatFlags()}; {@code setCheatFlags(flags)} stores the
 * flags as an EUD write does, without the reveal update or the one-shot grants; {@code applyCheats(flags)} runs the cheat command routine, which
 * does nothing when the game options forbid cheats or, in a network game, when more than one human plays.
 */
final class ScriptGameSettingsCore {
    /**
     * The largest value of a BYTE.
     */
    private static final int BYTE_MAXIMUM = 0xFF;

    private final @NotNull ScriptGame scriptGame;

    /**
     * Creates a {@link ScriptGameSettingsCore} instance.
     *
     * @param scriptGame the game binding
     */
    ScriptGameSettingsCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame = scriptGame;
    }

    /**
     * Defines the speed, settings and cheat functions of {@code core.game}.
     *
     * @param namespace the {@code core.game} namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        this.defineSpeedFunctions(namespace, global);
        this.defineSettingFunctions(namespace, global);
        this.defineCheatFunctions(namespace, global);
    }

    private void defineSpeedFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getSpeed", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.requireSpeed().getSpeedIndex());
        ScriptCoreUtils.defineFunction(global, namespace, "getSpeedDuration", 1, (context, scope, thisObject, arguments) -> {
            GameSpeed speed = this.requireSpeed();
            int index = ScriptCoreUtils.requireId("speed", ScriptCoreUtils.readInteger(arguments, 0), speed.getTableSize());

            return (double) Integer.toUnsignedLong(speed.getTableDuration(index));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setSpeedDuration", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            GameSpeed speed = this.requireSpeed();
            int index = ScriptCoreUtils.requireId("speed", ScriptCoreUtils.readInteger(arguments, 0), speed.getTableSize());
            long duration = ScriptCoreUtils.readLong(arguments, 1);

            if (duration < 0) {
                throw ScriptRuntime.rangeError(Exceptions.format("The frame duration {0} is negative.", duration));
            }

            speed.setTableDuration(index, (int) duration);

            return Context.getUndefinedValue();
        });
    }

    private void defineSettingFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "isMultiplayer", 0,
                                       (context, scope, thisObject, arguments) -> this.scriptGame.requireTriggerContext().getMultiplayerSession() != 0);
        ScriptCoreUtils.defineFunction(global, namespace, "getGameType", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.readHeaderWord(ReplayHeaderBuilder.GAME_TYPE_OFFSET));
        ScriptCoreUtils.defineFunction(global, namespace, "getGameSubtype", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.readHeaderWord(ReplayHeaderBuilder.GAME_SUBTYPE_OFFSET));
        this.defineSettingRead(namespace, global, "getVictoryCondition", GameSettings::getVictoryCondition);
        this.defineSettingRead(namespace, global, "getResourceType", GameSettings::getResourceType);
        ScriptCoreUtils.defineFunction(global, namespace, "getStartingMinerals", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.requireSettings().getStartingMinerals());
        this.defineSettingRead(namespace, global, "getStartingUnits", GameSettings::getCreateInitialUnits);
        this.defineSettingRead(namespace, global, "getTournamentMode", GameSettings::getTournamentMode);
        ScriptCoreUtils.defineFunction(global, namespace, "areCheatsEnabled", 0,
                                       (context, scope, thisObject, arguments) -> this.scriptGame.getGame().areCheatsEnabled());

        ScriptCoreUtils.defineFunction(global, namespace, "getExpansion", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.getExpansion().getValue());
        ScriptCoreUtils.defineFunction(global, namespace, "setExpansion", 1, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int value = ScriptCoreUtils.readInteger(arguments, 0);

            if (value < 0 || value > BYTE_MAXIMUM) {
                throw ScriptRuntime.rangeError(Exceptions.format("The expansion byte {0} is not from 0 to {1}.", value, BYTE_MAXIMUM));
            }

            this.getExpansion().setValue(value);

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines a read of an integer game option.
     */
    private void defineSettingRead(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name,
                                   @NotNull ToIntFunction<GameSettings> read) {
        ScriptCoreUtils.defineFunction(global, namespace, name, 0,
                                       (context, scope, thisObject, arguments) -> (double) read.applyAsInt(this.requireSettings()));
    }

    private void defineCheatFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getCheatFlags", 0, (context, scope, thisObject, arguments) -> {
            int flags = this.requireFrame().getVisibility().getCheatFlags();

            return (double) Integer.toUnsignedLong(flags);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setCheatFlags", 1, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int flags = (int) ScriptCoreUtils.readLong(arguments, 0);

            this.requireFrame().getVisibility().setCheatFlags(flags);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "applyCheats", 1, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int flags = (int) ScriptCoreUtils.readLong(arguments, 0);

            this.requireFrame();
            this.scriptGame.getGame().getGameCheats().apply(flags, this.scriptGame.getInstructionLimit());

            return Context.getUndefinedValue();
        });
    }

    /**
     * @return the frame timing of the game
     * @throws EcmaError if the game has no frame timing
     */
    private @NotNull GameSpeed requireSpeed() {
        GameSpeed speed = this.scriptGame.requireTriggerContext().getFrameTiming();

        if (speed == null) {
            throw ScriptRuntime.typeError("The game has no frame timing.");
        }

        return speed;
    }

    /**
     * @return the game options the game started with
     * @throws EcmaError if the game has none
     */
    private @NotNull GameSettings requireSettings() {
        GameSettings settings = this.scriptGame.getGame().getGameSettings();

        if (settings == null) {
            throw ScriptRuntime.typeError("The game has no game settings.");
        }

        return settings;
    }

    /**
     * @return the frame controls of the game, which hold the cheat flags
     * @throws EcmaError if the game has no frame
     */
    private @NotNull GameFrame requireFrame() {
        GameFrame frame = this.scriptGame.getGame().getFrame();

        if (frame == null) {
            throw ScriptRuntime.typeError("The game has no frame controls.");
        }

        return frame;
    }

    private @NotNull ContextExpansion getExpansion() {
        return this.scriptGame.getGame().getPlayerTable().requirePlayerUpgrades().getExpansion();
    }

    /**
     * Reads an unsigned WORD of the game header.
     *
     * @throws EcmaError if the game has no game header
     */
    private int readHeaderWord(int offset) {
        Game game = this.scriptGame.getGame();
        byte[] header = game.getGameContext().getGameHeaderSection();

        if (header == null || offset + Short.BYTES > header.length) {
            throw ScriptRuntime.typeError("The game has no game header.");
        }

        return LittleEndianUtils.readUnsigned16(header, offset);
    }
}
