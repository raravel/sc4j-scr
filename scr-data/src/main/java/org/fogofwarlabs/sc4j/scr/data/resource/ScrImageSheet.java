package org.fogofwarlabs.sc4j.scr.data.resource;

import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * The indexed frames an image draws instead of its installed graphics, each decoded when it is asked for: a frame count and per frame a
 * one-frame indexed {@link SpriteSheet} whose frame box is the canvas of all frames, its anchor at the canvas center.
 */
public interface ScrImageSheet {
    /**
     * @return the number of frames
     */
    int getFrameCount();

    /**
     * Returns one frame as a one-frame indexed sheet. The same instance is returned while the frame's pixels are unchanged, so what a renderer
     * makes of a frame can be kept under that instance.
     *
     * @param frameIndex the frame index, {@code 0} up to {@link #getFrameCount()} minus one
     * @return the one-frame sheet
     * @throws IOException if the index names no frame or the frame cannot be decoded
     */
    @NotNull SpriteSheet getFrame(int frameIndex) throws IOException;
}
