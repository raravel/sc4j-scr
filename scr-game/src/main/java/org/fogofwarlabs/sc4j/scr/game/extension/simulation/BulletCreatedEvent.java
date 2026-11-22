package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.fogofwarlabs.sc4j.scr.game.combat.ProjectilePhase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A weapon created a bullet and the bullet entered the active bullet list.
 */
public final class BulletCreatedEvent extends SimulationEvent {
    private final ProjectilePhase.@NotNull Bullet bullet;
    private final long bulletGeneration;
    private final int weapon;
    private final int ownerIndex;
    private final @NotNull SimulationUnit source;
    private final @Nullable SimulationUnit target;
    private final int targetX;
    private final int targetY;

    /**
     * Creates a {@link BulletCreatedEvent} instance.
     *
     * @param bullet     the bullet, an entry of the bullet table that a later bullet may take; its generation is kept
     * @param weapon     the weapons.dat entry
     * @param ownerIndex the owning player slot
     * @param source     the unit that fired
     * @param target     the target unit, or {@code null} for a position target
     * @param targetX    the target X coordinate
     * @param targetY    the target Y coordinate
     */
    public BulletCreatedEvent(ProjectilePhase.@NotNull Bullet bullet,
                              int weapon,
                              int ownerIndex,
                              @NotNull SimulationUnit source,
                              @Nullable SimulationUnit target,
                              int targetX, int targetY) {
        this.bullet           = bullet;
        this.bulletGeneration = bullet.getGeneration();
        this.weapon           = weapon;
        this.ownerIndex       = ownerIndex;
        this.source           = source;
        this.target           = target;
        this.targetX          = targetX;
        this.targetY          = targetY;
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
     * @return the unit that fired
     */
    public @NotNull SimulationUnit getSource() {
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
}
