package org.fogofwarlabs.sc4j.scr.render;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.engine.client.render.GraphicsDevice;
import org.fogofwarlabs.sc4j.engine.client.render.ShaderProgram;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.system.MemoryUtil;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.function.IntUnaryOperator;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Draws solid rectangles, RGBA textures and filtered fog of war into the world frame.
 */
public final class OverlayPass implements AutoCloseable {
    /**
     * The palette index of a draw that has none (fog, HD): the destination index attachment keeps its value.
     */
    static final int NO_PALETTE_INDEX = -1;
    /**
     * The default level of visible tiles, the game's visible level BYTE (set to {@code 0x1F} at game start).
     */
    public static final int DEFAULT_VISIBLE_LEVEL = 31;

    /**
     * The number of bytes of an RGBA pixel.
     */
    public static final int RGBA_BYTES = 4;
    /**
     * The position of the green channel in an RGBA pixel or an RGB color.
     */
    public static final int RGBA_GREEN = 1;
    /**
     * The position of the blue channel in an RGBA pixel or an RGB color.
     */
    public static final int RGBA_BLUE = 2;
    /**
     * The position of the alpha channel in an RGBA pixel.
     */
    public static final int RGBA_ALPHA = 3;
    /**
     * The largest value of a color channel byte, as a float divisor.
     */
    public static final float CHANNEL_MAXIMUM = 255.0f;
    /**
     * The alpha byte of an opaque RGBA pixel.
     */
    public static final byte OPAQUE_ALPHA = (byte) 0xFF;
    /**
     * The bit position of the red channel in a packed {@code 0xRRGGBB} color.
     */
    public static final int RGB_RED_SHIFT = 16;
    /**
     * The bit position of the green channel in a packed {@code 0xRRGGBB} color.
     */
    public static final int RGB_GREEN_SHIFT = 8;
    /**
     * The packed {@code 0xRRGGBB} color that leaves a texture's colors unchanged.
     */
    static final int WHITE = 0xFFFFFF;

    /**
     * The renderer name of the shader failure messages.
     */
    private static final @NotNull String SHADER_PROGRAM_NAME = "Overlay";
    /**
     * The uniform names of the shaders: the drawing mode, the drawing coordinates' size and the sampled texture.
     */
    private static final @NotNull String MODE_UNIFORM = "mode";
    private static final @NotNull String FRAMEBUFFER_SIZE_UNIFORM = "framebufferSize";
    private static final @NotNull String OVERLAY_TEXTURE_UNIFORM = "overlayTexture";
    private static final int MAXIMUM_VERTICES = 6144;
    private static final int MODE_SOLID = 0;
    private static final int MODE_TEXTURE = 1;
    private static final int MODE_FOG = 2;
    /**
     * The opaque white RGBA pixel of the solid-color texture.
     */
    private static final int WHITE_PIXEL = 0xFFFFFFFF;
    /**
     * The mask of an unsigned byte.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The fog cell size in game pixels.
     */
    private static final int FOG_CELL_SIZE = 8;
    private static final @NotNull String VERTEX_SHADER = """
                                                         #version 330 core
                                                         layout(location = 0) in vec2 position;
                                                         layout(location = 1) in vec2 coordinates;
                                                         layout(location = 2) in vec4 color;
                                                         layout(location = 3) in float paletteIndex;
                                                         uniform vec2 framebufferSize;
                                                         out vec2 texCoord;
                                                         out vec4 vertexColor;
                                                         flat out float vertexIndex;
                                                         void main() {
                                                             gl_Position = vec4(position.x * 2.0 / framebufferSize.x - 1.0,
                                                                                1.0 - position.y * 2.0 / framebufferSize.y, 0.0, 1.0);
                                                             texCoord = coordinates;
                                                             vertexColor = color;
                                                             vertexIndex = paletteIndex;
                                                         }
                                                         """;
    private static final @NotNull String FRAGMENT_SHADER = """
                                                           #version 330 core
                                                           in vec2 texCoord;
                                                           in vec4 vertexColor;
                                                           flat in float vertexIndex;
                                                           uniform sampler2D overlayTexture;
                                                           uniform int mode;
                                                           layout(location = 0) out vec4 frag_color;
                                                           layout(location = 1) out vec4 frag_effect;
                                                           // The classic world's destination palette index (WorldRenderer attachment 2): lighting remaps,
                                                           // shadows and cloak refraction drawn later read it, so a palette-colored overlay writes its index.
                                                           layout(location = 2) out vec4 frag_index;
                                                           void main() {
                                                               float coverage = 1.0;
                                                               if (mode == 0) {
                                                                   frag_color = vertexColor;
                                                                   frag_effect = vec4(0.0, 0.0, 0.0, 1.0);
                                                               } else if (mode == 1) {
                                                                   vec4 sampled = texture(overlayTexture, texCoord);
                                                                   if (sampled.a == 0.0) discard;
                                                                   frag_color = sampled * vertexColor;
                                                                   frag_effect = vec4(0.0, 0.0, 0.0, sampled.a);
                                                                   coverage = sampled.a;
                                                               } else if (mode == 2) {
                                                                   frag_color = vec4(vec3(texture(overlayTexture, texCoord).r), 1.0);
                                                                   frag_effect = vec4(0.0);
                                                               }
                                                               frag_index = mode != 2 && vertexIndex >= 0.0 ? vec4(vertexIndex / 255.0, 0.0, 0.0, coverage) : vec4(0.0);
                                                           }
                                                           """;

    /**
     * Uploads RGBA pixels as a texture owned by the caller.
     *
     * @param pixels the row-major RGBA bytes
     * @param width  the width in pixels
     * @param height the height in pixels
     * @return the texture name
     */
    static int uploadRgba(byte @NotNull [] pixels, int width, int height) {
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int texture = glGenTextures();

        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        ByteBuffer upload = MemoryUtil.memAlloc(pixels.length);

        try {
            upload.put(pixels).flip();
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, upload);
        } finally {
            MemoryUtil.memFree(upload);
            glBindTexture(GL_TEXTURE_2D, previousTexture);
        }

        return texture;
    }

    private final @NotNull WorldRenderer world;

    private final @NotNull ShaderProgram program;
    private final int modeUniform;
    private final int framebufferUniform;

    private final int vertexArray;
    private final int vertexBuffer;
    private final @NotNull FloatBuffer vertices;

    private final int whiteTexture;

    private final @NotNull FogCellGrid fogGrid;
    private @NotNull ByteBuffer fogValues;
    private int fogTexture;
    private int fogWidth;
    private int fogHeight;

    private int batchMode;
    private int batchTexture;
    private boolean active;
    private boolean closed;

    /**
     * Creates an {@link OverlayPass} instance on the current OpenGL 3.3 context.
     *
     * @param world the world renderer whose frame receives the overlays
     */
    public OverlayPass(@NotNull WorldRenderer world) {
        this.world = world;

        // A scene may be created inside a world frame; the bindings the world renderer relies on are restored below.
        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int previousVertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int previousArrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);

        this.program            = GraphicsDevice.getCurrent().createShaderProgram(SHADER_PROGRAM_NAME, VERTEX_SHADER, FRAGMENT_SHADER);
        this.modeUniform        = this.program.getUniformLocation(MODE_UNIFORM);
        this.framebufferUniform = this.program.getUniformLocation(FRAMEBUFFER_SIZE_UNIFORM);
        this.program.use();
        glUniform1i(this.program.getUniformLocation(OVERLAY_TEXTURE_UNIFORM), 0);

        this.vertexArray  = glGenVertexArrays();
        this.vertexBuffer = glGenBuffers();
        this.vertices     = MemoryUtil.memAllocFloat(MAXIMUM_VERTICES * WorldRenderer.VERTEX_FLOATS);
        glBindVertexArray(this.vertexArray);
        glBindBuffer(GL_ARRAY_BUFFER, this.vertexBuffer);
        glBufferData(GL_ARRAY_BUFFER, (long) this.vertices.capacity() * Float.BYTES, GL_STREAM_DRAW);
        WorldRenderer.setVertexLayout();

        this.whiteTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, this.whiteTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, new int[]{WHITE_PIXEL});

        this.fogGrid    = new FogCellGrid();
        this.fogValues  = MemoryUtil.memAlloc(1);
        this.fogTexture = 0;
        this.fogWidth   = 0;
        this.fogHeight  = 0;

        this.batchMode    = 0;
        this.batchTexture = 0;
        this.active       = false;
        this.closed       = false;

        glBindTexture(GL_TEXTURE_2D, previousTexture);
        glBindBuffer(GL_ARRAY_BUFFER, previousArrayBuffer);
        glBindVertexArray(previousVertexArray);
        glUseProgram(previousProgram);
    }

    /**
     * Takes over the world frame from the {@link WorldRenderer}, which must have an active world frame.
     *
     * @param external {@code true} when the world renderer's pass was already handed over by the caller
     * @throws IllegalStateException if the pass is closed
     */
    public void begin(boolean external) {
        if (this.active) {
            return;
        }

        if (this.closed) {
            throw Exceptions.newIllegalStateException("The overlay pass is closed.");
        }

        if (!external) {
            this.world.beginExternalWorldPass();
        }

        this.active = true;

        glBindFramebuffer(GL_FRAMEBUFFER, this.world.getWorldFramebuffer());
        glViewport(0, 0, this.world.getFramebufferWidth(), this.world.getFramebufferHeight());
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, this.world.getFramebufferHeight() - this.world.getViewHeight(), this.world.getViewWidth(), this.world.getViewHeight());
        this.program.use();
        glUniform2f(this.framebufferUniform, this.world.getFramebufferWidth(), this.world.getFramebufferHeight());
        glBindVertexArray(this.vertexArray);
        glActiveTexture(GL_TEXTURE0);

        this.vertices.clear();

        this.batchMode    = -1;
        this.batchTexture = 0;
    }

    /**
     * Submits pending overlays and returns the world frame to the {@link WorldRenderer}.
     *
     * @param external {@code true} when the caller returns the world renderer's pass itself
     */
    public void end(boolean external) {
        if (!this.active) {
            return;
        }

        this.flush();
        this.active = false;

        glColorMaski(WorldRenderer.INDEX_TARGET, true, true, true, true);
        glColorMaski(WorldRenderer.COLOR_TARGET, true, true, true, true);
        glColorMaski(WorldRenderer.EFFECT_TARGET, true, true, true, true);

        if (!external) {
            this.world.endExternalWorldPass();
        }
    }

    /**
     * Fills a world rectangle with a palette color and records its palette index for the classic world's later remaps.
     *
     * @param left         the left world coordinate
     * @param top          the top world coordinate
     * @param width        the width in world pixels
     * @param height       the height in world pixels
     * @param color        the packed {@code 0xRRGGBB} color
     * @param paletteIndex the palette index of the color, or {@link #NO_PALETTE_INDEX}
     */
    void fillRectangle(float left, float top, float width, float height, int color, int paletteIndex) {
        this.selectBatch(MODE_SOLID, this.whiteTexture);
        this.appendWorldQuad(left, top, width, height, color, paletteIndex);
    }

    /**
     * Draws a one-color RGBA texture over a world rectangle and records its palette index where it covers.
     *
     * @param texture      the texture name
     * @param left         the left world coordinate
     * @param top          the top world coordinate
     * @param width        the width in world pixels
     * @param height       the height in world pixels
     * @param paletteIndex the palette index of the texture's color, or {@link #NO_PALETTE_INDEX}
     */
    void drawTexture(int texture, float left, float top, float width, float height, int paletteIndex) {
        this.selectBatch(MODE_TEXTURE, texture);
        this.appendWorldQuad(left, top, width, height, WHITE, paletteIndex);
    }

    /**
     * Darkens the world with filtered tile visibility, interpolated fog cells and camera-aligned sampling. Cell levels are clamped to the fog table
     * range, with a separate alpha cap for observers.
     *
     * @param tileFlags    the current tile flag DWORD for each row-major tile index
     * @param mapWidth     the map width in tiles
     * @param mapHeight    the map height in tiles
     * @param visionMask   the eight-bit mask of players whose vision is shown
     * @param visibleLevel the level of visible tiles, the game's visible level BYTE (default {@code 31})
     * @param observer     whether the local human is an observer slot (the observer table)
     */
    public void drawFog(@NotNull IntUnaryOperator tileFlags, int mapWidth, int mapHeight, int visionMask, int visibleLevel, boolean observer) {
        if (mapWidth <= 0 || mapHeight <= 0) {
            return;
        }

        this.flush();
        this.fogGrid.update(tileFlags, mapWidth, mapHeight, visionMask, visibleLevel, observer);
        this.uploadFog(this.fogGrid.getCells(), this.fogGrid.getCellColumns(), this.fogGrid.getCellRows());
    }

    /**
     * Resamples the fog cells to texels of {@code 8} pixels aligned to the view, at the camera's offset inside a cell, and draws them.
     *
     * @param cells       the darkness alpha of each fog cell
     * @param cellColumns the fog cell columns of the map
     * @param cellRows    the fog cell rows of the map
     */
    private void uploadFog(int @NotNull [] cells, int cellColumns, int cellRows) {
        float cameraX = this.world.getCameraX();
        float cameraY = this.world.getCameraY();
        float scale = this.world.getScale();
        int firstColumn = (int) Math.floor(cameraX / FOG_CELL_SIZE);
        int firstRow = (int) Math.floor(cameraY / FOG_CELL_SIZE);
        float fractionX = cameraX / FOG_CELL_SIZE - firstColumn;
        float fractionY = cameraY / FOG_CELL_SIZE - firstRow;
        // The view's texels, limited to those whose linear footprint touches the map: a zoomed-out view larger than the map
        // leaves the ground outside the map undarkened, as it holds no terrain.
        int viewColumns = Math.max(1, (int) Math.ceil(this.world.getViewWidth() / scale / FOG_CELL_SIZE));
        int viewRows = Math.max(1, (int) Math.ceil(this.world.getViewHeight() / scale / FOG_CELL_SIZE));
        int startColumn = Math.clamp(-1L - firstColumn, 0, viewColumns);
        int endColumn = Math.clamp((long) cellColumns + 1 - firstColumn, startColumn, viewColumns);
        int startRow = Math.clamp(-1L - firstRow, 0, viewRows);
        int endRow = Math.clamp((long) cellRows + 1 - firstRow, startRow, viewRows);
        int textureWidth = endColumn - startColumn;
        int textureHeight = endRow - startRow;

        if (textureWidth <= 0 || textureHeight <= 0) {
            return;
        }

        int texels = textureWidth * textureHeight;

        if (this.fogValues.capacity() < texels) {
            MemoryUtil.memFree(this.fogValues);
            this.fogValues = MemoryUtil.memAlloc(texels);
        }

        // Absolute puts check the limit, which the previous upload set to its own texel count.
        this.fogValues.clear();

        for (int row = 0; row < textureHeight; row++) {
            int cellRow = firstRow + startRow + row;
            int top = Math.clamp(cellRow, 0, cellRows - 1) * cellColumns;
            int bottom = Math.clamp(cellRow + 1, 0, cellRows - 1) * cellColumns;

            for (int column = 0; column < textureWidth; column++) {
                int cellColumn = firstColumn + startColumn + column;
                int left = Math.clamp(cellColumn, 0, cellColumns - 1);
                int right = Math.clamp(cellColumn + 1, 0, cellColumns - 1);
                float upper = cells[top + left] + (cells[top + right] - cells[top + left]) * fractionX;
                float lower = cells[bottom + left] + (cells[bottom + right] - cells[bottom + left]) * fractionX;
                int alpha = Math.round(upper + (lower - upper) * fractionY);

                this.fogValues.put(row * textureWidth + column, (byte) (FogCellGrid.MAXIMUM_FOG_ALPHA - alpha));
            }
        }

        this.fogValues.position(0).limit(texels);

        if (this.fogTexture == 0) {
            this.fogTexture = glGenTextures();
        }

        glBindTexture(GL_TEXTURE_2D, this.fogTexture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

        if (this.fogWidth != textureWidth || this.fogHeight != textureHeight) {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, textureWidth, textureHeight, 0, GL_RED, GL_UNSIGNED_BYTE, this.fogValues);
            this.fogWidth  = textureWidth;
            this.fogHeight = textureHeight;
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, textureWidth, textureHeight, GL_RED, GL_UNSIGNED_BYTE, this.fogValues);
        }

        this.selectBatch(MODE_FOG, this.fogTexture);
        this.appendWorldQuad(cameraX + startColumn * (float) FOG_CELL_SIZE,
                             cameraY + startRow * (float) FOG_CELL_SIZE,
                             textureWidth * (float) FOG_CELL_SIZE,
                             textureHeight * (float) FOG_CELL_SIZE,
                             WHITE,
                             NO_PALETTE_INDEX);
        this.flush();
    }

    private void selectBatch(int mode, int texture) {
        if (this.batchMode == mode && this.batchTexture == texture) {
            return;
        }

        this.flush();
        this.batchMode    = mode;
        this.batchTexture = texture;
    }

    private void appendWorldQuad(float left, float top, float width, float height, int color, int paletteIndex) {
        if (this.vertices.remaining() < WorldRenderer.QUAD_FLOATS) {
            this.flush();
        }

        float scale = this.world.getScale();
        float screenLeft = (left - this.world.getCameraX()) * scale;
        float screenTop = (top - this.world.getCameraY()) * scale;
        float screenRight = screenLeft + width * scale;
        float screenBottom = screenTop + height * scale;

        float red = (color >>> RGB_RED_SHIFT & BYTE_MASK) / CHANNEL_MAXIMUM;
        float green = (color >>> RGB_GREEN_SHIFT & BYTE_MASK) / CHANNEL_MAXIMUM;
        float blue = (color & BYTE_MASK) / CHANNEL_MAXIMUM;

        this.putVertex(screenLeft, screenTop, 0, 0, red, green, blue, paletteIndex);
        this.putVertex(screenRight, screenTop, 1, 0, red, green, blue, paletteIndex);
        this.putVertex(screenRight, screenBottom, 1, 1, red, green, blue, paletteIndex);
        this.putVertex(screenLeft, screenTop, 0, 0, red, green, blue, paletteIndex);
        this.putVertex(screenRight, screenBottom, 1, 1, red, green, blue, paletteIndex);
        this.putVertex(screenLeft, screenBottom, 0, 1, red, green, blue, paletteIndex);
    }

    private void putVertex(float horizontal, float vertical,
                           float textureHorizontal, float textureVertical,
                           float red, float green, float blue,
                           int paletteIndex) {
        this.vertices.put(horizontal).put(vertical).put(textureHorizontal).put(textureVertical).put(red).put(green).put(blue).put(1)
                     .put(paletteIndex);
    }

    private void flush() {
        if (this.vertices.position() == 0) {
            return;
        }

        boolean fog = this.batchMode == MODE_FOG;

        this.vertices.flip();
        glColorMaski(WorldRenderer.EFFECT_TARGET, !fog, !fog, !fog, !fog);
        // Palette-colored overlays record their index (alpha 0 elsewhere keeps it); the fog's multiplicative blend must not.
        glColorMaski(WorldRenderer.INDEX_TARGET, !fog, !fog, !fog, !fog);
        glEnable(GL_BLEND);

        if (fog) {
            glBlendFunc(GL_ZERO, GL_SRC_COLOR);
        } else {
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }

        glUniform1i(this.modeUniform, this.batchMode);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, this.batchTexture);
        glBindBuffer(GL_ARRAY_BUFFER, this.vertexBuffer);
        glBufferSubData(GL_ARRAY_BUFFER, 0, this.vertices);
        glDrawArrays(GL_TRIANGLES, 0, this.vertices.remaining() / WorldRenderer.VERTEX_FLOATS);
        this.vertices.clear();
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
     * @implSpec Releases the GPU program, buffers and textures.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        IgnoreException.execute(
                () -> glDeleteTextures(this.fogTexture),
                () -> MemoryUtil.memFree(this.fogValues),
                () -> glDeleteTextures(this.whiteTexture),
                () -> MemoryUtil.memFree(this.vertices),
                () -> glDeleteBuffers(this.vertexBuffer),
                () -> glDeleteVertexArrays(this.vertexArray),
                () -> this.program.close()
        );
    }
}
