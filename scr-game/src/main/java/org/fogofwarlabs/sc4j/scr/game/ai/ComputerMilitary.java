package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrWeaponIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitComputerFlags;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.save.ComputerSaveMetadata;
import org.fogofwarlabs.sc4j.scr.game.save.SaveReferences;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegions;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Retains the computer guard and military record pools and the routines that bind combat units to them.
 * <p>
 * Guard records remember a unit's original position; each owner keeps its active guards in a list. Military records
 * link a unit to a region record. Both pools are single global free lists of {@code 1000} records initialized in index order.
 */
public final class ComputerMilitary {
    /**
     * The size of the guard and of the military record pool.
     */
    private static final int POOL_SIZE = 1000;

    /**
     * The saved military and guard record sections: the records and the free head DWORD.
     */
    static final int SAVED_MILITARY_RECORDS_SIZE = POOL_SIZE * ComputerUnitRecord.SAVED_MILITARY_SIZE + Integer.BYTES;
    static final int SAVED_GUARD_RECORDS_SIZE = POOL_SIZE * ComputerUnitRecord.SAVED_GUARD_SIZE + Integer.BYTES;
    /**
     * A saved list view: the DWORD base {@code 0} and the DWORD one-based head.
     */
    static final int SAVED_LIST_VIEW_SIZE = Integer.BYTES + Integer.BYTES;
    static final int SAVED_LIST_VIEWS_SIZE = PlayerTable.PLAYABLE_PLAYER_SLOTS * SAVED_LIST_VIEW_SIZE;

    /**
     * Unit types {@code PROTOSS_DARK_ARCHON + bit} a completed unit is not bound for: Dark Archon, Archon, Observer and Lurker.
     */
    private static final long UNREGISTERED_TYPES = 0x10000200021L;
    /**
     * The highest bit of {@link #UNREGISTERED_TYPES} the test reads.
     */
    private static final int UNREGISTERED_LAST_OFFSET = 0x28;

    /**
     * The request priority of a guard's unit and of the sources of its morph.
     */
    private static final int GUARD_REQUEST_PRIORITY = 0x32;
    /**
     * The clock ticks a guard waits after its first request, plus a fifteenth of the build time.
     */
    private static final int FIRST_REFILL_DELAY = 5;
    private static final int REFILL_BUILD_TIME_DIVISOR = 15;
    /**
     * The clock ticks a guard that was refilled before waits after its request.
     */
    private static final int REFILL_DELAY = 300;
    /**
     * A campaign owner releases a guard refilled this many times.
     */
    private static final int CAMPAIGN_REFILL_LIMIT = 3;
    /**
     * The refill count of a guard stops at this value.
     */
    private static final int REFILL_COUNT_LIMIT = 100;
    /**
     * The {@code place_guard} positions: the town position and the town's mineral target; others name a refinery.
     */
    private static final int GUARD_POSITION_TOWN = 0;
    private static final int GUARD_POSITION_MINERALS = 1;
    /**
     * The distance within which an existing guard counts as near a position.
     */
    private static final int GUARD_SPACING = 0x120;

    /**
     * Unit types {@code TERRAN_SCIENCE_VESSEL + bit} that keep their order in an attacking region: Science Vessel, Queen,
     * Defiler and High Templar.
     */
    private static final long HARASS_EXEMPT_TYPES = 0x400003000000001L;
    /**
     * The type offsets of {@link #HARASS_EXEMPT_TYPES} the test reads are below this.
     */
    private static final int HARASS_EXEMPT_OFFSET_LIMIT = 0x3B;
    /**
     * The random source of the scattered destination around a safe route.
     */
    private static final int SCATTER_RANDOM_SOURCE = 0x38;
    /**
     * The random bit that picks the side of the scatter, shifted into the half-turn bit.
     */
    private static final int SCATTER_SIDE_SHIFT = 4;
    /**
     * The random scatter length and extent change are below {@code 0x80}; the extent change is centered on zero.
     */
    private static final int SCATTER_LENGTH_MASK = 0x7F;
    private static final int SCATTER_EXTENT_OFFSET = 0x40;
    /**
     * The shift of the vertical component in the upper {@code 32} bits of a packed direction vector.
     */
    private static final int HIGH_DWORD_SHIFT = Integer.SIZE;

    /**
     * The clock ticks the route-safety marks of an owner stay valid.
     */
    private static final int SAFETY_CACHE_TICKS = 10;
    /**
     * The route-safety mark of a reached region whose neighbors are not visited yet.
     */
    private static final int MARK_OPEN = 1;
    /**
     * The route-safety mark of a visited region or of a reached region with a target.
     */
    private static final int MARK_CLOSED = 2;

    /**
     * Unit types {@code TERRAN_FIREBAT + bit} that defend at any hit points: Firebat, Zergling, Hydralisk, Zealot and
     * Dragoon.
     */
    private static final long ANY_LIFE_DEFENSE_TYPES = 0x600000061L;
    /**
     * The highest bit of {@link #ANY_LIFE_DEFENSE_TYPES} the test reads.
     */
    private static final int ANY_LIFE_DEFENSE_LAST_OFFSET = 0x22;
    /**
     * A defense candidate outside {@link #ANY_LIFE_DEFENSE_TYPES} needs at least its maximum hit points divided by this.
     */
    private static final int DEFENSE_LIFE_DIVISOR = 2;

    /**
     * The low {@code 16} bits of a Build Time entry or of a requested unit type.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The multiplier of the state hash.
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * Refills a guard: the unit takes the record and the guard returns to its original position.
     */
    static void refillGuard(@NotNull ComputerUnitRecord guard, @NotNull Unit unit) {
        guard.guardX = guard.homeX;
        guard.guardY = guard.homeY;
        guard.unit   = unit;

        unit.setComputerRecord(guard);
    }

    /**
     * Empties the guard of a leaving unit; the guard keeps its record for refilling and counts the refill.
     *
     * @param unit the leaving unit
     */
    static void releaseGuard(@NotNull Unit unit) {
        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null || record.getKind() != ComputerUnitRecord.KIND_GUARD) {
            return;
        }

        record.unit = null;

        if (record.guardRetries < REFILL_COUNT_LIMIT) {
            record.guardRetries++;
        }
    }

    /**
     * Sets the current guard position of a unit bound to a guard record; a unit without a guard record is unchanged.
     *
     * @param unit      the unit
     * @param positionX the horizontal guard position, stored as a WORD
     * @param positionY the vertical guard position, stored as a WORD
     */
    public static void setGuardPosition(@NotNull Unit unit, int positionX, int positionY) {
        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null || record.getKind() != ComputerUnitRecord.KIND_GUARD) {
            return;
        }

        record.guardX = (short) positionX;
        record.guardY = (short) positionY;
    }

    /**
     * @param game the game
     * @param unit the unit
     * @return {@code true} if the base type of the unit is the Firebat, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    static boolean isFirebat(@NotNull Game game, @NotNull Unit unit) {
        return game.requireGameData().getBaseType(unit) == ScrUnitIds.TERRAN_FIREBAT;
    }

    /**
     * Returns the unit a guard or military record is bound to.
     *
     * @param record the guard or military record
     * @return the record's unit
     * @throws IllegalStateException if the record holds no unit
     */
    static @NotNull Unit requireRecordUnit(@NotNull ComputerUnitRecord record) {
        Unit unit = record.unit;

        if (unit == null) {
            throw Exceptions.newIllegalStateException("Computer record {0} of kind {1} holds no unit.", record.getPoolIndex(), record.getKind());
        }

        return unit;
    }

    /**
     * Returns the guard record or region record index a guard or region request recorded in a build-queue slot.
     *
     * @throws IllegalStateException if the request of the slot has no target
     */
    private static @NotNull Object requireQueueTarget(@Nullable Object target, int slot) {
        if (target == null) {
            throw Exceptions.newIllegalStateException("The computer request of build-queue slot {0} has no target.", slot);
        }

        return target;
    }


    private final @NotNull Game game;

    private final @NotNull ComputerUnitRecord @NotNull [] militaryRecords;
    private final @NotNull ComputerUnitRecord @NotNull [] guardRecords;
    private final @Nullable ComputerUnitRecord @NotNull [] firstGuards;
    private @Nullable ComputerUnitRecord firstFreeMilitary;
    private @Nullable ComputerUnitRecord firstFreeGuard;

    /**
     * The route-safety cache of each owner: the clock of the marks, their source region and the region marks.
     */
    private final int @NotNull [] safetyClocks;
    private final int @NotNull [] safetySources;
    private final byte @NotNull [] @NotNull [] safetyMarks;

    /**
     * Creates a {@link ComputerMilitary} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public ComputerMilitary(@NotNull Game game) {
        this.game = game;

        this.militaryRecords = new ComputerUnitRecord[POOL_SIZE];
        this.guardRecords    = new ComputerUnitRecord[POOL_SIZE];
        this.firstGuards     = new ComputerUnitRecord[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        for (int index = 0; index < POOL_SIZE; index++) {
            this.militaryRecords[index] = new ComputerUnitRecord(ComputerUnitRecord.KIND_MILITARY, index);
            this.guardRecords[index]    = new ComputerUnitRecord(ComputerUnitRecord.KIND_GUARD, index);
        }

        for (int index = 0; index < POOL_SIZE; index++) {
            this.militaryRecords[index].previous = index == 0 ? null : this.militaryRecords[index - 1];
            this.militaryRecords[index].next     = index == POOL_SIZE - 1 ? null : this.militaryRecords[index + 1];
            this.guardRecords[index].previous    = index == 0 ? null : this.guardRecords[index - 1];
            this.guardRecords[index].next        = index == POOL_SIZE - 1 ? null : this.guardRecords[index + 1];
        }

        this.firstFreeMilitary = this.militaryRecords[0];
        this.firstFreeGuard    = this.guardRecords[0];

        this.safetyClocks  = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.safetySources = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.safetyMarks   = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS][ComputerPlayerPhase.REGION_CAPACITY];
    }

    /**
     * @return the military record pool
     */
    @NotNull ComputerUnitRecord @NotNull [] getMilitaryRecords() {
        return this.militaryRecords;
    }

    /**
     * @return the guard record pool
     */
    @NotNull ComputerUnitRecord @NotNull [] getGuardRecords() {
        return this.guardRecords;
    }

    /**
     * @param kind      {@link ComputerUnitRecord#KIND_GUARD} or {@link ComputerUnitRecord#KIND_MILITARY}
     * @param poolIndex the record index below the pool size
     * @return the guard or military record
     */
    @NotNull ComputerUnitRecord getRecord(int kind, int poolIndex) {
        return kind == ComputerUnitRecord.KIND_GUARD ? this.guardRecords[poolIndex] : this.militaryRecords[poolIndex];
    }

    /**
     * @param owner     the owner
     * @param positionX the horizontal position
     * @param positionY the vertical position
     * @return {@code true} if one of the owner's guards is within {@link #GUARD_SPACING} pixels of the position, {@code false} otherwise
     */
    boolean hasGuardNear(int owner, int positionX, int positionY) {
        for (ComputerUnitRecord guard = this.firstGuards[owner]; guard != null; guard = guard.next) {
            int distance = RegionPathSearch.calculateDistance((short) guard.guardX, positionX, (short) guard.guardY, positionY);

            if (Integer.compareUnsigned(distance, GUARD_SPACING) <= 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Orders a region's military unit toward a position, scattering the destination around the route when the approach
     * is safe.
     *
     * @param phase            the owning phase
     * @param unit             the unit
     * @param order            the requested order
     * @param positionX        the horizontal destination
     * @param positionY        the vertical destination
     * @param instructionLimit the iscript budget for issued orders
     * @throws NullPointerException if the world was built without game data
     */
    void issueRegionOrder(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, int order, int positionX, int positionY, int instructionLimit) {
        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null || record.getKind() != ComputerUnitRecord.KIND_MILITARY || record.unit != unit) {
            return;
        }

        if (UnitQueries.isBuilding(this.game.requireGameData().getUnitTypes(), unit.getUnitId()) ||
            (unit.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
            return;
        }

        int primaryOrder = unit.getPrimaryOrder();

        if (primaryOrder == ScrOrderIds.COMPLETING_ARCHON_SUMMON || primaryOrder == ScrOrderIds.RESET_COLLISION) {
            return;
        }

        if ((unit.getStatus() & UnitStatusFlags.IN_BUNKER) != 0) {
            // A bunkered unit unloads instead of taking the sieging or move early return.
            this.game.getUnitTransport().unload(unit.getOwnerIndex(), unit, instructionLimit);
        } else if (primaryOrder == ScrOrderIds.SIEGING || primaryOrder == ScrOrderIds.MOVE) {
            return;
        }

        // A sieged Siege Tank stays sieged while threatened and attacks instead; otherwise it unsieges and the region
        // order is queued behind the unsieging.
        boolean unsieging = false;

        if (this.game.requireGameData().getBaseType(unit) == ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE) {
            if (unit.getPrimaryOrder() == ScrOrderIds.UNSIEGING) {
                return;
            }

            if (this.game.getComputerCombat().isSiegeThreatened(unit)) {
                if (this.game.getComputerCombat().acquireTarget(unit, 0, true, false, instructionLimit)) {
                    return;
                }

                Unit target = this.game.getComputerCombat().findAcquireTarget(unit);

                if (target == null) {
                    return;
                }

                this.game.getComputerCombat().clearOrderTargets(unit);
                this.game.getComputerCombat().attack(unit, target, false, 0, instructionLimit);

                return;
            }

            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.UNSIEGING, instructionLimit);

            unsieging = true;
        }

        ComputerRegions regions = phase.getRegions();
        int index = record.regionIndex;
        int type = this.game.requireGameData().getBaseType(unit);
        int typeOffset = type - ScrUnitIds.TERRAN_SCIENCE_VESSEL;
        boolean harassExempt = typeOffset >= 0 && typeOffset < HARASS_EXEMPT_OFFSET_LIMIT && (HARASS_EXEMPT_TYPES >>> typeOffset & 1) != 0;
        int regionState = regions.getState(index);

        if (!harassExempt &&
            (regionState == ComputerRegionStates.ATTACK || regionState == ComputerRegionStates.ATTACK_GATHERING ||
             regionState == ComputerRegionStates.ATTACK_LAUNCHED)) {
            order = type == ScrUnitIds.TERRAN_MEDIC ? ScrOrderIds.HEAL_MOVE : ScrOrderIds.HARASS_MOVE;
        }

        int status = unit.getStatus();

        if ((status & UnitStatusFlags.LOADED) == 0 || (status & UnitStatusFlags.IN_BUNKER) != 0) {
            if (this.isRouteSafe(unit, positionX, positionY)) {
                TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
                int unitRegion = this.game.getComputerTerrain().getUnitRegion(unit);
                int centerX = (short) terrain.getCenterX(unitRegion);
                int centerY = (short) terrain.getCenterY(unitRegion);
                int direction = FlingyMotionUtils.calculateDirection(positionX - centerX, positionY - centerY);
                int side = this.game.getRandom().next(SCATTER_RANDOM_SOURCE);

                direction += ~(side << SCATTER_SIDE_SHIFT) & FlingyMotionUtils.DIRECTION_HALF_TURN | FlingyMotionUtils.DIRECTION_QUARTER_TURN;

                if (direction >= FlingyMotionUtils.DIRECTION_FULL_TURN) {
                    direction -= FlingyMotionUtils.DIRECTION_FULL_TURN;
                }

                int length = this.game.getRandom().next(SCATTER_RANDOM_SOURCE) & SCATTER_LENGTH_MASK;
                long offset = FlingyMotionUtils.calculateDirectionVector(direction, length);
                int pointX = (int) offset + positionX;
                int pointY = (int) (offset >> HIGH_DWORD_SHIFT) + positionY;
                int approach = FlingyMotionUtils.calculateDirection(pointX - centerX, pointY - centerY);
                int distance = RegionPathSearch.calculateDistance(centerX, pointX, centerY, pointY);
                int extent = (this.game.getRandom().next(SCATTER_RANDOM_SOURCE) & SCATTER_LENGTH_MASK) - SCATTER_EXTENT_OFFSET + distance;

                offset = FlingyMotionUtils.calculateDirectionVector(approach, Math.max(extent, 0));

                int scatteredX = (int) offset + centerX;
                int scatteredY = (int) (offset >> HIGH_DWORD_SHIFT) + centerY;

                if (scatteredX >= 0 && scatteredX < this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE &&
                    scatteredY >= 0 && scatteredY < this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE &&
                    terrain.getComponent(this.game.getComputerTerrain().getRegion(scatteredX, scatteredY)) ==
                    terrain.getComponent(this.game.getComputerTerrain().getRegion(positionX, positionY))) {
                    positionX = scatteredX;
                    positionY = scatteredY;
                }
            }

            if (unsieging) {
                if (unit.getFirstOrder() != null) {
                    return;
                }

                this.game.getOrders().queueReplacingSameOrders(unit, order, null, positionX, positionY, ScrUnitIds.NONE);
                this.game.getOrders().finishOrder(unit, instructionLimit);

                return;
            }

            this.game.getOrders().issue(unit, order, null, positionX, positionY, ScrUnitIds.NONE, false, instructionLimit);

            return;
        }

        if ((status & UnitStatusFlags.BURROWED) != 0) {
            this.game.getComputerOrders().unburrowToAttack(unit, instructionLimit);
        } else if (unit.getOrderTargetUnit() == null) {
            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.COMPUTER_AI, instructionLimit);
        }
    }

    /**
     * Tests whether a unit can reach a position safely: a unit without the ground elevation path flag always can; a ground
     * unit needs the position in its ground component and a safe route to it.
     *
     * @param unit      the moving unit
     * @param positionX the horizontal position
     * @param positionY the vertical position
     * @return {@code true} if the unit lacks the ground elevation path flag or reaches the position in its ground component along a safe route,
     * {@code false} otherwise
     */
    public boolean canReachSafely(@NotNull Unit unit, int positionX, int positionY) {
        if ((unit.getPathCollisionFlags() & UnitInitializer.GROUND_ELEVATION_PATH_FLAG) == 0) {
            return true;
        }

        return unit.sharesTerrainComponent(this.game.getComputerTerrain().getRegions(), positionX, positionY) &&
               this.isRouteSafe(unit, positionX, positionY);
    }

    /**
     * Tests whether the route from a unit's region to the destination region avoids targeted regions for its owner.
     *
     * @param unit      the moving unit
     * @param positionX the horizontal position
     * @param positionY the vertical position
     * @return {@code true} if the position's region is reachable without passing a region with a target, {@code false} otherwise
     * @throws UnsupportedOperationException if the computer-player phase is not initialized
     */
    public boolean isRouteSafe(@NotNull Unit unit, int positionX, int positionY) {
        return this.isRouteSafe(this.game.requireComputerPlayerPhase(),
                                unit.getOwnerIndex(),
                                this.game.getComputerTerrain().getUnitRegion(unit),
                                this.game.getComputerTerrain().getRegion(positionX, positionY));
    }

    /**
     * Tests whether the route from a unit's region to another unit's region avoids targeted regions for the unit's owner.
     *
     * @param phase  the owning phase
     * @param unit   the moving unit
     * @param target the unit whose region is the destination
     * @return {@code true} if the target's region is reachable without passing a region with a target, {@code false} otherwise
     */
    boolean isRouteSafe(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, @NotNull Unit target) {
        return this.isRouteSafe(phase,
                                unit.getOwnerIndex(),
                                this.game.getComputerTerrain().getUnitRegion(unit),
                                this.game.getComputerTerrain().getUnitRegion(target));
    }

    /**
     * Tests whether a region route avoids targeted regions, caching its marks per owner for {@link #SAFETY_CACHE_TICKS} clock ticks.
     *
     * @param phase       the owning phase
     * @param owner       the moving units' owner
     * @param source      the start region
     * @param destination the destination region
     * @return {@code true} if the destination is reachable without passing a region with a target, {@code false} otherwise
     * @throws UnsupportedOperationException if the source region's neighbor count underflows
     */
    public boolean isRouteSafe(@NotNull ComputerPlayerPhase phase, int owner, int source, int destination) {
        if (owner >= PlayerTable.PLAYABLE_PLAYER_SLOTS || source == destination) {
            return true;
        }

        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();

        if (terrain.getComponent(source) != terrain.getComponent(destination)) {
            return false;
        }

        int count = ComputerTerrain.getPathNeighborCount(terrain, source);

        if (count < 0) {
            throw Exceptions.newUnsupportedOperationException("Region {0} lists more region-zero neighbors than neighbors.", source);
        }

        for (int neighborIndex = 0; neighborIndex < count; neighborIndex++) {
            if (terrain.getNeighborRegion(source, neighborIndex) == destination) {
                return true;
            }
        }

        ComputerRegions regions = phase.getRegions();
        Unit airTarget = regions.getAirTarget(regions.getIndex(owner, source));

        // The air weapon is the unit's or its turret's.
        if (airTarget != null && this.game.getUnitTargeting().getUnitOrSubunitAirWeapon(airTarget) != ScrWeaponIds.NONE) {
            return true;
        }

        byte[] marks = this.safetyMarks[owner];
        int clock = phase.getElapsedClockTicks();

        if (Integer.compareUnsigned(clock, this.safetyClocks[owner] + SAFETY_CACHE_TICKS) >= 0 || this.safetySources[owner] != source) {
            this.safetyClocks[owner]  = clock;
            this.safetySources[owner] = source;

            Arrays.fill(marks, (byte) 0);

            marks[source] = MARK_OPEN;

            int regionCount = terrain.getRegionCount();
            boolean changed = true;

            while (changed && regionCount > 1) {
                changed = false;

                for (int region = 1; region < regionCount; region++) {
                    if (marks[region] != MARK_OPEN) {
                        continue;
                    }

                    marks[region] = MARK_CLOSED;

                    // Only walkable neighbors spread the marks.
                    int neighbors = terrain.getWalkableNeighborCount(region);

                    for (int neighborIndex = 0; neighborIndex < neighbors; neighborIndex++) {
                        int neighbor = terrain.getNeighborRegion(region, neighborIndex);

                        if (marks[neighbor] != 0) {
                            continue;
                        }

                        int neighborIndexInRecords = regions.getIndex(owner, neighbor);

                        marks[neighbor] = (byte) (regions.getAirTarget(neighborIndexInRecords) != null ||
                                                  regions.getGroundTarget(neighborIndexInRecords) != null ? MARK_CLOSED : MARK_OPEN);
                        changed         = true;
                    }
                }
            }
        }

        return marks[destination] == MARK_CLOSED;
    }

    /**
     * @param owner the owner
     * @param type  the guarded unit type
     * @return {@code true} if an empty guard of the type waits in a quiet region, {@code false} otherwise
     * @throws UnsupportedOperationException if the computer-player phase is not initialized
     */
    boolean hasEmptyGuard(int owner, int type) {
        ComputerRegions regions = this.game.requireComputerPlayerPhase().getRegions();

        for (ComputerUnitRecord guard = this.firstGuards[owner]; guard != null; guard = guard.next) {
            if (guard.unit == null && guard.guardType == type &&
                regions.isQuiet(owner, this.game.getComputerTerrain().getRegion(guard.homeX, guard.homeY))) {
                return true;
            }
        }

        return false;
    }


    /**
     * Binds a combat unit completed by a computer producer to a guard or region record, as the producer's build-queue
     * request recorded it.
     *
     * @param phase            the owning phase
     * @param producer         the producing unit, or the unit itself for an in-place transformation
     * @param unit             the completed unit
     * @param instructionLimit the iscript budget for issued orders
     * @throws IllegalStateException         if the producer's guard or region request has no target
     * @throws UnsupportedOperationException if the unit transformed in place and has a military or guard record
     */
    void register(@NotNull ComputerPlayerPhase phase, @NotNull Unit producer, @NotNull Unit unit, int instructionLimit) {
        int type = this.game.requireGameData().getBaseType(unit);

        if (type == ScrUnitIds.ZERG_GUARDIAN || type == ScrUnitIds.ZERG_DEVOURER) {
            return;
        }

        ComputerRegions regions = phase.getRegions();

        if ((unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
            this.link(phase, regions.getIndex(unit.getOwnerIndex(), this.game.getComputerTerrain().getUnitRegion(unit)), unit, instructionLimit);

            return;
        }

        int offset = type - ScrUnitIds.PROTOSS_DARK_ARCHON;

        if (offset >= 0 && offset <= UNREGISTERED_LAST_OFFSET && (UNREGISTERED_TYPES >>> offset & 1) != 0 ||
            type == ScrUnitIds.TERRAN_NUCLEAR_MISSILE) {
            return;
        }

        ComputerUnitRecord producerRecord = producer.getComputerRecord();

        if (producerRecord == null) {
            return;
        }

        int slot;

        if (producer == unit) {
            phase.releaseBuildingRecord(unit);

            slot = 0;
        } else {
            slot = this.game.requireGameData().getBaseType(producer) == type ? 0 : producer.getBuildQueueIndex();
        }

        int kind = producerRecord.queueKinds[slot];
        Object target = producerRecord.queueTargets[slot];

        if (kind == ComputerPlayer.REQUEST_KIND_GUARD) {
            ComputerUnitRecord guard = (ComputerUnitRecord) requireQueueTarget(target, slot);

            if (guard.unit == null) {
                refillGuard(guard, unit);

                return;
            }

            kind                              = ComputerPlayer.REQUEST_KIND_REGION;
            producerRecord.queueKinds[slot]   = ComputerPlayer.REQUEST_KIND_REGION;
            target                            = regions.getIndex(unit.getOwnerIndex(), this.game.getComputerTerrain().getUnitRegion(unit));
            producerRecord.queueTargets[slot] = target;
        }

        if (kind == ComputerPlayer.REQUEST_KIND_REGION) {
            this.addToRegion(phase, (Integer) requireQueueTarget(target, slot), unit, false, instructionLimit);
        } else {
            this.addGuard(unit);
        }
    }

    /**
     * Guards a computer unit at its position: reuses the owner's empty guard for the same type and position or takes a
     * new one.
     *
     * @param unit the unit
     * @throws IllegalStateException if a computer unit to guard has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    void addGuard(@NotNull Unit unit) {
        int owner = unit.getOwnerIndex();
        Player player = this.game.getPlayerTable().getPlayer(owner);

        if (player == null || player.getType() != PlayerTypes.COMPUTER) {
            return;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = this.game.requireGameData().getBaseType(unit);

        if ((units.getComputerInternalFlags(type) & UnitComputerFlags.TOWN_UNIT) != 0) {
            return;
        }

        Sprite sprite = unit.requireSprite();
        int positionX = (short) sprite.getX();
        int positionY = (short) sprite.getY();
        ComputerUnitRecord guard = null;

        for (ComputerUnitRecord candidate = this.firstGuards[owner]; candidate != null; candidate = candidate.next) {
            if (candidate.unit == null && candidate.guardType == type && candidate.homeX == positionX && candidate.homeY == positionY) {
                guard = candidate;

                break;
            }
        }

        if (guard == null) {
            guard = this.firstFreeGuard;

            if (guard == null) {
                return;
            }

            this.firstFreeGuard = guard.next;

            if (guard.next != null) {
                guard.next.previous = null;
            }

            guard.previous = null;
            guard.next     = this.firstGuards[owner];

            if (guard.next != null) {
                guard.next.previous = guard;
            }

            this.firstGuards[owner] = guard;

            guard.guardType = type;
        }

        guard.unit         = unit;
        guard.guardX       = positionX;
        guard.guardY       = positionY;
        guard.homeX        = positionX;
        guard.homeY        = positionY;
        guard.guardRetries = 0;

        unit.setComputerRecord(guard);
    }

    /**
     * Returns the owner's guards without a unit to the free list.
     *
     * @param owner the owner
     */
    void releaseEmptyGuards(int owner) {
        ComputerUnitRecord guard = this.firstGuards[owner];

        while (this.firstGuards[owner] != null && guard != null) {
            ComputerUnitRecord next = guard.next;

            if (guard.unit == null) {
                this.releaseGuardRecord(owner, guard);
            }

            guard = next;
        }
    }

    /**
     * Unlinks a guard from the owner's list and pushes it on the free list. The released unit keeps its record
     * reference.
     */
    private void releaseGuardRecord(int owner, @NotNull ComputerUnitRecord guard) {
        if (this.firstGuards[owner] == guard) {
            this.firstGuards[owner] = guard.next;
        }

        if (guard.previous != null) {
            guard.previous.next = guard.next;
        }

        if (guard.next != null) {
            guard.next.previous = guard.previous;
        }

        guard.previous = null;
        guard.next     = this.firstFreeGuard;

        if (this.firstFreeGuard != null) {
            this.firstFreeGuard.previous = guard;
        }

        this.firstFreeGuard = guard;
    }

    /**
     * Updates the owner's guards: releases the guards of hallucinations and, for a campaign owner, guards refilled {@link #CAMPAIGN_REFILL_LIMIT}
     * times; requests a unit for each empty guard whose home region is quiet, reusing a nearby surplus Zergling or Scourge.
     *
     * @param phase            the owning phase
     * @param owner            the computer owner
     * @param instructionLimit the iscript budget for issued orders
     */
    void update(@NotNull ComputerPlayerPhase phase, int owner, int instructionLimit) {
        ComputerPlayer player = phase.getPlayer(owner);
        ComputerRegions regions = phase.getRegions();
        ComputerUnitRecord guard = this.firstGuards[owner];

        while (this.firstGuards[owner] != null && guard != null) {
            ComputerUnitRecord next = guard.next;

            if (player.isCampaign() && guard.guardRetries >= CAMPAIGN_REFILL_LIMIT) {
                this.releaseGuardRecord(owner, guard);
            } else if (guard.unit != null) {
                if ((guard.unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
                    this.releaseGuardRecord(owner, guard);
                }
            } else if (!this.isRequestPending(guard)) {
                if (regions.isQuiet(owner, this.game.getComputerTerrain().getRegion((short) guard.homeX, (short) guard.homeY))) {
                    int type = guard.guardType;
                    Unit reused = type == ScrUnitIds.ZERG_ZERGLING || type == ScrUnitIds.ZERG_SCOURGE
                                  ? this.findSurplusUnit(phase, owner, type, guard.homeX, guard.homeY)
                                  : null;

                    if (reused != null) {
                        phase.removeUnit(reused, false);

                        refillGuard(guard, reused);
                        this.game.getOrders().issueUntargeted(reused, ScrOrderIds.COMPUTER_AI, instructionLimit);
                    } else {
                        this.requestGuard(player, regions, guard);

                        guard.guardRequest = this.game.getComputerTowns().getElapsedClockTicks();
                    }
                }
            }

            guard = next;
        }
    }

    /**
     * Runs the {@link ScrOrderIds#COMPUTER_AI} step of a unit in a region's military list.
     *
     * @param phase            the owning phase
     * @param unit             the unit
     * @param instructionLimit the iscript budget for issued orders
     * @throws IllegalStateException         if a loadable Bunker has no sprite or a record of an attacking region holds no unit
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    void update(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, int instructionLimit) {
        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null || record.getKind() != ComputerUnitRecord.KIND_MILITARY || record.unit != unit ||
            UnitQueries.isBuilding(this.game.requireGameData().getUnitTypes(), unit.getUnitId())) {
            return;
        }

        ComputerRegions regions = phase.getRegions();
        int index = record.regionIndex;
        int type = this.game.requireGameData().getBaseType(unit);

        if (type == ScrUnitIds.TERRAN_MARINE || type == ScrUnitIds.TERRAN_FIREBAT) {
            int region = regions.getRegion(index);

            ComputerTerrain query = this.game.getComputerTerrain();
            int found = query.collectRegionUnits(region);
            int[] candidates = new int[found];

            for (int candidateIndex = 0; candidateIndex < found; candidateIndex++) {
                candidates[candidateIndex] = query.getRegionUnit(candidateIndex);
            }

            for (int poolIndex : candidates) {
                Unit bunker = this.game.getUnitSystem().requireUnitPool().getUnit(poolIndex);

                if (this.game.requireGameData().getBaseType(bunker) != ScrUnitIds.TERRAN_BUNKER || bunker.getOwnerIndex() != unit.getOwnerIndex()) {
                    continue;
                }

                if (isFirebat(this.game, unit) && this.game.getUnitTransport().forEachLoadedUnit(bunker, loaded -> isFirebat(this.game, loaded))) {
                    continue;
                }

                if (!this.game.getUnitTransport().canLoad(bunker, unit)) {
                    continue;
                }

                Sprite sprite = bunker.requireSprite();

                this.game.getOrders().issue(unit,
                                            ScrOrderIds.ENTER_TRANSPORT,
                                            bunker,
                                            sprite.getX(), sprite.getY(),
                                            ScrUnitIds.NONE,
                                            false,
                                            instructionLimit);

                return;
            }
        }

        if (ComputerSpells.cast(phase, this.game, unit, false)) {
            return;
        }

        if (this.acquireTarget(unit)) {
            return;
        }

        int destination = regions.getRegion(index);

        if (regions.getState(index) == ComputerRegionStates.ATTACK) {
            // An attacking region follows its leader while any list unit is incomplete, merging or resetting collision.
            if (regions.getLeader(index) == null) {
                regions.updateLeader(phase, index);
            }

            Unit leader = regions.getLeader(index);

            if (leader != null) {
                for (ComputerUnitRecord member = regions.getFirstMilitaryRecord(index); member != null; member = member.next) {
                    Unit memberUnit = requireRecordUnit(member);

                    if ((memberUnit.getStatus() & UnitStatusFlags.COMPLETED) == 0 ||
                        memberUnit.getPrimaryOrder() == ScrOrderIds.COMPLETING_ARCHON_SUMMON ||
                        memberUnit.getPrimaryOrder() == ScrOrderIds.RESET_COLLISION) {
                        destination = this.game.getComputerTerrain().getUnitRegion(leader);

                        break;
                    }
                }
            }
        }

        if (this.game.getComputerTerrain().getUnitRegion(unit) == destination) {
            this.game.getUnitPointMovement().stopAndFaceDestination(unit);

            return;
        }

        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();

        this.issueRegionOrder(phase,
                              unit,
                              ScrOrderIds.ATTACK_MOVE,
                              (short) terrain.getCenterX(destination), (short) terrain.getCenterY(destination),
                              instructionLimit);
    }

    /**
     * Returns whether a guard's request still waits: {@link #FIRST_REFILL_DELAY} clock ticks plus a fifteenth of the build time (with the source unit
     * of a morph) before its first refill, and {@link #REFILL_DELAY} clock ticks once it has been refilled.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private boolean isRequestPending(@NotNull ComputerUnitRecord guard) {
        int requested = guard.guardRequest;

        if (requested == 0) {
            return false;
        }

        int type = guard.guardType == ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE ? ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE : guard.guardType;
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int time = units.getBuildTimeEntry(type) & WORD_MASK;
        int source = switch (ScrUnitIds.requireValid(this.game.requireGameData().getBaseType(type))) {
            case ScrUnitIds.ZERG_GUARDIAN, ScrUnitIds.ZERG_DEVOURER -> ScrUnitIds.ZERG_MUTALISK;
            case ScrUnitIds.PROTOSS_DARK_ARCHON -> ScrUnitIds.PROTOSS_DARK_TEMPLAR;
            case ScrUnitIds.PROTOSS_ARCHON -> ScrUnitIds.PROTOSS_HIGH_TEMPLAR;
            case ScrUnitIds.ZERG_LURKER -> ScrUnitIds.ZERG_HYDRALISK;
            default -> -1;
        };

        if (source != -1) {
            time += units.getBuildTimeEntry(source) & WORD_MASK;
        }

        int threshold = guard.guardRetries == 0
                        ? requested + FIRST_REFILL_DELAY + Integer.divideUnsigned(time, REFILL_BUILD_TIME_DIVISOR)
                        : requested + REFILL_DELAY;

        return Integer.compareUnsigned(this.game.getComputerTowns().getElapsedClockTicks(), threshold) <= 0;
    }

    /**
     * Requests a guard's unit unless the owner's building-hit request delay is still running
     * ({@link ComputerPlayer#isBuildingHitRequestDelayElapsed}). Reserves the costs, inserts a guard request and requests the
     * morph sources for the guard's current region.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private void requestGuard(@NotNull ComputerPlayer player, @NotNull ComputerRegions regions, @NotNull ComputerUnitRecord guard) {
        if (!player.isBuildingHitRequestDelayElapsed(this.game.getComputerTowns().getElapsedClockTicks())) {
            return;
        }

        int type = guard.guardType == ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE ? ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE : guard.guardType;
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        player.reserve(units.getMineralCost(type), units.getGasCost(type), ComputerTowns.getSupplyCost(units, type));
        this.game.getComputerTowns().pushGuardRequest(player, type, GUARD_REQUEST_PRIORITY, guard);

        int index = regions.getIndex(player.getOwner(), this.game.getComputerTerrain().getRegion((short) guard.guardX, (short) guard.guardY));

        this.game.getComputerTowns().requestMorphSources(player, type, GUARD_REQUEST_PRIORITY, index);
    }

    /**
     * Finds the owner's nearest military unit of the type whose region is not attacking, defending or gathering and
     * keeps its required ground strength without the unit.
     *
     * @throws IllegalStateException         if a candidate unit has no sprite
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private @Nullable Unit findSurplusUnit(@NotNull ComputerPlayerPhase phase, int owner, int type, int positionX, int positionY) {
        ComputerRegions regions = phase.getRegions();
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        Unit best = null;
        int bestDistance = ComputerPlayerPhase.NO_CANDIDATE_DISTANCE;

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || this.game.requireGameData().getBaseType(unit) != type) {
                continue;
            }

            ComputerUnitRecord record = unit.getComputerRecord();

            if (record == null || record.getKind() != ComputerUnitRecord.KIND_MILITARY) {
                continue;
            }

            int index = record.regionIndex;
            int regionState = regions.getState(index);

            if (regionState == ComputerRegionStates.ATTACK ||
                regionState >= ComputerRegionStates.DEFENDING && regionState <= ComputerRegionStates.ATTACK_LAUNCHED) {
                continue;
            }

            if (regions.getRequiredGroundStrength(index) >= regions.getAssignedGroundStrength(index) - unit.getGroundStrength()) {
                continue;
            }

            Sprite sprite = unit.requireSprite();
            int distance = RegionPathSearch.calculateDistance(positionX, (short) sprite.getX(), positionY, (short) sprite.getY());

            if (Integer.compareUnsigned(distance, bestDistance) < 0) {
                best         = unit;
                bestDistance = distance;
            }
        }

        return best;
    }

    /**
     * Places an empty guard for a script ({@code place_guard}): takes a free guard record for the owner with no unit, the type,
     * and the chosen town position as both guard and home position.
     *
     * @param owner    the script owner
     * @param town     the script town
     * @param type     the guarded unit type
     * @param position {@code 0} for the town position, {@code 1} for the mineral target, otherwise the first active or depleted refinery
     * @throws IllegalStateException if the mineral target or refinery has no sprite
     */
    void placeGuard(int owner, @NotNull ComputerTown town, int type, int position) {
        int positionX;
        int positionY;

        if (position == GUARD_POSITION_TOWN) {
            positionX = (short) town.positionX;
            positionY = (short) town.positionY;
        } else {
            Unit anchor;

            if (position == GUARD_POSITION_MINERALS) {
                anchor = town.mineralTarget;
            } else {
                anchor = this.game.getComputerOrders().findGasBuilding(town, ComputerOrders.GAS_ACTIVE, 1);

                if (anchor == null) {
                    anchor = this.game.getComputerOrders().findGasBuilding(town, ComputerOrders.GAS_DEPLETED, 1);
                }
            }

            if (anchor == null) {
                return;
            }

            Sprite sprite = anchor.requireSprite();

            positionX = (short) sprite.getX();
            positionY = (short) sprite.getY();
        }

        this.addEmptyGuard(owner, type, positionX, positionY);
    }

    /**
     * Takes a free guard record for the owner with no unit, the type and the position as both guard and home position.
     *
     * @param owner     the owner
     * @param type      the guarded unit type
     * @param positionX the horizontal guard position
     * @param positionY the vertical guard position
     */
    void addEmptyGuard(int owner, int type, int positionX, int positionY) {
        if (owner >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return;
        }

        ComputerUnitRecord guard = this.firstFreeGuard;

        if (guard == null) {
            return;
        }

        this.firstFreeGuard = guard.next;

        if (guard.next != null) {
            guard.next.previous = null;
        }

        guard.previous = null;
        guard.next     = this.firstGuards[owner];

        if (guard.next != null) {
            guard.next.previous = guard;
        }

        this.firstGuards[owner] = guard;

        guard.guardRetries = 0;
        guard.unit         = null;
        guard.guardType    = type;
        guard.guardX       = positionX;
        guard.guardY       = positionY;
        guard.homeX        = positionX;
        guard.homeY        = positionY;
    }

    /**
     * Links the unit to a region's military list and orders it there.
     *
     * @param phase            the owning phase
     * @param index            the region record index
     * @param unit             the unit
     * @param keepState        {@code false} redirects a region that wants a defender to the unit's region
     * @param instructionLimit the iscript budget for issued orders
     */
    void addToRegion(@NotNull ComputerPlayerPhase phase, int index, @NotNull Unit unit, boolean keepState, int instructionLimit) {
        ComputerRegions regions = phase.getRegions();

        if (regions.getState(index) == ComputerRegionStates.DEFENDER_WANTED && !keepState) {
            index = regions.getIndex(unit.getOwnerIndex(), this.game.getComputerTerrain().getUnitRegion(unit));
        }

        this.link(phase, index, unit, instructionLimit);
    }

    /**
     * Links a unit to a region's list through a free military record: the unit moves to the region center
     * ({@link ScrOrderIds#ATK_MOVE_EP}, {@link ScrOrderIds#HEAL_MOVE} for a Medic) and an attacking region refreshes its leader.
     *
     * @param phase            the owning phase
     * @param index            the region record index
     * @param unit             the unit
     * @param instructionLimit the iscript budget for issued orders
     */
    void link(@NotNull ComputerPlayerPhase phase, int index, @NotNull Unit unit, int instructionLimit) {
        ComputerUnitRecord record = this.firstFreeMilitary;

        if (record == null) {
            return;
        }

        this.firstFreeMilitary = record.next;

        if (record.next != null) {
            record.next.previous = null;
        }

        ComputerRegions regions = phase.getRegions();

        regions.linkMilitary(index, record, (unit.getStatus() & UnitStatusFlags.FLYING) == 0);

        record.unit = unit;

        unit.setComputerRecord(record);

        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        int region = regions.getRegion(index);
        int order = this.game.requireGameData().getBaseType(unit) == ScrUnitIds.TERRAN_MEDIC
                    ? ScrOrderIds.HEAL_MOVE
                    : ScrOrderIds.ATK_MOVE_EP;

        this.issueRegionOrder(phase, unit, order, (short) terrain.getCenterX(region), (short) terrain.getCenterY(region), instructionLimit);

        if (ComputerRegionStates.isAttackState(regions.getState(index))) {
            regions.updateLeader(phase, index);
        }
    }

    /**
     * Unlinks a military record from its region and returns it to the free-list head.
     *
     * @param phase  the owning phase
     * @param record the military record
     */
    void unlink(@NotNull ComputerPlayerPhase phase, @NotNull ComputerUnitRecord record) {
        Unit unit = record.unit;

        phase.getRegions().unlinkMilitary(record.regionIndex, record, unit == null || (unit.getStatus() & UnitStatusFlags.FLYING) == 0);

        record.previous = null;
        record.next     = this.firstFreeMilitary;

        if (this.firstFreeMilitary != null) {
            this.firstFreeMilitary.previous = record;
        }

        this.firstFreeMilitary = record;
    }

    /**
     * Orders the owner's units in the rectangle into their nearest own Bunker (aiscript {@code enter_bunker}): a unit
     * enters when the owner has a completed Bunker and the nearest one can load it.
     *
     * @param phase  the owning phase
     * @param owner  the script owner
     * @param left   the left rectangle bound
     * @param top    the upper rectangle bound
     * @param right  the right rectangle bound
     * @param bottom the lower rectangle bound
     * @throws IllegalStateException if the owner has a unit in the rectangle and the unit accounting is missing, or the chosen Bunker has no sprite
     */
    void enterBunkers(@NotNull ComputerPlayerPhase phase, int owner, int left, int top, int right, int bottom) {
        for (Unit unit : this.game.getComputerTerrain().collectRectangle(left, top, right, bottom)) {
            if (unit.getOwnerIndex() != owner) {
                continue;
            }

            UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();

            if (accounting == null) {
                throw Exceptions.newIllegalStateException("Computer Bunker entry requires unit accounting.");
            }

            if (this.game.getComputerUnits().getCompletedTypeCount(ScrUnitIds.TERRAN_BUNKER, owner) == 0) {
                continue;
            }

            Unit bunker = this.game.getComputerResources().findOnMap(
                    unit,
                    candidate -> this.game.requireGameData().getBaseType(candidate) == ScrUnitIds.TERRAN_BUNKER &&
                                 candidate.getOwnerIndex() == unit.getOwnerIndex()
            );

            if (bunker == null || !this.game.getUnitTransport().canLoad(bunker, unit)) {
                continue;
            }

            Sprite sprite = bunker.requireSprite();

            this.game.getOrders().issue(unit,
                                        ScrOrderIds.ENTER_TRANSPORT,
                                        bunker,
                                        sprite.getX(), sprite.getY(),
                                        ScrUnitIds.NONE,
                                        false,
                                        phase.getInstructionLimit());
        }
    }

    /**
     * Acquires an attack target for the unit without critters and without restriction.
     *
     * @param unit the unit
     * @return {@code true} if an attack target was chosen, {@code false} otherwise
     * @throws UnsupportedOperationException if the computer-player phase is not initialized
     */
    boolean acquireTarget(@NotNull Unit unit) {
        return this.game.getComputerCombat().acquireTarget(unit, 0, true, false, this.game.requireComputerPlayerPhase().getInstructionLimit());
    }

    /**
     * Moves the nearest suitable military or guard unit of each listed type into the region.
     *
     * @param phase   the owning phase
     * @param entries the use list, each entry a type plus one
     * @param index   the defended region record index
     * @param air     whether the air value table sums the moved types
     * @return the summed value of the moved units
     * @throws UnsupportedOperationException if the list has no terminating zero entry
     */
    int useDefense(@NotNull ComputerPlayerPhase phase, short @NotNull [] entries, int index, boolean air) {
        ComputerRegions regions = phase.getRegions();
        int total = 0;

        for (int entry = 0; ; entry++) {
            if (entry == entries.length) {
                throw Exceptions.newUnsupportedOperationException("A computer defense list of {0} entries has no zero terminator.", entries.length);
            }

            if (entries[entry] == 0) {
                break;
            }

            int type = Short.toUnsignedInt(entries[entry]) - 1;

            if (this.useUnit(phase, index, type, regions.getStateTimer(index))) {
                total += air ? phase.getAirValue(type) : phase.getGroundValue(type);
            }
        }

        return total;
    }

    /**
     * Moves the nearest spare unit of the type (else a guard or a lower-priority region's unit) into the region and
     * refreshes the strength of the region it left.
     *
     * @param phase    the owning phase
     * @param index    the region record index
     * @param type     the unit type
     * @param priority the state timer bound of the regions a unit may be taken from
     * @return {@code true} if a unit was moved, {@code false} otherwise
     * @throws IllegalStateException if a candidate has no sprite or the chosen record holds no unit
     */
    boolean useUnit(@NotNull ComputerPlayerPhase phase, int index, int type, int priority) {
        ComputerRegions regions = phase.getRegions();
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        int owner = regions.getOwner(index);
        int region = regions.getRegion(index);
        int linked = regions.getIndex(owner, regions.getLinkedRegion(index));
        int centerX = (short) terrain.getCenterX(region);
        int centerY = (short) terrain.getCenterY(region);
        ComputerUnitRecord found = this.findReserve(phase, index, type, centerX, centerY, linked);

        if (found == null) {
            found = this.findAvailable(phase, index, priority, type, centerX, centerY, linked);
        }

        if (found == null) {
            return false;
        }

        int source;

        if (found.getKind() == ComputerUnitRecord.KIND_GUARD) {
            Unit unit = requireRecordUnit(found);

            unit.setComputerRecord(null);
            this.addToRegion(phase, index, unit, true, phase.getInstructionLimit());

            found.unit = null;
            source     = regions.getIndex(owner, this.game.getComputerTerrain().getUnitRegion(unit));
        } else if (found.getKind() == ComputerUnitRecord.KIND_MILITARY) {
            source = found.regionIndex;

            this.unlink(phase, found);

            Unit unit = requireRecordUnit(found);

            unit.setComputerRecord(null);
            this.addToRegion(phase, index, unit, true, phase.getInstructionLimit());

            if ((unit.getStatus() & UnitStatusFlags.IN_BUNKER) != 0) {
                this.game.getUnitTransport().unload(unit.getOwnerIndex(), unit, phase.getInstructionLimit());
            }
        } else {
            return false;
        }

        regions.refreshStrength(phase, source);

        return true;
    }

    /**
     * Finds the nearest unit of the type whose region can spare its strength.
     *
     * @throws IllegalStateException         if a candidate unit has no sprite
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private @Nullable ComputerUnitRecord findReserve(@NotNull ComputerPlayerPhase phase, int index, int type, int centerX, int centerY, int linked) {
        if (type == ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE) {
            return null;
        }

        ComputerRegions regions = phase.getRegions();
        int owner = regions.getOwner(index);
        int region = regions.getRegion(index);
        int regionState = regions.getState(index);
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        ComputerUnitRecord best = null;
        int bestDistance = ComputerPlayerPhase.NO_CANDIDATE_DISTANCE;

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (!this.isDefenseCandidate(unit, type, true)) {
                continue;
            }

            ComputerUnitRecord record = unit.getComputerRecord();

            if (record == null || record.getKind() != ComputerUnitRecord.KIND_MILITARY) {
                continue;
            }

            int source = record.regionIndex;
            int sourceState = regions.getState(source);

            if (regions.getRegion(source) == region ||
                sourceState >= ComputerRegionStates.DEFENDING && sourceState <= ComputerRegionStates.ATTACK_LAUNCHED) {
                continue;
            }

            if (regionState >= ComputerRegionStates.DEFENDING && regionState <= ComputerRegionStates.ATTACK_LAUNCHED &&
                regions.getRegion(source) == regions.getRegion(linked)) {
                continue;
            }

            int air = unit.getAirStrength();

            if (air != 0 && !(regions.getRequiredAirStrength(source) <= regions.getAssignedAirStrength(source) - air)) {
                continue;
            }

            int ground = unit.getGroundStrength();

            if (ground != 0 && !(regions.getRequiredGroundStrength(source) < regions.getAssignedGroundStrength(source) - ground)) {
                continue;
            }

            Sprite sprite = unit.requireSprite();
            int distance = RegionPathSearch.calculateDistance(centerX, sprite.getX(), centerY, sprite.getY());

            if (Integer.compareUnsigned(distance, bestDistance) < 0) {
                best         = record;
                bestDistance = distance;
            }
        }

        return best;
    }

    /**
     * Finds the nearest unit of the type held by a guard or by a lower-priority region.
     *
     * @throws IllegalStateException         if a candidate unit has no sprite
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private @Nullable ComputerUnitRecord findAvailable(@NotNull ComputerPlayerPhase phase,
                                                       int index,
                                                       int priority,
                                                       int type,
                                                       int centerX, int centerY,
                                                       int linked) {
        ComputerRegions regions = phase.getRegions();
        int owner = regions.getOwner(index);
        int region = regions.getRegion(index);
        int regionState = regions.getState(index);
        boolean campaign = phase.getPlayer(owner).isCampaign();
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        ComputerUnitRecord best = null;
        int bestDistance = ComputerPlayerPhase.NO_CANDIDATE_DISTANCE;

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (!this.isDefenseCandidate(unit, type, false)) {
                continue;
            }

            Sprite sprite = unit.requireSprite();
            int distance = RegionPathSearch.calculateDistance(centerX, sprite.getX(), centerY, sprite.getY());
            ComputerUnitRecord record = unit.getComputerRecord();

            if (Integer.compareUnsigned(distance, bestDistance) >= 0 || record == null) {
                continue;
            }

            if (record.getKind() == ComputerUnitRecord.KIND_GUARD) {
                if (!campaign
                    ? regionState == ComputerRegionStates.ATTACK_GATHERING || regionState == ComputerRegionStates.ATTACK_LAUNCHED
                    : regionState != ComputerRegionStates.DEFENDING) {
                    best         = record;
                    bestDistance = distance;
                }
            } else if (record.getKind() == ComputerUnitRecord.KIND_MILITARY) {
                int source = record.regionIndex;
                int sourceState = regions.getState(source);

                if (regions.getRegion(source) == region) {
                    continue;
                }

                if ((sourceState == ComputerRegionStates.ATTACK || sourceState == ComputerRegionStates.ATTACK_ENGAGED) && !campaign) {
                    continue;
                }

                if ((unit.getStatus() & UnitStatusFlags.IN_BUNKER) != 0 &&
                    (priority == ComputerTowns.LOADED_BUNKER_PRIORITY ||
                     !phase.getPlayer(unit.getOwnerIndex()).isCampaign() && regionState != ComputerRegionStates.ATTACK_GATHERING &&
                     regionState != ComputerRegionStates.ATTACK_LAUNCHED)) {
                    continue;
                }

                if (regionState >= ComputerRegionStates.DEFENDING && regionState <= ComputerRegionStates.ATTACK_LAUNCHED &&
                    regions.getRegion(source) == regions.getRegion(linked)) {
                    continue;
                }

                if (regions.getStateTimer(source) < priority) {
                    best         = record;
                    bestDistance = distance;
                }
            }
        }

        return best;
    }

    /**
     * Returns whether a unit of the type is a healthy, idle defense candidate.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private boolean isDefenseCandidate(@NotNull Unit unit, int type, boolean excludeBunkered) {
        if (ComputerUnits.isDeadOrHidden(unit) || this.game.getComputerUnits().isSubunit(unit) || unit.getComputerRecord() == null) {
            return false;
        }

        int unitType = this.game.requireGameData().getBaseType(unit);

        if (unitType != type && !(type == ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE &&
                                  unitType == ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE)) {
            return false;
        }

        if (ComputerUnits.isUnavailableForTown(unit) || this.game.getComputerCombat().isEngaged(unit, null)) {
            return false;
        }

        if (excludeBunkered && (unit.getStatus() & UnitStatusFlags.IN_BUNKER) != 0) {
            return false;
        }

        if (unitType == ScrUnitIds.TERRAN_MARINE) {
            return unit.getPrimaryOrder() != ScrOrderIds.ENTER_TRANSPORT;
        }

        int offset = unitType - ScrUnitIds.TERRAN_FIREBAT;

        if (offset < 0 || offset > ANY_LIFE_DEFENSE_LAST_OFFSET || (ANY_LIFE_DEFENSE_TYPES >>> offset & 1) == 0) {
            ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

            return UnitQueries.getLife(units, unit) >= UnitQueries.getMaximumLife(units, unit) / DEFENSE_LIFE_DIVISOR;
        }

        return true;
    }

    /**
     * Sets already pending requests of empty guards of the type to one.
     *
     * @param owner the owner
     * @param type  the requested unit type
     */
    void markGuardRequested(int owner, int type) {
        for (ComputerUnitRecord guard = this.firstGuards[owner]; guard != null; guard = guard.next) {
            if (guard.unit != null || guard.guardRequest == 0) {
                continue;
            }

            int guardType = guard.guardType == ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE ? ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE : guard.guardType;

            if (guardType == (type & WORD_MASK)) {
                guard.guardRequest = 1;
            }
        }
    }

    /**
     * Writes the military pool: clears the free records ({@link ComputerUnitRecord#clearFreeMilitary}), then writes the
     * {@code 1000} records ({@link ComputerUnitRecord#writeSaveMilitary}) and the one-based free head DWORD after them.
     *
     * @param data       the section, little-endian
     * @param regions    the region records the records reference
     * @param metadata   the saved-format payloads of the typed pools
     * @param references the unit references of the saved game
     */
    void writeSaveMilitaryRecords(@NotNull ByteBuffer data,
                                  @NotNull ComputerRegions regions,
                                  @NotNull ComputerSaveMetadata metadata,
                                  @NotNull SaveReferences references) {
        boolean[] free = new boolean[POOL_SIZE];

        for (ComputerUnitRecord record = this.firstFreeMilitary; record != null; record = record.next) {
            record.clearFreeMilitary();
            metadata.clearFreeMilitary(record.getPoolIndex());

            free[record.getPoolIndex()] = true;
        }

        ComputerPlayerPhase.clearSaveRange(data, SAVED_MILITARY_RECORDS_SIZE);

        for (int index = 0; index < POOL_SIZE; index++) {
            this.militaryRecords[index].writeSaveMilitary(data, index * ComputerUnitRecord.SAVED_MILITARY_SIZE, free[index], regions, references);
        }

        data.putInt(POOL_SIZE * ComputerUnitRecord.SAVED_MILITARY_SIZE, ComputerUnitRecord.encodeSaveLink(this.firstFreeMilitary));

        metadata.writeMilitary(data);
    }

    /**
     * Reads the military pool that {@link #writeSaveMilitaryRecords} wrote.
     *
     * @param data       the section, little-endian
     * @param regions    the region records the records reference
     * @param metadata   the saved-format payloads of the typed pools
     * @param references the unit references of the restored game
     */
    void readSaveMilitaryRecords(@NotNull ByteBuffer data,
                                 @NotNull ComputerRegions regions,
                                 @NotNull ComputerSaveMetadata metadata,
                                 @NotNull SaveReferences references) {
        for (int index = 0; index < POOL_SIZE; index++) {
            this.militaryRecords[index].readSaveMilitary(data, index * ComputerUnitRecord.SAVED_MILITARY_SIZE,
                                                         this.militaryRecords,
                                                         regions,
                                                         references);
            metadata.readMilitary(data, index * ComputerUnitRecord.SAVED_MILITARY_SIZE, index);
        }

        this.firstFreeMilitary = ComputerUnitRecord.decodeSaveLink(this.militaryRecords,
                                                                   data.getInt(POOL_SIZE * ComputerUnitRecord.SAVED_MILITARY_SIZE));
    }

    /**
     * Writes the guard pool: clears the free records ({@link ComputerUnitRecord#clearFreeGuard}), writes the {@code 1000} records
     * ({@link ComputerUnitRecord#writeSaveGuard}) and the one-based free head DWORD into the record section, and the
     * owners' one-based first guards into the head section, with a zero base DWORD for each list view.
     *
     * @param records    the record section, little-endian
     * @param heads      the list head section, little-endian
     * @param metadata   the saved-format payloads of the typed pools
     * @param references the unit references of the saved game
     */
    void writeSaveGuardRecords(@NotNull ByteBuffer records, @NotNull ByteBuffer heads,
                               @NotNull ComputerSaveMetadata metadata,
                               @NotNull SaveReferences references) {
        boolean[] free = new boolean[POOL_SIZE];

        for (ComputerUnitRecord record = this.firstFreeGuard; record != null; record = record.next) {
            record.clearFreeGuard();

            free[record.getPoolIndex()] = true;
        }

        ComputerPlayerPhase.clearSaveRange(records, SAVED_GUARD_RECORDS_SIZE);

        for (int index = 0; index < POOL_SIZE; index++) {
            this.guardRecords[index].writeSaveGuard(records, index * ComputerUnitRecord.SAVED_GUARD_SIZE, free[index], references);
        }

        records.putInt(POOL_SIZE * ComputerUnitRecord.SAVED_GUARD_SIZE, ComputerUnitRecord.encodeSaveLink(this.firstFreeGuard));

        metadata.writeGuards(records);

        ComputerPlayerPhase.clearSaveRange(heads, SAVED_LIST_VIEWS_SIZE);

        for (int owner = 0; owner < this.firstGuards.length; owner++) {
            heads.putInt(owner * SAVED_LIST_VIEW_SIZE + Integer.BYTES, ComputerUnitRecord.encodeSaveLink(this.firstGuards[owner]));
        }
    }

    /**
     * Reads the guard pool and the guard lists that {@link #writeSaveGuardRecords} wrote.
     *
     * @param records    the record section, little-endian
     * @param heads      the list head section, little-endian
     * @param metadata   the saved-format payloads of the typed pools
     * @param references the unit references of the restored game
     */
    void readSaveGuardRecords(@NotNull ByteBuffer records, @NotNull ByteBuffer heads,
                              @NotNull ComputerSaveMetadata metadata,
                              @NotNull SaveReferences references) {
        for (int index = 0; index < POOL_SIZE; index++) {
            this.guardRecords[index].readSaveGuard(records, index * ComputerUnitRecord.SAVED_GUARD_SIZE, this.guardRecords, references);
            metadata.readGuard(records, index * ComputerUnitRecord.SAVED_GUARD_SIZE, index);
        }

        this.firstFreeGuard = ComputerUnitRecord.decodeSaveLink(this.guardRecords, records.getInt(POOL_SIZE * ComputerUnitRecord.SAVED_GUARD_SIZE));

        for (int owner = 0; owner < this.firstGuards.length; owner++) {
            this.firstGuards[owner] = ComputerUnitRecord.decodeSaveLink(this.guardRecords,
                                                                        heads.getInt(owner * SAVED_LIST_VIEW_SIZE + Integer.BYTES));
        }
    }

    /**
     * Mixes the pools' state into a state hash.
     *
     * @param hash the hash so far
     * @return the hash with the pools' state
     */
    int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + (this.firstFreeMilitary == null ? -1 : this.firstFreeMilitary.getPoolIndex());
        hash = HASH_MULTIPLIER * hash + (this.firstFreeGuard == null ? -1 : this.firstFreeGuard.getPoolIndex());

        for (int owner = 0; owner < PlayerTable.PLAYABLE_PLAYER_SLOTS; owner++) {
            hash = HASH_MULTIPLIER * hash + (this.firstGuards[owner] == null ? -1 : this.firstGuards[owner].getPoolIndex());
            hash = HASH_MULTIPLIER * hash + this.safetyClocks[owner];
            hash = HASH_MULTIPLIER * hash + this.safetySources[owner];
        }

        for (ComputerUnitRecord record : this.militaryRecords) {
            hash = (int) record.computeStateHash(hash);
        }

        for (ComputerUnitRecord record : this.guardRecords) {
            hash = (int) record.computeStateHash(hash);
        }

        return hash;
    }
}
