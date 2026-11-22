package org.fogofwarlabs.sc4j.scr.client.ui.command;

import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.content.*;
import org.fogofwarlabs.sc4j.scr.data.type.ScrOrderTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.WeaponTargetFlags;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitSpells;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.presentation.CommandErrorEvents;
import org.fogofwarlabs.sc4j.scr.game.state.GameCheats;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitRacesUtils;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Evaluates local right-click and targeting-click errors for the selected units.
 * Right-click tests walk selection slots in reverse until a unit accepts. Targeting tests walk forward and retain
 * acceptance, energy and error state before choosing the reported text, sound and send decision.
 * <p>
 * Every test uses one error convention: the error starts as {@link CommandErrorEvents#NO_ERROR_TEXT}, a test that accepts
 * leaves it unchanged and a test that rejects records its {@code stat_txt} id.
 */
public final class TargetValidation {
    /**
     * stat_txt {@code 876} STR_CANT_TARGET "Invalid target."
     */
    public static final int INVALID_TARGET = 876;
    /**
     * stat_txt {@code 877} STR_CANT_TARGET_BLDG "Unable to target structure."
     */
    public static final int CANNOT_TARGET_BUILDING = 877;
    /**
     * stat_txt {@code 879} STR_CANNOT_ATTACK_ERR "Unable to attack target."
     */
    public static final int CANNOT_ATTACK = 879;
    /**
     * stat_txt {@code 888} STR_OUT_OF_RANGE_ERR "Target out of range."
     */
    public static final int OUT_OF_RANGE = 888;
    /**
     * stat_txt {@code 889} STR_TOO_CLOSE_ERR "Target is too close."
     */
    public static final int TOO_CLOSE = 889;
    /**
     * stat_txt {@code 893} + race STR_HARVEST2_ERR "Morph an Extractor there first." (Zerg first).
     */
    public static final int HARVEST_GEYSER = 893;
    /**
     * stat_txt {@code 900} STR_STASISED_ERR "Units in stasis can't be targeted."
     */
    public static final int STASIS_TARGET = 900;
    /**
     * The race sound of the target errors (key {@code SND_Z_CANT_ATTACK} + race).
     */
    public static final @NotNull String CANT_ATTACK_SOUND = "SND_Z_CANT_ATTACK";
    /**
     * stat_txt {@code 864} + race "Not enough energy."
     */
    public static final int NOT_ENOUGH_ENERGY = 864;
    /**
     * stat_txt {@code 870} "Must target severely damaged Terran Command Center."
     */
    public static final int MUST_TARGET_COMMAND_CENTER = 870;
    /**
     * stat_txt {@code 878} "Must target units."
     */
    public static final int MUST_TARGET_UNITS = 878;
    /**
     * stat_txt {@code 883}, a unit that cannot be repaired.
     */
    public static final int CANNOT_REPAIR = 883;
    /**
     * stat_txt {@code 884}, repair of a non-Terran target.
     */
    public static final int MUST_TARGET_TERRAN = 884;
    /**
     * stat_txt {@code 892}, gathering from something that is not a resource.
     */
    public static final int MUST_TARGET_RESOURCE = 892;
    /**
     * stat_txt {@code 896}, gathering from another player's refinery.
     */
    public static final int OWN_REFINERY = 896;
    /**
     * stat_txt {@code 897}, the Consume target.
     */
    public static final int CONSUME_TARGET = 897;
    /**
     * stat_txt {@code 1325}, the Heal target.
     */
    public static final int MUST_TARGET_BIOLOGICAL = 1325;
    /**
     * stat_txt {@code 1327} "Must target enemy units" (Mind Control).
     */
    public static final int MUST_TARGET_ENEMY = 1327;
    /**
     * stat_txt {@code 1330} "Must target units with energy." (Feedback).
     */
    public static final int MUST_TARGET_ENERGY = 1330;
    /**
     * The energy error sound (race key insufficientEnergy, default SND_Z_NEED_MANA) + race.
     */
    public static final @NotNull String NEED_ENERGY_SOUND = "SND_Z_NEED_MANA";
    /**
     * The generic targeting-error sound key, with the reporting unit race added by the caller.
     */
    public static final @NotNull String ERROR_SOUND = "SND_Z_ERROR";

    private static final int ERROR_RESULT_INDEX = 0;

    private static final int FIRST_TERRAN_BUILDING = 0x6A;
    private static final int LAST_TERRAN_BUILDING = 0x7F;
    /**
     * units.dat special ability flags: building, invincible, mechanical.
     */
    private static final int BUILDING_ABILITY = UnitTypeFlags.BUILDING;
    private static final int UNTARGETABLE_TYPE = UnitTypeFlags.INVINCIBLE;
    private static final int MECHANICAL_ABILITY = UnitTypeFlags.MECHANICAL;
    /**
     * weapons.dat target flag {@code 0x40}: the weapon can target the ground.
     */
    private static final int GROUND_TARGET_FLAG = WeaponTargetFlags.TERRAIN;
    /**
     * The The Gathering cheat bit.
     */
    private static final int UNLIMITED_ENERGY_CHEAT = GameCheats.THE_GATHERING;
    private static final int ENERGY_FRACTION_BITS = 8;

    /**
     * The right-click actions of units.dat.
     */
    private static final int ACTION_NONE = ScrUnitTypeView.RIGHT_CLICK_NONE;
    private static final int ACTION_NORMAL = ScrUnitTypeView.RIGHT_CLICK_MOVE_AND_ATTACK;
    private static final int ACTION_ATTACK_ONLY = ScrUnitTypeView.RIGHT_CLICK_ATTACK_NO_MOVE;
    private static final int ACTION_HARVEST = ScrUnitTypeView.RIGHT_CLICK_HARVEST;
    private static final int ACTION_HARVEST_REPAIR = ScrUnitTypeView.RIGHT_CLICK_HARVEST_AND_REPAIR;
    /**
     * The inherited right-click target exclusion mask; its raw value is the POWERUP ability bit.
     */
    private static final int INVINCIBLE_ABILITY = UnitTypeFlags.POWERUP;
    /**
     * The selection slots, walked from the last.
     */
    private static final int SELECTION_SLOTS = UnitSelection.MAXIMUM_SELECTED;

    /**
     * The outcome of a test: the error to show ({@link CommandErrorEvents#NO_ERROR_TEXT} for none), the race of the unit that
     * raised it and whether a unit of the selection accepts the target.
     */
    public static final class Outcome {
        private final int error;
        private final int race;
        private final boolean accepted;

        Outcome(int error, int race, boolean accepted) {
            this.error    = error;
            this.race     = race;
            this.accepted = accepted;
        }

        /**
         * @return the one-based {@code stat_txt} id of the error, or {@link CommandErrorEvents#NO_ERROR_TEXT}
         */
        public int getError() {
            return this.error;
        }

        /**
         * @return the race added to the error sound (of the unit that raised the error)
         */
        public int getRace() {
            return this.race;
        }

        /**
         * @return {@code true} if a selected unit accepts the target, {@code false} otherwise
         */
        public boolean isAccepted() {
            return this.accepted;
        }
    }

    /**
     * The result of a targeting click.
     */
    public static final class Click {
        private final int error;
        private final @NotNull String sound;
        private final int race;
        private final boolean sent;

        Click(int error, @NotNull String sound, int race, boolean sent) {
            this.error = error;
            this.sound = sound;
            this.race  = race;
            this.sent  = sent;
        }

        /**
         * @return the one-based {@code stat_txt} id to show, or {@link CommandErrorEvents#NO_ERROR_TEXT} when the order is
         * acknowledged
         */
        public int getError() {
            return this.error;
        }

        /**
         * @return the sfx key of the error sound (plus {@link #getRace()})
         */
        public @NotNull String getSound() {
            return this.sound;
        }

        /**
         * @return the race added to the sound
         */
        public int getRace() {
            return this.race;
        }

        /**
         * @return {@code true} if the targeted order is sent, {@code false} otherwise
         */
        public boolean isSent() {
            return this.sent;
        }
    }

    /**
     * Walks selected units from the last slot and returns immediately when a unit accepts the right click.
     * If no unit accepts, the last recorded error and its unit race are returned.
     *
     * @param view      the game view
     * @param selection the local selection
     * @param target    the clicked unit, or {@code null}
     * @return the outcome
     * @throws NullPointerException if the world was built without game data
     */
    public static @NotNull Outcome testRightClick(@NotNull ScrGameView view, @NotNull InterfaceSelection selection, @Nullable Unit target) {
        int error = CommandErrorEvents.NO_ERROR_TEXT;
        int race = 0;

        for (int slot = SELECTION_SLOTS - 1; slot >= 0; slot--) {
            Unit unit = selection.getUnit(slot);

            if (unit == null) {
                continue;
            }

            int action = view.getUnitQueries().getRightClickClass(unit);

            if ((unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0 && (action == ACTION_HARVEST || action == ACTION_HARVEST_REPAIR)) {
                action = ACTION_NORMAL;
            }

            int unitError;

            if (action == ACTION_NONE) {
                continue;
            } else if (action == ACTION_ATTACK_ONLY) {
                if (target == null || isBeacon(view, target) || isInvincibleType(view, target)) {
                    continue;
                }

                unitError = getAttackError(view, unit, target);
            } else if (action == ACTION_HARVEST || action == ACTION_HARVEST_REPAIR) {
                if (target == null || isInvincibleType(view, target)) {
                    return new Outcome(CommandErrorEvents.NO_ERROR_TEXT, 0, true);
                }

                unitError = target.getUnitId() == ScrUnitIds.RESOURCE_VESPENE_GEYSER
                            ? HARVEST_GEYSER + UnitRacesUtils.getDisplayRace(view.requireGameData().getUnitTypes(), unit.getUnitId())
                            : getNormalError(view, unit, target);
            } else if (action == ACTION_NORMAL) {
                unitError = getNormalError(view, unit, target);
            } else {
                return new Outcome(CommandErrorEvents.NO_ERROR_TEXT, 0, true);
            }

            if (unitError == CommandErrorEvents.NO_ERROR_TEXT) {
                return new Outcome(CommandErrorEvents.NO_ERROR_TEXT, 0, true);
            }

            error = unitError;
            race  = UnitRacesUtils.getDisplayRace(view.requireGameData().getUnitTypes(), unit.getUnitId());
        }

        return new Outcome(error, race, false);
    }

    /**
     * Tests enabled selected units in slot order for weapon, technology or ordinary order targeting.
     * Retained acceptance, energy and error state determine the final error text, sound race and whether the order is sent.
     *
     * @param view      the game view
     * @param selection the local selection
     * @param order     the order of the click (unit target or ground order)
     * @param target    the clicked unit, or {@code null}
     * @param fogType   the clicked unit type for orders with a fog type (orders.dat Obscured not {@code 0xBD}), else {@code 0xE4}
     * @param positionX the map x
     * @param positionY the map y
     * @return the result
     * @throws NullPointerException if the world was built without game data
     */
    public static @NotNull Click testTargetClick(@NotNull ScrGameView view,
                                                 @NotNull InterfaceSelection selection,
                                                 int order,
                                                 @Nullable Unit target, int fogType,
                                                 int positionX, int positionY) {
        ScrOrderTypeView orders = view.requireGameData().getOrderTypes();
        boolean weaponTargeting = orders.getUseWeaponTargeting(order) != 0;
        int weapon = orders.getWeapon(order);
        int technology = orders.getTechnology(order);
        int energy = technology < ScrTechIds.NONE ? view.requireGameData().getTechTypes().getEnergyCost(technology) << ENERGY_FRACTION_BITS : 0;
        boolean unlimited = (view.getCheatFlags() & UNLIMITED_ENERGY_CHEAT) != 0;
        int[] error = {CommandErrorEvents.NO_ERROR_TEXT};
        boolean accepted = false;
        boolean energized = false;

        for (int slot = 0; slot < SELECTION_SLOTS; slot++) {
            Unit unit = selection.getUnit(slot);

            if (unit == null || UnitOrderEligibility.isDisabled(unit)) {
                continue;
            }

            boolean unitAccepted;

            if (weaponTargeting && weapon == ScrWeaponIds.NONE) {
                if (target == null) {
                    unitAccepted = unit.getUnitId() == ScrUnitIds.ZERG_INFESTED_TERRAN || acceptsGround(view, unit, fogType);
                } else if (view.getUnitQueries().getRightClickClass(unit) == ACTION_ATTACK_ONLY) {
                    if (view.getUnitTargeting().isOutOfMaximumRange(unit, target)) {
                        error[ERROR_RESULT_INDEX] = OUT_OF_RANGE;

                        unitAccepted = false;
                    } else if (isTooClose(view, unit, target)) {
                        error[ERROR_RESULT_INDEX] = TOO_CLOSE;

                        unitAccepted = false;
                    } else {
                        unitAccepted = view.getUnitTargeting().canAttackTarget(unit, target);
                    }
                } else {
                    unitAccepted = view.getUnitTargeting().canAttackTarget(unit, target);
                }
            } else if (weaponTargeting) {
                unitAccepted = (target == null
                                ? (view.requireGameData().getWeaponTypes().getTargetFlags(weapon) & GROUND_TARGET_FLAG) != 0
                                : view.getUnitTargeting().canWeaponTarget(weapon, target)) ||
                               fogType < ScrUnitIds.NONE && (view.getUnitQueries().getSpecialAbilityFlags(fogType) & UNTARGETABLE_TYPE) == 0;

                if (unitAccepted) {
                    energized |= unlimited || unit.getEnergy() >= energy;
                }
            } else if (technology < ScrTechIds.NONE) {
                unitAccepted = validateTechnology(view, unit, technology, target, fogType, positionX, positionY, error);

                if (unitAccepted) {
                    energized |= unlimited || unit.getEnergy() >= energy;
                }
            } else {
                validateOrder(view, unit, order, target, fogType, error);

                unitAccepted = false;
            }

            accepted |= unitAccepted;
        }

        Unit active = selection.getActiveUnit();
        int race = active == null ? 0 : UnitRacesUtils.getDisplayRace(view.requireGameData().getUnitTypes(), active.getUnitId());

        if (weaponTargeting) {
            if (accepted && error[ERROR_RESULT_INDEX] == CommandErrorEvents.NO_ERROR_TEXT) {
                if (energy != 0 && !energized) {
                    return new Click(NOT_ENOUGH_ENERGY + race, NEED_ENERGY_SOUND, race, false);
                }

                return new Click(CommandErrorEvents.NO_ERROR_TEXT, "", race, true);
            }

            int shown = error[ERROR_RESULT_INDEX];

            if (target != null && (target.getStatus() & UnitStatusFlags.INVINCIBLE) != 0) {
                shown = target.getStasisTimer() != 0 ? STASIS_TARGET
                                                     : UnitQueries.isGroundedBuilding(target) ? CANNOT_TARGET_BUILDING
                                                                                              : INVALID_TARGET;
            } else {
                if (shown == CommandErrorEvents.NO_ERROR_TEXT) {
                    shown = CANNOT_ATTACK;
                }

                if (weapon != ScrWeaponIds.NONE) {
                    shown = view.requireGameData().getWeaponTypes().getTargetErrorMessage(weapon);
                }
            }

            return new Click(shown, CANT_ATTACK_SOUND, race, weapon == ScrWeaponIds.NONE);
        }

        if (energy != 0) {
            if (!accepted) {
                return new Click(error[ERROR_RESULT_INDEX] == CommandErrorEvents.NO_ERROR_TEXT ? INVALID_TARGET : error[ERROR_RESULT_INDEX],
                                 ERROR_SOUND, race, false);
            }

            if (!energized) {
                return new Click(NOT_ENOUGH_ENERGY + race, NEED_ENERGY_SOUND, race, false);
            }
        }

        if (error[ERROR_RESULT_INDEX] != CommandErrorEvents.NO_ERROR_TEXT) {
            return new Click(error[ERROR_RESULT_INDEX], ERROR_SOUND, race, false);
        }

        return new Click(CommandErrorEvents.NO_ERROR_TEXT, "", race, true);
    }

    /**
     * Checks whether a fog type and the selected unit allow a ground targeting click.
     * Eligible special units bypass the ordinary ground-weapon lookup; a Lurker must be burrowed.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private static boolean acceptsGround(@NotNull ScrGameView view, @NotNull Unit unit, int fogType) {
        if (fogType >= ScrUnitIds.NONE || (view.getUnitQueries().getSpecialAbilityFlags(fogType) & UNTARGETABLE_TYPE) != 0) {
            return false;
        }

        int type = unit.getUnitId();

        if (view.requireGameData().getUnitTypes().hasHangar(type)) {
            return true;
        }

        if (type == ScrUnitIds.ZERG_LURKER) {
            return (unit.getStatus() & UnitStatusFlags.BURROWED) != 0;
        }

        ScrUnitTypeView units = view.requireGameData().getUnitTypes();

        if (units.getGroundWeapon(type) != ScrWeaponIds.NONE) {
            return true;
        }

        Unit subunit = unit.getSubunit();

        return subunit != null && units.getGroundWeapon(subunit.getUnitId()) != ScrWeaponIds.NONE;
    }

    /**
     * Applies the technology validator ({@link UnitSpells#getTechnologyTargetError}): a valid target is accepted and leaves
     * the click error unchanged, an invalid one records the validator's text as the click error.
     */
    static boolean validateTechnology(@NotNull ScrGameView view,
                                      @NotNull Unit unit,
                                      int technology,
                                      @Nullable Unit target, int fogType,
                                      int positionX, int positionY,
                                      int @NotNull [] error) {
        return recordError(error, view.getUnitSpells().getTechnologyTargetError(unit, technology, target, fogType, positionX, positionY));
    }

    /**
     * Records Repair, Gather and Heal target errors without selecting an accepting unit.
     * Fog-type repair and gather tests run before unit-target order branches.
     *
     * @throws NullPointerException if the world was built without game data
     */
    static void validateOrder(@NotNull ScrGameView view, @NotNull Unit unit, int order, @Nullable Unit target, int fogType, int @NotNull [] error) {
        if (target == null && fogType < ScrUnitIds.NONE) {
            if (order == ScrOrderIds.MOVE_TO_REPAIR && !(fogType >= FIRST_TERRAN_BUILDING && fogType <= LAST_TERRAN_BUILDING)) {
                error[ERROR_RESULT_INDEX] = MUST_TARGET_TERRAN;
            } else if (order == ScrOrderIds.HARVEST2) {
                recordError(error, getGatherTypeError(view, unit, fogType));
            }

            return;
        }

        switch (order) {
            case ScrOrderIds.REPAIR -> {
                if (target == null || UnitRacesUtils.getSupplyRace(view.requireGameData().getUnitTypes(), target.getUnitId()) != ScrRaceIds.TERRAN) {
                    error[ERROR_RESULT_INDEX] = MUST_TARGET_TERRAN;
                } else {
                    boolean mechanical = (view.getUnitQueries().getSpecialAbilityFlags(target.getUnitId()) & MECHANICAL_ABILITY) != 0;
                    boolean completed = (target.getStatus() & UnitStatusFlags.COMPLETED) != 0;
                    boolean damaged = target.getHitPoints() < view.requireGameData().getUnitTypes().getHitPoints(target.getUnitId());

                    if (!mechanical || !completed || !damaged) {
                        error[ERROR_RESULT_INDEX] = CANNOT_REPAIR;
                    }
                }
            }
            case ScrOrderIds.HARVEST1 -> {
                if (target == null) {
                    return;
                }

                int type = target.getUnitId();

                if (type == ScrUnitIds.RESOURCE_VESPENE_GEYSER) {
                    error[ERROR_RESULT_INDEX] = HARVEST_GEYSER + UnitRacesUtils.getDisplayRace(view.requireGameData().getUnitTypes(), unit.getUnitId());
                } else if (view.requireGameData().getUnitTypes().isMineralField(type)) {
                    return;
                } else if (!view.requireGameData().getUnitTypes().isGasStructure(type)) {
                    error[ERROR_RESULT_INDEX] = MUST_TARGET_RESOURCE;
                } else if (target.getOwnerIndex() != unit.getOwnerIndex()) {
                    error[ERROR_RESULT_INDEX] = OWN_REFINERY;
                }
            }
            case ScrOrderIds.HEAL_MOVE -> {
                if (target == null || view.getUnitTargeting().isEnemy(unit, target)) {
                    return;
                }

                int abilities = view.getUnitQueries().getSpecialAbilityFlags(target.getUnitId());

                if ((abilities & (MECHANICAL_ABILITY | BUILDING_ABILITY)) != 0) {
                    error[ERROR_RESULT_INDEX] = MUST_TARGET_BIOLOGICAL;
                }
            }
            default -> {
            }
        }
    }

    private static int getGatherTypeError(@NotNull ScrGameView view, @NotNull Unit unit, int fogType) {
        if (fogType == ScrUnitIds.RESOURCE_VESPENE_GEYSER) {
            return HARVEST_GEYSER + UnitRacesUtils.getDisplayRace(view.requireGameData().getUnitTypes(), unit.getUnitId());
        }

        if (view.requireGameData().getUnitTypes().isMineralField(fogType)) {
            return CommandErrorEvents.NO_ERROR_TEXT;
        }

        int refinery = switch (UnitRacesUtils.getDisplayRace(view.requireGameData().getUnitTypes(), unit.getUnitId())) {
            case 0 -> ScrUnitIds.ZERG_EXTRACTOR;
            case 1 -> ScrUnitIds.TERRAN_REFINERY;
            default -> ScrUnitIds.PROTOSS_ASSIMILATOR;
        };

        return fogType == refinery ? CommandErrorEvents.NO_ERROR_TEXT : MUST_TARGET_RESOURCE;
    }

    /**
     * Records the text of a rejecting test in the error slot; an accepting test leaves the slot unchanged.
     *
     * @param error      the error slot
     * @param identifier the text of the test, or {@link CommandErrorEvents#NO_ERROR_TEXT} when it accepts
     * @return {@code true} if the test accepts, {@code false} otherwise
     */
    private static boolean recordError(int @NotNull [] error, int identifier) {
        if (identifier == CommandErrorEvents.NO_ERROR_TEXT) {
            return true;
        }

        error[ERROR_RESULT_INDEX] = identifier;

        return false;
    }

    /**
     * Accepts a ground click, nonenemy, beacon or excluded target type before testing ordinary attack eligibility.
     */
    private static int getNormalError(@NotNull ScrGameView view, @NotNull Unit unit, @Nullable Unit target) {
        if (target == null || !view.getUnitTargeting().isEnemy(unit, target) || isBeacon(view, target) || isInvincibleType(view, target)) {
            return CommandErrorEvents.NO_ERROR_TEXT;
        }

        return view.getUnitTargeting().canAttackTarget(unit, target) ? CommandErrorEvents.NO_ERROR_TEXT : CANNOT_ATTACK;
    }

    private static int getAttackError(@NotNull ScrGameView view, @NotNull Unit unit, @NotNull Unit target) {
        if (!view.getUnitTargeting().canAttackTarget(unit, target)) {
            return CANNOT_ATTACK;
        }

        if (view.getUnitTargeting().isOutOfMaximumRange(unit, target)) {
            return OUT_OF_RANGE;
        }

        return isTooClose(view, unit, target) ? TOO_CLOSE : CommandErrorEvents.NO_ERROR_TEXT;
    }

    /**
     * Tests a nonzero minimum range of the attacking unit or subunit weapon against the target.
     *
     * @throws NullPointerException if the world was built without game data
     */
    static boolean isTooClose(@NotNull ScrGameView view, @NotNull Unit unit, @NotNull Unit target) {
        int weapon = view.getUnitTargeting().getTargetWeapon(unit, target);

        if (weapon == ScrWeaponIds.NONE) {
            return false;
        }

        int minimumRange = view.requireGameData().getWeaponTypes().getMinimumRange(weapon);

        return minimumRange != 0 && view.getUnitTargeting().isInRange(view.getUnitTargeting().getAttackingUnit(unit), target, minimumRange);
    }

    private static boolean isBeacon(@NotNull ScrGameView view, @NotNull Unit target) {
        return view.requireGameData().getUnitTypes().isBeacon(target.getUnitId());
    }

    private static boolean isInvincibleType(@NotNull ScrGameView view, @NotNull Unit target) {
        return (view.getUnitQueries().getSpecialAbilityFlags(target.getUnitId()) & INVINCIBLE_ABILITY) != 0;
    }

    private TargetValidation() {
    }
}
