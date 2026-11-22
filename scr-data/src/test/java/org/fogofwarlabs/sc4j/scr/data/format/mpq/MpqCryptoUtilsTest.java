package org.fogofwarlabs.sc4j.scr.data.format.mpq;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

/**
 * Tests the table encryption of {@link MpqCryptoUtils}.
 */
final class MpqCryptoUtilsTest {
    private static final long RANDOM_SEED = 0x5C4L;
    private static final int VALUE_COUNT = 64;

    @Test
    void encrypt_should_be_inverted_by_decrypt() {
        Random random = new Random(RANDOM_SEED);
        int[] plain = random.ints(VALUE_COUNT).toArray();
        int key = MpqCryptoUtils.hashKey("(hash table)");
        int[] data = plain.clone();

        MpqCryptoUtils.encrypt(data, key);

        Assertions.assertFalse(Arrays.equals(plain, data));

        MpqCryptoUtils.decrypt(data, key);

        Assertions.assertArrayEquals(plain, data);
    }
}
