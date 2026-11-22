package org.fogofwarlabs.sc4j.scr.game.unit;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.ai.UnitStrengthTable;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.production.UnitMorphing;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;

/**
 * Initializes completion effects before placement, then applies completed-unit accounting and initial orders.
 */
public final class UnitCompletion {
    /**
     * The {@code units.dat} initial direction that asks for a random direction.
     */
    private static final int RANDOM_DIRECTION = 32;
    /**
     * The random source of a random initial direction.
     */
    private static final int DIRECTION_RANDOM_SOURCE = 36;
    /**
     * The mask of the {@code 32} unit directions.
     */
    private static final int DIRECTION_MASK = 31;
    /**
     * The shift from one of the {@code 32} unit directions to the {@code 256}-step heading.
     */
    private static final int DIRECTION_SHIFT = 3;
    /**
     * The fraction bits of the shield points.
     */
    private static final int FIXED_POINT_SHIFT = 8;
    /**
     * The mask of the BYTE type of the owner player record.
     */
    private static final int BYTE_MASK = 0xFF;

    private final @NotNull Game game;

    /**
     * Creates a {@link UnitCompletion} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitCompletion(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Initializes completion effects before placement and completed-unit accounting.
     *
     * @param creation         the unit creation inputs
     * @param unit             the constructed unit
     * @param instructionLimit the checked script instruction limit
     * @throws UnsupportedOperationException if the unit type is past {@link ScrUnitIds#NONE} or a completed building has no sprite
     * @throws IllegalStateException         if the unit registry is missing
     * @throws NullPointerException          if the world was built without game data
     */
    public void initializeBeforePlacement(@NotNull UnitCreationContext creation, @NotNull Unit unit, int instructionLimit) {
        if (creation.isRegistryPresent()) {
            UnitRegistry registry = this.game.getUnitSystem().getUnitRegistry();

            if (registry == null) {
                throw Exceptions.newIllegalStateException("The unit registry is created with the game, before any unit.");
            }

            registry.completeUnit(unit);
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();

        if (unitType > ScrUnitIds.NONE && !units.isAddedType(unitType)) {
            throw Exceptions.newUnsupportedOperationException("Completion of unit type {0} past {1} is not modeled.",
                                                              unitType, ScrUnitIds.NONE);
        }

        if (unit.getRemainingBuildTime() != 0) {
            unit.setHitPoints(units.getHitPoints(unitType));
            unit.setShieldPoints(units.getShieldAmount(unitType) << FIXED_POINT_SHIFT);
            unit.setRemainingBuildTime(0);
        }

        UnitMorphing.writeButtonSet(units, unit, this.game.requireGameData().getButtonSet(unitType));

        if (UnitQueries.isGroundedBuilding(unit)) {
            unit.setParasiteFlags(0);
            unit.setBlindnessState(0);
            this.game.getIscriptExecutor().initializeConstructionImage(unit, IscriptExecutor.COMPLETED_IMAGE_MODE, instructionLimit);

            Sprite sprite = unit.getSpriteOrNull();

            if (sprite == null) {
                throw Exceptions.newUnsupportedOperationException("Building completion requires the unit sprite.");
            }

            this.game.getIscriptExecutor().runSpriteAnimation(sprite, unit, IscriptExecutor.ANIMATION_BUILT, 1, instructionLimit);

            // Outside replays a completed building of the local player requests interface refreshes (the button set choice,
            // the command card and the panels); the app interface recomputes them from game state every frame.
        } else {
            if ((unit.getStatus() & UnitStatusFlags.CAN_TURN) != 0) {
                int direction = units.getInitialDirection(unitType);

                if (direction == RANDOM_DIRECTION) {
                    direction = this.game.getRandom().next(DIRECTION_RANDOM_SOURCE) & DIRECTION_MASK;
                }

                unit.resetDirection(this.game, direction << DIRECTION_SHIFT);
            }

            if (units.isMapDoodad(unitType)) {
                this.game.getUnitVisibility().show(unit, creation.isFoliageEnabled());
            }
        }
    }

    /**
     * Applies the established visible-unit completion path.
     *
     * @param creation         the unit creation inputs
     * @param unit             the constructed and placed unit
     * @param ownerPlayerType  the BYTE type read from the original owner player record
     * @param instructionLimit the checked script instruction limit
     * @throws UnsupportedOperationException if the unit accounting, the unit sprite or the strength tables are absent, or the unit pool is not
     *                                       initialized
     * @throws NullPointerException          if the world was built without game data
     */
    public void completeAfterPlacement(@NotNull UnitCreationContext creation, @NotNull Unit unit, int ownerPlayerType, int instructionLimit) {
        UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();

        if (accounting == null) {
            throw Exceptions.newUnsupportedOperationException("Unit completion requires initialized unit accounting.");
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();
        int specialFlags = units.getFlags(unitType);

        if ((specialFlags & UnitTypeFlags.TWO_UNITS_IN_ONE_EGG) != 0) {
            accounting.update(unit, units, -1, creation.preservesNegativeTypeCounts());
        }

        unit.setStatus(unit.getStatus() | UnitStatusFlags.COMPLETED);

        if ((specialFlags & UnitTypeFlags.TWO_UNITS_IN_ONE_EGG) != 0) {
            accounting.update(unit, units, 1, creation.preservesNegativeTypeCounts());
        }

        accounting.updateCompleted(unit, units, 1, true, this.game.getPlayerTable().getProductionStatistics());

        // Initially cloaked types start cloaking on completion.
        if ((specialFlags & UnitTypeFlags.PERMANENT_CLOAK) != 0) {
            this.game.getUnitCloaking().cloakUnit(unit);
        }

        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Unit completion requires the constructed sprite.");
        }

        if (units.isMapRevealer(unitType)) {
            // A map revealer joins the map-revealer list and refreshes its vision.
            this.game.getUnitSystem().requireUnitPool().transferHiddenUnitToRevealers(unit);
            this.game.getUnitVisibility().updateFog(unit);
        } else if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) != 0 && !units.isHangarUnit(unitType)) {
            this.game.getUnitVisibility().show(unit, creation.isFoliageEnabled());
        }

        this.game.getEngineGame().getUnitLifecycle().notifyCompleted(unit);

        ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

        if (simulation != null) {
            simulation.reportUnitCompleted(unit);
        }

        int initialOrder = switch (ownerPlayerType & BYTE_MASK) {
            case PlayerTypes.RESCUE_PASSIVE -> ScrOrderIds.RESCUE_PASSIVE;
            case PlayerTypes.NEUTRAL -> ScrOrderIds.NEUTRAL;
            case PlayerTypes.COMPUTER -> units.getComputerIdleOrder(unitType);
            default -> units.getHumanIdleOrder(unitType);
        };

        this.game.getOrders().issueUntargeted(unit, initialOrder, instructionLimit);

        if ((specialFlags & UnitTypeFlags.POWERUP) != 0) {
            unit.setResources(unit.getPositionX());
            unit.setSecondLarvaSideCount(unit.getPositionY());
            this.game.getOrders().issueUntargeted(unit, units.getHumanIdleOrder(unitType), instructionLimit);
        }

        UnitStrengthTable strength = this.game.getUnitStrengthTable();

        if (strength == null) {
            throw Exceptions.newUnsupportedOperationException("Unit completion requires the initialized strength tables.");
        }

        strength.update(this.game, unit, units);
    }
}
