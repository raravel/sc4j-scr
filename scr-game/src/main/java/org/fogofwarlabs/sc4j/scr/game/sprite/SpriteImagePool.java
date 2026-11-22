package org.fogofwarlabs.sc4j.scr.game.sprite;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.animation.AnimationProgram;
import org.fogofwarlabs.sc4j.engine.animation.system.AnimationRunner;
import org.fogofwarlabs.sc4j.engine.animation.system.AnimationState;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.load.ScrIscriptOffsets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Tracks persistent image storage identity, free-list order and recycling.
 * <p>
 * The pool owns the {@link AnimationState} of its images, indexed by pool index: the running iscript of every slot ({@link SpriteImage}). Until
 * the game's iscript interpreter binds the pool to its program ({@link #bindIscript}), the state keeps native offsets as positions; binding
 * converts them through the offset map, and from then on {@link #getOffset} and {@link #getPosition} convert.
 * <p>
 * Every allocation stamps its slot with a generation ({@link #getGeneration(int)}), a serial that tells images apart that reuse a slot. The
 * generations are presentation data: no simulation rule reads them, the state hash and saved games leave them out.
 * <p>
 * Capacity must be supplied from established runtime state. This pool does not initialize the original graphics tables, viewport, sprite pool or
 * world schedule.
 */
public final class SpriteImagePool {
    /**
     * The return addresses an image keeps: one, overwritten by each {@code call}.
     */
    private static final int CALL_STACK_CAPACITY = 1;
    /**
     * The call stack entry that holds the return address of an image.
     */
    private static final int RETURN_ENTRY = 0;

    private final @NotNull AnimationState animations;
    private @Nullable AnimationRunner animationRunner;
    private @Nullable AnimationProgram iscriptProgram;
    private @Nullable ScrIscriptOffsets iscriptOffsets;
    private @NotNull SpriteImage @NotNull [] images;
    private int @NotNull [] nextFreeIndices;
    private boolean @NotNull [] allocated;
    private long @NotNull [] generations;

    private int firstFreeIndex;
    private int allocatedCount;
    private long generation;

    /**
     * Creates a {@link SpriteImagePool} instance with zeroed image storage.
     * <p>
     * The insertion loop preserves slot {@code 0} and inserts subsequent slots after it, producing {@code 0, capacity - 1, ..., 1}.
     *
     * @param capacity the explicitly established positive runtime capacity
     * @throws IllegalArgumentException if the capacity is not positive
     */
    public SpriteImagePool(int capacity) {
        if (capacity <= 0) {
            throw Exceptions.newIllegalArgumentException("Native image pool capacity must be positive.");
        }

        this.animations      = new AnimationState(capacity, CALL_STACK_CAPACITY);
        this.animationRunner = null;
        this.iscriptProgram  = null;
        this.iscriptOffsets  = null;
        this.images          = new SpriteImage[capacity];
        this.nextFreeIndices = new int[capacity];
        this.allocated       = new boolean[capacity];
        this.generations     = new long[capacity];

        this.firstFreeIndex = 0;
        this.allocatedCount = 0;
        this.generation     = 0;

        for (int poolIndex = 0; poolIndex < capacity; poolIndex++) {
            this.images[poolIndex]          = new SpriteImage(this, poolIndex);
            this.nextFreeIndices[poolIndex] = poolIndex == 0 ? capacity - 1 : poolIndex - 1;
        }

        this.nextFreeIndices[capacity == 1 ? 0 : 1] = -1;
    }

    /**
     * @return the running iscript of every slot, indexed by pool index
     */
    public @NotNull AnimationState getAnimationState() {
        return this.animations;
    }

    /**
     * @return the runner of the animation state that the game's iscript interpreter created, or {@code null} before its first run
     */
    public @Nullable AnimationRunner getAnimationRunner() {
        return this.animationRunner;
    }

    /**
     * Sets the runner of the animation state; the iscript interpreter of the game that owns the pool creates it once.
     *
     * @param animationRunner the runner of {@link #getAnimationState()}
     */
    public void setAnimationRunner(@NotNull AnimationRunner animationRunner) {
        this.animationRunner = animationRunner;
    }

    /**
     * Converts a program position of the state to an iscript offset.
     *
     * @param position the position, up to the program length (the end)
     * @return the offset; the position itself before the pool is bound
     */
    public int getOffset(int position) {
        ScrIscriptOffsets offsets = this.iscriptOffsets;

        return offsets == null ? position : offsets.getOffset(position);
    }

    /**
     * Converts an iscript offset to a program position of the state.
     *
     * @param offset the WORD offset
     * @return the position; the offset itself before the pool is bound
     */
    public int getPosition(int offset) {
        ScrIscriptOffsets offsets = this.iscriptOffsets;

        return offsets == null ? offset : offsets.getPosition(offset);
    }

    /**
     * @return the pool capacity, including the entries {@link #expandFreeList(int)} appended
     */
    public int getCapacity() {
        return this.images.length;
    }

    /**
     * @return the allocated slot count
     */
    public int getAllocatedCount() {
        return this.allocatedCount;
    }

    /**
     * @return the next allocation index, or {@code -1} when exhausted
     */
    public int getFirstFreeIndex() {
        return this.firstFreeIndex;
    }

    /**
     * @param poolIndex the zero-based storage index
     * @return the persistent image object at that index, including retained recycled fields
     */
    public @NotNull SpriteImage getImage(int poolIndex) {
        return this.images[poolIndex];
    }

    /**
     * @param poolIndex the zero-based storage index
     * @return {@code true} if the slot is allocated, {@code false} otherwise
     */
    public boolean isAllocated(int poolIndex) {
        return this.allocated[poolIndex];
    }

    /**
     * @param poolIndex the zero-based storage index
     * @return the generation of the slot's last allocation, {@code 0} if the slot was never allocated or holds a saved game's image
     */
    public long getGeneration(int poolIndex) {
        return this.generations[poolIndex];
    }

    /**
     * @return the generation of the latest allocation, {@code 0} before the first; every allocation takes the next one
     */
    public long getGeneration() {
        return this.generation;
    }

    /**
     * @param poolIndex the zero-based storage index
     * @return the following free index, or {@code -1} at the end
     */
    public int getNextFreeIndex(int poolIndex) {
        return this.nextFreeIndices[poolIndex];
    }


    /**
     * Replaces the free list with {@code count + 1} fresh zeroed entries appended to the pool, as the EUD emulation's free-list commit does when a
     * map writes the classic free-list head: the entries of a newly sized array, the first one as the head, then each further entry inserted after
     * the head, giving {@code new[0], new[count], ..., new[1]}. Previously free entries leave the free list; allocated entries return to the new list
     * when freed.
     *
     * @param count the length of the classic chain the map built
     * @return the pool index of {@code new[0]}
     */
    public int expandFreeList(int count) {
        int first = this.images.length;
        int capacity = first + count + 1;

        this.animations.ensureLength(capacity);

        this.images          = Arrays.copyOf(this.images, capacity);
        this.nextFreeIndices = Arrays.copyOf(this.nextFreeIndices, capacity);
        this.allocated       = Arrays.copyOf(this.allocated, capacity);
        this.generations     = Arrays.copyOf(this.generations, capacity);

        for (int poolIndex = first; poolIndex < capacity; poolIndex++) {
            this.images[poolIndex] = new SpriteImage(this, poolIndex);

            if (this.iscriptProgram != null) {
                this.animations.setProgram(poolIndex, this.iscriptProgram);
            }
        }

        this.firstFreeIndex         = first;
        this.nextFreeIndices[first] = -1;

        for (int entry = 1; entry <= count; entry++) {
            this.nextFreeIndices[first + entry] = this.nextFreeIndices[first];
            this.nextFreeIndices[first]         = first + entry;
        }

        return first;
    }

    /**
     * Binds the animation state to the game's iscript program: every slot's program counter and return address become positions of the program
     * through its offset map, and every slot runs the program. Binding again to the same program does nothing.
     *
     * @param program the iscript program
     * @param offsets the offset map of the program
     */
    public void bindIscript(@NotNull AnimationProgram program, @NotNull ScrIscriptOffsets offsets) {
        if (this.iscriptProgram == program) {
            return;
        }

        for (int poolIndex = 0; poolIndex < this.images.length; poolIndex++) {
            int programCounter = this.getOffset(this.animations.getPosition(poolIndex));
            int returnAddress = this.getOffset(this.animations.getCallEntry(poolIndex, RETURN_ENTRY));

            this.animations.setPosition(poolIndex, offsets.getPosition(programCounter));
            this.animations.setCallEntry(poolIndex, RETURN_ENTRY, offsets.getPosition(returnAddress));
            this.animations.setProgram(poolIndex, program);
        }

        this.iscriptProgram = program;
        this.iscriptOffsets = offsets;
    }

    /**
     * Zeroes every slot before a saved game's images are read into it, as the image pool reader clears the whole array first: no slot is allocated
     * and the free list is empty until {@link #rebuildFreeList}.
     */
    public void clear() {
        for (SpriteImage image : this.images) {
            image.clearStorage();
        }

        Arrays.fill(this.nextFreeIndices, -1);
        Arrays.fill(this.allocated, false);
        Arrays.fill(this.generations, 0);

        this.firstFreeIndex = -1;
        this.allocatedCount = 0;
    }

    /**
     * Marks a slot that a saved game's image was read into as allocated.
     *
     * @param poolIndex the zero-based storage index
     * @throws IllegalStateException if the slot is already allocated
     */
    public void markLoaded(int poolIndex) {
        if (this.allocated[poolIndex]) {
            throw Exceptions.newIllegalStateException("The saved image slot {0} is loaded twice.", poolIndex);
        }

        this.allocated[poolIndex] = true;
        this.allocatedCount++;
    }

    /**
     * Links the slots without graphics into the free list after a saved game's images were read, as the image pool reader does: the first such slot
     * is head and tail, and every later one is appended after the tail, so the free list runs in ascending slot order.
     */
    public void rebuildFreeList() {
        int lastFreeIndex = -1;

        for (int poolIndex = 0; poolIndex < this.images.length; poolIndex++) {
            if (this.images[poolIndex].hasGraphics()) {
                continue;
            }

            if (lastFreeIndex < 0) {
                this.firstFreeIndex = poolIndex;
            } else {
                this.nextFreeIndices[lastFreeIndex] = poolIndex;
            }

            lastFreeIndex = poolIndex;
        }
    }

    /**
     * Removes the free head before image initialization.
     *
     * @return the retained image storage, or {@code null} when exhausted
     */
    public @Nullable SpriteImage allocate() {
        if (this.firstFreeIndex < 0) {
            return null;
        }

        int poolIndex = this.firstFreeIndex;

        this.firstFreeIndex             = this.nextFreeIndices[poolIndex];
        this.nextFreeIndices[poolIndex] = -1;
        this.allocated[poolIndex]       = true;
        this.generations[poolIndex]     = ++this.generation;
        this.allocatedCount++;

        return this.images[poolIndex];
    }

    /**
     * Returns image storage after its sprite-list unlink.
     * <p>
     * The insertion preserves the existing free head. Other image fields and the sprite's main-image pointer remain intact; later allocation reuses
     * this object.
     *
     * @param image the image already unlinked from its sprite list
     * @throws IllegalStateException if the image does not identify allocated storage in this pool
     */
    public void recycle(@NotNull SpriteImage image) {
        int poolIndex = image.getIndex();

        if (poolIndex < 0 || poolIndex >= this.images.length || this.images[poolIndex] != image || !this.allocated[poolIndex]) {
            throw Exceptions.newIllegalStateException("The image is not allocated in this native pool.");
        }

        image.setGraphics(null);
        this.allocated[poolIndex] = false;
        this.allocatedCount--;

        if (this.firstFreeIndex < 0) {
            this.firstFreeIndex = poolIndex;
        } else {
            this.nextFreeIndices[poolIndex]           = this.nextFreeIndices[this.firstFreeIndex];
            this.nextFreeIndices[this.firstFreeIndex] = poolIndex;
        }
    }
}
