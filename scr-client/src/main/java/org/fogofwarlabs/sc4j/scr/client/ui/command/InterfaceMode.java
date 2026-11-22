package org.fogofwarlabs.sc4j.scr.client.ui.command;

import org.fogofwarlabs.sc4j.engine.client.command.CommandAction;
import org.fogofwarlabs.sc4j.engine.client.command.CommandTarget;
import org.fogofwarlabs.sc4j.engine.client.command.CommandTranslator;
import org.fogofwarlabs.sc4j.engine.client.command.TargetingMode;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrButtonInput;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the local cursor mode of the in-game interface: normal, targeting an order, or placing a building.
 * <p>
 * Targeting stores separate unit-target, ground-target and alternate orders; the alternate order is kept as supplied. The targeting of a
 * script target button ({@link ButtonAction#SCRIPT_TARGET}) stores the press instead of orders: its target goes to the game plugins.
 * Placement stores the pressed button's building type. These modes change only local interface state.
 * <p>
 * A targeting or placement mode is the SC:R action waiting in the engine {@link TargetingMode} ({@link #getTargeting}): the map input's
 * engine {@link CommandTranslator} issues it at the next left press on the map through the {@link TargetHandler} (the map input), and a right
 * press cancels it. The mode is normal while no SC:R action waits there.
 */
public final class InterfaceMode {
    private static final int NONE = -1;

    /**
     * Issues the waiting mode at the point the player pressed on the map: the map input's native targeting and placement clicks.
     */
    public interface TargetHandler {
        /**
         * Issues the targeting mode's order at a pressed point.
         *
         * @param target the pressed map point and the unit picked there
         * @param queued whether Shift is held
         * @return {@code true} if the order was sent and the mode ended, {@code false} if the mode stays (for example a refused target)
         */
        boolean clickTarget(@NotNull CommandTarget target, boolean queued);

        /**
         * Places the building of the placement mode at a pressed point.
         *
         * @param target the pressed map point
         * @return {@code true} if the placement was sent and the mode ended, {@code false} if the mode stays (for example an invalid spot)
         */
        boolean clickPlacement(@NotNull CommandTarget target);
    }

    /**
     * The SC:R action of the mode in the engine targeting mode: a placement while a building type is placed, else the targeting orders.
     */
    private static final class ModeAction implements CommandAction {
        private final @NotNull InterfaceMode mode;

        private ModeAction(@NotNull InterfaceMode mode) {
            this.mode = mode;
        }

        /**
         * @return {@code true}: the mode's action waits for the player to point at a target
         */
        @Override
        public boolean isTargeted() {
            return true;
        }

        /**
         * Submits the action's commands. A targeted action is still the waiting action of the translator's {@link TargetingMode} while it is
         * issued; it may refuse the target (for example a place a building cannot stand on), and then it keeps waiting for another.
         *
         * @param translator the translator: the local player, the selection and the command sink
         * @param target     the target the player pointed at, or {@code null} for an action without a target
         * @param queued     whether the commands queue after the units' current orders (Shift held)
         * @return {@code true} if the action took the target (or ran without one), {@code false} if it refused the target
         */
        @Override
        public boolean issue(@NotNull CommandTranslator translator, @Nullable CommandTarget target, boolean queued) {
            TargetHandler handler = this.mode.handler;

            if (handler == null || target == null) {
                return false;
            }

            return this.mode.placementType >= 0 ? handler.clickPlacement(target) : handler.clickTarget(target, queued);
        }
    }

    private final @NotNull TargetingMode targeting;
    private final @NotNull ModeAction action;
    private @Nullable TargetHandler handler;

    private int unitTargetOrder;
    private int groundTargetOrder;
    private int alternateOrder;
    private int placementType;
    private @NotNull ButtonAction placementAction;
    private @Nullable ScrButtonInput scriptButton;

    /**
     * Creates an {@link InterfaceMode} instance in normal mode.
     */
    public InterfaceMode() {
        this.targeting = new TargetingMode();
        this.action    = new ModeAction(this);
        this.handler   = null;

        this.unitTargetOrder   = NONE;
        this.groundTargetOrder = NONE;
        this.alternateOrder    = NONE;
        this.placementType     = NONE;
        this.placementAction   = ButtonAction.TERRAN_BUILD;
        this.scriptButton      = null;
    }

    /**
     * @return the engine targeting mode the SC:R mode waits in, shared with the map input's translator
     */
    public @NotNull TargetingMode getTargeting() {
        return this.targeting;
    }

    /**
     * Sets who issues the waiting mode at a pressed map point.
     *
     * @param handler the map input, or {@code null} for none (a press then keeps the mode)
     */
    public void setTargetHandler(@Nullable TargetHandler handler) {
        this.handler = handler;
    }

    /**
     * @return the order sent for a unit target, or {@code -1} outside targeting
     */
    public int getUnitTargetOrder() {
        return this.isWaiting() ? this.unitTargetOrder : NONE;
    }

    /**
     * @return the order sent for a ground target, or {@code -1} outside targeting
     */
    public int getGroundTargetOrder() {
        return this.isWaiting() ? this.groundTargetOrder : NONE;
    }

    /**
     * @return the third order byte of the targeting mode, or {@code -1} outside targeting
     */
    public int getAlternateOrder() {
        return this.isWaiting() ? this.alternateOrder : NONE;
    }

    /**
     * @return {@code true} while a targeting mode waits for a target, of an order or of a script target button, {@code false} otherwise
     */
    public boolean isTargeting() {
        return this.isWaiting() && this.placementType < 0;
    }

    /**
     * @return the press of the script target button whose targeting mode waits, or {@code null} outside such a mode
     */
    public @Nullable ScrButtonInput getScriptButton() {
        return this.isWaiting() ? this.scriptButton : null;
    }

    /**
     * @return the building type being placed, or {@code -1} outside placement
     */
    public int getPlacementType() {
        return this.isWaiting() ? this.placementType : NONE;
    }

    /**
     * @return the action that started the placement
     */
    public @NotNull ButtonAction getPlacementAction() {
        return this.placementAction;
    }

    private boolean isWaiting() {
        return this.targeting.getAction() == this.action;
    }


    /**
     * Enters targeting mode with the supplied orders. A negative unit-target order leaves the normal mode.
     *
     * @param unitTargetOrder   the order for a unit target
     * @param groundTargetOrder the order for a ground target
     * @param alternateOrder    the third order byte
     */
    public void startTargeting(int unitTargetOrder, int groundTargetOrder, int alternateOrder) {
        this.placementType     = NONE;
        this.scriptButton      = null;
        this.unitTargetOrder   = unitTargetOrder;
        this.groundTargetOrder = groundTargetOrder;
        this.alternateOrder    = alternateOrder;

        if (unitTargetOrder >= 0) {
            this.targeting.start(this.action);
        } else {
            this.clear();
        }
    }

    /**
     * Enters the targeting mode of a script target button, whose target goes to the game plugins.
     *
     * @param press the press of the button
     */
    public void startScriptTargeting(@NotNull ScrButtonInput press) {
        this.placementType     = NONE;
        this.unitTargetOrder   = NONE;
        this.groundTargetOrder = NONE;
        this.alternateOrder    = NONE;
        this.scriptButton      = press;

        this.targeting.start(this.action);
    }

    /**
     * Enters building placement. A negative building type leaves the normal mode.
     *
     * @param action   the placing action
     * @param unitType the building type
     */
    public void startPlacement(@NotNull ButtonAction action, int unitType) {
        this.unitTargetOrder   = NONE;
        this.groundTargetOrder = NONE;
        this.alternateOrder    = NONE;

        this.placementAction = action;
        this.placementType   = unitType;
        this.scriptButton    = null;

        if (unitType >= 0) {
            this.targeting.start(this.action);
        } else {
            this.clear();
        }
    }


    /**
     * Returns to normal mode.
     */
    public void clear() {
        if (this.isWaiting()) {
            this.targeting.cancel();
        }
    }
}
