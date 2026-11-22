package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerRelations;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.save.SaveReferences;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegions;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Retains the per-player regional records of the computer players and advances them through their states
 * ({@link ComputerRegionStates}).
 * <p>
 * Each owner has one record per region, stored player-major. Record fields keep the widths of the original record: the
 * linked region WORD, the state, state timer, cooldown, flags and retry BYTEs, the required ground and air strength
 * WORDs, the region and assigned strength WORDs, the enemy air and ground strength WORDs, the two selected targets and
 * the military record list.
 */
final class ComputerRegions {
    /**
     * Melee owners keep at least this required strength for an inaccessible or defense-line region.
     */
    static final int MINIMUM_REQUIRED_STRENGTH = 1000;
    /**
     * The size of a saved region record.
     */
    static final int SAVED_RECORD_SIZE = 0x34;

    /**
     * The scout candidate buffer: four buckets of {@code 16} neighbors (unscouted towns, scouted towns, unscouted others,
     * scouted others).
     */
    private static final int SCOUT_BUCKET_SIZE = 16;
    private static final int SCOUTED_TOWNS = SCOUT_BUCKET_SIZE;
    private static final int UNSCOUTED_OTHERS = 2 * SCOUT_BUCKET_SIZE;
    private static final int SCOUTED_OTHERS = 3 * SCOUT_BUCKET_SIZE;
    private static final int SCOUT_CANDIDATE_CAPACITY = 4 * SCOUT_BUCKET_SIZE;
    /**
     * The random call site of the scout region choice.
     */
    private static final int SCOUT_RANDOM_SOURCE = 0x37;
    /**
     * The list buffer passed to {@link ComputerAttack#countMissing}.
     */
    private static final int MISSING_TYPE_CAPACITY = 64;

    /**
     * The defense value of an empty defense list or of a town region smaller than {@link #SMALL_TOWN_TILES}.
     */
    private static final int UNAVAILABLE_DEFENSE = 999;
    private static final int SMALL_TOWN_TILES = 16;
    /**
     * The half size of the campaign unit query around a region center, in pixels.
     */
    private static final int CAMPAIGN_QUERY_RADIUS = 0x100;
    /**
     * The passes that claim the expansions: all regions, then the regions below {@link #SMALL_REGION_TILES}.
     */
    private static final int CAPTURE_PASSES = 2;
    private static final int SMALL_REGION_TILES = 0xB;

    /**
     * Melee owners lose this much required strength per step; campaign owners {@code 13} above {@code 1000},
     * {@code 6} above {@code 200}, then {@code 2}.
     */
    private static final int MELEE_DECAY = 300;
    private static final int CAMPAIGN_FAST_DECAY_ABOVE = 1000;
    private static final int CAMPAIGN_FAST_DECAY = 13;
    private static final int CAMPAIGN_DECAY_ABOVE = 200;
    private static final int CAMPAIGN_DECAY = 6;
    private static final int CAMPAIGN_SLOW_DECAY = 2;

    /**
     * The state timer BYTE a state change sets; it also serves as the region's request priority. The default
     * (also of the idle and defender-wanted states) and the values of the other states.
     */
    private static final int TIMER_DEFAULT = 0x0A;
    private static final int TIMER_DEFENDED_IDLE = 0x28;
    private static final int TIMER_ALLY_TOWN = 0x28;
    private static final int TIMER_TOWN = 0x32;
    private static final int TIMER_ATTACK = 0xA0;
    private static final int TIMER_CAMPAIGN_ATTACK = 0x46;
    private static final int TIMER_CAMPAIGN_GATHERING = 0x32;
    private static final int TIMER_UNDER_ATTACK = 0x64;
    private static final int TIMER_UNDER_ATTACK_OCCUPIED = 0x6E;
    /**
     * The timer of a defending region whose regions have no target.
     */
    private static final int TIMER_QUIET_DEFENDING = 0x32;

    /**
     * A defending region waits this many updates for the strength its linked region requires.
     */
    private static final int DEFENDING_RETRIES = 10;
    /**
     * The cooldown of a region a defender just reinforced.
     */
    private static final int REINFORCED_COOLDOWN = 5;
    /**
     * The ground military records at which an attack region becomes engaged.
     */
    private static final int ENGAGED_MILITARY_COUNT = 0x14;
    /**
     * The distance from the region center within which a patrolling unit stops.
     */
    private static final int PATROL_ARRIVAL_DISTANCE = 0x160;
    /**
     * The regions that request a Bunker or a Missile Turret at most.
     */
    private static final int BUNKER_REGION_LIMIT = 3;
    private static final int TURRET_REGION_LIMIT = 6;

    /**
     * The priorities of the missing units of a gathering attack: urgent for a melee owner without a recent building hit.
     */
    private static final int GATHERING_URGENT_PRIORITY = 0xA0;
    private static final int GATHERING_PRIORITY = 0x3C;
    /**
     * The Observers a Protoss owner with transports keeps, and their priority.
     */
    private static final int OBSERVER_COUNT = 2;
    private static final int OBSERVER_PRIORITY = 0x50;
    /**
     * The transports a campaign and a melee owner keep, and their priorities.
     */
    private static final int CAMPAIGN_TRANSPORT_COUNT = 2;
    private static final int TRANSPORT_COUNT = 5;
    private static final int FIRST_TRANSPORT_PRIORITY = 0x50;
    private static final int TRANSPORT_PRIORITY = 0x32;

    /**
     * The military transfer filters: every unit, transferable attackers, units outside Bunkers.
     */
    private static final int TRANSFER_ALL = 0;
    private static final int TRANSFER_ATTACKERS = 1;
    private static final int TRANSFER_UNBUNKERED = 2;

    /**
     * The owner stride of a saved region record reference: one-based region plus owner times the region capacity.
     */
    private static final int SAVED_REFERENCE_OWNER_STRIDE = ComputerPlayerPhase.REGION_CAPACITY;
    private static final int SAVED_REGION = 0x00;
    private static final int SAVED_LINKED_REGION = 0x02;
    private static final int SAVED_OWNER = 0x04;
    private static final int SAVED_STATE = 0x05;
    private static final int SAVED_STATE_TIMER = 0x06;
    private static final int SAVED_COOLDOWN = 0x07;
    private static final int SAVED_FLAGS = 0x08;
    private static final int SAVED_RETRIES = 0x09;
    private static final int SAVED_MILITARY_COUNT = 0x0A;
    private static final int SAVED_REQUIRED_GROUND = 0x0C;
    private static final int SAVED_REQUIRED_AIR = 0x0E;
    private static final int SAVED_REGION_GROUND = 0x10;
    private static final int SAVED_REGION_AIR = 0x12;
    private static final int SAVED_ASSIGNED_GROUND = 0x14;
    private static final int SAVED_ASSIGNED_AIR = 0x16;
    private static final int SAVED_ENEMY_AIR = 0x18;
    private static final int SAVED_ENEMY_GROUND = 0x1A;
    private static final int SAVED_AIR_TARGET = 0x1C;
    private static final int SAVED_GROUND_TARGET = 0x20;
    private static final int SAVED_LEADER = 0x24;
    private static final int SAVED_PATROL_UNIT = 0x28;
    private static final int SAVED_FIRST_MILITARY = 0x30;

    /**
     * Truncates a value to a BYTE.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The low WORD of a packed position: its horizontal coordinate.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The multiplier of each state-hash step.
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * Tests whether a region state keeps its patrol unit: the states {@code 1}, {@code 2} and {@code 7..9}. The state test
     * is an unsigned byte compare after subtracting {@code 7}.
     */
    private static boolean canKeepPatrol(int mode) {
        return mode != ComputerRegionStates.IDLE &&
               (mode <= ComputerRegionStates.ATTACK_ENGAGED ||
                (mode - ComputerRegionStates.DEFENDING & BYTE_MASK) <= ComputerRegionStates.ATTACK_LAUNCHED - ComputerRegionStates.DEFENDING);
    }

    /**
     * Returns a required strength after one decay step, never below zero.
     */
    private static int decay(int value, boolean campaign) {
        if (!campaign) {
            return value > MELEE_DECAY ? value - MELEE_DECAY : 0;
        }

        if (value > CAMPAIGN_FAST_DECAY_ABOVE) {
            return value - CAMPAIGN_FAST_DECAY;
        }

        if (value > CAMPAIGN_DECAY_ABOVE) {
            return value - CAMPAIGN_DECAY;
        }

        if (value > CAMPAIGN_SLOW_DECAY) {
            return value - CAMPAIGN_SLOW_DECAY;
        }

        return 0;
    }


    private final @NotNull Game game;

    private final int regionCount;
    private final short @NotNull [] linkedRegions;

    private final byte @NotNull [] states;
    private final byte @NotNull [] stateTimers;
    private final byte @NotNull [] cooldowns;
    private final byte @NotNull [] flags;
    private final byte @NotNull [] retries;

    private final short @NotNull [] requiredGroundStrength;
    private final short @NotNull [] requiredAirStrength;
    private final short @NotNull [] regionGroundStrength;
    private final short @NotNull [] regionAirStrength;
    private final short @NotNull [] assignedGroundStrength;
    private final short @NotNull [] assignedAirStrength;
    private final short @NotNull [] enemyAirStrength;
    private final short @NotNull [] enemyGroundStrength;

    private final @Nullable Unit @NotNull [] airTargets;
    private final @Nullable Unit @NotNull [] groundTargets;
    private final @Nullable Unit @NotNull [] patrolUnits;
    /**
     * The slowest unit of the region's military, chosen as leader (campaign owners only).
     */
    private final @Nullable Unit @NotNull [] leaders;
    private final @Nullable ComputerUnitRecord @NotNull [] firstMilitaryRecords;
    /**
     * The number of non-flying units in the military list.
     */
    private final short @NotNull [] militaryCounts;
    private final int @NotNull [] scoutCandidates;

    /**
     * Creates a {@link ComputerRegions} instance with idle records for every playable owner and region.
     *
     * @param game        the game these records belong to
     * @param regionCount the region count of the records of one owner
     */
    ComputerRegions(@NotNull Game game, int regionCount) {
        int count = regionCount * PlayerTable.PLAYABLE_PLAYER_SLOTS;

        this.game = game;

        this.regionCount   = regionCount;
        this.linkedRegions = new short[count];

        this.states      = new byte[count];
        this.stateTimers = new byte[count];
        this.cooldowns   = new byte[count];
        this.flags       = new byte[count];
        this.retries     = new byte[count];

        this.requiredGroundStrength = new short[count];
        this.requiredAirStrength    = new short[count];
        this.regionGroundStrength   = new short[count];
        this.regionAirStrength      = new short[count];
        this.assignedGroundStrength = new short[count];
        this.assignedAirStrength    = new short[count];
        this.enemyAirStrength       = new short[count];
        this.enemyGroundStrength    = new short[count];

        this.airTargets           = new Unit[count];
        this.groundTargets        = new Unit[count];
        this.patrolUnits          = new Unit[count];
        this.leaders              = new Unit[count];
        this.firstMilitaryRecords = new ComputerUnitRecord[count];
        this.militaryCounts       = new short[count];
        this.scoutCandidates      = new int[SCOUT_CANDIDATE_CAPACITY];
    }

    /**
     * @return the region count of the records of one owner
     */
    int getRegionCount() {
        return this.regionCount;
    }

    /**
     * @param owner  the owner
     * @param region the region index
     * @return the record index {@code owner * regionCount + region}
     */
    int getIndex(int owner, int region) {
        return owner * this.regionCount + region;
    }

    /**
     * @param index the record index
     * @return the region of the record
     */
    int getRegion(int index) {
        return index % this.regionCount;
    }

    /**
     * @param index the record index
     * @return the owner of the record
     */
    int getOwner(int index) {
        return index / this.regionCount;
    }

    /**
     * @param index the record index
     * @return the state BYTE ({@link ComputerRegionStates})
     */
    int getState(int index) {
        return Byte.toUnsignedInt(this.states[index]);
    }

    /**
     * @param index the record index
     * @return the state timer BYTE, also the region's request priority
     */
    int getStateTimer(int index) {
        return Byte.toUnsignedInt(this.stateTimers[index]);
    }

    /**
     * Sets the cooldown BYTE.
     *
     * @param index    the record index
     * @param cooldown the cooldown
     */
    void setCooldown(int index, int cooldown) {
        this.cooldowns[index] = (byte) cooldown;
    }

    /**
     * @param index the record index
     * @return the flag BYTE ({@link ComputerRegionStates})
     */
    int getFlags(int index) {
        return Byte.toUnsignedInt(this.flags[index]);
    }

    /**
     * Sets the flag BYTE.
     *
     * @param index the record index
     * @param value the flags
     */
    void setFlags(int index, int value) {
        this.flags[index] = (byte) value;
    }

    /**
     * @param owner  the owner
     * @param region the region index
     * @param air    whether the air strength is read instead of the ground strength
     * @return the required strength WORD
     */
    int getRequiredStrength(int owner, int region, boolean air) {
        int index = owner * this.regionCount + region;

        return Short.toUnsignedInt((air ? this.requiredAirStrength : this.requiredGroundStrength)[index]);
    }

    /**
     * @param index the record index
     * @return the required ground strength WORD
     */
    int getRequiredGroundStrength(int index) {
        return Short.toUnsignedInt(this.requiredGroundStrength[index]);
    }

    /**
     * Sets the required ground strength WORD.
     *
     * @param index the record index
     * @param value the strength
     */
    void setRequiredGroundStrength(int index, int value) {
        this.requiredGroundStrength[index] = (short) value;
    }

    /**
     * @param index the record index
     * @return the required air strength WORD
     */
    int getRequiredAirStrength(int index) {
        return Short.toUnsignedInt(this.requiredAirStrength[index]);
    }

    /**
     * Sets the required air strength WORD.
     *
     * @param index the record index
     * @param value the strength
     */
    void setRequiredAirStrength(int index, int value) {
        this.requiredAirStrength[index] = (short) value;
    }

    /**
     * @param index the record index
     * @return the packed enemy strength: air in the low WORD and ground in the high WORD
     */
    int getEnemyStrength(int index) {
        return Short.toUnsignedInt(this.enemyAirStrength[index]) | Short.toUnsignedInt(this.enemyGroundStrength[index]) << Short.SIZE;
    }

    /**
     * Sets the packed enemy strength.
     *
     * @param index the record index
     * @param value the air strength in the low WORD and the ground strength in the high WORD
     */
    void setEnemyStrength(int index, int value) {
        this.enemyAirStrength[index]    = (short) value;
        this.enemyGroundStrength[index] = (short) (value >>> Short.SIZE);
    }

    /**
     * Sets the enemy air strength WORD.
     *
     * @param index the record index
     * @param value the strength
     */
    void setEnemyAirStrength(int index, int value) {
        this.enemyAirStrength[index] = (short) value;
    }

    /**
     * Sets the enemy ground strength WORD.
     *
     * @param index the record index
     * @param value the strength
     */
    void setEnemyGroundStrength(int index, int value) {
        this.enemyGroundStrength[index] = (short) value;
    }

    /**
     * @param index the record index
     * @return the selected air target, or {@code null}
     */
    @Nullable Unit getAirTarget(int index) {
        return this.airTargets[index];
    }

    /**
     * @param index the record index
     * @return the selected ground target, or {@code null}
     */
    @Nullable Unit getGroundTarget(int index) {
        return this.groundTargets[index];
    }

    /**
     * @param index the record index
     * @return the assigned ground strength WORD
     */
    int getAssignedGroundStrength(int index) {
        return Short.toUnsignedInt(this.assignedGroundStrength[index]);
    }

    /**
     * @param index the record index
     * @return the assigned air strength WORD
     */
    int getAssignedAirStrength(int index) {
        return Short.toUnsignedInt(this.assignedAirStrength[index]);
    }

    /**
     * @param index the record index
     * @return the linked region WORD
     */
    int getLinkedRegion(int index) {
        return Short.toUnsignedInt(this.linkedRegions[index]);
    }

    /**
     * Sets the linked region WORD, as the attack preparation does for a staging region.
     *
     * @param index  the record index
     * @param region the linked region
     */
    void setLinkedRegion(int index, int region) {
        this.linkedRegions[index] = (short) region;
    }

    /**
     * @param index the record index
     * @return the patrol unit of a region record, or {@code null}
     */
    @Nullable Unit getPatrolUnit(int index) {
        return this.patrolUnits[index];
    }

    /**
     * @param index the record index
     * @return the leader of the region's military, or {@code null}
     */
    @Nullable Unit getLeader(int index) {
        return this.leaders[index];
    }

    /**
     * @param index the record index
     * @return the head of the military record list, or {@code null}
     */
    @Nullable ComputerUnitRecord getFirstMilitaryRecord(int index) {
        return this.firstMilitaryRecords[index];
    }

    /**
     * Tests whether the owner's record of the region is quiet: not wanting a defender, without air or ground target and
     * not hit.
     *
     * @param owner  the owner
     * @param region the region index
     * @return {@code true} if the record is quiet, {@code false} otherwise
     */
    boolean isQuiet(int owner, int region) {
        int index = this.getIndex(owner, region);

        return this.states[index] != ComputerRegionStates.DEFENDER_WANTED && this.airTargets[index] == null && this.groundTargets[index] == null &&
               (this.flags[index] & ComputerRegionStates.FLAG_HIT) == 0;
    }

    /**
     * Tests whether every intermediate region of the route between the region centers is quiet; equal regions and
     * separate components count as quiet.
     *
     * @param owner the owner
     * @param from  the source region
     * @param to    the destination region
     * @return {@code true} if the route is quiet, {@code false} otherwise
     */
    boolean isRouteQuiet(int owner, int from, int to) {
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();

        if (from == to || terrain.getComponent(from) != terrain.getComponent(to)) {
            return true;
        }

        RegionPathSearch search = this.game.getComputerTerrain().getPathSearch();

        if (!search.search(from, (short) terrain.getCenterX(from), (short) terrain.getCenterY(from),
                           to, (short) terrain.getCenterX(to), (short) terrain.getCenterY(to)) ||
            search.getRouteCount() <= RegionPathSearch.DIRECT_ROUTE_COUNT) {
            return true;
        }

        int last = search.getRouteCount() - 1;

        for (int routeIndex = 1; routeIndex < last; routeIndex++) {
            if (!this.isQuiet(owner, search.getRouteRegion(routeIndex))) {
                return false;
            }
        }

        return true;
    }

    /**
     * Cancels the attack staging: every staging region ({@link ComputerRegionStates#ATTACK_GATHERING}) returns to the
     * idle state with the default timer, or the defended timer for a keep-strength non-campaign region, without the
     * record initialization of {@link #setState}.
     *
     * @param phase the owning computer phase
     * @param owner the owner
     */
    void cancelStaging(@NotNull ComputerPlayerPhase phase, int owner) {
        boolean campaign = phase.getPlayer(owner).isCampaign();

        for (int region = 1; region < this.regionCount; region++) {
            int index = owner * this.regionCount + region;

            if (this.states[index] != ComputerRegionStates.ATTACK_GATHERING) {
                continue;
            }

            this.flags[index] &= (byte) ~ComputerRegionStates.FLAG_HIT;
            this.states[index]      = ComputerRegionStates.IDLE;
            this.stateTimers[index] = TIMER_DEFAULT;

            if ((this.flags[index] & ComputerRegionStates.FLAG_KEEP_REQUIRED_STRENGTH) != 0 && !campaign) {
                this.stateTimers[index] = TIMER_DEFENDED_IDLE;
            }
        }
    }

    /**
     * Sets the state of a record, including the record initialization when leaving the idle state, and sets the
     * state timer of the new state.
     *
     * @param phase     the owning computer phase for player and unit queries
     * @param index     the record index
     * @param nextState the requested state BYTE
     */
    void setState(@NotNull ComputerPlayerPhase phase, int index, int nextState) {
        int region = this.getRegion(index);

        if (region == 0) {
            return;
        }

        int owner = this.getOwner(index);
        boolean campaign = phase.getPlayer(owner).isCampaign();

        if (this.states[index] == ComputerRegionStates.IDLE) {
            boolean inaccessible = this.game.getComputerTerrain().isInaccessible(region);
            int scale = campaign ? phase.getPlayer(owner).getRegionStrengthScale() : 0;

            this.regionGroundStrength[index]   = 0;
            this.regionAirStrength[index]      = 0;
            this.assignedGroundStrength[index] = 0;
            this.assignedAirStrength[index]    = 0;
            this.airTargets[index]             = null;
            this.groundTargets[index]          = null;
            this.patrolUnits[index]            = null;
            this.leaders[index]                = null;
            this.requiredGroundStrength[index] = (short) (inaccessible ? 0 : scale);
            this.requiredAirStrength[index]    = (short) (inaccessible ? scale : 0);
            this.enemyAirStrength[index]       = 0;
            this.enemyGroundStrength[index]    = 0;

            if (nextState != ComputerRegionStates.DEFENDING) {
                this.linkedRegions[index] = 0;
            }
        }

        int previousFlags = Byte.toUnsignedInt(this.flags[index]);

        this.flags[index]  = (byte) (previousFlags & (~ComputerRegionStates.FLAG_HIT & BYTE_MASK));
        this.states[index] = (byte) nextState;

        switch (nextState) {
            case ComputerRegionStates.IDLE -> {
                boolean keepsRequiredStrength = (previousFlags & ComputerRegionStates.FLAG_KEEP_REQUIRED_STRENGTH) != 0 && !campaign;

                this.stateTimers[index] = (byte) (keepsRequiredStrength ? TIMER_DEFENDED_IDLE : TIMER_DEFAULT);
            }
            case ComputerRegionStates.ATTACK,
                 ComputerRegionStates.ATTACK_ENGAGED -> this.stateTimers[index] = (byte) (campaign ? TIMER_CAMPAIGN_ATTACK : TIMER_ATTACK);
            case ComputerRegionStates.DEFENDER_WANTED -> {
                this.flags[index]       = (byte) (previousFlags & (~(ComputerRegionStates.FLAG_HIT | ComputerRegionStates.FLAG_TOWN) & BYTE_MASK));
                this.stateTimers[index] = TIMER_DEFAULT;
            }
            case ComputerRegionStates.ALLY_TOWN -> this.stateTimers[index] = TIMER_ALLY_TOWN;
            case ComputerRegionStates.TOWN -> this.stateTimers[index] = TIMER_TOWN;
            case ComputerRegionStates.UNDER_ATTACK -> this.stateTimers[index] = (byte) (this.game.getComputerTerrain().hasWorkerOrBuilding(owner, region)
                                                                                        ? TIMER_UNDER_ATTACK_OCCUPIED
                                                                                        : TIMER_UNDER_ATTACK);
            case ComputerRegionStates.DEFENDING -> {
                this.retries[index] = 0;

                boolean occupied = this.game.getComputerTerrain().hasWorkerOrBuilding(owner, Short.toUnsignedInt(this.linkedRegions[index])) ||
                                   this.game.getComputerTerrain().hasWorkerOrBuilding(owner, region);

                this.stateTimers[index] = (byte) (occupied ? TIMER_UNDER_ATTACK_OCCUPIED : TIMER_UNDER_ATTACK);
            }
            case ComputerRegionStates.ATTACK_GATHERING,
                 ComputerRegionStates.ATTACK_LAUNCHED -> this.stateTimers[index] = (byte) (campaign ? TIMER_CAMPAIGN_GATHERING : TIMER_ATTACK);
            default -> this.stateTimers[index] = TIMER_DEFAULT;
        }
    }

    /**
     * Tests the waiting conditions of the staging state: a group type is still missing, or an owner unit of a staging
     * or attack region has neither an enemy order target nor an automatic target and has not reached its record's
     * region.
     *
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private boolean isGathering(@NotNull ComputerPlayerPhase phase, int index) {
        int[] missing = new int[MISSING_TYPE_CAPACITY];
        int count = this.game.getComputerAttack().countMissing(phase, index, missing, true);

        for (int entry = 0; entry < count; entry++) {
            if (missing[entry] < UnitsDat.UNIT_COUNT) {
                return true;
            }
        }

        int owner = this.getOwner(index);
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit)) {
                continue;
            }

            ComputerUnitRecord record = unit.getComputerRecord();

            if (record == null || record.getKind() != ComputerUnitRecord.KIND_MILITARY) {
                continue;
            }

            int recordState = Byte.toUnsignedInt(this.states[record.regionIndex]);

            if (recordState != ComputerRegionStates.ATTACK_GATHERING && recordState != ComputerRegionStates.ATTACK_LAUNCHED) {
                continue;
            }

            Unit orderTarget = unit.getOrderTargetUnit();

            if (orderTarget != null && this.game.getComputerUnits().isEnemy(owner, orderTarget)) {
                continue;
            }

            if (unit.getAutomaticTargetUnit() != null) {
                continue;
            }

            if (this.game.getComputerTerrain().getUnitRegion(unit) != this.getRegion(record.regionIndex)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Tests whether no region of the owner has the flag (aiscript {@code wait_bunkers} with
     * {@link ComputerRegionStates#FLAG_BUNKER_WANTED}, {@code wait_turrets} with
     * {@link ComputerRegionStates#FLAG_TURRET_WANTED}).
     *
     * @param owner the script owner
     * @param flag  the region flag
     * @return {@code true} if no region {@code 1..count-1} of the owner has the flag, {@code false} otherwise
     */
    boolean isFlagClear(int owner, int flag) {
        int count = this.game.getComputerTerrain().getRegions().getRegionCount();

        for (int region = 1; region < count; region++) {
            if ((this.flags[owner * this.regionCount + region] & flag) != 0) {
                return false;
            }
        }

        return true;
    }

    /**
     * Tests whether the owner still attacks (aiscript {@code wait_finishattack}).
     *
     * @param owner the script owner
     * @return {@code true} if a region {@code 1..count-1} of the owner is in an attack state
     * ({@link ComputerRegionStates#ATTACK}, {@link ComputerRegionStates#ATTACK_ENGAGED},
     * {@link ComputerRegionStates#ATTACK_GATHERING} or {@link ComputerRegionStates#ATTACK_LAUNCHED}),
     * {@code false} otherwise
     */
    boolean isAttacking(int owner) {
        int count = this.game.getComputerTerrain().getRegions().getRegionCount();

        for (int region = 1; region < count; region++) {
            if (ComputerRegionStates.isAttackState(Byte.toUnsignedInt(this.states[owner * this.regionCount + region]))) {
                return true;
            }
        }

        return false;
    }


    /**
     * Adds to the enemy air or ground strength WORD.
     *
     * @param index    the record index
     * @param air      whether the air strength is raised instead of the ground strength
     * @param strength the added strength
     */
    void addEnemyStrength(int index, boolean air, int strength) {
        if (air) {
            this.enemyAirStrength[index] = (short) (this.enemyAirStrength[index] + strength);
        } else {
            this.enemyGroundStrength[index] = (short) (this.enemyGroundStrength[index] + strength);
        }
    }

    /**
     * Links a military record at the head of the region's military list.
     *
     * @param index  the region record index
     * @param record the military record
     * @param ground whether the unit is not flying, counted in the region military total
     */
    void linkMilitary(int index, @NotNull ComputerUnitRecord record, boolean ground) {
        record.previous = null;
        record.next     = this.firstMilitaryRecords[index];

        if (record.next != null) {
            record.next.previous = record;
        }

        this.firstMilitaryRecords[index] = record;

        record.regionIndex = index;

        if (ground) {
            this.militaryCounts[index]++;
        }
    }

    /**
     * Unlinks a military record from its region's military list.
     *
     * @param index  the region record index
     * @param record the military record
     * @param ground whether the unit is not flying
     */
    void unlinkMilitary(int index, @NotNull ComputerUnitRecord record, boolean ground) {
        if (ground) {
            this.militaryCounts[index]--;
        }

        if (this.firstMilitaryRecords[index] == record) {
            this.firstMilitaryRecords[index] = record.next;
        }

        if (record.previous != null) {
            record.previous.next = record.next;
        }

        if (record.next != null) {
            record.next.previous = record.previous;
        }
    }

    /**
     * Claims the expansions around the owner's towns (script opcode {@code capt_expand}): twice, first over all
     * regions and then over regions below {@value #SMALL_REGION_TILES} tiles, clears the town flag (and for campaigns
     * the keep-strength flag) and marks the path neighbors of regions in states above
     * {@link ComputerRegionStates#DEFENDER_WANTED}; every marked idle region becomes an ally town region.
     *
     * @param phase the owning computer phase
     * @param owner the script owner
     * @throws UnsupportedOperationException if a neighbor count underflows
     */
    void captureExpansions(@NotNull ComputerPlayerPhase phase, int owner) {
        if (owner >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return;
        }

        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        boolean campaign = phase.getPlayer(owner).isCampaign();

        for (int pass = 0; pass < CAPTURE_PASSES; pass++) {
            boolean small = pass == 1;
            int count = terrain.getRegionCount();
            boolean[] marks = new boolean[count];

            for (int region = 1; region < count; region++) {
                if (small && terrain.getTileCount(region) >= SMALL_REGION_TILES) {
                    continue;
                }

                int index = owner * this.regionCount + region;

                this.flags[index] &= (byte) ~ComputerRegionStates.FLAG_TOWN;

                if (campaign) {
                    this.flags[index] &= (byte) ~ComputerRegionStates.FLAG_KEEP_REQUIRED_STRENGTH;
                }

                if (Byte.toUnsignedInt(this.states[index]) <= ComputerRegionStates.DEFENDER_WANTED) {
                    continue;
                }

                int neighbors = ComputerTerrain.getPathNeighborCount(terrain, region);

                if (neighbors < 0) {
                    throw Exceptions.newUnsupportedOperationException("Computer path neighbor count underflows: region={0}", region);
                }

                for (int neighborIndex = 0; neighborIndex < neighbors; neighborIndex++) {
                    marks[terrain.getNeighborRegion(region, neighborIndex)] = true;
                }
            }

            for (int region = 1; region < count; region++) {
                int index = owner * this.regionCount + region;

                if (!marks[region] || this.states[index] != ComputerRegionStates.IDLE) {
                    continue;
                }

                this.setState(phase, index, ComputerRegionStates.ALLY_TOWN);

                this.flags[index] |= ComputerRegionStates.FLAG_TOWN;

                if (campaign) {
                    this.flags[index] |= ComputerRegionStates.FLAG_KEEP_REQUIRED_STRENGTH;
                }
            }
        }
    }

    /**
     * Takes a defense building site: clears the flag of the first region having it and returns that region's center.
     *
     * @param owner the owner
     * @param flag  {@link ComputerRegionStates#FLAG_BUNKER_WANTED} or {@link ComputerRegionStates#FLAG_TURRET_WANTED}
     * @return the packed center {@code x | y << 16}, or {@code -1} when no region has the flag
     */
    int takeBuildSite(int owner, int flag) {
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();

        for (int region = 1; region < terrain.getRegionCount(); region++) {
            int index = owner * this.regionCount + region;

            if ((this.flags[index] & flag) == 0) {
                continue;
            }

            this.flags[index] &= (byte) ~flag;

            return terrain.getCenterX(region) & WORD_MASK | terrain.getCenterY(region) << Short.SIZE;
        }

        return -1;
    }

    /**
     * Moves the military records of a region into another: each unit leaves its record and joins the target, or with
     * {@code target} {@code -1} the region under the unit.
     *
     * @param filter {@link #TRANSFER_ALL}, {@link #TRANSFER_ATTACKERS} ({@link ComputerAttack#isTransferable}) or
     *               {@link #TRANSFER_UNBUNKERED} (Bunker occupants stay outside campaigns)
     */
    private void transferMilitary(@NotNull ComputerPlayerPhase phase, int from, int target, int filter) {
        ComputerMilitary military = phase.getMilitary();
        ComputerUnitRecord record = this.firstMilitaryRecords[from];

        while (this.firstMilitaryRecords[from] != null && record != null) {
            ComputerUnitRecord next = record.next;
            Unit unit = record.requireUnit();
            boolean moves = switch (filter) {
                case TRANSFER_ATTACKERS -> this.game.getComputerAttack().isTransferable(phase, unit);
                case TRANSFER_UNBUNKERED -> (unit.getStatus() & UnitStatusFlags.IN_BUNKER) == 0 || phase.getPlayer(unit.getOwnerIndex()).isCampaign();
                default -> true;
            };

            if (moves) {
                military.unlink(phase, record);
                unit.setComputerRecord(null);

                int destination = target >= 0 ? target : this.getIndex(unit.getOwnerIndex(), this.game.getComputerTerrain().getUnitRegion(unit));

                military.addToRegion(phase, destination, unit, true, phase.getInstructionLimit());
            }

            record = next;
        }
    }

    /**
     * Hands the patrol unit over when the target has none.
     */
    private void transferPatrol(int from, int target) {
        if (this.patrolUnits[target] == null) {
            this.patrolUnits[target] = this.patrolUnits[from];
            this.patrolUnits[from]   = null;
        }
    }

    /**
     * Gives up a region whose units fall short of the required strength: it returns to
     * {@link ComputerRegionStates#DEFENDER_WANTED} (an attack region to idle) and sends its units to the regions under
     * them. A melee region led by a Firebat or Zergling never gives up.
     *
     * @return {@code true} if the region gave up, {@code false} otherwise
     */
    private boolean escalate(@NotNull ComputerPlayerPhase phase, int index) {
        if (Short.toUnsignedInt(this.requiredAirStrength[index]) <= Short.toUnsignedInt(this.regionAirStrength[index]) &&
            Short.toUnsignedInt(this.requiredGroundStrength[index]) <= Short.toUnsignedInt(this.regionGroundStrength[index])) {
            return false;
        }

        int owner = this.getOwner(index);
        boolean campaign = phase.getPlayer(owner).isCampaign();

        if (!campaign) {
            ComputerUnitRecord first = this.firstMilitaryRecords[index];

            if (first != null && (this.game.requireGameData().getBaseType(first.requireUnit()) == ScrUnitIds.TERRAN_FIREBAT ||
                                  this.game.requireGameData().getBaseType(first.requireUnit()) == ScrUnitIds.ZERG_ZERGLING)) {
                return false;
            }
        }

        int current = Byte.toUnsignedInt(this.states[index]);
        int next = ComputerRegionStates.isAttackState(current) ? ComputerRegionStates.IDLE : ComputerRegionStates.DEFENDER_WANTED;

        if (campaign && next == ComputerRegionStates.DEFENDER_WANTED) {
            // A campaign region holding (or next to) an own worker or building returns to the idle state instead.
            int region = this.getRegion(index);
            TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
            boolean occupied = this.game.getComputerTerrain().hasWorkerOrBuilding(owner, region);

            for (int neighbor = 0; !occupied && neighbor < terrain.getWalkableNeighborCount(region); neighbor++) {
                occupied = this.game.getComputerTerrain().hasWorkerOrBuilding(owner, terrain.getNeighborRegion(region, neighbor));
            }

            if (occupied) {
                next = ComputerRegionStates.IDLE;
            }
        }

        this.setState(phase, index, next);
        this.transferMilitary(phase, index, -1, TRANSFER_ALL);

        return true;
    }

    /**
     * Chooses the leader of a campaign owner's region: the slowest (by top speed) ground unit among the current leader
     * and the military list, else the slowest air unit; strict comparisons keep the earlier candidate.
     *
     * @param phase the owning computer phase
     * @param index the record index
     */
    void updateLeader(@NotNull ComputerPlayerPhase phase, int index) {
        if (!phase.getPlayer(this.getOwner(index)).isCampaign()) {
            return;
        }

        Unit ground = null;
        Unit air = null;
        int groundSpeed = ComputerPlayerPhase.NO_DISTANCE;
        int airSpeed = ComputerPlayerPhase.NO_DISTANCE;
        Unit leader = this.leaders[index];

        if (leader != null) {
            if ((leader.getStatus() & UnitStatusFlags.FLYING) != 0) {
                air      = leader;
                airSpeed = leader.getTopSpeed();
            } else {
                ground      = leader;
                groundSpeed = leader.getTopSpeed();
            }
        }

        for (ComputerUnitRecord record = this.firstMilitaryRecords[index]; record != null; record = record.next) {
            Unit unit = record.requireUnit();
            int speed = unit.getTopSpeed();

            if ((unit.getStatus() & UnitStatusFlags.FLYING) != 0) {
                if (speed < airSpeed) {
                    airSpeed = speed;
                    air      = unit;
                }
            } else if (speed < groundSpeed) {
                groundSpeed = speed;
                ground      = unit;
            }
        }

        this.leaders[index] = ground != null ? ground : air;
    }

    /**
     * Applies one idle-state update with an explicit packed enemy strength and player controls.
     * <p>
     * This isolated form of the record update writes the enemy strengths, then runs the idle-state strength refresh and
     * the required-strength decay for a non-campaign player.
     *
     * @param owner         the owner in {@code 0..7}
     * @param region        the region index
     * @param enemyStrength the packed air (low) and ground (high) enemy strength WORDs
     * @param playerFlags   the owner's flags
     * @param strengthLimit the owner's strength limit WORD
     * @throws UnsupportedOperationException if the record is not idle or requires campaign or military state
     */
    void update(int owner, int region, int enemyStrength, int playerFlags, int strengthLimit) {
        int index = owner * this.regionCount + region;

        this.enemyAirStrength[index]    = (short) enemyStrength;
        this.enemyGroundStrength[index] = (short) (enemyStrength >>> Short.SIZE);
        this.flags[index] &= (byte) ~ComputerRegionStates.FLAG_SCOUTED;

        int cooldown = Byte.toUnsignedInt(this.cooldowns[index]);

        if (cooldown != 0) {
            this.cooldowns[index] = (byte) (cooldown - 1);
        }

        if (this.states[index] != ComputerRegionStates.IDLE) {
            throw Exceptions.newUnsupportedOperationException("Computer regional mode requires its military transition: {0}",
                                                              Byte.toUnsignedInt(this.states[index]));
        }

        if ((playerFlags & ComputerPlayer.FLAG_CAMPAIGN) != 0) {
            throw Exceptions.newUnsupportedOperationException("Computer campaign strength requires the regional radius query.");
        }

        if (this.firstMilitaryRecords[index] != null) {
            throw Exceptions.newUnsupportedOperationException("Computer regional strength requires its linked military records.");
        }

        this.regionGroundStrength[index]   = 0;
        this.regionAirStrength[index]      = 0;
        this.assignedGroundStrength[index] = 0;
        this.assignedAirStrength[index]    = 0;

        this.raiseRequiredStrength(index, false, strengthLimit);
        this.decayRequiredStrength(index, false);
    }

    /**
     * Applies one record update for the represented states.
     *
     * @param phase  the owning computer phase
     * @param owner  the owner in {@code 0..7}
     * @param region the region index
     * @throws UnsupportedOperationException if the regional state is unsupported
     */
    void update(@NotNull ComputerPlayerPhase phase, int owner, int region) {
        int index = owner * this.regionCount + region;

        this.flags[index] &= (byte) ~ComputerRegionStates.FLAG_SCOUTED;

        int cooldown = Byte.toUnsignedInt(this.cooldowns[index]);

        if (cooldown != 0) {
            this.cooldowns[index] = (byte) (cooldown - 1);
        }

        ComputerPlayer player = phase.getPlayer(owner);
        boolean campaign = player.isCampaign();

        switch (Byte.toUnsignedInt(this.states[index])) {
            case ComputerRegionStates.IDLE -> this.refreshIdle(index, campaign, player.getStrengthLimit());
            case ComputerRegionStates.ALLY_TOWN, ComputerRegionStates.TOWN -> {
                this.refreshIdle(index, campaign, player.getStrengthLimit());
                this.refreshTargets(phase, index);

                ComputerUnits computerUnits = this.game.getComputerUnits();
                Unit airTarget = this.airTargets[index];
                Unit groundTarget = this.groundTargets[index];

                if (airTarget != null && !computerUnits.isCritter(airTarget) || groundTarget != null && !computerUnits.isCritter(groundTarget)) {
                    this.setState(phase, index, ComputerRegionStates.UNDER_ATTACK);
                }
            }
            case ComputerRegionStates.UNDER_ATTACK -> {
                this.refreshIdle(index, campaign, player.getStrengthLimit());
                this.refreshTargets(phase, index);

                if (this.cooldowns[index] != 0) {
                    return;
                }

                if (!campaign) {
                    if (this.game.getComputerTerrain().isInaccessible(region)) {
                        if (Short.toUnsignedInt(this.requiredGroundStrength[index]) < MINIMUM_REQUIRED_STRENGTH) {
                            this.requiredGroundStrength[index] = MINIMUM_REQUIRED_STRENGTH;
                        }
                    } else if (Short.toUnsignedInt(this.requiredAirStrength[index]) < MINIMUM_REQUIRED_STRENGTH) {
                        this.requiredAirStrength[index] = MINIMUM_REQUIRED_STRENGTH;
                    }
                }

                if (this.airTargets[index] == null && this.groundTargets[index] == null) {
                    boolean remain = !campaign || this.game.getComputerTerrain().hasWorkerOrBuilding(owner, region);

                    this.setState(phase, index, remain ? ComputerRegionStates.TOWN : ComputerRegionStates.IDLE);

                    return;
                }

                if (this.game.getComputerTerrain().hasWorkerOrBuilding(owner, region)) {
                    return;
                }

                this.escalate(phase, index);

                return;
            }
            case ComputerRegionStates.DEFENDER_WANTED -> {
                this.refreshIdle(index, campaign, player.getStrengthLimit());
                this.refreshTargets(phase, index);

                if (this.airTargets[index] == null && this.groundTargets[index] == null &&
                    (this.flags[index] & ComputerRegionStates.FLAG_KEEP_REQUIRED_STRENGTH) == 0) {
                    this.decayRequiredStrength(index, campaign);
                }

                this.chooseDefender(phase, index);

                return;
            }
            case ComputerRegionStates.DEFENDING -> {
                this.refreshIdle(index, campaign, player.getStrengthLimit());
                this.refreshTargets(phase, index);

                if ((this.flags[index] & ComputerRegionStates.FLAG_HIT) != 0 && this.escalate(phase, index)) {
                    return;
                }

                int linked = this.getIndex(owner, Short.toUnsignedInt(this.linkedRegions[index]));

                if (this.stateTimers[index] != TIMER_QUIET_DEFENDING && this.airTargets[index] == null && this.groundTargets[index] == null &&
                    this.airTargets[linked] == null && this.groundTargets[linked] == null) {
                    this.stateTimers[index] = TIMER_QUIET_DEFENDING;
                }

                ComputerUnits computerUnits = this.game.getComputerUnits();
                Unit airTarget = this.airTargets[index];
                Unit groundTarget = this.groundTargets[index];

                if (airTarget != null && !computerUnits.isCritter(airTarget) || groundTarget != null && !computerUnits.isCritter(groundTarget)) {
                    this.setState(phase, index, ComputerRegionStates.UNDER_ATTACK);

                    return;
                }

                int retries = Byte.toUnsignedInt(this.retries[index]);

                if (retries < DEFENDING_RETRIES) {
                    this.retries[index] = (byte) (retries + 1);

                    if (Short.toUnsignedInt(this.regionAirStrength[index]) < Short.toUnsignedInt(this.requiredAirStrength[linked]) ||
                        Short.toUnsignedInt(this.regionGroundStrength[index]) < Short.toUnsignedInt(this.requiredGroundStrength[linked])) {
                        return;
                    }
                }

                this.setState(phase, linked, ComputerRegionStates.UNDER_ATTACK);
                this.transferPatrol(index, linked);
                this.transferMilitary(phase, index, linked, TRANSFER_UNBUNKERED);
                this.gatherGuards(phase, linked);
                this.setState(phase, index, ComputerRegionStates.TOWN);

                this.cooldowns[linked] = REINFORCED_COOLDOWN;

                return;
            }
            case ComputerRegionStates.ATTACK, ComputerRegionStates.ATTACK_ENGAGED -> {
                this.refreshTargets(phase, index);

                if (this.firstMilitaryRecords[index] == null) {
                    this.setState(phase, index, ComputerRegionStates.IDLE);

                    return;
                }

                if (this.states[index] != ComputerRegionStates.ATTACK_ENGAGED &&
                    Short.toUnsignedInt(this.militaryCounts[index]) >= ENGAGED_MILITARY_COUNT) {
                    this.setState(phase, index, ComputerRegionStates.ATTACK_ENGAGED);

                    return;
                }

                // The leader is chosen when missing.
                if (this.leaders[index] == null) {
                    this.updateLeader(phase, index);
                }

                if ((this.airTargets[index] != null || this.groundTargets[index] != null) &&
                    !this.game.getComputerAttack().isUnreachable(phase, index)) {
                    return;
                }

                Unit target = this.game.getComputerAttack().findTarget(phase, index);

                if (target != null) {
                    int next = this.getIndex(owner, this.game.getComputerTerrain().getUnitRegion(target));

                    this.setState(phase, next, Byte.toUnsignedInt(this.states[index]));
                    this.transferPatrol(index, next);
                    this.transferMilitary(phase, index, next, TRANSFER_ATTACKERS);
                }

                this.setState(phase, index, ComputerRegionStates.IDLE);

                return;
            }
            case ComputerRegionStates.ATTACK_GATHERING, ComputerRegionStates.ATTACK_LAUNCHED -> {
                this.refreshIdle(index, campaign, player.getStrengthLimit());
                this.refreshTargets(phase, index);

                if ((this.flags[index] & ComputerRegionStates.FLAG_HIT) != 0 && this.escalate(phase, index)) {
                    return;
                }

                boolean ground = false;

                for (ComputerUnitRecord record = this.firstMilitaryRecords[index]; record != null; record = record.next) {
                    if ((record.requireUnit().getStatus() & UnitStatusFlags.FLYING) == 0) {
                        ground = true;
                    }
                }

                boolean launch = ground && this.game.getComputerTerrain().isInaccessible(region) ||
                                 this.game.getComputerAttack().isUnreachable(phase, index);

                if (launch) {
                    this.setState(phase, index, ComputerRegionStates.IDLE);

                    Unit target = this.game.getComputerAttack().findTarget(phase, index);

                    if (target == null) {
                        this.transferMilitary(phase, index, -1, TRANSFER_ALL);
                    } else {
                        int next = this.getIndex(owner, this.game.getComputerTerrain().getUnitRegion(target));

                        this.setState(phase, next, ComputerRegionStates.ATTACK_LAUNCHED);
                        this.transferPatrol(index, next);
                        this.transferMilitary(phase, index, next, TRANSFER_ATTACKERS);
                    }

                    return;
                }

                if (!player.isAttackExpired(phase.getElapsedClockTicks()) && this.isGathering(phase, index)) {
                    return;
                }

                int linked = Short.toUnsignedInt(this.linkedRegions[index]);

                if (linked != 0) {
                    int next = this.getIndex(owner, linked);

                    this.setState(phase, next, ComputerRegionStates.ATTACK);
                    this.transferPatrol(index, next);
                    this.transferMilitary(phase, index, next, TRANSFER_ATTACKERS);
                }

                this.setState(phase, index, ComputerRegionStates.IDLE);

                return;
            }
            default -> throw Exceptions.newUnsupportedOperationException("Computer regional state is unsupported: state={0}, owner={1}, region={2}",
                                                                         Byte.toUnsignedInt(this.states[index]),
                                                                         owner,
                                                                         region);
        }

        if ((this.flags[index] & ComputerRegionStates.FLAG_KEEP_REQUIRED_STRENGTH) != 0) {
            return;
        }

        this.decayRequiredStrength(index, campaign);
    }

    private void refreshIdle(int index, boolean campaign, int strengthLimit) {
        int region = this.getRegion(index);

        this.regionGroundStrength[index]   = 0;
        this.regionAirStrength[index]      = 0;
        this.assignedGroundStrength[index] = 0;
        this.assignedAirStrength[index]    = 0;

        for (ComputerUnitRecord record = this.firstMilitaryRecords[index]; record != null; record = record.next) {
            Unit unit = record.requireUnit();

            if ((unit.getStatus() & UnitStatusFlags.IN_BUNKER) != 0) {
                continue;
            }

            int air = unit.getAirStrength();
            int ground = unit.getGroundStrength();

            this.assignedAirStrength[index] += (short) air;
            this.assignedGroundStrength[index] += (short) ground;

            if (this.game.getComputerTerrain().getUnitRegion(unit) == region) {
                this.regionAirStrength[index] += (short) air;
                this.regionGroundStrength[index] += (short) ground;
            }
        }

        if (campaign) {
            // Campaign owners also count every own unit outside a Bunker standing in the region, found by the radius
            // query around the region center (military list members count twice).
            TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
            int centerX = (short) terrain.getCenterX(region);
            int centerY = (short) terrain.getCenterY(region);
            ComputerTerrain query = this.game.getComputerTerrain();
            Unit[] units = query.collectRectangle(centerX - CAMPAIGN_QUERY_RADIUS, centerY - CAMPAIGN_QUERY_RADIUS,
                                                  centerX + CAMPAIGN_QUERY_RADIUS, centerY + CAMPAIGN_QUERY_RADIUS);

            int owner = this.getOwner(index);

            for (Unit unit : units) {
                if ((unit.getStatus() & UnitStatusFlags.IN_BUNKER) != 0 || unit.getOwnerIndex() != owner ||
                    this.game.getComputerTerrain().getUnitRegion(unit) != region) {
                    continue;
                }

                int air = unit.getAirStrength();

                this.assignedAirStrength[index] += (short) air;
                this.regionAirStrength[index] += (short) air;

                int ground = unit.getGroundStrength();

                this.assignedGroundStrength[index] += (short) ground;
                this.regionGroundStrength[index] += (short) ground;
            }
        }

        this.raiseRequiredStrength(index, campaign, strengthLimit);
    }

    /**
     * Selects a defender unless a neighbor is already linked here in the defending state. Eligible target-free
     * neighbors must pass the state and first-town route guards. A larger tile count replaces the largest slot;
     * otherwise a larger count replaces the second slot. The previous largest is not shifted when replaced.
     * The second slot, when present, is selected before the largest slot.
     *
     * @throws UnsupportedOperationException if the neighbor count of an inaccessible region underflows
     */
    private void chooseDefender(@NotNull ComputerPlayerPhase phase, int index) {
        int owner = this.getOwner(index);
        int region = this.getRegion(index);
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        boolean inaccessible = this.game.getComputerTerrain().isInaccessible(region);
        int count;

        if (inaccessible) {
            count = ComputerTerrain.getPathNeighborCount(terrain, region);

            if (count < 0) {
                throw Exceptions.newUnsupportedOperationException("Computer path neighbor count underflows: region={0}", region);
            }
        } else {
            // An accessible region reads its walkable neighbors.
            count = terrain.getWalkableNeighborCount(region);
        }

        for (int neighborIndex = 0; neighborIndex < count; neighborIndex++) {
            int neighbor = this.getIndex(owner, terrain.getNeighborRegion(region, neighborIndex));

            if (this.states[neighbor] == ComputerRegionStates.DEFENDING && Short.toUnsignedInt(this.linkedRegions[neighbor]) == region) {
                return;
            }
        }

        boolean campaign = phase.getPlayer(owner).isCampaign();
        ComputerTown town = phase.getFirstTown(owner);
        int largest = -1;
        int largestTiles = 0;
        int second = -1;
        int secondTiles = 0;

        for (int neighborIndex = 0; neighborIndex < count; neighborIndex++) {
            int neighborRegion = terrain.getNeighborRegion(region, neighborIndex);
            int neighbor = this.getIndex(owner, neighborRegion);

            if (this.airTargets[neighbor] != null || this.groundTargets[neighbor] != null) {
                continue;
            }

            int neighborState = Byte.toUnsignedInt(this.states[neighbor]);

            if (neighborState == ComputerRegionStates.IDLE && campaign || (neighborState & ComputerRegionStates.ACTIVE_STATE_BITS) != 0 ||
                neighborState == ComputerRegionStates.ATTACK) {
                continue;
            }

            if (town != null &&
                !this.isRouteQuiet(owner, this.game.getComputerTerrain().getRegion((short) town.positionX, (short) town.positionY), neighborRegion)) {
                continue;
            }

            int tiles = terrain.getTileCount(neighborRegion);

            if (tiles > largestTiles) {
                largestTiles = tiles;
                largest      = neighbor;
            } else if (tiles > secondTiles) {
                secondTiles = tiles;
                second      = neighbor;
            }
        }

        int chosen = second != -1 ? second : largest;

        if (chosen == -1) {
            return;
        }

        this.linkedRegions[chosen] = (short) region;

        this.setState(phase, chosen, ComputerRegionStates.DEFENDING);
    }

    /**
     * Gathers the guards around the region center: the owner's guards in the radius query (not in a Bunker) that stand
     * in the region join it.
     *
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private void gatherGuards(@NotNull ComputerPlayerPhase phase, int index) {
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        int owner = this.getOwner(index);
        int region = this.getRegion(index);
        int centerX = (short) terrain.getCenterX(region);
        int centerY = (short) terrain.getCenterY(region);
        ComputerTerrain query = this.game.getComputerTerrain();
        int[] units = query.collectRectanglePoolIndices(centerX - CAMPAIGN_QUERY_RADIUS, centerY - CAMPAIGN_QUERY_RADIUS,
                                                        centerX + CAMPAIGN_QUERY_RADIUS, centerY + CAMPAIGN_QUERY_RADIUS);

        for (int poolIndex : units) {
            Unit unit = this.game.getUnitSystem().requireUnitPool().getUnit(poolIndex);
            ComputerUnitRecord record = unit.getComputerRecord();

            if (record == null || record.getKind() != ComputerUnitRecord.KIND_GUARD || (unit.getStatus() & UnitStatusFlags.IN_BUNKER) != 0 ||
                unit.getOwnerIndex() != owner || this.game.getComputerTerrain().getUnitRegion(unit) != region) {
                continue;
            }

            phase.removeUnit(unit, false);
            phase.getMilitary().addToRegion(phase, index, unit, true, phase.getInstructionLimit());
        }
    }

    /**
     * Raises the required strengths to the enemy strengths (one and a half times for melee owners), bounded by the
     * owner's strength limit.
     */
    private void raiseRequiredStrength(int index, boolean campaign, int strengthLimit) {
        int air = Short.toUnsignedInt(this.enemyAirStrength[index]);

        if (!campaign) {
            air += air >>> 1;
        }

        if (Short.toUnsignedInt(this.requiredAirStrength[index]) < air) {
            this.requiredAirStrength[index] = (short) air;
        }

        int ground = Short.toUnsignedInt(this.enemyGroundStrength[index]);

        if (!campaign) {
            ground += ground >>> 1;
        }

        if (Short.toUnsignedInt(this.requiredGroundStrength[index]) < ground) {
            this.requiredGroundStrength[index] = (short) ground;
        }

        if (strengthLimit != 0) {
            if (strengthLimit < Short.toUnsignedInt(this.requiredAirStrength[index])) {
                this.requiredAirStrength[index] = (short) strengthLimit;
            }

            if (strengthLimit < Short.toUnsignedInt(this.requiredGroundStrength[index])) {
                this.requiredGroundStrength[index] = (short) strengthLimit;
            }
        }
    }

    /**
     * Refreshes the region's military strength sums and required strengths.
     *
     * @param phase the owning phase
     * @param index the region record index
     */
    void refreshStrength(@NotNull ComputerPlayerPhase phase, int index) {
        ComputerPlayer player = phase.getPlayer(this.getOwner(index));

        this.refreshIdle(index, player.isCampaign(), player.getStrengthLimit());
    }

    /**
     * Selects the air and ground targets of the region, then orders each idle military unit of the region that can
     * attack one of them into the Computer AI order.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private void refreshTargets(@NotNull ComputerPlayerPhase phase, int index) {
        int owner = this.getOwner(index);
        int region = this.getRegion(index);
        Unit air = this.game.getComputerTargets().selectRegionTarget(phase, owner, region, false);
        Unit ground = this.game.getComputerTargets().selectRegionTarget(phase, owner, region, true);

        this.airTargets[index]    = air;
        this.groundTargets[index] = ground;

        if (air == null && ground == null) {
            return;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        for (ComputerUnitRecord entry = this.firstMilitaryRecords[index]; entry != null; entry = entry.next) {
            Unit unit = entry.requireUnit();
            ComputerUnitRecord record = unit.getComputerRecord();

            if (record == null || record.getKind() != ComputerUnitRecord.KIND_MILITARY || record.unit != unit) {
                continue;
            }

            if (UnitQueries.isBuilding(units, unit.getUnitId())) {
                continue;
            }

            if ((unit.getStatus() & (UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE | UnitStatusFlags.ISCRIPT_NO_BREAK)) != 0) {
                continue;
            }

            Unit orderTarget = unit.getOrderTargetUnit();

            if (orderTarget == ground || orderTarget == air) {
                continue;
            }

            int order = unit.getPrimaryOrder();

            if (order == ScrOrderIds.ATTACK_MOVE || order == ScrOrderIds.ATK_MOVE_EP || order == ScrOrderIds.HARASS_MOVE ||
                this.game.getUnitQueries().isTransport(unit)) {
                continue;
            }

            if (!(ground != null && this.game.getUnitTargeting().canAttackTarget(unit, ground) ||
                  air != null && this.game.getUnitTargeting().canAttackTarget(unit, air))) {
                continue;
            }

            if ((unit.getStatus() & UnitStatusFlags.BURROWED) != 0) {
                this.game.getComputerOrders().unburrowToAttack(unit, phase.getInstructionLimit());
            } else if (orderTarget == null) {
                phase.issueOrder(unit, ScrOrderIds.COMPUTER_AI);
            }
        }
    }

    /**
     * Decays the required air strength, then the required ground strength.
     */
    private void decayRequiredStrength(int index, boolean campaign) {
        this.requiredAirStrength[index]    = (short) decay(Short.toUnsignedInt(this.requiredAirStrength[index]), campaign);
        this.requiredGroundStrength[index] = (short) decay(Short.toUnsignedInt(this.requiredGroundStrength[index]), campaign);
    }

    /**
     * Activates an idle region as an ally town region with the town flag; a campaign owner or a defense line also
     * keeps the required strength, and a defense line requires {@value #MINIMUM_REQUIRED_STRENGTH} ground strength in
     * an accessible region.
     *
     * @param phase       the owning phase
     * @param owner       the owner
     * @param region      the region
     * @param defenseLine whether the caller requests the required-strength line
     */
    void activate(@NotNull ComputerPlayerPhase phase, int owner, int region, boolean defenseLine) {
        int index = this.getIndex(owner, region);

        if (this.states[index] != ComputerRegionStates.IDLE) {
            return;
        }

        this.setState(phase, index, ComputerRegionStates.ALLY_TOWN);

        this.flags[index] |= ComputerRegionStates.FLAG_TOWN;

        if (!defenseLine && !phase.getPlayer(owner).isCampaign()) {
            return;
        }

        this.flags[index] |= ComputerRegionStates.FLAG_KEEP_REQUIRED_STRENGTH;

        if (defenseLine && !this.game.getComputerTerrain().isInaccessible(region)) {
            this.requiredGroundStrength[index] = (short) MINIMUM_REQUIRED_STRENGTH;
        }
    }

    /**
     * Requests Bunkers (aiscript {@code build_bunkers}): a town region ({@link ComputerRegionStates#FLAG_TOWN}) with an
     * own worker or building and no own Bunker among its units gets {@link ComputerRegionStates#FLAG_BUNKER_WANTED},
     * for at most {@value #BUNKER_REGION_LIMIT} regions.
     *
     * @param owner the script owner
     */
    void requestBunkers(int owner) {
        this.requestDefenseBuildings(owner, ScrUnitIds.TERRAN_BUNKER, ComputerRegionStates.FLAG_BUNKER_WANTED, BUNKER_REGION_LIMIT, true);
    }

    /**
     * Requests Missile Turrets (aiscript {@code build_turrets}): as {@link #requestBunkers} with
     * {@link ComputerRegionStates#FLAG_TURRET_WANTED}, for at most {@value #TURRET_REGION_LIMIT} regions and without the
     * worker or building test.
     *
     * @param owner the script owner
     */
    void requestTurrets(int owner) {
        this.requestDefenseBuildings(owner, ScrUnitIds.TERRAN_MISSILE_TURRET, ComputerRegionStates.FLAG_TURRET_WANTED, TURRET_REGION_LIMIT, false);
    }

    private void requestDefenseBuildings(int owner, int type, int flag, int limit, boolean occupied) {
        int count = this.game.getComputerTerrain().getRegions().getRegionCount();
        ComputerPlayerPhase phase = this.game.getComputerPlayerPhase();

        if (phase == null) {
            throw Exceptions.newIllegalStateException("Computer region requests require the computer-player phase.");
        }

        ComputerTerrain query = this.game.getComputerTerrain();
        int marked = 0;

        for (int region = 1; region < count; region++) {
            int index = owner * this.regionCount + region;

            if ((this.flags[index] & ComputerRegionStates.FLAG_TOWN) == 0) {
                continue;
            }

            if (occupied && !this.game.getComputerTerrain().hasWorkerOrBuilding(owner, region)) {
                continue;
            }

            boolean present = false;
            int found = query.collectRegionUnits(region);

            for (int unitIndex = 0; unitIndex < found; unitIndex++) {
                Unit unit = this.game.getUnitSystem().requireUnitPool().getUnit(query.getRegionUnit(unitIndex));

                if (this.game.requireGameData().getBaseType(unit) == type && unit.getOwnerIndex() == owner) {
                    present = true;

                    break;
                }
            }

            if (present) {
                continue;
            }

            this.flags[index] |= (byte) flag;

            if (++marked == limit) {
                return;
            }
        }
    }

    /**
     * Clears the combat data in a rectangle (aiscript {@code clear_combatdata}): every region {@code 1..count-1} whose
     * center lies inside the rectangle, bounds included, returns to {@link ComputerRegionStates#IDLE}.
     *
     * @param phase  the owning computer phase
     * @param owner  the script owner
     * @param left   the rectangle left DWORD
     * @param top    the rectangle top DWORD
     * @param right  the rectangle right DWORD
     * @param bottom the rectangle bottom DWORD
     */
    void clearCombatData(@NotNull ComputerPlayerPhase phase, int owner, int left, int top, int right, int bottom) {
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        int count = terrain.getRegionCount();

        for (int region = 1; region < count; region++) {
            int centerX = (short) terrain.getCenterX(region);
            int centerY = (short) terrain.getCenterY(region);

            if (left <= centerX && centerX <= right && top <= centerY && centerY <= bottom) {
                this.setState(phase, owner * this.regionCount + region, ComputerRegionStates.IDLE);
            }
        }
    }

    /**
     * Implodes the owner's area (aiscript {@code implode}): a region {@code 1..count-1} below
     * {@link ComputerRegionStates#ALLY_TOWN} with at most one path neighbor below that state becomes
     * {@link ComputerRegionStates#ALLY_TOWN}.
     *
     * @param phase the owning computer phase
     * @param owner the script owner
     * @throws UnsupportedOperationException if a neighbor count underflows
     */
    void implode(@NotNull ComputerPlayerPhase phase, int owner) {
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        int count = terrain.getRegionCount();

        for (int region = 1; region < count; region++) {
            int index = owner * this.regionCount + region;

            if (Byte.toUnsignedInt(this.states[index]) >= ComputerRegionStates.ALLY_TOWN) {
                continue;
            }

            int neighbors = ComputerTerrain.getPathNeighborCount(terrain, region);

            if (neighbors < 0) {
                throw Exceptions.newUnsupportedOperationException("Computer path neighbor count underflows: region={0}", region);
            }

            boolean open = false;
            boolean crowded = false;

            for (int neighborIndex = 0; neighborIndex < neighbors; neighborIndex++) {
                int neighbor = owner * this.regionCount + terrain.getNeighborRegion(region, neighborIndex);

                if (Byte.toUnsignedInt(this.states[neighbor]) >= ComputerRegionStates.ALLY_TOWN) {
                    continue;
                }

                if (open) {
                    crowded = true;

                    break;
                }

                open = true;
            }

            if (!crowded) {
                this.setState(phase, index, ComputerRegionStates.ALLY_TOWN);
            }
        }
    }

    /**
     * Helps the owner's allies (aiscript {@code help_iftrouble}): for every player {@code 0..7} allied with the owner in
     * both directions, each region {@code 1..count-1} that is active for the owner and idle for that player becomes
     * {@link ComputerRegionStates#ALLY_TOWN} for that player.
     *
     * @param phase the owning computer phase
     * @param owner the script owner
     * @throws UnsupportedOperationException if the game has no player relations
     */
    void helpAllies(@NotNull ComputerPlayerPhase phase, int owner) {
        PlayerRelations relations = this.game.getPlayerTable().getPlayerRelations();

        if (relations == null) {
            throw Exceptions.newUnsupportedOperationException("Computer ally help requires player relations.");
        }

        int count = this.game.getComputerTerrain().getRegions().getRegionCount();

        for (int ally = 0; ally < PlayerTable.PLAYABLE_PLAYER_SLOTS; ally++) {
            if (relations.getAlliance(owner, ally) == PlayerRelations.ENEMY || relations.getAlliance(ally, owner) == PlayerRelations.ENEMY) {
                continue;
            }

            for (int region = 1; region < count; region++) {
                if (this.states[owner * this.regionCount + region] == ComputerRegionStates.IDLE) {
                    continue;
                }

                int allyIndex = ally * this.regionCount + region;

                if (this.states[allyIndex] == ComputerRegionStates.IDLE) {
                    this.setState(phase, allyIndex, ComputerRegionStates.ALLY_TOWN);
                }
            }
        }
    }

    /**
     * Clears the ground and air enemy strengths of regions {@code 1..count-1} for all eight owners.
     */
    void clearEnemyStrength() {
        for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
            int start = owner * this.regionCount;

            Arrays.fill(this.enemyAirStrength, start + 1, start + this.regionCount, (short) 0);
            Arrays.fill(this.enemyGroundStrength, start + 1, start + this.regionCount, (short) 0);
        }
    }

    /**
     * Clears the references to a released unit from one owner's regions {@code 1..count-1}: targets, leader and patrol
     * unit.
     *
     * @param owner the region owner
     * @param unit  the released unit
     */
    void releaseUnit(int owner, @NotNull Unit unit) {
        for (int region = 1; region < this.regionCount; region++) {
            int index = owner * this.regionCount + region;

            if (this.airTargets[index] == unit) {
                this.airTargets[index] = null;
            }

            if (this.groundTargets[index] == unit) {
                this.groundTargets[index] = null;
            }

            if (this.leaders[index] == unit) {
                this.leaders[index] = null;
            }

            if (this.patrolUnits[index] == unit) {
                this.patrolUnits[index] = null;
            }
        }
    }

    /**
     * Continues the patrol of a unit assigned to a military region: a region left the military states releases the
     * unit where it stands; otherwise the unit stops near the region center or moves there.
     *
     * @param unit the patrolling unit
     * @return {@code true} if the unit keeps its assignment, {@code false} otherwise
     * @throws IllegalStateException if the unit has no sprite
     */
    boolean continuePatrol(@NotNull Unit unit) {
        int owner = unit.getOwnerIndex();

        for (int region = 1; region < this.regionCount; region++) {
            int index = owner * this.regionCount + region;

            if (this.patrolUnits[index] != unit) {
                continue;
            }

            if (!canKeepPatrol(Byte.toUnsignedInt(this.states[index]))) {
                this.patrolUnits[index] = null;

                Sprite sprite = unit.requireSprite();

                this.game.getComputerOrders().moveTo(unit, (short) sprite.getX(), (short) sprite.getY());

                return false;
            }

            // Within the arrival distance of the region center the unit stops, otherwise it moves there.
            TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
            int centerX = terrain.getCenterX(region);
            int centerY = terrain.getCenterY(region);

            if (ComputerTargets.isNear(unit, PATROL_ARRIVAL_DISTANCE, centerX, centerY)) {
                this.game.getUnitPointMovement().stopAndFaceDestination(unit);
            } else {
                this.game.getComputerOrders().moveTo(unit, centerX, centerY);
            }

            return true;
        }

        return false;
    }

    /**
     * Assigns a patrol unit to the first military region lacking one whose state keeps a patrol ({@link #canKeepPatrol}:
     * {@code 1}, {@code 2} or {@code 7..9}).
     *
     * @param phase the owning phase
     * @param unit  the candidate unit
     * @return {@code true} if assigned, {@code false} otherwise
     */
    boolean assignPatrol(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit) {
        int owner = unit.getOwnerIndex();

        if ((phase.getPlayer(owner).getFlags() & ComputerPlayer.FLAG_PATROL_ASSIGNMENT) == 0) {
            return false;
        }

        for (int region = 1; region < this.regionCount; region++) {
            int index = owner * this.regionCount + region;
            int mode = Byte.toUnsignedInt(this.states[index]);

            if (this.firstMilitaryRecords[index] == null || !canKeepPatrol(mode) || this.patrolUnits[index] != null) {
                continue;
            }

            this.patrolUnits[index] = unit;

            this.game.getComputerOrders().moveTo(unit,
                                                 this.game.getComputerTerrain().getRegions().getCenterX(region),
                                                 this.game.getComputerTerrain().getRegions().getCenterY(region));

            return true;
        }

        return false;
    }

    /**
     * Chooses the region a scout visits: a random path neighbor in a town state, preferring unscouted town regions,
     * then scouted town regions, unscouted and scouted other regions; without one the nearest town-state region. Marks
     * the chosen region scouted.
     *
     * @param unit the scouting unit
     * @return the chosen region
     * @throws UnsupportedOperationException if the neighbor count underflows
     * @throws IllegalStateException         if the unit has no sprite
     */
    int selectScoutRegion(@NotNull Unit unit) {
        TerrainRegions regions = this.game.getComputerTerrain().getRegions();
        int owner = unit.getOwnerIndex();
        int chosen = this.game.getComputerTerrain().getUnitRegion(unit);
        int neighborCount = ComputerTerrain.getPathNeighborCount(regions, chosen);

        if (neighborCount < 0) {
            throw Exceptions.newUnsupportedOperationException("Computer scout path neighbor count underflows: region={0}", chosen);
        }

        int[] candidates = this.scoutCandidates;
        int unvisitedTown = 0;
        int visitedTown = 0;
        int unvisitedOther = 0;
        int visitedOther = 0;

        for (int neighborIndex = 0; neighborCount != 0; neighborIndex++, neighborCount--) {
            int neighbor = regions.getNeighborRegion(chosen, neighborIndex);
            int index = owner * this.regionCount + neighbor;
            int mode = Byte.toUnsignedInt(this.states[index]);

            if (mode != ComputerRegionStates.ALLY_TOWN && mode != ComputerRegionStates.TOWN) {
                continue;
            }

            int regionFlags = Byte.toUnsignedInt(this.flags[index]);
            boolean visited = (regionFlags & ComputerRegionStates.FLAG_SCOUTED) != 0;

            if ((regionFlags & ComputerRegionStates.FLAG_TOWN) != 0) {
                if (!visited) {
                    if (unvisitedTown != SCOUT_BUCKET_SIZE) {
                        candidates[unvisitedTown++] = neighbor;
                    }
                } else if (visitedTown != SCOUT_BUCKET_SIZE) {
                    candidates[SCOUTED_TOWNS + visitedTown++] = neighbor;
                }
            } else if (visited) {
                if (visitedOther != SCOUT_BUCKET_SIZE) {
                    candidates[SCOUTED_OTHERS + visitedOther++] = neighbor;
                }
            } else if (unvisitedOther != SCOUT_BUCKET_SIZE) {
                candidates[UNSCOUTED_OTHERS + unvisitedOther++] = neighbor;
            }
        }

        LcgRandom random = this.game.getRandom();

        if (unvisitedTown != 0) {
            chosen = candidates[Integer.remainderUnsigned(random.next(SCOUT_RANDOM_SOURCE), unvisitedTown)];
        } else if (visitedTown != 0) {
            chosen = candidates[SCOUTED_TOWNS + Integer.remainderUnsigned(random.next(SCOUT_RANDOM_SOURCE), visitedTown)];
        } else if (unvisitedOther != 0) {
            chosen = candidates[UNSCOUTED_OTHERS + Integer.remainderUnsigned(random.next(SCOUT_RANDOM_SOURCE), unvisitedOther)];
        } else if (visitedOther != 0) {
            chosen = candidates[SCOUTED_OTHERS + Integer.remainderUnsigned(random.next(SCOUT_RANDOM_SOURCE), visitedOther)];
        }

        int chosenMode = Byte.toUnsignedInt(this.states[owner * this.regionCount + chosen]);

        if (chosenMode != ComputerRegionStates.ALLY_TOWN && chosenMode != ComputerRegionStates.TOWN) {
            int nearestDistance = ComputerPlayerPhase.NO_DISTANCE;

            for (int region = 1; region < regions.getRegionCount(); region++) {
                int mode = Byte.toUnsignedInt(this.states[owner * this.regionCount + region]);

                if (mode != ComputerRegionStates.ALLY_TOWN && mode != ComputerRegionStates.TOWN) {
                    continue;
                }

                Sprite sprite = unit.requireSprite();
                int distance = RegionPathSearch.calculateDistance(regions.getCenterX(region), (short) sprite.getX(),
                                                                  regions.getCenterY(region), (short) sprite.getY());

                if (Integer.compareUnsigned(distance, nearestDistance) < 0) {
                    nearestDistance = distance;
                    chosen          = region;
                }
            }
        }

        this.flags[owner * this.regionCount + chosen] |= ComputerRegionStates.FLAG_SCOUTED;

        return chosen;
    }

    /**
     * Plans the defense units of the owner's defended and military regions: the missing units of gathering attacks,
     * then the air and ground strength each region (or its linked region) requires, then the transports.
     *
     * @param phase the owning phase
     * @param owner the computer owner
     * @throws UnsupportedOperationException if the unit accounting is not initialized
     */
    void planDefense(@NotNull ComputerPlayerPhase phase, int owner) {
        ComputerPlayer player = phase.getPlayer(owner);
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        int transportRegion = -1;

        for (int region = 1; region < terrain.getRegionCount(); region++) {
            int index = owner * this.regionCount + region;
            int mode = Byte.toUnsignedInt(this.states[index]);

            if (mode <= ComputerRegionStates.DEFENDER_WANTED) {
                continue;
            }

            if (mode == ComputerRegionStates.ALLY_TOWN || mode == ComputerRegionStates.TOWN) {
                transportRegion = index;
            }

            if (mode == ComputerRegionStates.ATTACK_GATHERING) {
                int[] missing = new int[MISSING_TYPE_CAPACITY];
                int count = this.game.getComputerAttack().countMissing(phase, index, missing, !player.isCampaign());
                int priority = !player.isCampaign() && player.isBuildingHitRequestDelayElapsed(phase.getElapsedClockTicks())
                               ? GATHERING_URGENT_PRIORITY
                               : GATHERING_PRIORITY;

                for (int entry = 0; entry < count; entry++) {
                    int type = missing[entry];

                    if (type < UnitsDat.UNIT_COUNT && !phase.getMilitary().useUnit(phase, index, type, priority)) {
                        this.game.getComputerTowns().requestUnit(player, index, type, GATHERING_PRIORITY, true, true);
                    }
                }

                continue;
            }

            int linkedRegion = mode < ComputerRegionStates.DEFENDING ? region : Short.toUnsignedInt(this.linkedRegions[index]);
            int linked = owner * this.regionCount + linkedRegion;
            int required = Short.toUnsignedInt(this.requiredAirStrength[linked]);
            int assigned = Short.toUnsignedInt(this.assignedAirStrength[index]);

            if (required != 0 && assigned < required) {
                do {
                    int added;
                    boolean inaccessible = this.game.getComputerTerrain().isInaccessible(region) ||
                                           this.game.getComputerTerrain().isInaccessible(linkedRegion);

                    if (Byte.toUnsignedInt(this.states[linked]) == ComputerRegionStates.ALLY_TOWN) {
                        added = terrain.getTileCount(linkedRegion) < SMALL_TOWN_TILES
                                ? UNAVAILABLE_DEFENSE
                                : this.requestDefense(phase,
                                                      player,
                                                      inaccessible ? ComputerPlayer.DEFENSE_BUILD_AIR_AIR : ComputerPlayer.DEFENSE_BUILD_AIR_GROUND,
                                                      index,
                                                      true,
                                                      Short.toUnsignedInt(this.requiredAirStrength[linked]) - assigned);
                    } else {
                        added = phase.getMilitary().useDefense(phase,
                                                               player.getDefenseList(inaccessible
                                                                                     ? ComputerPlayer.DEFENSE_USE_AIR_AIR
                                                                                     : ComputerPlayer.DEFENSE_USE_AIR_GROUND),
                                                               index,
                                                               true);

                        if (added == 0) {
                            added = this.requestDefense(phase,
                                                        player,
                                                        inaccessible ? ComputerPlayer.DEFENSE_BUILD_AIR_AIR : ComputerPlayer.DEFENSE_BUILD_AIR_GROUND,
                                                        index,
                                                        true,
                                                        Short.toUnsignedInt(this.requiredAirStrength[linked]) - assigned);
                        }
                    }

                    assigned += added;
                } while (Integer.compareUnsigned(assigned, Short.toUnsignedInt(this.requiredAirStrength[linked])) < 0);
            }

            required = Short.toUnsignedInt(this.requiredGroundStrength[linked]);
            assigned = Short.toUnsignedInt(this.assignedGroundStrength[index]);

            if (required != 0 && assigned < required) {
                do {
                    assigned += this.planGroundDefense(phase,
                                                       player,
                                                       index,
                                                       linkedRegion,
                                                       this.enemyAirStrength[linked] != 0,
                                                       Short.toUnsignedInt(this.requiredGroundStrength[linked]) - assigned);
                } while (Integer.compareUnsigned(assigned, Short.toUnsignedInt(this.requiredGroundStrength[linked])) < 0);
            }

            player.finishDefenseUpdate();
        }

        if ((player.getFlags() & ComputerPlayer.FLAG_TRANSPORTS_OFF) != 0) {
            return;
        }

        // With transports enabled, a Terran owner with a Control Tower wants Dropships; a Protoss owner first requests
        // two Observers, then wants Shuttles in the last town region.
        int race = this.game.getPlayerTable().requirePlayer(owner).getRace();
        int transport;

        if (race == ScrRaceIds.TERRAN) {
            if (this.game.getComputerUnits().getCompletedTypeCount(ScrUnitIds.TERRAN_CONTROL_TOWER, owner) == 0) {
                return;
            }

            transport = ScrUnitIds.TERRAN_DROPSHIP;
        } else if (race == ScrRaceIds.PROTOSS) {
            int observers = this.game.getComputerUnits().getTypeCount(ScrUnitIds.PROTOSS_OBSERVER, owner);

            for (int missing = OBSERVER_COUNT - observers; missing > 0; missing--) {
                this.game.getComputerTowns().reserveCost(player, ScrUnitIds.PROTOSS_OBSERVER);
                this.game.getComputerTowns().pushRegionRequest(player,
                                                               ComputerPlayer.REQUEST_KIND_OBSERVER,
                                                               ScrUnitIds.PROTOSS_OBSERVER,
                                                               OBSERVER_PRIORITY,
                                                               -1);
            }

            transport = ScrUnitIds.PROTOSS_SHUTTLE;
        } else {
            return;
        }

        if (transportRegion == -1) {
            return;
        }

        int count = this.game.getComputerUnits().getTypeCount(transport, owner);
        int limit = player.isCampaign() ? CAMPAIGN_TRANSPORT_COUNT : TRANSPORT_COUNT;
        int priority = count == 0 ? FIRST_TRANSPORT_PRIORITY : TRANSPORT_PRIORITY;

        for (int missing = limit - count; missing > 0; missing--) {
            this.game.getComputerTowns().requestUnit(player, transportRegion, transport, priority, true, true);
        }
    }

    /**
     * Plans the ground defense of a region: an ally town region below {@value #SMALL_TOWN_TILES} tiles is unavailable;
     * otherwise the region uses spare units of the matching use list, else requests the matching build list.
     */
    private int planGroundDefense(@NotNull ComputerPlayerPhase phase,
                                  @NotNull ComputerPlayer player,
                                  int index,
                                  int linkedRegion,
                                  boolean airEnemies,
                                  int needed) {
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        int region = this.getRegion(index);
        int linked = player.getOwner() * this.regionCount + linkedRegion;
        boolean inaccessible = this.game.getComputerTerrain().isInaccessible(region) || this.game.getComputerTerrain().isInaccessible(linkedRegion);

        if (Byte.toUnsignedInt(this.states[linked]) == ComputerRegionStates.ALLY_TOWN) {
            if (terrain.getTileCount(linkedRegion) < SMALL_TOWN_TILES) {
                return UNAVAILABLE_DEFENSE;
            }
        } else {
            int list = airEnemies
                       ? inaccessible ? ComputerPlayer.DEFENSE_USE_AIR_AIR : ComputerPlayer.DEFENSE_USE_AIR_GROUND
                       : inaccessible ? ComputerPlayer.DEFENSE_USE_GROUND_AIR : ComputerPlayer.DEFENSE_USE_GROUND_GROUND;
            int used = phase.getMilitary().useDefense(phase, player.getDefenseList(list), index, airEnemies);

            if (used != 0) {
                return used;
            }
        }

        int list = !inaccessible
                   ? airEnemies ? ComputerPlayer.DEFENSE_BUILD_AIR_GROUND : ComputerPlayer.DEFENSE_BUILD_GROUND_GROUND
                   : airEnemies ? ComputerPlayer.DEFENSE_BUILD_AIR_AIR : ComputerPlayer.DEFENSE_BUILD_GROUND_AIR;

        return this.requestDefense(phase, player, list, index, airEnemies, needed);
    }

    /**
     * Requests the types of a defense build list: each listed type is requested at the region's timer plus the owner's
     * defense countdown, and its air or ground value is summed; a campaign owner stops once the sum covers the need.
     *
     * @return the summed value, or {@value #UNAVAILABLE_DEFENSE} for an empty list
     * @throws UnsupportedOperationException if a full list is read past its end
     */
    private int requestDefense(@NotNull ComputerPlayerPhase phase, @NotNull ComputerPlayer player, int list, int index, boolean air, int needed) {
        short[] entries = player.getDefenseList(list);

        if (entries[0] == 0) {
            return UNAVAILABLE_DEFENSE;
        }

        int priority = Byte.toUnsignedInt(this.stateTimers[index]) + player.getDefenseCountdown();
        int total = 0;

        for (int entry = 0; ; entry++) {
            if (entry == entries.length) {
                throw Exceptions.newUnsupportedOperationException("A full computer defense list has no zero terminator.");
            }

            if (entries[entry] == 0) {
                break;
            }

            int type = Short.toUnsignedInt(entries[entry]) - 1;

            this.game.getComputerTowns().requestUnit(player, index, type, priority, false, false);

            total += air ? phase.getAirValue(type) : phase.getGroundValue(type);

            if (player.isCampaign() && Integer.compareUnsigned(needed, total) <= 0) {
                break;
            }
        }

        return total;
    }

    /**
     * Writes the region records of one owner as saved records: the BYTE and WORD fields as in memory, then the unit
     * handles of the air target, the ground target, the leader and the patrol unit, and the one-based head of the
     * military list.
     *
     * @param data       the section of the owner, little-endian
     * @param owner      the owner in {@code 0..7}
     * @param references the unit references of the saved game
     */
    void writeSaveRecords(@NotNull ByteBuffer data, int owner, @NotNull SaveReferences references) {
        ComputerPlayerPhase.clearSaveRange(data, this.regionCount * SAVED_RECORD_SIZE);

        for (int region = 0; region < this.regionCount; region++) {
            int index = this.getIndex(owner, region);
            int offset = region * SAVED_RECORD_SIZE;

            data.putShort(offset + SAVED_REGION, (short) region);
            data.putShort(offset + SAVED_LINKED_REGION, this.linkedRegions[index]);
            data.put(offset + SAVED_OWNER, (byte) owner);
            data.put(offset + SAVED_STATE, this.states[index]);
            data.put(offset + SAVED_STATE_TIMER, this.stateTimers[index]);
            data.put(offset + SAVED_COOLDOWN, this.cooldowns[index]);
            data.put(offset + SAVED_FLAGS, this.flags[index]);
            data.put(offset + SAVED_RETRIES, this.retries[index]);
            data.putShort(offset + SAVED_MILITARY_COUNT, this.militaryCounts[index]);
            data.putShort(offset + SAVED_REQUIRED_GROUND, this.requiredGroundStrength[index]);
            data.putShort(offset + SAVED_REQUIRED_AIR, this.requiredAirStrength[index]);
            data.putShort(offset + SAVED_REGION_GROUND, this.regionGroundStrength[index]);
            data.putShort(offset + SAVED_REGION_AIR, this.regionAirStrength[index]);
            data.putShort(offset + SAVED_ASSIGNED_GROUND, this.assignedGroundStrength[index]);
            data.putShort(offset + SAVED_ASSIGNED_AIR, this.assignedAirStrength[index]);
            data.putShort(offset + SAVED_ENEMY_AIR, this.enemyAirStrength[index]);
            data.putShort(offset + SAVED_ENEMY_GROUND, this.enemyGroundStrength[index]);

            data.putInt(offset + SAVED_AIR_TARGET, references.encodeUnit(this.airTargets[index]));
            data.putInt(offset + SAVED_GROUND_TARGET, references.encodeUnit(this.groundTargets[index]));
            data.putInt(offset + SAVED_LEADER, references.encodeUnit(this.leaders[index]));
            data.putInt(offset + SAVED_PATROL_UNIT, references.encodeUnit(this.patrolUnits[index]));
            data.putInt(offset + SAVED_FIRST_MILITARY, ComputerUnitRecord.encodeSaveLink(this.firstMilitaryRecords[index]));
        }
    }

    /**
     * Reads the region records of one owner that {@link #writeSaveRecords} wrote; the saved region and owner fields are
     * implied by the record's place.
     *
     * @param data       the section of the owner, little-endian
     * @param owner      the owner in {@code 0..7}
     * @param military   the military record pool the lists index
     * @param references the unit references of the restored game
     */
    void readSaveRecords(@NotNull ByteBuffer data, int owner, @NotNull ComputerUnitRecord @NotNull [] military, @NotNull SaveReferences references) {
        for (int region = 0; region < this.regionCount; region++) {
            int index = this.getIndex(owner, region);
            int offset = region * SAVED_RECORD_SIZE;

            this.linkedRegions[index]          = data.getShort(offset + SAVED_LINKED_REGION);
            this.states[index]                 = data.get(offset + SAVED_STATE);
            this.stateTimers[index]            = data.get(offset + SAVED_STATE_TIMER);
            this.cooldowns[index]              = data.get(offset + SAVED_COOLDOWN);
            this.flags[index]                  = data.get(offset + SAVED_FLAGS);
            this.retries[index]                = data.get(offset + SAVED_RETRIES);
            this.militaryCounts[index]         = data.getShort(offset + SAVED_MILITARY_COUNT);
            this.requiredGroundStrength[index] = data.getShort(offset + SAVED_REQUIRED_GROUND);
            this.requiredAirStrength[index]    = data.getShort(offset + SAVED_REQUIRED_AIR);
            this.regionGroundStrength[index]   = data.getShort(offset + SAVED_REGION_GROUND);
            this.regionAirStrength[index]      = data.getShort(offset + SAVED_REGION_AIR);
            this.assignedGroundStrength[index] = data.getShort(offset + SAVED_ASSIGNED_GROUND);
            this.assignedAirStrength[index]    = data.getShort(offset + SAVED_ASSIGNED_AIR);
            this.enemyAirStrength[index]       = data.getShort(offset + SAVED_ENEMY_AIR);
            this.enemyGroundStrength[index]    = data.getShort(offset + SAVED_ENEMY_GROUND);

            this.airTargets[index]           = references.decodeUnit(data.getInt(offset + SAVED_AIR_TARGET));
            this.groundTargets[index]        = references.decodeUnit(data.getInt(offset + SAVED_GROUND_TARGET));
            this.leaders[index]              = references.decodeUnit(data.getInt(offset + SAVED_LEADER));
            this.patrolUnits[index]          = references.decodeUnit(data.getInt(offset + SAVED_PATROL_UNIT));
            this.firstMilitaryRecords[index] = ComputerUnitRecord.decodeSaveLink(military, data.getInt(offset + SAVED_FIRST_MILITARY));
        }
    }

    /**
     * Converts a region record index to a saved reference.
     *
     * @param index the region record index {@code owner * regionCount + region}, or {@code -1}
     * @return the one-based region plus the owner times {@value #SAVED_REFERENCE_OWNER_STRIDE}, or {@code 0}
     */
    int encodeSaveReference(int index) {
        return index < 0 ? 0 : this.getRegion(index) + 1 + this.getOwner(index) * SAVED_REFERENCE_OWNER_STRIDE;
    }

    /**
     * Converts a saved reference back to a region record index: an owner above {@code 7} or a region outside
     * {@code 1..regionCount} names no record.
     *
     * @param value the saved reference
     * @return the region record index, or {@code -1}
     */
    int decodeSaveReference(int value) {
        int owner = Integer.divideUnsigned(value, SAVED_REFERENCE_OWNER_STRIDE);
        int region = value - owner * SAVED_REFERENCE_OWNER_STRIDE;

        if (owner >= PlayerTable.PLAYABLE_PLAYER_SLOTS || region == 0 || region > this.regionCount) {
            return -1;
        }

        return this.getIndex(owner, region - 1);
    }

    /**
     * Includes every region record in the state hash.
     *
     * @param hash the preceding diagnostic hash
     * @return the hash including these records
     */
    int computeStateHash(int hash) {
        for (int index = 0; index < this.states.length; index++) {
            hash = HASH_MULTIPLIER * hash + Short.toUnsignedInt(this.linkedRegions[index]);
            hash = HASH_MULTIPLIER * hash + Byte.toUnsignedInt(this.states[index]);
            hash = HASH_MULTIPLIER * hash + Byte.toUnsignedInt(this.stateTimers[index]);
            hash = HASH_MULTIPLIER * hash + Byte.toUnsignedInt(this.cooldowns[index]);
            hash = HASH_MULTIPLIER * hash + Byte.toUnsignedInt(this.flags[index]);
            hash = HASH_MULTIPLIER * hash + Byte.toUnsignedInt(this.retries[index]);
            hash = HASH_MULTIPLIER * hash + Short.toUnsignedInt(this.requiredGroundStrength[index]);
            hash = HASH_MULTIPLIER * hash + Short.toUnsignedInt(this.requiredAirStrength[index]);
            hash = HASH_MULTIPLIER * hash + Short.toUnsignedInt(this.regionGroundStrength[index]);
            hash = HASH_MULTIPLIER * hash + Short.toUnsignedInt(this.regionAirStrength[index]);
            hash = HASH_MULTIPLIER * hash + Short.toUnsignedInt(this.assignedGroundStrength[index]);
            hash = HASH_MULTIPLIER * hash + Short.toUnsignedInt(this.assignedAirStrength[index]);
            hash = HASH_MULTIPLIER * hash + Short.toUnsignedInt(this.enemyAirStrength[index]);
            hash = HASH_MULTIPLIER * hash + Short.toUnsignedInt(this.enemyGroundStrength[index]);
            hash = HASH_MULTIPLIER * hash + (this.airTargets[index] == null ? 0 : this.airTargets[index].getIndex());
            hash = HASH_MULTIPLIER * hash + (this.groundTargets[index] == null ? 0 : this.groundTargets[index].getIndex());
            hash = HASH_MULTIPLIER * hash + (this.patrolUnits[index] == null ? 0 : this.patrolUnits[index].getIndex());
            hash = HASH_MULTIPLIER * hash + (this.leaders[index] == null ? 0 : this.leaders[index].getIndex());
            hash = HASH_MULTIPLIER * hash + (this.firstMilitaryRecords[index] == null ? -1 : this.firstMilitaryRecords[index].getPoolIndex());
            hash = HASH_MULTIPLIER * hash + this.militaryCounts[index];
        }

        return hash;
    }
}
