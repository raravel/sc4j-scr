package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatLineKind;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.engine.net.lobby.JoinRefusal;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.session.setup.GameTemplate;
import org.fogofwarlabs.sc4j.scr.net.beaconlink.ScrRoomData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Checks that the Beacon.Link room list's search row starts under its title as the local network game list's list does, and that a double
 * press on a room while the list slides away starts no join.
 */
@RequiresInstallation
final class RoomListScreenTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    /**
     * The key bytes of the players.
     */
    private static final int SELF_KEY = 1;
    private static final int KERRIGAN_KEY = 2;
    private static final int TASSADAR_KEY = 3;
    /**
     * The estimated round trip time to a host, in milliseconds.
     */
    private static final long FAIR_MILLISECONDS = 150;
    private static final int FASTEST_SPEED = 6;
    private static final int MAP_SIZE = 128;
    /**
     * The room list's title and search field, and how far the local network game list's list starts under the top of its title
     * ({@code gluJoin}: the title at {@code 60}, the list at {@code 92}).
     */
    private static final int TITLE = 103;
    private static final int SEARCH = 90;
    /**
     * The room list's Cancel (the dialog's control {@code 10}), and the middle of the first room row below the search field's bottom: the
     * page's inset ({@code 4}), the header ({@code 12}) and half a row ({@code 17}).
     */
    private static final int CANCEL = 10;
    private static final float FIRST_ROW_BELOW_SEARCH = 24;
    private static final float ROW_INSET = 20;
    private static final float CENTER_DIVISOR = 2;
    private static final int LAN_LIST_UNDER_TITLE = 32;
    /**
     * The channel's earlier lines, more than the chat under the rooms shows, so its log scrolls to the end above the input.
     */
    private static final @NotNull List<String> EARLIER_LINES = List.of("hi all", "anyone on Fighting Spirit?", "1v1 me", "gg wp", "rematch?",
                                                                       "sure", "lag again", "fixed now", "host left", "brb");

    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;

    private static @NotNull PlayerPresence createPlayer(int key, @NotNull String name, @NotNull PlayerStatus status) {
        return RecordingCommunitySession.createPresence(key, name, BeaconLinkGlueScenario.MOD_ID, BeaconLinkGlueScenario.MOD_VERSION, true,
                                                        status, LatencyEstimate.UNKNOWN);
    }

    private static @NotNull LinkParticipant createHost(int key) {
        byte[] bytes = new byte[LinkPlayer.KEY_LENGTH];

        Arrays.fill(bytes, (byte) key);

        return LinkParticipant.of(LinkPlayer.of(bytes), key);
    }

    private static @NotNull FoundRoom createRoom(@NotNull LinkParticipant host, @NotNull String name, @NotNull String map, int players,
                                                 int capacity, @NotNull ScrRoomData data, @Nullable JoinRefusal refusal,
                                                 @NotNull LatencyEstimate latency) {
        String version = refusal == null ? BeaconLinkGlueScenario.MOD_VERSION : "0.0.9";

        return new FoundRoom(host, 1, new RoomSettings(name, map, players, capacity, data.encode()), BeaconLinkGlueScenario.MOD_ID, version,
                             null, refusal, latency);
    }

    @TempDir
    @Nullable Path directory;

    private @NotNull RecordingCommunitySession createCommunity(@NotNull PlayerPresence kerrigan) {
        PlayerPresence tassadar = createPlayer(TASSADAR_KEY, "Tassadar", PlayerStatus.IN_ROOM);
        RecordingCommunitySession community = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor", PlayerStatus.AVAILABLE));

        community.setChannelSetup(channel -> {
            channel.addMember(kerrigan);
            channel.addMember(tassadar);

            for (int index = 0; index < EARLIER_LINES.size(); index++) {
                channel.receiveLine(index % 2 == 0 ? tassadar : kerrigan, ChatLineKind.SAY, EARLIER_LINES.get(index));
            }

            channel.receiveLine(kerrigan, ChatLineKind.SAY, "made it, come in");
            channel.receiveLine(tassadar, ChatLineKind.SAY, "joining");
        });

        return community;
    }

    @Test
    void search_row_starts_under_the_title_as_the_local_network_game_list_starts_its_list() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Path root = this.directory;

        Assertions.assertNotNull(root);

        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.IN_ROOM);

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve("search-row"), "enUS", SMALL_WIDTH, SMALL_HEIGHT,
                                                                           GlueCanvas.SD_PREFIX, this.createCommunity(kerrigan))) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.show(GlueNavigator.Destination.GAME_LIST);

            ScrControl title = scenario.getScreenDialog().findControl(TITLE);
            ScrControl search = scenario.getScreenDialog().findControl(SEARCH);

            Assertions.assertNotNull(title);
            Assertions.assertNotNull(search);
            Assertions.assertEquals(LAN_LIST_UNDER_TITLE, search.getDefinition().getY() - title.getDefinition().getY());
        }
    }

    @Test
    void double_press_on_a_room_while_the_list_slides_away_starts_no_join() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Path root = this.directory;

        Assertions.assertNotNull(root);

        PlayerPresence kerrigan = createPlayer(KERRIGAN_KEY, "Kerrigan", PlayerStatus.IN_ROOM);
        FoundRoom room = createRoom(createHost(TASSADAR_KEY), "2v2 team game", "Circuit Breakers", 3, 4,
                                    new ScrRoomData(GameTemplate.TYPE_MELEE, 1, FASTEST_SPEED, MAP_SIZE, MAP_SIZE), null,
                                    LatencyEstimate.of(FAIR_MILLISECONDS));

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root.resolve("slide-join"), "enUS", SMALL_WIDTH, SMALL_HEIGHT,
                                                                           GlueCanvas.SD_PREFIX, this.createCommunity(kerrigan), List.of(room))) {
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.show(GlueNavigator.Destination.GAME_LIST);

            ScrDialog dialog = scenario.getScreenDialog();
            ScrControl cancel = dialog.findControl(CANCEL);
            ScrControl search = dialog.findControl(SEARCH);

            Assertions.assertNotNull(cancel);
            Assertions.assertNotNull(search);

            DialogControl cancelDefinition = cancel.getDefinition();
            DialogControl searchDefinition = search.getDefinition();

            // Cancel slides the list away; a join of the session's recorded network would throw, as it carries no game.
            scenario.tap(cancelDefinition.getX() + cancelDefinition.getWidth() / CENTER_DIVISOR,
                         cancelDefinition.getY() + cancelDefinition.getHeight() / CENTER_DIVISOR);
            scenario.doubleTap(searchDefinition.getX() + ROW_INSET, searchDefinition.getY() + searchDefinition.getHeight() + FIRST_ROW_BELOW_SEARCH);
            scenario.settle();

            Assertions.assertNotSame(dialog, scenario.getScreenDialog(), "the list slid away to the channel chat");
        }
    }
}
