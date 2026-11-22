package org.fogofwarlabs.sc4j.scr.game.session;

import org.fogofwarlabs.sc4j.engine.command.CommandInput;
import org.fogofwarlabs.sc4j.engine.lockstep.*;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.format.replay.*;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayCommandExecutor;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayCommandIssuer;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.replay.ScrCommandType;
import org.fogofwarlabs.sc4j.scr.game.session.setup.GameTemplate;
import org.fogofwarlabs.sc4j.scr.game.session.setup.LobbyResolution;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.state.RuntimeRandom;
import org.fogofwarlabs.sc4j.scr.game.test.GameTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks how a live network game finds a player's slot from its player id (the waiting-for-players list and the issuer of the player's commands, in
 * melee games with fixed start positions), the name bytes of the Save Game commands a live game queues and executes, the turns a single-player game
 * keeps in transit, the player table of the game's start, and the replay a live game writes: its name section, a write while commands are
 * recorded and a submitted mod command.
 * <p>
 * The Save Game command layout (type, time DWORD, name and terminator), the player records (36 bytes, the name at {@code +0xb}) and the replay's name
 * section (twelve 96-byte records) are native formats, so the tests compare their bytes.
 */
final class LiveGameTest {
    private static final @NotNull String SCENARIO = "staredit\\scenario.chk";
    /**
     * The game seed; it orders the waiting list of three players as second, third, first.
     */
    private static final int SEED = 0x1234;
    /**
     * A C runtime generator state before the waiting list is shuffled, and the state the shuffle of three players leaves: the
     * game seed advanced three times.
     */
    private static final int GAME_GENERATOR_STATE = 0x5EED1234;
    private static final int THREE_PLAYER_SHUFFLE_STATE = 0x12B55FD9;
    private static final int THREE_PLAYER_SHUFFLE_DRAWS = 3;
    /**
     * The countdown of a running waiting-for-players dialog, and the countdown while the turns arrive (no dialog).
     */
    private static final int WAIT_COUNTDOWN = 30;
    private static final int NO_WAIT_COUNTDOWN = -1;
    /**
     * The Unix time of a typed line, the key draws a single-player cheat check makes, and the text results of a discarded
     * line and of a cheat.
     */
    private static final int TYPED_TIME = 0x6543210F;
    private static final int KEY_DRAW_COUNT = 4;
    private static final int TEXT_DISCARDED = 0;
    private static final int TEXT_CHEAT = 1;
    private static final int HOST_PLAYER = 0;
    private static final int HOST_SLOT = 0;
    private static final int RELEASED_SLOT = 1;
    private static final int JOINER_PLAYER = 1;
    private static final int JOINER_SLOT = 2;
    private static final int SECOND_PLAYER = 2;
    private static final int THIRD_PLAYER = 3;
    private static final int FIRST_WAITING_SLOT = 1;
    private static final int SECOND_WAITING_SLOT = 2;
    private static final int THIRD_WAITING_SLOT = 3;
    private static final int TURNS_IN_TRANSIT = 2;
    private static final int MELEE_FORCE = 1;
    private static final int BROOD_WAR_ENGINE = 1;
    private static final @NotNull String HOST_NAME = "host";
    private static final @NotNull String JOINER_NAME = "joiner";
    private static final @NotNull String SECOND_NAME = "second";
    private static final @NotNull String THIRD_NAME = "third";
    private static final LobbyResolution.@NotNull Options FIXED_START_MELEE = new LobbyResolution.Options(true, false, 0, true, false);
    /**
     * The time DWORD of the Save Game commands.
     */
    private static final int SAVE_TIME = 0x11223344;
    /**
     * The latency byte of a silent Latency command that sets the high latency: one extra turn in transit.
     */
    private static final int SILENT_HIGH_LATENCY = 0x81;
    /**
     * A character of three UTF-8 bytes.
     */
    private static final @NotNull String HANGUL = "\ud55c";
    /**
     * The longest saved game name in bytes.
     */
    private static final int LONGEST_SAVED_GAME_NAME = ReplayFile.SAVED_GAME_NAME_SIZE - 1;
    /**
     * The offset of the header section in a written replay: after the replay ID section (checksum, chunk count and one 4-byte chunk) and the
     * extension offset DWORD.
     */
    private static final int REPLAY_HEADER_SECTION_OFFSET = 20;
    /**
     * The index of the name section among the sections after the replay ID of a replay without commands: header, command size, map size, map and
     * names.
     */
    private static final int NAME_SECTION_INDEX = 4;
    /**
     * The size of one record of the replay's name section.
     */
    private static final int NAME_RECORD_SIZE = 0x60;
    /**
     * A saved header's player name holding the first two bytes of {@link #HANGUL} after {@code a}, as a name cut inside a character holds them.
     */
    private static final byte @NotNull [] SPLIT_NAME = {'a', (byte) 0xED, (byte) 0x95};
    /**
     * A computer nation name of 31 UTF-8 bytes, longer than a header record's 24 name bytes.
     */
    private static final @NotNull String LONG_NATION_NAME = "a" + HANGUL.repeat(10);
    /**
     * The single-byte conversion of {@link #LONG_NATION_NAME} with its terminator.
     */
    private static final @NotNull String CONVERTED_NATION_NAME = "a" + "?".repeat(10) + "\0";
    /**
     * A network player's name of nine characters of three UTF-8 bytes, longer than the 24 name bytes of a player record.
     */
    private static final @NotNull String LONG_HUMAN_NAME = HANGUL.repeat(9);
    /**
     * The single-byte conversion of {@link #LONG_HUMAN_NAME} in a written replay's record, terminator included.
     */
    private static final @NotNull String CONVERTED_HUMAN_NAME = "?".repeat(9) + "\0";
    /**
     * The UTF-8 byte count of {@link #HANGUL}.
     */
    private static final int HANGUL_BYTES = 3;
    /**
     * The name buffer of a player record, terminator included.
     */
    private static final int PLAYER_RECORD_NAME_SIZE = 25;
    /**
     * The turns the game thread records while the replay is written from another thread.
     */
    private static final int RECORDED_TURNS = 200000;
    private static final byte @NotNull [] STOP_PAYLOAD = {0};
    private static final byte @NotNull [] CHAT_TEXT = {'g', 'g'};

    /**
     * Creates a {@link LobbyResolution.Table} instance of a melee game whose eight player slots are closed but for the host's slot 0.
     *
     * @return the created {@link LobbyResolution.Table} instance
     */
    private static LobbyResolution.@NotNull Table createTable() {
        LobbyResolution.Table table = new LobbyResolution.Table();

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            table.type[slot]         = LobbyResolution.TYPE_CLOSED;
            table.templateType[slot] = LobbyResolution.TYPE_OPEN;
            table.savedType[slot]    = LobbyResolution.TYPE_OPEN;
            table.race[slot]         = ScrRaceIds.TERRAN;
            table.templateRace[slot] = ScrRaceIds.TERRAN;
            table.team[slot]         = MELEE_FORCE;
            table.templateTeam[slot] = MELEE_FORCE;
            table.lobbyColor[slot]   = slot;
        }

        setRecord(table, HOST_SLOT, HOST_PLAYER, MeleeGameSetup.TYPE_HUMAN, HOST_NAME);

        return table;
    }

    private static void setRecord(LobbyResolution.@NotNull Table table, int slot, int player, int type, @NotNull String name) {
        table.playerId[slot] = player;
        table.type[slot]     = type;
        table.name[slot]     = name;
    }

    /**
     * Creates a {@link MeleeGameSetup} instance of a game whose joiner's join came again: the joiner (player 1) released slot 1, which keeps its id,
     * and holds slot 2.
     *
     * @return the created {@link MeleeGameSetup} instance
     * @throws IOException if the map cannot be read
     */
    private static @NotNull MeleeGameSetup createRepeatedJoinSetup() throws IOException {
        LobbyResolution.Table table = createTable();

        setRecord(table, RELEASED_SLOT, JOINER_PLAYER, LobbyResolution.TYPE_OPEN, JOINER_NAME);
        setRecord(table, JOINER_SLOT, JOINER_PLAYER, MeleeGameSetup.TYPE_HUMAN, JOINER_NAME);

        return createSetup(table);
    }

    private static @NotNull MeleeGameSetup createSetup(LobbyResolution.@NotNull Table table) throws IOException {
        return MeleeGameSetup.createNetwork(readMapData(), HOST_NAME, table, FIXED_START_MELEE, GameTemplate.MELEE, GameSpeed.FASTEST, true);
    }

    private static byte @NotNull [] readMapData() throws IOException {
        return MpqArchive.open(GameTestResources.getPath(GameTestResources.MAP_ARCHIVE_NEW_AVALON)).readFile(SCENARIO);
    }

    /**
     * Creates a {@link MeleeGameSetup} instance of a single-player melee game loaded from a saved game whose header names the human with the given
     * bytes.
     *
     * @param name the bytes of the human's record name, without terminator
     * @return the created {@link MeleeGameSetup} instance
     * @throws IOException if the map cannot be read
     */
    private static @NotNull MeleeGameSetup createSavedSetup(byte @NotNull [] name) throws IOException {
        ReplayHeaderBuilder builder = new ReplayHeaderBuilder().setEngine(BROOD_WAR_ENGINE).setGameOptions(GameTemplate.MELEE.encode());
        byte[] header = builder.setPlayer(HOST_SLOT, HOST_SLOT, HOST_PLAYER, MeleeGameSetup.TYPE_HUMAN, ScrRaceIds.TERRAN, MELEE_FORCE, "").build();

        System.arraycopy(name, 0, header, ReplayHeaderBuilder.PLAYERS_OFFSET + ReplayHeaderBuilder.PLAYER_NAME_OFFSET, name.length);

        return MeleeGameSetup.createSaved(readMapData(), header, false, new byte[PlayerTable.PLAYER_SLOTS], new byte[PlayerTable.PLAYER_SLOTS],
                                          false, null);
    }

    /**
     * Reads the sections of a replay without commands that a live game wrote.
     *
     * @param live the live game
     * @return the header, command size, map size, map and name sections
     * @throws IOException if the replay cannot be decoded
     */
    private static @NotNull List<byte[]> readReplaySections(@NotNull LiveGame live) throws IOException {
        byte[] file = live.writeReplay(0, 0);

        return ReplayFile.readSections(Arrays.copyOfRange(file, REPLAY_HEADER_SECTION_OFFSET, file.length), false,
                                       ReplayHeaderBuilder.HEADER_SIZE, Integer.BYTES, Integer.BYTES, live.getSetup().getMapData().length,
                                       ReplayWriter.NAMES_SIZE);
    }

    /**
     * Reads one record of a replay's name section.
     *
     * @param names the name section
     * @param slot  the record index
     * @return the record's bytes before its terminator
     */
    private static byte @NotNull [] readNameRecord(byte @NotNull [] names, int slot) {
        int end = slot * NAME_RECORD_SIZE;

        while (names[end] != 0) {
            end++;
        }

        return Arrays.copyOfRange(names, slot * NAME_RECORD_SIZE, end);
    }

    /**
     * Creates a {@link LockstepTransport} instance whose players' turns are overdue.
     *
     * @param waitingPlayers the overdue players by their network ids
     * @param waitCountdown  the waiting-for-players countdown, {@link WaitCountdown#CLOSED} while the turns arrive
     * @return the created {@link LockstepTransport} instance
     */
    private static @NotNull LockstepTransport createWaitingTransport(@NotNull List<@NotNull Integer> waitingPlayers, int waitCountdown) {
        WaitCountdown countdown = new WaitCountdown(Math.max(waitCountdown, 0), 0L, 0L, 0);
        WaitingPeers waiting = new WaitingPeers(0L, 0L, countdown);
        List<LockstepPeer> awaited = new ArrayList<>();

        for (int playerId : waitingPlayers) {
            awaited.add(NetworkPeerUtils.getPeer(playerId));
        }

        waiting.update(awaited, 0L);

        if (waitCountdown != WaitCountdown.CLOSED) {
            countdown.advance(0L);
        }

        return new LockstepTransport() {
            /**
             * @return the local turns sent and not yet received back
             */
            @Override
            public int getTurnsInTransit() {
                return 0;
            }

            /**
             * @return the waiting state of the all-turns barrier (the awaited and overdue participants, the drop countdown), or {@code null}
             *         for a transport that never drops a participant
             */
            @Override
            public @NotNull WaitingPeers getWaitingPeers() {
                return waiting;
            }


            /**
             * Sends the local player's next turn.
             *
             * @param turn the turn bytes
             */
            @Override
            public void sendTurn(byte @NotNull [] turn) {
            }

            /**
             * Takes the next turn of every participant once all of them arrived.
             *
             * @return the participants' turns in execution order, or {@code null} while a participant's turn is missing
             */
            @Override
            public @Nullable List<@NotNull PeerTurn> receiveTurns() {
                return null;
            }
        };
    }

    /**
     * Creates a {@link LiveGame.SavedGameHandler} instance that collects the name bytes of every executed Save Game command.
     *
     * @param saved the list that receives the name bytes
     * @return the created {@link LiveGame.SavedGameHandler} instance
     */
    private static LiveGame.@NotNull SavedGameHandler createSavedGameHandler(@NotNull List<byte[]> saved) {
        return new LiveGame.SavedGameHandler() {
            /**
             * Writes the running game to a saved game on every machine executing the Save Game command.
             *
             * @param time the command's time DWORD, {@code time(0)} of the issuing machine
             * @param name the command's name bytes without terminator, as received: the file name without extension
             */
            @Override
            public void saveGame(int time, byte @NotNull [] name) {
                saved.add(name);
            }

            /**
             * Ends the running single-player game and starts the saved game.
             * <p>
             * This fixture ignores load requests.
             *
             * @param time the command's time DWORD
             * @param name the command's name bytes without terminator, as received: the file name without extension
             */
            @Override
            public void loadGame(int time, byte @NotNull [] name) {
            }
        };
    }

    /**
     * Creates the bytes of a Save Game command.
     *
     * @param name the name bytes without terminator
     * @return the command: type, time, name and terminator
     */
    private static byte @NotNull [] createSaveGameCommand(byte @NotNull [] name) {
        ByteBuffer command = ByteBuffer.allocate(1 + Integer.BYTES + name.length + 1).order(ByteOrder.LITTLE_ENDIAN);

        command.put((byte) ReplayCommandIds.SAVE_GAME).putInt(SAVE_TIME).put(name);

        return command.array();
    }

    /**
     * Queues a Save Game command in a new single-player live game and reads the queued bytes.
     *
     * @param name the name bytes without terminator
     * @return the queued command bytes
     * @throws IOException if the map cannot be read
     */
    private static byte @NotNull [] requestSaveGame(byte @NotNull [] name) throws IOException {
        LiveGame game = new LiveGame(createSetup(createTable()), SEED, HOST_PLAYER);
        OutgoingTurnBuffer turn = game.getOutgoingTurn();
        int start = turn.getLength();

        game.requestSaveGame(SAVE_TIME, name);

        byte[] command = new byte[turn.getLength() - start];

        for (int index = 0; index < command.length; index++) {
            command[index] = (byte) turn.readByte(start + index);
        }

        return command;
    }

    private static <C> void submitDecoded(@NotNull LocalCommandSink sink, @NotNull Player player, @NotNull ScrCommandType<C> type,
                                          byte @NotNull [] payload) throws IOException {
        sink.submit(player, type, type.read(new CommandInput(payload)));
    }

    private static byte @NotNull [] readTurn(@NotNull LiveGame game) {
        OutgoingTurnBuffer turn = game.getOutgoingTurn();
        byte[] bytes = new byte[turn.getLength()];

        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) turn.readByte(index);
        }

        return bytes;
    }

    @Test
    void local_command_sink_queues_the_bytes_of_the_raw_submission() throws IOException {
        LiveGame raw = new LiveGame(createSetup(createTable()), SEED, HOST_PLAYER);
        LiveGame typed = new LiveGame(createSetup(createTable()), SEED, HOST_PLAYER);
        Player local = new Player(HOST_PLAYER);
        LocalCommandSink sink = new LocalCommandSink(typed, local);
        ScrCommandType<?> stop = new ReplayCommandExecutor().getCommandTypes().get(ReplayCommandIds.STOP);

        raw.submit(ReplayCommandIds.STOP, STOP_PAYLOAD);
        submitDecoded(sink, local, stop, STOP_PAYLOAD);

        Assertions.assertArrayEquals(readTurn(raw), readTurn(typed));
        Assertions.assertThrows(IllegalArgumentException.class, () -> submitDecoded(sink, new Player(HOST_PLAYER), stop, STOP_PAYLOAD));
    }

    @Test
    void waiting_list_names_a_player_who_joined_again_once() throws IOException {
        LiveGame game = new LiveGame(createRepeatedJoinSetup(), SEED, HOST_PLAYER);

        game.attachNetwork(createWaitingTransport(List.of(JOINER_PLAYER), WAIT_COUNTDOWN), TURNS_IN_TRANSIT, false);

        Assertions.assertEquals(List.of(JOINER_NAME), game.getWaitingPlayerNames(new RuntimeRandom(GAME_GENERATOR_STATE)));
    }

    @Test
    void waiting_list_orders_the_waiting_players_by_the_game_seed_and_reseeds_the_game_generator() throws IOException {
        LobbyResolution.Table table = createTable();

        setRecord(table, FIRST_WAITING_SLOT, JOINER_PLAYER, MeleeGameSetup.TYPE_HUMAN, JOINER_NAME);
        setRecord(table, SECOND_WAITING_SLOT, SECOND_PLAYER, MeleeGameSetup.TYPE_HUMAN, SECOND_NAME);
        setRecord(table, THIRD_WAITING_SLOT, THIRD_PLAYER, MeleeGameSetup.TYPE_HUMAN, THIRD_NAME);

        LiveGame game = new LiveGame(createSetup(table), SEED, HOST_PLAYER);

        game.attachNetwork(createWaitingTransport(List.of(JOINER_PLAYER, SECOND_PLAYER, THIRD_PLAYER), WAIT_COUNTDOWN), TURNS_IN_TRANSIT, false);

        RuntimeRandom runtimeRandom = new RuntimeRandom(GAME_GENERATOR_STATE);

        Assertions.assertEquals(List.of(SECOND_NAME, THIRD_NAME, JOINER_NAME), game.getWaitingPlayerNames(runtimeRandom));
        Assertions.assertEquals(THREE_PLAYER_SHUFFLE_STATE, runtimeRandom.getState(), "The shuffle reseeds the game's generator.");
        Assertions.assertEquals(THREE_PLAYER_SHUFFLE_DRAWS, runtimeRandom.getDrawCount());
    }

    @Test
    void waiting_list_is_empty_and_leaves_the_game_generator_while_the_turns_arrive() throws IOException {
        LiveGame game = new LiveGame(createRepeatedJoinSetup(), SEED, HOST_PLAYER);
        RuntimeRandom runtimeRandom = new RuntimeRandom(GAME_GENERATOR_STATE);

        game.attachNetwork(createWaitingTransport(List.of(JOINER_PLAYER), NO_WAIT_COUNTDOWN), TURNS_IN_TRANSIT, false);

        Assertions.assertEquals(List.of(), game.getWaitingPlayerNames(runtimeRandom));
        Assertions.assertEquals(GAME_GENERATOR_STATE, runtimeRandom.getState(), "no dialog, no shuffle");
        Assertions.assertEquals(0, runtimeRandom.getDrawCount());
    }

    @Test
    void network_cheat_check_leaves_the_game_generator() throws IOException {
        LiveGame game = new LiveGame(createSetup(createTable()), SEED, HOST_PLAYER);
        RuntimeRandom runtimeRandom = new RuntimeRandom(GAME_GENERATOR_STATE);

        game.attachNetwork(createWaitingTransport(List.of(), NO_WAIT_COUNTDOWN), TURNS_IN_TRANSIT, false);
        game.setLocalCheatsAllowed(true);

        Assertions.assertEquals(TEXT_CHEAT, game.submitText("power overwhelming", 0, true, runtimeRandom, TYPED_TIME));
        Assertions.assertEquals(GAME_GENERATOR_STATE, runtimeRandom.getState(), "a network session checks without the game's generator");
        Assertions.assertEquals(0, runtimeRandom.getDrawCount());
    }

    @Test
    void single_player_cheat_check_reseeds_the_game_generator_before_the_cheat_option_is_read() throws IOException {
        MeleeGameSetup setup = new MeleeGameSetup(readMapData(), HOST_NAME, ScrRaceIds.TERRAN, 0, new int[]{ScrRaceIds.ZERG}, GameSpeed.FASTEST);
        LiveGame game = new LiveGame(setup, SEED);
        RuntimeRandom runtimeRandom = new RuntimeRandom(GAME_GENERATOR_STATE);

        Assertions.assertEquals(TEXT_DISCARDED, game.submitText("power overwhelming", 0, false, runtimeRandom, TYPED_TIME));
        Assertions.assertEquals(TYPED_TIME, runtimeRandom.getState(), "the check reseeds with the time although cheats are off");
        Assertions.assertEquals(KEY_DRAW_COUNT, runtimeRandom.getDrawCount());
    }

    @RequiresInstallation
    @Test
    void player_who_joined_again_issues_commands_from_the_slot_it_holds() throws IOException {
        LiveGame live = new LiveGame(createRepeatedJoinSetup(), SEED, HOST_PLAYER);

        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = ReplayRuntime.createLiveGame(live.getReplay(), ChkFile.parseTolerant(live.getSetup().getMapData()), gameData,
                                                                 live.getLocalPlayer(),
                                                                 RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT,
                                                                 RunningGameScenario.INSTRUCTION_LIMIT);

            runtime.start(0, 0);
            live.startSession(runtime.getGame(), runtime.getFrameTiming());

            Assertions.assertEquals(JOINER_PLAYER, runtime.getGame().getPlayerTable().requirePlayer(RELEASED_SLOT).getId(),
                                    "the released slot keeps the player id");

            ReplayCommandIssuer issuer = ReplayCommandIssuer.resolve(runtime.getGame(), JOINER_PLAYER);

            Assertions.assertEquals(ReplayCommandIssuer.Status.RESOLVED, issuer.getStatus());
            Assertions.assertEquals(JOINER_SLOT, issuer.getRecordIndex(), "the player's commands select in the slot it holds");
            Assertions.assertEquals(JOINER_SLOT, issuer.getCommandPlayerIndex(), "the player's commands act for the slot it holds");
        }
    }

    @Test
    void request_save_game_should_queue_the_name_bytes_cut_to_the_name_buffer() throws IOException {
        // The name a + 9 Hangul is 28 bytes: the cut keeps the first two bytes of the last character.
        byte[] typed = ("a" + HANGUL.repeat(9)).getBytes(StandardCharsets.UTF_8);
        byte[] cut = Arrays.copyOf(typed, LONGEST_SAVED_GAME_NAME);
        byte[] single = Arrays.copyOf(("x".repeat(LONGEST_SAVED_GAME_NAME - 1) + HANGUL).getBytes(StandardCharsets.UTF_8), LONGEST_SAVED_GAME_NAME);

        Assertions.assertArrayEquals(createSaveGameCommand(cut), requestSaveGame(cut));
        Assertions.assertArrayEquals(createSaveGameCommand(cut), requestSaveGame(typed));
        Assertions.assertArrayEquals(createSaveGameCommand(single), requestSaveGame(single));
    }

    @Test
    void executed_save_game_command_hands_the_received_name_bytes_to_the_handler() throws IOException {
        byte[] cut = Arrays.copyOf(("a" + HANGUL.repeat(9)).getBytes(StandardCharsets.UTF_8), LONGEST_SAVED_GAME_NAME);
        LiveGame game = new LiveGame(createSetup(createTable()), SEED, HOST_PLAYER);
        List<byte[]> saved = new ArrayList<>();

        game.setSavedGameHandler(createSavedGameHandler(saved));
        game.requestSaveGame(SAVE_TIME, cut);

        // The first exchange sends the turn; the second receives and executes it.
        game.exchangeTurn(0);
        game.exchangeTurn(1);

        Assertions.assertEquals(1, saved.size());
        Assertions.assertArrayEquals(cut, saved.get(0));
    }

    @Test
    void single_player_latency_command_keeps_one_more_turn_in_transit() throws IOException {
        LiveGame game = new LiveGame(createSetup(createTable()), SEED, HOST_PLAYER);
        List<byte[]> saved = new ArrayList<>();

        game.setSavedGameHandler(createSavedGameHandler(saved));

        // A map queues the latency as EUD writes do; the first exchange sends it, the second runs it, and the third sends two turns.
        game.getOutgoingTurn().queue(new byte[]{(byte) ReplayCommandIds.LATENCY, (byte) SILENT_HIGH_LATENCY});
        game.exchangeTurn(0);
        game.exchangeTurn(1);
        game.exchangeTurn(2);
        game.requestSaveGame(SAVE_TIME, HOST_NAME.getBytes(StandardCharsets.US_ASCII));
        game.exchangeTurn(3);
        game.exchangeTurn(4);

        Assertions.assertTrue(saved.isEmpty(), "Two turns are in transit, so the Save Game command is not received yet.");

        game.exchangeTurn(5);

        Assertions.assertEquals(1, saved.size());
    }

    @Test
    void write_replay_should_write_the_saved_header_name_bytes_into_the_name_section() throws IOException {
        LiveGame live = new LiveGame(createSavedSetup(SPLIT_NAME), SEED);
        byte[] names = readReplaySections(live).get(NAME_SECTION_INDEX);

        Assertions.assertArrayEquals(SPLIT_NAME, readNameRecord(names, HOST_SLOT));
    }

    @Test
    void write_replay_should_write_the_whole_computer_nation_name_into_the_name_section() throws IOException {
        MeleeGameSetup setup = new MeleeGameSetup(readMapData(), HOST_NAME, ScrRaceIds.TERRAN, 0, new int[]{ScrRaceIds.ZERG}, GameSpeed.FASTEST);

        setup.setNationNames((race, index) -> LONG_NATION_NAME);

        List<byte[]> sections = readReplaySections(new LiveGame(setup, SEED));
        byte[] header = sections.get(0);
        int computers = 0;

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            int record = ReplayHeaderBuilder.PLAYERS_OFFSET + slot * ReplayHeaderBuilder.PLAYER_RECORD_SIZE;
            int type = header[record + ReplayHeaderBuilder.PLAYER_TYPE_OFFSET];

            if (type == PlayerTypes.COMPUTER) {
                byte[] name = readNameRecord(sections.get(NAME_SECTION_INDEX), slot);

                Assertions.assertArrayEquals(LONG_NATION_NAME.getBytes(StandardCharsets.UTF_8), name);

                computers++;
            }
        }

        Assertions.assertEquals(1, computers);
    }

    @Test
    void game_start_header_should_keep_a_human_network_name_cut_on_whole_characters() throws IOException {
        LobbyResolution.Table table = createTable();

        setRecord(table, JOINER_SLOT, JOINER_PLAYER, MeleeGameSetup.TYPE_HUMAN, LONG_HUMAN_NAME);

        LiveGame live = new LiveGame(createSetup(table), SEED, HOST_PLAYER);
        byte[] start = live.getReplay().getHeaderSection();
        List<byte[]> written = readReplaySections(live);
        int host = ReplayHeaderBuilder.PLAYERS_OFFSET + HOST_SLOT * ReplayHeaderBuilder.PLAYER_RECORD_SIZE + ReplayHeaderBuilder.PLAYER_NAME_OFFSET;
        int joiner = ReplayHeaderBuilder.PLAYERS_OFFSET + JOINER_SLOT * ReplayHeaderBuilder.PLAYER_RECORD_SIZE + ReplayHeaderBuilder.PLAYER_NAME_OFFSET;
        byte[] cutName = Arrays.copyOf(HANGUL.repeat((PLAYER_RECORD_NAME_SIZE - 1) / HANGUL_BYTES).getBytes(StandardCharsets.UTF_8),
                                       PLAYER_RECORD_NAME_SIZE);

        Assertions.assertNotNull(start);
        Assertions.assertArrayEquals(cutName, Arrays.copyOfRange(start, joiner, joiner + PLAYER_RECORD_NAME_SIZE),
                                     "The player table keeps the UTF-8 name cut to whole characters within 24 bytes.");
        Assertions.assertArrayEquals(Arrays.copyOf(HOST_NAME.getBytes(StandardCharsets.US_ASCII), PLAYER_RECORD_NAME_SIZE),
                                     Arrays.copyOfRange(start, host, host + PLAYER_RECORD_NAME_SIZE));
        Assertions.assertArrayEquals(CONVERTED_HUMAN_NAME.getBytes(StandardCharsets.US_ASCII),
                                     Arrays.copyOfRange(written.getFirst(), joiner, joiner + CONVERTED_HUMAN_NAME.length()),
                                     "A written replay's record holds the single-byte conversion.");
        Assertions.assertArrayEquals(LONG_HUMAN_NAME.getBytes(StandardCharsets.UTF_8), readNameRecord(written.get(NAME_SECTION_INDEX), JOINER_SLOT),
                                     "The name table keeps the whole name.");
    }

    @Test
    void game_start_header_should_keep_a_computer_nation_name_cut_to_the_player_record() throws IOException {
        MeleeGameSetup setup = new MeleeGameSetup(readMapData(), HOST_NAME, ScrRaceIds.TERRAN, 0, new int[]{ScrRaceIds.ZERG}, GameSpeed.FASTEST);

        setup.setNationNames((race, index) -> LONG_NATION_NAME);

        LiveGame live = new LiveGame(setup, SEED);
        byte[] start = live.getReplay().getHeaderSection();
        byte[] written = readReplaySections(live).getFirst();
        byte[] nationName = LONG_NATION_NAME.getBytes(StandardCharsets.UTF_8);
        int computers = 0;

        Assertions.assertNotNull(start);

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            int name = ReplayHeaderBuilder.PLAYERS_OFFSET + slot * ReplayHeaderBuilder.PLAYER_RECORD_SIZE + ReplayHeaderBuilder.PLAYER_NAME_OFFSET;

            if (start[name - ReplayHeaderBuilder.PLAYER_NAME_OFFSET + ReplayHeaderBuilder.PLAYER_TYPE_OFFSET] == PlayerTypes.COMPUTER) {
                byte[] expected = Arrays.copyOf(nationName, PLAYER_RECORD_NAME_SIZE);

                expected[PLAYER_RECORD_NAME_SIZE - 1] = 0;

                Assertions.assertArrayEquals(expected, Arrays.copyOfRange(start, name, name + PLAYER_RECORD_NAME_SIZE),
                                             "The player table keeps the nation name's first 24 bytes.");
                Assertions.assertArrayEquals(CONVERTED_NATION_NAME.getBytes(StandardCharsets.US_ASCII),
                                             Arrays.copyOfRange(written, name, name + CONVERTED_NATION_NAME.length()),
                                             "A written replay's record holds the single-byte conversion.");

                computers++;
            }
        }

        Assertions.assertEquals(1, computers);
    }

    @Test
    void write_replay_should_copy_the_commands_while_the_game_thread_records_them() throws IOException, InterruptedException {
        LiveGame live = new LiveGame(createSetup(createTable()), SEED, HOST_PLAYER);
        AtomicBoolean recording = new AtomicBoolean(true);
        Thread gameThread = new Thread(() -> {
            try {
                for (int turn = 0; turn < RECORDED_TURNS; turn++) {
                    live.submit(ReplayCommandIds.STOP, STOP_PAYLOAD);
                    live.exchangeTurn(turn);
                    live.recordChat(HOST_SLOT, CHAT_TEXT, turn);
                }
            } finally {
                recording.set(false);
            }
        });

        gameThread.start();

        try {
            while (recording.get()) {
                ReplayFile.parse(live.writeReplay(0, 0));
            }
        } finally {
            gameThread.join();
        }

        // The last queued stop is still in transit.
        Assertions.assertEquals(2 * RECORDED_TURNS - 1, ReplayFile.parse(live.writeReplay(0, 0)).getCommands().size());
    }

    @Test
    void submit_mod_command_should_record_the_length_prefixed_data_in_the_written_replay() throws IOException {
        LiveGame live = new LiveGame(createSetup(createTable()), SEED, HOST_PLAYER);
        byte[] data = {0x10, 0x20, 0x30};

        Assertions.assertTrue(live.submitModCommand(data));
        Assertions.assertThrows(IllegalArgumentException.class, () -> live.submitModCommand(new byte[ReplayModCommand.MAXIMUM_DATA_LENGTH + 1]));

        for (int turn = 0; turn <= TURNS_IN_TRANSIT; turn++) {
            live.exchangeTurn(turn);
        }

        List<ReplayCommand> commands = ReplayFile.parse(live.writeReplay(0, 0)).getCommands();

        Assertions.assertEquals(1, commands.size());
        Assertions.assertEquals(ReplayCommandIds.MOD_COMMAND, commands.getFirst().getTypeId());
        Assertions.assertEquals(HOST_PLAYER, commands.getFirst().getPlayerId());
        Assertions.assertArrayEquals(new byte[]{(byte) data.length, 0x10, 0x20, 0x30}, commands.getFirst().getPayload());
    }

    @Test
    void submit_mod_command_should_refuse_a_command_the_outgoing_turn_has_no_room_for() throws IOException {
        LiveGame live = new LiveGame(createSetup(createTable()), SEED, HOST_PLAYER);
        byte[] largest = new byte[ReplayModCommand.MAXIMUM_DATA_LENGTH];
        int commandLength = ReplayModCommand.HEADER_SIZE + largest.length;
        int initialLength = live.getOutgoingTurn().getLength();
        int queued = 0;

        while (live.submitModCommand(largest)) {
            queued++;
        }

        int length = live.getOutgoingTurn().getLength();

        Assertions.assertTrue(queued > 0);
        Assertions.assertTrue(length <= LiveGame.MAXIMUM_TURN_BYTES, "the queued commands fit the turn");
        Assertions.assertTrue(length + commandLength > LiveGame.MAXIMUM_TURN_BYTES, "the refused command does not fit");
        Assertions.assertEquals(initialLength + queued * commandLength, length, "the refused command leaves the turn unchanged");
    }
}
