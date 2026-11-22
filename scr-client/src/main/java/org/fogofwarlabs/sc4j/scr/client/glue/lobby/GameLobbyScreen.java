package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.host.HostWindow;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLinkSession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.scr.client.GameCommunityChat;
import org.fogofwarlabs.sc4j.scr.client.glue.*;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.*;
import org.fogofwarlabs.sc4j.scr.client.ui.text.ForceNamesUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.session.setup.PlayerSlots;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanGameInformation;
import org.fogofwarlabs.sc4j.scr.net.lan.LanClient;
import org.fogofwarlabs.sc4j.scr.net.lan.LanGameOptions;
import org.fogofwarlabs.sc4j.scr.net.lan.LanLobby;
import org.fogofwarlabs.sc4j.scr.net.lan.LanSavedGame;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * Shows LAN lobby players, teams, observers, map details and chat, and submits authorized lobby changes.
 * <p>
 * Hosts start games after player, race and map checks. Lobby updates drive countdowns and game requests; refusal, departure and timeout errors return
 * to the game list (the channel chat over Beacon.Link) after the message closes.
 */
public final class GameLobbyScreen extends GlueScreen {
    private static final @NotNull Logger LOGGER = Logger.of(GameLobbyScreen.class);

    private static final @NotNull String DIALOG = "gluGameLobby";
    /**
     * Developer path for screenshots: host a lobby on this map ({@code -Dsc4j.lobbyMap=<file>}) on port {@code 6118}.
     */
    private static final @NotNull String LOBBY_MAP_PROPERTY = "sc4j.lobbyMap";
    /**
     * The developer lobby's game type: {@code melee} (default), {@code ffa}, {@code tvb}, {@code team} (Team Melee, two teams) or {@code ums}.
     */
    private static final @NotNull String LOBBY_TYPE_PROPERTY = "sc4j.lobbyType";
    /**
     * The developer lobby's UDP port ({@code -Dsc4j.lobbyPort=<port>}); defaults to {@code 6118}, {@code 6112} hosts on the game port.
     */
    private static final @NotNull String LOBBY_PORT_PROPERTY = "sc4j.lobbyPort";
    private static final int DEVELOPER_PORT = 6118;
    /**
     * The developer lobby's Team Melee options with two teams.
     */
    private static final @NotNull String TEAM_MELEE_OPTIONS = "0b00010002000000010101020200010102010000000000320000000000000000";

    private static final int OBSERVERS_PER_ROW = 2;
    private static final int PLAYER_LIST_COUNT = 2;
    private static final int LIST_GAP_COUNT = 4;
    private static final int CENTER_DIVISOR = 2;
    private static final int CHAT_INPUT_RESERVE = 2;
    private static final int TOP_VS_BOTTOM_TEAMS = 2;
    /**
     * The team ids of Top vs Bottom's top and bottom slots.
     */
    private static final int TOP_TEAM = 1;
    private static final int BOTTOM_TEAM = 2;
    private static final int MINIMUM_START_PLAYERS = 2;
    private static final int COMPLETE_PERCENT = 100;
    private static final int REFUSAL_BANNED = 1;
    private static final int REFUSAL_HOST_LEFT = 3;
    private static final int REFUSAL_VERSION_MISMATCH = 7;
    private static final int REFUSAL_INVALID_SPAWN = 8;
    private static final int REFUSAL_INVALID_MAP = 9;

    private static final int CHAT_PANEL = 1;
    private static final int MAP_PANEL = 2;
    private static final int LOBBY_PANEL = 3;
    private static final int START = 9;
    private static final int CANCEL = 10;
    private static final int CHAT_INPUT = 90;
    /**
     * The synthesized id of the chat log's scroll bar ({@link WebScrollBar#createDefinition}).
     */
    private static final int CHAT_SCROLL = 91;
    /**
     * The race of a slot whose player has not selected a race yet ({@link ScrRaceIds#INACTIVE}); the race list shows its Select Race placeholder for it.
     */
    private static final int UNSELECTED_RACE = ScrRaceIds.INACTIVE;
    private static final int COUNTDOWN_SECONDS = 5;
    private static final int COUNTDOWN_STEP_MILLISECONDS = 1000;

    /**
     * The Start button's frame ({@code pOk} of {@code glue/PalNl}), hidden with Start for a player who does not host.
     */
    private static final int START_FRAME = 11;
    /**
     * The player grid column shares for percent, name, race, color and latency.
     */
    private static final float PERCENT_SHARE = 0.06f;
    private static final float NAME_SHARE = 0.40f;
    private static final float RACE_BEFORE_SHARE = 0.02f;
    private static final float LIST_SHARE = 0.20f;
    private static final float LIST_GAP_SHARE = 0.01f;
    /**
     * The team header share at the left edge of the panel.
     */
    private static final float HEADER_SHARE = 0.46f;
    /**
     * A header: border {@code 1}, padding {@code 1}, the {@code 9} px line, padding {@code 1}, border {@code 1}.
     */
    private static final int HEADER_HEIGHT = 13;
    /**
     * A row: border {@code 1}, padding {@code 1}, the {@code 11} px arrow, padding {@code 1}, border {@code 1}.
     */
    private static final int ROW_HEIGHT = 15;
    /**
     * {@code margin-bottom: 2px} of the headers and rows.
     */
    private static final int ROW_MARGIN = 2;
    /**
     * The observers ({@code .observerDropdownWidgetHolder}): two per line, each {@code 49} % with {@code 1} % after the first; inside one, the
     * percent {@code 12} %, the list {@code 68} % and {@code 2} %, the latency {@code 18} %.
     */
    private static final float OBSERVER_SHARE = 0.49f;
    private static final float OBSERVER_GAP_SHARE = 0.01f;
    private static final float OBSERVER_PERCENT_SHARE = 0.12f;
    private static final float OBSERVER_LIST_SHARE = 0.68f;
    private static final float OBSERVER_LIST_GAP_SHARE = 0.02f;
    private static final int OBSERVER_ROWS = 2;
    /**
     * The observer header ({@code .observerSelector}): border {@code 1} and padding {@code 2} at the left, padding {@code 1vh} ({@code 1} % of the
     * page height) and border {@code 1} at the right.
     */
    private static final float OBSERVER_HEADER_LEFT = 3;
    private static final float OBSERVER_HEADER_RIGHT_SHARE = 0.01f;
    private static final int OBSERVER_HEADER_BORDER = 1;
    /**
     * The line kinds of the panel.
     */
    private static final int LINE_HEADER = 1;
    private static final int LINE_SLOT = 2;
    /**
     * The percent column ({@code .percentageBar}): {@code #4cc428}, the row's {@code 8} px text.
     */
    private static final int PERCENT_COLOR = 0x4CC428FF;
    /**
     * The player-row actions for opening, closing, assigning a computer, banning and swapping slots.
     */
    private static final int ACTION_NONE = 0;
    private static final int ACTION_OPEN = 1;
    private static final int ACTION_CLOSED = 2;
    private static final int ACTION_COMPUTER = 3;
    private static final int ACTION_BAN = 4;
    /**
     * The host's own name list offers Make Game Public ({@code N}), a Battle.net request that does nothing on a LAN.
     */
    private static final int ACTION_MAKE_PUBLIC = 5;
    private static final int ACTION_SWAP = 100;
    private static final int TEAM_LINES = 12;
    private static final int FIRST_HEADER = 100;
    private static final int FIRST_NAME = FIRST_HEADER + TEAM_LINES;
    private static final int FIRST_RACE = FIRST_NAME + TEAM_LINES;
    private static final int FIRST_COLOR = FIRST_RACE + TEAM_LINES;
    private static final int OBSERVER_HEADER = FIRST_COLOR + TEAM_LINES;
    private static final int FIRST_OBSERVER = OBSERVER_HEADER + 1;
    /**
     * Slides the Cancel, Ok and map-information panels in from the right and the slot-list and chat panels from the left, with no delay.
     */
    private static final int @NotNull [] SWISH = {12, 2, 11, 2, 8, 0, 6, 0, 7, 2};
    private static final int SWISH_DELAY_MILLISECONDS = 0;

    /**
     * The most lines the chat log keeps.
     */
    private static final int CHAT_HISTORY = 50;

    private static final @NotNull String TAB_STOP_FLAG = "WS_TABSTOP";
    private static final @NotNull String OBSERVERS_KEY = "lobbyScreen_observers";
    private static final @NotNull String MOVE_TO_KEY = "lobbyScreen_moveTo";
    private static final @NotNull String SWITCH_WITH_KEY = "lobbyScreen_switchWith";
    private static final @NotNull String OPEN_KEY = "lobbyScreen_open";
    private static final @NotNull String COMPUTER_KEY = "lobbyScreen_computer";
    private static final @NotNull String CLOSED_KEY = "lobbyScreen_closed";
    private static final @NotNull String HOST_LEFT_KEY = "chatHOSTLEFT";
    private static final @NotNull String BOOTED_KEY = "chatBOOTED";
    /**
     * The start refusal while a player has not completed the map transfer.
     */
    private static final @NotNull String FILE_TRANSFERRING_KEY = "chatFILETRANSFERRING";
    /**
     * The start refusal while a pair of players has no Beacon.Link channel yet; SC4J's own text, the screen has no native one.
     */
    private static final @NotNull String NETWORK_CONNECTING = "Waiting until every player is connected to every other player.";
    /**
     * The player name placeholder of the boot confirmation and the join, departure and drop notices.
     */
    private static final @NotNull String NAME_PLACEHOLDER = "%s";
    /**
     * The separator between an action or team text and its target.
     */
    private static final @NotNull String SPACE = " ";
    /**
     * The record indexes of the observer slots.
     */
    private static final @NotNull List<Integer> OBSERVER_RECORDS = IntStream.range(PlayerTable.PLAYABLE_PLAYER_SLOTS, LanLobby.RECORDS)
                                                                            .boxed()
                                                                            .toList();

    private static final int @NotNull [] RACES = {ScrRaceIds.ZERG, ScrRaceIds.TERRAN, ScrRaceIds.PROTOSS, ScrRaceIds.RANDOM};
    private static final @NotNull String @NotNull [] RACE_KEYS = {"lobbyScreen_zerg", "lobbyScreen_terran", "lobbyScreen_protoss",
                                                                  "lobbyScreen_random"};
    /**
     * The selectable color identifiers, with Random first.
     */
    private static final int @NotNull [] COLORS = {LanLobby.COLOR_DEFAULT, 15, 0, 10, 4, 5, 9, 7, 16, 17, 12, 8, 2, 1, 18, 13, 3, 19, 6, 20, 21};
    /**
     * The color-name text colors in the order of {@link #COLORS}.
     */
    private static final int @NotNull [] COLOR_TEXT = {0xFFFFFFFF, 0xFFC4E4FF, 0xF40404FF, 0xECC4B0FF, 0xF88C14FF, 0x703014FF, 0xFCFC7CFF, 0xFCFC38FF,
                                                       0x808000FF, 0xD2F53CFF, 0x74A47CFF, 0x088008FF, 0x2CB494FF, 0x0C48CCFF, 0x0000E6FF, 0x7290B8FF,
                                                       0x88409CFF, 0xF032E6FF, 0xCCE0D0FF, 0x808080FF, 0x3C3C3CFF};
    /**
     * The hovered and expanded color-name text colors in the order of {@link #COLORS}.
     */
    private static final int @NotNull [] COLOR_HOVER = {0xFFFFFFFF, 0xFFF7FBFF, 0xFC2F2FFF, 0xF6E3D9FF, 0xF9A446FF, 0x9B431CFF, 0xFDFDAEFF,
                                                        0xFDFD6AFF, 0xB3B300FF, 0xDDF76DFF, 0x93B899FF, 0x0BB00BFF, 0x42D1AFFF, 0x1A5DF2FF,
                                                        0x1A1AFFFF, 0x94ABC9FF, 0xA456B9FF, 0xF362ECFF, 0xECF3EDFF, 0x9A9A9AFF, 0x565656FF};
    /**
     * The color names in the order of {@link #COLORS}.
     */
    private static final @NotNull String @NotNull [] COLOR_KEYS = {"lobbyScreen_Random", "lobbyScreen_Pink", "lobbyScreen_Red",
                                                                   "lobbyScreen_Tan", "lobbyScreen_Orange", "lobbyScreen_Brown",
                                                                   "lobbyScreen_PaleYellow", "lobbyScreen_Yellow", "lobbyScreen_Olive",
                                                                   "lobbyScreen_Lime", "lobbyScreen_PaleGreen", "lobbyScreen_Green",
                                                                   "lobbyScreen_Teal", "lobbyScreen_Blue", "lobbyScreen_Navy",
                                                                   "lobbyScreen_BluishGrey", "lobbyScreen_Purple", "lobbyScreen_Magenta",
                                                                   "lobbyScreen_White", "lobbyScreen_Grey", "lobbyScreen_Black"};

    /**
     * One group of the slot list: a team with its header, or the slots of a game without teams.
     */
    private static final class Group {
        private final @NotNull String title;
        private final int team;
        private final @NotNull List<Integer> slots;

        private Group(@NotNull String title, int team) {
            this.title = title;
            this.team  = team;
            this.slots = new ArrayList<>();
        }
    }

    /**
     * Checks whether every occupied player slot has selected a race.
     *
     * @param records the lobby records by slot
     * @return {@code true} if no occupied player slot has the unselected race, {@code false} otherwise
     */
    static boolean areRacesChosen(@NotNull IntFunction<MeleeGameSetup.PlayerRecord> records) {
        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            MeleeGameSetup.PlayerRecord record = records.apply(slot);
            boolean occupied = PlayerSlots.isPlayerRecord(record) || record.getType() == MeleeGameSetup.TYPE_COMPUTER;

            if (occupied && record.getRace() == UNSELECTED_RACE) {
                return false;
            }
        }

        return true;
    }

    /**
     * Creates a {@link DialogControl} instance for a combo box on the lobby's whole-pixel grid.
     *
     * @param identifier the control id
     * @param left       the left edge, rounded to the pixel grid
     * @param top        the top edge
     * @param width      the width, rounded to the pixel grid
     * @param height     the height
     * @return the created {@link DialogControl} instance
     */
    private static @NotNull DialogControl createComboBoxControl(int identifier, float left, int top, float width, int height) {
        return ScrDialogControlUtils.createControl(ScrControlType.COMBOBOX,
                                                   identifier,
                                                   Math.round(left), top,
                                                   Math.round(width), height,
                                                   Set.of(TAB_STOP_FLAG, WebPanelStyleUtils.FLAG),
                                                   "",
                                                   List.of());
    }

    /**
     * Lists the kinds of the panel's lines for the slot groups: each group's header ({@link #LINE_HEADER}) and then a line per slot
     * ({@link #LINE_SLOT}), for the groups that fit the panel's {@value #TEAM_LINES} lines.
     *
     * @param groups the slot groups
     * @return the line kinds from the top
     */
    private static int @NotNull [] createLineKinds(@NotNull List<Group> groups) {
        int[] kinds = new int[TEAM_LINES];
        int line = 0;

        for (Group group : groups) {
            if (line + 1 + group.slots.size() > TEAM_LINES) {
                break;
            }

            kinds[line++] = LINE_HEADER;

            for (int ignored : group.slots) {
                kinds[line++] = LINE_SLOT;
            }
        }

        return Arrays.copyOf(kinds, line);
    }

    /**
     * Sets the status the player's Beacon.Link presence shows, when the client plays over Beacon.Link.
     *
     * @param client the LAN client of the lobby, or {@code null}
     * @param status the status
     */
    private static void setBeaconLinkStatus(@Nullable LanClient client, @NotNull PlayerStatus status) {
        BeaconLinkSession session = client == null ? null : client.getNetwork().getBeaconLinkSession();

        if (session != null) {
            session.getCommunity().setStatus(status);
        }
    }

    /**
     * Creates the Beacon.Link community part of the starting game: the channel chat of the client's network, with the install's texts.
     *
     * @param glueContext the glue context, which holds the channel chat
     * @param client      the LAN client of the lobby
     * @return the created {@link GameCommunityChat} instance, or {@code null} if the client plays without Beacon.Link or the channel chat is not
     * open
     */
    private static @Nullable GameCommunityChat createCommunityChat(@NotNull GlueContext glueContext, @NotNull LanClient client) {
        ChannelChat chat = glueContext.getChannelChat();

        if (client.getNetwork().getBeaconLinkSession() == null || chat == null) {
            return null;
        }

        return new GameCommunityChat(chat, glueContext.getGluText()::get);
    }

    private final @NotNull LobbyChatLog chatLog;
    private final @NotNull List<@Nullable ComboBox> headers;
    private final @NotNull List<@Nullable ComboBox> names;
    private final @NotNull List<@Nullable ComboBox> races;
    private final @NotNull List<@Nullable ComboBox> colors;
    private final @NotNull List<ComboBox> observers;
    /**
     * The record index shown on each observer list, or {@code -1}.
     */
    private final int @NotNull [] observerSlots;
    /**
     * The slot record index shown on each line's name, race and color lists, or {@code -1}.
     */
    private final int @NotNull [] lineSlots;
    /**
     * The team each header line stands for, or {@code -1}.
     */
    private final int @NotNull [] lineTeams;
    /**
     * The kind of each line built for the lobby's groups ({@link #LINE_HEADER}, {@link #LINE_SLOT}).
     */
    private final int @NotNull [] lineKinds;
    private final @NotNull MapPreviewTexture preview;

    private @Nullable MapInformation map;
    private int lineCount;
    private long countdownStart;
    /**
     * Whether Start has submitted a game-start request.
     */
    private boolean startRequested;
    /**
     * The countdown step whose sound ({@link GlueAudio.Sound#CHAT_COUNTDOWN}) played last.
     */
    private int countdownSounded;
    private boolean finished;

    private @Nullable ScrControl startButton;
    private @Nullable ScrControl cancelButton;
    private @Nullable ScrControl startFrame;
    private @Nullable EditControl chatInput;

    /**
     * Creates a {@link GameLobbyScreen} instance.
     */
    public GameLobbyScreen() {
        this(new LobbyChatLog(CHAT_HISTORY));
    }

    /**
     * Creates a {@link GameLobbyScreen} instance that shows a chat log kept from an earlier lobby screen of the same lobby.
     *
     * @param chatLog the chat log
     */
    private GameLobbyScreen(@NotNull LobbyChatLog chatLog) {
        super(DIALOG, SHARED_PALETTE_FOLDER, getPaletteBackground(SHARED_PALETTE_FOLDER));

        this.chatLog       = chatLog;
        this.headers       = new ArrayList<>();
        this.names         = new ArrayList<>();
        this.races         = new ArrayList<>();
        this.colors        = new ArrayList<>();
        this.observers     = new ArrayList<>();
        this.observerSlots = new int[PlayerTable.OBSERVER_SLOTS];
        this.lineSlots     = new int[TEAM_LINES];
        this.lineTeams     = new int[TEAM_LINES];
        this.lineKinds     = new int[TEAM_LINES];
        this.preview       = new MapPreviewTexture();

        this.map              = null;
        this.lineCount        = 0;
        this.countdownStart   = -1;
        this.startRequested   = false;
        this.countdownSounded = -1;
        this.finished         = false;

        this.startButton  = null;
        this.cancelButton = null;
        this.startFrame   = null;
        this.chatInput    = null;
    }


    /**
     * @return the current lobby, or {@code null} before connection
     */
    private @Nullable LanLobby getLobby() {
        LanClient client = this.getContext().getLanClient();

        return client == null ? null : client.getLobby();
    }


    /**
     * Loads the screen's dialog: {@code rez\<dialog name>.ui.json}, or an empty dialog without a name. A screen whose
     * native content is not a control list builds its dialog here.
     * <p>
     * Keeps the dialog's controls and adds the lobby's slot lists and the chat input.
     *
     * @param glueContext the shared services
     * @return the dialog
     * @throws IOException if the dialog cannot be read or has no lobby or chat panel
     */
    @Override
    protected @NotNull DialogDefinition loadDialog(@NotNull GlueContext glueContext) throws IOException {
        DialogDefinition file = super.loadDialog(glueContext);
        DialogControl panel = ScrDialogControlUtils.findControl(file, LOBBY_PANEL);
        DialogControl chat = ScrDialogControlUtils.findControl(file, CHAT_PANEL);

        if (panel == null || chat == null) {
            throw Exceptions.newIOException("gluGameLobby has no lobby or chat panel.");
        }

        if (glueContext.getLanClient() == null && System.getProperty(LOBBY_MAP_PROPERTY) != null) {
            this.hostDeveloperLobby(glueContext);
        }

        LanClient client = glueContext.getLanClient();
        LanLobby lobby = client == null ? null : client.getLobby();
        List<DialogControl> controls = new ArrayList<>(file.getControls());
        float left = panel.getX();
        float width = panel.getWidth();
        float nameLeft = left + PERCENT_SHARE * width;
        float raceLeft = nameLeft + (NAME_SHARE + RACE_BEFORE_SHARE) * width;
        float colorLeft = raceLeft + (LIST_SHARE + PLAYER_LIST_COUNT * LIST_GAP_SHARE) * width;
        int[] kinds = lobby == null ? new int[0] : createLineKinds(this.groupSlots(lobby, glueContext.getGluText()));
        int top = panel.getY();

        for (int line = 0; line < kinds.length; line++) {
            this.lineKinds[line] = kinds[line];

            if (kinds[line] == LINE_HEADER) {
                controls.add(createComboBoxControl(FIRST_HEADER + line, left, top, HEADER_SHARE * width, HEADER_HEIGHT));
                top += HEADER_HEIGHT + ROW_MARGIN;

                continue;
            }

            controls.add(createComboBoxControl(FIRST_NAME + line, nameLeft, top, NAME_SHARE * width, ROW_HEIGHT));
            controls.add(createComboBoxControl(FIRST_RACE + line, raceLeft, top, LIST_SHARE * width, ROW_HEIGHT));
            controls.add(createComboBoxControl(FIRST_COLOR + line, colorLeft, top, LIST_SHARE * width, ROW_HEIGHT));
            top += ROW_HEIGHT + ROW_MARGIN;
        }

        this.lineCount = kinds.length;

        // The observers at the bottom: the header, then the rows of two lists.
        int observerTop = panel.getY() + panel.getHeight() - OBSERVER_ROWS * (ROW_HEIGHT + ROW_MARGIN);
        String observersTitle = glueContext.getGluText().get(OBSERVERS_KEY);
        float headerWidth = glueContext.getCanvas().measure(WebPanelStyleUtils.DROPDOWN_TEXT, observersTitle) + OBSERVER_HEADER_LEFT +
                            OBSERVER_HEADER_RIGHT_SHARE * panel.getHeight() + OBSERVER_HEADER_BORDER;

        controls.add(createComboBoxControl(OBSERVER_HEADER, left, observerTop - HEADER_HEIGHT - ROW_MARGIN, headerWidth, HEADER_HEIGHT));

        float observerWidth = OBSERVER_SHARE * width;

        for (int slot = 0; slot < PlayerTable.OBSERVER_SLOTS; slot++) {
            float observerLeft = left +
                                 (slot % OBSERVERS_PER_ROW == 0 ? 0 : observerWidth + OBSERVER_GAP_SHARE * width) +
                                 OBSERVER_PERCENT_SHARE * observerWidth;

            controls.add(createComboBoxControl(FIRST_OBSERVER + slot,
                                               observerLeft, observerTop + slot / OBSERVERS_PER_ROW * (ROW_HEIGHT + ROW_MARGIN),
                                               OBSERVER_LIST_SHARE * observerWidth, ROW_HEIGHT));
        }

        // The chat input: the page's one-line input.
        controls.add(LobbyChatLog.createInputDefinition(glueContext.getCanvas(), chat, CHAT_INPUT));
        // The chat log's scroll bar: a hidden control the chat painter draws (the native panel has no definition for it).
        controls.add(WebScrollBar.createDefinition(CHAT_SCROLL, chat));

        return new DialogDefinition(file.getName(), file.getX(), file.getY(), file.getWidth(), file.getHeight(), controls);
    }

    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * Prepares the slot, race, color and observer lists and the chat input, reads the lobby's map and lays the slots out; over Beacon.Link
     * the player's presence shows the player in a room.
     *
     * @param glueContext the shared services
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) {
        this.startButton  = this.bindControl(START);
        this.cancelButton = this.bindControl(CANCEL);
        this.startFrame   = this.bindControl(START_FRAME);
        this.chatInput    = null;

        LanClient client = glueContext.getLanClient();
        // Hide Start and its frame for players who do not host.
        boolean host = client != null && client.isHost();

        requireBound(this.startButton).setEnabled(host);
        requireBound(this.startButton).setVisible(host);
        requireBound(this.startFrame).setVisible(host);
        this.setSwish(SWISH, SWISH_DELAY_MILLISECONDS);
        setBeaconLinkStatus(client, PlayerStatus.IN_ROOM);

        for (int line = 0; line < this.lineCount; line++) {
            if (this.lineKinds[line] == LINE_HEADER) {
                ComboBox header = this.addComboBox(FIRST_HEADER + line);

                header.setWebPanelStyle(true);
                header.setArrowVisible(false);
                this.headers.add(header);
                this.names.add(null);
                this.races.add(null);
                this.colors.add(null);

                continue;
            }

            this.headers.add(null);

            ComboBox name = this.addComboBox(FIRST_NAME + line);

            name.setWebPanelStyle(true);
            this.names.add(name);

            ComboBox race = this.addComboBox(FIRST_RACE + line);

            race.setWebPanelStyle(true);

            for (int index = 0; index < RACES.length; index++) {
                race.addItem(glueContext.getGluText().get(RACE_KEYS[index]), RACES[index]);
            }

            race.setPlaceholder(glueContext.getGluText().get("lobbyScreen_selectRace"));
            this.races.add(race);

            ComboBox color = this.addComboBox(FIRST_COLOR + line);

            color.setWebPanelStyle(true);

            for (int index = 0; index < COLORS.length; index++) {
                color.addItem(glueContext.getGluText().get(COLOR_KEYS[index]), COLORS[index], COLOR_TEXT[index], COLOR_HOVER[index]);
            }

            // A color the map sets (MapSpecified, 0x17) is shown, never offered: it stands in as the placeholder.
            color.setPlaceholder(glueContext.getGluText().get("lobbyScreen_MapSpecified"));
            this.colors.add(color);
        }

        ComboBox observerHeader = this.addComboBox(OBSERVER_HEADER);

        observerHeader.setWebPanelStyle(true);
        observerHeader.setArrowVisible(false);
        this.headers.add(observerHeader);

        for (int slot = 0; slot < PlayerTable.OBSERVER_SLOTS; slot++) {
            ComboBox observer = this.addComboBox(FIRST_OBSERVER + slot);

            observer.setWebPanelStyle(true);
            observer.setOpensUpward(true);
            this.observers.add(observer);
        }

        this.chatInput = this.setEditText(CHAT_INPUT, "", LanLobby.CHAT_LIMIT - CHAT_INPUT_RESERVE, "");
        this.chatLog.setScrollBar(this.requireDialogState().attach(new WebScrollBar(this.bindControl(CHAT_SCROLL).getDefinition())));
        this.loadMap(client);
        this.layoutSlots();
    }

    /**
     * Reads the lobby's map information once the map file is known: at once for the host, for a joiner when the map transfer found the map in the map
     * folders or finished downloading it ({@link LanClient#getMapPath()}).
     *
     * @param client the LAN client, or {@code null} without one
     */
    private void loadMap(@Nullable LanClient client) {
        Path mapPath = client == null || this.map != null ? null : client.getMapPath();

        if (mapPath == null) {
            return;
        }

        GlueContext glueContext = this.getContext();

        try {
            this.map = MapInformation.read(mapPath, glueContext.isMapCompatibility());
            this.preview.replace(glueContext, this.map);
        } catch (IOException exception) {
            LOGGER.warning("LOBBY MAP UNREADABLE: " + exception.getMessage());
        }
    }

    /**
     * Hosts the developer lobby on the map of {@link #LOBBY_MAP_PROPERTY} on a port other than the game port, so that the screen can be seen
     * without a second client.
     *
     * @param glueContext the shared services
     */
    private void hostDeveloperLobby(@NotNull GlueContext glueContext) {
        Path file = glueContext.getInstallLayout().resolveUserPath(Path.of(System.getProperty(LOBBY_MAP_PROPERTY)));
        LanGameOptions options = switch (System.getProperty(LOBBY_TYPE_PROPERTY, "melee")) {
            case "ffa" -> LanGameOptions.FREE_FOR_ALL;
            case "ums" -> LanGameOptions.USE_MAP_SETTINGS;
            case "tvb" -> LanGameOptions.TOP_VS_BOTTOM_1;
            case "team" -> LanGameOptions.parse(HexFormat.of().parseHex(TEAM_MELEE_OPTIONS));
            default -> LanGameOptions.MELEE;
        };
        String name = glueContext.getPlayerName();

        try {
            MapInformation information = MapInformation.read(file, glueContext.isMapCompatibility());
            LanClient client = LanClient.open(name, glueContext.getFolders().getLanMapFolders());

            client.host(name,
                        LanClient.HostedMap.read(file, information.getMap(), information.getName()),
                        options,
                        GameSpeed.FASTEST,
                        Integer.getInteger(LOBBY_PORT_PROPERTY, DEVELOPER_PORT));
            glueContext.setLanClient(client);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("DEVELOPER LOBBY FAILED: " + exception.getMessage());
        }
    }

    /**
     * Called by {@link #update} for screen-specific timers.
     * <p>
     * Polls the LAN client, shows the chat and the notices, leaves on a refusal or a departure, runs the countdown and
     * starts the game once the lobby reaches it. A join that failed or ended without a lobby shows the join error and returns to the list;
     * slot lines built for other slot groups than the lobby's are built again.
     *
     * @param time the time in milliseconds
     */
    @Override
    protected void onUpdate(long time) {
        LanClient client = this.getContext().getLanClient();

        if (client == null || this.finished) {
            return;
        }

        client.update();

        LanLobby lobby = client.getLobby();

        // A join that has not reached the lobby yet keeps the screen waiting; a failed or left one returns to the list.
        if (lobby == null) {
            if (client.getState() != LanClient.State.JOINING) {
                this.fail(GameListScreen.getJoinFailureText(this.getContext(), client));
            }

            return;
        }

        this.loadMap(client);

        for (LanLobby.Chat chat = lobby.pollChat(); chat != null; chat = lobby.pollChat()) {
            int playerSlot = lobby.getPlayerSlot(chat.getPlayer());
            String playerName = playerSlot < 0 ? "" : lobby.getRecord(playerSlot).getName();

            this.chatLog.add(playerName + ": ", chat.getText());
        }

        for (LanLobby.Notice notice = lobby.pollNotice(); notice != null; notice = lobby.pollNotice()) {
            this.addNotice(notice);
        }

        GluText text = this.getContext().getGluText();

        if (lobby.hasHostLeft()) {
            this.fail(text.get(HOST_LEFT_KEY));

            return;
        }

        if (lobby.getRefusal() != LanLobby.NO_REFUSAL) {
            this.fail(switch (lobby.getRefusal()) {
                case REFUSAL_BANNED -> text.get("chatBANNED");
                case LanLobby.REFUSAL_NO_SLOT -> text.get("chatSLOTCLOSED");
                case REFUSAL_HOST_LEFT -> text.get(HOST_LEFT_KEY);
                case LanLobby.UNJOINABLE_ERROR -> text.get("chatUNJOINABLE");
                case LanLobby.REFUSAL_INVALID_SAVE -> text.get("chatINVALIDSAVE");
                case LanLobby.REFUSAL_FILE_ERROR -> text.get("chatFDERROR");
                case REFUSAL_VERSION_MISMATCH -> text.get("chatVERSIONMISMATCH");
                case REFUSAL_INVALID_SPAWN -> text.get("chatINVALIDSPAWN");
                case REFUSAL_INVALID_MAP -> text.get("chatINVALIDMAP");
                default -> text.get(BOOTED_KEY);
            });

            return;
        }

        if (lobby.isTimedOut() && lobby.hasLeft()) {
            this.fail(text.get(BOOTED_KEY));

            return;
        }

        // The slot lines follow the first slot record; lines built for other groups are built again.
        if (lobby.getState() < LanLobby.STATE_LOBBY) {
            return;
        }

        if (!Arrays.equals(createLineKinds(this.groupSlots(lobby, text)), Arrays.copyOf(this.lineKinds, this.lineCount))) {
            this.rebuild();

            return;
        }

        if (lobby.getState() >= LanLobby.STATE_COUNTDOWN && this.countdownStart < 0) {
            this.countdownStart = time;

            requireBound(this.startButton).setEnabled(false);
            requireBound(this.cancelButton).setEnabled(false);
        }

        if (this.countdownStart >= 0) {
            // Play the countdown sound once for each nonnegative step.
            int count = COUNTDOWN_SECONDS - (int) ((time - this.countdownStart) / COUNTDOWN_STEP_MILLISECONDS);

            if (count >= 0 && count != this.countdownSounded) {
                this.countdownSounded = count;

                this.getContext().getAudio().play(GlueAudio.Sound.CHAT_COUNTDOWN);
            }
        }

        this.layoutSlots();

        // Enable Start only after occupied slots select races; other checks report their message when pressed.
        if (client.isHost() && this.countdownStart < 0 && !this.startRequested) {
            requireBound(this.startButton).setEnabled(areRacesChosen(lobby::getRecord));
        }

        if (lobby.getState() == LanLobby.STATE_GAME) {
            this.startGame(client);
        }
    }

    /**
     * Lays the slot records out on the panel's lines by their groups ({@link #groupSlots}): each group's header and slots from the top, the
     * observers' header and their lists at the bottom of the panel.
     */
    private void layoutSlots() {
        LanLobby lobby = this.getLobby();
        LanClient client = this.getContext().getLanClient();

        for (int line = 0; line < this.lineCount; line++) {
            if (this.lineKinds[line] == LINE_HEADER) {
                this.headers.get(line).setVisible(false);
            } else {
                this.names.get(line).setVisible(false);
                this.races.get(line).setVisible(false);
                this.colors.get(line).setVisible(false);
            }

            this.lineSlots[line] = -1;
            this.lineTeams[line] = -1;
        }

        this.headers.getLast().setVisible(false);

        for (int slot = 0; slot < PlayerTable.OBSERVER_SLOTS; slot++) {
            this.observers.get(slot).setVisible(false);
            this.observerSlots[slot] = -1;
        }

        if (lobby == null || client == null) {
            return;
        }

        GluText text = this.getContext().getGluText();
        int line = 0;
        List<Group> groups = this.groupSlots(lobby, text);

        for (Group group : groups) {
            // The lines were built for the groups of the lobby; a group that no longer fits them is left out.
            if (line >= this.lineCount || this.lineKinds[line] != LINE_HEADER) {
                break;
            }

            this.lineTeams[line] = group.team;
            this.showBar(this.headers.get(line++), group.title);

            for (int slot : group.slots) {
                if (line >= this.lineCount || this.lineKinds[line] != LINE_SLOT) {
                    break;
                }

                MeleeGameSetup.PlayerRecord record = lobby.getRecord(slot);

                this.showNameList(lobby, text, groups, this.names.get(line), slot);

                if (PlayerSlots.isPlayerRecord(record) || record.getType() == MeleeGameSetup.TYPE_COMPUTER) {
                    ComboBox race = this.races.get(line);

                    if (!race.isOpen()) {
                        race.selectValue(record.getRace() == UNSELECTED_RACE ? -1 : record.getRace());
                    }

                    race.setVisible(true);
                    race.setEnabled(this.canChange(lobby, slot) && (!lobby.getOptions().isUseMapSettings() || lobby.isRaceSelectable(slot)));

                    ComboBox color = this.colors.get(line);

                    if (!color.isOpen()) {
                        // 0x17 (MapSpecified) is not an item: the placeholder shows its name.
                        color.selectValue(lobby.getColor(slot));

                        for (int index = 0; index < COLORS.length; index++) {
                            color.setItemEnabled(index, lobby.isColorAvailable(COLORS[index], slot));
                        }
                    }

                    color.setVisible(true);
                    color.setEnabled(this.canChange(lobby, slot) && !lobby.isColorLocked(slot));
                }

                this.lineSlots[line++] = slot;
            }
        }

        // The observer block shows while the game has observer slots, closed ones included.
        List<Integer> watchers = new ArrayList<>();

        for (int index = PlayerTable.PLAYABLE_PLAYER_SLOTS; index < LanLobby.RECORDS; index++) {
            if (lobby.getRecord(index).getType() != PlayerTypes.NONE) {
                watchers.add(index);
            }
        }

        if (watchers.isEmpty()) {
            return;
        }

        this.showBar(this.headers.getLast(), text.get(OBSERVERS_KEY));

        for (int slot = 0; slot < Math.min(PlayerTable.OBSERVER_SLOTS, watchers.size()); slot++) {
            this.observerSlots[slot] = watchers.get(slot);
            this.showNameList(lobby, text, groups, this.observers.get(slot), watchers.get(slot));
        }
    }

    /**
     * Checks whether the local player may change a slot's race or color while the unsaved lobby is open.
     *
     * @param lobby the lobby
     * @param slot  the slot record index
     * @return {@code true} if the local player may change the slot's race and color, {@code false} otherwise
     */
    private boolean canChange(@NotNull LanLobby lobby, int slot) {
        LanClient client = this.getContext().getLanClient();
        ProviderSession session = client == null ? null : client.getSession();

        if (session == null || lobby.getState() != LanLobby.STATE_LOBBY || lobby.getSavedGame() != null) {
            return false;
        }

        int localSlot = lobby.getPlayerSlot(session.getLocalIdentifier());

        if (localSlot < 0 || localSlot >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return false;
        }

        MeleeGameSetup.PlayerRecord target = lobby.getRecord(slot);
        boolean host = client.isHost();

        if (lobby.getOptions().isTeamGame()) {
            int team = target.getTeam();

            return team == lobby.getRecord(localSlot).getTeam() || host && this.hasComputerOnTeam(lobby, team);
        }

        return slot == localSlot || host && target.getType() == MeleeGameSetup.TYPE_COMPUTER;
    }

    /**
     * @param lobby the lobby
     * @param team  the team
     * @return {@code true} if a player slot of the team holds a computer, {@code false} otherwise
     */
    private boolean hasComputerOnTeam(@NotNull LanLobby lobby, int team) {
        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            if (lobby.getRecord(slot).getTeam() == team && lobby.getRecord(slot).getType() == MeleeGameSetup.TYPE_COMPUTER) {
                return true;
            }
        }

        return false;
    }

    /**
     * Shows the slot label and the host's permitted open, closed, computer, ban and team/observer move actions.
     *
     * @param lobby    the lobby
     * @param text     the {@code gluAll} strings
     * @param groups   the slot groups of the lobby
     * @param comboBox the slot's name list, the dialog's control of the list
     * @param index    the slot record index
     */
    private void showNameList(@NotNull LanLobby lobby,
                              @NotNull GluText text,
                              @NotNull List<Group> groups,
                              @NotNull ComboBox comboBox,
                              int index) {
        LanClient client = this.getContext().getLanClient();
        MeleeGameSetup.PlayerRecord record = lobby.getRecord(index);
        boolean human = PlayerSlots.isPlayerRecord(record);
        // The bar shows the slot's name; the list holds only the slot's actions.
        String name = human
                      ? record.getName()
                      : switch (record.getType()) {
            case LanLobby.TYPE_OPEN, LanLobby.TYPE_OBSERVER_OPEN -> text.get(OPEN_KEY);
            case MeleeGameSetup.TYPE_COMPUTER -> text.get(COMPUTER_KEY);
            default -> text.get(CLOSED_KEY);
        };
        List<String> texts = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        int local = client == null || client.getSession() == null ? -1 : client.getSession().getLocalIdentifier();
        boolean host = client != null && client.isHost() && lobby.getState() == LanLobby.STATE_LOBBY;
        LanGameOptions options = lobby.getOptions();

        if (host && human && index == lobby.getPlayerSlot(local)) {
            texts.add(name);
            values.add(ACTION_NONE);
            texts.add(text.get("lobbyScreen_makeGamePublic"));
            values.add(ACTION_MAKE_PUBLIC);
        } else if (host) {
            if (human) {
                if (index < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                    if (options.isTopVsBottom()) {
                        for (Group group : groups) {
                            if (group.team != record.getTeam()) {
                                // Top vs Bottom: the other team's open slot alone, else the name and the switches.
                                if (!this.hasOpenSlot(lobby, group)) {
                                    texts.add(name);
                                    values.add(ACTION_NONE);
                                }

                                this.addMoves(lobby, text, group.title, group.slots, LanLobby.TYPE_OPEN, texts, values);
                            }
                        }
                    }

                    this.addMoves(lobby, text, text.get(OBSERVERS_KEY), OBSERVER_RECORDS, LanLobby.TYPE_OBSERVER_OPEN, texts, values);
                } else {
                    for (Group group : groups) {
                        this.addMoves(lobby, text, group.title, group.slots, LanLobby.TYPE_OPEN, texts, values);
                    }
                }

                this.addSlotStates(text, !options.isTeamGame() && index < PlayerTable.PLAYABLE_PLAYER_SLOTS, texts, values);
                texts.add(text.get("lobbyScreen_banPlayer"));
                values.add(ACTION_BAN);
            } else if (index >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                this.addSlotStates(text, false, texts, values);
            } else if (!(options.isUseMapSettings() && record.getType() == MeleeGameSetup.TYPE_COMPUTER)) {
                boolean computer = !options.isUseMapSettings() && !(options.isTeamGame() && this.hasHumanOnTeam(lobby, record.getTeam()));

                this.addSlotStates(text, computer, texts, values);
            }
        }

        boolean same = comboBox.getItemCount() == texts.size();

        for (int item = 0; same && item < texts.size(); item++) {
            same = texts.get(item).equals(comboBox.getItemText(item));
        }

        if (!same || comboBox.getSelection() != ComboBox.NO_SELECTION) {
            comboBox.clear();

            for (int item = 0; item < texts.size(); item++) {
                comboBox.addItem(texts.get(item), values.get(item));
            }

            comboBox.select(ComboBox.NO_SELECTION);
        }

        comboBox.setLabel(name);
        comboBox.setVisible(true);
        comboBox.setEnabled(!texts.isEmpty());
    }

    /**
     * @param lobby the lobby
     * @param group the slot group
     * @return {@code true} if a slot of the group is open, {@code false} otherwise
     */
    private boolean hasOpenSlot(@NotNull LanLobby lobby, @NotNull Group group) {
        for (int slot : group.slots) {
            if (lobby.getRecord(slot).getType() == LanLobby.TYPE_OPEN) {
                return true;
            }
        }

        return false;
    }

    /**
     * Adds the open and closed actions and, where a computer may take the slot, the computer action.
     *
     * @param text     the {@code gluAll} strings
     * @param computer whether the computer action is offered
     * @param texts    the action texts to add to
     * @param values   the action values to add to
     */
    private void addSlotStates(@NotNull GluText text, boolean computer, @NotNull List<String> texts, @NotNull List<Integer> values) {
        texts.add(text.get(OPEN_KEY));
        values.add(ACTION_OPEN);
        texts.add(text.get(CLOSED_KEY));
        values.add(ACTION_CLOSED);

        if (computer) {
            texts.add(text.get(COMPUTER_KEY));
            values.add(ACTION_COMPUTER);
        }
    }

    /**
     * Adds the moves to a slot group: a move to its first slot of the open type, or without one, a switch with each player of the group.
     *
     * @param lobby    the lobby
     * @param text     the {@code gluAll} strings
     * @param title    the group's title
     * @param slots    the slot record indexes of the group
     * @param openType the record type of an open slot of the group
     * @param texts    the action texts to add to
     * @param values   the action values to add to
     */
    private void addMoves(@NotNull LanLobby lobby,
                          @NotNull GluText text,
                          @NotNull String title, @NotNull List<Integer> slots, int openType,
                          @NotNull List<String> texts, @NotNull List<Integer> values) {
        for (int slot : slots) {
            MeleeGameSetup.PlayerRecord other = lobby.getRecord(slot);

            if (other.getType() == openType) {
                texts.add(text.get(MOVE_TO_KEY) + SPACE + title);
                values.add(ACTION_SWAP + slot);

                return;
            }
        }

        for (int slot : slots) {
            MeleeGameSetup.PlayerRecord other = lobby.getRecord(slot);

            if (PlayerSlots.isPlayerRecord(other)) {
                texts.add(text.get(SWITCH_WITH_KEY) + SPACE + other.getName());
                values.add(ACTION_SWAP + slot);
            }
        }
    }

    /**
     * @param lobby the lobby
     * @param team  the team
     * @return {@code true} if a player slot of the team holds a human player, {@code false} otherwise
     */
    private boolean hasHumanOnTeam(@NotNull LanLobby lobby, int team) {
        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            if (lobby.getRecord(slot).getTeam() == team && PlayerSlots.isPlayerRecord(lobby.getRecord(slot))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Runs a host slot action, confirming removal when a player occupies the target slot.
     *
     * @param index  the slot record index
     * @param action the action value of the name list
     */
    private void runNameAction(int index, int action) {
        LanLobby lobby = this.getLobby();

        if (lobby == null || action == ACTION_NONE || action == ACTION_MAKE_PUBLIC) {
            return;
        }

        if (action >= ACTION_SWAP) {
            lobby.swapSlots(index, action - ACTION_SWAP);

            return;
        }

        MeleeGameSetup.PlayerRecord record = lobby.getRecord(index);
        Runnable run = () -> {
            switch (action) {
                case ACTION_OPEN -> lobby.setSlotState(index, LanLobby.SLOT_OPEN);
                case ACTION_CLOSED -> lobby.setSlotState(index, LanLobby.SLOT_CLOSED);
                case ACTION_COMPUTER -> lobby.setSlotState(index, LanLobby.SLOT_COMPUTER);
                case ACTION_BAN -> lobby.boot(index, true);
                default -> {
                }
            }
        };

        if (!PlayerSlots.isPlayerRecord(record)) {
            run.run();

            return;
        }

        String confirm = this.getContext().getGluText().get("chatCONFIRMBOOT_s").replace(NAME_PLACEHOLDER, record.getName());

        this.showPopup(new GlueMessagePopup(GlueMessagePopup.Kind.OK_CANCEL,
                                            this.getPaletteFolder(),
                                            confirm,
                                            null,
                                            confirmed -> {
                                                if (confirmed) {
                                                    run.run();
                                                }
                                            }));
    }

    /**
     * Shows a one-item bar: a header (enabled, without arrow; a press on it is handled by {@link #onPointerPressed}).
     *
     * @param comboBox the header's list, the dialog's control of the header
     * @param text     the header's text
     */
    private void showBar(@NotNull ComboBox comboBox, @NotNull String text) {
        if (comboBox.getItemCount() != 1 || !text.equals(comboBox.getItemText(0))) {
            comboBox.clear();
            comboBox.addItem(text, 0);
            comboBox.select(0);
        }

        comboBox.setVisible(true);
        comboBox.setEnabled(true);
    }

    /**
     * Groups occupied, open and closed player slots by Top vs Bottom teams, map forces or team-game teams.
     *
     * @param lobby the lobby
     * @param text  the {@code gluAll} strings
     * @return the groups with slots, followed by the players outside the teams
     */
    private @NotNull List<Group> groupSlots(@NotNull LanLobby lobby, @NotNull GluText text) {
        LanGameOptions options = lobby.getOptions();
        List<Group> groups = new ArrayList<>();
        int teams = options.isTopVsBottom()
                    ? TOP_VS_BOTTOM_TEAMS
                    : options.isUseMapSettings() ? LanLobby.FORCES : options.isTeamGame() ? options.getTeamCount() : 0;

        for (int team = 1; team <= teams; team++) {
            String title;

            if (options.isTopVsBottom()) {
                title = text.get(team == TOP_TEAM ? "tvbTEAMONE" : "tvbTEAMTWO");
            } else if (options.isUseMapSettings()) {
                title = ForceNamesUtils.resolveForceName(this.getContext().getNetworkText()::get, false, lobby.getForceName(team - 1), team);
            } else {
                title = text.get("lobbyScreen_teams") + SPACE + team;
            }

            groups.add(new Group(title, team));
        }

        Group players = new Group(text.get("chatPLAYERS"), 0);

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            MeleeGameSetup.PlayerRecord record = lobby.getRecord(slot);
            int type = record.getType();

            if (!PlayerSlots.isPlayerRecord(record) && type != LanLobby.TYPE_OPEN && type != MeleeGameSetup.TYPE_CLOSED &&
                type != MeleeGameSetup.TYPE_COMPUTER) {
                continue;
            }

            int team = record.getTeam();

            if (team >= 1 && team <= teams) {
                groups.get(team - 1).slots.add(slot);
            } else {
                players.slots.add(slot);
            }
        }

        groups.removeIf(group -> group.slots.isEmpty());

        if (!players.slots.isEmpty()) {
            groups.add(players);
        }

        return groups;
    }

    private void startGame(@NotNull LanClient client) {
        this.finished = true;

        GlueContext glueContext = this.getContext();
        // A saved game starts from the local player's own file, whose map it holds (no map transfer).
        LanSavedGame saved = client.getSavedGame();
        Path mapPath = saved != null ? saved.getFile() : client.getMapPath();

        if (mapPath == null) {
            this.fail(glueContext.getNetworkText().get(GameListScreen.JOIN_ERROR_KEY));

            return;
        }

        // The network keeps receiving and acknowledging on its own while the game loads: the network thread takes over the pump.
        client.startNetworkThread();

        GlueGameRequest request = GlueGameRequest.createNetwork(mapPath,
                                                                client.getPlayerName(),
                                                                glueContext.isExpansion(),
                                                                client,
                                                                createCommunityChat(glueContext, client),
                                                                client.getSpeed(),
                                                                glueContext.getMultiplayerHome());

        setBeaconLinkStatus(client, PlayerStatus.IN_GAME);

        if (client.isUseMapSettings() && saved == null) {
            // Open a ready room before an unsaved Use Map Settings game while the network pump continues.
            client.beginBriefing();
            this.leave(() -> glueContext.getNavigator().openReadyRoom(request));

            return;
        }

        this.leave(() -> glueContext.getNavigator().start(request));
    }

    private void fail(@NotNull String message) {
        this.finished = true;

        GlueContext glueContext = this.getContext();
        LanClient client = glueContext.getLanClient();

        if (client != null) {
            client.leave();
        }

        this.showError(message, () -> this.leave(() -> glueContext.getNavigator().show(glueContext.getMultiplayerHome())));
    }

    /**
     * Shows the lobby on a new lobby screen, whose dialog has the slot lines of the lobby's groups, with this screen's chat log.
     */
    private void rebuild() {
        this.finished = true;

        this.getContext().getNavigator().replace(new GameLobbyScreen(this.chatLog));
    }

    /**
     * Adds a join, departure or drop message and plays a join sound while the window is in the background.
     *
     * @param notice the lobby notice
     */
    private void addNotice(LanLobby.@NotNull Notice notice) {
        GluText network = this.getContext().getNetworkText();
        String key = switch (notice.getKind()) {
            case LanLobby.Notice.JOINED -> "strPLAYER_JOINED";
            case LanLobby.Notice.DROPPED -> "strPLAYER_WAS_DROPPED";
            default -> "strPLAYER_LEFT";
        };

        this.chatLog.add(network.get(key).replace(NAME_PLACEHOLDER, notice.getName()), "");

        HostWindow window = this.getContext().getWindow();

        if (notice.getKind() == LanLobby.Notice.JOINED && (window == null || !window.isFocused())) {
            this.getContext().getAudio().play(GlueAudio.Sound.BATTLE_NET_CLICK);
        }
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     * <p>
     * Start requests the game start; Cancel leaves the lobby and returns to the game list (the channel chat over Beacon.Link).
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
        GlueContext glueContext = this.getContext();

        switch (control) {
            case START -> this.requestStart();
            case CANCEL -> {
                this.finished = true;

                LanClient client = glueContext.getLanClient();

                if (client != null) {
                    client.leave();
                }

                this.leave(() -> glueContext.getNavigator().show(glueContext.getMultiplayerHome()));
            }
            default -> {
            }
        }
    }

    /**
     * Called after the user changed the selection of a list or combo box, or the value of a slider.
     * <p>
     * Runs a chosen slot action or submits a chosen race or color while the lobby is open.
     *
     * @param control the control id
     */
    @Override
    protected void onSelectionChanged(int control) {
        LanLobby lobby = this.getLobby();

        if (lobby == null || lobby.getState() != LanLobby.STATE_LOBBY) {
            return;
        }

        if (control >= FIRST_NAME && control < FIRST_NAME + this.lineCount && this.lineSlots[control - FIRST_NAME] >= 0) {
            this.runNameAction(this.lineSlots[control - FIRST_NAME], this.names.get(control - FIRST_NAME).getSelectedValue(ACTION_NONE));
        } else if (control >= FIRST_OBSERVER && control < FIRST_OBSERVER + PlayerTable.OBSERVER_SLOTS &&
                   this.observerSlots[control - FIRST_OBSERVER] >= 0) {
            this.runNameAction(this.observerSlots[control - FIRST_OBSERVER],
                               this.observers.get(control - FIRST_OBSERVER).getSelectedValue(ACTION_NONE));
        } else if (control >= FIRST_RACE && control < FIRST_RACE + this.lineCount && this.lineSlots[control - FIRST_RACE] >= 0) {
            lobby.changeRace(this.lineSlots[control - FIRST_RACE], this.races.get(control - FIRST_RACE).getSelectedValue(ScrRaceIds.RANDOM));
        } else if (control >= FIRST_COLOR && control < FIRST_COLOR + this.lineCount && this.lineSlots[control - FIRST_COLOR] >= 0) {
            lobby.changeColor(this.lineSlots[control - FIRST_COLOR], this.colors.get(control - FIRST_COLOR).getSelectedValue(LanLobby.COLOR_DEFAULT));
        }
    }

    /**
     * Called first for every press of the screen that no open popup or open combo box takes, while the screen takes input.
     * <p>
     * Takes presses on the chat scroll bar and the chat text (a selection), and presses on a team or observers header to request a move to that team or to the observers.
     *
     * @param x the interface x
     * @param y the interface y
     * @return {@code true} if the press was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onPointerPressed(float x, float y) {
        if (this.chatLog.press(this.getContext().getCanvas(), x, y, this.isExtendingPress(), this.isDoublePress())) {
            return true;
        }

        int hovered = this.getHoveredControl();

        if (hovered == OBSERVER_HEADER) {
            // Request the observer position while the lobby is open.
            LanLobby lobby = this.getLobby();

            if (lobby != null && lobby.getState() == LanLobby.STATE_LOBBY) {
                lobby.requestObserver();
            }

            return true;
        }

        if (hovered < FIRST_HEADER || hovered >= FIRST_HEADER + this.lineCount) {
            return false;
        }

        LanLobby lobby = this.getLobby();
        int team = this.lineTeams[hovered - FIRST_HEADER];

        if (lobby != null && lobby.getState() == LanLobby.STATE_LOBBY && team > 0) {
            lobby.requestForce(team);
        }

        return true;
    }

    /**
     * Checks player readiness, chosen races and completed map transfer before submitting one start request.
     */
    private void requestStart() {
        LanClient client = this.getContext().getLanClient();
        LanLobby lobby = this.getLobby();

        if (client == null || lobby == null || !client.isHost()) {
            return;
        }

        String refusal = this.getStartRefusal(lobby);
        String transferring = this.getContext().getGluText().get(FILE_TRANSFERRING_KEY);

        if (refusal != null && !refusal.equals(transferring)) {
            this.chatLog.add("", refusal);

            return;
        }

        if (!areRacesChosen(lobby::getRecord)) {
            return;
        }

        if (refusal != null) {
            this.chatLog.add("", refusal);

            return;
        }

        // Disable Start after the request is queued, preventing a duplicate request during countdown.
        if (lobby.startGame()) {
            this.startRequested = true;

            requireBound(this.startButton).setEnabled(false);
        }
    }

    /**
     * Returns the message of the first failing start check: {@code gameNOTENOUGHPLAYERS} with fewer than two players outside Use Map Settings,
     * {@code chatONESIDE} with an empty Top vs Bottom side, {@code chatFILETRANSFERRING} while a player has not reported the map
     * ({@link LanLobby#isMapTransferComplete()}). They answer the Start press with their message; they do not disable Start
     * (only the race check does, {@link #areRacesChosen}).
     *
     * @param lobby the lobby
     * @return the refusal message, or {@code null} when the game can start
     */
    private @Nullable String getStartRefusal(@NotNull LanLobby lobby) {
        GluText text = this.getContext().getGluText();
        LanGameOptions options = lobby.getOptions();
        int players = 0;
        int[] sides = new int[BOTTOM_TEAM + 1];

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            MeleeGameSetup.PlayerRecord record = lobby.getRecord(slot);

            if (PlayerSlots.isPlayerRecord(record) || record.getType() == MeleeGameSetup.TYPE_COMPUTER) {
                players++;

                if (record.getTeam() == TOP_TEAM || record.getTeam() == BOTTOM_TEAM) {
                    sides[record.getTeam()]++;
                }
            }
        }

        if (!options.isUseMapSettings() && players < MINIMUM_START_PLAYERS) {
            return text.get("gameNOTENOUGHPLAYERS");
        }

        if (options.isTopVsBottom() && (sides[TOP_TEAM] == 0 || sides[BOTTOM_TEAM] == 0)) {
            return text.get("chatONESIDE");
        }

        // Wait until every player has completed its map transfer.
        if (!lobby.isMapTransferComplete()) {
            return text.get(FILE_TRANSFERRING_KEY);
        }

        LanClient client = this.getContext().getLanClient();

        // Over Beacon.Link, wait until every pair of players is connected, directly or through another player.
        if (client != null && !client.getNetwork().isReadyToStart()) {
            return NETWORK_CONNECTING;
        }

        return null;
    }

    /**
     * Called first for every key of the screen (after an open popup): a screen without controls, such as a cinematic, takes its keys here.
     * <p>
     * Takes Enter in an active lobby and sends a nonempty typed chat line; other keys reach the chat input.
     *
     * @param key the key
     * @return {@code true} if the key was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onKey(char key) {
        LanLobby lobby = this.getLobby();

        if (lobby == null || this.finished || key != KEY_ENTER) {
            return false;
        }

        String typed = requireBound(this.chatInput).getEditText().getText();

        if (!typed.isEmpty() && lobby.sendChat(typed)) {
            this.setEditText(CHAT_INPUT, "", LanLobby.CHAT_LIMIT - CHAT_INPUT_RESERVE, "");
        }

        return true;
    }

    /**
     * Draws one control.
     * <p>
     * Draws the chat and map information panels, and the download percent and latency bars beside the slot lists.
     *
     * @param canvas  the glue canvas
     * @param control the control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IOException if an image cannot be read
     */
    @Override
    protected void drawControl(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) throws IOException {
        int identifier = control.getId();

        if (identifier == CHAT_PANEL) {
            this.chatLog.draw(canvas, left, top, control.getWidth(), control.getHeight());

            return;
        }

        if (identifier == MAP_PANEL) {
            this.drawMapPanel(canvas, control, left, top);

            return;
        }

        if (identifier == LOBBY_PANEL) {
            return;
        }

        super.drawControl(canvas, control, left, top);

        DialogControl panel = ScrDialogControlUtils.findControl(this.getDialog(), LOBBY_PANEL);
        float panelWidth = panel == null ? 0 : panel.getWidth();

        if (identifier >= FIRST_NAME && identifier < FIRST_NAME + this.lineCount) {
            int slot = this.lineSlots[identifier - FIRST_NAME];

            // The percent column before the name, the latency bars after the color list.
            this.drawDownloadPercent(canvas, slot, left - PERCENT_SHARE * panelWidth, top, PERCENT_SHARE * panelWidth);
            this.drawLatency(canvas,
                             slot,
                             left + (NAME_SHARE + RACE_BEFORE_SHARE + PLAYER_LIST_COUNT * LIST_SHARE + LIST_GAP_COUNT * LIST_GAP_SHARE) * panelWidth,
                             top);
        } else if (identifier >= FIRST_OBSERVER && identifier < FIRST_OBSERVER + PlayerTable.OBSERVER_SLOTS) {
            int slot = this.observerSlots[identifier - FIRST_OBSERVER];
            float observerWidth = OBSERVER_SHARE * panelWidth;

            this.drawDownloadPercent(canvas, slot, left - OBSERVER_PERCENT_SHARE * observerWidth, top, OBSERVER_PERCENT_SHARE * observerWidth);
            this.drawLatency(canvas, slot, left + (OBSERVER_LIST_SHARE + OBSERVER_LIST_GAP_SHARE) * observerWidth, top);
        }
    }

    /**
     * Draws a slot's map download percentage while it is incomplete.
     *
     * @param canvas the glue canvas
     * @param slot   the slot record index, or {@code -1}
     * @param left   the left edge of the percent column
     * @param top    the top edge of the row
     * @param width  the width of the percent column
     */
    private void drawDownloadPercent(@NotNull GlueCanvas canvas, int slot, float left, float top, float width) {
        LanLobby lobby = this.getLobby();

        if (lobby == null || slot < 0) {
            return;
        }

        MeleeGameSetup.PlayerRecord record = lobby.getRecord(slot);
        int percent = PlayerSlots.isPlayerRecord(record) ? lobby.getDownloadPercent(record.getPlayerId()) : -1;

        if (percent < 0 || percent >= COMPLETE_PERCENT) {
            return;
        }

        String text = percent + "%";
        float line = canvas.getLineHeight(WebPanelStyleUtils.DROPDOWN_TEXT, text);

        WebPanelStyleUtils.drawText(canvas,
                                    WebPanelStyleUtils.DROPDOWN_TEXT,
                                    text,
                                    left, top + (ROW_HEIGHT - line) / CENTER_DIVISOR,
                                    width, line,
                                    GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                    PERCENT_COLOR);
    }

    /**
     * Draws latency bars in the row, choosing green, yellow or red by their count.
     *
     * @param canvas the glue canvas
     * @param slot   the slot record index, or {@code -1}
     * @param left   the left edge of the bars
     * @param top    the top edge of the row
     * @throws IOException if a bar image cannot be read
     */
    private void drawLatency(@NotNull GlueCanvas canvas, int slot, float left, float top) throws IOException {
        LanLobby lobby = this.getLobby();

        if (lobby == null || slot < 0) {
            return;
        }

        MeleeGameSetup.PlayerRecord record = lobby.getRecord(slot);
        LanClient client = this.getContext().getLanClient();
        int bars = client == null || !PlayerSlots.isPlayerRecord(record) ? 0 : client.getLatencyBars(record.getPlayerId());

        LatencyBarsUtils.draw(canvas, bars, left, top, ROW_HEIGHT);
    }

    private void drawMapPanel(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) {
        LanClient client = this.getContext().getLanClient();
        LanGameInformation game = client == null ? null : client.getGameInformation();
        MapInformation information = this.map;

        if (game == null || information == null) {
            return;
        }

        GlueContext glueContext = this.getContext();
        List<String[]> rows = new ArrayList<>();

        rows.add(new String[]{glueContext.getGluText().get("infoPane_size"),
                              MapInformationPanel.formatMapSize(information.getWidth(), information.getHeight())});
        rows.add(new String[]{glueContext.getGluText().get("infoPane_speed"), GameListScreen.getSpeedName(glueContext, game.getGameSpeed())});
        rows.add(new String[]{glueContext.getGluText().get("infoPane_turnRate"), Integer.toString(game.getNetworkTurnRate())});

        // Show the advertised hotkey and unit-limit flags from the host settings.
        int flags = game.getFlags();
        GluText gluText = glueContext.getGluText();

        rows.add(new String[]{gluText.get("createGameScreen_customHotkeys"),
                              gluText.get((flags & CreateGameScreen.HOTKEYS_DISABLED_FLAG) != 0
                                          ? "createGameScreen_disabled"
                                          : "createGameScreen_enabled")});
        rows.add(new String[]{gluText.get("createGameScreen_gameLimits"),
                              gluText.get((flags & CreateGameScreen.ORIGINAL_LIMITS_FLAG) != 0
                                          ? "createGameScreen_original"
                                          : "createGameScreen_extended")});

        String countdown = null;

        if (this.countdownStart >= 0) {
            long elapsed = this.getTime() - this.countdownStart;

            // Retain zero until the lobby completes the countdown and starts the game.
            int remaining = Math.max(0, COUNTDOWN_SECONDS - (int) (elapsed / COUNTDOWN_STEP_MILLISECONDS));

            countdown = String.format(Locale.ROOT,
                                      "%s: %d %s",
                                      glueContext.getGluText().get("infoPane_startingIn"),
                                      remaining,
                                      glueContext.getGluText().get("chatSECONDS"));
        }

        MapInformationPanel.drawLobby(canvas,
                                      glueContext.getGluText(),
                                      this.preview,
                                      game.getName(), information.getName(), GameTemplates.getTypeName(glueContext.getGluText(), game.getGameType()),
                                      rows,
                                      countdown,
                                      left, top,
                                      control.getWidth());
    }

    /**
     * Returns the image drawn centered behind the dialog: the one the screen was created with.
     * <p>
     * Over Beacon.Link the chat page's background.
     *
     * @return the image path, or {@code null} for none (popups)
     */
    @Override
    protected @Nullable String getBackgroundPath() {
        return this.getContext().isBeaconLinkSelected() ? ChannelChatScreen.BACKGROUND : super.getBackgroundPath();
    }

    /**
     * Selects the chat font for the chat input; other edit controls use their selected font slot.
     *
     * @param canvas  the glue canvas
     * @param control an {@code EDITTEXT} control
     * @return the control's selected font slot
     */
    @Override
    protected int getEditFontSlot(@NotNull GlueCanvas canvas, @NotNull DialogControl control) {
        return control.getId() == CHAT_INPUT ? LobbyChatLog.TEXT : super.getEditFontSlot(canvas, control);
    }

    /**
     * Selects the chat message color for the chat input; other edit controls use the screen's color.
     *
     * @param control an {@code EDITTEXT} control
     * @return the packed color of its text, or {@code 0} for the dialog's color code {@code 2}
     */
    @Override
    protected int getEditColor(@NotNull DialogControl control) {
        return control.getId() == CHAT_INPUT ? LobbyChatLog.MESSAGE_COLOR : super.getEditColor(control);
    }

    /**
     * Called first for a wheel notch of the screen.
     * <p>
     * Scrolls the chat while the pointer is over the chat panel.
     *
     * @param x    the pointer's interface x
     * @param y    the pointer's interface y
     * @param down {@code true} for a notch toward the user
     * @return {@code true} if the notch was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onWheel(float x, float y, boolean down) {
        DialogControl chat = ScrDialogControlUtils.findControl(this.getDialog(), CHAT_PANEL);

        if (chat == null || !GlueCanvas.contains(x - this.getDialogLeft(), y - this.getDialogTop(),
                                                 chat.getX(), chat.getY(),
                                                 chat.getWidth(), chat.getHeight())) {
            return false;
        }

        this.chatLog.scrollByWheel(down);

        return true;
    }

    /**
     * Called for every pointer move of the screen (after an open combo box or popup takes it).
     * <p>
     * Drags the chat scroll bar or the chat selection.
     *
     * @param x the interface x
     * @param y the interface y
     */
    @Override
    protected void onPointerMoved(float x, float y) {
        this.chatLog.drag(this.getContext().getCanvas(), x, y);
    }

    /**
     * Called for every release of the left button of the screen.
     * <p>
     * Releases the chat scroll bar and the chat selection.
     *
     * @param x the interface x
     * @param y the interface y
     */
    @Override
    protected void onPointerReleased(float x, float y) {
        this.chatLog.release();
    }

    /**
     * Called first for a copy (Ctrl+C or the edit menu's Copy): a screen whose read-only text (a chat log) holds a selection copies it here,
     * before the focused edit control.
     * <p>
     * Copies the chat log's selection.
     *
     * @return {@code true} if the copy was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onCopy() {
        return this.chatLog.copy(this.getContext().getClipboard());
    }

    /**
     * {@return a string representation of the object}
     * <p>
     * Satisfying this method's contract implies a non-{@code null} result must be returned.
     *
     * @apiNote In general, the {@code toString} method returns a string that "textually represents" this object. The result should be a concise but
     * informative representation that is easy for a person to read. It is recommended that all subclasses override this method. The string output is
     * not necessarily stable over time or across JVM invocations.
     * @implSpec Returns a diagnostic string containing the count of chat lines.
     */
    @Override
    public @NotNull String toString() {
        return String.format(Locale.ROOT, "GameLobbyScreen[%d chat lines]", this.chatLog.getLineCount());
    }


    /**
     * Releases the videos and the popup.
     * <p>
     * Releases the map preview first.
     */
    @Override
    public void close() {
        this.preview.release(this.getContext().getCanvas());
        super.close();
    }
}
