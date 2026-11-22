package org.fogofwarlabs.sc4j.scr.client.ui.input;

import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.input.KeyBinding;
import org.fogofwarlabs.sc4j.engine.client.input.KeyBindingTable;
import org.fogofwarlabs.sc4j.engine.client.input.KeyModifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds the play, chat and popup accelerator maps of virtual keys with Shift, Control and Alt bits, and matches input keys against them through
 * one engine {@link KeyBindingTable} per map: every input key whose virtual key ({@link VirtualKeys#of}) is an entry's key is bound to the entry.
 * Repeats match only repeatable entries. Control-group ranges use digits one through nine followed by zero.
 */
public final class GameAccelerators {
    /**
     * The modifier bit of Shift in a table entry (the engine {@link KeyModifier#SHIFT} mask).
     */
    public static final int SHIFT = 1;
    /**
     * The modifier bit of Ctrl in a table entry (the engine {@link KeyModifier#CONTROL} mask).
     */
    public static final int CONTROL = 2;
    /**
     * The modifier bit of Alt in a table entry (the engine {@link KeyModifier#ALT} mask).
     */
    public static final int ALT = 4;
    /**
     * The modifier bits an entry compares.
     */
    public static final int MODIFIER_MASK = SHIFT | CONTROL | ALT;
    /**
     * No accelerator matched.
     */
    public static final int NONE = -1;

    /**
     * Shift+1..9, Shift+0: add the selection to a control group.
     */
    public static final int GROUP_ADD = 0x3e8;
    /**
     * Ctrl+1..9, Ctrl+0: assign the selection to a control group.
     */
    public static final int GROUP_ASSIGN = 0x3f2;
    /**
     * Digits one through nine and zero recall a control group.
     */
    public static final int GROUP_RECALL = 0x3fc;
    /**
     * Alt+1..9, Alt+0: recall a control group and center the view on it.
     */
    public static final int GROUP_CENTER = 0x406;
    /**
     * The ids of one control group range (digits {@code 1..9}, then {@code 0}).
     */
    public static final int GROUP_RANGE = 10;
    /**
     * Ctrl+C, Alt+C: center the view on the active unit.
     */
    public static final int CENTER_ON_UNIT = 0x412;
    /**
     * Ctrl+M: turn {@code music_enabled} over.
     */
    public static final int TOGGLE_MUSIC = 0x413;
    /**
     * Ctrl+S: turn {@code sfx_enabled} over.
     */
    public static final int TOGGLE_SOUND = 0x414;
    /**
     * Ctrl+X, Alt+X: the Exit Program page.
     */
    public static final int EXIT_PROGRAM = 0x415;
    /**
     * Ctrl+Q, Alt+Q: the Quit Mission page.
     */
    public static final int QUIT_MISSION = 0x416;
    /**
     * Alt+O: the options popup.
     */
    public static final int OPTIONS = 0x417;
    /**
     * Alt+H, F11: the Help page.
     */
    public static final int HELP = 0x418;
    /**
     * Ctrl+R: show or hide the leaderboard rows after the first.
     */
    public static final int LEADERBOARD_PLAYERS = 0x419;
    /**
     * Alt+S: the Save Game dialog.
     */
    public static final int SAVE_GAME = 0x41a;
    /**
     * Alt+L: the Load Game dialog.
     */
    public static final int LOAD_GAME = 0x41b;
    /**
     * Single player: the next faster game speed.
     */
    public static final int INCREASE_SPEED = 0x41c;
    /**
     * {@code -}, numpad {@code -}: the next slower game speed in single player.
     */
    public static final int DECREASE_SPEED = 0x41d;
    /**
     * Alt+M: the game menu.
     */
    public static final int GAME_MENU = 0x41e;
    /**
     * Shift+F2 through F4 store the view position.
     */
    public static final int VIEW_STORE = 0x41f;
    /**
     * F2..F4: jump to a stored view position.
     */
    public static final int VIEW_RECALL = 0x422;
    /**
     * The stored view positions.
     */
    public static final int VIEW_SLOTS = 3;
    /**
     * F5: switch the graphics.
     */
    public static final int GRAPHICS = 0x425;
    /**
     * Alt+Enter: toggle the window mode at most once per second.
     */
    public static final int WINDOW_MODE = 0x426;
    /**
     * No key: the lighting toggle; no table entry produces it.
     */
    public static final int LIGHTING = 0x427;
    /**
     * F6: reset the view zoom.
     */
    public static final int RESET_ZOOM = 0x428;
    /**
     * Numpad zero selects the Battle.net action identifier.
     */
    public static final int BATTLE_NET_ACTION = 0x429;
    /**
     * The chat history dialog, the multiplayer meaning of {@link #INCREASE_SPEED_OR_CHAT_HISTORY}.
     */
    public static final int CHAT_HISTORY = 0x42a;
    /**
     * {@code =}, numpad {@code +}: {@link #INCREASE_SPEED} in single player, {@link #CHAT_HISTORY} in multiplayer.
     */
    public static final int INCREASE_SPEED_OR_CHAT_HISTORY = 0x42b;
    /**
     * Alt+F1: turn {@code MouseConfine} over in a window.
     */
    public static final int MOUSE_CONFINE = 0x42c;
    /**
     * Ctrl+Alt+T: turn {@code ShowTurnRate} over in a multiplayer game.
     */
    public static final int SHOW_TURN_RATE = 0x42d;
    /**
     * Ctrl+Alt+F: turn {@code ShowFPS} over.
     */
    public static final int SHOW_FPS = 0x42e;
    /**
     * Ctrl+A, Ctrl+X, Ctrl+C, Ctrl+V, Ctrl+Z, Ctrl+Shift+Z of the popup table: the focused edit's operations {@code 0..5}.
     */
    public static final int EDIT_SELECT_ALL = 0;
    /**
     * The last edit operation id of the popup table (Ctrl+Shift+Z, redo).
     */
    public static final int EDIT_REDO = 5;

    private static final int CUT_OPERATION_OFFSET = 1;
    private static final int COPY_OPERATION_OFFSET = 2;
    private static final int PASTE_OPERATION_OFFSET = 3;
    private static final int UNDO_OPERATION_OFFSET = 4;

    /**
     * The virtual keys of the tables; the virtual key of a digit or a letter is its upper-case ASCII code.
     */
    private static final int DIGIT_1 = '1';
    private static final int KEY_A = 'A';
    private static final int KEY_C = 'C';
    private static final int KEY_F = 'F';
    private static final int KEY_H = 'H';
    private static final int KEY_L = 'L';
    private static final int KEY_M = 'M';
    private static final int KEY_O = 'O';
    private static final int KEY_Q = 'Q';
    private static final int KEY_R = 'R';
    private static final int KEY_S = 'S';
    private static final int KEY_T = 'T';
    private static final int KEY_V = 'V';
    private static final int KEY_X = 'X';
    private static final int KEY_Z = 'Z';
    private static final int KEY_F2 = VirtualKeys.F2;
    private static final int KEY_F5 = VirtualKeys.F5;
    private static final int KEY_F6 = VirtualKeys.F6;
    private static final int KEY_F11 = VirtualKeys.F11;
    /**
     * The shift of the modifier bits in a table key ({@code modifiers << 8 | key}).
     */
    private static final int MODIFIER_SHIFT = 8;

    private static final @NotNull Map<Integer, Entry> PLAY_TABLE;
    private static final @NotNull Map<Integer, Entry> CHAT_TABLE;
    private static final @NotNull Map<Integer, Entry> POPUP_TABLE;
    private static final @NotNull KeyBindingTable<Entry> PLAY_BINDINGS;
    private static final @NotNull KeyBindingTable<Entry> CHAT_BINDINGS;
    private static final @NotNull KeyBindingTable<Entry> POPUP_BINDINGS;

    /**
     * Selects the play, chat or popup accelerator map.
     */
    public enum Mode {
        /**
         * Mode {@code 1}: the play table.
         */
        PLAY,
        /**
         * Mode {@code 2}: the chat table, while the chat line is open.
         */
        CHAT,
        /**
         * Mode {@code 3}: the popup table, while an in-game popup or the menus' popup over a game is open, or a trigger pause holds the game.
         */
        POPUP
    }

    /**
     * One table entry: a virtual key with its modifier bits and the accelerator id it produces.
     */
    public static final class Entry {
        private final int virtualKey;
        private final int modifiers;
        private final int identifier;
        private final boolean repeatable;

        private Entry(int virtualKey, int modifiers, int identifier, boolean repeatable) {
            this.virtualKey = virtualKey;
            this.modifiers  = modifiers;
            this.identifier = identifier;
            this.repeatable = repeatable;
        }

        /**
         * @return the Windows virtual-key code
         */
        public int getVirtualKey() {
            return this.virtualKey;
        }

        /**
         * @return the modifier bits ({@link #SHIFT}, {@link #CONTROL}, {@link #ALT})
         */
        public int getModifiers() {
            return this.modifiers;
        }

        /**
         * @return the accelerator id the handler switches on
         */
        public int getIdentifier() {
            return this.identifier;
        }

        /**
         * @return {@code true} if a key repeat also produces the id, {@code false} otherwise
         */
        public boolean isRepeatable() {
            return this.repeatable;
        }
    }

    static {
        Map<Integer, Entry> play = new HashMap<>();
        Map<Integer, Entry> chat = new HashMap<>();
        Map<Integer, Entry> popup = new HashMap<>();

        put(play, VirtualKeys.OEM_PLUS, 0, INCREASE_SPEED_OR_CHAT_HISTORY, false);
        put(play, VirtualKeys.ADD, 0, INCREASE_SPEED_OR_CHAT_HISTORY, false);
        put(play, VirtualKeys.OEM_MINUS, 0, DECREASE_SPEED, false);
        put(play, VirtualKeys.SUBTRACT, 0, DECREASE_SPEED, false);

        for (int offset = 0; offset < GROUP_RANGE; offset++) {
            // Group ids run over the digits 1..9 and then 0; the virtual key of digit d is VirtualKeys.DIGIT_0 + d.
            int virtualKey = offset == GROUP_RANGE - 1 ? VirtualKeys.DIGIT_0 : DIGIT_1 + offset;

            put(play, virtualKey, 0, GROUP_RECALL + offset, true);
            put(play, virtualKey, SHIFT, GROUP_ADD + offset, false);
            putBoth(play, chat, virtualKey, CONTROL, GROUP_ASSIGN + offset, false);
            putBoth(play, chat, virtualKey, ALT, GROUP_CENTER + offset, true);
        }

        putBoth(play, chat, KEY_C, CONTROL, CENTER_ON_UNIT, false);
        putBoth(play, chat, KEY_C, ALT, CENTER_ON_UNIT, false);
        putBoth(play, chat, KEY_H, ALT, HELP, false);
        putBoth(play, chat, KEY_L, ALT, LOAD_GAME, false);
        putBoth(play, chat, KEY_M, CONTROL, TOGGLE_MUSIC, false);
        putBoth(play, chat, KEY_M, ALT, GAME_MENU, false);
        putBoth(play, chat, KEY_O, ALT, OPTIONS, false);
        putBoth(play, chat, KEY_Q, CONTROL, QUIT_MISSION, false);
        putBoth(play, chat, KEY_Q, ALT, QUIT_MISSION, false);
        putBoth(play, chat, KEY_S, CONTROL, TOGGLE_SOUND, false);
        putBoth(play, chat, KEY_S, ALT, SAVE_GAME, false);
        putBoth(play, chat, KEY_R, CONTROL, LEADERBOARD_PLAYERS, false);
        putBoth(play, chat, KEY_F11, 0, HELP, false);

        for (int slot = 0; slot < VIEW_SLOTS; slot++) {
            putBoth(play, chat, KEY_F2 + slot, 0, VIEW_RECALL + slot, true);
            putBoth(play, chat, KEY_F2 + slot, SHIFT, VIEW_STORE + slot, false);
        }

        putBoth(play, chat, KEY_X, CONTROL, EXIT_PROGRAM, false);
        putBoth(play, chat, KEY_X, ALT, EXIT_PROGRAM, false);
        putBoth(play, chat, KEY_F5, 0, GRAPHICS, false);
        putBoth(play, chat, VirtualKeys.RETURN, ALT, WINDOW_MODE, false);
        putBoth(play, chat, VirtualKeys.NUMPAD_0, 0, BATTLE_NET_ACTION, false);
        put(play, KEY_F6, 0, RESET_ZOOM, false);
        putBoth(play, chat, VirtualKeys.F1, ALT, MOUSE_CONFINE, false);
        putBoth(play, chat, KEY_T, CONTROL | ALT, SHOW_TURN_RATE, false);
        putBoth(play, chat, KEY_F, CONTROL | ALT, SHOW_FPS, false);

        put(popup, KEY_M, CONTROL, TOGGLE_MUSIC, false);
        put(popup, KEY_S, CONTROL, TOGGLE_SOUND, false);
        put(popup, KEY_F5, 0, GRAPHICS, false);
        put(popup, VirtualKeys.RETURN, ALT, WINDOW_MODE, false);
        put(popup, VirtualKeys.F1, ALT, MOUSE_CONFINE, false);
        put(popup, KEY_F, CONTROL | ALT, SHOW_FPS, false);
        put(popup, KEY_A, CONTROL, EDIT_SELECT_ALL, false);
        put(popup, KEY_X, CONTROL, EDIT_SELECT_ALL + CUT_OPERATION_OFFSET, false);
        put(popup, KEY_C, CONTROL, EDIT_SELECT_ALL + COPY_OPERATION_OFFSET, false);
        put(popup, KEY_V, CONTROL, EDIT_SELECT_ALL + PASTE_OPERATION_OFFSET, false);
        put(popup, KEY_Z, CONTROL, EDIT_SELECT_ALL + UNDO_OPERATION_OFFSET, false);
        put(popup, KEY_Z, CONTROL | SHIFT, EDIT_REDO, false);

        PLAY_TABLE  = Collections.unmodifiableMap(play);
        CHAT_TABLE  = Collections.unmodifiableMap(chat);
        POPUP_TABLE = Collections.unmodifiableMap(popup);

        PLAY_BINDINGS  = createBindings(PLAY_TABLE);
        CHAT_BINDINGS  = createBindings(CHAT_TABLE);
        POPUP_BINDINGS = createBindings(POPUP_TABLE);
    }

    /**
     * Binds every input key whose virtual key has entries to those entries, with the entries' modifiers and repeat flags.
     *
     * @param table the entries by {@code modifiers << 8 | virtual key}
     * @return the bindings, comparing Shift, Control and Alt
     */
    private static @NotNull KeyBindingTable<Entry> createBindings(@NotNull Map<Integer, Entry> table) {
        KeyBindingTable<Entry> bindings = new KeyBindingTable<>(MODIFIER_MASK);

        for (Key key : Key.values()) {
            int virtualKey = VirtualKeys.of(key);

            if (virtualKey < 0) {
                continue;
            }

            for (int modifiers = 0; modifiers <= MODIFIER_MASK; modifiers++) {
                Entry entry = table.get(modifiers << MODIFIER_SHIFT | virtualKey);

                if (entry != null) {
                    bindings.bind(key, modifiers, entry, entry.isRepeatable());
                }
            }
        }

        return bindings;
    }

    private static void putBoth(@NotNull Map<Integer, Entry> play, @NotNull Map<Integer, Entry> chat,
                                int virtualKey, int modifiers,
                                int identifier,
                                boolean repeatable) {
        put(play, virtualKey, modifiers, identifier, repeatable);
        put(chat, virtualKey, modifiers, identifier, repeatable);
    }

    private static void put(@NotNull Map<Integer, Entry> table, int virtualKey, int modifiers, int identifier, boolean repeatable) {
        table.put(modifiers << MODIFIER_SHIFT | virtualKey, new Entry(virtualKey, modifiers, identifier, repeatable));
    }

    /**
     * Selects the table of a mode.
     *
     * @param mode the mode
     * @return the entries by {@code modifiers << 8 | virtual key}
     */
    public static @NotNull Map<Integer, Entry> getTable(@NotNull Mode mode) {
        return switch (mode) {
            case PLAY -> PLAY_TABLE;
            case CHAT -> CHAT_TABLE;
            case POPUP -> POPUP_TABLE;
        };
    }

    /**
     * Matches a key against the table of a mode.
     *
     * @param mode      the active mode
     * @param key       the key
     * @param modifiers the engine modifier mask of the key event; only Shift, Ctrl and Alt count
     * @param repeat    whether the event is a key repeat
     * @return the matching entry, or {@code null} when the key is no accelerator of the mode
     */
    public static @Nullable Entry find(@NotNull Mode mode, @NotNull Key key, int modifiers, boolean repeat) {
        KeyBindingTable<Entry> bindings = switch (mode) {
            case PLAY -> PLAY_BINDINGS;
            case CHAT -> CHAT_BINDINGS;
            case POPUP -> POPUP_BINDINGS;
        };
        KeyBinding<Entry> binding = bindings.find(key, modifiers, repeat);

        return binding != null ? binding.getAction() : null;
    }

    /**
     * Returns the control group digit of a group accelerator id: the ids of a group range run over the digits {@code 1..9} and then {@code 0}.
     *
     * @param identifier the accelerator id
     * @param base       the first id of its range ({@link #GROUP_ADD}, {@link #GROUP_ASSIGN}, {@link #GROUP_RECALL} or {@link #GROUP_CENTER})
     * @return the digit {@code 0..9}
     */
    public static int getGroupDigit(int identifier, int base) {
        return (identifier - base + 1) % GROUP_RANGE;
    }

    /**
     * @param identifier the accelerator id
     * @param base       the first id of a group range
     * @return {@code true} if the id lies in the group range starting at {@code base}, {@code false} otherwise
     */
    public static boolean isInGroupRange(int identifier, int base) {
        return identifier >= base && identifier < base + GROUP_RANGE;
    }

    /**
     * Tells whether a play accelerator acts on the game rather than on the interface: the control group keys, the stored screen positions,
     * centering on the active unit and the speed keys. {@link #INCREASE_SPEED_OR_CHAT_HISTORY} is resolved before, as it opens the chat history
     * in a network game.
     *
     * @param identifier the accelerator id
     * @return {@code true} if the accelerator acts on the game, {@code false} if it opens a menu or dialog or switches an interface setting
     */
    public static boolean isGameAction(int identifier) {
        return isInGroupRange(identifier, GROUP_ADD) || isInGroupRange(identifier, GROUP_ASSIGN) || isInGroupRange(identifier, GROUP_RECALL) ||
               isInGroupRange(identifier, GROUP_CENTER) ||
               identifier >= VIEW_STORE && identifier < VIEW_RECALL + VIEW_SLOTS ||
               identifier == CENTER_ON_UNIT || identifier == INCREASE_SPEED || identifier == DECREASE_SPEED;
    }

    private GameAccelerators() {
    }
}
