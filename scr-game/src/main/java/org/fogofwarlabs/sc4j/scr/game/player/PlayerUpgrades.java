package org.fogofwarlabs.sc4j.scr.game.player;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.registry.TableKind;
import org.fogofwarlabs.sc4j.engine.content.table.PlayerProgressTable;
import org.fogofwarlabs.sc4j.engine.entity.column.ByteColumn;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UpgradesDat;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUpgradeTypeView;
import org.fogofwarlabs.sc4j.scr.game.state.ContextExpansion;
import org.fogofwarlabs.sc4j.scr.game.state.GameContextBytes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Serves player upgrade levels, maximum levels and research flags in the native game-context layout. The levels and maximum levels live in
 * the engine {@link PlayerProgressTable}, the upgrading bits and the legacy classic upgrading bits in its SC:R extension columns
 * ({@link PlayerProgressColumns}); this view holds no value of its own.
 * <p>
 * Layout of the game context (the EUD game-data block has the same offsets): classic maximum levels and levels, one byte per player and classic
 * upgrade; the legacy classic upgrading bits, only copied to the upgrading bits when an old structure version is loaded; expansion maximum levels and
 * levels, one byte per player and expansion upgrade; the upgrading bits of all upgrades, one bit per player and upgrade.
 * <p>
 * The level, maximum level and upgrading accessors bound neither the player nor the upgrade: a player past the player slots (an owner byte that EUD
 * wrote) or an upgrade past the upgrade table addresses another game-context byte by the same layout, which this storage or its game-context view
 * serves; a write there stores that byte.
 * <p>
 * Initial zeroes follow the fresh context clear. The caller supplies the established context expansion mode and selects the actual CHK handler.
 * Constructing this storage does not establish world readiness.
 */
public final class PlayerUpgrades implements GameContextBytes {
    /**
     * Context offset of the classic maximum levels.
     */
    public static final int CLASSIC_MAXIMUM_OFFSET = 0xDF98;
    /**
     * Context offset of the classic levels.
     */
    public static final int CLASSIC_LEVEL_OFFSET = 0xE1C0;
    /**
     * Context offset of the legacy classic upgrading bits.
     */
    public static final int LEGACY_UPGRADING_OFFSET = 0xE3E8;
    /**
     * Context offset of the expansion maximum levels (the first expansion upgrade of player {@code 0}).
     */
    public static final int EXPANSION_MAXIMUM_OFFSET = 0x10188;
    /**
     * Context offset of the expansion levels (the first expansion upgrade of player {@code 0}).
     */
    public static final int EXPANSION_LEVEL_OFFSET = 0x1023C;
    /**
     * Context offset of the upgrading bits.
     */
    public static final int UPGRADING_OFFSET = 0x102F0;
    /**
     * The number of original (classic) upgrades, the upgrades a map's {@code UPGR} section covers.
     */
    public static final int CLASSIC_UPGRADE_COUNT = 46;

    /**
     * The number of expansion upgrades, the upgrades after the classic ones.
     */
    private static final int EXPANSION_UPGRADE_COUNT = UpgradesDat.UPGRADE_COUNT - CLASSIC_UPGRADE_COUNT;
    /**
     * The bytes of one player's legacy classic upgrading bits.
     */
    private static final int LEGACY_UPGRADING_BYTES = 6;
    /**
     * The bytes of one player's upgrading bits.
     */
    private static final int UPGRADING_BYTES = 8;
    /**
     * The mask of the upgrade WORD a level or maximum level access truncates an upgrade identifier to.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The mask of the upgrade BYTE an upgrading bit access truncates an upgrade identifier to, and of a level byte.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The multiplier that folds the expansion write gate, each level and each maximum level into the state hash.
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * Checks whether a game-context offset lies in the upgrade tables.
     *
     * @param contextOffset a game-context offset
     * @return {@code true} if the offset lies in one of the upgrade tables of this storage, {@code false} otherwise
     */
    public static boolean hasContextOffset(int contextOffset) {
        return contextOffset >= CLASSIC_MAXIMUM_OFFSET &&
               contextOffset < LEGACY_UPGRADING_OFFSET + PlayerTable.PLAYER_SLOTS * LEGACY_UPGRADING_BYTES ||
               contextOffset >= EXPANSION_MAXIMUM_OFFSET &&
               contextOffset < UPGRADING_OFFSET + PlayerTable.PLAYER_SLOTS * UPGRADING_BYTES;
    }

    /**
     * Checks that a context offset lies in the upgrade tables.
     *
     * @param contextOffset the upgrade context offset
     * @throws IllegalArgumentException if the offset lies outside the upgrade tables
     */
    private static void requireContextOffset(int contextOffset) {
        if (!hasContextOffset(contextOffset)) {
            throw Exceptions.newIllegalArgumentException("Context offset {0} is not an upgrade table.", String.format("0x%X", contextOffset));
        }
    }

    /**
     * Returns the index of a byte of a player's bit set in an upgrade extension column: byte {@code byteIndex} of player {@code player} is the
     * entry {@code player * UPGRADE_COUNT + byteIndex}.
     *
     * @param tableOffset the offset in the native bit set table, {@code player * bytes + byteIndex}
     * @param bytes       the bytes of one player's bit set
     * @return the column index
     */
    private static int getBitSetIndex(int tableOffset, int bytes) {
        return tableOffset / bytes * UpgradesDat.UPGRADE_COUNT + tableOffset % bytes;
    }

    /**
     * Returns the game-context offset of a level or maximum level byte by the upgrade table layout.
     *
     * @param classicOffset     the game-context offset of the classic table
     * @param expansionOffset   the game-context offset of the expansion table
     * @param playerIndex       the player DWORD, unsigned
     * @param upgradeIdentifier the upgrade WORD
     * @return the unsigned game-context offset
     */
    private static long getTableOffset(int classicOffset, int expansionOffset, int playerIndex, int upgradeIdentifier) {
        return ContextTableAccess.getEntryOffset(classicOffset, CLASSIC_UPGRADE_COUNT, expansionOffset, EXPANSION_UPGRADE_COUNT,
                                                 playerIndex, upgradeIdentifier);
    }

    /**
     * Checks whether a player and truncated upgrade identifier address an entry of the retained tables.
     *
     * @param playerIndex       the player DWORD
     * @param upgradeIdentifier the truncated upgrade identifier
     * @return {@code true} if the player lies in the player slots and the upgrade in the upgrade table, {@code false} otherwise
     */
    private static boolean isTableEntry(int playerIndex, int upgradeIdentifier) {
        return playerIndex >= 0 && playerIndex < PlayerTable.PLAYER_SLOTS && upgradeIdentifier < UpgradesDat.UPGRADE_COUNT;
    }

    /**
     * The expansion BYTE of the game context, shared with {@link PlayerTechnology}.
     */
    private final @NotNull ContextExpansion expansion;
    /**
     * Accesses the game-context bytes that an entry past the upgrade tables addresses.
     */
    private final @NotNull ContextTableAccess contextAccess;

    /**
     * The table that stores the levels and maximum levels.
     */
    private final @NotNull PlayerProgressTable progress;
    private final @NotNull ByteColumn upgrading;
    private final @NotNull ByteColumn legacyUpgrading;

    /**
     * Creates a {@link PlayerUpgrades} instance over a progress table, a shared expansion BYTE and the view of the other game-context bytes, and
     * clears the levels, maximum levels and upgrading bits of the table, as the fresh context clear does.
     *
     * @param progress  the progress table of the game, with the SC:R extension columns ({@link PlayerProgressColumns})
     * @param expansion the expansion BYTE of the game context, read live
     * @param context   supplies the view of the whole game context as the world has it at the access, which serves the bytes outside the upgrade
     *                  tables, or {@code null} when no game context exists
     */
    public PlayerUpgrades(@NotNull PlayerProgressTable progress, @NotNull ContextExpansion expansion, @Nullable Supplier<GameContextBytes> context) {
        this.expansion     = expansion;
        this.contextAccess = new ContextTableAccess(this, PlayerUpgrades::hasContextOffset, context);

        this.progress        = progress;
        this.upgrading       = progress.getExtensionColumns(TableKind.UPGRADE).get(PlayerProgressColumns.UPGRADING);
        this.legacyUpgrading = progress.getExtensionColumns(TableKind.UPGRADE).get(PlayerProgressColumns.LEGACY_UPGRADING);

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYER_SLOTS; playerIndex++) {
            for (int upgradeIdentifier = 0; upgradeIdentifier < UpgradesDat.UPGRADE_COUNT; upgradeIdentifier++) {
                int index = playerIndex * UpgradesDat.UPGRADE_COUNT + upgradeIdentifier;

                progress.setUpgradeLevel(playerIndex, upgradeIdentifier, 0);
                progress.setMaximumUpgradeLevel(playerIndex, upgradeIdentifier, 0);
                this.upgrading.set(index, (byte) 0);
                this.legacyUpgrading.set(index, (byte) 0);
            }
        }
    }

    /**
     * @return the expansion BYTE of the game context
     */
    public @NotNull ContextExpansion getExpansion() {
        return this.expansion;
    }

    /**
     * @return {@code true} if the expansion BYTE of the game context is nonzero, {@code false} otherwise
     */
    public boolean isExpansionEnabled() {
        return this.expansion.isEnabled();
    }

    /**
     * Returns an upgrade level. A player outside the player slots or an upgrade WORD outside the upgrade table reads the game-context byte the level
     * layout addresses, from this storage or through the game-context view.
     *
     * @param playerIndex       the original player DWORD, without truncation
     * @param upgradeIdentifier the original upgrade WORD
     * @return the unsigned current level byte
     * @throws UnsupportedOperationException if the addressed byte lies past the game context, or outside the upgrade tables without a game context
     */
    public int getLevel(int playerIndex, int upgradeIdentifier) {
        upgradeIdentifier &= WORD_MASK;

        if (isTableEntry(playerIndex, upgradeIdentifier)) {
            return this.progress.getUpgradeLevel(playerIndex, upgradeIdentifier);
        }

        return this.contextAccess.readByte(getTableOffset(CLASSIC_LEVEL_OFFSET, EXPANSION_LEVEL_OFFSET, playerIndex, upgradeIdentifier));
    }

    /**
     * Sets an upgrade level, applying its expansion-only write gate. A player outside the player slots or an upgrade WORD outside the upgrade table
     * writes the game-context byte the level layout addresses, in this storage or through the game-context view.
     *
     * @param playerIndex       the original player DWORD, without truncation
     * @param upgradeIdentifier the original upgrade WORD
     * @param level             the new level, truncated to a byte without clamping
     * @throws UnsupportedOperationException if the expansion gate keeps the write and the addressed byte lies past the game context, or outside the
     *                                       upgrade tables without a game context
     */
    public void setLevel(int playerIndex, int upgradeIdentifier, int level) {
        upgradeIdentifier &= WORD_MASK;

        if (upgradeIdentifier >= CLASSIC_UPGRADE_COUNT && !this.expansion.isEnabled()) {
            return;
        }

        if (isTableEntry(playerIndex, upgradeIdentifier)) {
            this.progress.setUpgradeLevel(playerIndex, upgradeIdentifier, level & BYTE_MASK);
        } else {
            this.contextAccess.writeByte(getTableOffset(CLASSIC_LEVEL_OFFSET, EXPANSION_LEVEL_OFFSET, playerIndex, upgradeIdentifier), level);
        }
    }

    /**
     * Returns a maximum upgrade level. A player outside the player slots or an upgrade WORD outside the upgrade table reads the game-context byte the
     * maximum level layout addresses, from this storage or through the game-context view.
     *
     * @param playerIndex       the original player DWORD, without truncation
     * @param upgradeIdentifier the original upgrade WORD
     * @return the unsigned maximum level byte
     * @throws UnsupportedOperationException if the addressed byte lies past the game context, or outside the upgrade tables without a game context
     */
    public int getMaximumLevel(int playerIndex, int upgradeIdentifier) {
        upgradeIdentifier &= WORD_MASK;

        if (isTableEntry(playerIndex, upgradeIdentifier)) {
            return this.progress.getMaximumUpgradeLevel(playerIndex, upgradeIdentifier);
        }

        return this.contextAccess.readByte(getTableOffset(CLASSIC_MAXIMUM_OFFSET, EXPANSION_MAXIMUM_OFFSET, playerIndex, upgradeIdentifier));
    }

    /**
     * Sets a maximum upgrade level as an EUD write of the game-data block stores it, without an expansion gate. A player outside the player slots or
     * an upgrade WORD outside the upgrade table writes the game-context byte the maximum level layout addresses, in this storage or through the
     * game-context view.
     *
     * @param playerIndex       the original player DWORD, without truncation
     * @param upgradeIdentifier the original upgrade WORD
     * @param level             the new maximum level, truncated to a byte without clamping
     * @throws UnsupportedOperationException if the addressed byte lies past the game context, or outside the upgrade tables without a game context
     */
    public void setMaximumLevel(int playerIndex, int upgradeIdentifier, int level) {
        upgradeIdentifier &= WORD_MASK;

        if (isTableEntry(playerIndex, upgradeIdentifier)) {
            this.progress.setMaximumUpgradeLevel(playerIndex, upgradeIdentifier, level & BYTE_MASK);
        } else {
            this.contextAccess.writeByte(getTableOffset(CLASSIC_MAXIMUM_OFFSET, EXPANSION_MAXIMUM_OFFSET, playerIndex, upgradeIdentifier), level);
        }
    }

    /**
     * Tells whether a building of the player is researching the upgrade. A player outside the player slots or an upgrade BYTE outside the upgrade
     * table tests the bit of the game-context byte the upgrading bit layout addresses.
     *
     * @param playerIndex       the original player DWORD, without truncation
     * @param upgradeIdentifier the upgrade identifier, truncated to its BYTE
     * @return {@code true} if the upgrading bit is set, {@code false} otherwise
     * @throws UnsupportedOperationException if the addressed byte lies past the game context, or outside the upgrade tables without a game context
     */
    public boolean isUpgrading(int playerIndex, int upgradeIdentifier) {
        upgradeIdentifier &= BYTE_MASK;

        if (isTableEntry(playerIndex, upgradeIdentifier)) {
            return (this.upgrading.get(playerIndex * UpgradesDat.UPGRADE_COUNT + (upgradeIdentifier >> PlayerTable.BIT_SET_BYTE_SHIFT)) >>
                    (upgradeIdentifier & PlayerTable.BIT_SET_BIT_MASK) & 1) != 0;
        }

        return this.contextAccess.isBitSet(ContextTableAccess.getBitByteOffset(UPGRADING_OFFSET, UPGRADING_BYTES, playerIndex, upgradeIdentifier),
                                           upgradeIdentifier);
    }

    /**
     * Sets whether a building of the player is researching the upgrade. A player outside the player slots or an upgrade BYTE outside the upgrade
     * table sets or clears the bit of the game-context byte the upgrading bit layout addresses.
     *
     * @param playerIndex       the original player DWORD, without truncation
     * @param upgradeIdentifier the upgrade identifier, truncated to its BYTE
     * @param upgrading         whether the upgrade is in progress
     * @throws UnsupportedOperationException if the addressed byte lies past the game context, or outside the upgrade tables without a game context
     */
    public void setUpgrading(int playerIndex, int upgradeIdentifier, boolean upgrading) {
        upgradeIdentifier &= BYTE_MASK;

        if (!isTableEntry(playerIndex, upgradeIdentifier)) {
            this.contextAccess.setBit(ContextTableAccess.getBitByteOffset(UPGRADING_OFFSET, UPGRADING_BYTES, playerIndex, upgradeIdentifier),
                                      upgradeIdentifier,
                                      upgrading);

            return;
        }

        int index = playerIndex * UpgradesDat.UPGRADE_COUNT + (upgradeIdentifier >> PlayerTable.BIT_SET_BYTE_SHIFT);
        int bit = 1 << (upgradeIdentifier & PlayerTable.BIT_SET_BIT_MASK);
        byte bits = this.upgrading.get(index);

        this.upgrading.set(index, (byte) (upgrading ? bits | bit : bits & ~bit));
    }


    /**
     * Applies the upgrade-storage effects of the fresh default branch: the levels and upgrading bits are cleared and every player's maximum levels
     * come from {@code upgrades.dat}.
     *
     * @param upgrades the loaded upgrade type table, before map-specific cost overrides
     */
    public void initializeDefaults(@NotNull ScrUpgradeTypeView upgrades) {
        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYER_SLOTS; playerIndex++) {
            for (int upgradeIdentifier = 0; upgradeIdentifier < UpgradesDat.UPGRADE_COUNT; upgradeIdentifier++) {
                this.progress.setUpgradeLevel(playerIndex, upgradeIdentifier, 0);
                this.progress.setMaximumUpgradeLevel(playerIndex, upgradeIdentifier, upgrades.getMaximumLevel(upgradeIdentifier) & BYTE_MASK);
            }

            for (int byteIndex = 0; byteIndex < UPGRADING_BYTES; byteIndex++) {
                this.upgrading.set(playerIndex * UpgradesDat.UPGRADE_COUNT + byteIndex, (byte) 0);
            }
        }
    }

    /**
     * Applies the supplied {@code UPGR} or {@code PUPx} payload through the original descending loops.
     * <p>
     * This models handler storage effects after a successful stream read. It does not select a descriptor table or retain the native diagnostic code
     * on failure.
     *
     * @param payload          the complete section payload
     * @param expansionSection whether the selected handler is {@code PUPx} rather than {@code UPGR}
     * @param loadingAllowed   the established result of the native map-settings test
     * @return {@code true} if the handler accepts the supplied payload, {@code false} otherwise
     */
    public boolean applySection(byte @NotNull [] payload, boolean expansionSection, boolean loadingAllowed) {
        int upgradeCount = expansionSection ? UpgradesDat.UPGRADE_COUNT : CLASSIC_UPGRADE_COUNT;

        if (!loadingAllowed || payload.length != upgradeCount * PlayerTable.SETTINGS_ROWS) {
            return false;
        }

        for (int playerIndex = PlayerTable.PLAYER_SLOTS - 1; playerIndex >= 0; playerIndex--) {
            for (int upgradeIdentifier = upgradeCount - 1; upgradeIdentifier >= 0; upgradeIdentifier--) {
                int sourceIndex = playerIndex * upgradeCount + upgradeIdentifier;
                boolean useDefault = payload[PlayerTable.SETTINGS_USE_DEFAULT_ROW * upgradeCount + sourceIndex] != 0;
                int level = payload[useDefault ? PlayerTable.SETTINGS_DEFAULT_SECOND_ROW * upgradeCount + upgradeIdentifier
                                               : PlayerTable.SETTINGS_PLAYER_SECOND_ROW * upgradeCount + sourceIndex];
                int maximumLevel = payload[useDefault ? PlayerTable.SETTINGS_DEFAULT_FIRST_ROW * upgradeCount + upgradeIdentifier : sourceIndex];

                this.setLevel(playerIndex, upgradeIdentifier, level);
                this.progress.setMaximumUpgradeLevel(playerIndex, upgradeIdentifier, maximumLevel & BYTE_MASK);
            }
        }

        return true;
    }

    /**
     * Reads a byte of the upgrade tables by its game-context offset, as an EUD read of the game-data block does.
     *
     * @param contextOffset a game-context offset accepted by {@link #hasContextOffset(int)}
     * @return the unsigned byte
     */
    @Override
    public int readContextByte(int contextOffset) {
        requireContextOffset(contextOffset);

        if (contextOffset < CLASSIC_LEVEL_OFFSET) {
            int offset = contextOffset - CLASSIC_MAXIMUM_OFFSET;

            return this.progress.getMaximumUpgradeLevel(offset / CLASSIC_UPGRADE_COUNT, offset % CLASSIC_UPGRADE_COUNT);
        }

        if (contextOffset < LEGACY_UPGRADING_OFFSET) {
            int offset = contextOffset - CLASSIC_LEVEL_OFFSET;

            return this.progress.getUpgradeLevel(offset / CLASSIC_UPGRADE_COUNT, offset % CLASSIC_UPGRADE_COUNT);
        }

        if (contextOffset < EXPANSION_MAXIMUM_OFFSET) {
            return Byte.toUnsignedInt(this.legacyUpgrading.get(getBitSetIndex(contextOffset - LEGACY_UPGRADING_OFFSET, LEGACY_UPGRADING_BYTES)));
        }

        if (contextOffset < EXPANSION_LEVEL_OFFSET) {
            int offset = contextOffset - EXPANSION_MAXIMUM_OFFSET;

            return this.progress.getMaximumUpgradeLevel(offset / EXPANSION_UPGRADE_COUNT, CLASSIC_UPGRADE_COUNT + offset % EXPANSION_UPGRADE_COUNT);
        }

        if (contextOffset < UPGRADING_OFFSET) {
            int offset = contextOffset - EXPANSION_LEVEL_OFFSET;

            return this.progress.getUpgradeLevel(offset / EXPANSION_UPGRADE_COUNT, CLASSIC_UPGRADE_COUNT + offset % EXPANSION_UPGRADE_COUNT);
        }

        return Byte.toUnsignedInt(this.upgrading.get(getBitSetIndex(contextOffset - UPGRADING_OFFSET, UPGRADING_BYTES)));
    }

    /**
     * Writes a byte of the upgrade tables by its game-context offset, as an EUD write of the game-data block does: the byte is stored unchanged,
     * without the level setter's expansion gate.
     *
     * @param contextOffset a game-context offset accepted by {@link #hasContextOffset(int)}
     * @param value         the byte value
     */
    @Override
    public void writeContextByte(int contextOffset, int value) {
        requireContextOffset(contextOffset);

        if (contextOffset < CLASSIC_LEVEL_OFFSET) {
            int offset = contextOffset - CLASSIC_MAXIMUM_OFFSET;

            this.progress.setMaximumUpgradeLevel(offset / CLASSIC_UPGRADE_COUNT, offset % CLASSIC_UPGRADE_COUNT, value & BYTE_MASK);
        } else if (contextOffset < LEGACY_UPGRADING_OFFSET) {
            int offset = contextOffset - CLASSIC_LEVEL_OFFSET;

            this.progress.setUpgradeLevel(offset / CLASSIC_UPGRADE_COUNT, offset % CLASSIC_UPGRADE_COUNT, value & BYTE_MASK);
        } else if (contextOffset < EXPANSION_MAXIMUM_OFFSET) {
            this.legacyUpgrading.set(getBitSetIndex(contextOffset - LEGACY_UPGRADING_OFFSET, LEGACY_UPGRADING_BYTES), (byte) value);
        } else if (contextOffset < EXPANSION_LEVEL_OFFSET) {
            int offset = contextOffset - EXPANSION_MAXIMUM_OFFSET;

            this.progress.setMaximumUpgradeLevel(offset / EXPANSION_UPGRADE_COUNT, CLASSIC_UPGRADE_COUNT + offset % EXPANSION_UPGRADE_COUNT,
                                                 value & BYTE_MASK);
        } else if (contextOffset < UPGRADING_OFFSET) {
            int offset = contextOffset - EXPANSION_LEVEL_OFFSET;

            this.progress.setUpgradeLevel(offset / EXPANSION_UPGRADE_COUNT, CLASSIC_UPGRADE_COUNT + offset % EXPANSION_UPGRADE_COUNT,
                                          value & BYTE_MASK);
        } else {
            this.upgrading.set(getBitSetIndex(contextOffset - UPGRADING_OFFSET, UPGRADING_BYTES), (byte) value);
        }
    }

    /**
     * Folds the expansion write gate, the levels and the maximum levels into a state hash.
     *
     * @param hash the current state hash
     * @return the hash including the expansion write gate, the levels and the maximum levels
     */
    public int computeStateHash(int hash) {
        hash = hash * HASH_MULTIPLIER + (this.expansion.isEnabled() ? 1 : 0);

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYER_SLOTS; playerIndex++) {
            for (int upgradeIdentifier = 0; upgradeIdentifier < UpgradesDat.UPGRADE_COUNT; upgradeIdentifier++) {
                hash = hash * HASH_MULTIPLIER + this.progress.getUpgradeLevel(playerIndex, upgradeIdentifier);
            }
        }

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYER_SLOTS; playerIndex++) {
            for (int upgradeIdentifier = 0; upgradeIdentifier < UpgradesDat.UPGRADE_COUNT; upgradeIdentifier++) {
                hash = hash * HASH_MULTIPLIER + this.progress.getMaximumUpgradeLevel(playerIndex, upgradeIdentifier);
            }
        }

        return hash;
    }
}
