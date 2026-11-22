package org.fogofwarlabs.sc4j.scr.client.glue;

import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayWriter;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Checks the character ID files of a player: the name bytes a created file holds, opening a player's file, which keeps the progress of a
 * valid file under the player's current name and replaces a missing or invalid one, and creating, listing and deleting local IDs with
 * their saved games.
 * <p>
 * The record and name section layout is the native file format, so the tests read the written sections.
 */
final class CharacterFileTest {
    /**
     * A character of three UTF-8 bytes.
     */
    private static final @NotNull String HANGUL = "\ud55c";
    /**
     * The character a {@link String} read from the bytes holds for a split character.
     */
    private static final char REPLACEMENT = '\ufffd';
    /**
     * The number of UTF-8 bytes of {@link #HANGUL}.
     */
    private static final int HANGUL_BYTES = 3;
    /**
     * The size of the name section: the longest name in bytes and its terminator.
     */
    private static final int NAME_SIZE = 0x60;
    /**
     * The longest name in bytes.
     */
    private static final int MAXIMUM_NAME_LENGTH = NAME_SIZE - 1;
    /**
     * The Hangul characters of a name one byte longer than the longest name.
     */
    private static final int LONG_NAME_LENGTH = NAME_SIZE / HANGUL_BYTES;
    /**
     * The size of the record section, and the offset and size of its legacy name field.
     */
    private static final int RECORD_SIZE = 0x70;
    private static final int LEGACY_NAME_OFFSET = 0x0C;
    private static final int LEGACY_NAME_SIZE = 25;
    /**
     * The indexes of the record and name sections in a character file.
     */
    private static final int RECORD_SECTION = 0;
    private static final int NAME_SECTION = 1;
    /**
     * The file name extension of a character file.
     */
    private static final @NotNull String EXTENSION = ".spc";
    /**
     * The next mission index the campaign progress test writes.
     */
    private static final int NEXT_MISSION = 1;
    /**
     * The file name of the player's character file in the open tests.
     */
    private static final @NotNull String FILE_NAME = "0123456789abcdef";
    /**
     * The local IDs the order test creates within one second, and their order by file name.
     */
    private static final @NotNull List<String> SAME_SECOND_NAMES = List.of("kerrigan", "Raynor", "Fenix");
    private static final @NotNull List<String> FILE_NAME_ORDER = List.of("Fenix", "Raynor", "kerrigan");
    /**
     * The attempts at creating the local IDs of the order test within one second.
     */
    private static final int SAME_SECOND_ATTEMPTS = 5;
    /**
     * A saved game of a player and a file of another kind in its saved-game folder.
     */
    private static final @NotNull String SAVED_GAME = "save.snx";
    private static final @NotNull String OTHER_FILE = "notes.txt";

    private static @NotNull List<byte[]> readSections(@NotNull Path file) throws IOException {
        return ReplayFile.readSections(Files.readAllBytes(file), false, RECORD_SIZE, NAME_SIZE);
    }

    private static long countFiles(@NotNull Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.count();
        }
    }

    @Test
    void create_should_write_the_name_bytes_cut_to_the_name_section(@TempDir @NotNull Path directory) throws IOException {
        // The 96-byte name loses its last byte, which ends a whole character.
        byte[] name = ("ab" + HANGUL.repeat(LONG_NAME_LENGTH - 1) + "c").getBytes(StandardCharsets.UTF_8);
        byte[] cut = Arrays.copyOf(name, MAXIMUM_NAME_LENGTH);
        CharacterFile created = CharacterFile.create(directory, FILE_NAME, name);
        Path file = directory.resolve(FILE_NAME + EXTENSION);
        List<byte[]> sections = readSections(file);

        Assertions.assertEquals("ab" + HANGUL.repeat(LONG_NAME_LENGTH - 1), created.getName());
        Assertions.assertArrayEquals(Arrays.copyOf(cut, NAME_SIZE), sections.get(NAME_SECTION));
        // The legacy field keeps the first 24 bytes, the first byte of a split character among them, and its terminator.
        Assertions.assertArrayEquals(Arrays.copyOf(cut, LEGACY_NAME_SIZE - 1),
                                     Arrays.copyOfRange(sections.get(RECORD_SECTION), LEGACY_NAME_OFFSET, LEGACY_NAME_OFFSET + LEGACY_NAME_SIZE - 1));
        Assertions.assertEquals(0, sections.get(RECORD_SECTION)[LEGACY_NAME_OFFSET + LEGACY_NAME_SIZE - 1]);
    }

    @Test
    void open_should_create_the_player_file_when_it_is_missing_or_invalid(@TempDir @NotNull Path directory) throws IOException {
        CharacterFile created = CharacterFile.open(directory, FILE_NAME, "Raynor");
        Path file = directory.resolve(FILE_NAME + EXTENSION);

        Assertions.assertEquals("Raynor", created.getName());
        Assertions.assertEquals(0, created.getCampaignProgress(false, CharacterFile.Race.TERRAN));
        Assertions.assertEquals(1, countFiles(directory));
        Assertions.assertArrayEquals(Arrays.copyOf("Raynor".getBytes(StandardCharsets.UTF_8), NAME_SIZE), readSections(file).get(NAME_SECTION));

        Files.writeString(file, "not a character file");

        CharacterFile replaced = CharacterFile.open(directory, FILE_NAME, "Raynor");

        Assertions.assertEquals(0, replaced.getCampaignProgress(false, CharacterFile.Race.TERRAN));
        Assertions.assertNotNull(CharacterFile.read(file), "the invalid file is written again");
    }

    @Test
    void open_should_keep_the_progress_of_the_player_file_under_the_current_name(@TempDir @NotNull Path directory) throws IOException {
        Path file = directory.resolve(FILE_NAME + EXTENSION);

        CharacterFile.open(directory, FILE_NAME, "Raynor").setCampaignProgress(true, CharacterFile.Race.ZERG, NEXT_MISSION);

        CharacterFile renamed = CharacterFile.open(directory, FILE_NAME, "Kerrigan");

        Assertions.assertEquals("Kerrigan", renamed.getName());
        Assertions.assertEquals(NEXT_MISSION, renamed.getCampaignProgress(true, CharacterFile.Race.ZERG));

        renamed.setCampaignProgress(true, CharacterFile.Race.ZERG, NEXT_MISSION + 1);

        CharacterFile read = CharacterFile.read(file);

        Assertions.assertNotNull(read);
        Assertions.assertEquals("Kerrigan", read.getName());
        Assertions.assertEquals(NEXT_MISSION + 1, read.getCampaignProgress(true, CharacterFile.Race.ZERG));
    }

    @Test
    void set_campaign_progress_should_keep_the_name_section_bytes(@TempDir @NotNull Path directory) throws IOException {
        // A file whose name section ends with the first two bytes of a split character.
        byte[] name = Arrays.copyOf(HANGUL.repeat(LONG_NAME_LENGTH).getBytes(StandardCharsets.UTF_8), MAXIMUM_NAME_LENGTH);
        Path file = directory.resolve(HANGUL.repeat(LONG_NAME_LENGTH - 1) + REPLACEMENT + EXTENSION);
        Path source = directory.resolve("x" + EXTENSION);
        ByteArrayOutputStream contents = new ByteArrayOutputStream();

        // A created file gives the record section.
        CharacterFile.create(directory, "x", "x".getBytes(StandardCharsets.UTF_8));
        contents.writeBytes(ReplayWriter.encodeSection(readSections(source).get(RECORD_SECTION)));
        contents.writeBytes(ReplayWriter.encodeSection(Arrays.copyOf(name, NAME_SIZE)));
        Files.write(file, contents.toByteArray());

        CharacterFile read = CharacterFile.read(file);

        Assertions.assertNotNull(read);
        Assertions.assertEquals(HANGUL.repeat(LONG_NAME_LENGTH - 1) + REPLACEMENT, read.getName());

        read.setCampaignProgress(false, CharacterFile.Race.TERRAN, NEXT_MISSION);

        Assertions.assertArrayEquals(Arrays.copyOf(name, NAME_SIZE), readSections(file).get(NAME_SECTION));
    }

    @Test
    void create_named_should_name_the_file_after_the_name_and_keep_an_existing_file(@TempDir @NotNull Path directory) throws IOException {
        CharacterFile created = CharacterFile.createNamed(directory, "Raynor".getBytes(StandardCharsets.UTF_8));

        Assertions.assertEquals("Raynor", created.getFileName());
        Assertions.assertTrue(Files.isRegularFile(directory.resolve("Raynor" + EXTENSION)));

        created.setCampaignProgress(false, CharacterFile.Race.TERRAN, NEXT_MISSION);

        Assertions.assertThrows(IOException.class, () -> CharacterFile.createNamed(directory, "Raynor".getBytes(StandardCharsets.UTF_8)));

        CharacterFile read = CharacterFile.read(directory.resolve("Raynor" + EXTENSION));

        Assertions.assertNotNull(read);
        Assertions.assertEquals(NEXT_MISSION, read.getCampaignProgress(false, CharacterFile.Race.TERRAN));
        // A name cut inside a character converts to no file name.
        byte[] split = HANGUL.repeat(LONG_NAME_LENGTH).getBytes(StandardCharsets.UTF_8);

        Assertions.assertThrows(IOException.class, () -> CharacterFile.createNamed(directory, split));
    }

    @Test
    void list_should_return_the_local_ids_whose_name_is_their_file_name(@TempDir @NotNull Path directory) throws IOException {
        CharacterFile.createNamed(directory, "Kerrigan".getBytes(StandardCharsets.UTF_8));
        CharacterFile.createNamed(directory, HANGUL.getBytes(StandardCharsets.UTF_8));
        // An account's file is named after the account id and holds the account name.
        CharacterFile.open(directory, FILE_NAME, "Raynor");
        Files.writeString(directory.resolve("broken" + EXTENSION), "not a character file");

        List<String> names = CharacterFile.list(directory).stream().map(CharacterFile::getName).sorted().toList();

        Assertions.assertEquals(List.of("Kerrigan", HANGUL), names);
        Assertions.assertEquals(List.of(), CharacterFile.list(directory.resolve("missing")));
    }

    @Test
    void list_should_order_the_local_ids_of_one_second_by_file_name(@TempDir @NotNull Path directory) throws IOException {
        for (int attempt = 0; attempt < SAME_SECOND_ATTEMPTS; attempt++) {
            Path folder = directory.resolve(Integer.toString(attempt));
            long creationTime = -1;
            boolean sameSecond = true;

            for (String name : SAME_SECOND_NAMES) {
                CharacterFile created = CharacterFile.createNamed(folder, name.getBytes(StandardCharsets.UTF_8));

                sameSecond &= creationTime < 0 || created.getCreationTime() == creationTime;
                creationTime = created.getCreationTime();
            }

            if (sameSecond) {
                Assertions.assertEquals(FILE_NAME_ORDER, CharacterFile.list(folder).stream().map(CharacterFile::getFileName).toList());

                return;
            }
        }

        Assertions.fail("The local IDs were not created within one second.");
    }

    @Test
    void delete_should_remove_the_file_and_the_saved_games(@TempDir @NotNull Path directory) throws IOException {
        Path characters = directory.resolve("characters");
        Path savedGames = directory.resolve("save");
        CharacterFile created = CharacterFile.createNamed(characters, "Raynor".getBytes(StandardCharsets.UTF_8));

        Files.createDirectories(savedGames);
        Files.write(savedGames.resolve(SAVED_GAME), new byte[]{0});
        created.delete(savedGames);

        Assertions.assertEquals(0, countFiles(characters));
        Assertions.assertEquals(List.of(), CharacterFile.list(characters));
        Assertions.assertFalse(Files.exists(savedGames));
    }

    @Test
    void delete_should_keep_a_saved_game_folder_with_other_files(@TempDir @NotNull Path directory) throws IOException {
        Path savedGames = directory.resolve("save");
        CharacterFile created = CharacterFile.createNamed(directory.resolve("characters"), "Raynor".getBytes(StandardCharsets.UTF_8));

        Files.createDirectories(savedGames);
        Files.write(savedGames.resolve(SAVED_GAME), new byte[]{0});
        Files.write(savedGames.resolve(OTHER_FILE), new byte[]{0});
        created.delete(savedGames);

        try (Stream<Path> files = Files.list(savedGames)) {
            Assertions.assertEquals(List.of(savedGames.resolve(OTHER_FILE)), files.toList());
        }
    }
}
