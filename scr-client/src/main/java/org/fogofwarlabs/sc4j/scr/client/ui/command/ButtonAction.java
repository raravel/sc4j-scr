package org.fogofwarlabs.sc4j.scr.client.ui.command;

import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Classifies command-card actions and retains their native function identifiers and represented command opcodes, followed by SC:R's own
 * actions.
 * Command actions send immediately, while targeting and placement actions establish the mode used by a later click.
 * Interface and replay actions are handled locally by the command input layer.
 */
public enum ButtonAction {
    /**
     * Enters Move targeting.
     */
    MOVE(0x1403a9cc0L, Kind.TARGET, -1),
    /**
     * Sends Stop {@code 0x1a}.
     */
    STOP(0x1403aa0e0L, Kind.COMMAND, ReplayCommandIds.STOP),
    /**
     * Targets Attack.
     */
    ATTACK(0x1403a99c0L, Kind.TARGET, -1),
    /**
     * Targets Patrol.
     */
    PATROL(0x1403a9d00L, Kind.TARGET, -1),
    /**
     * Sends Hold Position {@code 0x2b}.
     */
    HOLD_POSITION(0x1403a9c30L, Kind.COMMAND, ReplayCommandIds.HOLD_POSITION),
    /**
     * Sends Stim Packs {@code 0x36} after the health test.
     */
    STIM_PACKS(0x1403aa020L, Kind.COMMAND, ReplayCommandIds.STIM),
    /**
     * Sends Cloak {@code 0x21} after the energy test.
     */
    CLOAK(0x1403a9ae0L, Kind.COMMAND, ReplayCommandIds.CLOAK),
    /**
     * Sends Decloak {@code 0x22}.
     */
    DECLOAK(0x1403a9bc0L, Kind.COMMAND, ReplayCommandIds.DECLOAK),
    /**
     * Targets the order of the technology (jump table).
     */
    USE_TECHNOLOGY(0x1403aa130L, Kind.TARGET, -1),
    /**
     * Targets Nuclear Strike.
     */
    NUCLEAR_STRIKE(0x1403a9cd0L, Kind.TARGET, -1),
    /**
     * Sends Siege {@code 0x26}.
     */
    SIEGE(0x1403aa010L, Kind.COMMAND, ReplayCommandIds.SIEGE),
    /**
     * Sends Unsiege {@code 0x25}.
     */
    UNSIEGE(0x1403aa120L, Kind.COMMAND, ReplayCommandIds.UNSIEGE),
    /**
     * Targets Repair.
     */
    REPAIR(0x1403a9e60L, Kind.TARGET, -1),
    /**
     * Targets Gather.
     */
    GATHER(0x1403a9c10L, Kind.TARGET, -1),
    /**
     * Sends Return Cargo {@code 0x1e}.
     */
    RETURN_CARGO(0x1403a9e70L, Kind.COMMAND, ReplayCommandIds.RETURN_CARGO),
    /**
     * Shows the button set of the action value locally.
     */
    CHANGE_BUTTONS(0x1403ede60L, Kind.INTERFACE, -1),
    /**
     * Targets Load.
     */
    LOAD(0x1403a9bd0L, Kind.TARGET, -1),
    /**
     * Sends Unload All {@code 0x28} when the active unit is a grounded building, otherwise targets MoveUnload.
     */
    UNLOAD_ALL(0x1403aa3f0L, Kind.COMMAND, ReplayCommandIds.UNLOAD_ALL),
    /**
     * Targets Heal.
     */
    HEAL(0x1403a9c20L, Kind.TARGET, -1),
    /**
     * Sends Unit Morph {@code 0x23} after the resource test.
     */
    UNIT_MORPH(0x1403a9c80L, Kind.COMMAND, ReplayCommandIds.UNIT_MORPH),
    /**
     * Sends Cancel Morph {@code 0x19}.
     */
    CANCEL_MORPH(0x1403a9c50L, Kind.COMMAND, ReplayCommandIds.CANCEL_MORPH),
    /**
     * Sends Burrow {@code 0x2c}.
     */
    BURROW(0x1403a9a60L, Kind.COMMAND, ReplayCommandIds.BURROW),
    /**
     * Sends Unburrow {@code 0x2d}.
     */
    UNBURROW(0x1403a9bb0L, Kind.COMMAND, ReplayCommandIds.UNBURROW),
    /**
     * Targets the Infested Terran attack.
     */
    INFESTED_TERRAN_ATTACK(0x1403a9a20L, Kind.TARGET, -1),
    /**
     * Sends Dark Archon Meld {@code 0x5a}.
     */
    DARK_ARCHON_MELD(0x1403aa110L, Kind.COMMAND, ReplayCommandIds.MERGE_DARK_ARCHON),
    /**
     * Sends Archon Warp {@code 0x2a}.
     */
    ARCHON_WARP(0x1403aa100L, Kind.COMMAND, ReplayCommandIds.MERGE_ARCHON),
    /**
     * Targets the Carrier move.
     */
    CARRIER_MOVE(0x1403a9ac0L, Kind.TARGET, -1),
    /**
     * Sends Carrier Stop {@code 0x1b}.
     */
    CARRIER_STOP(0x1403a9ad0L, Kind.COMMAND, ReplayCommandIds.CARRIER_STOP),
    /**
     * Targets the Carrier attack.
     */
    CARRIER_ATTACK(0x1403a9ab0L, Kind.TARGET, -1),
    /**
     * Sends Train Fighter {@code 0x27}.
     */
    TRAIN_HANGAR_UNIT(0x1403a9a50L, Kind.COMMAND, ReplayCommandIds.TRAIN_FIGHTER),
    /**
     * Sends Cancel Train {@code 0x20} for the slot of the action value.
     */
    CANCEL_LAST(0x1403aa0f0L, Kind.COMMAND, ReplayCommandIds.CANCEL_TRAIN),
    /**
     * Sends Reaver Stop {@code 0x1c}.
     */
    REAVER_STOP(0x1403aa3e0L, Kind.COMMAND, ReplayCommandIds.REAVER_STOP),
    /**
     * Targets the Reaver attack.
     */
    REAVER_ATTACK(0x1403a9e40L, Kind.TARGET, -1),
    /**
     * Sends Train {@code 0x1f} with the unit type.
     */
    TRAIN(0x1403a9a40L, Kind.COMMAND, ReplayCommandIds.TRAIN),
    /**
     * Targets Set Rally Point.
     */
    SET_RALLY_POINT(0x1403a9e30L, Kind.TARGET, -1),
    /**
     * Places an add-on.
     */
    BUILD_ADDON(0x1403a9d60L, Kind.PLACEMENT, -1),
    /**
     * Places the landing building.
     */
    LAND(0x1403a9c60L, Kind.PLACEMENT, -1),
    /**
     * Sends Lift Off {@code 0x2f} with the building position.
     */
    LIFT_OFF(0x1403a9a30L, Kind.COMMAND, ReplayCommandIds.LIFT_OFF),
    /**
     * Sends Cancel Add-on {@code 0x34}.
     */
    CANCEL_ADDON(0x1403a9a70L, Kind.COMMAND, ReplayCommandIds.CANCEL_ADDON),
    /**
     * Sends Upgrade {@code 0x32}.
     */
    UPGRADE(0x1403aa430L, Kind.COMMAND, ReplayCommandIds.UPGRADE),
    /**
     * Sends Research {@code 0x30}.
     */
    RESEARCH(0x1403a9ea0L, Kind.COMMAND, ReplayCommandIds.TECH),
    /**
     * Sends Cancel Upgrade {@code 0x33}.
     */
    CANCEL_UPGRADE(0x1403a9a90L, Kind.COMMAND, ReplayCommandIds.CANCEL_UPGRADE),
    /**
     * Sends Cancel Research {@code 0x31}.
     */
    CANCEL_RESEARCH(0x1403a9a80L, Kind.COMMAND, ReplayCommandIds.CANCEL_TECH),
    /**
     * Targets the attack of a defensive structure.
     */
    BUILDING_ATTACK(0x1403aa3d0L, Kind.TARGET, -1),
    /**
     * Selects the Larva of the Hatchery locally.
     */
    SELECT_LARVA(0x1403a9eb0L, Kind.INTERFACE, -1),
    /**
     * Sends Building Morph {@code 0x35} after the resource test.
     */
    BUILDING_MORPH(0x1403aa440L, Kind.COMMAND, ReplayCommandIds.BUILDING_MORPH),
    /**
     * Places the Nydus Canal exit.
     */
    PLACE_NYDUS_EXIT(0x1403a9ce0L, Kind.PLACEMENT, -1),
    /**
     * Targets Recharge Shields.
     */
    RECHARGE_SHIELDS(0x1403a9e50L, Kind.TARGET, -1),
    /**
     * Places a flag beacon.
     */
    PLACE_BEACON(0x1403a9db0L, Kind.PLACEMENT, -1),
    /**
     * Leaves targeting mode.
     */
    CANCEL_TARGETING(0x140304980L, Kind.INTERFACE, -1),
    /**
     * Leaves building placement (set {@code 230}).
     */
    CANCEL_PLACEMENT(0x1403e5a70L, Kind.INTERFACE, -1),
    /**
     * Sends Cancel Construction {@code 0x18}.
     */
    CANCEL_CONSTRUCTION(0x1403a9c40L, Kind.COMMAND, ReplayCommandIds.CANCEL_BUILD),
    /**
     * The Cancel Infestation button function; pressing it has no effect.
     */
    CANCEL_INFESTATION(0x14014ed40L, Kind.INTERFACE, -1),
    /**
     * Sends Cancel Nuke {@code 0x2e}.
     */
    CANCEL_NUCLEAR_STRIKE(0x1403a9aa0L, Kind.COMMAND, ReplayCommandIds.CANCEL_NUKE),
    /**
     * Places a Zerg structure.
     */
    ZERG_BUILD(0x1403aa480L, Kind.PLACEMENT, -1),
    /**
     * Places a Terran structure.
     */
    TERRAN_BUILD(0x1403a9d10L, Kind.PLACEMENT, -1),
    /**
     * Places a Protoss structure.
     */
    PROTOSS_BUILD(0x1403a9de0L, Kind.PLACEMENT, -1),
    /**
     * Plays or pauses the replay.
     */
    REPLAY_PLAY_PAUSE(0x1403bad60L, Kind.REPLAY, -1),
    /**
     * Decreases replay speed.
     */
    REPLAY_SLOW_DOWN(0x1403bac00L, Kind.REPLAY, -1),
    /**
     * Increases replay speed.
     */
    REPLAY_SPEED_UP(0x1403bacb0L, Kind.REPLAY, -1),
    /**
     * Sends nothing: only the game plugins see the press. SC:R's own action, whose identifier is no native function address.
     */
    SCRIPT(0x2L, Kind.SCRIPT, -1),
    /**
     * Enters a targeting mode whose target only the game plugins see, a unit or the ground, instead of an order. SC:R's own action, as
     * {@link #SCRIPT}.
     */
    SCRIPT_TARGET(0x3L, Kind.SCRIPT_TARGET, -1);

    /**
     * Classifies the effect of pressing a button.
     */
    public enum Kind {
        /**
         * Sends one network command at once.
         */
        COMMAND,
        /**
         * Enters a targeting mode.
         */
        TARGET,
        /**
         * Enters building placement.
         */
        PLACEMENT,
        /**
         * Changes local interface state only.
         */
        INTERFACE,
        /**
         * Controls replay playback.
         */
        REPLAY,
        /**
         * Tells the game plugins about the press only.
         */
        SCRIPT,
        /**
         * Enters a targeting mode whose target the game plugins receive.
         */
        SCRIPT_TARGET
    }

    /**
     * Finds the action of a native function address, as the button set table holds it.
     *
     * @param nativeAddress the SC:R 1.23.10.13515 x64 function address
     * @return the action, or {@code null} for an address that is not an action function
     */
    public static @Nullable ButtonAction ofNativeAddress(long nativeAddress) {
        for (ButtonAction value : values()) {
            if (value.nativeAddress == nativeAddress) {
                return value;
            }
        }

        return null;
    }

    private final long nativeAddress;
    private final @NotNull Kind kind;
    private final int opcode;

    ButtonAction(long nativeAddress, @NotNull Kind kind, int opcode) {
        this.nativeAddress = nativeAddress;
        this.kind          = kind;
        this.opcode        = opcode;
    }

    /**
     * @return the SC:R 1.23.10.13515 x64 address of the action function
     */
    public long getNativeAddress() {
        return this.nativeAddress;
    }

    /**
     * @return the effect class of the action
     */
    public @NotNull Kind getKind() {
        return this.kind;
    }

    /**
     * @return the network command opcode of a {@link Kind#COMMAND} action, or {@code -1}
     */
    public int getOpcode() {
        return this.opcode;
    }
}
