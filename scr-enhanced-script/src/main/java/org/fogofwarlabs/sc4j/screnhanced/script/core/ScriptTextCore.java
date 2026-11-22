package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.text.LegacyTextUtils;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.presentation.StatTextOverrides;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;

import java.nio.charset.StandardCharsets;

/**
 * Defines {@code core.strings} and {@code core.messages}: the game texts a map script shows. The functions run in shared and local code; shared
 * code changes the texts of every peer, local code only the local ones. They change no game state.
 * <p>
 * {@code core.strings.setStatText(id, text)} shows a text instead of a {@code stat_txt} string ({@link StatTextOverrides}), the one-based string
 * identifier of the game's table, from {@code 1} to {@value #MAXIMUM_STRING_ID}; {@code null} shows the game's string again. A script text wins
 * over the installed table and over the table EUD memory commits; the name string of a unit type also wins over the map's name of the type.
 * <p>
 * {@code core.messages.clear()} empties the message lines on the screen. The lines from {@code 0} to {@value MessageLines#LINE_COUNT} minus one
 * ({@code getLineCount()}) are those the game writes: the messages take lines {@code 0} to {@value MessageLines#ROTATING_LINES} minus one in
 * turn, from the next line ({@code getNextLine()}, a byte; a value of {@value MessageLines#ROTATING_LINES} or more writes line {@code 0}), and
 * the status texts line {@value MessageLines#SYSTEM_LINE}. {@code getLine(line)} reads the text of a line up to its first zero byte;
 * {@code setLine(line, text)} stores the text's UTF-8 bytes, at most {@value MessageLines#LINE_SIZE} minus one and a zero byte, and
 * {@code setNextLine(value)} the next line, as EUD writes of the lines do: the line keeps the expiry time and the style of the message it held,
 * so a line whose message expired is cleared at the next expiry, which the client runs every drawn frame.
 */
final class ScriptTextCore {
    /**
     * The largest {@code stat_txt} string identifier: the table counts its strings in a WORD.
     */
    private static final int MAXIMUM_STRING_ID = 0xFFFF;
    /**
     * The largest next-line value, a byte.
     */
    private static final int MAXIMUM_NEXT_LINE = 0xFF;

    private final @NotNull ScriptGame scriptGame;

    /**
     * Creates a {@link ScriptTextCore} instance.
     *
     * @param scriptGame the game binding
     */
    ScriptTextCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame = scriptGame;
    }

    /**
     * Defines the functions of {@code core.strings}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void defineStrings(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "setStatText", 2, (context, scope, thisObject, arguments) -> {
            int id = ScriptCoreUtils.readInteger(arguments, 0);
            Object text = arguments.length > 1 ? arguments[1] : null;

            if (id < 1 || id > MAXIMUM_STRING_ID) {
                throw ScriptRuntime.rangeError(Exceptions.format("The stat_txt string id {0} is not from 1 to {1}.", id, MAXIMUM_STRING_ID));
            }

            this.getOverrides().set(id, text == null || text instanceof Undefined ? null : ScriptCoreUtils.readString(arguments, 1));

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines the functions of {@code core.messages}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void defineMessages(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "clear", 0, (context, scope, thisObject, arguments) -> {
            MessageLines lines = this.getMessageLines();

            for (int line = 0; line < MessageLines.LINE_COUNT; line++) {
                lines.writeByte(line * MessageLines.LINE_SIZE, 0);
            }

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getLineCount", 0,
                                       (context, scope, thisObject, arguments) -> (double) MessageLines.LINE_COUNT);
        ScriptCoreUtils.defineFunction(global, namespace, "getLine", 1, (context, scope, thisObject, arguments) -> {
            byte[] text = this.getMessageLines().getLine(readLine(arguments));

            return LegacyTextUtils.decode(text, 0, text.length);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setLine", 2, (context, scope, thisObject, arguments) -> {
            int line = readLine(arguments);
            byte[] text = ScriptCoreUtils.readString(arguments, 1).getBytes(StandardCharsets.UTF_8);
            int length = Math.min(text.length, MessageLines.LINE_SIZE - 1);
            MessageLines lines = this.getMessageLines();

            for (int index = 0; index < length; index++) {
                lines.writeByte(line * MessageLines.LINE_SIZE + index, text[index]);
            }

            lines.writeByte(line * MessageLines.LINE_SIZE + length, 0);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getNextLine", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.getMessageLines().getNextLine());
        ScriptCoreUtils.defineFunction(global, namespace, "setNextLine", 1, (context, scope, thisObject, arguments) -> {
            int value = ScriptCoreUtils.readInteger(arguments, 0);

            if (value < 0 || value > MAXIMUM_NEXT_LINE) {
                throw ScriptRuntime.rangeError(Exceptions.format("The next line {0} is not from 0 to {1}.", value, MAXIMUM_NEXT_LINE));
            }

            this.getMessageLines().setNextLine(value);

            return Context.getUndefinedValue();
        });
    }

    private static int readLine(@NotNull Object @NotNull [] arguments) {
        return ScriptCoreUtils.requireId("message line", ScriptCoreUtils.readInteger(arguments, 0), MessageLines.LINE_COUNT);
    }

    private @NotNull MessageLines getMessageLines() {
        return this.scriptGame.getGame().getInterfaceLink().getMessageLines();
    }

    private @NotNull StatTextOverrides getOverrides() {
        return this.scriptGame.getGame().getInterfaceLink().getStatTextOverrides();
    }
}
