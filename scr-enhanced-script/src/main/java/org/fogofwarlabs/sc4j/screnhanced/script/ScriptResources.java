package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Represents the resources of a script package as {@link ScriptStorageUtils} stores them, without reading them: the bytes that hold them and
 * the place, sizes and SHA-256 digest of each resource, by name in the stored order.
 * <p>
 * {@link #read} reads one resource on demand: it copies or inflates the stored bytes and checks the size and the digest. A resource of a replay
 * section is a reference without stored bytes; reading it fails.
 */
public final class ScriptResources {
    /**
     * The resources of a package without resources.
     */
    public static final @NotNull ScriptResources EMPTY = new ScriptResources(new byte[0], Map.of());

    /**
     * The digest algorithm of the stored files.
     */
    static final @NotNull String DIGEST_ALGORITHM = "SHA-256";
    /**
     * The size of a digest in bytes.
     */
    static final int DIGEST_SIZE = 32;
    /**
     * The offset of an entry without stored bytes.
     */
    static final int NO_OFFSET = -1;

    private static final @NotNull String DESCRIPTION = "script resources";

    /**
     * One stored file: its name, the range of its stored bytes, its original size and the SHA-256 digest of its original bytes.
     * <p>
     * The stored bytes are the original bytes when the stored size equals the original size, else a zlib stream of them.
     */
    public static final class Entry {
        private final @NotNull String name;
        private final int offset;
        private final int storedSize;
        private final int originalSize;
        private final byte @NotNull [] digest;

        /**
         * Creates an {@link Entry} instance.
         *
         * @param name         the file name
         * @param offset       the first stored byte in the bytes that hold the file, or {@link #NO_OFFSET} for a reference
         * @param storedSize   the number of stored bytes
         * @param originalSize the original size
         * @param digest       the SHA-256 digest of the original bytes, not copied
         */
        Entry(@NotNull String name, int offset, int storedSize, int originalSize, byte @NotNull [] digest) {
            this.name         = name;
            this.offset       = offset;
            this.storedSize   = storedSize;
            this.originalSize = originalSize;
            this.digest       = digest;
        }

        /**
         * @return the file name
         */
        public @NotNull String getName() {
            return this.name;
        }

        /**
         * @return the first stored byte in the bytes that hold the file, or {@link #NO_OFFSET} for a reference without stored bytes
         */
        int getOffset() {
            return this.offset;
        }

        /**
         * @return the number of stored bytes
         */
        public int getStoredSize() {
            return this.storedSize;
        }

        /**
         * @return the original size
         */
        public int getOriginalSize() {
            return this.originalSize;
        }

        /**
         * @return a copy of the SHA-256 digest of the original bytes
         */
        public byte @NotNull [] getDigest() {
            return this.digest.clone();
        }

        /**
         * Tells whether another entry names the same original file: the same name, original size and digest.
         *
         * @param other the other entry
         * @return {@code true} if both name the same original file, {@code false} otherwise
         */
        public boolean isSameFile(@NotNull Entry other) {
            return this.name.equals(other.name) && this.originalSize == other.originalSize && Arrays.equals(this.digest, other.digest);
        }
    }

    /**
     * Reads the original bytes of a stored file: copies them, or inflates them when the stored size is below the original size, then checks the
     * size and the digest.
     *
     * @param data        the bytes that hold the file
     * @param entry       the file
     * @param description the name of the bytes used in messages
     * @return the original bytes
     * @throws IOException if the file has no stored bytes, the stored size exceeds the original size, or the inflated bytes or their digest do not match
     */
    static byte @NotNull [] readFile(byte @NotNull [] data, @NotNull Entry entry, @NotNull String description) throws IOException {
        if (entry.getOffset() == NO_OFFSET) {
            throw Exceptions.newIOException("The {0} holds no bytes of {1}, only its size and digest.", description, entry.getName());
        }

        byte[] content;

        if (entry.getStoredSize() == entry.getOriginalSize()) {
            content = Arrays.copyOfRange(data, entry.getOffset(), entry.getOffset() + entry.getStoredSize());
        } else if (entry.getStoredSize() < entry.getOriginalSize()) {
            content = inflate(data, entry, description);
        } else {
            throw Exceptions.newIOException("The {0} stores {1} in {2} bytes, more than its {3} original bytes.", description, entry.getName(),
                                            entry.getStoredSize(), entry.getOriginalSize());
        }

        byte[] contentDigest = digest(content);

        if (!MessageDigest.isEqual(contentDigest, entry.digest)) {
            throw Exceptions.newIOException("The {0} holds {1} with the SHA-256 digest {2}, not the recorded {3}.", description, entry.getName(),
                                            HexFormat.of().formatHex(contentDigest), HexFormat.of().formatHex(entry.digest));
        }

        return content;
    }

    private static byte @NotNull [] inflate(byte @NotNull [] data, @NotNull Entry entry, @NotNull String description) throws IOException {
        Inflater inflater = new Inflater();
        byte[] content = new byte[entry.getOriginalSize()];
        byte[] overflow = new byte[1];
        int size = 0;
        boolean longer = false;

        try {
            inflater.setInput(data, entry.getOffset(), entry.getStoredSize());

            // A complete stream fills the content exactly and leaves no stored byte; a byte past the content ends the inflation.
            while (!inflater.finished() && !longer) {
                int inflated;

                if (size < content.length) {
                    inflated = inflater.inflate(content, size, content.length - size);
                    size += inflated;
                } else {
                    inflated = inflater.inflate(overflow);
                    longer   = inflated != 0;
                }

                if (inflated == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
            }

            if (longer || size != content.length || !inflater.finished() || inflater.getRemaining() != 0) {
                throw Exceptions.newIOException("The {0} holds a broken zlib stream of {1}: it does not inflate to exactly {2} bytes.", description,
                                                entry.getName(), entry.getOriginalSize());
            }
        } catch (DataFormatException exception) {
            throw Exceptions.newIOException(exception, "The {0} holds a broken zlib stream of {1}: {2}", description, entry.getName(),
                                            exception.getMessage());
        } finally {
            inflater.end();
        }

        return content;
    }

    /**
     * Computes the SHA-256 digest of bytes.
     *
     * @param content the bytes
     * @return the digest
     */
    static byte @NotNull [] digest(byte @NotNull [] content) {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM).digest(content);
        } catch (NoSuchAlgorithmException exception) {
            throw Exceptions.newIllegalStateException(exception, "The Java runtime has no {0}.", DIGEST_ALGORITHM);
        }
    }


    private final byte @NotNull [] data;
    private final @NotNull Map<String, Entry> entries;

    /**
     * Creates a {@link ScriptResources} instance.
     *
     * @param data    the bytes that hold the resources, not copied
     * @param entries the resources by name, in the stored order; the map is copied
     */
    ScriptResources(byte @NotNull [] data, @NotNull Map<String, Entry> entries) {
        this.data    = data;
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /**
     * @return the unmodifiable resources in the stored order
     */
    public @NotNull Collection<Entry> getEntries() {
        return this.entries.values();
    }

    /**
     * @return {@code true} if there is no resource, {@code false} otherwise
     */
    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    /**
     * Finds a resource by its name.
     *
     * @param name the resource name, relative to the resource folder with {@code /} separators
     * @return the resource, or {@code null} if there is no resource of the name
     */
    public @Nullable Entry findEntry(@NotNull String name) {
        return this.entries.get(name);
    }

    /**
     * Reads a resource: copies or inflates its stored bytes and checks its size and digest.
     *
     * @param entry a resource of these resources
     * @return the original bytes of the resource
     * @throws IOException if the resource has no stored bytes or its stored bytes do not give the recorded size and digest
     */
    public byte @NotNull [] read(@NotNull Entry entry) throws IOException {
        return readFile(this.data, entry, DESCRIPTION);
    }

    /**
     * Selects the resources that references name, when these resources hold each referenced file with the same original size and digest.
     *
     * @param references the referenced resources, with or without stored bytes
     * @return the resources of the references in their order, with the stored bytes of these resources, or {@code null} if one is not held
     */
    public @Nullable ScriptResources select(@NotNull Collection<Entry> references) {
        Map<String, Entry> selected = new LinkedHashMap<>();

        for (Entry reference : references) {
            Entry entry = this.entries.get(reference.getName());

            if (entry == null || !entry.isSameFile(reference)) {
                return null;
            }

            selected.put(entry.getName(), entry);
        }

        return new ScriptResources(this.data, selected);
    }
}
