package org.fogofwarlabs.sc4j.scr.game.unit;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUpgradeIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrOrderTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.*;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;

/**
 * Transfers a unit between players while keeping accounting, enemy targeting and upgrade state consistent.
 */
public final class UnitOwnership {
    private static final int @NotNull [] NO_IDENTIFIERS = {};

    /**
     * Returns the technologies a receiving player gains with a unit type.
     *
     * @param unitType the unit type, the base type of an added type ({@link Game#getBaseType(int)})
     * @return the technology identifiers, in grant order
     */
    private static int @NotNull [] getGrantedTechnologies(int unitType) {
        return switch (ScrUnitIds.requireValidOrAdded(unitType)) {
            case ScrUnitIds.TERRAN_MARINE, ScrUnitIds.TERRAN_FIREBAT -> new int[]{ScrTechIds.STIM_PACKS};
            case ScrUnitIds.TERRAN_GHOST -> new int[]{ScrTechIds.LOCKDOWN, ScrTechIds.PERSONNEL_CLOAKING};
            case ScrUnitIds.TERRAN_VULTURE -> new int[]{ScrTechIds.SPIDER_MINES};
            case ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE, ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE -> new int[]{ScrTechIds.TANK_SIEGE_MODE};
            case ScrUnitIds.TERRAN_WRAITH -> new int[]{ScrTechIds.CLOAKING_FIELD};
            case ScrUnitIds.TERRAN_SCIENCE_VESSEL -> new int[]{ScrTechIds.DEFENSIVE_MATRIX, ScrTechIds.IRRADIATE, ScrTechIds.EMP_SHOCKWAVE};
            case ScrUnitIds.TERRAN_BATTLECRUISER -> new int[]{ScrTechIds.YAMATO_GUN};
            case ScrUnitIds.TERRAN_MEDIC -> new int[]{ScrTechIds.HEALING, ScrTechIds.RESTORATION, ScrTechIds.OPTICAL_FLARE};
            case ScrUnitIds.ZERG_ZERGLING, ScrUnitIds.ZERG_DRONE, ScrUnitIds.ZERG_INFESTED_TERRAN -> new int[]{ScrTechIds.BURROWING};
            case ScrUnitIds.ZERG_HYDRALISK -> new int[]{ScrTechIds.BURROWING, ScrTechIds.LURKER_ASPECT};
            case ScrUnitIds.ZERG_QUEEN -> new int[]{ScrTechIds.INFESTATION, ScrTechIds.PARASITE, ScrTechIds.SPAWN_BROODLINGS,
                                                    ScrTechIds.ENSNARE};
            case ScrUnitIds.ZERG_DEFILER -> new int[]{ScrTechIds.BURROWING, ScrTechIds.DARK_SWARM, ScrTechIds.PLAGUE,
                                                      ScrTechIds.CONSUME};
            case ScrUnitIds.PROTOSS_CORSAIR -> new int[]{ScrTechIds.DISRUPTION_WEB};
            case ScrUnitIds.PROTOSS_DARK_TEMPLAR -> new int[]{ScrTechIds.DARK_ARCHON_MELD};
            case ScrUnitIds.PROTOSS_DARK_ARCHON -> new int[]{ScrTechIds.MIND_CONTROL, ScrTechIds.FEEDBACK, ScrTechIds.MAELSTROM};
            case ScrUnitIds.PROTOSS_HIGH_TEMPLAR -> new int[]{ScrTechIds.ARCHON_WARP, ScrTechIds.PSIONIC_STORM, ScrTechIds.HALLUCINATION};
            case ScrUnitIds.PROTOSS_ARBITER -> new int[]{ScrTechIds.RECALL, ScrTechIds.STASIS_FIELD};
            case ScrUnitIds.ZERG_LURKER -> new int[]{ScrTechIds.LURKER_ASPECT};
            default -> NO_IDENTIFIERS;
        };
    }

    /**
     * Returns the upgrades whose level a receiving player gains with a unit type.
     *
     * @param unitType the unit type, the base type of an added type ({@link Game#getBaseType(int)})
     * @return the upgrade identifiers, in grant order
     */
    private static int @NotNull [] getGrantedUpgrades(int unitType) {
        return switch (ScrUnitIds.requireValidOrAdded(unitType)) {
            case ScrUnitIds.TERRAN_MARINE -> new int[]{ScrUpgradeIds.U_238_SHELLS};
            case ScrUnitIds.TERRAN_GHOST -> new int[]{ScrUpgradeIds.OCULAR_IMPLANTS, ScrUpgradeIds.MOEBIUS_REACTOR};
            case ScrUnitIds.TERRAN_VULTURE -> new int[]{ScrUpgradeIds.ION_THRUSTERS};
            case ScrUnitIds.TERRAN_GOLIATH -> new int[]{ScrUpgradeIds.CHARON_BOOSTERS};
            case ScrUnitIds.TERRAN_WRAITH -> new int[]{ScrUpgradeIds.APOLLO_REACTOR};
            case ScrUnitIds.TERRAN_SCIENCE_VESSEL -> new int[]{ScrUpgradeIds.TITAN_REACTOR};
            case ScrUnitIds.TERRAN_BATTLECRUISER -> new int[]{ScrUpgradeIds.COLOSSUS_REACTOR};
            case ScrUnitIds.TERRAN_MEDIC -> new int[]{ScrUpgradeIds.CADUCEUS_REACTOR};
            case ScrUnitIds.ZERG_ZERGLING -> new int[]{ScrUpgradeIds.METABOLIC_BOOST, ScrUpgradeIds.ADRENAL_GLANDS};
            case ScrUnitIds.ZERG_HYDRALISK -> new int[]{ScrUpgradeIds.MUSCULAR_AUGMENTS, ScrUpgradeIds.GROOVED_SPINES};
            case ScrUnitIds.ZERG_ULTRALISK -> new int[]{ScrUpgradeIds.ANABOLIC_SYNTHESIS, ScrUpgradeIds.CHITINOUS_PLATING};
            case ScrUnitIds.ZERG_OVERLORD -> new int[]{ScrUpgradeIds.VENTRAL_SACS, ScrUpgradeIds.ANTENNAE, ScrUpgradeIds.PNEUMATIZED_CARAPACE};
            case ScrUnitIds.ZERG_QUEEN -> new int[]{ScrUpgradeIds.GAMETE_MEIOSIS};
            case ScrUnitIds.ZERG_DEFILER -> new int[]{ScrUpgradeIds.METASYNAPTIC_NODE};
            case ScrUnitIds.PROTOSS_CORSAIR -> new int[]{ScrUpgradeIds.ARGUS_JEWEL};
            case ScrUnitIds.PROTOSS_DARK_ARCHON -> new int[]{ScrUpgradeIds.ARGUS_TALISMAN};
            case ScrUnitIds.PROTOSS_ZEALOT -> new int[]{ScrUpgradeIds.LEG_ENHANCEMENTS};
            case ScrUnitIds.PROTOSS_DRAGOON -> new int[]{ScrUpgradeIds.SINGULARITY_CHARGE};
            case ScrUnitIds.PROTOSS_HIGH_TEMPLAR -> new int[]{ScrUpgradeIds.KHAYDARIN_AMULET};
            case ScrUnitIds.PROTOSS_SHUTTLE -> new int[]{ScrUpgradeIds.GRAVITIC_DRIVE};
            case ScrUnitIds.PROTOSS_SCOUT -> new int[]{ScrUpgradeIds.APIAL_SENSORS, ScrUpgradeIds.GRAVITIC_THRUSTERS};
            case ScrUnitIds.PROTOSS_ARBITER -> new int[]{ScrUpgradeIds.KHAYDARIN_CORE};
            case ScrUnitIds.PROTOSS_CARRIER -> new int[]{ScrUpgradeIds.CARRIER_CAPACITY};
            case ScrUnitIds.PROTOSS_REAVER -> new int[]{ScrUpgradeIds.SCARAB_DAMAGE, ScrUpgradeIds.REAVER_CAPACITY};
            case ScrUnitIds.PROTOSS_OBSERVER -> new int[]{ScrUpgradeIds.SENSOR_ARRAY, ScrUpgradeIds.GRAVITIC_BOOSTERS};
            default -> NO_IDENTIFIERS;
        };
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitOwnership} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitOwnership(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Sets the owner of a unit without changing its sprite owner. Nothing changes when the unit already has the owner.
     * <p>
     * The unit moves to the front of the new owner's unit list. An incomplete building that is morphing into another
     * building is counted as completed during the transfer. Orders that target enemies lose targets that became allied.
     * Protoss grounded buildings request the shared building power refresh. A turret follows its main unit. A carried Flag
     * is dropped when its worker passes to the Flag's owner or a non-playable player.
     *
     * @param unit             the transferred unit
     * @param owner            the new owner player
     * @param incrementScore   whether completed-unit production statistics are incremented for the new owner
     * @param instructionLimit the speed recalculation script budget
     * @throws UnsupportedOperationException if a required transfer context is unavailable or the unit pool is not initialized
     * @throws NullPointerException          if the world was built without game data
     */
    public void setOwner(@NotNull Unit unit, int owner, boolean incrementScore, int instructionLimit) {
        if (unit.getOwnerIndex() == owner) {
            return;
        }

        ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

        if (simulation != null) {
            simulation.reportOwnerChanged(unit, owner);
        }

        this.game.getEngineGame().getUnitLifecycle().notifyRemoved(unit, false);

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();
        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();
        PlayerRelations relations = this.game.getPlayerTable().getPlayerRelations();
        Sprite sprite = unit.getSpriteOrNull();

        if (accounting == null || context == null || relations == null || sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Ownership transfer requires accounting, creation context, alliances and the sprite.");
        }

        boolean morphing = UnitQueries.isBuildingMorph(units, unit);

        if (morphing) {
            unit.setStatus(unit.getStatus() | UnitStatusFlags.COMPLETED);
        }

        accounting.update(unit, units, -1, context.preservesNegativeTypeCounts());

        if ((unit.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
            accounting.updateCompleted(unit, units, -1, false, this.game.getPlayerTable().getProductionStatistics());
        }

        // A selected unit is selected again for its new owner's circle color.
        boolean selected = (sprite.getFlags() & Sprite.FLAG_SELECTED) != 0;
        int selectionIndex = sprite.getSelectionIndex();

        if (selected) {
            this.game.getSelectionImages().deselectUnit(unit);
        }

        this.game.getUnitSystem().requireUnitPool().moveToOwnerList(unit, owner);
        unit.setOwnerIndex(owner);

        if (selected) {
            this.game.getSelectionImages().selectUnit(unit, selectionIndex);
        }

        accounting.update(unit, units, 1, context.preservesNegativeTypeCounts());

        if ((unit.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
            accounting.updateCompleted(unit, units, 1, incrementScore, this.game.getPlayerTable().getProductionStatistics());
        }

        if (morphing) {
            unit.setStatus(unit.getStatus() & ~UnitStatusFlags.COMPLETED);
        }

        ScrOrderTypeView orders = this.game.requireGameData().getOrderTypes();
        Unit target = unit.getOrderTargetUnit();

        if (target != null && orders.getUseWeaponTargeting(unit.getPrimaryOrder()) != 0 && !this.game.getUnitTargeting().isEnemy(unit, target)) {
            unit.setOrderTargetUnit(null);
        }

        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int visibility = 0; visibility < UnitPool.CREATED_LIST_COUNT; visibility++) {
            for (int index = pool.getFirstCreatedIndex(visibility == 0); index != 0; index = pool.getNextCreatedIndex(index)) {
                Unit attacker = pool.getUnit(index);
                Unit subunit = attacker.getSubunit();

                if (subunit != null && (units.getFlags(subunit.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
                    attacker = subunit;
                }

                if (attacker.getOrderTargetUnit() == unit && orders.getUseWeaponTargeting(attacker.getPrimaryOrder()) != 0 &&
                    !this.game.getUnitTargeting().isEnemy(attacker, unit)) {
                    attacker.setOrderTargetUnit(null);
                }
            }
        }

        if ((units.getGroupFlags(unit.getUnitId()) & UnitGroupFlags.PROTOSS) != 0 && UnitQueries.isGroundedBuilding(unit)) {
            this.game.requestBuildingPowerRefresh();
        }

        // The new owner's computer AI takes the unit before the turret follows.
        this.game.getEngineGame().getUnitLifecycle().notifyAdopted(unit, instructionLimit);

        // The turret changes owner with its main unit.
        Unit turret = unit.getSubunit();

        if (turret != null && (units.getFlags(turret.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0 &&
            (units.getFlags(unit.getUnitId()) & UnitTypeFlags.SUBUNIT) == 0) {
            this.setOwner(turret, owner, incrementScore, instructionLimit);
        }

        this.game.getUnitSpeed().updateUpgradeFlags(unit, instructionLimit);

        this.game.getEngineGame().getUnitLifecycle().notifyOwnerChanged(unit, instructionLimit);
    }

    /**
     * Changes the owner of a unit with {@link #setOwner}, then sets the sprite owner of the unit and its turret.
     * <p>
     * The sprite owner is set even when the unit already has the owner and {@link #setOwner} changes nothing, so a
     * neutral unit whose sprite still has a former owner takes the neutral sprite owner.
     *
     * @param unit             the transferred unit
     * @param owner            the new owner player
     * @param incrementScore   whether completed-unit production statistics are incremented for the new owner
     * @param instructionLimit the speed recalculation script budget
     */
    public void changeOwner(@NotNull Unit unit, int owner, boolean incrementScore, int instructionLimit) {
        this.setOwner(unit, owner, incrementScore, instructionLimit);
        this.setSpriteOwner(unit, owner);
    }

    /**
     * Sets the sprite owner of a unit and of the subunits linked after it.
     * <p>
     * The walk follows the subunit links while the linked unit has a subunit type, so a turret's link back to its main
     * unit ends it. Each sprite takes the owner through {@link Sprite#updateOwner}, which marks its images for redrawing when the owner changes.
     *
     * @param unit  the unit
     * @param owner the new sprite owner
     * @throws IllegalStateException if the unit or a walked subunit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    private void setSpriteOwner(@NotNull Unit unit, int owner) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        Unit subunit = unit.getSubunit();

        unit.requireSprite().updateOwner(owner);

        while (subunit != null && (units.getFlags(subunit.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
            subunit.requireSprite().updateOwner(owner);
            subunit = subunit.getSubunit();
        }
    }


    /**
     * Gives an eligible unit and its attached units to another player.
     * <p>
     * A harvesting refinery worker is given first, followed by the unit, its passengers, hangar contents, add-on, incomplete add-on and linked Nydus
     * exit. Hallucinations, mineral fields, geysers, Interceptors, Scarabs and add-on types are rejected, as are invalid receiving players and units
     * already owned by the receiver.
     *
     * @param unit             the transferred unit
     * @param owner            the receiving player
     * @param instructionLimit the order and animation script budget
     * @return {@code true} if the unit was given, {@code false} otherwise
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    public boolean giveUnit(@NotNull Unit unit, int owner, int instructionLimit) {
        if (Integer.compareUnsigned(owner, PlayerTable.PLAYER_SLOTS) >= 0) {
            return false;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = unit.getUnitId();
        int baseType = this.game.getBaseType(type);
        int abilities = units.getFlags(type);

        if ((unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0 || units.isMineralField(type) ||
            type == ScrUnitIds.RESOURCE_VESPENE_GEYSER || baseType == ScrUnitIds.PROTOSS_INTERCEPTOR ||
            baseType == ScrUnitIds.PROTOSS_SCARAB || (abilities & UnitTypeFlags.ADDON) != 0 || unit.getOwnerIndex() == owner) {
            return false;
        }

        if ((unit.getStatus() & UnitStatusFlags.COMPLETED) != 0 && units.isGasStructure(type)) {
            UnitPool pool = this.game.getUnitSystem().requireUnitPool();

            for (int index = pool.getFirstOwnerIndex(unit.getOwnerIndex()); index != 0; index = pool.getNextOwnerIndex(index)) {
                Unit worker = pool.getUnit(index);
                Sprite workerSprite = worker.getSpriteOrNull();

                if (!this.game.getComputerUnits().isWorker(worker) || workerSprite == null ||
                    (workerSprite.getFlags() & Sprite.FLAG_HIDDEN) == 0 || worker.getPrimaryOrder() != ScrOrderIds.HARVEST_GAS ||
                    worker.getOrderTargetUnit() != unit) {
                    continue;
                }

                this.giveTo(worker, owner, instructionLimit);

                break;
            }
        }

        this.giveTo(unit, owner, instructionLimit);

        if (this.game.getUnitQueries().isTransport(unit)) {
            this.game.getUnitTransport().forEachLoadedUnit(unit, loaded -> {
                this.giveTo(loaded, owner, instructionLimit);

                return false;
            });
        }

        if (units.hasHangar(type)) {
            for (int side = 0; side < Unit.HANGAR_LIST_COUNT; side++) {
                for (Unit fighter = unit.getFirstHangarUnit(side == 1); fighter != null; fighter = fighter.getNextHangarUnit()) {
                    this.giveTo(fighter, owner, instructionLimit);
                }
            }
        }

        if (UnitQueries.isGroundedBuilding(unit)) {
            Unit addon = unit.getAddon();

            if (addon != null) {
                this.giveTo(addon, owner, instructionLimit);
            }

            Unit building = unit.getCurrentBuildUnit();

            if (unit.getSecondaryOrder() == ScrOrderIds.BUILD_ADDON && building != null &&
                (building.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
                this.giveTo(building, owner, instructionLimit);
            }

            Unit exit = type == ScrUnitIds.ZERG_NYDUS_CANAL ? unit.getNydusExit() : null;

            if (exit != null) {
                this.giveTo(exit, owner, instructionLimit);
            }
        }

        return true;
    }

    /**
     * Gives a unit to another player.
     * <p>
     * The receiving player gains the technologies and upgrade levels associated with the unit type. The unit is
     * deselected, its research and upgrade are canceled, ownership changes with score increment, the sprite owner
     * changes only for players below {@code 8}, granted upgrades refresh the receiver's units and an idle-capable unit
     * receives the receiver's idle order.
     * <p>
     * A Carrier or Reaver resets its build queue, which cancels the fighter it is building; its fighter training state and build unit stay unchanged.
     *
     * @param unit             the transferred unit
     * @param owner            the receiving player
     * @param instructionLimit the order and animation script budget
     * @throws UnsupportedOperationException if the player technology or upgrade state is missing, or the unit pool is not initialized
     * @throws IllegalStateException         if the unit or its turret has no sprite when the receiving player is below {@code 8}
     * @throws NullPointerException          if the world was built without game data
     */
    public void giveTo(@NotNull Unit unit, int owner, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();
        int baseType = this.game.getBaseType(unitType);
        boolean upgradesGranted = false;
        PlayerTechnology technology = this.game.getPlayerTable().getPlayerTechnology();
        PlayerUpgrades upgrades = this.game.getPlayerTable().getPlayerUpgrades();

        for (int technologyIdentifier : getGrantedTechnologies(baseType)) {
            if (technology == null) {
                throw Exceptions.newUnsupportedOperationException("Unit transfer requires player technology state.");
            }

            if (!technology.isResearched(owner, technologyIdentifier)) {
                technology.setResearched(owner, technologyIdentifier, true);
            }
        }

        for (int upgradeIdentifier : getGrantedUpgrades(baseType)) {
            if (upgrades == null) {
                throw Exceptions.newUnsupportedOperationException("Unit transfer requires player upgrade state.");
            }

            int level = upgrades.getLevel(unit.getOwnerIndex(), upgradeIdentifier);

            if (upgrades.getLevel(owner, upgradeIdentifier) < level) {
                upgradesGranted = true;

                upgrades.setLevel(owner, upgradeIdentifier, level);
            }
        }

        UnitSelection selection = this.game.getSelection();

        if (selection != null) {
            selection.removeUnit(this.game, unit);
        }

        this.game.getInterfaceLink().getLocalSelection().removeUnit(unit);

        if (UnitQueries.isGroundedBuilding(unit)) {
            if (UnitQueries.hasQueuedUnit(this.game.requireGameData().getUnitTypes(), unit, 0)) {
                this.game.getUnitLifecycle().resetBuildQueue(unit, instructionLimit);
                this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(),
                                                                  unit,
                                                                  IscriptExecutor.ANIMATION_WORKING_TO_IDLE,
                                                                  1,
                                                                  instructionLimit);
            }

            if (unit.getResearchType() != ScrTechIds.NONE) {
                this.game.getUnitResearch().cancelResearch(unit, false, instructionLimit);
            }

            if (unit.getUpgradeType() != ScrUpgradeIds.NONE) {
                this.game.getUnitResearch().cancelUpgrade(unit, false, instructionLimit);
            }
        }

        if (units.hasHangar(unitType)) {
            this.game.getUnitLifecycle().resetBuildQueue(unit, instructionLimit);
        }

        this.setOwner(unit, owner, true, instructionLimit);

        if (owner < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            this.setSpriteOwner(unit, owner);
        }

        if (upgradesGranted) {
            UnitPool pool = this.game.getUnitSystem().requireUnitPool();

            for (int index = pool.getFirstOwnerIndex(owner); index != 0; index = pool.getNextOwnerIndex(index)) {
                this.game.getUnitSpeed().updateUpgradeFlags(pool.getUnit(index), instructionLimit);
            }
        }

        int abilities = units.getFlags(unitType);
        boolean idle = (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0;

        if ((unit.getStatus() & UnitStatusFlags.LOADED) != 0) {
            idle = false;
        } else if (UnitQueries.isGroundedBuilding(unit) && unit.getSecondaryOrder() == ScrOrderIds.BUILD_ADDON &&
                   unit.getCurrentBuildUnit() != null && (unit.getCurrentBuildUnit().getStatus() & UnitStatusFlags.COMPLETED) == 0) {
            idle = false;
        } else if ((abilities & UnitTypeFlags.POWERUP) != 0 || baseType == ScrUnitIds.PROTOSS_INTERCEPTOR ||
                   baseType == ScrUnitIds.PROTOSS_SCARAB || baseType == ScrUnitIds.TERRAN_NUCLEAR_MISSILE) {
            idle = false;
        }

        if (idle) {
            Player receiver = this.game.getPlayerTable().getPlayer(owner);
            int controller = receiver == null ? 0 : receiver.getType();
            int order = controller == PlayerTypes.RESCUE_PASSIVE
                        ? ScrOrderIds.RESCUE_PASSIVE
                        : controller == PlayerTypes.NEUTRAL ? ScrOrderIds.NEUTRAL : units.getHumanIdleOrder(unitType);

            this.game.getOrders().issueUntargeted(unit, order, instructionLimit);
        }
    }

    /**
     * Cancels an unfinished add-on, gives the unit to neutral player {@code 11} and clears the secondary order of non-hallucinations.
     *
     * @param unit             the unit of a departing player
     * @param instructionLimit the order and animation script budget
     */
    public void makeNeutral(@NotNull Unit unit, int instructionLimit) {
        Unit building = unit.getCurrentBuildUnit();

        if (unit.getSecondaryOrder() == ScrOrderIds.BUILD_ADDON && UnitQueries.isGroundedBuilding(unit) &&
            building != null && (building.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
            this.game.getUnitConstruction().cancelBuildingUnit(building, instructionLimit);
        }

        this.giveTo(unit, PlayerTable.NEUTRAL_PLAYER_ID, instructionLimit);

        if ((unit.getStatus() & UnitStatusFlags.HALLUCINATION) == 0) {
            ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.NOTHING);
        }
    }
}
