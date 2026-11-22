package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsolePlacement;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceRenderer;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceTransform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL30C;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Draws console dialogs through the mask of a console placement: the dialogs are drawn into a transparent texture of the drawing coordinates' size,
 * which is then drawn over the resumed target in the runs of the mask's shown pixels only, in the classic screen centered in the interface width.
 */
final class ConsoleMaskLayer {
    /**
     * The values of one run: its classic screen row, its first column and the column after it.
     */
    private static final int RUN_VALUES = 3;
    private static final int RUN_START = 1;
    private static final int RUN_END = 2;
    /**
     * The packed color that draws a texture unchanged.
     */
    private static final int OPAQUE_WHITE = 0xFFFFFFFF;

    private int framebuffer;
    private int texture;
    private int width;
    private int height;
    private @Nullable ScrConsolePlacement runsPlacement;
    private long runsChange;
    private int @NotNull [] runs;
    private int runCount;

    /**
     * Creates a {@link ConsoleMaskLayer} instance without a texture.
     */
    ConsoleMaskLayer() {
        this.framebuffer   = 0;
        this.texture       = 0;
        this.width         = 0;
        this.height        = 0;
        this.runsPlacement = null;
        this.runsChange    = -1;
        this.runs          = new int[0];
        this.runCount      = 0;
    }

    /**
     * Ends the renderer's drawing and begins drawing into the cleared layer texture, in the same drawing coordinates.
     *
     * @param renderer the interface renderer, drawing
     */
    void begin(@NotNull InterfaceRenderer renderer) {
        int coordinateWidth = renderer.getWidth();
        int coordinateHeight = renderer.getHeight();

        renderer.end();

        if (this.framebuffer == 0 || coordinateWidth != this.width || coordinateHeight != this.height) {
            this.release();
            this.allocate(coordinateWidth, coordinateHeight);
        }

        renderer.begin(this.framebuffer, this.width, this.height, this.width, this.height);
        GL30C.glClearColor(0, 0, 0, 0);
        GL30C.glClear(GL30C.GL_COLOR_BUFFER_BIT);
    }

    /**
     * Ends the drawing into the layer, resumes the target and draws the layer over it where the placement's mask shows the console.
     *
     * @param renderer  the interface renderer, drawing into the layer
     * @param resume    begins the drawing into the target again, its clip included
     * @param console   the console layout of the drawn dialogs
     * @param placement the masked placement
     */
    void end(@NotNull InterfaceRenderer renderer, @NotNull Runnable resume, @NotNull ConsoleLayout console, @NotNull ScrConsolePlacement placement) {
        renderer.end();
        resume.run();
        this.updateRuns(placement);
        // The layer holds premultiplied color.
        GL30C.glBlendFuncSeparate(GL30C.GL_ONE, GL30C.GL_ONE_MINUS_SRC_ALPHA, GL30C.GL_ONE, GL30C.GL_ONE_MINUS_SRC_ALPHA);

        int center = InterfaceTransform.getCenteredDialogLeft(console.getInterfaceWidth());

        for (int index = 0; index < this.runCount; index++) {
            int row = this.runs[index * RUN_VALUES];
            float left = console.toScreenX(center + this.runs[index * RUN_VALUES + RUN_START]);
            float right = console.toScreenX(center + this.runs[index * RUN_VALUES + RUN_END]);
            float top = console.toScreenY(row);
            float bottom = console.toScreenY(row + 1);

            // The layer's rows run from the bottom of the texture.
            renderer.drawTexture(this.texture, left, top, right - left, bottom - top,
                                 left / this.width, 1 - top / this.height, right / this.width, 1 - bottom / this.height,
                                 OPAQUE_WHITE);
        }

        renderer.end();
        resume.run();
    }

    private void allocate(int layerWidth, int layerHeight) {
        int previous = GL30C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);

        this.texture = GL30C.glGenTextures();
        GL30C.glBindTexture(GL30C.GL_TEXTURE_2D, this.texture);
        GL30C.glTexImage2D(GL30C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA8, layerWidth, layerHeight, 0, GL30C.GL_RGBA, GL30C.GL_UNSIGNED_BYTE,
                           (ByteBuffer) null);
        GL30C.glTexParameteri(GL30C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_MIN_FILTER, GL30C.GL_NEAREST);
        GL30C.glTexParameteri(GL30C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_MAG_FILTER, GL30C.GL_NEAREST);
        GL30C.glTexParameteri(GL30C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_WRAP_S, GL30C.GL_CLAMP_TO_EDGE);
        GL30C.glTexParameteri(GL30C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_WRAP_T, GL30C.GL_CLAMP_TO_EDGE);

        this.framebuffer = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_TEXTURE_2D, this.texture, 0);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, previous);

        this.width  = layerWidth;
        this.height = layerHeight;
    }

    /**
     * Collects the runs of shown pixels of the placement's mask, again only after it changed.
     *
     * @param placement the masked placement
     */
    private void updateRuns(@NotNull ScrConsolePlacement placement) {
        if (this.runsPlacement == placement && this.runsChange == placement.getChangeCount()) {
            return;
        }

        this.runCount = 0;

        for (int row = 0; row < placement.getHeight(); row++) {
            int column = 0;

            while (column < placement.getWidth()) {
                if (!placement.isShown(column, row)) {
                    column++;
                    continue;
                }

                int start = column;

                while (column < placement.getWidth() && placement.isShown(column, row)) {
                    column++;
                }

                if ((this.runCount + 1) * RUN_VALUES > this.runs.length) {
                    this.runs = Arrays.copyOf(this.runs, Math.max(RUN_VALUES * placement.getHeight(), this.runs.length * 2));
                }

                this.runs[this.runCount * RUN_VALUES]             = row;
                this.runs[this.runCount * RUN_VALUES + RUN_START] = start;
                this.runs[this.runCount * RUN_VALUES + RUN_END]   = column;
                this.runCount++;
            }
        }

        this.runsPlacement = placement;
        this.runsChange    = placement.getChangeCount();
    }

    /**
     * Deletes the layer texture and its framebuffer.
     */
    void release() {
        if (this.framebuffer != 0) {
            GL30C.glDeleteFramebuffers(this.framebuffer);
            GL30C.glDeleteTextures(this.texture);
        }

        this.framebuffer = 0;
        this.texture     = 0;
        this.width       = 0;
        this.height      = 0;
    }
}
