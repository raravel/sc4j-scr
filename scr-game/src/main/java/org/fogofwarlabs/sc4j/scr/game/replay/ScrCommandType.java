package org.fogofwarlabs.sc4j.scr.game.replay;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.command.CommandInput;
import org.fogofwarlabs.sc4j.engine.command.CommandOutput;
import org.fogofwarlabs.sc4j.engine.command.CommandType;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandCodec;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Serves one native command type, a type byte of the command stream, as an engine {@link CommandType}: the payload codec of the type and its
 * case of the native command dispatcher ({@link ReplayCommandExecutor}), run for the command player of the issuing record. A case that needs the
 * issuing record itself ({@link IssuerAction}) receives it besides the command player: in a team game the command player of every member is the
 * team's leader, while the issuing record is the member's own record.
 * <p>
 * The dispatcher runs a type in one of three ways ({@link Dispatch}): a keep-alive marker, a session command outside the current selection, or a
 * command of the issuer's current selection.
 *
 * @param <C> the command class
 */
public final class ScrCommandType<C> implements CommandType<C> {
    /**
     * Says how the native dispatcher runs a command type.
     */
    public enum Dispatch {
        /**
         * Keep Alive and Sync: the command runs and sets the dispatch marker count to {@code 1}.
         */
        MARKER,
        /**
         * A session command (Restart Game, Replay Seek, the ignored session commands and the SC4J mod command): it runs outside the issuer's
         * selection and resets the dispatch marker count.
         */
        SESSION,
        /**
         * A command of the issuer's current selection: it runs between the begin and the end of the issuer's selection command and resets the
         * dispatch marker count.
         */
        SELECTION
    }

    /**
     * Runs the dispatcher case of a command type.
     *
     * @param <C> the command class
     */
    @FunctionalInterface
    public interface Action<C> {
        /**
         * Runs a command.
         *
         * @param game          the SC:R game
         * @param commandPlayer the command player DWORD of the issuing record
         * @param command       the command
         */
        void execute(@NotNull Game game, int commandPlayer, @NotNull C command);
    }

    /**
     * Runs the dispatcher case of a command type that reads the issuing player record besides the command player.
     *
     * @param <C> the command class
     */
    @FunctionalInterface
    public interface IssuerAction<C> {
        /**
         * Runs a command.
         *
         * @param game          the SC:R game
         * @param issuer        the player record the command's issuer resolved to: the issuing player's own record, also in a team game
         * @param commandPlayer the command player DWORD of the issuing record, the team's leader in a team game
         * @param command       the command
         */
        void execute(@NotNull Game game, org.fogofwarlabs.sc4j.scr.game.player.@NotNull Player issuer, int commandPlayer, @NotNull C command);
    }

    private final int id;
    private final @NotNull ReplayCommandCodec<C> codec;
    private final @NotNull Dispatch dispatch;
    private final @NotNull IssuerAction<C> action;

    /**
     * Creates a {@link ScrCommandType} instance whose case reads the command player only.
     *
     * @param id       the command type byte
     * @param codec    the payload codec
     * @param dispatch how the dispatcher runs the type
     * @param action   the dispatcher case
     */
    public ScrCommandType(int id, @NotNull ReplayCommandCodec<C> codec, @NotNull Dispatch dispatch, @NotNull Action<C> action) {
        this(id, codec, dispatch, (game, issuer, commandPlayer, command) -> action.execute(game, commandPlayer, command));
    }

    /**
     * Creates a {@link ScrCommandType} instance whose case reads the issuing record besides the command player.
     *
     * @param id       the command type byte
     * @param codec    the payload codec
     * @param dispatch how the dispatcher runs the type
     * @param action   the dispatcher case
     */
    public ScrCommandType(int id, @NotNull ReplayCommandCodec<C> codec, @NotNull Dispatch dispatch, @NotNull IssuerAction<C> action) {
        this.id       = id;
        this.codec    = codec;
        this.dispatch = dispatch;
        this.action   = action;
    }

    /**
     * @return the command type byte
     */
    @Override
    public int getId() {
        return this.id;
    }

    /**
     * @return how the dispatcher runs the type
     */
    public @NotNull Dispatch getDispatch() {
        return this.dispatch;
    }

    /**
     * Reads a command from its encoded payload.
     *
     * @param input the payload of one command, positioned at its first byte
     * @return the command
     * @throws IOException if the payload is not a command of this type
     */
    @Override
    public @NotNull C read(@NotNull CommandInput input) throws IOException {
        return this.codec.read(input);
    }

    /**
     * Writes the encoded payload of a command.
     *
     * @param command the command
     * @param output  the output the payload is appended to
     */
    @Override
    public void write(@NotNull C command, @NotNull CommandOutput output) {
        this.codec.write(command, output);
    }

    /**
     * Applies a command of a player to the game: the dispatcher case of the type, for the player's record and its command player DWORD.
     *
     * @param game    the game
     * @param player  the issuing player, the record the issuer resolved to
     * @param command the command
     * @throws IllegalArgumentException if the game has no SC:R game system
     * @throws IllegalStateException    if the player has no record
     */
    @Override
    public void execute(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game, @NotNull Player player, @NotNull C command) {
        Game scrGame = game.getModSystem(Game.SYSTEM_KEY);
        org.fogofwarlabs.sc4j.scr.game.player.Player record = scrGame.getPlayerTable().getPlayer(player.getIndex());

        if (record == null) {
            throw Exceptions.newIllegalStateException("The command issuer {0} has no player record.", player);
        }

        this.action.execute(scrGame, record, record.getCommandPlayerIndex(), command);
    }
}
