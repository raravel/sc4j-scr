package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugin;

/**
 * A decision point of a simulation routine, offered to the simulation plugins ({@link ScrSimulationPlugin#decide}) before the routine
 * continues: a plugin changes the values of the object, and the routine continues with the values the object holds afterwards. Each kind of
 * decision is a class of its own.
 * <p>
 * While the plugins decide a decision of one kind, a routine that would offer a decision of the same kind runs with its own values and offers
 * none.
 */
public abstract class SimulationDecision {
    /**
     * Creates a {@link SimulationDecision} instance.
     */
    protected SimulationDecision() {
    }
}
