package org.fogofwarlabs.sc4j.scr.net.extension;

import org.fogofwarlabs.sc4j.scr.net.lan.LanClient;
import org.fogofwarlabs.sc4j.scr.net.lan.LanLobby;
import org.fogofwarlabs.sc4j.scr.net.lan.LanMapDownloader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Calls the {@link ScrNetworkExtension} implementations that the jar of the running mod names. They are found once per class loader, with the
 * class loader of {@link ScrNetworkExtension}, so a mod jar in its own class loader finds its own implementations.
 */
public final class ScrNetworkExtensionUtils {
    private static final @NotNull List<@NotNull ScrNetworkExtension> EXTENSIONS = ServiceLoader.load(ScrNetworkExtension.class, ScrNetworkExtension.class.getClassLoader())
                                                                                               .stream()
                                                                                               .map(ServiceLoader.Provider::get)
                                                                                               .toList();

    /**
     * Creates the map downloader of a lobby: the first one an extension creates ({@link ScrNetworkExtension#createMapDownloader}).
     *
     * @param client the client, with its network and session
     * @param lobby  the lobby
     * @return the created downloader, or {@code null} when no extension creates one
     */
    public static @Nullable LanMapDownloader createMapDownloader(@NotNull LanClient client, @NotNull LanLobby lobby) {
        for (ScrNetworkExtension extension : EXTENSIONS) {
            LanMapDownloader downloader = extension.createMapDownloader(client, lobby);

            if (downloader != null) {
                return downloader;
            }
        }

        return null;
    }

    private ScrNetworkExtensionUtils() {
    }
}
