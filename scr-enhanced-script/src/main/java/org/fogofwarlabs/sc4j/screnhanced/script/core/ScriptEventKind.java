package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.game.extension.simulation.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Lists the kinds of the event records of {@code core.events}: the SC:R simulation events a script reads. The id of a kind is its ordinal.
 */
enum ScriptEventKind {
    UNIT_CREATED("UnitCreated", UnitCreatedEvent.class),
    UNIT_COMPLETED("UnitCompleted", UnitCompletedEvent.class),
    UNIT_DIED("UnitDied", UnitDiedEvent.class),
    UNIT_DAMAGED("UnitDamaged", UnitDamagedEvent.class),
    ORDER_ISSUED("OrderIssued", OrderIssuedEvent.class),
    TARGET_ACQUIRED("TargetAcquired", TargetAcquiredEvent.class),
    UNIT_TRAINED("UnitTrained", UnitTrainedEvent.class),
    RESEARCH_COMPLETED("ResearchCompleted", ResearchCompletedEvent.class),
    UPGRADE_COMPLETED("UpgradeCompleted", UpgradeCompletedEvent.class),
    SPELL_CAST("SpellCast", SpellCastEvent.class),
    OWNER_CHANGED("OwnerChanged", OwnerChangedEvent.class),
    UNIT_LOADED("UnitLoaded", UnitLoadedEvent.class),
    UNIT_UNLOADED("UnitUnloaded", UnitUnloadedEvent.class),
    BULLET_CREATED("BulletCreated", BulletCreatedEvent.class),
    BULLET_HIT("BulletHit", BulletHitEvent.class),
    PLAYER_LEFT("PlayerLeft", PlayerLeftEvent.class),
    PLAYER_RESULT("PlayerResult", PlayerResultEvent.class);

    private static final @NotNull Map<Class<?>, ScriptEventKind> KINDS_BY_CLASS = new HashMap<>();

    static {
        for (ScriptEventKind kind : values()) {
            KINDS_BY_CLASS.put(kind.eventClass, kind);
        }
    }

    /**
     * Finds the kind of a simulation event.
     *
     * @param event the event
     * @return the kind of the event's class, or {@code null} if no kind has it
     */
    static @Nullable ScriptEventKind findByEvent(@NotNull SimulationEvent event) {
        return KINDS_BY_CLASS.get(event.getClass());
    }


    private final @NotNull String kindName;
    private final @NotNull Class<? extends SimulationEvent> eventClass;

    ScriptEventKind(@NotNull String kindName, @NotNull Class<? extends SimulationEvent> eventClass) {
        this.kindName   = kindName;
        this.eventClass = eventClass;
    }

    /**
     * @return the name of the kind, as {@code core.events.getKindName} returns it
     */
    @NotNull String getKindName() {
        return this.kindName;
    }
}
