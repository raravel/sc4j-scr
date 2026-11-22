package org.fogofwarlabs.sc4j.scr.game.combat;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrWeaponIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.iscript.IscriptFile;
import org.fogofwarlabs.sc4j.scr.data.format.iscript.IscriptInstruction;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayLimits;
import org.fogofwarlabs.sc4j.scr.game.frame.GameFrame;
import org.fogofwarlabs.sc4j.scr.game.frame.UnitPhase;
import org.fogofwarlabs.sc4j.scr.game.movement.UnitMovement;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.sprite.*;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.test.UnitTestUtils;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.visibility.MapVisibility;
import org.fogofwarlabs.sc4j.scr.game.visibility.SightCoverage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Checks mining projectile signals, recycling, a bullet whose image ends before its order signal, and the creation limits: a full bullet pool and
 * the Halo Rockets limit.
 */
@RequiresInstallation
final class ProjectileLifetimeTest {
    /**
     * The bullet capacity of the extended limits.
     */
    private static final int EXTENDED_BULLET_CAPACITY = 400;
    /**
     * The active bullets from which a context of the classic bullet capacity creates no Halo Rockets bullet.
     */
    private static final int CLASSIC_HALO_ROCKETS_LIMIT = 80;
    /**
     * The active bullets from which a context of the extended bullet capacity creates no Halo Rockets bullet.
     */
    private static final int EXTENDED_HALO_ROCKETS_LIMIT = 320;
    /**
     * An EUD-written active bullet count of {@code 2^31}, negative as a signed DWORD.
     */
    private static final int SIGN_BIT_ACTIVE_COUNT = Integer.MIN_VALUE;
    /**
     * An EUD-written active bullet count of {@code 2^32 - 1}, the largest unsigned DWORD.
     */
    private static final int LARGEST_ACTIVE_COUNT = -1;
    /**
     * The launch position of the created bullets.
     */
    private static final int LAUNCH_POSITION = 100;
    /**
     * The image and order instruction budget of the created and updated bullets.
     */
    private static final int INSTRUCTION_LIMIT = 10000;

    /**
     * The width and height of the test map in tiles.
     */
    private static final int MAP_SIDE_TILES = 8;
    private static final int TILE_COUNT = MAP_SIDE_TILES * MAP_SIDE_TILES;
    private static final int IMAGE_CAPACITY = 16;
    private static final int SPRITE_CAPACITY = 8;
    private static final int VISIBILITY_CONTROL_COUNT = 5;

    /**
     * The heading of the source units.
     */
    private static final int SOURCE_HEADING = 32;
    /**
     * The bullet capacity of the mining bullet test.
     */
    private static final int MINING_BULLET_CAPACITY = 2;
    /**
     * The bullet capacity of the full bullet pool test.
     */
    private static final int FULL_POOL_BULLET_CAPACITY = 1;
    /**
     * The image type of the created mining bullet.
     */
    private static final int MINING_BULLET_IMAGE = 520;
    /**
     * The frames after which the mining bullet test stops updating.
     */
    private static final int MAXIMUM_UPDATE_FRAMES = 80;
    /**
     * The order state of a dying bullet.
     */
    private static final int DYING_ORDER_STATE = 5;
    /**
     * The order state of a bullet waiting for its order signal.
     */
    private static final int INIT_ORDER_STATE = 0;
    /**
     * The {@code iscript.bin} entry whose Init animation shows a frame, waits two frames and ends without an order signal.
     */
    private static final int SELF_ENDING_ISCRIPT = 250;
    /**
     * The {@code iscript.bin} animation index of Init.
     */
    private static final int INIT_ANIMATION = 0;
    /**
     * The instructions of an Init animation read before the test gives up looking for its end.
     */
    private static final int MAXIMUM_INIT_INSTRUCTIONS = 16;
    /**
     * The frames a bullet whose image ended is updated.
     */
    private static final int SPRITELESS_UPDATE_FRAMES = 10;
    /**
     * The mask of a heading.
     */
    private static final int HEADING_MASK = 255;
    /**
     * The factor of the source heading that the headings of two consecutive mining bullets add up to.
     */
    private static final int HEADING_SUM_FACTOR = 2;

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

    private static @NotNull Game createGame(int capacity) throws IOException {
        Assertions.assertNotNull(gameData);

        Game game = Game.create(gameData, new GameMap(MAP_SIDE_TILES, MAP_SIDE_TILES, 0, new int[TILE_COUNT], new int[TILE_COUNT]), new LcgRandom(1));

        game.getRandom().setEnabled(1);
        game.getSpriteSystem().initializeImagePool(IMAGE_CAPACITY);
        game.getSpriteSystem().initializeSpritePool(SPRITE_CAPACITY);

        SightCoverage coverage = new SightCoverage(MAP_SIDE_TILES);

        coverage.setSharedVisionMasks(new int[PlayerTable.PLAYER_SLOTS]);
        game.setSightCoverage(coverage);

        ProjectilePhase.Context context = new ProjectilePhase.Context(capacity);

        context.initializeEmptyLists();
        game.setFrame(new GameFrame(new UnitPhase.Context(UnitPhase.INITIAL_PRIMARY_REDISTRIBUTION_TIMER,
                                                          UnitPhase.INITIAL_SECONDARY_REDISTRIBUTION_TIMER),
                                    new UnitMovement.Context(0, 0),
                                    context,
                                    new SpriteEffectPool(1),
                                    new MapVisibility(gameData.getTerrainTileset(0), new int[VISIBILITY_CONTROL_COUNT])));

        return game;
    }

    private static @NotNull Unit createSourceUnit() {
        Unit unit = UnitTestUtils.createUnit(1);

        unit.setUnitId(ScrUnitIds.PROTOSS_PROBE);
        unit.setHeading(SOURCE_HEADING);
        unit.setSprite(new Sprite(1));

        return unit;
    }

    private static void assertEndsWithoutOrderSignal(@NotNull IscriptFile iscript, int scriptIdentifier) throws IOException {
        int offset = iscript.getAnimationOffset(scriptIdentifier, INIT_ANIMATION);

        for (int count = 0; count < MAXIMUM_INIT_INSTRUCTIONS; count++) {
            IscriptInstruction instruction = iscript.decodeInstruction(offset);

            Assertions.assertNotEquals("sigorder", instruction.getName());

            if (instruction.getName().equals("end")) {
                return;
            }

            offset += iscript.getInstructionSize(offset);
        }

        Assertions.fail("The Init animation has no end.");
    }

    private static @Nullable ProjectilePhase.Bullet createHaloRockets(int capacity, int activeCount) throws IOException {
        Game game = createGame(capacity);
        Unit valkyrie = createSourceUnit();
        GameFrame frame = game.getFrame();

        Assertions.assertNotNull(frame);

        valkyrie.setUnitId(ScrUnitIds.TERRAN_VALKYRIE);
        frame.getProjectiles().setActiveCount(activeCount);

        return game.getProjectilePhase().create(valkyrie, ScrWeaponIds.HALO_ROCKETS, LAUNCH_POSITION, LAUNCH_POSITION, INSTRUCTION_LIMIT);
    }


    @Test
    void mining_bullet_runs_impact_death_and_recycling_without_random_draws() throws IOException {
        Game game = createGame(MINING_BULLET_CAPACITY);
        ProjectilePhase projectilePhase = game.getProjectilePhase();
        int miningWeapon = ScrWeaponIds.UNKNOWN_63;
        Unit worker = createSourceUnit();
        GameFrame activeFrame = game.getFrame();

        Assertions.assertNotNull(activeFrame);

        ProjectilePhase.Context context = activeFrame.getProjectiles();
        long beforeRandom = game.getRandom().getTotalRandomCounts();
        ProjectilePhase.Bullet bullet = projectilePhase.create(worker, miningWeapon, LAUNCH_POSITION, LAUNCH_POSITION, INSTRUCTION_LIMIT);

        Assertions.assertNotNull(bullet);

        Sprite initialBulletSprite = bullet.sprite;

        Assertions.assertNotNull(initialBulletSprite);

        SpriteImage initialBulletImage = initialBulletSprite.getMainImage();

        Assertions.assertNotNull(initialBulletImage);
        Assertions.assertEquals(MINING_BULLET_IMAGE, initialBulletImage.getImageTypeId());
        Assertions.assertEquals(1, context.getActiveCount());
        Assertions.assertEquals(beforeRandom, game.getRandom().getTotalRandomCounts());

        int firstHeading = bullet.getHeading();
        int spriteIndex = initialBulletSprite.getPoolIndex();
        boolean dying = false;

        for (int frame = 0; frame < MAXIMUM_UPDATE_FRAMES && context.getActiveCount() != 0; frame++) {
            projectilePhase.updateBullets(context, INSTRUCTION_LIMIT);
            dying |= bullet.orderState == DYING_ORDER_STATE;
        }

        Assertions.assertTrue(dying);
        Assertions.assertEquals(0, context.getActiveCount());

        SpriteImagePool recycledImagePool = game.getSpriteSystem().getImagePool();

        Assertions.assertNotNull(recycledImagePool);
        Assertions.assertEquals(0, recycledImagePool.getAllocatedCount());

        SpritePool recycledSpritePool = game.getSpriteSystem().getSpritePool();

        Assertions.assertNotNull(recycledSpritePool);
        Assertions.assertFalse(recycledSpritePool.isAllocated(spriteIndex));
        Assertions.assertTrue(game.getInterfaceLink().getSoundEvents().getCount() > 0);

        ProjectilePhase.Bullet following = projectilePhase.create(worker, miningWeapon, LAUNCH_POSITION, LAUNCH_POSITION, INSTRUCTION_LIMIT);

        Assertions.assertNotNull(following);
        Assertions.assertEquals((HEADING_SUM_FACTOR * worker.getHeading()) & HEADING_MASK, (firstHeading + following.getHeading()) & HEADING_MASK);
        Assertions.assertEquals(beforeRandom, game.getRandom().getTotalRandomCounts());
    }

    @Test
    void bullet_whose_image_ends_before_its_order_signal_waits_in_the_init_state_without_a_sprite() throws IOException {
        Game game = createGame(MINING_BULLET_CAPACITY);
        ProjectilePhase projectilePhase = game.getProjectilePhase();
        GameFrame activeFrame = game.getFrame();

        Assertions.assertNotNull(activeFrame);

        ProjectilePhase.Context context = activeFrame.getProjectiles();

        assertEndsWithoutOrderSignal(game.requireGameData().getIscript(), SELF_ENDING_ISCRIPT);
        game.requireGameData().getImages().set("Iscript", MINING_BULLET_IMAGE, SELF_ENDING_ISCRIPT);

        ProjectilePhase.Bullet bullet = projectilePhase.create(createSourceUnit(), ScrWeaponIds.UNKNOWN_63, LAUNCH_POSITION, LAUNCH_POSITION,
                                                               INSTRUCTION_LIMIT);

        Assertions.assertNotNull(bullet);
        Assertions.assertNotNull(bullet.sprite);

        for (int frame = 0; frame < SPRITELESS_UPDATE_FRAMES; frame++) {
            projectilePhase.updateBullets(context, INSTRUCTION_LIMIT);
        }

        Assertions.assertNull(bullet.sprite);
        Assertions.assertEquals(INIT_ORDER_STATE, bullet.orderState);
        Assertions.assertEquals(1, context.getActiveCount());

        SpriteImagePool imagePool = game.getSpriteSystem().getImagePool();

        Assertions.assertNotNull(imagePool);
        Assertions.assertEquals(0, imagePool.getAllocatedCount());
    }

    @Test
    void full_bullet_pool_rejects_creation_and_reference_removal_clears_only_that_unit() throws IOException {
        Game game = createGame(FULL_POOL_BULLET_CAPACITY);
        ProjectilePhase projectilePhase = game.getProjectilePhase();
        int miningWeapon = ScrWeaponIds.UNKNOWN_63;
        Unit worker = createSourceUnit();
        Unit target = createSourceUnit();

        target.setUnitId(ScrUnitIds.RESOURCE_MINERAL_FIELD);
        target.setStatus(UnitStatusFlags.FLYING);
        worker.setOrderTargetUnit(target);

        GameFrame activeFrame = game.getFrame();

        Assertions.assertNotNull(activeFrame);

        ProjectilePhase.Context context = activeFrame.getProjectiles();
        ProjectilePhase.Bullet bullet = projectilePhase.create(worker, miningWeapon, LAUNCH_POSITION, LAUNCH_POSITION, INSTRUCTION_LIMIT);

        Assertions.assertNotNull(bullet);
        Assertions.assertSame(worker, bullet.ownerUnit);
        Assertions.assertSame(target, bullet.targetUnit);

        long randomCount = game.getRandom().getTotalRandomCounts();

        Assertions.assertNull(projectilePhase.create(worker, miningWeapon, LAUNCH_POSITION, LAUNCH_POSITION, INSTRUCTION_LIMIT));
        Assertions.assertEquals(randomCount, game.getRandom().getTotalRandomCounts());

        context.removeTargetReferences(target);

        Assertions.assertNull(bullet.targetUnit);
        Assertions.assertSame(worker, bullet.ownerUnit);

        context.removeTargetReferences(worker);

        Assertions.assertNull(bullet.ownerUnit);
        Assertions.assertEquals(1, context.getActiveCount());
    }

    @Test
    void create_should_refuse_halo_rockets_from_eight_tenths_of_the_created_bullet_capacity() throws IOException {
        Assertions.assertNotNull(createHaloRockets(EXTENDED_BULLET_CAPACITY, CLASSIC_HALO_ROCKETS_LIMIT));
        Assertions.assertNotNull(createHaloRockets(EXTENDED_BULLET_CAPACITY, EXTENDED_HALO_ROCKETS_LIMIT - 1));
        Assertions.assertNull(createHaloRockets(EXTENDED_BULLET_CAPACITY, EXTENDED_HALO_ROCKETS_LIMIT));
        Assertions.assertNotNull(createHaloRockets(ReplayLimits.CLASSIC_BULLET_CAPACITY, CLASSIC_HALO_ROCKETS_LIMIT - 1));
        Assertions.assertNull(createHaloRockets(ReplayLimits.CLASSIC_BULLET_CAPACITY, CLASSIC_HALO_ROCKETS_LIMIT));
    }

    @Test
    void create_should_compare_an_eud_active_count_with_the_halo_rockets_limit_unsigned() throws IOException {
        Assertions.assertNull(createHaloRockets(ReplayLimits.CLASSIC_BULLET_CAPACITY, SIGN_BIT_ACTIVE_COUNT));
        Assertions.assertNull(createHaloRockets(EXTENDED_BULLET_CAPACITY, SIGN_BIT_ACTIVE_COUNT));
        Assertions.assertNull(createHaloRockets(EXTENDED_BULLET_CAPACITY, LARGEST_ACTIVE_COUNT));
    }
}
