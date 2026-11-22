package org.fogofwarlabs.sc4j.scr.game.unit;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.SoundIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.SpriteIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepField;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.movement.MovementStates;
import org.fogofwarlabs.sc4j.scr.game.order.OrderPool;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.resource.UnitHarvesting;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffect;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.fogofwarlabs.sc4j.scr.game.visibility.SightCoverage;
import org.fogofwarlabs.sc4j.scr.game.visibility.SpriteVisibility;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Maintains unit references, spatial membership, visible/hidden list transitions and the build queue cancellation.
 */
public final class UnitLifecycle {
    /**
     * The sight descriptor index of a retained dying sprite.
     */
    private static final int DEAD_UNIT_SIGHT_RADIUS_INDEX = 1;

    /**
     * Empties the current build queue slot, the slot at the queue index, without moving the queue index. Like
     * {@link UnitQueries#getQueuedType}, the index has no bound: an index from {@link Unit#BUILD_QUEUE_SLOTS} on writes the empty type
     * {@code 228} to the WORD of the {@code 488}-byte unit layout that follows the queue ({@link NativeUnitFields}), so index {@code 5} sets the
     * energy and index {@code 6} the queue index and the slot's generation. A byte outside the represented fields of the layout is not written.
     *
     * @param unit the queue owner
     */
    public static void clearCurrentQueueSlot(@NotNull Unit unit) {
        int index = unit.getBuildQueueIndex();

        if (index < Unit.BUILD_QUEUE_SLOTS) {
            unit.setBuildQueueUnitType(index, ScrUnitIds.NONE);

            return;
        }

        int offset = UnitQueries.NATIVE_BUILD_QUEUE + index * Short.BYTES;

        for (int byteIndex = 0; byteIndex < Short.BYTES; byteIndex++) {
            if (NativeUnitFields.getFieldStart(offset + byteIndex) >= 0) {
                NativeUnitFields.writeByte(unit, offset + byteIndex, ScrUnitIds.NONE >>> byteIndex * Byte.SIZE);
            }
        }
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitLifecycle} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitLifecycle(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Resets the build queue: cancels each occupied entry from the fifth to the first through {@link #cancelBuildQueueEntry},
     * reading each entry's slot again from the current queue index ({@link UnitQueries#getQueueSlotType}), then empties the five
     * slots and moves the queue index to the first slot.
     *
     * @param unit             the queue owner
     * @param instructionLimit the order script budget
     */
    public void resetBuildQueue(@NotNull Unit unit, int instructionLimit) {
        for (int offset = Unit.BUILD_QUEUE_SLOTS - 1; offset >= 0; offset--) {
            if (UnitQueries.getQueueSlotType(unit, offset) != ScrUnitIds.NONE) {
                this.cancelBuildQueueEntry(unit, offset, instructionLimit);
            }
        }

        for (int slot = 0; slot < Unit.BUILD_QUEUE_SLOTS; slot++) {
            unit.setBuildQueueUnitType(slot, ScrUnitIds.NONE);
        }

        unit.setBuildQueueIndex(0);
    }

    /**
     * Cancels one production entry. An empty or out-of-range entry has no effect; the front entry with a unit in progress cancels that unit, other
     * entries are refunded unless they are buildings; then, from the canceled slot onward, each circular slot takes the following slot's type
     * until an empty slot is met or the fifth slot is reached, and the final slot becomes empty.
     * <p>
     * Each move also copies a computer building record's per-slot request kind and town.
     *
     * @param unit             the producing unit
     * @param index            the zero-based entry from the queue head
     * @param instructionLimit the order script budget
     * @throws NullPointerException if the world was built without game data
     */
    public void cancelBuildQueueEntry(@NotNull Unit unit, int index, int instructionLimit) {
        if (index < 0 || index >= Unit.BUILD_QUEUE_SLOTS) {
            return;
        }

        int slot = (unit.getBuildQueueIndex() + index) % Unit.BUILD_QUEUE_SLOTS;
        int queuedType = unit.getBuildQueueUnitType(slot);

        if (queuedType == ScrUnitIds.NONE) {
            return;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if (index == 0 && unit.getCurrentBuildUnit() != null) {
            this.game.getUnitConstruction().cancelBuildingUnit(unit.getCurrentBuildUnit(), instructionLimit);
        } else if ((units.getFlags(queuedType) & UnitTypeFlags.BUILDING) == 0) {
            int owner = unit.getOwnerIndex();
            ScrUnitTypeView refundTypes = this.game.requireGameData().getUnitTypes();
            Player player = this.game.getPlayerTable().requirePlayer(owner);

            player.setMinerals(player.getMinerals() + refundTypes.getMineralCost(queuedType));
            player.setGas(player.getGas() + refundTypes.getGasCost(queuedType));
        }

        for (int remaining = Unit.BUILD_QUEUE_SLOTS - 1 - index; remaining > 0; remaining--) {
            if (unit.getBuildQueueUnitType(slot) == ScrUnitIds.NONE) {
                break;
            }

            int next = (slot + 1) % Unit.BUILD_QUEUE_SLOTS;

            // The building record's per-slot request kind and target follow the moved queue entry.
            this.game.getHooks().notifyQueueSlotMoved(unit, slot, next);
            unit.setBuildQueueUnitType(slot, unit.getBuildQueueUnitType(next));

            slot = next;
        }

        unit.setBuildQueueUnitType(slot, ScrUnitIds.NONE);
    }

    /**
     * Credits a destroyed unit to the death, kill and score counters. Weapon and unit damage (also for loaded units),
     * Spawn Broodlings, Consume and Infestation call this method. Trigger kills, timers and larva deaths are not counted.
     *
     * @param unit   the destroyed unit
     * @param killer the crediting player, or {@code -1} for none
     * @throws NullPointerException if the world was built without game data
     */
    public void recordDeath(@NotNull Unit unit, int killer) {
        UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();

        if (accounting != null) {
            accounting.getScores().recordDeath(this.game, this.game.requireGameData().getUnitTypes(), unit, killer);
        }
    }

    /**
     * Requests death after discarding queued orders; allocation remains owned by the unit pool.
     *
     * @param unit             the dying unit
     * @param instructionLimit the order transition instruction budget
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    public void kill(@NotNull Unit unit, int instructionLimit) {
        this.kill(unit, null, -1, -1, instructionLimit);
    }

    /**
     * Requests death after discarding queued orders; allocation remains owned by the unit pool. The simulation plugins hear the death of a unit
     * that is not dying yet, with the damage that caused it.
     *
     * @param unit             the dying unit
     * @param killer           the attacking unit of the lethal damage, or {@code null} for none
     * @param killerPlayer     the player slot the death is credited to, or {@code -1} for none
     * @param weapon           the weapons.dat entry of the lethal weapon damage, or {@code -1} for none
     * @param instructionLimit the order transition instruction budget
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    public void kill(@NotNull Unit unit, @Nullable Unit killer, int killerPlayer, int weapon, int instructionLimit) {
        ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

        if (simulation != null && unit.getPrimaryOrder() != ScrOrderIds.DIE) {
            simulation.reportUnitDied(unit, killer, killerPlayer, weapon);
        }

        // A carried powerup is put down first.
        this.game.getUnitPowerups().drop(unit, instructionLimit);

        while (unit.getFirstOrder() != null) {
            this.game.requireOrderPool().remove(unit, unit.getFirstOrder(), this.game.requireGameData().getOrderTypes());
        }

        this.game.getOrders().issue(unit, ScrOrderIds.DIE,
                                    null, unit.getOrderTargetX(), unit.getOrderTargetY(), ScrUnitIds.NONE,
                                    false,
                                    instructionLimit);
        this.game.getEngineGame().getUnitLifecycle().notifyRemoved(unit, true);
    }

    /**
     * Begins the death animation and removes the unit's gameplay membership.
     *
     * @param unit             the unit executing Die
     * @param instructionLimit the death animation instruction budget
     */
    public void die(@NotNull Unit unit, int instructionLimit) {
        this.die(unit, false, instructionLimit);
    }

    /**
     * Begins the death animation and removes the unit's gameplay membership; a hidden unit destroyed directly ({@link #destroyHidden}) skips the
     * hallucination death effect.
     *
     * @param unit             the unit executing Die
     * @param destroyed        whether a hidden unit is destroyed directly by {@link #destroyHidden}
     * @param instructionLimit the death animation instruction budget
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if a grounded Zerg building dies without the creep field or the unit creation context is not established,
     *                                       or the unit spatial index, the path pool, the selection storage, the unit accounting or the unit pool is
     *                                       not initialized
     * @throws IllegalStateException         if the unit has no sprite
     */
    private void die(@NotNull Unit unit, boolean destroyed, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = unit.getUnitId();
        int abilities = units.getFlags(type);

        if ((unit.getUserActionFlags() & Unit.USER_ACTION_HIDE_ON_DEATH) != 0) {
            this.hide(unit, true);
        }

        this.game.getUnitTurret().detachForDeath(unit);
        unit.setPrimaryOrderState(Unit.DIE_STATE_DESTROYED);

        Sprite sprite = unit.requireSprite();
        boolean hidden = (sprite.getFlags() & Sprite.FLAG_HIDDEN) != 0;
        boolean hallucination = (unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0;

        if (!hidden && (!hallucination || (unit.getStatus() & UnitStatusFlags.LIFETIME_EXPIRED) != 0)) {
            this.game.getIscriptExecutor().runSpriteAnimation(sprite, unit, IscriptExecutor.ANIMATION_DEATH, 1, instructionLimit);
        } else if (hallucination && !destroyed) {
            // A hallucination that did not expire through a lifetime-expiry flag plays its death sound, leaves the Hallucination Death sprite one
            // elevation above it with the hallucination's visibility, then is hidden and destroyed.
            this.game.getInterfaceLink().getSoundEvents().add(SoundIds.HALLUCINATION_DEATH, sprite.getX(), sprite.getY(), type);

            SpriteEffect effect = this.game.getIscriptExecutor().createSpriteEffect(SpriteIds.HALLUCINATION_DEATH1,
                                                                                    sprite.getX(), sprite.getY(),
                                                                                    0,
                                                                                    instructionLimit);

            if (effect != null) {
                Sprite effectSprite = effect.getSprite();

                effectSprite.setElevationLevel(sprite.getElevationLevel() + 1);

                SpriteVisibility.apply(effectSprite, sprite.getVisibilityFlags(), this.game.getLocalVision().getComparisonMask());
            }

            this.hide(unit, true);

            hidden = true;
        }

        // The destruction of a building requests the computer unit-status refresh that clears every claimed-target mark.
        if ((abilities & UnitTypeFlags.BUILDING) != 0) {
            this.game.getEngineGame().getUnitLifecycle().notifyBuildingDestroyed(unit);
        }

        this.game.getUnitHangar().destroy(unit, instructionLimit);

        // Remove the Ghost target dot, Silo missile, Missile silo and Nydus exit.
        this.game.getUnitNuclear().destroy(unit, instructionLimit);
        this.game.getUnitNydus().destroy(unit, instructionLimit);

        // The unit leaves the systems it provides for (a Pylon its psi field).
        this.game.getEngineGame().getUnitLifecycle().notifyDying(unit);

        if ((abilities & UnitTypeFlags.RESOURCE_CONTAINER) != 0) {
            unit.setResourceBeingGathered(false);

            while (unit.getFirstGatheringWorker() != null) {
                Unit waiting = unit.getFirstGatheringWorker();

                waiting.setGatherTarget(null);
                UnitHarvesting.unlinkWaitingWorker(unit, waiting);
            }
        }

        this.game.getUnitTransport().destroy(unit, instructionLimit);
        this.removeAllTargetReferences(unit, true);

        if ((abilities & UnitTypeFlags.WORKER) != 0 && unit.getGatherTarget() != null) {
            if (unit.isGatheringResource()) {
                this.game.getUnitHarvesting().releaseResource(unit, unit.getGatherTarget(), instructionLimit);
            } else {
                UnitHarvesting.unlinkWaitingWorker(unit.getGatherTarget(), unit);
                unit.setGatherTarget(null);
            }
        }

        this.game.getEngineGame().getUnitLifecycle().notifyRemoved(unit, true);

        // A unit that is not hidden may remain as something else, which ends its death (a refinery turns back into its geyser, after an
        // Extractor released its creep); a removed one (hidden first by Remove Unit) dies like any other unit and leaves the hidden list.
        if (!hidden && this.game.getEngineGame().getUnitLifecycle().notifyRemains(unit, instructionLimit)) {
            return;
        }

        this.game.getUnitPointMovement().stop(unit);

        if (!hidden) {
            this.game.getUnitSystem().requireUnitSpatialIndex().remove(unit);

            if (UnitQueries.isGroundedBuilding(unit)) {
                this.vacateFootprint(unit);
            }

            if ((unit.getStatus() & UnitStatusFlags.FLYING) != 0) {
                this.game.getUnitSystem().requireUnitSpatialIndex().unregisterFlyingUnit(unit, units);
            }
        }

        this.game.getUnitCloaking().destroy(unit);

        // Research and upgrades are refunded at three quarters, and a queue with a unit in its current slot is reset.
        if (UnitQueries.isGroundedBuilding(unit)) {
            // Cancel an incomplete add-on and abandon an attached one.
            Unit addonUnderConstruction = unit.getCurrentBuildUnit();

            if (unit.getSecondaryOrder() == ScrOrderIds.BUILD_ADDON && addonUnderConstruction != null &&
                (addonUnderConstruction.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
                this.game.getUnitConstruction().cancelBuildingUnit(addonUnderConstruction, instructionLimit);
            }

            this.game.getUnitAddons().abandon(unit, instructionLimit);
            this.game.getUnitResearch().cancelResearch(unit, true, instructionLimit);
            this.game.getUnitResearch().cancelUpgrade(unit, true, instructionLimit);

            if (UnitQueries.hasQueuedUnit(this.game.requireGameData().getUnitTypes(), unit, 0)) {
                this.resetBuildQueue(unit, instructionLimit);
            }
        }

        // The unit stops changing the terrain after leaving the finder (a grounded Zerg building its creep). When a release must be retried (the
        // creep recession records ran out), status FLAG_4000 makes the dead-list update retry the removal before the unit is released.
        if (!this.game.getEngineGame().getUnitLifecycle().notifyTerrainRelease(unit)) {
            unit.setStatus(unit.getStatus() | UnitStatusFlags.FLAG_4000);
        }

        // A landing flying building releases its reserved footprint.
        if ((abilities & UnitTypeFlags.FLYING_BUILDING) != 0 && unit.isLanding()) {
            this.game.getUnitPositioning().setFootprintOccupied(type, unit.getOrderTargetX(), unit.getOrderTargetY(), false);
        }

        if (unit.getPathIndex() >= 0) {
            this.game.getPathSystem().requirePathPool().release(unit.getPathIndex());
            unit.setPathIndex(-1);
        }

        if (unit.getCurrentBuildUnit() != null &&
            (unit.getSecondaryOrder() == ScrOrderIds.TRAIN || unit.getSecondaryOrder() == ScrOrderIds.TRAIN_FIGHTER)) {
            this.kill(unit.getCurrentBuildUnit(), instructionLimit);
        }

        ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.NOTHING);

        this.game.requireSelection().removeUnit(this.game, unit);
        this.game.getInterfaceLink().getLocalSelection().removeUnit(unit);
        this.game.getSelectionImages().hideSelectionCircle(sprite);
        this.game.getPlayerTable().requireUnitAccounting().update(unit,
                                                                  units,
                                                                  -1,
                                                                  this.game.getUnitSystem().requireUnitCreationContext().preservesNegativeTypeCounts());

        if ((unit.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
            this.game.getPlayerTable().requireUnitAccounting().updateCompleted(unit,
                                                                               units,
                                                                               -1,
                                                                               false,
                                                                               this.game.getPlayerTable().getProductionStatistics());
        }

        this.game.getUnitSystem().requireUnitPool().transferUnitToDead(unit, hidden);

        if (hidden) {
            this.destroySprite(unit);
        }
    }

    /**
     * Destroys a hidden unit without a death animation or hallucination effect, then applies Die and destroys its sprite.
     *
     * @param unit             the hidden unit
     * @param instructionLimit the order budget
     * @throws IllegalStateException if the unit is not hidden or the unit has no sprite
     */
    public void destroyHidden(@NotNull Unit unit, int instructionLimit) {
        if ((unit.requireSprite().getFlags() & Sprite.FLAG_HIDDEN) == 0) {
            throw Exceptions.newIllegalStateException("Direct destruction is only represented for hidden units.");
        }

        ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

        if (simulation != null && unit.getPrimaryOrder() != ScrOrderIds.DIE) {
            simulation.reportUnitDied(unit, null, -1, -1);
        }

        unit.setPrimaryOrder(ScrOrderIds.DIE);
        this.die(unit, true, instructionLimit);
    }

    private void vacateFootprint(@NotNull Unit unit) {
        Sprite sprite = unit.requireSprite();

        this.game.getUnitPositioning().setFootprintOccupied(unit.getUnitId(), sprite.getX(), sprite.getY(), false);
    }

    private void destroySprite(@NotNull Unit unit) {
        Sprite sprite = unit.requireSprite();

        for (SpriteImage image : sprite.getImages()) {
            this.game.getSpriteSystem().requireImagePool().recycle(image);
        }

        sprite.clearImages();

        if (!this.game.getSpriteSystem().requireSpritePool().recycleEmptySprite(sprite, this.game.getMap().getHeight())) {
            throw Exceptions.newIllegalStateException("Unit death could not unlink its sprite row.");
        }

        unit.setSprite(null);
    }

    /**
     * Advances a retained death sprite and recycles the unit only after its final image disappears.
     *
     * @param unit             the dead-list member
     * @param updateSight      whether this frame refreshes unit sight
     * @param instructionLimit the death animation instruction budget
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if a creep provider retries its removal without a creep field or the sight coverage, the order pool or
     *                                       the unit pool is not initialized
     */
    public void updateDead(@NotNull Unit unit, boolean updateSight, int instructionLimit) {
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite != null) {
            if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) != 0) {
                this.destroySprite(unit);
            } else if (!this.game.getIscriptExecutor().executeSprite(sprite, unit, instructionLimit)) {
                unit.setSprite(null);
            }
        }

        // A creep provider whose removal ran out of recession records (status FLAG_4000) retries it; while the retry
        // fails the unit is not released.
        boolean retained = false;

        if ((unit.getStatus() & UnitStatusFlags.FLAG_4000) != 0) {
            CreepField creep = this.game.getCreepField();

            if (creep == null) {
                throw Exceptions.newUnsupportedOperationException("Dying creep provider requires the creep field.");
            }

            retained = !creep.retryProviderRemoval(this.game,
                                                   unit.getUnitId(),
                                                   unit.getPositionX(), unit.getPositionY(),
                                                   (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0);
        }

        sprite = unit.getSpriteOrNull();

        if (sprite != null) {
            if (updateSight && this.game.getPlayerTable().requirePlayer(unit.getOwnerIndex()).getType() == PlayerTypes.HUMAN) {
                SightCoverage coverage = this.game.requireSightCoverage();
                int visibilityMask = coverage.getSharedVisionMask(unit.getOwnerIndex());
                boolean flying = (unit.getStatus() & UnitStatusFlags.FLYING) != 0;

                coverage.reveal(this.game, (short) sprite.getX(), (short) sprite.getY(), flying, visibilityMask, DEAD_UNIT_SIGHT_RADIUS_INDEX);
            }

            this.game.getSpriteVisibility().update(unit, this.game.getMap(), this.game.getLocalVision().getComparisonMask(), instructionLimit);
        } else if (!retained) {
            while (unit.getFirstOrder() != null) {
                this.game.requireOrderPool().remove(unit, unit.getFirstOrder(), this.game.requireGameData().getOrderTypes());
            }

            this.game.getUnitSystem().requireUnitPool().unlinkDeadUnit(unit);
            this.game.getUnitSystem().requireUnitPool().recycleFinalized(unit.getIndex());
        }
    }

    /**
     * Removes every reference to a unit leaving the world: the units of the visible list, with {@code bothLists} also
     * those of the hidden list, then the bullets. Death and turret destruction clear both lists; detaching ({@link #detach})
     * clears the visible list only.
     *
     * @param target    the unit leaving the world
     * @param bothLists whether the hidden list is cleared too
     * @throws UnsupportedOperationException if the unit pool or the frame contexts are not initialized
     */
    public void removeAllTargetReferences(@NotNull Unit target, boolean bothLists) {
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int visibility = 0; visibility < (bothLists ? UnitPool.CREATED_LIST_COUNT : 1); visibility++) {
            for (int index = pool.getFirstCreatedIndex(visibility == 0); index != 0; index = pool.getNextCreatedIndex(index)) {
                this.removeTargetReferences(pool.getUnit(index), target);
            }
        }

        this.game.requireFrame().getProjectiles().removeTargetReferences(target);
    }

    /**
     * Removes references to a departing unit without changing unrelated target coordinates.
     *
     * @param unit   the referencing unit
     * @param target the unit leaving the visible world
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if the target has no sprite
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    public void removeTargetReferences(@NotNull Unit unit, @NotNull Unit target) {
        if (unit.getCurrentBuildUnit() == target) {
            unit.setCurrentBuildUnit(null);
        }

        if (unit.getOrderTargetUnit() == target) {
            unit.setOrderTargetUnit(null);
        }

        if (unit.getMovementTargetUnit() == target) {
            unit.setMovementTargetUnit(null);
        }

        if (unit.getAutomaticTargetUnit() == target) {
            unit.setAutomaticTargetUnit(null);
        }

        if (unit.getParentUnit() == target) {
            unit.setParentUnit(null);
        }

        if (unit.getIrradiatedBy() == target) {
            unit.setIrradiatedBy(null);
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        // The worker test reads no state that the references change, so it is made only for a unit that holds one.
        if ((unit.getTargetResourceUnit() == target || unit.isGatheringResource() && unit.getGatherTarget() == target) &&
            (units.getFlags(unit.getUnitId()) & UnitTypeFlags.WORKER) != 0) {
            if (unit.getTargetResourceUnit() == target) {
                unit.setTargetResource(null, unit.getTargetResourceX(), unit.getTargetResourceY());
            }

            if (unit.isGatheringResource() && unit.getGatherTarget() == target) {
                unit.setGatherTarget(null);
                unit.setGatheringResource(false);
            }
        }

        if (UnitQueries.isGroundedBuilding(unit) && unit.getAddon() == target) {
            unit.setAddon(null);
        }

        int type = unit.getUnitId();

        if (units.isRallyProducer(type) && unit.getRallyTargetUnit() == target) {
            Sprite sprite = target.requireSprite();

            unit.setRallyPositionX(sprite.getX());
            unit.setRallyPositionY(sprite.getY());
            unit.setRallyTargetUnit(null);
        }

        Unit subunit = unit.getSubunit();

        if (subunit != null && (units.getFlags(subunit.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
            this.removeTargetReferences(subunit, target);
        }

        for (OrderPool.Order order = unit.getFirstOrder(); order != null; ) {
            OrderPool.Order next = order.getNext();

            if (order.getTargetUnit() == target) {
                this.game.requireOrderPool().remove(unit, order, this.game.requireGameData().getOrderTypes());
            }

            order = next;
        }
    }

    /**
     * Hides a unit while preserving its allocation, owner membership and animation state.
     *
     * @param unit     the visible unit
     * @param deselect whether selection references are removed
     * @throws IllegalStateException         if the unit has no sprite
     * @throws UnsupportedOperationException if the selection storage is not initialized
     */
    public void hide(@NotNull Unit unit, boolean deselect) {
        Sprite sprite = unit.requireSprite();

        if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) != 0) {
            return;
        }

        this.detach(unit, true);

        if (deselect) {
            this.game.requireSelection().removeUnit(this.game, unit);
            this.game.getInterfaceLink().getLocalSelection().removeUnit(unit);
        }
    }

    /**
     * Detaches a visible unit from the world: clears the references to it ({@link #removeAllTargetReferences}, visible
     * list), removes it from the unit finder with its building footprint and flying registration, releases its path and
     * resets its movement; with {@code hide} it then moves to the hidden list and hides its sprites.
     *
     * @param unit the visible unit
     * @param hide whether the unit is hidden (Nydus travel, gas entry) or stays visible (relocation)
     * @throws IllegalStateException         if the unit or the subunit has no sprite
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit spatial index, the path pool or the unit pool is not initialized
     */
    public void detach(@NotNull Unit unit, boolean hide) {
        Sprite sprite = unit.requireSprite();

        this.removeAllTargetReferences(unit, false);

        UnitSpatialIndex spatial = this.game.getUnitSystem().requireUnitSpatialIndex();

        spatial.remove(unit);

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        // A grounded building also releases its footprint.
        if (UnitQueries.isGroundedBuilding(unit)) {
            this.vacateFootprint(unit);
        }

        if ((unit.getStatus() & UnitStatusFlags.FLYING) != 0) {
            spatial.unregisterFlyingUnit(unit, units);
        }

        if (unit.getPathIndex() >= 0) {
            this.game.getPathSystem().requirePathPool().release(unit.getPathIndex());
            unit.setPathIndex(-1);
        }

        unit.setMovementState(MovementStates.INIT);
        UnitInitializer.updateGroundElevationPathFlag(unit, sprite.getElevationLevel());

        if (!hide) {
            return;
        }

        this.game.getUnitSystem().requireUnitPool().transferCreatedUnitToHidden(unit);
        sprite.setFlags(sprite.getFlags() | Sprite.FLAG_HIDDEN);
        SpriteVisibility.apply(sprite, 0, this.game.getLocalVision().getComparisonMask());

        Unit subunit = unit.getSubunit();

        if (subunit != null && (units.getFlags(subunit.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
            Sprite subunitSprite = subunit.requireSprite();

            subunitSprite.setFlags(subunitSprite.getFlags() | Sprite.FLAG_HIDDEN);
            SpriteVisibility.apply(subunitSprite, 0, this.game.getLocalVision().getComparisonMask());
        }
    }
}
