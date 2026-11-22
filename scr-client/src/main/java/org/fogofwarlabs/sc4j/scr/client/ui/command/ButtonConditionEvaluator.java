package org.fogofwarlabs.sc4j.scr.client.ui.command;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.StatusButtonPanel;
import org.fogofwarlabs.sc4j.scr.game.resource.CarryingFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;

/**
 * Evaluates command-card conditions against the active unit, local selection and player.
 * Selection loops retain their early-exit order and requirement failure state.
 * Replay conditions delegate to the shared replay-button evaluator.
 */
public final class ButtonConditionEvaluator {
    private static final int ENABLED = RequirementEvaluator.ENABLED;
    private static final int HIDDEN = RequirementEvaluator.HIDDEN;
    private static final int DISABLED = RequirementEvaluator.DISABLED;
    /**
     * The mask of the BYTE condition value of a button.
     */
    private static final int CONDITION_VALUE_MASK = 0xFF;
    private static final int @NotNull [] ZERG_BASIC = {0x83, 0x8f, 0x95, 0x8e, 0x8b, 0x87};
    private static final int @NotNull [] ZERG_ADVANCED = {0x86, 0x8d, 0x8a, 0x8c, 0x88};
    private static final int @NotNull [] PROTOSS_BASIC = {0x9a, 0x9c, 0x9d, 0xa0, 0xa6, 0xa2, 0xa4, 0xac};
    private static final int @NotNull [] PROTOSS_ADVANCED = {0x9b, 0xa7, 0xa3, 0xab, 0xa9, 0xa5, 0x9f, 0xaa};
    private static final int @NotNull [] TERRAN_BASIC = {0x6a, 0x6d, 0x6e, 0x6f, 0x7a, 0x7c, 0x70, 0x7d};
    private static final int @NotNull [] TERRAN_ADVANCED = {0x71, 0x72, 0x74, 0x7b};

    /**
     * Tests whether the unit is a Siege Tank or hero tank in Siege Mode.
     */
    private static boolean isSieged(@NotNull Unit unit) {
        return unit.getUnitId() == ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE || unit.getUnitId() == ScrUnitIds.HERO_EDMUND_DUKE_SIEGE_MODE;
    }

    private static boolean isBurrowed(@NotNull Unit unit) {
        return (unit.getStatus() & UnitStatusFlags.BURROWED) != 0;
    }

    private static boolean isCarrying(@NotNull Unit unit) {
        return (unit.getCarryingFlags() & CarryingFlags.RESOURCES) != 0;
    }

    private static boolean isNotCarrying(@NotNull Unit unit) {
        return (unit.getCarryingFlags() & CarryingFlags.RESOURCES) == 0;
    }

    private static boolean isNotConstructing(@NotNull Unit unit) {
        return unit.getPrimaryOrder() != ScrOrderIds.CONSTRUCTING_BUILDING;
    }

    private static boolean isBurrowingOrUnburrowing(@NotNull Unit unit) {
        return unit.getPrimaryOrder() == ScrOrderIds.BURROWING || unit.getPrimaryOrder() == ScrOrderIds.UNBURROWING;
    }

    private static boolean isHatchery(@NotNull Unit unit) {
        return unit.getUnitId() >= ScrUnitIds.ZERG_HATCHERY && unit.getUnitId() <= ScrUnitIds.ZERG_HIVE;
    }

    private final @NotNull ScrGameView view;
    private final @NotNull RequirementEvaluator requirements;

    private boolean replayMode;
    private int replaySpeedSetting;
    private int replaySpeed;

    /**
     * Creates a {@link ButtonConditionEvaluator} instance reading the given game state.
     *
     * @param view the game view
     */
    public ButtonConditionEvaluator(@NotNull ScrGameView view) {
        this.view         = view;
        this.requirements = new RequirementEvaluator(view);

        this.replayMode         = false;
        this.replaySpeedSetting = 0;
        this.replaySpeed        = 0;
    }

    /**
     * @return the requirement evaluator whose failure code the last condition left
     */
    public @NotNull RequirementEvaluator getRequirements() {
        return this.requirements;
    }

    /**
     * Sets the replay control inputs read by the replay button conditions.
     *
     * @param replayMode   whether a replay is shown
     * @param speedSetting the game speed setting index
     * @param replaySpeed  the replay speed multiplier
     */
    public void setReplayControls(boolean replayMode, int speedSetting, int replaySpeed) {
        this.replayMode         = replayMode;
        this.replaySpeedSetting = speedSetting;
        this.replaySpeed        = replaySpeed;
    }


    /**
     * Evaluates a button condition.
     *
     * @param button    the button whose condition is evaluated
     * @param selection the local selection
     * @return {@link RequirementEvaluator#ENABLED}, {@link RequirementEvaluator#DISABLED} or
     * {@link RequirementEvaluator#HIDDEN}
     * @throws UnsupportedOperationException if a requirement reaches state without an established domain meaning
     */
    public int evaluate(@NotNull ButtonDefinition button, @NotNull InterfaceSelection selection) {
        int value = button.getConditionValue() & CONDITION_VALUE_MASK;
        int player = selection.getPlayer();
        Unit active = selection.getActiveUnit();

        return switch (button.getCondition()) {
            case ALWAYS -> ENABLED;
            case REPLAY_PLAY_PAUSE -> this.evaluateReplayButton(StatusButtonPanel.PLAY_PAUSE_BUTTON, player);
            case REPLAY_SLOW_DOWN -> this.evaluateReplayButton(StatusButtonPanel.SLOW_DOWN_BUTTON, player);
            case REPLAY_SPEED_UP -> this.evaluateReplayButton(StatusButtonPanel.SPEED_UP_BUTTON, player);
            default -> active == null ? HIDDEN : this.evaluateUnitCondition(button.getCondition(), value, player, active, selection);
        };
    }

    private int evaluateReplayButton(int replayButton, int player) {
        return StatusButtonPanel.evaluateReplayButtonCondition(replayButton,
                                                               this.replayMode ? 1 : 0, 0, player, 0,
                                                               this.replaySpeedSetting, this.replaySpeed);
    }

    private int evaluateUnitCondition(@NotNull ButtonCondition condition,
                                      int value,
                                      int player,
                                      @NotNull Unit active,
                                      @NotNull InterfaceSelection selection) {
        int status = active.getStatus();

        return switch (condition) {
            case UNIT_REQUIREMENT -> {
                int type = active.getUnitId();

                if (selection.getCount() > 1 && type != ScrUnitIds.ZERG_LARVA && type != ScrUnitIds.ZERG_MUTALISK &&
                    type != ScrUnitIds.ZERG_HYDRALISK) {
                    yield HIDDEN;
                }

                yield this.requirements.evaluateUnit(value, player, active);
            }
            case HANGAR_UNIT_REQUIREMENT -> {
                int result = HIDDEN;

                for (int slot = 0; slot < InterfaceSelection.CAPACITY; slot++) {
                    Unit unit = selection.getUnit(slot);

                    if (unit == null) {
                        continue;
                    }

                    if (unit.getUnitId() != active.getUnitId()) {
                        yield HIDDEN;
                    }

                    if (result == HIDDEN) {
                        result = this.requirements.evaluateUnit(value, player, unit);
                    }
                }

                yield result;
            }
            case ANY_CAN_MOVE -> this.evaluateAnySelectedUnit(selection, unit -> {
                int action = this.view.getUnitQueries().getRightClickClass(unit);

                return action != 0 && action != ScrUnitTypeView.RIGHT_CLICK_ATTACK_NO_MOVE;
            });
            case ANY_HAS_RIGHT_CLICK -> this.evaluateAnySelectedUnit(selection, unit -> this.view.getUnitQueries().getRightClickClass(unit) != 0);
            case ANY_CAN_ATTACK -> this.evaluateAnySelectedUnit(selection, this::canRightClickAttack);
            case ANY_ENABLED_CAN_ATTACK -> this.evaluateAnySelectedUnit(selection, unit -> !UnitOrderEligibility.isDisabled(unit) &&
                                                                                           this.view.getUnitQueries().canAttack(unit));
            case TRAINING_UNIT -> UnitQueries.hasQueuedUnit(this.view.requireGameData().getUnitTypes(), active, 0) ? ENABLED : HIDDEN;
            case BUILDING_ADDON -> UnitQueries.isBuildingAddon(active) ? ENABLED : HIDDEN;
            case IS_HATCHERY -> isHatchery(active) ? ENABLED : HIDDEN;
            case RALLY_ORDER -> this.requirements.evaluateOrder(ScrOrderIds.RALLY_POINT_TILE, player, active);
            case HATCHERY_RALLY_ORDER -> isHatchery(active)
                                         ? this.requirements.evaluateOrder(ScrOrderIds.RALLY_POINT_TILE, player, active)
                                         : HIDDEN;
            case INCOMPLETE -> (status & UnitStatusFlags.COMPLETED) == 0 ? ENABLED : HIDDEN;
            case UPGRADING -> UnitQueries.isUpgrading(active) ? ENABLED : HIDDEN;
            case RESEARCHING -> UnitQueries.isResearching(active) ? ENABLED : HIDDEN;
            case ANY_NOT_CARRYING -> this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isNotCarrying);
            case ANY_CARRYING -> this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isCarrying);
            case ANY_CAN_LOAD -> {
                for (int slot = 0; slot < InterfaceSelection.CAPACITY; slot++) {
                    Unit unit = selection.getUnit(slot);

                    if (unit == null) {
                        continue;
                    }

                    if (!this.view.getUnitQueries().isTransport(unit)) {
                        yield HIDDEN;
                    }

                    if (this.view.getUnitQueries().hasFreeSpace(unit)) {
                        yield ENABLED;
                    }
                }

                yield HIDDEN;
            }
            case ANY_HAS_LOADED -> this.evaluateAnySelectedUnit(selection, unit -> this.view.getUnitTransport().hasLoadedUnits(unit));
            case RESEARCH_REQUIREMENT -> this.requirements.evaluateResearch(value, player, active);
            case TECHNOLOGY_USE_REQUIREMENT -> this.requirements.evaluateTechnologyUse(value, player, active);
            case ANY_TECHNOLOGY_USE_REQUIREMENT -> {
                int lowest = HIDDEN;

                for (int slot = 0; slot < InterfaceSelection.CAPACITY; slot++) {
                    Unit unit = selection.getUnit(slot);

                    if (unit == null) {
                        continue;
                    }

                    int result = this.requirements.evaluateTechnologyUse(value, player, unit);

                    if (result == ENABLED) {
                        yield ENABLED;
                    }

                    lowest = Math.min(lowest, result);
                }

                yield lowest;
            }
            case UPGRADE_REQUIREMENT -> this.requirements.evaluateUpgrade(value, player, active);
            case CAN_CLOAK -> this.evaluateCloak(selection, value, player, false);
            case CAN_DECLOAK -> this.evaluateDecloak(selection, value, player, false);
            case CAN_CLOAK_OWN_TECHNOLOGY -> this.evaluateCloak(selection, value, player, true);
            case CAN_DECLOAK_OWN_TECHNOLOGY -> this.evaluateDecloak(selection, value, player, true);
            case CAN_SIEGE -> {
                int result = this.requirements.evaluateTechnologyUse(value, player, active);

                if (result != ENABLED) {
                    yield result;
                }

                yield this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isSieged) == ENABLED ? HIDDEN : ENABLED;
            }
            case CAN_UNSIEGE -> this.requirements.evaluateTechnologyUse(value, player, active) != ENABLED
                                ? HIDDEN
                                : this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isSieged);
            case NONE_SIEGED -> this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isSieged) == ENABLED ? HIDDEN : ENABLED;
            case NUCLEAR_SILO_READY -> this.hasReadySilo(active.getOwnerIndex()) ? ENABLED : DISABLED;
            case NOT_DISABLED -> !UnitOrderEligibility.isDisabled(active) ? ENABLED : HIDDEN;
            case NOT_GROUNDED -> !UnitQueries.isGroundedBuilding(active) ? ENABLED : HIDDEN;
            case CAN_LAND -> selection.getCount() == 1 && !UnitQueries.isGroundedBuilding(active) ? ENABLED : HIDDEN;
            case CAN_LIFT_OFF -> UnitQueries.isGroundedBuilding(active) && !UnitQueries.isProducing(active) &&
                                 !UnitQueries.isResearching(active) && !UnitQueries.isUpgrading(active) ? ENABLED : HIDDEN;
            case CAN_BURROW -> {
                for (int slot = 0; slot < InterfaceSelection.CAPACITY; slot++) {
                    Unit unit = selection.getUnit(slot);

                    if (unit == null || !this.view.getUnitQueries().isBurrowable(unit)) {
                        continue;
                    }

                    int result = this.requirements.evaluateTechnologyUse(value, player, unit);

                    if (result != ENABLED) {
                        yield result;
                    }

                    if (!isBurrowed(unit) && !isBurrowingOrUnburrowing(unit)) {
                        yield ENABLED;
                    }
                }

                yield HIDDEN;
            }
            case CAN_UNBURROW -> {
                for (int slot = 0; slot < InterfaceSelection.CAPACITY; slot++) {
                    Unit unit = selection.getUnit(slot);

                    if (unit == null || !this.view.getUnitQueries().isBurrowable(unit)) {
                        continue;
                    }

                    if (!isBurrowed(unit) || isBurrowingOrUnburrowing(unit) ||
                        this.requirements.evaluateTechnologyUse(value, player, unit) != ENABLED) {
                        yield HIDDEN;
                    }
                }

                yield ENABLED;
            }
            case LURKER_ASPECT -> this.requirements.evaluateTechnologyUse(ScrTechIds.LURKER_ASPECT, player, active);
            case NONE_BURROWED -> this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isBurrowed) == HIDDEN ? ENABLED : HIDDEN;
            case NONE_BURROWED_CAN_ATTACK -> this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isBurrowed) != HIDDEN
                                             ? HIDDEN
                                             : this.evaluateAnySelectedUnit(selection, this::canRightClickAttack);
            case NONE_BURROWED_NOT_CARRYING -> this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isBurrowed) != HIDDEN
                                               ? HIDDEN
                                               : this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isNotCarrying);
            case NONE_BURROWED_CARRYING -> this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isBurrowed) != HIDDEN
                                           ? HIDDEN
                                           : this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isCarrying);
            case SAME_BURROW_STATE -> {
                boolean burrowed = isBurrowed(active);

                yield this.evaluateAnySelectedUnit(selection, unit -> isBurrowed(unit) != burrowed) == ENABLED ? HIDDEN : ENABLED;
            }
            case NO_NYDUS_EXIT -> UnitQueries.getLinkedUnit(active) == null ? ENABLED : HIDDEN;
            case ZERG_BASIC_STRUCTURES -> this.evaluateStructureMenu(selection, player, active, ZERG_BASIC, false);
            case ZERG_ADVANCED_STRUCTURES -> this.evaluateStructureMenu(selection, player, active, ZERG_ADVANCED, false);
            case PROTOSS_BASIC_STRUCTURES -> this.evaluateStructureMenu(selection, player, active, PROTOSS_BASIC, false);
            case PROTOSS_ADVANCED_STRUCTURES -> this.evaluateStructureMenu(selection, player, active, PROTOSS_ADVANCED, false);
            case TERRAN_BASIC_STRUCTURES -> this.evaluateStructureMenu(selection, player, active, TERRAN_BASIC, true);
            case TERRAN_ADVANCED_STRUCTURES -> this.evaluateStructureMenu(selection, player, active, TERRAN_ADVANCED, true);
            case CARRIER_HAS_INTERCEPTORS -> {
                Predicate<Unit> hasInterceptors = unit -> this.view.requireGameData().getUnitTypes().isCarrier(unit.getUnitId()) &&
                                                          unit.getHangarCount(false) + unit.getHangarCount(true) != 0;

                yield this.evaluateAnySelectedUnit(selection, hasInterceptors) == ENABLED ? ENABLED : DISABLED;
            }
            case REAVER_HAS_SCARABS -> {
                Predicate<Unit> hasScarabs = unit -> this.view.requireGameData().getUnitTypes().isReaver(unit.getUnitId()) &&
                                                     unit.getHangarCount(false) != 0;

                yield this.evaluateAnySelectedUnit(selection, hasScarabs) == ENABLED ? ENABLED : DISABLED;
            }
            case TECHNOLOGY_USE_DISABLED -> this.requirements.evaluateTechnologyUse(value, player, active) == DISABLED ? DISABLED : HIDDEN;
            case ARCHON_WARP -> this.evaluateMerge(ScrTechIds.ARCHON_WARP, player, active, selection);
            case DARK_ARCHON_MELD -> this.evaluateMerge(ScrTechIds.DARK_ARCHON_MELD, player, active, selection);
            case ALL_CONSTRUCTING -> this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isNotConstructing) == ENABLED
                                     ? HIDDEN
                                     : ENABLED;
            case ANY_NOT_CONSTRUCTING -> this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isNotConstructing);
            case SCV_CAN_GATHER -> this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isNotCarrying) == HIDDEN
                                   ? HIDDEN
                                   : this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isNotConstructing);
            case SCV_CAN_RETURN_CARGO -> this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isCarrying) == HIDDEN
                                         ? HIDDEN
                                         : this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isNotConstructing);
            case SINGLE_UNIT_REQUIREMENT -> selection.getCount() == 1 ? this.requirements.evaluateUnit(value, player, active) : HIDDEN;
            case SCRIPT -> this.view.getButtonStates().getState(value);
            case ALWAYS, REPLAY_PLAY_PAUSE, REPLAY_SLOW_DOWN, REPLAY_SPEED_UP ->
                    throw Exceptions.newIllegalStateException("Selection-independent conditions are evaluated before the active unit.");
        };
    }

    private int evaluateAnySelectedUnit(@NotNull InterfaceSelection selection, @NotNull Predicate<Unit> test) {
        for (int slot = 0; slot < InterfaceSelection.CAPACITY; slot++) {
            Unit unit = selection.getUnit(slot);

            if (unit != null && test.test(unit)) {
                return ENABLED;
            }
        }

        return HIDDEN;
    }

    /**
     * Tests whether the unit has a right-click action and can attack.
     */
    private boolean canRightClickAttack(@NotNull Unit unit) {
        return this.view.getUnitQueries().getRightClickClass(unit) != 0 && this.view.getUnitQueries().canAttack(unit);
    }

    /**
     * Finds a ready Nuclear Silo in the owner-linked unit list.
     */
    private boolean hasReadySilo(int owner) {
        UnitPool pool = this.view.getUnitSystem().getUnitPool();

        if (pool == null || owner < 0 || owner >= PlayerTable.PLAYER_SLOTS) {
            return false;
        }

        for (int index = pool.getFirstOwnerIndex(owner); index != 0; index = pool.getNextOwnerIndex(index)) {
            Unit unit = pool.getUnit(index);

            if (unit.getUnitId() == ScrUnitIds.TERRAN_NUCLEAR_SILO && unit.isSiloReady()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Finds a selected unit that can use the chosen cloaking technology and still requires cloaking.
     */
    private int evaluateCloak(@NotNull InterfaceSelection selection, int value, int player, boolean ownTechnology) {
        for (int slot = 0; slot < InterfaceSelection.CAPACITY; slot++) {
            Unit unit = selection.getUnit(slot);

            if (unit == null) {
                continue;
            }

            int technology = ownTechnology ? UnitQueries.getCloakTechnology(this.view.requireGameData().getUnitTypes(), unit) : value;
            int result = this.requirements.evaluateTechnologyUse(technology, player, unit);

            if (result != ENABLED) {
                if (ownTechnology) {
                    continue;
                }

                return result;
            }

            int unitStatus = unit.getStatus();

            if ((unitStatus & UnitStatusFlags.PASSIVELY_CLOAKED) != 0 && (unitStatus & UnitStatusFlags.BURROWED) == 0) {
                continue;
            }

            if ((unitStatus & UnitStatusFlags.REQUIRES_DETECTOR) == 0) {
                return ENABLED;
            }
        }

        return HIDDEN;
    }

    /**
     * Requires every selected unit to pass its decloaking technology and state checks.
     */
    private int evaluateDecloak(@NotNull InterfaceSelection selection, int value, int player, boolean ownTechnology) {
        for (int slot = 0; slot < InterfaceSelection.CAPACITY; slot++) {
            Unit unit = selection.getUnit(slot);

            if (unit == null) {
                continue;
            }

            int technology = ownTechnology ? UnitQueries.getCloakTechnology(this.view.requireGameData().getUnitTypes(), unit) : value;

            if (this.requirements.evaluateTechnologyUse(technology, player, unit) != ENABLED) {
                return HIDDEN;
            }

            int unitStatus = unit.getStatus();

            if ((unitStatus & UnitStatusFlags.PASSIVELY_CLOAKED) != 0 && (unitStatus & UnitStatusFlags.BURROWED) == 0) {
                return HIDDEN;
            }

            if ((unitStatus & UnitStatusFlags.REQUIRES_DETECTOR) == 0) {
                return HIDDEN;
            }
        }

        return ENABLED;
    }

    /**
     * Tests the merge technology and disables a merge with only one selected unit.
     */
    private int evaluateMerge(int technology, int player, @NotNull Unit active, @NotNull InterfaceSelection selection) {
        if (this.requirements.evaluateTechnologyUse(technology, player, active) != ENABLED) {
            return HIDDEN;
        }

        return selection.getCount() == 1 ? DISABLED : ENABLED;
    }

    /**
     * Tests the represented structure menu in order for a single eligible selected builder.
     */
    private int evaluateStructureMenu(@NotNull InterfaceSelection selection,
                                      int player,
                                      @NotNull Unit active,
                                      int @NotNull [] structures,
                                      boolean terran) {
        if (selection.getCount() != 1) {
            return HIDDEN;
        }

        if (terran) {
            if (this.evaluateAnySelectedUnit(selection, ButtonConditionEvaluator::isNotConstructing) == HIDDEN) {
                return HIDDEN;
            }
        } else if (structures == ZERG_BASIC || structures == ZERG_ADVANCED) {
            if (isBurrowed(active)) {
                return HIDDEN;
            }
        }

        for (int structure : structures) {
            if (this.requirements.evaluateUnit(structure, player, active) != HIDDEN) {
                return ENABLED;
            }
        }

        return HIDDEN;
    }
}
