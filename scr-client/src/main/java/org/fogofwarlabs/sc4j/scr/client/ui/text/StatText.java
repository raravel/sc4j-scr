package org.fogofwarlabs.sc4j.scr.client.ui.text;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.engine.client.text.NameSubstitution;
import org.fogofwarlabs.sc4j.engine.client.text.TextSubstitutionHook;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.data.format.casc.AssetResolver;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.text.LegacyTextUtils;
import org.fogofwarlabs.sc4j.scr.game.state.GameContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Holds localized interface strings loaded from {@code stat_txt.json}.
 * String identifiers are one-based file positions. Escape sequences are decoded when the table is parsed;
 * button strings retain their hotkey, type and text color control characters.
 */
public final class StatText {
    /**
     * The one-based string of the first unit name; unit type {@code n} uses {@code n + 1}.
     */
    public static final int FIRST_UNIT_NAME = 1;

    /**
     * The zero-based position of the first rank string ({@code STR_FIRST_TERRAN_RANK}); units.dat sublabel {@code n} uses
     * position {@code 1300 + n}.
     */
    public static final int RANK_BASE = 1300;

    private static final @NotNull String SERVICE_NAME = "Battle.net";
    private static final @NotNull String PRODUCT_NAME = "Beacon.Link";
    /**
     * The registered sign the installed text puts after the service's name; Beacon.Link has none.
     */
    private static final @NotNull String REGISTERED_SIGN = "\u00AE";
    private static final @NotNull NameSubstitution REGISTERED_NAME_SUBSTITUTION = new NameSubstitution(SERVICE_NAME + REGISTERED_SIGN, PRODUCT_NAME);
    private static final @NotNull NameSubstitution NAME_SUBSTITUTION = new NameSubstitution(SERVICE_NAME, PRODUCT_NAME);

    /**
     * The substitution applied to every decoded interface string: the online service's name {@code Battle.net}, with or without its registered
     * sign, is shown as {@code Beacon.Link} (SC4J's own online service, not a registered name). It applies at the one decode step of the installed
     * string tables ({@link #decode}: {@code stat_txt}, {@code gluAll}, {@code network}, {@code RC3} and the campaign names), never to keys, file
     * names, protocol identifiers or text that a map or a player wrote; the domain name {@code battle.net} is kept.
     */
    public static final @NotNull TextSubstitutionHook PRODUCT_NAME_SUBSTITUTION = text -> NAME_SUBSTITUTION.substitute(REGISTERED_NAME_SUBSTITUTION.substitute(text));

    private static final int BUTTON_PREFIX_LENGTH = 2;
    private static final int UNICODE_ESCAPE_LENGTH = 6;
    private static final int UNICODE_DIGITS_OFFSET = 2;
    private static final int HEXADECIMAL_RADIX = 16;

    /**
     * Creates a {@link StatText} instance from the installed strings of a locale.
     *
     * @param storage the installed game storage
     * @param locale  the locale folder, for example {@code enUS} or {@code koKR}
     * @return the created {@link StatText} instance
     * @throws IOException if the file is missing or malformed
     */
    public static @NotNull StatText load(@NotNull CascStorage storage, @NotNull String locale) throws IOException {
        // The localized resolver: the locale, then enUS, then the unlocalized file.
        return parse(new AssetResolver(storage, AssetResolver.AssetSet.HD, "", locale).readLocalized("rez/stat_txt.json"), locale);
    }

    /**
     * Creates a {@link StatText} instance of an unknown locale from the contents of a {@code stat_txt.json} file.
     *
     * @param contents the file contents
     * @return the created {@link StatText} instance
     * @throws IOException if the contents are not a list of entries with a {@code Value}
     */
    public static @NotNull StatText parse(byte @NotNull [] contents) throws IOException {
        return parse(contents, null);
    }

    private static @NotNull StatText parse(byte @NotNull [] contents, @Nullable String locale) throws IOException {
        if (!(JsonReader.parse(contents) instanceof List<?> entries)) {
            throw Exceptions.newIOException("stat_txt.json must be a list of entries.");
        }

        List<String> strings = new ArrayList<>(entries.size());

        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map) || !(map.get("Value") instanceof String value)) {
                throw Exceptions.newIOException("A stat_txt.json entry has no string Value.");
            }

            strings.add(decode(value));
        }

        return new StatText(locale, strings);
    }

    /**
     * Decodes Unicode and line-break escape sequences in installed string values, then applies the client's product name
     * through {@link #PRODUCT_NAME_SUBSTITUTION}.
     *
     * @param value the string value as stored in the JSON file
     * @return the decoded string
     */
    public static @NotNull String decode(@NotNull String value) {
        StringBuilder builder = new StringBuilder(value.length());

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);

            if (character != '\\' || index + 1 >= value.length()) {
                builder.append(character);

                continue;
            }

            char escape = value.charAt(index + 1);

            if (escape == 'u' && index + UNICODE_ESCAPE_LENGTH <= value.length()) {
                try {
                    builder.append((char) Integer.parseInt(value.substring(index + UNICODE_DIGITS_OFFSET, index + UNICODE_ESCAPE_LENGTH),
                                                           HEXADECIMAL_RADIX));

                    index += UNICODE_ESCAPE_LENGTH - 1;

                    continue;
                } catch (NumberFormatException exception) {
                    builder.append(character);

                    continue;
                }
            }

            if (escape == 'n') {
                builder.append('\n');

                index++;

                continue;
            }

            builder.append(character);
        }

        return PRODUCT_NAME_SUBSTITUTION.substitute(builder.toString());
    }

    /**
     * Removes the control bytes (text color codes) from a string.
     *
     * @param value the string
     * @return the visible characters; line breaks are kept
     */
    public static @NotNull String stripColors(@NotNull String value) {
        StringBuilder builder = new StringBuilder(value.length());

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);

            if (character == '\n' || character >= ' ') {
                builder.append(character);
            }
        }

        return builder.toString();
    }

    private static @NotNull String truncateAtNul(@NotNull String value) {
        int end = value.indexOf('\0');

        return end < 0 ? value : value.substring(0, end);
    }

    private final @Nullable String locale;
    private final @NotNull List<String> strings;

    private StatText(@Nullable String locale, @NotNull List<String> strings) {
        this.locale  = locale;
        this.strings = List.copyOf(strings);
    }

    /**
     * @return the locale folder the strings were loaded for ({@code enUS}, {@code koKR}, ...), or {@code null} when unknown
     */
    public @Nullable String getLocale() {
        return this.locale;
    }

    /**
     * @return the number of strings
     */
    public int getCount() {
        return this.strings.size();
    }

    /**
     * Reads an installed string by its one-based identifier.
     *
     * @param identifier the one-based string identifier
     * @return the string, or an empty string for {@code 0} or an identifier outside the list
     */
    public @NotNull String getString(int identifier) {
        if (identifier <= 0 || identifier > this.strings.size()) {
            return "";
        }

        return this.strings.get(identifier - 1);
    }

    /**
     * Reads a string a plugin of the game overrides ({@link ScrGameView#findStatTextOverride}), else from the game's committed table, else the
     * installed string. {@link GameContext#getStatTextBytes} supplies the committed bytes, decoded through {@link LegacyTextUtils}.
     * A one-byte hotkey prefix is joined to text read at offset two with an intervening NUL character.
     *
     * @param view       the game view whose overrides and committed table apply, or {@code null} outside a game
     * @param identifier the one-based string identifier
     * @return the string
     */
    public @NotNull String getString(@Nullable ScrGameView view, int identifier) {
        String override = view == null ? null : view.findStatTextOverride(identifier);

        if (override != null) {
            return override;
        }

        byte[] head = view == null ? null : view.getGameContext().getStatTextBytes(identifier, 0);

        if (head == null) {
            return this.getString(identifier);
        }

        String value = LegacyTextUtils.decode(head, 0, head.length);

        if (head.length != 1) {
            return value;
        }

        byte[] text = view.getGameContext().getStatTextBytes(identifier, BUTTON_PREFIX_LENGTH);

        return text == null ? value : value + '\0' + LegacyTextUtils.decode(text, 0, text.length);
    }


    /**
     * Reads the name of a unit type before the first NUL character.
     *
     * @param unitType the units.dat type
     * @return the unit name
     */
    public @NotNull String getUnitName(int unitType) {
        return truncateAtNul(this.getString(unitType + FIRST_UNIT_NAME));
    }

    /**
     * Reads the map's unit-type name when present, otherwise the committed or installed unit-name string.
     *
     * @param view     the game view
     * @param unitType the units.dat type
     * @return the unit name
     */
    public @NotNull String getUnitName(@NotNull ScrGameView view, int unitType) {
        String mapName = view.getUnitDisplayName(unitType);

        if (mapName != null) {
            return mapName;
        }

        return truncateAtNul(this.getString(view, unitType + FIRST_UNIT_NAME));
    }

    /**
     * Reads the rank string of a units.dat sublabel.
     *
     * @param sublabel the units.dat sublabel
     * @return the rank string, or an empty string for sublabel {@code 0}
     */
    public @NotNull String getRank(int sublabel) {
        return sublabel == 0 ? "" : this.getString(RANK_BASE + sublabel + 1);
    }

    /**
     * Reads the first character of a button string as its hotkey.
     *
     * @param identifier the one-based string identifier of the button
     * @return the hotkey character, or {@code 0} for an empty string
     */
    public char getHotkey(int identifier) {
        String value = this.getString(identifier);

        return value.isEmpty() ? 0 : value.charAt(0);
    }

    /**
     * Reads the button type byte of a button string: {@code 1} unit, {@code 2} upgrade, {@code 3} technology use,
     * {@code 4} research, {@code 7} plain.
     *
     * @param identifier the one-based string identifier of the button
     * @return the type byte, or {@code 0} when absent
     */
    public int getButtonType(int identifier) {
        String value = this.getString(identifier);

        return value.length() < BUTTON_PREFIX_LENGTH ? 0 : value.charAt(1);
    }

    /**
     * Reads the visible text of an enabled button string without its hotkey, type byte or control bytes.
     *
     * @param identifier the one-based string identifier of the button
     * @return the tooltip text; line breaks are kept
     */
    public @NotNull String getButtonText(int identifier) {
        String value = this.getString(identifier);

        return stripColors(value.substring(Math.min(BUTTON_PREFIX_LENGTH, value.length())));
    }

    /**
     * Reads a string without control bytes for a disabled button or label.
     *
     * @param identifier the one-based string identifier
     * @return the visible text; line breaks are kept
     */
    public @NotNull String getPlainText(int identifier) {
        return stripColors(this.getString(identifier));
    }
}
