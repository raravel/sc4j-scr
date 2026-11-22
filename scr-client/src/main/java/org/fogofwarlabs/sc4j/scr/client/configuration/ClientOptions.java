package org.fogofwarlabs.sc4j.scr.client.configuration;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.input.InputScript;
import org.fogofwarlabs.sc4j.engine.client.settings.SettingsStore;
import org.fogofwarlabs.sc4j.scr.client.ReplayPlayback;
import org.fogofwarlabs.sc4j.scr.client.audio.SoundSettings;
import org.fogofwarlabs.sc4j.scr.client.ui.input.HotkeyProfile;
import org.fogofwarlabs.sc4j.scr.data.format.casc.SoundPathResolver;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.render.hd.HdRenderAssets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses development client launch options.
 */
public final class ClientOptions {
    private static final @NotNull Logger LOGGER = Logger.of(ClientOptions.class);

    private static final int DEFAULT_GAME_SPEED = 4;

    private static final int DEFAULT_WINDOW_WIDTH = 1280;
    private static final int DEFAULT_WINDOW_HEIGHT = 800;
    private static final int MINIMUM_WINDOW_WIDTH = 320;
    private static final int MINIMUM_WINDOW_HEIGHT = 240;
    private static final char WINDOW_SIZE_SEPARATOR = 'x';

    private static final int MAXIMUM_PREVIEW_STEPS = 10000;
    private static final int MAXIMUM_IMAGE_ANIMATION = 255;
    private static final int MAXIMUM_IMAGE_STEPS = 10000;
    private static final int SMOKE_FRAME_LIMIT = 120;

    private static final char COORDINATE_SEPARATOR = ',';
    private static final @NotNull String SEEK_TARGET_SEPARATOR = ",";
    private static final @NotNull String SEEK_TARGETS_MESSAGE = "--seek must list nonnegative replay frames.";

    private static final @NotNull String CONSOLE_REPLAY_SUFFIX = ":replay";
    private static final int MAXIMUM_CONSOLE_PLAYER = 7;

    private static final @NotNull String NO_SETTINGS_FILE = "none";

    /**
     * The launch command option, the prefix and the complete pattern of the launch-only {@code /wait N} command that holds the next launch
     * commands for game frames, and the frame count reported for any other command.
     */
    private static final @NotNull String COMMAND_OPTION = "--command";
    private static final @NotNull String LAUNCH_WAIT_PREFIX = "/wait ";
    private static final @NotNull String LAUNCH_WAIT_PATTERN = "/wait [0-9]+";
    private static final long NO_LAUNCH_WAIT = -1;

    private static final @NotNull String DEFAULT_MELEE_RACE = "terran";
    private static final @NotNull String DEFAULT_MELEE_COMPUTERS = "";
    private static final @NotNull String DEFAULT_PLAYER_NAME = "Player";

    /**
     * A single-player game against computer players ({@code --melee}), with the map's own settings ({@code --ums}),
     * or a built-in campaign mission ({@code --campaign}).
     */
    public static final class Melee {
        private final @NotNull Path map;
        private final @Nullable String campaign;
        private final boolean usingMapSettings;

        private final @NotNull String name;
        private final @NotNull String race;
        private final int slot;
        private final @NotNull String computers;

        private final int speed;
        private final @Nullable Path save;

        /**
         * Creates a {@link Melee} instance.
         *
         * @param map              the {@code .scm}/{@code .scx} map or raw {@code .chk}
         * @param campaign         the built-in campaign mission ({@code --campaign}: index, launch name such as {@code bw-terran-1}, or
         *                         {@code list}); the map is then that argument, used only as the session and replay file name
         * @param usingMapSettings whether the map's own settings fix players, races and forces ({@code --ums};
         *                         the slot is the index among the map's human slots)
         * @param name             the human player's name ({@code --name})
         * @param race             the human race: {@code terran}, {@code zerg}, {@code protoss} or {@code random} ({@code --race})
         * @param slot             the human player's slot ({@code --slot})
         * @param computers        the comma-separated computer races ({@code --computer})
         * @param speed            the game speed {@code 0..6}, {@code 6 = Fastest} ({@code --game-speed})
         * @param save             where the replay is saved at the end, or {@code null} for the default path ({@code --save})
         */
        Melee(@NotNull Path map, @Nullable String campaign, boolean usingMapSettings,
              @NotNull String name, @NotNull String race, int slot,
              @NotNull String computers,
              int speed,
              @Nullable Path save) {
            this.map              = map;
            this.campaign         = campaign;
            this.usingMapSettings = usingMapSettings;

            this.name      = name;
            this.race      = race;
            this.slot      = slot;
            this.computers = computers;

            this.speed = speed;
            this.save  = save;
        }

        /**
         * @return the map path
         */
        public @NotNull Path getMap() {
            return this.map;
        }

        /**
         * @return the campaign identifier, or {@code null} when absent
         */
        public @Nullable String getCampaign() {
            return this.campaign;
        }

        /**
         * @return {@code true} if the map supplies game rules, {@code false} otherwise
         */
        public boolean isUsingMapSettings() {
            return this.usingMapSettings;
        }

        /**
         * @return the local player name
         */
        public @NotNull String getName() {
            return this.name;
        }

        /**
         * @return the local player race
         */
        public @NotNull String getRace() {
            return this.race;
        }

        /**
         * @return the local player slot
         */
        public int getSlot() {
            return this.slot;
        }

        /**
         * @return the computer-player specification
         */
        public @NotNull String getComputers() {
            return this.computers;
        }

        /**
         * @return the initial game speed
         */
        public int getSpeed() {
            return this.speed;
        }

        /**
         * @return the replay destination, or {@code null} to use the default path
         */
        public @Nullable Path getSave() {
            return this.save;
        }
    }

    /**
     * Creates a {@link ClientOptions} instance by parsing the launch arguments, loading the optional settings and applying the explicit overrides.
     * The client root is detected ({@link InstallLayout#detect()}).
     *
     * @param arguments the launch arguments
     * @return the created {@link ClientOptions} instance
     * @throws IllegalArgumentException if an option or value is invalid
     * @throws IllegalStateException    if an option listed as taking a value has no value parser
     */
    public static @NotNull ClientOptions parse(@NotNull String @NotNull [] arguments) {
        return parse(arguments, InstallLayout.detect());
    }

    /**
     * Creates a {@link ClientOptions} instance by parsing the launch arguments, loading the optional settings and applying the explicit overrides,
     * with relative paths, the settings file and the client folders below a given root.
     *
     * @param arguments the launch arguments
     * @param layout    the client root and its folders
     * @return the created {@link ClientOptions} instance
     * @throws IllegalArgumentException if an option or value is invalid
     * @throws IllegalStateException    if an option listed as taking a value has no value parser
     */
    public static @NotNull ClientOptions parse(@NotNull String @NotNull [] arguments, @NotNull InstallLayout layout) {
        InstallLayout.DataDirectory dataDirectory = null;
        String dataDirectorySetting = null;
        Path replayPath = null;
        Path screenshotPath = null;
        Path inputScript = null;
        int frameLimit = 0;

        int previewSteps = 0;
        boolean previewStepsSpecified = false;
        int focusUnit = -1;
        int unitFrame = 0;
        boolean unitFrameSpecified = false;
        int imageAnimation = -1;
        int imageSteps = 0;
        boolean imageStepsSpecified = false;
        boolean mirrorUnit = false;
        boolean hidden = false;
        float contentScale = 1;
        boolean noGrab = false;
        boolean mapCompatibility = false;

        String hotkeyProfile = HotkeyProfile.DEVICE;
        Map<Integer, Integer> hotkeys = new HashMap<>();
        SoundPathResolver.AudioSet audioSet = SoundPathResolver.AudioSet.REMASTERED;
        boolean extendedUnitLimits = true;
        boolean customHotkeys = true;
        int gameSpeed = DEFAULT_GAME_SPEED;
        Map<String, Object> createGame = Map.of();

        GraphicsSettings graphics = new GraphicsSettings();
        SoundSettings sound = new SoundSettings();
        InterfaceSettings interfaceSettings = new InterfaceSettings();
        GraphicsSettings.Vsync requestedVsync = null;
        Integer requestedFrameRateLimit = null;
        Boolean requestedSmoothTurning = null;
        Boolean requestedShadowStacking = null;
        Boolean requestedEnvironmentEffects = null;
        Integer requestedSdFilterMode = null;
        Boolean requestedOriginalAspectRatio = null;
        Integer requestedGamma = null;
        String settingsArgument = null;
        LogLevel logLevel = LogLevel.INFO;
        Boolean logFile = null;
        boolean inspectMap = false;
        boolean playRequested = false;
        int speedIndex = ReplayPlayback.getNormalSpeedIndex();
        long seekFrame = -1;
        long[] seekFrames = new long[0];
        boolean help = false;

        float cameraX = Float.NaN;
        float cameraY = Float.NaN;
        float zoom = Float.NaN;
        HdRenderAssets.Quality sceneQuality = GraphicsChoice.HD.getQuality();
        boolean hdLighting = true;
        String hdSkin = null;
        int consolePlayer = -1;
        boolean consoleReplayControls = false;

        float pointerX = Float.NaN;
        float pointerY = Float.NaN;
        String debugLayers = null;
        int windowWidth = DEFAULT_WINDOW_WIDTH;
        int windowHeight = DEFAULT_WINDOW_HEIGHT;
        boolean hdConsoleCentered = false;
        String locale = null;
        List<String> commands = new ArrayList<>();

        Path meleeMap = null;
        String meleeRace = DEFAULT_MELEE_RACE;
        String meleeComputers = DEFAULT_MELEE_COMPUTERS;
        int meleeSlot = 0;
        int meleeSpeed = GameSpeed.FASTEST;
        String meleeName = DEFAULT_PLAYER_NAME;
        Path meleeSave = null;
        String campaign = null;
        boolean useMapSettings = false;

        Set<String> specifiedOptions = new HashSet<>();

        for (int argumentIndex = 0; argumentIndex < arguments.length; argumentIndex++) {
            String argument = arguments[argumentIndex];

            specifiedOptions.add(argument);

            switch (argument) {
                case "smoke", "--smoke" -> frameLimit = SMOKE_FRAME_LIMIT;
                case "--hidden" -> hidden = true;
                case "--no-grab" -> noGrab = true;
                case "--map-compat" -> mapCompatibility = true;
                case "--inspect-map" -> inspectMap = true;
                case "--play" -> playRequested = true;
                case "--mirror-unit" -> mirrorUnit = true;
                case "--help", "-h" -> help = true;
                case "--data",
                     "--replay",
                     "--screenshot",
                     "--frames",
                     "--preview-steps",
                     "--focus-unit",
                     "--unit-frame",
                     "--image-animation",
                     "--image-steps",
                     "--camera",
                     "--speed",
                     "--seek",
                     "--renderer",
                     "--hd-lighting",
                     "--audio",
                     "--vsync",
                     "--settings",
                     "--log-level",
                     "--max-fps",
                     "--smooth-turning",
                     "--shadow-stacking",
                     "--environment-effects",
                     "--sd-filter",
                     "--original-aspect-ratio",
                     "--gamma",
                     "--hd-skin",
                     "--console",
                     "--pointer",
                     "--debug",
                     "--window",
                     "--hd-console",
                     "--locale",
                     "--command",
                     "--input-script",
                     "--melee",
                     "--ums",
                     "--campaign",
                     "--race",
                     "--computer",
                     "--slot",
                     "--game-speed",
                     "--name",
                     "--save",
                     "--content-scale",
                     "--zoom" -> {
                    if (++argumentIndex == arguments.length) {
                        throw Exceptions.newIllegalArgumentException("Missing value for {0}", argument);
                    }

                    String value = arguments[argumentIndex];

                    switch (argument) {
                        case "--data" -> dataDirectory = new InstallLayout.DataDirectory(layout.resolveUserPath(Path.of(value)), "--data");
                        case "--replay" -> replayPath = layout.resolveUserPath(Path.of(value));
                        case "--screenshot" -> screenshotPath = layout.resolveUserPath(Path.of(value));
                        case "--input-script" -> inputScript = layout.resolveUserPath(Path.of(value));
                        case "--frames" -> {
                            frameLimit = ClientValuesUtils.parseInteger(argument, value);

                            if (frameLimit < 1) {
                                throw Exceptions.newIllegalArgumentException("--frames must be positive.");
                            }
                        }
                        case "--preview-steps" -> {
                            previewSteps          = ClientValuesUtils.parseInteger(argument, value);
                            previewStepsSpecified = true;

                            if (previewSteps < 0 || previewSteps > MAXIMUM_PREVIEW_STEPS) {
                                throw Exceptions.newIllegalArgumentException("--preview-steps must be between 0 and 10000.");
                            }
                        }
                        case "--focus-unit" -> {
                            focusUnit = ClientValuesUtils.parseInteger(argument, value);

                            if (focusUnit < 0) {
                                throw Exceptions.newIllegalArgumentException("--focus-unit must be a nonnegative CHK placement index.");
                            }
                        }
                        case "--unit-frame" -> {
                            unitFrame          = ClientValuesUtils.parseInteger(argument, value);
                            unitFrameSpecified = true;

                            if (unitFrame < 0) {
                                throw Exceptions.newIllegalArgumentException("--unit-frame must be a nonnegative GRP frame index.");
                            }
                        }
                        case "--image-animation" -> {
                            imageAnimation = ClientValuesUtils.parseInteger(argument, value);

                            if (imageAnimation < 0 || imageAnimation > MAXIMUM_IMAGE_ANIMATION) {
                                throw Exceptions.newIllegalArgumentException("--image-animation must be an unsigned byte index.");
                            }
                        }
                        case "--image-steps" -> {
                            imageSteps          = ClientValuesUtils.parseInteger(argument, value);
                            imageStepsSpecified = true;

                            if (imageSteps < 0 || imageSteps > MAXIMUM_IMAGE_STEPS) {
                                throw Exceptions.newIllegalArgumentException("--image-steps must be between 0 and 10000.");
                            }
                        }
                        case "--speed" -> speedIndex = ReplayPlayback.parseSpeed(value);
                        case "--seek" -> {
                            String[] targets = value.split(SEEK_TARGET_SEPARATOR);

                            if (targets.length == 0) {
                                throw Exceptions.newIllegalArgumentException(SEEK_TARGETS_MESSAGE);
                            }

                            seekFrames = new long[targets.length];

                            for (int targetIndex = 0; targetIndex < targets.length; targetIndex++) {
                                seekFrames[targetIndex] = ClientValuesUtils.parseLong(argument, targets[targetIndex].trim());

                                if (seekFrames[targetIndex] < 0) {
                                    throw Exceptions.newIllegalArgumentException(SEEK_TARGETS_MESSAGE);
                                }
                            }

                            seekFrame = seekFrames[0];
                        }
                        case "--camera" -> {
                            int separator = value.indexOf(COORDINATE_SEPARATOR);

                            if (separator < 0) {
                                throw Exceptions.newIllegalArgumentException("--camera requires horizontal,vertical world pixels.");
                            }

                            cameraX = ClientValuesUtils.parseFloat(argument, value.substring(0, separator));
                            cameraY = ClientValuesUtils.parseFloat(argument, value.substring(separator + 1));

                            if (!Float.isFinite(cameraX) || !Float.isFinite(cameraY)) {
                                throw Exceptions.newIllegalArgumentException("--camera coordinates must be finite.");
                            }
                        }
                        case "--zoom" -> {
                            zoom = ClientValuesUtils.parseFloat(argument, value);

                            if (!Float.isFinite(zoom) || zoom <= 0) {
                                throw Exceptions.newIllegalArgumentException("--zoom must be a positive finite scale.");
                            }
                        }
                        case "--content-scale" -> {
                            contentScale = ClientValuesUtils.parseFloat(argument, value);

                            if (!Float.isFinite(contentScale) || contentScale <= 0) {
                                throw Exceptions.newIllegalArgumentException("--content-scale must be a positive finite scale.");
                            }
                        }
                        case "--hd-skin" -> hdSkin = ClientValuesUtils.parseHdSkin(argument, value);
                        case "--vsync" -> requestedVsync = ClientValuesUtils.parseVsync(argument, value);
                        case "--max-fps" -> requestedFrameRateLimit = ClientValuesUtils.parseFrameRateLimit(argument, value);
                        case "--smooth-turning" -> requestedSmoothTurning = ClientValuesUtils.parseOnOff(argument, value);
                        case "--shadow-stacking" -> requestedShadowStacking = ClientValuesUtils.parseOnOff(argument, value);
                        case "--environment-effects" -> requestedEnvironmentEffects = ClientValuesUtils.parseOnOff(argument, value);
                        case "--sd-filter" -> {
                            requestedSdFilterMode = ClientValuesUtils.parseInteger(argument, value);

                            if (requestedSdFilterMode < 0 || requestedSdFilterMode > GraphicsSettings.MAXIMUM_SD_FILTER_MODE) {
                                throw Exceptions.newIllegalArgumentException("--sd-filter must be 0 through 3.");
                            }
                        }
                        case "--original-aspect-ratio" -> requestedOriginalAspectRatio = ClientValuesUtils.parseOnOff(argument, value);
                        case "--gamma" -> {
                            requestedGamma = ClientValuesUtils.parseInteger(argument, value);

                            if (requestedGamma < GraphicsSettings.MINIMUM_GAMMA || requestedGamma > GraphicsSettings.MAXIMUM_GAMMA) {
                                throw Exceptions.newIllegalArgumentException("--gamma must be {0} through {1}.",
                                                                             GraphicsSettings.MINIMUM_GAMMA,
                                                                             GraphicsSettings.MAXIMUM_GAMMA);
                            }
                        }
                        case "--settings" -> settingsArgument = value;
                        case "--log-level" -> logLevel = ClientValuesUtils.parseLogLevel(argument, value);
                        case "--audio" -> audioSet = ClientValuesUtils.parseAudioSet(argument, value);
                        case "--hd-lighting" -> hdLighting = ClientValuesUtils.parseOnOff(argument, value);
                        case "--renderer" -> sceneQuality = ClientValuesUtils.parseRenderer(argument, value);
                        case "--console" -> {
                            consoleReplayControls = value.endsWith(CONSOLE_REPLAY_SUFFIX);

                            String slot = consoleReplayControls ? value.substring(0, value.length() - CONSOLE_REPLAY_SUFFIX.length()) : value;

                            consolePlayer = ClientValuesUtils.parseInteger(argument, slot);

                            if (consolePlayer < 0 || consolePlayer > MAXIMUM_CONSOLE_PLAYER) {
                                throw Exceptions.newIllegalArgumentException("--console requires a player slot from 0 through 7, " +
                                                                             "optionally followed by :replay.");
                            }
                        }
                        case "--debug" -> debugLayers = value;
                        case "--window" -> {
                            int separator = value.toLowerCase(Locale.ROOT).indexOf(WINDOW_SIZE_SEPARATOR);

                            if (separator < 0) {
                                throw Exceptions.newIllegalArgumentException("--window requires WIDTHxHEIGHT.");
                            }

                            windowWidth  = ClientValuesUtils.parseInteger(argument, value.substring(0, separator));
                            windowHeight = ClientValuesUtils.parseInteger(argument, value.substring(separator + 1));

                            if (windowWidth < MINIMUM_WINDOW_WIDTH || windowHeight < MINIMUM_WINDOW_HEIGHT) {
                                throw Exceptions.newIllegalArgumentException("--window must be at least 320x240.");
                            }
                        }
                        case "--hd-console" -> hdConsoleCentered = switch (value) {
                            case "wide" -> false;
                            case "centered" -> true;
                            default -> throw Exceptions.newIllegalArgumentException("--hd-console must be wide or centered.");
                        };
                        case "--locale" -> locale = ClientValuesUtils.parseLocale(argument, value);
                        case "--command" -> {
                            // The count of a launch wait is checked here, so the frame loop reads a valid count.
                            getLaunchWaitFrames(value);
                            commands.add(value);
                        }
                        case "--melee" -> meleeMap = layout.resolveUserPath(Path.of(value));
                        case "--ums" -> {
                            meleeMap       = layout.resolveUserPath(Path.of(value));
                            useMapSettings = true;
                        }
                        case "--campaign" -> {
                            meleeMap = Path.of(value);
                            campaign = value;
                        }
                        case "--race" -> meleeRace = value;
                        case "--computer" -> meleeComputers = value;
                        case "--slot" -> meleeSlot = ClientValuesUtils.parseInteger(argument, value);
                        case "--game-speed" -> {
                            meleeSpeed = ClientValuesUtils.parseInteger(argument, value);

                            if (meleeSpeed < 0 || meleeSpeed > GameSpeed.FASTEST) {
                                throw Exceptions.newIllegalArgumentException("--game-speed must be 0..6.");
                            }
                        }
                        case "--name" -> meleeName = value;
                        case "--save" -> meleeSave = layout.resolveUserPath(Path.of(value));
                        case "--pointer" -> {
                            int separator = value.indexOf(COORDINATE_SEPARATOR);

                            if (separator < 0) {
                                throw Exceptions.newIllegalArgumentException("--pointer requires horizontal,vertical framebuffer pixels.");
                            }

                            pointerX = ClientValuesUtils.parseFloat(argument, value.substring(0, separator));
                            pointerY = ClientValuesUtils.parseFloat(argument, value.substring(separator + 1));

                            if (!Float.isFinite(pointerX) || !Float.isFinite(pointerY) || pointerX < 0 || pointerY < 0) {
                                throw Exceptions.newIllegalArgumentException("--pointer coordinates must be finite and not negative.");
                            }
                        }
                        default -> throw Exceptions.newIllegalStateException(argument);
                    }
                }
                default -> throw Exceptions.newIllegalArgumentException("Unknown client option: {0}", argument);
            }
        }

        if (hidden && frameLimit == 0 && !help) {
            throw Exceptions.newIllegalArgumentException("--hidden requires --smoke or --frames to ensure the client exits.");
        }

        if (specifiedOptions.contains("--content-scale") && !hidden && !help) {
            throw Exceptions.newIllegalArgumentException("--content-scale requires --hidden.");
        }

        if (focusUnit >= 0 && replayPath == null && !help) {
            throw Exceptions.newIllegalArgumentException("--focus-unit requires --replay.");
        }

        if (seekFrame >= 0 && (replayPath == null || inspectMap || focusUnit >= 0) && !help) {
            throw Exceptions.newIllegalArgumentException("--seek requires --replay without map or unit inspection.");
        }

        if (playRequested && (replayPath == null || inspectMap || focusUnit >= 0) && !help) {
            throw Exceptions.newIllegalArgumentException("--play requires --replay without map or unit inspection.");
        }

        if (previewStepsSpecified && replayPath == null && !help) {
            throw Exceptions.newIllegalArgumentException("--preview-steps requires --replay.");
        }

        if ((unitFrameSpecified || mirrorUnit) && focusUnit < 0 && !help) {
            throw Exceptions.newIllegalArgumentException("--unit-frame and --mirror-unit require --focus-unit.");
        }

        if (imageAnimation >= 0 && (focusUnit < 0 || unitFrameSpecified || mirrorUnit) && !help) {
            throw Exceptions.newIllegalArgumentException("--image-animation requires --focus-unit and cannot combine with manual frame or " +
                                                         "mirror options.");
        }

        if (imageStepsSpecified && imageAnimation < 0 && !help) {
            throw Exceptions.newIllegalArgumentException("--image-steps requires --image-animation.");
        }

        if ((!Float.isNaN(cameraX) || !Float.isNaN(zoom)) && replayPath == null && !help) {
            throw Exceptions.newIllegalArgumentException("--camera and --zoom require --replay.");
        }

        if (consolePlayer >= 0 && (replayPath == null || inspectMap || focusUnit >= 0) && !help) {
            throw Exceptions.newIllegalArgumentException("--console requires --replay without map or unit inspection.");
        }

        if (!Float.isNaN(pointerX) && replayPath == null && meleeMap == null && !help) {
            throw Exceptions.newIllegalArgumentException("--pointer requires --replay, --melee, --ums or --campaign.");
        }

        // A hidden run opens no tips at a mission start unless its settings file asks for them, as it writes no log file unless asked.
        if (hidden) {
            interfaceSettings.setTipsShown(false);
        }

        // The client's own settings file first (--settings path, else the shared root's default file for visible windows), then only the launch
        // options actually given.
        Path settingsPath = settingsArgument == null
                            ? (hidden ? null : ClientSettings.findDefault(layout))
                            : settingsArgument.equals(NO_SETTINGS_FILE) ? null : layout.resolveUserPath(Path.of(settingsArgument));

        if (settingsPath != null && !help) {
            try {
                ClientSettings settings = ClientSettings.load(settingsPath);

                settings.applyTo(graphics);
                settings.applyTo(sound);
                settings.applyTo(interfaceSettings);

                if (settings.hasRenderer() && !specifiedOptions.contains("--renderer")) {
                    sceneQuality = settings.getRendererQuality();
                }

                if (settings.hasHdSkin() && !specifiedOptions.contains("--hd-skin")) {
                    hdSkin = settings.getHdSkin();
                }

                if (settings.getHdLighting() != null && !specifiedOptions.contains("--hd-lighting")) {
                    hdLighting = settings.getHdLighting();
                }

                if (settings.getAudioSet() != null && !specifiedOptions.contains("--audio")) {
                    audioSet = settings.getAudioSet();
                }

                if (settings.getExtendedUnitLimits() != null) {
                    extendedUnitLimits = settings.getExtendedUnitLimits();
                }

                if (settings.getCustomHotkeys() != null) {
                    customHotkeys = settings.getCustomHotkeys();
                }

                if (settings.getGameSpeed() != null) {
                    gameSpeed = settings.getGameSpeed();
                }

                createGame = settings.getCreateGame();

                if (settings.getMouseConfine() != null && !specifiedOptions.contains("--no-grab")) {
                    noGrab = !settings.getMouseConfine();
                }

                if (settings.getMapCompatibility() != null && !specifiedOptions.contains("--map-compat")) {
                    mapCompatibility = settings.getMapCompatibility();
                }

                if (settings.getHotkeyProfile() != null) {
                    hotkeyProfile = settings.getHotkeyProfile();
                }

                hotkeys.putAll(settings.getHotkeys());

                if (settings.getSpeedIndex() != null && !specifiedOptions.contains("--speed")) {
                    speedIndex = settings.getSpeedIndex();
                }

                if (settings.getLocale() != null && !specifiedOptions.contains("--locale")) {
                    locale = settings.getLocale();
                }

                if (settings.getLogLevel() != null && !specifiedOptions.contains("--log-level")) {
                    logLevel = settings.getLogLevel();
                }

                if (settings.getLogFile() != null) {
                    logFile = settings.getLogFile();
                }

                dataDirectorySetting = settings.getDataDirectory();

                LOGGER.info("SETTINGS: {0}{1}",
                            settingsPath,
                            settings.getIgnored().isEmpty() ? "" : " (unknown keys ignored: " + String.join(", ", settings.getIgnored()) + ")");
            } catch (IOException | RuntimeException exception) {
                LOGGER.warning("SETTINGS: {0} ignored: {1}", settingsPath, exception.getMessage());
            }
        }

        // A window size on the command line and the hidden runs keep a window.
        if (hidden || specifiedOptions.contains("--window")) {
            graphics.setWindowMode(GraphicsSettings.WINDOWED);
        }

        if (requestedVsync != null) {
            graphics.setVsync(requestedVsync);
        }

        if (requestedSmoothTurning != null) {
            graphics.setSmoothTurning(requestedSmoothTurning);
        }

        if (requestedShadowStacking != null) {
            graphics.setShadowStacking(requestedShadowStacking);
        }

        if (requestedEnvironmentEffects != null) {
            graphics.setEnvironmentEffects(requestedEnvironmentEffects);
        }

        if (requestedSdFilterMode != null) {
            graphics.setSdFilterMode(requestedSdFilterMode);
        }

        if (requestedOriginalAspectRatio != null) {
            graphics.setOriginalAspectRatio(requestedOriginalAspectRatio);
        }

        if (requestedGamma != null) {
            graphics.setGamma(requestedGamma);
        }

        if (requestedFrameRateLimit != null) {
            graphics.setFrameRateLimit(requestedFrameRateLimit);
            graphics.setFrameRateLimitEnabled(requestedFrameRateLimit > 0);
        }

        if (dataDirectory == null) {
            dataDirectory = layout.resolveDataDirectory(dataDirectorySetting);
        }

        // --hd-console overrides the settings file's Mini-Map Position (consoleSplit: the wide layout is the split one).
        if (specifiedOptions.contains("--hd-console")) {
            interfaceSettings.setConsoleSplit(!hdConsoleCentered);
        }

        ClientOptions created = new ClientOptions(layout,
                                                  dataDirectory,
                                                  replayPath,
                                                  screenshotPath,
                                                  frameLimit,
                                                  previewSteps,
                                                  focusUnit, unitFrame, mirrorUnit,
                                                  imageAnimation, imageSteps,
                                                  hidden,
                                                  inspectMap || focusUnit >= 0,
                                                  playRequested,
                                                  speedIndex,
                                                  seekFrames,
                                                  help,
                                                  cameraX, cameraY, zoom,
                                                  sceneQuality,
                                                  hdLighting,
                                                  hdSkin,
                                                  consolePlayer, consoleReplayControls,
                                                  pointerX, pointerY,
                                                  debugLayers,
                                                  windowWidth, windowHeight,
                                                  locale,
                                                  commands,
                                                  meleeMap == null
                                                  ? null
                                                  : new Melee(meleeMap, campaign, useMapSettings,
                                                              meleeName, meleeRace, meleeSlot,
                                                              meleeComputers,
                                                              meleeSpeed,
                                                              meleeSave));

        created.contentScale       = contentScale;
        created.noGrab             = noGrab;
        created.inputScript        = inputScript;
        created.mapCompatibility   = mapCompatibility;
        created.hotkeyProfile      = hotkeyProfile;
        created.hotkeys            = Map.copyOf(hotkeys);
        created.audioSet           = audioSet;
        created.extendedUnitLimits = extendedUnitLimits;
        created.customHotkeys      = customHotkeys;
        created.gameSpeed          = gameSpeed;
        created.createGame         = Map.copyOf(createGame);
        // The file the client writes the menus' choices to: the one it reads, or the install's default file even when it does not exist yet; none
        // with --settings none, in hidden runs and for --help.
        created.settingsFile = help ? null : settingsArgument == null ? (hidden ? null : layout.getSettingsFile()) : settingsPath;

        created.graphics          = graphics;
        created.sound             = sound;
        created.interfaceSettings = interfaceSettings;

        created.logLevel = logLevel;
        created.logFile  = logFile != null ? logFile : !hidden;

        return created;
    }

    /**
     * Returns the game frames a launch {@code /wait N} command ({@code --command}) holds the remaining launch commands for.
     *
     * @param command the launch command
     * @return the frame count, or {@code -1} if the command is not a launch wait
     * @throws IllegalArgumentException if the frame count is not a long integer
     */
    public static long getLaunchWaitFrames(@NotNull String command) {
        if (!command.matches(LAUNCH_WAIT_PATTERN)) {
            return NO_LAUNCH_WAIT;
        }

        return ClientValuesUtils.parseLong(COMMAND_OPTION, command.substring(LAUNCH_WAIT_PREFIX.length()));
    }

    private final @NotNull InstallLayout installLayout;

    private @NotNull String hotkeyProfile;
    private @NotNull Map<Integer, Integer> hotkeys;

    private @NotNull SoundPathResolver.AudioSet audioSet;
    private boolean extendedUnitLimits;
    private boolean customHotkeys;
    private int gameSpeed;
    private @NotNull Map<String, Object> createGame;
    private @Nullable Path settingsFile;

    private @NotNull GraphicsSettings graphics;
    private @NotNull SoundSettings sound;
    private @NotNull InterfaceSettings interfaceSettings;

    private @NotNull LogLevel logLevel;
    private boolean logFile;

    private final @NotNull InstallLayout.DataDirectory dataDirectory;

    private final @Nullable Path replayPath;
    private final @Nullable Path screenshotPath;
    private @Nullable Path inputScript;

    private final int frameLimit;
    private final int previewSteps;

    private final int focusUnit;
    private final int unitFrame;
    private final boolean mirrorUnit;
    private final int imageAnimation;
    private final int imageSteps;

    private final boolean hidden;
    private float contentScale;
    private boolean noGrab;
    private boolean mapCompatibility;
    private final boolean inspectMap;
    private final boolean playRequested;
    private final int speedIndex;
    private final long @NotNull [] seekFrames;
    private final boolean help;

    private final float cameraX;
    private final float cameraY;
    private final float zoom;

    private final @Nullable HdRenderAssets.Quality sceneQuality;
    private boolean hdLighting;
    private final @Nullable String hdSkin;

    private final int consolePlayer;
    private final boolean consoleReplayControls;

    private final float pointerX;
    private final float pointerY;
    private final @Nullable String debugLayers;

    private final int windowWidth;
    private final int windowHeight;

    private final @Nullable String locale;
    private final @NotNull List<String> commands;

    private final @Nullable Melee melee;

    /**
     * Creates a {@link ClientOptions} instance. The seek frames and commands are copied.
     *
     * @param installLayout         the selected install layout
     * @param dataDirectory         the data directory and its configuration source
     * @param replayPath            the replay path, or {@code null} when absent
     * @param screenshotPath        the screenshot path, or {@code null} when absent
     * @param frameLimit            the simulation frame limit
     * @param previewSteps          the preview step count
     * @param focusUnit             the focused unit identifier
     * @param unitFrame             the unit preview frame
     * @param mirrorUnit            whether the unit preview is mirrored
     * @param imageAnimation        the image animation identifier
     * @param imageSteps            the image animation step count
     * @param hidden                whether the client runs without a visible window
     * @param inspectMap            whether map inspection is enabled
     * @param playRequested         whether replay playback starts immediately
     * @param speedIndex            the replay playback speed index
     * @param seekFrames            the seek frames, copied on construction
     * @param help                  whether usage help was requested
     * @param cameraX               the initial camera x-coordinate
     * @param cameraY               the initial camera y-coordinate
     * @param zoom                  the initial camera zoom
     * @param sceneQuality          the scene asset quality, or {@code null} for classic graphics
     * @param hdLighting            whether HD lighting is enabled
     * @param hdSkin                the HD skin, or {@code null} for the default animations
     * @param consolePlayer         the console player index
     * @param consoleReplayControls whether the console shows replay controls
     * @param pointerX              the initial pointer x-coordinate
     * @param pointerY              the initial pointer y-coordinate
     * @param debugLayers           the requested debug layers, or {@code null} when absent
     * @param windowWidth           the initial window width
     * @param windowHeight          the initial window height
     * @param locale                the requested locale, or {@code null} when absent
     * @param commands              the startup commands, copied on construction
     * @param melee                 the game creation options, or {@code null} when absent
     */
    private ClientOptions(@NotNull InstallLayout installLayout,
                          @NotNull InstallLayout.DataDirectory dataDirectory,
                          @Nullable Path replayPath,
                          @Nullable Path screenshotPath,
                          int frameLimit,
                          int previewSteps,
                          int focusUnit, int unitFrame, boolean mirrorUnit,
                          int imageAnimation, int imageSteps,
                          boolean hidden,
                          boolean inspectMap,
                          boolean playRequested,
                          int speedIndex,
                          long @NotNull [] seekFrames,
                          boolean help,
                          float cameraX, float cameraY, float zoom,
                          @Nullable HdRenderAssets.Quality sceneQuality,
                          boolean hdLighting,
                          @Nullable String hdSkin,
                          int consolePlayer, boolean consoleReplayControls,
                          float pointerX, float pointerY,
                          @Nullable String debugLayers,
                          int windowWidth, int windowHeight,
                          @Nullable String locale,
                          @NotNull List<String> commands,
                          @Nullable Melee melee) {
        this.installLayout = installLayout;

        this.hotkeyProfile = HotkeyProfile.DEVICE;
        this.hotkeys       = Map.of();

        this.audioSet           = SoundPathResolver.AudioSet.REMASTERED;
        this.extendedUnitLimits = true;
        this.customHotkeys      = true;
        this.gameSpeed          = DEFAULT_GAME_SPEED;
        this.createGame         = Map.of();
        this.settingsFile       = null;

        this.graphics          = new GraphicsSettings();
        this.sound             = new SoundSettings();
        this.interfaceSettings = new InterfaceSettings();

        this.logLevel = LogLevel.INFO;
        this.logFile  = false;

        this.dataDirectory = dataDirectory;

        this.replayPath     = replayPath;
        this.screenshotPath = screenshotPath;
        this.inputScript    = null;

        this.frameLimit   = frameLimit;
        this.previewSteps = previewSteps;

        this.focusUnit      = focusUnit;
        this.unitFrame      = unitFrame;
        this.mirrorUnit     = mirrorUnit;
        this.imageAnimation = imageAnimation;
        this.imageSteps     = imageSteps;

        this.hidden           = hidden;
        this.contentScale     = 1;
        this.noGrab           = false;
        this.mapCompatibility = false;
        this.inspectMap       = inspectMap;
        this.playRequested    = playRequested;
        this.speedIndex       = speedIndex;
        this.seekFrames       = seekFrames.clone();
        this.help             = help;

        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.zoom    = zoom;

        this.sceneQuality = sceneQuality;
        this.hdLighting   = hdLighting;
        this.hdSkin       = hdSkin;

        this.consolePlayer         = consolePlayer;
        this.consoleReplayControls = consoleReplayControls;

        this.pointerX    = pointerX;
        this.pointerY    = pointerY;
        this.debugLayers = debugLayers;

        this.windowWidth  = windowWidth;
        this.windowHeight = windowHeight;

        this.locale   = locale;
        this.commands = List.copyOf(commands);

        this.melee = melee;
    }

    /**
     * @return the game data directory selected by launch arguments, settings, the data property or the install default
     */
    public @NotNull Path getGameDirectory() {
        return this.dataDirectory.getPath();
    }

    /**
     * @return the data directory with the setting it came from
     */
    @NotNull InstallLayout.DataDirectory getDataDirectory() {
        return this.dataDirectory;
    }

    /**
     * @return the selected shared root and its settings, maps, replays, logs and native cache paths
     */
    public @NotNull InstallLayout getInstallLayout() {
        return this.installLayout;
    }

    /**
     * @return the requested replay path, or {@code null}
     */
    public @Nullable Path getReplayPath() {
        return this.replayPath;
    }

    /**
     * @return the screenshot output path, or {@code null}
     */
    public @Nullable Path getScreenshotPath() {
        return this.screenshotPath;
    }

    /**
     * @return the test-only input script ({@code --input-script}, {@link InputScript}), or {@code null}
     */
    public @Nullable Path getInputScript() {
        return this.inputScript;
    }

    /**
     * @return the configured frame limit
     */
    public int getFrameLimit() {
        return this.frameLimit;
    }

    /**
     * @return the configured preview step count
     */
    public int getPreviewSteps() {
        return this.previewSteps;
    }

    /**
     * @return the CHK placement index of the unit selected for preview, or {@code -1} for none
     */
    public int getFocusUnit() {
        return this.focusUnit;
    }

    /**
     * @return the configured unit preview frame
     */
    public int getUnitFrame() {
        return this.unitFrame;
    }

    /**
     * @return the image animation selected for preview, or {@code -1} for none
     */
    public int getImageAnimation() {
        return this.imageAnimation;
    }

    /**
     * @return the configured image preview step count
     */
    public int getImageSteps() {
        return this.imageSteps;
    }

    /**
     * @return {@code true} if the unit preview is mirrored, {@code false} otherwise
     */
    public boolean isMirrorUnit() {
        return this.mirrorUnit;
    }

    /**
     * @return {@code true} if the client window is hidden, {@code false} otherwise
     */
    public boolean isHidden() {
        return this.hidden;
    }

    /**
     * @return the display scale of the hidden window's monitor ({@code --content-scale}, default {@code 1}): the hidden window is its size times
     * this scale in pixels, as a visible window is on a monitor of that scale
     */
    public float getContentScale() {
        return this.contentScale;
    }

    /**
     * @return {@code true} if replay playback starts at game speed after loading and the client exits when it stops, {@code false} otherwise
     */
    public boolean isPlayRequested() {
        return this.playRequested;
    }

    /**
     * @return the initial playback speed index
     */
    public int getSpeedIndex() {
        return this.speedIndex;
    }

    /**
     * @return the completed frame to reach after loading, or {@code -1} for none
     */
    public long getSeekFrame() {
        return this.seekFrames.length == 0 ? -1 : this.seekFrames[0];
    }

    /**
     * Returns the timeline targets from {@code --seek}, reached one after another in either direction.
     *
     * @return the targets in command-line order, empty for none
     */
    public long @NotNull [] getSeekFrames() {
        return this.seekFrames.clone();
    }

    /**
     * @return {@code true} if map inspection is requested, {@code false} otherwise
     */
    public boolean isInspectMap() {
        return this.inspectMap;
    }

    /**
     * @return {@code true} if command-line help is requested, {@code false} otherwise
     */
    public boolean isHelp() {
        return this.help;
    }

    /**
     * @return the requested initial world center x-coordinate, or {@link Float#NaN} when the map is fitted
     */
    public float getCameraX() {
        return this.cameraX;
    }

    /**
     * @return the requested initial world center y-coordinate, or {@link Float#NaN} when the map is fitted
     */
    public float getCameraY() {
        return this.cameraY;
    }

    /**
     * @return the requested initial screen pixels per world pixel, or {@link Float#NaN} for the default
     */
    public float getZoom() {
        return this.zoom;
    }

    /**
     * @return the HD scene asset quality selected by {@code --renderer} ({@code hd} by default), or {@code null} for the classic renderer
     */
    public @Nullable HdRenderAssets.Quality getSceneQuality() {
        return this.sceneQuality;
    }

    /**
     * @return {@code true} if the HD renderer uses deferred lighting, {@code false} otherwise ({@code --hd-lighting on} by default)
     */
    public boolean isHdLighting() {
        return this.hdLighting;
    }

    /**
     * Sets the HD lighting path (the options popup's Real-Time Lighting); the client applies it at once.
     *
     * @param hdLighting {@code true} for the deferred lighting path
     */
    public void setHdLighting(boolean hdLighting) {
        this.hdLighting = hdLighting;
    }

    /**
     * @return the HD unit skin folder selected by {@code --hd-skin} ({@code PreSale} or {@code Carbot}), or {@code null} for the default animations
     */
    public @Nullable String getHdSkin() {
        return this.hdSkin;
    }

    /**
     * @return the player whose in-game console is shown from startup, or {@code -1} for none
     */
    public int getConsolePlayer() {
        return this.consolePlayer;
    }

    /**
     * @return {@code true} if the console shows replay controls instead of the player's command card, {@code false} otherwise
     */
    public boolean isConsoleReplayControls() {
        return this.consoleReplayControls;
    }

    /**
     * @return the fixed pointer column in framebuffer pixels that stands in for the mouse (screenshots of the game cursor), or {@link Float#NaN} to
     * follow the mouse
     */
    public float getPointerX() {
        return this.pointerX;
    }

    /**
     * @return the fixed pointer row in framebuffer pixels, or {@link Float#NaN} to follow the mouse
     */
    public float getPointerY() {
        return this.pointerY;
    }

    /**
     * @return the debug layers to show from startup as a comma-separated list of {@code /debug} arguments (for example {@code info} or
     * {@code path,collision,regions}), or {@code null} to start without the debug view
     */
    public @Nullable String getDebugLayers() {
        return this.debugLayers;
    }

    /**
     * @return the window width in pixels
     */
    public int getWindowWidth() {
        return this.windowWidth;
    }

    /**
     * @return the window height in pixels
     */
    public int getWindowHeight() {
        return this.windowHeight;
    }

    /**
     * @return the game locale such as {@code koKR} for interface text and speech ({@code --locale}), or {@code null} for the installed locale
     */
    public @Nullable String getLocale() {
        return this.locale;
    }

    /**
     * @return the prompt commands ({@code --command}, repeatable) run after each command-line seek, for example
     * {@code /shot file.png} for a screenshot
     */
    public @NotNull List<String> getCommands() {
        return this.commands;
    }

    /**
     * @return the melee game to start instead of a replay, or {@code null}
     */
    public @Nullable Melee getMelee() {
        return this.melee;
    }

    /**
     * @return the active hotkey profile ({@code input.hotkeyProfile}, default {@code $device})
     */
    public @NotNull String getHotkeyProfile() {
        return this.hotkeyProfile;
    }

    /**
     * @return the hotkey overrides of the settings file by {@code stat_txt} identifier ({@code input.hotkeys})
     */
    public @NotNull Map<Integer, Integer> getHotkeys() {
        return this.hotkeys;
    }

    /**
     * Sets the active hotkey profile and its key mapping for the next game.
     *
     * @param hotkeyProfile the active profile name
     * @param hotkeys       the profile keys by {@code stat_txt} identifier
     */
    public void setHotkeys(@NotNull String hotkeyProfile, @NotNull Map<Integer, Integer> hotkeys) {
        this.hotkeyProfile = hotkeyProfile;
        this.hotkeys       = Map.copyOf(hotkeys);
    }

    /**
     * @return {@code true} if maps load with the relaxed {@code 1.16}-era rules ({@code --map-compat}, settings {@code maps.compatibility}),
     * {@code false} otherwise (the native rules)
     */
    public boolean isMapCompatibility() {
        return this.mapCompatibility;
    }

    /**
     * @return {@code true} if the cursor is left free ({@code --no-grab}, for debugging), {@code false} otherwise (confined to the window)
     */
    public boolean isNoGrab() {
        return this.noGrab;
    }

    /**
     * Sets whether the cursor is left free: the options' Use Mouse Confine and Alt+F1 ({@code input.mouseConfine}) change it while the client runs.
     *
     * @param noGrab whether the cursor is left free
     */
    public void setNoGrab(boolean noGrab) {
        this.noGrab = noGrab;
    }

    /**
     * @return the selected remastered or classic sound set, independent of the renderer
     */
    public @NotNull SoundPathResolver.AudioSet getAudioSet() {
        return this.audioSet;
    }

    /**
     * @return the configured game speed, or the default speed when no setting overrides it
     */
    public int getGameSpeed() {
        return this.gameSpeed;
    }

    /**
     * Sets the speed of the games started from the menus (the options popup's Game Speed).
     *
     * @param gameSpeed the speed {@code 0..6}
     */
    public void setGameSpeed(int gameSpeed) {
        this.gameSpeed = gameSpeed;
    }

    /**
     * @return {@code true} if created games use extended unit limits, {@code false} otherwise (classic limits)
     */
    public boolean isExtendedUnitLimits() {
        return this.extendedUnitLimits;
    }

    /**
     * @return {@code true} if created games allow player hotkey profiles, {@code false} otherwise (default hotkeys)
     */
    public boolean isCustomHotkeys() {
        return this.customHotkeys;
    }

    /**
     * @return the create screen values read from the settings file's {@code createGame} group ({@link ClientSettings#getCreateGame})
     */
    public @NotNull Map<String, Object> getCreateGame() {
        return this.createGame;
    }

    /**
     * @return the settings file the client writes the menus' choices to ({@link SettingsStore#write}), or {@code null} when writing is off
     * ({@code --settings none}, hidden runs)
     */
    public @Nullable Path getSettingsFile() {
        return this.settingsFile;
    }

    /**
     * @return the log threshold: {@code --log-level}, else the settings file's {@code logging.level}, else {@link LogLevel#INFO}
     */
    public @NotNull LogLevel getLogLevel() {
        return this.logLevel;
    }

    /**
     * @return {@code true} if the rolling log file under {@code logs/} is written, {@code false} otherwise; the settings file's {@code logging.file}
     * selects it, defaulting to {@code true} for visible windows and {@code false} for {@code --hidden} runs
     */
    public boolean isLogFileEnabled() {
        return this.logFile;
    }

    /**
     * @return the current display pacing and graphics options
     */
    public @NotNull GraphicsSettings getGraphicsSettings() {
        return this.graphics;
    }

    /**
     * @return the current sound options
     */
    public @NotNull SoundSettings getSoundSettings() {
        return this.sound;
    }

    /**
     * @return the in-game interface settings (scroll speeds, frame rate and turn rate display), from the settings file or the native defaults
     */
    public @NotNull InterfaceSettings getInterfaceSettings() {
        return this.interfaceSettings;
    }

    /**
     * Checks that the configured data pack is present and returns its directory.
     *
     * @return the data directory
     * @throws IOException if the data directory holds no data pack; the message names the path tried and how to set it
     */
    public @NotNull Path requireGameDirectory() throws IOException {
        if (!this.dataDirectory.isPresent()) {
            throw Exceptions.newIOException(this.dataDirectory.describeMissing());
        }

        return this.dataDirectory.getPath();
    }
}
