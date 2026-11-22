package org.fogofwarlabs.sc4j.scr.game.combat;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrWeaponIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayLimits;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrFlingyTypeView;
import org.fogofwarlabs.sc4j.scr.game.frame.GameFrame;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMapScenario;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Checks the projectile list updates, the Halo Rockets creation limit and the cover tile of the miss chance of {@link ProjectilePhase}, and the
 * unsigned halt distance test of a fast bullet.
 */
final class ProjectilePhaseTest {
    /**
     * The bullet capacity of the extended limits.
     */
    private static final int EXTENDED_BULLET_CAPACITY = 400;
    /**
     * A bullet capacity whose eight tenths are not a whole number.
     */
    private static final int UNEVEN_BULLET_CAPACITY = 399;
    /**
     * The Halo Rockets creation limit of the classic bullet capacity.
     */
    private static final int CLASSIC_HALO_ROCKETS_LIMIT = 80;
    /**
     * The Halo Rockets creation limit of the extended bullet capacity.
     */
    private static final int EXTENDED_HALO_ROCKETS_LIMIT = 320;
    /**
     * The Halo Rockets creation limit of the uneven bullet capacity.
     */
    private static final int UNEVEN_HALO_ROCKETS_LIMIT = 319;
    /**
     * The length of the classic chain of the free-list commit in the Halo Rockets limit test.
     */
    private static final int COMMITTED_CHAIN_LENGTH = 10;

    /**
     * The state hash seed of the list update test.
     */
    private static final int STATE_HASH_SEED = 1;
    /**
     * A target X outside the signed WORD the target field keeps, and a negative target Y.
     */
    private static final int WRAPPED_TARGET_X = 70000;
    private static final int NEGATIVE_TARGET_Y = -5;
    /**
     * A remaining time outside the BYTE the field keeps.
     */
    private static final int WRAPPED_REMAINING_TIME = 300;

    /**
     * The native miss chance, out of {@code 256}, against a target on higher ground.
     */
    private static final int MISS_CHANCE_COVERED = 119;
    /**
     * The rows of the cover tile test: the cover tiles lie in row seven, the target stands past the right edge of row six and left of the left edge
     * of row eight.
     */
    private static final int ROW_SIX = 6;
    private static final int ROW_SEVEN = 7;
    private static final int ROW_EIGHT = 8;
    /**
     * The distance of the target position past a tile edge in the cover tile test.
     */
    private static final int IN_TILE_OFFSET = 5;

    /**
     * The order state of a moving bullet.
     */
    private static final int MOVE_STATE = 1;
    /**
     * The remaining lifetime of the braking bullet, which keeps it alive through the update.
     */
    private static final int BULLET_LIFETIME = 100;
    /**
     * The coordinate of the braking bullet's waypoint, far from its launch position.
     */
    private static final int FAR_WAYPOINT = 1000;
    /**
     * A speed whose square exceeds the signed DWORD range: its halt distance wraps to a negative DWORD.
     */
    private static final int OVERFLOWING_SPEED = 50000;
    private static final int OVERFLOWING_ACCELERATION = 1;
    /**
     * The speed after one braking update: {@link #OVERFLOWING_SPEED} less {@link #OVERFLOWING_ACCELERATION}.
     */
    private static final int BRAKED_SPEED = 49999;

    @Test
    void bullet_and_flingy_updates_require_established_lists_and_context() {
        ProjectilePhase.Context context = new ProjectilePhase.Context();

        Assertions.assertThrows(UnsupportedOperationException.class, () -> ProjectilePhase.updateEmptyBullets(context));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> ProjectilePhase.updateFlingies(context));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> ProjectilePhase.updateEmptyBullets(null));
    }

    @Test
    void bullet_and_flingy_updates_keep_established_empty_lists_unchanged() {
        ProjectilePhase.Context context = new ProjectilePhase.Context();

        context.initializeEmptyLists();

        int initialHash = context.computeStateHash(STATE_HASH_SEED);

        ProjectilePhase.updateEmptyBullets(context);
        ProjectilePhase.updateFlingies(context);

        Assertions.assertEquals(0, context.getActiveCount());
        Assertions.assertEquals(initialHash, context.computeStateHash(STATE_HASH_SEED));

        ProjectilePhase.updateEmptyBullets(context);
        ProjectilePhase.updateFlingies(context);

        Assertions.assertEquals(initialHash, context.computeStateHash(STATE_HASH_SEED));
    }

    @Test
    void get_halo_rockets_limit_should_take_eight_tenths_of_the_created_bullet_capacity() {
        ProjectilePhase.Context extended = new ProjectilePhase.Context(EXTENDED_BULLET_CAPACITY);

        Assertions.assertEquals(CLASSIC_HALO_ROCKETS_LIMIT, new ProjectilePhase.Context(ReplayLimits.CLASSIC_BULLET_CAPACITY).getHaloRocketsLimit());
        Assertions.assertEquals(UNEVEN_HALO_ROCKETS_LIMIT, new ProjectilePhase.Context(UNEVEN_BULLET_CAPACITY).getHaloRocketsLimit());
        Assertions.assertEquals(EXTENDED_HALO_ROCKETS_LIMIT, extended.getHaloRocketsLimit());

        extended.expandFreeList(COMMITTED_CHAIN_LENGTH);

        Assertions.assertEquals(EXTENDED_HALO_ROCKETS_LIMIT, extended.getHaloRocketsLimit(), "A free-list commit keeps the created capacity.");
    }

    @RequiresInstallation
    @Test
    void get_miss_chance_should_read_the_cover_tile_under_a_target_sprite_off_the_map() throws IOException {
        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            Game game = CombatGameScenario.createDuelGame(gameData, ScrUnitIds.TERRAN_WRAITH, ScrUnitIds.TERRAN_MARINE);
            GameMap map = game.getMap();
            UnitPool pool = game.getUnitSystem().getUnitPool();

            Assertions.assertNotNull(pool);

            Unit attacker = pool.getUnit(CombatGameScenario.FIRST_UNIT_INDEX);
            Unit target = pool.getUnit(CombatGameScenario.SECOND_UNIT_INDEX);
            Sprite targetSprite = target.getSpriteOrNull();
            int firstTileOfRowSeven = ROW_SEVEN * map.getWidth();
            int lastTileOfRowSeven = firstTileOfRowSeven + map.getWidth() - 1;
            int pastRightEdgeX = map.getWidth() * TerrainTileset.TILE_SIZE + IN_TILE_OFFSET;
            int leftOfLeftEdgeX = -TerrainTileset.TILE_SIZE - IN_TILE_OFFSET;
            int rowSixY = ROW_SIX * TerrainTileset.TILE_SIZE + IN_TILE_OFFSET;
            int rowEightY = ROW_EIGHT * TerrainTileset.TILE_SIZE + IN_TILE_OFFSET;

            Assertions.assertNotNull(targetSprite);

            GameMapScenario.setFlags(map, firstTileOfRowSeven, map.getTileFlags(firstTileOfRowSeven) | MapTileFlags.PROVIDES_COVER);
            GameMapScenario.setFlags(map, lastTileOfRowSeven, map.getTileFlags(lastTileOfRowSeven) | MapTileFlags.PROVIDES_COVER);
            target.setPositionX(pastRightEdgeX);
            target.setPositionY(rowSixY);
            targetSprite.setX(pastRightEdgeX);
            targetSprite.setY(rowSixY);

            Assertions.assertEquals(MISS_CHANCE_COVERED, game.getProjectilePhase().getMissChance(attacker, target),
                                    "The column past the right edge of row six reads the first tile of row seven.");

            target.setPositionX(leftOfLeftEdgeX);
            target.setPositionY(rowEightY);
            targetSprite.setX(leftOfLeftEdgeX);
            targetSprite.setY(rowEightY);

            Assertions.assertEquals(MISS_CHANCE_COVERED, game.getProjectilePhase().getMissChance(attacker, target),
                                    "Column -1 of row eight reads the last tile of row seven.");
        }
    }

    @RequiresInstallation
    @Test
    void update_bullets_should_brake_when_the_wrapped_halt_distance_reaches_the_waypoint_unsigned() throws IOException {
        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            Game game = CombatGameScenario.createDuelGame(gameData, ScrUnitIds.TERRAN_WRAITH, ScrUnitIds.TERRAN_MARINE);
            GameFrame frame = game.getFrame();
            UnitPool pool = game.getUnitSystem().getUnitPool();

            Assertions.assertNotNull(frame);
            Assertions.assertNotNull(pool);

            Unit source = pool.getUnit(CombatGameScenario.FIRST_UNIT_INDEX);
            ProjectilePhase.Bullet bullet = game.getProjectilePhase().create(source, ScrWeaponIds.GEMINI_MISSILES,
                                                                             CombatGameScenario.FIRST_UNIT_X, CombatGameScenario.FIRST_UNIT_Y,
                                                                             CombatGameScenario.INSTRUCTION_LIMIT);

            Assertions.assertNotNull(bullet);

            bullet.orderState    = MOVE_STATE;
            bullet.remainingTime = BULLET_LIFETIME;
            bullet.setMovementControl(ScrFlingyTypeView.MOVEMENT_CONTROL_FLINGY);
            bullet.setMovementFlags(0);
            bullet.setMovementDestinationX(FAR_WAYPOINT);
            bullet.setMovementDestinationY(FAR_WAYPOINT);
            bullet.setMovementWaypointX(FAR_WAYPOINT);
            bullet.setMovementWaypointY(FAR_WAYPOINT);
            bullet.setTopSpeed(OVERFLOWING_SPEED);
            bullet.setCurrentSpeed(OVERFLOWING_SPEED);
            bullet.setNextSpeed(OVERFLOWING_SPEED);
            bullet.setAcceleration(OVERFLOWING_ACCELERATION);

            game.getProjectilePhase().updateBullets(frame.getProjectiles(), CombatGameScenario.INSTRUCTION_LIMIT);

            Assertions.assertEquals(BRAKED_SPEED, bullet.getCurrentSpeed(),
                                    "The wrapped halt distance is compared with the waypoint distance unsigned.");
        }
    }

    @RequiresInstallation
    @Test
    void a_created_bullet_takes_the_next_generation_and_its_field_writes_keep_the_native_widths() throws IOException {
        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            Game game = CombatGameScenario.createDuelGame(gameData, ScrUnitIds.TERRAN_WRAITH, ScrUnitIds.TERRAN_MARINE);
            GameFrame frame = game.getFrame();
            UnitPool pool = game.getUnitSystem().getUnitPool();

            Assertions.assertNotNull(frame);
            Assertions.assertNotNull(pool);

            ProjectilePhase.Context context = frame.getProjectiles();
            Unit source = pool.getUnit(CombatGameScenario.FIRST_UNIT_INDEX);
            ProjectilePhase.Bullet first = game.getProjectilePhase().create(source, ScrWeaponIds.GEMINI_MISSILES,
                                                                            CombatGameScenario.FIRST_UNIT_X, CombatGameScenario.FIRST_UNIT_Y,
                                                                            CombatGameScenario.INSTRUCTION_LIMIT);
            ProjectilePhase.Bullet second = game.getProjectilePhase().create(source, ScrWeaponIds.GEMINI_MISSILES,
                                                                             CombatGameScenario.FIRST_UNIT_X, CombatGameScenario.FIRST_UNIT_Y,
                                                                             CombatGameScenario.INSTRUCTION_LIMIT);

            Assertions.assertNotNull(first);
            Assertions.assertNotNull(second);
            Assertions.assertTrue(first.isActive());
            Assertions.assertSame(first, context.getBullet(first.getPoolIndex()));
            Assertions.assertEquals(first.getGeneration() + 1, second.getGeneration());
            Assertions.assertEquals(ScrWeaponIds.GEMINI_MISSILES, first.getWeapon());
            Assertions.assertSame(source, first.getSourceUnit());

            long hash = game.computeStateHash();

            first.setTargetPosition(WRAPPED_TARGET_X, NEGATIVE_TARGET_Y);
            first.setRemainingTime(WRAPPED_REMAINING_TIME);

            Assertions.assertEquals((short) WRAPPED_TARGET_X, first.getTargetX());
            Assertions.assertEquals(NEGATIVE_TARGET_Y, first.getTargetY());
            Assertions.assertEquals(Byte.toUnsignedInt((byte) WRAPPED_REMAINING_TIME), first.getRemainingTime());
            Assertions.assertNotEquals(hash, game.computeStateHash(), "the writes are game state");
        }
    }
}
