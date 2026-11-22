package org.fogofwarlabs.sc4j.scr.net.provider;

import org.fogofwarlabs.sc4j.scr.net.proxy.GameProxySocket;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.*;

/**
 * Verifies that both sides of a session answer the recorded LAN provider packets ({@link ProviderTestResources#readLobbyJoin})
 * with the packets of the native host and joiner, and that two in-memory sessions consume a turn only at the complete barrier,
 * request missing turns, count the turn resend requests on both sides and report departures at their turn.
 */
final class ProviderSessionTest {
    private static final int CLOCK_BASE = 10_000_000;
    private static final @NotNull InetSocketAddress HOST = new InetSocketAddress("192.168.0.88", GameProxySocket.GAME_PORT);
    private static final @NotNull InetSocketAddress JOINER = new InetSocketAddress("192.168.0.2", GameProxySocket.GAME_PORT);

    private static final @NotNull String GAME_STATISTICS = ",44,12,6,1,f,,1,18,1,,B\rBottleneck\r\rfeedda7a,0,,18,";
    private static final byte @NotNull [] GAME_DATA = HexFormat.of().parseHex("0f00010001000000010101020200010100010000000000320000000000000000");

    private static final int HOST_IDENTIFIER = 0;
    private static final int JOINER_IDENTIFIER = 1;
    private static final int PLAYER_COUNT = 2;

    private static final int WORD_MASK = 0xFFFF;
    private static final byte KEEP_ALIVE = 0x05;

    /**
     * The milliseconds the host creates its game before the recording starts.
     */
    private static final int HOST_CREATION_LEAD_MILLISECONDS = 2500;
    /**
     * The last host turn sent before the join; turns after {@link #LAST_CONSUMED_TURN} stay queued and are only resent.
     */
    private static final int LAST_SEEDED_TURN = 20;
    private static final int LAST_CONSUMED_TURN = 18;
    private static final int FULL_STATE_TIME = 531;
    private static final int STARTED_STATE_TIME = 11332;
    /**
     * The recording time of the host's fourth request for the joiner's turn {@code 21}.
     */
    private static final int LATE_TURN_REQUEST_TIME = 344;

    private static final int HOST_HANDSHAKE_PACKETS = 5;
    private static final int JOINER_HANDSHAKE_PACKETS = 7;

    private static final int JOIN_STEPS = 20;
    private static final int LEAVE_STEPS = 5;
    private static final int LEAVE_REASON = 0x40000001;

    private static final int TURN_PRESENT = 0x10000;
    private static final int TURN_READY = 0x20000;
    private static final int TURN_RESPONSIVE = 0x40000;
    private static final int TURN_CLASS = 2;
    private static final int FLAG_RESEND_REQUEST = 2;
    private static final int FLAG_DEPARTED = 8;
    private static final int EVENT_PLAYER_LEFT = 3;
    private static final int UNKNOWN_IDENTIFIER = 0xFF;

    private static final class Clock {
        private int now;

        private Clock() {
            this.now = 0;
        }
    }

    private static final class Datagram {
        private final @NotNull InetSocketAddress source;
        private final @NotNull ProviderPacket packet;

        private Datagram(@NotNull InetSocketAddress source, @NotNull ProviderPacket packet) {
            this.source = source;
            this.packet = packet;
        }
    }

    private static final class Network {
        private int now;

        private final @NotNull ArrayDeque<Datagram> toHost;
        private final @NotNull ArrayDeque<Datagram> toJoiner;

        private final @NotNull List<ProviderPacket> fromHost;

        private final @NotNull ProviderSession host;
        private final @NotNull ProviderSession joiner;

        private Network() {
            this.now = CLOCK_BASE;

            this.toHost   = new ArrayDeque<>();
            this.toJoiner = new ArrayDeque<>();

            this.fromHost = new ArrayList<>();

            this.host   = new ProviderSession((address, packet) -> {
                this.fromHost.add(packet);
                this.toJoiner.add(new Datagram(HOST, packet));
            }, () -> this.now);
            this.joiner = new ProviderSession((address, packet) -> this.toHost.add(new Datagram(JOINER, packet)), () -> this.now);
        }

        private void step() {
            this.now++;

            while (!this.toHost.isEmpty() || !this.toJoiner.isEmpty()) {
                for (Datagram datagram = this.toHost.poll(); datagram != null; datagram = this.toHost.poll()) {
                    this.host.receive(datagram.source, datagram.packet.getEncodedBytes());
                }

                for (Datagram datagram = this.toJoiner.poll(); datagram != null; datagram = this.toJoiner.poll()) {
                    this.joiner.receive(datagram.source, datagram.packet.getEncodedBytes());
                }
            }

            this.host.update();
            this.joiner.update();
            this.joiner.pollJoin();
        }

        private @NotNull Network joinClients() {
            this.host.createGame("game", "statistics", "", PLAYER_COUNT, 0, 0, 0, new byte[]{1}, "host", "");
            this.joiner.beginJoin(HOST, "joiner", "", "");

            for (int step = 0; step < JOIN_STEPS; step++) {
                this.step();
            }

            Assertions.assertEquals(ProviderSession.JoinState.JOINED, this.joiner.getJoinState());

            return this;
        }
    }

    private static int createPacketKey(int packetType, int sequence) {
        return packetType << Short.SIZE | sequence;
    }

    private static @NotNull String describePacket(@NotNull ProviderPacket packet) {
        return "seq=" + packet.getSequence() + " ack=" + packet.getAcknowledgement() + " sub=" + packet.getSubtype() +
               " sender=" + packet.getSenderIdentifier() +
               " flags=" + packet.getFlags() + " payload=" + HexFormat.of().formatHex(packet.getPayloadBytes());
    }

    private static @NotNull List<String> describeControlPackets(@NotNull List<ProviderPacket> packets) {
        List<String> described = new ArrayList<>();

        for (ProviderPacket packet : packets) {
            if (packet.getType() == ProviderPacket.CONTROL) {
                described.add(describePacket(packet));
            }
        }

        return described;
    }

    /**
     * Replays recorded messages, missing-turn requests and turns, consuming a turn when the transit window is full.
     *
     * @param session     the session that sends the packets
     * @param produced    the recorded packets the session produces
     * @param otherPlayer the identifier of the player the messages go to
     */
    private static void replayPackets(@NotNull ProviderSession session, @NotNull List<ProviderPacket> produced, int otherPlayer) {
        for (ProviderPacket packet : produced) {
            if (packet.getType() == ProviderPacket.MESSAGE) {
                session.sendMessage(otherPlayer, packet.getPayloadBytes());
            } else if (packet.getFlags() == ProviderPacket.FLAG_RESEND_REQUEST) {
                // A recorded missing-turn request marks a receive step.
                session.receiveTurns(0, PLAYER_COUNT, 0);
            } else {
                ProviderChannel ownTurns = session.getSelf().getChannel(ProviderPacket.TURN);

                if (((ownTurns.getNextOutgoingSequence() - ownTurns.getExpectedSequence()) & WORD_MASK) >= ProviderSession.DEFAULT_TURNS_IN_TRANSIT) {
                    ProviderSession.TurnBatch batch = session.receiveTurns(0, PLAYER_COUNT, 0);

                    Assertions.assertTrue(batch.isComplete(), "turn step before sending turn " + packet.getSequence());
                }

                session.sendTurn(packet.getPayloadBytes());
            }
        }
    }

    /**
     * Lists data packets in order, retaining the first unflagged transmission of each class and sequence.
     * Optionally removes acknowledgements from unflagged packet descriptions.
     *
     * @param packets              the packets to describe
     * @param dataAcknowledgements {@code true} to keep the acknowledgements of unflagged packets, {@code false} to remove them
     * @return the descriptions of the data packets
     */
    private static @NotNull List<String> describeDataPackets(@NotNull List<ProviderPacket> packets, boolean dataAcknowledgements) {
        List<String> described = new ArrayList<>();
        Set<String> transmitted = new HashSet<>();

        for (ProviderPacket packet : packets) {
            if (packet.getType() == ProviderPacket.CONTROL) {
                continue;
            }

            if (packet.getFlags() == 0 && !transmitted.add(packet.getType() + "/" + packet.getSequence())) {
                continue;
            }

            String description = "class=" + packet.getType() + " " + describePacket(packet);

            described.add(dataAcknowledgements || packet.getFlags() != 0 ? description : description.replaceFirst(" ack=[0-9]+", ""));
        }

        return described;
    }

    private static @NotNull List<String> describeControlContents(@NotNull List<ProviderPacket> packets) {
        List<String> contents = new ArrayList<>();

        for (ProviderPacket packet : packets) {
            if (packet.getType() == ProviderPacket.CONTROL) {
                contents.add("sub=" + packet.getSubtype() + " flags=" + packet.getFlags() +
                             " payload=" + HexFormat.of().formatHex(packet.getPayloadBytes()) +
                             (packet.getFlags() == ProviderPacket.FLAG_ACKNOWLEDGEMENT ? " ack=" + packet.getAcknowledgement() : ""));
            }
        }

        contents.sort(null);

        return contents;
    }


    @Test
    void host_answers_the_captured_joiner_like_the_native_host() throws Exception {
        List<ProviderTestResources.CapturedPacket> capture = ProviderTestResources.readLobbyJoin();
        Clock clock = new Clock();
        List<ProviderPacket> sent = new ArrayList<>();
        ProviderSession host = new ProviderSession((address, packet) -> {
            Assertions.assertEquals(JOINER, address);
            sent.add(packet);
        }, () -> clock.now);

        clock.now = CLOCK_BASE - HOST_CREATION_LEAD_MILLISECONDS;
        host.createGame("B", GAME_STATISTICS, "", PLAYER_COUNT, 0, 0, 0, GAME_DATA, "B", "");

        // Seed the recorded host turn window before joining.
        for (int turn = 0; turn <= LAST_SEEDED_TURN; turn++) {
            host.sendTurn(new byte[]{KEEP_ALIVE});
        }

        for (int turn = 0; turn <= LAST_CONSUMED_TURN; turn++) {
            Assertions.assertTrue(host.receiveTurns(0, PLAYER_COUNT, 0).isComplete());
        }

        // Turns 19 and 20 were queued before the join; the host only resends them.
        Set<Integer> produce = new HashSet<>(List.of(createPacketKey(ProviderPacket.TURN, LAST_CONSUMED_TURN + 1),
                                                     createPacketKey(ProviderPacket.TURN, LAST_SEEDED_TURN)));
        int next = 0;

        for (int time = 0; time <= capture.getLast().getTime(); time++) {
            clock.now = CLOCK_BASE + time;

            if (time == FULL_STATE_TIME) {
                host.setGameState(ProviderSession.STATE_FULL | ProviderSession.STATE_ADVERTISED);
            }

            if (time == STARTED_STATE_TIME) {
                host.setGameState(ProviderSession.STATE_FULL | ProviderSession.STATE_ADVERTISED | ProviderSession.STATE_STARTED);
            }

            List<ProviderPacket> produced = new ArrayList<>();

            for (; next < capture.size() && capture.get(next).getTime() == time; next++) {
                ProviderPacket packet = capture.get(next).getPacket();

                if (!capture.get(next).isFromHost()) {
                    host.receive(JOINER, packet.getEncodedBytes());
                } else if (packet.getType() != ProviderPacket.CONTROL && packet.getFlags() == 0 &&
                           produce.add(createPacketKey(packet.getType(), packet.getSequence())) ||
                           packet.getType() == ProviderPacket.TURN && packet.getFlags() == ProviderPacket.FLAG_RESEND_REQUEST) {
                    produced.add(packet);
                }
            }

            host.update();
            replayPackets(host, produced, JOINER_IDENTIFIER);
        }

        List<ProviderPacket> nativePackets = new ArrayList<>();

        for (ProviderTestResources.CapturedPacket captured : capture) {
            if (captured.isFromHost()) {
                nativePackets.add(captured.getPacket());
            }
        }

        List<String> expected = describeControlPackets(nativePackets);
        List<String> actual = describeControlPackets(sent);

        Assertions.assertEquals(expected.subList(0, HOST_HANDSHAKE_PACKETS), actual.subList(0, HOST_HANDSHAKE_PACKETS), "join handshake");
        Assertions.assertEquals(JOINER_IDENTIFIER, host.getPeers().getFirst().getIdentifier());
        Assertions.assertEquals("A", host.getPeers().getFirst().getName());
        Assertions.assertEquals(expected, actual);

        // The fixture's times are the joiner's: the host's fourth request for turn 21 (344 milliseconds) left the host before the
        // joiner's turn 21 (sent at 336 milliseconds) reached it, while the replay delivers that turn at 336 milliseconds.
        List<ProviderPacket> expectedHost = new ArrayList<>();

        for (ProviderTestResources.CapturedPacket captured : capture) {
            if (captured.isFromHost() &&
                !(captured.getTime() == LATE_TURN_REQUEST_TIME && captured.getPacket().getFlags() == ProviderPacket.FLAG_RESEND_REQUEST)) {
                expectedHost.add(captured.getPacket());
            }
        }

        Assertions.assertEquals(describeDataPackets(expectedHost, false), describeDataPackets(sent, false),
                                "lobby messages, turns, overdue-turn requests and acknowledgements");
    }

    @Test
    void joiner_follows_the_captured_host_like_the_native_joiner() throws Exception {
        List<ProviderTestResources.CapturedPacket> capture = ProviderTestResources.readLobbyJoin();
        Clock clock = new Clock();
        List<ProviderPacket> sent = new ArrayList<>();
        ProviderSession joiner = new ProviderSession((address, packet) -> {
            Assertions.assertEquals(HOST, address);
            sent.add(packet);
        }, () -> clock.now);

        clock.now = CLOCK_BASE;
        joiner.beginJoin(HOST, "A", "", "");

        Set<Integer> produce = new HashSet<>();
        int next = 0;

        for (int time = 0; time <= capture.getLast().getTime(); time++) {
            clock.now = CLOCK_BASE + time;

            List<ProviderPacket> produced = new ArrayList<>();

            for (; next < capture.size() && capture.get(next).getTime() == time; next++) {
                ProviderPacket packet = capture.get(next).getPacket();

                if (capture.get(next).isFromHost()) {
                    joiner.receive(HOST, packet.getEncodedBytes());
                } else if (packet.getType() != ProviderPacket.CONTROL && packet.getFlags() == 0 &&
                           produce.add(createPacketKey(packet.getType(), packet.getSequence()))) {
                    produced.add(packet);
                }
            }

            joiner.update();
            joiner.pollJoin();
            replayPackets(joiner, produced, HOST_IDENTIFIER);
        }

        Assertions.assertEquals(ProviderSession.JoinState.JOINED, joiner.getJoinState());
        Assertions.assertEquals(JOINER_IDENTIFIER, joiner.getLocalIdentifier());
        Assertions.assertArrayEquals(GAME_DATA, joiner.getGameData());
        Assertions.assertEquals(GAME_STATISTICS, joiner.getGameStatistics());

        List<ProviderPacket> nativePackets = new ArrayList<>();

        for (ProviderTestResources.CapturedPacket captured : capture) {
            if (!captured.isFromHost()) {
                nativePackets.add(captured.getPacket());
            }
        }

        List<String> expected = describeControlPackets(nativePackets);
        List<String> actual = describeControlPackets(sent);

        Assertions.assertEquals(expected.subList(0, JOINER_HANDSHAKE_PACKETS), actual.subList(0, JOINER_HANDSHAKE_PACKETS),
                                "join handshake and first keep-alive");
        // Compare control contents independently of ping/pong ordering and sequence numbers.
        Assertions.assertEquals(describeControlContents(nativePackets), describeControlContents(sent));
        Assertions.assertEquals(describeDataPackets(nativePackets, true), describeDataPackets(sent, true),
                                "lobby messages, turns, their acknowledgements and resend requests");
    }

    @Test
    void turn_is_consumed_only_when_every_player_sent_it_and_the_missing_one_is_requested() {
        Network network = new Network().joinClients();

        network.host.sendTurn(new byte[]{5});
        network.step();

        ProviderSession.TurnBatch waiting = network.host.receiveTurns(0, PLAYER_COUNT, 0);

        Assertions.assertFalse(waiting.isComplete());
        Assertions.assertEquals(TURN_PRESENT | TURN_READY | TURN_RESPONSIVE, waiting.getStatus(0));
        Assertions.assertEquals(TURN_PRESENT | TURN_RESPONSIVE, waiting.getStatus(1));

        ProviderPacket request = network.fromHost.getLast();

        Assertions.assertEquals(TURN_CLASS, request.getType());
        Assertions.assertEquals(FLAG_RESEND_REQUEST, request.getFlags());
        Assertions.assertEquals(0, request.getPayloadBytes().length);

        int requests = network.fromHost.size();

        network.host.receiveTurns(0, PLAYER_COUNT, 0);

        Assertions.assertEquals(requests, network.fromHost.size(), "at most one request per request interval");

        network.joiner.sendTurn(new byte[]{0x3D, 0x64});
        network.step();

        ProviderSession.TurnBatch batch = network.host.receiveTurns(0, PLAYER_COUNT, 0);

        Assertions.assertTrue(batch.isComplete());
        Assertions.assertArrayEquals(new byte[]{5}, batch.getTurn(0));
        Assertions.assertArrayEquals(new byte[]{0x3D, 0x64}, batch.getTurn(1));

        ProviderSession.TurnBatch joinerBatch = network.joiner.receiveTurns(0, PLAYER_COUNT, 0);

        Assertions.assertTrue(joinerBatch.isComplete());
        Assertions.assertArrayEquals(new byte[]{5}, joinerBatch.getTurn(0));
    }

    @Test
    void overdue_turn_request_is_counted_for_the_player_on_both_sides() {
        Network network = new Network().joinClients();
        ProviderPeer joinerOnHost = network.host.getPeers().getFirst();
        ProviderPeer hostOnJoiner = network.joiner.getPeers().getFirst();

        joinerOnHost.takeTurnResendRequestsSent();
        hostOnJoiner.takeTurnResendRequestsReceived();

        network.host.sendTurn(new byte[]{5});
        network.step();

        Assertions.assertFalse(network.host.receiveTurns(0, PLAYER_COUNT, 0).isComplete());
        Assertions.assertFalse(network.host.receiveTurns(0, PLAYER_COUNT, 0).isComplete());

        network.step();

        Assertions.assertEquals(1, joinerOnHost.takeTurnResendRequestsSent(), "at most one request per request interval");
        Assertions.assertEquals(0, joinerOnHost.takeTurnResendRequestsSent());
        Assertions.assertEquals(0, joinerOnHost.takeTurnResendRequestsReceived());
        Assertions.assertEquals(1, hostOnJoiner.takeTurnResendRequestsReceived());
        Assertions.assertEquals(0, hostOnJoiner.takeTurnResendRequestsSent());
    }

    @Test
    void departure_is_reported_when_its_turn_is_reached() {
        Network network = new Network().joinClients();

        network.joiner.leave(LEAVE_REASON);

        for (int step = 0; step < LEAVE_STEPS; step++) {
            network.step();
        }

        ProviderPeer departed = network.host.getPeers().getFirst();

        Assertions.assertEquals(FLAG_DEPARTED, departed.getFlags() & FLAG_DEPARTED);

        network.host.pollEvent();
        network.host.receiveTurns(0, PLAYER_COUNT, 0);

        ProviderSession.Event event = network.host.pollEvent();

        Assertions.assertNotNull(event);
        Assertions.assertEquals(EVENT_PLAYER_LEFT, event.getType());
        Assertions.assertEquals(1, event.getPlayerIdentifier());
        Assertions.assertEquals(UNKNOWN_IDENTIFIER, departed.getIdentifier());
    }
}
