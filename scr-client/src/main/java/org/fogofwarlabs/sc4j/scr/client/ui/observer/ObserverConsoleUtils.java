package org.fogofwarlabs.sc4j.scr.client.ui.observer;

import org.fogofwarlabs.sc4j.scr.client.ui.console.ConsoleLayout;
import org.fogofwarlabs.sc4j.scr.client.ui.console.DialogLayout;
import org.jetbrains.annotations.NotNull;

/**
 * Computes the observer console and replay-panel positions from the console width and scale.
 * The caller applies the returned positions to the dialog controls.
 */
public final class ObserverConsoleUtils {
    /**
     * The right margin {@code M}.
     */
    public static final int MARGIN = 30;
    /**
     * The minimap frame with the timer band, without the asset set prefix.
     */
    public static final @NotNull String SQUARE_RIGHT = "game/observer/UIObserverSquareRight.DDS";
    /**
     * The minimap frame without the timer band.
     */
    public static final @NotNull String SQUARE_RIGHT_NO_TIMER = "game/observer/UIObserverSquareRightNoTimer.DDS";
    /**
     * The left end of the unit frame.
     */
    public static final @NotNull String CONSOLE_LEFT = "game/observer/UIObserverConsoleLeft.DDS";
    /**
     * The middle of the unit frame.
     */
    public static final @NotNull String CONSOLE_CENTER = "game/observer/UIObserverConsoleCenter.DDS";
    /**
     * The right end of the unit frame.
     */
    public static final @NotNull String CONSOLE_RIGHT = "game/observer/UIObserverConsoleRight.DDS";
    /**
     * The replay control frame.
     */
    public static final @NotNull String SQUARE_FULL = "game/observer/UIObserverSquareFull.DDS";

    /**
     * The widest page of the observer page's smallest media block ({@code @media (max-width: 640px)}).
     */
    private static final float SMALL_PAGE_WIDTH = 640;
    /**
     * The narrowest page of its largest media block ({@code @media (min-width: 2560px)}).
     */
    private static final float LARGE_PAGE_WIDTH = 2560;
    /**
     * The CSS scale of the smallest block.
     */
    private static final float SMALL_PAGE_SCALE = 1;
    /**
     * The CSS scale of the middle block, which doubles every size.
     */
    private static final float MIDDLE_PAGE_SCALE = 2;
    /**
     * The CSS scale of the largest block, which quadruples every size.
     */
    private static final float LARGE_PAGE_SCALE = 4;

    private static final int BUTTONS_RIGHT_OFFSET = 4;
    private static final int BUTTONS_BOTTOM_OFFSET = 6;
    private static final int PANEL_CENTER_OFFSET = 170;
    private static final int PANEL_BOTTOM_MARGIN = 10;
    private static final int MINIMAP_BOTTOM_MARGIN = 13;
    private static final int HIDDEN_DIALOG_OFFSET = -100;

    /**
     * Returns the scale selected by the observer page width.
     *
     * @param pageWidth the page width in screen pixels
     * @return {@code 1} up to {@code 640} pixels, {@code 2} below {@code 2560} pixels, otherwise {@code 4}
     */
    public static float getPageScale(float pageWidth) {
        if (pageWidth <= SMALL_PAGE_WIDTH) {
            return SMALL_PAGE_SCALE;
        }

        return pageWidth < LARGE_PAGE_WIDTH ? MIDDLE_PAGE_SCALE : LARGE_PAGE_SCALE;
    }

    /**
     * Returns the minimap frame drawn at the bottom left: {@link #SQUARE_RIGHT} while the game time is shown, else
     * {@link #SQUARE_RIGHT_NO_TIMER}. The dialogs are placed by {@link #SQUARE_RIGHT} either way ({@link #createLayout}).
     *
     * @param timer whether the game time is shown
     * @return the frame path without the asset set prefix
     */
    public static @NotNull String getMinimapFrame(boolean timer) {
        return timer ? SQUARE_RIGHT : SQUARE_RIGHT_NO_TIMER;
    }

    /**
     * Creates a {@link ConsoleLayout} instance with observer dialog positions.
     *
     * @param replay            the replay console layout ({@code statbtnn})
     * @param width             the interface width {@code W}
     * @param squareRightHeight {@code h(SquareRight)}, the frame with the timer band, whether or not the game time is shown
     * @param squareFullWidth   {@code w(SquareFull)}
     * @param squareFullHeight  {@code h(SquareFull)}
     * @return the created {@link ConsoleLayout} instance
     */
    public static @NotNull ConsoleLayout createLayout(@NotNull ConsoleLayout replay,
                                                      int width,
                                                      int squareRightHeight,
                                                      int squareFullWidth, int squareFullHeight) {
        int height = ConsoleLayout.INTERFACE_HEIGHT;
        DialogLayout minimap = replay.getMinimap();
        DialogLayout panel = replay.getSelectionPanel();
        DialogLayout buttons = replay.getCommandButtons();
        float buttonsX = width + BUTTONS_RIGHT_OFFSET - MARGIN - squareFullWidth - buttons.getX();
        float buttonsY = height + BUTTONS_BOTTOM_OFFSET - squareFullHeight - buttons.getY();
        // The stored dialog height is (bottom - top), one less than the rect height (inclusive rects, uncertain for dialogs).
        float panelX = width / 2 - PANEL_CENTER_OFFSET - panel.getX();
        float panelY = height - PANEL_BOTTOM_MARGIN - (panel.getHeight() - 1) - panel.getY();
        float minimapX = -minimap.getX();
        float minimapY = height - squareRightHeight - MINIMAP_BOTTOM_MARGIN - minimap.getY();

        return replay.createMovedLayout(width, height - squareFullHeight,
                                        buttonsX, buttonsY,
                                        panelX, panelY,
                                        0, 0,
                                        0, 0,
                                        minimapX, minimapY,
                                        0, HIDDEN_DIALOG_OFFSET);
    }

    private ObserverConsoleUtils() {
    }
}
