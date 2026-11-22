package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueSettings;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks the multiplayer create flags read from client settings, and that the Beacon.Link create page's controls end inside its panel art's
 * frame with its rows from the page's content top and that its private radio button shows the password field (GPU, hidden context).
 */
final class CreateGameScreenTest {
    private static final @NotNull String CUSTOM_HOTKEYS_OPTION = "game.customHotkeys";
    private static final @NotNull String EXTENDED_UNIT_LIMITS_OPTION = "game.extendedUnitLimits";

    /**
     * The created game's flags for disabled custom hotkeys and for original unit limits.
     */
    private static final int HOTKEYS_DISABLED_FLAG = 1;
    private static final int ORIGINAL_LIMITS_FLAG = 4;

    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    private static final int SELF_KEY = 1;
    /**
     * The create page and the map browser of the Beacon.Link create dialog, and their controls: the create page's controls (the game name, the
     * radio buttons, the password, the map drop-down and its folder button, the type and subtype drop-downs, the sliders, the title, the
     * labels and the slider rows' texts), the map browser's title and open folder.
     */
    private static final int FORM_PAGE = 22;
    private static final int GAME_NAME = 90;
    private static final int MAP_CHOICE = 91;
    private static final int FOLDER_BUTTON = 92;
    private static final int PRIVACY_PRIVATE = 101;
    private static final int PASSWORD = 102;
    private static final int FORM_TITLE = 110;
    private static final int BROWSER_TITLE = 127;
    private static final int BROWSER_FOLDER = 6;
    private static final int @NotNull [] FORM_CONTROLS = {GAME_NAME, MAP_CHOICE, FOLDER_BUTTON, 94, 95, 96, 97, 100, PRIVACY_PRIVATE, PASSWORD, 103,
                                                          16, 17, 18, FORM_TITLE, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123,
                                                          124, 125, 126};
    /**
     * The frame lines of the create page's panel art ({@code glue\create\pListMap4}) in page pixels: left, right and bottom.
     */
    private static final int FRAME_LEFT = 2;
    private static final int FRAME_RIGHT = 329;
    private static final int FRAME_BOTTOM = 249;
    /**
     * The local network game list's list starts this far under the top of its title ({@code gluJoin}: the title at {@code 60}, the list at
     * {@code 92}).
     */
    private static final int LAN_LIST_UNDER_TITLE = 32;

    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;

    private static @NotNull DialogControl getDefinition(@NotNull ScrDialog dialog, int id) {
        ScrControl control = dialog.findControl(id);

        Assertions.assertNotNull(control, "The create dialog has control " + id + ".");

        return control.getDefinition();
    }

    @TempDir
    @Nullable Path directory;

    /**
     * Opens the create page over Beacon.Link after the channel chat.
     */
    private @NotNull BeaconLinkGlueScenario openCreatePage(@NotNull String name, @NotNull String locale, int width, int height,
                                                           @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        RecordingCommunitySession community = new RecordingCommunitySession(
                RecordingCommunitySession.createPresence(SELF_KEY, "Raynor", BeaconLinkGlueScenario.MOD_ID, BeaconLinkGlueScenario.MOD_VERSION,
                                                         true, PlayerStatus.AVAILABLE)
        );
        BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(name), locale, width, height, qualityPrefix, community);

        scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
        scenario.show(GlueNavigator.Destination.CREATE_GAME);

        return scenario;
    }

    @Test
    void read_game_flags_should_be_zero_when_settings_are_unset() {
        Assertions.assertEquals(0, CreateGameScreen.readGameFlags(GlueSettings.createInMemory()));
    }

    @Test
    void read_game_flags_should_set_flag_for_each_setting_turned_off() {
        GlueSettings settings = GlueSettings.createInMemory(Map.of(CUSTOM_HOTKEYS_OPTION, false, EXTENDED_UNIT_LIMITS_OPTION, false));

        Assertions.assertEquals(HOTKEYS_DISABLED_FLAG | ORIGINAL_LIMITS_FLAG, CreateGameScreen.readGameFlags(settings));

        settings = GlueSettings.createInMemory(Map.of(CUSTOM_HOTKEYS_OPTION, true, EXTENDED_UNIT_LIMITS_OPTION, false));

        Assertions.assertEquals(ORIGINAL_LIMITS_FLAG, CreateGameScreen.readGameFlags(settings));
    }

    @Test
    @RequiresInstallation
    void create_page_controls_end_inside_the_panel_frame_and_start_at_the_page_content_top() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        try (BeaconLinkGlueScenario scenario = this.openCreatePage("create-rows", "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX)) {
            ScrDialog dialog = scenario.getScreenDialog();
            DialogControl page = getDefinition(dialog, FORM_PAGE);
            List<String> outside = new ArrayList<>();

            for (int id : FORM_CONTROLS) {
                ScrControl control = dialog.findControl(id);

                Assertions.assertNotNull(control);

                DialogControl definition = control.getDefinition();

                // The loaded place is the row's with the subtype's row shown, the shifted place without it.
                for (int top : new int[]{definition.getY(), definition.getY() + control.getVerticalShift()}) {
                    if (definition.getX() <= page.getX() + FRAME_LEFT || definition.getX() + definition.getWidth() > page.getX() + FRAME_RIGHT ||
                        top < page.getY() || top + definition.getHeight() > page.getY() + FRAME_BOTTOM) {
                        outside.add(id + " at " + (definition.getX() - page.getX()) + "," + (top - page.getY()));
                    }
                }
            }

            Assertions.assertEquals(List.of(), outside, "Create page controls (page pixels) outside the panel art's frame.");
            Assertions.assertEquals(LAN_LIST_UNDER_TITLE, getDefinition(dialog, GAME_NAME).getY() - getDefinition(dialog, FORM_TITLE).getY());
            Assertions.assertEquals(LAN_LIST_UNDER_TITLE, getDefinition(dialog, BROWSER_FOLDER).getY() - getDefinition(dialog, BROWSER_TITLE).getY());
        }
    }

    @Test
    @RequiresInstallation
    void private_radio_button_shows_the_password_field() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        try (BeaconLinkGlueScenario scenario = this.openCreatePage("create-private", "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX)) {
            ScrDialog dialog = scenario.getScreenDialog();
            DialogControl privateRadio = getDefinition(dialog, PRIVACY_PRIVATE);

            scenario.click(privateRadio.getX() + privateRadio.getWidth() / 2f, privateRadio.getY() + privateRadio.getHeight() / 2f);

            ScrControl field = dialog.findControl(PASSWORD);

            Assertions.assertNotNull(field);
            Assertions.assertTrue(field.isVisible(), "The password field shows for a private game.");
        }
    }
}
