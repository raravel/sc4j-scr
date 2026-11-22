package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueScreen;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Checks that the Beacon.Link channel list, opened by the channel tab's Join Channel button, has the screens' framed text field and buttons as
 * its name input and its Ok and Cancel, and that Enter joins the channel pressed in the list: SD at 640x480 in English and HD at 1920x1080 in
 * Korean.
 */
@RequiresInstallation
final class ChannelListPopupTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    private static final int SELF_KEY = 1;
    private static final int KERRIGAN_KEY = 2;
    /**
     * The known channels and their member counts.
     */
    private static final @NotNull String FIRST_CHANNEL = "StarCraft Remastered-1";
    private static final @NotNull String SECOND_CHANNEL = "StarCraft Remastered-2";
    private static final int SHARED_MEMBERS = 58;
    private static final int FIRST_MEMBERS = 23;
    private static final int SECOND_MEMBERS = 4;
    private static final int KOREAN_MEMBERS = 12;
    /**
     * The channel tab's Join Channel button and the list's fourth row ({@link #SECOND_CHANNEL}), in dialog pixels.
     */
    private static final float CHANNEL_BUTTON_X = 500;
    private static final float CHANNEL_BUTTON_Y = 414;
    private static final float ROW_X = 250;
    private static final float FOURTH_ROW_Y = 163;
    /**
     * The popup's Ok and Cancel, and with its name input the controls that are the screens' framed ones.
     */
    private static final int OK_BUTTON = 92;
    private static final int CANCEL_BUTTON = 93;
    private static final int @NotNull [] FRAMED_CONTROLS = {BeaconLinkPopup.INPUT, OK_BUTTON, CANCEL_BUTTON};

    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;
    private static final int LARGE_WIDTH = 1920;
    private static final int LARGE_HEIGHT = 1080;

    private static @NotNull PlayerPresence createPlayer(int key, @NotNull String name) {
        return RecordingCommunitySession.createPresence(key, name, BeaconLinkGlueScenario.MOD_ID, BeaconLinkGlueScenario.MOD_VERSION, true,
                                                        PlayerStatus.AVAILABLE);
    }

    @TempDir
    @Nullable Path directory;

    private void check(@NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan");
        RecordingCommunitySession community = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor"));

        community.addSummary(ChannelChat.SHARED_CHANNEL, SHARED_MEMBERS);
        community.addSummary(FIRST_CHANNEL, FIRST_MEMBERS);
        community.addSummary("Korean", KOREAN_MEMBERS);
        community.addSummary(SECOND_CHANNEL, SECOND_MEMBERS);
        community.setChannelSetup(channel -> channel.addMember(kerrigan));

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(width + "x" + height + "-" + locale), locale, width, height,
                                                                           qualityPrefix, community)) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.click(CHANNEL_BUTTON_X, CHANNEL_BUTTON_Y);
            scenario.click(ROW_X, FOURTH_ROW_Y);

            Assertions.assertTrue(scenario.isPopupOpen());

            ScrDialog popup = scenario.getPopupDialogs().getFirst();

            for (int control : FRAMED_CONTROLS) {
                ScrControl found = popup.findControl(control);

                Assertions.assertNotNull(found);
                Assertions.assertTrue(ScrDialogControlUtils.hasFlag(found.getDefinition(), GlueScreen.FRAMED_FLAG),
                                      "The popup's control " + control + " is the screens' framed control.");
            }

            scenario.press(Key.ENTER);

            Assertions.assertFalse(scenario.isPopupOpen());
            Assertions.assertNotNull(community.findChannel(SECOND_CHANNEL), "Enter joins the pressed channel.");
            Assertions.assertNull(community.findChannel(FIRST_CHANNEL), "Joining a channel leaves the previous one.");
        }
    }

    @Test
    void channel_list_popup_has_framed_controls_and_joins_the_pressed_channel() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        this.check("enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX);
        this.check("koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX);
    }
}
