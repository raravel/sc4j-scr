package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLinkSession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.FoundRoom;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LatencyClass;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.RoomSettings;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatChannel;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.CommunityText;
import org.fogofwarlabs.sc4j.engine.net.lobby.JoinRefusal;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.scr.client.glue.*;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.GlueEditPopup;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.*;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.net.beaconlink.BeaconLinkProvider;
import org.fogofwarlabs.sc4j.scr.net.beaconlink.ScrRoomData;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanDiscovery;
import org.fogofwarlabs.sc4j.scr.net.lan.LanClient;
import org.fogofwarlabs.sc4j.scr.net.proxy.GameProxySocket;
import org.fogofwarlabs.sc4j.scr.render.ui.TrueTypeText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;

/**
 * Lists the Beacon.Link rooms of the SC:R mod on the install's Battle.net join dialog ({@code gluJoinMultiBattlenet}), drawn as its pages
 * draw them: the custom games page (title, search input, Filter, Create and Refresh, a header and one row per room with its players, name,
 * game type, map and latency bars; a lock before the name of a private room), the selected room's details (the map preview's place, the
 * name, map and type, then the facts) and the channel chat under them. The closed rooms (full, started or of another version of the mod)
 * follow the joinable ones apart and in grey; they cannot be joined. Filter turns the list page into the filter settings (game type, closed
 * games, the worst latency and the largest player count on sliders), which the network keeps until it closes. Join or a double press joins
 * the selected room, a private one after the shared edit popup took its password, and follows the join into the lobby; a wrong password
 * shows the install's refusal. Cancel returns to the channel chat.
 */
public final class RoomListScreen extends GlueScreen {
    private static final @NotNull Logger LOGGER = Logger.of(RoomListScreen.class);

    private static final @NotNull String DIALOG = "gluJoinMultiBattlenet";

    private static final int LIST_PAGE = 6;
    private static final int INFO_PAGE = 7;
    private static final int CHAT_PAGE = 8;
    private static final int JOIN = 9;
    private static final int CANCEL = 10;
    /**
     * The synthesized controls of the pages, ids the dialog does not use.
     */
    private static final int SEARCH = 90;
    private static final int FILTER = 91;
    private static final int CREATE = 92;
    private static final int RESET = 93;
    private static final int SAVE = 94;
    private static final int TYPE_CHOICE = 95;
    private static final int LATENCY_SLIDER = 96;
    private static final int PLAYERS_SLIDER = 97;
    private static final int VERSIONS_CHECK = 98;
    private static final int CHAT_INPUT = 99;
    private static final int LIST_SCROLL = 100;
    private static final int CHAT_SCROLL = 101;
    private static final int REFRESH = 102;
    private static final int TITLE = 103;
    private static final int TYPE_LABEL = 104;
    private static final int LATENCY_LABEL = 105;
    private static final int PLAYERS_LABEL = 106;
    private static final int PLAYERS_VALUE = 107;
    private static final int @NotNull [] LIST_CONTROLS = {SEARCH, FILTER, CREATE, REFRESH};
    private static final int @NotNull [] FILTER_CONTROLS = {RESET, SAVE, TYPE_CHOICE, LATENCY_SLIDER, PLAYERS_SLIDER, VERSIONS_CHECK, TYPE_LABEL,
                                                            LATENCY_LABEL, PLAYERS_LABEL, PLAYERS_VALUE};
    /**
     * Slides the list and the chat in from the left, the details and Ok from the right and Cancel from below, with no delay.
     */
    private static final int @NotNull [] SWISH = {1, 0, 5, 0, 2, 2, 4, 2, 3, 3};
    private static final int SWISH_DELAY_MILLISECONDS = 0;
    private static final int REFRESH_MILLISECONDS = 1000;
    private static final long INITIAL_REFRESH_TIME = Long.MIN_VALUE / 2;

    /**
     * The custom games page ({@code GameSearchPanel}): the title (the screens' page title), the search row at the page's content top
     * ({@code .searchFormHolder}: margin {@code 4} at its sides; the search field, the magnifier first; the page buttons, at least a fifth of the
     * row, {@code 4} apart; the square refresh button last; the screens' framed text field and buttons), the header ({@code .gameListHeader}:
     * {@code 8} px {@code #a2b1f5}, {@code 12} high, padding {@code 4} left and {@code 14} right) and the rows ({@code .gameInfoHolder}:
     * {@code 8} px {@code #ddd}, line {@code 12}, padding {@code 2}, a {@code #404040} line above each, the selected one in {@code #b4fc74}
     * with a frame) in the columns' shares of the width ({@code .col_0} to {@code .col_4}). The page's text is {@code eurostile-reg}.
     */
    private static final float INSET = 4;
    private static final float PAGE_TEXT_SIZE = 8;
    private static final int PAGE_TEXT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY, PAGE_TEXT_SIZE,
                                                                        WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    private static final int SEARCH_ROW_TOP = PAGE_CONTENT_TOP;
    private static final int SEARCH_ROW_HEIGHT = FRAMED_CONTROL_HEIGHT;
    private static final float NAV_BUTTON_SHARE = 0.2f;
    /**
     * The room a framed button's label keeps on each side: the frame's corner.
     */
    private static final float NAV_BUTTON_PADDING = 6;
    private static final float REFRESH_ICON_INSET = 3;
    private static final @NotNull String SEARCH_ICON = "magnifying_glass_32.png";
    private static final @NotNull String REFRESH_ICON = "refresh_normal_64.png";
    private static final float LIST_LEFT = 8;
    private static final float HEADER_TOP = SEARCH_ROW_TOP + SEARCH_ROW_HEIGHT + INSET;
    private static final float HEADER_HEIGHT = 12;
    private static final float HEADER_LEFT_PADDING = 4;
    private static final float HEADER_RIGHT_PADDING = 14;
    private static final float ROWS_TOP = HEADER_TOP + HEADER_HEIGHT;
    private static final float ROW_HEIGHT = 17;
    /**
     * The rows' height: the whole rows that fit between the header and the panel art's frame ({@link CreateGameScreen#PANEL_FRAME_BOTTOM},
     * {@code 4} above it), as the local network game list shows whole rows only; the scroll bar takes the rest.
     */
    private static final float ROWS_HEIGHT = (float) Math.floor((CreateGameScreen.PANEL_FRAME_BOTTOM - INSET - ROWS_TOP) / ROW_HEIGHT) * ROW_HEIGHT;
    private static final float @NotNull [] COLUMN_SHARES = {0.12f, 0.40f, 0.24f, 0.16f, 0.08f};
    private static final int LATENCY_COLUMN = 4;
    private static final int ROW_TEXT_COLOR = 0xDDDDDDFF;
    private static final @NotNull String GROUP_FORMAT = "%s (%d)";
    private static final @NotNull String PLAYERS_FORMAT = "%d/%d";
    private static final @NotNull String SIZE_FORMAT = "%dx%d";
    private static final @NotNull String LABEL_SUFFIX = ":";
    private static final @NotNull String MOD_SEPARATOR = " ";
    private static final @NotNull String ROOM_NOTE_SEPARATOR = ", ";

    /**
     * The filter settings in the list page's place ({@code .filterPanel}: margin {@code 4}, a {@code 1} px {@code #6c0000} frame with
     * {@code 2} px corners on {@code rgba(0,0,0,.6)}, padding {@code 8}, {@code 215} high; {@code .filter_col}: padding {@code 4}): the game
     * type's label (the screens' small label text, a line of {@code 14}) {@code 4} above the drop-down (the create game drop-down of the
     * local network: {@code CreateGameScreen.CHOICE_HEIGHT} high, as wide as the row); the closed games' check box; the latency's and the
     * player count's labels above their sliders, each slider's value centered under it; {@code 8} between rows; Reset and Save at the bottom
     * ({@code .bottomPanel}: {@code 49} % wide each, at both ends; the screens' framed buttons).
     */
    private static final int FILTER_PANEL_HEIGHT = 215;
    private static final float FILTER_PANEL_RADIUS = 2;
    private static final int FILTER_PANEL_FILL = 0x00000099;
    private static final int FILTER_PANEL_INSET = 9;
    private static final int FILTER_COLUMN_PADDING = 4;
    private static final int CHOICE_LABEL_HEIGHT = 14;
    private static final int CHOICE_LABEL_GAP = 4;
    private static final int CHOICE_HEIGHT = CreateGameScreen.CHOICE_HEIGHT;
    private static final int CHOICE_ROW_GAP = 8;
    private static final int CHECK_HEIGHT = 14;
    private static final int SLIDER_HEIGHT = 12;
    private static final int SLIDER_VALUE_GAP = 2;
    private static final int TYPE_ROW_TOP = CHOICE_LABEL_HEIGHT + CHOICE_LABEL_GAP;
    private static final int CHECK_ROW_TOP = TYPE_ROW_TOP + CHOICE_HEIGHT + CHOICE_ROW_GAP;
    private static final int LATENCY_LABEL_TOP = CHECK_ROW_TOP + CHECK_HEIGHT + CHOICE_ROW_GAP;
    private static final int SLIDER_ROW_HEIGHT = CHOICE_LABEL_HEIGHT + CHOICE_LABEL_GAP + SLIDER_HEIGHT + SLIDER_VALUE_GAP + CHOICE_LABEL_HEIGHT +
                                                 CHOICE_ROW_GAP;
    private static final int PLAYERS_LABEL_TOP = LATENCY_LABEL_TOP + SLIDER_ROW_HEIGHT;
    private static final float FILTER_BUTTON_SHARE = 0.49f;
    private static final @NotNull String HORIZONTAL_SLIDER_FLAG = "SBS_HORZ";
    /**
     * The latency slider's steps from the left: good only, fair or better, any latency ({@code null}).
     */
    private static final @Nullable LatencyClass @NotNull [] LATENCY_STEPS = {LatencyClass.GOOD, LatencyClass.FAIR, null};
    private static final int ANY_LATENCY_STEP = 2;

    /**
     * The selected room's page ({@code GameSearchMapInfoPanel}): padding {@code 4}; a centered label ({@code .centerLabel}: {@code 8} px
     * {@code #a2b1f5}) over each of the name, the map and the game type ({@code .largeCenterText}: {@code eurostileext-reg} {@code 9} px,
     * line {@code 10}, white), {@code 4} apart; then the summary rows ({@code .infoRow}: the label right of the middle less {@code 5}, the
     * value left of it in white), {@code 8} below.
     */
    private static final float VALUE_SIZE = 9;
    private static final int VALUE_HEIGHT = 10;
    private static final int VALUE_TEXT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY, VALUE_SIZE, VALUE_HEIGHT);
    private static final float CENTER_GAP = 4;
    private static final float SUMMARY_GAP = 8;
    private static final float SUMMARY_LABEL_GAP = 5;
    private static final float SUMMARY_ROW_GAP = 2;
    private static final int VALUE_COLOR = 0xFFFFFFFF;
    /**
     * The map preview's place ({@code .mapImage}: {@code 105} px square, centered, the install's default map picture and its note while
     * the room's map is not local), {@code 4} above the labels.
     */
    private static final float PREVIEW_SIZE = 105;
    private static final float PREVIEW_TEXT_INSET = 6;
    private static final @NotNull String PREVIEW_IMAGE = "map_default.jpg";
    private static final int SIDE_COUNT = 2;
    private static final int CENTER_DIVISOR = 2;

    private static final @NotNull String VISIBLE_FLAG = "WS_VISIBLE";
    private static final @NotNull String TAB_STOP_FLAG = "WS_TABSTOP";
    private static final @NotNull String HOTKEY_FLAG = "WS_HOTKEY";
    private static final @NotNull String SMALL_TEXT_FLAG = "RC_SMALLTEXT";
    private static final @NotNull String HORIZONTAL_CENTER_FLAG = "FB_HTEXTCENTER";
    private static final @NotNull String VERTICAL_CENTER_FLAG = "FB_VTEXTCENTER";

    private static final @NotNull String NETWORK_ERROR_KEY = "gameNETWORKERROR";
    private static final @NotNull String PASSWORD_KEY = "bnet118_legacyFlow_password";
    private static final @NotNull String WRONG_PASSWORD_KEY = "bnet_joinGame_error_invalidPassword";
    /**
     * The password a private room's join asks for, at most as long as the create game page's.
     */
    private static final int PASSWORD_LENGTH = 32;
    /**
     * A private room's lock before its name ({@code lock_32.png} of the install's page images), as high as the row's text and {@code 2} before
     * the name.
     */
    private static final @NotNull String LOCK_ICON = "lock_32.png";
    private static final float LOCK_SIZE = 8;
    private static final float LOCK_GAP = 2;
    private static final int NAME_COLUMN = 1;

    private static @NotNull DialogControl createPageButton(int id, float left, float top, float width) {
        return ScrDialogControlUtils.createControl(ScrControlType.FLCBTN,
                                                   id,
                                                   Math.round(left), Math.round(top),
                                                   Math.round(width), SEARCH_ROW_HEIGHT,
                                                   Set.of(VISIBLE_FLAG, TAB_STOP_FLAG, HOTKEY_FLAG, HORIZONTAL_CENTER_FLAG, VERTICAL_CENTER_FLAG,
                                                          FRAMED_FLAG),
                                                   "",
                                                   List.of());
    }

    private static @NotNull DialogControl createText(@NotNull ScrControlType type, int id, int left, int top, int width) {
        return ScrDialogControlUtils.createControl(type, id, left, top, width, CHOICE_LABEL_HEIGHT, Set.of(VISIBLE_FLAG, SMALL_TEXT_FLAG), "",
                                                   List.of());
    }

    private static @NotNull DialogControl createFilterControl(@NotNull ScrControlType type, int id, int left, int top, int width, int height,
                                                              @NotNull String @NotNull ... flags) {
        Set<String> names = new HashSet<>(Set.of(flags));

        names.add(VISIBLE_FLAG);
        names.add(TAB_STOP_FLAG);

        return ScrDialogControlUtils.createControl(type, id, left, top, width, height, names, "", List.of());
    }

    /**
     * Returns the width of a page button: a fifth of the row at least, wide enough for its label (in the buttons' text) and its padding.
     *
     * @return the width
     */
    private static float getNavButtonWidth(@NotNull GlueCanvas canvas, @NotNull String label, float rowWidth) {
        return Math.max(rowWidth * NAV_BUTTON_SHARE, canvas.measure(GlueCanvas.NORMAL_FONT, label.substring(1)) + SIDE_COUNT * NAV_BUTTON_PADDING);
    }

    private static boolean isSameRoom(@NotNull FoundRoom first, @NotNull FoundRoom second) {
        return first.getHost().equals(second.getHost()) && first.getNumber() == second.getNumber();
    }

    private final @NotNull LobbyChatLog chatLog;
    private final @NotNull WebRowList rows;
    private final @NotNull RoomFilter filter;
    private @NotNull List<GameTemplates.Template> templates;

    /**
     * The listed rooms in row order; a {@code null} row is the label of the rooms of another version.
     */
    private @NotNull List<RoomFilter.@Nullable Entry> listed;
    private boolean filterShown;
    private boolean joining;
    private long lastRefresh;

    private @Nullable ChannelChat chat;
    private @Nullable EditControl search;
    private @Nullable EditControl chatInput;

    /**
     * Creates a {@link RoomListScreen} instance.
     */
    public RoomListScreen() {
        super(DIALOG, SHARED_PALETTE_FOLDER, ChannelChatScreen.BACKGROUND);

        this.chatLog   = LobbyChatLog.createChatPageLog(LobbyChatLog.Page.GLOBAL);
        this.rows      = new WebRowList(ROW_HEIGHT);
        this.filter    = new RoomFilter();
        this.templates = List.of();

        this.listed      = List.of();
        this.filterShown = false;
        this.joining     = false;
        this.lastRefresh = INITIAL_REFRESH_TIME;

        this.chat      = null;
        this.search    = null;
        this.chatInput = null;
    }

    private RoomFilter.@Nullable Entry getSelectedEntry() {
        int selection = this.rows.getSelection();

        return selection >= 0 && selection < this.listed.size() ? this.listed.get(selection) : null;
    }


    /**
     * Loads the screen's dialog: {@code rez\<dialog name>.ui.json}, or an empty dialog without a name. A screen whose
     * native content is not a control list builds its dialog here.
     * <p>
     * Keeps the dialog's controls and adds the page's title, the list page's search input and buttons, the filter controls and labels, the
     * chat input and the scroll bars.
     *
     * @param glueContext the shared services
     * @return the dialog
     * @throws IOException if the dialog cannot be read or has no list or chat page
     */
    @Override
    protected @NotNull DialogDefinition loadDialog(@NotNull GlueContext glueContext) throws IOException {
        DialogDefinition file = super.loadDialog(glueContext);
        DialogControl listPage = ScrDialogControlUtils.findControl(file, LIST_PAGE);
        DialogControl chatPage = ScrDialogControlUtils.findControl(file, CHAT_PAGE);

        if (listPage == null || chatPage == null) {
            throw Exceptions.newIOException("{0} has no list or chat page.", DIALOG);
        }

        GlueCanvas canvas = glueContext.getCanvas();
        GluText text = glueContext.getGluText();
        float rowLeft = listPage.getX() + INSET;
        float rowWidth = listPage.getWidth() - SIDE_COUNT * INSET;
        int rowTop = listPage.getY() + SEARCH_ROW_TOP;
        float createWidth = getNavButtonWidth(canvas, text.get("searchGameScreen_create"), rowWidth);
        float filterWidth = getNavButtonWidth(canvas, text.get("searchGameScreen_filter"), rowWidth);
        float refreshLeft = rowLeft + rowWidth - SEARCH_ROW_HEIGHT;
        float createLeft = refreshLeft - INSET - createWidth;
        float filterLeft = createLeft - INSET - filterWidth;
        int filterInnerLeft = Math.round(rowLeft) + FILTER_PANEL_INSET;
        int filterInnerWidth = Math.round(rowWidth) - SIDE_COUNT * FILTER_PANEL_INSET;
        int filterButtonTop = rowTop + FILTER_PANEL_HEIGHT - FILTER_PANEL_INSET - SEARCH_ROW_HEIGHT;
        float filterButtonWidth = filterInnerWidth * FILTER_BUTTON_SHARE;
        int choiceLeft = filterInnerLeft + FILTER_COLUMN_PADDING;
        int choiceWidth = filterInnerWidth - SIDE_COUNT * FILTER_COLUMN_PADDING;
        int columnTop = rowTop + FILTER_PANEL_INSET + FILTER_COLUMN_PADDING;
        int sliderOffset = CHOICE_LABEL_HEIGHT + CHOICE_LABEL_GAP;
        int valueOffset = sliderOffset + SLIDER_HEIGHT + SLIDER_VALUE_GAP;
        List<DialogControl> controls = new ArrayList<>(file.getControls());

        controls.add(createPageTitle(TITLE, listPage));
        controls.add(ScrDialogControlUtils.createControl(ScrControlType.EDITTEXT,
                                                         SEARCH,
                                                         Math.round(rowLeft), rowTop,
                                                         Math.round(filterLeft - INSET - rowLeft), SEARCH_ROW_HEIGHT,
                                                         Set.of(VISIBLE_FLAG, TAB_STOP_FLAG, SMALL_TEXT_FLAG, FRAMED_FLAG),
                                                         "",
                                                         List.of()));
        controls.add(createPageButton(FILTER, filterLeft, rowTop, filterWidth));
        controls.add(createPageButton(CREATE, createLeft, rowTop, createWidth));
        controls.add(ScrDialogControlUtils.createControl(ScrControlType.FLCBTN,
                                                         REFRESH,
                                                         Math.round(refreshLeft), rowTop,
                                                         SEARCH_ROW_HEIGHT, SEARCH_ROW_HEIGHT,
                                                         Set.of(VISIBLE_FLAG, TAB_STOP_FLAG, FRAMED_FLAG),
                                                         "",
                                                         List.of()));
        controls.add(createPageButton(RESET, filterInnerLeft, filterButtonTop, filterButtonWidth));
        controls.add(createPageButton(SAVE, filterInnerLeft + filterInnerWidth - filterButtonWidth, filterButtonTop, filterButtonWidth));

        controls.add(createText(ScrControlType.LTEXT, TYPE_LABEL, choiceLeft, columnTop, choiceWidth));
        controls.add(createText(ScrControlType.LTEXT, LATENCY_LABEL, choiceLeft, columnTop + LATENCY_LABEL_TOP, choiceWidth));
        controls.add(createText(ScrControlType.LTEXT, PLAYERS_LABEL, choiceLeft, columnTop + PLAYERS_LABEL_TOP, choiceWidth));
        controls.add(createText(ScrControlType.CTEXT, PLAYERS_VALUE, choiceLeft, columnTop + PLAYERS_LABEL_TOP + valueOffset, choiceWidth));
        controls.add(createFilterControl(ScrControlType.COMBOBOX, TYPE_CHOICE, choiceLeft, columnTop + TYPE_ROW_TOP, choiceWidth, CHOICE_HEIGHT,
                                         SMALL_TEXT_FLAG));
        controls.add(createFilterControl(ScrControlType.CHECKBOX, VERSIONS_CHECK, choiceLeft, columnTop + CHECK_ROW_TOP, choiceWidth, CHECK_HEIGHT,
                                         SMALL_TEXT_FLAG, WebPanelStyleUtils.FLAG));
        controls.add(createFilterControl(ScrControlType.SCROLLBAR, LATENCY_SLIDER, choiceLeft, columnTop + LATENCY_LABEL_TOP + sliderOffset,
                                         choiceWidth, SLIDER_HEIGHT, HORIZONTAL_SLIDER_FLAG));
        controls.add(createFilterControl(ScrControlType.SCROLLBAR, PLAYERS_SLIDER, choiceLeft, columnTop + PLAYERS_LABEL_TOP + sliderOffset,
                                         choiceWidth, SLIDER_HEIGHT, HORIZONTAL_SLIDER_FLAG));

        controls.add(WebScrollBar.createDefinition(LIST_SCROLL, listPage));
        controls.add(LobbyChatLog.createFieldInputDefinition(LobbyChatLog.Page.GLOBAL, chatPage, CHAT_INPUT));
        controls.add(WebScrollBar.createDefinition(CHAT_SCROLL, chatPage));

        return new DialogDefinition(file.getName(), file.getX(), file.getY(), file.getWidth(), file.getHeight(), controls);
    }

    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * Names the page buttons, fills the filter controls, prepares the inputs and the scroll bars, shows the channel chat of the network and
     * leaves a lobby or game the player comes back from.
     *
     * @param glueContext the shared services
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) {
        GluText text = glueContext.getGluText();
        String locale = glueContext.getLocale();

        this.bindControl(FILTER).setReplacedText(text.get("searchGameScreen_filter"));
        this.bindControl(CREATE).setReplacedText(text.get("searchGameScreen_create"));
        this.bindControl(RESET).setReplacedText(text.get("hotkeyPane_reset"));
        this.bindControl(SAVE).setReplacedText(text.get("hotkeyPane_save"));
        this.bindControl(TYPE_LABEL).setReplacedText(text.get("infoPane_gameType"));
        this.bindControl(LATENCY_LABEL).setReplacedText(text.get("searchGameScreen_acceptablePing"));
        this.bindControl(PLAYERS_LABEL).setReplacedText(text.get("searchGameScreen_maxPlayerCount"));
        this.setSwish(SWISH, SWISH_DELAY_MILLISECONDS);

        this.templates = GameTemplates.loadOrCreateDefaults(glueContext.getCanvas().getStorage(), LOGGER);
        this.filter.copy(glueContext.getRoomFilter());

        ComboBox types = this.addComboBox(TYPE_CHOICE);

        types.addItem(CommunityText.ANY.get(glueContext.getTextLocale()), RoomFilter.ANY_GAME_TYPE);

        for (int typeId : GameTemplates.getTypeIds(this.templates, template -> true)) {
            types.addItem(GameTemplates.getTypeName(text, typeId), typeId);
        }

        this.addSlider(LATENCY_SLIDER).setRange(0, LATENCY_STEPS.length - 1);
        this.addSlider(PLAYERS_SLIDER).setRange(RoomFilter.MINIMUM_PLAYERS, RoomFilter.MAXIMUM_PLAYERS);
        this.bindControl(VERSIONS_CHECK).setReplacedText(OwnText.SHOW_CLOSED_GAMES.get(locale));

        this.chatInput = this.setEditText(CHAT_INPUT, "", ChatChannel.MAXIMUM_TEXT_LENGTH, "");
        this.search    = this.setEditText(SEARCH, this.filter.getQuery(), RoomSettings.MAXIMUM_NAME_LENGTH, "");
        this.rows.setScrollBar(this.requireDialogState().attach(new WebScrollBar(this.bindControl(LIST_SCROLL).getDefinition())));
        this.chatLog.setScrollBar(this.requireDialogState().attach(new WebScrollBar(this.bindControl(CHAT_SCROLL).getDefinition())));
        this.showFilter(false);

        LanClient client = glueContext.getLanClient();

        // Back from a lobby or a game: the game's session is left (subtype 11) and its network thread stops.
        if (client != null && client.getState() != LanClient.State.BROWSING) {
            client.leave();
        }

        if (client == null || client.getNetwork().getBeaconLinkSession() == null) {
            this.showError(text.get(NETWORK_ERROR_KEY));

            return;
        }

        GameListScreen.setSaveDirectory(glueContext, client);

        ChannelChat opened = glueContext.getChannelChat();

        this.chat = opened;

        if (opened != null) {
            this.chatLog.listen(opened);
        }

        this.updateJoin();
    }

    /**
     * Called by {@link #update} for screen-specific timers.
     * <p>
     * Polls the network, follows a pending join and lists the found rooms again once a second.
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
            this.refreshRooms();
        }
    }

    /**
     * Lists the found rooms that pass the filter again, keeping the selected room selected while it is listed; rooms of blocked players are
     * not listed.
     */
    private void refreshRooms() {
        LanClient client = this.getContext().getLanClient();
        ChannelChat current = this.chat;

        if (client == null) {
            return;
        }

        RoomFilter.Entry selected = this.getSelectedEntry();
        List<FoundRoom> rooms = client.getNetwork().getRooms();
        List<RoomFilter.Entry> selection = this.filter.select(current == null ? rooms : current.listUnblockedRooms(rooms), this.createNames());
        List<RoomFilter.Entry> listed = new ArrayList<>(selection.size() + 1);
        int selectedRow = WebRowList.NO_SELECTION;
        boolean labeled = false;

        for (RoomFilter.Entry entry : selection) {
            if (!entry.isJoinable() && !labeled) {
                listed.add(null);
                labeled = true;
            }

            if (selected != null && isSameRoom(selected.getRoom(), entry.getRoom())) {
                selectedRow = listed.size();
            }

            listed.add(entry);
        }

        if (selectedRow == WebRowList.NO_SELECTION && !listed.isEmpty() && listed.getFirst() != null) {
            selectedRow = 0;
        }

        this.listed = listed;
        this.rows.setSelection(selectedRow);
        this.updateJoin();
    }

    private RoomFilter.@NotNull Names createNames() {
        GlueContext glueContext = this.getContext();

        return new RoomFilter.Names() {
            /**
             * @param gameType the game type identifier
             * @return the game type's shown name
             */
            @Override
            public @NotNull String getGameTypeName(int gameType) {
                return GameTemplates.getTypeName(glueContext.getGluText(), gameType);
            }

            /**
             * @param room the room
             * @return the shown name of the room's host, or an empty string if it is not known
             */
            @Override
            public @NotNull String getHostName(@NotNull FoundRoom room) {
                return RoomListScreen.this.findHostName(room);
            }
        };
    }

    private @Nullable BeaconLinkSession getSession() {
        LanClient client = this.getContext().getLanClient();

        return client == null ? null : client.getNetwork().getBeaconLinkSession();
    }

    /**
     * @return the tagged name of a room's host, or an empty string if the network has no presence of it
     */
    private @NotNull String findHostName(@NotNull FoundRoom room) {
        BeaconLinkSession session = this.getSession();
        PlayerPresence host = session == null ? null : session.getCommunity().getPresence(room.getHost().getPlayer());

        return host == null ? "" : host.getTaggedName();
    }

    /**
     * @return the mod of a room: the host's mod identifier and version, which the room's advertisement carries, with a note for a room of
     * another version
     */
    private @NotNull String describeMod(RoomFilter.@NotNull Entry entry) {
        Locale locale = this.getContext().getTextLocale();
        FoundRoom room = entry.getRoom();
        String mod = room.getModId().isEmpty() ? CommunityText.UNKNOWN.get(locale) : room.getModId() + MOD_SEPARATOR + room.getModVersion();

        return room.getJoinRefusal() == null ? mod : mod + ROOM_NOTE_SEPARATOR + CommunityText.OTHER_VERSION.get(locale);
    }

    private void updateJoin() {
        RoomFilter.Entry selected = this.getSelectedEntry();

        this.bindControl(JOIN).setEnabled(selected != null && selected.isJoinable() && !this.joining && !this.filterShown);
    }

    private void showFilter(boolean shown) {
        GluText text = this.getContext().getGluText();

        this.filterShown = shown;
        this.bindControl(TITLE).setReplacedText(text.get(shown ? "searchGameScreen_filterSettings" : "searchGameScreen_customGames"));

        for (int control : LIST_CONTROLS) {
            this.bindControl(control).setVisible(!shown);
        }

        for (int control : FILTER_CONTROLS) {
            this.bindControl(control).setVisible(shown);
        }

        if (shown) {
            this.showChoices(this.filter);
        }

        this.updateJoin();
    }

    private void showChoices(@NotNull RoomFilter shown) {
        int latencyStep = ANY_LATENCY_STEP;

        for (int step = 0; step < LATENCY_STEPS.length; step++) {
            if (LATENCY_STEPS[step] == shown.getWorstLatency()) {
                latencyStep = step;
            }
        }

        if (this.requireDialogState().requireControl(TYPE_CHOICE) instanceof ComboBox choice && !choice.selectValue(shown.getGameType())) {
            choice.select(0);
        }

        this.getSlider(LATENCY_SLIDER).setValue(latencyStep);
        this.getSlider(PLAYERS_SLIDER).setValue(shown.getMaximumPlayers());
        this.bindControl(VERSIONS_CHECK).setChecked(shown.isClosedRoomsShown());
        this.updatePlayersValue();
    }

    /**
     * Shows the player count slider's value under it.
     */
    private void updatePlayersValue() {
        this.bindControl(PLAYERS_VALUE).setReplacedText(Integer.toString(this.getSlider(PLAYERS_SLIDER).getValue()));
    }

    /**
     * Called after the user changed the selection of a list or combo box, or the value of a slider.
     * <p>
     * Shows the player count slider's new value.
     *
     * @param control the control id
     */
    @Override
    protected void onSelectionChanged(int control) {
        if (control == PLAYERS_SLIDER) {
            this.updatePlayersValue();
        }
    }

    private @NotNull Slider getSlider(int control) {
        if (!(this.requireDialogState().requireControl(control) instanceof Slider slider)) {
            throw Exceptions.newIllegalStateException("{0} has no slider {1}.", DIALOG, control);
        }

        return slider;
    }

    /**
     * Takes the filter settings the controls show into the filter, which the network keeps.
     */
    private void saveFilter() {
        int gameType = this.requireDialogState().requireControl(TYPE_CHOICE) instanceof ComboBox choice
                       ? choice.getSelectedValue(RoomFilter.ANY_GAME_TYPE)
                       : RoomFilter.ANY_GAME_TYPE;

        this.filter.setGameType(gameType);
        this.filter.setWorstLatency(LATENCY_STEPS[this.getSlider(LATENCY_SLIDER).getValue()]);
        this.filter.setMaximumPlayers(this.getSlider(PLAYERS_SLIDER).getValue());
        this.filter.setClosedRoomsShown(this.bindControl(VERSIONS_CHECK).isChecked());
        this.getContext().getRoomFilter().copy(this.filter);
    }

    /**
     * Called after the text of an {@code EDITTEXT} changed.
     * <p>
     * Lists the rooms again for a changed search text; a whisper start typed in the chat input becomes the input's whisper target.
     *
     * @param control the control id
     */
    @Override
    protected void onEditChanged(int control) {
        EditControl input = this.search;
        String chatText = control == CHAT_INPUT ? LobbyChatLog.takeWhisperStart(this.chat, this.chatInput) : null;

        if (chatText != null) {
            this.chatInput = this.setEditText(CHAT_INPUT, chatText, ChatChannel.MAXIMUM_TEXT_LENGTH, "");
        }

        if (control == SEARCH && input != null) {
            this.filter.setQuery(input.getEditText().getText());
            this.getContext().getRoomFilter().setQuery(this.filter.getQuery());
            this.refreshRooms();
        }
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     * <p>
     * Join joins the selected room; Filter shows the filter settings, Save takes them and Reset sets them to pass every room; Create opens
     * the game creation; Refresh lists the found rooms again; Cancel closes the filter settings, or returns to the channel chat.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
        GlueContext glueContext = this.getContext();

        switch (control) {
            case JOIN -> this.joinSelected();
            case FILTER -> this.showFilter(true);
            case RESET -> this.showChoices(new RoomFilter());
            case SAVE -> {
                this.saveFilter();
                this.showFilter(false);
                this.refreshRooms();
            }
            case CREATE -> this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.CREATE_GAME));
            case REFRESH -> this.refreshRooms();
            case CANCEL -> {
                if (this.filterShown) {
                    this.showFilter(false);

                    return;
                }

                this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.CHANNEL_CHAT));
            }
            default -> {
            }
        }
    }

    /**
     * Joins the selected room; a private room first asks for its password with the shared edit popup.
     */
    private void joinSelected() {
        RoomFilter.Entry selected = this.getSelectedEntry();

        if (selected == null || !selected.isJoinable() || this.joining) {
            return;
        }

        if (!selected.getRoom().isPrivate()) {
            this.join(selected, "");

            return;
        }

        this.showPopup(new GlueEditPopup(this.getPaletteFolder(), this.getContext().getGluText().get(PASSWORD_KEY), PASSWORD_LENGTH, "",
                                         password -> {
                                             if (password != null) {
                                                 this.join(selected, password);
                                             }
                                         }));
    }

    private void join(RoomFilter.@NotNull Entry selected, @NotNull String password) {
        GlueContext glueContext = this.getContext();
        LanClient client = glueContext.getLanClient();

        if (client == null || this.joining) {
            return;
        }

        InetSocketAddress address = BeaconLinkProvider.getAddress(selected.getRoom().getHost());
        LanDiscovery.Game game = null;

        for (LanDiscovery.Game listedGame : client.getGames()) {
            if (listedGame.getAddress().equals(address)) {
                game = listedGame;
            }
        }

        if (game == null) {
            return;
        }

        try {
            client.getNetwork().setPassword(password);
            client.join(game, GameProxySocket.GAME_PORT);
            this.joining = true;
            this.updateJoin();
        } catch (IOException exception) {
            LOGGER.warning("BEACON.LINK JOIN FAILED: " + exception.getMessage());
            this.showError(glueContext.getNetworkText().get(GameListScreen.JOIN_ERROR_KEY));
        }
    }

    private void followJoin(@NotNull LanClient client) {
        GlueContext glueContext = this.getContext();

        // The join stays pending while the screen slides away, so that no second join starts.
        if (GameListScreen.isLobbyReady(client)) {
            this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.GAME_LOBBY));
        } else if (client.getState() == LanClient.State.FAILED) {
            this.joining = false;
            this.updateJoin();
            boolean wrongPassword = client.getNetwork().getJoinRefusal() == JoinRefusal.WRONG_PASSWORD;

            this.showError(wrongPassword ? glueContext.getGluText().get(WRONG_PASSWORD_KEY)
                                         : GameListScreen.getJoinFailureText(glueContext, client));
        }
    }

    /**
     * Called first for every key of the screen (after an open popup): a screen without controls, such as a cinematic, takes its keys here.
     * <p>
     * Takes Enter while the chat input has the focus and holds a line, and says or whispers the line or runs the command, and Backspace in
     * the empty input after a whisper, which says lines in the channel again; otherwise Enter joins.
     *
     * @param key the key
     * @return {@code true} if the key was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onKey(char key) {
        EditControl input = this.chatInput;
        String text = LobbyChatLog.takeInputKey(this.chat, input, input != null && this.getFocusedEdit() == input, key);

        if (text == null) {
            return false;
        }

        this.chatInput = this.setEditText(CHAT_INPUT, text, ChatChannel.MAXIMUM_TEXT_LENGTH, "");

        return true;
    }

    /**
     * Called first for every press of the screen that no open popup or open combo box takes, while the screen takes input.
     * <p>
     * Takes presses on the scroll bars and the chat text (a selection), and selects the room under a press on the list; a double press joins it.
     *
     * @param x the interface x
     * @param y the interface y
     * @return {@code true} if the press was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onPointerPressed(float x, float y) {
        if (this.chatLog.press(this.getContext().getCanvas(), x, y, this.isExtendingPress(), this.isDoublePress()) || (!this.filterShown && this.rows.press(x, y))) {
            return true;
        }

        DialogControl page = ScrDialogControlUtils.findControl(this.getDialog(), LIST_PAGE);

        if (page == null || this.filterShown) {
            return false;
        }

        float left = this.getDialogLeft() + page.getX() + LIST_LEFT;
        float top = this.getDialogTop() + page.getY() + ROWS_TOP;
        float width = page.getWidth() - LIST_LEFT - INSET;

        if (!GlueCanvas.contains(x, y, left, top, width, ROWS_HEIGHT)) {
            return false;
        }

        int row = this.rows.findRow(x, y, left, top, width, ROWS_HEIGHT, this.listed.size());

        this.rows.setSelection(row >= 0 && this.listed.get(row) == null ? WebRowList.NO_SELECTION : row);
        this.updateJoin();

        if (this.isDoublePress()) {
            this.joinSelected();
        }

        return true;
    }

    /**
     * Called for every pointer move of the screen (after an open combo box or popup takes it).
     * <p>
     * Drags the scroll bars or the chat selection.
     *
     * @param x the interface x
     * @param y the interface y
     */
    @Override
    protected void onPointerMoved(float x, float y) {
        this.chatLog.drag(this.getContext().getCanvas(), x, y);
        this.rows.drag(y);
    }

    /**
     * Called for every release of the left button of the screen.
     * <p>
     * Releases the scroll bars and the chat selection.
     *
     * @param x the interface x
     * @param y the interface y
     */
    @Override
    protected void onPointerReleased(float x, float y) {
        this.chatLog.release();
        this.rows.release();
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
     * Called first for a wheel notch of the screen.
     * <p>
     * Scrolls the chat log or the room list under the pointer.
     *
     * @param x    the pointer's interface x
     * @param y    the pointer's interface y
     * @param down {@code true} for a notch toward the user
     * @return {@code true} if the notch was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onWheel(float x, float y, boolean down) {
        float dialogX = x - this.getDialogLeft();
        float dialogY = y - this.getDialogTop();
        DialogControl chatPage = ScrDialogControlUtils.findControl(this.getDialog(), CHAT_PAGE);
        DialogControl listPage = ScrDialogControlUtils.findControl(this.getDialog(), LIST_PAGE);

        if (chatPage != null && GlueCanvas.contains(dialogX, dialogY, chatPage.getX(), chatPage.getY(), chatPage.getWidth(), chatPage.getHeight())) {
            this.chatLog.scrollByWheel(down);

            return true;
        }

        if (listPage != null && !this.filterShown &&
            GlueCanvas.contains(dialogX, dialogY, listPage.getX(), listPage.getY(), listPage.getWidth(), listPage.getHeight())) {
            this.rows.scrollByWheel(down);

            return true;
        }

        return false;
    }

    /**
     * Draws one control.
     * <p>
     * Draws the list page, the selected room's page and the chat log on their pages, and the refresh icon on its button; the other controls
     * draw as on every screen.
     *
     * @param canvas  the glue canvas
     * @param control the control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IOException if an image cannot be read
     */
    @Override
    protected void drawControl(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) throws IOException {
        switch (control.getId()) {
            case LIST_PAGE -> {
                if (this.filterShown) {
                    this.drawFilterPage(canvas, control, left, top);
                } else {
                    this.drawListPage(canvas, control, left, top);
                }
            }
            case INFO_PAGE -> {
                if (!this.filterShown) {
                    this.drawInfoPage(canvas, control, left, top);
                }
            }
            case REFRESH -> {
                super.drawControl(canvas, control, left, top);
                canvas.drawImage(canvas.getWebImage(REFRESH_ICON),
                                 left + REFRESH_ICON_INSET, top + REFRESH_ICON_INSET,
                                 control.getWidth() - SIDE_COUNT * REFRESH_ICON_INSET, control.getHeight() - SIDE_COUNT * REFRESH_ICON_INSET);
            }
            case CHAT_PAGE -> this.chatLog.draw(canvas, left, top, control.getWidth(), control.getHeight());
            default -> super.drawControl(canvas, control, left, top);
        }
    }

    /**
     * Draws the list page: the header and the rooms; the network's failure or a note that no room is listed when there is none. The title,
     * the search field and the page buttons draw as controls.
     *
     * @throws IOException if an image cannot be read
     */
    private void drawListPage(@NotNull GlueCanvas canvas, @NotNull DialogControl page, float left, float top) throws IOException {
        GlueContext glueContext = this.getContext();
        GluText text = glueContext.getGluText();
        float width = page.getWidth();
        float listLeft = left + LIST_LEFT;
        float listWidth = width - LIST_LEFT - INSET;
        String[] headings = {text.get("infoPane_players"), text.get("infoPane_gameName"), text.get("infoPane_gameType"), text.get("infoPane_mapName"),
                             ""};

        this.drawColumns(canvas, headings, WebPanelStyleUtils.TEXT, listLeft, top + HEADER_TOP, listWidth, HEADER_HEIGHT, false);

        float rowsTop = top + ROWS_TOP;

        if (this.listed.isEmpty()) {
            LanClient client = glueContext.getLanClient();
            String failure = client == null ? null : client.getNetwork().getFailureText();

            WebPanelStyleUtils.drawCenteredLine(canvas,
                                                PAGE_TEXT,
                                                failure == null ? CommunityText.NO_GAMES.get(glueContext.getTextLocale()) : failure,
                                                listLeft, rowsTop,
                                                listWidth, ROWS_HEIGHT,
                                                GlueCanvas.FORMAT_CENTER,
                                                ROW_TEXT_COLOR);
        }

        this.rows.draw(canvas,
                       listLeft, rowsTop,
                       listWidth, ROWS_HEIGHT,
                       this.listed.size(),
                       (rowCanvas, row, selected, rowLeft, rowTop, rowWidth) -> this.drawRoom(rowCanvas, row, selected, rowLeft, rowTop, rowWidth));
    }

    /**
     * Draws one cell text per column of a row or the header, cut to its column, from the row's left padding to its right padding; a private
     * room's name follows its lock.
     *
     * @throws IOException if the lock image cannot be read
     */
    private void drawColumns(@NotNull GlueCanvas canvas, @NotNull String @NotNull [] cells, int color,
                             float left, float top, float width, float height,
                             boolean locked) throws IOException {
        float cellLeft = left + HEADER_LEFT_PADDING;
        float columnsWidth = width - HEADER_LEFT_PADDING - HEADER_RIGHT_PADDING;

        for (int column = 0; column < cells.length; column++) {
            float cellWidth = columnsWidth * COLUMN_SHARES[column];
            float textLeft = cellLeft;

            if (locked && column == NAME_COLUMN) {
                canvas.drawImage(canvas.getWebImage(LOCK_ICON), cellLeft, top + (height - LOCK_SIZE) / SIDE_COUNT, LOCK_SIZE, LOCK_SIZE);
                textLeft += LOCK_SIZE + LOCK_GAP;
            }

            float textWidth = cellWidth - (textLeft - cellLeft);

            if (!cells[column].isEmpty()) {
                WebPanelStyleUtils.drawCenteredLine(canvas,
                                                    PAGE_TEXT,
                                                    WebPanelStyleUtils.fitText(canvas, PAGE_TEXT, cells[column], textWidth - INSET),
                                                    textLeft, top,
                                                    textWidth, height,
                                                    GlueCanvas.FORMAT_LEFT,
                                                    color);
            }

            cellLeft += cellWidth;
        }
    }

    /**
     * Draws a room row (players, name with a lock before it for a private room, game type, map and latency bars; a line above it; the
     * selected one framed and highlighted, the closed rooms grey), or the label of the closed rooms.
     *
     * @throws IOException if an image cannot be read
     */
    private void drawRoom(@NotNull GlueCanvas canvas, int row, boolean selected, float left, float top, float width) throws IOException {
        GlueContext glueContext = this.getContext();
        RoomFilter.Entry entry = this.listed.get(row);

        if (entry == null) {
            int count = this.listed.size() - row - 1;
            String label = CommunityText.CLOSED_GAMES.get(glueContext.getTextLocale());

            WebPanelStyleUtils.drawCenteredLine(canvas,
                                                PAGE_TEXT,
                                                String.format(Locale.ROOT, GROUP_FORMAT, label, count),
                                                left + HEADER_LEFT_PADDING, top,
                                                width - HEADER_LEFT_PADDING, ROW_HEIGHT,
                                                GlueCanvas.FORMAT_LEFT,
                                                WebPanelStyleUtils.TEXT);

            return;
        }

        RoomSettings settings = entry.getRoom().getSettings();
        ScrRoomData data = entry.getData();
        int color = !entry.isJoinable() ? WebPanelStyleUtils.DISABLED_TEXT : selected ? WebPanelStyleUtils.HIGHLIGHT_TEXT : ROW_TEXT_COLOR;
        String[] cells = {String.format(Locale.ROOT, PLAYERS_FORMAT, settings.getPlayers(), settings.getCapacity()),
                          settings.getRoomName(),
                          data == null ? CommunityText.UNKNOWN.get(glueContext.getTextLocale())
                                       : GameTemplates.getTypeName(glueContext.getGluText(), data.getGameType()),
                          settings.getMapName(),
                          ""};

        canvas.fillRectangle(left, top, width, 1, WebPanelStyleUtils.DISABLED_BORDER);

        if (selected) {
            WebPanelStyleUtils.drawRoundedFrame(canvas, left, top, width, ROW_HEIGHT, WebPanelStyleUtils.HIGHLIGHT_TEXT);
        }

        this.drawColumns(canvas, cells, color, left, top, width, ROW_HEIGHT, entry.getRoom().isPrivate());

        if (entry.isJoinable()) {
            float columnsWidth = width - HEADER_LEFT_PADDING - HEADER_RIGHT_PADDING;
            float latencyLeft = left + HEADER_LEFT_PADDING + columnsWidth * (1 - COLUMN_SHARES[LATENCY_COLUMN]);

            LatencyBarsUtils.draw(canvas,
                                  LatencyBarsUtils.getBarCount(entry.getRoom().getLatencyEstimate().getLatencyClass()),
                                  latencyLeft, top,
                                  ROW_HEIGHT);
        }
    }

    /**
     * Draws the filter settings in the list page's place: the settings' frame and the latency slider's value under it (its bars); the title,
     * the labels, the drop-down, the check box, the sliders, the player count and the buttons draw as controls.
     *
     * @throws IOException if a bar image cannot be read
     */
    private void drawFilterPage(@NotNull GlueCanvas canvas, @NotNull DialogControl page, float left, float top) throws IOException {
        float panelLeft = left + INSET;
        float panelTop = top + SEARCH_ROW_TOP;
        float panelWidth = page.getWidth() - SIDE_COUNT * INSET;
        float labelLeft = panelLeft + FILTER_PANEL_INSET + FILTER_COLUMN_PADDING;
        float labelWidth = panelWidth - SIDE_COUNT * (FILTER_PANEL_INSET + FILTER_COLUMN_PADDING);
        float columnTop = panelTop + FILTER_PANEL_INSET + FILTER_COLUMN_PADDING;
        float valueOffset = CHOICE_LABEL_HEIGHT + CHOICE_LABEL_GAP + SLIDER_HEIGHT + SLIDER_VALUE_GAP;

        WebPanelStyleUtils.drawBox(canvas,
                                   panelLeft, panelTop, panelWidth, FILTER_PANEL_HEIGHT,
                                   FILTER_PANEL_RADIUS,
                                   WebPanelStyleUtils.BORDER, FILTER_PANEL_FILL,
                                   true, true,
                                   true, true);

        LatencyClass latency = LATENCY_STEPS[this.getSlider(LATENCY_SLIDER).getValue()];
        int bars = latency == null ? LatencyBarsUtils.getBarCount(LatencyClass.POOR) : LatencyBarsUtils.getBarCount(latency);

        LatencyBarsUtils.draw(canvas,
                              bars,
                              labelLeft + (labelWidth - bars * LatencyBarsUtils.BAR_WIDTH) / CENTER_DIVISOR,
                              columnTop + LATENCY_LABEL_TOP + valueOffset,
                              CHOICE_LABEL_HEIGHT);
    }

    /**
     * Draws the selected room: the map preview's place (the room's map is not local: the install's default picture and its note), the name,
     * the map and the game type centered under their labels, then the players, the host, the map size, the speed and the mod.
     *
     * @throws IOException if the preview image cannot be read
     */
    private void drawInfoPage(@NotNull GlueCanvas canvas, @NotNull DialogControl page, float left, float top) throws IOException {
        RoomFilter.Entry entry = this.getSelectedEntry();

        if (entry == null) {
            return;
        }

        GlueContext glueContext = this.getContext();
        GluText text = glueContext.getGluText();
        String locale = glueContext.getLocale();
        String unknown = CommunityText.UNKNOWN.get(glueContext.getTextLocale());
        RoomSettings settings = entry.getRoom().getSettings();
        ScrRoomData data = entry.getData();
        float innerLeft = left + INSET;
        float innerWidth = page.getWidth() - SIDE_COUNT * INSET;
        float labelHeight = canvas.getLineHeight(PAGE_TEXT);
        float previewLeft = innerLeft + (innerWidth - PREVIEW_SIZE) / CENTER_DIVISOR;

        canvas.drawImage(canvas.getWebImage(PREVIEW_IMAGE), previewLeft, top + INSET, PREVIEW_SIZE, PREVIEW_SIZE);
        WebPanelStyleUtils.drawText(canvas,
                                    VALUE_TEXT,
                                    text.get("infoPane_noMapImage"),
                                    previewLeft + PREVIEW_TEXT_INSET, top + INSET,
                                    PREVIEW_SIZE - SIDE_COUNT * PREVIEW_TEXT_INSET, PREVIEW_SIZE,
                                    GlueCanvas.FORMAT_CENTER | GlueCanvas.FORMAT_MIDDLE,
                                    VALUE_COLOR);

        float rowTop = top + INSET + PREVIEW_SIZE + CENTER_GAP;
        String[][] centered = {{text.get("infoPane_gameName"), settings.getRoomName()},
                               {text.get("infoPane_mapName"), settings.getMapName()},
                               {text.get("infoPane_gameType"), data == null ? unknown : GameTemplates.getTypeName(text, data.getGameType())}};

        for (String[] item : centered) {
            WebPanelStyleUtils.drawCenteredLine(canvas, PAGE_TEXT, item[0], innerLeft, rowTop, innerWidth, labelHeight, GlueCanvas.FORMAT_CENTER,
                                                WebPanelStyleUtils.TEXT);
            rowTop += labelHeight;
            WebPanelStyleUtils.drawCenteredLine(canvas,
                                                VALUE_TEXT,
                                                WebPanelStyleUtils.fitText(canvas, VALUE_TEXT, item[1], innerWidth),
                                                innerLeft, rowTop,
                                                innerWidth, VALUE_HEIGHT,
                                                GlueCanvas.FORMAT_CENTER,
                                                VALUE_COLOR);
            rowTop += VALUE_HEIGHT + CENTER_GAP;
        }

        String hostName = this.findHostName(entry.getRoom());
        String mod = this.describeMod(entry);
        String[][] summary = {{text.get("infoPane_players"), String.format(Locale.ROOT, PLAYERS_FORMAT, settings.getPlayers(), settings.getCapacity())},
                              {text.get("infoPane_gameCreator"), hostName.isEmpty() ? unknown : hostName},
                              {text.get("infoPane_size"),
                               data == null ? unknown : String.format(Locale.ROOT, SIZE_FORMAT, data.getMapWidth(), data.getMapHeight())},
                              {text.get("infoPane_speed"), data == null ? unknown : GameListScreen.getSpeedName(glueContext, data.getSpeed())},
                              {OwnText.MOD.get(locale), mod}};
        float middle = innerLeft + innerWidth / CENTER_DIVISOR;
        float valueWidth = innerLeft + innerWidth - middle;

        rowTop += SUMMARY_GAP - CENTER_GAP;

        for (String[] item : summary) {
            WebPanelStyleUtils.drawCenteredLine(canvas, PAGE_TEXT, item[0] + LABEL_SUFFIX, innerLeft, rowTop, middle - innerLeft - SUMMARY_LABEL_GAP,
                                                labelHeight, GlueCanvas.FORMAT_RIGHT, WebPanelStyleUtils.TEXT);
            WebPanelStyleUtils.drawCenteredLine(canvas,
                                                PAGE_TEXT,
                                                WebPanelStyleUtils.fitText(canvas, PAGE_TEXT, item[1], valueWidth),
                                                middle, rowTop,
                                                valueWidth, labelHeight,
                                                GlueCanvas.FORMAT_LEFT,
                                                VALUE_COLOR);
            rowTop += labelHeight + SUMMARY_ROW_GAP;
        }
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
     * Shows the install's search hint in the empty search field; other fields have none.
     *
     * @param control an {@code EDITTEXT} control with {@link #FRAMED_FLAG}
     * @return the hint the text field shows in gray while it is empty, or an empty string for none
     */
    @Override
    protected @NotNull String getEditHint(@NotNull DialogControl control) {
        return control.getId() == SEARCH ? this.getContext().getGluText().get("searchGameScreen_searchCustomGames") : super.getEditHint(control);
    }

    /**
     * Shows the magnifier in the search field; other fields have none.
     *
     * @param control an {@code EDITTEXT} control with {@link #FRAMED_FLAG}
     * @return the image the text field shows left of its text, or an empty string for none
     */
    @Override
    protected @NotNull String getEditIcon(@NotNull DialogControl control) {
        return control.getId() == SEARCH ? SEARCH_ICON : super.getEditIcon(control);
    }

    /**
     * Shows the chat's channel or whisper target before the chat input's text; other fields have none.
     *
     * @param control an {@code EDITTEXT} control with {@link #FRAMED_FLAG}
     * @return the text the field shows before its text, or an empty string for none
     */
    @Override
    protected @NotNull String getEditPrefix(@NotNull DialogControl control) {
        return control.getId() == CHAT_INPUT ? LobbyChatLog.getInputPrefix(this.chat) : super.getEditPrefix(control);
    }

    /**
     * Selects the chat's prefix color for the chat input's prefix.
     *
     * @param control an {@code EDITTEXT} control with {@link #FRAMED_FLAG}
     * @return the packed color of the field's prefix
     */
    @Override
    protected int getEditPrefixColor(@NotNull DialogControl control) {
        return control.getId() == CHAT_INPUT ? LobbyChatLog.getInputPrefixColor(this.chat) : super.getEditPrefixColor(control);
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
     * Releases the videos and the popup.
     * <p>
     * Detaches the screen's log from the chat first; the chat keeps listening and the player stays in the channel.
     */
    @Override
    public void close() {
        ChannelChat current = this.chat;

        if (current != null) {
            this.chatLog.stopListening(current);
        }

        super.close();
    }
}
