package org.fogofwarlabs.sc4j.scr.game.combat;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.modifier.Stat;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrWeaponIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrWeaponTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.WeaponTargetFlags;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitHangar;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerCombat;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerPlayerPhase;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerTerrain;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.player.*;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.*;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Answers weapon, visibility, range and priority questions used by attack orders.
 * <p>
 * The absent weapon is {@link ScrWeaponIds#NONE} ({@code 130}).
 */
public final class UnitTargeting {
    /**
     * The number of attack priority classes; acquisition starts above the last one.
     */
    private static final int PRIORITY_COUNT = 6;
    /**
     * Only the first sixteen candidates of a priority class compete.
     */
    private static final int PRIORITY_CAPACITY = 16;

    /**
     * The attack priority of a Larva or an egg, the last class.
     */
    private static final int PRIORITY_LARVA_OR_EGG = 5;
    /**
     * The attack priority added for a worker or for a target that can attack, but not the attacker.
     */
    private static final int PRIORITY_WORKER_OR_OTHER_ATTACKER = 2;
    /**
     * The attack priority added for a mobile target that cannot attack.
     */
    private static final int PRIORITY_HARMLESS_MOBILE = 3;
    /**
     * The attack priority added for an immobile target that cannot attack.
     */
    private static final int PRIORITY_HARMLESS_IMMOBILE = 4;

    /**
     * The weapon range bonus in pixels of a unit in a Bunker.
     */
    private static final int BUNKER_RANGE_BONUS = 64;
    /**
     * The acquisition range bonus in tiles of a unit in a Bunker.
     */
    private static final int BUNKER_ACQUISITION_BONUS = 2;
    /**
     * The weapon range bonus in pixels of the hero Fenix (Dragoon).
     */
    private static final int FENIX_RANGE_BONUS = 64;
    /**
     * The weapon range bonus in pixels of Alan Schezar's Hellfire Missile Pack.
     */
    private static final int ALAN_SCHEZAR_RANGE_BONUS = 96;

    /**
     * The random call site of the random target acquisition.
     */
    private static final int ACQUIRE_RANDOM_SOURCE = 19;
    /**
     * The margin in pixels around the acquisition distance of the candidate query.
     */
    private static final int CANDIDATE_SEARCH_MARGIN = 64;
    /**
     * The half size in pixels of the box that the unit type search around a position covers.
     */
    private static final int TYPE_SEARCH_RADIUS = 96;

    /**
     * The fraction bits of a fixed-point distance.
     */
    private static final int FIXED_POINT_FRACTION_BITS = 8;
    /**
     * The divisor of a placement size that gives its half.
     */
    private static final int PLACEMENT_CENTER_DIVISOR = 2;

    /**
     * @param unit   the observing unit
     * @param target the candidate target
     * @return {@code true} if the target is cloaked or burrowed and not detected by the observer owner, {@code false} otherwise
     */
    public static boolean isUndetected(@NotNull Unit unit, @NotNull Unit target) {
        return (target.getStatus() & (UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED)) != 0 &&
               (target.getDetectionFlags() & 1 << unit.getOwnerIndex()) == 0;
    }

    /**
     * @param unit   the observing unit
     * @param target the candidate target
     * @return {@code true} if the target sprite is visible to the observer owner, {@code false} otherwise
     * @throws IllegalStateException if the target has no sprite
     */
    public static boolean isVisible(@NotNull Unit unit, @NotNull Unit target) {
        return (target.requireSprite().getVisibilityFlags() & 1 << unit.getOwnerIndex()) != 0;
    }

    /**
     * @param unit   the observing unit
     * @param target the candidate target
     * @return {@code true} if the target is detected and visible to the observer owner, {@code false} otherwise
     * @throws IllegalStateException if the target passes detection and has no sprite
     */
    public static boolean canSee(@NotNull Unit unit, @NotNull Unit target) {
        return !isUndetected(unit, target) && isVisible(unit, target);
    }

    /**
     * Collects the placement candidates of the box that the acquisition distance and the search margin span around a unit.
     *
     * @param spatialIndex    the unit finder
     * @param unit            the acquiring unit
     * @param maximumDistance the acquisition distance in pixels
     * @return the number of collected candidates
     * @throws IllegalStateException if the unit has no sprite
     */
    private static int queryPriorityCandidates(@NotNull UnitSpatialIndex spatialIndex, @NotNull Unit unit, int maximumDistance) {
        Sprite sprite = unit.requireSprite();

        return spatialIndex.collectPlacementCandidates(sprite.getX() - maximumDistance - CANDIDATE_SEARCH_MARGIN,
                                                       sprite.getY() - maximumDistance - CANDIDATE_SEARCH_MARGIN,
                                                       sprite.getX() + maximumDistance + CANDIDATE_SEARCH_MARGIN,
                                                       sprite.getY() + maximumDistance + CANDIDATE_SEARCH_MARGIN);
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitTargeting} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitTargeting(@NotNull Game game) {
        this.game = game;
    }

    /**
     * @param unit   the source unit
     * @param target the candidate target
     * @return {@code true} if the source owner is not allied to the target owner, using the sprite owner for neutral units, {@code false} otherwise
     * @throws UnsupportedOperationException if alliances are unavailable
     * @throws IllegalStateException         if a neutral target requires its sprite owner and has no sprite
     */
    public boolean isEnemy(@NotNull Unit unit, @NotNull Unit target) {
        return this.isEnemy(unit.getOwnerIndex(), target);
    }

    /**
     * @param owner  the observing player
     * @param target the candidate target
     * @return {@code true} if {@code owner} is not allied to the target owner, using the sprite owner for neutral units, {@code false} otherwise
     * @throws UnsupportedOperationException if alliances are unavailable
     * @throws IllegalStateException         if a neutral target requires its sprite owner and has no sprite
     */
    public boolean isEnemy(int owner, @NotNull Unit target) {
        PlayerRelations relations = this.game.getPlayerTable().getPlayerRelations();

        if (relations == null) {
            throw Exceptions.newUnsupportedOperationException("Target allegiance requires player alliances.");
        }

        int targetOwner = target.getOwnerIndex();

        if (targetOwner == PlayerTable.NEUTRAL_PLAYER_ID) {
            targetOwner = target.requireSprite().getOwner();
        }

        return relations.getAlliance(owner, targetOwner) == PlayerRelations.ENEMY;
    }

    /**
     * @param unit the unit to inspect
     * @return {@code true} if the unit type is a turret subunit, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    public boolean isTurret(@NotNull Unit unit) {
        return (this.game.requireGameData().getUnitTypes().getFlags(unit.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0;
    }

    /**
     * @param unit a unit or turret
     * @return the turret's parent, or the unit itself
     * @throws IllegalStateException if a turret has no parent unit
     */
    public @NotNull Unit getMainUnit(@NotNull Unit unit) {
        if (this.isTurret(unit)) {
            Unit parent = unit.getSubunit();

            if (parent == null) {
                throw Exceptions.newIllegalStateException("A turret has no parent unit link.");
            }

            return parent;
        }

        return unit;
    }

    /**
     * @param unit a unit
     * @return the unit's turret, or the unit itself
     */
    public @NotNull Unit getAttackingUnit(@NotNull Unit unit) {
        Unit subunit = unit.getSubunit();

        return subunit != null && this.isTurret(subunit) ? subunit : unit;
    }

    /**
     * @param unit the attacking unit
     * @return the ground weapon, {@link ScrWeaponIds#NONE} for an unburrowed Lurker or a unit without one
     * @throws NullPointerException if the world was built without game data
     */
    public int getGroundWeapon(@NotNull Unit unit) {
        if (this.game.getBaseType(unit) == ScrUnitIds.ZERG_LURKER && (unit.getStatus() & UnitStatusFlags.BURROWED) == 0) {
            return ScrWeaponIds.NONE;
        }

        return this.game.requireGameData().getUnitTypes().getGroundWeapon(unit.getUnitId());
    }

    /**
     * @param unit the attacking unit
     * @return the air weapon, or {@link ScrWeaponIds#NONE}
     * @throws NullPointerException if the world was built without game data
     */
    public int getAirWeapon(@NotNull Unit unit) {
        return this.game.requireGameData().getUnitTypes().getAirWeapon(unit.getUnitId());
    }

    /**
     * @param unit the unit
     * @return the unit's ground weapon, or that of its subunit when the unit has none, or {@link ScrWeaponIds#NONE}
     */
    int getUnitOrSubunitGroundWeapon(@NotNull Unit unit) {
        int weapon = this.getGroundWeapon(unit);

        if (weapon != ScrWeaponIds.NONE || unit.getSubunit() == null) {
            return weapon;
        }

        return this.getGroundWeapon(unit.getSubunit());
    }

    /**
     * @param unit the unit
     * @return the unit's air weapon, or that of its subunit when the unit has none, or {@link ScrWeaponIds#NONE}
     */
    public int getUnitOrSubunitAirWeapon(@NotNull Unit unit) {
        int weapon = this.getAirWeapon(unit);

        if (weapon != ScrWeaponIds.NONE || unit.getSubunit() == null) {
            return weapon;
        }

        return this.getAirWeapon(unit.getSubunit());
    }

    /**
     * @param unit   the attacking unit or its parent
     * @param target the target
     * @return the air weapon for a flying target or the ground weapon otherwise, of the attacking turret when present
     */
    public int getTargetWeapon(@NotNull Unit unit, @NotNull Unit target) {
        Unit attacker = this.getAttackingUnit(unit);

        return (target.getStatus() & UnitStatusFlags.FLYING) != 0 ? this.getAirWeapon(attacker) : this.getGroundWeapon(attacker);
    }

    /**
     * Checks visibility, disability, target status and the unit-specific attack gates. A Carrier always can, a Reaver can attack a ground target in
     * its terrain component ({@link #isReachable}), a Queen an infestable target, and an Arbiter with a computer record never can; other units need a
     * weapon for the target.
     *
     * @param unit   the attacking unit
     * @param target the target, or {@code null}
     * @return {@code true} if the unit can attack the target, {@code false} otherwise
     * @throws IllegalStateException if the unit is not disabled and a target that is not invincible has no sprite
     */
    @Contract("_, null -> false")
    public boolean canAttackTarget(@NotNull Unit unit, @Nullable Unit target) {
        if (target == null) {
            return false;
        }

        if (UnitOrderEligibility.isDisabled(unit)) {
            return false;
        }

        if ((target.getStatus() & UnitStatusFlags.INVINCIBLE) != 0) {
            return false;
        }

        if ((target.requireSprite().getFlags() & Sprite.FLAG_HIDDEN) != 0) {
            return false;
        }

        if (isUndetected(unit, target)) {
            return false;
        }

        int unitType = unit.getUnitId();
        int baseType = this.game.getBaseType(unitType);

        if (this.game.requireGameData().getUnitTypes().isCarrier(unitType)) {
            return true;
        }

        if (this.game.requireGameData().getUnitTypes().isReaver(unitType)) {
            if ((target.getStatus() & UnitStatusFlags.FLYING) != 0) {
                return false;
            }

            return this.isReachable(unit, target);
        }

        if (baseType == ScrUnitIds.ZERG_QUEEN || baseType == ScrUnitIds.HERO_MATRIARCH) {
            return this.game.getUnitInfestation().canBeInfested(target);
        }

        // An Arbiter with a computer record does not attack.
        if (baseType == ScrUnitIds.PROTOSS_ARBITER && unit.getComputerRecord() != null) {
            return false;
        }

        return this.getTargetWeapon(unit, target) != ScrWeaponIds.NONE;
    }

    /**
     * Checks whether two units stand in the same connected terrain component: the regions at their sprite positions
     * ({@link ComputerTerrain#getUnitRegion}) have the same component.
     *
     * @param unit   the first unit
     * @param target the second unit
     * @return {@code true} if both units share a terrain component, {@code false} otherwise
     * @throws IllegalStateException if the terrain regions are missing
     */
    public boolean isReachable(@NotNull Unit unit, @NotNull Unit target) {
        TerrainContours terrain = this.game.getPathSystem().getTerrainContours();

        if (terrain == null) {
            throw Exceptions.newIllegalStateException("Reachability requires terrain regions.");
        }

        TerrainRegions regions = terrain.getRegions();
        ComputerTerrain computerTerrain = this.game.getComputerTerrain();

        return regions.getComponent(computerTerrain.getUnitRegion(unit)) == regions.getComponent(computerTerrain.getUnitRegion(target));
    }

    /**
     * @param unit the unit to inspect
     * @return {@code true} if the unit or its subunit has a weapon, {@code false} otherwise
     */
    public boolean canAttack(@NotNull Unit unit) {
        if (this.getUnitOrSubunitGroundWeapon(unit) != ScrWeaponIds.NONE || this.getUnitOrSubunitAirWeapon(unit) != ScrWeaponIds.NONE) {
            return true;
        }

        if (UnitHangar.getInterceptorCount(this.game.requireGameData().getUnitTypes(), unit) != 0) {
            return true;
        }

        return this.game.requireGameData().getUnitTypes().isReaver(unit.getUnitId()) && unit.getHangarCount(false) != 0;
    }

    /**
     * Computes attack priority, preferring lower values and the first passenger of a loaded Bunker.
     *
     * @param unit   the attacking unit
     * @param target the target
     * @return the priority from {@code 0} through {@code 5}
     * @throws NullPointerException if the world was built without game data
     */
    public int getAttackPriority(@NotNull Unit unit, @NotNull Unit target) {
        boolean loadedUnit = false;

        if (target.getUnitId() == ScrUnitIds.TERRAN_BUNKER) {
            int slots = this.game.requireGameData().getUnitTypes().getSpaceProvided(ScrUnitIds.TERRAN_BUNKER);

            for (int slot = 0; slot < slots; slot++) {
                Unit loaded = this.game.getUnitTransport().getLoadedUnit(target, slot);

                if (loaded != null) {
                    target     = loaded;
                    loadedUnit = true;

                    break;
                }
            }
        }

        int targetType = target.getUnitId();

        if (this.game.requireGameData().getUnitTypes().isLarvaOrEgg(targetType)) {
            return PRIORITY_LARVA_OR_EGG;
        }

        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        int priority = 0;

        if ((unitTypes.getFlags(targetType) & UnitTypeFlags.WORKER) != 0) {
            priority += PRIORITY_WORKER_OR_OTHER_ATTACKER;
        } else if (!this.canAttackTarget(target, unit)) {
            if (this.canAttack(target)) {
                priority += PRIORITY_WORKER_OR_OTHER_ATTACKER;
            } else if ((target.getStatus() & UnitStatusFlags.CAN_MOVE) != 0) {
                priority += PRIORITY_HARMLESS_MOBILE;
            } else {
                priority += PRIORITY_HARMLESS_IMMOBILE;
            }
        }

        if (loadedUnit || (target.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
            priority++;
        }

        if (priority == 0 && (target.getStatus() & UnitStatusFlags.CANNOT_ATTACK) != 0) {
            priority++;
        }

        return priority;
    }

    /**
     * Computes the approximate distance between the inner-plus-one sprite bounding boxes.
     *
     * @param first  the first unit
     * @param second the second unit
     * @return the approximate distance between both boxes in pixels
     * @throws IllegalStateException if either unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public int getDistance(@NotNull Unit first, @NotNull Unit second) {
        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        Sprite firstSprite = first.requireSprite();
        Sprite secondSprite = second.requireSprite();
        int firstType = first.getUnitId();
        int secondType = second.getUnitId();

        int firstLeft = firstSprite.getX() - unitTypes.getDimensionLeft(firstType);
        int firstUpper = firstSprite.getY() - unitTypes.getDimensionUp(firstType);
        int firstRight = firstSprite.getX() + unitTypes.getDimensionRight(firstType) + 1;
        int firstLower = firstSprite.getY() + unitTypes.getDimensionDown(firstType) + 1;
        int secondLeft = secondSprite.getX() - unitTypes.getDimensionLeft(secondType);
        int secondUpper = secondSprite.getY() - unitTypes.getDimensionUp(secondType);
        int secondRight = secondSprite.getX() + unitTypes.getDimensionRight(secondType) + 1;
        int secondLower = secondSprite.getY() + unitTypes.getDimensionDown(secondType) + 1;

        int horizontal = firstLeft > secondRight ? firstLeft - secondRight : secondLeft > firstRight ? secondLeft - firstRight : 0;
        int vertical = firstUpper > secondLower ? firstUpper - secondLower : secondUpper > firstLower ? secondUpper - firstLower : 0;

        return RegionPathSearch.calculateDistance(0, horizontal, 0, vertical);
    }

    /**
     * Measures from the nearest point of the sprite bounding box.
     *
     * @param unit      the unit
     * @param positionX the horizontal pixel coordinate
     * @param positionY the vertical pixel coordinate
     * @return the approximate distance in pixels
     * @throws IllegalStateException if the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public int getDistanceTo(@NotNull Unit unit, int positionX, int positionY) {
        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        Sprite sprite = unit.requireSprite();
        int unitType = unit.getUnitId();

        int left = sprite.getX() - unitTypes.getDimensionLeft(unitType);
        int upper = sprite.getY() - unitTypes.getDimensionUp(unitType);
        int right = sprite.getX() + unitTypes.getDimensionRight(unitType) + 1;
        int lower = sprite.getY() + unitTypes.getDimensionDown(unitType) + 1;
        int nearestX = left > positionX ? left : Math.min(right, positionX);
        int nearestY = upper > positionY ? upper : Math.min(lower, positionY);

        return RegionPathSearch.calculateDistance(nearestX, positionX, nearestY, positionY);
    }

    /**
     * Checks whether the main unit's box is within an inclusive pixel range of the target box.
     * <p>
     * The range compares unsigned, so a range of {@code -1} reaches every target.
     *
     * @param unit   the unit or turret
     * @param target the target
     * @param range  the inclusive pixel range, compared unsigned
     * @return {@code true} if the main unit's box is within range of the target box, {@code false} otherwise
     * @throws IllegalStateException if a turret has no parent unit
     * @throws NullPointerException  if the world was built without game data
     */
    public boolean isInRange(@NotNull Unit unit, @NotNull Unit target, int range) {
        return Integer.compareUnsigned(this.getDistance(this.getMainUnit(unit), target), range) <= 0;
    }

    /**
     * Applies Bunker, unit range upgrade and hero bonuses to the weapon's maximum range.
     *
     * @param unit   the attacking unit
     * @param weapon the weapon
     * @return the maximum range in pixels
     * @throws UnsupportedOperationException if a range upgrade applies and the player upgrade levels are missing
     * @throws NullPointerException          if the world was built without game data
     */
    public int getWeaponMaximumRange(@NotNull Unit unit, int weapon) {
        int range = this.game.requireGameData().getWeaponTypes().getMaximumRange(weapon);

        if ((unit.getStatus() & UnitStatusFlags.IN_BUNKER) != 0) {
            range += BUNKER_RANGE_BONUS;
        }

        range = this.game.applyModifiers(Stat.WEAPON_RANGE, unit.getOwnerIndex(), unit.getUnitId(), weapon, range);

        if (this.game.getBaseType(unit) == ScrUnitIds.HERO_FENIX_DRAGOON) {
            range += FENIX_RANGE_BONUS;
        }

        if ((unit.getUnitId() == ScrUnitIds.HERO_ALAN_SCHEZAR || unit.getUnitId() == ScrUnitIds.HERO_ALAN_SCHEZAR_TURRET) &&
            weapon == ScrWeaponIds.HELLFIRE_MISSILE_PACK_ALAN_SCHEZAR) {
            range += ALAN_SCHEZAR_RANGE_BONUS;
        }

        return range;
    }

    /**
     * Computes the stopping distance of a unit with the game's flingy types, by the engine motion profile rule ({@link FlingyMotionUtils}).
     *
     * @param unit the moving unit
     * @return the stopping distance with eight fractional bits
     * @throws NullPointerException if the world was built without game data
     */
    public int getHaltDistance(@NotNull Unit unit) {
        return FlingyMotionUtils.calculateStoppingDistance(this.game.requireGameData().getFlingyProperties(), unit);
    }

    /**
     * Computes the extra range while the attacker accelerates and the target is not moving in a similar direction.
     *
     * @param unit   the attacking unit
     * @param target the target
     * @return the extra range in whole pixels
     */
    public int getTargetMovementRange(@NotNull Unit unit, @NotNull Unit target) {
        if ((unit.getMovementFlags() & MovementFlags.ACCELERATING) == 0) {
            return 0;
        }

        if ((target.getMovementFlags() & MovementFlags.ACCELERATING) != 0 &&
            Math.abs((byte) (target.getTargetHeading() - unit.getTargetHeading())) <= FlingyMotionUtils.DIRECTION_EIGHTH_TURN) {
            return 0;
        }

        return this.getHaltDistance(unit) >> FIXED_POINT_FRACTION_BITS;
    }

    /**
     * Checks visibility and inclusive maximum weapon and movement range, excluding the minimum range.
     *
     * @param unit   the attacking unit
     * @param target the target, or {@code null} for the current order target
     * @return {@code true} if the target can be engaged from the current movement state, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    public boolean isInWeaponMovementRange(@NotNull Unit unit, @Nullable Unit target) {
        if (target == null) {
            target = unit.getOrderTargetUnit();
        }

        if (target == null) {
            return true;
        }

        if (!this.canSeeTarget(unit, target)) {
            return false;
        }

        int weapon = this.getTargetWeapon(unit, target);

        if (weapon == ScrWeaponIds.NONE) {
            return false;
        }

        // Both distance comparisons use unsigned ranges.
        int distance = this.getDistance(this.getMainUnit(unit), target);
        int minimumRange = this.game.requireGameData().getWeaponTypes().getMinimumRange(weapon);

        if (minimumRange != 0 && Integer.compareUnsigned(distance, minimumRange) <= 0) {
            return false;
        }

        return Integer.compareUnsigned(distance, this.getWeaponMaximumRange(unit, weapon) + this.getTargetMovementRange(unit, target)) <= 0;
    }

    /**
     * Checks whether the target is unseen or beyond the weapon and movement range.
     *
     * @param unit   the attacking unit
     * @param target the target
     * @return {@code true} if the target is unseen or beyond maximum weapon and movement range, {@code false} otherwise
     */
    public boolean isOutOfMaximumRange(@NotNull Unit unit, @NotNull Unit target) {
        if (!this.canSeeTarget(unit, target)) {
            return true;
        }

        int weapon = this.getTargetWeapon(unit, target);

        if (weapon == ScrWeaponIds.NONE) {
            return false;
        }

        // The distance comparison uses an unsigned range.
        return Integer.compareUnsigned(this.getDistance(unit, target),
                                       this.getWeaponMaximumRange(unit, weapon) + this.getTargetMovementRange(unit, target)) > 0;
    }

    /**
     * Returns the minimum range of the acquisition search: that of the ground weapon of the unit or its subunit, the smaller of both weapons compared
     * unsigned, or {@code 0} without a weapon.
     *
     * @param unit the acquiring unit
     * @return the minimum range in pixels, a DWORD that may hold any value an EUD map stored
     * @throws NullPointerException if the world was built without game data
     */
    public int getAcquisitionMinimumRange(@NotNull Unit unit) {
        ScrWeaponTypeView weapons = this.game.requireGameData().getWeaponTypes();
        int groundWeapon = this.getUnitOrSubunitGroundWeapon(unit);
        int airWeapon = this.getUnitOrSubunitAirWeapon(unit);

        if (groundWeapon != ScrWeaponIds.NONE) {
            int minimum = weapons.getMinimumRange(groundWeapon);

            if (airWeapon != ScrWeaponIds.NONE) {
                int airMinimum = weapons.getMinimumRange(airWeapon);

                if (Integer.compareUnsigned(minimum, airMinimum) >= 0) {
                    minimum = airMinimum;
                }
            }

            return minimum;
        }

        return airWeapon != ScrWeaponIds.NONE ? weapons.getMinimumRange(airWeapon) : 0;
    }

    /**
     * Applies the distance tests of the acquisition candidate test: a nonzero minimum range rejects a target within it, and the target must be within
     * the maximum distance, both compared unsigned.
     *
     * @param unit            the acquiring unit
     * @param target          the candidate
     * @param minimumDistance the minimum range
     * @param maximumDistance the acquisition distance
     * @return {@code true} if the candidate's distance is acceptable, {@code false} otherwise
     */
    public boolean isWithinAcquisitionDistance(@NotNull Unit unit, @NotNull Unit target, int minimumDistance, int maximumDistance) {
        if (minimumDistance != 0 && this.isInRange(unit, target, minimumDistance)) {
            return false;
        }

        return this.isInRange(unit, target, maximumDistance);
    }

    /**
     * Checks whether the direction to a position is within the weapon's attack angle; a Lurker instead turns to the position at once and passes.
     *
     * @param unit      the attacking unit
     * @param positionX the target horizontal coordinate
     * @param positionY the target vertical coordinate
     * @param weapon    the weapon
     * @return {@code true} if the direction to the position is within the weapon's attack angle or the unit is a Lurker, {@code false} otherwise
     * @throws IllegalStateException if the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public boolean isInAttackAngle(@NotNull Unit unit, int positionX, int positionY, int weapon) {
        Sprite sprite = unit.requireSprite();
        int direction = FlingyMotionUtils.calculateDirection(positionX - sprite.getX(), positionY - sprite.getY());

        if (this.game.getBaseType(unit) == ScrUnitIds.ZERG_LURKER) {
            unit.setHeading(direction);

            return true;
        }

        return Math.abs((byte) (direction - unit.getHeading())) <= this.game.requireGameData().getWeaponTypes().getAttackAngle(weapon);
    }

    /**
     * Checks whether a non-building unit's inner bounding box contains a Dark Swarm.
     *
     * @param unit the unit
     * @return {@code true} if the unit is covered by a Dark Swarm, {@code false} otherwise
     * @throws IllegalStateException if the unit is not a building, no unit accounting rules out a Dark Swarm, and the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    boolean isUnderDarkSwarm(@NotNull Unit unit) {
        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        int type = unit.getUnitId();

        if ((unitTypes.getFlags(type) & UnitTypeFlags.BUILDING) != 0) {
            return false;
        }

        UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();

        if (accounting != null && accounting.getCompletedTypeCount(ScrUnitIds.SPELL_DARK_SWARM, PlayerTable.NEUTRAL_PLAYER_ID) == 0) {
            return false;
        }

        Sprite sprite = unit.requireSprite();

        for (Unit candidate : this.findUnits(sprite.getX() - unitTypes.getDimensionLeft(type), sprite.getY() - unitTypes.getDimensionUp(type),
                                             sprite.getX() + unitTypes.getDimensionRight(type), sprite.getY() + unitTypes.getDimensionDown(type))) {
            if (candidate.getUnitId() == ScrUnitIds.SPELL_DARK_SWARM) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks whether a unit sees a target unit or, without one, the tile at its order target position.
     * <p>
     * A target unit must be detected and visible to the unit's owner ({@link #canSee}). Without a target unit the tile under the order target
     * position WORDs ({@link GameMap#getTileFlagsAtPosition}) must have a clear "not visible" bit for the owner in its low byte; an owner past the
     * eighth player tests a bit above that byte and always sees the tile.
     *
     * @param unit   the observing unit
     * @param target the target unit, or {@code null} for the order target position
     * @return {@code true} if the unit sees the target unit or the order target position, {@code false} otherwise
     */
    public boolean canSeeTarget(@NotNull Unit unit, @Nullable Unit target) {
        if (target != null) {
            return canSee(unit, target);
        }

        int flags = this.game.getMap().getTileFlagsAtPosition(unit.getOrderTargetX(), unit.getOrderTargetY());

        return (~(flags & MapTileFlags.NOT_VISIBLE) & 1 << unit.getOwnerIndex()) != 0;
    }

    /**
     * Checks the "not visible" bit of a player in the whole flag DWORD of the tile under a position WORD pair
     * ({@link GameMap#getTileFlagsAtPosition}); for a player past the eighth that bit is a "not explored" bit.
     *
     * @param owner     the player
     * @param positionX the horizontal position WORD in pixels
     * @param positionY the vertical position WORD in pixels
     * @return {@code true} if the player's bit of the tile is clear, {@code false} otherwise
     * @throws IllegalStateException if expanded terrain flags are unavailable
     */
    public boolean isPositionVisible(int owner, int positionX, int positionY) {
        return (this.game.getMap().getTileFlagsAtPosition(positionX, positionY) & 1 << owner) == 0;
    }

    /**
     * Checks target status and type against the weapon target flags.
     *
     * @param weapon the weapon
     * @param target the candidate target
     * @return {@code true} if the weapon's target flags accept the target, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    public boolean canWeaponTarget(int weapon, @NotNull Unit target) {
        if ((target.getStatus() & UnitStatusFlags.INVINCIBLE) != 0) {
            return false;
        }

        int flags = this.game.requireGameData().getWeaponTypes().getTargetFlags(weapon);

        if ((flags & ((target.getStatus() & UnitStatusFlags.FLYING) != 0 ? WeaponTargetFlags.AIR : WeaponTargetFlags.GROUND)) == 0) {
            return false;
        }

        int abilities = this.game.requireGameData().getUnitTypes().getFlags(target.getUnitId());
        boolean mechanical = (abilities & UnitTypeFlags.MECHANICAL) != 0;
        boolean organic = (abilities & UnitTypeFlags.ORGANIC) != 0;

        if ((flags & WeaponTargetFlags.MECHANICAL) != 0 && !mechanical) {
            return false;
        }

        if ((flags & WeaponTargetFlags.ORGANIC) != 0 && !organic) {
            return false;
        }

        if ((flags & WeaponTargetFlags.NON_BUILDING) != 0 && (abilities & UnitTypeFlags.BUILDING) != 0) {
            return false;
        }

        if ((flags & WeaponTargetFlags.NON_ROBOTIC) != 0 && (abilities & UnitTypeFlags.ROBOTIC) != 0) {
            return false;
        }

        return (flags & WeaponTargetFlags.ORGANIC_OR_MECHANICAL) == 0 || mechanical || organic;
    }


    /**
     * Finds the nearest visible enemy in the best priority class within the acquisition range.
     * <p>
     * Only the first sixteen candidates of a priority class compete, in unit finder order; the first nearest candidate wins ties. When the computer
     * phase exists, computer-owned units delegate to {@link ComputerCombat#findAcquireTarget}. The simulation plugins decide a found target
     * ({@link ScrSimulationPlugins#decideAcquiredTarget}).
     *
     * @param unit the acquiring unit
     * @return the selected target, or {@code null}
     * @throws IllegalStateException         if the unit does not delegate to the computer combat, and it or a competing candidate has no sprite
     * @throws UnsupportedOperationException if the unit does not delegate to the computer combat and the game has no unit finder
     */
    public @Nullable Unit findAcquireTarget(@NotNull Unit unit) {
        ComputerPlayerPhase phase = this.game.getComputerPlayerPhase();
        Player owner = unit.getOwnerIndex() < PlayerTable.PLAYABLE_PLAYER_SLOTS ? this.game.getPlayerTable().getPlayer(unit.getOwnerIndex()) : null;

        if (phase != null && owner != null && owner.getType() == PlayerTypes.COMPUTER) {
            return this.game.getComputerCombat().findAcquireTarget(unit);
        }

        int maximumDistance = this.getAcquisitionDistance(unit);
        int minimumDistance = this.getAcquisitionMinimumRange(unit);
        UnitSpatialIndex spatialIndex = this.game.getUnitSystem().requireUnitSpatialIndex();
        int count = queryPriorityCandidates(spatialIndex, unit, maximumDistance);
        Sprite sprite = unit.requireSprite();
        int bestPriority = PRIORITY_COUNT;
        int bestCount = 0;
        int bestDistance = 0;
        Unit best = null;

        for (int index = 0; index < count; index++) {
            Unit candidate = spatialIndex.getPlacementCandidate(index);
            int priority = this.getCandidatePriority(unit, candidate, minimumDistance, maximumDistance);

            if (priority < 0 || priority > bestPriority || priority == bestPriority && bestCount == PRIORITY_CAPACITY) {
                continue;
            }

            Sprite candidateSprite = candidate.requireSprite();
            int distance = RegionPathSearch.calculateDistance(sprite.getX(), candidateSprite.getX(), sprite.getY(), candidateSprite.getY());

            if (priority < bestPriority) {
                bestPriority = priority;
                bestCount    = 1;
                best         = candidate;
                bestDistance = distance;
            } else {
                bestCount++;

                if (distance < bestDistance) {
                    best         = candidate;
                    bestDistance = distance;
                }
            }
        }

        ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

        return simulation != null ? simulation.decideAcquiredTarget(unit, best) : best;
    }

    /**
     * Selects a random visible enemy from the best priority class within the acquisition range.
     * <p>
     * Consumes random source {@code 19} only when the class holds more than one of its first sixteen candidates. The simulation plugins decide a
     * found target ({@link ScrSimulationPlugins#decideAcquiredTarget}).
     *
     * @param unit the acquiring unit
     * @return the selected target, or {@code null}
     * @throws IllegalStateException         if the second pass does not find the chosen candidate
     * @throws UnsupportedOperationException if the game has no unit finder
     */
    public @Nullable Unit findAcquireRandomTarget(@NotNull Unit unit) {
        int maximumDistance = this.getAcquisitionDistance(unit);
        UnitSpatialIndex spatialIndex = this.game.getUnitSystem().requireUnitSpatialIndex();
        int count = queryPriorityCandidates(spatialIndex, unit, maximumDistance);
        int bestPriority = PRIORITY_COUNT;
        int bestCount = 0;

        for (int index = 0; index < count; index++) {
            int priority = this.getRandomCandidatePriority(unit, spatialIndex.getPlacementCandidate(index), maximumDistance);

            if (priority < 0 || priority > bestPriority) {
                continue;
            }

            if (priority < bestPriority) {
                bestPriority = priority;
                bestCount    = 1;
            } else if (bestCount < PRIORITY_CAPACITY) {
                bestCount++;
            }
        }

        if (bestCount == 0) {
            return null;
        }

        int selected = bestCount == 1 ? 0 : Integer.remainderUnsigned(this.game.getRandom().next(ACQUIRE_RANDOM_SOURCE), bestCount);

        for (int index = 0; index < count; index++) {
            Unit candidate = spatialIndex.getPlacementCandidate(index);

            if (this.getRandomCandidatePriority(unit, candidate, maximumDistance) == bestPriority && selected-- == 0) {
                ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

                return simulation != null ? simulation.decideAcquiredTarget(unit, candidate) : candidate;
            }
        }

        throw Exceptions.newIllegalStateException("Random acquisition lost its selected candidate.");
    }

    private int getAcquisitionDistance(@NotNull Unit unit) {
        int acquisitionRange = this.game.getUnitOrders().getTargetAcquisitionRange(unit);

        if ((unit.getStatus() & UnitStatusFlags.IN_BUNKER) != 0) {
            acquisitionRange += BUNKER_ACQUISITION_BONUS;
        }

        return acquisitionRange * TerrainTileset.TILE_SIZE;
    }

    /**
     * Applies the candidate test of the random acquisition: after the distance and attack-angle tests it skips a target that another computer unit
     * claimed (only for a unit of a computer owner).
     *
     * @param unit            the acquiring unit
     * @param target          the candidate
     * @param maximumDistance the acquisition distance
     * @return the attack priority of the candidate, or {@code -1} if the candidate is rejected
     */
    private int getRandomCandidatePriority(@NotNull Unit unit, @NotNull Unit target, int maximumDistance) {
        int priority = this.getCandidatePriority(unit, target, 0, maximumDistance);
        ComputerPlayerPhase phase = this.game.getComputerPlayerPhase();

        if (priority >= 0 && phase != null && this.game.getComputerCombat().isClaimedElsewhere(unit, target)) {
            return -1;
        }

        return priority;
    }

    /**
     * Returns the attack priority of an acquisition candidate, rejecting the unit itself and a target that is not a visible, attackable enemy
     * within the acquisition distance or, for an attacker that cannot turn, within the attack angle of its ground weapon, the none weapon included.
     *
     * @param unit            the acquiring unit
     * @param target          the candidate
     * @param minimumDistance the acquisition minimum range
     * @param maximumDistance the acquisition distance
     * @return the attack priority of the candidate, or {@code -1} if the candidate is rejected
     * @throws IllegalStateException if the accepted target of an attacker that cannot turn has no sprite
     */
    private int getCandidatePriority(@NotNull Unit unit, @NotNull Unit target, int minimumDistance, int maximumDistance) {
        if (target == unit || !this.isEnemy(unit, target) || !isVisible(unit, target) || !this.canAttackTarget(unit, target)) {
            return -1;
        }

        if (!this.isWithinAcquisitionDistance(unit, target, minimumDistance, maximumDistance)) {
            return -1;
        }

        Unit attackingUnit = this.getAttackingUnit(unit);

        if ((attackingUnit.getStatus() & UnitStatusFlags.CAN_TURN) == 0) {
            Sprite targetSprite = target.requireSprite();

            if (!this.isInAttackAngle(attackingUnit, targetSprite.getX(), targetSprite.getY(), this.getGroundWeapon(attackingUnit))) {
                return -1;
            }
        }

        return this.getAttackPriority(unit, target);
    }

    /**
     * Finds the first unit of the given type whose placement box contains the position, searching {@code 96} pixels around it.
     *
     * @param unitType  the requested unit type, or {@link ScrUnitIds#NONE} for none
     * @param positionX the horizontal pixel coordinate
     * @param positionY the vertical pixel coordinate
     * @return the matching unit, or {@code null}
     * @throws IllegalStateException         if a candidate of the requested type has no sprite
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if a unit type is requested and the game has no unit finder
     */
    public @Nullable Unit findUnitOfTypeAt(int unitType, int positionX, int positionY) {
        if (unitType == ScrUnitIds.NONE) {
            return null;
        }

        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        UnitSpatialIndex spatialIndex = this.game.getUnitSystem().requireUnitSpatialIndex();
        int count = spatialIndex.collectPlacementCandidates(positionX - TYPE_SEARCH_RADIUS, positionY - TYPE_SEARCH_RADIUS,
                                                            positionX + TYPE_SEARCH_RADIUS, positionY + TYPE_SEARCH_RADIUS);
        int width = unitTypes.getPlacementWidth(unitType);
        int height = unitTypes.getPlacementHeight(unitType);

        for (int index = 0; index < count; index++) {
            Unit candidate = spatialIndex.getPlacementCandidate(index);

            if (candidate.getUnitId() != unitType) {
                continue;
            }

            Sprite sprite = candidate.requireSprite();

            if (Integer.compareUnsigned(sprite.getX() + width / PLACEMENT_CENTER_DIVISOR - positionX, width) >= 0) {
                continue;
            }

            if (Integer.compareUnsigned(sprite.getY() + height / PLACEMENT_CENTER_DIVISOR - positionY, height) >= 0) {
                continue;
            }

            return candidate;
        }

        return null;
    }

    /**
     * Walks ordered unit-finder edges outwards in the order left, right, up, down, shrinking the area to the best distance, so the first candidate
     * reached at the smallest distance wins.
     *
     * @param unit      the searching unit
     * @param left      the inclusive left bound
     * @param upper     the inclusive upper bound
     * @param right     the exclusive right bound
     * @param lower     the exclusive lower bound
     * @param predicate the candidate test, evaluated before distance comparison
     * @return the nearest accepted unit, or {@code null}
     * @throws UnsupportedOperationException if the game has no unit finder
     */
    public @Nullable Unit findNearestUnit(@NotNull Unit unit, int left, int upper, int right, int lower, @NotNull Predicate<Unit> predicate) {
        return this.game.getUnitSystem().requireUnitSpatialIndex().findNearest(unit, left, upper, right, lower, predicate);
    }

    /**
     * Collects the units of the expanded rectangle query ({@link UnitSpatialIndex#findAllInRectangle}) into a new array, so that callers may run
     * nested unit finder queries while visiting them.
     *
     * @param left  the left pixel boundary
     * @param upper the upper pixel boundary
     * @param right the right pixel boundary
     * @param lower the lower pixel boundary
     * @return the units in unit finder order
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the game has no unit finder or the unit pool is not initialized
     */
    @NotNull Unit @NotNull [] findUnits(int left, int upper, int right, int lower) {
        int[] found = this.game.getUnitSystem().requireUnitSpatialIndex().findAllInRectangle(this.game.requireGameData().getUnitTypes(),
                                                                                             left, upper, right, lower);
        Unit[] units = new Unit[found.length];

        for (int index = 0; index < found.length; index++) {
            units[index] = this.game.getUnitSystem().requireUnitPool().getUnit(found[index]);
        }

        return units;
    }
}
