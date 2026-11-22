package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrGameExtension;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gives every game its classic console ({@link ClassicConsoleGraphics}), which also merges the console settings of a simulation plugin of the game
 * ({@link ConsoleSettingsSource}).
 */
public final class ClassicConsoleExtension implements ScrGameExtension {
    /**
     * Finds the console settings of a simulation plugin of a game: those of its first plugin that is a {@link ConsoleSettingsSource}.
     *
     * @param game the game
     * @return the settings, or {@code null} if no simulation plugin of the game sets the console
     */
    private static @Nullable ConsoleSettings findConsoleSettings(@NotNull Game game) {
        ScrSimulationPlugins plugins = game.getSimulationPlugins();

        if (plugins == null) {
            return null;
        }

        for (ScrSimulationPlugin plugin : plugins.getPlugins()) {
            if (plugin instanceof ConsoleSettingsSource source) {
                return source.getConsoleSettings();
            }
        }

        return null;
    }


    /**
     * Creates the extension's plugin of a game, once, when the game's SC:R system attaches; the plugin lives as long as the game, so every new
     * game, live, replay or loaded save, starts with a new plugin.
     *
     * @param game the game, attached and not yet initialized
     * @return the created {@link ClassicConsoleGraphics} instance
     */
    @Override
    public @NotNull ScrGamePlugin createPlugin(@NotNull Game game) {
        return new ClassicConsoleGraphics(() -> findConsoleSettings(game));
    }
}
