package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrGameExtension;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;

/**
 * Gives every game its classic game screen ({@link ClassicScreenGraphics}).
 */
public final class ClassicScreenExtension implements ScrGameExtension {
    /**
     * Creates the extension's plugin of a game, once, when the game's SC:R system attaches; the plugin lives as long as the game, so every new
     * game, live, replay or loaded save, starts with a new plugin.
     *
     * @param game the game, attached and not yet initialized
     * @return the created {@link ClassicScreenGraphics} instance
     */
    @Override
    public @NotNull ScrGamePlugin createPlugin(@NotNull Game game) {
        return new ClassicScreenGraphics();
    }
}
