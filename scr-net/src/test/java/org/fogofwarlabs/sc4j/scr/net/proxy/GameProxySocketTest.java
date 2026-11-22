package org.fogofwarlabs.sc4j.scr.net.proxy;

import org.fogofwarlabs.sc4j.scr.net.discovery.ProtobufUtils;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderPacket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Exchanges proxy datagrams with an opened socket over the loopback interface: the probes it sends and its answers to recorded probes.
 */
final class GameProxySocketTest {
    /**
     * A relayed probe without a provider payload: type {@code 8}, fields {@code 3} and {@code 4} {@code 192.168.0.2:6112}, the
     * captured probe timestamp, and the address information of the captured probe with the relay flag set.
     */
    private static final @NotNull String RELAYED_PROBE = "080818c0d1821020e02f48feb5a0b5e89597035a0b280130c0d182c00538e02f";
    /**
     * The answer to {@link #RELAYED_PROBE}: fields {@code 3} and {@code 4} copied into the address information fields {@code 1} and {@code 2}.
     */
    private static final @NotNull String RELAYED_PROBE_ANSWER = "080118c0d1821020e02f" +
                                                                "48feb5a0b5e89597035a1308c0d1821010e02f280130c0d182c00538e02f6009";

    /**
     * The address information of a probe sent over the loopback interface up to the destination port: field {@code 6} naming
     * {@code 127.0.0.1} and the tag of field {@code 7}.
     */
    private static final @NotNull String LOOPBACK_PROBE_ADDRESS_INFORMATION = "30ff80800838";
    /**
     * The type field and the payload field of a data datagram that carries the provider packet of
     * {@code send_should_address_a_probe_to_the_destination_only}.
     */
    private static final @NotNull String PROBE_DATA_FIELDS = "0801121300000000913b0f003412cdab02000700153412";
    /**
     * The tags of the payload hash field {@code 5}, the probe timestamp field {@code 9} and the address information field
     * {@code 11}.
     */
    private static final int PAYLOAD_HASH_TAG = 0x28;
    private static final int PROBE_TIMESTAMP_TAG = 0x48;
    private static final int ADDRESS_INFORMATION_TAG = 0x5A;

    /**
     * The unknown fixed {@code 64}-bit field appended to make the hash of a whole datagram zero.
     */
    private static final int HASH_CANCEL_TAG = 0x79;

    private static final int RECEIVE_TIMEOUT_MILLISECONDS = 100;
    private static final int POLL_ATTEMPTS = 50;
    private static final int SILENCE_TIMEOUT_MILLISECONDS = 300;
    private static final int PROBE_TIMEOUT_MILLISECONDS = 5000;
    private static final int MAXIMUM_DATAGRAM = 2048;

    private static void sendDatagram(@NotNull DatagramSocket peer, @NotNull GameProxySocket socket, byte @NotNull [] datagram) throws IOException {
        peer.send(new DatagramPacket(datagram, datagram.length, new InetSocketAddress(InetAddress.getLoopbackAddress(), socket.getLocalPort())));
    }

    /**
     * Polls the socket until the peer receives a datagram, collecting the sources of the delivered provider packets.
     *
     * @param socket  the socket to poll
     * @param peer    the peer that receives the answer
     * @param sources the sources of the delivered provider packets
     * @return the received datagram, or {@code null} if none arrived
     * @throws IOException if the peer cannot receive
     */
    private static byte @Nullable [] pollAnswer(@NotNull GameProxySocket socket,
                                                @NotNull DatagramSocket peer,
                                                @NotNull List<InetSocketAddress> sources) throws IOException {
        DatagramPacket packet = new DatagramPacket(new byte[MAXIMUM_DATAGRAM], MAXIMUM_DATAGRAM);

        peer.setSoTimeout(RECEIVE_TIMEOUT_MILLISECONDS);

        for (int attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
            socket.poll((source, payload) -> sources.add(source));

            try {
                peer.receive(packet);
            } catch (SocketTimeoutException exception) {
                continue;
            }

            return Arrays.copyOf(packet.getData(), packet.getLength());
        }

        return null;
    }

    private static byte @NotNull [] encodeLittleEndian(long value) {
        byte[] bytes = new byte[Long.BYTES];

        for (int index = 0; index < Long.BYTES; index++) {
            bytes[index] = (byte) (value >>> (index * Byte.SIZE));
        }

        return bytes;
    }

    @Test
    void send_should_address_a_probe_to_the_destination_only() throws Exception {
        ProviderPacket packet = ProviderPacket.createTurn(0x1234, 0xABCD, 7, new byte[]{0x15, 0x34, 0x12});

        try (GameProxySocket socket = GameProxySocket.open(0);
             DatagramSocket peer = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            DatagramPacket received = new DatagramPacket(new byte[MAXIMUM_DATAGRAM], MAXIMUM_DATAGRAM);
            ByteArrayOutputStream addressInformation = new ByteArrayOutputStream();
            ByteArrayOutputStream expected = new ByteArrayOutputStream();

            socket.send(new InetSocketAddress(InetAddress.getLoopbackAddress(), peer.getLocalPort()), packet);
            peer.setSoTimeout(PROBE_TIMEOUT_MILLISECONDS);
            peer.receive(received);

            byte[] probe = Arrays.copyOf(received.getData(), received.getLength());
            GameProxyDatagram datagram = GameProxyDatagram.parse(probe);

            addressInformation.writeBytes(HexFormat.of().parseHex(LOOPBACK_PROBE_ADDRESS_INFORMATION));
            ProtobufUtils.writeVariableInteger(addressInformation, peer.getLocalPort());

            long payloadHash = datagram.getScalarField(GameProxyDatagram.PAYLOAD_HASH_FIELD);
            long probeTimestamp = datagram.getScalarField(GameProxyDatagram.PROBE_TIMESTAMP_FIELD);

            // The hash and the timestamp vary with the socket's key and the clock: the expected probe takes them from the sent one
            // and, like a data datagram, leaves out a field whose value is zero.
            expected.writeBytes(HexFormat.of().parseHex(PROBE_DATA_FIELDS));

            if (payloadHash != 0) {
                expected.write(PAYLOAD_HASH_TAG);
                ProtobufUtils.writeVariableInteger(expected, payloadHash);
            }

            if (probeTimestamp != 0) {
                expected.write(PROBE_TIMESTAMP_TAG);
                ProtobufUtils.writeVariableInteger(expected, probeTimestamp);
            }

            expected.write(ADDRESS_INFORMATION_TAG);
            ProtobufUtils.writeVariableInteger(expected, addressInformation.size());
            expected.writeBytes(addressInformation.toByteArray());

            Assertions.assertTrue(datagram.hasScalarField(GameProxyDatagram.PROBE_TIMESTAMP_FIELD));
            Assertions.assertEquals(HexFormat.of().formatHex(expected.toByteArray()), HexFormat.of().formatHex(probe));
        }
    }

    @Test
    void poll_should_answer_a_probe_with_the_address_the_prober_addressed() throws Exception {
        try (GameProxySocket socket = GameProxySocket.open(0);
             DatagramSocket peer = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            List<InetSocketAddress> sources = new ArrayList<>();

            sendDatagram(peer, socket, HexFormat.of().parseHex(GameProxyCaptureTest.PROBE));

            byte[] answer = pollAnswer(socket, peer, sources);

            Assertions.assertNotNull(answer);
            Assertions.assertEquals(GameProxyCaptureTest.PROBE_ANSWER, HexFormat.of().formatHex(answer));
            Assertions.assertEquals(List.of(peer.getLocalSocketAddress()), sources);
        }
    }

    @Test
    void poll_should_not_answer_a_probe_the_hash_gate_drops() throws Exception {
        ByteArrayOutputStream gated = new ByteArrayOutputStream();

        gated.writeBytes(HexFormat.of().parseHex(GameProxyCaptureTest.PROBE));
        gated.write(HASH_CANCEL_TAG);
        gated.writeBytes(encodeLittleEndian(GameProxyDatagram.calculatePayloadHash(gated.toByteArray())));

        Assertions.assertEquals(0, GameProxyDatagram.calculatePayloadHash(gated.toByteArray()));

        try (GameProxySocket socket = GameProxySocket.open(0);
             DatagramSocket peer = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            List<InetSocketAddress> sources = new ArrayList<>();

            sendDatagram(peer, socket, gated.toByteArray());
            sendDatagram(peer, socket, HexFormat.of().parseHex(GameProxyCaptureTest.PROBE));

            byte[] answer = pollAnswer(socket, peer, sources);

            Assertions.assertNotNull(answer);
            Assertions.assertEquals(GameProxyCaptureTest.PROBE_ANSWER, HexFormat.of().formatHex(answer));
            Assertions.assertEquals(1, sources.size());

            peer.setSoTimeout(SILENCE_TIMEOUT_MILLISECONDS);

            Assertions.assertThrows(SocketTimeoutException.class,
                                    () -> peer.receive(new DatagramPacket(new byte[MAXIMUM_DATAGRAM], MAXIMUM_DATAGRAM)));
        }
    }

    @Test
    void poll_should_answer_a_relayed_probe_without_delivering_it() throws Exception {
        try (GameProxySocket socket = GameProxySocket.open(0);
             DatagramSocket peer = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            List<InetSocketAddress> sources = new ArrayList<>();

            sendDatagram(peer, socket, HexFormat.of().parseHex(RELAYED_PROBE));

            byte[] answer = pollAnswer(socket, peer, sources);

            Assertions.assertNotNull(answer);
            Assertions.assertEquals(RELAYED_PROBE_ANSWER, HexFormat.of().formatHex(answer));
            Assertions.assertTrue(sources.isEmpty());
        }
    }
}
