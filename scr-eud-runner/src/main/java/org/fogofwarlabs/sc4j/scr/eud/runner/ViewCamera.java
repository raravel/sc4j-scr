package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.world.CameraTargetScroll;
import org.fogofwarlabs.sc4j.scr.client.world.ClientCamera;
import org.fogofwarlabs.sc4j.scr.client.world.GameScreenCamera;
import org.fogofwarlabs.sc4j.scr.client.world.GameViewport;
import org.fogofwarlabs.sc4j.scr.client.world.ScrScrollStepTable;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.presentation.StartScreenUtils;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerEvents;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The local player's camera over the classic 640x480 game screen, kept the way the client keeps its own (scr-client {@code Main}): before every
 * frame the camera is supplied to the game ({@link LocalView#setView}, {@link LocalView#setScreenGeometry}); after it, a screen position the game
 * itself stored (the multiplayer Center View, {@link LocalView#centerScreen}) moves the camera, and the trigger presentation events are presented:
 * a single-player Center View starts the client's smooth scroll ({@link CameraTargetScroll}) whose arrival ends the trigger owner's wait
 * ({@link TriggerContext#completeCenterView}), and the scroll runs on the interface ticks between frames. The scroll keeps holding the camera
 * on its target until the trigger finishes it ({@link TriggerEvents#CENTER_VIEW_FINISH}). Shots draw this camera and a script's pointer rests on
 * the screen relative to it; a script's {@code click} or {@code view} takes the camera over ({@link #centerOn}).
 * <p>
 * EUD writes to the screen position (0x62848C, 0x6284A8) are ignored by the game's address space, so they never move the camera.
 */
final class ViewCamera {
    private static final int TILE_SIZE = 32;
    /**
     * The client's Center View scroll: its target is aligned to 8 pixels and it moves at scroll speed row 6 (scr-client {@code Main}).
     */
    private static final int CENTER_VIEW_ALIGNMENT = 8;
    private static final int CENTER_VIEW_SPEED = 6;
    /**
     * The {@link TriggerEvents#CENTER_VIEW} flag of a scroll whose arrival ends the trigger wait.
     */
    private static final int SMOOTH_CENTER_VIEW_FLAG = 1;
    /**
     * The client's draws per second, each running the interface loop's clock test; a guess at a 60 Hz display.
     */
    private static final int DRAWS_PER_SECOND = 60;
    private static final float CENTER_DIVISOR = 2f;

    private final @NotNull Game game;
    private final int mapWidth;
    private final int mapHeight;
    private final @NotNull GameViewport viewport;
    private final @NotNull ClientCamera camera;
    private final @NotNull CameraTargetScroll centerViewScroll;

    private int draws;
    /**
     * What ends the trigger owner's wait when the running Center View scroll arrives, or {@code null} once it ran.
     */
    private @Nullable Runnable arrival;

    /**
     * Creates the camera at the top-left corner of the map ({@link #placeStartScreen} moves it to the start location).
     *
     * @param game the game
     */
    ViewCamera(@NotNull Game game) {
        this.game      = game;
        this.mapWidth  = game.getMap().getWidth() * TILE_SIZE;
        this.mapHeight = game.getMap().getHeight() * TILE_SIZE;

        this.viewport = new GameViewport();
        this.viewport.setSurface(UiSurface.of(LiveMapSession.VIEWPORT_WIDTH, LiveMapSession.VIEWPORT_HEIGHT, DisplayScale.ONE));
        this.viewport.setInterface(false, true, false);
        this.camera = new ClientCamera(this.mapWidth, this.mapHeight);
        this.camera.setViewport(this.viewport);
        this.camera.setNativeZoom();

        this.centerViewScroll = new CameraTargetScroll(CENTER_VIEW_ALIGNMENT, CENTER_VIEW_SPEED,
                                                       new ScrScrollStepTable(() -> game.getInterfaceLink().getLocalInterface()));
        this.draws            = 0;

        this.moveTo(0, 0);
    }

    /**
     * Starts the camera on the local player's start location as the client's live start does ({@link StartScreenUtils#placeStartScreen}:
     * centred in the screen width and the play view, rounded down to whole tiles, clamped to the map). Without a start location the camera stays
     * at the top-left corner.
     *
     * @param player the local player
     */
    void placeStartScreen(int player) {
        if (StartScreenUtils.placeStartScreen(this.game, player, LiveMapSession.VIEWPORT_WIDTH, LiveMapSession.VIEWPORT_HEIGHT)) {
            LocalView localView = this.game.getInterfaceLink().getLocalView();

            this.moveTo(localView.getScreenX(), localView.getScreenY());
        }
    }

    /**
     * @return the map x of the game screen's left edge, as the client supplies it
     */
    int getScreenX() {
        return Math.round(this.camera.screenToWorldX(this.viewport.getGameLeft()));
    }

    /**
     * @return the map y of the game screen's top edge, as the client supplies it
     */
    int getScreenY() {
        return Math.round(this.camera.getY());
    }

    /**
     * Moves the game screen's top-left corner to a map position; the camera keeps itself inside the map.
     *
     * @param screenX the map x of the left edge
     * @param screenY the map y of the top edge
     */
    void moveTo(int screenX, int screenY) {
        GameScreenCamera.ofPlayView(this.camera, this.viewport, this.mapWidth, this.mapHeight).moveTo(screenX, screenY);
    }

    /**
     * Centres the 640x480 game screen on a map pixel, clamped so that the whole screen stays inside the map (a map narrower than the screen keeps
     * it at {@code 0}).
     * <p>
     * This is the script's own camera move, which a player cannot make while a Center View scroll holds the camera (the client then ignores the
     * player's scrolling and the minimap until the trigger finishes the scroll). The script takes the camera over instead: a scroll still on its
     * way counts as arrived (the trigger owner's wait ends, as at its arrival) and the scroll stops holding the camera.
     *
     * @param x the map pixel x
     * @param y the map pixel y
     */
    void centerOn(int x, int y) {
        if (this.centerViewScroll.isActive()) {
            this.arrive();
            this.centerViewScroll.finish();
        }

        this.moveTo(Math.clamp(x - LiveMapSession.VIEWPORT_WIDTH / 2, 0, Math.max(0, this.mapWidth - LiveMapSession.VIEWPORT_WIDTH)),
                    Math.clamp(y - LiveMapSession.VIEWPORT_HEIGHT / 2, 0, Math.max(0, this.mapHeight - LiveMapSession.VIEWPORT_HEIGHT)));
    }

    /**
     * Supplies the camera and the pointer to the game before a frame, as the client does: the screen position, the pointer on the screen and the
     * game screen geometry the Center View reads.
     *
     * @param mouseX the pointer column on the screen
     * @param mouseY the pointer row on the screen
     */
    void supply(int mouseX, int mouseY) {
        LocalView localView = this.game.getInterfaceLink().getLocalView();

        localView.setView(this.getScreenX(), this.getScreenY(), mouseX, mouseY);
        localView.setScreenGeometry(this.viewport.getViewTileWidth(this.camera.getZoom()), this.viewport.getViewTileHeight(this.camera.getZoom()),
                                    Math.round(this.camera.getMaximumScreenX()), Math.round(this.camera.getMaximumScreenY()));
    }

    /**
     * Follows a finished frame as the client does: a screen position the game stored moves the camera, the frame's trigger presentation events
     * are presented (Center View), and the interface ticks of the frame's time run the Center View scroll.
     */
    void followFrame() {
        LocalView localView = this.game.getInterfaceLink().getLocalView();

        if (localView.takeWritten()) {
            this.moveTo(localView.getScreenX(), localView.getScreenY());
        }

        TriggerContext context = this.game.getTriggerContext();

        if (context != null) {
            context.getEvents().present((kind, first, second, third, fourth, path) -> this.present(context, kind, first, second, third));
        }

        this.runInterfaceTicks(this.game.getCurrentFrame() * GameSpeed.FASTEST_DURATION);
    }

    private void present(@NotNull TriggerContext context, int kind, int first, int second, int third) {
        switch (kind) {
            case TriggerEvents.CENTER_VIEW -> {
                if (third == SMOOTH_CENTER_VIEW_FLAG) {
                    LocalView localView = this.game.getInterfaceLink().getLocalView();

                    this.arrival = context::completeCenterView;
                    this.centerViewScroll.start(localView.calculateScrollTargetX(first, this.mapWidth),
                                                localView.calculateScrollTargetY(second, this.mapHeight),
                                                this::arrive);
                } else {
                    float offset = ((this.viewport.getGameLeft() + this.viewport.getGameRight()) / CENTER_DIVISOR -
                                    LiveMapSession.VIEWPORT_WIDTH / CENTER_DIVISOR) / this.camera.getZoom();

                    this.camera.centerOn(first - offset, second);
                }
            }
            case TriggerEvents.CENTER_VIEW_FINISH -> this.centerViewScroll.finish();
            default -> {
                // Text, sounds, pings and portraits do not move the camera; their batch is acknowledged with the rest, as the client does.
            }
        }
    }

    /**
     * Ends the trigger owner's wait of the running Center View once: the scroll's arrival, or the script taking the camera over.
     */
    private void arrive() {
        Runnable current = this.arrival;

        this.arrival = null;

        if (current != null) {
            current.run();
        }
    }

    /**
     * Runs the client's draws up to a host time: each draw runs the interface loop's clock test, and a due tick moves a running Center View
     * scroll by one step.
     *
     * @param hostMilliseconds the host clock of the game's current frame
     */
    private void runInterfaceTicks(int hostMilliseconds) {
        while (true) {
            int draw = (int) ((long) (this.draws + 1) * 1000 / DRAWS_PER_SECOND);

            if (draw > hostMilliseconds) {
                return;
            }

            this.draws++;

            if (this.game.getInterfaceLink().getLocalInterface().advanceInterfaceTick(draw) && this.centerViewScroll.isActive()) {
                this.centerViewScroll.tick(GameScreenCamera.ofWholeView(this.camera, this.viewport, this.mapWidth, this.mapHeight));
            }
        }
    }

    @NotNull GameViewport getViewport() {
        return this.viewport;
    }

    @NotNull ClientCamera getCamera() {
        return this.camera;
    }
}
