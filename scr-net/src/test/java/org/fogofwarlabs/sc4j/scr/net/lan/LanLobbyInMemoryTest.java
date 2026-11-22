package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.engine.net.lobby.GameLobby;
import org.fogofwarlabs.sc4j.engine.net.lobby.LobbyMember;
import org.fogofwarlabs.sc4j.engine.net.lobby.LobbyState;
import org.fogofwarlabs.sc4j.engine.net.lobby.NetworkLobby;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.save.SaveGameInformation;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.setup.LobbyResolution;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderPacket;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderPeer;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.fogofwarlabs.sc4j.scr.net.proxy.GameProxySocket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Verifies the {@link LanLobby} of a host and its joiners connected in memory: the color request {@code 0x67}, the
 * host's check and answer {@code 0x68}, the colors of the lobby data {@code 0x69} for a joiner, the lobby timeout, the
 * force changes of Use Map Settings ({@code 0x43}), the host's placement of joining players with the new net player
 * commands ({@code 0x3f}) and slot tables ({@code 0x5e}) of a join, and the slot changes ({@code 0x3e}) and download
 * percents ({@code 0x3d}) every client takes, and the speed override every client's game and replay take from the game name.
 */
final class LanLobbyInMemoryTest {
    private static final @NotNull InetSocketAddress HOST = new InetSocketAddress("192.168.0.88", GameProxySocket.GAME_PORT);
    private static final @NotNull InetSocketAddress JOINER = new InetSocketAddress("192.168.0.2", GameProxySocket.GAME_PORT);
    private static final byte @NotNull [] GAME_DATA = new byte[LanGameOptions.SIZE];
    /**
     * The maximum players of the two-client network's game.
     */
    private static final int MAXIMUM_PLAYERS = 2;

    private static final int BYTE_MASK = 0xFF;

    private static final int CLOCK_BASE = 10_000_000;
    /**
     * The one-way delay of every datagram of the late joiner's network in milliseconds.
     */
    private static final int LATENCY = 2;

    private static final int HOST_SLOT = 0;
    private static final int JOINER_SLOT = 1;
    private static final int SECOND_OPEN_SLOT = 2;
    private static final int SECOND_TEAM_SLOT = 3;
    private static final int NO_SLOT = -1;
    /**
     * The record index and the slot identifier of the first observer slot.
     */
    private static final int FIRST_OBSERVER_RECORD = 8;
    private static final int FIRST_OBSERVER_SLOT = 0x80;

    private static final int HOST_PLAYER = 0;
    private static final int JOINER_PLAYER = 1;
    private static final int SECOND_JOINER_PLAYER = 2;
    private static final int NO_PLAYER = -1;

    private static final int NOT_ENDED = -1;

    private static final int TWO_CLIENTS = 2;
    private static final int THREE_CLIENTS = 3;
    private static final int FOUR_CLIENTS = 4;
    private static final int HOST_CLIENT = 0;
    private static final int JOINER_CLIENT = 1;
    private static final int SECOND_JOINER_CLIENT = 2;
    private static final int THIRD_JOINER_CLIENT = 3;

    private static final int MELEE_FORCE = 0;
    private static final int FIRST_FORCE = 1;
    private static final int SECOND_FORCE = 2;
    private static final int THIRD_FORCE = 3;

    private static final int TILESET = 1;
    private static final int MAP_SIZE = 128;
    private static final int FORCE_FLAGS_SIZE = 8;

    /**
     * The time the lobbies need to exchange a command and its answer.
     */
    private static final int SETTLE_MILLISECONDS = 500;
    private static final int JOIN_MILLISECONDS = 2000;
    private static final int BRIEFING_MILLISECONDS = 1000;
    private static final int COUNTDOWN_MILLISECONDS = 10_000;
    /**
     * The time a joined lobby needs to show every player's slot and name.
     */
    private static final int LOBBY_SETTLE_MILLISECONDS = 1000;
    /**
     * The time the host's lobby runs alone before the late joiner comes.
     */
    private static final int HOST_ALONE_MILLISECONDS = 120_000;
    /**
     * The longest time the lobbies need to pass the countdown and reach the game.
     */
    private static final int START_LIMIT_MILLISECONDS = 20_000;
    /**
     * The longest time of the late joiner's ready room, and the steps of it at which the host and the joiner press Start.
     */
    private static final int BRIEFING_LIMIT_MILLISECONDS = 8000;
    private static final int HOST_READY_STEP = 500;
    private static final int JOINER_READY_STEP = 3500;
    /**
     * A game name with the third speed override, the selector and the frame duration in milliseconds it selects.
     */
    private static final @NotNull String OVERRIDE_GAME_NAME = "Bottleneck #x3";
    private static final int OVERRIDE_INDEX = 3;
    private static final int OVERRIDE_FRAME_DURATION = 21;
    /**
     * The scenario entry of a map archive.
     */
    private static final @NotNull String SCENARIO_ENTRY = "staredit\\scenario.chk";
    /**
     * The longest time the joiner needs to find the host's map.
     */
    private static final int MAP_TRANSFER_LIMIT_MILLISECONDS = 20_000;
    /**
     * The viewport and the instruction budget of the live runtimes.
     */
    private static final int VIEWPORT_WIDTH = 640;
    private static final int VIEWPORT_HEIGHT = 480;
    private static final int INSTRUCTION_LIMIT = 100_000;
    /**
     * The own turns a live game keeps in transit.
     */
    private static final int TURNS_IN_TRANSIT = 3;
    /**
     * The time before and after the lobby timeout at which the host's timeout is checked.
     */
    private static final int TIMEOUT_MARGIN_MILLISECONDS = 1000;
    private static final int PAST_TIMEOUT_MILLISECONDS = 2000;
    /**
     * The time a chat line needs to reach the other player.
     */
    private static final int CHAT_MILLISECONDS = 200;
    /**
     * The longest time the late client's join takes.
     */
    private static final int LATE_JOIN_LIMIT_MILLISECONDS = 5000;
    /**
     * The longest time a joined client needs to queue its joined-game command.
     */
    private static final int JOIN_COMMAND_MILLISECONDS = 3000;

    /**
     * The saved game of the saved game lobby: its identifier and random state, and the lobby slots of its three players.
     */
    private static final int SAVE_GAME_IDENTIFIER = 0x6A000190;
    private static final int RANDOM_STATE = 0x12345678;
    private static final int HOST_LOBBY_SLOT = 0;
    private static final int FIRST_JOINER_LOBBY_SLOT = 2;
    private static final int SECOND_JOINER_LOBBY_SLOT = 1;

    /**
     * The Team Melee options of the team game test.
     */
    private static final @NotNull String TEAM_MELEE_OPTIONS = "0b0001000200000001010102020001010201000000000032000000" + "0000000000";
    /**
     * The name {@link InMemoryLanScenario} gives its first joiner.
     */
    private static final @NotNull String FIRST_JOINER_NAME = "joiner1";

    /**
     * The identifier of the host's refusal message {@code 0x4e}, which carries the refusal reason.
     */
    private static final int REFUSAL_MESSAGE = 0x4E;
    /**
     * The identifier of the host's slot table message {@code 0x5e}, which carries a player identifier and its block.
     */
    private static final int SLOT_TABLE_MESSAGE = 0x5E;
    /**
     * The new net player command {@code 0x3f} of the first joiner (player {@code 1}): flags WORD {@code 0}, WORD
     * {@code 1}, version WORD {@code 5}.
     */
    private static final @NotNull String JOINER_NEW_NET_PLAYER = "3f01000001000500";
    /**
     * The new net player command {@code 0x3f} of the second joiner (player {@code 2}) once it left: zero versions.
     */
    private static final @NotNull String DEPARTED_NEW_NET_PLAYER = "3f02000000000000";
    /**
     * A slot or player identifier byte after the player identifiers and before the observer identifiers {@code 0x80..0x83}: it
     * names no one.
     */
    private static final int UNNAMED_IDENTIFIER = 8;
    /**
     * A slot byte above the observer slot identifiers: it names no slot and no record index.
     */
    private static final int FOREIGN_SLOT_BYTE = 0x20;
    /**
     * The slot state command, which carries a slot and its state.
     */
    private static final byte SLOT_STATE_COMMAND = 0x44;
    /**
     * The move request command, which carries a force, an observer byte and a slot.
     */
    private static final byte MOVE_REQUEST_COMMAND = 0x38;
    /**
     * The slot change command, which carries a slot, a player identifier, a type, a race and a force.
     */
    private static final byte CHANGE_GAME_SLOT_COMMAND = 0x3E;
    /**
     * The swap command, which carries two slots.
     */
    private static final byte SWAP_PLAYERS_COMMAND = 0x45;

    private static final int COMPLETE_PERCENT = 100;
    /**
     * A download percent byte above {@link #COMPLETE_PERCENT}.
     */
    private static final int EXCESSIVE_PERCENT = 150;

    /**
     * The colors the color tests request: one the host grants, the host's own, one in the host color's group of similar
     * colors, one reserved on tileset {@code 1}, one for another player's slot and one in the lobby data.
     */
    private static final int GRANTED_COLOR = 3;
    private static final int HOST_COLOR = 0xD;
    private static final int SIMILAR_COLOR = 1;
    private static final int RESERVED_COLOR = 0x14;
    private static final int OTHER_SLOT_COLOR = 5;
    private static final int LOBBY_DATA_COLOR = 4;
    /**
     * The color of a slot that holds no granted color.
     */
    private static final int DEFAULT_COLOR = 0x16;

    private static final int STATE_JOINED = 3;
    private static final int STATE_COUNTDOWN = 5;
    private static final int STATE_GAME = 9;

    private static final int BRIEFING_NONE = 0;
    private static final int BRIEFING_RUNNING = 1;
    private static final int BRIEFING_READY = 2;

    private static final int NO_REFUSAL = -1;
    private static final int UNJOINABLE_ERROR = 4;

    private static final int STATE_STARTED = 8;

    private static final int HUMAN_TYPE = 2;
    private static final int EITHER_PREFER_COMPUTER_TYPE = 5;
    private static final int OPEN_TYPE = 6;
    private static final int CLOSED_TYPE = 8;
    private static final int OBSERVER_TYPE = 9;
    private static final int OPEN_OBSERVER_TYPE = 0x0C;

    private static final int ZERG_RACE = 0;
    private static final int TERRAN_RACE = 1;
    private static final int PROTOSS_RACE = 2;
    private static final int RANDOM_RACE = 6;

    private static final class Datagram {
        private final @NotNull InetSocketAddress source;
        private final byte @NotNull [] packet;

        private Datagram(@NotNull InetSocketAddress source, byte @NotNull [] packet) {
            this.source = source;
            this.packet = packet;
        }
    }

    private static final class Network {
        private final @NotNull String joinerName;

        private int now;

        private final @NotNull ArrayDeque<Datagram> toHost;
        private final @NotNull ArrayDeque<Datagram> toJoiner;

        private final @NotNull ProviderSession hostSession;
        private final @NotNull ProviderSession joinerSession;

        private final int @NotNull [] briefingEnd;

        private final @NotNull LanLobby host;

        private @Nullable LanLobby joiner;
        private boolean joinerSilent;

        private Network(byte @Nullable [] lobbyData) {
            this(lobbyData == null
                 ? LanTestResources.createCapturedLobbySetup("host")
                 : LanTestResources.createCapturedLobbySetup("host", lobbyData));
        }

        private Network(@NotNull LanLobby.Setup setup) {
            this(setup, GAME_DATA);
        }

        private Network(@NotNull LanLobby.Setup setup, byte @NotNull [] gameData) {
            this(setup, gameData, "joiner");
        }

        private Network(@NotNull LanLobby.Setup setup, byte @NotNull [] gameData, @NotNull String joinerName) {
            this.joinerName = joinerName;

            this.now = CLOCK_BASE;

            this.toHost   = new ArrayDeque<>();
            this.toJoiner = new ArrayDeque<>();

            this.hostSession   = new ProviderSession((address, packet) -> this.toJoiner.add(new Datagram(HOST, packet.getEncodedBytes())),
                                                     () -> this.now);
            this.joinerSession = new ProviderSession((address, packet) -> this.toHost.add(new Datagram(JOINER, packet.getEncodedBytes())),
                                                     () -> this.now);

            this.briefingEnd = new int[]{NOT_ENDED, NOT_ENDED};

            this.hostSession.createGame("game", "statistics", "", MAXIMUM_PLAYERS, 0, 0, 0, gameData, "host", "");
            this.host = new LanLobby(this.hostSession, () -> this.now, setup, "host", LanTestResources.createCapturedHostBlock());

            this.joiner       = null;
            this.joinerSilent = false;

            this.joinerSession.beginJoin(HOST, joinerName, "", "");
            this.run(JOIN_MILLISECONDS);

            Assertions.assertTrue(this.requireJoinerLobby().getPlayerSlot(JOINER_PLAYER) >= 0, "the joiner holds a slot");
        }

        private @NotNull LanLobby requireJoinerLobby() {
            Assertions.assertNotNull(this.joiner, "the joiner's lobby");

            return this.joiner;
        }

        /**
         * Runs the ready room on both clients: the network each millisecond and a ready room step; records the own
         * expected turn at which each client's room ended.
         */
        private void runBriefing() {
            for (int step = 0; step < BRIEFING_MILLISECONDS; step++) {
                this.now++;

                while (!this.toHost.isEmpty() || !this.toJoiner.isEmpty()) {
                    for (Datagram datagram = this.toHost.poll(); datagram != null; datagram = this.toHost.poll()) {
                        this.hostSession.receive(datagram.source, datagram.packet);
                    }

                    for (Datagram datagram = this.toJoiner.poll(); datagram != null; datagram = this.toJoiner.poll()) {
                        this.joinerSession.receive(datagram.source, datagram.packet);
                    }
                }

                this.hostSession.update();
                this.joinerSession.update();

                if (this.host.updateBriefing()) {
                    this.briefingEnd[HOST_CLIENT] = this.hostSession.getSelf().getChannel(ProviderPacket.TURN).getExpectedSequence();
                }

                if (this.requireJoinerLobby().updateBriefing()) {
                    this.briefingEnd[JOINER_CLIENT] = this.joinerSession.getSelf().getChannel(ProviderPacket.TURN).getExpectedSequence();
                }
            }
        }

        private void run(int milliseconds) {
            for (int step = 0; step < milliseconds; step++) {
                this.now++;

                while (!this.toHost.isEmpty() || !this.toJoiner.isEmpty()) {
                    for (Datagram datagram = this.toHost.poll(); datagram != null; datagram = this.toHost.poll()) {
                        if (!this.joinerSilent) {
                            this.hostSession.receive(datagram.source, datagram.packet);
                        }
                    }

                    for (Datagram datagram = this.toJoiner.poll(); datagram != null; datagram = this.toJoiner.poll()) {
                        this.joinerSession.receive(datagram.source, datagram.packet);
                    }
                }

                this.hostSession.update();
                this.joinerSession.update();

                if (this.joinerSession.pollJoin() == ProviderSession.JoinState.JOINED && this.joiner == null) {
                    this.joiner = new LanLobby(this.joinerSession, () -> this.now, null, this.joinerName, new byte[LanLobby.PLAYER_BLOCK_SIZE]);
                }

                this.host.update();

                if (this.joiner != null) {
                    this.joiner.update();
                }
            }
        }
    }

    private static byte @NotNull [] createDefaultLobbyData() {
        byte[] lobbyData = new byte[LanLobby.LOBBY_DATA_SIZE];

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            lobbyData[LanLobby.LOBBY_DATA_COLORS + slot] = (byte) LanLobby.COLOR_DEFAULT;
        }

        return lobbyData;
    }

    /**
     * Creates a {@link LanLobby.Setup} instance with four Use Map Settings slots. The host occupies slot {@code 0} in force
     * {@code 1}; slot {@code 1} is open in force {@code 1} and slots {@code 2} and {@code 3} are open in force {@code 2}.
     * The joiner takes slot {@code 2}, in the force with the most open slots.
     *
     * @return the created {@link LanLobby.Setup} instance
     */
    private static @NotNull LanLobby.Setup createForcesSetup() {
        MeleeGameSetup.PlayerRecord[] records = new MeleeGameSetup.PlayerRecord[LanLobby.RECORDS];
        int selectableRace = LobbyResolution.SELECTABLE_RACE;

        records[HOST_SLOT]        = new MeleeGameSetup.PlayerRecord(HOST_PLAYER, PlayerTypes.HUMAN, selectableRace, FIRST_FORCE, "host");
        records[JOINER_SLOT]      = new MeleeGameSetup.PlayerRecord(NO_PLAYER, LanLobby.TYPE_OPEN, selectableRace, FIRST_FORCE, "");
        records[SECOND_OPEN_SLOT] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, LanLobby.TYPE_OPEN, selectableRace, SECOND_FORCE, "");
        records[SECOND_TEAM_SLOT] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, LanLobby.TYPE_OPEN, selectableRace, SECOND_FORCE, "");

        for (int slot = SECOND_TEAM_SLOT + 1; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            records[slot] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, PlayerTypes.NONE, 0, 0, "");
        }

        for (int slot = PlayerTable.PLAYABLE_PLAYER_SLOTS; slot < LanLobby.RECORDS; slot++) {
            records[slot] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, PlayerTypes.CLOSED, 0, 0, "");
        }

        return new LanLobby.Setup(TILESET,
                                  MAP_SIZE, MAP_SIZE,
                                  HexFormat.of().parseHex("060606060000000000000000"),
                                  HexFormat.of().parseHex("070707070000000000000000"),
                                  HexFormat.of().parseHex("060606060000000000000000"),
                                  HexFormat.of().parseHex("010102020000000000000000"), new byte[FORCE_FLAGS_SIZE],
                                  createDefaultLobbyData(),
                                  records,
                                  "forces.scx", 1, 1);
    }

    /**
     * Creates a {@link LanLobby.Setup} instance for Melee: host in slot {@code 0}, slot {@code 1} open, slot {@code 2} open,
     * observer slots {@code 0x80} and {@code 0x81} open.
     *
     * @return the created {@link LanLobby.Setup} instance
     */
    private static @NotNull LanLobby.Setup createMeleeSetup() {
        MeleeGameSetup.PlayerRecord[] records = new MeleeGameSetup.PlayerRecord[LanLobby.RECORDS];

        records[HOST_SLOT]        = new MeleeGameSetup.PlayerRecord(HOST_PLAYER, PlayerTypes.HUMAN, ScrRaceIds.RANDOM, MELEE_FORCE, "host");
        records[JOINER_SLOT]      = new MeleeGameSetup.PlayerRecord(NO_PLAYER, LanLobby.TYPE_OPEN, ScrRaceIds.RANDOM, MELEE_FORCE, "");
        records[SECOND_OPEN_SLOT] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, LanLobby.TYPE_OPEN, ScrRaceIds.RANDOM, MELEE_FORCE, "");

        for (int slot = SECOND_OPEN_SLOT + 1; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            records[slot] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, PlayerTypes.NONE, 0, 0, "");
        }

        addObserverRecords(records);

        return new LanLobby.Setup(TILESET,
                                  MAP_SIZE, MAP_SIZE,
                                  HexFormat.of().parseHex("060606000000000000000000"),
                                  HexFormat.of().parseHex("060606000000000000000000"),
                                  HexFormat.of().parseHex("060606000000000000000000"),
                                  new byte[LanLobby.RECORDS], new byte[FORCE_FLAGS_SIZE],
                                  createDefaultLobbyData(),
                                  records,
                                  "melee.scm", 1, 1);
    }

    /**
     * Creates a {@link LanLobby.Setup} instance with two teams: slots {@code 0..2} team {@code 1} (the host in slot
     * {@code 0}), slot {@code 3} team {@code 2}.
     *
     * @return the created {@link LanLobby.Setup} instance
     */
    private static @NotNull LanLobby.Setup createTeamSetup() {
        MeleeGameSetup.PlayerRecord[] records = new MeleeGameSetup.PlayerRecord[LanLobby.RECORDS];

        records[HOST_SLOT]        = new MeleeGameSetup.PlayerRecord(HOST_PLAYER, PlayerTypes.HUMAN, ScrRaceIds.RANDOM, FIRST_FORCE, "host");
        records[JOINER_SLOT]      = new MeleeGameSetup.PlayerRecord(NO_PLAYER, LanLobby.TYPE_OPEN, ScrRaceIds.RANDOM, FIRST_FORCE, "");
        records[SECOND_OPEN_SLOT] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, LanLobby.TYPE_OPEN, ScrRaceIds.RANDOM, FIRST_FORCE, "");
        records[SECOND_TEAM_SLOT] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, LanLobby.TYPE_OPEN, ScrRaceIds.RANDOM, SECOND_FORCE, "");

        for (int slot = SECOND_TEAM_SLOT + 1; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            records[slot] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, PlayerTypes.NONE, 0, 0, "");
        }

        addObserverRecords(records);

        return new LanLobby.Setup(TILESET,
                                  MAP_SIZE, MAP_SIZE,
                                  HexFormat.of().parseHex("060606060000000000000000"),
                                  HexFormat.of().parseHex("060606060000000000000000"),
                                  HexFormat.of().parseHex("060606060000000000000000"),
                                  new byte[LanLobby.RECORDS], new byte[FORCE_FLAGS_SIZE],
                                  createDefaultLobbyData(),
                                  records,
                                  "team.scm", 1, 1);
    }

    /**
     * Creates a {@link LanLobby.Setup} instance of a melee saved game whose lobby packed its humans: the host (Protoss)
     * in slot {@code 0}, then the open slots {@code 1} (Terran) and {@code 2} (Zerg). In the saved game the host played
     * lobby slot {@code 0}, the first joiner lobby slot {@code 2} (Zerg) and the second joiner lobby slot {@code 1}
     * (Terran); each player's record slot is its lobby slot.
     *
     * @return the created {@link LanLobby.Setup} instance
     */
    private static @NotNull LanLobby.Setup createSavedMeleeSetup() {
        MeleeGameSetup.PlayerRecord[] records = new MeleeGameSetup.PlayerRecord[LanLobby.RECORDS];
        byte[] savedSlots = new byte[LanLobby.RECORDS];
        byte[] savedRaces = new byte[LanLobby.RECORDS];

        records[HOST_SLOT]        = new MeleeGameSetup.PlayerRecord(HOST_PLAYER, PlayerTypes.HUMAN, ScrRaceIds.PROTOSS, MELEE_FORCE, "host");
        records[JOINER_SLOT]      = new MeleeGameSetup.PlayerRecord(NO_PLAYER, LanLobby.TYPE_OPEN, ScrRaceIds.TERRAN, MELEE_FORCE, "");
        records[SECOND_OPEN_SLOT] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, LanLobby.TYPE_OPEN, ScrRaceIds.ZERG, MELEE_FORCE, "");

        for (int slot = SECOND_OPEN_SLOT + 1; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            records[slot] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, PlayerTypes.NONE, 0, 0, "");
        }

        for (int slot = PlayerTable.PLAYABLE_PLAYER_SLOTS; slot < LanLobby.RECORDS; slot++) {
            records[slot] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, PlayerTypes.CLOSED, 0, 0, "");
        }

        Arrays.fill(savedSlots, (byte) LanLobby.NO_SAVED_SLOT);
        savedSlots[HOST_SLOT] = HOST_LOBBY_SLOT;

        savedRaces[HOST_LOBBY_SLOT]          = (byte) ScrRaceIds.PROTOSS;
        savedRaces[SECOND_JOINER_LOBBY_SLOT] = (byte) ScrRaceIds.TERRAN;
        savedRaces[FIRST_JOINER_LOBBY_SLOT]  = (byte) ScrRaceIds.ZERG;

        LanLobby.Setup setup = new LanLobby.Setup(TILESET,
                                                  MAP_SIZE, MAP_SIZE,
                                                  HexFormat.of().parseHex("060606000000000000000000"),
                                                  HexFormat.of().parseHex("020100000000000000000000"),
                                                  HexFormat.of().parseHex("060606000000000000000000"),
                                                  new byte[LanLobby.RECORDS], new byte[FORCE_FLAGS_SIZE],
                                                  createDefaultLobbyData(),
                                                  records,
                                                  "saved.scm", 1, 1);

        return setup.withSavedGame(new SaveGameInformation(SAVE_GAME_IDENTIFIER, RANDOM_STATE, HOST_LOBBY_SLOT, HOST_LOBBY_SLOT),
                                   savedSlots, savedRaces);
    }

    /**
     * Creates a {@link LanLobby.Setup} instance of a team game's saved game, which keeps every slot in place: the host
     * (Protoss) in slot {@code 0} of team {@code 1}, the open slot {@code 1} (Terran) of team {@code 1} and the open slot
     * {@code 2} (Zerg) of team {@code 2}. Each player's record slot is its lobby slot.
     *
     * @return the created {@link LanLobby.Setup} instance
     */
    private static @NotNull LanLobby.Setup createSavedTeamSetup() {
        MeleeGameSetup.PlayerRecord[] records = new MeleeGameSetup.PlayerRecord[LanLobby.RECORDS];
        byte[] savedSlots = new byte[LanLobby.RECORDS];
        byte[] savedRaces = new byte[LanLobby.RECORDS];

        records[HOST_SLOT]        = new MeleeGameSetup.PlayerRecord(HOST_PLAYER, PlayerTypes.HUMAN, ScrRaceIds.PROTOSS, FIRST_FORCE, "host");
        records[JOINER_SLOT]      = new MeleeGameSetup.PlayerRecord(NO_PLAYER, LanLobby.TYPE_OPEN, ScrRaceIds.TERRAN, FIRST_FORCE, "");
        records[SECOND_OPEN_SLOT] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, LanLobby.TYPE_OPEN, ScrRaceIds.ZERG, SECOND_FORCE, "");

        for (int slot = SECOND_OPEN_SLOT + 1; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            records[slot] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, PlayerTypes.NONE, 0, 0, "");
        }

        for (int slot = PlayerTable.PLAYABLE_PLAYER_SLOTS; slot < LanLobby.RECORDS; slot++) {
            records[slot] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, PlayerTypes.CLOSED, 0, 0, "");
        }

        Arrays.fill(savedSlots, (byte) LanLobby.NO_SAVED_SLOT);
        savedSlots[HOST_SLOT] = HOST_LOBBY_SLOT;

        savedRaces[HOST_SLOT]        = (byte) ScrRaceIds.PROTOSS;
        savedRaces[JOINER_SLOT]      = (byte) ScrRaceIds.TERRAN;
        savedRaces[SECOND_OPEN_SLOT] = (byte) ScrRaceIds.ZERG;

        LanLobby.Setup setup = new LanLobby.Setup(TILESET,
                                                  MAP_SIZE, MAP_SIZE,
                                                  HexFormat.of().parseHex("060606000000000000000000"),
                                                  HexFormat.of().parseHex("020100000000000000000000"),
                                                  HexFormat.of().parseHex("060606000000000000000000"),
                                                  new byte[LanLobby.RECORDS], new byte[FORCE_FLAGS_SIZE],
                                                  createDefaultLobbyData(),
                                                  records,
                                                  "saved-team.scm", 1, 1);

        return setup.withSavedGame(new SaveGameInformation(SAVE_GAME_IDENTIFIER, RANDOM_STATE, HOST_LOBBY_SLOT, HOST_LOBBY_SLOT),
                                   savedSlots, savedRaces);
    }

    /**
     * Adds two open observer slots and two closed observer slots after the player slots.
     *
     * @param records the lobby records
     */
    private static void addObserverRecords(@Nullable MeleeGameSetup.PlayerRecord @NotNull [] records) {
        records[FIRST_OBSERVER_RECORD]     = new MeleeGameSetup.PlayerRecord(NO_PLAYER, LanLobby.TYPE_OBSERVER_OPEN, 0, 0, "");
        records[FIRST_OBSERVER_RECORD + 1] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, LanLobby.TYPE_OBSERVER_OPEN, 0, 0, "");
        records[FIRST_OBSERVER_RECORD + 2] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, PlayerTypes.CLOSED, 0, 0, "");
        records[FIRST_OBSERVER_RECORD + 3] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, PlayerTypes.CLOSED, 0, 0, "");
    }

    private static @NotNull LanLobby requireLobby(@NotNull InMemoryLanScenario network, int client) {
        LanLobby lobby = network.lobbies[client];

        Assertions.assertNotNull(lobby, "the lobby of client " + client);

        return lobby;
    }

    /**
     * Tells whether a client sent a message.
     *
     * @param network the network
     * @param client  the client
     * @param payload the whole message
     * @return {@code true} if the client sent the message, {@code false} otherwise
     */
    private static boolean hasSentMessage(@NotNull InMemoryLanScenario network, int client, byte @NotNull [] payload) {
        for (ProviderPacket packet : network.sentPackets.get(client)) {
            if (packet.getType() == ProviderPacket.MESSAGE && Arrays.equals(packet.getPayloadBytes(), payload)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Tells whether one of a client's turns holds a command.
     *
     * @param network the network
     * @param client  the client
     * @param command the command bytes
     * @return {@code true} if a turn of the client holds the command, {@code false} otherwise
     */
    private static boolean hasSentTurnCommand(@NotNull InMemoryLanScenario network, int client, byte @NotNull [] command) {
        for (ProviderPacket packet : network.sentPackets.get(client)) {
            byte[] turn = packet.getPayloadBytes();

            if (packet.getType() != ProviderPacket.TURN) {
                continue;
            }

            for (int offset = 0; offset + command.length <= turn.length; offset++) {
                if (Arrays.equals(turn, offset, offset + command.length, command, 0, command.length)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Tells whether a client sent a slot table {@code 0x5e} of a player, from one of its packets on.
     *
     * @param network     the network
     * @param client      the client
     * @param firstPacket the index of the client's first packet looked at
     * @param player      the player identifier the slot table names
     * @return {@code true} if the client sent the player's slot table, {@code false} otherwise
     */
    private static boolean hasSentSlotTable(@NotNull InMemoryLanScenario network, int client, int firstPacket, int player) {
        List<ProviderPacket> packets = network.sentPackets.get(client);

        for (int index = firstPacket; index < packets.size(); index++) {
            ProviderPacket packet = packets.get(index);
            byte[] message = packet.getPayloadBytes();

            if (packet.getType() == ProviderPacket.MESSAGE && message.length > 1 && (message[0] & BYTE_MASK) == SLOT_TABLE_MESSAGE &&
                (message[1] & BYTE_MASK) == player) {
                return true;
            }
        }

        return false;
    }

    /**
     * Creates the bytes of a slot change command {@code 0x3e}.
     *
     * @param slot   the slot
     * @param player the player identifier byte
     * @param type   the type
     * @param race   the race
     * @param force  the force
     * @return the command bytes
     */
    private static byte @NotNull [] createSlotCommand(int slot, int player, int type, int race, int force) {
        return new byte[]{CHANGE_GAME_SLOT_COMMAND, (byte) slot, (byte) player, (byte) type, (byte) race, (byte) force};
    }

    /**
     * Takes a lobby's notices and describes those of players that left or were dropped.
     *
     * @param lobby the lobby
     * @return the kind and the name of each departure notice, in order
     */
    private static @NotNull List<String> pollDepartureNotices(@NotNull LanLobby lobby) {
        List<String> departures = new ArrayList<>();

        for (LanLobby.Notice notice = lobby.pollNotice(); notice != null; notice = lobby.pollNotice()) {
            if (notice.getKind() != LanLobby.Notice.JOINED) {
                departures.add(notice.getKind() + " " + notice.getName());
            }
        }

        return departures;
    }

    /**
     * Joins two players to the melee saved game of {@link #createSavedMeleeSetup()} one after the other and checks on every
     * client that the first takes slot {@code 1} and the second slot {@code 2}, each with its own saved lobby race.
     *
     * @param first      the saved game of the first joiner
     * @param firstRace  the first joiner's saved lobby race
     * @param second     the saved game of the second joiner
     * @param secondRace the second joiner's saved lobby race
     */
    private static void assertSavedLobbyRaces(@NotNull SaveGameInformation first, int firstRace,
                                              @NotNull SaveGameInformation second, int secondRace) {
        InMemoryLanScenario network = new InMemoryLanScenario(THREE_CLIENTS);

        network.host(createSavedMeleeSetup(), LanGameOptions.MELEE.encode(), null);
        network.openLobby(JOINER_CLIENT, first);
        network.waitForSlot(JOINER_CLIENT);
        network.openLobby(SECOND_JOINER_CLIENT, second);
        network.waitForSlot(SECOND_JOINER_CLIENT);
        network.run(SETTLE_MILLISECONDS);

        for (int client = HOST_CLIENT; client <= SECOND_JOINER_CLIENT; client++) {
            LanLobby lobby = requireLobby(network, client);

            Assertions.assertEquals(JOINER_PLAYER, lobby.getRecord(JOINER_SLOT).getPlayerId(), "the first joiner takes slot 1");
            Assertions.assertEquals(firstRace, lobby.getRecord(JOINER_SLOT).getRace(), "the first joiner's saved race");
            Assertions.assertEquals(SECOND_JOINER_PLAYER, lobby.getRecord(SECOND_OPEN_SLOT).getPlayerId(), "the second joiner takes slot 2");
            Assertions.assertEquals(secondRace, lobby.getRecord(SECOND_OPEN_SLOT).getRace(), "the second joiner's saved race");
        }
    }


    /**
     * Checks the ready room of a network game: the turn stream goes on through the room, each Start sends the ready command {@code 0x54}, and the
     * room ends on both clients at the same turn once every player is ready.
     */
    @Test
    void ready_room_ends_on_every_client_at_the_same_turn_once_all_are_ready() {
        Network network = new Network((byte[]) null);

        Assertions.assertTrue(network.host.startGame());

        network.run(COUNTDOWN_MILLISECONDS);

        Assertions.assertEquals(STATE_GAME, network.host.getState());
        Assertions.assertEquals(STATE_GAME, network.requireJoinerLobby().getState());

        network.host.beginBriefing();
        network.requireJoinerLobby().beginBriefing();
        network.runBriefing();

        Assertions.assertEquals(BRIEFING_RUNNING, network.host.getBriefingState());

        network.host.readyBriefing();
        network.runBriefing();

        Assertions.assertEquals(BRIEFING_READY, network.host.getBriefingState());
        Assertions.assertTrue(network.requireJoinerLobby().isBriefingReady(HOST_PLAYER));
        Assertions.assertFalse(network.host.isBriefingReady(JOINER_PLAYER));
        Assertions.assertArrayEquals(new int[]{NOT_ENDED, NOT_ENDED}, network.briefingEnd, "the host waits for the joiner");

        network.requireJoinerLobby().readyBriefing();
        network.runBriefing();

        Assertions.assertEquals(BRIEFING_NONE, network.host.getBriefingState());
        Assertions.assertEquals(BRIEFING_NONE, network.requireJoinerLobby().getBriefingState());
        Assertions.assertTrue(network.briefingEnd[HOST_CLIENT] > 0);
        Assertions.assertEquals(network.briefingEnd[HOST_CLIENT], network.briefingEnd[JOINER_CLIENT], "both rooms end after the same turn");
    }

    /**
     * Checks the live case: the host's lobby ran alone for minutes before the joiner came (high turn numbers, the joiner taken as joined late until
     * its first turn), the host presses the ready room's Start first and the joiner seconds later. The host's room waits for the joiner's ready
     * command (every player whose turn status has {@link ProviderSession#TURN_PRESENT} must have its ready flag) and both rooms end after the same
     * turn.
     */
    @Test
    void late_joiner_ready_room_ends_on_the_same_turn_as_the_host_after_its_own_start() {
        InMemoryLanScenario network = new InMemoryLanScenario(TWO_CLIENTS);
        int[] end = {NOT_ENDED, NOT_ENDED};

        network.latency = LATENCY;
        network.host(LanTestResources.createCapturedLobbySetup("host"), GAME_DATA, null);
        network.run(HOST_ALONE_MILLISECONDS);
        network.join(JOINER_CLIENT, null, null);
        network.run(LOBBY_SETTLE_MILLISECONDS);

        LanLobby host = network.lobbies[HOST_CLIENT];
        LanLobby joiner = network.lobbies[JOINER_CLIENT];

        Assertions.assertNotNull(host);
        Assertions.assertNotNull(joiner);
        Assertions.assertTrue(host.startGame());

        network.runUntil(START_LIMIT_MILLISECONDS, lan -> {
            LanLobby currentHost = lan.lobbies[HOST_CLIENT];

            Assertions.assertNotNull(currentHost);

            if (currentHost.getState() != STATE_GAME) {
                return false;
            }

            LanLobby currentJoiner = lan.lobbies[JOINER_CLIENT];

            Assertions.assertNotNull(currentJoiner);

            return currentJoiner.getState() == STATE_GAME;
        });

        Assertions.assertEquals(STATE_GAME, joiner.getState());

        host.beginBriefing();
        joiner.beginBriefing();

        for (int step = 0; step < BRIEFING_LIMIT_MILLISECONDS && (end[HOST_CLIENT] < 0 || end[JOINER_CLIENT] < 0); step++) {
            network.now++;
            network.deliver();

            if (step == HOST_READY_STEP) {
                host.readyBriefing();
            }

            if (step == JOINER_READY_STEP) {
                Assertions.assertEquals(NOT_ENDED, end[HOST_CLIENT], "the host's room waits for the joiner");
                Assertions.assertEquals(BRIEFING_READY, host.getBriefingState());
                Assertions.assertFalse(host.isBriefingReady(JOINER_PLAYER));

                joiner.readyBriefing();
            }

            if (end[HOST_CLIENT] < 0 && host.updateBriefing()) {
                end[HOST_CLIENT] = network.sessions[HOST_CLIENT].getSelf().getChannel(ProviderPacket.TURN).getExpectedSequence();
            }

            if (end[JOINER_CLIENT] < 0 && joiner.updateBriefing()) {
                end[JOINER_CLIENT] = network.sessions[JOINER_CLIENT].getSelf().getChannel(ProviderPacket.TURN).getExpectedSequence();
            }
        }

        Assertions.assertTrue(end[HOST_CLIENT] > 0 && end[JOINER_CLIENT] > 0, "both rooms ended");
        Assertions.assertEquals(end[HOST_CLIENT], end[JOINER_CLIENT], "both rooms end after the same turn");
    }

    @Test
    void every_client_reads_the_room_as_an_engine_lobby() {
        Network network = new Network((byte[]) null);

        network.run(LOBBY_SETTLE_MILLISECONDS);

        GameLobby host = network.host;
        GameLobby joiner = network.requireJoinerLobby();

        Assertions.assertTrue(host.isHost());
        Assertions.assertFalse(joiner.isHost());

        for (GameLobby lobby : List.of(host, joiner)) {
            Assertions.assertEquals(LobbyState.OPEN, lobby.getLobbyState());
            Assertions.assertEquals(List.of("host " + HOST_SLOT, "joiner " + JOINER_SLOT),
                                    lobby.getMembers().stream().map(member -> member.getName() + " " + member.getSlotIndex()).toList());
            Assertions.assertTrue(lobby.getMembers().stream().noneMatch(member -> member.getSlotIndex() == NetworkLobby.OBSERVER_SLOT));
        }

        LobbyMember hostMember = host.getLocalMember();
        LobbyMember joinerMember = joiner.getLocalMember();

        Assertions.assertNotNull(hostMember);
        Assertions.assertNotNull(joinerMember);
        Assertions.assertEquals(HOST_PLAYER, hostMember.getPeer().getNumber());
        Assertions.assertEquals(JOINER_PLAYER, joinerMember.getPeer().getNumber());
    }

    @Test
    void every_client_names_both_players() {
        Network network = new Network((byte[]) null);

        network.run(LOBBY_SETTLE_MILLISECONDS);

        for (LanLobby lobby : new LanLobby[]{network.host, network.requireJoinerLobby()}) {
            Assertions.assertEquals("host", lobby.getRecord(HOST_SLOT).getName());
            Assertions.assertEquals("joiner", lobby.getRecord(JOINER_SLOT).getName());
        }
    }

    @Test
    void host_grants_a_color_and_every_client_shows_it() {
        Network network = new Network((byte[]) null);

        Assertions.assertEquals(DEFAULT_COLOR, network.requireJoinerLobby().getColor(JOINER_SLOT));

        network.requireJoinerLobby().changeColor(LanLobby.OWN_SLOT, GRANTED_COLOR);
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(GRANTED_COLOR, network.host.getColor(JOINER_SLOT));
        Assertions.assertEquals(GRANTED_COLOR, network.requireJoinerLobby().getColor(JOINER_SLOT));

        network.host.changeColor(LanLobby.OWN_SLOT, HOST_COLOR);
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(HOST_COLOR, network.host.getColor(HOST_SLOT));
        Assertions.assertEquals(HOST_COLOR, network.requireJoinerLobby().getColor(HOST_SLOT));
    }

    @Test
    void taken_similar_or_reserved_color_falls_back_to_the_default() {
        Network network = new Network((byte[]) null);

        network.host.changeColor(LanLobby.OWN_SLOT, HOST_COLOR);
        network.run(SETTLE_MILLISECONDS);

        // 1 is in 0xD's group of similar colors.
        network.requireJoinerLobby().changeColor(LanLobby.OWN_SLOT, SIMILAR_COLOR);
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(DEFAULT_COLOR, network.host.getColor(JOINER_SLOT));
        Assertions.assertEquals(DEFAULT_COLOR, network.requireJoinerLobby().getColor(JOINER_SLOT));

        // The reserved color 0xE and, on tileset 1, 0x14 and 0x15 are never granted.
        network.requireJoinerLobby().changeColor(LanLobby.OWN_SLOT, RESERVED_COLOR);
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(DEFAULT_COLOR, network.requireJoinerLobby().getColor(JOINER_SLOT));
    }

    @Test
    void client_cannot_color_another_players_slot() {
        Network network = new Network((byte[]) null);

        network.requireJoinerLobby().changeColor(HOST_SLOT, OTHER_SLOT_COLOR);
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(DEFAULT_COLOR, network.host.getColor(HOST_SLOT));
        Assertions.assertEquals(DEFAULT_COLOR, network.requireJoinerLobby().getColor(HOST_SLOT));
    }

    @Test
    void joiner_takes_the_colors_of_the_lobby_data() {
        byte[] lobbyData = createDefaultLobbyData();

        lobbyData[LanLobby.LOBBY_DATA_COLORS + HOST_SLOT] = LOBBY_DATA_COLOR;

        Network network = new Network(lobbyData);

        Assertions.assertEquals(LOBBY_DATA_COLOR, network.requireJoinerLobby().getColor(HOST_SLOT));
    }

    @Test
    void host_drops_a_joiner_that_stopped_sending_for_twenty_seconds() {
        Network network = new Network((byte[]) null);

        network.joinerSilent = true;
        network.run(LanLobby.TIMEOUT - TIMEOUT_MARGIN_MILLISECONDS);

        Assertions.assertFalse(network.host.isTimedOut());

        network.run(PAST_TIMEOUT_MILLISECONDS);

        Assertions.assertTrue(network.host.isTimedOut());
        Assertions.assertFalse(network.host.hasLeft(), "one responsive and one stalled player: the stalled one is dropped");
        Assertions.assertEquals(List.of(LanLobby.Notice.DROPPED + " joiner"), pollDepartureNotices(network.host), "the drop names the joiner");

        // The departure reached its turn: the slot returns to the template's open slot, race and force kept.
        Assertions.assertEquals(NO_PLAYER, network.host.getRecord(JOINER_SLOT).getPlayerId());
        Assertions.assertEquals(OPEN_TYPE, network.host.getRecord(JOINER_SLOT).getType());
        Assertions.assertEquals(SECOND_FORCE, network.host.getRecord(JOINER_SLOT).getTeam());
    }

    /**
     * Checks that the host and the other joiner name a joiner that left in a left notice.
     */
    @Test
    void every_other_client_names_a_joiner_that_left() {
        InMemoryLanScenario network = new InMemoryLanScenario(THREE_CLIENTS);

        network.host(createMeleeSetup(), LanGameOptions.MELEE.encode(), null);
        network.join(JOINER_CLIENT, null, null);
        network.join(SECOND_JOINER_CLIENT, null, null);
        network.run(SETTLE_MILLISECONDS);

        LanLobby host = requireLobby(network, HOST_CLIENT);
        LanLobby secondJoiner = requireLobby(network, SECOND_JOINER_CLIENT);

        for (LanLobby lobby : new LanLobby[]{host, secondJoiner}) {
            Assertions.assertEquals(List.of(), pollDepartureNotices(lobby), "no departure before the leave");
        }

        network.leave(JOINER_CLIENT);

        for (LanLobby lobby : new LanLobby[]{host, secondJoiner}) {
            Assertions.assertEquals(List.of(LanLobby.Notice.LEFT + " " + FIRST_JOINER_NAME), pollDepartureNotices(lobby));
        }
    }

    /**
     * Checks that the host and the other joiner name a joiner that the host dropped at once, as for a player whose connection ended, in a
     * dropped notice.
     */
    @Test
    void every_other_client_names_a_joiner_the_host_dropped() {
        InMemoryLanScenario network = new InMemoryLanScenario(THREE_CLIENTS);

        network.host(createMeleeSetup(), LanGameOptions.MELEE.encode(), null);
        network.join(JOINER_CLIENT, null, null);
        network.join(SECOND_JOINER_CLIENT, null, null);
        network.run(SETTLE_MILLISECONDS);

        LanLobby host = requireLobby(network, HOST_CLIENT);
        LanLobby secondJoiner = requireLobby(network, SECOND_JOINER_CLIENT);

        for (LanLobby lobby : new LanLobby[]{host, secondJoiner}) {
            Assertions.assertEquals(List.of(), pollDepartureNotices(lobby), "no departure before the drop");
        }

        network.silence(JOINER_CLIENT);

        Assertions.assertTrue(network.sessions[HOST_CLIENT].dropPlayer(JOINER_PLAYER, LanLobby.DROP_REASON));

        network.run(SETTLE_MILLISECONDS);

        for (LanLobby lobby : new LanLobby[]{host, secondJoiner}) {
            Assertions.assertEquals(List.of(LanLobby.Notice.DROPPED + " " + FIRST_JOINER_NAME), pollDepartureNotices(lobby));
            Assertions.assertEquals(NO_SLOT, lobby.getPlayerSlot(JOINER_PLAYER));
        }
    }

    @Test
    void chat_lines_reach_the_other_player_within_the_chat_limit() {
        Network network = new Network((byte[]) null);

        Assertions.assertTrue(network.requireJoinerLobby().sendChat("gl hf"));

        network.run(CHAT_MILLISECONDS);

        LanLobby.Chat chat = network.host.pollChat();

        Assertions.assertNotNull(chat);
        Assertions.assertEquals(JOINER_PLAYER, chat.getPlayer());
        Assertions.assertEquals("gl hf", chat.getText());
        Assertions.assertFalse(network.requireJoinerLobby().sendChat("x".repeat(LanLobby.CHAT_LIMIT)), "0x78 bytes with the NUL at most");
    }

    @Test
    void joiner_receives_the_force_names_and_the_host_shows_its_own() {
        byte[][] names = {"Top".getBytes(), "Bottom".getBytes(), new byte[0], "Force 4".getBytes()};
        Network network = new Network(LanTestResources.createCapturedLobbySetup("host").withForceNames(names));

        Assertions.assertEquals("Top", network.requireJoinerLobby().getForceName(0));
        Assertions.assertEquals("Bottom", network.requireJoinerLobby().getForceName(1));
        Assertions.assertEquals("", network.requireJoinerLobby().getForceName(2));
        Assertions.assertEquals("Force 4", network.requireJoinerLobby().getForceName(3));

        // The host shows its own map's names (it skips 0x4b).
        Assertions.assertEquals("Top", network.host.getForceName(0));
        Assertions.assertEquals("", network.host.getForceName(2));
    }

    @Test
    void force_names_in_utf8_and_cp949_korean_read_as_the_same_text() {
        Charset korean = Charset.forName("x-windows-949");
        byte[][] names = {"고수팀".getBytes(StandardCharsets.UTF_8), "고수팀".getBytes(korean), "Team 한국".getBytes(korean), "Force 4".getBytes()};
        Network network = new Network(LanTestResources.createCapturedLobbySetup("host").withForceNames(names));

        for (LanLobby lobby : new LanLobby[]{network.requireJoinerLobby(), network.host}) {
            Assertions.assertEquals("고수팀", lobby.getForceName(0));
            Assertions.assertEquals("고수팀", lobby.getForceName(1));
            Assertions.assertEquals("Team 한국", lobby.getForceName(2));
            Assertions.assertEquals("Force 4", lobby.getForceName(3));
        }
    }

    @Test
    void player_moves_into_another_force_and_within_its_own() {
        Network network = new Network(createForcesSetup());

        Assertions.assertEquals(JOINER_PLAYER, network.host.getRecord(SECOND_OPEN_SLOT).getPlayerId(),
                                "the joiner takes the force with the most open slots");

        // Within its own force: the next open slot after its own.
        network.requireJoinerLobby().requestForce(SECOND_FORCE);
        network.run(SETTLE_MILLISECONDS);

        for (LanLobby lobby : new LanLobby[]{network.host, network.requireJoinerLobby()}) {
            Assertions.assertEquals(JOINER_PLAYER, lobby.getRecord(SECOND_TEAM_SLOT).getPlayerId());
            Assertions.assertEquals(SECOND_FORCE, lobby.getRecord(SECOND_TEAM_SLOT).getTeam());
            Assertions.assertEquals(NO_PLAYER, lobby.getRecord(SECOND_OPEN_SLOT).getPlayerId());
            Assertions.assertEquals(OPEN_TYPE, lobby.getRecord(SECOND_OPEN_SLOT).getType());
        }

        // Into another force: its first open slot.
        network.requireJoinerLobby().requestForce(FIRST_FORCE);
        network.run(SETTLE_MILLISECONDS);

        for (LanLobby lobby : new LanLobby[]{network.host, network.requireJoinerLobby()}) {
            Assertions.assertEquals(JOINER_PLAYER, lobby.getRecord(JOINER_SLOT).getPlayerId());
            Assertions.assertEquals(FIRST_FORCE, lobby.getRecord(JOINER_SLOT).getTeam());
            Assertions.assertEquals(HUMAN_TYPE, lobby.getRecord(JOINER_SLOT).getType());
            Assertions.assertEquals(NO_PLAYER, lobby.getRecord(SECOND_TEAM_SLOT).getPlayerId());
        }

        // The own force has no other open slot: nothing moves. The slot flags were cleared by the slot changes, so a move
        // back into force 2 takes its first slot again.
        network.requireJoinerLobby().requestForce(FIRST_FORCE);
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(JOINER_PLAYER, network.host.getRecord(JOINER_SLOT).getPlayerId());

        network.requireJoinerLobby().requestForce(SECOND_FORCE);
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(JOINER_PLAYER, network.host.getRecord(SECOND_OPEN_SLOT).getPlayerId());
        Assertions.assertEquals(JOINER_PLAYER, network.requireJoinerLobby().getRecord(SECOND_OPEN_SLOT).getPlayerId());
    }

    @Test
    void force_without_an_open_slot_takes_no_one() {
        Network network = new Network(createForcesSetup());

        network.requireJoinerLobby().requestForce(THIRD_FORCE);
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(JOINER_PLAYER, network.host.getRecord(SECOND_OPEN_SLOT).getPlayerId(), "force 3 has no open slot");
    }

    /**
     * Checks the race request {@code 0x41} with a slot and a race: the host changes a computer slot's race; a joiner may change only its own slot.
     */
    @Test
    void host_changes_a_computer_slot_race_and_a_joiner_cannot() {
        Network network = new Network(createMeleeSetup(), LanGameOptions.MELEE.encode());
        int free = network.host.getPlayerSlot(JOINER_PLAYER) == JOINER_SLOT ? SECOND_OPEN_SLOT : JOINER_SLOT;

        Assertions.assertTrue(network.host.setSlotState(free, LanLobby.SLOT_COMPUTER));

        network.run(SETTLE_MILLISECONDS);
        network.host.changeRace(free, ScrRaceIds.PROTOSS);
        network.run(SETTLE_MILLISECONDS);

        for (LanLobby lobby : new LanLobby[]{network.host, network.requireJoinerLobby()}) {
            Assertions.assertEquals(PROTOSS_RACE, lobby.getRecord(free).getRace());
        }

        network.requireJoinerLobby().changeRace(free, ScrRaceIds.ZERG);
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(PROTOSS_RACE, network.requireJoinerLobby().getRecord(free).getRace(), "a joiner cannot change a computer slot");

        int own = network.host.getPlayerSlot(JOINER_PLAYER);

        network.requireJoinerLobby().changeRace(ScrRaceIds.TERRAN);
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(TERRAN_RACE, network.host.getRecord(own).getRace());
    }

    /**
     * Checks that the race request check identifies the sender by its provider identifier and maps that identifier to its slot: a joiner with the
     * host's name may change only its own slot in a game without teams, even in the host's force.
     */
    @Test
    void joiner_with_the_host_name_changes_only_its_own_race() {
        Network network = new Network(createMeleeSetup(), LanGameOptions.MELEE.encode(), "host");
        int own = network.host.getPlayerSlot(JOINER_PLAYER);

        Assertions.assertEquals(network.host.getRecord(HOST_SLOT).getTeam(), network.host.getRecord(own).getTeam());

        network.requireJoinerLobby().changeRace(HOST_SLOT, ScrRaceIds.ZERG);
        network.run(SETTLE_MILLISECONDS);

        for (LanLobby lobby : new LanLobby[]{network.host, network.requireJoinerLobby()}) {
            Assertions.assertEquals(RANDOM_RACE, lobby.getRecord(HOST_SLOT).getRace(), "the host's slot keeps its race");
        }

        network.requireJoinerLobby().changeRace(ScrRaceIds.ZERG);
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(ZERG_RACE, network.host.getRecord(own).getRace());
    }

    /**
     * Checks that in a team game the race request check lets a joiner change every slot of its own team, its host teammate's included, and no slot of
     * another team.
     */
    @Test
    void joiner_changes_the_races_of_its_own_team_only_in_a_team_game() {
        Network network = new Network(createTeamSetup(), HexFormat.of().parseHex(TEAM_MELEE_OPTIONS));
        int own = network.host.getPlayerSlot(JOINER_PLAYER);
        int otherTeamSlot = SECOND_TEAM_SLOT;

        Assertions.assertEquals(FIRST_FORCE, network.host.getRecord(own).getTeam(), "the joiner takes the team with the most open slots");

        network.requireJoinerLobby().changeRace(HOST_SLOT, ScrRaceIds.ZERG);
        network.run(SETTLE_MILLISECONDS);

        for (LanLobby lobby : new LanLobby[]{network.host, network.requireJoinerLobby()}) {
            Assertions.assertEquals(ZERG_RACE, lobby.getRecord(HOST_SLOT).getRace(), "a teammate's race");
        }

        Assertions.assertTrue(network.host.setSlotState(otherTeamSlot, LanLobby.SLOT_COMPUTER));

        network.run(SETTLE_MILLISECONDS);
        network.requireJoinerLobby().changeRace(otherTeamSlot, ScrRaceIds.TERRAN);
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(RANDOM_RACE, network.host.getRecord(otherTeamSlot).getRace(), "another team's slot");
    }

    @Test
    void host_sets_a_slot_closed_computer_and_open_again() {
        Network network = new Network(createMeleeSetup(), LanGameOptions.MELEE.encode());
        int free = network.host.getPlayerSlot(JOINER_PLAYER) == JOINER_SLOT ? SECOND_OPEN_SLOT : JOINER_SLOT;

        Assertions.assertTrue(network.host.setSlotState(free, LanLobby.SLOT_CLOSED));

        network.run(SETTLE_MILLISECONDS);

        for (LanLobby lobby : new LanLobby[]{network.host, network.requireJoinerLobby()}) {
            Assertions.assertEquals(CLOSED_TYPE, lobby.getRecord(free).getType());
        }

        Assertions.assertTrue(network.host.setSlotState(free, LanLobby.SLOT_COMPUTER));

        network.run(SETTLE_MILLISECONDS);

        for (LanLobby lobby : new LanLobby[]{network.host, network.requireJoinerLobby()}) {
            Assertions.assertEquals(EITHER_PREFER_COMPUTER_TYPE, lobby.getRecord(free).getType());
            Assertions.assertEquals(RANDOM_RACE, lobby.getRecord(free).getRace());
        }

        Assertions.assertTrue(network.host.setSlotState(free, LanLobby.SLOT_OPEN));

        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(OPEN_TYPE, network.requireJoinerLobby().getRecord(free).getType());

        // The host's own slot and a joiner's own request are refused.
        Assertions.assertFalse(network.host.setSlotState(HOST_SLOT, LanLobby.SLOT_CLOSED));
        Assertions.assertFalse(network.requireJoinerLobby().setSlotState(free, LanLobby.SLOT_CLOSED));
    }

    @Test
    void player_moves_to_the_observers_and_back_through_the_move_request() {
        Network network = new Network(createMeleeSetup(), LanGameOptions.MELEE.encode());
        int start = network.host.getPlayerSlot(JOINER_PLAYER);

        Assertions.assertTrue(start >= JOINER_SLOT && start < FIRST_OBSERVER_RECORD);

        network.requireJoinerLobby().requestObserver();
        network.run(SETTLE_MILLISECONDS);

        for (LanLobby lobby : new LanLobby[]{network.host, network.requireJoinerLobby()}) {
            Assertions.assertEquals(FIRST_OBSERVER_RECORD, lobby.getPlayerSlot(JOINER_PLAYER), "the first open observer slot");
            Assertions.assertEquals(OBSERVER_TYPE, lobby.getRecord(FIRST_OBSERVER_RECORD).getType());
            Assertions.assertEquals(OPEN_TYPE, lobby.getRecord(start).getType());
        }

        // Back to the players (force 0 of a melee game): the first open slot.
        network.requireJoinerLobby().requestForce(MELEE_FORCE);
        network.run(SETTLE_MILLISECONDS);

        for (LanLobby lobby : new LanLobby[]{network.host, network.requireJoinerLobby()}) {
            Assertions.assertEquals(JOINER_SLOT, lobby.getPlayerSlot(JOINER_PLAYER));
            Assertions.assertEquals(HUMAN_TYPE, lobby.getRecord(JOINER_SLOT).getType());
            Assertions.assertEquals(OPEN_OBSERVER_TYPE, lobby.getRecord(FIRST_OBSERVER_RECORD).getType());
        }
    }

    @Test
    void host_swaps_two_slots_on_every_client() {
        Network network = new Network(createMeleeSetup(), LanGameOptions.MELEE.encode());
        int joiner = network.host.getPlayerSlot(JOINER_PLAYER);

        network.host.swapSlots(HOST_SLOT, joiner);
        network.run(SETTLE_MILLISECONDS);

        for (LanLobby lobby : new LanLobby[]{network.host, network.requireJoinerLobby()}) {
            Assertions.assertEquals(joiner, lobby.getPlayerSlot(HOST_PLAYER));
            Assertions.assertEquals(HOST_SLOT, lobby.getPlayerSlot(JOINER_PLAYER));
        }
    }

    @Test
    void booted_player_is_told_and_dropped_and_the_host_cannot_boot_itself() {
        Network network = new Network(createMeleeSetup(), LanGameOptions.MELEE.encode());

        Assertions.assertEquals(NO_REFUSAL, network.requireJoinerLobby().getRefusal());

        int joiner = network.host.getPlayerSlot(JOINER_PLAYER);

        Assertions.assertTrue(network.host.boot(joiner, false));

        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(0, network.requireJoinerLobby().getRefusal(), "code 0: booted");
        Assertions.assertEquals(NO_SLOT, network.host.getPlayerSlot(JOINER_PLAYER));
        Assertions.assertFalse(network.host.boot(HOST_SLOT, false), "not the host itself");
    }

    /**
     * Checks that a started game rejects a later join request without assigning the client a lobby slot.
     */
    @Test
    void started_game_refuses_a_late_join() {
        int[] now = {CLOCK_BASE};
        InetSocketAddress late = new InetSocketAddress("192.168.0.3", GameProxySocket.GAME_PORT);
        ArrayDeque<Datagram> toHost = new ArrayDeque<>();
        ArrayDeque<Datagram> toLate = new ArrayDeque<>();
        ProviderSession hostSession = new ProviderSession((address, packet) -> toLate.add(new Datagram(HOST, packet.getEncodedBytes())),
                                                          () -> now[0]);
        ProviderSession lateSession = new ProviderSession((address, packet) -> toHost.add(new Datagram(late, packet.getEncodedBytes())),
                                                          () -> now[0]);

        hostSession.createGame("game", "statistics", "", PlayerTable.PLAYABLE_PLAYER_SLOTS, 0, 0, 0, GAME_DATA, "host", "");

        LanLobby host = new LanLobby(hostSession,
                                     () -> now[0],
                                     LanTestResources.createCapturedLobbySetup("host"),
                                     "host",
                                     LanTestResources.createCapturedHostBlock());

        Assertions.assertTrue(host.startGame());
        Assertions.assertEquals(STATE_STARTED, hostSession.getGameState() & STATE_STARTED);

        lateSession.beginJoin(HOST, "late", "", "");

        ProviderSession.JoinState state = ProviderSession.JoinState.CONNECTING;

        for (int step = 0;
             step < LATE_JOIN_LIMIT_MILLISECONDS && (state == ProviderSession.JoinState.CONNECTING || state == ProviderSession.JoinState.REQUESTING);
             step++) {
            now[0]++;

            while (!toHost.isEmpty() || !toLate.isEmpty()) {
                for (Datagram datagram = toHost.poll(); datagram != null; datagram = toHost.poll()) {
                    hostSession.receive(datagram.source, datagram.packet);
                }

                for (Datagram datagram = toLate.poll(); datagram != null; datagram = toLate.poll()) {
                    lateSession.receive(datagram.source, datagram.packet);
                }
            }

            hostSession.update();
            lateSession.update();
            host.update();
            state = lateSession.pollJoin();
        }

        Assertions.assertEquals(ProviderSession.JoinState.REJECTED, state);
        Assertions.assertEquals(NO_SLOT, host.getPlayerSlot(JOINER_PLAYER), "no slot for the late client");
    }

    /**
     * Checks that the slots a host's swap marked pending are not given to a player whose joined-game command the host executes before the swap's slot
     * changes: the joiner takes the next open slot and the host moves as asked.
     */
    @Test
    void joiner_skips_the_slots_a_pending_swap_holds() {
        InMemoryLanScenario network = new InMemoryLanScenario(TWO_CLIENTS);

        network.host(createMeleeSetup(), LanGameOptions.MELEE.encode(), null);

        LanLobby host = requireLobby(network, HOST_CLIENT);
        LanLobby joiner = network.openLobby(JOINER_CLIENT, null);

        network.runUntil(JOIN_COMMAND_MILLISECONDS, lan -> joiner.getState() >= STATE_JOINED);
        host.swapSlots(HOST_SLOT, JOINER_SLOT);
        network.waitForSlot(JOINER_CLIENT);
        network.run(SETTLE_MILLISECONDS);

        for (LanLobby lobby : new LanLobby[]{host, joiner}) {
            Assertions.assertEquals(JOINER_SLOT, lobby.getPlayerSlot(HOST_PLAYER), "the host took the slot it swapped with");
            Assertions.assertEquals(SECOND_OPEN_SLOT, lobby.getPlayerSlot(JOINER_PLAYER), "the joiner skipped the pending slots");
            Assertions.assertEquals(OPEN_TYPE, lobby.getRecord(HOST_SLOT).getType());
        }
    }

    /**
     * Checks that a joined-game command that the host executes after its start command gets no slot: the host refuses it with the lobby error
     * {@code 4}.
     */
    @Test
    void host_refuses_a_join_that_executes_after_the_start() {
        InMemoryLanScenario network = new InMemoryLanScenario(TWO_CLIENTS);

        network.host(createMeleeSetup(), LanGameOptions.MELEE.encode(), null);

        LanLobby host = requireLobby(network, HOST_CLIENT);

        network.openLobby(JOINER_CLIENT, null);

        Assertions.assertTrue(host.startGame());

        network.run(SETTLE_MILLISECONDS);

        // The joiner's own start command reports the lobby error 4 in both cases; the host's slot and message tell them apart.
        Assertions.assertEquals(STATE_COUNTDOWN, host.getState());
        Assertions.assertEquals(NO_SLOT, host.getPlayerSlot(JOINER_PLAYER), "no slot after the start");
        Assertions.assertTrue(hasSentMessage(network, HOST_CLIENT, new byte[]{REFUSAL_MESSAGE, UNJOINABLE_ERROR}),
                              "the host refuses the join with the lobby error 4");
    }

    /**
     * Checks that every joiner of a melee saved game takes its own saved lobby race, whichever packed slot it lands in, in both join orders: the
     * player of saved lobby slot {@code 2} (Zerg) joins first and takes slot {@code 1} (Terran), then the other order.
     */
    @Test
    void saved_game_joiners_take_their_saved_lobby_races_in_any_join_order() {
        SaveGameInformation zergPlayer = new SaveGameInformation(SAVE_GAME_IDENTIFIER, RANDOM_STATE,
                                                                 FIRST_JOINER_LOBBY_SLOT, FIRST_JOINER_LOBBY_SLOT);
        SaveGameInformation terranPlayer = new SaveGameInformation(SAVE_GAME_IDENTIFIER, RANDOM_STATE,
                                                                   SECOND_JOINER_LOBBY_SLOT, SECOND_JOINER_LOBBY_SLOT);

        assertSavedLobbyRaces(zergPlayer, ZERG_RACE, terranPlayer, TERRAN_RACE);
        assertSavedLobbyRaces(terranPlayer, TERRAN_RACE, zergPlayer, ZERG_RACE);
    }

    /**
     * Checks that a joined-game command that comes again (a joiner answers every game settings message {@code 0x4a} with one) moves its player to the
     * next open slot. The slot the player held becomes open and keeps the player's identifier, which its slot change {@code 0x3e} carries to every
     * client; only the players' slots name the slot the player holds now. The lobby table of the game start keeps the identifier on the released
     * slot too.
     */
    @Test
    void repeated_join_leaves_the_player_identifier_on_the_released_slot() {
        InMemoryLanScenario network = new InMemoryLanScenario(TWO_CLIENTS);
        LanLobby.Setup setup = createMeleeSetup();

        network.host(setup, LanGameOptions.MELEE.encode(), null);
        network.join(JOINER_CLIENT, null, null);
        network.run(SETTLE_MILLISECONDS);

        LanLobby host = requireLobby(network, HOST_CLIENT);
        LanLobby joiner = requireLobby(network, JOINER_CLIENT);

        Assertions.assertEquals(JOINER_PLAYER, host.getRecord(JOINER_SLOT).getPlayerId(), "the first join takes slot 1");

        network.sessions[HOST_CLIENT].sendMessage(JOINER_PLAYER, LanLobby.createGameSettings(setup));
        network.run(SETTLE_MILLISECONDS);

        for (LanLobby lobby : new LanLobby[]{host, joiner}) {
            Assertions.assertEquals(JOINER_PLAYER, lobby.getRecord(JOINER_SLOT).getPlayerId(), "the released slot keeps the player id");
            Assertions.assertEquals(OPEN_TYPE, lobby.getRecord(JOINER_SLOT).getType());
            Assertions.assertEquals(JOINER_PLAYER, lobby.getRecord(SECOND_OPEN_SLOT).getPlayerId(), "the next open slot");
            Assertions.assertEquals(HUMAN_TYPE, lobby.getRecord(SECOND_OPEN_SLOT).getType());
        }

        byte[] releasedSlot = createSlotCommand(JOINER_SLOT, JOINER_PLAYER, OPEN_TYPE, RANDOM_RACE, MELEE_FORCE);
        byte[] takenSlot = createSlotCommand(SECOND_OPEN_SLOT, JOINER_PLAYER, HUMAN_TYPE, RANDOM_RACE, MELEE_FORCE);

        Assertions.assertTrue(hasSentTurnCommand(network, HOST_CLIENT, releasedSlot), "the released slot's 0x3e carries the player id");
        Assertions.assertTrue(hasSentTurnCommand(network, HOST_CLIENT, takenSlot), "the taken slot's 0x3e");
        Assertions.assertEquals(FIRST_JOINER_NAME, host.getRecord(JOINER_SLOT).getName(), "the host's released record keeps the name");
        Assertions.assertEquals("", joiner.getRecord(JOINER_SLOT).getName(), "a joiner names only a human or an observer slot");

        for (LanLobby lobby : new LanLobby[]{host, joiner}) {
            Assertions.assertEquals(SECOND_OPEN_SLOT, lobby.getPlayerSlot(JOINER_PLAYER));
            Assertions.assertEquals(HOST_SLOT, lobby.getPlayerSlot(HOST_PLAYER));
            Assertions.assertEquals(JOINER_PLAYER, lobby.createResolutionTable().playerId[JOINER_SLOT],
                                    "the game start's table keeps the released slot's player id");
        }
    }

    /**
     * Checks that in a saved game that keeps its slots in place, a joined-game command that comes again from the player of lobby slot {@code 1}
     * places the player in slot {@code 1} again with its saved slot: the host releases the player's records before it checks that the chosen slot is
     * open.
     */
    @Test
    void saved_game_player_whose_join_comes_again_keeps_its_lobby_slot() {
        InMemoryLanScenario network = new InMemoryLanScenario(TWO_CLIENTS);
        LanLobby.Setup setup = createSavedTeamSetup();

        network.host(setup, HexFormat.of().parseHex(TEAM_MELEE_OPTIONS), null);
        network.openLobby(JOINER_CLIENT, new SaveGameInformation(SAVE_GAME_IDENTIFIER, RANDOM_STATE, JOINER_SLOT, JOINER_SLOT));
        network.waitForSlot(JOINER_CLIENT);
        network.run(SETTLE_MILLISECONDS);

        LanLobby host = requireLobby(network, HOST_CLIENT);
        LanLobby joiner = requireLobby(network, JOINER_CLIENT);

        Assertions.assertEquals(JOINER_PLAYER, host.getRecord(JOINER_SLOT).getPlayerId(), "the first join takes lobby slot 1");

        network.sessions[HOST_CLIENT].sendMessage(JOINER_PLAYER, LanLobby.createGameSettings(setup));
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(NO_REFUSAL, joiner.getRefusal(), "the join that comes again is not refused");

        for (LanLobby lobby : new LanLobby[]{host, joiner}) {
            Assertions.assertEquals(JOINER_PLAYER, lobby.getRecord(JOINER_SLOT).getPlayerId(), "lobby slot 1 again");
            Assertions.assertEquals(HUMAN_TYPE, lobby.getRecord(JOINER_SLOT).getType());
            Assertions.assertEquals(TERRAN_RACE, lobby.getRecord(JOINER_SLOT).getRace());
        }

        Assertions.assertEquals(JOINER_SLOT, host.getSavedSlot(JOINER_SLOT), "the slot takes the player's record slot again");
    }

    /**
     * Checks that only the host's slot change {@code 0x3e} runs, and only when its slot names a player or an observer slot and its player identifier
     * a player, an observer or no one ({@code 0xff}): a host's command with the slot or the player identifier {@code 8} and a joiner's own command
     * change no record; the host's valid command does.
     */
    @Test
    void slot_change_runs_only_from_the_host_with_player_or_observer_identifiers() {
        InMemoryLanScenario network = new InMemoryLanScenario(TWO_CLIENTS);

        network.host(createMeleeSetup(), LanGameOptions.MELEE.encode(), null);
        network.join(JOINER_CLIENT, null, null);
        network.run(SETTLE_MILLISECONDS);

        LanLobby joiner = requireLobby(network, JOINER_CLIENT);
        int free = joiner.getPlayerSlot(JOINER_PLAYER) == JOINER_SLOT ? SECOND_OPEN_SLOT : JOINER_SLOT;

        network.sessions[HOST_CLIENT].sendTurn(createSlotCommand(UNNAMED_IDENTIFIER, ProviderPeer.UNKNOWN_IDENTIFIER, PlayerTypes.CLOSED, 0, 0));
        network.sessions[HOST_CLIENT].sendTurn(createSlotCommand(free, UNNAMED_IDENTIFIER, PlayerTypes.CLOSED, 0, 0));
        network.sessions[JOINER_CLIENT].sendTurn(createSlotCommand(free, ProviderPeer.UNKNOWN_IDENTIFIER, PlayerTypes.CLOSED, 0, 0));
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(OPEN_OBSERVER_TYPE, joiner.getRecord(FIRST_OBSERVER_RECORD).getType(), "slot 8 is no observer slot");
        Assertions.assertEquals(OPEN_TYPE, joiner.getRecord(free).getType(), "player id 8 and the joiner's command");
        Assertions.assertEquals(NO_PLAYER, joiner.getRecord(free).getPlayerId());

        network.sessions[HOST_CLIENT].sendTurn(createSlotCommand(free, ProviderPeer.UNKNOWN_IDENTIFIER, PlayerTypes.CLOSED, 0, 0));
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(CLOSED_TYPE, joiner.getRecord(free).getType(), "the host's valid command");
    }

    /**
     * Checks that a received download percent ({@code 0x3d}) above {@code 100} reads {@code 0}; up to {@code 100} it reads as sent.
     */
    @Test
    void download_percent_above_one_hundred_reads_zero() {
        InMemoryLanScenario network = new InMemoryLanScenario(TWO_CLIENTS);

        network.host(createMeleeSetup(), LanGameOptions.MELEE.encode(), null);
        network.join(JOINER_CLIENT, null, null);
        network.run(SETTLE_MILLISECONDS);

        LanLobby host = requireLobby(network, HOST_CLIENT);

        network.sessions[JOINER_CLIENT].sendTurn(new byte[]{ReplayCommandIds.DOWNLOAD_PERCENTAGE, (byte) COMPLETE_PERCENT});
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(COMPLETE_PERCENT, host.getDownloadPercent(JOINER_PLAYER));

        network.sessions[JOINER_CLIENT].sendTurn(new byte[]{ReplayCommandIds.DOWNLOAD_PERCENTAGE, (byte) EXCESSIVE_PERCENT});
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(0, host.getDownloadPercent(JOINER_PLAYER));
    }

    /**
     * Checks that the host's swap ({@code 0x45}) and slot state ({@code 0x44}) run only for slot bytes that are player or observer slot identifiers:
     * slot byte {@code 8} names no slot, though its record index would be the first observer slot's.
     */
    @Test
    void swap_and_slot_state_run_only_for_player_or_observer_slot_identifiers() {
        InMemoryLanScenario network = new InMemoryLanScenario(TWO_CLIENTS);

        network.host(createMeleeSetup(), LanGameOptions.MELEE.encode(), null);
        network.join(JOINER_CLIENT, null, null);
        network.run(SETTLE_MILLISECONDS);

        LanLobby host = requireLobby(network, HOST_CLIENT);
        int joinerSlot = host.getPlayerSlot(JOINER_PLAYER);

        network.sessions[HOST_CLIENT].sendTurn(new byte[]{SWAP_PLAYERS_COMMAND, (byte) UNNAMED_IDENTIFIER, (byte) joinerSlot});
        network.sessions[HOST_CLIENT].sendTurn(new byte[]{SLOT_STATE_COMMAND, (byte) UNNAMED_IDENTIFIER, (byte) LanLobby.SLOT_CLOSED});
        network.run(SETTLE_MILLISECONDS);

        for (LanLobby lobby : new LanLobby[]{host, requireLobby(network, JOINER_CLIENT)}) {
            Assertions.assertEquals(joinerSlot, lobby.getPlayerSlot(JOINER_PLAYER), "slot 8 is no observer slot to swap with");
            Assertions.assertEquals(OPEN_OBSERVER_TYPE, lobby.getRecord(FIRST_OBSERVER_RECORD).getType(), "slot 8 is not closed");
        }
    }

    /**
     * Checks that a move request to the observers ({@code 0x38} with a nonzero observer byte) puts its slot byte into the host's swap {@code 0x45} as
     * it came: a byte that names no slot makes a swap that no client executes.
     */
    @Test
    void move_request_to_the_observers_swaps_the_request_slot_byte_as_it_came() {
        InMemoryLanScenario network = new InMemoryLanScenario(TWO_CLIENTS);

        network.host(createMeleeSetup(), LanGameOptions.MELEE.encode(), null);
        network.join(JOINER_CLIENT, null, null);
        network.run(SETTLE_MILLISECONDS);

        LanLobby host = requireLobby(network, HOST_CLIENT);
        int joinerSlot = host.getPlayerSlot(JOINER_PLAYER);

        network.sessions[JOINER_CLIENT].sendTurn(new byte[]{MOVE_REQUEST_COMMAND, 0, 1, (byte) FOREIGN_SLOT_BYTE});
        network.run(SETTLE_MILLISECONDS);

        byte[] swap = {SWAP_PLAYERS_COMMAND, (byte) FOREIGN_SLOT_BYTE, (byte) FIRST_OBSERVER_SLOT};

        Assertions.assertTrue(hasSentTurnCommand(network, HOST_CLIENT, swap));
        Assertions.assertEquals(joinerSlot, host.getPlayerSlot(JOINER_PLAYER));
        Assertions.assertEquals(OPEN_OBSERVER_TYPE, host.getRecord(FIRST_OBSERVER_RECORD).getType());
    }

    /**
     * Checks that the new net player command {@code 0x3f} names the player identifier, not the slot: in a team game whose slot {@code 2} the host
     * closed, the joiner (player {@code 1}) lands in slot {@code 3} and the host's turn names player {@code 1}.
     */
    @Test
    void new_net_player_command_names_the_player_identifier_of_a_joiner_in_another_slot() {
        InMemoryLanScenario network = new InMemoryLanScenario(TWO_CLIENTS);

        network.host(createTeamSetup(), HexFormat.of().parseHex(TEAM_MELEE_OPTIONS), null);

        LanLobby host = requireLobby(network, HOST_CLIENT);

        Assertions.assertTrue(host.setSlotState(SECOND_OPEN_SLOT, LanLobby.SLOT_CLOSED), "the host closes slot 2");

        network.run(SETTLE_MILLISECONDS);
        network.join(JOINER_CLIENT, null, null);
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(SECOND_TEAM_SLOT, host.getPlayerSlot(JOINER_PLAYER), "the joiner lands in slot 3");
        Assertions.assertTrue(hasSentTurnCommand(network, HOST_CLIENT, HexFormat.of().parseHex(JOINER_NEW_NET_PLAYER)),
                              "the host's 0x3f names player 1");
    }

    /**
     * Checks that a player that left is still named by the slot it released: the next join queues the player's new net player command {@code 0x3f}
     * with zero versions and sends no slot table {@code 0x5e} of it. In a team game the second joiner's repeated joined-game command moves it from
     * slot {@code 3} to slot {@code 2}; it and the first joiner leave, then a third player joins with the free identifier {@code 1}.
     */
    @Test
    void departed_player_of_a_released_slot_gets_zero_versions_and_no_slot_table() {
        InMemoryLanScenario network = new InMemoryLanScenario(FOUR_CLIENTS);
        LanLobby.Setup setup = createTeamSetup();

        network.host(setup, HexFormat.of().parseHex(TEAM_MELEE_OPTIONS), null);
        network.join(JOINER_CLIENT, null, null);
        network.run(SETTLE_MILLISECONDS);
        network.join(SECOND_JOINER_CLIENT, null, null);
        network.run(SETTLE_MILLISECONDS);
        network.sessions[HOST_CLIENT].sendMessage(SECOND_JOINER_PLAYER, LanLobby.createGameSettings(setup));
        network.run(SETTLE_MILLISECONDS);

        LanLobby host = requireLobby(network, HOST_CLIENT);

        Assertions.assertEquals(SECOND_JOINER_PLAYER, host.getRecord(SECOND_TEAM_SLOT).getPlayerId(), "the released slot names player 2");
        Assertions.assertEquals(OPEN_TYPE, host.getRecord(SECOND_TEAM_SLOT).getType());

        network.leave(SECOND_JOINER_CLIENT);
        network.leave(JOINER_CLIENT);

        int firstPacket = network.sentPackets.get(HOST_CLIENT).size();

        network.join(THIRD_JOINER_CLIENT, null, null);
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(JOINER_PLAYER, network.sessions[THIRD_JOINER_CLIENT].getLocalIdentifier(), "the third joiner takes id 1");
        Assertions.assertEquals(SECOND_JOINER_PLAYER, host.getRecord(SECOND_TEAM_SLOT).getPlayerId(), "the released slot still names player 2");
        Assertions.assertTrue(hasSentTurnCommand(network, HOST_CLIENT, HexFormat.of().parseHex(DEPARTED_NEW_NET_PLAYER)),
                              "the host queues player 2's 0x3f with zero versions");
        Assertions.assertFalse(hasSentSlotTable(network, HOST_CLIENT, firstPacket, SECOND_JOINER_PLAYER), "no 0x5e of the departed player");
        Assertions.assertTrue(hasSentSlotTable(network, HOST_CLIENT, firstPacket, JOINER_PLAYER), "the third joiner's 0x5e");
    }

    @RequiresInstallation
    @Test
    void every_client_runs_the_game_and_writes_the_replay_with_the_speed_override_of_the_game_name(@TempDir @NotNull Path maps) throws Exception {
        Path mapPath = Files.copy(ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, LanTestResources.CAPTURED_MAP_FILE_NAME),
                                  maps.resolve(LanTestResources.CAPTURED_MAP_FILE_NAME));
        byte[] mapFile = Files.readAllBytes(mapPath);
        byte[] mapData = MpqArchive.open(mapPath).readFile(SCENARIO_ENTRY);
        ChkFile scenario = ChkFile.parseTolerant(mapData);
        InMemoryLanScenario network = new InMemoryLanScenario(TWO_CLIENTS);
        LanLobby.Setup setup = LanLobby.Setup.create(scenario, LanGameOptions.MELEE, "host", LanTestResources.CAPTURED_MAP_FILE_NAME,
                                                     mapFile.length, LanMapTransfer.calculateChecksum(mapFile, mapFile.length));

        network.latency  = LATENCY;
        network.gameName = OVERRIDE_GAME_NAME;
        network.host(setup, LanGameOptions.MELEE.encode(), mapPath);
        network.join(JOINER_CLIENT, maps, maps.resolve("Download"));
        network.runUntil(MAP_TRANSFER_LIMIT_MILLISECONDS, lan -> {
            LanLobby host = lan.lobbies[HOST_CLIENT];

            Assertions.assertNotNull(host);

            return host.isMapTransferComplete();
        });
        network.run(LOBBY_SETTLE_MILLISECONDS);

        LanLobby host = network.lobbies[HOST_CLIENT];

        Assertions.assertNotNull(host);
        Assertions.assertTrue(host.startGame());

        network.runUntil(START_LIMIT_MILLISECONDS, lan -> {
            for (LanLobby lobby : lan.lobbies) {
                Assertions.assertNotNull(lobby);

                if (lobby.getState() != STATE_GAME) {
                    return false;
                }
            }

            return true;
        });

        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            for (int client = 0; client < TWO_CLIENTS; client++) {
                LanLobby lobby = network.lobbies[client];
                ProviderSession session = network.sessions[client];

                Assertions.assertNotNull(lobby);
                Assertions.assertEquals(OVERRIDE_GAME_NAME, session.getGameName(), "client " + client);

                MeleeGameSetup gameSetup = lobby.createGameSetup(mapData, GameSpeed.FASTEST, true);
                LiveGame live = new LiveGame(gameSetup, lobby.getSeed(), session.getLocalIdentifier());
                LanTurnTransport transport = new LanTurnTransport(session, network::deliver);

                live.attachNetwork(transport, TURNS_IN_TRANSIT, false);

                ReplayRuntime runtime = ReplayRuntime.createLiveGame(live.getReplay(), scenario, data, live.getLocalPlayer(), true,
                                                                     VIEWPORT_WIDTH, VIEWPORT_HEIGHT, INSTRUCTION_LIMIT);
                GameSpeed timing = runtime.getFrameTiming();
                GameSpeed expected = new GameSpeed(GameSpeed.FASTEST, 1, 0, OVERRIDE_INDEX);

                live.startSession(runtime.getGame(), timing);
                expected.startSession(1, transport.getTurnRate().getTurnsPerSecond(), transport.getTurnRate().getNetworkTurnsPerSecond());

                Assertions.assertEquals(OVERRIDE_INDEX, gameSetup.getSpeedOverrideIndex(), "client " + client);
                Assertions.assertEquals(OVERRIDE_FRAME_DURATION, runtime.getFrameDuration(), "client " + client);
                Assertions.assertArrayEquals(expected.getTimeouts(), timing.getTimeouts(), "client " + client);
                Assertions.assertEquals(OVERRIDE_INDEX, ReplayFile.parse(live.writeReplay(0, 0)).getSpeedOverrideIndex(), "client " + client);
            }
        }
    }
}
