package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A unit started to die: its death was requested, or a hidden unit was destroyed directly. A death by damage names the attacker, the credited player
 * and the weapon of the damage when the damage had one; any other death names none of them. A unit loaded in a dying transport dies without a killer.
 */
public final class UnitDiedEvent extends SimulationEvent {
    private final @NotNull SimulationUnit unit;
    private final @Nullable SimulationUnit killer;
    private final int killerPlayer;
    private final int weapon;

    /**
     * Creates a {@link UnitDiedEvent} instance.
     *
     * @param unit         the dying unit
     * @param killer       the attacking unit of the lethal damage, or {@code null} for none
     * @param killerPlayer the player slot the death is credited to, or {@code -1} for none
     * @param weapon       the weapons.dat entry of the lethal weapon damage, or {@code -1} for none
     */
    public UnitDiedEvent(@NotNull SimulationUnit unit, @Nullable SimulationUnit killer, int killerPlayer, int weapon) {
        this.unit         = unit;
        this.killer       = killer;
        this.killerPlayer = killerPlayer;
        this.weapon       = weapon;
    }

    /**
     * @return the dying unit
     */
    public @NotNull SimulationUnit getUnit() {
        return this.unit;
    }

    /**
     * @return the attacking unit of the lethal damage, or {@code null} for none
     */
    public @Nullable SimulationUnit getKiller() {
        return this.killer;
    }

    /**
     * @return the player slot the death is credited to, or {@code -1} for none
     */
    public int getKillerPlayer() {
        return this.killerPlayer;
    }

    /**
     * @return the weapons.dat entry of the lethal weapon damage, or {@code -1} for none
     */
    public int getWeapon() {
        return this.weapon;
    }
}
