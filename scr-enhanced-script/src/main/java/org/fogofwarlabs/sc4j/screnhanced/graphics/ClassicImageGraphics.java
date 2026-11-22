package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageAnimation;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageSheet;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImagePool;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemoryView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The classic image graphics of one game: which GRP in game memory each image draws, by the rule of the classic game. A write to the image GRP
 * table names the GRP of the images created after it; a write to the GRP pointer of an image array entry names the GRP of the image that holds the
 * entry at that time. An image draws its entry's pointer if the image existed at that write, else the table pointer of its image type written last
 * before the image was created; pointer {@code 0} draws the installed graphics.
 * <p>
 * Writes to the frame and the render function of an image array entry apply to the image that holds the entry, as long as the image's own frame,
 * or its own draw function, is the one it had at the write: the next change of the image's own state replaces the written value. A written frame
 * selects the frame index, unless the image's GRP in memory cannot be read; a written render function selects the draw function it renders
 * ({@link #RENDER_FUNCTIONS}), an unknown render function keeps the image's own.
 * <p>
 * A pointer names the graphics of {@link EmbeddedGraphics}: a GRP for the classic graphics, which may link an HD and an HD2 anim, or an anim for the
 * HD graphics. A pointer the GRP table held when the game's memory was created names the installed graphics of that entry's image, so a copied
 * table entry draws another image's graphics in every graphics.
 * <p>
 * A script's replacement of an image's graphics ({@link ImageReplacements}, found through {@link ImageReplacementSource}) wins over every EUD
 * GRP and anim pointer of the image: the image draws the replacement's GRP and anims, the installed graphics where the replacement has none, and
 * never the graphics its pointer names or another image's installed graphics. Written frames and render functions still apply to it, and a
 * frame or draw function a script set for the image wins over them.
 * <p>
 * The plugin observes the writes through the game's memory view and never changes the game: the simulation keeps the installed GRP's dimensions.
 * A GRP in memory is read at its pointer when it is first drawn and its frames are decoded when they are drawn ({@link EmbeddedGrp}); an anim is
 * read when it is first drawn and parsed off the drawing thread ({@link EmbeddedAnim}), the parsed anims of a game kept within
 * {@code 256} MiB. Graphics that cannot be read draw the installed graphics.
 */
public final class ClassicImageGraphics implements ScrGamePlugin {
    /**
     * The image GRP table: one GRP pointer per {@code images.dat} entry.
     */
    public static final int GRP_TABLE = 0x51CED0;
    /**
     * The number of entries of the image GRP table, the {@code images.dat} entries.
     */
    public static final int GRP_TABLE_ENTRIES = 999;
    /**
     * The image array: one entry per image slot.
     */
    public static final int IMAGE_ARRAY = 0x52F568;
    /**
     * The size of an image array entry.
     */
    public static final int IMAGE_ENTRY_SIZE = 0x40;
    /**
     * The number of image array entries.
     */
    public static final int IMAGE_ENTRIES = 5000;
    /**
     * The offset of the GRP pointer in an image array entry.
     */
    public static final int IMAGE_GRP_OFFSET = 0x2C;
    /**
     * The offset of the frame DWORD in an image array entry: the frame set in the low WORD, the drawn frame index in the high WORD.
     */
    public static final int IMAGE_FRAME_OFFSET = 0x18;
    /**
     * The offset of the render function pointer in an image array entry.
     */
    public static final int IMAGE_RENDER_OFFSET = 0x34;
    /**
     * The unflipped classic render function of the normal draw function.
     */
    public static final int NORMAL_RENDER_FUNCTION = 0x0040ABBE;
    /**
     * The classic render functions with a known draw function: {render function, draw function} rows.
     */
    public static final int @NotNull [] RENDER_FUNCTIONS = {NORMAL_RENDER_FUNCTION, SpriteImage.MODIFIER_NORMAL};

    /**
     * The number of values of one row of {@link #RENDER_FUNCTIONS}.
     */
    private static final int RENDER_ROW_SIZE = 2;
    /**
     * The shift that converts mebibytes to bytes.
     */
    private static final int MEBIBYTE_SHIFT = 20;
    /**
     * The bytes of parsed anims a game keeps.
     */
    static final long ANIMATION_BUDGET = 256L << MEBIBYTE_SHIFT;
    /**
     * The shift of the drawn frame index in the frame DWORD.
     */
    private static final int FRAME_INDEX_SHIFT = 16;

    /**
     * The table writes kept per image type before the history grows.
     */
    private static final int INITIAL_HISTORY = 4;
    /**
     * The factor the table write history of an image type grows by.
     */
    private static final int HISTORY_GROWTH = 2;

    private final @NotNull Supplier<@Nullable SpriteImagePool> images;
    private final @NotNull Supplier<@Nullable ImageReplacements> replacements;

    private @Nullable EmbeddedMemory memory;

    /**
     * Per image type, the generations ({@link SpriteImagePool#getGeneration()}) at the table writes, in write order; a write of the pointer
     * already in effect is not kept.
     */
    private final long @Nullable [] @NotNull [] tableGenerations;
    /**
     * Per image type, the pointers of the table writes, in write order.
     */
    private final int @Nullable [] @NotNull [] tablePointers;
    /**
     * Per image type, the number of kept table writes.
     */
    private final int @NotNull [] tableCounts;
    /**
     * Per image slot, the generation at the last write to the entry's GRP pointer; {@code -1} before the first.
     */
    private final long @NotNull [] slotGenerations;
    /**
     * Per image slot, the GRP pointer written last.
     */
    private final int @NotNull [] slotPointers;
    /**
     * Per image slot, the generation at the last frame write; {@code -1} before the first.
     */
    private final long @NotNull [] frameGenerations;
    /**
     * Per image slot, the frame index written last.
     */
    private final int @NotNull [] frames;
    /**
     * Per image slot, the image's own drawn frame at the last frame write.
     */
    private final int @NotNull [] ownFrames;
    /**
     * Per image slot, the generation at the last write of a known render function; {@code -1} before the first.
     */
    private final long @NotNull [] drawGenerations;
    /**
     * Per image slot, the draw function of the render function written last.
     */
    private final int @NotNull [] drawFunctions;
    /**
     * Per image slot, the image's own draw function at the last render function write.
     */
    private final int @NotNull [] ownDrawFunctions;

    /**
     * The image a pointer of the GRP table names the installed graphics of: the table's values when the game's memory was created.
     */
    private final @NotNull Map<Integer, Integer> installedPointers;
    /**
     * The graphics in memory by pointer.
     */
    private final @NotNull Map<Integer, EmbeddedGraphics> graphics;
    /**
     * The parsed anim sheets of the game.
     */
    private EmbeddedAnim.@NotNull Cache animations;

    /**
     * Creates a {@link ClassicImageGraphics} instance over a game's images.
     *
     * @param images       the game's image pool, {@code null} before the game has one
     * @param replacements the image graphics a script of the game replaces, {@code null} for a game without
     */
    public ClassicImageGraphics(@NotNull Supplier<@Nullable SpriteImagePool> images, @NotNull Supplier<@Nullable ImageReplacements> replacements) {
        this.images       = images;
        this.replacements = replacements;

        this.memory = null;

        this.tableGenerations = new long[GRP_TABLE_ENTRIES][];
        this.tablePointers    = new int[GRP_TABLE_ENTRIES][];
        this.tableCounts      = new int[GRP_TABLE_ENTRIES];
        this.slotGenerations  = new long[IMAGE_ENTRIES];
        this.slotPointers     = new int[IMAGE_ENTRIES];
        this.frameGenerations = new long[IMAGE_ENTRIES];
        this.frames           = new int[IMAGE_ENTRIES];
        this.ownFrames        = new int[IMAGE_ENTRIES];
        this.drawGenerations  = new long[IMAGE_ENTRIES];
        this.drawFunctions    = new int[IMAGE_ENTRIES];
        this.ownDrawFunctions = new int[IMAGE_ENTRIES];

        this.installedPointers = new HashMap<>();
        this.graphics          = new HashMap<>();
        this.animations        = new EmbeddedAnim.Cache(ANIMATION_BUDGET);

        Arrays.fill(this.slotGenerations, -1);
        Arrays.fill(this.frameGenerations, -1);
        Arrays.fill(this.drawGenerations, -1);
    }

    /**
     * Hears that the game's EUD address space was created: reads the installed graphics pointers of the image GRP table, observes the table and the
     * image array through it, and reads the graphics in memory through it from then on.
     *
     * @param memory the read-only view of the address space
     */
    @Override
    public void onAddressSpaceCreated(@NotNull ClassicMemoryView memory) {
        this.memory = EmbeddedMemory.of(memory);

        this.installedPointers.clear();
        this.graphics.clear();
        this.animations = new EmbeddedAnim.Cache(ANIMATION_BUDGET);

        byte[] table = new byte[GRP_TABLE_ENTRIES * Integer.BYTES];
        int count = memory.read(GRP_TABLE, table, 0, table.length) / Integer.BYTES;

        for (int type = 0; type < count; type++) {
            int pointer = LittleEndianUtils.readInt32(table, type * Integer.BYTES);

            if (pointer != 0) {
                this.installedPointers.putIfAbsent(pointer, type);
            }
        }

        memory.observeWrites(GRP_TABLE, GRP_TABLE_ENTRIES * Integer.BYTES, this::onTableWrite);
        memory.observeWrites(IMAGE_ARRAY, IMAGE_ENTRIES * IMAGE_ENTRY_SIZE, this::onImageWrite);
    }

    /**
     * Finds the graphics a sprite image draws instead of its installed GRP: the GRP of its script replacement, else the sheet of the GRP its
     * pointer names.
     *
     * @param image an image of a drawn sprite
     * @return the frames of the GRP the image draws, or {@code null} for its installed graphics
     */
    @Override
    public @Nullable ScrImageSheet findImageSheet(@NotNull SpriteImage image) {
        ImageGraphics replaced = this.findReplacement(image);

        if (replaced != null) {
            return replaced.getGrp();
        }

        EmbeddedGraphics found = this.findGraphics(this.findPointer(image));

        return found == null ? null : found.findGrp();
    }

    /**
     * Finds the HD graphics a sprite image draws instead of its installed anim: the anims of its script replacement, else the anims its pointer
     * names or links.
     *
     * @param image an image of a drawn sprite
     * @return the anims the image draws, or {@code null} for its installed anim
     */
    @Override
    public @Nullable ScrImageAnimation findImageAnimation(@NotNull SpriteImage image) {
        ImageGraphics replaced = this.findReplacement(image);

        if (replaced != null) {
            return replaced.hasAnimation() ? replaced : null;
        }

        EmbeddedGraphics found = this.findGraphics(this.findPointer(image));

        return found != null && found.hasAnimation() ? found : null;
    }

    /**
     * Finds the image type whose installed graphics a sprite image draws instead of its own: the image whose GRP table entry held the image's
     * pointer when the game's memory was created; an image with a script replacement keeps its own.
     *
     * @param image an image of a drawn sprite
     * @return the image type, or {@code -1} for the image's own
     */
    @Override
    public int findImageTypeId(@NotNull SpriteImage image) {
        if (this.findReplacement(image) != null) {
            return -1;
        }

        Integer type = this.installedPointers.get(this.findPointer(image));

        return type == null ? -1 : type;
    }

    /**
     * Finds the frame a sprite image draws instead of its own: the frame index written to its image array entry while it held the entry, until its
     * own frame changes, in the graphics the image draws; an image whose graphics in memory cannot be read draws its own frame, and an image with a
     * script replacement draws the written frame in the replacement. A frame a script set for the image ({@link ImageReplacements#findImageFrame})
     * wins over the written one.
     *
     * @param image an image of a drawn sprite
     * @return the frame index, or {@code -1} for the image's own frame
     */
    @Override
    public int findImageFrame(@NotNull SpriteImage image) {
        ImageReplacements current = this.replacements.get();
        int scriptFrame = current == null ? -1 : current.findImageFrame(image);

        if (scriptFrame >= 0) {
            return scriptFrame;
        }

        int slot = image.getIndex();

        if (slot < 0 || slot >= IMAGE_ENTRIES || this.frameGenerations[slot] < image.getPool().getGeneration(slot) ||
            this.ownFrames[slot] != image.getDrawnFrameIndex()) {
            return -1;
        }

        int pointer = this.findPointer(image);

        if (pointer != 0 && !this.installedPointers.containsKey(pointer) && this.findReplacement(image) == null) {
            EmbeddedGraphics found = this.findGraphics(pointer);

            if (found == null || !found.isReadable()) {
                return -1;
            }
        }

        return this.frames[slot];
    }

    /**
     * Finds the draw function a sprite image draws with instead of its own: the draw function of the render function written to its image array
     * entry while it held the entry, until its own draw function changes. A draw function a script set for the image
     * ({@link ImageReplacements#findImageDrawFunction}) wins over the written one.
     *
     * @param image an image of a drawn sprite
     * @return the draw function, or {@code -1} for the image's own
     */
    @Override
    public int findImageDrawFunction(@NotNull SpriteImage image) {
        ImageReplacements current = this.replacements.get();
        int scriptDrawFunction = current == null ? -1 : current.findImageDrawFunction(image);

        if (scriptDrawFunction >= 0) {
            return scriptDrawFunction;
        }

        int slot = image.getIndex();

        if (slot < 0 || slot >= IMAGE_ENTRIES || this.drawGenerations[slot] < image.getPool().getGeneration(slot) ||
            this.ownDrawFunctions[slot] != image.getModifier()) {
            return -1;
        }

        return this.drawFunctions[slot];
    }

    /**
     * Finds the script replacement of an image's graphics.
     *
     * @param image an image
     * @return the replacement, or {@code null} if the game has no script replacements or none for the image
     */
    private @Nullable ImageGraphics findReplacement(@NotNull SpriteImage image) {
        ImageReplacements current = this.replacements.get();

        return current == null ? null : current.findGraphics(image);
    }

    /**
     * Finds the graphics in memory a pointer names.
     *
     * @param pointer the pointer
     * @return the graphics, or {@code null} for pointer {@code 0}, an installed graphics pointer or before the address space exists
     */
    private @Nullable EmbeddedGraphics findGraphics(int pointer) {
        EmbeddedMemory view = this.memory;

        if (view == null || pointer == 0 || this.installedPointers.containsKey(pointer)) {
            return null;
        }

        return this.graphics.computeIfAbsent(pointer, key -> new EmbeddedGraphics(view, key, this.animations));
    }

    /**
     * Finds the GRP pointer of an image by the classic rule.
     *
     * @param image an image
     * @return the pointer, {@code 0} for the installed graphics
     */
    private int findPointer(@NotNull SpriteImage image) {
        int slot = image.getIndex();
        long generation = image.getPool().getGeneration(slot);

        if (slot >= 0 && slot < IMAGE_ENTRIES && this.slotGenerations[slot] >= generation) {
            return this.slotPointers[slot];
        }

        int type = image.getImageTypeId();

        if (type < 0 || type >= GRP_TABLE_ENTRIES) {
            return 0;
        }

        long[] generations = this.tableGenerations[type];
        int[] pointers = this.tablePointers[type];

        if (generations == null || pointers == null) {
            return 0;
        }

        for (int index = this.tableCounts[type] - 1; index >= 0; index--) {
            if (generations[index] < generation) {
                return pointers[index];
            }
        }

        return 0;
    }

    private long getGeneration() {
        SpriteImagePool pool = this.images.get();

        return pool == null ? 0 : pool.getGeneration();
    }

    private void onTableWrite(int address, int value) {
        int type = (address - GRP_TABLE) / Integer.BYTES;
        int count = this.tableCounts[type];
        long[] generations = this.tableGenerations[type];
        int[] pointers = this.tablePointers[type];

        if (generations == null || pointers == null) {
            generations = new long[INITIAL_HISTORY];
            pointers    = new int[INITIAL_HISTORY];
        } else if (pointers[count - 1] == value) {
            return;
        } else if (count == pointers.length) {
            generations = Arrays.copyOf(generations, count * HISTORY_GROWTH);
            pointers    = Arrays.copyOf(pointers, count * HISTORY_GROWTH);
        }

        generations[count] = this.getGeneration();
        pointers[count]    = value;

        this.tableGenerations[type] = generations;
        this.tablePointers[type]    = pointers;
        this.tableCounts[type]      = count + 1;
    }

    private void onImageWrite(int address, int value) {
        int offset = address - IMAGE_ARRAY;
        int slot = offset / IMAGE_ENTRY_SIZE;

        switch (offset % IMAGE_ENTRY_SIZE) {
            case IMAGE_GRP_OFFSET -> {
                this.slotGenerations[slot] = this.getGeneration();
                this.slotPointers[slot]    = value;
            }
            case IMAGE_FRAME_OFFSET -> {
                SpriteImage image = this.findImage(slot);

                if (image != null) {
                    this.frameGenerations[slot] = this.getGeneration();
                    this.frames[slot]           = value >>> FRAME_INDEX_SHIFT;
                    this.ownFrames[slot]        = image.getDrawnFrameIndex();
                }
            }
            case IMAGE_RENDER_OFFSET -> {
                SpriteImage image = this.findImage(slot);
                int drawFunction = findDrawFunction(value);

                if (image != null && drawFunction >= 0) {
                    this.drawGenerations[slot]  = this.getGeneration();
                    this.drawFunctions[slot]    = drawFunction;
                    this.ownDrawFunctions[slot] = image.getModifier();
                }
            }
            default -> {
            }
        }
    }

    private @Nullable SpriteImage findImage(int slot) {
        SpriteImagePool pool = this.images.get();

        return pool == null || slot >= pool.getCapacity() || !pool.isAllocated(slot) ? null : pool.getImage(slot);
    }

    private static int findDrawFunction(int renderFunction) {
        for (int index = 0; index < RENDER_FUNCTIONS.length; index += RENDER_ROW_SIZE) {
            if (RENDER_FUNCTIONS[index] == renderFunction) {
                return RENDER_FUNCTIONS[index + 1];
            }
        }

        return -1;
    }
}
