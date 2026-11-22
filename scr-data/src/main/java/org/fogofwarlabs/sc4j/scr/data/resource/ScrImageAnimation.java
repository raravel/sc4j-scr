package org.fogofwarlabs.sc4j.scr.data.resource;

import org.fogofwarlabs.sc4j.engine.resource.graphics.TextureSheet;
import org.jetbrains.annotations.Nullable;

/**
 * The HD graphics an image draws instead of its installed anim: the texture sheet of a single-entry anim of asset scale {@code 4} (HD) or
 * {@code 2} (HD2), prepared off the drawing thread. Its frame geometry is in scale-{@code 4} pixels whatever the scale, so a sheet of one scale
 * draws at the other scale too. The image's frame index selects the frame.
 */
public interface ScrImageAnimation {
    /**
     * Finds the texture sheet drawn at an asset scale: the anim of that scale, else the anim of the other scale. A sheet that is not prepared yet
     * starts its preparation and is not returned until it is ready; the sheet returned last stays the result meanwhile. The same instance is
     * returned while the anim's bytes are unchanged, so what a renderer makes of a sheet can be kept under that instance.
     *
     * @param scale the asset scale drawn: {@code 4} for HD, {@code 2} for HD2
     * @return the texture sheet, or {@code null} if no anim is ready
     */
    @Nullable TextureSheet findSheet(int scale);
}
