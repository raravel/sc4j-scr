package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Creates the simulation plugins of the {@link ScrSimulationExtension} implementations that the jar of the running mod names. They are found
 * once per class loader, with the class loader of {@link ScrSimulationExtension}, so a mod jar in its own class loader finds its own
 * implementations.
 */
public final class ScrSimulationExtensionUtils {
    private static final @NotNull List<@NotNull ScrSimulationExtension> EXTENSIONS = ServiceLoader.load(ScrSimulationExtension.class, ScrSimulationExtension.class.getClassLoader())
                                                                                                  .stream()
                                                                                                  .map(ServiceLoader.Provider::get)
                                                                                                  .toList();

    /**
     * Lets every extension create its simulation plugin of a game ({@link ScrSimulationExtension#createPlugin}).
     *
     * @param game the game, when its SC:R system attaches
     * @return the created plugins in extension order, or {@code null} if no extension created one
     */
    public static @Nullable ScrSimulationPlugins createPlugins(@NotNull Game game) {
        List<ScrSimulationPlugin> plugins = new ArrayList<>(EXTENSIONS.size());

        for (ScrSimulationExtension extension : EXTENSIONS) {
            ScrSimulationPlugin plugin = extension.createPlugin(game);

            if (plugin != null) {
                plugins.add(plugin);
            }
        }

        return plugins.isEmpty() ? null : new ScrSimulationPlugins(game, plugins.toArray(new ScrSimulationPlugin[0]));
    }

    /**
     * Tells whether the running mod has a simulation extension, so it runs games SC:R does not run.
     *
     * @return {@code true} if the running mod names a simulation extension, {@code false} otherwise
     */
    public static boolean hasExtensions() {
        return !EXTENSIONS.isEmpty();
    }

    private ScrSimulationExtensionUtils() {
    }
}
