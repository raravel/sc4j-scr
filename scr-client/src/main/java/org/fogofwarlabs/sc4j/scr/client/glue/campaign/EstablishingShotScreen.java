package org.fogofwarlabs.sc4j.scr.client.glue.campaign;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.video.VideoStream;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueContext;
import org.fogofwarlabs.sc4j.scr.client.glue.GluePalette;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueScreen;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.DialogPainter;
import org.fogofwarlabs.sc4j.scr.data.format.casc.SoundPathResolver;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceTimers;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plays campaign establishing-shot pages with backgrounds, video, sounds, text fades and timer-driven scrolling.
 */
public final class EstablishingShotScreen extends GlueScreen {
    private static final @NotNull Logger LOGGER = Logger.of(EstablishingShotScreen.class);

    private static final @NotNull String DIALOG = "credits";
    private static final @NotNull String PALETTE_FOLDER = "glue/palpa";
    private static final @NotNull String TEXTURE_KEY_PREFIX = "establishing@";
    /**
     * The localized script files: {@code rez/<name>.txt}, with an optional HD variant {@code rez/<name>_hd.txt}.
     */
    private static final @NotNull String SCRIPT_FOLDER = "rez/";
    private static final @NotNull String SCRIPT_EXTENSION = ".txt";

    private static final int VIDEO_CONTROL = 7;
    private static final int TEXT_BOX = 8;
    private static final int BACKGROUND_CONTROL = 9;
    private static final int LOWER_LEFT = 6;
    private static final int DIALOG_WIDTH = 640;
    /**
     * The text formats of locations one through six.
     */
    private static final int @NotNull [] LOCATION_FORMATS = {
            GlueCanvas.FORMAT_MIDDLE | GlueCanvas.FORMAT_LEFT, GlueCanvas.FORMAT_MIDDLE | GlueCanvas.FORMAT_RIGHT,
            GlueCanvas.FORMAT_MIDDLE | GlueCanvas.FORMAT_CENTER, GlueCanvas.FORMAT_MIDDLE | GlueCanvas.FORMAT_CENTER,
            GlueCanvas.FORMAT_MIDDLE | GlueCanvas.FORMAT_CENTER, GlueCanvas.FORMAT_BOTTOM | GlueCanvas.FORMAT_LEFT
    };
    private static final int TEXT_BOX_FORMAT = GlueCanvas.FORMAT_TOP | GlueCanvas.FORMAT_LEFT;
    private static final int TEXT_FADE_MILLISECONDS = 500;
    private static final int SCROLL_DELAY_MILLISECONDS = 3000;
    private static final int SCROLL_STEP_MILLISECONDS = 125;
    /**
     * The timer that starts delayed text scrolling and then repeats each scroll step.
     */
    private static final int SCROLL_TIMER = 1;
    /**
     * The timer that marks the text-box fade interval.
     */
    private static final int FADE_TIMER = 2;
    /**
     * The text box takes a quarter of the extra width on its left and three quarters on its right.
     */
    private static final int TEXT_BOX_EXTRA_DIVISOR = 4;
    private static final int TEXT_BOX_RIGHT_EXTRA_QUARTERS = 3;
    private static final int LOWER_LEFT_MARGIN_DIVISOR = 40;
    private static final int LOWER_LEFT_HD_DIVISOR = 8;
    /**
     * The lower-left text ends a quarter of the width before the right edge.
     */
    private static final int LOWER_LEFT_RIGHT_MARGIN_DIVISOR = 4;
    private static final int CENTER_DIVISOR = 2;
    private static final int OPAQUE_WHITE = 0xFFFFFFFF;
    private static final int ALPHA_MASK = 0xFF;
    private static final @NotNull String HD_SUFFIX = "_hd";
    /**
     * The font color path separators: backslashes become slashes before the palette folder is taken.
     */
    private static final char BACKSLASH = '\\';
    private static final char SLASH = '/';
    /**
     * The options' Original 1998 Campaign ({@code originalCampaign}).
     */
    private static final @NotNull String ORIGINAL_CAMPAIGN = "audio.originalCampaign";

    /**
     * The key of this screen's video frame texture in the canvas's dynamic textures.
     */
    private final @NotNull String textureKey;

    private final @NotNull String scriptName;
    private final @Nullable String music;
    private final @NotNull Runnable finished;

    private @Nullable EstablishingShot shot;
    private int pageIndex;
    private int scrollSteps;
    private long deadline;

    private @Nullable VideoStream stream;
    private long clipStart;
    private int texture;
    private int textureWidth;
    private int textureHeight;

    private final @NotNull Map<String, GluePalette> palettes;
    private boolean ended;

    /**
     * Creates a {@link EstablishingShotScreen} instance.
     *
     * @param scriptName the script name, for example {@code EstP01} ({@link EstablishingShot#getName})
     * @param finished   what follows the shot
     */
    public EstablishingShotScreen(@NotNull String scriptName, @NotNull Runnable finished) {
        this(scriptName, null, finished);
    }

    /**
     * Creates a {@link EstablishingShotScreen} instance that plays a script with an optional music track.
     *
     * @param scriptName the script name, for example {@code epilog}
     * @param music      the glue music track, or {@code null} to end the music
     * @param finished   what follows the script
     */
    public EstablishingShotScreen(@NotNull String scriptName, @Nullable String music, @NotNull Runnable finished) {
        super(DIALOG, PALETTE_FOLDER, null);

        this.textureKey = TEXTURE_KEY_PREFIX + System.identityHashCode(this);

        this.scriptName = scriptName;
        this.music      = music;
        this.finished   = finished;

        this.shot        = null;
        this.pageIndex   = -1;
        this.scrollSteps = 0;
        this.deadline    = 0;

        this.stream        = null;
        this.clipStart     = -1;
        this.texture       = 0;
        this.textureWidth  = 0;
        this.textureHeight = 0;

        this.palettes = new HashMap<>();
        this.ended    = false;
    }

    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * Selects the glue music, reads the script and displays its first page when present.
     *
     * @param glueContext the shared services
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) {
        if (this.music == null) {
            glueContext.getAudio().stopMusic();
        } else {
            glueContext.getAudio().playMusic(this.music);
        }

        this.shot = this.readShot(glueContext);

        if (this.shot == null || this.shot.getPages().isEmpty()) {
            this.end();

            return;
        }

        this.showPage(0);
    }

    private @Nullable EstablishingShot readShot(@NotNull GlueContext glueContext) {
        String name = this.scriptName;
        GlueCanvas canvas = glueContext.getCanvas();

        // Prefer the HD script only when HD graphics are enabled and the original campaign option is disabled.
        boolean highDefinition = canvas.isHighDefinition() &&
                                 !(glueContext.getSettings().getOption(ORIGINAL_CAMPAIGN) instanceof Boolean original && original);

        for (String candidate : highDefinition ? new String[]{name + HD_SUFFIX, name} : new String[]{name}) {
            try {
                return EstablishingShot.parse(new String(canvas.readLocalized(SCRIPT_FOLDER + candidate + SCRIPT_EXTENSION), StandardCharsets.UTF_8));
            } catch (IOException exception) {
                // Try the plain script when a localized HD script is unavailable.
            }
        }

        LOGGER.warning("ESTABLISHING SHOT UNAVAILABLE: " + name);

        return null;
    }

    private EstablishingShot.@NotNull Page requirePage() {
        EstablishingShot current = this.shot;

        if (current == null) {
            throw Exceptions.newIllegalStateException("No establishing shot.");
        }

        return current.getPages().get(this.pageIndex);
    }

    private void showPage(int index) {
        EstablishingShot current = this.shot;

        if (current == null || index >= current.getPages().size()) {
            this.end();

            return;
        }

        this.closeClip();
        this.pageIndex = index;

        EstablishingShot.Page page = this.requirePage();
        long now = this.getTime();

        this.deadline = page.getDisplayTime() == 0 ? 0 : now + page.getDisplayTime();

        // The text box timers on the client's timer list: the fade from now, the scroll after its delay.
        InterfaceTimers timers = this.getContext().getTimers();

        this.scrollSteps = 0;
        timers.arm(this, FADE_TIMER, TEXT_FADE_MILLISECONDS, (int) now, clock -> timers.kill(this, FADE_TIMER));
        timers.arm(this,
                   SCROLL_TIMER,
                   SCROLL_DELAY_MILLISECONDS,
                   (int) now,
                   clock -> timers.arm(this, SCROLL_TIMER, SCROLL_STEP_MILLISECONDS, clock, step -> this.scrollSteps++));

        GlueContext glueContext = this.getContext();
        String video = page.getVideo();

        if (video != null) {
            try {
                this.stream = VideoStream.open(glueContext.getCanvas().readVideo(video), false);
            } catch (IOException | RuntimeException | LinkageError exception) {
                LOGGER.warning("ESTABLISHING SHOT CLIP UNAVAILABLE: " + video + ": " + exception.getMessage());
            }
        }

        String sound = page.getSound();

        if (sound != null) {
            this.playSound(sound);
        }
    }

    private void playSound(@NotNull String path) {
        GlueCanvas canvas = this.getContext().getCanvas();
        SoundPathResolver.AudioSet set = canvas.isHighDefinition() ? SoundPathResolver.AudioSet.REMASTERED : SoundPathResolver.AudioSet.CLASSIC;

        try {
            String resolved = new SoundPathResolver(canvas.getStorage(), canvas.getLocale(), set).resolveAsset(path);

            if (resolved != null) {
                this.getContext().getAudio().playData(canvas.getStorage().readFile(resolved));
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("ESTABLISHING SHOT SOUND UNAVAILABLE: " + path + ": " + exception.getMessage());
        }
    }

    /**
     * Called by {@link #update} for screen-specific timers.
     * <p>
     * Updates the page video and advances when its clip or its display deadline completes.
     *
     * @param time the time in milliseconds
     * @throws IllegalStateException if no establishing shot is loaded
     */
    @Override
    protected void onUpdate(long time) {
        if (this.ended || this.shot == null || this.pageIndex < 0) {
            return;
        }

        VideoStream clip = this.stream;

        if (clip != null) {
            if (this.clipStart < 0) {
                this.clipStart = time;
            }

            byte[] pixels = clip.readFrameAt(time - this.clipStart);

            if (pixels != null) {
                GlueCanvas canvas = this.getContext().getCanvas();

                this.texture       = canvas.updateTexture(this.textureKey, clip.getWidth(), clip.getHeight(), pixels);
                this.textureWidth  = clip.getWidth();
                this.textureHeight = clip.getHeight();
            }
        }

        EstablishingShot.Page page = this.requirePage();
        boolean next = page.isAutoProceed() ? clip == null || clip.isFinished() : this.deadline != 0 && time > this.deadline;

        if (next) {
            this.showPage(this.pageIndex + 1);
        }
    }

    /**
     * Draws the screen and its popup.
     * <p>
     * This screen draws the active page instead of its dialog: the background, the fitted video and the text at their
     * dialog locations, on a black background.
     *
     * @param canvas the glue canvas
     * @throws IOException           if an image cannot be read
     * @throws IllegalStateException if no establishing shot is loaded
     */
    @Override
    public void draw(@NotNull GlueCanvas canvas) throws IOException {
        float width = canvas.getInterfaceWidth();
        float extra = width - DIALOG_WIDTH;

        canvas.fillRectangle(0, 0, width, GlueCanvas.INTERFACE_HEIGHT, GlueCanvas.OPAQUE_BLACK);

        if (this.shot == null || this.pageIndex < 0 || this.ended) {
            return;
        }

        EstablishingShot.Page page = this.requirePage();
        DialogControl background = ScrDialogControlUtils.findControl(this.getDialog(), BACKGROUND_CONTROL);
        String backgroundPath = page.getBackground();

        if (background != null && canvas.hasImage(backgroundPath)) {
            GlueCanvas.Image image = canvas.getImage(backgroundPath);

            canvas.drawImage(image, background.getX(), background.getY(), background.getWidth() + extra, background.getHeight());
        }

        DialogControl video = ScrDialogControlUtils.findControl(this.getDialog(), VIDEO_CONTROL);

        if (video != null && this.texture != 0 && this.textureWidth > 0 && this.textureHeight > 0) {
            float boxWidth = video.getWidth() + extra;
            float scale = Math.min(boxWidth / this.textureWidth, video.getHeight() / (float) this.textureHeight);
            float drawnWidth = this.textureWidth * scale;
            float drawnHeight = this.textureHeight * scale;

            canvas.drawTexture(this.texture,
                               (width - drawnWidth) / CENTER_DIVISOR, video.getY() + (video.getHeight() - drawnHeight) / CENTER_DIVISOR,
                               drawnWidth, drawnHeight,
                               OPAQUE_WHITE);
        }

        this.drawText(canvas, page, width, extra);
    }

    private void drawText(@NotNull GlueCanvas canvas, EstablishingShot.@NotNull Page page, float width, float extra) {
        int location = page.getLocation();
        String text = page.getText();
        DialogControl control = location == EstablishingShot.NO_LOCATION ? null : ScrDialogControlUtils.findControl(this.getDialog(), location);

        if (control == null || text.isEmpty()) {
            return;
        }

        float left = control.getX();
        float right = control.getX() + control.getWidth();

        if (location == TEXT_BOX) {
            left += extra / TEXT_BOX_EXTRA_DIVISOR;
            right += extra * TEXT_BOX_RIGHT_EXTRA_QUARTERS / TEXT_BOX_EXTRA_DIVISOR;
        } else if (location == LOWER_LEFT) {
            boolean highDefinition = canvas.isHighDefinition();

            left  = (int) (width / LOWER_LEFT_MARGIN_DIVISOR) + (highDefinition ? (int) (width / LOWER_LEFT_HD_DIVISOR) : 0);
            right = width - (int) (width / LOWER_LEFT_RIGHT_MARGIN_DIVISOR);
        }

        GluePalette palette = this.getPagePalette(page);
        int color = palette.getCodeColor(DialogPainter.CODE_TEXT);
        int format = location == TEXT_BOX ? TEXT_BOX_FORMAT : LOCATION_FORMATS[location - 1];
        int slot = canvas.getControlFontSlot(control);
        float top = control.getY();
        float height = control.getHeight();

        if (location == TEXT_BOX) {
            // Draw the text box with the medium font while its fade timer is armed.
            slot = GlueCanvas.NORMAL_FONT;

            InterfaceTimers timers = this.getContext().getTimers();
            float alpha = !timers.isArmed(this, FADE_TIMER)
                          ? 1f
                          : Math.min(1f, (this.getTime() - timers.getStart(this, FADE_TIMER)) / (float) TEXT_FADE_MILLISECONDS);

            color = color & ~ALPHA_MASK | Math.round(alpha * ALPHA_MASK);

            float scroll = this.getScroll(canvas, slot, text, right - left, height);

            canvas.setClip(left, top, right - left, height);
            canvas.drawText(slot, text, left, top - scroll, right - left, height + scroll, format, color, palette);
            canvas.setClip(0, 0, GlueCanvas.NO_CLIP_SIZE, 0);

            return;
        }

        canvas.drawText(slot, text, left, top, right - left, height, format, color, palette);
    }

    /**
     * Returns the text box scroll: a text taller than the box moves one step per firing of the scroll timer (after
     * {@code 3000} ms, then every {@code 125} ms) up to its end, where the timer stops.
     *
     * @param canvas the glue canvas
     * @param slot   the font slot of the text
     * @param text   the text box text
     * @param width  the text box width in interface pixels
     * @param height the text box height in interface pixels
     * @return the scroll offset in interface pixels
     */
    private float getScroll(@NotNull GlueCanvas canvas, int slot, @NotNull String text, float width, float height) {
        List<String> lines = InterfaceText.wrap(canvas.getFont(slot), text, width);
        float textHeight = InterfaceText.getLayoutHeight(canvas.getFont(slot), lines, TEXT_BOX_FORMAT);

        if (textHeight <= height) {
            return 0;
        }

        if (this.scrollSteps >= textHeight - height) {
            this.getContext().getTimers().kill(this, SCROLL_TIMER);
        }

        return Math.min(textHeight - height, this.scrollSteps);
    }

    private @NotNull GluePalette getPagePalette(EstablishingShot.@NotNull Page page) {
        String path = page.getFontColor().replace(BACKSLASH, SLASH);
        int slashIndex = path.lastIndexOf(SLASH);
        String folder = (slashIndex < 0 ? path : path.substring(0, slashIndex)).toLowerCase(Locale.ROOT);
        GluePalette palette = this.palettes.get(folder);

        if (palette == null) {
            try {
                palette = GluePalette.load(this.getContext().getCanvas().getStorage(), folder);
            } catch (IOException exception) {
                palette = this.getPalette();
            }

            this.palettes.put(folder, palette);
        }

        return palette;
    }

    /**
     * Called first for every key of the screen (after an open popup): a screen without controls, such as a cinematic, takes its keys here.
     * <p>
     * Escape ends the shot, Home restarts it, Enter and Space advance a page, and every key is consumed.
     *
     * @param key the key
     * @return {@code true} if the key was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onKey(char key) {
        if (this.ended || this.shot == null) {
            return true;
        }

        if (key == KEY_ESCAPE) {
            this.end();
        } else if (key == KEY_HOME) {
            this.showPage(0);
        } else if (key == KEY_ENTER || key == KEY_SPACE) {
            this.showPage(this.pageIndex + 1);
        }

        return true;
    }

    /**
     * Called first for every press of the screen that no open popup or open combo box takes, while the screen takes input.
     * <p>
     * A press advances an active shot by a page; every press is consumed.
     *
     * @param x the interface x
     * @param y the interface y
     * @return {@code true} if the press was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onPointerPressed(float x, float y) {
        if (!this.ended && this.shot != null) {
            this.showPage(this.pageIndex + 1);
        }

        return true;
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     * <p>
     * This screen ignores control activation.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
    }

    private void end() {
        if (this.ended) {
            return;
        }

        this.ended = true;
        this.getContext().getTimers().killAll(this);
        this.closeClip();
        this.finished.run();
    }

    private void closeClip() {
        if (this.stream != null) {
            this.stream.close();
        }

        this.stream    = null;
        this.clipStart = -1;

        if (this.texture != 0) {
            this.getContext().getCanvas().releaseTexture(this.textureKey);
        }

        this.texture = 0;
    }

    /**
     * Releases the videos and the popup.
     * <p>
     * Cancels the active page timers and closes the video before releasing the inherited screen resources.
     */
    @Override
    public void close() {
        if (this.shot != null) {
            this.getContext().getTimers().killAll(this);
        }

        this.closeClip();
        super.close();
    }
}
