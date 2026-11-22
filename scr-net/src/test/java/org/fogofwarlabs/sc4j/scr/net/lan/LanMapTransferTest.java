package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.engine.net.lobby.MapFolders;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Verifies the lobby's map transfer ({@link LanMapTransfer}, messages {@code 0x4f}) between sc4j clients in memory: a
 * joiner without the map downloads it from the host into its download folder byte for byte, with the download percent
 * ({@code 0x3d}) rising to {@code 100} on every client, the host's start check ({@code chatFILETRANSFERRING}) passing
 * only then; a joiner that has the map feeds another joiner (the owner pairs the first needer with the first holder),
 * and the download resumes from the host when that holder leaves.
 */
final class LanMapTransferTest {
    private static final byte @NotNull [] GAME_DATA = LanGameOptions.MELEE.encode();

    private static final int HOST_PLAYER = 0;
    private static final int JOINER_PLAYER = 1;
    private static final int SECOND_JOINER_PLAYER = 2;
    private static final int NO_PLAYER = -1;
    /**
     * The clients of a download and of a map check: the host and one joiner.
     */
    private static final int DOWNLOAD_CLIENTS = 2;
    /**
     * The clients of the feeding test: the host, the feeder and the needer.
     */
    private static final int FEEDING_CLIENTS = 3;
    private static final int LATENCY = 10;

    private static final int TILESET = 1;
    private static final int MAP_SIZE = 128;
    private static final int FORCE_FLAGS_SIZE = 8;

    /**
     * The largest map transfer message of a lobby: the provider's largest message less the lobby's reserve.
     */
    private static final int MESSAGE_LIMIT = ProviderSession.MAXIMUM_MESSAGE_SIZE - LanLobby.MAP_TRANSFER_RESERVE;
    /**
     * The chunks of a send order and their size, then the size of every later chunk.
     */
    private static final int BURST_CHUNKS = 32;
    private static final int BURST_CHUNK = 256;
    private static final int CHUNK = 483;

    private static final int INFORMATION_TYPE = 1;
    private static final int CHUNK_TYPE = 4;
    private static final int ACKNOWLEDGE_TYPE = 5;
    private static final int INFORMATION_NAME_OFFSET = 10;
    private static final int CHUNK_OFFSET_OFFSET = 3;
    private static final int CHUNK_LENGTH_OFFSET = 7;
    private static final int BYTE_MASK = 0xFF;

    private static final @NotNull String RANDOM_MAP = "random map.scx";
    /**
     * The size of the random map of the chunk test.
     */
    private static final int CHUNKED_MAP_SIZE = 50_000;
    /**
     * The size of the random map of the file name test.
     */
    private static final int NAME_CHECK_MAP_SIZE = 100;
    /**
     * The size of the random map of the map copy test.
     */
    private static final int MAP_COPY_SIZE = 1000;
    /**
     * The size of the random map of the feeding test.
     */
    private static final int FED_MAP_SIZE = 200_000;
    /**
     * The random seed of the chunk test map.
     */
    private static final long CHUNKED_MAP_SEED = 7;
    /**
     * The random seed of the file name test map.
     */
    private static final long NAME_CHECK_MAP_SEED = 3;
    /**
     * The random seed of the map copy test map.
     */
    private static final long MAP_COPY_SEED = 5;
    /**
     * The random seed of the feeding test map.
     */
    private static final long FED_MAP_SEED = 11;
    /**
     * The fewest percent values the zergling.scm download reports.
     */
    private static final int MINIMUM_ZERGLING_PERCENT_VALUES = 3;
    /**
     * The index of the sender in a queued wire message.
     */
    private static final int MESSAGE_SENDER = 0;
    /**
     * The index of the target in a queued wire message.
     */
    private static final int MESSAGE_TARGET = 1;
    /**
     * The index of the message bytes in a queued wire message.
     */
    private static final int MESSAGE_DATA = 2;

    /**
     * The longest time of a download, of the host's map check and of the feeder's first sends.
     */
    private static final int DOWNLOAD_LIMIT_MILLISECONDS = 60_000;
    private static final int MAP_CHECK_LIMIT_MILLISECONDS = 5000;
    private static final int FEED_LIMIT_MILLISECONDS = 10_000;
    /**
     * The bytes the second joiner holds from the feeder before the feeder leaves.
     */
    private static final int FED_BYTES = 20_000;

    private static final int COMPLETE_PERCENT = 100;
    private static final int FLAG_READY = 1;
    private static final int FLAG_SENDING = 2;
    private static final int FLAG_FAILED = 8;

    /**
     * A folder path so long that a name of {@link #NAME_LENGTH} bytes does not fit the longest path with it.
     */
    private static final int LONG_FOLDER_LENGTH = 200;
    private static final int NAME_LENGTH = 100;
    /**
     * The longest path in bytes: a map folder with its trailing separator and a received name must be shorter together.
     */
    private static final int LONGEST_PATH = 0x104;
    /**
     * A folder name of two characters that take three bytes each in UTF-8.
     */
    private static final @NotNull String MULTI_BYTE_FOLDER = "지도";

    /**
     * Creates a {@link LanLobby.Setup} instance with open slots {@code 0..players-1} and a {@code 128x128} Melee map.
     *
     * @param players     the open slots, the host's included
     * @param mapName     the map file name
     * @param mapSize     the map file size
     * @param mapChecksum the map file checksum
     * @return the created {@link LanLobby.Setup} instance
     */
    static @NotNull LanLobby.Setup createOpenLobbySetup(int players, @NotNull String mapName, int mapSize, int mapChecksum) {
        MeleeGameSetup.PlayerRecord[] records = new MeleeGameSetup.PlayerRecord[LanLobby.RECORDS];
        byte[] types = new byte[LanLobby.RECORDS];
        byte[] lobbyData = new byte[LanLobby.LOBBY_DATA_SIZE];

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            boolean open = slot < players;

            types[slot]   = (byte) (open ? LanLobby.TYPE_OPEN : PlayerTypes.NONE);
            records[slot] = slot == 0 ? new MeleeGameSetup.PlayerRecord(HOST_PLAYER, PlayerTypes.HUMAN, 0, 0, "host")
                                      : new MeleeGameSetup.PlayerRecord(NO_PLAYER, open ? LanLobby.TYPE_OPEN : PlayerTypes.NONE, 0, 0, "");
        }

        for (int slot = PlayerTable.PLAYABLE_PLAYER_SLOTS; slot < LanLobby.RECORDS; slot++) {
            records[slot] = new MeleeGameSetup.PlayerRecord(NO_PLAYER, PlayerTypes.CLOSED, 0, 0, "");
        }

        for (int index = LanLobby.LOBBY_DATA_COLORS; index < LanLobby.LOBBY_DATA_COLORS + PlayerTable.PLAYABLE_PLAYER_SLOTS; index++) {
            lobbyData[index] = (byte) LanLobby.COLOR_DEFAULT;
        }

        return new LanLobby.Setup(TILESET,
                                  MAP_SIZE, MAP_SIZE,
                                  types, new byte[LanLobby.RECORDS], types.clone(),
                                  new byte[LanLobby.RECORDS], new byte[FORCE_FLAGS_SIZE],
                                  lobbyData,
                                  records,
                                  mapName, mapSize, mapChecksum);
    }

    /**
     * Runs one download from the host and checks the outcome.
     *
     * @param source the map file
     * @param folder the folder of the joiner's maps
     * @return the joiner's download percents (each change)
     * @throws Exception if a file cannot be read or written
     */
    private static @NotNull List<Integer> downloadAndVerifyMap(@NotNull Path source, @NotNull Path folder) throws Exception {
        byte[] contents = Files.readAllBytes(source);
        InMemoryLanScenario network = new InMemoryLanScenario(DOWNLOAD_CLIENTS);

        network.latency = LATENCY;
        network.host(createOpenLobbySetup(DOWNLOAD_CLIENTS, source.getFileName().toString(),
                                          contents.length, LanMapTransfer.calculateChecksum(contents, contents.length)),
                     GAME_DATA, source);

        Path maps = Files.createDirectories(folder.resolve("joiner"));
        List<Integer> percents = new ArrayList<>();
        List<Integer> reported = new ArrayList<>();

        network.afterStep = () -> {
            LanLobby host = network.lobbies[HOST_PLAYER];
            LanLobby joiner = network.lobbies[JOINER_PLAYER];

            if (joiner == null || joiner.getMapTransfer().getTransfers().isEmpty()) {
                return;
            }

            Assertions.assertNotNull(host);
            Assertions.assertFalse(host.isMapTransferComplete() && joiner.getMapFile() == null, "the start check waits for the transfer");

            int percent = joiner.getMapTransfer().getDownloadPercent();
            int seen = host.getDownloadPercent(JOINER_PLAYER);

            if (percents.isEmpty() || percents.getLast() != percent) {
                percents.add(percent);
            }

            if (seen >= 0 && (reported.isEmpty() || reported.getLast() != seen)) {
                reported.add(seen);
            }
        };

        network.join(JOINER_PLAYER, maps, maps.resolve("Download"));

        LanLobby host = network.lobbies[HOST_PLAYER];
        LanLobby joiner = network.lobbies[JOINER_PLAYER];

        Assertions.assertNotNull(host);
        Assertions.assertNotNull(joiner);

        network.runUntil(DOWNLOAD_LIMIT_MILLISECONDS,
                         lan -> host.isMapTransferComplete() && joiner.getMapFile() != null &&
                                host.getDownloadPercent(JOINER_PLAYER) == COMPLETE_PERCENT);

        Assertions.assertTrue(host.isMapTransferComplete());

        Path downloaded = joiner.getMapFile();

        Assertions.assertEquals(maps.resolve("Download").resolve(source.getFileName().toString()), downloaded);
        Assertions.assertNotNull(downloaded);
        Assertions.assertArrayEquals(contents, Files.readAllBytes(downloaded));
        Assertions.assertEquals(COMPLETE_PERCENT, host.getDownloadPercent(JOINER_PLAYER), "the host sees the joiner's 0x3d");
        Assertions.assertEquals(COMPLETE_PERCENT, joiner.getDownloadPercent(HOST_PLAYER), "the joiner sees the host's 0x3d");
        Assertions.assertEquals(COMPLETE_PERCENT, percents.getLast());

        for (int index = 1; index < percents.size(); index++) {
            Assertions.assertTrue(percents.get(index) > percents.get(index - 1), "the percent rises: " + percents);
        }

        Assertions.assertEquals(COMPLETE_PERCENT, reported.getLast());

        for (int index = 1; index < reported.size(); index++) {
            Assertions.assertTrue(reported.get(index) > reported.get(index - 1), "the reported percent rises: " + reported);
        }

        return percents;
    }

    /**
     * Creates a {@link LanMapTransfer} instance of the joiner that sends no message.
     *
     * @return the created {@link LanMapTransfer} instance
     */
    private static @NotNull LanMapTransfer createSilentReceiver() {
        return new LanMapTransfer(JOINER_PLAYER, (target, data) -> {
        }, MESSAGE_LIMIT);
    }

    private static byte @NotNull [] createMapInformation(@NotNull String name, int size, int checksum) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer information = ByteBuffer.allocate(INFORMATION_NAME_OFFSET + bytes.length + 1).order(ByteOrder.LITTLE_ENDIAN);

        information.put((byte) INFORMATION_TYPE).put((byte) 0).putInt(size).putInt(checksum).put(bytes).put((byte) 0);

        return information.array();
    }

    /**
     * Receives a whole file in one chunk into a download folder that no search folder holds.
     *
     * @param download the download folder
     * @param name     the received name
     * @param contents the file bytes
     * @return the received transfer
     */
    private static @NotNull LanMapTransfer.Transfer receiveWhole(@NotNull Path download, @NotNull String name, byte @NotNull [] contents) {
        LanMapTransfer receiver = createSilentReceiver();
        ByteBuffer chunk = ByteBuffer.allocate(LanMapTransfer.CHUNK_HEADER + contents.length).order(ByteOrder.LITTLE_ENDIAN);

        receiver.setFolders(new MapFolders(List.of(), download));
        receiver.receive(HOST_PLAYER, createMapInformation(name, contents.length, LanMapTransfer.calculateChecksum(contents, contents.length)));
        chunk.put((byte) CHUNK_TYPE).put((byte) 0).put((byte) 0).putInt(0).putShort((short) contents.length).put(contents);
        receiver.receive(HOST_PLAYER, chunk.array());

        return receiver.getTransfers().getFirst();
    }


    @RequiresInstallation
    @Test
    void joiner_without_the_map_downloads_zergling_and_bottleneck_byte_for_byte(@TempDir @NotNull Path folder) throws Exception {
        Path zergling = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, "zergling.scm");
        Path bottleneck = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, LanTestResources.CAPTURED_MAP_FILE_NAME);

        List<Integer> zerglingPercents = downloadAndVerifyMap(zergling, folder.resolve("zergling"));
        List<Integer> bottleneckPercents = downloadAndVerifyMap(bottleneck, folder.resolve("bottleneck"));

        System.out.println("LAN MAP TRANSFER PERCENTS: zergling.scm " + zerglingPercents + ", (2)Bottleneck.scm " + bottleneckPercents);

        // Bottleneck (68376 bytes) passes through more percent values than zergling.scm (35255 bytes).
        Assertions.assertTrue(zerglingPercents.size() > MINIMUM_ZERGLING_PERCENT_VALUES, zerglingPercents.toString());
        Assertions.assertTrue(bottleneckPercents.size() > zerglingPercents.size(), bottleneckPercents + " / " + zerglingPercents);
    }

    /**
     * Checks the wire of a download: the host's own information comes back to itself and its transfer holds the file; the joiner's status
     * {@code {0, id, owner, 1, 0}}; the send order; {@code 32} chunks of {@code 256} bytes, then one chunk of {@code 483} bytes ({@code 496 - 4 - 9})
     * per acknowledgement; the joiner's final status {@code {0, 0, 0, 1, size}}.
     *
     * @param folder the temporary folder of the map and the joiner's map folder
     */
    @Test
    void chunks_have_the_native_sizes_and_every_chunk_is_acknowledged(@TempDir @NotNull Path folder) throws Exception {
        byte[] contents = new byte[CHUNKED_MAP_SIZE];

        new Random(CHUNKED_MAP_SEED).nextBytes(contents);

        Path source = folder.resolve(RANDOM_MAP);

        Files.write(source, contents);

        int checksum = LanMapTransfer.calculateChecksum(contents, contents.length);
        List<byte[]> fromJoiner = new ArrayList<>();
        List<byte[]> fromHost = new ArrayList<>();
        // Two transfers wired directly: player 0 owns the file, player 1 has an empty folder.
        List<Object[]> queue = new ArrayList<>();
        LanMapTransfer[] transfers = new LanMapTransfer[DOWNLOAD_CLIENTS];

        transfers[HOST_PLAYER]   = new LanMapTransfer(HOST_PLAYER,
                                                      (target, data) -> queue.add(new Object[]{HOST_PLAYER, target, data}),
                                                      MESSAGE_LIMIT);
        transfers[JOINER_PLAYER] = new LanMapTransfer(JOINER_PLAYER,
                                                      (target, data) -> queue.add(new Object[]{JOINER_PLAYER, target, data}),
                                                      MESSAGE_LIMIT);

        Path maps = Files.createDirectories(folder.resolve("maps"));

        transfers[JOINER_PLAYER].setFolders(MapFolders.of(List.of(maps), LanMapTransfer.DOWNLOAD_FOLDER));

        LanMapTransfer.SharedFile shared = transfers[HOST_PLAYER].share(source);

        Assertions.assertNotNull(shared);
        Assertions.assertEquals(CHUNK, transfers[HOST_PLAYER].getChunkSize());

        transfers[HOST_PLAYER].addPlayer(shared, HOST_PLAYER);
        transfers[HOST_PLAYER].addPlayer(shared, JOINER_PLAYER);

        while (!queue.isEmpty()) {
            Object[] message = queue.removeFirst();
            int sender = (int) message[MESSAGE_SENDER];
            int target = (int) message[MESSAGE_TARGET];
            byte[] data = (byte[]) message[MESSAGE_DATA];

            (sender == HOST_PLAYER ? fromHost : fromJoiner).add(data);
            transfers[target].receive(sender, data);
        }

        byte[] information = createMapInformation(RANDOM_MAP, contents.length, checksum);

        Assertions.assertArrayEquals(information, fromHost.get(0), "the information to the host itself");
        Assertions.assertArrayEquals(information, fromHost.get(1), "the information to the joiner");
        Assertions.assertEquals("00000001" + "50c30000", HexFormat.of().formatHex(fromHost.get(2)), "the host holds all bytes");
        Assertions.assertEquals("0000000100000000", HexFormat.of().formatHex(fromJoiner.get(0)), "the joiner holds no byte");
        Assertions.assertEquals("02000100000000", HexFormat.of().formatHex(fromHost.get(3)), "the host orders itself to send to player 1 from 0");

        int chunks = 0;
        int offset = 0;

        for (byte[] data : fromHost) {
            if (data[0] != CHUNK_TYPE) {
                continue;
            }

            int length = (data[CHUNK_LENGTH_OFFSET] & BYTE_MASK) | (data[CHUNK_LENGTH_OFFSET + 1] & BYTE_MASK) << Byte.SIZE;

            Assertions.assertEquals(offset, ByteBuffer.wrap(data, CHUNK_OFFSET_OFFSET, Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).getInt());

            int expected = Math.min(chunks < BURST_CHUNKS ? BURST_CHUNK : CHUNK, contents.length - offset);

            Assertions.assertEquals(expected, length, "chunk " + chunks);

            offset += length;
            chunks++;
        }

        Assertions.assertEquals(contents.length, offset);

        long acknowledgements = fromJoiner.stream().filter(data -> data[0] == ACKNOWLEDGE_TYPE).count();

        Assertions.assertEquals(chunks, acknowledgements);
        Assertions.assertEquals("000000" + "01" + "50c30000", HexFormat.of().formatHex(fromJoiner.getLast()),
                                "the joiner's final status holds all bytes");
        Assertions.assertTrue(transfers[HOST_PLAYER].isComplete(shared));
        Assertions.assertEquals(COMPLETE_PERCENT, transfers[JOINER_PLAYER].getDownloadPercent());
        Assertions.assertArrayEquals(contents, Files.readAllBytes(maps.resolve("Download").resolve(RANDOM_MAP)));
    }

    /**
     * Checks separator rejection and platform-dependent handling of a drive-prefixed name within the download folder.
     *
     * @param folder the temporary folder of the map folders
     */
    @Test
    void received_file_name_never_leaves_the_download_folder(@TempDir @NotNull Path folder) throws Exception {
        byte[] contents = new byte[NAME_CHECK_MAP_SIZE];

        new Random(NAME_CHECK_MAP_SEED).nextBytes(contents);

        int checksum = LanMapTransfer.calculateChecksum(contents, contents.length);
        Path download = folder.resolve("maps").resolve("Download");

        for (String name : new String[]{"..\\x.scm", "/abs.scm", "a/b.scm"}) {
            LanMapTransfer receiver = createSilentReceiver();

            receiver.setFolders(new MapFolders(List.of(Files.createDirectories(folder.resolve("maps"))), download));
            receiver.receive(HOST_PLAYER, createMapInformation(name, contents.length, checksum));

            Assertions.assertTrue(receiver.getTransfers().isEmpty(), name);
        }

        LanMapTransfer receiver = createSilentReceiver();

        receiver.setFolders(new MapFolders(List.of(folder.resolve("maps")), download));
        receiver.receive(HOST_PLAYER, createMapInformation("C:evil.scm", contents.length, checksum));

        Assertions.assertEquals(1, receiver.getTransfers().size(), "the check skips the drive prefix");

        ByteBuffer chunk = ByteBuffer.allocate(LanMapTransfer.CHUNK_HEADER + contents.length).order(ByteOrder.LITTLE_ENDIAN);

        chunk.put((byte) CHUNK_TYPE).put((byte) 0).put((byte) 0).putInt(0).putShort((short) contents.length).put(contents);
        receiver.receive(HOST_PLAYER, chunk.array());

        LanMapTransfer.Transfer transfer = receiver.getTransfers().getFirst();

        if (System.getProperty("os.name", "").startsWith("Windows")) {
            Assertions.assertEquals(FLAG_FAILED, transfer.getFlags());
            Assertions.assertNull(transfer.getFile());
        } else {
            Assertions.assertEquals(download.toAbsolutePath().normalize().resolve("C:evil.scm"), transfer.getFile());
        }

        try (Stream<Path> files = Files.walk(folder)) {
            Assertions.assertEquals(List.of(),
                                    files.filter(path -> path.getFileName().toString().contains("evil"))
                                         .filter(path -> !path.startsWith(download)).toList());
        }
    }

    /**
     * Checks that a download never replaces another file of its name (another run of the install or the host may have it open): the existing
     * file keeps its bytes and the download takes the next free name; a file of the download's name and contents is taken as it is, nothing is
     * written, and no temporary file stays.
     *
     * @param folder the temporary folder of the download folder
     */
    @Test
    void download_takes_a_free_name_instead_of_replacing_another_file(@TempDir @NotNull Path folder) throws Exception {
        byte[] contents = new byte[NAME_CHECK_MAP_SIZE];
        byte[] other = new byte[NAME_CHECK_MAP_SIZE];

        new Random(NAME_CHECK_MAP_SEED).nextBytes(contents);
        new Random(NAME_CHECK_MAP_SEED + 1).nextBytes(other);

        Path download = Files.createDirectories(folder.resolve("Download"));
        Path existing = Files.write(download.resolve("held.scm"), other);
        Path twin = Files.write(download.resolve("twin.scm"), contents);

        LanMapTransfer.Transfer moved = receiveWhole(download, "held.scm", contents);

        Assertions.assertArrayEquals(other, Files.readAllBytes(existing), "the existing file keeps its bytes");
        Assertions.assertEquals(download.resolve("held (2).scm"), moved.getFile());
        Assertions.assertArrayEquals(contents, Files.readAllBytes(download.resolve("held (2).scm")));

        long modified = Files.getLastModifiedTime(twin).toMillis();
        LanMapTransfer.Transfer found = receiveWhole(download, "twin.scm", contents);

        Assertions.assertEquals(twin, found.getFile());
        Assertions.assertEquals(modified, Files.getLastModifiedTime(twin).toMillis(), "the same file is not written again");

        try (Stream<Path> files = Files.list(download)) {
            Assertions.assertEquals(List.of("held (2).scm", "held.scm", "twin.scm"),
                                    files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    /**
     * Checks that a joiner with two copies of the host's map takes the copy among the files of its map folder before the copy in a subfolder that
     * comes first in the folder, whatever the copies' names.
     *
     * @param folder the temporary folder of the clients' map folders
     */
    @Test
    void joiner_takes_the_copy_in_its_map_folder_before_a_copy_in_a_subfolder(@TempDir @NotNull Path folder) throws Exception {
        byte[] contents = new byte[MAP_COPY_SIZE];

        new Random(MAP_COPY_SEED).nextBytes(contents);

        Path source = Files.createDirectories(folder.resolve("host")).resolve(RANDOM_MAP);

        Files.write(source, contents);

        Path maps = Files.createDirectories(folder.resolve("joiner"));
        Path topCopy = maps.resolve("b copy.scx");

        Files.write(Files.createDirectories(maps.resolve("a")).resolve(RANDOM_MAP), contents);
        Files.write(topCopy, contents);

        InMemoryLanScenario network = new InMemoryLanScenario(DOWNLOAD_CLIENTS);

        network.host(createOpenLobbySetup(DOWNLOAD_CLIENTS, RANDOM_MAP, contents.length, LanMapTransfer.calculateChecksum(contents, contents.length)),
                     GAME_DATA, source);
        network.join(JOINER_PLAYER, maps, maps.resolve("Download"));
        network.runUntil(MAP_CHECK_LIMIT_MILLISECONDS, lan -> {
            LanLobby host = lan.lobbies[HOST_PLAYER];

            Assertions.assertNotNull(host);

            return host.isMapTransferComplete();
        });

        LanLobby joiner = network.lobbies[JOINER_PLAYER];

        Assertions.assertNotNull(joiner);
        Assertions.assertEquals(topCopy, joiner.getMapFile());
    }

    /**
     * Checks three clients: the second joiner lacks the map and the first joiner has it, so the owner orders the first joiner to send (joiner to
     * joiner over their direct link); the first joiner then leaves mid-transfer and the host resumes the download from the byte count the second
     * joiner reports.
     *
     * @param folder the temporary folder of the clients' map folders
     */
    @Test
    void joiner_feeds_another_joiner_and_the_download_finishes_after_the_feeder_leaves(@TempDir @NotNull Path folder) throws Exception {
        byte[] contents = new byte[FED_MAP_SIZE];

        new Random(FED_MAP_SEED).nextBytes(contents);

        Path hostMaps = Files.createDirectories(folder.resolve("host"));
        Path source = hostMaps.resolve("big map.scm");

        Files.write(source, contents);

        Path feederMaps = Files.createDirectories(folder.resolve("feeder"));

        Files.write(Files.createDirectories(feederMaps.resolve("sub")).resolve("renamed.scm"), contents);

        Path neederMaps = Files.createDirectories(folder.resolve("needer"));
        InMemoryLanScenario network = new InMemoryLanScenario(FEEDING_CLIENTS);

        network.latency = LATENCY;
        network.host(createOpenLobbySetup(FEEDING_CLIENTS, "big map.scm",
                                          contents.length, LanMapTransfer.calculateChecksum(contents, contents.length)),
                     GAME_DATA, source);
        network.join(JOINER_PLAYER, feederMaps, feederMaps.resolve("Download"));
        network.runUntil(MAP_CHECK_LIMIT_MILLISECONDS, lan -> {
            LanLobby host = lan.lobbies[HOST_PLAYER];

            Assertions.assertNotNull(host);

            return host.isMapTransferComplete();
        });

        LanLobby host = network.lobbies[HOST_PLAYER];

        Assertions.assertNotNull(host);
        Assertions.assertTrue(host.isMapTransferComplete(), "the first joiner found the map under another name");

        LanLobby firstJoiner = network.lobbies[JOINER_PLAYER];

        Assertions.assertNotNull(firstJoiner);
        Assertions.assertEquals(feederMaps.resolve("sub").resolve("renamed.scm"), firstJoiner.getMapFile());

        network.join(SECOND_JOINER_PLAYER, neederMaps, neederMaps.resolve("Download"));

        LanLobby feederLobby = network.lobbies[JOINER_PLAYER];

        Assertions.assertNotNull(feederLobby);

        LanMapTransfer feeder = feederLobby.getMapTransfer();

        network.runUntil(FEED_LIMIT_MILLISECONDS, lan -> (feeder.getTransfers().getFirst().getFlags() & FLAG_SENDING) != 0);

        Assertions.assertEquals(FLAG_READY | FLAG_SENDING, feeder.getTransfers().getFirst().getFlags(), "the first joiner feeds the second");

        LanLobby neederLobby = network.lobbies[SECOND_JOINER_PLAYER];

        Assertions.assertNotNull(neederLobby);

        LanMapTransfer needer = neederLobby.getMapTransfer();

        network.runUntil(FEED_LIMIT_MILLISECONDS, lan -> needer.getTransfers().getFirst().getBytes() > FED_BYTES);

        int fed = needer.getTransfers().getFirst().getBytes();

        Assertions.assertTrue(fed > FED_BYTES && fed < contents.length, "fed " + fed);

        network.silence(JOINER_PLAYER);

        int downloadMilliseconds = network.runUntil(DOWNLOAD_LIMIT_MILLISECONDS, lan -> {
            LanLobby joiner = lan.lobbies[SECOND_JOINER_PLAYER];

            Assertions.assertNotNull(joiner);

            return joiner.getMapFile() != null;
        });

        Assertions.assertTrue(downloadMilliseconds < DOWNLOAD_LIMIT_MILLISECONDS, "the download finished after the feeder left");

        LanLobby completedJoiner = network.lobbies[SECOND_JOINER_PLAYER];

        Assertions.assertNotNull(completedJoiner);

        Path downloaded = completedJoiner.getMapFile();

        Assertions.assertNotNull(downloaded);
        Assertions.assertArrayEquals(contents, Files.readAllBytes(downloaded));

        network.runUntil(MAP_CHECK_LIMIT_MILLISECONDS, lan -> {
            LanLobby currentHost = lan.lobbies[HOST_PLAYER];

            Assertions.assertNotNull(currentHost);

            return currentHost.getRecord(JOINER_PLAYER).getPlayerId() < 0 && currentHost.isMapTransferComplete();
        });

        LanLobby completedHost = network.lobbies[HOST_PLAYER];

        Assertions.assertNotNull(completedHost);
        Assertions.assertTrue(completedHost.isMapTransferComplete(), "the remaining players hold the map");
    }

    /**
     * Checks that leaving sends the cancel {@code {3, id}} only for the lobby's map, to its players newest first, and none for another shared file.
     *
     * @param folder the temporary folder of the shared files
     */
    @Test
    void close_should_cancel_only_the_lobby_map_for_its_players_newest_first(@TempDir @NotNull Path folder) throws Exception {
        Path otherFile = folder.resolve("other.scm");
        Path lobbyFile = folder.resolve("lobby.scm");
        List<String> sent = new ArrayList<>();
        LanMapTransfer transfer = new LanMapTransfer(HOST_PLAYER,
                                                     (target, data) -> sent.add(target + ":" + HexFormat.of().formatHex(data)),
                                                     MESSAGE_LIMIT);

        Files.write(otherFile, new byte[]{1});
        Files.write(lobbyFile, new byte[]{2});

        LanMapTransfer.SharedFile other = transfer.share(otherFile);
        LanMapTransfer.SharedFile lobbyMap = transfer.share(lobbyFile);

        Assertions.assertNotNull(other);
        Assertions.assertNotNull(lobbyMap);

        transfer.addPlayer(other, JOINER_PLAYER);
        transfer.addPlayer(lobbyMap, JOINER_PLAYER);
        transfer.addPlayer(lobbyMap, SECOND_JOINER_PLAYER);
        sent.clear();
        transfer.close(lobbyMap);

        Assertions.assertEquals(List.of("2:0301", "1:0301"), sent);
    }

    /**
     * Checks a mod's downloader: it gets the host's shared file and the joiner's missing map, whose status then waits; a file of another
     * checksum hands the map back to the chunks, which carry it as without a downloader; a file of the information's size and checksum is
     * saved and completes the host's map; closing the transfer closes the downloader.
     *
     * @param folder the temporary folder of the map and the joiner's map folder
     */
    @Test
    void downloader_takes_over_a_missing_map_and_hands_it_back_to_the_chunks(@TempDir @NotNull Path folder) throws Exception {
        byte[] contents = new byte[CHUNKED_MAP_SIZE];

        new Random(CHUNKED_MAP_SEED).nextBytes(contents);

        Path source = Files.write(folder.resolve(RANDOM_MAP), contents);
        List<Object[]> queue = new ArrayList<>();
        LanMapTransfer[] transfers = new LanMapTransfer[DOWNLOAD_CLIENTS];
        List<LanMapTransfer.SharedFile> shared = new ArrayList<>();
        List<LanMapTransfer.Transfer> taken = new ArrayList<>();
        boolean[] closed = new boolean[1];

        for (int player = 0; player < DOWNLOAD_CLIENTS; player++) {
            int sender = player;

            transfers[player] = new LanMapTransfer(player, (target, data) -> queue.add(new Object[]{sender, target, data}), MESSAGE_LIMIT);
            transfers[player].setDownloader(new LanMapDownloader() {
                @Override
                public void share(LanMapTransfer.@NotNull SharedFile map) {
                    shared.add(map);
                }

                @Override
                public boolean download(LanMapTransfer.@NotNull Transfer transfer) {
                    return taken.add(transfer);
                }

                @Override
                public void update() {
                }

                @Override
                public void close() {
                    closed[0] = true;
                }
            });
        }

        Path maps = Files.createDirectories(folder.resolve("maps"));
        Runnable deliver = () -> {
            while (!queue.isEmpty()) {
                Object[] message = queue.removeFirst();

                transfers[(int) message[MESSAGE_TARGET]].receive((int) message[MESSAGE_SENDER], (byte[]) message[MESSAGE_DATA]);
            }
        };

        transfers[JOINER_PLAYER].setFolders(MapFolders.of(List.of(maps), LanMapTransfer.DOWNLOAD_FOLDER));

        LanMapTransfer.SharedFile map = transfers[HOST_PLAYER].share(source);

        Assertions.assertNotNull(map);
        Assertions.assertEquals(List.of(map), shared);

        transfers[HOST_PLAYER].addPlayer(map, HOST_PLAYER);
        transfers[HOST_PLAYER].addPlayer(map, JOINER_PLAYER);
        deliver.run();

        Assertions.assertEquals(1, taken.size(), "the joiner's missing map");
        Assertions.assertEquals(RANDOM_MAP, taken.getFirst().getName());
        Assertions.assertFalse(transfers[HOST_PLAYER].isComplete(map), "the joiner's status waits for the downloader");

        transfers[JOINER_PLAYER].reportProgress(taken.getFirst(), contents.length / 2);

        Assertions.assertEquals(50, transfers[JOINER_PLAYER].getDownloadPercent());

        byte[] other = contents.clone();

        other[0] ^= 1;

        Assertions.assertFalse(transfers[JOINER_PLAYER].completeDownload(taken.getFirst(), other));
        deliver.run();

        Assertions.assertTrue(transfers[HOST_PLAYER].isComplete(map), "the chunks carried the map");
        Assertions.assertArrayEquals(contents, Files.readAllBytes(maps.resolve("Download").resolve(RANDOM_MAP)));
        Assertions.assertFalse(transfers[JOINER_PLAYER].completeDownload(taken.getFirst(), contents), "a saved map stays");

        LanMapTransfer.SharedFile second = transfers[HOST_PLAYER].share(source);

        Assertions.assertNotNull(second);

        Path secondMaps = Files.createDirectories(folder.resolve("second"));

        transfers[JOINER_PLAYER].setFolders(MapFolders.of(List.of(secondMaps), LanMapTransfer.DOWNLOAD_FOLDER));
        transfers[HOST_PLAYER].addPlayer(second, JOINER_PLAYER);
        deliver.run();

        Assertions.assertEquals(2, taken.size());
        Assertions.assertTrue(transfers[JOINER_PLAYER].completeDownload(taken.getLast(), contents));
        deliver.run();

        Assertions.assertTrue(transfers[HOST_PLAYER].isComplete(second), "the downloaded map completes the host's map");
        Assertions.assertArrayEquals(contents, Files.readAllBytes(secondMaps.resolve("Download").resolve(RANDOM_MAP)));

        transfers[JOINER_PLAYER].close(null);

        Assertions.assertTrue(closed[0]);
    }

    @Test
    void create_file_match_should_take_any_name_with_the_size_and_checksum(@TempDir @NotNull Path folder) throws Exception {
        byte[] contents = new byte[MAP_COPY_SIZE];
        byte[] other = new byte[MAP_COPY_SIZE];

        new Random(MAP_COPY_SEED).nextBytes(contents);
        new Random(MAP_COPY_SEED + 1).nextBytes(other);

        int checksum = LanMapTransfer.calculateChecksum(contents, contents.length);
        MapFolders folders = MapFolders.of(List.of(folder), LanMapTransfer.DOWNLOAD_FOLDER);

        Files.write(folder.resolve("a same size.scm"), other);

        Path renamed = Files.write(folder.resolve("renamed.scx"), contents);

        Assertions.assertEquals(renamed, folders.findFile(LanMapTransfer.createFileMatch(contents.length, checksum)));
        Assertions.assertNull(folders.findFile(LanMapTransfer.createFileMatch(contents.length, checksum + 1)));
        Assertions.assertNull(folders.findFile(LanMapTransfer.createFileMatch(contents.length + 1, checksum)));
    }

    @Test
    void can_hold_name_should_accept_a_name_that_fits_any_folder() {
        Path longFolder = Path.of("x".repeat(LONG_FOLDER_LENGTH));
        MapFolders longOnly = MapFolders.of(List.of(longFolder), LanMapTransfer.DOWNLOAD_FOLDER);
        MapFolders longAndShort = MapFolders.of(List.of(longFolder, Path.of("maps")), LanMapTransfer.DOWNLOAD_FOLDER);

        Assertions.assertFalse(LanMapTransfer.canHoldName(longOnly, NAME_LENGTH));
        Assertions.assertTrue(LanMapTransfer.canHoldName(longAndShort, NAME_LENGTH));
        Assertions.assertTrue(LanMapTransfer.canHoldName(new MapFolders(List.of(), null), LONG_FOLDER_LENGTH + NAME_LENGTH),
                              "no folder limits nothing");
    }

    @Test
    void can_hold_name_should_count_the_folder_and_its_separator_in_utf_8_bytes(@TempDir @NotNull Path folder) {
        Path maps = folder.resolve(MULTI_BYTE_FOLDER);
        String nativeFolder = maps.toAbsolutePath() + maps.getFileSystem().getSeparator();
        int folderLength = nativeFolder.getBytes(StandardCharsets.UTF_8).length;
        MapFolders folders = MapFolders.of(List.of(maps), LanMapTransfer.DOWNLOAD_FOLDER);

        Assertions.assertTrue(LanMapTransfer.canHoldName(folders, LONGEST_PATH - folderLength - 1), "the longest name that fits");
        Assertions.assertFalse(LanMapTransfer.canHoldName(folders, LONGEST_PATH - folderLength), "one byte longer");
    }
}
