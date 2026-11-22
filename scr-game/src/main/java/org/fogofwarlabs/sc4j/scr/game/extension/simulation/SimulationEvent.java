package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugin;

/**
 * Something that happened in the simulation, reported to the simulation plugins ({@link ScrSimulationPlugin#onEvent}) by the routine where it
 * happened, at the moment it happened. An event holds the objects and values known there, captured at that moment; each kind of event is a
 * class of its own.
 */
public abstract class SimulationEvent {
    /**
     * Creates a {@link SimulationEvent} instance.
     */
    protected SimulationEvent() {
    }
}
