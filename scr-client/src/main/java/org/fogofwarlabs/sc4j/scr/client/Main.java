package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.base.log.Logging;
import org.fogofwarlabs.sc4j.engine.client.audio.MasterFade;
import org.fogofwarlabs.sc4j.engine.client.audio.PlayingSound;
import org.fogofwarlabs.sc4j.engine.client.console.ConsoleCommand;
import org.fogofwarlabs.sc4j.engine.client.console.ConsoleCommandRegistry;
import org.fogofwarlabs.sc4j.engine.client.cursor.CursorSurface;
import org.fogofwarlabs.sc4j.engine.client.dialog.DialogKey;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextClipboard;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextEditOperation;
import org.fogofwarlabs.sc4j.engine.client.dialog.WindowClipboard;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.game.ReplayAutosave;
import org.fogofwarlabs.sc4j.engine.client.game.SimulationWatchdog;
import org.fogofwarlabs.sc4j.engine.client.host.*;
import org.fogofwarlabs.sc4j.engine.client.host.DisplayMode;
import org.fogofwarlabs.sc4j.engine.client.input.*;
import org.fogofwarlabs.sc4j.engine.client.scene.LoadingService;
import org.fogofwarlabs.sc4j.engine.client.video.VideoClipDecoder;
import org.fogofwarlabs.sc4j.engine.client.world.*;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLink;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.scr.client.audio.*;
import org.fogofwarlabs.sc4j.scr.client.configuration.*;
import org.fogofwarlabs.sc4j.scr.client.cursor.GameCursor;
import org.fogofwarlabs.sc4j.scr.client.cursor.PointerSpeed;
import org.fogofwarlabs.sc4j.scr.client.debug.*;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueGameRequest;
import org.fogofwarlabs.sc4j.scr.client.glue.lobby.CreateGameScreen;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.GlueLoadPopup;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.GlueSavePopup;
import org.fogofwarlabs.sc4j.scr.client.glue.score.GlueResume;
import org.fogofwarlabs.sc4j.scr.client.glue.score.ScoreScreen;
import org.fogofwarlabs.sc4j.scr.client.loading.ClientDataLoadTask;
import org.fogofwarlabs.sc4j.scr.client.loading.HdImageSetLoadTask;
import org.fogofwarlabs.sc4j.scr.client.loading.TextTablesLoadTask;
import org.fogofwarlabs.sc4j.scr.client.ui.command.ButtonSetTable;
import org.fogofwarlabs.sc4j.scr.client.ui.command.CommandCard;
import org.fogofwarlabs.sc4j.scr.client.ui.command.CommandInput;
import org.fogofwarlabs.sc4j.scr.client.ui.command.HotkeyPress;
import org.fogofwarlabs.sc4j.scr.client.ui.console.*;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.*;
import org.fogofwarlabs.sc4j.scr.client.ui.input.*;
import org.fogofwarlabs.sc4j.scr.client.ui.menu.*;
import org.fogofwarlabs.sc4j.scr.client.ui.observer.ApmAlert;
import org.fogofwarlabs.sc4j.scr.client.ui.observer.ApmTracker;
import org.fogofwarlabs.sc4j.scr.client.ui.overlay.StatusOverlay;
import org.fogofwarlabs.sc4j.scr.client.ui.portrait.PortraitPlayer;
import org.fogofwarlabs.sc4j.scr.client.ui.text.ForceNamesUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.client.ui.text.StatText;
import org.fogofwarlabs.sc4j.scr.client.world.*;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedGraphics;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.FileNameUtils;
import org.fogofwarlabs.sc4j.scr.core.GameSettings;
import org.fogofwarlabs.sc4j.scr.data.format.casc.AssetResolver;
import org.fogofwarlabs.sc4j.scr.data.format.casc.GameLocaleUtils;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkUnit;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.format.replay.*;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.format.text.LegacyTextUtils;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsolePlacement;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrDrawList;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrScreenOverlay;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.map.MapStringSection;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerRelations;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.presentation.*;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayCommandDescription;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayCommandExecutor;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayCommandIssuer;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.PauseNotice;
import org.fogofwarlabs.sc4j.scr.game.session.ShuffleUtils;
import org.fogofwarlabs.sc4j.scr.game.session.setup.GameTemplate;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.load.ScrIscriptImporter;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameEnd;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.trigger.TransmissionPortraitTimer;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerEvents;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerPlayerResults;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalInterfaceState;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalKeyStates;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalView;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitRacesUtils;
import org.fogofwarlabs.sc4j.scr.net.lan.LanClient;
import org.fogofwarlabs.sc4j.scr.net.lan.LanLobby;
import org.fogofwarlabs.sc4j.scr.net.lan.LanSavedGame;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.fogofwarlabs.sc4j.scr.render.ClassicSceneRenderer;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.fogofwarlabs.sc4j.scr.render.SceneRenderer;
import org.fogofwarlabs.sc4j.scr.render.WorldRenderer;
import org.fogofwarlabs.sc4j.scr.render.hd.HdAnimations;
import org.fogofwarlabs.sc4j.scr.render.hd.HdImageSet;
import org.fogofwarlabs.sc4j.scr.render.hd.HdRenderAssets;
import org.fogofwarlabs.sc4j.scr.render.hd.HdSceneRenderer;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFonts;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceRenderer;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceTransform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.List;
import java.util.function.IntBinaryOperator;

/**
 * Runs the development client, its menus, rendering resources, game sessions and replay controls.
 */
public final class Main {
    private static final @NotNull Logger LOGGER = Logger.of(Main.class);

    /**
     * The unit types with a {@code units.dat} ready sound (types {@code 0..105}).
     */
    private static final int READY_SOUND_TYPES = 106;

    private static final int LEFT_MOUSE_BUTTON_MASK = 0x02;
    private static final int RIGHT_MOUSE_BUTTON_MASK = 0x08;
    private static final int MIDDLE_MOUSE_BUTTON_MASK = 0x20;
    private static final int ATTACK_UNIT_BLINK_COUNT = 20;
    private static final int ATTACK_UNIT_BLINK_COLOR = 174;
    private static final int ATTACK_AREA_BLINK_COUNT = 8;
    private static final int ATTACK_AREA_BLINK_COLOR = 0x10;
    private static final int UNIT_POINT_FRACTION_BITS = 8;
    private static final int OPAQUE_ARGB = 0xFF000000;

    /**
     * The Zerg race byte of {@link MeleeGameSetup}.
     */
    private static final int ZERG_RACE = 0;

    /**
     * The Terran race byte of {@link MeleeGameSetup}.
     */
    private static final int TERRAN_RACE = 1;

    /**
     * The Protoss race byte of {@link MeleeGameSetup}.
     */
    private static final int PROTOSS_RACE = 2;

    /**
     * The gain of a silent mixer group.
     */
    private static final float SILENT_GAIN = 0.0f;

    /**
     * The blink count of a talking alert.
     */
    private static final int ALERT_BLINK_COUNT = 8;

    /**
     * The color index of notification alerts.
     */
    private static final int NOTIFICATION_COLOR_INDEX = 0x11;

    /**
     * The score screen's local player after a replay without a human record.
     */
    private static final int REPLAY_VIEWER_SCORE_PLAYER = -1;

    /**
     * The complete unsigned-byte identifier space of replay players.
     */
    private static final int REPLAY_PLAYER_IDENTIFIER_COUNT = 256;

    /**
     * The unsupported-region failure message key.
     */
    private static final @NotNull String EUD_UNSUPPORTED_TEXT = "strERROR_NOT_SUPPORTED_EUD_MAP";

    /**
     * The fully unsupported-region failure message key.
     */
    private static final @NotNull String EUD_UNSUPPORTED_AT_ALL_TEXT = "strERROR_NOT_SUPPORTED_AT_ALL_EUD_MAP";

    /**
     * The second-string format of a region failure box.
     */
    private static final @NotNull String EUD_ERROR_FORMAT = "EUD ERROR: 0x%08X";

    /**
     * The targeting prompt key.
     */
    private static final @NotNull String TARGET_PROMPT_KEY = "strTARGET";

    /**
     * The placement prompt key.
     */
    private static final @NotNull String LOCATION_PROMPT_KEY = "strLOCATION";

    /**
     * The window-mode toggle debounce interval.
     */
    private static final long WINDOW_MODE_DEBOUNCE_MILLISECONDS = 1000;

    /**
     * The window-mode toggle time before the first toggle, far enough in the past that the debounce never holds it.
     */
    private static final long NO_WINDOW_MODE_TIME = Long.MIN_VALUE / 2;

    /**
     * The game-frame cooldown after an off-screen attack alert.
     */
    private static final int ATTACK_ALERT_COOLDOWN_FRAMES = 100;

    /**
     * The world-coordinate margin around the visible camera rectangle for attack alerts.
     */
    private static final int ATTACK_ALERT_CAMERA_MARGIN = 64;


    /**
     * The system property naming the text-position probe file (developer harness).
     */
    private static final @NotNull String TEXT_PROBE_PROPERTY = "sc4j.textProbe";

    /**
     * The system property whose value {@code true} makes LAN game turns carry Keep Alive instead of Sync (debug switch).
     */
    private static final @NotNull String LAN_KEEP_ALIVE_PROPERTY = "sc4j.lanKeepAlive";

    /**
     * The tolerance for treating camera zoom as the reference zoom.
     */
    private static final float ZOOM_TOLERANCE = 1e-4f;

    /**
     * The network pause-message key.
     */
    private static final @NotNull String PAUSED_TEXT = "strPAUSED_sd";

    /**
     * The network resume-message key.
     */
    private static final @NotNull String RESUMED_TEXT = "strRESUMED_s";

    /**
     * The list control of the message history dialog ({@code chathist.ui.json}).
     */
    private static final int CHAT_HISTORY_LIST = 1;

    /**
     * No staged latency.
     */
    private static final int NO_LATENCY = -1;

    /**
     * The default turns in transit for new and saved LAN games.
     */
    private static final int DEFAULT_NETWORK_TURNS_IN_TRANSIT = 3;

    /**
     * The build identity of a client started without the SC4J runtime.
     */
    private static final @NotNull String UNKNOWN_BUILD = "unknown (client started without the SC4J runtime)";

    /**
     * The network latency-message keys in latency order.
     */
    private static final @NotNull List<String> LATENCY_MESSAGES = List.of("strFIRSTLATENCY_s", "strFIRSTLATENCY_s-1", "strFIRSTLATENCY_s-2");

    /**
     * The numbered game-tip text table.
     */
    private static final @NotNull String TIPS_TABLE = "tips_txt.json";

    /**
     * The window title, the same in every mode.
     */
    private static final @NotNull String WINDOW_TITLE = "SC4J";

    private static final long ASSET_WARNING_NANOSECONDS = 7_000L * 1_000_000L;

    private static final long SEEK_BUDGET_NANOSECONDS = 50_000_000L;

    private static final long PLAYBACK_BUDGET_NANOSECONDS = 30_000_000L;

    /**
     * The names of the speed settings {@code 0..6} in the replay status lines.
     */
    private static final @NotNull String @NotNull [] REPLAY_SPEED_NAMES = {"Slowest", "Slower", "Slow", "Normal", "Fast", "Faster", "Fastest"};

    /**
     * Milliseconds in one game-clock or host-clock second.
     */
    private static final int MILLISECONDS_PER_SECOND = 1000;

    /**
     * The per-update asynchronous loading budget.
     */
    private static final long LOADING_BUDGET_NANOSECONDS = 12_000_000L;

    /**
     * The lifetime of a displayed status message.
     */
    private static final long STATUS_DURATION_NANOSECONDS = 3_000_000_000L;

    private static final int TEXT_COLOR = 0xDAE3ECFF;
    private static final int MUTED_COLOR = 0x91A0B5FF;
    private static final int ACCENT_COLOR = 0x59D4B4FF;
    private static final int WARNING_COLOR = 0xECC77BFF;

    /**
     * Opaque black in the renderer packed RGBA layout.
     */
    private static final int PILLARBOX_COLOR = 0x000000FF;

    /**
     * The screen-coordinate inset at each horizontal end of the replay timeline.
     */
    private static final int TIMELINE_HORIZONTAL_INSET = 8;

    /**
     * The combined horizontal insets removed from the replay timeline width.
     */
    private static final int TIMELINE_HORIZONTAL_INSETS = TIMELINE_HORIZONTAL_INSET * 2;

    /**
     * The screen-coordinate height of the replay timeline background and progress fill.
     */
    private static final int TIMELINE_STRIP_HEIGHT = 8;

    /**
     * The maximum selected handles displayed individually in the command status line.
     */
    private static final int COMMAND_STATUS_HANDLE_LIMIT = 4;

    private static final int END_NONE = 0;
    static final int END_BOX = 1;

    /**
     * The auto-close timer identifier used by a defeat box.
     */
    private static final int OUTCOME_CLOSE_TIMER = 0;

    /**
     * The default presentation limit while a game screen runs.
     */
    private static final int NATIVE_GAME_FPS_LIMIT = 300;

    /**
     * The default presentation limit outside the game screen.
     */
    private static final int NATIVE_MENU_FPS_LIMIT = 100;

    /**
     * Test harness: a live game runs unpaced up to this frame ({@code -Dsc4j.fastForwardTo}), or {@code -1}.
     */
    private static final long FAST_FORWARD_FRAME = Long.getLong("sc4j.fastForwardTo", -1L);

    /**
     * How long a command reply stays in the message area: long enough to read a page of help.
     */
    private static final int COMMAND_REPLY_MILLISECONDS = 20_000;

    /**
     * The maximum byte length retained for the next scenario name.
     */
    private static final int NEXT_SCENARIO_LENGTH = 31;

    private static final int MINIMUM_WINDOW_WIDTH = 960;
    private static final int MINIMUM_WINDOW_HEIGHT = 640;
    private static final int UPLOAD_WINDOW_WIDTH = 1;
    private static final int UPLOAD_WINDOW_HEIGHT = 1;

    private static final double MAXIMUM_INPUT_DELTA_SECONDS = 0.1;
    /**
     * The most interface ticks one pass runs; a longer pause between passes drops the remaining ticks.
     */
    private static final int MAXIMUM_INTERFACE_TICKS = 4;
    private static final double ICONIFIED_WINDOW_WAIT_SECONDS = 0.05;
    private static final int LAUNCH_COMMAND_READY_FRAME = 5;

    private static final int TEXT_PROBE_LINE_FEED = 0x0A;

    /**
     * The number of digit keys, {@code 0..9}.
     */
    private static final int DIGIT_KEY_COUNT = 10;

    /**
     * The virtual key of the digit {@code 9}, the last digit key after {@link VirtualKeys#DIGIT_0}.
     */
    private static final int LAST_DIGIT_VIRTUAL_KEY = VirtualKeys.DIGIT_0 + DIGIT_KEY_COUNT - 1;

    /**
     * The last virtual key of the asynchronous key range that starts at {@link VirtualKeys#DIGIT_0}.
     */
    private static final int LAST_ASYNC_VIRTUAL_KEY = 0x3B;

    private static final int PRINTABLE_CHARACTER_MINIMUM = 32;
    private static final int DELETE_CHARACTER = 127;
    private static final int ASCII_CHARACTER_LIMIT = 0x80;
    private static final int ESCAPE_HOTKEY = 27;

    private static final int MESSAGE_TEXT_WIDTH_RESERVE = 20;
    private static final float HALF_TILE_SIZE = TerrainTileset.TILE_SIZE / 2.0f;

    /**
     * The divisor that halves a length to its center.
     */
    private static final int CENTER_DIVISOR = 2;

    /**
     * The divisor that halves a length to its center in floating point.
     */
    private static final float CENTER_FLOAT_DIVISOR = 2.0f;
    /**
     * The packed color that draws a texture unchanged.
     */
    private static final int OPAQUE_WHITE_COLOR = 0xFFFFFFFF;

    /**
     * The fraction of a length at its center.
     */
    private static final float CENTER_FRACTION = 0.5f;

    /**
     * The diameter of a circle in radii.
     */
    private static final int DIAMETER_PER_RADIUS = 2;

    private static final int INITIALIZATION_PANEL_INSET = 24;
    private static final int INITIALIZATION_PANEL_HORIZONTAL_INSETS = 48;
    private static final int INITIALIZATION_PANEL_HEIGHT = 156;
    private static final int INITIALIZATION_PANEL_COLOR = 0x141B26EE;
    private static final int INITIALIZATION_TEXT_LEFT = 42;
    private static final int INITIALIZATION_TITLE_TOP = 46;
    private static final float INITIALIZATION_TITLE_SCALE = 1.8f;
    private static final int INITIALIZATION_TEXT_HORIZONTAL_INSETS = 84;
    private static final int INITIALIZATION_CHARACTER_WIDTH = 9;
    private static final int INITIALIZATION_STATUS_LINE_LIMIT = 3;
    private static final float INITIALIZATION_BODY_TOP = 82.0f;
    private static final int INITIALIZATION_BODY_LINE_HEIGHT = 22;
    private static final float INITIALIZATION_BODY_SCALE = 1.4f;

    private static final int INSPECTION_MINIMUM_RADIUS = 8;
    private static final int INSPECTION_WORLD_RADIUS = 18;
    private static final int INSPECTION_OUTLINE_THICKNESS = 2;
    private static final int NO_REPLAY_TEXT_LEFT = 42;
    private static final int NO_REPLAY_TEXT_TOP = 64;
    private static final float NO_REPLAY_TEXT_SCALE = 1.5f;
    private static final int LOADING_BAR_MAXIMUM_WIDTH = 480;
    private static final int LOADING_BAR_HORIZONTAL_INSETS = 84;
    private static final int LOADING_CAPTION_GAP = 34;
    private static final float LOADING_CAPTION_SCALE = 1.5f;
    private static final int LOADING_BAR_HEIGHT = 10;
    private static final int LOADING_BAR_COLOR = 0x2A3442FF;

    private static final int GAMMA_RAMP_BLUE_CHANNEL_INDEX = 2;
    private static final int HD2_ASSET_SCALE = 2;
    private static final int HD_ASSET_SCALE = 4;

    private static final int PERCENT_SCALE = 100;
    private static final int DRAG_OUTLINE_THICKNESS = 1;
    private static final int DRAG_OUTLINE_COLOR = 0x10FC18FF;
    private static final int PLACEMENT_VALID_COLOR = 0x1AFF1A40;
    private static final int PLACEMENT_INVALID_COLOR = 0xFF1A1A40;
    private static final int PING_SENDER_INDEX = 0;
    private static final int PING_X_INDEX = 1;
    private static final int PING_Y_INDEX = 2;
    private static final int LATENCY_NOTICE_SLOT_INDEX = 0;
    private static final int LATENCY_NOTICE_LATENCY_INDEX = 1;
    private static final int START_SCREEN_X_INDEX = 0;
    private static final int START_SCREEN_Y_INDEX = 1;
    private static final int KEYBOARD_DIRECTION_X_INDEX = 0;
    private static final int KEYBOARD_DIRECTION_Y_INDEX = 1;
    /**
     * The width of the camera scroll edge band in interface pixels.
     */
    private static final int SCROLL_EDGE_WIDTH = 2;
    /**
     * The alignment of a center view target in pixels.
     */
    private static final int CENTER_VIEW_ALIGNMENT = 8;
    /**
     * The step table row of a center view scroll.
     */
    private static final int CENTER_VIEW_SPEED = 6;
    /**
     * The number of camera slots (F2, F3, F4).
     */
    private static final int CAMERA_SLOT_COUNT = 3;
    /**
     * The alignment of a centered camera position in pixels.
     */
    private static final int VIEW_SNAP = 8;
    private static final int SMOOTH_CENTER_VIEW_FLAG = 1;
    private static final int OBJECTIVES_TEXT_CONTROL = 2;

    private static final int UNIT_INSPECTION_TEXT_HEIGHT = 13;
    private static final int UNIT_INSPECTION_PANEL_HORIZONTAL_INSET = 8;
    private static final int UNIT_INSPECTION_PANEL_TOP = 40;
    private static final int UNIT_INSPECTION_PANEL_HORIZONTAL_PADDING = 16;
    private static final int UNIT_INSPECTION_ROW_HEIGHT = 16;
    private static final int UNIT_INSPECTION_PANEL_VERTICAL_PADDING = 12;
    private static final int UNIT_INSPECTION_PANEL_COLOR = 0x000000B0;
    private static final int UNIT_INSPECTION_TEXT_HORIZONTAL_INSET = 8;
    private static final int UNIT_INSPECTION_TEXT_TOP = 46;
    private static final int UNIT_INSPECTION_TEXT_COLOR = 0xE0F0FFFF;
    private static final int GOOD_FRAME_RATE_THRESHOLD = 55;
    private static final int WARNING_FRAME_RATE_THRESHOLD = 30;

    private static final int STATUS_TEXT_HEIGHT = 15;
    private static final int STATUS_HORIZONTAL_PADDING = 20;
    private static final int STATUS_PANEL_TOP = 40;
    private static final int STATUS_PANEL_HEIGHT = 24;
    private static final int STATUS_PANEL_COLOR = 0x0B1018E0;
    private static final int STATUS_TEXT_HORIZONTAL_INSET = 10;
    private static final int STATUS_TEXT_TOP = 44;
    private static final int STATUS_TEXT_COLOR = 0xE6ECF2FF;
    private static final int DEBUG_PANEL_CONSOLE_BOTTOM_GAP = 6;
    private static final int DEBUG_PANEL_WINDOW_BOTTOM_GAP = 8;
    private static final int DEBUG_PANEL_HORIZONTAL_INSET = 8;
    private static final int DEBUG_PANEL_TOP = 36;

    private static final int TIMELINE_STRIP_TOP = 6;
    private static final int TIMELINE_BACKGROUND_COLOR = 0x0A0F16C0;
    private static final int TIMELINE_PROGRESS_COLOR = 0x4F8FB0FF;
    private static final int TIMELINE_MARKER_HORIZONTAL_INSET = 1;
    private static final int TIMELINE_MARKER_TOP = 3;
    private static final int TIMELINE_MARKER_WIDTH = 3;
    private static final int TIMELINE_MARKER_HEIGHT = 14;
    private static final int TIMELINE_MARKER_COLOR = 0xE0B050FF;
    private static final int TIMELINE_CLICK_HEIGHT = 18;

    /**
     * Starts the client.
     *
     * @param arguments the launch options; {@code --help} describes available options
     * @throws IOException if the data directory holds no data pack, the startup storage is missing or invalid, a screenshot cannot be written or a
     *                     bounded replay load fails
     */
    public static void main(@NotNull String @NotNull [] arguments) throws IOException {
        ConsoleOutputUtils.install();

        String[] launchArguments = CommandLineArgumentsUtils.recover(arguments);

        start(ClientOptions.parse(launchArguments), launchArguments, null, null, UNKNOWN_BUILD);
    }

    /**
     * Starts the client below a given root: relative launch paths, the settings file and the client folders (maps, replays, saves,
     * characters, cache) resolve there. The SC:R mod entry starts the client this way with the mod's storage folder as the root. A log file
     * that is already open, such as the runtime's, stays the log file.
     *
     * @param layout    the client root and its folders
     * @param resources the mod's resource provider, which the client context reads and closes
     * @param arguments the launch options; {@code --help} describes available options
     * @throws IOException if the data directory holds no data pack, the startup storage is missing or invalid, a screenshot cannot be written or a
     *                     bounded replay load fails
     */
    public static void launch(@NotNull InstallLayout layout, @NotNull ScrResourceProvider resources, @NotNull String @NotNull [] arguments) throws IOException {
        launch(layout, resources, null, UNKNOWN_BUILD, arguments);
    }

    /**
     * Starts the client below a given root with the run's Beacon.Link, which the multiplayer menu offers next to LAN, and the run's build
     * identity: as {@link #launch(InstallLayout, ScrResourceProvider, String[])}.
     *
     * @param layout     the client root and its folders
     * @param resources  the mod's resource provider, which the client context reads and closes
     * @param beaconLink the run's Beacon.Link, or {@code null} if the run offers none
     * @param build      the run's build identity, which the desync dumps of network games name
     * @param arguments  the launch options; {@code --help} describes available options
     * @throws IOException if the data directory holds no data pack, the startup storage is missing or invalid, a screenshot cannot be written or a
     *                     bounded replay load fails
     */
    public static void launch(@NotNull InstallLayout layout, @NotNull ScrResourceProvider resources,
                              @Nullable BeaconLink beaconLink, @NotNull String build, @NotNull String @NotNull [] arguments) throws IOException {
        ConsoleOutputUtils.install();
        start(ClientOptions.parse(arguments, layout), arguments, resources, beaconLink, build);
    }

    private static void start(@NotNull ClientOptions options, @NotNull String @NotNull [] launchArguments,
                              @Nullable ScrResourceProvider resources, @Nullable BeaconLink beaconLink, @NotNull String build) throws IOException {
        options.getInstallLayout().applyProcessProperties();
        Logging.setLevel(options.getLogLevel());
        installTextProbe(options.getInstallLayout());

        if (options.isLogFileEnabled() && !Logging.isFileEnabled()) {
            try {
                Logging.enableFile(options.getInstallLayout().getLogDirectory());
            } catch (IOException exception) {
                LOGGER.warning("LOG FILE DISABLED: {0}", exception.getMessage());
            }
        }

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            StringWriter trace = new StringWriter();

            throwable.printStackTrace(new PrintWriter(trace));
            LOGGER.error("UNCAUGHT EXCEPTION in thread " + thread.getName() + ": " + trace);
        });
        LOGGER.info("CLIENT START: " + String.join(" ", launchArguments));

        if (options.isHelp()) {
            LaunchHelpUtils.print();

            return;
        }

        if (options.getMelee() != null && "list".equals(options.getMelee().getCampaign())) {
            for (String line : ClientSession.listCampaignMissions(options.requireGameDirectory())) {
                System.out.println(line);
            }

            return;
        }

        new Main(options, resources, beaconLink, build).run();
        LOGGER.info("CLIENT END");
    }

    /**
     * Starts a LAN game whose lobby counted down: the lobby's table resolves with the host's seed, the local player plays its own record, and the
     * game takes over the lobby's turn stream with three own turns in transit. The system property {@link #LAN_KEEP_ALIVE_PROPERTY} set to
     * {@code true} is a debug switch: turns then carry Keep Alive instead of Sync.
     * <p>
     * The game writes its desync dump to the desync folder ({@link LiveGame#setDesyncDumpTarget}).
     *
     * @param clientContext the client resources
     * @param request       the network game request of the lobby
     * @param gameDirectory the StarCraft: Remastered data folder
     * @param locale        the session locale, or {@code null} for the installed one
     * @param build         the run's build identity, which the desync dump names
     * @return the started session
     * @throws IOException if the LAN game has no lobby or map, or the game cannot be loaded
     */
    private static @NotNull ClientSession startNetworkGame(@NotNull ClientContext clientContext,
                                                           @NotNull GlueGameRequest request,
                                                           @NotNull Path gameDirectory,
                                                           @Nullable String locale, @NotNull String build) throws IOException {
        LanClient lanClient = request.getLanClient();
        Path mapPath = request.getPath();
        LanLobby lobby = lanClient == null ? null : lanClient.getLobby();
        ProviderSession providerSession = lanClient == null ? null : lanClient.getSession();

        if (mapPath == null || lobby == null || providerSession == null) {
            throw Exceptions.newIOException("The LAN game has no lobby or map.");
        }

        LanSavedGame saved = lanClient.getSavedGame();

        if (saved != null) {
            ClientSession started = ClientSession.startNetworkSavedGame(gameDirectory,
                                                                        clientContext.getStorage(), clientContext.getGameData(),
                                                                        saved.getFile(), lobby.createSavedGamePlayers(),
                                                                        providerSession.getGameName(), lobby.getTurnRate(),
                                                                        providerSession.getLocalIdentifier(),
                                                                        lanClient.createTurnTransport(),
                                                                        DEFAULT_NETWORK_TURNS_IN_TRANSIT, Boolean.getBoolean(LAN_KEEP_ALIVE_PROPERTY),
                                                                        locale);

            attachChats(started, lanClient, request.getCommunityChat());
            started.setGameFlags(lanClient.getGameFlags());
            setDesyncDumpTarget(started, clientContext, mapPath, build);

            return started;
        }

        MpqArchive mapArchive = MpqArchive.open(mapPath);
        MeleeGameSetup setup = lobby.createGameSetup(mapArchive.readFile(ChkFile.ARCHIVE_ENTRY), request.getSpeed(), request.isExpansion());

        setup.setMapArchive(mapArchive);
        setup.setClassicLimits((lanClient.getGameFlags() & CreateGameScreen.ORIGINAL_LIMITS_FLAG) != 0);
        LOGGER.info("LAN GAME SETUP: local player " + providerSession.getLocalIdentifier() + ", seed 0x" + Integer.toHexString(lobby.getSeed()) +
                    ", speed " + request.getSpeed() + ", options " + HexFormat.of().formatHex(providerSession.getGameData()));

        CommandFeed feed = CommandFeed.fromProperty(clientContext.getInstallLayout());
        ClientSession started = ClientSession.startNetwork(gameDirectory,
                                                           clientContext.getStorage(), clientContext.getGameData(),
                                                           mapPath, setup, feed != null ? feed.getSeed() : lobby.getSeed(),
                                                           providerSession.getLocalIdentifier(),
                                                           lanClient.createTurnTransport(),
                                                           DEFAULT_NETWORK_TURNS_IN_TRANSIT, Boolean.getBoolean(LAN_KEEP_ALIVE_PROPERTY),
                                                           locale);

        attachChats(started, lanClient, request.getCommunityChat());
        started.setGameFlags(lanClient.getGameFlags());
        setDesyncDumpTarget(started, clientContext, mapPath, build);

        if (feed != null) {
            feed.attach(started);
        }

        return started;
    }

    /**
     * Attaches the game's chat to the LAN client and, for a game over Beacon.Link with the channel chat, the community.
     *
     * @param started       the started session
     * @param lanClient     the LAN client of the game
     * @param communityChat the community part of the game, or {@code null}
     */
    private static void attachChats(@NotNull ClientSession started, @NotNull LanClient lanClient, @Nullable GameCommunityChat communityChat) {
        started.attachNetworkChat(lanClient);

        if (communityChat != null) {
            started.attachCommunityChat(communityChat);
        }
    }

    private static void setDesyncDumpTarget(@NotNull ClientSession started, @NotNull ClientContext clientContext, @NotNull Path mapPath,
                                            @NotNull String build) {
        LiveGame liveGame = started.getLiveGame();
        Path mapName = mapPath.getFileName();

        if (liveGame != null) {
            liveGame.setDesyncDumpTarget(clientContext.getInstallLayout().getDesyncDirectory(), mapName == null ? "" : mapName.toString(), build);
        }
    }

    /**
     * Returns the launch option name of a race byte.
     *
     * @param race a race byte of {@link MeleeGameSetup}
     * @return its launch option name
     */
    private static @NotNull String getRaceName(int race) {
        return switch (race) {
            case ZERG_RACE -> "zerg";
            case TERRAN_RACE -> "terran";
            case PROTOSS_RACE -> "protoss";
            default -> "random";
        };
    }

    private static @NotNull String describeUnit(@Nullable Unit unit) {
        return unit == null ? "none"
                            : String.format(Locale.ROOT, "unit %d type %d at %d,%d", unit.getIndex(), unit.getUnitId(), unit.getPositionX(),
                                            unit.getPositionY());
    }

    /**
     * Installs the text-position probe when {@code -Dsc4j.textProbe=<file.csv>} is given (developer harness): every text laid out by
     * {@link InterfaceText} with a rectangle is written once per text, format and rectangle as
     * {@code format;top;bottom;inkTop;inkBottom;trueType;text}.
     *
     * @param installLayout the selected client directories
     */
    private static void installTextProbe(@NotNull InstallLayout installLayout) {
        String target = System.getProperty(TEXT_PROBE_PROPERTY);

        if (target == null) {
            return;
        }

        Path path = installLayout.resolveUserPath(Path.of(target));
        Set<String> seen = new HashSet<>();

        InterfaceText.setProbe((text, format, top, bottom, inkTop, inkBottom, trueType) -> {
            if (bottom < top) {
                return;
            }

            String line = String.format(Locale.ROOT, "%x;%.2f;%.2f;%.2f;%.2f;%b;%s", format, top, bottom, inkTop, inkBottom, trueType,
                                        text.replace((char) TEXT_PROBE_LINE_FEED, '|').replace(';', ','));

            if (!seen.add(line)) {
                return;
            }

            try {
                Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                LOGGER.warning("TEXT PROBE: " + exception.getMessage());
            }
        });
    }

    /**
     * Closes a resource of the client teardown when it was created.
     *
     * @param resource the resource, or {@code null} when it was never created
     * @throws Exception if the resource cannot be closed
     */
    private static void closeResource(@Nullable AutoCloseable resource) throws Exception {
        if (resource != null) {
            resource.close();
        }
    }

    /**
     * Converts a {@code WindowMode} of the graphics settings to the host window's display mode.
     *
     * @param windowMode {@link GraphicsSettings#WINDOWED}, {@link GraphicsSettings#WINDOWED_FULLSCREEN} or {@link GraphicsSettings#FULLSCREEN}
     * @return the display mode
     */
    private static @NotNull DisplayMode toDisplayMode(int windowMode) {
        return switch (windowMode) {
            case GraphicsSettings.WINDOWED -> DisplayMode.WINDOWED;
            case GraphicsSettings.FULLSCREEN -> DisplayMode.FULLSCREEN;
            default -> DisplayMode.BORDERLESS;
        };
    }

    private static void addHudLine(@NotNull List<String> lines, @NotNull List<Integer> colors, @NotNull String line, int color) {
        lines.add(line);
        colors.add(color);
    }

    /**
     * The host of the run ({@link ClientHost}), or {@code null} before the run starts.
     */
    private @Nullable ClientHost clientHost;

    /**
     * The host's client window ({@link ClientHost}), or {@code null} before the window exists.
     */
    private @Nullable HostWindow hostWindow;

    /**
     * The system clipboard of the client window; without the window it holds nothing.
     */
    private final @NotNull TextClipboard clipboard;

    /**
     * The hidden window of the HD image upload thread's shared context, or {@code null}.
     */
    private @Nullable HostWindow uploadWindow;

    private @Nullable WorldRenderer renderer;

    /**
     * The frames the frame loop has drawn, and the host time of the previous frame's input update in seconds.
     */
    private int renderedFrames;
    private double previousFrameTime;

    private @Nullable String statusMessage;
    private long statusDeadline;

    private @Nullable List<DebugPanel.Section> debugSections;

    /**
     * The running session; the stall watchdog's thread reads it ({@link LiveReplayWriter#saveStalled}).
     */
    volatile @Nullable ClientSession session;

    /**
     * The game the glue asked for; {@link #loadReplay} starts it instead of reading {@link #pendingReplay}.
     */
    private @Nullable GlueGameRequest glueRequest;

    /**
     * The game the running session was started for by the glue, or {@code null} for a launch option game.
     */
    private @Nullable GlueGameRequest sessionGlueRequest;
    /**
     * Whether the running glue game uses the classic unit limits (game flag bit {@code 4}, which also disables Save Replay).
     */
    private boolean sessionClassicLimits;

    private @Nullable String errorMessage;

    private @Nullable String assetWarning;
    private long assetWarningTime;
    private @Nullable String reportedAssetWarning;

    float pointerHorizontal;
    float pointerVertical;
    /**
     * Whether the pointer holds a position the window or an input script reported (none before the first).
     */
    private boolean pointerKnown;

    boolean playing;

    /**
     * The writer of the live games' replays: at the end, from the stall watchdog's thread, under a chosen name.
     */
    private final @NotNull LiveReplayWriter replayWriter;
    /**
     * The watchdog of the sessions' simulation frames; a stalled frame writes the running game's replay.
     */
    private final @NotNull SimulationWatchdog simulationWatchdog;

    @Nullable GameMenu gameMenu;

    /**
     * The network waiting dialog, created with the game menu.
     */
    @Nullable NetworkWaitDialog networkWaitDialog;

    int endPhase;
    private boolean outcomeVictory;
    private @Nullable OutcomeBox outcomeBox;
    /**
     * Whether Continue Playing closed the outcome box for the rest of this game.
     */
    private boolean outcomeDone;

    /**
     * The game frame a launch {@code /wait} holds the remaining commands until.
     */
    private long pendingCommandFrame;

    private @Nullable Path pendingShot;

    /**
     * The test-only input script ({@code --input-script}), fed through the window's input paths.
     */
    private @Nullable InputScript inputScript;
    private long inputScriptFrame;

    /**
     * The frame file of {@code /graphics <quality> <file>}: the first loading frame is captured.
     */
    private @Nullable Path pendingLoadingShot;

    /**
     * The supplied local-interface mouse-button word.
     */
    private int mouseButtonsWord;
    /**
     * The mouse position supplied to the local view this frame, in game-surface pixels.
     */
    private int suppliedMouseX;
    private int suppliedMouseY;

    /**
     * The supplied local-interface chat mode.
     */
    private int chatModeByte;
    private boolean chatWasOpen;

    private @Nullable ViewJumps viewJumps;

    /**
     * The gamma ramp of {@link #appliedGamma} that {@link #applyGamma()} hands the window, or {@code null} before the first frame.
     */
    private short @Nullable [] gammaRamp;

    private boolean blankCursorSet;

    /**
     * The platform pointer of the window: it shows the game cursor where the cursor is not drawn into the frame, and the blank pointer of a
     * captured game view; created on first use.
     */
    private @Nullable CursorSurface cursorSurface;

    /**
     * Whether the music is ducked for the trigger's unit speech mute.
     */
    private boolean musicDucked;

    private @Nullable String liveStopMessage;

    /**
     * Whether the session ends once the open fatal notice closes: a network game that a simulation failure stopped (it left already), or a game that
     * an EUD failure ended.
     */
    private boolean sessionEndsWithNotice;

    private @Nullable UnitSpeech unitSpeech;

    private UnitSpeech.@Nullable Output unitSpeechOutput;

    private long alertCooldownEnd;

    private @Nullable ApmTracker apmTracker;

    /**
     * The running local-player action counter.
     */
    private @Nullable ApmTracker liveApmTracker;
    private @Nullable ClientSession liveApmSession;

    private @Nullable ClientSession apmSession;

    private boolean automaticPlaybackStarted;

    private boolean seekSequenceEnded;

    /**
     * The mod's resource provider, or {@code null} when the client opens its own from the game directory.
     */
    private final @Nullable ScrResourceProvider resources;
    /**
     * The Beacon.Link sessions of the multiplayer screens: the run's Beacon.Link at the nodes of the runtime's command line.
     */
    private final @NotNull BeaconLinkAccess beaconLink;
    /**
     * The run's build identity, which the desync dumps of network games name.
     */
    private final @NotNull String build;
    /**
     * The client resources for the whole run ({@link ClientContext}), opened on first use.
     */
    @Nullable ClientContext clientContext;

    @Nullable ConsoleView consoleView;

    @Nullable ConsoleModel consoleModel;

    @Nullable WorldInterface worldInterface;

    private boolean systemCursorHidden;

    /**
     * The sound names of the session ({@code rez/sfx.json}), or {@code null} before one is loaded.
     */
    private @Nullable SoundTable soundTable;

    /**
     * The interface scroll ticks this pass runs.
     */
    private int interfaceTicksDue;

    /**
     * The SD interface layer being drawn ({@link #drawSdInterface}), or {@code 0}.
     */
    private int sdInterfaceFramebuffer;
    private int sdInterfaceWidth;
    private int sdInterfaceHeight;
    /**
     * Whether this frame's interface went into the SD interface layer.
     */
    private boolean sdInterfaceDrawn;

    boolean debugView;

    private int debugVisionPlayer;

    /**
     * Optional per-frame phase timing ({@link FrameTiming}); {@code null} when disabled.
     */
    private final @Nullable FrameTiming frameTiming;

    /**
     * The progress of the HD preload shown by a loading frame, or a negative value when none is shown.
     */
    private float loadingProgress;
    /**
     * The one loading mechanism: startup, graphics changes (F5, glue included) and game start run their tasks here.
     */
    private final @NotNull LoadingService loading;

    private final @NotNull DebugPanel debugPanel;

    /**
     * The status texts over every screen: the frame rate and the turn rate.
     */
    private final @NotNull StatusOverlay statusOverlay;

    /**
     * Whether the status overlay of the frame being presented is already drawn.
     */
    private boolean overlayComposed;
    /**
     * Whether the frame being presented is already composed: the status overlay and the cursor drawn over it.
     */
    private boolean frameComposed;

    final @NotNull GameClientState gameClientState;

    /**
     * The next launch command of a live game ({@code --command}), or {@code -1} when none is pending.
     */
    private int pendingLiveCommand;

    private final @NotNull DeveloperTextLayer developerTexts;

    final @NotNull TransmissionPortraitTimer transmissionTimer;

    /**
     * The client's screen geometry in game, the only source of the game view, the interface placement and the camera limits.
     */
    final @NotNull GameViewport gameViewport;

    /**
     * The fullscreen mode used when returning from windowed mode.
     */
    private int fullscreenMode;
    private int appliedGamma;

    private final @NotNull SteppedScrollRate scrollRate;
    private final @NotNull CameraScroll cameraScroll;

    private @NotNull PointerMode pointerMode;
    /**
     * The pointer clip last set on the window ({@link #updatePointerClip}), or {@code null} for none.
     */
    private @Nullable PointerClip pointerClip;

    private final @NotNull List<PlayingSound> triggerSounds;
    /**
     * The sound effect gain last seen by {@link #updateSound}; {@code -1} before the first frame.
     */
    private float appliedEffectsGain;

    final byte @NotNull [] keyStates;

    final @NotNull CameraTargetScroll centerViewScroll;

    /**
     * The textures of the drawn screen overlay and of the console dialog pictures ({@link #drawScreenOverlay}), and the palette of this frame.
     */
    private final @NotNull ScreenOverlayTexture screenOverlay;
    private final @NotNull ScreenOverlayTexture consoleSurfaces;
    private final int @NotNull [] screenOverlayCycled;
    /**
     * The painter of the plugins' draw lists ({@link #preparePluginPainter}), made with the interface renderer, and the minimap placement of
     * this frame.
     */
    private @Nullable PluginDrawPainter pluginPainter;
    private final float @NotNull [] minimapPlacement;

    private int seekSequenceIndex;

    private final @NotNull DoublePressDetector doubleClicks;
    private @Nullable WindowInput input;

    private final @NotNull DebugOverlay debugOverlay;

    final @NotNull ClientOptions options;

    /**
     * The out-of-game menus, shown while no session runs ({@code org.fogofwarlabs.sc4j.scr.client.glue}).
     */
    private final @NotNull GlueState glueState;

    private final int @NotNull [] framebufferWidthResult;
    private final int @NotNull [] framebufferHeightResult;
    final int @NotNull [] windowWidthResult;
    final int @NotNull [] windowHeightResult;

    /**
     * The running game accelerator handler.
     */
    final @NotNull GameAcceleratorHandler acceleratorHandler;

    /**
     * The game state the multiplayer game menu pages read ({@link GameMenu#refresh}).
     */
    private final GameMenu.@NotNull Context menuContext;

    /**
     * The message history, replaced for every running game.
     */
    private @NotNull ChatHistory chatHistory;

    /**
     * The window-activation master fade.
     */
    private final @NotNull MasterFade masterFade;
    /**
     * The configured pointer speed while the window is active.
     */
    private final @NotNull PointerSpeed pointerSpeed;

    /**
     * The latency staged by the options popup and sent when it closes; {@code -1} for none.
     */
    private int pendingLatency;

    private @NotNull ApmAlert apmAlert;
    private int apmAlertFrame;

    /**
     * The StarCraft Tips page's state of the running game ({@code tipnum}, {@code tip}), or {@code null} before it opens.
     */
    private @Nullable GameTips gameTips;
    /**
     * The Keystroke Help page's list while the page is open, else {@code null}.
     */
    private @Nullable ListBox keystrokeHelpList;

    /**
     * The game speed option last applied to a running single-player game ({@link #applyGameSpeedOption()}).
     */
    private int appliedGameSpeedOption;

    private @Nullable Path pendingReplay;

    int framebufferWidth;
    int framebufferHeight;
    int viewWidth;
    int viewHeight;
    /**
     * The window's interface surface: the view size in framebuffer pixels at the display scale of the window's monitor.
     */
    private @NotNull UiSurface viewSurface;

    /**
     * The world pass of a running session: the SC:R world layers over the scene backend of the selected skin, then the map placements of a
     * preview.
     */
    private final @NotNull ScrWorldPass worldPass;
    private final @NotNull PlacementPreviewLayer placementLayer;
    final @NotNull ReplayPlayback playback;
    /**
     * The draw throttle of a live game with a short override frame ({@link ClientSession#isDrawThrottled()}): passes between two drawn frames run
     * only input and simulation, and the frame timing row of a drawn frame covers the passes before it ({@link #presentSkipped}). The user's frame
     * rate limit lengthens its draw interval when the limit's period is longer.
     */
    private final @NotNull PresentThrottle presentThrottle;
    private boolean presentSkipped;

    private final @NotNull WorldSkin worldSkin;

    int consolePlayer;
    boolean consoleReplayControls;

    private final boolean pointerFixed;

    private long windowModeTime;

    private final @NotNull ConsoleCommandRegistry commands;

    /**
     * Creates a {@link Main} instance.
     *
     * @param options   the configured launch and live client settings
     * @param resources the mod's resource provider, or {@code null} to open one from the game directory
     */
    Main(@NotNull ClientOptions options, @Nullable ScrResourceProvider resources) {
        this(options, resources, null, UNKNOWN_BUILD);
    }

    /**
     * Creates a {@link Main} instance with the run's Beacon.Link and build identity.
     *
     * @param options    the configured launch and live client settings
     * @param resources  the mod's resource provider, or {@code null} to open one from the game directory
     * @param beaconLink the run's Beacon.Link, or {@code null} if the run offers none
     * @param build      the run's build identity, which the desync dumps of network games name
     */
    Main(@NotNull ClientOptions options, @Nullable ScrResourceProvider resources, @Nullable BeaconLink beaconLink, @NotNull String build) {
        this.clientHost = null;

        this.hostWindow = null;
        this.clipboard  = new WindowClipboard(() -> this.hostWindow);

        this.uploadWindow = null;

        this.renderer = null;

        this.renderedFrames    = 0;
        this.previousFrameTime = 0;

        this.statusMessage  = null;
        this.statusDeadline = 0;

        this.debugSections = null;

        this.session = null;

        this.glueRequest = null;

        this.sessionGlueRequest   = null;
        this.sessionClassicLimits = false;

        this.errorMessage = null;

        this.assetWarning         = null;
        this.assetWarningTime     = 0;
        this.reportedAssetWarning = null;

        this.pointerHorizontal = 0;
        this.pointerVertical   = 0;
        this.pointerKnown      = false;

        this.playing = false;

        this.replayWriter       = new LiveReplayWriter(options);
        this.simulationWatchdog = new SimulationWatchdog();

        this.gameMenu = null;

        this.networkWaitDialog = null;

        this.endPhase       = 0;
        this.outcomeVictory = false;
        this.outcomeBox     = null;
        this.outcomeDone    = false;

        this.pendingCommandFrame = 0;

        this.pendingShot = null;

        this.inputScript      = null;
        this.inputScriptFrame = 0;

        this.pendingLoadingShot = null;

        this.mouseButtonsWord = 0;
        this.suppliedMouseX   = 0;
        this.suppliedMouseY   = 0;

        this.chatModeByte = 0;
        this.chatWasOpen  = false;

        this.viewJumps = null;

        this.gammaRamp = null;

        this.blankCursorSet = false;

        this.cursorSurface = null;

        this.musicDucked = false;

        this.liveStopMessage = null;

        this.sessionEndsWithNotice = false;

        this.unitSpeech = null;

        this.unitSpeechOutput = null;

        this.alertCooldownEnd = 0;

        this.apmTracker = null;

        this.liveApmTracker = null;
        this.liveApmSession = null;

        this.apmSession = null;

        this.automaticPlaybackStarted = false;

        this.seekSequenceEnded = false;

        this.resources     = resources;
        this.beaconLink    = new BeaconLinkAccess(beaconLink);
        this.build         = build;
        this.clientContext = null;

        this.consoleView = null;

        this.consoleModel = null;

        this.worldInterface = null;

        this.systemCursorHidden = false;

        this.soundTable = null;

        this.interfaceTicksDue = 0;

        this.sdInterfaceFramebuffer = 0;
        this.sdInterfaceWidth       = 0;
        this.sdInterfaceHeight      = 0;
        this.sdInterfaceDrawn       = false;

        this.debugView = false;

        this.debugVisionPlayer = 0;

        this.frameTiming = FramePhaseUtils.open(options.getInstallLayout());

        this.loadingProgress = -1;
        this.loading         = new LoadingService();

        this.debugPanel = new DebugPanel();

        this.statusOverlay = new StatusOverlay();

        this.overlayComposed = false;
        this.frameComposed   = false;

        this.gameClientState = new GameClientState(this);

        this.pendingLiveCommand = -1;

        this.developerTexts = new DeveloperTextLayer();

        this.transmissionTimer = new TransmissionPortraitTimer();

        this.gameViewport = new GameViewport();

        this.fullscreenMode = GraphicsSettings.WINDOWED_FULLSCREEN;
        this.appliedGamma   = GraphicsSettings.DEFAULT_GAMMA;

        ScrollStepTable scrollSteps = new ScrScrollStepTable(this::readLocalInterface);

        this.scrollRate   = new SteppedScrollRate(scrollSteps);
        this.cameraScroll = new CameraScroll(SCROLL_EDGE_WIDTH, this.scrollRate);

        this.pointerMode = PointerMode.NORMAL;
        this.pointerClip = null;

        this.triggerSounds      = new ArrayList<>();
        this.appliedEffectsGain = -1;

        this.keyStates = new byte[LocalKeyStates.SIZE];

        this.centerViewScroll = new CameraTargetScroll(CENTER_VIEW_ALIGNMENT, CENTER_VIEW_SPEED, scrollSteps);

        this.screenOverlay       = new ScreenOverlayTexture(PaletteAnimation.COLOR_COUNT);
        this.consoleSurfaces     = new ScreenOverlayTexture(PaletteAnimation.COLOR_COUNT);
        this.screenOverlayCycled = new int[PaletteAnimation.COLOR_COUNT];
        this.pluginPainter       = null;
        this.minimapPlacement    = new float[ConsoleView.MINIMAP_PLACEMENT_VALUES];

        this.seekSequenceIndex = -1;

        this.doubleClicks = new DoublePressDetector();
        this.input        = null;

        this.debugOverlay = new DebugOverlay();

        this.options = options;

        this.glueState = new GlueState(options, new GlueState.GraphicsSelection() {
            /**
             * @return the selected HD quality, or {@code null} for the classic graphics
             */
            @Override
            public HdRenderAssets.@Nullable Quality getQuality() {
                return Main.this.worldSkin.getQuality();
            }

            /**
             * Selects the graphics at once: the image set of an HD quality loads once and stays with the client.
             *
             * @param quality the HD quality, or {@code null} for the classic graphics
             */
            @Override
            public void select(HdRenderAssets.@Nullable Quality quality) {
                Main.this.selectGraphicsQuality(quality);
            }
        });

        this.framebufferWidthResult  = new int[1];
        this.framebufferHeightResult = new int[1];
        this.windowWidthResult       = new int[1];
        this.windowHeightResult      = new int[1];

        this.glueState.setOptionAvailability(this::isOptionAvailable);
        // The Network tab's latency radio: the running game's latency (a staged choice shows at once), sent at the close.
        this.glueState.setLatencyAccess(this::getShownLatency, latency -> this.pendingLatency = latency);

        this.acceleratorHandler = new GameAcceleratorHandler(this);

        this.menuContext = this.createMenuContext();

        this.chatHistory = new ChatHistory();

        this.masterFade   = new MasterFade();
        this.pointerSpeed = new PointerSpeed(SystemPointerSpeed.getDefault());

        this.pendingLatency = NO_LATENCY;

        this.apmAlert      = new ApmAlert();
        this.apmAlertFrame = -1;

        // Without a stored Mouse Sensitivity the default follows the system pointer speed (not written to the file).
        if (this.options.getInterfaceSettings().getMouseSensitivity() == InterfaceSettings.SYSTEM_SENSITIVITY) {
            this.options.getInterfaceSettings().setMouseSensitivity(this.pointerSpeed.getDefaultSensitivity());
        }

        this.gameTips          = null;
        this.keystrokeHelpList = null;

        this.appliedGameSpeedOption = options.getGameSpeed();

        this.pendingReplay = options.getMelee() != null ? options.getMelee().getMap() : options.getReplayPath();

        this.framebufferWidth  = options.getWindowWidth();
        this.framebufferHeight = options.getWindowHeight();
        this.viewWidth         = options.getWindowWidth();
        this.viewHeight        = options.getWindowHeight();
        this.viewSurface       = UiSurface.of(options.getWindowWidth(), options.getWindowHeight(), DisplayScale.ONE);

        ScrWorldPass.Context worldContext = new ScrWorldPass.Context() {
            /**
             * @return the running game's view
             */
            @Override
            public @NotNull ScrGameView getView() {
                return Main.this.requireSession().getView();
            }

            /**
             * @return {@code true} if the game plays (its sprites are drawn), {@code false} for a map preview
             */
            @Override
            public boolean isPlaybackRequested() {
                return Main.this.requireSession().isPlaybackRequested();
            }

            /**
             * Prepares the read-only interface overlay the scene layer draws with the sprites (selection, placement, markers).
             *
             * @return the overlay, or {@code null} when the client has none
             */
            @Override
            public @Nullable WorldOverlayState prepareOverlay() {
                if (Main.this.isConsoleShown()) {
                    Main.this.ensureConsoleModel();
                }

                return Main.this.worldInterface;
            }

            /**
             * Reads the display colour of a player slot.
             *
             * @param owner the player slot
             * @return the colour index of the slot's player, else the slot itself
             */
            @Override
            public int getPlayerColor(int owner) {
                return Main.this.getPlayerColor(owner);
            }

            /**
             * Shows a rendering warning.
             *
             * @param warning the warning text
             */
            @Override
            public void setAssetWarning(@NotNull String warning) {
                Main.this.setAssetWarning(warning);
            }

            /**
             * @return the frame timing that marks each drawn layer, or {@code null} when timing is off
             */
            @Override
            public @Nullable FrameTiming getFrameTiming() {
                return Main.this.frameTiming;
            }
        };

        this.placementLayer = new PlacementPreviewLayer(() -> this.session, this.gameClientState.getInspection(), worldContext);
        this.worldPass      = new ScrWorldPass(worldContext, List.of(this.placementLayer));
        this.playback       = new ReplayPlayback(options.getSpeedIndex());

        this.presentThrottle = new PresentThrottle(GameSpeed.THROTTLED_DRAW_INTERVAL);
        this.presentSkipped  = false;

        this.placementLayer.setPass(this.worldPass);

        this.worldSkin = new WorldSkin(options.getSceneQuality());

        this.consolePlayer         = Math.max(0, options.getConsolePlayer());
        this.consoleReplayControls = options.getConsolePlayer() < 0 || options.isConsoleReplayControls();

        this.pointerFixed = !Float.isNaN(options.getPointerX());

        this.windowModeTime = NO_WINDOW_MODE_TIME;

        this.commands = this.createCommandRegistry();

        if (options.getDebugLayers() != null) {
            for (DebugOverlay.Layer layer : DebugOverlay.Layer.values()) {
                this.debugOverlay.setEnabled(layer, false);
            }

            this.debugView = true;

            for (String layer : options.getDebugLayers().split(",")) {
                String name = layer.trim().toLowerCase(Locale.ROOT);

                if (name.startsWith("lights")) {
                    LightingView view = LightingView.find(name.contains("=") ? name.substring(name.indexOf('=') + 1) : LightingView.LIGHTS.getName());

                    this.worldSkin.setLightingDebugView(view == null ? -1 : view.getRendererIndex());
                } else if (!name.isEmpty() && !name.equals("info")) {
                    this.debugOverlay.setEnabled(DebugOverlay.Layer.valueOf(name.toUpperCase(Locale.ROOT)), true);
                }
            }
        }

        if (this.pointerFixed) {
            this.pointerHorizontal = options.getPointerX();
            this.pointerVertical   = options.getPointerY();
        }
    }

    /**
     * @return {@code true} if the glue screens are open with no session running or loading, {@code false} otherwise
     */
    private boolean isGlueActive() {
        return this.glueState.isOpen() && this.session == null && this.pendingReplay == null;
    }

    /**
     * Returns the selected scene backend. Both backends receive the same extraction of the game state; the HD backend is created on first use for the
     * loaded map and falls back to the classic backend with a warning when its assets fail.
     *
     * @return the selected scene backend
     */
    private @NotNull SceneRenderer getSceneRenderer() {
        ClassicSceneRenderer classic = this.worldSkin.prepareClassicScene(this.requireRenderer(), this.requireSession().getStorage(),
                                                                          this.requireSession().getGameData().getAddedGraphics(),
                                                                          this.requireSession().getView().getMap().getTileset());

        if (this.worldSkin.getQuality() == null) {
            return classic;
        }

        if (this.worldSkin.getHdScene() == null) {
            try {
                this.openHdScene();
            } catch (IOException | RuntimeException exception) {
                this.setAssetWarning("HD renderer unavailable: " + exception.getMessage());
                this.worldSkin.setQuality(null);

                return classic;
            }
        }

        return this.worldSkin.prepareHdScene(this.options.getGraphicsSettings().isSmoothTurning(),
                                             this.options.getGraphicsSettings().isEnvironmentEffects());
    }

    /**
     * Returns the interface text locale: the session locale resolved once from {@code --locale} (validated against the installed locales, else the
     * installed {@code .build.info} text locale, else {@code enUS}; see {@link GameLocaleUtils}). It also drives the session's speech.
     *
     * @return the interface text locale
     */
    private @NotNull String getTextLocale() {
        if (this.session != null) {
            return this.requireSession().getLocale();
        }

        return this.options.getLocale() != null ? this.options.getLocale() : GameLocaleUtils.readInstalledLocale(this.options.getGameDirectory());
    }

    boolean isConsoleShown() {
        return this.consolePlayer >= 0 && this.consoleView != null && this.session != null && this.requireSession().isPlaybackRequested() &&
               this.requireSession().getView().getUnitSystem().getUnitPool() != null;
    }

    /**
     * @return {@code true} if the pointer is on the map view and not on the console, {@code false} otherwise
     */
    boolean isOnMapView() {
        if (!this.isConsoleShown() || this.pointerHorizontal >= this.viewWidth || this.pointerVertical >= this.viewHeight) {
            return false;
        }

        if (this.pointerHorizontal < this.gameViewport.getGameLeft() || this.pointerHorizontal >= this.gameViewport.getGameRight()) {
            return false;
        }

        ConsoleLayout layout = this.requireConsoleView().getLayout();

        return layout == null || !this.consoleView.isConsoleHit(this.pointerHorizontal, this.pointerVertical);
    }

    private boolean isLiveSession() {
        return this.session != null && this.requireSession().isLive();
    }

    /**
     * @return {@code true} if the game menu or outcome phase holds a game popup open, {@code false} otherwise
     */
    boolean isGamePopupOpen() {
        return this.gameMenu != null && this.gameMenu.isOpen() || this.endPhase != END_NONE;
    }

    /**
     * @return {@code true} if the menus' popup (the options popup of the game menu) is open over the game, {@code false} otherwise
     */
    boolean isGluePopupOverGame() {
        return this.glueState.isOverGame() && this.glueState.isPopupOpen();
    }

    /**
     * @return {@code true} if a live single-player game is held by a game popup or glue options popup, {@code false} otherwise
     */
    boolean isSinglePlayerPaused() {
        return this.session != null && this.requireSession().isLive() && !this.requireSession().isNetworkGame() &&
               (this.isGamePopupOpen() || this.isGluePopupOverGame());
    }

    /**
     * @return {@code true} if the network waiting dialog has an open page, {@code false} otherwise
     */
    boolean isNetworkWaitDialogOpen() {
        return this.networkWaitDialog != null && this.networkWaitDialog.getOpenDialog() != null;
    }

    /**
     * @return {@code true} if the running game has muted unit speech, {@code false} otherwise
     */
    private boolean isUnitSpeechMuted() {
        return this.requireSession().getPresentation().getInterfaceLink().getLocalInterface().getUnitSpeechMuted() != 0;
    }

    /**
     * Sets the camera to the requested map top-left position, subject to its bounds.
     *
     * @param x the map column of the new top-left corner
     * @param y the map row of the new top-left corner
     */
    private void setCameraTopLeft(int x, int y) {
        if (this.session == null) {
            return;
        }

        this.createPlayViewCamera(this.requireSession()).moveTo(x, y);
    }

    /**
     * Creates the game screen's camera of the edge and key scroll and the jumps: a session's camera, its range ending at the play view rows.
     *
     * @param session the session
     * @return the created {@link GameScreenCamera} instance
     */
    private @NotNull GameScreenCamera createPlayViewCamera(@NotNull ClientSession session) {
        GameMap map = session.getView().getMap();

        return GameScreenCamera.ofPlayView(session.getCamera(), this.gameViewport, map.getWidth() * TerrainTileset.TILE_SIZE,
                                           map.getHeight() * TerrainTileset.TILE_SIZE);
    }

    /**
     * Reads the local interface of the running game, whose scroll step table EUD writes may change while the game runs.
     *
     * @return the local interface
     */
    private @NotNull LocalInterfaceState readLocalInterface() {
        return this.requireSession().getPresentation().getInterfaceLink().getLocalInterface();
    }

    /**
     * @return {@code true} if the applied display mode is fullscreen, {@code false} otherwise
     */
    private boolean isFullScreen() {
        DisplayMode mode = this.hostWindow == null ? null : this.hostWindow.getDisplayMode();

        return mode != null && mode != DisplayMode.WINDOWED;
    }

    /**
     * Returns the staged or current network latency, or the no-latency sentinel outside a live network game.
     *
     * @return the shown latency, or {@link #NO_LATENCY} outside a live network game
     */
    private int getShownLatency() {
        ClientSession current = this.session;
        LiveGame live = current == null || !current.isLive() || !current.isNetworkGame() ? null : current.getLiveGame();

        if (live == null) {
            return NO_LATENCY;
        }

        return this.pendingLatency != NO_LATENCY ? this.pendingLatency : live.getLatency();
    }

    private boolean isOptionAvailable(@NotNull String key) {
        return switch (key) {
            case "game.gameSpeed" -> this.session == null || this.requireSession().isLive() && !this.requireSession().isNetworkGame();
            case "input.mouseConfine" -> !this.isFullScreen();
            case "audio.cinematicSubtitles", "audio.originalUnitVoices", "audio.originalCampaign" -> this.session == null;
            // Disable Hardware Cursor while Mouse Scaling is enabled.
            case "input.hardwareCursor" -> !this.options.getInterfaceSettings().isMouseScaling();
            default -> true;
        };
    }

    /**
     * @return the glue's open popups over the game, bottom first, as modal dialog layers (none without a popup)
     */
    @NotNull List<ScrDialogLayer> getGluePopupLayers() {
        return this.glueState.isOverGame() ? this.glueState.getPopupLayers() : List.of();
    }

    /**
     * Returns the open in-game popup of live play: the victory / defeat box, else the game menu page.
     *
     * @return the open in-game popup, or {@code null} when none is open
     */
    @Nullable ScrDialog getOpenGamePopup() {
        if (this.session == null || this.consoleView == null) {
            return null;
        }

        if (this.endPhase == END_BOX) {
            return this.outcomeBox == null ? null : this.outcomeBox.getDialog();
        }

        GameMenu menu = this.gameMenu;

        return menu == null ? null : menu.getDialog();
    }

    /**
     * Decodes the bounded next-scenario map string, or returns {@code null} when none is present.
     *
     * @return the next-scenario name, or {@code null} when none is present
     */
    private @Nullable String getNextScenario() {
        TriggerContext context = this.requireSession().getView().getTriggerContext();
        MapStringSection strings = this.requireSession().getPresentation().getInterfaceLink().getMessageLines().getMapStringSection();

        if (context == null || strings == null || context.getNextScenarioString() == 0) {
            return null;
        }

        byte[] name = strings.getStringBytes(context.getNextScenarioString());

        if (name == null) {
            return null;
        }

        return LegacyTextUtils.decode(name, 0, Math.min(name.length, NEXT_SCENARIO_LENGTH));
    }

    /**
     * Resolves the four score-row force names when the current settings and interface permit them.
     *
     * @return the four force names, or {@code null} when the settings or the interface do not permit them
     */
    private @NotNull String @Nullable [] getScoreForceNames() {
        ClientSession current = this.requireSession();
        GameSettings settings = current.getView().getGameSettings();
        boolean teamGame = settings instanceof ReplaySettings replay && replay.getTeamsEnabled() != 0;

        if (settings == null || this.consoleView == null ||
            !teamGame && (settings.getVictoryCondition() != 0 || settings.getCreateInitialUnits() != 0 || settings.getTournamentMode() != 0)) {
            return null;
        }

        String[] forces = new String[PlayerTable.FORCE_COUNT];

        for (int force = 0; force < forces.length; force++) {
            forces[force] = ForceNamesUtils.resolveForceName(this.consoleView::getNetworkText, teamGame, current.getForceName(force), force + 1);
        }

        return forces;
    }

    /**
     * @return {@code true} if the replay trigger-local player has the victory state, {@code false} otherwise
     */
    private boolean isReplayLocalPlayerVictorious() {
        int local = this.requireSession().getTriggerLocalPlayer();

        if (local < 0 || local >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return false;
        }

        Player record = this.requireSession().getView().getPlayerTable().getPlayer(local);

        return record != null && record.getVictoryState() == TriggerPlayerResults.VICTORY;
    }

    /**
     * Returns the live local player or the replay trigger-local player, using the viewer sentinel when absent.
     *
     * @return the score screen's local player, or {@link #REPLAY_VIEWER_SCORE_PLAYER} when absent
     */
    private int getScoreLocalPlayer() {
        if (this.requireSession().isLive()) {
            return this.requireSession().getLocalPlayer();
        }

        int local = this.requireSession().getTriggerLocalPlayer();

        return local >= 0 && local < PlayerTable.PLAYABLE_PLAYER_SLOTS ? local : REPLAY_VIEWER_SCORE_PLAYER;
    }

    /**
     * Returns the active placement or targeting prompt key, or {@code null} outside those modes.
     *
     * @return the prompt key, or {@code null} outside those modes
     */
    private @Nullable String getModePromptKey() {
        if (this.worldInterface != null && this.worldInterface.getPlacementType() >= 0) {
            return LOCATION_PROMPT_KEY;
        }

        if (this.consoleModel != null && this.consoleModel.getMode().isTargeting()) {
            return TARGET_PROMPT_KEY;
        }

        return null;
    }

    /**
     * @param key the key
     * @return {@code true} if the window or input script holds the key down, {@code false} otherwise
     */
    private boolean isKeyDown(@NotNull Key key) {
        if (this.isWindowKeyDown(key)) {
            return true;
        }

        // A hidden window has no key state of its own: an input script's held keys come from its key events.
        int virtualKey = VirtualKeys.of(key);

        return this.inputScript != null && virtualKey >= 0 && virtualKey < this.keyStates.length && this.keyStates[virtualKey] != 0;
    }

    private int getPlayerColor(int owner) {
        Player player = owner >= 0 && owner < PlayerTable.PLAYER_SLOTS ? this.requireSession().getView().getPlayerTable().getPlayer(owner) : null;

        return player != null ? player.getColor() : owner;
    }

    /**
     * @return the session status, or {@code Stopped at frame N.} when a running replay ends at the rendered-frame limit
     */
    private @NotNull String getExitStatus() {
        if (this.requireSession().isPlaybackRequested() && this.requireSession().canStep()) {
            return "Stopped at frame " + this.requireSession().getLastCompletedFrame() + ".";
        }

        return this.requireSession().getStatus();
    }

    /**
     * Sets a rendering warning in the footer; like chat, it ages out after seven seconds unless it is raised again.
     *
     * @param warning the warning text
     */
    void setAssetWarning(@NotNull String warning) {
        this.assetWarning     = warning;
        this.assetWarningTime = System.nanoTime();
    }

    private @NotNull String getPlaybackDetail() {
        if (this.requireSession().getFailureBoundary() == null) {
            return "";
        }

        return "Stopped in " + this.requireSession().getRuntimePhase() + " at state frame " + this.requireSession().getView().getCurrentFrame() + ".";
    }

    private @NotNull String getPlaybackLine() {
        long completed = this.requireSession().getLastCompletedFrame();
        long total = this.requireSession().getReplay().getHeader().getFrames();
        String boundary = this.requireSession().getFailureBoundary();

        if (boundary != null) {
            return "Failed after frame " + completed + " of " + total + "; boundary " + boundary;
        }

        if (this.requireSession().isFinished()) {
            return "Finished at frame " + completed + " of " + total + ".";
        }

        if (completed < 0) {
            return "Replay starting.";
        }

        return (this.playing ? "Playing" : "Paused") + " at frame " + completed + " of " + total + ", speed " + this.getReplaySpeedLabel() + ".";
    }

    /**
     * @return the replay's speed setting and factor as a label such as {@code Fastest x4}
     */
    private @NotNull String getReplaySpeedLabel() {
        GameSpeed timing = this.requireSession().getFrameTiming();

        return REPLAY_SPEED_NAMES[timing.getSpeedIndex()] + (timing.getFactor() > 1 ? " x" + timing.getFactor() : "");
    }

    /**
     * @return the platform pointer of the window, created on first use
     * @throws IllegalStateException if the window does not exist yet
     */
    private @NotNull CursorSurface getCursorSurface() {
        CursorSurface surface = this.cursorSurface;

        if (surface == null) {
            surface            = this.requireHostWindow().createCursorSurface();
            this.cursorSurface = surface;
        }

        return surface;
    }

    /**
     * @return the client state that owns the window's input: the menus while they are active, otherwise the game
     */
    private @NotNull InputListener getActiveState() {
        // Over a game the glue's popup (the options popup) is a modal dialog and takes every input while it is open.
        return this.isGlueActive() || this.isGluePopupOverGame() ? this.glueState : this.gameClientState;
    }

    /**
     * @return the glue asset set of the selected graphics
     */
    private @NotNull String getGlueQualityPrefix() {
        HdRenderAssets.Quality quality = this.worldSkin.getQuality();

        return quality == null ? GlueCanvas.SD_PREFIX : quality == HdRenderAssets.Quality.HD2 ? GlueCanvas.HD2_PREFIX : GlueCanvas.HD_PREFIX;
    }

    /**
     * @return what the client's loading loads for the loading panel: the HD quality being loaded, or {@code null}
     */
    private @Nullable String getLoadingCaption() {
        HdRenderAssets.Quality quality = this.worldSkin.getQuality();

        return quality == null ? null : quality.name();
    }

    /**
     * @return the client's interface renderer, or {@code null} before the client resources are opened
     */
    private @Nullable InterfaceRenderer getInterfaceRenderer() {
        return this.clientContext == null ? null : this.clientContext.getInterfaceRenderer();
    }

    private @NotNull String getPlayerName(int slot) {
        List<ReplayHeader.ReplayPlayer> players = this.requireSession().getReplay().getHeader().getPlayers();

        if (slot >= 0 && slot < players.size() && !players.get(slot).getName().isEmpty()) {
            return players.get(slot).getName();
        }

        return "Player " + (slot + 1);
    }


    /**
     * Creates the window and its OpenGL context on the engine host ({@link ClientHost}), runs the host's frame loop over {@link #runFrame} until
     * the window closes or the frame limit ends it, and releases the client resources in reverse order.
     *
     * @throws IOException           if a screenshot cannot be written, a bounded replay load fails or a smoke run cannot load unit images
     * @throws IllegalStateException if the windowing library cannot be initialized or cannot create the client window
     */
    private void run() throws IOException {
        // Set the archive locale before opening maps. The configured language takes effect on the next startup; languages other than enUS, deDE,
        // esES, frFR and itIT use enUS for map archives.
        MpqArchive.setLocale(MpqArchive.toStormLocale(this.getTextLocale()));

        this.simulationWatchdog.setStallListener(() -> this.replayWriter.saveStalled(this.session));

        ClientHost host = ClientHost.initialize();

        this.clientHost = host;

        try {
            // A visible window's size is in the units of its monitor's display scale. A hidden (screenshot) window has no frame, so that a
            // screen-sized client area is not cut by the frame, and is sized in pixels at its own display scale (--content-scale).
            boolean hidden = this.options.isHidden();
            float hiddenScale = hidden ? this.options.getContentScale() : 1;
            WindowSettings settings = WindowSettings.builder(WINDOW_TITLE,
                                                             Math.round(this.options.getWindowWidth() * hiddenScale),
                                                             Math.round(this.options.getWindowHeight() * hiddenScale))
                                                    .setMinimumSize(MINIMUM_WINDOW_WIDTH, MINIMUM_WINDOW_HEIGHT)
                                                    .setDecorated(!hidden)
                                                    .setScaledToMonitor(!hidden)
                                                    .build();
            HostWindow mainWindow = host.createWindow(settings);

            this.hostWindow = mainWindow;

            // A hidden window whose context shares the window's objects: the HD image set uploads its load there on a thread of its own, overlapping
            // the render thread's work (0 if it cannot be created: uploads stay here).
            HostWindow sharedWindow = host.createSharedContextWindow(mainWindow, UPLOAD_WINDOW_WIDTH, UPLOAD_WINDOW_HEIGHT);

            this.uploadWindow = sharedWindow;
            mainWindow.makeContextCurrent();
            this.applySwapInterval();
            host.createCapabilities();
            this.renderer = new WorldRenderer();
            this.updateDimensions();
            this.installCallbacks();

            if (this.options.getInputScript() != null) {
                this.inputScript = InputScript.load(this.options.getInputScript());
            }

            if (!this.options.isHidden()) {
                mainWindow.center();

                this.applyWindowMode();
                mainWindow.show();
            }

            LOGGER.debug("OpenGL: " + GL11C.glGetString(GL11C.GL_VERSION));

            this.renderedFrames    = 0;
            this.previousFrameTime = host.getTime();

            host.run(mainWindow, new HostApplication() {
                /**
                 * Called at the start of every frame, before the window events are polled.
                 */
                @Override
                public void beginFrame() {
                    if (Main.this.frameTiming != null && !Main.this.presentSkipped) {
                        Main.this.frameTiming.beginFrame();
                    }
                }

                /**
                 * Runs one frame: input, simulation, drawing and presenting.
                 *
                 * @return what the frame did
                 * @throws IOException if the frame cannot read or write a file it needs
                 */
                @Override
                public @NotNull FrameResult runFrame() throws IOException {
                    return Main.this.runFrame(host);
                }

                /**
                 * Returns the frame rate limit of the frame just run. It is read after {@link #runFrame} returned a presented frame.
                 *
                 * @return the frames per second the host paces to, or {@code 0} to present without waiting (for example while the
                 *         display's vertical sync paces the frames)
                 */
                @Override
                public int getFrameRateLimit() {
                    return Main.this.getFrameRateLimit();
                }
            });

            this.finishRun();
        } finally {
            this.closeRun(host);
        }
    }

    /**
     * Runs one frame of the client's frame loop ({@link ClientHost#run}): input, loading, simulation, drawing, screenshots and the present.
     *
     * @param host the host
     * @return {@link FrameResult#SKIPPED} for a frame that presented nothing (loading, a minimized window), {@link FrameResult#LAST} for the
     * frame that ends the run, {@link FrameResult#PRESENTED} otherwise
     * @throws IOException if a screenshot cannot be written or a bounded replay load fails
     */
    private @NotNull FrameResult runFrame(@NotNull ClientHost host) throws IOException {
        this.refreshPointer();
        this.advanceInputScript();

        this.interfaceTicksDue = this.session == null
                                 ? 0
                                 : this.requireSession().getPresentation().getInterfaceLink().getLocalInterface().advanceInterfaceTicks(
                (int) UiClockUtils.getHostTimeMilliseconds(),
                MAXIMUM_INTERFACE_TICKS
        );
        this.applyWindowMode();
        // A live game with a short override frame draws at the throttle's interval, never more often than the user's frame rate limit, and never
        // waits for the display or the host's pacing.
        this.presentThrottle.setFrameRateLimit(this.options.getGraphicsSettings().getEffectiveFrameRateLimit());
        this.presentThrottle.setActive(this.session != null && !this.isGlueActive() && this.requireSession().isDrawThrottled(),
                                       UiClockUtils.getHostTimeMilliseconds());
        this.presentSkipped = false;
        this.applySwapInterval();
        this.applyGameSpeedOption();
        this.updateDimensions();
        // The screen geometry of this pass, before input reads it (edge scroll, minimap, camera jumps).
        this.updateGameViewport();
        this.applyGamma();
        this.requireRenderer().setShadowStacking(this.options.getGraphicsSettings().isShadowStacking());
        this.renderer.setSdFilterMode(this.options.getGraphicsSettings().getSdFilterMode());

        if (this.pendingReplay != null) {
            Path replayPath = this.pendingReplay;

            this.pendingReplay = null;
            this.drawFrame("Loading " + replayPath.getFileName());
            this.presentFrame();
            // A game started before the image set of the selected graphics was loaded (--ums, --replay): its load is queued now and runs on the
            // loading screen after the map loads below, with the images the game data's mods add.
            this.ensureImageSet();
            this.loadReplay(replayPath);

            if (this.options.getSeekFrame() >= 0 && this.session != null && this.requireSession().isPlaybackRequested()) {
                this.playback.requestSeek(this.options.getSeekFrame(), this.requireSession().getLastCompletedFrame());
                this.seekSequenceIndex = 0;
            }

            if (this.options.isPlayRequested() && this.session != null && this.requireSession().canStep()) {
                this.playing                  = true;
                this.automaticPlaybackStarted = true;
            }

            this.previousFrameTime = host.getTime();
        }

        if (this.updateLoading()) {
            if (this.frameTiming != null) {
                this.frameTiming.endFrame(this.session == null ? -1 : this.requireSession().getLastCompletedFrame());
            }

            this.previousFrameTime = host.getTime();

            return FrameResult.SKIPPED;
        }

        double currentTime = host.getTime();
        long updateStart = System.nanoTime();

        this.debugPanel.recordFrame(updateStart);
        this.updateInput(Math.min(currentTime - this.previousFrameTime, MAXIMUM_INPUT_DELTA_SECONDS));
        this.updateSound();
        this.updatePointerSpeed();
        this.debugPanel.recordUpdate(System.nanoTime() - updateStart);

        if (this.frameTiming != null) {
            this.frameTiming.mark(FramePhaseUtils.INPUT_AND_SIMULATION);
        }

        this.previousFrameTime = currentTime;

        ClientSession pacedSession = this.session;

        if (pacedSession != null) {
            pacedSession.reportLivePacing();
        }

        if (this.framebufferWidth == 0 || this.framebufferHeight == 0) {
            host.waitEvents(ICONIFIED_WINDOW_WAIT_SECONDS);

            return FrameResult.SKIPPED;
        }

        if (this.loading.isLoading()) {
            // A graphics change during this frame's input (F5, the options, /graphics) started a load: the next iteration draws its first
            // loading frame instead of building the HD scene on a set still loading.
            return FrameResult.SKIPPED;
        }

        if (!this.presentThrottle.takePresentDue(UiClockUtils.getHostTimeMilliseconds())) {
            this.presentSkipped = true;

            return FrameResult.SKIPPED;
        }

        this.drawFrame(null);
        this.renderedFrames++;

        if (this.pendingShot != null) {
            this.captureScreenshot(this.pendingShot);
            this.pendingShot = null;
        }

        this.advanceSeekSequence();

        boolean lastFrame = this.options.getFrameLimit() > 0 && this.renderedFrames >= this.options.getFrameLimit() ||
                            this.automaticPlaybackStarted && !this.playing && this.playback.getSeekTarget() < 0 ||
                            this.seekSequenceEnded && !this.options.isPlayRequested();

        if (lastFrame && this.options.getScreenshotPath() != null) {
            this.captureScreenshot(this.resolveScreenshotPath(this.options.getScreenshotPath()));
        }

        this.presentFrame();

        ClientSession presentedSession = this.session;

        if (presentedSession != null) {
            presentedSession.recordPresentedFrame();
        }

        if (this.frameTiming != null) {
            this.frameTiming.mark(FramePhaseUtils.SWAP);
            this.frameTiming.endFrame(this.session == null ? -1 : this.requireSession().getLastCompletedFrame());
        }

        // The host paces after the timing record and before the next beginFrame, so the wait is in no measured phase.
        return lastFrame ? FrameResult.LAST : FrameResult.PRESENTED;
    }

    /**
     * Finishes a run whose frame loop ended normally: saves the live game's replay, reports the automatic playback's end and the smoke result, and
     * captures the final screenshot of a screenshot run.
     *
     * @throws IOException if a screenshot cannot be written or a smoke run could not load unit images
     */
    private void finishRun() throws IOException {
        this.replayWriter.saveAtEnd(this.session);

        ClientSession finished = this.session;

        if (this.automaticPlaybackStarted && finished != null) {
            if (finished.isFinished() || finished.getFailureBoundary() != null) {
                LOGGER.info("PLAYBACK END: completed frame " + finished.getLastCompletedFrame() + ", status " + finished.getStatus());
            } else {
                LOGGER.info("PLAYBACK STOPPED: Stopped at frame " + finished.getLastCompletedFrame() + " of " +
                            finished.getReplay().getHeader().getFrames() + " by the rendered-frame limit.");
            }

            LOGGER.debug("JAVA RNG DIAGNOSTIC: state " + Integer.toUnsignedString(finished.getView().getRandomState()) +
                         ", calls " + finished.getView().getRandomCallCount() +
                         "; original runtime state not collected.");
        }

        if (this.options.getFrameLimit() > 0) {
            if (!this.placementLayer.getUnavailableUnitTypes().isEmpty()) {
                throw Exceptions.newIOException("Smoke render could not load unit images for types {0}",
                                                String.valueOf(this.placementLayer.getUnavailableUnitTypes()));
            }

            LOGGER.info("SMOKE OK: " + this.renderedFrames + " frames rendered" +
                        (this.session == null ? ", no replay loaded" : ", " + this.getExitStatus()));
        } else if (this.options.getScreenshotPath() != null) {
            this.drawFrame(null);
            this.captureScreenshot(this.resolveScreenshotPath(this.options.getScreenshotPath()));
        }
    }

    /**
     * Ends a run, also after a failure: restores the pointer speed, saves the live game's replay and releases the client resources in reverse order,
     * the GL objects before the host's windows and the windowing library ({@link ClientHost#close}).
     *
     * @param host the host
     */
    private void closeRun(@NotNull ClientHost host) {
        try {
            this.pointerSpeed.restore();
        } catch (RuntimeException exception) {
            LOGGER.warning("POINTER SPEED NOT RESTORED: " + exception.getMessage());
        }

        // An exception out of the frame loop ends the running game too: write its replay before the session goes.
        try {
            this.replayWriter.saveAtEnd(this.session);
        } catch (RuntimeException exception) {
            LOGGER.warning("LIVE REPLAY SAVE FAILED: " + exception);
        }

        // A failing step is logged and the later steps still run: the GL objects go before the windows and the host.
        IgnoreException.execute(
                () -> closeResource(this.frameTiming),
                this.glueState::close,
                this.loading::cancel,
                this::closeImageSet,
                this.worldSkin::closeClassicScene,
                this::closeConsole,
                () -> closeResource(this.cursorSurface),
                () -> closeResource(this.clientContext),
                () -> closeResource(this.renderer),
                () -> closeResource(this.session),
                host::close
        );
    }

    /**
     * Reports each completed command-line seek, captures a screenshot when the screenshot path contains {@code %d}, and requests the next target,
     * which may lie before the current frame.
     *
     * @throws IOException if a screenshot cannot be written
     */
    private void advanceSeekSequence() throws IOException {
        if (this.seekSequenceIndex < 0 || this.session == null || this.playback.getSeekTarget() >= 0) {
            return;
        }

        long[] targets = this.options.getSeekFrames();
        long completed = this.requireSession().getLastCompletedFrame();

        LOGGER.info("SEEK DONE: target " + targets[this.seekSequenceIndex] + ", completed frame " + completed +
                    ", RNG state " + Integer.toUnsignedString(this.requireSession().getView().getRandomState()) +
                    ", calls " + this.requireSession().getView().getRandomCallCount() + ", status " + this.requireSession().getStatus());

        for (String command : this.options.getCommands()) {
            LOGGER.info("COMMAND: " + command + " -> " + this.runPromptCommand(command));
        }

        Path screenshot = this.options.getScreenshotPath();

        if (screenshot != null && screenshot.getFileName().toString().contains("%d")) {
            this.captureScreenshot(this.resolveScreenshotPath(screenshot));
        }

        this.seekSequenceIndex++;

        if (this.seekSequenceIndex < targets.length) {
            this.playback.requestSeek(targets[this.seekSequenceIndex], completed);
        } else {
            this.seekSequenceIndex = -1;
            this.seekSequenceEnded = true;
        }
    }

    /**
     * @param path the command-line screenshot path, whose file name may contain {@code %d}
     * @return the path with {@code %d} replaced by the last completed replay frame
     */
    private @NotNull Path resolveScreenshotPath(@NotNull Path path) {
        String name = path.getFileName().toString();

        if (!name.contains("%d")) {
            return path;
        }

        long frame = this.session == null ? -1 : this.requireSession().getLastCompletedFrame();

        return path.resolveSibling(name.replace("%d", Long.toString(frame)));
    }

    private void installCallbacks() {
        WindowInput windowInput = this.requireHostWindow().createInput(this::dispatchInput, this.doubleClicks, UiClockUtils::getHostTimeMilliseconds);

        windowInput.setSurfaceSize(this.windowWidthResult[0], this.windowHeightResult[0], this.framebufferWidth, this.framebufferHeight);
        this.input = windowInput;

        InputMethod.install(this.requireHostWindow().getTextInputMethod(), new InputMethod.Sink() {
            /**
             * Sends a keyboard event.
             *
             * @param event the key press or release
             */
            @Override
            public void onKeyEvent(@NotNull InputEvent event) {
                Main.this.dispatchKey(event);
            }

            /**
             * Sends a typed code point.
             *
             * @param codePoint the typed code point
             */
            @Override
            public void onCharacterTyped(int codePoint) {
                Main.this.getActiveState().onInput(InputEvent.createCharacterTyped(codePoint));
            }
        });
        this.requireHostWindow().setFileDropListener(files -> this.pendingReplay = files.getFirst());
    }

    /**
     * Routes one input event of the window: keys through {@link #dispatchKey}, pointer movements (unless the pointer is fixed) through
     * {@link #dispatchPointer}, buttons through {@link #dispatchButton}, typed characters and the wheel to the active client state.
     *
     * @param event the event
     */
    private void dispatchInput(@NotNull InputEvent event) {
        switch (event.getType()) {
            case KEY_PRESSED, KEY_REPEATED, KEY_RELEASED -> this.dispatchKey(event);
            case POINTER_MOVED -> {
                if (!this.pointerFixed) {
                    this.dispatchPointer(event.getX(), event.getY());
                }
            }
            case BUTTON_PRESSED, BUTTON_RELEASED -> this.dispatchButton(event);
            case CHARACTER_TYPED, WHEEL_SCROLLED -> this.getActiveState().onInput(event);
        }
    }

    /**
     * Routes a window or scripted key through global display actions and the active client state.
     *
     * @param event the key event
     */
    private void dispatchKey(@NotNull InputEvent event) {
        Key key = event.getKey();
        InputEvent.Type type = event.getType();
        int modifiers = event.getModifiers();
        int held = modifiers & (KeyModifier.SHIFT.getMask() | KeyModifier.CONTROL.getMask() | KeyModifier.ALT.getMask());

        if (type == InputEvent.Type.KEY_PRESSED && (key == Key.ENTER || key == Key.KEYPAD_ENTER) && held == KeyModifier.ALT.getMask()) {
            this.toggleWindowMode();

            return;
        }

        if (this.isGlueActive() && type == InputEvent.Type.KEY_PRESSED && key == Key.F5 && held == 0) {
            this.cycleSceneQuality();

            return;
        }

        // The status toggles work on every screen, ahead of the screen's own keys: in a session with the game's active accelerator table (the popup
        // table has no Ctrl+Alt+T), else with the play table.
        GameAccelerators.Mode mode = this.session == null ? GameAccelerators.Mode.PLAY : this.acceleratorHandler.getMode();
        int toggle = type == InputEvent.Type.KEY_RELEASED
                     ? GameAccelerators.NONE
                     : StatusOverlay.findToggle(mode, key, modifiers, type == InputEvent.Type.KEY_REPEATED);

        if (toggle == GameAccelerators.SHOW_FPS) {
            this.toggleShowFrameRate();

            return;
        }

        if (toggle == GameAccelerators.SHOW_TURN_RATE) {
            this.toggleShowTurnRate();

            return;
        }

        // The menus' popup over a game is a game popup: the popup table's accelerators run before it sees the key (its edit operations go to the
        // popup's focused edit).
        if (this.isGluePopupOverGame() && type != InputEvent.Type.KEY_RELEASED &&
            this.acceleratorHandler.handle(key, modifiers, type == InputEvent.Type.KEY_REPEATED)) {
            return;
        }

        this.getActiveState().onInput(event);
    }

    /**
     * Refreshes the window pointer each frame unless a fixed pointer or input script owns its position.
     */
    private void refreshPointer() {
        WindowInput windowInput = this.input;

        if (this.pointerFixed || this.inputScript != null || this.options.isHidden() || windowInput == null) {
            return;
        }

        HostWindow window = this.requireHostWindow();
        float x = windowInput.toFramebufferX(window.getPointerX());
        float y = windowInput.toFramebufferY(window.getPointerY());

        if (!this.pointerKnown || x != this.pointerHorizontal || y != this.pointerVertical) {
            this.dispatchPointer(x, y);
        }
    }

    /**
     * Routes a pointer move of the window or of the input script, in framebuffer pixels.
     *
     * @param x the framebuffer column
     * @param y the framebuffer row
     */
    private void dispatchPointer(float x, float y) {
        this.pointerKnown      = true;
        this.pointerHorizontal = x;
        this.pointerVertical   = y;
        this.getActiveState().onInput(InputEvent.createPointerMoved(x, y));
    }

    /**
     * Updates the held-button bits and routes a window or scripted button, whose press went through the double-press detector, to the active
     * client state at the routed pointer position.
     *
     * @param event the button event
     */
    private void dispatchButton(@NotNull InputEvent event) {
        PointerButton button = event.getButton();

        if (button == null) {
            return;
        }

        int mask = switch (button) {
            case LEFT -> LEFT_MOUSE_BUTTON_MASK;
            case RIGHT -> RIGHT_MOUSE_BUTTON_MASK;
            case MIDDLE -> MIDDLE_MOUSE_BUTTON_MASK;
            default -> 0;
        };
        boolean pressed = event.getType() == InputEvent.Type.BUTTON_PRESSED;

        if (pressed) {
            this.mouseButtonsWord |= mask;
        } else {
            this.mouseButtonsWord &= ~mask;
        }

        this.getActiveState().onInput(pressed
                                      ? InputEvent.createButtonPressed(button, event.getModifiers(), this.pointerHorizontal, this.pointerVertical,
                                                                       event.isDoublePress())
                                      : InputEvent.createButtonReleased(button, event.getModifiers(), this.pointerHorizontal, this.pointerVertical));
    }

    /**
     * Feeds the test-only input script's events of this client frame through the window's input paths.
     */
    private void advanceInputScript() {
        InputScript script = this.inputScript;

        if (script == null || script.isFinished()) {
            return;
        }

        script.advance(this.inputScriptFrame++, new InputScript.Sink() {
            /**
             * @return the framebuffer width
             */
            @Override
            public int getFramebufferWidth() {
                return Main.this.framebufferWidth;
            }

            /**
             * @return the framebuffer height
             */
            @Override
            public int getFramebufferHeight() {
                return Main.this.framebufferHeight;
            }

            /**
             * Handles a scripted key event.
             *
             * @param event the key press, repeat or release
             */
            @Override
            public void onKeyEvent(@NotNull InputEvent event) {
                Main.this.dispatchKey(event);
            }

            /**
             * Handles a scripted character input.
             *
             * @param codePoint the typed code point
             */
            @Override
            public void onCharacterTyped(int codePoint) {
                Main.this.getActiveState().onInput(InputEvent.createCharacterTyped(codePoint));
            }

            /**
             * Moves the pointer to the scripted framebuffer position.
             *
             * @param horizontal the framebuffer column
             * @param vertical   the framebuffer row
             */
            @Override
            public void onPointerMoved(float horizontal, float vertical) {
                Main.this.dispatchPointer(horizontal, vertical);
            }

            /**
             * Handles a scripted pointer button event.
             *
             * @param button    the button
             * @param pressed   whether the button goes down, else up
             * @param modifiers the modifier mask ({@link KeyModifier#getMask()} bits)
             */
            @Override
            public void onPointerButton(@NotNull PointerButton button, boolean pressed, int modifiers) {
                float x = Main.this.pointerHorizontal;
                float y = Main.this.pointerVertical;

                if (pressed) {
                    long time = UiClockUtils.getHostTimeMilliseconds();
                    boolean doublePress = Main.this.doubleClicks.press(button, time, Math.round(x), Math.round(y));

                    Main.this.dispatchButton(InputEvent.createButtonPressed(button, modifiers, x, y, doublePress));
                } else {
                    Main.this.dispatchButton(InputEvent.createButtonReleased(button, modifiers, x, y));
                }
            }

            /**
             * Handles a scripted vertical wheel movement.
             *
             * @param offset the vertical wheel offset
             */
            @Override
            public void onWheelScrolled(double offset) {
                Main.this.getActiveState().onInput(InputEvent.createWheelScrolled(0, offset, Main.this.pointerHorizontal, Main.this.pointerVertical));
            }

            /**
             * Saves the next drawn frame to the requested PNG file.
             *
             * @param file the PNG the next drawn frame is saved to
             */
            @Override
            public void captureNext(@NotNull Path file) {
                Main.this.pendingShot = Main.this.options.getInstallLayout().resolveUserPath(file);
            }

            /**
             * Closes the client as the window's close button does.
             */
            @Override
            public void exit() {
                Main.this.requestWindowClose();
            }
        });
    }

    private void updateDimensions() {
        HostWindow window = this.requireHostWindow();

        this.framebufferWidthResult[0]  = window.getFramebufferWidth();
        this.framebufferHeightResult[0] = window.getFramebufferHeight();
        this.windowWidthResult[0]       = window.getWidth();
        this.windowHeightResult[0]      = window.getHeight();
        this.framebufferWidth           = this.framebufferWidthResult[0];
        this.framebufferHeight          = this.framebufferHeightResult[0];

        WindowInput windowInput = this.input;

        if (windowInput != null) {
            windowInput.setSurfaceSize(this.windowWidthResult[0], this.windowHeightResult[0], this.framebufferWidth, this.framebufferHeight);
        }

        this.updateViewSize();
    }

    /**
     * Draws the glue screens while no session runs, opening them on first use; once a session exists the one glue stays open with its empty screen
     * over the game ({@link GlueState#enterGame}), and when the game has ended it shows its menus again ({@link GlueState#leaveGame}).
     *
     * @return {@code true} if the glue drew the frame, {@code false} otherwise
     */
    private boolean drawGlue() {
        if (this.session != null) {
            this.glueState.enterGame();

            return false;
        }

        // While a game loads the glue stays open, so that a failed start returns to it.
        if (this.pendingReplay != null) {
            return false;
        }

        this.openGlueOnFirstUse();

        if (!this.glueState.isOpen()) {
            return false;
        }

        this.glueState.leaveGame();

        boolean inside = this.showGlueCursor();
        boolean sdFrame = this.getGlueQualityPrefix().equals(GlueCanvas.SD_PREFIX);
        int target = 0;

        if (sdFrame) {
            target = this.requireRenderer().beginSdFrame(this.framebufferWidth, this.framebufferHeight);
            this.glueState.setTargetFramebuffer(target, this.renderer.getSdInterfaceWidth(), this.renderer.getSdInterfaceHeight());
        }

        GlueGameRequest request = this.glueState.draw(this.getGlueQualityPrefix(),
                                                      this.viewSurface,
                                                      UiClockUtils.getHostTimeMilliseconds(),
                                                      this.pointerHorizontal, this.pointerVertical, inside,
                                                      this.loading.isLoading(), this.getLoadingCaption(), this.loading.getProgress());

        if (sdFrame) {
            this.glueState.setTargetFramebuffer(0, 0, 0);
            this.composeOverlay(target, this.renderer.getSdInterfaceWidth(), this.renderer.getSdInterfaceHeight());
            this.renderer.presentSd();
        }

        if (this.glueState.isExitRequested()) {
            this.requestWindowClose();
        }

        if (request != null) {
            // A replay loads as any replay; for the other games loadReplay starts the request, and the pending path only names the session (the map,
            // or the mission index).
            Path path = request.getPath();

            this.glueRequest        = request.getKind() == GlueGameRequest.Kind.REPLAY ? null : request;
            this.pendingReplay      = path != null ? path : Path.of("campaign-" + request.getCampaignMission());
            this.sessionGlueRequest = request;
        }

        return true;
    }

    /**
     * Opens the one glue over a running game when it is not open yet (a game of the launch options starts without the menus), with its empty screen
     * over the game.
     *
     * @return {@code true} if the glue is open, {@code false} otherwise
     */
    private boolean openGlueOverGame() {
        this.openGlueOnFirstUse();

        if (!this.glueState.isOpen()) {
            return false;
        }

        this.glueState.enterGame();

        return true;
    }

    /**
     * Opens the one glue when it is neither open nor unavailable; a failure leaves it closed and shows a warning.
     */
    private void openGlueOnFirstUse() {
        if (!this.glueState.isOpen() && !this.glueState.isUnavailable()) {
            try {
                this.glueState.open(this.requireClientContext(), this.getTextLocale());
            } catch (IOException | RuntimeException exception) {
                this.setAssetWarning("Menus unavailable: " + exception.getMessage());
            }
        }
    }

    /**
     * Draws the glue over the game frame while it shows something there: its popup (the options popup of the game menu, a modal dialog) or
     * the loading panel.
     *
     * @param loadingShown whether the client's loading runs (the loading panel shows)
     */
    private void drawGlueOverGame(boolean loadingShown) {
        if (this.session == null || !this.glueState.isOverGame() || !loadingShown && !this.glueState.isPopupOpen()) {
            return;
        }

        boolean inside = this.pointerHorizontal >= 0 && this.pointerVertical >= 0 && this.pointerHorizontal < this.framebufferWidth &&
                         this.pointerVertical < this.framebufferHeight;
        boolean sdTarget = this.sdInterfaceFramebuffer != 0;

        if (sdTarget) {
            // The SD target: the glue keeps window coordinates, mapped onto the target.
            this.glueState.setTargetFramebuffer(this.sdInterfaceFramebuffer, this.sdInterfaceWidth, this.sdInterfaceHeight);
        }

        this.glueState.draw(this.getGlueQualityPrefix(),
                            this.viewSurface,
                            UiClockUtils.getHostTimeMilliseconds(),
                            this.pointerHorizontal, this.pointerVertical, inside,
                            loadingShown, this.getLoadingCaption(), this.loading.getProgress());

        if (sdTarget) {
            this.glueState.setTargetFramebuffer(0, 0, 0);
        }
    }

    /**
     * Draws the interface of an SD frame into the renderer's SD interface layer ({@link WorldRenderer#beginSdInterface()}): the console with its
     * popups and the cursor placement, then the glue over the game, then the status overlay.
     */
    private void drawSdInterface() {
        this.sdInterfaceFramebuffer = this.requireRenderer().beginSdInterface();
        this.sdInterfaceWidth       = this.renderer.getSdInterfaceWidth();
        this.sdInterfaceHeight      = this.renderer.getSdInterfaceHeight();

        try {
            this.drawConsole();
            this.drawGlueOverGame(false);
            this.composeOverlay(this.sdInterfaceFramebuffer, this.sdInterfaceWidth, this.sdInterfaceHeight);
        } finally {
            this.sdInterfaceFramebuffer = 0;
            this.sdInterfaceDrawn       = true;
        }
    }

    /**
     * @return the client resources, opened on first use
     * @throws IOException if the data directory holds no data pack, or the storage, the interface renderer or the fonts cannot be loaded
     */
    private @NotNull ClientContext requireClientContext() throws IOException {
        if (this.clientContext == null) {
            ScrResourceProvider provider = this.resources != null
                                           ? this.resources
                                           : ScrResourceProvider.open(this.options.requireGameDirectory(), ScrIscriptImporter::readLibrary);

            this.clientContext = ClientContext.open(this.options.getInstallLayout(), provider,
                                                    this.getTextLocale(), ClientAudio.open(this.options.getSoundSettings()),
                                                    this.beaconLink,
                                                    ReleaseStatus.checkRunningVersion());
            this.clientContext.setWindow(this.hostWindow);
            // The localized text tables are client data, loaded once at the start (and again for another locale).
            this.loading.enqueue(new TextTablesLoadTask(this.clientContext, this.getTextLocale()));
            this.loading.enqueue(new ClientDataLoadTask(this.clientContext));
        }

        return this.clientContext;
    }

    /**
     * Shows the game arrow (the shared {@link GameCursor}) of the menus over the window and hides the system cursor there; a capture or blank cursor
     * left by a game is released first, so the pointer shows after every return to the menus.
     *
     * @return {@code true} if the pointer is over the window, {@code false} otherwise
     */
    private boolean showGlueCursor() {
        boolean inside = (this.pointerFixed || this.inputScript != null || this.requireHostWindow().isPointerInside()) &&
                         this.pointerHorizontal >= 0 && this.pointerVertical >= 0 && this.pointerHorizontal < this.framebufferWidth &&
                         this.pointerVertical < this.framebufferHeight;
        boolean hardware = this.clientContext != null && this.updateCursorSurface(this.clientContext.getCursor());
        PointerMode mode = inside && !this.options.isHidden() && !hardware ? PointerMode.HIDDEN : PointerMode.NORMAL;

        if (mode != this.pointerMode) {
            this.requireHostWindow().setPointerMode(mode);
            this.pointerMode = mode;
        }

        if (this.blankCursorSet) {
            this.getCursorSurface().showDefault();
            this.blankCursorSet = false;
        }

        this.systemCursorHidden = mode == PointerMode.HIDDEN;

        return inside;
    }

    /**
     * Gives the game cursor the platform pointer ({@link HostWindow#createCursorSurface}) when the cursor is not drawn into the frame: hidden
     * runs, screenshots and scripted or fixed pointers keep the drawn cursor, as does a platform that refuses custom cursors. The pointer's pictures
     * are sized for the window: the interface's {@code 480} rows fill its height in SD and HD alike.
     *
     * @param cursor the shared game cursor
     * @return {@code true} if the platform pointer shows the cursor, {@code false} otherwise
     */
    private boolean updateCursorSurface(@NotNull GameCursor cursor) {
        InterfaceSettings interfaceSettings = this.options.getInterfaceSettings();
        boolean drawn = this.options.isHidden() || this.inputScript != null || this.pointerFixed || this.options.getScreenshotPath() != null ||
                        !interfaceSettings.isHardwareCursor() || interfaceSettings.isMouseScaling();

        boolean hardware = !drawn && this.getCursorSurface().isAvailable();

        cursor.setSurface(hardware ? this.cursorSurface : null);
        // The cursor is drawn at the 640-wide interface's scale (GameViewport.placeInterface).
        cursor.setSurfaceScale(GameViewport.placeInterface(InterfaceTransform.ORIGINAL_WIDTH, this.viewSurface).getScale());

        return hardware;
    }

    /**
     * Updates the view size and the window's interface surface from the framebuffer and the display scale: the scale of the window's monitor,
     * or {@code --content-scale} for a hidden window.
     */
    private void updateViewSize() {
        int width = Math.max(1, this.framebufferWidth);
        int height = Math.max(1, this.framebufferHeight);
        float contentScale = this.options.isHidden() ? this.options.getContentScale() : this.requireHostWindow().getContentScale();
        DisplayScale scale = DisplayScale.of(contentScale, 1);

        if (width == this.viewWidth && height == this.viewHeight && scale.equals(this.viewSurface.getScale())) {
            return;
        }

        this.viewWidth   = width;
        this.viewHeight  = height;
        this.viewSurface = UiSurface.of(width, height, scale);

        if (this.session != null) {
            ClientCamera camera = this.requireSession().getCamera();
            boolean nativeZoom = Math.abs(camera.getZoom() - camera.getNativeZoom()) < ZOOM_TOLERANCE;

            this.updateGameViewport();

            if (nativeZoom) {
                camera.setNativeZoom();
            }
        }
    }

    private void updateInput(double elapsedSeconds) {
        if (this.session == null) {
            return;
        }

        // The client's animated zoom (wheel zoom, /zoom) advances on real time before the scroll reads the camera.
        this.requireSession().getCamera().advanceZoom(elapsedSeconds);

        for (int tick = 0; tick < this.interfaceTicksDue; tick++) {
            this.tickCameraScroll();
        }

        this.advancePlayback(elapsedSeconds);
        this.showPauseNotices();
    }

    private void advancePlayback(double elapsedSeconds) {
        if (this.sessionEndsWithNotice && !this.glueState.isPopupOpen()) {
            // The fatal notice of a stopped network game or of an EUD failure closed: the session ends.
            this.sessionEndsWithNotice = false;
            this.returnToGlue(GlueResume.createAfterFatalNotice(this.sessionGlueRequest));

            return;
        }

        TriggerContext triggers = this.requireSession().getView().getTriggerContext();

        if (this.requireSession().getEnd() == GameEnd.EUD_FAILURE && !this.sessionEndsWithNotice && triggers != null) {
            // A step that did not present its trigger events (a seek, a single step) ended the game: its notice shows now.
            this.showEudFailureNotice(false, ~triggers.getEudFailureAddress());
        }

        if (this.requireSession().getEnd() == GameEnd.RESTART) {
            // A Restart Game command ended the replay: its scenario reload from the replay file fails, so the main menu shows the
            // scenario load error.
            this.returnToGlue(GlueResume.createAfterFatalNotice(this.sessionGlueRequest));
            this.glueState.showReplayLoadError();

            return;
        }

        if (this.pendingLiveCommand >= 0 && this.consoleView != null &&
            this.consoleView.getLayout() != null && this.requireSession().getLastCompletedFrame() > LAUNCH_COMMAND_READY_FRAME) {
            // One launch command per client frame, so a command sees the interface refreshed after the previous one (for example a hotkey after
            // a selection). A launch-only "/wait N" holds the remaining commands for N game frames (a selection reaches the command card when
            // its command executes).
            List<String> launchCommands = this.options.getCommands();
            long frame = this.requireSession().getLastCompletedFrame();

            if (frame >= this.pendingCommandFrame) {
                if (this.pendingLiveCommand < launchCommands.size()) {
                    String command = launchCommands.get(this.pendingLiveCommand++);

                    long waitFrames = ClientOptions.getLaunchWaitFrames(command);

                    if (waitFrames >= 0) {
                        this.pendingCommandFrame = frame + waitFrames;
                    } else {
                        LOGGER.info("COMMAND: " + command + " -> " + this.runPromptCommand(command));
                    }
                } else {
                    this.pendingLiveCommand = -1;
                }
            }
        }

        LocalKeyStates keys = this.requireSession().getPresentation().getInterfaceLink().getLocalKeyStates();

        keys.setStates(this.keyStates.clone());

        for (int virtualKey = VirtualKeys.DIGIT_0; virtualKey <= LAST_ASYNC_VIRTUAL_KEY; virtualKey++) {
            keys.setAsyncPressed(virtualKey,
                                 virtualKey <= LAST_DIGIT_VIRTUAL_KEY && this.isKeyDown(Key.ofDigit(virtualKey - VirtualKeys.DIGIT_0)));
        }

        this.pollHotkeyPress();

        if (this.unitSpeech != null) {
            this.unitSpeech.tick(UiClockUtils.getHostTimeMilliseconds(), this.requireSession().getPresentation().getLocalInterfaceEffects());
        }

        if (this.transmissionTimer.expire((int) UiClockUtils.getHostTimeMilliseconds())) {
            this.requireSession().completeTransmission();
        }

        int[] cycledPalette = this.requireSession().updateColorCycle(this.options.getInterfaceSettings().isColorCycling(),
                                                                     this.requireHostWindow().isMinimized());

        if (cycledPalette != null && this.renderer != null) {
            this.renderer.setPaletteColors(cycledPalette);
        }

        for (int tick = 0; tick < this.interfaceTicksDue && this.centerViewScroll.isActive() && !this.isSinglePlayerPaused(); tick++) {
            this.tickCenterView();
        }

        long seekTarget = this.playback.getSeekTarget();

        if (seekTarget >= 0) {
            if (this.playback.takeSeekRestart()) {
                // The restarted replay (end code 6) starts unpaused, with the loaded palette.
                if (this.renderer != null) {
                    this.renderer.setPaletteGrayscale(false);
                }

                try {
                    this.restartAtStartScreen();
                } catch (IOException | RuntimeException exception) {
                    this.errorMessage = "Seek restart failed: " + exception.getMessage();
                    this.playback.finishSeek();

                    return;
                }
            }

            long deadline = System.nanoTime() + SEEK_BUDGET_NANOSECONDS;

            while (this.requireSession().getLastCompletedFrame() < seekTarget && System.nanoTime() < deadline) {
                if (!this.requireSession().stepFrame(false)) {
                    break;
                }
            }

            if (this.requireSession().getLastCompletedFrame() >= seekTarget || !this.requireSession().canStep()) {
                this.playback.finishSeek();
            }

            return;
        }

        if (!this.requireSession().isLive()) {
            this.requireSession().setReplayPaused(!this.playing);
        }

        boolean replayPopupOpen = !this.requireSession().isLive() && (this.isGamePopupOpen() || this.isGluePopupOverGame());

        if (!this.playing && !this.requireSession().isLive() && !replayPopupOpen) {
            this.runPausedReplayLoops(elapsedSeconds);

            return;
        }

        if (!this.playing || this.isSinglePlayerPaused() || replayPopupOpen) {
            this.playback.resetClock();

            return;
        }

        this.receiveChat();

        boolean livePaced = this.requireSession().isLive() && !this.playback.isUnlimited();
        int dueFrames = livePaced
                        ? this.playback.takeLiveDueFrames(elapsedSeconds, this.requireSession().getFrameDuration(), this.requireSession().getLiveLagLimit())
                        : this.playback.takeDueFrames(elapsedSeconds, this.requireSession().getFrameDuration());
        boolean audible = !this.playback.isUnlimited();

        if (this.requireSession().isLive() && this.requireSession().getLastCompletedFrame() < FAST_FORWARD_FRAME) {
            // Test harness (-Dsc4j.fastForwardTo): a live game runs unpaced and silent up to that frame.
            dueFrames = Integer.MAX_VALUE;
            audible   = false;
            livePaced = false;
        }

        long deadline = System.nanoTime() + PLAYBACK_BUDGET_NANOSECONDS;

        for (int frame = 0; frame < dueFrames; frame++) {
            // A network game goes on under the victory or defeat box: the turns keep flowing until the player leaves (the session's close sends
            // the Storm leave).
            if (this.requireSession().isLive() && this.requireSession().getLiveOutcome() != 0 && !this.requireSession().isNetworkGame()) {
                this.playing = false;

                return;
            }

            this.supplyLocalView();

            boolean stepped = this.requireSession().stepFrame(audible);

            if (stepped) {
                this.notifyFramePlayed();
            }

            this.applyLocalViewWrite();
            // Trigger presentation (text, transmissions, pings, WAV) follows the trigger local player in replays too.
            this.drainTriggerEvents();
            this.drainMinimapPings();

            this.drainCommandErrors();

            if (this.requireSession().isLive()) {
                this.drainAttackAlerts();
                this.drainNotifications();
                this.updateOutcome();

                if (this.updateSavedGameCommands()) {
                    return;
                }
            }

            if (!stepped && this.requireSession().isWaitingForTurns()) {
                // A network game waits for a player's turn: no frame ran; retry on the next update. A paced live game keeps the time of this
                // frame and of the later due ones, so it catches up once the turn arrived.
                if (livePaced) {
                    this.playback.refundLiveFrames(dueFrames - frame);
                } else {
                    this.playback.resetClock();
                }

                return;
            }

            if (!stepped) {
                this.playing = false;
                this.playback.resetClock();

                if (!this.requireSession().isLive() && this.requireSession().isFinished()) {
                    this.openReplayFinishedBox();
                }

                if (this.requireSession().isLive() && this.requireSession().getLiveOutcome() == 0 && !this.requireSession().isEnded()) {
                    // The simulation stopped (for example an unimplemented trigger); show why instead of a silent freeze.
                    String boundary = this.requireSession().getFailureBoundary();

                    this.liveStopMessage = "Game stopped: " + (boundary != null ? boundary : this.requireSession().getStatus());
                    LOGGER.info("LIVE STOPPED: " + this.liveStopMessage);
                    // The game ends here: its replay holds every command executed up to the stop, as at a normal end.
                    this.replayWriter.saveAtEnd(this.session);

                    if (this.requireSession().isNetworkGame()) {
                        this.requireSession().leaveNetwork();
                        this.sessionEndsWithNotice = true;

                        if (this.clientContext != null && this.openGlueOverGame()) {
                            this.glueState.showFatal(this.liveStopMessage, null, this.clientContext.getGameFonts());
                        }
                    }
                }

                return;
            }

            // A recorded Pause command paused the replay's playback (ReplayCommandExecutor.PlaybackControl).
            if (!this.playing) {
                this.playback.resetClock();

                return;
            }

            if (System.nanoTime() >= deadline) {
                if (livePaced) {
                    this.playback.refundLiveFrames(dueFrames - frame - 1);
                }

                return;
            }
        }
    }

    /**
     * Runs the game loops of a paused replay that are due at the playback speed, without frames ({@link ClientSession#stepPausedReplay()}): their
     * turn steps run the viewer's controls, which may continue the playback. Unpaced playback runs one loop per update.
     *
     * @param elapsedSeconds the host time since the previous update
     */
    private void runPausedReplayLoops(double elapsedSeconds) {
        if (!this.requireSession().hasFrameTiming()) {
            this.playback.resetClock();

            return;
        }

        int dueLoops = this.playback.isUnlimited() ? 1 : this.playback.takeDueFrames(elapsedSeconds, this.requireSession().getFrameDuration());
        long deadline = System.nanoTime() + PLAYBACK_BUDGET_NANOSECONDS;

        for (int loop = 0; loop < dueLoops && !this.playing && System.nanoTime() < deadline; loop++) {
            if (!this.requireSession().stepPausedReplay()) {
                return;
            }
        }
    }

    /**
     * Opens the victory box of a finished replay ({@link OutcomeBox#openReplayFinished}) once: its Exit Replay ends the replay with code {@code 7}
     * and the score screen follows, as Exit Replay of the game menu does.
     */
    private void openReplayFinishedBox() {
        if (this.endPhase != END_NONE || this.outcomeDone || this.consoleView == null || this.consoleView.getGluText() == null) {
            return;
        }

        GluText glue = this.consoleView.getGluText();

        try {
            this.outcomeBox = OutcomeBox.openReplayFinished(this.requireSession().getStorage(), glue::get);
        } catch (IOException exception) {
            this.setAssetWarning("Outcome box: " + exception.getMessage());

            return;
        }

        this.outcomeVictory = true;
        this.endPhase       = END_BOX;
        this.stopTriggerSounds();

        if (this.gameMenu != null) {
            this.gameMenu.close();
        }
    }

    /**
     * Loads a replay, a map of the launch options or the game the glue asked for into a new session and renderer, which replace the running ones. A
     * failed load closes what it created and shows the load error; it fails only a run with a frame limit.
     *
     * @param replayPath the replay or map to load, or the path that names the glue's game
     * @throws IOException if the load fails in a run with a frame limit
     */
    private void loadReplay(@NotNull Path replayPath) throws IOException {
        ClientSession loadedSession = null;
        WorldRenderer loadedRenderer = null;

        this.playing      = false;
        this.errorMessage = null;
        this.assetWarning = null;
        this.placementLayer.clearUnavailableUnitTypes();

        GlueGameRequest request = this.glueRequest;

        this.glueRequest = null;

        try {
            ClientOptions.Melee melee = this.options.getMelee();

            if (request != null) {
                loadedSession = this.startGlueGame(request);
            } else if (melee != null && melee.getCampaign() != null && replayPath.equals(melee.getMap())) {
                loadedSession = ClientSession.startCampaign(this.options.requireGameDirectory(),
                                                            this.requireClientContext().getStorage(), this.requireClientContext().getGameData(),
                                                            melee.getCampaign(), melee.getName(), melee.getSpeed(),
                                                            this.options.getLocale());
            } else if (melee != null && replayPath.equals(melee.getMap())) {
                MeleeGameSetup setup = melee.isUsingMapSettings()
                                       ? MeleeGameSetup.loadUseMapSettings(melee.getMap(),
                                                                           melee.getName(), melee.getRace(), melee.getSlot(),
                                                                           melee.getSpeed(),
                                                                           this.options.isMapCompatibility())
                                       : MeleeGameSetup.load(melee.getMap(),
                                                             melee.getName(), melee.getRace(),
                                                             melee.getComputers(),
                                                             melee.getSlot(),
                                                             melee.getSpeed(),
                                                             this.options.isMapCompatibility());

                loadedSession = ClientSession.startMelee(this.options.requireGameDirectory(),
                                                         this.requireClientContext().getStorage(), this.requireClientContext().getGameData(),
                                                         melee.getMap(), setup,
                                                         this.options.getLocale());
            } else {
                loadedSession = ClientSession.load(this.options.requireGameDirectory(),
                                                   this.requireClientContext().getStorage(), this.requireClientContext().getGameData(),
                                                   replayPath,
                                                   this.options.getLocale());
            }

            loadedSession.setAudioSet(this.options.getAudioSet());
            loadedSession.setOriginalUnitVoices(this.options.getSoundSettings().isOriginalUnitVoices());

            if (loadedSession.isLive() && loadedSession.getSaveDirectory() == null) {
                String player = request != null ? request.getPlayerName() : melee != null ? melee.getName() : "";

                if (!player.isEmpty()) {
                    ClientPlayer clientPlayer = this.requireClientContext().getPlayer();

                    loadedSession.setSaveDirectory(clientPlayer.getSaveDirectory(this.options.getInstallLayout().getSaveDirectory()));
                }
            }

            this.gameViewport.setSurface(this.viewSurface);
            loadedSession.getCamera().setViewport(this.gameViewport);

            // A replay starts on the start screen of the native scale's screen size.
            float startZoom = loadedSession.getCamera().getNativeZoom();

            loadedSession.setScreenSize(this.gameViewport.getViewTileWidth(startZoom), this.gameViewport.getViewTileHeight(startZoom));
            loadedSession.setInteractiveReplay(!this.options.isHidden());
            loadedSession.setReplaySpeedSetting(this.options.getGameSpeed());

            if (!this.options.isInspectMap()) {
                // The game writes its message lines from the first frame: the client's text tables (reloaded when the session locale differs) and the
                // display layout are installed before the start.
                ClientContext client = this.requireClientContext();

                loadedSession.setTextTables(client.getTextTables(loadedSession.getLocale()));
                loadedSession.setMusicRotation(client.getMusicRotation());
                loadedSession.setAudio(client.getAudio());
                loadedSession.setMessageLayout(MessageAreaUtils.createTextLayout(client.getGameFonts().getFont(InterfaceFonts.NORMAL),
                                                                                 () -> ConsoleLayout.INTERFACE_WIDTH - MESSAGE_TEXT_WIDTH_RESERVE));
                loadedSession.startReplay();

                if (loadedSession.isSavedGame()) {
                    this.showSavedScreen(loadedSession);
                }
            }

            for (int previewStep = 0; previewStep < this.options.getPreviewSteps(); previewStep++) {
                if (!loadedSession.stepFrame()) {
                    break;
                }
            }

            if (this.options.getPreviewSteps() > 0) {
                LOGGER.debug("REPLAY STATE: completed frame " + loadedSession.getLastCompletedFrame() +
                             ", state frame " + loadedSession.getView().getCurrentFrame() +
                             ", turn marker count " + loadedSession.getView().getCommandDispatchMarkerCount() +
                             ", status " + loadedSession.getStatus());
                LOGGER.debug("JAVA RNG DIAGNOSTIC: state " + Integer.toUnsignedString(loadedSession.getView().getRandomState()) +
                             ", enabled " + loadedSession.getView().getRandomEnabled() +
                             ", calls " + loadedSession.getView().getRandomCallCount() +
                             "; original runtime state not collected.");
            }

            if (this.options.getFocusUnit() >= loadedSession.getPlacements().size()) {
                throw Exceptions.newIOException("Focus unit index {0} exceeds the loaded unit count {1}",
                                                this.options.getFocusUnit(), loadedSession.getPlacements().size());
            }

            GameMap map = loadedSession.getView().getMap();

            ScrAddedGraphics addedGraphics = loadedSession.getGameData().getAddedGraphics();

            loadedRenderer = new WorldRenderer();
            loadedRenderer.loadMap(ScrResourceProvider.borrow(loadedSession.getStorage(), addedGraphics), map.getTileset(), map.getWidth(),
                                   map.getHeight(), map.copyTileIdentifiersForRendering());

            int loadedFrameCount = 0;

            this.gameViewport.setSurface(this.viewSurface);
            loadedSession.getCamera().setViewport(this.gameViewport);

            if (this.options.getFocusUnit() >= 0) {
                ChkUnit focusUnit = loadedSession.getPlacements().get(this.options.getFocusUnit());
                ClientCamera camera = loadedSession.getCamera();

                camera.zoomAt(1 / camera.getZoom(), this.viewWidth / CENTER_FLOAT_DIVISOR, this.viewHeight / CENTER_FLOAT_DIVISOR);
                camera.centerOn(focusUnit.getX(), focusUnit.getY());

                ChkUnit inspectedUnit = loadedSession.selectPlacementForInspection(this.options.getFocusUnit());

                loadedFrameCount = loadedRenderer.getUnitFrameCount(inspectedUnit.getUnitId());

                if (this.options.getUnitFrame() >= loadedFrameCount) {
                    throw Exceptions.newIOException("GRP frame {0} exceeds the selected unit frame count {1}",
                                                    this.options.getUnitFrame(), loadedFrameCount);
                }

                LOGGER.debug("FOCUS UNIT: index " + this.options.getFocusUnit() + ", type " + inspectedUnit.getUnitId() +
                             ", owner " + inspectedUnit.getOwner() + ", position " + inspectedUnit.getX() + "," + inspectedUnit.getY() +
                             ", manual GRP frame " + this.options.getUnitFrame() + "/" + loadedFrameCount +
                             ", mirrored " + this.options.isMirrorUnit());
            }

            ClientSession previousSession = this.session;
            WorldRenderer previousRenderer = this.renderer;

            if (previousSession != null) {
                // A previous live game that ended without writing its replay writes it now, before its session closes.
                this.replayWriter.saveAtEnd(this.session);

                this.stopTriggerSounds();
            }

            // The loaded session and renderer are installed before the previous ones close, so a failed close never leaves a closed one in use.
            this.session  = loadedSession;
            this.renderer = loadedRenderer;
            this.replayWriter.startSession();
            loadedSession.setSimulationWatchdog(this.simulationWatchdog);

            // The HD image set stays resident (ClientContext); only the scene of the previous map closes. A failing close is logged and the later
            // ones still run, so the installed session is always set up below.
            IgnoreException.execute(
                    this.worldSkin::closeHdScene,
                    this.worldSkin::closeClassicScene,
                    () -> closeResource(previousRenderer),
                    () -> {
                        if (previousSession != null) {
                            this.closeFinishedSession(previousSession);
                        }
                    }
            );

            this.startGameMusic(loadedSession);
            this.resetGameInterfaceState();

            if (loadedSession.isLive()) {
                // Live play: the console shows the local player's view and sends commands; the game runs at once.
                this.consolePlayer         = loadedSession.getLocalPlayer();
                this.consoleReplayControls = false;
                this.playing               = true;
            } else {
                this.consolePlayer         = Math.max(0, this.options.getConsolePlayer());
                this.consoleReplayControls = this.options.getConsolePlayer() < 0 || this.options.isConsoleReplayControls();
            }

            this.openConsole(loadedSession, map.getTileset());

            UnitInspection inspection = this.gameClientState.getInspection();

            inspection.load(this.options.getUnitFrame(), loadedFrameCount, this.options.isMirrorUnit(), this.options.getImageAnimation());

            ImageScriptPreview preview = this.options.getImageAnimation() >= 0 && inspection.startPreview(this.session)
                                         ? inspection.getPreview()
                                         : null;

            if (preview != null) {
                this.playing = false;

                for (int invocationIndex = 0; invocationIndex < this.options.getImageSteps() &&
                                              preview.getStopReason() == null && !preview.isEnded(); invocationIndex++) {
                    preview.step();
                }

                LOGGER.debug("IMAGE PREVIEW: script " + preview.getScriptIdentifier() +
                             ", animation " + preview.getImage().getAnimationType() +
                             ", frame " + preview.getImage().getDrawnFrameIndex() +
                             ", wait " + preview.getImage().getWait() +
                             ", images " + preview.getSprite().getImages().size() +
                             ", calls " + preview.getInvocationCount() +
                             ", status " + (preview.getStopReason() != null ? preview.getStopReason() : preview.isEnded() ? "ended" : "ready") +
                             ", world frame " + this.requireSession().getView().getCurrentFrame());
            }

            if (this.options.getFocusUnit() < 0) {
                this.fitMap();
            }

            if (this.requireSession().isLive()) {
                this.centerOnStartLocation();
                this.pendingLiveCommand = 0;
            }

            ClientCamera loadedCamera = this.requireSession().getCamera();

            if (!Float.isNaN(this.options.getZoom())) {
                loadedCamera.zoomAt(this.options.getZoom() / loadedCamera.getZoom(),
                                    this.viewWidth / CENTER_FLOAT_DIVISOR, this.viewHeight / CENTER_FLOAT_DIVISOR);
            } else if (!this.requireSession().isLive() && this.options.getFocusUnit() < 0) {
                loadedCamera.setNativeZoom();

                int[] start = this.requireSession().placeStartScreen(this.gameViewport.getViewTileWidth(loadedCamera.getZoom()),
                                                                     this.gameViewport.getViewTileHeight(loadedCamera.getZoom()));

                if (start != null) {
                    this.setCameraTopLeft(start[START_SCREEN_X_INDEX], start[START_SCREEN_Y_INDEX]);
                }
            }

            if (!Float.isNaN(this.options.getCameraX())) {
                loadedCamera.centerOn(this.options.getCameraX(), this.options.getCameraY());
            }

            LOGGER.info((this.requireSession().isLive() ? "MAP LOADED: " : "REPLAY LOADED: ") + replayPath + " | " + map.getWidth() + "x" +
                        map.getHeight() + " tiles | " + this.requireSession().getPlacements().size() + " map placements");
            LOGGER.debug("INITIALIZATION INPUTS: " + this.requireSession().getInitializationRequests().size() +
                         " THG2/UNIT records; " + this.requireSession().getStatus());
        } catch (IOException | RuntimeException exception) {
            if (loadedRenderer != null && loadedRenderer != this.renderer) {
                loadedRenderer.close();
            }

            if (loadedSession != null && loadedSession != this.session) {
                loadedSession.close();
            }

            this.errorMessage = "Load failed: " + exception.getMessage();
            LOGGER.error(this.errorMessage);

            this.glueState.showReplayLoadError();

            if (this.options.getFrameLimit() > 0) {
                throw Exceptions.newIOException(exception, this.errorMessage);
            }
        }
    }

    /**
     * Starts a campaign mission or custom game the glue asked for.
     *
     * @param request the game the glue asked for
     * @return the started session
     * @throws IOException if the request has no map or file, the data directory holds no data pack, the client resources cannot be opened or the game
     *                     cannot be loaded
     */
    private @NotNull ClientSession startGlueGame(@NotNull GlueGameRequest request) throws IOException {
        Path map = request.getPath();

        this.sessionClassicLimits = false;

        switch (request.getKind()) {
            case MELEE, USE_MAP_SETTINGS -> {
                if (map == null) {
                    throw Exceptions.newIOException("The custom game has no map.");
                }

                String race = getRaceName(request.getRace());
                StringBuilder computers = new StringBuilder();

                for (int computer : request.getComputerRaces()) {
                    computers.append(computers.isEmpty() ? "" : ",").append(getRaceName(computer));
                }

                MeleeGameSetup setup = request.getKind() == GlueGameRequest.Kind.USE_MAP_SETTINGS
                                       ? MeleeGameSetup.loadUseMapSettings(map,
                                                                           request.getPlayerName(), race, 0,
                                                                           this.options.getGameSpeed(),
                                                                           this.options.isMapCompatibility())
                                       : MeleeGameSetup.load(map,
                                                             request.getPlayerName(), race,
                                                             computers.toString(),
                                                             0,
                                                             this.options.getGameSpeed(),
                                                             this.options.isMapCompatibility());

                GameTemplate template = request.getTemplate();

                if (request.getKind() == GlueGameRequest.Kind.MELEE && template != null) {
                    setup.setTemplate(template);
                }

                setup.setClassicLimits(!this.options.isExtendedUnitLimits());
                this.sessionClassicLimits = setup.isClassicLimits();

                return ClientSession.startMelee(this.options.requireGameDirectory(),
                                                this.requireClientContext().getStorage(), this.requireClientContext().getGameData(),
                                                map, setup,
                                                this.options.getLocale());
            }
            case NETWORK -> {
                Path gameDirectory = this.options.requireGameDirectory();
                ClientContext context = this.requireClientContext();

                return startNetworkGame(context, request, gameDirectory, this.options.getLocale(), this.build);
            }
            case SAVED_GAME -> {
                if (map == null) {
                    throw Exceptions.newIOException("The saved game has no file.");
                }

                return ClientSession.startSavedGame(this.options.requireGameDirectory(),
                                                    this.requireClientContext().getStorage(), this.requireClientContext().getGameData(),
                                                    map,
                                                    this.options.getLocale());
            }
            default -> {
                return ClientSession.startCampaign(this.options.requireGameDirectory(),
                                                   this.requireClientContext().getStorage(), this.requireClientContext().getGameData(),
                                                   Integer.toString(request.getCampaignMission()),
                                                   request.getPlayerName(),
                                                   this.options.getGameSpeed(),
                                                   this.options.getLocale());
            }
        }
    }

    /**
     * Opens the file dialog that chooses the next replay ({@code /replay open}).
     *
     * @return {@code true} if the dialog was shown, {@code false} otherwise
     */
    boolean chooseReplay() {
        if (this.options.isHidden()) {
            return false;
        }

        FileDialog dialog = new FileDialog((Frame) null, "Open StarCraft replay", FileDialog.LOAD);

        try {
            dialog.setFile("*.rep");

            Path replays = this.options.getInstallLayout().getReplayDirectory();

            if (Files.isDirectory(replays)) {
                dialog.setDirectory(replays.toString());
            }

            dialog.setVisible(true);

            if (dialog.getFile() != null) {
                this.pendingReplay = Path.of(dialog.getDirectory(), dialog.getFile());
            }
        } finally {
            dialog.dispose();
        }

        return true;
    }

    /**
     * Restarts the session from its first frame, paused ({@code /replay restart}).
     *
     * @return {@code true} if the session restarted, {@code false} otherwise
     */
    boolean resetSession() {
        this.playing = false;

        if (this.session == null) {
            return false;
        }

        try {
            this.restartAtStartScreen();
            this.gameClientState.getInspection().clear();
            this.errorMessage = null;

            return true;
        } catch (IOException | RuntimeException exception) {
            this.errorMessage = "Reset failed: " + exception.getMessage();

            return false;
        }
    }

    /**
     * Restarts the session ({@link ClientSession#reset}) with the displayed game screen size ({@link ClientSession#setScreenSize}): a restarted
     * replay starts on the start screen of that size, which the camera then shows at its current scale.
     *
     * @throws IOException if the embedded map cannot be loaded
     */
    private void restartAtStartScreen() throws IOException {
        ClientCamera camera = this.requireSession().getCamera();
        int screenWidth = this.gameViewport.getViewTileWidth(camera.getZoom());
        int screenHeight = this.gameViewport.getViewTileHeight(camera.getZoom());

        this.requireSession().setScreenSize(screenWidth, screenHeight);
        this.requireSession().reset();

        if (!this.requireSession().isPlaybackRequested()) {
            return;
        }

        int[] start = this.requireSession().placeStartScreen(screenWidth, screenHeight);

        if (start != null) {
            this.setCameraTopLeft(start[START_SCREEN_X_INDEX], start[START_SCREEN_Y_INDEX]);
        }
    }

    /**
     * Starts the live camera at the native scale on the game start's screen of the local player
     * ({@link ClientSession#placeStartScreen}), which keeps the whole-tile snap.
     */
    private void centerOnStartLocation() {
        ClientCamera camera = this.requireSession().getCamera();

        camera.setNativeZoom();

        int[] start = this.requireSession().placeStartScreen(this.gameViewport.getViewTileWidth(camera.getZoom()),
                                                             this.gameViewport.getViewTileHeight(camera.getZoom()));

        if (start == null) {
            return;
        }

        this.setCameraTopLeft(start[START_SCREEN_X_INDEX], start[START_SCREEN_Y_INDEX]);
        LOGGER.info("LIVE START: player " + this.requireSession().getLocalPlayer() + ", start screen " + start[START_SCREEN_X_INDEX] + "," +
                    start[START_SCREEN_Y_INDEX] + ", camera " + Math.round(camera.screenToWorldX(this.gameViewport.getGameLeft())) + "," +
                    Math.round(camera.getY()) + " viewport " + camera.getViewportWidth() + "x" + camera.getViewportHeight());
    }

    void fitMap() {
        if (this.session != null) {
            ClientCamera camera = this.requireSession().getCamera();
            GameMap map = this.requireSession().getView().getMap();
            float scale = Math.min(this.viewWidth / (map.getWidth() * (float) TerrainTileset.TILE_SIZE),
                                   this.viewHeight / (map.getHeight() * (float) TerrainTileset.TILE_SIZE));

            camera.zoomAt(scale / camera.getZoom(), this.viewWidth / CENTER_FLOAT_DIVISOR, this.viewHeight / CENTER_FLOAT_DIVISOR);
            camera.centerOn(map.getWidth() * HALF_TILE_SIZE, map.getHeight() * HALF_TILE_SIZE);
        }
    }

    private void drawFrame(@Nullable String loadingMessage) {
        if (this.framebufferWidth <= 0 || this.framebufferHeight <= 0) {
            return;
        }

        if (loadingMessage == null && this.drawGlue()) {
            return;
        }

        this.updateViewSize();

        ClientCamera camera = this.session == null ? null : this.requireSession().getCamera();
        HdRenderAssets.Quality quality = this.worldSkin.getQuality();

        this.requireRenderer().setRenderScaleLimit(quality == null ? 1 : quality.getScale());
        // SD graphics draw the interface into their target before the present (drawSdInterface).
        this.renderer.setSdInterfaceDeferred(true);
        this.updateGameViewport();
        this.renderer.beginFrame(this.framebufferWidth, this.framebufferHeight,
                                 this.gameViewport.getWindowWidth(), this.gameViewport.getWindowHeight(),
                                 camera == null ? 0 : camera.getX(), camera == null ? 0 : camera.getY(), camera == null ? 1 : camera.getZoom());

        if (camera != null && loadingMessage == null) {
            ChkUnit selectedUnit = this.requireSession().getSelectedPlacement();
            SceneRenderer scene = this.getSceneRenderer();

            try {
                this.worldPass.draw(scene, this.renderer,
                                    Math.max(1, this.gameViewport.getWindowWidth()), Math.max(1, this.gameViewport.getWindowHeight()),
                                    camera.getX(), camera.getY(), camera.getZoom());
            } catch (IOException exception) {
                this.setAssetWarning("World: " + exception.getMessage());
            }

            this.renderer.finishWorld();

            if (this.renderer.isSdPresentPending()) {
                this.drawSdInterface();
                this.renderer.presentSd();
            }

            this.drawPillarbox();

            if (this.requireSession().isPlaybackRequested() && this.requireSession().getView().getUnitSystem().getUnitPool() == null) {
                this.renderer.fillRectangle(INITIALIZATION_PANEL_INSET, INITIALIZATION_PANEL_INSET,
                                            Math.max(1, this.viewWidth - INITIALIZATION_PANEL_HORIZONTAL_INSETS), INITIALIZATION_PANEL_HEIGHT,
                                            INITIALIZATION_PANEL_COLOR);
                this.developerTexts.queue("REPLAY INITIALIZATION FAILED",
                                          INITIALIZATION_TEXT_LEFT, INITIALIZATION_TITLE_TOP,
                                          INITIALIZATION_TITLE_SCALE, WARNING_COLOR);

                String statusText = this.requireSession().getStatus();
                int availableTextWidth = this.viewWidth - INITIALIZATION_TEXT_HORIZONTAL_INSETS;
                int lineLength = Math.max(1, availableTextWidth / INITIALIZATION_CHARACTER_WIDTH);
                int characterLimit = lineLength * INITIALIZATION_STATUS_LINE_LIMIT;
                StringBuilder visibleStatusText = new StringBuilder(Math.min(statusText.length(), characterLimit));

                for (int characterIndex = 0; characterIndex < statusText.length() && visibleStatusText.length() < characterLimit; characterIndex++) {
                    char character = statusText.charAt(characterIndex);

                    if (character == '\n' || character == '\r' || character == '\t') {
                        visibleStatusText.append(' ');
                    } else if (character >= PRINTABLE_CHARACTER_MINIMUM && character != DELETE_CHARACTER) {
                        // The renderer draws non-ASCII characters from the installed multilingual font.
                        visibleStatusText.append(character);
                    }
                }

                String remainingStatusText = visibleStatusText.toString();

                for (int lineIndex = 0; !remainingStatusText.isEmpty() && lineIndex < INITIALIZATION_STATUS_LINE_LIMIT; lineIndex++) {
                    int splitPosition = Math.min(lineLength, remainingStatusText.length());

                    if (splitPosition < remainingStatusText.length()) {
                        int separatorPosition = remainingStatusText.lastIndexOf(' ', splitPosition);

                        if (separatorPosition > lineLength / CENTER_DIVISOR) {
                            splitPosition = separatorPosition;
                        }
                    }

                    this.developerTexts.queue(remainingStatusText.substring(0, splitPosition),
                                              INITIALIZATION_TEXT_LEFT, INITIALIZATION_BODY_TOP + lineIndex * INITIALIZATION_BODY_LINE_HEIGHT,
                                              INITIALIZATION_BODY_SCALE, TEXT_COLOR);
                    remainingStatusText = remainingStatusText.substring(splitPosition).stripLeading();
                }
            }

            if (selectedUnit != null) {
                float horizontal = camera.worldToScreenX(selectedUnit.getX());
                float vertical = camera.worldToScreenY(selectedUnit.getY());
                float radius = Math.max(INSPECTION_MINIMUM_RADIUS, INSPECTION_WORLD_RADIUS * camera.getZoom());

                this.renderer.fillRectangle(horizontal - radius, vertical - radius,
                                            radius * DIAMETER_PER_RADIUS, INSPECTION_OUTLINE_THICKNESS,
                                            ACCENT_COLOR);
                this.renderer.fillRectangle(horizontal - radius, vertical + radius - INSPECTION_OUTLINE_THICKNESS,
                                            radius * DIAMETER_PER_RADIUS, INSPECTION_OUTLINE_THICKNESS,
                                            ACCENT_COLOR);
                this.renderer.fillRectangle(horizontal - radius, vertical - radius,
                                            INSPECTION_OUTLINE_THICKNESS, radius * DIAMETER_PER_RADIUS,
                                            ACCENT_COLOR);
                this.renderer.fillRectangle(horizontal + radius - INSPECTION_OUTLINE_THICKNESS, vertical - radius,
                                            INSPECTION_OUTLINE_THICKNESS, radius * DIAMETER_PER_RADIUS,
                                            ACCENT_COLOR);
            }
        } else {
            // Without a replay the view is empty; the debug box says how to open one.
            if (loadingMessage == null) {
                this.developerTexts.queue("Drop a .rep file here, or press Enter and type /replay open.",
                                          NO_REPLAY_TEXT_LEFT, NO_REPLAY_TEXT_TOP,
                                          NO_REPLAY_TEXT_SCALE, TEXT_COLOR);
            } else if (this.loadingProgress >= 0) {
                float barWidth = Math.min(LOADING_BAR_MAXIMUM_WIDTH, this.viewWidth - LOADING_BAR_HORIZONTAL_INSETS);
                float left = (this.viewWidth - barWidth) / CENTER_DIVISOR;
                float top = this.viewHeight / CENTER_FLOAT_DIVISOR;

                this.developerTexts.queue(loadingMessage, left, top - LOADING_CAPTION_GAP, LOADING_CAPTION_SCALE, TEXT_COLOR);
                this.renderer.fillRectangle(left, top, barWidth, LOADING_BAR_HEIGHT, LOADING_BAR_COLOR);
                this.renderer.fillRectangle(left, top, barWidth * Math.min(1, this.loadingProgress), LOADING_BAR_HEIGHT, ACCENT_COLOR);
            }
        }

        if (this.frameTiming != null) {
            this.frameTiming.mark(FramePhaseUtils.WORLD_OVERLAY);
        }

        this.updateWarnings();

        if (this.debugView || this.session == null || !this.requireSession().isPlaybackRequested()) {
            this.drawDebugHud(loadingMessage);
        }

        this.renderer.endFrame();
        this.developerTexts.draw(this.getInterfaceRenderer(), this.framebufferWidth, this.framebufferHeight);

        if (this.frameTiming != null) {
            this.frameTiming.mark(FramePhaseUtils.HUD);
        }

        if (!this.sdInterfaceDrawn) {
            this.drawConsole();
        }

        this.drawDebugPanel();

        // A loading frame shows the loading panel (the task may have finished within this frame: it then shows 100%).
        if (!this.sdInterfaceDrawn) {
            this.drawGlueOverGame(loadingMessage != null);
        }

        this.sdInterfaceDrawn = false;

        if (this.frameTiming != null) {
            this.frameTiming.mark(FramePhaseUtils.CONSOLE);
        }
    }

    /**
     * Applies fullscreen display gamma while focused and restores the saved monitor ramp otherwise ({@link HostWindow#applyGammaRamp}); the ramp
     * is built again only when the gamma option changes.
     */
    private void applyGamma() {
        int gamma = this.options.getGraphicsSettings().getGamma();
        short[] ramp = this.gammaRamp;

        if (ramp == null || gamma != this.appliedGamma) {
            ramp = new short[HostWindow.GAMMA_RAMP_SIZE * HostWindow.GAMMA_RAMP_CHANNEL_COUNT];

            for (int index = 0; index < HostWindow.GAMMA_RAMP_SIZE; index++) {
                short value = (short) GraphicsSettings.getGammaRampValue(gamma, index);

                ramp[index]                                                              = value;
                ramp[HostWindow.GAMMA_RAMP_SIZE + index]                                 = value;
                ramp[HostWindow.GAMMA_RAMP_SIZE * GAMMA_RAMP_BLUE_CHANNEL_INDEX + index] = value;
            }

            this.gammaRamp    = ramp;
            this.appliedGamma = gamma;
        }

        this.requireHostWindow().applyGammaRamp(ramp);
    }

    /**
     * Sets the swap interval from {@link GraphicsSettings} when it changed, at window creation and on every frame after, so that the options' VSync
     * applies at once: hidden windows never wait for the display; adaptive falls back to on without
     * {@code WGL_EXT_swap_control_tear}/{@code GLX_EXT_swap_control_tear} ({@link HostWindow#setSwapInterval}). A throttled live game turns VSync
     * off until its throttle ends ({@link #presentThrottle}); the option itself keeps its value.
     */
    private void applySwapInterval() {
        int interval = this.options.isHidden() || this.presentThrottle.isActive()
                       ? GraphicsSettings.SWAP_INTERVAL_OFF
                       : this.options.getGraphicsSettings().getSwapInterval();

        this.requireHostWindow().setSwapInterval(interval);
    }

    /**
     * Returns the frame rate limit of the {@code --max-fps} option ({@code FPSLimit}) that the host paces presented frames to. Only presentation is
     * paced: the simulation advances from host time, so game speed does not depend on the limit. While the draw throttle of a live game is active
     * the throttle keeps the limit instead ({@link #presentThrottle}), so that the passes between two drawn frames still run their frames.
     *
     * @return the frames per second, or {@code 0} while VSync or the draw throttle of a live game paces the frames
     */
    private int getFrameRateLimit() {
        GraphicsSettings graphics = this.options.getGraphicsSettings();

        if (this.presentThrottle.isActive() || graphics.getSwapInterval() != GraphicsSettings.SWAP_INTERVAL_OFF) {
            return 0;
        }

        int limit = graphics.getEffectiveFrameRateLimit();

        if (limit <= 0) {
            limit = this.session != null && !this.isGlueActive() ? NATIVE_GAME_FPS_LIMIT : NATIVE_MENU_FPS_LIMIT;
        }

        return limit;
    }

    /**
     * @return the host's client window
     * @throws IllegalStateException if the window does not exist yet
     */
    private @NotNull HostWindow requireHostWindow() {
        HostWindow current = this.hostWindow;

        if (current == null) {
            throw Exceptions.newIllegalStateException("The client window does not exist.");
        }

        return current;
    }

    /**
     * @return the world renderer
     * @throws IllegalStateException if the world renderer does not exist yet
     */
    private @NotNull WorldRenderer requireRenderer() {
        WorldRenderer current = this.renderer;

        if (current == null) {
            throw Exceptions.newIllegalStateException("The world renderer does not exist.");
        }

        return current;
    }

    /**
     * @return the client's interface renderer
     * @throws IllegalStateException if the client resources are not open
     */
    private @NotNull InterfaceRenderer requireInterfaceRenderer() {
        InterfaceRenderer current = this.getInterfaceRenderer();

        if (current == null) {
            throw Exceptions.newIllegalStateException("The client resources are not open.");
        }

        return current;
    }

    /**
     * @return the running session
     * @throws IllegalStateException if no session runs
     */
    @NotNull ClientSession requireSession() {
        ClientSession current = this.session;

        if (current == null) {
            throw Exceptions.newIllegalStateException("No session runs.");
        }

        return current;
    }

    /**
     * @return the console view of the running game
     * @throws IllegalStateException if no game console exists
     */
    @NotNull ConsoleView requireConsoleView() {
        ConsoleView current = this.consoleView;

        if (current == null) {
            throw Exceptions.newIllegalStateException("No game console exists.");
        }

        return current;
    }

    /**
     * @return the console model of the running game
     * @throws IllegalStateException if no game console exists
     */
    @NotNull ConsoleModel requireConsoleModel() {
        ConsoleModel current = this.consoleModel;

        if (current == null) {
            throw Exceptions.newIllegalStateException("No game console exists.");
        }

        return current;
    }

    /**
     * @return the world interface of the running game
     * @throws IllegalStateException if no world interface exists
     */
    @NotNull WorldInterface requireWorldInterface() {
        WorldInterface current = this.worldInterface;

        if (current == null) {
            throw Exceptions.newIllegalStateException("No world interface exists.");
        }

        return current;
    }

    /**
     * @return the system clipboard of the client window; without the window it holds nothing
     */
    @NotNull TextClipboard getClipboard() {
        return this.clipboard;
    }

    /**
     * @return {@code true} if the client window exists and has the input focus, {@code false} otherwise
     */
    boolean isWindowFocused() {
        HostWindow current = this.hostWindow;

        return current != null && current.isFocused();
    }

    /**
     * @param key the key
     * @return {@code true} if the client window exists and holds the key down, {@code false} otherwise
     */
    boolean isWindowKeyDown(@NotNull Key key) {
        HostWindow current = this.hostWindow;

        return current != null && current.isKeyDown(key);
    }

    /**
     * Moves the window's pointer to a framebuffer point; nothing moves without the window or while it does not have the input focus.
     *
     * @param x the framebuffer column
     * @param y the framebuffer row
     */
    void moveWindowPointer(float x, float y) {
        HostWindow current = this.hostWindow;

        if (current != null) {
            current.setPointerPosition(x * this.windowWidthResult[0] / Math.max(1f, this.framebufferWidth),
                                       y * this.windowHeightResult[0] / Math.max(1f, this.framebufferHeight));
        }
    }

    /**
     * Sets the window's pointer clip when it changes, as the native cursor clip ({@code ClipCursor}): the captured pointer stays in the game screen
     * (the view rectangle of the shown mode and aspect) and, during a minimap drag, in the minimap.
     *
     * @param clip the clip in framebuffer pixels, or {@code null} for none
     */
    private void updatePointerClip(@Nullable PointerClip clip) {
        HostWindow current = this.hostWindow;

        if (current == null || Objects.equals(clip, this.pointerClip)) {
            return;
        }

        current.setPointerClip(clip);
        this.pointerClip = clip;
    }

    /**
     * Asks the client window to close, as its close button does; nothing happens without the window.
     */
    private void requestWindowClose() {
        HostWindow current = this.hostWindow;

        if (current != null) {
            current.requestClose();
        }
    }

    /**
     * @return the host clock ({@link ClientHost#getTime}) in milliseconds, or {@code 0} before the run starts
     */
    private long getHostClockMilliseconds() {
        ClientHost host = this.clientHost;

        return host == null ? 0 : (long) (host.getTime() * MILLISECONDS_PER_SECOND);
    }

    /**
     * Opens the HD scene of the selected quality for the session's map, loading the quality's image set first when it is not loaded.
     *
     * @throws IOException if the HD images or the tileset's HD assets are unavailable
     */
    private void openHdScene() throws IOException {
        GameMap map = this.requireSession().getView().getMap();
        HdImageSet images = this.ensureImageSet();

        if (images == null) {
            throw Exceptions.newIOException("HD images are unavailable.");
        }

        // Images missing from the preload (a failed read) stream in; screenshot runs wait for every image so that the captured frame is complete.
        this.worldSkin.openHdScene(this.requireRenderer(), this.requireClientContext().getHdTilesetAssets(map.getTileset()), images, map,
                                   this.options.isHdLighting(), this.options.getScreenshotPath() == null);
    }

    /**
     * Runs the shared loading mechanism for one frame: makes sure the image set of the selected graphics is loaded (at startup and after a graphics
     * change) and creates the HD scene of a new game (its map terrain loads with it), and while a task runs advances it and draws the loading screen
     * instead of the glue or the game, whose input and simulation wait.
     *
     * @return {@code true} if this frame was a loading frame, {@code false} otherwise
     */
    private boolean updateLoading() {
        if (this.framebufferWidth <= 0 || this.framebufferHeight <= 0) {
            return false;
        }

        this.ensureImageSet();

        if (!this.loading.isLoading() && this.session != null && this.worldSkin.isHdScenePending()) {
            try {
                this.openHdScene();
            } catch (IOException | RuntimeException exception) {
                this.setAssetWarning("HD renderer unavailable: " + exception.getMessage());
                this.worldSkin.setQuality(null);
            }
        }

        if (!this.loading.isLoading()) {
            return false;
        }

        this.loading.advance(LOADING_BUDGET_NANOSECONDS);
        this.drawLoadingScreen();

        if (this.pendingLoadingShot != null) {
            // The first loading frame after /graphics <quality> <file>.
            try {
                this.captureScreenshot(this.pendingLoadingShot);
            } catch (IOException exception) {
                this.setAssetWarning("Screenshot: " + exception.getMessage());
            }

            this.pendingLoadingShot = null;
        }

        if (this.pendingShot != null) {
            // An input script's shot is the next drawn frame, a loading frame included.
            try {
                this.captureScreenshot(this.pendingShot);
            } catch (IOException exception) {
                this.setAssetWarning("Screenshot: " + exception.getMessage());
            }

            this.pendingShot = null;
        }

        this.presentFrame();

        return true;
    }

    /**
     * Draws the current loading panel over the menus or game frame.
     */
    private void drawLoadingScreen() {
        if (this.session == null && this.pendingReplay == null && this.drawGlue()) {
            return;
        }

        if (this.session != null && this.openGlueOverGame()) {
            // The one loading panel of the glue (LoadingPanel) over the game frame.
            this.drawFrame(this.loading.getTitle() == null ? "Loading" : this.loading.getTitle());

            return;
        }

        this.loadingProgress = this.loading.getProgress();

        String title = this.loading.getTitle();

        this.drawFrame((title == null ? "Loading" : title) + " " + Math.round(this.loadingProgress * PERCENT_SCALE) + "%");
        this.loadingProgress = -1;
    }

    /**
     * Closes the current HD scene and starts loading the selected image quality before drawing it.
     *
     * @param quality the HD quality, or {@code null} for the classic graphics
     */
    private void selectGraphicsQuality(HdRenderAssets.@Nullable Quality quality) {
        this.worldSkin.setQuality(quality);

        LOGGER.debug("RENDERER: " + (quality == null ? "classic" : quality.name()));
        this.ensureImageSet();
    }

    /**
     * Returns the image set of the selected quality, creating it and starting its load when the quality or the lighting path changed; classic
     * graphics release it.
     *
     * @return the image set, or {@code null} for classic graphics or when the HD anims are unavailable
     */
    private @Nullable HdImageSet ensureImageSet() {
        HdRenderAssets.Quality quality = this.worldSkin.getQuality();
        ClientContext client = this.clientContext;

        if (quality == null) {
            if (client != null && client.getHdImages() != null) {
                this.loading.cancel();
                this.closeImageSet();
            }

            return null;
        }

        HdImageSet images = client == null ? null : client.getHdImages();

        if (images != null && images.getQuality() == quality && images.isLighting() == this.options.isHdLighting()) {
            return images;
        }

        this.loading.cancel();
        this.closeImageSet();

        try {
            // The client's one storage and one image set (ClientContext), both kept for the whole run.
            ClientContext context = this.requireClientContext();

            images = new HdImageSet(HdAnimations.load(context.getResources(), quality, this.options.getHdSkin()), this.options.isHdLighting(),
                                    this.uploadWindow);
            context.setHdImages(images);
        } catch (IOException | RuntimeException exception) {
            this.setAssetWarning("HD renderer unavailable: " + exception.getMessage());
            this.worldSkin.setQuality(null);

            return null;
        }

        // The loads above were canceled; queued behind the client data that opening the client context just queued, so that the startup data is not
        // canceled and read again at the first game start.
        this.loading.enqueue(new HdImageSetLoadTask(images));

        return images;
    }

    private void closeImageSet() {
        this.worldSkin.closeHdScene();

        if (this.clientContext != null) {
            this.clientContext.setHdImages(null);
        }
    }

    /**
     * Cycles classic, HD2 and HD graphics, persisted as the options popup's HD graphics are.
     */
    void cycleSceneQuality() {
        this.glueState.cycleGraphics();
    }

    /**
     * Creates the console view for a loaded session; its textures follow the map tileset palette.
     *
     * @param loadedSession the loaded session
     * @param tileset       the map tileset whose palette the textures follow
     */
    private void openConsole(@NotNull ClientSession loadedSession, int tileset) {
        this.closeConsole();

        try {
            ClientContext client = this.requireClientContext();
            // The client data (text tables, sound table, portrait and panel tables) loaded at the client start: a game start reads only its
            // map and tileset.
            TextTables text = client.getTextTables(this.getTextLocale());

            this.consoleView = new ConsoleView(loadedSession.getStorage(),
                                               client.getInterfaceRenderer(),
                                               client.getGameFonts(),
                                               client.getCursor(),
                                               text.getStatText(),
                                               client.getTimers(),
                                               client.getSheetFrameRecords(),
                                               client::getTilesetAssets);
            this.consoleView.setGluText(text.getGluText());
            this.consoleView.setTextLocale(this.getTextLocale());
            this.consoleView.setHotkeyProfile(HotkeyProfile.selectForGame(this.options.getHotkeyProfile(), loadedSession.getGameFlags()),
                                              this.options.getHotkeys());
            this.consoleView.setSkin(this.options.getHdSkin());
            this.consoleView.setControlText(text.getControl());
            this.consoleView.setNetworkText(text.getNetwork());
            // The sound names of sfx.json, for the sounds played by name in games and replays alike.
            this.soundTable = client.getSoundTable();

            try {
                this.consoleView.setObserverProduction(client.getObserverProduction());
            } catch (IOException | RuntimeException exception) {
                this.setAssetWarning("Production panel unavailable: " + exception.getMessage());
            }

            try {
                this.consoleView.setPortraitPlayer(
                        new PortraitPlayer(loadedSession.getStorage(), client.getPortraitTables(), VideoClipDecoder.getDefault())
                );
            } catch (IOException | RuntimeException | LinkageError exception) {
                this.setAssetWarning("Portraits unavailable: " + exception.getMessage());
            }

            this.consoleView.setHdVariant(this.options.getInterfaceSettings().isConsoleSplit()
                                          ? HdConsoleLayout.Variant.WIDE
                                          : HdConsoleLayout.Variant.CENTERED);
            this.consoleView.loadTileset(tileset);
        } catch (IOException | RuntimeException exception) {
            this.closeConsole();
            this.setAssetWarning("Console: " + exception.getMessage());
        }
    }

    private void closeConsole() {
        this.updatePointerClip(null);

        if (this.systemCursorHidden || this.pointerMode != PointerMode.NORMAL) {
            this.requireHostWindow().setPointerMode(PointerMode.NORMAL);

            if (this.cursorSurface != null) {
                this.cursorSurface.hide();
            }

            this.pointerMode        = PointerMode.NORMAL;
            this.blankCursorSet     = false;
            this.systemCursorHidden = false;
        }

        this.gameClientState.closeDialogs();

        if (this.consoleView != null) {
            this.consoleView.close();
        }

        if (this.pluginPainter != null) {
            this.pluginPainter.close();
        }

        this.pluginPainter = null;

        this.consoleView    = null;
        this.consoleModel   = null;
        this.worldInterface = null;
    }

    /**
     * Updates game and interface geometry from the window, graphics mode, console and replay view.
     */
    void updateGameViewport() {
        boolean replayView = this.consoleReplayControls && this.session != null && !this.requireSession().isLive();

        this.gameViewport.setSurface(this.viewSurface);
        this.gameViewport.setInterface(this.worldSkin.getQuality() != null && !this.options.getGraphicsSettings().isOriginalAspectRatio(),
                                       this.session != null && this.isConsoleShown(), replayView);

        if (this.session != null) {
            ClientCamera camera = this.requireSession().getCamera();

            if (camera.getViewport() != this.gameViewport) {
                camera.setViewport(this.gameViewport);
            } else {
                camera.applyViewport();
            }
        }
    }

    /**
     * Covers the world outside the {@code 4:3} game screen.
     */
    private void drawPillarbox() {
        // The HD renderer uses the widescreen HD console pieces; the classic renderer the SD console at 4:3.
        if (this.consoleView != null) {
            HdRenderAssets.Quality quality = this.worldSkin.getQuality();

            this.consoleView.setHdScale(quality == null ? 0 : quality == HdRenderAssets.Quality.HD2 ? HD2_ASSET_SCALE : HD_ASSET_SCALE);
            this.consoleView.setOriginalAspectRatio(this.options.getGraphicsSettings().isOriginalAspectRatio());
        }

        // The camera clamps against the game screen, so the map edges can scroll to the pillarbox edges.
        this.updateGameViewport();

        float left = this.gameViewport.getGameLeft();
        float right = this.gameViewport.getGameRight();

        if (left > 0) {
            this.requireRenderer().fillRectangle(0, 0, left, this.viewHeight, PILLARBOX_COLOR);
        }

        if (right < this.viewWidth) {
            this.requireRenderer().fillRectangle(right, 0, this.viewWidth - right, this.viewHeight, PILLARBOX_COLOR);
        }
    }

    /**
     * Creates the console model and its world interaction for the current game state; replay playback does not execute interface commands, so they
     * are reported instead.
     */
    void ensureConsoleModel() {
        ScrGameView state = this.requireSession().getView();

        if (this.consoleModel != null && this.consoleModel.getView() == state) {
            return;
        }

        this.consoleModel = new ConsoleModel(state, this.requireSession().getPresentation());
        this.viewJumps    = new ViewJumps(this.createPlayViewCamera(this.requireSession()),
                                          new LocalViewAlertPosition(this.requireSession().getPresentation().getInterfaceLink().getLocalView()),
                                          CAMERA_SLOT_COUNT, VIEW_SNAP);

        if (!this.requireSession().getPresentation().getInterfaceLink().getLocalView().isAlertKnown()) {
            this.viewJumps.startAlert();
        }

        if (this.consoleView != null) {
            this.consoleView.installMessageLines(this.consoleModel.getPresentation());
        }

        if (this.requireSession().isLive()) {
            // Live play: commands go into the session's turn buffer and execute on the next frame.
            ClientSession live = this.requireSession();

            this.worldInterface = new WorldInterface(this.consoleModel, (player, opcode, payload) -> {
                if (!live.submitLocalCommand(player, opcode, payload)) {
                    LOGGER.debug("UI COMMAND: 0x" + Integer.toHexString(opcode) + " is not recorded in live play");
                }
            }, true);
            this.worldInterface.setViewJumps(this.viewJumps);
            live.getPresentation().getInterfaceLink().setLocalSelection(this.worldInterface);
            this.consoleModel.setOwnLocalSelection(live.getLocalPlayer());
            this.worldInterface.setAltKey(() -> this.isWindowKeyDown(Key.LEFT_ALT) || this.isWindowKeyDown(Key.RIGHT_ALT));
            this.installUnitSpeech(live);
            this.worldInterface.getCommandInput().setErrorOutput((statTextIdentifier, soundName, race) ->
                                                                         this.showCommandError(live, statTextIdentifier, soundName, race));

            this.chatHistory = new ChatHistory();

            ChatHistory history = this.chatHistory;

            live.getPresentation().getInterfaceLink().getMessageLines().setChatListener(
                    (line, style, color) -> history.add(new String(line, StandardCharsets.UTF_8), style, color)
            );

            try {
                this.gameMenu          = new GameMenu(live.getStorage(), live.isNetworkGame() ? GameMenu.Mode.MULTIPLAYER : GameMenu.Mode.SINGLE_PLAYER,
                                                      this.consoleView == null ? key -> "" : this.consoleView::getControlString);
                this.networkWaitDialog = live.isNetworkGame() ? new NetworkWaitDialog(live.getStorage()) : null;
            } catch (IOException exception) {
                this.setAssetWarning("Game menu: " + exception.getMessage());
            }

            this.endPhase = END_NONE;
            this.gameTips = null;
            this.openStartupTips();

            return;
        }

        this.worldInterface = new WorldInterface(this.consoleModel, (player, opcode, payload) -> {
            StringBuilder bytes = new StringBuilder();

            for (byte value : payload) {
                bytes.append(String.format(Locale.ROOT, " %02X", Byte.toUnsignedInt(value)));
            }

            String message = String.format(Locale.ROOT, "UI command 0x%02X of player %d not executed during replay playback:%s",
                                           opcode, player, bytes);

            LOGGER.debug("UI COMMAND: " + message);
            this.setAssetWarning(message);
        }, false);
        this.worldInterface.setViewJumps(this.viewJumps);
        // The replay viewer's selection is the local array EUD reads see; hatching adds nothing (no commands).
        this.requireSession().getPresentation().getInterfaceLink().setLocalSelection(this.worldInterface);
        this.worldInterface.getCommandInput().setReplayControls(new CommandInput.ReplayControls() {
            /**
             * Toggles replay playback.
             */
            @Override
            public void playOrPause() {
                if (Main.this.session != null) {
                    Main.this.requireSession().playOrPauseReplay();
                }
            }

            /**
             * Decreases replay speed.
             */
            @Override
            public void decreaseSpeed() {
                if (Main.this.session != null) {
                    Main.this.requireSession().decreaseReplaySpeed();
                }
            }

            /**
             * Increases replay speed.
             */
            @Override
            public void increaseSpeed() {
                if (Main.this.session != null) {
                    Main.this.requireSession().increaseReplaySpeed();
                }
            }
        });

        try {
            this.gameMenu = new GameMenu(this.requireSession().getStorage(), GameMenu.Mode.REPLAY,
                                         this.consoleView == null ? key -> "" : this.consoleView::getControlString);
        } catch (IOException exception) {
            this.setAssetWarning("Game menu: " + exception.getMessage());
        }

        this.endPhase = END_NONE;
        this.requireSession().setReplayPlaybackControl(new ReplayCommandExecutor.PlaybackControl() {
            /**
             * @return {@code true} if the viewer's playback is paused, {@code false} otherwise
             */
            @Override
            public boolean isPlaybackPaused() {
                return !Main.this.playing;
            }

            /**
             * Sets whether the viewer's playback is paused.
             *
             * @param paused whether playback pauses
             */
            @Override
            public void setPlaybackPaused(boolean paused) {
                Main.this.playing = !paused && Main.this.session != null && Main.this.requireSession().canStep();
            }

            /**
             * Seeks the viewer's playback to a frame: a frame before the current one restarts the replay first.
             *
             * @param targetFrame the frame to reach, the replay's frame count at most
             */
            @Override
            public void seekReplay(int targetFrame) {
                if (Main.this.session != null) {
                    Main.this.playback.requestSeek(Integer.toUnsignedLong(targetFrame), Main.this.requireSession().getLastCompletedFrame());
                }
            }
        });
    }

    /**
     * Updates the console model from the recorded selection and draws it over the world view.
     */
    private void drawConsole() {
        if (!this.isConsoleShown() || this.getInterfaceRenderer() == null) {
            return;
        }

        this.ensureConsoleModel();

        // The options popup changes the Mini-Map Position and the portraits at once.
        InterfaceSettings interfaceSettings = this.options.getInterfaceSettings();

        this.requireConsoleView().setHdVariant(interfaceSettings.isConsoleSplit() ? HdConsoleLayout.Variant.WIDE : HdConsoleLayout.Variant.CENTERED);
        this.consoleView.setPortraitMode(interfaceSettings.getUnitPortraits());

        try {
            CommandCard.Perspective perspective = this.consoleReplayControls ? CommandCard.Perspective.NATIVE_REPLAY : CommandCard.Perspective.PLAYER;
            GameSpeed timing = this.requireSession().getFrameTiming();

            this.requireConsoleModel().update(this.consolePlayer, perspective, !this.playing, timing.getSpeedIndex(), timing.getFactor());
            // Hide tooltips while the game menu is open.
            this.consoleView.setSideBarTooltipsHidden(this.isGamePopupOpen());

            if (perspective == CommandCard.Perspective.NATIVE_REPLAY) {
                this.updateObserverInputs();
            }

            ReplayHeader header = this.requireSession().getReplay().getHeader();

            this.consoleView.setReplayState(Math.max(0, this.requireSession().getLastCompletedFrame()), header.getFrames(), header.getSpeed(),
                                            timing.getSpeedIndex(), timing.getFactor(), !this.playing, this.playback.getSeekTarget());
            this.requireWorldInterface().expireMarkers(this.requireSession().getView().getCurrentFrame(), WorldInterface.MARKER_LIFETIME);

            ClientCamera camera = this.requireSession().getCamera();
            ScrGameView gameView = this.requireSession().getView();
            boolean plugins = gameView.hasPlugins();
            PluginDrawPainter pluginPainter = plugins ? this.preparePluginPainter() : null;

            this.beginConsoleTarget();

            if (pluginPainter != null) {
                pluginPainter.setSpace(camera.worldToScreenX(0), camera.worldToScreenY(0), camera.getZoom(), camera.getZoom());
                gameView.paintDrawLists(ScrDrawList.Space.WORLD, pluginPainter);
            }

            if (this.debugView) {
                this.debugOverlay.draw(this.requireSession().getView(), this.getInterfaceRenderer(), new DebugOverlay.View() {
                    /**
                     * @return the screen pixels per map pixel
                     */
                    @Override
                    public float getZoom() {
                        return camera.getZoom();
                    }

                    /**
                     * Converts a horizontal map position to a screen column.
                     *
                     * @param mapX the horizontal map position
                     * @return the screen column
                     */
                    @Override
                    public float toScreenX(float mapX) {
                        return camera.worldToScreenX(mapX);
                    }

                    /**
                     * Converts a vertical map position to a screen row.
                     *
                     * @param mapY the vertical map position
                     * @return the screen row
                     */
                    @Override
                    public float toScreenY(float mapY) {
                        return camera.worldToScreenY(mapY);
                    }
                }, this.viewWidth, this.viewHeight, this.debugVisionPlayer);
            }

            if (this.worldInterface.isDragSelecting()) {
                float left = camera.worldToScreenX(this.worldInterface.getDragLeft());
                float top = camera.worldToScreenY(this.worldInterface.getDragTop());

                this.getInterfaceRenderer().drawRectangle(left, top,
                                                          camera.worldToScreenX(this.worldInterface.getDragRight()) - left,
                                                          camera.worldToScreenY(this.worldInterface.getDragBottom()) - top,
                                                          DRAG_OUTLINE_THICKNESS, DRAG_OUTLINE_COLOR);
            }

            this.drawPlacementGrid(camera);
            // The minimap frame shows the world inside the game screen.
            this.consoleView.setWindowed(!this.isFullScreen());
            this.consoleView.setMultiplayerButtons(this.requireSession().hasMultiplayerMinimapButtons());
            this.consoleView.setMultiplayerGame(this.requireSession().isLive() && this.requireSession().isNetworkGame());
            this.consoleView.setShowTimer(this.options.getInterfaceSettings().isGameTimer());
            this.updateApmDisplay(perspective == CommandCard.Perspective.NATIVE_REPLAY);

            ScrConsolePlacement placement = plugins ? gameView.findConsolePlacement() : null;

            if (plugins) {
                this.drawScreenOverlay(gameView.findScreenOverlay(), this.screenOverlay);
            }

            if (!plugins || !gameView.isConsoleHidden()) {
                SpriteSheet resourceIcons = plugins ? gameView.findResourceIcons() : null;
                boolean iconPalette = resourceIcons != null && this.requireSession().copyDisplayPalette(this.screenOverlayCycled);

                this.consoleView.setPlacement(placement);
                this.consoleView.setResourceIcons(iconPalette ? resourceIcons : null, this.screenOverlayCycled);
                this.consoleView.setTargetResumer(this::beginConsoleTarget);
                this.consoleView.draw(this.consoleModel, this.gameViewport, camera);

                if (placement != null) {
                    this.drawScreenOverlay(placement.getSurfaces(), this.consoleSurfaces);
                }

                if (pluginPainter != null && this.consoleView.placeMinimap(this.minimapPlacement)) {
                    this.drawMinimapLists(gameView, pluginPainter);
                }
            }

            if (pluginPainter != null) {
                float scale = this.gameViewport.getScale();

                pluginPainter.setSpace(this.gameViewport.toScreenX(0), this.gameViewport.toScreenY(0), scale, scale);
                gameView.paintDrawLists(ScrDrawList.Space.SCREEN, pluginPainter);
            }

            if (this.debugView) {
                this.drawUnitInspection();
            }

            this.drawPrompt();
            this.drawGamePopups();
            this.placeGameCursor();
            this.getInterfaceRenderer().end();
        } catch (IOException | RuntimeException exception) {
            this.setAssetWarning("Console: " + exception);
        }
    }

    /**
     * Prepares the painter of the plugins' draw lists for this frame, made again when the interface renderer changed, and supplies the game screen
     * of the frame to the game's local display: the interface size, the real pixels per interface unit (those of the SD interface layer while one
     * is drawn), the graphics, the font measure and the console rectangles (none while a plugin hides the console).
     *
     * @return the painter, or {@code null} without an interface renderer
     */
    private @Nullable PluginDrawPainter preparePluginPainter() {
        InterfaceRenderer interfaceRenderer = this.getInterfaceRenderer();

        if (interfaceRenderer == null || this.clientContext == null) {
            return null;
        }

        PluginDrawPainter painter = this.pluginPainter;

        if (painter == null || !painter.isFor(interfaceRenderer)) {
            if (painter != null) {
                painter.close();
            }

            painter            = new PluginDrawPainter(interfaceRenderer, this.clientContext.getGameFonts());
            this.pluginPainter = painter;
        }

        LocalDisplay display = this.requireSession().getPresentation().getInterfaceLink().getLocalDisplay();
        float pixels = this.sdInterfaceFramebuffer != 0 ? (float) this.sdInterfaceHeight / Math.max(1, this.framebufferHeight) : 1;
        float scale = this.gameViewport.getScale() * pixels;
        HdRenderAssets.Quality quality = this.worldSkin.getQuality();

        if (scale > 0) {
            display.setScreen(this.gameViewport.getInterfaceWidth(), GameViewport.INTERFACE_HEIGHT, scale,
                              quality == null
                              ? AssetResolver.AssetSet.SD
                              : quality == HdRenderAssets.Quality.HD2 ? AssetResolver.AssetSet.HD2 : AssetResolver.AssetSet.HD);
        }

        display.setTextMeasure(painter);
        display.setConsoleRectangles(this.consoleView != null && !this.requireSession().getView().isConsoleHidden() ? this.consoleView : null);

        return painter;
    }

    /**
     * Draws the minimap commands of the plugins' draw lists on the minimap placed in {@link #minimapPlacement}, limited to the minimap bitmap,
     * then limits the drawing to the game screen again.
     *
     * @param gameView the game view
     * @param painter  the painter
     */
    private void drawMinimapLists(@NotNull ScrGameView gameView, @NotNull PluginDrawPainter painter) {
        InterfaceRenderer interfaceRenderer = this.getInterfaceRenderer();

        if (interfaceRenderer == null) {
            return;
        }

        float left = this.minimapPlacement[ConsoleView.MINIMAP_SCREEN_LEFT_INDEX];
        float top = this.minimapPlacement[ConsoleView.MINIMAP_SCREEN_TOP_INDEX];
        float scale = this.minimapPlacement[ConsoleView.MINIMAP_SCREEN_SCALE_INDEX];
        int clipLeft = (int) Math.floor(left);
        int clipTop = (int) Math.floor(top);
        int clipRight = (int) Math.ceil(left + gameView.getMap().getWidth() * TerrainTileset.TILE_SIZE * scale);
        int clipBottom = (int) Math.ceil(top + gameView.getMap().getHeight() * TerrainTileset.TILE_SIZE * scale);

        interfaceRenderer.setClip(clipLeft, clipTop, clipRight - clipLeft, clipBottom - clipTop);
        painter.setSpace(left, top, scale, this.gameViewport.getScale());

        try {
            gameView.paintDrawLists(ScrDrawList.Space.MINIMAP, painter);
        } finally {
            interfaceRenderer.setClip(Math.round(this.gameViewport.getGameLeft()), 0, Math.round(this.gameViewport.getGameWidth()), this.viewHeight);
        }
    }

    /**
     * Begins the interface drawing of the console: into the SD interface layer while one is drawn, else over the window, limited to the game screen.
     */
    private void beginConsoleTarget() {
        if (this.sdInterfaceFramebuffer != 0) {
            this.getInterfaceRenderer().begin(this.sdInterfaceFramebuffer, this.sdInterfaceWidth, this.sdInterfaceHeight,
                                              this.framebufferWidth, this.framebufferHeight);
        } else {
            this.getInterfaceRenderer().begin(this.framebufferWidth, this.framebufferHeight);
        }

        this.getInterfaceRenderer().setClip(Math.round(this.gameViewport.getGameLeft()), 0,
                                            Math.round(this.gameViewport.getGameWidth()), this.viewHeight);
    }

    /**
     * Draws a screen overlay of a plugin: the classic screen scaled to the view height and centered in the game screen, each shown pixel in the
     * display palette, the others transparent.
     *
     * @param overlay the overlay, or {@code null} for none
     * @param texture the texture that shows the overlay
     */
    private void drawScreenOverlay(@Nullable ScrScreenOverlay overlay, @NotNull ScreenOverlayTexture texture) {
        InterfaceRenderer interfaceRenderer = this.getInterfaceRenderer();

        if (overlay == null || overlay.getShownCount() == 0 || interfaceRenderer == null ||
            !this.requireSession().copyDisplayPalette(this.screenOverlayCycled)) {
            return;
        }

        int drawn = texture.update(interfaceRenderer, overlay, this.screenOverlayCycled);
        float scale = (float) this.viewHeight / overlay.getHeight();
        float drawnWidth = overlay.getWidth() * scale;
        float left = this.gameViewport.getGameLeft() + (this.gameViewport.getGameWidth() - drawnWidth) / CENTER_FLOAT_DIVISOR;

        interfaceRenderer.drawTexture(drawn, left, 0, drawnWidth, this.viewHeight, OPAQUE_WHITE_COLOR);
    }

    /**
     * Places the game cursor of the frame while the pointer is over the game view and hides the system cursor there; the console area shows the
     * arrow. The present draws the placed cursor ({@link #composeFrame}).
     */
    private void placeGameCursor() {
        this.gameClientState.moveInterfacePointer();

        boolean inView = (this.pointerFixed || this.inputScript != null || this.requireHostWindow().isPointerInside()) &&
                         this.pointerHorizontal >= 0 && this.pointerVertical >= 0 && this.pointerHorizontal < this.viewWidth &&
                         this.pointerVertical < this.viewHeight;

        boolean grab = !this.options.isNoGrab() && !this.options.isHidden() && this.session != null && this.isWindowFocused();
        // With the platform pointer (CursorSurface) the cursor pictures are set on the window and stay visible.
        boolean hardware = this.clientContext != null && this.updateCursorSurface(this.clientContext.getCursor());
        PointerMode mode = grab ? PointerMode.CAPTURED : inView && !hardware ? PointerMode.HIDDEN : PointerMode.NORMAL;

        if (mode != this.pointerMode) {
            this.requireHostWindow().setPointerMode(mode);
            this.pointerMode = mode;
        }

        // The confinement clips the captured pointer to the game screen, not the window: the bars beside a 4:3 frame are outside it.
        this.updatePointerClip(grab ? this.gameClientState.getPointerClip(this.gameViewport.getScreenClip()) : null);

        if (hardware) {
            this.blankCursorSet = false;
        } else if (grab && inView != this.blankCursorSet) {
            if (inView) {
                this.getCursorSurface().hide();
            } else {
                this.getCursorSurface().showDefault();
            }

            this.blankCursorSet = inView;
        } else if (!grab && this.blankCursorSet) {
            this.getCursorSurface().showDefault();
            this.blankCursorSet = false;
        }

        this.systemCursorHidden = inView && !hardware;

        if (!inView) {
            return;
        }

        if (this.worldInterface == null) {
            this.clientContext.getCursor().hide();

            return;
        }

        boolean onMap = this.isOnMapView();

        int edge = this.cameraScroll.getEdgeDirection();

        int[] minimap = onMap ? null : this.requireConsoleView().getMinimapGeometry(this.pointerHorizontal, this.pointerVertical);
        boolean onMinimap = ConsoleView.isInsideMinimap(minimap);

        if (this.session != null && this.requireSession().isTriggerPaused()) {
            this.requireConsoleView().placeCursor(WorldInterface.Cursor.ARROW, -1,
                                                  this.pointerHorizontal, this.pointerVertical,
                                                  this.getHostClockMilliseconds());

            return;
        }

        this.requireConsoleView().placeCursor(edge >= 0 ? WorldInterface.Cursor.SCROLL : onMap ? this.worldInterface.getCursor()
                                                                                               : onMinimap
                                                                                                 ? this.worldInterface.getMinimapCursor()
                                                                                                 : WorldInterface.Cursor.ARROW,
                                              edge >= 0 ? edge : onMap ? this.worldInterface.getScrollDirection() : -1,
                                              this.pointerHorizontal, this.pointerVertical,
                                              this.getHostClockMilliseconds());
    }

    /**
     * Runs one command line through the command registry ({@link #createCommandRegistry}).
     *
     * @param line the typed line
     * @return the message describing the effect
     */
    @NotNull String runPromptCommand(@NotNull String line) {
        return this.commands.run(line);
    }

    /**
     * Runs a typed command line and shows its reply as the game's system messages do: every line through the shared message lines
     * ({@code MessageLines.writeGameMessage}, the game message color) for {@link #COMMAND_REPLY_MILLISECONDS}. A reply longer than the message area
     * shows a page at a time ({@link ConsoleCommandRegistry#runPaged}, {@code /more} shows the next); without a game the reply is only logged.
     *
     * @param line the typed line
     */
    void runTypedCommand(@NotNull String line) {
        List<String> reply = this.commands.runPaged(line);

        LOGGER.info("COMMAND: " + line + " -> " + String.join(" | ", reply));

        // An unknown command is dropped: never sent, nothing shown.
        if (reply.equals(List.of(ConsoleCommandRegistry.UNKNOWN_COMMAND)) || this.session == null) {
            return;
        }

        for (String replyLine : reply) {
            this.requireSession().getPresentation().getInterfaceLink().getMessageLines().writeGameMessage(replyLine.getBytes(StandardCharsets.UTF_8),
                                                                                                          COMMAND_REPLY_MILLISECONDS);
        }
    }

    /**
     * Creates a {@link ConsoleCommandRegistry} instance for debug, camera, replay, console, preview, capture and LAN cheat commands.
     *
     * @return the created {@link ConsoleCommandRegistry} instance
     */
    private @NotNull ConsoleCommandRegistry createCommandRegistry() {
        ConsoleCommandRegistry registry = DebugCommands.createRegistry();
        ClientCommandTarget target = new ClientCommandTarget(this);

        DebugCommands.register(registry, this.createDebugTarget());
        CameraCommands.register(registry, target);
        ReplayCommands.register(registry, target);
        ConsoleCommands.register(registry, target);
        PreviewCommands.register(registry, target);

        ConsoleCommand graphics = ConsoleCommand.of("/graphics", "classic|hd2|hd [file.png]", "select the graphics as F5 does")
                                                .describe("The image set of the chosen graphics loads. A PNG file name captures the " +
                                                          "first loading frame there, for screenshot sequences.");

        for (GraphicsChoice choice : GraphicsChoice.values()) {
            graphics.subcommand(choice.getName(), "[file.png]", choice.getDescription(), arguments -> {
                if (arguments.length > 0) {
                    this.pendingLoadingShot = this.options.getInstallLayout().resolveUserPath(Path.of(arguments[0]));
                }

                this.glueState.selectGraphics(choice);

                return "Graphics " + choice.getName() + ".";
            });
        }

        registry.register(graphics.availability("everywhere; the choice is local.").example("/graphics hd2"));
        registry.register(ConsoleCommand.of("/shot", "<file.png>", "capture the next rendered frame to a PNG file")
                                        .otherwise("<file.png>", "write the next rendered frame to that file (screenshot sequences)", arguments -> {
                                            this.pendingShot = this.options.getInstallLayout().resolveUserPath(Path.of(arguments[0]));

                                            return "Shot " + arguments[0] + " at " + UiClockUtils.getHostTimeMilliseconds() + " ms.";
                                        })
                                        .availability("everywhere a frame renders.")
                                        .example("/shot shots/view.png"));
        registry.register(ConsoleCommand.of("/cheat", "", "allow or forbid cheat codes in a LAN game without other humans")
                                        .whenEmpty("toggle the cheat codes; the switch starts off in every network game, where typed " +
                                                   "codes are otherwise chat", arguments -> {
                                            if (!this.isLiveSession() || !this.requireSession().isNetworkGame()) {
                                                return "Use /cheat in a LAN game; single player always allows cheats.";
                                            }

                                            if (this.requireSession().hasOtherHuman()) {
                                                return "Cheats are not available with other players.";
                                            }

                                            boolean allowed = this.requireSession().toggleLocalCheats();
                                            String text = this.consoleView == null
                                                          ? ""
                                                          : this.consoleView.getNetworkText(allowed ? "strCHEATON" : "strCHEATOFF");

                                            return text.isEmpty() ? allowed ? "Cheats enabled." : "Cheats disabled." : text;
                                        })
                                        .availability("a LAN game without other human players; single player always allows cheats.")
                                        .example("/cheat"));
        registry.register(ConsoleCommand.of("/uihotkey", "<letter>", "press a command card hotkey (screenshot sequences)")
                                        .otherwise("<letter>", "press the command card button whose hotkey is that letter, for " +
                                                               "screenshots of targeting and placement", arguments -> {
                                            if (!this.isLiveSession() || !arguments[0].matches("[a-zA-Z]")) {
                                                return "Use /uihotkey <letter> in a live game.";
                                            }

                                            char letter = Character.toLowerCase(arguments[0].charAt(0));

                                            return this.pressCommandHotkey(Key.ofLetter(letter), 0)
                                                   ? "Hotkey " + letter + "."
                                                   : "Hotkey " + letter + " ignored.";
                                        })
                                        .availability("live games with the game console.")
                                        .example("/uihotkey b"));

        return registry;
    }

    /**
     * Creates a {@link DebugCommands.Target} instance that the debug commands drive the client's debug view through.
     *
     * @return the created {@link DebugCommands.Target} instance
     */
    private DebugCommands.@NotNull Target createDebugTarget() {
        return new DebugCommands.Target() {
            /**
             * Sets whether the development panel and debug overlays are shown.
             *
             * @param shown whether the debug view (development panel and overlays) is shown
             */
            @Override
            public void setDebugView(boolean shown) {
                Main.this.debugView = shown;
            }

            /**
             * @return {@code true} if the debug view is shown, {@code false} otherwise
             */
            @Override
            public boolean isDebugView() {
                return Main.this.debugView;
            }

            /**
             * @return the overlay whose layers the commands switch
             */
            @Override
            public @NotNull DebugOverlay getOverlay() {
                return Main.this.debugOverlay;
            }

            /**
             * @return the selected HD lighting debug view
             */
            @Override
            public @NotNull LightingView getLightingView() {
                return LightingView.values()[Math.max(Main.this.worldSkin.getLightingDebugView(), 0)];
            }

            /**
             * Sets the selected HD lighting debug view.
             *
             * @param view the view
             * @return the message describing the effect
             */
            @Override
            public @NotNull String setLightingView(@NotNull LightingView view) {
                return Main.this.selectLightingView(view);
            }

            /**
             * Sets the player whose vision the vision layer shows.
             *
             * @param player the player whose vision the vision layer shows
             */
            @Override
            public void setVisionPlayer(int player) {
                Main.this.debugVisionPlayer = player;
            }

            /**
             * @return {@code true} if the session is a network game, whose lockstep simulation the frame tools must not move, {@code false} otherwise
             */
            @Override
            public boolean isNetworkGame() {
                return Main.this.session != null && Main.this.requireSession().isNetworkGame();
            }

            /**
             * @return {@code true} if a replay plays, the only session {@code /seek} can move, {@code false} otherwise
             */
            @Override
            public boolean isReplay() {
                return Main.this.session != null && Main.this.requireSession().isPlaybackRequested();
            }

            /**
             * @return the last completed game frame
             */
            @Override
            public long getFrame() {
                return Main.this.session == null ? 0 : Main.this.requireSession().getLastCompletedFrame();
            }

            /**
             * Shows the next page of the debug panel.
             *
             * @return the page now shown
             */
            @Override
            public DebugPanel.@NotNull Page showNextDebugPage() {
                Main.this.debugPanel.cyclePage();

                return Main.this.debugPanel.getPage();
            }

            /**
             * Pauses and runs one game frame.
             */
            @Override
            public void step() {
                Main.this.playing = false;

                if (Main.this.session != null && Main.this.requireSession().stepFrame()) {
                    Main.this.notifyFramePlayed();
                    Main.this.applyLocalViewWrite();
                }
            }

            /**
             * Requests a seek of the replay.
             *
             * @param frame the game frame to seek to
             * @return the requested frame after clamping it to the replay
             */
            @Override
            public long seek(long frame) {
                long target = Math.clamp(frame, 0, Main.this.requireSession().getReplay().getHeader().getFrames());

                Main.this.playback.requestSeek(target, Main.this.requireSession().getLastCompletedFrame());

                return target;
            }
        };
    }

    /**
     * Updates the live local-player APM display and its alert once per game frame. Replay views hide this display.
     *
     * @param replayView whether a replay view is shown
     */
    private void updateApmDisplay(boolean replayView) {
        InterfaceSettings settings = this.options.getInterfaceSettings();
        LiveGame live = this.requireSession().getLiveGame();

        if (replayView || live == null || !settings.isActionsPerMinuteShown()) {
            this.requireConsoleView().setApmDisplay(false, 0, ApmAlert.NORMAL_CODE);

            return;
        }

        if (this.liveApmTracker == null || this.liveApmSession != this.session) {
            this.liveApmTracker = new ApmTracker(live.getHistory(), this.createCommandIssuerSlots());
            this.liveApmSession = this.session;
            this.apmAlert       = new ApmAlert();
            this.apmAlertFrame  = -1;
        }

        int frame = this.requireSession().getView().getCurrentFrame();
        int speed = Math.clamp(this.requireSession().getReplay().getHeader().getSpeed(), 0, GameSpeed.FASTEST);
        int[] actionsPerMinute = this.liveApmTracker.update(frame, GameSpeed.getBaseDuration(speed));
        int local = live.getLocalSlot();
        int shown = local >= 0 && local < actionsPerMinute.length ? actionsPerMinute[local] : 0;

        this.requireConsoleView().setApmDisplay(true, shown,
                                                ApmAlert.getColorCode(shown,
                                                                      settings.isActionsPerMinuteAlertEnabled(),
                                                                      settings.isActionsPerMinuteAlertColorEnabled(),
                                                                      settings.getActionsPerMinuteAlertThreshold()));

        if (frame == this.apmAlertFrame) {
            return;
        }

        this.apmAlertFrame = frame;

        if (!this.apmAlert.tick(this.liveApmTracker.getClock(), shown,
                                settings.isActionsPerMinuteAlertEnabled(),
                                settings.isActionsPerMinuteAlertColorEnabled(),
                                settings.isActionsPerMinuteAlertSoundEnabled(),
                                settings.getActionsPerMinuteAlertThreshold())) {
            return;
        }

        SoundTable sounds = this.soundTable;
        int sound = sounds == null ? 0 : sounds.getSoundIdentifier(ApmAlert.SOUND);

        if (sound != 0) {
            this.requireSession().playInterfaceSound(sound, -1, ClientAudio.MAXIMUM_VOLUME);
        }
    }

    /**
     * Passes the replay statistics panel its player names (by slot) and each slot's APM from the replay commands.
     */
    private void updateObserverInputs() {
        ReplayHeader header = this.requireSession().getReplay().getHeader();

        if (this.apmTracker == null || this.apmSession != this.session) {
            this.apmTracker = new ApmTracker(this.requireSession().getReplay().getCommands(), this.createCommandIssuerSlots());
            this.apmSession = this.session;
        }

        String[] names = new String[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        for (int index = 0; index < header.getPlayers().size() && index < PlayerTable.PLAYABLE_PLAYER_SLOTS; index++) {
            if (header.getPlayers().get(index).getType() != PlayerTypes.NONE) {
                names[index] = this.requireSession().getReplay().getPlayerNames().get(index);
            }
        }

        this.requireConsoleView().setObserverInputs(
                names, this.apmTracker.update(this.requireSession().getView().getCurrentFrame(),
                                              GameSpeed.getBaseDuration(Math.clamp(header.getSpeed(), 0, GameSpeed.FASTEST)))
        );
    }

    /**
     * Opens the replay vision dialog with the replay's human and computer players (slots {@code 0..7}).
     */
    void openVisionDialog() {
        if (this.consoleView == null || this.session == null || !this.requireSession().isPlaybackRequested()) {
            return;
        }

        List<VisionDialog.Row> rows = new ArrayList<>();
        GluText glueText = this.consoleView.getGluText();
        String[] tags = {"tag_zerg", "tag_terran", "tag_protoss"};

        for (ReplayHeader.ReplayPlayer player : this.requireSession().getReplay().getHeader().getPlayers()) {
            if (player.getSlotId() >= PlayerTable.PLAYABLE_PLAYER_SLOTS || player.getType() == PlayerTypes.NONE || player.getName().isEmpty()) {
                continue;
            }

            Player record = this.requireSession().getView().getPlayerTable().getPlayer(player.getSlotId());
            int race = record != null ? record.getRace() : player.getRace();
            String tag = glueText == null ? "" : glueText.get(race >= 0 && race < ScrRaceIds.PLAYABLE_COUNT ? tags[race] : "tag_random");

            rows.add(new VisionDialog.Row(player.getSlotId(), "[" + tag + "] " + player.getName(),
                                          this.consoleView.getPlayerColor(this.requireSession().getView().getPlayerTable(), player.getSlotId())));
        }

        List<VisionDialog.Row> shuffled = ShuffleUtils.shuffle(rows, (int) this.requireSession().getReplay().getHeader().getRandomSeed(),
                                                               this.requireSession().getPresentation().getRuntimeRandom());

        this.consoleView.getVisionDialog().open(shuffled, this.requireSession().getView().getLocalVision());
    }

    /**
     * Opens diplomacy rows for the active network players, using game-seeded order and the team and computer restrictions. An observer, which
     * holds no player record, has no diplomacy.
     */
    void openAllianceDialog() {
        ClientSession current = this.session;

        if (current == null || !current.isNetworkGame() || current.isLocalObserver() || this.consoleView == null) {
            return;
        }

        int local = current.getLocalPlayer();
        boolean allies = current.areAlliancesAllowed();
        boolean team = current.isTeamGame();
        List<VisionDialog.Row> players = new ArrayList<>();

        for (int slot : current.getActivePlayers()) {
            players.add(new VisionDialog.Row(slot, current.getPlayerName(slot),
                                             this.consoleView.getPlayerColor(current.getView().getPlayerTable(), slot)));
        }

        List<AllianceDialog.Row> rows = new ArrayList<>();

        for (VisionDialog.Row player : ShuffleUtils.shuffle(players, current.getGameSeed(), current.getPresentation().getRuntimeRandom())) {
            int slot = player.getPlayer();
            boolean boxes = team ? current.getPlayerForce(slot) != current.getPlayerForce(local) : slot != local;
            boolean computer = current.getPlayerType(slot) == PlayerTypes.COMPUTER;
            boolean visionAllowed = current.getPresentation().getPlayerDeparture().canShareVision(local, slot);

            rows.add(new AllianceDialog.Row(player, boxes, allies && !computer, !computer && visionAllowed));
        }

        this.consoleView.getAllianceDialog().open(rows, current.getLocalAlliances(), current.getLocalSharedVision(), local, allies);
    }

    /**
     * Opens network messaging recipients in reverse shuffled order, excluding computers and the local player.
     */
    void openMessagingDialog() {
        ClientSession current = this.session;

        if (current == null || !current.isNetworkGame() || this.consoleView == null) {
            return;
        }

        int local = current.getLocalPlayer();
        List<VisionDialog.Row> players = new ArrayList<>();
        boolean team = current.isTeamGame();

        for (int slot : current.getActivePlayers()) {
            players.add(new VisionDialog.Row(slot, current.getPlayerName(slot)));
        }

        List<VisionDialog.Row> listed = new ArrayList<>();
        List<VisionDialog.Row> shuffled = ShuffleUtils.shuffle(players, current.getGameSeed(), current.getPresentation().getRuntimeRandom());

        for (int index = shuffled.size() - 1; index >= 0; index--) {
            int slot = shuffled.get(index).getPlayer();

            if (current.getPlayerType(slot) == PlayerTypes.COMPUTER || slot == local) {
                continue;
            }

            if (team) {
                int force = current.getPlayerForce(slot);

                listed.add(new VisionDialog.Row(force, current.getForceDisplayName(force)));
            } else {
                listed.add(shuffled.get(index));
            }
        }

        this.consoleView.getMessagingDialog().open(current.getChatRecipient(), listed, current.areAlliancesAllowed(), current.isLocalObserver());
    }

    /**
     * Draws the selected placement footprint and any parent footprint, and supplies the view used for placement snapping.
     *
     * @param camera the session camera
     * @throws NullPointerException if the world was built without game data
     */
    private void drawPlacementGrid(@NotNull ClientCamera camera) {
        PlacementPreview placement = this.requireWorldInterface().getPlacementPreview();

        this.worldInterface.setView((int) Math.floor(camera.screenToWorldX(this.gameViewport.getGameLeft())), (int) Math.floor(camera.getY()),
                                    this.gameViewport.getViewTileWidth(camera.getZoom()), this.gameViewport.getViewTileHeight(camera.getZoom()));

        if (!placement.isVisible()) {
            return;
        }

        ScrUnitTypeView units = this.requireSession().getView().requireGameData().getUnitTypes();

        this.drawPlacementTiles(camera,
                                placement.getTileX() * TerrainTileset.TILE_SIZE, placement.getTileY() * TerrainTileset.TILE_SIZE,
                                units.getPlacementWidth(placement.getType()) / TerrainTileset.TILE_SIZE,
                                units.getPlacementHeight(placement.getType()) / TerrainTileset.TILE_SIZE,
                                placement::getTileCode);

        int parent = placement.getParentType();

        if (parent >= 0) {
            this.drawPlacementTiles(camera,
                                    placement.getParentPixelX(), placement.getParentPixelY(),
                                    units.getPlacementWidth(parent) / TerrainTileset.TILE_SIZE,
                                    units.getPlacementHeight(parent) / TerrainTileset.TILE_SIZE,
                                    placement::getParentTileCode);
        }
    }

    private void drawPlacementTiles(@NotNull ClientCamera camera, int left, int top, int columns, int rows, @NotNull IntBinaryOperator codes) {
        float size = TerrainTileset.TILE_SIZE * camera.getZoom();

        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int color = codes.applyAsInt(row, column) == 0 ? PLACEMENT_VALID_COLOR : PLACEMENT_INVALID_COLOR;

                this.requireInterfaceRenderer().fillRectangle(camera.worldToScreenX(left + column * TerrainTileset.TILE_SIZE),
                                                              camera.worldToScreenY(top + row * TerrainTileset.TILE_SIZE),
                                                              size, size, color);
            }
        }
    }

    /**
     * Skips the running transmissions and waits of the local triggers ({@link TriggerContext#skipTransmissions}): a local input between frames, not
     * recorded. Each {@link TriggerEvents#TRANSMISSION_SKIP} event hides the talking portrait; the transmission timer ends.
     */
    private void skipTransmissions() {
        TriggerContext context = this.requireSession().getView().getTriggerContext();

        // A replay without a human player has no trigger local player (slot 8): there are no local triggers to skip.
        if (context == null || context.getLocalPlayer() < 0 || context.getLocalPlayer() >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return;
        }

        LiveGame live = this.requireSession().getLiveGame();

        context.skipTransmissions(live == null ? 0 : live.getSetup().getCampaignMission());

        TriggerEvents events = context.getEvents();

        for (int index = 0; index < events.getCount(); index++) {
            if (events.getKind(index) == TriggerEvents.TRANSMISSION_SKIP) {
                this.transmissionTimer.cancel();
                this.stopTriggerSounds();

                if (this.consoleView != null) {
                    this.consoleView.endTransmission();
                }
            }
        }
    }

    /**
     * Plays an available map WAV or transmission sound at full volume while sound effects are audible. Missing paths or files are silent.
     *
     * @param path the sound path the trigger action read ({@link TriggerEvents#getPath}), or {@code null}
     */
    private void playTriggerSound(byte @Nullable [] path) {
        if (!this.options.getSoundSettings().isSoundEffectsAudible() || path == null || path.length == 0) {
            return;
        }

        byte[] data = this.requireSession().readMapFile(path);

        if (data == null) {
            return;
        }

        PlayingSound sound = this.requireSession().playMapSound(data, ClientAudio.MAXIMUM_VOLUME);

        if (sound != null) {
            // Finished sounds leave the list, so it holds at most the sounds the map sound channels play.
            this.triggerSounds.removeIf(playing -> !playing.isPlaying());
            this.triggerSounds.add(sound);
        }
    }

    /**
     * Applies window master fading, changed effect and music gains, unit-speech ducking and game subtitles.
     */
    private void updateSound() {
        SoundSettings sound = this.options.getSoundSettings();
        ClientSession game = this.session;
        boolean active = this.isWindowFocused();

        ClientAudio audio = this.clientContext == null ? null : this.clientContext.getAudio();
        float master = this.masterFade.update(UiClockUtils.getHostTimeMilliseconds(), active, sound.isSoundInBackground());
        float effects = sound.getSoundEffectsGain();

        // The bus gains change only when they differ, so they are applied every frame and follow a client audio opened later.
        if (audio != null) {
            audio.getMasterBus().setGain(master);
            audio.applySettings();
        }

        if (effects != this.appliedEffectsGain) {
            this.appliedEffectsGain = effects;

            if (effects == SILENT_GAIN && game != null) {
                this.stopTriggerSounds();
                game.stopSoundEffects();
            }
        }

        if (game != null) {
            game.setSoundEffectsEnabled(sound.isSoundEffectsAudible());
            game.setGameSubtitles(sound.isGameSubtitles());
        }

        if (this.clientContext == null) {
            return;
        }

        Music music = this.clientContext.getMusic();

        music.applySettings();

        boolean muted = game != null && game.getPresentation().getInterfaceLink().getLocalInterface().getUnitSpeechMuted() != 0;

        if (muted != this.musicDucked) {
            this.musicDucked = muted;
            music.setDucked(muted);
        }

        music.update(UiClockUtils.getHostTimeMilliseconds(), game != null && game.isPlaybackRequested(),
                     game == null ? Music.NONE : game.getReplay().getHeader().getCampaignMission());
    }

    /**
     * Applies the configured pointer speed while the visible window is active and reports rejected changes.
     */
    private void updatePointerSpeed() {
        InterfaceSettings settings = this.options.getInterfaceSettings();
        boolean active = !this.options.isHidden() && this.isWindowFocused();

        try {
            this.pointerSpeed.update(active, settings.isMouseSensitivityEnabled(), Math.max(0, settings.getMouseSensitivity()));
        } catch (RuntimeException exception) {
            this.setAssetWarning("Pointer speed: " + exception.getMessage());
        }
    }

    /**
     * Starts the running game music: the music race of the game start ({@link ClientSession#getMusicRace}), plus the client's
     * music rotation outside campaigns (both set by the game start).
     *
     * @param game the running session
     */
    private void startGameMusic(@NotNull ClientSession game) {
        if (this.clientContext == null) {
            return;
        }

        Music music = this.clientContext.getMusic();

        music.setAudioSet(this.options.getAudioSet());
        music.fadeOut();

        if (!game.isPlaybackRequested()) {
            return;
        }

        music.start(Music.getGameTrack(game.getMusicRace(), game.getReplay().getHeader().getCampaignMission(),
                                       this.clientContext.getMusicRotation().getRotation()));
    }

    /**
     * Stops the tracked trigger sounds and clears their handles.
     */
    private void stopTriggerSounds() {
        if (this.session != null) {
            for (PlayingSound sound : this.triggerSounds) {
                this.requireSession().stopMapSound(sound);
            }
        }

        this.triggerSounds.clear();
    }

    /**
     * Presents eligible command errors, using positioned unit speech and alert positions when the event carries a usable unit.
     */
    private void drainCommandErrors() {
        ClientSession live = this.requireSession();
        CommandErrorEvents events = live.getPresentation().getInterfaceLink().getCommandErrorEvents();

        for (int index = 0; index < events.getCount(); index++) {
            CommandErrorEvents.Event event = events.get(index);

            boolean everyPlayer = event.getPlayer() == CommandErrorEvents.EVERY_PLAYER;

            if (!everyPlayer && (!live.isLive() || event.getPlayer() != live.getLocalPlayer())) {
                continue;
            }

            if (event.getTextKey() != null) {
                continue;
            }

            String soundName = switch (event.getSound()) {
                case SUPPLY_LIMIT, ERROR -> "SND_Z_ERROR";
                case INSUFFICIENT_SUPPLY -> "SND_Z_NEED_POWER";
                case INSUFFICIENT_MINERALS -> "SND_Z_NEED_MINERALS";
                case INSUFFICIENT_GAS -> "SND_Z_NEED_GAS";
                case BLOCKED_BUILD_LOCATION -> "SND_Z_CANT_BUILD_THERE";
                case INVALID_BUILD_LOCATION -> "SND_Z_BUILD_INTERRUPTED";
                case GAS_DEPLETED -> "SND_GAS_DEPLETED";
                case ERROR_MESSAGE -> "SND_T_ERROR";
                // NONE is text only; kinds added later by the game module play nothing until mapped here.
                default -> "";
            };
            int race = switch (event.getRace()) {
                case CommandErrorEvents.CONSOLE_RACE -> this.consoleModel == null ? 0 : this.consoleModel.getRace();
                case CommandErrorEvents.NO_RACE -> 0;
                default -> event.getRace();
            };
            UnitPool pool = live.getView().getUnitSystem().getUnitPool();
            Unit unit = event.getPoolIndex() == 0 || pool == null || !pool.isAllocated(event.getPoolIndex()) ? null
                                                                                                             : pool.getUnit(event.getPoolIndex());

            boolean positioned = event.getSound() != CommandErrorEvents.Sound.ERROR && event.getSound() != CommandErrorEvents.Sound.ERROR_MESSAGE &&
                                 event.getSound() != CommandErrorEvents.Sound.NONE;

            if (unit == null || !positioned) {
                this.showCommandError(live, event.getTextIdentifier(), soundName, race);

                continue;
            }

            if (this.consoleView != null) {
                this.consoleView.showStatus(this.consoleView.getText().getString(event.getTextIdentifier()));
            }

            UnitSpeech speech = this.unitSpeech;
            UnitSpeech.Output output = this.unitSpeechOutput;
            int sound = speech == null ? 0 : speech.getSoundIdentifier(soundName);

            if (sound != 0 && output != null && output.play(sound + race, unit, true)) {
                output.talk(unit, speech.getTalkDurationMilliseconds(output, sound + race));
            }

            if (this.viewJumps != null) {
                this.viewJumps.storeAlert(unit.getPositionX(), unit.getPositionY());
            }
        }
    }

    /**
     * Plays eligible local-unit notifications and updates their talking portrait and alert indicators.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private void drainNotifications() {
        ClientSession live = this.requireSession();
        NotificationEvents events = live.getPresentation().getInterfaceLink().getNotificationEvents();
        UnitSpeech speech = this.unitSpeech;
        UnitSpeech.Output output = this.unitSpeechOutput;
        UnitPool pool = live.getView().getUnitSystem().getUnitPool();
        SoundSettings sound = this.options.getSoundSettings();

        if (speech == null || output == null || pool == null) {
            return;
        }

        ScrUnitTypeView units = live.getView().requireGameData().getUnitTypes();

        for (int index = 0; index < events.getCount(); index++) {
            NotificationEvents.Event event = events.get(index);

            if (event.getOwner() != live.getLocalPlayer() || !pool.isAllocated(event.getPoolIndex())) {
                continue;
            }

            Unit unit = pool.getUnit(event.getPoolIndex());
            int type = unit.getUnitId();
            int race = UnitRacesUtils.getDisplayRace(units, type);
            int talkSound;

            switch (event.getKind()) {
                case UNIT_READY -> {
                    if (this.isUnitSpeechMuted() || type == ScrUnitIds.PROTOSS_INTERCEPTOR || type == ScrUnitIds.PROTOSS_SCARAB) {
                        continue;
                    }

                    talkSound = type < READY_SOUND_TYPES ? units.getReadySound(type) : 0;

                    if (talkSound == 0) {
                        continue;
                    }

                    output.play(talkSound, unit, sound.isUnitSpeech());
                }
                case WORK_DONE -> {
                    if (this.isUnitSpeechMuted()) {
                        continue;
                    }

                    if (race == ZERG_RACE) {
                        talkSound = speech.getSoundIdentifier("SND_Z_WORK_DONE");
                        output.play(talkSound, unit, sound.isUnitSpeech());
                    } else if (race == TERRAN_RACE) {
                        boolean worker = type == ScrUnitIds.TERRAN_SCV;

                        talkSound = speech.getSoundIdentifier(worker ? "SND_T_WORK_DONE" : "SND_T_ADDON_COMPLETE");
                        output.play(talkSound, unit, sound.isUnitSpeech());
                    } else {
                        talkSound = speech.getSoundIdentifier("SND_Z_WORK_DONE");
                    }
                }
                case RESEARCH_COMPLETE, UPGRADE_COMPLETE -> {
                    String name = event.getKind() == NotificationEvents.Kind.RESEARCH_COMPLETE ? "SND_Z_RESEARCH_COMPLETE" : "SND_Z_UPGRADE_COMPLETE";

                    talkSound = speech.getSoundIdentifier(name) + race;
                    output.play(talkSound, null, true);
                }
                case ADDON_DETACHED -> {
                    talkSound = speech.getSoundIdentifier("SND_T_DETACH_ADDON");
                    output.play(talkSound, unit, sound.isBuildingSounds());
                }
                case LANDING_INTERRUPTED -> {
                    talkSound = speech.getSoundIdentifier("SND_Z_LANDING_INTERRUPTED") + race;
                    output.play(talkSound, unit, true);
                }
                default -> {
                    continue;
                }
            }

            this.talkWithAlert(unit, speech.getTalkDurationMilliseconds(output, talkSound));
        }
    }

    /**
     * Updates a talking portrait and its camera alert, blink and minimap ping unless a transmission is armed.
     *
     * @param unit                 the talking unit
     * @param durationMilliseconds the talking duration
     */
    private void talkWithAlert(@NotNull Unit unit, long durationMilliseconds) {
        if (this.transmissionTimer.isArmed() || this.consoleView == null) {
            return;
        }

        if (this.unitSpeechOutput != null) {
            this.unitSpeechOutput.talk(unit, durationMilliseconds);
        }

        if (this.viewJumps != null) {
            this.viewJumps.storeAlert(unit.getPositionX(), unit.getPositionY());
        }

        this.consoleView.blinkUnit(unit.getIndex(), ALERT_BLINK_COUNT, NOTIFICATION_COLOR_INDEX);
        this.consoleView.addMinimapPing(unit.getPositionX(), unit.getPositionY(), NOTIFICATION_COLOR_INDEX, false);
    }

    private void showCommandError(@NotNull ClientSession live, int statTextIdentifier, @NotNull String soundName, int race) {
        if (this.consoleView != null && statTextIdentifier != 0) {
            this.consoleView.showStatus(this.consoleView.getText().getString(statTextIdentifier));
        }

        SoundTable sounds = this.soundTable;
        int sound = sounds == null ? 0 : sounds.getSoundIdentifier(soundName);

        if (sound != 0) {
            live.playInterfaceSound(sound + race, -1, ClientAudio.MAXIMUM_VOLUME);
        }
    }

    private void tickCenterView() {
        GameMap map = this.requireSession().getView().getMap();

        this.centerViewScroll.tick(GameScreenCamera.ofWholeView(this.requireSession().getCamera(), this.gameViewport,
                                                                map.getWidth() * TerrainTileset.TILE_SIZE, map.getHeight() * TerrainTileset.TILE_SIZE));
    }

    /**
     * Displays in-bounds pings for all replay senders or allied live senders, using the corresponding player or alert colors.
     */
    private void drainMinimapPings() {
        List<int[]> pings = this.requireSession().takeMinimapPings();

        if (pings.isEmpty() || this.consoleView == null) {
            return;
        }

        ScrGameView state = this.requireSession().getView();
        GameMap map = state.getMap();

        for (int[] ping : pings) {
            int sender = ping[PING_SENDER_INDEX];
            int x = ping[PING_X_INDEX];
            int y = ping[PING_Y_INDEX];

            if (x >= map.getWidth() * TerrainTileset.TILE_SIZE || y >= map.getHeight() * TerrainTileset.TILE_SIZE) {
                continue;
            }

            if (this.consoleReplayControls) {
                this.consoleView.addMinimapPing(x, y, sender, true);

                continue;
            }

            PlayerRelations relations = state.getPlayerTable().getPlayerRelations();
            int local = this.consolePlayer;

            // An observer holds no player record, so no player is its ally.
            if (relations == null || local >= PlayerTable.PLAYER_SLOTS || relations.getAlliance(sender, local) == 0) {
                continue;
            }

            this.consoleView.addMinimapPing(x, y, sender == local ? ConsoleView.ALERT_PING_COLOR : ConsoleView.ALLY_PING_COLOR, false);
        }
    }

    private void drainTriggerEvents() {
        TriggerContext context = this.requireSession().getView().getTriggerContext();

        if (context == null) {
            return;
        }

        context.getEvents().present(this::presentTriggerEvent);
    }

    /**
     * Presents one trigger event for the local player: camera moves, minimap pings, sounds, transmissions, the EUD failure notice and the pause
     * cursor; other kinds are normal no-ops.
     *
     * @param kind   the trigger event kind
     * @param first  the first event operand
     * @param second the second event operand
     * @param third  the third event operand
     * @param fourth the fourth event operand
     * @param path   the sound path of a sound event, {@code null} for other events
     */
    private void presentTriggerEvent(int kind, int first, int second, int third, int fourth, byte @Nullable [] path) {
        switch (kind) {
            case TriggerEvents.CENTER_VIEW -> {
                if (third == SMOOTH_CENTER_VIEW_FLAG) {
                    // The local view holds the game screen size supplied before the step (supplyLocalView).
                    LocalView view = this.requireSession().getPresentation().getInterfaceLink().getLocalView();
                    GameMap map = this.requireSession().getView().getMap();
                    int x = view.calculateScrollTargetX(first, map.getWidth() * TerrainTileset.TILE_SIZE);
                    int y = view.calculateScrollTargetY(second, map.getHeight() * TerrainTileset.TILE_SIZE);
                    ClientSession live = this.requireSession();

                    this.centerViewScroll.start(x, y, live::completeCenterView);
                } else {
                    ClientCamera camera = this.requireSession().getCamera();
                    float offset = ((this.gameViewport.getGameLeft() + this.gameViewport.getGameRight()) / CENTER_DIVISOR -
                                    this.viewWidth / CENTER_FLOAT_DIVISOR) / camera.getZoom();

                    camera.centerOn(first - offset, second);
                }
            }
            case TriggerEvents.CENTER_VIEW_FINISH -> this.centerViewScroll.finish();
            case TriggerEvents.MINIMAP_PING -> {
                if (this.consoleView != null) {
                    this.consoleView.addMinimapPing(first, second, ConsoleView.ALERT_PING_COLOR, false);
                }
            }
            case TriggerEvents.WAV -> this.playTriggerSound(path);
            case TriggerEvents.TRANSMISSION -> {
                this.transmissionTimer.arm((int) UiClockUtils.getHostTimeMilliseconds(), fourth);

                if (this.consoleView != null) {
                    this.consoleView.showTransmission(first, fourth, UiClockUtils.getHostTimeMilliseconds());

                    if (second != TriggerEvents.NO_POSITION) {
                        this.consoleView.addMinimapPing(second, third, ConsoleView.ALERT_PING_COLOR, false);

                        if (this.viewJumps != null) {
                            this.viewJumps.storeAlert(second, third);
                        }
                    }
                }
            }
            case TriggerEvents.EUD_FAILURE_NOTICE -> this.showEudFailureNotice(first != 0, second);
            case TriggerEvents.GAME_PAUSE -> {
                if (first == 0) {
                    this.moveMouseToSurface(this.gameViewport.getInterfaceWidth() / CENTER_DIVISOR, GameViewport.INTERFACE_HEIGHT / CENTER_DIVISOR);
                }
            }
            default -> {
            }
        }
    }

    /**
     * Cancels placement and targeting, closes game popups, and shows the EUD failure notice and code. The failure ended the game, so the session ends
     * once the notice closes.
     *
     * @param unsupportedAtAll whether the failed region carries the unsupported-region flag
     * @param code             the failure value displayed by the notice
     */
    private void showEudFailureNotice(boolean unsupportedAtAll, int code) {
        this.sessionEndsWithNotice = true;

        if (this.worldInterface != null) {
            this.worldInterface.cancelMode();
        }

        if (this.gameMenu != null) {
            this.gameMenu.close();
        }

        if (this.clientContext != null && this.openGlueOverGame()) {
            this.glueState.showFatal(unsupportedAtAll ? EUD_UNSUPPORTED_AT_ALL_TEXT : EUD_UNSUPPORTED_TEXT, String.format(EUD_ERROR_FORMAT, code),
                                     this.clientContext.getGameFonts());
        }
    }

    /**
     * Connects the unit responses of a live game: selection and acknowledgement sounds through the session's interface sound output, and the talking
     * portrait of the speaking unit.
     *
     * @param live the live session
     */
    private void installUnitSpeech(@NotNull ClientSession live) {
        if (this.soundTable == null) {
            this.unitSpeech = null;

            return;
        }

        this.unitSpeech = new UnitSpeech(this.soundTable, this.options.getSoundSettings());

        UnitSpeech speech = this.unitSpeech;
        UnitSpeech.Output output = new UnitSpeech.Output() {
            /**
             * @param sound the sound index
             * @return the decoded length in milliseconds, {@code 0} if unknown
             */
            @Override
            public int getLength(int sound) {
                return live.getSoundLength(sound);
            }

            /**
             * Requests an interface sound when enabled.
             *
             * @param sound   the sound index
             * @param unit    the speaking unit, or {@code null}
             * @param enabled whether the caller enables this response
             * @return {@code true} if the sound started, {@code false} otherwise
             */
            @Override
            public boolean play(int sound, @Nullable Unit unit, boolean enabled) {
                return enabled && live.playInterfaceSound(sound, unit == null ? -1 : unit.getUnitId(), ClientAudio.MAXIMUM_VOLUME);
            }

            /**
             * Requests a talking portrait for the unit.
             *
             * @param unit                 the speaking unit
             * @param durationMilliseconds the talk duration in milliseconds
             */
            @Override
            public void talk(@NotNull Unit unit, long durationMilliseconds) {
                if (Main.this.consoleView != null) {
                    Main.this.consoleView.showTransmission(unit.getUnitId(), durationMilliseconds, UiClockUtils.getHostTimeMilliseconds());
                }
            }

            /**
             * Receives the single-player critter effect callback.
             *
             * @param unit the critter
             */
            @Override
            public void explodeCritter(@NotNull Unit unit) {
                LOGGER.debug("UI CRITTER: " + live.killCritterFromInterface(unit.getIndex()));
            }
        };

        this.requireWorldInterface().setResponses(new WorldInterface.Responses() {
            /**
             * Responds to a new selection made by a click, a box or a type selection (with sound).
             *
             * @param selection the resulting selection in order
             */
            @Override
            public void respondToSelection(@NotNull List<Unit> selection) {
                speech.requestSelectionResponse(live.getView(), live.getPresentation(),
                                                UnitSpeech.findSelectionSpeaker(live.getView(), selection),
                                                live.getLocalPlayer(),
                                                false, !live.isNetworkGame(),
                                                output);
            }

            /**
             * Responds to an order given to the active unit: a right click, a targeted order or Return Cargo.
             *
             * @param active the active unit
             */
            @Override
            public void respondToOrderAcknowledgement(@NotNull Unit active) {
                speech.requestOrderAcknowledgment(live.getView(), live.getPresentation(), active, live.getLocalPlayer(), false, output);
            }
        });
        this.unitSpeechOutput = output;
    }

    /**
     * Shows local under-attack indicators and applies the cooldown, screen margin and nearby-unit alert marks.
     */
    private void drainAttackAlerts() {
        ScrGameView state = this.requireSession().getView();
        AttackAlertEvents alerts = this.requireSession().getPresentation().getInterfaceLink().getAttackAlertEvents();
        long frame = this.requireSession().getLastCompletedFrame();
        int local = this.requireSession().getLocalPlayer();

        for (int index = 0; index < alerts.getCount(); index++) {
            if (alerts.getOwner(index) != local || this.consoleView == null) {
                continue;
            }

            int x = alerts.getPositionX(index);
            int y = alerts.getPositionY(index);
            int poolIndex = alerts.getPoolIndex(index);

            this.consoleView.blinkUnit(poolIndex, ATTACK_UNIT_BLINK_COUNT, ATTACK_UNIT_BLINK_COLOR);

            UnitSpeech speech = this.unitSpeech;
            UnitPool unitPool = state.getUnitSystem().getUnitPool();
            Unit damaged = unitPool == null ? null : unitPool.getUnit(poolIndex);

            if ((speech != null ? speech.isAlertCoolingDown() : frame < this.alertCooldownEnd) || damaged != null && damaged.getAlertMark() != 0) {
                continue;
            }

            ClientCamera camera = this.requireSession().getCamera();
            float left = camera.screenToWorldX(this.gameViewport.getGameLeft()) - ATTACK_ALERT_CAMERA_MARGIN;
            float right = camera.screenToWorldX(this.gameViewport.getGameRight()) + ATTACK_ALERT_CAMERA_MARGIN;
            float top = camera.getY() - ATTACK_ALERT_CAMERA_MARGIN;
            float bottom = camera.screenToWorldY(this.viewHeight) + ATTACK_ALERT_CAMERA_MARGIN;

            if (x >= left && x < right && y >= top && y < bottom) {
                continue;
            }

            this.alertCooldownEnd = frame + ATTACK_ALERT_COOLDOWN_FRAMES;
            this.requireSession().getPresentation().getLocalInterfaceEffects().markAttackAlertArea(x, y);

            this.consoleView.addMinimapPing(x, y, ConsoleView.ATTACK_ALERT_PING_COLOR, false);
            this.consoleView.blinkUnit(poolIndex, ATTACK_AREA_BLINK_COUNT, ATTACK_AREA_BLINK_COLOR);

            if (this.viewJumps != null) {
                this.viewJumps.storeAlert(x, y);
            }

            if (speech != null && this.unitSpeechOutput != null && damaged != null) {
                speech.requestAttackAlert(damaged, this.consoleModel == null ? 0 : this.consoleModel.getRace(), this.unitSpeechOutput);
            }
        }
    }

    /**
     * Handles a key for the in-game popups of live play: F10 opens or closes the game menu; Esc takes the page's default "Previous" /
     * "Return to Game" button; a letter presses the button whose hotkey it is. Enter or Esc takes the default button of the victory / defeat box
     * and OK on the score screen.
     *
     * @param key the key
     * @return {@code true} if the key was consumed, {@code false} otherwise
     */
    boolean handleGameMenuKey(@NotNull Key key) {
        if (this.session == null) {
            return false;
        }

        if (this.endPhase == END_BOX) {
            // The box's procedure takes -3 (Esc) and -2 (Enter, the OK button) (ScrDialog.escape / enter chain).
            OutcomeBox box = this.outcomeBox;

            if (box != null && key == Key.ESCAPE) {
                box.getDialog().escape(this::pressOutcomeBox);
            } else if (box != null && (key == Key.ENTER || key == Key.KEYPAD_ENTER)) {
                box.getDialog().enter(this::pressOutcomeBox);
            }

            return true;
        }

        GameMenu menu = this.gameMenu;

        if (menu == null) {
            return false;
        }

        if (key == Key.F10) {
            menu.toggle();

            if (menu.isOpen()) {
                this.stopTriggerSounds();
            }

            return true;
        }

        // The menu accelerators (Alt+M, Alt+H / F11, Ctrl/Alt+X, Ctrl/Alt+Q, Alt+O) are table entries of the GameAcceleratorHandler.
        if (!menu.isOpen()) {
            return false;
        }

        if (key == Key.ESCAPE) {
            this.applyMenuResult(menu.escape());
        } else if (key == Key.ENTER || key == Key.KEYPAD_ENTER) {
            this.applyMenuResult(menu.enter());
        } else {
            this.pressPopupListKey(key);
        }

        return true;
    }

    /**
     * Applies a typed character to the open game-menu page after its character-domain checks.
     *
     * @param codePoint the typed character
     * @return {@code true} if the open game-menu page accepted the character domain, {@code false} otherwise
     */
    boolean pressGameMenuCharacter(int codePoint) {
        GameMenu menu = this.gameMenu;

        if (this.session == null || menu == null || !menu.isOpen() || this.endPhase == END_BOX ||
            this.consoleView == null || !Character.isBmpCodePoint(codePoint)) {
            return false;
        }

        char key = codePoint < ASCII_CHARACTER_LIMIT ? Character.toLowerCase((char) codePoint) : (char) codePoint;

        this.applyMenuResult(menu.pressHotkey(key, this.consoleView::getControlString));

        return true;
    }

    void applyMenuResult(GameMenu.@NotNull Result result) {
        switch (result) {
            case QUIT_MISSION -> {
                if (this.session != null && !this.requireSession().isLive()) {
                    this.outcomeVictory = this.isReplayLocalPlayerVictorious();
                }

                if (this.session != null && (this.requireSession().isNetworkGame() || !this.requireSession().isLive())) {
                    this.showScoreScreen();
                } else {
                    this.endWithoutScore();
                }
            }
            case EXIT_PROGRAM -> this.endLiveGame();
            case OPTIONS -> this.openOptionsOverGame();
            // Pause or resume through the turn buffer; the menu page stays open.
            case PAUSE_GAME -> {
                if (this.session != null) {
                    this.requireSession().requestPause();
                }
            }
            case RESUME_GAME -> {
                if (this.session != null) {
                    this.requireSession().requestResume();
                }
            }
            case KEYSTROKE_HELP -> this.fillKeystrokeHelp();
            case TIPS -> this.showTips(false);
            case NEXT_TIP -> this.stepTip(1);
            case PREVIOUS_TIP -> this.stepTip(-1);
            case SAVE_GAME -> this.openSaveGameDialog(true);
            case LOAD_GAME -> this.openLoadGameDialog(true);
            case TIPS_AT_STARTUP -> {
                ScrDialog dialog = this.gameMenu == null ? null : this.gameMenu.getDialog();
                ScrControl box = dialog == null ? null : dialog.findControl(GameMenu.TIPS_AT_STARTUP_BOX);
                GameTips tips = this.gameTips;

                if (dialog != null && tips != null) {
                    tips.setShowAtStartup(box != null && box.isChecked());
                    this.glueState.storeTips(tips.getNumber(), tips.isShowAtStartup());
                }
            }
            default -> {
            }
        }
    }

    /**
     * Loads the localized keystroke-help rows and installs their popup list.
     */
    private void fillKeystrokeHelp() {
        GameMenu menu = this.gameMenu;
        ScrDialog dialog = menu == null ? null : menu.getDialog();
        DialogControl control = dialog == null ? null : ScrDialogControlUtils.findControl(dialog.getDefinition(), KeystrokeHelp.LIST);

        if (control == null || this.session == null || this.consoleView == null) {
            return;
        }

        GluText table;

        try {
            table = GluText.load(this.requireSession().getStorage(), this.getTextLocale(), KeystrokeHelp.TABLE);
        } catch (IOException exception) {
            this.setAssetWarning("Keystroke help: " + exception.getMessage());

            return;
        }

        // Replace the message-history list so only one list popup remains open.
        this.chatHistory.close();

        ListBox list = this.consoleView.openPopupList(dialog, control);

        list.setItems(new KeystrokeHelp(table).getRows());
        this.keystrokeHelpList = list;
    }

    /**
     * Opens startup tips for eligible unsaved single-player live games when the option is enabled.
     */
    private void openStartupTips() {
        ClientSession current = this.session;
        GameMenu menu = this.gameMenu;

        if (current == null || !current.isLive() || current.isNetworkGame() || current.isSavedGame() || menu == null ||
            !this.options.getInterfaceSettings().isTipsShown()) {
            return;
        }

        menu.openPage(GameMenu.Page.TIPS);
        this.showTips(true);
    }

    /**
     * Opens the applicable game tip and updates its startup checkbox.
     *
     * @param missionStart whether the tips open at the mission start
     */
    private void showTips(boolean missionStart) {
        if (this.session == null) {
            return;
        }

        GameTips tips = this.gameTips;

        if (tips == null) {
            InterfaceSettings settings = this.options.getInterfaceSettings();

            tips          = new GameTips(this.requireSession().getReplay().getHeader().getEngine() != 0, settings.getTipNumber(), settings.isTipsShown());
            this.gameTips = tips;
        }

        int number = tips.open(missionStart, this.requireSession().getReplay().getHeader().getCampaignMission());
        ScrDialog dialog = this.gameMenu == null ? null : this.gameMenu.getDialog();
        ScrControl box = dialog == null ? null : dialog.findControl(GameMenu.TIPS_AT_STARTUP_BOX);

        if (box != null) {
            box.setChecked(tips.isShowAtStartup());
        }

        this.setTipText(number);
    }

    /**
     * Displays the next or previous game tip.
     *
     * @param delta the tip step, positive for the next tip and negative for the previous one
     */
    private void stepTip(int delta) {
        GameTips tips = this.gameTips;

        if (tips != null) {
            this.setTipText(tips.step(delta));
        }
    }

    /**
     * Sets the numbered tip, selects a fitting font and persists its number and startup choice.
     *
     * @param number the tip number
     */
    private void setTipText(int number) {
        GameMenu menu = this.gameMenu;
        ScrDialog dialog = menu == null ? null : menu.getDialog();
        DialogControl control = dialog == null ? null : ScrDialogControlUtils.findControl(dialog.getDefinition(), GameMenu.TIP_TEXT);

        if (control == null || this.session == null || this.consoleView == null) {
            return;
        }

        String text;

        try {
            text = GluText.load(this.requireSession().getStorage(), this.getTextLocale(), TIPS_TABLE).getByNumber(number);
        } catch (IOException exception) {
            this.setAssetWarning("Tips: " + exception.getMessage());
            text = "";
        }

        ScrControl tip = dialog.requireControl(control);

        tip.setReplacedText(text);

        boolean fits = this.consoleView.isPopupTextFitting(control, text, InterfaceFonts.BIG);

        tip.setFontSlot(fits ? InterfaceFonts.BIG : InterfaceFonts.NORMAL);

        GameTips tips = this.gameTips;

        if (tips != null) {
            this.glueState.storeTips(tips.getNumber(), tips.isShowAtStartup());
        }
    }

    /**
     * Creates a {@link GameMenu.Context} instance that reads the running live game.
     *
     * @return the created {@link GameMenu.Context} instance
     */
    private GameMenu.@NotNull Context createMenuContext() {
        return new GameMenu.Context() {
            /**
             * @return {@code true} if the game is paused, {@code false} otherwise
             */
            @Override
            public boolean isPaused() {
                return Main.this.session != null && Main.this.requireSession().isGamePaused();
            }

            /**
             * @return {@code true} if the local player's record is a human (type {@code 2}), {@code false} otherwise
             */
            @Override
            public boolean isLocalPlayerHuman() {
                LiveGame live = this.getLiveGame();

                return live != null && !live.isLocalObserver();
            }

            /**
             * @return {@code true} if the local player is the first active player of the game, {@code false} otherwise
             */
            @Override
            public boolean isLocalPlayerFirst() {
                LiveGame live = this.getLiveGame();

                return live != null && live.isLocalPlayerFirst();
            }

            /**
             * @return {@code true} if the local player is an observer, {@code false} otherwise
             */
            @Override
            public boolean isLocalPlayerObserver() {
                LiveGame live = this.getLiveGame();

                return live != null && live.isLocalObserver();
            }

            /**
             * @return {@code true} if the local player has won (game of the local player is {@code 3}), {@code false} otherwise
             */
            @Override
            public boolean isLocalPlayerVictorious() {
                LiveGame live = this.getLiveGame();

                return live != null && live.getLocalOutcome() == TriggerPlayerResults.VICTORY;
            }

            /**
             * @return {@code true} if the game page permits Save Game, {@code false} otherwise
             */
            @Override
            public boolean isSaveGameShown() {
                return Main.this.session != null && Main.this.requireSession().isSaveGameShown();
            }

            /**
             * @return {@code true} if the local player may pause (a human with pauses left), {@code false} otherwise
             */
            @Override
            public boolean canLocalPlayerPause() {
                LiveGame live = this.getLiveGame();

                return live != null && live.canPause(live.getLocalPlayer());
            }

            private @Nullable LiveGame getLiveGame() {
                return Main.this.session == null ? null : Main.this.requireSession().getLiveGame();
            }
        };
    }

    /**
     * Sends a staged network latency after popups close, presents executed notices and applies the pause palette.
     */
    private void showPauseNotices() {
        if (this.session == null) {
            return;
        }

        if (this.pendingLatency != NO_LATENCY && this.getGluePopupLayers().isEmpty()) {
            if (this.requireSession().isLive() && this.requireSession().isNetworkGame()) {
                this.requireSession().requestLatency(this.pendingLatency);
            }

            this.pendingLatency = NO_LATENCY;
        }

        // An executed Latency command names its sender (strFIRSTLATENCY_s, _s-1, _s-2) unless it was silent.
        for (int[] notice : this.requireSession().takeLatencyNotices()) {
            if (this.consoleView == null) {
                continue;
            }

            String name = notice[LATENCY_NOTICE_SLOT_INDEX] < 0 ? "" : this.getPlayerName(notice[LATENCY_NOTICE_SLOT_INDEX]);
            String format = this.consoleView.getNetworkText(LATENCY_MESSAGES.get(notice[LATENCY_NOTICE_LATENCY_INDEX]));
            String text = String.format(Locale.ROOT, format, name);

            this.requireSession().getPresentation().getInterfaceLink().getMessageLines().writeGameMessage(text.getBytes(StandardCharsets.UTF_8), 0);
        }

        for (PauseNotice notice : this.requireSession().takePauseNotices()) {
            if (this.renderer != null) {
                this.renderer.setPaletteGrayscale(notice.isPaused());
            }

            if (this.consoleView == null) {
                continue;
            }

            String name = notice.getSlot() < 0 ? "" : this.getPlayerName(notice.getSlot());
            String text = notice.isPaused() ? String.format(Locale.ROOT, this.consoleView.getNetworkText(PAUSED_TEXT), name, notice.getPausesLeft())
                                            : String.format(Locale.ROOT, this.consoleView.getNetworkText(RESUMED_TEXT), name);

            this.requireSession().getPresentation().getInterfaceLink().getMessageLines().writeGameMessage(text.getBytes(StandardCharsets.UTF_8), 0);
        }
    }

    /**
     * Opens the running network game chat history in its popup list.
     */
    void openChatHistory() {
        ClientSession current = this.session;
        GameMenu menu = this.gameMenu;

        if (current == null || !current.isLive() || !current.isNetworkGame() || menu == null || this.consoleView == null ||
            this.endPhase != END_NONE) {
            return;
        }

        menu.openPage(GameMenu.Page.CHAT_HISTORY);

        ScrDialog dialog = menu.getDialog();
        DialogControl control = dialog == null ? null : ScrDialogControlUtils.findControl(dialog.getDefinition(), CHAT_HISTORY_LIST);

        if (control == null) {
            return;
        }

        ListBox list = this.consoleView.openPopupList(dialog, control);

        this.keystrokeHelpList = null;
        this.chatHistory.open(list, text -> this.requireConsoleView().measurePopupText(control, text), this.consoleView.getPopupListRows(list));
    }

    /**
     * Routes a supported navigation key to the active history or help list.
     *
     * @param key the key
     */
    private void pressPopupListKey(@NotNull Key key) {
        ListBox list = this.chatHistory.getList() != null ? this.chatHistory.getList() : this.keystrokeHelpList;

        if (list == null || this.consoleView == null) {
            return;
        }

        DialogKey move = switch (key) {
            case PAGE_UP, PAGE_DOWN, END, HOME, UP, DOWN -> DialogKey.find(key);
            default -> null;
        };

        if (move != null) {
            this.consoleView.pressPopupListKey(list, move);
        }
    }

    /**
     * Scrolls the open chat-history list using a wheel notch.
     *
     * @param down whether the notch scrolls down
     * @return {@code true} if the open chat-history list was scrolled, {@code false} otherwise
     */
    boolean scrollChatHistory(boolean down) {
        ListBox list = this.chatHistory.getList();

        if (list == null || this.consoleView == null) {
            return false;
        }

        this.consoleView.scrollPopupList(list, true, down);

        return true;
    }

    /**
     * Opens a game-menu page directly and stops the running trigger sounds.
     *
     * @param page the game-menu page
     */
    void openGameMenuPage(GameMenu.@NotNull Page page) {
        GameMenu menu = this.gameMenu;

        if (this.session == null || menu == null || this.endPhase != END_NONE) {
            return;
        }

        menu.openPage(page);
        this.stopTriggerSounds();
    }

    /**
     * Opens an eligible live save dialog over the game and reopens its menu after cancellation when requested.
     *
     * @param fromMenu whether the game menu opened the dialog
     */
    void openSaveGameDialog(boolean fromMenu) {
        ClientSession game = this.session;
        Path directory = game == null ? null : game.getSaveDirectory();

        if (game == null || directory == null || this.endPhase != END_NONE || !game.canOpenSaveDialog(UiClockUtils.getHostTimeMilliseconds())) {
            return;
        }

        String extension = game.getSaveGameKind().getExtension();

        if (this.gameMenu != null) {
            this.gameMenu.close();
        }

        if (!this.openGlueOverGame()) {
            return;
        }

        this.glueState.showSaveGame(new GlueSavePopup.Target() {
            /**
             * @return the folder the dialog lists and writes into
             */
            @Override
            public @NotNull Path getDirectory() {
                return directory;
            }

            /**
             * @return the file name extension with its dot
             */
            @Override
            public @NotNull String getExtension() {
                return extension;
            }

            /**
             * @return {@code true} if the dialog saves the score screen's replay, {@code false} otherwise
             */
            @Override
            public boolean isReplay() {
                return false;
            }

            /**
             * Saves under a name: writes the replay into {@link #getDirectory()}, or sends the Save Game command whose execution writes the game.
             *
             * @param name the name the dialog read from its name box: the bytes cut to a saved game name buffer,
             *             without trailing spaces and without terminator
             * @return {@code true} if the save was written or sent, {@code false} otherwise
             */
            @Override
            public boolean save(byte @NotNull [] name) {
                game.requestSaveGame(name, UiClockUtils.getHostTimeMilliseconds());

                return true;
            }
        }, this.consoleModel == null ? 0 : this.consoleModel.getRace(), saved -> this.returnToGameMenu(fromMenu && !saved));
    }

    /**
     * Opens an eligible saved-game load dialog over the game and reopens its menu after cancellation when requested.
     *
     * @param fromMenu whether the game menu opened the dialog
     */
    void openLoadGameDialog(boolean fromMenu) {
        ClientSession game = this.session;
        Path directory = game == null ? null : game.getSaveDirectory();

        if (game == null || directory == null || this.endPhase != END_NONE || !game.canOpenLoadDialog()) {
            return;
        }

        String extension = game.getSaveGameKind().getExtension();

        if (this.gameMenu != null) {
            this.gameMenu.close();
        }

        if (!this.openGlueOverGame()) {
            return;
        }

        this.glueState.showLoadGame(new GlueLoadPopup.Source() {
            /**
             * @return the folder the dialog lists
             */
            @Override
            public @NotNull Path getDirectory() {
                return directory;
            }

            /**
             * @return the file name extension with its dot
             */
            @Override
            public @NotNull String getExtension() {
                return extension;
            }

            /**
             * Loads a saved game: sends the Load Game command in a game, or starts the game from the glue.
             *
             * @param file the picked file, in {@link #getDirectory()}
             */
            @Override
            public void load(@NotNull Path file) {
                game.requestLoadGame(file);
            }
        }, this.consoleModel == null ? 0 : this.consoleModel.getRace(), loaded -> this.returnToGameMenu(fromMenu && !loaded));
    }

    /**
     * Pans a restored session to its saved screen position.
     *
     * @param restored the restored session
     */
    private void showSavedScreen(@NotNull ClientSession restored) {
        LocalView view = restored.getPresentation().getInterfaceLink().getLocalView();
        this.createPlayViewCamera(restored).moveTo(view.getScreenX(), view.getScreenY());
    }

    /**
     * Presents save failures and stages a requested saved-game load after saving the current live replay.
     *
     * @return {@code true} if a saved-game load was staged, {@code false} otherwise
     */
    private boolean updateSavedGameCommands() {
        ClientSession game = this.session;

        if (game == null) {
            return false;
        }

        String failure = game.takeSaveFailure();

        if (failure != null && this.consoleView != null) {
            game.getPresentation().getInterfaceLink().getMessageLines().writeGameMessage(
                    this.consoleView.getNetworkText(failure).getBytes(StandardCharsets.UTF_8), 0
            );
        }

        Path load = game.takeRequestedLoad();

        if (load == null) {
            return false;
        }

        GlueGameRequest played = this.sessionGlueRequest;
        String player = played != null ? played.getPlayerName() : game.getLiveGame() == null ? "" : game.getLiveGame().getSetup().getHumanName();
        boolean expansion = game.getLiveGame() != null && game.getLiveGame().getSetup().isExpansion();
        int mission = game.getLiveGame() == null ? 0 : game.getLiveGame().getSetup().getCampaignMission();

        LOGGER.info("LOAD GAME: " + load);

        if (this.gameMenu != null) {
            this.gameMenu.close();
        }

        this.stopTriggerSounds();
        this.replayWriter.saveAtEnd(this.session);

        GlueGameRequest request = GlueGameRequest.createSavedGame(load, player, expansion, mission);

        this.glueRequest        = request;
        this.sessionGlueRequest = request;
        this.pendingReplay      = load;
        this.playing            = false;

        return true;
    }

    /**
     * Reopens the game page when requested and the running game still allows it.
     *
     * @param reopen whether the game page is requested
     */
    private void returnToGameMenu(boolean reopen) {
        if (reopen && this.session != null && this.gameMenu != null && this.endPhase == END_NONE) {
            this.gameMenu.openPage(GameMenu.Page.GAME);
        }
    }

    /**
     * Closes the game menu and opens the glue options popup over the running game.
     */
    void openOptionsOverGame() {
        if (this.session == null || this.endPhase != END_NONE) {
            return;
        }

        if (this.gameMenu != null) {
            this.gameMenu.close();
        }

        if (this.openGlueOverGame()) {
            this.glueState.showOptions();
        }
    }

    /**
     * Applies an edit operation of the popup accelerator table to the focused edit of the open popup: the menus' popup over the game; the in-game
     * popups (game menu pages, outcome box) have no edit.
     *
     * @param operation the edit operation
     */
    void applyPopupEditOperation(@NotNull TextEditOperation operation) {
        if (this.isGluePopupOverGame()) {
            this.glueState.applyEditOperation(operation);
        }
    }

    /**
     * Toggles the persisted pointer-confinement setting while the window is not fullscreen.
     */
    void toggleMouseConfine() {
        if (this.isFullScreen()) {
            return;
        }

        LOGGER.debug("UI MOUSE CONFINE: " + (this.glueState.toggleMouseConfine() ? "on" : "off"));
    }

    /**
     * Toggles the persisted turn-rate display during a network game.
     */
    void toggleShowTurnRate() {
        if (this.session == null || !this.requireSession().isNetworkGame()) {
            return;
        }

        LOGGER.debug("UI SHOW TURN RATE: " + (this.glueState.toggleShowTurnRate() ? "on" : "off"));
    }

    /**
     * Toggles the persisted frame-rate display.
     */
    void toggleShowFrameRate() {
        LOGGER.debug("UI SHOW FPS: " + (this.glueState.toggleShowFrameRate() ? "on" : "off"));
    }

    /**
     * Toggles the persisted music setting.
     */
    void toggleMusicSetting() {
        LOGGER.debug("UI MUSIC: " + (this.glueState.toggleMusicEnabled() ? "on" : "off"));
    }

    /**
     * Toggles the persisted sound-effect setting.
     */
    void toggleSoundEffectsSetting() {
        LOGGER.debug("UI SOUND EFFECTS: " + (this.glueState.toggleSoundEffectsEnabled() ? "on" : "off"));
    }

    /**
     * Resets the camera zoom around the center of the game view.
     */
    void resetZoom() {
        if (this.session == null) {
            return;
        }

        ClientCamera camera = this.requireSession().getCamera();

        camera.zoomAt(1f / camera.getZoom(),
                      (this.gameViewport.getGameLeft() + this.gameViewport.getGameRight()) * CENTER_FRACTION, this.viewHeight * CENTER_FRACTION);
    }

    /**
     * Toggles the leaderboard player rows.
     */
    void toggleLeaderboardPlayers() {
        if (this.consoleView != null) {
            this.consoleView.toggleLeaderboardPlayers();
        }
    }

    /**
     * Stores the view position in a camera slot.
     *
     * @param slot the slot from {@code 0} through {@code 2}
     */
    void storeView(int slot) {
        if (this.viewJumps != null) {
            this.viewJumps.saveSlot(slot);
        }
    }

    /**
     * Jumps to the view stored in the requested camera-jump slot.
     *
     * @param slot the camera-jump slot
     */
    void recallView(int slot) {
        if (this.viewJumps != null) {
            this.viewJumps.jumpToSlot(slot);
        }
    }

    /**
     * Applies a changed configured speed to a live single-player game.
     */
    private void applyGameSpeedOption() {
        int speed = this.options.getGameSpeed();

        if (speed == this.appliedGameSpeedOption) {
            return;
        }

        this.appliedGameSpeedOption = speed;

        if (this.session != null && this.requireSession().isLive() && !this.requireSession().isNetworkGame()) {
            this.requireSession().applyGameSpeedSetting(speed);
        }
    }

    /**
     * Opens a known live outcome once and arms the defeat-box close timer when needed.
     */
    private void updateOutcome() {
        ClientSession current = this.session;

        if (current == null || !current.isLive() || this.endPhase != END_NONE || this.outcomeDone || current.getLiveOutcome() == 0) {
            return;
        }

        int outcome = current.getLiveOutcome();

        this.outcomeVictory = outcome == TriggerPlayerResults.VICTORY || outcome == TriggerPlayerResults.DRAW;

        try {
            // The unrepresented game-end marker uses its default value.
            this.outcomeBox = OutcomeBox.open(current.getStorage(),
                                              this.outcomeVictory, outcome == TriggerPlayerResults.DRAW,
                                              current.isNetworkGame(), false,
                                              this.consoleView == null ? key -> "" : this.consoleView::getControlString);
        } catch (IOException exception) {
            this.setAssetWarning("Outcome box: " + exception.getMessage());
            this.outcomeBox = null;
        }

        this.endPhase = END_BOX;

        if (!this.outcomeVictory && this.outcomeBox != null) {
            OutcomeBox box = this.outcomeBox;
            ClientContext client = this.clientContext;

            if (client != null) {
                client.getTimers().arm(box, OUTCOME_CLOSE_TIMER, OutcomeBox.DEFEAT_CLOSE_MILLISECONDS,
                                       (int) UiClockUtils.getHostTimeMilliseconds(), now -> {
                            client.getTimers().killAll(box);

                            if (this.endPhase == END_BOX && this.outcomeBox == box) {
                                this.showScoreScreen();
                            }
                        });
            }
        }

        this.stopTriggerSounds();

        if (this.gameMenu != null) {
            this.gameMenu.close();
        }
    }

    /**
     * Runs a released control of the victory or defeat box ({@link OutcomeBox#press}).
     *
     * @param control the released control
     * @return {@code true} if the box took the control, {@code false} otherwise
     */
    boolean pressOutcomeBox(int control) {
        OutcomeBox box = this.outcomeBox;

        if (box == null || this.endPhase != END_BOX) {
            return false;
        }

        switch (box.press(control)) {
            case END_GAME -> this.showScoreScreen();
            case CONTINUE_PLAYING -> {
                if (this.consoleView != null && this.session != null) {
                    this.requireSession().getPresentation().getInterfaceLink().getMessageLines().writeGameMessage(
                            this.consoleView.getNetworkText(OutcomeBox.CONTINUE_WARNING).getBytes(StandardCharsets.UTF_8), 0
                    );
                }

                if (this.clientContext != null) {
                    this.clientContext.getTimers().killAll(box);
                }

                this.outcomeBox  = null;
                this.outcomeDone = true;
                this.endPhase    = END_NONE;
            }
            default -> {
                return false;
            }
        }

        return true;
    }

    /**
     * Saves and closes a single-player game and returns to the glue without a score screen.
     */
    private void endWithoutScore() {
        if (this.session == null) {
            return;
        }

        if (this.options.isHidden() && this.inputScript == null) {
            this.endLiveGame();

            return;
        }

        this.stopTriggerSounds();
        this.replayWriter.saveAtEnd(this.session);
        this.returnToGlue(GlueResume.createAfterGame(this.sessionGlueRequest, false));
    }

    /**
     * Collects score data, saves the replay and closes the session before returning to the glue score flow.
     */
    void showScoreScreen() {
        if (this.session == null) {
            return;
        }

        // A hidden smoke run ends with its game; a scripted hidden run goes on to the score screen as a player would.
        if (this.options.isHidden() && this.inputScript == null) {
            this.endLiveGame();

            return;
        }

        String[] names = new String[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        List<ReplayHeader.ReplayPlayer> headerPlayers = this.requireSession().getReplay().getHeader().getPlayers();

        for (int index = 0; index < headerPlayers.size() && index < PlayerTable.PLAYABLE_PLAYER_SLOTS; index++) {
            names[index] = this.requireSession().getReplay().getPlayerNames().get(index);
        }

        GluText glueText = this.consoleView == null ? null : this.consoleView.getGluText();
        int lastFrame = (int) Math.max(0, this.requireSession().getLastCompletedFrame());
        long seconds = Integer.toUnsignedLong(TimerTextUtils.calculateElapsedSeconds(lastFrame, GameSpeed.FASTEST_DURATION));

        boolean victory = this.outcomeVictory;
        boolean multiplayer = this.requireSession().isNetworkGame();

        String computerName = multiplayer && this.consoleView != null ? this.consoleView.getNetworkText("strCOMPUTER")
                                                                      : glueText == null ? "Computer" : glueText.get("slotCOMPUTER");
        String[] scoreNames = names.clone();

        if (!multiplayer) {
            for (int slot = 0; slot < scoreNames.length; slot++) {
                Player record = this.requireSession().getView().getPlayerTable().getPlayer(slot);

                if (record != null && (record.getType() == PlayerTypes.COMPUTER || record.getType() == PlayerTypes.COMPUTER_LEFT)) {
                    scoreNames[slot] = computerName;
                }
            }
        }

        ScoreScreen score = new ScoreScreen(this.requireSession().getView(),
                                            scoreNames,
                                            computerName,
                                            multiplayer,
                                            this.getScoreForceNames(),
                                            this.getScoreLocalPlayer(),
                                            victory,
                                            this.consoleModel == null ? TERRAN_RACE : this.consoleModel.getRace(),
                                            seconds);

        score.setActionsPerMinute(this.computeScoreActionsPerMinute(seconds * MILLISECONDS_PER_SECOND));

        score.setReplaySavingAllowed(!this.replayWriter.isCampaignGame() && !this.sessionClassicLimits);
        this.sessionClassicLimits = false;
        this.stopTriggerSounds();
        this.replayWriter.saveAtEnd(this.session);

        Path written = this.requireSession().isLive() ? this.replayWriter.getLastWritten() : this.requireSession().getReplayPath();

        if (multiplayer && !this.replayWriter.isCampaignGame() && written != null && Files.isRegularFile(written)) {
            score.setWatchedReplay(written);
        }

        this.returnToGlue(GlueResume.createAfterGame(this.sessionGlueRequest, victory)
                                    .withScore(score, new GlueResume.ReplaySaver() {
                                        /**
                                         * Returns the folder the dialog lists and writes into.
                                         * <p>
                                         * A replay saver returns the replay folder the Save Replay dialog lists and writes into.
                                         *
                                         * @return the folder the dialog lists and writes into
                                         */
                                        @Override
                                        public @NotNull Path getDirectory() {
                                            return Main.this.replayWriter.getReplayDirectory();
                                        }

                                        /**
                                         * Saves under a name: writes the replay into {@link #getDirectory()}, or sends the Save Game command whose
                                         * execution writes the game.
                                         * <p>
                                         * A replay saver writes the game's replay into {@link #getDirectory()}.
                                         *
                                         * @param name the name the dialog read from its name box: the bytes cut to a saved game name buffer,
                                         *             without trailing spaces and without terminator
                                         * @return {@code true} if the save was written or sent, {@code false} otherwise
                                         */
                                        @Override
                                        public boolean save(byte @NotNull [] name) {
                                            String fileName = FileNameUtils.readFileName(name);

                                            if (fileName == null) {
                                                return false;
                                            }

                                            Path target = this.getDirectory().resolve(fileName + this.getExtension());

                                            return ReplayAutosave.saveCopy(written, target);
                                        }
                                    })
                                    .withNextScenario(multiplayer ? null : this.getNextScenario()));
    }

    /**
     * Returns the APM of each slot for the score screen from the recorded commands of the live game.
     *
     * @param elapsedMilliseconds the game time
     * @return the APM of each slot
     */
    private int @NotNull [] computeScoreActionsPerMinute(long elapsedMilliseconds) {
        return ScoreScreen.computeActionsPerMinute(this.requireSession().getReplay().getCommands(), this.createCommandIssuerSlots(), elapsedMilliseconds);
    }

    /**
     * Creates the slot of every command issuer byte of the session's game: the record index its commands act in
     * ({@link ReplayCommandIssuer#resolve}), {@code -1} for an issuer that resolves to no record.
     *
     * @return the record slot of every issuer byte, {@code -1} for an issuer that resolves to no record
     */
    private int @NotNull [] createCommandIssuerSlots() {
        int[] slots = new int[REPLAY_PLAYER_IDENTIFIER_COUNT];

        for (int issuer = 0; issuer < REPLAY_PLAYER_IDENTIFIER_COUNT; issuer++) {
            ReplayCommandIssuer resolution = this.requireSession().resolveCommandIssuer(issuer);

            slots[issuer] = resolution.getStatus() == ReplayCommandIssuer.Status.RESOLVED ? resolution.getRecordIndex() : -1;
        }

        return slots;
    }

    /**
     * Draws the live game's popups: the game menu page, the victory / defeat box, or the score screen.
     *
     * @throws IOException if a popup image cannot be loaded
     */
    private void drawGamePopups() throws IOException {
        if (this.session == null || this.consoleView == null) {
            return;
        }

        GameMenu menu = this.gameMenu;

        if (menu != null) {
            menu.refresh(this.menuContext, this.consoleView::getControlString);
        }

        if (this.chatHistory.getList() != null && (menu == null || menu.getPage() != GameMenu.Page.CHAT_HISTORY)) {
            this.chatHistory.close();
            this.consoleView.closePopupList();
        }

        if (this.keystrokeHelpList != null && (menu == null || menu.getPage() != GameMenu.Page.KEYSTROKE_HELP)) {
            this.keystrokeHelpList = null;
            this.consoleView.closePopupList();
        }

        if (this.endPhase != END_BOX && menu != null && menu.getPage() == GameMenu.Page.OBJECTIVES && menu.getDialog() != null) {
            // Objectives text (control 2): the local player's mission objectives map string.
            TriggerContext context = this.requireSession().getView().getTriggerContext();
            int index = context == null ? 0 : context.getMissionObjectivesStringIndex(this.requireSession().getLocalPlayer());

            ScrControl objectives = menu.getDialog().findControl(OBJECTIVES_TEXT_CONTROL);

            if (objectives != null) {
                objectives.setReplacedText(StatText.stripColors(this.requireSession().getMapString(index)));
            }
        }

        ScrDialog dialog = this.getOpenGamePopup();

        if (dialog != null) {
            this.consoleView.drawPopup(dialog);
        }

        NetworkWaitDialog wait = this.networkWaitDialog;

        if (wait != null) {
            wait.update(this.requireSession().getNetworkWaitCountdown(), this.requireSession().getNetworkWaitingPlayers(),
                        this.requireSession().getPresentation().getInterfaceLink().getLocalInterface(),
                        (int) UiClockUtils.getHostTimeMilliseconds());

            if (wait.getOpenDialog() != null) {
                this.consoleView.drawPopup(wait.getOpenDialog());
            }
        }
    }

    /**
     * Debounces a change between windowed mode and the last applied fullscreen mode.
     */
    private void toggleWindowMode() {
        long now = UiClockUtils.getHostTimeMilliseconds();

        if (now - this.windowModeTime < WINDOW_MODE_DEBOUNCE_MILLISECONDS || this.options.isHidden()) {
            return;
        }

        this.windowModeTime = now;
        this.glueState.setWindowMode(this.isFullScreen() ? GraphicsSettings.WINDOWED : this.fullscreenMode);
    }

    /**
     * Applies a changed display mode while retaining the previous window bounds ({@link HostWindow#setDisplayMode}) and selected fullscreen mode.
     */
    private void applyWindowMode() {
        int mode = this.options.isHidden() ? GraphicsSettings.WINDOWED : this.options.getGraphicsSettings().getWindowMode();
        DisplayMode displayMode = toDisplayMode(mode);
        HostWindow current = this.requireHostWindow();

        if (displayMode == current.getDisplayMode()) {
            return;
        }

        if (mode != GraphicsSettings.WINDOWED) {
            this.fullscreenMode = mode;
        }

        current.setDisplayMode(displayMode, this.options.getWindowWidth(), this.options.getWindowHeight());
    }

    /**
     * Handles the live keys that are no accelerator table entry ({@link GameAcceleratorHandler} runs those first): Esc cancels targeting and
     * placement or skips transmissions, Space jumps to the last alert; the replay viewer's keys are ignored.
     *
     * @param key the key
     * @return {@code true} if the key was consumed, {@code false} otherwise
     */
    boolean handleLiveKey(@NotNull Key key) {
        if (this.session == null || !this.requireSession().isLive()) {
            return false;
        }

        return switch (key) {
            case ESCAPE -> {
                this.pressEscape();

                yield true;
            }

            case SPACE -> {
                if (this.viewJumps != null) {
                    this.viewJumps.jumpToAlert();
                }

                yield true;
            }

            // The key live play shares with the viewer: Tab (minimap terrain / color mode).
            case TAB -> false;
            // Every other key ends here in live play.
            default -> true;
        };
    }

    /**
     * Cancels targeting or placement; otherwise skips the current transmissions.
     */
    void pressEscape() {
        boolean targetingOrPlacing = this.worldInterface != null &&
                                     (this.worldInterface.getPlacementType() >= 0 ||
                                      this.consoleModel != null && this.consoleModel.getMode().isTargeting());

        if (targetingOrPlacing) {
            this.worldInterface.cancelMode();
        } else {
            this.skipTransmissions();
        }
    }

    /**
     * Resets the interface state of the client that belongs to one game, at the session start, as the game's interface initialization does: nothing
     * of the previous game (a scroll, a timer, a mode, a menu) carries into the next.
     */
    private void resetGameInterfaceState() {
        // The Center View scroll belongs to the game's interface: a scroll still active when the previous game ended (its arrival callback bound to
        // that game) must not carry into this one and block the scroll tick.
        this.centerViewScroll.reset();
        this.transmissionTimer.cancel();

        this.cameraScroll.reset();

        this.unitSpeech       = null;
        this.unitSpeechOutput = null;
        this.alertCooldownEnd = 0;
        this.triggerSounds.clear();

        this.chatModeByte = 0;
        this.chatWasOpen  = false;

        // The previous game's menu and outcome box.
        if (this.gameMenu != null) {
            this.gameMenu.close();
        }

        this.gameMenu          = null;
        this.networkWaitDialog = null;
        this.outcomeBox        = null;
        this.outcomeDone       = false;
        this.outcomeVictory    = false;
        this.endPhase          = END_NONE;
        this.liveStopMessage   = null;
        this.gameClientState.resetGameInterface();
    }

    /**
     * Closes the running session and its interface so that the glue draws the window again.
     */
    private void closeSessionForGlue() {
        // Every way back to the glue ends the game; a game that ended without its score (a stop, a fatal box) still writes its replay once.
        this.replayWriter.saveAtEnd(this.session);

        if (this.gameMenu != null) {
            this.gameMenu.close();
        }

        this.gameMenu          = null;
        this.networkWaitDialog = null;
        this.outcomeBox        = null;
        this.endPhase          = END_NONE;
        this.playing           = false;
        this.closeConsole();

        ClientSession finishedSession = this.session;

        if (finishedSession != null) {
            this.closeFinishedSession(finishedSession);
        }

        this.session = null;

        if (this.clientContext != null) {
            this.clientContext.getMusic().fadeOut();
        }
    }

    /**
     * Closes the running session after handing its random state to the client's global random.
     *
     * @param finishedSession the running session
     */
    private void closeFinishedSession(@NotNull ClientSession finishedSession) {
        if (this.clientContext != null) {
            this.clientContext.getGlobalRandom().setState(finishedSession.getView().getRandomState());
        }

        finishedSession.close();
    }

    /**
     * Hands the glue the state it resumes with and closes the running session, so that the glue draws the window again.
     *
     * @param resume the glue state after the game
     */
    private void returnToGlue(@NotNull GlueResume resume) {
        this.glueState.setResume(resume);
        this.sessionGlueRequest = null;
        this.closeSessionForGlue();
    }

    private void endLiveGame() {
        this.stopTriggerSounds();
        this.replayWriter.saveAtEnd(this.session);
        this.requestWindowClose();
    }

    /**
     * Draws the mode prompt or the open chat box above the console.
     */
    private void drawPrompt() {
        ChatPrompt prompt = this.gameClientState.getChatPrompt();
        EditText line = prompt.getText();
        String modePrompt = this.getModePromptKey();

        if (line == null && this.chatModeByte == 0 && modePrompt != null && this.session != null && this.requireSession().isLive() &&
            this.isConsoleShown()) {
            // Targeting / placement: the text-box dialog's status control 6, shown while the chat box is closed.
            try {
                this.requireConsoleView().drawModePrompt(modePrompt);
            } catch (IOException exception) {
                this.setAssetWarning("Mode prompt: " + exception.getMessage());
            }
        }

        if (line != null && this.session != null && this.isConsoleShown()) {
            try {
                // The caret string 04 A6 01 at the caret, blinking with the 500 ms edit timer.
                this.requireConsoleView().drawChatBox(line, UiClockUtils.getHostTimeMilliseconds());
            } catch (IOException exception) {
                this.setAssetWarning("Chat box: " + exception.getMessage());
            }
        }
    }

    /**
     * Formats a recipient or observer label and installs it for the opened chat mode.
     *
     * @param mode the opened chat mode
     */
    void showChatLabel(int mode) {
        if (this.consoleView == null) {
            return;
        }

        try {
            String label = null;

            if (mode == ChatPrompt.MODE_RECIPIENT && this.session != null && this.requireSession().isNetworkGame()) {
                this.requireSession().formatChatLabel(this.consoleView.getChatRecipientFormat());
                label = this.requireSession().getChatLabel();
            } else if (mode == ChatPrompt.MODE_OBSERVERS && this.clientContext != null) {
                TextTables text = this.clientContext.getTextTables(this.getTextLocale());

                label = text.getControl().get("textbox-6").replace("{name}", text.getGluText().get("lobbyScreen_observers"));
            }

            this.consoleView.setChatLabel(mode, label);
        } catch (IOException exception) {
            this.setAssetWarning("Chat box: " + exception.getMessage());
        }
    }

    /**
     * Receives pending network chat and plays the transmission sound when new message lines requested it.
     */
    private void receiveChat() {
        ClientSession current = this.session;

        if (current == null) {
            return;
        }

        if (current.isNetworkGame()) {
            current.receiveNetworkChat();
        }

        int sounds = current.getPresentation().getInterfaceLink().getMessageLines().takeTransmissionSounds();
        SoundTable table = this.soundTable;
        int sound = table == null || sounds == 0 ? 0 : table.getSoundIdentifier("SND_TRANSMISSION");

        if (sound != 0) {
            current.playInterfaceSound(sound, -1, ClientAudio.MAXIMUM_VOLUME);
        }
    }

    /**
     * Lists the public domain fields of the console's active unit at the top left of the view.
     */
    private void drawUnitInspection() {
        Unit unit = this.consoleModel == null ? null : this.consoleModel.getSelection().getActiveUnit();

        if (unit == null) {
            return;
        }

        String[] lines = {
                String.format(Locale.ROOT, "unit %d type %d owner %d", unit.getIndex(), unit.getUnitId(), unit.getOwnerIndex()),
                String.format(Locale.ROOT, "position %d,%d heading %d", unit.getPositionX(), unit.getPositionY(), unit.getHeading()),
                // Hit points, shields and energy are stored in 1/256 units.
                String.format(Locale.ROOT, "hp %d shields %d energy %d (raw %d %d %d)", unit.getHitPoints() >> UNIT_POINT_FRACTION_BITS,
                              unit.getShieldPoints() >> UNIT_POINT_FRACTION_BITS, unit.getEnergy() >> UNIT_POINT_FRACTION_BITS,
                              unit.getHitPoints(), unit.getShieldPoints(), unit.getEnergy()),
                String.format(Locale.ROOT, "status %08X", unit.getStatus()),
                String.format(Locale.ROOT, "order %d secondary %d", unit.getPrimaryOrder(), unit.getSecondaryOrder()),
                String.format(Locale.ROOT, "order target %s", describeUnit(unit.getOrderTargetUnit())),
                String.format(Locale.ROOT, "move target %s", describeUnit(unit.getMovementTargetUnit())),
                String.format(Locale.ROOT, "movement flags %02X speed %d top %d",
                              unit.getMovementFlags(), unit.getCurrentSpeed(), unit.getTopSpeed()),
                String.format(Locale.ROOT, "velocity %d,%d direction %d", unit.getVelocityX(), unit.getVelocityY(), unit.getMovementDirection()),
                String.format(Locale.ROOT, "path %d", unit.getPathIndex()),
                };
        float width = 0;

        for (String line : lines) {
            width = Math.max(width, this.requireInterfaceRenderer().measureText(line, UNIT_INSPECTION_TEXT_HEIGHT));
        }

        float left = this.gameViewport.getGameLeft() + UNIT_INSPECTION_PANEL_HORIZONTAL_INSET;

        this.getInterfaceRenderer().fillRectangle(left, UNIT_INSPECTION_PANEL_TOP,
                                                  width + UNIT_INSPECTION_PANEL_HORIZONTAL_PADDING,
                                                  lines.length * UNIT_INSPECTION_ROW_HEIGHT + UNIT_INSPECTION_PANEL_VERTICAL_PADDING,
                                                  UNIT_INSPECTION_PANEL_COLOR);

        for (int index = 0; index < lines.length; index++) {
            this.getInterfaceRenderer().drawText(lines[index],
                                                 left + UNIT_INSPECTION_TEXT_HORIZONTAL_INSET,
                                                 UNIT_INSPECTION_TEXT_TOP + index * UNIT_INSPECTION_ROW_HEIGHT,
                                                 UNIT_INSPECTION_TEXT_HEIGHT, UNIT_INSPECTION_TEXT_COLOR);
        }
    }

    /**
     * Matches punctuation and non-ASCII typed characters against command-button hotkeys, lowercasing ASCII first.
     *
     * @param codePoint the typed character
     * @return {@code true} if a command button took it, {@code false} otherwise
     */
    boolean pressCommandCharacter(int codePoint) {
        if (!this.isConsoleShown() || this.worldInterface == null || !Character.isBmpCodePoint(codePoint) || codePoint < ' ' ||
            codePoint >= 'A' && codePoint <= 'Z' || codePoint >= 'a' && codePoint <= 'z') {
            return false;
        }

        boolean shift = this.isWindowKeyDown(Key.LEFT_SHIFT) || this.isWindowKeyDown(Key.RIGHT_SHIFT);

        return this.pressCommandHotkey((char) codePoint, shift);
    }

    /**
     * Presses the command-card button of a pressed key: Escape, or a letter without Control and Alt (Shift queues).
     *
     * @param key       the key
     * @param modifiers the modifier mask ({@link KeyModifier#getMask()} bits)
     * @return {@code true} if a command button took the key, {@code false} otherwise
     */
    boolean pressCommandHotkey(@NotNull Key key, int modifiers) {
        if (!this.isConsoleShown() || this.worldInterface == null || KeyModifier.CONTROL.isIn(modifiers) || KeyModifier.ALT.isIn(modifiers)) {
            return false;
        }

        int letter = key.getLetterIndex();
        char hotkey = key == Key.ESCAPE ? ESCAPE_HOTKEY : letter >= 0 ? (char) ('a' + letter) : 0;

        if (hotkey == 0) {
            return false;
        }

        return this.pressCommandHotkey(hotkey, KeyModifier.SHIFT.isIn(modifiers));
    }

    private boolean pressCommandHotkey(char hotkey, boolean queued) {
        ConsoleLayout layout = this.requireConsoleView().getLayout();

        if (layout != null && !this.consoleView.isConsoleDialogShown(layout.getCommandButtons())) {
            return false;
        }

        int slot = this.requireWorldInterface().getCommandInput().findHotkeySlot(this.consoleView.getHotkeyProfile(), hotkey);

        if (slot < 0) {
            return false;
        }

        // The hotkey presses the button like a mouse press; the command runs at the release 20 ms later (HotkeyPress), or at once when the key is
        // pressed again while the button is still pressed.
        HotkeyPress press = this.consoleView.getHotkeyPress();
        int activated = press.press(this.requireConsoleModel().getCommandCard()::getPressCancellations, slot, queued,
                                    UiClockUtils.getHostTimeMilliseconds());

        LOGGER.debug("UI HOTKEY: " + (hotkey == ESCAPE_HOTKEY ? "ESC" : String.valueOf(hotkey)) + " PRESSED " + slot);

        if (activated >= 0) {
            boolean releasedQueued = press.isReleasedQueued();
            CommandInput.Result commandResult = this.worldInterface.getCommandInput().pressSlot(activated, releasedQueued, true);

            LOGGER.debug("UI HOTKEY RELEASE: slot " + activated + " " + commandResult);
        }

        return true;
    }

    /**
     * Tells the game's plugins that the client played a frame ({@link ScrGamePlugin#onFramePlayed}).
     */
    private void notifyFramePlayed() {
        Game game = this.requireSession().getGame();
        int frame = game.getCurrentFrame();

        for (ScrGamePlugin plugin : game.getPlugins()) {
            plugin.onFramePlayed(frame);
        }
    }

    /**
     * Supplies the pointer position in interface units, the units of the screen space of the plugins' draw lists, to the running game.
     */
    void supplyInterfacePointer() {
        if (this.session == null) {
            return;
        }

        LocalDisplay display = this.requireSession().getPresentation().getInterfaceLink().getLocalDisplay();

        display.setPointer(this.gameViewport.toInterfaceX(this.pointerHorizontal), this.gameViewport.toInterfaceY(this.pointerVertical));
    }

    /**
     * Supplies camera geometry, interface pointer coordinates and the displayed local-interface state to the running game.
     */
    private void supplyLocalView() {
        if (this.session == null) {
            return;
        }

        this.supplyInterfacePointer();

        ClientCamera camera = this.requireSession().getCamera();
        int viewX = Math.round(camera.screenToWorldX(this.gameViewport.getGameLeft()));
        int viewY = Math.round(camera.getY());

        boolean surface = this.consoleView != null && this.consoleView.getLayout() != null;
        int mouseX = (int) Math.floor(surface ? this.gameViewport.toSurfaceX(this.pointerHorizontal) : this.pointerHorizontal);
        int mouseY = (int) Math.floor(surface ? this.gameViewport.toSurfaceY(this.pointerVertical) : this.pointerVertical);

        if (Math.abs(camera.getZoom() - camera.getNativeZoom()) >= ZOOM_TOLERANCE) {
            mouseX = (int) Math.floor(camera.screenToWorldX(this.pointerHorizontal)) - viewX;
            mouseY = (int) Math.floor(camera.screenToWorldY(this.pointerVertical)) - viewY;
        }

        this.suppliedMouseX = mouseX;
        this.suppliedMouseY = mouseY;

        LocalView localView = this.requireSession().getPresentation().getInterfaceLink().getLocalView();

        localView.setView(viewX, viewY, mouseX, mouseY);
        // The game screen size at the current zoom, which the single-player Center View scroll target also reads (presentTriggerEvent).
        localView.setScreenGeometry(this.gameViewport.getViewTileWidth(camera.getZoom()), this.gameViewport.getViewTileHeight(camera.getZoom()),
                                    Math.round(camera.getMaximumScreenX()), Math.round(camera.getMaximumScreenY()));

        ChatPrompt chatPrompt = this.gameClientState.getChatPrompt();
        boolean chatOpen = chatPrompt.isOpen();

        if (chatOpen != this.chatWasOpen) {
            this.chatWasOpen  = chatOpen;
            this.chatModeByte = chatPrompt.getMode();
        }

        LocalInterfaceState local = this.requireSession().getPresentation().getInterfaceLink().getLocalInterface();

        local.setMouseButtons(this.mouseButtonsWord);
        local.setChatMode(this.chatModeByte);

        local.setGluePalette(this.glueState.getGluePalette());

        local.setDisplayedButtonSet(this.consoleModel == null ? ButtonSetTable.NO_SET : this.consoleModel.getCommandCard().getDisplayedSet());
    }

    /**
     * Applies EUD interface and view writes for the next render, routing changed mouse coordinates through the current layout.
     */
    void applyLocalViewWrite() {
        if (this.session == null) {
            return;
        }

        LocalInterfaceState local = this.requireSession().getPresentation().getInterfaceLink().getLocalInterface();

        if (local.takeWritten()) {
            this.mouseButtonsWord = local.getMouseButtons();
            this.chatModeByte     = local.getChatMode();
            this.glueState.setGluePalette(local.getGluePalette());

            if (this.consoleModel != null) {
                this.consoleModel.getCommandCard().setDisplayedSet(local.getDisplayedButtonSet());
            }
        }

        LocalView view = this.requireSession().getPresentation().getInterfaceLink().getLocalView();

        if (!view.takeWritten()) {
            return;
        }

        LOGGER.debug(() -> "CAMERA EUD WRITE: screen " + view.getScreenX() + "," + view.getScreenY() + " at frame " +
                           this.requireSession().getView().getCurrentFrame());
        this.createPlayViewCamera(this.requireSession()).moveTo(view.getScreenX(), view.getScreenY());

        if (view.getMouseX() != this.suppliedMouseX || view.getMouseY() != this.suppliedMouseY) {
            this.moveMouseToSurface(view.getMouseX(), view.getMouseY());
        }
    }

    /**
     * Routes written surface coordinates through the current layout and moves the focused visible window pointer.
     *
     * @param surfaceX the written surface column
     * @param surfaceY the written surface row
     */
    private void moveMouseToSurface(int surfaceX, int surfaceY) {
        ConsoleLayout layout = this.consoleView == null ? null : this.consoleView.getLayout();
        float x = layout != null ? layout.toScreenX(surfaceX) : surfaceX;
        float y = layout != null ? layout.toScreenY(surfaceY) : surfaceY;

        LOGGER.debug(() -> "MOUSE EUD WRITE: surface " + surfaceX + "," + surfaceY + " -> framebuffer " + x + "," + y);
        this.dispatchPointer(x, y);

        if (!this.pointerFixed && !this.options.isHidden() && this.isWindowFocused()) {
            this.moveWindowPointer(x, y);
        }
    }

    /**
     * Polls the command hotkey release and activates a released slot.
     */
    private void pollHotkeyPress() {
        if (this.consoleView == null || this.worldInterface == null) {
            return;
        }

        HotkeyPress press = this.consoleView.getHotkeyPress();
        int released = this.consoleModel == null
                       ? -1
                       : press.poll(this.consoleModel.getCommandCard()::getPressCancellations, UiClockUtils.getHostTimeMilliseconds());

        if (released >= 0) {
            boolean releasedQueued = press.isReleasedQueued();
            CommandInput.Result commandResult = this.worldInterface.getCommandInput().pressSlot(released, releasedQueued, true);

            LOGGER.debug("UI HOTKEY RELEASE: slot " + released + " " + commandResult);
        }
    }

    private void tickCameraScroll() {
        ConsoleLayout layout = this.consoleView == null ? null : this.consoleView.getLayout();

        if (layout == null || this.requireSession().isLive() && this.isGamePopupOpen() || this.centerViewScroll.isActive() ||
            this.worldInterface != null && this.worldInterface.isDragSelecting() || this.gameClientState.isMinimapDragging()) {
            this.cameraScroll.clearEdge();

            return;
        }

        boolean live = this.requireSession().isLive();

        int[] keys = CameraScroll.getKeyboardDirection(key -> (live || key != Key.HOME) && this.isKeyDown(key));
        GameScreenCamera camera = this.createPlayViewCamera(this.requireSession());
        float startX = camera.getX();
        float startY = camera.getY();
        // An input script drives the pointer of a hidden window, which never has the focus. No edge scroll from a position never reported, or from a
        // cursor outside the client area (an unconfined cursor off the window does not scroll).
        boolean inWindow = (this.pointerFixed || this.pointerKnown) &&
                           (this.pointerFixed || this.inputScript != null || this.isWindowFocused()) &&
                           this.pointerHorizontal >= 0 && this.pointerVertical >= 0 && this.pointerHorizontal < this.viewWidth &&
                           this.pointerVertical < this.viewHeight;
        int pointerX = (int) Math.floor(this.gameViewport.toInterfaceX(this.pointerHorizontal));
        // The edge zones lie at the game view's top and bottom edges (GameViewport.toEdgeInterfaceY).
        int pointerY = (int) Math.floor(this.gameViewport.toEdgeInterfaceY(this.pointerVertical));

        this.scrollRate.setKeySpeed(this.options.getInterfaceSettings().getKeyScroll());
        this.scrollRate.setEdgeSpeed(this.options.getInterfaceSettings().getMouseScroll());
        // The stepped rate takes one step per interface tick, whatever the time.
        this.cameraScroll.update(camera,
                                 keys[KEYBOARD_DIRECTION_X_INDEX], keys[KEYBOARD_DIRECTION_Y_INDEX],
                                 pointerX, pointerY,
                                 inWindow,
                                 this.gameViewport.getInterfaceWidth(), ConsoleLayout.INTERFACE_HEIGHT,
                                 0);
        LOGGER.debug(() -> "CAMERA SCROLL: " + startX + "," + startY + " -> " + camera.getX() + "," + camera.getY() +
                           " keys " + keys[KEYBOARD_DIRECTION_X_INDEX] + "," + keys[KEYBOARD_DIRECTION_Y_INDEX] +
                           " pointer " + this.pointerHorizontal + "," + this.pointerVertical +
                           " interface " + pointerX + "," + pointerY + " inWindow " + inWindow + " known " + this.pointerKnown +
                           " fixed " + this.pointerFixed +
                           " focused " + this.isWindowFocused() +
                           " view " + this.viewWidth + "x" + this.viewHeight);
    }

    /**
     * Selects an HD lighting debug view ({@code /debug lighting <view>}, {@code --debug lights=<view>}).
     *
     * @param view the view
     * @return the message describing the effect
     */
    private @NotNull String selectLightingView(@NotNull LightingView view) {
        this.worldSkin.setLightingDebugView(view.getRendererIndex());

        String message = "Lighting view: " + view.getName();

        if (this.worldSkin.getQuality() == null) {
            message += " (no effect in classic graphics; /graphics hd2 switches)";
        } else if (!this.options.isHdLighting()) {
            message += " (no effect: HD lighting is off, --hd-lighting on)";
        }

        this.showStatus(message);

        return message + ".";
    }

    /**
     * Shows a short status line at the top of the game screen for three seconds.
     *
     * @param message the message
     */
    private void showStatus(@NotNull String message) {
        this.statusMessage  = message;
        this.statusDeadline = System.nanoTime() + STATUS_DURATION_NANOSECONDS;
    }

    /**
     * Draws the debug information over the game view: a replay progress strip across the top and a translucent box in the top right corner with the
     * map, frame, random state, speed, the next recorded command and the status.
     *
     * @param loadingMessage the loading message, or {@code null} outside a load
     */
    private void drawDebugHud(@Nullable String loadingMessage) {
        List<DebugPanel.Section> sections = new ArrayList<>();
        DebugPanel.Section performance = new DebugPanel.Section(DebugPanel.Page.PERFORMANCE, "Performance");

        sections.add(performance);

        float framesPerSecond = this.statusOverlay.getFramesPerSecond();

        performance.add("FPS", String.format(Locale.ROOT, "%.0f", framesPerSecond),
                        framesPerSecond >= GOOD_FRAME_RATE_THRESHOLD ? DebugPanel.GOOD_COLOR
                                                                     : framesPerSecond >= WARNING_FRAME_RATE_THRESHOLD ? DebugPanel.WARNING_COLOR
                                                                                                                       : DebugPanel.ERROR_COLOR);
        performance.add("Frame", String.format(Locale.ROOT, "%.1f ms avg, %.1f ms max", this.debugPanel.getAverageFrameMilliseconds(),
                                               this.debugPanel.getMaximumFrameMilliseconds()));
        performance.add("Update", String.format(Locale.ROOT, "%.2f ms input + simulation", this.debugPanel.getAverageUpdateMilliseconds()));

        HdRenderAssets.Quality quality = this.worldSkin.getQuality();
        String rendererName = quality == null ? "classic" : quality.name() + (this.options.isHdLighting() ? " lit" : " forward");
        int lightingView = Math.max(this.worldSkin.getLightingDebugView(), 0);

        performance.add("Renderer", rendererName + " (F5 next)");
        performance.add("Lighting", (quality == null ? "classic (none)" : this.options.isHdLighting() ? "on" : "off") + ", view " +
                                    HdSceneRenderer.LIGHTING_DEBUG_VIEWS[lightingView] + " (/debug lighting next)");

        GraphicsSettings graphics = this.options.getGraphicsSettings();

        performance.add("Pacing",
                        "vsync " + (this.options.isHidden() ? "off (hidden)" : graphics.getVsync().name().toLowerCase(Locale.ROOT)) +
                        ", limit " + (graphics.getEffectiveFrameRateLimit() == 0 ? "none" : graphics.getEffectiveFrameRateLimit() + " fps"),
                        DebugPanel.MUTED_COLOR);

        HdSceneRenderer hdScene = this.worldSkin.getHdScene();

        if (hdScene != null) {
            performance.add("HD textures", (hdScene.getTextureBytes() >> HdImageSet.MEBIBYTE_SHIFT) + " MB", DebugPanel.MUTED_COLOR);
        }

        if (this.session == null) {
            DebugPanel.Section game = new DebugPanel.Section(DebugPanel.Page.GAME, "Game state");

            game.add("Status", loadingMessage != null ? loadingMessage : "No replay loaded. Type /replay open or drop a file.",
                     DebugPanel.WARNING_COLOR);
            sections.add(game);
        } else {
            ScrGameView state = this.requireSession().getView();
            ReplayHeader header = this.requireSession().getReplay().getHeader();
            ClientCamera camera = this.requireSession().getCamera();
            DebugPanel.Section view = new DebugPanel.Section(DebugPanel.Page.CAMERA, "Camera / map");

            view.add("Map", header.getMap() + "  " + state.getMap().getWidth() + "x" + state.getMap().getHeight() + "  tileset " +
                            state.getMap().getTileset());
            view.add("Camera",
                     Math.round(camera.getX()) + ", " + Math.round(camera.getY()) + "  zoom " +
                     Math.round(camera.getZoom() * PERCENT_SCALE) + "%");
            view.add("View", camera.getViewportWidth() + " x " + camera.getViewportHeight() + " px");
            sections.add(view);

            DebugPanel.Section selection = new DebugPanel.Section(DebugPanel.Page.SELECTION, "Selection");
            DebugPanel.Section orders = new DebugPanel.Section(DebugPanel.Page.ORDERS, "Orders / paths");
            int selected = this.worldInterface != null ? this.worldInterface.getSelectionCount() : 0;

            selection.add("Selected", selected + (selected == 1 ? " unit" : " units"));

            if (selected > 0) {
                Unit unit = this.worldInterface.getSelectedUnit(0);

                selection.add("Unit", "type " + unit.getUnitId() + "  owner " + unit.getOwnerIndex() + "  at " + unit.getPositionX() + ", " +
                                      unit.getPositionY());
                selection.add("Vitals", "hp " + UnitQueries.getHitPoints(unit) + "  shield " + (unit.getShieldPoints() >> UNIT_POINT_FRACTION_BITS) +
                                        "  energy " + (unit.getEnergy() >> UNIT_POINT_FRACTION_BITS));

                Unit target = unit.getOrderTargetUnit();

                orders.add("Order", unit.getPrimaryOrder() + " state " + unit.getPrimaryOrderState() + "  secondary " + unit.getSecondaryOrder());
                orders.add("Target",
                           unit.getOrderTargetX() + ", " + unit.getOrderTargetY() +
                           (target != null ? "  unit type " + target.getUnitId() : ""));
                orders.add("Path", unit.getPathIndex() < 0 ? "none" : "slot " + unit.getPathIndex(), DebugPanel.MUTED_COLOR);
            }

            orders.add("Layers", this.debugOverlay.getEnabledLayers().toString().toLowerCase(Locale.ROOT), DebugPanel.MUTED_COLOR);
            sections.add(selection);
            sections.add(orders);

            DebugPanel.Section game = new DebugPanel.Section(DebugPanel.Page.GAME, "Game state");
            long completed = Math.max(0, this.requireSession().getLastCompletedFrame());

            game.add("Frame", this.requireSession().getLastCompletedFrame() + " / " + header.getFrames() + "  " +
                              TimerTextUtils.formatGameTime((int) completed) + " / " + TimerTextUtils.formatGameTime((int) header.getFrames()));
            game.add("RNG", Integer.toUnsignedString(state.getRandomState()) + "  calls " + state.getRandomCallCount());
            game.add("Seed", Long.toUnsignedString(header.getRandomSeed()));

            String objectCount = this.requireSession().isPlaybackRequested()
                                 ? state.getUnitSystem().getUnitPool() == null
                                   ? "units not constructed"
                                   : "units " + state.getUnitSystem().getUnitPool().getAllocatedCount()
                                 : "placements " + this.requireSession().getPlacements().size();

            game.add("Speed",
                     (this.requireSession().hasFrameTiming()
                      ? this.getReplaySpeedLabel() + " (" + this.requireSession().getFrameDuration() + " ms)  "
                      : "  ") +
                     objectCount);
            game.add("Status", this.requireSession().isPlaybackRequested() ? this.getPlaybackLine() : this.requireSession().getStatus(),
                     DebugPanel.WARNING_COLOR);

            String detail = this.requireSession().isPlaybackRequested() ? this.getPlaybackDetail() : "";

            if (!detail.isEmpty()) {
                game.add("", detail, DebugPanel.WARNING_COLOR);
            }

            List<String> lines = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();

            if (this.requireSession().isPlaybackRequested()) {
                this.collectCommandLines(lines, colors);
            } else if (this.gameClientState.getInspection().getPreview() != null) {
                this.collectImageLines(lines, colors);
            } else {
                this.collectPlacementLines(lines, colors);
            }

            for (int index = 0; index < lines.size(); index++) {
                game.add(index == 0 ? "Next" : "", lines.get(index), colors.get(index));
            }

            sections.add(game);
        }

        if (this.errorMessage != null) {
            performance.add("Error", this.errorMessage, DebugPanel.ERROR_COLOR);
        }

        if (this.assetWarning != null) {
            performance.add("Warning", this.assetWarning, DebugPanel.WARNING_COLOR);
        }

        this.debugSections = sections;
        this.drawTimelineStrip();
    }

    /**
     * Draws the debug panel prepared by {@link #drawDebugHud} over the console layer, left in the game screen and above the console.
     */
    private void drawDebugPanel() {
        List<DebugPanel.Section> sections = this.debugSections;

        this.debugSections = null;

        if (this.getInterfaceRenderer() != null && this.statusMessage != null) {
            if (System.nanoTime() - this.statusDeadline > 0) {
                this.statusMessage = null;
            } else {
                float width = this.getInterfaceRenderer().measureText(this.statusMessage, STATUS_TEXT_HEIGHT) + STATUS_HORIZONTAL_PADDING;
                float left = (this.gameViewport.getGameLeft() + this.gameViewport.getGameRight() - width) / CENTER_DIVISOR;

                this.getInterfaceRenderer().begin(this.framebufferWidth, this.framebufferHeight);
                this.getInterfaceRenderer().fillRectangle(left, STATUS_PANEL_TOP, width, STATUS_PANEL_HEIGHT, STATUS_PANEL_COLOR);
                this.getInterfaceRenderer().drawText(this.statusMessage,
                                                     left + STATUS_TEXT_HORIZONTAL_INSET, STATUS_TEXT_TOP,
                                                     STATUS_TEXT_HEIGHT, STATUS_TEXT_COLOR);
                this.getInterfaceRenderer().end();
            }
        }

        if (sections == null || this.getInterfaceRenderer() == null) {
            return;
        }

        ConsoleLayout layout = this.isConsoleShown() ? this.requireConsoleView().getLayout() : null;
        float bottom = layout != null
                       ? layout.toScreenY(layout.getConsoleTop()) - DEBUG_PANEL_CONSOLE_BOTTOM_GAP
                       : this.viewHeight - DEBUG_PANEL_WINDOW_BOTTOM_GAP;

        this.getInterfaceRenderer().begin(this.framebufferWidth, this.framebufferHeight);
        this.debugPanel.draw(this.getInterfaceRenderer(),
                             sections,
                             "Enter /help lists the commands \u00b7 /help debug explains this view \u00b7 F5 graphics",
                             this.gameViewport.getGameLeft() + DEBUG_PANEL_HORIZONTAL_INSET, DEBUG_PANEL_TOP,
                             bottom);
        this.getInterfaceRenderer().end();
    }

    /**
     * Describes the next recorded command.
     *
     * @param lines  the lines to append to
     * @param colors the line colors to append to
     */
    private void collectCommandLines(@NotNull List<String> lines, @NotNull List<Integer> colors) {
        ReplayCommand command = this.requireSession().getPendingCommand();
        ReplayCommandIssuer issuer = this.requireSession().getPendingCommandIssuer();

        if (command == null || issuer == null) {
            addHudLine(lines, colors, "Next command: none; turn markers " + this.requireSession().getView().getCommandDispatchMarkerCount(), MUTED_COLOR);

            return;
        }

        String issuerText = issuer.getStatus() == ReplayCommandIssuer.Status.RESOLVED
                            ? "record " + issuer.getRecordIndex() + " -> player " + Integer.toUnsignedString(issuer.getCommandPlayerIndex())
                            : issuer.getStatus() == ReplayCommandIssuer.Status.UNRESOLVED_EXTENDED_RECORDS ? "issuer: extended records unknown"
                                                                                                           : "issuer: player records unknown";

        addHudLine(lines, colors, "Next command frame " + command.getFrame() + " opcode 0x" + Integer.toHexString(command.getTypeId()) +
                                  " issuer " + Integer.toUnsignedString(command.getPlayerId()) + " (" + issuerText + ")", TEXT_COLOR);

        ReplaySelectionCommand selection = this.requireSession().getPendingSelection();
        ReplayTargetCommand target = this.requireSession().getPendingTarget();

        if (selection != null) {
            StringBuilder handles = new StringBuilder();

            for (int index = 0; index < Math.min(COMMAND_STATUS_HANDLE_LIMIT, selection.getHandleCount()); index++) {
                handles.append(String.format(Locale.ROOT,
                                             selection.getHandleWidthBytes() == Short.BYTES ? " 0x%04X" : " 0x%08X",
                                             selection.getHandle(index)));
            }

            if (selection.getHandleCount() > COMMAND_STATUS_HANDLE_LIMIT) {
                handles.append(" +").append(selection.getHandleCount() - COMMAND_STATUS_HANDLE_LIMIT);
            }

            addHudLine(lines, colors, selection.getOperation().name() + " " + selection.getHandleCount() + " x " +
                                      selection.getHandleWidthBytes() * Byte.SIZE + "-bit:" + handles, ACCENT_COLOR);
        } else if (target != null) {
            addHudLine(lines, colors, target.getOperation().name() + " at " + target.getX() + "," + target.getY() +
                                      String.format(Locale.ROOT, target.getHandleWidthBytes() == Short.BYTES ? " handle 0x%04X" : " handle 0x%08X",
                                                    target.getTargetHandle()) + " type " + target.getUnitTypeIdentifier() + " flags " +
                                      target.getQueueFlags() + (target.getOrderIdentifier() < 0 ? "" : " order " + target.getOrderIdentifier()),
                       ACCENT_COLOR);
        } else if (this.requireSession().getPendingCommandError() != null) {
            addHudLine(lines, colors, "Command payload invalid: " + this.requireSession().getPendingCommandError(), WARNING_COLOR);
        } else {
            ReplayCommandDescription description = ReplayCommandDescription.describe(command);

            addHudLine(lines, colors, description.getName() + " (" + command.getPayload().length + " bytes)", ACCENT_COLOR);

            for (String detail : description.getDetails()) {
                addHudLine(lines, colors, "  " + detail, TEXT_COLOR);
            }
        }
    }

    /**
     * Describes the selected map placement in map inspection.
     *
     * @param lines  the lines to append to
     * @param colors the line colors to append to
     */
    private void collectPlacementLines(@NotNull List<String> lines, @NotNull List<Integer> colors) {
        ChkUnit selectedUnit = this.requireSession().getSelectedPlacement();

        if (selectedUnit == null) {
            addHudLine(lines, colors, "Click a unit on the map to inspect its map input values.", MUTED_COLOR);

            return;
        }

        ScrGameView state = this.requireSession().getView();

        addHudLine(lines, colors, "Placement type " + selectedUnit.getUnitId() + " serial " + selectedUnit.getSerial() + " owner " +
                                  selectedUnit.getOwner() + " at " + selectedUnit.getX() + "," + selectedUnit.getY(), TEXT_COLOR);
        addHudLine(lines, colors, String.format(Locale.ROOT, "Input health %d%% shields %d%% resources %d", selectedUnit.getHitPointsPercent(),
                                                selectedUnit.getShieldPointsPercent(), selectedUnit.getResourceAmount()), TEXT_COLOR);

        UnitInspection inspection = this.gameClientState.getInspection();

        addHudLine(lines, colors,
                   "GRP frame " + inspection.getFrame() + " / " + inspection.getFrameCount() +
                   (inspection.isFlipped() ? " mirrored" : "") + "  [ ] frame  M mirror  I image script",
                   ACCENT_COLOR);

        GameMap map = state.getMap();
        int tileHorizontal = selectedUnit.getX() / TerrainTileset.TILE_SIZE;
        int tileVertical = selectedUnit.getY() / TerrainTileset.TILE_SIZE;

        if (map.hasTileFlags() && tileHorizontal >= 0 && tileHorizontal < map.getWidth() && tileVertical >= 0 && tileVertical < map.getHeight()) {
            addHudLine(lines, colors,
                       String.format(Locale.ROOT, "Initial terrain 0x%08X", map.getTileFlags(tileHorizontal, tileVertical)), MUTED_COLOR);
        }
    }

    /**
     * Describes the manual image script preview.
     *
     * @param lines  the lines to append to
     * @param colors the line colors to append to
     */
    private void collectImageLines(@NotNull List<String> lines, @NotNull List<Integer> colors) {
        UnitInspection inspection = this.gameClientState.getInspection();
        ImageScriptPreview preview = inspection.getPreview();

        if (preview == null) {
            return;
        }

        SpriteImage image = preview.getImage();

        addHudLine(lines, colors, "Image " + image.getImageTypeId() + " script " + preview.getScriptIdentifier() + " animation " +
                                  image.getAnimationType() + " request " + inspection.getRequestedAnimation(), ACCENT_COLOR);
        addHudLine(lines, colors, "Frame " + image.getDrawnFrameIndex() + " wait " + image.getWait() + " offset " + image.getProgramCounter() + " calls " +
                                  preview.getInvocationCount(), TEXT_COLOR);
        addHudLine(lines, colors, "I restart  T invoke  J/K choose  Enter request  Backspace GRP view", MUTED_COLOR);

        String status = preview.getStopReason();

        if (status != null) {
            addHudLine(lines, colors, status, WARNING_COLOR);
        } else if (preview.isEnded()) {
            addHudLine(lines, colors, "Image ended. World state unchanged.", WARNING_COLOR);
        }
    }

    /**
     * Draws the replay progress across the top of the view; a click there seeks ({@link #seekOnTimeline()}).
     */
    private void drawTimelineStrip() {
        if (this.session == null || !this.requireSession().isPlaybackRequested()) {
            return;
        }

        float width = this.viewWidth - TIMELINE_HORIZONTAL_INSETS;
        long total = Math.max(1, this.requireSession().getReplay().getHeader().getFrames());
        long completed = Math.max(0, this.requireSession().getLastCompletedFrame());

        this.requireRenderer().fillRectangle(TIMELINE_HORIZONTAL_INSET, TIMELINE_STRIP_TOP, width, TIMELINE_STRIP_HEIGHT, TIMELINE_BACKGROUND_COLOR);
        this.renderer.fillRectangle(TIMELINE_HORIZONTAL_INSET, TIMELINE_STRIP_TOP,
                                    width * Math.min(1f, completed / (float) total), TIMELINE_STRIP_HEIGHT,
                                    TIMELINE_PROGRESS_COLOR);

        long seekTarget = this.playback.getSeekTarget();

        if (seekTarget >= 0) {
            this.renderer.fillRectangle(
                    TIMELINE_HORIZONTAL_INSET + width * Math.min(1f, seekTarget / (float) total) - TIMELINE_MARKER_HORIZONTAL_INSET,
                    TIMELINE_MARKER_TOP,
                    TIMELINE_MARKER_WIDTH, TIMELINE_MARKER_HEIGHT,
                    TIMELINE_MARKER_COLOR
            );
        }
    }

    /**
     * Seeks to the clicked position of the progress strip.
     *
     * @return {@code true} if the pointer was on the strip, {@code false} otherwise
     */
    boolean seekOnTimeline() {
        if (this.session == null || !this.requireSession().isPlaybackRequested() || this.pointerVertical > TIMELINE_CLICK_HEIGHT) {
            return false;
        }

        double fraction = Math.clamp((this.pointerHorizontal - TIMELINE_HORIZONTAL_INSET) / (this.viewWidth - TIMELINE_HORIZONTAL_INSETS), 0, 1);

        this.playback.requestSeek(Math.round(fraction * this.requireSession().getReplay().getHeader().getFrames()),
                                  this.requireSession().getLastCompletedFrame());

        return true;
    }

    /**
     * Ages out the rendering warning and reports each distinct warning once on the console.
     */
    private void updateWarnings() {
        if (this.assetWarning != null && System.nanoTime() - this.assetWarningTime >= ASSET_WARNING_NANOSECONDS) {
            this.assetWarning = null;
        }

        if (this.assetWarning != null && !this.assetWarning.equals(this.reportedAssetWarning)) {
            // Report each distinct rendering warning once on the console so unattended runs can be checked.
            LOGGER.warning("RENDER WARNING: " + this.assetWarning);
            this.reportedAssetWarning = this.assetWarning;
        }
    }

    /**
     * Presents the drawn frame of every screen: composes it ({@link #composeFrame}), counts the present for the frame rate and swaps the buffers.
     */
    private void presentFrame() {
        this.composeFrame();
        this.statusOverlay.recordPresent(System.nanoTime());
        this.requireHostWindow().swapBuffers();

        this.overlayComposed = false;
        this.frameComposed   = false;
    }

    /**
     * Composes the drawn frame once per present, the same over every screen: the status overlay ({@link #composeOverlay}, over the window unless it
     * is drawn already), then the placed cursor over the window.
     */
    private void composeFrame() {
        ClientContext client = this.clientContext;

        if (this.frameComposed || client == null) {
            return;
        }

        InterfaceRenderer interfaceRenderer = client.getInterfaceRenderer();

        this.frameComposed = true;

        this.composeOverlay(0, this.framebufferWidth, this.framebufferHeight);

        interfaceRenderer.begin(this.framebufferWidth, this.framebufferHeight);

        try {
            client.getCursor().drawPlaced();
        } catch (IOException | RuntimeException exception) {
            this.setAssetWarning("Cursor: " + exception.getMessage());
        }

        interfaceRenderer.end();
    }

    /**
     * Draws the status overlay once per present. With SD graphics it is drawn into the SD compose target before its present, so that it is resampled
     * with the screens; otherwise over the window. The overlay uses the small font of the game's font set while a session runs, else of the menus'
     * set, and the interface placement of the screen: the menus' canvas outside a session while the menus are open, else the game screen's
     * ({@link GameViewport}). The turn rate shows only in a network game.
     *
     * @param framebuffer    the target framebuffer, {@code 0} for the window
     * @param viewportWidth  the target width in pixels
     * @param viewportHeight the target height in pixels
     */
    private void composeOverlay(int framebuffer, int viewportWidth, int viewportHeight) {
        ClientContext client = this.clientContext;

        if (this.overlayComposed || client == null) {
            return;
        }

        InterfaceSettings settings = this.options.getInterfaceSettings();
        LiveGame live = this.session == null ? null : this.requireSession().getLiveGame();
        GluText glueText = this.consoleView == null ? null : this.consoleView.getGluText();
        InterfaceFonts fonts = this.session == null ? client.getGlueFonts() : client.getGameFonts();
        GlueCanvas canvas = this.session == null ? this.glueState.getCanvas() : null;
        InterfaceRenderer interfaceRenderer = client.getInterfaceRenderer();

        this.overlayComposed = true;

        if (live != null && live.isNetworkGame()) {
            String latency = glueText == null ? "" : glueText.get(StatusOverlay.getLatencyKey(live.getLatency()));
            String scan = glueText == null || !live.isTurnRateScanning() ? "" : glueText.get(StatusOverlay.getScanKey());

            this.statusOverlay.update(settings, true, live.getTurnRate(), latency, scan);
        } else {
            this.statusOverlay.update(settings, false, 0, "", "");
        }

        interfaceRenderer.begin(framebuffer, viewportWidth, viewportHeight, this.framebufferWidth, this.framebufferHeight);
        this.statusOverlay.draw(interfaceRenderer, canvas != null ? canvas : this.gameViewport, fonts.getFont(InterfaceFonts.SMALL));
        interfaceRenderer.end();
    }

    /**
     * Saves the drawn frame as it is presented, composed with the status overlay and the cursor, to a PNG file.
     *
     * @param path the PNG file
     * @throws IOException if the file cannot be written
     */
    private void captureScreenshot(@NotNull Path path) throws IOException {
        this.composeFrame();

        ByteBuffer pixels = MemoryUtil.memAlloc(
                Math.multiplyExact(Math.multiplyExact(this.framebufferWidth, this.framebufferHeight), OverlayPass.RGBA_BYTES)
        );

        try {
            GL11C.glReadBuffer(GL11C.GL_BACK);
            GL11C.glReadPixels(0, 0, this.framebufferWidth, this.framebufferHeight, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);

            BufferedImage image = new BufferedImage(this.framebufferWidth, this.framebufferHeight, BufferedImage.TYPE_INT_ARGB);

            for (int vertical = 0; vertical < this.framebufferHeight; vertical++) {
                for (int horizontal = 0; horizontal < this.framebufferWidth; horizontal++) {
                    int offset = (vertical * this.framebufferWidth + horizontal) * OverlayPass.RGBA_BYTES;
                    int color = OPAQUE_ARGB | Byte.toUnsignedInt(pixels.get(offset)) << Short.SIZE |
                                Byte.toUnsignedInt(pixels.get(offset + OverlayPass.RGBA_GREEN)) << Byte.SIZE |
                                Byte.toUnsignedInt(pixels.get(offset + OverlayPass.RGBA_BLUE));

                    image.setRGB(horizontal, this.framebufferHeight - vertical - 1, color);
                }
            }

            Path parent = path.toAbsolutePath().getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            ImageIO.write(image, "png", path.toFile());
            LOGGER.info("SCREENSHOT: " + path.toAbsolutePath());
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }
}
