package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrInputModifiers;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrKeyInput;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrMouseInput;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Delivers the local key and mouse button input of a running game to the game's plugins ({@link ScrGamePlugin#onKeyInput},
 * {@link ScrGamePlugin#onMouseInput}) in extension order until one consumes it, and pairs every release with its press: a release is delivered
 * when its press was, and a mouse release is withheld from the game when a plugin consumed its press. The caller decides which presses reach
 * the plugins.
 */
final class GamePluginInput {
    /**
     * The number of Windows virtual-key codes.
     */
    private static final int VIRTUAL_KEY_COUNT = 0x100;

    /**
     * The delivery states of a mouse button's last press.
     */
    private static final byte NOT_DELIVERED = 0;
    private static final byte DELIVERED = 1;
    private static final byte CONSUMED = 2;

    /**
     * Whether the last press of each virtual key was delivered to the plugins.
     */
    private final boolean @NotNull [] deliveredKeys;
    /**
     * The delivery state of the last press of each mouse button, by {@link ScrMouseInput.Button} ordinal.
     */
    private final byte @NotNull [] buttonStates;

    /**
     * Creates a {@link GamePluginInput} instance without delivered presses.
     */
    GamePluginInput() {
        this.deliveredKeys = new boolean[VIRTUAL_KEY_COUNT];
        this.buttonStates  = new byte[ScrMouseInput.Button.values().length];
    }


    /**
     * Delivers a key press to the plugins.
     *
     * @param plugins    the game's plugins in extension order
     * @param virtualKey the Windows virtual-key code in {@code 0..0xFF}
     * @param modifiers  the held modifier keys ({@link ScrInputModifiers} bits)
     * @return {@code true} if a plugin consumed the press, {@code false} otherwise
     */
    boolean pressKey(@NotNull ScrGamePlugin @NotNull [] plugins, int virtualKey, int modifiers) {
        this.deliveredKeys[virtualKey] = true;

        ScrKeyInput input = new ScrKeyInput(virtualKey, true, modifiers);

        for (ScrGamePlugin plugin : plugins) {
            if (plugin.onKeyInput(input)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Delivers a key release to the plugins if the key's press was delivered.
     *
     * @param plugins    the game's plugins in extension order
     * @param virtualKey the Windows virtual-key code in {@code 0..0xFF}
     * @param modifiers  the held modifier keys ({@link ScrInputModifiers} bits)
     */
    void releaseKey(@NotNull ScrGamePlugin @NotNull [] plugins, int virtualKey, int modifiers) {
        if (!this.deliveredKeys[virtualKey]) {
            return;
        }

        this.deliveredKeys[virtualKey] = false;

        ScrKeyInput input = new ScrKeyInput(virtualKey, false, modifiers);

        for (ScrGamePlugin plugin : plugins) {
            if (plugin.onKeyInput(input)) {
                return;
            }
        }
    }

    /**
     * Delivers a mouse button press to the plugins.
     *
     * @param plugins   the game's plugins in extension order
     * @param button    the button
     * @param modifiers the held modifier keys ({@link ScrInputModifiers} bits)
     * @param mapX      the map X under the pointer in pixels
     * @param mapY      the map Y under the pointer in pixels
     * @return {@code true} if a plugin consumed the press, {@code false} otherwise
     */
    boolean pressButton(@NotNull ScrGamePlugin @NotNull [] plugins, ScrMouseInput.@NotNull Button button, int modifiers, int mapX, int mapY) {
        ScrMouseInput input = new ScrMouseInput(button, true, modifiers, mapX, mapY);

        for (ScrGamePlugin plugin : plugins) {
            if (plugin.onMouseInput(input)) {
                this.buttonStates[button.ordinal()] = CONSUMED;

                return true;
            }
        }

        this.buttonStates[button.ordinal()] = DELIVERED;

        return false;
    }

    /**
     * Delivers a mouse button release to the plugins if the button's press was delivered.
     *
     * @param plugins   the game's plugins in extension order
     * @param button    the button
     * @param modifiers the held modifier keys ({@link ScrInputModifiers} bits)
     * @param mapX      the map X under the pointer in pixels
     * @param mapY      the map Y under the pointer in pixels
     * @return {@code true} if a plugin consumed the button's press, so the game does not see the release, {@code false} otherwise
     */
    boolean releaseButton(@NotNull ScrGamePlugin @NotNull [] plugins, ScrMouseInput.@NotNull Button button, int modifiers, int mapX, int mapY) {
        byte state = this.buttonStates[button.ordinal()];

        if (state == NOT_DELIVERED) {
            return false;
        }

        this.buttonStates[button.ordinal()] = NOT_DELIVERED;

        ScrMouseInput input = new ScrMouseInput(button, false, modifiers, mapX, mapY);

        for (ScrGamePlugin plugin : plugins) {
            if (plugin.onMouseInput(input)) {
                break;
            }
        }

        return state == CONSUMED;
    }

    /**
     * Forgets the delivered presses, as a new game starts.
     */
    void reset() {
        Arrays.fill(this.deliveredKeys, false);
        Arrays.fill(this.buttonStates, NOT_DELIVERED);
    }
}
