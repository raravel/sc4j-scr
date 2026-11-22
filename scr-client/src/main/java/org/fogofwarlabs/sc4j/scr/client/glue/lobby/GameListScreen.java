package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueContext;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueScreen;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ListBox;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.game.save.SaveGameKind;
import org.fogofwarlabs.sc4j.scr.game.session.setup.GameTemplate;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanDiscovery;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanGameInformation;
import org.fogofwarlabs.sc4j.scr.net.lan.LanClient;
import org.fogofwarlabs.sc4j.scr.net.lan.LanLobby;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.fogofwarlabs.sc4j.scr.net.proxy.GameProxySocket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lists the games of the local network (the LAN discovery), shows their advertised settings and follows game-join progress into the lobby.
 * Over Beacon.Link the rooms list on {@link RoomListScreen}.
 */
public final class GameListScreen extends GlueScreen {
    /**
     * The inline code that right aligns the rest of a row ({@code 0x12}).
     */
    static final char RIGHT_ALIGN = 0x12;
    /**
     * The network text of a failed join.
     */
    static final @NotNull String JOIN_ERROR_KEY = "strERROR_JOINING_GAME";

    private static final @NotNull Logger LOGGER = Logger.of(GameListScreen.class);

    private static final int LIST = 6;
    private static final int SUBTYPE_TITLE = 7;
    private static final int NAME = 8;
    private static final int TYPE = 9;
    private static final int SUBTYPE = 10;
    private static final int MAP = 11;
    private static final int SIZE = 12;
    private static final int SPEED = 13;
    private static final int OK = 14;
    private static final int CANCEL = 15;
    private static final int CREATE = 16;
    private static final int PORTS = 17;
    private static final int @NotNull [] VALUES = {NAME, TYPE, SUBTYPE, MAP, SIZE, SPEED};
    /**
     * Panel identifiers and directions for the game-list transitions.
     */
    private static final int @NotNull [] SWISH = {1, 0, 2, 2, 3, 3, 4, 2};
    private static final int SWISH_DELAY_MILLISECONDS = 0;
    private static final int FIRST_ROW = 0;
    private static final int REFRESH_MILLISECONDS = 1000;
    /**
     * The time of the last refresh before the first one, so that the first update refreshes.
     */
    private static final long INITIAL_REFRESH_TIME = Long.MIN_VALUE / 2;
    private static final int DISCOVERY_PORT = 5353;
    /**
     * Developer path: join from another local UDP port ({@code -Dsc4j.lanJoinPort=<port>}, two clients on one PC).
     */
    private static final @NotNull String JOIN_PORT_PROPERTY = "sc4j.lanJoinPort";

    /**
     * Gets the localized name of a supported game speed.
     *
     * @param glueContext the glue services
     * @param speed       the game speed, {@code 0..6}
     * @return the name, or an empty string for another value
     */
    static @NotNull String getSpeedName(@NotNull GlueContext glueContext, int speed) {
        return speed >= 0 && speed < GluText.GAME_SPEED_KEYS.size() ? glueContext.getGluText().get(GluText.GAME_SPEED_KEYS.get(speed)) : "";
    }

    /**
     * Gets the localized teams title for team templates, otherwise the stored subtype label, with a colon.
     *
     * @param glueContext the glue services
     * @param template    the template
     * @return the title
     */
    static @NotNull String getSubtypeTitle(@NotNull GlueContext glueContext, GameTemplates.@NotNull Template template) {
        GameTemplate options = template.getTemplate();

        return options.isTopVsBottom() || options.hasTeams() ? glueContext.getGluText().get("lobbyScreen_teams") + ":"
                                                             : template.getSubtypeLabel() + ":";
    }

    /**
     * Gets the formatted Top vs Bottom slot counts or the numeric subtype value.
     *
     * @param glueContext the glue services
     * @param template    the game options
     * @param slots       the map's start slots
     * @return the text
     */
    static @NotNull String getSubtypeText(@NotNull GlueContext glueContext, @NotNull GameTemplate template, int slots) {
        int value = template.getSubTypeValue();

        return template.isTopVsBottom() ? String.format(Locale.ROOT, glueContext.getGluText().get("tvbSIDES"), value, slots - value)
                                        : Integer.toString(value);
    }

    /**
     * Returns whether a joining client's lobby can be shown: the lobby screen lays its slot rows out for the lobby's groups, so it opens once
     * the first slot record arrived ({@link LanLobby#STATE_LOBBY}).
     *
     * @param client the joining client
     * @return {@code true} if the client is in a lobby whose first slot record arrived, {@code false} otherwise
     */
    static boolean isLobbyReady(@NotNull LanClient client) {
        LanLobby lobby = client.getLobby();

        return client.getState() == LanClient.State.LOBBY && lobby != null && lobby.getState() >= LanLobby.STATE_LOBBY;
    }

    /**
     * Sets the folder a join searches for the saved game of a saved-game lobby: the player's multiplayer save folder, of the product's kind.
     *
     * @param glueContext the glue services
     * @param client      the client that joins
     */
    static void setSaveDirectory(@NotNull GlueContext glueContext, @NotNull LanClient client) {
        client.setSaveDirectory(glueContext.getSaveDirectory(), SaveGameKind.of(glueContext.isExpansion(), true));
    }

    /**
     * Returns the message of a failed join: a missing matching save first, then a timeout, then the general join error.
     *
     * @param glueContext the glue services
     * @param client      the client whose join failed
     * @return the message
     */
    static @NotNull String getJoinFailureText(@NotNull GlueContext glueContext, @NotNull LanClient client) {
        return client.isSavedGameMissing() ? glueContext.getNetworkText().get("strERROR_NO_SAVE_FILE")
                                           : client.getJoinFailure() == ProviderSession.JoinState.TIMED_OUT
                                             ? glueContext.getGluText().get("bnet_joinGame_error_timeOut")
                                             : glueContext.getNetworkText().get(JOIN_ERROR_KEY);
    }

    private @NotNull List<GameTemplates.Template> templates;
    private @NotNull List<LanDiscovery.Game> games;

    private @Nullable ListBox list;
    private long lastRefresh;
    private boolean joining;

    private @Nullable ScrControl okButton;
    private @Nullable ScrControl subtypeTitle;
    private @Nullable ScrControl nameValue;
    private @Nullable ScrControl typeValue;
    private @Nullable ScrControl subtypeValue;
    private @Nullable ScrControl mapValue;
    private @Nullable ScrControl sizeValue;
    private @Nullable ScrControl speedValue;
    private @NotNull ScrControl @Nullable [] valueControls;

    /**
     * Creates a {@link GameListScreen} instance.
     */
    public GameListScreen() {
        super("gluJoin", SHARED_PALETTE_FOLDER, getPaletteBackground(SHARED_PALETTE_FOLDER));

        this.templates = List.of();
        this.games     = List.of();

        this.list        = null;
        this.lastRefresh = INITIAL_REFRESH_TIME;
        this.joining     = false;

        this.okButton      = null;
        this.subtypeTitle  = null;
        this.nameValue     = null;
        this.typeValue     = null;
        this.subtypeValue  = null;
        this.mapValue      = null;
        this.sizeValue     = null;
        this.speedValue    = null;
        this.valueControls = null;
    }

    private LanDiscovery.@Nullable Game getSelectedGame() {
        ListBox rows = this.list;

        if (rows == null) {
            return null;
        }

        int selection = rows.getSelection();

        return selection >= 0 && selection < this.games.size() ? this.games.get(selection) : null;
    }


    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * Prepares the game list, the templates and the LAN browsing client with its map and save folders.
     *
     * @param glueContext the shared services
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) {
        this.okButton      = this.bindControl(OK);
        this.subtypeTitle  = this.bindControl(SUBTYPE_TITLE);
        this.nameValue     = this.bindControl(NAME);
        this.typeValue     = this.bindControl(TYPE);
        this.subtypeValue  = this.bindControl(SUBTYPE);
        this.mapValue      = this.bindControl(MAP);
        this.sizeValue     = this.bindControl(SIZE);
        this.speedValue    = this.bindControl(SPEED);
        this.valueControls = this.bindControls(VALUES);

        this.setSwish(SWISH, SWISH_DELAY_MILLISECONDS);

        ListBox rows = this.addListBox(LIST);

        this.list = rows;
        // The rows have no bars, as the screen shows them (a transparent list).
        rows.setTransparent(true);

        // The selected game's values are white (code 6), their titles blue, as the screen shows them.
        for (ScrControl value : requireBound(this.valueControls)) {
            value.setHighlighted(true);
        }

        String ports = glueContext.getControlText().get("gluJoin-17");
        String networkText = ports.contains("%d") ? String.format(Locale.ROOT, ports.replace("%d", "%s"), DISCOVERY_PORT, GameProxySocket.GAME_PORT)
                                                  : ports;

        this.bindControl(PORTS).setReplacedText(networkText);

        this.templates = GameTemplates.loadOrCreateDefaults(glueContext.getCanvas().getStorage(), LOGGER);

        if (glueContext.getLanClient() == null) {
            String playerName = glueContext.getPlayerName();

            try {
                glueContext.setLanClient(LanClient.open(playerName, glueContext.getFolders().getLanMapFolders()));
            } catch (IOException | IllegalArgumentException exception) {
                LOGGER.warning("NETWORK UNAVAILABLE: " + exception.getMessage());
                this.showError(glueContext.getGluText().get("gameNETWORKERROR"));
            }
        }

        LanClient client = glueContext.getLanClient();

        if (client != null) {
            setSaveDirectory(glueContext, client);
        }

        // Back from a game or a lobby: the game's session is left (subtype 11) and its network thread stops.
        if (client != null && client.getState() != LanClient.State.BROWSING) {
            client.leave();
        }

        this.showGame(null);
    }

    /**
     * Called by {@link #update} for screen-specific timers.
     * <p>
     * Polls the LAN client, follows a pending join and refreshes the discovered games once a second.
     *
     * @param time the time in milliseconds
     */
    @Override
    protected void onUpdate(long time) {
        LanClient client = this.getContext().getLanClient();

        if (client == null) {
            return;
        }

        client.update();

        if (this.joining) {
            this.followJoin(client);
        }

        if (time - this.lastRefresh >= REFRESH_MILLISECONDS) {
            this.lastRefresh = time;
            this.refreshGames(client);
        }
    }

    private void refreshGames(@NotNull LanClient client) {
        ListBox rows = this.list;

        if (rows == null) {
            return;
        }

        LanDiscovery.Game selected = this.getSelectedGame();
        List<LanDiscovery.Game> listed = new ArrayList<>(client.getGames());
        List<String> texts = new ArrayList<>();
        int selection = ListBox.NO_SELECTION;

        for (LanDiscovery.Game game : listed) {
            LanGameInformation information = game.getInformation();
            String row = information.getName() + RIGHT_ALIGN + information.getPlayersCurrent() + "/" +
                         (information.getPlayersMaximum() - information.getClosedSlots());

            if (information.getObserversMaximum() > 0) {
                row += " (" + information.getObserversCurrent() + "/" + information.getObserversMaximum() + ")";
            }

            if (selected != null && selected.getInformation().getName().equals(information.getName())) {
                selection = texts.size();
            }

            texts.add(row);
        }

        this.games = listed;
        rows.setItems(texts);

        if (selection == ListBox.NO_SELECTION && !texts.isEmpty()) {
            selection = FIRST_ROW;
        }

        rows.select(selection, rows.getVisibleRows(this.getContext().getCanvas()));
        this.showGame(this.getSelectedGame());
    }

    /**
     * Called after the user changed the selection of a list or combo box, or the value of a slider.
     * <p>
     * Shows the advertised settings of the game the game list selects.
     *
     * @param control the control id
     */
    @Override
    protected void onSelectionChanged(int control) {
        if (control == LIST) {
            this.showGame(this.getSelectedGame());
        }
    }

    private void showGame(LanDiscovery.@Nullable Game game) {
        GlueContext glueContext = this.getContext();

        requireBound(this.okButton).setEnabled(game != null && !this.joining);

        if (game == null) {
            for (ScrControl value : requireBound(this.valueControls)) {
                value.setReplacedText("");
            }

            requireBound(this.subtypeTitle).setVisible(false);

            return;
        }

        LanGameInformation information = game.getInformation();

        requireBound(this.nameValue).setReplacedText(information.getName());
        requireBound(this.typeValue).setReplacedText(GameTemplates.getTypeName(glueContext.getGluText(), information.getGameType()));

        // The subtype of a type with several templates: the advertised subtype id names the template.
        GameTemplates.Template template = GameTemplates.find(this.templates, information.getGameType(), information.getSubtype());
        boolean subtype = template != null && GameTemplates.getSubtypes(this.templates, information.getGameType()).size() > 1;

        requireBound(this.subtypeTitle).setVisible(subtype);
        requireBound(this.subtypeTitle).setReplacedText(subtype ? getSubtypeTitle(glueContext, template) : "");
        requireBound(this.subtypeValue).setReplacedText(subtype
                                                        ? getSubtypeText(glueContext, template.getTemplate(), information.getPlayersMaximum())
                                                        : "");
        requireBound(this.mapValue).setReplacedText(information.getMapName());
        requireBound(this.sizeValue).setReplacedText(MapInformationPanel.formatMapSize(information.getMapWidth(), information.getMapHeight()));

        int speed = information.getGameSpeed();

        requireBound(this.speedValue).setReplacedText(getSpeedName(glueContext, speed));
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     * <p>
     * Ok joins the selected game, Cancel closes the LAN client and returns to the connection choice, Create opens the game creation.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
        GlueContext glueContext = this.getContext();

        switch (control) {
            case OK -> {
                LanClient client = glueContext.getLanClient();
                LanDiscovery.Game game = this.getSelectedGame();

                if (client == null || game == null || this.joining) {
                    return;
                }

                try {
                    client.join(game, Integer.getInteger(JOIN_PORT_PROPERTY, GameProxySocket.GAME_PORT));
                    this.joining = true;
                    requireBound(this.okButton).setEnabled(false);
                } catch (IOException exception) {
                    LOGGER.warning("LAN JOIN FAILED: " + exception.getMessage());
                    this.showError(glueContext.getNetworkText().get(JOIN_ERROR_KEY));
                }
            }
            case CANCEL -> {
                glueContext.setLanClient(null);
                this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.CONNECTION));
            }
            case CREATE -> this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.CREATE_GAME));
            default -> {
            }
        }
    }

    private void followJoin(@NotNull LanClient client) {
        GlueContext glueContext = this.getContext();

        // The join stays pending while the screen slides away, so that no second join starts.
        if (isLobbyReady(client)) {
            this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.GAME_LOBBY));
        } else if (client.getState() == LanClient.State.FAILED) {
            this.joining = false;
            this.showGame(this.getSelectedGame());
            this.showError(getJoinFailureText(glueContext, client));
        }
    }
}
