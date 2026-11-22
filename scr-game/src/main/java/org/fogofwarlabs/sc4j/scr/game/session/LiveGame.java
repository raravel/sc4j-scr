package org.fogofwarlabs.sc4j.scr.game.session;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.io.AtomicFileUtils;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.base.text.TerminatedStringUtils;
import org.fogofwarlabs.sc4j.engine.controller.ControllerSource;
import org.fogofwarlabs.sc4j.engine.lockstep.*;
import org.fogofwarlabs.sc4j.scr.core.FileNameUtils;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.format.replay.*;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrModCommandSubmitter;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrPauseRequester;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugin;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerColorRows;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplaySession;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.session.setup.PlayerSlots;
import org.fogofwarlabs.sc4j.scr.game.session.setup.SavedGamePlayers;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameContext;
import org.fogofwarlabs.sc4j.scr.game.state.GameRulesVersion;
import org.fogofwarlabs.sc4j.scr.game.state.RuntimeRandom;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.IntSupplier;

/**
 * Owns a live game's outgoing turns, executed-command history and recorded replay.
 * <p>
 * Live play, the saved replay and playback use the common replay runtime and one command path. A single-player
 * command queued at the current frame executes and is recorded in the following frame.
 */
public final class LiveGame implements ScrModCommandSubmitter, ScrPauseRequester {
    /**
     * The frame count stored in the running header; the saved header receives the real count.
     */
    public static final long RUNNING_FRAMES = 0x7FFFFFFFL;

    /**
     * The network id of the local human in the header and in command blocks.
     */
    public static final int HUMAN_PLAYER_ID = 0;

    /**
     * The initial pause count for every player.
     */
    public static final int INITIAL_PAUSES = GameContext.INITIAL_PAUSES;

    /**
     * The fixed text-byte region after the sender byte in a recorded chat command.
     */
    public static final int CHAT_TEXT_BYTES = ReplayFile.CHAT_MESSAGE_SIZE;

    /**
     * The single-player loopback turns kept in transit.
     */
    public static final int LATENCY_TURNS = 1;

    /**
     * The highest latency setting (extra high).
     */
    public static final int MAXIMUM_LATENCY = 2;

    /**
     * Game flags bit {@code 5}: the dynamic turn rate, which adjusts the turn rate and latency from the turn stalls.
     */
    public static final int DYNAMIC_TURN_RATE_FLAG = 0x20;

    /**
     * The maximum bytes in a single-player outgoing turn.
     */
    public static final int MAXIMUM_TURN_BYTES = 0x1F0;

    /**
     * {@link #submitText} result: the text was discarded; it was no cheat, or cheats are not allowed.
     */
    public static final int TEXT_DISCARDED = 0;

    /**
     * {@link #submitText} result: the text was a cheat and the Cheat command was queued.
     */
    public static final int TEXT_CHEAT = 1;

    /**
     * {@link #submitText} result: the text was a cheat that leaves the flags unchanged ({@code radio free zerg}).
     */
    public static final int TEXT_CHEAT_UNCHANGED = 2;

    /**
     * The departure reason when leaving a running network game.
     */
    public static final int LEAVE_REASON = 3;

    /**
     * The Sync mismatches logged one by one before the log thins out to one in {@link #MISMATCH_LOG_INTERVAL}.
     */
    private static final int MISMATCH_LOG_FIRST = 16;
    private static final int MISMATCH_LOG_INTERVAL = 24;

    /**
     * The Sync mismatches whose local record is described with its hash inputs ({@link SyncRing#describe}).
     */
    private static final int MISMATCH_DETAIL_FIRST = 2;

    /**
     * The Latency command's silent bit (the Network Health start's {@code 55 80}).
     */
    private static final int SILENT_LATENCY = 0x80;

    /**
     * The Dynamic Turn Rate command's bytes after the type byte: the sub-command, the turns per second and the latency.
     */
    private static final int DYNAMIC_TURN_RATE_PAYLOAD = 3;

    /**
     * The minimum provider turns in transit.
     */
    private static final int NETWORK_MINIMUM_LATENCY_TURNS = 2;

    /**
     * The maximum provider turns in transit.
     */
    private static final int NETWORK_MAXIMUM_LATENCY_TURNS = 8;

    /**
     * The most players the waiting-for-players dialog lists.
     */
    private static final int WAITING_LIST_LIMIT = 8;

    /**
     * The desync dump file name: date and time, then the map and the local player.
     */
    private static final @NotNull DateTimeFormatter DESYNC_FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final @NotNull String DESYNC_FILE_EXTENSION = ".txt";
    private static final @NotNull String DESYNC_THREAD_NAME = "desync-dump";
    private static final char FILE_NAME_REPLACEMENT = '_';
    private static final char EXTENSION_SEPARATOR = '.';

    private static final int HEADER_ENGINE_STARCRAFT = 0;
    private static final int HEADER_ENGINE_BROOD_WAR = 1;
    private static final int HEADER_GAME_STATE = 1;
    private static final int HEADER_SAVED_FLAG = 1;
    private static final int HEADER_COMPUTER_COUNT = 0;

    private static final @NotNull Logger LOGGER = Logger.of(LiveGame.class);

    /**
     * Receives the Save Game and Load Game commands that a turn executes ({@link #setSavedGameHandler}).
     */
    public interface SavedGameHandler {
        /**
         * Writes the running game to a saved game on every machine executing the Save Game command.
         *
         * @param time the command's time DWORD, {@code time(0)} of the issuing machine
         * @param name the command's name bytes without terminator, as received: the file name without extension
         */
        void saveGame(int time, byte @NotNull [] name);

        /**
         * Ends the running single-player game and starts the saved game.
         *
         * @param time the command's time DWORD
         * @param name the command's name bytes without terminator, as received: the file name without extension
         */
        void loadGame(int time, byte @NotNull [] name);
    }

    /**
     * The view of this game that its turn rate rules adjust.
     */
    private final class TurnRateSession implements DynamicTurnRate.Session {
        /**
         * @return the game's turns per second
         */
        @Override
        public int getTurnRate() {
            return LiveGame.this.turnRate;
        }

        /**
         * @return the game's latency setting, {@code 0} Low through {@code 2} Extra High
         */
        @Override
        public int getLatency() {
            return LiveGame.this.latency;
        }

        /**
         * @return {@code true} if the local player is the present player of the lowest network id below {@code 8}, {@code false}
         * otherwise
         */
        @Override
        public boolean isLowestPresentPlayer() {
            return LiveGame.this.lowestPresentPlayer;
        }

        /**
         * Queues a command into the local outgoing turn.
         *
         * @param command the command bytes, type byte first
         */
        @Override
        public void queueCommand(byte @NotNull [] command) {
            LiveGame.this.outgoingTurn.queue(command);
        }
    }

    /**
     * Reads the latency setting a Latency command sets: its byte without the silent bit, at most {@link #MAXIMUM_LATENCY}.
     *
     * @param value the command's unsigned latency byte
     * @return the latency 0 low, 1 high or 2 extra high
     */
    public static int readLatency(int value) {
        return Math.min(value & ~SILENT_LATENCY, MAXIMUM_LATENCY);
    }

    /**
     * Converts a name to a file name part: characters a file name must not hold and control characters become {@code _}.
     *
     * @param name the name
     * @return the file name part
     */
    private static @NotNull String toFileNamePart(@NotNull String name) {
        StringBuilder part = new StringBuilder(name.length());

        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);

            boolean replaced = Character.isISOControl(character) || FileNameUtils.BAD_CHARACTERS.indexOf(character) >= 0;

            part.append(replaced ? FILE_NAME_REPLACEMENT : character);
        }

        return part.toString();
    }

    private static @NotNull String stripExtension(@NotNull String fileName) {
        int separator = fileName.lastIndexOf(EXTENSION_SEPARATOR);

        return separator > 0 ? fileName.substring(0, separator) : fileName;
    }

    private static byte @NotNull [] encodeSavedGameCommand(int typeId, int time, byte @NotNull [] name) {
        byte[] text = TerminatedStringUtils.cutText(name, ReplayFile.SAVED_GAME_NAME_SIZE);
        // The name ends with its terminator, the last byte of the command.
        byte[] command = new byte[1 + Integer.BYTES + text.length + 1];

        command[0] = (byte) typeId;
        LittleEndianUtils.writeInt32(command, 1, time);

        System.arraycopy(text, 0, command, 1 + Integer.BYTES, text.length);

        return command;
    }

    private final @NotNull MeleeGameSetup setup;
    private final int seed;
    private final int @NotNull [] colors;
    private final MeleeGameSetup.@NotNull PlayerRecord @NotNull [] records;
    /**
     * The slot of the record each player holds, rebuilt from the eight player records of the game start.
     */
    private final @NotNull PlayerSlots playerSlots;

    /**
     * The slot each lobby slot's commands act on.
     */
    private final int @NotNull [] commandSlots;
    private final int localPlayer;
    private final int localSlot;
    private final int localPlayerId;

    /**
     * Guards the recorded commands and chat lines: the game thread appends them, and a replay write may copy them from another
     * thread ({@link #writeReplay}).
     */
    private final @NotNull Object replayLock;
    private final @NotNull List<ReplayCommand> history;
    private final @NotNull List<ReplayCommand> chatRecords;
    private final @NotNull OutgoingTurnBuffer outgoingTurn;
    /**
     * The single-player game's sent turns on their way back to the local player.
     */
    private final @NotNull LoopbackTurns loopbackTurns;
    private final @NotNull ReplayFile replay;

    private int localOutcome;

    private @Nullable Game game;
    /**
     * The game's speed configuration, whose turn-wait budget tells whether a frame exchanges a turn.
     */
    private @Nullable GameSpeed timing;
    private byte @NotNull [] pausesLeft;
    private final @NotNull List<PauseNotice> pauseNotices;
    private final @NotNull List<int[]> latencyNotices;
    private @Nullable SavedGameHandler savedGameHandler;

    private @Nullable LockstepTransport transport;
    /**
     * The turn exchange of a network game over its transport ({@link #attachNetwork}), or {@code null} outside one.
     */
    private @Nullable TurnExchange turnExchange;
    private int turnsInTransit;
    private int baseTurnsInTransit;
    private int latency;
    /**
     * The network game's turns per second, which the Turn Rate and Dynamic Turn Rate commands change.
     */
    private int turnRate;
    /**
     * The game flags of the network game ({@link #DYNAMIC_TURN_RATE_FLAG}).
     */
    private int gameFlags;
    /**
     * The turn rate rules of the network game, from its session start; {@code null} before and outside a network game.
     */
    private @Nullable DynamicTurnRate dynamicTurnRate;
    /**
     * Whether the local player was the present player of the lowest network id below {@code 8} at the last completed turn step.
     */
    private boolean lowestPresentPlayer;
    private boolean networkStarted;
    /**
     * The number of commands the startup turn recorded, the first commands of the history.
     */
    private int startupTurnCommandCount;
    private @Nullable SyncRing syncRing;
    private @Nullable Game syncGame;
    private @Nullable IntSupplier cameraRow;
    /**
     * Checks the received Sync commands against the local records and counts the mismatches.
     */
    private final @NotNull DesyncMonitor syncMonitor;
    /**
     * The folder of the desync dump, written at the first Sync mismatch ({@link #setDesyncDumpTarget}), or {@code null} for none.
     */
    private @Nullable Path desyncFolder;
    private @NotNull String desyncMapName;
    private @NotNull String desyncBuild;
    private boolean localCheatsAllowed;

    /**
     * Creates a {@link LiveGame} instance, resolving the lobby with the supplied seed.
     *
     * @param setup the lobby
     * @param seed  the game seed
     * @throws IOException if the header cannot be decoded
     */
    public LiveGame(@NotNull MeleeGameSetup setup, int seed) throws IOException {
        this(setup, seed, HUMAN_PLAYER_ID);
    }

    /**
     * Creates a {@link LiveGame} instance for the supplied network player in the resolved lobby.
     *
     * @param setup         the lobby
     * @param seed          the game seed
     * @param localPlayerId the local player's network id in the resolved records, or an observer's network id in the lobby
     *                      table's observer records ({@link MeleeGameSetup#getObserverSlot})
     * @throws IOException if the header cannot be decoded
     */
    public LiveGame(@NotNull MeleeGameSetup setup, int seed, int localPlayerId) throws IOException {
        this(setup, seed, localPlayerId, null);
    }

    /**
     * Creates a {@link LiveGame} instance, optionally restoring human network ids and names from saved slots.
     *
     * @param setup         the lobby
     * @param seed          the game seed
     * @param localPlayerId the local player's network id in the resolved (and restored) records, or an observer's network id in
     *                      the lobby table's observer records ({@link MeleeGameSetup#getObserverSlot})
     * @param players       the new game's players of the saved slots, or {@code null} to keep the resolved ids
     * @throws IOException           if the header cannot be decoded
     * @throws IllegalStateException if neither a resolved record nor an observer record holds {@code localPlayerId}
     */
    public LiveGame(@NotNull MeleeGameSetup setup, int seed, int localPlayerId, @Nullable SavedGamePlayers players) throws IOException {
        this.setup   = setup;
        this.seed    = seed;
        this.colors  = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.records = setup.resolve(seed, this.colors);

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            if (players == null || this.records[slot].getType() != MeleeGameSetup.TYPE_HUMAN) {
                continue;
            }

            MeleeGameSetup.PlayerRecord resolved = this.records[slot];

            this.records[slot] = players.getPlayerId(slot) == SavedGamePlayers.ABSENT
                                 ? new MeleeGameSetup.PlayerRecord(-1, 0, 0, 0, "")
                                 : new MeleeGameSetup.PlayerRecord(players.getPlayerId(slot), resolved.getType(), resolved.getRace(),
                                                                   resolved.getTeam(), players.getName(slot));
        }

        this.playerSlots = new PlayerSlots();

        this.playerSlots.update(this.records, PlayerTable.PLAYABLE_PLAYER_SLOTS);

        int local = this.findSlot(localPlayerId);
        int observer = local < 0 ? setup.getObserverSlot(localPlayerId) : PlayerSlots.NO_SLOT;

        if (local < 0 && observer < 0) {
            throw Exceptions.newIllegalStateException("The resolved lobby lost the human player.");
        }

        this.commandSlots  = setup.getResolvedCommandSlots();
        this.localPlayer   = local < 0 ? observer : this.commandSlots[local];
        this.localSlot     = local < 0 ? observer : local;
        this.localPlayerId = localPlayerId;

        this.replayLock    = new Object();
        this.history       = new ArrayList<>();
        this.chatRecords   = new ArrayList<>();
        this.outgoingTurn  = new OutgoingTurnBuffer(MAXIMUM_TURN_BYTES);
        this.loopbackTurns = new LoopbackTurns(this.outgoingTurn);

        if (this.isLocalObserver()) {
            this.outgoingTurn.useObserverTurns();
        }
        this.replay = ReplayFile.create(this.buildHeader(RUNNING_FRAMES, true), this.history, setup.getMapData(),
                                        this.getLimits(), ReplayWriter.encodeVersionBlock(GameRulesVersion.CURRENT_BUILD, 0),
                                        ReplayWriter.CURRENT_FIX_FLAGS);

        if (setup.getSavedHeader() != null) {
            this.replay.markSavedGameStart();
        }

        // The name table keeps the records' name bytes (computer nation names, non-ASCII players).
        this.replay.setPlayerNames(this.readNameBytes());
        this.replay.setSpeedOverrideIndex(setup.getSpeedOverrideIndex());
        this.replay.setCustomColors(PlayerColorRows.encodeCustomColors(setup.getResolvedCustomColors()));

        MpqArchive mapArchive = setup.getMapArchive();

        if (mapArchive != null) {
            this.replay.setMapArchive(mapArchive);
        }

        this.localOutcome = 0;

        this.game             = null;
        this.timing           = null;
        this.pausesLeft       = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.pauseNotices     = new ArrayList<>();
        this.latencyNotices   = new ArrayList<>();
        this.savedGameHandler = null;

        this.transport               = null;
        this.turnExchange            = null;
        this.turnsInTransit          = 0;
        this.baseTurnsInTransit      = 0;
        this.latency                 = 0;
        this.turnRate                = 0;
        this.gameFlags               = 0;
        this.dynamicTurnRate         = null;
        this.lowestPresentPlayer     = false;
        this.networkStarted          = false;
        this.startupTurnCommandCount = 0;
        this.syncRing                = null;
        this.syncGame                = null;
        this.cameraRow               = null;
        this.syncMonitor             = new DesyncMonitor(this::checkSyncTurn, this::logSyncMismatch);
        this.desyncFolder            = null;
        this.desyncMapName           = "";
        this.desyncBuild             = "";
        this.localCheatsAllowed      = false;

        Arrays.fill(this.pausesLeft, (byte) INITIAL_PAUSES);
    }

    /**
     * @return a team game's lobby slot races for its bases, or {@code null} outside team games
     */
    public int @Nullable [] getTeamSlotRaces() {
        return this.setup.getTemplate().hasTeams() ? this.setup.getResolvedSlotRaces() : null;
    }

    /**
     * @return the in-memory replay whose command list receives the executed commands
     */
    public @NotNull ReplayFile getReplay() {
        return this.replay;
    }

    /**
     * @return the game seed
     */
    public int getSeed() {
        return this.seed;
    }

    /**
     * @return the local player's network id in the resolved records
     */
    public int getLocalPlayerId() {
        return this.localPlayerId;
    }

    /**
     * @return the player index of the local human (its start slot), or the observer slot of an observer
     */
    public int getLocalPlayer() {
        return this.localPlayer;
    }

    /**
     * @return the local human's record and selection slot, or the observer slot of an observer; a human's command slot may
     * instead be its force leader
     */
    public int getLocalSlot() {
        return this.localSlot;
    }

    /**
     * @return {@code true} if the local player is an observer, whose local slot is an observer slot ({@link PlayerTable#isObserverSlot}),
     * {@code false} otherwise
     */
    public boolean isLocalObserver() {
        return PlayerTable.isObserverSlot(this.localSlot);
    }

    /**
     * @return the lobby
     */
    public @NotNull MeleeGameSetup getSetup() {
        return this.setup;
    }

    /**
     * @return the executed and recorded commands, in execution order
     */
    public @NotNull List<ReplayCommand> getHistory() {
        return this.history;
    }

    /**
     * @return the outcome screen shown to the local player: {@code 0} running, {@code 2} defeat, {@code 3} victory or draw
     */
    public int getLocalOutcome() {
        return this.localOutcome;
    }

    /**
     * @param slot the slot 0..7
     * @return the network id of the human in the slot, or {@code -1}
     */
    public int getPlayerId(int slot) {
        MeleeGameSetup.PlayerRecord record = this.records[slot];

        return record.getType() == MeleeGameSetup.TYPE_HUMAN ? record.getPlayerId() : -1;
    }

    /**
     * @return the outgoing turn buffer also served by EUD emulation
     */
    public @NotNull OutgoingTurnBuffer getOutgoingTurn() {
        return this.outgoingTurn;
    }

    /**
     * @return {@code true} if the local cheat toggle permits typed cheats in this network game, {@code false} otherwise
     */
    public boolean isLocalCheatsAllowed() {
        return this.localCheatsAllowed;
    }

    /**
     * Sets the client's {@code /cheat} toggle; it only takes effect in a network game without another human.
     *
     * @param allowed whether typed cheat codes are cheats in this network game
     */
    public void setLocalCheatsAllowed(boolean allowed) {
        this.localCheatsAllowed = allowed;
    }

    /**
     * @return {@code true} if a human other than the local player takes part (a record of slots 0..7 of type 2 with another
     * id), {@code false} otherwise
     */
    public boolean hasOtherHuman() {
        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            MeleeGameSetup.PlayerRecord record = this.records[slot];

            if (record.getType() == MeleeGameSetup.TYPE_HUMAN && record.getPlayerId() != this.localPlayerId) {
                return true;
            }
        }

        return false;
    }

    /**
     * @return the network game's turns per second: the room's, then as the Turn Rate and Dynamic Turn Rate commands set it; {@code 0}
     * outside a network game
     */
    public int getTurnRate() {
        return this.transport == null ? 0 : this.turnRate;
    }

    /**
     * @return the game flags of the network game
     */
    public int getGameFlags() {
        return this.gameFlags;
    }

    /**
     * Sets the game flags of the network game (the host's, which its advertisement carries) before its session starts; with
     * {@link #DYNAMIC_TURN_RATE_FLAG} the session adjusts the turn rate and latency.
     *
     * @param gameFlags the flags
     */
    public void setGameFlags(int gameFlags) {
        this.gameFlags = gameFlags;
    }

    /**
     * @return {@code true} if the dynamic turn rate's scan runs, {@code false} otherwise
     */
    public boolean isTurnRateScanning() {
        DynamicTurnRate rules = this.dynamicTurnRate;

        return rules != null && rules.isScanning();
    }

    /**
     * @return the extra local turns kept in transit beyond the provider's base count
     */
    public int getLatency() {
        return this.latency;
    }

    /**
     * @return {@code true} if a Pause command or single-player trigger paused the game, {@code false} otherwise
     */
    public boolean isPaused() {
        Game game = this.game;
        TriggerContext triggers = game == null ? null : game.getTriggerContext();

        return triggers != null && triggers.getPhaseGate() != 0;
    }

    /**
     * Tests whether a player may pause: always in single player, or a human network player with pauses left.
     *
     * @param slot the player slot
     * @return {@code true} if the player may pause, {@code false} otherwise
     */
    public boolean canPause(int slot) {
        if (!this.isNetworkGame()) {
            return true;
        }

        if (slot < 0 || slot >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return false;
        }

        return this.records[slot].getType() == MeleeGameSetup.TYPE_HUMAN && this.pausesLeft[slot] != 0;
    }

    /**
     * @param slot the player slot
     * @return the player's remaining pause count
     */
    public int getPausesLeft(int slot) {
        return Byte.toUnsignedInt(this.pausesLeft[slot]);
    }

    /**
     * Sets the receiver of the executed Save Game and Load Game commands.
     *
     * @param savedGameHandler the receiver, or {@code null} to execute neither
     */
    public void setSavedGameHandler(@Nullable SavedGameHandler savedGameHandler) {
        this.savedGameHandler = savedGameHandler;
    }

    /**
     * Tests whether the local network id is the lowest human id; always permits single player.
     *
     * @return {@code true} if the local player is the first player, {@code false} otherwise
     */
    public boolean isLocalPlayerFirst() {
        if (!this.isNetworkGame()) {
            return true;
        }

        for (MeleeGameSetup.PlayerRecord record : this.records) {
            if (record.getType() == MeleeGameSetup.TYPE_HUMAN && record.getPlayerId() < this.localPlayerId) {
                return false;
            }
        }

        return true;
    }

    /**
     * @return the waiting-for-players countdown in seconds while a network player's turns are overdue, {@code -1} otherwise
     * ({@link WaitingPeers#getCountdownSeconds()} of the transport's waiting state)
     */
    public int getWaitCountdown() {
        LockstepTransport network = this.transport;
        WaitingPeers waiting = network == null ? null : network.getWaitingPeers();

        return waiting == null ? WaitCountdown.CLOSED : waiting.getCountdownSeconds();
    }

    /**
     * Lists the players the waiting-for-players dialog shows. The dialog is shown only while the countdown runs
     * ({@link #getWaitCountdown()} is not negative); only then is the list built, and its shuffle reseeds and advances the
     * game's C runtime generator.
     *
     * @param runtimeRandom the game's C runtime generator
     * @return the names of the records a player holds ({@link PlayerSlots#isPlayerRecord}) with a player id ({@code 0} to {@code 7})
     * whose turns the transport awaits ({@link WaitingPeers#getAwaitedPeers()}, each peer a network id), at most
     * {@value #WAITING_LIST_LIMIT}, in the order the game seed shuffles them ({@link ShuffleUtils#shuffle}); empty while the dialog is
     * not shown
     */
    public @NotNull List<String> getWaitingPlayerNames(@NotNull RuntimeRandom runtimeRandom) {
        if (this.getWaitCountdown() < 0) {
            return List.of();
        }

        LockstepTransport network = this.transport;
        WaitingPeers waiting = network == null ? null : network.getWaitingPeers();
        List<LockstepPeer> awaited = waiting == null ? List.of() : waiting.getAwaitedPeers();
        List<String> names = new ArrayList<>();

        for (MeleeGameSetup.PlayerRecord record : this.records) {
            int playerId = record.getPlayerId();

            if (names.size() < WAITING_LIST_LIMIT && PlayerSlots.isPlayerRecord(record) && playerId >= 0 &&
                playerId < PlayerTable.PLAYABLE_PLAYER_SLOTS && awaited.contains(NetworkPeerUtils.getPeer(playerId))) {
                names.add(record.getName());
            }
        }

        return ShuffleUtils.shuffle(names, this.seed, runtimeRandom);
    }

    /**
     * @return {@code true} if the game runs over a network turn stream, {@code false} otherwise
     */
    public boolean isNetworkGame() {
        return this.transport != null;
    }

    /**
     * Sets the game and drawing-row supplier used to create the frame Sync records of a network game, whose records
     * alternate the unit and resource kinds and take drawing values.
     *
     * @param game      the game state this game runs in
     * @param cameraRow the camera's top tile row for the first drawn record row
     */
    public void setSyncSource(@NotNull Game game, @NotNull IntSupplier cameraRow) {
        this.syncGame  = game;
        this.cameraRow = cameraRow;
        this.syncRing  = new SyncRing(this.localPlayer, this.isNetworkGame());
    }

    /**
     * Sets where a network game writes its desync dump: at the first Sync mismatch, one plain-text file of the local simulation
     * state (the compared record, the state it hashed and the current state) goes to the folder, written on a background thread.
     *
     * @param folder  the folder of the dump files
     * @param mapName the map named in the file header and file name
     * @param build   the build identity named in the file header
     */
    public void setDesyncDumpTarget(@NotNull Path folder, @NotNull String mapName, @NotNull String build) {
        this.desyncFolder  = folder;
        this.desyncMapName = mapName;
        this.desyncBuild   = build;
    }

    /**
     * @return the received Sync commands that did not match the local record they name ({@link SyncRing#check})
     */
    public int getSyncMismatches() {
        return this.syncMonitor.getMismatchCount();
    }

    /**
     * @return the number of commands a network game's startup turn recorded, the first commands of the history that run
     * as the startup turn step ({@link ReplaySession#setStartupTurnCommandCount}); {@code 0} before the startup turn
     * and without one
     */
    public int getStartupTurnCommandCount() {
        return this.startupTurnCommandCount;
    }

    /**
     * @return the classic or extended resource-limit preset selected by the lobby
     */
    private int @NotNull [] getLimits() {
        return this.setup.isClassicLimits() ? ReplayWriter.CLASSIC_LIMITS : ReplayWriter.CURRENT_LIMITS;
    }


    /**
     * Finds the slot of the record a network id's player holds ({@link PlayerSlots#getSlot}).
     *
     * @param playerId the network id
     * @return the slot 0..7 the player holds, or {@link PlayerSlots#NO_SLOT} (an observer or no player)
     */
    public int findSlot(int playerId) {
        return this.playerSlots.getSlot(playerId);
    }

    /**
     * Finds the slot a network player's commands act on: its own slot or its team force leader.
     *
     * @param playerId the network id
     * @return the command slot, or {@code -1} for an observer or no player
     */
    private int findCommandSlot(int playerId) {
        if (!this.isNetworkGame()) {
            return this.localPlayer;
        }

        int slot = this.findSlot(playerId);

        return slot < 0 ? -1 : this.commandSlots[slot];
    }

    /**
     * Records a chat line for the saved replay, keeping it apart from the executed command history.
     *
     * @param senderSlot   the sender's slot
     * @param text         the text bytes up to the terminator
     * @param currentFrame the frame the replay records
     */
    public void recordChat(int senderSlot, byte @NotNull [] text, long currentFrame) {
        byte[] payload = new byte[1 + CHAT_TEXT_BYTES];

        payload[0] = (byte) senderSlot;
        TerminatedStringUtils.putText(text, 0, text.length, payload, 1, CHAT_TEXT_BYTES);

        synchronized (this.replayLock) {
            this.chatRecords.add(new ReplayCommand(currentFrame, this.localPlayerId, ReplayCommandIds.CHAT, payload));
        }
    }

    /**
     * Queues a local command into the outgoing turn.
     *
     * @param typeId  the command type byte
     * @param payload the command bytes after the type byte
     * @return {@code true} if queued, {@code false} otherwise (a command the replay excludes: pause, resume, keep-alive,
     * save/load, latency and the other excluded types, which this session handles outside the command stream)
     */
    public boolean submit(int typeId, byte @NotNull [] payload) {
        if (!ReplayWriter.isRecorded(typeId, payload.length)) {
            return false;
        }

        byte[] command = new byte[1 + payload.length];

        command[0] = (byte) typeId;
        System.arraycopy(payload, 0, command, 1, payload.length);
        this.outgoingTurn.queue(command);

        return true;
    }

    /**
     * Queues a mod command of the local player into the outgoing turn ({@link ReplayCommandIds#MOD_COMMAND}): when it runs, the game's
     * simulation plugins receive the data ({@link ScrSimulationPlugin#onModCommand}). The turn holds a limited number of bytes, so a command is
     * refused while the commands queued for the turn leave no room for it; it can be submitted again for a later turn.
     *
     * @param data the command data, at most {@link ReplayModCommand#MAXIMUM_DATA_LENGTH} bytes; the submitter keeps a copy
     * @return {@code true} if queued, {@code false} if the outgoing turn has no room for it
     * @throws IllegalArgumentException if the data is longer than {@link ReplayModCommand#MAXIMUM_DATA_LENGTH} bytes
     */
    @Override
    public boolean submitModCommand(byte @NotNull [] data) {
        byte[] payload = ReplayCommandCodecs.encode(ReplayCommandCodecs.MOD_COMMAND, new ReplayModCommand(data.clone()));

        if (!this.outgoingTurn.hasRoom(1 + payload.length)) {
            return false;
        }

        return this.submit(ReplayCommandIds.MOD_COMMAND, payload);
    }

    /**
     * Supplies the game's player slots that resolve the issuers of its commands, outgoing turn buffer, provider turn limits,
     * timing, lobby state and initial pause counts, this game as the game's mod command submitter unless the local player observes, and this
     * game as the game's pause requester; a single-player game also starts its Sync records from this game.
     *
     * @param game   the game's state
     * @param timing the game's speed configuration, whose turn-wait budget schedules the turn exchanges
     */
    public void startSession(@NotNull Game game, @NotNull GameSpeed timing) {
        LockstepTransport network = this.transport;
        TurnRate turnRate = network == null ? TurnRate.UNSPECIFIED : network.getTurnRate();

        int latencyTurns = LATENCY_TURNS;

        if (network != null) {
            int providerTurns = turnRate.getNetworkTurnsInTransit();
            int capped = Integer.compareUnsigned(providerTurns, NETWORK_MAXIMUM_LATENCY_TURNS) < 0 ? providerTurns : NETWORK_MAXIMUM_LATENCY_TURNS;

            latencyTurns = Integer.compareUnsigned(capped, NETWORK_MINIMUM_LATENCY_TURNS) > 0 ? capped : NETWORK_MINIMUM_LATENCY_TURNS;
        }

        game.setPlayerSlots(this.playerSlots);
        game.getInterfaceLink().setOutgoingTurn(this.outgoingTurn);
        game.getInterfaceLink().setSessionTurnLimits(latencyTurns, MAXIMUM_TURN_BYTES);
        game.getInterfaceLink().setModCommandSubmitter(this.isLocalObserver() ? null : this);
        game.getInterfaceLink().setPauseRequester(this);
        game.getGameContext().setContextLobbyColors(this.setup.getResolvedLobbyColors());

        int[] lobbyTypes = this.setup.getLobbyTypes();
        int[] lobbyRaces = this.setup.getResolvedSlotRaces();

        if (lobbyTypes != null && lobbyRaces != null) {
            byte[] types = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];
            byte[] races = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];

            for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                types[slot] = (byte) lobbyTypes[slot];
                races[slot] = (byte) lobbyRaces[slot];
            }

            game.getGameContext().setLobby(Math.max(this.setup.getLobbySlot(this.localPlayerId), 0), types, races);
        }

        this.game       = game;
        this.timing     = timing;
        this.pausesLeft = game.getGameContext().getContextPauses();

        // A single-player game makes its Sync records from the game state without drawing values.
        if (network == null) {
            this.syncRing = new SyncRing(this.localPlayer, false);
            this.syncGame = game;
        }

        Arrays.fill(this.pausesLeft, (byte) INITIAL_PAUSES);

        timing.startSession(network == null ? 0 : 1, turnRate.getTurnsPerSecond(), turnRate.getNetworkTurnsPerSecond());

        if (network != null) {
            DynamicTurnRate rules = new DynamicTurnRate(new TurnRateSession(), System::nanoTime, (this.gameFlags & DYNAMIC_TURN_RATE_FLAG) != 0,
                                                        latencyTurns);

            this.dynamicTurnRate = rules;

            rules.start();
        }
    }

    /**
     * Queues a Latency command for all clients to apply in the same turn.
     *
     * @param latency the latency 0 low, 1 high, 2 extra high
     * @return {@code true} if queued, {@code false} otherwise (outside a network game)
     */
    public boolean requestLatency(int latency) {
        if (!this.isNetworkGame()) {
            return false;
        }

        this.outgoingTurn.queue(new byte[]{(byte) ReplayCommandIds.LATENCY, (byte) Math.clamp(latency, 0, MAXIMUM_LATENCY)});

        return true;
    }

    /**
     * Takes the Latency commands executed since the last call, for their messages: each entry is the sender's slot and
     * the new latency; a silent command ({@code 0x80}) makes none.
     *
     * @return the notices, oldest first
     */
    public @NotNull List<int[]> takeLatencyNotices() {
        List<int[]> taken = List.copyOf(this.latencyNotices);

        this.latencyNotices.clear();

        return taken;
    }

    /**
     * Executes the received latency setting, updates turns in transit and retains non-silent message notices.
     *
     * @param payload  the command bytes after the type byte
     * @param playerId the sender's network id
     */
    private void executeLatency(byte @NotNull [] payload, int playerId) {
        if (payload.length == 0) {
            return;
        }

        int value = Byte.toUnsignedInt(payload[0]);

        this.latency        = readLatency(value);
        this.turnsInTransit = this.baseTurnsInTransit + this.latency;

        if ((value & SILENT_LATENCY) == 0) {
            this.latencyNotices.add(new int[]{this.findCommandSlot(playerId), this.latency});
        }
    }

    /**
     * Executes a received Turn Rate command ({@link #changeTurnRate}).
     *
     * @param payload the command bytes after the type byte: the turns per second
     */
    private void executeTurnRate(byte @NotNull [] payload) {
        if (payload.length > 0) {
            this.changeTurnRate(Byte.toUnsignedInt(payload[0]));
        }
    }

    /**
     * Executes a received Dynamic Turn Rate command: a scan start ({@link DynamicTurnRate#onScanStart}), or a scan result, which ends the
     * scan, sets the turn rate ({@link #changeTurnRate}) and sets the latency silently.
     *
     * @param payload  the command bytes after the type byte: the sub-command ({@code 0} scan start), the turns per second and the latency
     * @param playerId the sender's network id
     */
    private void executeDynamicTurnRate(byte @NotNull [] payload, int playerId) {
        DynamicTurnRate rules = this.dynamicTurnRate;

        if (payload.length < DYNAMIC_TURN_RATE_PAYLOAD) {
            return;
        }

        if (payload[0] == 0) {
            if (rules != null) {
                rules.onScanStart();
            }

            return;
        }

        if (rules != null) {
            rules.onScanResult();
        }

        this.changeTurnRate(Byte.toUnsignedInt(payload[1]));
        this.executeLatency(new byte[]{(byte) (payload[2] | SILENT_LATENCY)}, playerId);
    }

    /**
     * Sets the network game's turns per second when they differ, noting the change to the turn rate rules, and refills the timing's
     * timeouts from them.
     *
     * @param turnRate the turns per second
     */
    private void changeTurnRate(int turnRate) {
        DynamicTurnRate rules = this.dynamicTurnRate;
        GameSpeed timing = this.timing;

        if (turnRate != this.turnRate) {
            this.turnRate = turnRate;

            if (rules != null) {
                rules.onTurnRateChanged();
            }
        }

        if (timing != null) {
            timing.setTurnRate(this.turnRate);
        }
    }

    /**
     * Queues a Pause command while the game is running.
     * <p>
     * The command executes in the turn and is not recorded.
     */
    @Override
    public void requestPause() {
        if (!this.isPaused()) {
            this.outgoingTurn.queue(new byte[]{(byte) ReplayCommandIds.PAUSE});
        }
    }

    /**
     * Queues a Resume command while the game is paused.
     */
    @Override
    public void requestResume() {
        if (this.isPaused()) {
            this.outgoingTurn.queue(new byte[]{(byte) ReplayCommandIds.RESUME});
        }
    }

    /**
     * Queues a Save Game command for the first player, retaining the time and the name bytes cut to the command's name
     * buffer. Nothing is queued while the started game cannot be saved ({@link Game#isSaveAllowed()}).
     *
     * @param time the issuing machine's time DWORD
     * @param name the name bytes without terminator: the file name without extension
     */
    public void requestSaveGame(int time, byte @NotNull [] name) {
        Game running = this.game;

        if (this.isLocalPlayerFirst() && (running == null || running.isSaveAllowed())) {
            this.outgoingTurn.queue(encodeSavedGameCommand(ReplayCommandIds.SAVE_GAME, time, name));
        }
    }

    /**
     * Queues a Load Game command with the listed file time and the name bytes cut to the command's name buffer.
     *
     * @param time the time DWORD of the listed file
     * @param name the name bytes without terminator: the file name without extension
     */
    public void requestLoadGame(int time, byte @NotNull [] name) {
        this.outgoingTurn.queue(encodeSavedGameCommand(ReplayCommandIds.LOAD_GAME, time, name));
    }

    /**
     * Executes an unrecorded Save Game command for the first network player, or Load Game in single player; the handler
     * receives the name bytes as the command holds them.
     *
     * @param typeId   the command type byte
     * @param payload  the command bytes after the type byte
     * @param playerId the sender's network id
     */
    private void executeSavedGameCommand(int typeId, byte @NotNull [] payload, int playerId) {
        SavedGameHandler handler = this.savedGameHandler;

        if (handler == null || payload.length < Integer.BYTES + 1) {
            return;
        }

        int time = LittleEndianUtils.readInt32(payload, 0);
        byte[] name = Arrays.copyOfRange(payload, Integer.BYTES, payload.length - 1);

        if (typeId == ReplayCommandIds.SAVE_GAME) {
            if (this.isNetworkGame() && playerId != this.findFirstHumanPlayerId()) {
                return;
            }

            handler.saveGame(time, name);
        } else if (!this.isNetworkGame()) {
            handler.loadGame(time, name);
        }
    }

    /**
     * @return the lowest human network id, or {@code -1} without one
     */
    private int findFirstHumanPlayerId() {
        int first = -1;

        for (MeleeGameSetup.PlayerRecord record : this.records) {
            if (record.getType() == MeleeGameSetup.TYPE_HUMAN && (first < 0 || record.getPlayerId() < first)) {
                first = record.getPlayerId();
            }
        }

        return first;
    }

    /**
     * Takes the pauses and resumes executed since the last call, for the interface's game messages.
     *
     * @return the notices in execution order
     */
    public @NotNull List<PauseNotice> takePauseNotices() {
        List<PauseNotice> taken = List.copyOf(this.pauseNotices);

        this.pauseNotices.clear();

        return taken;
    }

    /**
     * Executes an unrecorded Pause or Resume command and retains its message notice; network pauses consume a count.
     *
     * @param typeId the command type byte
     * @param slot   the command slot of the sender
     */
    private void executePause(int typeId, int slot) {
        Game game = this.game;
        TriggerContext triggers = game == null ? null : game.getTriggerContext();

        if (triggers == null) {
            return;
        }

        if (typeId == ReplayCommandIds.PAUSE) {
            if (this.isPaused() || !this.canPause(slot) || !triggers.pauseGame()) {
                return;
            }

            if (this.isNetworkGame() && this.pausesLeft[slot] != 0) {
                this.pausesLeft[slot]--;
            }

            this.pauseNotices.add(new PauseNotice(slot, true, this.getPausesLeft(slot)));

            return;
        }

        if (!triggers.resumeGame()) {
            return;
        }

        this.pauseNotices.add(new PauseNotice(slot, false, slot < 0 ? 0 : this.getPausesLeft(slot)));
    }

    /**
     * Handles typed text as a cheat when allowed, queues changed flags and discards other single-player text.
     * <p>
     * A single-player game checks every line: the check reseeds the game's C runtime generator with the given time
     * ({@link CheatCodesUtils#apply(String, int, RuntimeRandom, int)}) before the cheat option is read, whether the line
     * matches or not. A network game checks a line only when the client's switch allows it, without the game's generator
     * ({@link CheatCodesUtils#apply(String, int)}).
     *
     * @param text          the typed line
     * @param currentFlags  the executed cheat flags
     * @param cheatsEnabled whether the game header enables cheats
     * @param runtimeRandom the game's C runtime generator
     * @param unixTime      the current Unix time in seconds, truncated to an {@code int}
     * @return {@link #TEXT_CHEAT}, {@link #TEXT_CHEAT_UNCHANGED} or {@link #TEXT_DISCARDED}
     */
    public int submitText(@NotNull String text, int currentFlags, boolean cheatsEnabled, @NotNull RuntimeRandom runtimeRandom, int unixTime) {
        if (this.isNetworkGame() && (!this.localCheatsAllowed || this.hasOtherHuman())) {
            return TEXT_DISCARDED;
        }

        long flags = this.isNetworkGame() ? CheatCodesUtils.apply(text, currentFlags)
                                          : CheatCodesUtils.apply(text, currentFlags, runtimeRandom, unixTime);

        if (!cheatsEnabled || flags == CheatCodesUtils.NOT_A_CHEAT) {
            return TEXT_DISCARDED;
        }

        if ((int) flags == currentFlags) {
            return TEXT_CHEAT_UNCHANGED;
        }

        this.submit(ReplayCommandIds.CHEAT, ReplayCommandCodecs.encodeValue(ReplayCommandIds.CHEAT, (int) flags));

        return TEXT_CHEAT;
    }

    /**
     * Attaches a network turn stream and configures the local turns kept in transit.
     *
     * @param transport      the turn stream
     * @param turnsInTransit the local turns kept in transit
     * @param keepAlive      whether turns carry Keep Alive instead of the Sync command
     *                       ({@link OutgoingTurnBuffer#useKeepAlive()}), so that peers do not drop this player
     */
    public void attachNetwork(@NotNull LockstepTransport transport, int turnsInTransit, boolean keepAlive) {
        this.transport          = transport;
        this.turnExchange       = new TurnExchange(transport, this.outgoingTurn::send);
        this.baseTurnsInTransit = turnsInTransit;
        this.turnsInTransit     = turnsInTransit + this.latency;
        this.turnRate           = transport.getTurnRate().getTurnsPerSecond();

        if (keepAlive) {
            this.outgoingTurn.useKeepAlive();
        }
    }

    /**
     * Presses the waiting-for-players dialog's Drop Players button ({@link LockstepTransport#dropWaitingPeers()}).
     */
    public void dropWaitingPlayers() {
        LockstepTransport network = this.transport;

        if (network != null) {
            network.dropWaitingPeers();
        }
    }

    /**
     * Asks the attached transport to leave the network game with reason {@link #LEAVE_REASON}.
     */
    public void leaveNetwork() {
        LockstepTransport network = this.transport;

        if (network == null) {
            return;
        }

        network.leave(LEAVE_REASON);
    }

    private void updateSync() {
        SyncRing ring = this.syncRing;
        Game game = this.syncGame;

        if (ring != null && game != null) {
            ring.sampleDrawing(game);
            ring.update(game);
        }
    }

    /**
     * Lets the frame's drawing take the Sync drawing values when it is the first drawing after a turn step
     * ({@link SyncRing#updateDrawing}).
     */
    private void updateSyncDrawing() {
        SyncRing ring = this.syncRing;
        Game game = this.syncGame;
        IntSupplier row = this.cameraRow;

        if (ring != null && game != null && row != null) {
            ring.updateDrawing(game, row.getAsInt());
        }
    }

    /**
     * Attaches the session that runs this game's recorded commands: it makes the Sync record after the commands of each
     * step ({@link #onCommandsExecuted()}) and runs the startup turn's commands ({@link #getStartupTurnCommandCount()}) as the
     * startup turn step. Its recorded stream's controller source is the network game's turns or the local person
     * ({@link ReplaySession#setControllerSource}); the network is attached before.
     *
     * @param session the session of this game's runtime
     */
    public void attachReplaySession(@NotNull ReplaySession session) {
        session.setCommandsExecutedListener(this::onCommandsExecuted);
        session.setStartupTurnCommandCount(this::getStartupTurnCommandCount);
        session.setControllerSource(this.isNetworkGame() ? ControllerSource.NETWORK : ControllerSource.HUMAN);
    }

    /**
     * Makes the sampled Sync record after commands execute and before the frame runs; ignores unsampled frames.
     */
    public void onCommandsExecuted() {
        SyncRing ring = this.syncRing;
        Game game = this.syncGame;

        if (ring != null && game != null && ring.isSampled()) {
            ring.update(game);
        }
    }

    /**
     * Runs a network frame's turn exchange ({@link TurnExchange}: replenishes the outgoing turns in transit, receives every
     * player's turn and replenishes them again) and records each sender in transport order.
     *
     * @param currentFrame the frame about to execute
     * @param exchange     the turn exchange over the attached turn stream
     * @return {@code true} if the frame may run, {@code false} otherwise (while a player's turn is missing)
     */
    private boolean exchangeNetworkTurn(long currentFrame, @NotNull TurnExchange exchange) {
        if (!this.networkStarted && !this.startNetworkTurns(currentFrame, exchange)) {
            return false;
        }

        SyncRing ring = this.syncRing;
        List<PeerTurn> turns = exchange.exchange(this.turnsInTransit);

        if (turns == null) {
            this.onTurnMissing();

            return false;
        }

        this.onTurnsReceived(turns);

        // The frame's record is made after its commands executed (onCommandsExecuted); its drawing values are those of the
        // first drawing after the previous turn step (updateSyncDrawing).
        if (ring != null && this.syncGame != null) {
            ring.sampleDrawing(this.syncGame);
        }

        for (PeerTurn turn : turns) {
            this.syncMonitor.check(turn, (int) currentFrame);
            this.record(turn.getTurn(), turn.getPeer().getNumber(), currentFrame);
        }

        return true;
    }

    /**
     * Runs the initial turn exchange without advancing a game frame, retaining Sync records before and after commands. The
     * commands it records are the startup turn's ({@link #getStartupTurnCommandCount()}).
     *
     * @param currentFrame the frame about to execute
     * @param exchange     the turn exchange over the attached turn stream
     * @return {@code true} if the startup turn ran, {@code false} otherwise (while a player's turn is missing)
     */
    private boolean startNetworkTurns(long currentFrame, @NotNull TurnExchange exchange) {
        SyncRing ring = this.syncRing;
        int firstCommand = this.history.size();

        // A start step that has to wait must not make record 1 again. The game start queues the Sync command of record 1.
        if (ring != null && ring.getUpdates() == 0) {
            this.updateSync();
            this.outgoingTurn.startSync(ring);
        }

        List<PeerTurn> turns = exchange.exchange(this.turnsInTransit);

        if (turns == null) {
            this.onTurnMissing();

            return false;
        }

        this.onTurnsReceived(turns);

        for (PeerTurn turn : turns) {
            this.syncMonitor.check(turn, (int) currentFrame);
            this.record(turn.getTurn(), turn.getPeer().getNumber(), currentFrame);
        }

        if (ring != null && ring.getUpdates() == 1) {
            this.updateSync();
        }

        this.networkStarted          = true;
        this.startupTurnCommandCount = this.history.size() - firstCommand;

        return true;
    }

    /**
     * Tells the turn rate rules that a turn step misses a turn.
     */
    private void onTurnMissing() {
        DynamicTurnRate rules = this.dynamicTurnRate;

        if (rules != null) {
            rules.onTurnMissing();
        }
    }

    /**
     * Notes whether the local player is the present player of the lowest network id below {@code 8} among the senders of a completed turn
     * step, and tells the turn rate rules, before the step's commands run.
     *
     * @param turns the step's turns
     */
    private void onTurnsReceived(@NotNull List<PeerTurn> turns) {
        DynamicTurnRate rules = this.dynamicTurnRate;
        int lowest = PlayerTable.PLAYABLE_PLAYER_SLOTS;

        for (PeerTurn turn : turns) {
            int playerId = turn.getPeer().getNumber();

            if (playerId >= 0 && playerId < lowest) {
                lowest = playerId;
            }
        }

        this.lowestPresentPlayer = lowest == this.localPlayerId;

        if (rules != null) {
            rules.onTurnReceived();
        }
    }

    /**
     * Checks a player's Sync command against the local record it names: the sync check of {@link #syncMonitor}, which
     * reports a mismatch ({@link #logSyncMismatch}) without dropping the sender.
     *
     * @param received the player's received turn, the peer its network id
     * @return the description of the mismatch, or {@code null} if the turn matches, starts with no Sync command, or its
     * sender holds no record, and always for an observer, whose records take its own slot masks ({@link SyncChecksumUtils#calculateSlotMasks})
     */
    private @Nullable String checkSyncTurn(@NotNull PeerTurn received) {
        SyncRing ring = this.syncRing;
        byte[] turn = received.getTurn();

        if (ring == null || ring.getUpdates() == 0 || this.isLocalObserver() || turn.length < OutgoingTurnBuffer.SYNC_COMMAND_LENGTH ||
            turn[0] != OutgoingTurnBuffer.SYNC_COMMAND) {
            return null;
        }

        int recordSlot = this.findSlot(received.getPeer().getNumber());

        if (recordSlot < 0) {
            return null;
        }

        return ring.check(turn, this.commandSlots[recordSlot]);
    }

    /**
     * Logs a Sync mismatch: one by one up to {@link #MISMATCH_LOG_FIRST}, then one in {@link #MISMATCH_LOG_INTERVAL}, and
     * the local record's hash inputs for the first {@link #MISMATCH_DETAIL_FIRST}. The first mismatch also writes the desync dump
     * ({@link #setDesyncDumpTarget}).
     *
     * @param received   the player's received turn, the peer its network id
     * @param frame      the frame about to execute
     * @param failure    the description of the mismatch
     * @param mismatches the mismatches so far, this one included
     */
    private void logSyncMismatch(@NotNull PeerTurn received, int frame, @NotNull String failure, int mismatches) {
        SyncRing ring = this.syncRing;
        byte[] turn = received.getTurn();
        int playerId = received.getPeer().getNumber();
        int senderSlot = this.commandSlots[this.findSlot(playerId)];

        if (mismatches <= MISMATCH_LOG_FIRST || mismatches % MISMATCH_LOG_INTERVAL == 0) {
            LOGGER.warning(String.format("LAN GAME SYNC MISMATCH: frame %d, player %d (slot %d) sent %s, local %s; %d mismatches",
                                         frame, playerId, senderSlot,
                                         HexFormat.of().formatHex(turn, 0, OutgoingTurnBuffer.SYNC_COMMAND_LENGTH), failure,
                                         mismatches));
        }

        if (mismatches <= MISMATCH_DETAIL_FIRST && ring != null) {
            LOGGER.warning("LAN GAME SYNC MISMATCH DETAIL: frame " + frame + ", local " + ring.describe(turn));
        }

        if (mismatches == 1) {
            this.writeDesyncDump(turn, frame, failure, playerId, senderSlot);
        }
    }

    /**
     * Writes the desync dump of the first Sync mismatch: the state is copied now, and the file is formatted and written on a
     * background thread, which logs its path.
     *
     * @param turn       the received turn, which starts with the Sync command
     * @param frame      the frame about to execute
     * @param failure    the description of the mismatch
     * @param playerId   the sender's network id
     * @param senderSlot the sender's slot
     */
    private void writeDesyncDump(byte @NotNull [] turn, int frame, @NotNull String failure, int playerId, int senderSlot) {
        SyncRing ring = this.syncRing;
        Game state = this.syncGame;
        Path folder = this.desyncFolder;

        if (ring == null || state == null || folder == null) {
            return;
        }

        DesyncDump dump = ring.createDump(turn, state);
        String localName = this.isLocalObserver() ? this.setup.getObserverName(this.localSlot) : this.records[this.localSlot].getName();
        StringBuilder header = new StringBuilder();

        SyncState.appendLine(header, "SC4J desync dump");
        SyncState.appendLine(header, "build %s", this.desyncBuild);
        SyncState.appendLine(header, "map %s", this.desyncMapName);
        SyncState.appendLine(header, "local player id %d slot %d \"%s\"", this.localPlayerId, this.localPlayer, localName);

        for (int lobbySlot = 0; lobbySlot < PlayerTable.PLAYABLE_PLAYER_SLOTS; lobbySlot++) {
            MeleeGameSetup.PlayerRecord record = this.records[lobbySlot];

            if (record.getType() != PlayerTypes.NONE) {
                SyncState.appendLine(header, "player id %d slot %d type %d race %d team %d \"%s\"",
                                     record.getPlayerId(), this.commandSlots[lobbySlot], record.getType(), record.getRace(), record.getTeam(),
                                     record.getName());
            }
        }

        SyncState.appendLine(header, "mismatch frame %d player id %d slot %d sent %s local %s", frame, playerId, senderSlot,
                             HexFormat.of().formatHex(turn, 0, OutgoingTurnBuffer.SYNC_COMMAND_LENGTH), failure);

        String fileName = DESYNC_FILE_TIME.format(LocalDateTime.now()) + '_' + toFileNamePart(stripExtension(this.desyncMapName)) + '_' +
                          toFileNamePart(localName) + DESYNC_FILE_EXTENSION;
        Path file = folder.resolve(fileName);
        String text = header.toString();

        Thread.ofPlatform().daemon().name(DESYNC_THREAD_NAME).start(() -> {
            try {
                AtomicFileUtils.write(file, dump.format(text).getBytes(StandardCharsets.UTF_8));
                LOGGER.info("LAN GAME DESYNC DUMP: {0}", file);
            } catch (IOException | RuntimeException exception) {
                LOGGER.warning("LAN GAME DESYNC DUMP FAILED: {0}: {1}", file, exception.toString());
            }
        });
    }

    /**
     * Receives the turn in transit, sends the queued turn and appends received commands before the frame advances; a
     * single-player game keeps {@link #LATENCY_TURNS} plus the latency setting in transit ({@link LoopbackTurns}). Once
     * the session started, only a frame whose game loop runs a turn step ({@link GameSpeed#isTurnStepDue()}) exchanges a
     * turn; any other frame runs without one. The drawing before the frame first takes the Sync drawing values when it is
     * the first after a turn step. The first exchange of a single-player game makes the Sync records of the game start
     * (record 1, whose Sync command it queues, and the startup turn step's record 2).
     *
     * @param currentFrame the frame about to execute
     * @return {@code true} if the frame may run, {@code false} otherwise (in a network game while a player's turn is missing)
     * @throws UnsupportedOperationException if a received turn holds a command this session executes outside the command
     *                                       stream
     */
    public boolean exchangeTurn(long currentFrame) {
        GameSpeed timing = this.timing;

        this.updateSyncDrawing();

        if (timing != null && !timing.isTurnStepDue()) {
            return true;
        }

        TurnExchange exchange = this.turnExchange;

        if (exchange != null) {
            return this.exchangeNetworkTurn(currentFrame, exchange);
        }

        SyncRing ring = this.syncRing;
        Game state = this.syncGame;

        // The game start queues the Sync command of record 1, and the startup turn step makes record 2.
        if (ring != null && state != null && ring.getUpdates() == 0) {
            this.updateSync();
            this.outgoingTurn.startSync(ring);
            this.updateSync();
        }

        byte[] received = this.loopbackTurns.exchangeTurn(LATENCY_TURNS + this.latency);

        // The turn step's record is made after its commands executed (onCommandsExecuted); without that call the records
        // stay those of the game start.
        if (ring != null && state != null && !ring.isSampled()) {
            ring.sampleDrawing(state);
        }

        this.record(received, HUMAN_PLAYER_ID, currentFrame);

        return true;
    }

    /**
     * Records one player's received turn, walked by the command lengths ({@link ReplayCommandUtils#walk}): each command of
     * a recorded type goes to the history with the player's id; the Sync command and Keep Alive are not recorded. A command
     * that has no dispatcher case or does not fit the turn ends the turn: it and the commands after it are dropped.
     *
     * @param received     the player's received turn
     * @param playerId     the player's network id
     * @param currentFrame the frame about to execute
     * @throws UnsupportedOperationException if this session executes a walked command outside the command stream
     */
    private void record(byte @NotNull [] received, int playerId, long currentFrame) {
        ReplayCommandUtils.walk(received, 0, received.length,
                                (turn, offset, length) -> this.recordCommand(turn, offset, length, playerId, currentFrame));
    }

    /**
     * Records one walked command of a player's received turn.
     *
     * @param turn         the player's received turn
     * @param offset       the offset of the command's type byte
     * @param length       the command length, type byte included
     * @param playerId     the player's network id
     * @param currentFrame the frame about to execute
     * @throws UnsupportedOperationException if this session executes the command outside the command stream
     */
    private void recordCommand(byte @NotNull [] turn, int offset, int length, int playerId, long currentFrame) {
        int typeId = Byte.toUnsignedInt(turn[offset]);
        byte[] payload = Arrays.copyOfRange(turn, offset + 1, offset + length);

        if (ReplayWriter.isRecorded(typeId, payload.length)) {
            synchronized (this.replayLock) {
                this.history.add(new ReplayCommand(currentFrame, playerId, typeId, payload));
            }
        } else if (typeId == ReplayCommandIds.PAUSE || typeId == ReplayCommandIds.RESUME) {
            this.executePause(typeId, this.findCommandSlot(playerId));
        } else if (typeId == ReplayCommandIds.SAVE_GAME || typeId == ReplayCommandIds.LOAD_GAME) {
            this.executeSavedGameCommand(typeId, payload, playerId);
        } else if (typeId == ReplayCommandIds.LATENCY) {
            this.executeLatency(payload, playerId);
        } else if (typeId == ReplayCommandIds.TURN_RATE) {
            this.executeTurnRate(payload);
        } else if (typeId == ReplayCommandIds.DYNAMIC_TURN_RATE) {
            this.executeDynamicTurnRate(payload, playerId);
        } else if (typeId != OutgoingTurnBuffer.SYNC_COMMAND && typeId != ReplayCommandIds.KEEP_ALIVE) {
            throw Exceptions.newUnsupportedOperationException(
                    "The received turn executes command 0x{0}, which this session handles outside the command stream.",
                    HexFormat.of().withUpperCase().toHexDigits((byte) typeId)
            );
        }
    }

    /**
     * Retains the first outcome screen made due by the completed live frame.
     *
     * @param game the world after the frame
     * @return the retained outcome: {@code 0} none, {@code 2} defeat screen, {@code 3} victory screen
     */
    public int updateOutcome(@NotNull Game game) {
        TriggerContext triggers = game.getTriggerContext();
        int due = triggers == null ? 0 : triggers.consumeOutcomeDisplay();

        if (this.localOutcome == 0 && due != 0) {
            this.localOutcome = due;
        }

        return this.localOutcome;
    }

    /**
     * Writes the played game as a SC:R replay with chat records merged in frame order. The commands and chat lines are copied
     * under the lock their appends take, so the replay can be written from another thread while the game runs. A game with a speed override
     * ({@link MeleeGameSetup#getSpeedOverrideIndex}) keeps it in the replay's {@code XSPD} section, which its playback takes. The replay sections
     * of the started game ({@link Game#setReplaySection}) follow as mod sections ({@link ReplayWriter#addModSection}).
     *
     * @param frames      the number of completed frames
     * @param unixSeconds the save time for the {@code GCFG} block
     * @return the replay file bytes
     * @throws IllegalStateException if the sections may take more than a replay file holds
     */
    public byte @NotNull [] writeReplay(long frames, long unixSeconds) {
        byte[][] names = this.readNameBytes();
        List<ReplayCommand> recorded;

        synchronized (this.replayLock) {
            int chat = 0;

            recorded = new ArrayList<>(this.history.size() + this.chatRecords.size());

            for (ReplayCommand command : this.history) {
                while (chat < this.chatRecords.size() && this.chatRecords.get(chat).getFrame() < command.getFrame()) {
                    recorded.add(this.chatRecords.get(chat++));
                }

                recorded.add(command);
            }

            recorded.addAll(this.chatRecords.subList(chat, this.chatRecords.size()));
        }

        ReplayWriter writer = new ReplayWriter(this.buildHeader(frames, false), ReplayWriter.encodeCommands(recorded), this.setup.getMapData(),
                                               ReplayWriter.encodeNames(names), new byte[ReplayWriter.SKIN_SIZE], this.getLimits(),
                                               ReplayWriter.CURRENT_FIX_FLAGS,
                                               PlayerColorRows.encodeCustomColors(this.setup.getResolvedCustomColors()),
                                               ReplayWriter.encodeVersionBlock(GameRulesVersion.CURRENT_BUILD, unixSeconds),
                                               this.setup.getSpeedOverrideIndex());
        Game running = this.game;

        if (running != null) {
            for (Map.Entry<String, byte[]> section : running.getReplaySections().entrySet()) {
                writer.addModSection(section.getKey(), section.getValue());
            }
        }

        return writer.write();
    }

    /**
     * Reads the game's name table: the records' name bytes in slot order. Outside a game loaded from a saved game, a human's
     * entry holds its network name cut to the entry on whole characters ({@link TerminatedStringUtils#cutWholeCharacters}), as
     * the lobby names the slot of a network player; the other records keep their name bytes.
     *
     * @return the name bytes of every player slot
     */
    private byte @NotNull [] @NotNull [] readNameBytes() {
        byte[][] names = new byte[PlayerTable.PLAYER_SLOTS][];
        boolean lobbyNames = this.setup.getSavedHeader() == null;

        for (int slot = 0; slot < PlayerTable.PLAYER_SLOTS; slot++) {
            MeleeGameSetup.PlayerRecord record = this.records[slot];

            names[slot] = lobbyNames && record.getType() == PlayerTypes.HUMAN
                          ? TerminatedStringUtils.cutWholeCharacters(record.getNameBytes(), ReplayWriter.NAME_RECORD_SIZE)
                          : record.getNameBytes();
        }

        return names;
    }

    /**
     * Builds the resolved lobby header, or updates the frame count and seed of a retained saved-game header. The records'
     * names are the single-byte conversion of a written replay's header, except that the header of the game's start holds
     * the game's player table, where a computer's record keeps its nation name's bytes
     * ({@link ReplayHeaderBuilder#setPlayerTableName}) and a human's record its UTF-8 network name cut on whole characters
     * ({@link ReplayHeaderBuilder#setPlayerTableNetworkName}).
     *
     * @param frames      the frame count the header stores
     * @param playerTable whether the header holds the game's player table (the header of the game's start)
     * @return the header bytes
     */
    private byte @NotNull [] buildHeader(long frames, boolean playerTable) {
        byte[] savedHeader = this.setup.getSavedHeader();

        if (savedHeader != null) {
            ByteBuffer fields = ByteBuffer.wrap(savedHeader).order(ByteOrder.LITTLE_ENDIAN);

            fields.putInt(ReplayHeaderBuilder.FRAMES_OFFSET, (int) frames);
            fields.putInt(ReplayHeaderBuilder.SEED_OFFSET, this.seed);

            return savedHeader;
        }

        int humans = 0;
        boolean[] melee = this.setup.getUseMapSettingsMeleeUnitPlayers();

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            int type = this.records[slot].getType();

            if (!this.setup.isUseMapSettings()) {
                melee[slot] = type == PlayerTypes.COMPUTER || type == PlayerTypes.HUMAN;
            }

            if (type == PlayerTypes.HUMAN) {
                humans++;
            }
        }

        // The host name field holds a network game's name or a single-player game's human, the game name field a network
        // game's human; each text field is written once.
        String gameName = this.setup.getNetworkGameName();
        ReplayHeaderBuilder header = new ReplayHeaderBuilder()
                .setEngine(this.setup.isExpansion() ? HEADER_ENGINE_BROOD_WAR : HEADER_ENGINE_STARCRAFT)
                .setFrames(frames)
                .setCampaignMission(this.setup.getCampaignMission())
                .setSeed(this.seed)
                .setHostName(gameName != null ? gameName : this.setup.getHumanName())
                .setMapSize(this.setup.getMap().getWidth(), this.setup.getMap().getHeight())
                .setCounts(humans, this.setup.getStartSlots().size(), this.setup.getSpeed(), HEADER_GAME_STATE)
                .setGameType(this.setup.getGameType(), this.setup.getSubType())
                .setTileset(this.setup.getMap().getTileset(), HEADER_SAVED_FLAG, HEADER_COMPUTER_COUNT)
                .setNames(gameName != null ? this.setup.getHumanName() : "", this.setup.getScenarioName())
                .setGameOptions(this.setup.getTemplate().encode())
                .setColors(this.colors)
                .setMeleeUnitPlayers(melee);

        if (gameName != null) {
            header.setTurnRate(this.setup.getNetworkTurnRate());
        }

        for (int slot = 0; slot < PlayerTable.PLAYER_SLOTS; slot++) {
            MeleeGameSetup.PlayerRecord record = this.records[slot];

            header.setPlayer(slot, slot < this.commandSlots.length ? this.commandSlots[slot] : slot, record.getPlayerId(), record.getType(),
                             record.getRace(), record.getTeam(), record.getName());

            if (playerTable && record.getType() == PlayerTypes.COMPUTER) {
                header.setPlayerTableName(slot, record.getNameBytes());
            } else if (playerTable && record.getType() == PlayerTypes.HUMAN) {
                header.setPlayerTableNetworkName(slot, record.getNameBytes());
            }
        }

        return header.build();
    }
}
