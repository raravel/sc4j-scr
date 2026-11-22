package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.base.text.TerminatedStringUtils;
import org.fogofwarlabs.sc4j.engine.net.lobby.MapFileMatch;
import org.fogofwarlabs.sc4j.engine.net.lobby.MapFolders;
import org.fogofwarlabs.sc4j.scr.core.FileNameUtils;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderPeer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Transfers LAN lobby map files through message {@code 0x4f}.
 * <p>
 * Owners pair idle players missing bytes with idle holders. Transfers retain newest-first player and file order, resume from reported byte counts,
 * and verify the complete checksum before writing. Mismatched chunks stop the sender and report progress; checksum mismatches restart from zero.
 * A mod's downloader ({@link #setDownloader}) can take over the download of a missing map instead of the chunks.
 * <p>
 * Local files are searched in the configured map folders ({@link MapFolders}): the first file of the transfer's size and checksum, whatever
 * its name ({@link #createFileMatch}). A download is saved into their save folder, by default the {@link #DOWNLOAD_FOLDER} subfolder of the
 * first map folder ({@link MapFolders#saveFile}); its name must be one name inside that folder. Files larger than a Java array are refused.
 */
public final class LanMapTransfer {
    /**
     * Transfer flag: the transfer exists and is idle unless another flag is set (set on creation).
     */
    public static final int FLAG_READY = 1;
    /**
     * Transfer flag: chunks go to the target.
     */
    public static final int FLAG_SENDING = 2;
    /**
     * Transfer flag: chunks arrive.
     */
    public static final int FLAG_RECEIVING = 4;
    /**
     * The flags of a transfer whose file could not be loaded or written.
     */
    public static final int FLAG_FAILED = 8;
    /**
     * The owner's state of a player that was sent the information and has not reported.
     */
    public static final int STATE_INFORMED = 0x10;
    /**
     * The subfolder of the first map folder that downloaded maps are saved to.
     */
    public static final @NotNull String DOWNLOAD_FOLDER = "Download";

    private static final @NotNull Logger LOGGER = Logger.of(LanMapTransfer.class);

    /**
     * The status of a transfer, to its owner: {@code {0, id, owner, flags, DWORD bytes held}}.
     */
    private static final int STATUS = 0;
    /**
     * The file information: {@code {1, id, DWORD size, DWORD checksum, name, 0}}.
     */
    static final int INFORMATION = 1;
    /**
     * The owner's order to a holder: {@code {2, id, target player, DWORD offset}}.
     */
    private static final int SEND = 2;
    /**
     * The owner's cancel: {@code {3, id}}.
     */
    private static final int CANCEL = 3;
    /**
     * A chunk: {@code {4, id, owner, DWORD offset, WORD length, data}}.
     */
    static final int CHUNK = 4;
    /**
     * A chunk's acknowledgement: {@code {5, id, owner, DWORD bytes held}}.
     */
    static final int ACKNOWLEDGE = 5;
    /**
     * The receiver's stop: {@code {6, id, owner}}.
     */
    private static final int STOP = 6;

    /**
     * The offset of the file id in every message.
     */
    private static final int IDENTIFIER_OFFSET = 1;
    /**
     * The offset of the owner in the status, chunk, acknowledgement and stop messages.
     */
    private static final int OWNER_OFFSET = 2;
    /**
     * The status layout: flags, bytes held, size.
     */
    private static final int STATUS_FLAGS_OFFSET = 3;
    private static final int STATUS_BYTES_OFFSET = 4;
    private static final int STATUS_SIZE = 8;
    /**
     * The information layout: size, checksum, then the name.
     */
    private static final int INFORMATION_SIZE_OFFSET = 2;
    private static final int INFORMATION_CHECKSUM_OFFSET = 6;
    static final int INFORMATION_NAME_OFFSET = 10;
    /**
     * The send order layout: target, offset, size.
     */
    private static final int SEND_TARGET_OFFSET = 2;
    private static final int SEND_START_OFFSET = 3;
    private static final int SEND_SIZE = 7;
    /**
     * The size of the cancel.
     */
    private static final int CANCEL_SIZE = 2;
    /**
     * The chunk layout: offset, length, then the data after the header.
     */
    private static final int CHUNK_START_OFFSET = 3;
    private static final int CHUNK_LENGTH_OFFSET = 7;
    static final int CHUNK_HEADER = 9;
    /**
     * The acknowledgement layout: bytes held, size.
     */
    private static final int ACKNOWLEDGE_BYTES_OFFSET = 3;
    private static final int ACKNOWLEDGE_SIZE = 7;
    /**
     * The size of the stop.
     */
    private static final int STOP_SIZE = 3;

    /**
     * The largest chunk.
     */
    private static final int CHUNK_LIMIT = 0x200;
    /**
     * The chunks of a send order and their size.
     */
    private static final int BURST_CHUNKS = 0x20;
    private static final int BURST_CHUNK = 0x100;
    /**
     * The name limit of the information (the name and its NUL within {@code 0x80} bytes).
     */
    private static final int NAME_LIMIT = 0x80;
    /**
     * The scale of the download percent.
     */
    private static final int PERCENT = 100;

    /**
     * The mask of an unsigned byte.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * The longest path in bytes: a received name fits a map folder when the folder's absolute path with its trailing separator, in UTF-8, and
     * the name are shorter together.
     */
    private static final int PATH_LIMIT = 0x104;

    /**
     * The send callback: wraps the data into message {@code 0x4f} for the player.
     */
    @FunctionalInterface
    public interface Sender {
        /**
         * Sends a transfer message to a player.
         *
         * @param target the player
         * @param data   the transfer message
         */
        void send(int target, byte @NotNull [] data);
    }

    /**
     * A file the local player shares as its owner.
     */
    public static final class SharedFile {
        private final int identifier;
        private final int size;
        private final int checksum;
        private final @NotNull Path path;
        private final @NotNull List<PlayerState> players;

        private SharedFile(int identifier, int size, int checksum, @NotNull Path path) {
            this.identifier = identifier;
            this.size       = size;
            this.checksum   = checksum;
            this.path       = path;
            this.players    = new ArrayList<>();
        }

        /**
         * @return the file id
         */
        public int getIdentifier() {
            return this.identifier;
        }

        /**
         * @return the file size
         */
        public int getSize() {
            return this.size;
        }

        /**
         * @return the checksum
         */
        public int getChecksum() {
            return this.checksum;
        }

        /**
         * @return the file
         */
        public @NotNull Path getPath() {
            return this.path;
        }
    }

    /**
     * The owner's record of one player.
     */
    private static final class PlayerState {
        private final int player;
        private int state;
        private int bytes;

        private PlayerState(int player) {
            this.player = player;
            this.state  = STATE_INFORMED;
            this.bytes  = 0;
        }
    }

    /**
     * One file a player receives or holds.
     */
    public static final class Transfer {
        private final int identifier;
        private final int owner;
        private final int size;
        private final int checksum;
        private @NotNull String name;

        private int flags;
        private int sendOffset;
        private int target;
        private int source;
        private int bytes;

        private final byte @NotNull [] data;
        private @Nullable Path file;
        private boolean downloading;

        private Transfer(int identifier, int owner, int size, int checksum) {
            this.identifier = identifier;
            this.owner      = owner;
            this.size       = size;
            this.checksum   = checksum;
            this.name       = "";

            this.flags      = FLAG_READY;
            this.sendOffset = 0;
            this.target     = 0;
            this.source     = 0;
            this.bytes      = 0;

            this.data        = new byte[size];
            this.file        = null;
            this.downloading = false;
        }

        /**
         * @return the file id
         */
        public int getIdentifier() {
            return this.identifier;
        }

        /**
         * @return the owner's player id
         */
        public int getOwner() {
            return this.owner;
        }

        /**
         * @return the file size
         */
        public int getSize() {
            return this.size;
        }

        /**
         * @return the checksum
         */
        public int getChecksum() {
            return this.checksum;
        }

        /**
         * @return the received name a download is saved under, empty when a local copy was found
         */
        public @NotNull String getName() {
            return this.name;
        }

        /**
         * @return the flags ({@link #FLAG_READY}, {@link #FLAG_SENDING}, {@link #FLAG_RECEIVING}, or {@link #FLAG_FAILED})
         */
        public int getFlags() {
            return this.flags;
        }

        /**
         * @return the bytes held
         */
        public int getBytes() {
            return this.bytes;
        }

        /**
         * @return the local file: the one found in the map folders, the shared one, or the downloaded one once written; {@code null} before
         */
        public @Nullable Path getFile() {
            return this.file;
        }
    }

    /**
     * Computes the file checksum: reflected CRC-32 from all ones without the final complement, the advertised {@code map_crc}.
     *
     * @param data   the file
     * @param length the bytes to check
     * @return the checksum
     */
    public static int calculateChecksum(byte @NotNull [] data, int length) {
        CRC32 crc = new CRC32();

        crc.update(data, 0, length);

        return (int) ~crc.getValue();
    }

    private static @NotNull String getFileName(@NotNull Path path) {
        Path name = path.getFileName();

        return name == null ? path.toString() : name.toString();
    }

    /**
     * Creates the match of a map file of a size and checksum: a file of that size whose {@link #calculateChecksum} is the checksum, whatever
     * its name.
     *
     * @param size     the map file's size
     * @param checksum the map file's checksum
     * @return the created {@link MapFileMatch} instance
     */
    public static @NotNull MapFileMatch createFileMatch(int size, int checksum) {
        return file -> {
            if (Files.size(file) != Integer.toUnsignedLong(size)) {
                return false;
            }

            byte[] contents = Files.readAllBytes(file);

            return calculateChecksum(contents, contents.length) == checksum;
        };
    }

    /**
     * Checks whether a received map name fits a map folder: for at least one read folder, the folder's absolute path with one trailing
     * separator, counted in UTF-8 bytes, and the name must be shorter together than the longest path. Without any folder every name fits.
     *
     * @param folders    the map folders
     * @param nameLength the name's length in bytes, without its NUL
     * @return {@code true} if the name fits a map folder, {@code false} otherwise
     */
    static boolean canHoldName(@NotNull MapFolders folders, int nameLength) {
        if (folders.getReadFolders().isEmpty()) {
            return true;
        }

        for (Path folder : folders.getReadFolders()) {
            String separator = folder.getFileSystem().getSeparator();
            String path = folder.toAbsolutePath().toString();
            String folderPath = path.endsWith(separator) ? path : path + separator;

            if (folderPath.getBytes(StandardCharsets.UTF_8).length + nameLength < PATH_LIMIT) {
                return true;
            }
        }

        return false;
    }


    private final int localPlayer;
    private final @NotNull Sender sender;
    private final int chunkSize;

    private final @NotNull List<SharedFile> sharedFiles;
    private final @NotNull List<Transfer> transfers;
    private int nextIdentifier;

    private @NotNull MapFolders folders;
    private @Nullable LanMapDownloader downloader;

    /**
     * Creates a {@link LanMapTransfer} instance.
     *
     * @param localPlayer the local player id
     * @param sender      the send callback
     * @param messageSize the provider's largest message less {@code 4}; the chunk size is {@code 9} less
     */
    public LanMapTransfer(int localPlayer, @NotNull Sender sender, int messageSize) {
        this.localPlayer = localPlayer;
        this.sender      = sender;
        this.chunkSize   = messageSize - CHUNK_HEADER;

        this.sharedFiles    = new ArrayList<>();
        this.transfers      = new ArrayList<>();
        this.nextIdentifier = 0;

        this.folders    = new MapFolders(List.of(), null);
        this.downloader = null;
    }

    /**
     * @return the chunk size
     */
    public int getChunkSize() {
        return this.chunkSize;
    }

    /**
     * @return the download percent of the lobby: the bytes held over the sizes of every transfer, {@code 0} without any
     */
    public int getDownloadPercent() {
        long size = 0;
        long bytes = 0;

        for (Transfer transfer : this.transfers) {
            size += Integer.toUnsignedLong(transfer.size);
            bytes += Integer.toUnsignedLong(transfer.bytes);
        }

        return size == 0 ? 0 : (int) (bytes * PERCENT / size & BYTE_MASK);
    }

    /**
     * @return the transfers, newest first
     */
    public @NotNull List<Transfer> getTransfers() {
        return List.copyOf(this.transfers);
    }

    /**
     * Sets the map folders: a received information searches them for a local copy, and a downloaded file goes to their save folder.
     *
     * @param folders the map folders
     */
    public void setFolders(@NotNull MapFolders folders) {
        this.folders = folders;
    }

    /**
     * @return the mod's downloader of missing maps, {@code null} when the chunks carry them
     */
    public @Nullable LanMapDownloader getDownloader() {
        return this.downloader;
    }

    /**
     * Sets the mod's downloader of missing maps: it gets the files shared before and after this call, and the maps whose information arrives
     * after it.
     *
     * @param downloader the downloader, or {@code null} for the chunks
     */
    public void setDownloader(@Nullable LanMapDownloader downloader) {
        this.downloader = downloader;

        if (downloader != null) {
            for (SharedFile shared : this.sharedFiles) {
                downloader.share(shared);
            }
        }
    }


    /**
     * Shares a file as its owner: its name must be shorter than {@code 0x80} bytes and the file must load with a size other than {@code 0}.
     *
     * @param path the file
     * @return the shared file, or {@code null} if it cannot be shared
     */
    public @Nullable SharedFile share(@NotNull Path path) {
        String name = getFileName(path);

        if (name.getBytes(StandardCharsets.UTF_8).length >= NAME_LIMIT || this.localPlayer == ProviderPeer.UNKNOWN_IDENTIFIER) {
            return null;
        }

        byte[] contents;

        try {
            contents = Files.readAllBytes(path);
        } catch (IOException exception) {
            LOGGER.warning("LAN MAP SHARE FAILED: " + exception.getMessage());

            return null;
        }

        if (contents.length == 0) {
            return null;
        }

        SharedFile shared = new SharedFile(this.nextIdentifier++ & BYTE_MASK, contents.length, calculateChecksum(contents, contents.length), path);

        this.sharedFiles.add(shared);

        LanMapDownloader current = this.downloader;

        if (current != null) {
            current.share(shared);
        }

        return shared;
    }

    /**
     * Adds a player to a shared file and sends it the information; a player already added is left as it is.
     *
     * @param shared the shared file
     * @param player the player id
     */
    public void addPlayer(@NotNull SharedFile shared, int player) {
        for (PlayerState state : shared.players) {
            if (state.player == player) {
                return;
            }
        }

        shared.players.addFirst(new PlayerState(player));

        String name = getFileName(shared.path);
        byte[] encoded = name.getBytes(StandardCharsets.UTF_8);

        // The name and its NUL within 0x80 bytes, else nothing is sent.
        if (encoded.length + 1 > NAME_LIMIT) {
            return;
        }

        ByteBuffer information = ByteBuffer.allocate(INFORMATION_NAME_OFFSET + encoded.length + 1).order(ByteOrder.LITTLE_ENDIAN);

        information.put((byte) INFORMATION).put((byte) shared.identifier).putInt(shared.size).putInt(shared.checksum).put(encoded).put((byte) 0);
        this.sender.send(player, information.array());
    }

    /**
     * Removes a player that left: its record leaves every shared file; the first transfer it owned is deleted, and
     * transfers that it fed or that fed it stop; every transfer then reports its status, except one a downloader took over.
     *
     * @param player the player id
     */
    public void removePlayer(int player) {
        for (SharedFile shared : this.sharedFiles) {
            shared.players.removeIf(state -> state.player == player);
        }

        for (Transfer transfer : this.transfers) {
            if (transfer.owner == player) {
                this.transfers.remove(transfer);

                break;
            }
        }

        for (Transfer transfer : List.copyOf(this.transfers)) {
            if ((transfer.flags & FLAG_RECEIVING) != 0 && transfer.source == player) {
                transfer.flags &= ~FLAG_RECEIVING;
            }

            if ((transfer.flags & FLAG_SENDING) != 0 && transfer.target == player) {
                transfer.flags &= ~FLAG_SENDING;
            }

            if (!transfer.downloading) {
                this.sendStatus(transfer);
            }
        }
    }


    /**
     * Checks a shared file for the lobby's start: every player idle with all bytes.
     *
     * @param shared the shared file
     * @return {@code true} if every player holds the whole file, {@code false} otherwise
     */
    public boolean isComplete(@NotNull SharedFile shared) {
        for (PlayerState state : shared.players) {
            if (state.state != FLAG_READY || state.bytes != shared.size) {
                return false;
            }
        }

        return true;
    }

    /**
     * @return {@code true} if a transfer failed (the lobby step then shows {@code chatFDERROR}), {@code false} otherwise
     */
    public boolean hasFailed() {
        for (Transfer transfer : this.transfers) {
            if (transfer.flags == FLAG_FAILED) {
                return true;
            }
        }

        return false;
    }


    /**
     * Handles a transfer message.
     *
     * @param sender the sending player
     * @param data   the transfer message (the {@code 0x4f} message's data)
     */
    public void receive(int sender, byte @NotNull [] data) {
        if (data.length == 0) {
            return;
        }

        switch (data[0] & BYTE_MASK) {
            case STATUS -> this.receiveStatus(sender, data);
            case INFORMATION -> this.receiveInformation(sender, data);
            case SEND -> this.receiveSendOrder(sender, data);
            case CANCEL -> this.receiveCancelOrder(sender, data);
            case CHUNK -> this.receiveChunk(sender, data);
            case ACKNOWLEDGE -> this.receiveAcknowledgement(data);
            case STOP -> this.receiveStop(data);
            default -> {
                // Other message types are ignored.
            }
        }
    }

    /**
     * Handles a status ({@code 8} bytes, the owner byte must be the local player): the player's state and bytes, then while an
     * idle player without all bytes and an idle player with all bytes exist, the first holder is ordered to send to the
     * first needer from its byte count; the needer's state becomes {@link #FLAG_RECEIVING}, the holder's {@link #FLAG_SENDING}.
     *
     * @param sender the reporting player
     * @param data   the status
     */
    private void receiveStatus(int sender, byte @NotNull [] data) {
        if (data.length != STATUS_SIZE || (data[OWNER_OFFSET] & BYTE_MASK) != this.localPlayer) {
            return;
        }

        SharedFile shared = this.findShared(data[IDENTIFIER_OFFSET] & BYTE_MASK);

        if (shared == null) {
            return;
        }

        PlayerState reporter = null;

        for (PlayerState state : shared.players) {
            if (state.player == sender) {
                reporter = state;

                break;
            }
        }

        if (reporter == null) {
            return;
        }

        reporter.state = data[STATUS_FLAGS_OFFSET] & BYTE_MASK;
        reporter.bytes = LittleEndianUtils.readInt32(data, STATUS_BYTES_OFFSET);

        while (true) {
            PlayerState needer = null;
            PlayerState holder = null;

            for (PlayerState state : shared.players) {
                if (needer == null && state.state == FLAG_READY && state.bytes != shared.size) {
                    needer = state;
                }

                if (holder == null && state.state == FLAG_READY && state.bytes == shared.size) {
                    holder = state;
                }

                if (needer != null && holder != null) {
                    break;
                }
            }

            if (needer == null || holder == null) {
                return;
            }

            ByteBuffer order = ByteBuffer.allocate(SEND_SIZE).order(ByteOrder.LITTLE_ENDIAN);

            order.put((byte) SEND).put((byte) shared.identifier).put((byte) needer.player).putInt(needer.bytes);
            this.sender.send(holder.player, order.array());
            LOGGER.info("LAN MAP TRANSFER ORDER: file " + shared.identifier + ", player " + holder.player +
                        " sends to player " + needer.player + " from byte " + Integer.toUnsignedString(needer.bytes));

            needer.state = FLAG_RECEIVING;
            holder.state = FLAG_SENDING;
        }
    }

    /**
     * Handles an information (at least {@code 10} bytes, size not {@code 0}, a valid name that fits a map folder's path and passes the
     * file name check): a new transfer; the local copy is searched (the shared file when the local player owns it, else
     * the first file of the same size and checksum in the map folders, whatever its name) and loaded, else the transfer
     * keeps the name for the download; then the status goes to the owner, unless the downloader took the download over.
     *
     * @param sender the transfer's owner
     * @param data   the information
     */
    private void receiveInformation(int sender, byte @NotNull [] data) {
        if (data.length < INFORMATION_NAME_OFFSET) {
            return;
        }

        int size = LittleEndianUtils.readInt32(data, INFORMATION_SIZE_OFFSET);

        if (size == 0) {
            return;
        }

        int nameLength = TerminatedStringUtils.getTerminatedLength(data, INFORMATION_NAME_OFFSET, NAME_LIMIT);

        if (nameLength == 0) {
            return;
        }

        String name = new String(data, INFORMATION_NAME_OFFSET, nameLength - 1, StandardCharsets.UTF_8);

        if (!canHoldName(this.folders, nameLength - 1)) {
            return;
        }

        // The English bad characters: the lobby has no localized text, and the installed locales hold the same text.
        if (!FileNameUtils.isValidFileName(data, INFORMATION_NAME_OFFSET, nameLength, FileNameUtils.BAD_CHARACTERS)) {
            return;
        }

        if (size < 0) {
            LOGGER.warning("LAN MAP TRANSFER: file " + (data[IDENTIFIER_OFFSET] & BYTE_MASK) + " of " + Integer.toUnsignedString(size) +
                           " bytes refused");

            return;
        }

        Transfer transfer = new Transfer(data[IDENTIFIER_OFFSET] & BYTE_MASK, sender,
                                         size, LittleEndianUtils.readInt32(data, INFORMATION_CHECKSUM_OFFSET));

        this.transfers.addFirst(transfer);

        Path local = this.findLocalFile(transfer);

        if (local != null) {
            try {
                byte[] contents = Files.readAllBytes(local);
                int length = Math.min(contents.length, size);

                System.arraycopy(contents, 0, transfer.data, 0, length);
                transfer.bytes = length;
                transfer.file  = local;
            } catch (IOException exception) {
                LOGGER.warning("LAN MAP TRANSFER: " + local + " could not be read: " + exception.getMessage());
                transfer.flags = FLAG_FAILED;

                return;
            }

            LOGGER.info("LAN MAP FOUND: " + name + " as " + local + " (" + Integer.toUnsignedString(size) + " bytes)");
        } else {
            transfer.name = new String(data, INFORMATION_NAME_OFFSET, Math.min(nameLength - 1, NAME_LIMIT - 1), StandardCharsets.UTF_8);

            LOGGER.info("LAN MAP MISSING: " + name + " (" + Integer.toUnsignedString(size) + " bytes, crc " + Integer.toHexString(transfer.checksum) +
                        "), downloading from player " + sender);

            LanMapDownloader current = this.downloader;

            if (current != null && current.download(transfer)) {
                transfer.downloading = true;

                return;
            }
        }

        this.sendStatus(transfer);
    }

    /**
     * Handles a send order ({@code 7} bytes, from the transfer's owner): unless already sending, the transfer sends to the
     * target from the offset, {@code 32} chunks of {@code 256} bytes.
     *
     * @param sender the transfer's owner
     * @param data   the send order
     */
    private void receiveSendOrder(int sender, byte @NotNull [] data) {
        if (data.length != SEND_SIZE) {
            return;
        }

        Transfer transfer = this.findTransfer(data[IDENTIFIER_OFFSET] & BYTE_MASK, sender);

        if (transfer == null || (transfer.flags & FLAG_SENDING) != 0) {
            return;
        }

        transfer.flags |= FLAG_SENDING;
        transfer.sendOffset = LittleEndianUtils.readInt32(data, SEND_START_OFFSET);
        transfer.target     = data[SEND_TARGET_OFFSET] & BYTE_MASK;

        for (int chunk = 0; chunk < BURST_CHUNKS; chunk++) {
            transfer.sendOffset += this.sendChunk(transfer, BURST_CHUNK);
        }
    }

    /**
     * Handles a cancel ({@code 2} bytes, from the transfer's owner): the transfer is deleted.
     *
     * @param sender the transfer's owner
     * @param data   the cancel
     */
    private void receiveCancelOrder(int sender, byte @NotNull [] data) {
        if (data.length != CANCEL_SIZE) {
            return;
        }

        Transfer transfer = this.findTransfer(data[IDENTIFIER_OFFSET] & BYTE_MASK, sender);

        if (transfer != null) {
            this.transfers.remove(transfer);
        }
    }

    /**
     * Handles a chunk ({@code 9} bytes and its length): taken only at the bytes held and within the size; then the
     * acknowledgement with the new byte count goes to the sender, and once complete the checksum is checked (a mismatch
     * drops all bytes) and the file written. Any other chunk makes the receiver tell the sender to stop and report its status.
     * A chunk of a transfer a downloader took over is dropped.
     *
     * @param sender the sending player
     * @param data   the chunk
     */
    private void receiveChunk(int sender, byte @NotNull [] data) {
        if (data.length < CHUNK_HEADER) {
            return;
        }

        int length = LittleEndianUtils.readUnsigned16(data, CHUNK_LENGTH_OFFSET);

        if (data.length != length + CHUNK_HEADER) {
            return;
        }

        Transfer transfer = this.findTransfer(data[IDENTIFIER_OFFSET] & BYTE_MASK, data[OWNER_OFFSET] & BYTE_MASK);

        if (transfer == null || transfer.downloading) {
            return;
        }

        int offset = LittleEndianUtils.readInt32(data, CHUNK_START_OFFSET);

        if (offset == transfer.size || Integer.compareUnsigned(offset + length, transfer.size) > 0 || offset != transfer.bytes) {
            this.sender.send(sender, new byte[]{STOP, (byte) transfer.identifier, (byte) transfer.owner});
            this.sendStatus(transfer);

            return;
        }

        transfer.flags |= FLAG_RECEIVING;
        transfer.source = sender;
        System.arraycopy(data, CHUNK_HEADER, transfer.data, offset, length);
        transfer.bytes += length;

        ByteBuffer acknowledgement = ByteBuffer.allocate(ACKNOWLEDGE_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        acknowledgement.put((byte) ACKNOWLEDGE).put((byte) transfer.identifier).put((byte) transfer.owner).putInt(transfer.bytes);
        this.sender.send(transfer.source, acknowledgement.array());

        if (transfer.bytes != transfer.size) {
            return;
        }

        if (calculateChecksum(transfer.data, transfer.size) != transfer.checksum) {
            LOGGER.warning("LAN MAP TRANSFER: file " + transfer.identifier + " checksum mismatch, starting over");
            transfer.flags &= ~FLAG_RECEIVING;
            transfer.bytes = 0;
            this.sendStatus(transfer);

            return;
        }

        if (!this.writeDownloadedFile(transfer)) {
            transfer.flags = FLAG_FAILED;

            return;
        }

        transfer.flags &= ~FLAG_RECEIVING;
        this.sendStatus(transfer);
    }

    /**
     * Handles an acknowledgement ({@code 7} bytes): while sending, the next chunk of the chunk size goes out until the
     * acknowledged count reaches the size; then sending ends and the status goes to the owner.
     *
     * @param data the acknowledgement
     */
    private void receiveAcknowledgement(byte @NotNull [] data) {
        if (data.length != ACKNOWLEDGE_SIZE) {
            return;
        }

        Transfer transfer = this.findTransfer(data[IDENTIFIER_OFFSET] & BYTE_MASK, data[OWNER_OFFSET] & BYTE_MASK);

        if (transfer == null || (transfer.flags & FLAG_SENDING) == 0) {
            return;
        }

        if (Integer.compareUnsigned(LittleEndianUtils.readInt32(data, ACKNOWLEDGE_BYTES_OFFSET), transfer.size) < 0) {
            transfer.sendOffset += this.sendChunk(transfer, this.chunkSize);

            return;
        }

        transfer.flags &= ~FLAG_SENDING;
        this.sendStatus(transfer);
    }

    /**
     * Handles a stop ({@code 3} bytes): sending ends and the status goes to the owner.
     *
     * @param data the stop
     */
    private void receiveStop(byte @NotNull [] data) {
        if (data.length != STOP_SIZE) {
            return;
        }

        Transfer transfer = this.findTransfer(data[IDENTIFIER_OFFSET] & BYTE_MASK, data[OWNER_OFFSET] & BYTE_MASK);

        if (transfer == null) {
            return;
        }

        transfer.flags &= ~FLAG_SENDING;
        this.sendStatus(transfer);
    }

    /**
     * Sends the chunk at the send offset to the target: at most {@code requested} (and {@code 0x200}) bytes within the
     * size; past the bytes held, sending ends and an empty chunk at the bytes held goes out.
     *
     * @param transfer  the sending transfer
     * @param requested the requested chunk length
     * @return the bytes sent, {@code 0} once the offset reached the size (nothing is sent then)
     */
    private int sendChunk(@NotNull Transfer transfer, int requested) {
        int offset = transfer.sendOffset;

        if (Integer.compareUnsigned(offset, transfer.size) >= 0) {
            return 0;
        }

        int limit = Integer.compareUnsigned(requested, CHUNK_LIMIT) < 0 ? requested : CHUNK_LIMIT;
        int length = Integer.compareUnsigned(offset + limit, transfer.size) <= 0 ? limit : transfer.size - offset;

        if (Integer.compareUnsigned(offset + length, transfer.bytes) > 0) {
            transfer.flags &= ~FLAG_SENDING;
            offset = transfer.bytes;
            length = 0;
        }

        ByteBuffer chunk = ByteBuffer.allocate(CHUNK_HEADER + length).order(ByteOrder.LITTLE_ENDIAN);

        chunk.put((byte) CHUNK).put((byte) transfer.identifier).put((byte) transfer.owner).putInt(offset).putShort((short) length);
        chunk.put(transfer.data, offset, length);
        this.sender.send(transfer.target, chunk.array());

        return length;
    }

    /**
     * Sets the bytes a downloader holds of a map it downloads, for the download percent; at most one less than the size.
     *
     * @param transfer the transfer the downloader took over
     * @param bytes    the bytes held
     */
    public void reportProgress(@NotNull Transfer transfer, int bytes) {
        if (this.transfers.contains(transfer) && transfer.file == null) {
            transfer.bytes = Math.clamp(bytes, 0, transfer.size - 1);
        }
    }

    /**
     * Completes a download a downloader took over with the whole file: a file of the transfer's size and checksum is saved as a received one
     * and the status with every byte goes to the owner; another file hands the map back to the chunks ({@link #downloadNatively}).
     *
     * @param transfer the transfer the downloader took over
     * @param contents the downloaded file
     * @return {@code true} if the file was saved, {@code false} otherwise
     */
    public boolean completeDownload(@NotNull Transfer transfer, byte @NotNull [] contents) {
        if (!this.transfers.contains(transfer) || transfer.file != null) {
            return false;
        }

        if (contents.length != transfer.size || calculateChecksum(contents, contents.length) != transfer.checksum) {
            LOGGER.warning("LAN MAP DOWNLOAD: file " + transfer.identifier + " does not match its information, downloading it in chunks");
            this.downloadNatively(transfer);

            return false;
        }

        System.arraycopy(contents, 0, transfer.data, 0, contents.length);
        transfer.bytes       = transfer.size;
        transfer.downloading = false;

        if (!this.writeDownloadedFile(transfer)) {
            transfer.flags = FLAG_FAILED;

            return false;
        }

        this.sendStatus(transfer);

        return true;
    }

    /**
     * Hands a download a downloader took over back to the chunks: the transfer holds no byte, and its status goes to the owner.
     *
     * @param transfer the transfer the downloader took over
     */
    public void downloadNatively(@NotNull Transfer transfer) {
        if (!this.transfers.contains(transfer) || transfer.file != null) {
            return;
        }

        transfer.bytes       = 0;
        transfer.downloading = false;
        this.sendStatus(transfer);
    }

    /**
     * Closes the downloader, if any; the transfers it took over stay where they are.
     */
    public void closeDownloader() {
        LanMapDownloader current = this.downloader;

        if (current != null) {
            current.close();
        }
    }

    /**
     * Sends the status to the transfer's owner.
     *
     * @param transfer the transfer
     */
    private void sendStatus(@NotNull Transfer transfer) {
        ByteBuffer status = ByteBuffer.allocate(STATUS_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        status.put((byte) STATUS).put((byte) transfer.identifier).put((byte) transfer.owner).put((byte) transfer.flags).putInt(transfer.bytes);
        this.sender.send(transfer.owner, status.array());
    }

    /**
     * Finds the local copy of a transfer's file: the shared file when the local player owns the transfer, else the first
     * file of the transfer's size and checksum in the map folders ({@link #createFileMatch}).
     *
     * @param transfer the transfer
     * @return the local copy, or {@code null} if none is found
     */
    private @Nullable Path findLocalFile(@NotNull Transfer transfer) {
        if (transfer.owner == this.localPlayer) {
            SharedFile shared = this.findShared(transfer.identifier);

            return shared == null ? null : shared.path;
        }

        return this.folders.findFile(createFileMatch(transfer.size, transfer.checksum));
    }

    /**
     * Saves the received file into the save folder of the map folders under its name ({@link MapFolders#saveFile}): a file of the same
     * contents is taken as it is, another file is never replaced, and the download takes the next free name instead. Path, folder or write
     * failures return {@code false}; the local file is recorded only after a successful save.
     *
     * @param transfer the completely received transfer
     * @return {@code true} if the file was written or found, {@code false} otherwise
     */
    private boolean writeDownloadedFile(@NotNull Transfer transfer) {
        Path written;

        try {
            written = this.folders.saveFile(transfer.name, transfer.data);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("LAN MAP DOWNLOAD NOT WRITTEN: " + transfer.name + ": " + exception.getMessage());

            return false;
        }

        transfer.file = written;
        LOGGER.info("LAN MAP DOWNLOADED: " + written + " (" + Integer.toUnsignedString(transfer.size) + " bytes)");

        return true;
    }

    private @Nullable SharedFile findShared(int identifier) {
        for (SharedFile shared : this.sharedFiles) {
            if (shared.identifier == identifier) {
                return shared;
            }
        }

        return null;
    }

    private @Nullable Transfer findTransfer(int identifier, int owner) {
        for (Transfer transfer : this.transfers) {
            if (transfer.identifier == identifier && transfer.owner == owner) {
                return transfer;
            }
        }

        return null;
    }


    /**
     * Ends the transfers when the local player leaves the game: the players of the lobby's map, if it is shared, are sent a
     * cancel, newest first; then every transfer and shared file is dropped without another message, and the downloader is closed.
     *
     * @param lobbyMap the lobby's shared map, {@code null} if the local player shares none
     */
    public void close(@Nullable SharedFile lobbyMap) {
        if (lobbyMap != null) {
            for (PlayerState state : lobbyMap.players) {
                this.sender.send(state.player, new byte[]{CANCEL, (byte) lobbyMap.identifier});
            }
        }

        this.transfers.clear();
        this.sharedFiles.clear();
        this.closeDownloader();
    }
}
