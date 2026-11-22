package org.fogofwarlabs.sc4j.scr.game.replay;

import org.fogofwarlabs.sc4j.engine.command.CommandInput;
import org.fogofwarlabs.sc4j.engine.command.CommandOutput;
import org.fogofwarlabs.sc4j.engine.command.CommandRegistry;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommand;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandUtils;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.test.RecordingSimulationPluginSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Covers the native command types of a dispatcher: one type per type byte with a dispatcher case, payload codecs that write back the bytes
 * they read, and the mod command's case, which hands the data to the simulation plugins with the issuing record.
 */
final class NativeCommandTableTest {
    private static final int INSTRUCTION_LIMIT = 1000;
    /**
     * The network ids of a team's leader in record 0 and of its member in record 1, whose commands act for the leader.
     */
    private static final int LEADER_ISSUER = 7;
    private static final int MEMBER_ISSUER = 8;
    private static final int LEADER_RECORD = 0;
    private static final int MEMBER_RECORD = 1;
    private static final int TEAM = 1;
    /**
     * The payload of a mod command: the length byte and two data bytes.
     */
    private static final byte @NotNull [] MOD_COMMAND_PAYLOAD = {2, 0x45, 0x53};

    private static @NotNull CommandRegistry<ScrCommandType<?>> createTypes() {
        return new NativeCommandTable(new ReplayCommandExecutor(), INSTRUCTION_LIMIT).createTypes();
    }

    private static <C> byte @NotNull [] roundTrip(@NotNull ScrCommandType<C> type, byte @NotNull [] payload) throws IOException {
        CommandInput input = new CommandInput(payload);
        C command = type.read(input);
        CommandOutput output = new CommandOutput();

        Assertions.assertEquals(0, input.getRemaining(), "the read takes the whole payload");
        type.write(command, output);

        return output.toByteArray();
    }


    @Test
    void command_types_cover_every_dispatched_type_byte() {
        CommandRegistry<ScrCommandType<?>> types = createTypes();

        for (int id = 0; id < NativeCommandTable.TYPE_COUNT; id++) {
            ScrCommandType<?> type = types.find(id);

            Assertions.assertEquals(ReplayCommandUtils.isDispatched(id), type != null, "type byte " + id);

            if (type != null) {
                Assertions.assertEquals(id, type.getId());
            }
        }
    }

    @Test
    void command_types_dispatch_keep_alive_and_sync_as_markers_and_session_commands_outside_the_selection() {
        CommandRegistry<ScrCommandType<?>> types = createTypes();

        Assertions.assertEquals(ScrCommandType.Dispatch.MARKER, types.get(ReplayCommandIds.KEEP_ALIVE).getDispatch());
        Assertions.assertEquals(ScrCommandType.Dispatch.MARKER, types.get(ReplayCommandIds.SYNC).getDispatch());
        Assertions.assertEquals(ScrCommandType.Dispatch.SESSION, types.get(ReplayCommandIds.RESTART_GAME).getDispatch());
        Assertions.assertEquals(ScrCommandType.Dispatch.SESSION, types.get(ReplayCommandIds.REPLAY_SEEK).getDispatch());
        Assertions.assertEquals(ScrCommandType.Dispatch.SESSION, types.get(ReplayCommandIds.TURN_RATE).getDispatch());
        Assertions.assertEquals(ScrCommandType.Dispatch.SESSION, types.get(ReplayCommandIds.MOD_COMMAND).getDispatch());
        Assertions.assertEquals(ScrCommandType.Dispatch.SELECTION, types.get(ReplayCommandIds.PAUSE).getDispatch());
        Assertions.assertEquals(ScrCommandType.Dispatch.SELECTION, types.get(ReplayCommandIds.SELECT_121).getDispatch());
    }

    @Test
    void codecs_write_back_the_payload_bytes_they_read() throws IOException {
        CommandRegistry<ScrCommandType<?>> types = createTypes();
        byte[] chat = new byte[ReplayCommandExecutor.CHAT_TEXT_OFFSET + ReplayFile.CHAT_MESSAGE_SIZE];

        chat[0] = 3;
        chat[1] = 'g';
        chat[2] = 'g';

        byte[][] payloads = {
                {},
                {0x10, 0x00, 0x20, 0x00, 0x34, 0x12, 0x00, 0x00, 0x07, 0x00, 0x0A, 0x01},
                {0x10, 0x00, 0x20, 0x00, 0x34, 0x12, (byte) 0xE4, 0x00, 0x00},
                {0x02, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00},
                {0x1E, 0x10, 0x00, 0x20, 0x00, 0x6A, 0x00},
                chat,
                {0x01, 0x05},
                {(byte) 0xFF},
                {0x3C, 0x00},
                {0x78, 0x56, 0x34, 0x12},
                {0x40, 0x01, (byte) 0xC0, 0x00},
                {0x00, 0x01, 0x02, 0x03, 0x04, 0x05},
                {0x02, 0x07, 0x08}
        };
        int[] ids = {ReplayCommandIds.STIM, ReplayCommandIds.TARGETED_ORDER_121, ReplayCommandIds.RIGHT_CLICK, ReplayCommandIds.SELECT_121,
                     ReplayCommandIds.BUILD, ReplayCommandIds.CHAT, ReplayCommandIds.HOTKEY, ReplayCommandIds.STOP, ReplayCommandIds.TRAIN,
                     ReplayCommandIds.CHEAT, ReplayCommandIds.LIFT_OFF, ReplayCommandIds.SYNC, ReplayCommandIds.MOD_COMMAND};

        for (int index = 0; index < ids.length; index++) {
            Assertions.assertArrayEquals(payloads[index], roundTrip(types.get(ids[index]), payloads[index]), "type byte " + ids[index]);
        }
    }

    @Test
    void mod_command_reaches_the_plugins_with_the_issuing_record_of_a_team_member() throws Throwable {
        RecordingSimulationPluginSource.Recording recording = new RecordingSimulationPluginSource.Recording();
        ReplayCommandExecutor executor = new ReplayCommandExecutor();
        Game[] games = new Game[1];

        RecordingSimulationPluginSource.record(recording, () -> games[0] = ReplayCommandScenario.createGame(null));

        Game game = games[0];

        game.getPlayerTable().setPlayer(LEADER_RECORD, new Player(LEADER_RECORD, LEADER_ISSUER, PlayerTypes.HUMAN, 0, TEAM));
        game.getPlayerTable().setPlayer(MEMBER_RECORD, new Player(LEADER_RECORD, MEMBER_ISSUER, PlayerTypes.HUMAN, 0, TEAM));

        executor.execute(game, new ReplayCommand(0, MEMBER_ISSUER, ReplayCommandIds.MOD_COMMAND, MOD_COMMAND_PAYLOAD));
        executor.execute(game, new ReplayCommand(0, LEADER_ISSUER, ReplayCommandIds.MOD_COMMAND, MOD_COMMAND_PAYLOAD));

        Assertions.assertEquals(2, recording.getModCommandPlayers().size());
        Assertions.assertSame(game.getPlayerTable().getPlayer(MEMBER_RECORD), recording.getModCommandPlayers().getFirst(),
                              "the member's own record, not the leader its commands act for");
        Assertions.assertSame(game.getPlayerTable().getPlayer(LEADER_RECORD), recording.getModCommandPlayers().getLast());
        Assertions.assertArrayEquals(new byte[]{0x45, 0x53}, recording.getModCommandData().getFirst());
    }
}
