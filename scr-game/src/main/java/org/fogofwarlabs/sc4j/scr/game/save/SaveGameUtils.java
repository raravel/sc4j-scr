package org.fogofwarlabs.sc4j.scr.game.save;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayHeaderBuilder;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitPsi;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.save.pool.*;
import org.fogofwarlabs.sc4j.scr.game.save.section.*;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.sprite.SelectionImages;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;

/**
 * Writes a running game to a saved file and restores its state into a started game.
 * <p>
 * The game starts from the saved header and scenario before the saved parts replace its state.
 */
public final class SaveGameUtils {
    /**
     * Writes the state of a running game without a save id (a save made outside a Save command).
     *
     * @param context     the running game
     * @param description the banner
     * @return the saved game
     */
    public static @NotNull SaveGameFile write(@NotNull SaveGameContext context, @NotNull SaveGameDescription description) {
        return write(context, description, 0);
    }

    /**
     * Writes the state of a running game in file order: the banner, the format mark, the game information, the trailer
     * offset, the player records, the game structure, the record slot, the pool parts ({@link #createPoolParts()}), the
     * remaining game parts ({@link #createWorldParts()}) and the trailer.
     * <p>
     * Writing changes the running game: the completed Pylons leave the provider list and lose their psi field sprites
     * before the pool parts ({@link UnitPsi#releaseProviders}) and are added again, with new sprites and their idle order,
     * after them ({@link UnitPsi#restoreProviders}). Every sprite loses its selection images and their flags before the
     * pool parts ({@link SelectionImages#detachBeforeSave}); after them the local selection and the teammates' selections mark
     * their units again ({@link SelectionImages#restoreAfterSave}), while flashing circles stay off.
     * <p>
     * A game whose simulation plugins do not allow a save ({@link Game#isSaveAllowed()}) is refused before anything is written or changed.
     *
     * @param context     the running game
     * @param description the banner
     * @param saveGameId  the Save command's time DWORD, the save id of the game information
     * @return the saved game
     * @throws IllegalStateException if the game cannot be saved or has no game header
     * @throws UncheckedIOException  if the written file cannot be parsed back
     */
    public static @NotNull SaveGameFile write(@NotNull SaveGameContext context, @NotNull SaveGameDescription description, int saveGameId) {
        Game game = context.getGame();

        if (!game.isSaveAllowed()) {
            throw Exceptions.newIllegalStateException("The game cannot be saved: a simulation plugin does not allow it.");
        }

        byte[] header = require(game.getGameContext().getGameHeaderSection(), "game header");
        SaveFileWriter output = new SaveFileWriter();

        output.writeBytes(description.toBannerBytes());
        output.writeBytes(new byte[]{(byte) SaveGameDescription.END_OF_BANNER});
        output.writeDword(SaveGameFile.FORMAT_MARK);
        output.writeSection(GameInformationSection.write(game,
                                                         saveGameId,
                                                         countPlayers(header, PlayerTypes.HUMAN),
                                                         countPlayers(header, PlayerTypes.COMPUTER)));

        int trailerOffset = output.getPosition();

        output.writeDword(0);
        output.writeSection(Arrays.copyOfRange(header,
                                               ReplayHeaderBuilder.PLAYERS_OFFSET,
                                               ReplayHeaderBuilder.PLAYERS_OFFSET + SaveGameFile.PLAYERS_SIZE));
        new GameStructureSection().write(context, output);

        LiveGame liveGame = context.getLiveGame();

        output.writeDword(liveGame == null ? 0 : liveGame.getLocalSlot());

        game.getPsi().releaseProviders();
        game.getSelectionImages().detachBeforeSave();

        for (SaveSection part : createPoolParts()) {
            part.write(context, output);
        }

        game.getSelectionImages().restoreAfterSave();
        game.getPsi().restoreProviders(context.getRuntime().getInstructionLimit());

        for (SaveSection part : createWorldParts()) {
            part.write(context, output);
        }

        output.patchDword(trailerOffset, output.getPosition());
        new TrailerSection().write(context, output);

        try {
            return SaveGameFile.parse(output.toByteArray());
        } catch (IOException exception) {
            throw Exceptions.newUncheckedIOException(exception);
        }
    }

    /**
     * Replaces the state of a game started from the saved game ({@link SaveGameSession}) with the saved parts: the game
     * structure and the random state the pre-load took, the local relation table rebuilt from the restored relations
     * ({@link SelectionImages#updateLocalRelations}, while the selections are still empty), the game parts in file order,
     * then the trailer; the completed Pylons then join the provider list ({@link UnitPsi#restoreProviders}).
     *
     * @param file    the saved game
     * @param context the started game
     * @throws UncheckedIOException if a part is malformed or does not fit the started game
     */
    public static void read(@NotNull SaveGameFile file, @NotNull SaveGameContext context) {
        Game game = context.getGame();
        SaveFileReader input = file.openBody();

        GameStructureSection.restore(context, file.getGameStructure());
        game.getRandom().setState(file.getRandomState());
        game.getSelectionImages().updateLocalRelations();

        for (SaveSection part : createPoolParts()) {
            part.read(context, input);
        }

        for (SaveSection part : createWorldParts()) {
            part.read(context, input);
        }

        if (input.getRemaining() != 0) {
            throw SaveFileReader.createFailure("The saved game has {0} bytes before its trailer that no part reads.", input.getRemaining());
        }

        new TrailerSection().read(context, file.openTrailer());

        // The saved Pylons hold no provider links and rejoin the provider list after the trailer.
        game.getPsi().restoreProviders(context.getRuntime().getInstructionLimit());
    }

    /**
     * Returns the smaller of the current pool capacity and its configured saved-array limit.
     * <p>
     * An EUD map can extend a runtime pool beyond the slots represented in the saved array.
     *
     * @param game     the game
     * @param limit    the index of the pool's limit
     * @param capacity the pool's current capacity
     * @return the number of slots the saved game holds
     * @throws IllegalStateException if the game has no pool limits
     */
    public static int getNativeCapacity(@NotNull Game game, int limit, int capacity) {
        return Math.min(capacity, require(game.getGameContext().getLimits(), "pool limits")[limit]);
    }

    /**
     * Requires a game object that a started game always has.
     *
     * @param value the object
     * @param name  the object name for the failure message
     * @param <T>   the object type
     * @return the object
     * @throws IllegalStateException if the object is absent
     */
    public static <T> @NotNull T require(@Nullable T value, @NotNull String name) {
        if (value == null) {
            throw Exceptions.newIllegalStateException("The saved game has no {0}.", name);
        }

        return value;
    }

    /**
     * Creates the pool parts after the record slot, in file order: images, sprites, thingies, the flingy record, units,
     * bullets and orders.
     *
     * @return the created pool parts
     */
    private static @NotNull List<SaveSection> createPoolParts() {
        ImagePoolSection images = new ImagePoolSection();

        return List.of(images,
                       new SpritePoolSection(images),
                       new ThingyPoolSection(),
                       new FlingyPoolSection(),
                       new UnitPoolSection(),
                       new BulletPoolSection(),
                       new OrderPoolSection());
    }

    /**
     * Creates the parts between the pool parts and the trailer, in file order.
     *
     * @return the created game parts
     */
    private static @NotNull List<SaveSection> createWorldParts() {
        return List.of(new TerrainOverlaySection(),
                       new CreepRecessionSection(),
                       new MapArraysSection(),
                       new TriggerSection(),
                       new ExtensionBlockSection(),
                       new SelectionSection(),
                       new PathingSection(),
                       new ComputerSection(),
                       new MapSettingsSection(),
                       new ScreenPositionSection(),
                       new FoliageSection());
    }

    /**
     * Counts the player records {@code 0}..{@code 7} of a type: the session players are the humans, and the writer counts
     * the computer records.
     *
     * @param header the replay header
     * @param type   the player type
     * @return the number of records of the type
     */
    private static int countPlayers(byte @NotNull [] header, int type) {
        int count = 0;

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            int record = ReplayHeaderBuilder.PLAYERS_OFFSET + player * ReplayHeaderBuilder.PLAYER_RECORD_SIZE;

            if (header[record + ReplayHeaderBuilder.PLAYER_TYPE_OFFSET] == type) {
                count++;
            }
        }

        return count;
    }


    private SaveGameUtils() {
    }
}
