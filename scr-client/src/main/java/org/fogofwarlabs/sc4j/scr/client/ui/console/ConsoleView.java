package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.engine.client.dialog.DialogKey;
import org.fogofwarlabs.sc4j.engine.client.host.PointerClip;
import org.fogofwarlabs.sc4j.engine.client.hud.MinimapPings;
import org.fogofwarlabs.sc4j.engine.client.render.Texture;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteFrame;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.scr.client.ChatPrompt;
import org.fogofwarlabs.sc4j.scr.client.cursor.GameCursor;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.ui.PictureTextureCache;
import org.fogofwarlabs.sc4j.scr.client.ui.PlayerColorUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.WebImageUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.command.ButtonDefinition;
import org.fogofwarlabs.sc4j.scr.client.ui.command.CommandCard;
import org.fogofwarlabs.sc4j.scr.client.ui.command.HotkeyPress;
import org.fogofwarlabs.sc4j.scr.client.ui.command.InterfaceSelection;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.*;
import org.fogofwarlabs.sc4j.scr.client.ui.input.HotkeyProfile;
import org.fogofwarlabs.sc4j.scr.client.ui.input.WorldInterface;
import org.fogofwarlabs.sc4j.scr.client.ui.menu.AllianceDialog;
import org.fogofwarlabs.sc4j.scr.client.ui.menu.MessagingDialog;
import org.fogofwarlabs.sc4j.scr.client.ui.menu.VisionDialog;
import org.fogofwarlabs.sc4j.scr.client.ui.minimap.MinimapImage;
import org.fogofwarlabs.sc4j.scr.client.ui.minimap.RememberedBuildings;
import org.fogofwarlabs.sc4j.scr.client.ui.observer.*;
import org.fogofwarlabs.sc4j.scr.client.ui.portrait.PortraitPlayer;
import org.fogofwarlabs.sc4j.scr.client.ui.portrait.PortraitStatic;
import org.fogofwarlabs.sc4j.scr.client.ui.status.*;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.client.ui.text.StatText;
import org.fogofwarlabs.sc4j.scr.client.world.ClientCamera;
import org.fogofwarlabs.sc4j.scr.client.world.GameViewport;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.client.world.ScrPresentation;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.casc.AssetResolver;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.format.text.LegacyTextUtils;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogImportUtils;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsoleArt;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsolePlacement;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceTimers;
import org.fogofwarlabs.sc4j.scr.game.presentation.LocalDisplay;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.presentation.TimerTextUtils;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.render.ui.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * Draws console and replay panes from the console model, game state and installed interface assets.
 * The view owns its cached textures and interface timers; fonts, renderer, cursor and storage are supplied by the client.
 * Loaded control identifiers select status values, wireframes, production icons, tooltips and dialog state.
 */
public final class ConsoleView implements AutoCloseable, LocalDisplay.ConsoleRectangles {
    /**
     * The {@code tblink} row {@code 16}, red: the minimap ping of attack alerts.
     */
    public static final int ATTACK_ALERT_PING_COLOR = 0x10;
    /**
     * The {@code tblink} row {@code 17}, green: the minimap ping of unit alerts, transmissions, trigger pings and the local player's own pings.
     */
    public static final int ALERT_PING_COLOR = 0x11;
    /**
     * The {@code tblink} row {@code 18}, yellow: the minimap ping of allies.
     */
    public static final int ALLY_PING_COLOR = 0x12;
    /**
     * The index of the pointer's column relative to the minimap bitmap in the result of {@link #getMinimapGeometry}.
     */
    public static final int MINIMAP_LOCAL_X_INDEX = 0;
    /**
     * The index of the pointer's row relative to the minimap bitmap in the result of {@link #getMinimapGeometry}.
     */
    public static final int MINIMAP_LOCAL_Y_INDEX = 1;
    /**
     * The index of the minimap bitmap width in the result of {@link #getMinimapGeometry}.
     */
    public static final int MINIMAP_WIDTH_INDEX = 2;
    /**
     * The index of the minimap bitmap height in the result of {@link #getMinimapGeometry}.
     */
    public static final int MINIMAP_HEIGHT_INDEX = 3;
    /**
     * The index of the minimap's view-frame divisor in the result of {@link #getMinimapGeometry}.
     */
    public static final int MINIMAP_VIEW_SCALE_INDEX = 4;
    /**
     * The index of the minimap's zoom shift in the result of {@link #getMinimapGeometry}.
     */
    public static final int MINIMAP_VIEW_SHIFT_INDEX = 5;
    /**
     * The index of the map pixels per minimap pixel in the result of {@link #getMinimapGeometry}.
     */
    public static final int MINIMAP_PIXEL_SCALE_INDEX = 6;
    /**
     * The index of the left edge in a rectangle of {@link #findDialogRectangle} and {@link #findControlRectangle}.
     */
    public static final int RECTANGLE_LEFT_INDEX = 0;
    /**
     * The index of the top edge in a rectangle of {@link #findDialogRectangle} and {@link #findControlRectangle}.
     */
    public static final int RECTANGLE_TOP_INDEX = 1;
    /**
     * The index of the width in a rectangle of {@link #findDialogRectangle} and {@link #findControlRectangle}.
     */
    public static final int RECTANGLE_WIDTH_INDEX = 2;
    /**
     * The index of the height in a rectangle of {@link #findDialogRectangle} and {@link #findControlRectangle}.
     */
    public static final int RECTANGLE_HEIGHT_INDEX = 3;
    /**
     * The index of the minimap bitmap's left screen column in the result of {@link #placeMinimap}.
     */
    public static final int MINIMAP_SCREEN_LEFT_INDEX = 0;
    /**
     * The index of the minimap bitmap's top screen row in the result of {@link #placeMinimap}.
     */
    public static final int MINIMAP_SCREEN_TOP_INDEX = 1;
    /**
     * The index of the screen pixels per map pixel in the result of {@link #placeMinimap}.
     */
    public static final int MINIMAP_SCREEN_SCALE_INDEX = 2;
    /**
     * The number of values {@link #placeMinimap} fills.
     */
    public static final int MINIMAP_PLACEMENT_VALUES = 3;

    /**
     * The console dialogs a placement moves, in the order of {@link ConsoleLayout#createPlacedLayout}.
     */
    private static final ScrConsolePlacement.Dialog @NotNull [] PLACED_DIALOGS = {
            ScrConsolePlacement.Dialog.COMMAND_CARD, ScrConsolePlacement.Dialog.SELECTION, ScrConsolePlacement.Dialog.RESOURCES,
            ScrConsolePlacement.Dialog.PORTRAIT, ScrConsolePlacement.Dialog.MINIMAP, ScrConsolePlacement.Dialog.MENU
    };
    /**
     * The bytes of an RGBA pixel and the index of its alpha byte.
     */
    private static final int RGBA_BYTES = 4;
    private static final int RGBA_ALPHA = 3;
    private static final int CHAT_ALL_LABEL_CONTROL = 3;
    private static final int CHAT_ALLIES_LABEL_CONTROL = 2;
    private static final int CHAT_OBSERVERS_LABEL_CONTROL = 4;

    private static final int BLACK_RGBA = 0x000000FF;
    private static final int COLOR_CHANNEL_MASK = 0xFF;
    private static final int PACKED_HIGH_BYTE_SHIFT = 3 * Byte.SIZE;
    private static final int STATISTICS_ROW_GAP = 2;
    private static final int PRODUCTION_STRIP_INSET = 2;
    private static final int PRODUCTION_ITEM_GAP = 2;
    private static final int PRODUCTION_PLAYER_WIDTH = 2;
    private static final int PRODUCTION_PROGRESS_HEIGHT = 2;
    private static final int WIDE_DIGIT_NUDGE = 3;
    private static final int DEFAULT_DIGIT_NUDGE = 2;
    private static final int SIDEBAR_LEFT_INDEX = 0;
    private static final int SIDEBAR_TOP_INDEX = 1;
    private static final int SIDEBAR_SCALE_INDEX = 2;
    private static final int HERO_WIREFRAME_FRAME = 0x13;
    private static final int HALLUCINATION_WIREFRAME_FRAME = 0x1F;
    private static final int PARASITE_WIREFRAME_FRAME = 0x19;
    private static final int NORMAL_WIREFRAME_FRAME = 0x0D;
    private static final float WIREFRAME_COLOR_FACTOR = 0.8f;
    private static final int REPLAY_FAST_ICON = 388;
    private static final int REPLAY_SLOW_ICON = 389;
    private static final int REPLAY_PLAY_ICON = 386;
    private static final int REPLAY_PAUSE_ICON = 387;
    private static final int REPLAY_FAST_SHIFT_X = -4;
    private static final int REPLAY_SLOW_SHIFT_X = -3;
    private static final int REPLAY_PLAY_PAUSE_SHIFT_X = -2;
    private static final int REPLAY_ICON_SHIFT_Y = 3;
    private static final int REPLAY_PROGRESS_CONTROL = 10;
    /**
     * The frame offsets of a console button from its base frame: normal and pressed.
     */
    private static final int NORMAL_BUTTON_OFFSET = 1;
    private static final int PRESSED_BUTTON_OFFSET = 2;
    /**
     * The HD shift of the minimap buttons: one column left and four rows down.
     */
    private static final int MINIMAP_BUTTON_HD_SHIFT_X = 1;
    private static final int MINIMAP_BUTTON_HD_SHIFT_Y = 4;
    private static final int REPLAY_TERRAIN_BUTTON_BASE = 140;
    private static final int TERRAIN_BUTTON_BASE = 3;
    /**
     * The race index of the Zerg console, whose menu button is shifted.
     */
    private static final int ZERG_RACE = 0;
    private static final int MENU_SHIFT_X = 2;
    private static final int MENU_HD_SHIFT_X = -2;
    private static final int ALLIANCE_TITLE = 27;
    private static final int VISION_HEADER_LEFT = 28;
    private static final int VISION_HEADER_RIGHT = 29;
    private static final int REPLAY_PORTRAIT_SHIFT_X = -2;
    private static final int LIVE_PORTRAIT_SHIFT_X = -4;
    private static final int REPLAY_PORTRAIT_SHIFT_Y = -5;
    private static final int LIVE_PORTRAIT_SHIFT_Y = 2;
    private static final int LINE_FEED_CODE = 0x0A;
    private static final int MINIMAP_RIGHT_BORDER_GROWTH = 2;
    private static final int MINIMAP_BOTTOM_BORDER_GROWTH = 2;
    private static final float STATISTICS_ROW_FONT_HEIGHT = 1.2f;
    private static final float SHADOW_OFFSET_FACTOR = 0.75f;

    private static final int DEFAULT_RECORDED_SPEED = 6;
    private static final int DEFAULT_PLAYBACK_SPEED = 4;
    private static final int MINIMAP_BITMAP_SIDE = 128;
    private static final int PING_ORIGIN_OFFSET = MINIMAP_BITMAP_SIDE / 2;
    /**
     * The number of minimap ping slots.
     */
    private static final int MINIMAP_PING_SLOTS = 16;
    /**
     * The frames of {@code game\blink.dds.grp}; the last frame index is {@code 19} ({@code 0x13}).
     */
    private static final int MINIMAP_PING_FRAMES = 20;
    /**
     * Timer {@code 6} of the minimap dialog: the ping frame period in milliseconds.
     */
    private static final int MINIMAP_PING_INTERVAL = 100;
    /**
     * The mask of the byte-sized colour of a ping.
     */
    private static final int PING_COLOR_MASK = 0xFF;
    private static final int MINIMAP_COLOR_MODES = 3;
    private static final int TEAM_COLOR_MODE = 2;
    private static final int NO_PORTRAIT = 0xFFFF;
    private static final int MESSAGE_WIDTH_MARGIN = 2 * MessageAreaUtils.MARGIN;
    private static final int MAIN_WIREFRAME_SIZE = 64;
    private static final int GROUP_WIREFRAME_SIZE = 32;
    private static final int LARGE_CARGO_SPACE = 4;
    private static final int MEDIUM_CARGO_SPACE = 2;
    private static final int PROGRESS_QUEUE_CONTROL = 7;
    private static final int PROGRESS_CONSTRUCTION_CONTROL = 13;
    private static final int PROGRESS_RESEARCH_CONTROL = 14;
    private static final int PROGRESS_EGG_CONTROL = 16;
    private static final int SWATCH_SIZE = 12;
    private static final int SWATCH_CENTER_HEIGHT = 11;
    private static final int PLAYER_LABEL_GAP = 9;
    private static final int FIRST_PLAYER_LABEL = -10;
    private static final int STATISTICS_FONT_SIZE = 10;
    private static final int STATISTICS_ICON_SIZE = 9;
    private static final int STATISTICS_RESOURCE_WIDTH = 60;
    private static final int STATISTICS_COUNT_WIDTH = 40;
    private static final int STATISTICS_SUPPLY_WIDTH = 70;
    private static final int STATISTICS_VALUE_GAP = 5;
    /**
     * The statistics row items from the right edge leftwards, the indexes of a row's item lefts; the items before the name
     * also index {@link #STATISTICS_COLUMN_WIDTHS}.
     */
    private static final int STATISTICS_APM_ITEM = 0;
    private static final int STATISTICS_SUPPLY_ITEM = 1;
    private static final int STATISTICS_WORKERS_ITEM = 2;
    private static final int STATISTICS_GAS_ITEM = 3;
    private static final int STATISTICS_MINERALS_ITEM = 4;
    private static final int STATISTICS_NAME_ITEM = 5;
    private static final int STATISTICS_ITEM_COUNT = 6;
    /**
     * The column widths of the statistics row items before the name, from the right edge leftwards.
     */
    private static final int @NotNull [] STATISTICS_COLUMN_WIDTHS = {STATISTICS_COUNT_WIDTH, STATISTICS_SUPPLY_WIDTH, STATISTICS_COUNT_WIDTH,
                                                                     STATISTICS_RESOURCE_WIDTH, STATISTICS_RESOURCE_WIDTH};
    /**
     * The text between the used and the available supply of a statistics row.
     */
    private static final @NotNull String STATISTICS_SUPPLY_SEPARATOR = " / ";
    private static final int STATISTICS_LEFT_BORDER = 12;
    private static final int STATISTICS_INSET = 4;
    private static final int STATISTICS_MINIMUM_HEIGHT = 28;
    private static final int STATISTICS_EDGE_HEIGHT = 6;
    private static final int STATISTICS_CORNER_HEIGHT = 20;
    private static final int PRODUCTION_STRIP_HEIGHT = 26;
    private static final int PRODUCTION_ITEM_EXTENT = 18;
    private static final int PRODUCTION_ICON_SIZE = 16;
    private static final int PRODUCTION_BORDER_WIDTH = 4;
    private static final int PRODUCTION_PLAYER_MARGIN = 3;
    private static final int PRODUCTION_PLAYER_HEIGHT = 20;
    private static final int PRODUCTION_COUNT_FONT = 7;
    private static final int OVER_SUPPLY_COLOR = 0xFF0000FF;
    private static final int PRODUCTION_PROGRESS_COLOR = 0x7AF550FF;
    private static final int UNRESOLVED_BUTTON_COLOR = 0xFF40FFFF;

    /**
     * {@code UnitPortraits} {@code 0}: no portrait.
     */
    private static final int PORTRAITS_NONE = 0;
    /**
     * {@code UnitPortraits} {@code 1}: a still portrait.
     */
    private static final int PORTRAITS_STILL = 1;
    /**
     * {@code UnitPortraits} {@code 2}: animated portraits.
     */
    private static final int PORTRAITS_ANIMATED = 2;

    private static final float HALF_TEXEL = 0.5f;
    /**
     * Half of a length, as a factor.
     */
    private static final float HALF = 0.5f;
    /**
     * The command button sheet of the race's console folder: the box, queue and stat icon frames.
     */
    private static final @NotNull String COMMAND_BUTTON_SHEET = "cmdbtns.dds.grp";
    /**
     * The dialog sheet of the race's console folder: the console buttons, popup frames and check boxes.
     */
    private static final @NotNull String DIALOG_SHEET = "dlg.dds.grp";
    /**
     * The main wireframe control of the {@code statdata} dialog.
     */
    private static final int MAIN_WIREFRAME_CONTROL = 1;
    /**
     * The portrait control of the {@code statport} dialog.
     */
    private static final int PORTRAIT_CONTROL = 1;
    /**
     * The gap between a cost icon and its number (icon width + {@code 2}).
     */
    private static final int COST_NUMBER_GAP = 2;
    /**
     * The command tooltip stays inside the interface width minus {@code 3}.
     */
    private static final int COMMAND_TOOLTIP_MARGIN = 3;
    private static final int FIRST_QUEUE_SLOT = 2;
    private static final int LAST_QUEUE_SLOT = 6;
    private static final int FIRST_STAT_ICON = 9;
    private static final int LAST_STAT_ICON = 12;
    private static final int RESEARCH_SLOT = 15;
    private static final int CHILD_SLOT = 17;
    private static final int FIRST_CARGO_SLOT = 18;
    private static final int LAST_CARGO_SLOT = 32;
    private static final int FIRST_GROUP_SLOT = 33;
    private static final int LAST_GROUP_SLOT = 44;
    private static final int TERRAIN_BUTTON = 2;
    /**
     * The replay minimap button "View Players" (minimap control {@code 4}), Diplomacy in a network game.
     */
    private static final int VISION_BUTTON = 4;
    /**
     * The minimap button Message of a network game (control {@code 3}).
     */
    private static final int MESSAGE_BUTTON = 3;
    /**
     * The style bases of the minimap buttons (table): Diplomacy {@code 83} (handshake), Message {@code 130} (waves).
     */
    private static final int DIPLOMACY_BUTTON_BASE = 83;
    private static final int MESSAGE_BUTTON_BASE = 130;
    /**
     * {@code stat_txt} {@code 812} "Diplomacy" and {@code 813} "Messaging", the tooltips of buttons {@code 4} and {@code 3}.
     */
    private static final int DIPLOMACY_TOOLTIP = 812;
    private static final int MESSAGING_TOOLTIP = 813;

    /**
     * {@code stat_txt} {@code 808} "Unload Unit (%s)".
     */
    private static final int UNLOAD_TOOLTIP = 808;
    /**
     * {@code stat_txt} {@code 809}, the group grid help.
     */
    private static final int GROUP_TOOLTIP = 809;
    /**
     * {@code stat_txt} {@code 810} "Show Terrain in Minimap (Tab)", shown while the terrain is hidden.
     */
    private static final int SHOW_TERRAIN_TOOLTIP = 810;
    /**
     * {@code stat_txt} {@code 811} "Hide Terrain in Minimap (Tab)", shown while the terrain is shown.
     */
    private static final int HIDE_TERRAIN_TOOLTIP = 811;
    /**
     * {@code stat_txt} {@code 814} "Game Menu (F10)".
     */
    private static final int MENU_TOOLTIP = 814;
    /**
     * The menu button of the {@code stat_f10} dialog.
     */
    private static final int MENU_BUTTON = 1;
    /**
     * The title text control of the {@code msgfltr} dialog.
     */
    private static final int MESSAGING_TITLE = -1;

    /**
     * The centered status text control of the text-box dialog (CTEXT id {@code 6}).
     */
    private static final int MODE_PROMPT_CONTROL = 6;
    /**
     * The console folder of each race ({@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss).
     */
    private static final @NotNull String @NotNull [] RACE_FOLDERS = {"zerg", "terran", "protoss"};
    /**
     * The HD console piece names by {@link ConsoleArt} piece index.
     */
    private static final @NotNull String @NotNull [] HD_CONSOLE_PIECES = {"left", "center", "right"};
    /**
     * The art files of the HD console pieces by {@link ConsoleArt} piece index.
     */
    private static final @NotNull ScrConsoleArt @NotNull [] HD_CONSOLE_ARTS = {
            ScrConsoleArt.CONSOLE_LEFT, ScrConsoleArt.CONSOLE_CENTER, ScrConsoleArt.CONSOLE_RIGHT
    };
    /**
     * The chat label of the text-box dialog in single player ({@code ?textbox-1}, "Message:").
     */
    private static final int CHAT_LABEL_CONTROL = 1;
    /**
     * The recipient label and upper end of the text-box dialog's contiguous label control range.
     */
    private static final int CHAT_RECIPIENT_LABEL_CONTROL = 5;
    /**
     * The text-box dialog's format of the chosen recipient's label (control {@code -10}, {@code ?textbox-5}, "To %s:").
     */
    private static final int CHAT_RECIPIENT_FORMAT_CONTROL = -10;
    /**
     * The chat edit control of the text-box dialog.
     */
    private static final int CHAT_EDIT_CONTROL = 7;
    /**
     * The TrueType caller shift of the stat level text ({@code origin.x - 3}).
     */
    private static final int STAT_TRUE_TYPE_SHIFT_X = -3;
    /**
     * The bitmap caller shift of the stat level text ({@code origin.y + 1}).
     */
    private static final int STAT_BITMAP_SHIFT_Y = 1;
    /**
     * The TrueType caller shift of the queue number ({@code origin.x - 4}).
     */
    private static final int QUEUE_TRUE_TYPE_SHIFT_X = -4;
    /**
     * The TrueType caller shift of the queue number ({@code origin.y - 3}).
     */
    private static final int QUEUE_TRUE_TYPE_SHIFT_Y = -3;

    private static final int WHITE_RGBA = 0xFFFFFFFF;
    /**
     * Minimap timer {@code 1}: the full refresh every {@code 1000} ms, every {@code 2000} ms in a multiplayer game (byte), where each
     * network turn also refreshes it.
     */
    private static final int MINIMAP_REFRESH_TIMER = 1;
    private static final int MINIMAP_REFRESH_INTERVAL = 1000;
    private static final int MINIMAP_MULTIPLAYER_REFRESH_INTERVAL = 2000;
    /**
     * Minimap timer {@code 3}: the {@code 200} ms blink phase.
     */
    private static final int MINIMAP_BLINK_TIMER = 3;
    private static final int MINIMAP_BLINK_INTERVAL = 200;
    /**
     * Minimap timer {@code 6}: the ping frames.
     */
    private static final int MINIMAP_PING_TIMER = 6;
    /**
     * The ping graphic {@code game\blink.grp}, loaded as the {@code .dds.grp} of the asset set.
     */
    private static final @NotNull String BLINK_SHEET = "game/blink.dds.grp";

    private static final @NotNull String RESOURCE_SHEET = "game/icons.dds.grp";
    /**
     * The number of palette colors of the resource icons a plugin draws.
     */
    private static final int RESOURCE_ICON_COLORS = 256;
    /**
     * The alpha of a shown pixel of a resource icon a plugin draws.
     */
    private static final int RESOURCE_ICON_ALPHA = 0xFF000000;

    /**
     * The leaderboard refresh timer identifier.
     */
    private static final int LEADERBOARD_TIMER = 1;
    /**
     * Left-aligned, vertically centered leaderboard text.
     */
    private static final int LEADERBOARD_TEXT_FORMAT = InterfaceText.LEFT | InterfaceText.MIDDLE;
    /**
     * The selected leaderboard text color code.
     */
    private static final int LEADERBOARD_TEXT_COLOR_CODE = 7;
    /**
     * The gap after the first value before the leaderboard label.
     */
    private static final int LEADERBOARD_LABEL_GAP = 4;
    /**
     * The first frame of the popup button style {@code 4} in the race's dialog sheet ({@link #DIALOG_SHEET}; disabled {@code 112..114}).
     */
    private static final int POPUP_BUTTON_BASE = 112;
    /**
     * The {@code statdata} icon buttons of the control procedure.
     */
    private static final int @NotNull [] STATUS_ICON_BUTTONS = {2, 3, 4, 5, 6, 9, 10, 11, 12, 15, 17};
    /**
     * The placed dialogs kept before the cache is cleared.
     */
    private static final int PLACED_DIALOG_LIMIT = 64;
    /**
     * The image cache key prefix of the console's own web images.
     */
    private static final @NotNull String WEB_IMAGE_KEY = "web#";
    /**
     * The resource counter timer identifier.
     */
    private static final int RESOURCE_TIMER = 1;
    /**
     * The paused replay-text blink interval in milliseconds.
     */
    private static final long PAUSED_BLINK_INTERVAL = 600;
    private static final @NotNull String PORTRAIT_FRAME_KEY = "portrait#";
    private static final @NotNull String PORTRAIT_STATIC_KEY = "portrait-static";
    /**
     * The portrait frames kept before the cache is cut back.
     */
    private static final int PORTRAIT_FRAME_LIMIT = 256;
    /**
     * CTEXT: format {@code 0x92} set by the CTEXT procedure (centered, top, no baseline tweak).
     */
    private static final int CTEXT_FORMAT = InterfaceText.CENTER | InterfaceText.TOP | InterfaceText.NO_BASELINE_TWEAK;
    /**
     * LTEXT: format {@code 0x91} set by the LTEXT procedure (left, top, no baseline tweak).
     */
    private static final int LTEXT_FORMAT = InterfaceText.LEFT | InterfaceText.TOP | InterfaceText.NO_BASELINE_TWEAK;
    /**
     * The console menu button text: format {@code 0xa2}, centered both ways without the tweak.
     */
    private static final int MENU_BUTTON_FORMAT = InterfaceText.CENTER | InterfaceText.MIDDLE | InterfaceText.NO_BASELINE_TWEAK;

    /**
     * The {@code allyfltr} controls: the first vision box ({@code 13..24}), "Reveal Entire Map" ({@code 25}), the title ({@code 26}),
     * OK ({@code -2}) and Cancel ({@code -3}).
     */
    private static final int VISION_FIRST_BOX = 13;
    private static final int VISION_REVEAL_BOX = 25;
    private static final int VISION_TITLE = 26;
    /**
     * The vision column header of {@code allyfltr} ("Shared" / "Vision", RTEXT {@code 28} and {@code 29}).
     */
    private static final int @NotNull [] VISION_HEADERS = {VISION_HEADER_LEFT, VISION_HEADER_RIGHT};
    private static final int VISION_OK = -2;
    private static final int VISION_CANCEL = -3;
    /**
     * The {@code 3}-slice style bases of OK (style {@code 3}, {@code 103..111}) and Cancel (style {@code 5}, {@code 121..129}).
     */
    private static final int VISION_OK_BASE = 103;
    private static final int VISION_CANCEL_BASE = 121;
    /**
     * The vision rows of {@code allyfltr}: boxes {@code 13..21} and labels {@code -10..-18}.
     */
    private static final int VISION_ROWS = 9;
    /**
     * The light minimap border palette target.
     */
    private static final int MINIMAP_BORDER_LIGHT = 0xD0D0D0;
    private static final int MINIMAP_BORDER_DARK = 0xA0A0A0;
    /**
     * The HD video and static offset below the portrait control top ({@code 5} pixels).
     */
    private static final int PORTRAIT_HD_VIDEO_Y = 5;
    /**
     * The gap between a resource icon and its number: the text starts {@code 2} pixels past the icon frame.
     */
    private static final int RESOURCE_TEXT_GAP = 2;
    /**
     * The pixel a resource number needs past its measured width, because the clip right of its rectangle is inclusive.
     */
    private static final int RESOURCE_TEXT_CLIP_PIXEL = 1;
    /**
     * The resource number format {@code 0xA1} (slot): left, vertically centered.
     */
    private static final int RESOURCE_TEXT_FORMAT = InterfaceText.LEFT | InterfaceText.MIDDLE | InterfaceText.NO_BASELINE_TWEAK;
    /**
     * The default color code of the resource numbers.
     */
    private static final int RESOURCE_TEXT_COLOR_CODE = 7;
    /**
     * The color code that the font selection sets.
     */
    private static final int SELECTED_FONT_COLOR_CODE = 2;
    /**
     * The {@code statres} countdown control.
     */
    private static final int COUNTDOWN_CONTROL = -10;
    /**
     * The game time plate of the console folder (console art index {@code 3}).
     */
    private static final @NotNull String TIME_PLATE = "console_TIME.DDS";
    /**
     * The APM plate of the console folder (console).
     */
    private static final @NotNull String APM_PLATE = "console_APM.DDS";

    /**
     * The normal enabled command-icon RGB tint.
     */
    private static final float @NotNull [] TINT_NORMAL = {1.96f, 1.96f, 0.43f};
    private static final float @NotNull [] TINT_HIGHLIGHT = {1.45f, 1.45f, 1.45f};
    private static final float @NotNull [] TINT_DISABLED = {1f, 1f, 1f};
    private static final @NotNull String ICON_SHEET = "unit/cmdicons/cmdicons.dds.grp";
    private static final @NotNull String WIREFRAME_SHEET = "unit/wirefram/wirefram.dds.grp";
    private static final @NotNull String GROUP_SHEET = "unit/wirefram/grpwire.dds.grp";
    private static final @NotNull String TRANSPORT_SHEET = "unit/wirefram/tranwire.dds.grp";

    /**
     * The client's interface data per tileset ({@code ClientContext#getTilesetAssets}).
     */
    @FunctionalInterface
    public interface TilesetSource {
        /**
         * @param tileset the tileset index
         * @return the tileset's interface data
         * @throws IOException if the tileset cannot be read
         */
        @NotNull InterfaceAssets getTilesetAssets(int tileset) throws IOException;
    }

    /**
     * Tests whether a minimap geometry places the pointer inside the minimap bitmap.
     *
     * @param minimap the minimap geometry of {@link #getMinimapGeometry}, or {@code null} when the console shows no minimap
     * @return {@code true} if the local pointer position lies inside the minimap bitmap, {@code false} otherwise
     */
    public static boolean isInsideMinimap(int @Nullable [] minimap) {
        return minimap != null &&
               minimap[MINIMAP_LOCAL_X_INDEX] >= 0 && minimap[MINIMAP_LOCAL_Y_INDEX] >= 0 &&
               minimap[MINIMAP_LOCAL_X_INDEX] < minimap[MINIMAP_WIDTH_INDEX] &&
               minimap[MINIMAP_LOCAL_Y_INDEX] < minimap[MINIMAP_HEIGHT_INDEX];
    }

    /**
     * Returns the classic cursor name of a cursor state.
     *
     * @param cursor          the cursor state
     * @param scrollDirection the edge scroll direction for {@link WorldInterface.Cursor#SCROLL}
     * @return the name under {@code cursor/}
     */
    static @NotNull String getCursorName(@NotNull WorldInterface.Cursor cursor, int scrollDirection) {
        return switch (cursor) {
            case ARROW -> "arrow";
            case MAGNIFIER_OWN -> "MagG";
            case MAGNIFIER_NEUTRAL -> "MagY";
            case MAGNIFIER_ENEMY -> "MagR";
            case TARGET_NONE -> "TargN";
            case TARGET_OWN -> "TargG";
            case TARGET_NEUTRAL -> "TargY";
            case TARGET_ENEMY -> "TargR";
            case DRAG -> "Drag";
            case SCROLL -> GameCursor.SCROLL[Math.floorMod(scrollDirection, GameCursor.SCROLL.length)];
        };
    }

    /**
     * @param loaded the interface assets
     * @return {@code true} if the classic (SD) asset set is loaded, {@code false} otherwise
     */
    private static boolean isStandardDefinition(@NotNull InterfaceAssets loaded) {
        return !loaded.getAssetSet().isHd();
    }

    /**
     * @param hdScale {@code 0} for the SD console, {@link HdConsoleLayout#HD2_SCALE} for HD2, otherwise HD
     * @return the asset set of that console
     */
    private static AssetResolver.@NotNull AssetSet getAssetSet(int hdScale) {
        return hdScale == 0
               ? AssetResolver.AssetSet.SD
               : hdScale == HdConsoleLayout.HD2_SCALE ? AssetResolver.AssetSet.HD2 : AssetResolver.AssetSet.HD;
    }

    /**
     * Selects the digit nudge for exact two-character text matches, with larger values in windowed or HD2 mode.
     *
     * @param value         the two-character level text
     * @param windowedOrHd2 whether the console is windowed or uses the HD2 asset set
     * @return the digit nudge in pixels
     */
    private static int getStatLevelNudge(@NotNull String value, boolean windowedOrHd2) {
        return switch (value) {
            case "3 ", "5 " -> windowedOrHd2 ? WIDE_DIGIT_NUDGE : 1;
            case "0 " -> windowedOrHd2 ? WIDE_DIGIT_NUDGE : 0;
            case "4 " -> windowedOrHd2 ? DEFAULT_DIGIT_NUDGE : 1;
            default -> windowedOrHd2 ? DEFAULT_DIGIT_NUDGE : 0;
        };
    }

    /**
     * Places the minimap bitmap in its control: the control rectangle shrinks by {@code (128 - size) / 2} per side (truncating), and the
     * bitmap is drawn at its origin, scaled by the minimap scale from the control's top left.
     *
     * @param area         the minimap area control
     * @param image        the minimap bitmap
     * @param minimapScale the interface pixels per bitmap pixel ({@link #getMinimapScale()})
     * @return the bitmap's left in interface pixels
     */
    private static float getMinimapLeft(DialogLayout.@NotNull Control area, @NotNull MinimapImage image, float minimapScale) {
        return area.getX() + (MINIMAP_BITMAP_SIDE - image.getWidth()) / 2 * minimapScale;
    }

    /**
     * Places the minimap bitmap in its control, as {@link #getMinimapLeft}.
     *
     * @param area         the minimap area control
     * @param image        the minimap bitmap
     * @param minimapScale the interface pixels per bitmap pixel ({@link #getMinimapScale()})
     * @return the bitmap's top in interface pixels
     */
    private static float getMinimapTop(DialogLayout.@NotNull Control area, @NotNull MinimapImage image, float minimapScale) {
        return area.getY() + (MINIMAP_BITMAP_SIDE - image.getHeight()) / 2 * minimapScale;
    }

    /**
     * Shows or hides a control the console dialog data may lack, as the native procedures write controls they name by id.
     *
     * @param control the control, or {@code null} if the dialog has none with the id
     * @param visible whether the control is drawn and takes input
     */
    private static void setVisible(@Nullable ScrControl control, boolean visible) {
        if (control != null) {
            control.setVisible(visible);
        }
    }

    /**
     * Sets the state of a check box row of an alliance popup the dialog data may lack.
     *
     * @param control the check box, or {@code null} if the dialog has none with the id
     * @param visible whether the check box is drawn and takes input
     * @param enabled whether it takes input
     * @param checked whether it is checked
     */
    private static void setCheckBox(@Nullable ScrControl control, boolean visible, boolean enabled, boolean checked) {
        if (control != null) {
            control.setVisible(visible);
            control.setEnabled(enabled);
            control.setChecked(checked);
        }
    }


    private @Nullable InterfaceAssets assets;
    private @Nullable ConsoleLayout layout;
    private @Nullable DialogLayout leaderboardLayout;
    private @Nullable Boolean leaderboardCreated;
    private int @Nullable [] portraitNoiseColors;
    private @Nullable PortraitPlayer portraitPlayer;
    /**
     * The clock a still portrait holds: the time its unit or portrait was first shown.
     */
    private long portraitStillClock;
    private @Nullable Object portraitStillUnit;
    private @Nullable GluText controlText;
    private @Nullable GluText networkText;
    private @Nullable GluText gluText;
    private @Nullable ScrDialog visionPopup;
    /**
     * The list control of the open popup (the message history's), drawn by {@link #drawPopup}, or {@code null}.
     */
    private @Nullable ListBox popupList;
    private @Nullable ScrDialog alliancePopup;
    private @Nullable ScrDialog messagingPopup;
    private @Nullable ComboBox messagingList;
    /**
     * Whether the minimap shows Diplomacy and Message instead of the terrain button ({@link #setMultiplayerButtons}).
     */
    private boolean multiplayerButtons;
    private @Nullable ConsoleLayout sdLayout;
    private float layoutWidth;
    private int hdScale;
    private boolean showTimer;
    /**
     * The options' Display APM In Game in a live game ({@code apm_Showing}), the shown APM and its color code.
     */
    private boolean showApm;
    private int apmValue;
    private int apmColorCode;
    private @Nullable String apmPlateKey;
    private boolean apmPlatePresent;
    /**
     * The quality prefix and path of the last tested game time plate, and whether it exists.
     */
    private @Nullable String timePlateKey;
    private boolean timePlatePresent;
    /**
     * The decoded console art of the laid out race and quality, whose alpha is the hit mask, or {@code null} while no art is loaded.
     */
    private @Nullable ConsoleArt consoleArt;
    private boolean layoutReplay;
    private @Nullable ReplayPanel replayPanel;
    private long replayFrame;
    private long replayEndFrame;
    private boolean replayPaused;
    private int consoleTexture;
    /**
     * The console placement of the game's plugins ({@link #setPlacement}), or {@code null} for the installed console.
     */
    private @Nullable ScrConsolePlacement placement;
    /**
     * The HD layout of the interface width, before the placement moves its dialogs.
     */
    private @Nullable ConsoleLayout hdLayout;
    /**
     * The layout with the placement's dialog and control moves, the layout it was moved from, the placement and its change count.
     */
    private @Nullable ConsoleLayout placedLayout;
    private @Nullable ConsoleLayout placedBase;
    private @Nullable ScrConsolePlacement placedFrom;
    private long placedChange;
    /**
     * The text box placed by the placement, the text box it was placed from, the interface width and the placement change count.
     */
    private @Nullable DialogLayout placedTextBox;
    private @Nullable DialogLayout placedTextBoxBase;
    private float placedTextBoxWidth;
    private long placedTextBoxChange;
    /**
     * The layer that draws the console dialogs through the placement's mask, and what begins the drawing into the target again
     * ({@link #setTargetResumer}).
     */
    private final @NotNull ConsoleMaskLayer maskLayer;
    private @Nullable Runnable targetResumer;
    /**
     * The standard-definition console art drawn through the placement's mask, its race, its texture and the placement change count of its texels.
     */
    private @Nullable InterfaceImage maskedArt;
    private int maskedArtRace;
    private int maskedArtTexture;
    private long maskedArtChange;
    /**
     * The resource icons a plugin draws instead of the installed ones ({@link #setResourceIcons}), the palette of their textures and the textures
     * uploaded so far, one per frame.
     */
    private @Nullable SpriteSheet resourceIcons;
    private final int @NotNull [] resourceIconPalette;
    private @Nullable Texture @NotNull [] resourceIconTextures;
    /**
     * The game view whose plugins give the pictures of the console art and the resource icons, and the textures of those pictures.
     */
    private @Nullable ScrGameView pictureView;
    private final @NotNull PictureTextureCache pictureTextures;
    /**
     * The renderer's layer textures: terrain with creep, fog (one texel per tile) and dots.
     */
    private int minimapTerrainTexture;
    private int minimapFogTexture;
    private int minimapDotTexture;
    private int minimapCanvasWidth;
    private int minimapCanvasHeight;
    /**
     * Whether the game is a multiplayer game (byte, {@link #setMultiplayerGame}).
     */
    private boolean multiplayerGame;
    private int minimapWidth;
    private int minimapHeight;
    private boolean minimapRefresh;
    /**
     * Whether the terrain texture missed an upload while the terrain was hidden.
     */
    private boolean minimapTerrainStale;
    private boolean minimapBlinked;
    private @Nullable MinimapImage minimapImage;
    private @Nullable ScrGameView minimapState;
    private @Nullable ConsoleLayout observerLayout;
    private int observerWidth;
    private int minimapColorMode;
    private @Nullable String skin;
    private @Nullable DialogLayout chatLayout;
    private @Nullable ConsoleDialog textBox;
    private @Nullable MessageLines messageLines;
    private boolean minimapBlinkPhase;
    private long transmissionEnd;
    private boolean transmissionStart;
    private boolean originalAspectRatio;
    private @Nullable ObserverProduction observerProduction;
    /**
     * Whether the side bar's tooltips are hidden ({@link #setSideBarTooltipsHidden}).
     */
    private boolean sideBarTooltipsHidden;
    private boolean pausedBlink;
    /**
     * Whether the last drawn frame showed a portrait (the portrait timers act only then).
     */
    private boolean portraitShown;
    /**
     * The chat edit text whose caret timer is armed.
     */
    private @Nullable EditText chatEdit;
    private long pausedBlinkTime;

    private final @NotNull ResourceBar resourceBar;
    /**
     * The row layout of the resource counters and of the observer statistics rows.
     */
    private final @NotNull ResourceRowLayout resourceLayout;
    /**
     * The trigger leaderboard ({@code statlb}); its layout loads on first use, and the game decides whether it exists.
     */
    private final @NotNull Leaderboard leaderboard;
    /**
     * The options' Portraits ({@code UnitPortraits}): {@code 2} animated, {@code 1} still, {@code 0} none.
     */
    private int portraitMode;
    private int portraitStillPortrait;
    private final @NotNull Map<ScrDialog, DialogLayout> popupLayouts;
    private final @NotNull Map<ScrDialog, ScrDialogLayer> popupLayers;
    private final @NotNull Map<String, DialogLayout> placedDialogs;
    private final @NotNull VisionDialog visionDialog;
    private final @NotNull AllianceDialog allianceDialog;
    private final @NotNull MessagingDialog messagingDialog;
    private @NotNull HdConsoleLayout.Variant hdVariant;
    private final int @NotNull [] pieceTextures;
    private int replayRecordedSpeed;
    private int replaySpeedIndex;
    private int replayMultiplier;
    private long replaySeekTarget;
    /**
     * The turn count of the last refresh ({@link ScrGameView#getTurnCount()}), for the per-turn refresh of a multiplayer game.
     */
    private int minimapTurn;
    private boolean minimapTerrain;
    /**
     * The text-box dialog's label control of the open chat box's mode ({@link #setChatLabel}).
     */
    private int chatLabelControl;
    private final @NotNull ObserverStatistics observerStatistics;
    /**
     * The item lefts of each observer statistics row by {@code STATISTICS_*_ITEM} index, kept between the width and the drawing pass.
     */
    private final float @NotNull [] @NotNull [] statisticsItemLefts;
    private final @NotNull RememberedBuildings rememberedBuildings;
    private @NotNull String chatLayoutRace;
    private final @NotNull MinimapPings minimapPings;
    private final @NotNull Map<Integer, int[]> minimapBlinks;
    private int transmissionType;
    private float pointerX;
    private float pointerY;
    /**
     * The replay side bar and the panes it shows ({@code ObserverPanel2}); a new console starts a new replay session.
     */
    private final @NotNull ReplaySideBar sideBar;
    /**
     * The observer page's CSS scale of the last drawn frame ({@link ObserverConsoleUtils#getPageScale}).
     */
    private float observerPageScale;
    private @Nullable String @NotNull [] observerNames;
    private int @NotNull [] observerApm;

    private final @NotNull CascStorage storage;
    private final @NotNull InterfaceRenderer renderer;
    private final @NotNull InterfaceFonts fonts;
    private final @NotNull InterfaceFont smallFont;
    private final @NotNull InterfaceFont normalFont;
    private final @NotNull InterfaceFont bigFont;
    /**
     * The shared cursor drawing (also used by the glue).
     */
    private final @NotNull GameCursor gameCursor;
    private final @NotNull StatText text;
    private final @NotNull InterfaceTimers timers;
    private final @NotNull TilesetSource tilesetAssets;
    private @NotNull HotkeyProfile hotkeyProfile;
    private final @NotNull HotkeyPress hotkeyPress;
    private final @NotNull StatusPanel statusPanel;
    private final @NotNull Tooltip tooltip;
    private boolean windowed;
    private @Nullable String tooltipHover;
    /**
     * The one texture cache of the console (sheet frames, plain DDS, web images, portraits, dynamic images).
     */
    private final @NotNull InterfaceImageCache images;
    /**
     * The console's dialog painter: the dialog frame rules shared with the glue ({@link DialogPainter}).
     */
    private final @NotNull ConsoleDialogPainter painter;
    private int layoutRace;
    private int hoveredSlot;

    /**
     * Creates a {@link ConsoleView} instance.
     *
     * @param storage  the installed game storage
     * @param renderer the client's interface renderer on the current OpenGL context
     * @param fonts    the client's game font set, shared and not closed here
     * @param cursor   the client's game cursor, shared and not closed here
     * @param text     the interface strings
     * @param timers   the client's interface timer list, pumped once per drawn frame while in game
     * @param records  the client's classic frame records of the dialog sheets, shared with the glue
     * @param tilesets the client's interface data per tileset, kept for the run
     */
    public ConsoleView(@NotNull CascStorage storage,
                       @NotNull InterfaceRenderer renderer,
                       @NotNull InterfaceFonts fonts,
                       @NotNull GameCursor cursor,
                       @NotNull StatText text,
                       @NotNull InterfaceTimers timers,
                       @NotNull SheetFrameRecords records,
                       @NotNull TilesetSource tilesets) {
        this.assets                = null;
        this.layout                = null;
        this.leaderboardLayout     = null;
        this.leaderboardCreated    = null;
        this.portraitNoiseColors   = null;
        this.portraitPlayer        = null;
        this.portraitStillClock    = 0L;
        this.portraitStillUnit     = null;
        this.controlText           = null;
        this.networkText           = null;
        this.gluText               = null;
        this.visionPopup           = null;
        this.popupList             = null;
        this.alliancePopup         = null;
        this.messagingPopup        = null;
        this.messagingList         = null;
        this.multiplayerButtons    = false;
        this.sdLayout              = null;
        this.layoutWidth           = 0.0f;
        this.hdScale               = 0;
        this.showTimer             = false;
        this.showApm               = false;
        this.apmValue              = 0;
        this.apmColorCode          = 0;
        this.apmPlateKey           = null;
        this.apmPlatePresent       = false;
        this.timePlateKey          = null;
        this.timePlatePresent      = false;
        this.consoleArt            = null;
        this.layoutReplay          = false;
        this.replayPanel           = null;
        this.replayFrame           = 0L;
        this.replayEndFrame        = 0L;
        this.replayPaused          = false;
        this.consoleTexture        = 0;
        this.placement             = null;
        this.hdLayout              = null;
        this.placedLayout          = null;
        this.placedBase            = null;
        this.placedFrom            = null;
        this.placedChange          = -1;
        this.placedTextBox         = null;
        this.placedTextBoxBase     = null;
        this.placedTextBoxWidth    = -1;
        this.placedTextBoxChange   = -1;
        this.maskLayer             = new ConsoleMaskLayer();
        this.targetResumer         = null;
        this.maskedArt             = null;
        this.maskedArtRace         = -1;
        this.maskedArtTexture      = 0;
        this.maskedArtChange       = -1;
        this.resourceIcons         = null;
        this.resourceIconPalette   = new int[RESOURCE_ICON_COLORS];
        this.resourceIconTextures  = new Texture[0];
        this.pictureView           = null;
        this.pictureTextures       = new PictureTextureCache();
        this.minimapTerrainTexture = 0;
        this.minimapFogTexture     = 0;
        this.minimapDotTexture     = 0;
        this.minimapCanvasWidth    = 0;
        this.minimapCanvasHeight   = 0;
        this.multiplayerGame       = false;
        this.minimapWidth          = 0;
        this.minimapHeight         = 0;
        this.minimapRefresh        = false;
        this.minimapTerrainStale   = false;
        this.minimapBlinked        = false;
        this.minimapImage          = null;
        this.minimapState          = null;
        this.observerLayout        = null;
        this.observerWidth         = 0;
        this.minimapColorMode      = 0;
        this.skin                  = null;
        this.chatLayout            = null;
        this.textBox               = null;
        this.messageLines          = null;
        this.minimapBlinkPhase     = false;
        this.transmissionEnd       = 0L;
        this.transmissionStart     = false;
        this.originalAspectRatio   = false;
        this.observerProduction    = null;
        this.sideBarTooltipsHidden = false;
        this.pausedBlink           = false;
        this.portraitShown         = false;
        this.chatEdit              = null;
        this.pausedBlinkTime       = 0L;

        this.resourceBar           = new ResourceBar();
        this.resourceLayout        = new ResourceRowLayout();
        this.leaderboard           = new Leaderboard();
        this.portraitMode          = PORTRAITS_ANIMATED;
        this.portraitStillPortrait = -1;
        this.popupLayouts          = new IdentityHashMap<>();
        this.popupLayers           = new IdentityHashMap<>();
        this.placedDialogs         = new HashMap<>();
        this.visionDialog          = new VisionDialog();
        this.allianceDialog        = new AllianceDialog();
        this.messagingDialog       = new MessagingDialog();
        this.hdVariant             = HdConsoleLayout.Variant.WIDE;
        this.pieceTextures         = new int[ConsoleArt.PIECE_COUNT];
        this.replayRecordedSpeed   = DEFAULT_RECORDED_SPEED;
        this.replaySpeedIndex      = DEFAULT_PLAYBACK_SPEED;
        this.replayMultiplier      = 1;
        this.replaySeekTarget      = -1;
        this.minimapTurn           = -1;
        this.minimapTerrain        = true;
        this.chatLabelControl      = CHAT_LABEL_CONTROL;
        this.observerStatistics    = new ObserverStatistics();
        this.statisticsItemLefts   = new float[PlayerTable.PLAYABLE_PLAYER_SLOTS][STATISTICS_ITEM_COUNT];
        this.rememberedBuildings   = new RememberedBuildings();
        this.chatLayoutRace        = "";
        this.minimapPings          = new MinimapPings(MINIMAP_PING_SLOTS, MINIMAP_PING_FRAMES);
        this.minimapBlinks         = new HashMap<>();
        this.transmissionType      = -1;
        this.pointerX              = -1;
        this.pointerY              = -1;
        this.sideBar               = new ReplaySideBar();
        this.observerPageScale     = 1;
        this.observerNames         = new String[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.observerApm           = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        this.storage       = storage;
        this.renderer      = renderer;
        this.fonts         = fonts;
        this.smallFont     = fonts.getFont(InterfaceFonts.SMALL);
        this.normalFont    = fonts.getFont(InterfaceFonts.NORMAL);
        this.bigFont       = fonts.getFont(InterfaceFonts.BIG);
        this.gameCursor    = cursor;
        this.text          = text;
        this.timers        = timers;
        this.tilesetAssets = tilesets;
        this.hotkeyProfile = new HotkeyProfile(text, HotkeyProfile.DEVICE, Map.of());
        this.hotkeyPress   = new HotkeyPress();
        this.statusPanel   = new StatusPanel(text);
        this.tooltip       = new Tooltip(renderer);
        this.windowed      = true;
        this.tooltipHover  = null;
        this.images        = new InterfaceImageCache(renderer);
        this.painter       = new ConsoleDialogPainter(renderer, this.images, fonts, records);
        this.layoutRace    = -1;
        this.hoveredSlot   = -1;
    }

    /**
     * Sets the client's icon tables of the replay production panel.
     *
     * @param production the tables
     */
    public void setObserverProduction(@NotNull ObserverProduction production) {
        this.observerProduction = production;
    }

    /**
     * @return the layout of the last drawn race, or {@code null} before drawing
     */
    public @Nullable ConsoleLayout getLayout() {
        return this.layout;
    }

    /**
     * Finds the rectangle of a console dialog as it was last drawn, placed by the console placement, in interface units from the game screen's
     * top: the console's rows moved down by the view extension, as {@link GameViewport#toScreenY} places the game screen. The minimap dialog is
     * sized by the minimap size of the placement ({@link ScrConsolePlacement#setMinimapSize}).
     *
     * @param dialog    the dialog
     * @param rectangle receives the left, top, width and height
     * @return {@code true} if the console was drawn with the dialog shown, {@code false} if the dialog is hidden or not drawn yet, the rectangle
     * then unchanged
     */
    @Override
    public boolean findDialogRectangle(ScrConsolePlacement.@NotNull Dialog dialog, float @NotNull [] rectangle) {
        DialogLayout layout = this.findShownDialog(dialog);

        if (layout == null) {
            return false;
        }

        rectangle[RECTANGLE_LEFT_INDEX]   = layout.getX();
        rectangle[RECTANGLE_TOP_INDEX]    = layout.getY() + this.getViewExtensionRows();
        rectangle[RECTANGLE_WIDTH_INDEX]  = layout.getWidth();
        rectangle[RECTANGLE_HEIGHT_INDEX] = layout.getHeight();

        return true;
    }

    /**
     * Finds the rectangle of a control of a console dialog as it was last drawn, placed by the console placement, in interface units; the minimap
     * area takes the minimap size of the placement.
     *
     * @param dialog    the dialog
     * @param control   the control's identifier in the dialog's definition
     * @param rectangle receives the left, top, width and height
     * @return {@code true} if the console was drawn with the dialog and the control shown, {@code false} otherwise, the rectangle then unchanged
     */
    @Override
    public boolean findControlRectangle(ScrConsolePlacement.@NotNull Dialog dialog, int control, float @NotNull [] rectangle) {
        DialogLayout layout = this.findShownDialog(dialog);
        ScrConsolePlacement shownPlacement = this.placement;

        if (layout == null || shownPlacement != null && shownPlacement.isControlHidden(dialog, control)) {
            return false;
        }

        for (DialogLayout.Control shown : layout.getControls()) {
            if (shown.getIdentifier() != control) {
                continue;
            }

            rectangle[RECTANGLE_LEFT_INDEX]   = shown.getX();
            rectangle[RECTANGLE_TOP_INDEX]    = shown.getY() + this.getViewExtensionRows();
            rectangle[RECTANGLE_WIDTH_INDEX]  = shown.getWidth();
            rectangle[RECTANGLE_HEIGHT_INDEX] = shown.getHeight();

            return true;
        }

        return false;
    }

    /**
     * @return the view extension of the last drawn console in interface rows, {@code 0} before drawing
     */
    private float getViewExtensionRows() {
        ConsoleLayout console = this.layout;

        return console == null ? 0 : console.getViewExtension() / console.getScale();
    }

    /**
     * Finds the drawn layout of a shown console dialog.
     *
     * @return the placed layout of the last drawn console, or {@code null} before drawing or for a hidden dialog
     */
    private @Nullable DialogLayout findShownDialog(ScrConsolePlacement.@NotNull Dialog dialog) {
        ConsoleLayout console = this.layout;
        ScrConsolePlacement shownPlacement = this.placement;

        if (console == null || shownPlacement != null && shownPlacement.isDialogHidden(dialog)) {
            return null;
        }

        DialogLayout layout = switch (dialog) {
            case COMMAND_CARD -> console.getCommandButtons();
            case SELECTION -> console.getSelectionPanel();
            case RESOURCES -> console.getResources();
            case PORTRAIT -> console.getPortrait();
            case MINIMAP -> console.getMinimap();
            case MENU -> console.getMenu();
            case TEXT_BOX -> this.textBox == null ? null : this.textBox.getLayout();
        };

        return layout != null && this.isConsoleDialogShown(layout) ? layout : null;
    }

    /**
     * Sets the command card slot under the pointer, which is drawn highlighted with its tooltip.
     *
     * @param slot the slot from {@code 0} through {@code 8}, or {@code -1}
     */
    public void setHoveredSlot(int slot) {
        this.hoveredSlot = slot;
    }

    /**
     * Sets the pointer for the dialog button states: hovering only turns a button's text white (code {@code 4}); the pressed frame
     * follows the dialog's pressed control ({@link #isControlPressed}).
     *
     * @param screenX the pointer screen x
     * @param screenY the pointer screen y
     */
    public void setPointer(float screenX, float screenY) {
        this.pointerX = screenX;
        this.pointerY = screenY;
    }

    /**
     * Tests the pressed state of a console dialog control: the push button sets it when the left button goes down on the control and keeps
     * it only while the pointer stays on the control; the press itself is the dialog's pressed control, set by the dialog list's routing.
     *
     * @param console    the console layout
     * @param layout     one of the console dialogs
     * @param identifier the control id
     * @return {@code true} if the control shows its pressed state, {@code false} otherwise
     */
    private boolean isControlPressed(@NotNull ConsoleLayout console, @NotNull DialogLayout layout, int identifier) {
        ScrDialog dialog = console.getDialog(layout).getDialog();

        return dialog.getPressedId() == identifier && dialog.getHoveredId() == identifier;
    }

    /**
     * Sets SC:R's {@code GameTimer} option: the game time shows on the race console ({@link #drawGameTimer}) and the observer console
     * takes its timer frame ({@link #drawObserver}).
     *
     * @param showTimer whether the game time is shown
     */
    public void setShowTimer(boolean showTimer) {
        this.showTimer = showTimer;
    }

    /**
     * Sets the replay state shown by the replay panel of the replay command card.
     *
     * @param frame         the current frame
     * @param endFrame      the last frame of the replay
     * @param recordedSpeed the replay's recorded game speed index
     * @param speedIndex    the playback speed index
     * @param multiplier    the playback multiplier
     * @param paused        whether playback is paused
     * @param seekTarget    the frame being sought, or a negative value
     */
    public void setReplayState(long frame, long endFrame, int recordedSpeed, int speedIndex, int multiplier, boolean paused, long seekTarget) {
        this.replayFrame         = frame;
        this.replayEndFrame      = endFrame;
        this.replayRecordedSpeed = recordedSpeed;
        this.replaySpeedIndex    = speedIndex;
        this.replayMultiplier    = multiplier;
        this.replayPaused        = paused;
        this.replaySeekTarget    = seekTarget;
    }

    /**
     * Sets the localized glue text.
     *
     * @param gluText the {@code gluAll} strings of the interface locale, used by the replay panel
     */
    public void setGluText(@NotNull GluText gluText) {
        this.replayPanel = new ReplayPanel(this.text, gluText);
        this.gluText     = gluText;
    }

    /**
     * Sets how the game's plugins place the console: the dialogs and controls it moves, the text box included, are drawn and hit at their moved
     * places, and with a mask the console art and the console dialogs are drawn, and the console art hit, only where the mask shows them; the
     * leaderboard, the resource display and the countdown over the game screen are drawn whole.
     *
     * @param placement the placement, or {@code null} for the installed console
     */
    public void setPlacement(@Nullable ScrConsolePlacement placement) {
        this.placement = placement;
    }

    /**
     * Sets what begins the drawing into the frame's target again, its clip included, after the console dialogs were drawn into the mask layer.
     * Without it the dialogs are drawn unmasked.
     *
     * @param resumer begins the drawing into the target, or {@code null}
     */
    public void setTargetResumer(@Nullable Runnable resumer) {
        this.targetResumer = resumer;
    }

    /**
     * Sets the resource icons the game's plugins draw instead of the installed ones: the resource display and the leaderboard draw a frame of the
     * sheet at its offset in the frame box from the icon's place, each pixel but index {@code 0} in the palette.
     *
     * @param icons   the indexed sheet, or {@code null} for the installed icons
     * @param palette the display palette, colors packed {@code 0x00BBGGRR}, copied by this call
     */
    public void setResourceIcons(@Nullable SpriteSheet icons, int @NotNull [] palette) {
        int count = Math.min(palette.length, this.resourceIconPalette.length);

        if (icons == this.resourceIcons && (icons == null || Arrays.equals(palette, 0, count, this.resourceIconPalette, 0, count))) {
            return;
        }

        this.releaseResourceIcons();
        System.arraycopy(palette, 0, this.resourceIconPalette, 0, count);

        this.resourceIcons        = icons;
        this.resourceIconTextures = new Texture[icons == null ? 0 : icons.getFrameCount()];
    }

    /**
     * Finds the frame a plugin draws for a resource icon ({@link #setResourceIcons}).
     *
     * @param icon the resource icon frame
     * @return the frame, or {@code null} for the installed icon
     */
    private @Nullable SpriteFrame findResourceIcon(int icon) {
        SpriteSheet icons = this.resourceIcons;

        return icons == null || icon < 0 || icon >= icons.getFrameCount() ? null : icons.getFrame(icon);
    }

    /**
     * Draws the frame a plugin draws for a resource icon ({@link #findResourceIcon}) at its offset in the frame box from a place, uploading its
     * texture on first use. Draws nothing for a frame without pixels.
     *
     * @param icon      the resource icon frame
     * @param frame     the frame of the sheet
     * @param positionX the interface left of the icon's place
     * @param positionY the interface top of the icon's place
     */
    private void drawResourceIcon(int icon, @NotNull SpriteFrame frame, float positionX, float positionY) {
        SpriteSheet icons = this.resourceIcons;

        if (icons == null || frame.getWidth() == 0 || frame.getHeight() == 0) {
            return;
        }

        Texture texture = this.resourceIconTextures[icon];

        if (texture == null) {
            int[] colors = new int[frame.getWidth() * frame.getHeight()];

            for (int offset = 0; offset < colors.length; offset++) {
                int index = icons.getColor().getValue(frame.getPixelOffset() + offset);
                int color = this.resourceIconPalette[index];
                int red = color & COLOR_CHANNEL_MASK;
                int green = color >>> Byte.SIZE & COLOR_CHANNEL_MASK;
                int blue = color >>> Short.SIZE & COLOR_CHANNEL_MASK;

                // Palette colors are packed 0x00BBGGRR, texture colors 0xAARRGGBB.
                colors[offset] = index == 0 ? 0 : RESOURCE_ICON_ALPHA | red << Short.SIZE | green << Byte.SIZE | blue;
            }

            texture                         = this.painter.uploadTexture(frame.getWidth(), frame.getHeight(), colors);
            this.resourceIconTextures[icon] = texture;
        }

        this.painter.drawTexture(texture, positionX + frame.getX(), positionY + frame.getY(), frame.getWidth(), frame.getHeight(), WHITE_RGBA);
    }

    /**
     * Finds the picture a plugin draws for a resource icon ({@link ScrGameView#findResourceIconPicture}).
     *
     * @param icon the resource icon frame
     * @return the picture, or {@code null} for the frame of {@link #findResourceIcon} or the installed icon
     */
    private @Nullable Picture findResourceIconPicture(int icon) {
        ScrGameView view = this.pictureView;

        return view == null ? null : view.findResourceIconPicture(icon);
    }

    /**
     * Draws the picture a plugin draws for a resource icon stretched into the installed icon's box, uploading its texture on first use.
     *
     * @param picture the picture
     * @param left    the interface left of the box
     * @param top     the interface top of the box
     * @param width   the interface width of the box
     * @param height  the interface height of the box
     */
    private void drawResourceIconPicture(@NotNull Picture picture, float left, float top, float width, float height) {
        this.painter.drawTexture(this.pictureTextures.findTexture(this.renderer, picture), left, top, width, height, WHITE_RGBA);
    }

    /**
     * Releases the uploaded textures of the resource icons a plugin draws.
     */
    private void releaseResourceIcons() {
        for (int index = 0; index < this.resourceIconTextures.length; index++) {
            Texture texture = this.resourceIconTextures[index];

            if (texture != null) {
                IgnoreException.execute(texture::close);
            }

            this.resourceIconTextures[index] = null;
        }
    }

    /**
     * Sets the HD console layout.
     *
     * @param variant the layout variant
     */
    public void setHdVariant(@NotNull HdConsoleLayout.Variant variant) {
        if (variant == this.hdVariant) {
            return;
        }

        this.hdVariant   = variant;
        this.layoutWidth = -1;
    }

    /**
     * Sets the console art: the SD console at {@code 4:3}, or the widescreen HD console pieces.
     *
     * @param hdScale {@code 0} for the SD console, {@code 2} for the HD2 pieces, {@code 4} for the HD pieces
     */
    public void setHdScale(int hdScale) {
        if (hdScale == this.hdScale) {
            return;
        }

        this.hdScale = hdScale;

        // A graphics switch drops every texture of the previous set (sheet frames, console art, cursor, portrait frames,
        // minimap); the new set's images load again on use.
        this.releaseTextures();

        if (this.assets != null) {
            this.assets.setAssetSet(getAssetSet(hdScale));
        }

        this.layoutRace = -1;
    }

    /**
     * Sets the original aspect ratio ({@code OriginalAspectRatio}): the game width is {@code 640} while it is on; a change lays the
     * console out again.
     *
     * @param originalAspectRatio whether the game keeps the {@code 4:3} screen
     */
    public void setOriginalAspectRatio(boolean originalAspectRatio) {
        if (originalAspectRatio == this.originalAspectRatio) {
            return;
        }

        this.originalAspectRatio = originalAspectRatio;
        this.layoutWidth         = -1;
        this.observerLayout      = null;
    }

    /**
     * Sets the console APM of a live game (the options' Display APM In Game).
     *
     * @param shown     whether it shows ({@code apm_Showing}, a live game that is no observer view)
     * @param apm       the local player's APM
     * @param colorCode its color code ({@link ApmAlert#getColorCode})
     */
    public void setApmDisplay(boolean shown, int apm, int colorCode) {
        this.showApm      = shown;
        this.apmValue     = apm;
        this.apmColorCode = colorCode;
    }

    /**
     * Sets the windowed-mode branch of icon digit placement.
     *
     * @param windowed whether the window is not full screen
     */
    public void setWindowed(boolean windowed) {
        this.windowed = windowed;
    }

    /**
     * Returns a decoded sheet frame (for its size) through the console painter's decode cache.
     *
     * @param loaded the interface assets
     * @param sheet  the sheet name
     * @param frame  the frame index
     * @return the decoded frame
     * @throws IOException if the sheet cannot be read
     */
    private @NotNull InterfaceAssets.ScaledImage getSheetImage(@NotNull InterfaceAssets loaded, @NotNull String sheet, int frame) throws IOException {
        this.painter.useAssets(loaded);

        return this.painter.getScaledImage(sheet, frame);
    }

    /**
     * @return the strings that hold the button names and hotkeys
     */
    public @NotNull StatText getText() {
        return this.text;
    }

    /**
     * @return the active hotkey profile of the command buttons
     */
    public @NotNull HotkeyProfile getHotkeyProfile() {
        return this.hotkeyProfile;
    }

    /**
     * Sets the active hotkey profile ({@code input.hotkeyProfile} and {@code input.hotkeys} of the settings file).
     *
     * @param name      {@link HotkeyProfile#DEVICE} or {@link HotkeyProfile#NO_HOTKEYS}
     * @param overrides the keys by {@code stat_txt} id
     */
    public void setHotkeyProfile(@NotNull String name, @NotNull Map<Integer, Integer> overrides) {
        this.hotkeyProfile = new HotkeyProfile(this.text, name, overrides);
    }

    /**
     * @return the pressed state of the buttons pressed by their hotkeys
     */
    public @NotNull HotkeyPress getHotkeyPress() {
        return this.hotkeyPress;
    }

    /**
     * Sets the text of a console control.
     *
     * @param controlText the dialog control strings of the interface locale ({@code RC3.json})
     */
    public void setControlText(@NotNull GluText controlText) {
        this.controlText = controlText;
    }

    /**
     * Sets the localized keyed network and interface status strings.
     *
     * @param networkText the keyed strings of {@code rez/network.json} of the interface locale, for example {@code strTARGET}
     */
    public void setNetworkText(@NotNull GluText networkText) {
        this.networkText = networkText;
    }

    /**
     * Resolves a keyed interface string ({@code rez/network.json}).
     *
     * @param key the key, for example {@code strMAXUNITS}
     * @return the string, or the key itself when it is unknown
     */
    public @NotNull String getNetworkText(@NotNull String key) {
        String value = this.networkText == null ? "" : this.networkText.get(key);

        return value.isEmpty() ? key : value;
    }

    /**
     * Returns the race's text-box dialog ({@code rez\<z|t|p>textbox.ui.json}) as an open dialog of the dialog list: the chat label
     * (control {@code 1}), the mode prompt ({@code 6}) and the chat edit control ({@code 7}). The label and the edit control show, and the
     * edit control holds the focus, only while the chat box is open; closed, the dialog has no pressable control and pointer events pass through it.
     *
     * @param chatOpen whether the chat box is open
     * @return the dialog, or {@code null} before the console is laid out
     * @throws IOException if the dialog file is missing
     */
    public @Nullable ScrDialogLayer getTextBoxLayer(boolean chatOpen) throws IOException {
        ConsoleLayout console = this.layout;

        if (console == null) {
            return null;
        }

        ConsoleDialog textBox = this.getTextBoxDialog(console);
        ScrDialog dialog = textBox.getDialog();

        for (int identifier = CHAT_LABEL_CONTROL; identifier <= CHAT_RECIPIENT_LABEL_CONTROL; identifier++) {
            ScrControl label = dialog.findControl(identifier);

            if (label != null) {
                label.setVisible(chatOpen && identifier == this.chatLabelControl);
            }
        }

        dialog.requireControl(CHAT_EDIT_CONTROL).setVisible(chatOpen);
        dialog.setFocused(dialog.findControl(chatOpen ? CHAT_EDIT_CONTROL : ScrDialog.NONE));

        return textBox;
    }

    /**
     * Sets the chat label control from the supplied mode and optionally replaces its text.
     *
     * @param mode  the chat mode BYTE
     * @param label the text of control {@code 5} or {@code 4} in modes {@code 4} and {@code 5}, {@code null} to keep it
     * @throws IOException if the dialog file is missing
     */
    public void setChatLabel(int mode, @Nullable String label) throws IOException {
        this.chatLabelControl = switch (mode) {
            case ChatPrompt.MODE_ALL -> CHAT_ALL_LABEL_CONTROL;
            case ChatPrompt.MODE_ALLIES -> CHAT_ALLIES_LABEL_CONTROL;
            case ChatPrompt.MODE_RECIPIENT -> CHAT_RECIPIENT_LABEL_CONTROL;
            case ChatPrompt.MODE_OBSERVERS -> CHAT_OBSERVERS_LABEL_CONTROL;
            default -> CHAT_LABEL_CONTROL;
        };

        ConsoleLayout console = this.layout;

        if (label != null && console != null) {
            this.getTextBoxDialog(console).getDialog().requireControl(this.chatLabelControl).setReplacedText(label);
        }
    }

    /**
     * @return the text-box dialog's format of the chosen recipient's label (control {@code -10}, "To %s:"), empty before the console is laid out
     * @throws IOException if the dialog file is missing
     */
    public @NotNull String getChatRecipientFormat() throws IOException {
        ConsoleLayout console = this.layout;
        GluText strings = this.controlText;

        if (console == null || strings == null) {
            return "";
        }

        ScrDialog dialog = this.getTextBoxDialog(console).getDialog();
        DialogControl format = ScrDialogControlUtils.findControl(dialog.getDefinition(), CHAT_RECIPIENT_FORMAT_CONTROL);

        return format == null ? "" : dialog.getControlString(format, strings::get);
    }

    private @NotNull ConsoleDialog getTextBoxDialog(@NotNull ConsoleLayout console) throws IOException {
        DialogLayout placed = this.placeTextBox(this.getTextBoxLayout(), console);
        ConsoleDialog textBox = this.textBox;

        if (textBox == null || textBox.getLayout() != placed) {
            textBox      = new ConsoleDialog(placed);
            this.textBox = textBox;
        }

        return textBox;
    }

    private @NotNull DialogLayout getTextBoxLayout() throws IOException {
        String race = String.valueOf(ConsoleLayout.RACE_LETTERS.charAt(Math.clamp(this.layoutRace, 0, ScrRaceIds.PLAYABLE_COUNT - 1)));

        if (this.chatLayout == null || !this.chatLayoutRace.equals(race)) {
            this.chatLayout     = DialogLayout.load(this.storage, race + "textbox");
            this.chatLayoutRace = race;
        }

        return this.chatLayout;
    }

    /**
     * Tests the console alpha masks at the converted pointer position.
     * Replay panes do not claim console-art clicks; HD side and center masks retain their overlap precedence.
     * No console is hit while its art is not loaded: before the first draw and after a graphics switch until the next draw.
     *
     * @param screenX the screen x
     * @param screenY the screen y
     * @return {@code true} if the point is on the console, {@code false} otherwise
     */
    public boolean isConsoleHit(float screenX, float screenY) {
        ConsoleLayout console = this.layout;
        ConsoleArt art = this.consoleArt;

        if (console == null || art == null || this.layoutReplay) {
            return false;
        }

        ScrConsolePlacement shownPlacement = this.placement;

        if (shownPlacement != null && shownPlacement.isMasked() && !art.hasPicture()) {
            float column = console.toInterfaceX(screenX) - InterfaceTransform.getCenteredDialogLeft(console.getInterfaceWidth());

            return shownPlacement.isShown((int) Math.floor(column), (int) Math.floor(console.toInterfaceY(screenY)));
        }

        return art.isHit(console.toInterfaceX(screenX), console.toInterfaceY(screenY));
    }

    /**
     * @return the replay vision dialog
     */
    public @NotNull VisionDialog getVisionDialog() {
        return this.visionDialog;
    }

    /**
     * @param list the popup's list
     * @return the rows the list shows
     */
    public int getPopupListRows(@NotNull ListBox list) {
        return list.getVisibleRows(this.painter);
    }

    /**
     * Tests whether a text fits a popup control in a font as the tip text does: the text measured in the font is narrower than the
     * control's rows of that font less one times its width (the control's rectangle words, right and bottom inclusive).
     *
     * @param control the control
     * @param text    the text
     * @param slot    the font slot
     * @return {@code true} if the text fits, {@code false} otherwise
     */
    public boolean isPopupTextFitting(@NotNull DialogControl control, @NotNull String text, int slot) {
        int width = (int) this.painter.measure(slot, text);
        int rows = (int) ((control.getHeight() - 1) / this.painter.getLineHeight(slot)) - 1;

        return width < rows * (control.getWidth() - 1);
    }

    /**
     * Returns a cached modal layer whose position follows the current console placement.
     *
     * @param dialog the popup
     * @return its layer, cached per dialog
     */
    public @NotNull ScrDialogLayer getPopupLayer(@NotNull ScrDialog dialog) {
        return this.popupLayers.computeIfAbsent(dialog, key -> new ScrDialogLayer() {
            /**
             * @return the dialog's runtime state
             */
            @Override
            public @NotNull ScrDialog getDialog() {
                return key;
            }

            /**
             * @return the dialog's left edge in interface pixels
             */
            @Override
            public float getLeft() {
                ConsoleLayout console = ConsoleView.this.layout;

                return console == null ? key.getDefinition().getX() : ConsoleView.this.getPopupLayout(key, console).getX();
            }

            /**
             * @return the dialog's top edge in interface pixels
             */
            @Override
            public float getTop() {
                ConsoleLayout console = ConsoleView.this.layout;

                return console == null ? key.getDefinition().getY() : ConsoleView.this.getPopupLayout(key, console).getY();
            }

            /**
             * @return {@code true} if the dialog takes all input while open, {@code false} otherwise
             */
            @Override
            public boolean isModal() {
                return true;
            }
        });
    }

    /**
     * Sets the minimap button visibility of a network game: with more than one player in the Storm game or in Top vs Bottom, the
     * terrain button ({@code 2}) hides and Diplomacy ({@code 4}, {@code allyfltr}) and Message ({@code 3}, {@code msgfltr}) show; Tab
     * still toggles the terrain.
     *
     * @param multiplayer whether the buttons show
     */
    public void setMultiplayerButtons(boolean multiplayer) {
        this.multiplayerButtons = multiplayer;
    }

    /**
     * Sets whether the game is a multiplayer game (byte): its network turn step refreshes the minimap on every turn, and timer
     * {@code 1} runs every {@code 2000} ms.
     *
     * @param multiplayer whether the game is a multiplayer game
     */
    public void setMultiplayerGame(boolean multiplayer) {
        if (this.multiplayerGame != multiplayer) {
            this.timers.kill(this.minimapPings, MINIMAP_REFRESH_TIMER);
        }

        this.multiplayerGame = multiplayer;
    }

    /**
     * @return the alliance dialog of a network game
     */
    public @NotNull AllianceDialog getAllianceDialog() {
        return this.allianceDialog;
    }

    /**
     * @return the Messaging dialog
     */
    public @NotNull MessagingDialog getMessagingDialog() {
        return this.messagingDialog;
    }

    /**
     * @return the open alliance dialog ({@code allyfltr} in a game) as an open dialog, or {@code null}
     * @throws IOException if the dialog cannot be loaded
     */
    public @Nullable ScrDialogLayer getAllianceLayer() throws IOException {
        return this.allianceDialog.isOpen() ? this.getPopupLayer(this.getAlliancePopup()) : null;
    }

    /**
     * @return the open Messaging dialog ({@code msgfltr}) as an open dialog, or {@code null}
     * @throws IOException if the dialog cannot be loaded
     */
    public @Nullable ScrDialogLayer getMessagingLayer() throws IOException {
        return this.messagingDialog.isOpen() ? this.getPopupLayer(this.getMessagingPopup()) : null;
    }

    /**
     * @return the replay vision dialog ({@code allyfltr}) as an open dialog, or {@code null} when it is closed
     * @throws IOException if the dialog cannot be loaded
     */
    public @Nullable ScrDialogLayer getVisionLayer() throws IOException {
        return this.visionDialog.isOpen() ? this.getPopupLayer(this.getVisionPopup()) : null;
    }

    /**
     * @return the status view mode (see {@link StatusPanel#getMode})
     */
    public int getStatusMode() {
        return this.statusPanel.getMode();
    }

    /**
     * Returns the unit a selection panel control shows (cargo slots and group grid).
     *
     * @param identifier the {@code statdata} control
     * @return the shown unit, or {@code null}
     */
    public @Nullable Unit getPanelControlUnit(int identifier) {
        StatusPanel.Control shown = this.statusPanel.getControl(identifier);

        return shown == null ? null : shown.getUnit();
    }

    /**
     * @return the local text layout of the message lines (with {@code font10} against the interface width minus {@code 20})
     */
    public MessageLines.@NotNull TextLayout getMessageLayout() {
        return MessageAreaUtils.createTextLayout(this.normalFont,
                                                 () -> (int) (this.layout == null ? ConsoleLayout.INTERFACE_WIDTH : this.layout.getInterfaceWidth()) -
                                                       MESSAGE_WIDTH_MARGIN);
    }

    /**
     * Sets the interface font locale.
     *
     * @param locale the text locale, for example {@code koKR}
     */
    public void setTextLocale(@NotNull String locale) {
        this.fonts.setLocale(locale);
    }

    /**
     * Sets the HD skin of the interface assets (the {@code --hd-skin} choice; SD ignores it).
     *
     * @param skin the skin folder, for example {@code Carbot}, or {@code null}
     */
    public void setSkin(@Nullable String skin) {
        this.skin = skin;

        if (this.assets != null) {
            this.assets.setSkin(skin);
        }

        this.painter.release();
    }

    /**
     * Resolves a dialog content key through the control strings of {@code RC3.json}.
     *
     * @param key the content key, for example {@code gamemenu-1}
     * @return the string, empty when unknown or before the strings are set
     */
    public @NotNull String getControlString(@NotNull String key) {
        GluText strings = this.controlText;

        return strings == null ? "" : strings.get(key);
    }

    /**
     * @return the interface strings of {@code gluAll}, or {@code null} before they are set
     */
    public @Nullable GluText getGluText() {
        return this.gluText;
    }

    /**
     * @param players the player table
     * @param player  the player slot
     * @return the color the player is shown in ({@link PlayerColorUtils#getPlayerColor}) {@code 0xRRGGBBAA}, or {@code 0} before the assets are
     * loaded
     */
    public int getPlayerColor(@NotNull PlayerTable players, int player) {
        return this.assets == null ? 0 : PlayerColorUtils.getPlayerColor(players, this.assets, player);
    }

    /**
     * Sets the portrait mode to hidden, still or animated; still mode retains the time when the shown portrait changed.
     *
     * @param portraitMode the mode {@code 0..2}
     */
    public void setPortraitMode(int portraitMode) {
        this.portraitMode = portraitMode;
    }

    /**
     * Sets the portrait clip player; without one the portrait slot stays empty.
     *
     * @param portraitPlayer the player, or {@code null}
     */
    public void setPortraitPlayer(@Nullable PortraitPlayer portraitPlayer) {
        if (this.portraitPlayer != null && this.portraitPlayer != portraitPlayer) {
            this.timers.killAll(this.portraitPlayer.getStatic());
        }

        this.portraitPlayer = portraitPlayer;
    }

    /**
     * Builds the text of a hovered control (and the minimap and menu button procedures).
     *
     * @param model   the console model
     * @param console the console layout
     * @param control the hovered control
     * @return the tooltip text, empty when the control has none
     */
    private @NotNull String getControlTooltip(@NotNull ConsoleModel model, @NotNull ConsoleLayout console, @NotNull DialogLayout.Control control) {
        if (control == console.getMinimap().getControl(TERRAIN_BUTTON)) {
            return this.text.getString(this.minimapTerrain ? HIDE_TERRAIN_TOOLTIP : SHOW_TERRAIN_TOOLTIP);
        }

        if (control == console.getMinimap().getControl(MESSAGE_BUTTON)) {
            return this.text.getString(MESSAGING_TOOLTIP);
        }

        if (control == console.getMinimap().getControl(VISION_BUTTON)) {
            return this.text.getString(DIPLOMACY_TOOLTIP);
        }

        if (control == console.getMenu().getControl(MENU_BUTTON)) {
            return this.text.getString(MENU_TOOLTIP);
        }

        Unit active = model.getSelection().getActiveUnit();
        StatusPanel.Control shown = this.statusPanel.getControl(control.getIdentifier());

        if (active == null || shown == null) {
            return "";
        }

        int identifier = control.getIdentifier();
        StatusPanel.Icon icon = shown.getIcon();

        if (identifier <= LAST_QUEUE_SLOT || identifier == CHILD_SLOT) {
            return icon == null ? "" : ProductionTooltipUtils.buildQueue(model.getView(), this.text, active, icon);
        }

        if (identifier == RESEARCH_SLOT) {
            return icon == null ? "" : ProductionTooltipUtils.buildCancel(model.getView(), this.text, icon);
        }

        if (identifier <= LAST_STAT_ICON) {
            return icon == null ? "" : StatIconTooltipUtils.build(model.getView(), this.text, active, icon);
        }

        if (identifier <= LAST_CARGO_SLOT) {
            Unit loaded = shown.getUnit();

            return loaded == null
                   ? ""
                   : this.text.getString(model.getView(), UNLOAD_TOOLTIP).replace("%s", this.text.getUnitName(model.getView(), loaded.getUnitId()));
        }

        return this.text.getString(GROUP_TOOLTIP);
    }

    /**
     * Returns where the next cost item of a button tooltip starts: the grown right bound (word) of the amount measured at its pen.
     *
     * @param font      the cost font
     * @param amount    the cost amount
     * @param numberPen the pen position of the amount
     * @param clipRight the right clip bound
     * @return the grown right bound of the measured amount
     */
    private float getCostNumberRight(@NotNull InterfaceFont font, int amount, float numberPen, int clipRight) {
        return InterfaceText.measureBounds(font,
                                           List.of(Integer.toString(amount)),
                                           (int) numberPen, 0,
                                           Tooltip.LINE_GAP_BYTE,
                                           Tooltip.PEN_START_X, 0,
                                           clipRight, ConsoleLayout.INTERFACE_HEIGHT - 1)
                            .getRight();
    }

    /**
     * @return the units shown in the group grid ({@code statdata} controls {@code 33..44}) in control order
     */
    public @NotNull List<Unit> getPanelUnits() {
        List<Unit> units = new ArrayList<>();

        for (int identifier = FIRST_GROUP_SLOT; identifier <= LAST_GROUP_SLOT; identifier++) {
            StatusPanel.Control shown = this.statusPanel.getControl(identifier);

            if (shown != null && shown.getUnit() != null) {
                units.add(shown.getUnit());
            }
        }

        return units;
    }

    /**
     * Sets the replay statistics inputs: player names by slot and the APM of each slot.
     *
     * @param names the names by slot from the replay header
     * @param apm   the APM of slots {@code 0..7}
     */
    public void setObserverInputs(@Nullable String @NotNull [] names, int @NotNull [] apm) {
        this.observerNames = names.clone();
        this.observerApm   = apm.clone();
    }

    /**
     * @return the replay side bar and the visibility of the panes it shows and hides
     */
    public @NotNull ReplaySideBar getReplaySideBar() {
        return this.sideBar;
    }

    /**
     * Tests whether the corresponding replay pane permits its console dialog to participate in input routing.
     *
     * @param dialog one of the laid out console's dialogs
     * @return {@code true} if the dialog is shown, {@code false} otherwise
     */
    public boolean isConsoleDialogShown(@NotNull DialogLayout dialog) {
        ConsoleLayout console = this.layout;

        if (!this.layoutReplay || console == null) {
            return true;
        }

        if (dialog == console.getMinimap()) {
            return this.sideBar.isShown(ReplaySideBar.Pane.MINIMAP);
        }

        if (dialog == console.getSelectionPanel()) {
            return this.sideBar.isShown(ReplaySideBar.Pane.UNIT);
        }

        if (dialog == console.getCommandButtons()) {
            return this.sideBar.isShown(ReplaySideBar.Pane.REPLAY);
        }

        return true;
    }

    /**
     * Tells whether a screen point lies on the shown replay side bar, whose clicks the game view does not get.
     *
     * @param screenX the screen x
     * @param screenY the screen y
     * @return {@code true} if the point is on the bar, {@code false} otherwise
     */
    public boolean isSideBarHit(float screenX, float screenY) {
        float[] bar = this.getSideBarRectangle();

        return bar != null && screenX >= bar[SIDEBAR_LEFT_INDEX] && screenY >= bar[SIDEBAR_TOP_INDEX] &&
               screenX < bar[SIDEBAR_LEFT_INDEX] + ReplaySideBar.WIDTH * bar[SIDEBAR_SCALE_INDEX] &&
               screenY < bar[SIDEBAR_TOP_INDEX] + ReplaySideBar.HEIGHT * bar[SIDEBAR_SCALE_INDEX];
    }

    /**
     * Sets whether replay side-bar tooltips are hidden.
     *
     * @param hidden whether the tooltips are hidden
     */
    public void setSideBarTooltipsHidden(boolean hidden) {
        this.sideBarTooltipsHidden = hidden;
    }

    /**
     * @return the race's console asset folder {@code game/consoles/<zerg|terran|protoss>/} of the laid out console
     */
    private @NotNull String getConsoleFolder() {
        return "game/consoles/" + RACE_FOLDERS[Math.clamp(this.layoutRace, 0, ScrRaceIds.PLAYABLE_COUNT - 1)] + "/";
    }

    /**
     * @return the command button sheet {@code cmdbtns.dds.grp} of the laid out console's folder
     */
    private @NotNull String getCommandButtonSheet() {
        return this.getConsoleFolder() + COMMAND_BUTTON_SHEET;
    }

    /**
     * @return the dialog sheet {@code dlg.dds.grp} of the laid out console's folder
     */
    private @NotNull String getDialogSheet() {
        return this.getConsoleFolder() + DIALOG_SHEET;
    }

    /**
     * Loads the images of a map tileset, replacing earlier textures.
     *
     * @param tileset the map tileset index
     * @throws IOException if an asset is missing or malformed
     */
    public void loadTileset(int tileset) throws IOException {
        this.releaseTextures();

        this.assets = this.tilesetAssets.getTilesetAssets(tileset);

        this.assets.setAssetSet(getAssetSet(this.hdScale));
        this.assets.setSkin(this.skin);

        this.layoutRace = -1;
    }

    /**
     * Draws the console of the model over a screen rectangle.
     *
     * @param model    the console model, updated for the current frame
     * @param viewport the client's screen geometry: the window, the interface and the game view
     * @param camera   the camera, for the minimap's view frame ({@link GameViewport#getMapView})
     * @throws IOException           if an interface image cannot be decoded
     * @throws IllegalStateException if the console art of the race is not loaded
     */
    public void draw(@NotNull ConsoleModel model, @NotNull GameViewport viewport, @NotNull ClientCamera camera) throws IOException {
        float width = viewport.getWindowWidth();
        float height = viewport.getWindowHeight();
        float[] view = viewport.getMapView(camera);
        float viewLeft = view[GameViewport.MAP_VIEW_LEFT_INDEX];
        float viewTop = view[GameViewport.MAP_VIEW_TOP_INDEX];
        float viewWidth = view[GameViewport.MAP_VIEW_WIDTH_INDEX];
        float viewHeight = view[GameViewport.MAP_VIEW_HEIGHT_INDEX];
        InterfaceAssets loaded = this.assets;

        if (loaded == null) {
            return;
        }

        // The interface loop pumps the client's timer list once; in game this is the console's frame.
        this.timers.pump((int) UiClockUtils.getHostTimeMilliseconds());

        this.pictureView = model.getView();

        // Text path, shared with the renderer's developer text.
        this.fonts.setViewport(getAssetSet(this.hdScale), width, height);

        int race = Math.clamp(model.getRace(), 0, ScrRaceIds.PLAYABLE_COUNT - 1);
        boolean replay = model.getPerspective() == CommandCard.Perspective.NATIVE_REPLAY;

        this.rememberedBuildings.update(model.getView(), model.getView().getCurrentFrame());

        if (race != this.layoutRace || replay != this.layoutReplay) {
            // A failed load is tried again on the next draw.
            this.layoutRace   = -1;
            this.layoutReplay = replay;
            this.sdLayout     = ConsoleLayout.load(this.storage, race, replay, this.hdScale != 0);
            this.layout       = this.sdLayout;

            this.releaseConsoleArt();

            this.observerLayout = null;

            if (!replay) {
                // Every image is decoded before the art and its textures are published.
                if (this.hdScale == 0) {
                    ConsoleArt art = ConsoleArt.ofStandardDefinition(loaded.decodeConsole(race));
                    InterfaceImage console = art.getImage(ConsoleArt.LEFT_PIECE);

                    this.consoleTexture = this.renderer.createTexture(console.getWidth(), console.getHeight(), console.getRgba());
                    this.consoleArt     = art;
                } else {
                    InterfaceImage[] pieces = new InterfaceImage[ConsoleArt.PIECE_COUNT];

                    for (int piece = 0; piece < ConsoleArt.PIECE_COUNT; piece++) {
                        pieces[piece] = loaded.decodeHdConsolePiece(race, HD_CONSOLE_PIECES[piece], this.hdScale == HdConsoleLayout.HD2_SCALE);
                    }

                    // Piece sizes in HD2 pixels.
                    ConsoleArt art = ConsoleArt.ofPieces(pieces, this.hdScale / HdConsoleLayout.HD2_SCALE);

                    for (int piece = 0; piece < ConsoleArt.PIECE_COUNT; piece++) {
                        InterfaceImage image = pieces[piece];

                        this.pieceTextures[piece] = this.renderer.createTexture(image.getWidth(), image.getHeight(), image.getRgba());
                    }

                    this.consoleArt = art;
                }
            }

            this.layoutWidth = -1;
            this.layoutRace  = race;
        }

        if (replay) {
            this.drawObserver(model, loaded, viewport, viewLeft, viewTop, viewWidth, viewHeight);

            return;
        }

        ConsoleArt art = this.consoleArt;

        if (art == null) {
            throw Exceptions.newIllegalStateException("The console art of race {0} is not loaded.", race);
        }

        ConsoleLayout console;

        if (this.hdScale == 0) {
            console = this.sdLayout;
        } else {
            // W = trunc(clamp(aspect, 4/3, 16/9) * 480), from the shared viewport.
            float interfaceWidth = viewport.getInterfaceWidth();

            if (interfaceWidth != this.layoutWidth || this.hdLayout == null) {
                this.hdLayout = HdConsoleLayout.createLayout(this.sdLayout, interfaceWidth, this.hdVariant);

                art.placePieces(interfaceWidth, this.hdVariant);

                this.layoutWidth = interfaceWidth;
            }

            console = this.hdLayout;
        }

        console     = this.placeDialogs(console);
        this.layout = console;

        console.setViewport(viewport);

        ScrConsolePlacement shownPlacement = this.placement;

        for (int piece = 0; piece < art.getPieceCount(); piece++) {
            art.setPicture(piece, model.getView().findConsoleArt(this.hdScale == 0 ? ScrConsoleArt.CONSOLE : HD_CONSOLE_ARTS[piece]));
        }

        // A replaced piece shows the whole frame; the placement then limits only the dialogs.
        if (shownPlacement != null && shownPlacement.isMasked() && !art.hasPicture()) {
            this.drawMaskedArt(console, loaded, race, shownPlacement);
        } else if (this.hdScale == 0) {
            this.drawArtPiece(console, art, ConsoleArt.LEFT_PIECE, this.consoleTexture);
        } else {
            // Side pieces first; the center piece covers their overlapping filler art.
            for (int piece : new int[]{ConsoleArt.LEFT_PIECE, ConsoleArt.RIGHT_PIECE, ConsoleArt.CENTER_PIECE}) {
                this.drawArtPiece(console, art, piece, this.pieceTextures[piece]);
            }
        }

        Runnable resumer = this.targetResumer;
        boolean layered = shownPlacement != null && shownPlacement.isMasked() && resumer != null;

        // The leaderboard, the resource display and the countdown are drawn over the game screen, not through the mask.
        if (layered) {
            this.drawLeaderboard(model, viewport, loaded);
            this.drawResources(model, console, viewport, loaded);
            this.drawCountdown(model, console, viewport);
            this.maskLayer.begin(this.renderer);
        }

        try {
            this.drawGameTimer(model, console, loaded);
            this.drawApm(console, loaded);

            if (!layered) {
                this.drawLeaderboard(model, viewport, loaded);
            }

            this.drawMinimap(model, console, loaded, viewLeft, viewTop, viewWidth, viewHeight);

            if (!layered) {
                this.drawResources(model, console, viewport, loaded);
                this.drawCountdown(model, console, viewport);
            }

            this.drawSelection(model, console, loaded);
            this.drawPortrait(model, console);
            this.drawConsoleButtons(console, loaded);

            if (layered) {
                this.drawCommandCard(model, console, loaded);
            }
        } finally {
            if (layered) {
                this.maskLayer.end(this.renderer, resumer, console, shownPlacement);
            }
        }

        if (this.visionDialog.isOpen()) {
            this.drawVisionDialog(console, loaded);
        }

        if (this.allianceDialog.isOpen()) {
            this.drawAllianceDialog(console, loaded);
        }

        if (this.messagingDialog.isOpen()) {
            this.drawMessagingDialog(console, loaded);
        }

        if (!layered) {
            this.drawCommandCard(model, console, loaded);
        }

        this.drawMessages(console);
        this.updateTooltip(model, console);
        this.tooltip.draw(console);
    }

    /**
     * Draws the optional time plate and the game clock centered on the time pen ({@link GameTimerLayout#ofRaceConsole}).
     * The console class of the art ({@link ConsoleKind}) places the plate and the pen; the plate is drawn only when the
     * console art has it, and the clock does not depend on it.
     */
    private void drawGameTimer(@NotNull ConsoleModel model, @NotNull ConsoleLayout console, @NotNull InterfaceAssets loaded) throws IOException {
        if (!this.showTimer) {
            return;
        }

        int interfaceWidth = (int) console.getInterfaceWidth();
        int offsetX = (interfaceWidth - ConsoleLayout.INTERFACE_WIDTH) / 2;
        ConsoleKind kind = ConsoleKind.of(loaded.getAssetSet());
        String plate = this.getConsoleFolder() + TIME_PLATE;
        String plateKey = loaded.getQualityPrefix() + plate;

        if (!plateKey.equals(this.timePlateKey)) {
            this.timePlateKey     = plateKey;
            this.timePlatePresent = loaded.hasAsset(plate);
        }

        if (this.timePlatePresent) {
            InterfaceImageCache.Sprite image = this.images.getImage(loaded, plate);
            ConsoleType type = ConsoleType.of(this.layoutRace, false);

            this.drawConsoleImage(console, image, ScrConsoleArt.CONSOLE_TIME, kind.getTimePlateLeft(type) + offsetX, kind.getTimePlateTop(type));
        }

        this.drawClockText(console,
                           this.smallFont,
                           TimerTextUtils.formatGameTime(model.getView().getCurrentFrame()),
                           GameTimerLayout.ofRaceConsole(interfaceWidth, kind),
                           interfaceWidth);
    }

    /**
     * Draws the optional APM plate, then the local APM value right aligned in its selected color ({@link ConsoleKind#calculateApmTextRight}).
     * The console class of the art ({@link ConsoleKind}) places the plate and the pen.
     */
    private void drawApm(@NotNull ConsoleLayout console, @NotNull InterfaceAssets loaded) throws IOException {
        if (!this.showApm) {
            return;
        }

        int interfaceWidth = (int) console.getInterfaceWidth();
        int offsetX = (interfaceWidth - ConsoleLayout.INTERFACE_WIDTH) / 2;
        ConsoleKind kind = ConsoleKind.of(loaded.getAssetSet());
        String plate = this.getConsoleFolder() + APM_PLATE;
        String plateKey = loaded.getQualityPrefix() + plate;

        if (!plateKey.equals(this.apmPlateKey)) {
            this.apmPlateKey     = plateKey;
            this.apmPlatePresent = loaded.hasAsset(plate);
        }

        if (this.apmPlatePresent) {
            InterfaceImageCache.Sprite image = this.images.getImage(loaded, plate);
            ConsoleType type = ConsoleType.of(this.layoutRace, false);

            this.drawConsoleImage(console, image, ScrConsoleArt.CONSOLE_APM, kind.getApmPlateLeft(type) + offsetX, kind.getApmPlateTop(type));
        }

        InterfaceFont font = this.smallFont;
        String text = Integer.toString(this.apmValue);
        float left = kind.calculateApmTextRight(interfaceWidth) - font.measure(text, true);

        InterfaceText.drawAt(this.renderer, console, font, text, left, kind.getApmPenY(), interfaceWidth - 1, this.apmColorCode);
    }

    /**
     * Measures the text bounds at the layout's center pen, then draws the text at the left that centers them ({@link GameTimerLayout}).
     */
    private void drawClockText(@NotNull InterfaceTransform transform,
                               @NotNull InterfaceFont font,
                               @NotNull String text,
                               @NotNull GameTimerLayout layout,
                               int interfaceWidth) {
        int clipRight = interfaceWidth - 1;
        InterfaceText.Bounds bounds = InterfaceText.measureBounds(font,
                                                                  List.of(text),
                                                                  layout.getCenterX(), layout.getTop(),
                                                                  InterfaceText.LINE_GAP,
                                                                  0, 0,
                                                                  clipRight, ConsoleLayout.INTERFACE_HEIGHT - 1);
        int left = layout.calculateTextLeft(bounds.getWidth());

        InterfaceText.drawAt(this.renderer, transform, font, text, left, layout.getTop(), clipRight, SELECTED_FONT_COLOR_CODE);
    }

    /**
     * Draws the trigger countdown at the top-anchored resource control while its display value is visible.
     */
    private void drawCountdown(@NotNull ConsoleModel model, @NotNull ConsoleLayout console, @NotNull GameViewport viewport) {
        TriggerContext context = model.getView().getTriggerContext();

        if (context == null) {
            return;
        }

        int seconds = context.getCountdownDisplay();

        if (seconds == TriggerContext.COUNTDOWN_HIDDEN) {
            return;
        }

        DialogLayout.Control control = console.getResources().getControl(COUNTDOWN_CONTROL);

        // The statres dialog keeps its offsets from the game view's top edge.
        this.drawCodedText(viewport, control, TimerTextUtils.formatCountdown(seconds));
    }

    /**
     * Draws the resource counters of {@link ResourceBar} in their {@code statres} slots, laid out by {@link ResourceRowLayout}:
     * each counter keeps its slot while its text fits, a wider counter moves left with the counters on its left, and the
     * text keeps its font size. The counters keep their offsets from the game view's top edge ({@link GameViewport}).
     */
    private void drawResources(@NotNull ConsoleModel model,
                               @NotNull ConsoleLayout console, @NotNull GameViewport viewport,
                               @NotNull InterfaceAssets loaded) throws IOException {
        DialogLayout resources = console.getResources();
        InterfaceFont font = this.normalFont;
        ResourceRowLayout layout = this.resourceLayout;

        if (!this.timers.isArmed(this.resourceBar, RESOURCE_TIMER)) {
            this.timers.arm(this.resourceBar, RESOURCE_TIMER, ResourceBar.TICK_INTERVAL,
                            (int) UiClockUtils.getHostTimeMilliseconds(), clock -> this.resourceBar.tick());
        }

        layout.reset();

        for (ResourceBar.Counter counter : this.resourceBar.update(model.getView(), model.getPlayer(), model.getRace())) {
            DialogLayout.Control control = resources.getControl(counter.getControlIdentifier());
            int icon = counter.getIcon();
            InterfaceAssets.ScaledImage image = this.getSheetImage(loaded, RESOURCE_SHEET, icon);
            Picture picture = this.findResourceIconPicture(icon);
            SpriteFrame replaced = picture == null ? this.findResourceIcon(icon) : null;
            int iconWidth = replaced == null ? image.getInterfaceWidth() : replaced.getX() + replaced.getWidth();
            int textWidth = (int) Math.ceil(font.measure(counter.getText(), true)) + RESOURCE_TEXT_CLIP_PIXEL;
            // The slot edges are integers, so the placed item edges are integers too.
            int iconX = (int) layout.placeItem(control.getX(), control.getX() + control.getWidth(), iconWidth + RESOURCE_TEXT_GAP + textWidth);
            int right = (int) layout.getItemRight();
            int iconY = control.getY() + (control.getHeight() - image.getInterfaceHeight()) / 2;
            int penX = iconX + iconWidth + RESOURCE_TEXT_GAP;

            this.painter.begin(viewport, loaded);

            if (picture != null) {
                this.drawResourceIconPicture(picture, iconX, iconY, iconWidth, image.getInterfaceHeight());
            } else if (replaced == null) {
                this.painter.drawImageFrame(this.painter.getSheetFrame(RESOURCE_SHEET, icon),
                                            iconX, iconY,
                                            iconWidth, image.getInterfaceHeight(),
                                            WHITE_RGBA);
            } else {
                this.drawResourceIcon(icon, replaced, iconX, iconY);
            }

            InterfaceText.draw(this.renderer,
                               viewport,
                               font,
                               counter.getText(),
                               penX, control.getY(),
                               right - penX, control.getHeight(),
                               RESOURCE_TEXT_FORMAT,
                               RESOURCE_TEXT_COLOR_CODE,
                               true);
        }
    }

    /**
     * Shows or hides the leaderboard rows after the first (Ctrl+R, {@link Leaderboard#toggleAllPlayersShown}).
     */
    public void toggleLeaderboardPlayers() {
        this.leaderboard.toggleAllPlayersShown();
    }

    /**
     * Draws the trigger leaderboard ({@link Leaderboard}, dialog {@code statlb} at the interface origin) over the game:
     * <ul>
     * <li>color boxes {@code -10..-17}: the control rectangle filled with the main palette entry of the player's minimap color (game context);</li>
     * <li>texts: {@code font10} (slot), format {@code 0x21} (left, vertically centered), color code {@code 7}, clipped to the control;</li>
     * <li>the label {@code -28}: its left edge is {@code 4} past the end of the first value text, at most {@code 4} past the
     * value control's right edge;</li>
     * <li>Greed icons {@code -48} and {@code -49}: the icons sheet frame {@code 0} and the gas frame {@code race + 1}
     * at the control's left, centered vertically.</li>
     * </ul>
     */
    private void drawLeaderboard(@NotNull ConsoleModel model, @NotNull GameViewport viewport, @NotNull InterfaceAssets loaded) throws IOException {
        boolean replay = model.getPerspective() == CommandCard.Perspective.NATIVE_REPLAY;

        if (this.leaderboardCreated == null) {
            this.leaderboardCreated = Leaderboard.isCreated(model.getView(), replay);
        }

        if (!this.leaderboardCreated) {
            return;
        }

        if (!this.timers.isArmed(this.leaderboard, LEADERBOARD_TIMER)) {
            this.timers.arm(this.leaderboard,
                            LEADERBOARD_TIMER,
                            Leaderboard.TICK_MILLISECONDS,
                            (int) UiClockUtils.getHostTimeMilliseconds(),
                            clock -> this.leaderboard.tick());
        }

        this.leaderboard.update(model.getView(), model.getPresentation(), this.text);

        if (this.leaderboard.getMode() == Leaderboard.Mode.NONE) {
            return;
        }

        DialogLayout dialog = this.leaderboardLayout;

        if (dialog == null) {
            dialog                 = DialogLayout.load(this.storage, "statlb");
            this.leaderboardLayout = dialog;
        }

        InterfaceFont font = this.normalFont;
        // The leaderboard keeps its offsets from the game view's top edge.
        float scale = viewport.getScale();
        boolean greed = this.leaderboard.getMode() == Leaderboard.Mode.GREED;

        for (int row = 0; row < Leaderboard.ROWS; row++) {
            int player = this.leaderboard.getRowPlayer(row);

            if (player < 0) {
                continue;
            }

            DialogLayout.Control box = dialog.getControl(Leaderboard.FIRST_BOX - row);
            int color = PlayerColorUtils.getMinimapIndexColor(model.getView().getPlayerTable(), loaded, player);

            this.renderer.fillRectangle(viewport.toScreenX(box.getX()), viewport.toScreenY(box.getY()),
                                        box.getWidth() * scale, box.getHeight() * scale,
                                        color);

            if (greed) {
                this.drawLeaderboardText(viewport, font, dialog.getControl(Leaderboard.FIRST_MINERALS - row), this.leaderboard.getMinerals(row), 0);
                this.drawLeaderboardText(viewport, font, dialog.getControl(Leaderboard.FIRST_GAS - row), this.leaderboard.getGas(row), 0);
            } else {
                this.drawLeaderboardText(viewport, font, dialog.getControl(Leaderboard.FIRST_VALUE - row), this.leaderboard.getValue(row), 0);
            }
        }

        if (greed) {
            int race = Math.clamp(model.getRace(), 0, ScrRaceIds.PLAYABLE_COUNT - 1);

            this.drawLeaderboardIcon(viewport, loaded, dialog.getControl(Leaderboard.MINERALS_ICON), ResourceBar.MINERAL_ICON);
            this.drawLeaderboardIcon(viewport, loaded, dialog.getControl(Leaderboard.GAS_ICON), race + ResourceBar.FIRST_GAS_ICON);

            return;
        }

        String label = this.leaderboard.getLabel();

        if (label == null) {
            return;
        }

        DialogLayout.Control value = dialog.getControl(Leaderboard.FIRST_VALUE);
        DialogLayout.Control title = dialog.getControl(Leaderboard.LABEL);
        int valueRight = value.getX() + value.getWidth() - 1;
        int textEnd = value.getX() + (int) font.measure(this.leaderboard.getValue(0), true);
        int left = Math.min(textEnd, valueRight) + LEADERBOARD_LABEL_GAP;

        this.drawLeaderboardText(viewport, font, title, label, left - title.getX());
    }

    private void drawLeaderboardText(@NotNull InterfaceTransform transform,
                                     @NotNull InterfaceFont font,
                                     DialogLayout.@NotNull Control control,
                                     @NotNull String value,
                                     int shiftLeft) {
        if (value.isEmpty()) {
            return;
        }

        InterfaceText.draw(this.renderer,
                           transform,
                           font,
                           value,
                           control.getX() + shiftLeft, control.getY(),
                           control.getWidth() - shiftLeft, control.getHeight(),
                           LEADERBOARD_TEXT_FORMAT,
                           LEADERBOARD_TEXT_COLOR_CODE,
                           true);
    }

    private void drawLeaderboardIcon(@NotNull InterfaceTransform transform,
                                     @NotNull InterfaceAssets loaded,
                                     DialogLayout.@NotNull Control control,
                                     int frame) throws IOException {
        InterfaceAssets.ScaledImage image = this.getSheetImage(loaded, RESOURCE_SHEET, frame);
        int y = control.getY() + (control.getHeight() - image.getInterfaceHeight() + 1) / 2;
        Picture picture = this.findResourceIconPicture(frame);
        SpriteFrame replaced = this.findResourceIcon(frame);

        this.painter.begin(transform, loaded);

        if (picture != null) {
            this.drawResourceIconPicture(picture, control.getX(), y, image.getInterfaceWidth(), image.getInterfaceHeight());

            return;
        }

        if (replaced != null) {
            this.drawResourceIcon(frame, replaced, control.getX(), y);

            return;
        }

        this.painter.drawImageFrame(this.painter.getSheetFrame(RESOURCE_SHEET, frame),
                                    control.getX(), y,
                                    image.getInterfaceWidth(), image.getInterfaceHeight(),
                                    WHITE_RGBA);
    }

    private void drawSelection(@NotNull ConsoleModel model, @NotNull ConsoleLayout console, @NotNull InterfaceAssets loaded) throws IOException {
        InterfaceSelection selection = model.getSelection();
        boolean replay = model.getPerspective() == CommandCard.Perspective.NATIVE_REPLAY;

        this.statusPanel.update(model.getView(), model.getPresentation(), selection, replay, model.getPlayer());
        this.updateDialogState(model, console, replay);

        DialogLayout panel = console.getSelectionPanel();
        ScrUnitTypeView units = model.getView().requireGameData().getUnitTypes();
        Wireframe wireframe = Wireframe.createStatusWireframe(selection, units);

        if (wireframe != null) {
            DialogLayout.Control control = panel.getControl(MAIN_WIREFRAME_CONTROL);

            this.drawWireframe(console,
                               loaded,
                               wireframe,
                               model.getView().requireGameData().getWireframe(wireframe.getFrame()),
                               WIREFRAME_SHEET,
                               control.getX(), control.getY(),
                               MAIN_WIREFRAME_SIZE, MAIN_WIREFRAME_SIZE,
                               false);
        }

        for (DialogLayout.Control control : panel.getControls()) {
            StatusPanel.Control shown = this.statusPanel.getControl(control.getIdentifier());

            if (shown == null) {
                continue;
            }

            int identifier = control.getIdentifier();

            if (identifier < 0) {
                this.drawCodedText(console, control, shown.getText());
            } else if (identifier == PROGRESS_QUEUE_CONTROL || identifier == PROGRESS_CONSTRUCTION_CONTROL ||
                       identifier == PROGRESS_RESEARCH_CONTROL || identifier == PROGRESS_EGG_CONTROL) {
                this.drawProgressBar(console, loaded, control, shown.getValue());
            } else if (shown.getUnit() != null) {
                this.drawUnitBox(console, loaded, control, shown, units, replay, model);
            } else if (shown.getIcon() != null) {
                this.drawIconControl(console, loaded, control, shown);
            }
        }
    }

    /**
     * Draws the cargo or selection box frame, then the unit wireframe with the existing status, owner and quality-dependent size.
     */
    private void drawUnitBox(@NotNull ConsoleLayout console,
                             @NotNull InterfaceAssets loaded,
                             @NotNull DialogLayout.Control control,
                             @NotNull StatusPanel.Control shown,
                             @NotNull ScrUnitTypeView units,
                             boolean replay,
                             @NotNull ConsoleModel model) throws IOException {
        Unit unit = shown.getUnit();
        boolean cargo = control.getIdentifier() >= FIRST_CARGO_SLOT && control.getIdentifier() <= LAST_CARGO_SLOT;
        int status = unit.getStatus();
        int frame;
        int tint = WHITE_RGBA;

        if (cargo) {
            frame = shown.getStyle();
        } else if (replay) {
            frame = HERO_WIREFRAME_FRAME;

            tint = PlayerColorUtils.getPlayerColor(model.getView().getPlayerTable(), loaded, unit.getOwnerIndex());

            if ((status & UnitStatusFlags.HALLUCINATION) != 0) {
                int red = Math.round((tint >>> PACKED_HIGH_BYTE_SHIFT) * WIREFRAME_COLOR_FACTOR);
                int green = Math.round((tint >>> Short.SIZE & COLOR_CHANNEL_MASK) * WIREFRAME_COLOR_FACTOR);
                int blue = Math.round((tint >>> Byte.SIZE & COLOR_CHANNEL_MASK) * WIREFRAME_COLOR_FACTOR);

                tint = red << PACKED_HIGH_BYTE_SHIFT | green << Short.SIZE | blue << Byte.SIZE | tint & COLOR_CHANNEL_MASK;
            }
        } else {
            frame = (status & UnitStatusFlags.HALLUCINATION) != 0
                    ? HALLUCINATION_WIREFRAME_FRAME
                    : (units.getFlags(unit.getUnitId()) & UnitTypeFlags.HERO) != 0
                      ? HERO_WIREFRAME_FRAME
                      : unit.getParasiteFlags() != 0
                        ? PARASITE_WIREFRAME_FRAME
                        : NORMAL_WIREFRAME_FRAME;
        }

        this.drawSheetFrame(console, loaded, this.getCommandButtonSheet(), frame, control.getX(), control.getY(), tint);

        int boxWidth = GROUP_WIREFRAME_SIZE;
        int boxHeight = GROUP_WIREFRAME_SIZE;

        if (cargo) {
            int space = units.getSpaceRequired(unit.getUnitId());

            boxWidth  = space == LARGE_CARGO_SPACE ? MAIN_WIREFRAME_SIZE : GROUP_WIREFRAME_SIZE;
            boxHeight = space == MEDIUM_CARGO_SPACE || space == LARGE_CARGO_SPACE ? MAIN_WIREFRAME_SIZE : GROUP_WIREFRAME_SIZE;
        }

        String sheet = isStandardDefinition(loaded) ? cargo ? TRANSPORT_SHEET : GROUP_SHEET : WIREFRAME_SHEET;
        float x = control.getX() + (control.getWidth() - boxWidth) / 2f;
        float y = control.getY() + (control.getHeight() - boxHeight) / 2f;
        Wireframe wireframe = Wireframe.createSlotWireframe(unit, units);

        this.drawWireframe(console, loaded, wireframe, model.getView().requireGameData().getWireframe(wireframe.getFrame()), sheet, x, y, boxWidth,
                           boxHeight, !sheet.equals(WIREFRAME_SHEET));
    }

    /**
     * Draws the six cells of a wireframe strip: each cell is a white part mask tinted with the color of its {@code twire.pcx} entry; the strip
     * frame and the cell entries come from the {@link Wireframe}.
     */
    private void drawWireframe(@NotNull ConsoleLayout console,
                               @NotNull InterfaceAssets loaded,
                               @NotNull Wireframe wireframe,
                               int frame,
                               @NotNull String sheet,
                               float x, float y,
                               int width, int height,
                               boolean groupSheet) throws IOException {

        // An added wireframe has its own frame above the installed ones.
        if (!loaded.isAddedSheetFrame(sheet, frame)) {
            if (frame >= ScrUnitIds.NONE) {
                return;
            }

            if (groupSheet && frame >= loaded.getSheetFrameCount(sheet)) {
                return;
            }
        }

        InterfaceAssets.ScaledImage strip = this.getSheetImage(loaded, sheet, frame);
        int texture = this.painter.getSheetFrame(sheet, frame, null).getTexture();
        int cellPixels = strip.getImage().getHeight();
        int cells = strip.getImage().getWidth() / Math.max(1, cellPixels);
        float scale = console.getScale();

        for (int cell = 0; cell < Math.min(Wireframe.CELL_COUNT, cells); cell++) {
            int entry = wireframe.getCellEntry(cell);

            if (entry < 0 && !isStandardDefinition(loaded)) {
                continue;
            }

            int color = entry < 0 ? Wireframe.NO_SHIELD_COLOR : loaded.getWireframeColor(entry);
            // The cell UV is inset by half a texel: linear filtering at the exact cell edge samples the neighboring cell
            // (a stray dot of the next cell's outline showed at the left of the Vulture and Siege Tank wireframes).
            float left = (cell * cellPixels + HALF_TEXEL) / strip.getImage().getWidth();
            float right = ((cell + 1) * cellPixels - HALF_TEXEL) / strip.getImage().getWidth();

            this.renderer.drawTexture(texture, console.toScreenX(x), console.toScreenY(y), width * scale, height * scale, left, 0, right, 1, color);
        }
    }

    /**
     * Draws a status or queue icon with its existing background, tint, pressed shift and level or queue text order.
     */
    private void drawIconControl(@NotNull ConsoleLayout console,
                                 @NotNull InterfaceAssets loaded,
                                 @NotNull DialogLayout.Control control,
                                 @NotNull StatusPanel.Control shown) throws IOException {
        StatusPanel.Icon icon = shown.getIcon();
        int identifier = control.getIdentifier();
        boolean statIcon = identifier >= FIRST_STAT_ICON && identifier <= LAST_STAT_ICON;
        boolean hd = !isStandardDefinition(loaded);
        // Other icon buttons: background first; stat icons: icon first, level box on top.
        // The box frame plus 1 while pressed (bit 30).
        boolean pressed = shown.isEnabled() && this.isControlPressed(console, console.getSelectionPanel(), identifier);

        if (!statIcon && shown.getStyle() != 0) {
            this.drawSheetFrame(console,
                                loaded,
                                this.getCommandButtonSheet(), shown.getStyle() + (pressed ? 1 : 0),
                                control.getX(), control.getY(),
                                WHITE_RGBA);
        }

        if (icon.getKind() == StatusPanel.Icon.EMPTY_QUEUE_SLOT) {
            String sheet = this.getCommandButtonSheet();
            int frame = icon.getFrame();
            InterfaceAssets.ScaledImage image = this.getSheetImage(loaded, sheet, frame);
            float centerX = control.getX() + ((control.getWidth() - image.getInterfaceWidth()) >> 1);
            float centerY = control.getY() + ((control.getHeight() - image.getInterfaceHeight()) >> 1);

            this.painter.begin(console, loaded);
            this.painter.drawImageFrame(this.painter.getSheetFrame(sheet, frame, null), centerX, centerY, WHITE_RGBA);
        } else if (icon.getFrame() >= 0 && icon.getFrame() < loaded.getSheetFrameCount(ICON_SHEET) ||
                   loaded.isAddedSheetFrame(ICON_SHEET, icon.getFrame())) {
            // Icon state 3 (enabled) is yellow, state 4 light gray; HD icon buttons other than stat icons shift 1 left.
            this.drawCommandIconShifted(console,
                                        loaded,
                                        control,
                                        icon.getFrame(),
                                        shown.isEnabled() ? TINT_NORMAL : TINT_HIGHLIGHT,
                                        !statIcon && hd ? -1 : 0, 0);
        }

        if (statIcon && shown.getStyle() != 0) {
            this.drawSheetFrame(console, loaded, this.getCommandButtonSheet(), shown.getStyle(), control.getX(), control.getY(), WHITE_RGBA);
        }

        if (!shown.getText().isEmpty()) {
            // Stat icons: TrueType origin x - 3, bitmap origin y + 1. Queue and other icon buttons: TrueType origin (-4, -3), bitmap unchanged.
            boolean trueType = this.smallFont.isTrueType(shown.getText());
            int shiftX = trueType ? statIcon ? STAT_TRUE_TYPE_SHIFT_X : QUEUE_TRUE_TYPE_SHIFT_X : 0;
            int shiftY = trueType ? statIcon ? 0 : QUEUE_TRUE_TYPE_SHIFT_Y : statIcon ? STAT_BITMAP_SHIFT_Y : 0;

            this.drawIconText(console, control, shown.getText(), hd, loaded.getAssetSet() == AssetResolver.AssetSet.HD2, shiftX, shiftY);
        }
    }

    /**
     * Bottom-aligns and right-aligns the measured icon text, applying the quality-dependent digit nudge and caller shifts.
     */
    private void drawIconText(@NotNull ConsoleLayout console,
                              @NotNull DialogLayout.Control control,
                              @NotNull String value,
                              boolean hd, boolean hd2,
                              int shiftX, int shiftY) {
        InterfaceFont font = this.smallFont;
        int right = control.getX() + control.getWidth() - 1;
        int bottom = control.getY() + control.getHeight() - 1;
        int nudge = hd ? getStatLevelNudge(value, this.windowed || hd2) : 0;
        float width = font.measure(value, true);
        float x = right - width;
        float y = bottom + nudge - 1 - font.getMeasuredHeight(value);

        if (font.isTrueType(value)) {
            y = Math.round(y - font.getMinimumTop(value) * HALF);
        }

        InterfaceText.drawAt(this.renderer, console, font, value, x + shiftX, y + shiftY, right + shiftX, Tooltip.TEXT_COLOR_CODE);
    }

    private void drawSheetFrame(@NotNull ConsoleLayout console,
                                @NotNull InterfaceAssets loaded,
                                @NotNull String sheet, int frame,
                                float x, float y,
                                int color) throws IOException {
        // The dialog frame draw: the position plus the frame offset of the sheet's classic .grp header.
        this.painter.begin(console, loaded);
        this.painter.drawFrame(this.painter.getSheetFrame(sheet, frame), x, y, color);
    }

    private void drawCommandIconShifted(@NotNull ConsoleLayout console,
                                        @NotNull InterfaceAssets loaded,
                                        @NotNull DialogLayout.Control control,
                                        int frame,
                                        float @NotNull [] tint,
                                        int shiftX, int shiftY) throws IOException {
        InterfaceAssets.ScaledImage image = this.getSheetImage(loaded, ICON_SHEET, frame);
        float x;
        float y;

        if (isStandardDefinition(loaded)) {
            // SD: the classic cmdicons.grp frame header width and height halved with truncating division; the SD frame is anchored
            // at the classic canvas origin and drawn there unchanged.
            x = control.getX() + (control.getWidth() - loaded.getClassicCommandIconWidth(frame)) / 2;
            y = control.getY() + (control.getHeight() - loaded.getClassicCommandIconHeight(frame)) / 2;
        } else {
            x = control.getX() + ((control.getWidth() - image.getInterfaceWidth()) >> 1);
            y = control.getY() + ((control.getHeight() - image.getInterfaceHeight()) >> 1);
        }

        // The plain image draw (no classic frame offset).
        this.painter.begin(console, loaded);
        this.painter.drawImageFrame(this.painter.getSheetFrame(ICON_SHEET, frame, tint), x + shiftX, y + shiftY, WHITE_RGBA);
    }

    /**
     * Selects the control font, horizontal alignment and optional no-kerning format before drawing its text.
     */
    private void drawCodedText(@NotNull InterfaceTransform console, @NotNull DialogLayout.Control control, @NotNull String text) {
        InterfaceFont font = this.fonts.getFont(ControlPainterUtils.getFontSlot(control.getFlags()));
        int format = CTEXT_FORMAT | (control.getFlags().contains("RC_NO_TEXTKERN") ? InterfaceText.NO_KERNING : 0);

        this.drawControlText(console, control, text, font, format, SELECTED_FONT_COLOR_CODE);
    }

    /**
     * Places the game cursor of the frame at a screen position with the interface scale of the last drawn console; the
     * present draws it over the whole frame.
     *
     * @param cursor          the cursor state
     * @param scrollDirection the edge scroll direction for {@link WorldInterface.Cursor#SCROLL}
     * @param screenX         the pointer column in framebuffer pixels
     * @param screenY         the pointer row in framebuffer pixels
     * @param milliseconds    a real-time clock in milliseconds that drives the cursor animation
     */
    public void placeCursor(@NotNull WorldInterface.Cursor cursor, int scrollDirection, float screenX, float screenY, long milliseconds) {
        InterfaceAssets loaded = this.assets;
        ConsoleLayout console = this.layout;

        if (loaded == null || console == null) {
            return;
        }

        // The shared cursor of the glue and the game (GameCursor: sheet per quality, 100 ms frames, hot spot).
        this.gameCursor.place(loaded, getCursorName(cursor, scrollDirection), screenX, screenY, console.getScale(), milliseconds);
    }

    private void drawCommandCard(@NotNull ConsoleModel model, @NotNull ConsoleLayout console, @NotNull InterfaceAssets loaded) throws IOException {
        CommandCard card = model.getCommandCard();
        DialogLayout buttons = console.getCommandButtons();
        float scale = console.getScale();

        for (int slot = 0; slot < CommandCard.SLOT_COUNT; slot++) {
            ButtonDefinition button = card.getButton(slot);

            if (button == null) {
                continue;
            }

            int state = card.getState(slot);
            // Icon state: 1 disabled, 2 pressed or the button the active unit's order highlights (CommandCard.getHighlightedIcon), else
            // 0; hovering does not change the icon.
            int frame = button.getIcon();

            if (frame >= loaded.getSheetFrameCount(ICON_SHEET) && !loaded.isAddedSheetFrame(ICON_SHEET, frame)) {
                continue;
            }

            DialogLayout.Control control = buttons.getControl(slot + 1);
            // Bit 30 (pressed): the left button is held over the button, or its hotkey pressed it (HotkeyPress, the same
            // bit); icon state 2 also for the button of the active unit's order (Hold Position while holding, ...).
            boolean pressed = state != CommandCard.DISABLED && (this.isControlPressed(console, buttons, slot + 1) &&
                                                                this.hotkeyPress.isMousePressKept(card::getPressCancellations, slot) ||
                                                                this.hotkeyPress.isPressed(slot));
            boolean highlighted = pressed || state != CommandCard.DISABLED && frame == card.getHighlightedIcon();
            float[] tint = state == CommandCard.DISABLED ? TINT_DISABLED : highlighted ? TINT_HIGHLIGHT : TINT_NORMAL;
            // Pressed icon shift: SD +1, HD -1 (console types 0/1 assumed).
            int pressShift = pressed ? isStandardDefinition(loaded) ? 1 : -1 : 0;

            if (!this.layoutReplay) {
                // Background frame pressed ? 1 : 0 at the control origin; none in a replay.
                this.drawSheetFrame(console,
                                    loaded,
                                    this.getCommandButtonSheet(),
                                    pressed ? InterfaceAssets.BUTTON_UP + 1 : InterfaceAssets.BUTTON_UP,
                                    control.getX(), control.getY(),
                                    WHITE_RGBA);
                this.drawCommandIconShifted(console, loaded, control, frame, tint, pressShift, pressShift);
            } else {
                // Replay icons are shifted: 386/387 by (-2, 3), 388 by (-4, 3), 389 by (-3, 3).
                int shiftX = frame == REPLAY_FAST_ICON
                             ? REPLAY_FAST_SHIFT_X
                             : frame == REPLAY_SLOW_ICON
                               ? REPLAY_SLOW_SHIFT_X
                               : frame == REPLAY_PLAY_ICON || frame == REPLAY_PAUSE_ICON
                                 ? REPLAY_PLAY_PAUSE_SHIFT_X
                                 : 0;
                int shiftY = frame >= REPLAY_PLAY_ICON && frame <= REPLAY_SLOW_ICON ? REPLAY_ICON_SHIFT_Y : 0;

                this.drawCommandIconShifted(console, loaded, control, frame, tint, shiftX + pressShift, shiftY + pressShift);
            }

            if (state == CommandCard.UNRESOLVED) {
                this.renderer.drawRectangle(console.toScreenX(control.getX()), console.toScreenY(control.getY()),
                                            control.getWidth() * scale, control.getHeight() * scale,
                                            Math.max(1, scale),
                                            UNRESOLVED_BUTTON_COLOR);
            }
        }

        if (this.layoutReplay) {
            this.drawReplayPanel(console, loaded);
        }
    }

    /**
     * Draws the replay panel and its seek bar ({@code pbrempt} under {@code pbrfull} clipped to the progress).
     */
    private void drawReplayPanel(@NotNull ConsoleLayout console, @NotNull InterfaceAssets loaded) throws IOException {
        ReplayPanel panel = this.replayPanel;

        if (panel == null) {
            return;
        }

        long now = UiClockUtils.getHostTimeMilliseconds();

        // While paused, toggle the blink after its interval and retain the toggle time.
        if (this.replayPaused && now - this.pausedBlinkTime > PAUSED_BLINK_INTERVAL) {
            this.pausedBlink     = !this.pausedBlink;
            this.pausedBlinkTime = now;
        }

        panel.update(this.replayFrame,
                     this.replayEndFrame,
                     this.replayRecordedSpeed,
                     this.replaySpeedIndex,
                     this.replayMultiplier,
                     this.replayPaused,
                     this.replayPaused && this.pausedBlink,
                     this.replaySeekTarget);

        DialogLayout buttons = console.getCommandButtons();

        for (Map.Entry<Integer, String> entry : panel.getTexts().entrySet()) {
            this.drawCodedText(console, buttons.getControl(entry.getKey()), entry.getValue());
        }

        this.drawProgressBar(console, loaded, buttons.getControl(REPLAY_PROGRESS_CONTROL), panel.getProgress());
    }

    /**
     * Draws the race console empty progress image, then its filled image clipped to the supplied percent.
     *
     * @param console the console layout
     * @param loaded  the interface assets
     * @param control the bar control
     * @param percent the control value, {@code 0..100}
     */
    private void drawProgressBar(@NotNull ConsoleLayout console, @NotNull InterfaceAssets loaded, @NotNull DialogLayout.Control control, int percent) throws IOException {
        String folder = this.getConsoleFolder();

        this.painter.begin(console, loaded);
        this.painter.drawProgressBar(this.painter.getImageFrame(folder + "pbrempt.DDS"),
                                     this.painter.getImageFrame(folder + "pbrfull.DDS"),
                                     control.getX(), control.getY(),
                                     percent);
    }

    /**
     * Updates the text-box mode prompt and draws it while its localized text is nonempty.
     *
     * @param key the {@code network.json} key: {@code strTARGET} or {@code strLOCATION}
     * @throws IOException if the dialog file is missing
     */
    public void drawModePrompt(@NotNull String key) throws IOException {
        ConsoleLayout console = this.layout;
        GluText strings = this.networkText;

        if (console == null || strings == null) {
            return;
        }

        String text = strings.get(key);
        ConsoleDialog textBox = this.getTextBoxDialog(console);
        ScrDialog dialog = textBox.getDialog();

        // The control's text, shown only while it is not empty.
        dialog.requireControl(MODE_PROMPT_CONTROL).setReplacedText(text);
        dialog.requireControl(MODE_PROMPT_CONTROL).setVisible(!text.isEmpty());

        if (text.isEmpty()) {
            return;
        }

        DialogControl prompt = ScrDialogControlUtils.findControl(dialog.getDefinition(), MODE_PROMPT_CONTROL);

        if (prompt == null) {
            return;
        }

        this.drawControlText(console,
                             textBox.getLayout().getControl(MODE_PROMPT_CONTROL),
                             dialog.getControlString(prompt, strings::get),
                             this.normalFont,
                             CTEXT_FORMAT, Tooltip.TEXT_COLOR_CODE);
    }

    /**
     * Draws minimap and menu button frames with their existing quality-dependent shifts and pressed states, then the menu label.
     */
    private void drawConsoleButtons(@NotNull ConsoleLayout console, @NotNull InterfaceAssets loaded) throws IOException {
        String sheet = this.getDialogSheet();
        // Replay and terrain buttons share a rectangle; the normal state is base + 1, disabled base, pressed base + 2.
        boolean hd = !isStandardDefinition(loaded);

        if (this.multiplayerButtons && !this.layoutReplay) {
            // Network games place Diplomacy over the terrain button and Message at its right, with the HD button shift.
            for (int[] button : new int[][]{{VISION_BUTTON, DIPLOMACY_BUTTON_BASE}, {MESSAGE_BUTTON, MESSAGE_BUTTON_BASE}}) {
                DialogLayout.Control placed = console.getMinimap().getControl(button[0]);
                boolean pressed = this.isControlPressed(console, console.getMinimap(), button[0]);

                this.drawSheetFrame(console,
                                    loaded,
                                    sheet, button[1] + (pressed ? PRESSED_BUTTON_OFFSET : NORMAL_BUTTON_OFFSET),
                                    placed.getX() - (hd ? MINIMAP_BUTTON_HD_SHIFT_X : 0), placed.getY() + (hd ? MINIMAP_BUTTON_HD_SHIFT_Y : 0),
                                    WHITE_RGBA);
            }
        } else {
            DialogLayout.Control terrain = console.getMinimap().getControl(this.layoutReplay ? VISION_BUTTON : TERRAIN_BUTTON);
            boolean terrainPressed = this.isControlPressed(console, console.getMinimap(), terrain.getIdentifier());

            // In HD the frame of buttons 2/4 is drawn at (-1, +4); the text is not moved.
            this.drawSheetFrame(console,
                                loaded,
                                sheet, (this.layoutReplay ? REPLAY_TERRAIN_BUTTON_BASE : TERRAIN_BUTTON_BASE) +
                                       (terrainPressed ? PRESSED_BUTTON_OFFSET : NORMAL_BUTTON_OFFSET),
                                terrain.getX() - (hd ? MINIMAP_BUTTON_HD_SHIFT_X : 0), terrain.getY() + (hd ? MINIMAP_BUTTON_HD_SHIFT_Y : 0),
                                WHITE_RGBA);
        }

        // Menu button: style 2, normal frame 1; the control's left edge moves +2 on the Zerg console or in a replay (right edge
        // kept); the frame draw adds -2 only in HD on the Zerg console.
        DialogLayout.Control menu = console.getMenu().getControl(MENU_BUTTON);
        float menuX = menu.getX() + (this.layoutRace == ZERG_RACE || this.layoutReplay ? MENU_SHIFT_X : 0) +
                      (!isStandardDefinition(loaded) && this.layoutRace == ZERG_RACE ? MENU_HD_SHIFT_X : 0);
        ScrDialog menuDialog = console.getMenuDialog();
        boolean menuHovered = menuDialog.getHoveredId() == menu.getIdentifier();
        boolean menuPressed = menuHovered && menuDialog.getPressedId() == menu.getIdentifier();

        this.drawSheetFrame(console, loaded, sheet, menuPressed ? PRESSED_BUTTON_OFFSET : NORMAL_BUTTON_OFFSET, menuX, menu.getY(), WHITE_RGBA);

        GluText strings = this.controlText;

        if (strings != null) {
            String label = InterfaceText.stripHotkey(strings.get("stat_f10-1"));

            // Shift the text rectangle and pen by the console type, then center the label in both axes.
            ConsoleType.MenuTextShift shift = isStandardDefinition(loaded) ? ConsoleType.MenuTextShift.SD
                                                                           : ConsoleType.of(this.layoutRace, this.layoutReplay).getMenuTextShift();
            int pen = shift.getPen(menuPressed);
            float controlLeft = menu.getX() + (this.layoutRace == ZERG_RACE || this.layoutReplay ? MENU_SHIFT_X : 0);
            float controlRight = menu.getX() + menu.getWidth() - 1;
            float startX = controlLeft + shift.getHorizontalShift() + pen;
            float rightX = controlRight + shift.getHorizontalShift();
            float top = menu.getY() + shift.getVerticalShift() + pen;
            float bottom = menu.getY() + menu.getHeight() - 1 + shift.getVerticalShift();

            InterfaceText.draw(this.renderer,
                               console,
                               this.smallFont,
                               label,
                               startX, top,
                               rightX - startX + 1, bottom - top + 1,
                               MENU_BUTTON_FORMAT,
                               menuHovered ? DialogPainter.CODE_HIGHLIGHT : DialogPainter.CODE_BUTTON,
                               false);
        }
    }

    /**
     * Draws the selected chat label and edit text, attaching the shared caret timer when the edit model changes.
     *
     * @param edit the chat edit text (the shared edit model of the edit control)
     * @param time the interface clock in milliseconds
     * @throws IOException if the dialog file is missing
     */
    public void drawChatBox(@NotNull EditText edit, long time) throws IOException {
        ConsoleLayout console = this.layout;
        InterfaceFont font = this.normalFont;
        GluText strings = this.controlText;

        if (console == null || strings == null) {
            return;
        }

        ConsoleDialog textBox = this.getTextBoxDialog(console);
        ScrDialog dialog = textBox.getDialog();

        dialog.setFocused(dialog.findControl(CHAT_EDIT_CONTROL));
        dialog.requireControl(MODE_PROMPT_CONTROL).setVisible(false);

        DialogControl labelControl = ScrDialogControlUtils.findControl(dialog.getDefinition(), this.chatLabelControl);

        if (labelControl == null) {
            return;
        }

        DialogLayout.Control label = textBox.getLayout().getControl(this.chatLabelControl);
        DialogLayout.Control editControl = textBox.getLayout().getControl(CHAT_EDIT_CONTROL);
        String prompt = dialog.getControlString(labelControl, strings::get);

        InterfaceText.drawAt(this.renderer, console,
                             font, prompt,
                             label.getX(), label.getY(), label.getX() + label.getWidth() - 1,
                             DialogPainter.CODE_TEXT);

        float editLeft = editControl.getX() + (float) Math.ceil(font.measure(prompt, true) + font.measure(" ", true));

        // The caret blinks on the edit timer of the shared timer list (timer 2).
        if (this.chatEdit != edit) {
            if (this.chatEdit != null) {
                this.chatEdit.detach();
            }

            this.chatEdit = edit;

            edit.attach(this.timers, time);
        }

        // EDITTEXT through the shared edit draw; the pen is the control left, which moved past the label (pad 0), and in game
        // (resource set 2) the end of the text stays visible.
        DialogControl editDefinition = ScrDialogControlUtils.findControl(dialog.getDefinition(), CHAT_EDIT_CONTROL);
        InterfaceAssets loaded = this.assets;

        if (editDefinition == null || loaded == null) {
            return;
        }

        this.painter.begin(console, loaded);
        ControlPainterUtils.drawEditText(this.painter,
                                         this.painter.getControlFontSlot(editDefinition),
                                         edit,
                                         EditText.ScrollMode.END,
                                         editLeft, editControl.getY(),
                                         editControl.getX() + editControl.getWidth() - 1, editControl.getHeight(),
                                         console.getInterfaceWidth() - 1,
                                         dialog.getFocusedId() == CHAT_EDIT_CONTROL,
                                         DialogPainter.CODE_TEXT);
    }

    /**
     * Returns the {@code allyfltr} dialog with a box ({@code 13..}) and label ({@code -10..}) shown for each player row of the open list.
     */
    private @NotNull ScrDialog getVisionPopup() throws IOException {
        ScrDialog dialog = this.visionPopup;

        if (dialog == null) {
            dialog           = new ScrDialog(ScrDialogImportUtils.load(this.storage, "allyfltr"));
            this.visionPopup = dialog;

            // The default OK button shows its focus outline while no control has focus.
            dialog.setDefaultButtonFocus(true);
        }

        int rows = this.visionDialog.getRows().size();

        for (int row = 0; row < VISION_ROWS; row++) {
            setVisible(dialog.findControl(VISION_FIRST_BOX + row), row < rows);
            setVisible(dialog.findControl(FIRST_PLAYER_LABEL - row), row < rows);
        }

        return dialog;
    }

    /**
     * Draws the vision dialog: the popup frame of {@code dlgs\tile.dds.grp} (three bands of left, stretched middle and right pieces
     * at ({@code 1},{@code 1},{@code 1},{@code 0.75})), the title (control {@code 26}, {@code font16}, code {@code 2}), a name label
     * ({@code font16}) and a vision checkbox (dialog sheet frames {@code 12} off, {@code 14} on) per player, "Reveal Entire Map"
     * ({@code 25}) and the OK / Cancel {@code 3}-slice buttons (bases {@code 103} and {@code 121}, the normal frame {@code base + 3};
     * {@code font16x}, code {@code 3}).
     */
    private void drawVisionDialog(@NotNull ConsoleLayout console, @NotNull InterfaceAssets loaded) throws IOException {
        ScrDialog popup = this.getVisionPopup();
        DialogLayout dialog = this.getPopupLayout(popup, console);
        int hovered = popup.getHoveredId();

        this.drawPopupFrame(console, loaded, dialog);

        GluText strings = this.gluText;
        GluText controls = this.controlText;
        String sheet = this.getDialogSheet();

        this.painter.begin(console, loaded);

        DialogDefinition definition = popup.getDefinition();
        DialogControl title = ScrDialogControlUtils.findControl(definition, VISION_TITLE);

        if (title != null) {
            ControlPainterUtils.drawText(this.painter,
                                         title,
                                         dialog.getControl(VISION_TITLE).getX(), dialog.getControl(VISION_TITLE).getY(),
                                         strings == null ? "" : strings.get("visionDialogTitle"),
                                         true,
                                         DialogPainter.CODE_TEXT);
        }

        // The vision column's two-line header (RTEXT 28, 29); event 14/10 hides only the alliance header 27 in a replay.
        for (int header : VISION_HEADERS) {
            DialogControl control = ScrDialogControlUtils.findControl(definition, header);

            if (control != null && controls != null) {
                ControlPainterUtils.drawText(this.painter,
                                             control,
                                             dialog.getControl(header).getX(), dialog.getControl(header).getY(),
                                             popup.getControlString(control, controls::get),
                                             true,
                                             DialogPainter.CODE_TEXT);
            }
        }

        for (int row = 0; row < this.visionDialog.getRows().size(); row++) {
            this.drawPlayerLabel(console, dialog.getControl(FIRST_PLAYER_LABEL - row), this.visionDialog.getRows().get(row));

            // The vision boxes (CHECKBOX 13..): the dialog's checked state mirrors the vision dialog.
            this.drawVisionCheckBox(popup, dialog, VISION_FIRST_BOX + row, this.visionDialog.isChecked(row), "", hovered, sheet);
        }

        this.drawVisionCheckBox(popup,
                                dialog,
                                VISION_REVEAL_BOX,
                                this.visionDialog.isRevealChecked(),
                                strings == null ? "" : strings.get("visionRevealAll"),
                                hovered,
                                sheet);
        this.drawPopupButtons(popup, dialog, "allyfltr-1", "allyfltr-2", sheet);
    }

    /**
     * Draws a player row label of the {@code allyfltr} dialog: a filled {@code 12x12} swatch in the player's minimap color at the
     * control's left, {@code 4} pixels down for these {@code 20} pixel controls, and the name, whose pen and clip rectangle both move
     * right by the control height plus {@code 9}.
     *
     * @param console the console layout
     * @param label   the placed label control
     * @param entry   the player of the row
     */
    private void drawPlayerLabel(@NotNull ConsoleLayout console, DialogLayout.@NotNull Control label, VisionDialog.@NotNull Row entry) {
        float scale = console.getScale();

        if (entry.getColor() != 0) {
            this.renderer.fillRectangle(console.toScreenX(label.getX()),
                                        console.toScreenY(label.getY() + (label.getHeight() - SWATCH_CENTER_HEIGHT) / 2),
                                        SWATCH_SIZE * scale, SWATCH_SIZE * scale,
                                        entry.getColor());
        }

        float textX = label.getX() + label.getHeight() + PLAYER_LABEL_GAP;
        float textY = label.getY();
        float textRight = label.getX() + label.getWidth() - 1 + label.getHeight() + PLAYER_LABEL_GAP;

        InterfaceText.drawAt(this.renderer, console, this.bigFont, entry.getName(), textX, textY, textRight, DialogPainter.CODE_TEXT);
    }

    private void drawVisionCheckBox(@NotNull ScrDialog popup,
                                    @NotNull DialogLayout dialog,
                                    int identifier,
                                    boolean checked,
                                    @NotNull String text,
                                    int hovered,
                                    @NotNull String sheet) throws IOException {
        DialogControl control = ScrDialogControlUtils.findControl(popup.getDefinition(), identifier);

        if (control == null) {
            return;
        }

        popup.requireControl(identifier).setChecked(checked);

        this.drawCheckBox(popup, dialog, control, text, hovered, sheet, true);
    }

    /**
     * Draws the placed popup frame and its visible controls, routing a retained list model through its list drawing path.
     *
     * @param dialog the open dialog
     * @throws IOException if an image cannot be loaded
     */
    public void drawPopup(@NotNull ScrDialog dialog) throws IOException {
        ConsoleLayout console = this.layout;
        InterfaceAssets loaded = this.assets;

        if (console == null || loaded == null) {
            return;
        }

        DialogLayout placed = this.getPopupLayout(dialog, console);

        this.drawPopupFrame(console, loaded, placed);

        String sheet = this.getDialogSheet();
        GluText strings = this.controlText;
        Function<String, String> controlTexts = key -> strings == null ? "" : strings.get(key);
        int hovered = dialog.getHoveredId();

        // The shared control draw of the type table (push buttons in the 3-slice style 4, base 112).
        this.painter.begin(console, loaded);

        List<DialogControl> controls = dialog.getDefinition().getControls();

        for (int index = 0; index < controls.size(); index++) {
            DialogControl control = controls.get(index);
            DialogLayout.Control placedControl = placed.getControls().get(index);
            ListBox list = this.popupList;

            if (ScrDialogControlUtils.getType(control) == ScrControlType.LISTBOX && list != null && list.getDefinition() == control) {
                list.update(this.painter);
                list.drawAt(this.painter, placedControl.getX(), placedControl.getY());

                continue;
            }

            ControlPainterUtils.draw(this.painter,
                                     dialog,
                                     control,
                                     placedControl.getX(),
                                     placedControl.getY(),
                                     sheet,
                                     dialog.getDrawnString(control, controlTexts),
                                     hovered,
                                     POPUP_BUTTON_BASE,
                                     true);
        }
    }

    /**
     * Creates a {@link ListBox} instance for a popup's list control (type {@code 12}) on the console's dialog sheet, attaches it in the
     * place of the control the popup's dialog built ({@link ScrDialog#attach}) and draws it with the popup ({@link #drawPopup}) until
     * {@link #closePopupList}.
     * <p>
     * The list is transparent, without row bars or border, as the message history and keystroke help popups make their lists.
     *
     * @param dialog  the popup's dialog
     * @param control the list control, of the dialog's definition
     * @return the created {@link ListBox} instance, now the dialog's control of the definition
     * @throws IllegalArgumentException if the dialog has neither the definition nor a control with its id
     */
    public @NotNull ListBox openPopupList(@NotNull ScrDialog dialog, @NotNull DialogControl control) {
        ListBox list = dialog.attach(new ListBox(control, this.getDialogSheet()));

        list.setTransparent(true);

        this.popupList = list;

        return list;
    }

    /**
     * Stops drawing the popup's list.
     */
    public void closePopupList() {
        this.popupList = null;
    }

    /**
     * Measures a text in a popup control's font ({@link DialogPainter#measure}).
     *
     * @param control the control
     * @param text    the text
     * @return the width in interface pixels
     */
    public float measurePopupText(@NotNull DialogControl control, @NotNull String text) {
        return this.painter.measure(this.painter.getControlFontSlot(control), text);
    }

    /**
     * Scrolls the popup's list as its procedure does for the wheel and the Up / Down keys.
     *
     * @param list  the popup's list
     * @param wheel {@code true} for a wheel notch, {@code false} for an arrow key
     * @param down  {@code true} toward the end
     */
    public void scrollPopupList(@NotNull ListBox list, boolean wheel, boolean down) {
        if (wheel) {
            list.scrollByWheel(this.painter, down);
        } else {
            list.step(this.painter, down);
        }
    }

    /**
     * Moves the selection of the popup's list for a navigation key.
     *
     * @param list the popup's list
     * @param key  the key
     */
    public void pressPopupListKey(@NotNull ListBox list, @NotNull DialogKey key) {
        list.pressKey(this.painter, key);
    }

    /**
     * Returns the {@code allyfltr} dialog of a game: per row a name label ({@code -10..}), an ally box ({@code 1..}) and a vision box
     * ({@code 13..}) for a row of another force, Allied Victory ({@code 25}), the column titles ({@code 27..29}).
     */
    private @NotNull ScrDialog getAlliancePopup() throws IOException {
        ScrDialog dialog = this.alliancePopup;

        if (dialog == null) {
            dialog             = new ScrDialog(ScrDialogImportUtils.load(this.storage, "allyfltr"));
            this.alliancePopup = dialog;

            // OK (DEFPUSHBUTTON -2) shows the focus outline while no control has the focus.
            dialog.setDefaultButtonFocus(true);
        }

        List<AllianceDialog.Row> rows = this.allianceDialog.getRows();

        for (int row = 0; row < AllianceDialog.ROWS; row++) {
            boolean shown = row < rows.size();
            boolean boxes = shown && rows.get(row).hasBoxes();

            setVisible(dialog.findControl(FIRST_PLAYER_LABEL - row), shown);
            setCheckBox(dialog.findControl(AllianceDialog.FIRST_ALLY + row), boxes, boxes && rows.get(row).isAllyEnabled(),
                        boxes && this.allianceDialog.isAllied(row));
            setCheckBox(dialog.findControl(AllianceDialog.FIRST_VISION + row), boxes, boxes && rows.get(row).isVisionEnabled(),
                        boxes && this.allianceDialog.isVisionShared(row));
        }

        ScrControl alliedVictory = dialog.findControl(AllianceDialog.ALLIED_VICTORY);

        if (alliedVictory != null) {
            alliedVictory.setEnabled(this.allianceDialog.isAlliedVictoryEnabled());
            alliedVictory.setChecked(this.allianceDialog.isAlliedVictory());
        }

        return dialog;
    }

    /**
     * Returns the {@code msgfltr} dialog with its radio buttons' state.
     */
    private @NotNull ScrDialog getMessagingPopup() throws IOException {
        ScrDialog dialog = this.messagingPopup;

        if (dialog == null) {
            DialogDefinition definition = ScrDialogImportUtils.load(this.storage, "msgfltr");

            dialog              = new ScrDialog(definition);
            this.messagingPopup = dialog;

            DialogControl list = ScrDialogControlUtils.findControl(definition, MessagingDialog.LIST);

            if (list != null) {
                this.messagingList = dialog.attach(new ComboBox(list, this.getDialogSheet()));
            }
        }

        for (int identifier : new int[]{MessagingDialog.PLAYER, MessagingDialog.EVERYONE, MessagingDialog.ALLIES}) {
            ScrControl button = dialog.findControl(identifier);

            if (button != null) {
                button.setEnabled(this.messagingDialog.isEnabled(identifier));
                button.setChecked(this.messagingDialog.isChecked(identifier));
            }
        }

        ScrControl listControl = dialog.findControl(MessagingDialog.LIST);

        if (listControl != null) {
            listControl.setEnabled(this.messagingDialog.isEnabled(MessagingDialog.LIST));
        }

        ComboBox list = this.messagingList;

        if (list != null) {
            List<VisionDialog.Row> rows = this.messagingDialog.getRows();
            boolean same = list.getItemCount() == rows.size();

            for (int index = 0; same && index < rows.size(); index++) {
                same = list.getItemText(index).equals(rows.get(index).getName());
            }

            if (!same) {
                list.clear();

                for (VisionDialog.Row row : rows) {
                    list.addItem(row.getName(), row.getPlayer());
                }
            }

            if (!rows.isEmpty()) {
                list.select(this.messagingDialog.getListIndex());
            }

            list.setInputEnabled(this.messagingDialog.isEnabled(MessagingDialog.LIST));
        }

        return dialog;
    }

    /**
     * Activates a control of the alliance dialog.
     *
     * @param identifier the released control
     * @return {@code true} if Accept closed the dialog, {@code false} otherwise
     */
    public boolean activateAllianceControl(int identifier) {
        if (identifier == AllianceDialog.ACCEPT) {
            this.allianceDialog.close();

            return true;
        }

        if (identifier == AllianceDialog.CANCEL) {
            this.allianceDialog.close();
        } else {
            this.allianceDialog.press(identifier);
        }

        return false;
    }

    /**
     * Activates a control of the Messaging dialog: the radio buttons check, the list opens or closes, Accept and Cancel close.
     *
     * @param identifier the released control
     * @return the accepted recipient byte, or {@code -1}
     * @throws IOException if the dialog cannot be loaded
     */
    public int activateMessagingControl(int identifier) throws IOException {
        ComboBox list = this.messagingList;

        switch (identifier) {
            case MessagingDialog.ACCEPT -> {
                if (list != null) {
                    list.setOpen(false);
                }

                return this.messagingDialog.accept();
            }
            case MessagingDialog.CANCEL -> {
                if (list != null) {
                    list.setOpen(false);
                }

                this.messagingDialog.cancel();
            }
            case MessagingDialog.LIST -> {
                if (list != null && this.messagingDialog.isEnabled(MessagingDialog.LIST)) {
                    list.setOpen(this.painter, !list.isOpen(), 0, ConsoleLayout.INTERFACE_HEIGHT);
                }
            }
            default -> this.messagingDialog.select(identifier);
        }

        return -1;
    }

    /**
     * Takes a press on the Messaging dialog's open list: the row under the point is selected and the list closes.
     *
     * @param screenX the screen x
     * @param screenY the screen y
     * @return {@code true} if the open list took the press, {@code false} otherwise
     * @throws IOException if the dialog cannot be loaded
     */
    public boolean pressMessagingList(float screenX, float screenY) throws IOException {
        ConsoleLayout console = this.layout;
        ComboBox list = this.messagingList;

        if (console == null || list == null || !list.isOpen() || !this.messagingDialog.isOpen()) {
            return false;
        }

        DialogLayout.Control placed = this.getPopupLayout(this.getMessagingPopup(), console).getControl(MessagingDialog.LIST);
        int index = list.findItem(this.painter, console.toInterfaceX(screenX) - placed.getX(), console.toInterfaceY(screenY) - placed.getY());

        if (index != ComboBox.NO_SELECTION) {
            this.messagingDialog.selectListIndex(index);
        }

        list.setOpen(false);

        return true;
    }

    /**
     * Draws the alliance dialog: the popup frame, the title ({@code 26}) and column titles ({@code 27} "Allies", {@code 28} / {@code 29} "Shared"
     * "Vision"), per row the player's swatch and name and, for another force, the ally and vision boxes, Allied Victory
     * and Accept / Cancel.
     */
    private void drawAllianceDialog(@NotNull ConsoleLayout console, @NotNull InterfaceAssets loaded) throws IOException {
        ScrDialog popup = this.getAlliancePopup();
        DialogLayout dialog = this.getPopupLayout(popup, console);
        int hovered = popup.getHoveredId();

        this.drawPopupFrame(console, loaded, dialog);

        GluText controls = this.controlText;
        String sheet = this.getDialogSheet();

        this.painter.begin(console, loaded);

        DialogDefinition definition = popup.getDefinition();

        for (int title : new int[]{VISION_TITLE, ALLIANCE_TITLE, VISION_HEADER_LEFT, VISION_HEADER_RIGHT}) {
            DialogControl control = ScrDialogControlUtils.findControl(definition, title);

            if (control != null && controls != null) {
                ControlPainterUtils.drawText(this.painter,
                                             control,
                                             dialog.getControl(title).getX(), dialog.getControl(title).getY(),
                                             popup.getControlString(control, controls::get),
                                             true,
                                             DialogPainter.CODE_TEXT);
            }
        }

        List<AllianceDialog.Row> rows = this.allianceDialog.getRows();

        for (int row = 0; row < rows.size(); row++) {
            this.drawPlayerLabel(console, dialog.getControl(FIRST_PLAYER_LABEL - row), rows.get(row).getPlayer());

            if (!rows.get(row).hasBoxes()) {
                continue;
            }

            this.drawPopupBox(popup, dialog, AllianceDialog.FIRST_ALLY + row, "", hovered, sheet);
            this.drawPopupBox(popup, dialog, AllianceDialog.FIRST_VISION + row, "", hovered, sheet);
        }

        this.drawPopupBox(popup,
                          dialog,
                          AllianceDialog.ALLIED_VICTORY,
                          controls == null ? "" : popup.getControlString(ScrDialogControlUtils.findControl(definition, AllianceDialog.ALLIED_VICTORY),
                                                                         controls::get),
                          hovered,
                          sheet);
        this.drawPopupButtons(popup, dialog, "allyfltr-1", "allyfltr-2", sheet);
    }

    /**
     * Draws the Messaging dialog: the popup frame, the title ({@code -1}), the radio buttons {@code 1..3}, the player list ({@code 4}) and
     * Accept / Cancel; the open list over the dialog.
     */
    private void drawMessagingDialog(@NotNull ConsoleLayout console, @NotNull InterfaceAssets loaded) throws IOException {
        ScrDialog popup = this.getMessagingPopup();
        DialogLayout dialog = this.getPopupLayout(popup, console);
        int hovered = popup.getHoveredId();

        this.drawPopupFrame(console, loaded, dialog);

        GluText controls = this.controlText;
        String sheet = this.getDialogSheet();

        this.painter.begin(console, loaded);

        DialogDefinition definition = popup.getDefinition();
        DialogControl title = ScrDialogControlUtils.findControl(definition, MESSAGING_TITLE);

        if (title != null && controls != null) {
            ControlPainterUtils.drawText(this.painter,
                                         title,
                                         dialog.getControl(MESSAGING_TITLE).getX(), dialog.getControl(MESSAGING_TITLE).getY(),
                                         popup.getControlString(title, controls::get),
                                         true,
                                         DialogPainter.CODE_TEXT);
        }

        for (int radio : new int[]{MessagingDialog.PLAYER, MessagingDialog.EVERYONE, MessagingDialog.ALLIES}) {
            DialogControl control = ScrDialogControlUtils.findControl(definition, radio);

            this.drawPopupBox(popup,
                              dialog,
                              radio,
                              control == null || controls == null ? "" : popup.getControlString(control, controls::get),
                              hovered,
                              sheet);
        }

        ComboBox list = this.messagingList;
        DialogLayout.Control placed = dialog.getControl(MessagingDialog.LIST);

        if (list != null) {
            list.draw(this.painter, placed.getX(), placed.getY(), hovered == MessagingDialog.LIST);
        }

        this.drawPopupButtons(popup, dialog, "MsgFltr-1", "MsgFltr-2", sheet);

        if (list != null) {
            list.drawList(this.painter, placed.getX(), placed.getY());
        }
    }

    private void drawPopupBox(@NotNull ScrDialog popup,
                              @NotNull DialogLayout dialog,
                              int identifier,
                              @NotNull String text,
                              int hovered,
                              @NotNull String sheet) throws IOException {
        DialogControl control = ScrDialogControlUtils.findControl(popup.getDefinition(), identifier);

        if (control == null) {
            return;
        }

        this.drawCheckBox(popup, dialog, control, text, hovered, sheet, popup.isEnabled(control));
    }

    /**
     * Draws a check box or radio button of a popup at its placed position, pressed while the pointer holds it.
     */
    private void drawCheckBox(@NotNull ScrDialog popup,
                              @NotNull DialogLayout dialog,
                              @NotNull DialogControl control,
                              @NotNull String text,
                              int hovered,
                              @NotNull String sheet,
                              boolean enabled) throws IOException {
        int identifier = control.getId();
        DialogLayout.Control placed = dialog.getControl(identifier);
        boolean over = hovered == identifier;

        CheckBoxUtils.draw(this.painter,
                           sheet,
                           control,
                           placed.getX(),
                           placed.getY(),
                           text,
                           popup.requireControl(identifier).isChecked(),
                           enabled,
                           over && popup.getPressedId() == identifier,
                           over);
    }

    /**
     * Draws Accept ({@code -2}) and Cancel ({@code -3}): {@code 3}-slice push buttons of style bases {@code 103} and {@code 121}.
     */
    private void drawPopupButtons(@NotNull ScrDialog popup,
                                  @NotNull DialogLayout dialog,
                                  @NotNull String acceptKey,
                                  @NotNull String cancelKey,
                                  @NotNull String sheet) throws IOException {
        GluText controls = this.controlText;
        int hovered = popup.getHoveredId();
        int[][] buttons = {{VISION_OK, VISION_OK_BASE}, {VISION_CANCEL, VISION_CANCEL_BASE}};

        for (int[] button : buttons) {
            DialogControl control = ScrDialogControlUtils.findControl(popup.getDefinition(), button[0]);

            if (control == null) {
                continue;
            }

            DialogLayout.Control placed = dialog.getControl(button[0]);
            String text = controls == null ? "" : controls.get(button[0] == VISION_OK ? acceptKey : cancelKey);

            // The shared push button draw: the 3-slice style and the default button's focus outline.
            ControlPainterUtils.draw(this.painter, popup, control, placed.getX(), placed.getY(), sheet, text, hovered, button[1], true);
        }
    }

    /**
     * Activates a control of the vision dialog ({@code allyfltr}): vision boxes ({@code 13..}), "Reveal Entire Map" ({@code 25}), OK ({@code -2})
     * and Cancel ({@code -3}).
     *
     * @param identifier the released control
     * @return {@code true} if OK applied the vision through {@link VisionDialog#accept}, {@code false} otherwise
     */
    public boolean activateVisionControl(int identifier) {
        if (identifier >= VISION_FIRST_BOX && identifier < VISION_FIRST_BOX + this.visionDialog.getRows().size()) {
            this.visionDialog.toggle(identifier - VISION_FIRST_BOX);
        } else if (identifier == VISION_REVEAL_BOX) {
            this.visionDialog.toggleReveal();
        } else if (identifier == VISION_OK) {
            this.visionDialog.accept();
            this.minimapRefresh = true;

            return true;
        } else if (identifier == VISION_CANCEL) {
            this.visionDialog.cancel();
        }

        return false;
    }

    /**
     * Updates the control state of the console dialogs from what they show, as their procedures do: the command buttons
     * that hold a button (enabled when the card enables them), the cargo slots ({@code 18..32}) and group grid ({@code 33..44}) of the
     * selection panel that show a unit (not in the replay console), and the minimap button of the console kind (terrain
     * {@code 2} in a game, View Players {@code 4} in a replay; the unused {@code 3} hidden).
     */
    private void updateDialogState(@NotNull ConsoleModel model, @NotNull ConsoleLayout console, boolean replay) {
        ScrDialog buttons = console.getDialog(console.getCommandButtons()).getDialog();
        CommandCard card = model.getCommandCard();

        for (int slot = 0; slot < CommandCard.SLOT_COUNT; slot++) {
            ScrControl button = buttons.findControl(slot + 1);

            if (button != null) {
                button.setVisible(card.getButton(slot) != null);
                button.setEnabled(card.getState(slot) == CommandCard.ENABLED);
            }
        }

        ScrDialog panel = console.getDialog(console.getSelectionPanel()).getDialog();

        for (int identifier = FIRST_CARGO_SLOT; identifier <= LAST_GROUP_SLOT; identifier++) {
            StatusPanel.Control shown = this.statusPanel.getControl(identifier);

            setVisible(panel.findControl(identifier), !replay && shown != null && shown.getUnit() != null);
        }

        // The icon buttons (queue slots 2..6, stat icons 9..12, research 15, egg/add-on 17) are shown with
        // their icon; their activation does nothing in a replay.
        for (int identifier : STATUS_ICON_BUTTONS) {
            StatusPanel.Control shown = this.statusPanel.getControl(identifier);

            ScrControl icon = panel.findControl(identifier);

            if (icon != null) {
                icon.setVisible(shown != null);
                // Answers the hit test (14/4) while visible, also on the LTEXT stat icons 9..12.
                icon.setHitTestable();
            }
        }

        ScrDialog minimap = console.getDialog(console.getMinimap()).getDialog();
        boolean multiplayer = !replay && this.multiplayerButtons;

        setVisible(minimap.findControl(TERRAIN_BUTTON), !replay && !multiplayer);
        setVisible(minimap.findControl(MESSAGE_BUTTON), multiplayer);
        // A replay's minimap has no buttons: the side bar opens the vision dialog (ReplaySideBar.Button.DIPLOMACY).
        setVisible(minimap.findControl(VISION_BUTTON), multiplayer);
    }

    /**
     * Returns the cached definition layout at its current console popup position.
     */
    private @NotNull DialogLayout getPopupLayout(@NotNull ScrDialog dialog, @NotNull ConsoleLayout console) {
        return this.placePopup(this.popupLayouts.computeIfAbsent(dialog, key -> DialogLayout.of(key.getDefinition())), console);
    }

    /**
     * Centers the popup in the interface and game-view extension, caching moved layouts by identity and position.
     */
    private @NotNull DialogLayout placePopup(@NotNull DialogLayout dialog, @NotNull ConsoleLayout console) {
        int left = InterfaceTransform.getCenteredDialogLeft(console.getInterfaceWidth());
        // A popup is centered in the game view: in a window taller than the interface it moves up by half the view's
        // extension, in whole interface rows so that drawing and hit tests share the placement.
        int top = -Math.round(console.getViewExtension() / 2 / console.getScale());

        if (left <= 0 && top == 0) {
            return dialog;
        }

        String key = System.identityHashCode(dialog) + "#" + left + "," + top;
        DialogLayout placed = this.placedDialogs.get(key);

        if (placed == null) {
            if (this.placedDialogs.size() > PLACED_DIALOG_LIMIT) {
                this.placedDialogs.clear();
            }

            placed = dialog.createMovedLayout(left, top);

            this.placedDialogs.put(key, placed);
        }

        return placed;
    }

    /**
     * Draws the popup frame: {@code dlgs\tile} in three bands of {@code 8}-pixel corners and stretched edges, color
     * ({@code 1}, {@code 1}, {@code 1}, {@code 0.75}).
     */
    private void drawPopupFrame(@NotNull ConsoleLayout console, @NotNull InterfaceAssets loaded, @NotNull DialogLayout dialog) throws IOException {
        // The shared popup frame.
        this.painter.begin(console, loaded);
        this.painter.drawPopupFrame(dialog.getX(), dialog.getY(), dialog.getWidth(), dialog.getHeight());
    }

    /**
     * Draws text within the supplied control rectangle.
     */
    private void drawControlText(@NotNull InterfaceTransform console,
                                 DialogLayout.@NotNull Control control,
                                 @NotNull String text,
                                 @NotNull InterfaceFont font,
                                 int format, int colorCode) {
        InterfaceText.draw(this.renderer,
                           console,
                           font,
                           text,
                           control.getX(), control.getY(),
                           control.getWidth(), control.getHeight(),
                           format,
                           colorCode,
                           false);
    }

    /**
     * Supplies the game's message-line model with the local text layout ({@code font10} word wrap against the interface width minus
     * {@code 20}) and draws its lines from now on. The localized texts, host clock and subtitle option are session inputs that
     * {@code ClientSession} installs before the first frame.
     *
     * @param presentation the presentation state of the game whose message lines are drawn
     */
    public void installMessageLines(@NotNull ScrPresentation presentation) {
        MessageLines lines = presentation.getInterfaceLink().getMessageLines();
        MessageLines.TextLayout layout = this.getMessageLayout();

        lines.setLayout(layout);

        this.messageLines = lines;
    }

    /**
     * Draws the message area ({@link MessageAreaUtils}) of the installed message lines and expires old lines (run from the interface loop).
     */
    private void drawMessages(@NotNull ConsoleLayout console) {
        MessageLines lines = this.messageLines;
        InterfaceFont font = this.normalFont;

        if (lines == null) {
            return;
        }

        lines.expire(MessageAreaUtils.createHostClock().getAsInt());

        float width = console.getInterfaceWidth();
        int next = lines.getNextLine();
        int oldest = next < MessageLines.ROTATING_LINES ? next : 0;

        for (int row = 0; row < MessageLines.ROTATING_LINES; row++) {
            int line = (oldest + row) % MessageLines.ROTATING_LINES;
            byte[] bytes = lines.getLine(line);

            if (bytes.length == 0) {
                continue;
            }

            // A row is one wrapped piece, drawn left aligned in its clip x 10 .. W - 10; its inline codes 0x12 / 0x13 align
            // the rest of the row against W - 10 (InterfaceText.computeAlignmentShifts).
            float y = MessageAreaUtils.FIRST_ROW + MessageAreaUtils.ROW_STEP * row;
            // A chat line starts in the sender's player color (its color struct), others in their style code.
            int color = lines.getColor(line) != 0 ? lines.getColor(line) : InterfaceFont.getCodeColor(lines.getStyle(line));

            InterfaceText.drawColored(this.renderer,
                                      console,
                                      font,
                                      LegacyTextUtils.decode(bytes, 0, bytes.length),
                                      MessageAreaUtils.MARGIN, y,
                                      width - MESSAGE_WIDTH_MARGIN + 1, MessageAreaUtils.ROW_STEP,
                                      LTEXT_FORMAT,
                                      color,
                                      InterfaceText.GAME_CODE_COLORS,
                                      true);
        }

        byte[] status = lines.getLine(MessageLines.SYSTEM_LINE);

        if (status.length > 0) {
            InterfaceText.draw(this.renderer, console,
                               font, LegacyTextUtils.decode(status, 0, status.length),
                               0, MessageAreaUtils.STATUS_ROW, console.getInterfaceWidth(), MessageAreaUtils.ROW_STEP,
                               CTEXT_FORMAT,
                               MessageLines.STYLE_SYSTEM_LINE,
                               false);
        }
    }

    /**
     * Shows a status line (placement and order errors): line {@code 12} of the message model, white, {@code 7} seconds.
     *
     * @param text the text
     */
    public void showStatus(@NotNull String text) {
        MessageLines lines = this.messageLines;

        if (lines != null) {
            lines.writeSystemLine(text.getBytes(StandardCharsets.UTF_8), MessageAreaUtils.createHostClock().getAsInt());
        }
    }

    /**
     * Chooses the selection or transmission portrait, advances its clip and static timer, then draws video, static and overlay in order.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private void drawPortrait(@NotNull ConsoleModel model, @NotNull ConsoleLayout console) throws IOException {
        PortraitPlayer player = this.portraitPlayer;

        if (player == null || this.portraitMode == PORTRAITS_NONE) {
            this.portraitShown = false;

            return;
        }

        player.setPrefix(this.hdScale == 0 ? "SD/portrait/" : this.hdScale == HdConsoleLayout.HD2_SCALE ? "HD2/portrait/" : "portrait/");
        player.setSkin(this.hdScale == 0 ? null : this.skin);
        player.setAddedGraphics(model.getView().requireGameData().getAddedGraphics());

        Unit active = model.getSelection().getActiveUnit();
        int portrait = active == null || !model.getView().requireGameData().getUnitTypes().hasEntry(active.getUnitId())
                       ? -1
                       : model.getView().requireGameData().getUnitTypes().getPortrait(active.getUnitId());
        long clock = UiClockUtils.getHostTimeMilliseconds();
        // The unit key: the active unit object, or none (null) during a transmission.
        Object unitKey = active;

        if (this.transmissionType >= 0 && this.transmissionType < ScrUnitIds.NONE) {
            if (clock < this.transmissionEnd || player.isTalking()) {
                portrait = model.getView().requireGameData().getUnitTypes().getPortrait(this.transmissionType);
                unitKey  = null;

                if (this.transmissionStart && portrait != NO_PORTRAIT) {
                    player.show(null, portrait, clock);
                    player.talk(clock, this.transmissionEnd - clock);

                    this.transmissionStart = false;
                }
            } else {
                this.transmissionType = -1;
            }
        }

        int shownPortrait = portrait == NO_PORTRAIT ? -1 : portrait;

        player.show(unitKey, shownPortrait, clock);

        if (shownPortrait < 0) {
            this.portraitShown = false;

            return;
        }

        InterfaceAssets loaded = this.assets;

        if (loaded != null && this.portraitNoiseColors == null) {
            // The color table: the palette entries nearest to 16 grays.
            int[] colors = new int[PortraitStatic.GRAYS.length];

            for (int index = 0; index < colors.length; index++) {
                colors[index] = loaded.getNearestPaletteColor(PortraitStatic.GRAYS[index]);
            }

            this.portraitNoiseColors = colors;

            player.getStatic().setColors(colors);
        }

        // A still portrait shows its clip at the time the unit or portrait came up.
        if (unitKey != this.portraitStillUnit || shownPortrait != this.portraitStillPortrait) {
            this.portraitStillUnit     = unitKey;
            this.portraitStillPortrait = shownPortrait;
            this.portraitStillClock    = clock;
        }

        PortraitPlayer.Frame frame = player.update(shownPortrait, this.portraitMode == PORTRAITS_STILL ? this.portraitStillClock : clock);
        // Timer 2 of the portrait control (id 2, 30 ms) on the shared timer list; it acts while a portrait is shown (and the
        // portrait unit), which this frame confirms.
        PortraitStatic noise = player.getStatic();

        this.portraitShown = true;

        if (!this.timers.isArmed(noise, PortraitStatic.TIMER)) {
            this.timers.arm(noise,
                            PortraitStatic.TIMER,
                            PortraitStatic.TICK_MILLISECONDS,
                            (int) clock,
                            now -> {
                                if (this.portraitShown && this.portraitPlayer == player) {
                                    noise.tick(!player.isLoading());
                                }
                            });
        }

        DialogLayout.Control control = console.getPortrait().getControl(PORTRAIT_CONTROL);
        boolean hd = this.hdScale != 0;
        float videoY = control.getY() + (hd ? PORTRAIT_HD_VIDEO_Y : 0);

        if (frame != null) {
            this.drawPortraitFrame(frame, console, control, videoY);
        }

        this.drawPortraitStatic(player.getStatic(), console, control, videoY);
        this.drawPortraitOverlay(console, control, hd);
    }

    /**
     * Draws the noise canvas over the video (layer {@code 0x14}) at the video rectangle, the {@code 60x56} canvas stretched to the control in HD.
     */
    private void drawPortraitStatic(@NotNull PortraitStatic noise, @NotNull ConsoleLayout console, DialogLayout.@NotNull Control control, float y) {
        if (!noise.isVisible()) {
            return;
        }

        boolean refill = noise.isDirty() || this.images.getCachedSprite(PORTRAIT_STATIC_KEY) == null;
        byte[] canvas = noise.takeCanvas();

        if (refill) {
            this.images.update(PORTRAIT_STATIC_KEY, PortraitStatic.WIDTH, PortraitStatic.HEIGHT, canvas, 1);
        }

        InterfaceImageCache.Sprite noiseSprite = this.images.getCachedSprite(PORTRAIT_STATIC_KEY);

        if (noiseSprite == null) {
            return;
        }

        float scale = console.getScale();

        this.renderer.drawTexture(noiseSprite.getTexture(),
                                  console.toScreenX(control.getX()), console.toScreenY(y),
                                  control.getWidth() * scale, control.getHeight() * scale,
                                  WHITE_RGBA);
    }

    private void drawPortraitFrame(PortraitPlayer.@NotNull Frame frame,
                                   @NotNull ConsoleLayout console,
                                   DialogLayout.@NotNull Control control,
                                   float y) throws IOException {
        String key = frame.getPath() + "#" + frame.getIndex();
        // Decoded clip frames are cached by clip path and index; the cache is cut back when it grows past 256 frames.
        String frameKey = PORTRAIT_FRAME_KEY + key;

        if (this.images.getCachedSprite(frameKey) == null && this.images.getSpriteCount(PORTRAIT_FRAME_KEY) > PORTRAIT_FRAME_LIMIT) {
            this.images.releaseMatching(PORTRAIT_FRAME_KEY);
        }

        int texture = this.images.getImage(frameKey,
                                           () -> new InterfaceImage(frame.getClip().getWidth(),
                                                                    frame.getClip().getHeight(),
                                                                    frame.getClip().getFrame(frame.getIndex())))
                                 .getTexture();
        float scale = console.getScale();

        // The video fills the control bounds, including the HD vertical offset.
        this.renderer.drawTexture(texture,
                                  console.toScreenX(control.getX()), console.toScreenY(y),
                                  control.getWidth() * scale, control.getHeight() * scale,
                                  WHITE_RGBA);
    }

    /**
     * Draws the quality-dependent portrait overlay while a portrait is shown.
     */
    private void drawPortraitOverlay(@NotNull ConsoleLayout console, DialogLayout.@NotNull Control control, boolean hd) throws IOException {
        InterfaceAssets loaded = this.assets;

        if (loaded == null) {
            return;
        }

        String path = this.getConsoleFolder() + "conover.DDS";
        InterfaceImageCache.Sprite overlay = this.images.getImage(loaded, path);
        float x = control.getX() + (hd ? this.layoutReplay ? REPLAY_PORTRAIT_SHIFT_X : LIVE_PORTRAIT_SHIFT_X : 0);
        float y = control.getY() + (hd ? this.layoutReplay ? REPLAY_PORTRAIT_SHIFT_Y : LIVE_PORTRAIT_SHIFT_Y : 0);

        this.drawConsoleImage(console, overlay, ScrConsoleArt.CONOVER, x, y);
    }

    /**
     * Draws a console art piece at its place: the picture drawn instead of it, or else its installed texture.
     *
     * @param console the console layout
     * @param art     the console art
     * @param piece   the piece index
     * @param texture the installed texture of the piece
     */
    private void drawArtPiece(@NotNull ConsoleLayout console, @NotNull ConsoleArt art, int piece, int texture) {
        float scale = console.getScale();
        float x = console.toScreenX(art.getLeft(piece));
        float y = console.toScreenY(art.getTop(piece));
        float width = art.getWidth(piece) * scale;
        float height = art.getHeight(piece) * scale;
        Picture picture = art.findPicture(piece);

        if (picture == null) {
            this.renderer.drawTexture(texture, x, y, width, height, WHITE_RGBA);
        } else {
            this.renderer.drawTexture(this.pictureTextures.findTexture(this.renderer, picture), x, y, width, height, WHITE_RGBA);
        }
    }

    /**
     * Draws a plate or the portrait overlay of the console at an interface position: the picture a plugin draws instead of its art file
     * ({@link ScrGameView#findConsoleArt}) stretched over the installed image's size, or else the installed image.
     *
     * @param console the console layout
     * @param image   the installed image
     * @param art     the art file of the image
     * @param x       the interface left
     * @param y       the interface top
     */
    private void drawConsoleImage(@NotNull ConsoleLayout console, InterfaceImageCache.@NotNull Sprite image, @NotNull ScrConsoleArt art,
                                  float x, float y) {
        ScrGameView view = this.pictureView;
        Picture picture = view == null ? null : view.findConsoleArt(art);

        if (picture == null) {
            this.drawObserverFrame(console, image, x, y, image.getWidth(), 1);

            return;
        }

        float scale = console.getScale();

        this.renderer.drawTexture(this.pictureTextures.findTexture(this.renderer, picture), console.toScreenX(x), console.toScreenY(y),
                                  image.getWidth() * scale, image.getHeight() * scale, WHITE_RGBA);
    }


    /**
     * Updates the shared tooltip from the control under the pointer: when the hovered control or its text changes, the area's text is shown or the
     * tooltip is cleared. A tooltip shown by a key (Tab) stays until the hover changes.
     */
    private void updateTooltip(@NotNull ConsoleModel model, @NotNull ConsoleLayout console) {
        this.tooltip.setFont(this.smallFont);

        InterfaceAssets styleAssets = this.assets;

        if (styleAssets != null) {
            this.tooltip.setStyle(styleAssets.getNearestPaletteColor(Tooltip.FILL_TARGET),
                                  styleAssets.getNearestPaletteColor(Tooltip.BORDER_TARGET),
                                  !isStandardDefinition(styleAssets));
        }

        String hover = null;
        Tooltip.Painter painter = null;
        CommandCard card = model.getCommandCard();
        ButtonDefinition button = this.hoveredSlot >= 0 ? card.getButton(this.hoveredSlot) : null;

        if (button != null) {
            int slot = this.hoveredSlot;
            Unit active = model.getSelection().getActiveUnit();
            int owner = active != null ? active.getOwnerIndex() : model.getPlayer();
            ButtonTooltip built = ButtonTooltip.build(model.getView(),
                                                      this.text,
                                                      this.hotkeyProfile,
                                                      button,
                                                      card.getState(slot) == CommandCard.DISABLED,
                                                      card.getDisabledString(slot),
                                                      active,
                                                      owner,
                                                      model.getRace());

            if (built != null && !built.getText().isEmpty()) {
                hover   = "command:" + slot + ":" + built.getText() + ":" + built.getCosts().size();
                painter = (shown, layout) -> this.paintCommandTooltip(shown, layout, slot, built);
            }
        } else {
            DialogLayout.Control control = this.findTooltipControl(console);
            String text = control == null ? "" : this.getControlTooltip(model, console, control);

            if (control != null && !text.isEmpty()) {
                // The statdata draw callback anchors the box at the control's inclusive right edge and top.
                float anchorX = control.getX() + control.getWidth() - 1;
                float anchorY = control.getY();

                hover   = "control:" + System.identityHashCode(control) + ":" + text;
                painter = (shown, layout) -> shown.drawBox(layout, anchorX, anchorY, text);
            }
        }

        if (Objects.equals(hover, this.tooltipHover)) {
            return;
        }

        this.tooltipHover = hover;

        if (painter != null) {
            this.tooltip.show(hover, painter);
        } else {
            this.tooltip.clear();
        }
    }

    /**
     * Refreshes the terrain tooltip only while the terrain control is hovered in a live game.
     */
    private void showTerrainTooltip() {
        ConsoleLayout console = this.layout;

        if (console == null || this.layoutReplay) {
            return;
        }

        // The tooltip shows only while the pointer rests on the button: a hotkey (Tab) refreshes the text of a hovered button and
        // shows nothing otherwise.
        if (console.getDialog(console.getMinimap()).getDialog().getHoveredId() != TERRAIN_BUTTON) {
            return;
        }

        DialogLayout.Control control = console.getMinimap().getControl(TERRAIN_BUTTON);
        String text = this.text.getString(this.minimapTerrain ? HIDE_TERRAIN_TOOLTIP : SHOW_TERRAIN_TOOLTIP);
        float anchorX = control.getX() + control.getWidth() - 1;
        float anchorY = control.getY();
        String owner = "control:" + System.identityHashCode(control) + ":" + text;

        this.tooltip.show(owner, (shown, layout) -> shown.drawBox(layout, anchorX, anchorY, text));
    }

    /**
     * Returns the console control under the pointer that has a tooltip area: the {@code statdata} queue slots, stat icons,
     * research slot, egg / add-on slot, cargo slots and group grid, the minimap terrain button and the menu button.
     */
    private @Nullable DialogLayout.Control findTooltipControl(@NotNull ConsoleLayout console) {
        // The hovered control of each console dialog (stored by the dialog list on pointer moves).
        int panel = console.getDialog(console.getSelectionPanel()).getDialog().getHoveredId();
        boolean area = panel >= FIRST_QUEUE_SLOT && panel <= LAST_QUEUE_SLOT || panel >= FIRST_STAT_ICON && panel <= LAST_STAT_ICON ||
                       panel == RESEARCH_SLOT || panel == CHILD_SLOT || panel >= FIRST_CARGO_SLOT && panel <= LAST_GROUP_SLOT;

        if (area && this.statusPanel.getControl(panel) != null) {
            return console.getSelectionPanel().getControl(panel);
        }

        int minimap = console.getDialog(console.getMinimap()).getDialog().getHoveredId();

        if (minimap == TERRAIN_BUTTON || this.multiplayerButtons && !this.layoutReplay && (minimap == MESSAGE_BUTTON || minimap == VISION_BUTTON)) {
            return console.getMinimap().getControl(minimap);
        }

        DialogLayout.Control menu = console.getMenu().getControl(MENU_BUTTON);

        return console.getMenuDialog().getHoveredId() == menu.getIdentifier() ? menu : null;
    }

    /**
     * Measures text and cost bounds, fits the background above the command control, then draws text and ordered cost icons and values.
     */
    private void paintCommandTooltip(@NotNull Tooltip shown, @NotNull ConsoleLayout console, int slot, @NotNull ButtonTooltip tooltip) throws IOException {
        InterfaceFont font = shown.getFont();
        InterfaceAssets loaded = this.assets;

        if (font == null || loaded == null) {
            return;
        }

        List<String> lines = List.of(tooltip.getText().split(String.valueOf((char) LINE_FEED_CODE)));
        int clipRight = (int) console.getInterfaceWidth() - 1;
        int clipBottom = ConsoleLayout.INTERFACE_HEIGHT - 1;
        InterfaceText.Bounds text = InterfaceText.measureBounds(font,
                                                                lines,
                                                                Tooltip.PEN_START_X, 0,
                                                                Tooltip.LINE_GAP_BYTE,
                                                                Tooltip.PEN_START_X, 0,
                                                                clipRight, clipBottom);
        float boundsRight = text.getRight();
        float boundsBottom = text.getBottom();
        float pen = Tooltip.PEN_START_X;
        int lastIconHeight = -1;

        for (ButtonTooltip.Cost cost : tooltip.getCosts()) {
            InterfaceAssets.ScaledImage icon = this.getSheetImage(loaded, RESOURCE_SHEET, cost.getIcon());
            float numberPen = pen + icon.getInterfaceWidth() + COST_NUMBER_GAP;

            pen            = this.getCostNumberRight(font, cost.getAmount(), numberPen, clipRight);
            lastIconHeight = icon.getInterfaceHeight();
        }

        if (lastIconHeight >= 0) {
            boundsRight = Math.max(boundsRight, pen);
            boundsBottom += lastIconHeight + 1;
        }

        float width = boundsRight - text.getLeft();
        float height = boundsBottom - text.getTop() + 1;
        DialogLayout.Control control = console.getCommandButtons().getControl(slot + 1);
        float left = control.getX();
        float right = left + width;
        float overflow = console.getInterfaceWidth() - COMMAND_TOOLTIP_MARGIN - right;

        if (overflow < 0) {
            left += overflow;
            right += overflow;
        }

        float bottom = control.getY() - 1;
        float top = bottom - height + 1;

        shown.drawBackground(console, left, top, right, bottom);

        // Draws within the clip words of the measure, relative to the tooltip origin.
        InterfaceText.drawUnwrapped(this.renderer,
                                    console,
                                    font,
                                    tooltip.getText(),
                                    left + Tooltip.PEN_START_X, top,
                                    left + clipRight,
                                    LTEXT_FORMAT,
                                    Tooltip.TEXT_COLOR_CODE);

        float costY = top + text.getBottom();
        float costPen = Tooltip.PEN_START_X;

        for (ButtonTooltip.Cost cost : tooltip.getCosts()) {
            int frame = cost.getIcon();
            InterfaceAssets.ScaledImage icon = this.getSheetImage(loaded, RESOURCE_SHEET, frame);

            this.painter.begin(console, loaded);
            this.painter.drawImageFrame(this.painter.getSheetFrame(RESOURCE_SHEET, frame),
                                        left + costPen, costY,
                                        icon.getInterfaceWidth(), icon.getInterfaceHeight(),
                                        WHITE_RGBA);

            String amount = Integer.toString(cost.getAmount());
            float numberPen = costPen + icon.getInterfaceWidth() + COST_NUMBER_GAP;

            InterfaceText.drawAt(this.renderer, console, font, amount, left + numberPen, costY, left + clipRight, Tooltip.TEXT_COLOR_CODE);

            costPen = this.getCostNumberRight(font, cost.getAmount(), numberPen, clipRight);
        }
    }

    private void drawMinimap(@NotNull ConsoleModel model,
                             @NotNull ConsoleLayout console,
                             @NotNull InterfaceAssets loaded,
                             float viewLeft, float viewTop, float viewWidth, float viewHeight) throws IOException {
        ScrGameView view = model.getView();
        GameMap map = view.getMap();

        if (!this.timers.isArmed(this.minimapPings, MINIMAP_REFRESH_TIMER)) {
            this.armMinimapTimers((int) UiClockUtils.getHostTimeMilliseconds());
        }

        // A multiplayer game refreshes after every network turn step; a frame can run without one.
        boolean turn = this.multiplayerGame && view.getTurnCount() != this.minimapTurn;

        if (this.minimapImage == null || this.minimapRefresh || turn || this.minimapWidth != map.getWidth() ||
            this.minimapHeight != map.getHeight() || this.minimapState != view) {
            // Whether a unit blinked during this dot pass (every listed blink still has a nonzero count).
            this.minimapBlinked = !this.minimapBlinks.isEmpty();

            MinimapImage image = MinimapImage.build(view,
                                                    loaded,
                                                    model.getPlayer(),
                                                    this.minimapTerrain,
                                                    this.rememberedBuildings.getGhosts(),
                                                    this.minimapColorMode,
                                                    this.minimapBlinks, this.minimapBlinkPhase,
                                                    this.minimapState == view ? this.minimapImage : null);

            this.uploadMinimapLayers(image);

            this.minimapImage   = image;
            this.minimapWidth   = map.getWidth();
            this.minimapHeight  = map.getHeight();
            this.minimapState   = view;
            this.minimapRefresh = false;
            this.minimapTurn    = view.getTurnCount();
        }

        MinimapImage image = this.minimapImage;
        DialogLayout.Control area = console.getMinimap().getControl(ScrConsolePlacement.MINIMAP_AREA_CONTROL);
        float minimapScale = this.getMinimapScale();
        float left = getMinimapLeft(area, image, minimapScale);
        float top = getMinimapTop(area, image, minimapScale);
        float scale = console.getScale() * minimapScale;
        // Black, then each layer texture stretched into the bitmap box (terrain and creep unless Tab hid
        // them, the linearly filtered fog, the dots); the layers are uploaded only when refreshed.
        float boxX = console.toScreenX(left);
        float boxY = console.toScreenY(top);
        float boxWidth = image.getWidth() * scale;
        float boxHeight = image.getHeight() * scale;

        this.renderer.fillRectangle(boxX, boxY, boxWidth, boxHeight, BLACK_RGBA);

        if (image.getTerrainLayer() != null) {
            this.renderer.drawTexture(this.minimapTerrainTexture, boxX, boxY, boxWidth, boxHeight, WHITE_RGBA);
        }

        if (image.getFogLayer() != null) {
            this.renderer.drawTexture(this.minimapFogTexture, boxX, boxY, boxWidth, boxHeight, WHITE_RGBA);
        }

        this.renderer.drawTexture(this.minimapDotTexture, boxX, boxY, boxWidth, boxHeight, WHITE_RGBA);
        this.drawMinimapBorder(console, left, top, image.getWidth(), image.getHeight(), minimapScale, loaded);
        this.drawMinimapPings(model, console, loaded, image, left, top, minimapScale);

        // Camera rectangle in bitmap pixels: origin and size in tiles scaled like the dots; only the bottom is clamped.
        int frameLeft = image.toBitmap((int) Math.floor(viewLeft / TerrainTileset.TILE_SIZE));
        int frameTop = image.toBitmap((int) Math.floor(viewTop / TerrainTileset.TILE_SIZE));
        int frameRight = frameLeft + image.toBitmap((int) Math.ceil(viewWidth / TerrainTileset.TILE_SIZE));
        int extent = image.toBitmap((int) Math.ceil(viewHeight / TerrainTileset.TILE_SIZE));
        int frameBottom = frameTop + (frameTop + extent <= image.getHeight() ? extent : image.getHeight() - frameTop);

        if (frameRight > frameLeft && frameBottom > frameTop) {
            this.renderer.drawRectangle(console.toScreenX(left + frameLeft * minimapScale), console.toScreenY(top + frameTop * minimapScale),
                                        (frameRight - frameLeft) * scale, (frameBottom - frameTop) * scale,
                                        Math.max(1, console.getScale()),
                                        WHITE_RGBA);
        }
    }

    /**
     * @return the interface pixels per minimap bitmap pixel: the minimap size of the console placement over the installed {@code 128}, else
     * {@code 1}
     */
    private float getMinimapScale() {
        ScrConsolePlacement shownPlacement = this.placement;

        return shownPlacement == null ? 1 : shownPlacement.getMinimapSize() / (float) MINIMAP_BITMAP_SIDE;
    }

    /**
     * Arms full refresh, blink phase and ping-frame timers in that order.
     * Multiplayer uses its longer refresh interval and also refreshes when the game frame changes.
     */
    private void armMinimapTimers(int now) {
        this.timers.arm(this.minimapPings,
                        MINIMAP_REFRESH_TIMER,
                        this.multiplayerGame ? MINIMAP_MULTIPLAYER_REFRESH_INTERVAL : MINIMAP_REFRESH_INTERVAL,
                        now,
                        clock -> this.minimapRefresh = true);
        this.timers.arm(this.minimapPings,
                        MINIMAP_BLINK_TIMER,
                        MINIMAP_BLINK_INTERVAL,
                        now,
                        clock -> {
                            this.minimapBlinkPhase = !this.minimapBlinkPhase;

                            if (this.minimapBlinked && !this.minimapPings.isAnyActive()) {
                                this.minimapRefresh = true;
                            }
                        });
        this.timers.arm(this.minimapPings,
                        MINIMAP_PING_TIMER,
                        MINIMAP_PING_INTERVAL,
                        now,
                        clock -> {
                            if (this.minimapPings.advance()) {
                                this.minimapRefresh = true;
                            }
                        });
    }

    /**
     * Uploads the refreshed minimap layers into their textures (created once per canvas size): the terrain only when it
     * was sampled again, the fog and the dots on every refresh.
     */
    private void uploadMinimapLayers(@NotNull MinimapImage image) {
        int width = image.getTextureWidth();
        int height = image.getTextureHeight();
        boolean created = false;

        if (this.minimapDotTexture == 0 || this.minimapCanvasWidth != width || this.minimapCanvasHeight != height ||
            this.minimapWidth != image.getMapWidth() || this.minimapHeight != image.getMapHeight()) {
            this.releaseMinimapTextures();

            this.minimapTerrainTexture = this.renderer.createDynamicTexture(width, height, false);
            this.minimapDotTexture     = this.renderer.createDynamicTexture(width, height, false);
            this.minimapFogTexture     = this.renderer.createDynamicTexture(image.getMapWidth(), image.getMapHeight(), true);
            this.minimapCanvasWidth    = width;
            this.minimapCanvasHeight   = height;
            created                    = true;
        }

        int[] terrain = image.getTerrainLayer();

        if (terrain != null && (created || image.isTerrainChanged() || this.minimapTerrainStale)) {
            this.renderer.updateTexture(this.minimapTerrainTexture, width, height, terrain);

            this.minimapTerrainStale = false;
        } else if (terrain == null && created) {
            this.minimapTerrainStale = true;
        }

        int[] fog = image.getFogLayer();

        if (fog != null) {
            this.renderer.updateTexture(this.minimapFogTexture, image.getMapWidth(), image.getMapHeight(), fog);
        }

        int[] dots = image.getDotLayer();

        if (dots != null) {
            if (created) {
                this.renderer.updateTexture(this.minimapDotTexture, width, height, dots);
            } else if (image.getChangedDotTop() >= 0) {
                this.renderer.updateTextureRows(this.minimapDotTexture,
                                                width,
                                                image.getChangedDotTop(),
                                                Math.min(image.getChangedDotBottom(), height - 1),
                                                dots);
            }
        }
    }

    private void releaseMinimapTextures() {
        if (this.minimapFogTexture != 0) {
            IgnoreException.execute(
                    () -> this.renderer.deleteTexture(this.minimapFogTexture)
            );
        }

        if (this.minimapDotTexture != 0) {
            IgnoreException.execute(
                    () -> this.renderer.deleteTexture(this.minimapDotTexture)
            );
        }

        if (this.minimapTerrainTexture != 0) {
            IgnoreException.execute(
                    () -> this.renderer.deleteTexture(this.minimapTerrainTexture)
            );
        }

        this.minimapFogTexture     = 0;
        this.minimapDotTexture     = 0;
        this.minimapTerrainTexture = 0;
        this.minimapCanvasHeight   = 0;
        this.minimapCanvasWidth    = 0;
    }

    /**
     * Draws active ping frames clipped to the bitmap, using the selected player or blink color.
     * The renderer clip is restored in finally.
     */
    private void drawMinimapPings(@NotNull ConsoleModel model,
                                  @NotNull ConsoleLayout console,
                                  @NotNull InterfaceAssets loaded,
                                  @NotNull MinimapImage image,
                                  float left, float top, float minimapScale) throws IOException {
        if (!this.minimapPings.isAnyActive()) {
            return;
        }

        int clipLeft = (int) Math.floor(console.toScreenX(left));
        int clipTop = (int) Math.floor(console.toScreenY(top));

        this.renderer.setClip(clipLeft,
                              clipTop,
                              (int) Math.ceil(console.toScreenX(left + image.getWidth() * minimapScale)) - clipLeft,
                              (int) Math.ceil(console.toScreenY(top + image.getHeight() * minimapScale)) - clipTop);

        try {
            for (int slot = 0; slot < MINIMAP_PING_SLOTS; slot++) {
                if (!this.minimapPings.isActive(slot)) {
                    continue;
                }

                float x = left + image.toBitmap(this.minimapPings.getX(slot) >>> TerrainTileset.TILE_SHIFT) * minimapScale - PING_ORIGIN_OFFSET;
                float y = top + image.toBitmap(this.minimapPings.getY(slot) >>> TerrainTileset.TILE_SHIFT) * minimapScale - PING_ORIGIN_OFFSET;
                int color = this.minimapPings.isPlayerColor(slot)
                            ? MinimapImage.getPlayerColor(model.getView(), loaded, this.minimapPings.getColor(slot), this.minimapColorMode)
                            : loaded.getBlinkColor(this.minimapPings.getColor(slot));

                // The plain image draw: no classic frame offset (blink.grp's frames are offset).
                this.painter.begin(console, loaded);
                this.painter.drawImageFrame(this.painter.getSheetFrame(BLINK_SHEET, this.minimapPings.getFrame(slot)), x, y, color);
            }
        } finally {
            this.renderer.setClip(0, 0, GlueCanvas.NO_CLIP_SIZE, GlueCanvas.NO_CLIP_SIZE);
        }
    }

    /**
     * Draws the frame around a bitmap narrower or shorter than {@code 128} pixels; colors are the palette entries nearest
     * RGB({@code 208},{@code 208},{@code 208}) and RGB({@code 160},{@code 160},{@code 160}). The bitmap is scaled by the minimap scale; the
     * frame's lines keep one interface pixel.
     */
    private void drawMinimapBorder(@NotNull ConsoleLayout console, float left, float top, int width, int height, float minimapScale,
                                   @NotNull InterfaceAssets loaded) {
        float scale = console.getScale();
        int light = loaded.getNearestPaletteColor(MINIMAP_BORDER_LIGHT);
        int dark = loaded.getNearestPaletteColor(MINIMAP_BORDER_DARK);
        float drawnWidth = width * minimapScale;
        float drawnHeight = height * minimapScale;

        if (width < MINIMAP_BITMAP_SIDE && height < MINIMAP_BITMAP_SIDE) {
            this.renderer.drawRectangle(console.toScreenX(left), console.toScreenY(top - 1),
                                        (drawnWidth - 1 + MINIMAP_RIGHT_BORDER_GROWTH) * scale,
                                        (drawnHeight - 1 + MINIMAP_BOTTOM_BORDER_GROWTH + 1) * scale,
                                        Math.max(1, scale),
                                        light);
        } else if (width < MINIMAP_BITMAP_SIDE) {
            this.renderer.fillRectangle(console.toScreenX(left - 1), console.toScreenY(top), scale, drawnHeight * scale, light);
            this.renderer.fillRectangle(console.toScreenX(left + drawnWidth - 1 + MINIMAP_RIGHT_BORDER_GROWTH), console.toScreenY(top), scale,
                                        drawnHeight * scale, dark);
        } else if (height < MINIMAP_BITMAP_SIDE) {
            this.renderer.fillRectangle(console.toScreenX(left), console.toScreenY(top - 1), drawnWidth * scale, scale, light);
            this.renderer.fillRectangle(console.toScreenX(left), console.toScreenY(top + drawnHeight), drawnWidth * scale, scale, dark);
        }
    }


    /**
     * Returns the pointer position relative to the bitmap and its dimensions, divisor, zoom and map-pixel scale.
     *
     * @param screenX the screen x
     * @param screenY the screen y
     * @return the geometry indexed by {@link #MINIMAP_LOCAL_X_INDEX} through {@link #MINIMAP_PIXEL_SCALE_INDEX}, or {@code null} without a minimap
     */
    public int @Nullable [] getMinimapGeometry(float screenX, float screenY) {
        ConsoleLayout console = this.layout;
        MinimapImage image = this.minimapImage;

        if (console == null || image == null || !this.isConsoleDialogShown(console.getMinimap())) {
            return null;
        }

        DialogLayout.Control area = console.getMinimap().getControl(ScrConsolePlacement.MINIMAP_AREA_CONTROL);
        float minimapScale = this.getMinimapScale();
        float left = getMinimapLeft(area, image, minimapScale);
        float top = getMinimapTop(area, image, minimapScale);

        return new int[]{(int) Math.floor((console.toInterfaceX(screenX) - left) / minimapScale),
                         (int) Math.floor((console.toInterfaceY(screenY) - top) / minimapScale),
                         image.getWidth(), image.getHeight(),
                         image.getDivisor(),
                         image.getZoom(),
                         image.getMapPixelsPerPixel()};
    }

    /**
     * Places map positions on the minimap bitmap as it was last drawn: a map pixel {@code (x, y)} is drawn at the screen position
     * {@code (left + x * scale, top + y * scale)}. The placement receives the screen left ({@link #MINIMAP_SCREEN_LEFT_INDEX}), the screen top
     * ({@link #MINIMAP_SCREEN_TOP_INDEX}) and the screen pixels per map pixel ({@link #MINIMAP_SCREEN_SCALE_INDEX}).
     *
     * @param placement the {@link #MINIMAP_PLACEMENT_VALUES} values to fill
     * @return {@code true} if the console shows a minimap, {@code false} otherwise, the placement then unchanged
     */
    public boolean placeMinimap(float @NotNull [] placement) {
        ConsoleLayout console = this.layout;
        MinimapImage image = this.minimapImage;

        if (console == null || image == null || !this.isConsoleDialogShown(console.getMinimap())) {
            return false;
        }

        DialogLayout.Control area = console.getMinimap().getControl(ScrConsolePlacement.MINIMAP_AREA_CONTROL);
        float minimapScale = this.getMinimapScale();

        placement[MINIMAP_SCREEN_LEFT_INDEX]  = console.toScreenX(getMinimapLeft(area, image, minimapScale));
        placement[MINIMAP_SCREEN_TOP_INDEX]   = console.toScreenY(getMinimapTop(area, image, minimapScale));
        placement[MINIMAP_SCREEN_SCALE_INDEX] = console.getScale() * minimapScale * (1 << image.getZoom()) / image.getDivisor() /
                                                TerrainTileset.TILE_SIZE;

        return true;
    }

    /**
     * Returns the screen rectangle of the minimap bitmap as the window's pointer clip, for the drag confinement.
     *
     * @return the clip in screen pixels, or {@code null} without a console or minimap
     */
    public @Nullable PointerClip getMinimapClip() {
        ConsoleLayout console = this.layout;
        MinimapImage image = this.minimapImage;

        if (console == null || image == null) {
            return null;
        }

        DialogLayout.Control area = console.getMinimap().getControl(ScrConsolePlacement.MINIMAP_AREA_CONTROL);
        float minimapScale = this.getMinimapScale();
        float left = getMinimapLeft(area, image, minimapScale);
        float top = getMinimapTop(area, image, minimapScale);

        return PointerClip.of(console.toScreenX(left), console.toScreenY(top),
                              console.toScreenX(left + image.getWidth() * minimapScale), console.toScreenY(top + image.getHeight() * minimapScale));
    }

    /**
     * Starts a minimap ping at a map position (see {@link MinimapPings}): it takes the first free of the {@code 16} slots and shows the
     * {@code 20} frames of {@code blink}, one per {@code 100} milliseconds.
     *
     * @param x           the map x in pixels
     * @param y           the map y in pixels
     * @param color       the {@code tblink} row ({@link #ALERT_PING_COLOR} and the others), or the player with {@code playerColor}
     * @param playerColor whether the ping is tinted with the player color of {@code color}
     */
    public void addMinimapPing(int x, int y, int color, boolean playerColor) {
        this.minimapPings.add(x, y, color & PING_COLOR_MASK, playerColor);
    }

    /**
     * Makes a unit's dot blink in the local player's minimap block: the count is decremented per dot pass; while it is nonzero and
     * the {@code 200} ms blink phase is on, the dot takes the palette color.
     *
     * @param poolIndex    the unit's pool index
     * @param count        the blink passes
     * @param paletteIndex the palette index of the blink color
     */
    public void blinkUnit(int poolIndex, int count, int paletteIndex) {
        this.minimapBlinks.put(poolIndex, new int[]{count, paletteIndex});
    }

    /**
     * Ends a transmission's talking portrait at once (a skipped transmission).
     */
    public void endTransmission() {
        this.transmissionType  = -1;
        this.transmissionEnd   = 0;
        this.transmissionStart = false;

        if (this.portraitPlayer != null) {
            this.portraitPlayer.stopTalking();
        }
    }

    /**
     * Shows a trigger transmission: the speaker type's portrait talks for the duration.
     *
     * @param unitType             the speaker's unit type
     * @param durationMilliseconds the duration
     * @param now                  a monotonic clock in milliseconds
     */
    public void showTransmission(int unitType, long durationMilliseconds, long now) {
        this.transmissionType  = unitType;
        this.transmissionEnd   = now + Math.max(0, durationMilliseconds);
        this.transmissionStart = true;
    }

    /**
     * Cycles the minimap color mode (Shift+Tab): {@code (mode + 1) % 3}; in a replay a nonzero result is forced to {@code 2} (team
     * colors). The minimap is redrawn at once.
     */
    public void cycleMinimapColorMode() {
        int mode = (this.minimapColorMode + 1) % MINIMAP_COLOR_MODES;

        this.minimapColorMode = this.layoutReplay && mode != 0 ? TEAM_COLOR_MODE : mode;
        this.minimapRefresh   = true;

        this.showTerrainTooltip();
    }

    /**
     * Toggles the minimap terrain (Tab); the minimap redraws immediately.
     */
    public void toggleMinimapTerrain() {
        this.minimapTerrain = !this.minimapTerrain;
        this.minimapRefresh = true;

        this.showTerrainTooltip();
    }

    /**
     * Draws the shown replay frames and panes in their existing order, followed by messages and an open vision dialog.
     */
    private void drawObserver(@NotNull ConsoleModel model,
                              @NotNull InterfaceAssets loaded,
                              @NotNull GameViewport viewport,
                              float viewLeft, float viewTop, float viewWidth, float viewHeight) throws IOException {
        ConsoleLayout replay = this.sdLayout;

        if (replay == null) {
            return;
        }

        int interfaceWidth = viewport.getInterfaceWidth();
        // The dialogs and the unit frame are placed by the frame with the timer band; the drawn minimap frame follows the timer.
        InterfaceImageCache.Sprite squareRight = this.images.getImage(loaded, ObserverConsoleUtils.SQUARE_RIGHT);
        InterfaceImageCache.Sprite minimapFrame = this.images.getImage(loaded, ObserverConsoleUtils.getMinimapFrame(this.showTimer));
        InterfaceImageCache.Sprite consoleLeft = this.images.getImage(loaded, ObserverConsoleUtils.CONSOLE_LEFT);
        InterfaceImageCache.Sprite consoleCenter = this.images.getImage(loaded, ObserverConsoleUtils.CONSOLE_CENTER);
        InterfaceImageCache.Sprite consoleRight = this.images.getImage(loaded, ObserverConsoleUtils.CONSOLE_RIGHT);
        InterfaceImageCache.Sprite squareFull = this.images.getImage(loaded, ObserverConsoleUtils.SQUARE_FULL);
        ConsoleLayout console = this.observerLayout;

        if (console == null || interfaceWidth != this.observerWidth) {
            console             = ObserverConsoleUtils.createLayout(replay,
                                                                    interfaceWidth,
                                                                    squareRight.getHeight(),
                                                                    squareFull.getWidth(), squareFull.getHeight());
            this.observerLayout = console;
            this.observerWidth  = interfaceWidth;
        }

        this.layout = console;

        console.setViewport(viewport);

        int interfaceHeight = ConsoleLayout.INTERFACE_HEIGHT;

        // With draw flags 0x16 and tint (1, 1, 1, 1); each frame only while the page shows its pane.
        if (this.sideBar.isShown(ReplaySideBar.Pane.MINIMAP)) {
            this.drawObserverFrame(console, minimapFrame, 0, interfaceHeight - minimapFrame.getHeight(), minimapFrame.getWidth(), 1);

            // With the timer the game time follows in font10, centered on the frame's middle, 2 below its top.
            if (this.showTimer) {
                this.drawClockText(console,
                                   this.normalFont,
                                   TimerTextUtils.formatGameTime(model.getView().getCurrentFrame()),
                                   GameTimerLayout.ofObserverConsole(minimapFrame.getWidth(), interfaceHeight - minimapFrame.getHeight()),
                                   interfaceWidth);
            }
        }

        if (this.sideBar.isShown(ReplaySideBar.Pane.UNIT)) {
            int paneLeft = squareRight.getWidth();
            int paneTop = interfaceHeight - consoleCenter.getHeight();
            int paneWidth = interfaceWidth - ObserverConsoleUtils.MARGIN - squareFull.getWidth() - paneLeft;
            int right = paneLeft + paneWidth - consoleRight.getWidth();

            this.drawObserverFrame(console, consoleLeft, paneLeft, paneTop, consoleLeft.getWidth(), 1);

            // The center piece covers the remaining span at one texel per texture pixel, clipped to its texture width.
            int centerLeft = squareRight.getWidth() + consoleLeft.getWidth();

            this.drawObserverFrame(console,
                                   consoleCenter,
                                   centerLeft, paneTop,
                                   Math.max(0, right - centerLeft),
                                   Math.min(1, Math.max(0, right - centerLeft) / (float) consoleCenter.getWidth()));
            this.drawObserverFrame(console, consoleRight, right, paneTop, consoleRight.getWidth(), 1);
        }

        if (this.sideBar.isShown(ReplaySideBar.Pane.REPLAY)) {
            this.drawObserverFrame(console,
                                   squareFull,
                                   interfaceWidth - ObserverConsoleUtils.MARGIN - squareFull.getWidth(), interfaceHeight - squareFull.getHeight(),
                                   squareFull.getWidth(),
                                   1);
        }

        // The sidebar controls minimap and selection-pane visibility; the observer minimap has no console buttons.
        if (this.sideBar.isShown(ReplaySideBar.Pane.MINIMAP)) {
            this.drawMinimap(model, console, loaded, viewLeft, viewTop, viewWidth, viewHeight);
        }

        if (this.sideBar.isShown(ReplaySideBar.Pane.UNIT)) {
            this.drawSelection(model, console, loaded);
        }

        this.drawLeaderboard(model, viewport, loaded);
        // The observer path hides the statres slots but not the countdown control -10.
        this.drawCountdown(model, console, viewport);

        // DisplayReplayControl hides every statbtn child.
        if (this.sideBar.isShown(ReplaySideBar.Pane.REPLAY)) {
            this.drawCommandCard(model, console, loaded);
        }

        float pageScale = ObserverConsoleUtils.getPageScale(viewport.getWindowWidth());

        this.observerPageScale = pageScale;

        if (this.sideBar.isShown(ReplaySideBar.Pane.STATISTICS)) {
            this.drawObserverStatistics(model, console, pageScale);
        }

        if (this.sideBar.isShown(ReplaySideBar.Pane.PRODUCTION)) {
            this.drawObserverProduction(console, pageScale);
        }

        if (this.sideBar.isShown(ReplaySideBar.Pane.NAVIGATION)) {
            this.drawReplaySideBar(console, loaded, pageScale);
        }

        this.drawMessages(console);

        if (this.visionDialog.isOpen()) {
            this.drawVisionDialog(console, loaded);
        }
    }

    /**
     * Returns the side bar icon under a screen point of the replay console.
     *
     * @param screenX the screen x
     * @param screenY the screen y
     * @return the icon, or {@code null} off the icons, off the bar, outside a replay or while the bar is hidden
     */
    public ReplaySideBar.@Nullable Button findSideBarButton(float screenX, float screenY) {
        float[] bar = this.getSideBarRectangle();

        if (bar == null) {
            return null;
        }

        return ReplaySideBar.findButton((screenX - bar[SIDEBAR_LEFT_INDEX]) / bar[SIDEBAR_SCALE_INDEX],
                                        (screenY - bar[SIDEBAR_TOP_INDEX]) / bar[SIDEBAR_SCALE_INDEX]);
    }

    /**
     * @return {@code {left, top, page scale}} of the shown replay side bar in screen pixels: the page's bottom right corner
     * ({@code .replayView .navigationPanel}: {@code right: 0}, {@code bottom: 0}), or {@code null} without it
     */
    private float @Nullable [] getSideBarRectangle() {
        ConsoleLayout console = this.layout;

        if (!this.layoutReplay || console == null || !this.sideBar.isShown(ReplaySideBar.Pane.NAVIGATION)) {
            return null;
        }

        float pageScale = this.observerPageScale;
        float right = console.toScreenX(console.getInterfaceWidth());
        float bottom = console.toScreenY(ConsoleLayout.INTERFACE_HEIGHT);

        return new float[]{right - ReplaySideBar.WIDTH * pageScale, bottom - ReplaySideBar.HEIGHT * pageScale, pageScale};
    }

    /**
     * Draws the navigation background and icons, then the hovered tooltip unless it is hidden or a modal vision dialog is open.
     */
    private void drawReplaySideBar(@NotNull ConsoleLayout console, @NotNull InterfaceAssets loaded, float pageScale) throws IOException {
        float[] bar = this.getSideBarRectangle();

        if (bar == null) {
            return;
        }

        this.drawWebImage(ReplaySideBar.BACKGROUND_IMAGE,
                          bar[SIDEBAR_LEFT_INDEX], bar[SIDEBAR_TOP_INDEX],
                          ReplaySideBar.WIDTH * pageScale, ReplaySideBar.HEIGHT * pageScale);

        for (ReplaySideBar.Button button : ReplaySideBar.Button.values()) {
            ReplaySideBar.Pane pane = button.getPane();
            String image = button.getImage(pane == null || this.sideBar.isShown(pane));

            this.drawWebImage(image,
                              bar[SIDEBAR_LEFT_INDEX] + ReplaySideBar.ICON_LEFT * pageScale,
                              bar[SIDEBAR_TOP_INDEX] + ReplaySideBar.getButtonTop(button) * pageScale,
                              ReplaySideBar.ICON * pageScale, ReplaySideBar.ICON * pageScale);
        }

        ReplaySideBar.Button hovered = this.findSideBarButton(this.pointerX, this.pointerY);
        GluText strings = this.gluText;

        // The vision dialog is modal: the bar under it shows no tooltip.
        if (hovered == null || strings == null || this.sideBarTooltipsHidden || this.visionDialog.isOpen()) {
            return;
        }

        // The tooltip is laid out in interface pixels: one CSS pixel is pageScale screen pixels.
        float css = pageScale / console.getScale();
        int slot = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY, ReplaySideBar.TOOLTIP_FONT * css, 0);
        float contentWidth = ReplaySideBar.TOOLTIP_WIDTH * css;
        float headerGap = ReplaySideBar.TOOLTIP_HEADER_GAP * css;

        this.painter.begin(console, loaded);

        List<ReplaySideBar.TooltipLine> lines = ReplaySideBar.layoutTooltip(ReplaySideBar.buildTooltip(hovered, strings),
                                                                            text -> this.painter.measure(slot, text),
                                                                            contentWidth,
                                                                            headerGap);
        float lineHeight = this.painter.getLineHeight(slot);
        float inset = (ReplaySideBar.TOOLTIP_PADDING + ReplaySideBar.TOOLTIP_BORDER) * css;
        float boxWidth = contentWidth + 2 * inset;
        float boxHeight = lines.size() * lineHeight + 2 * inset;
        float boxLeft = console.toInterfaceX(bar[SIDEBAR_LEFT_INDEX] + ReplaySideBar.WIDTH * pageScale) - ReplaySideBar.TOOLTIP_RIGHT * css -
                        boxWidth;
        // The page lets a tall tooltip of a low icon run off its bottom edge, where the view cuts it; it is kept inside here.
        float boxTop = Math.min(console.toInterfaceY(bar[SIDEBAR_TOP_INDEX]) +
                                (ReplaySideBar.getButtonTop(hovered) + ReplaySideBar.TOOLTIP_TOP) * css,
                                ConsoleLayout.INTERFACE_HEIGHT - boxHeight);

        WebPanelStyleUtils.drawBox(this.painter,
                                   boxLeft, boxTop, boxWidth, boxHeight,
                                   ReplaySideBar.TOOLTIP_RADIUS * css,
                                   WebPanelStyleUtils.BORDER, WebPanelStyleUtils.BACKGROUND,
                                   true, true,
                                   true, true);

        for (int line = 0; line < lines.size(); line++) {
            float x = boxLeft + inset + (contentWidth - lines.get(line).getWidth()) / 2;
            float y = boxTop + inset + line * lineHeight;

            for (ReplaySideBar.TooltipPart part : lines.get(line).getParts()) {
                float width = this.painter.measure(slot, part.getText());

                WebPanelStyleUtils.drawText(this.painter, slot, part.getText(), x, y, width + 1, lineHeight, InterfaceText.TOP, part.getColor());

                x += width + (part.isHeader() ? headerGap : 0);
            }
        }
    }

    private void drawObserverFrame(@NotNull ConsoleLayout console,
                                   InterfaceImageCache.@NotNull Sprite frame,
                                   float x, float y,
                                   float width,
                                   float uvRight) {
        float scale = console.getScale();

        this.renderer.drawTexture(frame.getTexture(),
                                  console.toScreenX(x), console.toScreenY(y),
                                  width * scale, frame.getHeight() * scale,
                                  0, 0,
                                  uvRight, 1,
                                  WHITE_RGBA);
    }

    /**
     * Updates observer statistics and draws each row with its player name, resource, worker, supply and APM values.
     * The rows are laid out by {@link ResourceRowLayout} ({@link #placeStatisticsRow}), and the pane grows to the widest row.
     */
    private void drawObserverStatistics(@NotNull ConsoleModel model, @NotNull ConsoleLayout console, float css) throws IOException {
        ScrGameView view = model.getView();

        if (this.observerStatistics.getView() != view) {
            GluText strings = this.gluText;

            this.observerStatistics.reset(view, this.observerNames, strings == null ? "Computer" : strings.get("slotCOMPUTER"));
        }

        this.observerStatistics.update(System.nanoTime(), this.observerApm);

        List<ObserverStatistics.Row> rows = this.observerStatistics.getRows();

        if (rows.isEmpty() || this.assets == null) {
            return;
        }

        float font = STATISTICS_FONT_SIZE * css;
        float icon = STATISTICS_ICON_SIZE * css;
        float rowHeight = Math.max(icon, font * STATISTICS_ROW_FONT_HEIGHT) + STATISTICS_ROW_GAP * css;
        float right = console.toScreenX(console.getInterfaceWidth());
        float paneWidth = 0;

        for (int index = 0; index < rows.size(); index++) {
            float rowLeft = this.placeStatisticsRow(rows.get(index), right, icon, font, css, this.statisticsItemLefts[index]);

            paneWidth = Math.max(paneWidth, right - rowLeft);
        }

        float border = STATISTICS_LEFT_BORDER * css;
        float innerLeft = STATISTICS_INSET * css;
        float left = right - paneWidth - innerLeft - border;
        float top = console.toScreenY(0);
        float contentHeight = Math.max(STATISTICS_MINIMUM_HEIGHT * css, rows.size() * rowHeight);
        float edge = STATISTICS_EDGE_HEIGHT * css;

        this.drawWebImage("observer_team_border_center.png", left + border, top + edge, paneWidth + innerLeft, contentHeight);
        this.drawWebImage("observer_team_border_top.png", left + border, top, paneWidth + innerLeft, edge);
        this.drawWebImage("observer_team_border_bottom.png", left + border, top + edge + contentHeight, paneWidth + innerLeft, edge);

        float leftColumn = contentHeight + 2 * edge;

        this.drawWebImage("observer_team_border_top_left.png", left, top, border, STATISTICS_CORNER_HEIGHT * css);
        this.drawWebImage("observer_team_border_left.png",
                          left, top + STATISTICS_CORNER_HEIGHT * css,
                          border, Math.max(0, leftColumn - 2 * STATISTICS_CORNER_HEIGHT * css));
        this.drawWebImage("observer_team_border_bottom_left.png",
                          left, top + leftColumn - STATISTICS_CORNER_HEIGHT * css,
                          border, STATISTICS_CORNER_HEIGHT * css);

        for (int index = 0; index < rows.size(); index++) {
            ObserverStatistics.Row row = rows.get(index);
            float[] itemLefts = this.statisticsItemLefts[index];
            float y = top + edge + index * rowHeight + css;
            float textY = y + (rowHeight - STATISTICS_ROW_GAP * css - font) / 2;
            int nameColor = PlayerColorUtils.getPlayerColor(view.getPlayerTable(), this.assets, row.getPlayer());
            String race = RACE_FOLDERS[Math.clamp(row.getRace(), 0, ScrRaceIds.PLAYABLE_COUNT - 1)];
            String used = Integer.toString(row.getSupplyUsed());
            float usedX = itemLefts[STATISTICS_SUPPLY_ITEM] + icon + STATISTICS_VALUE_GAP * css;
            float separatorX = usedX + this.renderer.measureText(used, font);

            this.drawShadowedText(row.getName(), itemLefts[STATISTICS_NAME_ITEM], textY, font, nameColor, css);
            this.drawStatisticsItem("icon_minerals.png",
                                    Integer.toString(row.getMinerals()),
                                    itemLefts[STATISTICS_MINERALS_ITEM], y,
                                    icon,
                                    font,
                                    rowHeight,
                                    css);
            this.drawStatisticsItem("icon_gas_" + race + ".png",
                                    Integer.toString(row.getGas()),
                                    itemLefts[STATISTICS_GAS_ITEM], y,
                                    icon,
                                    font,
                                    rowHeight,
                                    css);
            this.drawStatisticsItem("icon_worker_" + race + ".png",
                                    Integer.toString(row.getWorkers()),
                                    itemLefts[STATISTICS_WORKERS_ITEM], y,
                                    icon,
                                    font,
                                    rowHeight,
                                    css);
            this.drawStatisticsItem("icon_supply_" + race + ".png", "", itemLefts[STATISTICS_SUPPLY_ITEM], y, icon, font, rowHeight, css);
            this.drawShadowedText(used, usedX, textY, font, row.getSupplyMaximum() < row.getSupplyUsed() ? OVER_SUPPLY_COLOR : WHITE_RGBA, css);
            this.drawShadowedText(STATISTICS_SUPPLY_SEPARATOR + row.getSupplyMaximum(), separatorX, textY, font, WHITE_RGBA, css);
            this.drawStatisticsItem("icon_apm.png", Integer.toString(row.getApm()), itemLefts[STATISTICS_APM_ITEM], y, icon, font, rowHeight, css);
        }
    }

    /**
     * Places the items of one statistics row from the right edge leftwards with {@link ResourceRowLayout}: APM, supply,
     * workers, gas and minerals in their columns, then the name one value gap before the minerals column.
     *
     * @param row       the row
     * @param right     the right edge of the row in screen pixels
     * @param icon      the icon size in screen pixels
     * @param font      the font size in screen pixels
     * @param css       the screen pixels per CSS pixel
     * @param itemLefts the item lefts by {@code STATISTICS_*_ITEM} index, filled by the call
     * @return the left of the name, the left of the row
     */
    private float placeStatisticsRow(@NotNull ObserverStatistics.Row row, float right, float icon, float font, float css, float @NotNull [] itemLefts) {
        ResourceRowLayout layout = this.resourceLayout;
        String[] values = {Integer.toString(row.getApm()),
                           row.getSupplyUsed() + STATISTICS_SUPPLY_SEPARATOR + row.getSupplyMaximum(),
                           Integer.toString(row.getWorkers()),
                           Integer.toString(row.getGas()),
                           Integer.toString(row.getMinerals())};
        float gap = STATISTICS_VALUE_GAP * css;
        float nameWidth = this.renderer.measureText(row.getName(), font);
        float slotRight = right;

        layout.reset();

        for (int item = 0; item < values.length; item++) {
            float slotLeft = slotRight - STATISTICS_COLUMN_WIDTHS[item] * css;

            itemLefts[item] = layout.placeItem(slotLeft, slotRight, icon + gap + this.renderer.measureText(values[item], font));
            slotRight       = slotLeft;
        }

        itemLefts[STATISTICS_NAME_ITEM] = layout.placeItem(slotRight - gap - nameWidth, slotRight - gap, nameWidth);

        return itemLefts[STATISTICS_NAME_ITEM];
    }

    /**
     * Draws collected production items in player-row order with their icons, progress bars and merged counts.
     */
    private void drawObserverProduction(@NotNull ConsoleLayout console, float css) throws IOException {
        ScrGameView view = this.observerStatistics.getView();

        if (view == null || this.assets == null || this.observerProduction == null) {
            return;
        }

        float left = console.toScreenX(0);
        float y = console.toScreenY(0) + PRODUCTION_STRIP_INSET * css;
        float stripHeight = PRODUCTION_STRIP_HEIGHT * css;

        for (ObserverStatistics.Row row : this.observerStatistics.getRows()) {
            List<ObserverProduction.Item> items = this.observerProduction.collect(view, row.getPlayer());
            float width = PRODUCTION_STRIP_INSET * css + items.size() * PRODUCTION_ITEM_EXTENT * css + PRODUCTION_STRIP_INSET * css;

            this.drawWebImage("UIObserverThin.png", left, y, width, stripHeight);
            this.drawWebImage("UIObserverThinBorderRight.png", left + width, y, PRODUCTION_BORDER_WIDTH * css, stripHeight);
            this.renderer.fillRectangle(left, y + PRODUCTION_PLAYER_MARGIN * css,
                                        PRODUCTION_PLAYER_WIDTH * css, PRODUCTION_PLAYER_HEIGHT * css,
                                        PlayerColorUtils.getPlayerColor(view.getPlayerTable(), this.assets, row.getPlayer()));

            float x = left + PRODUCTION_STRIP_INSET * css;
            float top = y + (stripHeight - PRODUCTION_ITEM_EXTENT * css) / 2;

            for (ObserverProduction.Item item : items) {
                x += PRODUCTION_ITEM_GAP * css;

                this.renderer.fillRectangle(x, top, PRODUCTION_ICON_SIZE * css, PRODUCTION_ICON_SIZE * css, BLACK_RGBA);

                if (item.getIcon() != null) {
                    this.drawWebImage(item.getIcon(), x, top, PRODUCTION_ICON_SIZE * css, PRODUCTION_ICON_SIZE * css);
                }

                this.renderer.fillRectangle(x, top + PRODUCTION_ICON_SIZE * css,
                                            PRODUCTION_ICON_SIZE * css * item.getProgress(), PRODUCTION_PROGRESS_HEIGHT * css,
                                            PRODUCTION_PROGRESS_COLOR);

                if (item.getCount() > 1) {
                    String count = Integer.toString(item.getCount());
                    float size = PRODUCTION_COUNT_FONT * css;

                    this.drawShadowedText(count,
                                          x + PRODUCTION_ICON_SIZE * css - this.renderer.measureText(count, size),
                                          top + PRODUCTION_ICON_SIZE * css - size,
                                          size,
                                          WHITE_RGBA,
                                          css * HALF);
                }

                x += PRODUCTION_ICON_SIZE * css;
            }

            y += stripHeight;
        }
    }

    private void drawStatisticsItem(@NotNull String image,
                                    @NotNull String value,
                                    float x, float y,
                                    float icon,
                                    float font,
                                    float rowHeight,
                                    float css) throws IOException {
        float middle = y + (rowHeight - STATISTICS_ROW_GAP * css) / 2;

        this.drawWebImage(image, x, middle - icon / 2, icon, icon);

        if (!value.isEmpty()) {
            this.drawShadowedText(value, x + icon + STATISTICS_VALUE_GAP * css, middle - font / 2, font, WHITE_RGBA, css);
        }
    }

    private void drawShadowedText(@NotNull String text, float x, float y, float size, int color, float css) {
        // Page text-shadow: #000 around the glyphs (six 1 px blurred shadows, drawn here as four offset copies).
        float offset = css * SHADOW_OFFSET_FACTOR;

        this.renderer.drawText(text, x - offset, y, size, BLACK_RGBA);
        this.renderer.drawText(text, x + offset, y, size, BLACK_RGBA);
        this.renderer.drawText(text, x, y - offset, size, BLACK_RGBA);
        this.renderer.drawText(text, x, y + offset, size, BLACK_RGBA);
        this.renderer.drawText(text, x, y, size, color);
    }

    private void drawWebImage(@NotNull String name, float x, float y, float width, float height) throws IOException {
        if (width <= 0 || height <= 0) {
            return;
        }

        int texture = this.images.getImage(WEB_IMAGE_KEY + name, () -> {
            BufferedImage image = WebImageUtils.read(this.storage.readFile("webui/dist/lib/images/" + name));

            if (image == null) {
                throw Exceptions.newIOException("Unreadable observer image {0}", name);
            }

            return WebImageUtils.toInterfaceImage(image);
        }).getTexture();

        this.renderer.drawTexture(texture, x, y, width, height, WHITE_RGBA);
    }

    /**
     * Moves the dialogs of a layout and their controls to the places the placement gives them, in the classic screen at each dialog's anchor,
     * and the hidden dialogs and controls out of the interface.
     *
     * @param base the layout of the interface width
     * @return the layout with the dialogs placed, the same layout while the placement is unchanged, or {@code base} without a placed dialog
     */
    private @NotNull ConsoleLayout placeDialogs(@NotNull ConsoleLayout base) {
        ScrConsolePlacement shownPlacement = this.placement;

        if (shownPlacement == null) {
            return base;
        }

        if (this.placedLayout != null && this.placedBase == base && this.placedFrom == shownPlacement &&
            this.placedChange == shownPlacement.getChangeCount()) {
            return this.placedLayout;
        }

        DialogLayout[] dialogs = {base.getCommandButtons(), base.getSelectionPanel(), base.getResources(), base.getPortrait(), base.getMinimap(),
                                  base.getMenu()};
        boolean placed = false;

        for (int index = 0; index < dialogs.length; index++) {
            DialogLayout dialog = placeDialog(dialogs[index], PLACED_DIALOGS[index], shownPlacement, base.getInterfaceWidth());

                             placed |= dialog != dialogs[index];
            dialogs[index] = dialog;
        }

        this.placedLayout = placed ? base.createPlacedLayout(dialogs[0], dialogs[1], dialogs[2], dialogs[3], dialogs[4], dialogs[5]) : base;
        this.placedBase   = base;
        this.placedFrom   = shownPlacement;
        this.placedChange = shownPlacement.getChangeCount();

        return this.placedLayout;
    }

    /**
     * Places the text box where the placement moves it, in the classic screen at its anchor, or as a popup.
     *
     * @param textBox the installed text box
     * @param console the console layout
     * @return the placed text box, the same layout while its placement is unchanged
     */
    private @NotNull DialogLayout placeTextBox(@NotNull DialogLayout textBox, @NotNull ConsoleLayout console) {
        ScrConsolePlacement shownPlacement = this.placement;
        ScrConsolePlacement.Dialog dialog = ScrConsolePlacement.Dialog.TEXT_BOX;

        if (shownPlacement == null || !shownPlacement.isDialogMoved(dialog) && !shownPlacement.isDialogHidden(dialog) &&
                                      shownPlacement.getPlacedControls(dialog).length == 0) {
            return this.placePopup(textBox, console);
        }

        if (this.placedTextBox == null || this.placedTextBoxBase != textBox || this.placedTextBoxWidth != console.getInterfaceWidth() ||
            this.placedTextBoxChange != shownPlacement.getChangeCount()) {
            this.placedTextBox       = placeDialog(textBox, dialog, shownPlacement, console.getInterfaceWidth());
            this.placedTextBoxBase   = textBox;
            this.placedTextBoxWidth  = console.getInterfaceWidth();
            this.placedTextBoxChange = shownPlacement.getChangeCount();
        }

        return this.placedTextBox;
    }

    /**
     * Places one dialog and its controls: a moved dialog or control at its place in the classic screen, which stands where the dialog's anchor
     * puts it in the interface width ({@link ScrConsolePlacement.Anchor#computeClassicLeft}), a hidden one past the interface's right edge, where
     * it is neither drawn nor hit; the controls of a hidden dialog are hidden with it. The minimap dialog first grows or shrinks with its area to
     * the minimap size of the placement, its bottom-left corner kept ({@link DialogLayout#createResizedLayout}), so that a moved dialog keeps its
     * top left and an unmoved one the bottom of its area.
     *
     * @param layout         the dialog
     * @param dialog         the dialog's name in the placement
     * @param placement      the placement
     * @param interfaceWidth the interface width
     * @return the placed dialog, or {@code layout} when neither it nor a control of it is placed and its size is the installed one
     */
    static @NotNull DialogLayout placeDialog(@NotNull DialogLayout layout,
                                             ScrConsolePlacement.@NotNull Dialog dialog,
                                             @NotNull ScrConsolePlacement placement,
                                             float interfaceWidth) {
        int minimapSize = placement.getMinimapSize();
        DialogLayout sized = dialog == ScrConsolePlacement.Dialog.MINIMAP && minimapSize != ScrConsolePlacement.MINIMAP_SIZE
                             ? layout.createResizedLayout(ScrConsolePlacement.MINIMAP_AREA_CONTROL, minimapSize, minimapSize)
                             : layout;
        int classicLeft = placement.getDialogAnchor(dialog).computeClassicLeft(interfaceWidth);
        int hiddenLeft = (int) Math.ceil(interfaceWidth);
        int[] controls = placement.getPlacedControls(dialog);
        int left = sized.getX();
        int top = sized.getY();

        if (placement.isDialogHidden(dialog)) {
            return sized.createPlacedLayout(hiddenLeft, top, identifier -> null);
        }

        if (placement.isDialogMoved(dialog)) {
            left = placement.getDialogLeft(dialog) + classicLeft;
            top  = placement.getDialogTop(dialog);
        } else if (controls.length == 0) {
            return sized;
        }

        int dialogTop = top;

        return sized.createPlacedLayout(left, top, identifier -> {
            if (placement.isControlHidden(dialog, identifier)) {
                return new int[]{hiddenLeft, dialogTop};
            }

            if (placement.isControlMoved(dialog, identifier)) {
                return new int[]{placement.getControlLeft(dialog, identifier) + classicLeft, placement.getControlTop(dialog, identifier)};
            }

            return null;
        });
    }

    /**
     * Draws the standard-definition console art of a race through the placement's mask, in the classic screen centered in the interface width.
     *
     * @param console   the console layout
     * @param loaded    the interface assets
     * @param race      the race: {@code 0} Zerg, {@code 1} Terran, {@code 2} Protoss
     * @param placement the masked placement
     * @throws IOException if the art cannot be decoded
     */
    private void drawMaskedArt(@NotNull ConsoleLayout console, @NotNull InterfaceAssets loaded, int race, @NotNull ScrConsolePlacement placement) throws IOException {
        if (this.maskedArt == null || this.maskedArtRace != race) {
            this.maskedArt       = loaded.decodeConsole(race);
            this.maskedArtRace   = race;
            this.maskedArtChange = -1;
        }

        InterfaceImage image = this.maskedArt;
        int width = image.getWidth();
        int height = image.getHeight();
        int top = ConsoleLayout.INTERFACE_HEIGHT - height;

        if (this.maskedArtTexture == 0 || this.maskedArtChange != placement.getChangeCount()) {
            byte[] rgba = image.getRgba().clone();

            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    if (!placement.isShown(column * ConsoleLayout.INTERFACE_WIDTH / width, top + row)) {
                        rgba[(row * width + column) * RGBA_BYTES + RGBA_ALPHA] = 0;
                    }
                }
            }

            if (this.maskedArtTexture != 0) {
                this.renderer.deleteTexture(this.maskedArtTexture);
            }

            this.maskedArtTexture = this.renderer.createTexture(width, height, rgba);
            this.maskedArtChange  = placement.getChangeCount();
        }

        float scale = console.getScale();

        this.renderer.drawTexture(this.maskedArtTexture, console.toScreenX(InterfaceTransform.getCenteredDialogLeft(console.getInterfaceWidth())),
                                  console.toScreenY(top), ConsoleLayout.INTERFACE_WIDTH * scale, height * scale, WHITE_RGBA);
    }


    /**
     * Releases the console art and its textures. The hit test reports no console until the next draw loads the art again.
     */
    private void releaseConsoleArt() {
        for (int index = this.pieceTextures.length - 1; index >= 0; index--) {
            int texture = this.pieceTextures[index];

            if (texture != 0) {
                IgnoreException.execute(
                        () -> this.renderer.deleteTexture(texture)
                );
            }

            this.pieceTextures[index] = 0;
        }

        if (this.consoleTexture != 0) {
            IgnoreException.execute(
                    () -> this.renderer.deleteTexture(this.consoleTexture)
            );
        }

        if (this.maskedArtTexture != 0) {
            IgnoreException.execute(
                    () -> this.renderer.deleteTexture(this.maskedArtTexture)
            );
        }

        IgnoreException.execute(
                this.maskLayer::release,
                this::releaseResourceIcons
        );

        this.consoleTexture   = 0;
        this.maskedArtTexture = 0;
        this.maskedArtChange  = -1;
        this.layoutWidth      = -1;
        this.hdLayout         = null;
        this.consoleArt       = null;
    }

    private void releaseTextures() {
        IgnoreException.execute(
                this::releaseMinimapTextures,
                this::releaseConsoleArt,
                this.pictureTextures::close,
                this.gameCursor::release,
                this.painter::release,
                this.images::releaseAll
        );

        this.minimapImage   = null;
        this.observerLayout = null;
    }

    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * @apiNote While this interface method is declared to throw {@code
     * Exception}, implementers are <em>strongly</em> encouraged to
     * declare concrete implementations of the {@code close} method to
     * throw more specific exceptions, or to throw no exception at all
     * if the close operation cannot fail.
     *
     * <p>
     * Cases where the close operation may fail require careful
     * attention by implementers. It is strongly advised to relinquish
     * the underlying resources and to internally <em>mark</em> the
     * resource as closed, prior to throwing the exception. The {@code
     * close} method is unlikely to be invoked more than once and so
     * this ensures that the resources are released in a timely manner.
     * Furthermore it reduces problems that could arise when the resource
     * wraps, or is wrapped, by another resource.
     *
     * <p>
     * <em>Implementers of this interface are also strongly advised
     * to not have the {@code close} method throw {@link
     * InterruptedException}.</em>
     *
     * <p>
     * This exception interacts with a thread's interrupted status,
     * and runtime misbehavior is likely to occur if an {@code
     * InterruptedException} is {@linkplain Throwable#addSuppressed
     * suppressed}.
     *
     * <p>
     * More generally, if it would cause problems for an
     * exception to be suppressed, the {@code AutoCloseable.close}
     * method should not throw it.
     *
     * <p>
     * Note that unlike the {@link Closeable#close close}
     * method of {@link Closeable}, this {@code close} method
     * is <em>not</em> required to be idempotent.  In other words,
     * calling this {@code close} method more than once may have some
     * visible side effect, unlike {@code Closeable.close} which is
     * required to have no effect if called more than once.
     *
     * <p>
     * However, implementers of this interface are strongly encouraged
     * to make their {@code close} methods idempotent.
     * @implSpec Releases the view's cached textures, edit attachment and timers. Client-owned renderer, fonts, cursor and storage remain available.
     */
    @Override
    public void close() {
        IgnoreException.execute(
                this::releaseTextures
        );

        if (this.portraitPlayer != null) {
            IgnoreException.execute(
                    () -> this.timers.killAll(this.portraitPlayer.getStatic())
            );
        }

        if (this.chatEdit != null) {
            IgnoreException.execute(
                    this.chatEdit::detach
            );
        }

        IgnoreException.execute(
                () -> this.timers.killAll(this.leaderboard),
                () -> this.timers.killAll(this.resourceBar),
                () -> this.timers.killAll(this.minimapPings)
        );
    }
}
