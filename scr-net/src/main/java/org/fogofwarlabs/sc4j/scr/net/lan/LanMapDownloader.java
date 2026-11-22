package org.fogofwarlabs.sc4j.scr.net.lan;

import org.jetbrains.annotations.NotNull;

/**
 * Downloads the maps of a lobby in place of the transfer of message {@code 0x4f} ({@link LanMapTransfer#setDownloader}): a mod's own way to
 * carry a lobby map, which takes over a missing map once its information arrived and hands the whole file back to the lobby's transfer
 * ({@link LanMapTransfer#completeDownload}), or hands the map back to message {@code 0x4f} ({@link LanMapTransfer#downloadNatively}). The
 * information, the statuses and the file checks stay those of message {@code 0x4f}.
 */
public interface LanMapDownloader extends AutoCloseable {
    /**
     * Shares a map the local player shares through message {@code 0x4f}, so that the downloaders of the other players can take it.
     *
     * @param map the shared map
     */
    void share(LanMapTransfer.@NotNull SharedFile map);

    /**
     * Takes over the download of a map the local player lacks, right after its information arrived and before its first status goes to the
     * owner.
     *
     * @param transfer the transfer of the map
     * @return {@code true} if the downloader downloads the map, {@code false} to leave it to message {@code 0x4f}
     */
    boolean download(LanMapTransfer.@NotNull Transfer transfer);

    /**
     * Carries out the due work of the downloads and shares; called with every lobby update.
     */
    void update();

    /**
     * Ends the downloads and shares. Calling it again does nothing.
     */
    @Override
    void close();
}
