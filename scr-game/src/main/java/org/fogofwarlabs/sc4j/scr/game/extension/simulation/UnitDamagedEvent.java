package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A unit takes hit point damage, before its hit points change: weapon damage after shields, armor and size, or damage dealt directly. Damage to
 * shields alone and hit point changes that are no damage (setting hit points, Plague) have no event.
 */
public final class UnitDamagedEvent extends SimulationEvent {
    private final @NotNull SimulationUnit target;
    private final @Nullable SimulationUnit source;
    private final int sourcePlayer;
    private final int weapon;
    private final int damage;

    /**
     * Creates a {@link UnitDamagedEvent} instance.
     *
     * @param target       the damaged unit
     * @param source       the attacking unit, or {@code null} for none
     * @param sourcePlayer the attacking player slot, or {@code -1} for none
     * @param weapon       the weapons.dat entry of the weapon, or {@code -1} for damage without a weapon
     * @param damage       the hit point damage with eight fractional bits after the damage cheats; it may exceed the hit points
     */
    public UnitDamagedEvent(@NotNull SimulationUnit target, @Nullable SimulationUnit source, int sourcePlayer, int weapon, int damage) {
        this.target       = target;
        this.source       = source;
        this.sourcePlayer = sourcePlayer;
        this.weapon       = weapon;
        this.damage       = damage;
    }

    /**
     * @return the damaged unit
     */
    public @NotNull SimulationUnit getTarget() {
        return this.target;
    }

    /**
     * @return the attacking unit, or {@code null} for none
     */
    public @Nullable SimulationUnit getSource() {
        return this.source;
    }

    /**
     * @return the attacking player slot, or {@code -1} for none
     */
    public int getSourcePlayer() {
        return this.sourcePlayer;
    }

    /**
     * @return the weapons.dat entry of the weapon, or {@code -1} for damage without a weapon
     */
    public int getWeapon() {
        return this.weapon;
    }

    /**
     * @return the hit point damage with eight fractional bits after the damage cheats; it may exceed the hit points
     */
    public int getDamage() {
        return this.damage;
    }
}
