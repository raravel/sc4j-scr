package org.fogofwarlabs.sc4j.scr.game.sprite;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Tracks persistent sprite storage, free order, row membership and registration values.
 * <p>
 * The decoded position representation preserves the coordinate consumers' unsigned WORD values. Each map row holds a doubly linked sprite list, up to
 * {@link GameMap#MAXIMUM_DIMENSION} tile rows.
 * <p>
 * Every allocation stamps its slot with a generation ({@link #getGeneration(int)}), a serial that tells sprites apart that reuse a slot. The
 * generations are bookkeeping of the pool: no simulation rule reads them, the state hash and saved games leave them out.
 */
public final class SpritePool {
    /**
     * The mask of a WORD coordinate or unit type value.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The mask of the creation-tag BYTE.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The bit position of the creation-tag BYTE in the fog metadata, just above the unit type WORD.
     */
    private static final int FOG_CREATION_TAG_SHIFT = Short.SIZE;

    /**
     * Decodes the map row of a sprite's y coordinate: the coordinate read as a signed WORD, divided by the tile size and clamped to the rows of the
     * map.
     *
     * @param y         the y coordinate in pixels
     * @param mapHeight the map height in tiles
     * @return the row, from {@code 0} through {@code mapHeight - 1} for a positive map height
     */
    public static int decodeRow(int y, int mapHeight) {
        return Math.max(0, Math.min((short) y / TerrainTileset.TILE_SIZE, mapHeight - 1));
    }

    /**
     * Copies a link array into a larger one whose new entries hold no link ({@code -1}).
     *
     * @param values   the link array
     * @param capacity the new length, at least the current one
     * @return the grown link array
     */
    private static int @NotNull [] growLinkIndices(int @NotNull [] values, int capacity) {
        int length = values.length;
        int[] grown = Arrays.copyOf(values, capacity);

        Arrays.fill(grown, length, capacity, -1);

        return grown;
    }

    private @NotNull Sprite @NotNull [] sprites;
    private int @NotNull [] nextFreeIndices;
    /**
     * The previous links of the free entries, which the free list keeps doubly linked.
     */
    private int @NotNull [] previousFreeIndices;
    private boolean @NotNull [] allocated;
    private long @NotNull [] generations;

    private final int @NotNull [] firstRowIndices;
    private final int @NotNull [] lastRowIndices;
    private int @NotNull [] previousRowIndices;
    private int @NotNull [] nextRowIndices;
    private int @NotNull [] rowIndices;

    private boolean @NotNull [] registered;
    private int @NotNull [] registrationValues;
    private boolean @NotNull [] fogMetadataPresent;
    private int @NotNull [] fogMetadata;

    private int firstFreeIndex;
    private int lastFreeIndex;
    private int allocatedCount;
    private long generation;
    /**
     * The capacity the pool was created with: the initial array, which EUD pointer conversions cover.
     */
    private final int nativeCapacity;

    /**
     * Creates a {@link SpritePool} instance in the retained free-list order: entry {@code 0} becomes head and tail of the free list, then every
     * further entry is inserted right after the head, so the traversal is {@code 0, capacity - 1, ..., 1}, each entry's previous link is the entry
     * inserted after it (the head for the last one) and the tail stays entry {@code 1}.
     *
     * @param capacity the established positive runtime capacity
     * @throws IllegalArgumentException if the capacity is not positive
     */
    public SpritePool(int capacity) {
        if (capacity <= 0) {
            throw Exceptions.newIllegalArgumentException("Native sprite pool capacity must be positive.");
        }

        this.sprites             = new Sprite[capacity];
        this.nextFreeIndices     = new int[capacity];
        this.previousFreeIndices = new int[capacity];
        this.allocated           = new boolean[capacity];
        this.generations         = new long[capacity];

        this.firstRowIndices    = new int[GameMap.MAXIMUM_DIMENSION];
        this.lastRowIndices     = new int[GameMap.MAXIMUM_DIMENSION];
        this.previousRowIndices = new int[capacity];
        this.nextRowIndices     = new int[capacity];
        this.rowIndices         = new int[capacity];

        this.registered         = new boolean[capacity];
        this.registrationValues = new int[capacity];
        this.fogMetadataPresent = new boolean[capacity];
        this.fogMetadata        = new int[capacity];

        this.firstFreeIndex = 0;
        this.lastFreeIndex  = capacity == 1 ? 0 : 1;
        this.allocatedCount = 0;
        this.generation     = 0;
        this.nativeCapacity = capacity;

        Arrays.fill(this.firstRowIndices, -1);
        Arrays.fill(this.lastRowIndices, -1);
        Arrays.fill(this.previousRowIndices, -1);
        Arrays.fill(this.nextRowIndices, -1);
        Arrays.fill(this.rowIndices, -1);

        // Each entry stores its own index in its reserved WORD (entry 0 keeps 0).
        for (int poolIndex = 0; poolIndex < capacity; poolIndex++) {
            this.sprites[poolIndex] = new Sprite(poolIndex);
            this.sprites[poolIndex].setRawDimensions(poolIndex, 0, 0);

            this.nextFreeIndices[poolIndex]     = poolIndex == 0 ? capacity - 1 : poolIndex - 1;
            this.previousFreeIndices[poolIndex] = poolIndex == 0 ? -1 : poolIndex == capacity - 1 ? 0 : poolIndex + 1;
        }

        this.nextFreeIndices[capacity == 1 ? 0 : 1] = -1;
    }

    /**
     * @return the pool capacity, including the entries {@link #expandFreeList(int)} appended
     */
    public int getCapacity() {
        return this.sprites.length;
    }

    /**
     * @return the capacity the pool was created with (the initial array); later entries come from {@link #expandFreeList(int)}
     */
    public int getNativeCapacity() {
        return this.nativeCapacity;
    }

    /**
     * @return the number of allocated slots
     */
    public int getAllocatedCount() {
        return this.allocatedCount;
    }

    /**
     * @return the free head index, or {@code -1} when exhausted
     */
    public int getFirstFreeIndex() {
        return this.firstFreeIndex;
    }

    /**
     * @return the free tail index, or {@code -1} when exhausted
     */
    public int getLastFreeIndex() {
        return this.lastFreeIndex;
    }

    /**
     * @param poolIndex the storage index
     * @return the persistent storage object
     */
    public @NotNull Sprite getSprite(int poolIndex) {
        return this.sprites[poolIndex];
    }

    /**
     * @param poolIndex the storage index
     * @return {@code true} if allocated, {@code false} otherwise
     */
    public boolean isAllocated(int poolIndex) {
        return this.allocated[poolIndex];
    }

    /**
     * @param poolIndex the storage index
     * @return the generation of the slot's last allocation, {@code 0} if the slot was never allocated or holds a saved game's sprite
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
     * @param poolIndex the storage index
     * @return the next free index, or {@code -1}
     */
    public int getNextFreeIndex(int poolIndex) {
        return this.nextFreeIndices[poolIndex];
    }

    /**
     * Reads a list link of a sprite (previous or next): the row list of an allocated sprite, the free list of a free one.
     *
     * @param poolIndex the storage index
     * @param next      {@code true} for the next link, {@code false} for the previous link
     * @return the linked index, or {@code -1} for none
     */
    public int getLink(int poolIndex, boolean next) {
        if (this.allocated[poolIndex]) {
            return next ? this.nextRowIndices[poolIndex] : this.previousRowIndices[poolIndex];
        }

        return next ? this.nextFreeIndices[poolIndex] : this.previousFreeIndices[poolIndex];
    }

    /**
     * @param rowIndex the row from {@code 0} through {@code 255}
     * @return the row head index, or {@code -1}
     */
    public int getFirstRowIndex(int rowIndex) {
        return this.firstRowIndices[rowIndex];
    }

    /**
     * @param rowIndex the row from {@code 0} through {@code 255}
     * @return the row tail index, or {@code -1}
     */
    public int getLastRowIndex(int rowIndex) {
        return this.lastRowIndices[rowIndex];
    }

    /**
     * @param poolIndex the storage index
     * @return the preceding row member index, or {@code -1}
     */
    public int getPreviousRowIndex(int poolIndex) {
        return this.previousRowIndices[poolIndex];
    }

    /**
     * @param poolIndex the storage index
     * @return the next row member index, or {@code -1}
     */
    public int getNextRowIndex(int poolIndex) {
        return this.nextRowIndices[poolIndex];
    }

    /**
     * @param poolIndex the storage index
     * @return the attached row index, or {@code -1}
     */
    public int getRowIndex(int poolIndex) {
        return this.rowIndices[poolIndex];
    }

    /**
     * @param poolIndex the storage index
     * @return {@code true} if the storage key is registered, {@code false} otherwise
     */
    public boolean isRegistered(int poolIndex) {
        return this.registered[poolIndex];
    }

    /**
     * @param poolIndex the storage index
     * @return the DWORD value registered under the key {@code poolIndex + 1}
     */
    public int getRegistrationValue(int poolIndex) {
        return this.registrationValues[poolIndex];
    }

    /**
     * @param poolIndex the storage index
     * @return {@code true} if the separate fog metadata key is present, {@code false} otherwise
     */
    public boolean hasFogMetadata(int poolIndex) {
        return this.fogMetadataPresent[poolIndex];
    }

    /**
     * @param poolIndex the storage index
     * @return the unit type WORD and creation-tag BYTE packed into the low three bytes
     */
    public int getFogMetadata(int poolIndex) {
        return this.fogMetadata[poolIndex];
    }


    /**
     * Sets a list link of a sprite (previous or next) without relinking the neighbors: the row list of an allocated sprite, the free list of a free
     * one.
     *
     * @param poolIndex the storage index
     * @param next      {@code true} for the next link, {@code false} for the previous link
     * @param value     the linked index, or {@code -1} for none
     */
    public void setLink(int poolIndex, boolean next, int value) {
        if (this.allocated[poolIndex]) {
            if (next) {
                this.nextRowIndices[poolIndex] = value;
            } else {
                this.previousRowIndices[poolIndex] = value;
            }

            return;
        }

        if (next) {
            this.nextFreeIndices[poolIndex] = value;
        } else {
            this.previousFreeIndices[poolIndex] = value;
        }
    }


    /**
     * Replaces the free list with {@code count + 1} fresh zeroed entries appended to the pool, as the EUD emulation's free-list commit does when a
     * map writes the classic free-list head: the entries of a newly sized array, the first one as the head, then each further entry inserted after
     * the head, giving {@code new[0], new[count], ..., new[1]}, with the previous links and the tail {@code new[1]} that insertion leaves. The
     * entries free before leave the free list; allocated entries return to the new list when freed.
     *
     * @param count the length of the classic chain the map built
     * @return the pool index of {@code new[0]}
     */
    public int expandFreeList(int count) {
        int first = this.sprites.length;
        int capacity = first + count + 1;

        this.sprites             = Arrays.copyOf(this.sprites, capacity);
        this.nextFreeIndices     = Arrays.copyOf(this.nextFreeIndices, capacity);
        this.previousFreeIndices = Arrays.copyOf(this.previousFreeIndices, capacity);
        this.allocated           = Arrays.copyOf(this.allocated, capacity);
        this.generations         = Arrays.copyOf(this.generations, capacity);
        this.previousRowIndices  = growLinkIndices(this.previousRowIndices, capacity);
        this.nextRowIndices      = growLinkIndices(this.nextRowIndices, capacity);
        this.rowIndices          = growLinkIndices(this.rowIndices, capacity);
        this.registered          = Arrays.copyOf(this.registered, capacity);
        this.registrationValues  = Arrays.copyOf(this.registrationValues, capacity);
        this.fogMetadataPresent  = Arrays.copyOf(this.fogMetadataPresent, capacity);
        this.fogMetadata         = Arrays.copyOf(this.fogMetadata, capacity);

        for (int poolIndex = first; poolIndex < capacity; poolIndex++) {
            this.sprites[poolIndex] = new Sprite(poolIndex);
        }

        this.firstFreeIndex             = first;
        this.lastFreeIndex              = first;
        this.nextFreeIndices[first]     = -1;
        this.previousFreeIndices[first] = -1;

        for (int entry = 1; entry <= count; entry++) {
            this.insertFreeAfter(first, first + entry);
        }

        return first;
    }

    /**
     * Stores the unit type and creation tag in the three-byte fog metadata value.
     *
     * @param sprite      the allocated fog sprite
     * @param unitType    the unit type WORD
     * @param creationTag the unit metadata whose low BYTE is propagated
     * @throws IllegalStateException if the sprite is not allocated storage of this pool
     */
    public void registerFogMetadata(@NotNull Sprite sprite, int unitType, int creationTag) {
        int poolIndex = this.requireAllocated(sprite);

        this.fogMetadataPresent[poolIndex] = true;
        this.fogMetadata[poolIndex]        = (unitType & WORD_MASK) | ((creationTag & BYTE_MASK) << FOG_CREATION_TAG_SHIFT);
    }

    /**
     * Removes the separate fog metadata key.
     *
     * @param sprite the allocated fog sprite being retired
     * @throws IllegalStateException if the sprite is not allocated storage of this pool
     */
    public void removeFogMetadata(@NotNull Sprite sprite) {
        int poolIndex = this.requireAllocated(sprite);

        this.fogMetadataPresent[poolIndex] = false;
        this.fogMetadata[poolIndex]        = 0;
    }

    /**
     * Removes the free head before initialization, relinks the neighbors through both links and clears the entry's links.
     *
     * @return the retained sprite storage, or {@code null} when exhausted
     */
    public @Nullable Sprite allocate() {
        if (this.firstFreeIndex < 0) {
            return null;
        }

        int poolIndex = this.firstFreeIndex;
        int previous = this.previousFreeIndices[poolIndex];
        int next = this.nextFreeIndices[poolIndex];

        this.firstFreeIndex = next;

        if (this.lastFreeIndex == poolIndex) {
            this.lastFreeIndex = previous;
        }

        if (previous >= 0) {
            this.nextFreeIndices[previous] = next;
        }

        if (next >= 0) {
            this.previousFreeIndices[next] = previous;
        }

        this.nextFreeIndices[poolIndex]     = -1;
        this.previousFreeIndices[poolIndex] = -1;

        this.allocated[poolIndex]   = true;
        this.generations[poolIndex] = ++this.generation;
        this.allocatedCount++;

        return this.sprites[poolIndex];
    }

    /**
     * Zeroes every slot before a saved game's sprites are read into it: no slot is allocated, no row holds a sprite and the free list is empty until
     * {@link #rebuildFreeList}.
     */
    public void clear() {
        for (Sprite sprite : this.sprites) {
            sprite.clearStorage();
        }

        Arrays.fill(this.nextFreeIndices, -1);
        Arrays.fill(this.previousFreeIndices, -1);
        Arrays.fill(this.allocated, false);
        Arrays.fill(this.generations, 0);

        Arrays.fill(this.firstRowIndices, -1);
        Arrays.fill(this.lastRowIndices, -1);
        Arrays.fill(this.previousRowIndices, -1);
        Arrays.fill(this.nextRowIndices, -1);
        Arrays.fill(this.rowIndices, -1);

        Arrays.fill(this.registered, false);
        Arrays.fill(this.registrationValues, 0);
        Arrays.fill(this.fogMetadataPresent, false);
        Arrays.fill(this.fogMetadata, 0);

        this.firstFreeIndex = -1;
        this.lastFreeIndex  = -1;
        this.allocatedCount = 0;
    }

    /**
     * Marks a slot that a saved game's sprite was read into as allocated, with the row links of its record.
     *
     * @param poolIndex        the storage index
     * @param previousRowIndex the preceding row member, or {@code -1}
     * @param nextRowIndex     the next row member, or {@code -1}
     * @throws IllegalStateException if the slot is already allocated
     */
    public void markLoaded(int poolIndex, int previousRowIndex, int nextRowIndex) {
        if (this.allocated[poolIndex]) {
            throw Exceptions.newIllegalStateException("The saved sprite slot {0} is loaded twice.", poolIndex);
        }

        this.allocated[poolIndex]          = true;
        this.previousRowIndices[poolIndex] = previousRowIndex;
        this.nextRowIndices[poolIndex]     = nextRowIndex;
        this.allocatedCount++;
    }

    /**
     * Restores a row's head and tail from a saved game's row arrays, and the row of each sprite on the way from the head.
     *
     * @param rowIndex   the row from {@code 0} through {@code 255}
     * @param firstIndex the row head, or {@code -1}
     * @param lastIndex  the row tail, or {@code -1}
     */
    public void restoreRow(int rowIndex, int firstIndex, int lastIndex) {
        this.firstRowIndices[rowIndex] = firstIndex;
        this.lastRowIndices[rowIndex]  = lastIndex;

        for (int poolIndex = firstIndex; poolIndex >= 0 && this.rowIndices[poolIndex] < 0; poolIndex = this.nextRowIndices[poolIndex]) {
            this.rowIndices[poolIndex] = rowIndex;
        }
    }

    /**
     * Links unallocated slots into the free list after a saved game's sprites were read: the first such slot is head and tail, every later one is
     * appended after the tail (ascending slot order), and every slot's reserved WORD then holds its own index.
     */
    public void rebuildFreeList() {
        for (int poolIndex = 0; poolIndex < this.sprites.length; poolIndex++) {
            if (this.allocated[poolIndex]) {
                continue;
            }

            if (this.firstFreeIndex < 0) {
                this.firstFreeIndex = poolIndex;
                this.lastFreeIndex  = poolIndex;
            } else {
                this.insertFreeAfter(this.lastFreeIndex, poolIndex);
            }
        }

        for (Sprite sprite : this.sprites) {
            sprite.setRawDimensions(sprite.getPoolIndex(), sprite.getWidth(), sprite.getHeight());
        }
    }

    /**
     * Registers the storage key and DWORD value.
     *
     * @param sprite the allocated sprite
     * @param value  the registration value
     * @throws IllegalStateException if the sprite is not allocated storage of this pool
     */
    public void register(@NotNull Sprite sprite, int value) {
        int poolIndex = this.requireAllocated(sprite);

        this.registered[poolIndex]         = true;
        this.registrationValues[poolIndex] = value;
    }

    /**
     * Restores a registration a saved game kept ({@code SOWN}), and a freed sprite keeps its registration.
     *
     * @param poolIndex the storage index
     * @param value     the registered DWORD value
     */
    public void restoreRegistration(int poolIndex, int value) {
        this.registered[poolIndex]         = true;
        this.registrationValues[poolIndex] = value;
    }

    /**
     * Inserts a successfully initialized sprite after the existing row head.
     *
     * @param sprite   the allocated sprite
     * @param rowIndex the established row from {@code 0} through {@code 255}
     * @throws IllegalStateException if the sprite is not allocated storage of this pool or already belongs to a row
     */
    public void attachToRow(@NotNull Sprite sprite, int rowIndex) {
        int poolIndex = this.requireAllocated(sprite);

        if (this.rowIndices[poolIndex] >= 0) {
            throw Exceptions.newIllegalStateException("The native sprite already belongs to a row.");
        }

        int firstIndex = this.firstRowIndices[rowIndex];

        if (firstIndex < 0) {
            this.firstRowIndices[rowIndex]     = poolIndex;
            this.lastRowIndices[rowIndex]      = poolIndex;
            this.previousRowIndices[poolIndex] = -1;
            this.nextRowIndices[poolIndex]     = -1;
        } else {
            this.nextRowIndices[poolIndex]     = this.nextRowIndices[firstIndex];
            this.previousRowIndices[poolIndex] = firstIndex;

            if (this.nextRowIndices[poolIndex] < 0) {
                this.lastRowIndices[rowIndex] = poolIndex;
            } else {
                this.previousRowIndices[this.nextRowIndices[poolIndex]] = poolIndex;
            }

            this.nextRowIndices[firstIndex] = poolIndex;
        }

        this.rowIndices[poolIndex] = rowIndex;
    }

    /**
     * Updates the decoded position, row links and image redraw flags.
     *
     * @param sprite    the allocated sprite
     * @param positionX the new x coordinate in pixels, truncated to an unsigned WORD
     * @param positionY the new y coordinate in pixels, truncated to an unsigned WORD
     * @param mapHeight the map height in tiles, from {@code 1} through {@link GameMap#MAXIMUM_DIMENSION}
     * @throws IllegalStateException         if the sprite is not allocated storage of this pool
     * @throws IllegalArgumentException      if the map height is not positive or exceeds the row lists
     * @throws UnsupportedOperationException if retained links disagree with the decoded old row
     */
    public void relocate(@NotNull Sprite sprite, int positionX, int positionY, int mapHeight) {
        int poolIndex = this.requireAllocated(sprite);

        if (mapHeight <= 0 || mapHeight > GameMap.MAXIMUM_DIMENSION) {
            throw Exceptions.newIllegalArgumentException("Native sprite relocation requires a map height from 1 through {0} tiles, not {1}.",
                                                         GameMap.MAXIMUM_DIMENSION, mapHeight);
        }

        positionX &= WORD_MASK;
        positionY &= WORD_MASK;

        if ((sprite.getX() & WORD_MASK) == positionX && (sprite.getY() & WORD_MASK) == positionY) {
            return;
        }

        int previousRow = decodeRow(sprite.getY(), mapHeight);

        sprite.setX(positionX);
        sprite.setY(positionY);

        int nextRow = decodeRow(positionY, mapHeight);

        if (previousRow != nextRow) {
            if (this.rowIndices[poolIndex] != previousRow) {
                throw Exceptions.newUnsupportedOperationException("Native sprite row links no longer match the decoded position.");
            }

            this.detachFromRow(poolIndex, previousRow);
            this.attachToRow(sprite, nextRow);
        }

        sprite.markImagesForRedraw();
    }

    /**
     * Reinserts storage after the free head following an initializer failure: the entry becomes head and tail of an empty list (its links unchanged),
     * otherwise puts it right after the head, the entry becoming the tail when the head was the tail.
     * <p>
     * Scalar fields, image pointers and existing registration values are retained. The later out-of-range row branch does not invoke this operation.
     *
     * @param sprite the allocated sprite whose initializer returned failure
     * @throws IllegalStateException if the sprite is not allocated storage of this pool or already belongs to a row
     */
    public void recycleFailedInitialization(@NotNull Sprite sprite) {
        int poolIndex = this.requireAllocated(sprite);

        if (this.rowIndices[poolIndex] >= 0) {
            throw Exceptions.newIllegalStateException("A linked native sprite cannot use initializer-failure recycling.");
        }

        this.allocated[poolIndex] = false;
        this.allocatedCount--;

        if (this.firstFreeIndex < 0) {
            this.firstFreeIndex = poolIndex;
            this.lastFreeIndex  = poolIndex;
        } else {
            this.insertFreeAfter(this.firstFreeIndex, poolIndex);
        }
    }

    /**
     * Returns an emptied sprite from its row to the free list after image execution.
     *
     * @param sprite    the sprite whose final image just ended
     * @param mapHeight the established height in tiles
     * @return {@code true} if the row was valid and storage was returned, {@code false} otherwise
     * @throws IllegalStateException if the sprite is not allocated storage of this pool, still has images or is not linked in its decoded row, or the
     *                               map height is not positive
     */
    public boolean recycleEmptySprite(@NotNull Sprite sprite, int mapHeight) {
        int poolIndex = this.requireAllocated(sprite);

        if (!sprite.getImages().isEmpty() || mapHeight <= 0) {
            throw Exceptions.newIllegalStateException("Native sprite retirement requires an empty image list and established map height.");
        }

        int rowIndex = decodeRow(sprite.getY(), mapHeight);

        if (rowIndex >= GameMap.MAXIMUM_DIMENSION) {
            return false;
        }

        if (this.rowIndices[poolIndex] != rowIndex) {
            throw Exceptions.newIllegalStateException("The retiring sprite is not linked in its decoded native row.");
        }

        this.detachFromRow(poolIndex, rowIndex);

        this.previousRowIndices[poolIndex] = -1;
        this.nextRowIndices[poolIndex]     = -1;

        this.recycleFailedInitialization(sprite);

        return true;
    }

    /**
     * Detaches an allocated sprite from the row list it is linked in: the row head and tail move past it, its neighbors are linked to each other and
     * it no longer belongs to a row. Its own row links keep their values.
     *
     * @param poolIndex the storage index of the sprite
     * @param rowIndex  the row the sprite is linked in
     */
    private void detachFromRow(int poolIndex, int rowIndex) {
        int previousIndex = this.previousRowIndices[poolIndex];
        int nextIndex = this.nextRowIndices[poolIndex];

        if (this.firstRowIndices[rowIndex] == poolIndex) {
            this.firstRowIndices[rowIndex] = nextIndex;
        }

        if (this.lastRowIndices[rowIndex] == poolIndex) {
            this.lastRowIndices[rowIndex] = previousIndex;
        }

        if (previousIndex >= 0) {
            this.nextRowIndices[previousIndex] = nextIndex;
        }

        if (nextIndex >= 0) {
            this.previousRowIndices[nextIndex] = previousIndex;
        }

        this.rowIndices[poolIndex] = -1;
    }

    /**
     * Inserts a free entry right after another one; the entry becomes the tail when the other one was the tail.
     *
     * @param poolIndex the free entry to insert after
     * @param inserted  the entry to insert
     */
    private void insertFreeAfter(int poolIndex, int inserted) {
        if (this.lastFreeIndex == poolIndex) {
            this.lastFreeIndex = inserted;
        }

        int following = this.nextFreeIndices[poolIndex];

        this.previousFreeIndices[inserted] = poolIndex;
        this.nextFreeIndices[inserted]     = following;

        if (following >= 0) {
            this.previousFreeIndices[following] = inserted;
        }

        this.nextFreeIndices[poolIndex] = inserted;
    }

    /**
     * Returns the pool index of a sprite allocated in this pool.
     *
     * @param sprite the sprite
     * @return the sprite's pool index
     * @throws IllegalStateException if the sprite is not allocated storage of this pool
     */
    private int requireAllocated(@NotNull Sprite sprite) {
        int poolIndex = sprite.getPoolIndex();

        if (poolIndex < 0 || poolIndex >= this.sprites.length || this.sprites[poolIndex] != sprite || !this.allocated[poolIndex]) {
            throw Exceptions.newIllegalStateException("The sprite is not allocated in this native pool.");
        }

        return poolIndex;
    }
}
