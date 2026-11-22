package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.dialog.DialogStack;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextClipboard;
import org.fogofwarlabs.sc4j.engine.client.dialog.WindowClipboard;
import org.fogofwarlabs.sc4j.engine.client.host.HostWindow;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.audio.ClientAudio;
import org.fogofwarlabs.sc4j.scr.client.audio.Music;
import org.fogofwarlabs.sc4j.scr.client.audio.SoundTable;
import org.fogofwarlabs.sc4j.scr.client.configuration.InstallLayout;
import org.fogofwarlabs.sc4j.scr.client.cursor.GameCursor;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialogLayer;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.SheetFrameRecords;
import org.fogofwarlabs.sc4j.scr.client.ui.observer.ObserverProduction;
import org.fogofwarlabs.sc4j.scr.client.ui.portrait.PortraitTables;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedGraphics;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedImage;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrModGraphics;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceTimers;
import org.fogofwarlabs.sc4j.scr.game.presentation.MusicRotation;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.render.hd.HdImageSet;
import org.fogofwarlabs.sc4j.scr.render.hd.HdRenderAssets;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceAssets;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFonts;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the storage, renderer, fonts, cursor and shared resource caches for the client run. Creation and use require the window's OpenGL context.
 */
public final class ClientContext implements AutoCloseable {
    /**
     * The table steps of {@link #loadClientData}: the game data, the sound table, the portrait tables, the production panel tables. One step per
     * added image follows them ({@link #getClientDataStepCount()}).
     */
    private static final int TABLE_STEP_COUNT = 4;
    private static final int GAME_DATA_STEP = 0;
    private static final int SOUND_TABLE_STEP = 1;
    private static final int PORTRAIT_TABLES_STEP = 2;
    private static final int OBSERVER_PRODUCTION_STEP = 3;

    private static final int NO_HD_TILESET = -1;

    /**
     * The initial state of the global generator.
     */
    private static final int GLOBAL_RANDOM_SEED = 0;
    /**
     * The enable flag that closes the global generator's gate.
     */
    private static final int GLOBAL_RANDOM_DISABLED = 0;

    private static final @NotNull Logger LOGGER = Logger.of(ClientContext.class);

    /**
     * Creates a {@link ClientContext} instance with the acquired client resources, the run's Beacon.Link and its release status. The context
     * closes the provider and the audio when it is closed or when the creation fails.
     *
     * @param installLayout the selected client directories
     * @param resources     the resource provider of the StarCraft: Remastered installation
     * @param locale        the text locale, for example {@code koKR}
     * @param audio         the client audio, or {@code null} to play no sound
     * @param beaconLink    the Beacon.Link sessions of the multiplayer screens
     * @param releaseStatus the running SC4J version and how it stands against the latest SC4J release
     * @return the created {@link ClientContext} instance
     * @throws IOException if the storage, the renderer or the fonts cannot be loaded
     */
    public static @NotNull ClientContext open(@NotNull InstallLayout installLayout, @NotNull ScrResourceProvider resources,
                                              @NotNull String locale, @Nullable ClientAudio audio,
                                              @NotNull BeaconLinkAccess beaconLink,
                                              @NotNull ReleaseStatus releaseStatus) throws IOException {
        CascStorage storage;

        try {
            storage = resources.getStorage();
        } catch (IOException | RuntimeException exception) {
            resources.close();

            if (audio != null) {
                audio.close();
            }

            throw exception;
        }

        InterfaceRenderer renderer = null;
        InterfaceFonts gameFonts = null;
        InterfaceFonts glueFonts = null;

        try {
            renderer  = new InterfaceRenderer();
            gameFonts = new InterfaceFonts(resources, renderer, locale, InterfaceFonts.Set.GAME, installLayout.getFontDirectories());
            glueFonts = new InterfaceFonts(resources, renderer, locale, InterfaceFonts.Set.GLUE, installLayout.getFontDirectories());

            renderer.setTextFonts(gameFonts);

            return new ClientContext(installLayout, resources, storage, renderer, gameFonts, glueFonts, new GameCursor(storage, renderer), audio,
                                     beaconLink, releaseStatus);
        } catch (IOException | RuntimeException exception) {
            if (glueFonts != null) {
                glueFonts.close();
            }

            if (gameFonts != null) {
                gameFonts.close();
            }

            if (renderer != null) {
                renderer.close();
            }

            resources.close();

            if (audio != null) {
                audio.close();
            }

            throw exception;
        }
    }

    private final @NotNull InstallLayout installLayout;

    private final @NotNull Map<Integer, InterfaceAssets> tilesetAssets;

    private final @NotNull ScrResourceProvider resources;
    private final @NotNull CascStorage storage;
    private final @NotNull InterfaceRenderer interfaceRenderer;
    private final @NotNull InterfaceFonts gameFonts;
    private final @NotNull InterfaceFonts glueFonts;

    private final @NotNull GameCursor cursor;

    private final @NotNull InterfaceTimers timers;
    private final @NotNull DialogStack<DialogControl, ScrDialogLayer> dialogs;
    private final @NotNull SheetFrameRecords sheetFrameRecords;

    private final @Nullable ClientAudio audio;
    private final @NotNull BeaconLinkAccess beaconLink;
    private final @NotNull ReleaseStatus releaseStatus;
    /**
     * The player of the run: the run's account, else the local ID chosen on the ID screen or a guest before one is chosen.
     */
    private @NotNull ClientPlayer player;
    private final @NotNull Music music;
    /**
     * The game music rotation of this client process, which every non-campaign game start advances.
     */
    private final @NotNull MusicRotation musicRotation;

    /**
     * Retains the final game state; lobby race resolution temporarily opens its gate.
     */
    private final @NotNull LcgRandom globalRandom;

    /**
     * The client window, or {@code null} while it does not exist (an offscreen test).
     */
    private @Nullable HostWindow window;
    private final @NotNull TextClipboard clipboard;

    /**
     * The HD image set of the selected graphics, loaded once when selected and kept across the menus and games.
     */
    private @Nullable HdImageSet hdImages;
    /**
     * The HD tileset assets of the last map's tileset, for the image set's animations.
     */
    private @Nullable HdRenderAssets hdTilesetAssets;
    /**
     * The tileset of {@link #hdTilesetAssets}, or {@code -1} while none is loaded.
     */
    private int hdTileset;

    /**
     * The localized text tables of the client locale, loaded once and again only on a locale change.
     */
    private @Nullable TextTables textTables;
    /**
     * The sound names of {@code rez/sfx.json}, loaded at the client start.
     */
    private @Nullable SoundTable soundTable;
    /**
     * The portrait tables of {@code arr/portdata.dat} and {@code arr/portdata.tbl}, loaded at the client start.
     */
    private @Nullable PortraitTables portraitTables;
    /**
     * The icon tables of the replay production panel ({@code ObserverPanel2}), loaded at the client start.
     */
    private @Nullable ObserverProduction observerProduction;
    /**
     * The game data (the DAT tables, iscript, {@code images.tbl}), loaded at the client start; every game copies its DATs.
     */
    private @Nullable GameData gameData;

    /**
     * Creates a {@link ClientContext} instance for the acquired client resources.
     *
     * @param installLayout     the selected client directories
     * @param resources         the resource provider, which owns the storage
     * @param storage           the game storage of the provider
     * @param interfaceRenderer the shared interface renderer
     * @param gameFonts         the game font set
     * @param glueFonts         the menu font set
     * @param cursor            the drawn cursor
     * @param audio             the client audio, or {@code null} without sound
     * @param beaconLink        the Beacon.Link sessions of the multiplayer screens
     * @param releaseStatus     the running SC4J version and how it stands against the latest SC4J release
     */
    private ClientContext(@NotNull InstallLayout installLayout,
                          @NotNull ScrResourceProvider resources, @NotNull CascStorage storage,
                          @NotNull InterfaceRenderer interfaceRenderer,
                          @NotNull InterfaceFonts gameFonts, @NotNull InterfaceFonts glueFonts,
                          @NotNull GameCursor cursor,
                          @Nullable ClientAudio audio,
                          @NotNull BeaconLinkAccess beaconLink,
                          @NotNull ReleaseStatus releaseStatus) {
        this.installLayout = installLayout;

        this.tilesetAssets = new HashMap<>();

        this.resources         = resources;
        this.storage           = storage;
        this.interfaceRenderer = interfaceRenderer;
        this.gameFonts         = gameFonts;
        this.glueFonts         = glueFonts;

        this.cursor = cursor;

        this.timers            = new InterfaceTimers();
        this.dialogs           = new DialogStack<>();
        this.sheetFrameRecords = new SheetFrameRecords(storage);

        this.audio         = audio;
        this.beaconLink    = beaconLink;
        this.releaseStatus = releaseStatus;
        this.player        = ClientPlayer.of(beaconLink.getAccount());
        this.music         = new Music(storage, audio);
        this.musicRotation = new MusicRotation();

        this.globalRandom = new LcgRandom(GLOBAL_RANDOM_SEED);
        this.globalRandom.setEnabled(GLOBAL_RANDOM_DISABLED);

        this.window    = null;
        this.clipboard = new WindowClipboard(() -> this.window);

        this.hdImages        = null;
        this.hdTilesetAssets = null;
        this.hdTileset       = NO_HD_TILESET;

        this.textTables         = null;
        this.soundTable         = null;
        this.portraitTables     = null;
        this.observerProduction = null;
        this.gameData           = null;
    }

    /**
     * @return the Beacon.Link sessions of the multiplayer screens
     */
    public @NotNull BeaconLinkAccess getBeaconLink() {
        return this.beaconLink;
    }

    /**
     * @return the running SC4J version and how it stands against the latest SC4J release
     */
    public @NotNull ReleaseStatus getReleaseStatus() {
        return this.releaseStatus;
    }

    /**
     * @return the player of the run: the run's account, else the chosen local ID or a guest
     */
    public @NotNull ClientPlayer getPlayer() {
        return this.player;
    }

    /**
     * Makes a local ID the player of a run without an account.
     *
     * @param name     the ID's name
     * @param fileName the file name of the ID's character file, which names its saved-game folder
     * @throws IllegalStateException if the run has an account
     */
    public void chooseLocalId(@NotNull String name, @NotNull String fileName) {
        if (this.beaconLink.getAccount() != null) {
            throw Exceptions.newIllegalStateException("A run with an account plays as its account.");
        }

        this.player = ClientPlayer.ofLocalId(name, fileName);
    }

    /**
     * @return the selected client directories
     */
    public @NotNull InstallLayout getInstallLayout() {
        return this.installLayout;
    }

    /**
     * @return the resource provider of the installation, open for the whole run
     */
    public @NotNull ScrResourceProvider getResources() {
        return this.resources;
    }

    /**
     * @return the game storage, open for the whole run
     */
    public @NotNull CascStorage getStorage() {
        return this.storage;
    }

    /**
     * @return the interface renderer of the menus and the console
     */
    public @NotNull InterfaceRenderer getInterfaceRenderer() {
        return this.interfaceRenderer;
    }

    /**
     * @return the four game dialog font slots: {@code font8}, {@code font10}, {@code font16} and {@code font16x}
     */
    public @NotNull InterfaceFonts getGameFonts() {
        return this.gameFonts;
    }

    /**
     * @return the four menu dialog font slots: {@code font10}, {@code font14}, {@code font16} and {@code font16x}
     */
    public @NotNull InterfaceFonts getGlueFonts() {
        return this.glueFonts;
    }

    /**
     * @return the drawn game cursor
     */
    public @NotNull GameCursor getCursor() {
        return this.cursor;
    }

    /**
     * @return the shared interface timer list for menus and in-game dialogs
     */
    public @NotNull InterfaceTimers getTimers() {
        return this.timers;
    }

    /**
     * @return the shared list of open menu and in-game dialogs
     */
    public @NotNull DialogStack<DialogControl, ScrDialogLayer> getDialogStack() {
        return this.dialogs;
    }

    /**
     * @return the shared cache of classic dialog sheet frame offsets and metric sizes
     */
    public @NotNull SheetFrameRecords getSheetFrameRecords() {
        return this.sheetFrameRecords;
    }

    /**
     * @return the client window, or {@code null} while it does not exist
     */
    public @Nullable HostWindow getWindow() {
        return this.window;
    }

    /**
     * Sets the client window whose clipboard and focus the screens use.
     *
     * @param window the client window, or {@code null} while it does not exist
     */
    public void setWindow(@Nullable HostWindow window) {
        this.window = window;
    }

    /**
     * @return the system clipboard of the client window; without the window it holds nothing
     */
    public @NotNull TextClipboard getClipboard() {
        return this.clipboard;
    }

    /**
     * @return the client audio, or {@code null} if the client plays no sound
     */
    public @Nullable ClientAudio getAudio() {
        return this.audio;
    }

    /**
     * @return the music of the glue screens and the games
     */
    public @NotNull Music getMusic() {
        return this.music;
    }

    /**
     * @return the game music rotation of this client process, which every non-campaign game start advances
     */
    public @NotNull MusicRotation getMusicRotation() {
        return this.musicRotation;
    }

    /**
     * @return the generator that retains the last game state between games
     */
    public @NotNull LcgRandom getGlobalRandom() {
        return this.globalRandom;
    }

    /**
     * @return the HD image set of the selected graphics, or {@code null} with the classic graphics
     */
    public @Nullable HdImageSet getHdImages() {
        return this.hdImages;
    }

    /**
     * Sets the HD image set (a graphics change): the previous set and the tileset assets built on it are released.
     *
     * @param images the new image set, or {@code null} for the classic graphics
     */
    public void setHdImages(@Nullable HdImageSet images) {
        if (this.hdImages == images) {
            return;
        }

        this.hdTilesetAssets = null;
        this.hdTileset       = NO_HD_TILESET;

        if (this.hdImages != null) {
            this.hdImages.close();
        }

        this.hdImages = images;
    }


    /**
     * Returns the localized text tables of the client locale. A session started before the startup task ran loads them here once, so no game
     * starts without them.
     *
     * @param locale the locale such as {@code koKR}
     * @return the tables
     * @throws IOException if a table is missing or malformed
     */
    public @NotNull TextTables getTextTables(@NotNull String locale) throws IOException {
        TextTables tables = this.textTables;

        if (tables == null || !tables.getLocale().equals(locale)) {
            tables          = TextTables.load(this.storage, locale);
            this.textTables = tables;
        }

        return tables;
    }

    /**
     * Loads and retains the storage's game data. Games use their own copy through {@link GameData#createGameCopy}.
     *
     * @return the game data
     * @throws IOException if a table cannot be read
     */
    public @NotNull GameData getGameData() throws IOException {
        GameData data = this.gameData;

        if (data == null) {
            data          = GameData.load(this.storage);
            this.gameData = data;

            // The provider reads the frames of the images the mods add (the HD image set, the interface art) from the game data's registration.
            this.resources.setAddedGraphics(data.getAddedGraphics());
        }

        return data;
    }

    /**
     * Returns the sound names of {@code rez/sfx.json}, loaded at the client start (here as a fallback when a game starts before the
     * startup task ran).
     *
     * @return the table
     * @throws IOException if {@code rez/sfx.json} cannot be read
     */
    public @NotNull SoundTable getSoundTable() throws IOException {
        SoundTable table = this.soundTable;

        if (table == null) {
            table           = SoundTable.load(this.storage);
            this.soundTable = table;
        }

        return table;
    }

    /**
     * Returns the portrait tables shared by every portrait player, loaded at the client start (here as a fallback).
     *
     * @return the tables
     * @throws IOException if a portrait table is missing or malformed
     */
    public @NotNull PortraitTables getPortraitTables() throws IOException {
        PortraitTables tables = this.portraitTables;

        if (tables == null) {
            tables              = PortraitTables.load(this.storage);
            this.portraitTables = tables;
        }

        return tables;
    }

    /**
     * Returns the icon tables of the replay production panel, loaded at the client start (here as a fallback).
     *
     * @return the tables
     * @throws IOException if the panel script cannot be read
     */
    public @NotNull ObserverProduction getObserverProduction() throws IOException {
        ObserverProduction production = this.observerProduction;

        if (production == null) {
            production              = ObserverProduction.load(this.storage);
            this.observerProduction = production;
        }

        return production;
    }


    /**
     * Loads and caches HD tileset assets for the current image set and animations.
     *
     * @param tileset the map tileset index
     * @return the tileset assets
     * @throws IOException if there is no image set or the assets cannot be read
     */
    public @NotNull HdRenderAssets getHdTilesetAssets(int tileset) throws IOException {
        HdImageSet images = this.hdImages;

        if (images == null) {
            throw Exceptions.newIOException("HD images are unavailable.");
        }

        HdRenderAssets assets = this.hdTilesetAssets;

        if (assets == null || this.hdTileset != tileset || assets.getAnimations() != images.getAnimations()) {
            this.hdTilesetAssets = null;
            assets               = HdRenderAssets.load(tileset, images.getAnimations());
            this.hdTilesetAssets = assets;
            this.hdTileset       = tileset;
        }

        return assets;
    }

    /**
     * Returns the interface data of a tileset for lookups that do not depend on the asset set (the tile groups and minimap colors of a map preview),
     * loaded once per run. Painters keep their own {@link InterfaceAssets}, whose asset set they select.
     *
     * @param tileset the tileset index
     * @return the tileset's interface data
     * @throws IOException if the tileset cannot be read
     */
    public @NotNull InterfaceAssets getTilesetAssets(int tileset) throws IOException {
        InterfaceAssets assets = this.tilesetAssets.get(tileset);

        if (assets == null) {
            assets = InterfaceAssets.load(this.resources, tileset);
            this.tilesetAssets.put(tileset, assets);
        }

        return assets;
    }


    /**
     * @return the number of steps of {@link #loadClientData}: the four tables, then one per image the mods add, counted once the game data (the
     * first step) is loaded
     */
    public int getClientDataStepCount() {
        GameData data = this.gameData;

        return TABLE_STEP_COUNT + (data == null ? 0 : data.getAddedGraphics().getImages().size());
    }

    /**
     * Loads one step of the client's locale-independent game data ({@link org.fogofwarlabs.sc4j.scr.client.loading.ClientDataLoadTask} at the
     * client start); a loaded step is kept.
     *
     * @param step the step: {@code 0} the game data, {@code 1} the sound table, {@code 2} the portrait tables, {@code 3} the production panel, then
     *             the SD frames of the added images in id order ({@link #getClientDataStepCount()})
     * @throws IOException              if the table or the frames are missing or malformed
     * @throws IllegalArgumentException if the step is unknown
     */
    public void loadClientData(int step) throws IOException {
        switch (step) {
            case GAME_DATA_STEP -> this.getGameData();
            case SOUND_TABLE_STEP -> this.getSoundTable();
            case PORTRAIT_TABLES_STEP -> this.getPortraitTables();
            case OBSERVER_PRODUCTION_STEP -> this.getObserverProduction();
            default -> this.loadAddedImage(step);
        }
    }

    /**
     * Loads the SD frames of an added image, so that no game reads or decodes them when it creates or first draws the image: the frames the games
     * read when they create it ({@link GameData#getGrpFile}) and the sprite sheet the renderers draw ({@link ScrModGraphics#loadSheet()}). Its HD
     * frames load with the HD image set.
     */
    private void loadAddedImage(int step) throws IOException {
        GameData data = this.getGameData();
        List<ScrAddedImage> images = data.getAddedGraphics().getImages();
        int index = step - TABLE_STEP_COUNT;

        if (index < 0 || index >= images.size()) {
            throw Exceptions.newIllegalArgumentException("Unknown client data step: {0}", step);
        }

        ScrModGraphics graphics = images.get(index).getGraphics();

        data.getGrpFile(ScrAddedGraphics.FIRST_IMAGE_ID + index);

        if (graphics != null) {
            graphics.loadSheet();
        }
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
     * @implSpec Closes the music, the audio, image set, cursor, fonts, renderer and resource provider (with its storage) in their existing order. Earlier
     * close failures propagate and prevent subsequent cleanup; a storage close failure is logged.
     */
    @Override
    public void close() {
        this.music.close();

        if (this.audio != null) {
            this.audio.close();
        }

        this.setHdImages(null);
        this.cursor.close();
        this.interfaceRenderer.releaseTextFonts(this.gameFonts);
        this.glueFonts.close();
        this.gameFonts.close();
        this.interfaceRenderer.close();

        try {
            this.resources.close();
        } catch (RuntimeException exception) {
            LOGGER.warning("CLIENT STORAGE CLOSE FAILED: " + exception.getMessage());
        }
    }
}
