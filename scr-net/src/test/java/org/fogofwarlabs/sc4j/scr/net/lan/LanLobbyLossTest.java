package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.engine.net.lobby.MapFolders;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * Verifies lobbies of a host and its joiners on a lossy network in memory: datagrams are lost, delayed and arrive in another order, the
 * joiners join one after another while the map transfer of message {@code 0x4f} runs, one of them may leave during the transfer and another
 * join after it, and every remaining joiner still downloads the map, every lobby shows every remaining player, the host sees every download
 * percent at {@code 100}, and the host's start check passes.
 */
final class LanLobbyLossTest {
    private static final int HOST_CLIENT = 0;
    private static final int MAP_SIZE = 120_000;
    private static final @NotNull String MAP_NAME = "lossy map.scx";
    private static final long MAP_SEED = 1L;

    private static final int LATENCY = 20;
    private static final int JITTER = 80;
    private static final int LOSS_PERCENT = 20;
    private static final int SEEDS = 32;

    /**
     * The clients of the join test: the host and three joiners.
     */
    private static final int JOIN_CLIENTS = 4;
    /**
     * The clients of the leave test: the host, three joiners and the one that joins after the second joiner left.
     */
    private static final int LEAVE_CLIENTS = 5;
    private static final int LEAVING_CLIENT = 2;
    /**
     * The time between two joins.
     */
    private static final int JOIN_INTERVAL_MILLISECONDS = 700;
    private static final int LIMIT_MILLISECONDS = 120_000;
    private static final int COMPLETE_PERCENT = 100;

    private static boolean isSettled(@NotNull InMemoryLanScenario network, @NotNull List<@NotNull Integer> clients) {
        LanLobby host = network.lobbies[HOST_CLIENT];

        if (host == null || !host.isMapTransferComplete()) {
            return false;
        }

        for (int client : clients) {
            LanLobby lobby = network.lobbies[client];

            if (lobby == null || (client != HOST_CLIENT && lobby.getMapFile() == null)) {
                return false;
            }

            for (int other : clients) {
                int player = network.sessions[other].getLocalIdentifier();

                if (lobby.getPlayerSlot(player) < 0 || (client == HOST_CLIENT && lobby.getDownloadPercent(player) != COMPLETE_PERCENT)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static @NotNull String describe(@NotNull InMemoryLanScenario network, @NotNull List<@NotNull Integer> clients) {
        StringBuilder text = new StringBuilder();

        for (int client : clients) {
            LanLobby lobby = network.lobbies[client];

            text.append("\nclient ").append(client).append(" player ").append(network.sessions[client].getLocalIdentifier());

            if (lobby == null) {
                text.append(" without a lobby");

                continue;
            }

            text.append(" state ").append(lobby.getState()).append(" timed out ").append(lobby.isTimedOut())
                .append(" map ").append(lobby.getMapFile());

            for (int other : clients) {
                int player = network.sessions[other].getLocalIdentifier();

                text.append(" [player ").append(player).append(" slot ").append(lobby.getPlayerSlot(player)).append(" percent ")
                    .append(lobby.getDownloadPercent(player)).append(']');
            }
        }

        return text.toString();
    }

    /**
     * Creates a lossy network of a seed whose host shares the map.
     */
    private static @NotNull InMemoryLanScenario createNetwork(int clients, int seed, @NotNull Path folder) throws IOException {
        byte[] contents = new byte[MAP_SIZE];

        new Random(MAP_SEED).nextBytes(contents);

        Path source = Files.write(Files.createDirectories(folder.resolve("host")).resolve(MAP_NAME), contents);
        int checksum = LanMapTransfer.calculateChecksum(contents, contents.length);
        InMemoryLanScenario network = new InMemoryLanScenario(clients);

        network.latency     = LATENCY;
        network.jitter      = JITTER;
        network.lossPercent = LOSS_PERCENT;
        network.random      = new Random(seed);
        network.host(LanMapTransferTest.createOpenLobbySetup(clients, MAP_NAME, contents.length, checksum), LanGameOptions.MELEE.encode(), source);

        return network;
    }

    /**
     * Joins a client with an empty map folder and runs the network for the join interval.
     */
    private static void join(@NotNull InMemoryLanScenario network, int client, @NotNull Path folder) throws IOException {
        Path maps = Files.createDirectories(folder.resolve("joiner" + client));
        LanLobby lobby = network.openLobby(client, null);

        lobby.setMapFolders(new MapFolders(List.of(maps), maps.resolve(LanMapTransfer.DOWNLOAD_FOLDER)));
        network.run(JOIN_INTERVAL_MILLISECONDS);
    }

    private static void awaitSettled(@NotNull InMemoryLanScenario network, @NotNull List<@NotNull Integer> clients, int seed) {
        int elapsed = network.runUntil(LIMIT_MILLISECONDS, lan -> isSettled(lan, clients));

        Assertions.assertTrue(elapsed < LIMIT_MILLISECONDS, () -> "seed " + seed + " settles" + describe(network, clients));
    }

    @Test
    void joiners_download_the_map_and_see_every_player_on_a_lossy_network(@TempDir @NotNull Path folder) throws Exception {
        for (int seed = 0; seed < SEEDS; seed++) {
            Path round = folder.resolve("seed " + seed);
            InMemoryLanScenario network = createNetwork(JOIN_CLIENTS, seed, round);

            for (int client = 1; client < JOIN_CLIENTS; client++) {
                join(network, client, round);
            }

            awaitSettled(network, List.of(0, 1, 2, 3), seed);
        }
    }

    @Test
    void joiner_leaving_during_the_transfer_leaves_the_others_settled_on_a_lossy_network(@TempDir @NotNull Path folder) throws Exception {
        for (int seed = 0; seed < SEEDS; seed++) {
            Path round = folder.resolve("seed " + seed);
            InMemoryLanScenario network = createNetwork(LEAVE_CLIENTS, seed, round);

            for (int client = 1; client < LEAVE_CLIENTS - 1; client++) {
                join(network, client, round);
            }

            network.leave(LEAVING_CLIENT);
            join(network, LEAVE_CLIENTS - 1, round);
            awaitSettled(network, List.of(0, 1, 3, 4), seed);
        }
    }
}
