package org.fogofwarlabs.sc4j.scr.client.ui.command;

import org.fogofwarlabs.sc4j.scr.client.ui.console.ConsoleModel;
import org.fogofwarlabs.sc4j.scr.client.ui.input.HotkeyProfile;
import org.fogofwarlabs.sc4j.scr.client.ui.status.StatusPanel;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.data.format.replay.*;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrButtonInput;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.CommandErrorEvents;
import org.fogofwarlabs.sc4j.scr.game.state.GameCheats;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitRacesUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Turns command-card actions and map clicks into encoded commands or local interface changes.
 * Production and per-unit gates run before action dispatch and report local errors through the configured output.
 * Selection publication and command submission follow their existing order; targeting and placement modes clear after submission.
 */
public final class CommandInput {
    /**
     * The unit-type word of a target command without a remembered building under the click.
     */
    public static final int NO_UNIT_TYPE = 0xe4;
    /**
     * stat_txt {@code 874} "Not enough life remaining."
     */
    public static final int NOT_ENOUGH_LIFE = 874;
    /**
     * Status panel controls {@code 2..6}: the production queue slots {@code 0..4}.
     */
    public static final int FIRST_QUEUE_CONTROL = 2;
    /**
     * The last production queue control.
     */
    public static final int LAST_QUEUE_CONTROL = 6;
    /**
     * Status panel control {@code 15}: the research or upgrade in progress.
     */
    public static final int RESEARCH_CONTROL = 15;
    /**
     * Status panel control {@code 17}: the egg's unit or the add-on under construction.
     */
    public static final int EGG_ADDON_CONTROL = 17;
    /**
     * The status view mode of a research in progress.
     */
    public static final int RESEARCH_MODE = StatusPanel.RESEARCH_MODE;
    /**
     * The status view mode of an upgrade in progress.
     */
    public static final int UPGRADE_MODE = StatusPanel.UPGRADE_MODE;

    private static final int UNIT_TARGET_ORDER = 0;
    private static final int GROUND_TARGET_ORDER = 1;
    private static final int OBSCURED_TARGET_ORDER = 2;

    private static final int UNIT_HANDLE_BYTES = Integer.BYTES;
    /**
     * Unit energy and technology costs compare with eight fractional bits.
     */
    private static final int ENERGY_FRACTION_BITS = 8;
    /**
     * The Stim Packs test: more than {@code 10} hit points.
     */
    private static final int STIM_MINIMUM_HIT_POINTS = 0xA00;
    /**
     * The landing error sound (SND_Z_CANT_LAND_THERE) + race.
     */
    private static final @NotNull String LAND_ERROR_SOUND = "SND_Z_CANT_LAND_THERE";
    /**
     * The placement error sound (key invalidBuildLocation, SND_Z_BUILD_INTERRUPTED) + race.
     */
    private static final @NotNull String BUILD_ERROR_SOUND = "SND_Z_BUILD_INTERRUPTED";

    private static final int @NotNull [] @Nullable [] TECHNOLOGY_ORDERS = {
            null, {115, 115, 115}, {122, 122, 122}, {132, 132, 132}, {139, 139, 139}, null, {141, 141, 141}, {143, 143, 143},
            {113, 113, 114}, null, null, null, {27, 6, 28}, {121, 121, 121}, {119, 119, 119}, {144, 144, 144}, {145, 145, 145},
            {146, 146, 146}, {120, 120, 120}, {142, 142, 142}, {148, 148, 148}, {137, 137, 137}, {147, 147, 147}, null,
            {180, 180, 180}, {181, 181, 181}, null, {182, 182, 182}, null, {184, 184, 184}, {185, 185, 185}, {186, 186, 186}
    };

    /**
     * Receives encoded commands.
     */
    public interface CommandSink {
        /**
         * Submits a command of the local player.
         *
         * @param player  the local player slot
         * @param opcode  the command opcode
         * @param payload the command payload without the opcode
         */
        void submit(int player, int opcode, byte @NotNull [] payload);
    }

    /**
     * Receives the error of a failed gate; called only for the local player's buttons.
     */
    public interface ErrorOutput {
        /**
         * Shows an error: the status line text and the race sound (no unit, so no talking portrait and no alert).
         *
         * @param statTextIdentifier the one-based {@code stat_txt} id
         * @param soundName          the sfx name of the Zerg sound
         * @param race               the race added to the sfx id, {@code 0} Zerg through {@code 2} Protoss
         */
        void showError(int statTextIdentifier, @NotNull String soundName, int race);
    }

    /**
     * The result of an input.
     */
    public enum Result {
        /**
         * A command was submitted.
         */
        COMMAND,
        /**
         * The interface mode or command card changed.
         */
        INTERFACE,
        /**
         * The input had no effect: an empty, disabled or unresolved slot, or an action without an established effect.
         */
        IGNORED
    }

    /**
     * Receives the native replay buttons (button sets {@code 248} and {@code 249}).
     */
    public interface ReplayControls {
        /**
         * Toggles replay playback.
         */
        void playOrPause();

        /**
         * Decreases replay speed.
         */
        void decreaseSpeed();

        /**
         * Increases replay speed.
         */
        void increaseSpeed();
    }

    /**
     * The interface actions that change the local selection through the world interface.
     */
    public interface InterfaceActions {
        /**
         * Selects the Larva of the active Hatchery, Lair or Hive.
         *
         * @return the effect
         */
        @NotNull Result selectLarva();
    }

    /**
     * Selects morph, build and add-on actions that run the production gate before dispatch.
     */
    private static boolean isGated(@NotNull ButtonAction action) {
        return switch (action) {
            case UNIT_MORPH, BUILDING_MORPH, ZERG_BUILD, TERRAN_BUILD, PROTOSS_BUILD, BUILD_ADDON -> true;
            default -> false;
        };
    }

    private final @NotNull ConsoleModel console;
    private final @NotNull CommandSink sink;

    private @Nullable ReplayControls replayControls;
    private @Nullable Runnable acknowledgement;
    private @Nullable ErrorOutput errorOutput;
    private @Nullable InterfaceActions interfaceActions;

    /**
     * Creates a {@link CommandInput} instance for one console.
     *
     * @param console the console whose command card is pressed
     * @param sink    where produced commands are submitted
     */
    public CommandInput(@NotNull ConsoleModel console, @NotNull CommandSink sink) {
        this.console = console;
        this.sink    = sink;

        this.replayControls   = null;
        this.acknowledgement  = null;
        this.errorOutput      = null;
        this.interfaceActions = null;
    }

    /**
     * Sets what runs for the order acknowledgement of Return Cargo (acknowledges before sending the command).
     *
     * @param acknowledgement the acknowledgement, or {@code null}
     */
    public void setAcknowledgement(@Nullable Runnable acknowledgement) {
        this.acknowledgement = acknowledgement;
    }

    /**
     * Sets where gate errors are shown.
     *
     * @param errorOutput the error output, or {@code null}
     */
    public void setErrorOutput(@Nullable ErrorOutput errorOutput) {
        this.errorOutput = errorOutput;
    }

    /**
     * Sets the receiver of the interface actions that act on the local selection (Select Larva).
     *
     * @param interfaceActions the receiver, or {@code null}
     */
    public void setInterfaceActions(@Nullable InterfaceActions interfaceActions) {
        this.interfaceActions = interfaceActions;
    }

    /**
     * Sets the replay controls the replay buttons act on.
     *
     * @param replayControls the replay controls, or {@code null}
     */
    public void setReplayControls(@Nullable ReplayControls replayControls) {
        this.replayControls = replayControls;
    }

    /**
     * Returns the unit-target, ground-target and obscured-target order tuple for an action.
     */
    private int @Nullable [] getTargetOrders(@NotNull ButtonAction action, int value) {
        return switch (action) {
            case MOVE -> new int[]{ScrOrderIds.FOLLOW, ScrOrderIds.MOVE, ScrOrderIds.MOVE};
            case ATTACK -> new int[]{ScrOrderIds.ATTACK_DEFAULT,
                                     this.hasSelectedMovableUnit() ? ScrOrderIds.ATTACK_MOVE : ScrOrderIds.GUARD,
                                     ScrOrderIds.MOVE_TO_ATTACK};
            case PATROL -> new int[]{ScrOrderIds.PATROL, ScrOrderIds.PATROL, ScrOrderIds.PATROL};
            case REPAIR -> new int[]{ScrOrderIds.REPAIR, ScrOrderIds.MOVE, ScrOrderIds.MOVE_TO_REPAIR};
            case GATHER -> new int[]{ScrOrderIds.HARVEST1, ScrOrderIds.MOVE, ScrOrderIds.HARVEST2};
            case LOAD -> this.isActiveUnitGroundedBuilding()
                         ? new int[]{ScrOrderIds.PICKUP_BUNKER, ScrOrderIds.NOTHING, ScrOrderIds.NOTHING}
                         : new int[]{ScrOrderIds.PICKUP_TRANSPORT, ScrOrderIds.MOVE, ScrOrderIds.MOVE};
            case HEAL -> new int[]{ScrOrderIds.HEAL_MOVE, ScrOrderIds.HEAL_MOVE, ScrOrderIds.HEAL_MOVE};
            case INFESTED_TERRAN_ATTACK -> new int[]{ScrOrderIds.ATTACK_DEFAULT,
                                                     ScrOrderIds.ATTACK_DEFAULT,
                                                     ScrOrderIds.MOVE_TO_ATTACK};
            case CARRIER_MOVE -> new int[]{ScrOrderIds.FOLLOW,
                                           ScrOrderIds.REAVER_CARRIER_MOVE,
                                           ScrOrderIds.REAVER_CARRIER_MOVE};
            case CARRIER_ATTACK -> new int[]{ScrOrderIds.CARRIER_ATTACK,
                                             ScrOrderIds.ATTACK_MOVE,
                                             ScrOrderIds.CARRIER_MOVE_TO_ATTACK};
            case REAVER_ATTACK -> new int[]{ScrOrderIds.REAVER_ATTACK,
                                            ScrOrderIds.ATTACK_MOVE,
                                            ScrOrderIds.REAVER_MOVE_TO_ATTACK};
            case SET_RALLY_POINT -> new int[]{ScrOrderIds.RALLY_POINT_UNIT,
                                              ScrOrderIds.RALLY_POINT_TILE,
                                              ScrOrderIds.RALLY_POINT_TILE};
            case BUILDING_ATTACK -> new int[]{ScrOrderIds.TOWER_ATTACK, ScrOrderIds.TOWER_GUARD, ScrOrderIds.TOWER_GUARD};
            case RECHARGE_SHIELDS -> new int[]{ScrOrderIds.RECHARGE_SHIELDS_BATTERY,
                                               ScrOrderIds.RECHARGE_SHIELDS_BATTERY, ScrOrderIds.RECHARGE_SHIELDS_BATTERY};
            case NUCLEAR_STRIKE -> new int[]{ScrOrderIds.NUKE_PAINT, ScrOrderIds.NUKE_PAINT, ScrOrderIds.NUKE_PAINT};
            case USE_TECHNOLOGY -> value > 0 && value < TECHNOLOGY_ORDERS.length ? TECHNOLOGY_ORDERS[value] : null;
            default -> null;
        };
    }

    /**
     * Presses a command card slot with the mouse.
     *
     * @param slot   the slot from {@code 0} through {@code 8}
     * @param queued whether the command is queued (shift)
     * @return the effect of the press
     */
    public @NotNull Result pressSlot(int slot, boolean queued) {
        return this.pressSlot(slot, queued, false);
    }

    /**
     * Presses a command card slot. The game plugins see the press of an enabled button first ({@link ScrGamePlugin#onButtonInput}); a press a
     * plugin consumes runs no action.
     *
     * @param slot   the slot from {@code 0} through {@code 8}
     * @param queued whether the command is queued (shift)
     * @param hotkey whether a hotkey pressed the slot, else the mouse
     * @return the effect of the press
     */
    public @NotNull Result pressSlot(int slot, boolean queued, boolean hotkey) {
        CommandCard card = this.console.getCommandCard();
        ButtonDefinition button = card.getButton(Objects.checkIndex(slot, CommandCard.SLOT_COUNT));

        if (button == null || card.getState(slot) != CommandCard.ENABLED) {
            return Result.IGNORED;
        }

        int set = card.getDisplayedSet();
        ScrButtonInput press = ScrButtonInput.ofPress(set, card.findTableIndex(set, button), button.getPosition(), button.getIcon(),
                                                      button.getCondition().getNativeAddress(), button.getConditionValue(),
                                                      button.getAction().getNativeAddress(), button.getActionValue(),
                                                      queued, hotkey);

        if (this.console.getView().deliverButtonInput(press)) {
            return Result.INTERFACE;
        }

        return this.executeButton(button, press, queued);
    }

    /**
     * Finds the first enabled command-card slot whose active-profile hotkey matches the lowercased character.
     *
     * @param profile   the active hotkey profile
     * @param character the typed character
     * @return the slot, or {@code -1}
     */
    public int findHotkeySlot(@NotNull HotkeyProfile profile, char character) {
        CommandCard card = this.console.getCommandCard();
        char wanted = Character.toLowerCase(character);

        for (int slot = 0; slot < CommandCard.SLOT_COUNT; slot++) {
            ButtonDefinition button = card.getButton(slot);

            if (button == null || card.getState(slot) != CommandCard.ENABLED) {
                continue;
            }

            String text = button.getEnabledText();
            char hotkey = text == null ? profile.getHotkey(this.console.getView(), button.getEnabledString()) : text.isEmpty() ? 0 : text.charAt(0);

            if (Character.toLowerCase(hotkey) == wanted) {
                return slot;
            }
        }

        return -1;
    }

    /**
     * Activates production-queue, research/upgrade and egg/add-on status controls through command-card actions.
     * Replay input is ignored; queue slots choose their cancellation index and disabled eggs cannot cancel construction.
     *
     * @param identifier the status panel control id
     * @param statusMode the status view mode
     * @return the effect
     */
    public @NotNull Result activateStatusControl(int identifier, int statusMode) {
        if (this.console.getPerspective() == CommandCard.Perspective.NATIVE_REPLAY) {
            return Result.IGNORED;
        }

        if (identifier >= FIRST_QUEUE_CONTROL && identifier <= LAST_QUEUE_CONTROL) {
            return this.sendCommand(ButtonAction.CANCEL_LAST, identifier - FIRST_QUEUE_CONTROL, false);
        }

        if (identifier == RESEARCH_CONTROL) {
            if (statusMode == RESEARCH_MODE) {
                return this.sendCommand(ButtonAction.CANCEL_RESEARCH, 0, false);
            }

            if (statusMode == UPGRADE_MODE) {
                return this.sendCommand(ButtonAction.CANCEL_UPGRADE, 0, false);
            }

            return Result.IGNORED;
        }

        if (identifier == EGG_ADDON_CONTROL) {
            Unit active = this.console.getSelection().getActiveUnit();

            if (active == null) {
                return Result.IGNORED;
            }

            int type = active.getUnitId();

            if (this.console.getView().requireGameData().getUnitTypes().isEgg(type)) {
                if (UnitOrderEligibility.isDisabled(active)) {
                    return Result.IGNORED;
                }

                return this.sendCommand(ButtonAction.CANCEL_CONSTRUCTION, 0, false);
            }

            return this.sendCommand(ButtonAction.CANCEL_ADDON, 0, false);
        }

        return Result.IGNORED;
    }

    /**
     * Unloads one unit from the selected transport, bunker or Nydus canal: the cargo slot click of the status panel (event
     * {@code 14/2}) sends command {@code 0x62} with the loaded unit's handle (no sound, no queueing).
     *
     * @param loaded the loaded unit
     * @return the effect
     */
    public @NotNull Result unload(@NotNull Unit loaded) {
        UnitPool pool = this.console.getView().getUnitSystem().getUnitPool();
        Unit active = this.console.getSelection().getActiveUnit();

        if (pool == null || active == null || active.getOwnerIndex() != this.console.getPlayer()) {
            return Result.IGNORED;
        }

        ReplayValueCommand command = new ReplayValueCommand(pool.getHandle(loaded.getIndex()), UNIT_HANDLE_BYTES);
        byte[] payload = ReplayCommandCodecs.encode(ReplayCommandCodecs.UNLOAD_121, command);

        this.sink.submit(this.console.getPlayer(), ReplayCommandIds.UNLOAD_121, payload);

        return Result.COMMAND;
    }

    /**
     * Presses and releases the first enabled matching hotkey slot immediately.
     * The interactive held-hotkey path uses HotkeyPress to defer release.
     *
     * @param profile   the active hotkey profile
     * @param character the typed character
     * @param queued    whether the command is queued (shift)
     * @return the effect of the press
     */
    public @NotNull Result pressHotkey(@NotNull HotkeyProfile profile, char character, boolean queued) {
        int slot = this.findHotkeySlot(profile, character);

        return slot < 0 ? Result.IGNORED : this.pressSlot(slot, queued, true);
    }

    /**
     * Encodes and submits a targeting click, using the order fog-type rule, then clears the targeting mode. The targeting of a script target
     * button hands the target to the game plugins instead ({@link ScrGamePlugin#onButtonInput}): a target a plugin consumes keeps the mode.
     *
     * @param positionX the target map x coordinate
     * @param positionY the target map y coordinate
     * @param target    the clicked unit, or {@code null} for a ground target
     * @param fogType   the type of the remembered building under the click, or {@link #NO_UNIT_TYPE}
     * @param queued    whether the order is queued
     * @return the effect of the click
     * @throws NullPointerException if the world was built without game data
     */
    public @NotNull Result clickTarget(int positionX, int positionY, @Nullable Unit target, int fogType, boolean queued) {
        InterfaceMode mode = this.console.getMode();
        ScrButtonInput scriptButton = mode.getScriptButton();

        if (scriptButton != null) {
            if (this.console.getView().deliverButtonInput(scriptButton.createTargeted(positionX, positionY, target, queued))) {
                return Result.INTERFACE;
            }

            mode.clear();

            return Result.COMMAND;
        }

        int order = target != null ? mode.getUnitTargetOrder() : mode.getGroundTargetOrder();

        if (order < 0) {
            return Result.IGNORED;
        }

        ReplayTargetCommand command = ReplayTargetCommand.createTargetedOrder(positionX, positionY,
                                                                              this.getTargetHandle(target), UNIT_HANDLE_BYTES,
                                                                              this.getTargetFogType(order, fogType), order, queued ? 1 : 0);
        byte[] payload = ReplayCommandCodecs.encode(ReplayCommandCodecs.TARGETED_ORDER_121, command);

        this.sink.submit(this.console.getPlayer(), ReplayCommandIds.TARGETED_ORDER_121, payload);
        mode.clear();

        return Result.COMMAND;
    }

    /**
     * Encodes and submits the active placement order, tile coordinates and unit type, then clears placement.
     * Unsupported placement actions are ignored.
     *
     * @param tileX the left footprint tile
     * @param tileY the top footprint tile
     * @return the effect of the click
     */
    public @NotNull Result clickPlacement(int tileX, int tileY) {
        InterfaceMode mode = this.console.getMode();
        int unitType = mode.getPlacementType();

        if (unitType < 0) {
            return Result.IGNORED;
        }

        int order = switch (mode.getPlacementAction()) {
            case ZERG_BUILD -> ScrOrderIds.DRONE_START_BUILD;
            case TERRAN_BUILD -> ScrOrderIds.PLACE_BUILDING;
            case PROTOSS_BUILD -> ScrOrderIds.PLACE_PROTOSS_BUILDING;
            case BUILD_ADDON -> ScrOrderIds.PLACE_ADDON;
            case LAND -> ScrOrderIds.BUILDING_LAND;
            default -> -1;
        };

        if (order < 0) {
            return Result.IGNORED;
        }

        byte[] payload = ReplayCommandCodecs.encode(ReplayCommandCodecs.BUILD, new ReplayBuildCommand(order, tileX, tileY, unitType));

        this.sink.submit(this.console.getPlayer(), ReplayCommandIds.BUILD, payload);
        mode.clear();

        return Result.COMMAND;
    }

    /**
     * Encodes and submits a right-click position, target handle, fog type and queue flag.
     *
     * @param positionX the map x coordinate
     * @param positionY the map y coordinate
     * @param target    the clicked unit, or {@code null}
     * @param fogType   the type of the remembered building under the click, or {@link #NO_UNIT_TYPE}
     * @param queued    whether the order is queued
     * @return {@link Result#COMMAND}
     */
    public @NotNull Result rightClick(int positionX, int positionY, @Nullable Unit target, int fogType, boolean queued) {
        ReplayTargetCommand command = ReplayTargetCommand.createRightClick(positionX, positionY,
                                                                           this.getTargetHandle(target), UNIT_HANDLE_BYTES,
                                                                           fogType, queued ? 1 : 0);
        byte[] payload = ReplayCommandCodecs.encode(ReplayCommandCodecs.RIGHT_CLICK_121, command);

        this.sink.submit(this.console.getPlayer(), ReplayCommandIds.RIGHT_CLICK_121, payload);

        return Result.COMMAND;
    }

    /**
     * Sends a selection ({@code 0x63}) of up to twelve units; the local selection shows them at once
     * ({@link ConsoleModel#showPendingSelection}).
     *
     * @param units the units to select
     * @return {@link Result#COMMAND}, or {@link Result#IGNORED} without units or unit pool
     */
    public @NotNull Result select(@NotNull Unit @NotNull ... units) {
        UnitPool pool = this.console.getView().getUnitSystem().getUnitPool();

        if (units.length == 0 || units.length > InterfaceSelection.CAPACITY || pool == null) {
            return Result.IGNORED;
        }

        long[] handles = new long[units.length];

        for (int index = 0; index < units.length; index++) {
            handles[index] = Integer.toUnsignedLong(pool.getHandle(units[index].getIndex()));
        }

        byte[] payload = ReplayCommandCodecs.encode(ReplayCommandCodecs.SELECT_121,
                                                    ReplaySelectionCommand.create(SelectionRequest.Operation.REPLACE, UNIT_HANDLE_BYTES, handles));

        this.console.showPendingSelection(units);
        this.sink.submit(this.console.getPlayer(), ReplayCommandIds.SELECT_121, payload);

        return Result.COMMAND;
    }

    private @NotNull Result executeButton(@NotNull ButtonDefinition button, @NotNull ScrButtonInput press, boolean queued) {
        ButtonAction action = button.getAction();
        int value = button.getActionValue();
        InterfaceMode mode = this.console.getMode();

        if (isGated(action) && !this.passesGate(value)) {
            return Result.INTERFACE;
        }

        if (!this.passesUnitGate(action, value)) {
            return Result.INTERFACE;
        }

        return switch (action.getKind()) {
            case COMMAND -> action == ButtonAction.UNLOAD_ALL ? this.unloadAll(queued) : this.sendCommand(action, value, queued);
            case TARGET -> {
                int[] orders = this.getTargetOrders(action, value);

                if (orders == null) {
                    yield Result.IGNORED;
                }

                mode.startTargeting(orders[UNIT_TARGET_ORDER], orders[GROUND_TARGET_ORDER], orders[OBSCURED_TARGET_ORDER]);

                yield Result.INTERFACE;
            }
            case PLACEMENT -> {
                mode.startPlacement(action, value);

                yield Result.INTERFACE;
            }
            case INTERFACE -> switch (action) {
                case CHANGE_BUTTONS -> {
                    this.console.getCommandCard().changeButtons(value);

                    yield Result.INTERFACE;
                }
                case CANCEL_TARGETING, CANCEL_PLACEMENT -> {
                    mode.clear();

                    yield Result.INTERFACE;
                }
                case SELECT_LARVA -> this.interfaceActions == null ? Result.IGNORED : this.interfaceActions.selectLarva();
                default -> Result.IGNORED;
            };
            case SCRIPT -> Result.INTERFACE;
            case SCRIPT_TARGET -> {
                mode.startScriptTargeting(press);

                yield Result.INTERFACE;
            }
            case REPLAY -> {
                ReplayControls controls = this.replayControls;

                if (controls == null) {
                    yield Result.IGNORED;
                }

                switch (action) {
                    case REPLAY_PLAY_PAUSE -> controls.playOrPause();
                    case REPLAY_SLOW_DOWN -> controls.decreaseSpeed();
                    case REPLAY_SPEED_UP -> controls.increaseSpeed();
                    default -> {
                        yield Result.IGNORED;
                    }
                }

                yield Result.INTERFACE;
            }
        };
    }

    /**
     * Unloads the selected transports. A grounded building as the active unit sends Unload All at once; any other active unit
     * enters MoveUnload targeting for its unit, ground and obscured targets, and the targeting click sends the order.
     *
     * @param queued whether the command is queued (shift)
     * @return {@link Result#COMMAND} for a grounded building, {@link Result#INTERFACE} otherwise
     */
    private @NotNull Result unloadAll(boolean queued) {
        if (this.isActiveUnitGroundedBuilding()) {
            return this.sendCommand(ButtonAction.UNLOAD_ALL, 0, queued);
        }

        this.console.getMode().startTargeting(ScrOrderIds.MOVE_UNLOAD, ScrOrderIds.MOVE_UNLOAD, ScrOrderIds.MOVE_UNLOAD);

        return Result.INTERFACE;
    }

    /**
     * Runs the right-click target test ({@link TargetValidation#testRightClick}) after a right click was sent and shows its error.
     *
     * @param target the clicked unit, or {@code null}
     * @return {@code true} if the right click is acknowledged, {@code false} otherwise
     */
    public boolean checkRightClick(@Nullable Unit target) {
        TargetValidation.Outcome outcome = TargetValidation.testRightClick(this.console.getView(), this.console.getSelection(), target);

        this.showTargetError(outcome);

        return outcome.isAccepted();
    }

    /**
     * @param order   the order of a targeting click
     * @param fogType the type of the remembered building under the click, or {@link #NO_UNIT_TYPE}
     * @return the fog type when the order has an obscured order, {@link #NO_UNIT_TYPE} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    private int getTargetFogType(int order, int fogType) {
        return this.console.getView().requireGameData().getOrderTypes().getObscuredOrder(order) != ScrOrderIds.NONE ? fogType : NO_UNIT_TYPE;
    }

    /**
     * Runs the target test of a targeting click ({@link TargetValidation#testTargetClick}) before the order is sent and shows its error with its
     * sound. The supplied fog type is used only when the order has an obscured order.
     *
     * @param target    the clicked unit, or {@code null} for a ground click
     * @param fogType   the type of the remembered building under the click, or {@link #NO_UNIT_TYPE}
     * @param positionX the map x
     * @param positionY the map y
     * @return the result: whether the order is sent, and acknowledged when there is no error
     * @throws NullPointerException if the world was built without game data
     */
    public TargetValidation.@NotNull Click checkTargetClick(@Nullable Unit target, int fogType, int positionX, int positionY) {
        InterfaceMode mode = this.console.getMode();
        int order = target != null ? mode.getUnitTargetOrder() : mode.getGroundTargetOrder();

        if (order < 0) {
            return new TargetValidation.Click(CommandErrorEvents.NO_ERROR_TEXT, "", 0, true);
        }

        TargetValidation.Click click = TargetValidation.testTargetClick(this.console.getView(),
                                                                        this.console.getSelection(),
                                                                        order,
                                                                        target, this.getTargetFogType(order, fogType),
                                                                        positionX, positionY);
        Unit active = this.console.getSelection().getActiveUnit();

        if (click.getError() != CommandErrorEvents.NO_ERROR_TEXT && this.errorOutput != null && active != null &&
            active.getOwnerIndex() == this.console.getPlayer()) {
            this.errorOutput.showError(click.getError(), click.getSound(), click.getRace());
        }

        return click;
    }

    private void showTargetError(TargetValidation.@NotNull Outcome outcome) {
        if (outcome.getError() != CommandErrorEvents.NO_ERROR_TEXT && this.errorOutput != null) {
            this.errorOutput.showError(outcome.getError(), TargetValidation.CANT_ATTACK_SOUND, outcome.getRace());
        }
    }

    /**
     * Requires a selected unit to satisfy Stim Packs life or technology energy checks for the applicable action.
     * A failure reports the active local unit race and the corresponding error text and sound.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private boolean passesUnitGate(@NotNull ButtonAction action, int value) {
        if (action != ButtonAction.USE_TECHNOLOGY && action != ButtonAction.CLOAK && action != ButtonAction.STIM_PACKS) {
            return true;
        }

        ScrGameView view = this.console.getView();
        InterfaceSelection selection = this.console.getSelection();
        boolean unlimited = (view.getCheatFlags() & GameCheats.THE_GATHERING) != 0;

        for (int slot = 0; slot < UnitSelection.MAXIMUM_SELECTED; slot++) {
            Unit unit = selection.getUnit(slot);

            if (unit == null) {
                continue;
            }

            if (action == ButtonAction.STIM_PACKS) {
                if (unit.getHitPoints() > STIM_MINIMUM_HIT_POINTS) {
                    return true;
                }

                continue;
            }

            int technology = action == ButtonAction.CLOAK ? UnitQueries.getCloakTechnology(view.requireGameData().getUnitTypes(), unit) : value;

            if (technology < 0 || technology >= ScrTechIds.NONE) {
                continue;
            }

            int cost = view.requireGameData().getTechTypes().getEnergyCost(technology) << ENERGY_FRACTION_BITS;

            if (unlimited || unit.getEnergy() >= cost) {
                return true;
            }
        }

        Unit active = selection.getActiveUnit();

        if (this.errorOutput != null && active != null && active.getOwnerIndex() == this.console.getPlayer()) {
            int race = UnitRacesUtils.getSupplyRace(view.requireGameData().getUnitTypes(), active.getUnitId());

            if (action == ButtonAction.STIM_PACKS) {
                this.errorOutput.showError(NOT_ENOUGH_LIFE, TargetValidation.ERROR_SOUND, race);
            } else {
                this.errorOutput.showError(TargetValidation.NOT_ENOUGH_ENERGY + race, TargetValidation.NEED_ENERGY_SOUND, race);
            }
        }

        return false;
    }

    /**
     * Reports a local placement error with the active unit race and the landing or build error sound.
     *
     * @param statTextIdentifier the one-based {@code stat_txt} id
     * @param land               whether the placement is a landing
     * @return {@code true} if a local-player error was reported, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    public boolean showPlacementError(int statTextIdentifier, boolean land) {
        Unit active = this.console.getSelection().getActiveUnit();

        if (this.errorOutput == null || active == null || active.getOwnerIndex() != this.console.getPlayer()) {
            return false;
        }

        int race = UnitRacesUtils.getDisplayRace(this.console.getView().requireGameData().getUnitTypes(), active.getUnitId());

        this.errorOutput.showError(statTextIdentifier, land ? LAND_ERROR_SOUND : BUILD_ERROR_SOUND, race);

        return true;
    }

    private boolean passesGate(int unitType) {
        Unit active = this.console.getSelection().getActiveUnit();
        int owner = active != null ? active.getOwnerIndex() : this.console.getPlayer();
        ScrGameView view = this.console.getView();

        if (owner < 0 || owner >= PlayerTable.PLAYER_SLOTS) {
            return true;
        }

        ProductionGate.Failure failure = ProductionGate.check(view, this.console.getPresentation(), owner, unitType);

        if (failure == null) {
            return true;
        }

        if (this.errorOutput != null && owner == this.console.getPlayer()) {
            int race = failure.getSoundRace(UnitRacesUtils.getSupplyRace(view.requireGameData().getUnitTypes(), unitType), this.console.getRace());

            this.errorOutput.showError(failure.getTextIdentifier(race), failure.getSoundName(), race);
        }

        return false;
    }

    private @NotNull Result sendCommand(@NotNull ButtonAction action, int value, boolean queued) {
        if (action == ButtonAction.RETURN_CARGO && this.acknowledgement != null) {
            this.acknowledgement.run();
        }

        int opcode = action.getOpcode();
        byte[] payload = switch (action) {
            case STOP,
                 HOLD_POSITION,
                 SIEGE,
                 UNSIEGE,
                 BURROW,
                 UNBURROW,
                 RETURN_CARGO,
                 UNLOAD_ALL,
                 CLOAK,
                 DECLOAK -> ReplayCommandCodecs.encodeValue(opcode, queued ? 1 : 0);
            case TRAIN, UNIT_MORPH, BUILDING_MORPH, CANCEL_LAST, RESEARCH, UPGRADE -> ReplayCommandCodecs.encodeValue(opcode, value);
            case LIFT_OFF -> {
                Unit active = this.console.getSelection().getActiveUnit();

                if (active == null) {
                    yield null;
                }

                yield ReplayCommandCodecs.encode(ReplayCommandCodecs.LIFT_OFF, new ReplayPositionCommand(active.getPositionX(), active.getPositionY()));
            }
            default -> new byte[0];
        };

        if (payload == null || opcode < 0) {
            return Result.IGNORED;
        }

        this.sink.submit(this.console.getPlayer(), opcode, payload);

        return Result.COMMAND;
    }

    /**
     * Finds whether any locally selected unit passes the game movement query.
     */
    private boolean hasSelectedMovableUnit() {
        InterfaceSelection selection = this.console.getSelection();

        for (int slot = 0; slot < InterfaceSelection.CAPACITY; slot++) {
            Unit unit = selection.getUnit(slot);

            if (unit != null && this.console.getView().getUnitQueries().canMove(unit)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Tests the active unit for the grounded-building status, which selects the Load targets and the Unload All effect.
     *
     * @return {@code true} if the active unit is a grounded building, {@code false} otherwise or without an active unit
     */
    private boolean isActiveUnitGroundedBuilding() {
        Unit active = this.console.getSelection().getActiveUnit();

        return active != null && UnitQueries.isGroundedBuilding(active);
    }

    private long getTargetHandle(@Nullable Unit target) {
        ScrGameView view = this.console.getView();
        UnitPool pool = view.getUnitSystem().getUnitPool();

        return target == null || pool == null ? 0 : Integer.toUnsignedLong(pool.getHandle(target.getIndex()));
    }
}
