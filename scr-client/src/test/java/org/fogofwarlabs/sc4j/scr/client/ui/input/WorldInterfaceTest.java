package org.fogofwarlabs.sc4j.scr.client.ui.input;

import org.fogofwarlabs.sc4j.scr.client.ClientReplayScenario;
import org.fogofwarlabs.sc4j.scr.client.ClientSession;
import org.fogofwarlabs.sc4j.scr.client.ui.command.ButtonAction;
import org.fogofwarlabs.sc4j.scr.client.ui.command.CommandCard;
import org.fogofwarlabs.sc4j.scr.client.ui.command.CommandInput;
import org.fogofwarlabs.sc4j.scr.client.ui.command.RecordingCommandSink;
import org.fogofwarlabs.sc4j.scr.client.ui.console.ConsoleModel;
import org.fogofwarlabs.sc4j.scr.client.ui.console.UiClockUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.text.StatText;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.client.world.ScrPresentation;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.frame.GameFrame;
import org.fogofwarlabs.sc4j.scr.game.presentation.LocalSelection;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffect;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffectPool;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.test.GameTestResources;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tests the map interaction against real replay states: picking, selection and control-group commands, right clicks,
 * markers, cursors, building placement, Select Larva and hatched units joining the selection.
 */
@RequiresInstallation
final class WorldInterfaceTest {
    private static final int COMMAND_CENTER = 106;
    private static final int TERRAN_WORKER = 7;
    private static final int LARVA_TYPE = 35;
    private static final int ZERGLING_TYPE = 37;
    private static final int SCOURGE_TYPE = 47;

    private static final @NotNull String ZERG_REPLAY = GameTestResources.REPLAY_ZVZ_PYTHON;
    private static final int INTERACTION_FRAME = 6000;
    private static final long LARVA_FRAME = 1500;
    private static final long HATCH_SEARCH_START = 600;
    private static final long HATCH_SEARCH_END = 12_000;
    private static final int GAME_SPEED_SETTING = 4;
    private static final int REPLAY_SPEED = 1;
    private static final int CURSOR_SCREEN_X = 10;
    private static final int SELECTION_VIEW_EXTENT = 4096;
    private static final int PLACEMENT_VIEW_EXTENT = 8192;
    private static final int COMMAND_CONTROL_GROUP = 3;
    private static final int VIEWER_CONTROL_GROUP = 4;
    private static final int EMPTY_VIEWER_CONTROL_GROUP = 5;
    private static final int CONTROL_GROUP_RECALL = 1;
    private static final int RIGHT_CLICK_PAYLOAD_BYTES = 11;
    private static final int TARGET_HANDLE_OFFSET = 4;
    private static final int TARGET_TYPE_OFFSET = 8;
    /**
     * The offset of the first unit handle in a selection payload, after the unit count byte.
     */
    private static final int FIRST_HANDLE_OFFSET = 1;
    private static final int SELECTED_PAIR = 2;
    private static final int MARKER_DISPLAY_FRAMES = 24;
    /**
     * The marker relations of a ground position and of an enemy unit.
     */
    private static final int GROUND_MARKER = 0;
    private static final int ENEMY_MARKER = 3;
    private static final int ATTACK_BUTTON_SLOT = 2;
    private static final int GROUND_SEARCH_POSITION = 100;
    private static final int GROUND_SEARCH_STRIDE = 64;
    private static final int TARGET_BUTTON_SET = 229;
    private static final char ESCAPE_CHARACTER = 27;
    private static final int PLACEMENT_SEARCH_RADIUS = 8;
    private static final int DEPOT_CENTER_OFFSET_X = 48;
    private static final int CLICK_TILE_OFFSET = 5;
    private static final int BUILDING_ERROR_TEXT = 856;
    private static final int MESSAGE_DURATION_MILLISECONDS = 4000;
    private static final int WORKER_BUTTON_SET = 7;
    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;
    private static final int ALL_PLAYER_VISION_MASK = 0xFF;
    private static final int PLACEMENT_HALF_DIVISOR = 2;
    private static final int SUPPLY_DEPOT_TYPE = 109;
    private static final byte PLACE_BUILDING_ORDER = 0x1e;

    /**
     * The last frame searched for a dying unit, and the frames both sessions are compared after the click.
     */
    private static final long DEATH_SEARCH_END = 16_000;
    private static final int DEATH_COMPARED_FRAMES = 240;

    private static final int BUILD_OPCODE = 0x0C;
    private static final int HOTKEY_OPCODE = 0x13;
    private static final int RIGHT_CLICK_OPCODE = 0x60;
    private static final int TARGETED_ORDER_OPCODE = 0x61;
    private static final int SELECT_OPCODE = 0x63;

    private static @NotNull ClientSession startAtInteractionFrame() throws IOException {
        return ClientReplayScenario.start(GameTestResources.REPLAY_TVT_BAEKDUDAEGAN, INTERACTION_FRAME);
    }

    private static int readHandle(byte @NotNull [] payload, int offset) {
        int handle = 0;

        for (int index = 0; index < Integer.BYTES; index++) {
            handle |= (payload[offset + index] & BYTE_MASK) << index * Byte.SIZE;
        }

        return handle;
    }

    private static @NotNull List<Unit> findSelectedUnits(@NotNull Game game, @NotNull RecordingCommandSink.Command command) {
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        List<Unit> units = new ArrayList<>();

        for (int index = 0; index < (command.getPayload()[0] & BYTE_MASK); index++) {
            units.add(pool.resolveHandle(readHandle(command.getPayload(), FIRST_HANDLE_OFFSET + index * Integer.BYTES)));
        }

        return units;
    }

    /**
     * Finds a unit of the dead list that plays its death animation in a shown sprite whose main image can be clicked.
     *
     * @param pool the unit pool
     * @return the dying unit, or {@code null}
     */
    private static @Nullable Unit findClickableDyingUnit(@NotNull UnitPool pool) {
        for (int index = pool.getFirstDeadIndex(); index != 0; index = pool.getNextCreatedIndex(index)) {
            Unit unit = pool.getUnit(index);
            Sprite sprite = unit.getSpriteOrNull();
            SpriteImage main = sprite == null ? null : sprite.getMainImage();

            if (main != null && (sprite.getFlags() & Sprite.FLAG_HIDDEN) == 0 && (main.getFlags() & SpriteImage.FLAG_CLICKABLE) != 0 &&
                main.hasGraphics()) {
                return unit;
            }
        }

        return null;
    }

    private static void assertSameUnitSlots(@NotNull UnitPool expected, @NotNull UnitPool actual, long frame) {
        Assertions.assertEquals(expected.getFirstFreeIndex(), actual.getFirstFreeIndex(), "first free unit at frame " + frame);

        for (int index = 1; index <= expected.getCapacity(); index++) {
            Assertions.assertEquals(expected.isAllocated(index), actual.isAllocated(index), "allocation of unit " + index + " at frame " + frame);
            Assertions.assertEquals(expected.getGeneration(index), actual.getGeneration(index), "generation of unit " + index + " at frame " + frame);
        }
    }

    @Test
    void a_click_on_a_dying_unit_should_keep_the_unit_slots_of_the_replay() throws IOException {
        try (ClientSession clicked = startAtInteractionFrame(); ClientSession reference = startAtInteractionFrame()) {
            Game game = clicked.getGame();
            UnitPool pool = game.getUnitSystem().requireUnitPool();
            UnitPool referencePool = reference.getGame().getUnitSystem().requireUnitPool();
            Unit dying = null;

            while (dying == null && clicked.getLastCompletedFrame() < DEATH_SEARCH_END) {
                Assertions.assertTrue(clicked.stepFrame(false) && reference.stepFrame(false));
                dying = findClickableDyingUnit(pool);
            }

            Assertions.assertNotNull(dying, "a unit dies in the replay");

            // Makes the local player an enemy of the dying unit.
            int localPlayer = dying.getOwnerIndex() == 0 ? 1 : 0;
            ConsoleModel console = new ConsoleModel(new ScrGameView(game), new ScrPresentation(game));

            console.setOwnLocalSelection(localPlayer);
            console.update(localPlayer, CommandCard.Perspective.PLAYER, false, GAME_SPEED_SETTING, REPLAY_SPEED);

            WorldInterface world = new WorldInterface(console, new RecordingCommandSink(), true);
            Sprite sprite = dying.requireSprite();

            game.getInterfaceLink().setLocalSelection(world);
            world.handleLeftPress(sprite.getX(), sprite.getY(), false);
            world.handleLeftRelease(sprite.getX(), sprite.getY(), false, false, false, false, 0, 0, 1, 1);

            Assertions.assertFalse(console.getSelection().contains(dying), "a dying unit is not picked");

            for (int frame = 0; frame < DEATH_COMPARED_FRAMES; frame++) {
                Assertions.assertTrue(clicked.stepFrame(false) && reference.stepFrame(false));
                assertSameUnitSlots(referencePool, pool, clicked.getLastCompletedFrame());
            }
        }
    }

    @Test
    void pointer_clicks_pick_and_select_units_through_commands() throws IOException {
        try (ClientSession session = startAtInteractionFrame()) {
            Game game = session.getGame();
            ConsoleModel console = new ConsoleModel(new ScrGameView(game), new ScrPresentation(game));

            console.update(0, CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);

            RecordingCommandSink sink = new RecordingCommandSink();
            List<RecordingCommandSink.Command> submitted = sink.getCommands();
            WorldInterface world = new WorldInterface(console, sink, true);
            Unit commandCenter = ClientReplayScenario.findCompletedUnits(game, 0, COMMAND_CENTER).getFirst();

            UnitPicker picker = new UnitPicker(new ScrGameView(game));

            Assertions.assertSame(commandCenter, picker.pickUnit(commandCenter.getPositionX(), commandCenter.getPositionY()));
            Assertions.assertSame(commandCenter, picker.pick(commandCenter.getPositionX(), commandCenter.getPositionY()).getUnit(),
                                  "the engine target of the press is the picked unit");

            world.handlePointerMove(commandCenter.getPositionX(), commandCenter.getPositionY(), CURSOR_SCREEN_X, -1);
            Assertions.assertSame(commandCenter, world.getHoveredUnit());
            Assertions.assertEquals(WorldInterface.Cursor.MAGNIFIER_OWN, world.getCursor());
            Assertions.assertEquals(CURSOR_SCREEN_X, world.getCursorScreenX());

            world.handleLeftPress(commandCenter.getPositionX(), commandCenter.getPositionY(), false);
            Assertions.assertTrue(world.isDragSelecting());
            Assertions.assertEquals(WorldInterface.Cursor.DRAG, world.getCursor());
            Assertions.assertEquals(CommandInput.Result.COMMAND, world.handleLeftRelease(commandCenter.getPositionX(), commandCenter.getPositionY(),
                                                                                         false, false, false,
                                                                                         false,
                                                                                         0, 0,
                                                                                         SELECTION_VIEW_EXTENT, SELECTION_VIEW_EXTENT));
            Assertions.assertFalse(world.isDragSelecting());

            RecordingCommandSink.Command select = submitted.getLast();

            Assertions.assertEquals(SELECT_OPCODE, select.getOpcode());
            Assertions.assertEquals(0, select.getPlayer());
            Assertions.assertEquals(1, select.getPayload()[0]);
            Assertions.assertSame(commandCenter, game.getUnitSystem().requireUnitPool().resolveHandle(readHandle(select.getPayload(), 1)));

            Unit enemy = ClientReplayScenario.findCompletedUnits(game, 1, COMMAND_CENTER).getFirst();

            world.handlePointerMove(enemy.getPositionX(), enemy.getPositionY(), 0, -1);
            Assertions.assertEquals(WorldInterface.Cursor.MAGNIFIER_ENEMY, world.getCursor());
            world.handleLeftPress(enemy.getPositionX(), enemy.getPositionY(), false);
            Assertions.assertEquals(CommandInput.Result.COMMAND,
                                    world.handleLeftRelease(enemy.getPositionX(), enemy.getPositionY(), false, false, false, false, 0, 0, 1, 1));
            Assertions.assertTrue(console.isLocalSelection(), "another player's unit is shown as a local selection");
            Assertions.assertSame(enemy, world.getSelectedUnit(0));

            RecordingCommandSink.Command enemySelect = submitted.getLast();

            Assertions.assertEquals(SELECT_OPCODE, enemySelect.getOpcode(), "another player's unit is selected by command too");
            Assertions.assertEquals(0, enemySelect.getPlayer());
            Assertions.assertEquals(1, enemySelect.getPayload()[0]);
            Assertions.assertSame(enemy, game.getUnitSystem().requireUnitPool().resolveHandle(readHandle(enemySelect.getPayload(), 1)));

            Assertions.assertEquals(CommandInput.Result.COMMAND, world.handleControlGroupKey(COMMAND_CONTROL_GROUP, false, false, false, 0));
            Assertions.assertEquals(HOTKEY_OPCODE, submitted.getLast().getOpcode());
            Assertions.assertArrayEquals(new byte[]{CONTROL_GROUP_RECALL, COMMAND_CONTROL_GROUP}, submitted.getLast().getPayload());
            Assertions.assertFalse(console.isLocalSelection());
        }
    }

    @Test
    void orders_record_markers_and_placements_send_build_commands() throws IOException {
        try (ClientSession session = startAtInteractionFrame()) {
            Game game = session.getGame();
            ConsoleModel console = new ConsoleModel(new ScrGameView(game), new ScrPresentation(game));

            console.update(1, CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);

            RecordingCommandSink sink = new RecordingCommandSink();
            List<RecordingCommandSink.Command> submitted = sink.getCommands();
            WorldInterface world = new WorldInterface(console, sink, true);
            Unit enemy = ClientReplayScenario.findCompletedUnits(game, 0, COMMAND_CENTER).getFirst();

            Assertions.assertEquals(CommandInput.Result.COMMAND, world.handleRightClick(enemy.getPositionX(), enemy.getPositionY(), false));

            RecordingCommandSink.Command rightClick = submitted.getLast();

            Assertions.assertEquals(RIGHT_CLICK_OPCODE, rightClick.getOpcode());
            Assertions.assertEquals(RIGHT_CLICK_PAYLOAD_BYTES, rightClick.getPayload().length);
            Assertions.assertSame(enemy, game.getUnitSystem().requireUnitPool().resolveHandle(readHandle(rightClick.getPayload(), TARGET_HANDLE_OFFSET)));
            Assertions.assertEquals(1, world.getMarkerCount());
            Assertions.assertEquals(ENEMY_MARKER, world.getMarkerRelation(0));
            Assertions.assertEquals(-1, world.getMarkerOrder(0));
            Assertions.assertSame(enemy, world.getFlashedUnit());
            world.expireMarkers(game.getCurrentFrame() + WorldInterface.FLASH_TIMER, MARKER_DISPLAY_FRAMES);
            Assertions.assertEquals(1, world.getMarkerCount(), "a flash lasts until its timer has run down");
            world.expireMarkers(game.getCurrentFrame() + WorldInterface.FLASH_TIMER + 1, MARKER_DISPLAY_FRAMES);
            Assertions.assertEquals(0, world.getMarkerCount());

            Assertions.assertEquals(CommandInput.Result.INTERFACE, world.getCommandInput().pressSlot(ATTACK_BUTTON_SLOT, false));

            int groundX = GROUND_SEARCH_POSITION;
            UnitPicker picker = new UnitPicker(new ScrGameView(game));

            while (picker.pickUnit(groundX, GROUND_SEARCH_POSITION) != null) {
                groundX += GROUND_SEARCH_STRIDE;
            }

            world.handlePointerMove(groundX, GROUND_SEARCH_POSITION, 0, -1);
            Assertions.assertEquals(WorldInterface.Cursor.TARGET_NONE, world.getCursor());
            Assertions.assertEquals(CommandInput.Result.COMMAND, world.handleLeftPress(groundX, GROUND_SEARCH_POSITION, false));
            Assertions.assertEquals(TARGETED_ORDER_OPCODE, submitted.getLast().getOpcode());
            Assertions.assertEquals(1, world.getMarkerCount());
            Assertions.assertEquals(GROUND_MARKER, world.getMarkerRelation(0));

            HotkeyProfile text = new HotkeyProfile(StatText.load(session.getStorage(), "enUS"), HotkeyProfile.DEVICE, Map.of());

            Assertions.assertEquals(CommandInput.Result.INTERFACE,
                                    world.getCommandInput().pressHotkey(text, 'a', false),
                                    "A starts attack targeting");
            Assertions.assertTrue(console.getMode().getUnitTargetOrder() >= 0);
            console.update(1, CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);
            Assertions.assertEquals(TARGET_BUTTON_SET, console.getCommandCard().getDisplayedSet());
            Assertions.assertEquals(CommandInput.Result.INTERFACE,
                                    world.getCommandInput().pressHotkey(text, ESCAPE_CHARACTER, false),
                                    "Escape cancels targeting");
            Assertions.assertTrue(console.getMode().getUnitTargetOrder() < 0);

            Unit worker = ClientReplayScenario.findCompletedUnits(game, 1, TERRAN_WORKER).getFirst();

            console.getSelection().set(1, worker);
            console.getMode().startPlacement(ButtonAction.TERRAN_BUILD, SUPPLY_DEPOT_TYPE);
            Assertions.assertEquals(SUPPLY_DEPOT_TYPE, world.getPlacementType());
            // The hovered tile is the footprint's top-left tile: (viewX >> 5) + (cameraX >> 5).
            int validX = -1;
            int validY = -1;
            int blockedX = -1;
            int blockedY = -1;
            int workerTileX = worker.getPositionX() / TerrainTileset.TILE_SIZE;
            int workerTileY = worker.getPositionY() / TerrainTileset.TILE_SIZE;

            for (int tileY = Math.max(0, workerTileY - PLACEMENT_SEARCH_RADIUS); tileY < workerTileY + PLACEMENT_SEARCH_RADIUS; tileY++) {
                for (int tileX = Math.max(0, workerTileX - PLACEMENT_SEARCH_RADIUS); tileX < workerTileX + PLACEMENT_SEARCH_RADIUS; tileX++) {
                    boolean valid = game.getUnitPlacement().canPlaceBuilding(worker, 1, SUPPLY_DEPOT_TYPE,
                                                                             tileX * TerrainTileset.TILE_SIZE + DEPOT_CENTER_OFFSET_X,
                                                                             tileY * TerrainTileset.TILE_SIZE + TerrainTileset.TILE_SIZE,
                                                                             false, false);

                    if (valid && validX < 0) {
                        validX = tileX;
                        validY = tileY;
                    } else if (!valid && blockedX < 0) {
                        blockedX = tileX;
                        blockedY = tileY;
                    }
                }
            }

            Assertions.assertTrue(validX >= 0 && blockedX >= 0, "a valid and a blocked Supply Depot site near the worker");
            world.setView(0, 0, PLACEMENT_VIEW_EXTENT, PLACEMENT_VIEW_EXTENT);
            Assertions.assertEquals(CommandInput.Result.INTERFACE,
                                    world.handleLeftPress(blockedX * TerrainTileset.TILE_SIZE + CLICK_TILE_OFFSET,
                                                          blockedY * TerrainTileset.TILE_SIZE + CLICK_TILE_OFFSET, false),
                                    "an invalid site keeps placement mode");
            Assertions.assertEquals(SUPPLY_DEPOT_TYPE, world.getPlacementType());
            Assertions.assertEquals(BUILDING_ERROR_TEXT,
                                    world.getMessageId(UiClockUtils.getHostTimeMilliseconds(), MESSAGE_DURATION_MILLISECONDS),
                                    "You can't build there.");
            Assertions.assertEquals(CommandInput.Result.COMMAND,
                                    world.handleLeftPress(validX * TerrainTileset.TILE_SIZE + CLICK_TILE_OFFSET,
                                                          validY * TerrainTileset.TILE_SIZE + CLICK_TILE_OFFSET, false));

            RecordingCommandSink.Command build = submitted.getLast();

            Assertions.assertEquals(BUILD_OPCODE, build.getOpcode());
            Assertions.assertArrayEquals(new byte[]{PLACE_BUILDING_ORDER,
                                                    (byte) validX, (byte) (validX >> Byte.SIZE),
                                                    (byte) validY, (byte) (validY >> Byte.SIZE),
                                                    SUPPLY_DEPOT_TYPE, 0},
                                         build.getPayload());
            Assertions.assertEquals(-1, world.getPlacementType());
        }
    }

    @Test
    void replay_viewer_shows_clicked_units_locally_with_own_groups() throws IOException {
        try (ClientSession session = startAtInteractionFrame()) {
            Game game = session.getGame();
            ConsoleModel console = new ConsoleModel(new ScrGameView(game), new ScrPresentation(game));

            console.update(0, CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);

            WorldInterface world = new WorldInterface(console, (player, opcode, payload) -> Assertions.fail("the viewer sends no selection"), false);

            Assertions.assertEquals(ALL_PLAYER_VISION_MASK,
                                    game.getLocalVision().getComparisonMask(),
                                    "A replay compares with every player's vision.");

            Unit worker = ClientReplayScenario.findCompletedUnits(game, 0, TERRAN_WORKER).getFirst();

            world.handleLeftPress(worker.getPositionX(), worker.getPositionY(), false);
            world.handleLeftRelease(worker.getPositionX(), worker.getPositionY(), false, false, false, false, 0, 0, 1, 1);
            Assertions.assertTrue(console.isLocalSelection());
            console.update(0, CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);
            Assertions.assertSame(worker, world.getSelectedUnit(0));
            Assertions.assertEquals(WORKER_BUTTON_SET, console.getCommandCard().getDisplayedSet());
            Assertions.assertEquals(CommandInput.Result.INTERFACE,
                                    world.handleControlGroupKey(VIEWER_CONTROL_GROUP, true, false, false, 0),
                                    "the viewer keeps its own groups");
            console.followSynchronizedSelection();
            console.update(0, CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);
            Assertions.assertEquals(COMMAND_CENTER, world.getSelectedUnit(0).getUnitId());
            Assertions.assertEquals(CommandInput.Result.IGNORED, world.handleControlGroupKey(EMPTY_VIEWER_CONTROL_GROUP, false, false, false, 0));
            Assertions.assertEquals(CommandInput.Result.INTERFACE, world.handleControlGroupKey(VIEWER_CONTROL_GROUP, false, false, false, 0));
            console.update(0, CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);
            Assertions.assertTrue(console.isLocalSelection());
            Assertions.assertSame(worker, world.getSelectedUnit(0));
        }
    }

    @Test
    void right_click_on_remembered_building_flashes_it_and_sends_its_type() throws IOException {
        try (ClientSession session = startAtInteractionFrame()) {
            Game game = session.getGame();
            GameFrame frame = game.getFrame();

            Assertions.assertNotNull(frame);

            SpriteEffectPool remembered = frame.getSecondarySpriteEffects();
            int rememberedCount = remembered.getAllocatedCount();
            // Player 1's Command Center leaves player 0's vision and creates a remembered fog sprite at its position.
            Unit enemy = ClientReplayScenario.findCompletedUnits(game, 1, COMMAND_CENTER).getFirst();
            Sprite enemySprite = enemy.getSpriteOrNull();

            Assertions.assertNotNull(enemySprite);
            enemySprite.setVisibilityFlags(ALL_PLAYER_VISION_MASK);
            game.getSpriteVisibility().update(enemy, game.getMap(), 1, Integer.MAX_VALUE);

            Assertions.assertEquals(rememberedCount + 1, remembered.getAllocatedCount(), "the Command Center is remembered");

            SpriteEffect ghost = null;

            for (int index = remembered.getFirstActiveIndex(); index != -1; index = remembered.getNextIndex(index)) {
                SpriteEffect candidate = remembered.getSpriteEffect(index);

                if ((candidate.getLife() & WORD_MASK) == COMMAND_CENTER && candidate.getSprite().getX() == enemySprite.getX() &&
                    candidate.getSprite().getY() == enemySprite.getY()) {
                    ghost = candidate;
                }
            }

            Assertions.assertNotNull(ghost, "the remembered Command Center lies at its position");

            int type = ghost.getLife() & WORD_MASK;
            Sprite sprite = ghost.getSprite();
            DatTable units = game.requireGameData().getUnits();
            // The box ends at the sprite position plus half the placement box and spans the sprite's width.
            int mapX = (short) sprite.getX() + (short) units.get("Placement Box Width", type) / PLACEMENT_HALF_DIVISOR;
            int mapY = (short) sprite.getY() + (short) units.get("Placement Box Height", type) / PLACEMENT_HALF_DIVISOR;
            UnitPicker picker = new UnitPicker(new ScrGameView(game));

            Assertions.assertSame(ghost, picker.pickRememberedBuilding(mapX, mapY), "the first list entry covering the point");
            Assertions.assertNotSame(ghost, picker.pickRememberedBuilding(mapX + 1, mapY));
            Assertions.assertNotSame(ghost, picker.pickRememberedBuilding(mapX - sprite.getWidth(), mapY));

            ConsoleModel console = new ConsoleModel(new ScrGameView(game), new ScrPresentation(game));

            console.update(0, CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);

            RecordingCommandSink sink = new RecordingCommandSink();
            List<RecordingCommandSink.Command> submitted = sink.getCommands();
            WorldInterface world = new WorldInterface(console, sink, true);
            Unit commandCenter = ClientReplayScenario.findCompletedUnits(game, 0, COMMAND_CENTER).getFirst();

            console.getSelection().set(0, commandCenter);
            Assertions.assertEquals(CommandInput.Result.COMMAND, world.handleRightClick(mapX, mapY, false));

            RecordingCommandSink.Command rightClick = submitted.getLast();

            Assertions.assertEquals(RIGHT_CLICK_OPCODE, rightClick.getOpcode());
            Assertions.assertEquals(type,
                                    rightClick.getPayload()[TARGET_TYPE_OFFSET] & BYTE_MASK |
                                    (rightClick.getPayload()[TARGET_TYPE_OFFSET + 1] & BYTE_MASK) << Byte.SIZE,
                                    "the right click sends the remembered type");
            Assertions.assertEquals(1, world.getMarkerCount());
            Assertions.assertSame(sprite, world.getMarkerRememberedSprite(0));
            Assertions.assertNull(world.getMarkerTarget(0), "only the remembered building flashes");
            Assertions.assertNull(world.getFlashedUnit());
            world.expireMarkers(game.getCurrentFrame() + WorldInterface.MARKER_LIFETIME, WorldInterface.MARKER_LIFETIME);
            Assertions.assertEquals(1, world.getMarkerCount(), "the flash outlives the move marker");
        }
    }

    @Test
    void select_larva_should_select_larvae_of_active_hatchery() throws IOException {
        try (ClientSession session = ClientReplayScenario.start(ZERG_REPLAY, LARVA_FRAME)) {
            Game game = session.getGame();
            UnitPool pool = game.getUnitSystem().getUnitPool();

            Assertions.assertNotNull(pool);

            Unit hatchery = null;
            int expected = 0;

            for (int index = 1; index <= pool.getCapacity() && hatchery == null; index++) {
                if (!pool.isAllocated(index) || pool.getUnit(index).getUnitId() != ScrUnitIds.ZERG_HATCHERY) {
                    continue;
                }

                Unit candidate = pool.getUnit(index);
                int larvae = 0;

                for (int other = 1; other <= pool.getCapacity(); other++) {
                    if (pool.isAllocated(other) && pool.getUnit(other).getUnitId() == ScrUnitIds.ZERG_LARVA &&
                        pool.getUnit(other).getParentUnit() == candidate) {
                        larvae++;
                    }
                }

                if (larvae > 0) {
                    hatchery = candidate;
                    expected = larvae;
                }
            }

            Assertions.assertNotNull(hatchery, "the replay has a Hatchery with Larvae");

            ConsoleModel console = new ConsoleModel(new ScrGameView(game), new ScrPresentation(game));

            console.update(hatchery.getOwnerIndex(), CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);

            RecordingCommandSink sink = new RecordingCommandSink();
            List<RecordingCommandSink.Command> submitted = sink.getCommands();
            WorldInterface world = new WorldInterface(console, sink, true);

            console.getSelection().set(hatchery.getOwnerIndex(), hatchery);

            Assertions.assertEquals(CommandInput.Result.COMMAND, world.selectLarva());

            RecordingCommandSink.Command select = submitted.getLast();

            Assertions.assertEquals(SELECT_OPCODE, select.getOpcode());

            List<Unit> larvae = findSelectedUnits(game, select);

            Assertions.assertEquals(expected, larvae.size());

            for (Unit larva : larvae) {
                Assertions.assertEquals(LARVA_TYPE, larva.getUnitId());
                Assertions.assertSame(hatchery, larva.getParentUnit());
            }
        }
    }

    @Test
    void add_hatched_unit_should_join_pair_to_selection_only_when_egg_is_selected() throws IOException {
        try (ClientSession session = ClientReplayScenario.start(ZERG_REPLAY, HATCH_SEARCH_START)) {
            Game game = session.getGame();
            List<Unit[]> hatched = new ArrayList<>();

            game.getInterfaceLink().setLocalSelection(new LocalSelection() {
                /**
                 * Records the two offspring of a hatched egg.
                 *
                 * @param egg    the unit that hatched (the first offspring keeps the egg's unit)
                 * @param second the second offspring
                 */
                @Override
                public void addHatchedUnit(@NotNull Unit egg, @NotNull Unit second) {
                    hatched.add(new Unit[]{egg, second});
                }

                /**
                 * @param position the slot from {@code 0} through {@code 11}
                 * @return {@code 0}, because the recording selection holds no selected units
                 */
                @Override
                public int getSelectedPoolIndex(int position) {
                    return 0;
                }

                /**
                 * Accepts a raw slot write without storing it.
                 *
                 * @param position  the slot from {@code 0} through {@code 11}
                 * @param poolIndex the pool index of the unit to store, or {@code 0} for an empty slot
                 */
                @Override
                public void setSelectedPoolIndexRaw(int position, int poolIndex) {
                }
            });

            while (hatched.isEmpty() && session.getLastCompletedFrame() < HATCH_SEARCH_END && session.stepFrame(false)) {
            }

            game.getInterfaceLink().setLocalSelection(null);

            Assertions.assertFalse(hatched.isEmpty(), "a Zergling pair hatches in the replay");

            Unit egg = hatched.getFirst()[0];
            Unit second = hatched.getFirst()[1];

            Assertions.assertEquals(egg.getUnitId(), second.getUnitId());
            Assertions.assertTrue(egg.getUnitId() == ZERGLING_TYPE || egg.getUnitId() == SCOURGE_TYPE);

            ConsoleModel console = new ConsoleModel(new ScrGameView(game), new ScrPresentation(game));

            console.update(egg.getOwnerIndex(), CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);

            RecordingCommandSink sink = new RecordingCommandSink();
            List<RecordingCommandSink.Command> submitted = sink.getCommands();
            WorldInterface world = new WorldInterface(console, sink, true);

            // Not selected: nothing changes.
            console.getSelection().set(egg.getOwnerIndex(), second);
            world.addHatchedUnit(egg, second);

            Assertions.assertTrue(submitted.isEmpty());

            // Selected: the second unit takes the next slot and the pair is selected with command 0x63.
            console.getSelection().set(egg.getOwnerIndex(), egg);
            world.addHatchedUnit(egg, second);

            Assertions.assertEquals(1, submitted.size());
            Assertions.assertEquals(SELECT_OPCODE, submitted.getLast().getOpcode());
            Assertions.assertEquals(List.of(egg, second), findSelectedUnits(game, submitted.getLast()));
            Assertions.assertEquals(SELECTED_PAIR, console.getSelection().getCount());
            Assertions.assertSame(second, console.getSelection().getUnit(1));
        }
    }
}
