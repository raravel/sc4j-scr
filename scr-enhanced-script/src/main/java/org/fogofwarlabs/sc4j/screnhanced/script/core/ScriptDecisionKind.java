package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.game.extension.simulation.OrderDecision;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.SimulationDecision;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.TargetAcquisitionDecision;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.WeaponDamageDecision;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists the kinds of the decision records that the {@code decide} entry point receives: the SC:R simulation decisions a script takes part in.
 * The id of a kind is its ordinal, the id of the upper layer's {@code DecisionKind} of the same name.
 */
enum ScriptDecisionKind {
    DAMAGE(WeaponDamageDecision.class),
    ORDER(OrderDecision.class),
    TARGET_ACQUIRED(TargetAcquisitionDecision.class);

    /**
     * Finds the kind of a simulation decision.
     *
     * @param decision the decision
     * @return the kind of the decision's class, or {@code null} if no kind has it
     */
    static @Nullable ScriptDecisionKind findByDecision(@NotNull SimulationDecision decision) {
        for (ScriptDecisionKind kind : values()) {
            if (kind.decisionClass == decision.getClass()) {
                return kind;
            }
        }

        return null;
    }


    private final @NotNull Class<? extends SimulationDecision> decisionClass;

    ScriptDecisionKind(@NotNull Class<? extends SimulationDecision> decisionClass) {
        this.decisionClass = decisionClass;
    }
}
