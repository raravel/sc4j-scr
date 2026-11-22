package org.fogofwarlabs.sc4j.scr.game.sprite;

import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.unit.ClassicSpriteTableRegion;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks sprite storage and intrusive list ordering.
 */
final class SpritePoolTest {
    /**
     * The sprite type of the fixture sprites, which the storage does not depend on.
     */
    private static final int SPRITE_TYPE = 0;
    /**
     * The image type of the fixture images, which the storage does not depend on.
     */
    private static final int IMAGE_TYPE = 0;
    /**
     * The owner player of the relocated sprites.
     */
    private static final int OWNER = 0;
    /**
     * The capacity of a one-slot pool.
     */
    private static final int SINGLE_SLOT_CAPACITY = 1;
    /**
     * The capacity of the allocation and relocation pools, which is also the allocated count of the full allocation pool.
     */
    private static final int POOL_CAPACITY = 4;
    /**
     * The capacity of the row attachment pool.
     */
    private static final int ROW_POOL_CAPACITY = 3;
    /**
     * The largest unsigned word.
     */
    private static final int UNSIGNED_WORD_MAXIMUM = 65535;
    /**
     * The sprite type stored into the sprite whose initialization fails.
     */
    private static final int FAILED_SPRITE_TYPE = 130;
    /**
     * The y coordinate stored into the sprite whose initialization fails: the word sign bit.
     */
    private static final int WORD_SIGN_BIT = 32768;
    /**
     * The owner stored into the sprite whose initialization fails: the largest unsigned byte.
     */
    private static final int UNSIGNED_BYTE_MAXIMUM = 255;
    /**
     * The pool index that a new pool hands out first.
     */
    private static final int FIRST_POOL_INDEX = 0;
    /**
     * The pool index that a new pool of {@link #POOL_CAPACITY} sprites hands out second.
     */
    private static final int SECOND_POOL_INDEX = 3;
    /**
     * The pool index that a new pool of {@link #POOL_CAPACITY} sprites hands out third.
     */
    private static final int THIRD_POOL_INDEX = 2;
    /**
     * The pool index that a new pool of {@link #POOL_CAPACITY} sprites hands out last.
     */
    private static final int LAST_POOL_INDEX = 1;
    /**
     * The row end, row link and free link index that marks no sprite.
     */
    private static final int NO_SPRITE_INDEX = -1;
    /**
     * The registration value of the first row member.
     */
    private static final int REGISTRATION_VALUE = 17;
    /**
     * The row of the row attachment test.
     */
    private static final int ATTACHMENT_ROW = 5;
    /**
     * The first row, which holds the sprites at y coordinate {@code 0}.
     */
    private static final int FIRST_ROW = 0;
    /**
     * The x coordinate of the relocated sprites, before and after relocation.
     */
    private static final int RELOCATION_X = 16;
    /**
     * The y coordinate of the relocated sprites before relocation, in {@link #SOURCE_ROW}.
     */
    private static final int SOURCE_Y = 32;
    /**
     * The row of the relocated sprites before relocation.
     */
    private static final int SOURCE_ROW = 1;
    /**
     * The y coordinate of the relocated sprites after relocation, in {@link #DESTINATION_ROW}.
     */
    private static final int DESTINATION_Y = 64;
    /**
     * The row of the relocated sprites after relocation.
     */
    private static final int DESTINATION_ROW = 2;
    /**
     * The map height of the relocations, in tiles.
     */
    private static final int MAP_HEIGHT = 128;
    /**
     * The allocation position of the sprite in the middle of the source row, which is relocated first.
     */
    private static final int MIDDLE_ALLOCATION = 3;
    /**
     * The allocation position of the source row head, which is relocated second.
     */
    private static final int HEAD_ALLOCATION = 0;
    /**
     * The allocation position of the source row tail, which is relocated third.
     */
    private static final int TAIL_ALLOCATION = 1;
    /**
     * The allocation position of the last sprite left in the source row.
     */
    private static final int REMAINING_ALLOCATION = 2;
    /**
     * The pool indices of the destination row in list order after every sprite is relocated.
     */
    private static final int @NotNull [] RELOCATED_ROW_ORDER = {1, 2, 3, 0};
    /**
     * The member position of the destination row tail.
     */
    private static final int LAST_MEMBER = 3;
    /**
     * The images of the sprite whose word coordinates are relocated.
     */
    private static final int IMAGE_COUNT = 3;
    /**
     * The shift that gives every image distinct flags with the redraw flag clear.
     */
    private static final int DISTINCT_FLAGS_SHIFT = 1;
    /**
     * The redraw image flag.
     */
    private static final int REDRAW_FLAG = 0x01;
    /**
     * The directional frames image flag.
     */
    private static final int DIRECTIONAL_FRAMES_FLAG = 0x08;
    /**
     * The x coordinate of the sprite whose word coordinates are relocated.
     */
    private static final int WORD_TEST_X = 7;
    /**
     * A relocation x coordinate whose low word is {@link #WORD_TEST_X}.
     */
    private static final int WRAPPED_WORD_TEST_X = 65543;
    /**
     * A relocation y coordinate whose low word is {@code 0}.
     */
    private static final int WRAPPED_ZERO_Y = 65536;
    /**
     * An int with every bit set, which a word coordinate stores as {@link #UNSIGNED_WORD_MAXIMUM}.
     */
    private static final int ALL_BITS = -1;
    /**
     * A relocation x coordinate other than the stored one.
     */
    private static final int MOVED_X = 8;
    /**
     * The largest signed word, a y coordinate in a row past the map.
     */
    private static final int SIGNED_WORD_MAXIMUM = 32767;
    /**
     * The last row of a map {@link #MAP_HEIGHT} tiles high.
     */
    private static final int LAST_MAP_ROW = 127;
    /**
     * The x coordinate of the sprite before the out-of-range relocation.
     */
    private static final int OUT_OF_RANGE_START_X = 8;
    /**
     * The x coordinate of the out-of-range relocation.
     */
    private static final int OUT_OF_RANGE_X = 16;
    /**
     * The y coordinate of the out-of-range relocation, in the first row past the row range.
     */
    private static final int OUT_OF_RANGE_Y = 8192;
    /**
     * The map height of the out-of-range relocation, in tiles, which exceeds the row range.
     */
    private static final int OUT_OF_RANGE_MAP_HEIGHT = 300;
    /**
     * The offset of the next link after the previous link of a free entry.
     */
    private static final int NEXT_LINK_OFFSET = 0x04;
    /**
     * The last entry of the classic sprite table.
     */
    private static final int LAST_SPRITE_ENTRY = 2499;
    /**
     * The entry that an EUD write links to the last entry.
     */
    private static final int LINKED_SPRITE_ENTRY = 40;
    /**
     * The free tail of a new classic sprite table pool.
     */
    private static final int FREE_TAIL_ENTRY = 1;
    /**
     * The entry before the free tail in the free list of a new classic sprite table pool.
     */
    private static final int FREE_TAIL_PREVIOUS_ENTRY = 2;
    /**
     * The capacity of the pool whose free list is expanded.
     */
    private static final int SMALL_SPRITE_CAPACITY = 4;
    /**
     * The number of sprites that the expansion adds.
     */
    private static final int EXPANSION_COUNT = 3;
    /**
     * The classic address of the sprite table and the size of one sprite entry.
     */
    private static final int SPRITE_TABLE = 0x629D98;
    private static final int SPRITE_ENTRY_SIZE = 36;

    private static int getSpriteAddress(int poolIndex) {
        return SPRITE_TABLE + poolIndex * SPRITE_ENTRY_SIZE;
    }


    @Test
    void allocate_should_follow_original_order_and_keep_fields_after_failed_initialization() {
        SpritePool pool = new SpritePool(POOL_CAPACITY);
        Sprite first = pool.allocate();

        Assertions.assertNotNull(first);
        Assertions.assertEquals(FIRST_POOL_INDEX, first.getPoolIndex());

        first.initializeNativeStorage(FAILED_SPRITE_TYPE, UNSIGNED_WORD_MAXIMUM, WORD_SIGN_BIT, UNSIGNED_BYTE_MAXIMUM, true);

        pool.recycleFailedInitialization(first);

        Sprite headSprite = pool.allocate();

        Assertions.assertNotNull(headSprite);

        Assertions.assertEquals(SECOND_POOL_INDEX, headSprite.getPoolIndex());
        Assertions.assertSame(first, pool.allocate());

        Assertions.assertEquals(UNSIGNED_WORD_MAXIMUM, first.getX());
        Assertions.assertEquals(WORD_SIGN_BIT, first.getY());
        Assertions.assertEquals(UNSIGNED_BYTE_MAXIMUM, first.getOwner());

        Sprite middleSprite = pool.allocate();

        Assertions.assertNotNull(middleSprite);

        Assertions.assertEquals(THIRD_POOL_INDEX, middleSprite.getPoolIndex());

        Sprite tailSprite = pool.allocate();

        Assertions.assertNotNull(tailSprite);

        Assertions.assertEquals(LAST_POOL_INDEX, tailSprite.getPoolIndex());
        Assertions.assertNull(pool.allocate());

        Assertions.assertEquals(POOL_CAPACITY, pool.getAllocatedCount());
    }

    @Test
    void allocate_should_stamp_each_allocation_with_the_next_generation() {
        SpritePool pool = new SpritePool(SINGLE_SLOT_CAPACITY);

        Assertions.assertEquals(0, pool.getGeneration());

        Sprite sprite = pool.allocate();

        Assertions.assertNotNull(sprite);
        Assertions.assertEquals(1, pool.getGeneration(sprite.getPoolIndex()));

        pool.recycleFailedInitialization(sprite);

        Assertions.assertEquals(1, pool.getGeneration(sprite.getPoolIndex()), "a freed slot keeps its generation");
        Assertions.assertSame(sprite, pool.allocate());
        Assertions.assertEquals(2, pool.getGeneration(sprite.getPoolIndex()));
        Assertions.assertEquals(2, pool.getGeneration());

        pool.clear();

        Assertions.assertEquals(0, pool.getGeneration(sprite.getPoolIndex()));
    }

    @Test
    void row_attachment_keeps_the_row_head_and_storage_registration() {
        SpritePool pool = new SpritePool(ROW_POOL_CAPACITY);
        Sprite first = pool.allocate();
        Sprite second = pool.allocate();
        Sprite third = pool.allocate();

        Assertions.assertNotNull(first);
        Assertions.assertNotNull(second);
        Assertions.assertNotNull(third);

        pool.register(first, REGISTRATION_VALUE);
        pool.attachToRow(first, ATTACHMENT_ROW);
        pool.attachToRow(second, ATTACHMENT_ROW);
        pool.attachToRow(third, ATTACHMENT_ROW);

        Assertions.assertEquals(FIRST_POOL_INDEX, pool.getFirstRowIndex(ATTACHMENT_ROW));
        Assertions.assertEquals(third.getPoolIndex(), pool.getNextRowIndex(FIRST_POOL_INDEX));
        Assertions.assertEquals(second.getPoolIndex(), pool.getNextRowIndex(third.getPoolIndex()));
        Assertions.assertEquals(NO_SPRITE_INDEX, pool.getNextRowIndex(second.getPoolIndex()));
        Assertions.assertTrue(pool.isRegistered(FIRST_POOL_INDEX));
        Assertions.assertEquals(REGISTRATION_VALUE, pool.getRegistrationValue(FIRST_POOL_INDEX));

        Assertions.assertThrows(IllegalStateException.class, () -> pool.recycleFailedInitialization(first));
        Assertions.assertThrows(IllegalStateException.class, () -> pool.attachToRow(first, ATTACHMENT_ROW));
    }

    @Test
    void relocate_should_move_head_middle_and_tail_while_keeping_destination_head() {
        SpritePool pool = new SpritePool(POOL_CAPACITY);
        Sprite[] sprites = new Sprite[POOL_CAPACITY];

        for (int spriteIndex = 0; spriteIndex < sprites.length; spriteIndex++) {
            Sprite sprite = pool.allocate();

            Assertions.assertNotNull(sprite);

            sprite.initializeNativeStorage(SPRITE_TYPE, RELOCATION_X, SOURCE_Y, OWNER, false);
            pool.attachToRow(sprite, SOURCE_ROW);

            sprites[spriteIndex] = sprite;
        }

        Assertions.assertEquals(FIRST_POOL_INDEX, pool.getFirstRowIndex(SOURCE_ROW));
        Assertions.assertEquals(SECOND_POOL_INDEX, pool.getLastRowIndex(SOURCE_ROW));

        pool.relocate(sprites[MIDDLE_ALLOCATION], RELOCATION_X, DESTINATION_Y, MAP_HEIGHT);

        Assertions.assertEquals(THIRD_POOL_INDEX, pool.getNextRowIndex(FIRST_POOL_INDEX));
        Assertions.assertEquals(FIRST_POOL_INDEX, pool.getPreviousRowIndex(THIRD_POOL_INDEX));

        pool.relocate(sprites[HEAD_ALLOCATION], RELOCATION_X, DESTINATION_Y, MAP_HEIGHT);

        Assertions.assertEquals(THIRD_POOL_INDEX, pool.getFirstRowIndex(SOURCE_ROW));
        Assertions.assertEquals(NO_SPRITE_INDEX, pool.getPreviousRowIndex(THIRD_POOL_INDEX));

        pool.relocate(sprites[TAIL_ALLOCATION], RELOCATION_X, DESTINATION_Y, MAP_HEIGHT);

        Assertions.assertEquals(THIRD_POOL_INDEX, pool.getFirstRowIndex(SOURCE_ROW));
        Assertions.assertEquals(THIRD_POOL_INDEX, pool.getLastRowIndex(SOURCE_ROW));
        Assertions.assertEquals(NO_SPRITE_INDEX, pool.getNextRowIndex(THIRD_POOL_INDEX));

        pool.relocate(sprites[REMAINING_ALLOCATION], RELOCATION_X, DESTINATION_Y, MAP_HEIGHT);

        Assertions.assertEquals(NO_SPRITE_INDEX, pool.getFirstRowIndex(SOURCE_ROW));
        Assertions.assertEquals(NO_SPRITE_INDEX, pool.getLastRowIndex(SOURCE_ROW));
        Assertions.assertEquals(LAST_POOL_INDEX, pool.getFirstRowIndex(DESTINATION_ROW));
        Assertions.assertEquals(FIRST_POOL_INDEX, pool.getLastRowIndex(DESTINATION_ROW));

        for (int memberIndex = 0; memberIndex < RELOCATED_ROW_ORDER.length; memberIndex++) {
            int poolIndex = RELOCATED_ROW_ORDER[memberIndex];
            int expectedPrevious = memberIndex == 0 ? NO_SPRITE_INDEX : RELOCATED_ROW_ORDER[memberIndex - 1];
            int expectedNext = memberIndex == LAST_MEMBER ? NO_SPRITE_INDEX : RELOCATED_ROW_ORDER[memberIndex + 1];

            Assertions.assertEquals(expectedPrevious, pool.getPreviousRowIndex(poolIndex));
            Assertions.assertEquals(expectedNext, pool.getNextRowIndex(poolIndex));
            Assertions.assertEquals(DESTINATION_ROW, pool.getRowIndex(poolIndex));
        }
    }

    @Test
    void relocate_should_keep_word_coordinates_and_mark_images_only_when_position_changes() {
        SpritePool pool = new SpritePool(SINGLE_SLOT_CAPACITY);
        Sprite sprite = pool.allocate();

        Assertions.assertNotNull(sprite);

        sprite.initializeNativeStorage(SPRITE_TYPE, WORD_TEST_X, 0, OWNER, false);
        pool.attachToRow(sprite, FIRST_ROW);

        for (int imageIndex = 0; imageIndex < IMAGE_COUNT; imageIndex++) {
            SpriteImage image = SpriteScenario.createImage(IMAGE_TYPE, 0, 0);

            image.setFlags(imageIndex << DISTINCT_FLAGS_SHIFT);
            sprite.insertTailImage(image);
        }

        pool.relocate(sprite, WRAPPED_WORD_TEST_X, WRAPPED_ZERO_Y, MAP_HEIGHT);

        for (int imageIndex = 0; imageIndex < IMAGE_COUNT; imageIndex++) {
            Assertions.assertEquals(imageIndex << DISTINCT_FLAGS_SHIFT, sprite.getImages().get(imageIndex).getFlags());
        }

        pool.relocate(sprite, ALL_BITS, ALL_BITS, MAP_HEIGHT);

        Assertions.assertEquals(UNSIGNED_WORD_MAXIMUM, sprite.getX());
        Assertions.assertEquals(UNSIGNED_WORD_MAXIMUM, sprite.getY());
        Assertions.assertEquals(FIRST_ROW, pool.getRowIndex(FIRST_POOL_INDEX));

        for (int imageIndex = 0; imageIndex < IMAGE_COUNT; imageIndex++) {
            Assertions.assertEquals((imageIndex << DISTINCT_FLAGS_SHIFT) | REDRAW_FLAG, sprite.getImages().get(imageIndex).getFlags());
        }

        pool.relocate(sprite, MOVED_X, SIGNED_WORD_MAXIMUM, MAP_HEIGHT);

        Assertions.assertEquals(LAST_MAP_ROW, pool.getRowIndex(FIRST_POOL_INDEX));
        Assertions.assertEquals(NO_SPRITE_INDEX, pool.getFirstRowIndex(FIRST_ROW));
        Assertions.assertEquals(FIRST_POOL_INDEX, pool.getFirstRowIndex(LAST_MAP_ROW));
        Assertions.assertEquals(FIRST_POOL_INDEX, pool.getLastRowIndex(LAST_MAP_ROW));
    }

    @Test
    void relocate_should_reject_a_map_height_above_the_row_count_before_any_change() {
        SpritePool pool = new SpritePool(SINGLE_SLOT_CAPACITY);
        Sprite sprite = pool.allocate();

        Assertions.assertNotNull(sprite);

        sprite.initializeNativeStorage(SPRITE_TYPE, OUT_OF_RANGE_START_X, 0, OWNER, false);
        pool.attachToRow(sprite, FIRST_ROW);

        SpriteImage image = SpriteScenario.createImage(IMAGE_TYPE, 0, 0);

        image.setFlags(SpriteImage.FLAG_HAS_DIRECTIONAL_FRAMES);
        sprite.insertTailImage(image);

        Assertions.assertThrows(IllegalArgumentException.class, () -> pool.relocate(sprite, OUT_OF_RANGE_X, OUT_OF_RANGE_Y, OUT_OF_RANGE_MAP_HEIGHT));

        Assertions.assertEquals(OUT_OF_RANGE_START_X, sprite.getX());
        Assertions.assertEquals(0, sprite.getY());
        Assertions.assertEquals(FIRST_ROW, pool.getRowIndex(FIRST_POOL_INDEX));
        Assertions.assertEquals(FIRST_POOL_INDEX, pool.getFirstRowIndex(FIRST_ROW));
        Assertions.assertEquals(DIRECTIONAL_FRAMES_FLAG, image.getFlags());
    }

    @Test
    void free_sprites_serve_and_store_their_previous_links() {
        SpritePool pool = new SpritePool(ClassicSpriteTableRegion.ENTRY_COUNT);
        ClassicAddressSpace space = new ClassicAddressSpace();

        space.add(new ClassicSpriteTableRegion(pool));

        Assertions.assertEquals(0, space.readDword(getSpriteAddress(0)));
        Assertions.assertEquals(getSpriteAddress(0), space.readDword(getSpriteAddress(LAST_SPRITE_ENTRY)));
        Assertions.assertEquals(getSpriteAddress(FREE_TAIL_PREVIOUS_ENTRY), space.readDword(getSpriteAddress(FREE_TAIL_ENTRY)));
        Assertions.assertEquals(FREE_TAIL_ENTRY, pool.getLastFreeIndex());

        space.writeDword(getSpriteAddress(LAST_SPRITE_ENTRY), getSpriteAddress(LINKED_SPRITE_ENTRY));

        Assertions.assertEquals(getSpriteAddress(LINKED_SPRITE_ENTRY), space.readDword(getSpriteAddress(LAST_SPRITE_ENTRY)));
        Assertions.assertEquals(LINKED_SPRITE_ENTRY, pool.getLink(LAST_SPRITE_ENTRY, false));

        space.writeDword(getSpriteAddress(0), getSpriteAddress(LINKED_SPRITE_ENTRY));

        Sprite allocated = pool.allocate();

        Assertions.assertNotNull(allocated);
        Assertions.assertEquals(0, allocated.getPoolIndex());
        Assertions.assertEquals(getSpriteAddress(LAST_SPRITE_ENTRY), space.readDword(getSpriteAddress(LINKED_SPRITE_ENTRY) + NEXT_LINK_OFFSET),
                                "Entry 40 now links to the entry after the removed head.");
        Assertions.assertEquals(getSpriteAddress(LINKED_SPRITE_ENTRY), space.readDword(getSpriteAddress(LAST_SPRITE_ENTRY)));

        pool.recycleFailedInitialization(allocated);

        Assertions.assertEquals(getSpriteAddress(LAST_SPRITE_ENTRY), space.readDword(getSpriteAddress(0)));
        Assertions.assertEquals(getSpriteAddress(LAST_SPRITE_ENTRY - 1), space.readDword(getSpriteAddress(0) + NEXT_LINK_OFFSET));
    }

    @Test
    void expand_free_list_should_insert_new_sprites_after_the_head() {
        SpritePool pool = new SpritePool(SMALL_SPRITE_CAPACITY);
        int first = pool.expandFreeList(EXPANSION_COUNT);

        Assertions.assertEquals(SMALL_SPRITE_CAPACITY, first);
        Assertions.assertEquals(NO_SPRITE_INDEX, pool.getLink(first, false));
        Assertions.assertEquals(first, pool.getLink(first + EXPANSION_COUNT, false), "The last insertion follows the head.");
        Assertions.assertEquals(first + EXPANSION_COUNT, pool.getLink(first + EXPANSION_COUNT - 1, false));
        Assertions.assertEquals(first + 1, pool.getLastFreeIndex(), "The first insertion after the head is the tail.");
    }
}
