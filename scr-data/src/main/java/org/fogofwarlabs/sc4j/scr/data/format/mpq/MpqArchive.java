package org.fogofwarlabs.sc4j.scr.data.format.mpq;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.scr.data.extension.ScrDataExtensionUtils;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reads MPQ map archives with tolerant header, table and sector handling.
 * The whole archive is retained in memory; missing file data is returned as zero-filled bytes.
 */
public final class MpqArchive {
    /**
     * The block flag of a file compressed with PKWARE implode.
     */
    public static final int MPQ_FILE_IMPLODE = 0x00000100;
    /**
     * The block flag of a file compressed with a multi-method compression.
     */
    public static final int MPQ_FILE_COMPRESS = 0x00000200;
    /**
     * The block flag of an encrypted file.
     */
    public static final int MPQ_FILE_ENCRYPTED = 0x00010000;
    /**
     * The block flag of a file whose key is adjusted by its block offset and size.
     */
    public static final int MPQ_FILE_FIX_KEY = 0x00020000;
    /**
     * The block flag of a file stored as one unit instead of sectors; the map reader clears it.
     */
    public static final int MPQ_FILE_SINGLE_UNIT = 0x01000000;
    /**
     * The block flag of a file with sector checksums; the checksums are not used.
     */
    public static final int MPQ_FILE_SECTOR_CRC = 0x04000000;
    /**
     * The block flag of an existing file.
     */
    public static final int MPQ_FILE_EXISTS = 0x80000000;
    /**
     * The English (United States) locale identifier, the fallback game-language locale.
     */
    public static final int ENGLISH_LOCALE = 0x409;
    /**
     * The largest {@code staredit\scenario.chk} the native map list accepts; it skips a map whose scenario is larger.
     */
    public static final int NATIVE_MAXIMUM_SCENARIO_SIZE = 0x10000000;
    /**
     * The largest file size an array holds.
     */
    public static final int MAXIMUM_FILE_SIZE = Integer.MAX_VALUE - Long.BYTES;

    private static final long DWORD_MASK = 0xFFFFFFFFL;
    private static final @NotNull Logger LOGGER = Logger.of(MpqArchive.class);

    private static final int MPQ_MAGIC = 0x1A51504D;
    /**
     * The header bytes copied from each candidate position.
     */
    private static final int HEADER_COPY_SIZE = 0x2C;
    private static final int HEADER_SIZE_OFFSET = 4;
    private static final int SECTOR_SHIFT_OFFSET = 14;
    private static final int HASH_TABLE_POSITION_OFFSET = 16;
    private static final int BLOCK_TABLE_POSITION_OFFSET = 20;
    private static final int HASH_TABLE_SIZE_OFFSET = 24;
    private static final int BLOCK_TABLE_SIZE_OFFSET = 28;
    /**
     * The minimum header size field and the minimum bytes of a header buffer refill.
     */
    private static final int MINIMUM_HEADER_SIZE = 0x20;
    /**
     * The header search step without the {@code 0x10} open flag.
     */
    private static final int HEADER_SEARCH_STEP = 0x200;
    /**
     * The bytes that one header buffer refill reads.
     */
    private static final int HEADER_BUFFER_SIZE = 0x1000;
    /**
     * The largest accepted hash or block table entry count.
     */
    private static final int MAXIMUM_TABLE_ENTRY_COUNT = 0x40000;
    /**
     * The map-mode mask of the hash table size and the hash entry block index.
     */
    private static final int MAP_INDEX_MASK = 0x0FFFFFFF;
    /**
     * The block flags that the map reader clears: single unit and patch file.
     */
    private static final int MAP_CLEARED_BLOCK_FLAGS = 0x03000000;
    /**
     * The hash entry DWORD that holds the locale WORD, the platform byte and the byte that the reader clears.
     */
    private static final int HASH_ENTRY_LOCALE_MASK = 0x00FFFFFF;
    private static final int BASE_SECTOR_SIZE = 0x200;
    private static final int SECTOR_SHIFT_MASK = 0x1F;
    private static final int COMPRESSION_FLAGS_MASK = 0xFF00;
    /**
     * The page size of the unbuffered retry read.
     */
    private static final int RETRY_READ_ALIGNMENT = 0x1000;
    /**
     * The unsigned {@code 32}-bit mask that rounds a retry read length down to whole pages.
     */
    private static final long RETRY_READ_PAGE_MASK = 0xFFFFF000L;

    /**
     * The DWORD count of a hash or block table entry.
     */
    private static final int TABLE_ENTRY_VALUE_COUNT = 4;
    private static final int HASH_NAME_A_VALUE = 0;
    private static final int HASH_NAME_B_VALUE = 1;
    private static final int HASH_LOCALE_VALUE = 2;
    private static final int HASH_BLOCK_INDEX_VALUE = 3;
    private static final int BLOCK_POSITION_VALUE = 0;
    private static final int BLOCK_COMPRESSED_SIZE_VALUE = 1;
    private static final int BLOCK_FILE_SIZE_VALUE = 2;
    private static final int BLOCK_FLAGS_VALUE = 3;
    private static final int EMPTY_BLOCK_INDEX = -1;
    private static final int DELETED_BLOCK_INDEX = -2;
    /**
     * The neutral locale of a hash entry, which the lookup accepts for any configured locale.
     */
    private static final int NEUTRAL_LOCALE = 0;
    /**
     * The neutral platform identifier.
     */
    private static final int NEUTRAL_PLATFORM = 0;
    private static final int LOCALE_MASK = 0xFFFF;
    private static final int PLATFORM_SHIFT = 16;
    private static final int PLATFORM_MASK = 0xFF;
    private static final int GERMAN_LOCALE = 0x407;
    private static final int SPANISH_LOCALE = 0x40A;
    private static final int FRENCH_LOCALE = 0x40C;
    private static final int ITALIAN_LOCALE = 0x410;
    /**
     * A game language code: a two-letter language and a two-letter region, for example {@code koKR}.
     */
    private static final int GAME_LANGUAGE_LENGTH = 4;
    private static final int LANGUAGE_LENGTH = 2;

    private static final @NotNull String HASH_TABLE_KEY_NAME = "(hash table)";
    private static final @NotNull String BLOCK_TABLE_KEY_NAME = "(block table)";
    private static final @NotNull String LISTFILE_NAME = "(listfile)";
    private static final @NotNull String ATTRIBUTES_NAME = "(attributes)";
    private static final int ATTRIBUTES_HEADER_SIZE = 8;
    private static final int ATTRIBUTES_FLAGS_OFFSET = 4;
    /**
     * The {@code (attributes)} size bound is {@code 512} bytes per block entry.
     */
    private static final int ATTRIBUTES_ENTRY_SHIFT = 9;
    private static final int ATTRIBUTE_CRC32 = 0x1;
    private static final int ATTRIBUTE_FILE_TIME = 0x2;
    private static final int ATTRIBUTE_MD5 = 0x4;
    private static final int ATTRIBUTE_KNOWN_FLAGS = ATTRIBUTE_CRC32 | ATTRIBUTE_FILE_TIME | ATTRIBUTE_MD5;
    private static final int ATTRIBUTE_CRC32_SIZE = 4;
    private static final int ATTRIBUTE_FILE_TIME_SIZE = 8;
    private static final int ATTRIBUTE_MD5_SIZE = 16;

    /**
     * The configured lookup locale, defaulting to the English game-language locale.
     */
    private static volatile int locale = ENGLISH_LOCALE;

    /**
     * Represents a single file entry of the archive.
     */
    public static final class MpqEntry {
        private final @Nullable String fileName;
        private final long filePosition;
        private final long compressedSize;
        private final long fileSize;
        private final int flags;

        /**
         * Creates a {@link MpqEntry} instance.
         *
         * @param fileName       the file name, or {@code null} if the archive does not reveal it
         * @param filePosition   the absolute offset of the file data inside the archive file
         * @param compressedSize the stored size of the file
         * @param fileSize       the decompressed size of the file
         * @param flags          the file flags as the map reader keeps them
         */
        private MpqEntry(@Nullable String fileName, long filePosition, long compressedSize, long fileSize, int flags) {
            this.fileName       = fileName;
            this.filePosition   = filePosition;
            this.compressedSize = compressedSize;
            this.fileSize       = fileSize;
            this.flags          = flags;
        }

        /**
         * @return the file name, or {@code null} if the archive does not reveal it
         */
        public @Nullable String getFileName() {
            return this.fileName;
        }

        /**
         * @return the absolute offset of the file data inside the archive file
         */
        public long getFilePosition() {
            return this.filePosition;
        }

        /**
         * @return the stored size of the file
         */
        public long getCompressedSize() {
            return this.compressedSize;
        }

        /**
         * @return the decompressed size of the file
         */
        public long getFileSize() {
            return this.fileSize;
        }

        /**
         * @return the file flags as the map reader keeps them
         */
        public int getFlags() {
            return this.flags;
        }
    }

    /**
     * Reads sectors of one opened file.
     */
    private final class SectorReader {
        private final int fileSize;
        private final int flags;
        private final int fileKey;
        private final long filePosition;
        private final int sectorCount;
        private final @NotNull String fileName;

        private int @Nullable [] sectorOffsets;

        /**
         * Creates a {@link SectorReader} instance.
         *
         * @param blockIndex the block index of the file
         * @param fileName   the raw name bytes used to open the file
         * @param fileSize   the unsigned decompressed file size
         * @param flags      the block flags as the map reader keeps them
         */
        private SectorReader(int blockIndex, byte @NotNull [] fileName, int fileSize, int flags) {
            this.fileSize     = fileSize;
            this.flags        = flags;
            this.fileKey      = MpqArchive.this.computeFileKey(blockIndex, fileName, fileSize, flags);
            this.filePosition = MpqArchive.this.getFilePosition(blockIndex);
            // Unsigned 32-bit arithmetic, so an empty file has no sector.
            this.sectorCount = Integer.divideUnsigned(fileSize - 1 + MpqArchive.this.sectorSize, MpqArchive.this.sectorSize);
            this.fileName    = new String(fileName, StandardCharsets.ISO_8859_1);

            this.sectorOffsets = null;
        }

        /**
         * Reads a sector-aligned range; any sector failure makes the whole range read nothing.
         *
         * @param offset the sector-aligned file offset
         * @param size   the requested byte count
         * @param output the destination buffer
         * @return the reported byte count
         */
        private int read(int offset, int size, byte @NotNull [] output) {
            int readCount = this.readSectors(offset, size, output);

            if (readCount < 0) {
                LOGGER.debug(() -> "MPQ file " + this.fileName + " sectors from " + Integer.toUnsignedString(offset) +
                                   " fail in the original reader: " + MpqArchive.this.description);

                return 0;
            }

            return readCount;
        }

        private int readSectors(int offset, int size, byte @NotNull [] output) {
            if (size == 0) {
                return 0;
            }

            int sectorSize = MpqArchive.this.sectorSize;
            boolean compressed = (this.flags & COMPRESSION_FLAGS_MASK) != 0;
            int[] offsets = null;

            if (compressed) {
                offsets = this.loadSectorOffsets();

                if (offsets == null) {
                    return -1;
                }

                // The map flag turns a decreasing offset table into a successful empty read.
                if (offsets.length == 0) {
                    return 0;
                }
            }

            int firstSector = Integer.divideUnsigned(offset, sectorSize);
            int rawStart = compressed ? offsets[firstSector] : offset;
            int rawLength = compressed ? offsets[Integer.divideUnsigned(offset + size, sectorSize)] - rawStart : size;
            long rawPosition = this.filePosition + (rawStart & DWORD_MASK);
            int outputSize = size;
            long availableBytes = MpqArchive.this.archiveData.length - rawPosition;

            if (availableBytes < (rawLength & DWORD_MASK)) {
                // The strict first pass fails on the short read; the unbuffered retry reads whole 4 KiB pages and counts the
                // page prefix as data, so it reports more bytes than the file holds.
                long pagePrefix = Math.floorMod(rawPosition, RETRY_READ_ALIGNMENT);
                long pageStart = rawPosition - pagePrefix;
                long pageLength = (pagePrefix + RETRY_READ_ALIGNMENT - 1 + (rawLength & DWORD_MASK)) & RETRY_READ_PAGE_MASK;
                long reportedBytes = Math.max(0, Math.min(pageLength, MpqArchive.this.archiveData.length - pageStart));

                LOGGER.debug(() -> "MPQ file " + this.fileName + " sector data ends past the file; bytes past the end read as zero " +
                                   "(stale memory in the original): " + MpqArchive.this.description);

                if (reportedBytes < (rawLength & DWORD_MASK)) {
                    if (compressed) {
                        outputSize = 0;

                        for (int sectorIndex = firstSector + 1; Integer.compareUnsigned(sectorIndex, this.sectorCount) <= 0; sectorIndex++) {
                            if (reportedBytes < ((offsets[sectorIndex] - rawStart) & DWORD_MASK)) {
                                break;
                            }

                            outputSize += sectorSize;
                        }
                    } else {
                        outputSize = (int) reportedBytes;
                    }
                }
            }

            int processedSectors = Integer.divideUnsigned(outputSize - 1 + sectorSize, sectorSize);

            if (!compressed) {
                this.copyStoredData(rawPosition, rawLength, firstSector, processedSectors, output, outputSize);

                return outputSize;
            }

            return this.decompressSectors(offsets, firstSector, processedSectors, output) ? outputSize : -1;
        }

        private int @Nullable [] loadSectorOffsets() {
            if (this.sectorOffsets != null) {
                return this.sectorOffsets;
            }

            // (sectorCount + 1) DWORDs at the block offset; a short read fails both passes.
            long tableLength = ((this.sectorCount & DWORD_MASK) + 1) * Integer.BYTES;

            if (this.filePosition + tableLength > MpqArchive.this.archiveData.length) {
                return null;
            }

            int[] offsets = new int[(int) (tableLength / Integer.BYTES)];

            for (int entryIndex = 0; entryIndex < offsets.length; entryIndex++) {
                offsets[entryIndex] = LittleEndianUtils.readInt32(MpqArchive.this.archiveData,
                                                                  (int) (this.filePosition + entryIndex * (long) Integer.BYTES));
            }

            if ((this.flags & MPQ_FILE_ENCRYPTED) != 0) {
                MpqCryptoUtils.decrypt(offsets, this.fileKey - 1);
            }

            for (int entryIndex = 0; entryIndex + 1 < offsets.length; entryIndex++) {
                // Not cached: every read retries the table and reads nothing.
                if (Integer.compareUnsigned(offsets[entryIndex], offsets[entryIndex + 1]) > 0) {
                    return new int[0];
                }
            }

            this.sectorOffsets = offsets;

            return offsets;
        }

        private void copyStoredData(long rawPosition, int rawLength, int firstSector, int processedSectors, byte @NotNull [] output, int outputSize) {
            byte[] rawData = copyZeroPadded(MpqArchive.this.archiveData, rawPosition, Math.min(rawLength, output.length));

            System.arraycopy(rawData, 0, output, 0, rawData.length);

            if ((this.flags & MPQ_FILE_ENCRYPTED) == 0) {
                return;
            }

            // Each sector decrypts min(sector size, remaining output), clamped by the partial last sector.
            int sectorSize = MpqArchive.this.sectorSize;
            int partialLastSector = this.fileSize & (sectorSize - 1);
            int decryptedBytes = 0;

            for (int sectorIndex = firstSector; processedSectors > 0 && Integer.compareUnsigned(sectorIndex, this.sectorCount) < 0;
                 sectorIndex++, processedSectors--) {
                int sectorLength = (int) Math.min(sectorSize & DWORD_MASK, (outputSize - decryptedBytes) & DWORD_MASK);

                if (sectorIndex == this.sectorCount - 1 && partialLastSector != 0) {
                    sectorLength = Math.min(sectorLength, partialLastSector);
                }

                int decryptLength = Math.min(sectorLength & -Integer.BYTES, (output.length - decryptedBytes) & -Integer.BYTES);
                byte[] sectorData = Arrays.copyOfRange(output, decryptedBytes, decryptedBytes + decryptLength);

                System.arraycopy(MpqCryptoUtils.decryptBytes(sectorData, this.fileKey + sectorIndex), 0, output, decryptedBytes, decryptLength);
                decryptedBytes += sectorLength;
            }
        }

        private boolean decompressSectors(int @NotNull [] offsets, int firstSector, int processedSectors, byte @NotNull [] output) {
            int sectorSize = MpqArchive.this.sectorSize;
            int compression = this.flags & COMPRESSION_FLAGS_MASK;
            int writtenBytes = 0;

            for (int sectorIndex = firstSector; processedSectors > 0 && Integer.compareUnsigned(sectorIndex, this.sectorCount) < 0;
                 sectorIndex++, processedSectors--) {
                int rawLength = offsets[sectorIndex + 1] - offsets[sectorIndex];
                int expectedLength = sectorSize;

                if (sectorIndex == this.sectorCount - 1) {
                    // A zero file size or a partial last sector uses the size modulo the sector size.
                    int partialLastSector = this.fileSize & (sectorSize - 1);

                    expectedLength = this.fileSize == 0 || partialLastSector != 0 ? partialLastSector : sectorSize;
                }

                int capacity = Math.min(expectedLength, output.length - writtenBytes);
                byte[] rawData = copyZeroPadded(MpqArchive.this.archiveData, this.filePosition + (offsets[sectorIndex] & DWORD_MASK),
                                                (int) Math.min(rawLength & DWORD_MASK, capacity));

                if ((this.flags & MPQ_FILE_ENCRYPTED) != 0) {
                    rawData = MpqCryptoUtils.decryptBytes(rawData, this.fileKey + sectorIndex);
                }

                if (Integer.compareUnsigned(expectedLength, rawLength) <= 0) {
                    // Stored sector: copy only the expected length.
                    System.arraycopy(rawData, 0, output, writtenBytes, capacity);
                } else if (compression == MPQ_FILE_IMPLODE) {
                    // The implode path repeats its input and writes without bound; output past the sector is discarded here.
                    try {
                        MpqExploderUtils.explodeInto(rawData, output, writtenBytes, capacity, true);
                    } catch (IOException exception) {
                        return false;
                    }
                } else if (compression == MPQ_FILE_COMPRESS) {
                    byte[] sectorOutput = new byte[expectedLength];
                    int decompressedLength = MpqDecompressorUtils.decompress(rawData, sectorOutput, expectedLength);

                    if (decompressedLength == MpqDecompressorUtils.FAILED) {
                        return false;
                    }

                    System.arraycopy(sectorOutput, 0, output, writtenBytes, Math.min(decompressedLength, capacity));
                } else {
                    // Any other compression flag combination fails the sector.
                    return false;
                }

                writtenBytes += expectedLength;
            }

            return true;
        }
    }


    /**
     * Returns the locale for {@code enUS}, {@code deDE}, {@code esES}, {@code frFR} or {@code itIT}, defaulting to English.
     * Language codes are compared case-sensitively.
     *
     * @param gameLanguage the game language code, for example {@code koKR}
     * @return the locale identifier
     */
    public static int toStormLocale(@NotNull String gameLanguage) {
        if (gameLanguage.length() != GAME_LANGUAGE_LENGTH) {
            return ENGLISH_LOCALE;
        }

        String language = gameLanguage.substring(0, LANGUAGE_LENGTH);
        String region = gameLanguage.substring(LANGUAGE_LENGTH);

        return switch (language) {
            case "de" -> GERMAN_LOCALE;
            case "es" -> region.equals("MX") ? ENGLISH_LOCALE : SPANISH_LOCALE;
            case "fr" -> FRENCH_LOCALE;
            case "it" -> ITALIAN_LOCALE;
            default -> ENGLISH_LOCALE;
        };
    }

    /**
     * @return the configured lookup locale
     */
    public static int getLocale() {
        return locale;
    }

    /**
     * Sets the locale of every later lookup.
     *
     * @param stormLocale the locale identifier, usually from {@link #toStormLocale}
     */
    public static void setLocale(int stormLocale) {
        locale = stormLocale & LOCALE_MASK;
    }

    /**
     * Creates a {@link MpqArchive} instance from the given MPQ map archive.
     *
     * @param archiveFile the path to the archive file
     * @return the created {@link MpqArchive} instance
     * @throws IOException if the file cannot be read, no acceptable header is found or the {@code (attributes)} file fails validation
     */
    public static @NotNull MpqArchive open(@NotNull Path archiveFile) throws IOException {
        return open(archiveFile, false);
    }

    /**
     * Creates a {@link MpqArchive} instance from an archive, optionally in map compatibility mode.
     *
     * @param archiveFile   the path to the archive file
     * @param compatibility whether an {@code (attributes)} file that fails validation is ignored (logged) instead
     * @return the created {@link MpqArchive} instance
     * @throws IOException if the file cannot be read or the reader finds no acceptable header or rejects the {@code (attributes)} file
     */
    public static @NotNull MpqArchive open(@NotNull Path archiveFile, boolean compatibility) throws IOException {
        return open(Files.readAllBytes(archiveFile), archiveFile.toString(), compatibility);
    }

    /**
     * Creates a {@link MpqArchive} instance from the given MPQ map archive content.
     *
     * @param archiveData the whole archive file content
     * @param description the archive name used in messages
     * @return the created {@link MpqArchive} instance
     * @throws IOException if no acceptable header is found or the {@code (attributes)} file fails validation
     */
    public static @NotNull MpqArchive open(byte @NotNull [] archiveData, @NotNull String description) throws IOException {
        return open(archiveData, description, false);
    }

    /**
     * Creates a {@link MpqArchive} instance from archive content, optionally in map compatibility mode.
     *
     * @param archiveData   the whole archive file content
     * @param description   the archive name used in messages
     * @param compatibility whether an {@code (attributes)} file that fails validation is ignored (logged) instead
     * @return the created {@link MpqArchive} instance
     * @throws IOException if the reader finds no acceptable header or rejects the {@code (attributes)} file
     */
    public static @NotNull MpqArchive open(byte @NotNull [] archiveData, @NotNull String description, boolean compatibility) throws IOException {
        long headerOffset = findHeaderOffset(archiveData, description);
        byte[] header = copyZeroPadded(archiveData, headerOffset, HEADER_COPY_SIZE);

        if (headerOffset + HEADER_COPY_SIZE > archiveData.length) {
            LOGGER.debug(() -> "MPQ compatibility: header bytes past the file end read as zero (the original reads stale buffer " +
                               "bytes): " + description);
        }

        int sectorShift = header[SECTOR_SHIFT_OFFSET] & SECTOR_SHIFT_MASK;
        int hashTablePosition = LittleEndianUtils.readInt32(header, HASH_TABLE_POSITION_OFFSET);
        int blockTablePosition = LittleEndianUtils.readInt32(header, BLOCK_TABLE_POSITION_OFFSET);
        int headerHashTableSize = LittleEndianUtils.readInt32(header, HASH_TABLE_SIZE_OFFSET);
        int hashTableEntryCount = headerHashTableSize & MAP_INDEX_MASK;
        int blockTableEntryCount = LittleEndianUtils.readInt32(header, BLOCK_TABLE_SIZE_OFFSET);
        int[] hashEntries = readTable(archiveData, headerOffset + hashTablePosition, hashTableEntryCount, "hash", description);

        MpqCryptoUtils.decrypt(hashEntries, MpqCryptoUtils.hashKey(HASH_TABLE_KEY_NAME));

        for (int entryIndex = 0; entryIndex < hashTableEntryCount; entryIndex++) {
            hashEntries[entryIndex * TABLE_ENTRY_VALUE_COUNT + HASH_LOCALE_VALUE] &= HASH_ENTRY_LOCALE_MASK;
        }

        int[] blockEntries = readTable(archiveData, headerOffset + blockTablePosition, blockTableEntryCount, "block", description);

        MpqCryptoUtils.decrypt(blockEntries, MpqCryptoUtils.hashKey(BLOCK_TABLE_KEY_NAME));

        for (int blockIndex = 0; blockIndex < blockTableEntryCount; blockIndex++) {
            blockEntries[blockIndex * TABLE_ENTRY_VALUE_COUNT + BLOCK_FLAGS_VALUE] &= ~MAP_CLEARED_BLOCK_FLAGS;
        }

        MpqArchive archive = new MpqArchive(archiveData, description, headerOffset,
                                            BASE_SECTOR_SIZE << sectorShift,
                                            hashEntries, hashTableEntryCount, headerHashTableSize,
                                            blockEntries, blockTableEntryCount);

        try {
            archive.validateAttributes();
        } catch (IOException exception) {
            if (!compatibility) {
                throw exception;
            }

            LOGGER.info("MAP COMPATIBILITY: relaxed rule \"(attributes) must fit the block table\"; ignored: " + exception.getMessage());
        }

        return archive;
    }

    private static long findHeaderOffset(byte @NotNull [] archiveData, @NotNull String description) throws IOException {
        long bufferStart = 0;
        long bufferLength = 0;

        // Refill a 4 KiB buffer whenever the candidate leaves it; only a refill checks the file end.
        for (long headerOffset = 0; ; headerOffset += HEADER_SEARCH_STEP) {
            if (headerOffset >= bufferStart + bufferLength) {
                bufferStart  = headerOffset;
                bufferLength = Math.clamp(archiveData.length - headerOffset, 0, HEADER_BUFFER_SIZE);

                if (bufferLength < MINIMUM_HEADER_SIZE || headerOffset + MINIMUM_HEADER_SIZE >= archiveData.length) {
                    throw Exceptions.newIOException("The file holds no acceptable MPQ header: {0}", description);
                }
            }

            byte[] header = copyZeroPadded(archiveData, headerOffset, MINIMUM_HEADER_SIZE);
            int headerHashTableSize = LittleEndianUtils.readInt32(header, HASH_TABLE_SIZE_OFFSET) & MAP_INDEX_MASK;

            if (LittleEndianUtils.readInt32(header, 0) == MPQ_MAGIC &&
                Integer.compareUnsigned(LittleEndianUtils.readInt32(header, HEADER_SIZE_OFFSET), MINIMUM_HEADER_SIZE) >= 0 &&
                headerHashTableSize <= MAXIMUM_TABLE_ENTRY_COUNT &&
                Integer.compareUnsigned(LittleEndianUtils.readInt32(header, BLOCK_TABLE_SIZE_OFFSET), MAXIMUM_TABLE_ENTRY_COUNT) <= 0) {
                return headerOffset;
            }
        }
    }

    private static int @NotNull [] readTable(byte @NotNull [] archiveData, long tablePosition, int entryCount,
                                             @NotNull String tableName, @NotNull String description) {
        // The table reads into a zero-filled allocation: the map flag ignores a failed or short read. A negative position
        // fails the seek, so nothing is read; a position past the end reads nothing; a table crossing the end reads its head.
        int byteCount = entryCount * TABLE_ENTRY_VALUE_COUNT * Integer.BYTES;
        byte[] tableData = tablePosition < 0 ? new byte[byteCount] : copyZeroPadded(archiveData, tablePosition, byteCount);

        if (tablePosition < 0 || tablePosition + byteCount > archiveData.length) {
            LOGGER.debug(() -> "MPQ " + tableName + " table at " + tablePosition + " is not fully inside the file; unread entries " +
                               "stay zero before decryption: " + description);
        }

        int[] entries = new int[entryCount * TABLE_ENTRY_VALUE_COUNT];

        for (int valueIndex = 0; valueIndex < entries.length; valueIndex++) {
            entries[valueIndex] = LittleEndianUtils.readInt32(tableData, valueIndex * Integer.BYTES);
        }

        return entries;
    }

    private static byte @NotNull [] copyZeroPadded(byte @NotNull [] archiveData, long position, int length) {
        byte[] copy = new byte[length];

        if (position < archiveData.length && position + length > 0) {
            long copyStart = Math.max(position, 0);
            long copyEnd = Math.min(position + length, archiveData.length);

            System.arraycopy(archiveData, (int) copyStart, copy, (int) (copyStart - position), (int) (copyEnd - copyStart));
        }

        return copy;
    }


    private final byte @NotNull [] archiveData;
    private final @NotNull String description;
    private final long headerOffset;

    private final int sectorSize;

    private final int @NotNull [] hashEntries;
    private final int hashTableEntryCount;
    private final int headerHashTableSize;

    private final int @NotNull [] blockEntries;
    private final int blockTableEntryCount;

    private @Nullable List<MpqEntry> entries;

    /**
     * Creates a {@link MpqArchive} instance.
     *
     * @param archiveData          the whole archive content
     * @param description          the archive name used in messages
     * @param headerOffset         the file offset of the accepted header
     * @param sectorSize           the unsigned sector size; zero when the shift overflows {@code 32} bits
     * @param hashEntries          the decrypted hash table
     * @param hashTableEntryCount  the number of hash table entries
     * @param headerHashTableSize  the unmasked header hash table size, which the lookup mask uses
     * @param blockEntries         the decrypted block table with the map-mode flag changes
     * @param blockTableEntryCount the number of block table entries
     */
    private MpqArchive(byte @NotNull [] archiveData, @NotNull String description, long headerOffset,
                       int sectorSize,
                       int @NotNull [] hashEntries, int hashTableEntryCount, int headerHashTableSize,
                       int @NotNull [] blockEntries, int blockTableEntryCount) {
        this.archiveData  = archiveData;
        this.description  = description;
        this.headerOffset = headerOffset;

        this.sectorSize = sectorSize;

        this.hashEntries         = hashEntries;
        this.hashTableEntryCount = hashTableEntryCount;
        this.headerHashTableSize = headerHashTableSize;

        this.blockEntries         = blockEntries;
        this.blockTableEntryCount = blockTableEntryCount;

        this.entries = null;
    }

    /**
     * @return the whole archive content the archive reads its files from; shared, not copied, and never changed by the archive
     */
    public byte @NotNull [] getArchiveData() {
        return this.archiveData;
    }

    /**
     * Returns existing block entries, resolving their names from a readable listfile on the first call.
     *
     * @return the file entries; names are resolved only when the archive holds a readable {@code (listfile)}
     */
    public @NotNull List<MpqEntry> getEntries() {
        if (this.entries == null) {
            this.entries = this.resolveEntries();
        }

        return Collections.unmodifiableList(this.entries);
    }

    /**
     * @return the file offset of the header that the reader accepts
     */
    public long getHeaderOffset() {
        return this.headerOffset;
    }

    private long getFilePosition(int blockIndex) {
        long storedPosition = this.blockEntries[blockIndex * TABLE_ENTRY_VALUE_COUNT + BLOCK_POSITION_VALUE] & DWORD_MASK;

        // Only a nonzero block offset is relocated by the header offset.
        return storedPosition == 0 ? 0 : storedPosition + this.headerOffset;
    }


    /**
     * Finds the entry of the given file name with the lookup.
     *
     * @param fileName the file name; both slash directions and any letter case are accepted
     * @return the matching entry, or {@code null} if the lookup does not open the file
     */
    public @Nullable MpqEntry findEntry(@NotNull String fileName) {
        return this.findEntry(MpqCryptoUtils.toNameBytes(fileName));
    }

    /**
     * Finds the entry of the given raw file name bytes with the lookup, for names taken from map strings.
     *
     * @param fileName the raw file name bytes (CP949 or Windows-1252 as stored in the map), without a terminator
     * @return the matching entry, or {@code null} if the lookup does not open the file
     */
    public @Nullable MpqEntry findEntry(byte @NotNull [] fileName) {
        int blockIndex = this.findBlockIndex(fileName);

        if (blockIndex < 0) {
            return null;
        }

        return this.createEntry(blockIndex, new String(fileName, StandardCharsets.ISO_8859_1));
    }

    /**
     * Reads the file content with the block's declared file size.
     * Missing bytes after a failed or short sector read are zero-filled.
     *
     * @param fileName the file name; both slash directions and any letter case are accepted
     * @return the file content
     * @throws IOException if the lookup does not open the file, the file exceeds the accepted size limit or the sector size is zero
     */
    public byte @NotNull [] readFile(@NotNull String fileName) throws IOException {
        return this.readFile(MpqCryptoUtils.toNameBytes(fileName));
    }

    /**
     * Reads the content of the given raw file name bytes, for names taken from map strings such as Play WAV paths.
     *
     * @param fileName the raw file name bytes (CP949 or Windows-1252 as stored in the map), without a terminator
     * @return the file content
     * @throws IOException if the lookup does not open the file, the file exceeds the accepted size limit or the sector size is zero
     */
    public byte @NotNull [] readFile(byte @NotNull [] fileName) throws IOException {
        int blockIndex = this.findBlockIndex(fileName);

        if (blockIndex < 0) {
            throw Exceptions.newIOException("The file does not exist in the archive: {0}", new String(fileName, StandardCharsets.ISO_8859_1));
        }

        return this.readBlock(blockIndex, fileName);
    }

    private void validateAttributes() throws IOException {
        // An (attributes) file that the lookup opens must match the block count, or the archive open fails. Its CRC and
        // MD5 values are never checked afterwards: the verification options are not enabled by the game.
        int blockIndex = this.findBlockIndex(MpqCryptoUtils.toNameBytes(ATTRIBUTES_NAME));

        if (blockIndex < 0) {
            return;
        }

        long fileSize = this.blockEntries[blockIndex * TABLE_ENTRY_VALUE_COUNT + BLOCK_FILE_SIZE_VALUE] & DWORD_MASK;

        if (fileSize > ((long) this.blockTableEntryCount << ATTRIBUTES_ENTRY_SHIFT) + ATTRIBUTES_HEADER_SIZE) {
            throw Exceptions.newIOException("The (attributes) file exceeds the original size bound: {0}", this.description);
        }

        byte[] attributes = this.readBlock(blockIndex, MpqCryptoUtils.toNameBytes(ATTRIBUTES_NAME));

        if (fileSize < ATTRIBUTES_HEADER_SIZE) {
            throw Exceptions.newIOException("The (attributes) file is shorter than its header: {0}", this.description);
        }

        int attributeFlags = LittleEndianUtils.readInt32(attributes, ATTRIBUTES_FLAGS_OFFSET);
        long entrySize = ((attributeFlags & ATTRIBUTE_CRC32) != 0 ? ATTRIBUTE_CRC32_SIZE : 0) +
                         ((attributeFlags & ATTRIBUTE_FILE_TIME) != 0 ? ATTRIBUTE_FILE_TIME_SIZE : 0) +
                         ((attributeFlags & ATTRIBUTE_MD5) != 0 ? ATTRIBUTE_MD5_SIZE : 0);
        long expectedSize = (entrySize * this.blockTableEntryCount + ATTRIBUTES_HEADER_SIZE) & DWORD_MASK;
        boolean unknownFlags = (attributeFlags & ~ATTRIBUTE_KNOWN_FLAGS) != 0;
        boolean accepted = fileSize == expectedSize ||
                           fileSize > expectedSize && unknownFlags ||
                           fileSize < expectedSize && !unknownFlags && (fileSize - ATTRIBUTES_HEADER_SIZE) % entrySize == 0;

        if (!accepted) {
            throw Exceptions.newIOException("The (attributes) file size does not match its flags and the block table: {0}", this.description);
        }
    }

    private @NotNull List<MpqEntry> resolveEntries() {
        List<MpqEntry> resolvedEntries = new ArrayList<>();
        List<Integer> blockIndexes = new ArrayList<>();

        for (int blockIndex = 0; blockIndex < this.blockTableEntryCount; blockIndex++) {
            if ((this.blockEntries[blockIndex * TABLE_ENTRY_VALUE_COUNT + BLOCK_FLAGS_VALUE] & MPQ_FILE_EXISTS) == 0) {
                continue;
            }

            resolvedEntries.add(this.createEntry(blockIndex, null));
            blockIndexes.add(blockIndex);
        }

        int listfileBlockIndex = this.findBlockIndex(MpqCryptoUtils.toNameBytes(LISTFILE_NAME));

        if (listfileBlockIndex < 0) {
            return resolvedEntries;
        }

        try {
            byte[] listfileContent = this.readBlock(listfileBlockIndex, MpqCryptoUtils.toNameBytes(LISTFILE_NAME));

            for (String fileName : new String(listfileContent, StandardCharsets.ISO_8859_1).split("[\\r\\n;]+")) {
                int blockIndex = fileName.isEmpty() ? -1 : this.findBlockIndex(MpqCryptoUtils.toNameBytes(fileName));
                int entryIndex = blockIndexes.indexOf(blockIndex);

                if (entryIndex >= 0) {
                    resolvedEntries.set(entryIndex, this.createEntry(blockIndex, fileName));
                }
            }
        } catch (IOException exception) {
            LOGGER.debug(() -> "MPQ (listfile) is unreadable; entries stay unnamed: " + this.description);
        }

        return resolvedEntries;
    }

    private @NotNull MpqEntry createEntry(int blockIndex, @Nullable String fileName) {
        int entryOffset = blockIndex * TABLE_ENTRY_VALUE_COUNT;

        return new MpqEntry(fileName, this.getFilePosition(blockIndex),
                            this.blockEntries[entryOffset + BLOCK_COMPRESSED_SIZE_VALUE] & DWORD_MASK,
                            this.blockEntries[entryOffset + BLOCK_FILE_SIZE_VALUE] & DWORD_MASK,
                            this.blockEntries[entryOffset + BLOCK_FLAGS_VALUE]);
    }

    private int findBlockIndex(byte @NotNull [] fileName) {
        int hashIndex = this.findHashIndex(fileName);

        if (hashIndex < 0) {
            return -1;
        }

        // The lookup masks the block index and requires the exists flag of the selected block.
        int blockIndex = this.hashEntries[hashIndex * TABLE_ENTRY_VALUE_COUNT + HASH_BLOCK_INDEX_VALUE] & MAP_INDEX_MASK;

        if (blockIndex >= this.blockTableEntryCount ||
            (this.blockEntries[blockIndex * TABLE_ENTRY_VALUE_COUNT + BLOCK_FLAGS_VALUE] & MPQ_FILE_EXISTS) == 0) {
            return -1;
        }

        return blockIndex;
    }

    private int findHashIndex(byte @NotNull [] fileName) {
        // The mask comes from the unmasked header size, the entry index from the masked one.
        int indexMask = this.headerHashTableSize - 1;
        int startIndex = MpqCryptoUtils.hashTableOffset(fileName) & indexMask;
        int nameHashA = MpqCryptoUtils.hashNameA(fileName);
        int nameHashB = MpqCryptoUtils.hashNameB(fileName);
        int stormLocale = locale;
        int candidateIndex = -1;
        int probeIndex = startIndex;

        do {
            int entryIndex = probeIndex & MAP_INDEX_MASK;

            // An entry past the table ends the lookup without a result, even after a candidate.
            if (Integer.compareUnsigned(entryIndex, this.hashTableEntryCount) >= 0) {
                return -1;
            }

            int entryOffset = entryIndex * TABLE_ENTRY_VALUE_COUNT;
            int blockIndex = this.hashEntries[entryOffset + HASH_BLOCK_INDEX_VALUE];

            if (blockIndex == EMPTY_BLOCK_INDEX) {
                break;
            }

            boolean nameMatches = this.hashEntries[entryOffset + HASH_NAME_A_VALUE] == nameHashA &&
                                  this.hashEntries[entryOffset + HASH_NAME_B_VALUE] == nameHashB;

            if (nameMatches && blockIndex != DELETED_BLOCK_INDEX) {
                int entryLocale = this.hashEntries[entryOffset + HASH_LOCALE_VALUE] & LOCALE_MASK;
                int entryPlatform = (this.hashEntries[entryOffset + HASH_LOCALE_VALUE] >>> PLATFORM_SHIFT) & PLATFORM_MASK;

                // The configured locale and neutral platform return at once; a neutral locale with the neutral platform becomes the
                // candidate, and the last one wins.
                if (entryPlatform == NEUTRAL_PLATFORM) {
                    if (entryLocale == stormLocale) {
                        return entryIndex;
                    }

                    if (entryLocale == NEUTRAL_LOCALE) {
                        candidateIndex = entryIndex;
                    }
                }
            }

            probeIndex = (probeIndex + 1) & indexMask;
        } while (probeIndex != startIndex);

        return candidateIndex;
    }

    private byte @NotNull [] readBlock(int blockIndex, byte @NotNull [] rawFileName) throws IOException {
        String fileName = new String(rawFileName, StandardCharsets.ISO_8859_1);
        int entryOffset = blockIndex * TABLE_ENTRY_VALUE_COUNT;
        int fileSize = this.blockEntries[entryOffset + BLOCK_FILE_SIZE_VALUE];
        int flags = this.blockEntries[entryOffset + BLOCK_FLAGS_VALUE];

        if (fileName.equalsIgnoreCase(ChkFile.ARCHIVE_ENTRY) && Integer.compareUnsigned(fileSize, ScrDataExtensionUtils.getMaximumScenarioSize()) > 0) {
            throw Exceptions.newIOException("The scenario size 0x{0} exceeds the map list limit: {1}", Integer.toHexString(fileSize), fileName);
        }

        if (Integer.compareUnsigned(fileSize, MAXIMUM_FILE_SIZE) > 0) {
            throw Exceptions.newIOException("The file size 0x{0} exceeds the largest readable file: {1}", Integer.toHexString(fileSize), fileName);
        }

        if (this.sectorSize == 0) {
            throw Exceptions.newIOException("The sector size shift overflows; the original reader divides by zero: {0}", this.description);
        }

        SectorReader reader = new SectorReader(blockIndex, rawFileName, fileSize, flags);
        byte[] content = new byte[fileSize];

        // The whole sectors go straight to the output, the last partial sector through a sector buffer.
        int wholeSectorBytes = fileSize & -this.sectorSize;
        int readCount = reader.read(0, wholeSectorBytes, content);

        if (readCount == wholeSectorBytes && wholeSectorBytes != fileSize) {
            int remainingBytes = fileSize - wholeSectorBytes;
            // The partial read requests a whole sector for compressed files and the remaining bytes otherwise.
            int requestSize = (flags & COMPRESSION_FLAGS_MASK) != 0 ? this.sectorSize : remainingBytes;
            byte[] sectorBuffer = new byte[remainingBytes];
            int sectorCount = reader.read(wholeSectorBytes, requestSize, sectorBuffer);

            System.arraycopy(sectorBuffer, 0, content, wholeSectorBytes, remainingBytes);
            readCount += Math.min(sectorCount, remainingBytes);
        }

        if (readCount != fileSize) {
            int finalReadCount = readCount;

            LOGGER.debug(() -> "MPQ file " + fileName + " read " + Integer.toUnsignedString(finalReadCount) + " of " +
                               Integer.toUnsignedString(fileSize) + " bytes; the unread tail is zero (uninitialized in the " +
                               "original): " + this.description);
        }

        return content;
    }

    private int computeFileKey(int blockIndex, byte @NotNull [] fileName, int fileSize, int flags) {
        // The plain name starts after the last slash byte of either direction, even a CP949 trail byte.
        int plainNameOffset = 0;

        for (int byteIndex = 0; byteIndex < fileName.length; byteIndex++) {
            if (fileName[byteIndex] == '\\' || fileName[byteIndex] == '/') {
                plainNameOffset = byteIndex + 1;
            }
        }

        int fileKey = MpqCryptoUtils.hashKey(Arrays.copyOfRange(fileName, plainNameOffset, fileName.length));

        // The key adds the relocated block offset minus the header offset, in 32 bits.
        if ((flags & MPQ_FILE_FIX_KEY) != 0) {
            fileKey = (fileKey + (int) (this.getFilePosition(blockIndex) - this.headerOffset)) ^ fileSize;
        }

        return fileKey;
    }
}
