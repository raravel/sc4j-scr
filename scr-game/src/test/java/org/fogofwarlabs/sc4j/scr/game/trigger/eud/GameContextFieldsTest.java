package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameContext;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Checks snapshot completion and retry through the game-context bytes retained by the saved-game format.
 */
final class GameContextFieldsTest {
    private static final int MAP_DIMENSION = 8;
    private static final int TILESET = 0;
    private static final int RANDOM_SEED = 1;
    private static final int LOBBY_SLOT = 2;
    private static final int LOCAL_PLAYER = 5;
    private static final int PLAYER_COLOR = 7;
    private static final int PLAYER_TEAM = 1;
    private static final int FORCE_SNAPSHOT_SIZE = PlayerTable.PLAYABLE_PLAYER_SLOTS + PlayerTable.FORCE_COUNT;
    private static final int FORCE_SNAPSHOT_VALUE = 3;

    /**
     * The stored player type of a computer slot.
     */
    private static final int COMPUTER_TYPE = 1;
    /**
     * The stored player type of a human slot.
     */
    private static final int HUMAN_TYPE = 2;
    /**
     * The stored player type of a passive rescue slot.
     */
    private static final int RESCUE_PASSIVE_TYPE = 3;
    /**
     * The stored race of a Zerg slot.
     */
    private static final int ZERG_RACE = 0;
    /**
     * The stored race of a Terran slot.
     */
    private static final int TERRAN_RACE = 1;
    /**
     * The stored race of a Protoss slot.
     */
    private static final int PROTOSS_RACE = 2;

    private static @NotNull Game createGame() {
        GameMap map = new GameMap(MAP_DIMENSION, MAP_DIMENSION, TILESET, new int[MAP_DIMENSION * MAP_DIMENSION]);
        Game world = Game.create(null, map, new LcgRandom(RANDOM_SEED));

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS; playerIndex++) {
            Player player = new Player(playerIndex, playerIndex, PlayerTypes.HUMAN, ScrRaceIds.TERRAN, PLAYER_TEAM);

            player.setColor(PLAYER_COLOR);
            world.getPlayerTable().setPlayer(playerIndex, player);
        }

        return world;
    }


    @ParameterizedTest
    @CsvSource({"1,8", "8,1"})
    void snapshot_failure_remains_retryable_when_a_lobby_row_is_short(int typeCount, int raceCount) {
        Game world = createGame();
        GameContext context = world.getGameContext();
        byte[] lobbyTypes = new byte[typeCount];
        byte[] lobbyRaces = new byte[raceCount];

        Arrays.fill(lobbyTypes, (byte) PlayerTypes.COMPUTER);
        Arrays.fill(lobbyRaces, (byte) ScrRaceIds.ZERG);
        context.setLobby(LOBBY_SLOT, lobbyTypes, lobbyRaces);

        Assertions.assertThrows(ArrayIndexOutOfBoundsException.class, () -> new GameContextFields(world, LOCAL_PLAYER));

        byte[] partialStorage = context.getContextStorage();
        ByteBuffer partialFields = ByteBuffer.wrap(partialStorage).order(ByteOrder.LITTLE_ENDIAN);

        Assertions.assertEquals(LOBBY_SLOT, partialFields.getInt(GameStructureView.LOCAL_PLAYER_OFFSET));
        Assertions.assertEquals(COMPUTER_TYPE, partialStorage[GameStructureView.PLAYER_TYPES_OFFSET]);
        Assertions.assertEquals(ZERG_RACE, partialStorage[GameStructureView.PLAYER_RACES_OFFSET]);
        Assertions.assertEquals(PLAYER_COLOR, partialFields.getInt(GameStructureView.PLAYER_COLORS_OFFSET));
        Assertions.assertFalse(context.hasContextSnapshots());

        byte[] repairedTypes = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        byte[] repairedRaces = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        byte[] forces = new byte[FORCE_SNAPSHOT_SIZE];

        Arrays.fill(repairedTypes, (byte) PlayerTypes.RESCUE_PASSIVE);
        Arrays.fill(repairedRaces, (byte) ScrRaceIds.PROTOSS);
        Arrays.fill(forces, (byte) FORCE_SNAPSHOT_VALUE);
        context.setLobby(LOBBY_SLOT, repairedTypes, repairedRaces);
        context.setForceSnapshot(forces);

        GameContextFields fields = new GameContextFields(world, LOCAL_PLAYER);
        ByteBuffer storedFields = ByteBuffer.wrap(context.getContextStorage()).order(ByteOrder.LITTLE_ENDIAN);

        Assertions.assertTrue(context.hasContextSnapshots());

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS; playerIndex++) {
            Assertions.assertEquals(RESCUE_PASSIVE_TYPE, fields.readByte(GameStructureView.PLAYER_TYPES_OFFSET + playerIndex));
            Assertions.assertEquals(PROTOSS_RACE, fields.readByte(GameStructureView.PLAYER_RACES_OFFSET + playerIndex));
            Assertions.assertEquals(PLAYER_COLOR, storedFields.getInt(GameStructureView.PLAYER_COLORS_OFFSET + playerIndex * Integer.BYTES));
        }

        for (int forceIndex = 0; forceIndex < forces.length; forceIndex++) {
            Assertions.assertEquals(FORCE_SNAPSHOT_VALUE, fields.readByte(GameStructureView.PLAYER_FORCES_OFFSET + forceIndex));
        }

        Assertions.assertEquals(RANDOM_SEED, world.getRandom().getState());
        Assertions.assertEquals(0, world.getRandom().getTotalRandomCounts());
    }

    @Test
    void snapshot_completion_preserves_the_first_successful_lobby_values() {
        Game world = createGame();
        GameContext context = world.getGameContext();
        byte[] lobbyTypes = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        byte[] lobbyRaces = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        Arrays.fill(lobbyTypes, (byte) PlayerTypes.COMPUTER);
        Arrays.fill(lobbyRaces, (byte) ScrRaceIds.ZERG);
        context.setLobby(LOBBY_SLOT, lobbyTypes, lobbyRaces);

        new GameContextFields(world, LOCAL_PLAYER);

        Assertions.assertTrue(context.hasContextSnapshots());

        byte[] firstStorage = context.getContextStorage().clone();

        Arrays.fill(lobbyTypes, (byte) PlayerTypes.RESCUE_PASSIVE);
        Arrays.fill(lobbyRaces, (byte) ScrRaceIds.PROTOSS);
        context.setLobby(LOCAL_PLAYER, lobbyTypes, lobbyRaces);

        new GameContextFields(world, LOCAL_PLAYER);

        Assertions.assertArrayEquals(firstStorage, context.getContextStorage());
        Assertions.assertEquals(RANDOM_SEED, world.getRandom().getState());
        Assertions.assertEquals(0, world.getRandom().getTotalRandomCounts());
    }

    @Test
    void snapshot_without_a_lobby_uses_player_records() {
        Game world = createGame();
        GameContextFields fields = new GameContextFields(world, -1);
        GameContext context = world.getGameContext();
        ByteBuffer storage = ByteBuffer.wrap(context.getContextStorage()).order(ByteOrder.LITTLE_ENDIAN);

        Assertions.assertTrue(context.hasContextSnapshots());
        Assertions.assertEquals(0, storage.getInt(GameStructureView.LOCAL_PLAYER_OFFSET));

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS; playerIndex++) {
            Assertions.assertEquals(HUMAN_TYPE, fields.readByte(GameStructureView.PLAYER_TYPES_OFFSET + playerIndex));
            Assertions.assertEquals(TERRAN_RACE, fields.readByte(GameStructureView.PLAYER_RACES_OFFSET + playerIndex));
            Assertions.assertEquals(PLAYER_COLOR, storage.getInt(GameStructureView.PLAYER_COLORS_OFFSET + playerIndex * Integer.BYTES));
            Assertions.assertEquals(PLAYER_TEAM + 1, fields.readByte(GameStructureView.PLAYER_FORCES_OFFSET + playerIndex));
        }

        Assertions.assertEquals(RANDOM_SEED, world.getRandom().getState());
        Assertions.assertEquals(0, world.getRandom().getTotalRandomCounts());
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,1", "2,2", "8,8"})
    void snapshot_stores_the_number_of_human_players_as_the_active_player_count(int humans, int activePlayerCount) {
        Game world = createGame();

        for (int playerIndex = humans; playerIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS; playerIndex++) {
            Player player = world.getPlayerTable().getPlayer(playerIndex);

            Assertions.assertNotNull(player);
            player.setType(playerIndex % 2 == 0 ? PlayerTypes.COMPUTER : PlayerTypes.NONE);
        }

        GameContextFields fields = new GameContextFields(world, LOCAL_PLAYER);

        Assertions.assertEquals(activePlayerCount, fields.readByte(GameStructureView.ACTIVE_PLAYER_COUNT_OFFSET));
    }

    @ParameterizedTest
    @CsvSource({"false,false,1", "true,false,0", "false,true,0"})
    void snapshot_stores_the_single_player_byte_of_a_live_single_player_game(boolean network, boolean replay, int singlePlayer) {
        Game world = createGame();

        if (network) {
            byte[] lobbyTypes = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];
            byte[] lobbyRaces = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];

            Arrays.fill(lobbyTypes, (byte) PlayerTypes.HUMAN);
            Arrays.fill(lobbyRaces, (byte) ScrRaceIds.TERRAN);
            world.getGameContext().setLobby(LOBBY_SLOT, lobbyTypes, lobbyRaces);
        }

        world.setReplayPlayback(replay);

        GameContextFields fields = new GameContextFields(world, LOCAL_PLAYER);

        Assertions.assertEquals(singlePlayer, fields.readByte(GameStructureView.SINGLE_PLAYER_OFFSET));
    }

    @ParameterizedTest
    @CsvSource({"false,2", "true,1"})
    void snapshot_stores_the_console_race_of_the_local_player_or_terran_for_a_replay(boolean replay, int consoleRace) {
        Game world = createGame();
        byte[] lobbyTypes = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        byte[] lobbyRaces = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        Arrays.fill(lobbyTypes, (byte) PlayerTypes.HUMAN);
        Arrays.fill(lobbyRaces, (byte) ScrRaceIds.ZERG);
        lobbyRaces[LOBBY_SLOT] = (byte) ScrRaceIds.PROTOSS;
        world.getGameContext().setLobby(LOBBY_SLOT, lobbyTypes, lobbyRaces);
        world.setReplayPlayback(replay);

        GameContextFields fields = new GameContextFields(world, LOCAL_PLAYER);

        Assertions.assertEquals(consoleRace, fields.readByte(GameStructureView.CONSOLE_RACE_OFFSET));
    }
}
