package org.fogofwarlabs.sc4j.scr.game.production;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.presentation.NotificationEvents;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.jetbrains.annotations.NotNull;

/**
 * Advances queued unit training through creation, construction and completion states.
 */
public final class UnitTraining {
    /**
     * The {@link ScrOrderIds#TRAIN} secondary order states: first attempt, silent retry, unit under construction.
     */
    private static final int TRAINING_STATE_START = 0;
    private static final int TRAINING_STATE_WAITING = 1;
    private static final int TRAINING_STATE_BUILDING = 2;

    private final @NotNull Game game;

    /**
     * Creates a {@link UnitTraining} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitTraining(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Creates the queued unit and advances its incomplete construction state. Completes placement and dispatches the producer rally order before
     * consuming the queue slot.
     *
     * @param producer         the unit executing the {@link ScrOrderIds#TRAIN} secondary order
     * @param instructionLimit the creation, animation, hit point image, completion, cancellation and order budget
     * @throws UnsupportedOperationException if the producer's sprite, the supply accounting, the creation context, the unit pool or the owner is
     *                                       missing, or a completed unit would be completed again
     * @throws NullPointerException          if the world was built without game data
     */
    public void update(@NotNull Unit producer, int instructionLimit) {
        if (UnitOrderEligibility.isDisabled(producer)) {
            return;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int producerType = producer.getUnitId();

        if ((units.getGroupFlags(producerType) & UnitGroupFlags.ZERG) != 0 && producerType != ScrUnitIds.ZERG_INFESTED_COMMAND_CENTER) {
            return;
        }

        int trainingState = producer.getSecondaryOrderState();

        if (trainingState == TRAINING_STATE_START || trainingState == TRAINING_STATE_WAITING) {
            int workerType = UnitQueries.getQueuedType(producer);
            Sprite sprite = producer.getSpriteOrNull();

            if (sprite == null) {
                throw Exceptions.newUnsupportedOperationException("Training requires the producer sprite.");
            }

            if (workerType == ScrUnitIds.NONE) {
                ScrOrderSystem.setSecondaryOrder(producer, ScrOrderIds.NOTHING);
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, producer, IscriptExecutor.ANIMATION_WORKING_TO_IDLE, 1, instructionLimit);

                return;
            }

            Unit worker = null;
            // A grounded building, a Carrier or Reaver or a Zerg Egg trains the queued type at its own position; any other producer waits in the
            // retry state.
            boolean producesHere = UnitQueries.isGroundedBuilding(producer) || units.hasHangar(producerType) ||
                                   units.getBaseType(producerType) == ScrUnitIds.ZERG_EGG;

            if (producesHere) {
                UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();

                if (accounting == null) {
                    throw Exceptions.newUnsupportedOperationException("Training requires initialized supply accounting.");
                }

                // The supply test shows its error only on the first attempt; the retries of the waiting state are silent.
                if (this.game.getCostTests().testSupply(this.game, producer.getOwnerIndex(), workerType, trainingState == TRAINING_STATE_START)) {
                    UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();
                    UnitPool pool = this.game.getUnitSystem().getUnitPool();

                    if (context == null || pool == null) {
                        throw Exceptions.newUnsupportedOperationException("Training requires the established creation context and pool.");
                    }

                    worker = this.game.getUnitStorageConstructor().create(context,
                                                                          accounting,
                                                                          workerType,
                                                                          sprite.getX(), sprite.getY(),
                                                                          producer.getOwnerIndex(),
                                                                          pool.getCreationTag(producer.getIndex()),
                                                                          instructionLimit);

                    // A failed creation shows the pending creation message; like a supply failure, the attempt then waits in the retry state without
                    // a current build unit and retries silently.
                    if (worker == null) {
                        this.game.getUnitStorageConstructor().notifyCreationFailure(producer.getOwnerIndex());
                    }
                }
            }

            producer.setCurrentBuildUnit(worker);
            producer.setSecondaryOrderState(worker == null ? TRAINING_STATE_WAITING : TRAINING_STATE_BUILDING);

            if (worker != null) {
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, producer, IscriptExecutor.ANIMATION_IS_WORKING, 1, instructionLimit);
            }
        } else if (trainingState == TRAINING_STATE_BUILDING) {
            Unit worker = producer.getCurrentBuildUnit();

            if (worker == null) {
                producer.setSecondaryOrderState(TRAINING_STATE_START);

                return;
            }

            // A completed current build unit is rejected before construction advances. Normal completion clears the current build unit and secondary
            // state after the reached completion effects.
            if ((worker.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
                throw Exceptions.newUnsupportedOperationException("The completion state would complete the completed unit {0} again.",
                                                                  worker.getUnitId());
            }

            this.game.getUnitHealth().setHitPoints(worker,
                                                   worker.getHitPoints() + this.game.getUnitBuildTime().getHitPointIncrement(worker),
                                                   instructionLimit);

            int remaining = worker.getRemainingBuildTime();

            if (remaining != 0) {
                this.game.getUnitBuildTime().advance(worker);

                return;
            }

            UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();
            Player owner = this.game.getPlayerTable().getPlayer(worker.getOwnerIndex());

            if (context == null || owner == null) {
                throw Exceptions.newUnsupportedOperationException("Training completion requires established creation context and owner.");
            }

            boolean placed = this.game.getUnitStorageConstructor().tryComplete(context, worker, owner.getType(), instructionLimit);

            if (!placed) {
                // An unplaceable unit is canceled and leaves the queue.
                int queueIndex = producer.getBuildQueueIndex();

                if (producer.getBuildQueueUnitType(queueIndex) != ScrUnitIds.NONE) {
                    this.game.getUnitConstruction().cancelBuildingUnit(worker, instructionLimit);
                    this.game.getHooks().notifyQueueSlotCleared(producer, queueIndex);

                    producer.setBuildQueueUnitType(queueIndex, ScrUnitIds.NONE);
                    producer.setBuildQueueIndex((queueIndex + 1) % Unit.BUILD_QUEUE_SLOTS);
                }

                producer.setCurrentBuildUnit(null);
                producer.setSecondaryOrderState(TRAINING_STATE_START);

                return;
            }

            // A completed unit that is not a grounded building is ready.
            if (!UnitQueries.isGroundedBuilding(worker)) {
                this.game.getInterfaceLink().getNotificationEvents().raise(NotificationEvents.Kind.UNIT_READY, worker);
            }

            this.game.getComputerUnits().notifyCompleted(producer, worker, instructionLimit);

            ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

            if (simulation != null) {
                simulation.reportUnitTrained(worker, producer);
            }

            // A completed Nuclear Missile hides inside its Silo.
            if (this.game.getBaseType(worker) == ScrUnitIds.TERRAN_NUCLEAR_MISSILE) {
                this.game.getUnitLifecycle().hide(worker, true);
            } else {
                this.game.getUnitRally().issueRallyOrder(producer, worker, instructionLimit);
            }

            int queueIndex = producer.getBuildQueueIndex();

            this.game.getHooks().notifyQueueSlotCleared(producer, queueIndex);

            UnitLifecycle.clearCurrentQueueSlot(producer);
            producer.setBuildQueueIndex(Byte.toUnsignedInt((byte) (producer.getBuildQueueIndex() + 1)) % Unit.BUILD_QUEUE_SLOTS);

            producer.setCurrentBuildUnit(null);
            producer.setSecondaryOrderState(TRAINING_STATE_START);
        }
    }
}
