package org.fogofwarlabs.sc4j.scr.game.path;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteScenario;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitInitializer;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

/**
 * Checks that the local collision appends the destination as a terminal candidate past its candidate capacity, as the native collector does.
 */
@RequiresInstallation
final class PathObstacleCollectorTest {
    /**
     * The width and height of the test map in tiles.
     */
    private static final int MAP_SIDE_TILES = 32;
    private static final int TILE_COUNT = MAP_SIDE_TILES * MAP_SIDE_TILES;
    /**
     * The number of terrain tile groups, the miniature flag bytes and the walkable miniature tiles of the parsed tileset.
     */
    private static final int TILE_GROUP_COUNT = 3;
    private static final int MINIATURE_FLAG_BYTES = 32;
    private static final int WALKABLE_MINIATURE_TILES = 16;

    /**
     * The horizontal and vertical position of the moving Scarab.
     */
    private static final int UNIT_POSITION = 512;
    /**
     * The Scarab rows above and below the moving Scarab: their first horizontal offset and the last horizontal offset they reach.
     */
    private static final int ROW_START = -64;
    private static final int ROW_END = 64;
    /**
     * The rows of the first layout: their vertical distance from the moving Scarab and the horizontal spacing of their Scarabs.
     */
    private static final int NEAR_ROW_DISTANCE = 8;
    private static final int NEAR_ROW_SPACING = 12;
    /**
     * The rows of the second layout, whose destination lies in two visited rectangles.
     */
    private static final int FAR_ROW_DISTANCE = 16;
    private static final int FAR_ROW_SPACING = 24;
    /**
     * The distance of the destination to the left of and above the moving Scarab in the first and in the second layout.
     */
    private static final int NEAR_DESTINATION_DISTANCE = 2;
    private static final int FAR_DESTINATION_DISTANCE = 11;
    /**
     * The candidate capacity of the collector.
     */
    private static final int CANDIDATE_CAPACITY = 32;
    /**
     * The shift of the vertical WORD in a packed position.
     */
    private static final int POSITION_VERTICAL_SHIFT = 16;
    /**
     * The exclusion rectangle of a search without a target unit.
     */
    private static final long NO_EXCLUSION_RECTANGLE = 0L;

    private static @Nullable GameData gameData;

    @BeforeAll
    static void loadGameData() throws IOException {
        gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath());
    }

    @AfterAll
    static void closeGameData() {
        if (gameData != null) {
            gameData.close();
        }
    }

    /**
     * Creates a walkable map with a moving Scarab (unit {@code 1}) between two rows of standing Scarabs.
     *
     * @param rowDistance the vertical distance of the rows from the moving Scarab
     * @param spacing     the horizontal spacing of the Scarabs of a row
     * @return the game
     * @throws IOException if the tileset cannot be parsed
     */
    private static @NotNull Game createGame(int rowDistance, int spacing) throws IOException {
        GameData data = gameData;

        Assertions.assertNotNull(data);

        int[] flags = new int[TILE_COUNT];

        Arrays.fill(flags, MapTileFlags.WALKABLE);

        GameMap map = new GameMap(MAP_SIDE_TILES, MAP_SIDE_TILES, 0, new int[TILE_COUNT], flags);
        Game game = Game.create(data, map, new LcgRandom(1));
        int rowLength = (ROW_END - ROW_START) / spacing + 1;

        game.getUnitSystem().initializeUnitPool(1 + 2 * rowLength);
        game.initializeUnitSpatialIndex();
        game.getPathSystem().setLocalPathSearch(new LocalPathSearch());

        byte[] miniatureFlags = new byte[MINIATURE_FLAG_BYTES];

        for (int index = 0; index < WALKABLE_MINIATURE_TILES; index++) {
            miniatureFlags[index * Short.BYTES] = TerrainTileset.MINI_TILE_WALKABLE;
        }

        TerrainTileset tileset = TerrainTileset.parse(new byte[TILE_GROUP_COUNT * TerrainTileset.CV5_RECORD_SIZE], miniatureFlags);

        game.getPathSystem().setTerrainContours(TerrainContours.create(map, tileset));

        placeScarab(game, 1, UNIT_POSITION, UNIT_POSITION);

        for (int index = 0; index < rowLength; index++) {
            int horizontal = UNIT_POSITION + ROW_START + index * spacing;

            placeScarab(game, 2 + 2 * index, horizontal, UNIT_POSITION - rowDistance);
            placeScarab(game, 3 + 2 * index, horizontal, UNIT_POSITION + rowDistance);
        }

        return game;
    }

    private static void placeScarab(@NotNull Game game, int unitIndex, int horizontal, int vertical) {
        GameData data = gameData;
        UnitPool pool = game.getUnitSystem().getUnitPool();
        UnitSpatialIndex spatial = game.getUnitSystem().getUnitSpatialIndex();

        Assertions.assertNotNull(data);
        Assertions.assertNotNull(pool);
        Assertions.assertNotNull(spatial);

        Unit unit = pool.getUnit(unitIndex);

        unit.setUnitId(ScrUnitIds.PROTOSS_SCARAB);
        unit.setStatus(UnitStatusFlags.COMPLETED | UnitStatusFlags.GROUND_UNIT);
        unit.setPathCollisionFlags(UnitInitializer.GROUND_ELEVATION_PATH_FLAG);
        unit.setPositionX(horizontal);
        unit.setPositionY(vertical);
        unit.setSprite(SpriteScenario.createSprite(0, horizontal, vertical, 0));
        unit.setMovementDestinationX(horizontal);
        unit.setMovementDestinationY(vertical);

        spatial.insert(unit, data.getUnitTypes());
    }

    private static @NotNull Unit getMovingScarab(@NotNull Game game) {
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        return pool.getUnit(1);
    }

    private static void initialize(@NotNull PathObstacleCollector collector, @NotNull Game game, int destinationDistance) {
        collector.initialize(game, getMovingScarab(game), 0,
                             UNIT_POSITION - destinationDistance, UNIT_POSITION - destinationDistance,
                             NO_EXCLUSION_RECTANGLE);
    }

    private static void assertGoalCandidate(@NotNull PathObstacleCollector collector, int index, int destinationDistance) {
        int destination = UNIT_POSITION - destinationDistance;

        Assertions.assertEquals(destination | destination << POSITION_VERTICAL_SHIFT, collector.getCandidatePosition(index));
        Assertions.assertEquals(PathDirections.ALL, collector.getCandidateDirections(index));
        Assertions.assertEquals(1, collector.getCandidateTerminal(index));
    }


    @Test
    void collect_should_append_goal_after_full_candidates() throws IOException {
        Game game = createGame(NEAR_ROW_DISTANCE, NEAR_ROW_SPACING);
        PathObstacleCollector collector = new PathObstacleCollector();

        collector.initializeMovementContext(false, 0);
        initialize(collector, game, NEAR_DESTINATION_DISTANCE);
        collector.collect(UNIT_POSITION, UNIT_POSITION, PathDirections.ALL);

        Assertions.assertEquals(CANDIDATE_CAPACITY + 1, collector.getCandidateCount());

        for (int index = 0; index < CANDIDATE_CAPACITY; index++) {
            Assertions.assertEquals(0, collector.getCandidateTerminal(index));
        }

        assertGoalCandidate(collector, CANDIDATE_CAPACITY, NEAR_DESTINATION_DISTANCE);
    }

    @Test
    void collect_should_append_goal_for_every_visited_rectangle_holding_it_after_full_candidates() throws IOException {
        Game game = createGame(FAR_ROW_DISTANCE, FAR_ROW_SPACING);
        PathObstacleCollector collector = new PathObstacleCollector();

        collector.initializeMovementContext(false, 0);
        initialize(collector, game, FAR_DESTINATION_DISTANCE);
        collector.collect(UNIT_POSITION, UNIT_POSITION, PathDirections.ALL);

        Assertions.assertEquals(CANDIDATE_CAPACITY + 2, collector.getCandidateCount());

        assertGoalCandidate(collector, CANDIDATE_CAPACITY, FAR_DESTINATION_DISTANCE);
        assertGoalCandidate(collector, CANDIDATE_CAPACITY + 1, FAR_DESTINATION_DISTANCE);
    }

    @Test
    void collect_should_reject_terrain_lists_overwritten_by_goal_append_until_next_search() throws IOException {
        Game game = createGame(NEAR_ROW_DISTANCE, NEAR_ROW_SPACING);
        PathObstacleCollector collector = new PathObstacleCollector();

        collector.initializeMovementContext(false, 0);
        initialize(collector, game, NEAR_DESTINATION_DISTANCE);
        collector.collect(UNIT_POSITION, UNIT_POSITION, PathDirections.ALL);

        Assertions.assertThrows(UnsupportedOperationException.class, () -> collector.collect(UNIT_POSITION, UNIT_POSITION, PathDirections.ALL));

        initialize(collector, game, NEAR_DESTINATION_DISTANCE);
        collector.collect(UNIT_POSITION, UNIT_POSITION, PathDirections.ALL);

        Assertions.assertEquals(CANDIDATE_CAPACITY + 1, collector.getCandidateCount());
    }

    @Test
    void search_should_reach_goal_appended_after_full_candidates() throws IOException {
        Game game = createGame(NEAR_ROW_DISTANCE, NEAR_ROW_SPACING);
        LocalPathSearch search = game.getPathSystem().getLocalPathSearch();
        PathRequest request = new PathRequest();
        int destination = UNIT_POSITION - NEAR_DESTINATION_DISTANCE;
        int destinationPosition = destination | destination << POSITION_VERTICAL_SHIFT;

        Assertions.assertNotNull(search);

        request.sourcePosition      = UNIT_POSITION | UNIT_POSITION << POSITION_VERTICAL_SHIFT;
        request.destinationPosition = destinationPosition;

        search.initializeMovementContext(false, 0);

        Assertions.assertTrue(search.search(game, getMovingScarab(game), request, false));
        Assertions.assertEquals(1, request.destinationReached);
        Assertions.assertEquals(destinationPosition, request.getWaypoint(request.waypointCount - 1));
    }
}
