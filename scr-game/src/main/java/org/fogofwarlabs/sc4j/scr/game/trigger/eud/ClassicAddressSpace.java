package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerPlayerPhase;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.map.MapStringSection;
import org.fogofwarlabs.sc4j.scr.game.movement.UnitMovement;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.path.LocalPathSearch;
import org.fogofwarlabs.sc4j.scr.game.player.*;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.production.CostTests;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpritePool;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.trigger.ScrTriggerSystem;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerModifiers;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.*;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.unit.ClassicSpriteTableRegion;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.unit.ClassicUnitPointerRegion;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.unit.ClassicUnitTableRegion;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.fogofwarlabs.sc4j.scr.game.visibility.MapVisibility;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.function.IntConsumer;

/**
 * Resolves extended Deaths operands to wrapping classic addresses and reads or writes the represented regions.
 * Region access updates the domain owner, and bytes past a region's end read as zero. {@link ClassicAccessFailure}
 * notifies the failure listener on the trigger paths; an unrepresented access throws {@link UnsupportedOperationException}.
 * Set, add and subtract use the value modifier, with subtraction clamped at zero.
 */
public final class ClassicAddressSpace implements ScrTriggerSystem.ExtendedDeathsOperations {
    /**
     * The classic address of the death table, the base of the extended Deaths address formula.
     */
    public static final int DEATH_TABLE_ADDRESS = 0x58A364;
    /**
     * The classic address of the game-data block (region type {@code 0x0C}).
     */
    public static final int GAME_DATA_ADDRESS = 0x57F0F0;
    /**
     * The size of the game-data block, the game context up to its tail.
     */
    public static final int GAME_DATA_SIZE = 0x1035C;

    /**
     * The mask of an unsigned BYTE.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of an unsigned WORD.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The mask of an unsigned DWORD widened to a QWORD.
     */
    private static final long DWORD_MASK = 0xFFFFFFFFL;
    /**
     * The damage multiplier table.
     */
    private static final int DAMAGE_MULTIPLIERS = 0x515B70;
    /**
     * The cheat flags DWORD.
     */
    private static final int CHEAT_FLAGS = 0x6D5A6C;
    /**
     * The horizontal scratch next exact position of the movement.
     */
    private static final int MOVEMENT_SCRATCH_X = 0x63FED8;
    /**
     * The vertical scratch next exact position of the movement.
     */
    private static final int MOVEMENT_SCRATCH_Y = 0x63FF40;
    /**
     * The air and ground strengths of the computer players.
     */
    private static final int UNIT_STRENGTHS = 0x6BB210;
    /**
     * The horizontal unit finder table.
     */
    private static final int HORIZONTAL_FINDER = 0x66FF78;
    /**
     * The size the horizontal unit finder must have for the region to exist (the finder capacity times eight).
     */
    private static final int HORIZONTAL_FINDER_SIZE = 0x6A40;
    /**
     * The unit finder query markers.
     */
    private static final int FINDER_QUERY_MARKERS = 0x6BD3D0;
    /**
     * The size the query markers must have for the region to exist (unit capacity {@code 1700}).
     */
    private static final int FINDER_QUERY_MARKERS_SIZE = 0x1A94;
    /**
     * The last tested mineral costs per player.
     */
    private static final int MINERAL_COSTS = 0x6CA51C;
    /**
     * The last tested gas costs per player.
     */
    private static final int GAS_COSTS = 0x6CA4EC;
    /**
     * The last tested supply costs per player.
     */
    private static final int SUPPLY_COSTS = 0x6CA4BC;
    /**
     * The visited band count of the local path search.
     */
    private static final int VISITED_BAND_COUNT = 0x6CAF98;
    /**
     * The visited band records of the local path search.
     */
    private static final int VISITED_RECORDS = 0x6CAFA0;
    private static final int VISITED_RECORDS_SIZE = 0x2A00;
    /**
     * The visited band boundaries of the local path search.
     */
    private static final int VISITED_BOUNDARIES = 0x6CD9A0;
    private static final int VISITED_BOUNDARIES_SIZE = 0x204;
    /**
     * The band record map of the local path search.
     */
    private static final int VISITED_BAND_RECORDS = 0x6CDBA8;
    private static final int VISITED_BAND_RECORDS_SIZE = 0x204;
    /**
     * The draw-order XOR buckets of the local vision byte.
     */
    private static final int DRAW_ORDER_BUCKETS = 0x629C90;
    private static final int DRAW_ORDER_BUCKETS_SIZE = 0x100;
    /**
     * The Zerg sound preload list.
     */
    private static final int ZERG_SOUND_PRELOAD = 0x6402B8;
    private static final int ZERG_SOUND_PRELOAD_SIZE = 0x290;
    /**
     * The Terran sound preload list.
     */
    private static final int TERRAN_SOUND_PRELOAD = 0x63FF78;
    private static final int TERRAN_SOUND_PRELOAD_SIZE = 0x33C;
    /**
     * The Protoss sound preload list.
     */
    private static final int PROTOSS_SOUND_PRELOAD = 0x640550;
    private static final int PROTOSS_SOUND_PRELOAD_SIZE = 0x31A;
    /**
     * The thirteen message lines.
     */
    private static final int MESSAGE_LINES = 0x640B60;
    /**
     * The next-line byte of the message lines.
     */
    private static final int MESSAGE_CURSOR = 0x640B58;
    /**
     * The multiplier that combines the provided regions into the state hash ({@link #computeProvidedStateHash}).
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * The game-data block (region type {@code 0x0C}, {@link #GAME_DATA_SIZE} bytes). Represented offsets: the minerals, gas,
     * gathered gas and gathered minerals of every player; the per-player counters of {@link PlayerScores}; the unit and
     * building production scores; the kills and deaths ({@code 228} unit types of {@code 12} players); the technology bytes
     * ({@link PlayerTechnology}) and upgrade bytes ({@link PlayerUpgrades}) byte for byte. Any other offset stops.
     */
    static final class GameDataRegion implements ClassicRegion {
        /**
         * The size of a row of one DWORD per player.
         */
        private static final int PLAYER_ROW_SIZE = PlayerTable.PLAYER_SLOTS * Integer.BYTES;
        /**
         * The resource row of the minerals.
         */
        private static final int MINERALS_ROW = 0;
        /**
         * The resource row of the gas.
         */
        private static final int GAS_ROW = 1;
        /**
         * The resource row of the gathered gas; the last row holds the gathered minerals.
         */
        private static final int GATHERED_GAS_ROW = 2;
        /**
         * The number of resource rows.
         */
        private static final int RESOURCE_ROWS = 4;
        /**
         * The end of the resource rows.
         */
        private static final int RESOURCES_END = RESOURCE_ROWS * PLAYER_ROW_SIZE;
        /**
         * The victory-state bytes of the eight players.
         */
        private static final int VICTORY_STATE_OFFSET = 0xE610;
        /**
         * The vertical alert screen position, after the horizontal one ({@link LocalView#setAlertScreen}).
         */
        private static final int ALERT_SCREEN_Y_OFFSET = GameStructureView.ALERT_SCREEN_OFFSET + Integer.BYTES;

        private static @NotNull UnsupportedOperationException createUnmappedAccessException(int offset) {
            return Exceptions.newUnsupportedOperationException("EUD address 0x{0} (game data +0x{1}) is not represented; native serves it from its " +
                                                               "game-data structure.",
                                                               String.format("%08X", GAME_DATA_ADDRESS + offset),
                                                               String.format("%X", offset));
        }


        private final @NotNull PlayerScores scores;
        private final @Nullable UnitAccounting accounting;
        private final @Nullable Player @Nullable [] players;
        private final @Nullable PlayerTechnology technology;
        private final @Nullable PlayerUpgrades upgrades;
        private final @Nullable PlayerRelations relations;
        private final @Nullable UnitSelection selection;
        private final @Nullable TriggerContext triggers;

        private @Nullable GameMap map;
        private @Nullable LocalView view;
        private @Nullable GameContextFields fields;

        /**
         * Creates a {@link GameDataRegion} instance over the owners of the game-data block.
         *
         * @param triggers   the trigger state, or {@code null}
         * @param scores     the death, kill and score counters
         * @param accounting the production scores, or {@code null}
         * @param players    the twelve players, or {@code null}
         * @param technology the player technology, or {@code null}
         * @param upgrades   the player upgrades, or {@code null}
         * @param relations  the player relations, or {@code null}
         * @param selection  the player selections, or {@code null}
         */
        GameDataRegion(@Nullable TriggerContext triggers,
                       @NotNull PlayerScores scores,
                       @Nullable UnitAccounting accounting,
                       @Nullable Player @Nullable [] players,
                       @Nullable PlayerTechnology technology,
                       @Nullable PlayerUpgrades upgrades,
                       @Nullable PlayerRelations relations,
                       @Nullable UnitSelection selection) {
            this.scores     = scores;
            this.accounting = accounting;
            this.players    = players;
            this.technology = technology;
            this.upgrades   = upgrades;
            this.relations  = relations;
            this.selection  = selection;
            this.triggers   = triggers;

            this.map    = null;
            this.view   = null;
            this.fields = null;
        }

        /**
         * Sets the map whose tile dimensions and the local view whose screen tile the block shows.
         *
         * @param map  the map whose tile dimensions the map size WORDs hold
         * @param view the local view whose screen tile the screen tile WORDs hold
         */
        void setMapAndView(@NotNull GameMap map, @NotNull LocalView view) {
            this.map  = map;
            this.view = view;
        }

        /**
         * Sets the game-context fields no simulation class owns.
         *
         * @param fields the game-context fields
         */
        void setFields(@NotNull GameContextFields fields) {
            this.fields = fields;
        }

        /**
         * @return the classic base address
         */
        @Override
        public int getBase() {
            return GAME_DATA_ADDRESS;
        }

        /**
         * @return the size in bytes
         */
        @Override
        public int getSize() {
            return GAME_DATA_SIZE;
        }


        /**
         * Reads the unsigned byte at an offset.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @return the unsigned byte at the offset
         * @throws UnsupportedOperationException if the offset is a victory-state byte of a missing player
         */
        @Override
        public int readByte(int offset) {
            if (this.fields != null && GameContextFields.isRepresentedOffset(offset)) {
                return this.fields.readByte(offset);
            }

            if (this.technology != null && PlayerTechnology.hasContextOffset(offset)) {
                return this.technology.readContextByte(offset);
            }

            if (this.upgrades != null && PlayerUpgrades.hasContextOffset(offset)) {
                return this.upgrades.readContextByte(offset);
            }

            if (this.triggers != null && TriggerContext.hasGameDataOffset(offset)) {
                return this.triggers.readGameDataByte(offset);
            }

            if (this.relations != null && PlayerRelations.hasContextOffset(offset)) {
                return this.relations.readContextByte(offset);
            }

            if (this.selection != null && UnitSelection.hasContextOffset(offset)) {
                return this.selection.readContextByte(offset);
            }

            if (offset >= VICTORY_STATE_OFFSET && offset < VICTORY_STATE_OFFSET + PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                return this.requirePlayer(offset - VICTORY_STATE_OFFSET, offset).getVictoryState() & BYTE_MASK;
            }

            return this.readByteFromDword(offset);
        }

        /**
         * Stores one byte, changing the domain state that holds it.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @param value  the byte value in the low eight bits
         * @throws UnsupportedOperationException if the region, or this part of it, rejects writes
         */
        @Override
        public void writeByte(int offset, int value) {
            if (this.fields != null && GameContextFields.isRepresentedOffset(offset)) {
                this.fields.writeByte(offset, value);

                return;
            }

            if (this.technology != null && PlayerTechnology.hasContextOffset(offset)) {
                this.technology.writeContextByte(offset, value);

                return;
            }

            if (this.upgrades != null && PlayerUpgrades.hasContextOffset(offset)) {
                this.upgrades.writeContextByte(offset, value);

                return;
            }

            if (this.triggers != null && TriggerContext.hasGameDataOffset(offset)) {
                this.triggers.writeGameDataByte(offset, value);

                return;
            }

            if (this.relations != null && PlayerRelations.hasContextOffset(offset)) {
                this.relations.writeContextByte(offset, value);

                return;
            }

            if (this.selection != null && UnitSelection.hasContextOffset(offset)) {
                this.selection.writeContextByte(offset, value);

                return;
            }

            if (offset >= VICTORY_STATE_OFFSET && offset < VICTORY_STATE_OFFSET + PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                this.requirePlayer(offset - VICTORY_STATE_OFFSET, offset).setVictoryState(value & BYTE_MASK);

                return;
            }

            this.writeByteToDword(offset, value);
        }

        /**
         * Tells whether the four bytes at an offset include a byte served byte by byte.
         *
         * @param offset the byte offset of the DWORD
         * @return {@code true} if the four bytes at the offset include a byte served byte by byte (technologies, upgrades), {@code false} otherwise
         */
        private boolean coversByteTable(int offset) {
            for (int index = 0; index < Integer.BYTES; index++) {
                if (this.fields != null && GameContextFields.isRepresentedOffset(offset + index) ||
                    this.technology != null && PlayerTechnology.hasContextOffset(offset + index) ||
                    this.upgrades != null && PlayerUpgrades.hasContextOffset(offset + index) ||
                    this.triggers != null && TriggerContext.hasGameDataOffset(offset + index) ||
                    this.relations != null && PlayerRelations.hasContextOffset(offset + index) ||
                    this.selection != null && UnitSelection.hasContextOffset(offset + index) ||
                    this.players != null && offset + index >= VICTORY_STATE_OFFSET &&
                    offset + index < VICTORY_STATE_OFFSET + PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                    return true;
                }
            }

            return false;
        }

        /**
         * Reads an aligned little-endian DWORD; regions whose domain values are DWORDs override it to read them whole.
         *
         * @param offset the byte offset of the DWORD, with four bytes inside the region
         * @return the DWORD
         * @throws UnsupportedOperationException if the offset is not represented or its player is missing
         */
        @Override
        public int readDword(int offset) {
            if ((offset & ClassicRegion.DWORD_OFFSET_MASK) != 0 || this.coversByteTable(offset)) {
                return ClassicRegion.super.readDword(offset);
            }

            if (offset < RESOURCES_END) {
                Player player = this.requireResourcePlayer(offset);

                return switch (offset / PLAYER_ROW_SIZE) {
                    case MINERALS_ROW -> player.getMinerals();
                    case GAS_ROW -> player.getGas();
                    case GATHERED_GAS_ROW -> player.getGatheredGas();
                    default -> player.getGatheredMinerals();
                };
            }

            PlayerUnitCounts counts = this.findUnitCounts(offset);

            if (counts != null) {
                return counts.getByNativeIndex(this.getTableIndex(offset));
            }

            int[] counters = this.findPlayerCounters(offset);

            if (counters != null) {
                return counters[this.getTableIndex(offset)];
            }

            if (this.accounting != null && this.accounting.hasContextDword(offset)) {
                return this.accounting.readContextDword(offset);
            }

            if (offset == GameStructureView.SCREEN_TILES_OFFSET && this.view != null) {
                return this.view.readScreenTiles();
            }

            if (offset == GameStructureView.ALERT_SCREEN_OFFSET && this.view != null) {
                return this.view.getAlertScreenX();
            }

            if (offset == ALERT_SCREEN_Y_OFFSET && this.view != null) {
                return this.view.getAlertScreenY();
            }

            if (offset == GameStructureView.MAP_SIZE_OFFSET && this.map != null) {
                return this.map.getWidth() & WORD_MASK | (this.map.getHeight() & WORD_MASK) << Short.SIZE;
            }

            throw createUnmappedAccessException(offset);
        }

        /**
         * Stores a little-endian DWORD; regions whose domain values are DWORDs override it to store them whole.
         *
         * @param offset the byte offset of the DWORD, with four bytes inside the region
         * @param value  the DWORD
         * @throws UnsupportedOperationException if the offset is not represented or its player is missing
         */
        @Override
        public void writeDword(int offset, int value) {
            if ((offset & ClassicRegion.DWORD_OFFSET_MASK) != 0 || this.coversByteTable(offset)) {
                ClassicRegion.super.writeDword(offset, value);

                return;
            }

            if (offset < RESOURCES_END) {
                Player player = this.requireResourcePlayer(offset);

                switch (offset / PLAYER_ROW_SIZE) {
                    case MINERALS_ROW -> player.setMinerals(value);
                    case GAS_ROW -> player.setGas(value);
                    case GATHERED_GAS_ROW -> player.setGatheredGas(value);
                    default -> player.setGatheredMinerals(value);
                }

                return;
            }

            PlayerUnitCounts counts = this.findUnitCounts(offset);

            if (counts != null) {
                counts.setByNativeIndex(this.getTableIndex(offset), value);

                return;
            }

            int[] counters = this.findPlayerCounters(offset);

            if (counters != null) {
                counters[this.getTableIndex(offset)] = value;

                return;
            }

            if (this.accounting != null && this.accounting.hasContextDword(offset)) {
                this.accounting.writeContextDword(offset, value);

                return;
            }

            if (offset == GameStructureView.SCREEN_TILES_OFFSET && this.view != null) {
                this.view.writeScreenTiles(value);

                return;
            }

            if ((offset == GameStructureView.ALERT_SCREEN_OFFSET || offset == ALERT_SCREEN_Y_OFFSET) && this.view != null) {
                this.view.writeAlertScreen(offset == ALERT_SCREEN_Y_OFFSET, value);

                return;
            }

            // The map size WORDs are written only by the map loaders; every reader of the tile dimensions would see a
            // changed value, which the simulation does not represent.
            if (offset == GameStructureView.MAP_SIZE_OFFSET && this.map != null && value == this.readDword(offset)) {
                return;
            }

            throw createUnmappedAccessException(offset);
        }

        private @NotNull Player requirePlayer(int slot, int offset) {
            Player player = this.players == null ? null : this.players[slot];

            if (player == null) {
                throw createUnmappedAccessException(offset);
            }

            return player;
        }

        private @NotNull Player requireResourcePlayer(int offset) {
            int slot = offset % PLAYER_ROW_SIZE / Integer.BYTES;
            Player player = this.players == null ? null : this.players[slot];

            if (player == null) {
                throw createUnmappedAccessException(offset);
            }

            return player;
        }

        private @Nullable PlayerUnitCounts findUnitCounts(int offset) {
            if (offset >= PlayerScores.KILL_TABLE_OFFSET && offset < PlayerScores.DEATH_TABLE_OFFSET) {
                return this.scores.getKillTable();
            }

            if (offset >= PlayerScores.DEATH_TABLE_OFFSET &&
                offset < PlayerScores.DEATH_TABLE_OFFSET + PlayerUnitCounts.NATIVE_COUNT * Integer.BYTES) {
                return this.scores.getDeathTable();
            }

            return null;
        }

        private int @Nullable [] findPlayerCounters(int offset) {
            if (offset >= PlayerScores.PLAYER_COUNTERS_OFFSET && offset < PlayerScores.PLAYER_COUNTERS_END) {
                return this.scores.getPlayerCounters(PlayerScores.PLAYER_COUNTERS_OFFSET +
                                                     (offset - PlayerScores.PLAYER_COUNTERS_OFFSET) / PLAYER_ROW_SIZE * PLAYER_ROW_SIZE);
            }

            return null;
        }

        private int getTableIndex(int offset) {
            if (offset >= PlayerScores.KILL_TABLE_OFFSET && offset < PlayerScores.DEATH_TABLE_OFFSET) {
                return (offset - PlayerScores.KILL_TABLE_OFFSET) / Integer.BYTES;
            }

            if (offset >= PlayerScores.DEATH_TABLE_OFFSET) {
                return (offset - PlayerScores.DEATH_TABLE_OFFSET) / Integer.BYTES;
            }

            return (offset - PlayerScores.PLAYER_COUNTERS_OFFSET) % PLAYER_ROW_SIZE / Integer.BYTES;
        }
    }

    /**
     * The thirteen message lines (region type {@code 0x28}), mapped byte by byte onto the line buffer.
     */
    static final class MessageLineRegion implements ClassicRegion {
        private final @NotNull MessageLines lines;

        /**
         * Creates a {@link MessageLineRegion} instance.
         *
         * @param lines the message lines of the world
         */
        MessageLineRegion(@NotNull MessageLines lines) {
            this.lines = lines;
        }

        /**
         * @return the classic base address
         */
        @Override
        public int getBase() {
            return MESSAGE_LINES;
        }

        /**
         * @return the size in bytes
         */
        @Override
        public int getSize() {
            return MessageLines.LINE_COUNT * MessageLines.LINE_SIZE;
        }

        /**
         * Reads the unsigned byte at an offset.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @return the unsigned byte at the offset
         * @throws UnsupportedOperationException if the message lines are not observable
         */
        @Override
        public int readByte(int offset) {
            this.lines.requireObservable();

            return this.lines.readByte(offset);
        }

        /**
         * Stores one byte, changing the domain state that holds it.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @param value  the byte value in the low eight bits
         */
        @Override
        public void writeByte(int offset, int value) {
            // The byte offset selects a line and a column in the line buffer.
            this.lines.writeByte(offset, value);
        }
    }

    /**
     * The next-line byte (a one-byte region). A DWORD at its address would extend past the one-byte region and therefore
     * stops.
     */
    static final class MessageCursorRegion implements ClassicRegion {
        private final @NotNull MessageLines lines;

        /**
         * Creates a {@link MessageCursorRegion} instance.
         *
         * @param lines the message lines of the world
         */
        MessageCursorRegion(@NotNull MessageLines lines) {
            this.lines = lines;
        }

        /**
         * @return the classic base address
         */
        @Override
        public int getBase() {
            return MESSAGE_CURSOR;
        }

        /**
         * @return the size in bytes
         */
        @Override
        public int getSize() {
            return 1;
        }

        /**
         * Reads the unsigned byte at an offset.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @return the unsigned byte at the offset
         * @throws UnsupportedOperationException if the message lines are not observable
         */
        @Override
        public int readByte(int offset) {
            this.lines.requireObservable();

            return this.lines.getNextLine();
        }

        /**
         * Stores one byte, changing the domain state that holds it.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @param value  the byte value in the low eight bits
         */
        @Override
        public void writeByte(int offset, int value) {
            this.lines.setNextLine(value);
        }
    }

    /**
     * Creates a {@link ClassicAddressSpace} instance with the represented regions of a world.
     *
     * @param scores     the death, kill and score counters of the world
     * @param accounting the production scores of the world, or {@code null}
     * @param players    the twelve players of the world, or {@code null}
     * @param lines      the displayed message lines of the world
     * @return the created {@link ClassicAddressSpace} instance
     */
    public static @NotNull ClassicAddressSpace create(@NotNull PlayerScores scores,
                                                      @Nullable UnitAccounting accounting,
                                                      @Nullable Player @Nullable [] players,
                                                      @NotNull MessageLines lines) {
        return create(null, scores, accounting, players, lines);
    }

    /**
     * Creates a {@link ClassicAddressSpace} instance with the represented regions of a world whose trigger state is also
     * shown.
     *
     * @param triggers   the trigger state of the game-data block, or {@code null}
     * @param scores     the death, kill and score counters of the world
     * @param accounting the production scores of the world, or {@code null}
     * @param players    the twelve players of the world, or {@code null}
     * @param lines      the displayed message lines of the world
     * @return the created {@link ClassicAddressSpace} instance
     */
    public static @NotNull ClassicAddressSpace create(@Nullable TriggerContext triggers,
                                                      @NotNull PlayerScores scores,
                                                      @Nullable UnitAccounting accounting,
                                                      @Nullable Player @Nullable [] players,
                                                      @NotNull MessageLines lines) {
        return create(new GameDataRegion(triggers, scores, accounting, players, null, null, null, null), lines);
    }

    private static @NotNull ClassicAddressSpace create(@NotNull GameDataRegion gameData, @NotNull MessageLines lines) {
        ClassicAddressSpace space = new ClassicAddressSpace();

        space.add(gameData);
        space.add(new MessageLineRegion(lines));
        space.add(new MessageCursorRegion(lines));

        return space;
    }

    /**
     * Creates a {@link ClassicAddressSpace} instance with a world's represented regions.
     *
     * @param world the world
     * @return the created {@link ClassicAddressSpace} instance
     * @throws UnsupportedOperationException if the world has no unit accounting
     */
    public static @NotNull ClassicAddressSpace create(@NotNull Game world) {
        return create(world.getTriggerContext(), world);
    }

    /**
     * Creates a {@link ClassicAddressSpace} instance with a world's represented regions and the trigger state of a
     * context and the regions of the world's memory sources ({@link ClassicMemorySource}), and hands a read-only view of it to the world's
     * plugins ({@link ScrGamePlugin#onAddressSpaceCreated}).
     *
     * @param triggers the trigger context whose state the game-data block shows, or {@code null}
     * @param world    the world
     * @return the created {@link ClassicAddressSpace} instance
     * @throws UnsupportedOperationException if the world has no unit accounting
     * @throws NullPointerException          if the world was built without game data
     */
    public static @NotNull ClassicAddressSpace create(@Nullable TriggerContext triggers, @NotNull Game world) {
        Player[] players = new Player[PlayerTable.PLAYER_SLOTS];

        for (int player = 0; player < PlayerTable.PLAYER_SLOTS; player++) {
            players[player] = world.getPlayerTable().getPlayer(player);
        }

        UnitAccounting accounting = world.getPlayerTable().getUnitAccounting();

        if (accounting == null) {
            throw Exceptions.newUnsupportedOperationException("The EUD address space requires the world's accounting.");
        }

        GameDataRegion gameData = new GameDataRegion(triggers,
                                                     accounting.getScores(),
                                                     accounting,
                                                     players,
                                                     world.getPlayerTable().getPlayerTechnology(),
                                                     world.getPlayerTable().getPlayerUpgrades(),
                                                     world.getPlayerTable().getPlayerRelations(),
                                                     world.getSelection());

        gameData.setMapAndView(world.getMap(), world.getInterfaceLink().getLocalView());
        gameData.setFields(new GameContextFields(world, triggers == null ? -1 : triggers.getLocalPlayer()));

        ClassicAddressSpace space = create(gameData, world.getInterfaceLink().getMessageLines());

        space.add(new ClassicKeyStateRegion(world.getInterfaceLink().getLocalKeyStates()));

        if (triggers != null) {
            space.add(ClassicHostValueRegion.createCurrentPlayerRegion(triggers));
            space.addAll(ClassicShadowedHostRegion.createLocalPlayerRegions(triggers::getLocalPlayer));
            space.add(ClassicHostValueRegion.createMultiplayerSessionRegion(triggers));
            space.add(ClassicHostValueRegion.createTriggerCycleRegion(triggers));
        }

        // The per-speed timeouts (GameSpeed#fillTimeouts) and the turn-wait budget of the turn gate they refill.
        GameSpeed timing = triggers == null ? null : triggers.getFrameTiming();

        if (timing != null && timing.getMultiplayerSession() == triggers.getMultiplayerSession()) {
            space.add(ClassicHostTableRegion.createWriteIgnoredRegion(ClassicHostTableRegion.SPEED_TIMEOUTS, timing.getTimeouts()));
            space.add(ClassicHostValueRegion.createTurnBudgetRegion(timing));
        } else if (triggers != null && triggers.getMultiplayerSession() == 0) {
            space.add(ClassicHostTableRegion.createSinglePlayerSpeedTimeoutRegion());
            space.add(ClassicHostValueRegion.createSinglePlayerTurnBudgetRegion());
        } else if (triggers != null) {
            space.add(new ClassicUnrepresentedRegion(ClassicHostTableRegion.SPEED_TIMEOUTS, GameSpeed.SPEED_COUNT * Integer.BYTES,
                                                     "the multiplayer per-speed timeouts of a game whose session did not supply " +
                                                     "its turn rate (GameSpeed#startSession)"));
            space.add(new ClassicUnrepresentedRegion(ClassicHostValueRegion.TURN_BUDGET, Integer.BYTES,
                                                     "the multiplayer turn-wait budget of a game whose session did not supply " +
                                                     "its turn rate"));
        }

        if (triggers != null && triggers.getFrameTiming() != null) {
            space.addAll(ClassicHostValueRegion.createFrameDurationRegions(triggers.getFrameTiming()));
        }

        space.add(new ClassicPlayerInfoRegion(players, world.getGameContext().getGameHeaderSection()));
        space.addAll(ClassicHostValueRegion.create(world));
        space.addAll(ClassicHostValueRegion.createLocalInterfaceRegions(world));

        for (int address : NativeCodeConstants.ADDRESSES) {
            space.add(new ClassicCodeConstantRegion(address, world.getGameContext().getCodeConstants()));
        }

        UnitPool pool = world.getUnitSystem().getUnitPool();

        // The classic table has 1700 entries whatever the unit capacity; with the extended unit limit the units from slot
        // 1700 convert past the table and no entry names them.
        if (pool != null && pool.getCapacity() >= ClassicUnitTableRegion.ENTRY_COUNT) {
            space.add(new ClassicUnitTableRegion(pool, world.requireGameData().getUnitTypes(), world.getSpriteSystem().getSpritePool()));
            space.add(ClassicUnitPointerRegion.createOwnerListRegion(pool));
            space.add(ClassicUnitPointerRegion.createUnitListRegion(pool));
            space.add(ClassicHostValueRegion.createUnitsInUseRegion(pool));
            space.add(ClassicUnitPointerRegion.createLocalSelectionRegion(pool, world.getInterfaceLink()::getLocalSelection));

            if (world.getSelection() != null) {
                space.add(ClassicUnitPointerRegion.createPlayerSelectionRegion(pool, world.getSelection()));
            }

            space.add(ClassicUnitPointerRegion.createInterfaceSelectionRegion(pool, world.getInterfaceLink().getLocalInterface()));
        }

        SpritePool sprites = world.getSpriteSystem().getSpritePool();

        if (sprites != null && sprites.getCapacity() >= ClassicSpriteTableRegion.ENTRY_COUNT) {
            space.add(new ClassicSpriteTableRegion(sprites));
        }

        space.add(ClassicStorageRegion.createImageTableRegion());
        space.add(ClassicStorageRegion.createBulletTableRegion());
        space.add(ClassicStorageRegion.createOrderTableRegion());
        space.addAll(ClassicStorageRegion.createEmulatorStorageRegions());
        space.addAll(ClassicStorageRegion.createContainerSelfRegions());
        space.addAll(ClassicDatRegion.create(world.requireGameData()));
        space.addAll(List.of(ClassicHostTableRegion.createRequirementScriptRegions(world.getRequirements().getScripts())));

        if (world.getFrame() != null) {
            space.add(ClassicHostValueRegion.createActiveBulletCountRegion(world.getFrame().getProjectiles()));
        }

        space.poolHeads = ClassicPoolHeads.create(world);
        space.addAll(space.poolHeads.getRegions());
        space.add(new ClassicHostTableRegion(ClassicHostTableRegion.FLEE_DISTANCES, world.getUnitDamage().getFleeDistances()));
        space.add(new ClassicHostTableRegion(DAMAGE_MULTIPLIERS, world.getUnitDamage().getDamageMultipliers()));
        space.addAll(ClassicStorageRegion.createTerrainContourRegions());
        space.add(ClassicHostByteTableRegion.createImageDamageOverlayRegion(world.requireGameData()));

        if (world.getFrame() != null) {
            // The cheat flags are a plain DWORD store.
            MapVisibility visibility = world.getFrame().getVisibility();

            space.add(new ClassicHostValueRegion(CHEAT_FLAGS, visibility::getCheatFlags, visibility::setCheatFlags));

            UnitMovement.Context movement = world.getFrame().getMovement();

            space.add(new ClassicHostValueRegion(MOVEMENT_SCRATCH_X,
                                                 () -> movement.readScratchFixedPosition(false),
                                                 value -> movement.writeScratchFixedPosition(false, value)));
            space.add(new ClassicHostValueRegion(MOVEMENT_SCRATCH_Y,
                                                 () -> movement.readScratchFixedPosition(true),
                                                 value -> movement.writeScratchFixedPosition(true, value)));
        }

        if (world.getUnitStrengthTable() != null) {
            space.add(new ClassicHostTableRegion(UNIT_STRENGTHS, world.getUnitStrengthTable().getLiveStrengths()));
        }

        // The write-ignored unit finder regions require the classic host sizes.
        UnitSpatialIndex finder = world.getUnitSystem().getUnitSpatialIndex();

        if (finder != null && finder.getHorizontalFinderSize() == HORIZONTAL_FINDER_SIZE) {
            space.add(ClassicHostByteTableRegion.createWriteIgnoredRegion(HORIZONTAL_FINDER, HORIZONTAL_FINDER_SIZE,
                                                                          finder::readHorizontalFinderByte));
        }

        if (finder != null && finder.getQueryMarkers().length * Integer.BYTES == FINDER_QUERY_MARKERS_SIZE) {
            space.add(ClassicHostTableRegion.createWriteIgnoredRegion(FINDER_QUERY_MARKERS, finder.getQueryMarkers()));
        }

        CostTests costs = world.getCostTests();

        space.add(new ClassicHostTableRegion(MINERAL_COSTS, costs.getMineralCosts()));
        space.add(new ClassicHostTableRegion(GAS_COSTS, costs.getGasCosts()));
        space.add(new ClassicHostTableRegion(SUPPLY_COSTS, costs.getSupplyCosts()));

        LocalPathSearch localPath = world.getPathSystem().getLocalPathSearch();

        if (localPath != null) {
            space.add(new ClassicHostValueRegion(VISITED_BAND_COUNT, localPath::getVisitedBandCount, localPath::setVisitedBandCount));
            space.add(new ClassicHostTableRegion(VISITED_RECORDS, localPath.getVisitedRecords()));
            space.add(new ClassicHostTableRegion(VISITED_BOUNDARIES, localPath.getVisitedBoundaries()));
            space.add(new ClassicHostTableRegion(VISITED_BAND_RECORDS, localPath.getVisitedBandRecords()));
        } else {
            int[] bandRegions = {
                    VISITED_BAND_COUNT, Integer.BYTES, VISITED_RECORDS, VISITED_RECORDS_SIZE, VISITED_BOUNDARIES, VISITED_BOUNDARIES_SIZE,
                    VISITED_BAND_RECORDS, VISITED_BAND_RECORDS_SIZE
            };

            for (int index = 0; index < bandRegions.length; index += NativeRegionLayout.REGION_ROW_SIZE) {
                space.add(new ClassicUnrepresentedRegion(bandRegions[index], bandRegions[index + 1],
                                                         "a local path search band table of a world without a local path search"));
            }
        }

        // The local rendering scratch has no represented bucket-index calculation.
        space.add(new ClassicUnrepresentedRegion(DRAW_ORDER_BUCKETS, DRAW_ORDER_BUCKETS_SIZE,
                                                 "the draw-order XOR buckets of the local vision byte, " +
                                                 "local rendering scratch whose bucket index is not represented"));

        // Each game's regions hold their own copy of the sound preload lists.
        int[] preloadBases = {ZERG_SOUND_PRELOAD, TERRAN_SOUND_PRELOAD, PROTOSS_SOUND_PRELOAD};
        int[] preloadSizes = {ZERG_SOUND_PRELOAD_SIZE, TERRAN_SOUND_PRELOAD_SIZE, PROTOSS_SOUND_PRELOAD_SIZE};

        for (int race = 0; race < preloadBases.length; race++) {
            try {
                space.add(new ClassicStorageRegion(preloadBases[race], world.requireGameData().getSoundPreloadList(race).clone()));
            } catch (IOException exception) {
                space.add(new ClassicUnrepresentedRegion(preloadBases[race], preloadSizes[race],
                                                         "a sound preload list, whose rez/sfx.json could not be read (" +
                                                         exception.getMessage() + ")"));
            }
        }

        space.add(new ClassicReplayFlagRegion(world));

        byte[] header = world.getGameContext().getGameHeaderSection();

        if (header != null) {
            space.add(new ClassicStorageRegion(ClassicReplayFlagRegion.GAME_HEADER_ADDRESS, header, true));
            space.add(new ClassicGameInfoRegion(header));
        }

        // The game context tail: storage and the lobby color bytes that the game start resolves (LiveGame#startSession),
        // in every game mode.
        space.add(new ClassicContextTailRegion(world));

        for (ClassicViewRegion region : ClassicViewRegion.create(world.getInterfaceLink().getLocalView())) {
            space.add(region);
        }

        MapStringSection strings = world.getInterfaceLink().getMessageLines().getMapStringSection();
        ComputerPlayerPhase computers = world.getComputerPlayerPhase();

        space.addAll(ClassicDynamicRegionsUtils.create(strings,
                                                       world.getGameData(),
                                                       world.getUnitSystem().getUnitSpatialIndex() == null
                                                       ? null
                                                       : world.getUnitSystem().getUnitSpatialIndex().getFlyingUnitCounts(),
                                                       computers == null ? null : computers.getScriptBuffer(false),
                                                       computers == null ? null : computers.getScriptBuffer(true)));

        if (world.getGameContext().getCodeConstants().hasSnapshot()) {
            space.buttonSets = ClassicButtonSets.create(world.getGameContext().getCodeConstants(), world.getGameContext().getHostButtonSets());

            if (space.buttonSets != null) {
                space.addAll(space.buttonSets.getRegions());
            }

            // The type-7 regions wider than a DWORD serve their 1.16 records, the emulation's own copies, unless a domain region shows them.
            for (int index = 0; index < NativeCodeConstants.SNAPSHOT_REGIONS.length; index += NativeRegionLayout.REGION_ROW_SIZE) {
                int base = NativeCodeConstants.SNAPSHOT_REGIONS[index];
                int size = NativeCodeConstants.SNAPSHOT_REGIONS[index + 1];
                byte[] bytes = world.getGameContext().getCodeConstants().getSnapshot(base, size);

                if (bytes != null && space.isFree(base, size)) {
                    space.add(ClassicStorageRegion.createEmulatorStorageRegion(base, bytes));
                }
            }
        }

        space.statText = ClassicStatTextRegion.load(world.requireGameData());
        space.add(space.statText);

        if (triggers != null && triggers.getExtendedDeathsMode() != 0) {
            space.addAll(triggers.getClassicTriggerRegions(
                    ClassicDynamicRegionsUtils.getFirstTriggerNodeBase(strings == null ? 0 : strings.getSize())
            ));
        }

        byte[] mapPath = world.getGameContext().getMapArchivePath();
        byte[] mapFile = world.getGameContext().getMapArchiveFile();
        // The game builds the archive list only when its source file path is not empty and names a file.
        boolean archiveList = mapPath != null && mapFile != null && world.getGameContext().hasMapArchiveFile();
        boolean archivesKnown = world.getGameContext().getCodeConstants().hasStormArchives();

        if (archiveList && archivesKnown) {
            // The archive list is built after the trigger nodes; the allocator continues past the highest region.
            int next = ClassicDynamicRegionsUtils.getFirstTriggerNodeBase(strings == null ? 0 : strings.getSize());

            for (ClassicRegion region : space.regions) {
                long end = (region.getBase() & DWORD_MASK) + region.getSize();

                if (Integer.compareUnsigned(region.getBase(), NativeRegionLayout.MAP_REGION_BASE) >= 0 && end > (next & DWORD_MASK)) {
                    next = NativeRegionLayout.alignAddress((int) end);
                }
            }

            space.addAll(ClassicStormArchives.create(world.getGameContext().getCodeConstants().getStormArchives(), mapPath, mapFile, next));
        }

        ClassicConsoleAreasUtils.fill(space, world.getGameContext().getContextStorage()[GameStructureView.CONSOLE_RACE_OFFSET]);

        ClassicMemorySource[] sources = world.getMemorySources();

        if (sources.length != 0) {
            ClassicMemorySink sink = new ClassicMemorySink(space);

            for (ClassicMemorySource source : sources) {
                source.provideRegions(sink);
            }
        }

        // The map-dependent regions are complete with the trigger nodes when the archive list is built or not made at all.
        space.mapRegionsComplete = triggers != null && triggers.getExtendedDeathsMode() != 0 && (!archiveList || archivesKnown);
        space.archiveListAbsent  = !archiveList;

        // The game's plugins observe the complete address space through a read-only view.
        ScrGamePlugin[] plugins = world.getPlugins();

        if (plugins.length != 0) {
            ClassicMemoryView view = new ClassicMemoryView(space);

            for (ScrGamePlugin plugin : plugins) {
                plugin.onAddressSpaceCreated(view);
            }
        }

        return space;
    }

    /**
     * Resolves the classic address of extended Deaths operands.
     *
     * @param playerOperand the player DWORD, used as an index, not a selector
     * @param unitOperand   the unit operand, narrowed to its unsigned WORD
     * @return the {@code 32}-bit classic address, wrapped
     */
    public static int getAddress(int playerOperand, int unitOperand) {
        return DEATH_TABLE_ADDRESS + Integer.BYTES * (playerOperand + PlayerTable.PLAYER_SLOTS * (unitOperand & WORD_MASK));
    }

    /**
     * Tells whether extended Deaths writes ({@link #setExtendedDeaths}) of consecutive player operands of one unit may
     * change a byte range: a write at an unaligned address stores both aligned DWORDs it spans ({@link #writeDword}).
     *
     * @param base    the classic address of the range
     * @param size    the size of the range
     * @param first   the classic address of the first write ({@link #getAddress})
     * @param players the number of writes, each four bytes after the previous
     * @return {@code true} if a byte any write stores lies in the range (unsigned, wrapping), {@code false} otherwise
     */
    public static boolean canWriteDeathsRange(int base, int size, int first, int players) {
        int start = first & ~ClassicRegion.DWORD_OFFSET_MASK;
        int span = (players - 1) * Integer.BYTES + 2 * Integer.BYTES;

        return Integer.compareUnsigned(start - base, size) < 0 || Integer.compareUnsigned(base - start, span) < 0;
    }


    private final @NotNull List<ClassicRegion> regions;
    private @NotNull ClassicRegion @NotNull [] sorted;
    /**
     * The unsigned bases and ends of {@link #sorted}, in the same order. A region's base and size are fixed once it is
     * added (the overlap check of {@link #addAll} relies on it as well).
     */
    private long @NotNull [] sortedBases;
    private long @NotNull [] sortedEnds;

    private @Nullable IntConsumer failureListener;
    private @Nullable WriteWatch writeWatch;
    private int writeWatchStart;
    private int writeWatchSize;
    private @Nullable ClassicStatTextRegion statText;
    private @Nullable ClassicButtonSets buttonSets;
    private @Nullable ClassicPoolHeads poolHeads;
    /**
     * Whether the map-dependent regions from {@link NativeRegionLayout#MAP_REGION_BASE} are all present (the trigger nodes,
     * and the Storm archive list, the last allocation, when the game has one): an address there that no region serves
     * fails access.
     */
    private boolean mapRegionsComplete;
    /**
     * Whether the game has no Storm archive list (its source file path is empty): an address of the list's fixed regions
     * ({@link ClassicStormArchives#isFixedAddress}) fails access.
     */
    private boolean archiveListAbsent;

    private @Nullable ClassicRegion overlay;
    /**
     * The unsigned base and end of {@link #overlay}, read when it is set.
     */
    private long overlayBase;
    private long overlayEnd;

    /**
     * The index in {@link #sorted} of the last successful lookup, tried first by the next one, or {@code -1}.
     */
    private int lastHitIndex;
    /**
     * The index of the successful lookup before {@link #lastHitIndex}, tried second, or {@code -1}.
     */
    private int previousHitIndex;
    /**
     * Counts the region additions ({@link #getLayoutVersion}).
     */
    private int layoutVersion;

    /**
     * The write observers ({@link #addWriteObserver}) and the unsigned bases and ends of their ranges, in the order they were added.
     */
    private @NotNull ClassicWriteObserver @NotNull [] writeObservers;
    private long @NotNull [] observedBases;
    private long @NotNull [] observedEnds;

    /**
     * The regions of the memory sources ({@link #provide}) in the order they were added, with the state hash digest of each and the change count
     * the digest was made at ({@code -1} for none).
     */
    private @NotNull ClassicRegion @NotNull [] provided;
    private int @NotNull [] providedDigests;
    private long @NotNull [] providedChangeCounts;

    /**
     * Creates an empty {@link ClassicAddressSpace} instance.
     */
    public ClassicAddressSpace() {
        this.regions     = new ArrayList<>();
        this.sorted      = new ClassicRegion[0];
        this.sortedBases = new long[0];
        this.sortedEnds  = new long[0];

        this.failureListener    = null;
        this.statText           = null;
        this.buttonSets         = null;
        this.poolHeads          = null;
        this.mapRegionsComplete = false;
        this.archiveListAbsent  = false;

        this.overlay     = null;
        this.overlayBase = 0L;
        this.overlayEnd  = 0L;

        this.lastHitIndex     = -1;
        this.previousHitIndex = -1;
        this.layoutVersion    = 0;

        this.writeObservers = new ClassicWriteObserver[0];
        this.observedBases  = new long[0];
        this.observedEnds   = new long[0];

        this.provided             = new ClassicRegion[0];
        this.providedDigests      = new int[0];
        this.providedChangeCounts = new long[0];
    }

    /**
     * @return a value that changes whenever regions are added or removed
     */
    public int getLayoutVersion() {
        return this.layoutVersion;
    }

    /**
     * @return the regions in the order they were added, without those removed (a view that follows later changes)
     */
    public @NotNull List<ClassicRegion> getRegions() {
        return Collections.unmodifiableList(this.regions);
    }

    /**
     * Sets the listener for {@link ClassicAccessFailure}; the trigger phase stops evaluation through this callback.
     *
     * @param failureListener the listener, receiving the trigger's classic DWORD address, or {@code null}
     */
    public void setFailureListener(@Nullable IntConsumer failureListener) {
        this.failureListener = failureListener;
    }

    /**
     * Observes the DWORDs written into an address range.
     */
    public interface WriteWatch {
        /**
         * A DWORD of the watched range was written.
         *
         * @param address the aligned classic address
         * @param value   the DWORD after the write
         */
        void written(int address, int value);
    }

    /**
     * Watches the aligned DWORD writes of {@link #writeDword} and {@link #setExtendedDeaths} into a range: the watch sees each written
     * value on the simulation thread, after the write.
     *
     * @param start the first classic address
     * @param size  the range size in bytes
     * @param watch the watch, or {@code null} to stop watching
     */
    public void setWriteWatch(int start, int size, @Nullable WriteWatch watch) {
        this.writeWatchStart = start;
        this.writeWatchSize  = size;
        this.writeWatch      = watch;
    }

    private void notifyWatch(int address) {
        WriteWatch watch = this.writeWatch;

        if (watch != null && Integer.compareUnsigned(address - this.writeWatchStart, this.writeWatchSize) < 0) {
            int aligned = address & ~ClassicRegion.DWORD_OFFSET_MASK;

            watch.written(aligned, this.readDword(aligned));
        }
    }

    /**
     * Sets the running trigger record overlaid on its owning region. A write to a later condition, action, flag or cursor
     * changes the record that the next evaluation reads.
     *
     * @param overlay the record's view, inside a region, or {@code null} after the run; its base and size are read now
     */
    public void setOverlay(@Nullable ClassicRegion overlay) {
        this.overlay     = overlay;
        this.overlayBase = overlay == null ? 0 : overlay.getBase() & DWORD_MASK;
        this.overlayEnd  = overlay == null ? 0 : this.overlayBase + overlay.getSize();
    }


    /**
     * Adds a region.
     *
     * @param region the region
     * @throws IllegalArgumentException if it overlaps a region already added
     */
    public void add(@NotNull ClassicRegion region) {
        this.addAll(List.of(region));
    }

    /**
     * Adds regions.
     *
     * @param added the regions
     * @throws IllegalArgumentException if two regions overlap
     */
    public void addAll(@NotNull List<? extends ClassicRegion> added) {
        List<ClassicRegion> all = new ArrayList<>(this.regions);

        all.addAll(added);

        ClassicRegion[] sortedAll = all.toArray(new ClassicRegion[0]);

        Arrays.sort(sortedAll, Comparator.comparingLong(entry -> entry.getBase() & DWORD_MASK));

        for (int index = 1; index < sortedAll.length; index++) {
            ClassicRegion previous = sortedAll[index - 1];
            ClassicRegion region = sortedAll[index];

            if (Long.compareUnsigned(region.getBase() & DWORD_MASK, (previous.getBase() & DWORD_MASK) + previous.getSize()) < 0) {
                throw Exceptions.newIllegalArgumentException("Classic region 0x{0} overlaps 0x{1}.",
                                                             String.format("%08X", region.getBase()),
                                                             String.format("%08X", previous.getBase()));
            }
        }

        this.regions.addAll(added);
        this.setSorted(sortedAll);
    }

    /**
     * Removes a region and rebuilds the sorted lookup tables.
     *
     * @param region the region, which must have been added
     * @throws IllegalArgumentException if the region was not added
     */
    public void remove(@NotNull ClassicRegion region) {
        boolean removed = this.regions.removeIf(entry -> entry == region);

        if (!removed) {
            throw Exceptions.newIllegalArgumentException("Classic region 0x{0} was not added.", String.format("%08X", region.getBase()));
        }

        ClassicRegion[] sortedAll = new ClassicRegion[this.sorted.length - 1];
        int count = 0;

        for (ClassicRegion entry : this.sorted) {
            if (entry != region) {
                sortedAll[count++] = entry;
            }
        }

        this.setSorted(sortedAll);
    }

    private void setSorted(@NotNull ClassicRegion @NotNull [] sortedAll) {
        this.sorted      = sortedAll;
        this.sortedBases = new long[sortedAll.length];
        this.sortedEnds  = new long[sortedAll.length];

        for (int index = 0; index < sortedAll.length; index++) {
            this.sortedBases[index] = sortedAll[index].getBase() & DWORD_MASK;
            this.sortedEnds[index]  = this.sortedBases[index] + sortedAll[index].getSize();
        }

        this.lastHitIndex     = -1;
        this.previousHitIndex = -1;
        this.layoutVersion++;
    }

    /**
     * Adds an observer of the aligned DWORD writes to a range ({@link ClassicWriteObserver}).
     *
     * @param base     the classic address of the range
     * @param size     the size of the range in bytes, at least {@code 1}
     * @param observer the observer
     * @throws IllegalArgumentException if the size is not positive
     */
    void addWriteObserver(int base, int size, @NotNull ClassicWriteObserver observer) {
        if (size <= 0) {
            throw Exceptions.newIllegalArgumentException("The observed range size must be positive: {0}", size);
        }

        int count = this.writeObservers.length;

        this.writeObservers = Arrays.copyOf(this.writeObservers, count + 1);
        this.observedBases  = Arrays.copyOf(this.observedBases, count + 1);
        this.observedEnds   = Arrays.copyOf(this.observedEnds, count + 1);

        this.writeObservers[count] = observer;
        this.observedBases[count]  = base & DWORD_MASK;
        this.observedEnds[count]   = (base & DWORD_MASK) + size;
    }

    /**
     * Adds a region of a memory source ({@link ClassicMemorySink#add}), replacing the emulation storage regions it covers.
     *
     * @param region the region
     * @throws IllegalArgumentException if the region may not be added
     */
    void provide(@NotNull ClassicRegion region) {
        long base = region.getBase() & DWORD_MASK;
        long end = base + region.getSize();

        if (region.getSize() <= 0 || end > (NativeRegionLayout.MAP_REGION_BASE & DWORD_MASK)) {
            throw Exceptions.newIllegalArgumentException("Provided region 0x{0} is empty or reaches the map-dependent regions.",
                                                         String.format("%08X", region.getBase()));
        }

        List<ClassicRegion> replaced = new ArrayList<>();
        long next = base;

        for (int index = 0; index < this.sorted.length; index++) {
            if (this.sortedEnds[index] <= base || this.sortedBases[index] >= end) {
                continue;
            }

            ClassicRegion existing = this.sorted[index];

            if (!existing.isEmulatorStorage() || this.sortedBases[index] < base || this.sortedEnds[index] > end) {
                throw Exceptions.newIllegalArgumentException("Provided region 0x{0} overlaps region 0x{1}, which is not emulation storage it covers.",
                                                             String.format("%08X", region.getBase()),
                                                             String.format("%08X", existing.getBase()));
            }

            requireNativeGap(region, next, this.sortedBases[index]);
            replaced.add(existing);
            next = this.sortedEnds[index];
        }

        requireNativeGap(region, next, end);

        for (ClassicRegion existing : replaced) {
            this.remove(existing);
        }

        this.add(region);

        int count = this.provided.length;

        this.provided             = Arrays.copyOf(this.provided, count + 1);
        this.providedDigests      = Arrays.copyOf(this.providedDigests, count + 1);
        this.providedChangeCounts = Arrays.copyOf(this.providedChangeCounts, count + 1);

        this.provided[count]             = region;
        this.providedChangeCounts[count] = -1;
    }

    private static void requireNativeGap(@NotNull ClassicRegion region, long start, long end) {
        if (start < end && !NativeRegionLayout.isRangeUnmapped((int) start, (int) (end - start))) {
            throw Exceptions.newIllegalArgumentException("Provided region 0x{0} covers native memory at 0x{1} that no region serves.",
                                                         String.format("%08X", region.getBase()),
                                                         String.format("%08X", start));
        }
    }

    /**
     * Mixes the bytes of the memory sources' regions ({@link #provide}) into a state hash; without provided regions it returns the hash
     * unchanged. A region that tracks its changes ({@link ClassicRegion#getChangeCount()}) is read again only after a change.
     *
     * @param hash the hash so far
     * @return the hash with the provided regions mixed in
     */
    public int computeProvidedStateHash(int hash) {
        for (int index = 0; index < this.provided.length; index++) {
            ClassicRegion region = this.provided[index];
            long changeCount = region.getChangeCount();

            if (changeCount < 0 || changeCount != this.providedChangeCounts[index]) {
                byte[] bytes = new byte[region.getSize()];

                region.read(0, bytes, 0, bytes.length);

                this.providedDigests[index]      = Arrays.hashCode(bytes);
                this.providedChangeCounts[index] = changeCount;
            }

            hash = HASH_MULTIPLIER * hash + region.getBase();
            hash = HASH_MULTIPLIER * hash + this.providedDigests[index];
        }

        return hash;
    }

    /**
     * Tells whether an observer's range holds a byte of an aligned DWORD.
     *
     * @param address the aligned classic address
     * @return {@code true} if a write to the DWORD calls an observer, {@code false} otherwise
     */
    private boolean isObserved(int address) {
        long target = address & DWORD_MASK;

        for (int index = 0; index < this.writeObservers.length; index++) {
            if (target + Integer.BYTES > this.observedBases[index] && target < this.observedEnds[index]) {
                return true;
            }
        }

        return false;
    }

    /**
     * Calls the observers whose range holds an aligned DWORD write.
     *
     * @param address the aligned classic address
     * @param value   the DWORD the write stores
     */
    private void notifyWrite(int address, int value) {
        long target = address & DWORD_MASK;

        for (int index = 0; index < this.writeObservers.length; index++) {
            if (target + Integer.BYTES > this.observedBases[index] && target < this.observedEnds[index]) {
                this.writeObservers[index].onWrite(address, value);
            }
        }
    }

    /**
     * Commits the regions a write modified: the {@code stat_txt} copy becomes the world's table and the classic button
     * sets rebuild the world's button set table and a written free-list head rebuilds its pool's free list
     * ({@link ClassicPoolHeads}).
     *
     * @param world the world
     */
    public void commitModifiedRegions(@NotNull Game world) {
        if (this.statText != null) {
            this.statText.commit(world);
        }

        if (this.buttonSets != null) {
            this.buttonSets.commit(this);
        }

        if (this.poolHeads != null) {
            this.poolHeads.commit(this);
        }
    }

    /**
     * Finds the region holding an address.
     *
     * @param address the classic address
     * @return the region, or {@code null}
     */
    public @Nullable ClassicRegion findRegion(int address) {
        int index = this.findRegionIndex(address);

        return index < 0 ? null : this.sorted[index];
    }

    /**
     * Finds the index in {@link #sorted} of the region holding an address.
     *
     * @param address the classic address
     * @return the index, or {@code -1}
     */
    private int findRegionIndex(int address) {
        long target = address & DWORD_MASK;
        long[] bases = this.sortedBases;
        long[] ends = this.sortedEnds;
        int last = this.lastHitIndex;

        if (last >= 0 && target >= bases[last] && target < ends[last]) {
            return last;
        }

        int previous = this.previousHitIndex;

        if (previous >= 0 && target >= bases[previous] && target < ends[previous]) {
            this.previousHitIndex = last;
            this.lastHitIndex     = previous;

            return previous;
        }

        int low = 0;
        int high = bases.length - 1;

        while (low <= high) {
            int middle = (low + high) >>> 1;

            if (target < bases[middle]) {
                high = middle - 1;
            } else if (target >= ends[middle]) {
                low = middle + 1;
            } else {
                this.previousHitIndex = last;
                this.lastHitIndex     = middle;

                return middle;
            }
        }

        return -1;
    }

    /**
     * Reads a DWORD and reports {@link ClassicAccessFailure} as {@code false} without telling the failure listener.
     *
     * @param address     the classic address
     * @param destination receives the DWORD at index {@code 0}
     * @return {@code true} if the DWORD was read without an access failure, {@code false} otherwise
     */
    public boolean readQuietly(int address, int @NotNull [] destination) {
        try {
            destination[0] = this.readDword(address);

            return true;
        } catch (ClassicAccessFailure failure) {
            return false;
        }
    }

    /**
     * Reads bytes as the aligned DWORD reads of a trigger condition give them, without reporting an access failure: a range of a plain memory
     * region ({@link ClassicRegion#isPlainMemory()}) outside the overlay is copied whole, other bytes are read by DWORD. The read stops at the first
     * DWORD no represented region serves.
     *
     * @param address     the classic address of the first byte
     * @param destination the receiving array
     * @param offset      the first index written
     * @param length      the number of bytes
     * @return the number of bytes read, {@code length} unless the read stopped
     */
    public int readQuietly(int address, byte @NotNull [] destination, int offset, int length) {
        int[] dword = new int[1];
        int count = 0;

        while (count < length) {
            int current = address + count;
            int regionIndex = this.findRegionIndex(current);

            if (regionIndex >= 0 && this.sorted[regionIndex].isPlainMemory()) {
                long start = current & DWORD_MASK;
                int chunk = (int) Math.min(length - count, this.sortedEnds[regionIndex] - start);

                if (this.overlay == null || start >= this.overlayEnd || start + chunk <= this.overlayBase) {
                    this.sorted[regionIndex].read((int) (start - this.sortedBases[regionIndex]), destination, offset + count, chunk);
                    count += chunk;

                    continue;
                }
            }

            int aligned = current & ~ClassicRegion.DWORD_OFFSET_MASK;

            try {
                if (!this.readQuietly(aligned, dword)) {
                    return count;
                }
            } catch (UnsupportedOperationException exception) {
                return count;
            }

            for (int index = current - aligned; index < Integer.BYTES && count < length; index++) {
                destination[offset + count++] = (byte) (dword[0] >>> index * Byte.SIZE);
            }
        }

        return count;
    }

    /**
     * Reads the DWORD at a classic address as the low-level read of the emulation: an unaligned address combines the two
     * aligned DWORDs it spans, each read through its own region.
     *
     * @param address the classic address
     * @return the DWORD
     * @throws UnsupportedOperationException if no represented region holds the four bytes
     */
    public int readDword(int address) {
        int aligned = address & ~ClassicRegion.DWORD_OFFSET_MASK;

        if (aligned == address) {
            return this.readAligned(address);
        }

        int low = this.readAligned(aligned);
        int high = this.readAligned(aligned + Integer.BYTES);
        int shift = (address - aligned) * Byte.SIZE;

        return low >>> shift | high << (Integer.SIZE - shift);
    }

    /**
     * Reads an aligned DWORD: the bytes past the region's end read {@code 0}.
     *
     * @param address the aligned classic address
     * @return the DWORD
     * @throws ClassicAccessFailure          if no mapped region holds the address
     * @throws UnsupportedOperationException if the access is not represented
     */
    private int readAligned(int address) {
        int regionIndex = this.requireRegionIndex(address);
        ClassicRegion region = this.sorted[regionIndex];
        int offset = (int) ((address & DWORD_MASK) - this.sortedBases[regionIndex]);
        int available = (int) (this.sortedEnds[regionIndex] - (address & DWORD_MASK));

        if (available >= Integer.BYTES && !this.overlapsOverlay(address)) {
            return region.readDword(offset);
        }

        int value = 0;

        for (int index = 0; index < Math.min(available, Integer.BYTES); index++) {
            int overlayOffset = this.getOverlayOffset(address + index);
            int part = overlayOffset >= 0 ? this.overlay.readByte(overlayOffset) : region.readByte(offset + index);

            value |= (part & BYTE_MASK) << (index * Byte.SIZE);
        }

        return value;
    }

    /**
     * Stores the DWORD at a classic address as the low-level write of the emulation: an unaligned address reads the two
     * aligned DWORDs it spans, replaces its bytes and writes both back, each through its own region.
     *
     * @param address the classic address
     * @param value   the DWORD
     * @throws UnsupportedOperationException if no represented region holds the four bytes or it rejects the write
     */
    public void writeDword(int address, int value) {
        int aligned = address & ~ClassicRegion.DWORD_OFFSET_MASK;

        if (aligned == address) {
            this.writeAligned(address, value);
            this.notifyWatch(address);

            return;
        }

        int low = this.readAligned(aligned);
        int high = this.readAligned(aligned + Integer.BYTES);
        int shift = (address - aligned) * Byte.SIZE;

        low  = low & ~(-1 << shift) | value << shift;
        high = high & (-1 << shift) | value >>> (Integer.SIZE - shift);

        this.writeAligned(aligned, low);
        this.writeAligned(aligned + Integer.BYTES, high);
        this.notifyWatch(aligned);
        this.notifyWatch(aligned + Integer.BYTES);
    }

    /**
     * Writes an aligned DWORD: a write-ignored region accepts it without change; bytes past the region's end are dropped.
     *
     * @param address the aligned classic address
     * @param value   the DWORD
     * @throws ClassicAccessFailure          if no mapped region holds the address
     * @throws UnsupportedOperationException if the access is not represented
     */
    private void writeAligned(int address, int value) {
        int regionIndex = this.requireRegionIndex(address);
        ClassicRegion region = this.sorted[regionIndex];

        if (this.writeObservers.length != 0) {
            this.notifyWrite(address, value);
        }

        if (region.isWriteIgnored() && !this.overlapsOverlay(address)) {
            return;
        }

        int offset = (int) ((address & DWORD_MASK) - this.sortedBases[regionIndex]);
        int available = (int) (this.sortedEnds[regionIndex] - (address & DWORD_MASK));

        if (available >= Integer.BYTES && !this.overlapsOverlay(address)) {
            region.writeDword(offset, value);

            return;
        }

        int dwordOverlayOffset = this.getOverlayOffset(address);

        if (available >= Integer.BYTES && dwordOverlayOffset >= 0 && (address & DWORD_MASK) + Integer.BYTES <= this.overlayEnd) {
            // All four bytes lie in the overlay: the same writes as the byte loop below, in one call.
            this.overlay.writeDword(dwordOverlayOffset, value);

            return;
        }

        for (int index = 0; index < Math.min(available, Integer.BYTES); index++) {
            int overlayOffset = this.getOverlayOffset(address + index);

            if (overlayOffset >= 0) {
                this.overlay.writeByte(overlayOffset, value >>> (index * Byte.SIZE));
            } else if (!region.isWriteIgnored()) {
                region.writeByte(offset + index, value >>> (index * Byte.SIZE));
            }
        }
    }

    private boolean isFree(int base, int size) {
        long start = base & DWORD_MASK;

        for (ClassicRegion region : this.regions) {
            long regionStart = region.getBase() & DWORD_MASK;

            if (regionStart < start + size && start < regionStart + region.getSize()) {
                return false;
            }
        }

        return true;
    }

    private boolean overlapsOverlay(int address) {
        if (this.overlay == null) {
            return false;
        }

        long start = address & DWORD_MASK;

        return start + Integer.BYTES > this.overlayBase && start < this.overlayEnd;
    }

    private int getOverlayOffset(int address) {
        if (this.overlay == null) {
            return -1;
        }

        long offset = (address & DWORD_MASK) - this.overlayBase;

        return offset >= 0 && offset < this.overlayEnd - this.overlayBase ? (int) offset : -1;
    }

    /**
     * Reads a DWORD for a trigger and returns {@code 0} after reporting {@link ClassicAccessFailure} to the listener.
     *
     * @param address the classic address
     * @return the DWORD, or {@code 0} after an access failure
     * @throws UnsupportedOperationException if the access is not represented
     */
    public int readForTrigger(int address) {
        try {
            return this.readDword(address);
        } catch (ClassicAccessFailure failure) {
            this.fail(address);

            return 0;
        }
    }

    /**
     * Writes a DWORD for a trigger and reports {@link ClassicAccessFailure} to the listener. Earlier writes remain visible.
     *
     * @param address the classic address
     * @param value   the DWORD
     * @return {@code true} if the DWORD was written without an access failure, {@code false} otherwise
     * @throws UnsupportedOperationException if the access is not represented
     */
    public boolean writeForTrigger(int address, int value) {
        try {
            this.writeDword(address, value);

            return true;
        } catch (ClassicAccessFailure failure) {
            this.fail(address);

            return false;
        }
    }

    /**
     * Reports an access failure to the listener with the trigger's DWORD address rather than an individual byte offset.
     *
     * @param address the classic DWORD address of the trigger access
     */
    private void fail(int address) {
        if (this.failureListener != null) {
            this.failureListener.accept(address);
        }
    }

    /**
     * Reads a DWORD selected by already encoded operands.
     *
     * @param playerOperand the encoded address index, not a player selector
     * @param unitOperand   the unsigned unit WORD
     * @return all bits of the selected DWORD
     */
    @Override
    public int readExtendedDeaths(int playerOperand, int unitOperand) {
        return this.readForTrigger(getAddress(playerOperand, unitOperand));
    }

    /**
     * Sets a DWORD selected by already encoded operands using a supported modifier. Earlier action mutations remain
     * visible if this operation fails.
     *
     * @param playerOperand the encoded address index, not a player selector
     * @param unitOperand   the unsigned unit WORD
     * @param modifier      the encoded action modifier
     * @param amount        the action DWORD
     * @throws UnsupportedOperationException if the access is not represented
     */
    @Override
    public void setExtendedDeaths(int playerOperand, int unitOperand, int modifier, int amount) {
        int address = getAddress(playerOperand, unitOperand);

        try {
            if ((address & ClassicRegion.DWORD_OFFSET_MASK) == 0 && !this.overlapsOverlay(address)) {
                int regionIndex = this.requireRegionIndex(address);
                ClassicRegion region = this.sorted[regionIndex];
                int offset = (int) ((address & DWORD_MASK) - this.sortedBases[regionIndex]);

                if (this.sortedEnds[regionIndex] - (address & DWORD_MASK) >= Integer.BYTES && !region.isWriteIgnored()) {
                    if (this.writeObservers.length != 0 && this.isObserved(address)) {
                        int stored = modifier == TriggerModifiers.SET_TO ? amount : PlayerScores.modify(region.readDword(offset), modifier, amount);

                        this.notifyWrite(address, stored);
                    }

                    region.writeModified(offset, modifier, amount);
                    this.notifyWatch(address);

                    return;
                }
            }

            int value = modifier == TriggerModifiers.SET_TO ? amount : PlayerScores.modify(this.readDword(address), modifier, amount);

            this.writeDword(address, value);
        } catch (ClassicAccessFailure failure) {
            this.fail(address);
        }
    }

    /**
     * Finds the index in {@link #sorted} of the region holding an address.
     *
     * @param address the classic address
     * @return the index of the region holding the address
     * @throws ClassicAccessFailure          if no mapped region holds the address
     * @throws UnsupportedOperationException if the access is not represented
     */
    private int requireRegionIndex(int address) {
        int index = this.findRegionIndex(address);

        if (index >= 0) {
            return index;
        }

        if (NativeRegionLayout.isUnmapped(address) ||
            this.mapRegionsComplete && Integer.compareUnsigned(address, NativeRegionLayout.MAP_REGION_BASE) >= 0 ||
            this.archiveListAbsent && ClassicStormArchives.isFixedAddress(address)) {
            throw new ClassicAccessFailure(address, String.format("EUD address 0x%08X has no native region.", address));
        }

        throw Exceptions.newUnsupportedOperationException("EUD address 0x{0} has no represented emulation region; " +
                                                          "native serves it through its region table.",
                                                          String.format("%08X", address));
    }
}
