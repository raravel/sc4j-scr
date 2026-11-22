package org.fogofwarlabs.sc4j.scr.render;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

/**
 * Tests that {@link WorldRenderer} numbers its creep edge frames, takes the unit images, frame counts and frozen placement of the loaded map and
 * rejects missing frames, unlit glow images and draws outside a frame on a hidden context.
 */
final class WorldRendererTest {
    /**
     * The system property that enables the GPU checks and the message of a skipped check.
     */
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    /**
     * The width and height of the hidden window in pixels.
     */
    private static final int WIDTH = 96;
    private static final int HEIGHT = 80;
    /**
     * The Badlands and Jungle tilesets and a tile identifier (CV5 group {@code 2}, megatile {@code 0}).
     */
    private static final int BADLANDS_TILESET = 0;
    private static final int JUNGLE_TILESET = 4;
    private static final int TILE = 0x20;

    /**
     * The Marine shadow image.
     */
    private static final int MARINE_SHADOW = 240;
    /**
     * The player color of the drawn units.
     */
    private static final int PLAYER_COLOR = 1;
    /**
     * The world position of the rejected frame draws.
     */
    private static final int REJECTED_DRAW_X = 32;
    private static final int REJECTED_DRAW_Y = 32;

    /**
     * A three by three tile map and the window of the frozen image and glow checks.
     */
    private static final int MAP_TILES = 3;
    private static final int SIZE = MAP_TILES * TerrainTileset.TILE_SIZE;
    /**
     * The frames of the Marine image and the frozen frame-rectangle tops of the frozen image checks.
     */
    private static final int @NotNull [] DRAWN_FRAMES = {0, 5, 13};
    private static final int @NotNull [] FROZEN_TOPS = {20, 37};
    /**
     * The frozen top queried before a map is loaded, and a frame index past the Marine frames.
     */
    private static final int UNLOADED_FROZEN_TOP = 10;
    private static final int MISSING_FRAME = 100000;

    /**
     * The neighbors of a tile, one creep edge mask bit each.
     */
    private static final int NEIGHBOR_COUNT = 8;
    /**
     * The frames of the tileset creep GRP, and an exclusive bound of the edge frame numbers.
     */
    private static final int CREEP_EDGE_FRAME_COUNT = 37;
    private static final int EDGE_FRAME_LIMIT = 64;
    /**
     * The mask bits of the neighbors.
     */
    private static final int LOWER_RIGHT_NEIGHBOR = 0x01;
    private static final int LOWER_NEIGHBOR = 0x02;
    private static final int LOWER_LEFT_NEIGHBOR = 0x04;
    private static final int LEFT_NEIGHBOR = 0x10;
    private static final int UPPER_NEIGHBOR = 0x40;
    private static final int UPPER_LEFT_NEIGHBOR = 0x80;

    /**
     * The mining spark, a glow image of draw function {@code 9}, and an image whose color shift selects no lighting table.
     */
    private static final int MINING_SPARK = 520;
    private static final int UNLIT_IMAGE = 0;

    /**
     * The Kakaru image ({@code unit/neutral/Kcritter.grp}), the frame count of its GRP and a frame that an EUD map plays past the frame count.
     */
    private static final int KAKARU = 952;
    private static final int KAKARU_FRAME_COUNT = 212;
    private static final int EUD_KAKARU_FRAME = 425;

    @RequiresInstallation
    @Test
    void world_renderer_takes_unit_images_of_the_loaded_map_and_rejects_missing_frames() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        HiddenContextScenario.run(WIDTH, HEIGHT, "Renderer verification", () -> {
            try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath()); WorldRenderer renderer = new WorldRenderer()) {
                int marine = ScrUnitIds.TERRAN_MARINE;

                Assertions.assertThrows(IllegalStateException.class, () -> renderer.getUnitImageIndex(marine));
                Assertions.assertThrows(IllegalStateException.class, () -> renderer.getUnitFrameCount(marine));

                RenderAssets assets = RenderAssets.load(ScrResourceProvider.borrow(storage), BADLANDS_TILESET);

                renderer.loadMap(ScrResourceProvider.borrow(storage), BADLANDS_TILESET, 1, 1, new int[]{TILE});

                int marineImageIndex = assets.getUnitImage(marine);
                int marineFrameCount = assets.getImageFrameCount(marineImageIndex);

                Assertions.assertEquals(marineImageIndex, renderer.getUnitImageIndex(marine));
                Assertions.assertEquals(marineFrameCount, renderer.getUnitFrameCount(marine));

                renderer.beginFrame(WIDTH, HEIGHT, WIDTH, HEIGHT, 0, 0, 1);
                Assertions.assertThrows(IOException.class,
                                        () -> renderer.drawUnit(marine, marineFrameCount, PLAYER_COLOR, REJECTED_DRAW_X, REJECTED_DRAW_Y, false));
                Assertions.assertThrows(IOException.class,
                                        () -> renderer.drawUnit(marine, -1, PLAYER_COLOR, REJECTED_DRAW_X, REJECTED_DRAW_Y, false));
                renderer.endFrame();
                Assertions.assertThrows(IllegalStateException.class,
                                        () -> renderer.drawShadowImage(MARINE_SHADOW, 0, REJECTED_DRAW_X, REJECTED_DRAW_Y, false),
                                        "a shadow after the finished world is rejected");
            }
        });
    }

    @RequiresInstallation
    @Test
    void get_frozen_center_y_should_center_the_frame_under_the_frozen_top() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        HiddenContextScenario.run(SIZE, SIZE, "Frozen image verification", () -> {
            try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath()); WorldRenderer renderer = new WorldRenderer()) {
                RenderAssets assets = RenderAssets.load(ScrResourceProvider.borrow(storage), BADLANDS_TILESET);
                int[] tiles = new int[MAP_TILES * MAP_TILES];

                Arrays.fill(tiles, TILE);
                Assertions.assertThrows(IllegalStateException.class, () -> renderer.getFrozenCenterY(0, 0, UNLOADED_FROZEN_TOP));
                renderer.loadMap(ScrResourceProvider.borrow(storage), BADLANDS_TILESET, MAP_TILES, MAP_TILES, tiles);

                int image = assets.getUnitImage(ScrUnitIds.TERRAN_MARINE);

                for (int frameIndex : DRAWN_FRAMES) {
                    ClassicFrame frame = assets.decodeImage(image, frameIndex);

                    for (int frozenTop : FROZEN_TOPS) {
                        int center = renderer.getFrozenCenterY(image, frameIndex, frozenTop);

                        Assertions.assertEquals(frozenTop - frame.getYOffset() + frame.getHeight() / 2, center);
                    }
                }

                Assertions.assertThrows(IOException.class, () -> renderer.getFrozenCenterY(image, MISSING_FRAME, 0));
            }
        });
    }

    @Test
    void get_creep_edge_frame_should_number_frames_consecutively() {
        Assertions.assertEquals(0, WorldRenderer.getCreepEdgeFrame(0));
        Assertions.assertEquals(0, WorldRenderer.getCreepEdgeFrame(LOWER_RIGHT_NEIGHBOR), "A lone diagonal neighbor draws no edge.");

        boolean[] used = new boolean[EDGE_FRAME_LIMIT];
        int maximum = 0;

        for (int neighbors = 0; neighbors < 1 << NEIGHBOR_COUNT; neighbors++) {
            int frame = WorldRenderer.getCreepEdgeFrame(neighbors);

            used[frame] = true;
            maximum     = Math.max(maximum, frame);
        }

        for (int frame = 0; frame <= maximum; frame++) {
            Assertions.assertTrue(used[frame], "Edge identifiers are consecutive; missing " + frame);
        }

        Assertions.assertEquals(CREEP_EDGE_FRAME_COUNT, maximum, "The neighbor classes select one-based frames of the 37-frame tileset creep GRP.");
        Assertions.assertEquals(WorldRenderer.getCreepEdgeFrame(LEFT_NEIGHBOR),
                                WorldRenderer.getCreepEdgeFrame(LEFT_NEIGHBOR | LOWER_LEFT_NEIGHBOR | UPPER_LEFT_NEIGHBOR),
                                "Diagonal neighbors without an adjacent edge neighbor do not change the left edge.");
        Assertions.assertNotEquals(WorldRenderer.getCreepEdgeFrame(LOWER_NEIGHBOR), WorldRenderer.getCreepEdgeFrame(UPPER_NEIGHBOR));
    }

    @RequiresInstallation
    @Test
    void draw_glow_image_should_reject_unlit_images_and_draws_outside_a_frame() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        HiddenContextScenario.run(SIZE, SIZE, "Renderer effect verification", () -> {
            try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath()); WorldRenderer renderer = new WorldRenderer()) {
                int center = TerrainTileset.TILE_SIZE / 2;

                renderer.loadMap(ScrResourceProvider.borrow(storage), JUNGLE_TILESET, 1, 1, new int[]{TILE});
                renderer.beginFrame(SIZE, SIZE, TerrainTileset.TILE_SIZE, TerrainTileset.TILE_SIZE, 0, 0, 1);
                Assertions.assertThrows(IOException.class,
                                        () -> renderer.drawGlowImage(UNLIT_IMAGE, 0, center, center, false),
                                        "An image whose color shift selects no lighting table is rejected.");
                renderer.endFrame();
                Assertions.assertThrows(IllegalStateException.class, () -> renderer.drawGlowImage(MINING_SPARK, 0, center, center, false));
            }
        });
    }

    @RequiresInstallation
    @Test
    void is_drawn_frame_should_skip_frames_past_the_grp_frame_count() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        HiddenContextScenario.run(WIDTH, HEIGHT, "Shadow stacking verification", () -> {
            try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath()); WorldRenderer renderer = new WorldRenderer()) {
                renderer.loadMap(ScrResourceProvider.borrow(storage), JUNGLE_TILESET, 1, 1, new int[]{TILE});

                // An EUD map plays frames 425 + 17n on the Kakaru image.
                int frames = renderer.getImageFrameCount(KAKARU);

                Assertions.assertEquals(KAKARU_FRAME_COUNT, frames);
                Assertions.assertTrue(renderer.isDrawnFrame(KAKARU, frames - 1));
                Assertions.assertFalse(renderer.isDrawnFrame(KAKARU, frames), "a frame past the GRP frame count skips the image");
                Assertions.assertFalse(renderer.isDrawnFrame(KAKARU, EUD_KAKARU_FRAME));
            }
        });
    }
}
