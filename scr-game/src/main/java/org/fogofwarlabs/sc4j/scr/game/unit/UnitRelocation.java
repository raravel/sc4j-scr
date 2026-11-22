package org.fogofwarlabs.sc4j.scr.game.unit;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.fogofwarlabs.sc4j.scr.game.visibility.UnitVisibility;
import org.jetbrains.annotations.NotNull;

/**
 * Relocates a non-building unit, finding a free position and refreshing its visibility.
 * <p>
 * Visible units are detached before moving. If placement fails the previous position is restored; a hidden unit is
 * revealed when {@link UnitVisibility#refreshPosition} refreshes its position.
 */
public final class UnitRelocation {
    /**
     * The mask of a WORD search coordinate.
     */
    private static final int WORD_MASK = 0xFFFF;


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitRelocation} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitRelocation(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Relocates a unit with flag {@code 0}: detaches it (nothing for a hidden unit), moves it to the requested point,
     * searches the nearest free position, moves it there or back to the previous position, then attaches it.
     *
     * @param unit      the non-building unit
     * @param positionX the requested X coordinate
     * @param positionY the requested Y coordinate
     * @return {@code true} if a free position was found and the unit moved there, {@code false} otherwise
     * @throws UnsupportedOperationException if the unit creation context is not established
     * @throws IllegalStateException         if the unit has no sprite
     */
    public boolean relocate(@NotNull Unit unit, int positionX, int positionY) {
        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

        if (context == null) {
            throw Exceptions.newUnsupportedOperationException("Unit relocation requires the unit creation context.");
        }

        Sprite sprite = unit.requireSprite();
        int previousX = sprite.getX();
        int previousY = sprite.getY();

        // The detach returns at once for a hidden unit; the attach then reveals it at its new position.
        if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) == 0) {
            this.game.getUnitLifecycle().detach(unit, false);
        }

        this.game.getUnitPositioning().move(unit, positionX, positionY);

        int[] position = new int[UnitPositioning.POSITION_COORDINATES];
        boolean found = this.game.getUnitPositioning().find(unit, positionX & WORD_MASK, positionY & WORD_MASK, position);

        if (found) {
            this.game.getUnitPositioning().move(unit, position[UnitPositioning.X_INDEX], position[UnitPositioning.Y_INDEX]);
        } else {
            this.game.getUnitPositioning().move(unit, previousX, previousY);
        }

        this.game.getUnitVisibility().refreshPosition(unit, context.isFoliageEnabled());

        return found;
    }

    /**
     * Moves a ground unit that the terrain under it no longer carries to the nearest free walkable position in any terrain component
     * ({@link UnitPositioning#findWalkable}), as {@link #relocate} moves a unit. A unit without such a position stays.
     *
     * @param unit the non-building ground unit
     * @return {@code true} if a free position was found and the unit moved there, {@code false} otherwise
     * @throws UnsupportedOperationException if the unit creation context is not established
     * @throws IllegalStateException         if the unit has no sprite
     */
    public boolean displace(@NotNull Unit unit) {
        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

        if (context == null) {
            throw Exceptions.newUnsupportedOperationException("Unit relocation requires the unit creation context.");
        }

        Sprite sprite = unit.requireSprite();

        if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) == 0) {
            this.game.getUnitLifecycle().detach(unit, false);
        }

        int[] position = new int[UnitPositioning.POSITION_COORDINATES];
        boolean found = this.game.getUnitPositioning().findWalkable(unit, sprite.getX(), sprite.getY(), position);

        if (found) {
            this.game.getUnitPositioning().move(unit, position[UnitPositioning.X_INDEX], position[UnitPositioning.Y_INDEX]);
        }

        this.game.getUnitVisibility().refreshPosition(unit, context.isFoliageEnabled());

        return found;
    }
}
