package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLink;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkAccount;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Names the player of the run on every screen: the account the launcher chose for the run, a local ID chosen on the ID screen of a run
 * without an account, or a guest for a run without an account before it chose an ID. The menus show the account as {@code Name#tag}; games,
 * lobbies and Beacon.Link show its name. The player's character file and saved-game folder are named after the account id, the guest folder
 * name or the local ID's file name; those of the local IDs lie in the {@value #LOCAL_ID_FOLDER} subfolder of the character folder and of the
 * save folder, apart from the account and guest ones. A guest plays as {@value #GUEST_NAME}; a local ID and a guest have no Beacon.Link.
 */
public final class ClientPlayer {
    /**
     * The name of a guest.
     */
    public static final @NotNull String GUEST_NAME = BeaconLink.GUEST_NAME;
    /**
     * The subfolder of the character folder and of the save folder that holds the local IDs' files and saved-game folders.
     */
    public static final @NotNull String LOCAL_ID_FOLDER = "local";

    private static final @NotNull String GUEST_FOLDER_NAME = "guest";

    /**
     * Creates a {@link ClientPlayer} instance for the run's account.
     *
     * @param account the run's account, or {@code null} for a guest
     * @return the created {@link ClientPlayer} instance
     */
    public static @NotNull ClientPlayer of(@Nullable LinkAccount account) {
        return account == null ? new ClientPlayer(GUEST_NAME, GUEST_NAME, GUEST_FOLDER_NAME, true, false)
                               : new ClientPlayer(account.getName(), account.getTaggedName(), account.getId(), false, false);
    }

    /**
     * Creates a {@link ClientPlayer} instance for a local ID.
     *
     * @param name       the ID's name, which the menus, games and lobbies show
     * @param folderName the file name of the ID's character file, which names its saved-game folder
     * @return the created {@link ClientPlayer} instance
     */
    public static @NotNull ClientPlayer ofLocalId(@NotNull String name, @NotNull String folderName) {
        return new ClientPlayer(name, name, folderName, false, true);
    }


    private final @NotNull String name;
    private final @NotNull String taggedName;
    private final @NotNull String folderName;
    private final boolean guest;
    private final boolean localId;

    private ClientPlayer(@NotNull String name, @NotNull String taggedName, @NotNull String folderName, boolean guest, boolean localId) {
        this.name       = name;
        this.taggedName = taggedName;
        this.folderName = folderName;
        this.guest      = guest;
        this.localId    = localId;
    }

    /**
     * @return the player's name in games, lobbies and Beacon.Link: the account name, the local ID's name, or {@value #GUEST_NAME}
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the name the menus show: the account name with its tag ({@code Name#abcd12}), the local ID's name, or {@value #GUEST_NAME}
     */
    public @NotNull String getTaggedName() {
        return this.taggedName;
    }

    /**
     * @return the file name of the player's character file and saved-game folder: the account id, the local ID's file name, or {@code guest}
     */
    public @NotNull String getFolderName() {
        return this.folderName;
    }

    /**
     * @return {@code true} if the player is a guest, {@code false} for an account or a local ID
     */
    public boolean isGuest() {
        return this.guest;
    }


    /**
     * Returns the folder that holds the player's character file, or its saved-game folder, among the players of a folder.
     *
     * @param directory the character folder or the save folder
     * @return the folder's {@value #LOCAL_ID_FOLDER} subfolder for a local ID, the folder itself otherwise
     */
    public @NotNull Path getDirectory(@NotNull Path directory) {
        return this.localId ? directory.resolve(LOCAL_ID_FOLDER) : directory;
    }

    /**
     * Returns the player's saved-game folder inside the save folder.
     *
     * @param saveDirectory the save folder
     * @return the folder named {@link #getFolderName()} in {@link #getDirectory(Path)} of the save folder
     */
    public @NotNull Path getSaveDirectory(@NotNull Path saveDirectory) {
        return this.getDirectory(saveDirectory).resolve(this.folderName);
    }
}
