package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.constant.TriggerUnitGroups;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerUnits;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.map.MapStringSection;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerRelations;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Checks that the public trigger routines give the results of the trigger actions and conditions that call them, each run through a trigger pass
 * of the live game and called directly on the same state.
 */
@RequiresInstallation
final class TriggerRoutineTest {
    private static final int FRAME_DURATION = GameSpeed.FASTEST_DURATION;
    /**
     * The replay mode argument of a live game.
     */
    private static final int LIVE_GAME = 0;
    /**
     * The player whose triggers run: the local human player of the live game.
     */
    private static final int PLAYER = RunningGameScenario.HUMAN;
    /**
     * The switch that the condition records set when their condition holds.
     */
    private static final int RESULT_SWITCH = 255;
    /**
     * The size of the short location section.
     */
    private static final int SHORT_LOCATION_SECTION_SIZE = 0x500;
    /**
     * The half extent of the locations around a spot.
     */
    private static final int SPOT_HALF_EXTENT = 48;
    /**
     * The distance between the two spots of the unit tests.
     */
    private static final int SPOT_DISTANCE = 256;

    private static final int DEATHS_AMOUNT = 5;
    private static final int SCORE_AMOUNT = 10;
    private static final int CUSTOM_SCORE = 7;
    private static final int COUNTDOWN_SECONDS = 100;
    private static final int COUNTDOWN_ADDED_SECONDS = 5;
    private static final int COUNTDOWN_OVERFLOW_SECONDS = 400000;
    private static final int COUNTDOWN_MAXIMUM = 359999;
    private static final int TEST_SWITCH = 9;
    private static final int GREED_GOAL = 500;
    private static final int PORTRAIT_MILLISECONDS = 2500;
    private static final int PING_X = 640;
    private static final int PING_Y = 480;
    private static final int MINERAL_AMOUNT = 1234;
    private static final int OTHER_MINERAL_AMOUNT = 999;
    private static final int HANGAR_STEP = 2;
    private static final int CARRIER_CAPACITY = 4;
    private static final int HOST_CLOCK_MILLISECONDS = 1000;
    /**
     * The shared vision script {@code +Vi0} that a human player may start, and the computer-only script {@code Rscu}.
     */
    private static final int SHARED_VISION_SCRIPT = 0x3069562B;
    private static final int RESCUE_SCRIPT = 0x75637352;

    private static @NotNull TriggerContext requireContext(@NotNull Game game) {
        TriggerContext context = game.getTriggerContext();

        Assertions.assertNotNull(context);

        return context;
    }

    private static @NotNull MapStringSection requireStrings(@NotNull Game game) {
        MapStringSection strings = game.getInterfaceLink().getMessageLines().getMapStringSection();

        Assertions.assertNotNull(strings);

        return strings;
    }

    /**
     * Runs one trigger pass of only the supplied records, inside a command dispatch.
     *
     * @param runtime the live game
     * @param records the records, owned by {@link #PLAYER}
     */
    private static void runPass(@NotNull ReplayRuntime runtime, byte @NotNull [] records) {
        Game game = runtime.getGame();
        TriggerContext context = requireContext(game);

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            context.clearPlayerRecords(player);
        }

        context.loadRecords(records);
        context.setCycleCountdown(0);

        RunningGameScenario.runInCommandDispatch(runtime, () -> ScrTriggerSystem.update(context,
                                                                                        FRAME_DURATION,
                                                                                        LIVE_GAME,
                                                                                        0, 0,
                                                                                        game,
                                                                                        RunningGameScenario.INSTRUCTION_LIMIT));
    }

    private static @NotNull ByteBuffer createRecord(byte @NotNull [] records) {
        ByteBuffer record = TriggerRecordScenario.createRecord(records, 0);

        record.put(TriggerRecordCodec.OWNERS_OFFSET + PLAYER, (byte) 1);

        return record;
    }

    /**
     * Tests one condition through a trigger pass: the record sets {@link #RESULT_SWITCH} when the condition holds.
     */
    private static boolean testCondition(@NotNull ReplayRuntime runtime,
                                         int type,
                                         int location,
                                         int selector,
                                         int amount,
                                         int unitType,
                                         int comparison,
                                         int resourceType) {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer record = createRecord(records);

        record.putInt(TriggerRecordCodec.CONDITION_LOCATION_FIELD, location);
        record.putInt(TriggerRecordCodec.CONDITION_PLAYER_FIELD, selector);
        record.putInt(TriggerRecordCodec.CONDITION_AMOUNT_FIELD, amount);
        record.putShort(TriggerRecordCodec.CONDITION_UNIT_FIELD, (short) unitType);
        record.put(TriggerRecordCodec.CONDITION_COMPARISON_FIELD, (byte) comparison);
        record.put(TriggerRecordCodec.CONDITION_TYPE_FIELD, (byte) type);
        record.put(TriggerRecordCodec.CONDITION_RESOURCE_FIELD, (byte) resourceType);
        writeAction(record, TriggerActionIds.SET_SWITCH, 0, 0, 0, 0, 0, RESULT_SWITCH, 0, TriggerModifiers.SET, 0);

        TriggerContext context = requireContext(runtime.getGame());

        context.setSwitch(RESULT_SWITCH, false);
        runPass(runtime, records);

        return context.isSwitchSet(RESULT_SWITCH);
    }

    /**
     * Runs one action through a trigger pass, under an Always condition.
     */
    private static void runAction(@NotNull ReplayRuntime runtime,
                                  int type,
                                  int location,
                                  int stringIndex, int soundIndex,
                                  int duration,
                                  int selector,
                                  int secondary,
                                  int unitType,
                                  int modifier,
                                  int flags) {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer record = createRecord(records);

        record.put(TriggerRecordCodec.CONDITION_TYPE_FIELD, (byte) TriggerConditionIds.ALWAYS);
        writeAction(record, type, location, stringIndex, soundIndex, duration, selector, secondary, unitType, modifier, flags);
        runPass(runtime, records);
    }

    private static void writeAction(@NotNull ByteBuffer record,
                                    int type,
                                    int location,
                                    int stringIndex, int soundIndex,
                                    int duration,
                                    int selector,
                                    int secondary,
                                    int unitType,
                                    int modifier,
                                    int flags) {
        int base = TriggerRecordCodec.ACTIONS_OFFSET;

        record.putInt(base + TriggerRecordCodec.ACTION_LOCATION_FIELD, location);
        record.putInt(base + TriggerRecordCodec.ACTION_STRING_FIELD, stringIndex);
        record.putInt(base + TriggerRecordCodec.ACTION_SOUND_FIELD, soundIndex);
        record.putInt(base + TriggerRecordCodec.ACTION_DURATION_FIELD, duration);
        record.putInt(base + TriggerRecordCodec.ACTION_PLAYER_FIELD, selector);
        record.putInt(base + TriggerRecordCodec.ACTION_SECONDARY_FIELD, secondary);
        record.putShort(base + TriggerRecordCodec.ACTION_UNIT_FIELD, (short) unitType);
        record.put(base + TriggerRecordCodec.ACTION_TYPE_FIELD, (byte) type);
        record.put(base + TriggerRecordCodec.ACTION_MODIFIER_FIELD, (byte) modifier);
        record.put(base + TriggerRecordCodec.ACTION_FLAGS_FIELD, (byte) flags);
    }

    /**
     * Loads locations {@code 0} and {@code 1}: location {@code 0} covers the whole map and location {@code 1} a spot.
     */
    private static void loadLocations(@NotNull Game game, int spotX, int spotY) {
        ByteBuffer buffer = ByteBuffer.allocate(SHORT_LOCATION_SECTION_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(0, 0);
        buffer.putInt(Integer.BYTES, 0);
        buffer.putInt(2 * Integer.BYTES, game.getMap().getWidth() * TerrainTileset.TILE_SIZE);
        buffer.putInt(3 * Integer.BYTES, game.getMap().getHeight() * TerrainTileset.TILE_SIZE);
        buffer.putInt(TriggerContext.LOCATION_RECORD_SIZE, spotX - SPOT_HALF_EXTENT);
        buffer.putInt(TriggerContext.LOCATION_RECORD_SIZE + Integer.BYTES, spotY - SPOT_HALF_EXTENT);
        buffer.putInt(TriggerContext.LOCATION_RECORD_SIZE + 2 * Integer.BYTES, spotX + SPOT_HALF_EXTENT);
        buffer.putInt(TriggerContext.LOCATION_RECORD_SIZE + 3 * Integer.BYTES, spotY + SPOT_HALF_EXTENT);
        requireContext(game).loadLocations(buffer.array());
    }

    private static int findEvent(@NotNull TriggerEvents events, int kind) {
        for (int index = 0; index < events.getCount(); index++) {
            if (events.getKind(index) == kind) {
                return index;
            }
        }

        return -1;
    }

    private static int countLines(@NotNull MessageLines lines, @NotNull String text) {
        int count = 0;

        for (int line = 0; line < MessageLines.ROTATING_LINES; line++) {
            if (new String(lines.getLine(line), StandardCharsets.UTF_8).equals(text)) {
                count++;
            }
        }

        return count;
    }

    private static int countUnits(@NotNull Game game, int owner, int unitType) {
        UnitPool pool = game.getUnitSystem().getUnitPool();
        int count = 0;

        Assertions.assertNotNull(pool);

        for (int index = pool.getFirstOwnerIndex(owner); index != 0; index = pool.getNextOwnerIndex(index)) {
            if (pool.getUnit(index).getUnitId() == unitType && !ComputerUnits.isDeadOrHidden(pool.getUnit(index))) {
                count++;
            }
        }

        return count;
    }

    private static @Nullable Unit findMineralField(@NotNull Game game) {
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        for (int index = pool.getFirstOwnerIndex(PlayerTable.NEUTRAL_PLAYER_ID); index != 0; index = pool.getNextOwnerIndex(index)) {
            if (game.requireGameData().getUnitTypes().isMineralField(pool.getUnit(index).getUnitId())) {
                return pool.getUnit(index);
            }
        }

        return null;
    }


    @Test
    void count_and_counter_routines_match_the_conditions() throws IOException {
        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.startLiveGame(gameData);
            Game game = runtime.getGame();
            TriggerContext context = requireContext(game);

            loadLocations(game, PING_X, PING_Y);

            for (int unitType : new int[]{TriggerUnitGroups.ANY_UNIT, ScrUnitIds.TERRAN_SCV, ScrUnitIds.TERRAN_COMMAND_CENTER}) {
                int completed = context.countCommandedUnits(PLAYER, unitType, true);
                int all = context.countCommandedUnits(PLAYER, unitType, false);
                int brought = context.countUnitsAt(PLAYER, unitType, 0, true);

                Assertions.assertTrue(completed > 0, "The player commands units of type " + unitType);
                Assertions.assertTrue(testCondition(runtime, TriggerConditionIds.COMMAND, 0, TriggerPlayerSelectors.CURRENT_PLAYER, completed,
                                                    unitType, TriggerModifiers.EXACTLY, 0));
                Assertions.assertFalse(testCondition(runtime, TriggerConditionIds.COMMAND, 0, TriggerPlayerSelectors.CURRENT_PLAYER, completed + 1,
                                                     unitType, TriggerModifiers.EXACTLY, 0));
                Assertions.assertTrue(testCondition(runtime, TriggerConditionIds.COMMAND, 0, TriggerPlayerSelectors.CURRENT_PLAYER, all,
                                                    unitType, TriggerModifiers.AT_MOST, 0));
                Assertions.assertFalse(testCondition(runtime, TriggerConditionIds.COMMAND, 0, TriggerPlayerSelectors.CURRENT_PLAYER, all - 1,
                                                     unitType, TriggerModifiers.AT_MOST, 0));
                Assertions.assertTrue(testCondition(runtime, TriggerConditionIds.BRING, 1, TriggerPlayerSelectors.CURRENT_PLAYER, brought,
                                                    unitType, TriggerModifiers.EXACTLY, 0));
                Assertions.assertFalse(testCondition(runtime, TriggerConditionIds.BRING, 1, TriggerPlayerSelectors.CURRENT_PLAYER, brought + 1,
                                                     unitType, TriggerModifiers.EXACTLY, 0));
            }

            Player player = game.getPlayerTable().requirePlayer(PLAYER);
            int minerals = TriggerConditions.readResources(player, TriggerScoreActionsUtils.RESOURCE_MINERALS);

            Assertions.assertTrue(testCondition(runtime, TriggerConditionIds.ACCUMULATE, 0, TriggerPlayerSelectors.CURRENT_PLAYER, minerals, 0,
                                                TriggerModifiers.EXACTLY, TriggerScoreActionsUtils.RESOURCE_MINERALS));
            Assertions.assertFalse(testCondition(runtime, TriggerConditionIds.ACCUMULATE, 0, TriggerPlayerSelectors.CURRENT_PLAYER, minerals + 1, 0,
                                                 TriggerModifiers.EXACTLY, TriggerScoreActionsUtils.RESOURCE_MINERALS));

            context.setDeaths(PLAYER, ScrUnitIds.TERRAN_MARINE, TriggerModifiers.ADD, DEATHS_AMOUNT);

            int deaths = context.getDeaths(PLAYER, ScrUnitIds.TERRAN_MARINE);

            Assertions.assertTrue(testCondition(runtime, TriggerConditionIds.DEATHS, 0, TriggerPlayerSelectors.CURRENT_PLAYER, deaths,
                                                ScrUnitIds.TERRAN_MARINE, TriggerModifiers.EXACTLY, 0));
            Assertions.assertFalse(testCondition(runtime, TriggerConditionIds.DEATHS, 0, TriggerPlayerSelectors.CURRENT_PLAYER, deaths + 1,
                                                 ScrUnitIds.TERRAN_MARINE, TriggerModifiers.EXACTLY, 0));

            int kills = context.getKills(PLAYER, TriggerUnitGroups.ANY_UNIT);

            Assertions.assertTrue(testCondition(runtime, TriggerConditionIds.KILL, 0, TriggerPlayerSelectors.CURRENT_PLAYER, kills,
                                                TriggerUnitGroups.ANY_UNIT, TriggerModifiers.EXACTLY, 0));

            context.setScore(PLAYER, CUSTOM_SCORE, TriggerModifiers.ADD, SCORE_AMOUNT);

            for (int scoreType = 0; scoreType <= CUSTOM_SCORE; scoreType++) {
                int score = context.getScore(PLAYER, scoreType);

                Assertions.assertTrue(testCondition(runtime, TriggerConditionIds.SCORE, 0, TriggerPlayerSelectors.CURRENT_PLAYER, score, 0,
                                                    TriggerModifiers.EXACTLY, scoreType));
                Assertions.assertFalse(testCondition(runtime, TriggerConditionIds.SCORE, 0, TriggerPlayerSelectors.CURRENT_PLAYER, score + 1, 0,
                                                     TriggerModifiers.EXACTLY, scoreType));
            }

            int opponents = context.countOpponents(PLAYER);

            Assertions.assertTrue(opponents > 0);
            Assertions.assertTrue(testCondition(runtime, TriggerConditionIds.OPPONENTS, 0, TriggerPlayerSelectors.CURRENT_PLAYER, opponents, 0,
                                                TriggerModifiers.EXACTLY, 0));
            Assertions.assertFalse(testCondition(runtime, TriggerConditionIds.OPPONENTS, 0, TriggerPlayerSelectors.CURRENT_PLAYER, opponents + 1, 0,
                                                 TriggerModifiers.EXACTLY, 0));

            // A paused countdown keeps its value through the pass.
            context.setCountdownPaused(true);
            context.modifyCountdownTimer(TriggerModifiers.SET_TO, COUNTDOWN_SECONDS);

            Assertions.assertTrue(testCondition(runtime, TriggerConditionIds.COUNTDOWN_TIMER, 0, 0, context.getCountdownTimer(), 0,
                                                TriggerModifiers.EXACTLY, 0));

            int elapsed = context.getElapsedClockTicks();

            // The pass may advance the elapsed time by one second before its walk.
            Assertions.assertTrue(testCondition(runtime, TriggerConditionIds.ELAPSED_TIME, 0, 0, elapsed, 0, TriggerModifiers.AT_LEAST, 0));
            Assertions.assertFalse(testCondition(runtime, TriggerConditionIds.ELAPSED_TIME, 0, 0, elapsed + 2, 0, TriggerModifiers.AT_LEAST, 0));

            context.setSwitch(TEST_SWITCH, true);

            Assertions.assertTrue(testCondition(runtime, TriggerConditionIds.SWITCH, 0, 0, 0, 0, TriggerModifiers.SWITCH_IS_SET, TEST_SWITCH));

            context.toggleSwitch(TEST_SWITCH);

            Assertions.assertTrue(testCondition(runtime, TriggerConditionIds.SWITCH, 0, 0, 0, 0, TriggerModifiers.SWITCH_IS_CLEARED, TEST_SWITCH));
        }
    }

    @Test
    void state_routines_match_the_actions() throws IOException {
        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.startLiveGame(gameData);
            Game game = runtime.getGame();
            TriggerContext context = requireContext(game);
            int deaths = context.getDeaths(PLAYER, ScrUnitIds.TERRAN_MARINE);

            context.setDeaths(PLAYER, ScrUnitIds.TERRAN_MARINE, TriggerModifiers.ADD, DEATHS_AMOUNT);

            Assertions.assertEquals(deaths + DEATHS_AMOUNT, context.getDeaths(PLAYER, ScrUnitIds.TERRAN_MARINE));

            runAction(runtime, TriggerActionIds.SET_DEATHS, 0, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, DEATHS_AMOUNT,
                      ScrUnitIds.TERRAN_MARINE, TriggerModifiers.ADD, 0);

            Assertions.assertEquals(deaths + 2 * DEATHS_AMOUNT, context.getDeaths(PLAYER, ScrUnitIds.TERRAN_MARINE));

            context.setSwitch(TEST_SWITCH, true);
            runAction(runtime, TriggerActionIds.SET_SWITCH, 0, 0, 0, 0, 0, TEST_SWITCH, 0, TriggerModifiers.CLEAR, 0);

            Assertions.assertFalse(context.isSwitchSet(TEST_SWITCH));

            context.toggleSwitch(TEST_SWITCH);

            Assertions.assertTrue(context.isSwitchSet(TEST_SWITCH));

            runAction(runtime, TriggerActionIds.SET_SWITCH, 0, 0, 0, 0, 0, TEST_SWITCH, 0, TriggerModifiers.TOGGLE, 0);

            Assertions.assertFalse(context.isSwitchSet(TEST_SWITCH));

            context.setCountdownPaused(true);
            context.modifyCountdownTimer(TriggerModifiers.SET_TO, COUNTDOWN_SECONDS);
            runAction(runtime, TriggerActionIds.SET_COUNTDOWN_TIMER, 0, 0, 0, COUNTDOWN_ADDED_SECONDS, 0, 0, 0, TriggerModifiers.ADD, 0);

            Assertions.assertEquals(COUNTDOWN_SECONDS + COUNTDOWN_ADDED_SECONDS, context.getCountdownTimer());

            context.modifyCountdownTimer(TriggerModifiers.SET_TO, COUNTDOWN_OVERFLOW_SECONDS);

            Assertions.assertEquals(COUNTDOWN_MAXIMUM, context.getCountdownTimer());

            runAction(runtime, TriggerActionIds.UNPAUSE_TIMER, 0, 0, 0, 0, 0, 0, 0, 0, 0);

            Assertions.assertFalse(context.isCountdownPaused());

            context.setCountdownPaused(true);

            Assertions.assertTrue(context.isCountdownPaused());

            int score = context.getScore(PLAYER, CUSTOM_SCORE);

            context.setScore(PLAYER, CUSTOM_SCORE, TriggerModifiers.ADD, SCORE_AMOUNT);
            runAction(runtime, TriggerActionIds.SET_SCORE, 0, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, SCORE_AMOUNT, CUSTOM_SCORE,
                      TriggerModifiers.ADD, 0);

            Assertions.assertEquals(score + 2 * SCORE_AMOUNT, context.getScore(PLAYER, CUSTOM_SCORE));

            MapStringSection strings = requireStrings(game);
            int objectives = strings.registerString("Routine objectives".getBytes(StandardCharsets.UTF_8));
            int label = strings.registerString("Routine label".getBytes(StandardCharsets.UTF_8));

            context.setMissionObjectives(PLAYER, objectives);

            Assertions.assertEquals(objectives, context.getMissionObjectivesStringIndex(PLAYER));

            runAction(runtime, TriggerActionIds.SET_MISSION_OBJECTIVES, 0, label, 0, 0, 0, 0, 0, 0, 0);

            Assertions.assertEquals(label, context.getMissionObjectivesStringIndex(PLAYER));
            Assertions.assertEquals("Routine objectives", game.getInterfaceLink().getMessageLines().getMapString(objectives));

            context.showLeaderboard(TriggerActionIds.LEADERBOARD_POINTS, CUSTOM_SCORE, 0, label);

            Assertions.assertEquals(TriggerActionIds.LEADERBOARD_POINTS, context.getLeaderboardType());
            Assertions.assertEquals(label, context.getLeaderboardString());
            Assertions.assertEquals(1, context.getPeriodicCountdown());

            runAction(runtime, TriggerActionIds.LEADERBOARD_KILLS, 0, objectives, 0, 0, 0, 0, ScrUnitIds.TERRAN_MARINE, 0, 0);

            Assertions.assertEquals(TriggerActionIds.LEADERBOARD_KILLS, context.getLeaderboardType());
            Assertions.assertEquals(objectives, context.getLeaderboardString());

            context.showLeaderboardAt(TriggerActionIds.LEADERBOARD_CONTROL_AT_LOCATION, 0, ScrUnitIds.TERRAN_MARINE, 0, label);

            Assertions.assertEquals(TriggerActionIds.LEADERBOARD_CONTROL_AT_LOCATION, context.getLeaderboardType());

            context.showGreedLeaderboard(GREED_GOAL);

            Assertions.assertEquals(TriggerActionIds.LEADERBOARD_GREED, context.getLeaderboardType());

            runAction(runtime, TriggerActionIds.LEADERBOARD_GOAL_CONTROL_AT_LOCATION, 1, label, 0, 0, 0, GREED_GOAL, ScrUnitIds.TERRAN_MARINE, 0, 0);

            Assertions.assertEquals(TriggerActionIds.LEADERBOARD_GOAL_CONTROL_AT_LOCATION, context.getLeaderboardType());
            Assertions.assertEquals(label, context.getLeaderboardString());

            context.setLeaderboardComputersIncluded(false);

            Assertions.assertFalse(context.areLeaderboardComputersIncluded());

            runAction(runtime, TriggerActionIds.LEADERBOARD_COMPUTER_PLAYERS, 0, 0, 0, 0, 0, 0, 0, TriggerModifiers.SET, 0);

            Assertions.assertTrue(context.areLeaderboardComputersIncluded());
            Assertions.assertThrows(IllegalArgumentException.class, () -> context.showLeaderboard(TriggerActionIds.LEADERBOARD_GREED, 0, 0, 0));
            Assertions.assertThrows(IllegalArgumentException.class, () -> context.setDeaths(PLAYER, 0, TriggerModifiers.SET, 0));
        }
    }

    @Test
    void presentation_routines_match_the_actions() throws IOException {
        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.startLiveGame(gameData);
            Game game = runtime.getGame();
            TriggerContext context = requireContext(game);
            MessageLines lines = game.getInterfaceLink().getMessageLines();
            MapStringSection strings = requireStrings(game);

            Assertions.assertEquals(PLAYER, context.getLocalPlayer());

            lines.setLayout(text -> new int[]{text.length});
            lines.setHostClock(() -> HOST_CLOCK_MILLISECONDS);
            loadLocations(game, PING_X, PING_Y);

            String text = "Routine text";
            int textIndex = strings.registerString(text.getBytes(StandardCharsets.UTF_8));

            context.displayText(PLAYER, text, true);

            Assertions.assertEquals(1, countLines(lines, text));

            runAction(runtime, TriggerActionIds.DISPLAY_TEXT_MESSAGE, 0, textIndex, 0, 0, 0, 0, 0, 0, TriggerFlags.ACTION_ALWAYS_DISPLAY);

            Assertions.assertEquals(2, countLines(lines, text));

            context.displayText(RunningGameScenario.COMPUTER, text, true);

            Assertions.assertEquals(2, countLines(lines, text), "Only the local player's text shows.");

            byte[] path = "sound\\routine.wav".getBytes(StandardCharsets.UTF_8);
            int pathIndex = strings.registerString(path);

            context.getEvents().clear();
            context.playSound(PLAYER, path);

            int direct = findEvent(context.getEvents(), TriggerEvents.WAV);

            Assertions.assertTrue(direct >= 0);
            Assertions.assertArrayEquals(path, context.getEvents().getPath(direct));

            runAction(runtime, TriggerActionIds.PLAY_WAV, 0, 0, pathIndex, 0, 0, 0, 0, 0, 0);

            int played = findEvent(context.getEvents(), TriggerEvents.WAV);

            Assertions.assertTrue(played >= 0);
            Assertions.assertArrayEquals(path, context.getEvents().getPath(played));

            context.getEvents().clear();
            context.pingMinimap(PLAYER, PING_X, PING_Y);

            int ping = findEvent(context.getEvents(), TriggerEvents.MINIMAP_PING);

            Assertions.assertEquals(PING_X, context.getEvents().getFirst(ping));
            Assertions.assertEquals(PING_Y, context.getEvents().getSecond(ping));

            runAction(runtime, TriggerActionIds.MINIMAP_PING, 2, 0, 0, 0, 0, 0, 0, 0, 0);

            ping = findEvent(context.getEvents(), TriggerEvents.MINIMAP_PING);

            Assertions.assertEquals(PING_X, context.getEvents().getFirst(ping));
            Assertions.assertEquals(PING_Y, context.getEvents().getSecond(ping));

            context.getEvents().clear();
            context.showTalkingPortrait(PLAYER, ScrUnitIds.TERRAN_SCV, PORTRAIT_MILLISECONDS);

            int portrait = findEvent(context.getEvents(), TriggerEvents.TRANSMISSION);

            Assertions.assertEquals(ScrUnitIds.TERRAN_SCV, context.getEvents().getFirst(portrait));
            Assertions.assertEquals(PORTRAIT_MILLISECONDS, context.getEvents().getFourth(portrait));

            runAction(runtime, TriggerActionIds.TALKING_PORTRAIT, 0, 0, 0, PORTRAIT_MILLISECONDS, 0, 0, ScrUnitIds.TERRAN_SCV, 0, 0);

            portrait = findEvent(context.getEvents(), TriggerEvents.TRANSMISSION);

            Assertions.assertEquals(ScrUnitIds.TERRAN_SCV, context.getEvents().getFirst(portrait));
            Assertions.assertEquals(TriggerEvents.NO_POSITION, context.getEvents().getSecond(portrait));
            Assertions.assertEquals(PORTRAIT_MILLISECONDS, context.getEvents().getFourth(portrait));

            context.getEvents().clear();
            context.setUnitSpeechMuted(PLAYER, true);

            Assertions.assertEquals(PLAYER, context.getEvents().getFirst(findEvent(context.getEvents(), TriggerEvents.UNIT_SPEECH_MUTE)));

            runAction(runtime, TriggerActionIds.UNMUTE_UNIT_SPEECH, 0, 0, 0, 0, 0, 0, 0, 0, 0);

            Assertions.assertEquals(PLAYER, context.getEvents().getFirst(findEvent(context.getEvents(), TriggerEvents.UNIT_SPEECH_UNMUTE)));
        }
    }

    @Test
    void player_routines_match_the_actions() throws IOException {
        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.startLiveGame(gameData);
            Game game = runtime.getGame();
            TriggerContext context = requireContext(game);
            PlayerRelations relations = game.getPlayerTable().requirePlayerRelations();
            int computer = RunningGameScenario.COMPUTER;

            context.setAllianceStatus(PLAYER, computer, PlayerRelations.ALLIED);

            Assertions.assertEquals(PlayerRelations.ALLIED, relations.getAlliance(PLAYER, computer));

            // Set Alliance Status: the status WORD 0 makes an enemy.
            runAction(runtime, TriggerActionIds.SET_ALLIANCE_STATUS, 0, 0, 0, 0, computer, 0, 0, 0, 0);

            Assertions.assertEquals(PlayerRelations.ENEMY, relations.getAlliance(PLAYER, computer));

            // The status WORD 2 makes allied victory, as the relation of the routine.
            runAction(runtime, TriggerActionIds.SET_ALLIANCE_STATUS, 0, 0, 0, 0, computer, 0, PlayerRelations.ALLIED_VICTORY, 0, 0);

            int allied = relations.getAlliance(PLAYER, computer);

            context.setAllianceStatus(PLAYER, computer, PlayerRelations.ENEMY);
            context.setAllianceStatus(PLAYER, computer, PlayerRelations.ALLIED_VICTORY);

            Assertions.assertEquals(allied, relations.getAlliance(PLAYER, computer));
            Assertions.assertFalse(context.runAiScript(PLAYER, RESCUE_SCRIPT), "A human player runs no computer-only script.");
            Assertions.assertTrue(context.runAiScript(PLAYER, SHARED_VISION_SCRIPT));
            Assertions.assertThrows(IllegalArgumentException.class, () -> context.setAllianceStatus(PlayerTable.PLAYABLE_PLAYER_SLOTS, 0, 0));
        }
    }

    @Test
    void unit_routines_match_the_actions() throws IOException {
        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.startLiveGame(gameData);
            Game game = runtime.getGame();
            TriggerContext context = requireContext(game);
            int spotX = game.getMap().getWidth() * TerrainTileset.TILE_SIZE / 2;
            int spotY = game.getMap().getHeight() * TerrainTileset.TILE_SIZE / 2;

            loadLocations(game, spotX, spotY);

            Unit[] marines = new Unit[2];

            RunningGameScenario.runInCommandDispatch(runtime, () -> {
                marines[0] = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_MARINE, spotX, spotY, PLAYER);
                marines[1] = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_MARINE, spotX, spotY, PLAYER);
            });

            int before = countUnits(game, PLAYER, ScrUnitIds.TERRAN_MARINE);

            RunningGameScenario.runInCommandDispatch(runtime, () -> game.getTriggerUnitActions().removeUnits(context,
                                                                                                             PLAYER,
                                                                                                             ScrUnitIds.TERRAN_MARINE,
                                                                                                             1, 1,
                                                                                                             false));

            Assertions.assertEquals(before - 1, countUnits(game, PLAYER, ScrUnitIds.TERRAN_MARINE));

            // Kill Unit At Location: location byte 2 (location 1), count 1.
            runAction(runtime, TriggerActionIds.KILL_UNIT_AT_LOCATION, 2, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, 0,
                      ScrUnitIds.TERRAN_MARINE, 1, 0);

            Assertions.assertEquals(before - 2, countUnits(game, PLAYER, ScrUnitIds.TERRAN_MARINE));
            Assertions.assertTrue(ComputerUnits.isDeadOrHidden(marines[0]) && ComputerUnits.isDeadOrHidden(marines[1]));

            Unit[] carrier = new Unit[1];

            RunningGameScenario.runInCommandDispatch(runtime, () -> {
                carrier[0] = RunningGameScenario.createUnit(game, ScrUnitIds.PROTOSS_CARRIER, spotX, spotY, PLAYER);
            });

            game.getTriggerUnitActions().setInvincible(carrier[0], true);

            Assertions.assertNotEquals(0, carrier[0].getStatus() & UnitStatusFlags.INVINCIBLE);

            // Set Invincibility: location byte 2, the clear state.
            runAction(runtime, TriggerActionIds.SET_INVINCIBILITY, 2, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, 0,
                      ScrUnitIds.PROTOSS_CARRIER, TriggerModifiers.CLEAR, 0);

            Assertions.assertEquals(0, carrier[0].getStatus() & UnitStatusFlags.INVINCIBLE);

            RunningGameScenario.runInCommandDispatch(runtime, () -> game.getTriggerUnitActions().modifyHangar(carrier[0], HANGAR_STEP,
                                                                                                              RunningGameScenario.INSTRUCTION_LIMIT));

            Assertions.assertEquals(HANGAR_STEP, carrier[0].getHangarCount(false));

            runAction(runtime, TriggerActionIds.MODIFY_UNIT_HANGAR_COUNT, 2, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, HANGAR_STEP,
                      ScrUnitIds.PROTOSS_CARRIER, 0, 0);

            Assertions.assertEquals(CARRIER_CAPACITY, carrier[0].getHangarCount(false));

            Unit field = findMineralField(game);

            Assertions.assertNotNull(field);

            game.getTriggerUnitActions().modifyResources(field, MINERAL_AMOUNT, RunningGameScenario.INSTRUCTION_LIMIT);

            Assertions.assertEquals(MINERAL_AMOUNT, field.getResources());

            loadLocations(game, field.getPositionX(), field.getPositionY());
            // Modify Unit Resource Amount: every resource container of location 1 (count 0).
            runAction(runtime, TriggerActionIds.MODIFY_UNIT_RESOURCE_AMOUNT, 2, 0, 0, 0, TriggerPlayerSelectors.ALL_PLAYERS, OTHER_MINERAL_AMOUNT,
                      0, 0, 0);

            Assertions.assertEquals(OTHER_MINERAL_AMOUNT, field.getResources());
        }
    }
}
