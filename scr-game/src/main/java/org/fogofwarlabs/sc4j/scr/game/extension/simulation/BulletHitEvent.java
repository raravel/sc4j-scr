package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.fogofwarlabs.sc4j.scr.game.combat.ProjectilePhase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A bullet hits, before its damage or effect applies.
 */
public final class BulletHitEvent extends SimulationEvent {
    private final ProjectilePhase.@NotNull Bullet bullet;
    private final long bulletGeneration;
    private final int weapon;
    private final int ownerIndex;
    private final @Nullable SimulationUnit source;
    private final @Nullable SimulationUnit target;
    private final int targetX;
    private final int targetY;
    private final boolean missed;

    /**
     * Creates a {@link BulletHitEvent} instance.
     *
     * @param bullet     the bullet, an entry of the bullet table that a later bullet may take; its generation is kept
     * @param weapon     the weapons.dat entry
     * @param ownerIndex the owning player slot
     * @param source     the unit that fired, or {@code null} if it is gone
     * @param target     the target unit, or {@code null} for a position target
     * @param targetX    the target X coordinate
     * @param targetY    the target Y coordinate
     * @param missed     whether the bullet missed its target unit
     */
    public BulletHitEvent(ProjectilePhase.@NotNull Bullet bullet,
                          int weapon,
                          int ownerIndex,
                          @Nullable SimulationUnit source,
                          @Nullable SimulationUnit target,
                          int targetX, int targetY,
                          boolean missed) {
        this.bullet           = bullet;
        this.bulletGeneration = bullet.getGeneration();
        this.weapon           = weapon;
        this.ownerIndex       = ownerIndex;
        this.source           = source;
        this.target           = target;
        this.targetX          = targetX;
        this.targetY          = targetY;
        this.missed           = missed;
    }

    /**
     * @return the bullet, an entry of the bullet table that a later bullet may take
     */
    public ProjectilePhase.@NotNull Bullet getBullet() {
        return this.bullet;
    }

    /**
     * @return the generation of the bullet when the event happened ({@link ProjectilePhase.Bullet#getGeneration()})
     */
    public long getBulletGeneration() {
        return this.bulletGeneration;
    }

    /**
     * @return the weapons.dat entry
     */
    public int getWeapon() {
        return this.weapon;
    }

    /**
     * @return the owning player slot
     */
    public int getOwnerIndex() {
        return this.ownerIndex;
    }

    /**
     * @return the unit that fired, or {@code null} if it is gone
     */
    public @Nullable SimulationUnit getSource() {
        return this.source;
    }

    /**
     * @return the target unit, or {@code null} for a position target
     */
    public @Nullable SimulationUnit getTarget() {
        return this.target;
    }

    /**
     * @return the target X coordinate
     */
    public int getTargetX() {
        return this.targetX;
    }

    /**
     * @return the target Y coordinate
     */
    public int getTargetY() {
        return this.targetY;
    }

    /**
     * @return {@code true} if the bullet missed its target unit, {@code false} otherwise
     */
    public boolean isMissed() {
        return this.missed;
    }
}
