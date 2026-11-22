package org.fogofwarlabs.sc4j.scr.game.sprite;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.animation.system.AnimationState;
import org.fogofwarlabs.sc4j.engine.entity.Image;
import org.fogofwarlabs.sc4j.scr.content.ScrNativeIds;
import org.fogofwarlabs.sc4j.scr.data.format.image.GrpFile;
import org.fogofwarlabs.sc4j.scr.data.format.iscript.IscriptHeader;
import org.fogofwarlabs.sc4j.scr.game.save.SaveFileReader;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

/**
 * Represents an image of a sprite with its iscript execution state.
 * <p>
 * Flags and interpreter/frame words use their stored widths. Position offsets are signed bytes; direction/frame offset, animation and wait are
 * unsigned bytes. Pool storage retains object identity after recycling.
 * <p>
 * The running script of the image lives in the {@link AnimationState} of its pool at the image's pool index: the animation is the kind index,
 * the program counter the position (converted through the pool's offset map, {@link SpriteImagePool#getOffset}), the return address call stack
 * entry {@code 0} (converted the same way) and the wait the wait timer. The script identifier stays here, because it is image data that a
 * running program does not follow (the warp texture runs the Init script of another identifier).
 * <p>
 * The image is an engine {@link Image} of the SC:R image pool: its engine index is the pool index, the engine frame index is the frame index
 * base (the animation frame; the direction offset selects the drawn frame, {@link #getDrawnFrameIndex()}) and the engine offset is the signed
 * byte offset. The native {@code images.dat} ID stays the stored value (EUD and saves read it); every write of it also sets the engine image
 * type ({@code null} for a free slot or an ID outside {@code images.dat}).
 */
public final class SpriteImage extends Image {
    /**
     * The image flag requesting a redraw.
     */
    public static final int FLAG_REDRAW = 0x01;
    /**
     * The image flag of a horizontally flipped frame.
     */
    public static final int FLAG_HORIZONTALLY_FLIPPED = 0x02;
    /**
     * The image flag keeping the vertical offset.
     */
    public static final int FLAG_Y_FROZEN = 0x04;
    /**
     * The image flag of an image with directional frames.
     */
    public static final int FLAG_HAS_DIRECTIONAL_FRAMES = 0x08;
    /**
     * The image flag of an image running the full iscript.
     */
    public static final int FLAG_USES_FULL_ISCRIPT = 0x10;
    /**
     * The image flag of a clickable image.
     */
    public static final int FLAG_CLICKABLE = 0x20;
    /**
     * The image flag of a hidden image.
     */
    public static final int FLAG_HIDDEN = 0x40;
    /**
     * The image flag of an image using the special overlay offset.
     */
    public static final int FLAG_USES_SPECIAL_OFFSET = 0x80;
    /**
     * The size of a saved image record, the classic {@code 64}-byte image ({@link #writeSaveRecord}).
     */
    public static final int SAVE_RECORD_SIZE = 0x40;
    /**
     * The normal drawing function: the frame in its own colors.
     */
    public static final int MODIFIER_NORMAL = 0;
    /**
     * The first of the cloak drawing functions (cloaking, cloaked and decloaking, unseen and seen: {@code 2..7}).
     */
    public static final int MODIFIER_FIRST_CLOAK = 2;
    /**
     * The last of the cloak drawing functions.
     */
    public static final int MODIFIER_LAST_CLOAK = 7;
    /**
     * The cloak drawing function of an image fading into its cloak, unseen ({@link #MODIFIER_FIRST_CLOAK}).
     */
    public static final int MODIFIER_CLOAKING = 2;
    /**
     * The cloak drawing function of a fully cloaked image, unseen.
     */
    public static final int MODIFIER_CLOAKED = 3;
    /**
     * The cloak drawing function of an image fading out of its cloak, unseen.
     */
    public static final int MODIFIER_DECLOAKING = 4;
    /**
     * The cloak drawing function of an image fading into its cloak, seen.
     */
    public static final int MODIFIER_DETECTED_CLOAKING = 5;
    /**
     * The cloak drawing function of a fully cloaked image, seen.
     */
    public static final int MODIFIER_DETECTED_CLOAKED = 6;
    /**
     * The cloak drawing function of an image fading out of its cloak, seen ({@link #MODIFIER_LAST_CLOAK}).
     */
    public static final int MODIFIER_DETECTED_DECLOAKING = 7;
    /**
     * The drawing function that remaps colors through one of the eight remapping tables.
     */
    public static final int MODIFIER_REMAPPING = 9;
    /**
     * The drawing function of a shadow image, which follows its sprite at a vertical offset.
     */
    public static final int MODIFIER_SHADOW = 10;
    /**
     * The drawing function of the health bar image, which a selected unit redraws when its shields, energy or hit points change.
     */
    public static final int MODIFIER_HEALTH_BAR = 11;
    /**
     * The drawing function of the building warp texture.
     */
    public static final int MODIFIER_WARP_TEXTURE = 12;
    /**
     * The drawing function of a selection circle image, solid or dashed, colored by the relation kept in the modifier data.
     */
    public static final int MODIFIER_SELECTION_CIRCLE = 13;
    /**
     * The drawing function that draws an image in its owner's player color, kept in the modifier data.
     */
    public static final int MODIFIER_PLAYER_COLOR = 14;
    /**
     * The drawing function of a hallucination image, shown to the hallucination's owner and to replay or observer views.
     */
    public static final int MODIFIER_HALLUCINATION = 16;
    /**
     * The drawing function of the warp-in flash, whose modifier data holds a delay byte and the brightness.
     */
    public static final int MODIFIER_WARP_FLASH = 17;
    /**
     * The initial modifier data of the warp-in flash: delay {@code 2}, brightness {@code 0x30}.
     */
    public static final int WARP_FLASH_START_DATA = 0x0230;

    /**
     * The last of the eight color remapping table indexes ({@link #setColorRemappingIndex(int)}).
     */
    private static final int MAXIMUM_REMAPPING_INDEX = 7;
    /**
     * The mask of a WORD field value.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The mask of a BYTE field value.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The value a saved record adds to the GRP table entry of an image with graphics, so that {@code 0} stays the value of an image without them.
     */
    private static final int SAVED_GRAPHICS_ENTRY_BIAS = 1;
    /**
     * The divisor of the frame height that gives the distance from the frame's top to its vertical center.
     */
    private static final int FRAME_CENTER_DIVISOR = 2;
    /**
     * The call stack entry of the animation state that holds the return address.
     */
    private static final int RETURN_ENTRY = 0;

    private static final int RECORD_IMAGE_TYPE = 0x08;
    private static final int RECORD_MODIFIER = 0x0A;
    private static final int RECORD_FRAME_INDEX_OFFSET = 0x0B;
    private static final int RECORD_FLAGS = 0x0C;
    private static final int RECORD_OFFSET_X = 0x0E;
    private static final int RECORD_OFFSET_Y = 0x0F;
    private static final int RECORD_SCRIPT_HEADER = 0x10;
    private static final int RECORD_PROGRAM_COUNTER = 0x12;
    private static final int RECORD_RETURN_ADDRESS = 0x14;
    private static final int RECORD_ANIMATION = 0x16;
    private static final int RECORD_WAIT = 0x17;
    private static final int RECORD_FRAME_INDEX_BASE = 0x18;
    private static final int RECORD_FRAME_INDEX = 0x1A;
    private static final int RECORD_GRAPHICS = 0x2C;
    private static final int RECORD_COLORING = 0x30;
    private static final int RECORD_MODIFIER_DATA = 0x34;

    /**
     * Returns the SC:R image of an engine image.
     *
     * @param image the engine image
     * @return the image as the SC:R image it is
     * @throws IllegalArgumentException if the image is not an SC:R image
     */
    public static @NotNull SpriteImage of(@NotNull Image image) {
        if (!(image instanceof SpriteImage scrImage)) {
            throw Exceptions.newIllegalArgumentException("Not an SC:R image: {0}", image.getIndex());
        }

        return scrImage;
    }


    private final @NotNull SpriteImagePool pool;
    private final @NotNull AnimationState animations;
    private @Nullable Sprite sprite;
    private int imageTypeId;
    private @Nullable GrpFile graphics;

    private int frameIndexOffset;

    private int flags;

    private int modifier;
    private int modifierData1;
    private int modifierData2;
    private int colorRemappingIndex;

    private int scriptIdentifier;
    private int frozenMapY;

    /**
     * Creates a {@link SpriteImage} instance for one zeroed pool slot without a sprite.
     *
     * @param pool      the owning pool, whose animation state holds the script state of the image
     * @param poolIndex the persistent storage index the owning pool assigns
     */
    SpriteImage(@NotNull SpriteImagePool pool, int poolIndex) {
        super(poolIndex);

        this.pool        = pool;
        this.animations  = pool.getAnimationState();
        this.sprite      = null;
        this.imageTypeId = 0;
        this.graphics    = null;

        this.frameIndexOffset = 0;

        this.flags = 0;

        this.modifier            = 0;
        this.modifierData1       = 0;
        this.modifierData2       = 0;
        this.colorRemappingIndex = -1;

        this.scriptIdentifier = 0;
        this.frozenMapY       = 0;

        this.clearScriptState();
    }

    /**
     * @return the owning pool
     */
    public @NotNull SpriteImagePool getPool() {
        return this.pool;
    }

    /**
     * @return the owning sprite, or {@code null} when detached
     */
    public @Nullable Sprite getSprite() {
        return this.sprite;
    }

    /**
     * Returns the sprite of an attached image.
     *
     * @return the owning sprite
     * @throws IllegalStateException if the image is detached
     */
    public @NotNull Sprite requireSprite() {
        Sprite sprite = this.sprite;

        if (sprite == null) {
            throw Exceptions.newIllegalStateException("Image {0} has no sprite.", this.getIndex());
        }

        return sprite;
    }

    /**
     * @return the {@code images.dat} ID
     */
    public int getImageTypeId() {
        return this.imageTypeId;
    }

    /**
     * @return {@code true} if creation supplied graphics that have not been cleared by recycling, {@code false} otherwise
     */
    public boolean hasGraphics() {
        return this.graphics != null;
    }

    /**
     * @return the frame graphics, or {@code null} after recycling cleared them
     */
    public @Nullable GrpFile getGraphics() {
        return this.graphics;
    }

    /**
     * @return the drawn frame index: {@code (getFrameIndex() + frameIndexOffset) & 0xffff}
     */
    public int getDrawnFrameIndex() {
        return (this.getFrameIndex() + this.frameIndexOffset) & WORD_MASK;
    }

    /**
     * @return the frame index offset
     */
    public int getFrameIndexOffset() {
        return this.frameIndexOffset;
    }

    /**
     * @return the flags
     */
    public int getFlags() {
        return this.flags;
    }

    /**
     * @return the modifier
     */
    public int getModifier() {
        return this.modifier;
    }

    /**
     * @return the first modifier data
     */
    public int getModifierData1() {
        return this.modifierData1;
    }

    /**
     * @return the second modifier data
     */
    public int getModifierData2() {
        return this.modifierData2;
    }

    /**
     * @return the color remapping table index, {@code 0} for its null entry, or {@code -1} when unused
     */
    public int getColorRemappingIndex() {
        return this.colorRemappingIndex;
    }

    /**
     * @return the animation type
     */
    public int getAnimationType() {
        return this.animations.getKindIndex(this.getIndex());
    }

    /**
     * @return the script identifier resolved for image storage, retained after recycling
     */
    public int getScriptIdentifier() {
        return this.scriptIdentifier;
    }

    /**
     * @return the program counter as an absolute iscript file offset
     */
    public int getProgramCounter() {
        return this.pool.getOffset(this.animations.getPosition(this.getIndex()));
    }

    /**
     * @return the return address
     */
    public int getReturnAddress() {
        return this.pool.getOffset(this.animations.getCallEntry(this.getIndex(), RETURN_ENTRY));
    }

    /**
     * @return the unsigned byte wait timer from {@code 0} through {@code 255}
     */
    public int getWait() {
        return this.animations.getWait(this.getIndex());
    }

    /**
     * @return the frozen top map coordinate of the current frame rectangle, used while {@link #FLAG_Y_FROZEN} is set
     */
    public int getFrozenMapY() {
        return this.frozenMapY;
    }


    /**
     * Sets the owning sprite; only the sprite attaches and detaches its images.
     *
     * @param sprite the owning sprite, or {@code null} to detach the image
     */
    void setSprite(@Nullable Sprite sprite) {
        this.sprite = sprite;
    }

    /**
     * Sets the frame graphics; recycling clears them, which marks the slot free when a saved game rebuilds the free list.
     *
     * @param graphics the frame graphics, or {@code null}
     */
    public void setGraphics(@Nullable GrpFile graphics) {
        this.graphics = graphics;
    }

    /**
     * Sets the frame index.
     * <p>
     * The SC:R frame index is the frame index base, truncated to an unsigned WORD; the direction offset is unchanged.
     *
     * @param frameIndex the frame index
     */
    @Override
    public void setFrameIndex(int frameIndex) {
        super.setFrameIndex(frameIndex & WORD_MASK);
    }

    /**
     * Sets the frame index offset with unsigned byte truncation.
     *
     * @param frameIndexOffset the new frame index offset
     */
    public void setFrameIndexOffset(int frameIndexOffset) {
        this.frameIndexOffset = frameIndexOffset & BYTE_MASK;
    }

    /**
     * Sets the x offset with signed byte truncation.
     *
     * @param offsetX the new x offset in pixels
     */
    public void setOffsetX(int offsetX) {
        this.setOffset(offsetX, this.getOffsetY());
    }

    /**
     * Sets the y offset with signed byte truncation.
     *
     * @param offsetY the new y offset in pixels
     */
    public void setOffsetY(int offsetY) {
        this.setOffset(this.getOffsetX(), offsetY);
    }

    /**
     * Sets the offset from the sprite.
     * <p>
     * Both SC:R offsets are truncated to signed bytes.
     *
     * @param offsetX the horizontal offset in pixels
     * @param offsetY the vertical offset in pixels
     */
    @Override
    public void setOffset(int offsetX, int offsetY) {
        super.setOffset((byte) offsetX, (byte) offsetY);
    }

    /**
     * Sets the flags with unsigned word truncation.
     *
     * @param flags the new flags
     */
    public void setFlags(int flags) {
        this.flags = flags & WORD_MASK;
    }

    /**
     * Sets the modifier.
     *
     * @param modifier the new modifier
     */
    public void setModifier(int modifier) {
        this.modifier = modifier;
    }

    /**
     * Sets the first modifier data.
     *
     * @param modifierData1 the new first modifier data
     */
    public void setModifierData1(int modifierData1) {
        this.modifierData1 = modifierData1;
    }

    /**
     * Sets the color remapping table index of an image drawn with the remapping function.
     *
     * @param index the remapping table index from {@code 0} through {@code 7}
     * @throws UnsupportedOperationException if the index names no established remapping table
     */
    public void setColorRemappingIndex(int index) {
        if (index < 0 || index > MAXIMUM_REMAPPING_INDEX) {
            throw Exceptions.newUnsupportedOperationException("Native color remapping references an unestablished resource table entry: {0}", index);
        }

        this.colorRemappingIndex = index;
    }

    /**
     * Sets the animation type with unsigned byte truncation.
     *
     * @param animationType the new animation type
     */
    public void setAnimationType(int animationType) {
        this.animations.setKindIndex(this.getIndex(), animationType & BYTE_MASK);
    }

    /**
     * Sets the iscript identifier the image runs.
     *
     * @param scriptIdentifier the iscript identifier
     */
    public void setScriptIdentifier(int scriptIdentifier) {
        this.scriptIdentifier = scriptIdentifier;
    }

    /**
     * Sets the program counter with unsigned word truncation.
     *
     * @param programCounter the new program counter as an absolute iscript file offset
     */
    public void setProgramCounter(int programCounter) {
        this.animations.setPosition(this.getIndex(), this.pool.getPosition(programCounter & WORD_MASK));
    }

    /**
     * Sets the return address with unsigned word truncation.
     *
     * @param returnAddress the new return address
     */
    public void setReturnAddress(int returnAddress) {
        this.animations.setCallEntry(this.getIndex(), RETURN_ENTRY, this.pool.getPosition(returnAddress & WORD_MASK));
    }

    /**
     * Sets the wait timer with unsigned byte truncation.
     *
     * @param wait the new wait timer
     */
    public void setWait(int wait) {
        this.animations.setWait(this.getIndex(), wait & BYTE_MASK);
    }


    /**
     * Shows a frame: sets the frame index base and requests a redraw, unless the base already is that frame.
     *
     * @param frameIndex the frame index base
     */
    public void showFrame(int frameIndex) {
        if (this.getFrameIndex() == frameIndex) {
            return;
        }

        this.setFrameIndex(frameIndex);
        this.flags |= FLAG_REDRAW;
    }

    /**
     * Initializes the image fields of a freshly allocated slot: the type, offsets, flags and drawing function, with a zero frame, no modifier data,
     * no remapping table and a cleared script state. The sprite, graphics and frozen coordinate keep their values.
     *
     * @param imageTypeId the {@code images.dat} index, truncated to WORD
     * @param offsetX     the x offset in pixels, truncated to a signed byte
     * @param offsetY     the y offset in pixels, truncated to a signed byte
     * @param flags       the image flags, truncated to WORD
     * @param modifier    the drawing function, truncated to BYTE
     */
    public void initializeNativeStorage(int imageTypeId, int offsetX, int offsetY, int flags, int modifier) {
        this.setImageTypeId(imageTypeId);
        this.setFrameIndex(0);
        this.setOffset(offsetX, offsetY);

        this.frameIndexOffset    = 0;
        this.flags               = flags & WORD_MASK;
        this.modifier            = modifier & BYTE_MASK;
        this.modifierData1       = 0;
        this.modifierData2       = 0;
        this.colorRemappingIndex = -1;
        this.scriptIdentifier    = 0;

        this.clearScriptState();
    }

    /**
     * Freezes the current frame rectangle's top map coordinate.
     *
     * @throws IllegalStateException if the image has no sprite or no graphics
     */
    public void freezeMapY() {
        Sprite owner = this.sprite;
        GrpFile frames = this.graphics;

        if (owner == null || frames == null) {
            throw Exceptions.newIllegalStateException("Freezing an image position requires its sprite and graphics.");
        }

        this.frozenMapY = owner.getY() + this.getOffsetY() + frames.getFrameYOffset(this.getDrawnFrameIndex()) -
                          frames.getHeight() / FRAME_CENTER_DIVISOR;
        this.flags |= FLAG_Y_FROZEN;
    }

    /**
     * Restores sprite-relative vertical placement.
     */
    public void unfreezeMapY() {
        this.flags &= ~FLAG_Y_FROZEN;
    }

    /**
     * Writes the image's fields into its saved record, the classic {@code 64}-byte image of the image pool section. The list links and the sprite
     * belong to the pool section; the map and screen positions, the frame bounds and two further DWORDs are not written here.
     *
     * @param record   the record, little-endian, at offset {@code 0}
     * @param gameData the game data, which gives the iscript header offset and the GRP table entry
     */
    public void writeSaveRecord(@NotNull ByteBuffer record, @NotNull GameData gameData) {
        IscriptHeader header = gameData.getIscript().findHeader(this.scriptIdentifier);

        record.putShort(RECORD_IMAGE_TYPE, (short) this.imageTypeId);
        record.put(RECORD_MODIFIER, (byte) this.modifier);
        record.put(RECORD_FRAME_INDEX_OFFSET, (byte) this.frameIndexOffset);
        // Saves the image flags without the redraw request bit.
        record.putShort(RECORD_FLAGS, (short) (this.flags & ~FLAG_REDRAW));
        record.put(RECORD_OFFSET_X, (byte) this.getOffsetX());
        record.put(RECORD_OFFSET_Y, (byte) this.getOffsetY());

        record.putShort(RECORD_SCRIPT_HEADER, (short) (header == null ? 0 : header.getOffset()));
        record.putShort(RECORD_PROGRAM_COUNTER, (short) this.getProgramCounter());
        record.putShort(RECORD_RETURN_ADDRESS, (short) this.getReturnAddress());
        record.put(RECORD_ANIMATION, (byte) this.getAnimationType());
        record.put(RECORD_WAIT, (byte) this.getWait());

        record.putShort(RECORD_FRAME_INDEX_BASE, (short) this.getFrameIndex());
        record.putShort(RECORD_FRAME_INDEX, (short) this.getDrawnFrameIndex());

        record.putInt(RECORD_GRAPHICS, this.graphics == null ? 0 : gameData.getImageGraphicsEntry(this.imageTypeId) + SAVED_GRAPHICS_ENTRY_BIAS);
        record.putInt(RECORD_COLORING, this.modifier == MODIFIER_REMAPPING ? this.colorRemappingIndex : this.modifierData1);
        record.putInt(RECORD_MODIFIER_DATA, this.modifierData2);
    }

    /**
     * Reads the image's fields from its saved record ({@link #writeSaveRecord}) into a cleared slot.
     *
     * @param record   the record, little-endian, at offset {@code 0}
     * @param gameData the game data, which resolves the iscript header and the graphics
     * @throws UncheckedIOException if the record names no iscript header or the graphics cannot be read
     */
    public void readSaveRecord(@NotNull ByteBuffer record, @NotNull GameData gameData) {
        int scriptHeaderOffset = record.getShort(RECORD_SCRIPT_HEADER) & WORD_MASK;
        IscriptHeader header = gameData.getIscript().findHeaderAt(scriptHeaderOffset);

        if (header == null) {
            throw SaveFileReader.createFailure("A saved image names no iscript header at {0}.", scriptHeaderOffset);
        }

        this.setImageTypeId(record.getShort(RECORD_IMAGE_TYPE));
        this.modifier         = record.get(RECORD_MODIFIER) & BYTE_MASK;
        this.frameIndexOffset = record.get(RECORD_FRAME_INDEX_OFFSET) & BYTE_MASK;
        this.flags            = record.getShort(RECORD_FLAGS) & WORD_MASK;
        this.setOffset(record.get(RECORD_OFFSET_X), record.get(RECORD_OFFSET_Y));

        this.scriptIdentifier = header.getId();
        this.setProgramCounter(record.getShort(RECORD_PROGRAM_COUNTER));
        this.setReturnAddress(record.getShort(RECORD_RETURN_ADDRESS));
        this.setAnimationType(record.get(RECORD_ANIMATION));
        this.setWait(record.get(RECORD_WAIT));

        this.setFrameIndex(record.getShort(RECORD_FRAME_INDEX_BASE));

        int coloring = record.getInt(RECORD_COLORING);

        this.colorRemappingIndex = this.modifier == MODIFIER_REMAPPING ? coloring : -1;
        this.modifierData1       = this.modifier == MODIFIER_REMAPPING ? 0 : coloring;
        this.modifierData2       = record.getInt(RECORD_MODIFIER_DATA);

        try {
            this.graphics = record.getInt(RECORD_GRAPHICS) == 0 ? null : gameData.getGrpFile(this.imageTypeId);
        } catch (IOException exception) {
            throw Exceptions.newUncheckedIOException(exception);
        }
    }

    /**
     * Clears the stored image fields.
     */
    void clearStorage() {
        this.sprite      = null;
        this.imageTypeId = 0;
        this.graphics    = null;
        this.setType(null);

        this.setFrameIndex(0);
        this.frameIndexOffset = 0;

        this.setOffset(0, 0);
        this.flags = 0;

        this.modifier            = 0;
        this.modifierData1       = 0;
        this.modifierData2       = 0;
        this.colorRemappingIndex = -1;

        this.scriptIdentifier = 0;
        this.frozenMapY       = 0;

        this.clearScriptState();
    }

    /**
     * Sets the {@code images.dat} ID, truncated to WORD, and the engine image type of it.
     *
     * @param imageTypeId the {@code images.dat} ID
     */
    private void setImageTypeId(int imageTypeId) {
        this.imageTypeId = imageTypeId & WORD_MASK;
        this.setType(ScrNativeIds.imageOf(this.imageTypeId));
    }

    /**
     * Clears the script state of the image in its pool's animation state: animation, program counter, return address and wait.
     */
    private void clearScriptState() {
        this.setAnimationType(0);
        this.setProgramCounter(0);
        this.setReturnAddress(0);
        this.setWait(0);
    }
}
