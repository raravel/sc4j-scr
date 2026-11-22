package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameContext;
import org.fogofwarlabs.sc4j.scr.game.state.GameContextBytes;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.ClassicContextTailRegion;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Serves the whole game context ({@link GameContext#CONTEXT_SIZE} bytes, the game structure a saved game keeps) byte by
 * byte from the world's owners, as the EUD emulation serves its classic copy: the game-data block through the same
 * owners as the EUD region, then the context tail.
 * <p>
 * The class also owns the offsets of the game structure fields that the EUD emulation and the saved game codecs share.
 */
public final class GameStructureView implements GameContextBytes {
    /**
     * The offset of the local player DWORD (the local lobby slot).
     */
    public static final int LOCAL_PLAYER_OFFSET = 0xC0;
    /**
     * The offset of the player type bytes, one per player slot.
     */
    public static final int PLAYER_TYPES_OFFSET = 0xC4;
    /**
     * The offset of the player race bytes, one per player slot.
     */
    public static final int PLAYER_RACES_OFFSET = 0xD0;
    /**
     * The offset of the screen tile WORDs of the local camera (horizontal, then vertical).
     */
    public static final int SCREEN_TILES_OFFSET = 0xE0;
    /**
     * The offset of the map width WORD in tiles, followed by the map height WORD.
     */
    public static final int MAP_SIZE_OFFSET = 0xE4;
    /**
     * The offset of the tileset WORD.
     */
    public static final int TILESET_OFFSET = 0xEC;
    /**
     * The offset of the active player count byte: the number of human players at the game start.
     */
    public static final int ACTIVE_PLAYER_COUNT_OFFSET = 0xF1;
    /**
     * The offset of the console race byte.
     */
    public static final int CONSOLE_RACE_OFFSET = 0xF2;
    /**
     * The offset of the single-player byte: {@code 1} for a live single-player game, {@code 0} for a network game or a replay.
     */
    public static final int SINGLE_PLAYER_OFFSET = 0xF3;
    /**
     * The offset of the player color index DWORDs, one per playable player.
     */
    public static final int PLAYER_COLORS_OFFSET = 0x12C;
    /**
     * The offset of the frame counter DWORD.
     */
    public static final int FRAME_OFFSET = 0x14C;
    /**
     * The offset of the alert screen position of the local interface (horizontal DWORD, then vertical DWORD).
     */
    public static final int ALERT_SCREEN_OFFSET = 0xC44;
    /**
     * The offset of the player force bytes (force plus one), one per playable player.
     */
    public static final int PLAYER_FORCES_OFFSET = 0xE4C0;
    /**
     * The offset of the start location records (horizontal and vertical WORD), one per playable player.
     */
    public static final int START_LOCATIONS_OFFSET = 0xE630;
    /**
     * The offset of the expansion byte.
     */
    public static final int EXPANSION_OFFSET = 0x10350;

    /**
     * The end of the alert screen position.
     */
    static final int ALERT_SCREEN_END = ALERT_SCREEN_OFFSET + 2 * Integer.BYTES;

    private final @NotNull LocalView view;
    private final @NotNull ClassicAddressSpace.GameDataRegion gameData;
    private final @NotNull ClassicContextTailRegion tail;
    /**
     * The alert screen bytes a load stores until the whole position is known ({@link #writeContextByte}).
     */
    private final byte @NotNull [] alertScreen;

    /**
     * Creates a {@link GameStructureView} instance over a world.
     *
     * @param world    the world
     * @param triggers the world's trigger context, or {@code null}
     * @throws IllegalStateException if the world has no unit accounting
     */
    public GameStructureView(@NotNull Game world, @Nullable TriggerContext triggers) {
        Player[] players = new Player[PlayerTable.PLAYER_SLOTS];
        UnitAccounting accounting = world.getPlayerTable().getUnitAccounting();

        if (accounting == null) {
            throw Exceptions.newIllegalStateException("The game structure requires the world's accounting.");
        }

        this.view = world.getInterfaceLink().getLocalView();

        for (int player = 0; player < PlayerTable.PLAYER_SLOTS; player++) {
            players[player] = world.getPlayerTable().getPlayer(player);
        }

        this.gameData = new ClassicAddressSpace.GameDataRegion(triggers,
                                                               accounting.getScores(),
                                                               accounting,
                                                               players,
                                                               world.getPlayerTable().getPlayerTechnology(),
                                                               world.getPlayerTable().getPlayerUpgrades(),
                                                               world.getPlayerTable().getPlayerRelations(),
                                                               world.getSelection());
        this.gameData.setMapAndView(world.getMap(), this.view);
        this.gameData.setFields(new GameContextFields(world, triggers == null ? -1 : triggers.getLocalPlayer()));
        this.tail        = new ClassicContextTailRegion(world);
        this.alertScreen = new byte[ALERT_SCREEN_END - ALERT_SCREEN_OFFSET];
    }

    /**
     * Reads one byte of the game context.
     *
     * @param offset a game-context offset below {@link GameContext#CONTEXT_SIZE}
     * @return the unsigned byte
     */
    @Override
    public int readContextByte(int offset) {
        // The local camera and interface keep these; a game without an interface (a replay simulated in a test) has 0.
        if (offset >= SCREEN_TILES_OFFSET && offset < SCREEN_TILES_OFFSET + Integer.BYTES && !this.view.isKnown() ||
            offset >= ALERT_SCREEN_OFFSET && offset < ALERT_SCREEN_END && !this.view.isAlertKnown()) {
            return 0;
        }

        return offset < this.gameData.getSize() ? this.gameData.readByte(offset) : this.tail.readByte(offset - this.gameData.getSize());
    }

    /**
     * Writes one byte of the game context.
     *
     * @param offset a game-context offset below {@link GameContext#CONTEXT_SIZE}
     * @param value  the byte in the low eight bits
     */
    @Override
    public void writeContextByte(int offset, int value) {
        // The screen tiles follow the screen position the load centers the camera on; the alert screen is the interface's
        // once all eight bytes are read.
        if (offset >= SCREEN_TILES_OFFSET && offset < SCREEN_TILES_OFFSET + Integer.BYTES) {
            return;
        }

        if (offset >= ALERT_SCREEN_OFFSET && offset < ALERT_SCREEN_END) {
            this.alertScreen[offset - ALERT_SCREEN_OFFSET] = (byte) value;

            if (offset == ALERT_SCREEN_END - 1) {
                this.view.setAlertScreen(LittleEndianUtils.readInt32(this.alertScreen, 0),
                                         LittleEndianUtils.readInt32(this.alertScreen, Integer.BYTES));
            }

            return;
        }

        if (offset < this.gameData.getSize()) {
            this.gameData.writeByte(offset, value);
        } else {
            this.tail.writeByte(offset - this.gameData.getSize(), value);
        }
    }
}
