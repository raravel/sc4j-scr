package org.fogofwarlabs.sc4j.screnhanced.script.input;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrInputModifiers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One input of the local player that the local input hooks receive: a key or mouse button change with the held modifiers and the map position under
 * the pointer when known, a chat line, or a command card button press or target.
 */
public final class ScriptLocalInput {
    /**
     * The modifier bits an input keeps: {@link ScrInputModifiers#SHIFT}, {@link ScrInputModifiers#CONTROL} and {@link ScrInputModifiers#ALT}.
     */
    public static final int MODIFIER_MASK = ScrInputModifiers.SHIFT | ScrInputModifiers.CONTROL | ScrInputModifiers.ALT;

    private static final @NotNull String NO_TEXT = "";

    /**
     * Creates a {@link ScriptLocalInput} instance of a key change.
     *
     * @param virtualKey the Windows virtual-key code
     * @param pressed    {@code true} if the key went down, {@code false} if it went up
     * @param modifiers  the held modifier keys ({@link ScrInputModifiers} bits; other bits are dropped)
     * @param positioned whether the map position under the pointer is known
     * @param x          the map X under the pointer in pixels
     * @param y          the map Y under the pointer in pixels
     * @return the created {@link ScriptLocalInput} instance
     */
    public static @NotNull ScriptLocalInput ofKey(int virtualKey, boolean pressed, int modifiers, boolean positioned, int x, int y) {
        return new ScriptLocalInput(pressed ? ScriptInputKind.KEY_DOWN : ScriptInputKind.KEY_UP, virtualKey, modifiers & MODIFIER_MASK,
                                    positioned, x, y, NO_TEXT, null);
    }

    /**
     * Creates a {@link ScriptLocalInput} instance of a mouse button change over the map.
     *
     * @param button    the button: {@code 0} left, {@code 1} right, {@code 2} middle
     * @param pressed   {@code true} if the button went down, {@code false} if it went up
     * @param modifiers the held modifier keys ({@link ScrInputModifiers} bits; other bits are dropped)
     * @param x         the map X under the pointer in pixels
     * @param y         the map Y under the pointer in pixels
     * @return the created {@link ScriptLocalInput} instance
     */
    public static @NotNull ScriptLocalInput ofMouse(int button, boolean pressed, int modifiers, int x, int y) {
        return new ScriptLocalInput(pressed ? ScriptInputKind.MOUSE_DOWN : ScriptInputKind.MOUSE_UP, button, modifiers & MODIFIER_MASK, true, x,
                                    y, NO_TEXT, null);
    }

    /**
     * Creates a {@link ScriptLocalInput} instance of a chat line the local player submitted.
     *
     * @param text the chat line
     * @return the created {@link ScriptLocalInput} instance
     */
    public static @NotNull ScriptLocalInput ofChat(@NotNull String text) {
        return new ScriptLocalInput(ScriptInputKind.CHAT, 0, 0, false, 0, 0, text, null);
    }

    /**
     * Creates a {@link ScriptLocalInput} instance of a command card button press or target.
     *
     * @param button   the button
     * @param targeted {@code true} for the target of a script target button, {@code false} for a press
     * @return the created {@link ScriptLocalInput} instance
     */
    public static @NotNull ScriptLocalInput ofButton(@NotNull ScriptButtonPress button, boolean targeted) {
        return new ScriptLocalInput(targeted ? ScriptInputKind.BUTTON_TARGETED : ScriptInputKind.BUTTON_PRESSED, 0, 0, targeted, button.getX(),
                                    button.getY(), NO_TEXT, button);
    }


    private final @NotNull ScriptInputKind kind;
    private final int code;
    private final int modifiers;

    private final boolean positioned;
    private final int x;
    private final int y;

    private final @NotNull String text;
    private final @Nullable ScriptButtonPress button;

    private ScriptLocalInput(@NotNull ScriptInputKind kind, int code, int modifiers, boolean positioned, int x, int y, @NotNull String text,
                             @Nullable ScriptButtonPress button) {
        this.kind      = kind;
        this.code      = code;
        this.modifiers = modifiers;

        this.positioned = positioned;
        this.x          = x;
        this.y          = y;

        this.text   = text;
        this.button = button;
    }

    /**
     * @return the kind of the input
     */
    public @NotNull ScriptInputKind getKind() {
        return this.kind;
    }

    /**
     * @return the Windows virtual-key code of a key change, the button of a mouse button change, {@code 0} for a chat line
     */
    public int getCode() {
        return this.code;
    }

    /**
     * @return the held modifier keys ({@link ScrInputModifiers} bits), {@code 0} for a chat line
     */
    public int getModifiers() {
        return this.modifiers;
    }

    /**
     * @return {@code true} if the map position under the pointer is known, {@code false} otherwise
     */
    public boolean isPositioned() {
        return this.positioned;
    }

    /**
     * @return the map X under the pointer in pixels, {@code 0} without a position
     */
    public int getX() {
        return this.x;
    }

    /**
     * @return the map Y under the pointer in pixels, {@code 0} without a position
     */
    public int getY() {
        return this.y;
    }

    /**
     * @return the chat line, empty for a key or mouse button change
     */
    public @NotNull String getText() {
        return this.text;
    }

    /**
     * @return the command card button of a button press or target, {@code null} for another input
     */
    public @Nullable ScriptButtonPress getButton() {
        return this.button;
    }
}
