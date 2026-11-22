package org.fogofwarlabs.sc4j.scr.game.extension;

/**
 * Names the bits of the modifier mask of local input that game plugins see ({@link ScrKeyInput#getModifiers()},
 * {@link ScrMouseInput#getModifiers()}): the modifier keys held when the input happened.
 */
public final class ScrInputModifiers {
    /**
     * Either Shift key is held.
     */
    public static final int SHIFT = 1;
    /**
     * Either Control key is held.
     */
    public static final int CONTROL = 1 << 1;
    /**
     * Either Alt key is held.
     */
    public static final int ALT = 1 << 2;


    private ScrInputModifiers() {
    }
}
