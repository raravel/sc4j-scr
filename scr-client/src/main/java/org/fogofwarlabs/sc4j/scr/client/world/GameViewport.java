package org.fogofwarlabs.sc4j.scr.client.world;

import org.fogofwarlabs.sc4j.engine.client.display.LetterboxFit;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.host.PointerClip;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalView;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceTransform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Owns the client's window, interface and game-view geometry. Window and console changes recompute the interface placement, game columns and play
 * rows used by camera limits, edge scrolling and map-view conversion.
 * <p>
 * The window is the frame's interface surface ({@link UiSurface}); screen coordinates are its framebuffer pixels. The interface ({@code 640} or
 * wider by {@code 480}) is fitted into the surface's logical area ({@link LetterboxFit}), centred across and standing on the bottom edge, and
 * mapped to framebuffer pixels; pointer positions in framebuffer pixels map back to interface pixels through the same fit.
 * <p>
 * As an {@link InterfaceTransform} it places the game screen's interface pixels from the game view's top edge: the interface's columns and scale,
 * with row {@code 0} at the view's top (the surface coordinates of {@link #toSurfaceX} and {@link #toSurfaceY}). The console's top-anchored elements
 * and the status overlay are drawn with it. In a window narrower than {@code 4:3} the view extends above the interface; elements anchored to the
 * view's top keep their offsets from that edge.
 */
public final class GameViewport implements InterfaceTransform {
    /**
     * The interface height and the game screen height in game pixels.
     */
    public static final int INTERFACE_HEIGHT = InterfaceTransform.INTERFACE_HEIGHT;
    /**
     * The play view height with the game console, in interface rows ({@link LocalView#calculatePlayViewRows} of the interface height).
     */
    public static final int PLAY_VIEW_ROWS = LocalView.calculatePlayViewRows(INTERFACE_HEIGHT);
    /**
     * The smallest camera scale in screen pixels per map pixel.
     */
    public static final float MINIMUM_ZOOM = 1.0f / 32.0f;
    /**
     * The largest camera scale in screen pixels per map pixel.
     */
    public static final float MAXIMUM_ZOOM = 4.0f;

    /**
     * The index of the left edge in the result of {@link #getMapView}.
     */
    public static final int MAP_VIEW_LEFT_INDEX = 0;
    /**
     * The index of the top edge in the result of {@link #getMapView}.
     */
    public static final int MAP_VIEW_TOP_INDEX = 1;
    /**
     * The index of the width in the result of {@link #getMapView}.
     */
    public static final int MAP_VIEW_WIDTH_INDEX = 2;
    /**
     * The index of the height in the result of {@link #getMapView}.
     */
    public static final int MAP_VIEW_HEIGHT_INDEX = 3;

    private static final float EXTENSION_ROUNDING_THRESHOLD = 0.5f;
    /**
     * The interface rows of the upper half, counted from the game view's top edge in the edge tests.
     */
    private static final float UPPER_HALF_ROWS = INTERFACE_HEIGHT / 2f;

    /**
     * Places an interface of a width on an interface surface: fitted into the surface's logical area, centred across and standing on its bottom
     * edge, in framebuffer pixels.
     *
     * @param interfaceWidth the interface width
     * @param surface        the interface surface
     * @return the interface's scale and origin in framebuffer pixels
     */
    public static @NotNull LetterboxFit placeInterface(float interfaceWidth, @NotNull UiSurface surface) {
        return LetterboxFit.of(surface, interfaceWidth, INTERFACE_HEIGHT, LetterboxFit.CENTER, LetterboxFit.END).toFramebuffer(surface);
    }

    /**
     * Returns the rows by which a rectangle extends above an interface placed in it ({@link #placeInterface}); fractions of a pixel from rounding the
     * fitted scale at the native aspects count as none.
     *
     * @param interfaceTop the interface's screen top
     * @param top          the rectangle's top
     * @return the extension in screen rows
     */
    public static float computeViewExtension(float interfaceTop, float top) {
        float extension = interfaceTop - top;

        return extension < EXTENSION_ROUNDING_THRESHOLD ? 0 : extension;
    }

    private @Nullable UiSurface surface;
    private int windowWidth;
    private int windowHeight;

    private boolean widescreen;
    private boolean consoleShown;
    private boolean fullHeightView;

    private int interfaceWidth;
    private float scale;
    private float interfaceLeft;
    private float interfaceTop;
    private float extension;

    private float gameLeft;
    private float gameRight;
    private float playRows;
    private @NotNull PointerClip screenClip;
    private @NotNull PointerClip playClip;

    /**
     * Creates a {@link GameViewport} instance with the original interface width and unit scale.
     */
    public GameViewport() {
        this.surface      = null;
        this.windowWidth  = 0;
        this.windowHeight = 0;

        this.widescreen     = false;
        this.consoleShown   = false;
        this.fullHeightView = false;

        this.interfaceWidth = InterfaceTransform.ORIGINAL_WIDTH;
        this.scale          = 1;
        this.interfaceLeft  = 0;
        this.interfaceTop   = 0;
        this.extension      = 0;

        this.gameLeft   = 0;
        this.gameRight  = 0;
        this.playRows   = 0;
        this.screenClip = PointerClip.of(0, 0, 0, 0);
        this.playClip   = PointerClip.of(0, 0, 0, 0);
    }

    /**
     * @return the screen y of the interface's top: the console's interface stands on the window's bottom edge
     */
    public float getInterfaceTop() {
        return this.interfaceTop;
    }

    /**
     * @return the window's interface surface, or {@code null} before {@link #setSurface}
     */
    public @Nullable UiSurface getSurface() {
        return this.surface;
    }

    /**
     * @return the window width in screen pixels
     */
    public int getWindowWidth() {
        return this.windowWidth;
    }

    /**
     * @return the window height in screen pixels
     */
    public int getWindowHeight() {
        return this.windowHeight;
    }

    /**
     * @return the interface width {@code W}
     */
    public int getInterfaceWidth() {
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
     * @return the first screen column of the game screen
     */
    public float getGameLeft() {
        return this.gameLeft;
    }

    /**
     * @return the screen column after the game screen
     */
    public float getGameRight() {
        return this.gameRight;
    }

    /**
     * @return the game screen width in screen pixels
     */
    public float getGameWidth() {
        return this.gameRight - this.gameLeft;
    }

    /**
     * Returns the game screen as the window's pointer clip: its columns and the whole window height in screen pixels. The mouse confinement keeps
     * the cursor in it (the native {@code ClipCursor}), so the bars beside a pillarboxed frame are outside; it follows every window size, graphics
     * mode and aspect setting.
     *
     * @return the game screen's clip, recomputed with the geometry
     */
    public @NotNull PointerClip getScreenClip() {
        return this.screenClip;
    }

    /**
     * Returns the play view of the game screen as the window's pointer clip: the game screen's columns and the rows above the console's upper band
     * ({@link #getPlayRows}) in screen pixels. An open selection box keeps the pointer in it.
     *
     * @return the play view's clip, recomputed with the geometry
     */
    public @NotNull PointerClip getPlayClip() {
        return this.playClip;
    }

    /**
     * @return the play view's height in screen rows, from the window top to the console's upper band (the window height without the console)
     */
    public float getPlayRows() {
        return this.playRows;
    }

    /**
     * @return the native camera scale: the interface scale, clamped to the camera's range
     */
    public float getNativeZoom() {
        if (this.windowHeight <= 0) {
            return MINIMUM_ZOOM;
        }

        return Math.clamp(this.scale, MINIMUM_ZOOM, MAXIMUM_ZOOM);
    }

    /**
     * Returns the smallest camera scale at which the game screen columns and the whole window height still show only the map; a map smaller than the
     * native view keeps the native scale.
     *
     * @param mapWidth  the map width in pixels
     * @param mapHeight the map height in pixels
     * @return the minimum zoom
     */
    public float getMinimumZoom(int mapWidth, int mapHeight) {
        if (mapWidth <= 0 || mapHeight <= 0 || this.windowWidth <= 0 || this.windowHeight <= 0) {
            return MINIMUM_ZOOM;
        }

        float mapZoom = Math.max(this.getGameWidth() / mapWidth, (float) this.windowHeight / mapHeight);

        return Math.clamp(mapZoom, MINIMUM_ZOOM, this.getNativeZoom());
    }

    /**
     * @param zoom the camera scale
     * @return the smallest camera x: the map's left edge at the game screen's left edge
     */
    public float getMinimumCameraX(float zoom) {
        return 0.0f - this.gameLeft / zoom;
    }

    /**
     * @param zoom     the camera scale
     * @param mapWidth the map width in pixels
     * @return the largest camera x: the map's right edge at the game screen's right edge
     */
    public float getMaximumCameraX(float zoom, int mapWidth) {
        float minimum = this.getMinimumCameraX(zoom);

        return Math.max(minimum, mapWidth - this.gameRight / zoom);
    }

    /**
     * @param zoom      the camera scale
     * @param mapHeight the map height in pixels
     * @return the largest camera y: the map's bottom edge at the play view's bottom
     */
    public float getMaximumCameraY(float zoom, int mapHeight) {
        return Math.max(0, mapHeight - this.playRows / zoom);
    }

    /**
     * @param zoom     the camera scale
     * @param mapWidth the map width in pixels
     * @return the largest map x of the game screen's left edge
     */
    public float getMaximumScreenX(float zoom, int mapWidth) {
        return Math.max(0, mapWidth - this.getGameWidth() / zoom);
    }

    /**
     * @param zoom the camera scale
     * @return the game screen width in map pixels
     */
    public float getViewMapWidth(float zoom) {
        return this.getGameWidth() / zoom;
    }

    /**
     * @param zoom the camera scale
     * @return the game view height (the whole window) in map pixels
     */
    public float getViewMapHeight(float zoom) {
        return this.windowHeight / zoom;
    }

    /**
     * Returns the game view in map pixels for the minimap's view frame:
     * the game screen's left edge, the camera's top, the game screen width and the window height.
     *
     * @param camera the camera
     * @return {@code {left, top, width, height}} in map pixels
     */
    public float @NotNull [] getMapView(@NotNull ClientCamera camera) {
        float zoom = camera.getZoom();

        return new float[]{camera.screenToWorldX(this.gameLeft), camera.getY(), this.getViewMapWidth(zoom), this.getViewMapHeight(zoom)};
    }

    /**
     * @param zoom the camera scale
     * @return the game screen width in whole map pixels
     */
    public int getViewTileWidth(float zoom) {
        return Math.round(this.getViewMapWidth(zoom));
    }

    /**
     * @param zoom the camera scale
     * @return the game view height in whole map pixels
     */
    public int getViewTileHeight(float zoom) {
        return Math.round(this.getViewMapHeight(zoom));
    }

    /**
     * @param zoom the camera scale
     * @return the play view height in whole map pixels
     */
    public int getPlayMapRows(float zoom) {
        return Math.round(this.playRows / zoom);
    }


    /**
     * Sets the window: its interface surface, whose framebuffer pixels are the screen pixels.
     *
     * @param surface the window's interface surface
     */
    public void setSurface(@NotNull UiSurface surface) {
        this.surface      = surface;
        this.windowWidth  = surface.getFramebufferWidth();
        this.windowHeight = surface.getFramebufferHeight();

        this.recompute();
    }

    /**
     * Sets what the interface covers.
     *
     * @param widescreen     whether the interface may be wider than {@code 640} (remastered graphics without the original aspect ratio)
     * @param consoleShown   whether the console is shown, so that the game screen is the interface's columns and the play view ends at the console
     * @param fullHeightView whether the play view is the full {@code 480} rows (the replay console)
     */
    public void setInterface(boolean widescreen, boolean consoleShown, boolean fullHeightView) {
        this.widescreen     = widescreen;
        this.consoleShown   = consoleShown;
        this.fullHeightView = fullHeightView;

        this.recompute();
    }


    /**
     * Converts a screen row for the edge tests (camera edge scroll): rows in the upper half count from the game view's top edge, rows in the lower
     * half from the interface on the bottom edge, so both bands lie at the view's edges. Equal to the interface row at the native aspects.
     *
     * @param screenY the screen y
     * @return the interface row for an edge test
     */
    public float toEdgeInterfaceY(float screenY) {
        float fromBottom = (screenY - this.interfaceTop) / this.scale;

        if (this.extension == 0) {
            return fromBottom;
        }

        float fromTop = screenY / this.scale;

        return fromTop < UPPER_HALF_ROWS ? fromTop : fromBottom;
    }

    /**
     * Converts an interface x to a screen x.
     *
     * @param x an interface x
     * @return the screen x
     */
    @Override
    public float toScreenX(float x) {
        return this.interfaceLeft + x * this.scale;
    }

    /**
     * Converts an interface y to a screen y.
     * <p>
     * The interface y counts from the game view's top edge: the screen y is the row of the interface standing on the bottom edge, moved up by the
     * view extension. The console's top-anchored elements (resource counters, trigger countdown, leaderboard) are placed with it,
     * in this float operation order.
     *
     * @param y an interface y
     * @return the screen y
     */
    @Override
    public float toScreenY(float y) {
        return this.interfaceTop + y * this.scale - this.extension;
    }

    /**
     * Converts a screen column to an interface column.
     *
     * @param screenX the screen x
     * @return the interface column
     */
    public float toInterfaceX(float screenX) {
        return (screenX - this.interfaceLeft) / this.scale;
    }

    /**
     * Converts a screen row to an interface row, the inverse of {@link #toScreenY}: the row counts from the game view's top edge.
     *
     * @param screenY the screen y
     * @return the interface row
     */
    public float toInterfaceY(float screenY) {
        return (screenY - this.interfaceTop + this.extension) / this.scale;
    }

    /**
     * Converts a screen column to interface-scaled game-surface pixels.
     *
     * @param screenX the screen x
     * @return the surface x
     */
    public float toSurfaceX(float screenX) {
        return this.toInterfaceX(screenX);
    }

    /**
     * Converts a screen row to interface-scaled game-surface pixels from the game view's top edge.
     *
     * @param screenY the screen y
     * @return the surface y
     */
    public float toSurfaceY(float screenY) {
        return this.extension == 0 ? (screenY - this.interfaceTop) / this.scale : screenY / this.scale;
    }

    /**
     * Recomputes the fitted interface, game columns and play rows from the retained window and console state; nothing before the window is set.
     */
    private void recompute() {
        UiSurface window = this.surface;

        if (window == null) {
            return;
        }

        // The window's aspect ratio is that of its framebuffer.
        this.interfaceWidth = InterfaceTransform.getInterfaceWidth(this.widescreen, window.getFramebufferWidth(), window.getFramebufferHeight());

        LetterboxFit fit = placeInterface(this.interfaceWidth, window);

        this.scale         = fit.getScale();
        this.interfaceLeft = fit.getLeft();
        this.interfaceTop  = fit.getTop();
        this.extension     = computeViewExtension(this.interfaceTop, 0);

        if (this.consoleShown) {
            this.gameLeft  = Math.max(0, this.interfaceLeft);
            this.gameRight = Math.min(this.windowWidth, this.interfaceLeft + this.interfaceWidth * this.scale);

            int rows = this.fullHeightView ? INTERFACE_HEIGHT : PLAY_VIEW_ROWS;

            this.playRows = this.extension == 0 ? rows * this.windowHeight / (float) INTERFACE_HEIGHT : this.interfaceTop + rows * this.scale;
        } else {
            this.gameLeft  = 0;
            this.gameRight = this.windowWidth;
            this.playRows  = this.windowHeight;
        }

        // A window without a size (minimized) gives no finite game screen; the clip then holds nothing.
        boolean finite = Float.isFinite(this.gameLeft) && Float.isFinite(this.gameRight);

        this.screenClip = finite ? PointerClip.of(this.gameLeft, 0, Math.max(this.gameLeft, this.gameRight), Math.max(0, this.windowHeight))
                                 : PointerClip.of(0, 0, 0, 0);
        this.playClip   = finite ? PointerClip.of(this.gameLeft, 0, Math.max(this.gameLeft, this.gameRight), Math.max(0, this.playRows))
                                 : PointerClip.of(0, 0, 0, 0);
    }
}
