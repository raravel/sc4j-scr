package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
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
 * Checks that the add-friend popup, opened by the chat friends tab's Add Friend button, adds the suggested channel member pressed into its name
 * input on Enter and closes, and that the Remove Friend of the first friend's context menu removes the friend: SD at 640x480 in English and HD
 * at 1920x1080 in Korean.
 */
@RequiresInstallation
final class AddFriendPopupTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    private static final int SELF_KEY = 1;
    private static final int KERRIGAN_KEY = 2;
    private static final int TASSADAR_KEY = 3;
    private static final int ZERATUL_KEY = 4;
    private static final int ARTANIS_KEY = 5;
    /**
     * The chat's friends tab, the friends tab's first friend (below the Online group's label), the tab's button and the popup's first
     * suggested member (below the group's label), in dialog pixels.
     */
    private static final float FRIENDS_TAB_X = 445;
    private static final float FRIENDS_TAB_Y = 72;
    private static final float TAB_ROW_X = 500;
    private static final float FIRST_FRIEND_ROW_Y = 123;
    private static final float TAB_BUTTON_X = 500;
    private static final float TAB_BUTTON_Y = 414;
    private static final float POPUP_ROW_X = 250;
    private static final float FIRST_SUGGESTED_ROW_Y = 115;
    /**
     * The context menu's second action (Remove Friend) below the press that opened it: the name's row and one action row, then half a row.
     */
    private static final float MENU_X = TAB_ROW_X + 20;
    private static final float REMOVE_ACTION_Y = FIRST_FRIEND_ROW_Y + 45;

    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;
    private static final int LARGE_WIDTH = 1920;
    private static final int LARGE_HEIGHT = 1080;

    private static @NotNull PlayerPresence createPlayer(int key, @NotNull String name, @NotNull PlayerStatus status) {
        return RecordingCommunitySession.createPresence(key, name, BeaconLinkGlueScenario.MOD_ID, BeaconLinkGlueScenario.MOD_VERSION, true,
                                                        status);
    }

    @TempDir
    @Nullable Path directory;

    private void check(@NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path root = this.directory;

        Assertions.assertNotNull(root);

        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.AVAILABLE);
        PlayerPresence tassadar = createPlayer(TASSADAR_KEY, "Tassadar", PlayerStatus.AWAY);
        PlayerPresence zeratul = RecordingCommunitySession.createPresence(ZERATUL_KEY, "Zeratul", "scrdemo", "0.0.9", false, PlayerStatus.IN_GAME);
        PlayerPresence artanis = createPlayer(ARTANIS_KEY, "Artanis", PlayerStatus.AVAILABLE);
        RecordingCommunitySession community = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor", PlayerStatus.AVAILABLE));

        community.addFriend(kerrigan.getPlayer());
        community.addFriend(zeratul.getPlayer());
        community.addFriend(artanis.getPlayer());
        community.setChannelSetup(channel -> {
            channel.addMember(kerrigan);
            channel.addMember(tassadar);
            channel.addMember(zeratul);
        });

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve(width + "x" + height + "-" + locale), locale, width, height,
                                                                           qualityPrefix, community)) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.click(FRIENDS_TAB_X, FRIENDS_TAB_Y);
            scenario.click(TAB_ROW_X, FIRST_FRIEND_ROW_Y);
            scenario.click(TAB_BUTTON_X, TAB_BUTTON_Y);
            scenario.click(POPUP_ROW_X, FIRST_SUGGESTED_ROW_Y);

            Assertions.assertTrue(scenario.isPopupOpen());

            scenario.press(Key.ENTER);

            Assertions.assertEquals(List.of(kerrigan.getPlayer(), zeratul.getPlayer(), artanis.getPlayer(), tassadar.getPlayer()), community.getFriends());
            Assertions.assertFalse(scenario.isPopupOpen());

            scenario.rightClick(TAB_ROW_X, FIRST_FRIEND_ROW_Y);
            scenario.hover(MENU_X, REMOVE_ACTION_Y);
            scenario.click(MENU_X, REMOVE_ACTION_Y);

            Assertions.assertEquals(List.of(zeratul.getPlayer(), artanis.getPlayer(), tassadar.getPlayer()), community.getFriends());
        }
    }

    @Test
    void add_friend_popup_adds_the_pressed_member_and_friend_menu_removes_the_friend() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        this.check("enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX);
        this.check("koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX);
    }
}
