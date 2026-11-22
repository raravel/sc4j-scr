package org.fogofwarlabs.sc4j.scr.game.extension;

/**
 * A key press or release of the local player that a game plugin sees ({@link ScrGamePlugin#onKeyInput}). The key is named by its Windows
 * virtual-key code, the code the native game's key states use: both Shift, Control and Alt keys share their generic codes ({@code 0x10},
 * {@code 0x11}, {@code 0x12}) and keypad Enter shares the Return code ({@code 0x0D}).
 */
public final class ScrKeyInput {
    private final int virtualKey;
    private final boolean pressed;
    private final int modifiers;

    /**
     * Creates a {@link ScrKeyInput} instance.
     *
     * @param virtualKey the Windows virtual-key code in {@code 0..0xFF}
     * @param pressed    whether the key went down, else up
     * @param modifiers  the held modifier keys ({@link ScrInputModifiers} bits)
     */
    public ScrKeyInput(int virtualKey, boolean pressed, int modifiers) {
        this.virtualKey = virtualKey;
        this.pressed    = pressed;
        this.modifiers  = modifiers;
    }

    /**
     * @return the Windows virtual-key code in {@code 0..0xFF}
     */
    public int getVirtualKey() {
        return this.virtualKey;
    }

    /**
     * @return {@code true} if the key went down, {@code false} if it went up
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
}
