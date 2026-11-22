package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.lobby.RecentMaps;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatChannel;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.scr.client.glue.*;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.GlueEditPopup;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.*;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogImportUtils;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.save.SaveGameKind;
import org.fogofwarlabs.sc4j.scr.game.save.ScrSaveFormat;
import org.fogofwarlabs.sc4j.scr.game.session.setup.GameTemplate;
import org.fogofwarlabs.sc4j.scr.net.lan.LanClient;
import org.fogofwarlabs.sc4j.scr.net.lan.LanGameOptions;
import org.fogofwarlabs.sc4j.scr.net.lan.LanSavedGame;
import org.fogofwarlabs.sc4j.scr.net.proxy.GameProxySocket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;

/**
 * Lists maps and multiplayer saves with game-type, subtype and speed choices, then hosts the selected game.
 * <p>
 * On the local network the screen is the install's create dialog ({@code gluCreat}): the map list with the type, subtype and speed under it,
 * and under the speed the turn rate, a copy of the speed's row (label with the value, {@code -}, slider, {@code +}); the four rows share the
 * band of the dialog's three.
 * Over Beacon.Link it is the install's Battle.net create dialog ({@code gluBattlenetCreateGameScreen}), drawn as its pages draw them: the
 * create game page (title, then a label column and a control column: the game name, public or private as radio buttons, the password of a
 * private game, the map drop-down of the recently hosted maps with the folder button, the game type, the subtype when the type has one, the
 * speed slider and the turn rate slider with their values under them, the custom hotkeys and the unit limits as radio buttons), the
 * selected map's information and the channel chat under them. The folder button turns the create page into the map browser (title, the open
 * folder, the file list in its frame and a search field), whose Ok takes the selected map back to the create page and whose Cancel returns
 * without it. The game name (by default the player's game) is the room name other players list it by; a private game's room asks joiners
 * for its password.
 */
public final class CreateGameScreen extends FileBrowserScreen {
    /**
     * The created game's flag for disabled custom hotkeys.
     */
    public static final int HOTKEYS_DISABLED_FLAG = 1;
    /**
     * The created game's flag for original unit limits.
     */
    public static final int ORIGINAL_LIMITS_FLAG = LanClient.ORIGINAL_LIMITS_FLAG;

    /**
     * The message key for a template unsupported by the selected map.
     */
    static final @NotNull String BAD_GAME_TYPE_KEY = "gameBADGAMETYPE";
    /**
     * The height of a drop-down's control: the local network's create game drop-downs ({@code gluCreat} {@code 17}, {@code 18}: {@code 172}
     * x {@code 20}, small text), which the room filter's and the status drop-down take; the create page's drop-downs are as high as their bar.
     */
    static final int CHOICE_HEIGHT = 20;
    /**
     * The frame line of the Beacon.Link pages' panel art ({@code glue\create\pListMap4}, behind the create page and the room list) under the
     * page's top; a page's content ends above it.
     */
    static final int PANEL_FRAME_BOTTOM = 249;

    private static final @NotNull Logger LOGGER = Logger.of(CreateGameScreen.class);

    private static final int SUBTYPE_TITLE = 14;
    private static final int SPEED_LABEL = 15;
    private static final int SPEED = 16;
    private static final int GAME_TYPE = 17;
    private static final int SUBTYPE = 18;
    private static final int SLOT_PANEL = 19;
    private static final int GAME_TYPE_TITLE = 38;
    private static final int SPEED_DOWN_TEXT = 39;
    private static final int SPEED_UP_TEXT = 40;
    private static final int CHAT_PANEL = 42;
    /**
     * The start slots the subtype box assumes at least, as for a map without a selection.
     */
    private static final int MINIMUM_START_SLOTS = 2;
    /**
     * The subtype id stored and matched while the subtype box has no selection.
     */
    private static final int DEFAULT_SUBTYPE_ID = 1;

    /**
     * The Beacon.Link dialog and its pages: the create game page and the map browser in the same place, and the chat page.
     */
    private static final @NotNull String BEACON_LINK_DIALOG = "gluBattlenetCreateGameScreen";
    private static final int FORM_PAGE = 22;
    private static final int BROWSER_PAGE = 23;
    private static final int CHAT_PAGE = 25;
    /**
     * The synthesized controls of the Beacon.Link pages, ids the dialog does not use.
     */
    private static final int GAME_NAME = 90;
    private static final int MAP_CHOICE = 91;
    private static final int FOLDER_BUTTON = 92;
    private static final int MAP_SEARCH = 93;
    private static final int HOTKEYS_ENABLED = 94;
    private static final int HOTKEYS_DISABLED = 95;
    private static final int LIMITS_EXTENDED = 96;
    private static final int LIMITS_ORIGINAL = 97;
    private static final int CHAT_INPUT = 98;
    private static final int CHAT_SCROLL = 99;
    private static final int PRIVACY_PUBLIC = 100;
    private static final int PRIVACY_PRIVATE = 101;
    private static final int PASSWORD = 102;
    private static final int TURN_RATE = 103;
    /**
     * The texts of the Beacon.Link pages: the create page's title, the labels of its rows (in row order) and the slider rows' ends and values,
     * and the map browser's title.
     */
    private static final int FORM_TITLE = 110;
    private static final int NAME_LABEL = 111;
    private static final int PRIVACY_LABEL = 112;
    private static final int PASSWORD_LABEL = 113;
    private static final int MAP_LABEL = 114;
    private static final int TYPE_LABEL = 115;
    private static final int SUBTYPE_LABEL = 116;
    private static final int SPEED_ROW_LABEL = 117;
    private static final int TURN_RATE_ROW_LABEL = 118;
    private static final int HOTKEYS_LABEL = 119;
    private static final int LIMITS_LABEL = 120;
    private static final int SPEED_MINUS_TEXT = 121;
    private static final int SPEED_PLUS_TEXT = 122;
    private static final int SPEED_VALUE = 123;
    private static final int TURN_RATE_MINUS_TEXT = 124;
    private static final int TURN_RATE_PLUS_TEXT = 125;
    private static final int TURN_RATE_VALUE = 126;
    private static final int BROWSER_TITLE = 127;
    private static final int @NotNull [] FORM_CONTROLS = {GAME_NAME, PRIVACY_PUBLIC, PRIVACY_PRIVATE, PASSWORD, MAP_CHOICE, FOLDER_BUTTON,
                                                          GAME_TYPE, SUBTYPE, SPEED, TURN_RATE,
                                                          HOTKEYS_ENABLED, HOTKEYS_DISABLED, LIMITS_EXTENDED, LIMITS_ORIGINAL,
                                                          FORM_TITLE, NAME_LABEL, PRIVACY_LABEL, PASSWORD_LABEL, MAP_LABEL, TYPE_LABEL, SUBTYPE_LABEL,
                                                          SPEED_ROW_LABEL, TURN_RATE_ROW_LABEL, HOTKEYS_LABEL, LIMITS_LABEL,
                                                          SPEED_MINUS_TEXT, SPEED_PLUS_TEXT, SPEED_VALUE, TURN_RATE_MINUS_TEXT, TURN_RATE_PLUS_TEXT,
                                                          TURN_RATE_VALUE};
    private static final int @NotNull [] BROWSER_CONTROLS = {LIST, FOLDER, MAP_SEARCH, BROWSER_TITLE};
    /**
     * The create page's rows under the subtype's, which move up into the subtype's row while the type has no subtypes.
     */
    private static final int @NotNull [] ROWS_UNDER_SUBTYPE = {SPEED, SPEED_ROW_LABEL, SPEED_MINUS_TEXT, SPEED_PLUS_TEXT, SPEED_VALUE,
                                                               TURN_RATE, TURN_RATE_ROW_LABEL, TURN_RATE_MINUS_TEXT, TURN_RATE_PLUS_TEXT, TURN_RATE_VALUE,
                                                               HOTKEYS_LABEL, HOTKEYS_ENABLED, HOTKEYS_DISABLED,
                                                               LIMITS_LABEL, LIMITS_EXTENDED, LIMITS_ORIGINAL};
    /**
     * The local network's turn rate row, ids the dialog does not use: the copies of the speed's row ({@link #LAN_SPEED_ROW}, same order); its
     * slider has the id of the create page's turn rate slider.
     */
    private static final int TURN_RATE_LABEL = 104;
    private static final int TURN_RATE_DOWN = 105;
    private static final int TURN_RATE_UP = 106;
    /**
     * The local network's rows under the map list, top to bottom: the type, the subtype and the speed, which the turn rate's row follows. The
     * dialog has the three {@code 22} apart from the type's at {@code 261}; the four rows start {@link #LAN_ROW_SHIFT} above the type's and
     * are {@link #LAN_ROW_PITCH} apart, so that the turn rate's row ends inside the list's panel.
     */
    private static final int @NotNull [] @NotNull [] LAN_ROWS = {{GAME_TYPE_TITLE, GAME_TYPE}, {SUBTYPE_TITLE, SUBTYPE},
                                                                 {SPEED_LABEL, SPEED_DOWN_TEXT, SPEED, SPEED_UP_TEXT}};
    private static final int @NotNull [] LAN_SPEED_ROW = LAN_ROWS[LAN_ROWS.length - 1];
    private static final int @NotNull [] LAN_TURN_RATE_ROW = {TURN_RATE_LABEL, TURN_RATE_DOWN, TURN_RATE, TURN_RATE_UP};
    private static final int LAN_ROW_SHIFT = -8;
    private static final int LAN_ROW_PITCH = 20;
    /**
     * The game name and the password take at most as many characters as the install's create game page.
     */
    private static final int GAME_NAME_LENGTH = 31;
    private static final int PASSWORD_LENGTH = 32;
    private static final int MAP_SEARCH_LENGTH = 64;

    /**
     * The create game page ({@code GameCreatePanel}, page-relative): the title (the screens' page title), the labels in their column and the
     * controls in theirs: the local network's small label text, the game name and the password in the screens' framed text field, the local
     * network's drop-downs ({@code 172} wide, which sets the column), the framed folder button right of the map's, a square as high as the
     * drop-down's bar with the {@code 10} px green folder ({@code .folderIconHolder}), the speed and turn rate sliders between their {@code -}
     * and {@code +} ({@code .speedSpanLeft}, {@code .speedSpanRight}: {@code 7} % of the column each) with the value under the slider
     * ({@code .speedText}), and the radio buttons ({@code .radioButtonForm}: two columns, each from {@code 10} % in).
     * <p>
     * One row rule for the whole page: the rows start at the page's content top ({@link #PAGE_CONTENT_TOP}), each row is as high as the box
     * its control draws (the field {@code 20}, a drop-down's bar {@code 16}, a slider with its value, a radio button {@code 14}), the next row
     * starts {@code 3} under it, so that the page's ten rows end inside the panel art's frame ({@link #PANEL_FRAME_BOTTOM}), and the label
     * is centered on the row. While the type has no subtypes the rows under the subtype's move up into its place; the password's row keeps its
     * place while the game is public.
     */
    private static final int LABEL_LEFT = 16;
    private static final int LABEL_WIDTH = 112;
    private static final int FIELD_LEFT = 133;
    private static final int FIELD_WIDTH = 172;
    /**
     * The drawn bar of a small-text drop-down (the dialog sheet's small bar frame); the create page's drop-downs and the folder button are as
     * high as it.
     */
    private static final int CHOICE_BAR_HEIGHT = 16;
    private static final int RADIO_HEIGHT = 14;
    private static final int ROW_GAP = 3;
    private static final int FIELD_HEIGHT = FRAMED_CONTROL_HEIGHT;
    private static final int NAME_TOP = PAGE_CONTENT_TOP;
    private static final int PRIVACY_TOP = NAME_TOP + FIELD_HEIGHT + ROW_GAP;
    private static final int PASSWORD_TOP = PRIVACY_TOP + RADIO_HEIGHT + ROW_GAP;
    private static final int MAP_TOP = PASSWORD_TOP + FIELD_HEIGHT + ROW_GAP;
    private static final int TYPE_TOP = MAP_TOP + CHOICE_BAR_HEIGHT + ROW_GAP;
    private static final int SUBTYPE_TOP = TYPE_TOP + CHOICE_BAR_HEIGHT + ROW_GAP;
    private static final int SUBTYPE_ROW_SHIFT = -(CHOICE_BAR_HEIGHT + ROW_GAP);
    private static final int FOLDER_BUTTON_GAP = 2;
    private static final int FOLDER_BUTTON_LEFT = FIELD_LEFT + FIELD_WIDTH + FOLDER_BUTTON_GAP;
    private static final int FOLDER_BUTTON_WIDTH = CHOICE_BAR_HEIGHT;
    private static final float FOLDER_ICON_SIZE = 10;
    private static final @NotNull String FOLDER_ICON = "Folder_Green.png";
    private static final @NotNull String HOVERED_FOLDER_ICON = "Folder_GreenLight.png";
    private static final int SPEED_LEFT = 145;
    private static final int SPEED_WIDTH = 148;
    private static final int SPEED_TOP = SUBTYPE_TOP + CHOICE_BAR_HEIGHT + ROW_GAP;
    private static final int SPEED_HEIGHT = 12;
    /**
     * The small text's line around a slider's {@code -} and {@code +} (centered on the slider) and under it (its value).
     */
    private static final int SPEED_TEXT_OFFSET = 13;
    private static final int SPEED_TEXT_HEIGHT = 14;
    private static final int RANGE_TEXT_RAISE = (SPEED_TEXT_HEIGHT - SPEED_HEIGHT) / 2;
    private static final int SPEED_ROW_HEIGHT = SPEED_TEXT_OFFSET + SPEED_TEXT_HEIGHT;
    private static final int TURN_RATE_TOP = SPEED_TOP + SPEED_ROW_HEIGHT + ROW_GAP;
    private static final int HOTKEYS_TOP = TURN_RATE_TOP + SPEED_ROW_HEIGHT + ROW_GAP;
    private static final int LIMITS_TOP = HOTKEYS_TOP + RADIO_HEIGHT + ROW_GAP;
    private static final int FIRST_RADIO_LEFT = 142;
    private static final int SECOND_RADIO_LEFT = 226;
    private static final @NotNull String SPEED_DOWN = "-";
    private static final @NotNull String SPEED_UP = "+";
    private static final @NotNull String LABEL_SUFFIX = ":";
    /**
     * The text key of the dynamic turn rate's name, the turn rate slider's first step.
     */
    private static final @NotNull String DYNAMIC_TURN_RATE_KEY = "bnet_turnRate_dynamic";

    /**
     * The map browser ({@code MapBrowserPanel}, page-relative): the title, the open folder (at the page's content top) and the list's frame
     * ({@code .textAreaContainer}: a {@code 1} px {@code #6c0000} frame with {@code 3} px corners, {@code 1} under the folder) in the page's
     * padding ({@code .panelHolder}: {@code 13} left, {@code 7} right); the list inside the frame by a framed list's padding; the search
     * field {@code 3} under the frame, the magnifier first, ending {@code 15} above the page's bottom as the native field does (the page
     * reaches {@code 11} below the panel's frame), so that the list's frame takes the height between. The open folder and the list are the
     * local network's ({@code gluCreat} {@code 6}, {@code 5}: big text, the folder {@code 20} high) and the search field is the screens'
     * framed text field.
     */
    private static final int BROWSER_LEFT = 13;
    private static final int BROWSER_RIGHT_PADDING = 7;
    private static final int FOLDER_TOP = PAGE_CONTENT_TOP;
    private static final int FOLDER_HEIGHT = 20;
    private static final int LIST_FRAME_GAP = 1;
    private static final int LIST_FRAME_TOP = FOLDER_TOP + FOLDER_HEIGHT + LIST_FRAME_GAP;
    private static final int SEARCH_GAP = 3;
    private static final int BROWSER_BOTTOM_PADDING = 15;
    private static final @NotNull String SEARCH_ICON = "magnifying_glass_32.png";
    private static final int SIDE_COUNT = 2;
    private static final int NO_MAP = -1;

    private static final @NotNull String VISIBLE_FLAG = "WS_VISIBLE";
    private static final @NotNull String TAB_STOP_FLAG = "WS_TABSTOP";
    private static final @NotNull String SMALL_TEXT_FLAG = "RC_SMALLTEXT";
    private static final @NotNull String BIG_TEXT_FLAG = "RC_BIGTEXT";
    private static final @NotNull String HORIZONTAL_SLIDER_FLAG = "SBS_HORZ";

    private static final @NotNull String CUSTOM_HOTKEYS_OPTION = "game.customHotkeys";
    private static final @NotNull String EXTENDED_UNIT_LIMITS_OPTION = "game.extendedUnitLimits";
    private static final @NotNull String PASSWORD_KEY = "bnet118_legacyFlow_password";
    private static final @NotNull String NETWORK_ERROR_KEY = "gameNETWORKERROR";
    private static final @NotNull String CREATE_ERROR_KEY = "gameCREATEERROR";
    private static final @NotNull String INVALID_MAP_KEY = "gameINVALIDMAP";

    /**
     * Reads the game flags from the custom-hotkeys and extended-unit-limits settings, both enabled by default.
     *
     * @param settings the client settings
     * @return the flags
     */
    static int readGameFlags(@NotNull GlueSettings settings) {
        boolean customHotkeys = !(settings.getOption(CUSTOM_HOTKEYS_OPTION) instanceof Boolean value) || value;
        boolean extendedLimits = !(settings.getOption(EXTENDED_UNIT_LIMITS_OPTION) instanceof Boolean value) || value;

        return (customHotkeys ? 0 : HOTKEYS_DISABLED_FLAG) | (extendedLimits ? 0 : ORIGINAL_LIMITS_FLAG);
    }

    /**
     * @param pageHeight the map browser page's height
     * @return the search field's top in the page: the field's height above the page's bottom padding
     */
    private static int getSearchTop(int pageHeight) {
        return pageHeight - BROWSER_BOTTOM_PADDING - FIELD_HEIGHT;
    }

    /**
     * @param pageHeight the map browser page's height
     * @return the height of the list's frame: from under the open folder down to the search field's gap
     */
    private static int getListFrameHeight(int pageHeight) {
        return getSearchTop(pageHeight) - SEARCH_GAP - LIST_FRAME_TOP;
    }

    private static @NotNull DialogControl createPageControl(@NotNull ScrControlType type, int id, int left, int top, int width, int height,
                                                            @NotNull String @NotNull ... flags) {
        Set<String> names = new HashSet<>(Set.of(flags));

        names.add(VISIBLE_FLAG);

        return ScrDialogControlUtils.createControl(type, id, left, top, width, height, names, "", List.of());
    }

    /**
     * Adds the create page's labels in the label column, each as high as its row, and the slider rows' ends and values.
     */
    private static void addFormTexts(@NotNull List<DialogControl> controls, int formLeft, int formTop) {
        int[][] labels = {{NAME_LABEL, NAME_TOP, FIELD_HEIGHT}, {PRIVACY_LABEL, PRIVACY_TOP, RADIO_HEIGHT},
                          {PASSWORD_LABEL, PASSWORD_TOP, FIELD_HEIGHT}, {MAP_LABEL, MAP_TOP, CHOICE_BAR_HEIGHT},
                          {TYPE_LABEL, TYPE_TOP, CHOICE_BAR_HEIGHT}, {SUBTYPE_LABEL, SUBTYPE_TOP, CHOICE_BAR_HEIGHT},
                          {SPEED_ROW_LABEL, SPEED_TOP, SPEED_ROW_HEIGHT},
                          {TURN_RATE_ROW_LABEL, TURN_RATE_TOP, SPEED_ROW_HEIGHT}, {HOTKEYS_LABEL, HOTKEYS_TOP, RADIO_HEIGHT},
                          {LIMITS_LABEL, LIMITS_TOP, RADIO_HEIGHT}};

        for (int[] label : labels) {
            controls.add(createPageControl(ScrControlType.LTEXT, label[0], formLeft + LABEL_LEFT, formTop + label[1], LABEL_WIDTH, label[2],
                                           SMALL_TEXT_FLAG));
        }

        int[][] sliderRows = {{SPEED_MINUS_TEXT, SPEED_PLUS_TEXT, SPEED_VALUE, SPEED_TOP},
                              {TURN_RATE_MINUS_TEXT, TURN_RATE_PLUS_TEXT, TURN_RATE_VALUE, TURN_RATE_TOP}};

        for (int[] row : sliderRows) {
            int top = formTop + row[3];

            controls.add(createPageControl(ScrControlType.CTEXT, row[0], formLeft + FIELD_LEFT, top - RANGE_TEXT_RAISE, SPEED_LEFT - FIELD_LEFT,
                                           SPEED_TEXT_HEIGHT, SMALL_TEXT_FLAG));
            controls.add(createPageControl(ScrControlType.CTEXT, row[1], formLeft + SPEED_LEFT + SPEED_WIDTH, top - RANGE_TEXT_RAISE,
                                           FIELD_LEFT + FIELD_WIDTH - SPEED_LEFT - SPEED_WIDTH, SPEED_TEXT_HEIGHT, SMALL_TEXT_FLAG));
            controls.add(createPageControl(ScrControlType.CTEXT, row[2], formLeft + FIELD_LEFT, top + SPEED_TEXT_OFFSET, FIELD_WIDTH,
                                           SPEED_TEXT_HEIGHT, SMALL_TEXT_FLAG));
        }
    }

    /**
     * Returns the recently hosted map files that still exist, newest first; the list keeps each file's absolute path.
     */
    private static @NotNull List<@NotNull Path> findRecentFiles(@NotNull RecentMaps recent) {
        List<Path> files = new ArrayList<>();

        for (String map : recent.getMaps()) {
            try {
                Path file = Path.of(map);

                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            } catch (InvalidPathException exception) {
                LOGGER.warning("RECENT MAP SKIPPED: " + map + ": " + exception.getMessage());
            }
        }

        return files;
    }

    /**
     * @return a copy of a control with another id at another top
     */
    private static @NotNull DialogControl moveControl(@NotNull DialogControl control, int id, int top) {
        return ScrDialogControlUtils.createControl(ScrDialogControlUtils.getType(control), id, control.getX(), top, control.getWidth(),
                                                   control.getHeight(), ScrDialogControlUtils.getFlags(control),
                                                   ScrDialogControlUtils.getContent(control), control.getChildren());
    }

    /**
     * Lays out the local network's create dialog: moves the type, subtype and speed rows into the rows of {@link #LAN_ROWS} and adds the turn
     * rate's row under the speed's, a copy of it.
     *
     * @param file the dialog as the file has it
     * @return the dialog with the four rows
     * @throws IOException if the dialog has no type or subtype drop-down
     */
    private static @NotNull DialogDefinition createLanDialog(@NotNull DialogDefinition file) throws IOException {
        DialogControl typeChoice = ScrDialogControlUtils.findControl(file, GAME_TYPE);
        DialogControl subtypeChoice = ScrDialogControlUtils.findControl(file, SUBTYPE);

        if (typeChoice == null || subtypeChoice == null) {
            throw Exceptions.newIOException("{0} has no type or subtype drop-down.", file.getName());
        }

        int fileTop = typeChoice.getY();
        int filePitch = subtypeChoice.getY() - fileTop;
        int top = fileTop + LAN_ROW_SHIFT;
        List<DialogControl> controls = new ArrayList<>();

        for (DialogControl control : file.getControls()) {
            int row = findLanRow(control.getId());

            if (row < 0) {
                controls.add(control);

                continue;
            }

            // The control keeps its offset from its row's top in the file.
            int offset = control.getY() - fileTop - row * filePitch;

            controls.add(moveControl(control, control.getId(), top + row * LAN_ROW_PITCH + offset));

            for (int index = 0; index < LAN_SPEED_ROW.length; index++) {
                if (LAN_SPEED_ROW[index] == control.getId()) {
                    controls.add(moveControl(control, LAN_TURN_RATE_ROW[index], top + LAN_ROWS.length * LAN_ROW_PITCH + offset));
                }
            }
        }

        return new DialogDefinition(file.getName(), file.getX(), file.getY(), file.getWidth(), file.getHeight(), controls);
    }

    /**
     * @param control a control id
     * @return the index of the row of {@link #LAN_ROWS} that has the control, {@code -1} for none
     */
    private static int findLanRow(int control) {
        for (int row = 0; row < LAN_ROWS.length; row++) {
            for (int member : LAN_ROWS[row]) {
                if (member == control) {
                    return row;
                }
            }
        }

        return -1;
    }

    /**
     * @return the text of a local network row's label: the label, then the value at the label's right edge
     */
    private static @NotNull String formatRowLabel(@NotNull String label, @NotNull String value) {
        return String.format(Locale.ROOT, "%s%c%s", label, GameListScreen.RIGHT_ALIGN, value);
    }

    /**
     * The remembered "Game Subtype", applied the first time the subtype list is filled.
     */
    private int rememberedSubtype;

    private @NotNull List<GameTemplates.Template> templates;
    private int @NotNull [] typeIds;

    private @Nullable ComboBox gameType;
    private @Nullable ComboBox subtype;
    private @Nullable Slider speed;
    private @Nullable Slider turnRate;
    private int startSlots;
    private int gameFlags;

    private @Nullable ScrControl speedLabel;
    private @Nullable ScrControl turnRateLabel;
    private @Nullable ScrControl subtypeTitle;

    /**
     * The Beacon.Link pages' state: the game name, whether the game is private and its password, the map drop-down, its files
     * and the recently hosted maps, the search field, the chat, and whether the map browser shows; the selected file, and the one the create
     * page had when the browser opened.
     */
    private @Nullable EditControl gameName;
    private boolean privateGame;
    private @Nullable EditControl password;
    private @Nullable ComboBox mapChoice;
    private @Nullable RecentMaps recentMaps;
    private @NotNull List<Path> mapFiles;
    private @Nullable EditControl mapSearch;
    private final @NotNull LobbyChatLog chatLog;
    private @Nullable ChannelChat chat;
    private @Nullable EditControl chatInput;
    private boolean browserShown;
    private @Nullable Path selectedFile;
    private @Nullable Path formFile;

    /**
     * Creates a {@link CreateGameScreen} instance.
     */
    public CreateGameScreen() {
        super("gluCreat", MAP_EXTENSIONS);

        this.rememberedSubtype = GlueSettings.NONE;

        this.templates = List.of();
        this.typeIds   = new int[0];

        this.gameType   = null;
        this.subtype    = null;
        this.speed      = null;
        this.turnRate   = null;
        this.startSlots = 0;
        this.gameFlags  = 0;

        this.speedLabel    = null;
        this.turnRateLabel = null;
        this.subtypeTitle  = null;

        this.gameName     = null;
        this.privateGame  = false;
        this.password     = null;
        this.mapChoice    = null;
        this.recentMaps   = null;
        this.mapFiles     = List.of();
        this.mapSearch    = null;
        this.chatLog      = LobbyChatLog.createChatPageLog(LobbyChatLog.Page.GLOBAL);
        this.chat         = null;
        this.chatInput    = null;
        this.browserShown = false;
        this.selectedFile = null;
        this.formFile     = null;
    }

    /**
     * Loads the screen's dialog: {@code rez\<dialog name>.ui.json}, or an empty dialog without a name. A screen whose
     * native content is not a control list builds its dialog here.
     * <p>
     * Over Beacon.Link loads the Battle.net create dialog instead and adds the pages' controls; the local network's dialog gets the turn rate's
     * row under the speed's.
     *
     * @param glueContext the shared services
     * @return the dialog
     * @throws IOException if the dialog cannot be read, the local network's dialog has no type or subtype drop-down, or the Battle.net dialog
     *                     has no create, browser or chat page
     */
    @Override
    protected @NotNull DialogDefinition loadDialog(@NotNull GlueContext glueContext) throws IOException {
        if (!glueContext.isBeaconLinkSelected()) {
            return createLanDialog(super.loadDialog(glueContext));
        }

        DialogDefinition file = ScrDialogImportUtils.load(glueContext.getCanvas().getStorage(), BEACON_LINK_DIALOG);
        DialogControl form = ScrDialogControlUtils.findControl(file, FORM_PAGE);
        DialogControl browser = ScrDialogControlUtils.findControl(file, BROWSER_PAGE);
        DialogControl chatPage = ScrDialogControlUtils.findControl(file, CHAT_PAGE);

        if (form == null || browser == null || chatPage == null) {
            throw Exceptions.newIOException("{0} has no create, browser or chat page.", BEACON_LINK_DIALOG);
        }

        int formLeft = form.getX();
        int formTop = form.getY();
        int fieldLeft = formLeft + FIELD_LEFT;
        int browserLeft = browser.getX() + BROWSER_LEFT;
        int browserWidth = browser.getWidth() - BROWSER_LEFT - BROWSER_RIGHT_PADDING;
        int browserTop = browser.getY();
        int searchTop = getSearchTop(browser.getHeight());
        int listFrameHeight = getListFrameHeight(browser.getHeight());
        List<DialogControl> controls = new ArrayList<>(file.getControls());

        controls.add(createPageTitle(FORM_TITLE, form));
        addFormTexts(controls, formLeft, formTop);
        controls.add(createPageControl(ScrControlType.EDITTEXT, GAME_NAME, fieldLeft, formTop + NAME_TOP, FIELD_WIDTH, FIELD_HEIGHT, TAB_STOP_FLAG,
                                       SMALL_TEXT_FLAG, FRAMED_FLAG));
        controls.add(createPageControl(ScrControlType.RADIOBUTTON, PRIVACY_PUBLIC, formLeft + FIRST_RADIO_LEFT, formTop + PRIVACY_TOP,
                                       SECOND_RADIO_LEFT - FIRST_RADIO_LEFT, RADIO_HEIGHT, TAB_STOP_FLAG, SMALL_TEXT_FLAG, WebPanelStyleUtils.FLAG));
        controls.add(createPageControl(ScrControlType.RADIOBUTTON, PRIVACY_PRIVATE, formLeft + SECOND_RADIO_LEFT, formTop + PRIVACY_TOP,
                                       FIELD_LEFT + FIELD_WIDTH - SECOND_RADIO_LEFT, RADIO_HEIGHT, TAB_STOP_FLAG, SMALL_TEXT_FLAG,
                                       WebPanelStyleUtils.FLAG));
        controls.add(createPageControl(ScrControlType.EDITTEXT, PASSWORD, fieldLeft, formTop + PASSWORD_TOP, FIELD_WIDTH, FIELD_HEIGHT,
                                       TAB_STOP_FLAG, SMALL_TEXT_FLAG, FRAMED_FLAG));
        controls.add(createPageControl(ScrControlType.COMBOBOX, MAP_CHOICE, fieldLeft, formTop + MAP_TOP, FIELD_WIDTH, CHOICE_BAR_HEIGHT,
                                       TAB_STOP_FLAG, SMALL_TEXT_FLAG));
        controls.add(createPageControl(ScrControlType.FLCBTN, FOLDER_BUTTON, formLeft + FOLDER_BUTTON_LEFT, formTop + MAP_TOP, FOLDER_BUTTON_WIDTH,
                                       CHOICE_BAR_HEIGHT, TAB_STOP_FLAG, FRAMED_FLAG));
        controls.add(createPageControl(ScrControlType.COMBOBOX, GAME_TYPE, fieldLeft, formTop + TYPE_TOP, FIELD_WIDTH, CHOICE_BAR_HEIGHT,
                                       TAB_STOP_FLAG, SMALL_TEXT_FLAG));
        controls.add(createPageControl(ScrControlType.COMBOBOX, SUBTYPE, fieldLeft, formTop + SUBTYPE_TOP, FIELD_WIDTH, CHOICE_BAR_HEIGHT,
                                       TAB_STOP_FLAG, SMALL_TEXT_FLAG));
        controls.add(createPageControl(ScrControlType.SCROLLBAR, SPEED, formLeft + SPEED_LEFT, formTop + SPEED_TOP, SPEED_WIDTH, SPEED_HEIGHT,
                                       TAB_STOP_FLAG, HORIZONTAL_SLIDER_FLAG));
        controls.add(createPageControl(ScrControlType.SCROLLBAR, TURN_RATE, formLeft + SPEED_LEFT, formTop + TURN_RATE_TOP, SPEED_WIDTH,
                                       SPEED_HEIGHT, TAB_STOP_FLAG, HORIZONTAL_SLIDER_FLAG));
        controls.add(createPageControl(ScrControlType.RADIOBUTTON, HOTKEYS_ENABLED, formLeft + FIRST_RADIO_LEFT, formTop + HOTKEYS_TOP,
                                       SECOND_RADIO_LEFT - FIRST_RADIO_LEFT, RADIO_HEIGHT, TAB_STOP_FLAG, SMALL_TEXT_FLAG, WebPanelStyleUtils.FLAG));
        controls.add(createPageControl(ScrControlType.RADIOBUTTON, HOTKEYS_DISABLED, formLeft + SECOND_RADIO_LEFT, formTop + HOTKEYS_TOP,
                                       FIELD_LEFT + FIELD_WIDTH - SECOND_RADIO_LEFT, RADIO_HEIGHT, TAB_STOP_FLAG, SMALL_TEXT_FLAG,
                                       WebPanelStyleUtils.FLAG));
        controls.add(createPageControl(ScrControlType.RADIOBUTTON, LIMITS_EXTENDED, formLeft + FIRST_RADIO_LEFT, formTop + LIMITS_TOP,
                                       SECOND_RADIO_LEFT - FIRST_RADIO_LEFT, RADIO_HEIGHT, TAB_STOP_FLAG, SMALL_TEXT_FLAG, WebPanelStyleUtils.FLAG));
        controls.add(createPageControl(ScrControlType.RADIOBUTTON, LIMITS_ORIGINAL, formLeft + SECOND_RADIO_LEFT, formTop + LIMITS_TOP,
                                       FIELD_LEFT + FIELD_WIDTH - SECOND_RADIO_LEFT, RADIO_HEIGHT, TAB_STOP_FLAG, SMALL_TEXT_FLAG,
                                       WebPanelStyleUtils.FLAG));

        controls.add(createPageTitle(BROWSER_TITLE, browser));
        controls.add(createPageControl(ScrControlType.LTEXT, FOLDER, browserLeft, browserTop + FOLDER_TOP, browserWidth, FOLDER_HEIGHT,
                                       BIG_TEXT_FLAG));
        controls.add(createPageControl(ScrControlType.LISTBOX, LIST,
                                       browserLeft + WebRowList.FRAME_PADDING, browserTop + LIST_FRAME_TOP + WebRowList.FRAME_PADDING,
                                       browserWidth - SIDE_COUNT * WebRowList.FRAME_PADDING, listFrameHeight - SIDE_COUNT * WebRowList.FRAME_PADDING,
                                       TAB_STOP_FLAG, BIG_TEXT_FLAG));
        controls.add(createPageControl(ScrControlType.EDITTEXT, MAP_SEARCH, browserLeft, browserTop + searchTop, browserWidth, FIELD_HEIGHT,
                                       TAB_STOP_FLAG, SMALL_TEXT_FLAG, FRAMED_FLAG));

        controls.add(LobbyChatLog.createFieldInputDefinition(LobbyChatLog.Page.GLOBAL, chatPage, CHAT_INPUT));
        controls.add(WebScrollBar.createDefinition(CHAT_SCROLL, chatPage));

        return new DialogDefinition(file.getName(), file.getX(), file.getY(), file.getWidth(), file.getHeight(), controls);
    }

    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * Loads the templates and the remembered creation settings, fills the type, subtype, speed and turn rate controls and opens the map list. Over
     * Beacon.Link also prepares the game name, the map drop-down, the radio buttons, the search field and the chat, and shows the create
     * page.
     *
     * @param glueContext the shared services
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) {
        boolean beaconLink = glueContext.isBeaconLinkSelected();

        this.speedLabel    = this.bindControl(SPEED_LABEL);
        this.turnRateLabel = beaconLink ? null : this.bindControl(TURN_RATE_LABEL);
        this.subtypeTitle  = this.bindControl(SUBTYPE_TITLE);

        this.bindControl(SLOT_PANEL).setVisible(false);
        this.bindControl(CHAT_PANEL).setVisible(beaconLink);

        this.templates = GameTemplates.loadOrCreateDefaults(glueContext.getCanvas().getStorage(), LOGGER);
        this.typeIds   = GameTemplates.getTypeIds(this.templates, template -> true);

        // Restore game type, subtype, speed and turn rate choices before restoring the selected map.
        GlueSettings settings = glueContext.getSettings();
        ComboBox types = this.addComboBox(GAME_TYPE);
        int rememberedType = settings.getCreateGameType();
        int selectedType = 0;

        for (int index = 0; index < this.typeIds.length; index++) {
            types.addItem(GameTemplates.getTypeName(glueContext.getGluText(), this.typeIds[index]), this.typeIds[index]);

            if (this.typeIds[index] == rememberedType) {
                selectedType = index;
            }
        }

        types.select(selectedType);
        this.gameType          = types;
        this.subtype           = this.addComboBox(SUBTYPE);
        this.rememberedSubtype = settings.getCreateGameValue(GlueSettings.SUBTYPE);
        this.gameFlags         = readGameFlags(settings);

        Slider slider = this.addSlider(SPEED);

        slider.setRange(0, GameSpeed.FASTEST);

        int rememberedSpeed = settings.getCreateGameValue(GlueSettings.SPEED);

        slider.setValue(rememberedSpeed >= 0 && rememberedSpeed <= GameSpeed.FASTEST ? rememberedSpeed : GameSpeed.FASTEST);
        this.speed = slider;

        Slider rates = this.addSlider(TURN_RATE);
        int rememberedRate = settings.getCreateGameValue(GlueSettings.TURN_RATE);

        rates.setRange(0, LanClient.TURN_RATES.length);
        rates.setValue(findTurnRateStep(rememberedRate));
        this.turnRate = rates;

        super.onOpen(glueContext);
        this.updateSpeed();
        this.updateTurnRate();
        this.updateSubtype();

        if (beaconLink) {
            this.openPages(glueContext);
        }
    }

    /**
     * Prepares the Beacon.Link pages: the radio buttons' labels and states, the map drop-down and the recently hosted maps, the inputs, the
     * selected map, the chat and the create page.
     */
    private void openPages(@NotNull GlueContext glueContext) {
        GluText text = glueContext.getGluText();

        this.mapChoice  = this.addComboBox(MAP_CHOICE);
        this.recentMaps = RecentMaps.read(glueContext.getFolders().getRecentMapsFile());
        this.bindControl(PRIVACY_PUBLIC).setReplacedText(text.get("createGameScreen_public"));
        this.bindControl(PRIVACY_PRIVATE).setReplacedText(text.get("createGameScreen_private"));
        this.bindControl(HOTKEYS_ENABLED).setReplacedText(text.get("createGameScreen_enabled"));
        this.bindControl(HOTKEYS_DISABLED).setReplacedText(text.get("createGameScreen_disabled"));
        this.bindControl(LIMITS_EXTENDED).setReplacedText(text.get("createGameScreen_extended"));
        this.bindControl(LIMITS_ORIGINAL).setReplacedText(text.get("createGameScreen_original"));
        this.bindControl(FORM_TITLE).setReplacedText(text.get("createGameScreen_title"));
        this.bindControl(BROWSER_TITLE).setReplacedText(text.get("createGameScreen_fileBrowser_title"));

        String[] labelKeys = {"createGameScreen_gameName", "createGameScreen_privacy", PASSWORD_KEY, "createGameScreen_map", "infoPane_gameType",
                              "infoPane_speed", "infoPane_turnRate", "createGameScreen_customHotkeys", "createGameScreen_gameLimits"};
        int[] labelControls = {NAME_LABEL, PRIVACY_LABEL, PASSWORD_LABEL, MAP_LABEL, TYPE_LABEL, SPEED_ROW_LABEL, TURN_RATE_ROW_LABEL, HOTKEYS_LABEL,
                               LIMITS_LABEL};

        for (int index = 0; index < labelControls.length; index++) {
            this.setLabel(labelControls[index], text.get(labelKeys[index]));
        }

        this.bindControl(SUBTYPE_LABEL).setTextFormat(ControlPainterUtils.ROW_TEXT_FORMAT);

        // The slider's ends are centered on the slider.
        int[][] ends = {{SPEED_MINUS_TEXT, SPEED_PLUS_TEXT}, {TURN_RATE_MINUS_TEXT, TURN_RATE_PLUS_TEXT}};

        for (int[] row : ends) {
            this.bindControl(row[0]).setReplacedText(SPEED_DOWN);
            this.bindControl(row[1]).setReplacedText(SPEED_UP);
            this.bindControl(row[0]).setTextFormat(GlueCanvas.FORMAT_CENTER | GlueCanvas.FORMAT_MIDDLE);
            this.bindControl(row[1]).setTextFormat(GlueCanvas.FORMAT_CENTER | GlueCanvas.FORMAT_MIDDLE);
        }

        this.updateRadioButtons();

        this.chatInput = this.setEditText(CHAT_INPUT, "", ChatChannel.MAXIMUM_TEXT_LENGTH, "");
        this.mapSearch = this.setEditText(MAP_SEARCH, "", MAP_SEARCH_LENGTH, "");
        // The join popup asks for the password with the shared edit popup, so the field refuses the characters the popup refuses.
        this.password = this.setEditText(PASSWORD, "", PASSWORD_LENGTH, GlueEditPopup.ALWAYS_REJECTED);
        this.gameName = this.setEditText(GAME_NAME, this.getDefaultGameName(), GAME_NAME_LENGTH, "");
        this.chatLog.setScrollBar(this.requireDialogState().attach(new WebScrollBar(this.bindControl(CHAT_SCROLL).getDefinition())));

        // The create page always has a map while the folder has one: the first file when no file is remembered.
        List<Path> files = this.getListedFiles();

        if (this.selectedFile == null && !files.isEmpty()) {
            this.showFile(files.getFirst());
        }

        ChannelChat opened = glueContext.getChannelChat();

        this.chat = opened;

        if (opened != null) {
            this.chatLog.listen(opened);
        }

        this.showBrowser(false);
    }

    /**
     * Sets a label of the create page: the text with the labels' suffix, centered on its row as the drop-down centers its item
     * ({@link ControlPainterUtils#ROW_TEXT_FORMAT}).
     */
    private void setLabel(int control, @NotNull String text) {
        ScrControl label = this.bindControl(control);

        label.setReplacedText(text + LABEL_SUFFIX);
        label.setTextFormat(ControlPainterUtils.ROW_TEXT_FORMAT);
    }

    /**
     * @return the default game name: the player's game over Beacon.Link
     */
    private @NotNull String getDefaultGameName() {
        GlueContext glueContext = this.getContext();
        LanClient client = glueContext.getLanClient();
        String playerName = client == null ? glueContext.getPlayerName() : client.getPlayerName();

        return OwnText.GAME_NAME_DEFAULT.format(glueContext.getLocale(), playerName);
    }

    /**
     * @return the name of the hosted game: the typed name over Beacon.Link (the default name when it is blank), the player's name on the local
     * network
     */
    private @NotNull String getGameName(@NotNull LanClient client) {
        EditControl input = this.gameName;

        if (input == null) {
            return client.getPlayerName();
        }

        String typed = input.getEditText().getText().strip();

        return typed.isEmpty() ? this.getDefaultGameName() : typed;
    }

    /**
     * Finds the turn rate slider's step of a turn rate: step {@code 0} is the dynamic turn rate ({@link LanClient#DYNAMIC_TURN_RATE}), the
     * next steps are {@link LanClient#TURN_RATES} in order.
     *
     * @param turnRate turns per second, {@link LanClient#DYNAMIC_TURN_RATE} or {@link GlueSettings#NONE}
     * @return the step of the turn rate, step {@code 0} for a turn rate the slider does not offer
     */
    private static int findTurnRateStep(int turnRate) {
        for (int index = 0; index < LanClient.TURN_RATES.length; index++) {
            if (LanClient.TURN_RATES[index] == turnRate) {
                return index + 1;
            }
        }

        return 0;
    }

    /**
     * @return the turn rate the turn rate slider chooses ({@link LanClient#DYNAMIC_TURN_RATE} at its first step), the dynamic turn rate
     * before the screen opened
     */
    private int getTurnRate() {
        Slider rates = this.turnRate;

        if (rates == null) {
            return LanClient.DYNAMIC_TURN_RATE;
        }

        int step = Math.clamp(rates.getValue(), 0, LanClient.TURN_RATES.length);

        return step == 0 ? LanClient.DYNAMIC_TURN_RATE : LanClient.TURN_RATES[step - 1];
    }

    /**
     * @return the shown name of the chosen turn rate: the localized dynamic turn rate name, or the turns per second
     */
    private @NotNull String getTurnRateName() {
        int rate = this.getTurnRate();

        return rate == LanClient.DYNAMIC_TURN_RATE ? this.getContext().getGluText().get(DYNAMIC_TURN_RATE_KEY) : String.valueOf(rate);
    }

    /**
     * @return the password of a private game, empty for a public game
     */
    private @NotNull String getPassword() {
        EditControl input = this.password;

        return this.privateGame && input != null ? input.getEditText().getText() : "";
    }

    /**
     * Shows the game public or private; the password's row shows for a private game only.
     */
    private void setPrivateGame(boolean privateGame) {
        this.privateGame = privateGame;
        this.updateRadioButtons();
        this.bindControl(PASSWORD).setVisible(privateGame && !this.browserShown);
        this.bindControl(PASSWORD_LABEL).setVisible(privateGame && !this.browserShown);
        this.setFocusedEdit(privateGame ? this.password : this.gameName);
    }

    /**
     * Shows the map browser or the create page in the page's place; the create page's map drop-down then lists the recently hosted maps.
     *
     * @param shown {@code true} for the map browser
     */
    private void showBrowser(boolean shown) {
        this.browserShown = shown;

        for (int control : FORM_CONTROLS) {
            this.bindControl(control).setVisible(!shown);
        }

        this.bindControl(PASSWORD).setVisible(!shown && this.privateGame);
        this.bindControl(PASSWORD_LABEL).setVisible(!shown && this.privateGame);

        for (int control : BROWSER_CONTROLS) {
            this.bindControl(control).setVisible(shown);
        }

        if (!shown) {
            this.updateSubtype();
            this.updateMapChoice();
        }

        this.setFocusedEdit(shown ? this.mapSearch : this.gameName);
    }

    /**
     * Lists the recently hosted maps in the map drop-down, newest first, with the selected file first when it is not one of them, and selects
     * the selected file.
     */
    private void updateMapChoice() {
        ComboBox maps = this.mapChoice;
        RecentMaps recent = this.recentMaps;
        Path chosen = this.selectedFile;

        if (maps == null || recent == null) {
            return;
        }

        List<Path> files = findRecentFiles(recent);
        int selected = NO_MAP;

        if (chosen != null && !files.contains(chosen.toAbsolutePath().normalize())) {
            files.addFirst(chosen);
        }

        maps.clear();

        for (int index = 0; index < files.size(); index++) {
            maps.addItem(String.valueOf(files.get(index).getFileName()), index);

            if (chosen != null && files.get(index).toAbsolutePath().normalize().equals(chosen.toAbsolutePath().normalize())) {
                selected = index;
            }
        }

        this.mapFiles = files;

        if (selected != NO_MAP) {
            maps.select(selected);
        }
    }

    private void updateRadioButtons() {
        this.bindControl(PRIVACY_PUBLIC).setChecked(!this.privateGame);
        this.bindControl(PRIVACY_PRIVATE).setChecked(this.privateGame);
        this.bindControl(HOTKEYS_ENABLED).setChecked((this.gameFlags & HOTKEYS_DISABLED_FLAG) == 0);
        this.bindControl(HOTKEYS_DISABLED).setChecked((this.gameFlags & HOTKEYS_DISABLED_FLAG) != 0);
        this.bindControl(LIMITS_EXTENDED).setChecked((this.gameFlags & ORIGINAL_LIMITS_FLAG) == 0);
        this.bindControl(LIMITS_ORIGINAL).setChecked((this.gameFlags & ORIGINAL_LIMITS_FLAG) != 0);
    }

    /**
     * Sets a game flag from its radio buttons and stores it in the setting the options show.
     */
    private void setGameFlag(int flag, boolean set, @NotNull String option) {
        this.gameFlags = set ? this.gameFlags | flag : this.gameFlags & ~flag;
        this.getContext().getSettings().setOption(option, !set);
        this.updateRadioButtons();
    }

    /**
     * @param glueContext the shared services
     * @return the root folder of the list
     */
    @Override
    protected @NotNull Path getRoot(@NotNull GlueContext glueContext) {
        return glueContext.getFolders().getMapDirectory();
    }

    /**
     * @param glueContext the shared services
     * @return the file to show selected when the screen opens (its folder is opened), or {@code null} for the root
     */
    @Override
    protected @Nullable Path getRememberedFile(@NotNull GlueContext glueContext) {
        return glueContext.getLastMap();
    }

    /**
     * Returns files of other folders that the root folder lists after its own files.
     * <p>
     * The map folder lists the player's multiplayer saves.
     *
     * @param glueContext the shared services
     * @return the files, empty by default
     */
    @Override
    protected @NotNull List<Path> getRootFiles(@NotNull GlueContext glueContext) {
        return LanSavedGame.list(glueContext.getSaveDirectory(), new ScrSaveFormat(SaveGameKind.of(glueContext.isExpansion(), true)));
    }

    /**
     * @param file a listed file
     * @return {@code true} if the file is a multiplayer save of the current edition, {@code false} otherwise
     */
    private boolean isSavedGame(@NotNull Path file) {
        return String.valueOf(file.getFileName()).toLowerCase(Locale.ROOT).endsWith(
                SaveGameKind.of(this.getContext().isExpansion(), true).getExtension()
        );
    }

    /**
     * Called when the selected file changes.
     * <p>
     * Remembers a selected map and updates the subtypes for the selected map's start slots.
     *
     * @param file the selected file, or {@code null} for a folder or no selection
     */
    @Override
    protected void onFileSelected(@Nullable Path file) {
        this.selectedFile = file;

        if (file != null && !this.isSavedGame(file)) {
            this.getContext().setLastMap(file);
        }

        MapInformation map = this.getSelectedMap();

        this.startSlots = map == null ? 0 : map.getStartSlots();
        this.updateSubtype();
    }

    /**
     * Called after the user changed the selection of a list or combo box, or the value of a slider.
     * <p>
     * Applies the file list's selection and stores the chosen game type and subtype; the map drop-down selects its file.
     *
     * @param control the control id
     */
    @Override
    protected void onSelectionChanged(int control) {
        super.onSelectionChanged(control);

        GlueSettings settings = this.getContext().getSettings();

        if (control == GAME_TYPE) {
            settings.setCreateGameType(this.getGameType());
            this.updateSubtype();
        }

        if ((control == GAME_TYPE || control == SUBTYPE) && this.subtype != null && this.subtype.getItemCount() > 0) {
            settings.setCreateGameValue(GlueSettings.SUBTYPE, this.subtype.getSelectedValue(DEFAULT_SUBTYPE_ID));
        }

        ComboBox maps = this.mapChoice;

        if (control == MAP_CHOICE && maps != null) {
            int index = maps.getSelectedValue(NO_MAP);

            if (index >= 0 && index < this.mapFiles.size()) {
                this.showFile(this.mapFiles.get(index));
            }
        }
    }

    /**
     * Called after the text of an {@code EDITTEXT} changed.
     * <p>
     * Stores and shows the speed after its control changed; the map browser lists the files that match the search text; a whisper start
     * typed in the chat input becomes the input's whisper target.
     *
     * @param control the control id
     */
    @Override
    protected void onEditChanged(int control) {
        EditControl search = this.mapSearch;
        String chatText = control == CHAT_INPUT ? LobbyChatLog.takeWhisperStart(this.chat, this.chatInput) : null;

        if (chatText != null) {
            this.chatInput = this.setEditText(CHAT_INPUT, chatText, ChatChannel.MAXIMUM_TEXT_LENGTH, "");
        }

        if (control == SPEED) {
            this.updateSpeed();
        } else if (control == TURN_RATE) {
            this.updateTurnRate();
        } else if (control == MAP_SEARCH && search != null) {
            this.setNameQuery(search.getEditText().getText());
        }
    }

    /**
     * Called when a pressable control that is not a button (a list box) is pressed.
     * <p>
     * Stores and shows the speed or the turn rate after its control was pressed.
     *
     * @param control the control id
     * @param x       the interface x
     * @param y       the interface y
     */
    @Override
    protected void onPressed(int control, float x, float y) {
        if (control == SPEED) {
            this.updateSpeed();
        } else if (control == TURN_RATE) {
            this.updateTurnRate();
        }
    }

    /**
     * Called by {@link #update} for screen-specific timers.
     * <p>
     * Keeps the stored speed and turn rate and their labels current, over Beacon.Link the slider rows' values too; over Beacon.Link polls the
     * network, which hands the channel's lines to the chat.
     *
     * @param time the time in milliseconds
     */
    @Override
    protected void onUpdate(long time) {
        LanClient client = this.getContext().getLanClient();

        if (this.chat != null && client != null) {
            client.update();
        }

        this.updateSpeed();
        this.updateTurnRate();

        if (this.gameName != null) {
            this.bindControl(SPEED_VALUE).setReplacedText(GameListScreen.getSpeedName(this.getContext(), this.getSpeed()));
            this.bindControl(TURN_RATE_VALUE).setReplacedText(this.getTurnRateName());
        }
    }

    private int getSpeed() {
        return this.speed == null ? GameSpeed.FASTEST : Math.clamp(this.speed.getValue(), 0, GameSpeed.FASTEST);
    }

    private void updateSpeed() {
        GlueContext glueContext = this.getContext();

        if (this.speed != null) {
            glueContext.getSettings().setCreateGameValue(GlueSettings.SPEED, this.getSpeed());
        }

        requireBound(this.speedLabel).setReplacedText(formatRowLabel(glueContext.getGluText().get("gameSPEED"),
                                                                     GameListScreen.getSpeedName(glueContext, this.getSpeed())));
    }

    /**
     * Stores the turn rate and shows it in the local network's turn rate label.
     */
    private void updateTurnRate() {
        GlueContext glueContext = this.getContext();
        ScrControl label = this.turnRateLabel;

        if (this.turnRate != null) {
            glueContext.getSettings().setCreateGameValue(GlueSettings.TURN_RATE, this.getTurnRate());
        }

        if (label != null) {
            label.setReplacedText(formatRowLabel(glueContext.getGluText().get("infoPane_turnRate") + LABEL_SUFFIX, this.getTurnRateName()));
        }
    }

    private int getGameType() {
        int first = this.typeIds.length == 0 ? GameTemplate.TYPE_MELEE : this.typeIds[0];

        return this.gameType == null ? first : this.gameType.getSelectedValue(first);
    }

    /**
     * @return the selected type's templates the subtype box offers for the map, in subtype value order
     */
    private @NotNull List<GameTemplates.Template> getOfferedSubtypes() {
        int slots = Math.max(MINIMUM_START_SLOTS, this.startSlots);
        List<GameTemplates.Template> offered = new ArrayList<>();

        for (GameTemplates.Template template : GameTemplates.getSubtypes(this.templates, this.getGameType())) {
            GameTemplate options = template.getTemplate();

            if (options.isTopVsBottom() && options.getSubTypeValue() >= slots || options.hasTeams() && options.getSubTypeValue() > slots) {
                continue;
            }

            offered.add(template);
        }

        offered.sort(Comparator.comparingInt(template -> template.getTemplate().getSubTypeValue()));

        return offered;
    }

    private void updateSubtype() {
        ComboBox subtypes = this.subtype;

        if (subtypes == null) {
            return;
        }

        List<GameTemplates.Template> offered = this.getOfferedSubtypes();
        boolean shown = GameTemplates.getSubtypes(this.templates, this.getGameType()).size() > 1;
        int previous = this.rememberedSubtype != GlueSettings.NONE ? this.rememberedSubtype : subtypes.getSelectedValue(DEFAULT_SUBTYPE_ID);
        int slots = Math.max(MINIMUM_START_SLOTS, this.startSlots);
        int selected = 0;

        this.rememberedSubtype = GlueSettings.NONE;
        requireBound(this.subtypeTitle).setVisible(shown);
        subtypes.setVisible(shown && !this.browserShown);
        subtypes.clear();

        if (this.gameName != null) {
            this.bindControl(SUBTYPE_LABEL).setVisible(shown && !this.browserShown);

            for (int control : ROWS_UNDER_SUBTYPE) {
                this.bindControl(control).setVerticalShift(shown ? 0 : SUBTYPE_ROW_SHIFT);
            }
        }

        if (offered.isEmpty()) {
            return;
        }

        String title = GameListScreen.getSubtypeTitle(this.getContext(), offered.getFirst());

        requireBound(this.subtypeTitle).setReplacedText(title);

        if (this.gameName != null) {
            this.setLabel(SUBTYPE_LABEL, title.endsWith(LABEL_SUFFIX) ? title.substring(0, title.length() - LABEL_SUFFIX.length()) : title);
        }

        for (GameTemplates.Template template : offered) {
            GameTemplate options = template.getTemplate();

            if (options.getSubTypeId() == previous) {
                selected = subtypes.getItemCount();
            }

            subtypes.addItem(GameListScreen.getSubtypeText(this.getContext(), options, slots), options.getSubTypeId());
        }

        subtypes.select(selected);
    }

    /**
     * @return the template of the chosen type and subtype (the type's first one without a subtype), or {@code null} when the type has none
     */
    private GameTemplates.@Nullable Template getSelectedTemplate() {
        List<GameTemplates.Template> offered = this.getOfferedSubtypes();
        int subtypeId = this.subtype == null || this.subtype.getItemCount() == 0
                        ? GlueSettings.NONE
                        : this.subtype.getSelectedValue(GlueSettings.NONE);
        GameTemplates.Template template = GameTemplates.find(offered, this.getGameType(), subtypeId);

        if (template == null && !offered.isEmpty()) {
            template = offered.getFirst();
        }

        return template;
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     * <p>
     * Over Beacon.Link the folder button opens the map browser and the radio buttons set the privacy, the custom hotkeys and the unit limits; Ok
     * hosts the
     * selected file from the create page, and in the map browser opens a folder or takes the file back to the create page; Cancel in the
     * map browser returns to the create page with the file it had. Otherwise as the file list: Ok opens the selected folder or chooses the
     * selected file, Cancel leaves.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
        if (this.gameName == null) {
            super.onActivate(control);

            return;
        }

        switch (control) {
            case FOLDER_BUTTON -> {
                this.formFile = this.selectedFile;
                this.showBrowser(true);
            }
            case PRIVACY_PUBLIC, PRIVACY_PRIVATE -> this.setPrivateGame(control == PRIVACY_PRIVATE);
            case HOTKEYS_ENABLED, HOTKEYS_DISABLED -> this.setGameFlag(HOTKEYS_DISABLED_FLAG, control == HOTKEYS_DISABLED, CUSTOM_HOTKEYS_OPTION);
            case LIMITS_EXTENDED, LIMITS_ORIGINAL -> this.setGameFlag(ORIGINAL_LIMITS_FLAG, control == LIMITS_ORIGINAL, EXTENDED_UNIT_LIMITS_OPTION);
            case OK -> {
                Path file = this.selectedFile;

                if (this.browserShown) {
                    super.onActivate(control);
                } else if (file == null) {
                    this.showError(this.getContext().getGluText().get(INVALID_MAP_KEY));
                } else {
                    this.onFileChosen(file);
                }
            }
            case CANCEL -> {
                Path file = this.formFile;

                if (!this.browserShown) {
                    super.onActivate(control);

                    return;
                }

                if (file != null) {
                    this.showFile(file);
                }

                this.showBrowser(false);
            }
            default -> super.onActivate(control);
        }
    }

    /**
     * Called when Ok is chosen on a file.
     * <p>
     * In the map browser takes the file back to the create page. Otherwise hosts a chosen multiplayer save, or checks the chosen map and
     * template and hosts the map with the chosen turn rate (over Beacon.Link also with the chosen privacy), which then leads the recently hosted
     * maps.
     *
     * @param file the file
     */
    @Override
    protected void onFileChosen(@NotNull Path file) {
        if (this.browserShown) {
            this.showBrowser(false);

            return;
        }

        if (this.isSavedGame(file)) {
            this.hostSavedGame(file);

            return;
        }

        GlueContext glueContext = this.getContext();
        MapInformation map = this.getSelectedMap();

        if (map == null || this.startSlots == 0) {
            this.showError(glueContext.getGluText().get(INVALID_MAP_KEY));

            return;
        }

        // Reject missing templates and templates unsupported by the map's start slots.
        GameTemplates.Template template = this.getSelectedTemplate();

        if (template == null || !template.getTemplate().isSupportedBy(this.startSlots)) {
            this.showError(glueContext.getGluText().get(BAD_GAME_TYPE_KEY));

            return;
        }

        LanClient client = glueContext.getLanClient();

        if (client == null) {
            this.showError(glueContext.getGluText().get(NETWORK_ERROR_KEY));

            return;
        }

        try {
            client.setGameFlags(this.gameFlags);
            client.setTurnRate(this.getTurnRate());
            client.getNetwork().setPassword(this.getPassword());

            LanClient.HostedMap hostedMap = LanClient.HostedMap.read(file, map.getMap(), map.getName());
            GameTemplates.Template selectedTemplate = this.getSelectedTemplate();

            client.host(this.getGameName(client), hostedMap, selectedTemplate == null ? LanGameOptions.MELEE : selectedTemplate.getOptions(),
                        this.getSpeed());
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("LAN CREATE FAILED: " + exception.getMessage());
            this.showError(glueContext.getGluText().get(CREATE_ERROR_KEY));

            return;
        }

        if (this.recentMaps != null) {
            this.recentMaps.add(file.toAbsolutePath().normalize().toString());
        }

        this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.GAME_LOBBY));
    }

    /**
     * Hosts a multiplayer saved game with its saved speed and game type.
     *
     * @param file the multiplayer save file
     */
    private void hostSavedGame(@NotNull Path file) {
        GlueContext glueContext = this.getContext();
        LanClient client = glueContext.getLanClient();

        if (client == null) {
            this.showError(glueContext.getGluText().get(NETWORK_ERROR_KEY));

            return;
        }

        try {
            client.setGameFlags(this.gameFlags);
            client.setTurnRate(this.getTurnRate());
            client.getNetwork().setPassword(this.getPassword());
            client.hostSaved(this.getGameName(client), LanSavedGame.read(file), GameProxySocket.GAME_PORT);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("LAN CREATE SAVED GAME FAILED: " + file.getFileName() + ": " + exception.getMessage());
            this.showError(glueContext.getGluText().get(CREATE_ERROR_KEY));

            return;
        }

        this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.GAME_LOBBY));
    }

    /**
     * Called first for every key of the screen (after an open popup): a screen without controls, such as a cinematic, takes its keys here.
     * <p>
     * Takes Enter while the chat input has the focus and holds a line, and says or whispers the line or runs the command, and Backspace in
     * the empty input after a whisper, which says lines in the channel again; otherwise Enter is Ok.
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
     * Takes presses on the chat log's scroll bar and its text (a selection).
     *
     * @param x the interface x
     * @param y the interface y
     * @return {@code true} if the press was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onPointerPressed(float x, float y) {
        return this.chat != null && this.chatLog.press(this.getContext().getCanvas(), x, y, this.isExtendingPress(), this.isDoublePress());
    }

    /**
     * Called for every pointer move of the screen (after an open combo box or popup takes it).
     * <p>
     * Drags the chat log's scroll bar or its selection.
     *
     * @param x the interface x
     * @param y the interface y
     */
    @Override
    protected void onPointerMoved(float x, float y) {
        if (this.chat != null) {
            this.chatLog.drag(this.getContext().getCanvas(), x, y);
        }
    }

    /**
     * Called for every release of the left button of the screen.
     * <p>
     * Releases the chat log's scroll bar and its selection.
     *
     * @param x the interface x
     * @param y the interface y
     */
    @Override
    protected void onPointerReleased(float x, float y) {
        if (this.chat != null) {
            this.chatLog.release();
        }
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
        return this.chat != null && this.chatLog.copy(this.getContext().getClipboard());
    }

    /**
     * Called first for a wheel notch of the screen.
     * <p>
     * Scrolls the chat log under the pointer.
     *
     * @param x    the pointer's interface x
     * @param y    the pointer's interface y
     * @param down {@code true} for a notch toward the user
     * @return {@code true} if the notch was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onWheel(float x, float y, boolean down) {
        DialogControl chatPage = this.chat == null ? null : ScrDialogControlUtils.findControl(this.getDialog(), CHAT_PAGE);

        if (chatPage == null || !GlueCanvas.contains(x - this.getDialogLeft(), y - this.getDialogTop(),
                                                     chatPage.getX(), chatPage.getY(), chatPage.getWidth(), chatPage.getHeight())) {
            return false;
        }

        this.chatLog.scrollByWheel(down);

        return true;
    }

    /**
     * Draws one control.
     * <p>
     * Draws the Beacon.Link pages: the map browser's list frame, the chat log and the folder on the folder button; the other controls draw as
     * the file list draws them.
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
            case BROWSER_PAGE -> {
                if (this.browserShown) {
                    this.drawBrowserPage(canvas, control, left, top);
                }
            }
            case CHAT_PAGE -> {
                if (this.chat != null) {
                    this.chatLog.draw(canvas, left, top, control.getWidth(), control.getHeight());
                }
            }
            case FOLDER_BUTTON -> {
                boolean hovered = this.getHoveredControl() == control.getId();

                super.drawControl(canvas, control, left, top);
                canvas.drawImage(canvas.getWebImage(hovered ? HOVERED_FOLDER_ICON : FOLDER_ICON),
                                 left + (control.getWidth() - FOLDER_ICON_SIZE) / SIDE_COUNT,
                                 top + (control.getHeight() - FOLDER_ICON_SIZE) / SIDE_COUNT,
                                 FOLDER_ICON_SIZE, FOLDER_ICON_SIZE);
            }
            default -> super.drawControl(canvas, control, left, top);
        }
    }

    /**
     * Draws the map browser's list frame; the title, the open folder, the list and the search field draw as controls.
     */
    private void drawBrowserPage(@NotNull GlueCanvas canvas, @NotNull DialogControl page, float left, float top) {
        WebPanelStyleUtils.drawBox(canvas,
                                   left + BROWSER_LEFT, top + LIST_FRAME_TOP,
                                   page.getWidth() - BROWSER_LEFT - BROWSER_RIGHT_PADDING, getListFrameHeight(page.getHeight()),
                                   WebPanelStyleUtils.BORDER, 0,
                                   true, true,
                                   true, true);
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
     * Shows the magnifier in the map search field; other fields have none.
     *
     * @param control an {@code EDITTEXT} control with {@link #FRAMED_FLAG}
     * @return the image the text field shows left of its text, or an empty string for none
     */
    @Override
    protected @NotNull String getEditIcon(@NotNull DialogControl control) {
        return control.getId() == MAP_SEARCH ? SEARCH_ICON : super.getEditIcon(control);
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
     * Called when Cancel is chosen: returns to the campaign screen (the single player screens).
     * <p>
     * Returns to the game list instead, or over Beacon.Link to the channel chat.
     */
    @Override
    protected void onCancel() {
        this.leave(() -> this.getContext().getNavigator().show(this.getContext().getMultiplayerHome()));
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
