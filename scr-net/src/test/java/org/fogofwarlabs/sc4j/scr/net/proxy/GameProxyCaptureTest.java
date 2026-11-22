package org.fogofwarlabs.sc4j.scr.net.proxy;

import org.fogofwarlabs.sc4j.scr.net.provider.ProviderTransportMessage;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.HexFormat;

/**
 * Verifies that recorded proxy datagrams re-encode byte for byte with the sender's key and that the probe answer rebuilds.
 */
final class GameProxyCaptureTest {
    /**
     * {@code 192.168.0.2} to {@code .88}: the first join request (class {@code 0} subtype {@code 1}), a probe.
     */
    static final @NotNull String PROBE = "080112140000000028c41000000001000001ff00010000002886ff84f28ab9bdfd4f" +
                                         "48feb5a0b5e89597035a1218cb81c4c30c20e02f30c0d182c00538e02f";

    /**
     * {@code 192.168.0.88} to {@code .2}: the answer to that probe.
     */
    static final @NotNull String PROBE_ANSWER = "08011800200048feb5a0b5e89597035a0b280030c0d182c00538e02f6009";

    private static final long JOINER_KEY = 0x9DB4F0AF0AF2E7D6L;
    private static final long HOST_KEY = 0x5B760EFE3A6F074BL;
    /**
     * Ordinary datagrams: the joiner's subtype {@code 7}, the host's subtype {@code 9}, a joiner bare acknowledgement, a host turn.
     */
    private static final @NotNull String @NotNull [] JOINER_DATA = {
            "0801121400000000267d1000020002000007ff004100000028edff958c91e49291df015a00",
            "08011210000000005f820c00080008000000010128db8dbcbff0f1b7545a00"
    };
    private static final @NotNull String @NotNull [] HOST_DATA = {
            "0801123000000000e4902c0005000300000900000f0001000100000001010102020001010001000000000032000000000000000028a0c6bb938c94dac23c5a00",
            "0801121000000000d6ee0c00150015000200000228dae4e181badc8d86da015a00"
    };

    private static final int TYPE_PROBE_ANSWER = 9;

    private static void assertReencodedDatagram(@NotNull String encodedHexadecimal, long key) throws Exception {
        GameProxyDatagram recorded = GameProxyDatagram.parse(HexFormat.of().parseHex(encodedHexadecimal));
        ProviderTransportMessage message = recorded.parseProviderMessage();
        GameProxyDatagram encoded = GameProxyDatagram.createData(message,
                                                                 GameProxyDatagram.calculatePayloadHash(message.getEncodedBytes()) ^ key,
                                                                 0,
                                                                 new byte[0]);

        Assertions.assertEquals(encodedHexadecimal, HexFormat.of().formatHex(encoded.getEncodedBytes()));
        Assertions.assertArrayEquals(message.getEncodedBytes(), encoded.getPayloadBytes());
    }

    /**
     * Takes the sender's key as field {@code 5} XOR the payload hash of one datagram; every other datagram of the same
     * sender must then reproduce its field {@code 5}. The probe's public address (field {@code 11.3}) is replaced by
     * {@code 203.0.113.200} (documentation range), keeping the variable integer length.
     */
    @Test
    void probe_and_ordinary_datagrams_reencode_with_the_sender_key() throws Exception {
        GameProxyDatagram probe = GameProxyDatagram.parse(HexFormat.of().parseHex(PROBE));
        ProviderTransportMessage request = probe.parseProviderMessage();
        long payloadHash = GameProxyDatagram.calculatePayloadHash(request.getEncodedBytes());

        Assertions.assertEquals(JOINER_KEY, probe.getScalarField(GameProxyDatagram.PAYLOAD_HASH_FIELD) ^ payloadHash);

        int publicAddress = GameProxySocket.toNativeAddress(InetAddress.getByName("203.0.113.200"));
        int host = GameProxySocket.toNativeAddress(InetAddress.getByName("192.168.0.88"));
        byte[] addressInformation = GameProxyDatagram.encodeAddressInformation(publicAddress, GameProxySocket.GAME_PORT,
                                                                               null,
                                                                               host, GameProxySocket.GAME_PORT);
        GameProxyDatagram encoded = GameProxyDatagram.createData(request,
                                                                 payloadHash ^ JOINER_KEY,
                                                                 probe.getScalarField(GameProxyDatagram.PROBE_TIMESTAMP_FIELD),
                                                                 addressInformation);

        Assertions.assertEquals(PROBE, HexFormat.of().formatHex(encoded.getEncodedBytes()));

        GameProxyDatagram answer = GameProxyDatagram.createProbeAnswer(probe);

        Assertions.assertEquals(PROBE_ANSWER, HexFormat.of().formatHex(answer.getEncodedBytes()));
        Assertions.assertEquals(TYPE_PROBE_ANSWER, GameProxyDatagram.parse(HexFormat.of().parseHex(PROBE_ANSWER)).getMessageType());

        for (String encodedHexadecimal : JOINER_DATA) {
            assertReencodedDatagram(encodedHexadecimal, JOINER_KEY);
        }

        for (String encodedHexadecimal : HOST_DATA) {
            assertReencodedDatagram(encodedHexadecimal, HOST_KEY);
        }
    }
}
