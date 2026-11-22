package org.fogofwarlabs.sc4j.scr.client.glue;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.dialog.DialogStack;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextEditOperation;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.PointerButton;
import org.fogofwarlabs.sc4j.engine.client.scene.SceneStack;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.ClientContext;
import org.fogofwarlabs.sc4j.scr.client.TextTables;
import org.fogofwarlabs.sc4j.scr.client.configuration.ClientSettings;
import org.fogofwarlabs.sc4j.scr.client.cursor.GameCursor;
import org.fogofwarlabs.sc4j.scr.client.glue.campaign.*;
import org.fogofwarlabs.sc4j.scr.client.glue.menu.MainMenuScreen;
import org.fogofwarlabs.sc4j.scr.client.glue.menu.NativeGlueScenesUtils;
import org.fogofwarlabs.sc4j.scr.client.glue.menu.TitleScreen;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.GlueFatalPopup;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.GlueLoadPopup;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.GlueSavePopup;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.OptionsPopup;
import org.fogofwarlabs.sc4j.scr.client.glue.score.GlueResume;
import org.fogofwarlabs.sc4j.scr.client.glue.score.GlueScoreScreen;
import org.fogofwarlabs.sc4j.scr.client.glue.score.ScoreScreen;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialogLayer;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.game.session.setup.CampaignMissions;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFonts;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Runs the out-of-game screen flow, drawing and input on client resources and queues requests to start games or exit.
 * <p>
 * The window draws each frame and polls outgoing requests. Screen changes and errors are applied on the next frame;
 * modal glue popups may also run over a game.
 */
public final class GlueInterface implements AutoCloseable {
    private static final @NotNull Logger LOGGER = Logger.of(GlueInterface.class);
    private static final float NOT_LOADING = -1;
    /**
     * The message key for a next scenario that cannot be loaded.
     */
    private static final @NotNull String CAMPAIGN_LINK_ERROR = "strERROR_CAMPAIGN_LINK";

    /**
     * The palette folder names indexed by screen palette identifiers.
     */
    private static final @NotNull List<String> PALETTE_FOLDERS = List.of(
            "palmm", "palcs",
            "palrt", "palrz", "palrp",
            "palpd", "palpv", "palzd", "palzv", "paltd", "paltv",
            "palnl", "battlenet"
    );

    /**
     * The empty screen under the glue popups shown over a game: no dialog, no background, nothing drawn.
     */
    private static final class OverlayScreen extends GlueScreen {
        private OverlayScreen() {
            super("", SHARED_PALETTE_FOLDER, null);
        }

        /**
         * Called when a control is activated by a click, its hotkey, Esc or Enter.
         * <p>
         * The overlay has no controls.
         *
         * @param control the control id
         */
        @Override
        protected void onActivate(int control) {
        }
    }

    /**
     * Follows the switches of the glue's scene stack: the dialog list holds the current screen's layer, the palette identifier is
     * the current screen's, and the queued screen error shows over a screen once it is open.
     */
    private final class SceneListener implements SceneStack.Listener<GlueScreen> {
        /**
         * Called after a scene became current: the stack opened it and closed the left scene. The new screen's keyboard focus sets the text
         * input again, which the left screen turned off when it closed.
         *
         * @param previous the left scene, or {@code null} for the first scene
         * @param next     the new current scene
         */
        @Override
        public void onSwitched(@Nullable GlueScreen previous, @NotNull GlueScreen next) {
            if (previous != null) {
                GlueInterface.this.context.getDialogStack().remove(previous.getLayer());
            }

            GlueInterface.this.context.getDialogStack().push(next.getLayer());
            next.restoreTextInput();

            GlueInterface.this.gluePalette = getPaletteIdentifier(next);

            GlueInterface.this.showScreenError();
        }

        /**
         * Called when a scene the stack switches to fails to open; the current scene stays.
         *
         * @param scene     the scene that failed
         * @param exception what its open threw
         */
        @Override
        public void onOpenFailed(@NotNull GlueScreen scene, @NotNull Exception exception) {
            LOGGER.warning("GLUE SCREEN FAILED: " + exception.getMessage());
        }
    }

    /**
     * Routes the navigation of the glue screens to this interface; a screen change applies on the next frame.
     */
    private final class Navigator implements GlueNavigator {
        /**
         * Replaces the current screen.
         *
         * @param destination the next screen
         */
        @Override
        public void show(@NotNull Destination destination) {
            GlueInterface.this.stack.replace(GlueInterface.this.scenes.create(GlueInterface.this.context, destination));
        }

        /**
         * Replaces the current screen with a screen the caller created, for a step of the flow that is not a destination of
         * its own (for example the credits after the ending).
         *
         * @param screen the next screen
         */
        @Override
        public void replace(@NotNull GlueScreen screen) {
            GlueInterface.this.stack.replace(screen);
        }

        /**
         * Continues the campaign walk at an episode entry: a cinematic entry plays its clip and continues with the next
         * entry, a mission entry opens its ready room, the end of the table returns to the campaign screen.
         *
         * @param entry the entry, or {@code null} at the end of the episode
         */
        @Override
        public void playCampaign(CampaignEpisodes.@Nullable Entry entry) {
            GlueInterface.this.stack.replace(GlueInterface.this.createCampaignScreen(entry));
        }

        /**
         * Leaves the glue for a game.
         *
         * @param request the game to start
         */
        @Override
        public void start(@NotNull GlueGameRequest request) {
            GlueInterface.this.gameRequest = request;
        }

        /**
         * Opens the ready room for a custom Use Map Settings game; its Start button starts the game.
         *
         * @param request the custom game
         */
        @Override
        public void openReadyRoom(@NotNull GlueGameRequest request) {
            try {
                GlueInterface.this.stack.replace(ReadyRoomScreen.createForCustomGame(request, GlueInterface.this.context.isMapCompatibility()));
            } catch (IOException | RuntimeException exception) {
                // The map was read when it was chosen; a failure now leaves the start to the game's own map check.
                LOGGER.warning("READY ROOM UNAVAILABLE: " + exception.getMessage());
                GlueInterface.this.gameRequest = request;
            }
        }

        /**
         * Leaves the program (main menu Exit).
         */
        @Override
        public void exit() {
            GlueInterface.this.exitRequested = true;
        }

        /**
         * Shows an open popup over the current screen as the top modal scene of the glue's scene stack, which updates and draws it.
         *
         * @param popup the open popup
         */
        @Override
        public void addPopup(@NotNull GlueScreen popup) {
            GlueInterface.this.stack.addModal(popup);
        }

        /**
         * Closes a popup shown with {@link #addPopup}; does nothing if the scene stack closed it already.
         *
         * @param popup the popup
         */
        @Override
        public void closePopup(@NotNull GlueScreen popup) {
            GlueInterface.this.stack.closeModal(popup);
        }
    }

    /**
     * Creates a {@link GlueInterface} instance that opens the glue with the title screen on the client's resources.
     * Needs the window's OpenGL context.
     *
     * @param client  the client resources (storage, interface renderer, fonts, cursor), which stay open after the glue
     * @param locale  the text locale, for example {@code enUS}
     * @param folders the install folders of IDs, maps and replays
     * @param resume  the screen to continue at after a game, or {@code null} to start at the title screen
     * @return the created {@link GlueInterface} instance
     * @throws IOException if the glue assets cannot be read
     */
    public static @NotNull GlueInterface open(@NotNull ClientContext client,
                                              @NotNull String locale,
                                              @NotNull GlueFolders folders,
                                              @Nullable GlueResume resume) throws IOException {
        return open(client, locale, folders, resume, GlueSettings.createInMemory());
    }

    /**
     * Creates a {@link GlueInterface} instance that opens the glue with the title screen on the client's resources and the menu
     * choices kept across starts.
     *
     * @param client   the client resources, which stay open after the glue
     * @param locale   the text locale, for example {@code enUS}
     * @param folders  the install folders of IDs, maps and replays
     * @param resume   the screen to continue at after a game, or {@code null} to start at the title screen
     * @param settings the menu choices kept across starts
     * @return the created {@link GlueInterface} instance
     * @throws IOException if the glue assets cannot be read
     */
    public static @NotNull GlueInterface open(@NotNull ClientContext client,
                                              @NotNull String locale,
                                              @NotNull GlueFolders folders,
                                              @Nullable GlueResume resume,
                                              @NotNull GlueSettings settings) throws IOException {
        CascStorage storage = client.getStorage();
        GlueCanvas canvas = null;
        GlueAudio audio = null;

        try {
            canvas = new GlueCanvas(client.getResources(),
                                    client.getInterfaceRenderer(),
                                    locale,
                                    client.getGlueFonts(), client.getGameFonts(),
                                    client.getSheetFrameRecords());
            audio  = new GlueAudio(storage, client.getMusic(), client.getAudio());

            TextTables text = client.getTextTables(locale);

            return new GlueInterface(client,
                                     canvas,
                                     audio,
                                     locale,
                                     text.getControl(), text.getGluText(), text.getNetwork(),
                                     CampaignMissionNames.load(storage, locale),
                                     loadFlicLayout(storage),
                                     folders,
                                     resume,
                                     settings);
        } catch (IOException | RuntimeException exception) {
            if (audio != null) {
                audio.close();
            }

            if (canvas != null) {
                canvas.close();
            }

            throw exception;
        }
    }

    private static @NotNull GlueFlicLayout loadFlicLayout(@NotNull CascStorage storage) {
        try {
            return GlueFlicLayout.load(storage);
        } catch (IOException exception) {
            LOGGER.warning("GLUE VIDEO PLACEMENTS UNAVAILABLE: " + exception.getMessage());

            return GlueFlicLayout.createEmpty();
        }
    }

    private static void advanceProgress(@NotNull CharacterFile character, boolean expansion, CampaignEpisodes.@NotNull Entry entry) {
        try {
            CampaignScreen.advanceProgress(character, expansion, entry);
        } catch (IOException exception) {
            CampaignScreen.logSaveFailure(LOGGER, character, exception);
        }
    }

    /**
     * Finds the palette identifier of a screen from its palette folder name.
     *
     * @param screen the screen
     * @return the palette folder's index, or {@code 0} when unknown
     */
    private static int getPaletteIdentifier(@NotNull GlueScreen screen) {
        String folder = screen.getPaletteFolder().toLowerCase(Locale.ROOT);
        int index = PALETTE_FOLDERS.indexOf(folder.substring(folder.lastIndexOf('/') + 1));

        return Math.max(index, 0);
    }

    private final @NotNull InterfaceRenderer renderer;
    private final @NotNull GlueCanvas canvas;
    private final @NotNull GlueAudio audio;
    private final @NotNull GlueContext context;
    private final @NotNull GlueSceneRegistry scenes;

    private @NotNull String qualityPrefix;
    private int targetFramebuffer;
    private int targetWidth;
    private int targetHeight;

    private boolean overGame;
    private final @NotNull LoadingPanel loadingPanel;
    private @Nullable String loadingCaption;
    private float loadingProgress;

    /**
     * The glue's screens: the current screen, the screen requested next (applied on the next frame) and the title screen kept
     * open when it is left.
     */
    private final @NotNull SceneStack<GlueContext, GlueCanvas, GlueScreen> stack;
    private boolean exitRequested;
    private @Nullable GlueGameRequest gameRequest;
    private @Nullable String pendingError;
    /**
     * The {@code network.json} key of a message the next screen that becomes current shows over itself once it is open.
     */
    private @Nullable String screenError;

    private final @NotNull TitleScreen titleScreen;
    /**
     * The palette identifier of the last screen entered.
     */
    private int gluePalette;

    private final @NotNull GameCursor cursor;
    private float pointerX;
    private float pointerY;
    private boolean pointerInside;
    private boolean clientLoading;

    private GlueInterface(@NotNull ClientContext client,
                          @NotNull GlueCanvas canvas,
                          @NotNull GlueAudio audio,
                          @NotNull String locale,
                          @NotNull GluText controlText, @NotNull GluText gluText, @NotNull GluText networkText,
                          @NotNull CampaignMissionNames missionNames,
                          @NotNull GlueFlicLayout flicLayout,
                          @NotNull GlueFolders folders,
                          @Nullable GlueResume resume,
                          @NotNull GlueSettings settings) throws IOException {
        this.renderer = client.getInterfaceRenderer();
        this.canvas   = canvas;
        this.audio    = audio;
        this.context  = new GlueContext(client,
                                        canvas,
                                        audio,
                                        locale,
                                        controlText, gluText, networkText,
                                        missionNames,
                                        new Navigator(),
                                        flicLayout,
                                        folders,
                                        settings);
        this.scenes   = new GlueSceneRegistry();

        NativeGlueScenesUtils.register(this.scenes);

        this.qualityPrefix     = GlueCanvas.SD_PREFIX;
        this.targetFramebuffer = 0;
        this.targetWidth       = 0;
        this.targetHeight      = 0;

        this.overGame        = false;
        this.loadingPanel    = LoadingPanel.load(this.context);
        this.loadingCaption  = null;
        this.loadingProgress = NOT_LOADING;

        this.stack         = new SceneStack<>(this.context, new SceneListener());
        this.exitRequested = false;
        this.gameRequest   = null;
        this.pendingError  = null;
        this.screenError   = null;

        this.titleScreen = new TitleScreen();

        // The resume screen may queue a screen error, which the first screen shows once it is open.
        GlueScreen first = resume == null ? this.titleScreen : this.createResumeScreen(resume);

        this.stack.setInputRouter(this::findInputTarget);
        this.stack.setRetainedScene(this.titleScreen);
        this.stack.start(first);

        this.cursor        = client.getCursor();
        this.pointerX      = 0;
        this.pointerY      = 0;
        this.pointerInside = false;
        this.clientLoading = false;
    }

    /**
     * @return {@code true} if a game runs under the glue ({@link #enterGame}), {@code false} otherwise
     */
    public boolean isOverGame() {
        return this.overGame;
    }

    /**
     * @return {@code true} if Exit was chosen, {@code false} otherwise
     */
    public boolean isExitRequested() {
        return this.exitRequested;
    }

    /**
     * @return {@code true} if a popup is open over the current screen (over a game: the glue takes the input), {@code false} otherwise
     */
    public boolean isPopupOpen() {
        return this.getScreen().hasPopup();
    }

    /**
     * @return the open modal popup layers, ordered from bottom to top
     */
    public @NotNull List<ScrDialogLayer> getPopupLayers() {
        List<ScrDialogLayer> layers = new ArrayList<>();

        for (GlueScreen popup = this.getScreen().getPopup(); popup != null; popup = popup.getPopup()) {
            layers.add(popup.getLayer());
        }

        return layers;
    }

    /**
     * @return the palette identifier of the last screen entered
     */
    public int getGluePalette() {
        return this.gluePalette;
    }

    /**
     * @return the canvas the screens draw on, with the interface placement of the last drawn frame
     */
    public @NotNull GlueCanvas getCanvas() {
        return this.canvas;
    }


    /**
     * Sets strict or relaxed map compatibility rules for the map browser.
     *
     * @param mapCompatibility {@code true} for the relaxed {@code 1.16}-era map rules
     */
    public void setMapCompatibility(boolean mapCompatibility) {
        this.context.setMapCompatibility(mapCompatibility);
    }

    /**
     * Sets the game (Brood War or the original) as the main menu's choice does, for a screen the test harness opens
     * directly ({@code -Dsc4j.glueScreen}).
     *
     * @param expansion {@code true} for Brood War
     */
    public void setExpansion(boolean expansion) {
        this.context.setExpansion(expansion);
    }

    /**
     * Sets the asset set of the glue ({@link GlueCanvas#SD_PREFIX}, {@link GlueCanvas#HD2_PREFIX} or
     * {@link GlueCanvas#HD_PREFIX}), following the game's graphics mode.
     *
     * @param prefix the asset set prefix
     */
    public void setQualityPrefix(@NotNull String prefix) {
        this.qualityPrefix = prefix;
    }

    /**
     * Sets another framebuffer to draw into instead of the window (the SD target of SD graphics): the glue keeps laying
     * out, hit testing and drawing in window coordinates, which map onto the whole target.
     *
     * @param framebuffer the OpenGL framebuffer name, or {@code 0} for the window
     * @param width       the target width in pixels, or {@code 0} for the window's
     * @param height      the target height in pixels, or {@code 0} for the window's
     */
    public void setTargetFramebuffer(int framebuffer, int width, int height) {
        this.targetFramebuffer = framebuffer;
        this.targetWidth       = width;
        this.targetHeight      = height;
    }

    /**
     * Sets the loading panel ({@link LoadingPanel}) shown over the current screen, or hides it; the title screen's own
     * blinking "Loading" gives way to it.
     *
     * @param caption  what loads, for example {@code HD2}, or {@code null}
     * @param progress the progress {@code 0..1}, or a negative value to hide the panel
     */
    public void setLoading(@Nullable String caption, float progress) {
        this.loadingCaption  = caption;
        this.loadingProgress = progress < 0 ? NOT_LOADING : progress;

        this.titleScreen.setLoadingTextShown(progress < 0);
    }

    /**
     * Sets whether the client's loading runs (the title screen waits for it).
     *
     * @param loading whether loading tasks remain
     */
    public void setClientLoading(boolean loading) {
        this.clientLoading = loading;
    }

    /**
     * Sets the place of the drawn cursor without moving the pointer of the screens (for example on the first frame after a
     * game, and while the window hides the system cursor); it is shown while the pointer is over the window.
     *
     * @param screenX the framebuffer x
     * @param screenY the framebuffer y
     * @param inside  whether the pointer is over the window
     */
    public void setPointer(float screenX, float screenY, boolean inside) {
        this.pointerX      = screenX;
        this.pointerY      = screenY;
        this.pointerInside = inside;
    }

    /**
     * Sets the palette identifier an EUD write stored; the next screen entry replaces it.
     *
     * @param palette the palette identifier
     */
    public void setGluePalette(int palette) {
        this.gluePalette = palette;
    }


    /**
     * Switches to an empty screen as a game starts: the glue stays open, with a screen that draws nothing and does not clear
     * the framebuffer, so the same dialog runtime shows its popups over the game view (the options popup, {@link #showOptions},
     * and the loading panel, {@link #setLoading}); the window draws it after the game frame and gives it the input while
     * {@link #isPopupOpen} (a modal dialog). The game's start ends the glue music itself (the client's {@code Music}).
     */
    public void enterGame() {
        GlueScreen empty = new OverlayScreen();

        this.stack.switchTo(empty);

        this.stack.clearPendingScene();
        this.overGame = true;
    }

    /**
     * Shows the glue menus again after the game has ended, from the screen the game was started from.
     *
     * @param resume the screen to continue at (the score screen first when it has one)
     */
    public void leaveGame(@NotNull GlueResume resume) {
        this.overGame = false;

        this.stack.switchTo(this.createResumeScreen(resume));

        this.stack.clearPendingScene();
    }

    /**
     * Shows a screen directly (developer path for screenshot runs; the menus normally reach it through the navigator).
     *
     * @param destination the screen
     */
    public void showScreen(GlueNavigator.@NotNull Destination destination) {
        this.stack.replace(this.scenes.create(this.context, destination));
    }

    /**
     * Takes the game the glue asked for.
     *
     * @return the request, or {@code null} if none is pending
     */
    public @Nullable GlueGameRequest takeGameRequest() {
        GlueGameRequest request = this.gameRequest;

        this.gameRequest = null;

        return request;
    }

    /**
     * Queues a game-start error for display over the main menu after the title screen.
     *
     * @param networkKey the {@code network.json} key of the message, for example {@code strERROR_LOAD_MAP}
     */
    public void showError(@NotNull String networkKey) {
        this.pendingError = networkKey;
    }


    /**
     * Advances and draws the current screen over the whole framebuffer.
     *
     * @param surface the interface surface of the frame: the framebuffer and its logical units
     * @param time    the time in milliseconds
     */
    public void draw(@NotNull UiSurface surface, long time) {
        int framebufferWidth = surface.getFramebufferWidth();
        int framebufferHeight = surface.getFramebufferHeight();

        this.stack.applyPendingScene(time);

        // Pump timers before updating the current screen.
        this.context.getTimers().pump((int) time);
        this.stack.update(time);

        if (this.getScreen() == this.titleScreen && this.titleScreen.isFinished()) {
            // A run without an account chooses its local ID first.
            GlueNavigator.Destination destination = this.context.hasPlayer() ? GlueNavigator.Destination.MAIN_MENU
                                                                             : GlueNavigator.Destination.LOGIN;

            this.stack.switchTo(this.scenes.create(this.context, destination));
        }

        if (this.pendingError != null && this.getScreen() != this.titleScreen && !(this.getScreen() instanceof MainMenuScreen)) {
            // Show failed-start messages over the main menu.
            GlueNavigator.Destination destination = GlueNavigator.Destination.MAIN_MENU;

            this.stack.switchTo(this.scenes.create(this.context, destination));
        }

        if (this.pendingError != null && this.getScreen() instanceof MainMenuScreen) {
            String message = this.context.getNetworkText().get(this.pendingError);

            this.pendingError = null;
            this.getScreen().showError(message);
        }

        // Over a game the scene passes may leave another framebuffer, viewport or clip set; the glue draws into its own,
        // in window coordinates mapped onto the target (the pointer and the hit tests use the same coordinates).
        this.renderer.begin(this.targetFramebuffer,
                            this.targetWidth > 0 ? this.targetWidth : framebufferWidth,
                            this.targetHeight > 0 ? this.targetHeight : framebufferHeight,
                            framebufferWidth, framebufferHeight);
        this.canvas.setClip(0, 0, GlueCanvas.NO_CLIP_SIZE, GlueCanvas.NO_CLIP_SIZE);
        this.canvas.setViewport(this.qualityPrefix, surface,
                                Boolean.TRUE.equals(this.context.getSettings().getOption(ClientSettings.ORIGINAL_ASPECT_RATIO_KEY)));

        if (!this.overGame) {
            this.canvas.clear();
        }

        try {
            this.stack.draw(this.canvas);

            if (this.loadingProgress != NOT_LOADING) {
                this.loadingPanel.draw(this.canvas, this.loadingCaption, this.loadingProgress);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("GLUE DRAW FAILED: " + exception.getMessage());
        }

        if (!this.overGame) {
            this.canvas.coverOutsideInterface(framebufferWidth, framebufferHeight);
        }

        // Over a game the game places its cursor, except under the glue's modal popup, which places the arrow.
        if (!this.overGame || this.getScreen().hasPopup()) {
            this.placeCursor(time);
        }

        this.canvas.end();

        // The title screen stays until the client's start loading (the glue itself, and the HD image set of the chosen
        // graphics) has finished.
        this.titleScreen.setLoadingFinished(!this.clientLoading);
    }

    /**
     * Places the game arrow of the frame while the pointer is inside, except on the title and cinematic screens; the
     * present draws it over the whole frame.
     *
     * @param time the time in milliseconds
     */
    private void placeCursor(long time) {
        if (!this.pointerInside || this.getScreen() == this.titleScreen || this.getScreen() instanceof CinematicScreen) {
            return;
        }

        this.cursor.place(this.canvas.getAssets(), GameCursor.ARROW, this.pointerX, this.pointerY, this.canvas.getScale(), time);
    }


    /**
     * Opens the options popup ({@link OptionsPopup}) over the current screen: the main menu's Options, and the game
     * menu's Options over a game ({@link #enterGame}).
     */
    public void showOptions() {
        this.getScreen().showPopup(new OptionsPopup(this.getPopupPaletteFolder()));
    }

    /**
     * Opens the running game's save dialog over the current screen.
     *
     * @param target      the running game's saved games
     * @param consoleRace the console race of the game (its dialog sheet)
     * @param result      receives {@code true} once Save sent the Save Game command, {@code false} on Cancel
     */
    public void showSaveGame(GlueSavePopup.@NotNull Target target, int consoleRace, @NotNull Consumer<Boolean> result) {
        this.getScreen().showPopup(new GlueSavePopup(this.getPopupPaletteFolder(), this.getPopupConsoleRace(consoleRace), target, result));
    }

    /**
     * Opens the running game's load dialog over the current screen.
     *
     * @param source      the running game's saved games
     * @param consoleRace the console race of the game (its dialog sheet)
     * @param result      receives {@code true} once Load sent the Load Game command, {@code false} on Cancel
     */
    public void showLoadGame(GlueLoadPopup.@NotNull Source source, int consoleRace, @NotNull Consumer<Boolean> result) {
        this.getScreen().showPopup(new GlueLoadPopup(this.getPopupPaletteFolder(), this.getPopupConsoleRace(consoleRace), source, result));
    }

    /**
     * Opens a fatal notice using the current screen palette, or the game overlay palette.
     *
     * @param networkKey the {@code network.json} key of the message, for example {@code strERROR_NOT_SUPPORTED_EUD_MAP}
     * @param detail     the second string, or {@code null} for none
     * @param fonts      the game's font set
     */
    public void showFatal(@NotNull String networkKey, @Nullable String detail, @NotNull InterfaceFonts fonts) {
        String message = this.context.getNetworkText().get(networkKey);

        this.getScreen().showPopup(new GlueFatalPopup(this.getPopupPaletteFolder(), message.isEmpty() ? networkKey : message, detail, fonts));
    }

    /**
     * @return the palette folder of a popup: the game overlay palette over a game, the current screen's palette otherwise
     */
    private @NotNull String getPopupPaletteFolder() {
        return this.overGame ? GlueScreen.SHARED_PALETTE_FOLDER : this.getScreen().getPaletteFolder();
    }

    /**
     * @param consoleRace the console race of the game
     * @return the console race of a popup: the game's over a game, {@link GluePopup#NO_CONSOLE_RACE} otherwise
     */
    private int getPopupConsoleRace(int consoleRace) {
        return this.overGame ? consoleRace : GluePopup.NO_CONSOLE_RACE;
    }


    /**
     * Handles one window input event: a pointer event is turned into interface coordinates (the left button, and the right button's press
     * for the pages' context menus; other buttons are ignored) and the event goes through the scene stack to the screen or popup that takes
     * it ({@link #findInputTarget}).
     *
     * @param event the event, pointer positions in framebuffer pixels
     */
    public void handleInput(@NotNull InputEvent event) {
        if (event.getType() == InputEvent.Type.BUTTON_PRESSED || event.getType() == InputEvent.Type.BUTTON_RELEASED) {
            boolean contextPress = event.getButton() == PointerButton.RIGHT && event.getType() == InputEvent.Type.BUTTON_PRESSED;

            if (event.getButton() != PointerButton.LEFT && !contextPress) {
                return;
            }
        }

        if (event.getType() == InputEvent.Type.POINTER_MOVED ||
            event.getType() == InputEvent.Type.BUTTON_PRESSED && event.getButton() == PointerButton.LEFT) {
            this.pointerX = event.getX();
            this.pointerY = event.getY();
        }

        InputEvent routed = event.isPointerEvent()
                            ? event.createAt(this.canvas.toInterfaceX(event.getX()), this.canvas.toInterfaceY(event.getY()))
                            : event;

        this.stack.handleInput(routed);
    }

    /**
     * Applies an edit operation to the focused edit control of the screen or popup that takes the keys.
     *
     * @param operation the operation
     */
    public void applyEditOperation(@NotNull TextEditOperation operation) {
        this.findKeyTarget().applyEditOperation(operation);
    }

    /**
     * Finds the screen or popup that takes an input event (the scene stack's input router): a pointer event goes to
     * {@link #findPointerTarget}, keys, characters and the wheel to {@link #findKeyTarget}.
     *
     * @param event the event, pointer positions in interface coordinates
     * @return the screen or popup that takes the event
     */
    private @NotNull GlueScreen findInputTarget(@NotNull InputEvent event) {
        return event.isPointerEvent() ? this.findPointerTarget(event.getX(), event.getY()) : this.findKeyTarget();
    }

    /**
     * Finds the screen or popup that takes a pointer event through the client's dialog list: the modal popup on top,
     * else the top glue dialog with a control under the pointer, else the screen.
     *
     * @param x the interface x
     * @param y the interface y
     * @return the screen or popup that takes the event
     */
    private @NotNull GlueScreen findPointerTarget(float x, float y) {
        DialogStack.Target<DialogControl, ScrDialogLayer> target = this.context.getDialogStack().findPointerTarget(x, y);

        return target != null && target.getLayer() instanceof GlueScreen.ScreenLayer layer ? layer.getScreen() : this.getScreen();
    }

    /**
     * Finds the screen or popup that takes a key through the client's dialog list: the modal popup on top, else the screen.
     *
     * @return the screen or popup that takes the key
     */
    private @NotNull GlueScreen findKeyTarget() {
        ScrDialogLayer target = this.context.getDialogStack().findKeyTarget();

        return target instanceof GlueScreen.ScreenLayer layer ? layer.getScreen() : this.getScreen();
    }


    /**
     * @return the current screen
     */
    private @NotNull GlueScreen getScreen() {
        return this.stack.requireScene();
    }

    /**
     * Shows the queued screen error over the current screen once and clears it.
     */
    private void showScreenError() {
        if (this.screenError == null) {
            return;
        }

        String message = this.context.getNetworkText().get(this.screenError);

        this.screenError = null;
        this.getScreen().showError(message);
    }

    /**
     * Creates the screen a glue opened after a game starts at: the score screen when there is one, then the resume screen.
     *
     * @param resume the screen to continue at
     * @return the first screen
     */
    private @NotNull GlueScreen createResumeScreen(@NotNull GlueResume resume) {
        ScoreScreen score = resume.getScore();

        if (score == null) {
            return this.resume(resume);
        }

        Path watched = score.getWatchedReplay();
        Runnable watch = watched == null
                         ? () -> this.stack.replace(this.resume(resume))
                         : () -> this.gameRequest = GlueGameRequest.createReplayWithReturnDestination(watched,
                                                                                                      resume.getPlayerName(),
                                                                                                      resume.isExpansion(),
                                                                                                      resume.getDestination());

        return new GlueScoreScreen(score, resume.getReplaySaver(), () -> this.stack.replace(this.resume(resume)), watch);
    }

    /**
     * Restores the game choice of a resumed glue, raises the player's campaign progress after a won mission and opens the glue's screen.
     *
     * @param resume the screen to continue at
     * @return the resumed screen
     */
    private @NotNull GlueScreen resume(@NotNull GlueResume resume) {
        this.context.setExpansion(resume.isExpansion());

        CampaignEpisodes.Entry played = resume.getCampaignMission() < 0
                                        ? null
                                        : CampaignEpisodes.findMissionEntry(resume.isExpansion(), resume.getCampaignMission());
        // Continue a won mission at the next entry or the scenario selected by its map.
        CampaignEpisodes.Entry target = played == null || !resume.isVictory() ? null : this.walkAfterVictory(played, resume.getCampaignScenario());

        if (target != null) {
            advanceProgress(this.context.getCharacter(), resume.isExpansion(), target);
        }

        GlueScreen next;
        GlueGameRequest nextScenario = resume.getNextScenario();

        if (nextScenario != null) {
            next = this.openNextScenario(nextScenario);
        } else if (played != null) {
            // Continue a victory after its finale; a defeat returns to the ready room and replays the establishing shot.
            next = target != null ? this.continueAfterVictory(played, target) : this.openReadyRoom(played);
        } else {
            GlueNavigator.Destination destination = resume.getDestination();

            next = this.scenes.create(this.context, destination);
        }

        return next;
    }

    /**
     * Returns the next campaign entry or the mission named by a scenario, keeping the played entry when no target is found.
     *
     * @param played   the won mission's entry
     * @param scenario the scenario selected by the mission's map, or {@code null} for none
     * @return the entry to continue at
     */
    private CampaignEpisodes.@NotNull Entry walkAfterVictory(CampaignEpisodes.@NotNull Entry played, @Nullable String scenario) {
        if (scenario == null || scenario.isEmpty()) {
            CampaignEpisodes.Entry next = played.createNextEntry();

            return next == null ? played : next;
        }

        try {
            CampaignEpisodes.Entry named = CampaignEpisodes.findScenario(scenario, CampaignMissions.load(this.canvas.getStorage()));

            return named == null ? played : named;
        } catch (IOException exception) {
            LOGGER.warning("CAMPAIGN SCENARIO UNAVAILABLE: " + scenario + ": " + exception.getMessage());

            return played;
        }
    }

    /**
     * Opens the next custom scenario in its ready room, or queues a campaign-link error over campaign selection.
     *
     * @param request the next scenario
     * @return the ready room, or the campaign screen if the scenario cannot be loaded
     */
    private @NotNull GlueScreen openNextScenario(@NotNull GlueGameRequest request) {
        try {
            return ReadyRoomScreen.createForCustomGame(request, this.context.isMapCompatibility());
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("NEXT SCENARIO UNAVAILABLE: " + request.getPath() + ": " + exception.getMessage());
            this.screenError = CAMPAIGN_LINK_ERROR;

            return new CampaignScreen(this.context.isExpansion());
        }
    }

    /**
     * Creates the campaign entry screen, its cinematic or its ready room.
     *
     * @param entry the entry, or {@code null} at the end of the episode
     * @return the created screen
     */
    private @NotNull GlueScreen createCampaignScreen(CampaignEpisodes.@Nullable Entry entry) {
        // Show the ending after the last episode.
        if (entry != null && entry.isEnding()) {
            GlueNavigator.Destination destination = GlueNavigator.Destination.ENDING;

            return this.scenes.create(this.context, destination);
        }

        if (entry == null || entry.getMission() == 0) {
            return new CampaignScreen(this.context.isExpansion());
        }

        if (entry.getCinematic() != 0) {
            return new CinematicScreen(CinematicScreen.createSequenceWithPrelude(entry.getCinematic()),
                                       () -> this.context.getNavigator().playCampaign(entry.createNextEntry()));
        }

        return this.openReadyRoom(entry);
    }

    /**
     * Creates a mission ready room after its establishing shot when one is available.
     *
     * @param entry the mission entry
     * @return the establishing shot screen, or the ready room
     */
    private @NotNull GlueScreen openReadyRoom(CampaignEpisodes.@NotNull Entry entry) {
        String shot = EstablishingShot.getName(entry.getMission());

        if (shot == null) {
            return new ReadyRoomScreen(entry);
        }

        return new EstablishingShotScreen(shot, () -> this.stack.replace(new ReadyRoomScreen(entry)));
    }

    /**
     * Continues at the target entry after the played mission's finale, when one is available.
     *
     * @param played the won mission's entry
     * @param target the entry to continue at
     * @return the finale screen, or the target entry's screen
     */
    private @NotNull GlueScreen continueAfterVictory(CampaignEpisodes.@NotNull Entry played, CampaignEpisodes.@NotNull Entry target) {
        String finale = EstablishingShot.getFinaleName(played.getMission());

        if (finale == null) {
            return this.createCampaignScreen(target);
        }

        return new EstablishingShotScreen(finale, () -> this.stack.replace(this.createCampaignScreen(target)));
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
     * <p>
     * This exception interacts with a thread's interrupted status,
     * and runtime misbehavior is likely to occur if an {@code
     * InterruptedException} is {@linkplain Throwable#addSuppressed
     * suppressed}.
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
     * <p>
     * However, implementers of this interface are strongly encouraged
     * to make their {@code close} methods idempotent.
     * @implSpec Releases the screens, the glue textures and the glue sounds; the client resources stay open.
     */
    @Override
    public void close() {
        GlueScreen current = this.getScreen();

        // The current screen closes, then the title screen when it is not the current one.
        this.stack.close();
        this.context.getDialogStack().remove(current.getLayer());

        this.audio.close();
        this.canvas.close();
    }
}
