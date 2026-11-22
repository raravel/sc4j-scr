package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkFailure;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.Whisper;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
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
 * Checks that the Beacon.Link channel chat's status drop-down sets this player away without pressing the tab behind its open list, that a
 * typed whisper start makes the input whisper to its player (Backspace in the empty input says lines in the channel again) and a member's
 * context menu blocks the member, and that a session refused for too many devices of the account shows the failure, which Ok closes: SD at
 * 640x480 in English and HD at 1920x1080 in Korean.
 */
@RequiresInstallation
final class ChannelChatScreenTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    /**
     * The key bytes of the players.
     */
    private static final int SELF_KEY = 1;
    private static final int KERRIGAN_KEY = 2;
    private static final int TASSADAR_KEY = 3;

    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;
    private static final int LARGE_WIDTH = 1920;
    private static final int LARGE_HEIGHT = 1080;
    /**
     * The status drop-down's bar, its Away item over the Friends tab, and the tab's button, in dialog pixels; a channel the channel list
     * joins by its typed name.
     */
    private static final float STATUS_X = 460;
    private static final float STATUS_Y = 42;
    private static final float AWAY_Y = 72;
    private static final float TAB_BUTTON_X = 500;
    private static final float TAB_BUTTON_Y = 414;
    private static final @NotNull String TYPED_CHANNEL = "Lounge";
    /**
     * The channel tab's third member row (this player, Kerrigan, Tassadar below the channel's label) and the third action of its context
     * menu (Block, after Whisper and Add Friend) below the press that opened it: the name's row and two action rows, then half a row.
     */
    private static final float MEMBER_ROW_X = 500;
    private static final float THIRD_MEMBER_ROW_Y = 171;
    private static final float MENU_X = MEMBER_ROW_X + 20;
    private static final float BLOCK_ACTION_Y = THIRD_MEMBER_ROW_Y + 63;

    private static @NotNull PlayerPresence createPlayer(int key, @NotNull String name, @NotNull PlayerStatus status) {
        return RecordingCommunitySession.createPresence(key, name, BeaconLinkGlueScenario.MOD_ID, BeaconLinkGlueScenario.MOD_VERSION, true,
                                                        status);
    }

    @TempDir
    @Nullable Path directory;

    /**
     * Opens the status drop-down, chooses Away where its list lies over the Friends tab, then opens the tab's popup and joins a typed
     * channel: only the channel tab's popup (the channel list) joins one.
     */
    private void chooseAway(@NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        String name = width + "x" + height + "-" + locale;
        RecordingCommunitySession community = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor", PlayerStatus.AVAILABLE));

        community.setChannelSetup(channel -> channel.addMember(createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.AVAILABLE)));

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(name), locale, width, height, qualityPrefix, community)) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.click(STATUS_X, STATUS_Y);
            scenario.click(STATUS_X, AWAY_Y);

            Assertions.assertEquals(PlayerStatus.AWAY, community.getSelf().getStatus(), name);

            scenario.click(TAB_BUTTON_X, TAB_BUTTON_Y);
            scenario.type(TYPED_CHANNEL);
            scenario.press(Key.ENTER);

            Assertions.assertNotNull(community.findChannel(TYPED_CHANNEL), name);
        }
    }

    /**
     * Types a whisper start and two lines (both whispered), ends the whisper with Backspace and says a line; then blocks Tassadar from the
     * context menu.
     */
    private void whisperAndBlock(@NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        String name = width + "x" + height + "-" + locale;
        Path install = root.resolve(name);
        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.AVAILABLE);
        PlayerPresence tassadar = createPlayer(TASSADAR_KEY, "Tassadar", PlayerStatus.AVAILABLE);
        RecordingCommunitySession community = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor", PlayerStatus.AVAILABLE));

        community.setChannelSetup(channel -> {
            channel.addMember(kerrigan);
            channel.addMember(tassadar);
        });

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(install, locale, width, height, qualityPrefix, community)) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);

            RecordingCommunitySession.Channel channel = community.findChannel(ChannelChat.findDefaultChannel("StarCraft Remastered", List.of()));

            Assertions.assertNotNull(channel);

            scenario.type("/w " + kerrigan.getTaggedName() + " gl hf");
            scenario.press(Key.ENTER);
            scenario.type("again");
            scenario.press(Key.ENTER);
            scenario.press(Key.BACKSPACE);
            scenario.type("hello");
            scenario.press(Key.ENTER);

            Assertions.assertEquals(List.of("gl hf", "again"), community.getWhispers().stream().map(Whisper::getText).toList(), name);
            Assertions.assertEquals(List.of("hello"), channel.getSaid(), name);

            scenario.rightClick(MEMBER_ROW_X, THIRD_MEMBER_ROW_Y);
            scenario.hover(MENU_X, BLOCK_ACTION_Y);
            scenario.click(MENU_X, BLOCK_ACTION_Y);

            Assertions.assertEquals(List.of(tassadar.getPlayer()), community.getIgnored(), name);
        }
    }

    @Test
    void too_many_devices_shows_the_failure_and_ok_closes_it() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        this.showTooManyDevices("enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX);
        this.showTooManyDevices("koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX);
    }

    private void showTooManyDevices(@NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        String name = width + "x" + height + "-" + locale;
        RecordingCommunitySession community = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor", PlayerStatus.AVAILABLE));

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(name), locale, width, height, qualityPrefix, community)) {
            scenario.getBeaconLink().setFailure(LinkFailure.TOO_MANY_CONNECTIONS);
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);

            Assertions.assertTrue(scenario.isPopupOpen(), name);

            scenario.press(Key.ENTER);
            scenario.settle();

            Assertions.assertFalse(scenario.isPopupOpen(), name);
        }
    }

    @Test
    void status_drop_down_sets_away_without_pressing_the_tab_behind_its_list() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        this.chooseAway("enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX);
        this.chooseAway("koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX);
    }

    @Test
    void typed_whisper_start_and_context_menu_block_follow_the_input_prefix_and_the_block_list() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        this.whisperAndBlock("enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX);
        this.whisperAndBlock("koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX);
    }
}
