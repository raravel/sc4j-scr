package org.fogofwarlabs.sc4j.scr.client.ui.command;

import org.fogofwarlabs.sc4j.scr.client.ClientReplayScenario;
import org.fogofwarlabs.sc4j.scr.client.ClientSession;
import org.fogofwarlabs.sc4j.scr.client.ClientSessionScenario;
import org.fogofwarlabs.sc4j.scr.client.ui.console.ConsoleModel;
import org.fogofwarlabs.sc4j.scr.client.ui.input.HotkeyProfile;
import org.fogofwarlabs.sc4j.scr.client.ui.input.WorldInterface;
import org.fogofwarlabs.sc4j.scr.client.ui.status.ButtonTooltip;
import org.fogofwarlabs.sc4j.scr.client.ui.text.StatText;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.client.world.ScrPresentation;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrButtonInput;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.presentation.HostButtonSets;
import org.fogofwarlabs.sc4j.scr.game.presentation.ScrButtonStates;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.GameTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Checks the commands that the command input encodes in live and replay games: Unload All of every transport, the unload
 * of one cargo unit, training, canceling, targeting, selection and Select Larva.
 */
@RequiresInstallation
final class CommandInputTest {
    private static final int HUMAN = RunningGameScenario.HUMAN;
    private static final int TRANSPORT_X = 3300;
    private static final int TRANSPORT_Y = 2700;
    private static final int TARGET_X = 3200;
    private static final int TARGET_Y = 2600;

    /**
     * The replay control inputs of the command card updates: paused, the fastest speed setting and no multiplier.
     */
    private static final boolean REPLAY_PAUSED = true;
    private static final int GAME_SPEED_SETTING = 4;
    private static final int REPLAY_SPEED = 1;

    private static final int UNLOAD_ALL_OPCODE = 0x28;
    private static final int TARGETED_ORDER_OPCODE = 0x61;
    private static final int UNLOAD_OPCODE = 0x62;
    private static final int SELECT_OPCODE = 0x63;
    private static final int TRAIN_OPCODE = 0x1F;
    private static final int CANCEL_TRAIN_OPCODE = 0x20;
    private static final int STOP_OPCODE = 0x1A;
    private static final byte QUEUED_FLAG = 1;
    /**
     * The targeted order payload: x and y WORDs, the target handle DWORD, the fog type WORD, the order and the queue flag.
     */
    private static final int TARGET_HANDLE_OFFSET = 2 * Short.BYTES;
    private static final int TARGET_TYPE_OFFSET = 8;
    private static final int TARGET_ORDER_OFFSET = 3 * Short.BYTES + Integer.BYTES;
    private static final int TARGET_COMMAND_LENGTH = 12;
    private static final int NO_TARGET_HANDLE = 0;
    /**
     * The unit-type word of a target command without a remembered building under the click.
     */
    private static final int NO_TARGET_UNIT_TYPE = 0xe4;
    private static final int SELECTED_COUNT_OFFSET = 0;
    private static final int SELECTED_HANDLE_OFFSET = 1;
    /**
     * The Cancel Last payload: the last queue slot {@code 254}, then the unit-type high byte.
     */
    private static final byte LAST_QUEUE_SLOT = (byte) 254;

    private static final int MOVE_UNLOAD_ORDER = 112;
    private static final int FOLLOW_ORDER = 49;
    private static final int MOVE_ORDER = 6;
    private static final int NO_TARGET_ORDER = -1;
    private static final int CANCEL_TARGETING_SET = 229;
    private static final int SCV_TYPE = 7;
    private static final int LARVA_TYPE = 35;
    private static final int SELECTION_CAPACITY = 12;

    private static final long TVT_FRAME = 6000;
    private static final int UNLOAD_SETUP_FRAME = 9247;
    private static final int FIRST_PLAYER = 0;
    private static final int SECOND_PLAYER = 1;
    private static final int TRAIN_SLOT = 0;
    private static final int EMPTY_SLOT = 1;
    private static final int CANCEL_LAST_SLOT = 8;
    private static final int STOP_SLOT = 1;
    private static final int MOVE_SLOT = 0;
    private static final int GROUND_TARGET_X = 1000;
    private static final int GROUND_TARGET_Y = 2000;

    /**
     * Zerg mission {@code 2} has two local Hatcheries {@code 96} pixels apart.
     */
    private static final @NotNull String MISSION = "zerg-2";
    private static final @NotNull String HUMAN_NAME = "Tester";
    private static final @NotNull String GAME_LOCALE = "enUS";
    private static final int SETTLE_FRAMES = 400;
    private static final int COMMAND_FRAMES = 3;
    private static final int SEARCH_RADIUS = 128;
    private static final int SELECT_LARVA_SLOT = 0;
    /**
     * The script button of the script button test: its slot, condition and action value, icon, hotkey and texts, and the set a request shows.
     */
    private static final int SCRIPT_SLOT = 0;
    private static final int SCRIPT_VALUE = 3;
    private static final int SCRIPT_ICON = 236;
    private static final char SCRIPT_HOTKEY = 'z';
    private static final @NotNull String SCRIPT_TEXT = "Zap\nnow";
    private static final @NotNull String SCRIPT_DISABLED_TEXT = "\u0006Not ready";
    private static final int SCRIPT_CHANGED_SET = 0;

    private static @Nullable GameData gameData;
    private static @Nullable ReplayRuntime runtime;

    @BeforeAll
    static void startGame() throws IOException {
        gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath());
        runtime  = RunningGameScenario.start(gameData);
    }

    @AfterAll
    static void close() {
        runtime = null;

        if (gameData != null) {
            gameData.close();
        }

        gameData = null;
    }

    private static @NotNull Unit createLoadedTransport(@NotNull Game game, int transportType, int passengerType) {
        Unit transport = RunningGameScenario.createUnit(game, transportType, TRANSPORT_X, TRANSPORT_Y, HUMAN);
        Unit passenger = RunningGameScenario.createUnit(game, passengerType, TRANSPORT_X, TRANSPORT_Y, HUMAN);

        game.getUnitTransport().load(transport, passenger, RunningGameScenario.INSTRUCTION_LIMIT);

        return transport;
    }

    private static @NotNull ConsoleModel createConsole(@NotNull Game game, @NotNull Unit transport) {
        ConsoleModel console = new ConsoleModel(new ScrGameView(game), new ScrPresentation(game));

        console.refresh(HUMAN, CommandCard.Perspective.PLAYER, false, GAME_SPEED_SETTING, REPLAY_SPEED);
        console.showLocalSelection(transport);
        console.refresh(HUMAN, CommandCard.Perspective.PLAYER, false, GAME_SPEED_SETTING, REPLAY_SPEED);

        return console;
    }

    private static int findUnloadAllSlot(@NotNull CommandCard card) {
        for (int slot = 0; slot < CommandCard.SLOT_COUNT; slot++) {
            ButtonDefinition button = card.getButton(slot);

            if (button != null && button.getAction() == ButtonAction.UNLOAD_ALL) {
                return slot;
            }
        }

        return Assertions.fail("The command card has no Unload All button.");
    }

    private static int readInteger(byte @NotNull [] payload, int offset, int width) {
        int value = 0;

        for (int index = 0; index < width; index++) {
            value |= Byte.toUnsignedInt(payload[offset + index]) << (index * Byte.SIZE);
        }

        return value;
    }

    private static int getPassengerType(int transportType) {
        return switch (transportType) {
            case ScrUnitIds.PROTOSS_SHUTTLE -> ScrUnitIds.PROTOSS_ZEALOT;
            case ScrUnitIds.ZERG_OVERLORD -> ScrUnitIds.ZERG_ZERGLING;
            default -> ScrUnitIds.TERRAN_MARINE;
        };
    }

    /**
     * Presses Unload All for a loaded transport, then clicks the ground or a unit while targeting, and checks that the press
     * only enters MoveUnload targeting and the click sends the MoveUnload order at the clicked position and unit.
     *
     * @param transportType the transport type
     * @param clickUnit     whether the targeting click is on a unit instead of the ground
     */
    private static void assertUnloadAllTargetsMoveUnload(int transportType, boolean clickUnit) {
        RecordingCommandSink sink = new RecordingCommandSink();
        List<RecordingCommandSink.Command> submitted = sink.getCommands();
        int[] targetHandle = {NO_TARGET_HANDLE};

        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);

            Game game = runtime.getGame();
            UnitPool pool = game.getUnitSystem().getUnitPool();
            Unit transport = createLoadedTransport(game, transportType, getPassengerType(transportType));
            Unit target = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_SCV, TARGET_X, TARGET_Y, HUMAN);
            ConsoleModel console = createConsole(game, transport);
            CommandInput input = new CommandInput(console, sink);

            Assertions.assertNotNull(pool);
            Assertions.assertEquals(CommandInput.Result.INTERFACE, input.pressSlot(findUnloadAllSlot(console.getCommandCard()), false));
            Assertions.assertTrue(submitted.isEmpty(), "the press sends no command");

            InterfaceMode mode = console.getMode();

            Assertions.assertEquals(MOVE_UNLOAD_ORDER, mode.getUnitTargetOrder());
            Assertions.assertEquals(MOVE_UNLOAD_ORDER, mode.getGroundTargetOrder());
            Assertions.assertEquals(MOVE_UNLOAD_ORDER, mode.getAlternateOrder());

            if (clickUnit) {
                targetHandle[0] = pool.getHandle(target.getIndex());
            }

            Assertions.assertEquals(CommandInput.Result.COMMAND,
                                    input.clickTarget(TARGET_X, TARGET_Y, clickUnit ? target : null, CommandInput.NO_UNIT_TYPE, false));
        });

        Assertions.assertEquals(1, submitted.size());

        RecordingCommandSink.Command order = submitted.getFirst();

        Assertions.assertEquals(TARGETED_ORDER_OPCODE, order.getOpcode());
        Assertions.assertEquals(MOVE_UNLOAD_ORDER, Byte.toUnsignedInt(order.getPayload()[TARGET_ORDER_OFFSET]));
        Assertions.assertEquals(TARGET_X, readInteger(order.getPayload(), 0, Short.BYTES));
        Assertions.assertEquals(TARGET_Y, readInteger(order.getPayload(), Short.BYTES, Short.BYTES));
        Assertions.assertEquals(targetHandle[0], readInteger(order.getPayload(), TARGET_HANDLE_OFFSET, Integer.BYTES));
    }

    /**
     * Checks that every selected Larva belongs to the required Hatchery.
     *
     * @param hatchery  the required parent
     * @param selection the local selection
     * @param expected  the expected Larva count
     */
    private static void assertLarvaeOf(@NotNull Unit hatchery, @NotNull InterfaceSelection selection, int expected) {
        Assertions.assertEquals(expected, selection.getCount());

        for (int slot = 0; slot < selection.getCount(); slot++) {
            Unit larva = selection.getUnit(slot);

            Assertions.assertNotNull(larva);
            Assertions.assertEquals(LARVA_TYPE, larva.getUnitId());
            Assertions.assertSame(hatchery, larva.getParentUnit());
        }
    }

    /**
     * Finds a local Hatchery with another Hatchery's Larvae inside its search box.
     *
     * @param game   the initialized world
     * @param player the local player
     * @return the matching Hatchery, or {@code null} if none exists
     */
    private static @Nullable Unit findHatcheryWithForeignLarvae(@NotNull Game game, int player) {
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        for (int index = 1; index <= pool.getCapacity(); index++) {
            if (!pool.isAllocated(index)) {
                continue;
            }

            Unit candidate = pool.getUnit(index);

            if (candidate.getOwnerIndex() != player) {
                continue;
            }

            int candidateType = candidate.getUnitId();

            if ((candidateType != ScrUnitIds.ZERG_HATCHERY && candidateType != ScrUnitIds.ZERG_LAIR &&
                 candidateType != ScrUnitIds.ZERG_HIVE) || countLarvae(game, candidate) == 0) {
                continue;
            }

            for (int other = 1; other <= pool.getCapacity(); other++) {
                if (!pool.isAllocated(other)) {
                    continue;
                }

                Unit larva = pool.getUnit(other);

                if (larva.getUnitId() == ScrUnitIds.ZERG_LARVA && larva.getParentUnit() != candidate &&
                    Math.abs(larva.getPositionX() - candidate.getPositionX()) <= SEARCH_RADIUS &&
                    Math.abs(larva.getPositionY() - candidate.getPositionY()) <= SEARCH_RADIUS) {
                    return candidate;
                }
            }
        }

        return null;
    }

    /**
     * Counts allocated Larvae attached to the Hatchery.
     *
     * @param game     the initialized world
     * @param hatchery the parent Hatchery
     * @return the attached Larva count
     */
    private static int countLarvae(@NotNull Game game, @NotNull Unit hatchery) {
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        int count = 0;

        for (int index = 1; index <= pool.getCapacity(); index++) {
            if (pool.isAllocated(index) && pool.getUnit(index).getUnitId() == ScrUnitIds.ZERG_LARVA &&
                pool.getUnit(index).getParentUnit() == hatchery) {
                count++;
            }
        }

        return count;
    }


    @Test
    void press_slot_should_send_unload_all_at_once_when_a_bunker_is_active() {
        RecordingCommandSink sink = new RecordingCommandSink();
        List<RecordingCommandSink.Command> submitted = sink.getCommands();

        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);

            Game game = runtime.getGame();
            Unit bunker = createLoadedTransport(game, ScrUnitIds.TERRAN_BUNKER, ScrUnitIds.TERRAN_MARINE);
            ConsoleModel console = createConsole(game, bunker);
            CommandInput input = new CommandInput(console, sink);

            Assertions.assertEquals(CommandInput.Result.COMMAND, input.pressSlot(findUnloadAllSlot(console.getCommandCard()), true));
            Assertions.assertTrue(console.getMode().getUnitTargetOrder() < 0, "a Bunker does not enter targeting");
        });

        Assertions.assertEquals(1, submitted.size());
        Assertions.assertEquals(UNLOAD_ALL_OPCODE, submitted.getFirst().getOpcode());
        Assertions.assertArrayEquals(new byte[]{QUEUED_FLAG}, submitted.getFirst().getPayload());
    }

    @ParameterizedTest
    @ValueSource(ints = {ScrUnitIds.TERRAN_DROPSHIP, ScrUnitIds.PROTOSS_SHUTTLE})
    void press_slot_should_target_move_unload_at_the_ground_when_a_dropship_or_shuttle_is_active(int transportType) {
        assertUnloadAllTargetsMoveUnload(transportType, false);
    }

    @ParameterizedTest
    @ValueSource(ints = {ScrUnitIds.TERRAN_DROPSHIP, ScrUnitIds.PROTOSS_SHUTTLE})
    void press_slot_should_target_move_unload_at_a_unit_when_a_dropship_or_shuttle_is_active(int transportType) {
        assertUnloadAllTargetsMoveUnload(transportType, true);
    }

    @Test
    void press_slot_should_target_move_unload_at_the_ground_when_an_overlord_is_active() {
        assertUnloadAllTargetsMoveUnload(ScrUnitIds.ZERG_OVERLORD, false);
    }

    @Test
    void press_slot_should_target_move_unload_at_a_unit_when_an_overlord_is_active() {
        assertUnloadAllTargetsMoveUnload(ScrUnitIds.ZERG_OVERLORD, true);
    }

    @Test
    void unload_should_send_unload_command_for_that_cargo_unit() throws IOException {
        // bwrep_04cij unloads one unit (command 0x29) at frame 9248, so the unloading player holds a loaded transport.
        try (ClientSession session = ClientReplayScenario.start(GameTestResources.REPLAY_TVT_PYTHON, UNLOAD_SETUP_FRAME)) {
            Game game = session.getGame();
            UnitPool pool = game.getUnitSystem().getUnitPool();

            Assertions.assertNotNull(pool);

            Unit transport = null;
            Unit loaded = null;

            for (int index = 1; index <= pool.getCapacity() && loaded == null; index++) {
                if (!pool.isAllocated(index)) {
                    continue;
                }

                for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS && loaded == null; slot++) {
                    Unit cargo = game.getUnitTransport().getLoadedUnit(pool.getUnit(index), slot);

                    if (cargo != null) {
                        transport = pool.getUnit(index);
                        loaded    = cargo;
                    }
                }
            }

            Assertions.assertNotNull(loaded, "the replay has a loaded transport before its unload");

            ConsoleModel console = new ConsoleModel(new ScrGameView(game), new ScrPresentation(game));

            console.update(transport.getOwnerIndex(), CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);

            RecordingCommandSink sink = new RecordingCommandSink();
            WorldInterface world = new WorldInterface(console, sink, true);

            world.selectFromPanel(transport, List.of(transport), false, false, false);
            console.getSelection().set(transport.getOwnerIndex(), transport);
            Assertions.assertEquals(CommandInput.Result.COMMAND, world.getCommandInput().unload(loaded));

            RecordingCommandSink.Command unload = sink.getCommands().getLast();

            Assertions.assertEquals(UNLOAD_OPCODE, unload.getOpcode());
            Assertions.assertEquals(Integer.BYTES, unload.getPayload().length);
            Assertions.assertSame(loaded, pool.resolveHandle(readInteger(unload.getPayload(), 0, Integer.BYTES)));
        }
    }

    @Test
    void command_input_encodes_train_cancel_targeting_and_select_commands() throws IOException {
        try (ClientSession session = ClientReplayScenario.start(GameTestResources.REPLAY_TVT_BAEKDUDAEGAN, TVT_FRAME)) {
            Assertions.assertEquals(TVT_FRAME, session.getLastCompletedFrame(), session.getStatus());

            ConsoleModel model = new ConsoleModel(new ScrGameView(session.getGame()), new ScrPresentation(session.getGame()));
            RecordingCommandSink sink = new RecordingCommandSink();
            List<RecordingCommandSink.Command> submitted = sink.getCommands();
            CommandInput input = new CommandInput(model, sink);

            model.update(FIRST_PLAYER, CommandCard.Perspective.PLAYER, REPLAY_PAUSED, GAME_SPEED_SETTING, REPLAY_SPEED);

            Assertions.assertEquals(CommandInput.Result.COMMAND, input.pressSlot(TRAIN_SLOT, false));
            Assertions.assertEquals(TRAIN_OPCODE, submitted.get(0).getOpcode());
            Assertions.assertArrayEquals(new byte[]{(byte) SCV_TYPE, 0}, submitted.get(0).getPayload());
            Assertions.assertEquals(CommandInput.Result.IGNORED, input.pressSlot(EMPTY_SLOT, false));
            Assertions.assertEquals(CommandInput.Result.COMMAND, input.pressSlot(CANCEL_LAST_SLOT, false));
            Assertions.assertEquals(CANCEL_TRAIN_OPCODE, submitted.get(1).getOpcode());
            Assertions.assertArrayEquals(new byte[]{LAST_QUEUE_SLOT, 0}, submitted.get(1).getPayload());

            model.update(SECOND_PLAYER, CommandCard.Perspective.PLAYER, REPLAY_PAUSED, GAME_SPEED_SETTING, REPLAY_SPEED);

            Assertions.assertEquals(CommandInput.Result.COMMAND, input.pressSlot(STOP_SLOT, true));
            Assertions.assertEquals(STOP_OPCODE, submitted.get(2).getOpcode());
            Assertions.assertArrayEquals(new byte[]{QUEUED_FLAG}, submitted.get(2).getPayload());
            Assertions.assertEquals(CommandInput.Result.INTERFACE, input.pressSlot(MOVE_SLOT, false));
            Assertions.assertEquals(FOLLOW_ORDER, model.getMode().getUnitTargetOrder());
            Assertions.assertEquals(MOVE_ORDER, model.getMode().getGroundTargetOrder());

            model.update(SECOND_PLAYER, CommandCard.Perspective.PLAYER, REPLAY_PAUSED, GAME_SPEED_SETTING, REPLAY_SPEED);

            Assertions.assertEquals(CANCEL_TARGETING_SET, model.getCommandCard().getDisplayedSet(), "targeting shows the cancel set");
            Assertions.assertEquals(CommandInput.Result.COMMAND,
                                    input.clickTarget(GROUND_TARGET_X, GROUND_TARGET_Y, null, CommandInput.NO_UNIT_TYPE, false));

            RecordingCommandSink.Command target = submitted.get(3);

            Assertions.assertEquals(TARGETED_ORDER_OPCODE, target.getOpcode());
            Assertions.assertEquals(TARGET_COMMAND_LENGTH, target.getPayload().length);
            Assertions.assertEquals(GROUND_TARGET_X, readInteger(target.getPayload(), 0, Short.BYTES));
            Assertions.assertEquals(NO_TARGET_UNIT_TYPE, Byte.toUnsignedInt(target.getPayload()[TARGET_TYPE_OFFSET]));
            Assertions.assertEquals(MOVE_ORDER, target.getPayload()[TARGET_ORDER_OFFSET]);
            Assertions.assertEquals(NO_TARGET_ORDER, model.getMode().getUnitTargetOrder());
            Assertions.assertEquals(SECOND_PLAYER, submitted.get(3).getPlayer());

            Unit marine = model.getSelection().getActiveUnit();

            Assertions.assertNotNull(marine);
            Assertions.assertEquals(CommandInput.Result.COMMAND, input.select(marine));

            RecordingCommandSink.Command select = submitted.get(4);

            Assertions.assertEquals(SELECT_OPCODE, select.getOpcode());
            Assertions.assertEquals(1, select.getPayload()[SELECTED_COUNT_OFFSET]);

            int handle = readInteger(select.getPayload(), SELECTED_HANDLE_OFFSET, Integer.BYTES);
            UnitPool selectedUnitPool = session.getGame().getUnitSystem().getUnitPool();

            Assertions.assertNotNull(selectedUnitPool);
            Assertions.assertSame(marine, selectedUnitPool.resolveHandle(handle));
        }
    }

    /**
     * Puts a script button first in the shown set's table, so that it takes position 1 unless its state hides it: its state starts hidden, a
     * disabled one shows its texts in place of the strings; a press sends nothing, and a target button enters a targeting mode whose click
     * sends nothing either.
     */
    @Test
    void script_buttons_read_the_local_state_and_send_no_command() throws IOException {
        try (ClientSession session = ClientReplayScenario.start(GameTestResources.REPLAY_TVT_BAEKDUDAEGAN, TVT_FRAME)) {
            Game game = session.getGame();
            ConsoleModel model = new ConsoleModel(new ScrGameView(game), new ScrPresentation(game));
            RecordingCommandSink sink = new RecordingCommandSink();
            CommandInput input = new CommandInput(model, sink);
            HostButtonSets host = game.getGameContext().getHostButtonSets();
            ScrButtonStates states = game.getInterfaceLink().getButtonStates();
            StatText text = StatText.load(session.getStorage(), GAME_LOCALE);
            HotkeyProfile profile = new HotkeyProfile(text, HotkeyProfile.DEVICE, Map.of());

            model.update(FIRST_PLAYER, CommandCard.Perspective.PLAYER, REPLAY_PAUSED, GAME_SPEED_SETTING, REPLAY_SPEED);

            CommandCard card = model.getCommandCard();
            int set = card.getDisplayedSet();
            ButtonDefinition first = card.getButton(SCRIPT_SLOT);

            Assertions.assertNotNull(first);
            installScriptButton(host, set, ButtonAction.SCRIPT);
            model.update(FIRST_PLAYER, CommandCard.Perspective.PLAYER, REPLAY_PAUSED, GAME_SPEED_SETTING, REPLAY_SPEED);

            Assertions.assertSame(first.getAction(), card.getButton(SCRIPT_SLOT).getAction(), "a script state starts hidden");

            states.setState(SCRIPT_VALUE, ScrButtonStates.DISABLED);
            model.update(FIRST_PLAYER, CommandCard.Perspective.PLAYER, REPLAY_PAUSED, GAME_SPEED_SETTING, REPLAY_SPEED);

            ButtonDefinition script = card.getButton(SCRIPT_SLOT);

            Assertions.assertNotNull(script);
            Assertions.assertEquals(ButtonCondition.SCRIPT, script.getCondition());
            Assertions.assertEquals(CommandCard.DISABLED, card.getState(SCRIPT_SLOT));
            Assertions.assertEquals(0, card.getDisabledString(SCRIPT_SLOT));
            Assertions.assertEquals(SCRIPT_DISABLED_TEXT,
                                    ButtonTooltip.build(new ScrGameView(game), text, profile, script, true, card.getDisabledString(SCRIPT_SLOT),
                                                        model.getSelection().getActiveUnit(), FIRST_PLAYER, ScrRaceIds.TERRAN).getText());
            Assertions.assertEquals(CommandInput.Result.IGNORED, input.pressSlot(SCRIPT_SLOT, false));

            states.setState(SCRIPT_VALUE, ScrButtonStates.HIDDEN);
            model.update(FIRST_PLAYER, CommandCard.Perspective.PLAYER, REPLAY_PAUSED, GAME_SPEED_SETTING, REPLAY_SPEED);

            Assertions.assertSame(first.getAction(), card.getButton(SCRIPT_SLOT).getAction(), "a hidden script button leaves its position");

            states.setState(SCRIPT_VALUE, ScrButtonStates.ENABLED);
            model.update(FIRST_PLAYER, CommandCard.Perspective.PLAYER, REPLAY_PAUSED, GAME_SPEED_SETTING, REPLAY_SPEED);

            Assertions.assertEquals(CommandCard.ENABLED, card.getState(SCRIPT_SLOT));
            Assertions.assertEquals(0, card.findTableIndex(set, card.getButton(SCRIPT_SLOT)));
            Assertions.assertEquals(SCRIPT_SLOT, input.findHotkeySlot(profile, SCRIPT_HOTKEY));
            Assertions.assertEquals(SCRIPT_TEXT, ButtonTooltip.build(new ScrGameView(game), text, profile, card.getButton(SCRIPT_SLOT), false, 0,
                                                                     model.getSelection().getActiveUnit(), FIRST_PLAYER,
                                                                     ScrRaceIds.TERRAN).getText());
            Assertions.assertEquals(CommandInput.Result.INTERFACE, input.pressSlot(SCRIPT_SLOT, false, true));
            Assertions.assertEquals(List.of(), sink.getCommands(), "a script button sends no command");
            Assertions.assertFalse(model.getMode().isTargeting());

            installScriptButton(host, set, ButtonAction.SCRIPT_TARGET);
            model.update(FIRST_PLAYER, CommandCard.Perspective.PLAYER, REPLAY_PAUSED, GAME_SPEED_SETTING, REPLAY_SPEED);

            Assertions.assertEquals(CommandInput.Result.INTERFACE, input.pressSlot(SCRIPT_SLOT, true));
            Assertions.assertTrue(model.getMode().isTargeting());
            Assertions.assertEquals(NO_TARGET_ORDER, model.getMode().getUnitTargetOrder());

            ScrButtonInput pressed = model.getMode().getScriptButton();

            Assertions.assertNotNull(pressed);
            Assertions.assertEquals(set, pressed.getSet());
            Assertions.assertEquals(0, pressed.getIndex());
            Assertions.assertEquals(SCRIPT_VALUE, pressed.getActionValue());
            Assertions.assertTrue(pressed.isQueued());

            model.update(FIRST_PLAYER, CommandCard.Perspective.PLAYER, REPLAY_PAUSED, GAME_SPEED_SETTING, REPLAY_SPEED);

            Assertions.assertEquals(CANCEL_TARGETING_SET, card.getDisplayedSet(), "script targeting shows the cancel set");
            Assertions.assertEquals(CommandInput.Result.COMMAND,
                                    input.clickTarget(GROUND_TARGET_X, GROUND_TARGET_Y, null, CommandInput.NO_UNIT_TYPE, false));
            Assertions.assertEquals(List.of(), sink.getCommands(), "a script target sends no command");
            Assertions.assertFalse(model.getMode().isTargeting());
            Assertions.assertNull(model.getMode().getScriptButton());

            states.requestButtonSet(SCRIPT_CHANGED_SET);
            model.update(FIRST_PLAYER, CommandCard.Perspective.PLAYER, REPLAY_PAUSED, GAME_SPEED_SETTING, REPLAY_SPEED);

            Assertions.assertEquals(SCRIPT_CHANGED_SET, card.getDisplayedSet(), "a requested set shows as Change Buttons shows it");
        }
    }

    /**
     * Copies the installed table into the game's table on the first call, then puts a script button with the action first in a set.
     */
    private static void installScriptButton(@NotNull HostButtonSets host, int set, @NotNull ButtonAction action) {
        if (!host.isReplaced()) {
            HostButtonSets.Button[][] installed = ButtonSetTable.createHostButtons();

            for (int index = 0; index < HostButtonSets.SET_COUNT; index++) {
                host.replace(index, installed[index].length, installed[index], ButtonSetTable.getConnectedSet(index));
            }
        }

        HostButtonSets.Button[] buttons = host.getButtons(set);

        Assertions.assertNotNull(buttons);

        boolean replacing = buttons[0].getCondition() == ButtonCondition.SCRIPT.getNativeAddress();
        HostButtonSets.Button[] changed = new HostButtonSets.Button[replacing ? buttons.length : buttons.length + 1];

        changed[0] = new HostButtonSets.Button(1, SCRIPT_ICON, ButtonCondition.SCRIPT.getNativeAddress(), SCRIPT_VALUE, action.getNativeAddress(),
                                               SCRIPT_VALUE, 0, 0, SCRIPT_HOTKEY + "\0" + SCRIPT_TEXT, SCRIPT_DISABLED_TEXT);
        System.arraycopy(buttons, replacing ? 1 : 0, changed, 1, changed.length - 1);
        host.replace(set, changed.length, changed, host.getConnectedSet(set));
    }

    @Test
    void select_larva_selects_only_larvae_of_active_hatchery() throws IOException {
        Path installationDirectory = ExternalInput.INSTALLATION.getPath();

        Assertions.assertNotNull(installationDirectory);

        try (ClientSession session = ClientSession.startCampaign(installationDirectory,
                                                                 MISSION, HUMAN_NAME, GameSpeed.FASTEST, ClientSessionScenario.GAME_SEED,
                                                                 GAME_LOCALE)) {
            LiveGame liveGame = session.getLiveGame();

            Assertions.assertNotNull(liveGame);
            Assertions.assertEquals(ClientSessionScenario.GAME_SEED, liveGame.getSeed());
            Assertions.assertEquals(Integer.toUnsignedLong(ClientSessionScenario.GAME_SEED), session.getReplay().getHeader().getRandomSeed());

            session.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
            session.startReplay();

            for (int frame = 0; frame < SETTLE_FRAMES; frame++) {
                session.stepFrame(false);
            }

            Game game = session.getGame();
            int player = session.getLocalPlayer();
            Unit hatchery = findHatcheryWithForeignLarvae(game, player);

            Assertions.assertNotNull(hatchery, "a local Hatchery has another Hatchery's Larvae in its search box");

            int expected = Math.min(countLarvae(game, hatchery), SELECTION_CAPACITY);

            ConsoleModel console = new ConsoleModel(new ScrGameView(game), new ScrPresentation(game));
            WorldInterface world = new WorldInterface(console, (owner, opcode, payload) -> session.submitLocalCommand(owner, opcode, payload), true);

            console.update(player, CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);
            world.getCommandInput().select(hatchery);
            // The local selection changes before the command executes.
            console.update(player, CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);

            Assertions.assertSame(hatchery, console.getSelection().getActiveUnit());

            for (int frame = 0; frame < COMMAND_FRAMES; frame++) {
                session.stepFrame(false);
            }

            console.update(player, CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);

            Assertions.assertSame(hatchery, console.getSelection().getActiveUnit());

            Assertions.assertEquals(CommandInput.Result.COMMAND, world.getCommandInput().pressSlot(SELECT_LARVA_SLOT, false));
            console.update(player, CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);

            assertLarvaeOf(hatchery, console.getSelection(), expected);

            for (int frame = 0; frame < COMMAND_FRAMES; frame++) {
                session.stepFrame(false);
            }

            console.update(player, CommandCard.Perspective.PLAYER, true, GAME_SPEED_SETTING, REPLAY_SPEED);

            assertLarvaeOf(hatchery, console.getSelection(), expected);
        }
    }
}
