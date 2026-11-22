package org.fogofwarlabs.sc4j.scr.game.path;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitCollisionRules;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/**
 * Collects the local obstacles of one step of a {@link LocalPathSearch}: the terrain and unit edges in the four directions around the current
 * position, the candidate positions of the cardinal and diagonal scans with their {@link PathDirections} BYTEs, and the visited rectangles the scans
 * cover.
 * <p>
 * The local search rectangle extends up to {@code 64} pixels from the current position and shrinks to the nearest edge of each direction. Every edge
 * coordinate is moved by the unit dimension facing it, so the scans treat the unit as a point.
 */
public final class PathObstacleCollector {
    /**
     * The contour directions: the edge lists of each direction, and the unit size (dimension) facing it.
     */
    private static final int DIRECTION_UP = 0;
    private static final int DIRECTION_RIGHT = 1;
    private static final int DIRECTION_DOWN = 2;
    private static final int DIRECTION_LEFT = 3;
    private static final int DIRECTION_COUNT = 4;
    /**
     * The sides of the local search rectangle {@link #bounds}; direction {@code d} scans toward side {@code (d + 1) & 3}.
     */
    private static final int BOUND_LEFT = 0;
    private static final int BOUND_TOP = 1;
    private static final int BOUND_RIGHT = 2;
    private static final int BOUND_BOTTOM = 3;
    /**
     * The half size of the local search rectangle around the current position.
     */
    private static final int SEARCH_RADIUS = 64;
    /**
     * The bit of a contour direction that is set for the horizontal directions (right and left).
     */
    private static final int HORIZONTAL_DIRECTION_BIT = 1;
    /**
     * The edge searches compare coordinates times this, so that a bias of one orders a coordinate equal to an edge before or after that edge.
     */
    private static final int BOUNDARY_COMPARE_SCALE = 2;
    /**
     * The binary searches shift the sum of their lower and upper bounds right by this to get the middle index (half the sum).
     */
    private static final int MIDPOINT_SHIFT = 1;

    /**
     * Collector storage: {@code 128} edges per direction, {@code 32} candidates, {@code 40} visited rectangles.
     */
    private static final int BOUNDARY_CAPACITY = 128;
    private static final int CANDIDATE_CAPACITY = 32;
    private static final int SEGMENT_CAPACITY = 40;
    /**
     * The goal entries a goal append writes past the candidates, without a capacity check: they fill the saved copy of the terrain edge lists that
     * follows the candidates (four list QWORDs and one QWORD of the four list counts), up to the visited rectangle count.
     */
    private static final int SAVED_TERRAIN_LIST_ENTRIES = 5;

    /**
     * An edge record QWORD: the coordinate WORD, the first and last WORDs of its extent, the direction BYTE and a flag BYTE. Flag bits {@code 0-1}
     * and {@code 2-3} select the dimension added to the first and last end; bits {@code 4} and {@code 5} mark an end that continues into a
     * perpendicular edge.
     */
    private static final int WORD_BITS = 16;
    private static final int FIRST_SHIFT = WORD_BITS;
    private static final int LAST_SHIFT = 2 * WORD_BITS;
    private static final int DIRECTION_SHIFT = 3 * WORD_BITS;
    private static final int FLAGS_SHIFT = 56;
    private static final int LAST_END_SHIFT = 2;
    private static final int LAST_DIMENSION_SHIFT = FLAGS_SHIFT + LAST_END_SHIFT;
    private static final int END_MASK = 3;
    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;
    private static final long LONG_WORD_MASK = 0xFFFFL;
    private static final long FIRST_FIELD = LONG_WORD_MASK << FIRST_SHIFT;
    private static final long LAST_FIELD = LONG_WORD_MASK << LAST_SHIFT;
    private static final long FIRST_END_FLAG = 0x10L << FLAGS_SHIFT;
    private static final long LAST_END_FLAG = 0x20L << FLAGS_SHIFT;
    /**
     * The flag BYTEs of unit edges: horizontal edges end on dimensions {@code 2} and {@code 0}, vertical ones on {@code 1} and {@code 3}; both ends
     * continue.
     */
    private static final int UNIT_HORIZONTAL_EDGE_FLAGS = 0x32;
    private static final int UNIT_VERTICAL_EDGE_FLAGS = 0x3D;
    /**
     * The components of an edge record read by {@link #getExpandedCoordinate(long, int, boolean)}.
     */
    private static final int COMPONENT_COORDINATE = 0;
    private static final int COMPONENT_FIRST = 1;
    private static final int COMPONENT_LAST = 2;

    /**
     * The WORD shifts of a packed rectangle (the exclusion rectangle, a visited rectangle) after its left WORD.
     */
    static final int RECTANGLE_TOP_SHIFT = WORD_BITS;
    static final int RECTANGLE_RIGHT_SHIFT = 2 * WORD_BITS;
    static final int RECTANGLE_BOTTOM_SHIFT = 3 * WORD_BITS;

    /**
     * A candidate QWORD: the packed position DWORD, the direction BYTE and the terminal BYTE.
     */
    private static final long POSITION_MASK = 0xFFFFFFFFL;
    private static final int CANDIDATE_DIRECTIONS_SHIFT = 32;
    private static final int CANDIDATE_TERMINAL_SHIFT = 40;
    private static final long CANDIDATE_TERMINAL = 1L << CANDIDATE_TERMINAL_SHIFT;

    /**
     * The multiplier of the diagnostic state hash.
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * Checks whether a candidate unit blocks the local path of a moving unit.
     * <p>
     * A moving candidate blocks only when it is the selected unit or when moving units are included; the four door types never block; otherwise the
     * movement blocking rules of {@link UnitCollisionRules#isBlockingMovement(Unit, Unit)} decide.
     *
     * @param rules             the collision rules of the game
     * @param units             the unit type table
     * @param candidate         the potential obstacle
     * @param incoming          the moving unit
     * @param includeMoving     a value other than {@code 0} if every moving unit blocks
     * @param selectedUnitIndex the pool index of the moving unit that still blocks, or {@code 0}
     * @return {@code true} if the candidate participates in local collision, {@code false} otherwise
     * @throws IllegalStateException if the candidate has no sprite
     */
    public static boolean isBlockingPath(@NotNull UnitCollisionRules rules, @NotNull ScrUnitTypeView units,
                                         @NotNull Unit candidate, @NotNull Unit incoming,
                                         int includeMoving, int selectedUnitIndex) {
        if ((candidate.getMovementDestinationX() != (candidate.requireSprite().getX() & WORD_MASK) ||
             candidate.getMovementDestinationY() != (candidate.requireSprite().getY() & WORD_MASK)) &&
            candidate.getIndex() != selectedUnitIndex && includeMoving == 0) {
            return false;
        }

        if (units.isPathTransparent(candidate.getUnitId())) {
            return false;
        }

        return rules.isBlockingMovement(candidate, incoming);
    }


    private final long @NotNull [] @NotNull [] boundaries;
    private final int @NotNull [] boundaryCounts;
    private final int @NotNull [] dimensions;
    private final int @NotNull [] bounds;

    private @Nullable Game game;
    private @Nullable Unit unit;
    private int targetUnitIndex;
    private int destinationX;
    private int destinationY;
    private long exclusionRectangle;

    private final long @NotNull [] candidates;
    private final long @NotNull [] segments;
    private final int @NotNull [] nearestBoundaries;
    private int candidateCount;
    private int segmentCount;
    private boolean savedTerrainListsOverwritten;

    private int currentX;
    private int currentY;
    private int directions;
    private boolean terminalFound;

    private boolean movementContextKnown;
    private boolean includeMoving;
    private int selectedUnitIndex;

    /**
     * Creates a {@link PathObstacleCollector} instance with empty storage and unknown movement collision controls.
     */
    public PathObstacleCollector() {
        this.boundaries     = new long[DIRECTION_COUNT][BOUNDARY_CAPACITY];
        this.boundaryCounts = new int[DIRECTION_COUNT];
        this.dimensions     = new int[DIRECTION_COUNT];
        this.bounds         = new int[DIRECTION_COUNT];

        this.game               = null;
        this.unit               = null;
        this.targetUnitIndex    = 0;
        this.destinationX       = 0;
        this.destinationY       = 0;
        this.exclusionRectangle = 0L;

        this.candidates                   = new long[CANDIDATE_CAPACITY + SAVED_TERRAIN_LIST_ENTRIES];
        this.segments                     = new long[SEGMENT_CAPACITY];
        this.nearestBoundaries            = new int[DIRECTION_COUNT];
        this.candidateCount               = 0;
        this.segmentCount                 = 0;
        this.savedTerrainListsOverwritten = false;

        this.currentX      = 0;
        this.currentY      = 0;
        this.directions    = 0;
        this.terminalFound = false;

        this.movementContextKnown = false;
        this.includeMoving        = false;
        this.selectedUnitIndex    = 0;
    }

    /**
     * @return the number of collected candidates
     */
    public int getCandidateCount() {
        return this.candidateCount;
    }

    /**
     * @param index the candidate index
     * @return the packed position DWORD of the candidate
     * @throws IndexOutOfBoundsException if the index is not below the candidate count
     */
    public int getCandidatePosition(int index) {
        return (int) this.candidates[Objects.checkIndex(index, this.candidateCount)];
    }

    /**
     * @param index the candidate index
     * @return the direction BYTE of the candidate
     * @throws IndexOutOfBoundsException if the index is not below the candidate count
     */
    public int getCandidateDirections(int index) {
        return (int) (this.candidates[Objects.checkIndex(index, this.candidateCount)] >>> CANDIDATE_DIRECTIONS_SHIFT) & PathDirections.ALL;
    }

    /**
     * @param index the candidate index
     * @return the terminal BYTE of the candidate: a value other than {@code 0} for a position on the target unit's edge or at the destination
     * @throws IndexOutOfBoundsException if the index is not below the candidate count
     */
    public int getCandidateTerminal(int index) {
        return (int) (this.candidates[Objects.checkIndex(index, this.candidateCount)] >>> CANDIDATE_TERMINAL_SHIFT) & BYTE_MASK;
    }

    /**
     * @return the number of collected visited rectangles
     */
    public int getSegmentCount() {
        return this.segmentCount;
    }

    /**
     * @param index the visited rectangle index
     * @return the visited rectangle QWORD: the left, top, right and bottom WORDs
     * @throws IndexOutOfBoundsException if the index is not below the visited rectangle count
     */
    public long getSegment(int index) {
        return this.segments[Objects.checkIndex(index, this.segmentCount)];
    }

    /**
     * @param direction the contour direction
     * @return the number of collected edge records of the direction
     * @throws IndexOutOfBoundsException if the direction is not a contour direction
     */
    public int getBoundaryCount(int direction) {
        return this.boundaryCounts[Objects.checkIndex(direction, DIRECTION_COUNT)];
    }

    /**
     * @param direction the contour direction
     * @param index     the record index
     * @return the collected edge record QWORD
     * @throws IndexOutOfBoundsException if the direction or the index is outside the collected records
     */
    public long getBoundary(int direction, int index) {
        return this.boundaries[Objects.checkIndex(direction, DIRECTION_COUNT)][Objects.checkIndex(index, this.boundaryCounts[direction])];
    }

    private int getExpandedCoordinate(long boundary, int component, boolean outer) {
        int flags = (int) (boundary >>> FLAGS_SHIFT);
        int direction = component == COMPONENT_COORDINATE ? (int) (boundary >>> DIRECTION_SHIFT) & BYTE_MASK
                                                          : component == COMPONENT_FIRST ? flags & END_MASK : (flags >>> LAST_END_SHIFT) & END_MASK;
        int change = this.dimensions[direction];

        if (outer) {
            change = (short) (change + (direction == DIRECTION_UP || direction == DIRECTION_LEFT ? 1 : -1));
        }

        return (short) ((short) (boundary >>> (component * WORD_BITS)) + change);
    }


    /**
     * Sets the movement collision controls of the following collections.
     *
     * @param includeMoving     whether every moving unit blocks paths
     * @param selectedUnitIndex the pool index of the moving unit that still blocks paths, or {@code 0}
     */
    public void initializeMovementContext(boolean includeMoving, int selectedUnitIndex) {
        this.movementContextKnown = true;
        this.includeMoving        = includeMoving;
        this.selectedUnitIndex    = selectedUnitIndex;
    }

    /**
     * Sets the inputs of the following collections: the moving unit and its dimensions, the target unit, the destination and the exclusion rectangle
     * around the target unit. A new search also saves the terrain edge lists again, replacing a copy that a goal append overwrote.
     *
     * @param game               the game with the terrain and the units
     * @param unit               the moving unit
     * @param targetUnitIndex    the pool index of the target unit, or {@code 0}
     * @param destinationX       the destination X WORD
     * @param destinationY       the destination Y WORD
     * @param exclusionRectangle the left, top, right and bottom WORDs of the target unit's edge
     * @throws UnsupportedOperationException if the terrain contours or the unit spatial index are missing
     * @throws NullPointerException          if the world was built without game data
     */
    public void initialize(@NotNull Game game, @NotNull Unit unit, int targetUnitIndex, int destinationX, int destinationY, long exclusionRectangle) {
        if (game.getPathSystem().getTerrainContours() == null || game.getUnitSystem().getUnitSpatialIndex() == null) {
            throw Exceptions.newUnsupportedOperationException("Native local collision requires terrain and unit contours.");
        }

        this.game               = game;
        this.unit               = unit;
        this.targetUnitIndex    = targetUnitIndex;
        this.destinationX       = destinationX & WORD_MASK;
        this.destinationY       = destinationY & WORD_MASK;
        this.exclusionRectangle = exclusionRectangle;

        this.savedTerrainListsOverwritten = false;

        ScrUnitTypeView units = game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();

        this.dimensions[DIRECTION_UP]    = (short) units.getDimensionUp(unitType);
        this.dimensions[DIRECTION_RIGHT] = (short) -units.getDimensionRight(unitType);
        this.dimensions[DIRECTION_DOWN]  = (short) -units.getDimensionDown(unitType);
        this.dimensions[DIRECTION_LEFT]  = (short) units.getDimensionLeft(unitType);
    }

    /**
     * Collects the terrain and unit edges around a position, then the candidates and visited rectangles of the cardinal scans and of the diagonal
     * scans of the remaining directions.
     *
     * @param positionX     the current X WORD
     * @param positionY     the current Y WORD
     * @param directionMask the direction BYTE of the position
     */
    public void collect(int positionX, int positionY, int directionMask) {
        this.collectBoundaries(positionX, positionY, directionMask);
        this.collectCardinalCandidates(positionX, positionY, directionMask);

        if ((this.directions & PathDirections.NORTHEAST) != 0) {
            this.appendNortheast();
        }

        if ((this.directions & PathDirections.SOUTHEAST) != 0) {
            this.appendSoutheast();
        }

        if ((this.directions & PathDirections.SOUTHWEST) != 0) {
            this.appendSouthwest();
        }

        if ((this.directions & PathDirections.NORTHWEST) != 0) {
            this.appendNorthwest();
        }
    }

    /**
     * Sets the local search rectangle around a position (closed on the sides of the directions not in the mask) and collects the terrain and unit
     * edges of the four directions that reach into it, in coordinate order.
     * <p>
     * Every collection first restores the terrain edge lists from the copy saved by {@link #initialize(Game, Unit, int, int, int, long)}.
     *
     * @param positionX     the current X WORD
     * @param positionY     the current Y WORD
     * @param directionMask the direction BYTE of the position
     * @throws NullPointerException          if the collector is not initialized or the terrain contours or the unit spatial index are missing
     * @throws UnsupportedOperationException if a goal append overwrote the edge lists, a moving unit lacks collision controls or the unit pool is missing
     * @throws IllegalStateException         if the candidate has no sprite
     */
    public void collectBoundaries(int positionX, int positionY, int directionMask) {
        if (this.savedTerrainListsOverwritten) {
            throw Exceptions.newUnsupportedOperationException("Native local collision restores terrain edge lists that a goal append overwrote.");
        }

        Game game = this.game;

        if (game == null) {
            throw Exceptions.newNullPointerException("Native local collision is not initialized.");
        }

        TerrainContours terrain = game.getPathSystem().getTerrainContours();

        if (terrain == null) {
            throw Exceptions.newNullPointerException();
        }

        UnitSpatialIndex spatial = game.getUnitSystem().getUnitSpatialIndex();

        if (spatial == null) {
            throw Exceptions.newNullPointerException();
        }

        positionX = (short) positionX;
        positionY = (short) positionY;

        this.bounds[BOUND_LEFT]   = Math.max(0, (short) (positionX - SEARCH_RADIUS));
        this.bounds[BOUND_TOP]    = Math.max(0, (short) (positionY - SEARCH_RADIUS));
        this.bounds[BOUND_RIGHT]  = (short) (positionX + SEARCH_RADIUS);
        this.bounds[BOUND_BOTTOM] = (short) (positionY + SEARCH_RADIUS);

        if (this.bounds[BOUND_RIGHT] >= game.getMap().getWidth() * TerrainTileset.TILE_SIZE) {
            this.bounds[BOUND_RIGHT] = (short) (game.getMap().getWidth() * TerrainTileset.TILE_SIZE - 1);
        }

        if (this.bounds[BOUND_BOTTOM] >= game.getMap().getHeight() * TerrainTileset.TILE_SIZE) {
            this.bounds[BOUND_BOTTOM] = (short) (game.getMap().getHeight() * TerrainTileset.TILE_SIZE - 1);
        }

        if ((directionMask & (PathDirections.NORTHEAST | PathDirections.NORTHWEST | PathDirections.CARDINAL_UP)) == 0) {
            this.bounds[BOUND_TOP] = positionY;
        }

        if ((directionMask & (PathDirections.NORTHEAST | PathDirections.SOUTHEAST | PathDirections.CARDINAL_RIGHT)) == 0) {
            this.bounds[BOUND_RIGHT] = positionX;
        }

        if ((directionMask & (PathDirections.SOUTHEAST | PathDirections.SOUTHWEST | PathDirections.CARDINAL_DOWN)) == 0) {
            this.bounds[BOUND_BOTTOM] = positionY;
        }

        if ((directionMask & (PathDirections.SOUTHWEST | PathDirections.NORTHWEST | PathDirections.CARDINAL_LEFT)) == 0) {
            this.bounds[BOUND_LEFT] = positionX;
        }

        Arrays.fill(this.boundaryCounts, 0);

        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            boolean horizontal = (direction & HORIZONTAL_DIRECTION_BIT) != 0;
            boolean backward = direction == DIRECTION_UP || direction == DIRECTION_LEFT;
            int current = horizontal ? positionX : positionY;
            int limit = this.bounds[(direction + 1) & (DIRECTION_COUNT - 1)];
            int start = (backward ? limit : current) - this.dimensions[direction] + (backward ? -1 : 1);
            int end = (short) ((backward ? current : limit) - this.dimensions[direction] + (backward ? -1 : 1));
            int lower = 0;
            int upper = terrain.getBoundaryCount(direction);
            int doubled = (short) (start * BOUNDARY_COMPARE_SCALE - 1);

            while (lower < upper) {
                int middle = (lower + upper) >>> MIDPOINT_SHIFT;

                if (doubled >= (short) ((short) terrain.getBoundary(direction, middle) * BOUNDARY_COMPARE_SCALE)) {
                    lower = middle + 1;
                } else {
                    upper = middle;
                }
            }

            int cursor = 0;

            for (int index = lower; index < terrain.getBoundaryCount(direction); index++) {
                long boundary = terrain.getBoundary(direction, index);

                if ((short) boundary > end) {
                    break;
                }

                int flags = (int) (boundary >>> FLAGS_SHIFT);

                if ((short) (boundary >>> FIRST_SHIFT) + this.dimensions[flags & END_MASK] > this.bounds[horizontal ? BOUND_BOTTOM : BOUND_RIGHT] ||
                    (short) (boundary >>> LAST_SHIFT) + this.dimensions[(flags >>> LAST_END_SHIFT) & END_MASK] <
                    this.bounds[horizontal ? BOUND_TOP : BOUND_LEFT]) {
                    continue;
                }

                cursor = this.insertBoundary(direction, boundary, cursor);
            }
        }

        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            boolean horizontal = (direction & HORIZONTAL_DIRECTION_BIT) != 0;
            boolean backward = direction == DIRECTION_UP || direction == DIRECTION_LEFT;
            int current = horizontal ? positionX : positionY;
            int limit = this.bounds[(direction + 1) & (DIRECTION_COUNT - 1)];
            int start = (backward ? limit : current) - this.dimensions[direction] - (backward ? 1 : 0);
            int end = (short) ((backward ? current : limit) - this.dimensions[direction] + (backward ? 0 : 1));
            int lower = 0;
            int upper = spatial.getCount();
            int threshold = start * BOUNDARY_COMPARE_SCALE - 1;

            while (lower < upper) {
                int middle = (lower + upper) >>> MIDPOINT_SHIFT;

                if (threshold >= spatial.getCoordinate(horizontal, middle) * BOUNDARY_COMPARE_SCALE) {
                    lower = middle + 1;
                } else {
                    upper = middle;
                }
            }

            for (int index = lower; index < spatial.getCount(); index++) {
                int coordinate = spatial.getCoordinate(horizontal, index);

                if (backward ? coordinate >= end : coordinate > end) {
                    break;
                }

                Unit candidate = game.getUnitSystem().requireUnitPool().getUnit(spatial.getUnitIndex(horizontal, index));
                int candidateEdge = switch (direction) {
                    case DIRECTION_UP -> candidate.getMaximumVerticalEdgeIndex();
                    case DIRECTION_RIGHT -> candidate.getMinimumHorizontalEdgeIndex();
                    case DIRECTION_DOWN -> candidate.getMinimumVerticalEdgeIndex();
                    default -> candidate.getMaximumHorizontalEdgeIndex();
                };

                if (candidateEdge != index) {
                    continue;
                }

                int first = spatial.getCoordinate(!horizontal, candidate.getMinimumEdgeIndex(!horizontal));
                int last = spatial.getCoordinate(!horizontal, candidate.getMaximumEdgeIndex(!horizontal));
                int flags = horizontal ? UNIT_HORIZONTAL_EDGE_FLAGS : UNIT_VERTICAL_EDGE_FLAGS;

                if ((short) (first + this.dimensions[flags & END_MASK]) > this.bounds[horizontal ? BOUND_BOTTOM : BOUND_RIGHT] ||
                    (short) (last + this.dimensions[(flags >>> LAST_END_SHIFT) & END_MASK]) < this.bounds[horizontal ? BOUND_TOP : BOUND_LEFT]) {
                    continue;
                }

                if (candidate.getIndex() != this.targetUnitIndex) {
                    if (!this.movementContextKnown &&
                        (candidate.getMovementDestinationX() != (candidate.requireSprite().getX() & WORD_MASK) ||
                         candidate.getMovementDestinationY() != (candidate.requireSprite().getY() & WORD_MASK))) {
                        String message = "Native local collision requires controls 10A1880/10A1888 for a moving candidate.";

                        throw Exceptions.newUnsupportedOperationException(message);
                    }

                    Unit incoming = this.unit;

                    if (incoming == null) {
                        throw Exceptions.newNullPointerException();
                    }

                    if (!isBlockingPath(game.getHooks().getCollisionRules(), game.requireGameData().getUnitTypes(),
                                        candidate, incoming,
                                        this.includeMoving ? 1 : 0, this.selectedUnitIndex)) {
                        continue;
                    }
                }

                long boundary = (coordinate & LONG_WORD_MASK) | (first & LONG_WORD_MASK) << FIRST_SHIFT | (last & LONG_WORD_MASK) << LAST_SHIFT |
                                (long) direction << DIRECTION_SHIFT | (long) flags << FLAGS_SHIFT;

                // Unit edges sharing a coordinate arrive in unit-finder order and may merge with an earlier record.
                this.insertBoundary(direction, boundary, 0);
            }
        }
    }

    /**
     * Scans the four cardinal directions from a position up to the nearest edge or the local search rectangle, shrinks the rectangle to the edges
     * found, adds a candidate at the end of every open cardinal direction and keeps the diagonal directions that the diagonal scans then follow.
     *
     * @param positionX     the current X WORD
     * @param positionY     the current Y WORD
     * @param directionMask the direction BYTE of the position
     */
    public void collectCardinalCandidates(int positionX, int positionY, int directionMask) {
        this.currentX       = (short) positionX;
        this.currentY       = (short) positionY;
        this.candidateCount = 0;
        this.segmentCount   = 0;
        this.terminalFound  = false;

        int directions = directionMask & PathDirections.ALL;

        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            boolean horizontal = (direction & HORIZONTAL_DIRECTION_BIT) != 0;
            boolean backward = direction == DIRECTION_UP || direction == DIRECTION_LEFT;
            int current = horizontal ? this.currentX : this.currentY;
            int other = horizontal ? this.currentY : this.currentX;
            int limit = this.bounds[(direction + 1) & (DIRECTION_COUNT - 1)];
            int found = this.scan(direction, current, other, other, (short) (limit + (backward ? -1 : 1)));

            this.nearestBoundaries[direction] = found;

            if (found >= 0) {
                this.bounds[(direction + 1) & (DIRECTION_COUNT - 1)] = (short) ((short) this.boundaries[direction][found] +
                                                                                this.dimensions[direction] + (backward ? 1 : -1));
            }
        }

        if (this.bounds[BOUND_TOP] == this.currentY && this.bounds[BOUND_RIGHT] == this.currentX) {
            directions &= ~PathDirections.NORTHEAST;
        }

        if (this.bounds[BOUND_BOTTOM] == this.currentY) {
            if (this.bounds[BOUND_RIGHT] == this.currentX) {
                directions &= ~PathDirections.SOUTHEAST;
            }

            if (this.bounds[BOUND_LEFT] == this.currentX) {
                directions &= ~PathDirections.SOUTHWEST;
            }
        }

        if (this.bounds[BOUND_TOP] == this.currentY && this.bounds[BOUND_LEFT] == this.currentX) {
            directions &= ~PathDirections.NORTHWEST;
        }

        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            boolean horizontal = (direction & HORIZONTAL_DIRECTION_BIT) != 0;
            int current = horizontal ? this.currentX : this.currentY;
            int limit = this.bounds[(direction + 1) & (DIRECTION_COUNT - 1)];
            int bit = PathDirections.CARDINAL_UP << direction;

            if (limit == current) {
                directions &= ~bit;
            }

            if ((directions & bit) == 0) {
                continue;
            }

            directions |= switch (direction) {
                case DIRECTION_UP -> PathDirections.NORTHEAST | PathDirections.NORTHWEST;
                case DIRECTION_RIGHT -> PathDirections.NORTHEAST | PathDirections.SOUTHEAST;
                case DIRECTION_DOWN -> PathDirections.SOUTHEAST | PathDirections.SOUTHWEST;
                default -> PathDirections.SOUTHWEST | PathDirections.NORTHWEST;
            };

            int candidateDirections = switch (direction) {
                case DIRECTION_UP -> PathDirections.SOUTHEAST | PathDirections.SOUTHWEST | PathDirections.CARDINAL_RIGHT |
                                     PathDirections.CARDINAL_LEFT;
                case DIRECTION_RIGHT -> PathDirections.SOUTHWEST | PathDirections.NORTHWEST | PathDirections.CARDINAL_UP |
                                        PathDirections.CARDINAL_DOWN;
                case DIRECTION_DOWN -> PathDirections.NORTHEAST | PathDirections.NORTHWEST | PathDirections.CARDINAL_RIGHT |
                                       PathDirections.CARDINAL_LEFT;
                default -> PathDirections.NORTHEAST | PathDirections.SOUTHEAST | PathDirections.CARDINAL_UP | PathDirections.CARDINAL_DOWN;
            };

            if (this.nearestBoundaries[direction] < 0) {
                candidateDirections = switch (direction) {
                    case DIRECTION_UP -> ~PathDirections.CARDINAL_DOWN & PathDirections.ALL;
                    case DIRECTION_RIGHT -> ~PathDirections.CARDINAL_LEFT & PathDirections.ALL;
                    case DIRECTION_DOWN -> ~PathDirections.CARDINAL_UP & PathDirections.ALL;
                    default -> ~PathDirections.CARDINAL_RIGHT & PathDirections.ALL;
                };
            }

            this.addCandidate(horizontal ? limit : this.currentX, horizontal ? this.currentY : limit, candidateDirections);
        }

        this.directions = directions;
    }

    /**
     * Adds the collection state to a diagnostic hash.
     *
     * @param hash the previous hash
     * @return the updated hash
     */
    public int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + this.targetUnitIndex;
        hash = HASH_MULTIPLIER * hash + this.destinationX;
        hash = HASH_MULTIPLIER * hash + this.destinationY;
        hash = HASH_MULTIPLIER * hash + Long.hashCode(this.exclusionRectangle);
        hash = HASH_MULTIPLIER * hash + this.currentX;
        hash = HASH_MULTIPLIER * hash + this.currentY;
        hash = HASH_MULTIPLIER * hash + this.directions;
        hash = HASH_MULTIPLIER * hash + (this.terminalFound ? 1 : 0);
        hash = HASH_MULTIPLIER * hash + (this.movementContextKnown ? 1 : 0);
        hash = HASH_MULTIPLIER * hash + (this.includeMoving ? 1 : 0);
        hash = HASH_MULTIPLIER * hash + this.selectedUnitIndex;

        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            hash = HASH_MULTIPLIER * hash + this.dimensions[direction];
            hash = HASH_MULTIPLIER * hash + this.bounds[direction];
            hash = HASH_MULTIPLIER * hash + this.nearestBoundaries[direction];
            hash = HASH_MULTIPLIER * hash + this.boundaryCounts[direction];

            for (int index = 0; index < this.boundaryCounts[direction]; index++) {
                hash = HASH_MULTIPLIER * hash + Long.hashCode(this.boundaries[direction][index]);
            }
        }

        hash = HASH_MULTIPLIER * hash + this.candidateCount;
        hash = HASH_MULTIPLIER * hash + this.segmentCount;
        hash = HASH_MULTIPLIER * hash + (this.savedTerrainListsOverwritten ? 1 : 0);

        for (int index = 0; index < this.candidateCount; index++) {
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.candidates[index]);
        }

        for (int index = 0; index < this.segmentCount; index++) {
            hash = HASH_MULTIPLIER * hash + Long.hashCode(this.segments[index]);
        }

        return hash;
    }

    /**
     * Inserts an edge record into the coordinate-ordered records of a direction, starting at a cursor; a record with the same coordinate whose extent
     * overlaps or touches the new one is widened instead.
     *
     * @param direction the contour direction
     * @param boundary  the edge record
     * @param cursor    the record index the search starts at
     * @return the index of the inserted or widened record
     * @throws UnsupportedOperationException if the records of the direction are full
     */
    int insertBoundary(int direction, long boundary, int cursor) {
        long[] records = this.boundaries[direction];
        int count = this.boundaryCounts[direction];
        int first = (short) (boundary >>> FIRST_SHIFT);
        int last = (short) (boundary >>> LAST_SHIFT);
        int flags = (int) (boundary >>> FLAGS_SHIFT);

        while (cursor < count) {
            long previous = records[cursor];
            int previousFlags = (int) (previous >>> FLAGS_SHIFT);

            if ((short) previous < (short) boundary ||
                ((short) previous == (short) boundary &&
                 (short) (previous >>> LAST_SHIFT) + this.dimensions[(previousFlags >>> LAST_END_SHIFT) & END_MASK] <
                 first + this.dimensions[flags & END_MASK])) {
                cursor++;

                continue;
            }

            if ((short) previous == (short) boundary &&
                (short) (previous >>> LAST_SHIFT) + this.dimensions[(previousFlags >>> LAST_END_SHIFT) & END_MASK] + 1 >=
                first + this.dimensions[flags & END_MASK] &&
                (short) (previous >>> FIRST_SHIFT) + this.dimensions[previousFlags & END_MASK] - 1 <=
                last + this.dimensions[(flags >>> LAST_END_SHIFT) & END_MASK]) {
                if ((short) (previous >>> FIRST_SHIFT) > first) {
                    previous = (previous & ~FIRST_FIELD) | (first & LONG_WORD_MASK) << FIRST_SHIFT;
                }

                if ((short) (previous >>> LAST_SHIFT) < last) {
                    previous = (previous & ~LAST_FIELD) | (last & LONG_WORD_MASK) << LAST_SHIFT;
                }

                records[cursor] = previous;

                return cursor;
            }

            break;
        }

        if (count == records.length) {
            throw Exceptions.newUnsupportedOperationException("Native local contour insertion crossed its 128-record storage.");
        }

        System.arraycopy(records, cursor, records, cursor + 1, count - cursor);

        records[cursor]                = boundary;
        this.boundaryCounts[direction] = count + 1;

        return cursor;
    }

    /**
     * Finds the nearest collected edge of a direction from a coordinate whose expanded extent overlaps a cross range, up to a limit.
     *
     * @param direction  the contour direction
     * @param coordinate the coordinate the scan starts at
     * @param first      the first cross coordinate of the range
     * @param last       the last cross coordinate of the range
     * @param limit      the coordinate past which the scan stops
     * @return the record index of the edge, or {@code -1} if none is found
     */
    int scan(int direction, int coordinate, int first, int last, int limit) {
        boolean backward = direction == DIRECTION_UP || direction == DIRECTION_LEFT;
        int lower = 0;
        int upper = this.boundaryCounts[direction];
        int threshold = (short) ((coordinate - this.dimensions[direction]) * BOUNDARY_COMPARE_SCALE + (backward ? 1 : -1));

        while (lower < upper) {
            int middle = (lower + upper) >>> MIDPOINT_SHIFT;

            if (threshold >= (short) ((short) this.boundaries[direction][middle] * BOUNDARY_COMPARE_SCALE)) {
                lower = middle + 1;
            } else {
                upper = middle;
            }
        }

        int index = backward ? lower - 1 : lower;

        limit = (short) (limit - this.dimensions[direction]);

        while (index >= 0 && index < this.boundaryCounts[direction]) {
            long boundary = this.boundaries[direction][index];

            if (backward ? (short) boundary < limit : (short) boundary > limit) {
                return -1;
            }

            int flags = (int) (boundary >>> FLAGS_SHIFT);
            int begin = (short) ((short) (boundary >>> FIRST_SHIFT) + this.dimensions[flags & END_MASK]);
            int end = (short) ((short) (boundary >>> LAST_SHIFT) + this.dimensions[(flags >>> LAST_END_SHIFT) & END_MASK]);

            if (Math.max(begin, (short) first) <= Math.min(end, (short) last)) {
                return index;
            }

            index += backward ? -1 : 1;
        }

        return -1;
    }

    /**
     * Adds a candidate position with its direction BYTE, unless it is the current position or the candidates are full.
     * <p>
     * A position on the edge of the target unit's exclusion rectangle or at the destination is terminal and takes every direction; a non-terminal
     * position that is already a candidate keeps only the directions that both its direction BYTE and the new one allow (a bitwise AND).
     *
     * @param positionX     the X WORD
     * @param positionY     the Y WORD
     * @param directionMask the direction BYTE
     */
    void addCandidate(int positionX, int positionY, int directionMask) {
        positionX = (short) positionX;
        positionY = (short) positionY;

        if (positionX == this.currentX && positionY == this.currentY) {
            return;
        }

        boolean terminal = false;

        if (this.targetUnitIndex != 0) {
            int left = (short) this.exclusionRectangle;
            int upper = (short) (this.exclusionRectangle >>> RECTANGLE_TOP_SHIFT);
            int right = (short) (this.exclusionRectangle >>> RECTANGLE_RIGHT_SHIFT);
            int lower = (short) (this.exclusionRectangle >>> RECTANGLE_BOTTOM_SHIFT);

            terminal = ((positionX == left || positionX == right) && positionY >= upper && positionY <= lower) ||
                       ((positionY == upper || positionY == lower) && positionX >= left && positionX <= right);
        }

        terminal |= positionX == (short) this.destinationX && positionY == (short) this.destinationY;

        if (terminal) {
            directionMask      = PathDirections.ALL;
            this.terminalFound = true;
        }

        if (this.candidateCount >= CANDIDATE_CAPACITY) {
            return;
        }

        int packed = (positionX & WORD_MASK) | positionY << WORD_BITS;

        if (!terminal) {
            for (int index = 0; index < this.candidateCount; index++) {
                if ((int) this.candidates[index] == packed) {
                    this.candidates[index] &= ~((long) BYTE_MASK << CANDIDATE_DIRECTIONS_SHIFT) |
                                              (long) (directionMask & PathDirections.ALL) << CANDIDATE_DIRECTIONS_SHIFT;

                    return;
                }
            }
        }

        this.candidates[this.candidateCount++] = (packed & POSITION_MASK) |
                                                 (long) (directionMask & PathDirections.ALL) << CANDIDATE_DIRECTIONS_SHIFT |
                                                 (terminal ? CANDIDATE_TERMINAL : 0);
    }

    /**
     * Adds a visited rectangle between two corners; if the destination lies in it, the destination is first appended as a terminal candidate with
     * every direction.
     * <p>
     * The goal append has no capacity check: past the {@code 32} candidates it overwrites the saved terrain edge lists, while the search still reads
     * the appended entries back as candidates.
     *
     * @param firstX the X WORD of the first corner
     * @param firstY the Y WORD of the first corner
     * @param lastX  the X WORD of the opposite corner
     * @param lastY  the Y WORD of the opposite corner
     * @throws UnsupportedOperationException if a goal append reaches the visited rectangle count or the visited rectangles are full
     */
    private void visitArea(int firstX, int firstY, int lastX, int lastY) {
        int left = Math.min((short) firstX, (short) lastX);
        int right = Math.max((short) firstX, (short) lastX);
        int upper = Math.min((short) firstY, (short) lastY);
        int lower = Math.max((short) firstY, (short) lastY);

        if ((short) this.destinationX >= left && (short) this.destinationX <= right &&
            (short) this.destinationY >= upper && (short) this.destinationY <= lower) {
            if (this.candidateCount == this.candidates.length) {
                String message = "Native goal append crossed the saved terrain edge lists into the visited rectangle count.";

                throw Exceptions.newUnsupportedOperationException(message);
            }

            if (this.candidateCount >= CANDIDATE_CAPACITY) {
                this.savedTerrainListsOverwritten = true;
            }

            this.candidates[this.candidateCount++] = (this.destinationX & LONG_WORD_MASK) | (this.destinationY & LONG_WORD_MASK) << WORD_BITS |
                                                     ((long) PathDirections.ALL << CANDIDATE_DIRECTIONS_SHIFT | CANDIDATE_TERMINAL);
            this.terminalFound                     = true;
        }

        if (this.segmentCount == this.segments.length) {
            throw Exceptions.newUnsupportedOperationException("Native visited rectangle crossed local segment storage.");
        }

        this.segments[this.segmentCount++] = (left & LONG_WORD_MASK) | (upper & LONG_WORD_MASK) << RECTANGLE_TOP_SHIFT |
                                             (right & LONG_WORD_MASK) << RECTANGLE_RIGHT_SHIFT | (lower & LONG_WORD_MASK) << RECTANGLE_BOTTOM_SHIFT;
    }

    private void appendNortheast() {
        int positionX = this.currentX;
        int positionY = this.currentY;
        int right = this.bounds[BOUND_RIGHT];
        int upper = this.bounds[BOUND_TOP];

        if (positionX < right) {
            int coordinateX = right;
            int coordinateY = upper;
            int edgeY = upper;

            if (this.nearestBoundaries[DIRECTION_RIGHT] >= 0) {
                long nearest = this.boundaries[DIRECTION_RIGHT][this.nearestBoundaries[DIRECTION_RIGHT]];

                edgeY       = (short) (this.getExpandedCoordinate(nearest, COMPONENT_FIRST, false) - 1);
                coordinateY = Math.max(coordinateY, edgeY);
            }

            int directions = ~(PathDirections.SOUTHWEST | PathDirections.CARDINAL_DOWN | PathDirections.CARDINAL_LEFT) & PathDirections.ALL;
            int index = this.scan(DIRECTION_UP, (short) (positionY - 1), positionX, coordinateX, (short) (coordinateY - 1));
            long boundary;
            int boundaryCoordinate = 0;
            int boundaryFirst = 0;
            int boundaryLast = 0;

            if (index >= 0) {
                boundary           = this.boundaries[DIRECTION_UP][index];
                boundaryCoordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);
                boundaryFirst      = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);
                boundaryLast       = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);
                coordinateY        = boundaryCoordinate;

                if (coordinateX == boundaryLast) {
                    directions = (boundary & LAST_END_FLAG) != 0
                                 ? PathDirections.NORTHEAST | PathDirections.SOUTHEAST | PathDirections.CARDINAL_UP | PathDirections.CARDINAL_RIGHT
                                 : 0;
                } else if (coordinateX < boundaryLast) {
                    directions = PathDirections.SOUTHEAST | PathDirections.CARDINAL_RIGHT;
                }
            }

            if (this.nearestBoundaries[DIRECTION_RIGHT] >= 0) {
                directions &= ~PathDirections.SOUTHEAST & PathDirections.ALL;

                if (edgeY < coordinateY) {
                    directions &= ~(PathDirections.NORTHEAST | PathDirections.CARDINAL_RIGHT) & PathDirections.ALL;
                }
            }

            if (coordinateY >= positionY) {
                directions |= PathDirections.SOUTHWEST | PathDirections.CARDINAL_DOWN;
            } else {
                this.addCandidate(coordinateX, positionY, ~(PathDirections.NORTHWEST | PathDirections.CARDINAL_UP) & PathDirections.ALL);
            }

            this.addCandidate(coordinateX, coordinateY, directions);

            if (coordinateY == upper) {
                this.addCandidate(positionX, coordinateY, ~(PathDirections.SOUTHEAST | PathDirections.CARDINAL_RIGHT) & PathDirections.ALL);
            }

            if (index >= 0) {
                if (coordinateX > boundaryLast) {
                    this.addCandidate(boundaryLast, boundaryCoordinate, PathDirections.NORTHEAST | PathDirections.CARDINAL_UP);
                }

                while (true) {
                    if (boundaryFirst < positionX) {
                        right = positionX;

                        break;
                    }

                    right = boundaryFirst;

                    this.addCandidate(boundaryFirst, boundaryCoordinate, PathDirections.NORTHWEST | PathDirections.CARDINAL_UP);

                    if (--index < 0) {
                        break;
                    }

                    boundary           = this.boundaries[DIRECTION_UP][index];
                    boundaryCoordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);
                    boundaryFirst      = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);
                    boundaryLast       = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);

                    if (boundaryCoordinate != coordinateY || boundaryLast < positionX) {
                        break;
                    }

                    if (boundaryLast <= coordinateX) {
                        this.addCandidate(boundaryLast, boundaryCoordinate, PathDirections.NORTHEAST | PathDirections.CARDINAL_UP);
                    }
                }
            }

            this.visitArea(positionX, positionY, coordinateX, coordinateY);

            positionY = coordinateY;
        }

        if (positionY > upper) {
            int coordinateX = right;
            int edgeX = right;

            if (this.nearestBoundaries[DIRECTION_UP] >= 0) {
                long nearest = this.boundaries[DIRECTION_UP][this.nearestBoundaries[DIRECTION_UP]];

                edgeX       = (short) (this.getExpandedCoordinate(nearest, COMPONENT_LAST, false) + 1);
                coordinateX = Math.min(coordinateX, edgeX);
            }

            int directions = ~(PathDirections.SOUTHWEST | PathDirections.CARDINAL_DOWN | PathDirections.CARDINAL_LEFT) & PathDirections.ALL;
            int index = this.scan(DIRECTION_RIGHT, (short) (positionX + 1), upper, positionY, (short) (coordinateX + 1));
            long boundary;
            int boundaryCoordinate = 0;
            int boundaryFirst = 0;
            int boundaryLast = 0;

            if (index >= 0) {
                boundary           = this.boundaries[DIRECTION_RIGHT][index];
                boundaryCoordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);
                boundaryFirst      = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);
                boundaryLast       = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);
                coordinateX        = boundaryCoordinate;

                if (upper == boundaryFirst) {
                    directions = (boundary & FIRST_END_FLAG) != 0
                                 ? PathDirections.NORTHEAST | PathDirections.NORTHWEST | PathDirections.CARDINAL_UP | PathDirections.CARDINAL_RIGHT
                                 : 0;
                } else if (upper > boundaryFirst) {
                    directions = PathDirections.NORTHWEST | PathDirections.CARDINAL_UP;
                }
            }

            if (this.nearestBoundaries[DIRECTION_UP] >= 0) {
                directions &= ~PathDirections.NORTHWEST & PathDirections.ALL;

                if (edgeX > coordinateX) {
                    directions &= ~(PathDirections.NORTHEAST | PathDirections.CARDINAL_RIGHT) & PathDirections.ALL;
                }
            }

            if (coordinateX <= positionX) {
                directions |= PathDirections.SOUTHWEST | PathDirections.CARDINAL_LEFT;
            } else {
                this.addCandidate(positionX, upper, ~(PathDirections.SOUTHEAST | PathDirections.CARDINAL_RIGHT) & PathDirections.ALL);
            }

            this.addCandidate(coordinateX, upper, directions);

            if (coordinateX < right) {
                this.addCandidate(coordinateX, positionY, PathDirections.NORTHEAST);
            }

            if (index >= 0) {
                if (upper < boundaryFirst) {
                    this.addCandidate(boundaryCoordinate, boundaryFirst, PathDirections.NORTHEAST | PathDirections.CARDINAL_RIGHT);
                }

                while (boundaryLast <= positionY) {
                    this.addCandidate(boundaryCoordinate, boundaryLast, PathDirections.SOUTHEAST | PathDirections.CARDINAL_RIGHT);

                    if (++index >= this.boundaryCounts[DIRECTION_RIGHT]) {
                        break;
                    }

                    boundary           = this.boundaries[DIRECTION_RIGHT][index];
                    boundaryCoordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);
                    boundaryFirst      = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);
                    boundaryLast       = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);

                    if (boundaryCoordinate != coordinateX || boundaryFirst > positionY) {
                        this.addCandidate(coordinateX, positionY, PathDirections.NORTHEAST | PathDirections.CARDINAL_RIGHT);

                        break;
                    }

                    if (boundaryFirst >= upper) {
                        this.addCandidate(boundaryCoordinate, boundaryFirst, PathDirections.NORTHEAST | PathDirections.CARDINAL_RIGHT);
                    }
                }
            }

            this.visitArea(positionX, positionY, coordinateX, upper);
        }
    }

    private void appendSoutheast() {
        int positionX = this.currentX;
        int positionY = this.currentY;
        int maximumX = this.bounds[BOUND_RIGHT];
        int maximumY = this.bounds[BOUND_BOTTOM];

        if (positionX < maximumX) {
            int horizontal = maximumX;
            int vertical = maximumY;
            int nearestVertical = maximumY;
            int nearestIndex = this.nearestBoundaries[DIRECTION_RIGHT];

            if (nearestIndex >= 0) {
                long nearest = this.boundaries[DIRECTION_RIGHT][nearestIndex];

                nearestVertical = (short) ((short) (nearest >>> LAST_SHIFT) +
                                           this.dimensions[(int) (nearest >>> LAST_DIMENSION_SHIFT) & END_MASK] + 1);

                if (nearestVertical < vertical) {
                    vertical = nearestVertical;
                }
            }

            int boundaryIndex = this.scan(DIRECTION_DOWN, (short) (positionY + 1), positionX, horizontal, (short) (vertical + 1));
            long boundary = 0;
            int boundaryEnd = 0;

            if (boundaryIndex >= 0) {
                boundary = this.boundaries[DIRECTION_DOWN][boundaryIndex];

                int boundaryCoordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);
                int boundaryStart = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);

                boundaryEnd = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);
                maximumX    = boundaryStart;

                if (positionX < boundaryStart) {
                    this.addCandidate(boundaryStart, boundaryCoordinate, PathDirections.SOUTHWEST | PathDirections.CARDINAL_DOWN);
                }

                vertical = boundaryCoordinate;

                while (boundaryEnd < horizontal) {
                    this.addCandidate(boundaryEnd, boundaryCoordinate, PathDirections.SOUTHEAST | PathDirections.CARDINAL_DOWN);

                    boundaryIndex++;

                    if (boundaryIndex >= this.boundaryCounts[DIRECTION_DOWN]) {
                        break;
                    }

                    long following = this.boundaries[DIRECTION_DOWN][boundaryIndex];
                    int dimensionIndex = (int) (following >>> DIRECTION_SHIFT) & BYTE_MASK;

                    if ((short) following - 1 + this.dimensions[dimensionIndex] != vertical) {
                        break;
                    }

                    if ((short) (following >>> FIRST_SHIFT) - 1 + this.dimensions[(int) (following >>> FLAGS_SHIFT) & END_MASK] >= horizontal) {
                        break;
                    }

                    boundary           = following;
                    boundaryCoordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);
                    boundaryStart      = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);
                    boundaryEnd        = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);

                    if (boundaryStart <= horizontal) {
                        this.addCandidate(boundaryStart, boundaryCoordinate, PathDirections.SOUTHWEST | PathDirections.CARDINAL_DOWN);
                    }
                }
            }

            int directions = ~(PathDirections.NORTHWEST | PathDirections.CARDINAL_UP | PathDirections.CARDINAL_LEFT) & PathDirections.ALL;

            if (boundaryIndex >= 0) {
                if (horizontal < boundaryEnd) {
                    directions = PathDirections.NORTHEAST | PathDirections.CARDINAL_RIGHT;
                } else if (horizontal == boundaryEnd) {
                    directions = (boundary & LAST_END_FLAG) != 0
                                 ? PathDirections.NORTHEAST | PathDirections.SOUTHEAST | PathDirections.CARDINAL_RIGHT | PathDirections.CARDINAL_DOWN
                                 : 0;
                }
            }

            if (nearestIndex >= 0) {
                directions &= ~PathDirections.NORTHEAST & PathDirections.ALL;

                if (nearestVertical > vertical) {
                    directions &= ~(PathDirections.SOUTHEAST | PathDirections.CARDINAL_RIGHT) & PathDirections.ALL;
                }
            }

            if (vertical > positionY) {
                this.addCandidate(horizontal, positionY, ~(PathDirections.SOUTHWEST | PathDirections.CARDINAL_DOWN) & PathDirections.ALL);
            } else {
                directions |= PathDirections.NORTHWEST | PathDirections.CARDINAL_UP;
            }

            this.addCandidate(horizontal, vertical, directions);

            if (vertical == maximumY) {
                this.addCandidate(positionX, vertical, ~(PathDirections.NORTHEAST | PathDirections.CARDINAL_RIGHT) & PathDirections.ALL);
            }

            this.visitArea(positionX, positionY, horizontal, vertical);

            positionY = vertical;
        }

        if (positionY < maximumY) {
            int horizontal = maximumX;
            int vertical = maximumY;
            int nearestHorizontal = maximumX;
            int nearestIndex = this.nearestBoundaries[DIRECTION_DOWN];

            if (nearestIndex >= 0) {
                long nearest = this.boundaries[DIRECTION_DOWN][nearestIndex];

                nearestHorizontal = (short) ((short) (nearest >>> LAST_SHIFT) +
                                             this.dimensions[(int) (nearest >>> LAST_DIMENSION_SHIFT) & END_MASK] + 1);

                if (nearestHorizontal < horizontal) {
                    horizontal = nearestHorizontal;
                }
            }

            int boundaryIndex = this.scan(DIRECTION_RIGHT, (short) (positionX + 1), positionY, vertical, (short) (horizontal + 1));
            long boundary = 0;
            int boundaryEnd = 0;

            if (boundaryIndex >= 0) {
                boundary = this.boundaries[DIRECTION_RIGHT][boundaryIndex];

                int boundaryCoordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);
                int boundaryStart = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);

                boundaryEnd = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);

                if (boundaryCoordinate < maximumX) {
                    this.addCandidate(boundaryCoordinate, positionY, PathDirections.SOUTHEAST);
                }

                if (boundaryStart > positionY) {
                    this.addCandidate(boundaryCoordinate, boundaryStart, PathDirections.NORTHEAST | PathDirections.CARDINAL_RIGHT);
                }

                horizontal = boundaryCoordinate;

                while (boundaryEnd < vertical) {
                    this.addCandidate(boundaryCoordinate, boundaryEnd, PathDirections.SOUTHEAST | PathDirections.CARDINAL_RIGHT);

                    boundaryIndex++;

                    if (boundaryIndex >= this.boundaryCounts[DIRECTION_RIGHT]) {
                        break;
                    }

                    long following = this.boundaries[DIRECTION_RIGHT][boundaryIndex];
                    int dimensionIndex = (int) (following >>> DIRECTION_SHIFT) & BYTE_MASK;

                    if ((short) following - 1 + this.dimensions[dimensionIndex] != horizontal) {
                        break;
                    }

                    if ((short) (following >>> FIRST_SHIFT) - 1 + this.dimensions[(int) (following >>> FLAGS_SHIFT) & END_MASK] >= vertical) {
                        break;
                    }

                    boundary           = following;
                    boundaryCoordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);
                    boundaryStart      = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);
                    boundaryEnd        = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);

                    if (positionY <= boundaryStart && boundaryStart <= maximumY) {
                        this.addCandidate(boundaryCoordinate, boundaryStart, PathDirections.NORTHEAST | PathDirections.CARDINAL_RIGHT);
                    }
                }
            }

            int directions = ~(PathDirections.NORTHWEST | PathDirections.CARDINAL_UP | PathDirections.CARDINAL_LEFT) & PathDirections.ALL;

            if (boundaryIndex >= 0) {
                if (vertical < boundaryEnd) {
                    directions = PathDirections.SOUTHWEST | PathDirections.CARDINAL_DOWN;
                } else if (vertical == boundaryEnd) {
                    directions = (boundary & LAST_END_FLAG) != 0
                                 ? PathDirections.SOUTHEAST | PathDirections.SOUTHWEST | PathDirections.CARDINAL_RIGHT | PathDirections.CARDINAL_DOWN
                                 : 0;
                }
            }

            if (nearestIndex >= 0) {
                directions &= ~PathDirections.SOUTHWEST & PathDirections.ALL;

                if (nearestHorizontal > horizontal) {
                    directions &= ~(PathDirections.SOUTHEAST | PathDirections.CARDINAL_DOWN) & PathDirections.ALL;
                }
            }

            if (horizontal > positionX) {
                this.addCandidate(positionX, vertical, ~(PathDirections.NORTHEAST | PathDirections.CARDINAL_RIGHT) & PathDirections.ALL);
            } else {
                directions |= PathDirections.NORTHWEST | PathDirections.CARDINAL_LEFT;
            }

            this.addCandidate(horizontal, vertical, directions);
            this.visitArea(positionX, positionY, horizontal, vertical);
        }
    }

    private void appendSouthwest() {
        int positionX = this.currentX;
        int positionY = this.currentY;
        int minimumX = this.bounds[BOUND_LEFT];
        int maximumY = this.bounds[BOUND_BOTTOM];

        if (positionX > minimumX) {
            int horizontal = minimumX;
            int vertical = maximumY;
            int nearestVertical = maximumY;
            int nearestIndex = this.nearestBoundaries[DIRECTION_LEFT];

            if (nearestIndex >= 0) {
                long nearest = this.boundaries[DIRECTION_LEFT][nearestIndex];

                nearestVertical = (short) ((short) (nearest >>> LAST_SHIFT) +
                                           this.dimensions[(int) (nearest >>> LAST_DIMENSION_SHIFT) & END_MASK] + 1);

                if (nearestVertical < vertical) {
                    vertical = nearestVertical;
                }
            }

            int directions = ~(PathDirections.NORTHEAST | PathDirections.CARDINAL_UP | PathDirections.CARDINAL_RIGHT) & PathDirections.ALL;
            int boundaryIndex = this.scan(DIRECTION_DOWN, (short) (positionY + 1), horizontal, positionX, (short) (vertical + 1));
            long boundary;
            int boundaryCoordinate = 0;
            int boundaryStart = 0;
            int boundaryEnd = 0;

            if (boundaryIndex >= 0) {
                boundary           = this.boundaries[DIRECTION_DOWN][boundaryIndex];
                boundaryCoordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);
                boundaryStart      = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);
                boundaryEnd        = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);
                vertical           = boundaryCoordinate;

                if (horizontal > boundaryStart) {
                    directions = PathDirections.NORTHWEST | PathDirections.CARDINAL_LEFT;
                } else if (horizontal == boundaryStart) {
                    directions = (boundary & FIRST_END_FLAG) != 0
                                 ? PathDirections.SOUTHWEST | PathDirections.NORTHWEST | PathDirections.CARDINAL_DOWN | PathDirections.CARDINAL_LEFT
                                 : 0;
                }
            }

            if (nearestIndex >= 0) {
                directions &= ~PathDirections.NORTHWEST & PathDirections.ALL;

                if (nearestVertical > vertical) {
                    directions &= ~(PathDirections.SOUTHWEST | PathDirections.CARDINAL_LEFT) & PathDirections.ALL;
                }
            }

            if (vertical > positionY) {
                this.addCandidate(horizontal, positionY, ~(PathDirections.SOUTHEAST | PathDirections.CARDINAL_DOWN) & PathDirections.ALL);
            } else {
                directions |= PathDirections.NORTHEAST | PathDirections.CARDINAL_UP;
            }

            this.addCandidate(horizontal, vertical, directions);

            if (vertical == maximumY) {
                this.addCandidate(positionX, vertical, ~(PathDirections.NORTHWEST | PathDirections.CARDINAL_LEFT) & PathDirections.ALL);
            }

            if (boundaryIndex >= 0) {
                if (horizontal < boundaryStart) {
                    this.addCandidate(boundaryStart, boundaryCoordinate, PathDirections.SOUTHWEST | PathDirections.CARDINAL_DOWN);
                }

                while (true) {
                    if (boundaryEnd > positionX) {
                        this.addCandidate(positionX, boundaryCoordinate, PathDirections.NORTHEAST | PathDirections.CARDINAL_RIGHT);

                        minimumX = positionX;

                        break;
                    }

                    minimumX = boundaryEnd;

                    this.addCandidate(boundaryEnd, boundaryCoordinate, PathDirections.SOUTHEAST | PathDirections.CARDINAL_DOWN);

                    boundaryIndex++;

                    if (boundaryIndex >= this.boundaryCounts[DIRECTION_DOWN]) {
                        break;
                    }

                    boundary           = this.boundaries[DIRECTION_DOWN][boundaryIndex];
                    boundaryCoordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);
                    boundaryStart      = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);
                    boundaryEnd        = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);

                    if (boundaryCoordinate != vertical || boundaryStart > positionX) {
                        break;
                    }

                    this.addCandidate(boundaryStart, boundaryCoordinate, PathDirections.SOUTHWEST | PathDirections.CARDINAL_DOWN);
                }
            }

            this.visitArea(positionX, positionY, horizontal, vertical);

            positionY = vertical;
        }

        if (positionY < maximumY) {
            int horizontal = minimumX;
            int vertical = maximumY;
            int nearestHorizontal = minimumX;
            int nearestIndex = this.nearestBoundaries[DIRECTION_DOWN];

            if (nearestIndex >= 0) {
                long nearest = this.boundaries[DIRECTION_DOWN][nearestIndex];

                nearestHorizontal = (short) ((short) (nearest >>> FIRST_SHIFT) + this.dimensions[(int) (nearest >>> FLAGS_SHIFT) & END_MASK] - 1);

                if (nearestHorizontal > horizontal) {
                    horizontal = nearestHorizontal;
                }
            }

            int directions = ~(PathDirections.NORTHEAST | PathDirections.CARDINAL_UP | PathDirections.CARDINAL_RIGHT) & PathDirections.ALL;
            int boundaryIndex = this.scan(DIRECTION_LEFT, (short) (positionX - 1), positionY, vertical, (short) (horizontal - 1));
            long boundary;
            int boundaryCoordinate = 0;
            int boundaryStart = 0;
            int boundaryEnd = 0;

            if (boundaryIndex >= 0) {
                boundary           = this.boundaries[DIRECTION_LEFT][boundaryIndex];
                boundaryCoordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);
                boundaryStart      = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);
                boundaryEnd        = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);
                horizontal         = boundaryCoordinate;

                if (vertical < boundaryEnd) {
                    directions = PathDirections.SOUTHEAST | PathDirections.CARDINAL_DOWN;
                } else if (vertical == boundaryEnd) {
                    directions = (boundary & LAST_END_FLAG) != 0
                                 ? PathDirections.SOUTHEAST | PathDirections.SOUTHWEST | PathDirections.CARDINAL_DOWN | PathDirections.CARDINAL_LEFT
                                 : 0;
                }
            }

            if (nearestIndex >= 0) {
                directions &= ~PathDirections.SOUTHEAST & PathDirections.ALL;

                if (nearestHorizontal < horizontal) {
                    directions &= ~(PathDirections.SOUTHWEST | PathDirections.CARDINAL_DOWN) & PathDirections.ALL;
                }
            }

            if (horizontal < positionX) {
                this.addCandidate(positionX, vertical, ~(PathDirections.NORTHWEST | PathDirections.CARDINAL_LEFT) & PathDirections.ALL);
            } else {
                directions |= PathDirections.SOUTHEAST | PathDirections.CARDINAL_RIGHT;
            }

            this.addCandidate(horizontal, vertical, directions);

            if (horizontal > minimumX) {
                this.addCandidate(horizontal, positionY, PathDirections.SOUTHWEST);
            }

            if (boundaryIndex >= 0) {
                if (vertical > boundaryEnd) {
                    this.addCandidate(boundaryCoordinate, boundaryEnd, PathDirections.SOUTHWEST | PathDirections.CARDINAL_LEFT);
                }

                while (boundaryStart >= positionY) {
                    this.addCandidate(boundaryCoordinate, boundaryStart, PathDirections.NORTHWEST | PathDirections.CARDINAL_LEFT);

                    boundaryIndex--;

                    if (boundaryIndex < 0) {
                        break;
                    }

                    boundary           = this.boundaries[DIRECTION_LEFT][boundaryIndex];
                    boundaryCoordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);
                    boundaryStart      = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);
                    boundaryEnd        = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);

                    if (boundaryCoordinate != horizontal || boundaryEnd < positionY) {
                        this.addCandidate(horizontal, positionY, PathDirections.SOUTHWEST | PathDirections.CARDINAL_LEFT);

                        break;
                    }

                    if (boundaryEnd <= vertical) {
                        this.addCandidate(boundaryCoordinate, boundaryEnd, PathDirections.SOUTHWEST | PathDirections.CARDINAL_LEFT);
                    }
                }
            }

            this.visitArea(positionX, positionY, horizontal, vertical);
        }
    }

    private void appendNorthwest() {
        int positionX = this.currentX;
        int positionY = this.currentY;
        int left = this.bounds[BOUND_LEFT];
        int upper = this.bounds[BOUND_TOP];

        if (positionX > left) {
            int coordinateX = left;
            int coordinateY = upper;
            int edgeY = upper;

            if (this.nearestBoundaries[DIRECTION_LEFT] >= 0) {
                long nearest = this.boundaries[DIRECTION_LEFT][this.nearestBoundaries[DIRECTION_LEFT]];

                edgeY       = (short) (this.getExpandedCoordinate(nearest, COMPONENT_FIRST, false) - 1);
                coordinateY = Math.max(coordinateY, edgeY);
            }

            int index = this.scan(DIRECTION_UP, (short) (positionY - 1), coordinateX, positionX, (short) (coordinateY - 1));
            boolean found = index >= 0;
            long boundary = 0;
            int first = 0;

            if (found) {
                boundary = this.boundaries[DIRECTION_UP][index];

                int coordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);

                first = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);

                int last = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);

                if (positionX <= last) {
                    this.addCandidate(positionX, coordinate, PathDirections.SOUTHEAST | PathDirections.CARDINAL_RIGHT);
                } else {
                    this.addCandidate(last, coordinate, PathDirections.NORTHEAST | PathDirections.CARDINAL_UP);
                }

                coordinateY = coordinate;
                left        = last;

                while (first > coordinateX) {
                    this.addCandidate(first, coordinate, PathDirections.NORTHWEST | PathDirections.CARDINAL_UP);

                    if (--index < 0) {
                        break;
                    }

                    long following = this.boundaries[DIRECTION_UP][index];

                    if ((short) following + 1 + this.dimensions[(int) (following >>> DIRECTION_SHIFT) & BYTE_MASK] != coordinateY ||
                        (short) (following >>> LAST_SHIFT) + 1 + this.dimensions[(int) (following >>> LAST_DIMENSION_SHIFT) & END_MASK] <= coordinateX) {
                        break;
                    }

                    boundary   = following;
                    coordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);
                    first      = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);
                    last       = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);

                    if (last >= coordinateX) {
                        this.addCandidate(last, coordinate, PathDirections.NORTHEAST | PathDirections.CARDINAL_UP);
                    }
                }
            }

            int directions = ~(PathDirections.SOUTHEAST | PathDirections.CARDINAL_RIGHT | PathDirections.CARDINAL_DOWN) & PathDirections.ALL;

            if (found) {
                if (coordinateX > first) {
                    directions = PathDirections.SOUTHWEST | PathDirections.CARDINAL_LEFT;
                } else if (coordinateX == first) {
                    directions = (boundary & FIRST_END_FLAG) != 0
                                 ? PathDirections.SOUTHWEST | PathDirections.NORTHWEST | PathDirections.CARDINAL_UP | PathDirections.CARDINAL_LEFT
                                 : 0;
                }
            }

            if (this.nearestBoundaries[DIRECTION_LEFT] >= 0) {
                directions &= ~PathDirections.SOUTHWEST & PathDirections.ALL;

                if (edgeY < coordinateY) {
                    directions &= ~(PathDirections.NORTHWEST | PathDirections.CARDINAL_LEFT) & PathDirections.ALL;
                }
            }

            if (coordinateY >= positionY) {
                directions |= PathDirections.SOUTHWEST | PathDirections.CARDINAL_DOWN;
            } else {
                this.addCandidate(coordinateX, positionY, ~(PathDirections.NORTHEAST | PathDirections.CARDINAL_UP) & PathDirections.ALL);
            }

            this.addCandidate(coordinateX, coordinateY, directions);

            if (coordinateY == upper) {
                this.addCandidate(positionX, coordinateY, ~(PathDirections.SOUTHWEST | PathDirections.CARDINAL_LEFT) & PathDirections.ALL);
            }

            this.visitArea(positionX, positionY, coordinateX, coordinateY);

            positionY = coordinateY;
        }

        if (positionY > upper) {
            int coordinateX = left;
            int edgeX = left;

            if (this.nearestBoundaries[DIRECTION_UP] >= 0) {
                long nearest = this.boundaries[DIRECTION_UP][this.nearestBoundaries[DIRECTION_UP]];

                edgeX       = (short) (this.getExpandedCoordinate(nearest, COMPONENT_FIRST, false) - 1);
                coordinateX = Math.max(coordinateX, edgeX);
            }

            int index = this.scan(DIRECTION_LEFT, (short) (positionX - 1), upper, positionY, (short) (coordinateX - 1));
            boolean found = index >= 0;
            long boundary = 0;
            int first = 0;

            if (found) {
                boundary = this.boundaries[DIRECTION_LEFT][index];

                int coordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);

                first = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);

                int last = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);

                if (coordinate > left) {
                    this.addCandidate(coordinate, positionY, PathDirections.NORTHWEST);
                }

                if (last < positionY) {
                    this.addCandidate(coordinate, last, PathDirections.SOUTHWEST | PathDirections.CARDINAL_LEFT);
                }

                coordinateX = coordinate;

                while (first > upper) {
                    this.addCandidate(coordinate, first, PathDirections.NORTHWEST | PathDirections.CARDINAL_LEFT);

                    if (--index < 0) {
                        break;
                    }

                    long following = this.boundaries[DIRECTION_LEFT][index];

                    if ((short) following + 1 + this.dimensions[(int) (following >>> DIRECTION_SHIFT) & BYTE_MASK] != coordinateX ||
                        (short) (following >>> LAST_SHIFT) + 1 + this.dimensions[(int) (following >>> LAST_DIMENSION_SHIFT) & END_MASK] <= upper) {
                        break;
                    }

                    boundary   = following;
                    coordinate = this.getExpandedCoordinate(boundary, COMPONENT_COORDINATE, true);
                    first      = this.getExpandedCoordinate(boundary, COMPONENT_FIRST, true);
                    last       = this.getExpandedCoordinate(boundary, COMPONENT_LAST, true);

                    if (upper <= last) {
                        this.addCandidate(coordinate, last, PathDirections.SOUTHWEST | PathDirections.CARDINAL_LEFT);
                    }
                }
            }

            int directions = ~(PathDirections.SOUTHEAST | PathDirections.CARDINAL_RIGHT | PathDirections.CARDINAL_DOWN) & PathDirections.ALL;

            if (found) {
                if (upper > first) {
                    directions = PathDirections.NORTHEAST | PathDirections.CARDINAL_UP;
                } else if (upper == first) {
                    directions = (boundary & FIRST_END_FLAG) != 0
                                 ? PathDirections.NORTHEAST | PathDirections.NORTHWEST | PathDirections.CARDINAL_UP | PathDirections.CARDINAL_LEFT
                                 : 0;
                }
            }

            if (this.nearestBoundaries[DIRECTION_UP] >= 0) {
                directions &= ~PathDirections.NORTHEAST & PathDirections.ALL;

                if (edgeX < coordinateX) {
                    directions &= ~(PathDirections.NORTHWEST | PathDirections.CARDINAL_UP) & PathDirections.ALL;
                }
            }

            if (coordinateX >= positionX) {
                directions |= PathDirections.NORTHEAST | PathDirections.CARDINAL_RIGHT;
            } else {
                this.addCandidate(positionX, upper, ~(PathDirections.SOUTHWEST | PathDirections.CARDINAL_LEFT) & PathDirections.ALL);
            }

            this.addCandidate(coordinateX, upper, directions);
            this.visitArea(positionX, positionY, coordinateX, upper);
        }
    }
}
