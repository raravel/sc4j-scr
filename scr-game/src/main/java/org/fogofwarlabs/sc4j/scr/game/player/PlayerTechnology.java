package org.fogofwarlabs.sc4j.scr.game.player;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.registry.TableKind;
import org.fogofwarlabs.sc4j.engine.content.table.PlayerProgressTable;
import org.fogofwarlabs.sc4j.engine.entity.column.ByteColumn;
import org.fogofwarlabs.sc4j.scr.data.format.dat.TechdataDat;
import org.fogofwarlabs.sc4j.scr.game.state.ContextExpansion;
import org.fogofwarlabs.sc4j.scr.game.state.GameContextBytes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Serves per-player technology availability, completed research and research in progress as context bytes. The availability and researched
 * bytes live in SC:R extension columns of the engine {@link PlayerProgressTable} ({@link PlayerProgressColumns}), which also holds whether each
 * byte is nonzero in its engine columns, and the researching bits and the legacy classic researching bits in extension columns; this view holds no
 * value of its own.
 * <p>
 * Layout of the game context (the EUD game-data block has the same offsets):
 * <ul>
 *     <li>classic availability and classic researched: one byte per player and classic technology;</li>
 *     <li>the legacy classic researching bits, only copied to the researching bits when an old structure version is loaded; nothing else reads
 *         them;</li>
 *     <li>expansion availability and expansion researched: one byte per player and expansion technology;</li>
 *     <li>the researching bits of all technologies, one bit per player and technology.</li>
 * </ul>
 * The fresh default branch fills both availability ranges with {@code 1} and clears the researched ranges. Map-settings games replace availability
 * and research with {@link #applySection(byte[], boolean)}.
 * <p>
 * The classic availability bytes follow the death table in the game context; as a {@link ContextDwordTable} the storage serves the DWORDs from there,
 * which a death counter past the death table addresses.
 * <p>
 * The availability, researched and researching accessors bound neither the player nor the technology: a player past the player slots (an owner byte
 * that EUD wrote) or a technology past the technology table addresses another game-context byte by the same layout, which this storage or its
 * game-context view serves; a write there stores that byte.
 */
public final class PlayerTechnology implements ContextDwordTable, GameContextBytes {
    /**
     * Context offset of the classic availability bytes.
     */
    public static final int CLASSIC_AVAILABLE_OFFSET = 0xDD34;
    /**
     * Context offset of the classic researched bytes.
     */
    public static final int CLASSIC_RESEARCHED_OFFSET = 0xDE54;
    /**
     * Context offset of the legacy classic researching bits.
     */
    public static final int LEGACY_RESEARCHING_OFFSET = 0xDF74;
    /**
     * Context offset of the expansion availability bytes (the first expansion technology of player {@code 0}).
     */
    public static final int EXPANSION_AVAILABLE_OFFSET = 0xFF60;
    /**
     * Context offset of the expansion researched bytes (the first expansion technology of player {@code 0}).
     */
    public static final int EXPANSION_RESEARCHED_OFFSET = 0x10050;
    /**
     * Context offset of the researching bits.
     */
    public static final int RESEARCHING_OFFSET = 0x10140;
    /**
     * The number of original (classic) technologies, the technologies a map's {@code PTEC} section covers.
     */
    public static final int CLASSIC_TECHNOLOGY_COUNT = 24;

    /**
     * The number of expansion technologies, the technologies after the classic ones.
     */
    private static final int EXPANSION_TECHNOLOGY_COUNT = TechdataDat.TECH_COUNT - CLASSIC_TECHNOLOGY_COUNT;
    /**
     * The bytes of one player's legacy classic researching bits.
     */
    private static final int LEGACY_RESEARCHING_BYTES = 3;
    /**
     * The bytes of one player's researching bits.
     */
    private static final int RESEARCHING_BYTES = 6;
    /**
     * The mask of an unsigned technology table byte, and of the technology BYTE a researching bit access truncates a technology identifier to.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of the technology WORD an availability or researched access truncates a technology identifier to.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The availability byte the fresh default branch and the availability setter store for an available technology.
     */
    static final byte AVAILABLE = 1;
    /**
     * The researched byte the researched setter stores for a finished research.
     */
    private static final int RESEARCHED = 1;

    /**
     * Checks whether a game-context offset lies in the technology tables.
     *
     * @param contextOffset a game-context offset
     * @return {@code true} if the offset lies in one of the technology tables of this storage, {@code false} otherwise
     */
    public static boolean hasContextOffset(int contextOffset) {
        return contextOffset >= CLASSIC_AVAILABLE_OFFSET &&
               contextOffset < LEGACY_RESEARCHING_OFFSET + PlayerTable.PLAYER_SLOTS * LEGACY_RESEARCHING_BYTES ||
               contextOffset >= EXPANSION_AVAILABLE_OFFSET &&
               contextOffset < RESEARCHING_OFFSET + PlayerTable.PLAYER_SLOTS * RESEARCHING_BYTES;
    }

    /**
     * Selects the technology table containing a context offset.
     *
     * @param contextOffset a game-context offset of the technology tables
     * @return the table start offset
     * @throws IllegalArgumentException if the offset lies outside the technology tables
     */
    private static int getTableBase(int contextOffset) {
        if (!hasContextOffset(contextOffset)) {
            throw Exceptions.newIllegalArgumentException("Context offset {0} is not a technology table.", String.format("0x%X", contextOffset));
        }

        if (contextOffset < CLASSIC_RESEARCHED_OFFSET) {
            return CLASSIC_AVAILABLE_OFFSET;
        }

        if (contextOffset < LEGACY_RESEARCHING_OFFSET) {
            return CLASSIC_RESEARCHED_OFFSET;
        }

        if (contextOffset < EXPANSION_AVAILABLE_OFFSET) {
            return LEGACY_RESEARCHING_OFFSET;
        }

        if (contextOffset < EXPANSION_RESEARCHED_OFFSET) {
            return EXPANSION_AVAILABLE_OFFSET;
        }

        if (contextOffset < RESEARCHING_OFFSET) {
            return EXPANSION_RESEARCHED_OFFSET;
        }

        return RESEARCHING_OFFSET;
    }

    /**
     * Returns the index of a byte of a player's bit set in a technology extension column: byte {@code byteIndex} of player {@code player} is the
     * entry {@code player * TECH_COUNT + byteIndex}.
     *
     * @param tableOffset the offset in the native bit set table, {@code player * bytes + byteIndex}
     * @param bytes       the bytes of one player's bit set
     * @return the column index
     */
    private static int getBitSetIndex(int tableOffset, int bytes) {
        return tableOffset / bytes * TechdataDat.TECH_COUNT + tableOffset % bytes;
    }

    /**
     * Returns the column index of the technology entry a classic or expansion table offset addresses.
     *
     * @param tableOffset the offset in the classic or expansion table
     * @param expansion   whether the table is an expansion table
     * @return the column index, {@code player * TECH_COUNT + technology}
     */
    private static int getEntryIndex(int tableOffset, boolean expansion) {
        if (expansion) {
            return tableOffset / EXPANSION_TECHNOLOGY_COUNT * TechdataDat.TECH_COUNT + CLASSIC_TECHNOLOGY_COUNT +
                   tableOffset % EXPANSION_TECHNOLOGY_COUNT;
        }

        return tableOffset / CLASSIC_TECHNOLOGY_COUNT * TechdataDat.TECH_COUNT + tableOffset % CLASSIC_TECHNOLOGY_COUNT;
    }

    /**
     * Returns the game-context offset of an availability or researched byte by the technology table layout.
     *
     * @param classicOffset   the game-context offset of the classic table
     * @param expansionOffset the game-context offset of the expansion table
     * @param playerIndex     the player DWORD, unsigned
     * @param technology      the technology WORD
     * @return the unsigned game-context offset
     */
    private static long getTableOffset(int classicOffset, int expansionOffset, int playerIndex, int technology) {
        return ContextTableAccess.getEntryOffset(classicOffset, CLASSIC_TECHNOLOGY_COUNT, expansionOffset, EXPANSION_TECHNOLOGY_COUNT,
                                                 playerIndex, technology);
    }

    /**
     * Checks whether a player and truncated technology identifier address an entry of the retained tables.
     *
     * @param playerIndex the player DWORD
     * @param technology  the truncated technology identifier
     * @return {@code true} if the player lies in the player slots and the technology in the technology table, {@code false} otherwise
     */
    private static boolean isTableEntry(int playerIndex, int technology) {
        return playerIndex >= 0 && playerIndex < PlayerTable.PLAYER_SLOTS && technology < TechdataDat.TECH_COUNT;
    }

    /**
     * The expansion BYTE of the game context, shared with {@link PlayerUpgrades}.
     */
    private final @NotNull ContextExpansion expansion;
    /**
     * Accesses the game-context bytes that an entry past the technology tables addresses.
     */
    private final @NotNull ContextTableAccess contextAccess;

    /**
     * The table whose engine columns hold whether each availability and researched byte is nonzero.
     */
    private final @NotNull PlayerProgressTable progress;
    private final @NotNull ByteColumn available;
    private final @NotNull ByteColumn researched;
    private final @NotNull ByteColumn legacyResearching;
    private final @NotNull ByteColumn researching;

    /**
     * Creates a {@link PlayerTechnology} instance over a progress table and the view of the other game-context bytes, and sets the normal game
     * defaults in the table: every technology available, none researched or researching.
     *
     * @param progress  the progress table of the game, with the SC:R extension columns ({@link PlayerProgressColumns})
     * @param expansion the expansion BYTE of the game context, read at every gated store
     * @param context   supplies the view of the whole game context as the world has it at the access, which serves the bytes outside the technology
     *                  tables, or {@code null} when no game context exists
     */
    public PlayerTechnology(@NotNull PlayerProgressTable progress, @NotNull ContextExpansion expansion, @Nullable Supplier<GameContextBytes> context) {
        this.expansion     = expansion;
        this.contextAccess = new ContextTableAccess(this, PlayerTechnology::hasContextOffset, context);

        this.progress          = progress;
        this.available         = progress.getExtensionColumns(TableKind.TECH).get(PlayerProgressColumns.TECH_AVAILABLE);
        this.researched        = progress.getExtensionColumns(TableKind.TECH).get(PlayerProgressColumns.TECH_RESEARCHED);
        this.legacyResearching = progress.getExtensionColumns(TableKind.TECH).get(PlayerProgressColumns.LEGACY_RESEARCHING);
        this.researching       = progress.getExtensionColumns(TableKind.TECH).get(PlayerProgressColumns.RESEARCHING);

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYER_SLOTS; playerIndex++) {
            for (int technology = 0; technology < TechdataDat.TECH_COUNT; technology++) {
                int index = playerIndex * TechdataDat.TECH_COUNT + technology;

                this.storeAvailableByte(index, AVAILABLE);
                this.storeResearchedByte(index, 0);
                this.legacyResearching.set(index, (byte) 0);
                this.researching.set(index, (byte) 0);
            }
        }
    }

    /**
     * Tells whether the player may research or use the technology. A player outside the player slots or a technology WORD outside the technology
     * table reads the game-context byte the availability layout addresses.
     *
     * @param playerIndex the original player DWORD, without truncation
     * @param technology  the original technology WORD
     * @return {@code true} if the availability byte is nonzero, {@code false} otherwise
     * @throws UnsupportedOperationException if the addressed byte lies past the game context, or outside the technology tables without a game
     *                                       context
     */
    public boolean isAvailable(int playerIndex, int technology) {
        technology &= WORD_MASK;

        if (isTableEntry(playerIndex, technology)) {
            return this.progress.isTechAvailable(playerIndex, technology);
        }

        return this.contextAccess.readByte(getTableOffset(CLASSIC_AVAILABLE_OFFSET, EXPANSION_AVAILABLE_OFFSET, playerIndex, technology)) != 0;
    }

    /**
     * Sets whether the player may research or use the technology, as an EUD write of the game-data block stores the availability byte, without an
     * expansion gate. A player outside the player slots or a technology WORD outside the technology table writes the game-context byte the
     * availability layout addresses.
     *
     * @param playerIndex the original player DWORD, without truncation
     * @param technology  the original technology WORD
     * @param available   whether the technology is available
     * @throws UnsupportedOperationException if the addressed byte lies past the game context, or outside the technology tables without one
     */
    public void setAvailable(int playerIndex, int technology, boolean available) {
        technology &= WORD_MASK;

        int value = available ? AVAILABLE : 0;

        if (isTableEntry(playerIndex, technology)) {
            this.storeAvailableByte(playerIndex * TechdataDat.TECH_COUNT + technology, value);
        } else {
            this.contextAccess.writeByte(getTableOffset(CLASSIC_AVAILABLE_OFFSET, EXPANSION_AVAILABLE_OFFSET, playerIndex, technology), value);
        }
    }

    /**
     * Tells whether the player has finished the research. A player outside the player slots or a technology WORD outside the technology table reads
     * the game-context byte the researched layout addresses.
     *
     * @param playerIndex the original player DWORD, without truncation
     * @param technology  the original technology WORD
     * @return {@code true} if the researched byte is nonzero, {@code false} otherwise
     * @throws UnsupportedOperationException if the addressed byte lies past the game context, or outside the technology tables without a game
     *                                       context
     */
    public boolean isResearched(int playerIndex, int technology) {
        technology &= WORD_MASK;

        if (isTableEntry(playerIndex, technology)) {
            return this.progress.isTechResearched(playerIndex, technology);
        }

        return this.contextAccess.readByte(getTableOffset(CLASSIC_RESEARCHED_OFFSET, EXPANSION_RESEARCHED_OFFSET, playerIndex, technology)) != 0;
    }

    /**
     * Sets whether the player has finished the research: classic technologies are stored always, the expansion ones only while the context expansion
     * BYTE is set; otherwise the write is dropped. A player outside the player slots or a technology WORD outside the technology table writes the
     * game-context byte the researched layout addresses.
     *
     * @param playerIndex the original player DWORD, without truncation
     * @param technology  the original technology WORD
     * @param researched  whether the research is complete
     * @throws UnsupportedOperationException if the expansion gate keeps the write and the addressed byte lies past the game context, or outside the
     *                                       technology tables without a game context
     */
    public void setResearched(int playerIndex, int technology, boolean researched) {
        this.storeResearched(playerIndex, technology, researched ? RESEARCHED : 0);
    }

    /**
     * Tells whether a building of the player is researching the technology. A player outside the player slots or a technology BYTE outside the
     * technology table tests the bit of the game-context byte the researching bit layout addresses.
     *
     * @param playerIndex the original player DWORD, without truncation
     * @param technology  the technology identifier, truncated to its BYTE
     * @return {@code true} if the researching bit is set, {@code false} otherwise
     * @throws UnsupportedOperationException if the addressed byte lies past the game context, or outside the technology tables without a game
     *                                       context
     */
    public boolean isResearching(int playerIndex, int technology) {
        technology &= BYTE_MASK;

        if (isTableEntry(playerIndex, technology)) {
            return (this.researching.get(playerIndex * TechdataDat.TECH_COUNT + (technology >> PlayerTable.BIT_SET_BYTE_SHIFT)) >>
                    (technology & PlayerTable.BIT_SET_BIT_MASK) & 1) != 0;
        }

        return this.contextAccess.isBitSet(ContextTableAccess.getBitByteOffset(RESEARCHING_OFFSET, RESEARCHING_BYTES, playerIndex, technology),
                                           technology);
    }

    /**
     * Sets or clears the researching bit. A player outside the player slots or a technology BYTE outside the technology table sets or clears the bit
     * of the game-context byte the researching bit layout addresses.
     *
     * @param playerIndex the original player DWORD, without truncation
     * @param technology  the technology identifier, truncated to its BYTE
     * @param researching whether the research is in progress
     * @throws UnsupportedOperationException if the addressed byte lies past the game context, or outside the technology tables without a game
     *                                       context
     */
    public void setResearching(int playerIndex, int technology, boolean researching) {
        technology &= BYTE_MASK;

        if (!isTableEntry(playerIndex, technology)) {
            this.contextAccess.setBit(ContextTableAccess.getBitByteOffset(RESEARCHING_OFFSET, RESEARCHING_BYTES, playerIndex, technology),
                                      technology,
                                      researching);

            return;
        }

        int index = playerIndex * TechdataDat.TECH_COUNT + (technology >> PlayerTable.BIT_SET_BYTE_SHIFT);
        int bit = 1 << (technology & PlayerTable.BIT_SET_BIT_MASK);
        byte bits = this.researching.get(index);

        this.researching.set(index, (byte) (researching ? bits | bit : bits & ~bit));
    }


    /**
     * Applies a {@code PTEC} or {@code PTEx} payload of a map-settings game: per player and technology, descending, the use-default byte selects the
     * global or player bytes; the availability byte is stored unchanged and the researched byte unchanged through the expansion write gate of
     * {@link #setResearched}. {@code PTEC} covers the classic technologies; {@code PTEx} covers all of them.
     *
     * @param payload          the complete section payload
     * @param expansionSection whether the section is {@code PTEx} rather than {@code PTEC}
     * @throws UnsupportedOperationException if the payload is shorter than the section layout
     */
    public void applySection(byte @NotNull [] payload, boolean expansionSection) {
        int technologyCount = expansionSection ? TechdataDat.TECH_COUNT : CLASSIC_TECHNOLOGY_COUNT;

        if (payload.length < technologyCount * PlayerTable.SETTINGS_ROWS) {
            throw Exceptions.newUnsupportedOperationException("Map technology settings are shorter than their section layout: {0}", payload.length);
        }

        for (int playerIndex = PlayerTable.PLAYER_SLOTS - 1; playerIndex >= 0; playerIndex--) {
            for (int technology = technologyCount - 1; technology >= 0; technology--) {
                int playerOffset = playerIndex * technologyCount + technology;
                boolean useDefault = payload[technologyCount * PlayerTable.SETTINGS_USE_DEFAULT_ROW + playerOffset] != 0;
                int availableOffset = useDefault ? technologyCount * PlayerTable.SETTINGS_DEFAULT_FIRST_ROW + technology : playerOffset;
                int researchedOffset = useDefault ? technologyCount * PlayerTable.SETTINGS_DEFAULT_SECOND_ROW + technology
                                                  : technologyCount * PlayerTable.SETTINGS_PLAYER_SECOND_ROW + playerOffset;

                this.storeAvailableByte(playerIndex * TechdataDat.TECH_COUNT + technology, payload[availableOffset]);
                this.storeResearched(playerIndex, technology, payload[researchedOffset]);
            }
        }
    }

    /**
     * Reads a byte of the technology tables by its game-context offset, as an EUD read of the game-data block does.
     *
     * @param contextOffset a game-context offset accepted by {@link #hasContextOffset(int)}
     * @return the unsigned byte
     */
    @Override
    public int readContextByte(int contextOffset) {
        int tableBase = getTableBase(contextOffset);
        int tableOffset = contextOffset - tableBase;

        return switch (tableBase) {
            case CLASSIC_AVAILABLE_OFFSET -> this.available.get(getEntryIndex(tableOffset, false));
            case CLASSIC_RESEARCHED_OFFSET -> this.researched.get(getEntryIndex(tableOffset, false));
            case LEGACY_RESEARCHING_OFFSET -> this.legacyResearching.get(getBitSetIndex(tableOffset, LEGACY_RESEARCHING_BYTES));
            case EXPANSION_AVAILABLE_OFFSET -> this.available.get(getEntryIndex(tableOffset, true));
            case EXPANSION_RESEARCHED_OFFSET -> this.researched.get(getEntryIndex(tableOffset, true));
            default -> this.researching.get(getBitSetIndex(tableOffset, RESEARCHING_BYTES));
        } & BYTE_MASK;
    }

    /**
     * Writes a byte of the technology tables by its game-context offset, as an EUD write of the game-data block does: the byte is stored unchanged,
     * without the setters' write gates.
     *
     * @param contextOffset a game-context offset accepted by {@link #hasContextOffset(int)}
     * @param value         the byte value
     */
    @Override
    public void writeContextByte(int contextOffset, int value) {
        int tableBase = getTableBase(contextOffset);
        int tableOffset = contextOffset - tableBase;

        switch (tableBase) {
            case CLASSIC_AVAILABLE_OFFSET -> this.storeAvailableByte(getEntryIndex(tableOffset, false), value);
            case CLASSIC_RESEARCHED_OFFSET -> this.storeResearchedByte(getEntryIndex(tableOffset, false), value);
            case LEGACY_RESEARCHING_OFFSET -> this.legacyResearching.set(getBitSetIndex(tableOffset, LEGACY_RESEARCHING_BYTES), (byte) value);
            case EXPANSION_AVAILABLE_OFFSET -> this.storeAvailableByte(getEntryIndex(tableOffset, true), value);
            case EXPANSION_RESEARCHED_OFFSET -> this.storeResearchedByte(getEntryIndex(tableOffset, true), value);
            default -> this.researching.set(getBitSetIndex(tableOffset, RESEARCHING_BYTES), (byte) value);
        }
    }

    /**
     * Returns a DWORD by its native linear index.
     * <p>
     * The table starts at the classic availability bytes ({@link #CLASSIC_AVAILABLE_OFFSET}); the DWORD is the four bytes at the index,
     * little-endian.
     *
     * @param index the DWORD index from the start of the table
     * @return the DWORD
     */
    @Override
    public int getByNativeIndex(int index) {
        int contextOffset = CLASSIC_AVAILABLE_OFFSET + index * Integer.BYTES;
        int value = 0;

        for (int byteIndex = 0; byteIndex < Integer.BYTES; byteIndex++) {
            value |= this.readContextByte(contextOffset + byteIndex) << byteIndex * Byte.SIZE;
        }

        return value;
    }

    /**
     * Sets a DWORD by its native linear index.
     * <p>
     * The table starts at the classic availability bytes ({@link #CLASSIC_AVAILABLE_OFFSET}); the DWORD is stored as the four bytes at the index,
     * little-endian and unchanged, without the setters' write gates.
     *
     * @param index the DWORD index from the start of the table
     * @param value the new DWORD
     */
    @Override
    public void setByNativeIndex(int index, int value) {
        int contextOffset = CLASSIC_AVAILABLE_OFFSET + index * Integer.BYTES;

        for (int byteIndex = 0; byteIndex < Integer.BYTES; byteIndex++) {
            this.writeContextByte(contextOffset + byteIndex, value >>> byteIndex * Byte.SIZE);
        }
    }

    /**
     * Stores a researched byte after checking the expansion write gate.
     *
     * @param playerIndex the original player DWORD, without truncation
     * @param technology  the original technology WORD
     * @param value       the researched byte
     * @throws UnsupportedOperationException if the expansion gate keeps the write and the addressed byte lies past the game context, or outside the
     *                                       technology tables without a game context
     */
    private void storeResearched(int playerIndex, int technology, int value) {
        technology &= WORD_MASK;

        if (technology >= CLASSIC_TECHNOLOGY_COUNT && !this.expansion.isEnabled()) {
            return;
        }

        if (isTableEntry(playerIndex, technology)) {
            this.storeResearchedByte(playerIndex * TechdataDat.TECH_COUNT + technology, value);
        } else {
            this.contextAccess.writeByte(getTableOffset(CLASSIC_RESEARCHED_OFFSET, EXPANSION_RESEARCHED_OFFSET, playerIndex, technology), value);
        }
    }

    /**
     * Stores an availability byte and whether it is nonzero, the engine availability of the technology.
     *
     * @param index the column index, {@code player * TECH_COUNT + technology}
     * @param value the byte in the low eight bits
     */
    private void storeAvailableByte(int index, int value) {
        this.available.set(index, (byte) value);
        this.progress.setTechAvailable(index / TechdataDat.TECH_COUNT, index % TechdataDat.TECH_COUNT, (byte) value != 0);
    }

    /**
     * Stores a researched byte and whether it is nonzero, the engine researched state of the technology.
     *
     * @param index the column index, {@code player * TECH_COUNT + technology}
     * @param value the byte in the low eight bits
     */
    private void storeResearchedByte(int index, int value) {
        this.researched.set(index, (byte) value);
        this.progress.setTechResearched(index / TechdataDat.TECH_COUNT, index % TechdataDat.TECH_COUNT, (byte) value != 0);
    }
}
