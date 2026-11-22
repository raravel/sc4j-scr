package org.fogofwarlabs.sc4j.scr.render.hd;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.host.HostWindow;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TextureSheet;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedGraphics;
import org.fogofwarlabs.sc4j.scr.data.format.dat.ImagesDat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Shares uploaded HD images across scenes, with resident loading or on-demand least-recently-used eviction. Preparation runs on loader threads;
 * uploads run on a shared context or in render-thread time slices. A resident load covers the installed images and the images the mods add after
 * them ({@link ScrAddedGraphics}, as registered in the anims' resource provider when the load starts), so no game uploads one when it first draws it.
 */
public final class HdImageSet implements AutoCloseable {
    /**
     * The shift that converts mebibytes to bytes.
     */
    public static final int MEBIBYTE_SHIFT = 20;
    /**
     * The number of image ids the set holds textures for: the WORD id space of the installed images ({@link ImagesDat#IMAGE_COUNT}) and of the
     * images a mod adds after them ({@link ScrAddedGraphics}).
     */
    static final int IMAGE_ID_COUNT = ScrAddedGraphics.LAST_ID + 1;

    private static final @NotNull Logger LOGGER = Logger.of(HdImageSet.class);

    /**
     * The texture-memory budget of streamed images at the HD and HD2 qualities.
     */
    private static final long HD_TEXTURE_BUDGET = 1024L << MEBIBYTE_SHIFT;
    private static final long HD2_TEXTURE_BUDGET = 384L << MEBIBYTE_SHIFT;
    /**
     * The texture-memory budget of override sheets ({@link #acquireSheet}) at the HD and HD2 qualities.
     */
    private static final long HD_SHEET_BUDGET = 256L << MEBIBYTE_SHIFT;
    private static final long HD2_SHEET_BUDGET = 96L << MEBIBYTE_SHIFT;
    /**
     * The override sheets remembered as unpreparable; past it, the one that failed first is forgotten.
     */
    private static final int MAXIMUM_FAILED_SHEETS = 64;
    /**
     * The wait between two polls of the loader threads while the render thread continues a load.
     */
    private static final long LOAD_POLL_NANOSECONDS = 200_000;

    private static void joinUploadThread(@NotNull Thread thread) {
        boolean interrupted = false;

        while (true) {
            try {
                thread.join();

                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static int uploadTexture(HdImageLoader.@NotNull Prepared prepared, int layer) {
        ByteBuffer data = prepared.getData(layer);

        if (data == null) {
            return 0;
        }

        int format = prepared.getFormat(layer);
        int name = glGenTextures();

        glBindTexture(GL_TEXTURE_2D, name);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);

        if (format != GL_RGBA8) {
            glCompressedTexImage2D(GL_TEXTURE_2D, 0, format, prepared.getWidth(layer), prepared.getHeight(layer), 0, data);
        } else {
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, prepared.getWidth(layer), prepared.getHeight(layer), 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
        }

        return name;
    }

    private final @NotNull HdAnimations animations;
    private final long textureBudget;
    private final long sheetBudget;
    /**
     * The hidden window whose context shares objects with the render context, for the upload thread, or {@code null}.
     */
    private final @Nullable HostWindow uploadWindow;

    private final @Nullable HdImageTextures @NotNull [] images;
    /**
     * The {@code diffuse}-layer textures of {@link #acquireDiffuse} while the set uploads the lit layers.
     */
    private final @Nullable HdImageTextures @NotNull [] diffuseImages;
    private final boolean @NotNull [] missingImages;
    private final @NotNull HdImageLoader loader;
    /**
     * The textures of the override sheets, the one used least recently first.
     */
    private final @NotNull SequencedMap<TextureSheet, HdImageTextures> sheetImages;
    /**
     * The override sheets whose preparation failed, in the order they failed.
     */
    private final @NotNull SequencedSet<TextureSheet> failedSheets;

    private boolean lighting;
    /**
     * The thread uploading a load in the shared context, or {@code null}; it owns the images while it runs.
     */
    private @Nullable Thread uploadThread;
    private volatile boolean uploadCanceled;

    private long textureBytes;
    private long sheetTextureBytes;
    private long uploadNanoseconds;
    /**
     * The time the running load spent uploading and waiting for prepared images, for its log line.
     */
    private long loadUploadNanoseconds;
    private long loadWaitNanoseconds;

    /**
     * Whether the images are kept resident (a load was started): no eviction.
     */
    private boolean resident;
    private boolean loading;
    /**
     * The images a load covers, from id {@code 0}: the installed images and the added images registered when the load started.
     */
    private int loadImageCount;
    private volatile int loadImage;
    private boolean closed;

    /**
     * Creates an {@link HdImageSet} instance without images on the current OpenGL 3.3 context.
     *
     * @param animations the anims of the quality and skin
     * @param lighting   whether the lit path's emissive, normal, specular and AO/depth layers are uploaded too
     */
    public HdImageSet(@NotNull HdAnimations animations, boolean lighting) {
        this(animations, lighting, null);
    }

    /**
     * Creates an {@link HdImageSet} instance without images on the current OpenGL 3.3 context whose loads upload on a thread of their own.
     *
     * @param animations   the anims of the quality and skin
     * @param lighting     whether the lit path's emissive, normal, specular and AO/depth layers are uploaded too
     * @param uploadWindow a hidden window whose context shares objects with the current one and is current on no thread, used by the upload
     *                     thread of a load, or {@code null} to upload in {@link #continueLoad(long)}
     */
    public HdImageSet(@NotNull HdAnimations animations, boolean lighting, @Nullable HostWindow uploadWindow) {
        boolean compressedTextures = GL.getCapabilities().GL_EXT_texture_compression_s3tc;

        this.animations    = animations;
        this.textureBudget = animations.getQuality() == HdRenderAssets.Quality.HD ? HD_TEXTURE_BUDGET : HD2_TEXTURE_BUDGET;
        this.sheetBudget   = animations.getQuality() == HdRenderAssets.Quality.HD ? HD_SHEET_BUDGET : HD2_SHEET_BUDGET;
        this.uploadWindow  = uploadWindow;

        this.images        = new HdImageTextures[IMAGE_ID_COUNT];
        this.diffuseImages = new HdImageTextures[IMAGE_ID_COUNT];
        this.missingImages = new boolean[IMAGE_ID_COUNT];
        this.loader        = new HdImageLoader(animations, compressedTextures);
        this.sheetImages   = new LinkedHashMap<>();
        this.failedSheets  = new LinkedHashSet<>();

        this.lighting       = lighting;
        this.uploadThread   = null;
        this.uploadCanceled = false;

        this.textureBytes          = 0L;
        this.sheetTextureBytes     = 0L;
        this.uploadNanoseconds     = 0L;
        this.loadUploadNanoseconds = 0L;
        this.loadWaitNanoseconds   = 0L;

        this.resident       = false;
        this.loading        = false;
        this.loadImageCount = ImagesDat.IMAGE_COUNT;
        this.loadImage      = 0;
        this.closed         = false;
    }

    /**
     * @return the anims of the set
     */
    public @NotNull HdAnimations getAnimations() {
        return this.animations;
    }

    /**
     * @return the asset quality
     */
    public HdRenderAssets.@NotNull Quality getQuality() {
        return this.animations.getQuality();
    }

    /**
     * @return {@code true} if the lit path's layers are uploaded, {@code false} otherwise
     */
    public boolean isLighting() {
        return this.lighting;
    }

    /**
     * @return {@code true} if a load started by {@link #startLoad()} is still incomplete, {@code false} otherwise
     */
    public boolean isLoading() {
        return this.loading;
    }

    /**
     * @return {@code true} if every image has been loaded and stays resident, {@code false} otherwise
     */
    public boolean isLoaded() {
        return this.resident && !this.loading && this.loadImage >= this.loadImageCount;
    }

    /**
     * @return the number of images the load covers: the installed images and the added images registered when it started
     */
    public int getLoadImageCount() {
        return this.loadImageCount;
    }

    /**
     * @return the load progress from {@code 0} to {@code 1}
     */
    public float getLoadProgress() {
        return (float) this.loadImage / this.loadImageCount;
    }

    /**
     * @return the time the load spent uploading textures, in nanoseconds
     */
    public long getLoadUploadNanoseconds() {
        return this.loadUploadNanoseconds;
    }

    /**
     * @return the time the load spent waiting for the loader threads, in nanoseconds
     */
    public long getLoadWaitNanoseconds() {
        return this.loadWaitNanoseconds;
    }

    /**
     * @return the number of images the load has passed
     */
    public int getLoadedImageCount() {
        return this.loadImage;
    }

    /**
     * @return the anim texture bytes currently uploaded, override sheets included
     */
    public long getTextureBytes() {
        return this.textureBytes + this.sheetTextureBytes;
    }

    /**
     * @param image the image identifier
     * @return the frame placement of an image that has been read, or {@code null} before
     */
    @Nullable HdFrameGeometry getGeometry(int image) {
        return this.loader.getGeometry(this.animations.getSharedImage(image));
    }

    /**
     * Sets whether the lit path's layers are uploaded. A change releases every image; a loaded (resident) set starts loading again, so a client runs
     * {@link #continueLoad(long)} (a loading screen) before drawing.
     *
     * @param lighting {@code true} for the lit path's layers
     */
    public void setLighting(boolean lighting) {
        if (lighting == this.lighting) {
            return;
        }

        this.releaseImages();
        this.lighting = lighting;

        if (this.resident) {
            this.loading               = true;
            this.loadImage             = 0;
            this.loadUploadNanoseconds = 0;
            this.loadWaitNanoseconds   = 0;
            this.startLoadSequence();
            this.startUploadThread();
        }
    }


    /**
     * Starts loading every image: the installed images and the images the mods added to the anims' resource provider so far
     * ({@link ScrAddedGraphics}); afterwards the images stay resident. A complete set does not load again. The loader threads start reading at once,
     * so a caller that is busy before the first {@link #continueLoad(long)} (a game start loading its map) overlaps that work with the reads.
     */
    public void startLoad() {
        this.resident = true;

        if (this.uploadThread != null) {
            return;
        }

        this.loadImageCount = ImagesDat.IMAGE_COUNT + this.animations.getResources().getAddedGraphics().getImages().size();

        if (this.loadImage < this.loadImageCount) {
            this.loading = true;
            this.startLoadSequence();
            this.startUploadThread();
        }
    }

    /**
     * Hands the images the load still uploads, in upload order, to the loader threads.
     */
    private void startLoadSequence() {
        int[] sequence = new int[this.loadImageCount - this.loadImage];
        int count = 0;

        for (int image = this.loadImage; image < this.loadImageCount; image++) {
            if (this.animations.getSharedImage(image) == image && this.images[image] == null && !this.missingImages[image]) {
                sequence[count++] = image;
            }
        }

        this.loader.startSequence(Arrays.copyOf(sequence, count), this.lighting);
    }

    private void startUploadThread() {
        if (this.uploadWindow == null) {
            return;
        }

        Thread thread = new Thread(this::runUploads, "HD image upload");

        thread.setDaemon(true);

        this.uploadCanceled = false;
        this.uploadThread   = thread;

        thread.start();
    }

    /**
     * Runs the upload thread: uploads the images of the load in order in the shared context, then waits for the driver to complete them before the
     * render context uses them.
     */
    private void runUploads() {
        HostWindow window = this.uploadWindow;

        if (window == null) {
            return;
        }

        window.makeContextCurrent();
        GL.createCapabilities();

        try {
            while (this.loadImage < this.loadImageCount && !this.uploadCanceled) {
                int image = this.loadImage;

                if (this.animations.getSharedImage(image) == image && this.images[image] == null && !this.missingImages[image]) {
                    try {
                        long waitStarted = System.nanoTime();
                        HdImageLoader.Prepared prepared = this.loader.await(image, this.lighting);
                        long uploadStarted = System.nanoTime();

                        this.loadWaitNanoseconds += uploadStarted - waitStarted;

                        HdImageTextures textures = this.createTextures(image, prepared);

                        this.images[image] = textures;

                        this.loadUploadNanoseconds += System.nanoTime() - uploadStarted;
                    } catch (IOException exception) {
                        this.missingImages[image] = true;
                        LOGGER.warning("HD image {0} is unavailable: {1}", image, exception.getMessage());
                    }
                }

                this.loadImage++;
            }
        } finally {
            glFinish();
            GL.setCapabilities(null);
            window.releaseContext();
        }
    }

    /**
     * Stops the upload thread of a running load and waits for it; its uploaded images stay.
     */
    private void stopUploadThread() {
        Thread thread = this.uploadThread;

        if (thread == null) {
            return;
        }

        this.uploadCanceled = true;
        joinUploadThread(thread);
        this.uploadThread = null;
    }

    /**
     * Waits for the upload thread of a running load, which owns the images until it ends; {@link #continueLoad(long)} completes the load afterwards.
     */
    private void awaitUploadThread() {
        Thread thread = this.uploadThread;

        if (thread != null) {
            joinUploadThread(thread);
        }
    }

    /**
     * Advances the load started by {@link #startLoad()}. Call it outside a scene with the OpenGL context current. With an upload thread it only
     * reports the thread's progress and completes the load when the thread has finished.
     *
     * @param budgetNanoseconds the upload time after which the call returns, counted after the driver has taken the previous frame
     * @return the load progress from {@code 0} to {@code 1} ({@code 1} when complete or when no load was started)
     */
    public float continueLoad(long budgetNanoseconds) {
        if (!this.loading) {
            return 1;
        }

        Thread thread = this.uploadThread;

        if (thread != null) {
            if (thread.isAlive()) {
                return this.getLoadProgress();
            }

            joinUploadThread(thread);
            this.uploadThread = null;

            if (this.loadImage >= this.loadImageCount) {
                this.loading = false;

                return 1;
            }

            // The upload thread failed: the render thread continues the load in time slices.
            LOGGER.warning("HD image upload thread ended at image {0}; the render thread continues the load.", this.loadImage);
        }

        int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);

        glActiveTexture(GL_TEXTURE0);

        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        // The state query waits for the driver to take the previous frame (with vsync, until its presentation); the budget starts after it so that
        // the wait does not use up the frame's upload time.
        long deadline = System.nanoTime() + budgetNanoseconds;

        try {
            while (this.loadImage < this.loadImageCount) {
                int image = this.loadImage;

                // An image sharing a lower image's anim was loaded (or found missing) with that image.
                if (this.animations.getSharedImage(image) != image || this.images[image] != null || this.missingImages[image]) {
                    this.loadImage++;

                    continue;
                }

                try {
                    HdImageLoader.Prepared prepared = this.loader.poll(image, this.lighting);

                    if (prepared == null) {
                        if (System.nanoTime() >= deadline) {
                            return this.getLoadProgress();
                        }

                        long waitStarted = System.nanoTime();

                        LockSupport.parkNanos(LOAD_POLL_NANOSECONDS);
                        this.loadWaitNanoseconds += System.nanoTime() - waitStarted;

                        continue;
                    }

                    long uploadStarted = System.nanoTime();

                    HdImageTextures textures = this.createTextures(image, prepared);

                    this.images[image] = textures;

                    this.loadUploadNanoseconds += System.nanoTime() - uploadStarted;
                } catch (IOException exception) {
                    this.missingImages[image] = true;
                    LOGGER.warning("HD image {0} is unavailable: {1}", image, exception.getMessage());
                }

                this.loadImage++;

                if (System.nanoTime() >= deadline) {
                    return this.getLoadProgress();
                }
            }

            this.loading = false;

            return 1;
        } finally {
            glBindTexture(GL_TEXTURE_2D, previousTexture);
            glActiveTexture(activeTexture);
        }
    }

    /**
     * Stops a running load and releases every image; the set is empty and not resident again.
     */
    public void cancelLoad() {
        this.releaseImages();
        this.loading               = false;
        this.resident              = false;
        this.loadImage             = 0;
        this.loadUploadNanoseconds = 0;
        this.loadWaitNanoseconds   = 0;
    }

    /**
     * Resets the per-frame upload time of streamed images.
     */
    void beginFrame() {
        this.uploadNanoseconds = 0;
    }

    /**
     * Returns the textures of an image, uploading it if needed.
     *
     * @param image        the image identifier
     * @param streaming    {@code true} to skip an image that is not prepared yet (it is queued for the loader threads) or when this frame's upload
     *                     budget is spent, {@code false} to wait for it
     * @param uploadBudget the upload time per frame while streaming
     * @param frame        the scene frame counter, for least-recently-used eviction
     * @param beforeUpload run before an upload (the scene flushes its batch)
     * @return the textures, or {@code null} while a streamed image is not ready
     * @throws IOException if the image has no HD anim or cannot be prepared
     */
    @Nullable HdImageTextures acquire(int image, boolean streaming, long uploadBudget, long frame, @NotNull Runnable beforeUpload) throws IOException {
        if (image < 0 || image >= IMAGE_ID_COUNT) {
            throw Exceptions.newIOException("Invalid image identifier: {0}", image);
        }

        this.awaitUploadThread();
        image = this.animations.getSharedImage(image);

        HdImageTextures textures = this.images[image];

        if (textures == null) {
            if (this.missingImages[image]) {
                throw Exceptions.newIOException("Image {0} has no HD anim.", image);
            }

            HdImageLoader.Prepared prepared;

            if (streaming) {
                if (this.uploadNanoseconds > uploadBudget) {
                    this.loader.request(image, this.lighting);

                    return null;
                }

                prepared = this.loader.poll(image, this.lighting);

                if (prepared == null) {
                    return null;
                }
            } else {
                prepared = this.loader.await(image, this.lighting);
            }

            long started = System.nanoTime();

            beforeUpload.run();
            this.evictTextures(prepared.getByteSize(), frame);

            HdImageTextures uploadedTextures = this.createTextures(image, prepared);

            this.images[image] = uploadedTextures;

            textures = uploadedTextures;

            this.uploadNanoseconds += System.nanoTime() - started;
        }

        textures.lastUsedFrame = frame;

        return textures;
    }

    /**
     * Returns an image's diffuse-layer textures, uploading them separately when the set uses lit layers. Without lit layers, returns the ordinary
     * image textures.
     *
     * @param image        the image identifier
     * @param streaming    {@code true} to skip an image that is not prepared yet (it is queued for the loader threads), {@code false} to wait for it
     * @param uploadBudget the upload time per frame while streaming
     * @param frame        the scene frame counter, for least-recently-used eviction
     * @param beforeUpload run before an upload (the scene flushes its batch)
     * @return the textures, or {@code null} while a streamed image is not ready
     * @throws IOException if the image has no HD anim or cannot be prepared
     */
    @Nullable HdImageTextures acquireDiffuse(int image, boolean streaming, long uploadBudget, long frame, @NotNull Runnable beforeUpload) throws IOException {
        if (!this.lighting) {
            return this.acquire(image, streaming, uploadBudget, frame, beforeUpload);
        }

        if (image < 0 || image >= IMAGE_ID_COUNT) {
            throw Exceptions.newIOException("Invalid image identifier: {0}", image);
        }

        this.awaitUploadThread();
        image = this.animations.getSharedImage(image);

        HdImageTextures textures = this.diffuseImages[image];

        if (textures == null) {
            if (this.missingImages[image]) {
                throw Exceptions.newIOException("Image {0} has no HD anim.", image);
            }

            HdImageLoader.Prepared prepared;

            if (streaming) {
                prepared = this.loader.poll(image, false);

                if (prepared == null) {
                    return null;
                }
            } else {
                prepared = this.loader.await(image, false);
            }

            long started = System.nanoTime();

            beforeUpload.run();
            this.evictTextures(prepared.getByteSize(), frame);

            textures                  = this.createTextures(image, prepared);
            this.diffuseImages[image] = textures;

            this.uploadNanoseconds += System.nanoTime() - started;
        }

        textures.lastUsedFrame = frame;

        return textures;
    }

    /**
     * Returns the textures of an override sheet, uploading it once the loader threads have prepared it. Past the override budget, the sheets used
     * least recently release their textures, also in a resident set. A sheet whose preparation failed is logged once and gives no textures.
     *
     * @param sheet        the texture sheet of an override anim
     * @param streaming    {@code true} to skip the upload when this frame's upload budget is spent, {@code false} to upload once prepared
     * @param uploadBudget the upload time per frame while streaming
     * @param frame        the scene frame counter
     * @param beforeUpload run before an upload (the scene flushes its batch)
     * @return the textures, or {@code null} while the sheet is not prepared or uploaded yet, or when its preparation failed
     */
    @Nullable HdImageTextures acquireSheet(@NotNull TextureSheet sheet,
                                           boolean streaming, long uploadBudget, long frame, @NotNull Runnable beforeUpload) {
        this.awaitUploadThread();

        HdImageTextures textures = this.sheetImages.get(sheet);

        if (textures == null) {
            if (this.failedSheets.contains(sheet) || streaming && this.uploadNanoseconds > uploadBudget) {
                return null;
            }

            HdImageLoader.Prepared prepared;

            try {
                prepared = this.loader.pollSheet(sheet, this.lighting);
            } catch (IOException exception) {
                LOGGER.warning("An HD override sheet is unavailable: {0}", exception.getMessage());

                if (this.failedSheets.size() >= MAXIMUM_FAILED_SHEETS) {
                    this.failedSheets.removeFirst();
                }

                this.failedSheets.add(sheet);

                return null;
            }

            if (prepared == null) {
                return null;
            }

            long started = System.nanoTime();

            beforeUpload.run();

            while (!this.sheetImages.isEmpty() && this.sheetTextureBytes + prepared.getByteSize() > this.sheetBudget) {
                HdImageTextures evicted = this.sheetImages.pollFirstEntry().getValue();

                evicted.delete();
                this.sheetTextureBytes -= evicted.byteSize;
            }

            textures = this.createTextures(sheet, prepared, true);
            this.sheetTextureBytes += textures.byteSize;

            this.uploadNanoseconds += System.nanoTime() - started;
        }

        this.sheetImages.putLast(sheet, textures);
        textures.lastUsedFrame = frame;

        return textures;
    }

    /**
     * Releases the textures of every override sheet.
     */
    void releaseSheets() {
        for (HdImageTextures textures : this.sheetImages.values()) {
            textures.delete();
        }

        this.sheetImages.clear();
        this.failedSheets.clear();
        this.sheetTextureBytes = 0;
    }

    private @NotNull HdImageTextures createTextures(int image, HdImageLoader.@NotNull Prepared prepared) throws IOException {
        TextureSheet sheet = prepared.getSheet();

        if (sheet == null) {
            prepared.free();
            this.missingImages[image] = true;

            throw Exceptions.newIOException("Image {0} has no HD anim.", image);
        }

        HdImageTextures textures = this.createTextures(sheet, prepared, image >= ImagesDat.IMAGE_COUNT);

        this.textureBytes += textures.byteSize;

        return textures;
    }

    /**
     * Uploads the layers of a prepared sheet and frees its buffers.
     *
     * @param sheet          the prepared texture sheet
     * @param prepared       the prepared layers
     * @param keepsTeamColor whether a frame drawn from the diffuse layer in the lit path keeps the team color mask
     * @return the textures
     */
    private @NotNull HdImageTextures createTextures(@NotNull TextureSheet sheet, HdImageLoader.@NotNull Prepared prepared, boolean keepsTeamColor) {
        int[] names = new int[HdImageLoader.UNLIT_LAYER + 1];

        try {
            for (int layer = 0; layer < prepared.getLayerCount(); layer++) {
                names[layer] = uploadTexture(prepared, layer);
            }
        } finally {
            prepared.free();
        }

        // Lighting data: the bright (sprite layer when loaded), normal, specular and AO/depth layers all present.
        int[] lightingTextures = Arrays.copyOfRange(names, HdImageLoader.FIRST_LIGHTING_LAYER, HdImageLoader.UNLIT_LAYER);
        boolean lightingData = prepared.isBright() && lightingTextures[HdImageLoader.LIGHTING_NORMAL] != 0 &&
                               lightingTextures[HdImageLoader.LIGHTING_SPECULAR] != 0 && lightingTextures[HdImageLoader.LIGHTING_AO_DEPTH] != 0;
        int unlitTexture = prepared.isBright() ? names[HdImageLoader.UNLIT_LAYER] : names[HdImageLoader.SPRITE_LAYER];
        return new HdImageTextures(HdFrameGeometry.of(sheet),
                                   names[HdImageLoader.SPRITE_LAYER], names[HdImageLoader.TEAM_COLOR_LAYER],
                                   lightingTextures, unlitTexture, lightingData,
                                   keepsTeamColor,
                                   prepared.getTextureWidth(), prepared.getTextureHeight(),
                                   prepared.getByteSize());
    }

    private void evictTextures(long incomingBytes, long frame) {
        if (this.resident) {
            return;
        }

        while (this.textureBytes + incomingBytes > this.textureBudget) {
            HdImageTextures[] oldestTable = null;
            int oldest = -1;
            long oldestFrame = frame;

            for (int image = 0; image < this.images.length; image++) {
                HdImageTextures candidate = this.images[image];

                if (candidate != null && candidate.lastUsedFrame < oldestFrame) {
                    oldestTable = this.images;
                    oldest      = image;
                    oldestFrame = candidate.lastUsedFrame;
                }

                HdImageTextures diffuse = this.diffuseImages[image];

                if (diffuse != null && diffuse.lastUsedFrame < oldestFrame) {
                    oldestTable = this.diffuseImages;
                    oldest      = image;
                    oldestFrame = diffuse.lastUsedFrame;
                }
            }

            if (oldestTable == null) {
                return;
            }

            HdImageTextures evicted = oldestTable[oldest];

            evicted.delete();
            this.textureBytes -= evicted.byteSize;
            oldestTable[oldest] = null;
        }
    }

    private void releaseImages() {
        this.stopUploadThread();

        for (int image = 0; image < this.images.length; image++) {
            HdImageTextures textures = this.images[image];

            if (textures != null) {
                textures.delete();
                this.images[image] = null;
            }

            HdImageTextures diffuse = this.diffuseImages[image];

            if (diffuse != null) {
                diffuse.delete();
                this.diffuseImages[image] = null;
            }
        }

        this.releaseSheets();
        this.textureBytes = 0;
        this.loader.clear();
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
     * @implSpec Releases the textures and stops the loader threads.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        this.stopUploadThread();
        this.loader.close();
        this.releaseImages();
    }
}
