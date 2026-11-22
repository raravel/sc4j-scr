package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUpgradeIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.combat.UnitTargeting;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.order.OrderPool;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerUpgrades;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegions;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Executes the computer combat orders AtkMoveEP and GuardPost and the computer target acquisition.
 * <p>
 * The target tests use the common combat model: the attack test is {@link UnitTargeting#canAttackTarget}, the weapon range
 * test {@link UnitTargeting#isInWeaponMovementRange}, the maximum range test {@link UnitTargeting#isOutOfMaximumRange} and
 * the distance {@link UnitTargeting#getDistance} from the main unit.
 */
public final class ComputerCombat {
    /**
     * The attack priorities of the acquisition search.
     */
    private static final int PRIORITY_COUNT = 6;
    /**
     * The candidates the acquisition search keeps per priority.
     */
    private static final int PRIORITY_CAPACITY = 16;
    /**
     * The margin added around the acquisition range of the candidate query.
     */
    private static final int ACQUISITION_QUERY_MARGIN = 64;
    /**
     * The acquisition range bonus, in tiles, of a unit inside a Bunker.
     */
    private static final int BUNKER_RANGE_BONUS = 2;
    /**
     * A harass move in the order position's region ends when the unit is farther than this from the position.
     */
    private static final int HARASS_END_DISTANCE = 0x60;
    /**
     * The radius of the enemy search that keeps a sieged Siege Tank in siege.
     */
    private static final int SIEGE_THREAT_RADIUS = 0x200;
    /**
     * The life an order target needs before a Marine or Firebat uses a stim pack against it.
     */
    private static final int STIM_TARGET_MINIMUM_LIFE = 100;
    /**
     * The half-health threshold divisor for using a stim pack.
     */
    private static final int STIM_HEALTH_DIVISOR = 2;
    /**
     * The tile count of the leader's region below which a unit does not follow its leader.
     */
    private static final int LEADER_REGION_MINIMUM_TILES = 0x3C;
    /**
     * A unit follows its leader only when the leader is farther than this.
     */
    private static final int LEADER_FOLLOW_DISTANCE = 0x80;
    /**
     * A unit follows its leader only when it is farther than this from its region center.
     */
    private static final int REGION_CENTER_FOLLOW_DISTANCE = 0x180;
    /**
     * The distance within which the leader considers the members of its region.
     */
    private static final int LEADER_WAIT_RADIUS = 0x180;
    /**
     * The leader waits for a following member farther than this.
     */
    private static final int LEADER_WAIT_DISTANCE = 0x100;
    /**
     * A Reaver on a safe route asks for a transport from this distance on.
     */
    private static final int REAVER_PICKUP_DISTANCE = 0x3C0;
    /**
     * A transport candidate needs three quarters of its hit points unless its shields are full.
     */
    private static final int TRANSPORT_HEALTH_NUMERATOR = 3;
    private static final int TRANSPORT_HEALTH_DENOMINATOR = 4;
    /**
     * The fraction bits removed from a transport's stored shield points.
     */
    private static final int SHIELD_POINT_FRACTION_BITS = 8;

    /**
     * Returns the Carrier of an Interceptor target, which stands for its Carrier, or the target itself.
     */
    private static @Nullable Unit resolveCarrier(@NotNull Game game, @Nullable Unit target) {
        if (target != null && game.requireGameData().getBaseType(target) == ScrUnitIds.PROTOSS_INTERCEPTOR) {
            return target.getHangarParent();
        }

        return target;
    }

    private static int getCenterDistance(@NotNull Unit unit, @NotNull Unit target) {
        Sprite sprite = unit.requireSprite();
        Sprite targetSprite = target.requireSprite();

        return RegionPathSearch.calculateDistance(sprite.getX(), targetSprite.getX(), sprite.getY(), targetSprite.getY());
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link ComputerCombat} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public ComputerCombat(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Searches the units around a sieged Siege Tank for another unit that is an enemy of the tank, detected by it, that
     * the tank can attack and that is in its weapon range.
     *
     * @param tank the sieged Siege Tank
     * @return {@code true} if such a unit exists, {@code false} otherwise
     * @throws IllegalStateException         if the tank has no sprite
     * @throws UnsupportedOperationException if the computer-player phase is not initialized
     */
    boolean isSiegeThreatened(@NotNull Unit tank) {
        this.game.requireComputerPlayerPhase();

        Sprite sprite = tank.requireSprite();
        int centerX = (short) sprite.getX();
        int centerY = (short) sprite.getY();
        ComputerTerrain query = this.game.getComputerTerrain();
        Unit[] candidates = query.collectRectangle(centerX - SIEGE_THREAT_RADIUS, centerY - SIEGE_THREAT_RADIUS,
                                                   centerX + SIEGE_THREAT_RADIUS, centerY + SIEGE_THREAT_RADIUS);

        for (Unit candidate : candidates) {
            if (candidate == tank || !this.game.getComputerUnits().isEnemy(tank.getOwnerIndex(), candidate) ||
                UnitTargeting.isUndetected(tank, candidate)) {
                continue;
            }

            if (this.game.getUnitTargeting().canAttackTarget(tank, candidate) &&
                this.game.getUnitTargeting().isInWeaponMovementRange(tank, candidate)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns whether a computer ground unit skips an out-of-range target that another computer unit already claimed.
     *
     * @param unit   the attacking unit
     * @param target the target
     * @return {@code true} if the target is claimed and skipped, {@code false} otherwise
     */
    public boolean isClaimedElsewhere(@NotNull Unit unit, @NotNull Unit target) {
        Player owner = this.game.getPlayerTable().getPlayer(unit.getOwnerIndex());

        if (owner == null || owner.getType() != PlayerTypes.COMPUTER || (unit.getStatus() & UnitStatusFlags.FLYING) != 0) {
            return false;
        }

        if (this.game.getUnitTargeting().isInWeaponMovementRange(unit, target)) {
            return false;
        }

        return (target.getStatus() & UnitStatusFlags.COMPUTER_TARGET_CLAIMED) != 0;
    }

    /**
     * @param unit   the unit
     * @param target the enemy target, or {@code null} for the unit's order target
     * @return {@code true} if the enemy target can attack the unit from where it is, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    boolean isEngaged(@NotNull Unit unit, @Nullable Unit target) {
        if (target == null) {
            target = unit.getOrderTargetUnit();
        }

        if (target == null || !this.game.getComputerUnits().isEnemy(unit.getOwnerIndex(), target)) {
            return false;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if ((target.getStatus() & UnitStatusFlags.HALLUCINATION) != 0 &&
            UnitQueries.getLife(units, target) != UnitQueries.getMaximumLife(units, target)) {
            return false;
        }

        if (this.game.getUnitTargeting().canAttackTarget(target, unit)) {
            return !this.game.getUnitTargeting().isOutOfMaximumRange(target, unit);
        }

        return this.isLoadedBunker(target);
    }

    private boolean isLoadedBunker(@NotNull Unit unit) {
        if (this.game.requireGameData().getBaseType(unit) != ScrUnitIds.TERRAN_BUNKER) {
            return false;
        }

        for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS; slot++) {
            if (this.game.getUnitTransport().getLoadedUnit(unit, slot) != null) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns whether a ground unit restricted to safe routes cannot reach the target safely.
     *
     * @throws IllegalStateException if the target has no sprite
     */
    private boolean isExcluded(@NotNull Unit unit, @NotNull Unit target) {
        Sprite sprite = target.requireSprite();

        return !this.game.getComputerMilitary().canReachSafely(unit, sprite.getX(), sprite.getY());
    }

    /**
     * Tests the safe-route exclusion for the range test of the attack order.
     *
     * @param unit   the attacking unit
     * @param target the order target
     * @return {@code true} if the unit cannot reach the target along safe regions, {@code false} otherwise
     * @throws UnsupportedOperationException if the computer-player phase is not initialized
     */
    public boolean isChaseExcluded(@NotNull Unit unit, @NotNull Unit target) {
        this.game.requireComputerPlayerPhase();

        return this.isExcluded(unit, target);
    }

    /**
     * Returns whether the unit is not a military unit resting in its region while the region is defending.
     *
     * @throws UnsupportedOperationException if the computer-player phase is not initialized
     */
    private boolean isOutsideRegion(@NotNull Unit unit) {
        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null || record.getKind() != ComputerUnitRecord.KIND_MILITARY) {
            return true;
        }

        ComputerRegions regions = this.game.requireComputerPlayerPhase().getRegions();

        if (this.game.getComputerTerrain().getUnitRegion(unit) != regions.getRegion(record.regionIndex)) {
            return true;
        }

        return regions.getState(record.regionIndex) != ComputerRegionStates.DEFENDING;
    }

    /**
     * Returns {@code true} unless the unit is a region member that should follow its leader: the leader is not in a
     * transport, not the unit, the region is not engaged and the leader passes {@link #isFollowing}.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private boolean isNearOwnRegion(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit) {
        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null || record.getKind() != ComputerUnitRecord.KIND_MILITARY || record.unit != unit ||
            UnitQueries.isBuilding(this.game.requireGameData().getUnitTypes(), unit.getUnitId())) {
            return true;
        }

        ComputerRegions regions = phase.getRegions();
        int index = record.regionIndex;
        Unit leader = regions.getLeader(index);

        if (leader == null || (leader.getStatus() & UnitStatusFlags.LOADED) != 0 || leader == unit ||
            regions.getState(index) == ComputerRegionStates.ATTACK_ENGAGED) {
            return true;
        }

        return !this.isFollowing(phase, unit, leader, index);
    }

    /**
     * Returns whether a unit whose leader passed the first tests follows it: the leader region is large enough, the route
     * to it is safe, the leader is far enough away and the unit far enough from its region center.
     *
     * @throws IllegalStateException if the unit or the leader has no sprite
     */
    private boolean isFollowing(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit, @NotNull Unit leader, int index) {
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        int leaderRegion = this.game.getComputerTerrain().getUnitRegion(leader);

        if (terrain.getTileCount(leaderRegion) < LEADER_REGION_MINIMUM_TILES) {
            return false;
        }

        if (!phase.getMilitary().isRouteSafe(phase, unit, leader)) {
            return false;
        }

        Sprite unitSprite = unit.requireSprite();
        Sprite leaderSprite = leader.requireSprite();

        if (RegionPathSearch.calculateDistance((short) leaderSprite.getX(), (short) unitSprite.getX(),
                                               (short) leaderSprite.getY(), (short) unitSprite.getY()) <= LEADER_FOLLOW_DISTANCE) {
            return false;
        }

        int region = phase.getRegions().getRegion(index);
        int centerX = terrain.getCenterX(region);
        int centerY = terrain.getCenterY(region);

        return RegionPathSearch.calculateDistance((short) unitSprite.getX(), centerX,
                                                  (short) unitSprite.getY(), centerY) > REGION_CENTER_FOLLOW_DISTANCE;
    }

    /**
     * Returns whether the leader waits: a member of its region (not engaged) close to it, reachable on a safe route,
     * follows its leader (not {@link #isNearOwnRegion}) and is not too close.
     *
     * @throws IllegalStateException if the leader or a member of its region has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    private boolean isLeaderWaiting(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit) {
        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null || record.getKind() != ComputerUnitRecord.KIND_MILITARY || record.unit != unit ||
            UnitQueries.isBuilding(this.game.requireGameData().getUnitTypes(), unit.getUnitId())) {
            return false;
        }

        ComputerRegions regions = phase.getRegions();

        if (regions.getState(record.regionIndex) == ComputerRegionStates.ATTACK_ENGAGED) {
            return false;
        }

        Sprite sprite = unit.requireSprite();

        for (ComputerUnitRecord member = regions.getFirstMilitaryRecord(record.regionIndex); member != null; member = member.next) {
            Unit other = member.requireUnit();

            if (other == unit) {
                continue;
            }

            Sprite otherSprite = other.requireSprite();
            int distance = RegionPathSearch.calculateDistance((short) sprite.getX(), (short) otherSprite.getX(),
                                                              (short) sprite.getY(), (short) otherSprite.getY());

            if (Integer.compareUnsigned(distance, LEADER_WAIT_RADIUS) > 0) {
                continue;
            }

            if (!phase.getMilitary().isRouteSafe(phase, unit, other) || this.isNearOwnRegion(phase, other)) {
                continue;
            }

            if (Integer.compareUnsigned(distance, LEADER_WAIT_DISTANCE) > 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Runs the AtkMoveEP order: moves to the order position and attacks targets found on the way.
     *
     * @param unit             the unit
     * @param instructionLimit the iscript budget for issued orders
     * @throws IllegalStateException if the unit has no sprite
     */
    public void updateAttackMove(@NotNull Unit unit, int instructionLimit) {
        if (!this.moveAndAcquire(unit, instructionLimit)) {
            return;
        }

        if (!ComputerOrders.consumeTimer(unit)) {
            return;
        }

        int targetX = (short) unit.getOrderTargetX();
        int targetY = (short) unit.getOrderTargetY();

        if (this.game.getComputerTerrain().getRegion(targetX, targetY) == this.game.getComputerTerrain().getUnitRegion(unit)) {
            this.game.getUnitPointMovement().stopAndFaceDestination(unit);
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        if (ComputerOrders.getArrival(unit) != ComputerOrders.ARRIVAL_AWAY) {
            Sprite sprite = unit.requireSprite();

            if (targetX != (short) sprite.getX() || targetY != (short) sprite.getY()) {
                if (!this.moveTo(unit, targetX, targetY)) {
                    if (this.game.getComputerMilitary().canReachSafely(unit, targetX, targetY)) {
                        return;
                    }

                    this.game.getUnitPointMovement().stopAndFaceDestination(unit);

                    return;
                }
            }
        }

        this.acquire(unit, instructionLimit);
    }

    /**
     * Runs the HarassMove order: a region unit moves with its region, waits while another unit of the region waits for a
     * transport and re-issues the region move when stopped outside its region. In the destination region it ends at the
     * exact order position or when farther than {@link #HARASS_END_DISTANCE} from it.
     *
     * @param unit             the unit
     * @param instructionLimit the iscript budget for issued orders
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if the unit has no sprite
     * @throws UnsupportedOperationException if the computer-player phase is not initialized
     */
    public void updateHarassMove(@NotNull Unit unit, int instructionLimit) {
        if (!this.moveAndAcquire(unit, instructionLimit)) {
            return;
        }

        if (!ComputerOrders.consumeTimer(unit)) {
            return;
        }

        ComputerPlayerPhase phase = this.game.requireComputerPlayerPhase();
        ComputerRegions regions = phase.getRegions();
        ComputerUnitRecord record = unit.getComputerRecord();
        boolean member = record != null && record.getKind() == ComputerUnitRecord.KIND_MILITARY && record.unit == unit &&
                         !UnitQueries.isBuilding(this.game.requireGameData().getUnitTypes(), unit.getUnitId());
        // Only campaign players set a region leader.
        Unit leader = member ? regions.getLeader(record.regionIndex) : null;
        boolean wait;

        if (leader == unit) {
            wait = this.isLeaderWaiting(phase, unit);
        } else if (leader != null && !this.isNearOwnRegion(phase, unit)) {
            this.game.getUnitTargetMovement().moveToTarget(unit, leader);

            return;
        } else {
            // Another region unit waiting for a transport outside of one makes the unit wait.
            wait = false;

            if (member && regions.getState(record.regionIndex) != ComputerRegionStates.ATTACK_ENGAGED) {
                for (ComputerUnitRecord other = regions.getFirstMilitaryRecord(record.regionIndex); other != null; other = other.next) {
                    if (other.unit != unit && other.requireUnit().getPrimaryOrder() == ScrOrderIds.PICKUP4 &&
                        (other.requireUnit().getStatus() & UnitStatusFlags.LOADED) == 0) {
                        wait = true;

                        break;
                    }
                }
            }
        }

        if (wait) {
            this.game.getUnitPointMovement().stopAndFaceDestination(unit);

            return;
        }

        if (member && ComputerOrders.getArrival(unit) != ComputerOrders.ARRIVAL_AWAY) {
            int region = regions.getRegion(record.regionIndex);

            if (this.game.getComputerTerrain().getUnitRegion(unit) != region) {
                TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
                int move = this.game.requireGameData().getBaseType(unit) == ScrUnitIds.TERRAN_MEDIC ? ScrOrderIds.HEAL_MOVE
                                                                                                    : ScrOrderIds.HARASS_MOVE;

                this.game.getOrders().issue(unit,
                                            move,
                                            null,
                                            terrain.getCenterX(region), terrain.getCenterY(region),
                                            ScrUnitIds.NONE,
                                            false,
                                            instructionLimit);
            }
        }

        if (ComputerOrders.getArrival(unit) == ComputerOrders.ARRIVAL_AWAY) {
            this.acquire(unit, instructionLimit);

            return;
        }

        int targetX = (short) unit.getOrderTargetX();
        int targetY = (short) unit.getOrderTargetY();
        boolean done;

        if (this.game.getComputerTerrain().getRegion(targetX, targetY) != this.game.getComputerTerrain().getUnitRegion(unit)) {
            done = false;
        } else {
            Sprite sprite = unit.requireSprite();

            if (targetX == (short) sprite.getX() && targetY == (short) sprite.getY()) {
                done = true;
            } else {
                done = RegionPathSearch.calculateDistance(targetX, (short) sprite.getX(), targetY, (short) sprite.getY()) > HARASS_END_DISTANCE;
            }
        }

        if (done) {
            this.game.getOrders().finishOrder(unit, instructionLimit);
        } else if (!this.moveTo(unit, targetX, targetY)) {
            return;
        }

        this.acquire(unit, instructionLimit);
    }

    /**
     * Runs the ComputerReturn order: the unit attacks what it finds on the way, a Medic runs its idle behavior first, and
     * it moves to the order position; once there a guard takes its position as its new post and guards, and any other
     * record continues with ComputerAI.
     *
     * @param unit             the unit
     * @param instructionLimit the iscript budget for issued orders
     * @throws IllegalStateException if the unit has no sprite
     */
    public void updateReturn(@NotNull Unit unit, int instructionLimit) {
        if (this.acquireTarget(unit, 0, true, false, instructionLimit)) {
            return;
        }

        if (this.game.requireGameData().getBaseType(unit) == ScrUnitIds.TERRAN_MEDIC) {
            this.game.getUnitMedic().updateIdle(unit, instructionLimit);

            if (unit.getPrimaryOrder() != ScrOrderIds.COMPUTER_RETURN) {
                return;
            }
        }

        if (unit.getPrimaryOrderState() == 0) {
            if (!this.moveTo(unit, (short) unit.getOrderTargetX(), (short) unit.getOrderTargetY())) {
                return;
            }

            unit.setPrimaryOrderState(1);
        }

        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null) {
            return;
        }

        if (record.getKind() != ComputerUnitRecord.KIND_GUARD) {
            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.COMPUTER_AI, instructionLimit);

            return;
        }

        if (ComputerOrders.getArrival(unit) == ComputerOrders.ARRIVAL_AWAY) {
            return;
        }

        Sprite sprite = unit.requireSprite();

        ComputerMilitary.setGuardPosition(unit, sprite.getX(), sprite.getY());

        this.game.getOrders().issueUntargeted(unit, ScrOrderIds.GUARD, instructionLimit);
    }

    /**
     * Runs the GuardPost order: attacks a target near the post, otherwise returns to the guard position.
     *
     * @param unit             the unit
     * @param instructionLimit the iscript budget for issued orders
     * @throws IllegalStateException         if the unit has no sprite
     * @throws UnsupportedOperationException if the computer-player phase is not initialized
     */
    public void updateGuardPost(@NotNull Unit unit, int instructionLimit) {
        if (!ComputerOrders.consumeTimer(unit)) {
            return;
        }

        ComputerPlayerPhase phase = this.game.requireComputerPlayerPhase();

        if (ComputerSpells.cast(phase, this.game, unit, false)) {
            return;
        }

        if (this.acquireTarget(unit, 0, true, false, instructionLimit)) {
            return;
        }

        if (this.game.requireGameData().getBaseType(unit) == ScrUnitIds.PROTOSS_OBSERVER) {
            return;
        }

        ComputerUnitRecord record = unit.getComputerRecord();

        if (record != null && record.getKind() == ComputerUnitRecord.KIND_GUARD) {
            Sprite sprite = unit.requireSprite();

            if (sprite.getX() != record.guardX || sprite.getY() != record.guardY) {
                // A sieged tank first queues Unsieging.
                if (this.game.requireGameData().getBaseType(unit) == ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE) {
                    this.game.getOrders().queueReplacingSameOrders(unit, ScrOrderIds.UNSIEGING, null, 0, 0, ScrUnitIds.NONE);
                }

                this.game.getOrders().queueReplacingSameOrders(unit,
                                                               ScrOrderIds.COMPUTER_RETURN,
                                                               null,
                                                               record.guardX, record.guardY,
                                                               ScrUnitIds.NONE);
                this.game.getOrders().finishOrder(unit, instructionLimit);

                return;
            }
        }

        this.game.getOrders().issueUntargeted(unit, ScrOrderIds.COMPUTER_AI, instructionLimit);
    }

    /**
     * Starts the move and attacks the automatic target.
     *
     * @return {@code true} if movement started and no automatic-target attack started, {@code false} otherwise
     * @throws IllegalStateException if the enemy automatic target has no sprite
     */
    private boolean moveAndAcquire(@NotNull Unit unit, int instructionLimit) {
        if (unit.getPrimaryOrderState() == 0) {
            if (!this.moveTo(unit, (short) unit.getOrderTargetX(), (short) unit.getOrderTargetY())) {
                return false;
            }

            unit.setPrimaryOrderState(1);
        }

        Unit target = unit.getAutomaticTargetUnit();

        if (target == null || !this.game.getComputerUnits().isEnemy(unit.getOwnerIndex(), target) ||
            (target.requireSprite().getFlags() & Sprite.FLAG_HIDDEN) != 0 || (target.getStatus() & UnitStatusFlags.INVINCIBLE) != 0 ||
            UnitTargeting.isUndetected(unit, target)) {
            return true;
        }

        if (unit.getComputerRecord() == null) {
            // Without a computer record the unit stops, queues its order behind an attack of the automatic target and
            // reactivates, like the attack move of a human unit.
            unit.setAutomaticTargetUnit(null);
            this.game.getUnitAttack().interruptForAttack(unit, target, true, instructionLimit);

            return false;
        }

        return !this.acquireTarget(unit, 0, false, false, instructionLimit);
    }

    /**
     * Lets a unit with an acquisition range choose its target through {@link #acquireTarget} with a computer record,
     * otherwise through the acquisition search, and attack it.
     */
    private void acquire(@NotNull Unit unit, int instructionLimit) {
        if (this.game.getUnitOrders().getTargetAcquisitionRange(unit) == 0) {
            return;
        }

        if (unit.getComputerRecord() == null) {
            // Without a computer record the acquisition search picks the target, which the unit attacks after stopping
            // and queueing its order.
            Unit target = this.game.getUnitTargeting().findAcquireTarget(unit);

            if (target == null) {
                return;
            }

            this.game.getUnitAttack().interruptForAttack(unit, target, true, instructionLimit);

            return;
        }

        this.acquireTarget(unit, 0, false, false, instructionLimit);
    }

    /**
     * Chooses among the region target, the order target, the automatic target and a nearby target, and attacks the
     * choice.
     * <p>
     * Initial record, attack eligibility and queued Patrol guards can return before the phase check.
     *
     * @param unit             the unit with a computer record
     * @param attackArgument   the attack argument passed to {@link #attack}
     * @param allowCritters    whether critters remain valid targets
     * @param restricted       whether targets must be reachable along safe regions
     * @param instructionLimit the iscript budget for issued orders
     * @return {@code true} if a target was chosen, {@code false} otherwise
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if the unit has no sprite
     * @throws UnsupportedOperationException if the computer-player phase is not initialized
     */
    public boolean acquireTarget(@NotNull Unit unit, int attackArgument, boolean allowCritters, boolean restricted, int instructionLimit) {
        if (unit.getComputerRecord() == null || !this.game.getUnitQueries().canAttack(unit)) {
            return false;
        }

        OrderPool.Order head = unit.getFirstOrder();

        if (head != null && head.getOrderId() == ScrOrderIds.PATROL) {
            return false;
        }

        ComputerPlayerPhase phase = this.game.requireComputerPlayerPhase();

        if (unit.getPrimaryOrder() == ScrOrderIds.PICKUP4) {
            restricted = true;

            if (unit.getAutomaticTargetUnit() == null && head != null && head.getOrderId() == ScrOrderIds.ATTACK_UNIT) {
                return false;
            }
        }

        Unit orderTarget = unit.getOrderTargetUnit();
        Unit movementTarget = unit.getMovementTargetUnit();

        if (orderTarget != null && (unit.getStatus() & UnitStatusFlags.CAN_MOVE) != 0 && movementTarget != null &&
            this.game.getComputerUnits().isEnemy(unit.getOwnerIndex(), movementTarget) &&
            ComputerOrders.getArrival(unit) == ComputerOrders.ARRIVAL_BLOCKED) {
            // A chase that cannot reach stops at the unit's position, marks the order target as claimed, drops it as
            // automatic target and clears the order targets of the unit and its turret.
            Sprite sprite = unit.requireSprite();

            unit.setMovementDestinationX(sprite.getX());
            unit.setMovementDestinationY(sprite.getY());
            unit.setStatus(unit.getStatus() & ~UnitStatusFlags.IMMOVABLE);
            orderTarget.setStatus(orderTarget.getStatus() | UnitStatusFlags.COMPUTER_TARGET_CLAIMED);

            if (unit.getAutomaticTargetUnit() == orderTarget) {
                unit.setAutomaticTargetUnit(null);
            }

            unit.setOrderTargetUnit(null);

            Unit turret = unit.getSubunit();

            if (turret != null && (this.game.requireGameData().getUnitTypes().getFlags(turret.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
                turret.setOrderTargetUnit(null);
            }
        }

        ComputerUnits computerUnits = this.game.getComputerUnits();
        boolean flying = (unit.getStatus() & UnitStatusFlags.FLYING) != 0;
        Unit regionTarget = this.game.getComputerTargets().selectUnitTarget(phase, unit, !flying);

        if (regionTarget == null || restricted && this.isExcluded(unit, regionTarget) ||
            !this.game.getUnitTargeting().canAttackTarget(unit, regionTarget) || !allowCritters && computerUnits.isCritter(regionTarget)) {
            regionTarget = null;
        }

        if (regionTarget == null) {
            regionTarget = this.game.getComputerTargets().selectUnitTarget(phase, unit, flying);

            if (regionTarget == null || restricted && this.isExcluded(unit, regionTarget) ||
                !this.game.getUnitTargeting().canAttackTarget(unit, regionTarget) || !allowCritters && computerUnits.isCritter(regionTarget)) {
                regionTarget = null;
            }
        }

        // With the orders.dat Use Weapon Targeting flag the order target competes with the other candidates.
        Unit orderedTarget = null;

        if (this.game.requireGameData().getOrderTypes().getUseWeaponTargeting(unit.getPrimaryOrder()) != 0) {
            orderedTarget = unit.getOrderTargetUnit();

            if (orderedTarget == null || restricted && this.isExcluded(unit, orderedTarget) ||
                !this.game.getUnitTargeting().canAttackTarget(unit, orderedTarget) || !allowCritters && computerUnits.isCritter(orderedTarget)) {
                orderedTarget = null;
            }
        }

        Unit automatic = unit.getAutomaticTargetUnit();

        if (restricted && automatic != null && this.isExcluded(unit, automatic)) {
            unit.setAutomaticTargetUnit(automatic = null);
        }

        // A disabled automatic target is kept without the attacked-owner, exclusion and attack tests.
        if (automatic != null && !UnitOrderEligibility.isDisabled(automatic)) {
            Unit attacked = automatic.getOrderTargetUnit();

            if (this.game.requireGameData().getBaseType(automatic) != ScrUnitIds.TERRAN_BUNKER &&
                (attacked == null || attacked.getOwnerIndex() != unit.getOwnerIndex()) ||
                restricted && this.isExcluded(unit, automatic)) {
                unit.setAutomaticTargetUnit(automatic = null);
            }

            if (automatic != null &&
                (!this.game.getUnitTargeting().canAttackTarget(unit, automatic) || !allowCritters && computerUnits.isCritter(automatic))) {
                unit.setAutomaticTargetUnit(null);
            }
        }

        Unit choice = this.choose(unit, this.choose(unit, orderedTarget, regionTarget), unit.getAutomaticTargetUnit());

        if (choice == null) {
            if (!this.isOutsideRegion(unit)) {
                return false;
            }

            Unit nearby = null;

            if (!this.leavesGuardPost(unit)) {
                nearby = this.findAcquireTarget(unit);
            }

            choice = this.choose(unit, null, nearby);

            if (choice == null || restricted && this.isExcluded(unit, choice) ||
                !this.game.getUnitTargeting().canAttackTarget(unit, choice) || !allowCritters && computerUnits.isCritter(choice)) {
                return false;
            }
        }

        if (unit.getOrderTargetUnit() != choice) {
            if ((unit.getStatus() & UnitStatusFlags.IN_BUNKER) == 0) {
                this.attack(unit, choice, true, attackArgument, instructionLimit);
            } else {
                unit.setOrderTargetUnit(choice);
            }

            int type = unit.getUnitId();

            if (this.game.requireGameData().getUnitTypes().hasHangar(type) && !this.game.getUnitTargeting().isInWeaponMovementRange(unit, choice)) {
                unit.setStatus(unit.getStatus() | UnitStatusFlags.AUTOMATIC_ATTACK_STARTED);
            }
        }

        return true;
    }

    /**
     * Chooses between two targets: an attacking-capable, then an engaging, then an in-range and then the nearer target.
     */
    private @Nullable Unit choose(@NotNull Unit unit, @Nullable Unit first, @Nullable Unit second) {
        Unit firstCandidate = resolveCarrier(this.game, first);
        Unit secondCandidate = resolveCarrier(this.game, second);

        if (firstCandidate == null || this.isClaimedElsewhere(unit, firstCandidate) ||
            !this.game.getUnitTargeting().canAttackTarget(unit, firstCandidate)) {
            firstCandidate = null;
        }

        if (secondCandidate == null || this.isClaimedElsewhere(unit, secondCandidate) ||
            !this.game.getUnitTargeting().canAttackTarget(unit, secondCandidate)) {
            secondCandidate = null;
        }

        if (firstCandidate == null) {
            return secondCandidate;
        }

        if (secondCandidate == null) {
            return firstCandidate;
        }

        if (!this.isLoadedBunker(secondCandidate) && !this.game.getUnitQueries().canAttack(secondCandidate) &&
            this.game.getUnitQueries().canAttack(firstCandidate)) {
            return firstCandidate;
        }

        if (!this.isLoadedBunker(firstCandidate) && !this.game.getUnitQueries().canAttack(firstCandidate) &&
            this.game.getUnitQueries().canAttack(secondCandidate)) {
            return secondCandidate;
        }

        if (!this.isEngaged(unit, firstCandidate) && this.isEngaged(unit, secondCandidate)) {
            return secondCandidate;
        }

        if (this.game.getUnitTargeting().isInWeaponMovementRange(unit, firstCandidate)) {
            return firstCandidate;
        }

        if (this.game.getUnitTargeting().isInWeaponMovementRange(unit, secondCandidate)) {
            return secondCandidate;
        }

        Unit main = this.game.getUnitTargeting().getMainUnit(unit);

        return this.game.getUnitTargeting().getDistance(main, firstCandidate) <= this.game.getUnitTargeting().getDistance(main, secondCandidate)
               ? firstCandidate
               : secondCandidate;
    }

    /**
     * Orders the attack of the unit and of its turret.
     * <p>
     * With {@code ready} set each ordered unit and its subunit are marked ready to attack; the region attack of a sieged
     * Siege Tank and the suicide target pass it clear, the target acquisition passes it set.
     *
     * @param unit             the attacking unit
     * @param target           the attacked target
     * @param ready            whether the ordered units are marked ready to attack
     * @param attackArgument   the argument read by the Siege Tank tail ({@link #attackOutOfSiegeRange})
     * @param instructionLimit the iscript budget for issued orders
     * @throws IllegalStateException if the target has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    void attack(@NotNull Unit unit, @NotNull Unit target, boolean ready, int attackArgument, int instructionLimit) {
        if (this.game.requireGameData().getBaseType(target) == ScrUnitIds.PROTOSS_INTERCEPTOR && target.getHangarParent() != null) {
            target = target.getHangarParent();
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        Sprite sprite = target.requireSprite();

        this.game.getOrders().issue(unit,
                                    units.getAttackUnitOrder(unit.getUnitId()),
                                    target,
                                    sprite.getX(), sprite.getY(),
                                    ScrUnitIds.NONE,
                                    false,
                                    instructionLimit);

        if (ready) {
            unit.markReadyToAttack();
        }

        Unit turret = unit.getSubunit();

        if (turret != null && (units.getFlags(turret.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
            this.game.getOrders().issue(turret,
                                        units.getAttackUnitOrder(turret.getUnitId()),
                                        target,
                                        sprite.getX(), sprite.getY(),
                                        ScrUnitIds.NONE,
                                        false,
                                        instructionLimit);

            if (ready) {
                turret.markReadyToAttack();
            }
        }

        this.attackOutOfSiegeRange(unit, target, attackArgument, instructionLimit);
    }

    /**
     * Handles a sieged Siege Tank with a computer record whose new target is out of range: it keeps its siege when another
     * enemy threatens it and chooses again; otherwise it unsieges and queues the Siege Tank's attack of the target.
     *
     * @param unit             the attacking unit
     * @param target           the attacked target
     * @param attackArgument   nonzero to request detection instead of choosing again
     * @param instructionLimit the iscript budget for issued orders
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if the target has no sprite
     * @throws UnsupportedOperationException if the computer-player phase is not initialized
     */
    public void attackOutOfSiegeRange(@NotNull Unit unit, @NotNull Unit target, int attackArgument, int instructionLimit) {
        if (unit.getComputerRecord() == null || this.game.requireGameData().getBaseType(unit) != ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE ||
            unit.getPrimaryOrder() == ScrOrderIds.UNSIEGING || this.game.getUnitTargeting().isInWeaponMovementRange(unit, target)) {
            return;
        }

        if (this.isSiegeThreatened(unit)) {
            if (attackArgument != 0) {
                this.game.getComputerDefense().requestDetection(this.game.requireComputerPlayerPhase(), unit, target, instructionLimit);
            } else {
                this.acquireTarget(unit, 1, true, false, instructionLimit);
            }

            return;
        }

        this.game.getOrders().issueUntargeted(unit, ScrOrderIds.UNSIEGING, instructionLimit);

        Sprite sprite = target.requireSprite();
        int attack = this.game.requireGameData().getUnitTypes().getAttackUnitOrder(ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE);

        this.game.getOrders().queueReplacingSameOrders(unit, attack, target, sprite.getX(), sprite.getY(), ScrUnitIds.NONE);
    }

    /**
     * Clears the order target of the unit and of its turret.
     *
     * @param unit the unit
     * @throws NullPointerException if the world was built without game data
     */
    void clearOrderTargets(@NotNull Unit unit) {
        unit.setOrderTargetUnit(null);

        Unit turret = unit.getSubunit();

        if (turret != null && (this.game.requireGameData().getUnitTypes().getFlags(turret.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
            turret.setOrderTargetUnit(null);
        }
    }

    /**
     * Finds the acquisition target of a computer unit: the acquisition range is at least the sight range, a Scourge
     * prefers the healthiest target and targets claimed by other computer units are skipped. The simulation plugins decide a found target
     * ({@link ScrSimulationPlugins#decideAcquiredTarget}).
     *
     * @param unit the searching unit
     * @return the target of the best priority, or {@code null}
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the game has no unit finder
     * @throws IllegalStateException         if the unit or a candidate tested for the attack angle has no sprite
     */
    public @Nullable Unit findAcquireTarget(@NotNull Unit unit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int range = this.game.getUnitOrders().getTargetAcquisitionRange(unit);

        if ((unit.getStatus() & UnitStatusFlags.IN_BUNKER) != 0) {
            range += BUNKER_RANGE_BONUS;
        } else if (unit.getComputerRecord() != null) {
            range = Math.max(range, units.getSightRange(unit.getUnitId()));
        }

        int maximumDistance = range << TerrainTileset.TILE_SHIFT;
        int minimumDistance = this.game.getUnitTargeting().getAcquisitionMinimumRange(unit);
        UnitSpatialIndex spatial = this.game.getUnitSystem().requireUnitSpatialIndex();
        Sprite sprite = unit.requireSprite();
        int count = spatial.collectPlacementCandidates(sprite.getX() - maximumDistance - ACQUISITION_QUERY_MARGIN,
                                                       sprite.getY() - maximumDistance - ACQUISITION_QUERY_MARGIN,
                                                       sprite.getX() + maximumDistance + ACQUISITION_QUERY_MARGIN,
                                                       sprite.getY() + maximumDistance + ACQUISITION_QUERY_MARGIN);
        Unit[][] buckets = new Unit[PRIORITY_COUNT][PRIORITY_CAPACITY];
        int[] sizes = new int[PRIORITY_COUNT];

        for (int index = 0; index < count; index++) {
            Unit candidate = spatial.getPlacementCandidate(index);

            if (candidate == unit || !UnitTargeting.isVisible(unit, candidate) || !this.game.getUnitTargeting().canAttackTarget(unit, candidate) ||
                !this.game.getComputerUnits().isEnemy(unit.getOwnerIndex(), candidate)) {
                continue;
            }

            if (!this.game.getUnitTargeting().isWithinAcquisitionDistance(unit, candidate, minimumDistance, maximumDistance)) {
                continue;
            }

            Unit attacking = this.game.getUnitTargeting().getAttackingUnit(unit);

            if ((attacking.getStatus() & UnitStatusFlags.CAN_TURN) == 0) {
                int weapon = this.game.getUnitTargeting().getGroundWeapon(attacking);
                Sprite targetSprite = candidate.requireSprite();

                if (!this.game.getUnitTargeting().isInAttackAngle(attacking, targetSprite.getX(), targetSprite.getY(), weapon)) {
                    continue;
                }
            }

            if (this.isClaimedElsewhere(unit, candidate)) {
                continue;
            }

            int priority = this.game.getUnitTargeting().getAttackPriority(unit, candidate);

            if (priority >= 0 && sizes[priority] < PRIORITY_CAPACITY) {
                buckets[priority][sizes[priority]++] = candidate;
            }
        }

        for (int priority = 0; priority < PRIORITY_COUNT; priority++) {
            if (sizes[priority] == 0) {
                continue;
            }

            Unit best = buckets[priority][0];

            if (this.game.requireGameData().getBaseType(unit) == ScrUnitIds.ZERG_SCOURGE) {
                int bestLife = UnitQueries.getLife(units, best);

                for (int index = 1; index < sizes[priority]; index++) {
                    int life = UnitQueries.getLife(units, buckets[priority][index]);

                    if (Integer.compareUnsigned(bestLife, life) < 0) {
                        best     = buckets[priority][index];
                        bestLife = life;
                    }
                }
            } else {
                int bestDistance = getCenterDistance(unit, best);

                for (int index = 1; index < sizes[priority]; index++) {
                    int distance = getCenterDistance(unit, buckets[priority][index]);

                    if (Integer.compareUnsigned(distance, bestDistance) < 0) {
                        best         = buckets[priority][index];
                        bestDistance = distance;
                    }
                }
            }

            ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

            return simulation != null ? simulation.decideAcquiredTarget(unit, best) : best;
        }

        return null;
    }

    /**
     * Uses a stim pack before a computer unit's attack fires: a Marine or Firebat that is not already sped up, has at
     * least half of its hit points, faces an order target that can attack it within weapon range and has enough life,
     * uses a stim pack when its owner can use the technology.
     *
     * @param unit             the attacking unit
     * @param instructionLimit the image and order budget
     * @return {@code true} if the stim pack was used, {@code false} otherwise
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the computer-player phase is not initialized
     */
    public boolean useStimPack(@NotNull Unit unit, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = this.game.requireGameData().getBaseType(unit);

        if (type != ScrUnitIds.TERRAN_MARINE && type != ScrUnitIds.TERRAN_FIREBAT) {
            return false;
        }

        Unit target = unit.getOrderTargetUnit();

        if (target == null) {
            return false;
        }

        int speed = unit.getSpeedModifier();

        if (speed > 0) {
            return false;
        }

        if (UnitQueries.getHitPoints(unit) < UnitQueries.getMaximumHitPoints(units, unit) / STIM_HEALTH_DIVISOR) {
            return false;
        }

        if (!this.game.getUnitTargeting().canAttackTarget(target, unit) || !this.game.getUnitTargeting().isInWeaponMovementRange(unit, null)) {
            return false;
        }

        if (!this.game.requireComputerPlayerPhase().canUseTechnology(unit, ScrTechIds.STIM_PACKS) ||
            UnitQueries.getLife(units, target) < STIM_TARGET_MINIMUM_LIFE) {
            return false;
        }

        return this.game.getUnitTechnology().stimUnit(unit, instructionLimit);
    }

    /**
     * Orders a guard away from its post back to it (ComputerReturn).
     *
     * @param unit             the unit
     * @param instructionLimit the iscript budget for issued orders
     * @return {@code true} if the guard is away from its post, {@code false} otherwise
     */
    public boolean returnToGuardPost(@NotNull Unit unit, int instructionLimit) {
        if (!this.leavesGuardPost(unit)) {
            return false;
        }

        ComputerUnitRecord record = unit.requireComputerRecord();

        this.game.getOrders().issue(unit, ScrOrderIds.COMPUTER_RETURN, null, record.guardX, record.guardY, ScrUnitIds.NONE, false, instructionLimit);

        return true;
    }

    /**
     * Returns whether a guard is away from its post and should return.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private boolean leavesGuardPost(@NotNull Unit unit) {
        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null || record.getKind() != ComputerUnitRecord.KIND_GUARD) {
            return false;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = unit.getUnitId();
        int group = units.getGroupFlags(type);
        boolean terran = (group & UnitGroupFlags.ZERG) == 0 && (group & UnitGroupFlags.PROTOSS) == 0 && (group & UnitGroupFlags.TERRAN) != 0;

        if (terran && (units.getFlags(type) & UnitTypeFlags.MECHANICAL) != 0 && unit.getHitPoints() != units.getHitPoints(type)) {
            return false;
        }

        if (unit.getAutomaticTargetUnit() != null) {
            return false;
        }

        if (this.game.getComputerTerrain().getUnitRegion(unit) ==
            this.game.getComputerTerrain().getRegion((short) record.guardX, (short) record.guardY) ||
            ComputerTargets.isNear(unit, ComputerTargets.GUARD_RADIUS, record.guardX, record.guardY)) {
            return false;
        }

        if (unit.getOrderTargetUnit() != null && this.game.getUnitTargeting().isInWeaponMovementRange(unit, null)) {
            return false;
        }

        OrderPool.Order head = unit.getFirstOrder();

        return head == null || head.getOrderId() != ScrOrderIds.PATROL;
    }

    /**
     * Tests whether a computer unit moving to a position needs a transport, inside the move-target setters: a movable
     * ground unit whose destination is in another ground component, or on an unsafe route while its region is not
     * attacking, or far away for a Reaver, asks for a transport. Without a usable own transport the move proceeds; with
     * one the unit starts waiting for it.
     * <p>
     * Initial waiting, flying and subunit guards can return before position validation.
     *
     * @param unit      the moving unit
     * @param positionX the horizontal destination
     * @param positionY the vertical destination
     * @return {@code true} if the unit waits or starts waiting for a transport, whose move target stays unchanged, {@code false} otherwise
     * @throws IllegalStateException         if the unit has no sprite
     * @throws UnsupportedOperationException if the computer-player phase is not initialized
     */
    public boolean requiresPickup(@NotNull Unit unit, int positionX, int positionY) {
        // A unit already waiting for its transport keeps its move target.
        if (unit.getPrimaryOrder() == ScrOrderIds.PICKUP4) {
            return true;
        }

        if ((unit.getStatus() & UnitStatusFlags.FLYING) != 0 || this.game.getComputerUnits().isSubunit(unit)) {
            return false;
        }

        Sprite sprite = unit.requireSprite();

        if ((short) sprite.getX() == positionX && (short) sprite.getY() == positionY) {
            return false;
        }

        if ((unit.getStatus() & UnitStatusFlags.CAN_MOVE) == 0) {
            return false;
        }

        if (this.game.requireGameData().getBaseType(unit) == ScrUnitIds.TERRAN_SCV && unit.getPrimaryOrder() == ScrOrderIds.REPAIR &&
            unit.getOrderTargetUnit() != null &&
            (unit.getOrderTargetUnit().getStatus() & UnitStatusFlags.FLYING) != 0) {
            return false;
        }

        int destination = this.game.getComputerTerrain().getRegion(positionX, positionY);

        if (this.game.getComputerTerrain().isInaccessible(destination)) {
            return false;
        }

        if (unit.sharesTerrainComponent(this.game.getComputerTerrain().getRegions(), positionX, positionY)) {
            ComputerUnitRecord record = unit.getComputerRecord();
            ComputerPlayerPhase phase = this.game.requireComputerPlayerPhase();

            if (record != null && record.getKind() == ComputerUnitRecord.KIND_MILITARY) {
                int regionState = phase.getRegions().getState(record.regionIndex);

                if (regionState == ComputerRegionStates.ATTACK || regionState == ComputerRegionStates.ATTACK_ENGAGED) {
                    return false;
                }
            }

            if (phase.getMilitary().isRouteSafe(unit, positionX, positionY)) {
                if (this.game.requireGameData().getBaseType(unit) != ScrUnitIds.PROTOSS_REAVER) {
                    return false;
                }

                if (RegionPathSearch.calculateDistance(positionX, (short) sprite.getX(), positionY, (short) sprite.getY()) < REAVER_PICKUP_DISTANCE) {
                    return false;
                }
            }
        }

        boolean[] found = new boolean[1];

        this.findTransport(unit, found);

        if (!found[0]) {
            return false;
        }

        this.startPickup(unit, positionX, positionY);

        return true;
    }

    /**
     * Finds a transport for the unit over the owner list: an own non-hallucinated transport (an Overlord only with
     * Ventral Sacs) that is completed and movable either already targets the unit (returned at once) or, with enough hit
     * points or full shields, sets {@code found[0]}; the candidate nearest to the unit's exact position that can load it,
     * is not picking up or unloading and, while move-unloading, unloads in the region of the unit's order position, is
     * returned.
     *
     * @param unit  the unit to carry
     * @param found receives whether an eligible transport was encountered, even if none can currently load the unit
     * @return the chosen transport, or {@code null}
     * @throws IllegalStateException         if a candidate transport has no sprite
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private @Nullable Unit findTransport(@NotNull Unit unit, boolean @NotNull [] found) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        Unit best = null;
        int bestDistance = ComputerPlayerPhase.NO_DISTANCE;

        found[0] = false;

        for (int poolIndex = pool.getFirstOwnerIndex(unit.getOwnerIndex()); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit candidate = pool.getUnit(poolIndex);

            if ((candidate.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
                continue;
            }

            int type = candidate.getUnitId();

            if (this.game.requireGameData().getBaseType(candidate) == ScrUnitIds.ZERG_OVERLORD) {
                PlayerUpgrades upgrades = this.game.getPlayerTable().getPlayerUpgrades();

                if (upgrades == null || upgrades.getLevel(candidate.getOwnerIndex(), ScrUpgradeIds.VENTRAL_SACS) == 0) {
                    continue;
                }
            }

            if (units.getSpaceProvided(type) == 0 || (candidate.getStatus() & UnitStatusFlags.COMPLETED) == 0 ||
                (candidate.getStatus() & UnitStatusFlags.CAN_MOVE) == 0) {
                continue;
            }

            if (candidate.getOrderTargetUnit() == unit) {
                found[0] = true;

                return candidate;
            }

            int maximum = UnitQueries.getMaximumHitPoints(units, candidate);

            if (UnitQueries.getHitPoints(candidate) < maximum * TRANSPORT_HEALTH_NUMERATOR / TRANSPORT_HEALTH_DENOMINATOR &&
                (units.getShieldEnabled(type) == 0 || candidate.getShieldPoints() >> SHIELD_POINT_FRACTION_BITS != units.getShieldAmount(type))) {
                continue;
            }

            found[0] = true;

            if (!this.game.getUnitTransport().canLoad(candidate, unit)) {
                continue;
            }

            int order = candidate.getPrimaryOrder();

            if (order == ScrOrderIds.PICKUP_TRANSPORT || order == ScrOrderIds.UNLOAD) {
                continue;
            }

            if (order == ScrOrderIds.MOVE_UNLOAD &&
                this.game.getComputerTerrain().getRegion((short) candidate.getOrderTargetX(), (short) candidate.getOrderTargetY()) !=
                this.game.getComputerTerrain().getRegion((short) unit.getOrderTargetX(), (short) unit.getOrderTargetY())) {
                continue;
            }

            Sprite sprite = candidate.requireSprite();
            int distance = RegionPathSearch.calculateDistance(unit.getFixedPositionX(), (short) sprite.getX(),
                                                              unit.getFixedPositionY(), (short) sprite.getY());

            if (Integer.compareUnsigned(distance, bestDistance) < 0) {
                best         = candidate;
                bestDistance = distance;
            }
        }

        return best;
    }

    /**
     * Starts waiting for a transport: the current order goes back in front of the queue, the PICKUP4 order to the
     * destination is inserted before it, a real subunit gets order Nothing, the queue activates and the unit and its
     * subunit are marked ready to attack.
     *
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the computer-player phase is not initialized
     */
    private void startPickup(@NotNull Unit unit, int positionX, int positionY) {
        int limit = this.game.requireComputerPlayerPhase().getInstructionLimit();
        int order = unit.getPrimaryOrder();
        Unit orderTarget = unit.getOrderTargetUnit();
        int orderX = unit.getOrderTargetX();
        int orderY = unit.getOrderTargetY();

        if (unit.getFirstOrder() != null) {
            this.game.getOrders().queueFront(unit, order, orderTarget, orderX, orderY, ScrUnitIds.NONE);
        } else {
            this.game.getOrders().queueReplacingSameOrders(unit, order, orderTarget, orderX, orderY, ScrUnitIds.NONE);
        }

        this.game.getOrders().queueFront(unit, ScrOrderIds.PICKUP4, null, positionX, positionY, ScrUnitIds.NONE);

        Unit subunit = unit.getSubunit();
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if (subunit != null && (units.getFlags(subunit.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
            this.game.getOrders().issueUntargeted(subunit, ScrOrderIds.NOTHING, limit);
        }

        this.game.getOrders().completeOrder(unit, limit);
        unit.markReadyToAttack();
    }

    /**
     * Runs the PICKUP4 order of a unit waiting for a transport.
     * <p>
     * A neutral unit leaves the order (order Nothing inside a transport, else its Return to Idle order); a loaded unit
     * waits; a found target ends the wait. Without a transport target the unit looks for one (none ends the order); an
     * invalid or unloading transport is forgotten. Next to the transport the unit is loaded, the transport moves to unload
     * at the unit's order position and, for a computer transport, queues a move to its own position; otherwise the
     * transport is ordered to pick the unit up and the unit, on a safe route, turns to and follows the transport.
     *
     * @param unit             the waiting unit
     * @param instructionLimit the iscript budget for issued orders
     * @throws NullPointerException  if the world was built without game data
     * @throws IllegalStateException if the unit or its transport has no sprite
     */
    public void updatePickupWait(@NotNull Unit unit, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        boolean loaded = (unit.getStatus() & UnitStatusFlags.LOADED) != 0;

        if (unit.getOwnerIndex() == PlayerTable.NEUTRAL_PLAYER_ID) {
            // Both leave through the general order issue, which accepts a queue.
            int order = loaded ? ScrOrderIds.NOTHING : units.getReturnToIdleOrder(unit.getUnitId());

            this.game.getOrders().issueUntargeted(unit, order, instructionLimit);

            return;
        }

        if (loaded) {
            return;
        }

        if (this.acquireTarget(unit, 0, false, true, instructionLimit)) {
            return;
        }

        Unit transport = unit.getOrderTargetUnit();

        if (transport == null) {
            // The transport search writes its choice, or null, straight into the order target.
            boolean[] found = new boolean[1];

            transport = this.findTransport(unit, found);

            unit.setOrderTargetUnit(transport);

            if (!found[0]) {
                this.game.getOrders().finishOrder(unit, instructionLimit);

                return;
            }

            if (transport == null) {
                return;
            }
        } else {
            boolean valid = (transport.getStatus() & UnitStatusFlags.HALLUCINATION) == 0;

            if (valid && this.game.requireGameData().getBaseType(transport) == ScrUnitIds.ZERG_OVERLORD) {
                PlayerUpgrades upgrades = this.game.getPlayerTable().getPlayerUpgrades();

                valid = upgrades != null && upgrades.getLevel(transport.getOwnerIndex(), ScrUpgradeIds.VENTRAL_SACS) != 0;
            }

            if (valid) {
                valid = units.getSpaceProvided(transport.getUnitId()) != 0 && transport.getOrderTargetUnit() == unit;
            }

            if (!valid) {
                unit.setOrderTargetUnit(null);

                return;
            }
        }

        if (!this.game.getUnitTransport().canLoad(transport, unit)) {
            unit.setOrderTargetUnit(null);

            return;
        }

        if (this.game.getUnitTargeting().isInRange(unit, transport, 1)) {
            this.game.getUnitTransport().load(transport, unit, instructionLimit);
            this.game.getOrders().issue(transport,
                                        ScrOrderIds.MOVE_UNLOAD,
                                        null,
                                        (short) unit.getOrderTargetX(), (short) unit.getOrderTargetY(),
                                        ScrUnitIds.NONE,
                                        false,
                                        instructionLimit);

            if (transport.getComputerRecord() != null) {
                Sprite sprite = transport.requireSprite();

                this.game.getOrders().queueReplacingSameOrders(transport,
                                                               ScrOrderIds.MOVE,
                                                               null,
                                                               (short) sprite.getX(), (short) sprite.getY(),
                                                               ScrUnitIds.NONE);
            }

            return;
        }

        Sprite unitSprite = unit.requireSprite();

        if (transport.getFirstOrder() != null) {
            this.game.getOrders().queueFront(transport, ScrOrderIds.PICKUP_TRANSPORT, unit, unitSprite.getX(), unitSprite.getY(), ScrUnitIds.NONE);
        } else {
            this.game.getOrders().queueBack(transport, ScrOrderIds.PICKUP_TRANSPORT, unit, unitSprite.getX(), unitSprite.getY(), ScrUnitIds.NONE);
        }

        this.game.getOrders().activateNext(transport, instructionLimit);

        Sprite transportSprite = transport.requireSprite();
        int transportX = (short) transportSprite.getX();
        int transportY = (short) transportSprite.getY();

        if (!this.game.getComputerMilitary().isRouteSafe(unit, transportX, transportY)) {
            return;
        }

        if ((short) unit.getDirectionTargetX() != transportX || (short) unit.getDirectionTargetY() != transportY) {
            unit.setDirectionTargetX(transportX);
            unit.setDirectionTargetY(transportY);
        }

        this.game.getUnitAttack().tryFollowTarget(unit, transport, instructionLimit);
    }

    /**
     * Sets the movement destination and the direction target; the direction target is set even when the transport test
     * starts a pickup.
     */
    private boolean moveTo(@NotNull Unit unit, int positionX, int positionY) {
        boolean moved = this.game.getUnitPositioning().setMovementDestination(unit, positionX, positionY);

        if ((short) unit.getDirectionTargetX() != positionX || (short) unit.getDirectionTargetY() != positionY) {
            unit.setDirectionTargetX(positionX);
            unit.setDirectionTargetY(positionY);
        }

        return moved;
    }
}
