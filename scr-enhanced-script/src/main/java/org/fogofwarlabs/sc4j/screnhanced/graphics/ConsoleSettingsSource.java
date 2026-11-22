package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.jetbrains.annotations.NotNull;

/**
 * A simulation plugin of a game that sets the console ({@link ConsoleSettings}); the classic console of the game finds it among the game's
 * simulation plugins and merges its settings with what EUD memory gives ({@link ClassicConsoleGraphics}).
 */
public interface ConsoleSettingsSource {
    /**
     * @return the console settings of the plugin, the same instance for the whole game
     */
    @NotNull ConsoleSettings getConsoleSettings();
}
