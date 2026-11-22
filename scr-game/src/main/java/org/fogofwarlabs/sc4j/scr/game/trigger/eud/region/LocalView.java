package org.fogofwarlabs.sc4j.scr.game.trigger.eud.region;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.GameStructureView;

/**
 * Holds local screen and mouse positions, camera movement history, drawn tile coordinates and alert positions.
 * Direct {@link ClassicViewRegion} writes store the corresponding DWORD or WORD and set the written mark, which
 * {@link #takeWritten()} returns and clears. The interface applies current screen and mouse positions when the mark is set.
 * {@link ClassicAddressSpace} ignores screen-position and drawn-tile writes while admitting mouse writes.
 * Region reads and direct writes require a supplied, start-placed ({@link #placeStartScreen}) or restored view. Alert access has a
 * separate supplied-state check.
 */
public final class LocalView {
    /**
     * The classic address of the moved screen X (the screen X of the last camera move).
     */
    public static final int PREVIOUS_SCREEN_X_ADDRESS = 0x628448;
    /**
     * The classic address of the moved screen Y.
     */
    public static final int PREVIOUS_SCREEN_Y_ADDRESS = 0x628470;
    /**
     * The classic address of the screen X.
     */
    public static final int SCREEN_X_ADDRESS = 0x62848C;
    /**
     * The classic address of the screen Y.
     */
    public static final int SCREEN_Y_ADDRESS = 0x6284A8;
    /**
     * The classic address of the mouse position (X, then Y).
     */
    public static final int MOUSE_ADDRESS = 0x6CDDC4;
    /**
     * The classic address of the drawn screen tile X WORD (address-space writes are ignored): the screen drawing steps it to the game context
     * tile, drawing each tile column on the way; the game start sets it with the context tile.
     */
    public static final int DRAWN_TILE_X_ADDRESS = 0x628498;
    /**
     * The classic address of the drawn screen tile Y WORD (address-space writes are ignored).
     */
    public static final int DRAWN_TILE_Y_ADDRESS = 0x6284AC;
    /**
     * The classic address of the screen tile WORDs in the game-data block.
     */
    static final int SCREEN_TILE_ADDRESS = ClassicAddressSpace.GAME_DATA_ADDRESS + GameStructureView.SCREEN_TILES_OFFSET;

    private static final int MOUSE_Y_ADDRESS = MOUSE_ADDRESS + Integer.BYTES;
    private static final int WORD_MASK = 0xFFFF;
    private static final int SCREEN_CENTER_DIVISOR = 2;
    /**
     * The share of the game screen height above the console, the play view whose middle row a centered screen shows.
     */
    private static final float PLAY_VIEW_FACTOR = 0.798f;


    /**
     * Computes the rows of the play view above the console: the game screen height times {@code 0.798}, truncated
     * ({@code 383} for the native {@code 480} rows).
     *
     * @param screenHeight the game screen height in pixels
     * @return the play view rows
     */
    public static int calculatePlayViewRows(int screenHeight) {
        return (int) (screenHeight * PLAY_VIEW_FACTOR);
    }

    /**
     * Clamps a camera move on one axis to {@code 0..maximum}: a negative position becomes {@code 0}, one at or past the
     * maximum (unsigned) the maximum.
     *
     * @param position the screen position in pixels
     * @param maximum  the maximum screen position of the axis
     * @return the clamped screen position
     */
    private static int clampScreenPosition(int position, int maximum) {
        return position < 0 ? 0 : Integer.compareUnsigned(position, maximum) >= 0 ? maximum : position;
    }

    /**
     * Clamps a scroll target on one axis to the map: a negative position becomes {@code 0}; otherwise a screen that would
     * reach the map end stops one pixel before the last full screen ({@code mapSize - screenSize - 1}).
     *
     * @param position   the scroll target in pixels
     * @param screenSize the game screen size of the axis in pixels
     * @param mapSize    the map size of the axis in pixels
     * @return the clamped scroll target
     */
    private static int clampScrollTarget(int position, int screenSize, int mapSize) {
        if (position < 0) {
            return 0;
        }

        return position + screenSize >= mapSize ? mapSize - screenSize - 1 : position;
    }


    private boolean known;
    private boolean written;

    private int screenX;
    private int screenY;
    /**
     * The moved screen position: the screen position of the last camera move.
     */
    private int movedScreenX;
    private int movedScreenY;
    /**
     * The screen position the interface supplied last ({@link #setView}): a different one is an interface camera move.
     */
    private int suppliedScreenX;
    private int suppliedScreenY;

    private int mouseX;
    private int mouseY;

    /**
     * The game context screen tile WORDs that the camera move stores.
     */
    private int screenTileX;
    private int screenTileY;

    /**
     * The drawn screen tile WORDs: the context tile of the last drawn frame.
     */
    private int drawnTileX;
    private int drawnTileY;

    /**
     * The game screen width and height and the largest screen position, which the camera move clamps to; local display
     * state the interface supplies ({@link #setScreenGeometry}).
     */
    private boolean geometryKnown;
    private int screenWidth;
    private int screenHeight;
    private int maximumScreenX;
    private int maximumScreenY;

    private boolean alertKnown;
    private int alertScreenX;
    private int alertScreenY;


    /**
     * Creates a {@link LocalView} instance whose view, display geometry and alert screen position are unknown.
     */
    public LocalView() {
        this.known   = false;
        this.written = false;

        this.screenX         = 0;
        this.screenY         = 0;
        this.movedScreenX    = 0;
        this.movedScreenY    = 0;
        this.suppliedScreenX = 0;
        this.suppliedScreenY = 0;

        this.mouseX = 0;
        this.mouseY = 0;

        this.screenTileX = 0;
        this.screenTileY = 0;

        this.drawnTileX = 0;
        this.drawnTileY = 0;

        this.geometryKnown  = false;
        this.screenWidth    = 0;
        this.screenHeight   = 0;
        this.maximumScreenX = 0;
        this.maximumScreenY = 0;

        this.alertKnown   = false;
        this.alertScreenX = 0;
        this.alertScreenY = 0;
    }


    /**
     * @return {@code true} if the view was supplied, start-placed or restored, {@code false} otherwise
     */
    public boolean isKnown() {
        return this.known;
    }

    /**
     * @return the screen X
     */
    public int getScreenX() {
        return this.screenX;
    }

    /**
     * @return the screen Y
     */
    public int getScreenY() {
        return this.screenY;
    }

    /**
     * @return the mouse X
     */
    public int getMouseX() {
        return this.mouseX;
    }

    /**
     * @return the mouse Y
     */
    public int getMouseY() {
        return this.mouseY;
    }

    /**
     * @return {@code true} if the alert screen position is known, {@code false} otherwise
     */
    public boolean isAlertKnown() {
        return this.alertKnown;
    }

    /**
     * @return the alert screen X, which an EUD write may have changed
     * @throws UnsupportedOperationException if the alert screen position is unknown
     */
    public int getAlertScreenX() {
        this.requireAlertKnown();

        return this.alertScreenX;
    }

    /**
     * @return the alert screen Y, which an EUD write may have changed
     * @throws UnsupportedOperationException if the alert screen position is unknown
     */
    public int getAlertScreenY() {
        this.requireAlertKnown();

        return this.alertScreenY;
    }

    /**
     * Sets the alert screen position and marks it as known.
     *
     * @param alertScreenX the alert screen X
     * @param alertScreenY the alert screen Y
     */
    public void setAlertScreen(int alertScreenX, int alertScreenY) {
        this.alertKnown   = true;
        this.alertScreenX = alertScreenX;
        this.alertScreenY = alertScreenY;
    }

    /**
     * Sets the view of the current interface frame. A screen position that differs from the last supplied one is a
     * camera move of the interface: it stores the screen tile of each moved axis in the game context and the moved screen
     * position; an EUD write of the moved position stays until the next move. The first view also starts the alert screen
     * position if that position is still unknown.
     *
     * @param screenX the screen X in pixels
     * @param screenY the screen Y in pixels
     * @param mouseX  the mouse X
     * @param mouseY  the mouse Y
     */
    public void setView(int screenX, int screenY, int mouseX, int mouseY) {
        if (!this.known || screenX != this.screenX) {
            this.screenTileX = screenX >> TerrainTileset.TILE_SHIFT & WORD_MASK;
        }

        if (!this.known || screenY != this.screenY) {
            this.screenTileY = screenY >> TerrainTileset.TILE_SHIFT & WORD_MASK;
        }

        if (!this.known || screenX != this.suppliedScreenX || screenY != this.suppliedScreenY) {
            this.movedScreenX = screenX;
            this.movedScreenY = screenY;
        }

        this.suppliedScreenX = screenX;
        this.suppliedScreenY = screenY;

        // The interface start copies the screen position into the alert position before the first trigger pass;
        // afterwards only alerts and EUD writes change it.
        if (!this.alertKnown) {
            this.setAlertScreen(screenX, screenY);
        }

        // The frame's drawing steps the drawn tile to the context tile, including one an EUD write stored.
        this.drawnTileX = this.screenTileX;
        this.drawnTileY = this.screenTileY;
        this.known      = true;
        this.screenX    = screenX;
        this.screenY    = screenY;
        this.mouseX     = mouseX;
        this.mouseY     = mouseY;
    }

    /**
     * Sets the local display geometry the camera move, the centering and the Center View scroll target read.
     *
     * @param screenWidth    the game screen width in pixels
     * @param screenHeight   the game screen height in pixels
     * @param maximumScreenX the largest screen X (the map width less the screen width)
     * @param maximumScreenY the largest screen Y
     */
    public void setScreenGeometry(int screenWidth, int screenHeight, int maximumScreenX, int maximumScreenY) {
        this.geometryKnown  = true;
        this.screenWidth    = screenWidth;
        this.screenHeight   = screenHeight;
        this.maximumScreenX = maximumScreenX;
        this.maximumScreenY = maximumScreenY;
    }

    /**
     * Computes the screen X that centers a point: the point less half the game screen width (a signed half).
     *
     * @param centerX the point X in pixels
     * @return the screen X before the clamp of the camera move
     */
    public int calculateCenteredScreenX(int centerX) {
        return centerX - this.screenWidth / SCREEN_CENTER_DIVISOR;
    }

    /**
     * Computes the screen Y that centers a point: the point less half the play view rows ({@link #calculatePlayViewRows}),
     * not half the screen height.
     *
     * @param centerY the point Y in pixels
     * @return the screen Y before the clamp of the camera move
     */
    public int calculateCenteredScreenY(int centerY) {
        return centerY - calculatePlayViewRows(this.screenHeight) / SCREEN_CENTER_DIVISOR;
    }

    /**
     * Computes the screen X the camera scroll of the single-player Center View moves to: the centered screen X
     * ({@link #calculateCenteredScreenX}), {@code 0} if it is negative, and one pixel less than the map width less the
     * game screen width if the screen would reach the map's right edge.
     *
     * @param centerX  the point X in pixels
     * @param mapWidth the map width in pixels
     * @return the scroll target X before the scroll's eight-pixel alignment
     */
    public int calculateScrollTargetX(int centerX, int mapWidth) {
        return clampScrollTarget(this.calculateCenteredScreenX(centerX), this.screenWidth, mapWidth);
    }

    /**
     * Computes the screen Y the camera scroll of the single-player Center View moves to: the point less half the whole
     * game screen height (not the play view rows), {@code 0} if it is negative, and one pixel less than the map height
     * less the game screen height if the screen would reach the map's bottom edge.
     *
     * @param centerY   the point Y in pixels
     * @param mapHeight the map height in pixels
     * @return the scroll target Y before the scroll's eight-pixel alignment
     */
    public int calculateScrollTargetY(int centerY, int mapHeight) {
        return clampScrollTarget(centerY - this.screenHeight / SCREEN_CENTER_DIVISOR, this.screenHeight, mapHeight);
    }

    /**
     * Places the screen as the game start does, in the supplied display geometry ({@link #setScreenGeometry}): moves the
     * camera to the position clamped to {@code 0..maximum} on each axis (a negative position becomes {@code 0}, one at or
     * past the maximum the maximum), and starts the view there as the first supplied view does ({@link #setView}): the
     * screen tiles, the moved screen position and the alert screen position follow it, and the mouse stays where it is. A
     * view supplied or restored before is replaced.
     *
     * @param screenX the screen X in pixels before the clamp
     * @param screenY the screen Y in pixels before the clamp
     * @throws IllegalStateException if no display geometry was supplied
     */
    public void placeStartScreen(int screenX, int screenY) {
        if (!this.geometryKnown) {
            throw Exceptions.newIllegalStateException("The start screen is placed after the display geometry is supplied.");
        }

        int startScreenX = clampScreenPosition(screenX, this.maximumScreenX);
        int startScreenY = clampScreenPosition(screenY, this.maximumScreenY);

        this.known      = false;
        this.alertKnown = false;

        this.setView(startScreenX, startScreenY, this.mouseX, this.mouseY);
    }

    /**
     * Stores one coordinate of the alert screen position, as an EUD write of the game context does.
     *
     * @param vertical {@code true} for the Y, {@code false} for the X
     * @param value    the coordinate
     * @throws UnsupportedOperationException if the alert screen position is unknown
     */
    public void writeAlertScreen(boolean vertical, int value) {
        this.requireAlertKnown();

        if (vertical) {
            this.alertScreenY = value;
        } else {
            this.alertScreenX = value;
        }
    }

    /**
     * Centers the screen on a point as the multiplayer Center View does: the screen position is the point less half the
     * game screen width and half the play view rows ({@link #calculateCenteredScreenX}, {@link #calculateCenteredScreenY}),
     * and the camera moves there at once ({@link #moveScreen}).
     *
     * @param centerX the point X in pixels
     * @param centerY the point Y in pixels
     * @return {@code true} if the view is known and display geometry was supplied, {@code false} otherwise
     */
    public boolean centerScreen(int centerX, int centerY) {
        if (!this.known || !this.geometryKnown) {
            return false;
        }

        return this.moveScreen(this.calculateCenteredScreenX(centerX), this.calculateCenteredScreenY(centerY));
    }

    /**
     * Moves the camera at once to a screen position, as the camera move of the multiplayer Center View does: each axis is clamped to
     * {@code 0..maximum} (a negative position becomes {@code 0}, one at or past the maximum the maximum), the screen tile WORDs and the
     * screen position are stored, and the moved screen position follows when the position changed. The interface applies the move
     * ({@link #takeWritten()}). Not modeled: the observer and replay gates of the camera move.
     *
     * @param screenX the screen X in pixels before the clamp
     * @param screenY the screen Y in pixels before the clamp
     * @return {@code true} if the view is known and display geometry was supplied, {@code false} otherwise
     */
    public boolean moveScreen(int screenX, int screenY) {
        if (!this.known || !this.geometryKnown) {
            return false;
        }

        int movedX = clampScreenPosition(screenX, this.maximumScreenX);
        int movedY = clampScreenPosition(screenY, this.maximumScreenY);

        this.screenTileX = movedX >> TerrainTileset.TILE_SHIFT & WORD_MASK;
        this.screenTileY = movedY >> TerrainTileset.TILE_SHIFT & WORD_MASK;
        this.screenX     = movedX;
        this.screenY     = movedY;

        if (movedX != this.movedScreenX || movedY != this.movedScreenY) {
            this.movedScreenX = movedX;
            this.movedScreenY = movedY;
        }

        this.written = true;

        return true;
    }

    /**
     * Restores the screen position a saved game keeps: the reader moves the camera there, clamped to the display geometry
     * once it is known; the position is then known, and the interface applies the move ({@link #takeWritten()}).
     *
     * @param screenX the saved screen X in pixels
     * @param screenY the saved screen Y in pixels
     */
    public void restoreSavedScreen(int screenX, int screenY) {
        int restoredScreenX = screenX;
        int restoredScreenY = screenY;

        if (this.geometryKnown) {
            restoredScreenX = clampScreenPosition(restoredScreenX, this.maximumScreenX);
            restoredScreenY = clampScreenPosition(restoredScreenY, this.maximumScreenY);
        }

        this.screenTileX  = restoredScreenX >> TerrainTileset.TILE_SHIFT & WORD_MASK;
        this.screenTileY  = restoredScreenY >> TerrainTileset.TILE_SHIFT & WORD_MASK;
        this.screenX      = restoredScreenX;
        this.screenY      = restoredScreenY;
        this.movedScreenX = restoredScreenX;
        this.movedScreenY = restoredScreenY;
        this.written      = true;
        this.known        = true;
    }

    /**
     * Takes and clears the pending written mark. Accepted local view region stores and screen-position applications set
     * the mark even if they store the same values.
     *
     * @return {@code true} if the written mark was pending, {@code false} otherwise
     */
    public boolean takeWritten() {
        boolean result = this.written;

        this.written = false;

        return result;
    }

    /**
     * Reads the screen tile WORDs of the game context.
     *
     * @return the game context DWORD of the screen tiles: the screen tile X WORD and the screen tile Y WORD above it
     * @throws UnsupportedOperationException if the local view is unknown
     */
    public int readScreenTiles() {
        this.requireKnown(SCREEN_TILE_ADDRESS);

        return this.screenTileX | this.screenTileY << Short.SIZE;
    }

    /**
     * Stores the screen tile WORDs of the game context; the next camera move replaces them.
     *
     * @param value the game context DWORD of the screen tiles
     * @throws UnsupportedOperationException if the local view is unknown
     */
    public void writeScreenTiles(int value) {
        this.requireKnown(SCREEN_TILE_ADDRESS);

        this.screenTileX = value & WORD_MASK;
        this.screenTileY = value >>> Short.SIZE;
    }


    int read(int address) {
        this.requireKnown(address);

        return switch (address) {
            case PREVIOUS_SCREEN_X_ADDRESS -> this.movedScreenX;
            case PREVIOUS_SCREEN_Y_ADDRESS -> this.movedScreenY;
            case SCREEN_X_ADDRESS -> this.screenX;
            case SCREEN_Y_ADDRESS -> this.screenY;
            case MOUSE_ADDRESS -> this.mouseX;
            case MOUSE_Y_ADDRESS -> this.mouseY;
            case DRAWN_TILE_X_ADDRESS -> this.drawnTileX;
            case DRAWN_TILE_Y_ADDRESS -> this.drawnTileY;
            default -> throw Exceptions.newUnsupportedOperationException("Unsupported local view address: {0}.", String.format("0x%08X", address));
        };
    }

    void write(int address, int value) {
        this.requireKnown(address);

        switch (address) {
            case PREVIOUS_SCREEN_X_ADDRESS -> this.movedScreenX = value;
            case PREVIOUS_SCREEN_Y_ADDRESS -> this.movedScreenY = value;
            case SCREEN_X_ADDRESS -> this.screenX = value;
            case SCREEN_Y_ADDRESS -> this.screenY = value;
            case MOUSE_ADDRESS -> this.mouseX = value;
            case MOUSE_Y_ADDRESS -> this.mouseY = value;
            case DRAWN_TILE_X_ADDRESS -> this.drawnTileX = value & WORD_MASK;
            case DRAWN_TILE_Y_ADDRESS -> this.drawnTileY = value & WORD_MASK;
            default -> throw Exceptions.newUnsupportedOperationException("Unsupported local view address: {0}.", String.format("0x%08X", address));
        }

        this.written = true;
    }


    private void requireAlertKnown() {
        if (!this.alertKnown) {
            throw Exceptions.newUnsupportedOperationException(
                    "EUD address 0x0057FD34 is the alert screen position of the game context (+0xC44), a local input that was not supplied."
            );
        }
    }

    private void requireKnown(int address) {
        if (!this.known) {
            throw Exceptions.newUnsupportedOperationException(
                    "EUD address {0} is the local view (screen or mouse position), a local input that was not supplied.",
                    String.format("0x%08X", address)
            );
        }
    }
}
