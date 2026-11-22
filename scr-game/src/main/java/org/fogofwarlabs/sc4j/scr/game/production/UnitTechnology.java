package org.fogofwarlabs.sc4j.scr.game.production;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.SoundIds;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;

/**
 * Decides technology use and applies the instant technology commands.
 * <p>
 * Technology identifiers are {@code techdata.dat} indices. Commands use the current selection and requirement scripts.
 */
public final class UnitTechnology {
    /**
     * The hit points (whole points) a stim pack costs; a unit needs more than this to use one.
     */
    private static final int STIM_PACK_DAMAGE = 10;
    /**
     * The stim timer a stim pack starts or extends to.
     */
    private static final int STIM_PACK_TIMER = 37;
    /**
     * The random source of the stim pack sound.
     */
    private static final int STIM_PACK_SOUND_RANDOM_SOURCE = 31;
    /**
     * The fractional bits of unit energy and hit points.
     */
    private static final int FIXED_POINT_SHIFT = 8;

    private final @NotNull Game game;

    /**
     * Creates a {@link UnitTechnology} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitTechnology(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Applies the technology-use requirement script ({@link ScrRequirementSystem#checkTechnologyUse}) for the unit's owner.
     * <p>
     * An out-of-range technology identifier returns {@code false} before any game-data read.
     *
     * @param unit       the unit
     * @param technology the {@code techdata.dat} index
     * @return {@code true} if the unit may use the technology, {@code false} otherwise
     */
    public boolean canUseTechnology(@NotNull Unit unit, int technology) {
        return this.game.getRequirements().checkTechnologyUse(unit, technology, unit.getOwnerIndex()) == 0;
    }


    /**
     * Releases the Nuclear Missiles of selected Ghosts.
     *
     * @param owner the command player
     * @return {@code true} if any Ghost released a missile, {@code false} otherwise
     * @throws UnsupportedOperationException if selection storage is absent
     */
    public boolean cancelNuke(int owner) {
        UnitSelection selection = this.game.requireSelection();
        boolean accepted = false;

        for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
            Unit unit = selection.getSelectedUnit(owner, position);

            if (unit == null) {
                break;
            }

            if (unit.isDestroyed()) {
                continue;
            }

            Unit missile = unit.getParentUnit();

            if (!this.game.requireGameData().getUnitTypes().isGhost(unit.getUnitId()) || missile == null ||
                this.game.getBaseType(missile) != ScrUnitIds.TERRAN_NUCLEAR_MISSILE) {
                continue;
            }

            missile.setParentUnit(null);
            unit.setParentUnit(null);

            accepted = true;
        }

        return accepted;
    }

    /**
     * Issues {@link ScrOrderIds#SIEGING} or {@link ScrOrderIds#UNSIEGING} to each selected tank of the command player in the opposite mode that may
     * use {@link ScrTechIds#TANK_SIEGE_MODE}.
     *
     * @param owner            the command player
     * @param siege            {@code true} for Siege Mode, {@code false} for Tank Mode
     * @param queue            whether to append the order
     * @param instructionLimit the order budget
     * @return {@code true} if any selected tank received the order, {@code false} otherwise
     * @throws UnsupportedOperationException if selection storage is absent
     */
    public boolean siege(int owner, boolean siege, boolean queue, int instructionLimit) {
        UnitSelection selection = this.game.requireSelection();
        int order = siege ? ScrOrderIds.SIEGING : ScrOrderIds.UNSIEGING;
        boolean accepted = false;

        for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
            Unit unit = selection.getSelectedUnit(owner, position);

            if (unit == null) {
                break;
            }

            if (unit.isDestroyed()) {
                continue;
            }

            int type = unit.getUnitId();
            boolean eligibleType = siege
                                   ? type == ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE || type == ScrUnitIds.HERO_EDMUND_DUKE_TANK_MODE
                                   : type == ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE || type == ScrUnitIds.HERO_EDMUND_DUKE_SIEGE_MODE;

            if (unit.getOwnerIndex() != owner || !eligibleType || unit.getPrimaryOrder() == order ||
                !this.canUseTechnology(unit, ScrTechIds.TANK_SIEGE_MODE)) {
                continue;
            }

            this.game.getOrders().issue(unit, order, null, 0, 0, ScrUnitIds.NONE, queue, instructionLimit);

            accepted = true;
        }

        return accepted;
    }

    /**
     * Starts the {@link ScrOrderIds#CLOAK} secondary order of eligible selected units after paying their cloaking technology energy cost, or starts
     * the {@link ScrOrderIds#DECLOAK} secondary order.
     *
     * @param owner the command player
     * @param cloak {@code true} to cloak, {@code false} to decloak
     * @throws UnsupportedOperationException if selection storage is absent
     */
    public void cloak(int owner, boolean cloak) {
        UnitSelection selection = this.game.requireSelection();

        for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
            Unit unit = selection.getSelectedUnit(owner, position);

            if (unit == null) {
                break;
            }

            if (unit.isDestroyed()) {
                continue;
            }

            if (unit.getOwnerIndex() != owner) {
                continue;
            }

            int technology = UnitQueries.getCloakTechnology(this.game.requireGameData().getUnitTypes(), unit);

            if (technology == ScrTechIds.NONE || !this.canUseTechnology(unit, technology)) {
                continue;
            }

            if (cloak) {
                this.cloakUnit(unit);
            } else {
                ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.DECLOAK);
            }
        }
    }

    /**
     * Starts the {@link ScrOrderIds#CLOAK} secondary order of a unit that does not already require a detector and has enough energy to pay for its
     * cloaking technology.
     *
     * @param unit the unit with cloaking technology
     * @throws IllegalStateException if the unit has no cloaking technology
     * @throws NullPointerException  if the world was built without game data
     */
    public void cloakUnit(@NotNull Unit unit) {
        if ((unit.getStatus() & UnitStatusFlags.REQUIRES_DETECTOR) != 0) {
            return;
        }

        int technology = UnitQueries.getCloakTechnology(this.game.requireGameData().getUnitTypes(), unit);

        if (technology == ScrTechIds.NONE) {
            throw Exceptions.newIllegalStateException("A unit without cloaking technology cannot start cloaking: {0}", unit.getUnitId());
        }

        int cost = this.game.requireGameData().getTechTypes().getEnergyCost(technology) << FIXED_POINT_SHIFT;

        if (!this.game.getUnitEnergy().hasEnergy(unit, cost)) {
            return;
        }

        this.game.getUnitEnergy().spend(unit, cost);
        this.reportSelfCast(unit, technology);

        ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.CLOAK);
    }

    /**
     * Applies a stim pack to each eligible selected Marine or Firebat with more than {@code 10} hit points: it loses {@code 10} hit points and starts
     * or extends its stim timer.
     * <p>
     * On the current unit, random draws and sound precede damage.
     *
     * @param owner            the command player
     * @param instructionLimit the damage and speed recalculation budget
     * @return {@code true} if any selected unit used the stim pack, {@code false} otherwise
     * @throws UnsupportedOperationException if selection storage is absent
     */
    public boolean stimPack(int owner, int instructionLimit) {
        UnitSelection selection = this.game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Stim packs require initialized selection storage.");
        }

        boolean accepted = false;

        for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
            Unit unit = selection.getSelectedUnit(owner, position);

            if (unit == null) {
                break;
            }

            if (unit.isDestroyed()) {
                continue;
            }

            if (unit.getOwnerIndex() != owner || !this.canUseTechnology(unit, ScrTechIds.STIM_PACKS)) {
                continue;
            }

            if (this.stimUnit(unit, instructionLimit)) {
                accepted = true;
            }
        }

        return accepted;
    }

    /**
     * Applies the single-unit stim pack: a unit with more than {@code 10} hit points picks a stim sound with the synchronized random number
     * generator, takes {@code 10} damage and starts or extends its stim timer to {@code 37}.
     * <p>
     * Insufficient hit points return {@code false} before random draws or game-data reads. Random draws and sound precede damage.
     *
     * @param unit             the Marine or Firebat
     * @param instructionLimit the damage and speed recalculation budget
     * @return {@code true} if the stim pack was used, {@code false} otherwise
     */
    public boolean stimUnit(@NotNull Unit unit, int instructionLimit) {
        if (unit.getHitPoints() <= STIM_PACK_DAMAGE << FIXED_POINT_SHIFT) {
            return false;
        }

        int sound = this.game.getRandom().next(STIM_PACK_SOUND_RANDOM_SOURCE, SoundIds.MARINE_STIM_PACK, SoundIds.MARINE_STIM_PACK + 1);
        Sprite sprite = unit.getSpriteOrNull();

        this.game.getInterfaceLink().getSoundEvents().add(sound,
                                                          sprite == null ? 0 : sprite.getX(), sprite == null ? 0 : sprite.getY(),
                                                          unit.getUnitId());
        this.reportSelfCast(unit, ScrTechIds.STIM_PACKS);
        this.game.getEngineGame().getCombat().dealDamage(unit, STIM_PACK_DAMAGE << FIXED_POINT_SHIFT, null, instructionLimit);

        if (unit.getSpeedIncreaseByte() < STIM_PACK_TIMER) {
            unit.setSpeedIncreaseByte(STIM_PACK_TIMER);
            this.game.getEngineGame().getMovement().recalculateSpeed(unit, instructionLimit);
        }

        return true;
    }

    /**
     * Reports a technology a unit used on itself to the simulation plugins ({@link ScrSimulationPlugins#reportSpellCast}), at the unit's
     * position, when the game has them.
     *
     * @param unit       the unit
     * @param technology the techdata.dat entry
     */
    private void reportSelfCast(@NotNull Unit unit, int technology) {
        ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

        if (simulation != null) {
            Sprite sprite = unit.getSpriteOrNull();

            simulation.reportSpellCast(unit, technology, null, sprite == null ? 0 : sprite.getX(), sprite == null ? 0 : sprite.getY());
        }
    }
}
