package org.fogofwarlabs.sc4j.scr.net.extension;

import org.fogofwarlabs.sc4j.scr.net.lan.LanClient;
import org.fogofwarlabs.sc4j.scr.net.lan.LanLobby;
import org.fogofwarlabs.sc4j.scr.net.lan.LanMapDownloader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Extends the network play of the SC:R mod. A mod built from the SC:R mod's sources adds an implementation with a public no-argument
 * constructor and names it in the resource {@code META-INF/services/org.fogofwarlabs.sc4j.scr.net.extension.ScrNetworkExtension} of its jar;
 * {@link ScrNetworkExtensionUtils} calls the named implementations in the order of that file. The SC:R mod itself names none, so its lobbies
 * keep the native map transfer.
 */
public interface ScrNetworkExtension {
    /**
     * Creates the map downloader of a lobby the client opened: a hosted game, a hosted saved game or a joined game, on the client's network
     * and provider session. The players of a lobby all run the same mod, as a host refuses a joiner of another LAN join version
     * ({@link LanLobby#JOIN_VERSION}).
     *
     * @param client the client, with its network and session
     * @param lobby  the lobby
     * @return the created downloader, or {@code null} to keep the transfer of message {@code 0x4f}
     */
    default @Nullable LanMapDownloader createMapDownloader(@NotNull LanClient client, @NotNull LanLobby lobby) {
        return null;
    }
}
