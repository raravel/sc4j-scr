package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.combat.ProjectilePhase;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.*;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The simulation plugins of one game ({@link ScrSimulationExtensionUtils#createPlugins}) as the simulation routines reach them: the routines report
 * their events and offer their decisions here, and this object creates the event and decision objects, captures the units they name
 * ({@link SimulationUnit}) and hands them to every plugin in extension order. A game without simulation plugins has no such object, so its
 * routines create no event or decision object.
 * <p>
 * While the plugins decide a decision of one kind, an offer of the same kind is not passed on: the routine continues with its own values.
 */
public final class ScrSimulationPlugins {
    private final @NotNull Game game;
    private final @NotNull ScrSimulationPlugin @NotNull [] plugins;

    /* Whether the plugins are deciding a decision of each kind. */
    private boolean decidingWeaponDamage;
    private boolean decidingOrder;
    private boolean decidingTarget;

    /**
     * Creates a {@link ScrSimulationPlugins} instance.
     *
     * @param game    the game
     * @param plugins the plugins in extension order, at least one
     */
    ScrSimulationPlugins(@NotNull Game game, @NotNull ScrSimulationPlugin @NotNull [] plugins) {
        this.game    = game;
        this.plugins = plugins;

        this.decidingWeaponDamage = false;
        this.decidingOrder        = false;
        this.decidingTarget       = false;
    }

    /**
     * @return the plugins in extension order; the array is shared and must not be changed
     */
    public @NotNull ScrSimulationPlugin @NotNull [] getPlugins() {
        return this.plugins;
    }


    /**
     * Calls {@link ScrSimulationPlugin#onFrameStarted()} of every plugin.
     */
    public void startFrame() {
        for (ScrSimulationPlugin plugin : this.plugins) {
            plugin.onFrameStarted();
        }
    }

    /**
     * Asks every plugin whether the game can be saved ({@link ScrSimulationPlugin#allowsSave()}).
     *
     * @return {@code true} if every plugin allows a save, {@code false} otherwise
     */
    public boolean allowsSave() {
        for (ScrSimulationPlugin plugin : this.plugins) {
            if (!plugin.allowsSave()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Hands a mod command to every plugin ({@link ScrSimulationPlugin#onModCommand}).
     *
     * @param player the issuing player's record
     * @param data   the command data
     */
    public void runModCommand(@NotNull Player player, byte @NotNull [] data) {
        for (ScrSimulationPlugin plugin : this.plugins) {
            plugin.onModCommand(player, data);
        }
    }


    /**
     * Offers the base damage of a weapon hit ({@link WeaponDamageDecision}).
     *
     * @param weapon       the weapons.dat entry
     * @param source       the attacking unit, or {@code null} for none
     * @param sourcePlayer the attacking player slot
     * @param target       the hit unit
     * @param damage       the base damage with eight fractional bits
     * @return the decision, which the plugins decided unless a weapon damage decision is being decided
     */
    public @NotNull WeaponDamageDecision decideWeaponDamage(int weapon, @Nullable Unit source, int sourcePlayer, @NotNull Unit target, int damage) {
        WeaponDamageDecision decision = new WeaponDamageDecision(weapon, this.captureNullable(source), sourcePlayer, this.capture(target), damage);

        if (this.decidingWeaponDamage) {
            return decision;
        }

        this.decidingWeaponDamage = true;

        try {
            this.decide(decision);
        } finally {
            this.decidingWeaponDamage = false;
        }

        return decision;
    }

    /**
     * Offers an order issued to a unit ({@link OrderDecision}) and reports it ({@link OrderIssuedEvent}) unless it is rejected. The Die order and
     * the orders of a turret are reported without a decision.
     *
     * @param unit           the receiving unit
     * @param orderId        the orders.dat entry
     * @param targetUnit     the target unit, or {@code null} for a position target
     * @param positionX      the target X coordinate
     * @param positionY      the target Y coordinate
     * @param targetUnitType the target unit type of a build or placement order
     * @param queued         whether the order is requested as a queued order
     * @return the decision, which the plugins decided unless it offers no decision
     */
    public @NotNull OrderDecision decideOrder(@NotNull Unit unit,
                                              int orderId,
                                              @Nullable Unit targetUnit,
                                              int positionX, int positionY,
                                              int targetUnitType,
                                              boolean queued) {
        boolean turret = this.isTurret(unit);
        SimulationUnit captured = this.capture(unit);
        OrderDecision decision = new OrderDecision(captured, orderId, targetUnit, positionX, positionY, targetUnitType, queued);

        if (!this.decidingOrder && !turret && orderId != ScrOrderIds.DIE) {
            this.decidingOrder = true;

            try {
                this.decide(decision);
            } finally {
                this.decidingOrder = false;
            }
        }

        if (!decision.isRejected() && !turret) {
            this.report(new OrderIssuedEvent(captured,
                                             decision.getOrderId(),
                                             this.captureNullable(decision.getTargetUnit()),
                                             decision.getPositionX(), decision.getPositionY(),
                                             targetUnitType,
                                             queued));
        }

        return decision;
    }

    /**
     * Offers the target an automatic target search found ({@link TargetAcquisitionDecision}) and reports the target the search keeps
     * ({@link TargetAcquiredEvent}).
     *
     * @param unit   the searching unit
     * @param target the found target, or {@code null} for none, which offers no decision
     * @return the target the search finds, or {@code null} for none
     */
    public @Nullable Unit decideAcquiredTarget(@NotNull Unit unit, @Nullable Unit target) {
        if (target == null) {
            return null;
        }

        SimulationUnit captured = this.capture(unit);
        TargetAcquisitionDecision decision = new TargetAcquisitionDecision(captured, target);

        if (!this.decidingTarget) {
            this.decidingTarget = true;

            try {
                this.decide(decision);
            } finally {
                this.decidingTarget = false;
            }
        }

        Unit decided = decision.getTarget();

        if (decided != null) {
            this.report(new TargetAcquiredEvent(captured, this.capture(decided)));
        }

        return decided;
    }


    /**
     * Reports a created unit ({@link UnitCreatedEvent}).
     *
     * @param unit the created unit
     */
    public void reportUnitCreated(@NotNull Unit unit) {
        this.report(new UnitCreatedEvent(this.capture(unit)));
    }

    /**
     * Reports a completed unit ({@link UnitCompletedEvent}).
     *
     * @param unit the completed unit
     */
    public void reportUnitCompleted(@NotNull Unit unit) {
        this.report(new UnitCompletedEvent(this.capture(unit)));
    }

    /**
     * Reports a unit that starts to die ({@link UnitDiedEvent}).
     *
     * @param unit         the dying unit
     * @param killer       the attacking unit of the lethal damage, or {@code null} for none
     * @param killerPlayer the player slot the death is credited to, or {@code -1} for none
     * @param weapon       the weapons.dat entry of the lethal weapon damage, or {@code -1} for none
     */
    public void reportUnitDied(@NotNull Unit unit, @Nullable Unit killer, int killerPlayer, int weapon) {
        this.report(new UnitDiedEvent(this.capture(unit), this.captureNullable(killer), killerPlayer, weapon));
    }

    /**
     * Reports hit point damage to a unit ({@link UnitDamagedEvent}).
     *
     * @param target       the damaged unit
     * @param source       the attacking unit, or {@code null} for none
     * @param sourcePlayer the attacking player slot, or {@code -1} for none
     * @param weapon       the weapons.dat entry of the weapon, or {@code -1} for none
     * @param damage       the hit point damage with eight fractional bits
     */
    public void reportUnitDamaged(@NotNull Unit target, @Nullable Unit source, int sourcePlayer, int weapon, int damage) {
        this.report(new UnitDamagedEvent(this.capture(target), this.captureNullable(source), sourcePlayer, weapon, damage));
    }

    /**
     * Reports a unit a producer trained ({@link UnitTrainedEvent}).
     *
     * @param unit     the trained unit
     * @param producer the producing unit
     */
    public void reportUnitTrained(@NotNull Unit unit, @NotNull Unit producer) {
        this.report(new UnitTrainedEvent(this.capture(unit), this.capture(producer)));
    }

    /**
     * Reports a finished research ({@link ResearchCompletedEvent}).
     *
     * @param unit       the researching building
     * @param technology the techdata.dat entry
     */
    public void reportResearchCompleted(@NotNull Unit unit, int technology) {
        this.report(new ResearchCompletedEvent(this.capture(unit), technology));
    }

    /**
     * Reports a finished upgrade ({@link UpgradeCompletedEvent}).
     *
     * @param unit    the upgrading building
     * @param upgrade the upgrades.dat entry
     * @param level   the new upgrade level
     */
    public void reportUpgradeCompleted(@NotNull Unit unit, int upgrade, int level) {
        this.report(new UpgradeCompletedEvent(this.capture(unit), upgrade, level));
    }

    /**
     * Reports a cast technology ({@link SpellCastEvent}).
     *
     * @param caster     the casting unit
     * @param technology the techdata.dat entry
     * @param target     the target unit, or {@code null} for none
     * @param positionX  the target X coordinate
     * @param positionY  the target Y coordinate
     */
    public void reportSpellCast(@NotNull Unit caster, int technology, @Nullable Unit target, int positionX, int positionY) {
        this.report(new SpellCastEvent(this.capture(caster), technology, this.captureNullable(target), positionX, positionY));
    }

    /**
     * Reports a created bullet ({@link BulletCreatedEvent}).
     *
     * @param bullet     the bullet
     * @param weapon     the weapons.dat entry
     * @param ownerIndex the owning player slot
     * @param source     the unit that fired
     * @param target     the target unit, or {@code null} for a position target
     * @param targetX    the target X coordinate
     * @param targetY    the target Y coordinate
     */
    public void reportBulletCreated(ProjectilePhase.@NotNull Bullet bullet,
                                    int weapon,
                                    int ownerIndex,
                                    @NotNull Unit source, @Nullable Unit target,
                                    int targetX, int targetY) {
        this.report(new BulletCreatedEvent(bullet, weapon, ownerIndex, this.capture(source), this.captureNullable(target), targetX, targetY));
    }

    /**
     * Reports a bullet hit ({@link BulletHitEvent}).
     *
     * @param bullet     the bullet
     * @param weapon     the weapons.dat entry
     * @param ownerIndex the owning player slot
     * @param source     the unit that fired, or {@code null} if it is gone
     * @param target     the target unit, or {@code null} for a position target
     * @param targetX    the target X coordinate
     * @param targetY    the target Y coordinate
     * @param missed     whether the bullet missed its target unit
     */
    public void reportBulletHit(ProjectilePhase.@NotNull Bullet bullet,
                                int weapon,
                                int ownerIndex,
                                @Nullable Unit source, @Nullable Unit target,
                                int targetX, int targetY,
                                boolean missed) {
        this.report(new BulletHitEvent(bullet,
                                       weapon,
                                       ownerIndex,
                                       this.captureNullable(source), this.captureNullable(target),
                                       targetX, targetY,
                                       missed));
    }

    /**
     * Reports a unit that changes its owner ({@link OwnerChangedEvent}), before the change; a turret is not reported.
     *
     * @param unit  the unit, with its previous owner
     * @param owner the new owner's player slot
     */
    public void reportOwnerChanged(@NotNull Unit unit, int owner) {
        if (!this.isTurret(unit)) {
            this.report(new OwnerChangedEvent(this.capture(unit), unit.getOwnerIndex(), owner));
        }
    }

    /**
     * Reports a loaded unit ({@link UnitLoadedEvent}).
     *
     * @param unit      the loaded unit
     * @param transport the transport or Bunker
     */
    public void reportUnitLoaded(@NotNull Unit unit, @NotNull Unit transport) {
        this.report(new UnitLoadedEvent(this.capture(unit), this.capture(transport)));
    }

    /**
     * Reports a unit that left its transport ({@link UnitUnloadedEvent}).
     *
     * @param unit      the unloaded unit
     * @param transport the transport or Bunker
     */
    public void reportUnitUnloaded(@NotNull Unit unit, @NotNull Unit transport) {
        this.report(new UnitUnloadedEvent(this.capture(unit), this.capture(transport)));
    }

    /**
     * Reports a player who left ({@link PlayerLeftEvent}).
     *
     * @param playerIndex the player slot
     * @param reason      the signed departure reason
     */
    public void reportPlayerLeft(int playerIndex, int reason) {
        this.report(new PlayerLeftEvent(playerIndex, reason));
    }

    /**
     * Reports a player's result ({@link PlayerResultEvent}).
     *
     * @param playerIndex  the player slot
     * @param victoryState the victory state BYTE
     */
    public void reportPlayerResult(int playerIndex, int victoryState) {
        this.report(new PlayerResultEvent(playerIndex, victoryState));
    }

    private void report(@NotNull SimulationEvent event) {
        for (ScrSimulationPlugin plugin : this.plugins) {
            plugin.onEvent(event);
        }
    }

    private void decide(@NotNull SimulationDecision decision) {
        for (ScrSimulationPlugin plugin : this.plugins) {
            plugin.decide(decision);
        }
    }

    private @NotNull SimulationUnit capture(@NotNull Unit unit) {
        int handle = this.game.getUnitSystem().requireUnitPool().getHandle(unit.getIndex());

        return new SimulationUnit(unit, handle, unit.getUnitId(), unit.getOwnerIndex());
    }

    private @Nullable SimulationUnit captureNullable(@Nullable Unit unit) {
        return unit == null ? null : this.capture(unit);
    }

    /**
     * @param unit the unit
     * @return {@code true} if the unit is a turret, which follows its unit, {@code false} otherwise
     */
    private boolean isTurret(@NotNull Unit unit) {
        return (this.game.requireGameData().getUnitTypes().getFlags(unit.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0;
    }
}
