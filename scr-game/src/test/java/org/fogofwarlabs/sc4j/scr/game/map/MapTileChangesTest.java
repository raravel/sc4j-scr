package org.fogofwarlabs.sc4j.scr.game.map;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.*;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;

/**
 * Checks the map tile changes of a running game: a queued change applies at the next trigger step, the changed tiles take the terrain flags,
 * the regions and the walkability of the new tiles, a ground unit on tiles that became unwalkable moves to walkable terrain, the computer players
 * keep the start regions, two runs with the same changes keep the same state, and invalid changes are refused.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class MapTileChangesTest {
    private static final int POSITION_X = 1000;
    private static final int POSITION_Y = 1000;
    /**
     * The tiles around the unit's tile on each side that become unwalkable.
     */
    private static final int BLOCK_RADIUS = 1;
    private static final int STEPPED_FRAMES = 48;
    private static final int HALF_TILE = TerrainTileset.TILE_SIZE / 2;
    private static final int MAXIMUM_TILE_IDENTIFIER = 0xFFFF;
    private static final int TILES_PER_GROUP = 16;

    /**
     * Finds a tile of the map that is unwalkable, not partially walkable and unbuildable.
     */
    private static int findUnwalkableTile(@NotNull Game game) {
        GameMap map = game.getMap();
        MapTileChanges changes = game.getMapTileChanges();

        for (int tileIndex = 0; tileIndex < map.getTileCount(); tileIndex++) {
            int tileIdentifier = map.getTerrainTileIdentifier(tileIndex);
            int flags = changes.getTerrainFlags(tileIdentifier);

            if ((flags & (MapTileFlags.WALKABLE | MapTileFlags.PARTIALLY_WALKABLE | MapTileFlags.HAS_CREEP)) == 0 &&
                (flags & MapTileFlags.UNBUILDABLE) != 0) {
                return tileIdentifier;
            }
        }

        return Assertions.fail("The map has no unwalkable tile.");
    }

    private static @NotNull Unit createMarine(@NotNull ReplayRuntime runtime) {
        Unit[] created = new Unit[1];

        RunningGameScenario.runInCommandDispatch(runtime, () -> created[0] = RunningGameScenario.createUnit(runtime.getGame(),
                                                                                                            ScrUnitIds.TERRAN_MARINE,
                                                                                                            POSITION_X, POSITION_Y,
                                                                                                            RunningGameScenario.HUMAN));

        return created[0];
    }

    private static void step(@NotNull ReplayRuntime runtime) {
        Game game = runtime.getGame();

        Assertions.assertTrue(runtime.step(0, game.getCurrentFrame() * runtime.getFrameDuration()));
    }

    /**
     * Blocks the tiles around a unit's tile with a tile.
     */
    private static void blockAround(@NotNull Game game, @NotNull Unit unit, int tileIdentifier) {
        Sprite sprite = unit.requireSprite();
        int tileX = sprite.getX() / TerrainTileset.TILE_SIZE;
        int tileY = sprite.getY() / TerrainTileset.TILE_SIZE;

        for (int row = tileY - BLOCK_RADIUS; row <= tileY + BLOCK_RADIUS; row++) {
            for (int column = tileX - BLOCK_RADIUS; column <= tileX + BLOCK_RADIUS; column++) {
                game.getMapTileChanges().setTile(column, row, tileIdentifier);
            }
        }
    }

    /**
     * Runs the replay with a marine whose surrounding tiles become unwalkable, then some frames more.
     *
     * @return the state hash at the end
     */
    private static long runBlockedMarine(@NotNull GameData gameData) throws IOException {
        ReplayRuntime runtime = RunningGameScenario.start(gameData);
        Game game = runtime.getGame();
        Unit marine = createMarine(runtime);

        blockAround(game, marine, findUnwalkableTile(game));

        for (int frame = 0; frame < STEPPED_FRAMES; frame++) {
            step(runtime);
        }

        return game.computeStateHash();
    }


    @Test
    void apply_changes_should_give_the_tiles_their_terrain_and_move_the_unit_off_them(@NotNull InstallationTestResources resources) throws IOException {
        ReplayRuntime runtime = RunningGameScenario.start(resources.getGameData());
        Game game = runtime.getGame();
        GameMap map = game.getMap();
        MapTileChanges changes = game.getMapTileChanges();
        Unit marine = createMarine(runtime);
        Sprite sprite = marine.requireSprite();
        int tileX = sprite.getX() / TerrainTileset.TILE_SIZE;
        int tileY = sprite.getY() / TerrainTileset.TILE_SIZE;
        int tileIndex = tileY * map.getWidth() + tileX;
        int centerX = tileX * TerrainTileset.TILE_SIZE + HALF_TILE;
        int centerY = tileY * TerrainTileset.TILE_SIZE + HALF_TILE;
        int previousTile = map.getTerrainTileIdentifier(tileIndex);
        int unwalkableTile = findUnwalkableTile(game);
        TerrainContours startContours = game.getPathSystem().requireTerrainContours();

        Assertions.assertNotEquals(previousTile, unwalkableTile);
        Assertions.assertNotEquals(TerrainRegions.BLOCKED_TERRAIN,
                                   startContours.getRegions().getTerrainType(TerrainRegionLookupUtils.lookup(startContours.getRegions(),
                                                                                                             centerX, centerY)));

        blockAround(game, marine, unwalkableTile);

        Assertions.assertTrue(changes.hasPendingChanges());
        Assertions.assertEquals(unwalkableTile, changes.getTile(tileX, tileY));
        Assertions.assertEquals(previousTile, map.getTerrainTileIdentifier(tileIndex), "the change waits for the trigger step");

        step(runtime);

        TerrainContours contours = game.getPathSystem().requireTerrainContours();

        Assertions.assertFalse(changes.hasPendingChanges());
        Assertions.assertEquals(unwalkableTile, map.getTerrainTileIdentifier(tileIndex));
        Assertions.assertEquals(MapTileFlags.UNWALKABLE, map.getTileFlags(tileIndex) & (MapTileFlags.WALKABLE | MapTileFlags.UNWALKABLE));
        Assertions.assertFalse(game.getTerrain().isBuildable(tileX, tileY));
        Assertions.assertFalse(game.getTerrain().isWalkable(tileX * TerrainTileset.MINI_TILES_PER_ROW, tileY * TerrainTileset.MINI_TILES_PER_ROW));
        Assertions.assertNotSame(startContours, contours);
        Assertions.assertSame(startContours, game.getPathSystem().getStartTerrainContours(), "the computer players keep the start regions");
        Assertions.assertEquals(TerrainRegions.BLOCKED_TERRAIN,
                                contours.getRegions().getTerrainType(TerrainRegionLookupUtils.lookup(contours.getRegions(), centerX, centerY)));

        int movedTileX = sprite.getX() / TerrainTileset.TILE_SIZE;
        int movedTileY = sprite.getY() / TerrainTileset.TILE_SIZE;

        Assertions.assertTrue(Math.abs(movedTileX - tileX) > BLOCK_RADIUS || Math.abs(movedTileY - tileY) > BLOCK_RADIUS,
                              "the marine left the unwalkable tiles");
        Assertions.assertTrue(game.getUnitPositioning().isTerrainPlacementWalkable(marine.getUnitId(), sprite.getX(), sprite.getY()));
    }

    @Test
    void the_same_tile_changes_give_the_same_state_in_two_runs(@NotNull InstallationTestResources resources) throws IOException {
        long first = runBlockedMarine(resources.getGameData());
        long second = runBlockedMarine(resources.getGameData());

        Assertions.assertEquals(first, second);
    }

    @Test
    void set_tile_should_refuse_positions_outside_the_map_and_tiles_outside_the_tileset(@NotNull InstallationTestResources resources) throws IOException {
        ReplayRuntime runtime = RunningGameScenario.start(resources.getGameData());
        Game game = runtime.getGame();
        MapTileChanges changes = game.getMapTileChanges();
        int width = game.getMap().getWidth();
        int height = game.getMap().getHeight();
        int tile = game.getMap().getTerrainTileIdentifier(0);
        int firstMissingGroupTile = game.requireGameData().getTerrainTileset(game.getMap().getTileset()).getGroupCount() * TILES_PER_GROUP;

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> changes.setTile(-1, 0, tile));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> changes.setTile(width, 0, tile));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> changes.getTile(0, height));
        Assertions.assertThrows(IllegalArgumentException.class, () -> changes.setTile(0, 0, MAXIMUM_TILE_IDENTIFIER + 1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> changes.setTile(0, 0, firstMissingGroupTile));
        Assertions.assertFalse(changes.isValidTile(-1));
        Assertions.assertTrue(changes.isValidTile(tile));
        Assertions.assertFalse(changes.hasPendingChanges());
    }
}
