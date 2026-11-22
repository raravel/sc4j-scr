package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.engine.net.lobby.MapFolders;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.session.setup.GameTemplate;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanDiscovery;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanGameInformation;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanPresenceInformation;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderPacket;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.fogofwarlabs.sc4j.scr.net.proxy.GameProxySocket;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Console utility for browsing, advertising, hosting and joining LAN games.
 * <ul>
 * <li>{@code browse [toon]} publishes presence and prints changes to games and players.</li>
 * <li>{@code advertise [toon] [game]} publishes the recorded Top vs Bottom game without opening the game port.</li>
 * <li>{@code host [toon] [game]} opens UDP {@code 6112} and runs a {@link LanLobby}, printing its changes.</li>
 * <li>{@code join [toon] [game] [port]} joins the first matching game for {@code 180} seconds; the optional port
 * selects the local binding.</li>
 * </ul>
 * Console commands change the local race or color, send chat, or start the host's countdown. After the countdown,
 * the probe sends empty turns without running the game simulation.
 */
public final class LanProbeScenario {
    private static final int BYTE_MASK = 0xFF;
    /**
     * The positions of the command-line arguments: the mode, the toon, the game name and the local port.
     */
    private static final int MODE_ARGUMENT = 0;
    private static final int TOON_ARGUMENT = 1;
    private static final int GAME_NAME_ARGUMENT = 2;
    private static final int PORT_ARGUMENT = 3;
    private static final long CAPTURED_MAP_CHECKSUM = LanTestResources.CAPTURED_MAP_CHECKSUM;
    private static final @NotNull String CAPTURED_MAP_MD5 = "725EAA6B3C05137A2ECAF973F1441CA0";
    private static final long CAPTURED_MAP_SIZE = LanTestResources.CAPTURED_MAP_FILE_SIZE;
    /**
     * The installation folder, relative to the working directory, that the probe reads while {@code sc4j.data} is not set.
     */
    private static final @NotNull String DEFAULT_INSTALLATION_FOLDER = "StarCraft";
    /**
     * The recorded Top vs Bottom game options.
     */
    private static final byte @NotNull [] CAPTURED_GAME_DATA = HexFormat.of().parseHex("0f00010001000000" +
                                                                                       "010101020200010100010000000000320000000000000000");
    private static final int JOIN_SECONDS = 180;
    private static final long MILLISECONDS_PER_SECOND = 1000L;
    private static final int GUID_SIZE = 16;
    /**
     * The advertised game: subtype, human players, maximum players and map.
     */
    private static final int ADVERTISED_SUBTYPE = 1;
    private static final int ADVERTISED_PLAYERS = 1;
    private static final int ADVERTISED_MAXIMUM_PLAYERS = 2;
    private static final @NotNull String ADVERTISED_MAP_NAME = "Bottleneck";
    private static final int ADVERTISED_TILESET = 1;
    private static final int ADVERTISED_MAP_SIZE = 128;
    /**
     * The provider packet header: sequence, acknowledgement, class, subtype, sender and flags.
     */
    private static final int SEQUENCE_OFFSET = 4;
    private static final int ACKNOWLEDGEMENT_OFFSET = 6;
    private static final int CLASS_OFFSET = 8;
    private static final int SUBTYPE_OFFSET = 9;
    private static final int SENDER_OFFSET = 10;
    private static final int FLAGS_OFFSET = 11;
    /**
     * The payload bytes a trace line shows at most.
     */
    private static final int TRACED_PAYLOAD = 40;
    private static final long BROWSE_INTERVAL_MILLISECONDS = 1000;
    private static final long JOIN_POLL_MILLISECONDS = 250;
    private static final long PUMP_INTERVAL_MILLISECONDS = 10;
    private static final int LEAVE_PUMPS = 50;
    private static final long NANOSECONDS_PER_MILLISECOND = 1_000_000L;
    private static final char FIRST_PRINTABLE = 0x20;
    private static final char DELETE = 0x7F;
    /**
     * The words of a console command with a value: the command and the value.
     */
    private static final int VALUE_COMMAND_WORDS = 2;
    private static final int COMMAND_WORD = 0;
    private static final int VALUE_WORD = 1;
    private static final @NotNull String SAY_PREFIX = "say ";
    private static final @NotNull ConcurrentLinkedQueue<String> INPUT = new ConcurrentLinkedQueue<>();

    private static int lastState = LanLobby.STATE_LOBBY;

    /**
     * Runs the probe.
     *
     * @param arguments the mode ({@code browse}, {@code advertise}, {@code host} or {@code join}), the toon, the game name
     *                  and, for {@code join}, the local port
     * @throws Exception if the network cannot be opened
     */
    public static void main(String @NotNull [] arguments) throws Exception {
        String mode = arguments.length > MODE_ARGUMENT ? arguments[MODE_ARGUMENT] : "browse";
        Thread reader = new Thread(() -> {
            BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

            try {
                for (String line = console.readLine(); line != null; line = console.readLine()) {
                    INPUT.add(line);
                }
            } catch (IOException exception) {
                System.out.println("INPUT closed: " + exception.getMessage());
            }
        }, "lan-probe-input");

        reader.setDaemon(true);
        reader.start();

        String toon = arguments.length > TOON_ARGUMENT ? arguments[TOON_ARGUMENT] : "sc4j";
        String gameName = arguments.length > GAME_NAME_ARGUMENT ? arguments[GAME_NAME_ARGUMENT] : mode.equals("join") ? "" : toon;
        boolean advertise = mode.equals("advertise") || mode.equals("host");
        byte[] guid = new byte[GUID_SIZE];

        new SecureRandom().nextBytes(guid);

        try (LanDiscovery discovery = LanDiscovery.open()) {
            System.out.println("LAN PROBE: " + mode + " as PNM:" + toon + " on host " + discovery.getHostName());

            discovery.setPresence(LanPresenceInformation.create(toon, advertise ? gameName : null, guid).toRecord(0));

            if (advertise) {
                ProviderSession advertised = new ProviderSession((address, packet) -> {
                }, LanProbeScenario::getCurrentTick);
                LanGameAdvertisement advertisement = new LanGameAdvertisement(LanTestResources.CAPTURED_MAP_FILE_NAME,
                                                                              CAPTURED_MAP_SIZE, CAPTURED_MAP_CHECKSUM,
                                                                              CAPTURED_MAP_MD5,
                                                                              0);

                advertised.createGame(gameName, createStatistics(toon).encode(), "",
                                      ADVERTISED_MAXIMUM_PLAYERS, PlayerTable.OBSERVER_SLOTS,
                                      0, 0, CAPTURED_GAME_DATA,
                                      toon, "");
                advertisement.update(advertised);
                discovery.setGame(advertisement.toRecord());
                System.out.println("LAN PROBE: published game " + gameName);
            }

            switch (mode) {
                case "host" -> host(toon, gameName);
                case "join" -> join(discovery, toon, gameName,
                                    arguments.length > PORT_ARGUMENT ? Integer.parseInt(arguments[PORT_ARGUMENT]) : GameProxySocket.GAME_PORT);
                default -> browse(discovery);
            }
        }
    }

    private static void browse(@NotNull LanDiscovery discovery) throws InterruptedException {
        List<String> previous = List.of();

        while (true) {
            List<String> lines = new ArrayList<>();

            for (LanDiscovery.Game game : discovery.getGames()) {
                lines.add(describeGame(game));
            }

            for (LanPresenceInformation presence : discovery.getPresences()) {
                lines.add("PLAYER " + escapeText(presence.getToonName()) +
                          (presence.getLobbyName() == null ? "" : " hosting=" + escapeText(presence.getLobbyName())));
            }

            if (!Objects.equals(lines, previous)) {
                System.out.println("--- " + LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + " " + lines.size() + " entries");
                lines.forEach(System.out::println);
                previous = lines;
            }

            Thread.sleep(BROWSE_INTERVAL_MILLISECONDS);
        }
    }

    /**
     * Creates a {@link LanGameStatistics} instance of the recorded Top vs Bottom game hosted by a toon, its observer slots
     * closed.
     *
     * @param toon the host's toon name
     * @return the created {@link LanGameStatistics} instance
     */
    private static @NotNull LanGameStatistics createStatistics(@NotNull String toon) {
        return new LanGameStatistics(0,
                                     ADVERTISED_MAP_SIZE, ADVERTISED_MAP_SIZE,
                                     ADVERTISED_PLAYERS, ADVERTISED_MAXIMUM_PLAYERS,
                                     GameSpeed.FASTEST, LanGameStatistics.HOST_MAP_STATUS,
                                     GameTemplate.TYPE_TOP_VS_BOTTOM, ADVERTISED_SUBTYPE,
                                     ProviderSession.DEFAULT_TURNS_PER_SECOND, ADVERTISED_TILESET, false,
                                     toon, ADVERTISED_MAP_NAME,
                                     0, 0, 0);
    }

    private static @NotNull String describeGame(@NotNull LanDiscovery.Game game) {
        LanGameInformation information = game.getInformation();

        return "GAME " + escapeText(information.getName()) + " host=" + escapeText(information.getHostName()) +
               " map=" + escapeText(information.getMapName()) + " type=" + information.getGameType() +
               " speed=" + information.getGameSpeed() + " players=" + information.getPlayersCurrent() + "/" + information.getPlayersMaximum() +
               " address=" + game.getAddress() +
               " revision=" + game.getRevision();
    }

    /**
     * Creates a {@link LanLobby.Setup} instance of the host's lobby: built from the installed {@code (2)Bottleneck.scm}
     * ({@code StarCraft/Maps}, or the directory of the {@code sc4j.data} property) with the Top vs Bottom options, else
     * the recorded table.
     *
     * @param toon the host's toon name
     * @return the created {@link LanLobby.Setup} instance
     */
    private static @NotNull LanLobby.Setup createHostSetup(@NotNull String toon) {
        Path installation = ExternalInput.INSTALLATION.isConfigured() ? ExternalInput.INSTALLATION.getPath() : Path.of(DEFAULT_INSTALLATION_FOLDER);
        Path map = installation.resolve(ExternalInput.MAPS_FOLDER).resolve(LanTestResources.CAPTURED_MAP_FILE_NAME);

        try {
            if (Files.isRegularFile(map)) {
                byte[] scenario = MpqArchive.open(map).readFile("staredit\\scenario.chk");

                System.out.println("LAN PROBE: lobby built from " + map);

                return LanLobby.Setup.create(ChkFile.parseTolerant(scenario), LanGameOptions.TOP_VS_BOTTOM_1,
                                             toon,
                                             LanTestResources.CAPTURED_MAP_FILE_NAME, (int) CAPTURED_MAP_SIZE, (int) CAPTURED_MAP_CHECKSUM);
            }
        } catch (IOException exception) {
            System.out.println("LAN PROBE: map not readable (" + exception.getMessage() + "), captured table used");
        }

        return LanTestResources.createCapturedLobbySetup(toon);
    }

    private static void host(@NotNull String toon, @NotNull String gameName) throws IOException, InterruptedException {
        try (GameProxySocket socket = GameProxySocket.open(GameProxySocket.GAME_PORT)) {
            ProviderSession session = new ProviderSession(createTracingTransport(socket), LanProbeScenario::getCurrentTick);

            session.createGame(gameName, createStatistics(toon).encode(), "", ADVERTISED_MAXIMUM_PLAYERS, 0, 0, 0, CAPTURED_GAME_DATA, toon, "");

            LanLobby lobby = new LanLobby(session,
                                          LanProbeScenario::getCurrentTick,
                                          createHostSetup(toon),
                                          toon,
                                          new byte[LanLobby.PLAYER_BLOCK_SIZE]);
            String table = "";

            System.out.println("LAN PROBE: hosting on UDP " + socket.getLocalPort());

            while (true) {
                pump(socket, session);
                applyInput(lobby);
                lobby.update();
                table = printTable(lobby, table);
            }
        }
    }

    private static void join(@NotNull LanDiscovery discovery, @NotNull String toon, @NotNull String gameName, int port) throws IOException, InterruptedException {
        LanDiscovery.Game target = null;

        while (target == null) {
            for (LanDiscovery.Game game : discovery.getGames()) {
                if (game.getInformation().getName().contains(gameName)) {
                    target = game;

                    break;
                }
            }

            Thread.sleep(JOIN_POLL_MILLISECONDS);
        }

        System.out.println("LAN PROBE: joining " + describeGame(target));

        try (GameProxySocket socket = GameProxySocket.open(port)) {
            ProviderSession session = new ProviderSession(createTracingTransport(socket), LanProbeScenario::getCurrentTick);

            session.beginJoin(target.getAddress(), toon, "", "");

            ProviderSession.JoinState previous = null;
            LanLobby lobby = null;
            String table = "";
            long end = System.currentTimeMillis() + JOIN_SECONDS * MILLISECONDS_PER_SECOND;

            while (System.currentTimeMillis() < end) {
                pump(socket, session);

                if (lobby != null) {
                    applyInput(lobby);
                    lobby.update();
                    table = printTable(lobby, table);

                    if (lobby.isTimedOut()) {
                        System.out.println("LOBBY TIMED OUT: no lobby turn completed for " + LanLobby.TIMEOUT / MILLISECONDS_PER_SECOND + " s");

                        break;
                    }
                }

                ProviderSession.JoinState state = session.pollJoin();

                if (state == ProviderSession.JoinState.JOINED && lobby == null) {
                    lobby = new LanLobby(session, LanProbeScenario::getCurrentTick, null, toon, new byte[LanLobby.PLAYER_BLOCK_SIZE]);

                    // -Dsc4j.probeMaps=<folder>: the joiner's map folder (downloads go to its Download subfolder).
                    String maps = System.getProperty("sc4j.probeMaps");

                    if (maps != null) {
                        lobby.setMapFolders(MapFolders.of(List.of(Path.of(maps)), LanMapTransfer.DOWNLOAD_FOLDER));
                    }
                }

                if (state != previous) {
                    System.out.println("JOIN " + state + " local=" + session.getLocalIdentifier() +
                                       " game=" + escapeText(session.getGameName()) + " statistics=" +
                                       escapeText(session.getGameStatistics()));
                    previous = state;
                }

                if (state == ProviderSession.JoinState.REJECTED || state == ProviderSession.JoinState.TIMED_OUT) {
                    return;
                }
            }

            session.leave(LanClient.LEAVE_REASON);

            for (int step = 0; step < LEAVE_PUMPS; step++) {
                pump(socket, session);
            }
        }
    }

    /**
     * Prints received chat lines; console lines {@code race <n>} and {@code color <n>} request a race or color for the
     * local slot, {@code say <text>} sends a chat line.
     *
     * @param lobby the lobby
     */
    private static void applyInput(@NotNull LanLobby lobby) {
        int state = lobby.getState();

        if (state != lastState) {
            System.out.println("STATE " + state +
                               (state == LanLobby.STATE_GAME ? " game starts, seed 0x" + Integer.toHexString(lobby.getSeed()) : "") +
                               (state == LanLobby.STATE_COUNTDOWN ? " countdown " + lobby.getCountdown() + " steps" : ""));
            lastState = state;
        }

        for (LanLobby.Chat chat = lobby.pollChat(); chat != null; chat = lobby.pollChat()) {
            System.out.println("CHAT player " + chat.getPlayer() + ": " + escapeText(chat.getText()));
        }

        for (String line = INPUT.poll(); line != null; line = INPUT.poll()) {
            String[] words = line.trim().split(" +");

            try {
                if (words.length == VALUE_COMMAND_WORDS && words[COMMAND_WORD].equals("race")) {
                    lobby.changeRace(Integer.parseInt(words[VALUE_WORD]));
                    System.out.println("INPUT race " + words[VALUE_WORD]);
                } else if (words.length == VALUE_COMMAND_WORDS && words[COMMAND_WORD].equals("color")) {
                    lobby.changeColor(LanLobby.OWN_SLOT, Integer.parseInt(words[VALUE_WORD]));
                    System.out.println("INPUT color " + words[VALUE_WORD]);
                } else if (line.trim().equals("start")) {
                    System.out.println("INPUT start " + (lobby.startGame() ? "queued" : "refused (host in the lobby state only)"));
                } else if (line.startsWith(SAY_PREFIX)) {
                    System.out.println("INPUT say " + (lobby.sendChat(line.substring(SAY_PREFIX.length())) ? "sent" : "too long"));
                } else if (!line.isBlank()) {
                    System.out.println("INPUT ignored (race <n> | color <n> | say <text> | start): " + line);
                }
            } catch (NumberFormatException exception) {
                System.out.println("INPUT ignored: " + line);
            }
        }
    }

    private static void pump(@NotNull GameProxySocket socket, @NotNull ProviderSession session) throws InterruptedException {
        socket.poll((source, packet) -> {
            printPacketTrace("RECV", packet);
            session.receive(source, packet);
        });
        session.update();
        Thread.sleep(PUMP_INTERVAL_MILLISECONDS);
    }

    /**
     * Prints the lobby table whenever it changes: one line per occupied or open slot.
     *
     * @param lobby    the lobby
     * @param previous the table printed last
     * @return the current table
     */
    private static @NotNull String printTable(@NotNull LanLobby lobby, @NotNull String previous) {
        StringBuilder table = new StringBuilder();

        for (int index = 0; index < LanLobby.RECORDS; index++) {
            MeleeGameSetup.PlayerRecord record = lobby.getRecord(index);
            boolean player = index < PlayerTable.PLAYABLE_PLAYER_SLOTS;

            if (record.getPlayerId() >= 0 || record.getType() == LanLobby.TYPE_OPEN) {
                table.append("  slot ").append(player ? index : "0x" + Integer.toHexString(LanLobby.getSlotIdentifier(index)))
                     .append(": player ").append(record.getPlayerId())
                     .append(" name=").append(escapeText(record.getName())).append(" type=").append(record.getType())
                     .append(" race=").append(record.getRace())
                     .append(" force=").append(record.getTeam()).append(player ? " color=" + lobby.getColor(index) : "")
                     .append(record.getPlayerId() >= 0 ? " download=" + lobby.getDownloadPercent(record.getPlayerId()) : "")
                     .append('\n');
            }
        }

        String text = table.toString();

        if (!text.equals(previous)) {
            System.out.print("LOBBY " + LocalTime.now().truncatedTo(ChronoUnit.MILLIS) + "\n" + text);
        }

        return text;
    }

    private static @NotNull ProviderSession.Transport createTracingTransport(@NotNull GameProxySocket socket) {
        return (address, packet) -> {
            printPacketTrace("SEND", packet.getEncodedBytes());
            socket.send(address, packet);
        };
    }

    /**
     * Prints every packet except ordinary turns (too frequent); bare acknowledgements and resend requests included.
     *
     * @param direction the direction, {@code SEND} or {@code RECV}
     * @param packet    the provider packet
     */
    private static void printPacketTrace(@NotNull String direction, byte @NotNull [] packet) {
        if (packet.length < ProviderPacket.HEADER_SIZE || packet[CLASS_OFFSET] == ProviderPacket.TURN && packet[FLAGS_OFFSET] == 0) {
            return;
        }

        System.out.println(direction + " " + LocalTime.now().truncatedTo(ChronoUnit.MILLIS) + " class=" + packet[CLASS_OFFSET] +
                           " sub=" + (packet[SUBTYPE_OFFSET] & BYTE_MASK) +
                           " seq=" + ((packet[SEQUENCE_OFFSET] & BYTE_MASK) | (packet[SEQUENCE_OFFSET + 1] & BYTE_MASK) << Byte.SIZE) + " ack=" +
                           ((packet[ACKNOWLEDGEMENT_OFFSET] & BYTE_MASK) | (packet[ACKNOWLEDGEMENT_OFFSET + 1] & BYTE_MASK) << Byte.SIZE) +
                           " sender=" + (packet[SENDER_OFFSET] & BYTE_MASK) +
                           " flags=" + (packet[FLAGS_OFFSET] & BYTE_MASK) + " payload=" +
                           HexFormat.of().formatHex(packet,
                                                    ProviderPacket.HEADER_SIZE,
                                                    Math.min(packet.length, ProviderPacket.HEADER_SIZE + TRACED_PAYLOAD)));
    }

    /**
     * Returns the low {@code 32} bits of the monotonic millisecond value derived from {@link System#nanoTime()}.
     *
     * @return the tick in milliseconds
     */
    private static int getCurrentTick() {
        return (int) (System.nanoTime() / NANOSECONDS_PER_MILLISECOND);
    }

    /**
     * Escapes UTF-16 code units outside printable ASCII as {@code <U+XXXX>}.
     *
     * @param text the text
     * @return the escaped text
     */
    private static @NotNull String escapeText(@NotNull String text) {
        StringBuilder builder = new StringBuilder(text.length());

        text.chars().forEach(character -> {
            if (character >= FIRST_PRINTABLE && character < DELETE) {
                builder.append((char) character);
            } else {
                builder.append(String.format("<U+%04X>", character));
            }
        });

        return builder.toString();
    }


    private LanProbeScenario() {
    }
}
