package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.computer.ComputerInstructionRegistry;
import org.fogofwarlabs.sc4j.scr.content.*;
import org.fogofwarlabs.sc4j.scr.game.player.*;
import org.fogofwarlabs.sc4j.scr.game.resource.ResourceAreas;
import org.fogofwarlabs.sc4j.scr.game.save.SaveFileReader;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.visibility.SightCoverage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

/**
 * Interprets {@code 100} script records in their intrusive active/free order.
 * <p>
 * The engine instruction registry holds the instruction of every opcode BYTE {@code 0x00..0x70} (see {@link ComputerScriptOpcodes}); the
 * interpreter finds the opcode it reads there and executes it with its one {@link ScrComputerScriptContext}. Operand reads check the
 * following byte before advancing the program counter.
 * <p>
 * Each record holds a program counter, a wait, an owner, a rectangle, its center, a town and flags. Flag bit
 * {@link #FLAG_EXPANSION} selects the expansion program and bit {@link #FLAG_RELEASE} requests release.
 */
final class ComputerScripts {
    /**
     * The saved script record and the sections it fills.
     */
    static final int SAVED_RECORD_SIZE = 0x34;
    static final int SAVED_RECORDS_SIZE = ComputerScripts.RECORD_COUNT * SAVED_RECORD_SIZE + Integer.BYTES;
    static final int SAVED_HEAD_SIZE = 2 * Integer.BYTES;
    /**
     * The clock ticks of one minute of the script opcodes {@code time_jump} and {@code nuke_rate}.
     */
    static final int CLOCK_TICKS_PER_MINUTE = 60;

    /**
     * The record pool size.
     */
    private static final int RECORD_COUNT = 100;
    /**
     * The absent record link.
     */
    private static final int NONE = -1;

    private static final int SAVED_NEXT = 0x00;
    private static final int SAVED_PREVIOUS = 0x04;
    private static final int SAVED_ADDRESS = 0x08;
    private static final int SAVED_WAIT = 0x0C;
    private static final int SAVED_OWNER = 0x10;
    private static final int SAVED_LEFT = 0x14;
    private static final int SAVED_TOP = 0x18;
    private static final int SAVED_RIGHT = 0x1C;
    private static final int SAVED_BOTTOM = 0x20;
    private static final int SAVED_CENTER_X = 0x24;
    private static final int SAVED_CENTER_Y = 0x28;
    private static final int SAVED_TOWN = 0x2C;
    private static final int SAVED_FLAGS = 0x30;

    /**
     * Record flag: the record runs the expansion program.
     */
    private static final int FLAG_EXPANSION = 0x1;
    /**
     * Record flag: {@code kill_thread} may release the record.
     */
    private static final int FLAG_KILLABLE = 0x2;
    /**
     * Record flag: the record is released when its wait expires.
     */
    private static final int FLAG_RELEASE = 0x4;
    /**
     * The flags a recycled record keeps when a script starts in it.
     */
    private static final int FLAGS_KEPT_ON_START = FLAG_KILLABLE | FLAG_RELEASE;
    /**
     * The flag bits below the resource area.
     */
    private static final int FLAG_BITS = FLAG_EXPANSION | FLAG_KILLABLE | FLAG_RELEASE;
    /**
     * The shift of the resource area in the record flags.
     */
    private static final int RESOURCE_AREA_SHIFT = 3;

    /**
     * The positions of the rectangle values in a location array: left, top, right, bottom.
     */
    private static final int LOCATION_LEFT = 0;
    private static final int LOCATION_TOP = 1;
    private static final int LOCATION_RIGHT = 2;
    private static final int LOCATION_BOTTOM = 3;

    /**
     * The frames after which a waiting opcode runs again.
     */
    private static final int RETRY_WAIT = 30;
    /**
     * The size of an opcode with a count BYTE and a type WORD, which a retry steps back over.
     */
    private static final int COUNT_TYPE_INSTRUCTION_SIZE = 4;
    /**
     * The upgrade levels a script requests are below this.
     */
    private static final int UPGRADE_LEVEL_LIMIT = 4;
    /**
     * The priority of a {@code player_need} request.
     */
    private static final int PLAYER_NEED_PRIORITY = 0x50;
    /**
     * The random call site of {@code random_jump}.
     */
    private static final int RANDOM_JUMP_RANDOM_SOURCE = 0x34;
    /**
     * The required ground and air strength {@code value_area} raises a region to at least.
     */
    private static final int VALUE_AREA_MINIMUM_STRENGTH = 200;
    /**
     * {@code give_money} adds minerals and gas when the owner has less than this.
     */
    private static final int GIVE_MONEY_THRESHOLD = 500;
    /**
     * The minerals and the gas {@code give_money} adds.
     */
    private static final int GIVE_MONEY_AMOUNT = 2000;
    /**
     * The attack delay {@code send_suicide} and {@code quick_attack} set.
     */
    private static final int ATTACK_DELAY = 5;
    /**
     * The {@code creep} operand that spaces buildings for creep.
     */
    private static final int CREEP_SPACING = 4;
    /**
     * The shift that turns the {@code region_size} operand into tiles (times {@code 32}).
     */
    private static final int REGION_SIZE_SHIFT = 5;
    /**
     * The minerals of a melee owner above which {@code expand} starts nothing (with {@link #EXPAND_GAS_RESERVE}).
     */
    private static final int EXPAND_MINERAL_RESERVE = 5000;
    /**
     * The gas of a melee owner above which {@code expand} starts nothing (with {@link #EXPAND_MINERAL_RESERVE}).
     */
    private static final int EXPAND_GAS_RESERVE = 3000;
    /**
     * The occupancy flags of a resource area that refuse a claim.
     */
    private static final int OCCUPIED_AREA_FLAGS = ResourceAreas.OCCUPIED_FLAG | ResourceAreas.EXPANSION_FLAG;

    /**
     * Truncates a value to a BYTE.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The multiplier of each state-hash step.
     */
    private static final int HASH_MULTIPLIER = 31;
    /**
     * Halves a rectangle extent to its center.
     */
    private static final int LOCATION_CENTER_DIVISOR = 2;
    /**
     * The position of the last BYTE of a little-endian DWORD operand.
     */
    private static final int LAST_BYTE_SHIFT = 3 * Byte.SIZE;

    /**
     * @param value a saved one-based record index
     * @return the zero-based index, or {@link #NONE} for {@code 0}
     * @throws UncheckedIOException if the value names no record
     */
    private static int decodeSaveLink(int value) {
        if (value < 0 || value > RECORD_COUNT) {
            throw SaveFileReader.createFailure("A saved computer script reference {0} names no record of {1}.", value, RECORD_COUNT);
        }

        return value - 1;
    }


    private final @NotNull Game game;

    private final @NotNull ComputerScriptProgram program;
    private final @NotNull ComputerInstructionRegistry<ComputerScriptInstruction> instructions;
    private final @NotNull ScrComputerScriptContext context;

    private final int @NotNull [] next;
    private final int @NotNull [] previous;

    private final int @NotNull [] addresses;
    private final int @NotNull [] waits;
    private final int @NotNull [] owners;
    private final int @NotNull [] flags;

    private final int @NotNull [] left;
    private final int @NotNull [] top;
    private final int @NotNull [] right;
    private final int @NotNull [] bottom;
    private final int @NotNull [] centerX;
    private final int @NotNull [] centerY;
    private final @Nullable ComputerTown @NotNull [] towns;

    private int firstFree;
    private int firstActive;
    /**
     * The single return address shared by opcodes {@code call} and {@code return} of every record.
     */
    private int callReturn;

    /**
     * Creates a {@link ComputerScripts} instance with an empty record pool over the program.
     *
     * @param game    the game this pool belongs to
     * @param program the script programs
     */
    ComputerScripts(@NotNull Game game, @NotNull ComputerScriptProgram program) {
        this.game = game;

        this.program      = program;
        this.instructions = this.createInstructions();
        this.context      = new ScrComputerScriptContext(game);

        this.next     = new int[RECORD_COUNT];
        this.previous = new int[RECORD_COUNT];

        this.addresses = new int[RECORD_COUNT];
        this.waits     = new int[RECORD_COUNT];
        this.owners    = new int[RECORD_COUNT];
        this.flags     = new int[RECORD_COUNT];

        this.left    = new int[RECORD_COUNT];
        this.top     = new int[RECORD_COUNT];
        this.right   = new int[RECORD_COUNT];
        this.bottom  = new int[RECORD_COUNT];
        this.centerX = new int[RECORD_COUNT];
        this.centerY = new int[RECORD_COUNT];
        this.towns   = new ComputerTown[RECORD_COUNT];

        for (int index = 0; index < RECORD_COUNT; index++) {
            this.next[index]     = index == RECORD_COUNT - 1 ? NONE : index + 1;
            this.previous[index] = index - 1;
        }

        this.firstFree   = 0;
        this.firstActive = NONE;
        this.callReturn  = 0;
    }

    /**
     * @param expansion whether the expansion program is requested
     * @return the live script buffer
     */
    byte @NotNull [] getProgramBuffer(boolean expansion) {
        return this.program.getBuffer(expansion);
    }

    /**
     * @return the first active record, or {@code -1} without one
     */
    int getFirstActive() {
        return this.firstActive;
    }

    /**
     * @param index the record
     * @return the record's program counter
     */
    int getAddress(int index) {
        return this.addresses[index];
    }

    /**
     * Issues patrol orders to movable owned units in the script location, targeting the general-command rectangle center.
     */
    private void issuePatrolOrders(@NotNull ComputerPlayerPhase phase, int scriptIndex) {
        int owner = this.owners[scriptIndex];
        int[] rectangle = phase.getPlayer(owner).getGeneralCommandRectangle();
        int positionX = rectangle[LOCATION_LEFT] + (rectangle[LOCATION_RIGHT] - rectangle[LOCATION_LEFT]) / LOCATION_CENTER_DIVISOR;
        int positionY = rectangle[LOCATION_TOP] + (rectangle[LOCATION_BOTTOM] - rectangle[LOCATION_TOP]) / LOCATION_CENTER_DIVISOR;

        for (Unit unit : this.game.getComputerTerrain().collectRectangle(this.left[scriptIndex], this.top[scriptIndex],
                                                                         this.right[scriptIndex], this.bottom[scriptIndex])) {
            if (unit.getOwnerIndex() == owner && (unit.getStatus() & UnitStatusFlags.CAN_MOVE) != 0) {
                this.game.getOrders().issue(unit,
                                            ScrOrderIds.PATROL,
                                            null,
                                            positionX, positionY,
                                            ScrUnitIds.NONE,
                                            false,
                                            phase.getInstructionLimit());
            }
        }
    }

    /**
     * Issues transport-entry orders to owned units in the script location, selecting the nearest owned transport that can load each unit.
     */
    private void issueEnterTransportOrders(@NotNull ComputerPlayerPhase phase, int scriptIndex) {
        int owner = this.owners[scriptIndex];

        for (Unit unit : this.game.getComputerTerrain().collectRectangle(this.left[scriptIndex], this.top[scriptIndex],
                                                                         this.right[scriptIndex], this.bottom[scriptIndex])) {
            if (unit.getOwnerIndex() != owner) {
                continue;
            }

            Unit transport = this.game.getComputerResources().findOnMap(unit,
                                                                        candidate -> this.game.getUnitQueries().isTransport(candidate) &&
                                                                                     candidate.getOwnerIndex() == unit.getOwnerIndex() &&
                                                                                     this.game.getUnitTransport().canLoad(candidate, unit));

            if (transport != null) {
                this.issueTargetOrder(unit, ScrOrderIds.ENTER_TRANSPORT, transport, phase.getInstructionLimit());
            }
        }
    }

    /**
     * Issues unload orders to owned transports in the script location, targeting each transport itself.
     */
    private void issueUnloadTransportOrders(@NotNull ComputerPlayerPhase phase, int scriptIndex) {
        int owner = this.owners[scriptIndex];

        for (Unit unit : this.game.getComputerTerrain().collectRectangle(this.left[scriptIndex], this.top[scriptIndex],
                                                                         this.right[scriptIndex], this.bottom[scriptIndex])) {
            if (unit.getOwnerIndex() == owner && this.game.getUnitQueries().isTransport(unit)) {
                this.issueTargetOrder(unit, ScrOrderIds.MOVE_UNLOAD, unit, phase.getInstructionLimit());
            }
        }
    }

    /**
     * Issues the order with a unit target at its sprite position and no target type.
     *
     * @throws IllegalStateException if the target unit has no sprite
     */
    private void issueTargetOrder(@NotNull Unit unit, int orderIdentifier, @NotNull Unit targetUnit, int instructionLimit) {
        Sprite sprite = targetUnit.requireSprite();

        this.game.getOrders().issue(unit, orderIdentifier, targetUnit, sprite.getX(), sprite.getY(), ScrUnitIds.NONE, false, instructionLimit);
    }

    /**
     * Issues a nuclear strike through an idle Ghost of the owner with a ready Nuclear Silo. Location mode paints a selected
     * target or the location center; position mode paints the supplied coordinates. Sets the missile's parent to the Ghost
     * after issuing the order.
     *
     * @throws IllegalStateException if the ready Silo has no missile
     */
    private void issueNuclearStrikeOrder(@NotNull ComputerPlayerPhase phase, int scriptIndex, boolean useLocationBounds, int positionX, int positionY) {
        int owner = this.owners[scriptIndex];
        Unit ghost = this.findCaster(owner, ScrUnitIds.TERRAN_GHOST, 0);

        if (ghost == null) {
            return;
        }

        Unit silo = ComputerSpells.findSilo(this.game, ghost);

        if (silo == null) {
            return;
        }

        int instructionLimit = phase.getInstructionLimit();

        if (useLocationBounds) {
            Unit target = ComputerSpells.findNukeTarget(phase,
                                                        this.game,
                                                        ghost,
                                                        this.left[scriptIndex], this.top[scriptIndex],
                                                        this.right[scriptIndex], this.bottom[scriptIndex]);

            if (target != null) {
                this.issueTargetOrder(ghost, ScrOrderIds.NUKE_PAINT, target, instructionLimit);
            } else {
                this.game.getOrders().issue(ghost,
                                            ScrOrderIds.NUKE_PAINT,
                                            null,
                                            (this.left[scriptIndex] + this.right[scriptIndex]) / LOCATION_CENTER_DIVISOR,
                                            (this.top[scriptIndex] + this.bottom[scriptIndex]) / LOCATION_CENTER_DIVISOR,
                                            ScrUnitIds.NONE,
                                            false,
                                            instructionLimit);
            }
        } else {
            this.game.getOrders().issue(ghost, ScrOrderIds.NUKE_PAINT, null, positionX, positionY, ScrUnitIds.NONE, false, instructionLimit);
        }

        Unit missile = silo.getSiloNuke();

        if (missile == null) {
            throw Exceptions.newIllegalStateException("A ready Nuclear Silo has no missile.");
        }

        missile.setParentUnit(ghost);
    }

    /**
     * Issues a spell order at the script location center through an idle owned caster with the technology's required energy.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private void issueSpellOrderAtLocationCenter(int scriptIndex, int casterType, int technology, int orderType, int instructionLimit) {
        int energy = this.game.requireGameData().getTechTypes().getEnergyCost(technology);
        Unit caster = this.findCaster(this.owners[scriptIndex], casterType, energy);

        if (caster == null) {
            return;
        }

        int positionX = this.left[scriptIndex] + (this.right[scriptIndex] - this.left[scriptIndex]) / LOCATION_CENTER_DIVISOR;
        int positionY = this.top[scriptIndex] + (this.bottom[scriptIndex] - this.top[scriptIndex]) / LOCATION_CENTER_DIVISOR;

        this.game.getOrders().issue(caster, orderType, null, positionX, positionY, ScrUnitIds.NONE, false, instructionLimit);
    }

    /**
     * Sets or clears the owner's bit in the shared-vision DWORD of {@code player}.
     *
     * @throws UnsupportedOperationException if the game has no player relations or sight coverage
     */
    private void setVision(int owner, int player, boolean enabled) {
        PlayerRelations relations = this.game.getPlayerTable().getPlayerRelations();
        SightCoverage sight = this.game.getSightCoverage();

        if (relations == null || sight == null) {
            throw Exceptions.newUnsupportedOperationException("Computer script vision requires player relations and sight coverage.");
        }

        int mask = relations.getSharedVision(player);

        mask = enabled ? mask | 1 << owner : mask & ~(1 << owner);

        relations.setSharedVision(player, mask);
        sight.setSharedVisionMask(player, mask);
    }


    /**
     * Starts a script at a position: the rectangle reaches one pixel around it.
     *
     * @param identifier the four-character script identifier
     * @param owner      the script owner
     * @param positionX  the position x coordinate
     * @param positionY  the position y coordinate
     * @return {@code true} if a record was started, {@code false} otherwise
     */
    boolean start(int identifier, int owner, int positionX, int positionY) {
        return this.start(identifier, owner, positionX - 1, positionY - 1, positionX + 1, positionY + 1, positionX, positionY);
    }

    /**
     * Starts a script for a trigger action after its owner check: resolves the identifier in the {@code aiscript.bin}
     * and {@code bwscript.bin} directories and takes the free-list head. A {@code null} location of the trigger action gives a
     * zero rectangle and center.
     *
     * @param identifier the four-character script identifier
     * @param owner      the script owner
     * @param location   the location rectangle {@code left, top, right, bottom}, or {@code null}
     * @return {@code true} if a record was started, {@code false} otherwise
     */
    boolean start(int identifier, int owner, int @Nullable [] location) {
        if (location == null) {
            return this.start(identifier, owner, 0, 0, 0, 0, 0, 0);
        }

        return this.start(identifier,
                          owner,
                          location[LOCATION_LEFT], location[LOCATION_TOP],
                          location[LOCATION_RIGHT], location[LOCATION_BOTTOM],
                          (location[LOCATION_RIGHT] - location[LOCATION_LEFT]) / LOCATION_CENTER_DIVISOR + location[LOCATION_LEFT],
                          (location[LOCATION_BOTTOM] - location[LOCATION_TOP]) / LOCATION_CENTER_DIVISOR + location[LOCATION_TOP]);
    }

    private boolean start(int identifier, int owner, int left, int top, int right, int bottom, int centerX, int centerY) {
        long resolved = this.program.find(identifier);

        if (resolved == -1 || this.firstFree == NONE) {
            return false;
        }

        int index = this.activateFreeRecord();

        this.addresses[index] = (int) resolved;
        this.waits[index]     = 0;
        this.owners[index]    = owner;
        // Reusing a record keeps its killable and release bits and replaces the expansion bit with the directory selector;
        // reproduced on purpose.
        this.flags[index]   = this.flags[index] & FLAGS_KEPT_ON_START | (int) (resolved >>> Integer.SIZE);
        this.left[index]    = left;
        this.top[index]     = top;
        this.right[index]   = right;
        this.bottom[index]  = bottom;
        this.centerX[index] = centerX;
        this.centerY[index] = centerY;
        this.towns[index]   = null;

        return true;
    }

    /**
     * Decrements every active wait and executes or releases expired records.
     *
     * @param phase            the owning computer phase
     * @param instructionLimit the iscript budget for issued orders
     */
    void update(@NotNull ComputerPlayerPhase phase, int instructionLimit) {
        int index = this.firstActive;

        while (index != NONE) {
            int following = this.next[index];
            int previousWait = this.waits[index]--;

            if (previousWait == 0) {
                if ((this.flags[index] & FLAG_RELEASE) != 0) {
                    this.release(index);
                } else {
                    this.execute(phase, index, instructionLimit);
                }
            }

            if (this.firstActive == NONE) {
                break;
            }

            index = following;
        }
    }

    /**
     * Takes the free-list head and links it as the new active-list head; the caller checks that a free record exists.
     *
     * @return the taken record
     */
    private int activateFreeRecord() {
        int index = this.firstFree;

        this.firstFree = this.next[index];

        if (this.firstFree != NONE) {
            this.previous[this.firstFree] = NONE;
        }

        this.previous[index] = NONE;
        this.next[index]     = this.firstActive;

        if (this.firstActive != NONE) {
            this.previous[this.firstActive] = index;
        }

        this.firstActive = index;

        return index;
    }

    /**
     * Returns an active record to the free-list head, as the release path of the update and opcode {@code stop}.
     */
    private void release(int index) {
        if (this.firstActive == index) {
            this.firstActive = this.next[index];
        }

        if (this.previous[index] != NONE) {
            this.next[this.previous[index]] = this.next[index];
        }

        if (this.next[index] != NONE) {
            this.previous[this.next[index]] = this.previous[index];
        }

        this.previous[index] = NONE;
        this.next[index]     = this.firstFree;

        if (this.firstFree != NONE) {
            this.previous[this.firstFree] = index;
        }

        this.firstFree = index;
    }

    /**
     * Runs the opcodes of a record until one stops it for this frame.
     *
     * @throws UnsupportedOperationException if the record owner is outside the playable player slots or an opcode has no instruction
     */
    private void execute(@NotNull ComputerPlayerPhase phase, int index, int instructionLimit) {
        int owner = this.owners[index];

        if (owner >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            throw Exceptions.newUnsupportedOperationException("Computer script owner is outside the playable player slots.");
        }

        boolean expansion = (this.flags[index] & FLAG_EXPANSION) != 0;
        ScrComputerScriptContext context = this.context;

        context.begin(phase, index, owner, expansion, instructionLimit);

        while (true) {
            int start = this.addresses[index];
            int opcode = this.readByte(expansion, index);
            ComputerScriptInstruction instruction = this.instructions.find(opcode);

            if (instruction == null) {
                // Opcodes above the last supported opcode are rejected.
                this.addresses[index] = start;

                throw Exceptions.newUnsupportedOperationException(
                        "Computer script opcode is outside its instruction table: owner={0}, opcode={1}, address={2}",
                        owner,
                        opcode,
                        start
                );
            }

            context.setInstruction(opcode, start);

            if (!instruction.execute(context)) {
                return;
            }
        }
    }

    /**
     * Creates the instruction registry: the instruction of every supported opcode, frozen.
     */
    private @NotNull ComputerInstructionRegistry<ComputerScriptInstruction> createInstructions() {
        ComputerInstructionRegistry<ComputerScriptInstruction> instructions = new ComputerInstructionRegistry<>(ComputerScriptOpcodes.COUNT);

        instructions.register(ComputerScriptOpcodes.GOTO, this::executeGoto);
        instructions.register(ComputerScriptOpcodes.WAIT, this::executeWait);

        ComputerScriptInstruction startTown = this::executeStartTown;

        instructions.register(ComputerScriptOpcodes.START_TOWN, startTown);
        instructions.register(ComputerScriptOpcodes.START_AREATOWN, startTown);
        instructions.register(ComputerScriptOpcodes.BUILD, this::executeBuild);

        ComputerScriptInstruction waitBuild = this::executeWaitBuild;

        instructions.register(ComputerScriptOpcodes.WAIT_BUILD, waitBuild);
        instructions.register(ComputerScriptOpcodes.WAIT_BUILDSTART, waitBuild);
        instructions.register(ComputerScriptOpcodes.STOP, this::executeStop);

        ComputerScriptInstruction defenseRequests = this::executeDefenseRequests;

        instructions.register(ComputerScriptOpcodes.DEFENSEBUILD_GG, defenseRequests);
        instructions.register(ComputerScriptOpcodes.DEFENSEBUILD_AG, defenseRequests);
        instructions.register(ComputerScriptOpcodes.DEFENSEBUILD_GA, defenseRequests);
        instructions.register(ComputerScriptOpcodes.DEFENSEBUILD_AA, defenseRequests);
        instructions.register(ComputerScriptOpcodes.DEFENSEUSE_GG, defenseRequests);
        instructions.register(ComputerScriptOpcodes.DEFENSEUSE_AG, defenseRequests);
        instructions.register(ComputerScriptOpcodes.DEFENSEUSE_GA, defenseRequests);
        instructions.register(ComputerScriptOpcodes.DEFENSEUSE_AA, defenseRequests);

        ComputerScriptInstruction clearDefenseRequests = this::executeClearDefenseRequests;

        instructions.register(ComputerScriptOpcodes.DEFENSECLEAR_GG, clearDefenseRequests);
        instructions.register(ComputerScriptOpcodes.DEFENSECLEAR_AG, clearDefenseRequests);
        instructions.register(ComputerScriptOpcodes.DEFENSECLEAR_GA, clearDefenseRequests);
        instructions.register(ComputerScriptOpcodes.DEFENSECLEAR_AA, clearDefenseRequests);

        ComputerScriptInstruction transportsOff = this::executeTransportsOff;

        instructions.register(ComputerScriptOpcodes.TRANSPORTS_OFF, transportsOff);
        instructions.register(ComputerScriptOpcodes.CHECK_TRANSPORTS, transportsOff);
        instructions.register(ComputerScriptOpcodes.RANDOM_JUMP, this::executeRandomJump);

        ComputerScriptInstruction farmTiming = this::executeSetFarmTiming;

        instructions.register(ComputerScriptOpcodes.FARMS_NOTIMING, farmTiming);
        instructions.register(ComputerScriptOpcodes.FARMS_TIMING, farmTiming);
        instructions.register(ComputerScriptOpcodes.VALUE_AREA, this::executeValueArea);
        instructions.register(ComputerScriptOpcodes.START_CAMPAIGN, this::executeStartCampaign);
        instructions.register(ComputerScriptOpcodes.DEFAULTBUILD_OFF, this::executeDisableDefaultBuild);
        instructions.register(ComputerScriptOpcodes.DEFAULT_MIN, this::executeDefaultMin);
        instructions.register(ComputerScriptOpcodes.GET_OLDPEONS, this::executeTransferWorkers);
        instructions.register(ComputerScriptOpcodes.GIVE_MONEY, this::executeGiveMoney);
        instructions.register(ComputerScriptOpcodes.SEND_SUICIDE, this::executeSendSuicide);
        instructions.register(ComputerScriptOpcodes.ENTER_BUNKER, this::executeEnterBunker);
        instructions.register(ComputerScriptOpcodes.NUKE_RATE, this::executeNukeRate);
        instructions.register(ComputerScriptOpcodes.MAX_FORCE, this::executeMaxForce);
        instructions.register(ComputerScriptOpcodes.FAKE_NUKE, this::executeFakeNuke);
        instructions.register(ComputerScriptOpcodes.RACE_JUMP, this::executeRaceJump);
        instructions.register(ComputerScriptOpcodes.GROUNDMAP_JUMP, this::executeGroundMapJump);

        ComputerScriptInstruction sharedVision = this::executeSetSharedVision;

        instructions.register(ComputerScriptOpcodes.SHAREDVISION_ON, sharedVision);
        instructions.register(ComputerScriptOpcodes.SHAREDVISION_OFF, sharedVision);
        instructions.register(ComputerScriptOpcodes.NOTOWNS_JUMP, this::executeNoTownUnitsJump);
        instructions.register(ComputerScriptOpcodes.CALL, this::executeCall);
        instructions.register(ComputerScriptOpcodes.RETURN, this::executeReturn);
        instructions.register(ComputerScriptOpcodes.CREEP, this::executeCreep);

        ComputerScriptInstruction doMorph = this::executeDoMorph;

        instructions.register(ComputerScriptOpcodes.DO_MORPH, doMorph);
        instructions.register(ComputerScriptOpcodes.TRAIN, doMorph);
        instructions.register(ComputerScriptOpcodes.WAIT_FORCE, doMorph);
        instructions.register(ComputerScriptOpcodes.RESOURCES_JUMP, this::executeResourcesJump);
        instructions.register(ComputerScriptOpcodes.IF_OWNED, this::executeIfOwned);
        instructions.register(ComputerScriptOpcodes.MULTIRUN, this::executeMultirun);
        instructions.register(ComputerScriptOpcodes.WAIT_TRAIN, this::executeWaitTrain);
        instructions.register(ComputerScriptOpcodes.UPGRADE, this::executeUpgrade);
        instructions.register(ComputerScriptOpcodes.TECH, this::executeTech);
        instructions.register(ComputerScriptOpcodes.PANIC, this::executePanic);
        instructions.register(ComputerScriptOpcodes.ATTACK_CLEAR, this::executeAttackClear);
        instructions.register(ComputerScriptOpcodes.ATTACK_ADD, this::executeAttackAdd);
        instructions.register(ComputerScriptOpcodes.ATTACK_PREPARE, this::executeAttackPrepare);
        instructions.register(ComputerScriptOpcodes.ATTACK_DO, this::executeAttackDo);
        instructions.register(ComputerScriptOpcodes.PLAYER_NEED, this::executePlayerNeed);
        instructions.register(ComputerScriptOpcodes.EXPAND, this::executeExpand);
        instructions.register(ComputerScriptOpcodes.CAPT_EXPAND, this::executeCaptureExpansions);
        instructions.register(ComputerScriptOpcodes.ENEMYOWNS_JUMP, this::executeEnemyOwnsJump);
        instructions.register(ComputerScriptOpcodes.GUARD_RESOURCES, this::executeGuardResources);
        instructions.register(ComputerScriptOpcodes.PLACE_GUARD, this::executePlaceGuard);
        instructions.register(ComputerScriptOpcodes.DEFINE_MAX, this::executeDefineMax);
        instructions.register(ComputerScriptOpcodes.WAIT_SECURE, this::executeWaitSecure);
        instructions.register(ComputerScriptOpcodes.BUILD_BUNKERS, this::executeBuildBunkers);
        instructions.register(ComputerScriptOpcodes.BUILD_TURRETS, this::executeBuildTurrets);

        ComputerScriptInstruction waitBunkers = this::executeWaitBunkers;

        instructions.register(ComputerScriptOpcodes.WAIT_BUNKERS, waitBunkers);
        instructions.register(ComputerScriptOpcodes.WAIT_TURRETS, waitBunkers);

        ComputerScriptInstruction playerEnemy = this::executePlayerEnemy;

        instructions.register(ComputerScriptOpcodes.PLAYER_ENEMY, playerEnemy);
        instructions.register(ComputerScriptOpcodes.PLAYER_ALLY, playerEnemy);
        instructions.register(ComputerScriptOpcodes.SWITCH_RESCUE, this::executeSwitchRescue);
        instructions.register(ComputerScriptOpcodes.MOVE_DT, this::executeMoveDarkTemplars);
        instructions.register(ComputerScriptOpcodes.DEBUG, this::executeDebug);
        instructions.register(ComputerScriptOpcodes.FATAL_ERROR, this::executeFatalError);
        instructions.register(ComputerScriptOpcodes.CLEAR_COMBATDATA, this::executeClearCombatData);
        instructions.register(ComputerScriptOpcodes.TIME_JUMP, this::executeTimeJump);
        instructions.register(ComputerScriptOpcodes.DEFAULT_BUILD, this::executeEnableDefaultBuild);
        instructions.register(ComputerScriptOpcodes.HARASS_FACTOR, this::executeHarassFactor);
        instructions.register(ComputerScriptOpcodes.REGION_SIZE, this::executeRegionSize);
        instructions.register(ComputerScriptOpcodes.EVAL_HARASS, this::executeEvalHarass);
        instructions.register(ComputerScriptOpcodes.WAIT_UPGRADES, this::executeWaitUpgrades);
        instructions.register(ComputerScriptOpcodes.RUSH, this::executeRush);
        instructions.register(ComputerScriptOpcodes.SCOUT_WITH, this::executeScoutWith);
        instructions.register(ComputerScriptOpcodes.TARGET_EXPANSION, this::executeTargetExpansion);
        instructions.register(ComputerScriptOpcodes.SET_ATTACKS, this::executeSetAttacks);
        instructions.register(ComputerScriptOpcodes.SET_GENCMD, this::executeSetGeneralCommand);
        instructions.register(ComputerScriptOpcodes.MAKE_PATROL, this::executeMakePatrol);
        instructions.register(ComputerScriptOpcodes.PREP_DOWN, this::executePrepDown);
        instructions.register(ComputerScriptOpcodes.ENTER_TRANSPORT, this::executeEnterTransport);
        instructions.register(ComputerScriptOpcodes.EXIT_TRANSPORT, this::executeExitTransport);
        instructions.register(ComputerScriptOpcodes.NUKE_LOCATION, this::executeNuclearStrikeAtLocation);
        instructions.register(ComputerScriptOpcodes.NUKE_POS, this::executeNuclearStrikeAtPosition);
        instructions.register(ComputerScriptOpcodes.HARASS_LOCATION, this::executeHarassLocation);
        instructions.register(ComputerScriptOpcodes.IMPLODE, this::executeImplode);
        instructions.register(ComputerScriptOpcodes.GUARD_ALL, this::executeGuardAll);
        instructions.register(ComputerScriptOpcodes.ENEMYRESOURCES_JUMP, this::executeEnemyResourcesJump);
        instructions.register(ComputerScriptOpcodes.IF_DIF, this::executeDifficultyJump);
        instructions.register(ComputerScriptOpcodes.EASY_ATTACK, this::executeEasyAttack);
        instructions.register(ComputerScriptOpcodes.KILL_THREAD, this::executeKillThread);
        instructions.register(ComputerScriptOpcodes.KILLABLE, this::executeKillable);
        instructions.register(ComputerScriptOpcodes.WAIT_FINISHATTACK, this::executeWaitForAttackCompletion);
        instructions.register(ComputerScriptOpcodes.QUICK_ATTACK, this::executeQuickAttack);
        instructions.register(ComputerScriptOpcodes.JUNKYARD_DOG, this::executeJunkyardDog);
        instructions.register(ComputerScriptOpcodes.DISRUPTION_WEB, this::executeDisruptionWeb);
        instructions.register(ComputerScriptOpcodes.RECALL_LOCATION, this::executeRecallLocation);
        instructions.register(ComputerScriptOpcodes.SET_RANDOMSEED, this::executeSetRandomSeed);
        instructions.register(ComputerScriptOpcodes.CREATE_NUKE, this::executeCreateNuke);
        instructions.register(ComputerScriptOpcodes.CREATE_UNIT, this::executeCreateUnit);
        instructions.register(ComputerScriptOpcodes.HELP_IFTROUBLE, this::executeHelpAlliesIfInTrouble);
        instructions.register(ComputerScriptOpcodes.ALLIES_WATCH, this::executeAlliesWatch);
        instructions.register(ComputerScriptOpcodes.TRY_TOWNPOINT, this::executeTownCountJump);

        instructions.freeze();

        return instructions;
    }

    /**
     * Executes {@code goto}.
     */
    private boolean executeGoto(@NotNull ComputerPlayerPhase phase,
                                int index,
                                int opcode, int start,
                                int owner,
                                boolean expansion,
                                int instructionLimit) {
        this.addresses[index] = this.readWord(expansion, index);

        return true;
    }

    /**
     * Executes {@code wait}.
     */
    private boolean executeWait(@NotNull ComputerPlayerPhase phase,
                                int index,
                                int opcode, int start,
                                int owner,
                                boolean expansion,
                                int instructionLimit) {
        this.waits[index] = this.readWord(expansion, index);

        return false;
    }

    /**
     * Executes {@code start_town}, {@code start_areatown}: {@code start_town} founds the town at the center,
     * {@code start_areatown} from the units in the rectangle.
     */
    private boolean executeStartTown(@NotNull ComputerPlayerPhase phase,
                                     int index,
                                     int opcode, int start,
                                     int owner,
                                     boolean expansion,
                                     int instructionLimit) {
        ComputerTown town = opcode == ComputerScriptOpcodes.START_TOWN
                            ? phase.startTown(owner,
                                              this.centerX[index], this.centerY[index],
                                              this.flags[index] >>> RESOURCE_AREA_SHIFT,
                                              instructionLimit)
                            : phase.startAreaTown(owner,
                                                  this.left[index], this.top[index],
                                                  this.right[index], this.bottom[index],
                                                  this.flags[index] >>> RESOURCE_AREA_SHIFT,
                                                  instructionLimit);

        this.towns[index] = town;

        if (town == null) {
            this.release(index);

            return false;
        }

        phase.getPlayer(owner).markTownStarted();

        Player player = this.game.getPlayerTable().getPlayer(owner);

        if (player != null && player.getType() == PlayerTypes.COMPUTER) {
            int regionCount = this.game.getComputerTerrain().getRegions().getRegionCount();

            for (int region = 0; region < regionCount; region++) {
                phase.getRegions().update(phase, owner, region);
            }
        }

        return true;
    }

    /**
     * Executes {@code build}.
     *
     * @throws UnsupportedOperationException if the script record has no town
     */
    private boolean executeBuild(@NotNull ComputerPlayerPhase phase,
                                 int index,
                                 int opcode, int start,
                                 int owner,
                                 boolean expansion,
                                 int instructionLimit) {
        ComputerTown town = this.requireTown(index, opcode);
        int count = this.readByte(expansion, index);
        int unitType = this.readUnitType(expansion, index);
        int priority = this.readByte(expansion, index);

        if (count < ComputerTown.REQUEST_COUNT_LIMIT) {
            phase.addTownRequest(town, ComputerTown.REQUEST_UNIT, count, unitType, priority, 0);
        }

        return true;
    }

    /**
     * Executes {@code wait_build}, {@code wait_buildstart}.
     *
     * @throws UnsupportedOperationException if the script record has no town or the owner has no player record
     */
    private boolean executeWaitBuild(@NotNull ComputerPlayerPhase phase,
                                     int index,
                                     int opcode, int start,
                                     int owner,
                                     boolean expansion,
                                     int instructionLimit) {
        ComputerTown town = this.requireTown(index, opcode);
        int count = this.readByte(expansion, index);
        int unitType = this.readUnitType(expansion, index);
        Player player = this.game.getPlayerTable().getPlayer(owner);

        if (player == null) {
            throw Exceptions.newUnsupportedOperationException("Computer build waits require the owner's player record.");
        }

        if (unitType == ComputerUnits.getWorkerType(player.getRace()) && town.resourceArea != 0) {
            int desired = this.game.getComputerTowns().getDesiredWorkers(phase, town.resourceArea, owner);

            if (Integer.compareUnsigned(desired, count) < 0) {
                count = desired & BYTE_MASK;
            }
        }

        if (this.game.getComputerTowns().countTownUnits(town, unitType, opcode == ComputerScriptOpcodes.WAIT_BUILD) < count) {
            this.addresses[index] = start;
            this.waits[index]     = RETRY_WAIT;

            return false;
        }

        return true;
    }

    /**
     * Executes {@code stop}.
     */
    private boolean executeStop(@NotNull ComputerPlayerPhase phase,
                                int index,
                                int opcode, int start,
                                int owner,
                                boolean expansion,
                                int instructionLimit) {
        this.release(index);

        return false;
    }

    /**
     * Executes {@code defensebuild_gg}, {@code defensebuild_ag}, {@code defensebuild_ga}, {@code defensebuild_aa},
     * {@code defenseuse_gg}, {@code defenseuse_ag}, {@code defenseuse_ga}, {@code defenseuse_aa}.
     */
    private boolean executeDefenseRequests(@NotNull ComputerPlayerPhase phase,
                                           int index,
                                           int opcode, int start,
                                           int owner,
                                           boolean expansion,
                                           int instructionLimit) {
        int count = this.readByte(expansion, index);
        int unitType = this.readUnitType(expansion, index);

        phase.getPlayer(owner).addDefenseUnits(opcode - ComputerScriptOpcodes.DEFENSEBUILD_GG, count, unitType);

        return true;
    }

    /**
     * Executes {@code defenseclear_gg}, {@code defenseclear_ag}, {@code defenseclear_ga}, {@code defenseclear_aa}.
     */
    private boolean executeClearDefenseRequests(@NotNull ComputerPlayerPhase phase,
                                                int index,
                                                int opcode, int start,
                                                int owner,
                                                boolean expansion,
                                                int instructionLimit) {
        phase.getPlayer(owner).clearDefenseUnits(opcode - ComputerScriptOpcodes.DEFENSECLEAR_GG);

        return true;
    }

    /**
     * Executes {@code transports_off}, {@code check_transports}.
     */
    private boolean executeTransportsOff(@NotNull ComputerPlayerPhase phase,
                                         int index,
                                         int opcode, int start,
                                         int owner,
                                         boolean expansion,
                                         int instructionLimit) {
        phase.getPlayer(owner).setTransportsEnabled(opcode == ComputerScriptOpcodes.CHECK_TRANSPORTS);

        return true;
    }

    /**
     * Executes {@code random_jump}.
     */
    private boolean executeRandomJump(@NotNull ComputerPlayerPhase phase,
                                      int index,
                                      int opcode, int start,
                                      int owner,
                                      boolean expansion,
                                      int instructionLimit) {
        int chance = this.readByte(expansion, index);
        int target = this.readWord(expansion, index);

        if ((this.game.getRandom().next(RANDOM_JUMP_RANDOM_SOURCE) & BYTE_MASK) <= chance) {
            this.addresses[index] = target;
        }

        return true;
    }

    /**
     * Executes {@code farms_notiming}, {@code farms_timing}.
     */
    private boolean executeSetFarmTiming(@NotNull ComputerPlayerPhase phase,
                                         int index,
                                         int opcode, int start,
                                         int owner,
                                         boolean expansion,
                                         int instructionLimit) {
        phase.getPlayer(owner).setFarmTiming(opcode == ComputerScriptOpcodes.FARMS_TIMING);

        return true;
    }

    /**
     * Executes {@code value_area}: raises the region at the script center to a town region when it is below that state,
     * with both required strengths at least {@value #VALUE_AREA_MINIMUM_STRENGTH}.
     */
    private boolean executeValueArea(@NotNull ComputerPlayerPhase phase,
                                     int index,
                                     int opcode, int start,
                                     int owner,
                                     boolean expansion,
                                     int instructionLimit) {
        ComputerRegions regions = phase.getRegions();
        int record = regions.getIndex(owner, this.game.getComputerTerrain().getRegion(this.centerX[index], this.centerY[index]));

        if (regions.getState(record) < ComputerRegionStates.TOWN) {
            regions.setState(phase, record, ComputerRegionStates.TOWN);

            if (regions.getRequiredGroundStrength(record) < VALUE_AREA_MINIMUM_STRENGTH) {
                regions.setRequiredGroundStrength(record, VALUE_AREA_MINIMUM_STRENGTH);
            }

            if (regions.getRequiredAirStrength(record) < VALUE_AREA_MINIMUM_STRENGTH) {
                regions.setRequiredAirStrength(record, VALUE_AREA_MINIMUM_STRENGTH);
            }
        }

        return true;
    }

    /**
     * Executes {@code start_campaign}: sets the campaign flag.
     */
    private boolean executeStartCampaign(@NotNull ComputerPlayerPhase phase,
                                         int index,
                                         int opcode, int start,
                                         int owner,
                                         boolean expansion,
                                         int instructionLimit) {
        ComputerPlayer player = phase.getPlayer(owner);

        player.setFlags(player.getFlags() | ComputerPlayer.FLAG_CAMPAIGN);

        return true;
    }

    /**
     * Executes {@code defaultbuild_off}: sets the default-build-off flag, which the town request processing reads.
     */
    private boolean executeDisableDefaultBuild(@NotNull ComputerPlayerPhase phase,
                                               int index,
                                               int opcode, int start,
                                               int owner,
                                               boolean expansion,
                                               int instructionLimit) {
        ComputerPlayer player = phase.getPlayer(owner);

        player.setFlags(player.getFlags() | ComputerPlayer.FLAG_DEFAULT_BUILD_OFF);

        return true;
    }

    /**
     * Executes {@code default_min}: one BYTE operand for the region strength scale.
     */
    private boolean executeDefaultMin(@NotNull ComputerPlayerPhase phase,
                                      int index,
                                      int opcode, int start,
                                      int owner,
                                      boolean expansion,
                                      int instructionLimit) {
        phase.getPlayer(owner).setRegionStrengthScale(this.readByte(expansion, index));

        return true;
    }

    /**
     * Executes {@code get_oldpeons}: requires a town, then moves surplus workers to it.
     *
     * @throws UnsupportedOperationException if the script record has no town
     */
    private boolean executeTransferWorkers(@NotNull ComputerPlayerPhase phase,
                                           int index,
                                           int opcode, int start,
                                           int owner,
                                           boolean expansion,
                                           int instructionLimit) {
        ComputerTown town = this.requireTown(index, opcode);

        phase.transferWorkers(town, this.readByte(expansion, index));

        return true;
    }

    /**
     * Executes {@code give_money}: adds {@value #GIVE_MONEY_AMOUNT} minerals and gas below {@value #GIVE_MONEY_THRESHOLD}
     * each.
     */
    private boolean executeGiveMoney(@NotNull ComputerPlayerPhase phase,
                                     int index,
                                     int opcode, int start,
                                     int owner,
                                     boolean expansion,
                                     int instructionLimit) {
        Player player = this.game.getPlayerTable().requirePlayer(owner);

        if (player.getMinerals() < GIVE_MONEY_THRESHOLD) {
            player.setMinerals(player.getMinerals() + GIVE_MONEY_AMOUNT);
        }

        if (player.getGas() < GIVE_MONEY_THRESHOLD) {
            player.setGas(player.getGas() + GIVE_MONEY_AMOUNT);
        }

        return true;
    }

    /**
     * Executes {@code send_suicide}: clears the attack group, sends a suicide or strength attack by the BYTE operand, then
     * sets the attack delay.
     */
    private boolean executeSendSuicide(@NotNull ComputerPlayerPhase phase,
                                       int index,
                                       int opcode, int start,
                                       int owner,
                                       boolean expansion,
                                       int instructionLimit) {
        int argument = this.readByte(expansion, index);

        ComputerAttack.clear(phase, owner, true);

        if (argument != 0) {
            this.game.getComputerAttack().sendSuicide(phase, owner);
        } else {
            this.game.getComputerAttack().startStrengthAttack(phase, owner);
        }

        phase.getPlayer(owner).setAttackDelay(ATTACK_DELAY, this.game.getComputerTowns().getElapsedClockTicks());

        return true;
    }

    /**
     * Executes {@code enter_bunker} with the script rectangle.
     */
    private boolean executeEnterBunker(@NotNull ComputerPlayerPhase phase,
                                       int index,
                                       int opcode, int start,
                                       int owner,
                                       boolean expansion,
                                       int instructionLimit) {
        this.game.getComputerMilitary().enterBunkers(phase, owner, this.left[index], this.top[index], this.right[index], this.bottom[index]);

        return true;
    }

    /**
     * Executes {@code nuke_rate}: one BYTE operand.
     */
    private boolean executeNukeRate(@NotNull ComputerPlayerPhase phase,
                                    int index,
                                    int opcode, int start,
                                    int owner,
                                    boolean expansion,
                                    int instructionLimit) {
        phase.getPlayer(owner).setNukeRate(this.readByte(expansion, index));

        return true;
    }

    /**
     * Executes {@code max_force}: one WORD operand for the strength limit.
     */
    private boolean executeMaxForce(@NotNull ComputerPlayerPhase phase,
                                    int index,
                                    int opcode, int start,
                                    int owner,
                                    boolean expansion,
                                    int instructionLimit) {
        phase.getPlayer(owner).setStrengthLimit(this.readWord(expansion, index));

        return true;
    }

    /**
     * Executes {@code fake_nuke}: stores the clock plus one.
     */
    private boolean executeFakeNuke(@NotNull ComputerPlayerPhase phase,
                                    int index,
                                    int opcode, int start,
                                    int owner,
                                    boolean expansion,
                                    int instructionLimit) {
        phase.getPlayer(owner).setLastNukeClock(this.game.getComputerTowns().getElapsedClockTicks() + 1);

        return true;
    }

    /**
     * Executes {@code race_jump}: jumps to the target of the nearest enemy's race.
     *
     * @throws UnsupportedOperationException if the script record has no town
     */
    private boolean executeRaceJump(@NotNull ComputerPlayerPhase phase,
                                    int index,
                                    int opcode, int start,
                                    int owner,
                                    boolean expansion,
                                    int instructionLimit) {
        ComputerTown town = this.requireTown(index, opcode);
        int terranTarget = this.readWord(expansion, index);
        int zergTarget = this.readWord(expansion, index);
        int protossTarget = this.readWord(expansion, index);
        int race = phase.findEnemyRace(town);

        if (race == ScrRaceIds.ZERG) {
            this.addresses[index] = zergTarget;
        } else if (race == ScrRaceIds.TERRAN) {
            this.addresses[index] = terranTarget;
        } else if (race == ScrRaceIds.PROTOSS) {
            this.addresses[index] = protossTarget;
        }

        return true;
    }

    /**
     * Executes {@code groundmap_jump}.
     *
     * @throws UnsupportedOperationException if the script record has no town
     */
    private boolean executeGroundMapJump(@NotNull ComputerPlayerPhase phase,
                                         int index,
                                         int opcode, int start,
                                         int owner,
                                         boolean expansion,
                                         int instructionLimit) {
        this.requireTown(index, opcode);

        int target = this.readWord(expansion, index);

        if (phase.hasGroundEnemy(owner, this.centerX[index], this.centerY[index])) {
            this.addresses[index] = target;
        }

        return true;
    }

    /**
     * Executes {@code sharedvision_on}, {@code sharedvision_off}.
     *
     * @throws UnsupportedOperationException if the vision player is outside the player slots
     */
    private boolean executeSetSharedVision(@NotNull ComputerPlayerPhase phase,
                                           int index,
                                           int opcode, int start,
                                           int owner,
                                           boolean expansion,
                                           int instructionLimit) {
        int player = this.readByte(expansion, index);

        if (player >= PlayerTable.PLAYER_SLOTS) {
            throw Exceptions.newUnsupportedOperationException("Computer script vision player is outside the player slots: {0}", player);
        }

        this.setVision(owner, player, opcode == ComputerScriptOpcodes.SHAREDVISION_ON);

        return true;
    }

    /**
     * Executes {@code notowns_jump}.
     *
     * @throws UnsupportedOperationException if the unit accounting is missing
     */
    private boolean executeNoTownUnitsJump(@NotNull ComputerPlayerPhase phase,
                                           int index,
                                           int opcode, int start,
                                           int owner,
                                           boolean expansion,
                                           int instructionLimit) {
        int unitType = this.readUnitType(expansion, index);
        int target = this.readWord(expansion, index);

        if (this.game.getComputerUnits().getTypeCount(unitType, owner) == 0) {
            this.addresses[index] = target;
        }

        return true;
    }

    /**
     * Executes {@code call}.
     */
    private boolean executeCall(@NotNull ComputerPlayerPhase phase,
                                int index,
                                int opcode, int start,
                                int owner,
                                boolean expansion,
                                int instructionLimit) {
        int target = this.readWord(expansion, index);

        this.callReturn       = this.addresses[index];
        this.addresses[index] = target;

        return true;
    }

    /**
     * Executes {@code return}.
     */
    private boolean executeReturn(@NotNull ComputerPlayerPhase phase,
                                  int index,
                                  int opcode, int start,
                                  int owner,
                                  boolean expansion,
                                  int instructionLimit) {
        this.addresses[index] = this.callReturn;

        this.callReturn = 0;

        return true;
    }

    /**
     * Executes {@code creep}.
     *
     * @throws UnsupportedOperationException if the script record has no town
     */
    private boolean executeCreep(@NotNull ComputerPlayerPhase phase,
                                 int index,
                                 int opcode, int start,
                                 int owner,
                                 boolean expansion,
                                 int instructionLimit) {
        this.requireTown(index, opcode);
        phase.getPlayer(owner).setCreepSpacing(this.readByte(expansion, index) == CREEP_SPACING);

        return true;
    }

    /**
     * Executes {@code do_morph}, {@code train}, {@code wait_force}: {@code do_morph} and {@code wait_force} count
     * completed units, {@code train} all units; below the count the type is requested and every opcode but
     * {@code do_morph} retries.
     */
    private boolean executeDoMorph(@NotNull ComputerPlayerPhase phase,
                                   int index,
                                   int opcode, int start,
                                   int owner,
                                   boolean expansion,
                                   int instructionLimit) {
        int count = this.readByte(expansion, index);
        int unitType = this.readUnitType(expansion, index);

        if (this.countUnits(unitType, owner, opcode != ComputerScriptOpcodes.TRAIN) < count) {
            phase.getPlayer(owner).setPendingUnitRequest(unitType);

            if (opcode != ComputerScriptOpcodes.DO_MORPH) {
                this.addresses[index] -= COUNT_TYPE_INSTRUCTION_SIZE;
                this.waits[index] =      RETRY_WAIT;

                return false;
            }
        }

        return true;
    }

    /**
     * Executes {@code resources_jump}: jumps to the block when the owner has at least the minerals and the gas (signed
     * DWORD compares).
     */
    private boolean executeResourcesJump(@NotNull ComputerPlayerPhase phase,
                                         int index,
                                         int opcode, int start,
                                         int owner,
                                         boolean expansion,
                                         int instructionLimit) {
        int minerals = this.readWord(expansion, index);
        int gas = this.readWord(expansion, index);
        int target = this.readWord(expansion, index);
        Player player = this.game.getPlayerTable().requirePlayer(owner);

        if (player.getMinerals() >= minerals && player.getGas() >= gas) {
            this.addresses[index] = target;
        }

        return true;
    }

    /**
     * Executes {@code if_owned}: jumps to the block when the owner has any unit of the type (the all-units count without
     * the Siege Tank merge of {@link #countUnits}).
     *
     * @throws UnsupportedOperationException if the unit accounting is missing
     */
    private boolean executeIfOwned(@NotNull ComputerPlayerPhase phase,
                                   int index,
                                   int opcode, int start,
                                   int owner,
                                   boolean expansion,
                                   int instructionLimit) {
        int unitType = this.readUnitType(expansion, index);
        int target = this.readWord(expansion, index);

        if (this.game.getComputerUnits().getTypeCount(unitType, owner) != 0) {
            this.addresses[index] = target;
        }

        return true;
    }

    /**
     * Executes {@code multirun}.
     */
    private boolean executeMultirun(@NotNull ComputerPlayerPhase phase,
                                    int index,
                                    int opcode, int start,
                                    int owner,
                                    boolean expansion,
                                    int instructionLimit) {
        int target = this.readWord(expansion, index);
        int started = this.startThread(target, owner, index);

        if (started != NONE) {
            this.towns[started] = this.towns[index];
        }

        return true;
    }

    /**
     * Executes {@code wait_train}.
     */
    private boolean executeWaitTrain(@NotNull ComputerPlayerPhase phase,
                                     int index,
                                     int opcode, int start,
                                     int owner,
                                     boolean expansion,
                                     int instructionLimit) {
        int count = this.readByte(expansion, index);
        int unitType = this.readUnitType(expansion, index);

        if (this.countUnits(unitType, owner, true) < count) {
            this.addresses[index] -= COUNT_TYPE_INSTRUCTION_SIZE;
            this.waits[index] =      RETRY_WAIT;

            return false;
        }

        return true;
    }

    /**
     * Executes {@code upgrade}.
     *
     * @throws UnsupportedOperationException if the script record has no town
     */
    private boolean executeUpgrade(@NotNull ComputerPlayerPhase phase,
                                   int index,
                                   int opcode, int start,
                                   int owner,
                                   boolean expansion,
                                   int instructionLimit) {
        ComputerTown town = this.requireTown(index, opcode);
        int level = this.readByte(expansion, index);
        int upgrade = this.readWord(expansion, index);
        int priority = this.readByte(expansion, index);

        if (level < UPGRADE_LEVEL_LIMIT && upgrade < ScrUpgradeIds.NONE) {
            phase.addTownRequest(town, ComputerTown.REQUEST_UPGRADE, level, upgrade, priority, 0);
        }

        return true;
    }

    /**
     * Executes {@code tech}.
     *
     * @throws UnsupportedOperationException if the script record has no town
     */
    private boolean executeTech(@NotNull ComputerPlayerPhase phase,
                                int index,
                                int opcode, int start,
                                int owner,
                                boolean expansion,
                                int instructionLimit) {
        ComputerTown town = this.requireTown(index, opcode);
        int technology = this.readWord(expansion, index);
        int priority = this.readByte(expansion, index);

        if (technology < ScrTechIds.NONE) {
            phase.addTownRequest(town, ComputerTown.REQUEST_TECHNOLOGY, 1, technology, priority, 0);
        }

        return true;
    }

    /**
     * Executes {@code panic}.
     */
    private boolean executePanic(@NotNull ComputerPlayerPhase phase,
                                 int index,
                                 int opcode, int start,
                                 int owner,
                                 boolean expansion,
                                 int instructionLimit) {
        phase.getPlayer(owner).setPanicAddress(this.readWord(expansion, index));

        return true;
    }

    /**
     * Executes {@code attack_clear}.
     */
    private boolean executeAttackClear(@NotNull ComputerPlayerPhase phase,
                                       int index,
                                       int opcode, int start,
                                       int owner,
                                       boolean expansion,
                                       int instructionLimit) {
        ComputerAttack.clear(phase, owner, true);

        return true;
    }

    /**
     * Executes {@code attack_add}.
     */
    private boolean executeAttackAdd(@NotNull ComputerPlayerPhase phase,
                                     int index,
                                     int opcode, int start,
                                     int owner,
                                     boolean expansion,
                                     int instructionLimit) {
        int count = this.readByte(expansion, index);
        int unitType = this.readUnitType(expansion, index);

        phase.getPlayer(owner).addAttackUnits(count, unitType);

        return true;
    }

    /**
     * Executes {@code attack_prepare}.
     */
    private boolean executeAttackPrepare(@NotNull ComputerPlayerPhase phase,
                                         int index,
                                         int opcode, int start,
                                         int owner,
                                         boolean expansion,
                                         int instructionLimit) {
        this.game.getComputerAttack().prepare(phase, owner, this.centerX[index], this.centerY[index], true, false);

        return true;
    }

    /**
     * Executes {@code attack_do}: re-executes the opcode after {@value #RETRY_WAIT} frames until the attack starts.
     */
    private boolean executeAttackDo(@NotNull ComputerPlayerPhase phase,
                                    int index,
                                    int opcode, int start,
                                    int owner,
                                    boolean expansion,
                                    int instructionLimit) {
        if (!this.game.getComputerAttack().prepare(phase, owner, this.centerX[index], this.centerY[index], true, false)) {
            this.addresses[index] -= 1;
            this.waits[index] = RETRY_WAIT;

            return false;
        }

        return true;
    }

    /**
     * Executes {@code player_need}: inserts a town request with priority {@value #PLAYER_NEED_PRIORITY} and flag {@code 1}.
     *
     * @throws UnsupportedOperationException if the script record has no town
     */
    private boolean executePlayerNeed(@NotNull ComputerPlayerPhase phase,
                                      int index,
                                      int opcode, int start,
                                      int owner,
                                      boolean expansion,
                                      int instructionLimit) {
        ComputerTown town = this.requireTown(index, opcode);
        int count = this.readByte(expansion, index);
        int unitType = this.readUnitType(expansion, index);

        if (count < ComputerTown.REQUEST_COUNT_LIMIT) {
            phase.addTownRequest(town, ComputerTown.REQUEST_UNIT, count, unitType, PLAYER_NEED_PRIORITY, 1);
        }

        return true;
    }

    /**
     * Executes {@code expand}.
     */
    private boolean executeExpand(@NotNull ComputerPlayerPhase phase,
                                  int index,
                                  int opcode, int start,
                                  int owner,
                                  boolean expansion,
                                  int instructionLimit) {
        int count = this.readByte(expansion, index);
        int address = this.readWord(expansion, index);

        if (Integer.compareUnsigned(phase.countTowns(owner), count) <= 0) {
            this.expand(phase, owner, this.towns[index], address, count, this.flags[index] & FLAG_EXPANSION);
        }

        return true;
    }

    /**
     * Executes {@code capt_expand}.
     *
     * @throws UnsupportedOperationException if the script record has no town
     */
    private boolean executeCaptureExpansions(@NotNull ComputerPlayerPhase phase,
                                             int index,
                                             int opcode, int start,
                                             int owner,
                                             boolean expansion,
                                             int instructionLimit) {
        this.requireTown(index, opcode);
        phase.getRegions().captureExpansions(phase, owner);

        return true;
    }

    /**
     * Executes {@code enemyowns_jump}: jumps when any unit of the type (in creation order) is an enemy of the owner.
     *
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private boolean executeEnemyOwnsJump(@NotNull ComputerPlayerPhase phase,
                                         int index,
                                         int opcode, int start,
                                         int owner,
                                         boolean expansion,
                                         int instructionLimit) {
        int unitType = this.readUnitType(expansion, index);
        int target = this.readWord(expansion, index);
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (this.game.requireGameData().getBaseType(unit) == unitType && this.game.getComputerUnits().isEnemy(owner, unit)) {
                this.addresses[index] = target;

                break;
            }
        }

        return true;
    }

    /**
     * Executes {@code guard_resources}: an empty guard at every free resource area without a guard within {@code 288} pixels.
     *
     * @throws UnsupportedOperationException if the resource areas are not initialized
     */
    private boolean executeGuardResources(@NotNull ComputerPlayerPhase phase,
                                          int index,
                                          int opcode, int start,
                                          int owner,
                                          boolean expansion,
                                          int instructionLimit) {
        int unitType = this.readUnitType(expansion, index);
        ResourceAreas areas = this.game.requireResourceAreas();
        ComputerMilitary military = phase.getMilitary();
        int count = areas.getAreaCount();

        for (int area = 1; area <= count; area++) {
            if (area <= ResourceAreas.LAST_AREA && (areas.getOccupancyFlags(area) & OCCUPIED_AREA_FLAGS) != 0) {
                continue;
            }

            int positionX = area <= ResourceAreas.LAST_AREA ? (short) areas.getHorizontalPosition(area) : 0;
            int positionY = area <= ResourceAreas.LAST_AREA ? (short) areas.getVerticalPosition(area) : 0;

            if (!military.hasGuardNear(owner, positionX, positionY)) {
                military.addEmptyGuard(owner, unitType, positionX, positionY);
            }
        }

        return true;
    }

    /**
     * Executes {@code place_guard}.
     *
     * @throws UnsupportedOperationException if the script record has no town
     */
    private boolean executePlaceGuard(@NotNull ComputerPlayerPhase phase,
                                      int index,
                                      int opcode, int start,
                                      int owner,
                                      boolean expansion,
                                      int instructionLimit) {
        ComputerTown town = this.requireTown(index, opcode);
        int unitType = this.readUnitType(expansion, index);
        int position = this.readByte(expansion, index);

        phase.getMilitary().placeGuard(owner, town, unitType, position);

        return true;
    }

    /**
     * Executes {@code define_max}.
     */
    private boolean executeDefineMax(@NotNull ComputerPlayerPhase phase,
                                     int index,
                                     int opcode, int start,
                                     int owner,
                                     boolean expansion,
                                     int instructionLimit) {
        int maximum = this.readByte(expansion, index);
        int unitType = this.readUnitType(expansion, index);

        phase.getPlayer(owner).setMaximumCount(unitType, maximum);

        return true;
    }

    /**
     * Executes {@code wait_secure}: needs a town; retries every {@value #RETRY_WAIT} frames until the player record is
     * updated.
     *
     * @throws UnsupportedOperationException if the script record has no town
     */
    private boolean executeWaitSecure(@NotNull ComputerPlayerPhase phase,
                                      int index,
                                      int opcode, int start,
                                      int owner,
                                      boolean expansion,
                                      int instructionLimit) {
        this.requireTown(index, opcode);

        if ((phase.getPlayer(owner).getFlags() & ComputerPlayer.FLAG_UPDATED) == 0) {
            this.retry(index, start);

            return false;
        }

        return true;
    }

    /**
     * Executes {@code build_bunkers}.
     */
    private boolean executeBuildBunkers(@NotNull ComputerPlayerPhase phase,
                                        int index,
                                        int opcode, int start,
                                        int owner,
                                        boolean expansion,
                                        int instructionLimit) {
        phase.getRegions().requestBunkers(owner);

        return true;
    }

    /**
     * Executes {@code build_turrets}.
     */
    private boolean executeBuildTurrets(@NotNull ComputerPlayerPhase phase,
                                        int index,
                                        int opcode, int start,
                                        int owner,
                                        boolean expansion,
                                        int instructionLimit) {
        phase.getRegions().requestTurrets(owner);

        return true;
    }

    /**
     * Executes {@code wait_bunkers}, {@code wait_turrets}.
     */
    private boolean executeWaitBunkers(@NotNull ComputerPlayerPhase phase,
                                       int index,
                                       int opcode, int start,
                                       int owner,
                                       boolean expansion,
                                       int instructionLimit) {
        int flag = opcode == ComputerScriptOpcodes.WAIT_BUNKERS ? ComputerRegionStates.FLAG_BUNKER_WANTED : ComputerRegionStates.FLAG_TURRET_WANTED;

        if (!phase.getRegions().isFlagClear(owner, flag)) {
            this.retry(index, start);

            return false;
        }

        return true;
    }

    /**
     * Executes {@code player_enemy}, {@code player_ally}.
     *
     * @throws UnsupportedOperationException if the game has no player relations
     */
    private boolean executePlayerEnemy(@NotNull ComputerPlayerPhase phase,
                                       int index,
                                       int opcode, int start,
                                       int owner,
                                       boolean expansion,
                                       int instructionLimit) {
        int alliance = opcode == ComputerScriptOpcodes.PLAYER_ENEMY ? PlayerRelations.ENEMY : PlayerRelations.ALLIED_VICTORY;
        int scriptOwner = this.owners[index];
        PlayerRelations relations = this.game.getPlayerTable().getPlayerRelations();

        if (relations == null) {
            throw Exceptions.newUnsupportedOperationException("Computer script alliances require player relations.");
        }

        for (Unit unit : this.game.getComputerTerrain().collectRectangle(this.left[index], this.top[index], this.right[index], this.bottom[index])) {
            if (unit.getOwnerIndex() != scriptOwner) {
                relations.setAlliance(scriptOwner, unit.getOwnerIndex(), alliance);
            }
        }

        return true;
    }

    /**
     * Executes {@code switch_rescue}.
     */
    private boolean executeSwitchRescue(@NotNull ComputerPlayerPhase phase,
                                        int index,
                                        int opcode, int start,
                                        int owner,
                                        boolean expansion,
                                        int instructionLimit) {
        phase.switchToRescue(owner, instructionLimit);

        return true;
    }

    /**
     * Executes {@code move_dt} with the script center.
     */
    private boolean executeMoveDarkTemplars(@NotNull ComputerPlayerPhase phase,
                                            int index,
                                            int opcode, int start,
                                            int owner,
                                            boolean expansion,
                                            int instructionLimit) {
        phase.moveDarkTemplars(owner, this.centerX[index], this.centerY[index], instructionLimit);

        return true;
    }

    /**
     * Executes {@code debug}: a WORD target, then the text shown as a chat line of the owner.
     */
    private boolean executeDebug(@NotNull ComputerPlayerPhase phase,
                                 int index,
                                 int opcode, int start,
                                 int owner,
                                 boolean expansion,
                                 int instructionLimit) {
        int target = this.readWord(expansion, index);
        byte[] text = this.program.readText(expansion, this.addresses[index]);

        this.game.getInterfaceLink().getMessageLines().writeChat(owner, text);

        this.addresses[index] = target;

        return true;
    }

    /**
     * Executes {@code fatal_error}: restores the opcode address and fails.
     *
     * @throws UnsupportedOperationException if the instruction executes
     */
    private boolean executeFatalError(@NotNull ComputerPlayerPhase phase,
                                      int index,
                                      int opcode, int start,
                                      int owner,
                                      boolean expansion,
                                      int instructionLimit) {
        this.addresses[index] = start;

        throw Exceptions.newUnsupportedOperationException("Computer script fatal_error is unsupported: owner={0}, address={1}", owner, start);
    }

    /**
     * Executes {@code clear_combatdata} with the script rectangle.
     */
    private boolean executeClearCombatData(@NotNull ComputerPlayerPhase phase,
                                           int index,
                                           int opcode, int start,
                                           int owner,
                                           boolean expansion,
                                           int instructionLimit) {
        phase.getRegions().clearCombatData(phase, owner, this.left[index], this.top[index], this.right[index], this.bottom[index]);

        return true;
    }

    /**
     * Executes {@code time_jump}: jumps once the elapsed clock reached the minutes.
     */
    private boolean executeTimeJump(@NotNull ComputerPlayerPhase phase,
                                    int index,
                                    int opcode, int start,
                                    int owner,
                                    boolean expansion,
                                    int instructionLimit) {
        int minutes = this.readByte(expansion, index);
        int target = this.readWord(expansion, index);

        if (Integer.compareUnsigned(this.game.getComputerTowns().getElapsedClockTicks(), minutes * CLOCK_TICKS_PER_MINUTE) >= 0) {
            this.addresses[index] = target;
        }

        return true;
    }

    /**
     * Executes {@code default_build}: clears the default-build-off flag.
     */
    private boolean executeEnableDefaultBuild(@NotNull ComputerPlayerPhase phase,
                                              int index,
                                              int opcode, int start,
                                              int owner,
                                              boolean expansion,
                                              int instructionLimit) {
        ComputerPlayer player = phase.getPlayer(owner);

        player.setFlags(player.getFlags() & ~ComputerPlayer.FLAG_DEFAULT_BUILD_OFF);

        return true;
    }

    /**
     * Executes {@code harass_factor} with the WORD operand.
     */
    private boolean executeHarassFactor(@NotNull ComputerPlayerPhase phase,
                                        int index,
                                        int opcode, int start,
                                        int owner,
                                        boolean expansion,
                                        int instructionLimit) {
        this.game.getComputerAttack().applyHarassFactor(phase, owner, this.readWord(expansion, index));

        return true;
    }

    /**
     * Executes {@code region_size}: needs a town; jumps when the area walk from the town stays below {@code size * 32} tiles.
     *
     * @throws UnsupportedOperationException if the script record has no town
     */
    private boolean executeRegionSize(@NotNull ComputerPlayerPhase phase,
                                      int index,
                                      int opcode, int start,
                                      int owner,
                                      boolean expansion,
                                      int instructionLimit) {
        ComputerTown town = this.requireTown(index, opcode);
        int limit = this.readByte(expansion, index) << REGION_SIZE_SHIFT;
        int target = this.readWord(expansion, index);

        if (Integer.compareUnsigned(this.game.getComputerTerrain().measureArea(town.positionX, town.positionY, limit), limit) < 0) {
            this.addresses[index] = target;
        }

        return true;
    }

    /**
     * Executes {@code eval_harass}: jumps unless the staging area is favorable.
     */
    private boolean executeEvalHarass(@NotNull ComputerPlayerPhase phase,
                                      int index,
                                      int opcode, int start,
                                      int owner,
                                      boolean expansion,
                                      int instructionLimit) {
        int target = this.readWord(expansion, index);

        if (!this.game.getComputerAttack().isHarassFavorable(phase, owner, this.centerX[index], this.centerY[index])) {
            this.addresses[index] = target;
        }

        return true;
    }

    /**
     * Executes {@code wait_upgrades}: retries until the player's request scan flag is set.
     */
    private boolean executeWaitUpgrades(@NotNull ComputerPlayerPhase phase,
                                        int index,
                                        int opcode, int start,
                                        int owner,
                                        boolean expansion,
                                        int instructionLimit) {
        if ((phase.getPlayer(owner).getFlags() & ComputerPlayer.FLAG_REQUEST_SCAN) == 0) {
            this.retry(index, start);

            return false;
        }

        return true;
    }

    /**
     * Executes {@code rush}: jumps unless the rush kind is viable.
     */
    private boolean executeRush(@NotNull ComputerPlayerPhase phase,
                                int index,
                                int opcode, int start,
                                int owner,
                                boolean expansion,
                                int instructionLimit) {
        int kind = this.readByte(expansion, index);
        int target = this.readWord(expansion, index);

        if (!this.game.getComputerAttack().isRushViable(phase, owner, kind, this.centerX[index], this.centerY[index])) {
            this.addresses[index] = target;
        }

        return true;
    }

    /**
     * Executes {@code scout_with}: skips its WORD.
     */
    private boolean executeScoutWith(@NotNull ComputerPlayerPhase phase,
                                     int index,
                                     int opcode, int start,
                                     int owner,
                                     boolean expansion,
                                     int instructionLimit) {
        this.readWord(expansion, index);

        return true;
    }

    /**
     * Executes {@code target_expansion}: sets the attack-ready flag.
     */
    private boolean executeTargetExpansion(@NotNull ComputerPlayerPhase phase,
                                           int index,
                                           int opcode, int start,
                                           int owner,
                                           boolean expansion,
                                           int instructionLimit) {
        ComputerPlayer player = phase.getPlayer(owner);

        player.setFlags(player.getFlags() | ComputerPlayer.FLAG_ATTACK_READY);

        return true;
    }

    /**
     * Executes {@code set_attacks} with the BYTE operand.
     */
    private boolean executeSetAttacks(@NotNull ComputerPlayerPhase phase,
                                      int index,
                                      int opcode, int start,
                                      int owner,
                                      boolean expansion,
                                      int instructionLimit) {
        phase.getPlayer(owner).setAttackPreparation(this.readByte(expansion, index));

        return true;
    }

    /**
     * Executes {@code set_gencmd}: copies the script rectangle.
     */
    private boolean executeSetGeneralCommand(@NotNull ComputerPlayerPhase phase,
                                             int index,
                                             int opcode, int start,
                                             int owner,
                                             boolean expansion,
                                             int instructionLimit) {
        phase.getPlayer(owner).setGeneralCommandRectangle(this.left[index], this.top[index], this.right[index], this.bottom[index]);

        return true;
    }

    /**
     * Executes {@code make_patrol}.
     */
    private boolean executeMakePatrol(@NotNull ComputerPlayerPhase phase,
                                      int index,
                                      int opcode, int start,
                                      int owner,
                                      boolean expansion,
                                      int instructionLimit) {
        this.issuePatrolOrders(phase, index);

        return true;
    }

    /**
     * Executes {@code prep_down}: the all-units count (Siege Tanks merged) less the first BYTE, at least the second BYTE,
     * joins the attack group.
     */
    private boolean executePrepDown(@NotNull ComputerPlayerPhase phase,
                                    int index,
                                    int opcode, int start,
                                    int owner,
                                    boolean expansion,
                                    int instructionLimit) {
        int keep = this.readByte(expansion, index);
        int minimum = this.readByte(expansion, index);
        int unitType = this.readUnitType(expansion, index);
        int count = this.countUnits(unitType, owner, false) - keep;

        if (count <= minimum) {
            count = minimum;
        }

        phase.getPlayer(owner).addAttackUnits(count, unitType);

        return true;
    }

    /**
     * Executes {@code enter_transport}.
     */
    private boolean executeEnterTransport(@NotNull ComputerPlayerPhase phase,
                                          int index,
                                          int opcode, int start,
                                          int owner,
                                          boolean expansion,
                                          int instructionLimit) {
        this.issueEnterTransportOrders(phase, index);

        return true;
    }

    /**
     * Executes {@code exit_transport}.
     */
    private boolean executeExitTransport(@NotNull ComputerPlayerPhase phase,
                                         int index,
                                         int opcode, int start,
                                         int owner,
                                         boolean expansion,
                                         int instructionLimit) {
        this.issueUnloadTransportOrders(phase, index);

        return true;
    }

    /**
     * Executes {@code nuke_location}.
     */
    private boolean executeNuclearStrikeAtLocation(@NotNull ComputerPlayerPhase phase,
                                                   int index,
                                                   int opcode, int start,
                                                   int owner,
                                                   boolean expansion,
                                                   int instructionLimit) {
        this.issueNuclearStrikeOrder(phase, index, true, 0, 0);

        return true;
    }

    /**
     * Executes {@code nuke_pos}: the same Ghost and Silo as {@code nuke_location}, painting the WORD position.
     */
    private boolean executeNuclearStrikeAtPosition(@NotNull ComputerPlayerPhase phase,
                                                   int index,
                                                   int opcode, int start,
                                                   int owner,
                                                   boolean expansion,
                                                   int instructionLimit) {
        int positionX = this.readWord(expansion, index);
        int positionY = this.readWord(expansion, index);

        this.issueNuclearStrikeOrder(phase, index, false, positionX, positionY);

        return true;
    }

    /**
     * Executes {@code harass_location}, which dispatches straight to the next opcode.
     */
    private boolean executeHarassLocation(@NotNull ComputerPlayerPhase phase,
                                          int index,
                                          int opcode, int start,
                                          int owner,
                                          boolean expansion,
                                          int instructionLimit) {
        return true;
    }

    /**
     * Executes {@code implode}: needs a town, then implodes the owner's regions.
     *
     * @throws UnsupportedOperationException if the script record has no town
     */
    private boolean executeImplode(@NotNull ComputerPlayerPhase phase,
                                   int index,
                                   int opcode, int start,
                                   int owner,
                                   boolean expansion,
                                   int instructionLimit) {
        this.requireTown(index, opcode);
        phase.getRegions().implode(phase, owner);

        return true;
    }

    /**
     * Executes {@code guard_all}: activates the building region of every unit of the owner list.
     *
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private boolean executeGuardAll(@NotNull ComputerPlayerPhase phase,
                                    int index,
                                    int opcode, int start,
                                    int owner,
                                    boolean expansion,
                                    int instructionLimit) {
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            phase.activateBuildingRegion(owner, pool.getUnit(poolIndex));
        }

        return true;
    }

    /**
     * Executes {@code enemyresources_jump}: the first enemy in the unit list decides whose minerals and gas (signed DWORD
     * compares) are tested.
     *
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private boolean executeEnemyResourcesJump(@NotNull ComputerPlayerPhase phase,
                                              int index,
                                              int opcode, int start,
                                              int owner,
                                              boolean expansion,
                                              int instructionLimit) {
        int minerals = this.readWord(expansion, index);
        int gas = this.readWord(expansion, index);
        int target = this.readWord(expansion, index);
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (!this.game.getComputerUnits().isEnemy(owner, unit)) {
                continue;
            }

            Player enemy = this.game.getPlayerTable().requirePlayer(unit.getOwnerIndex());

            if (enemy.getMinerals() >= minerals && enemy.getGas() >= gas) {
                this.addresses[index] = target;
            }

            break;
        }

        return true;
    }

    /**
     * Executes {@code if_dif}: mode {@code 0} jumps below the value, other modes above it.
     */
    private boolean executeDifficultyJump(@NotNull ComputerPlayerPhase phase,
                                          int index,
                                          int opcode, int start,
                                          int owner,
                                          boolean expansion,
                                          int instructionLimit) {
        int mode = this.readByte(expansion, index);
        int value = this.readByte(expansion, index);
        int target = this.readWord(expansion, index);
        int difficulty = phase.getPlayer(owner).getDifficulty();

        if (mode == 0 ? difficulty < value : difficulty > value) {
            this.addresses[index] = target;
        }

        return true;
    }

    /**
     * Executes {@code easy_attack}: {@code attack_add} at difficulty {@code 0} only.
     */
    private boolean executeEasyAttack(@NotNull ComputerPlayerPhase phase,
                                      int index,
                                      int opcode, int start,
                                      int owner,
                                      boolean expansion,
                                      int instructionLimit) {
        int count = this.readByte(expansion, index);
        int unitType = this.readUnitType(expansion, index);

        if (phase.getPlayer(owner).getDifficulty() == 0) {
            phase.getPlayer(owner).addAttackUnits(count, unitType);
        }

        return true;
    }

    /**
     * Executes {@code kill_thread}: every active killable record is marked for release.
     */
    private boolean executeKillThread(@NotNull ComputerPlayerPhase phase,
                                      int index,
                                      int opcode, int start,
                                      int owner,
                                      boolean expansion,
                                      int instructionLimit) {
        for (int record = this.firstActive; record != NONE; record = this.next[record]) {
            if ((this.flags[record] & FLAG_KILLABLE) != 0) {
                this.flags[record] |= FLAG_RELEASE;
            }
        }

        return true;
    }

    /**
     * Executes {@code killable}: sets the killable flag.
     */
    private boolean executeKillable(@NotNull ComputerPlayerPhase phase,
                                    int index,
                                    int opcode, int start,
                                    int owner,
                                    boolean expansion,
                                    int instructionLimit) {
        this.flags[index] |= FLAG_KILLABLE;

        return true;
    }

    /**
     * Executes {@code wait_finishattack}: retries while the owner has an attack region.
     */
    private boolean executeWaitForAttackCompletion(@NotNull ComputerPlayerPhase phase,
                                                   int index,
                                                   int opcode, int start,
                                                   int owner,
                                                   boolean expansion,
                                                   int instructionLimit) {
        if (phase.getRegions().isAttacking(owner)) {
            this.retry(index, start);

            return false;
        }

        return true;
    }

    /**
     * Executes {@code quick_attack}: prepares the attack, then sets the attack delay.
     */
    private boolean executeQuickAttack(@NotNull ComputerPlayerPhase phase,
                                       int index,
                                       int opcode, int start,
                                       int owner,
                                       boolean expansion,
                                       int instructionLimit) {
        this.game.getComputerAttack().prepare(phase, owner, this.centerX[index], this.centerY[index], true, false);
        phase.getPlayer(owner).setAttackDelay(ATTACK_DELAY, this.game.getComputerTowns().getElapsedClockTicks());

        return true;
    }

    /**
     * Executes {@code junkyard_dog}: the owner's units in the rectangle get order JunkYardDog.
     */
    private boolean executeJunkyardDog(@NotNull ComputerPlayerPhase phase,
                                       int index,
                                       int opcode, int start,
                                       int owner,
                                       boolean expansion,
                                       int instructionLimit) {
        for (Unit unit : this.game.getComputerTerrain().collectRectangle(this.left[index], this.top[index], this.right[index], this.bottom[index])) {
            if (unit.getOwnerIndex() == owner) {
                this.game.getOrders().issue(unit, ScrOrderIds.JUNK_YARD_DOG, null, 0, 0, ScrUnitIds.NONE, false, instructionLimit);
            }
        }

        return true;
    }

    /**
     * Executes {@code disruption_web}: a Corsair with the technology's energy casts at the rectangle center.
     */
    private boolean executeDisruptionWeb(@NotNull ComputerPlayerPhase phase,
                                         int index,
                                         int opcode, int start,
                                         int owner,
                                         boolean expansion,
                                         int instructionLimit) {
        this.issueSpellOrderAtLocationCenter(index,
                                             ScrUnitIds.PROTOSS_CORSAIR,
                                             ScrTechIds.DISRUPTION_WEB,
                                             ScrOrderIds.CAST_DISRUPTION_WEB,
                                             instructionLimit);

        return true;
    }

    /**
     * Executes {@code recall_location}: an Arbiter with the technology's energy recalls at the rectangle center.
     */
    private boolean executeRecallLocation(@NotNull ComputerPlayerPhase phase,
                                          int index,
                                          int opcode, int start,
                                          int owner,
                                          boolean expansion,
                                          int instructionLimit) {
        this.issueSpellOrderAtLocationCenter(index, ScrUnitIds.PROTOSS_ARBITER, ScrTechIds.RECALL, ScrOrderIds.CAST_RECALL, instructionLimit);

        return true;
    }

    /**
     * Executes {@code set_randomseed}: a DWORD for the game seed.
     */
    private boolean executeSetRandomSeed(@NotNull ComputerPlayerPhase phase,
                                         int index,
                                         int opcode, int start,
                                         int owner,
                                         boolean expansion,
                                         int instructionLimit) {
        this.game.getGameContext().setGameSeed(this.readDword(expansion, index));

        return true;
    }

    /**
     * Executes {@code create_nuke}.
     */
    private boolean executeCreateNuke(@NotNull ComputerPlayerPhase phase,
                                      int index,
                                      int opcode, int start,
                                      int owner,
                                      boolean expansion,
                                      int instructionLimit) {
        this.createNuke(phase, owner, instructionLimit);

        return true;
    }

    /**
     * Executes {@code create_unit}: creates the unit at the WORD position, cloaked when its cloaking technology is usable.
     */
    private boolean executeCreateUnit(@NotNull ComputerPlayerPhase phase,
                                      int index,
                                      int opcode, int start,
                                      int owner,
                                      boolean expansion,
                                      int instructionLimit) {
        int unitType = this.readUnitType(expansion, index);
        int positionX = this.readWord(expansion, index);
        int positionY = this.readWord(expansion, index);
        Unit unit = this.game.getTriggers().createUnit(unitType, positionX, positionY, owner, instructionLimit);

        if (unit != null) {
            int technology = UnitQueries.getCloakTechnology(this.game.requireGameData().getUnitTypes(), unit);

            if (technology != ScrTechIds.NONE && this.game.getUnitTechnology().canUseTechnology(unit, technology)) {
                this.game.getUnitCloaking().cloakPreplaced(unit);
            }
        }

        return true;
    }

    /**
     * Executes {@code help_iftrouble}.
     */
    private boolean executeHelpAlliesIfInTrouble(@NotNull ComputerPlayerPhase phase,
                                                 int index,
                                                 int opcode, int start,
                                                 int owner,
                                                 boolean expansion,
                                                 int instructionLimit) {
        phase.getRegions().helpAllies(phase, owner);

        return true;
    }

    /**
     * Executes {@code allies_watch}: claims the resource area and starts an expansion record there.
     */
    private boolean executeAlliesWatch(@NotNull ComputerPlayerPhase phase,
                                       int index,
                                       int opcode, int start,
                                       int owner,
                                       boolean expansion,
                                       int instructionLimit) {
        int area = this.readByte(expansion, index);
        int address = this.readWord(expansion, index);

        if (this.claimArea(area)) {
            this.startExpansion(phase, owner, area, address, this.flags[index] & FLAG_EXPANSION);
        }

        return true;
    }

    /**
     * Executes {@code try_townpoint}: jumps when the town count equals the BYTE.
     */
    private boolean executeTownCountJump(@NotNull ComputerPlayerPhase phase,
                                         int index,
                                         int opcode, int start,
                                         int owner,
                                         boolean expansion,
                                         int instructionLimit) {
        int count = this.readByte(expansion, index);
        int target = this.readWord(expansion, index);

        if (phase.countTowns(owner) == count) {
            this.addresses[index] = target;
        }

        return true;
    }

    /**
     * Applies the retry path of opcodes without operands: the opcode runs again after {@value #RETRY_WAIT} frames.
     */
    private void retry(int index, int start) {
        this.addresses[index] = start;
        this.waits[index]     = RETRY_WAIT;
    }

    /**
     * Returns the first unit of the owner list of the type whose energy BYTE reaches the value and whose order is
     * ComputerAI, GuardPost or Guard.
     *
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private @Nullable Unit findCaster(int owner, int type, int energy) {
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (this.game.requireGameData().getBaseType(unit) != type || (unit.getEnergy() >> Byte.SIZE & BYTE_MASK) < energy) {
                continue;
            }

            int order = unit.getPrimaryOrder();

            if (order == ScrOrderIds.COMPUTER_AI || order == ScrOrderIds.GUARD_POST || order == ScrOrderIds.GUARD) {
                return unit;
            }
        }

        return null;
    }

    /**
     * Applies {@code create_nuke}: the first Nuclear Silo of the owner without a ready missile receives a Nuclear
     * Missile created at it with its creation tag, completed, hidden, attached and registered with the Silo's town.
     *
     * @throws UnsupportedOperationException if the unit accounting is missing or the game has no unit creation context, or the unit pool is not
     *                                       initialized
     * @throws IllegalStateException         if the chosen Nuclear Silo has no sprite
     */
    private void createNuke(@NotNull ComputerPlayerPhase phase, int owner, int instructionLimit) {
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        Unit silo = null;

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (this.game.requireGameData().getBaseType(unit) == ScrUnitIds.TERRAN_NUCLEAR_SILO && unit.getSiloReadyDword() == 0) {
                silo = unit;

                break;
            }
        }

        if (silo == null) {
            return;
        }

        UnitAccounting accounting = this.game.getPlayerTable().requireUnitAccounting();
        UnitCreationContext creation = this.game.getUnitSystem().getUnitCreationContext();

        if (creation == null) {
            throw Exceptions.newUnsupportedOperationException("Computer script nukes require the unit creation context.");
        }

        Sprite sprite = silo.requireSprite();
        Unit missile = this.game.getUnitStorageConstructor().create(creation,
                                                                    accounting,
                                                                    ScrUnitIds.TERRAN_NUCLEAR_MISSILE,
                                                                    sprite.getX(), sprite.getY(),
                                                                    silo.getOwnerIndex(),
                                                                    pool.getCreationTag(silo.getIndex()),
                                                                    instructionLimit);

        if (missile == null) {
            return;
        }

        Player player = this.game.getPlayerTable().requirePlayer(silo.getOwnerIndex());

        this.game.getUnitCompletion().initializeBeforePlacement(creation, missile, instructionLimit);
        this.game.getUnitCompletion().completeAfterPlacement(creation, missile, player.getType(), instructionLimit);
        this.game.getUnitLifecycle().hide(missile, true);
        silo.setSiloNuke(missile);
        silo.setSiloReady(true);
        missile.setParentUnit(silo);
        phase.registerCompletedUnit(silo, missile, instructionLimit);
    }

    /**
     * Claims a resource area: an area {@code 1..249} with an occupancy flag is refused; otherwise it is marked as a
     * computer expansion.
     *
     * @return {@code true} if the area was claimed, {@code false} otherwise
     * @throws UnsupportedOperationException if the game has no resource areas or the area is above the last one
     */
    private boolean claimArea(int area) {
        ResourceAreas areas = this.game.getResourceAreas();

        if (areas == null) {
            throw Exceptions.newUnsupportedOperationException("Computer area claims require the resource areas.");
        }

        if (area >= 1 && area <= ResourceAreas.LAST_AREA && (areas.getOccupancyFlags(area) & OCCUPIED_AREA_FLAGS) != 0) {
            return false;
        }

        if (area > ResourceAreas.LAST_AREA) {
            throw Exceptions.newUnsupportedOperationException("Computer area claim is outside the resource-area records: {0}", area);
        }

        areas.markExpansion(area);

        return true;
    }

    /**
     * Returns the all-units or completed count of a type, counting Siege Tanks in both modes together.
     *
     * @throws UnsupportedOperationException if the unit accounting is missing
     */
    private int countUnits(int unitType, int owner, boolean completed) {
        ComputerUnits computerUnits = this.game.getComputerUnits();
        int count = completed ? computerUnits.getCompletedTypeCount(unitType, owner) : computerUnits.getTypeCount(unitType, owner);
        int other = unitType == ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE
                    ? ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE
                    : unitType == ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE ? ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE : -1;

        if (other != -1) {
            count += completed ? computerUnits.getCompletedTypeCount(other, owner) : computerUnits.getTypeCount(other, owner);
        }

        return count;
    }

    /**
     * Applies {@code multirun}: a new record at the free-list head runs from the target address with the location and
     * directory selector of the starting record.
     *
     * @return the new record index, or {@code -1} when the pool is exhausted
     */
    private int startThread(int address, int owner, int source) {
        if (this.firstFree == NONE) {
            return NONE;
        }

        int index = this.activateFreeRecord();

        this.addresses[index] = address;
        this.waits[index]     = 0;
        this.owners[index]    = owner;
        this.flags[index]     = this.flags[index] & FLAGS_KEPT_ON_START | this.flags[source] & FLAG_EXPANSION;
        this.left[index]      = this.left[source];
        this.top[index]       = this.top[source];
        this.right[index]     = this.right[source];
        this.bottom[index]    = this.bottom[source];
        this.centerX[index]   = (this.right[source] - this.left[source]) / LOCATION_CENTER_DIVISOR + this.left[source];
        this.centerY[index]   = (this.bottom[source] - this.top[source]) / LOCATION_CENTER_DIVISOR + this.top[source];
        this.towns[index]     = null;

        return index;
    }

    /**
     * Applies {@code expand}: unless a melee owner already holds {@value #EXPAND_MINERAL_RESERVE} minerals and
     * {@value #EXPAND_GAS_RESERVE} gas, selects an expansion area from the town's area and starts a script record there.
     *
     * @param phase        the owning phase
     * @param owner        the owner
     * @param town         the requesting town; required when selecting an expansion
     * @param address      the program address of the new record
     * @param count        the town-count argument of the expansion area selection
     * @param expansionBit the expansion flag bit of the requesting record
     * @throws UnsupportedOperationException if the expansion has no town
     */
    void expand(@NotNull ComputerPlayerPhase phase, int owner, @Nullable ComputerTown town, int address, int count, int expansionBit) {
        Player player = this.game.getPlayerTable().requirePlayer(owner);

        if (player.getMinerals() >= EXPAND_MINERAL_RESERVE && player.getGas() >= EXPAND_GAS_RESERVE && !phase.getPlayer(owner).isCampaign()) {
            return;
        }

        if (town == null) {
            throw Exceptions.newUnsupportedOperationException("Computer script expansion requires a town.");
        }

        int source = town.resourceArea;

        if (source == 0) {
            return;
        }

        int area = this.game.getComputerTowns().selectExpansionArea(phase, owner, source, count);

        if (area == 0) {
            return;
        }

        this.startExpansion(phase, owner, area, address, expansionBit);
    }

    /**
     * Takes the free-list head as the new active head at the area position, with the area in the flag bits above
     * {@link #FLAG_BITS}, then activates the area's region without defense line and, outside campaigns, marks the chokes
     * toward the enemies.
     *
     * @throws UnsupportedOperationException if the resource areas are not initialized
     */
    private void startExpansion(@NotNull ComputerPlayerPhase phase, int owner, int area, int address, int expansionBit) {
        int positionX = area >= 1 && area <= ResourceAreas.LAST_AREA ? (short) this.game.requireResourceAreas().getHorizontalPosition(area) : 0;
        int positionY = area >= 1 && area <= ResourceAreas.LAST_AREA ? (short) this.game.requireResourceAreas().getVerticalPosition(area) : 0;

        if (this.firstFree == NONE) {
            return;
        }

        int index = this.activateFreeRecord();

        this.flags[index]     = (this.flags[index] & FLAGS_KEPT_ON_START | expansionBit & FLAG_EXPANSION) & FLAG_BITS | area << RESOURCE_AREA_SHIFT;
        this.waits[index]     = 0;
        this.left[index]      = positionX - 1;
        this.top[index]       = positionY - 1;
        this.right[index]     = positionX + 1;
        this.bottom[index]    = positionY + 1;
        this.addresses[index] = address;
        this.owners[index]    = owner;
        this.centerX[index]   = positionX;
        this.centerY[index]   = positionY;
        this.towns[index]     = null;

        phase.getRegions().activate(phase, owner, this.game.getComputerTerrain().getRegion((short) positionX, (short) positionY), false);

        if (!phase.getPlayer(owner).isCampaign()) {
            this.game.getComputerTerrain().defendTownChokes(phase, owner, positionX, positionY);
        }
    }

    /**
     * Releases every active record bound to the town.
     *
     * @param town the released town
     */
    void releaseTown(@NotNull ComputerTown town) {
        int index = this.firstActive;

        while (this.firstActive != NONE && index != NONE) {
            int following = this.next[index];

            if (this.towns[index] == town) {
                this.release(index);
            }

            index = following;
        }
    }

    private @NotNull ComputerTown requireTown(int index, int opcode) {
        ComputerTown town = this.towns[index];

        if (town == null) {
            throw Exceptions.newUnsupportedOperationException("Computer script opcode requires a town: {0}", opcode);
        }

        return town;
    }

    private int readByte(boolean expansion, int index) {
        int address = this.addresses[index];
        int value = this.program.readByte(expansion, address);

        this.program.readByte(expansion, address + 1);

        this.addresses[index] = address + 1;

        return value;
    }

    private int readWord(boolean expansion, int index) {
        int address = this.addresses[index];
        int value = this.program.readByte(expansion, address) | this.program.readByte(expansion, address + 1) << Byte.SIZE;

        this.program.readByte(expansion, address + Short.BYTES);

        this.addresses[index] = address + Short.BYTES;

        return value;
    }

    private int readDword(boolean expansion, int index) {
        int address = this.addresses[index];
        int value = this.program.readByte(expansion, address) |
                    this.program.readByte(expansion, address + 1) << Byte.SIZE |
                    this.program.readByte(expansion, address + Short.BYTES) << Short.SIZE |
                    this.program.readByte(expansion, address + Short.BYTES + 1) << LAST_BYTE_SHIFT;

        this.program.readByte(expansion, address + Integer.BYTES);

        this.addresses[index] = address + Integer.BYTES;

        return value;
    }

    private int readUnitType(boolean expansion, int index) {
        int unitType = this.readWord(expansion, index);

        if (unitType >= ScrUnitIds.NONE) {
            throw Exceptions.newUnsupportedOperationException("Computer script unit type is outside its type table: {0}", unitType);
        }

        return unitType;
    }

    /**
     * Writes the script pool: clears the fields from the program counter on, the town and the flags of every free record,
     * then writes the {@code 100} saved records (next and previous one-based, program counter, wait, owner, rectangle,
     * center, one-based town, flags) and the one-based free head DWORD into the record section, and the one-based first
     * active record into the second DWORD of the head section. The shared return address is not saved.
     *
     * @param records the record section, little-endian
     * @param head    the list head section, little-endian
     */
    void writeSaveRecords(@NotNull ByteBuffer records, @NotNull ByteBuffer head) {
        for (int index = this.firstFree; index != NONE; index = this.next[index]) {
            this.addresses[index] = 0;
            this.waits[index]     = 0;
            this.owners[index]    = 0;
            this.left[index]      = 0;
            this.top[index]       = 0;
            this.right[index]     = 0;
            this.bottom[index]    = 0;
            this.centerX[index]   = 0;
            this.centerY[index]   = 0;
            this.towns[index]     = null;
            this.flags[index]     = 0;
        }

        for (int index = 0; index < RECORD_COUNT; index++) {
            int offset = index * SAVED_RECORD_SIZE;

            records.putInt(offset + SAVED_NEXT, this.next[index] + 1);
            records.putInt(offset + SAVED_PREVIOUS, this.previous[index] + 1);
            records.putInt(offset + SAVED_ADDRESS, this.addresses[index]);
            records.putInt(offset + SAVED_WAIT, this.waits[index]);
            records.putInt(offset + SAVED_OWNER, this.owners[index]);
            records.putInt(offset + SAVED_LEFT, this.left[index]);
            records.putInt(offset + SAVED_TOP, this.top[index]);
            records.putInt(offset + SAVED_RIGHT, this.right[index]);
            records.putInt(offset + SAVED_BOTTOM, this.bottom[index]);
            records.putInt(offset + SAVED_CENTER_X, this.centerX[index]);
            records.putInt(offset + SAVED_CENTER_Y, this.centerY[index]);
            records.putInt(offset + SAVED_TOWN, ComputerTown.encodeSaveLink(this.towns[index]));
            records.putInt(offset + SAVED_FLAGS, this.flags[index]);
        }

        records.putInt(RECORD_COUNT * SAVED_RECORD_SIZE, this.firstFree + 1);

        ComputerPlayerPhase.clearSaveRange(head, SAVED_HEAD_SIZE);

        head.putInt(Integer.BYTES, this.firstActive + 1);
    }

    /**
     * Reads the script pool that {@link #writeSaveRecords} wrote; the shared return address keeps its value.
     *
     * @param records the record section, little-endian
     * @param head    the list head section, little-endian
     * @param towns   the town pool
     */
    void readSaveRecords(@NotNull ByteBuffer records, @NotNull ByteBuffer head, @NotNull ComputerTown @NotNull [] towns) {
        for (int index = 0; index < RECORD_COUNT; index++) {
            int offset = index * SAVED_RECORD_SIZE;

            this.next[index]      = decodeSaveLink(records.getInt(offset + SAVED_NEXT));
            this.previous[index]  = decodeSaveLink(records.getInt(offset + SAVED_PREVIOUS));
            this.addresses[index] = records.getInt(offset + SAVED_ADDRESS);
            this.waits[index]     = records.getInt(offset + SAVED_WAIT);
            this.owners[index]    = records.getInt(offset + SAVED_OWNER);
            this.left[index]      = records.getInt(offset + SAVED_LEFT);
            this.top[index]       = records.getInt(offset + SAVED_TOP);
            this.right[index]     = records.getInt(offset + SAVED_RIGHT);
            this.bottom[index]    = records.getInt(offset + SAVED_BOTTOM);
            this.centerX[index]   = records.getInt(offset + SAVED_CENTER_X);
            this.centerY[index]   = records.getInt(offset + SAVED_CENTER_Y);
            this.towns[index]     = ComputerTown.decodeSaveLink(towns, records.getInt(offset + SAVED_TOWN));
            this.flags[index]     = records.getInt(offset + SAVED_FLAGS);
        }

        this.firstFree   = decodeSaveLink(records.getInt(RECORD_COUNT * SAVED_RECORD_SIZE));
        this.firstActive = decodeSaveLink(head.getInt(Integer.BYTES));
    }

    /**
     * Includes the record pool and the shared return address in the state hash.
     *
     * @param hash the preceding diagnostic hash
     * @return the hash including this pool
     */
    int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + this.firstFree;
        hash = HASH_MULTIPLIER * hash + this.callReturn;
        hash = HASH_MULTIPLIER * hash + this.firstActive;

        for (int index = 0; index < RECORD_COUNT; index++) {
            hash = HASH_MULTIPLIER * hash + this.next[index];
            hash = HASH_MULTIPLIER * hash + this.previous[index];
            hash = HASH_MULTIPLIER * hash + this.addresses[index];
            hash = HASH_MULTIPLIER * hash + this.waits[index];
            hash = HASH_MULTIPLIER * hash + this.owners[index];
            hash = HASH_MULTIPLIER * hash + this.flags[index];
            hash = HASH_MULTIPLIER * hash + this.left[index];
            hash = HASH_MULTIPLIER * hash + this.top[index];
            hash = HASH_MULTIPLIER * hash + this.right[index];
            hash = HASH_MULTIPLIER * hash + this.bottom[index];
            hash = HASH_MULTIPLIER * hash + this.centerX[index];
            hash = HASH_MULTIPLIER * hash + this.centerY[index];
            hash = HASH_MULTIPLIER * hash + (this.towns[index] == null ? -1 : this.towns[index].getPoolIndex());
        }

        return hash;
    }
}
