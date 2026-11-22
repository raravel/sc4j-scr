package org.fogofwarlabs.sc4j.scr.game.production;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnKey;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnReset;
import org.fogofwarlabs.sc4j.engine.entity.column.IntColumn;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.data.format.dat.TechdataDat;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UpgradesDat;
import org.fogofwarlabs.sc4j.scr.data.type.ScrTechTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUpgradeTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTechnology;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerUpgrades;
import org.fogofwarlabs.sc4j.scr.game.presentation.NotificationEvents;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Starts, advances, completes and cancels building technology research and upgrades.
 * <p>
 * Requirement tests use {@link ScrRequirementSystem}. A building stores its technology in {@link Unit#getResearchType()}, its upgrade in
 * {@link Unit#getUpgradeType()}, the shared remaining time and the target upgrade level.
 * <p>
 * The research and upgrade state of the native unit union is registered as unit pool columns ({@link #RESEARCH_COLUMNS}):
 * the research type, the upgrade type, the research or upgrade time and the upgrade level. The columns keep their values
 * when a slot is reused, as the union fields do; unit creation and the unit reset methods set them.
 */
public final class UnitResearch {
    /**
     * The research type BYTE ({@link Unit#getResearchType()}).
     */
    public static final @NotNull ColumnKey<IntColumn> RESEARCH_TYPE_COLUMN = ColumnKey.ofInt("researchType", 0, ColumnReset.KEEP);
    /**
     * The upgrade type BYTE ({@link Unit#getUpgradeType()}).
     */
    public static final @NotNull ColumnKey<IntColumn> UPGRADE_TYPE_COLUMN = ColumnKey.ofInt("upgradeType", 0, ColumnReset.KEEP);
    /**
     * The research or upgrade time WORD ({@link Unit#getUpgradeResearchTime()}).
     */
    public static final @NotNull ColumnKey<IntColumn> RESEARCH_TIME_COLUMN = ColumnKey.ofInt("upgradeResearchTime", 0, ColumnReset.KEEP);
    /**
     * The upgrade level BYTE ({@link Unit#getUpgradeLevel()}).
     */
    public static final @NotNull ColumnKey<IntColumn> UPGRADE_LEVEL_COLUMN = ColumnKey.ofInt("upgradeLevel", 0, ColumnReset.KEEP);
    /**
     * The unit pool columns of the research and upgrade state, in registration order.
     */
    public static final @NotNull List<@NotNull ColumnKey<?>> RESEARCH_COLUMNS = List.of(
            RESEARCH_TYPE_COLUMN,
            UPGRADE_TYPE_COLUMN,
            RESEARCH_TIME_COLUMN,
            UPGRADE_LEVEL_COLUMN
    );

    /**
     * The research type value of a building that researches nothing (the {@code techdata.dat} count).
     */
    public static final int NO_TECHNOLOGY = TechdataDat.TECH_COUNT;
    /**
     * The upgrade type value of a building that upgrades nothing (the {@code upgrades.dat} count).
     */
    public static final int NO_UPGRADE = UpgradesDat.UPGRADE_COUNT;

    /**
     * The mask of the WORD an upgrade research time is truncated to.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * The share of the cost a destroyed building refunds: {@code 3/4}.
     */
    private static final int DESTROYED_REFUND_NUMERATOR = 3;
    private static final int DESTROYED_REFUND_DENOMINATOR = 4;
    /**
     * The {@code 2} creation lists of the unit pool: the placed list, then the hidden list.
     */
    private static final int CREATION_LIST_COUNT = 2;

    private final @NotNull Game game;

    /**
     * Creates a {@link UnitResearch} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitResearch(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Applies the research requirement script ({@link ScrRequirementSystem#checkResearch}) for the unit's owner.
     *
     * @param unit       the researching building
     * @param technology the {@code techdata.dat} identifier
     * @return {@code true} if the research can start, {@code false} otherwise
     */
    public boolean canResearch(@NotNull Unit unit, int technology) {
        return this.game.getRequirements().checkResearch(unit, technology, unit.getOwnerIndex()) == 0;
    }

    /**
     * Applies the upgrade requirement script ({@link ScrRequirementSystem#checkUpgrade}) for the unit's owner.
     *
     * @param unit    the upgrading building
     * @param upgrade the {@code upgrades.dat} identifier
     * @return {@code true} if the upgrade can start, {@code false} otherwise
     */
    public boolean canUpgrade(@NotNull Unit unit, int upgrade) {
        return this.game.getRequirements().checkUpgrade(unit, upgrade, unit.getOwnerIndex()) == 0;
    }

    /**
     * Cancels research on the player's single selected completed building with a full refund.
     *
     * @param owner            the command player
     * @param instructionLimit the animation budget
     * @return {@code true} if research was canceled, {@code false} otherwise
     */
    public boolean cancelResearch(int owner, int instructionLimit) {
        Unit unit = this.getSingleSelectedUnit(owner);

        if (unit == null || unit.getOwnerIndex() != owner || !this.isCompletedBuilding(unit) || unit.getResearchType() == NO_TECHNOLOGY) {
            return false;
        }

        this.cancelResearch(unit, false, instructionLimit);

        return true;
    }

    /**
     * Stops a building's research, refunding all of its cost or {@code 3/4} when the building was destroyed.
     *
     * @param unit             the researching building
     * @param destroyed        whether the cancellation comes from the building's destruction
     * @param instructionLimit the animation budget
     * @throws UnsupportedOperationException if player technology storage is absent
     * @throws NullPointerException          if the world was built without game data
     */
    public void cancelResearch(@NotNull Unit unit, boolean destroyed, int instructionLimit) {
        int technology = unit.getResearchType();

        if (technology == NO_TECHNOLOGY) {
            return;
        }

        unit.setResearchType(NO_TECHNOLOGY);
        unit.setUpgradeResearchTime(0);
        this.game.getPlayerTable().requirePlayerTechnology().setResearching(unit.getOwnerIndex(), technology, false);

        ScrTechTypeView technologies = this.game.requireGameData().getTechTypes();

        this.refund(unit, technologies.getMineralCost(technology), technologies.getGasCost(technology), destroyed, instructionLimit);
    }

    /**
     * Cancels the upgrade on the player's single selected completed building with a full refund.
     *
     * @param owner            the command player
     * @param instructionLimit the animation budget
     * @return {@code true} if the upgrade was canceled, {@code false} otherwise
     */
    public boolean cancelUpgrade(int owner, int instructionLimit) {
        Unit unit = this.getSingleSelectedUnit(owner);

        if (unit == null || unit.getOwnerIndex() != owner || !this.isCompletedBuilding(unit) || unit.getUpgradeType() == NO_UPGRADE) {
            return false;
        }

        this.cancelUpgrade(unit, false, instructionLimit);

        return true;
    }

    /**
     * Stops a building's upgrade, refunding the current level cost or {@code 3/4} when the building was destroyed.
     *
     * @param unit             the upgrading building
     * @param destroyed        whether the cancellation comes from the building's destruction
     * @param instructionLimit the animation budget
     * @throws UnsupportedOperationException if player upgrade storage is absent
     * @throws NullPointerException          if the world was built without game data
     */
    public void cancelUpgrade(@NotNull Unit unit, boolean destroyed, int instructionLimit) {
        int upgrade = unit.getUpgradeType();

        if (upgrade == NO_UPGRADE) {
            return;
        }

        unit.setUpgradeType(NO_UPGRADE);
        unit.setUpgradeResearchTime(0);
        unit.setUpgradeLevel(0);

        PlayerUpgrades upgrades = this.game.getPlayerTable().requirePlayerUpgrades();

        upgrades.setUpgrading(unit.getOwnerIndex(), upgrade, false);

        int level = upgrades.getLevel(unit.getOwnerIndex(), upgrade);
        ScrUpgradeTypeView types = this.game.requireGameData().getUpgradeTypes();

        this.refund(unit,
                    types.getMineralCost(upgrade) + types.getMineralCostFactor(upgrade) * level,
                    types.getGasCost(upgrade) + types.getGasCostFactor(upgrade) * level,
                    destroyed,
                    instructionLimit);
    }


    /**
     * Starts research on the player's single selected building.
     *
     * @param owner            the command player
     * @param technology       the {@code techdata.dat} identifier
     * @param instructionLimit the animation and order activation budget
     * @return {@code true} if research started, {@code false} otherwise
     */
    public boolean research(int owner, int technology, int instructionLimit) {
        Unit unit = this.getSingleSelectedUnit(owner);

        if (unit == null || unit.getOwnerIndex() != owner || !this.canResearch(unit, technology)) {
            return false;
        }

        if (!this.startResearch(unit, technology, instructionLimit)) {
            return false;
        }

        this.game.getOrders().issueUntargeted(unit, ScrOrderIds.RESEARCH_TECH, instructionLimit);

        return true;
    }

    /**
     * Starts the next upgrade level on the player's single selected building.
     *
     * @param owner            the command player
     * @param upgrade          the {@code upgrades.dat} identifier
     * @param instructionLimit the animation and order activation budget
     * @return {@code true} if the upgrade started, {@code false} otherwise
     */
    public boolean upgrade(int owner, int upgrade, int instructionLimit) {
        Unit unit = this.getSingleSelectedUnit(owner);

        if (unit == null || unit.getOwnerIndex() != owner || !this.canUpgrade(unit, upgrade)) {
            return false;
        }

        if (!this.startUpgrade(unit, upgrade, instructionLimit)) {
            return false;
        }

        this.game.getOrders().issueUntargeted(unit, ScrOrderIds.UPGRADE, instructionLimit);

        return true;
    }

    /**
     * Advances a building's research each frame and records the technology when the time elapses. Operation Cwal completes research immediately.
     *
     * @param unit             the researching building
     * @param instructionLimit the animation and order activation budget
     * @throws UnsupportedOperationException if player technology storage is absent
     * @throws IllegalStateException         if the unit has no sprite
     */
    public void updateResearch(@NotNull Unit unit, int instructionLimit) {
        int technology = unit.getResearchType();

        if (technology != NO_TECHNOLOGY) {
            int remaining = unit.getUpgradeResearchTime();

            unit.setUpgradeResearchTime(remaining - 1);

            PlayerTechnology technologies = this.game.getPlayerTable().requirePlayerTechnology();

            if (remaining != 0 && !technologies.isResearched(unit.getOwnerIndex(), technology) && !this.game.isOperationCwal()) {
                return;
            }

            this.game.getInterfaceLink().getNotificationEvents().raise(NotificationEvents.Kind.RESEARCH_COMPLETE, unit);
            technologies.setResearched(unit.getOwnerIndex(), technology, true);

            ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

            if (simulation != null) {
                simulation.reportResearchCompleted(unit, technology);
            }
        }

        unit.setResearchType(NO_TECHNOLOGY);

        if (technology != NO_TECHNOLOGY) {
            this.game.getPlayerTable().requirePlayerTechnology().setResearching(unit.getOwnerIndex(), technology, false);
        }

        this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(), unit, IscriptExecutor.ANIMATION_WORKING_TO_IDLE, 1, instructionLimit);
        this.finishOrder(unit, instructionLimit);
    }

    /**
     * Advances a building's upgrade each frame and raises the player's level when the time elapses. A level reached elsewhere or Operation Cwal
     * completes the order immediately.
     *
     * @param unit             the upgrading building
     * @param instructionLimit the animation, speed refresh and order activation budget
     * @throws UnsupportedOperationException if player upgrade storage is absent
     * @throws IllegalStateException         if the unit has no sprite
     */
    public void updateUpgrade(@NotNull Unit unit, int instructionLimit) {
        int upgrade = unit.getUpgradeType();

        if (UnitQueries.isGroundedBuilding(unit) && upgrade != NO_UPGRADE) {
            PlayerUpgrades upgrades = this.game.getPlayerTable().requirePlayerUpgrades();
            int owner = unit.getOwnerIndex();
            boolean alreadyUpgraded = upgrades.getLevel(owner, upgrade) >= unit.getUpgradeLevel();
            int remaining = unit.getUpgradeResearchTime();

            unit.setUpgradeResearchTime(remaining - 1);

            if (remaining != 0 && !alreadyUpgraded && !this.game.isOperationCwal()) {
                return;
            }

            this.game.getInterfaceLink().getNotificationEvents().raise(NotificationEvents.Kind.UPGRADE_COMPLETE, unit);

            if (!alreadyUpgraded && upgrades.getMaximumLevel(owner, upgrade) >= unit.getUpgradeLevel()) {
                upgrades.setLevel(owner, upgrade, unit.getUpgradeLevel());
                this.applyToPlayerUnits(owner, instructionLimit);

                ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

                if (simulation != null) {
                    simulation.reportUpgradeCompleted(unit, upgrade, unit.getUpgradeLevel());
                }
            }
        }

        unit.setUpgradeType(NO_UPGRADE);
        unit.setUpgradeLevel(0);

        if (upgrade != NO_UPGRADE) {
            this.game.getPlayerTable().requirePlayerUpgrades().setUpgrading(unit.getOwnerIndex(), upgrade, false);
        }

        this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(), unit, IscriptExecutor.ANIMATION_WORKING_TO_IDLE, 1, instructionLimit);
        this.finishOrder(unit, instructionLimit);
    }

    /**
     * Finishes the research or upgrade order: a building with a computer record continues with the {@link ScrOrderIds#COMPUTER_AI} order; others
     * finish the order.
     *
     * @param unit             the building
     * @param instructionLimit the order activation budget
     */
    private void finishOrder(@NotNull Unit unit, int instructionLimit) {
        if (unit.getComputerRecord() != null) {
            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.COMPUTER_AI, instructionLimit);
        } else {
            this.game.getOrders().finishOrder(unit, instructionLimit);
        }
    }

    /**
     * Starts research, shared by the research command and the computer players: the technology cost test with its error display, then the research
     * type, the time, the spend, the researching bit and the working animation.
     *
     * @param unit             the researching building
     * @param technology       the {@code techdata.dat} identifier
     * @param instructionLimit the animation budget
     * @return {@code true} if the research started, {@code false} otherwise
     * @throws UnsupportedOperationException if player technology storage is absent
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if the unit has no sprite
     */
    public boolean startResearch(@NotNull Unit unit, int technology, int instructionLimit) {
        if (technology >= NO_TECHNOLOGY) {
            return false;
        }

        CostTests costs = this.game.getCostTests();

        if (!costs.testTechnology(this.game, unit.getOwnerIndex(), technology, true)) {
            return false;
        }

        unit.setResearchType(technology);
        unit.setUpgradeResearchTime(this.game.requireGameData().getTechTypes().getResearchTime(technology));
        costs.spend(this.game, unit.getOwnerIndex());
        this.game.getPlayerTable().requirePlayerTechnology().setResearching(unit.getOwnerIndex(), technology, true);
        this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(), unit, IscriptExecutor.ANIMATION_IS_WORKING, 1, instructionLimit);

        return true;
    }

    /**
     * Starts the next upgrade level, shared by the upgrade command and the computer players: the upgrade cost test with its error display, then the
     * upgrade type, the time and target level (WORD arithmetic), the spend, the upgrading bit and the working animation.
     *
     * @param unit             the upgrading building
     * @param upgrade          the {@code upgrades.dat} identifier
     * @param instructionLimit the animation budget
     * @return {@code true} if the upgrade started, {@code false} otherwise
     * @throws UnsupportedOperationException if player upgrade storage is absent
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if the unit has no sprite
     */
    public boolean startUpgrade(@NotNull Unit unit, int upgrade, int instructionLimit) {
        if (upgrade >= NO_UPGRADE) {
            return false;
        }

        int owner = unit.getOwnerIndex();
        CostTests costs = this.game.getCostTests();

        if (!costs.testUpgrade(this.game, owner, upgrade, true)) {
            return false;
        }

        PlayerUpgrades upgrades = this.game.getPlayerTable().requirePlayerUpgrades();
        int level = upgrades.getLevel(owner, upgrade);
        ScrUpgradeTypeView types = this.game.requireGameData().getUpgradeTypes();

        unit.setUpgradeType(upgrade);
        unit.setUpgradeResearchTime((types.getResearchTime(upgrade) + types.getResearchTimeFactor(upgrade) * level) & WORD_MASK);
        unit.setUpgradeLevel(level + 1);
        costs.spend(this.game, owner);
        upgrades.setUpgrading(owner, upgrade, true);
        this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(), unit, IscriptExecutor.ANIMATION_IS_WORKING, 1, instructionLimit);

        return true;
    }

    /**
     * Refreshes the upgrade-dependent flags of every unit of a player, in the order of the unit pool's creation lists.
     *
     * @param owner            the player
     * @param instructionLimit the animation budget of the speed refresh
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    public void applyToPlayerUnits(int owner, int instructionLimit) {
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int visibility = 0; visibility < CREATION_LIST_COUNT; visibility++) {
            for (int index = pool.getFirstCreatedIndex(visibility == 0); index != 0; index = pool.getNextCreatedIndex(index)) {
                Unit unit = pool.getUnit(index);

                if (unit.getOwnerIndex() == owner) {
                    this.game.getUnitSpeed().updateUpgradeFlags(unit, instructionLimit);
                }
            }
        }
    }

    private boolean isCompletedBuilding(@NotNull Unit unit) {
        return (this.game.requireGameData().getUnitTypes().getFlags(unit.getUnitId()) & UnitTypeFlags.BUILDING) != 0 &&
               (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0;
    }

    private @Nullable Unit getSingleSelectedUnit(int owner) {
        UnitSelection selection = this.game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Research commands require initialized selection storage.");
        }

        Unit unit = selection.getSelectedUnit(owner, 0);

        if (unit == null || selection.getSelectedUnit(owner, 1) != null) {
            return null;
        }

        return unit;
    }

    private void refund(@NotNull Unit unit, int mineralCost, int gasCost, boolean destroyed, int instructionLimit) {
        Player player = this.game.getPlayerTable().requirePlayer(unit.getOwnerIndex());

        if (destroyed) {
            player.setMinerals(player.getMinerals() + mineralCost * DESTROYED_REFUND_NUMERATOR / DESTROYED_REFUND_DENOMINATOR);
            player.setGas(player.getGas() + gasCost * DESTROYED_REFUND_NUMERATOR / DESTROYED_REFUND_DENOMINATOR);
        } else {
            player.setMinerals(player.getMinerals() + mineralCost);
            player.setGas(player.getGas() + gasCost);
            this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(),
                                                              unit,
                                                              IscriptExecutor.ANIMATION_WORKING_TO_IDLE,
                                                              1,
                                                              instructionLimit);
        }
    }
}
