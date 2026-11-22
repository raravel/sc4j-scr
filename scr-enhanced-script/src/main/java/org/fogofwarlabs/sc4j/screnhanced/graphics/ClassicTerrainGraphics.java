package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemoryView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;

/**
 * The classic terrain pictures of one game: the minitile pixels ({@code .vr4}) and the megatile minitile references ({@code .vx4}) the classic
 * game kept in memory at the addresses its tileset pointers hold. A write to them changes the picture of every megatile that shows the written
 * minitile or whose references were written; the megatile draws the picture composed from the written and installed data.
 * <p>
 * The pictures are presentation: the plugin observes the writes through the game's memory view and never changes the game. The installed data is
 * read on the first write.
 */
public final class ClassicTerrainGraphics implements ScrGamePlugin {
    /**
     * The pointer to the minitile pixels: {@value #MINITILE_SIZE} palette indices per minitile, rows from the top.
     */
    public static final int MINITILE_POINTER = 0x628444;
    /**
     * The size of the minitile pixel buffer.
     */
    public static final int MINITILE_BUFFER_SIZE = 0x300000;
    /**
     * The pointer to the megatile references: one WORD per minitile of a megatile, rows from the top, the minitile index shifted left by one
     * above the horizontal mirror bit.
     */
    public static final int MEGATILE_POINTER = 0x628458;
    /**
     * The size of the megatile reference buffer.
     */
    public static final int MEGATILE_BUFFER_SIZE = 0x32000;
    /**
     * The bytes of one minitile's pixels.
     */
    public static final int MINITILE_SIZE = TerrainTileset.MINI_TILE_SIZE * TerrainTileset.MINI_TILE_SIZE;
    /**
     * The minitiles of a megatile.
     */
    public static final int MEGATILE_MINITILES = TerrainTileset.MINI_TILES_PER_ROW * TerrainTileset.MINI_TILES_PER_ROW;
    /**
     * The bytes of one classic megatile entry: one WORD reference per minitile.
     */
    public static final int MEGATILE_ENTRY_SIZE = MEGATILE_MINITILES * Short.BYTES;

    /**
     * The extension of the installed minitile pixel file.
     */
    static final @NotNull String MINITILE_EXTENSION = ".vr4";
    /**
     * The extension of the installed megatile reference file.
     */
    static final @NotNull String MEGATILE_EXTENSION = ".vx4ex";

    /**
     * The bytes of one installed {@code .vx4ex} megatile entry: one DWORD reference per minitile.
     */
    private static final int INSTALLED_MEGATILE_ENTRY_SIZE = MEGATILE_MINITILES * Integer.BYTES;
    private static final int MIRRORED = 1;
    private static final int INDEX_SHIFT = 1;
    private static final int WORD_MASK = 0xFFFF;
    private static final int WORD_SHIFT = 16;
    private static final int DWORD_MASK = Integer.BYTES - 1;

    /**
     * Reads the files of the installed tileset.
     */
    @FunctionalInterface
    public interface InstalledTileset {
        /**
         * Reads a file of the game's installed tileset.
         *
         * @param extension the file extension with its dot, {@code .vr4} or {@code .vx4ex}
         * @return the file contents
         * @throws IOException if the file cannot be read
         */
        byte @NotNull [] readFile(@NotNull String extension) throws IOException;
    }

    private final @NotNull InstalledTileset installed;

    private int minitileBase;
    private int megatileBase;

    private byte @Nullable [] installedMinitiles;
    private byte @Nullable [] installedMegatiles;
    private boolean installedFailed;

    /**
     * The written minitiles: their pixels, installed pixels with the written bytes.
     */
    private final @NotNull Map<Integer, byte[]> minitiles;
    /**
     * The written megatile entries: their references, installed references with the written WORDs.
     */
    private final @NotNull Map<Integer, int[]> megatiles;
    /**
     * The composed pictures of the changed megatiles, as of the last composition.
     */
    private final @NotNull Map<Integer, byte[]> pictures;
    private boolean changed;

    /**
     * Creates a {@link ClassicTerrainGraphics} instance over a game's installed tileset.
     *
     * @param installed the installed tileset files
     */
    public ClassicTerrainGraphics(@NotNull InstalledTileset installed) {
        this.installed = installed;

        this.minitileBase = 0;
        this.megatileBase = 0;

        this.installedMinitiles = null;
        this.installedMegatiles = null;
        this.installedFailed    = false;

        this.minitiles = new HashMap<>();
        this.megatiles = new HashMap<>();
        this.pictures  = new HashMap<>();
        this.changed   = false;
    }

    /**
     * Hears that the game's EUD address space was created: observes the buffers its two tileset pointers name.
     *
     * @param memory the read-only view of the address space
     */
    @Override
    public void onAddressSpaceCreated(@NotNull ClassicMemoryView memory) {
        this.minitileBase = readPointer(memory, MINITILE_POINTER);
        this.megatileBase = readPointer(memory, MEGATILE_POINTER);

        if (this.minitileBase != 0) {
            memory.observeWrites(this.minitileBase, MINITILE_BUFFER_SIZE, this::onMinitileWrite);
        }

        if (this.megatileBase != 0) {
            memory.observeWrites(this.megatileBase, MEGATILE_BUFFER_SIZE, this::onMegatileWrite);
        }
    }

    /**
     * Finds the picture a megatile draws instead of its installed one: the picture composed from the written data, if the megatile shows a written
     * minitile or its references were written.
     *
     * @param megatile the megatile index of a drawn tile
     * @return the palette indices, the same array while the picture is unchanged, or {@code null} for the installed picture
     */
    @Override
    public byte @Nullable [] findTerrainTile(int megatile) {
        if (this.changed) {
            this.changed = false;
            this.compose();
        }

        return this.pictures.get(megatile);
    }

    private static int readPointer(@NotNull ClassicMemoryView memory, int address) {
        byte[] pointer = new byte[Integer.BYTES];

        return memory.read(address, pointer, 0, pointer.length) == pointer.length ? LittleEndianUtils.readInt32(pointer, 0) : 0;
    }

    /**
     * Stores a written DWORD of minitile pixels.
     *
     * @param address the written classic address
     * @param value   the stored DWORD
     */
    private void onMinitileWrite(int address, int value) {
        if (!this.loadInstalled()) {
            return;
        }

        int offset = address - this.minitileBase;
        byte[] pixels = this.minitiles.computeIfAbsent(offset / MINITILE_SIZE, this::copyInstalledMinitile);

        LittleEndianUtils.writeInt32(pixels, offset % MINITILE_SIZE & ~DWORD_MASK, value);
        this.changed = true;
    }

    /**
     * Stores a written DWORD of megatile references: two WORD references.
     *
     * @param address the written classic address
     * @param value   the stored DWORD
     */
    private void onMegatileWrite(int address, int value) {
        if (!this.loadInstalled()) {
            return;
        }

        int offset = address - this.megatileBase;
        int[] references = this.megatiles.computeIfAbsent(offset / MEGATILE_ENTRY_SIZE, this::copyInstalledMegatile);
        int word = (offset % MEGATILE_ENTRY_SIZE & ~DWORD_MASK) / Short.BYTES;

        references[word]     = value & WORD_MASK;
        references[word + 1] = value >>> WORD_SHIFT;
        this.changed         = true;
    }

    /**
     * Reads the installed minitile pixels and megatile references once.
     *
     * @return {@code true} if they are loaded, {@code false} if they cannot be read
     */
    private boolean loadInstalled() {
        if (this.installedMinitiles != null) {
            return true;
        }

        if (this.installedFailed) {
            return false;
        }

        try {
            this.installedMegatiles = this.installed.readFile(MEGATILE_EXTENSION);
            this.installedMinitiles = this.installed.readFile(MINITILE_EXTENSION);

            return true;
        } catch (IOException exception) {
            this.installedFailed = true;

            return false;
        }
    }

    private byte @NotNull [] copyInstalledMinitile(int minitile) {
        byte[] pixels = new byte[MINITILE_SIZE];
        byte[] source = this.installedMinitiles;
        long start = (long) minitile * MINITILE_SIZE;

        if (source != null && start + MINITILE_SIZE <= source.length) {
            System.arraycopy(source, (int) start, pixels, 0, MINITILE_SIZE);
        }

        return pixels;
    }

    private int @NotNull [] copyInstalledMegatile(int megatile) {
        int[] references = new int[MEGATILE_MINITILES];
        byte[] source = this.installedMegatiles;
        long start = (long) megatile * INSTALLED_MEGATILE_ENTRY_SIZE;

        if (source != null && start + INSTALLED_MEGATILE_ENTRY_SIZE <= source.length) {
            for (int index = 0; index < MEGATILE_MINITILES; index++) {
                references[index] = LittleEndianUtils.readInt32(source, (int) start + index * Integer.BYTES);
            }
        }

        return references;
    }

    /**
     * Composes the pictures of the megatiles that show a written minitile or whose references were written; a picture equal to the last one keeps
     * its array.
     */
    private void compose() {
        Set<Integer> changedMegatiles = new HashSet<>(this.megatiles.keySet());
        byte[] source = this.installedMegatiles;

        if (source != null && !this.minitiles.isEmpty()) {
            for (int megatile = 0; megatile < source.length / INSTALLED_MEGATILE_ENTRY_SIZE; megatile++) {
                for (int index = 0; index < MEGATILE_MINITILES; index++) {
                    int reference = LittleEndianUtils.readInt32(source, megatile * INSTALLED_MEGATILE_ENTRY_SIZE + index * Integer.BYTES);

                    if (this.minitiles.containsKey(reference >>> INDEX_SHIFT)) {
                        changedMegatiles.add(megatile);

                        break;
                    }
                }
            }
        }

        for (int megatile : changedMegatiles) {
            byte[] picture = this.composeMegatile(megatile);
            byte[] previous = this.pictures.get(megatile);

            if (previous == null || !Arrays.equals(previous, picture)) {
                this.pictures.put(megatile, picture);
            }
        }
    }

    /**
     * Composes a megatile's picture from its references and the minitile pixels, mirroring a minitile whose reference has the mirror bit.
     *
     * @param megatile the megatile index
     * @return the {@code 32x32} palette indices
     */
    private byte @NotNull [] composeMegatile(int megatile) {
        int[] references = this.megatiles.get(megatile);

        if (references == null) {
            references = this.copyInstalledMegatile(megatile);
        }

        byte[] picture = new byte[TerrainTileset.TILE_SIZE * TerrainTileset.TILE_SIZE];

        for (int index = 0; index < MEGATILE_MINITILES; index++) {
            int reference = references[index];
            int minitile = reference >>> INDEX_SHIFT;
            byte[] pixels = this.minitiles.get(minitile);

            if (pixels == null) {
                pixels = this.copyInstalledMinitile(minitile);
            }

            int left = index % TerrainTileset.MINI_TILES_PER_ROW * TerrainTileset.MINI_TILE_SIZE;
            int top = index / TerrainTileset.MINI_TILES_PER_ROW * TerrainTileset.MINI_TILE_SIZE;
            boolean mirrored = (reference & MIRRORED) != 0;

            for (int row = 0; row < TerrainTileset.MINI_TILE_SIZE; row++) {
                for (int column = 0; column < TerrainTileset.MINI_TILE_SIZE; column++) {
                    int sourceColumn = mirrored ? TerrainTileset.MINI_TILE_SIZE - 1 - column : column;

                    picture[(top + row) * TerrainTileset.TILE_SIZE + left + column] = pixels[row * TerrainTileset.MINI_TILE_SIZE + sourceColumn];
                }
            }
        }

        return picture;
    }
}
