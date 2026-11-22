package org.fogofwarlabs.sc4j.scr.game.player;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.IntUnaryOperator;

/**
 * Retains the player colors of a game: the color remapping rows (twelve rows of the eight {@code game/tunit.pcx} palette indices of each player,
 * then one {@code game/tminimap.pcx} index per player), the map colors of the map's {@code COLR} section and the game's custom colors.
 * <p>
 * The game start fills the rows from each player's color (the slots past the playable ones by their own index), then the map's {@code COLR}
 * section remaps them ({@link #applyMapColors}); an EUD write changes them, and the unit remapping and the minimap follow. The color a player is
 * shown in is its custom color when {@link #hasCustomColor} holds, otherwise the palette entry of its row's first byte.
 */
public final class PlayerColorRows {
    /**
     * The palette indices of one unit color row.
     */
    public static final int UNIT_ROW = 8;
    /**
     * The bytes of the block: twelve unit rows, then twelve minimap indices.
     */
    public static final int SIZE = PlayerTable.PLAYER_SLOTS * UNIT_ROW + PlayerTable.PLAYER_SLOTS;
    /**
     * The bytes of a {@code COLR} section: one color per playable slot.
     */
    public static final int MAP_COLORS_SIZE = PlayerTable.PLAYABLE_PLAYER_SLOTS;
    /**
     * The channels of a custom color entry: red, green, blue and alpha.
     */
    public static final int CUSTOM_COLOR_CHANNELS = 4;
    /**
     * The bytes of one custom color entry: a little-endian float per channel, {@code 0..1}.
     */
    public static final int CUSTOM_COLOR_ENTRY_SIZE = CUSTOM_COLOR_CHANNELS * Float.BYTES;
    /**
     * The bytes of a custom color table ({@code CCLR}): an entry per player slot.
     */
    public static final int CUSTOM_COLORS_SIZE = PlayerTable.PLAYER_SLOTS * CUSTOM_COLOR_ENTRY_SIZE;

    /**
     * The bytes of the unit rows of every player slot; the minimap indices follow them.
     */
    private static final int UNIT_ROWS_SIZE = PlayerTable.PLAYER_SLOTS * UNIT_ROW;
    /**
     * The largest value of a color channel.
     */
    private static final float CHANNEL_MAXIMUM = 255f;
    /**
     * The mask of one color channel.
     */
    private static final int CHANNEL_MASK = 0xFF;

    /**
     * Creates a {@link PlayerColorRows} instance filled from the players' colors, as the game start does.
     *
     * @param gameData the game data (the player color tables)
     * @param players  the player table
     * @return the created {@link PlayerColorRows} instance, or {@code null} if a player's color lies outside the supplied {@code tunit.pcx} rows
     * @throws UncheckedIOException if {@code tunit.pcx} or {@code tminimap.pcx} cannot be read
     */
    public static @Nullable PlayerColorRows create(@NotNull GameData gameData, @NotNull PlayerTable players) {
        int[][] colorTables;

        try {
            colorTables = gameData.getPlayerColorTables();
        } catch (IOException exception) {
            throw Exceptions.newUncheckedIOException(exception, "The player color rows need tunit.pcx and tminimap.pcx.");
        }

        PlayerColorRows rows = new PlayerColorRows();

        for (int slot = 0; slot < PlayerTable.PLAYER_SLOTS; slot++) {
            Player player = slot < PlayerTable.PLAYABLE_PLAYER_SLOTS ? players.getPlayer(slot) : null;
            int color = player != null ? player.getColor() : slot;

            if (color < 0 || color >= colorTables.length) {
                return null;
            }

            for (int index = 0; index < UNIT_ROW; index++) {
                rows.bytes[slot * UNIT_ROW + index] = (byte) colorTables[color][index];
            }

            rows.bytes[UNIT_ROWS_SIZE + slot] = (byte) colorTables[color][UNIT_ROW];
        }

        return rows;
    }

    /**
     * Encodes packed colors as a custom color table: each slot's red, green and blue divided by {@code 255} and alpha {@code 1}; a slot without a
     * color and the slots past the given ones stay zero.
     *
     * @param colors the packed {@code 0xRRGGBB} color of each slot, {@code -1} for none
     * @return the {@link #CUSTOM_COLORS_SIZE}-byte table
     */
    public static byte @NotNull [] encodeCustomColors(int @NotNull [] colors) {
        ByteBuffer buffer = ByteBuffer.allocate(CUSTOM_COLORS_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        for (int slot = 0; slot < colors.length && slot < PlayerTable.PLAYER_SLOTS; slot++) {
            if (colors[slot] < 0) {
                continue;
            }

            buffer.position(slot * CUSTOM_COLOR_ENTRY_SIZE);
            buffer.putFloat((colors[slot] >>> Short.SIZE & CHANNEL_MASK) / CHANNEL_MAXIMUM)
                  .putFloat((colors[slot] >>> Byte.SIZE & CHANNEL_MASK) / CHANNEL_MAXIMUM)
                  .putFloat((colors[slot] & CHANNEL_MASK) / CHANNEL_MAXIMUM)
                  .putFloat(1f);
        }

        return buffer.array();
    }

    /**
     * Decodes an entry of a custom color table: each channel is clamped to {@code 0..1}, multiplied by {@code 255} and truncated.
     *
     * @param table the custom color table
     * @param entry the entry
     * @return the packed {@code 0xRRGGBBAA} color
     */
    public static int decodeCustomColor(byte @NotNull [] table, int entry) {
        ByteBuffer entries = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
        int color = 0;

        for (int channel = 0; channel < CUSTOM_COLOR_CHANNELS; channel++) {
            float value = entries.getFloat(entry * CUSTOM_COLOR_ENTRY_SIZE + channel * Float.BYTES);

            color = color << Byte.SIZE | (int) (Math.clamp(value, 0f, 1f) * CHANNEL_MAXIMUM);
        }

        return color;
    }

    private final byte @NotNull [] bytes;
    /**
     * The map color of each playable slot, as the {@code COLR} section set it ({@code 0} where it left the slot's color).
     */
    private final byte @NotNull [] mapColors;
    /**
     * The packed {@code 0xRRGGBBAA} custom color of each player slot.
     */
    private final int @NotNull [] customColors;
    /**
     * Whether the game keeps its custom colors.
     */
    private boolean customColorsActive;

    /**
     * Creates a {@link PlayerColorRows} instance with zeroed rows, which {@link #create} or a restored saved game fills.
     */
    PlayerColorRows() {
        this.bytes        = new byte[SIZE];
        this.mapColors    = new byte[MAP_COLORS_SIZE];
        this.customColors = new int[PlayerTable.PLAYER_SLOTS];

        this.customColorsActive = false;
    }

    /**
     * @param player the player slot
     * @param index  the remapped color {@code 0..7} (palette indices {@code 8..15} of a unit frame)
     * @return the palette index
     */
    public int getUnitColor(int player, int index) {
        return Byte.toUnsignedInt(this.bytes[player * UNIT_ROW + index]);
    }

    /**
     * Sets a palette index of a player's unit color row, as an EUD write of the row's byte does: the unit remapping and the team color follow it
     * from the next drawn frame.
     *
     * @param player  the player slot
     * @param index   the remapped color {@code 0..7} (palette indices {@code 8..15} of a unit frame)
     * @param palette the palette index in the low eight bits
     */
    public void setUnitColor(int player, int index, int palette) {
        this.bytes[player * UNIT_ROW + index] = (byte) palette;
    }

    /**
     * @param player the player slot
     * @return the minimap palette index
     */
    public int getMinimapColor(int player) {
        return Byte.toUnsignedInt(this.bytes[UNIT_ROWS_SIZE + player]);
    }

    /**
     * Sets a player's minimap palette index, as an EUD write of its byte does: the minimap follows it from its next drawing.
     *
     * @param player  the player slot
     * @param palette the palette index in the low eight bits
     */
    public void setMinimapColor(int player, int palette) {
        this.bytes[UNIT_ROWS_SIZE + player] = (byte) palette;
    }

    /**
     * @param player the playable slot
     * @return the color the map's {@code COLR} section gave the slot, {@code 0} where the section left its color
     */
    public int getMapColor(int player) {
        return Byte.toUnsignedInt(this.mapColors[player]);
    }

    /**
     * Tests whether a player is shown in its custom color: the game keeps its custom colors and the slot is a playable one.
     *
     * @param player the player slot
     * @return {@code true} if the player is shown in {@link #getCustomColor}, {@code false} if in the palette entry of its row's first byte
     */
    public boolean hasCustomColor(int player) {
        return this.customColorsActive && player >= 0 && player < PlayerTable.PLAYABLE_PLAYER_SLOTS;
    }

    /**
     * @param player the player slot
     * @return the packed {@code 0xRRGGBBAA} custom color, {@code 0} where the slot has none
     */
    public int getCustomColor(int player) {
        return this.customColors[player];
    }

    /**
     * Returns the color a player is shown in, the same in every interface: its custom color when {@link #hasCustomColor} holds, otherwise the palette
     * entry of its row's first byte.
     *
     * @param player  the player slot
     * @param palette the packed {@code 0xRRGGBBAA} color of a palette index
     * @return the packed {@code 0xRRGGBBAA} color
     */
    public int getPlayerColor(int player, @NotNull IntUnaryOperator palette) {
        return this.hasCustomColor(player) ? this.customColors[player] : palette.applyAsInt(this.getUnitColor(player, 0));
    }

    /**
     * Returns the color of a player's minimap dots in the player color mode: the custom color of any slot while the game keeps its custom colors,
     * otherwise the palette color of its minimap index.
     *
     * @param player  the player slot
     * @param palette the packed {@code 0xRRGGBBAA} color of a palette index
     * @return the packed {@code 0xRRGGBBAA} color
     */
    public int getMinimapPlayerColor(int player, @NotNull IntUnaryOperator palette) {
        return this.customColorsActive ? this.customColors[player] : palette.applyAsInt(this.getMinimapColor(player));
    }

    /**
     * @return {@code true} if the game keeps its custom colors, {@code false} otherwise
     */
    public boolean isCustomColorsActive() {
        return this.customColorsActive;
    }

    /**
     * Sets the custom colors from a custom color table, as the game start does: the game keeps them when an entry of a playable slot is not zero.
     *
     * @param table the {@link #CUSTOM_COLORS_SIZE}-byte custom color table, or {@code null} for none
     * @throws IllegalArgumentException if the table has another size
     */
    public void setCustomColors(byte @Nullable [] table) {
        if (table != null && table.length != CUSTOM_COLORS_SIZE) {
            throw Exceptions.newIllegalArgumentException("A custom color table has {0} bytes, not {1}.", table.length, CUSTOM_COLORS_SIZE);
        }

        boolean active = false;

        for (int slot = 0; slot < PlayerTable.PLAYER_SLOTS; slot++) {
            for (int offset = 0; table != null && slot < PlayerTable.PLAYABLE_PLAYER_SLOTS && offset < CUSTOM_COLOR_ENTRY_SIZE; offset++) {
                active |= table[slot * CUSTOM_COLOR_ENTRY_SIZE + offset] != 0;
            }

            this.customColors[slot] = table != null ? decodeCustomColor(table, slot) : 0;
        }

        this.customColorsActive = active;
    }

    /**
     * Remaps the rows by the map's {@code COLR} section, as its map loader does: from the last slot to the first, a slot whose color names another
     * of the twelve rows takes that row and minimap index as they were before the section and keeps the color as its map color. A section of
     * another size changes nothing.
     *
     * @param section the {@code COLR} section
     */
    public void applyMapColors(byte @NotNull [] section) {
        if (section.length != MAP_COLORS_SIZE) {
            return;
        }

        byte[] previous = this.bytes.clone();

        for (int slot = section.length - 1; slot >= 0; slot--) {
            int color = Byte.toUnsignedInt(section[slot]);

            if (color == slot || color >= PlayerTable.PLAYER_SLOTS) {
                continue;
            }

            System.arraycopy(previous, color * UNIT_ROW, this.bytes, slot * UNIT_ROW, UNIT_ROW);

            this.bytes[UNIT_ROWS_SIZE + slot] = previous[UNIT_ROWS_SIZE + color];
            this.mapColors[slot]              = (byte) color;
        }
    }

    /**
     * Reads a byte of the block, as an EUD read of the game context does.
     *
     * @param offset the byte offset in the block
     * @return the byte
     */
    public int readByte(int offset) {
        return Byte.toUnsignedInt(this.bytes[offset]);
    }

    /**
     * Writes a byte of the block, as an EUD write of the game context does.
     *
     * @param offset the byte offset in the block
     * @param value  the byte in the low eight bits
     */
    public void writeByte(int offset, int value) {
        this.bytes[offset] = (byte) value;
    }

    /**
     * Copies the unit rows of every player slot.
     *
     * @param destination the array receiving the {@link #UNIT_ROW} palette indices of every player slot
     */
    public void copyUnitRows(byte @NotNull [] destination) {
        System.arraycopy(this.bytes, 0, destination, 0, UNIT_ROWS_SIZE);
    }
}
