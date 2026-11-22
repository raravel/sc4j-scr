package org.fogofwarlabs.sc4j.scr.data.format.mpq;

import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.test.DataTestResources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Tests MPQ sector methods against fixed vectors and optional installed-map WAVE files.
 */
final class MpqDecompressorUtilsTest {
    private static final int UNKNOWN_METHOD = 0x04;
    private static final int BZIP2_METHOD = 0x10;
    private static final int SPARSE_METHOD = 0x20;
    private static final int ADPCM_MONO_METHOD = 0x40;
    private static final int ADPCM_STEREO_METHOD = 0x80;
    private static final int CAPACITY_MARGIN = 100;
    private static final int RIFF_HEADER_SIZE = 12;
    private static final int WAVE_TAG_OFFSET = 8;
    private static final int TAG_LENGTH = 4;
    private static final int SAMPLE_TEXT_REPEAT_COUNT = 40;
    private static final int BYTE_VALUE_COUNT = 256;
    private static final int SAMPLE_ZERO_COUNT = 300;
    /**
     * The index of the stream byte the corruption test flips: the first byte of the block CRC after the stream and block magic.
     */
    private static final int CORRUPTED_BYTE_INDEX = 10;

    /**
     * {@code bz2.compress(sample, 9)} of {@link #createBzip2Sample()}.
     */
    private static final @NotNull String BZIP2_STREAM = "425a6839314159265359c6f7c6480000927fffffffffffffffffffffffffffffffffffffffffffffffffffff" +
                                                        "ffffffffffffffb001182100000000000000000000000000000000009804c000000000000000000000260002" +
                                                        "60024c0013000130000000000000000000000000000000000000000000000000000000004a5490d34c81a794" +
                                                        "cd4681b53403469a34c40c869a0d188326803468034c27947a834323469a0068c434d34d3406800000d068c4" +
                                                        "0640c9843406419341a01faff3002c50951a930d4d89d18d92ab56abd62b2cb5a52aab6ccceaeb95d68af57e" +
                                                        "c161b134ac5cd4a11101e8041c0041aacd67b45a6d4d76bb635b65b5b6dd6f6eb837ad010715571b95cee975" +
                                                        "7273bb5def0e97975724577a6073bddf2faed7ebfbbbc3ce0302d88880c982c1a80107a7b4041bb08f985c36" +
                                                        "1f1189c562f18fb8dc73f63f20cc02191ca00864f3202195a8659ff2f08021030507985ab9780830808669d5" +
                                                        "40082f52020f8a40414b166f399dcf2b01084559fd0686161b45a387d26962222262a2e33fb1b1d1f2123252" +
                                                        "7292b2d2f31333537393b3d3f41434547494b4d4f51535557595b5d5f61636567696b6d6f71737577797b7d7" +
                                                        "f81838587898b8d8f91939597999b9d9fa1a3a5a7a9abadafb1b3b5b7b9bbbdbfc1c3c5c7c9fee5e6e7e8e9e" +
                                                        "aebecedeeeff0f1f2f3fffa7afb7bfc697cfcfd7dfe7e8bb9229c2848637be3240";

    private static byte @Nullable [] decompress(int methodMask, byte @NotNull [] data, int capacity) {
        byte[] sector = new byte[data.length + 1];

        sector[0] = (byte) methodMask;
        System.arraycopy(data, 0, sector, 1, data.length);

        byte[] output = new byte[capacity];
        int outputSize = MpqDecompressorUtils.decompress(sector, output, capacity);

        return outputSize == MpqDecompressorUtils.FAILED ? null : Arrays.copyOf(output, outputSize);
    }

    private static byte @NotNull [] createSamples(int @NotNull ... values) {
        byte[] bytes = new byte[values.length * Short.BYTES];

        for (int index = 0; index < values.length; index++) {
            bytes[index * Short.BYTES]     = (byte) values[index];
            bytes[index * Short.BYTES + 1] = (byte) (values[index] >> Byte.SIZE);
        }

        return bytes;
    }

    private static byte @NotNull [] createBzip2Sample() {
        ByteArrayOutputStream sample = new ByteArrayOutputStream();

        sample.writeBytes("MPQ bzip2 sector ".repeat(SAMPLE_TEXT_REPEAT_COUNT).getBytes(StandardCharsets.US_ASCII));

        for (int value = 0; value < BYTE_VALUE_COUNT; value++) {
            sample.write(value);
        }

        sample.writeBytes(new byte[SAMPLE_ZERO_COUNT]);
        sample.writeBytes("AAAAAAAAAAB".getBytes(StandardCharsets.US_ASCII));

        return sample.toByteArray();
    }


    @Test
    void decompress_should_decode_bzip2_with_buff_to_buff_contract() {
        byte[] sample = createBzip2Sample();
        byte[] stream = HexFormat.of().parseHex(BZIP2_STREAM);

        Assertions.assertArrayEquals(sample, decompress(BZIP2_METHOD, stream, sample.length + CAPACITY_MARGIN));
        Assertions.assertNull(decompress(BZIP2_METHOD, stream, sample.length - 1), "BZ_OUTBUFF_FULL");
        Assertions.assertNull(decompress(BZIP2_METHOD, Arrays.copyOf(stream, stream.length - 3), sample.length + CAPACITY_MARGIN),
                              "BZ_UNEXPECTED_EOF");

        byte[] corrupted = stream.clone();

        corrupted[CORRUPTED_BYTE_INDEX] ^= 1;

        Assertions.assertNull(decompress(BZIP2_METHOD, corrupted, sample.length + CAPACITY_MARGIN), "block CRC");
    }

    @Test
    void decompress_should_expand_sparse_chunks_of_thirty_two_plus_count() {
        // Declared 80 bytes: copy 0x20 bytes, zero 0x25 bytes, then copy the remaining 11 of a 0x9F-byte chunk.
        ByteArrayOutputStream sector = new ByteArrayOutputStream();

        sector.writeBytes(new byte[]{0, 0, 0, 80});

        byte[] first = new byte[0x20];

        Arrays.fill(first, (byte) 0x11);
        sector.write(0x80);
        sector.writeBytes(first);
        sector.write(0x05);

        byte[] last = new byte[11];

        Arrays.fill(last, (byte) 0x22);
        sector.write(0xFF);
        sector.writeBytes(last);

        byte[] expected = new byte[80];

        Arrays.fill(expected, 0, 0x20, (byte) 0x11);
        Arrays.fill(expected, 69, 80, (byte) 0x22);

        Assertions.assertArrayEquals(expected, decompress(SPARSE_METHOD, sector.toByteArray(), 200));
        Assertions.assertNull(decompress(SPARSE_METHOD, new byte[]{0, 0, 0, 1}, 200), "fewer than five bytes");
    }

    @Test
    void decompress_should_decode_mono_adpcm_samples_and_commands() {
        // Shift 2, initial sample 1000. 0x05 adds 494 + 123 + 123 at step index 44; 0x80 repeats and lowers the index;
        // 0x81 raises it by 8; 0x83 does nothing; 0x40 subtracts 1166 >> 2 at index 53.
        byte[] sector = {0, 2, (byte) 0xE8, 0x03, 0x05, (byte) 0x80, (byte) 0x81, (byte) 0x83, 0x40};

        Assertions.assertArrayEquals(createSamples(1000, 1740, 1740, 1449), decompress(ADPCM_MONO_METHOD, sector, 100));
        Assertions.assertArrayEquals(createSamples(1000, 1740), MpqAdpcmUtils.decompress(sector, 5, 1), "whole samples within the capacity");
        Assertions.assertNull(decompress(ADPCM_MONO_METHOD, sector, 5), "a capacity below the stored size fails before any method");
    }

    @Test
    void decompress_should_keep_stereo_channel_after_step_commands() {
        // Shift 4, initial samples 100 and -100. 0x00 decodes the left channel (+30), 0x80 repeats the right one, 0x81
        // raises the left index to 51 and keeps the left channel, whose next sample adds 60 + 963.
        byte[] sector = {0, 4, 100, 0, (byte) 0x9C, (byte) 0xFF, 0x00, (byte) 0x80, (byte) 0x81, 0x01};

        Assertions.assertArrayEquals(createSamples(100, -100, 130, -100, 1153), decompress(ADPCM_STEREO_METHOD, sector, 100));
    }

    @Test
    void decompress_should_reject_unknown_method_bits() {
        Assertions.assertNull(decompress(UNKNOWN_METHOD, new byte[]{1, 2, 3}, 100));
    }

    @RequiresInstallation
    @Test
    void installed_map_waves_decode_to_riff_wave_files() throws IOException {
        List<Path> mapFiles = DataTestResources.listInstalledMaps();
        int waveCount = 0;

        for (Path mapFile : mapFiles) {
            MpqArchive archive = MpqArchive.open(mapFile);

            for (MpqArchive.MpqEntry entry : archive.getEntries()) {
                String fileName = entry.getFileName();

                if (fileName == null || !fileName.toLowerCase().endsWith(".wav") || entry.getFileSize() < RIFF_HEADER_SIZE) {
                    continue;
                }

                byte[] wave = archive.readFile(fileName);

                Assertions.assertEquals("RIFF", new String(wave, 0, TAG_LENGTH, StandardCharsets.US_ASCII), mapFile + " " + fileName);
                Assertions.assertEquals("WAVE", new String(wave, WAVE_TAG_OFFSET, TAG_LENGTH, StandardCharsets.US_ASCII), mapFile + " " + fileName);

                waveCount++;
            }
        }

        Assertions.assertTrue(waveCount > 0, "No map with a named WAVE file.");
    }
}
