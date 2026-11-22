package org.fogofwarlabs.sc4j.scr.game.order;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.replay.SelectionRequest;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.player.ProductionStatistics;
import org.fogofwarlabs.sc4j.scr.game.sprite.SelectionImages;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitHandle;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/**
 * Executes the selection commands against the persistent unit pool: the selection changes in packet order, the hotkey records (control groups and the
 * recent-selection history) with their timestamps, and the scope accounting of the production statistics. Handles are resolved through the pool; this
 * state does not create units or infer handles from CHK serials or provisional list positions.
 * <p>
 * A stored or recalled selection-history record takes the current timestamp: the low WORD of the game's turn count ({@link Game#getTurnCount()}),
 * which the session counts and which does not follow the frame counter.
 * <p>
 * Callers establish the zeroed selection, history and accounting state and the supplied context. The command recording of a live game, after the
 * dispatch of every command, belongs to the session, which records the executed commands ({@code LiveGame}). One simulation thread owns this state.
 * <p>
 * In a team game a command has two identities ({@link #beginCommand}): the owner of the units it commands and the slot of the selection and hotkeys
 * it uses. A teammate's selection marks its units with the dashed team circle count in the sprite flags {@link Sprite#FLAG_TEAM_SELECTION_CIRCLE_1}
 * and {@link Sprite#FLAG_TEAM_SELECTION_CIRCLE_2} and the dashed circle of {@link SelectionImages}.
 */
public final class UnitSelection {
    /**
     * The most units one selection holds.
     */
    public static final int MAXIMUM_SELECTED = 12;

    /**
     * The control groups {@code 0..9} of the hotkey command.
     */
    private static final int CONTROL_GROUP_COUNT = 10;
    /**
     * The selection-history records, hotkey records {@code 10..17} ({@code 18} records a player).
     */
    private static final int HISTORY_COUNT = 8;
    /**
     * The handles of one record.
     */
    private static final int RECORD_SIZE = MAXIMUM_SELECTED;
    /**
     * The hotkey records of one player: the control groups, then the selection history.
     */
    private static final int RECORDS_PER_PLAYER = CONTROL_GROUP_COUNT + HISTORY_COUNT;
    /**
     * The hotkey records of the eight players.
     */
    private static final int RECORD_COUNT = PlayerTable.PLAYABLE_PLAYER_SLOTS * RECORDS_PER_PLAYER;
    /**
     * The game-context offset of the hotkey records, which the EUD game-data block shows.
     */
    private static final int RECORDS_OFFSET = 0xD70;
    /**
     * The last record BYTE the hotkey command accepts.
     */
    private static final int LAST_HOTKEY_RECORD = RECORDS_PER_PLAYER;

    /**
     * The hotkey operation that stores the selection in a record.
     */
    private static final int HOTKEY_SET = 0;
    /**
     * The hotkey operation that recalls a record into the selection.
     */
    private static final int HOTKEY_RECALL = 1;
    /**
     * The hotkey operation that adds the selection to a record.
     */
    private static final int HOTKEY_APPEND = 2;

    /**
     * The largest history timestamp WORD: the oldest-record search starts with it, so a record stamped with it is never chosen.
     */
    private static final int NEWEST_TIMESTAMP = 0xFFFF;

    /**
     * The command identity outside a command dispatch ({@link #beginCommand}).
     */
    private static final int NO_COMMAND = -1;

    /**
     * The offset of the last trap or door unit type from the Floor Missile Trap; these types are never multi-selected.
     */
    private static final int TRAP_LAST_OFFSET = 10;
    /**
     * The unit types from the Zerg Egg that a multiple selection never holds, as bits of their offset from the Zerg Egg: the Egg and the critters
     * Rhynadon, Bengalaas, Scantid, Kakaru, Ragnasaur and Ursadon.
     */
    private static final long SINGLE_SELECTION_TYPES = 0x1E60000000000001L;
    /**
     * The offset of the last unit type in {@link #SINGLE_SELECTION_TYPES} from the Zerg Egg.
     */
    private static final int SINGLE_SELECTION_LAST_OFFSET = 0x3C;
    /**
     * The bit of a unit type in {@link #SINGLE_SELECTION_TYPES} after the shift by its offset from the Zerg Egg.
     */
    private static final int SINGLE_SELECTION_BIT = 1;

    /**
     * The mask of an unsigned WORD: the type offsets and the history timestamps wrap to it.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The mask of an unsigned BYTE of the hotkey records.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * Checks whether a game-context offset lies in the hotkey records.
     *
     * @param contextOffset a game-context offset
     * @return {@code true} if the offset lies in the hotkey records, {@code false} otherwise
     */
    public static boolean hasContextOffset(int contextOffset) {
        return contextOffset >= RECORDS_OFFSET && contextOffset < RECORDS_OFFSET + RECORD_COUNT * RECORD_SIZE * Integer.BYTES;
    }

    /**
     * Checks whether a unit may join a selection of more than one unit. The selection command, the hotkey recall and append, and the local
     * interface's multiple selections all test a unit through this method.
     * <p>
     * The unit is rejected if its type is a building, which is tested for the {@code units.dat} types only, or a powerup, which is tested for every
     * type; if it is disabled ({@link UnitOrderEligibility#isDisabled}); or if its type is a trap or door, the Spider Mine, a single-selection type
     * ({@link #SINGLE_SELECTION_TYPES}), Dark Swarm or Disruption Web.
     *
     * @param unitTypes the unit type table whose flags are tested
     * @param unit      the unit
     * @return {@code true} if the unit may join a multiple selection, {@code false} otherwise
     */
    public static boolean canSelectMultiple(@NotNull ScrUnitTypeView unitTypes, @NotNull Unit unit) {
        int unitType = unit.getUnitId();
        int baseType = unitTypes.getBaseType(unitType);
        int flags = unitTypes.getFlags(unitType);
        int trapOffset = (baseType - ScrUnitIds.SPECIAL_FLOOR_MISSILE_TRAP) & WORD_MASK;
        int exceptionOffset = (baseType - ScrUnitIds.ZERG_EGG) & WORD_MASK;

        return !UnitQueries.isBuilding(unitTypes, unitType) &&
               (flags & UnitTypeFlags.POWERUP) == 0 &&
               !UnitOrderEligibility.isDisabled(unit) &&
               trapOffset > TRAP_LAST_OFFSET &&
               baseType != ScrUnitIds.TERRAN_VULTURE_SPIDER_MINE &&
               !(exceptionOffset <= SINGLE_SELECTION_LAST_OFFSET && ((SINGLE_SELECTION_TYPES >>> exceptionOffset) & SINGLE_SELECTION_BIT) != 0) &&
               baseType != ScrUnitIds.SPELL_DARK_SWARM &&
               baseType != ScrUnitIds.SPELL_DISRUPTION_WEB;
    }

    /**
     * Computes the index into the history timestamps that the recall of a record from {@code 10} stamps.
     *
     * @param playerIndex the selection slot
     * @param groupIndex  the record BYTE from {@code 10}
     * @return the timestamp index
     * @throws UnsupportedOperationException if the index lies past the history timestamps of the eight players
     */
    private static int calculateTimestampIndex(int playerIndex, int groupIndex) {
        int index = playerIndex * HISTORY_COUNT + groupIndex - CONTROL_GROUP_COUNT;

        if (index >= PlayerTable.PLAYABLE_PLAYER_SLOTS * HISTORY_COUNT) {
            throw Exceptions.newUnsupportedOperationException("Hotkey record {0} of player {1} stamps past the represented history timestamps.",
                                                              groupIndex,
                                                              playerIndex);
        }

        return index;
    }

    private static long mix(long hash, int @NotNull [] values) {
        for (int value : values) {
            hash = (hash ^ Integer.toUnsignedLong(value)) * Game.STATE_HASH_PRIME;
        }

        return hash;
    }


    private final @NotNull UnitPool unitPool;
    private final boolean scopeAccountingEnabled;
    private final int teamSize;
    private final int localPlayerIndex;

    private final int @NotNull [] @NotNull [] selections;
    private final int @NotNull [] records;
    private final int @NotNull [] timestamps;

    private final int @NotNull [] scopeTotals;
    private final int @NotNull [] scopeChanges;
    private final float @NotNull [] scopeFloatTotals;
    private final float @NotNull [] scopeFloatChanges;

    private int commandOwner;
    private int commandSlot;
    private @Nullable ProductionStatistics statistics;

    /**
     * Creates a {@link UnitSelection} instance with zeroed selections, hotkey records and accounting.
     *
     * @param unitPool               the persistent unit slots
     * @param scopeAccountingEnabled whether the scope accounting is active
     * @param teamSize               the slots of a team in a team game, or {@code 0} outside a team game
     * @param localPlayerIndex       the local selection slot the team predicate compares
     */
    public UnitSelection(@NotNull UnitPool unitPool, boolean scopeAccountingEnabled, int teamSize, int localPlayerIndex) {
        this.unitPool               = unitPool;
        this.scopeAccountingEnabled = scopeAccountingEnabled;
        this.teamSize               = teamSize;
        this.localPlayerIndex       = localPlayerIndex;

        this.selections = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS][MAXIMUM_SELECTED];
        this.records    = new int[RECORD_COUNT * RECORD_SIZE];
        this.timestamps = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS * HISTORY_COUNT];

        this.scopeTotals       = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.scopeChanges      = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.scopeFloatTotals  = new float[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        this.scopeFloatChanges = new float[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        this.commandOwner = NO_COMMAND;
        this.commandSlot  = NO_COMMAND;
        this.statistics   = null;
    }

    /**
     * @return the slots of a team in a team game, or {@code 0} outside a team game
     */
    public int getTeamSize() {
        return this.teamSize;
    }

    /**
     * @return the local selection slot the team predicate compares
     */
    public int getLocalPlayerIndex() {
        return this.localPlayerIndex;
    }

    /**
     * Returns the unit selected at a position of a player's selection.
     *
     * @param playerIndex the command player index from {@code 0..7}
     * @param position    the selection position from {@code 0..11}
     * @return the selected unit, or {@code null} at the end of the selection
     * @throws IndexOutOfBoundsException if the resolved selection slot or position is outside its table, or a stored nonzero index is outside the
     *                                   unit pool
     */
    public @Nullable Unit getSelectedUnit(int playerIndex, int position) {
        int poolIndex = this.getSelectedPoolIndex(playerIndex, position);

        return poolIndex == 0 ? null : this.unitPool.getUnit(poolIndex);
    }

    /**
     * @param playerIndex the command player index from {@code 0..7}
     * @param position    the selection position from {@code 0..11}
     * @return the selected one-based pool index, or {@code 0} for an empty position
     * @throws IndexOutOfBoundsException if the resolved command slot or selection position is outside its table
     */
    public int getSelectedPoolIndex(int playerIndex, int position) {
        int slot = this.getSelectionSlot(playerIndex);

        return this.selections[Objects.checkIndex(slot, PlayerTable.PLAYABLE_PLAYER_SLOTS)][Objects.checkIndex(position, MAXIMUM_SELECTED)];
    }

    /**
     * Reads one player-selection pointer by its table row, as the saved selections keep them.
     *
     * @param playerIndex the table row from {@code 0..7}
     * @param position    the selection position from {@code 0..11}
     * @return the one-based pool index, or {@code 0} for a null pointer
     * @throws IndexOutOfBoundsException if the raw player row or selection position is outside its table
     */
    public int getSelectedPoolIndexRaw(int playerIndex, int position) {
        return this.selections[Objects.checkIndex(playerIndex, PlayerTable.PLAYABLE_PLAYER_SLOTS)][Objects.checkIndex(position, MAXIMUM_SELECTED)];
    }

    /**
     * Sets one player-selection pointer without any selection bookkeeping, as an EUD write of the classic player selections does.
     *
     * @param playerIndex the player index from {@code 0..7}
     * @param position    the selection position from {@code 0..11}
     * @param poolIndex   the one-based pool index, or {@code 0} for a null pointer
     * @throws IndexOutOfBoundsException if the raw player row or selection position is outside its table
     */
    public void setSelectedPoolIndexRaw(int playerIndex, int position, int poolIndex) {
        int[] selection = this.selections[Objects.checkIndex(playerIndex, PlayerTable.PLAYABLE_PLAYER_SLOTS)];

        selection[Objects.checkIndex(position, MAXIMUM_SELECTED)] = poolIndex;
    }

    /**
     * @param playerIndex the command player index from {@code 0..7}
     * @param groupIndex  the group from {@code 0..9}
     * @param position    the handle position from {@code 0..11}
     * @return the full handle, or {@code 0} at an empty position
     * @throws IndexOutOfBoundsException if the player row, group or handle position is outside its table
     */
    public int getControlGroupHandle(int playerIndex, int groupIndex, int position) {
        Objects.checkIndex(playerIndex, PlayerTable.PLAYABLE_PLAYER_SLOTS);
        Objects.checkIndex(groupIndex, CONTROL_GROUP_COUNT);
        Objects.checkIndex(position, RECORD_SIZE);

        return this.records[(playerIndex * RECORDS_PER_PLAYER + groupIndex) * RECORD_SIZE + position];
    }

    /**
     * @param playerIndex  the command player index from {@code 0..7}
     * @param historyIndex the recent-history position from {@code 0..7}
     * @param position     the handle position from {@code 0..11}
     * @return the encoded handle DWORD of the recent-selection record
     * @throws IndexOutOfBoundsException if the player row, history position or handle position is outside its table
     */
    public int getHistoryHandle(int playerIndex, int historyIndex, int position) {
        Objects.checkIndex(playerIndex, PlayerTable.PLAYABLE_PLAYER_SLOTS);
        Objects.checkIndex(historyIndex, HISTORY_COUNT);

        return this.records[(playerIndex * RECORDS_PER_PLAYER + CONTROL_GROUP_COUNT + historyIndex) * RECORD_SIZE +
                            Objects.checkIndex(position, RECORD_SIZE)];
    }

    /**
     * @param playerIndex  the command player index from {@code 0..7}
     * @param historyIndex the recent-history position from {@code 0..7}
     * @return the unsigned WORD timestamp for the history position
     * @throws IndexOutOfBoundsException if the player row or history position is outside its table
     */
    public int getHistoryTimestamp(int playerIndex, int historyIndex) {
        Objects.checkIndex(playerIndex, PlayerTable.PLAYABLE_PLAYER_SLOTS);

        return this.timestamps[playerIndex * HISTORY_COUNT + Objects.checkIndex(historyIndex, HISTORY_COUNT)];
    }

    /**
     * Sets a history timestamp without changing its handle storage.
     *
     * @param playerIndex  the command player index from {@code 0..7}
     * @param historyIndex the recent-history position from {@code 0..7}
     * @param timestamp    the value stored as a WORD
     * @throws IndexOutOfBoundsException if the player row or history position is outside its table
     */
    public void setHistoryTimestamp(int playerIndex, int historyIndex, int timestamp) {
        Objects.checkIndex(playerIndex, PlayerTable.PLAYABLE_PLAYER_SLOTS);

        this.timestamps[playerIndex * HISTORY_COUNT + Objects.checkIndex(historyIndex, HISTORY_COUNT)] = timestamp & WORD_MASK;
    }

    /**
     * @param playerIndex the command player index from {@code 0..7}
     * @return the DWORD scope total, including scopes with no successful insertion or removal lookup
     * @throws IndexOutOfBoundsException if the player row is outside the playable player slots
     */
    public int getScopeTotal(int playerIndex) {
        Objects.checkIndex(playerIndex, PlayerTable.PLAYABLE_PLAYER_SLOTS);

        return this.statistics != null
               ? this.statistics.getSampleValue(playerIndex * ProductionStatistics.CATEGORY_COUNT + ProductionStatistics.TOTAL_CATEGORY)
               : this.scopeTotals[playerIndex];
    }

    /**
     * @param playerIndex the command player index from {@code 0..7}
     * @return the DWORD count of scopes whose handler marked its changed byte
     * @throws IndexOutOfBoundsException if the player row is outside the playable player slots
     */
    public int getScopeChanges(int playerIndex) {
        Objects.checkIndex(playerIndex, PlayerTable.PLAYABLE_PLAYER_SLOTS);

        return this.statistics != null
               ? this.statistics.getSampleValue(playerIndex * ProductionStatistics.CATEGORY_COUNT + ProductionStatistics.SUMMED_CATEGORY)
               : this.scopeChanges[playerIndex];
    }

    /**
     * @param playerIndex the command player index from {@code 0..7}
     * @return the independently accumulated single-precision total
     * @throws IndexOutOfBoundsException if the player row is outside the playable player slots
     */
    public float getScopeFloatTotal(int playerIndex) {
        Objects.checkIndex(playerIndex, PlayerTable.PLAYABLE_PLAYER_SLOTS);

        return this.statistics != null
               ? this.statistics.getAccumulatedValue(playerIndex * ProductionStatistics.CATEGORY_COUNT + ProductionStatistics.TOTAL_CATEGORY)
               : this.scopeFloatTotals[playerIndex];
    }

    /**
     * @param playerIndex the command player index from {@code 0..7}
     * @return the independently accumulated single-precision changed count
     * @throws IndexOutOfBoundsException if the player row is outside the playable player slots
     */
    public float getScopeFloatChanges(int playerIndex) {
        Objects.checkIndex(playerIndex, PlayerTable.PLAYABLE_PLAYER_SLOTS);

        return this.statistics != null
               ? this.statistics.getAccumulatedValue(playerIndex * ProductionStatistics.CATEGORY_COUNT + ProductionStatistics.SUMMED_CATEGORY)
               : this.scopeFloatChanges[playerIndex];
    }

    /**
     * Checks the complete represented handler path without applying its effects.
     *
     * @param game        the game
     * @param command     the decoded selection request
     * @param playerIndex the full resolved command player DWORD
     * @return {@code true} if the path is represented, {@code false} otherwise
     * @throws NullPointerException  if the world was built without game data
     * @throws IllegalStateException if a requested unit to insert, other than a Nuclear Missile of a replacing selection, has no sprite
     */
    public boolean canExecute(@NotNull Game game, @NotNull SelectionRequest command, int playerIndex) {
        try {
            this.apply(game, command, playerIndex, false, false);

            return true;
        } catch (UnsupportedOperationException exception) {
            return false;
        }
    }

    /**
     * Applies a supported selection request after checking all required effects.
     *
     * @param game        the game
     * @param command     the decoded selection request
     * @param playerIndex the full resolved command player DWORD
     * @throws UnsupportedOperationException if the command player is outside the playable player slots, a legacy selection request holds more than
     *                                       {@code 12} WORD handles, the local slot of a team selection names no represented player record or every
     *                                       history timestamp is {@code 0xFFFF}
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if a requested unit to insert, other than a Nuclear Missile of a replacing selection, has no sprite
     */
    public void execute(@NotNull Game game, @NotNull SelectionRequest command, int playerIndex) {
        this.apply(game, command, playerIndex, true, true);
    }

    /**
     * Replaces the selection of a player slot with units outside a command, as a replacing selection command of the slot's player changes it: the
     * command owner is the command player of the slot's record (the force leader in a team game), the units are taken in order by the rules of
     * the command (a hidden unit, a Nuclear Missile, a unit already taken, and in a selection of more than one unit a unit that cannot join one
     * or that the owner does not own are left out), and the team marks and the selection-history record change as the command changes them.
     * No command ran, so the scope accounting counts nothing. The command identities of a running dispatch are kept.
     *
     * @param game  the game
     * @param slot  the player slot from {@code 0..7} whose selection changes
     * @param units the units in selection order, at most {@link #MAXIMUM_SELECTED}; units of this game's pool
     * @throws IllegalArgumentException      if more than {@link #MAXIMUM_SELECTED} units are given
     * @throws IndexOutOfBoundsException     if the slot is outside the playable player slots
     * @throws UnsupportedOperationException if the command player is not playable, a team record is missing or every history timestamp is {@code 0xFFFF}
     * @throws NullPointerException          if the world was built without game data
     */
    public void select(@NotNull Game game, int slot, @NotNull Unit @NotNull [] units) {
        Objects.checkIndex(slot, PlayerTable.PLAYABLE_PLAYER_SLOTS);

        if (units.length > MAXIMUM_SELECTED) {
            throw Exceptions.newIllegalArgumentException("A selection holds at most {0} units, not {1}.", MAXIMUM_SELECTED, units.length);
        }

        long[] handles = new long[units.length];

        for (int index = 0; index < units.length; index++) {
            handles[index] = Integer.toUnsignedLong(this.unitPool.getHandle(units[index].getIndex()));
        }

        Player record = game.getPlayerTable().getPlayer(slot);
        int owner = record != null ? record.getCommandPlayerIndex() : slot;
        int dispatchOwner = this.commandOwner;
        int dispatchSlot = this.commandSlot;

        this.beginCommand(owner, slot);

        try {
            this.apply(game, new SelectionRequest(SelectionRequest.Operation.REPLACE, Integer.BYTES, handles), owner, true, false);
        } finally {
            this.commandOwner = dispatchOwner;
            this.commandSlot  = dispatchSlot;
        }
    }


    /**
     * Sets the identities of the dispatched command, as the dispatcher sets the command owner and the command selection slot from the sender's storm
     * id.
     * <p>
     * In a team game each human gets the leader of its force as owner but keeps its own record position as the slot of its selection and hotkeys, so
     * teammates select apart and command the same units. Until {@link #endCommand()} the selection of the owner is the selection of the slot. Outside
     * a team game the slot is the owner.
     *
     * @param owner the command player, the sender record's player index
     * @param slot  the sender record's position
     */
    public void beginCommand(int owner, int slot) {
        this.commandOwner = owner;
        this.commandSlot  = this.teamSize != 0 ? slot : owner;
    }

    /**
     * Ends the identities of {@link #beginCommand}, as the dispatcher restores the local ones.
     */
    public void endCommand() {
        this.commandOwner = NO_COMMAND;
        this.commandSlot  = NO_COMMAND;
    }

    /**
     * Applies the hotkey command: recall, set and append of a record. The storage holds {@code 18} records a player (ten control groups, then the
     * eight selection-history records) as one run in the game context, twelve handle DWORDs each, and indexes it with the record BYTE without a range
     * check: record {@code player * 18 + group}, so records from {@code 18} are the following players' records. A record holds its leading nonzero
     * handles. A recall of a record from {@code 10} takes the current timestamp of history timestamp {@code player * 8 + n} for record
     * {@code 10 + n}; a recall of a group below {@code 10} becomes the newest selection-history record instead. A record BYTE above {@code 18} or an
     * operation other than {@code 0..2} does nothing.
     *
     * @param game        the game
     * @param playerIndex the command player from {@code 0..7}
     * @param groupIndex  the record BYTE
     * @param operation   {@code 0} to set, {@code 1} to recall, or {@code 2} to append
     * @return {@code true} if a unit was stored or recalled, {@code false} otherwise
     * @throws IndexOutOfBoundsException     if the command player is outside the playable player slots
     * @throws UnsupportedOperationException if a hotkey record, its history timestamp or an append to a full record lies past the represented
     *                                       storage, the local slot of a team selection names no represented player record or every history timestamp
     *                                       is {@code 0xFFFF}
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if a recalled unit has no sprite
     */
    public boolean executeControlGroup(@NotNull Game game, int playerIndex, int groupIndex, int operation) {
        Objects.checkIndex(playerIndex, PlayerTable.PLAYABLE_PLAYER_SLOTS);

        if (groupIndex < 0 || groupIndex > LAST_HOTKEY_RECORD || operation < HOTKEY_SET || operation > HOTKEY_APPEND) {
            return false;
        }

        // The records and the selection belong to the command slot, the unit checks to the owner.
        int slot = this.getSelectionSlot(playerIndex);
        int recordIndex = slot * RECORDS_PER_PLAYER + groupIndex;

        if (recordIndex >= RECORD_COUNT) {
            throw Exceptions.newUnsupportedOperationException("Hotkey record {0} of player {1} lies past the represented hotkey records.",
                                                              groupIndex,
                                                              slot);
        }

        int base = recordIndex * RECORD_SIZE;
        int[] selection = this.selections[slot];
        int count = this.countRecordHandles(recordIndex);
        ScrUnitTypeView unitTypes = game.requireGameData().getUnitTypes();
        boolean changed = false;

        if (operation == HOTKEY_RECALL) {
            if (count == 0) {
                return false;
            }

            if (groupIndex >= CONTROL_GROUP_COUNT) {
                calculateTimestampIndex(slot, groupIndex);
            }

            boolean teamMarks = this.isTeamSelection(game, slot);

            if (teamMarks) {
                this.unmarkSelection(game, selection);
            }

            Arrays.fill(selection, 0);

            int selectedCount = 0;

            for (int position = 0; position < count; ) {
                Unit unit = this.unitPool.resolveHandle(this.records[base + position]);

                if (unit != null && (unit.requireSprite().getFlags() & Sprite.FLAG_HIDDEN) == 0 && unit.getOwnerIndex() == playerIndex &&
                    (count == 1 || canSelectMultiple(unitTypes, unit))) {
                    selection[selectedCount++] = unit.getIndex();

                    // An inserted unit takes a team mark.
                    if (teamMarks) {
                        this.markTeamSelection(game, unit);
                    }

                    changed = true;
                    position++;
                } else {
                    this.records[base + position] = this.records[base + --count];
                    this.records[base + count]    = 0;
                }
            }

            if (groupIndex >= CONTROL_GROUP_COUNT) {
                // A recalled record from 10 takes the current timestamp.
                this.timestamps[calculateTimestampIndex(slot, groupIndex)] = game.getTurnCount() & WORD_MASK;
            } else {
                // A recalled group becomes the newest selection-history record.
                this.recordRecalledSelection(game, slot, playerIndex);
            }

            return changed;
        }

        if (operation == HOTKEY_SET) {
            Arrays.fill(this.records, base, base + RECORD_SIZE, 0);

            count = 0;
        } else if (count != 0) {
            Unit first = this.unitPool.resolveHandle(this.records[base]);

            if (first != null && !canSelectMultiple(unitTypes, first)) {
                return false;
            }
        }

        for (int position = 0; position < MAXIMUM_SELECTED; position++) {
            int poolIndex = selection[position];

            if (poolIndex == 0) {
                break;
            }

            Unit unit = this.unitPool.getUnit(poolIndex);

            if (unit.isDestroyed()) {
                continue;
            }

            if (unit.getOwnerIndex() != playerIndex) {
                break;
            }

            if (operation == HOTKEY_APPEND && count != 0 && !canSelectMultiple(unitTypes, unit)) {
                continue;
            }

            int handle = this.unitPool.getHandle(poolIndex);
            boolean duplicate = false;

            if (operation == HOTKEY_APPEND) {
                for (int storedPosition = 0; storedPosition < count; storedPosition++) {
                    if (this.records[base + storedPosition] == handle) {
                        duplicate = true;

                        break;
                    }
                }
            }

            if (duplicate) {
                continue;
            }

            changed = true;

            if (count == RECORD_SIZE) {
                // Appending to a full record writes the handle into the following record's first slot.
                if (recordIndex + 1 >= RECORD_COUNT) {
                    throw Exceptions.newUnsupportedOperationException("Appending to player 7's full record 17 writes past the hotkey records.");
                }

                this.records[base + RECORD_SIZE] = handle;

                break;
            }

            // The loop ends once the twelfth handle is stored.
            this.records[base + count++] = handle;

            if (count == RECORD_SIZE) {
                break;
            }
        }

        return changed;
    }

    /**
     * Removes a retired or hidden unit from active selections without issuing a selection command. Control-group and history handles remain available
     * for their normal stale-handle checks.
     *
     * @param game the game whose sprites hold the team marks
     * @param unit the unit being deselected by its lifecycle
     * @throws IllegalArgumentException if the unit is not in the unit pool
     */
    public void removeUnit(@NotNull Game game, @NotNull Unit unit) {
        int poolIndex = unit.getIndex();

        if (poolIndex <= 0 || poolIndex > this.unitPool.getCapacity() || this.unitPool.getUnit(poolIndex) != unit) {
            throw Exceptions.newIllegalArgumentException("Lifecycle deselection requires a unit from the retained pool.");
        }

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS; playerIndex++) {
            int[] selection = this.selections[playerIndex];

            for (int position = 0; position < MAXIMUM_SELECTED; position++) {
                if (selection[position] != poolIndex) {
                    continue;
                }

                System.arraycopy(selection, position + 1, selection, position, MAXIMUM_SELECTED - 1 - position);

                selection[MAXIMUM_SELECTED - 1] = 0;

                break;
            }
        }

        // The unit loses every team mark.
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite != null) {
            game.getSelectionImages().clearTeamMarks(sprite);
        }
    }

    /**
     * Reads a byte of the hotkey records by its game-context offset, as the EUD game-data block does.
     *
     * @param contextOffset an offset accepted by {@link #hasContextOffset(int)}
     * @return the unsigned byte
     */
    public int readContextByte(int contextOffset) {
        int index = contextOffset - RECORDS_OFFSET;

        return this.records[index / Integer.BYTES] >>> index % Integer.BYTES * Byte.SIZE & BYTE_MASK;
    }

    /**
     * Writes a byte of the hotkey records by its game-context offset, as an EUD write of the game-data block.
     *
     * @param contextOffset an offset accepted by {@link #hasContextOffset(int)}
     * @param value         the byte value
     */
    public void writeContextByte(int contextOffset, int value) {
        int index = contextOffset - RECORDS_OFFSET;
        int shift = index % Integer.BYTES * Byte.SIZE;
        int handleIndex = index / Integer.BYTES;

        this.records[handleIndex] = LittleEndianUtils.replaceByte(this.records[handleIndex], shift, value);
    }

    /**
     * Binds the shared integer and float accounting storage of the production statistics before command execution.
     *
     * @param statistics the same initialized storage used by production events
     * @throws IllegalStateException if accounting was already bound or used
     */
    public void bindStatistics(@NotNull ProductionStatistics statistics) {
        if (this.statistics != null) {
            throw Exceptions.newIllegalStateException("Selection accounting is already bound.");
        }

        for (int total : this.scopeTotals) {
            if (total != 0) {
                throw Exceptions.newIllegalStateException("Selection accounting was already used.");
            }
        }

        this.statistics = statistics;
    }

    /**
     * Computes a diagnostic hash of the selection context and storage.
     *
     * @return the diagnostic hash
     */
    public long computeStateHash() {
        long hash = Game.STATE_HASH_OFFSET_BASIS;

        hash = (hash ^ this.unitPool.getCapacity()) * Game.STATE_HASH_PRIME;
        hash = (hash ^ (this.scopeAccountingEnabled ? 1 : 0)) * Game.STATE_HASH_PRIME;
        hash = (hash ^ this.teamSize) * Game.STATE_HASH_PRIME;
        hash = (hash ^ this.localPlayerIndex) * Game.STATE_HASH_PRIME;

        if (this.statistics != null) {
            hash = (hash ^ Integer.toUnsignedLong(this.statistics.computeStateHash(1))) * Game.STATE_HASH_PRIME;
        }

        for (int[] selection : this.selections) {
            hash = mix(hash, selection);
        }

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS; playerIndex++) {
            for (int groupIndex = 0; groupIndex < CONTROL_GROUP_COUNT; groupIndex++) {
                int base = (playerIndex * RECORDS_PER_PLAYER + groupIndex) * RECORD_SIZE;

                hash = mix(hash, Arrays.copyOfRange(this.records, base, base + RECORD_SIZE));
            }
        }

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS; playerIndex++) {
            for (int groupIndex = 0; groupIndex < CONTROL_GROUP_COUNT; groupIndex++) {
                hash = (hash ^ this.countRecordHandles(playerIndex * RECORDS_PER_PLAYER + groupIndex)) * Game.STATE_HASH_PRIME;
            }
        }

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS; playerIndex++) {
            int base = (playerIndex * RECORDS_PER_PLAYER + CONTROL_GROUP_COUNT) * RECORD_SIZE;

            hash = mix(hash, Arrays.copyOfRange(this.records, base, base + HISTORY_COUNT * RECORD_SIZE));
        }

        hash = mix(hash, this.timestamps);
        hash = mix(hash, this.scopeTotals);
        hash = mix(hash, this.scopeChanges);

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS; playerIndex++) {
            hash = (hash ^ Float.floatToRawIntBits(this.scopeFloatTotals[playerIndex])) * Game.STATE_HASH_PRIME;
            hash = (hash ^ Float.floatToRawIntBits(this.scopeFloatChanges[playerIndex])) * Game.STATE_HASH_PRIME;
        }

        return hash;
    }

    /**
     * Checks whether the selection of a slot marks its units for the local player: in a team game, for a slot other than the local one whose record
     * and the local record are humans of one force.
     *
     * @param game the game whose player records are compared
     * @param slot the selection slot
     * @return {@code true} if the selection of the slot marks its units for the local player, {@code false} otherwise
     * @throws UnsupportedOperationException if the local slot names no represented player record
     */
    private boolean isTeamSelection(@NotNull Game game, int slot) {
        if (this.teamSize == 0 || slot == this.localPlayerIndex || slot >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return false;
        }

        Player player = game.getPlayerTable().getPlayer(slot);

        if (player == null || player.getType() != PlayerTypes.HUMAN) {
            return false;
        }

        Player localPlayer = this.localPlayerIndex >= 0 && this.localPlayerIndex < PlayerTable.PLAYER_SLOTS
                             ? game.getPlayerTable().getPlayer(this.localPlayerIndex)
                             : null;

        if (localPlayer == null) {
            throw Exceptions.newUnsupportedOperationException("The team selection predicate reads the unrepresented local record {0}.",
                                                              this.localPlayerIndex);
        }

        return localPlayer.getType() == PlayerTypes.HUMAN && player.getTeam() == localPlayer.getTeam();
    }

    /**
     * Returns the selection slot of a command player: the command slot of the dispatched command for its owner ({@link #beginCommand}), otherwise the
     * player itself.
     *
     * @param playerIndex the command player
     * @return the selection slot
     */
    private int getSelectionSlot(int playerIndex) {
        return playerIndex == this.commandOwner ? this.commandSlot : playerIndex;
    }

    /**
     * Counts one more teammate's selection on the unit's sprite in the owner's circle color, at most one less than the team size
     * ({@link SelectionImages#addTeamMark}).
     *
     * @param game the game whose sprites hold the team marks
     * @param unit the inserted unit
     */
    private void markTeamSelection(@NotNull Game game, @NotNull Unit unit) {
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite != null) {
            game.getSelectionImages().addTeamMark(sprite, game.getSelectionImages().getCircleColor(unit.getOwnerIndex()), this.teamSize);
        }
    }

    /**
     * Counts one teammate's selection less on the unit's sprite; the last one removes the dashed circle ({@link SelectionImages#removeTeamMark}).
     *
     * @param game the game whose sprites hold the team marks
     * @param unit the removed unit
     */
    private void unmarkTeamSelection(@NotNull Game game, @NotNull Unit unit) {
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite != null) {
            game.getSelectionImages().removeTeamMark(sprite);
        }
    }

    /**
     * Unmarks the units of a marking selection in order, as clearing it does.
     *
     * @param game      the game whose sprites hold the team marks
     * @param selection the one-based pool indexes of the selection, ending at the first {@code 0}
     */
    private void unmarkSelection(@NotNull Game game, int @NotNull [] selection) {
        for (int poolIndex : selection) {
            if (poolIndex == 0) {
                break;
            }

            this.unmarkTeamSelection(game, this.unitPool.getUnit(poolIndex));
        }
    }

    /**
     * @param recordIndex the hotkey record
     * @return the number of leading nonzero handles of a record
     */
    private int countRecordHandles(int recordIndex) {
        int base = recordIndex * RECORD_SIZE;
        int count = 0;

        while (count < RECORD_SIZE && this.records[base + count] != 0) {
            count++;
        }

        return count;
    }

    /**
     * Stores the selection of a recalled control group as the newest history record: the oldest selection-history record (the lowest timestamp,
     * searched from the last record down) is cleared and filled with the handles of the player's current selection, then takes the current timestamp.
     *
     * @param game  the game whose turn count stamps the record
     * @param slot  the selection slot
     * @param owner the command player whose units the record keeps
     */
    private void recordRecalledSelection(@NotNull Game game, int slot, int owner) {
        int historyIndex = this.findOldestHistoryRecord(slot);
        int base = (slot * RECORDS_PER_PLAYER + CONTROL_GROUP_COUNT + historyIndex) * RECORD_SIZE;

        Arrays.fill(this.records, base, base + RECORD_SIZE, 0);

        int stored = 0;

        for (int position = 0; position < RECORD_SIZE; position++) {
            int poolIndex = this.selections[slot][position];

            if (poolIndex == 0 || this.unitPool.getUnit(poolIndex).getOwnerIndex() != owner) {
                break;
            }

            int handle = this.unitPool.getHandle(poolIndex);

            if (handle != 0) {
                this.records[base + stored++] = handle;
            }
        }

        this.timestamps[slot * HISTORY_COUNT + historyIndex] = game.getTurnCount() & WORD_MASK;
    }

    /**
     * Finds the oldest selection-history record of a player slot: the lowest timestamp, searched from the last record down.
     *
     * @param slot the player slot
     * @return the history record index
     * @throws UnsupportedOperationException if every history timestamp is {@code 0xFFFF}
     */
    private int findOldestHistoryRecord(int slot) {
        int historyIndex = -1;
        int minimumTimestamp = NEWEST_TIMESTAMP;

        for (int candidateIndex = HISTORY_COUNT - 1; candidateIndex >= 0; candidateIndex--) {
            int timestamp = this.timestamps[slot * HISTORY_COUNT + candidateIndex];

            if (timestamp < minimumTimestamp) {
                minimumTimestamp = timestamp;
                historyIndex     = candidateIndex;
            }
        }

        if (historyIndex < 0) {
            throw Exceptions.newUnsupportedOperationException("Selection history timestamps all at 0xFFFF write outside the history records.");
        }

        return historyIndex;
    }

    /**
     * Checks a selection request and, if {@code commit} is {@code true}, applies its effects: the selection change, the team marks, the
     * selection-history record and, if {@code accounted} is {@code true}, the scope accounting.
     *
     * @param game        the game
     * @param command     the decoded selection request
     * @param playerIndex the full resolved command player DWORD
     * @param commit      whether to apply the effects after the checks
     * @param accounted   whether a committed request counts in the scope accounting
     * @throws UnsupportedOperationException if the command player is outside the playable player slots, a legacy selection request holds more than
     *                                       {@code 12} WORD handles, the local slot of a team selection names no represented player record or every
     *                                       history timestamp is {@code 0xFFFF}
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if a requested unit to insert, other than a Nuclear Missile of a replacing selection, has no sprite
     */
    private void apply(@NotNull Game game, @NotNull SelectionRequest command, int playerIndex, boolean commit, boolean accounted) {
        if (playerIndex < 0 || playerIndex >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            throw Exceptions.newUnsupportedOperationException("Selection command player {0} lies outside the represented player slots.", playerIndex);
        }

        int requestedCount = command.getHandleCount();

        if (requestedCount > MAXIMUM_SELECTED) {
            if (command.getHandleWidthBytes() == Short.BYTES) {
                throw Exceptions.newUnsupportedOperationException("A legacy selection of {0} WORD handles writes past the selection buffer.",
                                                                  requestedCount);
            }

            return;
        }

        // The selection, history and scope belong to the command slot, the unit checks to the owner.
        int slot = this.getSelectionSlot(playerIndex);
        int[] selected = this.selections[slot].clone();
        int selectedCount = 0;

        while (selectedCount < MAXIMUM_SELECTED && selected[selectedCount] != 0) {
            selectedCount++;
        }

        boolean markedChanged = false;
        int historyCount = 0;
        boolean teamMarks = this.isTeamSelection(game, slot);
        boolean clearsMarks = false;
        int firstInserted = selectedCount;
        int[] unmarkedUnits = teamMarks ? new int[MAXIMUM_SELECTED] : null;
        int unmarkedCount = 0;
        SelectionRequest.Operation operation = command.getOperation();
        boolean capacityRejected = operation == SelectionRequest.Operation.ADD && selectedCount + requestedCount > MAXIMUM_SELECTED;

        if (!capacityRejected) {
            if (operation == SelectionRequest.Operation.REPLACE) {
                // A marking selection unmarks its units as it is cleared.
                clearsMarks = teamMarks;

                Arrays.fill(selected, 0);

                selectedCount = 0;
                firstInserted = 0;
            }

            for (int handleIndex = 0; handleIndex < requestedCount; handleIndex++) {
                Unit unit = this.unitPool.resolveHandle((int) command.getHandle(handleIndex));

                if (unit == null) {
                    continue;
                }

                int poolIndex = unit.getIndex();
                int matchingPosition = -1;

                for (int position = 0; position < selectedCount; position++) {
                    if (selected[position] == poolIndex) {
                        matchingPosition = position;
                    }
                }

                if (operation == SelectionRequest.Operation.REMOVE) {
                    // A marking selection unmarks every unit it is asked to remove, selected or not.
                    if (teamMarks) {
                        unmarkedUnits[unmarkedCount++] = poolIndex;
                    }

                    if (matchingPosition >= 0) {
                        System.arraycopy(selected, matchingPosition + 1, selected, matchingPosition, selectedCount - matchingPosition - 1);

                        selected[--selectedCount] = 0;
                    }

                    markedChanged = true;
                    historyCount  = selectedCount;

                    continue;
                }

                if (matchingPosition >= 0) {
                    continue;
                }

                int unitType = unit.getUnitId();

                if ((operation == SelectionRequest.Operation.REPLACE && game.getBaseType(unitType) == ScrUnitIds.TERRAN_NUCLEAR_MISSILE) ||
                    (unit.requireSprite().getFlags() & Sprite.FLAG_HIDDEN) != 0) {
                    continue;
                }

                if (selectedCount > 0 && (!canSelectMultiple(game.requireGameData().getUnitTypes(), unit) || unit.getOwnerIndex() != playerIndex)) {
                    continue;
                }

                selected[selectedCount++] = poolIndex;

                markedChanged = true;
            }

            if (operation != SelectionRequest.Operation.REMOVE) {
                historyCount = selectedCount;
            }
        }

        int historyIndex = -1;

        if (historyCount > 1) {
            historyIndex = this.findOldestHistoryRecord(slot);
        }

        if (!commit) {
            return;
        }

        if (teamMarks) {
            // The handlers' order: the clear or each removal, then each insertion.
            if (clearsMarks) {
                this.unmarkSelection(game, this.selections[slot]);
            }

            for (int index = 0; index < unmarkedCount; index++) {
                this.unmarkTeamSelection(game, this.unitPool.getUnit(unmarkedUnits[index]));
            }

            if (operation != SelectionRequest.Operation.REMOVE) {
                for (int position = firstInserted; position < selectedCount; position++) {
                    this.markTeamSelection(game, this.unitPool.getUnit(selected[position]));
                }
            }
        }

        System.arraycopy(selected, 0, this.selections[slot], 0, MAXIMUM_SELECTED);

        if (historyIndex >= 0) {
            int base = (slot * RECORDS_PER_PLAYER + CONTROL_GROUP_COUNT + historyIndex) * RECORD_SIZE;

            Arrays.fill(this.records, base, base + RECORD_SIZE, 0);

            int historyPosition = 0;

            for (int position = 0; position < selectedCount; position++) {
                int poolIndex = selected[position];
                Unit unit = this.unitPool.getUnit(poolIndex);

                if (unit.getOwnerIndex() != playerIndex) {
                    break;
                }

                int handle = UnitHandle.encode(poolIndex, this.unitPool.getGeneration(unit.getIndex()), this.unitPool.getCapacity());

                if (handle != 0) {
                    this.records[base + historyPosition++] = handle;
                }
            }

            this.timestamps[slot * HISTORY_COUNT + historyIndex] = game.getTurnCount() & WORD_MASK;
        }

        if (this.scopeAccountingEnabled && accounted) {
            if (this.statistics != null) {
                this.statistics.record(slot, markedChanged ? ProductionStatistics.SUMMED_CATEGORY : ProductionStatistics.TOTAL_CATEGORY, 1);

                return;
            }

            this.scopeTotals[slot]++;
            this.scopeFloatTotals[slot] += 1.0f;

            if (markedChanged) {
                this.scopeChanges[slot]++;
                this.scopeFloatChanges[slot] += 1.0f;
            }
        }
    }
}
