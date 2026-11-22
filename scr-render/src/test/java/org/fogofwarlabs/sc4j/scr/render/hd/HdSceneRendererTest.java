package org.fogofwarlabs.sc4j.scr.render.hd;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.dat.ImagesDat;
import org.fogofwarlabs.sc4j.scr.data.format.image.AnimFile;
import org.fogofwarlabs.sc4j.scr.data.format.image.GrpFile;
import org.fogofwarlabs.sc4j.scr.data.format.text.TblFile;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.render.HiddenContextScenario;
import org.fogofwarlabs.sc4j.scr.render.SceneRenderer;
import org.fogofwarlabs.sc4j.scr.render.WorldRenderer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

/**
 * Tests that {@link HdSceneRenderer} places SD-scaled frames by their GRP offset, rejects selection circle draws of sprite images and missing
 * frames, makes every image resident by a preload, and refuses every draw once closed.
 */
@RequiresInstallation
final class HdSceneRendererTest {
    /**
     * The system property that enables the GPU checks and the message of a skipped check.
     */
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    /**
     * The width and height of the hidden window in pixels, and the width and height of the map in tiles.
     */
    private static final int SIZE = 128;
    private static final int MAP_TILES = 4;
    /**
     * Two screen pixels per world pixel.
     */
    private static final int SCREEN_SCALE = 2;
    /**
     * The frame units per game pixel of an HD anim.
     */
    private static final int HD_FRAME_SCALE = 4;
    /**
     * The Jungle tileset and a tile identifier of it (CV5 group {@code 2}, megatile {@code 0}).
     */
    private static final int JUNGLE_TILESET = 4;
    private static final int TILE = 0x20;

    /**
     * The number of SD frames that the checked count of the SD frame placement must exceed.
     */
    private static final int MINIMUM_CHECKED_FRAMES = 500;
    /**
     * Images: the Zealot and its shadow.
     */
    private static final int ZEALOT = 151;
    private static final int ZEALOT_SHADOW = 152;
    /**
     * The world position of the drawn sprites and a frame index past the Zealot frames.
     */
    private static final int SPRITE_X = 32;
    private static final int SPRITE_Y = 32;
    private static final int MISSING_FRAME = 100000;

    /**
     * The preload step limit, the upload time budget of one step and the minimum resident texture size in megabytes.
     */
    private static final int MAXIMUM_PRELOAD_STEPS = 10_000;
    private static final long PRELOAD_BUDGET_NANOSECONDS = 20_000_000L;
    private static final long MINIMUM_RESIDENT_MEGABYTES = 200L;
    private static final int MEGABYTE_SHIFT = 20;

    private interface Scene {
        void draw(@NotNull HdSceneRenderer scene) throws IOException;
    }

    private static void drawScene(@NotNull WorldRenderer world, @NotNull HdSceneRenderer scene, @NotNull Scene drawing) throws IOException {
        world.beginFrame(SIZE, SIZE, SIZE, SIZE, 0, 0, SCREEN_SCALE);

        try {
            drawing.draw(scene);
        } finally {
            scene.finishScene();
            world.endFrame();
        }
    }


    @Test
    void sd_frames_are_stored_with_their_grp_offset() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            DatTable images = ImagesDat.createSchema().parse(storage.readFile("arr/images.dat"));
            TblFile names = TblFile.parse(storage.readFile("arr/images.tbl"));
            int checked = 0;

            for (int imageIdentifier = 0; imageIdentifier < ImagesDat.IMAGE_COUNT; imageIdentifier++) {
                String path = String.format("anim/main_%03d.anim", imageIdentifier);

                if (!storage.hasFile(path)) {
                    continue;
                }

                AnimFile.Entry entry = AnimFile.parse(storage.readFile(path)).getEntry(0);

                if (entry.isReference()) {
                    continue;
                }

                GrpFile spriteFile = GrpFile.parse(
                        storage.readFile("unit/" + names.getString(images.get(ImagesDat.GRAPHICS_FILE, imageIdentifier) - 1).replace('\\', '/'))
                );

                for (int frame = 0; frame < entry.getFrameCount(); frame++) {
                    if ((entry.getFrameFlags(frame) & HdSceneRenderer.FRAME_CANVAS_PLACED) != 0) {
                        continue;
                    }

                    // A frame without the flag is the SD frame scaled by 4, stored with the GRP offset: The renderer draws it from the
                    // image's classic map position.
                    String label = "image " + imageIdentifier + " frame " + frame;

                    Assertions.assertEquals(spriteFile.getFrameXOffset(frame), entry.getFrameXOffset(frame), label);
                    Assertions.assertEquals(spriteFile.getFrameYOffset(frame), entry.getFrameYOffset(frame), label);
                    Assertions.assertEquals(spriteFile.getFrameWidth(frame) * HD_FRAME_SCALE, entry.getFrameWidth(frame), label);
                    Assertions.assertEquals(spriteFile.getFrameHeight(frame) * HD_FRAME_SCALE, entry.getFrameHeight(frame), label);
                    checked++;
                }
            }

            Assertions.assertTrue(checked > MINIMUM_CHECKED_FRAMES, "SD frames checked: " + checked);
        }
    }

    @Test
    void draw_image_should_reject_selection_circle_draws_and_missing_frames() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        HiddenContextScenario.run(SIZE, SIZE, "HD scene verification", () -> {
            int[] tiles = new int[MAP_TILES * MAP_TILES];

            Arrays.fill(tiles, TILE);

            try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath()); WorldRenderer world = new WorldRenderer()) {
                world.loadMap(ScrResourceProvider.borrow(storage), JUNGLE_TILESET, MAP_TILES, MAP_TILES, tiles);
                world.setRenderScaleLimit(HdRenderAssets.Quality.HD2.getScale());

                HdSceneRenderer scene = new HdSceneRenderer(world, HdRenderAssets.load(ScrResourceProvider.borrow(storage), JUNGLE_TILESET,
                                                                                       HdRenderAssets.Quality.HD2));

                try {
                    Assertions.assertThrows(IOException.class,
                                            () -> drawScene(world, scene,
                                                            renderer -> renderer.drawImage(ZEALOT,
                                                                                           0,
                                                                                           SceneRenderer.DRAW_FUNCTION_SELECTION_CIRCLE,
                                                                                           0,
                                                                                           0,
                                                                                           SPRITE_X,
                                                                                           SPRITE_Y,
                                                                                           false)));
                    Assertions.assertThrows(IOException.class,
                                            () -> drawScene(world, scene,
                                                            renderer -> renderer.drawImage(ZEALOT,
                                                                                           MISSING_FRAME,
                                                                                           SceneRenderer.DRAW_FUNCTION_NORMAL,
                                                                                           0,
                                                                                           0,
                                                                                           SPRITE_X,
                                                                                           SPRITE_Y,
                                                                                           false)));
                } finally {
                    scene.close();
                }
            }
        });
    }

    @Test
    void continue_preload_should_make_every_image_resident() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        HiddenContextScenario.run(SIZE, SIZE, "HD preload verification", () -> {
            int[] tiles = new int[MAP_TILES * MAP_TILES];

            Arrays.fill(tiles, TILE);

            try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath()); WorldRenderer world = new WorldRenderer()) {
                world.loadMap(ScrResourceProvider.borrow(storage), JUNGLE_TILESET, MAP_TILES, MAP_TILES, tiles);
                world.setRenderScaleLimit(HdRenderAssets.Quality.HD2.getScale());

                HdSceneRenderer scene = new HdSceneRenderer(world, HdRenderAssets.load(ScrResourceProvider.borrow(storage), JUNGLE_TILESET,
                                                                                       HdRenderAssets.Quality.HD2));

                try {
                    scene.setStreaming(true);
                    scene.startPreload(index -> TILE);
                    Assertions.assertTrue(scene.isPreloading());

                    float progress = 0;

                    for (int step = 0; step < MAXIMUM_PRELOAD_STEPS && scene.isPreloading(); step++) {
                        float next = scene.continuePreload(PRELOAD_BUDGET_NANOSECONDS);

                        Assertions.assertTrue(next >= progress, "the progress never goes back");
                        progress = next;
                    }

                    Assertions.assertFalse(scene.isPreloading(), "the preload completes");
                    Assertions.assertEquals(1, progress);
                    Assertions.assertTrue(scene.getTextureBytes() > MINIMUM_RESIDENT_MEGABYTES << MEGABYTE_SHIFT,
                                          "all images are resident: " + (scene.getTextureBytes() >> MEGABYTE_SHIFT) + " MB");
                } finally {
                    scene.close();
                }
            }
        });
    }

    @Test
    void draw_shadow_image_should_reject_a_closed_renderer_like_the_other_draws() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        HiddenContextScenario.run(SIZE, SIZE, "HD closed scene verification", () -> {
            int[] tiles = new int[MAP_TILES * MAP_TILES];

            Arrays.fill(tiles, TILE);

            try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath()); WorldRenderer world = new WorldRenderer()) {
                world.loadMap(ScrResourceProvider.borrow(storage), JUNGLE_TILESET, MAP_TILES, MAP_TILES, tiles);
                world.setRenderScaleLimit(HdRenderAssets.Quality.HD2.getScale());

                HdSceneRenderer scene = new HdSceneRenderer(world, HdRenderAssets.load(ScrResourceProvider.borrow(storage), JUNGLE_TILESET,
                                                                                       HdRenderAssets.Quality.HD2));

                scene.close();

                // A shadow that is the first draw of a frame begins the scene, which a closed renderer refuses.
                Assertions.assertThrows(IllegalStateException.class,
                                        () -> scene.drawImage(ZEALOT, 0, SceneRenderer.DRAW_FUNCTION_NORMAL, 0, 0, SPRITE_X, SPRITE_Y, false));
                Assertions.assertThrows(IllegalStateException.class, () -> scene.drawShadowImage(ZEALOT_SHADOW, 0, -1, SPRITE_X, SPRITE_Y, false));
            }
        });
    }
}
