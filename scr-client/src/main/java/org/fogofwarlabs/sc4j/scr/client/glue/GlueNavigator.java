package org.fogofwarlabs.sc4j.scr.client.glue;

import org.fogofwarlabs.sc4j.scr.client.glue.campaign.CampaignEpisodes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Routes a glue screen to another screen, a game or program exit.
 */
public interface GlueNavigator {
    /**
     * The glue destinations handled by the client.
     */
    enum Destination {
        /**
         * The main menu.
         */
        MAIN_MENU,
        /**
         * The local ID choice of a run without an account, followed by the main menu.
         */
        LOGIN,
        /**
         * The campaign selection for the chosen game.
         */
        CAMPAIGN,
        /**
         * The single-player custom game screen.
         */
        CUSTOM_GAME,
        /**
         * The local replay list.
         */
        REPLAY_LIST,
        /**
         * The campaign saved-game list.
         */
        SAVED_GAME_LIST,
        /**
         * The multiplayer connection choice.
         */
        CONNECTION,
        /**
         * The LAN game list.
         */
        GAME_LIST,
        /**
         * The multiplayer create-game screen.
         */
        CREATE_GAME,
        /**
         * The game lobby.
         */
        GAME_LOBBY,
        /**
         * The Beacon.Link channel chat.
         */
        CHANNEL_CHAT,
        /**
         * The logo and chosen game intro, followed by the main menu.
         */
        INTRO,
        /**
         * The campaign epilogue and credits, followed by the main menu.
         */
        ENDING,
        /**
         * The credits, followed by the main menu.
         */
        CREDITS,
        /**
         * The hotkey editor, followed by the main menu.
         */
        HOTKEYS
    }

    /**
     * Replaces the current screen.
     *
     * @param destination the next screen
     */
    void show(@NotNull Destination destination);

    /**
     * Replaces the current screen with a screen the caller created, for a step of the flow that is not a destination of
     * its own (for example the credits after the ending).
     *
     * @param screen the next screen
     */
    void replace(@NotNull GlueScreen screen);

    /**
     * Continues the campaign walk at an episode entry: a cinematic entry plays its clip and continues with the next
     * entry, a mission entry opens its ready room, the end of the table returns to the campaign screen.
     *
     * @param entry the entry, or {@code null} at the end of the episode
     */
    void playCampaign(CampaignEpisodes.@Nullable Entry entry);

    /**
     * Leaves the glue for a game.
     *
     * @param request the game to start
     */
    void start(@NotNull GlueGameRequest request);

    /**
     * Opens the ready room for a custom Use Map Settings game; its Start button starts the game.
     *
     * @param request the custom game
     */
    void openReadyRoom(@NotNull GlueGameRequest request);

    /**
     * Leaves the program (main menu Exit).
     */
    void exit();

    /**
     * Shows an open popup over the current screen as the top modal scene of the glue's scene stack, which updates and draws it.
     *
     * @param popup the open popup
     */
    void addPopup(@NotNull GlueScreen popup);

    /**
     * Closes a popup shown with {@link #addPopup}; does nothing if the scene stack closed it already.
     *
     * @param popup the popup
     */
    void closePopup(@NotNull GlueScreen popup);
}
