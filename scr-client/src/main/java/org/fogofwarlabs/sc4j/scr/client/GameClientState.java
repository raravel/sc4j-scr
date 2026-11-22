package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.dialog.DialogStack;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextEditKey;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextEditOperation;
import org.fogofwarlabs.sc4j.engine.client.host.PointerClip;
import org.fogofwarlabs.sc4j.engine.client.input.*;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.ui.command.CommandCard;
import org.fogofwarlabs.sc4j.scr.client.ui.command.CommandInput;
import org.fogofwarlabs.sc4j.scr.client.ui.command.HotkeyPress;
import org.fogofwarlabs.sc4j.scr.client.ui.console.*;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialogLayer;
import org.fogofwarlabs.sc4j.scr.client.ui.input.GameAccelerators;
import org.fogofwarlabs.sc4j.scr.client.ui.input.VirtualKeys;
import org.fogofwarlabs.sc4j.scr.client.ui.input.WorldInterface;
import org.fogofwarlabs.sc4j.scr.client.ui.menu.AllianceDialog;
import org.fogofwarlabs.sc4j.scr.client.ui.menu.GameMenu;
import org.fogofwarlabs.sc4j.scr.client.ui.menu.NetworkWaitDialog;
import org.fogofwarlabs.sc4j.scr.client.ui.observer.ReplaySideBar;
import org.fogofwarlabs.sc4j.scr.client.world.ClientCamera;
import org.fogofwarlabs.sc4j.scr.client.world.GameViewport;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrInputModifiers;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrMouseInput;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalKeyStates;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.visibility.LocalVision;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Routes window input through the running game, replay or loading interface. The client owns the session and interface resources used by this state.
 */
final class GameClientState implements InputListener {
    private static final @NotNull Logger LOGGER = Logger.of(GameClientState.class);

    /**
     * The menu button of the console dialog {@code stat_f10}.
     */
    private static final int CONSOLE_MENU_BUTTON = 1;

    /**
     * The last cargo slot of the selection panel {@code statdata} ({@code 18..32}); the group grid follows ({@code 33..44}).
     */
    private static final int LAST_CARGO_CONTROL = 32;

    /**
     * The minimap terrain button (minimap control {@code 2}).
     */
    private static final int MINIMAP_TERRAIN_BUTTON = 2;

    /**
     * The minimap Diplomacy button of a network game (minimap control {@code 4}), which opens the alliance dialog.
     */
    private static final int MINIMAP_DIPLOMACY_BUTTON = 4;

    /**
     * The minimap Message button of a network game (control {@code 3}).
     */
    private static final int MINIMAP_MESSAGE_BUTTON = 3;

    /**
     * The target zoom factor of one mouse-wheel step.
     */
    private static final double WHEEL_ZOOM_STEP = 1.2;

    /**
     * The key state byte of a held key.
     */
    private static final int KEY_DOWN = 1;
    /**
     * The key state byte of a released key.
     */
    private static final int KEY_UP = 0;

    /**
     * The asset warning prefix of a failed pointer move or map interaction.
     */
    private static final @NotNull String POINTER_WARNING_PREFIX = "Pointer: ";
    /**
     * The asset warning prefix of a failed dialog update.
     */
    private static final @NotNull String DIALOGS_WARNING_PREFIX = "Dialogs: ";

    private static final int REPLAY_SEEK_CONTROL = 10;
    private static final int REPLAY_SEEK_LEFT_PADDING = 2;
    private static final int REPLAY_SEEK_RIGHT_PADDING = 3;

    private static final int TILE_ROUNDING_BIAS = TerrainTileset.TILE_SIZE - 1;
    private static final int VIEW_HALF_DIVISOR = 2;

    /**
     * Converts the modifier mask of a window input event to the modifier mask game plugins see.
     *
     * @param modifiers the modifier mask ({@link KeyModifier#getMask()} bits)
     * @return the held Shift, Control and Alt keys ({@link ScrInputModifiers} bits)
     */
    private static int toPluginModifiers(int modifiers) {
        int converted = 0;

        if (KeyModifier.SHIFT.isIn(modifiers)) {
            converted |= ScrInputModifiers.SHIFT;
        }

        if (KeyModifier.CONTROL.isIn(modifiers)) {
            converted |= ScrInputModifiers.CONTROL;
        }

        if (KeyModifier.ALT.isIn(modifiers)) {
            converted |= ScrInputModifiers.ALT;
        }

        return converted;
    }

    /**
     * @param button a window pointer button
     * @return the button game plugins see, or {@code null} for a button they do not see
     */
    private static ScrMouseInput.@Nullable Button toPluginButton(@NotNull PointerButton button) {
        return switch (button) {
            case LEFT -> ScrMouseInput.Button.LEFT;
            case RIGHT -> ScrMouseInput.Button.RIGHT;
            case MIDDLE -> ScrMouseInput.Button.MIDDLE;
            default -> null;
        };
    }


    /**
     * The in-game dialogs this state put into the client's dialog list.
     */
    private final @NotNull List<ScrDialogLayer> gameDialogLayers;

    private final @NotNull Main main;
    private final @NotNull UnitInspection inspection;
    private final @NotNull ChatPrompt chatPrompt;
    /**
     * The key and mouse button presses delivered to the game's plugins.
     */
    private final @NotNull GamePluginInput pluginInput;

    /**
     * Whether a minimap press drags the camera.
     */
    private boolean minimapDragging;
    /**
     * Whether the game view's current left press was a double press.
     */
    private boolean pendingDoubleClick;
    /**
     * Whether the last press was a double press of the game's detector.
     */
    private boolean lastPressDouble;

    /**
     * The {@code /zoom on|off} choice of the mouse-wheel zoom (a client feature), or {@code null} until a command chooses: the wheel then zooms in
     * the replay viewer and not in live games.
     */
    private @Nullable Boolean wheelZoom;
    /**
     * Whether a middle or right press over the game view drags the camera ({@code /pan on}, a client feature; off by default).
     */
    private boolean dragPan;
    /**
     * Whether a middle or right press over the game view is dragging the camera now.
     */
    private boolean dragging;

    /**
     * The pointer position the camera drag last moved from, in framebuffer pixels.
     */
    private float dragX;
    private float dragY;

    /**
     * Creates a {@link GameClientState} instance.
     *
     * @param main the client that still owns the session and the interface objects
     */
    GameClientState(@NotNull Main main) {
        this.gameDialogLayers = new ArrayList<>();

        this.main        = main;
        this.inspection  = new UnitInspection();
        this.chatPrompt  = new ChatPrompt();
        this.pluginInput = new GamePluginInput();

        this.minimapDragging    = false;
        this.pendingDoubleClick = false;
        this.lastPressDouble    = false;

        this.wheelZoom = null;
        this.dragPan   = false;
        this.dragging  = false;

        this.dragX = 0.0f;
        this.dragY = 0.0f;
    }

    /**
     * @return the typed line (chat box or client command prompt)
     */
    @NotNull ChatPrompt getChatPrompt() {
        return this.chatPrompt;
    }

    /**
     * @return the developer inspection of the clicked map placement
     */
    @NotNull UnitInspection getInspection() {
        return this.inspection;
    }

    /**
     * @return {@code true} if a minimap press drags the camera, {@code false} otherwise
     */
    boolean isMinimapDragging() {
        return this.minimapDragging;
    }

    /**
     * @return {@code true} if the mouse wheel zooms the game view (as {@code /zoom on|off} chose, else in the replay viewer only),
     * {@code false} otherwise
     */
    boolean isWheelZoom() {
        Boolean chosen = this.wheelZoom;

        if (chosen != null) {
            return chosen;
        }

        return this.main.session != null && !this.main.requireSession().isLive();
    }

    /**
     * Sets whether the mouse wheel zooms the game view.
     *
     * @param wheelZoom whether the mouse wheel zooms the game view
     */
    void setWheelZoom(boolean wheelZoom) {
        this.wheelZoom = wheelZoom;
    }

    /**
     * @return {@code true} if a middle or right drag pans the camera, {@code false} otherwise
     */
    boolean isDragPan() {
        return this.dragPan;
    }

    /**
     * Sets whether a middle or right drag pans the camera; switching it off ends a running drag.
     *
     * @param dragPan whether a middle or right drag pans the camera
     */
    void setDragPan(boolean dragPan) {
        this.dragPan  = dragPan;
        this.dragging = this.dragging && dragPan;
    }


    /**
     * Handles one input event: a key event goes to {@link #onKeyEvent}, a typed character to {@link #onCharacterTyped}, a pointer movement to
     * {@link #onPointerMoved}, a button event to {@link #onPointerButton} and a wheel movement to {@link #onScrolled}.
     *
     * @param event the event
     */
    @Override
    public void onInput(@NotNull InputEvent event) {
        switch (event.getType()) {
            case KEY_PRESSED, KEY_REPEATED, KEY_RELEASED -> this.onKeyEvent(event);
            case CHARACTER_TYPED -> this.onCharacterTyped(event.getCodePoint());
            case POINTER_MOVED -> this.onPointerMoved(event.getX(), event.getY());
            case BUTTON_PRESSED, BUTTON_RELEASED -> {
                PointerButton button = event.getButton();

                if (button != null) {
                    this.onPointerButton(button, event.getType() == InputEvent.Type.BUTTON_PRESSED, event.getModifiers(), event.isDoublePress());
                }
            }
            case WHEEL_SCROLLED -> this.onScrolled(event.getVerticalOffset());
        }
    }

    /**
     * Handles a window key press, repeat or release.
     *
     * @param event the key event
     */
    void onKeyEvent(@NotNull InputEvent event) {
        Key key = event.getKey();
        InputEvent.Type type = event.getType();
        int modifiers = event.getModifiers();
        // Updates the input manager key state from press and release events.
        int virtualKey = VirtualKeys.of(key);

        if (virtualKey >= 0 && virtualKey < LocalKeyStates.SIZE && type != InputEvent.Type.KEY_REPEATED) {
            this.main.keyStates[virtualKey] = (byte) (type == InputEvent.Type.KEY_PRESSED ? KEY_DOWN : KEY_UP);
        }

        if (type == InputEvent.Type.KEY_RELEASED) {
            this.releasePluginKey(virtualKey, modifiers);

            return;
        }

        // Open chat editing takes text-input edit operations before chat accelerators, so matching keys edit the
        // line instead of centering on a unit or exiting.
        TextEditOperation operation = TextEditOperation.ofKey(key, modifiers);

        if (this.chatPrompt.isOpen() && operation != null) {
            this.chatPrompt.apply(operation, this.main.getClipboard(), UiClockUtils.getHostTimeMilliseconds());

            return;
        }

        // The game's plugins see a play key before the game's accelerators and keys.
        if (type == InputEvent.Type.KEY_PRESSED && this.pressPluginKey(key, virtualKey, modifiers)) {
            return;
        }

        // Routes accelerators before dialogs and the typed line.
        if (this.main.session != null && this.main.acceleratorHandler.handle(key, modifiers, type == InputEvent.Type.KEY_REPEATED)) {
            return;
        }

        if (type != InputEvent.Type.KEY_PRESSED) {
            // A held Backspace or Delete repeats in the chat line (the key-down repeats reach the edit control).
            if (this.chatPrompt.isOpen()) {
                this.pressEditingKey(key);
            }

            return;
        }

        // The client's own features have no keys: they are chat-line commands (/help lists them).
        // In-game popups (game menu, outcome box) take keys first, in games and in the replay viewer.
        if (!this.chatPrompt.isOpen() && this.main.session != null && (this.main.isGamePopupOpen() || key == Key.F10) &&
            this.main.handleGameMenuKey(key)) {
            return;
        }

        if (this.handlePromptKey(key, modifiers)) {
            return;
        }

        if (this.main.pressCommandHotkey(key, modifiers)) {
            return;
        }

        if (this.main.handleGameMenuKey(key)) {
            return;
        }

        if (this.pressSideBarKey(key, modifiers)) {
            return;
        }

        if (this.main.handleLiveKey(key)) {
            return;
        }

        // Applies the active game or replay escape action.
        if (key == Key.ESCAPE) {
            if (this.main.session != null) {
                this.main.pressEscape();
            }

            return;
        }

        if (key == Key.TAB) {
            // Tab toggles terrain; Shift+Tab cycles minimap colors.
            if (this.main.consoleView != null && !KeyModifier.SHIFT.isIn(modifiers)) {
                this.main.consoleView.toggleMinimapTerrain();
            } else if (this.main.consoleView != null) {
                this.main.consoleView.cycleMinimapColorMode();
            }

            return;
        }

        if (key == Key.SPACE) {
            // Centers on the active unit before changing replay playback.
            boolean centered = this.main.consoleReplayControls && this.main.worldInterface != null && this.main.worldInterface.centerOnActiveUnit();

            if (!centered) {
                this.main.playing = this.main.session != null && this.main.requireSession().canStep() && !this.main.playing;
            }
        }
    }

    /**
     * Handles a typed Unicode character.
     *
     * @param codePoint the Unicode code point
     */
    void onCharacterTyped(int codePoint) {
        if (!this.chatPrompt.isOpen() && this.main.pressGameMenuCharacter(codePoint)) {
            return;
        }

        // A hotkey bound to a character the key path does not map (punctuation, a non-ASCII letter).
        if (!this.chatPrompt.isOpen() && this.main.pressCommandCharacter(codePoint)) {
            return;
        }

        this.chatPrompt.type(codePoint, UiClockUtils.getHostTimeMilliseconds());
    }

    /**
     * Handles a pointer movement.
     *
     * @param x the framebuffer x
     * @param y the framebuffer y
     */
    void onPointerMoved(float x, float y) {
        ClientSession session = this.main.session;

        if (this.dragging && session != null) {
            session.getCamera().pan(this.dragX - x, this.dragY - y);
        }

        this.moveInterfacePointer();

        this.dragX = this.main.pointerHorizontal;
        this.dragY = this.main.pointerVertical;
    }

    /**
     * Handles a mouse button change.
     *
     * @param button      the button
     * @param pressed     whether the button went down, else up
     * @param modifiers   the modifier mask ({@link KeyModifier#getMask()} bits)
     * @param doublePress whether the game's double-press detector reports a double press
     */
    void onPointerButton(@NotNull PointerButton button, boolean pressed, int modifiers, boolean doublePress) {
        if (pressed) {
            this.lastPressDouble = doublePress;
        }

        if (button == PointerButton.LEFT && this.main.consoleView != null) {
            this.main.consoleView.setPointer(this.main.pointerHorizontal, this.main.pointerVertical);
        }

        // The game does not see the release of a press a plugin consumed.
        if (!pressed && this.releasePluginButton(button, modifiers)) {
            return;
        }

        if (this.handleInterfaceButton(button, pressed, modifiers)) {
            return;
        }

        // With /pan on, the middle button drags the camera (a client feature); the right button only without the game console:
        // with the console, a right press the interface did not take has no action on the console panel.
        if (this.dragPan && (button == PointerButton.MIDDLE || button == PointerButton.RIGHT && !this.main.isConsoleShown())) {
            this.dragging = pressed && this.main.pointerHorizontal < this.main.gameViewport.getWindowWidth() &&
                            this.main.pointerVertical < this.main.gameViewport.getWindowHeight();
            this.dragX    = this.main.pointerHorizontal;
            this.dragY    = this.main.pointerVertical;
        } else if (button == PointerButton.LEFT && pressed) {
            if (this.main.debugView && this.main.seekOnTimeline()) {
                return;
            }

            if (this.main.session != null) {
                this.main.requireSession().selectAtScreen(this.main.pointerHorizontal, this.main.pointerVertical);
                this.inspection.clear();
            }
        }
    }

    /**
     * Handles a mouse wheel movement.
     *
     * @param verticalOffset the vertical wheel offset, negative toward the user
     */
    void onScrolled(double verticalOffset) {
        // The open message history consumes wheel events first.
        if (verticalOffset != 0 && this.main.scrollChatHistory(verticalOffset < 0)) {
            return;
        }

        // Enabled client wheel zoom animates about the pointer.
        if (this.isWheelZoom() && this.main.session != null && this.isPointerInView()) {
            this.main.requireSession().getCamera().zoomToward((float) Math.pow(WHEEL_ZOOM_STEP, verticalOffset),
                                                              this.main.pointerHorizontal, this.main.pointerVertical);
        }
    }

    /**
     * @return {@code true} if the pointer lies inside the game view, {@code false} otherwise
     */
    private boolean isPointerInView() {
        return this.main.pointerHorizontal < this.main.viewWidth && this.main.pointerVertical < this.main.viewHeight;
    }

    /**
     * Opens, edits, submits or closes the typed line according to the current session and modifier keys.
     *
     * @param key       the key
     * @param modifiers the modifier mask ({@link KeyModifier#getMask()} bits)
     * @return {@code true} if the prompt took the key, {@code false} otherwise
     */
    private boolean handlePromptKey(@NotNull Key key, int modifiers) {
        if (!this.chatPrompt.isOpen()) {
            // Both Enter keys open the typed line.
            if (key != Key.ENTER && key != Key.KEYPAD_ENTER) {
                return false;
            }

            ClientSession opening = this.main.session;
            int mode;

            if (opening != null && !opening.isLive()) {
                mode = ChatPrompt.MODE_OBSERVERS;
            } else if (opening != null && opening.isNetworkGame()) {
                mode = ChatPrompt.chooseMode(true, opening.isLocalObserver(), modifiers, opening.areAlliancesAllowed(), opening.getChatRecipient());
            } else {
                mode = ChatPrompt.MODE_SINGLE_PLAYER;
            }

            this.chatPrompt.open("", mode, opening != null && opening.isLive());
            this.main.showChatLabel(mode);

            return true;
        }

        switch (key) {
            case ESCAPE -> this.chatPrompt.close();
            case ENTER, KEYPAD_ENTER -> this.submitTypedLine(this.chatPrompt.take());
            default -> this.pressEditingKey(key);
        }

        return true;
    }

    /**
     * Applies Backspace or Delete to the open typed line; other keys have no effect.
     *
     * @param key the key
     */
    private void pressEditingKey(@NotNull Key key) {
        if (key == Key.BACKSPACE) {
            this.chatPrompt.press(TextEditKey.BACKSPACE, UiClockUtils.getHostTimeMilliseconds());
        } else if (key == Key.DELETE) {
            this.chatPrompt.press(TextEditKey.DELETE, UiClockUtils.getHostTimeMilliseconds());
        }
    }

    /**
     * Routes community commands to the session's channel chat, client commands locally and live-game text to the session; dropped lines perform no
     * action.
     *
     * @param line the submitted line
     */
    private void submitTypedLine(@NotNull String line) {
        ClientSession session = this.main.session;

        switch (ChatPrompt.route(line, session != null && session.isLive(), session != null && session.hasCommunityChat())) {
            case COMMUNITY -> this.main.requireSession().submitCommunityCommand(line);
            case COMMAND -> this.main.runTypedCommand(line);
            case GAME_TEXT -> this.submitGameText(this.main.requireSession(), line);
            case DROPPED -> {
            }
        }
    }

    /**
     * Submits live-game text and sends discarded network text through chat, unless one of the game's plugins consumes the line
     * ({@link ScrGamePlugin#onChatInput}).
     *
     * @param session the active session
     * @param line    the submitted text
     */
    private void submitGameText(@NotNull ClientSession session, @NotNull String line) {
        boolean consumed = false;

        for (ScrGamePlugin plugin : session.getGame().getPlugins()) {
            if (plugin.onChatInput(line)) {
                consumed = true;
                break;
            }
        }

        this.main.applyLocalViewWrite();

        if (consumed) {
            return;
        }

        int mode = this.chatPrompt.getLastMode();
        int result = session.submitChat(line);

        if (result == LiveGame.TEXT_DISCARDED && session.isNetworkGame()) {
            session.sendNetworkChat(line, mode);
        }

        LOGGER.debug("CHAT: \"" + line + "\" -> " + result);
    }

    /**
     * Delivers a key press to the game's plugins while the game takes play keys ({@link GameAccelerators.Mode#PLAY}: the chat box closed, no game
     * menu, dialog or trigger pause) and no network wait holds the game; the keys that open the chat box and the game menu and the presses that
     * run an interface accelerator ({@link GameAcceleratorHandler#isInterfaceAccelerator}) are not delivered.
     *
     * @param key        the key
     * @param virtualKey the key's Windows virtual-key code, or a negative value for a key without one
     * @param modifiers  the modifier mask ({@link KeyModifier#getMask()} bits)
     * @return {@code true} if a plugin consumed the press, {@code false} otherwise
     */
    private boolean pressPluginKey(@NotNull Key key, int virtualKey, int modifiers) {
        ClientSession session = this.main.session;

        if (session == null || virtualKey < 0 || key == Key.ENTER || key == Key.KEYPAD_ENTER || key == Key.F10 || !this.main.isConsoleShown() ||
            this.main.isNetworkWaitDialogOpen() || this.main.acceleratorHandler.getMode() != GameAccelerators.Mode.PLAY) {
            return false;
        }

        ScrGamePlugin[] plugins = session.getGame().getPlugins();

        if (plugins.length == 0 || this.main.acceleratorHandler.isInterfaceAccelerator(key, modifiers)) {
            return false;
        }

        boolean consumed = this.pluginInput.pressKey(plugins, virtualKey, toPluginModifiers(modifiers));

        this.main.applyLocalViewWrite();

        return consumed;
    }

    /**
     * Delivers a key release to the game's plugins if the key's press was delivered.
     *
     * @param virtualKey the key's Windows virtual-key code, or a negative value for a key without one
     * @param modifiers  the modifier mask ({@link KeyModifier#getMask()} bits)
     */
    private void releasePluginKey(int virtualKey, int modifiers) {
        ClientSession session = this.main.session;

        if (session == null || virtualKey < 0) {
            return;
        }

        ScrGamePlugin[] plugins = session.getGame().getPlugins();

        if (plugins.length == 0) {
            return;
        }

        this.pluginInput.releaseKey(plugins, virtualKey, toPluginModifiers(modifiers));
        this.main.applyLocalViewWrite();
    }

    /**
     * Delivers a mouse button press to the game's plugins while the pointer is on the map view ({@link Main#isOnMapView()}), with the map position
     * under the pointer.
     *
     * @param button    the button
     * @param modifiers the modifier mask ({@link KeyModifier#getMask()} bits)
     * @return {@code true} if a plugin consumed the press, {@code false} otherwise
     */
    private boolean pressPluginButton(@NotNull PointerButton button, int modifiers) {
        ClientSession session = this.main.session;
        ScrMouseInput.Button pluginButton = toPluginButton(button);

        if (session == null || pluginButton == null || !this.main.isOnMapView()) {
            return false;
        }

        ScrGamePlugin[] plugins = session.getGame().getPlugins();

        if (plugins.length == 0) {
            return false;
        }

        ClientCamera camera = session.getCamera();
        boolean consumed = this.pluginInput.pressButton(plugins, pluginButton, toPluginModifiers(modifiers),
                                                        Math.round(camera.screenToWorldX(this.main.pointerHorizontal)),
                                                        Math.round(camera.screenToWorldY(this.main.pointerVertical)));

        this.main.applyLocalViewWrite();

        return consumed;
    }

    /**
     * Delivers a mouse button release to the game's plugins if the button's press was delivered.
     *
     * @param button    the button
     * @param modifiers the modifier mask ({@link KeyModifier#getMask()} bits)
     * @return {@code true} if a plugin consumed the button's press, so the game does not see the release, {@code false} otherwise
     */
    private boolean releasePluginButton(@NotNull PointerButton button, int modifiers) {
        ClientSession session = this.main.session;
        ScrMouseInput.Button pluginButton = toPluginButton(button);

        if (session == null || pluginButton == null) {
            return false;
        }

        ScrGamePlugin[] plugins = session.getGame().getPlugins();

        if (plugins.length == 0) {
            return false;
        }

        ClientCamera camera = session.getCamera();
        boolean consumed = this.pluginInput.releaseButton(plugins, pluginButton, toPluginModifiers(modifiers),
                                                          Math.round(camera.screenToWorldX(this.main.pointerHorizontal)),
                                                          Math.round(camera.screenToWorldY(this.main.pointerVertical)));

        this.main.applyLocalViewWrite();

        return consumed;
    }

    /**
     * Starts over for a new game: no minimap or camera drag, no pending double press, the chat line closed.
     */
    void resetGameInterface() {
        this.minimapDragging    = false;
        this.pendingDoubleClick = false;
        this.lastPressDouble    = false;
        this.dragging           = false;

        this.chatPrompt.close();
        this.pluginInput.reset();
    }

    /**
     * Takes this state's dialogs out of the client's dialog list (the console closes).
     */
    void closeDialogs() {
        ClientContext context = this.main.clientContext;

        if (context != null) {
            for (ScrDialogLayer layer : this.gameDialogLayers) {
                context.getDialogStack().remove(layer);
            }
        }

        this.gameDialogLayers.clear();
    }

    /**
     * Updates the camera from an active minimap drag; the pointer clip keeps the pointer on the minimap bitmap ({@link #getPointerClip}).
     */
    private void dragMinimap() {
        if (!this.minimapDragging || this.main.consoleView == null || this.main.session == null) {
            return;
        }

        this.followMinimap();
    }

    /**
     * Returns the clip of the captured pointer: the minimap bitmap during a minimap drag, the play view of the game screen while a selection box is
     * open, the game screen otherwise.
     *
     * @param screenClip the game screen's clip
     * @return the clip in screen pixels
     */
    @NotNull PointerClip getPointerClip(@NotNull PointerClip screenClip) {
        PointerClip minimapClip = this.minimapDragging && this.main.consoleView != null ? this.main.consoleView.getMinimapClip() : null;

        if (minimapClip != null) {
            return minimapClip;
        }

        return this.main.worldInterface != null && this.main.worldInterface.isDragSelecting() ? this.main.gameViewport.getPlayClip() : screenClip;
    }

    /**
     * Follows the minimap pointer with the camera, rounding view dimensions up and snapping its target to whole tiles.
     */
    private void followMinimap() {
        if (this.main.centerViewScroll.isActive()) {
            this.minimapDragging = false;

            return;
        }

        int[] minimap = this.main.requireConsoleView().getMinimapGeometry(this.main.pointerHorizontal, this.main.pointerVertical);

        if (minimap == null) {
            return;
        }

        ClientCamera camera = this.main.requireSession().getCamera();
        GameViewport viewport = this.main.gameViewport;
        int viewWidth = viewport.getViewTileWidth(camera.getZoom());
        int viewHeight = viewport.getViewTileHeight(camera.getZoom());
        int tileWidth = ((viewWidth + TILE_ROUNDING_BIAS) / TerrainTileset.TILE_SIZE) << minimap[ConsoleView.MINIMAP_VIEW_SHIFT_INDEX];
        int tileHeight = ((viewHeight + TILE_ROUNDING_BIAS) / TerrainTileset.TILE_SIZE) << minimap[ConsoleView.MINIMAP_VIEW_SHIFT_INDEX];
        int halfWidth = tileWidth / (VIEW_HALF_DIVISOR * minimap[ConsoleView.MINIMAP_VIEW_SCALE_INDEX]);
        int halfHeight = tileHeight / (VIEW_HALF_DIVISOR * minimap[ConsoleView.MINIMAP_VIEW_SCALE_INDEX]);
        int localX = Math.clamp(minimap[ConsoleView.MINIMAP_LOCAL_X_INDEX] - halfWidth, 0, minimap[ConsoleView.MINIMAP_WIDTH_INDEX] - 1);
        int localY = Math.clamp(minimap[ConsoleView.MINIMAP_LOCAL_Y_INDEX] - halfHeight, 0, minimap[ConsoleView.MINIMAP_HEIGHT_INDEX] - 1);
        int targetX = (localX * minimap[ConsoleView.MINIMAP_PIXEL_SCALE_INDEX] >> TerrainTileset.TILE_SHIFT) << TerrainTileset.TILE_SHIFT;
        int targetY = (localY * minimap[ConsoleView.MINIMAP_PIXEL_SCALE_INDEX] >> TerrainTileset.TILE_SHIFT) << TerrainTileset.TILE_SHIFT;
        float left = camera.screenToWorldX(viewport.getGameLeft());

        camera.pan((targetX - left) * camera.getZoom(), (targetY - camera.getY()) * camera.getZoom());
    }

    /**
     * Updates minimap dragging, dialog hover and the map pointer from the current client coordinates.
     */
    void moveInterfacePointer() {
        this.main.supplyInterfacePointer();
        this.dragMinimap();

        if (this.main.consoleView != null) {
            this.main.consoleView.setPointer(this.main.pointerHorizontal, this.main.pointerVertical);
        }

        if (this.main.consoleView != null) {
            this.updateDialogHover();
        }

        WorldInterface world = this.main.worldInterface;

        if (world == null || !this.main.isConsoleShown()) {
            return;
        }

        // An open selection box follows the pointer over the console too, held inside the play view (getPointerClip).
        boolean boxOpen = world.isDragSelecting();

        if (!boxOpen && !this.main.isOnMapView()) {
            return;
        }

        try {
            ClientCamera camera = this.main.requireSession().getCamera();
            float pointerX = this.main.pointerHorizontal;
            float pointerY = this.main.pointerVertical;

            if (boxOpen) {
                PointerClip play = this.main.gameViewport.getPlayClip();

                pointerX = Math.max(play.getLeft(), Math.min(pointerX, play.getRight() - 1));
                pointerY = Math.max(play.getTop(), Math.min(pointerY, play.getBottom() - 1));
            }

            world.handlePointerMove(Math.round(camera.screenToWorldX(pointerX)), Math.round(camera.screenToWorldY(pointerY)), pointerX, -1);
        } catch (IOException exception) {
            this.main.setAssetWarning(POINTER_WARNING_PREFIX + exception.getMessage());
        }
    }

    /**
     * Routes a mouse button to the console buttons and the map interaction while the console is shown: left presses a command card button or selects,
     * right orders, middle keeps the camera drag.
     *
     * @param button    the button
     * @param pressed   whether the button went down, else up
     * @param modifiers the modifier mask ({@link KeyModifier#getMask()} bits)
     * @return {@code true} if the interface consumed the button, {@code false} otherwise
     */
    private boolean handleInterfaceButton(@NotNull PointerButton button, boolean pressed, int modifiers) {
        if (!this.main.isConsoleShown()) {
            return false;
        }

        this.main.ensureConsoleModel();

        boolean shift = KeyModifier.SHIFT.isIn(modifiers);
        ConsoleLayout consoleLayout = this.main.requireConsoleView().getLayout();

        // The replay seek bar (statbtnn control 10) lies over the replay command buttons and is tested first.
        if (button == PointerButton.LEFT && pressed && this.main.consoleReplayControls && consoleLayout != null &&
            this.isPointerInView() && this.seekOnReplayBar(consoleLayout)) {
            return true;
        }

        if (button == PointerButton.LEFT) {
            Boolean routed = this.routeDialogButton(pressed, shift);

            if (routed != null) {
                return routed;
            }
        }

        if (this.main.session != null && this.main.isGamePopupOpen()) {
            return true;
        }

        if (this.clickSideBar(button, pressed)) {
            return true;
        }

        // ScrDialog routing precedes the surrounding console art hit test.
        int[] minimapHit = this.main.consoleView.getMinimapGeometry(this.main.pointerHorizontal, this.main.pointerVertical);
        boolean overMinimap = ConsoleView.isInsideMinimap(minimapHit);

        // The console dialogs took their controls already (routeDialogButton); the console art swallows the rest.
        if (button == PointerButton.LEFT && pressed && !overMinimap && this.main.isConsoleShown() &&
            this.main.worldInterface != null && this.main.consoleView.getLayout() != null &&
            !(this.main.pointerHorizontal >= this.main.viewWidth || this.main.pointerVertical >= this.main.viewHeight) &&
            this.main.consoleView.isConsoleHit(this.main.pointerHorizontal, this.main.pointerVertical)) {
            return true;
        }

        // The game's plugins see a press over the map view before the map interaction.
        if (pressed && !overMinimap && this.pressPluginButton(button, modifiers)) {
            return true;
        }

        if (button == PointerButton.MIDDLE) {
            return false;
        }

        ClientCamera camera = this.main.requireSession().getCamera();

        // Handles a press or release over the minimap.
        if ((button == PointerButton.LEFT || button == PointerButton.RIGHT) && !pressed &&
            this.minimapDragging) {
            this.minimapDragging = false;

            return true;
        }

        int[] minimap = this.main.consoleView.getMinimapGeometry(this.main.pointerHorizontal, this.main.pointerVertical);
        boolean onMinimap = ConsoleView.isInsideMinimap(minimap);

        if (onMinimap && pressed && (button == PointerButton.LEFT || button == PointerButton.RIGHT)) {
            if (this.main.isGamePopupOpen()) {
                return true;
            }

            // Converts clamped minimap coordinates to map pixels.
            int pointX = Math.clamp(minimap[ConsoleView.MINIMAP_LOCAL_X_INDEX], 0, minimap[ConsoleView.MINIMAP_WIDTH_INDEX] - 1) *
                         minimap[ConsoleView.MINIMAP_PIXEL_SCALE_INDEX];
            int pointY = Math.clamp(minimap[ConsoleView.MINIMAP_LOCAL_Y_INDEX], 0, minimap[ConsoleView.MINIMAP_HEIGHT_INDEX] - 1) *
                         minimap[ConsoleView.MINIMAP_PIXEL_SCALE_INDEX];
            boolean targeting = this.main.consoleModel != null && this.main.consoleModel.getMode().isTargeting();
            boolean placing = this.main.worldInterface != null && this.main.worldInterface.getPlacementType() >= 0;
            boolean alternateHeld = KeyModifier.ALT.isIn(modifiers);

            if (button == PointerButton.LEFT && targeting && this.main.worldInterface != null) {
                LOGGER.debug("UI MINIMAP TARGET: " + this.main.worldInterface.handleMinimapTarget(pointX, pointY, shift));
            } else if (button == PointerButton.LEFT && alternateHeld && this.main.worldInterface != null && this.main.requireSession().isLive()) {
                LOGGER.debug("UI MINIMAP PING: " + this.main.worldInterface.handleMinimapPing(pointX, pointY));
            } else if (button == PointerButton.LEFT || targeting || placing) {
                // A navigation press follows the pointer while the button is held.
                this.minimapDragging = true;
                this.followMinimap();
            } else if (this.main.worldInterface != null) {
                LOGGER.debug("UI MINIMAP ORDER: " + this.main.worldInterface.handleMinimapRightClick(pointX, pointY, shift));
            }

            return true;
        }

        int mapX = Math.round(camera.screenToWorldX(this.main.pointerHorizontal));
        int mapY = Math.round(camera.screenToWorldY(this.main.pointerVertical));

        try {
            if (button == PointerButton.LEFT && pressed && this.main.isOnMapView()) {
                boolean doublePress = this.lastPressDouble;

                LOGGER.debug("UI LEFT PRESS: " + this.main.requireWorldInterface().handleLeftPress(mapX, mapY, shift));

                // Keeps whether the current game-view press was a double press.
                this.pendingDoubleClick = this.main.worldInterface.isDragSelecting() && doublePress;

                return true;
            }

            if (button == PointerButton.LEFT && !pressed && this.main.requireWorldInterface().isDragSelecting()) {
                boolean doubleClick = this.pendingDoubleClick;

                this.pendingDoubleClick = false;

                // The selection is limited to the game view (GameViewport); the box ends at the pointer held inside the play view.
                GameViewport viewport = this.main.gameViewport;
                PointerClip play = viewport.getPlayClip();
                float pointerX = Math.max(play.getLeft(), Math.min(this.main.pointerHorizontal, play.getRight() - 1));
                float pointerY = Math.max(play.getTop(), Math.min(this.main.pointerVertical, play.getBottom() - 1));
                int left = Math.round(camera.screenToWorldX(viewport.getGameLeft()));
                int top = Math.round(camera.getY());
                CommandInput.Result result = this.main.worldInterface.handleLeftRelease(Math.round(camera.screenToWorldX(pointerX)),
                                                                                        Math.round(camera.screenToWorldY(pointerY)),
                                                                                        shift,
                                                                                        KeyModifier.CONTROL.isIn(modifiers),
                                                                                        KeyModifier.ALT.isIn(modifiers),
                                                                                        doubleClick,
                                                                                        left,
                                                                                        top,
                                                                                        left + viewport.getViewTileWidth(camera.getZoom()),
                                                                                        top + viewport.getViewTileHeight(camera.getZoom()));

                LOGGER.debug("UI SELECT: " + result);

                return true;
            }

            if (button == PointerButton.RIGHT && pressed && this.main.isOnMapView()) {
                LOGGER.debug("UI RIGHT CLICK: " + this.main.requireWorldInterface().handleRightClick(mapX, mapY, shift));

                return true;
            }
        } catch (IOException exception) {
            this.main.setAssetWarning(POINTER_WARNING_PREFIX + exception.getMessage());

            return true;
        }

        return false;
    }

    /**
     * Refreshes the dialog stack in drawing order, placing open game and glue popups above the console layers.
     *
     * @return the current dialog stack, or {@code null} when the client context is absent
     * @throws IOException if a dialog asset cannot be loaded
     */
    private @Nullable DialogStack<DialogControl, ScrDialogLayer> syncGameDialogs() throws IOException {
        if (this.main.clientContext == null) {
            return null;
        }

        DialogStack<DialogControl, ScrDialogLayer> stack = this.main.clientContext.getDialogStack();

        for (ScrDialogLayer layer : this.gameDialogLayers) {
            stack.remove(layer);
        }

        this.gameDialogLayers.clear();

        ConsoleLayout layout = this.main.consoleView == null ? null : this.main.consoleView.getLayout();

        if (layout == null) {
            return stack;
        }

        // A replay's hidden panes take their console dialogs out of the list (ReplaySideBar).
        for (ConsoleDialog dialog : layout.getDialogs()) {
            if (this.main.consoleView.isConsoleDialogShown(dialog.getLayout())) {
                this.gameDialogLayers.add(dialog);
            }
        }

        ScrDialogLayer textBox = this.main.consoleView.getTextBoxLayer(this.chatPrompt.isOpen());

        if (textBox != null) {
            this.gameDialogLayers.add(textBox);
        }

        ScrDialogLayer vision = this.main.consoleView.getVisionLayer();

        if (vision != null) {
            this.gameDialogLayers.add(vision);
        }

        ScrDialogLayer alliance = this.main.consoleView.getAllianceLayer();

        if (alliance != null) {
            this.gameDialogLayers.add(alliance);
        }

        ScrDialogLayer messaging = this.main.consoleView.getMessagingLayer();

        if (messaging != null) {
            this.gameDialogLayers.add(messaging);
        }

        ScrDialog popup = this.main.getOpenGamePopup();

        if (popup != null) {
            this.gameDialogLayers.add(this.main.consoleView.getPopupLayer(popup));
        }

        // The waiting-for-players modal layer appears above the console.
        ScrDialog waiting = this.main.networkWaitDialog == null ? null : this.main.networkWaitDialog.getOpenDialog();

        if (waiting != null) {
            this.gameDialogLayers.add(this.main.consoleView.getPopupLayer(waiting));
        }

        // The glue's popups over the game (the options popup and its confirmations), modal dialogs on top.
        this.gameDialogLayers.addAll(this.main.getGluePopupLayers());

        for (ScrDialogLayer layer : this.gameDialogLayers) {
            stack.push(layer);
        }

        return stack;
    }

    /**
     * Stores the hovered control of the open dialogs ({@link DialogStack#updateHover}) for the pointer, and the hovered command card slot (statbtn
     * controls {@code 1..9}) whose tooltip shows.
     */
    void updateDialogHover() {
        ConsoleLayout layout = this.main.requireConsoleView().getLayout();
        boolean inView = this.isPointerInView();
        DialogStack<DialogControl, ScrDialogLayer> stack;

        try {
            stack = layout == null || this.main.worldInterface == null || !this.main.isConsoleShown() ? null : this.syncGameDialogs();
        } catch (IOException exception) {
            this.main.setAssetWarning(DIALOGS_WARNING_PREFIX + exception.getMessage());

            stack = null;
        }

        if (stack == null) {
            this.main.consoleView.setHoveredSlot(-1);

            return;
        }

        float interfaceX = inView ? layout.toInterfaceX(this.main.pointerHorizontal) : Float.NaN;
        float interfaceY = layout.toInterfaceY(this.main.pointerVertical);
        DialogStack.Target<DialogControl, ScrDialogLayer> target = stack.updateHover(interfaceX, interfaceY);
        int slot = -1;

        if (target != null && target.getLayer() instanceof ConsoleDialog console && console.getLayout() == layout.getCommandButtons() &&
            target.getControl() != null && target.getControl().getId() >= 1 && target.getControl().getId() <= CommandCard.SLOT_COUNT) {
            slot = target.getControl().getId() - 1;
        }

        this.main.consoleView.setHoveredSlot(slot);
    }

    /**
     * Routes a left-button event through the dialog stack.
     * Presses mark a control; release actions honor modal capture and command refresh cancellations.
     *
     * @param pressed whether the button went down, else up
     * @param queued  whether shift is held
     * @return {@code true} if a dialog took the event, or {@code null} when the rest of the interface should handle it
     */
    private @Nullable Boolean routeDialogButton(boolean pressed, boolean queued) {
        ConsoleLayout layout = this.main.requireConsoleView().getLayout();

        if (layout == null || this.main.worldInterface == null) {
            return null;
        }

        DialogStack<DialogControl, ScrDialogLayer> stack;
        ScrDialogLayer vision;

        try {
            stack  = this.syncGameDialogs();
            vision = this.main.consoleView.getVisionLayer();
        } catch (IOException exception) {
            this.main.setAssetWarning(DIALOGS_WARNING_PREFIX + exception.getMessage());

            return null;
        }

        if (stack == null) {
            return null;
        }

        float interfaceX = layout.toInterfaceX(this.main.pointerHorizontal);
        float interfaceY = layout.toInterfaceY(this.main.pointerVertical);

        if (pressed) {
            // The Messaging dialog's open list takes the press first (the list control keeps the capture while open).
            try {
                if (this.main.consoleView.pressMessagingList(this.main.pointerHorizontal, this.main.pointerVertical)) {
                    return true;
                }
            } catch (IOException exception) {
                this.main.setAssetWarning(DIALOGS_WARNING_PREFIX + exception.getMessage());
            }

            if (!this.isPointerInView() && stack.getModal() == null) {
                return null;
            }

            // Marks the dialog control under the pointer.
            DialogStack.Target<DialogControl, ScrDialogLayer> target = stack.press(interfaceX, interfaceY);

            if (target == null) {
                return null;
            }

            DialogControl pressedControl = target.getControl();

            if (pressedControl != null && target.getLayer() instanceof ConsoleDialog console) {
                if (console.getLayout() == layout.getCommandButtons()) {
                    // A command button marks its press and acts on release.
                    this.main.consoleView.getHotkeyPress().pressMouse(this.main.requireConsoleModel().getCommandCard()::getPressCancellations,
                                                                      pressedControl.getId() - 1);
                } else {
                    int control = pressedControl.getId();
                    DialogLayout dialog = console.getLayout();

                    if (dialog == layout.getSelectionPanel()) {
                        if (control <= CommandInput.EGG_ADDON_CONTROL) {
                            // Status controls cancel pending unit operations.
                            CommandInput commandInput = this.main.worldInterface.getCommandInput();
                            CommandInput.Result status = commandInput.activateStatusControl(control, this.main.consoleView.getStatusMode());

                            LOGGER.debug("UI STATUS BUTTON: " + control + " " + status);

                            return true;
                        }

                        Unit unit = this.main.consoleView.getPanelControlUnit(control);

                        if (unit == null) {
                            return true;
                        }

                        if (control <= LAST_CARGO_CONTROL) {
                            LOGGER.debug("UI UNLOAD: " + this.main.worldInterface.getCommandInput().unload(unit));

                            return true;
                        }

                        boolean controlHeld = this.main.isWindowKeyDown(Key.LEFT_CONTROL) || this.main.isWindowKeyDown(Key.RIGHT_CONTROL);
                        boolean alternateHeld = this.main.isWindowKeyDown(Key.LEFT_ALT) || this.main.isWindowKeyDown(Key.RIGHT_ALT);

                        CommandInput.Result selection = this.main.worldInterface.selectFromPanel(unit, this.main.consoleView.getPanelUnits(),
                                                                                                 queued, controlHeld, alternateHeld);

                        LOGGER.debug("UI PANEL SELECT: " + selection);
                    } else if (dialog == layout.getPortrait() && !this.main.consoleReplayControls && this.main.consoleModel != null &&
                               !this.main.transmissionTimer.isArmed()) {
                        this.main.worldInterface.centerOnActiveUnit();
                    }
                }
            }

            return true;
        }

        // Applies released dialog controls.
        DialogStack.Target<DialogControl, ScrDialogLayer> released = stack.release(interfaceX, interfaceY);

        if (released != null && released.getLayer() instanceof ConsoleDialog console && console.getLayout() == layout.getCommandButtons()) {
            // A command action requires a retained press and the pointer over its control; Shift is read on release.
            HotkeyPress press = this.main.consoleView.getHotkeyPress();
            DialogControl releasedControl = released.getControl();

            if (releasedControl != null &&
                press.isMousePressKept(this.main.requireConsoleModel().getCommandCard()::getPressCancellations, releasedControl.getId() - 1)) {
                int slot = releasedControl.getId() - 1;
                CommandInput.Result result = this.main.worldInterface.getCommandInput().pressSlot(slot, queued);

                LOGGER.debug("UI BUTTON: slot " + releasedControl.getId() + " " + result);
            }

            press.releaseMouse();

            return true;
        }

        if (released != null) {
            DialogControl releasedControl = released.getControl();

            if (releasedControl != null) {
                ScrDialogLayer layer = released.getLayer();
                int control = releasedControl.getId();

                if (layer instanceof ConsoleDialog console) {
                    if (console.getLayout() == layout.getMenu()) {
                        if (control == CONSOLE_MENU_BUTTON && this.main.gameMenu != null && this.main.session != null) {
                            this.main.gameMenu.toggle();
                        }
                    } else if (console.getLayout() == layout.getMinimap()) {
                        if (control == MINIMAP_TERRAIN_BUTTON) {
                            this.main.consoleView.toggleMinimapTerrain();
                        } else if (control == MINIMAP_DIPLOMACY_BUTTON && !this.main.consoleReplayControls) {
                            this.main.openAllianceDialog();
                        } else if (control == MINIMAP_MESSAGE_BUTTON) {
                            this.main.openMessagingDialog();
                        }
                    }

                    return true;
                }

                try {
                    if (layer == this.main.consoleView.getAllianceLayer()) {
                        if (this.main.consoleView.activateAllianceControl(control) && this.main.session != null) {
                            AllianceDialog alliance = this.main.consoleView.getAllianceDialog();

                            this.main.requireSession().submitAlliances(alliance.getPackedAlliances(), alliance.getVision());
                            LOGGER.debug("UI ALLIANCE: " + Integer.toHexString(alliance.getPackedAlliances()) + " vision " +
                                         Integer.toHexString(alliance.getVision()));
                        }

                        return true;
                    }

                    if (layer == this.main.consoleView.getMessagingLayer()) {
                        int recipient = this.main.consoleView.activateMessagingControl(control);

                        if (recipient >= 0 && this.main.session != null) {
                            this.main.requireSession().setChatRecipient(recipient);
                        }

                        return true;
                    }
                } catch (IOException exception) {
                    this.main.setAssetWarning(DIALOGS_WARNING_PREFIX + exception.getMessage());

                    return true;
                }

                if (layer == vision) {
                    if (this.main.consoleView.activateVisionControl(control) && this.main.session != null) {
                        LocalVision replayVision = this.main.requireSession().getView().getLocalVision();

                        LOGGER.debug("UI VISION: mask " + Integer.toHexString(replayVision.getReplayMask()) + " revealed " +
                                     replayVision.isRevealed());
                    }

                    return true;
                }

                NetworkWaitDialog waiting = this.main.networkWaitDialog;

                if (waiting != null && waiting.getOpenDialog() != null && layer.getDialog() == waiting.getOpenDialog()) {
                    // Drops waiting players through the active session.
                    if (control == NetworkWaitDialog.DROP && this.main.session != null) {
                        this.main.requireSession().dropWaitingPlayers();
                    }

                    return true;
                }

                if (this.main.endPhase == Main.END_BOX) {
                    this.main.pressOutcomeBox(control);

                    return true;
                }

                if (this.main.gameMenu != null) {
                    this.main.applyMenuResult(this.main.gameMenu.press(control));
                }
            }

            return true;
        }

        return stack.getModal() != null ? Boolean.TRUE : null;
    }

    /**
     * Requests a replay seek from an interior point on the seek bar.
     *
     * @param layout the console layout
     * @return {@code true} if the pointer was on the seek bar, {@code false} otherwise
     */
    private boolean seekOnReplayBar(@NotNull ConsoleLayout layout) {
        if (!this.main.requireConsoleView().isConsoleDialogShown(layout.getCommandButtons())) {
            return false;
        }

        DialogLayout.Control bar;

        try {
            bar = layout.getCommandButtons().getControl(REPLAY_SEEK_CONTROL);
        } catch (RuntimeException exception) {
            return false;
        }

        float interfaceX = layout.toInterfaceX(this.main.pointerHorizontal);
        float interfaceY = layout.toInterfaceY(this.main.pointerVertical);
        int right = bar.getX() + bar.getWidth() - 1;

        if (interfaceX < bar.getX() + REPLAY_SEEK_LEFT_PADDING || interfaceX > right - REPLAY_SEEK_RIGHT_PADDING ||
            interfaceY < bar.getY() || interfaceY >= bar.getY() + bar.getHeight()) {
            return false;
        }

        long end = this.main.requireSession().getReplay().getHeader().getFrames();
        int width = right - bar.getX();
        long frame = width > 0 ? Math.min(end, (long) (end * (interfaceX - bar.getX()) / width)) : this.main.requireSession().getLastCompletedFrame();
        // The bar consumes the click even when the seek command is not sent.
        boolean sent = this.main.playback.canSendReplaySeek(frame, this.main.requireSession().getLastCompletedFrame(), end,
                                                            this.main.requireSession().getReplay().getHeader().getSpeed());

        if (sent) {
            this.main.requireSession().seekReplay((int) frame);
        }

        LOGGER.debug("UI SEEK: frame " + frame + (sent ? "" : " (not sent)"));

        return true;
    }

    /**
     * Routes a left button event over the replay side bar ({@link ReplaySideBar}): a press on the bar is the bar's, and the release over the pressed
     * icon runs it, as the page's {@code onClick}.
     *
     * @param button  the button
     * @param pressed whether the button went down, else up
     * @return {@code true} if the bar took the event, {@code false} otherwise
     */
    private boolean clickSideBar(@NotNull PointerButton button, boolean pressed) {
        if (button != PointerButton.LEFT || this.main.consoleView == null) {
            return false;
        }

        ReplaySideBar sideBar = this.main.consoleView.getReplaySideBar();
        ReplaySideBar.Button under = this.main.consoleView.findSideBarButton(this.main.pointerHorizontal, this.main.pointerVertical);

        if (pressed) {
            if (!this.main.consoleView.isSideBarHit(this.main.pointerHorizontal, this.main.pointerVertical)) {
                return false;
            }

            sideBar.press(under);

            return true;
        }

        if (sideBar.getPressed() == null) {
            return false;
        }

        this.performSideBarAction(sideBar.release(under));

        return true;
    }

    /**
     * Applies a key to the replay side bar's hotkeys ({@link ReplaySideBar#pressKey}), only in the replay viewer's console.
     *
     * @param key       the key
     * @param modifiers the modifier mask ({@link KeyModifier#getMask()} bits)
     * @return {@code true} if the key was the bar's, {@code false} otherwise
     */
    private boolean pressSideBarKey(@NotNull Key key, int modifiers) {
        if (this.main.consoleView == null || this.main.session == null || this.main.requireSession().isLive() || !this.main.consoleReplayControls ||
            !this.main.isConsoleShown()) {
            return false;
        }

        ReplaySideBar.Action action = this.main.consoleView.getReplaySideBar().pressKey(key, modifiers);

        this.performSideBarAction(action);

        return action != ReplaySideBar.Action.NONE;
    }

    /**
     * Applies the replay side-bar action and ends minimap dragging when its pane is hidden.
     *
     * @param action the side-bar action
     */
    private void performSideBarAction(ReplaySideBar.@NotNull Action action) {
        switch (action) {
            case OPEN_DIPLOMACY -> this.main.openVisionDialog();
            case OPEN_MENU -> this.main.openGameMenuPage(GameMenu.Page.GAME);
            case TOGGLED -> {
                this.minimapDragging = this.minimapDragging && this.main.requireConsoleView().getReplaySideBar().isShown(ReplaySideBar.Pane.MINIMAP);

                LOGGER.debug("UI SIDE BAR: panes changed");
            }
            case NONE -> {
            }
        }
    }
}
