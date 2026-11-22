package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatChannel;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChatEntryKind;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.CommunityText;
import org.fogofwarlabs.sc4j.engine.net.lobby.MapFolders;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.scr.client.glue.*;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ComboBox;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.EditControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.WebPanelStyleUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.WebScrollBar;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.net.lan.LanClient;
import org.fogofwarlabs.sc4j.scr.render.ui.TrueTypeText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shows the Beacon.Link channel chat on the install's chat dialog ({@code gluBattlenetChat}): the channel's log with the input field under
 * it (the pages' chat log), and the participants page at the right: this player's name and status (online or away), the Friends and Channel
 * tabs, the tab's list (the friends grouped online and offline, or the channel's members; each group folds open or shut at its label) with
 * each player's status, mod and latency, and the tab's button (Add Friend, Join Channel). The play menu holds Create, Join and the disabled
 * Ladder and Records; Whisper and Leave sit at the bottom right. Typed lines go to the channel, or to the whisper target the input shows
 * before its text; slash commands ({@code /help}) whisper, join or leave channels and manage friends and the ignore list. A player's context
 * menu whispers, adds or removes the friend and blocks or unblocks the player. Channels are shared by every mod.
 * <p>
 * The screen opens the network of the multiplayer screens (the room list, the lobby and the chat use the same one) and enters the chat once a
 * node registered the session; a failed connection shows the network's error box, which returns to the connection choice. The chat of the network stays
 * open while the player is in a room or a game, and the screen shows its recent log again on every visit.
 */
public final class ChannelChatScreen extends GlueScreen {
    /**
     * The chat page's background, behind every Beacon.Link screen.
     */
    static final @NotNull String BACKGROUND = "glue/Battlenet/Chat/BackGnd.dds";

    private static final @NotNull Logger LOGGER = Logger.of(ChannelChatScreen.class);

    private static final @NotNull String DIALOG = "gluBattlenetChat";
    private static final @NotNull String PALETTE_FOLDER = "glue/Battlenet";
    /**
     * The base name of the SC:R mod's default channels ({@code <base>-1}, {@code <base>-2}, ...).
     */
    private static final @NotNull String CHANNEL_BASE = "StarCraft Remastered";

    private static final int CHAT_PANEL = 2;
    private static final int USER_PANEL = 5;
    private static final int PLAY_PANEL = 6;
    private static final int BUTTON_PANEL = 3;
    private static final int CREATE = 12;
    private static final int JOIN = 13;
    /**
     * The play menu's third and fourth buttons, Ladder and Records, shown disabled: Beacon.Link has no ladder and keeps no records.
     */
    private static final int LADDER = 11;
    private static final int RECORDS = 10;
    /**
     * The button left of Leave (the news'), which the chat uses for Whisper.
     */
    private static final int WHISPER = 18;
    private static final int LEAVE = 14;
    private static final int CHAT_PAGE = 15;
    private static final int USER_PAGE = 16;
    /**
     * The dialog's controls the chat does not use: the second play menu, the matchmaking menus and page and the advertisement.
     */
    private static final int @NotNull [] HIDDEN = {7, 8, 9, 17, 21, 22};
    private static final int CHAT_INPUT = 90;
    /**
     * The synthesized ids of the chat log's and the participants list's scroll bars ({@link WebScrollBar#createDefinition}), the status
     * drop-down and the tab's button.
     */
    private static final int CHAT_SCROLL = 91;
    private static final int USER_SCROLL = 92;
    private static final int STATUS_CHOICE = 93;
    private static final int TAB_BUTTON = 94;
    /**
     * Slides the chat and play menus in from the left, the user menu from the right and the button holder from below, with no delay.
     */
    private static final int @NotNull [] SWISH = {CHAT_PANEL, 0, PLAY_PANEL, 0, USER_PANEL, 2, BUTTON_PANEL, 3};
    private static final int SWISH_DELAY_MILLISECONDS = 0;

    /**
     * The participants page ({@code ParticipantsPanel}, page-relative): this player's portrait, the name line and the status drop-down
     * (the local network's create game drop-down, {@code CreateGameScreen.CHOICE_HEIGHT} high) right of it; the tabs
     * ({@code .listSelection}: margin and holder padding {@code 4} each, padding right {@code 9}; {@code .tabHolder}: padding {@code 4},
     * {@code eurostile-reg} {@code 10} px, centered, a {@code 1} px line under each, {@code #404040}, {@code #b4fc74} under the active tab whose
     * text is {@code #b4fc74}, the other's {@code #4cc428}), the list inside the holder's and the list's padding ({@code .listHolder}: {@code 4})
     * and the tab's button at the bottom (the screens' framed button).
     */
    private static final int PAGE_INSET = 8;
    private static final float PORTRAIT_LEFT = 14;
    private static final float PORTRAIT_TOP = 14;
    private static final float PORTRAIT_SIZE = 24;
    private static final int HEADER_LEFT = 53;
    private static final float NAME_TOP = 14;
    private static final int STATUS_TOP = 27;
    private static final int STATUS_WIDTH = 84;
    private static final int STATUS_HEIGHT = CreateGameScreen.CHOICE_HEIGHT;
    private static final float TAB_TOP = 54;
    private static final float TAB_HEIGHT = 21;
    private static final float TAB_RIGHT_PADDING = 9;
    private static final float TAB_TEXT_SIZE = 10;
    private static final int TAB_TEXT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY, TAB_TEXT_SIZE,
                                                                       WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    private static final int TAB_LINE_COLOR = 0x404040FF;
    private static final float LIST_TOP = TAB_TOP + TAB_HEIGHT + 4;
    private static final float LIST_GAP = 4;
    private static final int BUTTON_HEIGHT = FRAMED_CONTROL_HEIGHT;
    private static final int SIDE_COUNT = 2;
    private static final @NotNull String TAB_FORMAT = "%s (%d)";
    private static final @NotNull String PORTRAIT = "App.png";
    /**
     * The status drop-down's values.
     */
    private static final int AVAILABLE_VALUE = 0;
    private static final int AWAY_VALUE = 1;
    private static final int NO_MENU_ACTION = -1;
    private static final int OUTSIDE_LIST = -2;

    /**
     * The install's texts: the tabs, the groups, the tabs' buttons and the away status.
     */
    private static final @NotNull String FRIENDS_TAB_KEY = "bnet_participantsPanel_friends";
    private static final @NotNull String CHANNEL_TAB_KEY = "bnet_participantsPanel_channel";
    private static final @NotNull String ONLINE_KEY = "bnet_participantsPanel_friendsOnline";
    private static final @NotNull String OFFLINE_KEY = "bnet_participantsPanel_friendsOffline";
    private static final @NotNull String ADD_FRIEND_KEY = "bnet_participantsPanel_addFriend";
    private static final @NotNull String JOIN_CHANNEL_KEY = "bnet_participantsPanel_joinChannel";
    private static final @NotNull String AWAY_KEY = "bnet_participantsPanel_statusAway";
    private static final @NotNull String NETWORK_ERROR_KEY = "gameNETWORKERROR";
    /**
     * The install's labels of the context menu's actions.
     */
    private static final @NotNull String WHISPER_ACTION_KEY = "bnet119_chatPanel_contextMenu_whisper";
    private static final @NotNull String ADD_FRIEND_ACTION_KEY = "bnet118_addFriendPopup_title";
    private static final @NotNull String REMOVE_FRIEND_ACTION_KEY = "bnet119_chatPanel_contextMenu_removeFriend";

    private static final @NotNull String VISIBLE_FLAG = "WS_VISIBLE";
    private static final @NotNull String TAB_STOP_FLAG = "WS_TABSTOP";
    private static final @NotNull String HOTKEY_FLAG = "WS_HOTKEY";
    private static final @NotNull String SMALL_TEXT_FLAG = "RC_SMALLTEXT";
    private static final @NotNull String HORIZONTAL_CENTER_FLAG = "FB_HTEXTCENTER";
    private static final @NotNull String VERTICAL_CENTER_FLAG = "FB_VTEXTCENTER";

    /**
     * The colors of the chat page ({@code ChatPanel}): {@code .emote}, the whispers ({@code .whisperOutbound}), {@code .info} and
     * {@code .error}.
     */
    private static final int EMOTE_COLOR = 0xFF8040FF;
    private static final int WHISPER_COLOR = 0xF85CF8FF;
    private static final int INFO_COLOR = 0xF2D008FF;
    private static final int ERROR_COLOR = 0xFF0000FF;
    /**
     * The member names' colors besides the page's name color: this player ({@code .playerName}) and friends ({@code .friendFollow}).
     */
    private static final int SELF_COLOR = 0xFFFFFFFF;
    private static final int FRIEND_COLOR = INFO_COLOR;

    /**
     * A group of the participants list.
     */
    private enum Group {
        CHANNEL,
        ONLINE,
        OFFLINE
    }

    /**
     * An action of a player's context menu.
     */
    private enum MenuAction {
        WHISPER,
        ADD_FRIEND,
        REMOVE_FRIEND,
        BLOCK,
        UNBLOCK
    }

    /**
     * A row of the participants list: a group's label (no player, the group's count), or a player of the group above it with the player's
     * presence ({@code null} while the player is offline).
     */
    private static final class Row {
        private final @NotNull Group group;
        private final @Nullable LinkPlayer player;
        private final @Nullable PlayerPresence presence;
        private final int count;

        private Row(@NotNull Group group, @Nullable LinkPlayer player, @Nullable PlayerPresence presence, int count) {
            this.group    = group;
            this.player   = player;
            this.presence = presence;
            this.count    = count;
        }
    }

    /**
     * Returns the color of a log entry's prefix or text.
     *
     * @param kind   the entry's kind
     * @param prefix {@code true} for the prefix, {@code false} for the text
     * @return the packed {@code 0xRRGGBBAA} color
     */
    static int getEntryColor(@NotNull ChatEntryKind kind, boolean prefix) {
        return switch (kind) {
            case SAY -> prefix ? LobbyChatLog.NAME_COLOR : LobbyChatLog.MESSAGE_COLOR;
            case EMOTE -> EMOTE_COLOR;
            case WHISPER -> WHISPER_COLOR;
            case INFO -> INFO_COLOR;
            case ERROR -> ERROR_COLOR;
        };
    }

    private final @NotNull LobbyChatLog chatLog;
    private final @NotNull WebRowList users;
    private @Nullable ChannelChat chat;
    private @Nullable EditControl chatInput;
    private @Nullable ComboBox status;

    /**
     * Whether the participants page shows the friends ({@code false}: the channel's members), and which groups show their players.
     */
    private boolean friendsShown;
    private boolean channelExpanded;
    private boolean onlineExpanded;
    private boolean offlineExpanded;
    /**
     * Whether the network failed and its error box shows.
     */
    private boolean failed;

    /**
     * The open context menu: the player's row, its actions, its top left corner and the hovered action ({@code -1} for none); no row while
     * it is shut.
     */
    private @Nullable Row menuRow;
    private @NotNull List<MenuAction> menuActions;
    private float menuLeft;
    private float menuTop;
    private int menuHovered;

    /**
     * Creates a {@link ChannelChatScreen} instance.
     */
    public ChannelChatScreen() {
        super(DIALOG, PALETTE_FOLDER, BACKGROUND);

        this.chatLog   = LobbyChatLog.createChatPageLog(LobbyChatLog.Page.CHANNEL);
        this.users     = new WebRowList(ParticipantRowsUtils.ROW_HEIGHT);
        this.chat      = null;
        this.chatInput = null;
        this.status    = null;

        this.friendsShown    = false;
        this.channelExpanded = true;
        this.onlineExpanded  = true;
        this.offlineExpanded = true;
        this.failed          = false;

        this.menuRow     = null;
        this.menuActions = List.of();
        this.menuLeft    = 0;
        this.menuTop     = 0;
        this.menuHovered = NO_MENU_ACTION;
    }


    /**
     * Loads the screen's dialog: {@code rez\<dialog name>.ui.json}, or an empty dialog without a name. A screen whose
     * native content is not a control list builds its dialog here.
     * <p>
     * Keeps the dialog's controls and adds the chat input, the scroll bars of the chat log and the participants list, the status drop-down
     * and the tab's button.
     *
     * @param glueContext the shared services
     * @return the dialog
     * @throws IOException if the dialog cannot be read or has no chat or user page
     */
    @Override
    protected @NotNull DialogDefinition loadDialog(@NotNull GlueContext glueContext) throws IOException {
        DialogDefinition file = super.loadDialog(glueContext);
        DialogControl chatPage = ScrDialogControlUtils.findControl(file, CHAT_PAGE);
        DialogControl userPage = ScrDialogControlUtils.findControl(file, USER_PAGE);

        if (chatPage == null || userPage == null) {
            throw Exceptions.newIOException("{0} has no chat or user page.", DIALOG);
        }

        List<DialogControl> controls = new ArrayList<>(file.getControls());

        controls.add(LobbyChatLog.createFieldInputDefinition(LobbyChatLog.Page.CHANNEL, chatPage, CHAT_INPUT));
        controls.add(WebScrollBar.createDefinition(CHAT_SCROLL, chatPage));
        controls.add(WebScrollBar.createDefinition(USER_SCROLL, userPage));
        controls.add(ScrDialogControlUtils.createControl(ScrControlType.COMBOBOX,
                                                         STATUS_CHOICE,
                                                         userPage.getX() + HEADER_LEFT, userPage.getY() + STATUS_TOP,
                                                         STATUS_WIDTH, STATUS_HEIGHT,
                                                         Set.of(VISIBLE_FLAG, TAB_STOP_FLAG, SMALL_TEXT_FLAG),
                                                         "",
                                                         List.of()));
        controls.add(ScrDialogControlUtils.createControl(ScrControlType.FLCBTN,
                                                         TAB_BUTTON,
                                                         userPage.getX() + PAGE_INSET,
                                                         userPage.getY() + userPage.getHeight() - PAGE_INSET / SIDE_COUNT - BUTTON_HEIGHT,
                                                         userPage.getWidth() - SIDE_COUNT * PAGE_INSET, BUTTON_HEIGHT,
                                                         Set.of(VISIBLE_FLAG, TAB_STOP_FLAG, HOTKEY_FLAG, HORIZONTAL_CENTER_FLAG, VERTICAL_CENTER_FLAG,
                                                                FRAMED_FLAG),
                                                         "",
                                                         List.of()));

        return new DialogDefinition(file.getName(), file.getX(), file.getY(), file.getWidth(), file.getHeight(), controls);
    }

    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * Hides the controls the chat does not use, names the Whisper, Ladder and Records buttons (the last two disabled), prepares the input, the
     * scroll bars, the status drop-down and the tab's button, selects Beacon.Link for the multiplayer screens and opens its network unless one
     * is open. A lobby or game left before is left on the network too, and the player shows available again; the chat itself opens in
     * {@link #onUpdate} once the network is connected.
     *
     * @param glueContext the shared services
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) {
        String locale = glueContext.getLocale();
        GluText text = glueContext.getGluText();

        for (int control : HIDDEN) {
            this.bindControl(control).setVisible(false);
        }

        this.bindControl(WHISPER).setReplacedText(OwnText.WHISPER_BUTTON.get(locale));
        this.bindControl(LADDER).setReplacedText(OwnText.LADDER_BUTTON.get(locale));
        this.bindControl(RECORDS).setReplacedText(OwnText.RECORDS_BUTTON.get(locale));
        this.bindControl(LADDER).setEnabled(false);
        this.bindControl(RECORDS).setEnabled(false);
        this.setSwish(SWISH, SWISH_DELAY_MILLISECONDS);

        this.chatInput = this.setEditText(CHAT_INPUT, "", ChatChannel.MAXIMUM_TEXT_LENGTH, "");
        this.users.setScrollBar(this.requireDialogState().attach(new WebScrollBar(this.bindControl(USER_SCROLL).getDefinition())));
        this.chatLog.setScrollBar(this.requireDialogState().attach(new WebScrollBar(this.bindControl(CHAT_SCROLL).getDefinition())));

        ComboBox choice = this.addComboBox(STATUS_CHOICE);

        choice.addItem(text.get(ONLINE_KEY), AVAILABLE_VALUE);
        choice.addItem(text.get(AWAY_KEY), AWAY_VALUE);
        this.status = choice;
        this.showTab(this.friendsShown);
        glueContext.setBeaconLinkSelected(true);

        if (glueContext.getLanClient() == null) {
            try {
                MapFolders mapFolders = glueContext.getFolders().getLanMapFolders();

                glueContext.setLanClient(LanClient.open(glueContext.getPlayerName(), glueContext.getBeaconLink().open(), mapFolders));
            } catch (IOException | IllegalArgumentException exception) {
                LOGGER.warning("BEACON.LINK UNAVAILABLE: " + exception.getMessage());
                this.fail(text.get(NETWORK_ERROR_KEY));

                return;
            }
        }

        LanClient client = glueContext.getLanClient();

        // Back from a game list join, a lobby or a game: the game's session is left (subtype 11) and its network thread stops.
        if (client != null && client.getState() != LanClient.State.BROWSING) {
            client.leave();
        }

        ChannelChat opened = glueContext.getChannelChat();

        if (opened != null) {
            this.enter(opened);
        }
    }

    /**
     * @return the Beacon.Link session of the multiplayer screens' network, or {@code null} without one
     */
    private @Nullable BeaconLinkSession getSession() {
        LanClient client = this.getContext().getLanClient();

        return client == null ? null : client.getNetwork().getBeaconLinkSession();
    }

    /**
     * Shows the network's error box once; closing it returns to the connection choice.
     */
    private void fail(@NotNull String message) {
        GlueContext glueContext = this.getContext();

        if (this.failed) {
            return;
        }

        this.failed = true;
        this.showError(message, () -> {
            glueContext.setLanClient(null);
            this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.CONNECTION));
        });
    }

    /**
     * Enters a chat: the player shows available again and the screen's log takes the chat's entries.
     */
    private void enter(@NotNull ChannelChat opened) {
        this.chat = opened;
        opened.showAvailable();
        this.chatLog.listen(opened);
    }

    /**
     * Called by {@link #update} for screen-specific timers.
     * <p>
     * Polls the network, which hands the channel's lines, members and whispers to the chat. Before the chat opens, it waits for a node to
     * register the session (after the first frame: the first call finds the account's player stamp, a proof of work once per account) and
     * shows the network's failure; once it is open, the status drop-down follows this player's status.
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

        BeaconLinkSession session = client.getNetwork().getBeaconLinkSession();

        if (session == null) {
            this.fail(this.getContext().getGluText().get(NETWORK_ERROR_KEY));

            return;
        }

        ChannelChat current = this.chat;

        if (current == null) {
            this.openChat(session);

            return;
        }

        ComboBox choice = this.status;

        if (choice != null && !choice.isOpen()) {
            choice.selectValue(current.isAway() ? AWAY_VALUE : AVAILABLE_VALUE);
        }
    }

    private void openChat(@NotNull BeaconLinkSession session) {
        GlueContext glueContext = this.getContext();
        LinkFailure failure = session.getFailure();

        if (failure != null) {
            this.fail(getFailureText(failure).get(glueContext.getLocale()));

            return;
        }

        if (this.getElapsed() <= 0 || !isRegistered(session)) {
            return;
        }

        ChannelChat opened = new ChannelChat(session.getCommunity(), glueContext.getTextLocale(), CHANNEL_BASE);

        glueContext.setChannelChat(opened);
        opened.open();
        this.enter(opened);
    }


    private static boolean isRegistered(@NotNull BeaconLinkSession session) {
        for (LinkNode node : session.getNodes()) {
            if (node.getState() == LinkNodeState.REGISTERED) {
                return true;
            }
        }

        return false;
    }

    private static @NotNull OwnText getFailureText(@NotNull LinkFailure failure) {
        return switch (failure) {
            case NO_NODES_CONFIGURED -> OwnText.NO_NODES;
            case NO_NODE_ANSWERED -> OwnText.NO_ANSWER;
            case ALL_NODES_REFUSED, ROOM_REFUSED -> OwnText.REFUSED;
            case TOO_MANY_CONNECTIONS -> OwnText.TOO_MANY_DEVICES;
            case SIGNED_OUT -> OwnText.SIGNED_OUT;
        };
    }

    /**
     * Called after the user changed the selection of a list or combo box, or the value of a slider.
     * <p>
     * Shows this player away or available as the status drop-down selects ({@link ChannelChat#setAway}).
     *
     * @param control the control id
     */
    @Override
    protected void onSelectionChanged(int control) {
        ChannelChat current = this.chat;
        ComboBox choice = this.status;

        if (control == STATUS_CHOICE && current != null && choice != null) {
            current.setAway(choice.getSelectedValue(AVAILABLE_VALUE) == AWAY_VALUE);
        }
    }

    /**
     * Shows the friends or the channel's members, and names the tab's button.
     *
     * @param friends {@code true} for the friends
     */
    private void showTab(boolean friends) {
        GluText text = this.getContext().getGluText();

        this.friendsShown = friends;
        this.menuRow      = null;
        this.users.setSelection(WebRowList.NO_SELECTION);
        this.bindControl(TAB_BUTTON).setReplacedText(text.get(friends ? ADD_FRIEND_KEY : JOIN_CHANNEL_KEY));
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     * <p>
     * Create opens the game creation, Join the room list; Whisper starts a whisper to the selected player in the input; the channel tab's
     * button opens the channel list, the friends tab's button the add-friend popup; Leave closes the network and returns to the connection choice.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
        GlueContext glueContext = this.getContext();
        ChannelChat current = this.chat;

        switch (control) {
            case CREATE -> this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.CREATE_GAME));
            case JOIN -> this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.GAME_LIST));
            case WHISPER -> this.whisperSelected();
            case TAB_BUTTON -> {
                if (current != null) {
                    this.showPopup(this.friendsShown ? AddFriendPopup.create(glueContext, PALETTE_FOLDER, current)
                                                     : ChannelListPopup.create(glueContext, PALETTE_FOLDER, current));
                }
            }
            case LEAVE -> {
                glueContext.setLanClient(null);
                this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.CONNECTION));
            }
            default -> {
            }
        }
    }

    /**
     * Makes the selected player the input's whisper target: an online player other than this one.
     */
    private void whisperSelected() {
        Row selected = this.getSelectedRow();

        if (selected == null || selected.presence == null) {
            return;
        }

        this.whisper(selected.presence);
    }

    /**
     * Makes a player the input's whisper target and gives the input the focus; this player is never the target.
     */
    private void whisper(@NotNull PlayerPresence player) {
        ChannelChat current = this.chat;
        EditControl input = this.chatInput;

        if (current == null || input == null || current.isSelf(player)) {
            return;
        }

        current.setWhisperTarget(player.getPlayer());
        this.setFocusedEdit(input);
    }

    /**
     * Called first for every key of the screen (after an open popup): a screen without controls, such as a cinematic, takes its keys here.
     * <p>
     * Takes Enter, which says or whispers the typed line or runs the command, Backspace in the empty input after a whisper, which says lines
     * in the channel again, and Esc while a context menu is open, which shuts it; other keys reach the input.
     *
     * @param key the key
     * @return {@code true} if the key was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onKey(char key) {
        EditControl input = this.chatInput;

        if (this.menuRow != null && key == KEY_ESCAPE) {
            this.menuRow = null;

            return true;
        }

        String text = LobbyChatLog.takeInputKey(this.chat, input, true, key);

        if (text != null) {
            this.chatInput = this.setEditText(CHAT_INPUT, text, ChatChannel.MAXIMUM_TEXT_LENGTH, "");
        }

        return text != null || this.chat != null && key == KEY_ENTER;
    }

    /**
     * Called after the text of an {@code EDITTEXT} changed.
     * <p>
     * A whisper start typed in the input becomes the input's whisper target.
     *
     * @param control the control id
     */
    @Override
    protected void onEditChanged(int control) {
        String text = control == CHAT_INPUT ? LobbyChatLog.takeWhisperStart(this.chat, this.chatInput) : null;

        if (text != null) {
            this.chatInput = this.setEditText(CHAT_INPUT, text, ChatChannel.MAXIMUM_TEXT_LENGTH, "");
        }
    }

    /**
     * Returns the rows of the shown tab: the channel's label and its members, or the Online group's label and its friends, then the Offline
     * group's; a folded group shows its label only.
     *
     * @return the rows
     */
    private @NotNull List<Row> getRows() {
        ChannelChat current = this.chat;
        List<Row> rows = new ArrayList<>();

        if (current == null) {
            return rows;
        }

        if (!this.friendsShown) {
            List<PlayerPresence> members = current.getMembers();

            rows.add(new Row(Group.CHANNEL, null, null, members.size()));

            if (this.channelExpanded) {
                for (PlayerPresence member : members) {
                    rows.add(new Row(Group.CHANNEL, member.getPlayer(), member, 0));
                }
            }

            return rows;
        }

        List<Row> online = new ArrayList<>();
        List<Row> offline = new ArrayList<>();

        for (LinkPlayer friend : current.getFriends()) {
            PlayerPresence presence = current.findPresence(friend);

            if (presence == null) {
                offline.add(new Row(Group.OFFLINE, friend, null, 0));
            } else {
                online.add(new Row(Group.ONLINE, friend, presence, 0));
            }
        }

        rows.add(new Row(Group.ONLINE, null, null, online.size()));

        if (this.onlineExpanded) {
            rows.addAll(online);
        }

        rows.add(new Row(Group.OFFLINE, null, null, offline.size()));

        if (this.offlineExpanded) {
            rows.addAll(offline);
        }

        return rows;
    }

    /**
     * @return the selected row, or {@code null} if none is selected or the selected row went away
     */
    private @Nullable Row getSelectedRow() {
        List<Row> rows = this.getRows();
        int selected = this.users.getSelection();

        return selected >= 0 && selected < rows.size() ? rows.get(selected) : null;
    }

    /**
     * Called first for every press of the screen that no open popup or open combo box takes, while the screen takes input.
     * <p>
     * An open context menu takes the press: it runs the action under it and shuts. Otherwise takes presses on the scroll bars, the chat text (a
     * selection) and the tabs, folds a group open or shut at its label and selects the player under a press on the list; a double press starts a
     * whisper to the player.
     *
     * @param x the interface x
     * @param y the interface y
     * @return {@code true} if the press was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onPointerPressed(float x, float y) {
        if (this.menuRow != null) {
            int action = ParticipantRowsUtils.findMenuItem(x, y, this.menuLeft, this.menuTop, this.menuActions.size());
            Row row = this.menuRow;

            this.menuRow = null;

            if (action != NO_MENU_ACTION) {
                this.runMenuAction(row, this.menuActions.get(action));
            }

            return true;
        }

        if (this.chatLog.press(this.getContext().getCanvas(), x, y, this.isExtendingPress(), this.isDoublePress()) || this.users.press(x, y)) {
            return true;
        }

        DialogControl page = ScrDialogControlUtils.findControl(this.getDialog(), USER_PAGE);

        if (page == null) {
            return false;
        }

        float pageLeft = this.getDialogLeft() + page.getX();
        float pageTop = this.getDialogTop() + page.getY();
        float tabWidth = getTabWidth(page) / SIDE_COUNT;

        if (GlueCanvas.contains(x, y, pageLeft + PAGE_INSET, pageTop + TAB_TOP, SIDE_COUNT * tabWidth, TAB_HEIGHT)) {
            this.showTab(x < pageLeft + PAGE_INSET + tabWidth);

            return true;
        }

        List<Row> rows = this.getRows();
        int row = this.findListRow(x, y, rows);

        if (row == OUTSIDE_LIST) {
            return false;
        }

        if (row >= 0 && rows.get(row).player == null) {
            switch (rows.get(row).group) {
                case CHANNEL -> this.channelExpanded = !this.channelExpanded;
                case ONLINE -> this.onlineExpanded = !this.onlineExpanded;
                case OFFLINE -> this.offlineExpanded = !this.offlineExpanded;
            }

            this.users.setSelection(WebRowList.NO_SELECTION);

            return true;
        }

        this.users.setSelection(row);

        if (this.isDoublePress()) {
            this.whisperSelected();
        }

        return true;
    }

    /**
     * Finds the row of the participants list under a point.
     *
     * @return the row's index, {@link WebRowList#NO_SELECTION} under the list's last row, or {@link #OUTSIDE_LIST} outside the list
     */
    private int findListRow(float x, float y, @NotNull List<Row> rows) {
        DialogControl page = ScrDialogControlUtils.findControl(this.getDialog(), USER_PAGE);

        if (page == null) {
            return OUTSIDE_LIST;
        }

        float listLeft = this.getDialogLeft() + page.getX() + PAGE_INSET;
        float listTop = this.getDialogTop() + page.getY() + LIST_TOP;
        float listWidth = page.getWidth() - SIDE_COUNT * PAGE_INSET;
        float listHeight = getListHeight(page);

        if (!GlueCanvas.contains(x, y, listLeft, listTop, listWidth, listHeight)) {
            return OUTSIDE_LIST;
        }

        return this.users.findRow(x, y, listLeft, listTop, listWidth, listHeight, rows.size());
    }

    /**
     * Called for a press of the right button of the screen that no open popup or open combo box takes (the dialog's controls take
     * none): a page's context menu opens here.
     * <p>
     * Selects the player under the press on the participants list and opens the player's context menu at the press: Whisper for an online
     * player, then Add Friend, or Remove Friend for a friend, then Block, or Unblock for a blocked player; none for this player.
     *
     * @param x the interface x
     * @param y the interface y
     */
    @Override
    protected void onContextPressed(float x, float y) {
        ChannelChat current = this.chat;
        List<Row> rows = this.getRows();
        int index = this.findListRow(x, y, rows);

        this.menuRow = null;

        if (current == null || index < 0) {
            return;
        }

        Row row = rows.get(index);
        LinkPlayer player = row.player;
        PlayerPresence presence = row.presence;

        if (player == null) {
            return;
        }

        this.users.setSelection(index);

        if (presence != null && current.isSelf(presence)) {
            return;
        }

        List<MenuAction> actions = new ArrayList<>();

        if (presence != null) {
            actions.add(MenuAction.WHISPER);
        }

        if (current.getFriends().contains(player)) {
            actions.add(MenuAction.REMOVE_FRIEND);
        } else if (presence != null) {
            actions.add(MenuAction.ADD_FRIEND);
        }

        actions.add(current.isBlocked(player) ? MenuAction.UNBLOCK : MenuAction.BLOCK);

        GlueCanvas canvas = this.getContext().getCanvas();

        this.menuRow     = row;
        this.menuActions = List.copyOf(actions);
        this.menuLeft    = Math.min(x, canvas.getInterfaceWidth() - ParticipantRowsUtils.getMenuWidth());
        this.menuTop     = Math.min(y, GlueCanvas.INTERFACE_HEIGHT - ParticipantRowsUtils.getMenuHeight(actions.size()));
        this.menuHovered = ParticipantRowsUtils.findMenuItem(x, y, this.menuLeft, this.menuTop, actions.size());
    }

    /**
     * Runs an action of a player's context menu: makes the player the input's whisper target, adds or removes the friend, or blocks or
     * unblocks the player.
     */
    private void runMenuAction(@NotNull Row row, @NotNull MenuAction action) {
        ChannelChat current = this.chat;
        LinkPlayer player = row.player;
        PlayerPresence presence = row.presence;

        if (current == null || player == null) {
            return;
        }

        switch (action) {
            case WHISPER -> {
                if (presence != null) {
                    this.whisper(presence);
                }
            }
            case ADD_FRIEND -> current.addFriend(current.getTaggedName(player));
            case REMOVE_FRIEND -> {
                current.removeFriend(player);
                this.users.setSelection(WebRowList.NO_SELECTION);
            }
            case BLOCK -> current.block(player);
            case UNBLOCK -> current.unblock(player);
        }
    }

    /**
     * Called for every pointer move of the screen (after an open combo box or popup takes it).
     * <p>
     * Drags the scroll bars or the chat selection and follows the hovered action of an open context menu.
     *
     * @param x the interface x
     * @param y the interface y
     */
    @Override
    protected void onPointerMoved(float x, float y) {
        if (this.menuRow != null) {
            this.menuHovered = ParticipantRowsUtils.findMenuItem(x, y, this.menuLeft, this.menuTop, this.menuActions.size());
        }

        this.chatLog.drag(this.getContext().getCanvas(), x, y);
        this.users.drag(y);
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
        this.users.release();
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
     * Scrolls the chat log or the participants list under the pointer.
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
        DialogControl userPage = ScrDialogControlUtils.findControl(this.getDialog(), USER_PAGE);

        if (chatPage != null && GlueCanvas.contains(dialogX, dialogY,
                                                    chatPage.getX(), chatPage.getY(),
                                                    chatPage.getWidth(), chatPage.getHeight())) {
            this.chatLog.scrollByWheel(down);

            return true;
        }

        if (userPage != null && GlueCanvas.contains(dialogX, dialogY,
                                                    userPage.getX(), userPage.getY(),
                                                    userPage.getWidth(), userPage.getHeight())) {
            this.users.scrollByWheel(down);

            return true;
        }

        return false;
    }

    /**
     * Draws one control.
     * <p>
     * Draws the chat log on the chat page and the participants on the user page; the other controls draw as on every screen.
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
            case CHAT_PAGE -> this.chatLog.draw(canvas, left, top, control.getWidth(), control.getHeight());
            case USER_PAGE -> this.drawUsers(canvas, control, left, top);
            default -> super.drawControl(canvas, control, left, top);
        }
    }

    /**
     * Draws the controls in child order.
     * <p>
     * Draws an open context menu over them.
     *
     * @param canvas the glue canvas
     * @throws IOException           if an image cannot be read
     * @throws IllegalStateException if the screen is not open
     */
    @Override
    protected void drawControls(@NotNull GlueCanvas canvas) throws IOException {
        super.drawControls(canvas);

        Row row = this.menuRow;
        ChannelChat current = this.chat;

        if (row == null || row.player == null || current == null) {
            return;
        }

        GluText text = this.getContext().getGluText();
        Locale locale = this.getContext().getTextLocale();
        String[] labels = new String[this.menuActions.size()];

        for (int index = 0; index < labels.length; index++) {
            labels[index] = switch (this.menuActions.get(index)) {
                case WHISPER -> text.get(WHISPER_ACTION_KEY);
                case ADD_FRIEND -> text.get(ADD_FRIEND_ACTION_KEY);
                case REMOVE_FRIEND -> text.get(REMOVE_FRIEND_ACTION_KEY);
                case BLOCK -> CommunityText.BLOCK.get(locale);
                case UNBLOCK -> CommunityText.UNBLOCK.get(locale);
            };
        }

        ParticipantRowsUtils.drawMenu(canvas, current.getTaggedName(row.player), labels, this.menuHovered, this.menuLeft, this.menuTop);
    }

    /**
     * @return the width of both tabs: the page less its insets and the tabs' right padding
     */
    private static float getTabWidth(@NotNull DialogControl page) {
        return page.getWidth() - SIDE_COUNT * PAGE_INSET - TAB_RIGHT_PADDING;
    }

    /**
     * @return the height of the participants list: from under the tabs to above the tab's button
     */
    private static float getListHeight(@NotNull DialogControl page) {
        return page.getHeight() - PAGE_INSET / SIDE_COUNT - BUTTON_HEIGHT - LIST_GAP - LIST_TOP;
    }

    /**
     * Draws the participants page: this player's portrait and names (the status drop-down draws as a control), the tabs with their counts and
     * the rows of the shown tab; while the chat is not open yet, the network's state in the list's place.
     *
     * @throws IOException if an image cannot be read
     */
    private void drawUsers(@NotNull GlueCanvas canvas, @NotNull DialogControl page, float left, float top) throws IOException {
        GlueContext glueContext = this.getContext();
        GluText text = glueContext.getGluText();
        ChannelChat current = this.chat;
        BeaconLinkSession session = this.getSession();
        float listLeft = left + PAGE_INSET;
        float listWidth = page.getWidth() - SIDE_COUNT * PAGE_INSET;

        canvas.drawImage(canvas.getWebImage(PORTRAIT), left + PORTRAIT_LEFT, top + PORTRAIT_TOP, PORTRAIT_SIZE, PORTRAIT_SIZE);

        if (current == null || session == null) {
            WebPanelStyleUtils.drawCenteredLine(canvas,
                                                TAB_TEXT,
                                                OwnText.CONNECTING.get(glueContext.getLocale()),
                                                listLeft, top + LIST_TOP,
                                                listWidth, getListHeight(page),
                                                GlueCanvas.FORMAT_CENTER,
                                                WebPanelStyleUtils.TEXT);

            return;
        }

        PlayerPresence self = session.getCommunity().getSelf();
        int onlineFriends = 0;

        for (LinkPlayer friend : current.getFriends()) {
            onlineFriends += current.findPresence(friend) == null ? 0 : 1;
        }

        ParticipantRowsUtils.drawNames(canvas,
                                       self.getName(), self.getTaggedName(),
                                       ParticipantRowsUtils.TEXT_COLOR,
                                       left + HEADER_LEFT, top + NAME_TOP,
                                       left + page.getWidth() - PAGE_INSET);
        this.drawTab(canvas, String.format(Locale.ROOT, TAB_FORMAT, text.get(FRIENDS_TAB_KEY), onlineFriends), this.friendsShown,
                     listLeft, top + TAB_TOP, getTabWidth(page) / SIDE_COUNT);
        this.drawTab(canvas, String.format(Locale.ROOT, TAB_FORMAT, text.get(CHANNEL_TAB_KEY), current.getMembers().size()), !this.friendsShown,
                     listLeft + getTabWidth(page) / SIDE_COUNT, top + TAB_TOP, getTabWidth(page) / SIDE_COUNT);

        List<Row> rows = this.getRows();

        this.users.draw(canvas,
                        listLeft, top + LIST_TOP,
                        listWidth, getListHeight(page),
                        rows.size(),
                        (rowCanvas, row, selected, rowLeft, rowTop, rowWidth) -> this.drawRow(rowCanvas, rows.get(row), selected,
                                                                                              rowLeft, rowTop, rowWidth));
    }

    private void drawTab(@NotNull GlueCanvas canvas, @NotNull String label, boolean active, float left, float top, float width) {
        int color = active ? WebPanelStyleUtils.HIGHLIGHT_TEXT : WebPanelStyleUtils.BUTTON_TEXT;

        WebPanelStyleUtils.drawCenteredLine(canvas, TAB_TEXT, label, left, top, width, TAB_HEIGHT - 1, GlueCanvas.FORMAT_CENTER, color);
        canvas.fillRectangle(left, top + TAB_HEIGHT - 1, width, 1, active ? WebPanelStyleUtils.HIGHLIGHT_TEXT : TAB_LINE_COLOR);
    }

    /**
     * Draws a row: a group's label, a channel member (one line: the name, the mod and the latency bars; this player white, friends yellow)
     * or a friend (the name and the tagged name, the status line with the mod, the latency bars).
     *
     * @throws IOException if an image cannot be read
     */
    private void drawRow(@NotNull GlueCanvas canvas, @NotNull Row row, boolean selected, float left, float top, float width) throws IOException {
        ChannelChat current = this.chat;
        LinkPlayer player = row.player;

        if (current == null) {
            return;
        }

        if (player == null) {
            GluText text = this.getContext().getGluText();
            ChatChannel channel = current.getChannel();
            String name = switch (row.group) {
                case CHANNEL -> channel == null ? CommunityText.NO_CHANNEL.get(this.getContext().getTextLocale()) : channel.getName();
                case ONLINE -> text.get(ONLINE_KEY);
                case OFFLINE -> text.get(OFFLINE_KEY);
            };
            boolean expanded = switch (row.group) {
                case CHANNEL -> this.channelExpanded;
                case ONLINE -> this.onlineExpanded;
                case OFFLINE -> this.offlineExpanded;
            };

            ParticipantRowsUtils.drawGroupLabel(canvas, expanded, name, row.count, left, top, width);

            return;
        }

        PlayerPresence presence = row.presence;
        int bars = presence == null || current.isSelf(presence) ? 0 : LatencyBarsUtils.getBarCount(presence.getLatencyEstimate().getLatencyClass());
        String mod = presence == null ? "" : ParticipantRowsUtils.getModText(presence);
        boolean playable = presence == null || presence.canPlayTogether();

        if (row.group == Group.CHANNEL && presence != null) {
            int nameColor = current.isSelf(presence) ? SELF_COLOR : current.isFriend(presence) ? FRIEND_COLOR : ParticipantRowsUtils.TEXT_COLOR;

            ParticipantRowsUtils.drawMember(canvas, left, top, width, selected, true, presence.getTaggedName(), "", nameColor, null, "", mod, playable,
                                            bars);

            return;
        }

        String taggedName = current.getTaggedName(player);
        String name = presence == null ? taggedName : presence.getName();

        ParticipantRowsUtils.drawMember(canvas,
                                        left, top, width,
                                        selected,
                                        presence != null,
                                        name, presence == null ? "" : taggedName, ParticipantRowsUtils.TEXT_COLOR,
                                        ParticipantRowsUtils.getStatusIcon(presence), current.getStatusText(presence),
                                        mod, playable,
                                        bars);
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
     * Shows the chat's channel or whisper target before the input's text.
     *
     * @param control an {@code EDITTEXT} control with {@link #FRAMED_FLAG}
     * @return the text the field shows before its text, or an empty string for none
     */
    @Override
    protected @NotNull String getEditPrefix(@NotNull DialogControl control) {
        return control.getId() == CHAT_INPUT ? LobbyChatLog.getInputPrefix(this.chat) : super.getEditPrefix(control);
    }

    /**
     * Selects the chat's prefix color for the input's prefix.
     *
     * @param control an {@code EDITTEXT} control with {@link #FRAMED_FLAG}
     * @return the packed color of the field's prefix
     */
    @Override
    protected int getEditPrefixColor(@NotNull DialogControl control) {
        return control.getId() == CHAT_INPUT ? LobbyChatLog.getInputPrefixColor(this.chat) : super.getEditPrefixColor(control);
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
