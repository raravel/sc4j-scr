package org.fogofwarlabs.sc4j.scr.game.movement;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.modifier.Stat;
import org.fogofwarlabs.sc4j.engine.content.type.ContentType;
import org.fogofwarlabs.sc4j.scr.data.type.ScrFlingyTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.frame.GameExecutionScope;
import org.fogofwarlabs.sc4j.scr.game.modifier.ScrSpeedModifier;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerUpgrades;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;

/**
 * Recalculates the movement fields of a unit (top speed, acceleration and turn rate) from {@code flingy.dat} and its speed modifiers, and refreshes
 * its speed and cooldown upgrade flags.
 */
public final class UnitSpeed {
    /**
     * A speed increase multiplies the modified rate by this factor.
     */
    private static final int INCREASE_FACTOR = 2;

    /**
     * A speed decrease removes this right shift of the modified rate (a quarter).
     */
    private static final int DECREASE_SHIFT = 2;

    /**
     * Applies a net speed modifier to a movement rate: a positive modifier doubles the rate, a negative one removes a quarter of it. The rate is a
     * {@code flingy.dat} acceleration or turn rate, or a walking speed sampled from the iscript.
     *
     * @param rate     the acceleration, turn rate or sampled walking speed
     * @param modifier the net speed modifier ({@link Unit#getSpeedModifier()})
     * @return the modified rate
     */
    public static int applySpeedModifier(int rate, int modifier) {
        return modifier > 0 ? rate * INCREASE_FACTOR : modifier < 0 ? rate - (rate >>> DECREASE_SHIFT) : rate;
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitSpeed} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitSpeed(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Recalculates the movement fields of a unit: an iscript-controlled unit runs its Walking script, any other unit takes the {@code flingy.dat}
     * values with its speed modifiers inside the initialized execution scope.
     *
     * @param unit             the unit whose movement fields are recalculated
     * @param instructionLimit the Walking interpreter instruction limit
     * @throws UnsupportedOperationException if a flingy-controlled unit has no initialized execution scope, or neither construction, command dispatch
     *                                       nor a frame phase is active
     * @throws NullPointerException          if the world was built without game data
     */
    public void recalculate(@NotNull Unit unit, int instructionLimit) {
        int flingyType = this.game.requireGameData().getUnitTypes().getFlingyType(unit.getUnitId());

        if (this.game.requireGameData().getFlingyTypes().getMovementControl(flingyType) >= ScrFlingyTypeView.MOVEMENT_CONTROL_ISCRIPT) {
            this.game.getUnitInitializer().recalculateWalkingSpeed(unit, instructionLimit);

            return;
        }

        GameExecutionScope executionScope = this.game.getExecutionScope();

        if (executionScope == null) {
            throw Exceptions.newUnsupportedOperationException("Movement control zero or one requires its initialized execution scope.");
        }

        executionScope.requireActiveScope();

        ScrFlingyTypeView flingyTypes = this.game.requireGameData().getFlingyTypes();

        unit.setTopSpeed(this.calculateModifiedTopSpeed(unit));

        int modifier = unit.getSpeedModifier();

        unit.setAcceleration(applySpeedModifier(flingyTypes.getAcceleration(flingyType), modifier));
        unit.setTurnRate(applySpeedModifier(flingyTypes.getTurnRate(flingyType), modifier));
    }

    /**
     * Calculates the top speed of a unit: the {@code flingy.dat} top speed of its type's flingy with its speed modifiers
     * ({@link Unit#calculateModifiedMovementSpeed}).
     *
     * @param unit the unit
     * @return the modified top speed in units with eight fraction bits
     * @throws NullPointerException if the world was built without game data
     */
    public int calculateModifiedTopSpeed(@NotNull Unit unit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int flingyType = units.getFlingyType(unit.getUnitId());

        return unit.calculateModifiedMovementSpeed(units, this.game.requireGameData().getFlingyTypes().getTopSpeed(flingyType));
    }

    /**
     * Refreshes the speed and cooldown upgrade flags from the owner's upgrade levels, recalculating the movement fields only when a flag changes.
     *
     * @param unit             the unit whose owner or upgrades changed
     * @param instructionLimit the Walking interpreter instruction limit
     * @throws UnsupportedOperationException if the unit type has a speed upgrade and the player upgrade levels are absent
     */
    public void updateUpgradeFlags(@NotNull Unit unit, int instructionLimit) {
        int unitType = unit.getUnitId();

        if (!ScrSpeedModifier.hasSpeedRule(this.game.getBaseType(unitType))) {
            return;
        }

        PlayerUpgrades upgrades = this.game.getPlayerTable().getPlayerUpgrades();

        if (upgrades == null) {
            throw Exceptions.newUnsupportedOperationException("Ownership speed upgrades require player upgrade levels.");
        }

        boolean cooldown = this.game.applyModifiers(Stat.WEAPON_COOLDOWN, unit.getOwnerIndex(), unitType, ContentType.NO_INDEX, 0) > 0;
        boolean speed = this.game.applyModifiers(Stat.TOP_SPEED, unit.getOwnerIndex(), unitType, ContentType.NO_INDEX, 0) > 0;
        int status = unit.getStatus();
        boolean changed = ((status & UnitStatusFlags.SPEED_UPGRADE) != 0) != speed || ((status & UnitStatusFlags.COOLDOWN_UPGRADE) != 0) != cooldown;

        status = speed ? status | UnitStatusFlags.SPEED_UPGRADE : status & ~UnitStatusFlags.SPEED_UPGRADE;
        status = cooldown ? status | UnitStatusFlags.COOLDOWN_UPGRADE : status & ~UnitStatusFlags.COOLDOWN_UPGRADE;

        unit.setStatus(status);

        if (changed) {
            this.recalculate(unit, instructionLimit);
        }
    }
}
