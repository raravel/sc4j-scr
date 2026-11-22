package org.fogofwarlabs.sc4j.scr.render;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Tests the warp flash alpha and the warp dimming of shadow images of the {@link SceneRenderer} contract.
 */
final class SceneRendererTest {
    /**
     * Warp flash data values: the first (alpha {@code 1}) and the last (alpha {@code 0}) of the flash range, inside the range, below and above it,
     * and with bits above the data byte, and the tolerated brighten alpha difference.
     */
    private static final int FIRST_FLASH_DATA = 48;
    private static final int LAST_FLASH_DATA = 63;
    private static final int MIDDLE_FLASH_DATA = 56;
    private static final int BELOW_FLASH_DATA = 47;
    private static final int ABOVE_FLASH_DATA = 64;
    private static final int HIGH_BITS_FLASH_DATA = 0x230;
    private static final float ALPHA_TOLERANCE = 1e-6f;

    /**
     * The frame count of the Warp Texture's SD anim, the warp frame of a sprite without a warping primary image, the {@code images.rel} relation
     * type of shadow images, an {@code images.rel} relation that is not a shadow and a sampled warp frame.
     */
    private static final int WARP_FRAME_COUNT = 21;
    private static final int NO_WARP_FRAME = -1;
    private static final int SHADOW_RELATION = 8;
    private static final int OTHER_RELATION = 1;
    private static final int SAMPLE_WARP_FRAME = 7;
    /**
     * The expected shadow alpha of each warp frame: the frame over the frame count, and {@code 1} without a warping primary image.
     */
    private static final @NotNull Map<Integer, Float> EXPECTED_WARP_ALPHAS = Map.of(-1, 1f,
                                                                                    0, 0f,
                                                                                    7, 0.33333334f,
                                                                                    14, 0.6666667f,
                                                                                    21, 1f);

    @Test
    void get_brighten_alpha_should_compute_the_warp_flash_alpha() {
        Assertions.assertEquals(1.0f, SceneRenderer.getBrightenAlpha(FIRST_FLASH_DATA), ALPHA_TOLERANCE);
        Assertions.assertEquals(0.0f, SceneRenderer.getBrightenAlpha(LAST_FLASH_DATA), ALPHA_TOLERANCE);
        Assertions.assertEquals(1 - (float) Math.sqrt(1 - 7 / 15.0), SceneRenderer.getBrightenAlpha(MIDDLE_FLASH_DATA), ALPHA_TOLERANCE);
        Assertions.assertEquals(0.0f, SceneRenderer.getBrightenAlpha(BELOW_FLASH_DATA), "the square root of a negative number draws nothing");
        Assertions.assertEquals(0.0f, SceneRenderer.getBrightenAlpha(ABOVE_FLASH_DATA), "a negative alpha draws nothing");
        Assertions.assertEquals(1.0f, SceneRenderer.getBrightenAlpha(HIGH_BITS_FLASH_DATA), "only the data byte counts");
    }

    @Test
    void get_shadow_warp_alpha_should_dim_only_shadow_images_with_the_warp_frame() {
        Assertions.assertEquals(1.0f,
                                SceneRenderer.getShadowWarpAlpha(OTHER_RELATION, SAMPLE_WARP_FRAME, WARP_FRAME_COUNT),
                                "only images.rel shadow images are dimmed");

        for (Map.Entry<Integer, Float> expected : EXPECTED_WARP_ALPHAS.entrySet()) {
            Assertions.assertEquals(expected.getValue(),
                                    SceneRenderer.getShadowWarpAlpha(SHADOW_RELATION, expected.getKey(), WARP_FRAME_COUNT),
                                    ALPHA_TOLERANCE,
                                    "warp frame " + expected.getKey());
        }

        Assertions.assertEquals(1.0f, SceneRenderer.getShadowWarpAlpha(SHADOW_RELATION, NO_WARP_FRAME, WARP_FRAME_COUNT), "no warping primary image");
    }
}
