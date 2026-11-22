package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.engine.client.console.ConsoleCommandRegistry;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextClipboard;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextEditKey;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextEditOperation;
import org.fogofwarlabs.sc4j.engine.client.input.KeyModifier;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.EditText;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.InputMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * Keeps the chat edit control and routes client commands locally before live game text.
 */
public final class ChatPrompt {
    /**
     * Chat in a single-player game.
     */
    public static final int MODE_SINGLE_PLAYER = 1;
    /**
     * Chat to every player.
     */
    public static final int MODE_ALL = 2;
    /**
     * Chat to allied players.
     */
    public static final int MODE_ALLIES = 3;
    /**
     * Chat to the selected player or force.
     */
    public static final int MODE_RECIPIENT = 4;
    /**
     * Chat as an observer or replay viewer.
     */
    public static final int MODE_OBSERVERS = 5;

    /**
     * The recipient byte for chat to every player.
     */
    static final int RECIPIENT_ALL = 8;
    /**
     * The recipient byte for chat to the allies.
     */
    static final int RECIPIENT_ALLIES = 9;

    /**
     * The live chat byte limit.
     */
    private static final int CHAT_MAXIMUM_BYTES = 78;
    /**
     * The maximum client command length.
     */
    private static final int COMMAND_MAXIMUM_LENGTH = 120;
    /**
     * The mode reported while the prompt is closed.
     */
    private static final int CLOSED_MODE = 0;
    /**
     * The combined Shift and Control modifier mask.
     */
    private static final int MODIFIERS_SHIFT_CONTROL = KeyModifier.SHIFT.getMask() | KeyModifier.CONTROL.getMask();
    /**
     * The lowest code point the prompt accepts; control characters below it are ignored.
     */
    private static final int MINIMUM_INPUT_CODE_POINT = 32;

    /**
     * The route of a typed line: a local command, live game text or no action.
     */
    enum Route {
        /**
         * A Beacon.Link community command ({@link GameCommunityChat#isCommand}): run by the channel chat.
         */
        COMMUNITY,
        /**
         * A client command: run locally, never sent.
         */
        COMMAND,
        /**
         * Game text: the cheat parser, then chat in a network game.
         */
        GAME_TEXT,
        /**
         * Nothing: a blank line, or text outside a live game.
         */
        DROPPED
    }

    /**
     * Chooses the chat mode from network state, observer state, key modifiers and recipient.
     *
     * @param network          whether the game is a network game
     * @param observer         whether the local player observes
     * @param modifiers        the modifier mask ({@link KeyModifier#getMask()} bits)
     * @param alliancesAllowed whether the game allows alliances
     * @param recipient        the recipient byte
     * @return the chat mode
     */
    static int chooseMode(boolean network, boolean observer, int modifiers, boolean alliancesAllowed, int recipient) {
        if (!network) {
            return MODE_SINGLE_PLAYER;
        }

        if (observer) {
            return MODE_OBSERVERS;
        }

        if ((modifiers & MODIFIERS_SHIFT_CONTROL) != 0) {
            return MODE_ALL;
        }

        if (alliancesAllowed && KeyModifier.ALT.isIn(modifiers)) {
            return MODE_ALLIES;
        }

        if (recipient == RECIPIENT_ALL) {
            return MODE_ALL;
        }

        if (alliancesAllowed && recipient == RECIPIENT_ALLIES) {
            return MODE_ALLIES;
        }

        return MODE_RECIPIENT;
    }

    /**
     * Chooses the route of a typed line. A community command goes to the channel chat while the game has one; any other {@code /} line is a local
     * command.
     *
     * @param line      the typed line
     * @param live      whether a live game runs
     * @param community whether the game has the Beacon.Link community ({@link ClientSession#hasCommunityChat})
     * @return the line's route
     */
    static @NotNull Route route(@NotNull String line, boolean live, boolean community) {
        if (community && GameCommunityChat.isCommand(line)) {
            return Route.COMMUNITY;
        }

        if (ConsoleCommandRegistry.isCommand(line)) {
            return Route.COMMAND;
        }

        return live && !line.isBlank() ? Route.GAME_TEXT : Route.DROPPED;
    }

    private @Nullable EditText text;

    /**
     * The mode used at the most recent opening.
     */
    private int mode;

    /**
     * Creates a {@link ChatPrompt} instance with no open line.
     */
    ChatPrompt() {
        this.text = null;

        this.mode = CLOSED_MODE;
    }

    /**
     * @return the open line, or {@code null} while the prompt is closed
     */
    @Nullable EditText getText() {
        return this.text;
    }

    /**
     * @return {@code true} if the prompt is open, {@code false} otherwise
     */
    boolean isOpen() {
        return this.text != null;
    }

    /**
     * @return the open prompt's chat mode, or {@code 0} while closed
     */
    int getMode() {
        return this.text == null ? CLOSED_MODE : this.mode;
    }

    /**
     * @return the mode the box last opened with, also after it closed (the line taken at Enter goes to these recipients)
     */
    int getLastMode() {
        return this.mode;
    }


    /**
     * Opens the prompt and enables text input. Live chat is limited to {@code 78} UTF-8 bytes; client commands and text outside
     * a live game use the command limit.
     *
     * @param initial the initial text
     * @param mode    the chat mode
     * @param live    whether a live game runs
     */
    void open(@NotNull String initial, int mode, boolean live) {
        this.mode = mode;
        this.text = new EditText(initial, COMMAND_MAXIMUM_LENGTH, "");
        this.text.setAcceptance(next -> !live || ConsoleCommandRegistry.isCommand(next) ||
                                        next.getBytes(StandardCharsets.UTF_8).length <= CHAT_MAXIMUM_BYTES);

        InputMethod.setTextInput(true);
    }

    /**
     * Closes the prompt and returns its line.
     *
     * @return the typed line, empty while closed
     */
    @NotNull String take() {
        EditText line = this.text;

        this.text = null;

        InputMethod.setTextInput(false);

        return line == null ? "" : line.getText();
    }

    /**
     * Types a character under the limits of {@link #open}.
     *
     * @param codePoint            the Unicode code point
     * @param hostTimeMilliseconds the interface clock in milliseconds
     */
    void type(int codePoint, long hostTimeMilliseconds) {
        EditText line = this.text;

        if (line == null || codePoint < MINIMUM_INPUT_CODE_POINT) {
            return;
        }

        // A refused character rejects the pending IME insertion.
        if (!line.type(codePoint, hostTimeMilliseconds)) {
            InputMethod.rejectInsertion();
        }
    }

    /**
     * Applies an editing key (Backspace, Delete, Left, Right, Home, End).
     *
     * @param key                  the key
     * @param hostTimeMilliseconds the interface clock in milliseconds
     */
    void press(@NotNull TextEditKey key, long hostTimeMilliseconds) {
        if (this.text != null) {
            this.text.press(key, hostTimeMilliseconds);
        }
    }

    /**
     * Applies an edit operation (select all, cut, copy, paste, undo, redo) with a clipboard.
     *
     * @param operation            the operation
     * @param clipboard            the clipboard (the window's system clipboard in the client)
     * @param hostTimeMilliseconds the interface clock in milliseconds
     */
    void apply(@NotNull TextEditOperation operation, @NotNull TextClipboard clipboard, long hostTimeMilliseconds) {
        if (this.text != null) {
            this.text.apply(operation, clipboard, hostTimeMilliseconds);
        }
    }

    /**
     * Closes the prompt without taking its line.
     */
    void close() {
        this.text = null;

        InputMethod.setTextInput(false);
    }
}
