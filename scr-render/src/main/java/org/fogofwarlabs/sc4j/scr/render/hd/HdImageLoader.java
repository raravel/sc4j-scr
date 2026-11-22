package org.fogofwarlabs.sc4j.scr.render.hd;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.CompressedTexture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TextureSheet;
import org.fogofwarlabs.sc4j.scr.data.format.image.DdsFile;
import org.fogofwarlabs.sc4j.scr.data.resource.importer.AnimTextureUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;

import static org.lwjgl.opengl.EXTTextureCompressionS3TC.*;
import static org.lwjgl.opengl.GL33C.GL_RGBA8;

/**
 * Reads and prepares HD image textures on background threads so that the render thread only uploads them.
 * <p>
 * Preparing an image reads its {@code .anim} from CASC, parses it and copies the level-{@code 0} data of the diffuse, team-color and (for the lit
 * path) emissive, normal, specular and AO/depth layers into native buffers in their upload format (compressed blocks when the driver supports S3TC,
 * decoded RGBA otherwise). The upload stays on the render thread. Results are keyed by image and lighting path.
 */
final class HdImageLoader implements AutoCloseable {
    /**
     * The emissive, normal, specular and AO/depth layer names of the lit path.
     */
    static final @NotNull String @NotNull [] LIGHTING_LAYERS = {"emissive", "normal", "specular", "ao_depth"};
    /**
     * The positions of the normal, specular and AO/depth layers in {@link #LIGHTING_LAYERS}.
     */
    static final int LIGHTING_NORMAL = 1;
    static final int LIGHTING_SPECULAR = 2;
    static final int LIGHTING_AO_DEPTH = 3;
    /**
     * The sprite layer: the diffuse layer, or in the lit path the bright layer where the anim has one.
     */
    static final int SPRITE_LAYER = 0;
    /**
     * The team color layer.
     */
    static final int TEAM_COLOR_LAYER = 1;
    /**
     * The first lit-path layer, followed by the other {@link #LIGHTING_LAYERS}.
     */
    static final int FIRST_LIGHTING_LAYER = 2;
    /**
     * The lit-path layer that holds the diffuse layer for frames without lighting data when the sprite layer is the bright layer; absent when every
     * frame has lighting data.
     */
    static final int UNLIT_LAYER = FIRST_LIGHTING_LAYER + LIGHTING_LAYERS.length;
    /**
     * The anim frame flag of frames without lighting data.
     */
    static final int FRAME_UNLIT = 8;

    /**
     * The layers of the forward path: the sprite and team color layers.
     */
    private static final int FORWARD_LAYER_COUNT = 2;
    /**
     * The anim layer names of the diffuse, bright and team color layers.
     */
    private static final @NotNull String DIFFUSE_LAYER_NAME = "diffuse";
    private static final @NotNull String BRIGHT_LAYER_NAME = "bright";
    private static final @NotNull String TEAM_COLOR_LAYER_NAME = "teamcolor";
    /**
     * The logical processors per worker thread and the bounds of the worker thread count.
     */
    private static final int PROCESSORS_PER_WORKER = 2;
    private static final int MINIMUM_WORKER_COUNT = 2;
    private static final int MAXIMUM_WORKER_COUNT = 12;
    /**
     * The number of worker threads that read and prepare images: half the logical processors, from two up to twelve.
     */
    private static final int WORKER_COUNT = Math.clamp(Runtime.getRuntime().availableProcessors() / PROCESSORS_PER_WORKER,
                                                       MINIMUM_WORKER_COUNT,
                                                       MAXIMUM_WORKER_COUNT);
    /**
     * The native upload buffers a load sequence keeps prepared ahead of the uploads before it waits for them.
     */
    private static final long SEQUENCE_PREPARED_BYTES = 512L << HdImageSet.MEBIBYTE_SHIFT;
    /**
     * The override sheets whose preparations are kept until they are taken; past it, the one requested first is dropped.
     */
    private static final int MAXIMUM_PENDING_SHEETS = 16;

    /**
     * One prepared image: the texture sheet and the native upload buffers of its layers.
     */
    static final class Prepared {
        private final @Nullable TextureSheet sheet;
        private final @Nullable ByteBuffer @NotNull [] data;
        private final int @NotNull [] formats;
        private final int @NotNull [] widths;
        private final int @NotNull [] heights;

        private final float textureWidth;
        private final float textureHeight;
        private final long byteSize;
        private final boolean bright;

        /**
         * Whether the buffers count toward the load sequence's prepared bytes until they are taken.
         */
        private boolean sequenced;

        private Prepared(@Nullable TextureSheet sheet,
                         @Nullable ByteBuffer @NotNull [] data, int @NotNull [] formats, int @NotNull [] widths, int @NotNull [] heights,
                         float textureWidth, float textureHeight,
                         long byteSize,
                         boolean bright) {
            this.sheet   = sheet;
            this.data    = data;
            this.formats = formats;
            this.widths  = widths;
            this.heights = heights;

            this.textureWidth  = textureWidth;
            this.textureHeight = textureHeight;
            this.byteSize      = byteSize;
            this.bright        = bright;

            this.sequenced = false;
        }

        /**
         * @return the texture sheet, or {@code null} when the image has no HD anim
         */
        @Nullable TextureSheet getSheet() {
            return this.sheet;
        }

        /**
         * @return {@code true} if layer {@code 0} is the anim's bright layer (the lit path of an anim that has one) instead of diffuse, {@code false}
         * otherwise
         */
        boolean isBright() {
            return this.bright;
        }

        /**
         * @return the layer count: diffuse, team color, then the lighting layers and {@link #UNLIT_LAYER}
         */
        int getLayerCount() {
            return this.data.length;
        }

        /**
         * @param layer the layer index
         * @return the native level-{@code 0} data, or {@code null} when the layer is absent
         */
        @Nullable ByteBuffer getData(int layer) {
            return this.data[layer];
        }

        /**
         * @param layer the layer index
         * @return the OpenGL internal format
         */
        int getFormat(int layer) {
            return this.formats[layer];
        }

        /**
         * @param layer the layer index
         * @return the texture width in texels
         */
        int getWidth(int layer) {
            return this.widths[layer];
        }

        /**
         * @param layer the layer index
         * @return the texture height in texels
         */
        int getHeight(int layer) {
            return this.heights[layer];
        }

        /**
         * @return the diffuse texture width in scale-{@code 4} frame units
         */
        float getTextureWidth() {
            return this.textureWidth;
        }

        /**
         * @return the diffuse texture height in scale-{@code 4} frame units
         */
        float getTextureHeight() {
            return this.textureHeight;
        }

        /**
         * @return the texture memory the uploaded layers use
         */
        long getByteSize() {
            return this.byteSize;
        }

        /**
         * Frees the native buffers.
         */
        void free() {
            for (int layer = 0; layer < this.data.length; layer++) {
                if (this.data[layer] != null) {
                    MemoryUtil.memFree(this.data[layer]);
                    this.data[layer] = null;
                }
            }
        }
    }

    /**
     * Returns the OpenGL internal format a texture is uploaded in.
     *
     * @param texture            the texture
     * @param compressedTextures whether S3TC blocks can be uploaded compressed
     * @return the compressed format of the texture's blocks, or {@code GL_RGBA8} for decoded pixels
     */
    static int getInternalFormat(@NotNull DdsFile texture, boolean compressedTextures) {
        if (!compressedTextures) {
            return GL_RGBA8;
        }

        return switch (texture.getFormat()) {
            case DXT1 -> GL_COMPRESSED_RGBA_S3TC_DXT1_EXT;
            case DXT3 -> GL_COMPRESSED_RGBA_S3TC_DXT3_EXT;
            case DXT5 -> GL_COMPRESSED_RGBA_S3TC_DXT5_EXT;
            default -> GL_RGBA8;
        };
    }

    /**
     * Decodes level {@code 0} of a layer to RGBA bytes, for drivers that cannot upload S3TC blocks.
     *
     * @param texture the layer
     * @return the row-major RGBA bytes
     * @throws IOException if the layer's compression cannot be decoded
     */
    private static byte @NotNull [] decodeRgba(@NotNull CompressedTexture texture) throws IOException {
        DdsFile.Format format = AnimTextureUtils.toDdsFormat(texture.getFormat());

        if (format == null) {
            throw Exceptions.newIOException("The {0} layer cannot be decoded.", texture.getFormat());
        }

        ByteBuffer blocks = ByteBuffer.allocate(texture.getLevelSize(0));

        texture.copyLevel(0, blocks);

        return DdsFile.decodeBlocks(format, texture.getWidth(), texture.getHeight(), blocks.array(), 0);
    }

    private static int getRequestKey(int image, boolean lighting) {
        return image << 1 | (lighting ? 1 : 0);
    }

    /**
     * Returns the OpenGL internal format a texture sheet layer is uploaded in.
     *
     * @param texture            the layer
     * @param compressedTextures whether S3TC blocks can be uploaded compressed
     * @return the compressed format of the layer's blocks, or {@code GL_RGBA8} for decoded pixels
     */
    static int getInternalFormat(@NotNull CompressedTexture texture, boolean compressedTextures) {
        if (!compressedTextures) {
            return GL_RGBA8;
        }

        return switch (texture.getFormat()) {
            case BC1 -> GL_COMPRESSED_RGBA_S3TC_DXT1_EXT;
            case BC2 -> GL_COMPRESSED_RGBA_S3TC_DXT3_EXT;
            case BC3 -> GL_COMPRESSED_RGBA_S3TC_DXT5_EXT;
            case BC7 -> GL_RGBA8;
        };
    }

    private static boolean hasUnlitFrame(@NotNull TextureSheet sheet) {
        for (int frame = 0; frame < sheet.getFrameCount(); frame++) {
            if ((sheet.getFrame(frame).getFlags() & FRAME_UNLIT) != 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Cancels a preparation, or frees its buffers if it has finished.
     *
     * @param future the preparation
     */
    private static void discard(@NotNull Future<Prepared> future) {
        if (!future.cancel(false) && future.isDone()) {
            try {
                future.get().free();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | CancellationException ignored) {
                // A failed preparation holds no buffers.
            }
        }
    }

    private final @NotNull HdAnimations animations;
    private final boolean compressedTextures;

    private final @NotNull ExecutorService executor;
    private final @NotNull Map<Integer, Future<Prepared>> pending;
    private final @NotNull Map<Integer, HdFrameGeometry> geometries;
    /**
     * The preparations of override sheets by sheet instance, in the order they were requested; guarded by itself.
     */
    private final @NotNull Map<TextureSheet, Future<Prepared>> pendingSheets;
    private volatile int generation;

    /**
     * The images a load prepares in order ({@link #startSequence}), or {@code null}; guarded by {@code this}.
     */
    private int @Nullable [] sequence;
    private boolean sequenceLighting;
    private int sequenceNext;
    private int sequenceRunning;
    private long sequencePreparedBytes;

    /**
     * Creates an {@link HdImageLoader} instance with {@link #WORKER_COUNT} daemon worker threads.
     *
     * @param animations         the HD anims read
     * @param compressedTextures whether layers are kept in their S3TC blocks
     */
    HdImageLoader(@NotNull HdAnimations animations, boolean compressedTextures) {
        this.animations         = animations;
        this.compressedTextures = compressedTextures;

        this.executor      = Executors.newFixedThreadPool(WORKER_COUNT, runnable -> {
            Thread thread = new Thread(runnable, "HD image loader");

            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);

            return thread;
        });
        this.pending       = new ConcurrentHashMap<>();
        this.geometries    = new ConcurrentHashMap<>();
        this.pendingSheets = new LinkedHashMap<>();
        this.generation    = 0;

        this.sequence              = null;
        this.sequenceLighting      = false;
        this.sequenceNext          = 0;
        this.sequenceRunning       = 0;
        this.sequencePreparedBytes = 0L;
    }

    /**
     * @param image the image identifier
     * @return the frame placement of an image that has been read (prepared), or {@code null} before
     */
    @Nullable HdFrameGeometry getGeometry(int image) {
        return this.geometries.get(image);
    }


    /**
     * Queues an image for preparation unless it is already queued or prepared.
     *
     * @param image    the image identifier
     * @param lighting whether the lighting layers are prepared
     */
    void request(int image, boolean lighting) {
        this.request(image, lighting, false);
    }

    private @NotNull Future<Prepared> request(int image, boolean lighting, boolean sequenced) {
        int requested = this.generation;

        return this.pending.computeIfAbsent(getRequestKey(image, lighting), unused -> this.executor.submit(() -> {
            Prepared prepared = null;

            try {
                prepared = this.prepare(image, lighting);

                if (requested != this.generation) {
                    // Dropped by clear() while it was being prepared: nobody will take it.
                    prepared.free();
                }

                return prepared;
            } finally {
                if (sequenced) {
                    this.finishSequenced(requested, prepared);
                }
            }
        }));
    }

    /**
     * Starts preparing images in order on the worker threads, independent of the render thread: the workers keep up to
     * {@link #SEQUENCE_PREPARED_BYTES} of buffers prepared ahead of the images taken, so a load keeps reading while the render thread is busy
     * elsewhere (the map of a game start). Replaces a previous sequence.
     *
     * @param images   the images in the order they will be taken
     * @param lighting whether the lighting layers are prepared
     */
    synchronized void startSequence(int @NotNull [] images, boolean lighting) {
        this.sequence         = images;
        this.sequenceLighting = lighting;
        this.sequenceNext     = 0;

        this.fillSequence();
    }

    private synchronized void finishSequenced(int requested, @Nullable Prepared prepared) {
        if (requested != this.generation) {
            return;
        }

        this.sequenceRunning--;

        if (prepared != null) {
            prepared.sequenced = true;
            this.sequencePreparedBytes += prepared.byteSize;
        }

        this.fillSequence();
    }

    private synchronized void takeSequenced(@NotNull Prepared prepared) {
        if (!prepared.sequenced) {
            return;
        }

        prepared.sequenced = false;
        this.sequencePreparedBytes -= prepared.byteSize;

        this.fillSequence();
    }

    private void fillSequence() {
        int[] images = this.sequence;

        if (images == null) {
            return;
        }

        while (this.sequenceNext < images.length && this.sequenceRunning < WORKER_COUNT && this.sequencePreparedBytes < SEQUENCE_PREPARED_BYTES) {
            int image = images[this.sequenceNext++];

            if (this.pending.containsKey(getRequestKey(image, this.sequenceLighting))) {
                continue;
            }

            this.sequenceRunning++;
            this.request(image, this.sequenceLighting, true);
        }
    }

    /**
     * Returns a prepared image if its preparation has finished, without waiting.
     *
     * @param image    the image identifier
     * @param lighting whether the lighting layers are needed
     * @return the prepared image, or {@code null} while it is still being prepared (it is queued if it was not)
     * @throws IOException if the preparation failed
     */
    @Nullable Prepared poll(int image, boolean lighting) throws IOException {
        Future<Prepared> future = this.pending.get(getRequestKey(image, lighting));

        if (future == null) {
            this.request(image, lighting);

            return null;
        }

        if (!future.isDone()) {
            return null;
        }

        return this.take(image, lighting, future);
    }

    /**
     * Returns a prepared image, waiting for its preparation.
     *
     * @param image    the image identifier
     * @param lighting whether the lighting layers are needed
     * @return the prepared image
     * @throws IOException if the preparation failed
     */
    @NotNull Prepared await(int image, boolean lighting) throws IOException {
        return this.take(image, lighting, this.request(image, lighting, false));
    }

    /**
     * Returns a prepared override sheet if its preparation has finished, without waiting; the first call queues it. Past
     * {@value #MAXIMUM_PENDING_SHEETS} sheets queued or prepared and not taken, the one requested first is dropped and its buffers freed.
     *
     * @param sheet    the texture sheet of an override anim
     * @param lighting whether the lighting layers are prepared
     * @return the prepared sheet, or {@code null} while it is still being prepared
     * @throws IOException if the preparation failed
     */
    @Nullable Prepared pollSheet(@NotNull TextureSheet sheet, boolean lighting) throws IOException {
        Future<Prepared> future;

        synchronized (this.pendingSheets) {
            future = this.pendingSheets.get(sheet);

            if (future == null) {
                int requested = this.generation;

                this.pendingSheets.put(sheet, this.executor.submit(() -> {
                    Prepared prepared = this.prepare(sheet, lighting);

                    if (requested != this.generation) {
                        prepared.free();
                    }

                    return prepared;
                }));

                if (this.pendingSheets.size() > MAXIMUM_PENDING_SHEETS) {
                    Iterator<Future<Prepared>> eldest = this.pendingSheets.values().iterator();

                    discard(eldest.next());
                    eldest.remove();
                }

                return null;
            }

            if (!future.isDone()) {
                return null;
            }

            this.pendingSheets.remove(sheet);
        }

        return this.take(-1, lighting, future);
    }

    private @NotNull Prepared take(int image, boolean lighting, @NotNull Future<Prepared> future) throws IOException {
        if (image >= 0) {
            this.pending.remove(getRequestKey(image, lighting));
        }

        try {
            Prepared prepared = future.get();

            this.takeSequenced(prepared);

            return prepared;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw Exceptions.newIOException(exception, "Interrupted while preparing HD image {0}", image);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();

            if (cause instanceof IOException ioException) {
                throw ioException;
            }

            throw Exceptions.newIOException(cause, "Cannot prepare HD image {0}: {1}", image, cause);
        }
    }

    private @NotNull Prepared prepare(int image, boolean lighting) throws IOException {
        TextureSheet sheet = this.animations.loadTextureSheet(image);

        if (sheet == null) {
            return new Prepared(null, new ByteBuffer[0], new int[0], new int[0], new int[0], 0, 0, 0, false);
        }

        this.geometries.putIfAbsent(image, HdFrameGeometry.of(sheet));

        return this.prepare(sheet, lighting);
    }

    /**
     * Prepares the layers of a texture sheet: the sprite layer (diffuse, or bright in the lit path where the sheet has one), team color and, for
     * the lit path, the lighting layers and the diffuse layer of frames without lighting data, each level {@code 0} in its upload format.
     *
     * @param sheet    the texture sheet
     * @param lighting whether the lighting layers are prepared
     * @return the prepared sheet
     * @throws IOException if the sheet has no diffuse layer or a layer cannot be decoded
     */
    private @NotNull Prepared prepare(@NotNull TextureSheet sheet, boolean lighting) throws IOException {
        CompressedTexture diffuse = sheet.findLayer(DIFFUSE_LAYER_NAME);

        if (diffuse == null) {
            throw Exceptions.newIOException("The HD texture sheet has no diffuse layer.");
        }

        CompressedTexture[] layers = new CompressedTexture[lighting ? UNLIT_LAYER + 1 : FORWARD_LAYER_COUNT];

        layers[SPRITE_LAYER] = diffuse;

        // Use the bright layer for lit sprites when it exists.
        CompressedTexture bright = lighting ? sheet.findLayer(BRIGHT_LAYER_NAME) : null;

        if (bright != null) {
            layers[SPRITE_LAYER] = bright;
        }

        layers[TEAM_COLOR_LAYER] = sheet.findLayer(TEAM_COLOR_LAYER_NAME);

        for (int layer = FIRST_LIGHTING_LAYER; layer < FIRST_LIGHTING_LAYER + (lighting ? LIGHTING_LAYERS.length : 0); layer++) {
            layers[layer] = sheet.findLayer(LIGHTING_LAYERS[layer - FIRST_LIGHTING_LAYER]);
        }

        // The lit pass draws a frame without lighting data from the diffuse layer.
        if (bright != null && (layers[FIRST_LIGHTING_LAYER + LIGHTING_NORMAL] == null || layers[FIRST_LIGHTING_LAYER + LIGHTING_SPECULAR] == null ||
                               layers[FIRST_LIGHTING_LAYER + LIGHTING_AO_DEPTH] == null || hasUnlitFrame(sheet))) {
            layers[UNLIT_LAYER] = diffuse;
        }

        ByteBuffer[] data = new ByteBuffer[layers.length];
        int[] formats = new int[layers.length];
        int[] widths = new int[layers.length];
        int[] heights = new int[layers.length];
        long byteSize = 0;

        try {
            for (int layer = 0; layer < layers.length; layer++) {
                CompressedTexture texture = layers[layer];

                if (texture == null) {
                    continue;
                }

                formats[layer] = getInternalFormat(texture, this.compressedTextures);
                widths[layer]  = texture.getWidth();
                heights[layer] = texture.getHeight();

                if (formats[layer] != GL_RGBA8) {
                    data[layer] = MemoryUtil.memAlloc(texture.getLevelSize(0));
                    texture.copyLevel(0, data[layer]);
                } else {
                    byte[] pixels = decodeRgba(texture);

                    data[layer] = MemoryUtil.memAlloc(pixels.length);
                    data[layer].put(pixels);
                }

                data[layer].flip();
                byteSize += data[layer].remaining();
            }
        } catch (RuntimeException exception) {
            for (ByteBuffer buffer : data) {
                if (buffer != null) {
                    MemoryUtil.memFree(buffer);
                }
            }

            throw exception;
        }

        // Frame rectangles are in scale-4 pixels; HD2 textures hold them at half size.
        float unitsPerTexel = sheet.getUnitsPerTexel();

        return new Prepared(sheet,
                            data, formats, widths, heights,
                            diffuse.getWidth() * unitsPerTexel, diffuse.getHeight() * unitsPerTexel,
                            byteSize,
                            bright != null);
    }

    /**
     * Drops all queued and finished preparations and frees their buffers.
     */
    void clear() {
        synchronized (this) {
            this.generation++;
            this.sequence              = null;
            this.sequenceRunning       = 0;
            this.sequencePreparedBytes = 0;
        }

        for (Future<Prepared> future : this.pending.values()) {
            discard(future);
        }

        this.pending.clear();

        synchronized (this.pendingSheets) {
            for (Future<Prepared> future : this.pendingSheets.values()) {
                discard(future);
            }

            this.pendingSheets.clear();
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
     * @implSpec Stops the worker threads and frees all finished preparations.
     */
    @Override
    public void close() {
        this.executor.shutdownNow();
        this.clear();
    }
}
