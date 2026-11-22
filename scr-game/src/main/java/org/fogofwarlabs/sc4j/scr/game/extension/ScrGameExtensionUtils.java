package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemorySource;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Creates the plugins and memory sources of the {@link ScrGameExtension} implementations that the jar of the running mod names. They are found
 * once per class loader, with the class loader of {@link ScrGameExtension}, so a mod jar in its own class loader finds its own implementations.
 */
public final class ScrGameExtensionUtils {
    private static final @NotNull List<@NotNull ScrGameExtension> EXTENSIONS = ServiceLoader.load(ScrGameExtension.class, ScrGameExtension.class.getClassLoader())
                                                                                            .stream()
                                                                                            .map(ServiceLoader.Provider::get)
                                                                                            .toList();

    /**
     * Lets every extension create its plugin of a game ({@link ScrGameExtension#createPlugin}).
     *
     * @param game the game, when its SC:R system attaches
     * @return the created plugins in extension order, empty without extensions
     */
    public static @NotNull ScrGamePlugin @NotNull [] createPlugins(@NotNull Game game) {
        List<ScrGamePlugin> plugins = new ArrayList<>(EXTENSIONS.size());

        for (ScrGameExtension extension : EXTENSIONS) {
            ScrGamePlugin plugin = extension.createPlugin(game);

            if (plugin != null) {
                plugins.add(plugin);
            }
        }

        return plugins.toArray(new ScrGamePlugin[0]);
    }

    /**
     * Lets every extension that provides memory create its memory source of a game ({@link ScrGameExtension#createMemorySource}).
     *
     * @param game the game, when its SC:R system attaches
     * @return the created memory sources in extension order, empty without extensions that provide memory
     */
    public static @NotNull ClassicMemorySource @NotNull [] createMemorySources(@NotNull Game game) {
        List<ClassicMemorySource> sources = new ArrayList<>();

        for (ScrGameExtension extension : EXTENSIONS) {
            ClassicMemorySource source = extension.providesMemory() ? extension.createMemorySource(game) : null;

            if (source != null) {
                sources.add(source);
            }
        }

        return sources.toArray(new ClassicMemorySource[0]);
    }

    /**
     * Tells whether an extension of the running mod provides EUD memory ({@link ScrGameExtension#providesMemory()}), so the mod runs games
     * SC:R does not run.
     *
     * @return {@code true} if an extension provides memory, {@code false} otherwise
     */
    public static boolean providesMemory() {
        return EXTENSIONS.stream().anyMatch(ScrGameExtension::providesMemory);
    }

    private ScrGameExtensionUtils() {
    }
}
