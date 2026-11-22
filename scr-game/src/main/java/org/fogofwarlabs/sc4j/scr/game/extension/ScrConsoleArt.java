package org.fogofwarlabs.sc4j.scr.game.extension;

/**
 * The art files of the shown race console that a plugin can replace ({@link ScrGamePlugin#findConsoleArt}), named after the installed files. The
 * SD graphics draw the console frame from {@link #CONSOLE}, the HD and HD2 graphics from {@link #CONSOLE_LEFT}, {@link #CONSOLE_CENTER} and
 * {@link #CONSOLE_RIGHT}; every graphics set draws the plates {@link #CONSOLE_TIME}, {@link #CONSOLE_APM} and the portrait overlay
 * {@link #CONOVER}.
 */
public enum ScrConsoleArt {
    /**
     * The SD console frame, {@code game/<z|t|p>console.DDS}.
     */
    CONSOLE,
    /**
     * The left HD console piece with the minimap frame, {@code game/consoles/<race>/console_left.DDS}.
     */
    CONSOLE_LEFT,
    /**
     * The center HD console piece with the selection frame, {@code game/consoles/<race>/console_center.DDS}.
     */
    CONSOLE_CENTER,
    /**
     * The right HD console piece with the portrait and command card frames, {@code game/consoles/<race>/console_right.DDS}.
     */
    CONSOLE_RIGHT,
    /**
     * The plate under the game time, {@code game/consoles/<race>/console_TIME.DDS}.
     */
    CONSOLE_TIME,
    /**
     * The plate under the APM number, {@code game/consoles/<race>/console_APM.DDS}.
     */
    CONSOLE_APM,
    /**
     * The overlay over the portrait, {@code game/consoles/<race>/conover.DDS}.
     */
    CONOVER
}
