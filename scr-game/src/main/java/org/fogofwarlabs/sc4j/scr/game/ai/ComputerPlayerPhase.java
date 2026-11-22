package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitComputerFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.player.*;
import org.fogofwarlabs.sc4j.scr.game.production.CostTests;
import org.fogofwarlabs.sc4j.scr.game.production.ScrRequirementSystem;
import org.fogofwarlabs.sc4j.scr.game.resource.ResourceAreas;
import org.fogofwarlabs.sc4j.scr.game.save.ComputerSaveMetadata;
import org.fogofwarlabs.sc4j.scr.game.save.SaveFileReader;
import org.fogofwarlabs.sc4j.scr.game.save.SaveReferences;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Owns the computer-player records and executes their scheduler.
 * <p>
 * The phase owns the per-player records, the region records, the script records, and the town, worker and building
 * pools.
 */
public final class ComputerPlayerPhase {
    /**
     * The size of the region count section of the computer-player part.
     */
    public static final int SAVED_REGION_COUNT_SIZE = Integer.BYTES;
    /**
     * The size of the military record section.
     */
    public static final int SAVED_MILITARY_SIZE = ComputerMilitary.SAVED_MILITARY_RECORDS_SIZE;
    /**
     * The size of one owner's region record section.
     */
    public static final int SAVED_REGION_RECORD_SIZE = ComputerRegions.SAVED_RECORD_SIZE;
    /**
     * The size of the guard record section.
     */
    public static final int SAVED_GUARDS_SIZE = ComputerMilitary.SAVED_GUARD_RECORDS_SIZE;
    /**
     * The size of the guard list head section.
     */
    public static final int SAVED_GUARD_HEADS_SIZE = ComputerMilitary.SAVED_LIST_VIEWS_SIZE;
    /**
     * The size of the worker record section: every record and the free head DWORD.
     */
    public static final int SAVED_WORKERS_SIZE = ComputerPlayerPhase.UNIT_RECORD_CAPACITY * ComputerUnitRecord.SAVED_WORKER_SIZE + Integer.BYTES;
    /**
     * The size of the building record section: every record and the free head DWORD.
     */
    public static final int SAVED_BUILDINGS_SIZE = ComputerPlayerPhase.UNIT_RECORD_CAPACITY * ComputerUnitRecord.SAVED_BUILDING_SIZE + Integer.BYTES;
    /**
     * The size of the town section: every town and the free head DWORD.
     */
    public static final int SAVED_TOWNS_SIZE = ComputerPlayerPhase.TOWN_CAPACITY * ComputerTown.SAVED_SIZE + Integer.BYTES;
    /**
     * The size of the town list head section.
     */
    public static final int SAVED_TOWN_HEADS_SIZE = ComputerMilitary.SAVED_LIST_VIEWS_SIZE;
    /**
     * The size of the player record section.
     */
    public static final int SAVED_PLAYERS_SIZE = PlayerTable.PLAYABLE_PLAYER_SLOTS * ComputerPlayer.SAVED_SIZE;
    /**
     * The size of the script record section.
     */
    public static final int SAVED_SCRIPTS_SIZE = ComputerScripts.SAVED_RECORDS_SIZE;
    /**
     * The size of the script list head section.
     */
    public static final int SAVED_SCRIPT_HEAD_SIZE = ComputerScripts.SAVED_HEAD_SIZE;

    /**
     * The region capacity of the regional records and of the regional strength cursor.
     */
    static final int REGION_CAPACITY = 2500;
    /**
     * The capacity of the town pool.
     */
    private static final int TOWN_CAPACITY = 100;
    /**
     * The capacity of the worker and of the building record pool.
     */
    private static final int UNIT_RECORD_CAPACITY = 1000;
    /**
     * The start of nearest-distance searches.
     */
    public static final int NO_DISTANCE = 99999999;
    /**
     * The start of the other nearest-candidate searches, one digit shorter than {@link #NO_DISTANCE}.
     */
    static final int NO_CANDIDATE_DISTANCE = 9999999;
    /**
     * The region index that names no region: the region record capacity.
     */
    static final int NO_REGION = REGION_CAPACITY;

    /**
     * The retained scheduler DWORDs, in the order of the constructor's {@code counters}.
     */
    private static final int COUNTER_PLAYER_UPDATE_COUNTDOWN = 0;
    private static final int COUNTER_UNIT_STATUS_REFRESH_COUNTDOWN = 1;
    private static final int COUNTER_UNIT_STATUS_REFRESH_CYCLE = 2;
    private static final int COUNTER_UNIT_STATUS_REFRESH_REQUESTED = 3;
    private static final int COUNTER_STRENGTH_PLAYER_CURSOR = 4;
    private static final int COUNTER_STRENGTH_REGION_CURSOR = 5;
    private static final int COUNTER_PLAYER_UPDATE_CURSOR = 6;
    private static final int COUNTER_TOWN_UPDATE_PLAYER_CURSOR = 7;
    private static final int COUNTER_COUNT = 8;

    /**
     * The frames between two unit status refresh steps.
     */
    private static final int UNIT_STATUS_REFRESH_PERIOD = 300;
    /**
     * The refresh steps after which the claimed-target status bits are cleared without a request.
     */
    private static final int UNIT_STATUS_REFRESH_CYCLES = 6;
    /**
     * The elapsed clock below which the player updates run at the early rate.
     */
    private static final int EARLY_PLAYER_UPDATE_CLOCK = 4500;
    /**
     * The scheduler steps between two player updates in the early game.
     */
    private static final int EARLY_PLAYER_UPDATE_DELAY = 7;
    /**
     * The scheduler steps between two player updates after the early game.
     */
    private static final int PLAYER_UPDATE_DELAY = 37;
    /**
     * The regional sweep steps of each scheduler update.
     */
    private static final int REGION_SWEEP_STEPS = 333;
    /**
     * The defense countdown each player update sets.
     */
    private static final int PLAYER_DEFENSE_COUNTDOWN = 9;
    /**
     * The elapsed clock from which a player update plans the region defense.
     */
    private static final int REGION_DEFENSE_START_CLOCK = 10;

    /**
     * The elapsed clock after which the strength comparison runs ({@code 359} ticks).
     */
    private static final int STRENGTH_COMPARISON_START_CLOCK = 0x167;
    /**
     * The allied strength above which a strength attack may start before {@link #STRENGTH_ATTACK_START_CLOCK}.
     */
    private static final int STRENGTH_ATTACK_ALLIED_STRENGTH = 5999;
    /**
     * The elapsed clock after which a strength attack may start with any allied strength.
     */
    private static final int STRENGTH_ATTACK_START_CLOCK = 0xA8B;

    /**
     * The race letters of the Melee script identifiers ({@code ZMCx}, {@code TMCu}, ...).
     */
    private static final char MELEE_SCRIPT_ZERG = 'Z';
    private static final char MELEE_SCRIPT_TERRAN = 'T';
    private static final char MELEE_SCRIPT_PROTOSS = 'P';
    /**
     * The second and third letters of the Melee script identifiers.
     */
    private static final char MELEE_SCRIPT_KIND_FIRST = 'M';
    private static final char MELEE_SCRIPT_KIND_SECOND = 'C';
    /**
     * The last letter of the Melee script identifiers with and without the expansion.
     */
    private static final char MELEE_SCRIPT_EXPANSION = 'x';
    private static final char MELEE_SCRIPT_ORIGINAL = 'u';

    /**
     * The worker orders, as bits relative to {@link ScrOrderIds#DRONE_START_BUILD}, of a worker about to construct a
     * building whose cost the player update reserves.
     */
    private static final long CONSTRUCTION_ORDER_STAGES = 0x200000000063L;
    /**
     * The last order stage {@link #CONSTRUCTION_ORDER_STAGES} covers.
     */
    private static final int CONSTRUCTION_ORDER_LAST_STAGE = 0x2D;
    /**
     * The worker orders, as bits relative to {@link ScrOrderIds#DRONE_START_BUILD}, of a worker a transfer does not take.
     */
    private static final long BUSY_WORKER_ORDER_STAGES = 0xC400200000000163L;

    /**
     * The choke flood depth of a town that is the owner's first town.
     */
    private static final int FIRST_TOWN_CHOKE_DEPTH = 5;
    /**
     * The choke flood depth of any further town.
     */
    private static final int EXPANSION_CHOKE_DEPTH = 2;
    /**
     * The elapsed clock after which a new Bunker gets empty Siege Tank and Ghost guards ({@code 60} ticks).
     */
    private static final int BUNKER_GUARD_START_CLOCK = 0x3C;
    /**
     * The Missile Turret count of a town from which a new Missile Turret gets an empty Ghost guard.
     */
    private static final int TURRET_GHOST_GUARD_COUNT = 4;

    /**
     * Truncates a value to a BYTE.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * Truncates a value to a WORD.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The multiplier of each state-hash step.
     */
    private static final int HASH_MULTIPLIER = 31;
    /**
     * The position of the last letter in a four-letter script identifier.
     */
    private static final int SCRIPT_LAST_LETTER_SHIFT = 3 * Byte.SIZE;
    /**
     * A strength attack needs the allied strength to exceed this multiple of the enemy strength.
     */
    private static final int STRENGTH_ATTACK_ENEMY_FACTOR = 2;
    /**
     * Halves a rectangle extent to its center.
     */
    private static final int TOWN_CENTER_DIVISOR = 2;

    /**
     * The region states without a town: {@link ComputerRegionStates#IDLE} and {@link ComputerRegionStates#ALLY_TOWN}.
     */
    private static final int NON_TOWN_STATE_MASK = BYTE_MASK & ~ComputerRegionStates.ALLY_TOWN;
    /**
     * The factor of the strength scale that gives both required strengths of a campaign depot region.
     */
    private static final int CAMPAIGN_DEPOT_STRENGTH_FACTOR = 6;
    /**
     * The required ground strength of a melee depot region.
     */
    private static final int DEPOT_REQUIRED_GROUND_STRENGTH = 0x5DC;
    /**
     * The required air strength of a melee depot region.
     */
    private static final int DEPOT_REQUIRED_AIR_STRENGTH = 0x3E8;
    /**
     * The region cooldown after a depot activation.
     */
    private static final int DEPOT_REGION_COOLDOWN = 0x2D;

    /**
     * Marks a deferred production failure. Reading the last error derives its non-negative failure code from the retained
     * producer and type.
     */
    private static final int UNREPRESENTED_PRODUCTION_ERROR = -1;

    /**
     * Clears a complete writable saved section without changing its position, limit or mark.
     */
    static void clearSaveRange(@NotNull ByteBuffer data, int size) {
        if (data.isReadOnly() || data.limit() < size) {
            return;
        }

        int wideSize = size - size % Long.BYTES;

        for (int offset = 0; offset < wideSize; offset += Long.BYTES) {
            data.putLong(offset, 0L);
        }

        for (int offset = wideSize; offset < size; offset++) {
            data.put(offset, (byte) 0);
        }
    }

    /**
     * Applies the loaded-unit hook: an SCV that is its town's reserved worker stops being reserved when it enters a
     * transport.
     *
     * @param unit     the loaded unit
     * @param baseType the base type of the unit ({@link GameData#getBaseType(Unit)})
     */
    public static void notifyLoaded(@NotNull Unit unit, int baseType) {
        ComputerUnitRecord record = unit.getComputerRecord();

        if (baseType == ScrUnitIds.TERRAN_SCV && record != null && record.town != null && record.town.reservedWorker == unit) {
            record.town.reservedWorker = null;
        }
    }

    /**
     * Applies the training completion hook: clears the request kind and town recorded for a build-queue slot of a
     * computer building.
     *
     * @param producer the producing unit
     * @param slot     the build queue slot
     */
    public static void notifyQueueSlotCleared(@NotNull Unit producer, int slot) {
        ComputerUnitRecord record = producer.getComputerRecord();

        if (record != null && record.getKind() == ComputerUnitRecord.KIND_BUILDING) {
            record.queueKinds[slot]   = 0;
            record.queueTargets[slot] = null;
        }
    }


    private final @NotNull Game game;

    private final @NotNull ComputerPlacement placement;
    private final @NotNull ComputerMilitary military;
    private @Nullable ComputerResources resources;
    private @Nullable UnitStrengthTable values;
    private @Nullable ComputerScripts scripts;

    private int regionCount;
    private @NotNull ComputerRegions regions;

    private final @Nullable ComputerPlayer @NotNull [] players;
    private final @NotNull ScrComputerController @NotNull [] controllers;
    private final @NotNull ComputerTown @NotNull [] towns;
    private final @Nullable ComputerTown @NotNull [] firstTowns;
    private final @NotNull ComputerUnitRecord @NotNull [] workerRecords;
    private final @NotNull ComputerUnitRecord @NotNull [] buildingRecords;
    private final @NotNull ComputerSaveMetadata saveMetadata;

    private int playerUpdateCountdown;
    private int unitStatusRefreshCountdown;
    private int unitStatusRefreshCycle;
    private int unitStatusRefreshRequested;
    private int regionalStrengthPlayerCursor;
    private int regionalStrengthRegionCursor;
    private int playerUpdateCursor;
    private int townUpdatePlayerCursor;

    private boolean hasComputerPlayers;
    private int instructionLimit;
    /**
     * The elapsed trigger clock ticks of the running update, which the per-player update of a controller reads.
     */
    private int updateElapsedClockTicks;
    /**
     * Set while a released town's units move to another town, suppressing nested releases.
     */
    private boolean mergingTown;
    /**
     * The frame in which the first Interceptor of that frame exposed its Carrier.
     */
    private int interceptorAlertFrame;
    /**
     * The forced argument stored by the Irradiate, Spawn Broodlings, Maelstrom and Psionic Storm searches; the Ensnare,
     * Plague and Stasis callbacks read the stale value.
     */
    private boolean spellSearchForced;

    private int lastError;
    private @Nullable Game failureGame;
    private @Nullable Unit failureProducer;
    private int failureType;

    private @Nullable ComputerTown firstFreeTown;
    private @Nullable ComputerUnitRecord firstFreeWorkerRecord;
    private @Nullable ComputerUnitRecord firstFreeBuildingRecord;

    /**
     * Creates a {@link ComputerPlayerPhase} instance with the retained scheduler and regional strength fields.
     * <p>
     * The counters are, in order: the player update countdown, the unit status refresh countdown, cycle and request,
     * the regional strength player and region cursors, the player update cursor and the town update player cursor.
     * Fresh initialization stores {@code 60, 15, 6, 0, 0, 0, 0, 0}, with zero regional strengths and empty lists.
     *
     * @param game              the game this phase belongs to
     * @param regionCount       the original signed WORD region count
     * @param counters          the eight retained scheduler DWORDs
     * @param regionalStrengths the packed regional strengths, in player-major order
     * @throws IllegalArgumentException if the region count, the array lengths or a cursor is outside its range
     */
    public ComputerPlayerPhase(@NotNull Game game, int regionCount, int @NotNull [] counters, int @NotNull [] regionalStrengths) {
        if (regionCount < 1 || regionCount > REGION_CAPACITY || counters.length != COUNTER_COUNT ||
            regionalStrengths.length != regionCount * PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            throw Exceptions.newIllegalArgumentException("The computer-player phase requires eight players and the exact regional record count.");
        }

        if (counters[COUNTER_STRENGTH_PLAYER_CURSOR] < 0 || counters[COUNTER_STRENGTH_PLAYER_CURSOR] >= PlayerTable.PLAYABLE_PLAYER_SLOTS ||
            counters[COUNTER_STRENGTH_REGION_CURSOR] < 0 || counters[COUNTER_STRENGTH_REGION_CURSOR] >= REGION_CAPACITY ||
            counters[COUNTER_PLAYER_UPDATE_CURSOR] < 0 || counters[COUNTER_PLAYER_UPDATE_CURSOR] >= PlayerTable.PLAYABLE_PLAYER_SLOTS ||
            counters[COUNTER_TOWN_UPDATE_PLAYER_CURSOR] < 0 || counters[COUNTER_TOWN_UPDATE_PLAYER_CURSOR] >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            throw Exceptions.newIllegalArgumentException("The scheduler cursor is outside its initialized range.");
        }

        this.game = game;

        this.placement = game.getComputerPlacement();
        this.military  = game.getComputerMilitary();
        this.resources = null;
        this.values    = null;
        this.scripts   = null;

        this.regionCount = regionCount;
        this.regions     = new ComputerRegions(game, regionCount);

        this.players         = new ComputerPlayer[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.controllers     = new ScrComputerController[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.towns           = new ComputerTown[TOWN_CAPACITY];
        this.firstTowns      = new ComputerTown[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.workerRecords   = new ComputerUnitRecord[UNIT_RECORD_CAPACITY];
        this.buildingRecords = new ComputerUnitRecord[UNIT_RECORD_CAPACITY];
        this.saveMetadata    = new ComputerSaveMetadata(this.towns.length, this.military.getMilitaryRecords().length,
                                                        this.military.getGuardRecords().length, this.buildingRecords.length);

        this.playerUpdateCountdown        = counters[COUNTER_PLAYER_UPDATE_COUNTDOWN];
        this.unitStatusRefreshCountdown   = counters[COUNTER_UNIT_STATUS_REFRESH_COUNTDOWN];
        this.unitStatusRefreshCycle       = counters[COUNTER_UNIT_STATUS_REFRESH_CYCLE];
        this.unitStatusRefreshRequested   = counters[COUNTER_UNIT_STATUS_REFRESH_REQUESTED];
        this.regionalStrengthPlayerCursor = counters[COUNTER_STRENGTH_PLAYER_CURSOR];
        this.regionalStrengthRegionCursor = counters[COUNTER_STRENGTH_REGION_CURSOR];
        this.playerUpdateCursor           = counters[COUNTER_PLAYER_UPDATE_CURSOR];
        this.townUpdatePlayerCursor       = counters[COUNTER_TOWN_UPDATE_PLAYER_CURSOR];

        this.hasComputerPlayers      = false;
        this.instructionLimit        = 0;
        this.updateElapsedClockTicks = 0;
        this.mergingTown             = false;
        this.interceptorAlertFrame   = -1;
        this.spellSearchForced       = false;

        this.lastError       = 0;
        this.failureGame     = null;
        this.failureProducer = null;
        this.failureType     = 0;

        for (int index = 0; index < regionalStrengths.length; index++) {
            this.regions.setEnemyStrength(index, regionalStrengths[index]);
        }

        for (int index = 0; index < TOWN_CAPACITY; index++) {
            this.towns[index] = new ComputerTown(index);
        }

        for (int index = 0; index < TOWN_CAPACITY; index++) {
            this.towns[index].previous = index == 0 ? null : this.towns[index - 1];
            this.towns[index].next     = index == TOWN_CAPACITY - 1 ? null : this.towns[index + 1];
        }

        this.firstFreeTown = this.towns[0];

        for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
            this.controllers[owner] = new ScrComputerController(this, game.getEngineGame().getPlayer(owner));
        }

        for (int index = 0; index < UNIT_RECORD_CAPACITY; index++) {
            this.workerRecords[index]   = new ComputerUnitRecord(ComputerUnitRecord.KIND_WORKER, index);
            this.buildingRecords[index] = new ComputerUnitRecord(ComputerUnitRecord.KIND_BUILDING, index);
        }

        for (int index = 0; index < UNIT_RECORD_CAPACITY; index++) {
            this.workerRecords[index].previous   = index == 0 ? null : this.workerRecords[index - 1];
            this.workerRecords[index].next       = index == UNIT_RECORD_CAPACITY - 1 ? null : this.workerRecords[index + 1];
            this.buildingRecords[index].previous = index == 0 ? null : this.buildingRecords[index - 1];
            this.buildingRecords[index].next     = index == UNIT_RECORD_CAPACITY - 1 ? null : this.buildingRecords[index + 1];
        }

        this.firstFreeWorkerRecord   = this.workerRecords[0];
        this.firstFreeBuildingRecord = this.buildingRecords[0];
    }

    /**
     * @return an independent diagnostic snapshot in the constructor's scheduler order
     */
    public int @NotNull [] getCounterSnapshot() {
        return new int[]{this.playerUpdateCountdown, this.unitStatusRefreshCountdown, this.unitStatusRefreshCycle,
                         this.unitStatusRefreshRequested, this.regionalStrengthPlayerCursor, this.regionalStrengthRegionCursor,
                         this.playerUpdateCursor, this.townUpdatePlayerCursor};
    }

    /**
     * @param playerIndex the player index in {@code 0..7}
     * @param regionIndex the region index below the configured region count
     * @return the packed regional ground and air strength values
     * @throws IndexOutOfBoundsException if either index is outside its domain
     */
    public int getRegionalStrength(int playerIndex, int regionIndex) {
        Objects.checkIndex(playerIndex, PlayerTable.PLAYABLE_PLAYER_SLOTS);
        Objects.checkIndex(regionIndex, this.regionCount);

        return this.regions.getEnemyStrength(playerIndex * this.regionCount + regionIndex);
    }

    /**
     * @return the region count of the region records, the first section of the computer-player part
     */
    public int getRegionCount() {
        return this.regionCount;
    }

    /**
     * @return the region records of every owner
     */
    @NotNull ComputerRegions getRegions() {
        return this.regions;
    }

    /**
     * @return the animation script instruction budget of the current update, used by orders issued from computer routines
     */
    public int getInstructionLimit() {
        return this.instructionLimit;
    }

    /**
     * @return the computer value tables, the game's unit strength tables
     * @throws IllegalStateException if the value tables are read before the scripts are initialized
     */
    @NotNull UnitStrengthTable requireValues() {
        if (this.values == null) {
            throw Exceptions.newIllegalStateException("Computer value tables are read before the scripts are initialized.");
        }

        return this.values;
    }

    /**
     * @param type the unit type
     * @return the air value of the type in the computer value tables
     * @throws IllegalStateException if the value tables are read before the scripts are initialized
     */
    int getAirValue(int type) {
        return this.requireValues().getStrength(type, UnitStrengthTable.AIR);
    }

    /**
     * @param type the unit type
     * @return the ground value of the type in the computer value tables
     * @throws IllegalStateException if the value tables are read before the scripts are initialized
     */
    int getGroundValue(int type) {
        return this.requireValues().getStrength(type, UnitStrengthTable.GROUND);
    }

    /**
     * @return the game's military records and guards
     */
    public @NotNull ComputerMilitary getMilitary() {
        return this.military;
    }

    /**
     * Returns the computer unit record a saved unit references; a saved unit holds the kind in the high WORD and the
     * one-based record index in the low WORD.
     *
     * @param kind      the record kind ({@link ComputerUnitRecord#getKind()})
     * @param poolIndex the zero-based index in the kind's pool
     * @return the record
     * @throws IndexOutOfBoundsException if the kind has no such record
     */
    public @NotNull ComputerUnitRecord getUnitRecord(int kind, int poolIndex) {
        return switch (kind) {
            case ComputerUnitRecord.KIND_WORKER -> this.workerRecords[poolIndex];
            case ComputerUnitRecord.KIND_BUILDING -> this.buildingRecords[poolIndex];
            default -> this.military.getRecord(kind, poolIndex);
        };
    }

    /**
     * @return the game's computer building placement
     */
    public @NotNull ComputerPlacement getPlacement() {
        return this.placement;
    }

    /**
     * @return the computer resource marks
     * @throws IllegalStateException if the scripts are not initialized
     */
    @NotNull ComputerResources getResources() {
        ComputerResources current = this.resources;

        if (current == null) {
            throw Exceptions.newIllegalStateException("Computer resource marks are created with the scripts.");
        }

        return current;
    }

    /**
     * @param owner the owner in {@code 0..7}
     * @return the owner's computer player record
     * @throws IllegalStateException          if the player records are not initialized
     * @throws ArrayIndexOutOfBoundsException if the owner is outside the computer-player array
     */
    public @NotNull ComputerPlayer getPlayer(int owner) {
        ComputerPlayer player = this.players[owner];

        if (player == null) {
            throw Exceptions.newIllegalStateException("Computer player records are initialized with the scripts.");
        }

        return player;
    }

    /**
     * Returns the computer controller of a playable slot.
     *
     * @param owner the owner in {@code 0..7}
     * @return the controller of the slot
     * @throws ArrayIndexOutOfBoundsException if the owner is outside the playable slots
     */
    @NotNull ScrComputerController getController(int owner) {
        return this.controllers[owner];
    }

    /**
     * @return the failure code the last requirement test left for the computer routines
     * @throws IllegalStateException if a deferred production failure lost its inputs
     */
    int getLastError() {
        if (this.lastError == UNREPRESENTED_PRODUCTION_ERROR) {
            Game failedGame = this.failureGame;
            Unit producer = this.failureProducer;

            if (failedGame == null || producer == null) {
                throw Exceptions.newIllegalStateException("A deferred production failure lost its inputs.");
            }

            this.lastError       = failedGame.getComputerRequirements().getFailureCode(this.failureType, producer.getOwnerIndex(), producer);
            this.failureGame     = null;
            this.failureProducer = null;
        }

        return this.lastError;
    }

    /**
     * Sets the failure code of the last requirement test.
     *
     * @param error the production failure code
     */
    void setLastError(int error) {
        this.lastError       = error;
        this.failureGame     = null;
        this.failureProducer = null;
    }

    /**
     * @return {@code true} if a player of the game is or was a computer player, {@code false} otherwise
     */
    public boolean hasComputerPlayers() {
        return this.hasComputerPlayers;
    }

    /**
     * @return {@code true} if the last spell target search stored the forced argument, {@code false} otherwise
     */
    boolean isSpellSearchForced() {
        return this.spellSearchForced;
    }

    /**
     * Sets the forced argument of the spell target searches.
     *
     * @param forced the forced argument
     */
    void setSpellSearchForced(boolean forced) {
        this.spellSearchForced = forced;
    }

    /**
     * Returns the live script buffer of {@code aiscript.bin} or {@code bwscript.bin}, which the EUD regions of the two
     * programs serve.
     *
     * @param expansion whether the {@code bwscript.bin} buffer is requested
     * @return the buffer, or {@code null} before the script pool exists
     */
    public byte @Nullable [] getScriptBuffer(boolean expansion) {
        ComputerScripts current = this.scripts;

        return current == null ? null : current.getProgramBuffer(expansion);
    }

    /**
     * @return the script pool
     * @throws IllegalStateException if the script pool is not initialized
     */
    @NotNull ComputerScripts getScripts() {
        ComputerScripts current = this.scripts;

        if (current == null) {
            throw Exceptions.newIllegalStateException("Computer expansions require the script pool.");
        }

        return current;
    }

    /**
     * @param owner the owner in {@code 0..7}
     * @return the first town of the owner's active town list, or {@code null} without towns
     */
    @Nullable ComputerTown getFirstTown(int owner) {
        return this.firstTowns[owner];
    }

    /**
     * @return {@code true} if an Interceptor already exposed its Carrier in the current frame, {@code false} otherwise
     */
    boolean isInterceptorAlerted() {
        return this.interceptorAlertFrame == this.game.getCurrentFrame();
    }

    /**
     * Sets the mark that an Interceptor exposed its Carrier in the current frame. The frame comparison expires the mark
     * when the game advances.
     */
    void setInterceptorAlerted() {
        this.interceptorAlertFrame = this.game.getCurrentFrame();
    }

    /**
     * @return the elapsed trigger clock ticks, or {@code 0} before the trigger clock exists
     */
    int getElapsedClockTicks() {
        TriggerContext triggers = this.game.getTriggerContext();

        return triggers == null ? 0 : triggers.getElapsedClockTicks();
    }

    /**
     * Sets the last error to a rejection of the common production test whose failure code {@link #getLastError} derives
     * through {@link ComputerRequirements} when first read.
     *
     * @param type     the rejected type
     * @param producer the producer
     */
    void setProductionFailure(int type, @NotNull Unit producer) {
        this.lastError       = UNREPRESENTED_PRODUCTION_ERROR;
        this.failureGame     = this.game;
        this.failureType     = type;
        this.failureProducer = producer;
    }

    /**
     * Applies the technology-use test for the unit's owner, which stores its failure code (or {@code 0}) as the last
     * error.
     * <p>
     * The last error changes only after requirement processing returns; invalid technology identifiers return before the game-data read.
     *
     * @param unit       the unit to use the technology
     * @param technology the technology
     * @return {@code true} if the unit may use the technology, {@code false} otherwise
     */
    public boolean canUseTechnology(@NotNull Unit unit, int technology) {
        int result = this.game.getRequirements().checkTechnologyUse(unit, technology, unit.getOwnerIndex());

        this.setLastError(ScrRequirementSystem.getFailureCode(result));

        return ScrRequirementSystem.isAccepted(result);
    }

    /**
     * Returns whether the owner needs power: no Pylon is in construction and a live unit with
     * {@link UnitStatusFlags#DISABLED} status stands in a quiet region.
     *
     * @param owner the owner in {@code 0..7}
     * @return {@code true} if the owner needs power, {@code false} otherwise
     * @throws UnsupportedOperationException if the unit accounting or the unit pool is not initialized
     */
    boolean isPowerNeeded(int owner) {
        ComputerUnits computerUnits = this.game.getComputerUnits();

        if (Integer.compareUnsigned(computerUnits.getTypeCount(ScrUnitIds.PROTOSS_PYLON, owner),
                                    computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_PYLON, owner)) > 0) {
            return false;
        }

        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            // A disabled unit in a quiet region triggers the power request.
            if (!ComputerUnits.isDeadOrHidden(unit) && (unit.getStatus() & UnitStatusFlags.DISABLED) != 0 &&
                this.regions.isQuiet(unit.getOwnerIndex(), this.game.getComputerTerrain().getUnitRegion(unit))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Issues an order with the current update's instruction budget.
     *
     * @param unit            the receiving unit
     * @param orderIdentifier the order BYTE
     */
    void issueOrder(@NotNull Unit unit, int orderIdentifier) {
        this.game.getOrders().issueUntargeted(unit, orderIdentifier, this.instructionLimit);
    }

    /**
     * Returns whether an enemy grounded building is reachable on the ground from the position.
     *
     * @param owner     the owner
     * @param positionX the position x coordinate
     * @param positionY the position y coordinate
     * @return {@code true} if such a building exists, {@code false} otherwise
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    boolean hasGroundEnemy(int owner, int positionX, int positionY) {
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || !this.game.getComputerUnits().isEnemy(owner, unit) || !UnitQueries.isGroundedBuilding(unit)) {
                continue;
            }

            if (this.game.getUnitPositioning().isGroundReachable(unit, positionX, positionY)) {
                return true;
            }
        }

        return false;
    }


    /**
     * @param owner the owner in {@code 0..7}
     * @return the owner's computer player record, or {@code null} before the scripts are initialized
     */
    @Nullable ComputerPlayer findPlayer(int owner) {
        return this.players[owner];
    }

    /**
     * Initializes the computer players: with {@code meleeScripts} the race Melee script starts at each computer start
     * location; otherwise every player record gets the campaign flag and no script starts.
     *
     * @param meleeScripts whether the Melee scripts start
     * @throws IOException                   if either original program cannot be read
     * @throws IllegalStateException         if a Melee computer has no resolved race, no expansion mode, a start location of
     *                                       another player, or no startable script
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the game has no unit strength tables
     */
    public void initializeScripts(boolean meleeScripts) throws IOException {
        this.resources = this.game.getComputerResources();
        // Use the established strength tables rather than recomputing values from patched scenario unit settings.
        this.values = this.game.getUnitStrengthTable();

        if (this.values == null) {
            throw Exceptions.newUnsupportedOperationException("Computer value tables require the game's unit strength tables.");
        }

        for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
            Player player = this.game.getPlayerTable().getPlayer(owner);

            this.players[owner] = new ComputerPlayer(owner, player == null ? 0 : player.getMinerals(), player == null ? 0 : player.getGas());
        }

        // The 100-record script pool and both directories exist in every game; trigger actions can start scripts for
        // human players (Run AI Script).
        this.scripts = new ComputerScripts(this.game,
                                           new ComputerScriptProgram(this.game.requireGameData().readComputerScripts(false),
                                                                     this.game.requireGameData().readComputerScripts(true)));

        if (!meleeScripts) {
            for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
                ComputerPlayer computer = this.getPlayer(owner);

                computer.setFlags(computer.getFlags() | ComputerPlayer.FLAG_CAMPAIGN);

                Player player = this.game.getPlayerTable().getPlayer(owner);

                if (player != null && player.getType() == PlayerTypes.COMPUTER) {
                    this.hasComputerPlayers = true;
                }
            }

            return;
        }

        for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
            Player player = this.game.getPlayerTable().getPlayer(owner);

            if (player == null || player.getType() != PlayerTypes.COMPUTER) {
                continue;
            }

            this.hasComputerPlayers = true;

            int race = player.getRace();
            int raceIdentifier;

            if (race == ScrRaceIds.ZERG) {
                raceIdentifier = MELEE_SCRIPT_ZERG;
            } else if (race == ScrRaceIds.TERRAN) {
                raceIdentifier = MELEE_SCRIPT_TERRAN;
            } else if (race == ScrRaceIds.PROTOSS) {
                raceIdentifier = MELEE_SCRIPT_PROTOSS;
            } else {
                throw Exceptions.newIllegalStateException("Melee computer has an unresolved race.");
            }

            if (this.game.getPlayerTable().getPlayerUpgrades() == null) {
                throw Exceptions.newIllegalStateException("Melee computer requires established expansion mode.");
            }

            int identifier = raceIdentifier | MELEE_SCRIPT_KIND_FIRST << Byte.SIZE | MELEE_SCRIPT_KIND_SECOND << Short.SIZE |
                             (this.game.getPlayerTable().getPlayerUpgrades().isExpansionEnabled()
                              ? MELEE_SCRIPT_EXPANSION
                              : MELEE_SCRIPT_ORIGINAL) << SCRIPT_LAST_LETTER_SHIFT;
            StartLocation start = this.game.getPlayerTable().getStartLocations().get(owner);

            if (start.getOwner() != owner) {
                throw Exceptions.newIllegalStateException("Computer script start locations lost player ordering.");
            }

            if (!this.scripts.start(identifier, owner, start.getX(), start.getY())) {
                throw Exceptions.newIllegalStateException("Original Melee computer script is absent or its pool is exhausted.");
            }
        }
    }

    /**
     * Starts a script for a trigger action, after the caller applied the owner check. An absent identifier or an
     * exhausted pool starts nothing, as in the original.
     *
     * @param identifier the four-character script identifier
     * @param owner      the trigger owner
     * @param location   the location rectangle {@code left, top, right, bottom}, or {@code null} for Run AI Script
     * @throws IllegalStateException if the script pool was not initialized
     */
    public void runScript(int identifier, int owner, int @Nullable [] location) {
        ComputerScripts current = this.scripts;

        if (current == null) {
            throw Exceptions.newIllegalStateException("Computer scripts are created by game initialization.");
        }

        current.start(identifier, owner, location);
    }

    /**
     * Writes the region sections after the region count: the military pool ({@link ComputerMilitary#writeSaveMilitaryRecords},
     * which clears the free records first) and the region records of every owner ({@link ComputerRegions#writeSaveRecords}).
     *
     * @param military   the military section, little-endian
     * @param regions    the region section of each owner, little-endian, indexed by owner
     * @param references the unit references of the saved game
     */
    public void writeSaveRegions(@NotNull ByteBuffer military, @NotNull ByteBuffer @NotNull [] regions, @NotNull SaveReferences references) {
        this.military.writeSaveMilitaryRecords(military, this.regions, this.saveMetadata, references);

        for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
            this.regions.writeSaveRecords(regions[owner], owner, references);
        }
    }

    /**
     * Reads the sections that {@link #writeSaveRegions} wrote. Region records are recreated for the saved count before
     * their contents are read. The regional strength cursors are reset.
     *
     * @param regionCount the saved region count
     * @param military    the military section, little-endian
     * @param regions     the region section of each owner, little-endian, indexed by owner
     * @param references  the unit references of the restored game
     * @throws UncheckedIOException if the saved region count is outside {@code 1..2500}
     */
    public void readSaveRegions(int regionCount,
                                @NotNull ByteBuffer military,
                                @NotNull ByteBuffer @NotNull [] regions,
                                @NotNull SaveReferences references) {
        if (regionCount < 1 || regionCount > REGION_CAPACITY) {
            throw SaveFileReader.createFailure("The saved computer records cover {0} regions.", regionCount);
        }

        this.regionCount = regionCount;
        this.regions     = new ComputerRegions(this.game, regionCount);

        this.military.readSaveMilitaryRecords(military, this.regions, this.saveMetadata, references);

        for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
            this.regions.readSaveRecords(regions[owner], owner, this.military.getMilitaryRecords(), references);
        }

        this.regionalStrengthPlayerCursor = 0;
        this.regionalStrengthRegionCursor = 0;
    }

    /**
     * Writes the guard sections: the guard pool and the owners' guard lists ({@link ComputerMilitary#writeSaveGuardRecords}).
     *
     * @param records    the record section, little-endian
     * @param heads      the list head section, little-endian
     * @param references the unit references of the saved game
     */
    public void writeSaveGuards(@NotNull ByteBuffer records, @NotNull ByteBuffer heads, @NotNull SaveReferences references) {
        this.military.writeSaveGuardRecords(records, heads, this.saveMetadata, references);
    }

    /**
     * Reads the sections that {@link #writeSaveGuards} wrote.
     *
     * @param records    the record section, little-endian
     * @param heads      the list head section, little-endian
     * @param references the unit references of the restored game
     */
    public void readSaveGuards(@NotNull ByteBuffer records, @NotNull ByteBuffer heads, @NotNull SaveReferences references) {
        this.military.readSaveGuardRecords(records, heads, this.saveMetadata, references);
    }

    /**
     * Writes the town sections. Each pool first clears its free records ({@link ComputerUnitRecord#clearFreeWorker},
     * {@link ComputerUnitRecord#clearFreeBuilding}, {@link ComputerTown#clearFree}), then writes every record and the
     * one-based free head DWORD after them: the worker records ({@link ComputerUnitRecord#writeSaveWorker}), the building
     * records ({@link ComputerUnitRecord#writeSaveBuilding}) and the towns ({@link ComputerTown#writeSaveRecord}); the last
     * section writes each owner's one-based first town at the second DWORD of its list view, with a zero base DWORD.
     *
     * @param workers    the worker section, little-endian
     * @param buildings  the building section, little-endian
     * @param towns      the town section, little-endian
     * @param heads      the town list head section, little-endian
     * @param references the unit references of the saved game
     */
    public void writeSaveTowns(@NotNull ByteBuffer workers,
                               @NotNull ByteBuffer buildings,
                               @NotNull ByteBuffer towns,
                               @NotNull ByteBuffer heads,
                               @NotNull SaveReferences references) {
        boolean[] free = new boolean[UNIT_RECORD_CAPACITY];

        for (ComputerUnitRecord record = this.firstFreeWorkerRecord; record != null; record = record.next) {
            record.clearFreeWorker();

            free[record.getPoolIndex()] = true;
        }

        for (int index = 0; index < UNIT_RECORD_CAPACITY; index++) {
            this.workerRecords[index].writeSaveWorker(workers, index * ComputerUnitRecord.SAVED_WORKER_SIZE, free[index], references);
        }

        workers.putInt(UNIT_RECORD_CAPACITY * ComputerUnitRecord.SAVED_WORKER_SIZE, ComputerUnitRecord.encodeSaveLink(this.firstFreeWorkerRecord));

        free = new boolean[UNIT_RECORD_CAPACITY];

        for (ComputerUnitRecord record = this.firstFreeBuildingRecord; record != null; record = record.next) {
            record.clearFreeBuilding();

            free[record.getPoolIndex()] = true;
        }

        clearSaveRange(buildings, SAVED_BUILDINGS_SIZE);

        for (int index = 0; index < UNIT_RECORD_CAPACITY; index++) {
            this.buildingRecords[index].writeSaveBuilding(buildings,
                                                          index * ComputerUnitRecord.SAVED_BUILDING_SIZE,
                                                          free[index],
                                                          this.regions,
                                                          references);
        }

        buildings.putInt(UNIT_RECORD_CAPACITY * ComputerUnitRecord.SAVED_BUILDING_SIZE,
                         ComputerUnitRecord.encodeSaveLink(this.firstFreeBuildingRecord));

        this.saveMetadata.writeBuildings(buildings);

        for (ComputerTown town = this.firstFreeTown; town != null; town = town.next) {
            town.clearFree();
            this.saveMetadata.clearFreeTown(town.getPoolIndex());
        }

        clearSaveRange(towns, SAVED_TOWNS_SIZE);

        for (int index = 0; index < TOWN_CAPACITY; index++) {
            this.towns[index].writeSaveRecord(towns, index * ComputerTown.SAVED_SIZE, references);
        }

        towns.putInt(TOWN_CAPACITY * ComputerTown.SAVED_SIZE, ComputerTown.encodeSaveLink(this.firstFreeTown));

        this.saveMetadata.writeTowns(towns);

        clearSaveRange(heads, SAVED_TOWN_HEADS_SIZE);

        for (int owner = 0; owner < this.firstTowns.length; owner++) {
            heads.putInt(owner * ComputerMilitary.SAVED_LIST_VIEW_SIZE + Integer.BYTES, ComputerTown.encodeSaveLink(this.firstTowns[owner]));
        }
    }

    /**
     * Reads the sections that {@link #writeSaveTowns} wrote.
     *
     * @param workers    the worker section, little-endian
     * @param buildings  the building section, little-endian
     * @param towns      the town section, little-endian
     * @param heads      the town list head section, little-endian
     * @param references the unit references of the restored game
     */
    public void readSaveTowns(@NotNull ByteBuffer workers,
                              @NotNull ByteBuffer buildings,
                              @NotNull ByteBuffer towns,
                              @NotNull ByteBuffer heads,
                              @NotNull SaveReferences references) {
        for (int index = 0; index < UNIT_RECORD_CAPACITY; index++) {
            this.workerRecords[index].readSaveWorker(workers, index * ComputerUnitRecord.SAVED_WORKER_SIZE,
                                                     this.workerRecords,
                                                     this.towns,
                                                     references);
        }

        int firstFreeWorkerLink = workers.getInt(UNIT_RECORD_CAPACITY * ComputerUnitRecord.SAVED_WORKER_SIZE);

        this.firstFreeWorkerRecord = ComputerUnitRecord.decodeSaveLink(this.workerRecords, firstFreeWorkerLink);

        for (int index = 0; index < UNIT_RECORD_CAPACITY; index++) {
            this.buildingRecords[index].readSaveBuilding(buildings, index * ComputerUnitRecord.SAVED_BUILDING_SIZE,
                                                         this.buildingRecords,
                                                         this.military.getGuardRecords(),
                                                         this.towns,
                                                         this.regions,
                                                         references);
            this.saveMetadata.readBuilding(buildings, index * ComputerUnitRecord.SAVED_BUILDING_SIZE, index);
        }

        int firstFreeBuildingLink = buildings.getInt(UNIT_RECORD_CAPACITY * ComputerUnitRecord.SAVED_BUILDING_SIZE);

        this.firstFreeBuildingRecord = ComputerUnitRecord.decodeSaveLink(this.buildingRecords, firstFreeBuildingLink);

        for (int index = 0; index < TOWN_CAPACITY; index++) {
            this.towns[index].readSaveRecord(towns, index * ComputerTown.SAVED_SIZE,
                                             this.towns,
                                             this.workerRecords,
                                             this.buildingRecords,
                                             references);
            this.saveMetadata.readTown(towns, index * ComputerTown.SAVED_SIZE, index);
        }

        this.firstFreeTown = ComputerTown.decodeSaveLink(this.towns, towns.getInt(TOWN_CAPACITY * ComputerTown.SAVED_SIZE));

        for (int owner = 0; owner < this.firstTowns.length; owner++) {
            int firstTownLink = heads.getInt(owner * ComputerMilitary.SAVED_LIST_VIEW_SIZE + Integer.BYTES);

            this.firstTowns[owner] = ComputerTown.decodeSaveLink(this.towns, firstTownLink);
        }
    }

    /**
     * Writes the player section: the eight player records ({@link ComputerPlayer#writeSaveRecord}).
     *
     * @param players    the section, little-endian
     * @param references the unit references of the saved game
     */
    public void writeSavePlayers(@NotNull ByteBuffer players, @NotNull SaveReferences references) {
        for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
            this.getPlayer(owner).writeSaveRecord(players, owner * ComputerPlayer.SAVED_SIZE, references);
        }
    }

    /**
     * Reads the section that {@link #writeSavePlayers} wrote.
     *
     * @param players    the section, little-endian
     * @param references the unit references of the restored game
     */
    public void readSavePlayers(@NotNull ByteBuffer players, @NotNull SaveReferences references) {
        for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
            this.getPlayer(owner).readSaveRecord(players, owner * ComputerPlayer.SAVED_SIZE, references);
        }
    }

    /**
     * Writes the script sections: the script pool and its active list ({@link ComputerScripts#writeSaveRecords}).
     *
     * @param records the record section, little-endian
     * @param head    the list head section, little-endian
     */
    public void writeSaveScripts(@NotNull ByteBuffer records, @NotNull ByteBuffer head) {
        this.getScripts().writeSaveRecords(records, head);
    }

    /**
     * Reads the sections that {@link #writeSaveScripts} wrote.
     *
     * @param records the record section, little-endian
     * @param head    the list head section, little-endian
     */
    public void readSaveScripts(@NotNull ByteBuffer records, @NotNull ByteBuffer head) {
        this.getScripts().readSaveRecords(records, head, this.towns);
    }

    /**
     * Applies the scheduler for games without computer players.
     *
     * @param playerTypes       the eight currently decoded player-record type BYTEs
     * @param elapsedClockTicks the elapsed trigger clock ticks
     * @throws UnsupportedOperationException if a computer player requires the instruction budget
     */
    public void update(int @NotNull [] playerTypes, int elapsedClockTicks) {
        for (int playerType : playerTypes) {
            if ((playerType & BYTE_MASK) == PlayerTypes.COMPUTER) {
                throw Exceptions.newUnsupportedOperationException("Computer players require the order instruction budget.");
            }
        }

        this.update(playerTypes, elapsedClockTicks, 0);
    }

    /**
     * Applies the complete computer-player scheduler: the script records, the regional sweep, the resource areas, the
     * unit status refresh, one player update and one town update at their countdowns, then the strength comparison.
     *
     * @param playerTypes       the eight currently decoded player-record type BYTEs
     * @param elapsedClockTicks the elapsed trigger clock ticks
     * @param instructionLimit  the iscript budget used by orders issued from computer routines
     * @throws IllegalArgumentException      if there are not eight player types
     * @throws UnsupportedOperationException if computer players run without scripts or without unit and resource-area contexts
     */
    public void update(int @NotNull [] playerTypes, int elapsedClockTicks, int instructionLimit) {
        if (playerTypes.length != PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            throw Exceptions.newIllegalArgumentException("The computer-player phase requires eight decoded player records.");
        }

        for (int playerType : playerTypes) {
            this.hasComputerPlayers |= (playerType & BYTE_MASK) == PlayerTypes.COMPUTER;
        }

        if (this.hasComputerPlayers && this.scripts == null) {
            throw Exceptions.newUnsupportedOperationException("Computer startup requires initialized Melee strategy scripts.");
        }

        this.instructionLimit        = instructionLimit;
        this.updateElapsedClockTicks = elapsedClockTicks;

        if (this.scripts != null) {
            this.scripts.update(this, instructionLimit);
        }

        UnitPool pool = this.game.getUnitSystem().getUnitPool();
        ResourceAreas resourceAreas = this.game.getResourceAreas();

        if (pool == null || resourceAreas == null) {
            throw Exceptions.newUnsupportedOperationException("The computer-player phase requires established unit and resource-area contexts.");
        }

        this.updateRegions(playerTypes);
        resourceAreas.update();

        if (this.unitStatusRefreshCountdown != 0) {
            this.unitStatusRefreshCountdown--;
        } else {
            this.unitStatusRefreshCountdown = UNIT_STATUS_REFRESH_PERIOD;

            int previousCycle = this.unitStatusRefreshCycle;

            if (previousCycle != 0) {
                this.unitStatusRefreshCycle--;
            }

            if (previousCycle == 0 || this.unitStatusRefreshRequested != 0) {
                this.unitStatusRefreshCycle     = UNIT_STATUS_REFRESH_CYCLES;
                this.unitStatusRefreshRequested = 0;

                for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
                    Unit unit = pool.getUnit(poolIndex);

                    unit.setStatus(unit.getStatus() & ~UnitStatusFlags.COMPUTER_TARGET_CLAIMED);
                }
            }
        }

        int previousCountdown = this.playerUpdateCountdown--;

        if (previousCountdown != 0) {
            return;
        }

        this.playerUpdateCountdown = Integer.compareUnsigned(elapsedClockTicks, EARLY_PLAYER_UPDATE_CLOCK) < 0
                                     ? EARLY_PLAYER_UPDATE_DELAY
                                     : PLAYER_UPDATE_DELAY;

        if (++this.playerUpdateCursor == PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            this.playerUpdateCursor = 0;
        }

        if ((playerTypes[this.playerUpdateCursor] & BYTE_MASK) == PlayerTypes.COMPUTER &&
            (this.getPlayer(this.playerUpdateCursor).getFlags() & ComputerPlayer.FLAG_TOWN_STARTED) != 0) {
            this.controllers[this.playerUpdateCursor].update(this.game.getEngineGame());
        }

        if (++this.townUpdatePlayerCursor == PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            this.townUpdatePlayerCursor = 0;
        }

        this.game.getComputerTowns().updateTowns(this, this.townUpdatePlayerCursor);

        if (this.hasComputerPlayers) {
            this.compareStrength(playerTypes, elapsedClockTicks);
        }
    }

    /**
     * Compares, after {@code 359} clock ticks, the ground strength of each player's side with its strongest enemy side, marks
     * computer players that outnumber every enemy, and starts an attack when the allied strength doubles the enemy
     * strength late enough.
     *
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private void compareStrength(int @NotNull [] playerTypes, int elapsedClockTicks) {
        if (Integer.compareUnsigned(elapsedClockTicks, STRENGTH_COMPARISON_START_CLOCK) <= 0) {
            return;
        }

        for (ComputerPlayer player : this.players) {
            if (player != null && player.isCampaign()) {
                return;
            }
        }

        PlayerRelations relations = this.game.getPlayerTable().requirePlayerRelations();
        int[] allied = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        int[] enemy = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        int[] strongest = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        int[] total = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);
            int owner = unit.getOwnerIndex();

            if (ComputerUnits.isDeadOrHidden(unit) || owner >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                continue;
            }

            int ground = unit.getGroundStrength();

            total[owner] += ground;

            if (!this.game.getComputerUnits().isWorker(unit) &&
                !UnitQueries.isBuilding(this.game.requireGameData().getUnitTypes(), unit.getUnitId())) {
                for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
                    if (player == owner || relations.getAlliance(owner, player) == PlayerRelations.ALLIED_VICTORY) {
                        allied[player] += ground;
                    }
                }
            }

            for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
                if (relations.getAlliance(player, owner) == PlayerRelations.ENEMY || relations.getAlliance(owner, player) == PlayerRelations.ENEMY) {
                    enemy[player] += ground;
                }
            }
        }

        for (int other = 0; other < PlayerTable.PLAYABLE_PLAYER_SLOTS; other++) {
            for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
                if (relations.getAlliance(other, player) == PlayerRelations.ENEMY && Integer.compareUnsigned(strongest[player], allied[other]) < 0) {
                    strongest[player] = allied[other];
                }
            }
        }

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            if ((playerTypes[player] & BYTE_MASK) != PlayerTypes.COMPUTER) {
                continue;
            }

            this.getPlayer(player).setOutnumbering(Integer.compareUnsigned(strongest[player], total[player]) < 0);

            if ((Integer.compareUnsigned(allied[player], STRENGTH_ATTACK_ALLIED_STRENGTH) > 0 ||
                 Integer.compareUnsigned(elapsedClockTicks, STRENGTH_ATTACK_START_CLOCK) > 0) &&
                enemy[player] != 0 && Integer.compareUnsigned(enemy[player] * STRENGTH_ATTACK_ENEMY_FACTOR, allied[player]) < 0) {
                this.game.getComputerAttack().startStrengthAttack(this, player);
                this.getPlayer(player).setAttackDelay(0, this.getElapsedClockTicks());
            }
        }
    }

    /**
     * Applies the computer branch of the player update of the running scheduler step ({@link ScrComputerController#update}): the budgets,
     * the pending construction, the supply budget, the military records, the region defense and the town requests, with the elapsed trigger
     * clock ticks of the step.
     *
     * @param owner the owner in {@code 0..7}
     */
    void updatePlayer(int owner) {
        ComputerPlayer player = this.getPlayer(owner);
        Player record = this.game.getPlayerTable().requirePlayer(owner);

        player.beginUpdate(record.getMinerals(), record.getGas());
        this.reservePendingConstruction(owner, player);
        player.setSupplyBudget(this.game.getSupplyCheck().getAvailableSupply(ComputerUnits.getWorkerType(record.getRace()), owner));
        player.setDefenseCountdown(PLAYER_DEFENSE_COUNTDOWN);
        this.military.update(this, owner, this.instructionLimit);

        if (Integer.compareUnsigned(this.updateElapsedClockTicks, REGION_DEFENSE_START_CLOCK) >= 0) {
            this.regions.planDefense(this, owner);
        }

        this.game.getComputerTowns().processRequests(this, owner);
    }

    /**
     * Removes the costs of buildings that town workers are about to construct from the player's budgets.
     */
    private void reservePendingConstruction(int owner, @NotNull ComputerPlayer player) {
        for (ComputerTown town = this.firstTowns[owner]; town != null; town = town.next) {
            for (ComputerUnitRecord record = town.firstWorker; record != null; record = record.next) {
                int stage = record.requireUnit().getPrimaryOrder() - ScrOrderIds.DRONE_START_BUILD;

                if (stage < 0 || stage > CONSTRUCTION_ORDER_LAST_STAGE || (CONSTRUCTION_ORDER_STAGES >>> stage & 1) == 0) {
                    continue;
                }

                Unit worker = record.unit;
                int type = UnitQueries.getQueueSlotType(worker, 0);
                // The cost test runs with the error display on; an empty queue entry (228) reads the zero padding after
                // the cost and supply tables, so it passes with zero costs. The budgets lose the tested costs.
                CostTests costs = this.game.getCostTests();

                if (!costs.testUnit(this.game, owner, type, true, true)) {
                    continue;
                }

                player.chargeBudgets(costs.getMineralCosts()[owner], costs.getGasCosts()[owner]);
            }
        }
    }

    /**
     * Counts the towns of the owner's active town list.
     *
     * @param owner the owner in {@code 0..7}
     * @return the town count
     */
    int countTowns(int owner) {
        int count = 0;

        for (ComputerTown town = this.firstTowns[owner]; town != null; town = town.next) {
            count++;
        }

        return count;
    }

    /**
     * Counts a type over all of the owner's towns.
     *
     * @param owner the owner in {@code 0..7}
     * @param type  the unit type
     * @return the unit count
     */
    int countOwnerTownUnits(int owner, int type) {
        int count = 0;

        for (ComputerTown town = this.firstTowns[owner]; town != null; town = town.next) {
            count += this.game.getComputerTowns().countTownUnits(town, type, false);
        }

        return count;
    }

    /**
     * Moves idle workers from towns with surplus workers to a town, starting with the owner's oldest town.
     *
     * @param town  the receiving town
     * @param count the requested worker count
     */
    void transferWorkers(@NotNull ComputerTown town, int count) {
        if (town.requestDelay != 0) {
            return;
        }

        if (town.workerCount != 0) {
            if (town.desiredWorkers <= town.workerCount) {
                return;
            }

            if (town.desiredWorkers < town.workerCount + count) {
                count = town.desiredWorkers - town.workerCount;
            }
        }

        if (count == 0) {
            return;
        }

        ComputerTown candidate = this.firstTowns[town.owner];

        if (candidate == null) {
            return;
        }

        while (candidate.next != null) {
            candidate = candidate.next;
        }

        for (; candidate != null; candidate = candidate.previous) {
            if (candidate == town || candidate.workerCount <= 1 || candidate.resourceDepot == null ||
                !this.game.getUnitResourceSearch().isActiveDepot(candidate.resourceDepot) ||
                town.workerCount != 0 && candidate.workerCount <= candidate.desiredWorkers) {
                continue;
            }

            for (ComputerUnitRecord record = candidate.firstWorker; record != null; ) {
                ComputerUnitRecord following = record.next;
                Unit unit = record.requireUnit();
                int stage = unit.getPrimaryOrder() - ScrOrderIds.DRONE_START_BUILD;
                boolean busy = stage >= 0 && stage < Long.SIZE && (BUSY_WORKER_ORDER_STAGES >>> stage & 1) != 0;

                if (!busy && (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
                    // The worker leaves its town without the transfer flag and binds to the receiving town.
                    this.releaseTownRecord(unit);
                    this.bindUnit(town, unit, this.instructionLimit);

                    if (--count == 0) {
                        return;
                    }
                }

                if (candidate.firstWorker == null) {
                    break;
                }

                record = following;
            }
        }
    }

    /**
     * Applies the regional sweep: a fixed number of steps over the regions of each player, recomputing the enemy
     * strengths after a full pass and assigning an expansion target to the next computer player.
     */
    private void updateRegions(int @NotNull [] playerTypes) {
        for (int iteration = 0; iteration < REGION_SWEEP_STEPS; iteration++) {
            int owner = this.regionalStrengthPlayerCursor;
            int region = this.regionalStrengthRegionCursor;

            if (region < this.regionCount && (playerTypes[owner] & BYTE_MASK) == PlayerTypes.COMPUTER) {
                this.regions.update(this, owner, region);
            }

            if (++this.regionalStrengthRegionCursor != REGION_CAPACITY) {
                continue;
            }

            this.regionalStrengthRegionCursor = 1;

            if (++this.regionalStrengthPlayerCursor == PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                this.regionalStrengthPlayerCursor = 0;

                this.accumulateEnemyStrength(playerTypes);
            }

            int next = this.regionalStrengthPlayerCursor;

            if ((playerTypes[next] & BYTE_MASK) == PlayerTypes.COMPUTER && !this.getPlayer(next).isCampaign()) {
                this.game.getComputerAttack().assignExpansionTarget(this, next);
            }
        }
    }

    /**
     * Zeroes every enemy strength and adds each unit's larger strength WORD to the regions of computer players that
     * consider it an enemy.
     *
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private void accumulateEnemyStrength(int @NotNull [] playerTypes) {
        this.regions.clearEnemyStrength();

        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit)) {
                continue;
            }

            int strength = Math.max(unit.getAirStrength() & WORD_MASK, unit.getGroundStrength() & WORD_MASK);

            if (strength == 0) {
                continue;
            }

            int region = this.game.getComputerTerrain().getUnitRegion(unit);

            for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
                if ((playerTypes[owner] & BYTE_MASK) != PlayerTypes.COMPUTER || unit.getOwnerIndex() == owner ||
                    !this.game.getComputerUnits().isEnemy(owner, unit)) {
                    continue;
                }

                this.regions.addEnemyStrength(this.regions.getIndex(owner, region), (unit.getStatus() & UnitStatusFlags.FLYING) != 0, strength);
            }
        }
    }

    /**
     * Allocates a town and binds the owner's eligible units; a melee owner's town also activates its regions and
     * defends its chokes.
     *
     * @param owner            the owner
     * @param positionX        the town horizontal pixel
     * @param positionY        the town vertical pixel
     * @param resourceArea     the script flags shifted right by three
     * @param instructionLimit the iscript budget for issued orders
     * @return the created town, or {@code null} if the owner has no units or the pool is exhausted
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    @Nullable ComputerTown startTown(int owner, int positionX, int positionY, int resourceArea, int instructionLimit) {
        int first = this.game.getUnitSystem().requireUnitPool().getFirstOwnerIndex(owner);

        if (first == 0) {
            return null;
        }

        ComputerTown existing = this.firstTowns[owner];

        if (existing == null) {
            this.military.releaseEmptyGuards(owner);
        }

        ComputerTown town = this.allocateTown(owner, positionX, positionY);

        if (town == null) {
            return null;
        }

        town.resourceArea        = resourceArea & BYTE_MASK;
        town.resourceAreaPending = resourceArea != 0;

        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = first; poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (!ComputerUnits.isDeadOrHidden(unit) && !ComputerUnits.isUnavailableForTown(unit) && unit.getComputerRecord() == null) {
                this.bindUnit(town, unit, instructionLimit);
            }
        }

        if (!this.getPlayer(owner).isCampaign()) {
            ComputerTerrain terrain = this.game.getComputerTerrain();

            terrain.activateTownRegions(this,
                                        owner,
                                        town.positionX, town.positionY,
                                        existing == null ? FIRST_TOWN_CHOKE_DEPTH : EXPANSION_CHOKE_DEPTH);
            terrain.defendTownChokes(this, owner, town.positionX, town.positionY);
        }

        return town;
    }

    /**
     * Takes the first free town, links it at the head of the owner's town list and initializes it.
     */
    private @Nullable ComputerTown allocateTown(int owner, int positionX, int positionY) {
        ComputerTown town = this.firstFreeTown;

        if (town == null) {
            return null;
        }

        this.firstFreeTown = town.next;

        if (town.next != null) {
            town.next.previous = null;
        }

        town.previous = null;
        town.next     = this.firstTowns[owner];

        if (town.next != null) {
            town.next.previous = town;
        }

        this.firstTowns[owner] = town;

        town.initialize(owner, positionX, positionY);

        return town;
    }

    /**
     * Links a worker or building record to the town. A completed Hatchery-family building restarts its creep spread, a
     * building activates its region, a resource depot becomes the town's depot and a refinery or geyser one of its gas
     * buildings.
     *
     * @param town             the town
     * @param unit             the unit
     * @param instructionLimit the iscript budget for issued orders
     */
    void bindUnit(@NotNull ComputerTown town, @NotNull Unit unit, int instructionLimit) {
        if (this.game.getComputerUnits().isWorker(unit)) {
            ComputerUnitRecord record = this.firstFreeWorkerRecord;

            if (record == null) {
                return;
            }

            this.firstFreeWorkerRecord = record.next;

            if (record.next != null) {
                record.next.previous = null;
            }

            record.previous = null;
            record.next     = town.firstWorker;

            if (record.next != null) {
                record.next.previous = record;
            }

            town.firstWorker        = record;
            record.workerAssignment = 1;
            record.unit             = unit;
            record.town             = town;
            record.lastUpdateClock  = this.getElapsedClockTicks();
            record.workerState      = 0;
            record.workerDelay      = 0;

            unit.setTargetResource(null, unit.getTargetResourceX(), unit.getTargetResourceY());
            unit.setComputerRecord(record);

            town.workerCount = (town.workerCount + 1) & BYTE_MASK;

            return;
        }

        if (!this.game.getComputerUnits().isTownBuilding(unit)) {
            return;
        }

        ComputerUnitRecord record = this.firstFreeBuildingRecord;

        if (record == null) {
            return;
        }

        this.firstFreeBuildingRecord = record.next;

        if (record.next != null) {
            record.next.previous = null;
        }

        record.previous = null;
        record.next     = town.firstBuilding;

        if (record.next != null) {
            record.next.previous = record;
        }

        town.firstBuilding = record;
        record.unit        = unit;
        record.town        = town;

        for (int slot = 0; slot < record.queueKinds.length; slot++) {
            record.queueKinds[slot]   = 0;
            record.queueTargets[slot] = null;
        }

        unit.setComputerRecord(record);

        int depotType = this.game.requireGameData().getBaseType(unit);

        if ((depotType == ScrUnitIds.ZERG_HATCHERY || depotType == ScrUnitIds.ZERG_LAIR || depotType == ScrUnitIds.ZERG_HIVE) &&
            (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.COMPUTER_AI, instructionLimit);
            ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.SPREAD_CREEP);
        }

        int owner = town.owner;
        boolean campaign = this.getPlayer(owner).isCampaign();
        int type = this.game.requireGameData().getBaseType(unit);

        if (!campaign || UnitQueries.isGroundedBuilding(unit) && type != ScrUnitIds.TERRAN_MISSILE_TURRET &&
                         type != ScrUnitIds.TERRAN_BUNKER && type != ScrUnitIds.ZERG_CREEP_COLONY &&
                         type != ScrUnitIds.ZERG_SPORE_COLONY && type != ScrUnitIds.ZERG_SUNKEN_COLONY &&
                         type != ScrUnitIds.PROTOSS_PYLON && type != ScrUnitIds.PROTOSS_PHOTON_CANNON) {
            this.activateBuildingRegion(owner, unit);
        }

        if (!town.buildingsLinked) {
            town.buildingsLinked = true;
            town.mineralTarget   = null;

            Arrays.fill(town.gasBuildings, null);
        }

        Player player = this.game.getPlayerTable().getPlayer(unit.getOwnerIndex());
        int race = player == null ? 0 : player.getRace();

        if (type == ComputerUnits.getResourceDepotType(race) || this.game.getUnitResourceSearch().isActiveDepot(unit)) {
            if (town.resourceDepot == null) {
                town.resourceDepot = unit;
            }

            return;
        }

        if (type != ComputerUnits.getRefineryType(race) && type != ScrUnitIds.RESOURCE_VESPENE_GEYSER) {
            return;
        }

        for (int index = 0; index < town.gasBuildings.length; index++) {
            if (town.gasBuildings[index] == null) {
                town.gasBuildings[index] = unit;

                break;
            }
        }

        if (town.resourceArea != 0) {
            town.resourceAreaPending = true;
        }
    }

    /**
     * Binds a worker or town building created or transformed by a computer-owned producer to the producer's town. A
     * producer without a record is ignored. A new Bunker gets empty Siege Tank and Ghost guards late enough, and the fourth
     * Missile Turret of a town or any later one gets an empty Ghost guard.
     * <p>
     * The common creation paths call this hook, Larva and starting Larva included.
     *
     * @param producer         the producing or transforming unit
     * @param unit             the created unit, or the producer itself for an in-place transformation
     * @param instructionLimit the iscript budget for issued orders
     * @throws IllegalStateException         if the unit has no sprite
     * @throws UnsupportedOperationException if the unit is a worker or a town building and the producer has a military or
     *                                       guard record
     */
    public void registerCreatedUnit(@NotNull Unit producer, @NotNull Unit unit, int instructionLimit) {
        Player owner = this.game.getPlayerTable().getPlayer(producer.getOwnerIndex());

        if (owner == null || owner.getType() != PlayerTypes.COMPUTER) {
            return;
        }

        ComputerTown town = this.bindToProducerTown(producer, unit, instructionLimit);

        if (town == null) {
            return;
        }

        int type = this.game.requireGameData().getBaseType(unit);

        if (type != ScrUnitIds.TERRAN_BUNKER && type != ScrUnitIds.TERRAN_MISSILE_TURRET) {
            return;
        }

        Sprite sprite = unit.requireSprite();
        int positionX = (short) sprite.getX();
        int positionY = (short) sprite.getY();

        if (type == ScrUnitIds.TERRAN_BUNKER) {
            // After 60 clock ticks a new Bunker gets empty guards for a Siege Tank and a Ghost.
            if (Integer.compareUnsigned(this.getElapsedClockTicks(), BUNKER_GUARD_START_CLOCK) > 0) {
                this.military.addEmptyGuard(unit.getOwnerIndex(), ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE, positionX, positionY);
                this.military.addEmptyGuard(unit.getOwnerIndex(), ScrUnitIds.TERRAN_GHOST, positionX, positionY);
            }

            return;
        }

        // A town with at least four Missile Turrets, built or in production, gets an empty Ghost guard.
        int turrets = 0;

        for (ComputerUnitRecord record = town.firstWorker; record != null; record = record.next) {
            turrets += this.game.requireGameData().getBaseType(record.requireUnit()) == ScrUnitIds.TERRAN_MISSILE_TURRET
                       ? 1
                       : this.game.getComputerUnits().countProducing(record.requireUnit(), ScrUnitIds.TERRAN_MISSILE_TURRET);
        }

        for (ComputerUnitRecord record = town.firstBuilding; record != null; record = record.next) {
            turrets += this.game.requireGameData().getBaseType(record.requireUnit()) == ScrUnitIds.TERRAN_MISSILE_TURRET
                       ? 1
                       : this.game.getComputerUnits().countProducing(record.requireUnit(), ScrUnitIds.TERRAN_MISSILE_TURRET);
        }

        if (turrets >= TURRET_GHOST_GUARD_COUNT) {
            this.military.addEmptyGuard(unit.getOwnerIndex(), ScrUnitIds.TERRAN_GHOST, positionX, positionY);
        }
    }

    /**
     * Registers a unit completed by a computer-owned producer. Workers join the producer's town, an Overlord follows
     * {@link #registerCreatedUnit}, a Broodling joins the military list of its region and other units take military
     * records.
     * <p>
     * Common code calls this for an Egg becoming a Drone or Overlord, Zerg birth, training, hallucinations and Spawn
     * Broodling.
     *
     * @param producer         the producing unit, or the unit itself for an in-place transformation
     * @param unit             the completed unit
     * @param instructionLimit the iscript budget for issued orders
     * @throws UnsupportedOperationException if the producer has a military or guard record and the unit is a worker, an
     *                                       Overlord or a unit that takes a record after transforming in place
     */
    public void registerCompletedUnit(@NotNull Unit producer, @NotNull Unit unit, int instructionLimit) {
        Player owner = this.game.getPlayerTable().getPlayer(producer.getOwnerIndex());

        if (owner == null || owner.getType() != PlayerTypes.COMPUTER) {
            return;
        }

        this.trackMedic(unit);

        if (this.game.getComputerUnits().isWorker(unit)) {
            this.bindToProducerTown(producer, unit, instructionLimit);

            return;
        }

        int type = this.game.requireGameData().getBaseType(unit);

        if (type == ScrUnitIds.ZERG_OVERLORD) {
            this.registerCreatedUnit(producer, unit, instructionLimit);

            return;
        }

        if (type == ScrUnitIds.ZERG_BROODLING) {
            // The Broodling joins the producer owner's record of its region, which starts attacking when idle.
            int index = this.regions.getIndex(producer.getOwnerIndex(), this.game.getComputerTerrain().getUnitRegion(unit));

            if (this.regions.getState(index) == ComputerRegionStates.IDLE) {
                this.regions.setState(this, index, ComputerRegionStates.ATTACK);
            }

            this.military.link(this, index, unit, instructionLimit);

            return;
        }

        this.military.register(this, producer, unit, instructionLimit);
    }

    /**
     * Applies the shared start of the created-unit and completed-unit hooks: releases an in-place unit's building record
     * and binds a worker or town building to the town in the producer record's town slot. Other units bind nothing and
     * leave the slot unread, so they pass for a producer with a military or guard record.
     *
     * @return the producer's town, or {@code null} if the producer has no record or the unit is neither a worker nor a town
     * building
     * @throws UnsupportedOperationException if the unit is a worker or a town building and the producer has a military or
     *                                       guard record
     */
    private @Nullable ComputerTown bindToProducerTown(@NotNull Unit producer, @NotNull Unit unit, int instructionLimit) {
        ComputerUnitRecord producerRecord = producer.getComputerRecord();

        if (producerRecord == null) {
            return null;
        }

        if (producer == unit) {
            this.releaseBuildingRecord(unit);
        }

        if (!this.game.getComputerUnits().isWorker(unit) && !this.game.getComputerUnits().isTownBuilding(unit)) {
            return null;
        }

        ComputerTown town = producerRecord.requireTown();

        this.bindUnit(town, unit, instructionLimit);

        return town;
    }

    /**
     * Releases the building record of a unit transformed in place, as the shared prologue of the created-unit,
     * completed-unit and military registration hooks: resource references are cleared, the record returns to the
     * building free list and the town forgets the unit as reserved worker and depot.
     *
     * @param unit the transformed unit
     * @throws UnsupportedOperationException if the unit has a military or guard record
     */
    void releaseBuildingRecord(@NotNull Unit unit) {
        this.releaseResourceReferences(unit);

        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null) {
            return;
        }

        ComputerTown town = record.requireTown();

        this.unlinkBuildingRecord(record);

        if (unit == town.reservedWorker) {
            town.reservedWorker = null;
        }

        if (unit == town.resourceDepot) {
            town.resourceDepot = null;
        }

        unit.setComputerRecord(null);
    }

    /**
     * Gives the computer AI a unit that no computer producer made: the unit creation wrapper (trigger Create Unit and
     * aiscript {@code create_unit}) calls it for every created unit and the owner change for the new owner. For an owner
     * below {@link PlayerTable#PLAYABLE_PLAYER_SLOTS}, a Medic is tracked; a type with the {@code units.dat} AI Internal flag
     * {@link UnitComputerFlags#TOWN_UNIT} joins the first town of its computer owner; any other type of a computer owner
     * becomes a guard of its current position.
     *
     * @param unit             the unit
     * @param instructionLimit the iscript budget for issued orders
     * @throws NullPointerException if the world was built without game data
     */
    public void adoptUnit(@NotNull Unit unit, int instructionLimit) {
        int owner = unit.getOwnerIndex();

        if (owner >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return;
        }

        this.trackMedic(unit);

        if ((this.game.requireGameData().getUnitTypes().getComputerInternalFlags(unit.getUnitId()) & UnitComputerFlags.TOWN_UNIT) == 0) {
            this.military.addGuard(unit);

            return;
        }

        Player player = this.game.getPlayerTable().getPlayer(owner);
        ComputerTown town = this.firstTowns[owner];

        if (player != null && player.getType() == PlayerTypes.COMPUTER && town != null) {
            this.bindUnit(town, unit, instructionLimit);
        }
    }

    /**
     * Tracks the first Medic of a computer owner in its player record.
     */
    private void trackMedic(@NotNull Unit unit) {
        Player player = this.game.getPlayerTable().getPlayer(unit.getOwnerIndex());

        if (player != null && player.getType() == PlayerTypes.COMPUTER && this.game.requireGameData().getBaseType(unit) == ScrUnitIds.TERRAN_MEDIC &&
            this.getPlayer(unit.getOwnerIndex()).getMedic() == null) {
            this.getPlayer(unit.getOwnerIndex()).setMedic(unit);
        }
    }

    /**
     * Rebinds a worker whose record must be recreated, such as a Drone becoming a building.
     *
     * @param unit             the unit
     * @param instructionLimit the iscript budget for issued orders
     */
    public void rebindWorker(@NotNull Unit unit, int instructionLimit) {
        Player owner = this.game.getPlayerTable().getPlayer(unit.getOwnerIndex());
        ComputerUnitRecord record = unit.getComputerRecord();

        if (owner == null || owner.getType() != PlayerTypes.COMPUTER || record == null) {
            return;
        }

        ComputerTown town = record.requireTown();

        unit.setComputerRecord(null);
        this.unlinkWorkerRecord(record);

        town.workerCount = (town.workerCount - 1) & BYTE_MASK;

        this.bindUnit(town, unit, instructionLimit);
    }

    /**
     * Rebinds a building whose record must be recreated.
     *
     * @param unit             the unit
     * @param instructionLimit the iscript budget for issued orders
     */
    public void rebindBuilding(@NotNull Unit unit, int instructionLimit) {
        Player owner = this.game.getPlayerTable().getPlayer(unit.getOwnerIndex());
        ComputerUnitRecord record = unit.getComputerRecord();

        if (owner == null || owner.getType() != PlayerTypes.COMPUTER || record == null) {
            return;
        }

        ComputerTown town = record.requireTown();

        unit.setComputerRecord(null);
        this.unlinkBuildingRecord(record);
        this.bindUnit(town, unit, instructionLimit);
    }

    /**
     * Releases every computer relation of a dying unit or a unit changing owner: the region references, the town record,
     * the guard and the tracked Medic.
     * <p>
     * Common code calls this for a dying unit with {@code death} set and for an owner change without it.
     *
     * @param unit  the unit
     * @param death whether the unit is dying
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    public void removeUnit(@NotNull Unit unit, boolean death) {
        if (this.players[0] == null) {
            return;
        }

        this.releaseRegionReferences(unit, death);
        this.releaseTownRecord(unit);
        ComputerMilitary.releaseGuard(unit);
        this.handOverMedic(unit);

        unit.setComputerRecord(null);
    }

    /**
     * Passes the tracked Medic of a leaving Medic's owner to the first Medic of the owner list, or to none. An owner outside
     * the computer player records has no tracked Medic, so its Medics change nothing.
     */
    private void handOverMedic(@NotNull Unit unit) {
        int owner = unit.getOwnerIndex();

        if (this.game.requireGameData().getBaseType(unit) != ScrUnitIds.TERRAN_MEDIC || owner >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return;
        }

        ComputerPlayer player = this.getPlayer(owner);

        if (player.getMedic() != unit) {
            return;
        }

        Unit next = null;
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            if (this.game.requireGameData().getBaseType(pool.getUnit(poolIndex)) == ScrUnitIds.TERRAN_MEDIC) {
                next = pool.getUnit(poolIndex);

                break;
            }
        }

        player.setMedic(next);
    }

    /**
     * Clears region references and removes an enemy's value from required strengths; a military record leaves its
     * region, which may want a defender after a death.
     */
    private void releaseRegionReferences(@NotNull Unit unit, boolean death) {
        for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
            Player player = this.game.getPlayerTable().getPlayer(owner);

            if (player == null || player.getType() != PlayerTypes.COMPUTER) {
                continue;
            }

            this.regions.releaseUnit(owner, unit);

            if (!this.game.getComputerUnits().isEnemy(owner, unit)) {
                continue;
            }

            int index = this.regions.getIndex(owner, this.game.getComputerTerrain().getUnitRegion(unit));
            int type = unit.getUnitId();
            int air = this.getAirValue(type);
            int required = this.regions.getRequiredAirStrength(index);

            this.regions.setRequiredAirStrength(index, Integer.compareUnsigned(air, required) >= 0 ? 0 : required - air);

            int ground = this.getGroundValue(type);

            required = this.regions.getRequiredGroundStrength(index);

            this.regions.setRequiredGroundStrength(index, Integer.compareUnsigned(ground, required) < 0 ? required - ground : 0);
        }

        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null || record.getKind() != ComputerUnitRecord.KIND_MILITARY) {
            return;
        }

        int index = record.regionIndex;
        int regionState = this.regions.getState(index);

        if (regionState == ComputerRegionStates.ATTACK_GATHERING) {
            // The group entry of the lost unit is marked; the death branch below is skipped.
            this.getPlayer(unit.getOwnerIndex()).releaseAttackUnit(this.game.requireGameData().getBaseType(unit));
        } else if (death && regionState > ComputerRegionStates.ATTACK_ENGAGED && regionState != ComputerRegionStates.ATTACK_LAUNCHED) {
            int region = this.regions.getRegion(index);

            if (this.game.getComputerTerrain().getUnitRegion(unit) != region &&
                !this.game.getComputerTerrain().hasWorkerOrBuilding(this.regions.getOwner(index), region)) {
                this.regions.setState(this, index, ComputerRegionStates.DEFENDER_WANTED);
            }
        }

        this.military.unlink(this, record);
    }

    /**
     * Releases the town record of a worker or town building without the transfer flag, and dissolves a town left empty.
     */
    private void releaseTownRecord(@NotNull Unit unit) {
        this.releaseResourceReferences(unit);

        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null) {
            return;
        }

        boolean townBuilding = this.game.getComputerUnits().isTownBuilding(unit);

        if (!townBuilding && !this.game.getComputerUnits().isWorker(unit)) {
            return;
        }

        ComputerTown town = record.requireTown();

        if (!townBuilding) {
            this.unlinkWorkerRecord(record);

            town.workerCount = (town.workerCount - 1) & BYTE_MASK;
        } else {
            this.unlinkBuildingRecord(record);
        }

        if (unit == town.reservedWorker) {
            town.reservedWorker = null;
        }

        if (unit == town.resourceDepot) {
            town.resourceDepot = null;
        }

        this.dissolveIfEmpty(town);
        unit.setComputerRecord(null);
    }

    /**
     * Releases a town without a building other than Overlords: a town left with only workers or Overlords hands them
     * to the nearest other town of the owner and is released; an empty town is released directly. Release stops the
     * town's script records, clears every resource claim, unmarks its expansion area and returns the town to the free
     * list.
     *
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool or the resource areas are not initialized
     */
    private void dissolveIfEmpty(@NotNull ComputerTown town) {
        if (this.mergingTown) {
            return;
        }

        if (town.firstBuilding != null || town.firstWorker != null) {
            for (ComputerUnitRecord building = town.firstBuilding; building != null; building = building.next) {
                if (this.game.requireGameData().getBaseType(building.requireUnit()) != ScrUnitIds.ZERG_OVERLORD) {
                    return;
                }
            }

            ComputerTown nearest = null;
            int best = NO_DISTANCE;

            for (ComputerTown other = this.firstTowns[town.owner]; other != null; other = other.next) {
                if (other == town) {
                    continue;
                }

                int distance = RegionPathSearch.calculateDistance((short) town.positionX, (short) other.positionX,
                                                                  (short) town.positionY, (short) other.positionY);

                if (Integer.compareUnsigned(distance, best) < 0) {
                    nearest = other;
                    best    = distance;
                }
            }

            if (nearest == null) {
                return;
            }

            this.mergingTown = true;

            for (ComputerUnitRecord record = town.firstBuilding; town.firstBuilding != null && record != null; ) {
                ComputerUnitRecord next = record.next;
                Unit unit = record.requireUnit();

                this.removeUnit(unit, false);
                this.bindUnit(nearest, unit, this.instructionLimit);

                record = next;
            }

            for (ComputerUnitRecord record = town.firstWorker; town.firstWorker != null && record != null; ) {
                ComputerUnitRecord next = record.next;
                Unit unit = record.requireUnit();

                this.removeUnit(unit, false);
                this.bindUnit(nearest, unit, this.instructionLimit);

                record = next;
            }

            this.mergingTown = false;
        }

        if (this.scripts != null) {
            this.scripts.releaseTown(town);
        }

        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        this.getResources();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (!ComputerUnits.isDeadOrHidden(unit) && (units.getFlags(unit.getUnitId()) & UnitTypeFlags.RESOURCE_CONTAINER) != 0) {
                unit.setResourceClaimedByComputer(false);
            }
        }

        if (town.resourceArea != 0) {
            this.game.requireResourceAreas().clearExpansion(town.resourceArea);
        }

        int owner = town.owner;

        if (this.firstTowns[owner] == town) {
            this.firstTowns[owner] = town.next;
        }

        if (town.previous != null) {
            town.previous.next = town.next;
        }

        if (town.next != null) {
            town.next.previous = town.previous;
        }

        town.previous = null;
        town.next     = this.firstFreeTown;

        if (this.firstFreeTown != null) {
            this.firstFreeTown.previous = town;
        }

        this.firstFreeTown = town;
    }

    /**
     * Clears every town reference to a resource container.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private void releaseResourceReferences(@NotNull Unit unit) {
        if ((this.game.requireGameData().getUnitTypes().getFlags(unit.getUnitId()) & UnitTypeFlags.RESOURCE_CONTAINER) == 0) {
            return;
        }

        for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
            for (ComputerTown town = this.firstTowns[owner]; town != null; town = town.next) {
                if (town.mineralTarget == unit) {
                    town.mineralTarget = null;
                }

                for (int slot = 0; slot < town.gasBuildings.length; slot++) {
                    if (town.gasBuildings[slot] == unit) {
                        town.gasBuildings[slot] = null;
                    }
                }

                if (town.resourceArea != 0) {
                    town.resourceAreaPending = true;
                }
            }
        }
    }

    /**
     * Returns a worker record to the free-list head.
     */
    private void unlinkWorkerRecord(@NotNull ComputerUnitRecord record) {
        ComputerTown town = record.requireTown();

        if (town.firstWorker == record) {
            town.firstWorker = record.next;
        }

        if (record.previous != null) {
            record.previous.next = record.next;
        }

        if (record.next != null) {
            record.next.previous = record.previous;
        }

        record.previous = null;
        record.next     = this.firstFreeWorkerRecord;

        if (this.firstFreeWorkerRecord != null) {
            this.firstFreeWorkerRecord.previous = record;
        }

        this.firstFreeWorkerRecord = record;
    }

    /**
     * Returns a building record to the free-list head.
     */
    private void unlinkBuildingRecord(@NotNull ComputerUnitRecord record) {
        ComputerTown town = record.requireTown();

        if (town.firstBuilding == record) {
            town.firstBuilding = record.next;
        }

        if (record.previous != null) {
            record.previous.next = record.next;
        }

        if (record.next != null) {
            record.next.previous = record.previous;
        }

        record.previous = null;
        record.next     = this.firstFreeBuildingRecord;

        if (this.firstFreeBuildingRecord != null) {
            this.firstFreeBuildingRecord.previous = record;
        }

        this.firstFreeBuildingRecord = record;
    }

    /**
     * Applies aiscript {@code start_areatown}: the owner's units in the rectangle found a town when there is at least
     * one. Every unit without a computer record is bound; the town stands at the last resource depot among them, else
     * the first worker, else the rectangle center. Unlike {@link #startTown} no guard release, region activation or
     * choke defense follows.
     *
     * @param owner            the owner
     * @param left             the rectangle left DWORD
     * @param top              the rectangle top DWORD
     * @param right            the rectangle right DWORD
     * @param bottom           the rectangle bottom DWORD
     * @param resourceArea     the script flags shifted right by three
     * @param instructionLimit the iscript budget for issued orders
     * @return the created town, or {@code null} without units or with the pool exhausted
     * @throws IllegalStateException if the anchoring resource depot or worker has no sprite
     */
    @Nullable ComputerTown startAreaTown(int owner, int left, int top, int right, int bottom, int resourceArea, int instructionLimit) {
        Unit[] found = this.game.getComputerTerrain().collectRectangle(left, top, right, bottom);
        int count = 0;

        for (Unit unit : found) {
            if (unit.getOwnerIndex() == owner) {
                found[count++] = unit;
            }
        }

        if (count == 0) {
            return null;
        }

        ComputerTown town = this.allocateTown(owner, 0, 0);

        if (town == null) {
            return null;
        }

        town.resourceArea        = resourceArea & BYTE_MASK;
        town.resourceAreaPending = resourceArea != 0;

        Unit anchor = null;

        for (int index = 0; index < count; index++) {
            Unit unit = found[index];

            if (unit.getComputerRecord() != null) {
                continue;
            }

            this.bindUnit(town, unit, instructionLimit);

            int type = ScrUnitIds.requireValid(this.game.requireGameData().getBaseType(unit.getUnitId()));

            switch (type) {
                case ScrUnitIds.TERRAN_SCV, ScrUnitIds.ZERG_DRONE, ScrUnitIds.PROTOSS_PROBE -> {
                    if (anchor == null) {
                        anchor = unit;
                    }
                }
                case ScrUnitIds.TERRAN_COMMAND_CENTER, ScrUnitIds.ZERG_HATCHERY, ScrUnitIds.ZERG_LAIR, ScrUnitIds.ZERG_HIVE,
                     ScrUnitIds.PROTOSS_NEXUS -> anchor = unit;
                default -> {
                }
            }
        }

        if (anchor == null) {
            town.positionX = (short) ((short) ((right - left) / TOWN_CENTER_DIVISOR) + (short) left);
            town.positionY = (short) ((short) ((bottom - top) / TOWN_CENTER_DIVISOR) + (short) top);
        } else {
            Sprite sprite = anchor.requireSprite();

            town.positionX = (short) sprite.getX();
            town.positionY = (short) sprite.getY();
        }

        return town;
    }

    /**
     * Applies aiscript {@code switch_rescue}: the owner becomes a rescuable player, allied with all twelve players in
     * both directions, and every live unit of the owner leaves its computer relations without death and receives
     * RescuePassive.
     *
     * @param owner            the script owner
     * @param instructionLimit the iscript budget for issued orders
     * @throws UnsupportedOperationException if the game has no player relations or the unit pool is not initialized
     */
    void switchToRescue(int owner, int instructionLimit) {
        this.game.getPlayerTable().requirePlayer(owner).setType(PlayerTypes.RESCUE_PASSIVE);

        PlayerRelations relations = this.game.getPlayerTable().getPlayerRelations();

        if (relations == null) {
            throw Exceptions.newUnsupportedOperationException("Computer rescue switching requires player relations.");
        }

        for (int player = 0; player < PlayerTable.PLAYER_SLOTS; player++) {
            relations.setAlliance(owner, player, PlayerRelations.ALLIED);
            relations.setAlliance(player, owner, PlayerRelations.ALLIED);
        }

        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit)) {
                continue;
            }

            this.removeUnit(unit, false);
            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.RESCUE_PASSIVE, instructionLimit);
            unit.setComputerRecord(null);
        }
    }

    /**
     * Applies aiscript {@code move_dt}: every live Dark Templar and Dark Templar hero of the owner leaves its computer
     * relations without death and joins the region at the position.
     *
     * @param owner            the script owner
     * @param positionX        the script center X
     * @param positionY        the script center Y
     * @param instructionLimit the iscript budget for issued orders
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    void moveDarkTemplars(int owner, int positionX, int positionY, int instructionLimit) {
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit)) {
                continue;
            }

            int type = this.game.requireGameData().getBaseType(unit);

            if (type != ScrUnitIds.HERO_DARK_TEMPLAR && type != ScrUnitIds.PROTOSS_DARK_TEMPLAR) {
                continue;
            }

            if (unit.getComputerRecord() != null) {
                this.removeUnit(unit, false);
            }

            if (unit.getOwnerIndex() < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                int index = this.regions.getIndex(unit.getOwnerIndex(), this.game.getComputerTerrain().getRegion(positionX, positionY));

                this.military.addToRegion(this, index, unit, true, instructionLimit);
            }
        }
    }

    /**
     * Activates the building's region as a town region, marks the allied regions, and seeds the required strength of a
     * depot region.
     *
     * @param owner the building owner
     * @param unit  the building
     */
    void activateBuildingRegion(int owner, @NotNull Unit unit) {
        int region = this.game.getComputerTerrain().getUnitRegion(unit);
        int index = this.regions.getIndex(owner, region);

        if ((this.regions.getState(index) & NON_TOWN_STATE_MASK) == 0) {
            this.regions.setState(this, index, ComputerRegionStates.TOWN);
            this.regions.setFlags(index, this.regions.getFlags(index) | ComputerRegionStates.FLAG_TOWN);

            if (!this.getPlayer(owner).isCampaign()) {
                this.regions.setFlags(index, this.regions.getFlags(index) | ComputerRegionStates.FLAG_KEEP_REQUIRED_STRENGTH);
            }

            PlayerRelations relations = this.game.getPlayerTable().requirePlayerRelations();

            for (int ally = 0; ally < PlayerTable.PLAYABLE_PLAYER_SLOTS; ally++) {
                if (this.getPlayer(ally).isCampaign() || relations.getAlliance(ally, owner) == PlayerRelations.ENEMY) {
                    continue;
                }

                int allyIndex = this.regions.getIndex(ally, region);

                if (this.regions.getState(allyIndex) == ComputerRegionStates.IDLE) {
                    this.regions.setState(this, allyIndex, ComputerRegionStates.ALLY_TOWN);
                }
            }
        }

        Player player = this.game.getPlayerTable().getPlayer(owner);
        int race = player == null ? 0 : player.getRace();

        if (!this.game.getUnitResourceSearch().isActiveDepot(unit) &&
            this.game.requireGameData().getBaseType(unit) != ComputerUnits.getResourceDepotType(race)) {
            return;
        }

        if (this.getPlayer(owner).isCampaign()) {
            // Both required strengths become six times the strength scale before the transition to the under-attack
            // state; the cooldown is the same as below.
            int required = this.getPlayer(owner).getRegionStrengthScale() * CAMPAIGN_DEPOT_STRENGTH_FACTOR & WORD_MASK;

            this.regions.setRequiredGroundStrength(index, required);
            this.regions.setRequiredAirStrength(index, required);
            this.regions.setState(this, index, ComputerRegionStates.UNDER_ATTACK);
            this.regions.setCooldown(index, DEPOT_REGION_COOLDOWN);

            return;
        }

        this.regions.setState(this, index, ComputerRegionStates.UNDER_ATTACK);
        this.regions.setFlags(index, this.regions.getFlags(index) | ComputerRegionStates.FLAG_KEEP_REQUIRED_STRENGTH);
        this.regions.setRequiredGroundStrength(index, DEPOT_REQUIRED_GROUND_STRENGTH);
        this.regions.setRequiredAirStrength(index, DEPOT_REQUIRED_AIR_STRENGTH);
        this.regions.setCooldown(index, DEPOT_REGION_COOLDOWN);
    }

    /**
     * Adds a request to a town of a computer owner; other owners add nothing.
     *
     * @param town     the town
     * @param kind     the request kind
     * @param count    the requested count
     * @param unitType the requested type
     * @param priority the request priority
     * @param flag     the request flag
     */
    void addTownRequest(@NotNull ComputerTown town, int kind, int count, int unitType, int priority, int flag) {
        Player player = this.game.getPlayerTable().getPlayer(town.owner);

        if (player == null || player.getType() != PlayerTypes.COMPUTER) {
            return;
        }

        town.addRequest(kind, count, unitType, priority, flag);
    }

    /**
     * Returns the race of the nearest enemy grounded building, or Terran without one.
     *
     * @param town the town whose region center is the search origin
     * @return the race id
     * @throws UnsupportedOperationException if the nearest building's owner has no player record or the unit pool is not initialized
     * @throws IllegalStateException         if the unit has no sprite
     */
    int findEnemyRace(@NotNull ComputerTown town) {
        int region = this.game.getComputerTerrain().getRegion(town.positionX, town.positionY);
        int centerX = this.game.getComputerTerrain().getRegions().getCenterX(region);
        int centerY = this.game.getComputerTerrain().getRegions().getCenterY(region);
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        Unit nearest = null;
        int nearestDistance = NO_DISTANCE;

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || !UnitQueries.isGroundedBuilding(unit) ||
                !this.game.getComputerUnits().isEnemy(town.owner, unit)) {
                continue;
            }

            Sprite sprite = unit.requireSprite();
            int distance = RegionPathSearch.calculateDistance(centerX, (short) sprite.getX(), centerY, (short) sprite.getY());

            if (Integer.compareUnsigned(distance, nearestDistance) < 0) {
                nearest         = unit;
                nearestDistance = distance;
            }
        }

        if (nearest == null) {
            return ScrRaceIds.TERRAN;
        }

        Player owner = this.game.getPlayerTable().getPlayer(nearest.getOwnerIndex());

        if (owner == null) {
            throw Exceptions.newUnsupportedOperationException("Computer enemy race lookup requires the unit owner's player record.");
        }

        return owner.getRace();
    }

    /**
     * Requests a unit-status refresh at the next refresh step.
     */
    public void requestUnitStatusRefresh() {
        this.unitStatusRefreshRequested = 1;
    }

    /**
     * Registers a preplaced map unit of a computer player as a guard, as the preplaced unit constructor does after a
     * successful completion. Map units are created before this phase exists; replaying the registrations in creation
     * order on the fresh guard pools gives the same records.
     * <p>
     * A missing or noncomputer player returns before the data read.
     *
     * @param unit the completed preplaced unit
     */
    public void registerPreplacedUnit(@NotNull Unit unit) {
        this.military.addGuard(unit);
    }

    /**
     * Includes the scheduler counters and regional strengths in the state hash.
     *
     * @param hash the preceding diagnostic hash
     * @return the hash including this context
     */
    public int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + this.regionCount;
        hash = HASH_MULTIPLIER * hash + this.playerUpdateCountdown;
        hash = HASH_MULTIPLIER * hash + this.unitStatusRefreshCountdown;
        hash = HASH_MULTIPLIER * hash + this.unitStatusRefreshCycle;
        hash = HASH_MULTIPLIER * hash + this.unitStatusRefreshRequested;
        hash = HASH_MULTIPLIER * hash + this.regionalStrengthPlayerCursor;
        hash = HASH_MULTIPLIER * hash + this.regionalStrengthRegionCursor;
        hash = HASH_MULTIPLIER * hash + this.playerUpdateCursor;
        hash = HASH_MULTIPLIER * hash + this.townUpdatePlayerCursor;

        for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
            for (int region = 0; region < this.regionCount; region++) {
                hash = HASH_MULTIPLIER * hash + this.regions.getEnemyStrength(this.regions.getIndex(owner, region));
            }
        }

        if (this.hasComputerPlayers) {
            hash = this.regions.computeStateHash(hash);
            hash = this.game.getComputerTerrain().computeStateHash(hash);
            hash = HASH_MULTIPLIER * hash + this.lastError;

            if (this.scripts != null) {
                hash = this.scripts.computeStateHash(hash);
            }

            hash = this.military.computeStateHash(hash);

            for (ComputerPlayer player : this.players) {
                hash = player == null ? HASH_MULTIPLIER * hash : player.computeStateHash(hash);
            }

            for (ComputerTown town : this.towns) {
                hash = town.computeStateHash(hash);
            }

            for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
                hash = HASH_MULTIPLIER * hash + (this.firstTowns[owner] == null ? -1 : this.firstTowns[owner].getPoolIndex());
            }

            hash = HASH_MULTIPLIER * hash + (this.firstFreeTown == null ? -1 : this.firstFreeTown.getPoolIndex());
            hash = HASH_MULTIPLIER * hash + (this.mergingTown ? 1 : 0);
            hash = HASH_MULTIPLIER * hash + (this.spellSearchForced ? 1 : 0);
            hash = HASH_MULTIPLIER * hash + (this.firstFreeWorkerRecord == null ? -1 : this.firstFreeWorkerRecord.getPoolIndex());
            hash = HASH_MULTIPLIER * hash + (this.firstFreeBuildingRecord == null ? -1 : this.firstFreeBuildingRecord.getPoolIndex());
        }

        return hash;
    }
}
