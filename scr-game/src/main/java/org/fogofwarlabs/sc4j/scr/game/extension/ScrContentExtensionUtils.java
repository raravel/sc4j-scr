package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Calls the {@link ScrContentExtension} implementations that the jar of the running mod names. They are found once per class loader, with
 * the class loader of {@link ScrContentExtension}, so a mod jar in its own class loader finds its own implementations.
 */
public final class ScrContentExtensionUtils {
    private static final @NotNull List<@NotNull ScrContentExtension> EXTENSIONS = ServiceLoader.load(ScrContentExtension.class, ScrContentExtension.class.getClassLoader())
                                                                                               .stream()
                                                                                               .map(ServiceLoader.Provider::get)
                                                                                               .toList();

    /**
     * Lets every extension extend the installed data ({@link ScrContentExtension#extendInstalledData}).
     *
     * @param installed the installed data, right after it is loaded
     * @throws IOException if a file an extension reads cannot be read
     */
    public static void extendInstalledData(@NotNull GameData installed) throws IOException {
        for (ScrContentExtension extension : EXTENSIONS) {
            extension.extendInstalledData(installed);
        }
    }

    /**
     * Lets every extension extend a game's own tables ({@link ScrContentExtension#extendGameData}).
     *
     * @param game the game's own data, right after the game is created
     */
    public static void extendGameData(@NotNull GameData game) {
        for (ScrContentExtension extension : EXTENSIONS) {
            extension.extendGameData(game);
        }
    }

    private ScrContentExtensionUtils() {
    }
}
