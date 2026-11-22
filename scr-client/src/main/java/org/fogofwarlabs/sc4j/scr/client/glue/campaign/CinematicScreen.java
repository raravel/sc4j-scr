package org.fogofwarlabs.sc4j.scr.client.glue.campaign;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.audio.AudioStream;
import org.fogofwarlabs.sc4j.engine.client.video.VideoStream;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueContext;
import org.fogofwarlabs.sc4j.scr.client.glue.GluePalette;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueScreen;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.DialogPainter;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays campaign cinematic sequences with audio and optional localized subtitles, advancing on user input or clip completion.
 */
public final class CinematicScreen extends GlueScreen {
    /**
     * {@code blizzard}: the Blizzard logo, played before an intro.
     */
    public static final int BLIZZARD = 0;
    /**
     * {@code starintr}: the original game's intro.
     */
    public static final int INTRO = 1;
    /**
     * {@code starXintr}: the Brood War intro.
     */
    public static final int EXPANSION_INTRO = 24;

    private static final @NotNull Logger LOGGER = Logger.of(CinematicScreen.class);

    /**
     * The cinematic clip names by cinematic index.
     */
    private static final @NotNull String @NotNull [] CLIPS = {
            "blizzard", "starintr", "PreTerran_1", "PreTerran_2", "PreTerran_3", "PreTerran_4", "Terran1", "Terran2", "Terran3", "Terran4",
            "PreZerg_2", "PreZerg_3", "PreZerg_4", "Zerg1", "Zerg2", "Zerg3", "Zerg4", "PreProtoss_1", "PreProtoss_2", "PreProtoss_3",
            "Protoss1", "Protoss2", "Protoss3", "Protoss4", "starXintr", "xProtoss", "xTerran", "xZerg"
    };
    private static final @NotNull String CLIP_FOLDER = "smk\\";
    private static final @NotNull String CLIP_EXTENSION = ".webm";

    /**
     * Pairs of cinematic indices and their preceding cinematic indices.
     */
    private static final int @NotNull [] @NotNull [] PRELUDES = {
            {6, 2}, {7, 3}, {8, 4}, {9, 5}, {14, 10}, {15, 11}, {16, 12}, {20, 17}, {21, 18}, {22, 19}
    };
    private static final int PRELUDE_CINEMATIC_FIELD = 0;
    private static final int PRELUDE_FIELD = 1;

    private static final @NotNull String PALETTE_FOLDER = "glue/palmm";
    /**
     * The dialog name of a screen without a dialog.
     */
    private static final @NotNull String NO_DIALOG = "";
    private static final @NotNull String TEXTURE_KEY_PREFIX = "cinematic@";

    private static final @NotNull String SUBTITLE_HOTKEY = "video_toggle_subtitles_hotkey";
    /**
     * The Cinematic Subtitles option key.
     */
    private static final @NotNull String CINEMATIC_SUBTITLES = "audio.cinematicSubtitles";
    private static final long AUDIO_LEAD_MILLISECONDS = 500;
    private static final int OPAQUE_WHITE = 0xFFFFFFFF;
    private static final int CENTER_DIVISOR = 2;
    /**
     * The picture aspect of the classic clips: the original {@code 640x480} screen.
     */
    private static final float CLASSIC_DISPLAY_ASPECT = 4f / 3f;
    private static final int SUBTITLE_TOP = 400;
    private static final int SUBTITLE_HEIGHT = 70;

    /**
     * The SubRip subtitle files: {@code smk/<clip>.srt}, cue blocks separated by blank lines.
     */
    private static final @NotNull String SUBTITLE_FOLDER = "smk/";
    private static final @NotNull String SUBTITLE_EXTENSION = ".srt";
    private static final @NotNull String BYTE_ORDER_MARK = "﻿";
    private static final @NotNull String CARRIAGE_RETURN = "\r";
    private static final @NotNull String LINE_SEPARATOR = "\n";
    private static final @NotNull String CUE_SEPARATOR_PATTERN = "\n\n+";
    private static final @NotNull String TIMING_SEPARATOR = "-->";
    private static final @NotNull String TIME_FIELD_SEPARATOR_PATTERN = "[:,]";
    private static final char DECIMAL_POINT = '.';
    private static final char DECIMAL_COMMA = ',';
    /**
     * The lines of a cue block that hold its timing: the line after the index line, or the first line of a cue without an index.
     */
    private static final int NUMBERED_TIMING_LINE = 1;
    private static final int UNNUMBERED_TIMING_LINE = 0;
    /**
     * The fields of a timing line {@code start --> end}.
     */
    private static final int START_TIME_FIELD = 0;
    private static final int END_TIME_FIELD = 1;
    /**
     * The fields of a SubRip time {@code HH:MM:SS,mmm}.
     */
    private static final int HOURS_FIELD = 0;
    private static final int MINUTES_FIELD = 1;
    private static final int SECONDS_FIELD = 2;
    private static final int MILLISECONDS_FIELD = 3;
    private static final int MINUTES_PER_HOUR = 60;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int MILLISECONDS_PER_SECOND = 1000;

    /**
     * Stores the start, end and text of one subtitle cue.
     */
    private static final class Cue {
        private final long start;
        private final long end;
        private final @NotNull String text;

        /**
         * Creates a {@link Cue} instance for the supplied playback interval.
         *
         * @param start the inclusive interval start in milliseconds
         * @param end   the exclusive interval end in milliseconds
         * @param text  the subtitle text
         */
        private Cue(long start, long end, @NotNull String text) {
            this.start = start;
            this.end   = end;
            this.text  = text;
        }
    }

    /**
     * Creates a {@link List} instance with the clip sequence of a cinematic: the requested cinematic after its prelude when one is configured.
     *
     * @param cinematic a cinematic of the table
     * @return the created {@link List} instance: the cinematic preceded by its prelude, if it has one
     */
    public static @NotNull List<Integer> createSequenceWithPrelude(int cinematic) {
        for (int[] pair : PRELUDES) {
            if (pair[PRELUDE_CINEMATIC_FIELD] == cinematic) {
                return List.of(pair[PRELUDE_FIELD], cinematic);
            }
        }

        return List.of(cinematic);
    }

    private static @NotNull List<Cue> readSubtitles(@NotNull GlueContext glueContext, @NotNull String name) {
        try {
            byte[] contents = glueContext.getCanvas().readLocalized(SUBTITLE_FOLDER + name + SUBTITLE_EXTENSION);

            return parseSubtitles(new String(contents, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return List.of();
        }
    }

    /**
     * Parses SubRip cues: an index line, a {@code HH:MM:SS,mmm --> HH:MM:SS,mmm} line, then text lines up to a blank line.
     *
     * @param text the subtitle file text
     * @return the cues that parse, in file order
     */
    private static @NotNull List<Cue> parseSubtitles(@NotNull String text) {
        List<Cue> cues = new ArrayList<>();
        String[] blocks = text.replace(BYTE_ORDER_MARK, "").replace(CARRIAGE_RETURN, "").split(CUE_SEPARATOR_PATTERN);

        for (String block : blocks) {
            String[] lines = block.strip().split(LINE_SEPARATOR);
            int timing = lines.length > NUMBERED_TIMING_LINE && lines[NUMBERED_TIMING_LINE].contains(TIMING_SEPARATOR)
                         ? NUMBERED_TIMING_LINE
                         : lines.length > UNNUMBERED_TIMING_LINE && lines[UNNUMBERED_TIMING_LINE].contains(TIMING_SEPARATOR)
                           ? UNNUMBERED_TIMING_LINE
                           : -1;

            if (timing < 0) {
                continue;
            }

            String[] times = lines[timing].split(TIMING_SEPARATOR);
            StringBuilder cue = new StringBuilder();

            for (int lineIndex = timing + 1; lineIndex < lines.length; lineIndex++) {
                cue.append(cue.isEmpty() ? "" : LINE_SEPARATOR).append(lines[lineIndex]);
            }

            try {
                cues.add(new Cue(parseTime(times[START_TIME_FIELD]), parseTime(times[END_TIME_FIELD]), cue.toString()));
            } catch (RuntimeException exception) {
                LOGGER.warning("SUBTITLE CUE SKIPPED: " + lines[timing]);
            }
        }

        return cues;
    }

    private static long parseTime(@NotNull String value) {
        String[] fields = value.strip().replace(DECIMAL_POINT, DECIMAL_COMMA).split(TIME_FIELD_SEPARATOR_PATTERN);

        return ((Long.parseLong(fields[HOURS_FIELD]) * MINUTES_PER_HOUR + Long.parseLong(fields[MINUTES_FIELD])) * SECONDS_PER_MINUTE +
                Long.parseLong(fields[SECONDS_FIELD])) * MILLISECONDS_PER_SECOND + Long.parseLong(fields[MILLISECONDS_FIELD]);
    }

    /**
     * The key of this screen's video frame texture in the canvas's dynamic textures.
     */
    private final @NotNull String textureKey;

    private final @NotNull List<Integer> clips;
    private final @NotNull Runnable finished;

    private int clipIndex;
    private @Nullable VideoStream stream;
    private @Nullable AudioStream audioStream;
    private long clipStart;
    private @NotNull List<Cue> cues;

    private int texture;
    private int textureWidth;
    private int textureHeight;

    private boolean subtitles;
    private boolean ended;

    /**
     * Creates a {@link CinematicScreen} instance.
     *
     * @param clips    the cinematics to play in order
     * @param finished what follows the last one
     */
    public CinematicScreen(@NotNull List<Integer> clips, @NotNull Runnable finished) {
        super(NO_DIALOG, PALETTE_FOLDER, null);

        this.textureKey = TEXTURE_KEY_PREFIX + System.identityHashCode(this);

        this.clips    = List.copyOf(clips);
        this.finished = finished;

        this.clipIndex   = -1;
        this.stream      = null;
        this.audioStream = null;
        this.clipStart   = -1;
        this.cues        = List.of();

        this.texture       = 0;
        this.textureWidth  = 0;
        this.textureHeight = 0;

        this.subtitles = true;
        this.ended     = false;
    }

    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * Loads the subtitle option, stops glue music and starts the cinematic sequence.
     *
     * @param glueContext the shared services
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) {
        // The hotkey changes the active subtitle flag without changing the saved option.
        this.subtitles = !(glueContext.getSettings().getOption(CINEMATIC_SUBTITLES) instanceof Boolean shown) || shown;

        glueContext.getAudio().stopMusic();
        this.startNext();
    }

    private void startNext() {
        this.closeClip();
        this.clipIndex++;

        if (this.clipIndex >= this.clips.size()) {
            if (!this.ended) {
                this.ended = true;
                this.finished.run();
            }

            return;
        }

        int cinematic = this.clips.get(this.clipIndex);

        if (cinematic < 0 || cinematic >= CLIPS.length) {
            this.startNext();

            return;
        }

        GlueContext glueContext = this.getContext();
        String name = CLIPS[cinematic];

        try {
            this.stream = VideoStream.open(glueContext.getCanvas().readVideo(CLIP_FOLDER + name + CLIP_EXTENSION), true);
        } catch (IOException | RuntimeException | LinkageError exception) {
            LOGGER.warning("CINEMATIC UNAVAILABLE: " + name + ": " + exception.getMessage());
            this.startNext();

            return;
        }

        this.cues        = readSubtitles(glueContext, name);
        this.audioStream = this.stream.getAudioChannels() > 0 ? glueContext.getAudio().openStream() : null;
        this.clipStart   = -1;
    }

    /**
     * Called by {@link #update} for screen-specific timers.
     * <p>
     * Queues clip audio, updates the frame texture and advances finished clips.
     *
     * @param time the time in milliseconds
     */
    @Override
    protected void onUpdate(long time) {
        VideoStream clip = this.stream;

        if (clip == null || this.ended) {
            return;
        }

        if (this.clipStart < 0) {
            this.clipStart = time;
        }

        long elapsed = time - this.clipStart;
        byte[] audioSamples = clip.readAudio(elapsed + AUDIO_LEAD_MILLISECONDS);

        AudioStream audio = this.audioStream;

        if (audio != null && audioSamples.length > 0) {
            this.getContext().getAudio().queueStream(audio, audioSamples, clip.getAudioChannels(), clip.getAudioSampleRate());
        }

        byte[] pixels = clip.readFrameAt(elapsed);

        if (pixels != null) {
            GlueCanvas canvas = this.getContext().getCanvas();

            this.texture       = canvas.updateTexture(this.textureKey, clip.getWidth(), clip.getHeight(), pixels);
            this.textureWidth  = clip.getWidth();
            this.textureHeight = clip.getHeight();
        }

        if (clip.isFinished()) {
            this.startNext();
        }
    }

    /**
     * Draws the screen and its popup.
     * <p>
     * This screen has no dialog: it draws the fitted cinematic frame and the active subtitle cues on a black background.
     *
     * @param canvas the glue canvas
     * @throws IOException if an image cannot be read
     */
    @Override
    public void draw(@NotNull GlueCanvas canvas) throws IOException {
        float width = canvas.getInterfaceWidth();

        canvas.fillRectangle(0, 0, width, GlueCanvas.INTERFACE_HEIGHT, GlueCanvas.OPAQUE_BLACK);

        if (this.texture != 0 && this.textureWidth > 0 && this.textureHeight > 0) {
            // The classic clips (the SD set's cinematics and the Blizzard logo of every set) are SMK transcodes of 320x308
            // frames, whose square-pixel aspect is not their picture's: they fill the original 4:3 screen. A frame narrower
            // than 4:3 is therefore shown at 4:3; the remastered 16:9 clips keep their own aspect.
            float aspect = Math.max(this.textureWidth / (float) this.textureHeight, CLASSIC_DISPLAY_ASPECT);
            float drawnHeight = Math.min(GlueCanvas.INTERFACE_HEIGHT, width / aspect);
            float drawnWidth = drawnHeight * aspect;

            canvas.drawTexture(this.texture,
                               (width - drawnWidth) / CENTER_DIVISOR, (GlueCanvas.INTERFACE_HEIGHT - drawnHeight) / CENTER_DIVISOR,
                               drawnWidth, drawnHeight,
                               OPAQUE_WHITE);
        }

        if (!this.subtitles || this.clipStart < 0) {
            return;
        }

        long elapsed = this.getTime() - this.clipStart;

        for (Cue cue : this.cues) {
            if (elapsed >= cue.start && elapsed < cue.end) {
                GluePalette palette = this.getPalette();

                canvas.drawText(GlueCanvas.BIG_FONT,
                                cue.text,
                                0, SUBTITLE_TOP,
                                width, SUBTITLE_HEIGHT,
                                GlueCanvas.FORMAT_CENTER | GlueCanvas.FORMAT_MIDDLE,
                                palette.getCodeColor(DialogPainter.CODE_WHITE),
                                palette);
            }
        }
    }

    /**
     * Called first for every key of the screen (after an open popup): a screen without controls, such as a cinematic, takes its keys here.
     * <p>
     * The subtitle hotkey toggles the subtitles, Escape, Enter and Space advance to the next clip, and every key is consumed.
     *
     * @param key the key
     * @return {@code true} if the key was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onKey(char key) {
        String hotkey = this.getContext().getGluText().get(SUBTITLE_HOTKEY);

        if (InterfaceText.isHotkey(hotkey, key)) {
            this.subtitles = !this.subtitles;

            return true;
        }

        if (key == KEY_ESCAPE || key == KEY_ENTER || key == KEY_SPACE) {
            this.startNext();
        }

        return true;
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     * <p>
     * This screen has no controls and ignores activation.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
    }

    /**
     * Called first for every press of the screen that no open popup or open combo box takes, while the screen takes input.
     * <p>
     * Every press advances to the next clip and is consumed.
     *
     * @param x the interface x
     * @param y the interface y
     * @return {@code true} if the press was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onPointerPressed(float x, float y) {
        this.startNext();

        return true;
    }

    private void closeClip() {
        if (this.audioStream != null) {
            this.getContext().getAudio().closeStream(this.audioStream);
        }

        this.audioStream = null;

        if (this.stream != null) {
            this.stream.close();
        }

        this.stream = null;

        if (this.texture != 0) {
            this.getContext().getCanvas().releaseTexture(this.textureKey);
        }

        this.texture = 0;
    }

    /**
     * Releases the videos and the popup.
     * <p>
     * Closes the active cinematic audio, decoder and texture before releasing the inherited screen resources.
     */
    @Override
    public void close() {
        this.closeClip();
        super.close();
    }
}
