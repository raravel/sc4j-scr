package org.fogofwarlabs.sc4j.scr.client.glue;

import org.fogofwarlabs.sc4j.engine.client.audio.AudioMixer;
import org.fogofwarlabs.sc4j.engine.client.audio.SilentAudioDevice;
import org.fogofwarlabs.sc4j.engine.client.host.ClientHost;
import org.fogofwarlabs.sc4j.engine.client.host.HostWindow;
import org.fogofwarlabs.sc4j.engine.client.host.WindowSettings;
import org.fogofwarlabs.sc4j.scr.client.BeaconLinkAccess;
import org.fogofwarlabs.sc4j.scr.client.ClientContext;
import org.fogofwarlabs.sc4j.scr.client.audio.ClientAudio;
import org.fogofwarlabs.sc4j.scr.client.audio.SoundSettings;
import org.fogofwarlabs.sc4j.scr.client.configuration.InstallLayout;
import org.fogofwarlabs.sc4j.scr.client.glue.lobby.BeaconLinkGlueScenario;
import org.fogofwarlabs.sc4j.scr.client.glue.score.GlueResume;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.game.session.setup.GameTemplate;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.load.ScrIscriptImporter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;


/**
 * Checks that the glue shows the campaign-link error over the campaign screen it opens when a next scenario cannot be loaded.
 */
@RequiresInstallation
final class GlueInterfaceTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    private static final int WINDOW_WIDTH = 640;
    private static final int WINDOW_HEIGHT = 480;

    private static final @NotNull String LOCALE = "enUS";
    private static final @NotNull String PLAYER_NAME = "player";
    private static final @NotNull String PLAYED_MAP_NAME = "chain1.scx";
    private static final @NotNull String MISSING_SCENARIO_NAME = "missing.scx";
    private static final int TERRAN = 1;
    /**
     * The popups over the campaign screen: the one error box.
     */
    private static final int POPUP_COUNT = 1;

    /**
     * Creates the hidden window of an OpenGL core profile context.
     *
     * @param host the host
     * @return the created window
     */
    private static @NotNull HostWindow createHiddenWindow(@NotNull ClientHost host) {
        return host.createWindow(WindowSettings.builder(GlueInterfaceTest.class.getSimpleName(), WINDOW_WIDTH, WINDOW_HEIGHT).build());
    }

    /**
     * Creates client audio on a silent device: the glue plays its sounds on the host clock without output.
     *
     * @return the client audio, which the client context closes
     */
    private static @NotNull ClientAudio createSilentAudio() {
        return new ClientAudio(new AudioMixer(new SilentAudioDevice(System::nanoTime)), new SoundSettings());
    }


    @TempDir
    @Nullable Path directory;

    @Test
    void open_should_show_campaign_link_error_when_next_scenario_cannot_be_loaded() throws IOException {
        this.checkCampaignLinkError(this.createMissingScenarioResume(), null);
    }

    @Test
    void leave_game_should_show_campaign_link_error_when_next_scenario_cannot_be_loaded() throws IOException {
        this.checkCampaignLinkError(null, this.createMissingScenarioResume());
    }

    /**
     * Creates the resume after a custom game whose next scenario names a missing map next to the played one.
     *
     * @return the resume
     */
    private @NotNull GlueResume createMissingScenarioResume() {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        GlueGameRequest played = GlueGameRequest.createCustom(root.resolve(PLAYED_MAP_NAME),
                                                              PLAYER_NAME,
                                                              true,
                                                              GameTemplate.USE_MAP_SETTINGS,
                                                              TERRAN,
                                                              new int[0]);

        return GlueResume.createAfterGame(played, true).withNextScenario(MISSING_SCENARIO_NAME);
    }

    /**
     * Opens a glue at a resume, optionally leaves a game with another resume, and checks the popups over the current screen.
     *
     * @param openResume  the screen the glue opens at, or {@code null} for the title screen
     * @param leaveResume the screen a left game continues at, or {@code null} to leave no game
     * @throws IOException if an installed asset cannot be read
     */
    private void checkCampaignLinkError(@Nullable GlueResume openResume, @Nullable GlueResume leaveResume) throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Path installationPath = ExternalInput.INSTALLATION.getPath();
        Path root = this.directory;

        Assertions.assertNotNull(installationPath);
        Assertions.assertNotNull(root);

        ClientHost host = ClientHost.initialize();

        try {
            HostWindow window = createHiddenWindow(host);

            window.makeContextCurrent();
            host.createCapabilities();

            InstallLayout layout = InstallLayout.of(root);
            GlueFolders folders = new GlueFolders(layout.getCharacterDirectory(),
                                                  layout.getMapDirectory(),
                                                  layout.getReplayDirectory(),
                                                  layout.getSaveDirectory(),
                                                  layout.getRecentMapsFile());

            try (ClientContext client = ClientContext.open(layout, ScrResourceProvider.open(installationPath, ScrIscriptImporter::readLibrary), LOCALE,
                                                           createSilentAudio(), BeaconLinkAccess.NONE, BeaconLinkGlueScenario.UNKNOWN_RELEASE);
                 GlueInterface glue = GlueInterface.open(client, LOCALE, folders, openResume)) {
                if (leaveResume != null) {
                    glue.enterGame();
                    glue.leaveGame(leaveResume);
                }

                Assertions.assertTrue(glue.isPopupOpen(), "The campaign-link error is open over the campaign screen.");
                Assertions.assertEquals(POPUP_COUNT, glue.getPopupLayers().size());
            }
        } finally {
            host.close();
        }
    }
}
