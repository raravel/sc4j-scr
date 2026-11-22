package org.fogofwarlabs.sc4j.scr.client.glue.menu;

import org.fogofwarlabs.sc4j.scr.client.glue.GlueContext;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueSceneRegistry;
import org.fogofwarlabs.sc4j.scr.client.glue.campaign.CampaignScreen;
import org.fogofwarlabs.sc4j.scr.client.glue.campaign.CinematicScreen;
import org.fogofwarlabs.sc4j.scr.client.glue.campaign.EstablishingShotScreen;
import org.fogofwarlabs.sc4j.scr.client.glue.campaign.LoadSavedGameScreen;
import org.fogofwarlabs.sc4j.scr.client.glue.lobby.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Registers every glue destination and its intro, credits and ending transitions.
 */
public final class NativeGlueScenesUtils {
    /**
     * The music of Show Credits.
     */
    private static final @NotNull String CREDITS_MUSIC = "terran2";
    /**
     * The music of the ending.
     */
    private static final @NotNull String ENDING_MUSIC = "protoss3";
    /**
     * The epilogue script of the original ending.
     */
    private static final @NotNull String EPILOGUE = "epilog";
    /**
     * The epilogue script of the expansion ending.
     */
    private static final @NotNull String EXPANSION_EPILOGUE = "epilogX";

    /**
     * Registers the menu, campaign, multiplayer and cinematic destinations.
     *
     * @param scenes the scene registry of the glue
     */
    public static void register(@NotNull GlueSceneRegistry scenes) {
        scenes.register(GlueNavigator.Destination.MAIN_MENU, context -> new MainMenuScreen());
        scenes.register(GlueNavigator.Destination.LOGIN, context -> new LoginScreen(GlueNavigator.Destination.MAIN_MENU));
        scenes.register(GlueNavigator.Destination.CAMPAIGN, context -> new CampaignScreen(context.isExpansion()));
        scenes.register(GlueNavigator.Destination.CUSTOM_GAME, context -> new CustomGameScreen());
        scenes.register(GlueNavigator.Destination.REPLAY_LIST, context -> new ReplayListScreen());
        scenes.register(GlueNavigator.Destination.SAVED_GAME_LIST, context -> new LoadSavedGameScreen());
        scenes.register(GlueNavigator.Destination.CONNECTION, context -> new ConnectionScreen());
        scenes.register(GlueNavigator.Destination.GAME_LIST, context -> context.isBeaconLinkSelected() ? new RoomListScreen() : new GameListScreen());
        scenes.register(GlueNavigator.Destination.CREATE_GAME, context -> new CreateGameScreen());
        scenes.register(GlueNavigator.Destination.GAME_LOBBY, context -> new GameLobbyScreen());
        scenes.register(GlueNavigator.Destination.CHANNEL_CHAT, context -> new ChannelChatScreen());

        scenes.register(GlueNavigator.Destination.CREDITS, context -> createCreditsScreen(context, CREDITS_MUSIC));

        scenes.register(GlueNavigator.Destination.ENDING,
                        context -> new EstablishingShotScreen(context.isExpansion() ? EXPANSION_EPILOGUE : EPILOGUE,
                                                              ENDING_MUSIC,
                                                              () -> context.getNavigator().replace(createCreditsScreen(context, null))));
        scenes.register(GlueNavigator.Destination.HOTKEYS, context -> new HotkeysScreen());
        scenes.register(GlueNavigator.Destination.INTRO,
                        context -> new CinematicScreen(List.of(CinematicScreen.BLIZZARD,
                                                               context.isExpansion() ? CinematicScreen.EXPANSION_INTRO : CinematicScreen.INTRO),
                                                       createMainMenuAction(context)));
    }

    /**
     * Creates a {@link CreditsScreen} instance that returns to the main menu when the credits end.
     *
     * @param context the glue context of the transition
     * @param music   the glue music track the credits start, or {@code null} to keep the music that plays
     * @return the created {@link CreditsScreen} instance
     */
    private static @NotNull CreditsScreen createCreditsScreen(@NotNull GlueContext context, @Nullable String music) {
        return new CreditsScreen(music, createMainMenuAction(context));
    }

    /**
     * Creates a {@link Runnable} instance that shows {@link GlueNavigator.Destination#MAIN_MENU} after a credits or intro transition.
     *
     * @param context the glue context of the transition
     * @return the created {@link Runnable} instance
     */
    private static @NotNull Runnable createMainMenuAction(@NotNull GlueContext context) {
        return () -> context.getNavigator().show(GlueNavigator.Destination.MAIN_MENU);
    }

    /**
     * Creates a {@link NativeGlueScenesUtils} instance.
     */
    private NativeGlueScenesUtils() {
    }
}
