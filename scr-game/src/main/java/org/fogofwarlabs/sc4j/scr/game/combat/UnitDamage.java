package org.fogofwarlabs.sc4j.scr.game.combat;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.combat.DamageHit;
import org.fogofwarlabs.sc4j.engine.content.modifier.Stat;
import org.fogofwarlabs.sc4j.engine.content.type.ContentType;
import org.fogofwarlabs.sc4j.scr.content.ScrDamageKinds;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrWeaponIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrImageTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrWeaponTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ImageIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.WeaponHitTypes;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerMilitary;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerPlayerPhase;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerUnitRecord;
import org.fogofwarlabs.sc4j.scr.game.ai.UnitStrengthTable;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.WeaponDamageDecision;
import org.fogofwarlabs.sc4j.scr.game.frame.UnitPhase;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitLifecycle;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.fogofwarlabs.sc4j.scr.game.visibility.SightCoverage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Applies weapon damage, shields (the game's {@link ScrShieldLayer}), armor, target retaliation and death requests.
 * <p>
 * Damage values carry eight fractional bits.
 */
public final class UnitDamage {
    /**
     * The index of the entry of damage type {@code 0} and size {@code 0} in the damage multiplier table; the entry of a damage type and size is this
     * plus the type times {@link #MULTIPLIER_ROW_LENGTH} plus the size.
     */
    private static final int DAMAGE_MULTIPLIER_FIRST = 6;
    /**
     * The number of entries of one damage type's row in the damage multiplier table.
     */
    private static final int MULTIPLIER_ROW_LENGTH = 5;
    /**
     * The largest kill count the kill count BYTE holds.
     */
    private static final int MAXIMUM_KILL_COUNT = 0xFF;

    /**
     * The {@code weapons.dat} damage type that ignores shield upgrades and armor.
     */
    private static final int DAMAGE_TYPE_IGNORE_ARMOR = 4;
    /**
     * The {@code weapons.dat} damage type without shield hit overlays.
     */
    private static final int DAMAGE_TYPE_NONE = 0;
    /**
     * The smallest damage of a hit, half a point.
     */
    private static final int MINIMUM_DAMAGE = 128;
    /**
     * Shields absorb damage and show hit overlays only while they hold at least one whole point.
     */
    private static final int MINIMUM_ABSORBING_SHIELDS = 256;
    /**
     * The factor of the damage a hallucination takes.
     */
    private static final int HALLUCINATION_DAMAGE_MULTIPLIER = 2;

    /**
     * The random call site of the air splash target choice.
     */
    private static final int AIR_SPLASH_RANDOM_SOURCE = 58;
    /**
     * The damage divisor of full damage: a direct hit, the unit an air splash first damages and a unit within the inner splash radius.
     */
    private static final int FULL_DAMAGE_DIVISOR = 1;
    /**
     * The damage divisor of a unit within the medium splash radius.
     */
    private static final int MEDIUM_SPLASH_DIVISOR = 2;
    /**
     * The damage divisor of a unit within the outer splash radius.
     */
    private static final int OUTER_SPLASH_DIVISOR = 4;
    /**
     * The smallest damage of a nuclear missile hit in whole points.
     */
    private static final int NUCLEAR_MINIMUM_DAMAGE = 500;
    /**
     * A nuclear missile hit deals the target's maximum hit points and shields times this, divided by {@link #NUCLEAR_DAMAGE_DIVISOR}.
     */
    private static final int NUCLEAR_HIT_POINT_NUMERATOR = 2;
    /**
     * A nuclear missile hit deals the target's maximum hit points and shields times {@link #NUCLEAR_HIT_POINT_NUMERATOR}, divided by this.
     */
    private static final int NUCLEAR_DAMAGE_DIVISOR = 3;
    /**
     * The Psionic Storm timer a unit gets when a storm hits it; no storm damages the unit while the timer is nonzero.
     */
    private static final int STORM_HIT_TIMER = 1;
    /**
     * The half size in pixels of the box of owner units that retaliate together with a damaged unit.
     */
    private static final int RETALIATION_RADIUS = 96;
    /**
     * The sight descriptor index of an attacker reveal.
     */
    private static final int ATTACKER_REVEAL_RADIUS_INDEX = 1;

    /**
     * The direction subtracted from the hit heading before it selects a shield overlay position.
     */
    private static final int SHIELD_OVERLAY_DIRECTION_OFFSET = 124;
    /**
     * The number of shield overlay positions.
     */
    private static final int SHIELD_OVERLAY_POSITIONS = 32;

    /**
     * The fraction bits of fixed-point damage, armor and shield values.
     */
    private static final int FIXED_POINT_FRACTION_BITS = 8;
    /**
     * The mask of an unsigned byte value.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The shift of the upper WORD of an image overlay offset.
     */
    private static final int WORD_BITS = 16;

    /**
     * Credits a kill to a unit: its kill count grows up to {@code 255}, and an Interceptor with a Carrier credits the kill to the Carrier as well.
     *
     * @param game the game of the unit
     * @param unit the unit that killed
     */
    public static void creditKill(@NotNull Game game, @NotNull Unit unit) {
        if (unit.getKillCount() < MAXIMUM_KILL_COUNT) {
            unit.setKillCount(unit.getKillCount() + 1);
        }

        Unit carrier = game.getBaseType(unit) == ScrUnitIds.PROTOSS_INTERCEPTOR ? unit.getHangarParent() : null;

        if (carrier != null) {
            creditKill(game, carrier);
        }
    }

    /**
     * Checks whether a unit is away from its guard position, which keeps it from fleeing.
     *
     * @param unit the unit that would flee
     * @return {@code true} if the unit has a guard record and is away from its guard position, {@code false} otherwise
     * @throws IllegalStateException if the unit has a guard record and no sprite
     */
    private static boolean isGuardAwayFromPost(@NotNull Unit unit) {
        ComputerUnitRecord record = unit.getComputerRecord();

        if (record == null || record.getKind() != ComputerUnitRecord.KIND_GUARD) {
            return false;
        }

        Sprite sprite = unit.requireSprite();

        return (short) sprite.getX() != (short) record.guardX || (short) sprite.getY() != (short) record.guardY;
    }


    private final @NotNull Game game;
    private final @NotNull ScrShieldLayer shieldLayer;
    private final @NotNull DamageHit hit;

    /**
     * The five live flee distances in pixels, tried in order; EUD writes change them.
     */
    private final int @NotNull [] fleeDistances;
    /**
     * The damage multiplier table ({@code 30} DWORDs in rows of type, independent, small, medium and large): damage is scaled by the entry of the
     * damage type and unit size and shifted right by eight; EUD writes change it.
     */
    private final int @NotNull [] damageMultipliers;

    /**
     * Creates a {@link UnitDamage} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitDamage(@NotNull Game game) {
        this.game        = game;
        this.shieldLayer = new ScrShieldLayer(game);
        this.hit         = new DamageHit();

        this.fleeDistances     = new int[]{192, 128, 64, 32, 16};
        this.damageMultipliers = new int[]{
                0, 0, 1, 2, 3,
                0, 0, 0, 0, 0,
                1, 0, 128, 192, 256,
                2, 0, 256, 128, 64,
                3, 0, 256, 256, 256,
                4, 0, 256, 256, 256
        };
    }

    /**
     * @return the shield damage layer of this game, which the game registers on every unit type
     */
    public @NotNull ScrShieldLayer getShieldLayer() {
        return this.shieldLayer;
    }

    /**
     * @return the five flee distances of the flee position search, live: EUD writes change them
     */
    public int @NotNull [] getFleeDistances() {
        return this.fleeDistances;
    }

    /**
     * @return the damage multiplier table, live: EUD writes change it
     */
    public int @NotNull [] getDamageMultipliers() {
        return this.damageMultipliers;
    }

    /**
     * Computes the weapon's base damage with the owner's weapon upgrade ({@link Stat#WEAPON_DAMAGE}).
     *
     * @param weapon the weapon
     * @param owner  the attacking player
     * @return the damage with eight fractional bits
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the player upgrade levels are missing
     */
    public int getWeaponDamage(int weapon, int owner) {
        ScrWeaponTypeView weapons = this.game.requireGameData().getWeaponTypes();
        int damage = this.game.applyModifiers(Stat.WEAPON_DAMAGE, owner, ContentType.NO_INDEX, weapon, weapons.getDamageAmount(weapon));

        return damage << FIXED_POINT_FRACTION_BITS;
    }

    /**
     * Computes the unit's armor with its armor upgrade and, for an Ultralisk or Torrasque, Chitinous Plating ({@link Stat#ARMOR}).
     *
     * @param unit the armored unit
     * @return the armor with eight fractional bits
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the player upgrade levels are missing
     */
    private int getArmor(@NotNull Unit unit) {
        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();

        return this.game.applyModifiers(Stat.ARMOR, unit.getOwnerIndex(), unitType, ContentType.NO_INDEX,
                                        unitTypes.getArmor(unitType) << FIXED_POINT_FRACTION_BITS);
    }

    /**
     * Applies a melee weapon hit from the unit's current order target.
     *
     * @param unit             the attacking unit executing {@code attackmelee}
     * @param instructionLimit the image and order instruction budget
     */
    public void dealMeleeDamage(@NotNull Unit unit, int instructionLimit) {
        Unit target = unit.getOrderTargetUnit();

        if (target == null) {
            return;
        }

        int weapon = this.game.getUnitTargeting().getGroundWeapon(unit);

        if ((unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
            this.dealHallucinatedHit(weapon, target, unit.getHeading(), unit, instructionLimit);
        } else {
            this.dealWeaponHit(weapon, unit, target, instructionLimit);
        }
    }

    /**
     * Applies a full-damage weapon hit of a unit to a target ({@link #dealWeaponDamage}): the weapon damage of the attacker's owner, from the
     * attacker in its heading.
     *
     * @param weapon           the weapon
     * @param source           the attacking unit
     * @param target           the hit unit
     * @param instructionLimit the image and order instruction budget
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the player upgrade levels are missing, or the damage type and size read past the damage
     *                                       multiplier table
     */
    public void dealWeaponHit(int weapon, @NotNull Unit source, @NotNull Unit target, int instructionLimit) {
        this.dealWeaponDamage(weapon,
                              this.getWeaponDamage(weapon, source.getOwnerIndex()),
                              FULL_DAMAGE_DIVISOR,
                              source, source.getOwnerIndex(),
                              target, source.getHeading(),
                              instructionLimit);
    }

    /**
     * Applies hallucination, divisor, acid spores, shields, armor and size factors to weapon damage.
     *
     * @param weapon           the weapon
     * @param damage           the base damage with eight fractional bits
     * @param divisor          the positive damage divisor
     * @param source           the attacking unit, or {@code null}
     * @param sourceOwner      the attacking player
     * @param target           the damaged unit
     * @param heading          the hit direction used for shield overlays
     * @param instructionLimit the image and order instruction budget
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the player upgrade levels are missing, or the damage type and size read past the damage
     *                                       multiplier table
     */
    public void dealWeaponDamage(int weapon,
                                 int damage,
                                 int divisor,
                                 @Nullable Unit source, int sourceOwner,
                                 @NotNull Unit target, int heading,
                                 int instructionLimit) {
        if (target.getHitPoints() == 0) {
            return;
        }

        if ((target.getStatus() & UnitStatusFlags.INVINCIBLE) != 0) {
            return;
        }

        ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

        if (simulation != null) {
            WeaponDamageDecision decision = simulation.decideWeaponDamage(weapon, source, sourceOwner, target, damage);

            if (decision.isCancelled()) {
                return;
            }

            damage = decision.getDamage();
        }

        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        ScrWeaponTypeView weapons = this.game.requireGameData().getWeaponTypes();

        if (this.game.getGameCheats().suppressesDamage(sourceOwner, false)) {
            damage = 0;
        }

        if ((target.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
            damage *= HALLUCINATION_DAMAGE_MULTIPLIER;
        }

        damage /= divisor;
        damage += target.getAcidSporeCount() << FIXED_POINT_FRACTION_BITS;

        if (damage < MINIMUM_DAMAGE) {
            damage = MINIMUM_DAMAGE;
        }

        if (target.getDefensiveMatrixHealth() != 0) {
            int absorbed = Math.min(damage, target.getDefensiveMatrixHealth());

            this.game.getUnitSpells().dealDefensiveMatrixDamage(target, absorbed, instructionLimit);

            damage -= absorbed;
        }

        int damageType = weapons.getDamageType(weapon);
        int targetType = target.getUnitId();

        this.hit.set(target, source, ScrDamageKinds.find(damageType), damage);

        int shieldDamage = this.shieldLayer.absorbDamage(this.hit);

        damage = this.hit.getDamage();

        if (damageType != DAMAGE_TYPE_IGNORE_ARMOR) {
            int armor = this.getArmor(target);

            if (damage > armor) {
                damage -= armor;
            } else {
                damage = 0;
            }
        }

        int size = unitTypes.getSizeClass(targetType);
        // The multiplier is applied in 32 bits and the product shifted right by eight.
        int multiplier = DAMAGE_MULTIPLIER_FIRST + (damageType & BYTE_MASK) * MULTIPLIER_ROW_LENGTH + (size & BYTE_MASK);

        if (multiplier >= this.damageMultipliers.length) {
            throw Exceptions.newUnsupportedOperationException("Damage type {0} against size {1} reads past the damage multiplier table.",
                                                              damageType, size);
        }

        damage = this.damageMultipliers[multiplier] * damage >>> FIXED_POINT_FRACTION_BITS;

        if (shieldDamage == 0 && damage < MINIMUM_DAMAGE) {
            damage = MINIMUM_DAMAGE;
        }

        this.dealUnitDamage(target, damage, source, sourceOwner, weapon, weapon != ScrWeaponIds.IRRADIATE, instructionLimit);

        if (shieldDamage != 0) {
            this.shieldLayer.setPoints(target, target.getShieldPoints() - shieldDamage);

            if (damageType != DAMAGE_TYPE_NONE && target.getShieldPoints() != 0) {
                this.createShieldHitOverlay(target, heading, instructionLimit);
            }
        }

        this.updateStrength(target);
    }

    /**
     * Applies the retaliation and shield effects of a hallucinated weapon hit without damage.
     *
     * @param weapon           the hallucinated weapon
     * @param target           the hit unit
     * @param heading          the hit direction
     * @param source           the hallucinated attacker, or {@code null}
     * @param instructionLimit the image and order instruction budget
     * @throws NullPointerException if the world was built without game data
     */
    public void dealHallucinatedHit(int weapon, @NotNull Unit target, int heading, @Nullable Unit source, int instructionLimit) {
        if (target.getHitPoints() == 0 || (target.getStatus() & UnitStatusFlags.INVINCIBLE) != 0 || source == null) {
            return;
        }

        this.onDamage(target, source, weapon != ScrWeaponIds.IRRADIATE, instructionLimit);

        // The under-attack alert for another player's hallucinated hit other than Irradiate.
        if (weapon != ScrWeaponIds.IRRADIATE && source.getOwnerIndex() != target.getOwnerIndex()) {
            this.game.getInterfaceLink().getAttackAlertEvents().add(target);
        }

        if (this.game.requireGameData().getWeaponTypes().getDamageType(weapon) != DAMAGE_TYPE_NONE &&
            this.game.requireGameData().getUnitTypes().getShieldEnabled(target.getUnitId()) != 0 &&
            target.getShieldPoints() >= MINIMUM_ABSORBING_SHIELDS) {
            this.createShieldHitOverlay(target, heading, instructionLimit);
        }
    }

    /**
     * Applies damage, killing the unit when the damage reaches its hit points.
     *
     * @param target           the damaged unit
     * @param damage           the damage with eight fractional bits
     * @param source           the attacking unit, or {@code null}
     * @param revealSource     whether the source position is revealed to the target owner
     * @param instructionLimit the image and order instruction budget
     */
    public void dealUnitDamage(@NotNull Unit target, int damage, @Nullable Unit source, boolean revealSource, int instructionLimit) {
        this.dealUnitDamage(target, damage, source, -1, revealSource, instructionLimit);
    }

    /**
     * Applies damage, killing the unit when it reaches its hit points; a killed unit and the units it carries are credited to {@code sourceOwner}
     * through {@link UnitLifecycle#recordDeath}.
     *
     * @param target           the damaged unit
     * @param damage           the damage with eight fractional bits
     * @param source           the attacking unit, or {@code null}
     * @param sourceOwner      the attacking player, or {@code -1} for none
     * @param revealSource     whether the source position is revealed to the target owner
     * @param instructionLimit the image and order instruction budget
     * @throws NullPointerException if the world was built without game data
     */
    public void dealUnitDamage(@NotNull Unit target, int damage, @Nullable Unit source, int sourceOwner, boolean revealSource, int instructionLimit) {
        this.dealUnitDamage(target, damage, source, sourceOwner, -1, revealSource, instructionLimit);
    }

    /**
     * Applies damage of a weapon or without one, killing the unit when it reaches its hit points; a killed unit and the units it carries are
     * credited to {@code sourceOwner} through {@link UnitLifecycle#recordDeath}. The simulation plugins hear the damage and a death it causes
     * with the weapon.
     *
     * @param target           the damaged unit
     * @param damage           the damage with eight fractional bits
     * @param source           the attacking unit, or {@code null}
     * @param sourceOwner      the attacking player, or {@code -1} for none
     * @param weapon           the weapons.dat entry of the damage, or {@code -1} for damage without a weapon
     * @param revealSource     whether the source position is revealed to the target owner
     * @param instructionLimit the image and order instruction budget
     * @throws NullPointerException if the world was built without game data
     */
    private void dealUnitDamage(@NotNull Unit target,
                                int damage,
                                @Nullable Unit source, int sourceOwner,
                                int weapon,
                                boolean revealSource,
                                int instructionLimit) {
        if (target.getHitPoints() == 0) {
            return;
        }

        this.onDamage(target, source, revealSource, instructionLimit);

        if (this.game.getGameCheats().suppressesDamage(sourceOwner, true)) {
            damage = 0;
        }

        ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

        if (simulation != null) {
            simulation.reportUnitDamaged(target, source, sourceOwner, weapon, damage);
        }

        if (damage < target.getHitPoints()) {
            target.setHitPoints(target.getHitPoints() - damage);
            this.updateStrength(target);

            if ((target.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
                this.game.getUnitHealth().updateDamageOverlays(target, instructionLimit);
            }

            // The under-attack alert after non-lethal damage from another player's unit that reveals its source.
            if (source != null && source.getOwnerIndex() != target.getOwnerIndex() && revealSource) {
                this.game.getInterfaceLink().getAttackAlertEvents().add(target);
            }
        } else {
            if (this.game.getUnitQueries().isTransport(target) &&
                !UnitQueries.isBuilding(this.game.requireGameData().getUnitTypes(), target.getUnitId())) {
                for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS; slot++) {
                    Unit loaded = this.game.getUnitTransport().getLoadedUnit(target, slot);

                    if (loaded != null) {
                        this.game.getUnitLifecycle().recordDeath(loaded, sourceOwner);
                    }
                }

                this.game.getUnitTransport().killLoadedUnits(target, instructionLimit);
            }

            target.setHitPoints(0);
            this.game.getUnitLifecycle().kill(target, source, sourceOwner, weapon, instructionLimit);
            this.game.getUnitLifecycle().recordDeath(target, sourceOwner);

            // An attacking unit whose owner is not allied with the killed unit's owner gains the kill.
            if (source != null && this.game.getEngineGame().getCombat().isEnemy(source, target)) {
                creditKill(this.game, source);
            }
        }
    }

    /**
     * Applies bullet damage or delegates spell effects according to the weapon hit type.
     *
     * @param weapon           the bullet weapon
     * @param owner            the bullet owner player
     * @param sourceUnit       the attacking unit, or {@code null}
     * @param target           the bullet target, or {@code null}
     * @param heading          the bullet heading
     * @param hitFlags         the bullet miss and hallucination flags
     * @param bounceDivisor    the bounce damage divisor
     * @param sprite           the bullet sprite, the splash center
     * @param targetX          the bullet target position X
     * @param targetY          the bullet target position Y
     * @param instructionLimit the image and order instruction budget
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the explosion type has no spell hit branch
     */
    void hitBullet(int weapon,
                   int owner, @Nullable Unit sourceUnit,
                   @Nullable Unit target, int heading,
                   int hitFlags,
                   int bounceDivisor,
                   @NotNull Sprite sprite,
                   int targetX, int targetY,
                   int instructionLimit) {
        int hitType = this.game.requireGameData().getWeaponTypes().getExplosionType(weapon);

        switch (hitType) {
            case WeaponHitTypes.NONE -> {
            }
            case WeaponHitTypes.NORMAL -> {
                if (target != null && (hitFlags & ProjectilePhase.HIT_FLAG_MISSED) == 0) {
                    this.dealBulletDamage(weapon, owner, sourceUnit, target, heading, hitFlags, bounceDivisor, instructionLimit);
                } else if (target != null && target.getComputerRecord() != null && sourceUnit != null) {
                    this.onDamage(target, sourceUnit, true, instructionLimit);
                }
            }
            case WeaponHitTypes.RADIAL_SPLASH, WeaponHitTypes.ENEMY_SPLASH, WeaponHitTypes.NUCLEAR_MISSILE -> {
                if (weapon == ScrWeaponIds.SUBTERRANEAN_SPINES) {
                    this.dealSpineDamage(weapon, owner, sourceUnit, target, heading, hitFlags, sprite, instructionLimit);
                } else {
                    this.dealSplashDamage(weapon, owner, sourceUnit, target, heading, hitFlags, sprite, false, instructionLimit);
                }
            }
            case WeaponHitTypes.CORROSIVE_ACID -> {
                if (target != null && (hitFlags & ProjectilePhase.HIT_FLAG_MISSED) == 0) {
                    this.dealBulletDamage(weapon, owner, sourceUnit, target, heading, hitFlags, FULL_DAMAGE_DIVISOR, instructionLimit);

                    if ((hitFlags & ProjectilePhase.HIT_FLAG_HALLUCINATION) == 0) {
                        this.game.getUnitSpells().addAcidSpores(sprite.getX(), sprite.getY(), owner, instructionLimit);
                    }
                }
            }
            case WeaponHitTypes.YAMATO_GUN -> {
                if (target != null) {
                    this.dealBulletDamage(weapon, owner, sourceUnit, target, heading, hitFlags, FULL_DAMAGE_DIVISOR, instructionLimit);
                }
            }
            case WeaponHitTypes.AIR_SPLASH -> this.dealSplashDamage(weapon,
                                                                    owner, sourceUnit,
                                                                    target, heading,
                                                                    hitFlags,
                                                                    sprite,
                                                                    true,
                                                                    instructionLimit);
            default -> this.game.getUnitSpells().hit(hitType,
                                                     owner,
                                                     sourceUnit,
                                                     target,
                                                     sprite.getX(), sprite.getY(),
                                                     targetX, targetY,
                                                     instructionLimit);
        }
    }

    /**
     * Applies Subterranean Spines: each enemy within the inner radius, and the bullet's own target even when it belongs to the bullet owner, is hit
     * at most once per Lurker across the recent hit history.
     *
     * @param weapon           the bullet weapon
     * @param owner            the bullet owner player
     * @param sourceUnit       the attacking Lurker, or {@code null}
     * @param target           the bullet target, or {@code null}
     * @param heading          the bullet heading
     * @param hitFlags         the bullet miss and hallucination flags
     * @param sprite           the bullet sprite, the splash center
     * @param instructionLimit the image and order instruction budget
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the frame contexts are not initialized
     */
    private void dealSpineDamage(int weapon,
                                 int owner, @Nullable Unit sourceUnit,
                                 @Nullable Unit target, int heading,
                                 int hitFlags,
                                 @NotNull Sprite sprite,
                                 int instructionLimit) {
        ScrWeaponTypeView weapons = this.game.requireGameData().getWeaponTypes();
        int outer = weapons.getOuterSplashRadius(weapon);
        int inner = weapons.getInnerSplashRadius(weapon);
        int centerX = sprite.getX();
        int centerY = sprite.getY();
        UnitPhase.Context context = this.game.requireFrame().getUnits();

        for (Unit candidate : this.game.getUnitTargeting().findUnits(centerX - outer, centerY - outer, centerX + outer, centerY + outer)) {
            // The explicit bullet target remains eligible even when it belongs to the bullet owner.
            if (candidate == sourceUnit || candidate.getOwnerIndex() == owner && candidate != target ||
                !this.game.getUnitTargeting().canWeaponTarget(weapon, candidate)) {
                continue;
            }

            if (this.game.getUnitTargeting().getDistanceTo(candidate, centerX, centerY) > inner) {
                continue;
            }

            if (sourceUnit != null) {
                if (context.hasLurkerHit(sourceUnit, candidate)) {
                    continue;
                }

                context.addLurkerHit(sourceUnit, candidate);
            }

            this.dealBulletDamage(weapon, owner, sourceUnit, candidate, heading, hitFlags, FULL_DAMAGE_DIVISOR, instructionLimit);
        }
    }

    /**
     * Applies splash damage: the air variant first damages one eligible unit, then both variants apply inner, medium and outer splash factors to the
     * units around the bullet.
     *
     * @param weapon           the bullet weapon
     * @param owner            the bullet owner player
     * @param sourceUnit       the attacking unit, or {@code null}
     * @param target           the bullet target, or {@code null}
     * @param heading          the bullet heading
     * @param hitFlags         the bullet miss and hallucination flags
     * @param sprite           the bullet sprite, the splash center
     * @param air              whether the splash is the air variant
     * @param instructionLimit the image and order instruction budget
     * @throws NullPointerException if the world was built without game data
     */
    private void dealSplashDamage(int weapon,
                                  int owner, @Nullable Unit sourceUnit,
                                  @Nullable Unit target, int heading,
                                  int hitFlags,
                                  @NotNull Sprite sprite,
                                  boolean air,
                                  int instructionLimit) {
        ScrWeaponTypeView weapons = this.game.requireGameData().getWeaponTypes();
        int outer = weapons.getOuterSplashRadius(weapon);
        int inner = weapons.getInnerSplashRadius(weapon);
        int medium = weapons.getMediumSplashRadius(weapon);
        int centerX = sprite.getX();
        int centerY = sprite.getY();

        if (air) {
            Unit[] candidates = this.game.getUnitTargeting().findUnits(centerX - outer, centerY - outer, centerX + outer, centerY + outer);
            int count = 0;

            for (Unit candidate : candidates) {
                if (candidate == sourceUnit) {
                    continue;
                }

                if (candidate.getOwnerIndex() == owner && candidate != target) {
                    continue;
                }

                if (!this.game.getUnitTargeting().canWeaponTarget(weapon, candidate)) {
                    continue;
                }

                int distance = this.game.getUnitTargeting().getDistanceTo(candidate, centerX, centerY);

                if (distance > outer) {
                    continue;
                }

                if (candidate == target && distance <= inner) {
                    candidates[0] = candidate;

                    count = 1;

                    break;
                }

                candidates[count++] = candidate;
            }

            if (count != 0) {
                int selected = count == 1 ? 0 : Integer.remainderUnsigned(this.game.getRandom().next(AIR_SPLASH_RANDOM_SOURCE), count);

                this.dealBulletDamage(weapon, owner, sourceUnit, candidates[selected], heading, hitFlags, FULL_DAMAGE_DIVISOR, instructionLimit);
            }
        }

        boolean damagesAllies = !air && weapons.getExplosionType(weapon) != WeaponHitTypes.ENEMY_SPLASH;

        for (Unit candidate : this.game.getUnitTargeting().findUnits(centerX - outer, centerY - outer, centerX + outer, centerY + outer)) {
            if (candidate == sourceUnit && weapon != ScrWeaponIds.PSIONIC_STORM) {
                continue;
            }

            if (!damagesAllies && candidate.getOwnerIndex() == owner && candidate != target) {
                continue;
            }

            if (!this.game.getUnitTargeting().canWeaponTarget(weapon, candidate)) {
                continue;
            }

            int distance = this.game.getUnitTargeting().getDistanceTo(candidate, centerX, centerY);

            if (distance > outer) {
                continue;
            }

            if (weapon == ScrWeaponIds.PSIONIC_STORM) {
                if (candidate.getStormTimer() != 0) {
                    continue;
                }

                candidate.setStormTimer(STORM_HIT_TIMER);
            }

            if (air && (candidate == target || this.game.getBaseType(candidate) == ScrUnitIds.PROTOSS_INTERCEPTOR)) {
                continue;
            }

            if (!air && distance <= inner) {
                this.dealBulletDamage(weapon, owner, sourceUnit, candidate, heading, hitFlags, FULL_DAMAGE_DIVISOR, instructionLimit);
            } else if ((candidate.getStatus() & UnitStatusFlags.BURROWED) == 0) {
                this.dealBulletDamage(weapon,
                                      owner, sourceUnit,
                                      candidate, heading,
                                      hitFlags,
                                      distance <= medium ? MEDIUM_SPLASH_DIVISOR : OUTER_SPLASH_DIVISOR,
                                      instructionLimit);
            }
        }
    }

    private void dealBulletDamage(int weapon,
                                  int owner, @Nullable Unit sourceUnit,
                                  @NotNull Unit target, int heading,
                                  int hitFlags,
                                  int divisor,
                                  int instructionLimit) {
        if ((hitFlags & ProjectilePhase.HIT_FLAG_HALLUCINATION) != 0) {
            this.dealHallucinatedHit(weapon, target, heading, sourceUnit, instructionLimit);
        } else {
            this.dealWeaponDamage(weapon, this.getBulletDamage(weapon, owner, target), divisor, sourceUnit, owner, target, heading, instructionLimit);
        }
    }

    /**
     * Returns the damage of a bullet: the weapon damage, or for a nuclear missile two thirds of the target's maximum hit points and shields, at least
     * {@link #NUCLEAR_MINIMUM_DAMAGE}.
     *
     * @param weapon the bullet weapon
     * @param owner  the bullet owner player
     * @param target the bullet target
     * @return the damage with eight fractional bits
     * @throws NullPointerException if the world was built without game data
     */
    private int getBulletDamage(int weapon, int owner, @NotNull Unit target) {
        if (this.game.requireGameData().getWeaponTypes().getExplosionType(weapon) != WeaponHitTypes.NUCLEAR_MISSILE) {
            return this.getWeaponDamage(weapon, owner);
        }

        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        int maximumHitPoints = UnitQueries.getMaximumHitPoints(unitTypes, target);

        if (unitTypes.getShieldEnabled(target.getUnitId()) != 0) {
            maximumHitPoints += unitTypes.getShieldAmount(target.getUnitId());
        }

        return Math.max(NUCLEAR_MINIMUM_DAMAGE, maximumHitPoints * NUCLEAR_HIT_POINT_NUMERATOR / NUCLEAR_DAMAGE_DIVISOR) << FIXED_POINT_FRACTION_BITS;
    }

    /**
     * Records the attacking player, reveals the attacker and updates retaliation targets around the damaged unit.
     *
     * @param unit             the damaged unit
     * @param source           the attacking unit, or {@code null}
     * @param revealSource     whether the attacker's position is revealed
     * @param instructionLimit the order instruction budget
     * @throws IllegalStateException         if a unit damaged by another player's unit has no computer record and no sprite, or an attacker in
     *                                       a Bunker has no Bunker
     * @throws UnsupportedOperationException if the unit finder is missing
     */
    public void onDamage(@NotNull Unit unit, @Nullable Unit source, boolean revealSource, int instructionLimit) {
        if (source == null || source.getOwnerIndex() == unit.getOwnerIndex()) {
            return;
        }

        unit.setLastAttackingPlayer(source.getOwnerIndex());

        // A computer unit without an automatic target takes the source before the defense reaction.
        boolean computerUnit = unit.getComputerRecord() != null;

        if (computerUnit && unit.getAutomaticTargetUnit() == null) {
            this.setAutomaticTarget(unit, source);
        }

        ComputerPlayerPhase computerPhase = this.game.getComputerPlayerPhase();

        if (computerPhase != null && computerPhase.hasComputerPlayers()) {
            this.game.getComputerDefense().onHit(computerPhase, unit, source, true, false);
        }

        if (revealSource && !UnitTargeting.isUndetected(unit, source) && unit.getOwnerIndex() < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            this.revealAttacker(unit, source);
        }

        // Retaliation against a unit firing from a Bunker targets the Bunker.
        if ((source.getStatus() & UnitStatusFlags.IN_BUNKER) != 0) {
            source = source.requireParentUnit();
        }

        if (this.shouldChangeTarget(unit, unit.getAutomaticTargetUnit(), source)) {
            this.changeTarget(unit, source, instructionLimit);
        }

        // Computer units skip neighbor retaliation.
        if (unit.getComputerRecord() != null) {
            return;
        }

        Sprite sprite = unit.requireSprite();
        UnitSpatialIndex spatialIndex = this.game.getUnitSystem().getUnitSpatialIndex();

        if (spatialIndex == null) {
            throw Exceptions.newUnsupportedOperationException("Damage retaliation requires the unit finder.");
        }

        int count = spatialIndex.collectPlacementCandidates(sprite.getX() - RETALIATION_RADIUS, sprite.getY() - RETALIATION_RADIUS,
                                                            sprite.getX() + RETALIATION_RADIUS, sprite.getY() + RETALIATION_RADIUS);

        for (int index = 0; index < count; index++) {
            Unit nearby = spatialIndex.getPlacementCandidate(index);

            if (nearby != unit && nearby.getOwnerIndex() == unit.getOwnerIndex()) {
                this.changeAreaTarget(nearby, source);
            }
        }
    }

    /**
     * Computes the flee position, also used by computer retreat: the first flee distance away from the target whose position the unit can reach
     * safely ({@link ComputerMilitary#canReachSafely}), or the unit's own position. An accepted position also becomes the guard position of a unit
     * with a guard record.
     *
     * @param unit   the fleeing unit
     * @param target the unit it flees from
     * @return the position {@code {x, y}}
     * @throws IllegalStateException if the unit or the target has no sprite
     */
    public int @NotNull [] computeFleePosition(@NotNull Unit unit, @NotNull Unit target) {
        Sprite sprite = unit.requireSprite();
        Sprite targetSprite = target.requireSprite();
        int direction = FlingyMotionUtils.calculateDirection(sprite.getX() - targetSprite.getX(), sprite.getY() - targetSprite.getY());
        int mapWidth = this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE;
        int mapHeight = this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE;

        // Each distance scales the direction vector: a 32-bit product shifted right by eight.
        for (int distance : this.fleeDistances) {
            long vector = FlingyMotionUtils.calculateDirectionVector(direction, distance);
            int positionX = Math.max(0, Math.min(mapWidth - 1, sprite.getX() + (int) vector));
            int positionY = Math.max(0, Math.min(mapHeight - 1, sprite.getY() + (int) (vector >>> FlingyMotionUtils.DIRECTION_VECTOR_VERTICAL_SHIFT)));

            if (this.game.getComputerMilitary().canReachSafely(unit, positionX, positionY)) {
                ComputerMilitary.setGuardPosition(unit, positionX, positionY);

                return new int[]{positionX, positionY};
            }
        }

        return new int[]{sprite.getX(), sprite.getY()};
    }

    /**
     * Reveals the attacker's position to the damaged unit's owner, at the attacker's ground height.
     *
     * @param unit   the damaged unit
     * @param source the attacking unit
     * @throws IllegalStateException         if the source has no sprite
     * @throws UnsupportedOperationException if the sight coverage is missing
     */
    private void revealAttacker(@NotNull Unit unit, @NotNull Unit source) {
        SightCoverage coverage = this.game.getSightCoverage();

        if (coverage == null) {
            throw Exceptions.newUnsupportedOperationException("Attacker reveal requires sight coverage.");
        }

        boolean flying = (source.getStatus() & UnitStatusFlags.FLYING) != 0;
        Sprite sprite = source.requireSprite();

        coverage.reveal(this.game, (short) sprite.getX(), (short) sprite.getY(), flying, 1 << unit.getOwnerIndex(), ATTACKER_REVEAL_RADIUS_INDEX);
    }

    private boolean shouldChangeTarget(@NotNull Unit unit, @Nullable Unit previous, @NotNull Unit candidate) {
        if (previous == candidate) {
            return false;
        }

        if (!this.game.getEngineGame().getCombat().isEnemy(unit, candidate)) {
            return false;
        }

        if (previous == null) {
            return true;
        }

        if (!this.game.getUnitTargeting().canAttackTarget(unit, previous)) {
            return true;
        }

        if (!this.game.getEngineGame().getCombat().isEnemy(unit, previous)) {
            return true;
        }

        return this.game.getUnitTargeting().isInWeaponMovementRange(unit, candidate) &&
               !this.game.getUnitTargeting().isInWeaponMovementRange(unit, previous);
    }

    /**
     * Responds to the attacker of a unit other than a Larva: a burrowed unit other than a Lurker that is not irradiated unburrows if it can
     * burrow; a building, or a non-worker that can attack the attacker, takes it as its automatic target; otherwise, except for a burrowed
     * Lurker without a computer record or under irradiation, the main unit flees from the attacker when its order is fleeable, it can move,
     * it is not away from its guard position and it does not always dodge.
     *
     * @param unit             the damaged unit
     * @param target           the attacker
     * @param instructionLimit the order instruction budget
     * @throws NullPointerException if the world was built without game data
     */
    private void changeTarget(@NotNull Unit unit, @NotNull Unit target, int instructionLimit) {
        int unitType = unit.getUnitId();
        int baseType = this.game.getBaseType(unitType);

        if (baseType == ScrUnitIds.ZERG_LARVA) {
            return;
        }

        ScrUnitTypeView unitTypes = this.game.requireGameData().getUnitTypes();
        int abilities = unitTypes.getFlags(unitType);

        if ((unit.getStatus() & UnitStatusFlags.BURROWED) != 0 && baseType != ScrUnitIds.ZERG_LURKER && unit.getIrradiateTimer() == 0) {
            if ((abilities & UnitTypeFlags.BURROWABLE) != 0) {
                this.game.getUnitBurrowing().unburrowUnit(unit, instructionLimit);
            }

            return;
        }

        if (UnitQueries.isBuilding(unitTypes, unitType) ||
            (abilities & UnitTypeFlags.WORKER) == 0 && this.game.getUnitTargeting().canAttackTarget(unit, target)) {
            this.setAutomaticTarget(unit, target);
        } else if ((unit.getStatus() & UnitStatusFlags.BURROWED) == 0 || baseType != ScrUnitIds.ZERG_LURKER ||
                   unit.getComputerRecord() != null && unit.getIrradiateTimer() == 0) {
            // A burrowed computer Lurker without irradiation remains eligible to flee.
            Unit mainUnit = this.game.getUnitTargeting().getMainUnit(unit);

            if (this.game.requireGameData().getOrderTypes().getFleeable(mainUnit.getPrimaryOrder()) != 0 &&
                (mainUnit.getStatus() & UnitStatusFlags.CAN_MOVE) != 0 && !isGuardAwayFromPost(mainUnit) &&
                this.game.getProjectilePhase().getDodgeChance(mainUnit) < ProjectilePhase.MISS_CHANCE_ALWAYS) {
                int[] position = this.computeFleePosition(mainUnit, target);

                this.game.getOrders().issue(mainUnit, ScrOrderIds.MOVE, null, position[0], position[1], ScrUnitIds.NONE, false, instructionLimit);
            }
        }
    }

    /**
     * Lets a unit near the damaged unit take the attacker as its automatic target; an Arbiter, Danimoth, a worker, a burrowed unit and a unit
     * that cannot attack keep their target.
     *
     * @param unit   the nearby unit of the damaged unit's owner
     * @param target the attacker
     * @throws NullPointerException if the world was built without game data
     */
    private void changeAreaTarget(@NotNull Unit unit, @NotNull Unit target) {
        int unitType = unit.getUnitId();
        int baseType = this.game.getBaseType(unitType);

        if (baseType == ScrUnitIds.PROTOSS_ARBITER || baseType == ScrUnitIds.HERO_DANIMOTH) {
            return;
        }

        if ((this.game.requireGameData().getUnitTypes().getFlags(unitType) & UnitTypeFlags.WORKER) != 0) {
            return;
        }

        if ((unit.getStatus() & UnitStatusFlags.BURROWED) != 0) {
            return;
        }

        if (!this.game.getUnitTargeting().canAttack(unit)) {
            return;
        }

        if (this.shouldChangeTarget(unit, unit.getAutomaticTargetUnit(), target)) {
            this.setAutomaticTarget(unit, target);
        }
    }

    /**
     * Sets the automatic target of a unit and, when the unit has a turret, the turret's automatic target and last attacking player.
     *
     * @param unit   the unit that targets
     * @param target the new automatic target
     */
    private void setAutomaticTarget(@NotNull Unit unit, @NotNull Unit target) {
        unit.setAutomaticTargetUnit(target);

        Unit turret = unit.getSubunit();

        if (turret != null && this.game.getUnitTargeting().isTurret(turret)) {
            turret.setLastAttackingPlayer(unit.getLastAttackingPlayer());
            turret.setAutomaticTargetUnit(target);
        }
    }

    /**
     * Creates the shield hit overlay on the target's main image at the overlay position of the hit direction.
     *
     * @param target           the hit unit
     * @param heading          the hit direction
     * @param instructionLimit the image instruction budget
     * @throws IllegalStateException if the target has no sprite or main image, or the LO offsets cannot be read
     * @throws NullPointerException  if the world was built without game data
     */
    private void createShieldHitOverlay(@NotNull Unit target, int heading, int instructionLimit) {
        Sprite sprite = target.requireSprite();
        SpriteImage mainImage = sprite.getMainImage();

        if (mainImage == null) {
            throw Exceptions.newIllegalStateException("Shield hit overlays require the target main image.");
        }

        int index = (int) Long.remainderUnsigned(
                Long.divideUnsigned((long) (heading & BYTE_MASK) - SHIELD_OVERLAY_DIRECTION_OFFSET, FlingyMotionUtils.DIRECTION_THIRTY_SECOND_TURN),
                SHIELD_OVERLAY_POSITIONS
        );
        int offset;

        try {
            offset = this.game.requireGameData().getImageOverlayOffset(mainImage.getImageTypeId(),
                                                                       ScrImageTypeView.OVERLAY_SHIELD,
                                                                       mainImage.getFrameIndexOffset(),
                                                                       index);
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Shield hit overlays require installed LO offsets.");
        }

        int horizontal = (short) offset;

        if ((mainImage.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0) {
            horizontal = -horizontal;
        }

        this.game.getIscriptExecutor().createImage(sprite,
                                                   mainImage,
                                                   ImageIds.SHIELD_OVERLAY,
                                                   horizontal, (short) (offset >>> WORD_BITS),
                                                   index,
                                                   null,
                                                   instructionLimit);
    }

    /**
     * Updates the strength table entries of the unit.
     *
     * @param unit the damaged unit
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the strength tables are not initialized
     */
    private void updateStrength(@NotNull Unit unit) {
        UnitStrengthTable strength = this.game.getUnitStrengthTable();

        if (strength == null) {
            throw Exceptions.newUnsupportedOperationException("Damage requires initialized strength tables.");
        }

        strength.update(this.game, unit, this.game.requireGameData().getUnitTypes());
    }
}
