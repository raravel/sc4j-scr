package org.fogofwarlabs.sc4j.scr.client.ui.input;

import org.fogofwarlabs.sc4j.scr.client.ui.text.StatText;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves command-button strings and keys for the selected hotkey profile.
 * Default keys come from each button string; the device profile applies overrides. The disabled profile retains plain strings.
 * Transformed installed strings are cached. A game whose EUD emulation committed a {@code stat_txt} table reads the plain strings of that table.
 */
public final class HotkeyProfile {
    /**
     * The profile built from the device's default keys, with the settings overrides.
     */
    public static final @NotNull String DEVICE = "$device";
    /**
     * The profile name that disables every entry.
     */
    public static final @NotNull String NO_HOTKEYS = "$NoHotkeys";
    /**
     * The profile of default {@code stat_txt} keys without the user's overrides.
     */
    public static final @NotNull String DEFAULT = "$default";
    /**
     * The game flag of a game created with custom hotkeys disabled (game flags bit {@code 0}).
     */
    public static final int HOTKEYS_DISABLED_FLAG = 1;

    /**
     * The highlight color code of the appended key (yellow).
     */
    private static final char HIGHLIGHT_CODE = 0x03;
    /**
     * The code that restores the default color.
     */
    private static final char RESTORE_CODE = 0x01;
    /**
     * The diagnostic text of a string without a name ({@code %d} the id).
     */
    private static final @NotNull String TOO_SHORT_FORMAT = "## STR %d TOO SHORT (CHotkeyProfile::GetHotkeyNameText)";
    private static final int FIRST_COLOR_RANGE_START = 0x01;
    private static final int FIRST_COLOR_RANGE_END = 0x08;
    private static final int SECOND_COLOR_RANGE_START = 0x0E;
    private static final int SECOND_COLOR_RANGE_END = 0x11;
    private static final int THIRD_COLOR_RANGE_START = 0x15;
    private static final int THIRD_COLOR_RANGE_END = 0x19;
    private static final int FOURTH_COLOR_RANGE_START = 0x1B;
    private static final int FOURTH_COLOR_RANGE_END = 0x1F;

    /**
     * Selects the default profile when the game disables custom hotkeys.
     *
     * @param active    the active profile name
     * @param gameFlags the game flags
     * @return the profile name used by the game
     */
    public static @NotNull String selectForGame(@NotNull String active, int gameFlags) {
        return (gameFlags & HOTKEYS_DISABLED_FLAG) != 0 ? DEFAULT : active;
    }

    /**
     * Replaces the button key, keeps its type byte, strips body color codes and appends the highlighted key label.
     *
     * @param value the plain button string
     * @param key   the replacement key code point
     * @return the transformed string
     */
    static @NotNull String transform(@NotNull String value, int key) {
        int prefix = value.isEmpty() ? 0 : Character.charCount(value.codePointAt(0));
        StringBuilder result = new StringBuilder().appendCodePoint(key);

        if (value.length() > prefix) {
            result.append(value.charAt(prefix));
        }

        String body = value.length() > prefix + 1 ? value.substring(prefix + 1) : "";

        result.append(stripColorCodes(body));

        String shown = key < HotkeyBindingsUtils.ASCII_LIMIT ? String.valueOf((char) key).toUpperCase(Locale.ROOT)
                                                             : new String(Character.toChars(key));

        result.append(" [").append(HIGHLIGHT_CODE).append(shown).append(RESTORE_CODE).append(']');

        return result.toString();
    }

    /**
     * Removes the four color-code ranges while preserving tabs, line breaks and other control characters.
     *
     * @param body the string body
     * @return the body without color codes
     */
    static @NotNull String stripColorCodes(@NotNull String body) {
        StringBuilder result = new StringBuilder(body.length());

        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            boolean removed = character >= FIRST_COLOR_RANGE_START && character <= FIRST_COLOR_RANGE_END ||
                              character >= SECOND_COLOR_RANGE_START && character <= SECOND_COLOR_RANGE_END ||
                              character >= THIRD_COLOR_RANGE_START && character <= THIRD_COLOR_RANGE_END ||
                              character >= FOURTH_COLOR_RANGE_START && character <= FOURTH_COLOR_RANGE_END;

            if (!removed) {
                result.append(character);
            }
        }

        return result.toString();
    }

    private final @NotNull StatText text;
    private final @NotNull Map<Integer, Integer> keys;
    private final @NotNull Map<Integer, String> strings;

    /**
     * Creates a {@link HotkeyProfile} instance for the selected profile and overrides.
     *
     * @param text      the {@code stat_txt} strings
     * @param name      the active profile name: {@link #DEVICE}, {@link #DEFAULT} or {@link #NO_HOTKEYS}
     * @param overrides the settings keys by {@code stat_txt} id (applied to {@link #DEVICE} only)
     */
    public HotkeyProfile(@NotNull StatText text, @NotNull String name, @NotNull Map<Integer, Integer> overrides) {
        this.text    = text;
        this.keys    = new HashMap<>();
        this.strings = new HashMap<>();

        if (NO_HOTKEYS.equals(name)) {
            return;
        }

        for (int index = 0; index < HotkeyBindingsUtils.COUNT; index++) {
            int identifier = HotkeyBindingsUtils.getIdentifier(index);
            String value = text.getString(identifier);

            if (value.isEmpty()) {
                continue;
            }

            int key = value.codePointAt(0);

            if (key != HotkeyBindingsUtils.REPLACEMENT_CHARACTER) {
                this.keys.put(identifier, key);
            }
        }

        if (DEFAULT.equals(name)) {
            return;
        }

        for (Map.Entry<Integer, Integer> entry : overrides.entrySet()) {
            this.keys.put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Gets the installed button string for the identifier.
     *
     * @param identifier the one-based {@code stat_txt} id
     * @return the plain string, or the profile entry's transformed string
     */
    public @NotNull String getString(int identifier) {
        Integer key = this.keys.get(identifier);
        String value = this.text.getString(identifier);

        if (key == null) {
            return value;
        }

        return this.strings.computeIfAbsent(identifier, ignored -> transform(value, key));
    }

    /**
     * Returns the button string of an id in a game: the plain string or the entry's transform of it.
     * Once the game's EUD emulation has committed a {@code stat_txt} table, the game uses the profile {@link #NO_HOTKEYS}: every id
     * is the plain string as the game reads it ({@link StatText#getString(ScrGameView, int)}).
     *
     * @param view       the game view, or {@code null} outside a game
     * @param identifier the one-based {@code stat_txt} id
     * @return the plain string, or the profile entry's transformed string
     */
    public @NotNull String getString(@Nullable ScrGameView view, int identifier) {
        if (view == null || !view.getGameContext().hasStatTextTable()) {
            return this.getString(identifier);
        }

        return this.text.getString(view, identifier);
    }

    /**
     * @param identifier the one-based {@code stat_txt} id
     * @return the hotkey of the button string (its first character), or {@code 0} for an empty string
     */
    public char getHotkey(int identifier) {
        return this.getHotkey(null, identifier);
    }

    /**
     * @param view       the game view, or {@code null} outside a game
     * @param identifier the one-based {@code stat_txt} id
     * @return the hotkey of the game's button string (its first character), or {@code 0} for an empty string
     */
    public char getHotkey(@Nullable ScrGameView view, int identifier) {
        String value = this.getString(view, identifier);

        return value.isEmpty() ? 0 : value.charAt(0);
    }

    /**
     * Returns the key of an id's entry.
     *
     * @param identifier the one-based {@code stat_txt} id
     * @return the entry's key code point, or {@code 0} when the profile has no entry for the id
     */
    public int getKey(int identifier) {
        return this.keys.getOrDefault(identifier, 0);
    }

    /**
     * Gets the button name without its first code point, type byte or color codes.
     * Returns the stored diagnostic text when the string is too short to contain that prefix.
     *
     * @param identifier the one-based {@code stat_txt} id
     * @return the button name
     */
    public @NotNull String getNameText(int identifier) {
        String value = this.text.getString(identifier);
        int prefix = value.isEmpty() ? 0 : Character.charCount(value.codePointAt(0)) + 1;

        if (value.isEmpty() || value.length() < prefix) {
            return String.format(Locale.ROOT, TOO_SHORT_FORMAT, identifier);
        }

        return stripColorCodes(value.substring(prefix));
    }
}
