package org.fogofwarlabs.sc4j.scr.client.glue;

import org.fogofwarlabs.sc4j.engine.net.lobby.MapFolders;
import org.fogofwarlabs.sc4j.scr.client.ClientPlayer;
import org.fogofwarlabs.sc4j.scr.net.lan.LanMapTransfer;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

/**
 * The install folders and files the glue screens read and write: single player IDs, maps, replays, saved games and the recently hosted maps.
 * The Beacon.Link block list is the run's account's ignore list
 * ({@link org.fogofwarlabs.sc4j.engine.net.beaconlink.community.CommunitySession#getIgnored()}).
 */
public final class GlueFolders {
    private final @NotNull Path characterDirectory;
    private final @NotNull Path mapDirectory;
    private final @NotNull Path replayDirectory;
    private final @NotNull Path saveDirectory;
    private final @NotNull Path recentMapsFile;

    /**
     * Creates a {@link GlueFolders} instance.
     *
     * @param characterDirectory the single player ID folder ({@code <install root>/characters})
     * @param mapDirectory       the map folder ({@code <install root>/maps})
     * @param replayDirectory    the replay folder ({@code <install root>/replays})
     * @param saveDirectory      the folder of the IDs' saved game folders ({@code <install root>/save})
     * @param recentMapsFile     the file of the recently hosted maps ({@code <install root>/recent-maps.txt})
     */
    public GlueFolders(@NotNull Path characterDirectory, @NotNull Path mapDirectory, @NotNull Path replayDirectory, @NotNull Path saveDirectory,
                       @NotNull Path recentMapsFile) {
        this.characterDirectory = characterDirectory;
        this.mapDirectory       = mapDirectory;
        this.replayDirectory    = replayDirectory;
        this.saveDirectory      = saveDirectory;
        this.recentMapsFile     = recentMapsFile;
    }

    /**
     * @return the single player ID folder
     */
    public @NotNull Path getCharacterDirectory() {
        return this.characterDirectory;
    }

    /**
     * @return the folder of the local IDs: the {@value ClientPlayer#LOCAL_ID_FOLDER} subfolder of the single player ID folder
     */
    public @NotNull Path getLocalIdDirectory() {
        return this.characterDirectory.resolve(ClientPlayer.LOCAL_ID_FOLDER);
    }

    /**
     * @return the map folder
     */
    public @NotNull Path getMapDirectory() {
        return this.mapDirectory;
    }

    /**
     * @return the map folders a LAN client searches for a lobby's map: the map folder alone, with downloads in its
     * {@link LanMapTransfer#DOWNLOAD_FOLDER} subfolder
     */
    public @NotNull MapFolders getLanMapFolders() {
        return MapFolders.of(List.of(this.mapDirectory), LanMapTransfer.DOWNLOAD_FOLDER);
    }

    /**
     * @return the replay folder
     */
    public @NotNull Path getReplayDirectory() {
        return this.replayDirectory;
    }

    /**
     * Returns the saved game folder of a player.
     *
     * @param player the player
     * @return the player's folder in the save folder ({@link ClientPlayer#getSaveDirectory(Path)})
     */
    public @NotNull Path getSaveDirectory(@NotNull ClientPlayer player) {
        return player.getSaveDirectory(this.saveDirectory);
    }

    /**
     * @return the file of the maps this client hosted last
     */
    public @NotNull Path getRecentMapsFile() {
        return this.recentMapsFile;
    }
}
