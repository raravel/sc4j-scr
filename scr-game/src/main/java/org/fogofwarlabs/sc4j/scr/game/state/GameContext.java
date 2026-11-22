package org.fogofwarlabs.sc4j.scr.game.state;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.text.TerminatedStringUtils;
import org.fogofwarlabs.sc4j.scr.core.FileNameUtils;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.HostButtonSets;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.NativeCodeConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Owns the parts of the game context and the host values of one game that no simulation system owns: the
 * game-start snapshots, the lobby the game started from, the map file, the committed {@code stat_txt} table, the pool
 * limits, the elapsed-time base, and the host tables that EUD writes reach (code constants, button sets, the visible fog
 * level, the last error string).
 */
public final class GameContext {
    /**
     * The size of the game context, the game structure a saved game keeps.
     */
    public static final int CONTEXT_SIZE = 0x17700;
    /**
     * The size of the map file path in the game context ({@code MAX_PATH}).
     */
    public static final int MAP_PATH_SIZE = 0x104;
    /**
     * The pauses every player starts with ({@link #getContextPauses()}).
     */
    public static final int INITIAL_PAUSES = 3;

    /**
     * The lobby color byte that names no color ({@link #getContextLobbyColors()}).
     */
    private static final int CONTEXT_LOBBY_COLOR_NONE = 0x16;
    /**
     * The game context offset of the map file path.
     */
    private static final int MAP_PATH_OFFSET = 0xC4C;
    /**
     * The game context offset of the local relation table: one byte per player.
     */
    private static final int LOCAL_RELATIONS_OFFSET = 0x2C7A;
    /**
     * The default fog level of visible tiles.
     */
    private static final int DEFAULT_VISIBLE_FOG_LEVEL = 0x1F;
    /**
     * The value of {@link #contextTileset} while the context tileset is the map's tileset.
     */
    private static final int MAP_TILESET = -1;
    /**
     * The mask of a WORD value.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The mask of a BYTE value.
     */
    private static final int BYTE_MASK = 0xFF;

    private final @NotNull GameMap map;

    /**
     * The context tileset WORD once an EUD write replaced it, {@link #MAP_TILESET} while it is the map's.
     */
    private int contextTileset;
    /**
     * The game seed DWORD written by aiscript {@code set_randomseed}; the simulation does not read it.
     */
    private int gameSeed;

    private byte @Nullable [] gameHeaderSection;
    /**
     * The game structure's player forces and force flags at the start, or {@code null} when unknown.
     */
    private byte @Nullable [] forceSnapshot;
    /**
     * The seven pool limits the game started with ({@code LMTS}), in native field order.
     */
    private int @Nullable [] limits;
    /**
     * The elapsed seconds and the frame the elapsed time counts from: the game start, or the saved game's values.
     */
    private int elapsedSecondsBase;
    private int elapsedFrameBase;
    /**
     * The lobby a network game started from, as the game start copies it into the game context: the local lobby slot and
     * the lobby types and races of players 0..7, or {@code null} for a game whose final player records are its lobby.
     */
    private byte @Nullable [] lobbyTypes;
    private byte @Nullable [] lobbyRaces;
    private int lobbySlot;

    /**
     * The twelve lobby color bytes of the game context.
     */
    private final byte @NotNull [] contextLobbyColors;
    /**
     * The pauses left of players 0..7 in the game context.
     */
    private final byte @NotNull [] contextPauses;
    /**
     * The bytes of the game context that no simulation object owns: the game-start snapshots, interface state, the map
     * path and title, the legacy save blocks and unused space. The EUD emulation serves them and a saved game keeps them,
     * both from this array.
     */
    private final byte @NotNull [] contextStorage;
    /**
     * Whether {@link #contextStorage} holds the game-start snapshots (taken once, or read from a saved game).
     */
    private boolean contextSnapshotsTaken;
    private byte @Nullable [] statTextTable;
    private byte @Nullable [] mapArchivePath;
    private byte @Nullable [] mapArchiveFile;

    private final @NotNull NativeCodeConstants codeConstants;
    private final @NotNull HostButtonSets hostButtonSets;
    /**
     * The minimap color BYTE of visible tiles; EUD writes change it.
     */
    private int visibleFogLevel;
    private byte @Nullable [] lastErrorBytes;

    /**
     * Creates a {@link GameContext} instance for a new game.
     *
     * @param gameData the game data tables (the code constants of the installed executable), or {@code null} in unit tests
     *                 that build worlds without game data
     * @param map      the map (the default context tileset)
     */
    public GameContext(@Nullable GameData gameData, @NotNull GameMap map) {
        this.map = map;

        this.contextTileset = MAP_TILESET;
        this.gameSeed       = 0;

        this.gameHeaderSection  = null;
        this.forceSnapshot      = null;
        this.limits             = null;
        this.elapsedSecondsBase = 0;
        this.elapsedFrameBase   = 0;
        this.lobbyTypes         = null;
        this.lobbyRaces         = null;
        this.lobbySlot          = 0;

        this.contextLobbyColors    = new byte[PlayerTable.PLAYER_SLOTS];
        this.contextPauses         = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.contextStorage        = new byte[CONTEXT_SIZE];
        this.contextSnapshotsTaken = false;
        this.statTextTable         = null;
        this.mapArchivePath        = null;
        this.mapArchiveFile        = null;

        Arrays.fill(this.contextLobbyColors, (byte) CONTEXT_LOBBY_COLOR_NONE);
        Arrays.fill(this.contextPauses, (byte) INITIAL_PAUSES);

        this.codeConstants = new NativeCodeConstants();

        if (gameData != null) {
            this.codeConstants.copyFrom(gameData.getCodeConstants());
        }

        this.hostButtonSets  = new HostButtonSets();
        this.visibleFogLevel = DEFAULT_VISIBLE_FOG_LEVEL;
        this.lastErrorBytes  = null;
    }

    /**
     * Returns the tileset WORD of the game context: the map loader stores the map's tileset there and the iscript tile
     * frame reads it live, so an EUD write changes the frames that follow; the terrain tables stay those loaded for
     * {@link GameMap#getTileset()}.
     *
     * @return the tileset WORD
     */
    public int getContextTileset() {
        return this.contextTileset < 0 ? this.map.getTileset() : this.contextTileset;
    }

    /**
     * Sets the tileset WORD of the game context, as an EUD write of the game data does.
     *
     * @param tileset the WORD in the low sixteen bits
     */
    public void setContextTileset(int tileset) {
        this.contextTileset = tileset & WORD_MASK;
    }

    /**
     * @return {@code true} if an EUD write replaced the context tileset, {@code false} otherwise
     */
    public boolean hasContextTilesetOverride() {
        return this.contextTileset >= 0;
    }

    /**
     * @return the game seed DWORD
     */
    public int getGameSeed() {
        return this.gameSeed;
    }

    /**
     * Sets the game seed DWORD, as aiscript {@code set_randomseed} does.
     *
     * @param seed the seed
     */
    public void setGameSeed(int seed) {
        this.gameSeed = seed;
    }

    /**
     * @return the in-memory replay header section ({@code 0x279} bytes), shared and mutable,
     * or {@code null} when it is not known
     */
    public byte @Nullable [] getGameHeaderSection() {
        return this.gameHeaderSection;
    }

    /**
     * Sets the in-memory replay header section.
     *
     * @param gameHeaderSection the game header bytes, retained
     */
    public void setGameHeaderSection(byte @Nullable [] gameHeaderSection) {
        this.gameHeaderSection = gameHeaderSection;
    }

    /**
     * @return the eight player forces and four force flags the game started with, or {@code null} when the start did not
     * provide them
     */
    public byte @Nullable [] getForceSnapshot() {
        return this.forceSnapshot;
    }

    /**
     * Sets the player forces and force flags the game started with.
     *
     * @param forceSnapshot the eight player forces and four force flags, retained, or {@code null}
     */
    public void setForceSnapshot(byte @Nullable [] forceSnapshot) {
        this.forceSnapshot = forceSnapshot;
    }

    /**
     * @return a copy of the seven pool limits the game started with, in native field order, or {@code null} when unknown
     */
    public int @Nullable [] getLimits() {
        return this.limits == null ? null : this.limits.clone();
    }

    /**
     * Sets the pool limits the game started with.
     *
     * @param limits the seven pool limits in native field order, copied
     */
    public void setLimits(int @NotNull [] limits) {
        this.limits = limits.clone();
    }

    /**
     * @return the elapsed seconds at {@link #getElapsedFrameBase()}: {@code 0}, or those of the saved game the game was
     * loaded from
     */
    public int getElapsedSecondsBase() {
        return this.elapsedSecondsBase;
    }

    /**
     * @return the frame the elapsed time counts from
     */
    public int getElapsedFrameBase() {
        return this.elapsedFrameBase;
    }

    /**
     * Sets where the elapsed time counts from, as a load restores it.
     *
     * @param elapsedSecondsBase the elapsed seconds at the frame
     * @param elapsedFrameBase   the frame
     */
    public void setElapsedBase(int elapsedSecondsBase, int elapsedFrameBase) {
        this.elapsedSecondsBase = elapsedSecondsBase;
        this.elapsedFrameBase   = elapsedFrameBase;
    }

    /**
     * @return the lobby types of players 0..7 the network game started from (a copy), or {@code null}
     */
    public byte @Nullable [] getLobbyTypes() {
        return this.lobbyTypes == null ? null : this.lobbyTypes.clone();
    }

    /**
     * @return the lobby races of players 0..7 the network game started from (a copy), or {@code null}
     */
    public byte @Nullable [] getLobbyRaces() {
        return this.lobbyRaces == null ? null : this.lobbyRaces.clone();
    }

    /**
     * @return the local machine's lobby slot of the network game
     */
    public int getLobbySlot() {
        return this.lobbySlot;
    }

    /**
     * Sets the lobby a network game starts from, which the game start copies into the game context.
     *
     * @param lobbySlot  the local machine's lobby slot
     * @param lobbyTypes the lobby types of players 0..7, copied
     * @param lobbyRaces the lobby races of players 0..7, copied
     */
    public void setLobby(int lobbySlot, byte @NotNull [] lobbyTypes, byte @NotNull [] lobbyRaces) {
        this.lobbySlot  = lobbySlot;
        this.lobbyTypes = lobbyTypes.clone();
        this.lobbyRaces = lobbyRaces.clone();
    }

    /**
     * @return the twelve lobby color bytes of the game context, shared and mutable: the EUD context tail serves and
     * stores them
     */
    public byte @NotNull [] getContextLobbyColors() {
        return this.contextLobbyColors;
    }

    /**
     * Sets the lobby color bytes the game start leaves in the game context.
     *
     * @param lobbyColors the twelve bytes, copied
     * @throws IllegalArgumentException if there are not twelve bytes
     */
    public void setContextLobbyColors(byte @NotNull [] lobbyColors) {
        if (lobbyColors.length != PlayerTable.PLAYER_SLOTS) {
            throw Exceptions.newIllegalArgumentException("The game context must hold {0} lobby colors, not {1}.",
                                                         PlayerTable.PLAYER_SLOTS, lobbyColors.length);
        }

        System.arraycopy(lobbyColors, 0, this.contextLobbyColors, 0, PlayerTable.PLAYER_SLOTS);
    }

    /**
     * @return the pauses left of players 0..7 in the game context, shared and mutable: the network pause handling and the
     * EUD game-data block read and write them
     */
    public byte @NotNull [] getContextPauses() {
        return this.contextPauses;
    }

    /**
     * @return the game-context bytes no simulation object owns ({@link #CONTEXT_SIZE} bytes indexed by context offset),
     * shared and mutable: the EUD emulation and the saved game serve them
     */
    public byte @NotNull [] getContextStorage() {
        return this.contextStorage;
    }

    /**
     * @return {@code true} if the context storage holds the game-start snapshots, {@code false} otherwise
     */
    public boolean hasContextSnapshots() {
        return this.contextSnapshotsTaken;
    }

    /**
     * Returns a player's entry of the local relation table, which the context storage keeps, so the EUD emulation and the
     * saved game serve it too.
     *
     * @param player the player from {@code 0} through {@code 11}
     * @return the relation of the player to the local player, the circle color of the player's units
     */
    public int getLocalRelation(int player) {
        return this.contextStorage[LOCAL_RELATIONS_OFFSET + player] & BYTE_MASK;
    }

    /**
     * Sets a player's entry of the local relation table.
     *
     * @param player   the player from {@code 0} through {@code 11}
     * @param relation the relation of the player to the local player
     */
    public void setLocalRelation(int player, int relation) {
        this.contextStorage[LOCAL_RELATIONS_OFFSET + player] = (byte) relation;
    }

    /**
     * Returns the {@code stat_txt} string table the EUD emulation last committed, or {@code null} while the installed
     * table is in effect. When a trigger pass wrote the emulation's copy of {@code rez\stat_txt.tbl}, the end of the pass
     * points the game's table view at that buffer, so the game's {@code stat_txt} texts read the written bytes from then
     * on.
     *
     * @return a copy of the committed table bytes (the whole buffer), or {@code null}
     */
    public byte @Nullable [] getStatTextTable() {
        return this.statTextTable == null ? null : this.statTextTable.clone();
    }

    /**
     * @return {@code true} if the EUD emulation has committed a {@code stat_txt} table, {@code false} while the installed table is in effect
     */
    public boolean hasStatTextTable() {
        return this.statTextTable != null;
    }

    /**
     * Reads the bytes of the committed {@code stat_txt} table from a string's start plus a skip up to the next {@code 0}
     * byte: a button string is read as its hotkey byte, its type byte and the text from the pointer plus two, whatever the
     * type byte is.
     *
     * @param identifier the 1-based string identifier
     * @param skip       the bytes to skip from the string's start
     * @return the bytes, or {@code null} while the installed table is in effect or for an identifier outside the count
     */
    public byte @Nullable [] getStatTextBytes(int identifier, int skip) {
        byte[] table = this.statTextTable;

        if (table == null || table.length < Short.BYTES) {
            return null;
        }

        int count = LittleEndianUtils.readUnsigned16(table, 0);
        int entry = identifier * Short.BYTES;

        if (identifier < 1 || identifier > count || entry + 1 >= table.length) {
            return null;
        }

        int start = LittleEndianUtils.readUnsigned16(table, entry) + skip;
        int end = start;

        while (end < table.length && table[end] != 0) {
            end++;
        }

        return start >= table.length ? new byte[0] : Arrays.copyOfRange(table, start, end);
    }

    /**
     * Sets the committed {@code stat_txt} table, as the EUD emulation commits its buffer.
     *
     * @param statTextTable the buffer bytes, copied
     */
    public void setStatTextTable(byte @NotNull [] statTextTable) {
        this.statTextTable = statTextTable.clone();
    }

    /**
     * @return the path bytes of the file the game was loaded from (the replay file of replay playback, the map file of a
     * live game), or {@code null} when the game has no source file
     */
    public byte @Nullable [] getMapArchivePath() {
        return this.mapArchivePath;
    }

    /**
     * @return the bytes of the file the game was loaded from, or {@code null} when the game has no source file
     */
    public byte @Nullable [] getMapArchiveFile() {
        return this.mapArchiveFile;
    }

    /**
     * Tells whether the game lists its source file as a Storm archive: the path the game context holds
     * ({@link #setMapArchive}) is not empty and converts to a file name ({@link FileNameUtils#readFileName}), and the file
     * was read. A path whose cut splits a character does not convert, so it names no file.
     *
     * @return {@code true} if the source file is listed, {@code false} otherwise
     */
    public boolean hasMapArchiveFile() {
        int length = TerminatedStringUtils.getTextLength(this.contextStorage, MAP_PATH_OFFSET);

        return this.mapArchiveFile != null && length > 0 &&
               FileNameUtils.readFileName(Arrays.copyOfRange(this.contextStorage, MAP_PATH_OFFSET, MAP_PATH_OFFSET + length)) != null;
    }

    /**
     * Sets the file the game was loaded from: the replay file of replay playback, the map file of a live game. The game
     * start keeps the path in the game context, cut as a buffer of {@link #MAP_PATH_SIZE} bytes holds it
     * ({@link TerminatedStringUtils#putText}: at most 259 bytes and the terminating zero, a longer path loses its end), which
     * a saved game keeps and from which the EUD emulation builds its Storm archive list.
     *
     * @param path the path bytes, retained
     * @param file the file bytes, retained
     */
    public void setMapArchive(byte @Nullable [] path, byte @Nullable [] file) {
        this.mapArchivePath = path;
        this.mapArchiveFile = file;

        Arrays.fill(this.contextStorage, MAP_PATH_OFFSET, MAP_PATH_OFFSET + MAP_PATH_SIZE, (byte) 0);

        if (path != null) {
            TerminatedStringUtils.putText(path, 0, path.length, this.contextStorage, MAP_PATH_OFFSET, MAP_PATH_SIZE);
        }
    }

    /**
     * @return the type-7 code constants, unknown until loaded from the installed executable
     */
    public @NotNull NativeCodeConstants getCodeConstants() {
        return this.codeConstants;
    }

    /**
     * @return the game's button set table as the EUD emulation's commits leave it
     */
    public @NotNull HostButtonSets getHostButtonSets() {
        return this.hostButtonSets;
    }

    /**
     * @return the fog level of visible tiles (default {@code 0x1F}): the world fog builder gives visible tiles this level
     * and explored tiles half of it, so it sets the world's brightness
     */
    public int getVisibleFogLevel() {
        return this.visibleFogLevel;
    }

    /**
     * Sets the fog level of visible tiles, as an EUD write does.
     *
     * @param value the BYTE in the low eight bits
     */
    public void setVisibleFogLevel(int value) {
        this.visibleFogLevel = value & BYTE_MASK;
    }

    /**
     * @return the last established error payload, or {@code null} when its value is unknown
     */
    public byte @Nullable [] getLastErrorBytes() {
        return this.lastErrorBytes == null ? null : this.lastErrorBytes.clone();
    }

    /**
     * Sets the error string payload, copied.
     *
     * @param error the bytes preceding the terminating NUL
     */
    public void setLastErrorBytes(byte @NotNull [] error) {
        this.lastErrorBytes = error.clone();
    }


    /**
     * Records that the context storage holds the game-start snapshots, taken by the EUD emulation or read from a saved
     * game.
     */
    public void markContextSnapshotsTaken() {
        this.contextSnapshotsTaken = true;
    }
}
