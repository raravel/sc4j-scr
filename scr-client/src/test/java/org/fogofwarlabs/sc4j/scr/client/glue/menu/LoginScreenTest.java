package org.fogofwarlabs.sc4j.scr.client.glue.menu;

import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LatencyEstimate;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.scr.client.ClientPlayer;
import org.fogofwarlabs.sc4j.scr.client.TextTables;
import org.fogofwarlabs.sc4j.scr.client.configuration.InstallLayout;
import org.fogofwarlabs.sc4j.scr.client.glue.CharacterFile;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.lobby.BeaconLinkGlueScenario;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ListBox;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Checks the ID screen of a run without an account: the title screen leads to it and opens New ID for an empty list, New ID creates and
 * selects an ID in the local ID folder and refuses an existing or invalid name, Delete removes an ID with its saved games and keeps another
 * player's saved games of the same name, and Ok makes the selected ID the run's player; after Cancel the main menu's login button and Single
 * Player lead to the ID screen; an account run goes from the title screen to the main menu. SD at 640x480 in English and HD at 1920x1080 in
 * Korean.
 */
@RequiresInstallation
final class LoginScreenTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    private static final int SELF_KEY = 1;

    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;
    private static final int LARGE_WIDTH = 1920;
    private static final int LARGE_HEIGHT = 1080;
    private static final @NotNull String ENGLISH = "enUS";
    private static final @NotNull String KOREAN = "koKR";

    /**
     * The controls of the ID screen and of the message popups.
     */
    private static final int LIST = 8;
    private static final int MESSAGE_TEXT = 2;
    /**
     * The hotkeys of New ID and Delete, the same in English and Korean.
     */
    private static final @NotNull String NEW_ID_HOTKEY = "n";
    private static final @NotNull String DELETE_HOTKEY = "d";
    /**
     * The hotkeys of the main menu's login button, of Single Player and of the Expansion choice in English.
     */
    private static final @NotNull String LOGIN_HOTKEY = "l";
    private static final @NotNull String SINGLE_PLAYER_HOTKEY = "s";
    private static final @NotNull String EXPANSION_HOTKEY = "e";

    private static final @NotNull String NAME_EXISTS_KEY = "loginNAMEEXISTS";
    private static final @NotNull String RESERVED_NAME_KEY = "strRESERVED_FILENAME";
    private static final @NotNull String CONFIRM_DELETE_KEY = "loginDELCHAR";
    /**
     * A Windows device name, which no ID may have.
     */
    private static final @NotNull String RESERVED_NAME = "con";
    private static final @NotNull String SAVED_GAME = "mission.snx";

    private static @NotNull RecordingCommunitySession createCommunity() {
        return new RecordingCommunitySession(RecordingCommunitySession.createPresence(SELF_KEY, BeaconLinkGlueScenario.ACCOUNT_NAME,
                                                                                      BeaconLinkGlueScenario.MOD_ID,
                                                                                      BeaconLinkGlueScenario.MOD_VERSION, true,
                                                                                      PlayerStatus.AVAILABLE, LatencyEstimate.UNKNOWN));
    }

    private static @NotNull ListBox requireList(@NotNull BeaconLinkGlueScenario scenario) {
        if (scenario.getScreenDialog().findControl(LIST) instanceof ListBox list) {
            return list;
        }

        return Assertions.fail("The ID screen is not shown.");
    }

    private static @Nullable String getMessage(@NotNull BeaconLinkGlueScenario scenario) {
        List<ScrDialog> popups = scenario.getPopupDialogs();

        Assertions.assertEquals(1, popups.size(), "one popup is open");

        ScrControl text = popups.getFirst().requireControl(MESSAGE_TEXT);

        return text.getReplacedText();
    }

    private static @NotNull List<String> listNames(@NotNull Path directory) {
        return CharacterFile.list(directory).stream().map(CharacterFile::getName).toList();
    }

    @TempDir
    @Nullable Path directory;

    private @NotNull Path createInstall(@NotNull String name) {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        return root.resolve(name);
    }

    /**
     * Starts a run without an account from the title screen, then creates, refuses, deletes and chooses IDs.
     */
    private void playIdScreen(@NotNull String locale, int width, int height, @NotNull String qualityPrefix,
                              @NotNull String firstName, @NotNull String secondName) throws IOException {
        String size = width + "x" + height + "-" + locale;
        Path install = this.createInstall(size);
        Path characters = InstallLayout.of(install).getCharacterDirectory().resolve(ClientPlayer.LOCAL_ID_FOLDER);

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(install, locale, width, height, qualityPrefix, createCommunity(),
                                                                           List.of(), null)) {
            TextTables texts = scenario.getClient().getTextTables(locale);

            scenario.settle();
            scenario.settle();

            Assertions.assertEquals(0, requireList(scenario).getCount(), "the ID list is empty");
            Assertions.assertTrue(scenario.isPopupOpen(), "an empty list opens New ID");

            scenario.type(firstName);
            scenario.press(Key.ENTER);

            Assertions.assertFalse(scenario.isPopupOpen());
            Assertions.assertEquals(List.of(firstName), listNames(characters));
            Assertions.assertEquals(0, requireList(scenario).getSelection(), "the created ID is selected");

            scenario.type(NEW_ID_HOTKEY);
            scenario.type(firstName.toUpperCase(Locale.ROOT));
            scenario.press(Key.ENTER);

            Assertions.assertEquals(texts.getGluText().get(NAME_EXISTS_KEY), getMessage(scenario));
            scenario.press(Key.ENTER);

            scenario.type(NEW_ID_HOTKEY);
            scenario.type(RESERVED_NAME);
            scenario.press(Key.ENTER);

            Assertions.assertEquals(texts.getNetwork().get(RESERVED_NAME_KEY), getMessage(scenario));
            scenario.press(Key.ENTER);

            scenario.type(NEW_ID_HOTKEY);
            scenario.type(secondName);
            scenario.press(Key.ENTER);

            List<String> names = listNames(characters);

            Assertions.assertEquals(2, names.size(), names.toString());
            Assertions.assertEquals(names.indexOf(secondName), requireList(scenario).getSelection(), "the created ID is selected");

            Path saveFolder = InstallLayout.of(install).getSaveDirectory();
            Path savedGames = saveFolder.resolve(ClientPlayer.LOCAL_ID_FOLDER).resolve(secondName);
            // The saved-game folder of another player of the same name, which the ID's deletion keeps.
            Path otherSavedGames = saveFolder.resolve(secondName);

            Files.createDirectories(savedGames);
            Files.write(savedGames.resolve(SAVED_GAME), new byte[]{0});
            Files.createDirectories(otherSavedGames);
            Files.write(otherSavedGames.resolve(SAVED_GAME), new byte[]{0});

            scenario.type(DELETE_HOTKEY);

            Assertions.assertEquals(texts.getGluText().get(CONFIRM_DELETE_KEY), getMessage(scenario));
            scenario.press(Key.ENTER);

            Assertions.assertEquals(List.of(firstName), listNames(characters));
            Assertions.assertFalse(Files.exists(savedGames), "the deleted ID's saved games are deleted");
            Assertions.assertTrue(Files.exists(otherSavedGames.resolve(SAVED_GAME)), "another player's saved games are kept");
            Assertions.assertEquals(0, requireList(scenario).getSelection(), "the selection moves to the last row");

            scenario.press(Key.ENTER);

            ClientPlayer player = scenario.getClient().getPlayer();

            Assertions.assertFalse(player.isGuest());
            Assertions.assertEquals(firstName, player.getName());
            Assertions.assertEquals(firstName, player.getFolderName());
            Assertions.assertEquals(saveFolder.resolve(ClientPlayer.LOCAL_ID_FOLDER).resolve(firstName), player.getSaveDirectory(saveFolder));
            Assertions.assertFalse(scenario.getScreenDialog().findControl(LIST) instanceof ListBox, "Ok leaves for the main menu");
        }
    }

    @Test
    void id_screen_creates_refuses_deletes_and_chooses_ids() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        this.playIdScreen(ENGLISH, SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX, "Kerrigan", "Raynor");
        this.playIdScreen(KOREAN, LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX, "Kerrigan", "레이너");
    }

    @Test
    void canceled_id_screen_leaves_the_login_button_and_single_player_asks_for_an_id() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(this.createInstall("cancel"), ENGLISH, SMALL_WIDTH, SMALL_HEIGHT,
                                                                           GlueCanvas.SD_PREFIX, createCommunity(), List.of(), null)) {
            scenario.settle();
            scenario.settle();
            scenario.press(Key.ESCAPE);
            scenario.press(Key.ESCAPE);

            Assertions.assertFalse(scenario.getScreenDialog().findControl(LIST) instanceof ListBox, "Cancel leaves for the main menu");
            Assertions.assertTrue(scenario.getClient().getPlayer().isGuest(), "no ID is chosen");

            scenario.type(LOGIN_HOTKEY);

            Assertions.assertEquals(0, requireList(scenario).getCount(), "the login button opens the ID screen");

            scenario.press(Key.ESCAPE);
            scenario.press(Key.ESCAPE);
            scenario.type(SINGLE_PLAYER_HOTKEY);
            scenario.type(EXPANSION_HOTKEY);

            Assertions.assertEquals(0, requireList(scenario).getCount(), "Single Player asks for an ID first");
        }
    }

    @Test
    void account_run_goes_from_the_title_to_the_main_menu_without_an_id() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Path install = this.createInstall("account");

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(install, ENGLISH, SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX,
                                                                           createCommunity())) {
            scenario.settle();
            scenario.settle();

            Assertions.assertFalse(scenario.getScreenDialog().findControl(LIST) instanceof ListBox, "no ID screen");
            Assertions.assertFalse(scenario.isPopupOpen(), "no New ID");
            Assertions.assertEquals(BeaconLinkGlueScenario.ACCOUNT_NAME, scenario.getClient().getPlayer().getName());
            Assertions.assertFalse(Files.exists(InstallLayout.of(install).getCharacterDirectory()), "no ID is created");
        }
    }
}
