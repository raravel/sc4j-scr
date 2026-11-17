package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts the trigger work of each frame: walked trigger records and executed actions. With the trace of a {@code [main] debug} build it
 * also attributes every action to the epScript statement running when it ran, by watching the writes into the trace stack.
 */
final class Profiler implements TriggerContext.ExecutionCounter, ClassicAddressSpace.WriteWatch {
    private static final int STACK_SLOTS = 2048;
    private static final int TOP_LINES = 15;
    private static final int TOP_FUNCTIONS = 10;

    private final @Nullable TraceReader trace;
    private final int[] slots = new int[STACK_SLOTS];
    private final Map<Integer, long[]> actionsByStatement = new HashMap<>();
    private int stackAddress;
    private @Nullable ClassicAddressSpace watchedSpace;
    private int current;
    private long frameActions;
    private long frameRecords;
    private long totalActions;
    private long totalRecords;
    private long maxActions = -1;
    private long maxRecords;
    private int peakFrame;
    private int frames;

    Profiler(@Nullable TraceReader trace) {
        this.trace = trace;
    }

    /**
     * Attaches to a started game.
     *
     * @param game the game
     */
    void attach(@NotNull Game game) {
        TriggerContext context = game.getTriggerContext();

        if (context != null) {
            context.setExecutionCounter(this);
        }
    }

    @Override
    public void record() {
        this.frameRecords++;
    }

    @Override
    public void action() {
        this.frameActions++;

        if (this.current != 0) {
            this.actionsByStatement.computeIfAbsent(this.current, ignored -> new long[1])[0]++;
        }
    }

    @Override
    public void written(int address, int value) {
        int depth = (address - this.stackAddress) / Integer.BYTES;

        this.slots[depth] = value;

        if (value != 0) {
            this.current = value;

            return;
        }

        // Leaving a call clears its DWORD: the statement of the nearest outer depth runs again.
        this.current = 0;

        for (int outer = depth - 1; outer >= 0; outer--) {
            if (this.slots[outer] != 0) {
                this.current = this.slots[outer];
                break;
            }
        }
    }

    /**
     * Ends a frame: records its counts, and watches the trace stack of the live address space once the map has written its header.
     *
     * @param game  the game
     * @param frame the frame that ended
     */
    void endFrame(@NotNull Game game, int frame) {
        this.frames++;
        this.totalActions += this.frameActions;
        this.totalRecords += this.frameRecords;

        if (this.frameActions > this.maxActions) {
            this.maxActions = this.frameActions;
            this.peakFrame  = frame;
        }

        this.maxRecords   = Math.max(this.maxRecords, this.frameRecords);
        this.frameActions = 0;
        this.frameRecords = 0;

        if (this.trace == null) {
            return;
        }

        ClassicAddressSpace live = game.getTriggerContext() == null ? null : game.getTriggerContext().getClassicAddressSpace();

        if (live == null || live == this.watchedSpace) {
            return;
        }

        int address = this.trace.findStackAddress(live);

        if (address != 0) {
            this.stackAddress = address;
            live.setWriteWatch(address, STACK_SLOTS * Integer.BYTES, this);
            this.watchedSpace = live;
        }
    }

    /**
     * @return the profile section of the report
     */
    @NotNull Map<String, Object> report() {
        // The work of a frame that did not end (a failure or a hang inside it) counts in the totals.
        long actions = this.totalActions + this.frameActions;
        long records = this.totalRecords + this.frameRecords;
        Map<String, Object> perFrame = RunnerMain.ordered(
            "avgActions", this.frames == 0 ? 0 : this.totalActions / this.frames,
            "maxActions", Math.max(this.maxActions, 0),
            "peakFrame", this.peakFrame,
            "avgTriggers", this.frames == 0 ? 0 : this.totalRecords / this.frames,
            "maxTriggers", this.maxRecords);
        Map<String, Object> report = RunnerMain.ordered("frames", this.frames, "totalActions", actions,
                                                        "totalTriggers", records, "perFrame", perFrame);

        if (this.trace == null) {
            report.put("note", "Without the trace of a debug build the actions are not attributed to statements.");

            return report;
        }

        Map<String, Long> byLine = new HashMap<>();
        Map<String, Long> byFunction = new HashMap<>();
        long attributed = 0;

        for (Map.Entry<Integer, long[]> entry : this.actionsByStatement.entrySet()) {
            String location = this.trace.locate(entry.getKey());
            long statementActions = entry.getValue()[0];
            int lineSeparator = location.lastIndexOf('|');
            String function = lineSeparator > 0 ? location.substring(0, lineSeparator) : location;

            byLine.merge(location, statementActions, Long::sum);
            byFunction.merge(function, statementActions, Long::sum);
            attributed += statementActions;
        }

        report.put("attributedShare", share(attributed, actions));
        report.put("top", top(byLine, TOP_LINES, actions));
        report.put("topFunctions", top(byFunction, TOP_FUNCTIONS, actions));
        report.put("note", "Counts of a debug build: its trace triggers add actions, so compare statements, not absolute numbers.");

        return report;
    }

    private static @NotNull List<Object> top(@NotNull Map<String, Long> counts, int limit, long total) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());

        entries.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()));

        List<Object> top = new ArrayList<>();

        for (Map.Entry<String, Long> entry : entries.subList(0, Math.min(limit, entries.size()))) {
            top.add(RunnerMain.ordered("where", entry.getKey(), "actions", entry.getValue(), "share", share(entry.getValue(), total)));
        }

        return top;
    }

    private static double share(long part, long total) {
        return total == 0 ? 0 : Math.round(part * 1000.0 / total) / 1000.0;
    }
}
