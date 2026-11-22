package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextEditOperation;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.input.KeyModifier;
import org.fogofwarlabs.sc4j.scr.client.ui.command.CommandInput;
import org.fogofwarlabs.sc4j.scr.client.ui.console.UiClockUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.input.GameAccelerators;
import org.fogofwarlabs.sc4j.scr.client.ui.menu.GameMenu;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.jetbrains.annotations.NotNull;

/**
 * Matches game accelerators to popup, chat and play modes, then applies wait and pause guards in order.
 */
final class GameAcceleratorHandler {
    private static final @NotNull Logger LOGGER = Logger.of(GameAcceleratorHandler.class);

    /**
     * The first supported live game speed.
     */
    private static final int SLOWEST_SPEED = 0;

    /**
     * Resolves the play accelerator that depends on the session: the speed increase opens the chat history in a network game.
     *
     * @param identifier  the accelerator identifier
     * @param multiplayer whether the session is a network game
     * @return the identifier of the action the accelerator runs
     */
    private static int resolvePlayAction(int identifier, boolean multiplayer) {
        if (identifier == GameAccelerators.INCREASE_SPEED_OR_CHAT_HISTORY) {
            return multiplayer ? GameAccelerators.CHAT_HISTORY : GameAccelerators.INCREASE_SPEED;
        }

        return identifier;
    }

    private final @NotNull Main main;

    /**
     * Creates a {@link GameAcceleratorHandler} instance.
     *
     * @param main the client that owns the session and the interface
     */
    GameAcceleratorHandler(@NotNull Main main) {
        this.main = main;
    }

    /**
     * @return popup mode while a game popup, the menus' popup over the game or a trigger pause is active, chat mode while the prompt is open,
     * or play mode
     */
    @NotNull GameAccelerators.Mode getMode() {
        ClientSession session = this.main.session;

        if (this.main.isGamePopupOpen() || this.main.isGluePopupOverGame() || session != null && session.isTriggerPaused()) {
            return GameAccelerators.Mode.POPUP;
        }

        if (this.main.gameClientState.getChatPrompt().isOpen()) {
            return GameAccelerators.Mode.CHAT;
        }

        return GameAccelerators.Mode.PLAY;
    }

    /**
     * Runs the accelerator of a key of a live game.
     *
     * @param key       the key
     * @param modifiers the modifier mask ({@link KeyModifier#getMask()} bits, the same Shift, Ctrl and Alt bits as the table entries)
     * @param repeat    whether the event is a key repeat
     * @return {@code true} if the key is an accelerator of the active table (the key is then consumed), {@code false} otherwise
     */
    boolean handle(@NotNull Key key, int modifiers, boolean repeat) {
        GameAccelerators.Mode mode = this.getMode();
        GameAccelerators.Entry entry = GameAccelerators.find(mode, key, modifiers, repeat);

        if (entry == null) {
            return false;
        }

        if (mode == GameAccelerators.Mode.POPUP) {
            this.runPopup(entry.getIdentifier());
        } else {
            this.runPlay(entry.getIdentifier());
        }

        return true;
    }

    /**
     * Tells whether a key press in play mode runs an accelerator of the interface rather than of the game ({@link GameAccelerators#isGameAction}):
     * the menus and their dialogs, the sound, music and graphics toggles, the chat history and the other interface switches.
     *
     * @param key       the key
     * @param modifiers the modifier mask ({@link KeyModifier#getMask()} bits)
     * @return {@code true} if the press runs an interface accelerator, {@code false} if it runs a game accelerator or none
     */
    boolean isInterfaceAccelerator(@NotNull Key key, int modifiers) {
        GameAccelerators.Entry entry = GameAccelerators.find(GameAccelerators.Mode.PLAY, key, modifiers, false);
        ClientSession session = this.main.session;

        return entry != null && !GameAccelerators.isGameAction(resolvePlayAction(entry.getIdentifier(), session != null && session.isNetworkGame()));
    }

    /**
     * Runs actions available in popup mode; the edit-operation identifiers go to the focused edit of the open popup.
     *
     * @param identifier the accelerator identifier
     */
    private void runPopup(int identifier) {
        if (identifier >= GameAccelerators.EDIT_SELECT_ALL && identifier <= GameAccelerators.EDIT_REDO) {
            this.main.applyPopupEditOperation(TextEditOperation.values()[identifier - GameAccelerators.EDIT_SELECT_ALL]);

            return;
        }

        this.runAlways(identifier);
    }

    /**
     * Runs always, menu and in-game actions in their wait and pause order.
     *
     * @param identifier the accelerator identifier
     */
    private void runPlay(int identifier) {
        ClientSession session = this.main.session;

        if (session == null) {
            return;
        }

        boolean multiplayer = session.isNetworkGame();
        int action = resolvePlayAction(identifier, multiplayer);

        this.runAlways(action);

        if (this.main.isNetworkWaitDialogOpen()) {
            return;
        }

        this.runMenus(action);

        if (session.isGamePaused() || this.main.isSinglePlayerPaused()) {
            return;
        }

        this.runInGame(session, action, multiplayer);

        // Plain digit recall follows the guarded in-game actions.
        if (GameAccelerators.isInGroupRange(action, GameAccelerators.GROUP_RECALL)) {
            this.pressControlGroupKey(GameAccelerators.getGroupDigit(action, GameAccelerators.GROUP_RECALL), false, false, false);
        }
    }

    /**
     * Runs the first switch of both handlers in every state. The frame and turn rate toggles of the tables run before any screen, in the client's key
     * dispatch ({@link org.fogofwarlabs.sc4j.scr.client.ui.overlay.StatusOverlay#findToggle}).
     *
     * @param identifier the accelerator identifier
     */
    private void runAlways(int identifier) {
        switch (identifier) {
            case GameAccelerators.TOGGLE_MUSIC -> this.main.toggleMusicSetting();
            case GameAccelerators.TOGGLE_SOUND -> this.main.toggleSoundEffectsSetting();
            case GameAccelerators.GRAPHICS -> this.main.cycleSceneQuality();
            case GameAccelerators.CHAT_HISTORY -> this.main.openChatHistory();
            case GameAccelerators.MOUSE_CONFINE -> this.main.toggleMouseConfine();
            default -> {
            }
        }
    }

    /**
     * Runs the second switch of the play handler: dialogs and actions that do not change the game.
     *
     * @param identifier the accelerator identifier
     */
    private void runMenus(int identifier) {
        switch (identifier) {
            case GameAccelerators.EXIT_PROGRAM -> this.main.openGameMenuPage(GameMenu.Page.EXIT_PROGRAM);
            case GameAccelerators.QUIT_MISSION -> this.main.openGameMenuPage(GameMenu.Page.QUIT_MISSION);
            case GameAccelerators.OPTIONS -> this.main.openOptionsOverGame();
            case GameAccelerators.HELP -> this.main.openGameMenuPage(GameMenu.Page.HELP);
            case GameAccelerators.LEADERBOARD_PLAYERS -> this.main.toggleLeaderboardPlayers();
            // Save and load dialogs open at depth one so Cancel closes them.
            case GameAccelerators.SAVE_GAME -> this.main.openSaveGameDialog(false);
            case GameAccelerators.LOAD_GAME -> this.main.openLoadGameDialog(false);
            case GameAccelerators.GAME_MENU -> this.main.openGameMenuPage(GameMenu.Page.GAME);
            case GameAccelerators.RESET_ZOOM -> this.main.resetZoom();
            // The provider-specific action has no handler here.
            case GameAccelerators.BATTLE_NET_ACTION -> {
            }
            default -> {
            }
        }
    }

    /**
     * Runs the third switch of the play handler unless the game is paused.
     *
     * @param session     the running session
     * @param identifier  the accelerator identifier
     * @param multiplayer whether the session is a network game
     */
    private void runInGame(@NotNull ClientSession session, int identifier, boolean multiplayer) {
        if (GameAccelerators.isInGroupRange(identifier, GameAccelerators.GROUP_ADD)) {
            this.pressControlGroupKey(GameAccelerators.getGroupDigit(identifier, GameAccelerators.GROUP_ADD), false, true, false);

            return;
        }

        if (GameAccelerators.isInGroupRange(identifier, GameAccelerators.GROUP_ASSIGN)) {
            this.pressControlGroupKey(GameAccelerators.getGroupDigit(identifier, GameAccelerators.GROUP_ASSIGN), true, false, false);

            return;
        }

        if (GameAccelerators.isInGroupRange(identifier, GameAccelerators.GROUP_CENTER)) {
            this.pressControlGroupKey(GameAccelerators.getGroupDigit(identifier, GameAccelerators.GROUP_CENTER), false, false, true);

            return;
        }

        if (identifier >= GameAccelerators.VIEW_STORE && identifier < GameAccelerators.VIEW_STORE + GameAccelerators.VIEW_SLOTS) {
            this.main.storeView(identifier - GameAccelerators.VIEW_STORE);

            return;
        }

        if (identifier >= GameAccelerators.VIEW_RECALL && identifier < GameAccelerators.VIEW_RECALL + GameAccelerators.VIEW_SLOTS) {
            this.main.recallView(identifier - GameAccelerators.VIEW_RECALL);

            return;
        }

        switch (identifier) {
            case GameAccelerators.CENTER_ON_UNIT -> {
                if (this.main.worldInterface != null) {
                    this.main.worldInterface.centerOnActiveUnit();
                }
            }
            // Live speed changes are restricted to single player; replay keys press the viewer's Speed Up and Slow Down.
            case GameAccelerators.INCREASE_SPEED -> {
                if (!session.isLive()) {
                    session.increaseReplaySpeed();
                } else if (!multiplayer && session.getGameSpeed() < GameSpeed.FASTEST) {
                    session.requestGameSpeed(session.getGameSpeed() + 1);
                }
            }
            case GameAccelerators.DECREASE_SPEED -> {
                if (!session.isLive()) {
                    session.decreaseReplaySpeed();
                } else if (!multiplayer && session.getGameSpeed() != SLOWEST_SPEED) {
                    session.requestGameSpeed(session.getGameSpeed() - 1);
                }
            }
            default -> {
            }
        }
    }

    /**
     * Passes a control group key to the world interface while the console is shown.
     *
     * @param digit   the control group digit
     * @param control whether the group is assigned
     * @param shift   whether the selection is added to the group
     * @param alt     whether the camera centers on the group
     */
    private void pressControlGroupKey(int digit, boolean control, boolean shift, boolean alt) {
        if (this.main.isConsoleShown() && this.main.worldInterface != null) {
            CommandInput.Result result = this.main.worldInterface.handleControlGroupKey(digit, control, shift, alt,
                                                                                        UiClockUtils.getHostTimeMilliseconds());

            LOGGER.debug("UI CONTROL GROUP: " + result);
        }
    }
}
