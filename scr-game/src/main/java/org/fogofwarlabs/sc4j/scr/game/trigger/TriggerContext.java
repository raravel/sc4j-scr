package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.trigger.TriggerRegistry;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerPlayerPhase;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.map.MapStringSection;
import org.fogofwarlabs.sc4j.scr.game.player.*;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Retains the trigger scheduling, records and map trigger state of one game.
 * <p>
 * The class also owns the trigger state layout of the game context (locations, switches, countdown and leaderboard) and
 * the layout of the saved trigger part.
 * <p>
 * It is the context of the engine trigger framework for the SC:R conditions and actions ({@link ScrTriggerCondition},
 * {@link ScrTriggerAction}), which it holds in its condition and action registries, and it holds the operands of the condition
 * under test ({@link TriggerConditionOperands}).
 */
public final class TriggerContext implements org.fogofwarlabs.sc4j.engine.trigger.TriggerContext {
    /**
     * The {@link #getCountdownDisplay()} value while the countdown text is hidden.
     */
    public static final int COUNTDOWN_HIDDEN = -1;
    /**
     * The {@link #consumeOutcomeDisplay()} value of a due defeat screen.
     */
    public static final int OUTCOME_DEFEAT_SCREEN = 2;
    /**
     * The {@link #consumeOutcomeDisplay()} value of a due victory screen.
     */
    public static final int OUTCOME_VICTORY_SCREEN = 3;
    /**
     * The {@link #getExtendedDeathsMode()} bit of a map with an extended Deaths condition.
     */
    public static final int EXTENDED_DEATHS_CONDITION_MODE = 1;
    /**
     * The {@link #getExtendedDeathsMode()} bit of a map with an extended Set Deaths action.
     */
    public static final int EXTENDED_SET_DEATHS_MODE = 2;

    /**
     * The number of map locations of the full {@code MRGN} section.
     */
    public static final int LOCATION_COUNT = 255;
    /**
     * The size of one {@code MRGN} record: four bound DWORDs, a string WORD and an elevation-flag WORD.
     */
    public static final int LOCATION_RECORD_SIZE = 20;
    /**
     * The game context offset of the location records.
     */
    public static final int LOCATIONS_OFFSET = 0xEB70;
    /**
     * The size of the saved trigger header: eight initially-active bytes and eight WORD record counts.
     */
    public static final int SAVED_RECORDS_HEADER_SIZE = 0x18;
    /**
     * The size of the map's unit property records ({@code UPRP}, {@code 64} records of {@code 20} bytes).
     */
    public static final int UNIT_PROPERTIES_SIZE = 0x500;
    /**
     * The player argument that starts the active-player walk ({@link #getNextActivePlayer}).
     */
    public static final int WALK_START = -1;

    /**
     * The {@link #initializeEvaluation} control of the stop byte.
     */
    public static final int CONTROL_STOP_FLAGS = 0;
    /**
     * The {@link #initializeEvaluation} control of the decoded session state.
     */
    public static final int CONTROL_SESSION_STATE = 1;
    /**
     * The {@link #initializeEvaluation} control of the multiplayer session BYTE.
     */
    public static final int CONTROL_MULTIPLAYER_SESSION = 2;
    /**
     * The {@link #initializeEvaluation} control of the cheat result flags DWORD.
     */
    public static final int CONTROL_RESULT_FLAGS = 3;
    /**
     * The {@link #initializeEvaluation} control of the local player DWORD.
     */
    public static final int CONTROL_LOCAL_PLAYER = 4;
    /**
     * The number of {@link #initializeEvaluation} controls.
     */
    public static final int CONTROL_COUNT = 5;

    /**
     * The decoded session state at which the record and action loops stop.
     */
    static final int STOPPING_SESSION_STATE = 0x21;
    /**
     * The periodic callback WORD after it ran the leaderboard.
     */
    static final int PERIODIC_COUNTDOWN_RELOAD = 15;
    /**
     * The game-clock WORD after it advanced the elapsed time.
     */
    static final int CLOCK_COUNTDOWN_RELOAD = 15;
    /**
     * The trigger-cycle WORD after an evaluation pass.
     */
    static final int CYCLE_COUNTDOWN_RELOAD = 30;
    /**
     * The victory countdown WORD after it made the outcome screen due, and after a defeat of the local player.
     */
    static final int VICTORY_COUNTDOWN_RELOAD = 45;
    /**
     * The pause request of the next trigger step while no plugin requested a change.
     */
    static final int NO_PAUSE_REQUEST = 0;
    /**
     * The pause request of the next trigger step after a plugin requested Pause Game ({@link #requestPauseGame()}).
     */
    static final int PAUSE_REQUEST = 1;
    /**
     * The pause request of the next trigger step after a plugin requested Unpause Game ({@link #requestUnpauseGame()}).
     */
    static final int UNPAUSE_REQUEST = 2;
    /**
     * The largest countdown, {@code 99} hours, {@code 59} minutes and {@code 59} seconds.
     */
    static final int COUNTDOWN_MAXIMUM = 359999;
    /**
     * The number of switches, one bit each.
     */
    static final int SWITCH_COUNT = 256;
    /**
     * The shift from a switch to its DWORD.
     */
    static final int SWITCH_DWORD_SHIFT = 5;
    /**
     * The mask of a switch's bit within its DWORD.
     */
    static final int SWITCH_BIT_MASK = Integer.SIZE - 1;

    /**
     * The number of locations of the short {@code MRGN} section.
     */
    private static final int SHORT_LOCATION_COUNT = 64;
    /**
     * The size of the full {@code MRGN} section.
     */
    private static final int LOCATION_SECTION_SIZE = LOCATION_COUNT * LOCATION_RECORD_SIZE;
    /**
     * The size of the short {@code MRGN} section.
     */
    private static final int SHORT_LOCATION_SECTION_SIZE = SHORT_LOCATION_COUNT * LOCATION_RECORD_SIZE;
    /**
     * The offsets of the bound DWORDs in an {@code MRGN} record.
     */
    private static final int LOCATION_LEFT_OFFSET = 0;
    private static final int LOCATION_TOP_OFFSET = 4;
    private static final int LOCATION_RIGHT_OFFSET = 8;
    private static final int LOCATION_BOTTOM_OFFSET = 12;
    /**
     * The offset of the location-name string WORD in an {@code MRGN} record.
     */
    private static final int LOCATION_STRING_OFFSET = 16;
    /**
     * The offset of the elevation-flag WORD in an {@code MRGN} record.
     */
    private static final int LOCATION_ELEVATION_OFFSET = 18;

    /**
     * The leader slot of a force without a leader in the active-player walk.
     */
    private static final int NO_TEAM_LEADER = -1;

    /**
     * The campaign missions from this one on cannot skip transmissions.
     */
    private static final int UNSKIPPABLE_MISSION = 0x20;
    /**
     * The mask of a player's result byte in the pass results.
     */
    private static final long PLAYER_RESULT_MASK = 0xFF;
    /*
     * Byte offsets in the trigger globals from the pending result bytes: eight waiting bytes, eight wait DWORDs, eight initially active bytes,
     * the current-record pointer, eight unused bytes and the fallback record.
     */
    private static final long GLOBAL_WAITING = 8;
    private static final long GLOBAL_WAIT_TIMERS = 16;
    private static final long GLOBAL_INITIALLY_ACTIVE = 48;
    private static final long GLOBAL_CURRENT_RECORD = 56;
    private static final long GLOBAL_PADDING = 64;
    private static final long GLOBAL_FALLBACK_RECORD = 72;
    private static final long GLOBAL_END = GLOBAL_FALLBACK_RECORD + TriggerRecordCodec.RECORD_SIZE;
    /**
     * The masks of a BYTE and a WORD of trigger state.
     */
    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The multiplier of the state hash.
     */
    private static final int HASH_MULTIPLIER = 31;

    /*
     * Trigger state in the game context, which the EUD game-data block shows at the same offsets.
     */
    /**
     * The mission objectives string DWORDs of the {@code 12} players.
     */
    static final int MISSION_OBJECTIVES_OFFSET = 0xE5D4;
    private static final int COUNTDOWN_OFFSET = 0xE604;
    private static final int ELAPSED_OFFSET = 0xE608;
    private static final int LEADERBOARD_COMPUTERS_OFFSET = 0xE618;
    private static final int LEADERBOARD_TYPE_OFFSET = 0xE61C;
    private static final int LEADERBOARD_LOCATION_OFFSET = 0xE61D;
    private static final int LEADERBOARD_UNIT_OFFSET = 0xE61E;
    private static final int LEADERBOARD_GOAL_OFFSET = 0xE620;
    private static final int LEADERBOARD_STRING_OFFSET = 0xE624;
    private static final int SWITCHES_OFFSET = 0xEB50;
    private static final int COUNTDOWN_PAUSED_OFFSET = 0xFF5C;
    /**
     * The mask of a byte offset within its WORD.
     */
    private static final int WORD_OFFSET_MASK = Short.BYTES - 1;
    /**
     * The offset of the WORD record counts in the saved trigger header.
     */
    private static final int SAVED_RECORD_COUNTS = 0x08;
    /**
     * The DWORD of the first leaderboard pair after the all-units type counts of the count cache: the mode and location
     * BYTEs and the gap bytes come first.
     */
    private static final int LEADERBOARD_PAIRS_DWORD = 4;
    /**
     * The DWORDs of one leaderboard pair: the player index and the value.
     */
    private static final int LEADERBOARD_PAIR_SIZE = 2;
    /**
     * The shift of the location BYTE above the mode BYTE.
     */
    private static final int COUNT_CACHE_LOCATION_SHIFT = Byte.SIZE;
    /**
     * The bits of the first count-cache DWORD above the mode and location BYTEs, the first gap bytes.
     */
    private static final int COUNT_CACHE_GAP_MASK = 0xFFFF0000;


    /**
     * Serves the game-context DWORDs after the all-units type counts of the trigger count cache, which an index past
     * those counts addresses: the count-cache mode and location BYTEs and two gap bytes in the first DWORD, gap DWORDs up
     * to {@link #LEADERBOARD_PAIRS_DWORD}, then the leaderboard pairs of player index and value.
     */
    private final class CountCacheTail implements ContextDwordTable {
        /**
         * Returns a DWORD by its native linear index.
         *
         * @param index the DWORD index from the start of the table
         * @return the DWORD
         * @throws UnsupportedOperationException if the DWORD lies past the leaderboard pairs
         */
        @Override
        public int getByNativeIndex(int index) {
            TriggerContext context = TriggerContext.this;

            if (index == 0) {
                return context.preparationFlag & BYTE_MASK |
                       (context.preparedLocation & BYTE_MASK) << COUNT_CACHE_LOCATION_SHIFT |
                       context.countCacheGap[0];
            }

            if (index < LEADERBOARD_PAIRS_DWORD) {
                return context.countCacheGap[index];
            }

            int pair = this.requirePair(index);

            return (index - LEADERBOARD_PAIRS_DWORD) % LEADERBOARD_PAIR_SIZE == 0 ? context.leaderboardPlayerIndices[pair]
                                                                                  : context.leaderboardValues[pair];
        }

        /**
         * Sets a DWORD by its native linear index.
         *
         * @param index the DWORD index from the start of the table
         * @param value the new DWORD
         * @throws UnsupportedOperationException if the DWORD lies past the leaderboard pairs
         */
        @Override
        public void setByNativeIndex(int index, int value) {
            TriggerContext context = TriggerContext.this;

            if (index == 0) {
                context.preparationFlag  = value & BYTE_MASK;
                context.preparedLocation = value >>> COUNT_CACHE_LOCATION_SHIFT & BYTE_MASK;
                context.countCacheGap[0] = value & COUNT_CACHE_GAP_MASK;

                return;
            }

            if (index < LEADERBOARD_PAIRS_DWORD) {
                context.countCacheGap[index] = value;

                return;
            }

            int pair = this.requirePair(index);

            if ((index - LEADERBOARD_PAIRS_DWORD) % LEADERBOARD_PAIR_SIZE == 0) {
                context.leaderboardPlayerIndices[pair] = value;
            } else {
                context.leaderboardValues[pair] = value;
            }
        }

        /**
         * Returns the leaderboard pair of a DWORD after the gap bytes.
         *
         * @param index the DWORD index after the all-units type counts
         * @return the leaderboard pair
         * @throws UnsupportedOperationException if the DWORD lies past the leaderboard pairs
         */
        private int requirePair(int index) {
            int pair = (index - LEADERBOARD_PAIRS_DWORD) / LEADERBOARD_PAIR_SIZE;

            if (pair >= TriggerContext.this.leaderboardValues.length) {
                throw Exceptions.newUnsupportedOperationException("Count-cache DWORD {0} lies past the leaderboard pairs.", index);
            }

            return pair;
        }
    }

    /**
     * Tests whether a game-context offset holds trigger state of this context: the mission objectives, the countdown
     * and elapsed clocks, the leaderboard, the switches, the locations and the countdown pause.
     *
     * @param contextOffset a game-context offset
     * @return {@code true} if the offset holds trigger state of this context, {@code false} otherwise
     */
    public static boolean hasGameDataOffset(int contextOffset) {
        return (contextOffset >= MISSION_OBJECTIVES_OFFSET && contextOffset < ELAPSED_OFFSET + Integer.BYTES) ||
               (contextOffset >= LEADERBOARD_COMPUTERS_OFFSET && contextOffset < LEADERBOARD_STRING_OFFSET + Integer.BYTES) ||
               (contextOffset >= SWITCHES_OFFSET && contextOffset < COUNTDOWN_PAUSED_OFFSET + Integer.BYTES);
    }

    /**
     * Returns the game-context offset of the trigger state field that holds a byte: the DWORD, the location WORD or the
     * leaderboard BYTE or WORD.
     *
     * @param contextOffset a game-context offset
     * @return the offset of the field holding the byte
     * @throws IllegalArgumentException if the offset holds no trigger state of this context
     */
    private static int getGameDataFieldBase(int contextOffset) {
        if (!hasGameDataOffset(contextOffset)) {
            throw Exceptions.newIllegalArgumentException("TriggerContext offset {0} is not trigger state.", String.format("0x%X", contextOffset));
        }

        if (contextOffset >= LOCATIONS_OFFSET && contextOffset < COUNTDOWN_PAUSED_OFFSET) {
            int record = LOCATIONS_OFFSET + (contextOffset - LOCATIONS_OFFSET) / LOCATION_RECORD_SIZE * LOCATION_RECORD_SIZE;
            int field = contextOffset - record;

            return record + (field < LOCATION_STRING_OFFSET ? field & ~ClassicRegion.DWORD_OFFSET_MASK : field & ~WORD_OFFSET_MASK);
        }

        if (contextOffset == LEADERBOARD_TYPE_OFFSET || contextOffset == LEADERBOARD_LOCATION_OFFSET) {
            return contextOffset;
        }

        if (contextOffset == LEADERBOARD_UNIT_OFFSET || contextOffset == LEADERBOARD_UNIT_OFFSET + 1) {
            return LEADERBOARD_UNIT_OFFSET;
        }

        return contextOffset & ~ClassicRegion.DWORD_OFFSET_MASK;
    }

    /**
     * Creates the record lists of the trigger players, every list empty.
     *
     * @return the empty record lists of players {@code 0}-{@code 7}
     */
    private static @NotNull RunningTrigger @NotNull [] @NotNull [] createEmptyPlayerRecords() {
        RunningTrigger[][] records = new RunningTrigger[PlayerTable.PLAYABLE_PLAYER_SLOTS][];

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            records[player] = new RunningTrigger[0];
        }

        return records;
    }

    /**
     * Checks a switch index.
     *
     * @param index the switch index
     * @return the switch index
     * @throws IllegalArgumentException if the index is not {@code 0} through {@code 255}
     */
    private static int requireSwitch(int index) {
        if (index < 0 || index >= SWITCH_COUNT) {
            throw Exceptions.newIllegalArgumentException("Switch {0} is not a switch 0 through 255.", index);
        }

        return index;
    }

    /**
     * Checks a zero-based location index.
     *
     * @param location the location index
     * @return the location index
     * @throws IllegalArgumentException if the index is not {@code 0} through {@code 254}
     */
    private static int requireLocation(int location) {
        if (location < 0 || location >= LOCATION_COUNT) {
            throw Exceptions.newIllegalArgumentException("Location {0} is not a location 0 through 254.", location);
        }

        return location;
    }

    /**
     * Checks that a player owns triggers, as the players the trigger walk visits.
     *
     * @param player the player index
     * @throws IllegalArgumentException if the player is not {@code 0} through {@code 7}
     */
    private static void requireTriggerPlayer(int player) {
        if (player < 0 || player >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            throw Exceptions.newIllegalArgumentException("Player {0} owns no triggers.", player);
        }
    }

    /**
     * Checks a number modifier.
     *
     * @param modifier the modifier
     * @throws IllegalArgumentException if the modifier is not set, add or subtract
     */
    private static void requireNumericModifier(int modifier) {
        if (!TriggerModifiers.isNumericModifier(modifier)) {
            throw Exceptions.newIllegalArgumentException("Modifier {0} is not set, add or subtract.", modifier);
        }
    }

    /**
     * Reads a byte of the trigger globals at an offset from the pending result bytes: the results, the waiting bytes, the wait DWORDs, the
     * initially active bytes, the current-record pointer, eight unused bytes and the fallback record ({@link #fallbackRecord}).
     *
     * @param offset the unsigned byte offset from the pending result bytes
     * @return the unsigned byte
     * @throws UnsupportedOperationException if the byte is the current-record pointer or lies past the fallback record
     */
    private int readGlobalByte(long offset) {
        if (offset < GLOBAL_WAITING) {
            return (int) (this.playerEvaluationFlags >>> (offset * Byte.SIZE)) & BYTE_MASK;
        }

        if (offset < GLOBAL_WAIT_TIMERS) {
            return this.waitingPlayers[(int) (offset - GLOBAL_WAITING)] & BYTE_MASK;
        }

        if (offset < GLOBAL_INITIALLY_ACTIVE) {
            int index = (int) (offset - GLOBAL_WAIT_TIMERS);

            return this.waitTimers[index / Integer.BYTES] >>> (index % Integer.BYTES * Byte.SIZE) & BYTE_MASK;
        }

        if (offset < GLOBAL_CURRENT_RECORD) {
            return this.initiallyActive[(int) (offset - GLOBAL_INITIALLY_ACTIVE)] & BYTE_MASK;
        }

        return this.fallbackRecord[requireFallbackIndex(offset)] & BYTE_MASK;
    }

    /**
     * Writes a byte of the trigger globals at the offset {@link #readGlobalByte} reads.
     *
     * @param offset the unsigned byte offset from the pending result bytes
     * @param value  the byte value in the low eight bits
     * @throws UnsupportedOperationException if the byte is the current-record pointer or lies past the fallback record
     */
    private void writeGlobalByte(long offset, int value) {
        if (offset < GLOBAL_WAITING) {
            long shift = offset * Byte.SIZE;

            this.playerEvaluationFlags = this.playerEvaluationFlags & ~(PLAYER_RESULT_MASK << shift) | (long) (value & BYTE_MASK) << shift;
        } else if (offset < GLOBAL_WAIT_TIMERS) {
            this.waitingPlayers[(int) (offset - GLOBAL_WAITING)] = (byte) value;
        } else if (offset < GLOBAL_INITIALLY_ACTIVE) {
            int index = (int) (offset - GLOBAL_WAIT_TIMERS);

            this.waitTimers[index / Integer.BYTES] = LittleEndianUtils.replaceByte(this.waitTimers[index / Integer.BYTES],
                                                                                   index % Integer.BYTES * Byte.SIZE, value);
        } else if (offset < GLOBAL_CURRENT_RECORD) {
            this.initiallyActive[(int) (offset - GLOBAL_INITIALLY_ACTIVE)] = (byte) value;
        } else {
            int index = requireFallbackIndex(offset);

            if (this.fallbackRecord[index] != (byte) value) {
                this.fallbackRecord[index] = (byte) value;
                // Decodes the fallback record again on its next run.
                this.emptyTrigger = null;
            }
        }
    }

    /**
     * Returns the index in {@link #fallbackRecord} of a trigger-globals offset past the initially active bytes.
     *
     * @param offset the unsigned byte offset from the pending result bytes
     * @return the index
     * @throws UnsupportedOperationException if the byte is the current-record pointer or lies past the fallback record
     */
    private static int requireFallbackIndex(long offset) {
        if (offset < GLOBAL_PADDING || offset >= GLOBAL_END) {
            throw Exceptions.newUnsupportedOperationException("Trigger globals offset 0x{0} lies outside the represented player bytes.",
                                                              Long.toHexString(offset));
        }

        return (int) (offset - GLOBAL_PADDING);
    }

    /**
     * Returns the fallback record the classic trigger walk runs for a link outside every region, decoded from {@link #fallbackRecord}.
     *
     * @return the fallback record
     */
    @NotNull RunningTrigger requireFallbackTrigger() {
        if (this.emptyTrigger == null) {
            byte[] bytes = Arrays.copyOfRange(this.fallbackRecord, (int) (GLOBAL_FALLBACK_RECORD - GLOBAL_PADDING), this.fallbackRecord.length);

            this.emptyTrigger = TriggerRecordCodec.createExecution(TriggerRecordCodec.decodeDefinition(bytes, 0), bytes, 0);
        }

        return this.emptyTrigger;
    }

    /**
     * Returns the trigger-globals offset of a player's element in a per-player array, with the player index read unsigned.
     *
     * @param base   the offset of the array
     * @param player the player index, any value
     * @param size   the element size
     * @return the unsigned byte offset
     */
    private static long getGlobalOffset(long base, int player, int size) {
        return base + Integer.toUnsignedLong(player) * size;
    }


    final byte @NotNull [] waitingPlayers;
    final int @NotNull [] waitTimers;
    int cycleCountdown;
    int periodicCountdown;
    int clockCountdown;
    int elapsedClockTicks;
    int countdownTimer;
    int countdownPaused;
    /**
     * The seconds the countdown text shows, or {@link #COUNTDOWN_HIDDEN}; presentation state outside the hash.
     */
    int countdownDisplay;
    int victoryCountdown;
    private @Nullable GameSpeed frameTiming;

    long playerEvaluationFlags;
    /**
     * The eight unused bytes and the fallback record after the current-record pointer of the trigger globals.
     */
    final byte @NotNull [] fallbackRecord;
    int preparationFlag;
    /**
     * Whether the active-player walk of a trigger pass runs; outside the hash, as it is {@code false} between updates.
     */
    boolean passRunning;
    boolean evaluationStateInitialized;
    boolean evaluationControlsInitialized;
    int evaluationStopFlags;
    int currentPlayer;
    int @Nullable [] teamLeaders;
    boolean currentPlayerInitialized;
    @NotNull RunningTrigger @Nullable [] @NotNull [] playerRecords;
    int currentRecordPlayer;
    int currentRecordIndex;
    boolean currentRecordInitialized;
    int phaseGate;
    int triggersEnabledWhileGated;
    /**
     * The Pause Game or Unpause Game a plugin requested for the next trigger step; outside the hash, as the trigger step takes it.
     */
    int pauseRequest;
    boolean replayObservation;
    int instructionLimit;

    @Nullable Game world;
    @NotNull Player @Nullable [] players;
    @Nullable UnitAccounting accounting;
    UnitAccounting.@Nullable TriggerSnapshot accountingSnapshot;
    @Nullable PlayerRelations relations;
    @Nullable ComputerPlayerPhase computerPhase;
    int localPlayer;
    /**
     * The initially active bytes of players {@code 0}-{@code 7}, nonzero for a player the active-player walk visited when the
     * classic trigger players were bound.
     */
    private final byte @NotNull [] initiallyActive;
    private int decodedSessionState;
    int multiplayerSession;
    int resultFlags;
    int outcomeDisplay;

    final int @NotNull [] switches;
    private final int @NotNull [] missionObjectives;
    /**
     * The mission objectives of players {@code 8}-{@code 11}, reachable only through the EUD emulation or a written current player.
     */
    private final int @NotNull [] upperMissionObjectives;
    byte @Nullable [] unitProperties;
    final @NotNull TriggerLocation @NotNull [] locations;
    int preparedLocation;
    /**
     * The gap bytes of the count cache after the location BYTE and before the leaderboard pairs, as DWORDs: the first one
     * holds only the two bytes above the mode and location BYTEs. Only a count-cache index past the all-units counts
     * writes them.
     */
    final int @NotNull [] countCacheGap;
    @Nullable ScrTriggerSystem.ExtendedDeathsOperations extendedDeathsOperations;
    /**
     * The observer of trigger work, or {@code null}.
     */
    @Nullable ExecutionCounter executionCounter;
    int extendedDeathsMode;
    /**
     * The classic trigger lists of the EUD emulation, built with the address space.
     */
    @Nullable ClassicTriggerMirror triggerMirror;
    /**
     * The zeroed record that a link without a node or room for a record executes.
     */
    @Nullable RunningTrigger emptyTrigger;
    /**
     * The execution and memory view that {@link RegionRecordRunnerUtils#runRegionRecord} reuses for every record it runs in place.
     */
    @Nullable RunningTrigger regionRecord;
    RegionRecordRunnerUtils.@Nullable RunningRecordView regionRecordView;
    /**
     * Whether an EUD access the address space does not serve ended this game.
     */
    boolean eudFailed;
    /**
     * The classic DWORD address of the trigger access that ended the game, {@code 0} until then.
     */
    int eudFailureAddress;
    /**
     * The definitions last decoded from records that run in place, reused while their bytes are unchanged.
     */
    final @NotNull DecodedRecordCache decodedRecords;
    /**
     * Receives the next link of a walk when it is read through the address space.
     */
    final int @NotNull [] linkScratch;
    /**
     * The records each player's walk ran last, by position in the walk ({@link ClassicTriggerWalkUtils#evaluateMirroredPlayer}).
     */
    final @NotNull WalkCache @NotNull [] walkCaches;
    int nextScenarioString;

    int leaderboardType;
    int leaderboardLocation;
    int leaderboardUnitType;
    int leaderboardGoal;
    int leaderboardString;
    final int @NotNull [] leaderboardPlayerIndices;
    final int @NotNull [] leaderboardValues;
    int leaderboardComputers;

    boolean centerViewPending;
    final @NotNull TriggerEvents events;

    private final @NotNull TriggerRegistry<ScrTriggerCondition> conditionRegistry;
    private final @NotNull TriggerRegistry<ScrTriggerAction> actionRegistry;
    final @NotNull TriggerConditionOperands conditionOperands;

    /**
     * Creates a {@link TriggerContext} instance with the wait and countdown values of a new game.
     */
    public TriggerContext() {
        this.waitingPlayers    = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.fallbackRecord    = new byte[(int) (GLOBAL_END - GLOBAL_PADDING)];
        this.waitTimers        = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.cycleCountdown    = 1;
        this.periodicCountdown = PERIODIC_COUNTDOWN_RELOAD;
        this.clockCountdown    = CLOCK_COUNTDOWN_RELOAD;
        this.elapsedClockTicks = 0;
        this.countdownTimer    = 0;
        this.countdownPaused   = 0;
        this.countdownDisplay  = COUNTDOWN_HIDDEN;
        this.victoryCountdown  = 1;
        this.frameTiming       = null;

        this.playerEvaluationFlags         = 0;
        this.preparationFlag               = 0;
        this.passRunning                   = false;
        this.evaluationStateInitialized    = false;
        this.evaluationControlsInitialized = false;
        this.evaluationStopFlags           = 0;
        this.currentPlayer                 = 0;
        this.teamLeaders                   = null;
        this.currentPlayerInitialized      = false;
        this.playerRecords                 = null;
        this.currentRecordPlayer           = 0;
        this.currentRecordIndex            = 0;
        this.currentRecordInitialized      = false;
        this.phaseGate                     = 0;
        this.triggersEnabledWhileGated     = 0;
        this.pauseRequest                  = NO_PAUSE_REQUEST;
        this.replayObservation             = false;
        this.instructionLimit              = 0;

        this.world               = null;
        this.players             = null;
        this.accounting          = null;
        this.accountingSnapshot  = null;
        this.relations           = null;
        this.computerPhase       = null;
        this.localPlayer         = 0;
        this.initiallyActive     = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.decodedSessionState = 0;
        this.multiplayerSession  = 0;
        this.resultFlags         = 0;
        this.outcomeDisplay      = 0;

        this.switches               = new int[SWITCH_COUNT / Integer.SIZE];
        this.missionObjectives      = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.upperMissionObjectives = new int[PlayerTable.PLAYER_SLOTS - PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.unitProperties         = null;
        this.locations              = new TriggerLocation[LOCATION_COUNT];

        for (int index = 0; index < LOCATION_COUNT; index++) {
            this.locations[index] = new TriggerLocation();
        }

        this.preparedLocation         = 0;
        this.countCacheGap            = new int[LEADERBOARD_PAIRS_DWORD];
        this.extendedDeathsOperations = null;
        this.extendedDeathsMode       = 0;
        this.triggerMirror            = null;
        this.emptyTrigger             = null;
        this.regionRecord             = null;
        this.regionRecordView         = null;
        this.eudFailed                = false;
        this.eudFailureAddress        = 0;
        this.decodedRecords           = new DecodedRecordCache();
        this.linkScratch              = new int[1];
        this.walkCaches               = ClassicTriggerWalkUtils.createWalkCaches();
        this.nextScenarioString       = 0;

        this.leaderboardType     = 0;
        this.leaderboardLocation = 0;
        this.leaderboardUnitType = 0;
        this.leaderboardGoal     = 0;
        this.leaderboardString   = 0;
        // The cached pairs stay zero until the first leaderboard calculation.
        this.leaderboardPlayerIndices = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.leaderboardValues        = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        // The leaderboards include computer players.
        this.leaderboardComputers = 1;

        this.centerViewPending = false;
        this.events            = new TriggerEvents();

        this.conditionRegistry = TriggerConditions.createRegistry();
        this.actionRegistry    = TriggerActionsUtils.createRegistry();
        this.conditionOperands = new TriggerConditionOperands();
    }

    /**
     * @return the game whose triggers run
     * @throws UnsupportedOperationException if the evaluation runs without the world state
     */
    @Override
    public org.fogofwarlabs.sc4j.engine.game.@NotNull Game getGame() {
        Game current = this.world;

        if (current == null) {
            throw Exceptions.newUnsupportedOperationException("The trigger evaluation runs without the world state.");
        }

        return current.getEngineGame();
    }

    /**
     * @return the player whose triggers run, or {@code null} outside the triggers of a player: the player of the current-player
     * DWORD while the walk of the evaluation has set it to one of the eight playable players
     * @throws UnsupportedOperationException if the evaluation runs without the world state
     */
    @Override
    public org.fogofwarlabs.sc4j.engine.player.@Nullable Player getCurrentPlayer() {
        int player = this.currentPlayer;

        if (!this.currentPlayerInitialized || player < 0 || player >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return null;
        }

        return this.getGame().getPlayer(player);
    }

    /**
     * @return the frozen registry of the handler of every condition type
     */
    public @NotNull TriggerRegistry<ScrTriggerCondition> getConditionRegistry() {
        return this.conditionRegistry;
    }

    /**
     * @return the frozen registry of the handler of every action type
     */
    public @NotNull TriggerRegistry<ScrTriggerAction> getActionRegistry() {
        return this.actionRegistry;
    }

    /**
     * @return the victory countdown WORD: Victory, Defeat and Draw set it to {@code 1} while the game is paused by a
     * trigger and to {@code 45} otherwise, and the non-replay branch of the trigger update counts it down
     */
    public int getVictoryCountdown() {
        return this.victoryCountdown;
    }

    /**
     * @return the game-paused DWORD as the last trigger update left it: Pause Game sets it and Unpause Game clears it; the
     * frame runner passes it back as the phase gate of the next update
     */
    public int getPhaseGate() {
        return this.phaseGate;
    }

    /**
     * @return the trigger pause BYTE as the last trigger update left it: nonzero while a trigger paused the game, so
     * trigger evaluation continues under the phase gate
     */
    public int getTriggersEnabledWhileGated() {
        return this.triggersEnabledWhileGated;
    }

    /**
     * @return the seconds that the trigger countdown text shows, as the once-per-second update last set it, or
     * {@link #COUNTDOWN_HIDDEN} after it hid the text; the text keeps its value between those updates, so a Set
     * Countdown Timer shows at the next second
     */
    public int getCountdownDisplay() {
        return this.countdownDisplay;
    }

    /**
     * @return the map string index last stored by Set Next Scenario, or {@code 0} when none was set
     */
    public int getNextScenarioString() {
        return this.nextScenarioString;
    }

    /**
     * @return the presentation output of the last trigger update for the local player
     */
    public @NotNull TriggerEvents getEvents() {
        return this.events;
    }

    /**
     * @param index the zero-based location index
     * @return the location record
     */
    public @NotNull TriggerLocation getLocation(int index) {
        return this.locations[index];
    }

    /**
     * @param index the switch index from {@code 0} through {@code 255}
     * @return {@code true} if the switch bit is set, {@code false} otherwise
     */
    public boolean isSwitchSet(int index) {
        return (this.switches[index >>> SWITCH_DWORD_SHIFT] & 1 << (index & SWITCH_BIT_MASK)) != 0;
    }

    /**
     * @param player the trigger-owner index
     * @return the map string index stored by Set Mission Objectives
     */
    public int getMissionObjectivesStringIndex(int player) {
        return player < this.missionObjectives.length ? this.missionObjectives[player]
                                                      : this.upperMissionObjectives[player - this.missionObjectives.length];
    }

    /**
     * @return the elapsed game-clock DWORD, advanced every sixteen trigger calls
     */
    public int getElapsedClockTicks() {
        return this.elapsedClockTicks;
    }

    /**
     * @return the leaderboard type BYTE: the action id of the last leaderboard action ({@code 17..21}, {@code 33..37},
     * {@code 40} Greed), {@code 0} for none
     */
    public int getLeaderboardType() {
        return this.leaderboardType & BYTE_MASK;
    }

    /**
     * @return the leaderboard label map string WORD ({@code 0} for none)
     */
    public int getLeaderboardString() {
        return this.leaderboardString & WORD_MASK;
    }

    /**
     * @param position the leaderboard cache position in {@code 0..7}
     * @return the cached player index, or {@code -1} for an unused position
     */
    public int getLeaderboardPlayerIndex(int position) {
        return this.leaderboardPlayerIndices[position];
    }

    /**
     * @param position the leaderboard cache position in {@code 0..7}
     * @return the retained leaderboard value
     */
    public int getLeaderboardValue(int position) {
        return this.leaderboardValues[position];
    }

    /**
     * Sets the address space that extended Deaths and SetDeaths operands of an EUD-mode map act on.
     * <p>
     * While {@link #getExtendedDeathsMode()} is nonzero, Deaths and SetDeaths operands that are not player groups or unit
     * groups go through this address space. The caller must bind the provider to the same world's state. A {@code null}
     * provider selects {@link ClassicAddressSpace#create(TriggerContext, Game)} over the world's counters and message
     * lines.
     *
     * @param extendedDeathsOperations the address operations, or {@code null} for the represented classic address space
     */
    public void setExtendedDeathsOperations(@Nullable ScrTriggerSystem.ExtendedDeathsOperations extendedDeathsOperations) {
        this.extendedDeathsOperations = extendedDeathsOperations;
    }

    /**
     * Returns the classic address space that the triggers of this context read and write in EUD mode. Its regions hold what the
     * triggers wrote (the string section payload, for one); a space made with {@link ClassicAddressSpace#create(TriggerContext, Game)}
     * builds those regions anew from the map.
     *
     * @return the live address space, or {@code null} before the first EUD access or when another provider was set
     */
    public @Nullable ClassicAddressSpace getClassicAddressSpace() {
        return this.extendedDeathsOperations instanceof ClassicAddressSpace space ? space : null;
    }

    /**
     * Observes the trigger work of the evaluation: each walked trigger record and each executed action. It is called on the
     * simulation thread in execution order and must not change game state.
     */
    public interface ExecutionCounter {
        /**
         * A trigger record is walked: its conditions are tested, or its running actions resume.
         */
        void record();

        /**
         * An action runs.
         */
        void action();
    }

    /**
     * Sets the observer of trigger work.
     *
     * @param executionCounter the observer, or {@code null}
     */
    public void setExecutionCounter(@Nullable ExecutionCounter executionCounter) {
        this.executionCounter = executionCounter;
    }

    /**
     * @return the EUD mode bits of the map triggers, set by the {@code TRIG} scan:
     * {@link #EXTENDED_DEATHS_CONDITION_MODE} for an extended Deaths condition,
     * {@link #EXTENDED_SET_DEATHS_MODE} for an extended SetDeaths action
     */
    public int getExtendedDeathsMode() {
        return this.extendedDeathsMode;
    }

    /**
     * Creates the classic trigger lists once, appending a node for every running trigger of every player of the
     * active-player walk ({@link #getNextActivePlayer}) in walk and list order, and returns their regions for the EUD
     * address space.
     *
     * @param firstNodeBase the classic address the allocator gives the first node
     * @return the list-head region followed by the node regions
     * @throws UnsupportedOperationException if the trigger players are not bound
     */
    public @NotNull List<ClassicRegion> getClassicTriggerRegions(int firstNodeBase) {
        if (this.triggerMirror == null) {
            if (this.playerRecords == null) {
                throw Exceptions.newUnsupportedOperationException("The classic trigger lists require bound trigger players.");
            }

            ClassicTriggerMirror mirror = new ClassicTriggerMirror(firstNodeBase);

            for (int player = this.getNextActivePlayer(WALK_START); player < PlayerTable.PLAYABLE_PLAYER_SLOTS;
                 player = this.getNextActivePlayer(player)) {
                for (RunningTrigger record : this.playerRecords[player]) {
                    mirror.append(player, record);
                }
            }

            this.triggerMirror = mirror;
        }

        return this.triggerMirror.getRegions();
    }

    /**
     * Sets the shared script pool that Run AI Script starts records in.
     *
     * @param computerPhase the world's computer-player phase, or {@code null} to keep the unsupported boundary
     */
    public void setComputerPhase(@Nullable ComputerPlayerPhase computerPhase) {
        this.computerPhase = computerPhase;
    }

    /**
     * Sets the world whose state the trigger routines of this context act on, as every trigger update sets it, with the replay observation of
     * the world ({@link Game#isReplayPlayback()}).
     *
     * @param world the world
     */
    public void setWorld(@NotNull Game world) {
        this.world             = world;
        this.replayObservation = world.isReplayPlayback();
    }

    /**
     * @return the local player DWORD of trigger evaluation, whose triggers present text, sounds, transmissions and view
     * changes ({@code 8} when no player is local)
     */
    public int getLocalPlayer() {
        return this.localPlayer;
    }

    /**
     * @return the multiplayer session BYTE ({@code 0} in a single-player game)
     */
    public int getMultiplayerSession() {
        return this.multiplayerSession & BYTE_MASK;
    }

    /**
     * @return the game's frame timing (the frame durations), or {@code null} when not supplied
     */
    public @Nullable GameSpeed getFrameTiming() {
        return this.frameTiming;
    }

    /**
     * Sets the game's frame timing, whose duration table an EUD region serves.
     *
     * @param frameTiming the timing the frame runner reads
     */
    public void setFrameTiming(@NotNull GameSpeed frameTiming) {
        this.frameTiming = frameTiming;
    }

    /**
     * @return the trigger-cycle WORD
     */
    public int getCycleCountdown() {
        return this.cycleCountdown;
    }

    /**
     * Sets the trigger-cycle WORD, as an EUD write of its classic address does: with {@code 0} the next frame's
     * decrement finds it zero and evaluates the triggers, which the "hyper trigger" maps rely on.
     *
     * @param value the WORD in the low sixteen bits
     */
    public void setCycleCountdown(int value) {
        this.cycleCountdown = value & WORD_MASK;
    }

    /**
     * @return the periodic callback WORD
     */
    public int getPeriodicCountdown() {
        return this.periodicCountdown;
    }

    /**
     * @return the game-clock WORD
     */
    public int getClockCountdown() {
        return this.clockCountdown;
    }

    /**
     * Returns a player's wait DWORD from the trigger globals ({@link #readGlobalByte}).
     *
     * @param player the player index, any value
     * @return the retained wait DWORD
     * @throws UnsupportedOperationException if the DWORD lies outside the represented trigger globals
     */
    public int getWaitTimer(int player) {
        long offset = getGlobalOffset(GLOBAL_WAIT_TIMERS, player, Integer.BYTES);
        int value = 0;

        for (int index = 0; index < Integer.BYTES; index++) {
            value |= this.readGlobalByte(offset + index) << (index * Byte.SIZE);
        }

        return value;
    }

    /**
     * Tests a player's waiting byte in the trigger globals ({@link #readGlobalByte}).
     *
     * @param player the player index, any value
     * @return {@code true} if the player's wait BYTE is nonzero, {@code false} otherwise
     * @throws UnsupportedOperationException if the byte lies outside the represented trigger globals
     */
    public boolean isWaiting(int player) {
        return this.readGlobalByte(getGlobalOffset(GLOBAL_WAITING, player, Byte.BYTES)) != 0;
    }

    /**
     * Sets the leader slots of a team network game, which the active-player walk ({@link #getNextActivePlayer}) visits in
     * force order instead of slots {@code 0}-{@code 7}. A slot can lead only one force: a repeated slot leads only the
     * first force that names it.
     *
     * @param leaders the leader slot of forces {@code 1}-{@code 4}, a value outside {@code 0}-{@code 7} for none, in a game
     *                with a multiplayer session and the teams option; {@code null} in every other game
     */
    public void setTeamLeaders(int @Nullable [] leaders) {
        if (leaders == null) {
            this.teamLeaders = null;

            return;
        }

        int[] distinctLeaders = leaders.clone();

        for (int force = 1; force < distinctLeaders.length; force++) {
            for (int earlier = 0; earlier < force; earlier++) {
                if (distinctLeaders[force] == distinctLeaders[earlier]) {
                    distinctLeaders[force] = NO_TEAM_LEADER;
                }
            }
        }

        this.teamLeaders = distinctLeaders;
    }

    /**
     * Returns the next player of the active-player walk, which every trigger player loop, player group and the alliance and
     * messaging dialogs share: in a team network game ({@link #setTeamLeaders}) the walk visits the force leaders in force
     * order, otherwise slots {@code 0}-{@code 7}; either way it skips the players {@link Player#isActive} rejects at the
     * time of the step.
     *
     * @param player the player the walk returned last, or {@link #WALK_START}
     * @return the next active player, or {@link PlayerTable#PLAYABLE_PLAYER_SLOTS} at the end of the walk
     * @throws IllegalStateException if the classic players have not been bound
     */
    public int getNextActivePlayer(int player) {
        Player[] players = this.players;
        int[] leaders = this.teamLeaders;

        if (players == null) {
            throw Exceptions.newIllegalStateException("Classic trigger players have not been initialized.");
        }

        if (leaders == null) {
            for (int next = player + 1; next < PlayerTable.PLAYABLE_PLAYER_SLOTS; next++) {
                if (players[next].isActive()) {
                    return next;
                }
            }

            return PlayerTable.PLAYABLE_PLAYER_SLOTS;
        }

        int force = 0;

        if (player != WALK_START) {
            while (force < leaders.length && leaders[force] != player) {
                force++;
            }

            force++;
        }

        for (; force < leaders.length; force++) {
            int leader = leaders[force];

            if (leader >= 0 && leader < PlayerTable.PLAYABLE_PLAYER_SLOTS && players[leader].isActive()) {
                return leader;
            }
        }

        return PlayerTable.PLAYABLE_PLAYER_SLOTS;
    }

    /**
     * Lists the players of one whole active-player walk ({@link #getNextActivePlayer}).
     *
     * @return the active player slots, in walk order
     * @throws IllegalStateException if the classic players have not been bound
     */
    public int @NotNull [] getActivePlayers() {
        int[] active = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        int count = 0;

        for (int player = this.getNextActivePlayer(WALK_START); player < PlayerTable.PLAYABLE_PLAYER_SLOTS;
             player = this.getNextActivePlayer(player)) {
            active[count++] = player;
        }

        return Arrays.copyOf(active, count);
    }

    /**
     * Returns the pending result byte of a player from the trigger globals ({@link #readGlobalByte}): players {@code 8..15} read the waiting
     * bytes that follow the results.
     *
     * @param player the trigger-owner index, any value
     * @return the pending trigger result byte of the player
     * @throws UnsupportedOperationException if the byte lies outside the represented trigger globals
     */
    public int getPlayerResult(int player) {
        return this.readGlobalByte(Integer.toUnsignedLong(player));
    }

    /**
     * @param player the trigger-owner index
     * @return {@code true} if the active-player walk visited the player when classic trigger players were bound, {@code false} otherwise
     */
    public boolean isInitiallyActive(int player) {
        return this.initiallyActive[player] != 0;
    }

    /**
     * @return {@code true} if the stop byte is set or the session state is {@link #STOPPING_SESSION_STATE}, so the record
     * and action loops stop, {@code false} otherwise
     */
    boolean isEvaluationStopped() {
        return (this.evaluationStopFlags & BYTE_MASK) != 0 || this.decodedSessionState == STOPPING_SESSION_STATE;
    }

    /**
     * @return {@code true} if a trigger accessed an EUD address the address space does not serve, which stops trigger
     * evaluation and ends the game after the frame, {@code false} otherwise
     */
    public boolean hasEudFailure() {
        return this.eudFailed;
    }

    /**
     * @return the classic DWORD address of the trigger access that ended the game, or {@code 0} without an EUD failure
     */
    public int getEudFailureAddress() {
        return this.eudFailureAddress;
    }

    /**
     * Sets the pending trigger result byte of a player at the byte {@link #getPlayerResult} reads.
     *
     * @param player the trigger-owner index, any value
     * @param value  the result byte in the low eight bits
     * @throws UnsupportedOperationException if the byte lies outside the represented trigger globals
     */
    void setPlayerResult(int player, int value) {
        this.writeGlobalByte(Integer.toUnsignedLong(player), value);
    }

    /**
     * Ends the game for a player as the Victory, Defeat and Draw actions do: Defeat and Draw fail against a pending victory or secured
     * result; otherwise a human or computer player receives the pending result and the victory countdown reloads ({@code 1} while triggers run
     * during the phase gate). The trigger step of the frame then applies the pending results ({@link ScrTriggerSystem}).
     *
     * @param player the trigger-owner index
     * @param result {@link TriggerPlayerResults#VICTORY}, {@link TriggerPlayerResults#DEFEAT} or {@link TriggerPlayerResults#DRAW}
     * @return {@code true} if the game ended for the player, {@code false} if a Defeat or Draw met a victory or secured result
     * @throws IllegalArgumentException      if the result is not victory, defeat or draw
     * @throws IllegalStateException         if players have not been bound
     * @throws UnsupportedOperationException if the player's record or result byte is not represented
     */
    public boolean endGame(int player, int result) {
        if (result != TriggerPlayerResults.VICTORY && result != TriggerPlayerResults.DEFEAT && result != TriggerPlayerResults.DRAW) {
            throw Exceptions.newIllegalArgumentException("A game ends in victory, defeat or a draw, not in the result {0}.", result);
        }

        int pending = this.getPlayerResult(player);

        if (result != TriggerPlayerResults.VICTORY && (pending == TriggerPlayerResults.VICTORY || pending == TriggerPlayerResults.SECURED)) {
            return false;
        }

        int playerType = this.getPlayerType(player);

        if (playerType == PlayerTypes.HUMAN || playerType == PlayerTypes.COMPUTER) {
            this.setPlayerResult(player, result);
        }

        this.victoryCountdown = this.triggersEnabledWhileGated != 0 ? 1 : VICTORY_COUNTDOWN_RELOAD;

        return true;
    }

    /**
     * Returns the deaths of a unit type for a player as the Deaths condition reads them for one player: the loss counter, or in EUD mode
     * ({@link #getExtendedDeathsMode()}) the DWORD of the EUD address space the player and unit type address (the loss counters for the unit
     * groups).
     *
     * @param player   the player index, any value in EUD mode, below {@code 12} otherwise
     * @param unitType the unit type or unit group (its low WORD is used)
     * @return the deaths, {@code 0} for a unit operand above the unit groups outside EUD mode
     * @throws UnsupportedOperationException if the evaluation controls are missing, or the EUD address space does not represent the DWORD
     */
    public int getDeaths(int player, int unitType) {
        return ExtendedDeathsUtils.readPlayerDeaths(this, player, unitType & WORD_MASK, -1);
    }

    /**
     * Sets the deaths of a unit type for a player as Set Deaths sets them for one player: outside EUD mode the loss counter of a player below
     * {@code 8}, in EUD mode ({@link #getExtendedDeathsMode()}) the loss counter for the unit groups and else the DWORD of the EUD address space.
     *
     * @param player   the player index
     * @param unitType the unit type or unit group (its low WORD is used)
     * @param modifier {@link TriggerModifiers#SET_TO}, {@link TriggerModifiers#ADD} or {@link TriggerModifiers#SUBTRACT}
     * @param amount   the amount; a subtraction clamps at zero
     * @throws IllegalArgumentException      if the modifier is not set, add or subtract
     * @throws UnsupportedOperationException if the evaluation controls are missing, or the EUD address space does not represent the DWORD
     */
    public void setDeaths(int player, int unitType, int modifier, int amount) {
        requireNumericModifier(modifier);

        ExtendedDeathsUtils.writePlayerDeaths(this, player, unitType & WORD_MASK, modifier, amount, -1, false);
    }

    /**
     * Sets or clears a switch, as Set Switch does with the set and clear states.
     *
     * @param index the switch index from {@code 0} through {@code 255}
     * @param set   {@code true} to set the switch, {@code false} to clear it
     * @throws IllegalArgumentException if the index is not a switch
     */
    public void setSwitch(int index, boolean set) {
        int bit = 1 << (requireSwitch(index) & SWITCH_BIT_MASK);

        if (set) {
            this.switches[index >>> SWITCH_DWORD_SHIFT] |= bit;
        } else {
            this.switches[index >>> SWITCH_DWORD_SHIFT] &= ~bit;
        }
    }

    /**
     * Toggles a switch, as Set Switch does with the toggle state.
     *
     * @param index the switch index from {@code 0} through {@code 255}
     * @throws IllegalArgumentException if the index is not a switch
     */
    public void toggleSwitch(int index) {
        this.switches[requireSwitch(index) >>> SWITCH_DWORD_SHIFT] ^= 1 << (index & SWITCH_BIT_MASK);
    }

    /**
     * @return the countdown timer in seconds, which the Countdown Timer condition compares
     */
    public int getCountdownTimer() {
        return this.countdownTimer;
    }

    /**
     * Sets the countdown timer as Set Countdown Timer does: set, add or subtract (another modifier keeps the value), then the value is clamped to
     * {@code 0} through {@code 359999} seconds.
     *
     * @param modifier {@link TriggerModifiers#SET_TO}, {@link TriggerModifiers#ADD} or {@link TriggerModifiers#SUBTRACT}
     * @param seconds  the seconds
     */
    public void modifyCountdownTimer(int modifier, int seconds) {
        int value = this.countdownTimer;

        switch (modifier) {
            case TriggerModifiers.SET_TO -> value = seconds;
            case TriggerModifiers.ADD -> value += seconds;
            case TriggerModifiers.SUBTRACT -> value -= seconds;
            default -> {
            }
        }

        this.countdownTimer = Math.min(Math.max(value, 0), COUNTDOWN_MAXIMUM);
    }

    /**
     * @return {@code true} if Pause Timer paused the countdown timer, {@code false} otherwise
     */
    public boolean isCountdownPaused() {
        return this.countdownPaused != 0;
    }

    /**
     * Pauses or resumes the countdown timer, as Pause Timer and Unpause Timer do.
     *
     * @param paused {@code true} to pause the countdown, {@code false} to resume it
     */
    public void setCountdownPaused(boolean paused) {
        this.countdownPaused = paused ? 1 : 0;
    }

    /**
     * Sets the mission objectives of a player as Set Mission Objectives does: the string index is stored for players {@code 0} through
     * {@code 11}; for a later player index it is written to the player's mission objectives DWORD of the game data through the EUD address
     * space. The objectives show the map string, which may be a registered string ({@link MapStringSection#registerString}).
     *
     * @param player      the player index
     * @param stringIndex the map string index
     * @throws UnsupportedOperationException if the address space does not represent the DWORD of a player past {@code 11}
     */
    public void setMissionObjectives(int player, int stringIndex) {
        if (Integer.compareUnsigned(player, PlayerTable.PLAYER_SLOTS) < 0) {
            this.setMissionObjectivesStringIndex(player, stringIndex);
        } else {
            ExtendedDeathsUtils.writeGameDataDword(this, MISSION_OBJECTIVES_OFFSET + Integer.BYTES * player, stringIndex);
        }
    }

    /**
     * Shows a leaderboard as the leaderboard actions without a location do: the leaderboard calculation is scheduled for the next frame and the
     * type, unit operand, goal and label are stored. The label is a map string, which may be a registered string
     * ({@link MapStringSection#registerString}).
     * <p>
     * The type is {@link TriggerActionIds#LEADERBOARD_CONTROL}, {@link TriggerActionIds#LEADERBOARD_RESOURCES},
     * {@link TriggerActionIds#LEADERBOARD_KILLS}, {@link TriggerActionIds#LEADERBOARD_POINTS} or one of their goal forms. The operand is the unit
     * type of the control and kills forms, the resource type of the resources forms or the score type of the points forms.
     *
     * @param type        the leaderboard action type
     * @param unitType    the unit, resource or score operand (its low WORD is used)
     * @param goal        the goal of the goal forms
     * @param stringIndex the map string index of the label
     * @throws IllegalArgumentException if the type is not a leaderboard type without a location
     */
    public void showLeaderboard(int type, int unitType, int goal, int stringIndex) {
        switch (type) {
            case TriggerActionIds.LEADERBOARD_CONTROL, TriggerActionIds.LEADERBOARD_RESOURCES, TriggerActionIds.LEADERBOARD_KILLS,
                 TriggerActionIds.LEADERBOARD_POINTS, TriggerActionIds.LEADERBOARD_GOAL_CONTROL, TriggerActionIds.LEADERBOARD_GOAL_RESOURCES,
                 TriggerActionIds.LEADERBOARD_GOAL_KILLS, TriggerActionIds.LEADERBOARD_GOAL_POINTS -> {
                this.storeLeaderboard(type, unitType, goal, stringIndex);
            }
            default -> throw Exceptions.newIllegalArgumentException("Leaderboard type {0} is not a leaderboard without a location.", type);
        }
    }

    /**
     * Shows a leaderboard of the units at a location, as Leaderboard Control At Location and its goal form do with a location.
     *
     * @param type        {@link TriggerActionIds#LEADERBOARD_CONTROL_AT_LOCATION} or {@link TriggerActionIds#LEADERBOARD_GOAL_CONTROL_AT_LOCATION}
     * @param location    the zero-based location index
     * @param unitType    the unit type (its low WORD is used)
     * @param goal        the goal of the goal form
     * @param stringIndex the map string index of the label
     * @throws IllegalArgumentException if the type is not a leaderboard type with a location or the location is not a location index
     */
    public void showLeaderboardAt(int type, int location, int unitType, int goal, int stringIndex) {
        if (type != TriggerActionIds.LEADERBOARD_CONTROL_AT_LOCATION && type != TriggerActionIds.LEADERBOARD_GOAL_CONTROL_AT_LOCATION) {
            throw Exceptions.newIllegalArgumentException("Leaderboard type {0} is not a leaderboard at a location.", type);
        }

        this.leaderboardLocation = requireLocation(location);

        this.storeLeaderboard(type, unitType, goal, stringIndex);
    }

    /**
     * Shows the Greed leaderboard of the minerals and gas of every player, as Leaderboard Greed does.
     *
     * @param goal the resources goal
     */
    public void showGreedLeaderboard(int goal) {
        this.periodicCountdown   = 1;
        this.leaderboardType     = TriggerActionIds.LEADERBOARD_GREED;
        this.leaderboardGoal     = goal;
        this.leaderboardUnitType = TriggerScoreActionsUtils.RESOURCE_BOTH;
    }

    /**
     * @return {@code true} if the leaderboards include computer players, {@code false} otherwise
     */
    public boolean areLeaderboardComputersIncluded() {
        return this.leaderboardComputers != 0;
    }

    /**
     * Includes or excludes computer players in the leaderboards, as Leaderboard Computer Players does with the set and clear states.
     *
     * @param included {@code true} to include computer players, {@code false} to exclude them
     */
    public void setLeaderboardComputersIncluded(boolean included) {
        this.leaderboardComputers = included ? 1 : 0;
    }

    /**
     * Pings the minimap of the local player at a position, as Minimap Ping does at the center of its location; for any other player the call
     * does nothing.
     *
     * @param player    the player whose triggers ping
     * @param positionX the horizontal map position in pixels
     * @param positionY the vertical map position in pixels
     */
    public void pingMinimap(int player, int positionX, int positionY) {
        if (player == this.localPlayer) {
            this.events.add(TriggerEvents.MINIMAP_PING, positionX, positionY, 0);
        }
    }

    /**
     * Plays a sound file of the map archive for the local player, as Play WAV plays the sound its map string names: nothing while replaying or
     * for any other player. The path is cut at {@link TriggerEvents#SOUND_PATH_LIMIT} bytes.
     *
     * @param player the player whose triggers play the sound
     * @param path   the sound file path in the map archive, as the map strings hold it
     */
    public void playSound(int player, byte @NotNull [] path) {
        this.playSound(player, 0, path);
    }

    /**
     * Plays a sound file for the local player ({@link #playSound(int, byte[])}).
     *
     * @param player      the player whose triggers play the sound
     * @param stringIndex the map string index that named the path, {@code 0} for a path without one
     * @param path        the sound file path
     */
    void playSound(int player, int stringIndex, byte @NotNull [] path) {
        if (this.replayObservation || player != this.localPlayer) {
            return;
        }

        this.events.addSound(stringIndex, path.length > TriggerEvents.SOUND_PATH_LIMIT ? Arrays.copyOf(path, TriggerEvents.SOUND_PATH_LIMIT) : path);
    }

    /**
     * Shows text for the local player as Display Text Message shows its map string ({@link MessageLines#writeTriggerText(byte[], boolean, int)}):
     * when the subtitle option is on or the display is forced; for any other player the call does nothing.
     *
     * @param player the player whose triggers show the text
     * @param text   the text
     * @param forced whether the display is forced, as the always-display flag of the action
     */
    public void displayText(int player, @NotNull String text, boolean forced) {
        if (player != this.localPlayer) {
            return;
        }

        this.events.add(TriggerEvents.TEXT, 0, forced ? 1 : 0, 0);

        if (this.world != null) {
            this.world.getInterfaceLink().getMessageLines().writeTriggerText(text.getBytes(StandardCharsets.UTF_8), forced, 0);
        }
    }

    /**
     * Shows the talking portrait of a unit type for the local player, as Talking Portrait does: the portrait talks for the duration like the
     * portrait of a Transmission, and its timer ends the local player's trigger wait as a Transmission's does ({@link #completeTransmission()});
     * for any other player the call does nothing.
     *
     * @param player       the player whose triggers show the portrait
     * @param unitType     the unit type whose portrait talks (its low WORD is used)
     * @param milliseconds the talking duration in milliseconds
     */
    public void showTalkingPortrait(int player, int unitType, int milliseconds) {
        if (player == this.localPlayer) {
            this.events.add(TriggerEvents.TRANSMISSION, unitType & WORD_MASK, TriggerEvents.NO_POSITION, TriggerEvents.NO_POSITION, milliseconds);
        }
    }

    /**
     * Mutes or unmutes unit speech as Mute Unit Speech and Unmute Unit Speech do: the event reaches the consumer, which applies the local-player
     * test, and the local interface follows at once when the player is the local player.
     *
     * @param player the player whose triggers mute or unmute
     * @param muted  {@code true} to mute unit speech, {@code false} to unmute it
     */
    public void setUnitSpeechMuted(int player, boolean muted) {
        this.events.add(muted ? TriggerEvents.UNIT_SPEECH_MUTE : TriggerEvents.UNIT_SPEECH_UNMUTE, player, 0, 0);

        if (this.world != null && player == this.localPlayer) {
            this.world.getInterfaceLink().getLocalInterface().setUnitSpeechMuted(muted ? 1 : 0);
        }
    }

    /**
     * Sets the alliance of a player toward a target as Set Alliance Status does for one selected player, then the player's units drop targets
     * that became allied and, for the local player, the local relations and selection circles follow.
     *
     * @param player   the player whose alliance row changes, {@code 0} through {@code 7}
     * @param target   the target player, {@code 0} through {@code 12}; the player itself is never changed
     * @param relation {@link PlayerRelations#ENEMY}, {@link PlayerRelations#ALLIED} or {@link PlayerRelations#ALLIED_VICTORY}
     * @throws IllegalArgumentException      if the player, the target or the relation is outside its values
     * @throws UnsupportedOperationException if the context has no world
     */
    public void setAllianceStatus(int player, int target, int relation) {
        requireTriggerPlayer(player);

        if (target < 0 || target > PlayerTable.PLAYER_SLOTS) {
            throw Exceptions.newIllegalArgumentException("Alliance target {0} is not a player 0 through 12.", target);
        }

        if (relation != PlayerRelations.ENEMY && relation != PlayerRelations.ALLIED && relation != PlayerRelations.ALLIED_VICTORY) {
            throw Exceptions.newIllegalArgumentException("Alliance relation {0} is not enemy, allied or allied victory.", relation);
        }

        Game current = ScrTriggerSystem.requireWorld(this, "Set Alliance Status");

        TriggerActionsUtils.setAllianceStatus(this, player, target, relation);

        this.applyAllianceChange(current, player);
    }

    /**
     * Applies the effects of changed alliances of a player: the player's units drop targets that became allied, and for the local player the
     * local relation table and the selection circles follow.
     *
     * @param world  the world
     * @param player the player whose alliance row changed
     */
    void applyAllianceChange(@NotNull Game world, int player) {
        world.getTriggerUnitActions().clearAlliedOrderTargets(player);

        if (player == this.localPlayer) {
            world.getSelectionImages().updateLocalRelations();
        }
    }

    /**
     * Starts a computer script for a player as Run AI Script does: scripts outside the list human players may start run only for computer
     * players, and the rescue script also for neutral players.
     *
     * @param player the player that runs the script
     * @param script the four-character script identifier, its first character in the low byte
     * @return {@code true} if the script started, {@code false} if the player may not run it
     * @throws UnsupportedOperationException if the script runs without the shared computer script pool
     */
    public boolean runAiScript(int player, int script) {
        return TriggerActionsUtils.runScript(this, player, script, null);
    }

    /**
     * Starts a computer script for a player at a location as Run AI Script At Location does, with the owner gate of {@link #runAiScript}.
     *
     * @param player   the player that runs the script
     * @param script   the four-character script identifier, its first character in the low byte
     * @param location the zero-based location index whose bounds the script receives
     * @return {@code true} if the script started, {@code false} if the player may not run it
     * @throws IllegalArgumentException      if the location is not a location index
     * @throws UnsupportedOperationException if the script runs without the shared computer script pool
     */
    public boolean runAiScriptAt(int player, int script, int location) {
        TriggerLocation area = this.locations[requireLocation(location)];

        return TriggerActionsUtils.runScript(this, player, script, new int[]{area.getLeft(), area.getTop(), area.getRight(), area.getBottom()});
    }

    /**
     * Returns a score of a player as the Score and Highest Score conditions read it. The score types {@code 0} through {@code 7} are total,
     * units, buildings, units and buildings, kills, razings, kills and razings and custom; a later type reads {@code 0}.
     *
     * @param player    the player index
     * @param scoreType the score type
     * @return the score
     * @throws UnsupportedOperationException if the evaluation controls are missing
     */
    public int getScore(int player, int scoreType) {
        return TriggerConditions.readPlayerCounter(this, player, scoreType, TriggerConditions.COUNTER_SCORE);
    }

    /**
     * Sets a score of a player as Set Score does for one player: the total score splits the amount into quarters and the combined types into
     * halves over their scores; a subtraction clamps at zero.
     *
     * @param player    the player, {@code 0} through {@code 7}
     * @param scoreType the score type ({@link #getScore}); a later type changes nothing
     * @param modifier  {@link TriggerModifiers#SET_TO}, {@link TriggerModifiers#ADD} or {@link TriggerModifiers#SUBTRACT}
     * @param amount    the amount
     * @throws IllegalArgumentException      if the player is not {@code 0} through {@code 7} or the modifier is not set, add or subtract
     * @throws UnsupportedOperationException if the evaluation controls are missing
     */
    public void setScore(int player, int scoreType, int modifier, int amount) {
        requireTriggerPlayer(player);
        requireNumericModifier(modifier);

        TriggerScoreActionsUtils.modifyScore(this.requireAccounting(), player, scoreType, modifier, amount);
    }

    /**
     * Returns the kills of a unit type by a player as the Kill and Most Kills conditions read them.
     *
     * @param player   the player index
     * @param unitType the unit type or unit group (its low WORD is used)
     * @return the kills, {@code 0} for a unit operand above the unit groups
     * @throws UnsupportedOperationException if the evaluation controls are missing
     */
    public int getKills(int player, int unitType) {
        return TriggerConditions.readPlayerCounter(this, player, unitType & WORD_MASK, TriggerConditions.COUNTER_KILLS);
    }

    /**
     * Counts the units of a unit type a player commands, as the Command and Command the Most conditions count them.
     * <p>
     * The counts come from the count cache. During the walk of a trigger pass the cache holds the pass's counts, as for the walk's own
     * conditions; outside the walk the cache is reset first, so the count is the current one. Every reset copies all counts again, and the
     * cache is part of the state hash.
     *
     * @param player    the player index; {@code 12} and above count {@code 0}
     * @param unitType  the unit type or unit group (its low WORD is used); above the unit groups counts {@code 0}
     * @param completed {@code true} to count completed units only (every comparison but At Most), {@code false} to count all units
     * @return the unit count
     * @throws UnsupportedOperationException if the evaluation controls are missing
     */
    public int countCommandedUnits(int player, int unitType, boolean completed) {
        this.resetCountCacheOutsidePass();

        TriggerConditions.prepareCommandCounts(this);

        return TriggerConditions.countPlayerUnits(this, player, unitType, completed);
    }

    /**
     * Counts the units of a unit type a player has at a location, as the Bring and Command the Most At conditions count them: the units of the
     * location rectangle that pass its elevation filter and are neither hallucinations nor subunits; completed units also count their loaded
     * units, hangar units and carried powerup.
     * <p>
     * The counts come from the count cache under the rule of {@link #countCommandedUnits}; the cache holds one location at a time, so a count at
     * another location fills it again.
     *
     * @param player    the player index; {@code 12} and above count {@code 0}
     * @param unitType  the unit type or unit group (its low WORD is used); above the unit groups counts {@code 0}
     * @param location  the zero-based location index
     * @param completed {@code true} to count completed units only (every comparison but At Most), {@code false} to count all units
     * @return the unit count
     * @throws IllegalArgumentException      if the location is not a location index
     * @throws UnsupportedOperationException if the context has no world or the world has no unit spatial index
     */
    public int countUnitsAt(int player, int unitType, int location, boolean completed) {
        requireLocation(location);

        this.resetCountCacheOutsidePass();

        TriggerConditions.prepareLocationCounts(this, location);

        return TriggerConditions.countPlayerUnits(this, player, unitType, completed);
    }

    /**
     * Counts the opponents of a player as the Opponents condition counts them for one player: none for a player from {@code 8} and for
     * rescuable and neutral players; otherwise every active player that is not rescuable or neutral and lacks mutual allied victory with the
     * player or one of its mutual allies.
     *
     * @param player the player index
     * @return the number of opponents
     */
    public int countOpponents(int player) {
        return TriggerConditions.countOpponents(this, player);
    }

    /**
     * Resets the count cache mode outside the walk of a trigger pass, so the next count fills the cache again.
     */
    private void resetCountCacheOutsidePass() {
        if (!this.passRunning) {
            this.preparationFlag = 0;
        }
    }

    /**
     * Stores the shown leaderboard and schedules its calculation for the next frame.
     *
     * @param type        the leaderboard action type
     * @param unitType    the unit, resource or score operand
     * @param goal        the goal
     * @param stringIndex the map string index of the label
     */
    void storeLeaderboard(int type, int unitType, int goal, int stringIndex) {
        this.periodicCountdown   = 1;
        this.leaderboardType     = type;
        this.leaderboardUnitType = unitType & WORD_MASK;
        this.leaderboardGoal     = goal;
        this.leaderboardString   = stringIndex;
    }

    /**
     * @param player the trigger-owner index
     * @return the actual number of retained executable records
     * @throws IllegalStateException if players have not been bound
     */
    public int getPlayerRecordCount(int player) {
        if (this.playerRecords == null) {
            throw Exceptions.newIllegalStateException("Classic trigger players have not been initialized.");
        }

        return this.playerRecords[player].length;
    }

    /**
     * Returns the player-type byte of a player's record: an observer slot id names the observer record of its index ({@code 12..15}), any
     * other id the record of that index. Records {@code 0..7} are the trigger players and {@code 8..11} the world's other players; observer
     * records read as {@link PlayerTypes#NONE}.
     *
     * @param player the trigger-owner index, any value
     * @return the player-type byte
     * @throws IllegalStateException         if players have not been bound
     * @throws UnsupportedOperationException if the record lies past the sixteen player records, or is one of records
     *                                       {@code 8..11} while the evaluation runs without a world
     */
    int getPlayerType(int player) {
        int record = PlayerTable.isObserverSlot(player) ? player - PlayerTable.FIRST_OBSERVER_SLOT + PlayerTable.PLAYER_SLOTS : player;

        if (Integer.compareUnsigned(record, PlayerTable.PLAYABLE_PLAYER_SLOTS) < 0) {
            return this.requirePlayer(record).getType() & BYTE_MASK;
        }

        if (Integer.compareUnsigned(record, PlayerTable.PLAYER_SLOTS) < 0) {
            Player other = ScrTriggerSystem.requireWorld(this, "A player record above player 7").getPlayerTable().getPlayer(record);

            return other == null ? PlayerTypes.NONE : other.getType() & BYTE_MASK;
        }

        if (Integer.compareUnsigned(record, PlayerTable.PLAYER_SLOTS + PlayerTable.OBSERVER_SLOTS) < 0) {
            return PlayerTypes.NONE;
        }

        throw Exceptions.newUnsupportedOperationException("The player record of current player 0x{0} lies past the sixteen player records.",
                                                          String.format("%08X", player));
    }

    /**
     * Returns a bound trigger player ({@link #initializeClassicPlayerRecords}), as every evaluation has them.
     *
     * @param player the trigger-owner index
     * @return the player
     * @throws IllegalStateException if players have not been bound
     */
    @NotNull Player requirePlayer(int player) {
        Player[] players = this.players;

        if (players == null) {
            throw Exceptions.newIllegalStateException("Classic trigger players have not been initialized.");
        }

        return players[player];
    }

    /**
     * @return the accounting of the evaluation controls ({@link #initializeEvaluation}), as every evaluation has them
     * @throws UnsupportedOperationException if the evaluation controls have not been supplied
     */
    @NotNull UnitAccounting requireAccounting() {
        UnitAccounting accounting = this.accounting;

        if (accounting == null) {
            throw Exceptions.newUnsupportedOperationException("Trigger evaluation requires its evaluation controls.");
        }

        return accounting;
    }

    /**
     * @return the player relations of the evaluation controls ({@link #initializeEvaluation}), as every evaluation has them
     * @throws UnsupportedOperationException if the evaluation controls have not been supplied
     */
    @NotNull PlayerRelations requireRelations() {
        PlayerRelations relations = this.relations;

        if (relations == null) {
            throw Exceptions.newUnsupportedOperationException("Trigger evaluation requires its evaluation controls.");
        }

        return relations;
    }

    /**
     * @return the current-player DWORD written by the established evaluation iterator
     * @throws IllegalStateException if evaluation has not written the current-player DWORD
     */
    public int getCurrentPlayerIndex() {
        if (!this.currentPlayerInitialized) {
            throw Exceptions.newIllegalStateException("The current trigger player has not been established.");
        }

        return this.currentPlayer;
    }

    /**
     * @return the number of running records of players {@code 0}-{@code 7}, the plain WORD count of the saved trigger part
     */
    public int getSavedRecordCount() {
        int count = 0;

        if (this.playerRecords != null) {
            for (RunningTrigger[] records : this.playerRecords) {
                count += records.length;
            }
        }

        return count;
    }

    /**
     * @return the unit property records of the map's {@code UPRP} section, which Create Unit with Properties reads and a
     * saved game keeps; zero when the map has none
     */
    public byte @NotNull [] getSavedUnitProperties() {
        byte[] properties = new byte[UNIT_PROPERTIES_SIZE];

        if (this.unitProperties != null) {
            System.arraycopy(this.unitProperties, 0, properties, 0, Math.min(this.unitProperties.length, UNIT_PROPERTIES_SIZE));
        }

        return properties;
    }

    /**
     * @param player the player index
     * @param index  the record index in evaluation order
     * @return a copy of the retained trigger record
     * @throws IllegalStateException if record storage has not been loaded
     */
    public byte @NotNull [] getPlayerRecord(int player, int index) {
        if (this.playerRecords == null) {
            throw Exceptions.newIllegalStateException("Trigger records have not been loaded.");
        }

        return TriggerRecordCodec.exportRecord(this.playerRecords[player][index]);
    }

    /**
     * @return the retained semantic accounting snapshot, or {@code null} before the first condition update
     */
    public @Nullable UnitAccounting.TriggerSnapshot getAccountingSnapshot() {
        return this.accountingSnapshot;
    }


    /**
     * Returns and clears the outcome screen that the non-replay branch of the trigger update made due in the last update:
     * when the countdown reaches zero it reloads {@code 45}; a local player's defeat shows the defeat screen and a victory
     * or draw the victory screen; an observing local player sees the victory screen once every initially active player
     * has a victory state.
     *
     * @return {@link #OUTCOME_DEFEAT_SCREEN} for the defeat screen, {@link #OUTCOME_VICTORY_SCREEN} for the victory
     * screen, or {@code 0} when none is due
     */
    public int consumeOutcomeDisplay() {
        int outcome = this.outcomeDisplay;

        this.outcomeDisplay = 0;

        return outcome;
    }

    /**
     * Applies the Pause command: when the game-paused DWORD is clear it becomes {@code 1}, so the next loop iteration runs commands and triggers but
     * no frame. The trigger pause BYTE is left to the Pause Game action.
     *
     * @return {@code true} if the game was running and is now paused, {@code false} otherwise
     */
    public boolean pauseGame() {
        if (this.phaseGate != 0) {
            return false;
        }

        this.phaseGate = 1;

        return true;
    }

    /**
     * Applies the Resume command by clearing the game-paused DWORD.
     *
     * @return {@code true} if the game was paused and now runs, {@code false} otherwise
     */
    public boolean resumeGame() {
        if (this.phaseGate == 0) {
            return false;
        }

        this.phaseGate = 0;

        return true;
    }

    /**
     * Requests Pause Game for the next trigger step, as a plugin does without a trigger record. The step runs the action's rule before its
     * triggers, after it advanced its clocks with the frame's paused state ({@link ScrTriggerSystem#update}): nothing in a multiplayer session or
     * while Pause Game already paused the game; otherwise the game is paused, the triggers keep running and the game pause event is raised, until
     * Unpause Game. A request replaces an earlier one of the same step; a step that the Pause command keeps from running drops it.
     */
    public void requestPauseGame() {
        this.pauseRequest = PAUSE_REQUEST;
    }

    /**
     * Requests Unpause Game for the next trigger step, as a plugin does without a trigger record. The step runs the action's rule before its
     * triggers ({@link ScrTriggerSystem#update}): nothing in a multiplayer session or while Pause Game did not pause the game; otherwise the trigger
     * pause and the game-paused DWORD clear and the game pause event is raised. A request replaces an earlier one of the same step.
     */
    public void requestUnpauseGame() {
        this.pauseRequest = UNPAUSE_REQUEST;
    }

    /**
     * Reports that the local Transmission portrait was hidden when its timer expired: outside a multiplayer session it
     * continues into the arrival callback, which ends the local player's trigger wait and sets the trigger cycle WORD to
     * {@code 1}. The portrait timer runs on wall-clock milliseconds, so the consumer of {@link TriggerEvents#TRANSMISSION}
     * reports it between simulation frames; it is not recorded in replays.
     */
    public void completeTransmission() {
        if (this.localPlayer >= 0 && this.localPlayer < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            this.completeTransmission(this.localPlayer);
        }
    }

    /**
     * Runs the Transmission arrival callback: ends the player's transmission wait outside a multiplayer session and sets
     * the trigger cycle WORD to {@code 1}.
     *
     * @param player the local player whose transmission wait ended
     * @throws UnsupportedOperationException if the player is not a trigger player
     */
    private void completeTransmission(int player) {
        if (player < 0 || player >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            throw Exceptions.newUnsupportedOperationException("Transmission arrival for local player {0} requires a trigger player.", player);
        }

        if ((this.multiplayerSession & BYTE_MASK) != 0) {
            return;
        }

        this.endWait(player);
    }

    /**
     * Applies the local Escape skip. Outside a multiplayer session and for a campaign mission WORD ({@code 0} in custom games)
     * below the unskippable missions, every running record of the local player with running actions and without disabled
     * wait skipping gets {@link TriggerFlags#EXECUTION_IGNORE_INTERFACE_ACTIONS}, which makes its Wait and Transmission
     * actions finish at once; for each such record the local wait ends, the trigger cycle WORD becomes {@code 1}, the
     * trigger-text message lines are cleared and a {@link TriggerEvents#TRANSMISSION_SKIP} event is added. It is a local
     * input, not recorded in replays.
     *
     * @param campaignMission the campaign mission WORD, {@code 0} outside campaigns
     * @throws UnsupportedOperationException if the local player is not a trigger player
     */
    public void skipTransmissions(int campaignMission) {
        if ((this.multiplayerSession & BYTE_MASK) != 0 || (campaignMission & WORD_MASK) >= UNSKIPPABLE_MISSION || this.playerRecords == null) {
            return;
        }

        if (this.localPlayer < 0 || this.localPlayer >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            throw Exceptions.newUnsupportedOperationException("Transmission skip for local player {0} requires a trigger player.", this.localPlayer);
        }

        for (RunningTrigger record : this.playerRecords[this.localPlayer]) {
            if ((record.executionFlags & TriggerFlags.EXECUTION_ACTIONS_RUNNING) == 0 ||
                (record.executionFlags & TriggerFlags.EXECUTION_WAIT_SKIPPING_DISABLED) != 0) {
                continue;
            }

            record.executionFlags |= TriggerFlags.EXECUTION_IGNORE_INTERFACE_ACTIONS;

            this.endWait(this.localPlayer);

            if (this.world != null) {
                this.world.getInterfaceLink().getMessageLines().clearTriggerText();
            }

            this.events.add(TriggerEvents.TRANSMISSION_SKIP, 0, 0, 0);
        }
    }

    /**
     * Reports that the local camera reached the target of a waiting Center View, as the scroll arrival callback: the local player's trigger wait ends
     * and the trigger cycle WORD becomes {@code 1}. Without a pending waiting Center View, the call leaves the state unchanged.
     */
    public void completeCenterView() {
        if (!this.centerViewPending) {
            return;
        }

        this.centerViewPending = false;

        if (this.localPlayer >= 0 && this.localPlayer < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            this.endWait(this.localPlayer);
        }
    }

    /**
     * Copies an {@code MRGN} payload into the location records: records of four DWORD bounds, a string WORD and an elevation-flag WORD. Only the
     * short ({@code 64} records) and the full ({@link #LOCATION_COUNT} records) section sizes are accepted.
     *
     * @param payload the complete section payload
     * @throws UnsupportedOperationException if the payload has neither the short nor the full section size
     */
    public void loadLocations(byte @NotNull [] payload) {
        if (payload.length != SHORT_LOCATION_SECTION_SIZE && payload.length != LOCATION_SECTION_SIZE) {
            throw Exceptions.newUnsupportedOperationException("An MRGN section of {0} bytes is neither short nor full.", payload.length);
        }

        for (int index = 0; index < payload.length / LOCATION_RECORD_SIZE; index++) {
            int record = index * LOCATION_RECORD_SIZE;
            TriggerLocation location = this.locations[index];

            location.setBounds(LittleEndianUtils.readInt32(payload, record + LOCATION_LEFT_OFFSET),
                               LittleEndianUtils.readInt32(payload, record + LOCATION_TOP_OFFSET),
                               LittleEndianUtils.readInt32(payload, record + LOCATION_RIGHT_OFFSET),
                               LittleEndianUtils.readInt32(payload, record + LOCATION_BOTTOM_OFFSET));
            location.setStringIndex(LittleEndianUtils.readUnsigned16(payload, record + LOCATION_STRING_OFFSET));
            location.setElevationFlags(LittleEndianUtils.readUnsigned16(payload, record + LOCATION_ELEVATION_OFFSET));
        }
    }

    /**
     * Loads the {@code UPRP} section: only a payload of exactly {@link #UNIT_PROPERTIES_SIZE} bytes is copied; other sizes
     * make the map invalid.
     *
     * @param payload the {@code UPRP} payload
     * @throws UnsupportedOperationException if the payload is not {@link #UNIT_PROPERTIES_SIZE} bytes
     */
    public void loadUnitProperties(byte @NotNull [] payload) {
        if (payload.length != UNIT_PROPERTIES_SIZE) {
            throw Exceptions.newUnsupportedOperationException("A UPRP section of {0} bytes does not have the unit property size.", payload.length);
        }

        this.unitProperties = payload.clone();
    }

    /**
     * Sets the mission objectives string index of a player.
     *
     * @param player      the trigger-owner index
     * @param stringIndex the map string index
     */
    void setMissionObjectivesStringIndex(int player, int stringIndex) {
        if (player < this.missionObjectives.length) {
            this.missionObjectives[player] = stringIndex;
        } else {
            this.upperMissionObjectives[player - this.missionObjectives.length] = stringIndex;
        }
    }

    /**
     * Reads a byte of the trigger state by its game-context offset, as the EUD game-data block does.
     *
     * @param contextOffset an offset accepted by {@link #hasGameDataOffset(int)}
     * @return the unsigned byte
     */
    public int readGameDataByte(int contextOffset) {
        int base = getGameDataFieldBase(contextOffset);

        return this.readGameDataField(base) >>> (contextOffset - base) * Byte.SIZE & BYTE_MASK;
    }

    /**
     * Writes a byte of the trigger state by its game-context offset, as an EUD write of the game-data block.
     *
     * @param contextOffset an offset accepted by {@link #hasGameDataOffset(int)}
     * @param value         the byte value
     */
    public void writeGameDataByte(int contextOffset, int value) {
        int base = getGameDataFieldBase(contextOffset);
        int shift = (contextOffset - base) * Byte.SIZE;

        this.writeGameDataField(base, LittleEndianUtils.replaceByte(this.readGameDataField(base), shift, value));
    }

    /**
     * Reads the trigger state field at a field offset.
     *
     * @param base the game-context offset of the field ({@link #getGameDataFieldBase})
     * @return the field value
     */
    private int readGameDataField(int base) {
        if (base >= LOCATIONS_OFFSET && base < COUNTDOWN_PAUSED_OFFSET) {
            TriggerLocation location = this.locations[(base - LOCATIONS_OFFSET) / LOCATION_RECORD_SIZE];

            return switch ((base - LOCATIONS_OFFSET) % LOCATION_RECORD_SIZE) {
                case LOCATION_LEFT_OFFSET -> location.getLeft();
                case LOCATION_TOP_OFFSET -> location.getTop();
                case LOCATION_RIGHT_OFFSET -> location.getRight();
                case LOCATION_BOTTOM_OFFSET -> location.getBottom();
                case LOCATION_STRING_OFFSET -> location.getStringIndex();
                default -> location.getElevationFlags();
            };
        }

        if (base >= SWITCHES_OFFSET && base < LOCATIONS_OFFSET) {
            return this.switches[(base - SWITCHES_OFFSET) / Integer.BYTES];
        }

        if (base >= MISSION_OBJECTIVES_OFFSET && base < COUNTDOWN_OFFSET) {
            return this.getMissionObjectivesStringIndex((base - MISSION_OBJECTIVES_OFFSET) / Integer.BYTES);
        }

        return switch (base) {
            case COUNTDOWN_OFFSET -> this.countdownTimer;
            case ELAPSED_OFFSET -> this.elapsedClockTicks;
            case LEADERBOARD_COMPUTERS_OFFSET -> this.leaderboardComputers;
            case LEADERBOARD_TYPE_OFFSET -> this.leaderboardType & BYTE_MASK;
            case LEADERBOARD_LOCATION_OFFSET -> this.leaderboardLocation & BYTE_MASK;
            case LEADERBOARD_UNIT_OFFSET -> this.leaderboardUnitType & WORD_MASK;
            case LEADERBOARD_GOAL_OFFSET -> this.leaderboardGoal;
            case LEADERBOARD_STRING_OFFSET -> this.leaderboardString;
            default -> this.countdownPaused;
        };
    }

    /**
     * Writes the trigger state field at a field offset.
     *
     * @param base  the game-context offset of the field ({@link #getGameDataFieldBase})
     * @param value the field value
     */
    private void writeGameDataField(int base, int value) {
        if (base >= LOCATIONS_OFFSET && base < COUNTDOWN_PAUSED_OFFSET) {
            TriggerLocation location = this.locations[(base - LOCATIONS_OFFSET) / LOCATION_RECORD_SIZE];

            switch ((base - LOCATIONS_OFFSET) % LOCATION_RECORD_SIZE) {
                case LOCATION_LEFT_OFFSET -> location.setLeft(value);
                case LOCATION_TOP_OFFSET -> location.setTop(value);
                case LOCATION_RIGHT_OFFSET -> location.setRight(value);
                case LOCATION_BOTTOM_OFFSET -> location.setBottom(value);
                case LOCATION_STRING_OFFSET -> location.setStringIndex(value);
                default -> location.setElevationFlags(value & WORD_MASK);
            }

            return;
        }

        if (base >= SWITCHES_OFFSET && base < LOCATIONS_OFFSET) {
            this.switches[(base - SWITCHES_OFFSET) / Integer.BYTES] = value;

            return;
        }

        if (base >= MISSION_OBJECTIVES_OFFSET && base < COUNTDOWN_OFFSET) {
            this.setMissionObjectivesStringIndex((base - MISSION_OBJECTIVES_OFFSET) / Integer.BYTES, value);

            return;
        }

        switch (base) {
            case COUNTDOWN_OFFSET -> this.countdownTimer = value;
            case ELAPSED_OFFSET -> this.elapsedClockTicks = value;
            case LEADERBOARD_COMPUTERS_OFFSET -> this.leaderboardComputers = value;
            case LEADERBOARD_TYPE_OFFSET -> this.leaderboardType = value & BYTE_MASK;
            case LEADERBOARD_LOCATION_OFFSET -> this.leaderboardLocation = value & BYTE_MASK;
            case LEADERBOARD_UNIT_OFFSET -> this.leaderboardUnitType = value & WORD_MASK;
            case LEADERBOARD_GOAL_OFFSET -> this.leaderboardGoal = value;
            case LEADERBOARD_STRING_OFFSET -> this.leaderboardString = value;
            default -> this.countdownPaused = value;
        }
    }

    /**
     * Applies the wait flag and timer writes of the Wait action to the trigger globals ({@link #writeGlobalByte}).
     *
     * @param player   the player index, any value
     * @param duration the unsigned wait DWORD
     * @throws UnsupportedOperationException if a byte lies outside the represented trigger globals
     */
    public void beginWait(int player, int duration) {
        long offset = getGlobalOffset(GLOBAL_WAIT_TIMERS, player, Integer.BYTES);

        this.writeGlobalByte(getGlobalOffset(GLOBAL_WAITING, player, Byte.BYTES), 1);

        for (int index = 0; index < Integer.BYTES; index++) {
            this.writeGlobalByte(offset + index, duration >>> (index * Byte.SIZE));
        }
    }

    /**
     * Ends a player's trigger wait: the player stops waiting, its wait timer clears and the trigger cycle WORD becomes
     * {@code 1}.
     *
     * @param player the trigger player whose wait ends
     */
    void endWait(int player) {
        this.waitingPlayers[player] = 0;
        this.waitTimers[player]     = 0;
        this.cycleCountdown         = 1;
    }

    /**
     * Binds the first eight authoritative players and establishes empty classic trigger lists. The reference array is copied; changes to each
     * player's state remain visible to evaluation. The players {@link #getActivePlayers} walks now are the initially active
     * players, so a team network game sets its leaders ({@link #setTeamLeaders}) first.
     *
     * @param players the world's players in trigger-owner order
     * @throws IllegalArgumentException if fewer than eight players are supplied or one of the first eight is {@code null}
     */
    public void initializeClassicPlayerRecords(Player @NotNull [] players) {
        if (players.length < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            throw Exceptions.newIllegalArgumentException("Classic trigger iteration requires eight players.");
        }

        Player[] selectedPlayers = Arrays.copyOf(players, PlayerTable.PLAYABLE_PLAYER_SLOTS);

        for (Player player : selectedPlayers) {
            if (player == null) {
                throw Exceptions.newIllegalArgumentException("Classic trigger iteration requires eight players.");
            }
        }

        this.players       = selectedPlayers;
        this.playerRecords = createEmptyPlayerRecords();

        Arrays.fill(this.initiallyActive, (byte) 0);

        for (int player : this.getActivePlayers()) {
            this.initiallyActive[player] = 1;
        }
    }

    /**
     * Discards a player's running triggers. Players without records are unaffected.
     *
     * @param player the leaving player index
     */
    public void clearPlayerRecords(int player) {
        if (this.playerRecords != null && player >= 0 && player < this.playerRecords.length) {
            this.playerRecords[player] = new RunningTrigger[0];
        }
    }

    /**
     * Reads the current-player DWORD as the EUD emulation serves it.
     *
     * @return the current-player DWORD, {@code 0} before the first evaluation
     */
    public int readCurrentPlayerDword() {
        return this.currentPlayer;
    }

    /**
     * Replaces the current-player DWORD, as an EUD write of its classic address does. The conditions and actions that
     * follow read it; the evaluation loop sets the next player from its own iterator and {@code 8} after the last one.
     *
     * @param value the new DWORD
     */
    public void writeCurrentPlayerDword(int value) {
        this.currentPlayer            = value;
        this.currentPlayerInitialized = true;
    }

    /**
     * Replaces the classic trigger lists with the records of a trigger resource. Complete records share an immutable definition and retain
     * independent player execution state.
     *
     * @param resource the actual installed trigger resource bytes
     * @throws UnsupportedOperationException if player state is absent
     */
    public void loadAllPlayersResource(byte @NotNull [] resource) {
        if (this.players == null) {
            throw Exceptions.newUnsupportedOperationException("Trigger record loading requires bound trigger players.");
        }

        this.playerRecords = createEmptyPlayerRecords();

        this.loadRecords(resource);
    }

    /**
     * Registers every complete {@link TriggerRecordCodec#RECORD_SIZE}-byte record of a trigger resource or map {@code TRIG}
     * section after the retained lists.
     * <p>
     * Each nonzero owner byte registers an independent copy in ascending selector order, so a record enabled for several
     * matching selectors is registered once per selector. The player selectors register for that player,
     * {@link TriggerPlayerSelectors#ALL_PLAYERS} for every active player and the force selectors for the active players
     * of their force; the other selectors register nothing. Active players follow the active-player iterator.
     *
     * @param records the record bytes
     * @throws UnsupportedOperationException if player state is absent
     */
    public void loadRecords(byte @NotNull [] records) {
        if (this.players == null || this.playerRecords == null) {
            throw Exceptions.newUnsupportedOperationException("Trigger record loading requires bound trigger players.");
        }

        for (int offset = 0; offset <= records.length - TriggerRecordCodec.RECORD_SIZE; offset += TriggerRecordCodec.RECORD_SIZE) {
            this.extendedDeathsMode |= TriggerRecordCodec.scanExtendedDeaths(records, offset);

            TriggerDefinition definition = null;

            for (int selector = 0; selector < TriggerPlayerSelectors.COUNT; selector++) {
                if (TriggerRecordCodec.readOwnerSelector(records, offset, selector) == 0) {
                    continue;
                }

                if (selector >= PlayerTable.PLAYABLE_PLAYER_SLOTS && selector != TriggerPlayerSelectors.ALL_PLAYERS &&
                    (selector < TriggerPlayerSelectors.FORCE_1 || selector > TriggerPlayerSelectors.FORCE_4)) {
                    continue;
                }

                for (int player : this.getActivePlayers()) {
                    if (selector < PlayerTable.PLAYABLE_PLAYER_SLOTS
                        ? player != selector
                        : selector != TriggerPlayerSelectors.ALL_PLAYERS &&
                          (this.players[player].getTeam() & BYTE_MASK) != selector - TriggerPlayersUtils.FORCE_BASE) {
                        continue;
                    }

                    int count = this.playerRecords[player].length;

                    this.playerRecords[player] = Arrays.copyOf(this.playerRecords[player], count + 1);

                    if (definition == null) {
                        definition = TriggerRecordCodec.decodeDefinition(records, offset);
                    }

                    this.playerRecords[player][count] = TriggerRecordCodec.createExecution(definition, records, offset);
                }
            }
        }
    }

    /**
     * Builds the trigger section of a saved game: the eight initially-active bytes, the eight WORD record counts of
     * players {@code 0}-{@code 7}, then every running record of players {@code 0}-{@code 7} in player and evaluation order
     * as its {@link TriggerRecordCodec#RECORD_SIZE} classic bytes (the definition with this execution's flags and cursor).
     *
     * @return the section bytes
     */
    public byte @NotNull [] writeSaveRecords() {
        ByteBuffer section = ByteBuffer.allocate(SAVED_RECORDS_HEADER_SIZE + this.getSavedRecordCount() * TriggerRecordCodec.RECORD_SIZE)
                                       .order(ByteOrder.LITTLE_ENDIAN);

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            section.put(player, this.initiallyActive[player]);
        }

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            section.putShort(SAVED_RECORD_COUNTS + player * Short.BYTES,
                             (short) (this.playerRecords == null ? 0 : this.playerRecords[player].length));
        }

        section.position(SAVED_RECORDS_HEADER_SIZE);

        if (this.playerRecords != null) {
            for (RunningTrigger[] records : this.playerRecords) {
                for (RunningTrigger record : records) {
                    section.put(TriggerRecordCodec.exportRecord(record));
                }
            }
        }

        return section.array();
    }

    /**
     * Replaces the running records with the trigger section of a saved game, each record a new execution of its own
     * decoded definition.
     *
     * @param section the section bytes ({@link #writeSaveRecords})
     * @throws IllegalStateException if the classic players have not been bound
     */
    public void readSaveRecords(byte @NotNull [] section) {
        ByteBuffer buffer = ByteBuffer.wrap(section).order(ByteOrder.LITTLE_ENDIAN);
        int offset = SAVED_RECORDS_HEADER_SIZE;

        if (this.players == null) {
            throw Exceptions.newIllegalStateException("Classic trigger players have not been initialized.");
        }

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            this.initiallyActive[player] = buffer.get(player);
        }

        this.playerRecords = new RunningTrigger[PlayerTable.PLAYABLE_PLAYER_SLOTS][];

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            int count = buffer.getShort(SAVED_RECORD_COUNTS + player * Short.BYTES) & WORD_MASK;

            this.playerRecords[player] = new RunningTrigger[count];

            for (int index = 0; index < count; index++) {
                this.extendedDeathsMode |= TriggerRecordCodec.scanExtendedDeaths(section, offset);
                this.playerRecords[player][index] = TriggerRecordCodec.createExecution(TriggerRecordCodec.decodeDefinition(section, offset),
                                                                                       section, offset);
                offset += TriggerRecordCodec.RECORD_SIZE;
            }
        }
    }

    /**
     * Replaces the unit property records with those of a saved game.
     *
     * @param properties the {@link #UNIT_PROPERTIES_SIZE} bytes of the records
     */
    public void restoreSavedUnitProperties(byte @NotNull [] properties) {
        this.unitProperties = properties.clone();
    }

    /**
     * Supplies an independent control snapshot consumed by nonempty classic trigger evaluation. Later changes to the input array do not alter this
     * context; call this method again to replace the snapshot.
     *
     * @param accounting the retained accounting source
     * @param relations  the retained original alliance BYTEs
     * @param controls   the stop byte, the decoded session state, the multiplayer session BYTE, the cheat result flags
     *                   and the local player DWORD, at the {@code CONTROL_*} indices
     * @throws IllegalArgumentException if the controls are not {@link #CONTROL_COUNT} values
     */
    public void initializeEvaluation(@NotNull UnitAccounting accounting, @NotNull PlayerRelations relations, int @NotNull [] controls) {
        if (controls.length != CONTROL_COUNT) {
            throw Exceptions.newIllegalArgumentException("Trigger evaluation requires {0} controls.", CONTROL_COUNT);
        }

        this.accounting                    = accounting;
        this.relations                     = relations;
        this.evaluationStopFlags           = controls[CONTROL_STOP_FLAGS];
        this.decodedSessionState           = controls[CONTROL_SESSION_STATE];
        this.multiplayerSession            = controls[CONTROL_MULTIPLAYER_SESSION];
        this.resultFlags                   = controls[CONTROL_RESULT_FLAGS];
        this.localPlayer                   = controls[CONTROL_LOCAL_PLAYER];
        this.evaluationControlsInitialized = true;
    }

    /**
     * Creates a {@link UnitAccounting.TriggerSnapshot} instance for the trigger count cache, whose all-units type counts
     * are followed by the count-cache state, the gap bytes and the leaderboard pairs of this context, as in the game
     * context.
     *
     * @return the created {@link UnitAccounting.TriggerSnapshot} instance
     */
    UnitAccounting.@NotNull TriggerSnapshot createAccountingSnapshot() {
        return new UnitAccounting.TriggerSnapshot(new CountCacheTail());
    }

    /**
     * Adds the retained scheduling, evaluation and map trigger state and the regions of the memory sources
     * ({@link ClassicAddressSpace#computeProvidedStateHash}) to a diagnostic hash.
     *
     * @param hash the preceding diagnostic hash
     * @return the hash including retained scheduling effects
     * @throws NullPointerException if the bound trigger players have no record lists
     */
    public int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + this.cycleCountdown;
        hash = HASH_MULTIPLIER * hash + this.periodicCountdown;
        hash = HASH_MULTIPLIER * hash + this.clockCountdown;
        hash = HASH_MULTIPLIER * hash + this.elapsedClockTicks;
        hash = HASH_MULTIPLIER * hash + this.countdownTimer;
        hash = HASH_MULTIPLIER * hash + this.countdownPaused;
        hash = HASH_MULTIPLIER * hash + this.leaderboardType;
        hash = HASH_MULTIPLIER * hash + this.leaderboardLocation;
        hash = HASH_MULTIPLIER * hash + this.leaderboardUnitType;
        hash = HASH_MULTIPLIER * hash + this.leaderboardGoal;
        hash = HASH_MULTIPLIER * hash + this.leaderboardString;

        for (int value : this.leaderboardPlayerIndices) {
            hash = HASH_MULTIPLIER * hash + value;
        }

        for (int value : this.leaderboardValues) {
            hash = HASH_MULTIPLIER * hash + value;
        }

        hash = HASH_MULTIPLIER * hash + (this.evaluationStateInitialized ? 1 : 0);

        if (this.evaluationStateInitialized) {
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.playerEvaluationFlags);
            hash = HASH_MULTIPLIER * hash + Arrays.hashCode(this.fallbackRecord);
            hash = HASH_MULTIPLIER * hash + this.preparationFlag;
        }

        hash = HASH_MULTIPLIER * hash + (this.currentPlayerInitialized ? 1 : 0);

        if (this.currentPlayerInitialized) {
            hash = HASH_MULTIPLIER * hash + this.currentPlayer;
        }

        hash = HASH_MULTIPLIER * hash + (this.players == null ? 0 : 1);

        if (this.players != null) {
            RunningTrigger[][] records = this.playerRecords;

            if (records == null) {
                throw Exceptions.newNullPointerException("Bound trigger players have no record lists.");
            }

            for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
                hash = HASH_MULTIPLIER * hash + (this.players[player].getType() & BYTE_MASK);
                hash = HASH_MULTIPLIER * hash + this.players[player].getVictoryState();
                hash = HASH_MULTIPLIER * hash + records[player].length;
            }
        }

        hash = HASH_MULTIPLIER * hash + (this.playerRecords == null ? 0 : 1);

        if (this.playerRecords != null) {
            for (RunningTrigger[] records : this.playerRecords) {
                for (RunningTrigger record : records) {
                    hash = record.computeStateHash(hash);
                }
            }
        }

        hash = HASH_MULTIPLIER * hash + (this.evaluationControlsInitialized ? 1 : 0);

        if (this.evaluationControlsInitialized) {
            hash = HASH_MULTIPLIER * hash + this.evaluationStopFlags;
            hash = HASH_MULTIPLIER * hash + this.decodedSessionState;
            hash = HASH_MULTIPLIER * hash + this.multiplayerSession;
            hash = HASH_MULTIPLIER * hash + this.resultFlags;
            hash = HASH_MULTIPLIER * hash + this.localPlayer;
        }

        hash = HASH_MULTIPLIER * hash + (this.accountingSnapshot == null ? 0 : 1);

        if (this.accountingSnapshot != null) {
            hash = this.accountingSnapshot.computeStateHash(hash);
        }

        hash = HASH_MULTIPLIER * hash + (this.currentRecordInitialized ? 1 : 0);

        if (this.currentRecordInitialized) {
            hash = HASH_MULTIPLIER * hash + this.currentRecordPlayer;
            hash = HASH_MULTIPLIER * hash + this.currentRecordIndex;
        }

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            hash = HASH_MULTIPLIER * hash + (this.waitingPlayers[player] & BYTE_MASK);
            hash = HASH_MULTIPLIER * hash + this.waitTimers[player];
        }

        for (int value : this.switches) {
            hash = HASH_MULTIPLIER * hash + value;
        }

        for (int value : this.missionObjectives) {
            hash = HASH_MULTIPLIER * hash + value;
        }

        for (TriggerLocation location : this.locations) {
            hash = HASH_MULTIPLIER * hash + location.getLeft();
            hash = HASH_MULTIPLIER * hash + location.getTop();
            hash = HASH_MULTIPLIER * hash + location.getRight();
            hash = HASH_MULTIPLIER * hash + location.getBottom();
        }

        for (TriggerLocation location : this.locations) {
            hash = HASH_MULTIPLIER * hash + location.getElevationFlags();
        }

        // The regions of the memory sources; nothing without them.
        if (this.extendedDeathsOperations instanceof ClassicAddressSpace space) {
            hash = space.computeProvidedStateHash(hash);
        }

        return hash;
    }
}
