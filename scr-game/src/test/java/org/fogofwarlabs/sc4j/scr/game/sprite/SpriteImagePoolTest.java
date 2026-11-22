package org.fogofwarlabs.sc4j.scr.game.sprite;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks image allocation, recycling and retained storage identity.
 */
final class SpriteImagePoolTest {
    /**
     * The capacity of the pool whose allocation order is checked, which is also its allocated count when it is full.
     */
    private static final int POOL_CAPACITY = 4;
    /**
     * The capacity of a one-slot pool.
     */
    private static final int SINGLE_SLOT_CAPACITY = 1;
    /**
     * An empty capacity, which the pool rejects.
     */
    private static final int EMPTY_CAPACITY = 0;
    /**
     * The pool indices in the allocation order of a new pool.
     */
    private static final int @NotNull [] INITIAL_FREE_ORDER = {0, 3, 2, 1};
    /**
     * The pool indices in the allocation order after the first allocated image is recycled.
     */
    private static final int @NotNull [] RECYCLED_FREE_ORDER = {3, 0, 2, 1};
    /**
     * The free head after the first allocation, which recycling keeps.
     */
    private static final int KEPT_FREE_HEAD = 3;
    /**
     * The free head of an exhausted pool.
     */
    private static final int NO_FREE_INDEX = -1;
    /**
     * The {@code images.dat} index stored into the recycled image.
     */
    private static final int STORED_IMAGE_TYPE = 239;
    /**
     * The x offset stored into the recycled image.
     */
    private static final int STORED_OFFSET_X = 1;
    /**
     * The y offset stored into the recycled image.
     */
    private static final int STORED_OFFSET_Y = -2;
    /**
     * The draw function stored into the recycled image: the normal one.
     */
    private static final int NORMAL_MODIFIER = 0;
    /**
     * The script position stored into the recycled image.
     */
    private static final int STORED_PROGRAM_COUNTER = 33;
    /**
     * The image type of a foreign fixture image, which the ownership check does not depend on.
     */
    private static final int IMAGE_TYPE = 0;

    @Test
    void allocate_should_follow_initial_free_order_and_return_null_after_exhaustion() {
        SpriteImagePool pool = new SpriteImagePool(POOL_CAPACITY);

        for (int expectedIndex : INITIAL_FREE_ORDER) {
            SpriteImage image = pool.allocate();

            Assertions.assertNotNull(image);
            Assertions.assertEquals(expectedIndex, image.getIndex());
            Assertions.assertSame(image, pool.getImage(expectedIndex));
            Assertions.assertTrue(pool.isAllocated(expectedIndex));
        }

        Assertions.assertEquals(POOL_CAPACITY, pool.getAllocatedCount());
        Assertions.assertEquals(NO_FREE_INDEX, pool.getFirstFreeIndex());

        Assertions.assertNull(pool.allocate());

        Assertions.assertThrows(IllegalArgumentException.class, () -> new SpriteImagePool(EMPTY_CAPACITY));
    }

    @Test
    void recycle_should_keep_the_free_head_and_retain_storage_fields_until_initialization() {
        SpriteImagePool pool = new SpriteImagePool(POOL_CAPACITY);
        SpriteImage first = pool.allocate();

        Assertions.assertNotNull(first);

        first.initializeNativeStorage(STORED_IMAGE_TYPE,
                                      STORED_OFFSET_X, STORED_OFFSET_Y,
                                      SpriteImage.FLAG_REDRAW | SpriteImage.FLAG_HAS_DIRECTIONAL_FRAMES,
                                      NORMAL_MODIFIER);
        first.setProgramCounter(STORED_PROGRAM_COUNTER);

        pool.recycle(first);

        Assertions.assertFalse(first.hasGraphics());
        Assertions.assertEquals(STORED_PROGRAM_COUNTER, first.getProgramCounter());
        Assertions.assertEquals(STORED_IMAGE_TYPE, first.getImageTypeId());
        Assertions.assertEquals(KEPT_FREE_HEAD, pool.getFirstFreeIndex());

        for (int expectedIndex : RECYCLED_FREE_ORDER) {
            SpriteImage image = pool.allocate();

            Assertions.assertNotNull(image);
            Assertions.assertEquals(expectedIndex, image.getIndex());
        }

        Assertions.assertNull(pool.allocate());

        pool.recycle(first);

        Assertions.assertSame(first, pool.allocate());

        Assertions.assertEquals(STORED_PROGRAM_COUNTER, first.getProgramCounter());
    }

    @Test
    void allocate_should_stamp_each_allocation_with_the_next_generation() {
        SpriteImagePool pool = new SpriteImagePool(POOL_CAPACITY);

        Assertions.assertEquals(0, pool.getGeneration());

        SpriteImage first = pool.allocate();
        SpriteImage second = pool.allocate();

        Assertions.assertNotNull(first);
        Assertions.assertNotNull(second);
        Assertions.assertEquals(1, pool.getGeneration(first.getIndex()));
        Assertions.assertEquals(2, pool.getGeneration(second.getIndex()));

        pool.clear();

        Assertions.assertEquals(0, pool.getGeneration(first.getIndex()));

        SpriteImagePool single = new SpriteImagePool(SINGLE_SLOT_CAPACITY);
        SpriteImage image = single.allocate();

        Assertions.assertNotNull(image);

        single.recycle(image);

        Assertions.assertEquals(1, single.getGeneration(image.getIndex()));
        Assertions.assertSame(image, single.allocate());
        Assertions.assertEquals(2, single.getGeneration(image.getIndex()));
        Assertions.assertEquals(2, single.getGeneration());
    }

    @Test
    void recycle_should_reject_foreign_and_already_recycled_images() {
        SpriteImagePool pool = new SpriteImagePool(SINGLE_SLOT_CAPACITY);
        SpriteImage image = pool.allocate();

        Assertions.assertNotNull(image);

        Assertions.assertThrows(IllegalStateException.class, () -> pool.recycle(SpriteScenario.createImage(IMAGE_TYPE, 0, 0)));

        SpriteImagePool other = new SpriteImagePool(SINGLE_SLOT_CAPACITY);
        SpriteImage foreignImage = other.allocate();

        Assertions.assertNotNull(foreignImage);

        Assertions.assertThrows(IllegalStateException.class, () -> pool.recycle(foreignImage));

        pool.recycle(image);

        Assertions.assertEquals(0, pool.getAllocatedCount());

        Assertions.assertThrows(IllegalStateException.class, () -> pool.recycle(image));
    }
}
