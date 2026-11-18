package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.scr.client.ui.command.CommandCard;
import org.fogofwarlabs.sc4j.scr.client.ui.console.ConsoleModel;
import org.fogofwarlabs.sc4j.scr.client.ui.input.WorldInterface;
import org.fogofwarlabs.sc4j.scr.client.world.ClientCamera;
import org.fogofwarlabs.sc4j.scr.client.world.GameScreenCamera;
import org.fogofwarlabs.sc4j.scr.client.world.GameViewport;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.client.world.ScrPresentation;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalInterfaceState;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalView;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * The client's interface over the runner's game, without a window: the console model (selection, command card, statuses) and the world
 * interaction ({@link WorldInterface}) that turns a left click on the map into the selection command, with the classic 640x480 screen geometry.
 * <p>
 * It is made at the first left press or the first shot. A left press installs it as the game's local selection, as the client does at a game
 * start, and from then on it follows every frame as the client's draw does: the console model reads the game and the displayed button set is
 * supplied to the game.
 */
final class ClientInterface {
    private static final int TILE_SIZE = 32;

    private final @NotNull Game game;
    private final @NotNull GameSpeed timing;
    private final int player;
    private final int mapWidth;
    private final int mapHeight;

    private final @NotNull ScrGameView view;
    private final @NotNull ConsoleModel model;
    private final @NotNull WorldInterface world;
    private final @NotNull GameViewport viewport;
    private final @NotNull ClientCamera camera;

    private boolean installed;

    /**
     * Creates the interface of the local player.
     *
     * @param game   the game
     * @param live   the live session, which takes the selection commands
     * @param timing the game speed configuration
     */
    ClientInterface(@NotNull Game game, @NotNull LiveGame live, @NotNull GameSpeed timing) {
        this.game      = game;
        this.timing    = timing;
        this.player    = live.getLocalPlayer();
        this.mapWidth  = game.getMap().getWidth() * TILE_SIZE;
        this.mapHeight = game.getMap().getHeight() * TILE_SIZE;

        this.view  = new ScrGameView(game);
        this.model = new ConsoleModel(this.view, new ScrPresentation(game));
        // Live play: the commands go into the session's turn buffer and execute on a later frame.
        this.world = new WorldInterface(this.model, (issuer, opcode, payload) -> live.submit(opcode, payload), true);

        this.viewport = new GameViewport();
        this.viewport.setSurface(UiSurface.of(LiveMapSession.VIEWPORT_WIDTH, LiveMapSession.VIEWPORT_HEIGHT, DisplayScale.ONE));
        this.viewport.setInterface(false, true, false);
        this.camera = new ClientCamera(this.mapWidth, this.mapHeight);
        this.camera.setViewport(this.viewport);
        this.camera.setNativeZoom();

        this.model.setOwnLocalSelection(this.player);
        this.installed = false;
    }

    /**
     * Installs the interface as the game's local selection; from then on {@link #followFrame} keeps it current.
     */
    private void install() {
        if (!this.installed) {
            this.game.getInterfaceLink().setLocalSelection(this.world);
            this.installed = true;
            this.followFrame();
        }
    }

    /**
     * Follows a finished frame as the client's draw does, once installed: the console model reads the game, old click markers expire and the
     * displayed button set (an EUD write to it first) is supplied to the game.
     */
    void followFrame() {
        if (!this.installed) {
            return;
        }

        LocalInterfaceState local = this.game.getInterfaceLink().getLocalInterface();

        if (local.takeWritten()) {
            this.model.getCommandCard().setDisplayedSet(local.getDisplayedButtonSet());
        }

        this.refresh();
        local.setDisplayedButtonSet(this.model.getCommandCard().getDisplayedSet());
    }

    /**
     * Reads the console values for a shot when the interface does not follow the frames.
     */
    void prepareShot() {
        if (!this.installed) {
            this.refresh();
        }

        // The camera shows the game screen the game holds (LocalView), as the client follows a screen write.
        LocalView localView = this.game.getInterfaceLink().getLocalView();

        GameScreenCamera.ofPlayView(this.camera, this.viewport, this.mapWidth, this.mapHeight).moveTo(localView.getScreenX(), localView.getScreenY());
    }

    private void refresh() {
        this.model.update(this.player, CommandCard.Perspective.PLAYER, false, this.timing.getSpeedIndex(), this.timing.getFactor());
        this.world.expireMarkers(this.game.getCurrentFrame(), WorldInterface.MARKER_LIFETIME);
    }

    /**
     * Presses the left button over the map as the client's pointer handler does: a press over the game view starts a selection box (or completes a
     * targeting mode).
     *
     * @param mapX    the map x of the pointer
     * @param mapY    the map y of the pointer
     * @param screenX the pointer column on the 640x480 screen
     * @param screenY the pointer row on the 640x480 screen
     * @throws IOException if a unit image cannot be read
     */
    void pressLeft(int mapX, int mapY, int screenX, int screenY) throws IOException {
        this.install();

        if (screenY >= this.viewport.getPlayRows()) {
            // Below the play view the pointer is over the console, not the map.
            return;
        }

        this.world.handlePointerMove(mapX, mapY, screenX, -1);
        this.world.handleLeftPress(mapX, mapY, false);
    }

    /**
     * Releases the left button: a press and release within a few pixels selects the unit under the pointer, a larger box the units in it.
     *
     * @param mapX the map x of the pointer
     * @param mapY the map y of the pointer
     * @throws IOException if a unit image cannot be read
     */
    void releaseLeft(int mapX, int mapY) throws IOException {
        if (!this.installed || !this.world.isDragSelecting()) {
            return;
        }

        LocalView localView = this.game.getInterfaceLink().getLocalView();
        int left = localView.getScreenX();
        int top = localView.getScreenY();

        this.world.handleLeftRelease(mapX, mapY, false, false, false, false,
                                     left, top,
                                     left + this.viewport.getViewTileWidth(this.camera.getZoom()),
                                     top + this.viewport.getViewTileHeight(this.camera.getZoom()));
    }

    @NotNull ScrGameView getView() {
        return this.view;
    }

    @NotNull ConsoleModel getModel() {
        return this.model;
    }

    @NotNull WorldInterface getWorld() {
        return this.world;
    }

    @NotNull GameViewport getViewport() {
        return this.viewport;
    }

    @NotNull ClientCamera getCamera() {
        return this.camera;
    }
}
