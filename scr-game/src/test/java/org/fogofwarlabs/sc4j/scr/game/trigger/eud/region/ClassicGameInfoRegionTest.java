package org.fogofwarlabs.sc4j.scr.game.trigger.eud.region;

import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayHeaderBuilder;
import org.fogofwarlabs.sc4j.scr.game.session.setup.GameTemplate;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks that the game-info block holds the game type WORD at block offset {@code 0x28} and the subtype WORD at
 * {@code 0x2A}, the game header's {@code 0x3C} and {@code 0x3E}, and that a write changing either WORD stops while a write
 * to the following turn rate keeps its value.
 */
final class ClassicGameInfoRegionTest {
    /**
     * The offsets of the game type WORD, the subtype WORD and the turn rate DWORD in the block.
     */
    private static final int GAME_TYPE = 0x28;
    private static final int SUBTYPE = 0x2A;
    private static final int TURN_RATE = 0x2C;
    private static final int SUBTYPE_VALUE = 3;
    private static final int CHANGED_VALUE = 0x7F;

    private static @NotNull ClassicGameInfoRegion createRegion() {
        return new ClassicGameInfoRegion(new ReplayHeaderBuilder().setGameType(GameTemplate.TYPE_TOP_VS_BOTTOM, SUBTYPE_VALUE).build());
    }

    @Test
    void game_type_and_subtype_words_follow_the_game_header() {
        ClassicGameInfoRegion region = createRegion();

        Assertions.assertEquals(GameTemplate.TYPE_TOP_VS_BOTTOM, region.readByte(GAME_TYPE));
        Assertions.assertEquals(0, region.readByte(GAME_TYPE + 1));
        Assertions.assertEquals(SUBTYPE_VALUE, region.readByte(SUBTYPE));
        Assertions.assertEquals(0, region.readByte(SUBTYPE + 1));
        Assertions.assertEquals(GameTemplate.TYPE_TOP_VS_BOTTOM | SUBTYPE_VALUE << Short.SIZE, region.readDword(GAME_TYPE));
    }

    @Test
    void write_that_changes_the_game_type_or_subtype_word_stops() {
        ClassicGameInfoRegion region = createRegion();

        region.writeByte(SUBTYPE, SUBTYPE_VALUE);

        for (int offset = GAME_TYPE; offset < SUBTYPE + Short.BYTES; offset++) {
            int changed = offset;

            Assertions.assertThrows(UnsupportedOperationException.class, () -> region.writeByte(changed, CHANGED_VALUE));
        }

        region.writeByte(TURN_RATE, CHANGED_VALUE);

        Assertions.assertEquals(CHANGED_VALUE, region.readByte(TURN_RATE));
    }
}
