package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.engine.trigger.TriggerRegistry;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicRegion;
import org.jetbrains.annotations.NotNull;

/**
 * Runs a trigger record that lies inside another region's memory: over its bytes in place when the run cannot write
 * them, else on an execution over its decode ({@link DecodedRecordCache}) whose writes reach the region through a
 * {@link RunningRecordView}.
 */
final class RegionRecordRunnerUtils {
    /**
     * {@link #executeActionInPlace} results: the action ran, was skipped as disabled, ended the list or preserved the trigger.
     */
    private static final int ACTION_RAN = 0;
    private static final int ACTION_SKIPPED = 1;
    private static final int ACTION_END = 2;
    private static final int ACTION_PRESERVE = 3;
    /**
     * The first action of {@link #executeDecoded} that runs the whole record.
     */
    private static final int WHOLE_RECORD = -1;
    /**
     * The changed actions of {@link DecodedRecordCache.DecodedRecord#update} after a run that wrote the record: all.
     */
    private static final long ALL_ACTIONS = -1L;

    /**
     * The memory of a record that runs in place inside another region: the running execution serves its bytes (the
     * execution flags, the cursor and the started bits from its state), and a write reaches both the region and the
     * execution, so the rest of the run sees it.
     */
    static final class RunningRecordView implements ClassicRegion {
        private final @NotNull RunningTrigger record;

        private @NotNull ClassicRegion region;
        private int offset;

        /**
         * Creates a {@link RunningRecordView} instance.
         *
         * @param record the running execution of the record
         * @param region the region holding the record
         * @param offset the record offset in the region
         */
        RunningRecordView(@NotNull RunningTrigger record, @NotNull ClassicRegion region, int offset) {
            this.record = record;

            this.region = region;
            this.offset = offset;
        }

        /**
         * @return the classic base address
         */
        @Override
        public int getBase() {
            return this.region.getBase() + this.offset;
        }

        /**
         * @return the size in bytes
         */
        @Override
        public int getSize() {
            return TriggerRecordCodec.RECORD_SIZE;
        }


        /**
         * Shows its execution at another record.
         *
         * @param region the region holding the record
         * @param offset the record offset in the region
         */
        void reset(@NotNull ClassicRegion region, int offset) {
            this.region = region;
            this.offset = offset;
        }

        /**
         * Reads the unsigned byte at an offset.
         *
         * @param offset the byte offset, below {@link #getSize()}
         * @return the unsigned byte at the offset
         */
        @Override
        public int readByte(int offset) {
            return this.record.readByte(offset);
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
            this.region.writeByte(this.offset + offset, value);
            this.record.writeByte(offset, value);
        }

        /**
         * Stores a little-endian DWORD as four {@link #writeByte} calls in address order do.
         *
         * @param offset the byte offset of the DWORD, with four bytes inside the region
         * @param value  the DWORD
         */
        @Override
        public void writeDword(int offset, int value) {
            for (int index = 0; index < Integer.BYTES; index++) {
                this.region.writeByte(this.offset + offset + index, value >>> (index * Byte.SIZE));
            }

            this.record.writeDword(offset, value);
        }
    }

    /**
     * Runs a record that lies inside another region's memory as {@link ScrTriggerSystem#runRecord} runs an execution. In plain
     * memory ({@link ClassicRegion#isPlainMemory}) the run reads the record's bytes in the region: a disabled record is
     * skipped, a record with running actions resumes them, otherwise the conditions are tested
     * ({@link TriggerConditions#testConditionsInPlace}; they do not write the record) and the actions run from the first.
     * Actions that cannot write the record's bytes run over them too ({@link #executeInPlace}); any others, and every
     * record in other memory, run on an execution over the record's decode ({@link #executeDecoded}).
     *
     * @param context     the trigger context
     * @param region      the region holding the record
     * @param offset      the record offset in the region
     * @param player      the player whose walk runs the record
     * @param recordIndex the position of the record in the walk
     */
    static void runRegionRecord(@NotNull TriggerContext context, @NotNull ClassicRegion region, int offset, int player, int recordIndex) {
        if (context.executionCounter != null) {
            context.executionCounter.record();
        }

        if (!region.isPlainMemory()) {
            executeDecoded(context, region, offset, player, recordIndex, WHOLE_RECORD);

            return;
        }

        int flags = region.readDword(offset + TriggerRecordCodec.FLAGS_OFFSET);

        if ((flags & TriggerFlags.EXECUTION_DISABLED) != 0) {
            return;
        }

        int cursor = region.readByte(offset + TriggerRecordCodec.ACTION_INDEX_OFFSET);
        boolean resuming = (flags & TriggerFlags.EXECUTION_ACTIONS_RUNNING) != 0;

        context.currentRecordPlayer      = player;
        context.currentRecordIndex       = recordIndex;
        context.currentRecordInitialized = true;

        if (!resuming && !TriggerConditions.testConditionsInPlace(context, region, offset)) {
            return;
        }

        int first = resuming ? cursor : 0;

        if ((flags & TriggerFlags.EXECUTION_PAUSED_GAME) == 0 && canRunInPlace(context, region, offset, first)) {
            executeInPlace(context, region, offset, flags, cursor, first);
        } else {
            executeDecoded(context, region, offset, player, recordIndex, first);
        }
    }

    /**
     * Runs a record on an execution over the record's decode, the whole record as {@link ScrTriggerSystem#runRecord} or its
     * actions from the first as {@link TriggerActionsUtils#executeActions}: the execution state (execution flags, cursor,
     * started bits) that changed is stored back. The execution runs over the bytes of the record's last decode, so a write
     * into the running record keeps that decode current.
     *
     * @param context     the trigger context
     * @param region      the region holding the record
     * @param offset      the record offset in the region
     * @param player      the player whose walk runs the record
     * @param recordIndex the position of the record in the walk
     * @param first       the first action to run, or {@code -1} to run the whole record
     */
    static void executeDecoded(@NotNull TriggerContext context, @NotNull ClassicRegion region, int offset, int player, int recordIndex, int first) {
        DecodedRecordCache.DecodedRecord decoded = context.decodedRecords.findCurrent(region, offset);
        // One execution and view serve every record run in place (records do not run inside each other); a run while the
        // pair is taken gets its own.
        RunningTrigger record = context.regionRecord;
        RunningRecordView view = context.regionRecordView;

        context.regionRecord     = null;
        context.regionRecordView = null;

        if (record == null) {
            record = new RunningTrigger(decoded.getDefinition(), decoded.executionFlags, decoded.currentActionIndex, decoded.startedActions);
            view   = new RunningRecordView(record, region, offset);
        } else {
            record.reset(decoded.getDefinition(), decoded.executionFlags, decoded.currentActionIndex, decoded.startedActions);
            view.reset(region, offset);
        }

        record.setRecordBytes(decoded.bytes);

        int flags = record.executionFlags;
        int cursor = record.currentActionIndex;
        long started = record.startedActions;
        ClassicAddressSpace space = (ClassicAddressSpace) ExtendedDeathsUtils.getExtendedDeathsOperations(context);

        space.setOverlay(view);

        try {
            if (first < 0) {
                ScrTriggerSystem.runRecord(context, record, player, recordIndex, null);
            } else {
                record.currentActionIndex = first;

                TriggerActionsUtils.executeActions(context, record);
            }
        } finally {
            space.setOverlay(null);

            context.regionRecord     = record;
            context.regionRecordView = view;
        }

        if (record.isRecordBytesWritten()) {
            decoded.update(record.definition, ALL_ACTIONS);
        }

        storeExecutionState(region, offset, flags, record.executionFlags, cursor, record.currentActionIndex);

        for (long changed = record.startedActions ^ started; changed != 0; changed &= changed - 1) {
            int action = Long.numberOfTrailingZeros(changed);
            int position = offset + TriggerRecordCodec.ACTIONS_OFFSET + action * TriggerRecordCodec.ACTION_SIZE +
                           TriggerRecordCodec.ACTION_FLAGS_FIELD;
            int value = region.readByte(position);

            region.writeByte(position, (record.startedActions & 1L << action) != 0 ? value | TriggerFlags.ACTION_STARTED
                                                                                   : value & ~TriggerFlags.ACTION_STARTED);
        }
    }

    /**
     * Stores the execution flags DWORD and the action cursor of a record run in place when they changed during the run.
     *
     * @param region         the region holding the record
     * @param offset         the record offset in the region
     * @param previousFlags  the execution flags before the run
     * @param flags          the execution flags after the run
     * @param previousCursor the action cursor before the run
     * @param cursor         the action cursor after the run
     */
    private static void storeExecutionState(@NotNull ClassicRegion region, int offset, int previousFlags, int flags, int previousCursor, int cursor) {
        if (flags != previousFlags) {
            for (int index = 0; index < Integer.BYTES; index++) {
                region.writeByte(offset + TriggerRecordCodec.FLAGS_OFFSET + index, flags >>> (index * Byte.SIZE));
            }
        }

        if (cursor != previousCursor) {
            region.writeByte(offset + TriggerRecordCodec.ACTION_INDEX_OFFSET, cursor);
        }
    }

    /**
     * Runs the actions of a record from the first over its own bytes in the region, as
     * {@link TriggerActionsUtils#executeActions} runs an execution, when {@link #canRunInPlace} admits them: such a run writes no
     * byte of the record until its execution state is stored at the end, and reads none of the bytes that state changes.
     *
     * @param context        the trigger context
     * @param region         the region holding the record
     * @param offset         the record offset in the region
     * @param previousFlags  the execution flags DWORD before the run
     * @param previousCursor the action cursor before the run
     * @param first          the first action to run
     */
    private static void executeInPlace(@NotNull TriggerContext context,
                                       @NotNull ClassicRegion region, int offset,
                                       int previousFlags,
                                       int previousCursor,
                                       int first) {
        int flags = TriggerActionsUtils.startActions(context, previousFlags);
        boolean completed = true;
        int cursor = first;

        for (; cursor < TriggerRecordCodec.ACTION_COUNT; cursor++) {
            int result = executeActionInPlace(context, region, offset + TriggerRecordCodec.ACTIONS_OFFSET + cursor * TriggerRecordCodec.ACTION_SIZE);

            if (context.executionCounter != null && (result == ACTION_RAN || result == ACTION_PRESERVE)) {
                context.executionCounter.action();
            }

            if (result == ACTION_END) {
                cursor = TriggerRecordCodec.ACTION_COUNT;

                break;
            }

            if (result == ACTION_PRESERVE) {
                flags |= TriggerFlags.EXECUTION_PRESERVE;
            }

            if (result != ACTION_SKIPPED && context.isEvaluationStopped()) {
                completed = false;

                break;
            }
        }

        if (completed) {
            cursor = (flags & TriggerFlags.EXECUTION_PRESERVE) != 0 ? 0 : cursor;
            flags  = (flags & TriggerFlags.EXECUTION_PRESERVE) != 0
                     ? flags & ~TriggerFlags.EXECUTION_CLEARED_ON_REWIND
                     : flags | TriggerFlags.EXECUTION_DISABLED;
        }

        storeExecutionState(region, offset, previousFlags, flags, previousCursor, cursor);
    }

    /**
     * Tests whether the enabled actions from the first up to the first enabled No Action can run over the record's bytes
     * ({@link #executeInPlace}): Set Deaths that cannot write the record's bytes for any player their selector may name,
     * Preserve Trigger or types without a handler, while the context's action registry holds the default Set Deaths and
     * Preserve Trigger actions that this run reproduces.
     *
     * @param context the trigger context
     * @param region  the region holding the record
     * @param offset  the record offset in the region
     * @param first   the first action the run executes
     * @return {@code true} if the actions can run in place, {@code false} otherwise
     */
    private static boolean canRunInPlace(@NotNull TriggerContext context, @NotNull ClassicRegion region, int offset, int first) {
        TriggerRegistry<ScrTriggerAction> actions = context.getActionRegistry();

        if (actions.find(TriggerActionIds.SET_DEATHS) != TriggerActionsUtils.SET_DEATHS ||
            actions.find(TriggerActionIds.PRESERVE_TRIGGER) != TriggerActionsUtils.PRESERVE_TRIGGER ||
            actions.getCount() != TriggerActionIds.FIRST_WITHOUT_HANDLER) {
            return false;
        }

        for (int index = first; index < TriggerRecordCodec.ACTION_COUNT; index++) {
            int action = offset + TriggerRecordCodec.ACTIONS_OFFSET + index * TriggerRecordCodec.ACTION_SIZE;

            if ((region.readByte(action + TriggerRecordCodec.ACTION_FLAGS_FIELD) & TriggerFlags.ACTION_DISABLED) != 0) {
                continue;
            }

            int type = region.readByte(action + TriggerRecordCodec.ACTION_TYPE_FIELD);

            if (type == TriggerActionIds.NO_ACTION) {
                return true;
            }

            if (type == TriggerActionIds.SET_DEATHS) {
                if (ExtendedDeathsUtils.mayWriteRecord(region.getBase() + offset,
                                                       region.readDword(action + TriggerRecordCodec.ACTION_PLAYER_FIELD),
                                                       readWord(region, action + TriggerRecordCodec.ACTION_UNIT_FIELD))) {
                    return false;
                }
            } else if (type != TriggerActionIds.PRESERVE_TRIGGER && type < TriggerActionIds.FIRST_WITHOUT_HANDLER) {
                return false;
            }
        }

        return true;
    }

    /**
     * Runs one action of a record over its bytes in the region, as {@code TriggerActionsUtils.executeAction}, for the types
     * {@link #canRunInPlace} admits.
     *
     * @param context the trigger context
     * @param region  the region holding the record
     * @param action  the action offset in the region
     * @return {@link #ACTION_RAN}, {@link #ACTION_SKIPPED}, {@link #ACTION_END} or {@link #ACTION_PRESERVE}
     */
    private static int executeActionInPlace(@NotNull TriggerContext context, @NotNull ClassicRegion region, int action) {
        if ((region.readByte(action + TriggerRecordCodec.ACTION_FLAGS_FIELD) & TriggerFlags.ACTION_DISABLED) != 0) {
            return ACTION_SKIPPED;
        }

        int type = region.readByte(action + TriggerRecordCodec.ACTION_TYPE_FIELD);

        if (type == TriggerActionIds.NO_ACTION) {
            return ACTION_END;
        }

        if (type == TriggerActionIds.SET_DEATHS) {
            boolean masked = readWord(region, action + TriggerRecordCodec.ACTION_MARKER_FIELD) == TriggerRecordCodec.MASK_MARKER;

            ExtendedDeathsUtils.setDeaths(context,
                                          region.readDword(action + TriggerRecordCodec.ACTION_PLAYER_FIELD),
                                          readWord(region, action + TriggerRecordCodec.ACTION_UNIT_FIELD),
                                          region.readByte(action + TriggerRecordCodec.ACTION_MODIFIER_FIELD),
                                          region.readDword(action + TriggerRecordCodec.ACTION_SECONDARY_FIELD),
                                          masked ? region.readDword(action + TriggerRecordCodec.ACTION_LOCATION_FIELD) : -1, masked);

            return ACTION_RAN;
        }

        return type == TriggerActionIds.PRESERVE_TRIGGER ? ACTION_PRESERVE : ACTION_RAN;
    }

    /**
     * Reads a little-endian WORD of a region.
     *
     * @param region the region
     * @param offset the region offset
     * @return the unsigned WORD at the offset
     */
    static int readWord(@NotNull ClassicRegion region, int offset) {
        return region.readByte(offset) | region.readByte(offset + 1) << Byte.SIZE;
    }


    private RegionRecordRunnerUtils() {
    }
}
