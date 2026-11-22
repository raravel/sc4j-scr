package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrGameExtension;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gives every game its classic image graphics ({@link ClassicImageGraphics}), which also draw the image graphics a simulation plugin of the game
 * replaces ({@link ImageReplacementSource}).
 */
public final class ClassicGraphicsExtension implements ScrGameExtension {
    /**
     * Finds the image graphics a simulation plugin of a game replaces: those of its first plugin that is an {@link ImageReplacementSource}.
     *
     * @param game the game
     * @return the replaced image graphics, or {@code null} if no simulation plugin of the game replaces any
     */
    private static @Nullable ImageReplacements findImageReplacements(@NotNull Game game) {
        ScrSimulationPlugins plugins = game.getSimulationPlugins();

        if (plugins == null) {
            return null;
        }

        for (ScrSimulationPlugin plugin : plugins.getPlugins()) {
            if (plugin instanceof ImageReplacementSource source) {
                return source.getImageReplacements();
            }
        }

        return null;
    }


    /**
     * Creates the extension's plugin of a game, once, when the game's SC:R system attaches; the plugin lives as long as the game, so every new
     * game, live, replay or loaded save, starts with a new plugin.
     *
     * @param game the game, attached and not yet initialized
     * @return the created {@link ClassicImageGraphics} instance
     */
    @Override
    public @NotNull ScrGamePlugin createPlugin(@NotNull Game game) {
        return new ClassicImageGraphics(game.getSpriteSystem()::getImagePool, () -> findImageReplacements(game));
    }
}
