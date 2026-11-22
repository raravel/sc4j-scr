package org.fogofwarlabs.sc4j.scr.client.world;

import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.LetterboxFit;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.host.PointerClip;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceTransform;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies the client's single screen geometry ({@link GameViewport}) at native and tall window sizes and on surfaces of display scale 1.5 and 2.
 */
final class GameViewportTest {
    private static final float NATIVE_ROWS = 480f;
    private static final int PLAY_VIEW_ROWS = 383;

    private static final int TALL_WINDOW_WIDTH = 960;
    private static final int TALL_WINDOW_HEIGHT = 1000;
    private static final int WIDE_WINDOW_WIDTH = 1920;
    private static final int WIDE_WINDOW_HEIGHT = 1080;
    private static final int MAP_SIZE = 128 * 32;

    private static final float SCALE_TOLERANCE = 1e-6f;
    private static final float FINE_TOLERANCE = 1e-4f;
    private static final float PIXEL_TOLERANCE = 1e-3f;
    private static final float CAMERA_TOLERANCE = 1e-2f;
    private static final int ROUNDING_TOLERANCE = 1;
    private static final float HALF = 2f;

    /**
     * Framebuffer sizes of the scaled surfaces: 16:9, 16:10, 4:3, a tall window and a size whose logical size is rounded.
     */
    private static final int @NotNull [] @NotNull [] SCALED_SIZES = {{2560, 1440}, {1920, 1080}, {2880, 1800}, {1920, 1440}, {1500, 1700}, {2001, 1333}};
    private static final float @NotNull [] DISPLAY_SCALES = {1.5f, 2f};

    private static @NotNull GameViewport createViewport(int width, int height, boolean highDefinition, boolean console) {
        return createViewport(UiSurface.of(width, height, DisplayScale.ONE), highDefinition, console);
    }

    private static @NotNull GameViewport createViewport(@NotNull UiSurface surface, boolean highDefinition, boolean console) {
        GameViewport viewport = new GameViewport();

        viewport.setSurface(surface);
        viewport.setInterface(highDefinition, console, false);

        return viewport;
    }

    private static void assertClip(float left, float top, float right, float bottom, @NotNull PointerClip clip, float tolerance) {
        Assertions.assertEquals(left, clip.getLeft(), tolerance);
        Assertions.assertEquals(top, clip.getTop(), tolerance);
        Assertions.assertEquals(right, clip.getRight(), tolerance);
        Assertions.assertEquals(bottom, clip.getBottom(), tolerance);
    }


    @Test
    void native_aspect_ratios_keep_native_geometry_without_view_extension() {
        int[][] sizes = {{1280, 800}, {1920, 1080}, {1280, 960}, {2560, 1080}};

        for (int[] size : sizes) {
            for (boolean highDefinition : new boolean[]{false, true}) {
                GameViewport viewport = createViewport(size[0], size[1], highDefinition, true);
                String label = size[0] + "x" + size[1] + (highDefinition ? " HD" : " SD");

                Assertions.assertEquals(0, viewport.getViewExtension(), label);
                // 480 game rows in the window height.
                Assertions.assertEquals(size[1] / NATIVE_ROWS, viewport.getNativeZoom(), SCALE_TOLERANCE, label);
                // The play view ends 383 interface rows down, as viewH = 383 * height / 480.
                Assertions.assertEquals(PLAY_VIEW_ROWS * size[1] / NATIVE_ROWS, viewport.getPlayRows(), FINE_TOLERANCE, label);
                // The edge rows equal the interface rows.
                Assertions.assertEquals(0, viewport.toEdgeInterfaceY(0), FINE_TOLERANCE, label);
                Assertions.assertEquals(479, viewport.toEdgeInterfaceY(479 * size[1] / NATIVE_ROWS), PIXEL_TOLERANCE, label);
                Assertions.assertEquals(viewport.toEdgeInterfaceY(size[1] - 1), viewport.toSurfaceY(size[1] - 1), FINE_TOLERANCE, label);
            }
        }

        // 1920x1080 HD: W = 1920, no pillarbox; SD: 640 columns centered.
        GameViewport highDefinitionViewport = createViewport(WIDE_WINDOW_WIDTH, WIDE_WINDOW_HEIGHT, true, true);

        Assertions.assertEquals(853, highDefinitionViewport.getInterfaceWidth());
        Assertions.assertEquals(0, highDefinitionViewport.getGameLeft(), ROUNDING_TOLERANCE);

        GameViewport standardDefinitionViewport = createViewport(WIDE_WINDOW_WIDTH, WIDE_WINDOW_HEIGHT, false, true);

        Assertions.assertEquals(640, standardDefinitionViewport.getInterfaceWidth());
        Assertions.assertEquals(240, standardDefinitionViewport.getGameLeft(), PIXEL_TOLERANCE);
        Assertions.assertEquals(1680, standardDefinitionViewport.getGameRight(), PIXEL_TOLERANCE);

        // An ultrawide window pillarboxes HD at 16:9.
        GameViewport ultrawide = createViewport(2560, 1080, true, true);

        Assertions.assertEquals(853, ultrawide.getInterfaceWidth());
        Assertions.assertEquals((2560 - 853 * 2.25f) / 2, ultrawide.getGameLeft(), PIXEL_TOLERANCE);
    }

    @Test
    void tall_window_extends_view_above_interface() {
        GameViewport viewport = createViewport(TALL_WINDOW_WIDTH, TALL_WINDOW_HEIGHT, true, true);

        // W = 640 (4:3 is the narrowest), fitted to the width: 1.5, standing on the bottom edge.
        Assertions.assertEquals(640, viewport.getInterfaceWidth());
        Assertions.assertEquals(1.5f, viewport.getScale(), SCALE_TOLERANCE);
        Assertions.assertEquals(1000 - 720, viewport.getInterfaceTop(), PIXEL_TOLERANCE);
        Assertions.assertEquals(280, viewport.getViewExtension(), PIXEL_TOLERANCE);
        Assertions.assertEquals(1.5f, viewport.getNativeZoom(), SCALE_TOLERANCE);
        Assertions.assertEquals(0, viewport.getGameLeft(), PIXEL_TOLERANCE);
        Assertions.assertEquals(960, viewport.getGameRight(), PIXEL_TOLERANCE);
        // The play view reaches the console's upper band: 280 + 383 * 1.5.
        Assertions.assertEquals(280 + PLAY_VIEW_ROWS * 1.5f, viewport.getPlayRows(), PIXEL_TOLERANCE);
        // The top edge band is at the window top, not at the interface's top.
        Assertions.assertEquals(0, viewport.toEdgeInterfaceY(0), FINE_TOLERANCE);
        Assertions.assertTrue(viewport.toEdgeInterfaceY(150) > 16, "a row inside the view is no edge");
        Assertions.assertEquals(479, viewport.toEdgeInterfaceY(999 - 0.5f), ROUNDING_TOLERANCE);
        // The surface mouse counts from the view's top edge.
        Assertions.assertEquals(100, viewport.toSurfaceY(150), FINE_TOLERANCE);
    }

    @Test
    void to_screen_x_and_y_should_place_the_game_screen_from_the_view_top() {
        // 1920x1080 HD: the 853-wide interface at 2.25, centered 0.375 pixels from the left, row 0 at the window top.
        GameViewport wide = createViewport(WIDE_WINDOW_WIDTH, WIDE_WINDOW_HEIGHT, true, true);

        Assertions.assertEquals(22.875f, wide.toScreenX(10), FINE_TOLERANCE);
        Assertions.assertEquals(22.5f, wide.toScreenY(10), FINE_TOLERANCE);

        // A tall window: the interface stands on the bottom edge, and row 0 of the game screen stays at the view's top.
        GameViewport tall = createViewport(TALL_WINDOW_WIDTH, TALL_WINDOW_HEIGHT, true, true);

        Assertions.assertEquals(15, tall.toScreenX(10), FINE_TOLERANCE);
        Assertions.assertEquals(15, tall.toScreenY(10), FINE_TOLERANCE);
        Assertions.assertEquals(100, tall.toSurfaceY(tall.toScreenY(100)), FINE_TOLERANCE);
    }

    @Test
    void to_screen_y_should_equal_the_bottom_placement_moved_up_by_the_view_extension() {
        // The console stands on the bottom edge; its top-anchored elements keep their rows bit for bit, also where the view
        // extension has a fraction (1001x1400, 1366x2000).
        int[][] sizes = {{1001, 1400}, {1366, 2000}, {960, 1000}, {1920, 1080}};

        for (int[] size : sizes) {
            GameViewport viewport = createViewport(size[0], size[1], true, true);

            for (int row = 0; row < GameViewport.INTERFACE_HEIGHT; row++) {
                float bottomPlacement = viewport.getInterfaceTop() + row * viewport.getScale();
                float expected = bottomPlacement - viewport.getViewExtension();

                Assertions.assertEquals(Float.floatToRawIntBits(expected),
                                        Float.floatToRawIntBits(viewport.toScreenY(row)),
                                        size[0] + "x" + size[1] + " row " + row);
            }
        }
    }

    @Test
    void to_interface_x_and_y_should_invert_to_screen_x_and_y_at_any_window() {
        int[][] sizes = {{640, 480}, {1920, 1080}, {960, 1000}, {1366, 2000}, {2560, 1080}};

        for (int[] size : sizes) {
            GameViewport viewport = createViewport(size[0], size[1], true, true);
            String label = size[0] + "x" + size[1];

            for (int unit = 0; unit <= GameViewport.INTERFACE_HEIGHT; unit += GameViewport.INTERFACE_HEIGHT / 4) {
                Assertions.assertEquals(unit, viewport.toInterfaceX(viewport.toScreenX(unit)), PIXEL_TOLERANCE, label + " column " + unit);
                Assertions.assertEquals(unit, viewport.toInterfaceY(viewport.toScreenY(unit)), PIXEL_TOLERANCE, label + " row " + unit);
            }
        }
    }

    @Test
    void camera_limits_follow_game_view_at_any_zoom() {
        int mapWidth = MAP_SIZE;
        int mapHeight = MAP_SIZE;

        for (float zoom : new float[]{0.5f, 1.5f, 2.25f}) {
            GameViewport tall = createViewport(TALL_WINDOW_WIDTH, TALL_WINDOW_HEIGHT, true, true);

            Assertions.assertEquals(0, tall.getMinimumCameraX(zoom), FINE_TOLERANCE);
            Assertions.assertEquals(mapWidth - TALL_WINDOW_WIDTH / zoom, tall.getMaximumCameraX(zoom, mapWidth), PIXEL_TOLERANCE);
            Assertions.assertEquals(mapHeight - tall.getPlayRows() / zoom, tall.getMaximumCameraY(zoom, mapHeight), PIXEL_TOLERANCE);
            Assertions.assertEquals(Math.round(TALL_WINDOW_WIDTH / zoom), tall.getViewTileWidth(zoom));
            Assertions.assertEquals(Math.round(TALL_WINDOW_HEIGHT / zoom), tall.getViewTileHeight(zoom));

            GameViewport standardDefinitionViewport = createViewport(WIDE_WINDOW_WIDTH, WIDE_WINDOW_HEIGHT, false, true);

            Assertions.assertEquals(-240 / zoom, standardDefinitionViewport.getMinimumCameraX(zoom), PIXEL_TOLERANCE);
            Assertions.assertEquals(mapWidth - 1680 / zoom, standardDefinitionViewport.getMaximumCameraX(zoom, mapWidth), PIXEL_TOLERANCE);
        }

        // A camera on the tall viewport reaches the map's top and stops at its bottom above the console.
        GameViewport viewport = createViewport(TALL_WINDOW_WIDTH, TALL_WINDOW_HEIGHT, true, true);
        ClientCamera camera = new ClientCamera(mapWidth, mapHeight);

        camera.setViewport(viewport);
        camera.setNativeZoom();
        camera.pan(0, -1_000_000);

        Assertions.assertEquals(0, camera.getY(), FINE_TOLERANCE);

        camera.pan(0, 1_000_000);

        Assertions.assertEquals(mapHeight, camera.screenToWorldY(viewport.getPlayRows()), CAMERA_TOLERANCE);
    }

    @Test
    void set_surface_should_fit_the_interface_to_the_framebuffer_at_one_pixel_per_unit() {
        // At one pixel per logical unit the fit on the surface is the fit to the framebuffer, bit for bit: scale min(w / W, h / 480), centred
        // across, standing on the bottom edge.
        int[][] sizes = {{1280, 720}, {1600, 900}, {1280, 800}, {1001, 1400}, {1366, 2000}, {2560, 1080}, {640, 480}, {1920, 1080}};

        for (int[] size : sizes) {
            for (boolean highDefinition : new boolean[]{false, true}) {
                GameViewport viewport = createViewport(size[0], size[1], highDefinition, true);
                String label = size[0] + "x" + size[1] + (highDefinition ? " HD" : " SD");
                float width = size[0];
                float height = size[1];
                int interfaceWidth = InterfaceTransform.getInterfaceWidth(highDefinition, width, height);
                float scale = Math.min(width / interfaceWidth, height / GameViewport.INTERFACE_HEIGHT);

                Assertions.assertEquals(interfaceWidth, viewport.getInterfaceWidth(), label);
                Assertions.assertEquals(Float.floatToRawIntBits(scale), Float.floatToRawIntBits(viewport.getScale()), label);
                Assertions.assertEquals(Float.floatToRawIntBits((width - interfaceWidth * scale) / HALF),
                                        Float.floatToRawIntBits(viewport.toScreenX(0)), label);
                Assertions.assertEquals(Float.floatToRawIntBits(height - GameViewport.INTERFACE_HEIGHT * scale),
                                        Float.floatToRawIntBits(viewport.getInterfaceTop()), label);
            }
        }
    }

    @Test
    void set_surface_should_fit_the_interface_into_the_logical_area_of_a_scaled_surface() {
        for (float displayScale : DISPLAY_SCALES) {
            for (int[] size : SCALED_SIZES) {
                for (boolean highDefinition : new boolean[]{false, true}) {
                    UiSurface surface = UiSurface.of(size[0], size[1], DisplayScale.of(displayScale, 1));
                    GameViewport viewport = createViewport(surface, highDefinition, true);
                    GameViewport unscaled = createViewport(size[0], size[1], highDefinition, true);
                    String label = size[0] + "x" + size[1] + " at " + displayScale + (highDefinition ? " HD" : " SD");
                    LetterboxFit fit = LetterboxFit.of(surface, viewport.getInterfaceWidth(), GameViewport.INTERFACE_HEIGHT, LetterboxFit.CENTER,
                                                       LetterboxFit.END);

                    // The interface is laid out on the logical area and drawn at the display scale.
                    Assertions.assertEquals(unscaled.getInterfaceWidth(), viewport.getInterfaceWidth(), label);
                    Assertions.assertEquals(fit.getScale() * displayScale, viewport.getScale(), SCALE_TOLERANCE, label);
                    Assertions.assertEquals(fit.getLeft() * displayScale, viewport.toScreenX(0), FINE_TOLERANCE, label);
                    Assertions.assertEquals(fit.getTop() * displayScale, viewport.getInterfaceTop(), FINE_TOLERANCE, label);
                    // In framebuffer pixels it is the native fit to the framebuffer: the game view and the camera keep the framebuffer's
                    // geometry, bit for bit at the whole display scale 2.
                    Assertions.assertEquals(size[0], viewport.getWindowWidth(), label);
                    Assertions.assertEquals(size[1], viewport.getWindowHeight(), label);

                    if (displayScale == DISPLAY_SCALES[1]) {
                        Assertions.assertEquals(Float.floatToRawIntBits(unscaled.getScale()),
                                                Float.floatToRawIntBits(viewport.getScale()), label);
                        Assertions.assertEquals(Float.floatToRawIntBits(unscaled.toScreenX(0)),
                                                Float.floatToRawIntBits(viewport.toScreenX(0)), label);
                        Assertions.assertEquals(Float.floatToRawIntBits(unscaled.getInterfaceTop()),
                                                Float.floatToRawIntBits(viewport.getInterfaceTop()), label);
                    }

                    Assertions.assertEquals(unscaled.getScale(), viewport.getScale(), SCALE_TOLERANCE, label);
                    Assertions.assertEquals(unscaled.getNativeZoom(), viewport.getNativeZoom(), SCALE_TOLERANCE, label);
                    Assertions.assertEquals(unscaled.getGameLeft(), viewport.getGameLeft(), PIXEL_TOLERANCE, label);
                    Assertions.assertEquals(unscaled.getPlayRows(), viewport.getPlayRows(), PIXEL_TOLERANCE, label);
                }
            }
        }
    }

    @Test
    void to_interface_x_should_map_framebuffer_pointers_through_the_logical_fit() {
        for (float displayScale : DISPLAY_SCALES) {
            for (int[] size : SCALED_SIZES) {
                UiSurface surface = UiSurface.of(size[0], size[1], DisplayScale.of(displayScale, 1));
                GameViewport viewport = createViewport(surface, true, true);
                LetterboxFit fit = LetterboxFit.of(surface, viewport.getInterfaceWidth(), GameViewport.INTERFACE_HEIGHT, LetterboxFit.CENTER,
                                                   LetterboxFit.END);
                String label = size[0] + "x" + size[1] + " at " + displayScale;

                // A framebuffer pixel is a logical position of the fit's area (framebuffer / display scale), mapped to the design area.
                for (float pointer : new float[]{0, 0.5f, size[0] / HALF, size[0] - 1}) {
                    float expected = fit.toDesignX(pointer / displayScale);

                    Assertions.assertEquals(expected, viewport.toInterfaceX(pointer), FINE_TOLERANCE, label + " column " + pointer);
                }

                // Without a view extension the surface row is the design row of the fit.
                if (viewport.getViewExtension() == 0) {
                    for (float pointer : new float[]{0, size[1] / HALF, size[1] - 1}) {
                        float expected = fit.toDesignY(pointer / displayScale);

                        Assertions.assertEquals(expected, viewport.toSurfaceY(pointer), FINE_TOLERANCE, label + " row " + pointer);
                    }
                }

                // A pointer on an interface pixel's framebuffer position maps back to that pixel.
                Assertions.assertEquals(100, viewport.toInterfaceX(viewport.toScreenX(100)), FINE_TOLERANCE, label);
            }
        }
    }

    @Test
    void screen_clip_should_be_the_pillarboxed_frame_and_follow_a_resize() {
        // The classic 4:3 frame in a 1920x1080 window: the bars of 240 columns on each side are outside the cursor clip.
        GameViewport viewport = createViewport(WIDE_WINDOW_WIDTH, WIDE_WINDOW_HEIGHT, false, true);

        assertClip(240, 0, 1680, 1080, viewport.getScreenClip(), PIXEL_TOLERANCE);

        // A resize to 1600x900 moves the clip with the frame.
        viewport.setSurface(UiSurface.of(1600, 900, DisplayScale.ONE));

        assertClip(200, 0, 1400, 900, viewport.getScreenClip(), PIXEL_TOLERANCE);

        // The widescreen interface fills the 16:9 window, so the clip is the whole window.
        viewport.setInterface(true, true, false);

        assertClip(0, 0, 1600, 900, viewport.getScreenClip(), ROUNDING_TOLERANCE);

        // A window narrower than 4:3 shows the frame over the whole width.
        viewport.setSurface(UiSurface.of(TALL_WINDOW_WIDTH, TALL_WINDOW_HEIGHT, DisplayScale.ONE));

        assertClip(0, 0, TALL_WINDOW_WIDTH, TALL_WINDOW_HEIGHT, viewport.getScreenClip(), PIXEL_TOLERANCE);
    }

    @Test
    void play_clip_should_end_at_the_play_view_rows_of_the_game_screen() {
        // The selection box clip of the 4:3 frame in a 1920x1080 window: the game screen columns and the 383 of 480 rows above the console.
        GameViewport viewport = createViewport(WIDE_WINDOW_WIDTH, WIDE_WINDOW_HEIGHT, false, true);

        assertClip(240, 0, 1680, PLAY_VIEW_ROWS * WIDE_WINDOW_HEIGHT / NATIVE_ROWS, viewport.getPlayClip(), PIXEL_TOLERANCE);

        // The replay console's full-height play view and a hidden console leave the whole game screen.
        viewport.setInterface(false, true, true);

        assertClip(240, 0, 1680, WIDE_WINDOW_HEIGHT, viewport.getPlayClip(), PIXEL_TOLERANCE);

        viewport.setInterface(false, false, false);

        assertClip(0, 0, WIDE_WINDOW_WIDTH, WIDE_WINDOW_HEIGHT, viewport.getPlayClip(), PIXEL_TOLERANCE);
    }

    @Test
    void game_view_fills_window_without_console() {
        GameViewport viewport = createViewport(1280, 800, true, false);

        Assertions.assertEquals(0, viewport.getGameLeft());
        Assertions.assertEquals(1280, viewport.getGameRight());
        Assertions.assertEquals(800, viewport.getPlayRows());
    }
}
