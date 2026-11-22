package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.LatencyEstimate;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.session.setup.LobbyResolution;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup.PlayerRecord;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Checks the LAN lobby's disabled Start of occupied slots with an unchosen race, and that a lobby screen without a joined game shows the join
 * error.
 */
final class GameLobbyScreenTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    private static final int OPEN = LobbyResolution.TYPE_OPEN;
    private static final int SELF_KEY = 1;
    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;

    /**
     * The player ids of a slot without a player, of the host and of the joiner.
     */
    private static final int NO_PLAYER = -1;
    private static final int HOST_PLAYER = 0;
    private static final int JOINER_PLAYER = 1;
    /**
     * The race bytes: Zerg, Terran, Protoss, Random and the lobby's unselected race.
     */
    private static final int ZERG_RACE = 0;
    private static final int TERRAN_RACE = 1;
    private static final int PROTOSS_RACE = 2;
    private static final int RANDOM_RACE = 6;
    private static final int UNSELECTED_RACE = 7;
    private static final int NO_TEAM = 0;

    /**
     * Creates all lobby slots, retaining occupied records and filling the rest as open.
     *
     * @param occupied the occupied slots in their existing order
     * @return the fully populated lobby records
     */
    private static @NotNull PlayerRecord @NotNull [] createLobbyRecords(@NotNull PlayerRecord @NotNull ... occupied) {
        PlayerRecord[] records = new PlayerRecord[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        for (int slot = 0; slot < records.length; slot++) {
            records[slot] = slot < occupied.length ? occupied[slot] : new PlayerRecord(NO_PLAYER, OPEN, UNSELECTED_RACE, NO_TEAM, "");
        }

        return records;
    }

    @TempDir
    @Nullable Path directory;

    @Test
    @RequiresInstallation
    void lobby_without_a_joined_game_shows_the_join_error() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Path root = this.directory;

        Assertions.assertNotNull(root);

        RecordingCommunitySession community = new RecordingCommunitySession(
                RecordingCommunitySession.createPresence(SELF_KEY, BeaconLinkGlueScenario.ACCOUNT_NAME, BeaconLinkGlueScenario.MOD_ID,
                                                         BeaconLinkGlueScenario.MOD_VERSION, true, PlayerStatus.AVAILABLE, LatencyEstimate.UNKNOWN)
        );

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root, "enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX, community)) {
            // The channel chat opens the client on the network, which has joined no game.
            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            scenario.show(GlueNavigator.Destination.GAME_LOBBY);

            Assertions.assertTrue(scenario.isPopupOpen(), "the lobby screen shows the join error");
        }
    }

    @Test
    void are_races_chosen_should_be_false_for_host_with_select_race() {
        PlayerRecord[] records = createLobbyRecords(new PlayerRecord(HOST_PLAYER, MeleeGameSetup.TYPE_HUMAN, UNSELECTED_RACE, NO_TEAM, ""));

        Assertions.assertFalse(GameLobbyScreen.areRacesChosen(slot -> records[slot]));
    }

    @Test
    void are_races_chosen_should_be_false_for_joiner_with_select_race() {
        PlayerRecord[] records = createLobbyRecords(new PlayerRecord(HOST_PLAYER, MeleeGameSetup.TYPE_HUMAN, TERRAN_RACE, NO_TEAM, ""),
                                                    new PlayerRecord(JOINER_PLAYER, MeleeGameSetup.TYPE_HUMAN, UNSELECTED_RACE, NO_TEAM, ""));

        Assertions.assertFalse(GameLobbyScreen.areRacesChosen(slot -> records[slot]));
    }

    @Test
    void are_races_chosen_should_be_false_for_computer_with_select_race() {
        PlayerRecord[] records = createLobbyRecords(new PlayerRecord(HOST_PLAYER, MeleeGameSetup.TYPE_HUMAN, ZERG_RACE, NO_TEAM, ""),
                                                    new PlayerRecord(NO_PLAYER, MeleeGameSetup.TYPE_COMPUTER, UNSELECTED_RACE, NO_TEAM, ""));

        Assertions.assertFalse(GameLobbyScreen.areRacesChosen(slot -> records[slot]));
    }

    @Test
    void are_races_chosen_should_be_true_for_chosen_races_whatever_open_slots() {
        // Random is a chosen race; open slots keep the unselected race and do not count. A lone host is enabled too: the
        // player count is refused on press (gameNOTENOUGHPLAYERS), not by the disabled state.
        Assertions.assertTrue(GameLobbyScreen.areRacesChosen(
                slot -> createLobbyRecords(new PlayerRecord(HOST_PLAYER, MeleeGameSetup.TYPE_HUMAN, RANDOM_RACE, NO_TEAM, ""))[slot]
        ));

        PlayerRecord[] records = createLobbyRecords(new PlayerRecord(HOST_PLAYER, MeleeGameSetup.TYPE_HUMAN, PROTOSS_RACE, NO_TEAM, ""),
                                                    new PlayerRecord(JOINER_PLAYER, MeleeGameSetup.TYPE_HUMAN, ZERG_RACE, NO_TEAM, ""));

        Assertions.assertTrue(GameLobbyScreen.areRacesChosen(slot -> records[slot]));
    }
}
