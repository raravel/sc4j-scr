package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.scr.client.ui.command.CommandCard;
import org.fogofwarlabs.sc4j.scr.client.ui.command.InterfaceMode;
import org.fogofwarlabs.sc4j.scr.client.ui.command.InterfaceSelection;
import org.fogofwarlabs.sc4j.scr.client.ui.status.UnitStatus;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.client.world.ScrPresentation;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.presentation.LocalSelection;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Retains resources, supply, selection status, command-card state and interface modes for the shown player.
 * Local and pending selections can replace the synchronized selection; every change of the shown selection moves the
 * health bars and selection circles of the game's selection images. Refresh publishes the selected pool indices
 * and updates statuses and the command card in their existing order.
 */
public final class ConsoleModel {
    /**
     * The own local player that disables the own local selection.
     */
    private static final int NO_OWN_LOCAL_PLAYER = -1;

    private final @NotNull ScrGameView view;
    private final @NotNull ScrPresentation presentation;
    private final @NotNull InterfaceSelection selection;
    private final @NotNull CommandCard commandCard;
    private final @NotNull UnitStatus @NotNull [] statuses;
    private final @NotNull InterfaceMode mode;

    private @NotNull CommandCard.Perspective perspective;
    /**
     * The live player whose local selection remains independent of synchronized selection commands; {@code -1} disables this mode.
     */
    private int ownLocalPlayer;

    private boolean localSelection;
    private boolean pendingSelection;
    private int pendingFrame;
    private int player;
    private int race;
    private int supplyUsed;
    private int supplyProvided;

    /**
     * Creates a {@link ConsoleModel} instance reading the given game state.
     *
     * @param view         the game view
     * @param presentation the presentation state of the viewed game that the interface writes
     * @throws NullPointerException if the world was built without game data
     */
    public ConsoleModel(@NotNull ScrGameView view, @NotNull ScrPresentation presentation) {
        this.view         = view;
        this.presentation = presentation;
        this.selection    = new InterfaceSelection(view.requireGameData().getUnitTypes());
        this.commandCard  = new CommandCard(view);
        this.statuses     = new UnitStatus[InterfaceSelection.CAPACITY];
        this.mode         = new InterfaceMode();

        for (int slot = 0; slot < this.statuses.length; slot++) {
            this.statuses[slot] = new UnitStatus();
        }

        this.perspective    = CommandCard.Perspective.PLAYER;
        this.ownLocalPlayer = NO_OWN_LOCAL_PLAYER;

        this.localSelection   = false;
        this.pendingSelection = false;
        this.pendingFrame     = 0;
        this.player           = 0;
        this.race             = 0;
        this.supplyUsed       = 0;
        this.supplyProvided   = 0;
    }

    /**
     * @return the view of the game the model reads
     */
    public @NotNull ScrGameView getView() {
        return this.view;
    }

    /**
     * @return the presentation state of the viewed game that the interface writes
     */
    public @NotNull ScrPresentation getPresentation() {
        return this.presentation;
    }

    /**
     * @return whose command card the last update showed; {@link CommandCard.Perspective#NATIVE_REPLAY} is the replay
     * viewer, where the native replay flag is set
     */
    public @NotNull CommandCard.Perspective getPerspective() {
        return this.perspective;
    }

    /**
     * @return the player whose console is shown
     */
    public int getPlayer() {
        return this.player;
    }

    /**
     * @return the player's race: {@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss
     */
    public int getRace() {
        return this.race;
    }

    /**
     * @return the used supply of the player's race in half-supply units
     */
    public int getSupplyUsed() {
        return this.supplyUsed;
    }

    /**
     * @return the provided supply of the player's race in half-supply units
     */
    public int getSupplyProvided() {
        return this.supplyProvided;
    }

    /**
     * @return the local selection
     */
    public @NotNull InterfaceSelection getSelection() {
        return this.selection;
    }

    /**
     * @param slot the selection slot from {@code 0} through {@code 11}
     * @return the status values of the selected unit in the slot; meaningful when the slot holds a unit
     */
    public @NotNull UnitStatus getStatus(int slot) {
        return this.statuses[slot];
    }

    /**
     * @return the command card
     */
    public @NotNull CommandCard getCommandCard() {
        return this.commandCard;
    }

    /**
     * @return the local interface mode
     */
    public @NotNull InterfaceMode getMode() {
        return this.mode;
    }

    /**
     * @return {@code true} if a local-only selection (the replay viewer's, or another player's units) replaces the player's
     * synchronized selection, {@code false} otherwise, also while an own selection command is pending
     */
    public boolean isLocalSelection() {
        return this.localSelection && !this.pendingSelection;
    }

    /**
     * Reads one slot of the local selection array.
     *
     * @param position the slot from {@code 0} through {@code 11}
     * @return the pool index of the unit in the slot, or {@code 0} for an empty slot
     */
    public int getSelectedPoolIndex(int position) {
        Unit unit = this.selection.getUnit(position);

        return unit == null ? 0 : unit.getIndex();
    }

    /**
     * Sets one local selection slot without issuing a command or playing a sound.
     * The local selection is shown on the next refresh; an unallocated pool index leaves the slot empty.
     *
     * @param position  the slot from {@code 0} through {@code 11}
     * @param poolIndex the pool index to store, or {@code 0}
     */
    public void setSelectedPoolIndexRaw(int position, int poolIndex) {
        UnitPool pool = this.view.getUnitSystem().getUnitPool();
        Unit unit = poolIndex != 0 && pool != null && poolIndex <= pool.getCapacity() && pool.isAllocated(poolIndex) ? pool.getUnit(poolIndex) : null;

        this.selection.setSlot(this.player, position, unit);

        this.localSelection   = true;
        this.pendingSelection = false;
    }

    /**
     * Sets the local player whose own local selection array is shown in live play (copies into the panel): it starts empty and
     * changes only through the local selection input, the lifecycle removal of a unit and direct writes, never through executed
     * selection commands.
     *
     * @param player the local player, or {@code -1} to show the synchronized selection (replays, other players)
     */
    public void setOwnLocalSelection(int player) {
        Unit[] previous = this.copySelection();

        this.ownLocalPlayer = player;

        this.selection.set(Math.max(0, player));
        this.updateSelectionImages(previous);
    }

    /**
     * @return the active unit's status, or {@code null} without a selection
     */
    public @Nullable UnitStatus getActiveStatus() {
        return this.selection.getCount() == 0 ? null : this.statuses[0];
    }

    /**
     * Shows an own selection command immediately and waits for synchronized selection or the queued frame to pass.
     *
     * @param units the units the command selects, at most twelve
     */
    public void showPendingSelection(@NotNull Unit @NotNull ... units) {
        this.selectLocally(this.player, units);

        this.localSelection   = true;
        this.pendingSelection = true;
        this.pendingFrame     = this.view.getCurrentFrame();
    }

    /**
     * Shows a local selection instead of the synchronized one until {@link #followSynchronizedSelection()}: the viewer's
     * own clicks in a replay, or units the local player cannot select by command (another player's units).
     *
     * @param units the units to show, at most twelve
     */
    public void showLocalSelection(@NotNull Unit @NotNull ... units) {
        this.selectLocally(this.player, units);

        this.localSelection   = true;
        this.pendingSelection = false;
    }

    /**
     * Removes a unit from the shown selection, as the lifecycle removal of a retired or hidden unit, or of a selected unit the
     * local player stops seeing, clears it from the local selection array too ({@link LocalSelection#removePoolIndex}): the units
     * before the first empty slot are selected again without it, and the units after that slot keep their slots.
     *
     * @param unit the unit
     */
    public void removeUnit(@NotNull Unit unit) {
        UnitPool pool = this.view.getUnitSystem().getUnitPool();
        int[] slots = new int[InterfaceSelection.CAPACITY];

        for (int slot = 0; slot < slots.length; slot++) {
            slots[slot] = this.getSelectedPoolIndex(slot);
        }

        if (pool == null || !LocalSelection.removePoolIndex(slots, unit.getIndex())) {
            return;
        }

        int count = 0;

        while (count < slots.length && slots[count] != LocalSelection.EMPTY_SLOT) {
            count++;
        }

        Unit[] kept = new Unit[count];

        for (int slot = 0; slot < count; slot++) {
            kept[slot] = pool.getUnit(slots[slot]);
        }

        this.selectLocally(this.selection.getPlayer(), kept);
    }

    /**
     * Returns to the player's synchronized selection.
     */
    public void followSynchronizedSelection() {
        this.localSelection   = false;
        this.pendingSelection = false;
    }

    /**
     * Reads the console values of a player from the game state and its recorded selection.
     *
     * @param player       the player slot from {@code 0} through {@code 7}
     * @param perspective  whose command card is shown
     * @param replayPaused whether the replay is paused
     * @param speedSetting the game speed setting index
     * @param replaySpeed  the replay speed multiplier
     */
    public void update(int player, @NotNull CommandCard.Perspective perspective, boolean replayPaused, int speedSetting, int replaySpeed) {
        if (player != this.player) {
            this.followSynchronizedSelection();
        }

        if (this.pendingSelection && (this.view.getCurrentFrame() - this.pendingFrame > 1 || this.matchesSynchronized(player))) {
            this.followSynchronizedSelection();
        }

        boolean local = this.localSelection || player == this.ownLocalPlayer && perspective == CommandCard.Perspective.PLAYER;

        // A shown local selection array changes only through local selections, raw writes and the lifecycle removal of a unit.
        if (!local && perspective == CommandCard.Perspective.NATIVE_REPLAY) {
            Unit[] previous = this.copySelection();

            // The replay viewer selects on its own: the recorded players' selections are not the viewer's, so the panel
            // stays empty until the viewer clicks (inferred from the viewer never sending select commands).
            this.selection.set(player);
            this.updateSelectionImages(previous);
        } else if (!local) {
            Unit[] previous = this.copySelection();

            this.selection.update(this.view, player);
            this.updateSelectionImages(previous);
        }

        this.refresh(player, perspective, replayPaused, speedSetting, replaySpeed);
    }

    /**
     * Reads the console values for the current selection, which the caller has already set.
     *
     * @param player       the player slot from {@code 0} through {@code 7}
     * @param perspective  whose command card is shown
     * @param replayPaused whether the replay is paused
     * @param speedSetting the game speed setting index
     * @param replaySpeed  the replay speed multiplier
     */
    public void refresh(int player, @NotNull CommandCard.Perspective perspective, boolean replayPaused, int speedSetting, int replaySpeed) {
        this.publishInterfaceSelection();

        // Every interface refresh clears the local portrait-refresh flag.
        this.presentation.getInterfaceLink().getLocalInterface().setPortraitRefresh(0);

        this.perspective = perspective;
        this.player      = player;
        this.view.setLocalPlayerIndex(player);

        Player record = player >= 0 && player < PlayerTable.PLAYER_SLOTS ? this.view.getPlayerTable().getPlayer(player) : null;

        this.race = record == null ? ScrRaceIds.TERRAN : record.getRace();

        UnitAccounting accounting = this.view.getPlayerTable().getUnitAccounting();
        int raceOffset = this.race == ScrRaceIds.ZERG
                         ? 0
                         : this.race == ScrRaceIds.PROTOSS ? ScrRaceIds.PROTOSS * PlayerTable.PLAYER_SLOTS : PlayerTable.PLAYER_SLOTS;

        if (accounting != null && player >= 0 && player < PlayerTable.PLAYER_SLOTS) {
            this.supplyUsed     = accounting.getSupplyUsed(raceOffset + player);
            this.supplyProvided = accounting.getSupplyProvided(raceOffset + player);
        } else {
            this.supplyUsed     = 0;
            this.supplyProvided = 0;
        }

        for (int slot = 0; slot < InterfaceSelection.CAPACITY; slot++) {
            Unit unit = this.selection.getUnit(slot);

            if (unit != null) {
                this.statuses[slot].read(this.view, unit);
            }
        }

        this.commandCard.update(this.selection,
                                perspective,
                                replayPaused, speedSetting, replaySpeed,
                                this.mode.getPlacementType() >= 0,
                                this.mode.isTargeting());
    }

    /**
     * Copies the slots of the shown selection.
     *
     * @return the selected units by slot, {@code null} for an empty slot
     */
    private @Nullable Unit @NotNull [] copySelection() {
        Unit[] units = new Unit[InterfaceSelection.CAPACITY];

        for (int slot = 0; slot < units.length; slot++) {
            units[slot] = this.selection.getUnit(slot);
        }

        return units;
    }

    /**
     * Selects units in the local selection array as a selection the player makes ({@link InterfaceSelection#select}); the health bars and
     * selection circles always move from the units before the first empty slot to the selected units, even when the units are the same.
     *
     * @param player the local player
     * @param units  the selected units
     */
    private void selectLocally(int player, @NotNull Unit @NotNull [] units) {
        Unit[] previous = this.copySelection();

        this.selection.select(player, units);
        this.presentation.getSelectionImages().changeLocalSelection(previous, units);
    }

    /**
     * Moves the health bars and selection circles from the previously shown units to the shown ones when a change of the shown selection that
     * the player did not make changed its units. Raw writes to single slots leave them.
     *
     * @param previous the slots before the change
     */
    private void updateSelectionImages(@Nullable Unit @NotNull [] previous) {
        Unit[] current = this.copySelection();

        if (Arrays.equals(previous, current)) {
            return;
        }

        Unit[] selected = Arrays.copyOf(current, this.selection.getCount());

        this.presentation.getSelectionImages().changeLocalSelection(previous, selected);
    }

    /**
     * Publishes selected pool indices to the local interface state, using zero for an empty slot.
     */
    private void publishInterfaceSelection() {
        int[] indices = new int[InterfaceSelection.CAPACITY];

        for (int slot = 0; slot < indices.length; slot++) {
            Unit unit = this.selection.getUnit(slot);

            indices[slot] = unit == null ? 0 : unit.getIndex();
        }

        this.presentation.getInterfaceLink().getLocalInterface().setInterfaceSelection(indices);
    }

    private boolean matchesSynchronized(int player) {
        UnitSelection synchronizedSelection = this.view.getSelection();

        if (synchronizedSelection == null || player < 0 || player >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return false;
        }

        for (int position = 0; position < InterfaceSelection.CAPACITY; position++) {
            Unit unit = synchronizedSelection.getSelectedUnit(player, position);

            if (unit != (position < this.selection.getCount() ? this.selection.getUnit(position) : null)) {
                return false;
            }

            if (unit == null) {
                return true;
            }
        }

        return true;
    }
}
