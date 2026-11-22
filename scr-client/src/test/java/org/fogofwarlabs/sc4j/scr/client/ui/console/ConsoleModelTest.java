package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.client.ClientSession;
import org.fogofwarlabs.sc4j.scr.client.ui.command.*;
import org.fogofwarlabs.sc4j.scr.client.ui.status.UnitStatus;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.client.world.ScrPresentation;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.test.GameTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Tests the console model against real replay states: the recorded selections, the status and the command cards with their
 * requirements and button set changes.
 */
@RequiresInstallation
final class ConsoleModelTest {
    private static final long TVT_FRAME = 6000;
    private static final long PVZ_FRAME = 9000;
    private static final long ZVZ_FRAME = 8000;
    private static final int FIRST_PLAYER = 0;
    private static final int SECOND_PLAYER = 1;
    private static final int FOURTH_PLAYER = 3;
    /**
     * The replay control inputs of the command card updates: paused, the fastest speed setting and no multiplier.
     */
    private static final boolean REPLAY_PAUSED = true;
    private static final int SPEED_SETTING = 4;
    private static final int REPLAY_SPEED = 1;

    private static final int COMMAND_CENTER = 106;
    private static final int SCV = 7;
    private static final int MARINES_SELECTED = 4;
    private static final int TERRAN_RACE = 1;
    /**
     * The disabled string of the Command Center's first add-on button, which slot {@code 6} shows.
     */
    private static final int ADDON_DISABLED_STRING = 708;
    private static final int ADDON_SLOT = 6;
    /**
     * The requirement results: enabled, hidden and disabled.
     */
    private static final int ENABLED_REQUIREMENT = 1;
    private static final int HIDDEN_REQUIREMENT = 0;
    private static final int DISABLED_REQUIREMENT = -1;
    /**
     * The requirement failure codes: another player's unit, a missing Academy and the producer mismatch of the first group.
     */
    private static final int OWNER_FAILURE = 1;
    private static final int MISSING_BUILDING_FAILURE = 8;
    private static final int PRODUCER_FAILURE = 0x19;
    /**
     * The replay set shown while a replay is paused.
     */
    private static final int PAUSED_REPLAY_SET = 248;
    private static final int LARVA_SET = 35;
    private static final int ZERG_GROUND_UNITS_SELECTED = 3;
    /**
     * The mixed Zerg ground selection's burrow set.
     */
    private static final int BURROW_SET = 247;
    /**
     * The Terran basic structures set an SCV's Build button changes to.
     */
    private static final int TERRAN_BUILD_SET = 239;

    private static final int BUILD_SLOT = 6;
    private static final int BACK_SLOT = 8;
    /**
     * The local selection slots of the four selected Marines, and the pool index of an empty slot.
     */
    private static final int FIRST_SLOT = 0;
    private static final int SECOND_SLOT = 1;
    private static final int THIRD_SLOT = 2;
    private static final int FOURTH_SLOT = 3;
    private static final int EMPTY_POOL_INDEX = 0;
    /**
     * The frames the retired-unit case steps at most until one of the written units is retired.
     */
    private static final int RETIREMENT_SEARCH_FRAMES = 6000;

    /**
     * Loads the recorded replay and steps it to the required completed frame.
     *
     * @param replay the corpus-relative replay path
     * @param frame  the required completed frame
     * @return the running session
     * @throws IOException if the installed data or replay cannot be read
     */
    private static @NotNull ClientSession loadReplayAtFrame(@NotNull String replay, long frame) throws IOException {
        ClientSession session = ClientSession.load(ExternalInput.INSTALLATION.getPath(), GameTestResources.getPath(replay));

        session.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
        session.startReplay();

        while (session.getLastCompletedFrame() < frame && session.stepFrame(false)) {
        }

        Assertions.assertEquals(frame, session.getLastCompletedFrame(), session.getStatus());

        return session;
    }

    /**
     * Formats visible command actions and disabled markers for fixture assertions.
     *
     * @param card the current command card
     * @return the slot descriptions
     */
    private static @NotNull String formatCommandCard(@NotNull CommandCard card) {
        StringBuilder text = new StringBuilder();

        for (int slot = 0; slot < CommandCard.SLOT_COUNT; slot++) {
            ButtonDefinition button = card.getButton(slot);

            text.append(slot == 0 ? "" : " ")
                .append(button == null ? "-" : button.getAction() + (card.getState(slot) == CommandCard.DISABLED ? "!" : ""));
        }

        return text.toString();
    }

    /**
     * Updates the console model of a player with the paused replay controls.
     *
     * @param model       the console model
     * @param player      the shown player
     * @param perspective whose command card is shown
     */
    private static void update(@NotNull ConsoleModel model, int player, CommandCard.@NotNull Perspective perspective) {
        model.update(player, perspective, REPLAY_PAUSED, SPEED_SETTING, REPLAY_SPEED);
    }

    /**
     * Refreshes the console model of a player's command card with the paused replay controls.
     *
     * @param model  the console model
     * @param player the shown player
     */
    private static void refresh(@NotNull ConsoleModel model, int player) {
        model.refresh(player, CommandCard.Perspective.PLAYER, REPLAY_PAUSED, SPEED_SETTING, REPLAY_SPEED);
    }

    @Test
    void console_shows_recorded_selection_status_and_command_card() throws IOException {
        try (ClientSession session = loadReplayAtFrame(GameTestResources.REPLAY_TVT_BAEKDUDAEGAN, TVT_FRAME)) {
            ConsoleModel model = new ConsoleModel(new ScrGameView(session.getGame()), new ScrPresentation(session.getGame()));

            update(model, FIRST_PLAYER, CommandCard.Perspective.PLAYER);

            Assertions.assertEquals(1, model.getSelection().getCount());

            Unit activeUnit = model.getSelection().getActiveUnit();

            Assertions.assertNotNull(activeUnit);
            Assertions.assertEquals(COMMAND_CENTER, activeUnit.getUnitId());
            Assertions.assertEquals(COMMAND_CENTER, model.getCommandCard().getDisplayedSet());
            // The training Command Center hides its add-on and lift-off buttons and offers Cancel Last.
            Assertions.assertEquals("TRAIN - - - - SET_RALLY_POINT - - CANCEL_LAST", formatCommandCard(model.getCommandCard()));

            UnitStatus status = model.getActiveStatus();

            Assertions.assertNotNull(status);
            Assertions.assertTrue(status.getQueueCount() > 0);
            Assertions.assertEquals(SCV, status.getQueuedType(0));
            Assertions.assertTrue(status.getProgress() >= 0 && status.getProgress() <= status.getProgressTotal());
            Assertions.assertEquals(TERRAN_RACE, model.getRace());
            Assertions.assertTrue(model.getSupplyUsed() > 0 && model.getSupplyUsed() <= model.getSupplyProvided());

            update(model, SECOND_PLAYER, CommandCard.Perspective.PLAYER);

            Assertions.assertEquals(MARINES_SELECTED, model.getSelection().getCount());
            Assertions.assertEquals("MOVE STOP ATTACK PATROL HOLD_POSITION - STIM_PACKS! - -", formatCommandCard(model.getCommandCard()));

            update(model, SECOND_PLAYER, CommandCard.Perspective.NATIVE_REPLAY);

            Assertions.assertEquals(PAUSED_REPLAY_SET, model.getCommandCard().getDisplayedSet());
            Assertions.assertEquals("- - - - - - REPLAY_PLAY_PAUSE REPLAY_SLOW_DOWN REPLAY_SPEED_UP", formatCommandCard(model.getCommandCard()));
        }
    }

    @Test
    void requirements_enable_disable_and_hide_buttons_for_single_units() throws IOException {
        try (ClientSession session = loadReplayAtFrame(GameTestResources.REPLAY_TVT_BAEKDUDAEGAN, TVT_FRAME)) {
            Game game = session.getGame();
            ConsoleModel model = new ConsoleModel(new ScrGameView(game), new ScrPresentation(game));
            UnitPool unitPool = game.getUnitSystem().getUnitPool();
            Unit commandCenter = null;

            Assertions.assertNotNull(unitPool);

            for (int index = 1; index <= unitPool.getCapacity(); index++) {
                if (!unitPool.isAllocated(index)) {
                    continue;
                }

                Unit unit = unitPool.getUnit(index);

                if (unit.getOwnerIndex() == SECOND_PLAYER && unit.getUnitId() == COMMAND_CENTER && (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
                    commandCenter = unit;

                    break;
                }
            }

            if (commandCenter == null) {
                throw Exceptions.newAssertionError("No unit of type {0} for player {1}", COMMAND_CENTER, SECOND_PLAYER);
            }

            model.getSelection().set(SECOND_PLAYER, commandCenter);
            refresh(model, SECOND_PLAYER);

            CommandCard card = model.getCommandCard();

            Assertions.assertEquals("TRAIN - - - - SET_RALLY_POINT BUILD_ADDON! BUILD_ADDON! LIFT_OFF", formatCommandCard(card));
            Assertions.assertEquals(ADDON_DISABLED_STRING, card.getDisabledString(ADDON_SLOT));

            model.getSelection().set(FIRST_PLAYER, commandCenter);
            refresh(model, FIRST_PLAYER);

            Assertions.assertEquals("- - - - - - - - -", formatCommandCard(model.getCommandCard()), "another player's unit hides all buttons");

            RequirementEvaluator requirements = new RequirementEvaluator(new ScrGameView(game));

            Assertions.assertEquals(ENABLED_REQUIREMENT, requirements.evaluateUnit(SCV, SECOND_PLAYER, commandCenter));
            Assertions.assertEquals(HIDDEN_REQUIREMENT, requirements.evaluateUnit(SCV, FIRST_PLAYER, commandCenter));
            Assertions.assertEquals(OWNER_FAILURE, requirements.getFailureCode());
            Assertions.assertEquals(DISABLED_REQUIREMENT,
                                    requirements.evaluateUnit(ScrUnitIds.TERRAN_COMSAT_STATION, SECOND_PLAYER, commandCenter));
            Assertions.assertEquals(MISSING_BUILDING_FAILURE, requirements.getFailureCode());
            // A Marine from a Command Center: the producer mismatch of the first group ends in failure 0x19, disabled.
            Assertions.assertEquals(DISABLED_REQUIREMENT, requirements.evaluateUnit(ScrUnitIds.TERRAN_MARINE, SECOND_PLAYER, commandCenter));
            Assertions.assertEquals(PRODUCER_FAILURE, requirements.getFailureCode());
        }
    }

    @Test
    void console_shows_larva_morph_set_and_mixed_zerg_burrow_set() throws IOException {
        try (ClientSession session = loadReplayAtFrame(GameTestResources.REPLAY_PVZ_GRAND_LINE, PVZ_FRAME)) {
            ConsoleModel model = new ConsoleModel(new ScrGameView(session.getGame()), new ScrPresentation(session.getGame()));

            update(model, FOURTH_PLAYER, CommandCard.Perspective.PLAYER);

            Assertions.assertEquals(LARVA_SET, model.getCommandCard().getDisplayedSet());
            Assertions.assertEquals("UNIT_MORPH UNIT_MORPH UNIT_MORPH UNIT_MORPH UNIT_MORPH UNIT_MORPH UNIT_MORPH! UNIT_MORPH! UNIT_MORPH!",
                                    formatCommandCard(model.getCommandCard()));
        }

        try (ClientSession session = loadReplayAtFrame(GameTestResources.REPLAY_ZVZ_HEARTBREAK_RIDGE, ZVZ_FRAME)) {
            ConsoleModel model = new ConsoleModel(new ScrGameView(session.getGame()), new ScrPresentation(session.getGame()));

            update(model, SECOND_PLAYER, CommandCard.Perspective.PLAYER);

            Assertions.assertEquals(ZERG_GROUND_UNITS_SELECTED, model.getSelection().getCount());
            // Drones and Zerglings: the mixed Zerg ground selection uses the burrow set 247.
            Assertions.assertEquals(BURROW_SET, model.getCommandCard().getDisplayedSet());
        }
    }

    @Test
    void worker_build_buttons_change_sets_locally_without_commands() throws IOException {
        try (ClientSession session = loadReplayAtFrame(GameTestResources.REPLAY_TVT_BAEKDUDAEGAN, TVT_FRAME)) {
            Game game = session.getGame();
            ConsoleModel model = new ConsoleModel(new ScrGameView(game), new ScrPresentation(game));
            CommandInput input = new CommandInput(model, (player, opcode, payload) -> Assertions.fail("no command expected"));
            Unit worker = null;
            UnitPool pool = game.getUnitSystem().getUnitPool();

            Assertions.assertNotNull(pool);

            for (int index = 1; index <= pool.getCapacity() && worker == null; index++) {
                Unit unit = pool.isAllocated(index) ? pool.getUnit(index) : null;

                if (unit != null && unit.getOwnerIndex() == SECOND_PLAYER && unit.getUnitId() == SCV && unit.getCurrentButtonSet() == SCV &&
                    unit.getPrimaryOrder() != ScrOrderIds.CONSTRUCTING_BUILDING) {
                    worker = unit;
                }
            }

            Assertions.assertNotNull(worker);

            model.getSelection().set(SECOND_PLAYER, worker);
            refresh(model, SECOND_PLAYER);

            ButtonDefinition buildButton = model.getCommandCard().getButton(BUILD_SLOT);

            Assertions.assertNotNull(buildButton);
            Assertions.assertEquals(ButtonAction.CHANGE_BUTTONS, buildButton.getAction());
            Assertions.assertEquals(CommandInput.Result.INTERFACE, input.pressSlot(BUILD_SLOT, false));

            refresh(model, SECOND_PLAYER);

            Assertions.assertEquals(TERRAN_BUILD_SET, model.getCommandCard().getDisplayedSet());

            ButtonDefinition workerButton = model.getCommandCard().getButton(0);

            Assertions.assertNotNull(workerButton);
            Assertions.assertEquals(ButtonAction.TERRAN_BUILD, workerButton.getAction());
            Assertions.assertEquals(CommandInput.Result.INTERFACE, input.pressSlot(BACK_SLOT, false));

            refresh(model, SECOND_PLAYER);

            Assertions.assertEquals(SCV, model.getCommandCard().getDisplayedSet());
        }
    }

    @Test
    void remove_unit_should_move_units_before_the_first_empty_slot_and_keep_later_slots() throws IOException {
        try (ClientSession session = loadReplayAtFrame(GameTestResources.REPLAY_TVT_BAEKDUDAEGAN, TVT_FRAME)) {
            ConsoleModel model = new ConsoleModel(new ScrGameView(session.getGame()), new ScrPresentation(session.getGame()));

            update(model, SECOND_PLAYER, CommandCard.Perspective.PLAYER);

            Assertions.assertEquals(MARINES_SELECTED, model.getSelection().getCount());

            Unit first = model.getSelection().getUnit(FIRST_SLOT);
            int third = model.getSelectedPoolIndex(THIRD_SLOT);
            int fourth = model.getSelectedPoolIndex(FOURTH_SLOT);

            Assertions.assertNotNull(first);

            // The first unit is alone before the empty second slot: it leaves, and the units after the empty slot keep their slots.
            model.setSelectedPoolIndexRaw(SECOND_SLOT, EMPTY_POOL_INDEX);
            model.removeUnit(first);

            Assertions.assertEquals(EMPTY_POOL_INDEX, model.getSelectedPoolIndex(FIRST_SLOT));
            Assertions.assertEquals(EMPTY_POOL_INDEX, model.getSelectedPoolIndex(SECOND_SLOT));
            Assertions.assertEquals(third, model.getSelectedPoolIndex(THIRD_SLOT));
            Assertions.assertEquals(fourth, model.getSelectedPoolIndex(FOURTH_SLOT));
        }
    }

    @Test
    void update_should_keep_retired_units_in_the_local_selection_array() throws IOException {
        try (ClientSession session = loadReplayAtFrame(GameTestResources.REPLAY_TVT_BAEKDUDAEGAN, TVT_FRAME)) {
            Game game = session.getGame();
            ConsoleModel model = new ConsoleModel(new ScrGameView(game), new ScrPresentation(game));
            UnitPool pool = game.getUnitSystem().getUnitPool();
            int[] written = new int[InterfaceSelection.CAPACITY];
            int slot = FIRST_SLOT;

            Assertions.assertNotNull(pool);

            // The second slot stays empty; the other slots get Marines and SCVs, which die in the fights that follow.
            for (int index = 1; index <= pool.getCapacity() && slot < written.length; index++) {
                Unit unit = pool.getUnit(index);

                if (pool.isAllocated(index) && (unit.getUnitId() == SCV || unit.getUnitId() == ScrUnitIds.TERRAN_MARINE)) {
                    written[slot] = index;

                    slot = slot == FIRST_SLOT ? THIRD_SLOT : slot + 1;
                }
            }

            for (int position = 0; position < written.length; position++) {
                model.setSelectedPoolIndexRaw(position, written[position]);
            }

            boolean retired = false;

            while (!retired && session.getLastCompletedFrame() < TVT_FRAME + RETIREMENT_SEARCH_FRAMES && session.stepFrame(false)) {
                for (int poolIndex : written) {
                    retired |= poolIndex != EMPTY_POOL_INDEX && !pool.isAllocated(poolIndex);
                }
            }

            Assertions.assertTrue(retired, "a written unit is retired");

            update(model, FIRST_PLAYER, CommandCard.Perspective.PLAYER);

            for (int position = 0; position < written.length; position++) {
                Assertions.assertEquals(written[position], model.getSelectedPoolIndex(position), "slot " + position);
            }
        }
    }
}
