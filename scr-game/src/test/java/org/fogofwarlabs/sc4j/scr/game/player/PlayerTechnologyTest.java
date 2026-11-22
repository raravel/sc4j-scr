package org.fogofwarlabs.sc4j.scr.game.player;

import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.data.format.dat.TechdataDat;
import org.fogofwarlabs.sc4j.scr.game.state.ContextExpansion;
import org.fogofwarlabs.sc4j.scr.game.state.GameContext;
import org.fogofwarlabs.sc4j.scr.game.state.GameContextBytes;
import org.fogofwarlabs.sc4j.scr.game.test.PlayerProgressTestUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks that the technology section applies the player and default values, that the classic availability bytes follow player rows of
 * {@code 24} technologies, read by DWORD index because the bytes are EUD-visible game-context state that the death counters past the death
 * table read as DWORDs, and that an availability, researched or researching access past the tables reads or writes the game-context byte it
 * addresses.
 */
final class PlayerTechnologyTest {
    /**
     * The player of the only unavailable technology: byte {@code 26} of the classic availability bytes.
     */
    private static final int UNAVAILABLE_PLAYER = 1;
    /**
     * The only unavailable technology, EMP Shockwave.
     */
    private static final int UNAVAILABLE_TECHNOLOGY = 2;
    /**
     * The DWORD of the classic availability bytes {@code 0..3}: player {@code 0}, technologies {@code 0..3}.
     */
    private static final int PLAYER_0_FIRST_DWORD = 0;
    /**
     * The DWORD of the classic availability bytes {@code 24..27}: player {@code 1}, technologies {@code 0..3}.
     */
    private static final int PLAYER_1_FIRST_DWORD = 6;
    /**
     * The availability bytes {@code 1, 1, 0, 1} of player {@code 1}, technologies {@code 0..3}, as a little-endian DWORD.
     */
    private static final int PLAYER_1_FIRST_AVAILABILITY = 0x01000101;
    /**
     * The availability bytes {@code 1, 1, 1, 1} as a little-endian DWORD: every technology of the DWORD is available.
     */
    private static final int FOUR_AVAILABLE = 0x01010101;
    /**
     * The availability byte of an available technology.
     */
    private static final int AVAILABLE = 1;
    /**
     * The availability byte of an unavailable technology.
     */
    private static final int UNAVAILABLE = 0;
    /**
     * The second player slot.
     */
    private static final int SECOND_PLAYER = 1;
    /**
     * The third player slot.
     */
    private static final int THIRD_PLAYER = 2;
    /**
     * A technology section size that matches no section layout.
     */
    private static final int INVALID_SECTION_SIZE = 100;
    /**
     * The section BYTE of a set allowed, available, researched or use-default value.
     */
    private static final byte SECTION_SET = 1;
    /**
     * The first player past the player slots: its classic availability bytes lie on the classic researched bytes of player {@code 0}, its classic
     * researched bytes on the legacy researching bits of player {@code 0}.
     */
    private static final int PAST_PLAYER = 12;
    /**
     * The first player slot.
     */
    private static final int FIRST_PLAYER = 0;
    /**
     * The first classic technology.
     */
    private static final int FIRST_TECHNOLOGY = 0;
    /**
     * The first expansion technology.
     */
    private static final int FIRST_EXPANSION_TECHNOLOGY = 24;
    /**
     * A technology whose researching bit is the second bit of the second bit byte.
     */
    private static final int SECOND_BYTE_TECHNOLOGY = 9;
    /**
     * The bit byte of {@link #SECOND_BYTE_TECHNOLOGY} once its researching bit is set.
     */
    private static final int SECOND_BIT = 2;
    /**
     * A player whose bytes lie past the game context.
     */
    private static final int CONTEXT_END_PLAYER = 0x2000;

    /**
     * Serves a whole game context from a byte array.
     */
    private static final class ArrayContext implements GameContextBytes {
        private final byte @NotNull [] contents;

        private ArrayContext() {
            this.contents = new byte[GameContext.CONTEXT_SIZE];
        }

        /**
         * Reads one byte of the game context.
         *
         * @param contextOffset a game-context offset this instance serves
         * @return the unsigned byte
         */
        @Override
        public int readContextByte(int contextOffset) {
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
            this.contents[contextOffset] = (byte) value;
        }
    }

    @Test
    void get_by_native_index_should_read_the_classic_availability_in_player_rows_of_24() {
        PlayerTechnology technology = PlayerProgressTestUtils.createPlayerTechnology(true);
        byte[] section = new byte[PlayerTechnology.CLASSIC_TECHNOLOGY_COUNT * PlayerTable.SETTINGS_ROWS];

        for (int player = 0; player < PlayerTable.PLAYER_SLOTS; player++) {
            for (int technologyIndex = 0; technologyIndex < PlayerTechnology.CLASSIC_TECHNOLOGY_COUNT; technologyIndex++) {
                int sectionIndex = player * PlayerTechnology.CLASSIC_TECHNOLOGY_COUNT + technologyIndex;

                section[sectionIndex] = (byte) (player == UNAVAILABLE_PLAYER && technologyIndex == UNAVAILABLE_TECHNOLOGY ? UNAVAILABLE : AVAILABLE);
            }
        }

        technology.applySection(section, false);

        Assertions.assertEquals(PLAYER_1_FIRST_AVAILABILITY, technology.getByNativeIndex(PLAYER_1_FIRST_DWORD), "Player 1, technology 2 is byte 26.");
        Assertions.assertEquals(FOUR_AVAILABLE, technology.getByNativeIndex(PLAYER_0_FIRST_DWORD), "Player 0, technologies 0..3 are bytes 0..3.");
    }

    @Test
    void apply_section_should_apply_player_and_default_values_and_reject_other_section_sizes() {
        byte[] technology = new byte[TechdataDat.TECH_COUNT * PlayerTable.SETTINGS_ROWS];
        int burrowing = ScrTechIds.BURROWING;
        int siegeMode = ScrTechIds.TANK_SIEGE_MODE;

        technology[TechdataDat.TECH_COUNT * PlayerTable.SETTINGS_DEFAULT_FIRST_ROW + burrowing]                                       = SECTION_SET;
        technology[TechdataDat.TECH_COUNT * PlayerTable.SETTINGS_DEFAULT_SECOND_ROW + burrowing]                                      = SECTION_SET;
        technology[TechdataDat.TECH_COUNT * PlayerTable.SETTINGS_USE_DEFAULT_ROW + THIRD_PLAYER * TechdataDat.TECH_COUNT + burrowing] = SECTION_SET;
        technology[THIRD_PLAYER * TechdataDat.TECH_COUNT + siegeMode]                                                                 = SECTION_SET;

        PlayerTechnology storage = PlayerProgressTestUtils.createPlayerTechnology(true);

        storage.applySection(technology, true);

        Assertions.assertTrue(storage.isAvailable(THIRD_PLAYER, burrowing));
        Assertions.assertTrue(storage.isResearched(THIRD_PLAYER, burrowing));
        Assertions.assertFalse(storage.isAvailable(SECOND_PLAYER, burrowing));
        Assertions.assertTrue(storage.isAvailable(THIRD_PLAYER, siegeMode));
        Assertions.assertFalse(storage.isResearched(THIRD_PLAYER, siegeMode));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> storage.applySection(new byte[INVALID_SECTION_SIZE], false));
    }

    @Test
    void is_researched_should_read_the_context_byte_a_player_past_the_slots_addresses() {
        PlayerTechnology technology = PlayerProgressTestUtils.createPlayerTechnology(true);

        Assertions.assertFalse(technology.isAvailable(PAST_PLAYER, FIRST_TECHNOLOGY), "the classic researched byte of player 0");

        technology.setResearched(FIRST_PLAYER, FIRST_TECHNOLOGY, true);
        technology.writeContextByte(PlayerTechnology.LEGACY_RESEARCHING_OFFSET, SECTION_SET);

        Assertions.assertTrue(technology.isAvailable(PAST_PLAYER, FIRST_TECHNOLOGY));
        Assertions.assertTrue(technology.isResearched(PAST_PLAYER, FIRST_TECHNOLOGY), "the legacy researching bits of player 0");
    }

    @Test
    void set_researched_should_write_the_context_byte_of_a_player_past_the_slots_behind_the_expansion_gate() {
        PlayerTechnology classic = PlayerProgressTestUtils.createPlayerTechnology(false);

        classic.setResearched(PAST_PLAYER, FIRST_TECHNOLOGY, true);
        classic.setResearched(PAST_PLAYER, FIRST_EXPANSION_TECHNOLOGY, true);

        Assertions.assertEquals(SECTION_SET, classic.readContextByte(PlayerTechnology.LEGACY_RESEARCHING_OFFSET));
        Assertions.assertEquals(0, classic.readContextByte(PlayerTechnology.RESEARCHING_OFFSET),
                                "the expansion gate keeps an expansion technology of classic storage");
    }

    @Test
    void set_available_should_store_the_availability_byte_without_the_expansion_gate() {
        PlayerTechnology classic = PlayerProgressTestUtils.createPlayerTechnology(false);

        classic.setAvailable(SECOND_PLAYER, FIRST_EXPANSION_TECHNOLOGY, false);
        classic.setAvailable(SECOND_PLAYER, FIRST_TECHNOLOGY, false);

        Assertions.assertFalse(classic.isAvailable(SECOND_PLAYER, FIRST_EXPANSION_TECHNOLOGY), "an expansion technology of classic storage");
        Assertions.assertFalse(classic.isAvailable(SECOND_PLAYER, FIRST_TECHNOLOGY));
        Assertions.assertEquals(UNAVAILABLE, classic.readContextByte(PlayerTechnology.CLASSIC_AVAILABLE_OFFSET +
                                                                     SECOND_PLAYER * PlayerTechnology.CLASSIC_TECHNOLOGY_COUNT));

        classic.setAvailable(SECOND_PLAYER, FIRST_TECHNOLOGY, true);

        Assertions.assertEquals(AVAILABLE, classic.readContextByte(PlayerTechnology.CLASSIC_AVAILABLE_OFFSET +
                                                                   SECOND_PLAYER * PlayerTechnology.CLASSIC_TECHNOLOGY_COUNT));

        classic.setAvailable(PAST_PLAYER, FIRST_TECHNOLOGY, true);

        Assertions.assertTrue(classic.isResearched(FIRST_PLAYER, FIRST_TECHNOLOGY), "the classic researched byte of player 0");
    }

    @Test
    void set_researching_should_set_or_clear_the_bit_of_the_context_byte_a_player_past_the_slots_addresses() {
        ArrayContext context = new ArrayContext();
        PlayerTechnology technology = PlayerProgressTestUtils.createPlayerTechnology(new ContextExpansion(true), () -> context);
        int bitByteOffset = PlayerUpgrades.EXPANSION_MAXIMUM_OFFSET + 1;

        technology.setResearching(PAST_PLAYER, SECOND_BYTE_TECHNOLOGY, true);

        Assertions.assertEquals(SECOND_BIT, context.readContextByte(bitByteOffset), "an expansion maximum level of player 0");
        Assertions.assertTrue(technology.isResearching(PAST_PLAYER, SECOND_BYTE_TECHNOLOGY));

        technology.setResearching(PAST_PLAYER, SECOND_BYTE_TECHNOLOGY, false);

        Assertions.assertEquals(0, context.readContextByte(bitByteOffset));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> technology.setResearching(CONTEXT_END_PLAYER, FIRST_TECHNOLOGY, true));
        Assertions.assertThrows(UnsupportedOperationException.class,
                                () -> PlayerProgressTestUtils.createPlayerTechnology(true).setResearching(PAST_PLAYER, FIRST_TECHNOLOGY, true));
    }
}
