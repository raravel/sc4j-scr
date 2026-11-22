package org.fogofwarlabs.sc4j.scr.client.ui.input;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.command.CommandAction;
import org.fogofwarlabs.sc4j.engine.client.command.CommandTarget;
import org.fogofwarlabs.sc4j.engine.client.command.CommandTranslator;
import org.fogofwarlabs.sc4j.engine.client.command.SelectionFilter;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.KeyBindingTable;
import org.fogofwarlabs.sc4j.engine.client.input.KeyModifier;
import org.fogofwarlabs.sc4j.engine.client.input.PointerButton;
import org.fogofwarlabs.sc4j.engine.client.world.ViewJumps;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.scr.client.ui.command.*;
import org.fogofwarlabs.sc4j.scr.client.ui.console.ConsoleModel;
import org.fogofwarlabs.sc4j.scr.client.ui.console.UiClockUtils;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.client.world.WorldOverlayState;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.replay.*;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerRelations;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.CommandErrorEvents;
import org.fogofwarlabs.sc4j.scr.game.presentation.LocalSelection;
import org.fogofwarlabs.sc4j.scr.game.production.PlacementResult;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffect;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Handles pointer and hotkey interaction with the map view and exposes the selection, drag, cursor and click-marker state. Selection and order
 * commands are encoded through {@link CommandInput}. A selected foreign unit is shown as a local selection and selected by command too;
 * replay-viewer selections remain local. Selection filters retain the
 * existing click, box, type and Shift-merge rules. Click feedback can directly set a retained sprite's flash timer; selection and order changes are
 * submitted as commands.
 * <p>
 * The map presses go through the engine {@link CommandTranslator} in the native routing order: its selection is the interface selection
 * ({@link InterfaceSelection}), its picker the SC:R pixel picker ({@link UnitPicker}, events in map pixels), its targeting mode the interface
 * mode's ({@link InterfaceMode#getTargeting}); a waiting targeting or placement mode is issued at a left press through the native targeting
 * and placement clicks ({@link InterfaceMode.TargetHandler}), a right press cancels it, and without a mode a right press issues the native
 * right click as the translator's default action. A left press without a mode starts the selection box.
 */
public final class WorldInterface implements WorldOverlayState, LocalSelection {
    /**
     * The rectangle size in map pixels up to which a drag is a click, in both directions.
     */
    public static final int CLICK_DRAG_LIMIT = 4;

    /**
     * The game-frame lifetime used for a ground click marker.
     */
    public static final int MARKER_LIFETIME = 5;

    /**
     * The frame count used for a unit or remembered-building flash.
     */
    public static final int FLASH_TIMER = 0x1f;

    /**
     * The half-size in map pixels of the Select Larva spatial query.
     */
    private static final int LARVA_SEARCH_RADIUS = 0x80;

    private static final int CONTROL_GROUP_ASSIGN = 0;
    private static final int CONTROL_GROUP_RECALL = 1;
    private static final int CONTROL_GROUP_ADD = 2;
    private static final int CONTROL_GROUP_COUNT = 10;
    private static final int NO_GROUP = -1;
    private static final int FIRST_HISTORY_GROUP = 10;
    private static final int HISTORY_RECORDS = 8;

    /**
     * The time within which a second recall of the same group centers the camera.
     */
    private static final long DOUBLE_RECALL_MILLISECONDS = 500;

    private static final int MARKER_LIMIT = 8;
    private static final int LANDING_ERROR_STRING = 863;
    private static final int NO_BUILDER_OWNER = 0xFF;
    private static final int DEFAULT_VIEW_WIDTH = 640;
    private static final int DEFAULT_VIEW_HEIGHT = 480;

    /**
     * Selects the installed map-view cursor assets for hover, targeting, dragging and edge scrolling.
     */
    public enum Cursor {
        /**
         * {@code arrow.grp}.
         */
        ARROW,
        /**
         * {@code MagG.grp}: over an own or allied unit (and over any player's unit for an observer or in a replay).
         */
        MAGNIFIER_OWN,
        /**
         * {@code MagY.grp}: over a neutral unit (owner {@code 11}).
         */
        MAGNIFIER_NEUTRAL,
        /**
         * {@code MagR.grp}: over an enemy unit.
         */
        MAGNIFIER_ENEMY,
        /**
         * {@code TargN.grp}: targeting without a unit.
         */
        TARGET_NONE,
        /**
         * {@code TargG.grp}: targeting an own or allied unit.
         */
        TARGET_OWN,
        /**
         * {@code TargY.grp}: targeting a neutral unit, and the minimap's target cursor ({@link #getMinimapCursor}).
         */
        TARGET_NEUTRAL,
        /**
         * {@code TargR.grp}: targeting an enemy unit.
         */
        TARGET_ENEMY,
        /**
         * {@code Drag.grp}: drawing a selection box.
         */
        DRAG,
        /**
         * {@code ScrollU.grp} and the other edge cursors, in {@link #getScrollDirection()} order.
         */
        SCROLL
    }

    /**
     * Classifies a click marker by its target.
     */
    public enum MarkerKind {
        /**
         * A ground position.
         */
        GROUND,
        /**
         * An own unit.
         */
        OWN,
        /**
         * An allied or neutral unit.
         */
        ALLY,
        /**
         * An enemy unit.
         */
        ENEMY
    }

    /**
     * Retains one ground marker or unit or remembered-building flash at the point where an order was issued.
     */
    public static final class ClickMarker {
        private final int mapX;
        private final int mapY;
        private final @Nullable Unit target;
        private final @Nullable SpriteEffect remembered;
        private final @NotNull MarkerKind kind;
        private final int order;
        private final int frame;

        private ClickMarker(int mapX, int mapY,
                            @Nullable Unit target, @Nullable SpriteEffect remembered, @NotNull MarkerKind kind,
                            int order, int frame) {
            this.mapX       = mapX;
            this.mapY       = mapY;
            this.target     = target;
            this.remembered = remembered;
            this.kind       = kind;
            this.order      = order;
            this.frame      = frame;
        }

        /**
         * @return the map x coordinate
         */
        public int getX() {
            return this.mapX;
        }

        /**
         * @return the map y coordinate
         */
        public int getY() {
            return this.mapY;
        }

        /**
         * @return the flashed target unit, or {@code null} for a ground target or a flashed remembered building
         */
        public @Nullable Unit getTarget() {
            return this.target;
        }

        /**
         * @return the fog sprite of the flashed remembered building while that building is still remembered, or {@code null}
         */
        public @Nullable Sprite getRememberedSprite() {
            return this.remembered != null ? this.remembered.getSpriteOrNull() : null;
        }

        /**
         * @return {@code true} if the marker flashes a unit or a remembered building, {@code false} otherwise
         */
        public boolean isFlash() {
            return this.target != null || this.remembered != null;
        }

        /**
         * @return the target classification
         */
        public @NotNull MarkerKind getKind() {
            return this.kind;
        }

        /**
         * @return the {@code orders.dat} identifier of a targeted order, or {@code -1} for a right click
         */
        public int getOrder() {
            return this.order;
        }

        /**
         * @return the game frame of the click
         */
        public int getFrame() {
            return this.frame;
        }
    }

    /**
     * Receives the interface actions that make units respond.
     */
    public interface Responses {
        /**
         * Responds to a new selection made by a click, a box or a type selection (with sound).
         *
         * @param selection the resulting selection in order
         */
        void respondToSelection(@NotNull List<Unit> selection);

        /**
         * Responds to an order given to the active unit: a right click, a targeted order or Return Cargo.
         *
         * @param active the active unit
         */
        void respondToOrderAcknowledgement(@NotNull Unit active);
    }

    /**
     * The native map clicks the engine translator issues: the waiting targeting and placement modes at a left press and the right click as the
     * default action. Each records the effect of its press for the caller.
     */
    private final class MapClicks implements InterfaceMode.TargetHandler, CommandAction {
        /**
         * @return {@code true}: the right click is issued at the pointed target
         */
        @Override
        public boolean isTargeted() {
            return true;
        }

        /**
         * Sends a right click ({@code 0x60}) at the pressed point and records its marker; a local selection sends nothing.
         *
         * @param translator the map input's translator
         * @param target     the pressed point and the unit picked there
         * @param queued     whether Shift is held
         * @return {@code true} if the right click was sent, {@code false} for a local selection
         */
        @Override
        public boolean issue(@NotNull CommandTranslator translator, @Nullable CommandTarget target, boolean queued) {
            if (target == null || WorldInterface.this.console.isLocalSelection()) {
                return false;
            }

            WorldInterface.this.pressResult = WorldInterface.this.rightClick(target, queued);

            return true;
        }

        /**
         * Issues the targeting mode's order at a pressed point.
         *
         * @param target the pressed map point and the unit picked there
         * @param queued whether Shift is held
         * @return {@code true} if the order was sent and the mode ended, {@code false} if the mode stays (for example a refused target)
         */
        @Override
        public boolean clickTarget(@NotNull CommandTarget target, boolean queued) {
            CommandInput.Result result = WorldInterface.this.clickTarget(target, queued);

            WorldInterface.this.pressResult = result;

            return result == CommandInput.Result.COMMAND;
        }

        /**
         * Places the building of the placement mode at a pressed point.
         *
         * @param target the pressed map point
         * @return {@code true} if the placement was sent and the mode ended, {@code false} if the mode stays (for example an invalid spot)
         */
        @Override
        public boolean clickPlacement(@NotNull CommandTarget target) {
            CommandInput.Result result = WorldInterface.this.clickPlacement(target);

            WorldInterface.this.pressResult = result;

            return result == CommandInput.Result.COMMAND;
        }
    }

    /**
     * Returns the player of the engine translator: the local player, else the first player (a replay viewer or an observer submits no engine
     * command; the native clicks submit for the console's player).
     *
     * @param view the game view
     * @return the player
     */
    private static @NotNull Player findPlayer(@NotNull ScrGameView view) {
        Player local = view.getLocalPlayer();

        return local != null ? local : view.getPlayers().getFirst();
    }

    /**
     * Returns the stored handle of a control group ({@code 0..9}) or selection history record ({@code 10..17}).
     *
     * @param selections the selection storage of the game
     * @param player     the player
     * @param group      the control group or selection history group
     * @param position   the position in the group
     * @return the stored unit handle, or {@code 0} for an empty position
     */
    private static int getStoredHandle(@NotNull UnitSelection selections, int player, int group, int position) {
        if (group >= FIRST_HISTORY_GROUP) {
            return selections.getHistoryHandle(player, group - FIRST_HISTORY_GROUP, position);
        }

        return selections.getControlGroupHandle(player, group, position);
    }

    private final @NotNull ConsoleModel console;
    private final @NotNull CommandInput.CommandSink sink;
    private final boolean executesCommands;
    private final @NotNull CommandInput input;
    private final @NotNull UnitPicker picker;
    private final @NotNull CommandTranslator translator;
    private @NotNull CommandInput.Result pressResult;
    private final @NotNull List<Unit> candidates;
    private final @NotNull PlacementPreview placement;
    private @Nullable Responses responses;
    private @Nullable BooleanSupplier altKey;
    private @Nullable ViewJumps viewJumps;

    private final @NotNull List<ClickMarker> markers;

    private final Unit @NotNull [] @Nullable [] localGroups;
    private int lastRecalledGroup;
    private long lastRecallTime;

    private @Nullable Unit hoveredUnit;
    private @NotNull Cursor cursor;
    private int scrollDirection;
    private float cursorScreenX;

    private boolean dragging;
    private int dragStartX;
    private int dragStartY;
    private int dragEndX;
    private int dragEndY;

    private int cameraX;
    private int cameraY;
    private int viewWidth;
    private int viewHeight;

    private int messageIdentifier;
    private long messageTime;

    /**
     * Creates a {@link WorldInterface} instance for a console.
     *
     * @param console          the console whose selection, mode and command card are used
     * @param sink             the receiver of encoded commands
     * @param executesCommands whether submitted commands are executed; a replay viewer shows clicked units as a local selection instead of
     *                         sending selection commands
     */
    public WorldInterface(@NotNull ConsoleModel console, @NotNull CommandInput.CommandSink sink, boolean executesCommands) {
        this.console          = console;
        this.sink             = sink;
        this.executesCommands = executesCommands;
        this.input            = new CommandInput(console, sink);
        this.picker           = new UnitPicker(console.getView());
        this.translator       = new CommandTranslator(findPlayer(console.getView()),
                                                      new EncodedCommandSink(sink),
                                                      console.getSelection(),
                                                      new KeyBindingTable<>(),
                                                      this.picker,
                                                      console.getMode().getTargeting());
        this.pressResult      = CommandInput.Result.IGNORED;
        this.candidates       = new ArrayList<>();
        this.placement        = new PlacementPreview();
        this.responses        = null;
        this.altKey           = null;
        this.viewJumps        = null;

        this.markers = new ArrayList<>();

        this.localGroups       = new Unit[CONTROL_GROUP_COUNT][];
        this.lastRecalledGroup = NO_GROUP;
        this.lastRecallTime    = 0;

        this.hoveredUnit     = null;
        this.cursor          = Cursor.ARROW;
        this.scrollDirection = -1;
        this.cursorScreenX   = 0;

        this.dragging   = false;
        this.dragStartX = 0;
        this.dragStartY = 0;
        this.dragEndX   = 0;
        this.dragEndY   = 0;

        this.cameraX    = 0;
        this.cameraY    = 0;
        this.viewWidth  = DEFAULT_VIEW_WIDTH;
        this.viewHeight = DEFAULT_VIEW_HEIGHT;

        this.messageIdentifier = 0;
        this.messageTime       = 0;

        this.input.setInterfaceActions(this::selectLarva);

        MapClicks clicks = new MapClicks();

        this.translator.setDefaultAction(clicks);
        console.getMode().setTargetHandler(clicks);
    }

    /**
     * @return the command input of the command card
     */
    public @NotNull CommandInput getCommandInput() {
        return this.input;
    }

    /**
     * @return the viewing player, whose color draws the ground click markers and the placement preview
     */
    @Override
    public int getViewingPlayer() {
        return this.console.getPlayer();
    }

    /**
     * @return the number of selected units, at most {@code 12}
     */
    @Override
    public int getSelectionCount() {
        return this.console.getSelection().getCount();
    }

    /**
     * @param slot the selection slot below {@link #getSelectionCount()}
     * @return the selected unit
     * @throws IndexOutOfBoundsException if the slot is outside the current selection count
     * @throws NullPointerException      if the selected slot is empty
     */
    @Override
    public @NotNull Unit getSelectedUnit(int slot) {
        Unit unit = this.console.getSelection().getUnit(Objects.checkIndex(slot, this.getSelectionCount()));

        if (unit == null) {
            throw Exceptions.newNullPointerException();
        }

        return unit;
    }

    /**
     * @return the screen x coordinate of the cursor
     */
    public float getCursorScreenX() {
        return this.cursorScreenX;
    }

    /**
     * @return the target unit of the newest unit-targeting click, or {@code null}
     */
    @Override
    public @Nullable Unit getFlashedUnit() {
        for (int index = this.markers.size() - 1; index >= 0; index--) {
            Unit target = this.markers.get(index).getTarget();

            if (target != null) {
                return target;
            }
        }

        return null;
    }

    /**
     * @return the building type being placed, or {@code -1} outside placement
     */
    public int getPlacementType() {
        return this.console.getMode().getPlacementType();
    }

    /**
     * Sets the game view used by placement snapping: the camera map position and the view size in map pixels.
     *
     * @param cameraX    the camera map x in pixels
     * @param cameraY    the camera map y in pixels
     * @param viewWidth  the view width in map pixels
     * @param viewHeight the view height in map pixels
     */
    public void setView(int cameraX, int cameraY, int viewWidth, int viewHeight) {
        this.cameraX    = cameraX;
        this.cameraY    = cameraY;
        this.viewWidth  = viewWidth;
        this.viewHeight = viewHeight;
    }

    /**
     * Sets the receiver of unit responses.
     *
     * @param responses the receiver, or {@code null}
     */
    public void setResponses(@Nullable Responses responses) {
        this.responses = responses;

        this.input.setAcknowledgement(responses == null ? null : () -> {
            Unit active = this.console.getSelection().getActiveUnit();

            if (active != null) {
                responses.respondToOrderAcknowledgement(active);
            }
        });
    }

    /**
     * Sets the camera jumps used for group centering.
     *
     * @param viewJumps the camera jumps, or {@code null}
     */
    public void setViewJumps(@Nullable ViewJumps viewJumps) {
        this.viewJumps = viewJumps;
    }

    /**
     * Sets the test of the Alt key the selection actions read when they run.
     *
     * @param altKey whether Alt is held, or {@code null} for never
     */
    public void setAltKey(@Nullable BooleanSupplier altKey) {
        this.altKey = altKey;
    }

    /**
     * @return the building placement preview, never {@code null}; {@link PlacementPreview#isVisible()} tells whether it is drawn
     */
    @Override
    public @NotNull PlacementPreview getPlacementPreview() {
        return this.placement;
    }

    /**
     * Returns the current interface message while its supplied duration has not elapsed.
     *
     * @param now      the current time in milliseconds
     * @param duration how long a message stays, in milliseconds
     * @return the message id, or {@code 0} when none is shown
     */
    public int getMessageId(long now, long duration) {
        return this.messageIdentifier != 0 && now - this.messageTime < duration ? this.messageIdentifier : 0;
    }

    /**
     * @return the unit under the cursor, or {@code null}
     */
    @Override
    public @Nullable Unit getHoveredUnit() {
        return this.hoveredUnit;
    }

    /**
     * @return {@code true} if a drag selection box is open, {@code false} otherwise
     */
    @Override
    public boolean isDragSelecting() {
        return this.dragging;
    }

    /**
     * @return the drag box left edge in map pixels
     */
    @Override
    public int getDragLeft() {
        return Math.min(this.dragStartX, this.dragEndX);
    }

    /**
     * @return the drag box top edge in map pixels
     */
    @Override
    public int getDragTop() {
        return Math.min(this.dragStartY, this.dragEndY);
    }

    /**
     * @return the drag box right edge in map pixels
     */
    @Override
    public int getDragRight() {
        return Math.max(this.dragStartX, this.dragEndX);
    }

    /**
     * @return the drag box bottom edge in map pixels
     */
    @Override
    public int getDragBottom() {
        return Math.max(this.dragStartY, this.dragEndY);
    }

    /**
     * @return the cursor over the map view
     */
    public @NotNull Cursor getCursor() {
        return this.cursor;
    }

    /**
     * @return the edge scroll direction for {@link Cursor#SCROLL}: {@code 0} up, then clockwise to {@code 7} up-left; {@code -1} otherwise
     */
    public int getScrollDirection() {
        return this.scrollDirection;
    }

    /**
     * @return the number of live click markers, oldest first
     */
    @Override
    public int getMarkerCount() {
        return this.markers.size();
    }

    /**
     * @param index the marker index
     * @return the click marker
     * @throws IndexOutOfBoundsException if the index is outside the current marker count
     */
    public @NotNull ClickMarker getMarker(int index) {
        return this.markers.get(Objects.checkIndex(index, this.markers.size()));
    }

    /**
     * @param index the marker index
     * @return the marker map x coordinate
     */
    @Override
    public int getMarkerX(int index) {
        return this.getMarker(index).getX();
    }

    /**
     * @param index the marker index
     * @return the marker map y coordinate
     */
    @Override
    public int getMarkerY(int index) {
        return this.getMarker(index).getY();
    }

    /**
     * @param index the marker index
     * @return the flashed target unit, or {@code null} for a ground click or a flashed remembered building
     */
    @Override
    public @Nullable Unit getMarkerTarget(int index) {
        return this.getMarker(index).getTarget();
    }

    /**
     * @param index the marker index
     * @return the fog sprite of a flashed remembered building, or {@code null}
     */
    @Override
    public @Nullable Sprite getMarkerRememberedSprite(int index) {
        return this.getMarker(index).getRememberedSprite();
    }

    /**
     * @param index the marker index
     * @return {@code 0} ground, {@code 1} own, {@code 2} allied or neutral, {@code 3} enemy
     */
    @Override
    public int getMarkerRelation(int index) {
        return this.getMarker(index).getKind().ordinal();
    }

    /**
     * @param index the marker index
     * @return the {@code orders.dat} identifier of a targeted order, or {@code -1} for a right click
     */
    @Override
    public int getMarkerOrder(int index) {
        return this.getMarker(index).getOrder();
    }

    /**
     * @param index the marker index
     * @return the game frame of the click
     */
    @Override
    public int getMarkerFrame(int index) {
        return this.getMarker(index).getFrame();
    }

    /**
     * @param position the slot from {@code 0} through {@code 11}
     * @return the pool index of the unit in the slot, or {@code 0} for an empty slot
     */
    @Override
    public int getSelectedPoolIndex(int position) {
        return this.console.getSelectedPoolIndex(position);
    }

    /**
     * Sets one local selection slot directly without a command or sound; the console shows it on the next refresh.
     *
     * @param position  the slot from {@code 0} through {@code 11}
     * @param poolIndex the pool index of the unit to store, or {@code 0} for an empty slot
     */
    @Override
    public void setSelectedPoolIndexRaw(int position, int poolIndex) {
        this.console.setSelectedPoolIndexRaw(position, poolIndex);
    }

    /**
     * Shows units that a game change selected for the local player as the local selection, as an own selection shows them before its command
     * runs ({@link ConsoleModel#showPendingSelection}): the health bars and selection circles move to them, nothing is sent and no sound plays.
     *
     * @param units the selected units in slot order, at most {@link LocalSelection#CAPACITY}
     */
    @Override
    public void showSelection(@NotNull Unit @NotNull [] units) {
        this.console.showPendingSelection(units);
    }

    /**
     * Returns the neutral target cursor while targeting is active, otherwise the arrow.
     *
     * @return the cursor over the minimap
     */
    public @NotNull Cursor getMinimapCursor() {
        return this.console.getMode().isTargeting() ? Cursor.TARGET_NEUTRAL : Cursor.ARROW;
    }


    private @NotNull List<Unit> getGroupUnits(int group, boolean living) {
        ScrGameView view = this.console.getView();
        UnitSelection selections = view.getSelection();
        UnitPool pool = view.getUnitSystem().getUnitPool();
        List<Unit> units = new ArrayList<>();
        int player = this.console.getPlayer();

        if (selections == null || pool == null || player < 0 || player >= PlayerTable.PLAYER_SLOTS) {
            return units;
        }

        int stored = 0;

        while (stored < UnitSelection.MAXIMUM_SELECTED && getStoredHandle(selections, player, group, stored) != 0) {
            stored++;
        }

        ScrUnitTypeView unitTypes = view.requireGameData().getUnitTypes();

        for (int position = 0; position < stored; position++) {
            Unit unit = pool.resolveHandle(getStoredHandle(selections, player, group, position));
            Sprite sprite = unit != null ? unit.getSpriteOrNull() : null;

            if (unit == null || sprite == null || (sprite.getFlags() & Sprite.FLAG_HIDDEN) != 0) {
                continue;
            }

            if (unit.getOwnerIndex() != player || (stored != 1 && !UnitSelection.canSelectMultiple(unitTypes, unit))) {
                continue;
            }

            if (living && unit.getPrimaryOrder() == ScrOrderIds.DIE) {
                continue;
            }

            units.add(unit);
        }

        return units;
    }

    /**
     * Lists the living units of a local control group.
     *
     * @param units the units of the group
     * @return the living units in group order
     */
    private @NotNull List<Unit> getLiveUnits(@NotNull Unit @NotNull [] units) {
        UnitPool pool = this.console.getView().getUnitSystem().getUnitPool();
        List<Unit> live = new ArrayList<>();

        for (Unit unit : units) {
            if (pool != null && unit.getSpriteOrNull() != null && !unit.isDestroyed() && pool.isAllocated(unit.getIndex()) &&
                pool.getUnit(unit.getIndex()) == unit) {
                live.add(unit);
            }
        }

        return live;
    }

    private @NotNull List<Unit> getSelectedUnits() {
        InterfaceSelection selection = this.console.getSelection();
        List<Unit> units = new ArrayList<>();

        for (int slot = 0; slot < selection.getCount(); slot++) {
            Unit unit = selection.getUnit(slot);

            if (unit != null) {
                units.add(unit);
            }
        }

        return units;
    }

    private void acknowledge() {
        Unit active = this.console.getSelection().getActiveUnit();

        if (this.responses != null && active != null) {
            this.responses.respondToOrderAcknowledgement(active);
        }
    }

    /**
     * Cancels targeting and building placement (the Esc handler).
     */
    public void cancelMode() {
        this.console.getMode().clear();
        this.placement.clear();
    }

    /**
     * Shows an interface message.
     *
     * @param statTextIdentifier the one-based {@code stat_txt} id
     * @param now                the current time in milliseconds
     */
    public void showMessage(int statTextIdentifier, long now) {
        this.messageIdentifier = statTextIdentifier;
        this.messageTime       = now;

        // The status line is written at once, every time.
        this.console.getPresentation().getInterfaceLink().getMessageLines().writeStatus(null, statTextIdentifier);
    }

    private void updatePlacement(int mapX, int mapY) {
        int type = this.console.getMode().getPlacementType();

        if (type < 0) {
            this.placement.clear();

            return;
        }

        Unit builder = this.console.getSelection().getActiveUnit();
        int owner = builder != null ? builder.getOwnerIndex() : NO_BUILDER_OWNER;

        this.placement.update(this.console.getView(),
                              builder,
                              owner,
                              type,
                              mapX - this.cameraX, mapY - this.cameraY,
                              this.cameraX, this.cameraY,
                              this.viewWidth, this.viewHeight);
    }

    /**
     * Updates the hovered unit and the cursor for a pointer over the map view.
     *
     * @param mapX            the map x coordinate under the pointer
     * @param mapY            the map y coordinate under the pointer
     * @param screenX         the screen x coordinate of the pointer
     * @param scrollDirection the edge scroll direction, or {@code -1} away from the view edges
     * @throws IOException if a unit image cannot be read
     */
    public void handlePointerMove(int mapX, int mapY, float screenX, int scrollDirection) throws IOException {
        this.cursorScreenX = screenX;
        this.hoveredUnit   = this.picker.pickUnit(mapX, mapY);

        this.updatePlacement(mapX, mapY);

        if (this.dragging) {
            this.dragEndX = mapX;
            this.dragEndY = mapY;
        }

        this.scrollDirection = this.dragging ? -1 : scrollDirection;
        this.cursor          = this.computeCursor();
    }

    /**
     * Handles a left button press over the map view: completes a targeting mode, or starts a selection box.
     *
     * @param mapX   the map x coordinate
     * @param mapY   the map y coordinate
     * @param queued whether Shift is held
     * @return the effect of the press
     * @throws IOException if a unit image cannot be read
     */
    public @NotNull CommandInput.Result handleLeftPress(int mapX, int mapY, boolean queued) throws IOException {
        // A waiting targeting or placement mode is issued at the press (MapClicks).
        if (this.dispatchPress(PointerButton.LEFT, mapX, mapY, queued)) {
            return this.pressResult;
        }

        this.dragging   = true;
        this.dragStartX = mapX;
        this.dragStartY = mapY;
        this.dragEndX   = mapX;
        this.dragEndY   = mapY;
        this.cursor     = Cursor.DRAG;

        return CommandInput.Result.INTERFACE;
    }

    /**
     * Hands a map press to the engine translator as an event in map pixels.
     *
     * @return {@code true} if the translator took the press, {@code false} otherwise
     * @throws IOException if a unit image cannot be read
     */
    private boolean dispatchPress(@NotNull PointerButton button, int mapX, int mapY, boolean queued) throws IOException {
        this.pressResult = CommandInput.Result.IGNORED;

        try {
            return this.translator.handleInput(InputEvent.createButtonPressed(button, queued ? KeyModifier.SHIFT.getMask() : 0, mapX, mapY, false));
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    /**
     * Places the building of the placement mode at a pressed point: re-evaluated at the click, an invalid spot keeps the mode and shows the
     * code's stat_txt message.
     *
     * @param target the pressed map point
     * @return the effect of the click
     */
    private @NotNull CommandInput.Result clickPlacement(@NotNull CommandTarget target) {
        this.updatePlacement(target.getX(), target.getY());

        if (!this.placement.isVisible()) {
            return CommandInput.Result.IGNORED;
        }

        if (this.placement.getCode() != PlacementResult.VALID) {
            boolean land = this.console.getMode().getPlacementAction() == ButtonAction.LAND;
            int placementMessageIdentifier = land ? LANDING_ERROR_STRING : PlacementResult.getMessageId(this.placement.getCode());

            // Show the placement message through the command input, falling back to the status line.
            if (!this.input.showPlacementError(placementMessageIdentifier, land)) {
                this.showMessage(placementMessageIdentifier, UiClockUtils.getHostTimeMilliseconds());
            }

            return CommandInput.Result.INTERFACE;
        }

        CommandInput.Result result = this.input.clickPlacement(this.placement.getTileX(), this.placement.getTileY());

        if (result == CommandInput.Result.COMMAND) {
            this.placement.clear();
        }

        return result;
    }

    /**
     * Sends the targeting mode's order at a pressed point: the marker or flash first, then with the remembered building's type; an error that
     * blocks the order keeps the targeting mode, otherwise the order is sent, acknowledged when there was no error.
     *
     * @param target the pressed map point and the unit picked there
     * @param queued whether Shift is held
     * @return the effect of the click
     */
    private @NotNull CommandInput.Result clickTarget(@NotNull CommandTarget target, boolean queued) {
        InterfaceMode mode = this.console.getMode();
        Unit unit = (Unit) target.getUnit();
        int mapX = target.getX();
        int mapY = target.getY();
        int order = unit != null ? mode.getUnitTargetOrder() : mode.getGroundTargetOrder();
        SpriteEffect remembered = this.markClick(mapX, mapY, unit, order);
        int fogType = remembered != null ? UnitPicker.getRememberedBuildingType(remembered) : CommandInput.NO_UNIT_TYPE;
        TargetValidation.Click click = this.input.checkTargetClick(unit, fogType, mapX, mapY);

        if (!click.isSent()) {
            return CommandInput.Result.INTERFACE;
        }

        CommandInput.Result result = this.input.clickTarget(mapX, mapY, unit, fogType, queued);

        if (result == CommandInput.Result.COMMAND && click.getError() == CommandErrorEvents.NO_ERROR_TEXT) {
            this.acknowledge();
        }

        return result;
    }

    /**
     * Handles a left button release: a rectangle of at most {@link #CLICK_DRAG_LIMIT} map pixels in both directions is a click at its top-left
     * corner, a larger one a box selection. A box whose top is below the map is ignored; its bottom is clamped to the map.
     *
     * @param mapX        the map x coordinate
     * @param mapY        the map y coordinate
     * @param shift       whether Shift is held
     * @param control     whether Ctrl is held
     * @param alt         whether Alt is held: a single selected unit recalls its newest selection history record
     * @param doubleClick whether this completes a double click
     * @param viewLeft    the left map coordinate of the view, for type selection
     * @param viewTop     the top map coordinate of the view
     * @param viewRight   the right map coordinate of the view
     * @param viewBottom  the bottom map coordinate of the view
     * @return the effect of the release
     * @throws IOException if a unit image cannot be read
     */
    public @NotNull CommandInput.Result handleLeftRelease(int mapX, int mapY,
                                                          boolean shift, boolean control, boolean alt,
                                                          boolean doubleClick,
                                                          int viewLeft, int viewTop, int viewRight, int viewBottom) throws IOException {
        if (!this.dragging) {
            return CommandInput.Result.IGNORED;
        }

        this.dragging = false;
        this.dragEndX = mapX;
        this.dragEndY = mapY;

        int left = this.getDragLeft();
        int top = this.getDragTop();
        int right = this.getDragRight();
        int bottom = this.getDragBottom();
        int mapHeight = this.console.getView().getMap().getHeight() * TerrainTileset.TILE_SIZE;

        if (top >= mapHeight) {
            return CommandInput.Result.IGNORED;
        }

        bottom = Math.min(bottom, mapHeight - 1);

        if (right - left <= CLICK_DRAG_LIMIT && bottom - top <= CLICK_DRAG_LIMIT) {
            return this.clickSelect(left, top, shift, control, alt, doubleClick, viewLeft, viewTop, viewRight, viewBottom);
        }

        this.picker.collect(left, top, right, bottom, this.candidates);

        List<Unit> chosen = this.createSelectionFilter().filterBox(this.candidates);

        if (chosen.isEmpty()) {
            return CommandInput.Result.IGNORED;
        }

        if (shift && this.console.getSelection().getCount() != 0) {
            return this.selectMerged(chosen, alt);
        }

        if (alt && chosen.size() == 1 && this.recallHistory(chosen.getFirst())) {
            return CommandInput.Result.COMMAND;
        }

        return this.selectReplacing(chosen, true);
    }

    /**
     * Selects matching visible units on Ctrl or a double click of a selected unit. An ordinary Shift click toggles the unit; Alt may recall its
     * selection history before replacing the selection.
     *
     * @param mapX        the map x coordinate of the click
     * @param mapY        the map y coordinate of the click
     * @param shift       whether Shift is held
     * @param control     whether Ctrl is held
     * @param alt         whether Alt is held
     * @param doubleClick whether this completes a double click
     * @param viewLeft    the left map coordinate of the view, for type selection
     * @param viewTop     the top map coordinate of the view
     * @param viewRight   the right map coordinate of the view
     * @param viewBottom  the bottom map coordinate of the view
     * @return the effect of the click
     * @throws IOException if a unit image cannot be read
     */
    private @NotNull CommandInput.Result clickSelect(int mapX, int mapY,
                                                     boolean shift, boolean control, boolean alt,
                                                     boolean doubleClick,
                                                     int viewLeft, int viewTop, int viewRight, int viewBottom) throws IOException {
        Unit unit = this.picker.pickUnit(mapX, mapY);

        if (unit == null) {
            return CommandInput.Result.IGNORED;
        }

        List<Unit> current = this.getSelectedUnits();
        boolean typeSelect = control || doubleClick && current.contains(unit);

        if (!typeSelect) {
            if (shift && !current.isEmpty()) {
                return this.toggleSelection(current, unit);
            }

            if (alt && this.recallHistory(unit)) {
                return CommandInput.Result.COMMAND;
            }

            return this.selectReplacing(List.of(unit), true);
        }

        this.picker.collect(viewLeft, viewTop, viewRight, viewBottom, this.candidates);

        List<Unit> chosen = this.createSelectionFilter().filterType(this.candidates, unit);

        if (shift && !current.isEmpty()) {
            return this.selectMerged(chosen, false);
        }

        return this.selectReplacing(chosen, true);
    }

    /**
     * Selects one panel unit, the other shown units with Shift, or matching types with Control. Replay type selection also matches the owner. Empty
     * choices are ignored and panel clicks play no selection sound.
     *
     * @param clicked the clicked unit
     * @param shown   the units shown in the panel grid, in slot order
     * @param shift   whether Shift is held
     * @param control whether Ctrl is held
     * @param alt     whether Alt is held: a single chosen unit first recalls its selection history record
     * @return the effect of the click
     */
    public @NotNull CommandInput.Result selectFromPanel(@NotNull Unit clicked, @NotNull List<Unit> shown, boolean shift, boolean control, boolean alt) {
        List<Unit> chosen = new ArrayList<>();

        if (shift) {
            for (Unit unit : shown) {
                if (unit != clicked) {
                    chosen.add(unit);
                }
            }
        } else if (control) {
            for (Unit unit : shown) {
                if (unit.getUnitId() == clicked.getUnitId() && (this.executesCommands || unit.getOwnerIndex() == clicked.getOwnerIndex())) {
                    chosen.add(unit);
                }
            }
        } else {
            chosen.add(clicked);
        }

        if (chosen.isEmpty()) {
            return CommandInput.Result.IGNORED;
        }

        if (alt && chosen.size() == 1 && this.recallHistory(chosen.getFirst())) {
            return CommandInput.Result.COMMAND;
        }

        return this.selectReplacing(chosen, false);
    }

    /**
     * Sends a right click ({@code 0x60}) at a map position and records its marker: a remembered building under the point flashes and its type is sent
     * as the command's unit type; otherwise a clicked unit flashes or the move marker is placed, and the unit type is none.
     *
     * @param mapX   the map x coordinate
     * @param mapY   the map y coordinate
     * @param queued whether Shift is held
     * @return the effect of the click
     * @throws IOException if a unit image cannot be read
     */
    public @NotNull CommandInput.Result handleRightClick(int mapX, int mapY, boolean queued) throws IOException {
        // A waiting targeting or placement mode is cancelled; without one, an empty selection ignores the press.
        if (this.console.getMode().getTargeting().isActive()) {
            this.dispatchPress(PointerButton.RIGHT, mapX, mapY, queued);
            this.placement.clear();

            return CommandInput.Result.INTERFACE;
        }

        return this.dispatchPress(PointerButton.RIGHT, mapX, mapY, queued) ? this.pressResult : CommandInput.Result.IGNORED;
    }

    /**
     * Sends the right click at a pressed point: a remembered building under the point flashes and its type is the command's unit type,
     * otherwise a clicked unit flashes or the move marker is placed; after the command, the per-unit target test either acknowledges or shows
     * its error (for example a sieged tank's "Target out of range.").
     *
     * @param target the pressed map point and the unit picked there
     * @param queued whether Shift is held
     * @return the effect of the click
     */
    private @NotNull CommandInput.Result rightClick(@NotNull CommandTarget target, boolean queued) {
        Unit unit = (Unit) target.getUnit();
        int mapX = target.getX();
        int mapY = target.getY();
        SpriteEffect remembered = this.markClick(mapX, mapY, unit, -1);
        int fogType = remembered != null ? UnitPicker.getRememberedBuildingType(remembered) : CommandInput.NO_UNIT_TYPE;
        CommandInput.Result result = this.input.rightClick(mapX, mapY, unit, fogType, queued);

        if (result == CommandInput.Result.COMMAND && this.input.checkRightClick(unit)) {
            this.acknowledge();
        }

        return result;
    }

    /**
     * Sends a minimap right click: command {@code 0x60} at the clicked minimap pixel's map point with no unit target (unit type
     * {@link CommandInput#NO_UNIT_TYPE}), queued with Shift, then the acknowledgement and the move marker.
     *
     * @param mapX   the map x (minimap pixel times map pixels per minimap pixel)
     * @param mapY   the map y
     * @param queued whether Shift is held
     * @return the effect of the click
     */
    public @NotNull CommandInput.Result handleMinimapRightClick(int mapX, int mapY, boolean queued) {
        Unit first = this.console.getSelection().getActiveUnit();

        if (!this.executesCommands || first == null || first.getOwnerIndex() != this.console.getPlayer() || this.console.isLocalSelection()) {
            return CommandInput.Result.IGNORED;
        }

        CommandInput.Result result = this.input.rightClick(mapX, mapY, null, CommandInput.NO_UNIT_TYPE, queued);

        if (result == CommandInput.Result.COMMAND) {
            this.acknowledge();
            this.addMarker(mapX, mapY, null, null, -1);
        }

        return result;
    }

    /**
     * Sends the active targeting order for a minimap left click: the targeted order ({@code 0x61}) at the map point with no unit target; the
     * targeting mode ends; the marker is drawn.
     *
     * @param mapX   the map x
     * @param mapY   the map y
     * @param queued whether Shift is held
     * @return the effect of the click
     */
    public @NotNull CommandInput.Result handleMinimapTarget(int mapX, int mapY, boolean queued) {
        int order = this.console.getMode().getGroundTargetOrder();
        TargetValidation.Click click = this.input.checkTargetClick(null, CommandInput.NO_UNIT_TYPE, mapX, mapY);

        if (!click.isSent()) {
            return CommandInput.Result.INTERFACE;
        }

        CommandInput.Result result = this.input.clickTarget(mapX, mapY, null, CommandInput.NO_UNIT_TYPE, queued);

        this.addMarker(mapX, mapY, null, null, order);

        if (result == CommandInput.Result.COMMAND && click.getError() == CommandErrorEvents.NO_ERROR_TEXT) {
            this.acknowledge();
        }

        return result;
    }

    /**
     * Sends the minimap ping command {@code 0x58} (x, y) for an Alt+minimap left click.
     *
     * @param mapX the map x
     * @param mapY the map y
     * @return the effect of the click
     */
    public @NotNull CommandInput.Result handleMinimapPing(int mapX, int mapY) {
        if (!this.executesCommands) {
            return CommandInput.Result.IGNORED;
        }

        byte[] payload = ReplayCommandCodecs.encode(ReplayCommandCodecs.MINIMAP_PING, new ReplayPositionCommand(mapX, mapY));

        this.sink.submit(this.console.getPlayer(), ReplayCommandIds.MINIMAP_PING, payload);

        return CommandInput.Result.COMMAND;
    }

    /**
     * Handles a control group key: assigns with Control, adds with Shift, and recalls otherwise. Alt centers the camera before recall; a repeated
     * recall within the deadline centers it after submission.
     *
     * @param group   the group from {@code 0} through {@code 9}
     * @param control whether Ctrl is held
     * @param shift   whether Shift is held
     * @param alt     whether Alt is held
     * @param now     a monotonic clock in milliseconds
     * @return the effect of the key
     * @throws IndexOutOfBoundsException if the group is outside the control groups
     */
    public @NotNull CommandInput.Result handleControlGroupKey(int group, boolean control, boolean shift, boolean alt, long now) {
        Objects.checkIndex(group, CONTROL_GROUP_COUNT);

        int operation = control ? CONTROL_GROUP_ASSIGN : shift ? CONTROL_GROUP_ADD : CONTROL_GROUP_RECALL;

        if (!this.executesCommands) {
            return this.handleLocalControlGroupKey(group, operation);
        }

        if (operation != CONTROL_GROUP_RECALL) {
            // An assign or add operation forgets the last recall.
            this.lastRecalledGroup = NO_GROUP;

            if (this.console.getSelection().getCount() == 0 || this.console.isLocalSelection()) {
                return CommandInput.Result.IGNORED;
            }

            this.console.followSynchronizedSelection();
            this.sink.submit(this.console.getPlayer(), ReplayCommandIds.HOTKEY,
                             ReplayCommandCodecs.encode(ReplayCommandCodecs.HOTKEY, new ReplayHotkeyCommand(operation, group)));

            return CommandInput.Result.COMMAND;
        }

        if (alt) {
            // Center on the group, then recall it.
            this.centerOnGroup(group);
        }

        return this.recallGroup(group, now);
    }

    /**
     * Recalls a control group: the group's live, shown own units (multi-selectable ones unless the group holds a single unit) are selected locally
     * with the selection sound, targeting and placement end, and command {@code 0x13} type {@code 1} is sent. A second recall within
     * {@link #DOUBLE_RECALL_MILLISECONDS} milliseconds centers the camera.
     *
     * @param group the control group or selection history group
     * @param now   a monotonic clock in milliseconds
     * @return the effect of the recall
     */
    private @NotNull CommandInput.Result recallGroup(int group, long now) {
        List<Unit> units = this.getGroupUnits(group, false);

        this.cancelMode();

        if (!units.isEmpty() && this.responses != null) {
            this.responses.respondToSelection(units);
        }

        // Selects the recalled units locally before the command goes out.
        if (units.isEmpty()) {
            this.console.followSynchronizedSelection();
        } else {
            this.console.showPendingSelection(units.toArray(new Unit[0]));
        }

        this.sink.submit(this.console.getPlayer(), ReplayCommandIds.HOTKEY,
                         ReplayCommandCodecs.encode(ReplayCommandCodecs.HOTKEY, new ReplayHotkeyCommand(CONTROL_GROUP_RECALL, group)));

        if (group == this.lastRecalledGroup && now - this.lastRecallTime < DOUBLE_RECALL_MILLISECONDS) {
            this.centerOnGroup(group);

            this.lastRecalledGroup = NO_GROUP;
        } else {
            this.lastRecalledGroup = group;
            this.lastRecallTime    = now;
        }

        return CommandInput.Result.COMMAND;
    }

    /**
     * Centers the camera on a control group: the integer mean of the positions of the group's recallable units that are not dying, snapped to
     * {@code 8} pixels.
     *
     * @param group the control group from {@code 0} through {@code 9}
     */
    private void centerOnGroup(int group) {
        ViewJumps jumps = this.viewJumps;
        List<Unit> units = this.getGroupUnits(group, true);

        if (jumps == null || units.isEmpty()) {
            return;
        }

        int sumX = 0;
        int sumY = 0;

        for (Unit unit : units) {
            sumX += unit.getPositionX();
            sumY += unit.getPositionY();
        }

        jumps.centerOn(sumX / units.size(), sumY / units.size());
    }


    /**
     * Finds the newest matching selection-history record using the greatest positive stamp and recalls its history group. Records are scanned in
     * reverse order and positions in forward order; replay viewers do not recall recorded history.
     *
     * @param unit the unit the history record holds
     * @return {@code true} if a record was recalled, {@code false} otherwise
     */
    private boolean recallHistory(@NotNull Unit unit) {
        ScrGameView view = this.console.getView();
        UnitSelection selections = view.getSelection();
        UnitPool pool = view.getUnitSystem().getUnitPool();
        int player = this.console.getPlayer();

        if (!this.executesCommands || selections == null || pool == null || player < 0 || player >= PlayerTable.PLAYER_SLOTS) {
            return false;
        }

        int best = NO_GROUP;
        int bestStamp = 0;

        for (int record = HISTORY_RECORDS - 1; record >= 0; record--) {
            for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
                int handle = selections.getHistoryHandle(player, record, position);
                int stamp = selections.getHistoryTimestamp(player, record);

                if (handle != 0 && pool.resolveHandle(handle) == unit && stamp > bestStamp) {
                    best      = record;
                    bestStamp = stamp;
                }
            }
        }

        if (best == NO_GROUP) {
            return false;
        }

        this.recallGroup(FIRST_HISTORY_GROUP + best, UiClockUtils.getHostTimeMilliseconds());

        return true;
    }

    /**
     * Centers the camera on the active unit (Ctrl+C / Alt+C, hotkey {@code 0x412}).
     *
     * @return {@code true} if there is an active unit, {@code false} otherwise
     */
    public boolean centerOnActiveUnit() {
        Unit active = this.console.getSelection().getActiveUnit();
        Sprite sprite = active != null ? active.getSpriteOrNull() : null;
        ViewJumps jumps = this.viewJumps;

        if (sprite == null || jumps == null) {
            return false;
        }

        jumps.centerOn(sprite.getX(), sprite.getY());

        return true;
    }

    /**
     * Keeps the replay viewer's own control groups: they hold the shown units locally and never reach the recorded players, whose groups exist only
     * inside the replayed game.
     *
     * @param group     the control group from {@code 0} through {@code 9}
     * @param operation the assign, add or recall operation
     * @return the effect of the key
     */
    private @NotNull CommandInput.Result handleLocalControlGroupKey(int group, int operation) {
        InterfaceSelection selection = this.console.getSelection();
        List<Unit> units = new ArrayList<>();

        if (operation == CONTROL_GROUP_ADD) {
            Unit[] groupUnits = this.localGroups[group];

            if (groupUnits != null) {
                units.addAll(this.getLiveUnits(groupUnits));
            }
        }

        if (operation != CONTROL_GROUP_RECALL) {
            for (int slot = 0; slot < selection.getCount() && units.size() < UnitSelection.MAXIMUM_SELECTED; slot++) {
                Unit unit = selection.getUnit(slot);

                if (unit != null && !units.contains(unit)) {
                    units.add(unit);
                }
            }

            if (units.isEmpty()) {
                return CommandInput.Result.IGNORED;
            }

            this.localGroups[group] = units.toArray(new Unit[0]);

            return CommandInput.Result.INTERFACE;
        }

        Unit[] groupUnits = this.localGroups[group];

        if (groupUnits == null) {
            return CommandInput.Result.IGNORED;
        }

        List<Unit> live = this.getLiveUnits(groupUnits);

        if (live.isEmpty()) {
            return CommandInput.Result.IGNORED;
        }

        this.console.showLocalSelection(live.toArray(new Unit[0]));

        return CommandInput.Result.INTERFACE;
    }

    /**
     * Drops markers shown for at least a number of frames; a move marker created on frame {@code F} is shown through {@code F + lifetime - 1}, a
     * flash until its timer ({@link #FLASH_TIMER}) has run down, through {@code F + FLASH_TIMER}.
     *
     * @param currentFrame the current game frame
     * @param lifetime     the move marker lifetime in frames
     */
    public void expireMarkers(int currentFrame, int lifetime) {
        this.markers.removeIf(marker -> currentFrame - marker.getFrame() >= (marker.isFlash() ? FLASH_TIMER + 1 : lifetime) ||
                                        currentFrame < marker.getFrame());
    }

    /**
     * Collects visible larvae belonging to the active unit in spatial-query order, up to the selection capacity. Alt may recall a single larva
     * history; otherwise the selection is replaced with its response sound.
     *
     * @return the effect
     */
    public @NotNull CommandInput.Result selectLarva() {
        ScrGameView view = this.console.getView();
        Unit active = this.console.getSelection().getActiveUnit();
        UnitSpatialIndex index = view.getUnitSystem().getUnitSpatialIndex();
        UnitPool pool = view.getUnitSystem().getUnitPool();

        if (active == null || index == null || pool == null) {
            return CommandInput.Result.IGNORED;
        }

        int activeMapX = active.getPositionX();
        int activeMapY = active.getPositionY();
        List<Unit> larvae = new ArrayList<>();

        for (int poolIndex : index.findAllInRectangleUnexpanded(activeMapX - LARVA_SEARCH_RADIUS,
                                                                activeMapY - LARVA_SEARCH_RADIUS,
                                                                activeMapX + LARVA_SEARCH_RADIUS,
                                                                activeMapY + LARVA_SEARCH_RADIUS)) {
            Unit candidate = pool.getUnit(poolIndex);
            Sprite sprite = candidate.getSpriteOrNull();

            if (candidate.getUnitId() != ScrUnitIds.ZERG_LARVA || candidate.getParentUnit() != active || sprite == null ||
                (sprite.getFlags() & Sprite.FLAG_HIDDEN) != 0) {
                continue;
            }

            larvae.add(candidate);

            if (larvae.size() == UnitSelection.MAXIMUM_SELECTED) {
                break;
            }
        }

        if (larvae.isEmpty()) {
            return CommandInput.Result.IGNORED;
        }

        BooleanSupplier alt = this.altKey;

        if (larvae.size() == 1 && alt != null && alt.getAsBoolean() && this.recallHistory(larvae.getFirst())) {
            return CommandInput.Result.COMMAND;
        }

        return this.selectReplacing(larvae, true);
    }

    /**
     * Removes a retired or hidden unit from the local selection array, as the lifecycle deselection clears it from every selection
     * ({@link UnitSelection#removeUnit}), and a selected unit the local player stops seeing or detecting; nothing is sent.
     *
     * @param unit the unit
     */
    @Override
    public void removeUnit(@NotNull Unit unit) {
        this.console.removeUnit(unit);
    }

    /**
     * Adds the second offspring of a hatched egg to the first free local selection slot when the egg was selected; leaves a full selection unchanged
     * and sends no sound.
     * <p>
     * Replay viewers ignore the call; a foreign egg updates only the local selection, while an own egg submits a replacement selection.
     *
     * @param egg    the unit that hatched (the first offspring keeps the egg's unit)
     * @param second the second offspring
     */
    @Override
    public void addHatchedUnit(@NotNull Unit egg, @NotNull Unit second) {
        if (!this.executesCommands) {
            return;
        }

        List<Unit> current = this.getSelectedUnits();

        if (!current.contains(egg) || current.size() >= UnitSelection.MAXIMUM_SELECTED) {
            return;
        }

        List<Unit> chosen = new ArrayList<>(current);

        chosen.add(second);

        if (egg.getOwnerIndex() != this.console.getPlayer()) {
            // Another player's egg shown in the local array: the array takes both units; no command selects them.
            this.console.showLocalSelection(chosen.toArray(new Unit[0]));

            return;
        }

        this.selectReplacing(chosen, false);
    }

    /**
     * Toggles a unit for a Shift click without type selection: removes a selected unit, or adds a qualifying unit to a qualifying selection that
     * is not full.
     *
     * @param current the current selection
     * @param unit    the clicked unit
     * @return the effect of the click
     */
    private @NotNull CommandInput.Result toggleSelection(@NotNull List<Unit> current, @NotNull Unit unit) {
        if (current.contains(unit)) {
            if (!this.executesCommands) {
                List<Unit> remaining = new ArrayList<>(current);

                remaining.remove(unit);
                this.console.showLocalSelection(remaining.toArray(new Unit[0]));

                return CommandInput.Result.INTERFACE;
            }

            // Removing a unit plays no sound.
            return this.submitSelection(ReplayCommandIds.SELECT_REMOVE_121, List.of(unit));
        }

        ScrSelectionRules rules = this.createSelectionRules();

        if (current.size() >= UnitSelection.MAXIMUM_SELECTED || !rules.canJoinMultipleSelection(unit) ||
            !rules.canJoinMultipleSelection(current.getFirst())) {
            return CommandInput.Result.IGNORED;
        }

        List<Unit> merged = new ArrayList<>(current);

        merged.add(unit);

        return this.selectAdding(merged, List.of(unit));
    }

    /**
     * Creates the selection rules of the local player: any owner when the interface does not execute commands (replay or observer), the
     * local comparison mask as the vision mask.
     */
    private @NotNull ScrSelectionRules createSelectionRules() {
        return new ScrSelectionRules(this.console.getView(), this.console.getPlayer(), !this.executesCommands,
                                     this.console.getView().getLocalVision().getComparisonMask());
    }

    private @NotNull SelectionFilter<Unit> createSelectionFilter() {
        return new SelectionFilter<>(ScrSelectionRules.CAPACITY, this.createSelectionRules());
    }

    /**
     * Merges new units into the current selection and selects the result with the sound.
     *
     * @param chosen the units to merge
     * @param alt    whether Alt is held: a single merged unit first recalls its selection history record
     * @return the effect of the selection
     */
    private @NotNull CommandInput.Result selectMerged(@NotNull List<Unit> chosen, boolean alt) {
        List<Unit> current = this.getSelectedUnits();
        List<Unit> merged = this.createSelectionFilter().merge(current, chosen);

        if (alt && merged.size() == 1 && this.recallHistory(merged.getFirst())) {
            return CommandInput.Result.COMMAND;
        }

        List<Unit> added = new ArrayList<>();

        for (Unit unit : merged) {
            if (!current.contains(unit)) {
                added.add(unit);
            }
        }

        return this.selectAdding(merged, added);
    }

    private @NotNull CommandInput.Result selectAdding(@NotNull List<Unit> merged, @NotNull List<Unit> added) {
        if (!this.executesCommands) {
            this.console.showLocalSelection(merged.toArray(new Unit[0]));

            return CommandInput.Result.INTERFACE;
        }

        // The merged list is selected again with the sound even when nothing was added.
        CommandInput.Result result = added.isEmpty() ? CommandInput.Result.INTERFACE : this.submitSelection(ReplayCommandIds.SELECT_ADD_121, added);

        if (this.responses != null) {
            this.responses.respondToSelection(merged);
        }

        return result;
    }

    /**
     * Replaces the selection with command {@code 0x63}: own units are shown as the pending selection; a single unit of another player is shown as a
     * local selection and the command selects it all the same, so the game's selection of the player holds it. The replay viewer shows every
     * selection locally.
     *
     * @param chosen the units to select, in order
     * @param sound  whether the selection response and the rally point are shown
     * @return the effect of the selection
     */
    private @NotNull CommandInput.Result selectReplacing(@NotNull List<Unit> chosen, boolean sound) {
        if (!this.executesCommands) {
            this.console.showLocalSelection(chosen.toArray(new Unit[0]));

            return CommandInput.Result.INTERFACE;
        }

        Unit first = chosen.getFirst();
        UnitPool pool = this.console.getView().getUnitSystem().getUnitPool();

        if (first.getOwnerIndex() != this.console.getPlayer() || pool == null) {
            this.console.showLocalSelection(first);

            if (sound && this.responses != null) {
                this.responses.respondToSelection(List.of(first));
            }

            if (pool == null) {
                return CommandInput.Result.INTERFACE;
            }

            this.sendSelection(ReplayCommandIds.SELECT_121, List.of(first), pool);

            return CommandInput.Result.COMMAND;
        }

        CommandInput.Result result = this.submitSelection(ReplayCommandIds.SELECT_121, chosen);

        if (sound && this.responses != null) {
            this.responses.respondToSelection(chosen);
        }

        if (sound && chosen.size() == 1) {
            this.showRally(first);
        }

        return result;
    }

    /**
     * Shows the rally point of a single selected own producer: when the rally position is set and differs from the unit's position, flashes a
     * remembered building there or places the move marker ({@link #markClick}).
     *
     * @param unit the selected unit
     */
    private void showRally(@NotNull Unit unit) {
        if (unit.getOwnerIndex() != this.console.getPlayer() ||
            !this.console.getView().requireGameData().getUnitTypes().isRallyProducer(unit.getUnitId())) {
            return;
        }

        int rallyMapX = unit.getRallyPositionX();
        int rallyMapY = unit.getRallyPositionY();

        if (rallyMapX == 0 && rallyMapY == 0 || rallyMapX == unit.getPositionX() && rallyMapY == unit.getPositionY()) {
            return;
        }

        this.markClick(rallyMapX, rallyMapY, null, -1);
    }

    private @NotNull CommandInput.Result submitSelection(int opcode, @NotNull List<Unit> units) {
        UnitPool pool = this.console.getView().getUnitSystem().getUnitPool();

        if (pool == null) {
            return CommandInput.Result.IGNORED;
        }

        List<Unit> shown = opcode == ReplayCommandIds.SELECT_121 ? new ArrayList<>() : this.getSelectedUnits();

        if (opcode == ReplayCommandIds.SELECT_REMOVE_121) {
            shown.removeAll(units);
        } else {
            for (Unit unit : units) {
                if (!shown.contains(unit) && shown.size() < UnitSelection.MAXIMUM_SELECTED) {
                    shown.add(unit);
                }
            }
        }

        this.console.showPendingSelection(shown.toArray(new Unit[0]));
        this.sendSelection(opcode, units, pool);

        return CommandInput.Result.COMMAND;
    }

    /**
     * Sends a selection command of the local player with the units' handles.
     *
     * @param opcode the command: {@link ReplayCommandIds#SELECT_121}, {@link ReplayCommandIds#SELECT_ADD_121} or
     *               {@link ReplayCommandIds#SELECT_REMOVE_121}
     * @param units  the units, at most twelve
     * @param pool   the unit pool that gives the handles
     */
    private void sendSelection(int opcode, @NotNull List<Unit> units, @NotNull UnitPool pool) {
        long[] handles = new long[units.size()];

        for (int index = 0; index < units.size(); index++) {
            handles[index] = Integer.toUnsignedLong(pool.getHandle(units.get(index).getIndex()));
        }

        SelectionRequest.Operation operation = switch (opcode) {
            case ReplayCommandIds.SELECT_ADD_121 -> SelectionRequest.Operation.ADD;
            case ReplayCommandIds.SELECT_REMOVE_121 -> SelectionRequest.Operation.REMOVE;
            default -> SelectionRequest.Operation.REPLACE;
        };
        ReplayCommandCodec<ReplaySelectionCommand> codec = switch (opcode) {
            case ReplayCommandIds.SELECT_ADD_121 -> ReplayCommandCodecs.SELECT_ADD_121;
            case ReplayCommandIds.SELECT_REMOVE_121 -> ReplayCommandCodecs.SELECT_REMOVE_121;
            default -> ReplayCommandCodecs.SELECT_121;
        };
        byte[] payload = ReplayCommandCodecs.encode(codec, ReplaySelectionCommand.create(operation, Integer.BYTES, handles));

        this.sink.submit(this.console.getPlayer(), opcode, payload);
    }

    /**
     * Flashes a remembered building under the point before considering a clicked unit, otherwise creates a ground marker. A flash sets the sprite's
     * flash timer to {@link #FLASH_TIMER}; the game's flash update shows its solid circle.
     *
     * @param mapX   the map x
     * @param mapY   the map y
     * @param target the clicked unit, or {@code null}
     * @param order  the order the click issued, or {@code -1}
     * @return the remembered building under the point, or {@code null}
     */
    private @Nullable SpriteEffect markClick(int mapX, int mapY, @Nullable Unit target, int order) {
        SpriteEffect remembered = this.picker.pickRememberedBuilding(mapX, mapY);
        Sprite flashed = remembered != null ? remembered.getSprite() : target != null ? target.getSpriteOrNull() : null;

        if (flashed != null) {
            flashed.setFlashTimer(FLASH_TIMER);
        }

        this.addMarker(mapX, mapY, remembered == null ? target : null, remembered, order);

        return remembered;
    }

    private void addMarker(int mapX, int mapY, @Nullable Unit target, @Nullable SpriteEffect remembered, int order) {
        MarkerKind kind;

        if (target != null) {
            kind = this.classify(target.getOwnerIndex());
        } else if (remembered != null) {
            kind = this.classify(remembered.getSprite().getOwner());
        } else {
            kind = MarkerKind.GROUND;
        }

        if (this.markers.size() >= MARKER_LIMIT) {
            this.markers.removeFirst();
        }

        this.markers.add(new ClickMarker(mapX, mapY, target, remembered, kind, order, this.console.getView().getCurrentFrame()));
    }

    private @NotNull MarkerKind classify(int owner) {
        int player = this.console.getPlayer();

        if (owner == player) {
            return MarkerKind.OWN;
        }

        if (owner >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return MarkerKind.ALLY;
        }

        ScrGameView view = this.console.getView();
        PlayerRelations relations = view.getPlayerTable().getPlayerRelations();

        return relations != null && relations.getAlliance(player, owner) != 0 ? MarkerKind.ALLY : MarkerKind.ENEMY;
    }

    /**
     * Chooses drag, edge-scroll, targeting or magnifier cursors using the current hovered unit and player relations.
     *
     * @return the cursor over the map view
     */
    private @NotNull Cursor computeCursor() {
        if (this.dragging) {
            return Cursor.DRAG;
        }

        boolean targeting = this.console.getMode().isTargeting();

        if (this.scrollDirection >= 0 && !targeting) {
            return Cursor.SCROLL;
        }

        Unit unit = this.hoveredUnit;

        if (unit == null) {
            return targeting ? Cursor.TARGET_NONE : Cursor.ARROW;
        }

        int player = this.console.getPlayer();
        int owner = unit.getOwnerIndex();

        if (owner == PlayerTable.NEUTRAL_PLAYER_ID) {
            return targeting ? Cursor.TARGET_NEUTRAL : Cursor.MAGNIFIER_NEUTRAL;
        }

        PlayerRelations relations = this.console.getView().getPlayerTable().getPlayerRelations();
        boolean friendly = !this.executesCommands || owner == player ||
                           player < PlayerTable.PLAYER_SLOTS && owner < PlayerTable.PLAYER_SLOTS && relations != null &&
                           relations.getAlliance(player, owner) != 0;

        if (friendly) {
            return targeting ? Cursor.TARGET_OWN : Cursor.MAGNIFIER_OWN;
        }

        return targeting ? Cursor.TARGET_ENEMY : Cursor.MAGNIFIER_ENEMY;
    }
}
