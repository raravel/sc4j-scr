package org.fogofwarlabs.sc4j.scr.client.glue;

import org.fogofwarlabs.sc4j.scr.client.GameCommunityChat;
import org.fogofwarlabs.sc4j.scr.game.session.setup.GameTemplate;
import org.fogofwarlabs.sc4j.scr.net.lan.LanClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * A game the glue asks the window to start, and the glue screen to return to after it.
 */
public final class GlueGameRequest {
    /**
     * The kinds of games the glue starts.
     */
    public enum Kind {
        /**
         * A mission of the campaign screens.
         */
        CAMPAIGN,
        /**
         * A replay of the replay list.
         */
        REPLAY,
        /**
         * A melee game of the custom game screen: a game type template that does not use the map settings ({@link GlueGameRequest#getTemplate}).
         */
        MELEE,
        /**
         * A Use Map Settings game of the custom game screen.
         */
        USE_MAP_SETTINGS,
        /**
         * A LAN game whose lobby counted down ({@link GlueGameRequest#getLanClient()}).
         */
        NETWORK,
        /**
         * A saved game of the load saved game screen or the game's Load Game ({@link GlueGameRequest#getPath()} names the file).
         */
        SAVED_GAME
    }

    /**
     * Creates a {@link GlueGameRequest} instance for a campaign mission.
     *
     * @param playerName the single player ID
     * @param expansion  whether the game is Brood War
     * @param mission    the mission index of {@code arr\mapdata.dat}
     * @return the created {@link GlueGameRequest} instance
     */
    public static @NotNull GlueGameRequest createCampaign(@NotNull String playerName, boolean expansion, int mission) {
        return new GlueGameRequest(Kind.CAMPAIGN, null, playerName, expansion, mission, null, 0, new int[0]);
    }

    /**
     * Creates a {@link GlueGameRequest} instance for a replay.
     *
     * @param replay     the replay file
     * @param playerName the single player ID
     * @param expansion  whether Brood War was chosen
     * @return the created {@link GlueGameRequest} instance
     */
    public static @NotNull GlueGameRequest createReplay(@NotNull Path replay, @NotNull String playerName, boolean expansion) {
        return new GlueGameRequest(Kind.REPLAY, replay, playerName, expansion, 0, null, 0, new int[0]);
    }

    /**
     * Creates a {@link GlueGameRequest} instance to watch the completed game's replay and then continue at the score screen's return destination.
     *
     * @param replay            the game's replay file
     * @param playerName        the single player ID
     * @param expansion         whether Brood War was chosen
     * @param returnDestination the screen to continue at after the replay
     * @return the created {@link GlueGameRequest} instance
     */
    public static @NotNull GlueGameRequest createReplayWithReturnDestination(@NotNull Path replay,
                                                                             @NotNull String playerName,
                                                                             boolean expansion,
                                                                             GlueNavigator.@NotNull Destination returnDestination) {
        GlueGameRequest request = createReplay(replay, playerName, expansion);

        request.returnDestination = returnDestination;

        return request;
    }

    /**
     * Creates a {@link GlueGameRequest} instance for a custom game.
     *
     * @param map           the map file
     * @param playerName    the single player ID
     * @param expansion     whether Brood War was chosen
     * @param template      the game type template of the create screen
     * @param race          the player's race byte
     * @param computerRaces the computer race bytes in slot order (melee)
     * @return the created {@link GlueGameRequest} instance
     */
    public static @NotNull GlueGameRequest createCustom(@NotNull Path map,
                                                        @NotNull String playerName,
                                                        boolean expansion,
                                                        @NotNull GameTemplate template,
                                                        int race,
                                                        int @NotNull [] computerRaces) {
        return new GlueGameRequest(template.isUseMapSettings() ? Kind.USE_MAP_SETTINGS : Kind.MELEE,
                                   map,
                                   playerName,
                                   expansion,
                                   0,
                                   template,
                                   race,
                                   computerRaces);
    }

    /**
     * Creates a {@link GlueGameRequest} instance for a LAN game whose lobby reached the game start.
     *
     * @param map               the local copy of the lobby's map
     * @param playerName        the local player's name
     * @param expansion         whether Brood War was chosen
     * @param lanClient         the LAN client with the lobby and the session
     * @param communityChat     the Beacon.Link community part of the game, or {@code null} for a game without the channel chat
     * @param speed             the game speed, {@code 0..6}
     * @param returnDestination the screen to continue at after the game: the multiplayer screens' home
     * @return the created {@link GlueGameRequest} instance
     */
    public static @NotNull GlueGameRequest createNetwork(@NotNull Path map,
                                                         @NotNull String playerName,
                                                         boolean expansion,
                                                         @NotNull LanClient lanClient,
                                                         @Nullable GameCommunityChat communityChat,
                                                         int speed,
                                                         GlueNavigator.@NotNull Destination returnDestination) {
        GlueGameRequest request = new GlueGameRequest(Kind.NETWORK, map, playerName, expansion, 0, null, 0, new int[0]);

        request.lanClient         = lanClient;
        request.communityChat     = communityChat;
        request.speed             = speed;
        request.returnDestination = returnDestination;

        return request;
    }

    /**
     * Creates a {@link GlueGameRequest} instance to restore a saved game's setup and state, keeping its campaign mission for the later campaign walk.
     *
     * @param file            the saved game file
     * @param playerName      the single player ID
     * @param expansion       whether the saved game is Brood War
     * @param campaignMission the saved game's campaign mission index, {@code 0} for none
     * @return the created {@link GlueGameRequest} instance
     */
    public static @NotNull GlueGameRequest createSavedGame(@NotNull Path file, @NotNull String playerName, boolean expansion, int campaignMission) {
        GlueGameRequest request = new GlueGameRequest(Kind.SAVED_GAME, file, playerName, expansion, campaignMission, null, 0, new int[0]);

        request.returnDestination = GlueNavigator.Destination.CAMPAIGN;

        return request;
    }

    private final @NotNull Kind kind;
    private final @Nullable Path path;

    private final @NotNull String playerName;
    private final boolean expansion;

    private final int campaignMission;

    private final @Nullable GameTemplate template;
    private final int race;
    private final int @NotNull [] computerRaces;

    private @Nullable LanClient lanClient;
    private @Nullable GameCommunityChat communityChat;
    private int speed;
    private GlueNavigator.@Nullable Destination returnDestination;

    private GlueGameRequest(@NotNull Kind kind,
                            @Nullable Path path,
                            @NotNull String playerName,
                            boolean expansion,
                            int campaignMission,
                            @Nullable GameTemplate template,
                            int race,
                            int @NotNull [] computerRaces) {
        this.kind = kind;
        this.path = path;

        this.playerName = playerName;
        this.expansion  = expansion;

        this.campaignMission = campaignMission;

        this.template      = template;
        this.race          = race;
        this.computerRaces = computerRaces.clone();

        this.lanClient         = null;
        this.communityChat     = null;
        this.speed             = 0;
        this.returnDestination = null;
    }

    /**
     * @return the kind of game
     */
    public @NotNull Kind getKind() {
        return this.kind;
    }

    /**
     * @return the replay, map or saved-game file, or {@code null} for a campaign mission
     */
    public @Nullable Path getPath() {
        return this.path;
    }

    /**
     * @return the player name
     */
    public @NotNull String getPlayerName() {
        return this.playerName;
    }

    /**
     * @return {@code true} if the game is Brood War instead of the original game, {@code false} otherwise
     */
    public boolean isExpansion() {
        return this.expansion;
    }

    /**
     * @return the campaign mission index ({@link Kind#CAMPAIGN})
     */
    public int getCampaignMission() {
        return this.campaignMission;
    }

    /**
     * @return the game type template of a custom game, or {@code null} for another kind
     */
    public @Nullable GameTemplate getTemplate() {
        return this.template;
    }

    /**
     * @return the player's race byte ({@link Kind#MELEE}, {@link Kind#USE_MAP_SETTINGS})
     */
    public int getRace() {
        return this.race;
    }

    /**
     * @return the computer race bytes in slot order ({@link Kind#MELEE})
     */
    public int @NotNull [] getComputerRaces() {
        return this.computerRaces.clone();
    }

    /**
     * @return the LAN client of a {@link Kind#NETWORK} game, or {@code null} for another kind
     */
    public @Nullable LanClient getLanClient() {
        return this.lanClient;
    }

    /**
     * @return the Beacon.Link community part of a {@link Kind#NETWORK} game, or {@code null} for a game without the channel chat or another kind
     */
    public @Nullable GameCommunityChat getCommunityChat() {
        return this.communityChat;
    }

    /**
     * @return the game speed of a {@link Kind#NETWORK} game
     */
    public int getSpeed() {
        return this.speed;
    }

    /**
     * @return the screen to continue at after a watched replay ({@link #createReplayWithReturnDestination}), or {@code null} for the screen of
     * the request's kind
     */
    public GlueNavigator.@Nullable Destination getReturnDestination() {
        return this.returnDestination;
    }


    /**
     * Creates a {@link GlueGameRequest} instance for another map with this custom game's setup.
     *
     * @param map the map file
     * @return the created {@link GlueGameRequest} instance
     */
    public @NotNull GlueGameRequest createWithMap(@NotNull Path map) {
        return new GlueGameRequest(this.kind,
                                   map,
                                   this.playerName,
                                   this.expansion,
                                   this.campaignMission,
                                   this.template,
                                   this.race,
                                   this.computerRaces);
    }
}
