package org.fogofwarlabs.sc4j.scr.game.player;

import org.fogofwarlabs.sc4j.scr.content.ScrUpgradeIds;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UpgradesDat;
import org.fogofwarlabs.sc4j.scr.game.state.ContextExpansion;
import org.fogofwarlabs.sc4j.scr.game.state.GameContext;
import org.fogofwarlabs.sc4j.scr.game.state.GameContextBytes;
import org.fogofwarlabs.sc4j.scr.game.test.PlayerProgressTestUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Checks that player upgrade levels and maximum levels keep their byte width and take the {@code upgrades.dat} and upgrade section values, and that
 * a level, maximum level or upgrading bit access past the tables reads or writes the game-context byte it addresses.
 */
final class PlayerUpgradesTest {
    /**
     * The default level of upgrade {@code 0} in a created payload, decreasing with the upgrade identifier.
     */
    private static final int DEFAULT_LEVEL_BASE = 255;
    /**
     * The player level of the first player value in a created payload, increasing with the value index.
     */
    private static final int PLAYER_LEVEL_BASE = 180;
    /**
     * The period of the player values of a created payload that do not use the default.
     */
    private static final int OWN_VALUE_PERIOD = 3;
    /**
     * The use-default byte of a player value that uses the default.
     */
    private static final byte USE_DEFAULT = (byte) 0x80;
    /**
     * The use-default byte of a player value that uses its own value.
     */
    private static final byte OWN_VALUE = 0;
    /**
     * The upgrade count of a classic upgrade section, whose levels classic storage keeps.
     */
    private static final int CLASSIC_UPGRADE_COUNT = 46;
    /**
     * The byte length of an {@code upgrades.dat} image.
     */
    private static final int UPGRADES_DAT_SIZE = 1281;
    /**
     * The {@code upgrades.dat} image offset of the maximum level bytes.
     */
    private static final int MAXIMUM_LEVEL_COLUMN_OFFSET = 1159;
    /**
     * The maximum level step between neighboring upgrades of the {@code upgrades.dat} image.
     */
    private static final int MAXIMUM_LEVEL_STEP = 17;
    /**
     * The largest level byte.
     */
    private static final int MAXIMUM_LEVEL = 255;
    /**
     * The mask of a level byte.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * A level wider than the level byte.
     */
    private static final int WIDE_LEVEL = 0x1FF;
    /**
     * The range of a WORD upgrade identifier, by which an identifier wraps.
     */
    private static final int IDENTIFIER_RANGE = 0x10000;
    /**
     * An upgrade identifier outside the upgrades.
     */
    private static final int OUTSIDE_IDENTIFIER = 0xFFFF;
    /**
     * A player index outside the player slots.
     */
    private static final int OUTSIDE_PLAYER = 0x100;
    /**
     * The level of the ignored classic write.
     */
    private static final int IGNORED_LEVEL = 5;
    /**
     * A negative level, whose low byte is the largest level byte.
     */
    private static final int NEGATIVE_LEVEL = -1;
    /**
     * A negative player index.
     */
    private static final int NEGATIVE_PLAYER = -1;
    /**
     * The level of the rejected expansion write.
     */
    private static final int REJECTED_LEVEL = 1;
    /**
     * The first upgrade identifier, which {@link #IDENTIFIER_RANGE} wraps to.
     */
    private static final int FIRST_UPGRADE = 0;
    /**
     * The first player slot.
     */
    private static final int FIRST_PLAYER = 0;
    /**
     * The default maximum level of {@link ScrUpgradeIds#UNUSED_60} in a created payload.
     */
    private static final int UNUSED_60_DEFAULT_MAXIMUM = 60;
    /**
     * The player whose level is set before the rejected sections.
     */
    private static final int CHANGED_PLAYER = 4;
    /**
     * The level that is set before the rejected sections.
     */
    private static final int CHANGED_LEVEL = 250;
    /**
     * The initial state hash of the hash comparison.
     */
    private static final int INITIAL_HASH = 1;
    /**
     * The first player past the player slots, whose classic levels lie on the legacy upgrading bits of player {@code 0}.
     */
    private static final int PAST_PLAYER = 12;
    /**
     * The game-context offset of the legacy upgrading bits of player {@code 0}.
     */
    private static final int LEGACY_UPGRADING_OFFSET = 0xE3E8;
    /**
     * The classic upgrade whose level byte {@link #PAST_PLAYER} reads.
     */
    private static final int PAST_PLAYER_UPGRADE = 5;
    /**
     * The second player slot, whose first expansion level the upgrade past the table reads for player {@code 0}.
     */
    private static final int SECOND_PLAYER = 1;
    /**
     * The first expansion upgrade.
     */
    private static final int FIRST_EXPANSION_UPGRADE = 46;
    /**
     * The first upgrade WORD past the upgrade table.
     */
    private static final int PAST_UPGRADE = 61;
    /**
     * A level written into the context bytes a level read past the tables reaches.
     */
    private static final int WRITTEN_LEVEL = 7;
    /**
     * A player whose classic level bytes lie outside the upgrade tables, and the game-context offset of its first one.
     */
    private static final int FORCES_PLAYER = 14;
    private static final int FORCES_PLAYER_OFFSET = 0xE444;
    /**
     * A player whose expansion level bytes lie in the game-context tail, and the game-context offset of its first one.
     */
    private static final int TAIL_PLAYER = 20;
    private static final int TAIL_PLAYER_OFFSET = 0x10368;
    /**
     * A player whose level bytes lie past the game context.
     */
    private static final int CONTEXT_END_PLAYER = 0x1000;
    /**
     * The byte the test game context serves.
     */
    private static final int CONTEXT_BYTE = 0x5A;
    /**
     * A player whose classic maximum level bytes lie outside the upgrade tables, from {@link #FORCES_PLAYER_OFFSET} on.
     */
    private static final int FORCES_MAXIMUM_PLAYER = 26;
    /**
     * The game-context offset of the expansion BYTE, on which the upgrading bits of upgrades {@code 0..7} of player {@code 12} lie.
     */
    private static final int EXPANSION_OFFSET = 0x10350;
    /**
     * The expansion BYTE of a Brood War game.
     */
    private static final int BROOD_WAR = 1;
    /**
     * An upgrade whose upgrading bit is the second bit of its bit byte.
     */
    private static final int SECOND_BIT_UPGRADE = 1;
    /**
     * The expansion BYTE once the upgrading bit of {@link #SECOND_BIT_UPGRADE} of player {@code 12} is set on a cleared Brood War BYTE.
     */
    private static final int SECOND_BIT = 2;
    /**
     * The range of a BYTE upgrade identifier, by which an upgrading bit identifier wraps.
     */
    private static final int BYTE_IDENTIFIER_RANGE = 0x100;

    /**
     * Serves a whole game context from a byte array and records the last offset it served.
     */
    private static final class RecordingContext implements GameContextBytes {
        private final byte @NotNull [] contents;

        private int lastOffset;

        private RecordingContext() {
            this.contents = new byte[GameContext.CONTEXT_SIZE];

            this.lastOffset = -1;
        }

        private int getLastOffset() {
            return this.lastOffset;
        }

        /**
         * Reads one byte of the game context.
         *
         * @param contextOffset a game-context offset this instance serves
         * @return the unsigned byte
         */
        @Override
        public int readContextByte(int contextOffset) {
            this.lastOffset = contextOffset;

            return Byte.toUnsignedInt(this.contents[contextOffset]);
        }

        /**
         * Writes one byte of the game context.
         *
         * @param contextOffset a game-context offset this instance serves
         * @param value         the byte in the low eight bits
         */
        @Override
        public void writeContextByte(int contextOffset, int value) {
            this.lastOffset = contextOffset;

            this.contents[contextOffset] = (byte) value;
        }
    }

    private static byte @NotNull [] createPayload(int upgradeCount) {
        byte[] payload = new byte[upgradeCount * PlayerTable.SETTINGS_ROWS];

        for (int upgradeIdentifier = 0; upgradeIdentifier < upgradeCount; upgradeIdentifier++) {
            payload[PlayerTable.SETTINGS_DEFAULT_FIRST_ROW * upgradeCount + upgradeIdentifier]  = (byte) upgradeIdentifier;
            payload[PlayerTable.SETTINGS_DEFAULT_SECOND_ROW * upgradeCount + upgradeIdentifier] = (byte) (DEFAULT_LEVEL_BASE - upgradeIdentifier);
        }

        for (int sourceIndex = 0; sourceIndex < PlayerTable.PLAYER_SLOTS * upgradeCount; sourceIndex++) {
            byte useDefaultByte = sourceIndex % OWN_VALUE_PERIOD == 0 ? OWN_VALUE : USE_DEFAULT;

            payload[sourceIndex]                                                         = (byte) sourceIndex;
            payload[PlayerTable.SETTINGS_PLAYER_SECOND_ROW * upgradeCount + sourceIndex] = (byte) (PLAYER_LEVEL_BASE + sourceIndex);
            payload[PlayerTable.SETTINGS_USE_DEFAULT_ROW * upgradeCount + sourceIndex]   = useDefaultByte;
        }

        return payload;
    }


    @Test
    void initialize_defaults_should_reset_levels_and_copy_every_player_maximum_from_dat() throws IOException {
        PlayerUpgrades upgrades = PlayerProgressTestUtils.createPlayerUpgrades(true);
        byte[] data = new byte[UPGRADES_DAT_SIZE];

        for (int upgradeIdentifier = 0; upgradeIdentifier < UpgradesDat.UPGRADE_COUNT; upgradeIdentifier++) {
            data[MAXIMUM_LEVEL_COLUMN_OFFSET + upgradeIdentifier] = (byte) (upgradeIdentifier * MAXIMUM_LEVEL_STEP);
            upgrades.setLevel(PlayerTable.NEUTRAL_PLAYER_ID, upgradeIdentifier, MAXIMUM_LEVEL);
        }

        upgrades.initializeDefaults(UpgradesDat.createTypeTable(UpgradesDat.createSchema().parse(data)));

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYER_SLOTS; playerIndex++) {
            for (int upgradeIdentifier = 0; upgradeIdentifier < UpgradesDat.UPGRADE_COUNT; upgradeIdentifier++) {
                Assertions.assertEquals(0, upgrades.getLevel(playerIndex, upgradeIdentifier));
                Assertions.assertEquals((upgradeIdentifier * MAXIMUM_LEVEL_STEP) & BYTE_MASK,
                                        upgrades.getMaximumLevel(playerIndex, upgradeIdentifier));
            }
        }
    }

    @Test
    void set_level_should_keep_byte_width_and_ignore_expansion_writes_on_classic_storage() {
        PlayerUpgrades classic = PlayerProgressTestUtils.createPlayerUpgrades(false);
        PlayerUpgrades expansion = PlayerProgressTestUtils.createPlayerUpgrades(true);

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYER_SLOTS; playerIndex++) {
            for (int upgradeIdentifier = 0; upgradeIdentifier < UpgradesDat.UPGRADE_COUNT; upgradeIdentifier++) {
                Assertions.assertEquals(0, expansion.getLevel(playerIndex, upgradeIdentifier));

                classic.setLevel(playerIndex, upgradeIdentifier, WIDE_LEVEL);
                expansion.setLevel(playerIndex, upgradeIdentifier + IDENTIFIER_RANGE, WIDE_LEVEL);

                Assertions.assertEquals(upgradeIdentifier < CLASSIC_UPGRADE_COUNT ? MAXIMUM_LEVEL : 0,
                                        classic.getLevel(playerIndex, upgradeIdentifier));
                Assertions.assertEquals(MAXIMUM_LEVEL, expansion.getLevel(playerIndex, upgradeIdentifier));
            }
        }

        expansion.setLevel(PlayerTable.NEUTRAL_PLAYER_ID, IDENTIFIER_RANGE, NEGATIVE_LEVEL);

        Assertions.assertEquals(MAXIMUM_LEVEL, expansion.getLevel(PlayerTable.NEUTRAL_PLAYER_ID, FIRST_UPGRADE));

        classic.setLevel(NEGATIVE_PLAYER, OUTSIDE_IDENTIFIER, IGNORED_LEVEL);

        Assertions.assertThrows(UnsupportedOperationException.class, () -> expansion.setLevel(NEGATIVE_PLAYER, FIRST_UPGRADE, REJECTED_LEVEL));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> expansion.getLevel(OUTSIDE_PLAYER, FIRST_UPGRADE));
    }

    @Test
    void apply_section_should_apply_player_and_default_values_without_clamping_levels() {
        for (int upgradeCount : new int[]{CLASSIC_UPGRADE_COUNT, UpgradesDat.UPGRADE_COUNT}) {
            PlayerUpgrades upgrades = PlayerProgressTestUtils.createPlayerUpgrades(true);
            byte[] payload = createPayload(upgradeCount);

            Assertions.assertTrue(upgrades.applySection(payload, upgradeCount == UpgradesDat.UPGRADE_COUNT, true));

            for (int playerIndex = 0; playerIndex < PlayerTable.PLAYER_SLOTS; playerIndex++) {
                for (int upgradeIdentifier = 0; upgradeIdentifier < upgradeCount; upgradeIdentifier++) {
                    int sourceIndex = playerIndex * upgradeCount + upgradeIdentifier;
                    boolean useDefault = sourceIndex % OWN_VALUE_PERIOD != 0;

                    Assertions.assertEquals((useDefault ? DEFAULT_LEVEL_BASE - upgradeIdentifier : PLAYER_LEVEL_BASE + sourceIndex) & BYTE_MASK,
                                            upgrades.getLevel(playerIndex, upgradeIdentifier));
                    Assertions.assertEquals((useDefault ? upgradeIdentifier : sourceIndex) & BYTE_MASK,
                                            upgrades.getMaximumLevel(playerIndex, upgradeIdentifier));
                }
            }
        }
    }

    @Test
    void apply_section_should_always_write_expansion_maximums_but_honor_current_level_mode() {
        PlayerUpgrades upgrades = PlayerProgressTestUtils.createPlayerUpgrades(false);

        Assertions.assertTrue(upgrades.applySection(createPayload(UpgradesDat.UPGRADE_COUNT), true, true));
        Assertions.assertEquals(0, upgrades.getLevel(PlayerTable.NEUTRAL_PLAYER_ID, ScrUpgradeIds.UNUSED_60));
        Assertions.assertEquals(UNUSED_60_DEFAULT_MAXIMUM, upgrades.getMaximumLevel(PlayerTable.NEUTRAL_PLAYER_ID, ScrUpgradeIds.UNUSED_60));
        Assertions.assertNotEquals(0, upgrades.getLevel(PlayerTable.NEUTRAL_PLAYER_ID, ScrUpgradeIds.UNKNOWN_45));
    }

    @Test
    void apply_section_should_preserve_unaddressed_expansion_values_across_later_sections() {
        PlayerUpgrades upgrades = PlayerProgressTestUtils.createPlayerUpgrades(true);

        Assertions.assertTrue(upgrades.applySection(createPayload(UpgradesDat.UPGRADE_COUNT), true, true));

        int expansionLevel = upgrades.getLevel(PlayerTable.NEUTRAL_PLAYER_ID, ScrUpgradeIds.UNUSED_60);

        Assertions.assertTrue(upgrades.applySection(new byte[CLASSIC_UPGRADE_COUNT * PlayerTable.SETTINGS_ROWS], false, true));
        Assertions.assertEquals(0, upgrades.getLevel(PlayerTable.NEUTRAL_PLAYER_ID, ScrUpgradeIds.UNKNOWN_45));
        Assertions.assertEquals(expansionLevel, upgrades.getLevel(PlayerTable.NEUTRAL_PLAYER_ID, ScrUpgradeIds.UNUSED_60));

        Assertions.assertTrue(upgrades.applySection(createPayload(CLASSIC_UPGRADE_COUNT), false, true));
        Assertions.assertNotEquals(0, upgrades.getLevel(PlayerTable.NEUTRAL_PLAYER_ID, ScrUpgradeIds.UNKNOWN_45));
    }

    @Test
    void apply_section_should_reject_invalid_sizes_and_loading_predicate_before_mutating() {
        PlayerUpgrades upgrades = PlayerProgressTestUtils.createPlayerUpgrades(true);

        upgrades.setLevel(CHANGED_PLAYER, ScrUpgradeIds.PROTOSS_GROUND_ARMOR, CHANGED_LEVEL);

        int previousHash = upgrades.computeStateHash(INITIAL_HASH);

        Assertions.assertFalse(upgrades.applySection(createPayload(UpgradesDat.UPGRADE_COUNT), true, false));
        Assertions.assertFalse(upgrades.applySection(new byte[UpgradesDat.UPGRADE_COUNT * PlayerTable.SETTINGS_ROWS - 1], true, true));
        Assertions.assertFalse(upgrades.applySection(new byte[CLASSIC_UPGRADE_COUNT * PlayerTable.SETTINGS_ROWS + 1], false, true));
        Assertions.assertFalse(upgrades.applySection(createPayload(CLASSIC_UPGRADE_COUNT), true, true));
        Assertions.assertEquals(previousHash, upgrades.computeStateHash(INITIAL_HASH));
    }

    @Test
    void get_level_should_read_the_upgrade_table_byte_a_player_or_upgrade_past_the_tables_addresses() {
        PlayerUpgrades upgrades = PlayerProgressTestUtils.createPlayerUpgrades(true);

        upgrades.writeContextByte(LEGACY_UPGRADING_OFFSET + PAST_PLAYER_UPGRADE, WRITTEN_LEVEL);
        upgrades.setLevel(SECOND_PLAYER, FIRST_EXPANSION_UPGRADE, WRITTEN_LEVEL);

        Assertions.assertEquals(WRITTEN_LEVEL, upgrades.getLevel(PAST_PLAYER, PAST_PLAYER_UPGRADE), "the legacy upgrading bits of player 0");
        Assertions.assertEquals(WRITTEN_LEVEL, upgrades.getLevel(FIRST_PLAYER, PAST_UPGRADE), "the first expansion level of player 1");
        Assertions.assertEquals(WRITTEN_LEVEL, upgrades.getLevel(FIRST_PLAYER, PAST_UPGRADE + IDENTIFIER_RANGE));
    }

    @Test
    void get_level_should_read_other_context_bytes_through_the_game_context() {
        RecordingContext context = new RecordingContext();
        PlayerUpgrades upgrades = PlayerProgressTestUtils.createPlayerUpgrades(new ContextExpansion(true), () -> context);

        context.writeContextByte(FORCES_PLAYER_OFFSET, CONTEXT_BYTE);
        context.writeContextByte(TAIL_PLAYER_OFFSET, CONTEXT_BYTE);

        Assertions.assertEquals(CONTEXT_BYTE, upgrades.getLevel(FORCES_PLAYER, FIRST_UPGRADE));
        Assertions.assertEquals(FORCES_PLAYER_OFFSET, context.getLastOffset());
        Assertions.assertEquals(CONTEXT_BYTE, upgrades.getLevel(TAIL_PLAYER, FIRST_EXPANSION_UPGRADE));
        Assertions.assertEquals(TAIL_PLAYER_OFFSET, context.getLastOffset());
    }

    @Test
    void get_level_should_stop_past_the_game_context_or_without_a_game_context() {
        PlayerUpgrades upgrades = PlayerProgressTestUtils.createPlayerUpgrades(new ContextExpansion(true), RecordingContext::new);

        Assertions.assertThrows(UnsupportedOperationException.class, () -> upgrades.getLevel(CONTEXT_END_PLAYER, FIRST_UPGRADE));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> upgrades.getLevel(NEGATIVE_PLAYER, FIRST_UPGRADE));
        Assertions.assertThrows(UnsupportedOperationException.class,
                                () -> PlayerProgressTestUtils.createPlayerUpgrades(true).getLevel(FORCES_PLAYER, FIRST_UPGRADE));
    }

    @Test
    void get_maximum_level_should_read_the_context_byte_a_player_past_the_slots_addresses() {
        RecordingContext context = new RecordingContext();
        PlayerUpgrades upgrades = PlayerProgressTestUtils.createPlayerUpgrades(new ContextExpansion(true), () -> context);

        upgrades.setLevel(FIRST_PLAYER, PAST_PLAYER_UPGRADE, WRITTEN_LEVEL);
        upgrades.setLevel(FIRST_PLAYER, FIRST_EXPANSION_UPGRADE, WRITTEN_LEVEL);
        context.writeContextByte(FORCES_PLAYER_OFFSET, CONTEXT_BYTE);

        Assertions.assertEquals(WRITTEN_LEVEL, upgrades.getMaximumLevel(PAST_PLAYER, PAST_PLAYER_UPGRADE), "a classic level of player 0");
        Assertions.assertEquals(WRITTEN_LEVEL, upgrades.getMaximumLevel(PAST_PLAYER, FIRST_EXPANSION_UPGRADE), "an expansion level of player 0");
        Assertions.assertEquals(CONTEXT_BYTE, upgrades.getMaximumLevel(FORCES_MAXIMUM_PLAYER, FIRST_UPGRADE));
        Assertions.assertEquals(FORCES_PLAYER_OFFSET, context.getLastOffset());
    }

    @Test
    void set_maximum_level_should_store_the_byte_without_the_expansion_gate() {
        PlayerUpgrades classic = PlayerProgressTestUtils.createPlayerUpgrades(false);

        classic.setMaximumLevel(SECOND_PLAYER, FIRST_EXPANSION_UPGRADE, WRITTEN_LEVEL);
        classic.setMaximumLevel(SECOND_PLAYER, FIRST_UPGRADE, WIDE_LEVEL);
        classic.setMaximumLevel(PAST_PLAYER, PAST_PLAYER_UPGRADE, WRITTEN_LEVEL);

        Assertions.assertEquals(WRITTEN_LEVEL, classic.getMaximumLevel(SECOND_PLAYER, FIRST_EXPANSION_UPGRADE),
                                "an expansion maximum of classic storage");
        Assertions.assertEquals(WIDE_LEVEL & BYTE_MASK, classic.getMaximumLevel(SECOND_PLAYER, FIRST_UPGRADE));
        Assertions.assertEquals(WRITTEN_LEVEL, classic.getLevel(FIRST_PLAYER, PAST_PLAYER_UPGRADE), "a classic level of player 0");
    }

    @Test
    void set_level_should_write_the_context_byte_a_player_past_the_slots_addresses_behind_the_expansion_gate() {
        RecordingContext context = new RecordingContext();
        PlayerUpgrades classic = PlayerProgressTestUtils.createPlayerUpgrades(new ContextExpansion(false), () -> context);

        classic.setLevel(PAST_PLAYER, PAST_PLAYER_UPGRADE, WRITTEN_LEVEL);
        classic.setLevel(FORCES_PLAYER, FIRST_UPGRADE, CONTEXT_BYTE);
        classic.setLevel(TAIL_PLAYER, FIRST_EXPANSION_UPGRADE, CONTEXT_BYTE);

        Assertions.assertEquals(WRITTEN_LEVEL, classic.readContextByte(LEGACY_UPGRADING_OFFSET + PAST_PLAYER_UPGRADE), "the legacy upgrading bits");
        Assertions.assertEquals(CONTEXT_BYTE, context.readContextByte(FORCES_PLAYER_OFFSET));
        Assertions.assertEquals(0, context.readContextByte(TAIL_PLAYER_OFFSET), "the expansion gate keeps an expansion level of classic storage");
    }

    @Test
    void set_upgrading_should_set_or_clear_the_bit_of_the_context_byte_a_player_past_the_slots_addresses() {
        RecordingContext context = new RecordingContext();
        PlayerUpgrades upgrades = PlayerProgressTestUtils.createPlayerUpgrades(new ContextExpansion(true), () -> context);

        context.writeContextByte(EXPANSION_OFFSET, BROOD_WAR);

        Assertions.assertTrue(upgrades.isUpgrading(PAST_PLAYER, FIRST_UPGRADE), "the expansion BYTE is the first upgrading byte of player 12");

        upgrades.setUpgrading(PAST_PLAYER, FIRST_UPGRADE, false);
        upgrades.setUpgrading(PAST_PLAYER, SECOND_BIT_UPGRADE + BYTE_IDENTIFIER_RANGE, true);

        Assertions.assertEquals(SECOND_BIT, context.readContextByte(EXPANSION_OFFSET));
        Assertions.assertFalse(upgrades.isUpgrading(PAST_PLAYER, FIRST_UPGRADE));
        Assertions.assertTrue(upgrades.isUpgrading(PAST_PLAYER, SECOND_BIT_UPGRADE));
    }
}
