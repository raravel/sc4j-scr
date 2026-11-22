package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The {@code stat_txt} strings a plugin shows instead of those of the game's table, by their one-based identifiers. An overridden string wins over
 * the installed table and over the table the EUD emulation commits; an overridden unit name also wins over the map's name of the unit type
 * ({@link org.fogofwarlabs.sc4j.scr.game.state.Game#getUnitDisplayName}). The change count grows with every change.
 */
public final class StatTextOverrides {
    private final @NotNull Map<Integer, String> texts;

    private long changeCount;

    /**
     * Creates a {@link StatTextOverrides} instance without overridden strings.
     */
    public StatTextOverrides() {
        this.texts = new HashMap<>();

        this.changeCount = 0;
    }

    /**
     * @return the number of changes so far
     */
    public long getChangeCount() {
        return this.changeCount;
    }

    /**
     * Finds the overridden text of a string.
     *
     * @param identifier the one-based string identifier
     * @return the text, or {@code null} if the string is not overridden
     */
    public @Nullable String find(int identifier) {
        return this.texts.get(identifier);
    }

    /**
     * Sets or removes the overridden text of a string.
     *
     * @param identifier the one-based string identifier
     * @param text       the text, or {@code null} to show the game's string again
     */
    public void set(int identifier, @Nullable String text) {
        String previous = text == null ? this.texts.remove(identifier) : this.texts.put(identifier, text);

        if (previous == null ? text != null : !previous.equals(text)) {
            this.changeCount++;
        }
    }
}
