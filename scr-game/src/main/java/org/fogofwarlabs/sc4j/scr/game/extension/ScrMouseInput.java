package org.fogofwarlabs.sc4j.scr.game.extension;

import org.jetbrains.annotations.NotNull;

/**
 * A mouse button press or release of the local player over the map that a game plugin sees ({@link ScrGamePlugin#onMouseInput}), with the map
 * position under the pointer.
 */
public final class ScrMouseInput {
    /**
     * A mouse button.
     */
    public enum Button {
        /**
         * The primary (left) button.
         */
        LEFT,
        /**
         * The secondary (right) button.
         */
        RIGHT,
        /**
         * The middle button or the pressed wheel.
         */
        MIDDLE
    }

    private final @NotNull Button button;
    private final boolean pressed;
    private final int modifiers;
    private final int mapX;
    private final int mapY;

    /**
     * Creates a {@link ScrMouseInput} instance.
     *
     * @param button    the button
     * @param pressed   whether the button went down, else up
     * @param modifiers the held modifier keys ({@link ScrInputModifiers} bits)
     * @param mapX      the map X under the pointer in pixels
     * @param mapY      the map Y under the pointer in pixels
     */
    public ScrMouseInput(@NotNull Button button, boolean pressed, int modifiers, int mapX, int mapY) {
        this.button    = button;
        this.pressed   = pressed;
        this.modifiers = modifiers;
        this.mapX      = mapX;
        this.mapY      = mapY;
    }

    /**
     * @return the button
     */
    public @NotNull Button getButton() {
        return this.button;
    }

    /**
     * @return {@code true} if the button went down, {@code false} if it went up
     */
    public boolean isPressed() {
        return this.pressed;
    }

    /**
     * @return the held modifier keys ({@link ScrInputModifiers} bits)
     */
    public int getModifiers() {
        return this.modifiers;
    }

    /**
     * @return the map X under the pointer in pixels
     */
    public int getMapX() {
        return this.mapX;
    }

    /**
     * @return the map Y under the pointer in pixels
     */
    public int getMapY() {
        return this.mapY;
    }
}
