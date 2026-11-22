package org.fogofwarlabs.sc4j.scr.client.ui.command;

import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedUnitType;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedUnitTypes;
import org.fogofwarlabs.sc4j.scr.content.ScrUnits;
import org.fogofwarlabs.sc4j.scr.data.type.ScrOrderTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ButtonSetIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.presentation.HostButtonSets;
import org.fogofwarlabs.sc4j.scr.game.presentation.ScrButtonStates;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Models the nine command-card slots from the local selection and game state.
 * Replay or interface sets take priority over mixed-selection and active-unit sets. Conditions are walked in table order to fill each position;
 * icon changes cancel held slot presses. The model changes interface state only.
 */
public final class CommandCard {
    /**
     * The number of command card slots.
     */
    public static final int SLOT_COUNT = 9;

    /**
     * The slot state of an empty slot.
     */
    public static final int HIDDEN = 0;

    /**
     * The slot state of an enabled button.
     */
    public static final int ENABLED = 1;

    /**
     * The slot state of a disabled button.
     */
    public static final int DISABLED = 2;

    /**
     * The slot state of a button whose condition reached state without an established domain meaning.
     */
    public static final int UNRESOLVED = 3;

    /**
     * The {@code stat_txt} string the refresh substitutes for the disabled string after failure code {@code 0x15}.
     */
    public static final int UNAVAILABLE_STRING = 0x2fa;

    /**
     * The highlight WORD without a highlighted button.
     */
    public static final int NO_HIGHLIGHT = ScrOrderTypeView.NO_HIGHLIGHT;

    private static final int REPLAY_PAUSED_SET = ButtonSetIds.REPLAY_PAUSED;
    private static final int REPLAY_PLAYING_SET = ButtonSetIds.REPLAY_PLAYING;
    private static final int TARGETING_SET = ButtonSetIds.CANCEL_TARGETING;
    private static final int PLACEMENT_SET = ButtonSetIds.CANCEL_PLACEMENT;
    private static final int GENERIC_SET = ButtonSetIds.MIXED_UNITS;
    private static final int WORKER_SET = ButtonSetIds.MIXED_WORKERS;
    private static final int CLOAK_SET = ButtonSetIds.MIXED_CLOAKABLE_UNITS;
    private static final int BURROW_SET = ButtonSetIds.MIXED_BURROWABLE_UNITS;
    /**
     * The Stop icon: a main order highlighting it gives way to the subunit's order highlight.
     */
    private static final int STOP_ICON = 229;
    private static final int WORD_MASK = 0xFFFF;

    /**
     * The buttons of a table entry without an array.
     */
    private static final @NotNull ButtonDefinition @NotNull [] NO_BUTTONS = new ButtonDefinition[0];

    /**
     * Selects whose command card is shown.
     */
    public enum Perspective {
        /**
         * The card of the selection's player in a live game; replay conditions are not applied.
         */
        PLAYER,
        /**
         * The card of the native replay client, which shows the replay controls.
         */
        NATIVE_REPLAY
    }

    private static @NotNull ButtonDefinition @NotNull [] convertHostButtons(HostButtonSets.@NotNull Button @NotNull [] buttons) {
        ButtonDefinition[] converted = new ButtonDefinition[buttons.length];
        int count = 0;

        for (int index = 0; index < buttons.length; index++) {
            HostButtonSets.Button button = buttons[index];
            ButtonCondition condition = ButtonCondition.ofNativeAddress(button.getCondition());
            ButtonAction action = ButtonAction.ofNativeAddress(button.getAction());

            if (condition == null || action == null) {
                continue;
            }

            converted[count++] = new ButtonDefinition(button.getPosition(), button.getIcon(), condition, button.getConditionValue(),
                                                      action, button.getActionValue(), button.getEnabledString(), button.getDisabledString(),
                                                      button.getEnabledText(), button.getDisabledText(), index);
        }

        return count == converted.length ? converted : Arrays.copyOf(converted, count);
    }

    private final @NotNull ScrGameView view;
    private final @NotNull ButtonConditionEvaluator conditions;
    /**
     * Converted host button arrays cached by source array identity, for the host table's revision {@link #hostRevision}.
     */
    private final @NotNull Map<HostButtonSets.Button[], ButtonDefinition[]> hostSets;
    private int hostRevision;
    /**
     * The button arrays of the sets that show train buttons of added unit types, by set: the source array and the array with the train buttons.
     */
    private final @NotNull Map<Integer, ButtonDefinition @NotNull [] @NotNull []> trainButtonSets;

    private final @Nullable ButtonDefinition @NotNull [] slotButtons;
    private final int @NotNull [] slotStates;
    private final int @NotNull [] slotDisabledStrings;
    /**
     * The icon each slot showed after the last refresh, {@code -1} for a hidden slot.
     */
    private final int @NotNull [] shownIcons;
    /**
     * The cancellation count for each slot when its displayed icon changes.
     */
    private final int @NotNull [] pressCancellations;

    private int interfaceSet;
    private int mixedSelectionSet;
    private int displayedSet;
    private int highlightedIcon;

    private @Nullable Unit lastActiveUnit;
    private int lastSelectionCount;
    private int lastSelections;

    /**
     * Creates a {@link CommandCard} instance reading the given game state.
     *
     * @param view the game view
     */
    public CommandCard(@NotNull ScrGameView view) {
        this.view       = view;
        this.conditions = new ButtonConditionEvaluator(view);
        this.hostSets   = new IdentityHashMap<>();

        this.hostRevision = 0;

        this.trainButtonSets = new HashMap<>();

        this.slotButtons         = new ButtonDefinition[SLOT_COUNT];
        this.slotStates          = new int[SLOT_COUNT];
        this.slotDisabledStrings = new int[SLOT_COUNT];
        this.shownIcons          = new int[SLOT_COUNT];
        this.pressCancellations  = new int[SLOT_COUNT];

        this.interfaceSet      = ButtonSetTable.NO_SET;
        this.mixedSelectionSet = ButtonSetTable.NO_SET;
        this.displayedSet      = ButtonSetTable.NO_SET;
        this.highlightedIcon   = NO_HIGHLIGHT;

        this.lastActiveUnit     = null;
        this.lastSelectionCount = 0;
        this.lastSelections     = 0;

        Arrays.fill(this.shownIcons, -1);
    }

    /**
     * @return the displayed button set WORD, {@link ButtonSetTable#NO_SET} for none
     */
    public int getDisplayedSet() {
        return this.displayedSet;
    }

    /**
     * Sets the displayed set to the low sixteen bits of a displayed-set write.
     * A refresh replaces it when there is a set to show; otherwise the written value remains.
     *
     * @param set the written WORD
     */
    public void setDisplayedSet(int set) {
        this.displayedSet = set & WORD_MASK;
    }

    /**
     * Returns the displayed icon selected from the active unit order and eligible subunit order.
     *
     * @return the icon frame, or {@link #NO_HIGHLIGHT}
     */
    public int getHighlightedIcon() {
        return this.highlightedIcon;
    }

    /**
     * Returns how often an icon change canceled a held slot press.
     * A hotkey press remains active only while its slot keeps the same icon.
     *
     * @param slot the slot from {@code 0} through {@code 8}
     * @return the cancellation count, compared before and after a press
     * @throws IndexOutOfBoundsException if {@code slot} is outside {@code 0} through {@code 8}
     */
    public int getPressCancellations(int slot) {
        return this.pressCancellations[Objects.checkIndex(slot, SLOT_COUNT)];
    }

    /**
     * @return the interface set chosen locally, or {@link ButtonSetTable#NO_SET}
     */
    public int getInterfaceSet() {
        return this.interfaceSet;
    }

    /**
     * @param slot the slot from {@code 0} (position {@code 1}) through {@code 8}
     * @return the button shown in the slot, or {@code null} for an empty slot
     * @throws IndexOutOfBoundsException if {@code slot} is outside {@code 0} through {@code 8}
     */
    public @Nullable ButtonDefinition getButton(int slot) {
        return this.slotButtons[Objects.checkIndex(slot, SLOT_COUNT)];
    }

    /**
     * @param slot the slot from {@code 0} through {@code 8}
     * @return {@link #HIDDEN}, {@link #ENABLED}, {@link #DISABLED} or {@link #UNRESOLVED}
     * @throws IndexOutOfBoundsException if {@code slot} is outside {@code 0} through {@code 8}
     */
    public int getState(int slot) {
        return this.slotStates[Objects.checkIndex(slot, SLOT_COUNT)];
    }

    /**
     * @param slot the slot from {@code 0} through {@code 8}
     * @return the one-based {@code stat_txt} string shown for a disabled button
     * @throws IndexOutOfBoundsException if {@code slot} is outside {@code 0} through {@code 8}
     */
    public int getDisabledString(int slot) {
        return this.slotDisabledStrings[Objects.checkIndex(slot, SLOT_COUNT)];
    }

    /**
     * Returns the default button table or converts a committed host table once per button-array identity.
     * Entries with no host array share the empty array. Rows whose condition or action cannot be resolved are skipped.
     *
     * @param set the button set from {@code 0} through {@code 249}
     * @return the buttons; entries without buttons share one empty array, as their null pointers compare equal
     */
    private @NotNull ButtonDefinition @NotNull [] getButtons(int set) {
        HostButtonSets host = this.view.getGameContext().getHostButtonSets();

        if (!host.isReplaced()) {
            this.hostSets.clear();

            return this.addTrainButtons(set, ButtonSetTable.getButtons(set));
        }

        HostButtonSets.Button[] buttons = host.getButtons(set);

        if (buttons == null) {
            return this.addTrainButtons(set, NO_BUTTONS);
        }

        // A changed table drops the conversions of the arrays it no longer holds.
        if (host.getRevision() != this.hostRevision) {
            this.hostRevision = host.getRevision();
            this.hostSets.keySet().removeIf(array -> !isHeld(host, array));
        }

        return this.addTrainButtons(set, this.hostSets.computeIfAbsent(buttons, CommandCard::convertHostButtons));
    }

    private static boolean isHeld(@NotNull HostButtonSets host, HostButtonSets.@NotNull Button @NotNull [] array) {
        for (int set = 0; set < HostButtonSets.SET_COUNT; set++) {
            if (host.getButtons(set) == array) {
                return true;
            }
        }

        return false;
    }

    /**
     * Finds the index of a shown button in its set's table: the entry of the game's changed table, else of the default table.
     *
     * @param set    the button set the button was shown in
     * @param button the button
     * @return the index, or {@link ButtonDefinition#NO_TABLE_INDEX} for a button that is no entry of the set's table
     */
    public int findTableIndex(int set, @NotNull ButtonDefinition button) {
        if (button.getTableIndex() != ButtonDefinition.NO_TABLE_INDEX || set >= ButtonSetTable.SET_COUNT ||
            this.view.getGameContext().getHostButtonSets().isReplaced()) {
            return button.getTableIndex();
        }

        ButtonDefinition[] buttons = ButtonSetTable.getButtons(set);

        for (int index = 0; index < buttons.length; index++) {
            if (buttons[index] == button) {
                return index;
            }
        }

        return ButtonDefinition.NO_TABLE_INDEX;
    }

    /**
     * Adds the production buttons of the added unit types whose producer is the unit type of a set
     * ({@link ScrAddedUnitType#getTrainButton()}): each follows the set's buttons of its position, with the icon of the added type's wireframe
     * type, the unit requirement condition and the train or building morph action of the added type
     * ({@link ScrAddedUnitType.ProductionAction}), and the button's own texts. The array is built once per set and source array.
     *
     * @param set     the button set
     * @param buttons the set's buttons in position order
     * @return the buttons with the train buttons, or {@code buttons} itself if no added type has a train button in the set
     * @throws NullPointerException if the world was built without game data
     */
    private @NotNull ButtonDefinition @NotNull [] addTrainButtons(int set, @NotNull ButtonDefinition @NotNull [] buttons) {
        ScrAddedUnitTypes added = this.view.requireGameData().getAddedUnitTypes();

        if (added.getCount() == 0) {
            return buttons;
        }

        ButtonDefinition[][] cached = this.trainButtonSets.get(set);

        if (cached != null && cached[0] == buttons) {
            return cached[1];
        }

        List<ButtonDefinition> merged = new ArrayList<>(Arrays.asList(buttons));

        for (ScrAddedUnitType type : added.getTypes()) {
            ScrAddedUnitType.TrainButton button = type.getTrainButton();

            if (button == null || ScrUnits.getNativeId(button.getProducerType()) != set) {
                continue;
            }

            int unitType = added.getId(type.getType());
            int index = 0;

            // The button goes after every button up to its position, so the set's own buttons of the position stay ahead of it.
            while (index < merged.size() && merged.get(index).getPosition() <= button.getPosition()) {
                index++;
            }

            ButtonAction action = switch (button.getAction()) {
                case TRAIN -> ButtonAction.TRAIN;
                case BUILDING_MORPH -> ButtonAction.BUILDING_MORPH;
            };

            merged.add(index, new ButtonDefinition(button.getPosition(), this.view.requireGameData().getCommandIcon(unitType),
                                                   ButtonCondition.UNIT_REQUIREMENT, unitType,
                                                   action, unitType,
                                                   button));
        }

        ButtonDefinition[] result = merged.size() == buttons.length ? buttons : merged.toArray(new ButtonDefinition[0]);

        this.trainButtonSets.put(set, new ButtonDefinition[][]{buttons, result});

        return result;
    }

    /**
     * @param set the button set from {@code 0} through {@code 249}
     * @return the connected set WORD of the set in the game's table (see {@link #getButtons(int)})
     */
    private int getConnectedSet(int set) {
        HostButtonSets host = this.view.getGameContext().getHostButtonSets();

        return host.isReplaced() ? host.getConnectedSet(set) : ButtonSetTable.getConnectedSet(set);
    }

    /**
     * Shows another button set, as {@link ButtonAction#CHANGE_BUTTONS} does: values of {@code 250} and above select no interface set.
     *
     * @param set the button set of the action value
     */
    public void changeButtons(int set) {
        this.interfaceSet = set < ButtonSetTable.SET_COUNT ? set : ButtonSetTable.NO_SET;
    }

    /**
     * Recomputes the shown set, slot conditions, press cancellations and highlighted icon in that order.
     * A changed active unit, selection count or selection revision resets a locally chosen set outside targeting and placement.
     *
     * @param selection     the local selection
     * @param perspective   whose card is shown
     * @param replayPaused  whether the replay is paused, for the replay perspective
     * @param speedSetting  the game speed setting index for the replay conditions
     * @param replaySpeed   the replay speed multiplier for the replay conditions
     * @param placementMode whether building placement is active
     * @param targetingMode whether a targeting mode is active (byte)
     */
    public void update(@NotNull InterfaceSelection selection,
                       @NotNull Perspective perspective,
                       boolean replayPaused, int speedSetting, int replaySpeed,
                       boolean placementMode, boolean targetingMode) {
        boolean replay = perspective == Perspective.NATIVE_REPLAY;
        Unit active = selection.getActiveUnit();

        if (active != this.lastActiveUnit || selection.getCount() != this.lastSelectionCount ||
            selection.getSelectionCount() != this.lastSelections) {
            this.lastActiveUnit     = active;
            this.lastSelectionCount = selection.getCount();
            this.lastSelections     = selection.getSelectionCount();

            if (this.interfaceSet != PLACEMENT_SET && this.interfaceSet != TARGETING_SET) {
                this.interfaceSet = ButtonSetTable.NO_SET;
            }

            // A changed selection recomputes the card, which resets the shown set to 0xE4.
            this.displayedSet = ButtonSetTable.NO_SET;
        }

        int requestedSet = this.view.getButtonStates().takeRequestedButtonSet();

        if (requestedSet != ScrButtonStates.NO_REQUEST) {
            this.changeButtons(requestedSet);
        }

        // Entering placement sets button set 230 and targeting sets 229 whatever card was shown, including a
        // Build submenu; leaving either mode returns to the unit's own set (228 = none chosen, 0xe4).
        if (!replay && (placementMode || targetingMode) && selection.getCount() != 0) {
            this.interfaceSet = placementMode ? PLACEMENT_SET : TARGETING_SET;
        } else if (this.interfaceSet == PLACEMENT_SET || this.interfaceSet == TARGETING_SET) {
            this.interfaceSet = ButtonSetTable.NO_SET;
        }

        int overrideSet = this.interfaceSet;

        if (replay) {
            overrideSet = replayPaused ? REPLAY_PAUSED_SET : REPLAY_PLAYING_SET;
        }

        this.mixedSelectionSet = replay || active == null ? ButtonSetTable.NO_SET : this.computeMixedSelectionSet(selection, active);

        int set = overrideSet != ButtonSetTable.NO_SET
                  ? overrideSet
                  : this.mixedSelectionSet != ButtonSetTable.NO_SET
                    ? this.mixedSelectionSet
                    : active != null ? active.getCurrentButtonSet() : ButtonSetTable.NO_SET;

        // A set to show replaces the shown WORD; without one the WORD (an EUD write included) stays.
        if (set < ButtonSetTable.SET_COUNT) {
            this.displayedSet = set;
        }

        this.conditions.setReplayControls(replay, speedSetting, replaySpeed);
        this.refreshSlots(selection, replay);

        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            ButtonDefinition button = this.slotButtons[slot];
            int icon = button == null ? -1 : button.getIcon();

            if (this.shownIcons[slot] != -1 && icon != this.shownIcons[slot]) {
                this.pressCancellations[slot]++;
            }

            this.shownIcons[slot] = icon;
        }

        this.highlightedIcon = this.computeHighlightedIcon(active);
    }

    /**
     * Selects the main order highlight, yielding to an eligible subunit highlight when the main icon is absent or Stop.
     * Only an icon present in a shown slot is retained; without an active unit the previous highlight remains.
     *
     * @param active the active unit, or {@code null}
     * @return the highlighted icon, or {@link #NO_HIGHLIGHT}
     * @throws NullPointerException if the world was built without game data
     */
    private int computeHighlightedIcon(@Nullable Unit active) {
        if (active == null) {
            return this.highlightedIcon;
        }

        ScrOrderTypeView orders = this.view.requireGameData().getOrderTypes();
        int icon = orders.getHighlight(active.getPrimaryOrder()) & WORD_MASK;
        Unit subunit = active.getSubunit();
        int subunitIcon = subunit == null ? NO_HIGHLIGHT : orders.getHighlight(subunit.getPrimaryOrder()) & WORD_MASK;

        if (icon == NO_HIGHLIGHT || subunitIcon != NO_HIGHLIGHT && icon == STOP_ICON) {
            icon = subunitIcon;
        }

        for (ButtonDefinition button : this.slotButtons) {
            if (button != null && button.getIcon() == icon) {
                return icon;
            }
        }

        return NO_HIGHLIGHT;
    }

    /**
     * Compares button-array identity and connected sets across selected units.
     * Mixed selections then choose the worker, cloak or burrow set when every counted unit meets that category.
     *
     * @param selection the local selection
     * @param active    the active unit
     * @return the mixed-selection button set, or {@link ButtonSetTable#NO_SET}
     * @throws NullPointerException if the world was built without game data
     */
    private int computeMixedSelectionSet(@NotNull InterfaceSelection selection, @NotNull Unit active) {
        if (selection.getCount() <= 1) {
            return ButtonSetTable.NO_SET;
        }

        ScrUnitTypeView units = this.view.requireGameData().getUnitTypes();
        int baseSet = active.getCurrentButtonSet();
        int result = ButtonSetTable.NO_SET;
        int workers = 0;
        int cloakable = 0;
        int zergGround = 0;
        boolean burrowable = false;

        for (int slot = 0; slot < InterfaceSelection.CAPACITY; slot++) {
            Unit unit = selection.getUnit(slot);

            if (unit == null) {
                continue;
            }

            int unitSet = unit.getCurrentButtonSet();
            int abilities = units.getFlags(unit.getUnitId());

            if ((abilities & UnitTypeFlags.WORKER) != 0) {
                workers++;
            }

            if ((abilities & UnitTypeFlags.CLOAKABLE) != 0) {
                cloakable++;
            }

            // Grounded units with the Zerg group flag contribute to the burrow-set test.
            if ((unit.getStatus() & UnitStatusFlags.FLYING) == 0 && (units.getGroupFlags(unit.getUnitId()) & UnitGroupFlags.ZERG) != 0) {
                zergGround++;
            }

            if (this.view.getUnitQueries().isBurrowable(unit)) {
                burrowable = true;
            }

            if (unitSet == baseSet || unitSet >= ButtonSetTable.SET_COUNT || baseSet >= ButtonSetTable.SET_COUNT ||
                this.getButtons(unitSet) == this.getButtons(baseSet) ||
                this.getConnectedSet(unitSet) == baseSet) {
                continue;
            }

            if (this.getConnectedSet(baseSet) == unitSet) {
                baseSet = unitSet;
                result  = unitSet;
            } else {
                result = GENERIC_SET;
            }
        }

        if (result == ButtonSetTable.NO_SET) {
            return result;
        }

        int count = selection.getCount();

        if (count == workers) {
            return WORKER_SET;
        }

        if (count == cloakable) {
            return CLOAK_SET;
        }

        if (burrowable && count == zergGround) {
            return BURROW_SET;
        }

        return result;
    }

    /**
     * Walks conditions in table order and fills each matching button position.
     * Unresolved conditions mark the slot unresolved; negative conditions select the disabled string.
     *
     * @param selection the local selection
     * @param replay    whether the replay perspective is shown
     */
    private void refreshSlots(@NotNull InterfaceSelection selection, boolean replay) {
        Arrays.fill(this.slotButtons, null);
        Arrays.fill(this.slotStates, HIDDEN);
        Arrays.fill(this.slotDisabledStrings, 0);

        if (this.displayedSet >= ButtonSetTable.SET_COUNT) {
            return;
        }

        Unit active = selection.getActiveUnit();

        if (!replay && active == null) {
            return;
        }

        if (!replay && active.getOwnerIndex() != selection.getPlayer()) {
            return;
        }

        ButtonDefinition[] buttons = this.getButtons(this.displayedSet);
        int index = 0;

        for (int position = 1; position <= SLOT_COUNT; position++) {
            int result = RequirementEvaluator.HIDDEN;
            boolean unresolved = false;

            while (index < buttons.length) {
                try {
                    result = this.conditions.evaluate(buttons[index], selection);
                } catch (UnsupportedOperationException exception) {
                    unresolved = true;
                    result     = RequirementEvaluator.ENABLED;
                }

                if (result != RequirementEvaluator.HIDDEN) {
                    break;
                }

                index++;
            }

            if (index >= buttons.length || position < buttons[index].getPosition()) {
                continue;
            }

            int slot = position - 1;

            this.slotButtons[slot] = buttons[index];

            if (unresolved) {
                this.slotStates[slot] = UNRESOLVED;
            } else if (result < 0) {
                this.slotStates[slot] = DISABLED;

                boolean unavailable = this.conditions.getRequirements().getFailureCode() == RequirementEvaluator.UNAVAILABLE_FAILURE;

                // A disabled text of a changed table takes the place of the disabled string.
                this.slotDisabledStrings[slot] = unavailable
                                                 ? UNAVAILABLE_STRING
                                                 : buttons[index].getDisabledText() != null ? 0 : buttons[index].getDisabledString();
            } else {
                this.slotStates[slot] = ENABLED;
            }

            index++;
        }
    }
}
