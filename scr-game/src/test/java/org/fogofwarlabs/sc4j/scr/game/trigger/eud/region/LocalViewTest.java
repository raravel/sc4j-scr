package org.fogofwarlabs.sc4j.scr.game.trigger.eud.region;

import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks the local view: camera moves, screen and drawn tiles, region reads and writes, address dispatch and address-space
 * write suppression.
 */
final class LocalViewTest {
    /**
     * The mask of the low WORD.
     */
    private static final int WORD_MASK = 0xFFFF;

    private static final int INITIAL_SCREEN_X = 9 << TerrainTileset.TILE_SHIFT;
    private static final int INITIAL_SCREEN_Y = 13 << TerrainTileset.TILE_SHIFT;
    private static final int INITIAL_MOUSE_X = 1234;
    private static final int INITIAL_MOUSE_Y = 5678;
    private static final int LOW_DRAWN_BYTE = 0xAB;
    private static final int HIGH_DRAWN_BYTE = 0xCD;
    private static final int WRITTEN_MOUSE_Y = -123;
    private static final int UNALIGNED_SCREEN_ADDRESS = LocalView.SCREEN_X_ADDRESS + 1;
    private static final int UNMAPPED_MOUSE_ADDRESS = LocalView.MOUSE_ADDRESS + Long.BYTES;
    /**
     * A 640 x 480 screen on a map with room on both axes, a centered point, and the screen it centers: half the width and half
     * the 383-row play view (191) less.
     */
    private static final int CENTER_VIEW_SCREEN_WIDTH = 640;
    private static final int CENTER_VIEW_SCREEN_HEIGHT = 480;
    private static final int CENTER_VIEW_MAXIMUM = 2000;
    private static final int CENTER_VIEW_POINT = 1000;
    private static final int CENTERED_SCREEN_X = 680;
    private static final int CENTERED_SCREEN_Y = 809;
    /**
     * The single-player Center View scroll target of the same point on a 2000-pixel map: half the width and half the whole
     * 480-row screen (240) less.
     */
    private static final int CENTER_VIEW_MAP_SIZE = 2000;
    private static final int SCROLL_TARGET_X = 680;
    private static final int SCROLL_TARGET_Y = 760;
    /**
     * A point near the map end, and the targets one pixel before the last full screen: {@code 2000 - 640 - 1} and
     * {@code 2000 - 480 - 1}.
     */
    private static final int MAP_END_POINT = 1900;
    private static final int MAP_END_TARGET_X = 1359;
    private static final int MAP_END_TARGET_Y = 1519;
    /**
     * A 16 x 12 tile map, smaller than the screen on both axes, and its middle point.
     */
    private static final int SMALL_MAP_WIDTH = 16 << TerrainTileset.TILE_SHIFT;
    private static final int SMALL_MAP_HEIGHT = 12 << TerrainTileset.TILE_SHIFT;
    private static final int SMALL_MAP_CENTER_X = SMALL_MAP_WIDTH / 2;
    private static final int SMALL_MAP_CENTER_Y = SMALL_MAP_HEIGHT / 2;

    private static @NotNull ClassicViewRegion findRegion(@NotNull LocalView view, int address) {
        for (ClassicViewRegion region : ClassicViewRegion.create(view)) {
            if (region.getBase() == address) {
                return region;
            }
        }

        return Assertions.fail("The local view factory did not provide the requested region.");
    }


    @ParameterizedTest
    @ValueSource(ints = {LocalView.DRAWN_TILE_X_ADDRESS, LocalView.DRAWN_TILE_Y_ADDRESS})
    void classic_view_region_stores_drawn_tile_bytes_without_changing_mouse(int address) {
        LocalView view = new LocalView();
        ClassicViewRegion region = findRegion(view, address);

        view.setView(INITIAL_SCREEN_X, INITIAL_SCREEN_Y, INITIAL_MOUSE_X, INITIAL_MOUSE_Y);

        region.writeByte(0, LOW_DRAWN_BYTE);
        region.writeByte(1, HIGH_DRAWN_BYTE);

        Assertions.assertEquals(LOW_DRAWN_BYTE, region.readByte(0));
        Assertions.assertEquals(HIGH_DRAWN_BYTE, region.readByte(1));
        Assertions.assertEquals(INITIAL_MOUSE_X, view.getMouseX());
        Assertions.assertEquals(INITIAL_MOUSE_Y, view.getMouseY());
        Assertions.assertTrue(view.takeWritten());
        Assertions.assertFalse(view.takeWritten());
    }

    @Test
    void classic_view_region_stores_mouse_y_without_changing_mouse_x() {
        LocalView view = new LocalView();
        ClassicViewRegion region = findRegion(view, LocalView.MOUSE_ADDRESS);

        view.setView(INITIAL_SCREEN_X, INITIAL_SCREEN_Y, INITIAL_MOUSE_X, INITIAL_MOUSE_Y);

        region.writeDword(Integer.BYTES, WRITTEN_MOUSE_Y);

        Assertions.assertEquals(INITIAL_MOUSE_X, view.getMouseX());
        Assertions.assertEquals(WRITTEN_MOUSE_Y, view.getMouseY());
        Assertions.assertEquals(INITIAL_SCREEN_X, view.getScreenX());
        Assertions.assertEquals(INITIAL_SCREEN_Y, view.getScreenY());
        Assertions.assertTrue(view.takeWritten());
    }

    @ParameterizedTest
    @ValueSource(ints = {UNALIGNED_SCREEN_ADDRESS, UNMAPPED_MOUSE_ADDRESS})
    void local_view_rejects_unknown_reads_without_changing_state(int address) {
        LocalView view = new LocalView();

        view.setView(INITIAL_SCREEN_X, INITIAL_SCREEN_Y, INITIAL_MOUSE_X, INITIAL_MOUSE_Y);

        Assertions.assertThrows(UnsupportedOperationException.class, () -> view.read(address));
        Assertions.assertEquals(INITIAL_SCREEN_X, view.getScreenX());
        Assertions.assertEquals(INITIAL_SCREEN_Y, view.getScreenY());
        Assertions.assertEquals(INITIAL_MOUSE_X, view.getMouseX());
        Assertions.assertEquals(INITIAL_MOUSE_Y, view.getMouseY());
        Assertions.assertFalse(view.takeWritten());
    }

    @ParameterizedTest
    @ValueSource(ints = {UNALIGNED_SCREEN_ADDRESS, UNMAPPED_MOUSE_ADDRESS})
    void local_view_rejects_unknown_writes_without_changing_state(int address) {
        LocalView view = new LocalView();

        view.setView(INITIAL_SCREEN_X, INITIAL_SCREEN_Y, INITIAL_MOUSE_X, INITIAL_MOUSE_Y);

        Assertions.assertThrows(UnsupportedOperationException.class, () -> view.write(address, WRITTEN_MOUSE_Y));
        Assertions.assertEquals(INITIAL_SCREEN_X, view.getScreenX());
        Assertions.assertEquals(INITIAL_SCREEN_Y, view.getScreenY());
        Assertions.assertEquals(INITIAL_MOUSE_X, view.getMouseX());
        Assertions.assertEquals(INITIAL_MOUSE_Y, view.getMouseY());
        Assertions.assertFalse(view.takeWritten());
    }

    @ParameterizedTest
    @ValueSource(ints = {LocalView.PREVIOUS_SCREEN_X_ADDRESS, LocalView.PREVIOUS_SCREEN_Y_ADDRESS,
                         LocalView.SCREEN_X_ADDRESS, LocalView.SCREEN_Y_ADDRESS,
                         LocalView.DRAWN_TILE_X_ADDRESS, LocalView.DRAWN_TILE_Y_ADDRESS})
    void address_space_ignores_screen_and_drawn_tile_writes_before_view_is_supplied(int address) {
        LocalView view = new LocalView();
        ClassicAddressSpace addressSpace = new ClassicAddressSpace();

        for (ClassicViewRegion region : ClassicViewRegion.create(view)) {
            addressSpace.add(region);
        }

        addressSpace.writeDword(address, WRITTEN_MOUSE_Y);

        Assertions.assertFalse(view.isKnown());
        Assertions.assertFalse(view.takeWritten());
    }

    @ParameterizedTest
    @ValueSource(ints = {LocalView.DRAWN_TILE_X_ADDRESS, LocalView.DRAWN_TILE_Y_ADDRESS})
    void classic_view_region_rejects_drawn_tile_reads_and_writes_before_view_is_supplied(int address) {
        LocalView view = new LocalView();
        ClassicViewRegion region = findRegion(view, address);

        Assertions.assertThrows(UnsupportedOperationException.class, () -> region.readByte(0));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> region.writeByte(0, LOW_DRAWN_BYTE));
        Assertions.assertFalse(view.isKnown());
        Assertions.assertFalse(view.takeWritten());
    }

    @Test
    void local_view_is_a_local_input_and_ignores_screen_writes() {
        LocalView view = new LocalView();
        ClassicAddressSpace space = new ClassicAddressSpace();

        for (ClassicViewRegion region : ClassicViewRegion.create(view)) {
            space.add(region);
        }

        Assertions.assertThrows(UnsupportedOperationException.class, () -> space.readDword(LocalView.SCREEN_X_ADDRESS));

        view.setView(640, 320, 12, 34);

        Assertions.assertEquals(320, space.readDword(LocalView.SCREEN_Y_ADDRESS));
        Assertions.assertEquals(34, space.readDword(LocalView.MOUSE_ADDRESS + 4));
        Assertions.assertFalse(view.takeWritten());

        space.writeDword(LocalView.SCREEN_X_ADDRESS, 1024);

        Assertions.assertFalse(view.takeWritten(), "The screen regions accept the write without change.");
        Assertions.assertEquals(640, view.getScreenX());
    }

    @Test
    void first_view_starts_the_alert_position_and_camera_moves_store_the_moved_screen() {
        LocalView view = new LocalView();
        ClassicAddressSpace space = new ClassicAddressSpace();

        for (ClassicViewRegion region : ClassicViewRegion.create(view)) {
            space.add(region);
        }

        view.setView(640, 320, 12, 34);

        Assertions.assertEquals(640, view.getAlertScreenX(), "The interface start copies the screen into the alert position.");
        Assertions.assertEquals(320, view.getAlertScreenY());
        Assertions.assertEquals(640, space.readDword(LocalView.PREVIOUS_SCREEN_X_ADDRESS), "The camera move stores the moved position.");

        view.setAlertScreen(5, 6);
        view.setView(700, 320, 12, 34);

        Assertions.assertEquals(5, view.getAlertScreenX(), "Only the interface start copies the screen.");
        Assertions.assertEquals(700, space.readDword(LocalView.PREVIOUS_SCREEN_X_ADDRESS));
        Assertions.assertEquals(320, space.readDword(LocalView.PREVIOUS_SCREEN_Y_ADDRESS));

        view.setScreenGeometry(640, 480, 2000, 1000);

        Assertions.assertTrue(view.centerScreen(100, 5000), "Centering clamps: negative to 0, past the maximum to the maximum.");
        Assertions.assertEquals(0, view.getScreenX());
        Assertions.assertEquals(1000, view.getScreenY());
        Assertions.assertEquals(1000, space.readDword(LocalView.PREVIOUS_SCREEN_Y_ADDRESS));
        Assertions.assertTrue(view.takeWritten(), "The interface follows the move.");
    }

    @Test
    void center_screen_should_center_the_point_in_the_play_view_above_the_console() {
        LocalView view = new LocalView();

        view.setView(0, 0, 0, 0);
        view.setScreenGeometry(CENTER_VIEW_SCREEN_WIDTH, CENTER_VIEW_SCREEN_HEIGHT, CENTER_VIEW_MAXIMUM, CENTER_VIEW_MAXIMUM);

        Assertions.assertTrue(view.centerScreen(CENTER_VIEW_POINT, CENTER_VIEW_POINT));
        Assertions.assertEquals(CENTERED_SCREEN_X, view.getScreenX());
        Assertions.assertEquals(CENTERED_SCREEN_Y, view.getScreenY());
    }

    @Test
    void move_screen_should_place_the_top_left_clamped_to_the_map() {
        LocalView view = new LocalView();
        ClassicAddressSpace space = new ClassicAddressSpace();

        for (ClassicViewRegion region : ClassicViewRegion.create(view)) {
            space.add(region);
        }

        Assertions.assertFalse(view.moveScreen(64, 64), "The view is unknown.");

        view.setView(0, 0, 10, 20);

        Assertions.assertFalse(view.moveScreen(64, 64), "The display geometry is unknown.");

        view.setScreenGeometry(640, 480, 2000, 1000);
        view.takeWritten();

        Assertions.assertTrue(view.moveScreen(96, 5000));
        Assertions.assertEquals(96, view.getScreenX());
        Assertions.assertEquals(1000, view.getScreenY(), "A position past the maximum is clamped to it.");
        Assertions.assertEquals(96, space.readDword(LocalView.PREVIOUS_SCREEN_X_ADDRESS));
        Assertions.assertEquals(96 >> TerrainTileset.TILE_SHIFT | (1000 >> TerrainTileset.TILE_SHIFT) << Short.SIZE, view.readScreenTiles());
        Assertions.assertEquals(10, view.getMouseX(), "The pointer stays.");
        Assertions.assertTrue(view.takeWritten(), "The interface follows the move.");

        Assertions.assertTrue(view.moveScreen(-32, 0));
        Assertions.assertEquals(0, view.getScreenX(), "A negative position is clamped to 0.");
    }

    @Test
    void center_view_scroll_target_centers_the_point_in_the_whole_screen() {
        LocalView view = new LocalView();

        view.setScreenGeometry(CENTER_VIEW_SCREEN_WIDTH, CENTER_VIEW_SCREEN_HEIGHT, CENTER_VIEW_MAXIMUM, CENTER_VIEW_MAXIMUM);

        Assertions.assertEquals(SCROLL_TARGET_X, view.calculateScrollTargetX(CENTER_VIEW_POINT, CENTER_VIEW_MAP_SIZE));
        Assertions.assertEquals(SCROLL_TARGET_Y, view.calculateScrollTargetY(CENTER_VIEW_POINT, CENTER_VIEW_MAP_SIZE));
    }

    @Test
    void center_view_scroll_target_stops_before_the_map_end_and_at_zero() {
        LocalView view = new LocalView();

        view.setScreenGeometry(CENTER_VIEW_SCREEN_WIDTH, CENTER_VIEW_SCREEN_HEIGHT, CENTER_VIEW_MAXIMUM, CENTER_VIEW_MAXIMUM);

        Assertions.assertEquals(MAP_END_TARGET_X, view.calculateScrollTargetX(MAP_END_POINT, CENTER_VIEW_MAP_SIZE));
        Assertions.assertEquals(MAP_END_TARGET_Y, view.calculateScrollTargetY(MAP_END_POINT, CENTER_VIEW_MAP_SIZE));
        // A target before the map start stays at zero even where the screen is larger than the map.
        Assertions.assertEquals(0, view.calculateScrollTargetX(SMALL_MAP_CENTER_X, SMALL_MAP_WIDTH));
        Assertions.assertEquals(0, view.calculateScrollTargetY(SMALL_MAP_CENTER_Y, SMALL_MAP_HEIGHT));
    }

    @Test
    void screen_tile_words_follow_only_camera_moves() {
        LocalView view = new LocalView();

        view.setView(320, 96, 0, 0);

        Assertions.assertEquals(10 | 3 << 16, view.readScreenTiles(), "The screen tiles are the screen pixels shifted right by five.");

        view.writeScreenTiles(7 | 8 << 16);
        view.setView(320, 96, 5, 5);

        Assertions.assertEquals(7 | 8 << 16, view.readScreenTiles(), "Without a camera move the written tile stays.");

        view.setView(640, 96, 5, 5);

        Assertions.assertEquals(20 | 8 << 16, view.readScreenTiles());
    }

    @Test
    void drawn_screen_tile_words_follow_the_context_tile_on_the_next_frame() {
        LocalView view = new LocalView();
        ClassicAddressSpace space = new ClassicAddressSpace();

        for (ClassicViewRegion region : ClassicViewRegion.create(view)) {
            space.add(region);
        }

        Assertions.assertThrows(UnsupportedOperationException.class, () -> space.readDword(LocalView.DRAWN_TILE_X_ADDRESS));

        view.setView(1304, 1008, 320, 191);

        Assertions.assertEquals(40, space.readDword(LocalView.DRAWN_TILE_X_ADDRESS) & WORD_MASK, "Drawing reaches the context tile.");
        Assertions.assertEquals(31, space.readDword(LocalView.DRAWN_TILE_Y_ADDRESS) & WORD_MASK);

        view.writeScreenTiles(7 | 8 << 16);

        Assertions.assertEquals(40, space.readDword(LocalView.DRAWN_TILE_X_ADDRESS) & WORD_MASK, "Drawing happens once per frame.");

        view.setView(1304, 1008, 320, 191);

        Assertions.assertEquals(7, space.readDword(LocalView.DRAWN_TILE_X_ADDRESS) & WORD_MASK);
        Assertions.assertEquals(8, space.readDword(LocalView.DRAWN_TILE_Y_ADDRESS) & WORD_MASK);

        space.writeDword(LocalView.DRAWN_TILE_X_ADDRESS, 99);

        Assertions.assertEquals(7, space.readDword(LocalView.DRAWN_TILE_X_ADDRESS) & WORD_MASK, "Type 2: the write is ignored.");
    }
}
