package org.fogofwarlabs.sc4j.scr.client.glue.menu;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.LatencyEstimate;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkAccount;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.scr.client.configuration.InstallLayout;
import org.fogofwarlabs.sc4j.scr.client.glue.CharacterFile;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.glue.lobby.BeaconLinkGlueScenario;
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

/**
 * Checks that the main menu's Single Player opens the campaigns at once, playing with the character file of the account.
 */
@RequiresInstallation
final class MainMenuScreenTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    private static final int SELF_KEY = 1;

    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;
    /**
     * The hotkeys of Single Player and of the Expansion choice in English.
     */
    private static final @NotNull String SINGLE_PLAYER_HOTKEY = "s";
    private static final @NotNull String EXPANSION_HOTKEY = "e";

    private static @NotNull RecordingCommunitySession createCommunity() {
        return new RecordingCommunitySession(RecordingCommunitySession.createPresence(SELF_KEY, BeaconLinkGlueScenario.ACCOUNT_NAME,
                                                                                      BeaconLinkGlueScenario.MOD_ID,
                                                                                      BeaconLinkGlueScenario.MOD_VERSION, true,
                                                                                      PlayerStatus.AVAILABLE, LatencyEstimate.UNKNOWN));
    }

    @TempDir
    @Nullable Path directory;

    @Test
    void single_player_opens_the_campaigns_with_the_account_character_without_asking_for_an_id() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Path root = this.directory;

        Assertions.assertNotNull(root);

        Path install = root.resolve("single-player");

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(install, "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX,
                                                                           createCommunity())) {
            LinkAccount account = scenario.getBeaconLink().getAccount();

            Assertions.assertNotNull(account);

            scenario.show(GlueNavigator.Destination.MAIN_MENU);
            scenario.type(SINGLE_PLAYER_HOTKEY);

            Assertions.assertTrue(scenario.isPopupOpen(), "Single Player asks for Original or Expansion");

            scenario.type(EXPANSION_HOTKEY);

            Assertions.assertFalse(scenario.isPopupOpen(), "no ID is asked for");

            Path character = InstallLayout.of(install).getCharacterDirectory().resolve(account.getId() + CharacterFile.EXTENSION);
            CharacterFile read = CharacterFile.read(character);

            Assertions.assertTrue(Files.isRegularFile(character), "the campaigns play with the account's character file");
            Assertions.assertNotNull(read);
            Assertions.assertEquals(BeaconLinkGlueScenario.ACCOUNT_NAME, read.getName());
        }
    }
}
