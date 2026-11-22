package org.fogofwarlabs.sc4j.scr.data.format.mpq;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.jetbrains.annotations.NotNull;

/**
 * Provides the MPQ hash, encryption and decryption primitives.
 * <p>
 * The hash function and the block encryption both rely on the {@code 0x500}-entry crypt table that is generated from a fixed seed sequence.
 */
public final class MpqCryptoUtils {
    private static final int CRYPT_TABLE_SIZE = 0x500;
    private static final int KEY2_MIX_OFFSET = 0x400;

    private static final int HASH_TABLE_OFFSET = 0;
    private static final int HASH_NAME_A = 1;
    private static final int HASH_NAME_B = 2;
    private static final int HASH_TABLE_KEY = 3;

    private static final int HASH_SEED_1 = 0x7FED7FED;
    private static final int HASH_SEED_2 = 0xEEEEEEEE;
    private static final int SEED_2_SHIFT = 5;
    private static final int SEED_2_INCREMENT = 3;
    private static final int KEY_SHIFT_LEFT = 0x15;
    private static final int KEY_INCREMENT = 0x11111111;
    private static final int KEY_SHIFT_RIGHT = 0x0B;
    private static final int BYTE_MASK = 0xFF;
    private static final int MAXIMUM_NAME_BYTE = 0xFF;

    /**
     * The crypt table holds five blocks of {@code 0x100} entries: hash types {@code 0} to {@code 3} and the key mixing block.
     */
    private static final int CRYPT_BLOCK_SIZE = 0x100;
    private static final int CRYPT_BLOCK_SHIFT = 8;
    private static final int CRYPT_BLOCK_COUNT = 5;
    private static final int CRYPT_TABLE_SEED = 0x00100001;
    private static final int CRYPT_SEED_MULTIPLIER = 125;
    private static final int CRYPT_SEED_INCREMENT = 3;
    private static final int CRYPT_SEED_MODULUS = 0x2AAAAB;
    private static final int WORD_MASK = 0xFFFF;
    private static final int HIGH_WORD_SHIFT = 16;

    private static final int @NotNull [] CRYPT_TABLE = createCryptTable();

    /**
     * Computes the MPQ hash of the given file name text.
     * <p>
     * Each character up to {@code 0xFF} is one name byte (ASCII and ISO-8859-1 names, such as {@code (listfile)} lines read
     * as ISO-8859-1). A character above {@code 0xFF} has no byte form; it is hashed as {@code 0xFF}. Use {@link #hashString(byte[], int)}
     * for names taken from map strings.
     *
     * @param value    the file name
     * @param hashType the hash type: {@code 0} table offset, {@code 1} name hash A, {@code 2} name hash B, {@code 3} table key
     * @return the hash value
     */
    public static int hashString(@NotNull String value, int hashType) {
        return hashString(toNameBytes(value), hashType);
    }

    /**
     * Computes the MPQ hash of the given raw file name bytes by mapping {@code '/'} to a backslash and ASCII letters to upper case.
     * Every other byte is kept, including CP949 lead and trail bytes.
     *
     * @param value    the raw file name bytes, without a terminator
     * @param hashType the hash type: {@code 0} table offset, {@code 1} name hash A, {@code 2} name hash B, {@code 3} table key
     * @return the hash value
     */
    public static int hashString(byte @NotNull [] value, int hashType) {
        int seed1 = HASH_SEED_1;
        int seed2 = HASH_SEED_2;

        for (byte nameByte : value) {
            int nameCharacter = nameByte & BYTE_MASK;

            if (nameCharacter == '/') {
                nameCharacter = '\\';
            }

            if (nameCharacter >= 'a' && nameCharacter <= 'z') {
                nameCharacter -= 'a' - 'A';
            }

            seed1 = CRYPT_TABLE[(hashType << CRYPT_BLOCK_SHIFT) + nameCharacter] ^ (seed1 + seed2);
            seed2 = nameCharacter + seed1 + seed2 + (seed2 << SEED_2_SHIFT) + SEED_2_INCREMENT;
        }

        return seed1;
    }

    /**
     * Converts file name text to name bytes as {@link #hashString(String, int)} describes.
     *
     * @param value the file name text
     * @return one byte per character
     */
    public static byte @NotNull [] toNameBytes(@NotNull String value) {
        byte[] bytes = new byte[value.length()];

        for (int characterIndex = 0; characterIndex < bytes.length; characterIndex++) {
            bytes[characterIndex] = (byte) Math.min(value.charAt(characterIndex), MAXIMUM_NAME_BYTE);
        }

        return bytes;
    }

    /**
     * Computes the hash table index of the given file name.
     *
     * @param fileName the file name
     * @return the hash table offset hash
     */
    public static int hashTableOffset(@NotNull String fileName) {
        return hashString(fileName, HASH_TABLE_OFFSET);
    }

    /**
     * Computes the hash table index of the given file name.
     *
     * @param fileName the raw file name bytes
     * @return the hash table offset hash
     */
    public static int hashTableOffset(byte @NotNull [] fileName) {
        return hashString(fileName, HASH_TABLE_OFFSET);
    }

    /**
     * Computes the name hash A of the given file name.
     *
     * @param fileName the file name
     * @return the name hash A
     */
    public static int hashNameA(@NotNull String fileName) {
        return hashString(fileName, HASH_NAME_A);
    }

    /**
     * Computes the name hash A of the given file name.
     *
     * @param fileName the raw file name bytes
     * @return the name hash A
     */
    public static int hashNameA(byte @NotNull [] fileName) {
        return hashString(fileName, HASH_NAME_A);
    }

    /**
     * Computes the name hash B of the given file name.
     *
     * @param fileName the file name
     * @return the name hash B
     */
    public static int hashNameB(@NotNull String fileName) {
        return hashString(fileName, HASH_NAME_B);
    }

    /**
     * Computes the name hash B of the given file name.
     *
     * @param fileName the raw file name bytes
     * @return the name hash B
     */
    public static int hashNameB(byte @NotNull [] fileName) {
        return hashString(fileName, HASH_NAME_B);
    }

    /**
     * Computes the decryption key of the given name.
     *
     * @param name the name, for example {@code "(hash table)"} or a plain file name
     * @return the decryption key
     */
    public static int hashKey(@NotNull String name) {
        return hashString(name, HASH_TABLE_KEY);
    }

    /**
     * Computes the decryption key of the given name.
     *
     * @param name the raw name bytes, for example a plain file name
     * @return the decryption key
     */
    public static int hashKey(byte @NotNull [] name) {
        return hashString(name, HASH_TABLE_KEY);
    }

    /**
     * Decrypts the given little-endian data in place.
     *
     * @param data the little-endian {@code 32}-bit words to decrypt
     * @param key  the decryption key
     */
    public static void decrypt(int @NotNull [] data, int key) {
        int seed2 = HASH_SEED_2;

        for (int dataIndex = 0; dataIndex < data.length; dataIndex++) {
            seed2 += CRYPT_TABLE[KEY2_MIX_OFFSET + (key & BYTE_MASK)];

            // The key update uses the decrypted value.
            int decryptedValue = data[dataIndex] ^ (key + seed2);

            data[dataIndex] = decryptedValue;

            key   = ((~key << KEY_SHIFT_LEFT) + KEY_INCREMENT) | (key >>> KEY_SHIFT_RIGHT);
            seed2 = decryptedValue + seed2 + (seed2 << SEED_2_SHIFT) + SEED_2_INCREMENT;
        }
    }

    /**
     * Encrypts the given little-endian data in place; {@link #decrypt} with the same key restores it.
     *
     * @param data the little-endian {@code 32}-bit words to encrypt
     * @param key  the encryption key
     */
    public static void encrypt(int @NotNull [] data, int key) {
        int seed2 = HASH_SEED_2;

        for (int dataIndex = 0; dataIndex < data.length; dataIndex++) {
            seed2 += CRYPT_TABLE[KEY2_MIX_OFFSET + (key & BYTE_MASK)];

            // The key update uses the plain value.
            int plainValue = data[dataIndex];

            data[dataIndex] = plainValue ^ (key + seed2);

            key   = ((~key << KEY_SHIFT_LEFT) + KEY_INCREMENT) | (key >>> KEY_SHIFT_RIGHT);
            seed2 = plainValue + seed2 + (seed2 << SEED_2_SHIFT) + SEED_2_INCREMENT;
        }
    }

    /**
     * Decrypts the given little-endian bytes.
     * <p>
     * Only full {@code 4}-byte blocks are decrypted; trailing bytes that do not form a complete block are kept as they are.
     *
     * @param data the data to decrypt
     * @param key  the decryption key
     * @return the decrypted bytes
     */
    public static byte @NotNull [] decryptBytes(byte @NotNull [] data, int key) {
        int alignedLength = data.length & -Integer.BYTES;
        int[] integers = new int[alignedLength / Integer.BYTES];

        for (int integerIndex = 0; integerIndex < integers.length; integerIndex++) {
            integers[integerIndex] = LittleEndianUtils.readInt32(data, integerIndex * Integer.BYTES);
        }

        decrypt(integers, key);

        byte[] decryptedData = new byte[data.length];

        for (int integerIndex = 0; integerIndex < integers.length; integerIndex++) {
            LittleEndianUtils.writeInt32(decryptedData, integerIndex * Integer.BYTES, integers[integerIndex]);
        }

        System.arraycopy(data, alignedLength, decryptedData, alignedLength, data.length - alignedLength);

        return decryptedData;
    }

    private static int @NotNull [] createCryptTable() {
        int[] cryptTable = new int[CRYPT_TABLE_SIZE];
        int seed = CRYPT_TABLE_SEED;

        for (int byteValue = 0; byteValue < CRYPT_BLOCK_SIZE; byteValue++) {
            for (int tableIndex = byteValue, copyIndex = 0; copyIndex < CRYPT_BLOCK_COUNT; copyIndex++, tableIndex += CRYPT_BLOCK_SIZE) {
                seed = (seed * CRYPT_SEED_MULTIPLIER + CRYPT_SEED_INCREMENT) % CRYPT_SEED_MODULUS;

                int highWord = (seed & WORD_MASK) << HIGH_WORD_SHIFT;

                seed = (seed * CRYPT_SEED_MULTIPLIER + CRYPT_SEED_INCREMENT) % CRYPT_SEED_MODULUS;

                int lowWord = seed & WORD_MASK;

                cryptTable[tableIndex] = highWord | lowWord;
            }
        }

        return cryptTable;
    }


    private MpqCryptoUtils() {
    }
}
