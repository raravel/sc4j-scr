package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.jetbrains.annotations.NotNull;

/**
 * An SC:R anim that an image draws in the HD graphics instead of its installed anim ({@link ImageGraphics}): a single-entry anim of asset scale
 * {@code 4} (HD) or {@code 2} (HD2) with a canvas and a block-compressed {@code diffuse} layer, in a byte range that is not copied and never
 * changes. Its texture sheet is parsed off the drawing thread when it is first drawn, as {@link EmbeddedAnim} parses an anim in game memory, and
 * kept within the anim budget of its game ({@link ImageReplacements#readAnim}).
 */
public final class ImageAnim {
    private final @NotNull String name;
    private final int scale;
    private final @NotNull EmbeddedAnim anim;

    /**
     * Creates an {@link ImageAnim} instance.
     *
     * @param name  the name of the anim
     * @param scale the asset scale of the anim
     * @param anim  the checked anim
     */
    ImageAnim(@NotNull String name, int scale, @NotNull EmbeddedAnim anim) {
        this.name  = name;
        this.scale = scale;
        this.anim  = anim;
    }

    /**
     * @return the name of the anim
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the asset scale of the anim: {@code 4} for HD, {@code 2} for HD2
     */
    public int getScale() {
        return this.scale;
    }

    /**
     * @return {@code true} if the anim is an HD anim, {@code false} if it is an HD2 anim
     */
    public boolean isHd() {
        return this.scale == EmbeddedAnim.HD_SCALE;
    }

    /**
     * @return the anim in its byte range
     */
    @NotNull EmbeddedAnim getAnim() {
        return this.anim;
    }
}
