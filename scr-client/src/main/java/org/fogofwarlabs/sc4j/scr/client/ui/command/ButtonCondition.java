package org.fogofwarlabs.sc4j.scr.client.ui.command;

import org.jetbrains.annotations.Nullable;

/**
 * Identifies command-card conditions by their native function identifiers, followed by SC:R's own conditions.
 * The evaluator applies each condition to the active unit, selection and player and returns an enabled, hidden or disabled state.
 */
public enum ButtonCondition {
    /**
     * Always enabled.
     */
    ALWAYS(0x140183eb0L),
    /**
     * Replay Slow Down button.
     */
    REPLAY_SLOW_DOWN(0x1403bac60L),
    /**
     * Replay Speed Up button.
     */
    REPLAY_SPEED_UP(0x1403bad10L),
    /**
     * Replay Play or Pause button.
     */
    REPLAY_PLAY_PAUSE(0x1403bae40L),
    /**
     * Unit requirement of the active unit; multiple selections only for Larva, Mutalisks, Hydralisks.
     */
    UNIT_REQUIREMENT(0x1403e4400L),
    /**
     * Unit requirement for Interceptors and Scarabs over a selection of one unit type.
     */
    HANGAR_UNIT_REQUIREMENT(0x1403e4480L),
    /**
     * Any selected unit can move.
     */
    ANY_CAN_MOVE(0x1403e4520L),
    /**
     * Any selected unit has a right-click action.
     */
    ANY_HAS_RIGHT_CLICK(0x1403e4580L),
    /**
     * Any selected unit with a right-click action can attack.
     */
    ANY_CAN_ATTACK(0x1403e45d0L),
    /**
     * Any selected enabled unit can attack.
     */
    ANY_ENABLED_CAN_ATTACK(0x1403e4640L),
    /**
     * The active unit's current production slot holds a unit.
     */
    TRAINING_UNIT(0x1403e46b0L),
    /**
     * The active unit constructs an add-on.
     */
    BUILDING_ADDON(0x1403e46c0L),
    /**
     * The active unit is a Hatchery, Lair or Hive.
     */
    IS_HATCHERY(0x1403e46d0L),
    /**
     * Order requirement of the rally order {@code 0x28}.
     */
    RALLY_ORDER(0x1403e4730L),
    /**
     * Order requirement of the rally order for a Hatchery, Lair or Hive.
     */
    HATCHERY_RALLY_ORDER(0x1403e4740L),
    /**
     * The active unit is incomplete.
     */
    INCOMPLETE(0x1403e47b0L),
    /**
     * The active unit researches an upgrade.
     */
    UPGRADING(0x1403e47d0L),
    /**
     * The active unit researches a technology.
     */
    RESEARCHING(0x1403e47e0L),
    /**
     * Any selected unit carries no resource.
     */
    ANY_NOT_CARRYING(0x1403e47f0L),
    /**
     * Any selected unit carries a resource.
     */
    ANY_CARRYING(0x1403e4850L),
    /**
     * The selected transports can load; the first one that cannot carry hides the button.
     */
    ANY_CAN_LOAD(0x1403e48b0L),
    /**
     * Any selected unit has loaded units.
     */
    ANY_HAS_LOADED(0x1403e4910L),
    /**
     * Research requirement of the active unit.
     */
    RESEARCH_REQUIREMENT(0x1403e4960L),
    /**
     * Technology use requirement of the active unit.
     */
    TECHNOLOGY_USE_REQUIREMENT(0x1403e4970L),
    /**
     * Technology use requirement of any selected unit.
     */
    ANY_TECHNOLOGY_USE_REQUIREMENT(0x1403e4980L),
    /**
     * Upgrade requirement of the active unit.
     */
    UPGRADE_REQUIREMENT(0x1403e4a00L),
    /**
     * A selected unit may cloak with the condition technology.
     */
    CAN_CLOAK(0x1403e4a10L),
    /**
     * All selected units may decloak with the condition technology.
     */
    CAN_DECLOAK(0x1403e4ab0L),
    /**
     * A selected unit may cloak with its own cloaking technology.
     */
    CAN_CLOAK_OWN_TECHNOLOGY(0x1403e4b50L),
    /**
     * All selected units may decloak with their own cloaking technology.
     */
    CAN_DECLOAK_OWN_TECHNOLOGY(0x1403e4be0L),
    /**
     * Siege Mode may be used and no selected tank is sieged.
     */
    CAN_SIEGE(0x1403e4c70L),
    /**
     * Siege Mode may be used and a selected tank is sieged.
     */
    CAN_UNSIEGE(0x1403e4ce0L),
    /**
     * No selected tank is sieged.
     */
    NONE_SIEGED(0x1403e4d40L),
    /**
     * The player owns a Nuclear Silo with a ready missile.
     */
    NUCLEAR_SILO_READY(0x1403e4d90L),
    /**
     * The active unit is not disabled.
     */
    NOT_DISABLED(0x1403e4dc0L),
    /**
     * The active unit is not a grounded building.
     */
    NOT_GROUNDED(0x1403e4de0L),
    /**
     * One lifted building is selected.
     */
    CAN_LAND(0x1403e4e00L),
    /**
     * The active grounded building is idle.
     */
    CAN_LIFT_OFF(0x1403e4e30L),
    /**
     * A selected burrowable unit may burrow.
     */
    CAN_BURROW(0x1403e4e80L),
    /**
     * All selected burrowable units may unburrow.
     */
    CAN_UNBURROW(0x1403e4f20L),
    /**
     * Technology use requirement of Lurker Aspect ({@code 32}).
     */
    LURKER_ASPECT(0x1403e4fc0L),
    /**
     * No selected unit is burrowed.
     */
    NONE_BURROWED(0x1403e4fd0L),
    /**
     * No selected unit is burrowed and one can attack.
     */
    NONE_BURROWED_CAN_ATTACK(0x1403e5020L),
    /**
     * No selected unit is burrowed and one carries no resource.
     */
    NONE_BURROWED_NOT_CARRYING(0x1403e50c0L),
    /**
     * No selected unit is burrowed and one carries a resource.
     */
    NONE_BURROWED_CARRYING(0x1403e5150L),
    /**
     * All selected units share the active unit's burrow state.
     */
    SAME_BURROW_STATE(0x1403e51e0L),
    /**
     * The active Nydus Canal has no exit.
     */
    NO_NYDUS_EXIT(0x1403e5260L),
    /**
     * One unburrowed Drone may mutate into a basic structure.
     */
    ZERG_BASIC_STRUCTURES(0x1403e5280L),
    /**
     * One unburrowed Drone may mutate into an advanced structure.
     */
    ZERG_ADVANCED_STRUCTURES(0x1403e5340L),
    /**
     * A selected Carrier has Interceptors.
     */
    CARRIER_HAS_INTERCEPTORS(0x1403e53e0L),
    /**
     * A selected Reaver has Scarabs.
     */
    REAVER_HAS_SCARABS(0x1403e5460L),
    /**
     * Disabled when the technology use requirement is disabled, otherwise hidden.
     */
    TECHNOLOGY_USE_DISABLED(0x1403e54e0L),
    /**
     * Archon Warp with more than one selected unit.
     */
    ARCHON_WARP(0x1403e5500L),
    /**
     * Dark Archon Meld with more than one selected unit.
     */
    DARK_ARCHON_MELD(0x1403e5540L),
    /**
     * One Probe may warp in a basic structure.
     */
    PROTOSS_BASIC_STRUCTURES(0x1403e5580L),
    /**
     * One Probe may warp in an advanced structure.
     */
    PROTOSS_ADVANCED_STRUCTURES(0x1403e5650L),
    /**
     * All selected SCVs construct.
     */
    ALL_CONSTRUCTING(0x1403e5720L),
    /**
     * A selected SCV does not construct.
     */
    ANY_NOT_CONSTRUCTING(0x1403e5750L),
    /**
     * A selected SCV carries nothing and one does not construct.
     */
    SCV_CAN_GATHER(0x1403e5780L),
    /**
     * A selected SCV carries a resource and one does not construct.
     */
    SCV_CAN_RETURN_CARGO(0x1403e5820L),
    /**
     * One SCV may build a basic structure.
     */
    TERRAN_BASIC_STRUCTURES(0x1403e58c0L),
    /**
     * One SCV may build an advanced structure.
     */
    TERRAN_ADVANCED_STRUCTURES(0x1403e59b0L),
    /**
     * Unit requirement with exactly one selected unit.
     */
    SINGLE_UNIT_REQUIREMENT(0x1403e5a50L),
    /**
     * The state a game plugin sets for the condition value ({@code ScrButtonStates}), evaluated like the other conditions only for an own
     * active unit. SC:R's own condition, whose identifier is no native function address.
     */
    SCRIPT(0x1L);

    /**
     * Finds the condition of a native function address, as the button set table holds it.
     *
     * @param nativeAddress the SC:R 1.23.10.13515 x64 function address
     * @return the condition, or {@code null} for an address that is not a condition function
     */
    public static @Nullable ButtonCondition ofNativeAddress(long nativeAddress) {
        for (ButtonCondition value : values()) {
            if (value.nativeAddress == nativeAddress) {
                return value;
            }
        }

        return null;
    }

    private final long nativeAddress;

    ButtonCondition(long nativeAddress) {
        this.nativeAddress = nativeAddress;
    }

    /**
     * @return the SC:R 1.23.10.13515 x64 address of the condition function
     */
    public long getNativeAddress() {
        return this.nativeAddress;
    }
}
