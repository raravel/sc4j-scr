package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.game.extension.simulation.SimulationEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines {@code core.events}: what happened in the simulation, delivered as records at the frame start after it happened.
 * <p>
 * The simulation events wait from the moment they happen ({@link #queue}); each frame start ({@link #startFrame()}) makes the waiting events
 * the frame's events, in the order they happened. {@code getFrameEvents()} returns a new array of the records of the frame's events, read-only
 * plain objects of numbers and booleans: the {@code kind} (the ordinal of {@link ScriptEventKind}), the {@code frame} the event happened in
 * and the event's values ({@link ScriptSimulationUtils}). Reading does not take the events: every call of a frame returns the same records,
 * and the events that happen meanwhile wait for the next frame start. It works in shared code only. {@code getKindCount()} and
 * {@code getKindName(kind)} name the kinds.
 */
final class ScriptEventCore {
    /**
     * One waiting event, its kind and the frame it happened in.
     */
    private static final class QueuedEvent {
        private final @NotNull ScriptEventKind kind;
        private final @NotNull SimulationEvent event;
        private final int frame;

        private QueuedEvent(@NotNull ScriptEventKind kind, @NotNull SimulationEvent event, int frame) {
            this.kind  = kind;
            this.event = event;
            this.frame = frame;
        }
    }


    private final @NotNull ScriptGame scriptGame;

    private @NotNull List<QueuedEvent> waiting;
    private @NotNull List<QueuedEvent> frameEvents;
    private @NotNull Object @Nullable [] records;

    /**
     * Creates a {@link ScriptEventCore} instance without events.
     *
     * @param scriptGame the game binding
     */
    ScriptEventCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame = scriptGame;

        this.waiting     = new ArrayList<>();
        this.frameEvents = new ArrayList<>();
        this.records     = null;
    }

    /**
     * Adds an event to the waiting events; an event of no kind is left out.
     *
     * @param event the event, whose values were captured when it happened
     * @param frame the frame it happened in
     */
    void queue(@NotNull SimulationEvent event, int frame) {
        ScriptEventKind kind = ScriptEventKind.findByEvent(event);

        if (kind != null) {
            this.waiting.add(new QueuedEvent(kind, event, frame));
        }
    }

    /**
     * Makes the waiting events the frame's events.
     */
    void startFrame() {
        List<QueuedEvent> events = this.frameEvents;

        events.clear();

        this.frameEvents = this.waiting;
        this.waiting     = events;
        this.records     = null;
    }

    /**
     * Forgets every event.
     */
    void clear() {
        this.waiting.clear();
        this.frameEvents.clear();

        this.records = null;
    }

    /**
     * Defines the functions of {@code core.events}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptEventKind[] kinds = ScriptEventKind.values();

        ScriptCoreUtils.defineFunction(global, namespace, "getFrameEvents", 0, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkShared();

            return context.newArray(global, this.getRecords(context, global).clone());
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getKindCount", 0, (context, scope, thisObject, arguments) -> (double) kinds.length);
        ScriptCoreUtils.defineFunction(global, namespace, "getKindName", 1, (context, scope, thisObject, arguments) -> {
            int kind = ScriptCoreUtils.requireId("event kind", ScriptCoreUtils.readInteger(arguments, 0), kinds.length);

            return kinds[kind].getKindName();
        });
    }

    /**
     * Creates the records of the frame's events once per frame.
     */
    private @NotNull Object @NotNull [] getRecords(@NotNull Context context, @NotNull Scriptable global) {
        Object[] frameRecords = this.records;

        if (frameRecords != null) {
            return frameRecords;
        }

        frameRecords = new Object[this.frameEvents.size()];

        for (int index = 0; index < frameRecords.length; index++) {
            QueuedEvent queued = this.frameEvents.get(index);
            ScriptableObject record = ScriptSimulationUtils.createEventRecord(context, global, this.scriptGame.getUnitPool(), queued.kind,
                                                                              queued.event, queued.frame);

            frameRecords[index] = ScriptCoreUtils.freeze(record);
        }

        this.records = frameRecords;

        return frameRecords;
    }
}
