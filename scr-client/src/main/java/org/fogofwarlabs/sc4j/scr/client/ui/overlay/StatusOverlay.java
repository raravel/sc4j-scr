package org.fogofwarlabs.sc4j.scr.client.ui.overlay;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.input.KeyModifier;
import org.fogofwarlabs.sc4j.scr.client.configuration.InterfaceSettings;
import org.fogofwarlabs.sc4j.scr.client.ui.input.GameAccelerators;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFont;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceRenderer;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceTransform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Draws the status texts over every screen, the menus, the lobby, the loading screen and the game alike, after the
 * screen is complete and below the cursor: the frame rate ({@code ShowFPS}) and, in a running network game, the turn
 * rate with the latency ({@code ShowTurnRate}).
 * <p>
 * The turn rate line starts at the top left of the screen's interface and the frame rate line one font height below it,
 * both in opaque white, placed by the interface transform of the screen they are drawn over. The frame rate is the
 * number of presents in the trailing second, updated on every present. The overlay also recognizes the keys that turn
 * the two texts on and off.
 */
public final class StatusOverlay {
    /**
     * The interface left of both status lines.
     */
    private static final int TEXT_LEFT = 10;
    /**
     * The interface top of the turn rate line; the frame rate line is one font height lower.
     */
    private static final int TEXT_TOP = 10;
    /**
     * The opaque white of both status lines.
     */
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    /**
     * The frame rate text: the presents of the trailing second.
     */
    private static final @NotNull String FRAME_RATE_FORMAT = "FPS: {0}";
    /**
     * The turn rate text: the game's turns per second and the localized latency name.
     */
    private static final @NotNull String TURN_RATE_FORMAT = "TR: {0} | {1}";
    /**
     * The localized latency name keys in latency order.
     */
    private static final @NotNull List<String> LATENCY_KEYS = List.of("bnet_latency_low", "bnet_latency_high", "bnet_latency_extraHigh");
    /**
     * The localized scan text key of the dynamic turn rate.
     */
    private static final @NotNull String SCAN_KEY = "bnet_dtr_scanInProgress";
    /**
     * The age beyond which a present no longer counts for the frame rate: one second.
     */
    private static final long FRAME_RATE_WINDOW_NANOSECONDS = 1_000_000_000L;
    /**
     * The first capacity of the present times; it doubles whenever a second holds more presents.
     */
    private static final int INITIAL_PRESENT_CAPACITY = 256;
    /**
     * The factor by which the present times grow.
     */
    private static final int PRESENT_CAPACITY_GROWTH = 2;

    /**
     * Creates the frame rate text, shown when {@code ShowFPS} is on.
     *
     * @param settings        the interface settings with the display switches
     * @param framesPerSecond the presents of the trailing second
     * @return the frame rate text, or {@code null} when it is not shown
     */
    static @Nullable String createFrameRateText(@NotNull InterfaceSettings settings, int framesPerSecond) {
        return settings.isFrameRateShown() ? Exceptions.format(FRAME_RATE_FORMAT, framesPerSecond) : null;
    }

    /**
     * Creates the turn rate text, shown when {@code ShowTurnRate} is on and a network game runs: the turns per second and the latency name,
     * or the scan text instead while the dynamic turn rate scans.
     *
     * @param settings    the interface settings with the display switches
     * @param networkGame whether a network game runs
     * @param turnRate    the game's turns per second
     * @param latency     the localized name of the game's latency
     * @param scan        the localized scan text while the dynamic turn rate scans, else empty
     * @return the turn rate text, or {@code null} when it is not shown
     */
    static @Nullable String createTurnRateText(@NotNull InterfaceSettings settings, boolean networkGame, int turnRate, @NotNull String latency,
                                               @NotNull String scan) {
        if (!settings.isTurnRateShown() || !networkGame) {
            return null;
        }

        return scan.isEmpty() ? Exceptions.format(TURN_RATE_FORMAT, turnRate, latency) : scan;
    }

    /**
     * @return the text key of the dynamic turn rate's scan text, which replaces the turn rate text while the scan runs
     */
    public static @NotNull String getScanKey() {
        return SCAN_KEY;
    }

    /**
     * Returns the text key of a latency's localized name; latencies above the highest use the highest name.
     *
     * @param latency the latency, {@code 0} low through {@code 2} extra high
     * @return the text key
     */
    public static @NotNull String getLatencyKey(int latency) {
        return LATENCY_KEYS.get(Math.min(latency, LATENCY_KEYS.size() - 1));
    }

    /**
     * Calculates the interface top of the frame rate line: one font height below the turn rate line.
     *
     * @param fontHeight the maximum glyph height of the status font
     * @return the interface top of the frame rate line
     */
    static int getFrameRateTop(int fontHeight) {
        return TEXT_TOP + fontHeight;
    }

    /**
     * Matches a key against the status toggles of the active accelerator table: Ctrl+Alt+F turns {@code ShowFPS} over in
     * every table, Ctrl+Alt+T turns {@code ShowTurnRate} over in the play and chat tables (the popup table has no entry
     * for it). Neither repeats.
     *
     * @param mode      the active accelerator mode: the game's mode while a session runs, else
     *                  {@link GameAccelerators.Mode#PLAY}
     * @param key       the key
     * @param modifiers the modifier mask ({@link KeyModifier#getMask()} bits)
     * @param repeat    whether the event is a key repeat
     * @return {@link GameAccelerators#SHOW_FPS}, {@link GameAccelerators#SHOW_TURN_RATE}, or {@link GameAccelerators#NONE}
     * when the key turns neither over
     */
    public static int findToggle(@NotNull GameAccelerators.Mode mode, @NotNull Key key, int modifiers, boolean repeat) {
        GameAccelerators.Entry entry = GameAccelerators.find(mode, key, modifiers, repeat);

        if (entry == null) {
            return GameAccelerators.NONE;
        }

        int identifier = entry.getIdentifier();

        return identifier == GameAccelerators.SHOW_FPS || identifier == GameAccelerators.SHOW_TURN_RATE ? identifier : GameAccelerators.NONE;
    }

    private static void drawLine(@NotNull InterfaceRenderer renderer, @NotNull InterfaceTransform transform,
                                 @NotNull InterfaceFont font, @NotNull String text,
                                 int top) {
        InterfaceText.drawColored(renderer,
                                  transform,
                                  font,
                                  text,
                                  TEXT_LEFT, top, 0, 0,
                                  InterfaceText.LEFT | InterfaceText.TOP,
                                  TEXT_COLOR,
                                  InterfaceText.GAME_CODE_COLORS,
                                  false);
    }


    /**
     * The times of the counted presents, oldest first, in a ring that starts at {@link #firstPresent}.
     */
    private long @NotNull [] presentTimes;
    private int firstPresent;
    private int presentCount;

    private @Nullable String frameRateText;
    private @Nullable String turnRateText;

    /**
     * Creates a {@link StatusOverlay} instance with no counted present and no text shown.
     */
    public StatusOverlay() {
        this.presentTimes = new long[INITIAL_PRESENT_CAPACITY];
        this.firstPresent = 0;
        this.presentCount = 0;

        this.frameRateText = null;
        this.turnRateText  = null;
    }

    /**
     * @return the presents of the trailing second, the last recorded present included
     */
    public int getFramesPerSecond() {
        return this.presentCount;
    }


    /**
     * Counts a present: adds its time and drops the presents more than one second older than it.
     *
     * @param nanoTime the {@link System#nanoTime()} of the present
     */
    public void recordPresent(long nanoTime) {
        if (this.presentCount == this.presentTimes.length) {
            long[] grown = new long[this.presentTimes.length * PRESENT_CAPACITY_GROWTH];

            for (int index = 0; index < this.presentCount; index++) {
                grown[index] = this.presentTimes[(this.firstPresent + index) % this.presentTimes.length];
            }

            this.presentTimes = grown;
            this.firstPresent = 0;
        }

        this.presentTimes[(this.firstPresent + this.presentCount) % this.presentTimes.length] = nanoTime;
        this.presentCount++;

        while (nanoTime - this.presentTimes[this.firstPresent] > FRAME_RATE_WINDOW_NANOSECONDS) {
            this.firstPresent = (this.firstPresent + 1) % this.presentTimes.length;
            this.presentCount--;
        }
    }

    /**
     * Prepares the texts of the next present: the frame rate when it is shown, and the turn rate when it is shown and a
     * network game runs (never in a replay or a single-player game).
     *
     * @param settings    the interface settings with the display switches
     * @param networkGame whether a network game runs
     * @param turnRate    the game's turns per second
     * @param latency     the localized name of the game's latency
     * @param scan        the localized scan text while the dynamic turn rate scans, else empty
     */
    public void update(@NotNull InterfaceSettings settings, boolean networkGame, int turnRate, @NotNull String latency, @NotNull String scan) {
        this.frameRateText = createFrameRateText(settings, this.presentCount);
        this.turnRateText  = createTurnRateText(settings, networkGame, turnRate, latency, scan);
    }

    /**
     * Draws the prepared texts: the frame rate line, then the turn rate line.
     *
     * @param renderer  the interface renderer, between {@code begin} and {@code end}
     * @param transform the interface transform of the screen the texts are drawn over
     * @param font      the small font of the screen's font set
     */
    public void draw(@NotNull InterfaceRenderer renderer, @NotNull InterfaceTransform transform, @NotNull InterfaceFont font) {
        String frameRate = this.frameRateText;
        String turnRate = this.turnRateText;

        if (frameRate != null) {
            drawLine(renderer, transform, font, frameRate, getFrameRateTop(font.getMaximumHeight()));
        }

        if (turnRate != null) {
            drawLine(renderer, transform, font, turnRate, TEXT_TOP);
        }
    }
}
