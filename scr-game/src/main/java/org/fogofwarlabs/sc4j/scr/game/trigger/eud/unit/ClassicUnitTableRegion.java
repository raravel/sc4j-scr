package org.fogofwarlabs.sc4j.scr.game.trigger.eud.unit;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerScores;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpritePool;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerModifiers;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAccessFailure;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicRegion;
import org.fogofwarlabs.sc4j.scr.game.unit.NativeUnitFields;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Translates the classic unit table to {@link Unit} fields, pointers and kind-dependent union members.
 * Classic slots use one-based unit pool indices. Invalid pointer values remain in per-slot shadows; zero and exact
 * entry addresses update the represented pointer and clear its shadow. Unsupported fields stop trigger access.
 * The timer pair write also updates the following DWORD after applying its modifier a second time.
 */
public final class ClassicUnitTableRegion implements ClassicRegion {
    /**
     * The mask that reads a DWORD as an unsigned value.
     */
    private static final long DWORD_MASK = 0xFFFFFFFFL;

    /**
     * The classic address of the unit table.
     */
    public static final int BASE = 0x59CCA8;
    /**
     * The classic entry size.
     */
    public static final int ENTRY_SIZE = 336;
    /**
     * The number of classic entries.
     */
    public static final int ENTRY_COUNT = 1700;

    /**
     * {classic DWORD offset, native offset}: the classic DWORDs that copy four native bytes, a native layout transcription.
     */
    private static final int @NotNull [] @NotNull [] NATIVE_DWORDS = {
            {0x10, 0x20}, {0x18, 0x30}, {0x1C, 0x34}, {0x20, 0x38}, {0x24, 0x3C}, {0x28, 0x40}, {0x2C, 0x44},
            {0x30, 0x48}, {0x34, 0x4C}, {0x38, 0x50}, {0x3C, 0x54}, {0x40, 0x58}, {0x44, 0x5C}, {0x48, 0x60}, {0x4C, 0x68},
            {0x54, 0x70}, {0x58, 0x78}, {0x60, 0x88}, {0x64, 0x8C}, {0x84, 0xC8}, {0x8C, 0xD0}, {0x90, 0xD4},
            {0x94, 0xD8}, {0x98, 0xDC}, {0x9C, 0xE0}, {0xA0, 0xE4}, {0xA4, 0xE8}, {0xA8, 0xEC}, {0xAC, 0xF0},
            {0xDC, 0x140}, {0xE4, 0x148}, {0xE8, 0x150}, {0x104, 0x190}, {0x110, 0x19C}, {0x114, 0x1A0},
            {0x118, 0x1A4}, {0x120, 0x1B0}, {0x124, 0x1B4}, {0x128, 0x1B8}, {0x12C, 0x1BC}, {0x14C, 0x1E4}
    };
    /**
     * The native offset of each classic byte, or {@code -1}.
     */
    private static final int @NotNull [] NATIVE_BYTE = new int[ENTRY_SIZE];

    /**
     * The classic DWORD offsets that reject both reads and writes for every unit.
     */
    private static final int @NotNull [] FAILING_OFFSETS = {
            0x74, 0x78, 0x88, 0xB0, 0xB4, 0xB8, 0xBC, 0xF0, 0xF4, 0x100, 0x108, 0x10C, 0x130, 0x134, 0x138, 0x13C, 0x140, 0x144,
            0x148
    };
    /**
     * The classic pointer offsets and the native pointers behind them ({@link NativeUnitPointers}).
     */
    private static final int @NotNull [] @NotNull [] POINTERS = {
            {0x00, NativeUnitPointers.PREVIOUS_LINK}, {0x04, NativeUnitPointers.NEXT_LINK},
            {0x14, NativeUnitPointers.MOVEMENT_TARGET}, {0x5C, NativeUnitPointers.ORDER_TARGET},
            {0x68, NativeUnitPointers.PREVIOUS_OWNER_LINK}, {0x6C, NativeUnitPointers.NEXT_OWNER_LINK},
            {0x70, NativeUnitPointers.SUBUNIT}, {0x7C, NativeUnitPointers.AUTOMATIC_TARGET},
            {0x80, NativeUnitPointers.CONNECTED_UNIT}, {0xEC, NativeUnitPointers.CURRENT_BUILD_UNIT},
            {0x11C, NativeUnitPointers.IRRADIATED_BY}
    };
    /**
     * The padding byte offsets backed by region storage, initially zero and retained across unit slot reuse.
     */
    private static final int @NotNull [] NATIVE_PADDING = {0x3E, 0x192, 0x1B5};

    /**
     * The classic offset of the hit points DWORD.
     */
    private static final int HIT_POINTS = 0x08;
    /**
     * The classic offset of the sprite pointer.
     */
    private static final int SPRITE_POINTER = 0x0C;
    /**
     * The classic offset of the order target type DWORD.
     */
    private static final int ORDER_TARGET_TYPE = 0x50;
    /**
     * The native offset of the order target type WORD.
     */
    private static final int NATIVE_ORDER_TARGET_TYPE = 0x6C;
    /**
     * The classic offset of the timer pair DWORD.
     */
    private static final int TIMER_PAIR = 0xE0;
    /**
     * The native offset of the four bytes of the timer pair DWORD.
     */
    private static final int NATIVE_TIMER_PAIR = 0x144;
    /**
     * The native offset of the DWORD that the timer pair write stores the second helper result into.
     */
    private static final int NATIVE_TIMER_PAIR_NEXT = 0x148;
    /**
     * The classic offset of the low kind DWORD.
     */
    private static final int KIND_LOW = 0xF8;
    /**
     * The classic offset of the high kind DWORD.
     */
    private static final int KIND_HIGH = 0xFC;

    private static final int UNION_MEMBERS = NativeUnitUnion.Member.values().length;

    /**
     * The kind-union pointer shadows of each slot: Pylon low kind DWORD, Pylon high kind DWORD, rally unit.
     */
    private static final int KIND_SHADOWS = 3;
    /**
     * The kind shadow of a Pylon's low kind DWORD.
     */
    private static final int PYLON_LOW_SHADOW = 0;
    /**
     * The kind shadow of a Pylon's high kind DWORD.
     */
    private static final int PYLON_HIGH_SHADOW = 1;
    /**
     * The kind shadow of a rallying building's rally unit.
     */
    private static final int RALLY_UNIT_SHADOW = 2;
    private static final int PYLON_TYPE = ScrUnitIds.PROTOSS_PYLON;


    static {
        Arrays.fill(NATIVE_BYTE, -1);

        for (int[] range : NATIVE_DWORDS) {
            for (int index = 0; index < Integer.BYTES; index++) {
                NATIVE_BYTE[range[0] + index] = range[1] + index;
            }
        }
    }


    /**
     * Returns the classic address of the entry of a pool index.
     *
     * @param poolIndex the one-based pool index, or {@code 0} for none
     * @return the classic address of the entry, or {@code 0}
     */
    static int getEntryAddress(int poolIndex) {
        return poolIndex == 0 ? 0 : BASE + (poolIndex - 1) * ENTRY_SIZE;
    }

    /**
     * Finds the unit whose entry starts at a classic address: only the exact start of an entry names a unit.
     *
     * @param address the classic address
     * @param pool    the unit pool
     * @return the one-based pool index, or {@code 0} when the address is no entry start
     */
    static int findPoolIndex(int address, @NotNull UnitPool pool) {
        long relative = (address & DWORD_MASK) - BASE;

        if (relative < 0 || relative % ENTRY_SIZE != 0 || relative / ENTRY_SIZE >= Math.min(ENTRY_COUNT, pool.getCapacity())) {
            return 0;
        }

        return (int) (relative / ENTRY_SIZE) + 1;
    }

    private static void writeTimerPair(@NotNull Unit unit, int bytes, int next) {
        for (int index = 0; index < Integer.BYTES; index++) {
            NativeUnitFields.writeByte(unit, NATIVE_TIMER_PAIR + index, bytes >>> (index * Byte.SIZE));
        }

        for (int index = 0; index < Integer.BYTES; index++) {
            NativeUnitFields.writeByte(unit, NATIVE_TIMER_PAIR_NEXT + index, next >>> (index * Byte.SIZE));
        }
    }

    private static boolean isUnion(int field) {
        return field >= NativeUnitUnion.FIRST_OFFSET && field <= NativeUnitUnion.LAST_OFFSET;
    }

    private static int findPaddingIndex(int nativeOffset) {
        for (int index = 0; index < NATIVE_PADDING.length; index++) {
            if (NATIVE_PADDING[index] == nativeOffset) {
                return index;
            }
        }

        return -1;
    }

    private static int findPointerIndex(int field) {
        for (int index = 0; index < POINTERS.length; index++) {
            if (POINTERS[index][0] == field) {
                return index;
            }
        }

        return -1;
    }

    private static void requireServedByNative(int offset, int field) {
        for (int failing : FAILING_OFFSETS) {
            if (field == failing) {
                throw new ClassicAccessFailure(BASE + offset, String.format(
                        "EUD address 0x%08X (unit entry %d, classic offset 0x%X): the native unit region fails for this offset.",
                        BASE + offset, offset / ENTRY_SIZE, field
                ));
            }
        }
    }

    private static @NotNull UnsupportedOperationException createUnmappedAccessException(int offset) {
        return Exceptions.newUnsupportedOperationException(
                "EUD address {0} (unit entry {1}, classic offset {2}) is not represented by the unit table translation.",
                String.format("0x%08X", BASE + offset),
                offset / ENTRY_SIZE,
                String.format("0x%X", offset % ENTRY_SIZE)
        );
    }


    private final @NotNull UnitPool pool;
    private final @Nullable ScrUnitTypeView unitTypes;
    private final @Nullable SpritePool sprites;

    private final int @NotNull [] spriteShadowValues;
    private final boolean @NotNull [] spriteShadowSet;
    private final int @NotNull [] unionShadowValues;
    private final boolean @NotNull [] unionShadowSet;
    private final int @NotNull [] kindShadowValues;
    private final boolean @NotNull [] kindShadowSet;
    private final int @NotNull [] pointerShadowValues;
    private final boolean @NotNull [] pointerShadowSet;
    private final byte @NotNull [] paddingBytes;


    /**
     * Creates a {@link ClassicUnitTableRegion} instance over a world's units.
     *
     * @param pool the unit pool of 1700 slots
     */
    public ClassicUnitTableRegion(@NotNull UnitPool pool) {
        this(pool, null, null);
    }

    /**
     * Creates a {@link ClassicUnitTableRegion} instance over a world's units that also serves the union DWORDs.
     *
     * @param pool      the unit pool of 1700 slots
     * @param unitTypes the unit type table for the kind tests of the union, or {@code null} to leave the union unrepresented
     * @param sprites   the sprite pool for the Pylon's psi field sprite, or {@code null}
     */
    public ClassicUnitTableRegion(@NotNull UnitPool pool, @Nullable ScrUnitTypeView unitTypes, @Nullable SpritePool sprites) {
        this.pool      = pool;
        this.unitTypes = unitTypes;
        this.sprites   = sprites;

        this.spriteShadowValues  = new int[ENTRY_COUNT];
        this.spriteShadowSet     = new boolean[ENTRY_COUNT];
        this.unionShadowValues   = new int[ENTRY_COUNT * UNION_MEMBERS];
        this.unionShadowSet      = new boolean[ENTRY_COUNT * UNION_MEMBERS];
        this.kindShadowValues    = new int[ENTRY_COUNT * KIND_SHADOWS];
        this.kindShadowSet       = new boolean[ENTRY_COUNT * KIND_SHADOWS];
        this.pointerShadowValues = new int[ENTRY_COUNT * POINTERS.length];
        this.pointerShadowSet    = new boolean[ENTRY_COUNT * POINTERS.length];
        this.paddingBytes        = new byte[ENTRY_COUNT * NATIVE_PADDING.length];
    }


    /**
     * @return the classic base address
     */
    @Override
    public int getBase() {
        return BASE;
    }

    /**
     * @return the size in bytes
     */
    @Override
    public int getSize() {
        return ENTRY_COUNT * ENTRY_SIZE;
    }


    /**
     * Reads the unsigned byte at an offset.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @return the unsigned byte at the offset
     * @throws ClassicAccessFailure          if the offset rejects access
     * @throws UnsupportedOperationException if the offset has no represented field
     */
    @Override
    public int readByte(int offset) {
        return this.readByteFromDword(offset);
    }

    /**
     * Stores one byte, changing the domain state that holds it.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @param value  the byte value in the low eight bits
     * @throws ClassicAccessFailure          if the offset rejects access
     * @throws UnsupportedOperationException if the offset has no represented field
     */
    @Override
    public void writeByte(int offset, int value) {
        this.writeByteToDword(offset, value);
    }

    /**
     * Reads an aligned little-endian DWORD; regions whose domain values are DWORDs override it to read them whole.
     *
     * @param offset the byte offset of the DWORD, with four bytes inside the region
     * @return the DWORD
     * @throws ClassicAccessFailure          if the offset rejects access
     * @throws UnsupportedOperationException if the offset has no represented field
     */
    @Override
    public int readDword(int offset) {
        if ((offset & DWORD_OFFSET_MASK) != 0) {
            return ClassicRegion.super.readDword(offset);
        }

        Unit unit = this.getUnit(offset);
        int field = offset % ENTRY_SIZE;

        requireServedByNative(offset, field);

        int pointer = findPointerIndex(field);

        if (pointer >= 0) {
            int shadow = offset / ENTRY_SIZE * POINTERS.length + pointer;

            if (this.pointerShadowSet[shadow]) {
                return this.pointerShadowValues[shadow];
            }

            return getEntryAddress(NativeUnitPointers.read(this.pool, unit, POINTERS[pointer][1]));
        }

        if (field == HIT_POINTS) {
            return unit.getHitPoints();
        }

        if (isUnion(field)) {
            return this.readUnion(offset, unit, field);
        }

        if (field == ORDER_TARGET_TYPE) {
            return NativeUnitFields.readByte(unit, NATIVE_ORDER_TARGET_TYPE) |
                   NativeUnitFields.readByte(unit, NATIVE_ORDER_TARGET_TYPE + 1) << Byte.SIZE;
        }

        if (field == TIMER_PAIR) {
            int value = 0;

            for (int index = 0; index < Integer.BYTES; index++) {
                value |= (NativeUnitFields.readByte(unit, NATIVE_TIMER_PAIR + index) & BYTE_MASK) << (index * Byte.SIZE);
            }

            return value;
        }

        if (field == KIND_LOW || field == KIND_HIGH) {
            return this.readKind(offset, unit, field);
        }

        if (field == SPRITE_POINTER) {
            if (this.spriteShadowSet[offset / ENTRY_SIZE]) {
                return this.spriteShadowValues[offset / ENTRY_SIZE];
            }

            Sprite sprite = unit.getSpriteOrNull();

            return sprite == null ? 0 : this.getSpriteAddress(sprite.getPoolIndex());
        }

        int value = 0;

        for (int index = 0; index < Integer.BYTES; index++) {
            value |= (this.readNativeByte(unit, offset, field + index) & BYTE_MASK) << (index * Byte.SIZE);
        }

        return value;
    }

    /**
     * Stores a little-endian DWORD; regions whose domain values are DWORDs override it to store them whole.
     *
     * @param offset the byte offset of the DWORD, with four bytes inside the region
     * @param value  the DWORD
     * @throws ClassicAccessFailure          if the offset rejects access
     * @throws UnsupportedOperationException if the offset has no represented field
     */
    @Override
    public void writeDword(int offset, int value) {
        if ((offset & DWORD_OFFSET_MASK) != 0) {
            ClassicRegion.super.writeDword(offset, value);

            return;
        }

        Unit unit = this.getUnit(offset);
        int field = offset % ENTRY_SIZE;

        requireServedByNative(offset, field);

        if (field == HIT_POINTS) {
            unit.setHitPoints(value);

            return;
        }

        if (isUnion(field)) {
            this.writeUnion(offset, unit, field, value);

            return;
        }

        if (field == ORDER_TARGET_TYPE) {
            NativeUnitFields.writeByte(unit, NATIVE_ORDER_TARGET_TYPE, value);
            NativeUnitFields.writeByte(unit, NATIVE_ORDER_TARGET_TYPE + 1, value >>> Byte.SIZE);

            return;
        }

        if (field == TIMER_PAIR) {
            writeTimerPair(unit, value, value);

            return;
        }

        if (field == KIND_LOW || field == KIND_HIGH) {
            this.writeKind(offset, unit, field, value);

            return;
        }

        int pointer = findPointerIndex(field);

        if (pointer >= 0) {
            int shadow = offset / ENTRY_SIZE * POINTERS.length + pointer;
            int target = value == 0 ? 0 : findPoolIndex(value, this.pool);

            if (value != 0 && target == 0) {
                this.pointerShadowValues[shadow] = value;
                this.pointerShadowSet[shadow]    = true;

                return;
            }

            NativeUnitPointers.write(this.pool, unit, POINTERS[pointer][1], target);

            this.pointerShadowSet[shadow] = false;

            return;
        }

        if (field == SPRITE_POINTER) {
            this.writeSpritePointer(offset, unit, value);

            return;
        }

        for (int index = 0; index < Integer.BYTES; index++) {
            int nativeOffset = NATIVE_BYTE[field + index];

            if (findPaddingIndex(nativeOffset) < 0 && (nativeOffset < 0 || NativeUnitFields.getFieldStart(nativeOffset) < 0)) {
                throw createUnmappedAccessException(offset);
            }
        }

        for (int index = 0; index < Integer.BYTES; index++) {
            int nativeOffset = NATIVE_BYTE[field + index];
            int paddingIndex = findPaddingIndex(nativeOffset);

            if (paddingIndex >= 0) {
                this.paddingBytes[offset / ENTRY_SIZE * NATIVE_PADDING.length + paddingIndex] = (byte) (value >>> (index * Byte.SIZE));
            } else {
                NativeUnitFields.writeByte(unit, nativeOffset, value >>> (index * Byte.SIZE));
            }
        }
    }

    /**
     * Applies a SetDeaths modifier to the current aligned DWORD. For the timer pair, applies the modifier to that result
     * a second time and stores it in the following DWORD.
     *
     * @param offset   the byte offset of the DWORD, with four bytes inside the region
     * @param modifier the trigger modifier ({@link TriggerModifiers})
     * @param amount   the amount
     */
    @Override
    public void writeModified(int offset, int modifier, int amount) {
        if (offset % ENTRY_SIZE != TIMER_PAIR) {
            ClassicRegion.super.writeModified(offset, modifier, amount);

            return;
        }

        // Update the timer pair with the first result and the following DWORD with the second.
        Unit unit = this.getUnit(offset);
        int first = modifier == TriggerModifiers.SET_TO ? amount : PlayerScores.modify(this.readDword(offset), modifier, amount);
        int second = modifier == TriggerModifiers.SET_TO ? amount : PlayerScores.modify(first, modifier, amount);

        writeTimerPair(unit, first, second);
    }

    private int readKind(int offset, @NotNull Unit unit, int field) {
        int shadow = this.findKindShadow(offset / ENTRY_SIZE, unit, field);

        if (shadow >= 0) {
            if (this.kindShadowSet[shadow]) {
                return this.kindShadowValues[shadow];
            }

            int pointer = field == KIND_LOW ? NativeUnitPointers.PREVIOUS_PSI_PROVIDER : NativeUnitPointers.PSI_NEXT_OR_RALLY_UNIT;

            return getEntryAddress(NativeUnitPointers.read(this.pool, unit, pointer));
        }

        if (this.isRallyProducer(unit)) {
            return NativeUnitPointers.readRallyPosition(unit);
        }

        int position = NativeUnitPointers.readRallyPosition(unit);

        return field == KIND_LOW ? position & BYTE_MASK : position >>> Byte.SIZE & BYTE_MASK;
    }

    private void writeKind(int offset, @NotNull Unit unit, int field, int value) {
        int shadow = this.findKindShadow(offset / ENTRY_SIZE, unit, field);

        if (shadow >= 0) {
            int target = value == 0 ? 0 : findPoolIndex(value, this.pool);

            if (value != 0 && target == 0) {
                this.kindShadowValues[shadow] = value;
                this.kindShadowSet[shadow]    = true;

                return;
            }

            int pointer = field == KIND_LOW ? NativeUnitPointers.PREVIOUS_PSI_PROVIDER : NativeUnitPointers.PSI_NEXT_OR_RALLY_UNIT;

            NativeUnitPointers.write(this.pool, unit, pointer, target);

            this.kindShadowSet[shadow] = false;

            return;
        }

        if (this.isRallyProducer(unit)) {
            NativeUnitPointers.writeRallyPosition(unit, value);

            return;
        }

        int position = NativeUnitPointers.readRallyPosition(unit);
        int shift = field == KIND_LOW ? 0 : Byte.SIZE;

        NativeUnitPointers.writeRallyPosition(unit, LittleEndianUtils.replaceByte(position, shift, value));
    }

    /**
     * Finds the kind-union shadow of a kind DWORD.
     *
     * @param slot  the classic slot
     * @param unit  the slot's unit
     * @param field the kind DWORD offset
     * @return the kind-union shadow of the DWORD, or {@code -1} when the unit's kind serves no pointer there
     */
    private int findKindShadow(int slot, @NotNull Unit unit, int field) {
        if (unit.getUnitId() == PYLON_TYPE) {
            return slot * KIND_SHADOWS + (field == KIND_LOW ? PYLON_LOW_SHADOW : PYLON_HIGH_SHADOW);
        }

        if (this.isRallyProducer(unit) && field == KIND_HIGH) {
            return slot * KIND_SHADOWS + RALLY_UNIT_SHADOW;
        }

        return -1;
    }

    /**
     * @param unit the slot's unit
     * @return {@code true} if the unit type table is represented and the unit is a rally producer, {@code false} otherwise
     */
    private boolean isRallyProducer(@NotNull Unit unit) {
        return this.unitTypes != null && this.unitTypes.isRallyProducer(unit.getUnitId());
    }

    /**
     * Finds the union member a DWORD serves for the unit's kind.
     *
     * @param offset the byte offset of the DWORD
     * @param unit   the slot's unit
     * @param field  the union DWORD offset
     * @return the member of the unit's kind, or {@code null} when no kind of the DWORD matches
     * @throws UnsupportedOperationException if the union, or the member's sprite pointer, is not represented
     */
    private NativeUnitUnion.@Nullable Member findUnionMember(int offset, @NotNull Unit unit, int field) {
        if (this.unitTypes == null) {
            throw createUnmappedAccessException(offset);
        }

        NativeUnitUnion.Member member = NativeUnitUnion.getMember(this.unitTypes, unit, field);

        if (member != null && member.isSpritePointer() && this.sprites == null) {
            throw createUnmappedAccessException(offset);
        }

        return member;
    }

    private int readUnion(int offset, @NotNull Unit unit, int field) {
        NativeUnitUnion.Member member = this.findUnionMember(offset, unit, field);

        if (member == null) {
            return 0;
        }

        if (!member.isPointer()) {
            return NativeUnitUnion.readValue(unit, member);
        }

        int shadow = offset / ENTRY_SIZE * UNION_MEMBERS + member.ordinal();

        if (this.unionShadowSet[shadow]) {
            return this.unionShadowValues[shadow];
        }

        if (member.isSpritePointer()) {
            int sprite = NativeUnitUnion.readSprite(unit);

            return sprite < 0 ? 0 : this.getSpriteAddress(sprite);
        }

        return getEntryAddress(NativeUnitUnion.readUnit(unit, member));
    }

    private void writeUnion(int offset, @NotNull Unit unit, int field, int value) {
        NativeUnitUnion.Member member = this.findUnionMember(offset, unit, field);

        if (member == null) {
            return;
        }

        if (!member.isPointer()) {
            NativeUnitUnion.writeValue(unit, member, value);

            return;
        }

        int shadow = offset / ENTRY_SIZE * UNION_MEMBERS + member.ordinal();

        if (member.isSpritePointer()) {
            int sprite = value == 0 ? -1 : ClassicSpriteTableRegion.findSpriteIndex(value);

            if (value != 0 && sprite < 0) {
                this.unionShadowValues[shadow] = value;
                this.unionShadowSet[shadow]    = true;

                return;
            }

            NativeUnitUnion.writeSprite(this.sprites, unit, sprite);
        } else {
            int target = value == 0 ? 0 : findPoolIndex(value, this.pool);

            if (value != 0 && target == 0) {
                this.unionShadowValues[shadow] = value;
                this.unionShadowSet[shadow]    = true;

                return;
            }

            NativeUnitUnion.writeUnit(this.pool, unit, member, target);
        }

        this.unionShadowSet[shadow] = false;
    }

    /**
     * Replaces the sprite pointer: {@code 0} stores none, the exact start of a sprite entry stores that sprite (only the
     * raw pointer), any other value only the shadow.
     *
     * @param offset the byte offset of the sprite pointer DWORD
     * @param unit   the slot's unit
     * @param value  the DWORD
     * @throws UnsupportedOperationException if the region has no sprite pool
     */
    private void writeSpritePointer(int offset, @NotNull Unit unit, int value) {
        if (this.sprites == null) {
            throw createUnmappedAccessException(offset);
        }

        int slot = offset / ENTRY_SIZE;
        int sprite = value == 0 ? -1 : ClassicSpriteTableRegion.findSpriteIndex(value);

        if (value != 0 && sprite < 0) {
            this.spriteShadowValues[slot] = value;
            this.spriteShadowSet[slot]    = true;

            return;
        }

        unit.setSprite(sprite < 0 ? null : this.sprites.getSprite(sprite));

        this.spriteShadowSet[slot] = false;
    }

    /**
     * Returns the classic address of a sprite: index times the sprite entry size plus the sprite table, {@code 0}
     * outside the main array (a sprite a free-list commit added).
     *
     * @param poolIndex the sprite pool index
     * @return the classic address, or {@code 0}
     */
    private int getSpriteAddress(int poolIndex) {
        if (this.sprites != null && poolIndex >= this.sprites.getNativeCapacity()) {
            return 0;
        }

        return ClassicSpriteTableRegion.BASE + poolIndex * ClassicSpriteTableRegion.ENTRY_SIZE;
    }


    private int readNativeByte(@NotNull Unit unit, int offset, int classicByte) {
        int nativeOffset = NATIVE_BYTE[classicByte];

        if (findPaddingIndex(nativeOffset) >= 0) {
            return this.paddingBytes[offset / ENTRY_SIZE * NATIVE_PADDING.length + findPaddingIndex(nativeOffset)] & BYTE_MASK;
        }

        if (nativeOffset < 0 || NativeUnitFields.getFieldStart(nativeOffset) < 0) {
            throw createUnmappedAccessException(offset);
        }

        return NativeUnitFields.readByte(unit, nativeOffset);
    }

    private @NotNull Unit getUnit(int offset) {
        int slot = offset / ENTRY_SIZE;

        if (slot >= this.pool.getCapacity()) {
            throw createUnmappedAccessException(offset);
        }

        return this.pool.getUnit(slot + 1);
    }
}
