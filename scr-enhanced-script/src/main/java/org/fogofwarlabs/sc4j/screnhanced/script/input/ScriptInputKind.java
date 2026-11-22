package org.fogofwarlabs.sc4j.screnhanced.script.input;

/**
 * Lists the kinds of the local inputs ({@link ScriptLocalInput}). The id of a kind is its ordinal, the id of the upper layer's
 * {@code InputKind} of the same name.
 */
public enum ScriptInputKind {
    /**
     * A key the local player pressed.
     */
    KEY_DOWN,
    /**
     * A key the local player released.
     */
    KEY_UP,
    /**
     * A mouse button the local player pressed over the map.
     */
    MOUSE_DOWN,
    /**
     * A mouse button the local player released.
     */
    MOUSE_UP,
    /**
     * A chat line the local player submitted.
     */
    CHAT,
    /**
     * A command card button the local player pressed.
     */
    BUTTON_PRESSED,
    /**
     * A map point or unit the local player targeted with a script target button.
     */
    BUTTON_TARGETED
}
