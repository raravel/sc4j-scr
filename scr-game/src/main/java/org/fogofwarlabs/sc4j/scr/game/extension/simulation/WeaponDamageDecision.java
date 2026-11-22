package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The base damage of a weapon hit on a unit, before the damage cheats, hallucination, the divisor, acid spores, the defensive matrix, shields,
 * armor and size apply: a plugin changes the damage or cancels the hit, which then leaves the target as an invincible target is left. A hit on
 * an invincible target or a target without hit points offers no decision.
 */
public final class WeaponDamageDecision extends SimulationDecision {
    private final int weapon;
    private final @Nullable SimulationUnit source;
    private final int sourcePlayer;
    private final @NotNull SimulationUnit target;

    private int damage;
    private boolean cancelled;

    /**
     * Creates a {@link WeaponDamageDecision} instance that is not cancelled.
     *
     * @param weapon       the weapons.dat entry
     * @param source       the attacking unit, or {@code null} for none
     * @param sourcePlayer the attacking player slot
     * @param target       the hit unit
     * @param damage       the base damage with eight fractional bits
     */
    public WeaponDamageDecision(int weapon, @Nullable SimulationUnit source, int sourcePlayer, @NotNull SimulationUnit target, int damage) {
        this.weapon       = weapon;
        this.source       = source;
        this.sourcePlayer = sourcePlayer;
        this.target       = target;

        this.damage    = damage;
        this.cancelled = false;
    }

    /**
     * @return the weapons.dat entry
     */
    public int getWeapon() {
        return this.weapon;
    }

    /**
     * @return the attacking unit, or {@code null} for none
     */
    public @Nullable SimulationUnit getSource() {
        return this.source;
    }

    /**
     * @return the attacking player slot
     */
    public int getSourcePlayer() {
        return this.sourcePlayer;
    }

    /**
     * @return the hit unit
     */
    public @NotNull SimulationUnit getTarget() {
        return this.target;
    }

    /**
     * @return the base damage with eight fractional bits
     */
    public int getDamage() {
        return this.damage;
    }

    /**
     * Sets the base damage the hit continues with.
     *
     * @param damage the base damage with eight fractional bits
     */
    public void setDamage(int damage) {
        this.damage = damage;
    }

    /**
     * @return {@code true} if the hit is cancelled, {@code false} otherwise
     */
    public boolean isCancelled() {
        return this.cancelled;
    }

    /**
     * Sets whether the hit is cancelled.
     *
     * @param cancelled whether the hit leaves the target unchanged
     */
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
