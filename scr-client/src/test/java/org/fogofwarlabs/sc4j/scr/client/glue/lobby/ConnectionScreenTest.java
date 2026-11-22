package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LatencyEstimate;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.glue.OwnText;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks that the connection choice enters Beacon.Link with the run's account (Beacon.Link and Ok open the channel chat at once with a
 * session named after the account), that the lower left panel holds LAN's protocol pane and stays shown while it slides out for Beacon.Link,
 * which has no pane, and that Beacon.Link shows its plain name and a requirements line as the installed ones: SD at 640x480 in English and HD
 * at 1920x1080 in Korean.
 */
@RequiresInstallation
final class ConnectionScreenTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    private static final int SELF_KEY = 1;

    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;
    private static final int LARGE_WIDTH = 1920;
    private static final int LARGE_HEIGHT = 1080;
    /**
     * The drawn time after a selection at which the sliding panel is checked, in milliseconds.
     */
    private static final int SLIDE_CHECK_MILLISECONDS = 60;
    /**
     * The panel of the selected provider's pane and LAN's protocol pane in it.
     */
    private static final int PANE_PANEL = 11;
    private static final int PROTOCOL_PANEL = 17;
    /**
     * The installed requirements line of LAN, whose label ({@code Requirements:}) every provider's line starts with.
     */
    private static final @NotNull String LAN_REQUIREMENTS_KEY = "conUDP_d";
    /**
     * The installed title of the online service, shown with Beacon.Link's plain name.
     */
    private static final @NotNull String BEACON_LINK_TITLE_KEY = "spi_battlenet_title";
    private static final @NotNull String BEACON_LINK_NAME = "Beacon.Link";
    private static final char LABEL_END = ':';
    /**
     * The locales of the mod's own texts.
     */
    private static final @NotNull List<String> TEXT_LOCALES = List.of("enUS", "koKR");
    /**
     * The provider list's first row, Beacon.Link, in dialog pixels.
     */
    private static final float BEACON_LINK_ROW_X = 150;
    private static final float BEACON_LINK_ROW_Y = 100;
    /**
     * The provider list's second row, LAN, in dialog pixels.
     */
    private static final float LAN_ROW_Y = 120;

    @TempDir
    @Nullable Path directory;

    /**
     * Opens the connection choice on LAN and selects Beacon.Link: the lower left panel and its protocol pane show for LAN only.
     */
    private void selectProviders(@NotNull String size, @NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        RecordingCommunitySession community = new RecordingCommunitySession(
                RecordingCommunitySession.createPresence(SELF_KEY, BeaconLinkGlueScenario.ACCOUNT_NAME, BeaconLinkGlueScenario.MOD_ID,
                                                         BeaconLinkGlueScenario.MOD_VERSION, true, PlayerStatus.AVAILABLE, LatencyEstimate.UNKNOWN)
        );

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(size), locale, width, height, qualityPrefix, community)) {
            scenario.show(GlueNavigator.Destination.CONNECTION);

            ScrDialog dialog = scenario.getScreenDialog();
            DialogControl panel = dialog.requireControl(PANE_PANEL).getDefinition();
            DialogControl protocol = dialog.requireControl(PROTOCOL_PANEL).getDefinition();

            Assertions.assertTrue(dialog.isVisible(panel), size);
            Assertions.assertTrue(dialog.isVisible(protocol), size);

            scenario.click(BEACON_LINK_ROW_X, BEACON_LINK_ROW_Y);

            Assertions.assertFalse(dialog.isVisible(panel), size);
            Assertions.assertFalse(dialog.isVisible(protocol), size);
        }
    }

    /**
     * Opens the connection choice, selects Beacon.Link and presses Ok.
     */
    private void enterBeaconLink(@NotNull String name, @NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        RecordingCommunitySession community = new RecordingCommunitySession(
                RecordingCommunitySession.createPresence(SELF_KEY, BeaconLinkGlueScenario.ACCOUNT_NAME, BeaconLinkGlueScenario.MOD_ID,
                                                         BeaconLinkGlueScenario.MOD_VERSION, true, PlayerStatus.AVAILABLE, LatencyEstimate.UNKNOWN)
        );

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(name), locale, width, height, qualityPrefix, community)) {
            scenario.show(GlueNavigator.Destination.CONNECTION);
            scenario.click(BEACON_LINK_ROW_X, BEACON_LINK_ROW_Y);
            scenario.press(Key.ENTER);

            Assertions.assertEquals(List.of(BeaconLinkGlueScenario.ACCOUNT_NAME), scenario.getBeaconLink().getDisplayNames(), name);
            Assertions.assertFalse(scenario.isPopupOpen(), name);
        }
    }

    /**
     * Opens the connection choice on LAN, selects Beacon.Link and LAN again and checks the panel during and after both slides.
     */
    private void slidePane(@NotNull String size, @NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        RecordingCommunitySession community = new RecordingCommunitySession(
                RecordingCommunitySession.createPresence(SELF_KEY, BeaconLinkGlueScenario.ACCOUNT_NAME, BeaconLinkGlueScenario.MOD_ID,
                                                         BeaconLinkGlueScenario.MOD_VERSION, true, PlayerStatus.AVAILABLE, LatencyEstimate.UNKNOWN)
        );

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(size), locale, width, height, qualityPrefix, community)) {
            scenario.show(GlueNavigator.Destination.CONNECTION);

            ScrDialog dialog = scenario.getScreenDialog();
            DialogControl panel = dialog.requireControl(PANE_PANEL).getDefinition();

            // The panel slides out while it stays shown, then hides.
            selectDuringSlide(scenario, BEACON_LINK_ROW_Y, size, dialog, panel);
            scenario.settle();
            Assertions.assertFalse(dialog.isVisible(panel), size);

            // The panel shows at once and slides in.
            selectDuringSlide(scenario, LAN_ROW_Y, size, dialog, panel);
        }
    }

    /**
     * Selects a provider row and draws for {@link #SLIDE_CHECK_MILLISECONDS}; the panel is shown then.
     */
    private static void selectDuringSlide(@NotNull BeaconLinkGlueScenario scenario, float rowY, @NotNull String name, @NotNull ScrDialog dialog,
                                          @NotNull DialogControl panel) {
        scenario.tap(BEACON_LINK_ROW_X, rowY);
        scenario.draw(SLIDE_CHECK_MILLISECONDS);

        Assertions.assertTrue(dialog.isVisible(panel), name);
    }

    @Test
    void beacon_link_shows_its_plain_name_and_a_requirements_line_as_the_installed_ones() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            for (String locale : TEXT_LOCALES) {
                GluText text = GluText.load(storage, locale);
                String installed = text.get(LAN_REQUIREMENTS_KEY);
                String label = installed.substring(0, installed.indexOf(LABEL_END) + 1);

                Assertions.assertEquals(BEACON_LINK_NAME, text.get(BEACON_LINK_TITLE_KEY), locale);
                Assertions.assertTrue(OwnText.CONNECTION_REQUIREMENTS.get(locale).startsWith(label + " "), locale);
            }
        }
    }

    @Test
    void lan_shows_its_protocol_panel_and_beacon_link_shows_no_empty_panel() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        this.selectProviders(SMALL_WIDTH + "x" + SMALL_HEIGHT + "-enUS", "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX);
        this.selectProviders(LARGE_WIDTH + "x" + LARGE_HEIGHT + "-koKR", "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX);
    }

    @Test
    void the_lower_left_panel_slides_out_for_beacon_link_and_back_in_for_lan() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        this.slidePane(SMALL_WIDTH + "x" + SMALL_HEIGHT + "-enUS", "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX);
        this.slidePane(LARGE_WIDTH + "x" + LARGE_HEIGHT + "-koKR", "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX);
    }

    @Test
    void ok_enters_beacon_link_with_the_account_name() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        this.enterBeaconLink(SMALL_WIDTH + "x" + SMALL_HEIGHT + "-enUS", "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX);
        this.enterBeaconLink(LARGE_WIDTH + "x" + LARGE_HEIGHT + "-koKR", "koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX);
    }
}
