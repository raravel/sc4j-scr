package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TextureSheet;
import org.fogofwarlabs.sc4j.scr.data.format.image.AnimFile;
import org.fogofwarlabs.sc4j.scr.data.resource.importer.AnimTextureUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * An SC:R anim in game memory at an address: a single-entry anim of asset scale {@code 4} or {@code 2} with a canvas and a block-compressed
 * {@code diffuse} layer. Its bytes are read through the memory view when its sheet is first asked for and parsed into a texture sheet on a worker
 * thread; a write to the bytes read reads them again on the next use, and the sheet parsed last stays the result until the new one is ready. Bytes
 * that are no such anim give no sheet until they are written again.
 */
final class EmbeddedAnim {
    /**
     * The anim header magic {@code ANIM}.
     */
    static final int MAGIC = 0x4D494E41;
    /**
     * The offset of the asset scale byte in the anim header.
     */
    static final int SCALE_OFFSET = 4;
    /**
     * The asset scale of HD anims.
     */
    static final int HD_SCALE = 4;
    /**
     * The asset scale of HD2 anims.
     */
    static final int HD2_SCALE = 2;

    /**
     * The largest anim read: the largest map scenario.
     */
    private static final int MAXIMUM_SIZE = 0x10000000;
    private static final @NotNull String DIFFUSE_LAYER_NAME = "diffuse";

    /**
     * The worker thread that parses anims, shared by every game.
     */
    private static final @NotNull ExecutorService PARSER;

    /**
     * The parsed sheets of one game, bounded by their bytes: past the budget, the anim used least recently drops its sheet and reads its bytes
     * again on its next use.
     */
    static final class Cache {
        private final long budget;
        private final @NotNull SequencedMap<EmbeddedAnim, Long> sizes;

        private long size;

        /**
         * Creates a {@link Cache} instance.
         *
         * @param budget the bytes of parsed sheets kept
         */
        Cache(long budget) {
            this.budget = budget;
            this.sizes  = new LinkedHashMap<>();

            this.size = 0;
        }

        /**
         * Marks an anim's sheet used, dropping the sheets used least recently while the kept bytes exceed the budget.
         *
         * @param anim  the anim
         * @param bytes the bytes of its sheet
         */
        private void use(@NotNull EmbeddedAnim anim, long bytes) {
            Long previous = this.sizes.remove(anim);

            this.size += bytes - (previous == null ? 0 : previous);
            this.sizes.putLast(anim, bytes);

            while (this.size > this.budget && this.sizes.size() > 1) {
                Map.Entry<EmbeddedAnim, Long> eldest = this.sizes.pollFirstEntry();

                this.size -= eldest.getValue();
                eldest.getKey().drop();
            }
        }

        private void remove(@NotNull EmbeddedAnim anim) {
            Long previous = this.sizes.remove(anim);

            if (previous != null) {
                this.size -= previous;
            }
        }
    }

    static {
        PARSER = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Embedded anim parser");

            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);

            return thread;
        });
    }

    /**
     * Parses anim bytes into the texture sheet of its one entry.
     *
     * @param data the anim bytes
     * @return the texture sheet
     * @throws IOException if the bytes are no single-entry anim of scale {@code 4} or {@code 2} with a canvas and a block-compressed {@code diffuse} layer
     */
    static @NotNull TextureSheet parse(byte @NotNull [] data) throws IOException {
        return AnimTextureUtils.createSheet(readFile(data));
    }

    /**
     * Checks that anim bytes parse into a texture sheet ({@link #parse}), and reads their asset scale.
     *
     * @param data the anim bytes
     * @return the asset scale: {@value #HD_SCALE} or {@value #HD2_SCALE}
     * @throws IOException if the bytes are no single-entry anim of scale {@code 4} or {@code 2} with a canvas and a block-compressed {@code diffuse} layer
     */
    static int check(byte @NotNull [] data) throws IOException {
        AnimFile file = readFile(data);

        AnimTextureUtils.createSheet(file);

        return file.getScale();
    }

    /**
     * Reads anim bytes as a single-entry anim of scale {@code 4} or {@code 2} with a canvas and a {@code diffuse} layer, without its textures.
     */
    private static @NotNull AnimFile readFile(byte @NotNull [] data) throws IOException {
        AnimFile file = AnimFile.parse(data);

        if (file.getEntryCount() != 1 || file.getScale() != HD_SCALE && file.getScale() != HD2_SCALE) {
            throw Exceptions.newIOException("The anim is no single-entry HD or HD2 anim: {0} entries, scale {1}.",
                                            file.getEntryCount(), file.getScale());
        }

        AnimFile.Entry entry = file.getEntry(0);

        if (entry.isReference() || entry.getWidth() == 0 || entry.getHeight() == 0 || !entry.hasLayer(file.findLayer(DIFFUSE_LAYER_NAME))) {
            throw Exceptions.newIOException("The anim has no frames on a canvas or no diffuse layer.");
        }

        return file;
    }


    /**
     * Finds the texture sheet drawn at an asset scale: the anim of that scale, else the anim of the other scale. A sheet that is not parsed yet
     * starts its parse and is not returned until it is ready; meanwhile the anim of the drawn scale gives the sheet it parsed last, and the
     * anim of the other scale is asked only when the drawn scale has no anim or its anim parses nothing.
     *
     * @param scale   the asset scale drawn: {@value #HD_SCALE} for HD, {@value #HD2_SCALE} for HD2
     * @param hdAnim  the HD anim, or {@code null} for none
     * @param hd2Anim the HD2 anim, or {@code null} for none
     * @return the texture sheet, or {@code null} if no anim is ready
     */
    static @Nullable TextureSheet findSheet(int scale, @Nullable EmbeddedAnim hdAnim, @Nullable EmbeddedAnim hd2Anim) {
        EmbeddedAnim first = scale == HD2_SCALE ? hd2Anim : hdAnim;
        EmbeddedAnim second = scale == HD2_SCALE ? hdAnim : hd2Anim;
        TextureSheet sheet = first == null ? null : first.findSheet();

        if (sheet != null || first != null && first.isParsing() || second == null) {
            return sheet;
        }

        return second.findSheet();
    }


    private final @NotNull EmbeddedMemory memory;
    private final int address;
    private final @NotNull Cache cache;

    /**
     * The end of the observed bytes, as an offset from the address.
     */
    private long observedEnd;
    /**
     * Whether the bytes were written, or the sheet dropped, since they were read last.
     */
    private boolean changed;
    /**
     * The parse of the bytes read last, or {@code null} while none runs.
     */
    private @Nullable Future<TextureSheet> parsing;
    private int parsedSize;
    /**
     * The sheet parsed last, or {@code null} before one is ready or when the bytes read last were no anim.
     */
    private @Nullable TextureSheet sheet;

    /**
     * Creates an {@link EmbeddedAnim} instance for an address, read on first use.
     *
     * @param memory  the memory the bytes are read and observed through
     * @param address the address of the anim header
     * @param cache   the parsed sheets of the game
     */
    EmbeddedAnim(@NotNull EmbeddedMemory memory, int address, @NotNull Cache cache) {
        this.memory  = memory;
        this.address = address;
        this.cache   = cache;

        this.observedEnd = 0;
        this.changed     = true;
        this.parsing     = null;
        this.parsedSize  = 0;
        this.sheet       = null;
    }

    /**
     * Finds the anim's texture sheet: the sheet parsed last, reading and parsing the bytes again first if they were written since.
     *
     * @return the texture sheet, or {@code null} until one is ready or when the bytes are no anim
     */
    @Nullable TextureSheet findSheet() {
        Future<TextureSheet> current = this.parsing;

        if (current != null && current.isDone()) {
            this.parsing = null;

            try {
                this.sheet = current.get();
                this.cache.use(this, this.parsedSize);
            } catch (ExecutionException exception) {
                this.sheet = null;
                this.cache.remove(this);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        } else if (this.sheet != null) {
            this.cache.use(this, this.parsedSize);
        }

        if (this.changed && this.parsing == null) {
            this.changed = false;

            byte[] data = this.read();

            if (data == null) {
                this.sheet = null;
                this.cache.remove(this);
            } else {
                this.parsedSize = data.length;
                this.parsing    = PARSER.submit(() -> parse(data));
            }
        }

        return this.sheet;
    }

    /**
     * @return {@code true} if a parse of the anim's bytes runs, {@code false} otherwise
     */
    boolean isParsing() {
        return this.parsing != null;
    }

    /**
     * Drops the parsed sheet; the next use reads the bytes again.
     */
    private void drop() {
        this.sheet   = null;
        this.changed = true;
    }

    /**
     * Reads the anim's bytes: its header and entry records, then every byte up to the end of its frame table and layer textures.
     *
     * @return the bytes, or {@code null} if the bytes at the address are no anim or cannot be read
     */
    private byte @Nullable [] read() {
        byte[] data = new byte[0];
        long size = AnimFile.findContentSize(data, 0);

        while (size > data.length && size <= MAXIMUM_SIZE) {
            byte[] grown = new byte[(int) size];
            int count = this.memory.read(this.address, grown, 0, grown.length);

            this.observe(Math.max(count, Integer.BYTES));

            if (count != grown.length) {
                return null;
            }

            data = grown;
            size = AnimFile.findContentSize(data, data.length);
        }

        this.observe(Integer.BYTES);

        return size > 0 && size == data.length ? data : null;
    }

    /**
     * Observes the writes to the bytes from the address up to an offset, past the bytes observed so far.
     *
     * @param end the end of the bytes to observe, as an offset from the address
     */
    private void observe(long end) {
        if (end > this.observedEnd) {
            this.memory.observeWrites(this.address + (int) this.observedEnd, (int) (end - this.observedEnd), this::onWrite);
            this.observedEnd = end;
        }
    }

    /**
     * Hears a write to the anim's bytes: the next use reads them again.
     *
     * @param address the aligned classic address
     * @param value   the written DWORD
     */
    private void onWrite(int address, int value) {
        this.changed = true;
    }
}
