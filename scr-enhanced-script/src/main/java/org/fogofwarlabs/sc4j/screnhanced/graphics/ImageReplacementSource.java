package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.jetbrains.annotations.NotNull;

/**
 * A simulation plugin of a game that replaces the graphics of the game's images ({@link ImageReplacements}); the classic image graphics of the
 * game find it among the game's simulation plugins and draw its replacements ({@link ClassicImageGraphics}).
 */
public interface ImageReplacementSource {
    /**
     * @return the image graphics the plugin replaces, the same instance for the whole game
     */
    @NotNull ImageReplacements getImageReplacements();
}
