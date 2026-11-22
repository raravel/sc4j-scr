package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.type.constant.TriggerUnitGroups;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.map.MapStringSection;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerRelations;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.test.PlayerProgressTestUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Checks local trigger presentation, waits, results and player-group selection.
 */
final class LiveTriggerPresentationTest {
    private static final int FRAME_DURATION = GameSpeed.FASTEST_DURATION;
    /**
     * The replay mode argument of a live game.
     */
    private static final int LIVE_GAME = 0;
    /**
     * The replay mode argument while replaying.
     */
    private static final int REPLAY_GAME = 1;
    /**
     * The local player of a replay observer, outside the playable slots.
     */
    private static final int REPLAY_OBSERVER = 8;
    private static final int MULTIPLAYER_SESSION = 1;
    /**
     * The trigger update calls after which a fixture stops waiting for its result.
     */
    private static final int UPDATE_CALL_LIMIT = 100;

    /**
     * The size of the short location section.
     */
    private static final int SHORT_LOCATION_SECTION_SIZE = 0x500;
    private static final int LOCATION_LEFT_OFFSET = 0;
    private static final int LOCATION_TOP_OFFSET = 4;
    private static final int LOCATION_RIGHT_OFFSET = 8;
    private static final int LOCATION_BOTTOM_OFFSET = 12;

    /**
     * The first campaign mission WORD whose transmissions cannot be skipped.
     */
    private static final int FIRST_UNSKIPPABLE_MISSION = 0x20;

    private static final int PORTRAIT_MILLISECONDS = 2500;
    /**
     * The unit type of the talking portrait, an SCV.
     */
    private static final int PORTRAIT_UNIT_TYPE = 7;

    /*
     * The team network game fixtures: four humans, of which slots 0 and 2 lead forces 1 and 2.
     */
    private static final int FIRST_LEADER = 0;
    private static final int FIRST_MEMBER = 1;
    private static final int SECOND_LEADER = 2;
    private static final int SECOND_MEMBER = 3;
    private static final int NO_LEADER = -1;
    private static final int @NotNull [] TEAM_LEADERS = {FIRST_LEADER, SECOND_LEADER, NO_LEADER, NO_LEADER};
    /**
     * The opponents of a team leader when only the other leader counts.
     */
    private static final int LEADER_OPPONENTS = 1;
    private static final int RESOURCE_AMOUNT = 7;
    /**
     * The reload value of the trigger cycle WORD.
     */
    private static final int CYCLE_RELOAD = 30;
    /**
     * The reload value of the victory countdown.
     */
    private static final int VICTORY_COUNTDOWN_RELOAD = 45;
    /**
     * The draw result.
     */
    private static final int DRAW_RESULT = 5;
    /**
     * The outcome screen value of the victory screen.
     */
    private static final int VICTORY_SCREEN = 3;
    /**
     * The execution flag that makes interface actions finish at once.
     */
    private static final int IGNORE_INTERFACE_ACTIONS_FLAG = 0x10;

    /**
     * The event kind of a Display Text Message.
     */
    private static final int TEXT_EVENT = 1;
    /**
     * The event kind of a Play WAV.
     */
    private static final int WAV_EVENT = 2;
    /**
     * The event kind of a Center View.
     */
    private static final int CENTER_VIEW_EVENT = 3;
    /**
     * The event kind of a finished Center View.
     */
    private static final int CENTER_VIEW_FINISH_EVENT = 4;
    /**
     * The event kind of a Minimap Ping.
     */
    private static final int MINIMAP_PING_EVENT = 5;
    /**
     * The event kind of a game pause change.
     */
    private static final int GAME_PAUSE_EVENT = 8;
    /**
     * The event kind of a transmission or talking portrait.
     */
    private static final int TRANSMISSION_EVENT = 9;
    /**
     * The event kind of a transmission skip.
     */
    private static final int TRANSMISSION_SKIP_EVENT = 10;
    /**
     * The event position of a transmission without a speaking unit.
     */
    private static final int NO_EVENT_POSITION = -1;

    /**
     * The map strings of the sound path fixture: a short sound file name and a name longer than the native path buffer.
     */
    private static final @NotNull String SOUND_FILE = "staredit/wav/a.wav";
    private static final int LONG_SOUND_FILE_LENGTH = 300;
    private static final int SOUND_STRING = 1;
    private static final int LONG_SOUND_STRING = 2;
    /**
     * A sound string DWORD whose high WORD the action ignores.
     */
    private static final int HIGH_WORD = 0x10000;
    /**
     * The offset of the first string in a two-string {@code STR } section: the count WORD and two offset WORDs come first.
     */
    private static final int FIRST_STRING_OFFSET = 6;
    /**
     * The offset in {@link #SOUND_FILE} of the {@code a} that the string writes replace.
     */
    private static final int REPLACED_CHARACTER_OFFSET = 13;

    private static @NotNull TriggerEvents runUntilEvents(@NotNull TriggerContext context, int replayMode) {
        return runUntilEvents(context, replayMode, null);
    }

    private static @NotNull TriggerEvents runUntilEvents(@NotNull TriggerContext context, int replayMode, @Nullable Game world) {
        for (int call = 0; call < UPDATE_CALL_LIMIT; call++) {
            ScrTriggerSystem.update(context, FRAME_DURATION, replayMode, 0, 0, world, 0);

            if (context.getEvents().getCount() != 0) {
                return context.getEvents();
            }
        }

        Assertions.fail("No trigger events were produced.");

        return context.getEvents();
    }

    /**
     * Creates a {@code STR } section payload of the given strings.
     *
     * @param strings the strings in table order
     * @return the payload
     */
    private static byte @NotNull [] createStringSection(byte @NotNull [] @NotNull ... strings) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer word = ByteBuffer.allocate(Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int offset = Short.BYTES * (strings.length + 1);

        output.writeBytes(word.putShort(0, (short) strings.length).array());

        for (byte[] string : strings) {
            output.writeBytes(word.putShort(0, (short) offset).array());
            offset += string.length + 1;
        }

        for (byte[] string : strings) {
            output.writeBytes(string);
            output.write(0);
        }

        return output.toByteArray();
    }

    private static void assertEvent(@NotNull TriggerEvents events, int index, int kind, int first, int second, int third) {
        Assertions.assertEquals(kind, events.getKind(index));
        Assertions.assertEquals(first, events.getFirst(index));
        Assertions.assertEquals(second, events.getSecond(index));
        Assertions.assertEquals(third, events.getThird(index));
    }

    private static @NotNull Player @NotNull [] createPlayers(int @NotNull [] types) {
        Player[] players = new Player[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        for (int index = 0; index < PlayerTable.PLAYABLE_PLAYER_SLOTS; index++) {
            players[index] = new Player(index, index, types[index], 0, 0);
        }

        return players;
    }

    private static @NotNull TriggerContext createContext(@NotNull Player @NotNull [] players,
                                                         byte @NotNull [] records,
                                                         int multiplayer,
                                                         int localPlayer) {
        int[] types = new int[PlayerTable.PLAYER_SLOTS];

        for (int index = 0; index < PlayerTable.PLAYABLE_PLAYER_SLOTS; index++) {
            types[index] = players[index].getType();
        }

        TriggerContext context = new TriggerContext();
        byte[] locations = new byte[SHORT_LOCATION_SECTION_SIZE];
        ByteBuffer location = ByteBuffer.wrap(locations).order(ByteOrder.LITTLE_ENDIAN);

        location.putInt(LOCATION_LEFT_OFFSET, 32);
        location.putInt(LOCATION_TOP_OFFSET, 64);
        location.putInt(LOCATION_RIGHT_OFFSET, 96);
        location.putInt(LOCATION_BOTTOM_OFFSET, 128);
        context.loadLocations(locations);

        context.initializeClassicPlayerRecords(players);
        context.loadRecords(records);
        context.initializeEvaluation(PlayerProgressTestUtils.createUnitAccounting(), new PlayerRelations(types, false, false, false),
                                     new int[]{0, 0, multiplayer, 0, localPlayer});

        return context;
    }

    /**
     * Creates a {@link TriggerContext} instance of a team network game whose leaders are set before the players are bound
     * and the records load.
     *
     * @param players the players
     * @param records the record bytes
     * @return the created {@link TriggerContext} instance
     */
    private static @NotNull TriggerContext createTeamNetworkContext(@NotNull Player @NotNull [] players, byte @NotNull [] records) {
        int[] types = new int[PlayerTable.PLAYER_SLOTS];

        for (int index = 0; index < PlayerTable.PLAYABLE_PLAYER_SLOTS; index++) {
            types[index] = players[index].getType();
        }

        int[] controls = new int[TriggerContext.CONTROL_COUNT];
        TriggerContext context = new TriggerContext();

        controls[TriggerContext.CONTROL_MULTIPLAYER_SESSION] = MULTIPLAYER_SESSION;
        controls[TriggerContext.CONTROL_LOCAL_PLAYER]        = FIRST_LEADER;

        context.setTeamLeaders(TEAM_LEADERS);
        context.initializeClassicPlayerRecords(players);
        context.loadRecords(records);
        context.initializeEvaluation(PlayerProgressTestUtils.createUnitAccounting(), new PlayerRelations(types, false, false, false), controls);

        return context;
    }

    private static @NotNull Player @NotNull [] createTeamPlayers() {
        return createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.HUMAN, PlayerTypes.HUMAN, PlayerTypes.HUMAN, PlayerTypes.NONE,
                                       PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE});
    }

    private static void writeCondition(@NotNull ByteBuffer record, int type, int player, int amount, int comparison, int unitType) {
        record.putInt(TriggerRecordCodec.CONDITION_PLAYER_FIELD, player);
        record.putInt(TriggerRecordCodec.CONDITION_AMOUNT_FIELD, amount);
        record.putShort(TriggerRecordCodec.CONDITION_UNIT_FIELD, (short) unitType);
        record.put(TriggerRecordCodec.CONDITION_COMPARISON_FIELD, (byte) comparison);
        record.put(TriggerRecordCodec.CONDITION_TYPE_FIELD, (byte) type);
    }

    private static void writeAction(@NotNull ByteBuffer record, int index,
                                    int type,
                                    int location,
                                    int string, int sound,
                                    int player, int secondary,
                                    int modifier,
                                    int flags) {
        int base = TriggerRecordCodec.ACTIONS_OFFSET + index * TriggerRecordCodec.ACTION_SIZE;

        record.putInt(base + TriggerRecordCodec.ACTION_LOCATION_FIELD, location);
        record.putInt(base + TriggerRecordCodec.ACTION_STRING_FIELD, string);
        record.putInt(base + TriggerRecordCodec.ACTION_SOUND_FIELD, sound);
        record.putInt(base + TriggerRecordCodec.ACTION_PLAYER_FIELD, player);
        record.putInt(base + TriggerRecordCodec.ACTION_SECONDARY_FIELD, secondary);
        record.put(base + TriggerRecordCodec.ACTION_TYPE_FIELD, (byte) type);
        record.put(base + TriggerRecordCodec.ACTION_MODIFIER_FIELD, (byte) modifier);
        record.put(base + TriggerRecordCodec.ACTION_FLAGS_FIELD, (byte) flags);
    }


    @Test
    void local_presentation_events_emit_and_center_view_waits_for_arrival() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer record = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(record, TriggerConditionIds.ALWAYS, 0, 0, 0, 0);
        writeAction(record, 0, TriggerActionIds.DISPLAY_TEXT_MESSAGE, 0, 5, 0, 0, 0, 0, TriggerFlags.ACTION_ALWAYS_DISPLAY);
        writeAction(record, 1, TriggerActionIds.PLAY_WAV, 0, 0, 6, 0, 0, 0, 0);
        writeAction(record, 2, TriggerActionIds.MINIMAP_PING, 1, 0, 0, 0, 0, 0, 0);
        writeAction(record, 3, TriggerActionIds.CENTER_VIEW, 1, 0, 0, 0, 0, 0, 0);
        writeAction(record, 4, TriggerActionIds.SET_RESOURCES, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, 50, TriggerModifiers.SET_TO, 0);
        record.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        Player[] players = createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                   PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE});
        TriggerContext context = createContext(players, records, 0, 0);

        TriggerEvents events = runUntilEvents(context, LIVE_GAME);

        Assertions.assertEquals(4, events.getCount());
        assertEvent(events, 0, TEXT_EVENT, 5, 1, 0);
        assertEvent(events, 1, WAV_EVENT, 6, 0, 0);
        assertEvent(events, 2, MINIMAP_PING_EVENT, 64, 96, 0);
        assertEvent(events, 3, CENTER_VIEW_EVENT, 64, 96, 1);
        Assertions.assertTrue(context.isWaiting(0), "Single-player Center View waits for the camera.");
        Assertions.assertEquals(-1, context.getWaitTimer(0));

        for (int call = 0; call < UPDATE_CALL_LIMIT; call++) {
            ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, 0, 0);
        }

        Assertions.assertEquals(0, players[0].getMinerals(), "The wait never expires without the arrival.");

        context.completeCenterView();

        Assertions.assertFalse(context.isWaiting(0));
        Assertions.assertEquals(1, context.getCycleCountdown(), "The arrival callback reloads the trigger cycle with 1.");

        events = runUntilEvents(context, LIVE_GAME);

        assertEvent(events, 0, CENTER_VIEW_FINISH_EVENT, 0, 0, 0);
        Assertions.assertEquals(50, players[0].getMinerals(), "The resumed Center View succeeds and the next action runs.");

        context.completeCenterView();

        Assertions.assertEquals(CYCLE_RELOAD, context.getCycleCountdown(), "A second report without a pending scroll does nothing.");
    }

    @Test
    void replay_observer_receives_no_presentation_but_actions_run() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer record = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(record, TriggerConditionIds.ALWAYS, 0, 0, 0, 0);
        writeAction(record, 0, TriggerActionIds.DISPLAY_TEXT_MESSAGE, 0, 5, 0, 0, 0, 0, TriggerFlags.ACTION_ALWAYS_DISPLAY);
        writeAction(record, 1, TriggerActionIds.PLAY_WAV, 0, 0, 6, 0, 0, 0, 0);
        writeAction(record, 2, TriggerActionIds.MINIMAP_PING, 1, 0, 0, 0, 0, 0, 0);
        writeAction(record, 3, TriggerActionIds.SET_RESOURCES, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, 50, TriggerModifiers.SET_TO, 0);
        record.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        Player[] players = createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                   PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE});
        TriggerContext context = createContext(players, records, 0, REPLAY_OBSERVER);

        for (int call = 0; call < 3; call++) {
            ScrTriggerSystem.update(context, FRAME_DURATION, REPLAY_GAME, 0, 0);

            Assertions.assertEquals(0, context.getEvents().getCount());
        }

        Assertions.assertEquals(50, players[0].getMinerals());
    }

    @Test
    void play_wav_is_silent_while_replaying_for_local_player() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer record = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(record, TriggerConditionIds.ALWAYS, 0, 0, 0, 0);
        writeAction(record, 0, TriggerActionIds.PLAY_WAV, 0, 0, 6, 0, 0, 0, 0);
        writeAction(record, 1, TriggerActionIds.DISPLAY_TEXT_MESSAGE, 0, 5, 0, 0, 0, 0, 0);
        record.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        TriggerContext context = createContext(createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                                       PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE}),
                                               records,
                                               0,
                                               0);

        TriggerEvents events = runUntilEvents(context, REPLAY_GAME);

        Assertions.assertEquals(1, events.getCount(), "Play WAV is silent while replaying; Display Text has no replay check.");
        assertEvent(events, 0, TEXT_EVENT, 5, 0, 0);
    }

    @Test
    void play_wav_requests_the_sound_string_as_the_strings_hold_it_when_the_action_runs() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer record = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(record, TriggerConditionIds.ALWAYS, 0, 0, 0, 0);
        writeAction(record, 0, TriggerActionIds.PLAY_WAV, 0, 0, HIGH_WORD | SOUND_STRING, 0, 0, 0, 0);
        writeAction(record, 1, TriggerActionIds.PLAY_WAV, 0, 0, LONG_SOUND_STRING, 0, 0, 0, 0);
        record.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        TriggerContext context = createContext(createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                                       PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE}),
                                               records,
                                               0,
                                               0);
        byte[] longName = "x".repeat(LONG_SOUND_FILE_LENGTH).getBytes(StandardCharsets.US_ASCII);
        MapStringSection strings = new MapStringSection(createStringSection(SOUND_FILE.getBytes(StandardCharsets.US_ASCII), longName), false);
        Game world = Game.create(null, new GameMap(1, 1, 0, new int[1]), new LcgRandom(0));

        world.getInterfaceLink().getMessageLines().setMapStringSection(strings);
        // An EUD write to the string before the action runs changes the requested file; a write after it does not.
        strings.writeByte(FIRST_STRING_OFFSET + REPLACED_CHARACTER_OFFSET, 'b');

        TriggerEvents events = runUntilEvents(context, LIVE_GAME, world);

        strings.writeByte(FIRST_STRING_OFFSET + REPLACED_CHARACTER_OFFSET, 'c');

        Assertions.assertEquals(2, events.getCount());
        assertEvent(events, 0, WAV_EVENT, HIGH_WORD | SOUND_STRING, 0, 0);
        Assertions.assertArrayEquals("staredit/wav/b.wav".getBytes(StandardCharsets.US_ASCII), events.getPath(0),
                                     "The low WORD selects the string.");
        Assertions.assertArrayEquals(Arrays.copyOf(longName, TriggerEvents.SOUND_PATH_LIMIT), events.getPath(1),
                                     "The path holds at most the native buffer's 259 bytes.");
    }

    @Test
    void victory_writes_victory_countdown_and_shows_local_outcome() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer record = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(record, TriggerConditionIds.ALWAYS, 0, 0, 0, 0);
        writeAction(record, 0, TriggerActionIds.VICTORY, 0, 0, 0, 0, 0, 0, 0);
        record.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        Player[] players = createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                   PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE});
        TriggerContext context = createContext(players, records, 0, 0);

        Assertions.assertEquals(1, context.getVictoryCountdown());

        ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, 0, 0);
        ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, 0, 0);

        Assertions.assertEquals(CYCLE_RELOAD, context.getCycleCountdown(), "Victory leaves the trigger cycle WORD alone.");
        Assertions.assertEquals(DRAW_RESULT, players[0].getVictoryState(), "The only initially active player won: a draw result for all.");

        int shown = 0;
        int updates = 0;

        while (shown == 0 && updates < UPDATE_CALL_LIMIT) {
            ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, 0, 0);
            shown = context.consumeOutcomeDisplay();
            updates++;
        }

        Assertions.assertEquals(VICTORY_SCREEN, shown, "A draw shows the victory screen.");
        Assertions.assertEquals(VICTORY_COUNTDOWN_RELOAD, context.getVictoryCountdown());
        Assertions.assertEquals(0, context.consumeOutcomeDisplay());
    }

    @Test
    void replay_mode_never_runs_victory_countdown() {
        TriggerContext context = createContext(createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                                       PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE}),
                                               new byte[0],
                                               0,
                                               0);

        for (int call = 0; call < UPDATE_CALL_LIMIT; call++) {
            ScrTriggerSystem.update(context, FRAME_DURATION, REPLAY_GAME, 0, 0);

            Assertions.assertEquals(0, context.consumeOutcomeDisplay());
        }

        Assertions.assertEquals(1, context.getVictoryCountdown());
    }

    @Test
    void multiplayer_session_releases_records_of_players_with_a_result() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer record = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(record, TriggerConditionIds.ALWAYS, 0, 0, 0, 0);
        writeAction(record, 0, TriggerActionIds.VICTORY, 0, 0, 0, 0, 0, 0, 0);
        record.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        TriggerContext context = createContext(createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                                       PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE}),
                                               records,
                                               MULTIPLAYER_SESSION,
                                               0);

        ScrTriggerSystem.update(context, FRAME_DURATION, REPLAY_GAME, 0, 0);
        ScrTriggerSystem.update(context, FRAME_DURATION, REPLAY_GAME, 0, 0);

        Assertions.assertEquals(0, context.getPlayerRecordCount(0));
    }

    @Test
    void opponents_condition_group_selectors_sum_per_player_counts() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE * 3];
        ByteBuffer all = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(all, TriggerConditionIds.OPPONENTS, TriggerPlayerSelectors.ALL_PLAYERS, 2, TriggerModifiers.EXACTLY, 0);
        writeAction(all, 0, TriggerActionIds.SET_RESOURCES, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, 7, TriggerModifiers.ADD, 0);
        all.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        ByteBuffer neutral = TriggerRecordScenario.createRecord(records, 1);

        writeCondition(neutral, TriggerConditionIds.OPPONENTS, 8, 0, TriggerModifiers.EXACTLY, 0);
        writeAction(neutral, 0, TriggerActionIds.SET_RESOURCES, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, 100, TriggerModifiers.ADD, 0);
        neutral.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        ByteBuffer enemies = TriggerRecordScenario.createRecord(records, 2);

        writeCondition(enemies, TriggerConditionIds.OPPONENTS, TriggerPlayerSelectors.FOES, 1, TriggerModifiers.EXACTLY, 0);
        writeAction(enemies, 0, TriggerActionIds.SET_RESOURCES, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, 1000, TriggerModifiers.ADD, 0);
        enemies.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        Player[] players = createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                   PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE});
        TriggerContext context = createContext(players, records, 0, REPLAY_OBSERVER);

        ScrTriggerSystem.update(context, FRAME_DURATION, REPLAY_GAME, 0, 0);
        ScrTriggerSystem.update(context, FRAME_DURATION, REPLAY_GAME, 0, 0);

        Assertions.assertEquals(1107, players[0].getMinerals(), "Selector 17 sums two opponents, 8 counts none, 14 sums player 1's one.");
    }

    @Test
    void opponents_counts_only_the_team_leaders_in_a_team_network_game() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer record = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(record, TriggerConditionIds.OPPONENTS, TriggerPlayerSelectors.CURRENT_PLAYER, LEADER_OPPONENTS, TriggerModifiers.EXACTLY, 0);
        writeAction(record, 0, TriggerActionIds.SET_RESOURCES, 0, 0, 0,
                    TriggerPlayerSelectors.CURRENT_PLAYER, RESOURCE_AMOUNT, TriggerModifiers.ADD, 0);
        record.put(TriggerRecordCodec.OWNERS_OFFSET + FIRST_LEADER, (byte) 1);

        Player[] players = createTeamPlayers();
        TriggerContext context = createTeamNetworkContext(players, records);

        ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, 0, 0);
        ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, 0, 0);

        Assertions.assertEquals(RESOURCE_AMOUNT, players[FIRST_LEADER].getMinerals(), "The other force counts only as its leader.");
    }

    @Test
    void player_groups_select_only_the_team_leaders_in_a_team_network_game() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer record = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(record, TriggerConditionIds.ALWAYS, 0, 0, 0, 0);
        writeAction(record, 0, TriggerActionIds.SET_RESOURCES, 0, 0, 0, TriggerPlayerSelectors.FOES, RESOURCE_AMOUNT, TriggerModifiers.ADD, 0);
        record.put(TriggerRecordCodec.OWNERS_OFFSET + FIRST_LEADER, (byte) 1);

        Player[] players = createTeamPlayers();
        TriggerContext context = createTeamNetworkContext(players, records);

        ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, 0, 0);
        ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, 0, 0);

        Assertions.assertEquals(RESOURCE_AMOUNT, players[SECOND_LEADER].getMinerals());
        Assertions.assertEquals(0, players[FIRST_MEMBER].getMinerals(), "A member that leads no force is in no player group.");
        Assertions.assertEquals(0, players[SECOND_MEMBER].getMinerals());
    }

    @Test
    void is_owner_selected_should_take_only_the_units_of_team_leaders_for_player_groups() {
        TriggerContext context = createTeamNetworkContext(createTeamPlayers(), new byte[TriggerRecordCodec.RECORD_SIZE]);

        context.writeCurrentPlayerDword(FIRST_LEADER);

        Assertions.assertTrue(TriggerPlayersUtils.isOwnerSelected(context, TriggerPlayerSelectors.FOES, SECOND_LEADER));
        Assertions.assertFalse(TriggerPlayersUtils.isOwnerSelected(context, TriggerPlayerSelectors.FOES, SECOND_MEMBER),
                               "The units of a member that leads no force belong to no player group.");
        Assertions.assertFalse(TriggerPlayersUtils.isOwnerSelected(context, TriggerPlayerSelectors.ALL_PLAYERS, FIRST_MEMBER));
        Assertions.assertTrue(TriggerPlayersUtils.isOwnerSelected(context, TriggerPlayerSelectors.ALL_PLAYERS, PlayerTable.NEUTRAL_PLAYER_ID));
        Assertions.assertTrue(TriggerPlayersUtils.isOwnerSelected(context, SECOND_MEMBER, SECOND_MEMBER), "A single-player selector names any slot.");
    }

    @Test
    void pause_game_succeeds_without_effect_in_multiplayer() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer record = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(record, TriggerConditionIds.ALWAYS, 0, 0, 0, 0);
        writeAction(record, 0, TriggerActionIds.PAUSE_GAME, 0, 0, 0, 0, 0, 0, 0);
        writeAction(record, 1, TriggerActionIds.UNPAUSE_GAME, 0, 0, 0, 0, 0, 0, 0);
        writeAction(record, 2, TriggerActionIds.SET_RESOURCES, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, 50, TriggerModifiers.SET_TO, 0);
        record.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        Player[] players = createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                   PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE});
        TriggerContext context = createContext(players, records, MULTIPLAYER_SESSION, 0);

        ScrTriggerSystem.update(context, FRAME_DURATION, REPLAY_GAME, 0, 0);
        ScrTriggerSystem.update(context, FRAME_DURATION, REPLAY_GAME, 0, 0);

        Assertions.assertEquals(50, players[0].getMinerals());
        Assertions.assertEquals(0, context.getPhaseGate());
        Assertions.assertEquals(0, context.getTriggersEnabledWhileGated());
    }

    @Test
    void pause_game_holds_game_until_pausing_trigger_completes() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer record = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(record, TriggerConditionIds.ALWAYS, 0, 0, 0, 0);
        writeAction(record, 0, TriggerActionIds.PAUSE_GAME, 0, 0, 0, 0, 0, 0, 0);
        writeAction(record, 1, TriggerActionIds.WAIT, 0, 0, 0, 0, 0, 0, 0);
        record.putInt(TriggerRecordCodec.ACTIONS_OFFSET + TriggerRecordCodec.ACTION_SIZE + TriggerRecordCodec.ACTION_DURATION_FIELD, 420);
        writeAction(record, 2, TriggerActionIds.SET_RESOURCES, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, 50, TriggerModifiers.SET_TO, 0);
        record.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        Player[] players = createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                   PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE});
        TriggerContext context = createContext(players, records, 0, 0);

        ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, 0, 0);
        ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, 0, 0);

        Assertions.assertEquals(1, context.getPhaseGate(), "Pause Game sets the paused state.");
        Assertions.assertEquals(1, context.getTriggersEnabledWhileGated(), "Triggers keep running while the game is paused by a trigger.");
        assertEvent(context.getEvents(), 0, GAME_PAUSE_EVENT, 1, 0, 0);
        Assertions.assertTrue(context.isWaiting(0));

        int updates = 0;

        while (players[0].getMinerals() == 0 && updates < UPDATE_CALL_LIMIT) {
            ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, context.getPhaseGate(), context.getTriggersEnabledWhileGated());
            updates++;
        }

        Assertions.assertEquals(50, players[0].getMinerals(), "The wait expires under the phase gate and the trigger completes.");
        Assertions.assertEquals(0, context.getPhaseGate(), "Completion flag 0x20 resumes the game through the Unpause handler.");
        Assertions.assertEquals(0, context.getTriggersEnabledWhileGated());
        assertEvent(context.getEvents(), 0, GAME_PAUSE_EVENT, 0, 0, 0);
    }

    @Test
    void plugin_pause_requests_follow_the_pause_game_rule_at_the_next_trigger_step() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer record = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(record, TriggerConditionIds.ALWAYS, 0, 0, 0, 0);
        writeAction(record, 0, TriggerActionIds.SET_RESOURCES, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, 50, TriggerModifiers.SET_TO, 0);
        record.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        Player[] players = createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                   PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE});
        TriggerContext context = createContext(players, records, 0, 0);

        context.requestPauseGame();
        ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, 0, 0);

        Assertions.assertEquals(1, context.getPhaseGate(), "Pause Game without a record sets the paused state.");
        Assertions.assertEquals(1, context.getTriggersEnabledWhileGated());
        Assertions.assertEquals(1, context.getEvents().getCount());
        assertEvent(context.getEvents(), 0, GAME_PAUSE_EVENT, 1, 0, 0);

        context.requestPauseGame();
        ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, context.getPhaseGate(), context.getTriggersEnabledWhileGated());

        Assertions.assertEquals(0, context.getEvents().getCount(), "Pause Game does nothing while the game is paused by it.");
        Assertions.assertEquals(1, context.getPhaseGate());

        context.requestUnpauseGame();
        ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, context.getPhaseGate(), context.getTriggersEnabledWhileGated());

        Assertions.assertEquals(0, context.getPhaseGate());
        Assertions.assertEquals(0, context.getTriggersEnabledWhileGated());
        assertEvent(context.getEvents(), 0, GAME_PAUSE_EVENT, 0, 0, 0);

        context.requestPauseGame();
        ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, 1, 0);
        ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, 0, 0);

        Assertions.assertEquals(0, context.getPhaseGate(), "A step the Pause command keeps from running drops the request.");

        TriggerContext multiplayer = createContext(players, records, MULTIPLAYER_SESSION, 0);

        multiplayer.requestPauseGame();
        ScrTriggerSystem.update(multiplayer, FRAME_DURATION, LIVE_GAME, 0, 0);

        Assertions.assertEquals(0, multiplayer.getPhaseGate(), "Pause Game does nothing in a multiplayer session.");
    }

    @Test
    void escape_skip_ends_local_wait_and_lets_actions_finish() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer waiting = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(waiting, TriggerConditionIds.ALWAYS, 0, 0, 0, 0);
        writeAction(waiting, 0, TriggerActionIds.WAIT, 0, 0, 0, 0, 0, 0, 0);
        waiting.putInt(TriggerRecordCodec.ACTIONS_OFFSET + TriggerRecordCodec.ACTION_DURATION_FIELD, 60000);
        writeAction(waiting, 1, TriggerActionIds.SET_RESOURCES, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, 50, TriggerModifiers.SET_TO, 0);
        waiting.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        Player[] players = createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                   PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE});
        TriggerContext context = createContext(players, records, 0, 0);

        for (int call = 0; call < 10 && !context.isWaiting(0); call++) {
            ScrTriggerSystem.update(context, FRAME_DURATION, REPLAY_GAME, 0, 0);
        }

        Assertions.assertTrue(context.isWaiting(0));

        context.skipTransmissions(FIRST_UNSKIPPABLE_MISSION);

        Assertions.assertTrue(context.isWaiting(0), "Campaign mission WORDs from 0x20 do not skip.");

        context.skipTransmissions(0);

        Assertions.assertFalse(context.isWaiting(0), "Escape skip ends the local wait.");
        Assertions.assertEquals(IGNORE_INTERFACE_ACTIONS_FLAG,
                                context.getPlayerRecord(0, 0)[TriggerRecordCodec.FLAGS_OFFSET] & TriggerFlags.EXECUTION_IGNORE_INTERFACE_ACTIONS,
                                "The running record takes skip flag 0x10.");
        Assertions.assertEquals(TRANSMISSION_SKIP_EVENT, context.getEvents().getKind(context.getEvents().getCount() - 1));

        for (int call = 0; call < 3 && players[0].getMinerals() == 0; call++) {
            ScrTriggerSystem.update(context, FRAME_DURATION, REPLAY_GAME, 0, 0);
        }

        Assertions.assertEquals(50, players[0].getMinerals(), "The skipped Wait finishes and the next action runs.");
    }

    @Test
    void command_of_unit_type_none_reads_all_units_table_for_completed_counts() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer command = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(command, TriggerConditionIds.COMMAND, 0, 0, TriggerModifiers.AT_LEAST, TriggerUnitGroups.NONE);
        writeAction(command, 0, TriggerActionIds.SET_RESOURCES, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, 50, TriggerModifiers.SET_TO, 0);
        command.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        Player[] players = createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                   PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE});
        TriggerContext context = createContext(players, records, 0, 0);

        for (int call = 0; call < 10 && players[0].getMinerals() == 0; call++) {
            ScrTriggerSystem.update(context, FRAME_DURATION, REPLAY_GAME, 0, 0);
        }

        Assertions.assertEquals(50, players[0].getMinerals());
    }

    @Test
    void escape_skip_does_nothing_in_multiplayer() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer waiting = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(waiting, TriggerConditionIds.ALWAYS, 0, 0, 0, 0);
        writeAction(waiting, 0, TriggerActionIds.WAIT, 0, 0, 0, 0, 0, 0, 0);
        waiting.putInt(TriggerRecordCodec.ACTIONS_OFFSET + TriggerRecordCodec.ACTION_DURATION_FIELD, 60000);
        waiting.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        Player[] players = createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                   PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE});
        TriggerContext context = createContext(players, records, MULTIPLAYER_SESSION, 0);

        for (int call = 0; call < 10 && !context.isWaiting(0); call++) {
            ScrTriggerSystem.update(context, FRAME_DURATION, REPLAY_GAME, 0, 0);
        }

        context.skipTransmissions(0);

        Assertions.assertTrue(context.isWaiting(0));
        Assertions.assertEquals(0, context.getPlayerRecord(0, 0)[TriggerRecordCodec.FLAGS_OFFSET] & TriggerFlags.EXECUTION_IGNORE_INTERFACE_ACTIONS);
    }

    @Test
    void talking_portrait_talks_for_local_player_without_speaker_and_does_not_wait() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer record = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(record, TriggerConditionIds.ALWAYS, 0, 0, 0, 0);
        writeAction(record, 0, TriggerActionIds.TALKING_PORTRAIT, 0, 0, 0, 0, 0, 0, 0);
        record.putInt(TriggerRecordCodec.ACTIONS_OFFSET + TriggerRecordCodec.ACTION_DURATION_FIELD, PORTRAIT_MILLISECONDS);
        record.putShort(TriggerRecordCodec.ACTIONS_OFFSET + TriggerRecordCodec.ACTION_UNIT_FIELD, (short) PORTRAIT_UNIT_TYPE);
        record.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        TriggerContext context = createContext(createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                                       PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE}),
                                               records,
                                               0,
                                               0);

        TriggerEvents events = runUntilEvents(context, LIVE_GAME);

        Assertions.assertEquals(1, events.getCount());
        assertEvent(events, 0, TRANSMISSION_EVENT, PORTRAIT_UNIT_TYPE, NO_EVENT_POSITION, NO_EVENT_POSITION);
        Assertions.assertEquals(PORTRAIT_MILLISECONDS, events.getFourth(0), "Talking Portrait passes the action's time to the portrait.");
        Assertions.assertFalse(context.isWaiting(0), "Talking Portrait does not wait.");
    }

    @Test
    void talking_portrait_of_another_player_shows_nothing() {
        byte[] records = new byte[TriggerRecordCodec.RECORD_SIZE];
        ByteBuffer record = TriggerRecordScenario.createRecord(records, 0);

        writeCondition(record, TriggerConditionIds.ALWAYS, 0, 0, 0, 0);
        writeAction(record, 0, TriggerActionIds.TALKING_PORTRAIT, 0, 0, 0, 0, 0, 0, 0);
        writeAction(record, 1, TriggerActionIds.SET_RESOURCES, 0, 0, 0, TriggerPlayerSelectors.CURRENT_PLAYER, 50, TriggerModifiers.SET_TO, 0);
        record.putInt(TriggerRecordCodec.ACTIONS_OFFSET + TriggerRecordCodec.ACTION_DURATION_FIELD, PORTRAIT_MILLISECONDS);
        record.put(TriggerRecordCodec.OWNERS_OFFSET, (byte) 1);

        Player[] players = createPlayers(new int[]{PlayerTypes.HUMAN, PlayerTypes.HUMAN, PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE,
                                                   PlayerTypes.NONE, PlayerTypes.NONE, PlayerTypes.NONE});
        TriggerContext context = createContext(players, records, 0, 1);

        for (int call = 0; call < UPDATE_CALL_LIMIT && players[0].getMinerals() == 0; call++) {
            ScrTriggerSystem.update(context, FRAME_DURATION, LIVE_GAME, 0, 0);

            Assertions.assertEquals(0, context.getEvents().getCount(), "Talking Portrait shows nothing for another player.");
        }

        Assertions.assertEquals(50, players[0].getMinerals(), "The record ran.");
    }
}
