package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.jetbrains.annotations.NotNull;

/**
 * A building finished an upgrade and raised its owner's upgrade level.
 */
public final class UpgradeCompletedEvent extends SimulationEvent {
    private final @NotNull SimulationUnit unit;
    private final int upgrade;
    private final int level;

    /**
     * Creates a {@link UpgradeCompletedEvent} instance.
     *
     * @param unit    the upgrading building
     * @param upgrade the upgrades.dat entry
     * @param level   the new upgrade level
     */
    public UpgradeCompletedEvent(@NotNull SimulationUnit unit, int upgrade, int level) {
        this.unit    = unit;
        this.upgrade = upgrade;
        this.level   = level;
    }

    /**
     * @return the upgrading building
     */
    public @NotNull SimulationUnit getUnit() {
        return this.unit;
    }

    /**
     * @return the upgrades.dat entry
     */
    public int getUpgrade() {
        return this.upgrade;
    }

    /**
     * @return the new upgrade level
     */
    public int getLevel() {
        return this.level;
    }
}
