package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TextureSheet;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageAnimation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The graphics a pointer in game memory names. Bytes that start with the anim header are an anim ({@link EmbeddedAnim}), drawn in the HD graphics
 * of its scale and the other one; the classic graphics keep the installed graphics. Other bytes that are a GRP header with a readable frame table
 * are a GRP ({@link EmbeddedGrp}) drawn in the classic graphics; the {@value #LINK_SIZE} bytes right before it may link anims: {@code HDAN}, then
 * the offsets of the HD and the HD2 anim from the GRP ({@code 0} for none). Writes to the anim header magic and to the link read them again.
 * <p>
 * The HD graphics draw the HD anim, else the HD2 anim; the HD2 graphics draw the HD2 anim, else the HD anim.
 */
final class EmbeddedGraphics implements ScrImageAnimation {
    /**
     * The link magic {@code HDAN}.
     */
    static final int LINK_MAGIC = 0x4E414448;
    /**
     * The size of the link before a GRP: the magic and two offsets.
     */
    static final int LINK_SIZE = 12;

    private static final int HD_OFFSET = 4;
    private static final int HD2_OFFSET = 8;
    /**
     * The bytes read at the pointer: the anim header magic and its asset scale.
     */
    private static final int HEAD_SIZE = 8;
    private static final int BYTE_MASK = 0xFF;

    private final @NotNull EmbeddedMemory memory;
    private final int pointer;
    private final EmbeddedAnim.@NotNull Cache cache;

    private final @NotNull EmbeddedGrp grp;
    /**
     * The anims by address, kept while the link or the header names other anims.
     */
    private final @NotNull Map<Integer, EmbeddedAnim> anims;

    /**
     * Whether the link or the anim header magic was written since they were read.
     */
    private boolean changed;
    /**
     * Whether the pointer names an anim header.
     */
    private boolean anim;
    private @Nullable EmbeddedAnim hdAnim;
    private @Nullable EmbeddedAnim hd2Anim;

    /**
     * Creates an {@link EmbeddedGraphics} instance for a pointer, read on first use.
     *
     * @param memory  the memory the bytes are read and observed through
     * @param pointer the address the pointer names
     * @param cache   the parsed anim sheets of the game
     */
    EmbeddedGraphics(@NotNull EmbeddedMemory memory, int pointer, EmbeddedAnim.@NotNull Cache cache) {
        this.memory  = memory;
        this.pointer = pointer;
        this.cache   = cache;

        this.grp   = new EmbeddedGrp(memory, pointer);
        this.anims = new HashMap<>();

        this.changed = true;
        this.anim    = false;
        this.hdAnim  = null;
        this.hd2Anim = null;

        memory.observeWrites(pointer - LINK_SIZE, LINK_SIZE + Integer.BYTES, (address, value) -> this.changed = true);
    }

    /**
     * Finds the GRP the pointer names.
     *
     * @return the GRP, or {@code null} if the bytes at the pointer are an anim or no readable GRP
     */
    @Nullable EmbeddedGrp findGrp() {
        this.detect();

        return !this.anim && this.grp.isGrp() ? this.grp : null;
    }

    /**
     * @return {@code true} if the pointer names an anim or links one, {@code false} otherwise
     */
    boolean hasAnimation() {
        this.detect();

        return this.hdAnim != null || this.hd2Anim != null;
    }

    /**
     * @return {@code true} if the pointer names a readable GRP or an anim, or links an anim, {@code false} otherwise
     */
    boolean isReadable() {
        return this.hasAnimation() || this.findGrp() != null;
    }

    /**
     * Finds the texture sheet drawn at an asset scale: the anim of that scale, else the anim of the other scale. A sheet that is not prepared yet
     * starts its preparation and is not returned until it is ready; the sheet returned last stays the result meanwhile. The same instance is
     * returned while the anim's bytes are unchanged, so what a renderer makes of a sheet can be kept under that instance.
     *
     * @param scale the asset scale drawn: {@code 4} for HD, {@code 2} for HD2
     * @return the texture sheet, or {@code null} if no anim is ready
     */
    @Override
    public @Nullable TextureSheet findSheet(int scale) {
        this.detect();

        return EmbeddedAnim.findSheet(scale, this.hdAnim, this.hd2Anim);
    }

    /**
     * Reads the anim header magic and the link again after a write to them.
     */
    private void detect() {
        if (!this.changed) {
            return;
        }

        this.changed = false;
        this.hdAnim  = null;
        this.hd2Anim = null;

        byte[] head = new byte[HEAD_SIZE];

        this.anim = this.memory.read(this.pointer, head, 0, HEAD_SIZE) >= Integer.BYTES && LittleEndianUtils.readInt32(head, 0) == EmbeddedAnim.MAGIC;

        if (this.anim) {
            if ((head[EmbeddedAnim.SCALE_OFFSET] & BYTE_MASK) == EmbeddedAnim.HD2_SCALE) {
                this.hd2Anim = this.findAnim(this.pointer);
            } else {
                this.hdAnim = this.findAnim(this.pointer);
            }

            return;
        }

        byte[] link = new byte[LINK_SIZE];

        if (this.memory.read(this.pointer - LINK_SIZE, link, 0, LINK_SIZE) == LINK_SIZE && LittleEndianUtils.readInt32(link, 0) == LINK_MAGIC) {
            int hdOffset = LittleEndianUtils.readInt32(link, HD_OFFSET);
            int hd2Offset = LittleEndianUtils.readInt32(link, HD2_OFFSET);

            this.hdAnim  = hdOffset == 0 ? null : this.findAnim(this.pointer + hdOffset);
            this.hd2Anim = hd2Offset == 0 ? null : this.findAnim(this.pointer + hd2Offset);
        }
    }

    private @NotNull EmbeddedAnim findAnim(int address) {
        return this.anims.computeIfAbsent(address, key -> new EmbeddedAnim(this.memory, key, this.cache));
    }
}
