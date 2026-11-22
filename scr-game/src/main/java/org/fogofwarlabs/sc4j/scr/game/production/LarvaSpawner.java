package org.fogofwarlabs.sc4j.scr.game.production;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnKey;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnReset;
import org.fogofwarlabs.sc4j.engine.entity.column.IntColumn;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepField;
import org.fogofwarlabs.sc4j.scr.game.frame.GameExecutionScope;
import org.fogofwarlabs.sc4j.scr.game.map.StartingUnitLoader;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitCreationContext;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Searches starting Larva positions, creates Larvae and runs Larva and Hatchery spawning and creep orders.
 * <p>
 * The Hatchery state of the native unit union is registered as unit pool columns ({@link #COLUMNS}): the Larva spawn
 * countdown, the upper, right and lower Larva side counts and the creep timer. The columns keep their values when a slot
 * is reused, as the union fields do; unit creation and the unit reset methods set them.
 */
public final class LarvaSpawner {
    /**
     * The Larva spawn countdown BYTE ({@link Unit#getLarvaSpawnCountdown()}).
     */
    public static final @NotNull ColumnKey<IntColumn> SPAWN_COUNTDOWN_COLUMN = ColumnKey.ofInt("larvaSpawnCountdown", 0, ColumnReset.KEEP);
    /**
     * The upper Larva side count WORD ({@link Unit#getSecondLarvaSideCount()}).
     */
    public static final @NotNull ColumnKey<IntColumn> SECOND_SIDE_COUNT_COLUMN = ColumnKey.ofInt("secondLarvaSideCount", 0, ColumnReset.KEEP);
    /**
     * The right Larva side count WORD ({@link Unit#getThirdLarvaSideCount()}).
     */
    public static final @NotNull ColumnKey<IntColumn> THIRD_SIDE_COUNT_COLUMN = ColumnKey.ofInt("thirdLarvaSideCount", 0, ColumnReset.KEEP);
    /**
     * The lower Larva side count WORD ({@link Unit#getFourthLarvaSideCount()}).
     */
    public static final @NotNull ColumnKey<IntColumn> FOURTH_SIDE_COUNT_COLUMN = ColumnKey.ofInt("fourthLarvaSideCount", 0, ColumnReset.KEEP);
    /**
     * The creep timer BYTE ({@link Unit#getCreepTimer()}).
     */
    public static final @NotNull ColumnKey<IntColumn> CREEP_TIMER_COLUMN = ColumnKey.ofInt("creepTimer", 0, ColumnReset.KEEP);
    /**
     * The unit pool columns of the Hatchery state, in registration order.
     */
    public static final @NotNull List<@NotNull ColumnKey<?>> COLUMNS = List.of(
            SPAWN_COUNTDOWN_COLUMN,
            SECOND_SIDE_COUNT_COLUMN, THIRD_SIDE_COUNT_COLUMN, FOURTH_SIDE_COUNT_COLUMN,
            CREEP_TIMER_COLUMN
    );

    /**
     * The order in which the position search tries the sides of the parent a Larva stays on. A side is also the Larva order state:
     * {@link Unit#LARVA_SIDE_LEFT}, {@link Unit#LARVA_SIDE_UPPER}, {@link Unit#LARVA_SIDE_RIGHT} or {@link Unit#LARVA_SIDE_LOWER}.
     */
    private static final int @NotNull [] SIDES = {Unit.LARVA_SIDE_LOWER, Unit.LARVA_SIDE_LEFT, Unit.LARVA_SIDE_RIGHT, Unit.LARVA_SIDE_UPPER};

    /**
     * The position search phases (the center point of each side, then its fallback points) and their count.
     */
    private static final int CENTER_PHASE = 0;
    private static final int FALLBACK_PHASE = 1;
    private static final int SEARCH_PHASE_COUNT = 2;

    /**
     * The fallback points per side, on both sides of the side's center point.
     */
    private static final int FALLBACK_POINT_COUNT = 2;
    /**
     * The starting best side count, above every Larva count of a side.
     */
    private static final int INITIAL_BEST_SIDE_COUNT = 101;
    /**
     * The distance of a new Larva from the parent's edge.
     */
    private static final int SIDE_MARGIN = 10;
    /**
     * The step to the second point that must also be free, outward from the parent.
     */
    private static final int SIDE_STEP = 22;

    /**
     * The random source and the random bits that choose the direction of a Larva's wander step.
     */
    private static final int WANDER_RANDOM_SOURCE = 20;
    private static final int WANDER_HORIZONTAL_BIT = 0x8;
    private static final int WANDER_VERTICAL_BIT = 0x80;
    /**
     * The length of a Larva's wander step on each axis.
     */
    private static final int WANDER_STEP = 10;
    /**
     * The distance within which a Larva counts as near its parent.
     */
    private static final int PARENT_DISTANCE = 10;
    /**
     * The divisor of a parent's side length at the fallback center.
     */
    private static final int SIDE_CENTER_DIVISOR = 2;
    /**
     * The unsigned WORD mask of side coordinates and map bounds.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * The creep countdown a building reloads when it spreads creep; it spreads again on the first update that finds the countdown at {@code 0}.
     */
    private static final int CREEP_SPREAD_INTERVAL = 15;
    /**
     * The Larva count near a Hatchery that stops spawning.
     */
    private static final int MAXIMUM_LARVAE = 3;
    /**
     * The Larva countdown a Hatchery reloads when an update finds it at {@code 0} and spawns a Larva, and the countdown it reloads instead when no
     * position is free; the next spawn comes on the first update that finds the countdown at {@code 0} again.
     */
    private static final int LARVA_SPAWN_INTERVAL = 37;
    private static final int LARVA_SPAWN_RETRY = 3;
    /**
     * The largest owner player type a BYTE holds.
     */
    private static final int MAXIMUM_OWNER_TYPE = 0xFF;

    private final @NotNull Game game;

    /**
     * Creates a {@link LarvaSpawner} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public LarvaSpawner(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Finds the position of a new Larva around its parent, including the {@link #FALLBACK_POINT_COUNT} fallback points per side.
     *
     * @param parent the parent building
     * @param result {@link UnitPositioning#POSITION_COORDINATES} DWORD coordinates, unchanged when no position succeeds
     * @return {@code true} if a position was found, {@code false} otherwise
     * @throws IllegalArgumentException      if the result array is shorter than {@link UnitPositioning#POSITION_COORDINATES} coordinates
     * @throws UnsupportedOperationException if the parent's sprite is missing
     * @throws NullPointerException          if the world was built without game data
     */
    public boolean findPosition(@NotNull Unit parent, int @NotNull [] result) {
        if (result.length < UnitPositioning.POSITION_COORDINATES) {
            throw Exceptions.newIllegalArgumentException("Native larva position requires two output coordinates.");
        }

        Sprite sprite = parent.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Native larva placement requires parent sprite coordinates.");
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int parentType = parent.getUnitId();
        int horizontal = (short) sprite.getX();
        int vertical = (short) sprite.getY();
        int left = (short) (horizontal - units.getDimensionLeft(parentType));
        int upper = (short) (vertical - units.getDimensionUp(parentType));
        int right = (short) (horizontal + units.getDimensionRight(parentType));
        int lower = (short) (vertical + units.getDimensionDown(parentType));
        int halfWidth = (short) (units.getDimensionLeft(parentType) + units.getDimensionRight(parentType) + 1) / SIDE_CENTER_DIVISOR;
        int halfHeight = (short) (units.getDimensionUp(parentType) + units.getDimensionDown(parentType) + 1) / SIDE_CENTER_DIVISOR;
        int bestCount = INITIAL_BEST_SIDE_COUNT;
        boolean found = false;

        for (int phase = 0; phase < SEARCH_PHASE_COUNT; phase++) {
            for (int side : SIDES) {
                int count = parent.getLarvaSideCount(side);

                if (count >= bestCount && !(phase == FALLBACK_PHASE && side == Unit.LARVA_SIDE_LOWER)) {
                    continue;
                }

                int horizontalStep = side == Unit.LARVA_SIDE_LEFT ? -SIDE_STEP : side == Unit.LARVA_SIDE_RIGHT ? SIDE_STEP : 0;
                int verticalStep = side == Unit.LARVA_SIDE_UPPER ? -SIDE_STEP : side == Unit.LARVA_SIDE_LOWER ? SIDE_STEP : 0;
                int pointX = side == Unit.LARVA_SIDE_LEFT ? left - SIDE_MARGIN : side == Unit.LARVA_SIDE_RIGHT ? right + SIDE_MARGIN : horizontal;
                int pointY = side == Unit.LARVA_SIDE_UPPER ? upper - SIDE_MARGIN : side == Unit.LARVA_SIDE_LOWER ? lower + SIDE_MARGIN : vertical;
                int halfExtent = side == Unit.LARVA_SIDE_LEFT || side == Unit.LARVA_SIDE_RIGHT ? halfHeight : halfWidth;
                int attempts = phase == CENTER_PHASE ? 1 : FALLBACK_POINT_COUNT;

                for (int attempt = 0; attempt < attempts; attempt++) {
                    int offset = phase == CENTER_PHASE ? 0 : attempt == 0 ? -halfExtent : halfExtent;
                    int candidateX = pointX + (side == Unit.LARVA_SIDE_UPPER || side == Unit.LARVA_SIDE_LOWER ? offset : 0);
                    int candidateY = pointY + (side == Unit.LARVA_SIDE_LEFT || side == Unit.LARVA_SIDE_RIGHT ? offset : 0);

                    if (this.isPositionAvailable(candidateX, candidateY, horizontalStep, verticalStep)) {
                        result[UnitPositioning.X_INDEX] = candidateX;
                        result[UnitPositioning.Y_INDEX] = candidateY;

                        bestCount = count;
                        found     = true;

                        break;
                    }
                }
            }

            if (found) {
                return true;
            }
        }

        return false;
    }

    private boolean isPositionAvailable(int horizontal, int vertical, int horizontalStep, int verticalStep) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        GameMap map = this.game.getMap();
        int left = units.getDimensionLeft(ScrUnitIds.ZERG_LARVA);
        int upper = units.getDimensionUp(ScrUnitIds.ZERG_LARVA);
        int right = units.getDimensionRight(ScrUnitIds.ZERG_LARVA);
        int lower = units.getDimensionDown(ScrUnitIds.ZERG_LARVA);

        if ((short) UnitPositioning.clampCoordinate(horizontal, left, right, map.getWidth() * TerrainTileset.TILE_SIZE, 0) != horizontal ||
            (short) UnitPositioning.clampCoordinate(vertical,
                                                    upper, lower,
                                                    map.getHeight() * TerrainTileset.TILE_SIZE,
                                                    UnitPositioning.MAP_BOTTOM_MARGIN) != vertical) {
            return false;
        }

        if (!map.hasTileFlagsAtPosition(horizontal, vertical, MapTileFlags.HAS_CREEP, 0)) {
            return false;
        }

        int nextHorizontal = horizontal + horizontalStep;
        int nextVertical = vertical + verticalStep;

        if ((short) UnitPositioning.clampCoordinate(nextHorizontal, left, right, map.getWidth() * TerrainTileset.TILE_SIZE, 0) != nextHorizontal ||
            (short) UnitPositioning.clampCoordinate(nextVertical,
                                                    upper, lower,
                                                    map.getHeight() * TerrainTileset.TILE_SIZE,
                                                    UnitPositioning.MAP_BOTTOM_MARGIN) != nextVertical) {
            return false;
        }

        return map.hasTileFlagsAtPosition(nextHorizontal, nextVertical, MapTileFlags.WALKABLE | MapTileFlags.HAS_CREEP, MapTileFlags.OCCUPIED);
    }

    /**
     * Creates a starting Larva around its parent in a free unit pool slot.
     *
     * @param creation         the unit creation inputs
     * @param parent           the persistent parent unit
     * @param ownerPlayerType  the owner player type BYTE
     * @param instructionLimit the creation, completion and order budget
     * @return the created Larva, or {@code null} when no position or unit allocation succeeds
     */
    public @Nullable Unit create(@NotNull UnitCreationContext creation, @NotNull Unit parent, int ownerPlayerType, int instructionLimit) {
        return this.create(creation, parent, ownerPlayerType, instructionLimit, null);
    }

    /**
     * Creates a starting Larva around its parent in a free unit pool slot and records the random state captured before creation.
     *
     * @param creation         the unit creation inputs
     * @param parent           the persistent parent unit
     * @param ownerPlayerType  the owner player type BYTE
     * @param instructionLimit the creation, completion and order budget
     * @param creations        the list receiving the creation record, or {@code null} for none
     * @return the created Larva, or {@code null} when no position or unit allocation succeeds
     */
    public @Nullable Unit create(@NotNull UnitCreationContext creation,
                                 @NotNull Unit parent,
                                 int ownerPlayerType,
                                 int instructionLimit,
                                 @Nullable List<StartingUnitLoader.Creation> creations) {
        int[] position = new int[UnitPositioning.POSITION_COORDINATES];

        if (!this.findPosition(parent, position)) {
            return null;
        }

        int randomStateBefore = this.game.getRandom().getState();
        int randomCallsBefore = this.game.getRandom().getTotalRandomCounts();
        Unit larva = this.createAtPosition(creation, parent, position, ownerPlayerType, instructionLimit);

        if (creations != null) {
            creations.add(new StartingUnitLoader.Creation(this.game,
                                                          ScrUnitIds.ZERG_LARVA,
                                                          parent.getOwnerIndex(),
                                                          position[UnitPositioning.X_INDEX], position[UnitPositioning.Y_INDEX],
                                                          larva,
                                                          randomStateBefore, randomCallsBefore));
        }

        return larva;
    }

    /**
     * Runs the Larva primary order: the Larva follows its parent, dies off creep away from its parent and wanders randomly on its parent's side.
     * <p>
     * The facing call's pickup result is deliberately ignored.
     *
     * @param unit             the persistent Larva
     * @param instructionLimit the order budget
     * @throws UnsupportedOperationException if the unit's sprite, or when needed its movement target's or its parent's sprite, is missing, or the
     *                                       order state is not a side
     * @throws NullPointerException          if the world was built without game data
     */
    public void updatePrimary(@NotNull Unit unit, int instructionLimit) {
        Unit parent = unit.getParentUnit();

        if (parent != null && !this.isNearParent(unit, parent)) {
            this.game.getUnitTargetMovement().moveToTargetReset(unit, parent);
        }

        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Native larva orders require retained sprite coordinates.");
        }

        Unit target = unit.getMovementTargetUnit();
        int horizontal = (short) sprite.getX();
        int vertical = (short) sprite.getY();

        if ((short) unit.getMovementDestinationX() != horizontal || (short) unit.getMovementDestinationY() != vertical) {
            if (target == null) {
                return;
            }

            Sprite targetSprite = target.getSpriteOrNull();

            if (targetSprite == null) {
                throw Exceptions.newUnsupportedOperationException("Native larva movement target requires its retained sprite.");
            }

            if ((short) unit.getMovementDestinationX() == (short) targetSprite.getX() &&
                (short) unit.getMovementDestinationY() == (short) targetSprite.getY()) {
                return;
            }

            if (this.game.getMap().hasTileFlagsAtPosition(unit.getMovementDestinationX(), unit.getMovementDestinationY(),
                                                          MapTileFlags.HAS_CREEP, 0)) {
                return;
            }
        }

        if ((parent == null || target != parent) && !this.game.getMap().hasTileFlagsAtPosition(horizontal, vertical, MapTileFlags.HAS_CREEP, 0)) {
            // Drop carried items, remove queued orders and activate Die.
            this.game.getUnitLifecycle().kill(unit, instructionLimit);

            return;
        }

        int random = this.game.getRandom().next(WANDER_RANDOM_SOURCE);
        int candidateX = horizontal + ((random & WANDER_HORIZONTAL_BIT) != 0 ? WANDER_STEP : -WANDER_STEP);
        int candidateY = vertical + ((random & WANDER_VERTICAL_BIT) != 0 ? WANDER_STEP : -WANDER_STEP);

        if ((candidateX & WORD_MASK) >= (this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE & WORD_MASK) ||
            (candidateY & WORD_MASK) >= (this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE & WORD_MASK) ||
            !this.game.getMap().hasTileFlagsAtPosition(candidateX, candidateY, MapTileFlags.HAS_CREEP, 0)) {
            return;
        }

        if (!this.isOnParentSide(unit, candidateX, candidateY)) {
            if (this.isOnParentSide(unit, horizontal, vertical)) {
                return;
            }

            if (parent != null) {
                Sprite parentSprite = parent.getSpriteOrNull();

                if (parentSprite == null) {
                    throw Exceptions.newUnsupportedOperationException("Native larva parent requires retained sprite coordinates.");
                }

                ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
                int parentType = parent.getUnitId();
                int parentX = (short) parentSprite.getX();
                int parentY = (short) parentSprite.getY();

                switch (unit.getPrimaryOrderState()) {
                    case Unit.LARVA_SIDE_LEFT -> {
                        candidateX = (short) (parentX - units.getDimensionLeft(parentType)) - SIDE_MARGIN;
                        candidateY = parentY;
                    }
                    case Unit.LARVA_SIDE_UPPER -> {
                        candidateX = parentX;
                        candidateY = (short) (parentY - units.getDimensionUp(parentType)) - SIDE_MARGIN;
                    }
                    case Unit.LARVA_SIDE_RIGHT -> {
                        candidateX = (short) (parentX + units.getDimensionRight(parentType)) + SIDE_MARGIN;
                        candidateY = parentY;
                    }
                    case Unit.LARVA_SIDE_LOWER -> {
                        candidateX = parentX;
                        candidateY = (short) (parentY + units.getDimensionDown(parentType)) + SIDE_MARGIN;
                    }
                    default -> throw Exceptions.newUnsupportedOperationException("Larva order received an invalid side state.");
                }
            }
        }

        this.game.getEngineGame().getMovement().setDestination(unit, candidateX, candidateY);

        if ((short) unit.getDirectionTargetX() != candidateX || (short) unit.getDirectionTargetY() != candidateY) {
            unit.setDirectionTargetX(candidateX);
            unit.setDirectionTargetY(candidateY);
        }
    }

    private boolean isNearParent(@NotNull Unit unit, @NotNull Unit parent) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if ((units.getFlags(unit.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
            unit = unit.getSubunit();

            if (unit == null) {
                throw Exceptions.newUnsupportedOperationException("Parent distance requires the unit subunit.");
            }
        }

        Sprite sprite = unit.getSpriteOrNull();
        Sprite parentSprite = parent.getSpriteOrNull();

        if (sprite == null || parentSprite == null) {
            throw Exceptions.newUnsupportedOperationException("Native parent distance requires both sprite coordinates.");
        }

        int unitType = unit.getUnitId();
        int parentType = parent.getUnitId();
        int left = (short) (sprite.getX() - units.getDimensionLeft(unitType));
        int upper = (short) (sprite.getY() - units.getDimensionUp(unitType));
        int right = (short) (sprite.getX() + units.getDimensionRight(unitType) + 1);
        int lower = (short) (sprite.getY() + units.getDimensionDown(unitType) + 1);
        int parentLeft = (short) (parentSprite.getX() - units.getDimensionLeft(parentType));
        int parentUpper = (short) (parentSprite.getY() - units.getDimensionUp(parentType));
        int parentRight = (short) (parentSprite.getX() + units.getDimensionRight(parentType) + 1);
        int parentLower = (short) (parentSprite.getY() + units.getDimensionDown(parentType) + 1);
        int differenceX = left - parentRight;

        if (differenceX < 0) {
            differenceX = Math.max(parentLeft - right, 0);
        }

        int differenceY = upper - parentLower;

        if (differenceY < 0) {
            differenceY = Math.max(parentUpper - lower, 0);
        }

        return Integer.compareUnsigned(RegionPathSearch.calculateDistance(0, differenceX, 0, differenceY), PARENT_DISTANCE) <= 0;
    }

    private boolean isOnParentSide(@NotNull Unit unit, int horizontal, int vertical) {
        Unit parent = unit.getParentUnit();

        if (parent == null) {
            return true;
        }

        Sprite sprite = parent.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Native larva side test requires parent sprite coordinates.");
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int unitType = parent.getUnitId();

        return switch (unit.getPrimaryOrderState()) {
            case Unit.LARVA_SIDE_LEFT -> Integer.compareUnsigned(horizontal, (sprite.getX() - units.getDimensionLeft(unitType)) & WORD_MASK) <= 0;
            case Unit.LARVA_SIDE_UPPER -> Integer.compareUnsigned(vertical, (sprite.getY() - units.getDimensionUp(unitType)) & WORD_MASK) <= 0;
            case Unit.LARVA_SIDE_RIGHT -> Integer.compareUnsigned(horizontal, (sprite.getX() + units.getDimensionRight(unitType)) & WORD_MASK) >= 0;
            case Unit.LARVA_SIDE_LOWER -> Integer.compareUnsigned(vertical, (sprite.getY() + units.getDimensionDown(unitType)) & WORD_MASK) >= 0;
            default -> throw Exceptions.newUnsupportedOperationException("Larva side test received an invalid side state.");
        };
    }

    /**
     * Executes the Hatchery creep-growth secondary order.
     *
     * @param unit             the persistent building
     * @param instructionLimit the creation, completion and order budget
     */
    public void updateHatchery(@NotNull Unit unit, int instructionLimit) {
        this.updateHatchery(unit, -1, instructionLimit);
    }

    /**
     * Executes Hatchery growth with the caller's established owner type: a Hatchery, Lair or Hive spawns Larvae, and when the creep countdown is
     * {@code 0} the building reloads it to {@link #CREEP_SPREAD_INTERVAL} and spreads creep, otherwise the countdown decreases; when the spread
     * returns {@code 0} the secondary order becomes {@link ScrOrderIds#SPAWNING_LARVA} (Hatchery, Lair, Hive) or {@link ScrOrderIds#NOTHING}.
     *
     * @param unit             the persistent building
     * @param ownerPlayerType  the owner type BYTE
     * @param instructionLimit the creation, completion and order budget
     * @throws UnsupportedOperationException if creep storage or the unit's sprite is missing
     */
    public void updateHatchery(@NotNull Unit unit, int ownerPlayerType, int instructionLimit) {
        int unitType = unit.getUnitId();
        boolean hatchery = unitType == ScrUnitIds.ZERG_HATCHERY || unitType == ScrUnitIds.ZERG_LAIR ||
                           unitType == ScrUnitIds.ZERG_HIVE;

        if (hatchery) {
            this.updateIdleHatchery(unit, ownerPlayerType, instructionLimit);
        }

        int countdown = unit.getCreepTimer();

        if (countdown != 0) {
            unit.setCreepTimer(countdown - 1);

            return;
        }

        unit.setCreepTimer(CREEP_SPREAD_INTERVAL);

        CreepField creep = this.game.getCreepField();
        Sprite sprite = unit.getSpriteOrNull();

        if (creep == null || sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Native Hatchery growth requires creep storage and sprite coordinates.");
        }

        if (creep.spread(this.game, ScrUnitIds.ZERG_HIVE, (short) sprite.getX(), (short) sprite.getY()) == CreepField.SPREAD_NONE) {
            int order = hatchery ? ScrOrderIds.SPAWNING_LARVA : ScrOrderIds.NOTHING;

            ScrOrderSystem.setSecondaryOrder(unit, order);
        }
    }

    /**
     * Executes guarded Larva spawning without supplying an owner type for creation.
     * <p>
     * Initial countdown and neutral-owner guards can return before validation.
     *
     * @param unit             the persistent Hatchery
     * @param instructionLimit the creation, completion and order budget
     * @throws UnsupportedOperationException if the execution scope is missing or inactive, or the creation inputs, spatial index or owner type BYTE
     *                                       is missing
     */
    public void updateIdleHatchery(@NotNull Unit unit, int instructionLimit) {
        this.updateIdleHatchery(unit, -1, instructionLimit);
    }

    /**
     * Executes spawning with the retained creation inputs and active execution scope.
     * <p>
     * Initial countdown and neutral-owner guards can return before validation.
     *
     * @param unit             the persistent parent
     * @param ownerPlayerType  the owner type BYTE
     * @param instructionLimit the creation, completion and order budget
     * @throws UnsupportedOperationException if the execution scope is missing or inactive, or the creation inputs, spatial index or owner type BYTE
     *                                       is missing
     * @throws NullPointerException          if the world was built without game data
     */
    public void updateIdleHatchery(@NotNull Unit unit, int ownerPlayerType, int instructionLimit) {
        if (unit.getPrimaryOrderCountdown() != 0 || unit.getOwnerIndex() == PlayerTable.NEUTRAL_PLAYER_ID) {
            return;
        }

        GameExecutionScope executionScope = this.game.getExecutionScope();

        if (executionScope == null) {
            throw Exceptions.newUnsupportedOperationException("Hatchery spawning requires its active execution scope.");
        }

        executionScope.requireActiveScope();

        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

        if (context == null) {
            throw Exceptions.newUnsupportedOperationException("Native Hatchery spawning requires established runtime creation inputs.");
        }

        UnitSpatialIndex spatial = this.game.getUnitSystem().getUnitSpatialIndex();

        if (spatial == null) {
            throw Exceptions.newUnsupportedOperationException("Native Hatchery spawning requires its initialized spatial index.");
        }

        // Fails before any work when the world was built without game data.
        this.game.requireGameData();

        if (spatial.countLarvaeNearParent(unit) >= MAXIMUM_LARVAE) {
            return;
        }

        int countdown = unit.getLarvaSpawnCountdown();

        if (countdown != 0) {
            unit.setLarvaSpawnCountdown(countdown - 1);

            return;
        }

        unit.setLarvaSpawnCountdown(LARVA_SPAWN_INTERVAL);

        int[] position = new int[UnitPositioning.POSITION_COORDINATES];

        if (!this.findPosition(unit, position)) {
            unit.setLarvaSpawnCountdown(LARVA_SPAWN_RETRY);

            return;
        }

        if (ownerPlayerType < 0 || ownerPlayerType > MAXIMUM_OWNER_TYPE) {
            throw Exceptions.newUnsupportedOperationException("Native Larva completion requires the caller's owner type BYTE.");
        }

        this.createAtPosition(context, unit, position, ownerPlayerType, instructionLimit);
    }

    private @Nullable Unit createAtPosition(@NotNull UnitCreationContext creation,
                                            @NotNull Unit parent,
                                            int @NotNull [] position,
                                            int ownerPlayerType,
                                            int instructionLimit) {
        UnitPool pool = this.game.getUnitSystem().getUnitPool();
        UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();

        if (pool == null || accounting == null || pool.getUnit(parent.getIndex()) != parent) {
            throw Exceptions.newUnsupportedOperationException("Native larva creation requires persistent parent identity and accounting.");
        }

        Unit larva = this.game.getUnitStorageConstructor().create(creation,
                                                                  accounting,
                                                                  ScrUnitIds.ZERG_LARVA,
                                                                  position[UnitPositioning.X_INDEX], position[UnitPositioning.Y_INDEX],
                                                                  parent.getOwnerIndex(),
                                                                  pool.getCreationTag(parent.getIndex()),
                                                                  instructionLimit);

        if (larva == null) {
            return null;
        }

        this.game.getUnitCompletion().initializeBeforePlacement(creation, larva, instructionLimit);
        this.game.getUnitCompletion().completeAfterPlacement(creation, larva, ownerPlayerType, instructionLimit);
        larva.setParentUnit(parent);

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int parentType = parent.getUnitId();
        int left = (short) (parent.getPositionX() - units.getDimensionLeft(parentType));
        int upper = (short) (parent.getPositionY() - units.getDimensionUp(parentType));
        int right = (short) (parent.getPositionX() + units.getDimensionRight(parentType));
        int side = (short) larva.getPositionX() < left
                   ? Unit.LARVA_SIDE_LEFT
                   : (short) larva.getPositionY() < upper
                     ? Unit.LARVA_SIDE_UPPER
                     : (short) larva.getPositionX() <= right ? Unit.LARVA_SIDE_LOWER : Unit.LARVA_SIDE_RIGHT;

        larva.setPrimaryOrderState(side);
        this.game.getComputerUnits().notifyCreated(parent, larva, instructionLimit);

        return larva;
    }
}
