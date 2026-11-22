package org.fogofwarlabs.sc4j.scr.client.glue;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.base.text.TerminatedStringUtils;
import org.fogofwarlabs.sc4j.scr.core.FileNameUtils;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayWriter;
import org.fogofwarlabs.sc4j.scr.game.save.SaveGameKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Stores the campaign progress of a player in a single-player ID file, encoded in replay sections: a record section, whose unrecognized
 * bytes are kept, and a UTF-8 name section. The file is named after the player's folder name ({@link
 * org.fogofwarlabs.sc4j.scr.client.ClientPlayer#getFolderName()}) and holds the player's name: an account's file is named after the
 * account id, a local ID's file after the ID's name. Files from before {@code 1.18} using the PKWare codec are unsupported.
 */
public final class CharacterFile {
    /**
     * The extension of single player IDs ({@code .mpc} for multiplayer ones).
     */
    public static final @NotNull String EXTENSION = ".spc";
    /**
     * The size of the name section: the longest name in bytes and its terminator.
     */
    public static final int NAME_SIZE = 0x60;
    /**
     * The longest name in bytes: the name section less its terminator.
     */
    public static final int MAXIMUM_NAME_LENGTH = NAME_SIZE - 1;
    /**
     * The most local IDs {@link #list} returns.
     */
    public static final int MAXIMUM_COUNT = 250;

    private static final @NotNull Logger LOGGER = Logger.of(CharacterFile.class);
    private static final int MILLISECONDS_PER_SECOND = 1000;
    private static final @NotNull String FILE_PATTERN = "*" + EXTENSION;

    private static final int RECORD_SECTION_INDEX = 0;
    private static final int NAME_SECTION_INDEX = 1;
    private static final int RECORD_SIZE = 0x70;
    private static final int MAGIC = 0x00112233;
    private static final int MAGIC_OFFSET = 0x00;
    private static final int MULTIPLAYER_OFFSET = 0x04;
    private static final int CREATION_TIME_OFFSET = 0x08;
    private static final int LEGACY_NAME_OFFSET = 0x0c;
    private static final int LEGACY_NAME_SIZE = 25;
    private static final int PROGRESS_OFFSET = 0x28;
    private static final int EXPANSION_PROGRESS_OFFSET = 0x34;

    /**
     * The races of the campaign progress fields, in field order.
     */
    public enum Race {
        /**
         * Zerg campaign progress.
         */
        ZERG,
        /**
         * Terran campaign progress.
         */
        TERRAN,
        /**
         * Protoss campaign progress.
         */
        PROTOSS
    }

    /**
     * Lists the local IDs of a folder: the valid single-player ID files whose name is their file name, oldest first and by file name within the
     * same second, at most {@link #MAXIMUM_COUNT}.
     *
     * @param directory the character folder
     * @return the local IDs; none if the folder does not exist or cannot be read
     */
    public static @NotNull List<CharacterFile> list(@NotNull Path directory) {
        List<CharacterFile> characters = new ArrayList<>();

        if (!Files.isDirectory(directory)) {
            return characters;
        }

        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, FILE_PATTERN)) {
            for (Path file : files) {
                CharacterFile character = read(file);

                if (character != null && !character.isMultiplayer() && character.getName().equals(character.getFileName())) {
                    characters.add(character);
                }
            }
        } catch (IOException exception) {
            LOGGER.warning("CHARACTERS UNREADABLE: {0}: {1}", directory, exception.getMessage());
        }

        characters.sort(Comparator.comparingLong(CharacterFile::getCreationTime).thenComparing(CharacterFile::getFileName));

        return characters.size() > MAXIMUM_COUNT ? new ArrayList<>(characters.subList(0, MAXIMUM_COUNT)) : characters;
    }

    /**
     * Opens the character file of a player: reads the file when it is a valid single-player ID, which keeps its progress, and creates and
     * writes a new one otherwise. The ID holds the given name; a read file takes it at its next write.
     *
     * @param directory the character folder
     * @param fileName  the file name without {@link #EXTENSION}
     * @param name      the player's name; more than the name section holds is cut ({@link TerminatedStringUtils#cutText(String, int)})
     * @return the opened character file; when a new file cannot be written, the ID lives in memory until a write succeeds
     */
    public static @NotNull CharacterFile open(@NotNull Path directory, @NotNull String fileName, @NotNull String name) {
        Path file = directory.resolve(fileName + EXTENSION);
        byte[] nameBytes = TerminatedStringUtils.cutText(name, NAME_SIZE);
        CharacterFile read = Files.isRegularFile(file) ? read(file) : null;

        if (read != null && !read.isMultiplayer()) {
            return new CharacterFile(file, nameBytes, read.record);
        }

        try {
            return create(directory, fileName, nameBytes);
        } catch (IOException exception) {
            LOGGER.warning("CHARACTER CREATE FAILED: {0}: {1}", file, exception.getMessage());

            return new CharacterFile(file, nameBytes, createRecord(nameBytes));
        }
    }

    /**
     * Reads one ID file.
     *
     * @param file the {@code .spc} file
     * @return the ID with the name its name section holds, or {@code null} if the file is not a valid ID
     */
    public static @Nullable CharacterFile read(@NotNull Path file) {
        try {
            List<byte[]> sections = ReplayFile.readSections(Files.readAllBytes(file), false, RECORD_SIZE, NAME_SIZE);
            byte[] record = sections.get(RECORD_SECTION_INDEX);

            if (createRecordBuffer(record).getInt(MAGIC_OFFSET) != MAGIC) {
                return null;
            }

            byte[] section = sections.get(NAME_SECTION_INDEX);

            return new CharacterFile(file, Arrays.copyOf(section, TerminatedStringUtils.getTextLength(section)), record);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("CHARACTER UNREADABLE: {0}: {1}", file, exception.getMessage());

            return null;
        }
    }

    /**
     * Creates a {@link CharacterFile} instance for a new single-player ID and writes its file, replacing a file of the same name.
     * <p>
     * The ID keeps the name bytes as its name section holds them: more than the name section's {@value #NAME_SIZE} bytes less the
     * terminator are cut ({@link TerminatedStringUtils#cutText(byte[], int)}).
     *
     * @param directory the character folder
     * @param fileName  the file name without {@link #EXTENSION}
     * @param name      the name bytes without terminator
     * @return the created {@link CharacterFile} instance
     * @throws IOException if the file cannot be written
     */
    public static @NotNull CharacterFile create(@NotNull Path directory, @NotNull String fileName, byte @NotNull [] name) throws IOException {
        return write(directory, fileName, TerminatedStringUtils.cutText(name, NAME_SIZE));
    }

    /**
     * Creates a {@link CharacterFile} instance for a new local ID and writes its file, named after the name as the name section holds it.
     * <p>
     * More than {@link #MAXIMUM_NAME_LENGTH} name bytes are cut ({@link TerminatedStringUtils#cutText(byte[], int)}); cut bytes that do not
     * convert to a file name ({@link FileNameUtils#readFileName}) open no file.
     *
     * @param directory the character folder
     * @param name      the name bytes without terminator, which the caller checked with {@link FileNameUtils#isValidFileName(byte[], int, String)}
     * @return the created {@link CharacterFile} instance
     * @throws IOException if the cut name is no file name, or the file exists or cannot be written
     */
    public static @NotNull CharacterFile createNamed(@NotNull Path directory, byte @NotNull [] name) throws IOException {
        byte[] sectionName = TerminatedStringUtils.cutText(name, NAME_SIZE);
        String fileName = FileNameUtils.readFileName(sectionName);

        if (fileName == null) {
            throw Exceptions.newIOException("The character name {0} is no file name.", TerminatedStringUtils.readText(sectionName));
        }

        return write(directory, fileName, sectionName, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /**
     * Creates a {@link CharacterFile} instance for a new single-player ID and writes its file.
     *
     * @param directory   the character folder
     * @param fileName    the file name without {@link #EXTENSION}
     * @param sectionName the name bytes as the name section holds them
     * @param options     how the file is opened; none creates or replaces it
     * @return the created {@link CharacterFile} instance
     * @throws IOException if the file cannot be written
     */
    private static @NotNull CharacterFile write(@NotNull Path directory, @NotNull String fileName, byte @NotNull [] sectionName,
                                                @NotNull OpenOption @NotNull ... options) throws IOException {
        Files.createDirectories(directory);

        Path file = directory.resolve(fileName + EXTENSION);
        CharacterFile character = new CharacterFile(file, sectionName, createRecord(sectionName));

        Files.write(file, character.encode(), options);

        return character;
    }

    /**
     * Creates the record of a new single-player ID: the magic number, the creation time and the legacy name field, which holds the name
     * cut to its size followed by a zero byte.
     */
    private static byte @NotNull [] createRecord(byte @NotNull [] sectionName) {
        byte[] record = new byte[RECORD_SIZE];
        ByteBuffer fields = createRecordBuffer(record);

        fields.putInt(MAGIC_OFFSET, MAGIC);
        record[MULTIPLAYER_OFFSET] = 0;
        fields.putInt(CREATION_TIME_OFFSET, (int) (System.currentTimeMillis() / MILLISECONDS_PER_SECOND));

        byte[] legacyName = TerminatedStringUtils.cutText(sectionName, LEGACY_NAME_SIZE);

        System.arraycopy(legacyName, 0, record, LEGACY_NAME_OFFSET, legacyName.length);

        return record;
    }

    private static @NotNull ByteBuffer createRecordBuffer(byte @NotNull [] record) {
        return ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Finds the record offset of a campaign's progress field.
     *
     * @param expansion {@code true} for the Brood War campaigns
     * @param race      the campaign race
     * @return the offset of the race's next mission index
     */
    private static int findProgressOffset(boolean expansion, @NotNull Race race) {
        return (expansion ? EXPANSION_PROGRESS_OFFSET : PROGRESS_OFFSET) + race.ordinal() * Integer.BYTES;
    }

    private final @NotNull Path path;
    /**
     * The name section's bytes before the terminator.
     */
    private final byte @NotNull [] name;

    private final byte @NotNull [] record;

    private CharacterFile(@NotNull Path path, byte @NotNull [] name, byte @NotNull [] record) {
        this.path = path;
        this.name = name;

        this.record = record;
    }

    /**
     * @return the player's name the name section holds, read from the name bytes ({@link TerminatedStringUtils#readText})
     */
    public @NotNull String getName() {
        return TerminatedStringUtils.readText(this.name);
    }

    /**
     * @return the file name without {@link #EXTENSION}: the player's folder name
     */
    public @NotNull String getFileName() {
        String fileName = this.path.getFileName().toString();

        return fileName.substring(0, fileName.length() - EXTENSION.length());
    }

    /**
     * @return the creation time in seconds since 1970, unsigned
     */
    public long getCreationTime() {
        return Integer.toUnsignedLong(createRecordBuffer(this.record).getInt(CREATION_TIME_OFFSET));
    }

    /**
     * @return {@code true} if this is a multiplayer ID, {@code false} otherwise
     */
    public boolean isMultiplayer() {
        return this.record[MULTIPLAYER_OFFSET] != 0;
    }

    /**
     * @param expansion {@code true} for the Brood War campaigns
     * @param race      the campaign race
     * @return the campaign's next mission index, {@code 0} if not started
     */
    public int getCampaignProgress(boolean expansion, @NotNull Race race) {
        return createRecordBuffer(this.record).getInt(findProgressOffset(expansion, race));
    }

    /**
     * Sets a campaign's next mission index and writes the character file.
     *
     * @param expansion {@code true} for the Brood War campaigns
     * @param race      the campaign race
     * @param mission   the mission index
     * @throws IOException if the file cannot be written
     */
    public void setCampaignProgress(boolean expansion, @NotNull Race race, int mission) throws IOException {
        createRecordBuffer(this.record).putInt(findProgressOffset(expansion, race), mission);
        Files.write(this.path, this.encode());
    }

    /**
     * Deletes the file of this ID, then the saved games of its saved-game folder and the folder once it is empty. Saved games that cannot be
     * deleted are kept and logged.
     *
     * @param saveDirectory the ID's saved-game folder
     * @throws IOException if the file cannot be deleted; the saved games are kept then
     */
    public void delete(@NotNull Path saveDirectory) throws IOException {
        Files.deleteIfExists(this.path);

        if (!Files.isDirectory(saveDirectory)) {
            return;
        }

        try {
            for (SaveGameKind kind : SaveGameKind.values()) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(saveDirectory, kind.getPattern())) {
                    for (Path file : files) {
                        Files.deleteIfExists(file);
                    }
                }
            }

            Files.deleteIfExists(saveDirectory);
        } catch (DirectoryNotEmptyException exception) {
            LOGGER.info("SAVED GAME FOLDER KEPT: {0}", saveDirectory);
        } catch (IOException exception) {
            LOGGER.warning("SAVED GAMES NOT DELETED: {0}: {1}", saveDirectory, exception.getMessage());
        }
    }

    private byte @NotNull [] encode() {
        byte[] nameSection = Arrays.copyOf(this.name, NAME_SIZE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        output.writeBytes(ReplayWriter.encodeSection(this.record));
        output.writeBytes(ReplayWriter.encodeSection(nameSection));

        return output.toByteArray();
    }
}
