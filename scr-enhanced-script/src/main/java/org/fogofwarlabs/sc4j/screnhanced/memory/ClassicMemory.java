package org.fogofwarlabs.sc4j.screnhanced.memory;

import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemorySink;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemorySource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * The classic memory of one game: the classic structures ({@link #createStructures}) that SC:R serves as zero or write-protected values, as nodes
 * of its own or smaller than the classic game, each served as the classic game laid it out, with its buffers on a heap of the game's own
 * ({@link ClassicHeap}). Every other address keeps what SC:R serves.
 */
public final class ClassicMemory implements ClassicMemorySource {
    /**
     * The width of the classic game screen in pixels.
     */
    public static final int SCREEN_WIDTH = 640;
    /**
     * The height of the classic game screen in pixels.
     */
    public static final int SCREEN_HEIGHT = 480;
    /**
     * The game screen bitmap: the frame the classic game draws and shows.
     */
    public static final int GAME_SCREEN = 0x6CEFF0;
    /**
     * The pointer to the resource icons: the installed {@code game\icons.grp}, a GRP whose frames hold their pixels uncompressed, rows from the
     * top.
     */
    public static final int RESOURCE_ICONS = 0x68C238;
    /**
     * The size of the block of the resource icons: the installed GRP and room after it for a larger one.
     */
    public static final int RESOURCE_ICONS_SIZE = 0x10000;
    /**
     * The pointer to the console transparency: a header whose DWORD at {@code +8} is {@link #CONSOLE_TRANSPARENCY_DATA}, followed by the
     * picture size and the bounds of its opaque pixels.
     */
    public static final int CONSOLE_TRANSPARENCY = 0x6D5E14;
    /**
     * The data of the console transparency: for every screen row, pairs of BYTE counts of skipped and copied pixels, and a zero pair at the row's
     * end.
     */
    public static final int CONSOLE_TRANSPARENCY_DATA = 0xE770B0;
    /**
     * The size of the largest transparency data of one screen: per row, one pair for every two pixels and the end pair.
     */
    public static final int TRANSPARENCY_DATA_SIZE = SCREEN_HEIGHT * (SCREEN_WIDTH + Short.BYTES);

    /**
     * Reads an installed file of the game.
     */
    @FunctionalInterface
    public interface InstalledFile {
        /**
         * Reads the file.
         *
         * @return the file's bytes
         * @throws IOException if the file cannot be read
         */
        byte @NotNull [] read() throws IOException;
    }

    /**
     * Lists the served structures, in heap order: the buffers, the dialogs ({@link ClassicDialogStructure#DIALOGS}), the console areas and the image
     * GRP table.
     *
     * @param resourceIcons the initial bytes of the resource icons, at most {@link #RESOURCE_ICONS_SIZE}
     * @return the structures
     */
    public static @NotNull List<@NotNull ClassicStructure> createStructures(byte @NotNull [] resourceIcons) {
        return List.of(
                new ClassicPointerStructure(RESOURCE_ICONS, RESOURCE_ICONS_SIZE, resourceIcons),
                new ClassicBitmapStructure(GAME_SCREEN, SCREEN_WIDTH, SCREEN_HEIGHT),
                new ClassicBufferStructure(CONSOLE_TRANSPARENCY_DATA, TRANSPARENCY_DATA_SIZE),
                ClassicDialogStructure.COMMAND_CARD,
                ClassicDialogStructure.SELECTION,
                ClassicDialogStructure.RESOURCES,
                ClassicDialogStructure.PORTRAIT,
                ClassicDialogStructure.MINIMAP,
                ClassicDialogStructure.MENU,
                ClassicDialogStructure.TEXT_BOX,
                new ClassicConsoleAreaStructure(),
                new ClassicGrpTableStructure()
        );
    }

    private final @Nullable InstalledFile resourceIcons;

    /**
     * Creates a {@link ClassicMemory} instance whose resource icons are zero.
     */
    public ClassicMemory() {
        this(null);
    }

    /**
     * Creates a {@link ClassicMemory} instance.
     *
     * @param resourceIcons reads the installed resource icons when the regions are added, or {@code null} for zero
     */
    public ClassicMemory(@Nullable InstalledFile resourceIcons) {
        this.resourceIcons = resourceIcons;
    }

    /**
     * Adds the regions of every served structure to the game's address space; the resource icons are zero when the installed file cannot be read
     * or does not fit.
     *
     * @param sink the sink that adds the regions
     */
    @Override
    public void provideRegions(@NotNull ClassicMemorySink sink) {
        ClassicHeap heap = new ClassicHeap();

        for (ClassicStructure structure : createStructures(this.readResourceIcons())) {
            structure.provide(sink, heap);
        }
    }

    private byte @NotNull [] readResourceIcons() {
        InstalledFile file = this.resourceIcons;

        if (file == null) {
            return new byte[0];
        }

        try {
            byte[] bytes = file.read();

            return bytes.length <= RESOURCE_ICONS_SIZE ? bytes : new byte[0];
        } catch (IOException exception) {
            return new byte[0];
        }
    }
}
