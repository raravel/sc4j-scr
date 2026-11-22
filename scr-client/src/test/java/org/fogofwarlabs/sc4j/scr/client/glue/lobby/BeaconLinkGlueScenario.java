package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.engine.client.audio.AudioMixer;
import org.fogofwarlabs.sc4j.engine.client.audio.SilentAudioDevice;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.host.ClientHost;
import org.fogofwarlabs.sc4j.engine.client.host.HostWindow;
import org.fogofwarlabs.sc4j.engine.client.host.WindowSettings;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.input.PointerButton;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.FoundRoom;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.CommunitySession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingBeaconLink;
import org.fogofwarlabs.sc4j.scr.client.BeaconLinkAccess;
import org.fogofwarlabs.sc4j.scr.client.ClientContext;
import org.fogofwarlabs.sc4j.scr.client.ReleaseState;
import org.fogofwarlabs.sc4j.scr.client.ReleaseStatus;
import org.fogofwarlabs.sc4j.scr.client.audio.ClientAudio;
import org.fogofwarlabs.sc4j.scr.client.audio.SoundSettings;
import org.fogofwarlabs.sc4j.scr.client.configuration.InstallLayout;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueFolders;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueInterface;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialogLayer;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.load.ScrIscriptImporter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Draws the glue's Beacon.Link screens offscreen: a hidden window's OpenGL context, a framebuffer of the frame size, the installed client
 * resources and a Beacon.Link of one registered node whose session's community part is the given one ({@link RecordingBeaconLink}), played
 * with the account {@value #ACCOUNT_NAME} unless the scenario names another account or none.
 */
public final class BeaconLinkGlueScenario implements AutoCloseable {
    /**
     * The mod id of the session's player and of the players who can play with it.
     */
    public static final @NotNull String MOD_ID = "scr";
    /**
     * The mod version of the session's player and of the players who can play with it.
     */
    public static final @NotNull String MOD_VERSION = "0.1.0";
    /**
     * The running SC4J version of a scenario's run, fixed so that a run does not depend on the built version.
     */
    public static final @NotNull ReleaseVersion RUNNING_VERSION = Objects.requireNonNull(ReleaseVersion.parse("0.1.0"));
    /**
     * The release status of a run whose latest release is not known, which a scenario's run has unless another is given.
     */
    public static final @NotNull ReleaseStatus UNKNOWN_RELEASE = ReleaseStatus.of(RUNNING_VERSION, ReleaseState.UNKNOWN, null);

    private static final int WINDOW_WIDTH = 640;
    private static final int WINDOW_HEIGHT = 480;
    /**
     * The frozen host time step between two drawn frames and the drawn time a screen gets to settle (swish, fades), in milliseconds.
     */
    private static final int FRAME_MILLISECONDS = 20;
    private static final int SETTLE_MILLISECONDS = 3000;
    /**
     * The width of the Beacon.Link dialogs, centered on the interface's width.
     */
    private static final float DIALOG_WIDTH = 640;
    private static final int CENTER_DIVISOR = 2;
    private static final int NO_MODIFIERS = 0;


    /**
     * The name of the scenario's account.
     */
    public static final @NotNull String ACCOUNT_NAME = "Raynor";

    /**
     * Creates a {@link BeaconLinkGlueScenario} instance and opens the glue.
     *
     * @param root          the install root of the client's folders, empty
     * @param locale        the text locale, for example {@code koKR}
     * @param width         the frame width in pixels
     * @param height        the frame height in pixels
     * @param qualityPrefix the glue's asset set ({@link org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas#SD_PREFIX}, ...)
     * @param community     the community part of the Beacon.Link session
     * @return the created {@link BeaconLinkGlueScenario} instance
     * @throws IOException if an installed asset cannot be read
     */
    public static @NotNull BeaconLinkGlueScenario open(@NotNull Path root, @NotNull String locale, int width, int height, @NotNull String qualityPrefix,
                                                       @NotNull CommunitySession community) throws IOException {
        return open(root, locale, width, height, qualityPrefix, community, List.of());
    }

    /**
     * Creates a {@link BeaconLinkGlueScenario} instance whose room searches find the given rooms, and opens the glue.
     *
     * @param root          the install root of the client's folders, empty
     * @param locale        the text locale, for example {@code koKR}
     * @param width         the frame width in pixels
     * @param height        the frame height in pixels
     * @param qualityPrefix the glue's asset set ({@link org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas#SD_PREFIX}, ...)
     * @param community     the community part of the Beacon.Link session
     * @param rooms         the rooms every search finds; those with a join refusal are another version's
     * @return the created {@link BeaconLinkGlueScenario} instance
     * @throws IOException if an installed asset cannot be read
     */
    public static @NotNull BeaconLinkGlueScenario open(@NotNull Path root, @NotNull String locale, int width, int height, @NotNull String qualityPrefix,
                                                       @NotNull CommunitySession community, @NotNull List<@NotNull FoundRoom> rooms) throws IOException {
        return open(root, locale, width, height, qualityPrefix, community, rooms, ACCOUNT_NAME);
    }

    /**
     * Creates a {@link BeaconLinkGlueScenario} instance whose room searches find the given rooms and whose run has the given account, and
     * opens the glue.
     *
     * @param root          the install root of the client's folders, empty
     * @param locale        the text locale, for example {@code koKR}
     * @param width         the frame width in pixels
     * @param height        the frame height in pixels
     * @param qualityPrefix the glue's asset set ({@link org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas#SD_PREFIX}, ...)
     * @param community     the community part of the Beacon.Link session
     * @param rooms         the rooms every search finds; those with a join refusal are another version's
     * @param accountName   the name of the run's account, or {@code null} for a run without an account (a guest)
     * @return the created {@link BeaconLinkGlueScenario} instance
     * @throws IOException if an installed asset cannot be read
     */
    public static @NotNull BeaconLinkGlueScenario open(@NotNull Path root, @NotNull String locale, int width, int height, @NotNull String qualityPrefix,
                                                       @NotNull CommunitySession community, @NotNull List<@NotNull FoundRoom> rooms,
                                                       @Nullable String accountName) throws IOException {
        return open(root, locale, width, height, qualityPrefix, community, rooms, accountName, UNKNOWN_RELEASE);
    }

    /**
     * Creates a {@link BeaconLinkGlueScenario} instance whose room searches find the given rooms and whose run has the given account and SC4J
     * release status, and opens the glue.
     *
     * @param root          the install root of the client's folders, empty
     * @param locale        the text locale, for example {@code koKR}
     * @param width         the frame width in pixels
     * @param height        the frame height in pixels
     * @param qualityPrefix the glue's asset set ({@link org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas#SD_PREFIX}, ...)
     * @param community     the community part of the Beacon.Link session
     * @param rooms         the rooms every search finds; those with a join refusal are another version's
     * @param accountName   the name of the run's account, or {@code null} for a run without an account (a guest)
     * @param releaseStatus the running SC4J version and the latest SC4J release
     * @return the created {@link BeaconLinkGlueScenario} instance
     * @throws IOException if an installed asset cannot be read
     */
    public static @NotNull BeaconLinkGlueScenario open(@NotNull Path root, @NotNull String locale, int width, int height, @NotNull String qualityPrefix,
                                                       @NotNull CommunitySession community, @NotNull List<@NotNull FoundRoom> rooms,
                                                       @Nullable String accountName, @NotNull ReleaseStatus releaseStatus) throws IOException {
        ClientHost host = ClientHost.initialize();

        try {
            HostWindow window = host.createWindow(WindowSettings.builder(BeaconLinkGlueScenario.class.getSimpleName(), WINDOW_WIDTH, WINDOW_HEIGHT)
                                                                .build());

            window.makeContextCurrent();
            host.createCapabilities();

            return new BeaconLinkGlueScenario(host, root, locale, width, height, qualityPrefix, community, rooms, accountName, releaseStatus);
        } catch (IOException | RuntimeException exception) {
            host.close();

            throw exception;
        }
    }

    private final @NotNull ClientHost host;
    private final int width;
    private final int height;

    private final int colorTexture;
    private final int framebuffer;
    private final @NotNull RecordingBeaconLink beaconLink;
    private final @NotNull ClientContext client;
    private final @NotNull GlueInterface glue;
    private long time;

    private BeaconLinkGlueScenario(@NotNull ClientHost host, @NotNull Path root, @NotNull String locale, int width, int height,
                                   @NotNull String qualityPrefix, @NotNull CommunitySession community, @NotNull List<@NotNull FoundRoom> rooms,
                                   @Nullable String accountName, @NotNull ReleaseStatus releaseStatus) throws IOException {
        this.host   = host;
        this.width  = width;
        this.height = height;

        this.colorTexture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, this.colorTexture);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, width, height, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        this.framebuffer = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, this.colorTexture, 0);

        this.beaconLink = new RecordingBeaconLink(community, rooms, null, accountName);

        InstallLayout layout = InstallLayout.of(root);
        GlueFolders folders = new GlueFolders(layout.getCharacterDirectory(), layout.getMapDirectory(), layout.getReplayDirectory(),
                                              layout.getSaveDirectory(), layout.getRecentMapsFile());
        ClientContext opened = ClientContext.open(layout,
                                                  ScrResourceProvider.open(ExternalInput.INSTALLATION.getPath(), ScrIscriptImporter::readLibrary),
                                                  locale,
                                                  new ClientAudio(new AudioMixer(new SilentAudioDevice(System::nanoTime)), new SoundSettings()),
                                                  new BeaconLinkAccess(this.beaconLink),
                                                  releaseStatus);

        this.client = opened;

        try {
            this.glue = GlueInterface.open(opened, locale, folders, null);
        } catch (IOException | RuntimeException exception) {
            opened.close();

            throw exception;
        }

        this.time = 0;

        this.glue.setQualityPrefix(qualityPrefix);
        this.glue.setTargetFramebuffer(this.framebuffer, width, height);
    }

    /**
     * Shows a screen and draws it until it settled.
     *
     * @param destination the screen
     */
    public void show(GlueNavigator.@NotNull Destination destination) {
        this.glue.showScreen(destination);
        this.settle();
    }

    /**
     * Draws the current screen for the settle time of frozen time.
     */
    public void settle() {
        this.draw(SETTLE_MILLISECONDS);
    }

    /**
     * Draws the current screen frame by frame for a time of frozen time.
     *
     * @param milliseconds the time to draw
     */
    public void draw(int milliseconds) {
        for (int elapsed = 0; elapsed < milliseconds; elapsed += FRAME_MILLISECONDS) {
            this.glue.draw(UiSurface.of(this.width, this.height, DisplayScale.ONE), this.time);
            this.time += FRAME_MILLISECONDS;
        }
    }

    /**
     * Clicks a point of the shown dialog with the left button and draws until the screen settled; the pointer stays there.
     *
     * @param dialogX the x in the dialog's pixels
     * @param dialogY the y in the dialog's pixels
     */
    public void click(float dialogX, float dialogY) {
        this.tap(dialogX, dialogY);
        this.settle();
    }

    /**
     * Clicks a point of the shown dialog with the left button without drawing; the pointer stays there.
     *
     * @param dialogX the x in the dialog's pixels
     * @param dialogY the y in the dialog's pixels
     */
    public void tap(float dialogX, float dialogY) {
        GlueCanvas canvas = this.glue.getCanvas();
        float x = canvas.toScreenX((canvas.getInterfaceWidth() - DIALOG_WIDTH) / CENTER_DIVISOR + dialogX);
        float y = canvas.toScreenY(dialogY);

        this.glue.handleInput(InputEvent.createPointerMoved(x, y));
        this.glue.handleInput(InputEvent.createButtonPressed(PointerButton.LEFT, NO_MODIFIERS, x, y, false));
        this.glue.handleInput(InputEvent.createButtonReleased(PointerButton.LEFT, NO_MODIFIERS, x, y));
    }

    /**
     * Double-clicks a point of the shown dialog with the left button without drawing: a click, then a press the host marks as the second
     * press of a double press; the pointer stays there.
     *
     * @param dialogX the x in the dialog's pixels
     * @param dialogY the y in the dialog's pixels
     */
    public void doubleTap(float dialogX, float dialogY) {
        GlueCanvas canvas = this.glue.getCanvas();
        float x = canvas.toScreenX((canvas.getInterfaceWidth() - DIALOG_WIDTH) / CENTER_DIVISOR + dialogX);
        float y = canvas.toScreenY(dialogY);

        this.tap(dialogX, dialogY);
        this.glue.handleInput(InputEvent.createButtonPressed(PointerButton.LEFT, NO_MODIFIERS, x, y, true));
        this.glue.handleInput(InputEvent.createButtonReleased(PointerButton.LEFT, NO_MODIFIERS, x, y));
    }

    /**
     * Presses the right button at a point of the shown dialog and draws until the screen settled; the pointer stays there.
     *
     * @param dialogX the x in the dialog's pixels
     * @param dialogY the y in the dialog's pixels
     */
    public void rightClick(float dialogX, float dialogY) {
        GlueCanvas canvas = this.glue.getCanvas();
        float x = canvas.toScreenX((canvas.getInterfaceWidth() - DIALOG_WIDTH) / CENTER_DIVISOR + dialogX);
        float y = canvas.toScreenY(dialogY);

        this.glue.handleInput(InputEvent.createPointerMoved(x, y));
        this.glue.handleInput(InputEvent.createButtonPressed(PointerButton.RIGHT, NO_MODIFIERS, x, y, false));
        this.glue.handleInput(InputEvent.createButtonReleased(PointerButton.RIGHT, NO_MODIFIERS, x, y));
        this.settle();
    }

    /**
     * Moves the pointer to a point of the shown dialog and draws until the screen settled.
     *
     * @param dialogX the x in the dialog's pixels
     * @param dialogY the y in the dialog's pixels
     */
    public void hover(float dialogX, float dialogY) {
        GlueCanvas canvas = this.glue.getCanvas();

        this.glue.handleInput(InputEvent.createPointerMoved(canvas.toScreenX((canvas.getInterfaceWidth() - DIALOG_WIDTH) / CENTER_DIVISOR + dialogX),
                                                            canvas.toScreenY(dialogY)));
        this.settle();
    }

    /**
     * Types a text into the focused input and draws until the screen settled.
     *
     * @param text the text
     */
    public void type(@NotNull String text) {
        text.codePoints().forEach(codePoint -> this.glue.handleInput(InputEvent.createCharacterTyped(codePoint)));
        this.settle();
    }

    /**
     * Presses and releases a key and draws until the screen settled.
     *
     * @param key the key, for example {@link Key#ENTER}
     */
    public void press(@NotNull Key key) {
        this.glue.handleInput(InputEvent.createKey(InputEvent.Type.KEY_PRESSED, key, NO_MODIFIERS));
        this.glue.handleInput(InputEvent.createKey(InputEvent.Type.KEY_RELEASED, key, NO_MODIFIERS));
        this.settle();
    }

    /**
     * @return the recorded Beacon.Link of the client, whose sessions report its failure
     */
    public @NotNull RecordingBeaconLink getBeaconLink() {
        return this.beaconLink;
    }

    /**
     * @return the client's resources, which name the run's player
     */
    public @NotNull ClientContext getClient() {
        return this.client;
    }

    /**
     * @return {@code true} if a popup is open over the screen, {@code false} otherwise
     */
    public boolean isPopupOpen() {
        return this.glue.isPopupOpen();
    }

    /**
     * @return the dialogs of the open popups, the one over the screen first
     */
    public @NotNull List<ScrDialog> getPopupDialogs() {
        List<ScrDialog> dialogs = new ArrayList<>();

        for (ScrDialogLayer layer : this.glue.getPopupLayers()) {
            dialogs.add(layer.getDialog());
        }

        return dialogs;
    }

    /**
     * @return the dialog of the shown screen, under its popups
     */
    public @NotNull ScrDialog getScreenDialog() {
        return this.client.getDialogStack().getLayers().getLast().getDialog();
    }

    /**
     * Closes the glue, the client resources, the framebuffer and the host.
     */
    @Override
    public void close() {
        IgnoreException.execute(
                this.glue::close,
                this.client::close,
                () -> GL30C.glDeleteFramebuffers(this.framebuffer),
                () -> GL11C.glDeleteTextures(this.colorTexture),
                this.host::close
        );
    }
}
