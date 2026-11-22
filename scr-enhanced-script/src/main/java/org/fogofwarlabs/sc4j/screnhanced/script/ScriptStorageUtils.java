package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.zip.Deflater;

/**
 * Reads and writes the script trailer of a map file: the script package stored after the bytes of the map, which stay as they are.
 * <p>
 * The trailer follows the last byte of the map, all little endian:
 * {@snippet lang = "text":
 * padding    paddingSize zero bytes
 * data       the stored bytes of each file, in directory order
 * directory  fileCount u32, then per file: nameSize u16, name (UTF-8), storedSize u32, originalSize u32, digest (SHA-256 of the original bytes, 32 bytes)
 * footer     version u16 = 1, flags u16, paddingSize u32, directorySize u32, trailerSize u64, magic (8 bytes: "SCES" 1A 0D 0A 00)
 *}
 * {@code trailerSize} counts every trailer byte from the padding to the magic, so the map ends {@code trailerSize} bytes before the end of the
 * file. A file whose last eight bytes are not the magic has no trailer. The names are those of a script folder ({@link ScriptPackage}):
 * {@value ScriptManifest#FILE_NAME}, the scripts, and the resources under {@code resources/}; the writer stores them in this order. The stored
 * bytes are the original bytes when {@code storedSize} equals {@code originalSize}, else a zlib stream of them; a file is stored as it is when its
 * zlib stream is not smaller.
 * <p>
 * The padding keeps every MPQ range that the map reader reads past the end of the map, the header, the hash table and the block table, read as
 * zeros as before, and the trailer outside the archive size of the header; it is empty for a map whose archive ends at its last byte. An archive
 * size more than {@value #MAXIMUM_PADDING} bytes past the end of the map gets no padding.
 * <p>
 * A replay section ({@value #REPLAY_SECTION}) holds a trailer with the flag {@value #REFERENCED_RESOURCES_FLAG}, no padding and the resources
 * as references: their names, original sizes and digests, without stored bytes ({@code storedSize} {@code 0}).
 * <p>
 * The flag {@value #PROTECTED_FLAG} marks a protected map, whose scripts were packed in protected form ({@link ScriptProtector}); the replay
 * section of its games keeps the flag. The tool does not unpack a protected map.
 */
public final class ScriptStorageUtils {
    /**
     * The tag of the replay section that holds the script package of a game of SC:R Enhanced, with its resources as references.
     */
    public static final @NotNull String REPLAY_SECTION = "SCES";
    /**
     * The format version of the trailer.
     */
    public static final int FORMAT_VERSION = 1;
    /**
     * The size of the footer.
     */
    public static final int FOOTER_SIZE = 28;
    /**
     * The flag of a trailer whose resources are references without stored bytes.
     */
    public static final int REFERENCED_RESOURCES_FLAG = 0x1;
    /**
     * The flag of a trailer whose scripts are protected: a map trailer or a replay section may have it.
     */
    public static final int PROTECTED_FLAG = 0x2;
    /**
     * The largest padding before a trailer: the MPQ ranges of a map may reach this many bytes past its end.
     */
    public static final int MAXIMUM_PADDING = 0x1000000;

    private static final byte @NotNull [] MAGIC = {'S', 'C', 'E', 'S', 0x1A, '\r', '\n', 0};
    private static final int RECORD_FIXED_SIZE = Short.BYTES + Integer.BYTES + Integer.BYTES + ScriptResources.DIGEST_SIZE;
    private static final int MAXIMUM_NAME_SIZE = 0xFFFF;
    private static final @NotNull String RESOURCE_PREFIX = ScriptPackage.RESOURCE_FOLDER + "/";
    private static final @NotNull String REPLAY_DESCRIPTION = "script replay section";

    /**
     * The MPQ header fields from the header offset, as the map reader reads them.
     */
    private static final int MPQ_ARCHIVE_SIZE_OFFSET = 8;
    private static final int MPQ_HASH_TABLE_POSITION_OFFSET = 16;
    private static final int MPQ_BLOCK_TABLE_POSITION_OFFSET = 20;
    private static final int MPQ_HASH_TABLE_SIZE_OFFSET = 24;
    private static final int MPQ_BLOCK_TABLE_SIZE_OFFSET = 28;
    /**
     * The header bytes the map reader copies from the header offset.
     */
    private static final int MPQ_HEADER_COPY_SIZE = 0x2C;
    private static final int MPQ_TABLE_ENTRY_SIZE = 16;
    private static final int MPQ_HASH_TABLE_SIZE_MASK = 0x0FFFFFFF;

    /**
     * The footer of a trailer.
     */
    private static final class Footer {
        private final int flags;
        private final int paddingSize;
        private final int directorySize;
        private final long trailerSize;

        private Footer(int flags, int paddingSize, int directorySize, long trailerSize) {
            this.flags         = flags;
            this.paddingSize   = paddingSize;
            this.directorySize = directorySize;
            this.trailerSize   = trailerSize;
        }

        /**
         * @return the size of the data, between the padding and the directory
         */
        private long getDataSize() {
            return this.trailerSize - FOOTER_SIZE - this.directorySize - this.paddingSize;
        }
    }

    /**
     * A file to store: its name in the script folder, its stored bytes and its original size and digest.
     */
    private static final class StoredFile {
        private final byte @NotNull [] nameBytes;
        private final byte @Nullable [] data;
        private final int originalSize;
        private final byte @NotNull [] digest;

        private StoredFile(@NotNull String name, byte @Nullable [] data, int originalSize, byte @NotNull [] digest) throws IOException {
            byte[] encodedName = name.getBytes(StandardCharsets.UTF_8);

            if (encodedName.length > MAXIMUM_NAME_SIZE) {
                throw Exceptions.newIOException("The file name {0} exceeds {1} UTF-8 bytes.", name, MAXIMUM_NAME_SIZE);
            }

            this.nameBytes    = encodedName;
            this.data         = data;
            this.originalSize = originalSize;
            this.digest       = digest;
        }

        private int getStoredSize() {
            return this.data == null ? 0 : this.data.length;
        }
    }


    /**
     * Reads the script trailer of a map file; the manifest and the scripts are read and checked, the resources only indexed.
     *
     * @param mapFile     the whole map file, kept by the returned resources and not to be changed
     * @param description the map name used in messages
     * @return the trailer, or {@code null} if the file does not end with the trailer magic
     * @throws IOException if the file ends with the trailer magic but the trailer is not valid, or the package it holds is not
     */
    public static @Nullable ScriptTrailer readTrailer(byte @NotNull [] mapFile, @NotNull String description) throws IOException {
        String trailerDescription = "script trailer of " + description;
        Footer footer = readFooter(mapFile, trailerDescription);

        if (footer == null) {
            return null;
        }

        if ((footer.flags & ~PROTECTED_FLAG) != 0) {
            throw Exceptions.newIOException("The {0} has the flags {1}; a map trailer has none but {2}.", trailerDescription, footer.flags,
                                            PROTECTED_FLAG);
        }

        int trailerStart = (int) (mapFile.length - footer.trailerSize);

        for (int index = trailerStart; index < trailerStart + footer.paddingSize; index++) {
            if (mapFile[index] != 0) {
                throw Exceptions.newIOException("The {0} holds a nonzero padding byte at offset {1}.", trailerDescription, index);
            }
        }

        return readContents(mapFile, footer, trailerStart + footer.paddingSize, trailerDescription);
    }

    /**
     * Reads the whole script package of a map file, every resource included.
     *
     * @param mapFile     the whole map file
     * @param description the map name used in messages
     * @return the script package, or {@code null} if the file has no trailer
     * @throws IOException if the trailer, the package or a resource is not valid
     */
    public static @Nullable ScriptPackage readPackage(byte @NotNull [] mapFile, @NotNull String description) throws IOException {
        ScriptTrailer trailer = readTrailer(mapFile, description);

        return trailer == null ? null : trailer.createPackage();
    }

    /**
     * Reads the resources of the script trailer of a map file without reading the stored bytes: only the footer and the directory.
     *
     * @param mapFile the map file
     * @return the resources as references without stored bytes, or {@code null} if the file does not end with the trailer magic
     * @throws IOException if the file cannot be read or its trailer is not valid
     */
    public static @Nullable ScriptResources readResourceIndex(@NotNull Path mapFile) throws IOException {
        String description = "script trailer of " + mapFile;

        try (FileChannel channel = FileChannel.open(mapFile, StandardOpenOption.READ)) {
            long fileSize = channel.size();

            if (fileSize < FOOTER_SIZE) {
                return null;
            }

            Footer footer = readFooter(readBytes(channel, fileSize - FOOTER_SIZE, FOOTER_SIZE), fileSize, description);

            if (footer == null) {
                return null;
            }

            if ((footer.flags & ~PROTECTED_FLAG) != 0) {
                throw Exceptions.newIOException("The {0} has the flags {1}; a map trailer has none but {2}.", description, footer.flags,
                                                PROTECTED_FLAG);
            }

            ByteBuffer directory = readBytes(channel, fileSize - FOOTER_SIZE - footer.directorySize, footer.directorySize);
            List<ScriptResources.Entry> entries = readDirectory(directory, footer.getDataSize(), ScriptResources.NO_OFFSET, false, description);

            return new ScriptResources(new byte[0], splitResources(entries, description));
        }
    }

    /**
     * Finds the end of the map in a map file: the start of its trailer, or the end of the file.
     *
     * @param mapFile     the whole map file
     * @param description the map name used in messages
     * @return the size of the map without the trailer
     * @throws IOException if the file ends with the trailer magic but the trailer is not valid
     */
    public static int findMapEnd(byte @NotNull [] mapFile, @NotNull String description) throws IOException {
        Footer footer = readFooter(mapFile, "script trailer of " + description);

        if (footer == null) {
            return mapFile.length;
        }

        readTrailer(mapFile, description);

        return (int) (mapFile.length - footer.trailerSize);
    }

    /**
     * Writes a script package into a map file that is not protected: the map without its old trailer, then the padding its MPQ ranges need and
     * the new trailer. An MPQ table may reach at most {@value #MAXIMUM_PADDING} bytes past the end of the map, a name at most {@code 65535}
     * UTF-8 bytes and the new file at most {@link MpqArchive#MAXIMUM_FILE_SIZE} bytes.
     *
     * @param mapFile       the whole map file, not modified
     * @param description   the map name used in messages
     * @param scriptPackage the script package
     * @return the new map file
     * @throws IOException if the old trailer is not valid, the map holds no MPQ archive the map reader opens, or a size limit is exceeded
     */
    public static byte @NotNull [] writePackage(byte @NotNull [] mapFile, @NotNull String description,
                                                @NotNull ScriptPackage scriptPackage) throws IOException {
        return writePackage(mapFile, description, scriptPackage, false);
    }

    /**
     * Writes a script package into a map file: the map without its old trailer, then the padding its MPQ ranges need and the new trailer, with
     * the flag {@value #PROTECTED_FLAG} for a protected map. An MPQ table may reach at most {@value #MAXIMUM_PADDING} bytes past the end of the
     * map, a name at most {@code 65535} UTF-8 bytes and the new file at most {@link MpqArchive#MAXIMUM_FILE_SIZE} bytes.
     *
     * @param mapFile       the whole map file, not modified
     * @param description   the map name used in messages
     * @param scriptPackage the script package, with protected scripts for a protected map
     * @param protectedMap  whether the map is protected
     * @return the new map file
     * @throws IOException if the old trailer is not valid, the map holds no MPQ archive the map reader opens, or a size limit is exceeded
     */
    public static byte @NotNull [] writePackage(byte @NotNull [] mapFile, @NotNull String description, @NotNull ScriptPackage scriptPackage,
                                                boolean protectedMap) throws IOException {
        byte[] map = Arrays.copyOf(mapFile, findMapEnd(mapFile, description));
        long headerOffset = MpqArchive.open(map, description, true).getHeaderOffset();
        List<StoredFile> files = storePackage(scriptPackage);
        long bodySize = getBodySize(files);
        int paddingSize = findPaddingSize(map, headerOffset, bodySize, description);
        long fileSize = map.length + paddingSize + bodySize;

        if (fileSize > MpqArchive.MAXIMUM_FILE_SIZE) {
            throw Exceptions.newIOException("The map {0} with its script package has {1} bytes, more than the {2} bytes a map file may hold.",
                                            description, fileSize, MpqArchive.MAXIMUM_FILE_SIZE);
        }

        ByteBuffer newFile = ByteBuffer.allocate((int) fileSize).order(ByteOrder.LITTLE_ENDIAN);

        newFile.put(map);
        writeTrailer(newFile, files, paddingSize, protectedMap ? PROTECTED_FLAG : 0);

        return newFile.array();
    }

    /**
     * Creates the replay section of a script trailer: the manifest and the scripts with their bytes, as the trailer holds them, the resources as
     * references, and the flag {@value #PROTECTED_FLAG} of a protected trailer.
     *
     * @param trailer the trailer of the game's map
     * @return the replay section
     * @throws IOException if a name exceeds {@code 65535} UTF-8 bytes
     */
    public static byte @NotNull [] createReplaySection(@NotNull ScriptTrailer trailer) throws IOException {
        List<StoredFile> files = storeScripts(trailer.getScripts());

        for (ScriptResources.Entry entry : trailer.getResources().getEntries()) {
            files.add(new StoredFile(RESOURCE_PREFIX + entry.getName(), null, entry.getOriginalSize(), entry.getDigest()));
        }

        ByteBuffer section = ByteBuffer.allocate((int) getBodySize(files)).order(ByteOrder.LITTLE_ENDIAN);

        writeTrailer(section, files, 0, REFERENCED_RESOURCES_FLAG | (trailer.isProtected() ? PROTECTED_FLAG : 0));

        return section.array();
    }

    /**
     * Reads a replay section ({@link #createReplaySection}).
     *
     * @param section the section bytes
     * @return the trailer, whose resources are references without stored bytes
     * @throws IOException if the section is not a valid trailer with referenced resources, or the package it holds is not valid
     */
    public static @NotNull ScriptTrailer readReplaySection(byte @NotNull [] section) throws IOException {
        Footer footer = readFooter(section, REPLAY_DESCRIPTION);

        if (footer == null) {
            throw Exceptions.newIOException("The {0} does not end with the trailer magic.", REPLAY_DESCRIPTION);
        }

        if ((footer.flags & ~PROTECTED_FLAG) != REFERENCED_RESOURCES_FLAG || footer.paddingSize != 0 || footer.trailerSize != section.length) {
            throw Exceptions.newIOException("The {0} has the flags {1}, the padding {2} and the size {3}; a replay section has the flags {4}, no " +
                                            "padding and the size {5}.",
                                            REPLAY_DESCRIPTION, footer.flags, footer.paddingSize, footer.trailerSize, REFERENCED_RESOURCES_FLAG,
                                            section.length);
        }

        return readContents(section, footer, 0, REPLAY_DESCRIPTION);
    }

    /**
     * Computes the size of the trailer of a script package when every file is stored as it is, the largest trailer without padding
     * {@link #writePackage} writes.
     *
     * @param scriptPackage the script package
     * @return the size in bytes
     */
    static long getMaximumTrailerSize(@NotNull ScriptPackage scriptPackage) {
        long size = Integer.BYTES + FOOTER_SIZE + getRecordSize(ScriptManifest.FILE_NAME, scriptPackage.getManifest().getContent().length);

        for (Map.Entry<String, byte[]> script : scriptPackage.getScripts().entrySet()) {
            size += getRecordSize(script.getKey(), script.getValue().length);
        }

        for (Map.Entry<String, byte[]> resource : scriptPackage.getResources().entrySet()) {
            size += getRecordSize(RESOURCE_PREFIX + resource.getKey(), resource.getValue().length);
        }

        return size;
    }

    private static long getRecordSize(@NotNull String name, long dataSize) {
        return RECORD_FIXED_SIZE + name.getBytes(StandardCharsets.UTF_8).length + dataSize;
    }


    /**
     * Reads the footer at the end of bytes.
     *
     * @param data        the bytes
     * @param description the trailer name used in messages
     * @return the footer, or {@code null} if the bytes do not end with the trailer magic
     * @throws IOException if the footer is not valid
     */
    private static @Nullable Footer readFooter(byte @NotNull [] data, @NotNull String description) throws IOException {
        if (data.length < FOOTER_SIZE) {
            return null;
        }

        return readFooter(ByteBuffer.wrap(data, data.length - FOOTER_SIZE, FOOTER_SIZE).slice(), data.length, description);
    }

    /**
     * Reads a footer.
     *
     * @param footerBytes the {@value #FOOTER_SIZE} footer bytes
     * @param fileSize    the size of the bytes the footer ends
     * @param description the trailer name used in messages
     * @return the footer, or {@code null} if the bytes do not end with the trailer magic
     * @throws IOException if the version is not {@value #FORMAT_VERSION}, the flags are unknown or the sizes do not fit the file
     */
    private static @Nullable Footer readFooter(@NotNull ByteBuffer footerBytes, long fileSize, @NotNull String description) throws IOException {
        ByteBuffer footer = footerBytes.order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[MAGIC.length];

        footer.get(FOOTER_SIZE - MAGIC.length, magic);

        if (!Arrays.equals(magic, MAGIC)) {
            return null;
        }

        int version = Short.toUnsignedInt(footer.getShort());
        int flags = Short.toUnsignedInt(footer.getShort());
        long paddingSize = Integer.toUnsignedLong(footer.getInt());
        long directorySize = Integer.toUnsignedLong(footer.getInt());
        long trailerSize = footer.getLong();

        if (version != FORMAT_VERSION) {
            throw Exceptions.newIOException("The {0} has the version {1}; SC:R Enhanced reads the version {2}.", description, version, FORMAT_VERSION);
        }

        if ((flags & ~(REFERENCED_RESOURCES_FLAG | PROTECTED_FLAG)) != 0) {
            throw Exceptions.newIOException("The {0} has the unknown flags {1}.", description, flags);
        }

        if (trailerSize < FOOTER_SIZE || trailerSize > fileSize || paddingSize > MAXIMUM_PADDING ||
            trailerSize - FOOTER_SIZE < directorySize + paddingSize) {
            throw Exceptions.newIOException("The {0} declares {1} trailer bytes, {2} directory bytes and {3} padding bytes, which do not fit its " +
                                            "{4} bytes.",
                                            description, trailerSize, directorySize, paddingSize, fileSize);
        }

        if (directorySize > MpqArchive.MAXIMUM_FILE_SIZE) {
            throw Exceptions.newIOException("The {0} declares {1} directory bytes, more than an array holds.", description, directorySize);
        }

        return new Footer(flags, (int) paddingSize, (int) directorySize, trailerSize);
    }

    /**
     * Reads the directory and the manifest and scripts of a trailer in bytes.
     *
     * @param data        the bytes that hold the trailer, kept by the returned resources
     * @param footer      the read footer
     * @param dataStart   the offset of the data in the bytes
     * @param description the trailer name used in messages
     * @return the read trailer
     * @throws IOException if the directory, a manifest or script, or the package is not valid
     */
    private static @NotNull ScriptTrailer readContents(byte @NotNull [] data, @NotNull Footer footer, int dataStart,
                                                       @NotNull String description) throws IOException {
        long dataSize = footer.getDataSize();
        ByteBuffer directory = ByteBuffer.wrap(data, (int) (dataStart + dataSize), footer.directorySize).slice();
        boolean referencedResources = (footer.flags & REFERENCED_RESOURCES_FLAG) != 0;
        List<ScriptResources.Entry> entries = readDirectory(directory, dataSize, dataStart, referencedResources, description);
        Map<String, byte[]> scripts = new LinkedHashMap<>();
        byte[] manifest = null;

        for (ScriptResources.Entry entry : entries) {
            if (entry.getName().startsWith(RESOURCE_PREFIX)) {
                continue;
            }

            byte[] content = ScriptResources.readFile(data, entry, description);

            if (entry.getName().equals(ScriptManifest.FILE_NAME)) {
                manifest = content;
            } else {
                scripts.put(entry.getName(), content);
            }
        }

        if (manifest == null) {
            throw Exceptions.newIOException("The {0} holds no {1}.", description, ScriptManifest.FILE_NAME);
        }

        Map<String, ScriptResources.Entry> resources = splitResources(entries, description);

        ScriptPackage.checkNames(scripts.keySet(), resources.keySet());

        return new ScriptTrailer(ScriptPackage.create(ScriptManifest.parse(manifest), scripts, Map.of()), new ScriptResources(data, resources),
                                 (footer.flags & PROTECTED_FLAG) != 0);
    }

    /**
     * Takes the resources of directory entries, named without the resource folder.
     *
     * @param entries     the directory entries
     * @param description the trailer name used in messages
     * @return the resources by name, in directory order
     * @throws IOException if the directory holds the manifest twice
     */
    private static @NotNull Map<String, ScriptResources.Entry> splitResources(@NotNull List<ScriptResources.Entry> entries,
                                                                              @NotNull String description) throws IOException {
        Map<String, ScriptResources.Entry> resources = new LinkedHashMap<>();
        int manifestCount = 0;

        for (ScriptResources.Entry entry : entries) {
            String name = entry.getName();

            if (name.startsWith(RESOURCE_PREFIX)) {
                String resourceName = name.substring(RESOURCE_PREFIX.length());

                resources.put(resourceName, new ScriptResources.Entry(resourceName, entry.getOffset(), entry.getStoredSize(), entry.getOriginalSize(),
                                                                      entry.getDigest()));
            } else if (name.equals(ScriptManifest.FILE_NAME)) {
                manifestCount++;
            }
        }

        if (manifestCount > 1) {
            throw Exceptions.newIOException("The {0} holds {1} twice.", description, ScriptManifest.FILE_NAME);
        }

        return resources;
    }

    /**
     * Reads the directory of a trailer. A valid directory ends with its last record, has unique UTF-8 names and no stored bytes for a
     * reference, and its stored sizes add up to the data size.
     *
     * @param directory           the directory bytes
     * @param dataSize            the size of the data before the directory
     * @param dataStart           the offset of the data in the bytes that hold the trailer, or {@link ScriptResources#NO_OFFSET} to read no offsets
     * @param referencedResources whether the resources are references without stored bytes
     * @param description         the trailer name used in messages
     * @return the files in directory order, with their offsets
     * @throws IOException if the directory is not valid
     */
    private static @NotNull List<ScriptResources.Entry> readDirectory(@NotNull ByteBuffer directory, long dataSize, int dataStart,
                                                                      boolean referencedResources, @NotNull String description) throws IOException {
        ByteBuffer records = directory.order(ByteOrder.LITTLE_ENDIAN);
        long fileCount = Integer.toUnsignedLong(readInt(records, description));
        List<ScriptResources.Entry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        long offset = 0;

        for (long fileIndex = 0; fileIndex < fileCount; fileIndex++) {
            byte[] nameBytes = new byte[Short.toUnsignedInt(readShort(records, description))];

            requireRemaining(records, nameBytes.length + RECORD_FIXED_SIZE - Short.BYTES, description);
            records.get(nameBytes);

            String name;

            try {
                name = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(nameBytes)).toString();
            } catch (CharacterCodingException exception) {
                throw Exceptions.newIOException(exception, "The {0} holds a file name that is not UTF-8.", description);
            }

            long storedSize = Integer.toUnsignedLong(records.getInt());
            long originalSize = Integer.toUnsignedLong(records.getInt());
            byte[] digest = new byte[ScriptResources.DIGEST_SIZE];

            records.get(digest);

            if (!names.add(name)) {
                throw Exceptions.newIOException("The {0} holds the file name {1} twice.", description, name);
            }

            if (originalSize > MpqArchive.MAXIMUM_FILE_SIZE || storedSize > originalSize) {
                throw Exceptions.newIOException("The {0} holds {1} with {2} stored and {3} original bytes.", description, name, storedSize,
                                                originalSize);
            }

            boolean reference = referencedResources && name.startsWith(RESOURCE_PREFIX);

            if (reference && storedSize != 0) {
                throw Exceptions.newIOException("The {0} stores {1} bytes of the referenced resource {2}.", description, storedSize, name);
            }

            if (offset + storedSize > dataSize) {
                throw Exceptions.newIOException("The {0} holds files of more than its {1} data bytes.", description, dataSize);
            }

            int entryOffset = reference || dataStart == ScriptResources.NO_OFFSET ? ScriptResources.NO_OFFSET : (int) (dataStart + offset);

            entries.add(new ScriptResources.Entry(name, entryOffset, (int) storedSize, (int) originalSize, digest));
            offset += storedSize;
        }

        if (records.hasRemaining()) {
            throw Exceptions.newIOException("The {0} holds {1} bytes after its last directory record.", description, records.remaining());
        }

        if (offset != dataSize) {
            throw Exceptions.newIOException("The {0} holds {1} data bytes, of which its files take {2}.", description, dataSize, offset);
        }

        return entries;
    }

    private static int readInt(@NotNull ByteBuffer records, @NotNull String description) throws IOException {
        requireRemaining(records, Integer.BYTES, description);

        return records.getInt();
    }

    private static short readShort(@NotNull ByteBuffer records, @NotNull String description) throws IOException {
        requireRemaining(records, Short.BYTES, description);

        return records.getShort();
    }

    private static void requireRemaining(@NotNull ByteBuffer records, int size, @NotNull String description) throws IOException {
        if (records.remaining() < size) {
            throw Exceptions.newIOException("The directory of the {0} ends inside a record at offset {1}.", description, records.position());
        }
    }

    private static @NotNull ByteBuffer readBytes(@NotNull FileChannel channel, long position, int size) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(size);

        while (bytes.hasRemaining()) {
            if (channel.read(bytes, position + bytes.position()) < 0) {
                throw new EOFException(Exceptions.format("The file ends before byte {0}.", position + size));
            }
        }

        return bytes.flip();
    }


    /**
     * Finds the padding before a trailer: up to the end of each MPQ range past the end of the map that the trailer would otherwise reach, the
     * header bytes and the hash and block tables the map reader reads, and the archive size of the header when it ends at most
     * {@value #MAXIMUM_PADDING} bytes past the map.
     *
     * @param map          the map bytes
     * @param headerOffset the offset of the header the map reader accepts
     * @param bodySize     the size of the trailer without padding
     * @param description  the map name used in messages
     * @return the padding size
     * @throws IOException if a range the trailer would reach ends more than {@value #MAXIMUM_PADDING} bytes past the map
     */
    private static int findPaddingSize(byte @NotNull [] map, long headerOffset, long bodySize, @NotNull String description) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(map).order(ByteOrder.LITTLE_ENDIAN);
        int base = (int) headerOffset;
        long hashStart = headerOffset + header.getInt(base + MPQ_HASH_TABLE_POSITION_OFFSET);
        long blockStart = headerOffset + header.getInt(base + MPQ_BLOCK_TABLE_POSITION_OFFSET);
        long hashSize = (long) (header.getInt(base + MPQ_HASH_TABLE_SIZE_OFFSET) & MPQ_HASH_TABLE_SIZE_MASK) * MPQ_TABLE_ENTRY_SIZE;
        long blockSize = Integer.toUnsignedLong(header.getInt(base + MPQ_BLOCK_TABLE_SIZE_OFFSET)) * MPQ_TABLE_ENTRY_SIZE;
        long archiveEnd = headerOffset + Integer.toUnsignedLong(header.getInt(base + MPQ_ARCHIVE_SIZE_OFFSET));
        long[] rangeStarts = {headerOffset, hashStart, blockStart};
        long[] rangeEnds = {headerOffset + MPQ_HEADER_COPY_SIZE, hashStart + hashSize, blockStart + blockSize};
        String[] rangeNames = {"header", "hash table", "block table"};
        long end = map.length;

        if (archiveEnd > end && archiveEnd - map.length <= MAXIMUM_PADDING) {
            end = archiveEnd;
        }

        boolean extended = true;

        // A range that starts inside the trailer reads trailer bytes; padding to its end keeps it as zeros, and may pull in another range.
        while (extended) {
            extended = false;

            for (int index = 0; index < rangeStarts.length; index++) {
                if (rangeStarts[index] < 0 || rangeEnds[index] <= end || rangeStarts[index] >= end + bodySize) {
                    continue;
                }

                if (rangeEnds[index] - map.length > MAXIMUM_PADDING) {
                    throw Exceptions.newIOException("The MPQ {0} of {1} ends {2} bytes past the end of the map; a script trailer there would change it.",
                                                    rangeNames[index], description, rangeEnds[index] - map.length);
                }

                end      = rangeEnds[index];
                extended = true;
            }
        }

        return (int) (end - map.length);
    }

    private static @NotNull List<StoredFile> storePackage(@NotNull ScriptPackage scriptPackage) throws IOException {
        List<StoredFile> files = storeScripts(scriptPackage);

        for (Map.Entry<String, byte[]> resource : scriptPackage.getResources().entrySet()) {
            files.add(storeFile(RESOURCE_PREFIX + resource.getKey(), resource.getValue()));
        }

        return files;
    }

    private static @NotNull List<StoredFile> storeScripts(@NotNull ScriptPackage scriptPackage) throws IOException {
        List<StoredFile> files = new ArrayList<>();

        files.add(storeFile(ScriptManifest.FILE_NAME, scriptPackage.getManifest().getContent()));

        for (Map.Entry<String, byte[]> script : scriptPackage.getScripts().entrySet()) {
            files.add(storeFile(script.getKey(), script.getValue()));
        }

        return files;
    }

    /**
     * Stores a file: its zlib stream when that is smaller, else its bytes.
     */
    private static @NotNull StoredFile storeFile(@NotNull String name, byte @NotNull [] content) throws IOException {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        byte[] buffer = new byte[content.length];
        int size = 0;

        try {
            deflater.setInput(content);
            deflater.finish();

            // The stream is kept only while it stays below the original size.
            while (!deflater.finished() && size < buffer.length) {
                size += deflater.deflate(buffer, size, buffer.length - size);
            }

            boolean deflated = deflater.finished() && size < content.length;

            return new StoredFile(name, deflated ? Arrays.copyOf(buffer, size) : content, content.length, ScriptResources.digest(content));
        } finally {
            deflater.end();
        }
    }

    private static long getBodySize(@NotNull List<StoredFile> files) {
        long size = Integer.BYTES + FOOTER_SIZE;

        for (StoredFile file : files) {
            size += RECORD_FIXED_SIZE + file.nameBytes.length + file.getStoredSize();
        }

        return size;
    }

    /**
     * Writes a trailer at the position of a buffer: the padding, the data, the directory and the footer.
     */
    private static void writeTrailer(@NotNull ByteBuffer target, @NotNull List<StoredFile> files, int paddingSize, int flags) {
        long trailerSize = paddingSize + getBodySize(files);
        int directorySize = Integer.BYTES;

        target.put(new byte[paddingSize]);

        for (StoredFile file : files) {
            if (file.data != null) {
                target.put(file.data);
            }

            directorySize += RECORD_FIXED_SIZE + file.nameBytes.length;
        }

        target.putInt(files.size());

        for (StoredFile file : files) {
            target.putShort((short) file.nameBytes.length).put(file.nameBytes).putInt(file.getStoredSize()).putInt(file.originalSize).put(file.digest);
        }

        target.putShort((short) FORMAT_VERSION).putShort((short) flags).putInt(paddingSize).putInt(directorySize).putLong(trailerSize).put(MAGIC);
    }

    private ScriptStorageUtils() {
    }
}
