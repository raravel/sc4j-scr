package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.game.player.*;
import org.fogofwarlabs.sc4j.scr.game.state.ContextExpansion;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameContext;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Serves game-context bytes through live simulation owners, game-start snapshots and retained context storage.
 * Frame, tileset, unit availability, expansion ({@link ContextExpansion}), pauses and start locations remain live.
 * Local player, lobby types, races, colors, forces, the active player count, the console race and the single-player byte are captured
 * once. Color rows use their current owner; local alert bytes are delegated to {@link LocalView}. Other bytes retain their stored values,
 * among them the local relation table, which the selection images rebuild ({@link GameContext#getLocalRelation}).
 */
final class GameContextFields {
    private static final int FIRST_OFFSET = GameStructureView.LOCAL_PLAYER_OFFSET;

    private static final int LOW_END = 0xD70;

    private static final int COLOR_REMAP_OFFSET = 0x2870;

    private static final int COLOR_REMAP_END = 0x2CF4;

    private static final int FORCES_OFFSET = 0xE430;

    private static final int FORCES_END = 0xE544;

    private static final int LEGACY_SWITCHES_OFFSET = 0xE60C;

    private static final int PAUSES_OFFSET = 0xE628;

    private static final int LEGACY_END = 0xEB50;

    private static final int CONTEXT_END = ClassicAddressSpace.GAME_DATA_SIZE;

    private static final int SCREEN_AND_MAP_END = GameStructureView.MAP_SIZE_OFFSET + Integer.BYTES;

    private static final int VISION_END = PlayerRelations.SHARED_VISION_OFFSET + PlayerTable.PLAYER_SLOTS * Integer.BYTES;

    private static final int AVAILABILITY_OFFSET = 0x18C;

    private static final int AVAILABILITY_END = AVAILABILITY_OFFSET + PlayerTable.PLAYER_SLOTS * UnitsDat.UNIT_COUNT;

    private static final int UNIT_COLOR_ROWS_OFFSET = 0x2C86;

    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;

    /**
     * Tells whether this class serves a byte of the game-data block.
     *
     * @param offset a game-context offset
     * @return {@code true} if this class serves the byte, {@code false} otherwise
     */
    static boolean isRepresentedOffset(int offset) {
        return offset >= FIRST_OFFSET && offset < LOW_END &&
               !(offset >= GameStructureView.SCREEN_TILES_OFFSET && offset < SCREEN_AND_MAP_END) &&
               !(offset >= GameStructureView.ALERT_SCREEN_OFFSET && offset < GameStructureView.ALERT_SCREEN_END) &&
               !(offset >= PlayerRelations.SHARED_VISION_OFFSET && offset < VISION_END) ||
               offset >= COLOR_REMAP_OFFSET && offset < COLOR_REMAP_END ||
               offset >= FORCES_OFFSET && offset < FORCES_END ||
               offset >= LEGACY_SWITCHES_OFFSET && offset < LEGACY_SWITCHES_OFFSET + Integer.BYTES ||
               offset >= PAUSES_OFFSET && offset < LEGACY_END ||
               offset >= GameStructureView.EXPANSION_OFFSET && offset < CONTEXT_END;
    }


    private final @NotNull Game world;

    /**
     * The world's context storage ({@link GameContext#getContextStorage()}), indexed by context offset.
     */
    private final byte @NotNull [] storage;

    private final @Nullable PlayerColorRows colorRows;

    /**
     * Creates a {@link GameContextFields} instance over the world's context storage; the first one of a game takes the
     * game-start snapshots. A failed snapshot keeps its preceding writes and remains retryable.
     *
     * @param world       the world
     * @param localPlayer the local player, or {@code -1} when unknown
     * @throws UnsupportedOperationException if a player color lies outside the remapping tables
     * @throws IndexOutOfBoundsException     if a supplied lobby row is too short or the force snapshot exceeds context storage
     */
    GameContextFields(@NotNull Game world, int localPlayer) {
        this.world   = world;
        this.storage = world.getGameContext().getContextStorage();

        if (!world.getGameContext().hasContextSnapshots()) {
            this.takeSnapshots(localPlayer);
            world.getGameContext().markContextSnapshotsTaken();
        }

        // The player color remapping of the twelve players and their minimap indices; the world owns them
        // (PlayerTable#getPlayerColorRows), because every draw reads them too.
        if (world.getGameData() == null) {
            this.colorRows = null;

            return;
        }

        this.colorRows = world.getPlayerTable().getPlayerColorRows();

        if (this.colorRows == null) {
            throw Exceptions.newUnsupportedOperationException("A player color lies outside the remapping tables.");
        }
    }

    /**
     * Stores the game-start snapshots into the world's context storage, once per game: the local player, the player types,
     * races, colors, forces, the active player count, the console race and the single-player byte.
     *
     * @param localPlayer the local player, or {@code -1} when unknown
     */
    private void takeSnapshots(int localPlayer) {
        Game world = this.world;
        byte[] lobbyTypes = world.getGameContext().getLobbyTypes();
        byte[] lobbyRaces = world.getGameContext().getLobbyRaces();
        int activePlayers = 0;

        // The game start copies the local lobby slot and the player table's types and races before the start placement: a
        // network game's lobby, otherwise the final records, which a single-player lobby keeps in place.
        this.writeDword(GameStructureView.LOCAL_PLAYER_OFFSET, lobbyTypes == null ? Math.max(localPlayer, 0) : world.getGameContext().getLobbySlot());

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            Player record = world.getPlayerTable().getPlayer(player);

            if (record == null) {
                continue;
            }

            this.storage[GameStructureView.PLAYER_TYPES_OFFSET + player] = lobbyTypes == null ? (byte) record.getType() : lobbyTypes[player];
            this.storage[GameStructureView.PLAYER_RACES_OFFSET + player] = lobbyRaces == null ? (byte) record.getRace() : lobbyRaces[player];

            this.writeDword(GameStructureView.PLAYER_COLORS_OFFSET + player * Integer.BYTES, record.getColor());

            this.storage[GameStructureView.PLAYER_FORCES_OFFSET + player] = (byte) (record.getType() == PlayerTypes.NONE ? 0 : record.getTeam() + 1);

            if (record.getType() == PlayerTypes.HUMAN) {
                activePlayers++;
            }
        }

        this.storage[GameStructureView.ACTIVE_PLAYER_COUNT_OFFSET] = (byte) activePlayers;

        // The forces and force flags the game started with.
        byte[] forces = world.getGameContext().getForceSnapshot();

        if (forces != null) {
            System.arraycopy(forces, 0, this.storage, GameStructureView.PLAYER_FORCES_OFFSET, forces.length);
        }

        this.storage[GameStructureView.CONSOLE_RACE_OFFSET] = (byte) this.findConsoleRace();
        // A network game, a replay and an observer store 0.
        this.storage[GameStructureView.SINGLE_PLAYER_OFFSET] = (byte) (lobbyTypes == null && !world.getLocalVision().isReplayOrObserver() ? 1 : 0);
    }

    /**
     * Finds the console race of the game start: Terran for a replay or an observer, otherwise the stored race of the local
     * player.
     *
     * @return the console race
     */
    private int findConsoleRace() {
        int localPlayer = LittleEndianUtils.readInt32(this.storage, GameStructureView.LOCAL_PLAYER_OFFSET);

        if (this.world.getLocalVision().isReplayOrObserver() || localPlayer < 0 || localPlayer >= PlayerTable.PLAYER_SLOTS) {
            return ScrRaceIds.TERRAN;
        }

        return this.storage[GameStructureView.PLAYER_RACES_OFFSET + localPlayer] & BYTE_MASK;
    }

    /**
     * Reads one byte this class serves.
     *
     * @param offset a game-context offset that {@link #isRepresentedOffset} accepts
     * @return the unsigned byte
     */
    int readByte(int offset) {
        if (offset >= GameStructureView.FRAME_OFFSET && offset < GameStructureView.FRAME_OFFSET + Integer.BYTES) {
            return this.world.getCurrentFrame() >>> (offset - GameStructureView.FRAME_OFFSET) * Byte.SIZE & BYTE_MASK;
        }

        if (offset >= GameStructureView.TILESET_OFFSET && offset < GameStructureView.TILESET_OFFSET + Short.BYTES) {
            return this.world.getGameContext().getContextTileset() >>> (offset - GameStructureView.TILESET_OFFSET) * Byte.SIZE & BYTE_MASK;
        }

        if (offset >= AVAILABILITY_OFFSET && offset < AVAILABILITY_END) {
            PlayerUnitAvailability availability = this.world.getPlayerTable().getPlayerUnitAvailability();
            int index = offset - AVAILABILITY_OFFSET;

            return availability == null ? 1 : availability.readByte(index / UnitsDat.UNIT_COUNT, index % UnitsDat.UNIT_COUNT);
        }

        if (offset == GameStructureView.EXPANSION_OFFSET) {
            PlayerUpgrades upgrades = this.world.getPlayerTable().getPlayerUpgrades();

            return upgrades == null ? 0 : upgrades.getExpansion().getValue();
        }

        if (offset >= PAUSES_OFFSET && offset < PAUSES_OFFSET + PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return this.world.getGameContext().getContextPauses()[offset - PAUSES_OFFSET] & BYTE_MASK;
        }

        StartLocation start = this.findStartLocation(offset);

        if (start != null) {
            int field = offset - GameStructureView.START_LOCATIONS_OFFSET & ClassicRegion.DWORD_OFFSET_MASK;
            int word = field < Short.BYTES ? start.getX() : start.getY();

            return word >>> (field & 1) * Byte.SIZE & BYTE_MASK;
        }

        if (this.colorRows != null && offset >= UNIT_COLOR_ROWS_OFFSET && offset < UNIT_COLOR_ROWS_OFFSET + PlayerColorRows.SIZE) {
            return this.colorRows.readByte(offset - UNIT_COLOR_ROWS_OFFSET);
        }

        return this.storage[offset] & BYTE_MASK;
    }

    /**
     * Stores one byte this class serves.
     *
     * @param offset a game-context offset that {@link #isRepresentedOffset} accepts
     * @param value  the byte in the low eight bits
     * @throws UnsupportedOperationException if the world cannot hold the written expansion flag or unit availability
     */
    void writeByte(int offset, int value) {
        if (offset >= GameStructureView.TILESET_OFFSET && offset < GameStructureView.TILESET_OFFSET + Short.BYTES) {
            int shift = (offset - GameStructureView.TILESET_OFFSET) * Byte.SIZE;
            int tileset = this.world.getGameContext().getContextTileset();

            this.world.getGameContext().setContextTileset(LittleEndianUtils.replaceByte(tileset, shift, value));

            return;
        }

        if (offset >= GameStructureView.FRAME_OFFSET && offset < GameStructureView.FRAME_OFFSET + Integer.BYTES) {
            int shift = (offset - GameStructureView.FRAME_OFFSET) * Byte.SIZE;

            this.world.storeCurrentFrame(LittleEndianUtils.replaceByte(this.world.getCurrentFrame(), shift, value));

            return;
        }

        if (offset == GameStructureView.EXPANSION_OFFSET) {
            PlayerUpgrades upgrades = this.world.getPlayerTable().getPlayerUpgrades();

            if (upgrades == null) {
                throw Exceptions.newUnsupportedOperationException("EUD write of 0x{0} to the expansion flag of a world without upgrades.",
                                                                  Integer.toHexString(value & BYTE_MASK));
            }

            upgrades.getExpansion().setValue(value);

            return;
        }

        if (offset >= AVAILABILITY_OFFSET && offset < AVAILABILITY_END) {
            PlayerUnitAvailability availability = this.world.getPlayerTable().getPlayerUnitAvailability();

            // A world without established availability serves 1 for every unit; storing that value changes nothing.
            if (availability == null && (value & BYTE_MASK) == 1) {
                return;
            }

            if (availability == null) {
                throw Exceptions.newUnsupportedOperationException("EUD write to the unit availability without established availability.");
            }

            int index = offset - AVAILABILITY_OFFSET;

            availability.writeByte(index / UnitsDat.UNIT_COUNT, index % UnitsDat.UNIT_COUNT, value);

            return;
        }

        if (this.colorRows != null && offset >= UNIT_COLOR_ROWS_OFFSET && offset < UNIT_COLOR_ROWS_OFFSET + PlayerColorRows.SIZE) {
            this.colorRows.writeByte(offset - UNIT_COLOR_ROWS_OFFSET, value);

            return;
        }

        if (offset >= PAUSES_OFFSET && offset < PAUSES_OFFSET + PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            this.world.getGameContext().getContextPauses()[offset - PAUSES_OFFSET] = (byte) value;

            return;
        }

        StartLocation start = this.findStartLocation(offset);

        if (start != null) {
            int field = offset - GameStructureView.START_LOCATIONS_OFFSET & ClassicRegion.DWORD_OFFSET_MASK;
            int shift = (field & 1) * Byte.SIZE;
            int positionX = start.getX() & WORD_MASK;
            int positionY = start.getY() & WORD_MASK;

            if (field < Short.BYTES) {
                positionX = LittleEndianUtils.replaceByte(positionX, shift, value);
            } else {
                positionY = LittleEndianUtils.replaceByte(positionY, shift, value);
            }

            this.world.getPlayerTable().getStartLocations().set(start.getOwner(), new StartLocation(start.getOwner(), positionX, positionY));

            return;
        }

        this.storage[offset] = (byte) value;
    }

    /**
     * Finds the world's start location whose coordinate WORDs contain the requested byte.
     *
     * @param offset a game-context offset
     * @return the start location, or {@code null} when the byte is no start location byte or the world has none
     */
    private @Nullable StartLocation findStartLocation(int offset) {
        if (offset < GameStructureView.START_LOCATIONS_OFFSET ||
            offset >= GameStructureView.START_LOCATIONS_OFFSET + PlayerTable.PLAYABLE_PLAYER_SLOTS * Integer.BYTES) {
            return null;
        }

        List<StartLocation> starts = this.world.getPlayerTable().getStartLocations();
        int owner = (offset - GameStructureView.START_LOCATIONS_OFFSET) / Integer.BYTES;

        if (starts.size() != PlayerTable.PLAYABLE_PLAYER_SLOTS || starts.get(owner).getOwner() != owner) {
            return null;
        }

        return starts.get(owner);
    }

    private void writeDword(int offset, int value) {
        for (int index = 0; index < Integer.BYTES; index++) {
            this.storage[offset + index] = (byte) (value >>> index * Byte.SIZE);
        }
    }
}
