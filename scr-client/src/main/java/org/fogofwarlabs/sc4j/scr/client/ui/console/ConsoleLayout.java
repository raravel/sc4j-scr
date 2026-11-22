package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.display.LetterboxFit;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.client.world.GameViewport;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceTransform;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

/**
 * Loads and places the six console dialogs for the selected race, replay and art mode. The interface fits the viewport uniformly, centered
 * horizontally and anchored at the bottom. Elements kept at their offsets from the game view's top edge are placed by {@link GameViewport} instead.
 */
public final class ConsoleLayout implements InterfaceTransform {
    /**
     * The width of the interface space.
     */
    public static final int INTERFACE_WIDTH = 640;
    /**
     * The height of the interface space.
     */
    public static final int INTERFACE_HEIGHT = 480;
    /**
     * The top of the console area in the interface space; the map view above it is not covered by console dialogs.
     */
    public static final int CONSOLE_TOP = 315;
    /**
     * The race letters of the console, score and text box files by race ({@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss).
     */
    public static final @NotNull String RACE_LETTERS = "ztp";

    /**
     * The console skin name of the remastered console without a purchased skin.
     */
    private static final @NotNull String DEFAULT_CONSOLE = "default";

    /**
     * Creates a {@link ConsoleLayout} instance from the installed console dialogs of a race.
     *
     * @param storage the installed game storage
     * @param race    the race: {@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss; other values use the default buttons
     * @return the created {@link ConsoleLayout} instance
     * @throws IOException if a dialog file is missing or malformed
     */
    public static @NotNull ConsoleLayout load(@NotNull CascStorage storage, int race) throws IOException {
        return load(storage, race, false);
    }

    /**
     * Creates a {@link ConsoleLayout} instance from the installed console dialogs of a race; in a replay the command card is {@code statbtnn}, which
     * also holds the replay panel (controls {@code -45..-48} and {@code 10}).
     *
     * @param storage the installed game storage
     * @param race    the race: {@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss
     * @param replay  whether the replay command card is loaded
     * @return the created {@link ConsoleLayout} instance
     * @throws IOException if a dialog file is missing or malformed
     */
    public static @NotNull ConsoleLayout load(@NotNull CascStorage storage, int race, boolean replay) throws IOException {
        return load(storage, race, replay, false);
    }

    /**
     * Creates a {@link ConsoleLayout} instance from the installed console dialogs of a race for the classic or the remastered console art. The
     * command card file is {@code rez\statbtn%s.ui.json} with the console's name: the race letter for the classic console, the console skin name for
     * the remastered consoles, {@code default} without a purchased skin. The Zerg classic card ({@code statbtnz}) is shifted by ({@code -5, +3})
     * against the others; the remastered consoles of all three races use {@code statbtndefault}.
     *
     * @param storage    the installed game storage
     * @param race       the race: {@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss
     * @param replay     whether the replay command card ({@code statbtnn}) is loaded
     * @param remastered whether the remastered console art is shown (HD and HD2)
     * @return the created {@link ConsoleLayout} instance
     * @throws IOException if a dialog file is missing or malformed
     */
    public static @NotNull ConsoleLayout load(@NotNull CascStorage storage, int race, boolean replay, boolean remastered) throws IOException {
        String buttons = replay ? "statbtnn" : remastered || race < 0 || race >= RACE_LETTERS.length() ? "statbtn" + DEFAULT_CONSOLE
                                                                                                       : "statbtn" + RACE_LETTERS.charAt(race);

        return new ConsoleLayout(DialogLayout.load(storage, buttons),
                                 DialogLayout.load(storage, "statdata"),
                                 DialogLayout.load(storage, "statres"),
                                 DialogLayout.load(storage, "statport"),
                                 DialogLayout.load(storage, "minimap"),
                                 DialogLayout.load(storage, "stat_f10"));
    }

    private float interfaceWidth;
    private float consoleTop;

    private final @NotNull DialogLayout commandButtons;
    private final @NotNull DialogLayout selectionPanel;
    private final @NotNull DialogLayout resources;
    private final @NotNull DialogLayout portrait;
    private final @NotNull DialogLayout minimap;
    private final @NotNull DialogLayout menu;

    private final @NotNull ConsoleDialog menuDialog;
    private final @NotNull List<ConsoleDialog> dialogs;

    private float scale;
    private float offsetX;
    private float offsetY;
    private float extension;

    /**
     * Creates a {@link ConsoleLayout} instance from parsed dialogs.
     *
     * @param commandButtons the command card dialog
     * @param selectionPanel the selection panel dialog
     * @param resources      the resource dialog
     * @param portrait       the portrait dialog
     * @param minimap        the minimap dialog
     * @param menu           the menu button dialog
     */
    public ConsoleLayout(@NotNull DialogLayout commandButtons,
                         @NotNull DialogLayout selectionPanel,
                         @NotNull DialogLayout resources,
                         @NotNull DialogLayout portrait,
                         @NotNull DialogLayout minimap,
                         @NotNull DialogLayout menu) {
        this.interfaceWidth = INTERFACE_WIDTH;
        this.consoleTop     = CONSOLE_TOP;

        this.commandButtons = commandButtons;
        this.selectionPanel = selectionPanel;
        this.resources      = resources;
        this.portrait       = portrait;
        this.minimap        = minimap;
        this.menu           = menu;

        this.menuDialog = new ConsoleDialog(menu);
        this.dialogs    = List.of(new ConsoleDialog(commandButtons),
                                  new ConsoleDialog(selectionPanel),
                                  new ConsoleDialog(resources),
                                  new ConsoleDialog(portrait),
                                  new ConsoleDialog(minimap),
                                  this.menuDialog);

        this.scale     = 1;
        this.offsetX   = 0;
        this.offsetY   = 0;
        this.extension = 0;
    }

    /**
     * @return the runtime state of the menu button dialog ({@code stat_f10}): its pressed button
     */
    public @NotNull ScrDialog getMenuDialog() {
        return this.menuDialog.getDialog();
    }

    /**
     * @return the console dialogs as open dialogs: {@code statbtn}, {@code statdata}, {@code statres},
     * {@code statport}, {@code minimap}, {@code stat_f10}
     */
    public @NotNull List<ConsoleDialog> getDialogs() {
        return this.dialogs;
    }

    /**
     * Returns the console dialog of a loaded dialog.
     *
     * @param layout one of this console's dialogs, for example {@link #getCommandButtons()}
     * @return its open dialog
     * @throws IllegalArgumentException if the layout is not one of this console's dialogs
     */
    public @NotNull ConsoleDialog getDialog(@NotNull DialogLayout layout) {
        for (ConsoleDialog dialog : this.dialogs) {
            if (dialog.getLayout() == layout) {
                return dialog;
            }
        }

        throw Exceptions.newIllegalArgumentException("Not a console dialog: {0}", layout.getName());
    }

    /**
     * @return the command card dialog
     */
    public @NotNull DialogLayout getCommandButtons() {
        return this.commandButtons;
    }

    /**
     * @return the selection panel dialog
     */
    public @NotNull DialogLayout getSelectionPanel() {
        return this.selectionPanel;
    }

    /**
     * @return the resource dialog
     */
    public @NotNull DialogLayout getResources() {
        return this.resources;
    }

    /**
     * @return the portrait dialog
     */
    public @NotNull DialogLayout getPortrait() {
        return this.portrait;
    }

    /**
     * @return the minimap dialog
     */
    public @NotNull DialogLayout getMinimap() {
        return this.minimap;
    }

    /**
     * @return the menu button dialog
     */
    public @NotNull DialogLayout getMenu() {
        return this.menu;
    }

    /**
     * @return the screen pixels per interface pixel
     */
    @Override
    public float getScale() {
        return this.scale;
    }

    /**
     * @return the screen rows of the game view above the interface: {@code 0} at the native aspects, positive in a window narrower than {@code 4:3}
     */
    public float getViewExtension() {
        return this.extension;
    }

    /**
     * @return the interface width: {@code 640}, or wider for the widescreen HD console
     */
    public float getInterfaceWidth() {
        return this.interfaceWidth;
    }

    /**
     * @return the logical width {@code W} of the interface pixels the transform maps
     * ({@link org.fogofwarlabs.sc4j.engine.client.host.AspectRange#getLogicalWidth}): {@code 640}, or wider for the widescreen interface
     */
    @Override
    public float getLogicalWidth() {
        return this.interfaceWidth;
    }

    /**
     * @return the interface row of the console's upper band
     */
    public float getConsoleTop() {
        return this.consoleTop;
    }

    /**
     * Sets the client's game view ({@link GameViewport}) this interface is placed in: the interface is fitted into the window's interface surface
     * as {@link GameViewport#placeInterface} places it.
     *
     * @param viewport the client's viewport
     * @throws IllegalStateException if the viewport has no window yet
     */
    public void setViewport(@NotNull GameViewport viewport) {
        UiSurface surface = viewport.getSurface();

        if (surface == null) {
            throw Exceptions.newIllegalStateException("The game view has no window.");
        }

        LetterboxFit placed = GameViewport.placeInterface(this.interfaceWidth, surface);

        this.scale     = placed.getScale();
        this.offsetX   = placed.getLeft();
        this.offsetY   = placed.getTop();
        this.extension = GameViewport.computeViewExtension(this.offsetY, 0);
    }


    /**
     * Tests whether a screen row lies in the console area below {@link #CONSOLE_TOP}.
     * <p>
     * The console image has an irregular upper edge; this band test does not follow its transparent pixels.
     *
     * @param screenY the screen y coordinate
     * @return {@code true} if the screen row lies below the console top, {@code false} otherwise
     */
    public boolean isOnConsole(float screenY) {
        return this.toInterfaceY(screenY) >= this.consoleTop;
    }

    /**
     * Finds the control of a console dialog under a screen position, as {@link ScrDialog#findControlAt(float, float)}.
     *
     * @param dialog  the dialog's runtime state
     * @param layout  the dialog's controls in interface coordinates
     * @param screenX the screen x
     * @param screenY the screen y
     * @return the control id, or {@link ScrDialog#NONE}
     */
    public int findControl(@NotNull ScrDialog dialog, @NotNull DialogLayout layout, float screenX, float screenY) {
        ScrControl control = dialog.findControlAt(this.toInterfaceX(screenX) - layout.getX(), this.toInterfaceY(screenY) - layout.getY());

        return control == null ? ScrDialog.NONE : control.getDefinition().getId();
    }

    /**
     * Converts an interface x to a screen x.
     *
     * @param x an interface x
     * @return the screen x
     */
    @Override
    public float toScreenX(float x) {
        return this.offsetX + x * this.scale;
    }

    /**
     * Converts an interface y to a screen y.
     *
     * @param y an interface y
     * @return the screen y
     */
    @Override
    public float toScreenY(float y) {
        return this.offsetY + y * this.scale;
    }

    /**
     * Converts a screen x to an interface x.
     *
     * @param x a screen x
     * @return the interface x
     */
    public float toInterfaceX(float x) {
        return (x - this.offsetX) / this.scale;
    }

    /**
     * Converts a screen y to an interface y.
     *
     * @param y a screen y
     * @return the interface y
     */
    public float toInterfaceY(float y) {
        return (y - this.offsetY) / this.scale;
    }

    /**
     * Creates a {@link ConsoleLayout} instance with other dialogs in this layout's interface width and console top.
     *
     * @param commandButtons the command card dialog
     * @param selectionPanel the selection panel dialog
     * @param resources      the resource dialog
     * @param portrait       the portrait dialog
     * @param minimap        the minimap dialog
     * @param menu           the menu button dialog
     * @return the created {@link ConsoleLayout} instance
     */
    public @NotNull ConsoleLayout createPlacedLayout(@NotNull DialogLayout commandButtons,
                                                     @NotNull DialogLayout selectionPanel,
                                                     @NotNull DialogLayout resources,
                                                     @NotNull DialogLayout portrait,
                                                     @NotNull DialogLayout minimap,
                                                     @NotNull DialogLayout menu) {
        ConsoleLayout placed = new ConsoleLayout(commandButtons, selectionPanel, resources, portrait, minimap, menu);

        placed.interfaceWidth = this.interfaceWidth;
        placed.consoleTop     = this.consoleTop;

        return placed;
    }

    /**
     * Creates a {@link ConsoleLayout} instance with this layout's dialogs moved and a wider interface space.
     *
     * @param interfaceWidth the interface width
     * @param consoleTop     the interface row of the console's upper band
     * @param buttonsX       the command card x offset
     * @param buttonsY       the command card y offset
     * @param panelX         the selection panel x offset
     * @param panelY         the selection panel y offset
     * @param resourcesX     the resource dialog x offset
     * @param resourcesY     the resource dialog y offset
     * @param portraitX      the portrait x offset
     * @param portraitY      the portrait y offset
     * @param minimapX       the minimap x offset
     * @param minimapY       the minimap y offset
     * @param menuX          the menu button x offset
     * @param menuY          the menu button y offset
     * @return the created {@link ConsoleLayout} instance
     */
    public @NotNull ConsoleLayout createMovedLayout(float interfaceWidth, float consoleTop,
                                                    float buttonsX, float buttonsY,
                                                    float panelX, float panelY,
                                                    float resourcesX, float resourcesY,
                                                    float portraitX, float portraitY,
                                                    float minimapX, float minimapY,
                                                    float menuX, float menuY) {
        ConsoleLayout moved = new ConsoleLayout(this.commandButtons.createMovedLayout(buttonsX, buttonsY),
                                                this.selectionPanel.createMovedLayout(panelX, panelY),
                                                this.resources.createMovedLayout(resourcesX, resourcesY),
                                                this.portrait.createMovedLayout(portraitX, portraitY),
                                                this.minimap.createMovedLayout(minimapX, minimapY),
                                                this.menu.createMovedLayout(menuX, menuY));

        moved.interfaceWidth = interfaceWidth;
        moved.consoleTop     = consoleTop;

        return moved;
    }
}
