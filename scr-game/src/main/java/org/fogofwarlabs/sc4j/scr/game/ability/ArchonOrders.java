package org.fogofwarlabs.sc4j.scr.game.ability;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ButtonSetIds;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTechnology;
import org.fogofwarlabs.sc4j.scr.game.presentation.NotificationEvents;
import org.fogofwarlabs.sc4j.scr.game.production.UnitMorphing;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitInitializer;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;

/**
 * Pairs templars for archon merges and executes the merge orders.
 * <p>
 * Selected templars are paired by distance, then their warp and summon orders advance movement, merging and completion.
 */
public final class ArchonOrders {
    /**
     * The templars of a merge.
     */
    private static final int PAIR_SIZE = 2;
    /**
     * The distance at which {@link #PAIR_SIZE} Dark Templars or {@link #PAIR_SIZE} High Templars merge.
     */
    private static final int DARK_MERGE_DISTANCE = 20;
    private static final int MERGE_DISTANCE = 2;
    /**
     * The distance below which a merging templar moves to the midpoint instead of its partner.
     */
    private static final int DARK_MIDPOINT_DISTANCE = 19;
    private static final int MIDPOINT_DISTANCE = 10;
    /**
     * The energy of a new Dark Archon.
     */
    private static final int DARK_ARCHON_START_ENERGY = 50;
    /**
     * The movement steps of the next speed within which a merging ground templar leaves ground collision.
     */
    private static final int COLLISION_RELEASE_SPEED_STEPS = 2;
    /**
     * The fraction bits of the fixed-point speed and energy values.
     */
    private static final int FIXED_POINT_FRACTION_BITS = 8;
    /**
     * The divisor of the coordinate sum of the {@link #PAIR_SIZE} templars that gives their midpoint.
     */
    private static final int MIDPOINT_DIVISOR = 2;

    /**
     * The order states of {@link ScrOrderIds#ARCHON_WARP} and {@link ScrOrderIds#DARK_ARCHON_MELD}: started, moving to the partner.
     */
    private static final int WARP_STATE_START = 0;
    private static final int WARP_STATE_MOVING = 1;
    /**
     * The order states of {@link ScrOrderIds#COMPLETING_ARCHON_SUMMON}: building, waiting for the {@link IscriptExecutor#ANIMATION_INIT} animation.
     */
    private static final int SUMMON_STATE_BUILDING = 0;
    private static final int SUMMON_STATE_ANIMATING = 1;

    /**
     * Merges the larger timers and defensive-matrix values, the longer irradiation with its source, the union of parasite and blindness bits, and the
     * source's acid spores.
     *
     * @param source the partner whose effects are merged in
     * @param unit   the merged templar
     */
    private static void mergeStatusEffects(@NotNull Unit source, @NotNull Unit unit) {
        unit.setDefensiveMatrixHealth(Math.max(unit.getDefensiveMatrixHealth(), source.getDefensiveMatrixHealth()));
        unit.setDefensiveMatrixTimer(Math.max(unit.getDefensiveMatrixTimer(), source.getDefensiveMatrixTimer()));
        unit.setSpeedIncreaseByte(Math.max(unit.getSpeedIncreaseByte(), source.getSpeedIncreaseByte()));
        unit.setSpeedDecreaseByte(Math.max(unit.getSpeedDecreaseByte(), source.getSpeedDecreaseByte()));
        unit.setLockdownTimer(Math.max(unit.getLockdownTimer(), source.getLockdownTimer()));
        unit.setStasisTimer(Math.max(unit.getStasisTimer(), source.getStasisTimer()));
        unit.setPlagueTimer(Math.max(unit.getPlagueTimer(), source.getPlagueTimer()));

        if (source.getIrradiateTimer() > unit.getIrradiateTimer()) {
            unit.setIrradiateTimer(source.getIrradiateTimer());
            unit.setIrradiatedBy(source.getIrradiatedBy());
            unit.setIrradiateOwner(source.getIrradiateOwner());
        }

        unit.setParasiteFlags(unit.getParasiteFlags() | source.getParasiteFlags());
        unit.setBlindnessState(unit.getBlindnessState() | source.getBlindnessState());
        unit.setMaelstromTimer(Math.max(source.getMaelstromTimer(), unit.getMaelstromTimer()));
        unit.setAcidSporeCount(source.getAcidSporeCount());

        for (int index = 0; index < Unit.ACID_SPORE_SLOTS; index++) {
            unit.setAcidSporeTimer(index, source.getAcidSporeTimer(index));
        }
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link ArchonOrders} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public ArchonOrders(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Pairs the issuer's selected templars.
     * <p>
     * The first selected unit must be able to use {@link ScrTechIds#ARCHON_WARP} or {@link ScrTechIds#DARK_ARCHON_MELD}. Each templar is paired with the
     * nearest later templar; a replaced nearer candidate is put back into the replaced slot.
     *
     * @param owner            the issuing player
     * @param dark             whether Dark Templars merge into a Dark Archon
     * @param instructionLimit the order script budget
     * @return {@code true} if a pair received merge orders, {@code false} otherwise
     * @throws UnsupportedOperationException if the selection storage is unavailable
     * @throws IllegalStateException         if the unit or the candidate has no sprite
     */
    public boolean merge(int owner, boolean dark, int instructionLimit) {
        UnitSelection selection = this.game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Archon merges require initialized selection storage.");
        }

        Unit first = selection.getSelectedUnit(owner, 0);

        if (first == null || !this.canUseMerge(first, owner, dark)) {
            return false;
        }

        int templarType = dark ? ScrUnitIds.PROTOSS_DARK_TEMPLAR : ScrUnitIds.PROTOSS_HIGH_TEMPLAR;
        Unit[] units = new Unit[UnitSelection.MAXIMUM_SELECTED];
        int count = 0;

        for (int index = 0; index < UnitSelection.MAXIMUM_SELECTED; index++) {
            Unit selected = selection.getSelectedUnit(owner, index);

            if (selected == null) {
                break;
            }

            if (this.game.getBaseType(selected) == templarType) {
                units[count++] = selected;
            }
        }

        if (count < PAIR_SIZE) {
            return false;
        }

        int order = dark ? ScrOrderIds.DARK_ARCHON_MELD : ScrOrderIds.ARCHON_WARP;
        boolean issued = false;

        for (int index = 0; index < count; index++) {
            Unit unit = units[index];

            if (unit == null) {
                continue;
            }

            int nearestDistance = Integer.MAX_VALUE;
            Unit nearest = null;

            for (int other = index + 1; other < count; other++) {
                Unit candidate = units[other];

                if (candidate == null) {
                    continue;
                }

                int differenceX = (short) unit.requireSprite().getX() - (short) candidate.requireSprite().getX();
                int differenceY = (short) unit.requireSprite().getY() - (short) candidate.requireSprite().getY();
                int distance = differenceX * differenceX + differenceY * differenceY;

                if (distance < nearestDistance) {
                    units[other] = nearest;

                    nearestDistance = distance;
                    nearest         = candidate;
                }
            }

            if (nearest == null) {
                continue;
            }

            this.game.getOrders().issue(unit, order, nearest, 0, 0, ScrUnitIds.NONE, false, instructionLimit);
            this.game.getOrders().issue(nearest, order, unit, 0, 0, ScrUnitIds.NONE, false, instructionLimit);

            issued = true;
        }

        return issued;
    }

    /**
     * Tests whether the first selected unit may start a merge: a completed, real, enabled templar of the issuer whose owner has the merge technology.
     *
     * @param unit  the first selected unit
     * @param owner the issuing player
     * @param dark  whether Dark Templars merge into a Dark Archon
     * @return {@code true} if the merge may start, {@code false} otherwise
     * @throws UnsupportedOperationException if the player technology state is unavailable
     */
    private boolean canUseMerge(@NotNull Unit unit, int owner, boolean dark) {
        if (unit.getOwnerIndex() != owner || (unit.getStatus() & UnitStatusFlags.COMPLETED) == 0 ||
            (unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
            return false;
        }

        if (UnitOrderEligibility.isDisabled(unit)) {
            return false;
        }

        PlayerTechnology technology = this.game.getPlayerTable().getPlayerTechnology();

        if (technology == null) {
            throw Exceptions.newUnsupportedOperationException("Archon merges require player technology state.");
        }

        if (!technology.isAvailable(owner, dark ? ScrTechIds.DARK_ARCHON_MELD : ScrTechIds.ARCHON_WARP)) {
            return false;
        }

        return this.game.getBaseType(unit) == (dark ? ScrUnitIds.PROTOSS_DARK_TEMPLAR : ScrUnitIds.PROTOSS_HIGH_TEMPLAR);
    }

    /**
     * Advances {@link ScrOrderIds#ARCHON_WARP} or {@link ScrOrderIds#DARK_ARCHON_MELD}: the templars walk toward each other (to the midpoint when close)
     * and merge when close enough; the merged templar becomes the forming archon, takes the partner's kills and effects, shows the empty button set
     * and starts {@link ScrOrderIds#COMPLETING_ARCHON_SUMMON}, and the partner dies hidden.
     *
     * @param unit             the merging templar
     * @param instructionLimit the order, morph and animation script budget
     * @throws NullPointerException  if the world was built without game data
     * @throws IllegalStateException if the unit or the target has no sprite
     */
    public void updateWarp(@NotNull Unit unit, int instructionLimit) {
        boolean dark = unit.getPrimaryOrder() == ScrOrderIds.DARK_ARCHON_MELD;
        Unit target = unit.getOrderTargetUnit();

        if (target == null || target.getOwnerIndex() != unit.getOwnerIndex() || target.getPrimaryOrder() != unit.getPrimaryOrder() ||
            target.getOrderTargetUnit() != unit) {
            this.game.getUnitPointMovement().stopAndFaceDestination(unit);
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        if ((unit.getStatus() & UnitStatusFlags.GROUND_UNIT) != 0 &&
            this.game.getUnitTargetMovement().isTargetInRange(unit, target,
                                                              (unit.getNextSpeed() * COLLISION_RELEASE_SPEED_STEPS) >> FIXED_POINT_FRACTION_BITS)) {
            unit.setStatus(unit.getStatus() & ~UnitStatusFlags.GROUND_UNIT);
            this.game.getOrders().queueFront(unit, ScrOrderIds.RESET_COLLISION, null, 0, 0, ScrUnitIds.NONE);
        }

        Sprite sprite = unit.requireSprite();
        Sprite targetSprite = target.requireSprite();
        int distance = RegionPathSearch.calculateDistance((short) sprite.getX(), (short) targetSprite.getX(),
                                                          (short) sprite.getY(), (short) targetSprite.getY());

        if (distance <= (dark ? DARK_MERGE_DISTANCE : MERGE_DISTANCE)) {
            mergeStatusEffects(target, unit);
            this.game.getUnitMorphing().morph(unit, dark ? ScrUnitIds.PROTOSS_DARK_ARCHON : ScrUnitIds.PROTOSS_ARCHON, instructionLimit);

            if (dark) {
                this.game.getEngineGame().getVisibility().setCloaked(unit, false);
            }

            sprite = unit.requireSprite();

            sprite.setFlags(sprite.getFlags() & ~Sprite.FLAG_ISCRIPT_NO_BREAK);
            this.game.getIscriptExecutor().runSpriteAnimation(sprite, unit, IscriptExecutor.ANIMATION_SPECIAL_STATE_1, 0, instructionLimit);
            UnitMorphing.writeButtonSet(this.game.requireGameData().getUnitTypes(), unit, ButtonSetIds.EMPTY);
            unit.setKillCount(unit.getKillCount() + target.getKillCount());

            if (dark) {
                unit.setEnergy(DARK_ARCHON_START_ENERGY << FIXED_POINT_FRACTION_BITS);
            }

            target.setUserActionFlags(target.getUserActionFlags() | Unit.USER_ACTION_HIDE_ON_DEATH);
            this.game.getUnitLifecycle().kill(target, instructionLimit);
            unit.setStatus(unit.getStatus() & ~UnitStatusFlags.CAN_MOVE);
            this.resetMovementState(unit);
            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.COMPLETING_ARCHON_SUMMON, instructionLimit);

            return;
        }

        if (unit.getPrimaryOrderState() != WARP_STATE_START && (short) sprite.getX() == (short) unit.getMovementDestinationX() &&
            (short) sprite.getY() == (short) unit.getMovementDestinationY() && (unit.getStatus() & UnitStatusFlags.IMMOVABLE) != 0) {
            this.game.getUnitPointMovement().stopAndFaceDestination(unit);
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        unit.setPrimaryOrderState(WARP_STATE_MOVING);

        if (unit.getMovementDestinationCountdown() != 0) {
            return;
        }

        if (distance <= (dark ? DARK_MIDPOINT_DISTANCE : MIDPOINT_DISTANCE)) {
            this.game.getEngineGame().getMovement().setDestination(unit,
                                                                   ((short) sprite.getX() + (short) targetSprite.getX()) / MIDPOINT_DIVISOR,
                                                                   ((short) sprite.getY() + (short) targetSprite.getY()) / MIDPOINT_DIVISOR);
        } else {
            this.game.getEngineGame().getMovement().setDestination(unit, (short) targetSprite.getX(), (short) targetSprite.getY());
        }
    }

    /**
     * Advances {@link ScrOrderIds#COMPLETING_ARCHON_SUMMON}: the build countdown ({@code 16} per frame with Operation CWAL), the
     * {@link IscriptExecutor#ANIMATION_INIT} animation, and on the animation-done order signal movement is restored, the archon takes its own button
     * set, the unit-ready notification is raised and the idle order queued.
     *
     * @param unit             the forming archon
     * @param instructionLimit the order and animation script budget
     * @throws NullPointerException  if the world was built without game data
     * @throws IllegalStateException if the unit has no sprite
     */
    public void updateSummon(@NotNull Unit unit, int instructionLimit) {
        if (unit.getPrimaryOrderState() == SUMMON_STATE_BUILDING) {
            int remaining = unit.getRemainingBuildTime();

            if (remaining != 0) {
                this.game.getUnitBuildTime().advance(unit);
            } else {
                Sprite sprite = unit.requireSprite();

                sprite.setFlags(sprite.getFlags() & ~Sprite.FLAG_ISCRIPT_NO_BREAK);
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, unit, IscriptExecutor.ANIMATION_INIT, 0, instructionLimit);
                unit.setPrimaryOrderState(SUMMON_STATE_ANIMATING);
            }
        } else if (unit.getPrimaryOrderState() == SUMMON_STATE_ANIMATING && (unit.getOrderSignal() & Unit.ORDER_SIGNAL_ANIMATION_DONE) != 0) {
            unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_ANIMATION_DONE);
            unit.setStatus(unit.getStatus() | UnitStatusFlags.CAN_MOVE);
            this.resetMovementState(unit);

            ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

            UnitMorphing.writeButtonSet(units, unit, unit.getUnitId());
            this.game.getInterfaceLink().getNotificationEvents().raise(NotificationEvents.Kind.UNIT_READY, unit);
            this.game.getOrders().queueReplacingSameOrders(unit, units.getHumanIdleOrder(unit.getUnitId()), null, 0, 0, ScrUnitIds.NONE);
            this.game.getOrders().completeOrder(unit, instructionLimit);
        }
    }

    /**
     * Releases the unit's path, clears its movement state and sets its ground elevation path flag from its sprite elevation.
     *
     * @param unit the unit
     * @throws UnsupportedOperationException if the path pool is not initialized
     * @throws IllegalStateException         if the unit has no sprite
     */
    private void resetMovementState(@NotNull Unit unit) {
        if (unit.getPathIndex() >= 0) {
            this.game.getPathSystem().requirePathPool().release(unit.getPathIndex());
            unit.setPathIndex(-1);
        }

        unit.setMovementState(0);

        UnitInitializer.updateGroundElevationPathFlag(unit, unit.requireSprite().getElevationLevel());
    }
}
