package org.fogofwarlabs.sc4j.scr.client.glue;

import org.fogofwarlabs.sc4j.engine.client.dialog.DialogStack;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextClipboard;
import org.fogofwarlabs.sc4j.engine.client.host.HostWindow;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.BeaconLinkAccess;
import org.fogofwarlabs.sc4j.scr.client.ClientContext;
import org.fogofwarlabs.sc4j.scr.client.ClientPlayer;
import org.fogofwarlabs.sc4j.scr.client.ReleaseStatus;
import org.fogofwarlabs.sc4j.scr.client.configuration.InstallLayout;
import org.fogofwarlabs.sc4j.scr.client.glue.campaign.CampaignMissionNames;
import org.fogofwarlabs.sc4j.scr.client.glue.lobby.RoomFilter;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialogLayer;
import org.fogofwarlabs.sc4j.scr.client.ui.portrait.PortraitTables;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceTimers;
import org.fogofwarlabs.sc4j.scr.net.lan.LanClient;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceAssets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Shares drawing, audio, localized strings, navigation and client resources between glue screens.
 * Keeps the selected game, map and multiplayer session for the glue flow, and the run player's character file.
 */
public final class GlueContext {
    /**
     * The length of a text locale's language part ({@code ko} of {@code koKR}).
     */
    private static final int LANGUAGE_LENGTH = 2;

    private final @NotNull ClientContext client;

    private final @NotNull GlueCanvas canvas;
    private final @NotNull GlueAudio audio;
    private final @NotNull String locale;
    private final @NotNull GluText controlText;
    private final @NotNull GluText gluText;
    private final @NotNull GluText networkText;
    private final @NotNull CampaignMissionNames missionNames;
    private final @NotNull GlueNavigator navigator;
    private final @NotNull GlueFlicLayout flicLayout;
    private final @NotNull GlueFolders folders;
    private final @NotNull GlueSettings settings;

    private boolean expansion;
    private @Nullable Path lastMap;
    private boolean mapCompatibility;
    private @Nullable CharacterFile character;

    private boolean multiplayer;
    private boolean beaconLinkSelected;
    private @Nullable LanClient lanClient;
    private @Nullable ChannelChat channelChat;
    private @NotNull RoomFilter roomFilter;

    /**
     * Creates a {@link GlueContext} instance.
     *
     * @param client       the client's resources: the dialog list, the interface timers, the global generator and the tileset data
     * @param canvas       the drawing surface
     * @param audio        the glue audio
     * @param locale       the text locale of the strings, for example {@code koKR}
     * @param controlText  the dialog control strings
     * @param gluText      the glue strings
     * @param networkText  the network strings
     * @param missionNames the campaign episode entry names ({@code gluHist.json})
     * @param navigator    the glue flow
     * @param flicLayout   the high definition video placements ({@code rez/flc.json})
     * @param folders      the install folders of IDs, maps and replays
     * @param settings     the menu choices kept across starts
     */
    public GlueContext(@NotNull ClientContext client,
                       @NotNull GlueCanvas canvas,
                       @NotNull GlueAudio audio,
                       @NotNull String locale,
                       @NotNull GluText controlText, @NotNull GluText gluText, @NotNull GluText networkText,
                       @NotNull CampaignMissionNames missionNames,
                       @NotNull GlueNavigator navigator,
                       @NotNull GlueFlicLayout flicLayout,
                       @NotNull GlueFolders folders,
                       @NotNull GlueSettings settings) {
        this.client = client;

        this.canvas       = canvas;
        this.audio        = audio;
        this.locale       = locale;
        this.controlText  = controlText;
        this.gluText      = gluText;
        this.networkText  = networkText;
        this.missionNames = missionNames;
        this.navigator    = navigator;
        this.flicLayout   = flicLayout;
        this.folders      = folders;
        this.settings     = settings;

        this.expansion        = false;
        this.lastMap          = null;
        this.mapCompatibility = false;
        this.character        = null;

        this.multiplayer        = false;
        this.beaconLinkSelected = false;
        this.lanClient          = null;
        this.channelChat        = null;
        this.roomFilter         = new RoomFilter();
    }

    /**
     * @return the client window, or {@code null} while it does not exist
     */
    public @Nullable HostWindow getWindow() {
        return this.client.getWindow();
    }

    /**
     * @return the system clipboard of the client window
     */
    public @NotNull TextClipboard getClipboard() {
        return this.client.getClipboard();
    }

    /**
     * @return the selected client directories
     */
    public @NotNull InstallLayout getInstallLayout() {
        return this.client.getInstallLayout();
    }

    /**
     * @return the client's one list of open dialogs: the glue screen and its popups, and over a game the console dialogs
     */
    public @NotNull DialogStack<DialogControl, ScrDialogLayer> getDialogStack() {
        return this.client.getDialogStack();
    }

    /**
     * @param tileset a tileset index
     * @return the client's interface data of the tileset (tile groups, minimap colors), loaded once per run
     * @throws IOException if the tileset cannot be read
     */
    public @NotNull InterfaceAssets getTilesetAssets(int tileset) throws IOException {
        return this.client.getTilesetAssets(tileset);
    }

    /**
     * @return the client's interface timer list
     */
    public @NotNull InterfaceTimers getTimers() {
        return this.client.getTimers();
    }

    /**
     * @return the client's portrait tables, for the briefing portraits
     * @throws IOException if a portrait table is missing or malformed
     */
    public @NotNull PortraitTables getPortraitTables() throws IOException {
        return this.client.getPortraitTables();
    }

    /**
     * @return the game's one generator between games, which the lobby's race resolution draws from
     */
    public @NotNull LcgRandom getGlobalRandom() {
        return this.client.getGlobalRandom();
    }

    /**
     * @return the drawing surface
     */
    public @NotNull GlueCanvas getCanvas() {
        return this.canvas;
    }

    /**
     * @return the glue audio
     */
    public @NotNull GlueAudio getAudio() {
        return this.audio;
    }

    /**
     * @return the text locale of the strings, for example {@code koKR}
     */
    public @NotNull String getLocale() {
        return this.locale;
    }

    /**
     * @return the locale of the strings for the engine's texts: the language and country of the text locale, for example Korean in Korea for
     * {@code koKR}; the root locale for a text locale without them
     */
    public @NotNull Locale getTextLocale() {
        if (this.locale.length() <= LANGUAGE_LENGTH) {
            return this.locale.isEmpty() ? Locale.ROOT : Locale.of(this.locale);
        }

        return Locale.of(this.locale.substring(0, LANGUAGE_LENGTH), this.locale.substring(LANGUAGE_LENGTH));
    }

    /**
     * @return the dialog control strings
     */
    public @NotNull GluText getControlText() {
        return this.controlText;
    }

    /**
     * @return the glue strings
     */
    public @NotNull GluText getGluText() {
        return this.gluText;
    }

    /**
     * @return the network strings ({@code strBADFILECHARS}, {@code strERROR_LOAD_MAP}, ...)
     */
    public @NotNull GluText getNetworkText() {
        return this.networkText;
    }

    /**
     * @return the campaign episode entry names of the mission choice box
     */
    public @NotNull CampaignMissionNames getMissionNames() {
        return this.missionNames;
    }

    /**
     * @return the glue flow
     */
    public @NotNull GlueNavigator getNavigator() {
        return this.navigator;
    }

    /**
     * @return the high definition video placements
     */
    public @NotNull GlueFlicLayout getFlicLayout() {
        return this.flicLayout;
    }

    /**
     * @return the install folders of IDs, maps and replays
     */
    public @NotNull GlueFolders getFolders() {
        return this.folders;
    }

    /**
     * @return the folder of the local IDs ({@link GlueFolders#getLocalIdDirectory()})
     */
    public @NotNull Path getLocalIdDirectory() {
        return this.folders.getLocalIdDirectory();
    }

    /**
     * @return the menu choices kept across starts ({@link GlueSettings})
     */
    public @NotNull GlueSettings getSettings() {
        return this.settings;
    }

    /**
     * @return {@code true} if Brood War was chosen instead of the original game, {@code false} otherwise
     */
    public boolean isExpansion() {
        return this.expansion;
    }

    /**
     * Sets the selected game edition.
     *
     * @param expansion {@code true} for Brood War, {@code false} for the original game
     */
    public void setExpansion(boolean expansion) {
        this.expansion = expansion;
    }

    /**
     * @return the map last selected on the create screen in this run, or {@code null}
     */
    public @Nullable Path getLastMap() {
        return this.lastMap;
    }

    /**
     * Sets the last selected map for this run.
     *
     * @param map the map, or {@code null} to clear the selection
     */
    public void setLastMap(@Nullable Path map) {
        this.lastMap = map;
    }

    /**
     * @return {@code true} if map loading and listings use the relaxed {@code 1.16}-era compatibility rules, {@code false} otherwise
     */
    public boolean isMapCompatibility() {
        return this.mapCompatibility;
    }

    /**
     * Sets whether map loading uses the relaxed compatibility rules.
     *
     * @param mapCompatibility whether to use the relaxed {@code 1.16}-era map compatibility rules
     */
    public void setMapCompatibility(boolean mapCompatibility) {
        this.mapCompatibility = mapCompatibility;
    }

    /**
     * Returns the run player's character file, which holds the campaign progress: the chosen local ID's file, else the file the first call
     * opens from the player's folder of the character folder ({@link ClientPlayer#getDirectory}, {@link CharacterFile#open}).
     *
     * @return the player's character file
     */
    public @NotNull CharacterFile getCharacter() {
        CharacterFile opened = this.character;

        if (opened == null) {
            ClientPlayer player = this.client.getPlayer();

            opened         = CharacterFile.open(player.getDirectory(this.folders.getCharacterDirectory()), player.getFolderName(), player.getName());
            this.character = opened;
        }

        return opened;
    }

    /**
     * Makes a local ID the player of a run without an account: its name, its file name as the player's folder name, and its file as the
     * player's character file.
     *
     * @param character the local ID
     * @throws IllegalStateException if the run has an account
     */
    public void setCharacter(@NotNull CharacterFile character) {
        this.client.chooseLocalId(character.getName(), character.getFileName());
        this.character = character;
    }

    /**
     * @return {@code true} if the run has a player to play as: the run's account or a chosen local ID, {@code false} for a guest
     */
    public boolean hasPlayer() {
        return !this.client.getPlayer().isGuest();
    }

    /**
     * @return {@code true} if the glue runs the multiplayer flow, {@code false} otherwise
     */
    public boolean isMultiplayer() {
        return this.multiplayer;
    }

    /**
     * Sets whether the glue runs its multiplayer flow.
     *
     * @param multiplayer whether the glue runs the multiplayer flow
     */
    public void setMultiplayer(boolean multiplayer) {
        this.multiplayer = multiplayer;
    }

    /**
     * @return the Beacon.Link sessions of the multiplayer screens
     */
    public @NotNull BeaconLinkAccess getBeaconLink() {
        return this.client.getBeaconLink();
    }

    /**
     * @return the running SC4J version and how it stands against the latest SC4J release
     */
    public @NotNull ReleaseStatus getReleaseStatus() {
        return this.client.getReleaseStatus();
    }

    /**
     * @return the screen the multiplayer screens return to after a lobby or a canceled game creation: the channel chat over Beacon.Link,
     * the game list on the local network
     */
    public GlueNavigator.@NotNull Destination getMultiplayerHome() {
        return this.beaconLinkSelected ? GlueNavigator.Destination.CHANNEL_CHAT : GlueNavigator.Destination.GAME_LIST;
    }

    /**
     * @return {@code true} if the multiplayer screens play over Beacon.Link, {@code false} if on the local network
     */
    public boolean isBeaconLinkSelected() {
        return this.beaconLinkSelected;
    }

    /**
     * Sets the network the multiplayer screens play over; the game list opens its client on it.
     *
     * @param beaconLinkSelected whether the multiplayer screens play over Beacon.Link
     */
    public void setBeaconLinkSelected(boolean beaconLinkSelected) {
        this.beaconLinkSelected = beaconLinkSelected;
    }

    /**
     * @return the LAN client of the multiplayer screens, or {@code null} before the game list opened it
     */
    public @Nullable LanClient getLanClient() {
        return this.lanClient;
    }

    /**
     * Sets the LAN client, closing the previous one unless it is the same; a new client ends the channel chat of the previous one and
     * clears the room list's filter.
     *
     * @param lanClient the client, or {@code null} to close the LAN protocol
     */
    public void setLanClient(@Nullable LanClient lanClient) {
        LanClient previous = this.lanClient;
        ChannelChat chat = this.channelChat;

        if (chat != null && previous != lanClient) {
            chat.close();
            this.channelChat = null;
        }

        if (previous != lanClient) {
            this.roomFilter = new RoomFilter();
        }

        if (previous != null && previous != lanClient) {
            previous.close();
        }

        this.lanClient = lanClient;
    }

    /**
     * @return the Beacon.Link channel chat of the LAN client's network, kept while the client is open so that the chat screen shows its log
     * again on every visit, or {@code null} before the chat screen opened it
     */
    public @Nullable ChannelChat getChannelChat() {
        return this.channelChat;
    }

    /**
     * Sets the Beacon.Link channel chat of the LAN client's network; {@link #setLanClient} ends it with the client.
     *
     * @param channelChat the chat
     */
    public void setChannelChat(@NotNull ChannelChat channelChat) {
        this.channelChat = channelChat;
    }

    /**
     * @return the filter of the Beacon.Link room list, kept while the LAN client is open so that the list shows the same rooms on every
     * visit
     */
    public @NotNull RoomFilter getRoomFilter() {
        return this.roomFilter;
    }


    /**
     * @return the run player's name in games, lobbies and Beacon.Link: the account name, the local ID's name, or the guest name
     */
    public @NotNull String getPlayerName() {
        return this.client.getPlayer().getName();
    }

    /**
     * @return the run player's name as the menus show it: the account name with its tag, the local ID's name, or the guest name
     */
    public @NotNull String getTaggedPlayerName() {
        return this.client.getPlayer().getTaggedName();
    }

    /**
     * @return the folder of the run player's saved games
     */
    public @NotNull Path getSaveDirectory() {
        return this.folders.getSaveDirectory(this.client.getPlayer());
    }
}
