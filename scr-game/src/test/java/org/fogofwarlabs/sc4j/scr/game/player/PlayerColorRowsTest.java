package org.fogofwarlabs.sc4j.scr.game.player;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.GameTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

/**
 * Checks that EUD, the named setters and renderers use the same live player color table, which the map's {@code COLR} section remapped.
 */
@RequiresInstallation
final class PlayerColorRowsTest {
    /**
     * The EUD address of the unit color rows.
     */
    private static final int UNIT_COLOR_ROWS = 0x581D76;
    /**
     * The EUD address of the minimap colors.
     */
    private static final int MINIMAP_COLORS = 0x581DD6;
    /**
     * The address bits below a DWORD boundary.
     */
    private static final int DWORD_ALIGNMENT_MASK = 3;
    /**
     * The mask of one color byte.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The bit position of player {@code 0}'s minimap color in its aligned DWORD.
     */
    private static final int MINIMAP_COLOR_SHIFT = 16;
    /**
     * The unit color that the EUD write stores.
     */
    private static final int WRITTEN_UNIT_COLOR = 0x55;
    /**
     * The minimap color that the EUD write stores.
     */
    private static final int WRITTEN_MINIMAP_COLOR = 0x66;
    /**
     * The unit color that the named setter stores.
     */
    private static final int SET_UNIT_COLOR = 0x77;
    /**
     * The minimap color that the named setter stores.
     */
    private static final int SET_MINIMAP_COLOR = 0x88;
    /**
     * The elapsed host ticks of the replay runtime start.
     */
    private static final int START_ELAPSED_HOST_TICKS = 0;
    /**
     * The host tick of the replay runtime start.
     */
    private static final int START_HOST_TICK = 0;
    /**
     * The number of remapped unit colors in a player color table, before its minimap color.
     */
    private static final int UNIT_COLOR_COUNT = 8;
    /**
     * The index of the minimap color in a player color table, after the unit colors.
     */
    private static final int MINIMAP_COLOR_INDEX = 8;
    /**
     * The player whose first unit color and minimap color the EUD writes replace.
     */
    private static final int FIRST_PLAYER = 0;
    /**
     * The remapped color index of the first unit color.
     */
    private static final int FIRST_UNIT_COLOR = 0;

    @Test
    void player_color_rows_follow_color_tables_and_receive_eud_writes() throws Exception {
        ReplayFile replay = ReplayFile.parse(Files.readAllBytes(GameTestResources.getPath(GameTestResources.REPLAY_TVT_BAEKDUDAEGAN)));

        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            byte[] mapContents = replay.getMapData();

            Assertions.assertNotNull(mapContents);

            ReplayRuntime runtime = new ReplayRuntime(replay,
                                                      ChkFile.parseTolerant(mapContents),
                                                      gameData,
                                                      RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT,
                                                      RunningGameScenario.INSTRUCTION_LIMIT);

            runtime.start(START_ELAPSED_HOST_TICKS, START_HOST_TICK);

            Assertions.assertNull(runtime.getFailure());

            Game game = runtime.getGame();
            PlayerColorRows rows = game.getPlayerTable().getPlayerColorRows();

            Assertions.assertNotNull(rows);

            int[][] tables = gameData.getPlayerColorTables();
            byte[] mapColors = ChkFile.parseTolerant(mapContents).getSection(ChkFile.COLORS_SECTION);

            Assertions.assertNotNull(mapColors);

            for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
                // The map's COLR section names the slot whose row, as the players' colors filled it, the player takes.
                int named = Byte.toUnsignedInt(mapColors[player]);
                Player activePlayer = game.getPlayerTable().getPlayer(named < PlayerTable.PLAYER_SLOTS ? named : player);

                Assertions.assertNotNull(activePlayer);

                int color = activePlayer.getColor();

                for (int index = 0; index < UNIT_COLOR_COUNT; index++) {
                    Assertions.assertEquals(tables[color][index], rows.getUnitColor(player, index));
                }

                Assertions.assertEquals(tables[color][MINIMAP_COLOR_INDEX], rows.getMinimapColor(player));
            }

            ClassicAddressSpace space = ClassicAddressSpace.create(game);
            int aligned = UNIT_COLOR_ROWS & ~DWORD_ALIGNMENT_MASK;
            int shift = (UNIT_COLOR_ROWS - aligned) * Byte.SIZE;
            int minimapAligned = MINIMAP_COLORS & ~DWORD_ALIGNMENT_MASK;

            Assertions.assertEquals(rows.getUnitColor(FIRST_PLAYER, FIRST_UNIT_COLOR), space.readDword(aligned) >>> shift & BYTE_MASK);

            space.writeDword(aligned, space.readDword(aligned) & ~(BYTE_MASK << shift) | WRITTEN_UNIT_COLOR << shift);

            PlayerColorRows updatedRows = game.getPlayerTable().getPlayerColorRows();

            Assertions.assertNotNull(updatedRows);

            Assertions.assertEquals(WRITTEN_UNIT_COLOR, updatedRows.getUnitColor(FIRST_PLAYER, FIRST_UNIT_COLOR));

            space.writeDword(minimapAligned,
                             space.readDword(minimapAligned) & ~(BYTE_MASK << MINIMAP_COLOR_SHIFT) | WRITTEN_MINIMAP_COLOR << MINIMAP_COLOR_SHIFT);

            Assertions.assertEquals(WRITTEN_MINIMAP_COLOR, rows.getMinimapColor(FIRST_PLAYER));

            // A later address space (another trigger context) shows the same live rows.
            Assertions.assertEquals(WRITTEN_UNIT_COLOR, ClassicAddressSpace.create(game).readDword(aligned) >>> shift & BYTE_MASK);

            // The named setters store the bytes that EUD reads.
            rows.setUnitColor(FIRST_PLAYER, FIRST_UNIT_COLOR, SET_UNIT_COLOR);
            rows.setMinimapColor(FIRST_PLAYER, SET_MINIMAP_COLOR);

            Assertions.assertEquals(SET_UNIT_COLOR, space.readDword(aligned) >>> shift & BYTE_MASK);
            Assertions.assertEquals(SET_MINIMAP_COLOR, space.readDword(minimapAligned) >>> MINIMAP_COLOR_SHIFT & BYTE_MASK);
        }
    }
}
