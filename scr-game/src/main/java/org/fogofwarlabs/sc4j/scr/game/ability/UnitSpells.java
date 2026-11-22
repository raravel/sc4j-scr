package org.fogofwarlabs.sc4j.scr.game.ability;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrWeaponIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrOrderTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrWeaponTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.*;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerOrders;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerPlayerPhase;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerTargets;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerUnits;
import org.fogofwarlabs.sc4j.scr.game.combat.UnitDamage;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.order.OrderPool;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.CommandErrorEvents;
import org.fogofwarlabs.sc4j.scr.game.production.UnitMorphing;
import org.fogofwarlabs.sc4j.scr.game.production.UnitResearch;
import org.fogofwarlabs.sc4j.scr.game.resource.UnitHarvesting;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffect;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.fogofwarlabs.sc4j.scr.game.visibility.SpriteVisibility;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Casts spell orders and applies their weapon hits and status effects.
 */
public final class UnitSpells {
    /**
     * The {@code units.dat} unknown-byte value that excludes a type from Spider Mine targeting.
     */
    public static final int HOVERING_UNKNOWN_BYTE = 0xC1;

    private static final int BYTE_MASK = 0xFF;
    private static final int FIXED_POINT_FRACTION_BITS = 8;
    private static final int COMPUTER_SPELL_COUNTDOWN_DURATION = 1;
    private static final int BLINDNESS_TILE_UPDATE_DELAY = 1;

    /**
     * The half sizes of the square unit searches of the area spells.
     */
    private static final int RECALL_RADIUS = 64;
    private static final int ENSNARE_RADIUS = 64;
    private static final int PLAGUE_RADIUS = 64;
    private static final int ACID_SPORE_RADIUS = 64;
    private static final int STASIS_FIELD_RADIUS = 48;
    private static final int MAELSTROM_RADIUS = 48;
    /**
     * Irradiate searches {@code 160} pixels around the irradiated unit and damages units within {@code 32} pixels of it.
     */
    private static final int IRRADIATE_SEARCH_RADIUS = 160;
    private static final int IRRADIATE_RANGE = 32;
    /**
     * The half size of the placement search of a Broodling off unwalkable terrain.
     */
    private static final int BROODLING_PLACEMENT_RADIUS = 32;
    /**
     * The number of Broodlings Spawn Broodlings creates.
     */
    private static final int BROODLING_COUNT = 2;
    /**
     * The offset of the first Broodling candidate from the target's position, on both axes.
     */
    private static final int BROODLING_OFFSET = 2;
    /**
     * The step from one Broodling's position to the next Broodling's candidate, on both axes.
     */
    private static final int BROODLING_STEP = 4;
    /**
     * The distance from the order target within which a Vulture places its Spider Mine.
     */
    private static final int MINE_PLACEMENT_DISTANCE = 20;
    /**
     * A Spider Mine chases a target within this distance and explodes within the second one.
     */
    private static final int SPIDER_MINE_CHASE_RANGE = 576;
    private static final int SPIDER_MINE_EXPLOSION_RANGE = 30;
    /**
     * The largest heading difference, in direction units, at which a caster counts as facing its target.
     */
    private static final int CAST_FACING_TOLERANCE = 5;

    /**
     * The status timer reloads of Lockdown, Stasis Field, Maelstrom and Plague.
     */
    private static final int LOCKDOWN_DURATION = 131;
    private static final int STASIS_FIELD_DURATION = 131;
    private static final int MAELSTROM_DURATION = 22;
    private static final int PLAGUE_DURATION = 75;
    /**
     * The movement speed decrease of Ensnare.
     */
    private static final int ENSNARE_SPEED_DECREASE = 75;
    /**
     * Defensive Matrix absorbs {@code 250} hit points (fixed point, {@code << 8}) for {@code 168} frames.
     */
    private static final int DEFENSIVE_MATRIX_HEALTH = 250;
    private static final int DEFENSIVE_MATRIX_DURATION = 168;
    /**
     * The timer reload of an acid spore slot.
     */
    private static final int ACID_SPORE_DURATION = 150;
    /**
     * The removal timers of the spell units.
     */
    private static final int DARK_SWARM_DURATION = 900;
    private static final int DISRUPTION_WEB_DURATION = 360;
    /**
     * The order timer of an Arbiter between the Recall cast and the teleport.
     */
    private static final int RECALL_DELAY = 22;
    /**
     * The ground weapon cooldown a new Spider Mine waits before it burrows.
     */
    private static final int SPIDER_MINE_BURROW_DELAY = 60;
    /**
     * The order timer of a unit whose disabling effect ends.
     */
    private static final int DISABLE_END_ORDER_TIMER = 15;
    /**
     * The energy (without its fraction bits) that Consume gives its caster.
     */
    private static final int CONSUME_ENERGY = 50;
    /**
     * The hit direction that Feedback passes to its weapon damage.
     */
    private static final int FEEDBACK_HIT_HEADING = 1;
    /**
     * The hit direction that Irradiate passes to its weapon damage.
     */
    private static final int IRRADIATE_HIT_HEADING = 0;
    /**
     * The weapon damage divisor of Feedback and Irradiate: their damage is not divided.
     */
    private static final int FULL_DAMAGE_DIVISOR = 1;
    /**
     * Plague deals its weapon damage spread over this many status timer updates.
     */
    private static final int PLAGUE_DAMAGE_DIVISOR = 76;
    /**
     * An EMP Shockwave target of a computer caster needs this energy as a spellcaster, or these shields.
     */
    private static final int EMP_TARGET_MINIMUM_ENERGY = 100;
    private static final int EMP_TARGET_MINIMUM_SHIELDS = 100;
    /**
     * The Irradiate owner of a unit whose Irradiate ended: the first index past the playable players.
     */
    private static final int NO_IRRADIATE_OWNER = 8;

    /**
     * The random sources: the spell cooldown, the Recall cast and arrival sounds and the hallucination direction.
     */
    private static final int SPELL_COOLDOWN_RANDOM_SOURCE = 49;
    private static final int RECALL_CAST_SOUND_RANDOM_SOURCE = 17;
    private static final int RECALL_ARRIVAL_SOUND_RANDOM_SOURCE = 18;
    private static final int HALLUCINATION_DIRECTION_RANDOM_SOURCE = 32;
    /**
     * The random part of the spell cooldown: these low bits of the draw, minus {@code 1}.
     */
    private static final int SPELL_COOLDOWN_RANDOM_MASK = 3;
    /**
     * The number of variants of the Recall cast sound and of the Recall arrival sound.
     */
    private static final int RECALL_SOUND_VARIANTS = 2;
    /**
     * The maximum random direction index of a hallucination.
     */
    private static final int HALLUCINATION_LAST_DIRECTION = 32;
    /**
     * The number of hallucinations a cast creates.
     */
    private static final int HALLUCINATION_COPIES = 2;
    /**
     * The hangar list index of the units outside the hangar; the list of index {@code 0} holds the units inside.
     */
    private static final int OUTSIDE_HANGAR_LIST = 1;

    /**
     * The order states of a spell order: started, approaching the target, casting.
     */
    private static final int SPELL_STATE_START = 0;
    private static final int SPELL_STATE_APPROACHING = 1;
    private static final int SPELL_STATE_CASTING = 2;
    /**
     * The order states of Place Mine: started, moving to the site.
     */
    private static final int PLACE_MINE_STATE_START = 0;
    private static final int PLACE_MINE_STATE_MOVING = 1;
    /**
     * The order states of a Spider Mine: new, waiting, burrowing, burrowed, unburrowing, chasing, exploding.
     */
    private static final int SPIDER_MINE_STATE_NEW = 0;
    private static final int SPIDER_MINE_STATE_WAITING = 1;
    private static final int SPIDER_MINE_STATE_BURROWING = 2;
    private static final int SPIDER_MINE_STATE_BURROWED = 3;
    private static final int SPIDER_MINE_STATE_UNBURROWING = 4;
    private static final int SPIDER_MINE_STATE_CHASING = 5;
    private static final int SPIDER_MINE_STATE_EXPLODING = 6;
    /**
     * The order states of Recall: started, waiting for the teleport.
     */
    private static final int RECALL_STATE_START = 0;
    private static final int RECALL_STATE_TELEPORTING = 1;

    /**
     * The overlay sizes of {@link #getSpriteSize}; the small, medium and large images follow each other.
     */
    private static final int SMALL_OVERLAY = 0;
    private static final int MEDIUM_OVERLAY = 1;
    private static final int LARGE_OVERLAY = 2;
    /**
     * The Acid Spore overlays: the number of stages of each size, and the offset of the last large overlay from the first small one.
     */
    static final int ACID_SPORE_STAGES = 4;
    private static final int ACID_SPORE_LAST_IMAGE_OFFSET = ACID_SPORE_STAGES * (LARGE_OVERLAY + 1) - 1;
    /**
     * The number of acid spores that advance the Acid Spore overlay by {@code 1} stage.
     */
    static final int ACID_SPORES_PER_STAGE = 2;
    /**
     * The sprite elevations of the Ensnare, Stasis Field and Maelstrom effects and of a spell field unit.
     */
    private static final int ENSNARE_ELEVATION = 19;
    private static final int STASIS_FIELD_ELEVATION = 17;
    private static final int MAELSTROM_ELEVATION = 17;
    private static final int SPELL_FIELD_ELEVATION = 11;

    /**
     * The {@code stat_txt} ids of the technology target test and of the cast check without a target.
     */
    private static final int TEXT_CANNOT_TARGET_BUILDING = 0x36D;
    private static final int TEXT_MUST_TARGET_UNIT = 0x36E;
    private static final int TEXT_DEFENSIVE_MATRIX_NO_TARGET = 0x370;
    private static final int TEXT_INVALID_CONSUME_TARGET = 0x381;
    private static final int TEXT_HALLUCINATION_NO_TARGET = 0x383;
    private static final int TEXT_TARGET_IN_STASIS = 0x384;
    private static final int TEXT_INVALID_MINE_SITE = 0x386;
    private static final int TEXT_MIND_CONTROL_NO_TARGET = 0x52E;
    private static final int TEXT_MIND_CONTROL_OWN_UNIT = 0x52F;
    private static final int TEXT_INVALID_FEEDBACK_TARGET = 0x532;

    /**
     * Returns whether a computer caster's cast is redundant: the target is irradiated or, by the order's weapon, already carries the effect of the
     * same kind (disabled for Lockdown and Maelstrom, parasites, Ensnare, Plague, blindness).
     *
     * @param target the cast target
     * @param weapon the weapon of the spell order
     * @return {@code true} if the cast is dropped, {@code false} otherwise
     */
    private static boolean isComputerCastRedundant(@NotNull Unit target, int weapon) {
        boolean irradiated = target.getIrradiateTimer() != 0;

        return switch (ScrWeaponIds.orNone(weapon)) {
            case ScrWeaponIds.LOCKDOWN, ScrWeaponIds.MAELSTROM -> irradiated || UnitOrderEligibility.isDisabled(target);
            case ScrWeaponIds.IRRADIATE -> irradiated;
            case ScrWeaponIds.PARASITE -> irradiated || target.getParasiteFlags() != 0;
            case ScrWeaponIds.ENSNARE -> irradiated || target.getSpeedDecreaseByte() != 0;
            case ScrWeaponIds.PLAGUE -> irradiated || target.getPlagueTimer() != 0;
            case ScrWeaponIds.OPTICAL_FLARE -> irradiated || target.getBlindnessState() != 0;
            default -> false;
        };
    }

    private static @Nullable SpriteImage findSpriteImage(@NotNull Sprite sprite, int first, int last) {
        for (SpriteImage image : sprite.getImages()) {
            int type = image.getImageTypeId();

            if (type >= first && type <= last) {
                return image;
            }
        }

        return null;
    }

    private static @Nullable SpriteImage findImage(@NotNull Unit unit, int first, int last) {
        SpriteImage image = findSpriteImage(unit.requireSprite(), first, last);

        if (image == null && unit.getSubunit() != null) {
            image = findSpriteImage(unit.getSubunit().requireSprite(), first, last);
        }

        return image;
    }

    /**
     * Returns the default text of the cast check for a missing target.
     *
     * @param technology the {@code techdata.dat} index
     * @return the text of Hallucination, Mind Control, Feedback or Defensive Matrix, {@link CommandErrorEvents#NO_ERROR_TEXT} for other technologies
     */
    private static int getDefaultCastErrorText(int technology) {
        return switch (ScrTechIds.requireValid(technology)) {
            case ScrTechIds.HALLUCINATION -> TEXT_HALLUCINATION_NO_TARGET;
            case ScrTechIds.MIND_CONTROL -> TEXT_MIND_CONTROL_NO_TARGET;
            case ScrTechIds.FEEDBACK -> TEXT_INVALID_FEEDBACK_TARGET;
            case ScrTechIds.DEFENSIVE_MATRIX -> TEXT_DEFENSIVE_MATRIX_NO_TARGET;
            default -> CommandErrorEvents.NO_ERROR_TEXT;
        };
    }

    /**
     * Returns whether a fog type is a targetable type: a type below {@link ScrUnitIds#NONE} without the invincible type flag.
     *
     * @param units the {@code units.dat} table
     * @param type  the fog type
     * @return {@code true} if the type is a targetable type, {@code false} otherwise
     */
    private static boolean isTargetableType(@NotNull ScrUnitTypeView units, int type) {
        return type < ScrUnitIds.NONE && (units.getFlags(type) & UnitTypeFlags.INVINCIBLE) == 0;
    }

    /**
     * Returns whether Mind Control rejects the type: Spider Mine, Larva, Egg, Cocoon, Interceptor, Scarab and Lurker Egg.
     *
     * @param type the unit type, the base type of an added type ({@link Game#getBaseType(int)})
     * @return {@code true} if Mind Control rejects the type, {@code false} otherwise
     */
    private static boolean isMindControlExempt(int type) {
        return type == ScrUnitIds.TERRAN_VULTURE_SPIDER_MINE || type == ScrUnitIds.ZERG_LARVA || type == ScrUnitIds.ZERG_EGG ||
               type == ScrUnitIds.ZERG_COCOON || type == ScrUnitIds.PROTOSS_INTERCEPTOR || type == ScrUnitIds.PROTOSS_SCARAB ||
               type == ScrUnitIds.ZERG_LURKER_EGG;
    }

    /**
     * Returns whether the type is the Larva, an Egg or a Lurker Egg.
     *
     * @param type the unit type, the base type of an added type ({@link Game#getBaseType(int)})
     * @return {@code true} if the type is the Larva, an Egg or a Lurker Egg, {@code false} otherwise
     */
    private static boolean isLarvaEggOrLurkerEgg(int type) {
        return type == ScrUnitIds.ZERG_LARVA || type == ScrUnitIds.ZERG_EGG || type == ScrUnitIds.ZERG_LURKER_EGG;
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitSpells} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitSpells(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Unlinks a Ghost and the Nuclear Missile it guides from each other.
     *
     * @param unit the unit, which is unlinked only if it is a Ghost linked to a Nuclear Missile
     */
    private void unlinkNuclearMissile(@NotNull Unit unit) {
        Unit connected = unit.getParentUnit();

        if (this.game.requireGameData().getUnitTypes().isGhost(unit.getUnitId()) && connected != null &&
            this.game.getBaseType(connected) == ScrUnitIds.TERRAN_NUCLEAR_MISSILE) {
            connected.setParentUnit(null);
            unit.setParentUnit(null);
        }
    }

    /**
     * Returns whether the caster sees its order target and has it in the spell weapon's range; both range tests compare unsigned.
     *
     * @param unit   the caster
     * @param target the order target, or {@code null} for a position target
     * @param weapon the weapon of the spell order
     * @return {@code true} if the caster sees the target and has it in range, {@code false} otherwise
     */
    private boolean isInSpellRange(@NotNull Unit unit, @Nullable Unit target, int weapon) {
        if (!this.game.getUnitTargeting().canSeeTarget(unit, unit.getOrderTargetUnit())) {
            return false;
        }

        int range = this.game.getUnitTargeting().getWeaponMaximumRange(unit, weapon);

        if (target != null) {
            return this.game.getUnitTargeting().isInRange(unit, target, range + this.game.getUnitTargeting().getTargetMovementRange(unit, target));
        }

        range += this.game.getUnitTargeting().getHaltDistance(unit) >> FIXED_POINT_FRACTION_BITS;

        return ComputerTargets.isNear(unit, range, (short) unit.getOrderTargetX(), (short) unit.getOrderTargetY());
    }

    /**
     * Applies the computer-caster test that opens the spell order check: for a caster with a computer record, a target already under the effect (or
     * no target where one is required) makes the order invalid. Lockdown and Stasis Field need a target that is not disabled, Parasite one without
     * parasites, Irradiate an unirradiated target, Plague an unplagued one and Ensnare an unensnared one; an EMP Shockwave target needs {@code 100}
     * energy as a spellcaster or {@code 100} shields.
     *
     * @param unit the caster
     * @return {@code true} if the order is invalid before the common test, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    private boolean isComputerTargetSpent(@NotNull Unit unit) {
        if (unit.getComputerRecord() == null) {
            return false;
        }

        Unit target = unit.getOrderTargetUnit();

        return switch (ScrOrderIds.orNone(unit.getPrimaryOrder())) {
            case ScrOrderIds.CAST_LOCKDOWN, ScrOrderIds.CAST_STASIS_FIELD -> target == null || UnitOrderEligibility.isDisabled(target);
            case ScrOrderIds.CAST_PARASITE -> target == null || target.getParasiteFlags() != 0;
            case ScrOrderIds.CAST_IRRADIATE -> target == null || target.getIrradiateTimer() != 0;
            case ScrOrderIds.CAST_PLAGUE -> target == null || target.getPlagueTimer() != 0;
            case ScrOrderIds.CAST_ENSNARE -> target != null && target.getSpeedDecreaseByte() != 0;
            case ScrOrderIds.CAST_EMP_SHOCKWAVE -> {
                if (target == null) {
                    yield false;
                }

                ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
                int type = target.getUnitId();
                boolean caster = UnitTypeTraitsUtils.isSpellcaster(units, target);

                if (caster && (target.getEnergy() >>> FIXED_POINT_FRACTION_BITS) >= EMP_TARGET_MINIMUM_ENERGY) {
                    yield false;
                }

                yield units.getShieldEnabled(type) == 0 || (target.getShieldPoints() >> FIXED_POINT_FRACTION_BITS) < EMP_TARGET_MINIMUM_SHIELDS;
            }
            default -> false;
        };
    }

    /**
     * Validates the spell order; an {@code orders.dat} technology goes through the technology target test at the order target.
     *
     * @param unit the caster
     * @return {@code true} if the current spell order may be cast, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    boolean isSpellOrderValid(@NotNull Unit unit) {
        ScrOrderTypeView orders = this.game.requireGameData().getOrderTypes();
        int order = unit.getPrimaryOrder();
        Unit target = unit.getOrderTargetUnit();

        if (orders.getUseWeaponTargeting(order) != 0) {
            int weapon = orders.getWeapon(order);

            if (weapon == ScrWeaponIds.NONE) {
                return false;
            }

            int flags = this.game.requireGameData().getWeaponTypes().getTargetFlags(weapon);

            if (target == null) {
                return (flags & WeaponTargetFlags.TERRAIN) != 0;
            }

            if (!this.game.getUnitTargeting().canWeaponTarget(weapon, target)) {
                return false;
            }

            return (flags & WeaponTargetFlags.OWN) == 0 || target.getOwnerIndex() == unit.getOwnerIndex();
        }

        int technology = orders.getTechnology(order);

        if (technology >= ScrTechIds.NONE) {
            return true;
        }

        return this.getTechnologyTargetError(unit, technology, target, ScrUnitIds.NONE, unit.getOrderTargetX(), unit.getOrderTargetY()) ==
               CommandErrorEvents.NO_ERROR_TEXT;
    }

    /**
     * Applies the technology target test ({@link #getTechnologyTargetError}) for a unit target without a fog type, at the target's position, as the
     * Consume hit and the computer searches call it.
     *
     * @param unit       the caster
     * @param technology the {@code techdata.dat} index
     * @param target     the target
     * @return {@code true} if the technology may target the unit, {@code false} otherwise
     */
    public boolean isTechnologyTargetValid(@NotNull Unit unit, int technology, @NotNull Unit target) {
        Sprite sprite = target.getSpriteOrNull();
        int positionX = sprite == null ? 0 : sprite.getX();
        int positionY = sprite == null ? 0 : sprite.getY();

        return this.getTechnologyTargetError(unit, technology, target, ScrUnitIds.NONE, positionX, positionY) == CommandErrorEvents.NO_ERROR_TEXT;
    }

    /**
     * Sets a disabled unit's stopped state, clears incompatible targets and shows the empty button set.
     *
     * @param unit             the disabled unit
     * @param instructionLimit the order and script budget
     * @throws NullPointerException if the world was built without game data
     */
    private void setDisabled(@NotNull Unit unit, int instructionLimit) {
        Unit subunit = unit.getSubunit();

        if (subunit != null) {
            subunit.setStatus(subunit.getStatus() | UnitStatusFlags.DISABLED);
        }

        this.game.getEngineGame().getMovement().stop(unit);
        this.unlinkNuclearMissile(unit);

        if (this.game.requireGameData().getUnitTypes().isCarrier(unit.getUnitId())) {
            for (Unit fighter = unit.getFirstHangarUnit(true); fighter != null; fighter = fighter.getNextHangarUnit()) {
                this.game.getOrders().issueUntargeted(fighter, ScrOrderIds.INTERCEPTOR_RETURN, instructionLimit);
            }
        }

        int order = unit.getPrimaryOrder();

        if (order == ScrOrderIds.REPAIR || order == ScrOrderIds.DRONE_LAND) {
            unit.setPrimaryOrderState(0);
        }

        if (this.game.requireGameData().getOrderTypes().getDisablingKeepsTarget(order) == 0) {
            unit.setOrderTargetUnit(null);
        }

        this.game.getIscriptExecutor().runToIdle(unit, instructionLimit);
        UnitMorphing.writeButtonSet(this.game.requireGameData().getUnitTypes(), unit, ButtonSetIds.EMPTY);
    }

    /**
     * @param unit the unit
     * @return the overlay size of the unit's type: medium first, otherwise large below {@link ScrUnitIds#NONE}, otherwise small
     * @throws NullPointerException if the world was built without game data
     */
    int getSpriteSize(@NotNull Unit unit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();
        int abilities = units.getFlags(unitType);

        if ((abilities & UnitTypeFlags.MEDIUM_OVERLAYS) != 0) {
            return MEDIUM_OVERLAY;
        }

        if (units.hasEntry(unitType) && (abilities & UnitTypeFlags.LARGE_OVERLAYS) != 0) {
            return LARGE_OVERLAY;
        }

        return SMALL_OVERLAY;
    }

    /**
     * Applies the technology target test.
     * <p>
     * Dark Swarm, EMP Shockwave, Plague, Ensnare, Psionic Storm, Recall, Stasis Field, Disruption Web, Maelstrom and Scanner Sweep are always valid.
     * Spider Mines need a walkable or creep tile at the position. Without a target and without a fog type the target must be a unit; an invincible
     * target, or a fog type that is no targetable type, is an invalid target. With a fog type, Defensive Matrix and Irradiate reject it as a
     * building, Infestation accepts only a Command Center and Consume rejects it. With a unit target, a target in stasis is rejected; then Defensive
     * Matrix, Irradiate, Restoration and Optical Flare reject buildings; Hallucination rejects Interceptors and buildings; Infestation needs an
     * infestable target; Consume an own Zerg non-building that is no Larva; Mind Control a different-owner non-building that is none of the exempt
     * types ({@link #isMindControlExempt}); Feedback a non-building spellcaster; other technologies are invalid.
     *
     * @param caster     the casting unit
     * @param technology the {@code techdata.dat} index
     * @param target     the target unit, or {@code null}
     * @param fogType    the fog-covered target type, or {@link ScrUnitIds#NONE} for none
     * @param positionX  the target X, read by Spider Mines
     * @param positionY  the target Y, read by Spider Mines
     * @return {@link CommandErrorEvents#NO_ERROR_TEXT} when valid, otherwise the {@code stat_txt} error text id
     * @throws NullPointerException if the world was built without game data
     */
    public int getTechnologyTargetError(@NotNull Unit caster, int technology, @Nullable Unit target, int fogType, int positionX, int positionY) {
        int technologyType = ScrTechIds.orNone(technology);

        switch (technologyType) {
            case ScrTechIds.EMP_SHOCKWAVE, ScrTechIds.SCANNER_SWEEP, ScrTechIds.DARK_SWARM, ScrTechIds.PLAGUE, ScrTechIds.ENSNARE,
                 ScrTechIds.PSIONIC_STORM, ScrTechIds.RECALL, ScrTechIds.STASIS_FIELD, ScrTechIds.DISRUPTION_WEB, ScrTechIds.MAELSTROM -> {
                return CommandErrorEvents.NO_ERROR_TEXT;
            }
            case ScrTechIds.SPIDER_MINES -> {
                return this.game.getMap().hasTileFlagsAtPosition(positionX, positionY, MapTileFlags.WALKABLE | MapTileFlags.HAS_CREEP, 0)
                       ? CommandErrorEvents.NO_ERROR_TEXT
                       : TEXT_INVALID_MINE_SITE;
            }
            default -> {
            }
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if (target == null && fogType == ScrUnitIds.NONE) {
            return TEXT_MUST_TARGET_UNIT;
        }

        if ((target == null || (target.getStatus() & UnitStatusFlags.INVINCIBLE) != 0) && !isTargetableType(units, fogType)) {
            return CommandErrorEvents.TEXT_INVALID_SPELL_TARGET;
        }

        if (fogType != ScrUnitIds.NONE) {
            return switch (technologyType) {
                case ScrTechIds.DEFENSIVE_MATRIX, ScrTechIds.IRRADIATE -> TEXT_CANNOT_TARGET_BUILDING;
                case ScrTechIds.INFESTATION -> fogType == ScrUnitIds.TERRAN_COMMAND_CENTER ? CommandErrorEvents.NO_ERROR_TEXT
                                                                                           : UnitInfestation.TEXT_CANNOT_INFEST;
                case ScrTechIds.CONSUME -> TEXT_INVALID_CONSUME_TARGET;
                default -> CommandErrorEvents.TEXT_INVALID_SPELL_TARGET;
            };
        }

        if (target.getStasisTimer() != 0) {
            return TEXT_TARGET_IN_STASIS;
        }

        int type = target.getUnitId();
        int baseType = this.game.getBaseType(type);
        int abilities = units.getFlags(type);
        boolean building = (abilities & UnitTypeFlags.BUILDING) != 0;

        return switch (technologyType) {
            case ScrTechIds.DEFENSIVE_MATRIX, ScrTechIds.IRRADIATE,
                 ScrTechIds.RESTORATION, ScrTechIds.OPTICAL_FLARE -> building ? TEXT_CANNOT_TARGET_BUILDING : CommandErrorEvents.NO_ERROR_TEXT;
            case ScrTechIds.HALLUCINATION -> baseType == ScrUnitIds.PROTOSS_INTERCEPTOR
                                             ? CommandErrorEvents.TEXT_INVALID_SPELL_TARGET
                                             : building ? TEXT_CANNOT_TARGET_BUILDING : CommandErrorEvents.NO_ERROR_TEXT;
            case ScrTechIds.INFESTATION -> this.game.getUnitInfestation().canBeInfested(target) ? CommandErrorEvents.NO_ERROR_TEXT
                                                                                                : UnitInfestation.TEXT_CANNOT_INFEST;
            case ScrTechIds.CONSUME -> building || target.getOwnerIndex() != caster.getOwnerIndex() ||
                                       (units.getGroupFlags(type) & UnitGroupFlags.ZERG) == 0 || baseType == ScrUnitIds.ZERG_LARVA
                                       ? TEXT_INVALID_CONSUME_TARGET
                                       : CommandErrorEvents.NO_ERROR_TEXT;
            case ScrTechIds.MIND_CONTROL -> target.getOwnerIndex() == caster.getOwnerIndex()
                                            ? TEXT_MIND_CONTROL_OWN_UNIT
                                            : building ? TEXT_CANNOT_TARGET_BUILDING
                                                       : isMindControlExempt(baseType) ? CommandErrorEvents.TEXT_INVALID_SPELL_TARGET
                                                                                       : CommandErrorEvents.NO_ERROR_TEXT;
            case ScrTechIds.FEEDBACK -> building ? TEXT_CANNOT_TARGET_BUILDING
                                                 : UnitTypeTraitsUtils.isSpellcaster(units, target) ? CommandErrorEvents.NO_ERROR_TEXT
                                                                                                    : TEXT_INVALID_FEEDBACK_TARGET;
            default -> CommandErrorEvents.TEXT_INVALID_SPELL_TARGET;
        };
    }

    private int getEnergyCost(int technology) {
        return this.game.requireGameData().getTechTypes().getEnergyCost(technology) << FIXED_POINT_FRACTION_BITS;
    }


    // Orders

    /**
     * Updates the spell order: approaches the target, then pays energy and starts the casting animation; the {@code castspell} script operation
     * launches the spell weapon.
     * <p>
     * The completed casting branch clears its signal before finishing the order.
     *
     * @param unit             the caster
     * @param instructionLimit the order and script budget
     * @throws UnsupportedOperationException if a computer spell operation has no player phase
     * @throws IllegalStateException         if the target or the unit has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    public void updateSpell(@NotNull Unit unit, int instructionLimit) {
        Unit target = unit.getOrderTargetUnit();

        if (unit.getPrimaryOrderState() == SPELL_STATE_CASTING) {
            if ((unit.getOrderSignal() & Unit.ORDER_SIGNAL_CAST_DONE) != 0) {
                unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_CAST_DONE);
                this.game.getOrders().finishOrder(unit, instructionLimit);
            } else if (target != null) {
                Sprite targetSprite = target.requireSprite();

                unit.setOrderTargetX(targetSprite.getX());
                unit.setOrderTargetY(targetSprite.getY());
                unit.setDirectionTargetX(targetSprite.getX());
                unit.setDirectionTargetY(targetSprite.getY());
            }

            return;
        }

        if (this.isComputerTargetSpent(unit) || !this.isSpellOrderValid(unit)) {
            this.game.getInterfaceLink().getCommandErrorEvents().raiseUnitError(this.game,
                                                                                unit,
                                                                                CommandErrorEvents.TEXT_INVALID_SPELL_TARGET,
                                                                                unit.getOwnerIndex());
            this.game.getEngineGame().getMovement().stop(unit);
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        ScrOrderTypeView orders = this.game.requireGameData().getOrderTypes();
        int order = unit.getPrimaryOrder();
        int technology = orders.getTechnology(order);

        if (technology != UnitResearch.NO_TECHNOLOGY && !this.game.getUnitEnergy().hasEnergy(unit, this.getEnergyCost(technology))) {
            this.game.getInterfaceLink().getCommandErrorEvents().raiseNotEnoughEnergy(this.game, unit);
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        int weapon = orders.getWeapon(order);

        if (target != null) {
            Sprite targetSprite = target.requireSprite();

            unit.setOrderTargetX(targetSprite.getX());
            unit.setOrderTargetY(targetSprite.getY());
        }

        unit.setDirectionTargetX(unit.getOrderTargetX());
        unit.setDirectionTargetY(unit.getOrderTargetY());

        if (this.isInSpellRange(unit, target, weapon)) {
            if ((unit.getMovementFlags() & MovementFlags.BRAKING) == 0) {
                this.game.getEngineGame().getMovement().stop(unit);
            }

            if ((unit.getCooldownTimers() >>> Unit.SPELL_COOLDOWN_SHIFT) == 0 && this.game.getUnitAttack().canFireWeapon(unit, weapon) &&
                (unit.getMovementFlags() & MovementFlags.ACCELERATING) == 0 && this.game.getEngineGame().getMovement().isAtDestination(unit)) {
                // A caster with a computer record drops a cast whose target already carries the effect.
                if (unit.getComputerRecord() != null && target != null && isComputerCastRedundant(target, weapon)) {
                    this.game.getOrders().finishOrder(unit, instructionLimit);

                    return;
                }

                if (technology != UnitResearch.NO_TECHNOLOGY) {
                    this.game.getUnitEnergy().spend(unit, this.getEnergyCost(technology));
                }

                this.reportSpellCast(unit, technology, target, unit.getOrderTargetX(), unit.getOrderTargetY());

                int cooldown = this.game.getUnitAttack().getModifiedCooldown(unit, weapon) +
                               (this.game.getRandom().next(SPELL_COOLDOWN_RANDOM_SOURCE) & SPELL_COOLDOWN_RANDOM_MASK) - 1;

                unit.setCooldownTimers(unit.getCooldownTimers() & ~Unit.SPELL_COOLDOWN_MASK | (cooldown & BYTE_MASK) << Unit.SPELL_COOLDOWN_SHIFT);
                unit.setMovementFlags(unit.getMovementFlags() | MovementFlags.STARTING_ATTACK);
                unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_CAST_DONE);
                this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(), unit, orders.getAnimation(order), 0, instructionLimit);
                unit.setPrimaryOrderState(SPELL_STATE_CASTING);

                // A computer caster sets its owner's spell countdown.
                if (unit.getComputerRecord() != null) {
                    ComputerPlayerPhase phase = this.game.getComputerPlayerPhase();

                    if (phase == null) {
                        throw Exceptions.newUnsupportedOperationException("Computer spell casting requires the computer-player phase.");
                    }

                    phase.getPlayer(unit.getOwnerIndex()).setSpellCountdown(COMPUTER_SPELL_COUNTDOWN_DURATION);
                }
            }
        } else {
            if (target != null) {
                if (unit.getPrimaryOrderState() == SPELL_STATE_START) {
                    this.game.getEngineGame().getMovement().setTargetUnit(unit, target);
                } else {
                    this.game.getEngineGame().getCombat().pursueTarget(unit, target, instructionLimit);
                }
            } else if (unit.getMovementDestinationCountdown() == 0) {
                this.game.getEngineGame().getMovement().setDestination(unit, (short) unit.getOrderTargetX(), (short) unit.getOrderTargetY());
            }

            unit.setDirectionTargetX(unit.getOrderTargetX());
            unit.setDirectionTargetY(unit.getOrderTargetY());

            if (this.game.getEngineGame().getMovement().isAtDestination(unit) && (unit.getStatus() & UnitStatusFlags.IMMOVABLE) != 0) {
                this.game.getOrders().finishOrder(unit, instructionLimit);

                return;
            }
        }

        if (unit.getPrimaryOrderState() == SPELL_STATE_START) {
            unit.setPrimaryOrderState(SPELL_STATE_APPROACHING);
        }
    }

    /**
     * Applies the {@code castspell} script operation: launches the order's spell weapon when the order is still valid.
     * <p>
     * The spell-weapon lookup precedes the no-weapon and no-target returns; multiple launches keep their existing order.
     *
     * @param unit             the script unit
     * @param instructionLimit the bullet sprite budget
     * @throws NullPointerException if the world was built without game data
     */
    public void castSpell(@NotNull Unit unit, int instructionLimit) {
        int weapon = this.game.requireGameData().getOrderTypes().getWeapon(unit.getPrimaryOrder());

        if (weapon == ScrWeaponIds.NONE || this.isComputerTargetSpent(unit) || !this.isSpellOrderValid(unit)) {
            return;
        }

        this.game.getUnitAttack().attackWithWeapon(unit, weapon, instructionLimit);
    }

    /**
     * Approaches a visible spell target and reports when the caster stands in range facing it. A cast without a valid target shows the spell's
     * default text without a target, otherwise the text of the technology target test.
     *
     * @param unit             the caster
     * @param technology       the {@code techdata.dat} index
     * @param range            the cast range
     * @param instructionLimit the order budget
     * @return {@code true} if the caster stands in range facing the target, {@code false} otherwise
     * @throws IllegalStateException if the target or the unit has no sprite
     */
    private boolean moveToCastTarget(@NotNull Unit unit, int technology, int range, int instructionLimit) {
        Unit target = unit.getOrderTargetUnit();

        if (target == null || !this.isSpellOrderValid(unit)) {
            int text = target == null ? getDefaultCastErrorText(technology)
                                      : this.getTechnologyTargetError(unit,
                                                                      technology,
                                                                      target,
                                                                      ScrUnitIds.NONE,
                                                                      unit.getOrderTargetX(), unit.getOrderTargetY());

            if (text != CommandErrorEvents.NO_ERROR_TEXT) {
                this.game.getInterfaceLink().getCommandErrorEvents().raiseUnitError(this.game, unit, text, unit.getOwnerIndex());
            }

            this.game.getOrders().finishOrder(unit, instructionLimit);

            return false;
        }

        if (!this.game.getUnitEnergy().hasEnergy(unit, this.getEnergyCost(technology))) {
            this.game.getInterfaceLink().getCommandErrorEvents().raiseNotEnoughEnergy(this.game, unit);
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return false;
        }

        Sprite targetSprite = target.requireSprite();

        unit.setOrderTargetX(targetSprite.getX());
        unit.setOrderTargetY(targetSprite.getY());
        unit.setDirectionTargetX(targetSprite.getX());
        unit.setDirectionTargetY(targetSprite.getY());

        if (this.game.getUnitTargeting().canSeeTarget(unit, target)) {
            if ((unit.getMovementFlags() & MovementFlags.ACCELERATING) != 0) {
                if ((target.getMovementFlags() & MovementFlags.ACCELERATING) == 0 ||
                    Math.abs((byte) (target.getTargetHeading() - unit.getTargetHeading())) > FlingyMotionUtils.DIRECTION_EIGHTH_TURN) {
                    range += this.game.getUnitTargeting().getHaltDistance(unit) >> FIXED_POINT_FRACTION_BITS;
                }
            }

            if (this.game.getUnitTargeting().isInRange(unit, target, range)) {
                this.game.getEngineGame().getMovement().stop(unit);

                if (unit.getPositionX() == unit.getDirectionTargetX() && unit.getPositionY() == unit.getDirectionTargetY()) {
                    return true;
                }

                Sprite sprite = unit.requireSprite();
                int direction = FlingyMotionUtils.calculateDirection(unit.getDirectionTargetX() - sprite.getX(),
                                                                     unit.getDirectionTargetY() - sprite.getY());

                return Math.abs((byte) (unit.getHeading() - direction)) <= CAST_FACING_TOLERANCE;
            }
        }

        if (this.game.getEngineGame().getMovement().isAtDestination(unit) && (unit.getStatus() & UnitStatusFlags.IMMOVABLE) != 0) {
            this.game.getEngineGame().getMovement().stop(unit);
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return false;
        }

        this.game.getEngineGame().getCombat().pursueTarget(unit, target, instructionLimit);

        return false;
    }

    /**
     * Creates a Scanner Sweep, spending energy only after successful creation.
     * <p>
     * A represented creation failure still finishes the order without spending energy; energy is spent only after creation and completion return.
     *
     * @param unit             the Comsat Station
     * @param instructionLimit the creation, completion and order budget
     */
    public void updateScannerSweep(@NotNull Unit unit, int instructionLimit) {
        int cost = this.getEnergyCost(ScrTechIds.SCANNER_SWEEP);

        if (!this.game.getUnitEnergy().hasEnergy(unit, cost)) {
            this.game.getInterfaceLink().getCommandErrorEvents().raiseNotEnoughEnergy(this.game, unit);
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        int creationType = ScrUnitIds.SPELL_SCANNER_SWEEP;
        int creationX = (short) unit.getOrderTargetX();
        int creationY = (short) unit.getOrderTargetY();
        int creationOwner = unit.getOwnerIndex();

        Unit scan = this.createCompletedUnit(creationType, creationX, creationY, creationOwner, false, creationOwner, instructionLimit);

        this.game.getOrders().finishOrder(unit, instructionLimit);

        if (scan != null) {
            this.game.getUnitEnergy().spend(unit, cost);
            this.reportSpellCast(unit, ScrTechIds.SCANNER_SWEEP, null, creationX, creationY);
            this.playSound(SoundIds.COMSAT_SCANNER_SWEEP, unit);
        }
    }

    /**
     * Kills a Scanner Sweep when its script signals the end.
     * <p>
     * An unset completion signal returns before death processing.
     *
     * @param unit             the Scanner Sweep
     * @param instructionLimit the order budget
     */
    public void updateScanner(@NotNull Unit unit, int instructionLimit) {
        if ((unit.getOrderSignal() & Unit.ORDER_SIGNAL_ANIMATION_DONE) != 0) {
            unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_ANIMATION_DONE);
            this.game.getUnitLifecycle().kill(unit, instructionLimit);
        }
    }

    /**
     * Moves a Vulture to place a Spider Mine; a computer-controlled Vulture stopped short of the site gives up.
     * <p>
     * An unsuccessful creation returns without reducing the mine count; the count changes only after a mine has been created.
     *
     * @param unit             the Vulture
     * @param instructionLimit the creation, completion and order budget
     * @throws IllegalStateException if the target has no sprite
     */
    public void updatePlaceMine(@NotNull Unit unit, int instructionLimit) {
        if (unit.getPrimaryOrderState() == PLACE_MINE_STATE_START) {
            Unit target = unit.getOrderTargetUnit();

            if (target != null) {
                if (!this.game.getUnitTargetMovement().moveToTargetReset(unit, target)) {
                    return;
                }

                Sprite targetSprite = target.requireSprite();

                unit.setDirectionTargetX(targetSprite.getX());
                unit.setDirectionTargetY(targetSprite.getY());
                unit.setOrderTargetX(targetSprite.getX());
                unit.setOrderTargetY(targetSprite.getY());
            } else {
                this.game.getEngineGame().getMovement().setDestination(unit, (short) unit.getOrderTargetX(), (short) unit.getOrderTargetY());
                unit.setDirectionTargetX(unit.getOrderTargetX());
                unit.setDirectionTargetY(unit.getOrderTargetY());
            }

            unit.setPrimaryOrderState(PLACE_MINE_STATE_MOVING);

            return;
        }

        if (!this.game.getUnitTechnology().canUseTechnology(unit, ScrTechIds.SPIDER_MINES) ||
            this.game.getEngineGame().getMovement().isAtDestination(unit) && (unit.getStatus() & UnitStatusFlags.IMMOVABLE) != 0) {
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        int targetX = (short) unit.getOrderTargetX();
        int targetY = (short) unit.getOrderTargetY();
        int horizontal = (targetX << FIXED_POINT_FRACTION_BITS) - unit.getFixedPositionX();
        int vertical = (targetY << FIXED_POINT_FRACTION_BITS) - unit.getFixedPositionY();

        if (RegionPathSearch.calculateDistance(0, horizontal, 0, vertical) >> FIXED_POINT_FRACTION_BITS > MINE_PLACEMENT_DISTANCE) {
            if (unit.getComputerRecord() != null && ComputerOrders.getArrival(unit) != ComputerOrders.ARRIVAL_AWAY) {
                this.game.getOrders().finishOrder(unit, instructionLimit);
            }

            return;
        }

        this.game.getUnitPointMovement().stopAndFaceDestination(unit);

        int creationType = ScrUnitIds.TERRAN_VULTURE_SPIDER_MINE;
        int creationX = (short) unit.getOrderTargetX();
        int creationY = (short) unit.getOrderTargetY();
        int creationOwner = unit.getOwnerIndex();

        Unit mine = this.createCompletedUnit(creationType, creationX, creationY, creationOwner, false, creationOwner, instructionLimit);

        if (mine != null) {
            mine.resetDirection(this.game, unit.getHeading());
            unit.setSpiderMineCount(unit.getSpiderMineCount() - 1);
        }

        this.game.getOrders().finishOrder(unit, instructionLimit);
    }

    /**
     * Burrows a Spider Mine, then waits for an enemy ground unit to chase and explode beside.
     *
     * @param unit             the Spider Mine
     * @param instructionLimit the order and script budget
     * @throws IllegalStateException if the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void updateSpiderMine(@NotNull Unit unit, int instructionLimit) {
        Sprite sprite = unit.requireSprite();
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if (unit.getPrimaryOrderState() == SPIDER_MINE_STATE_NEW) {
            unit.setCooldownTimers(unit.getCooldownTimers() & ~Unit.GROUND_COOLDOWN_MASK | SPIDER_MINE_BURROW_DELAY << Unit.GROUND_COOLDOWN_SHIFT);
            unit.setPrimaryOrderState(SPIDER_MINE_STATE_WAITING);
        }

        if (unit.getPrimaryOrderState() == SPIDER_MINE_STATE_WAITING && (unit.getCooldownTimers() >>> Unit.GROUND_COOLDOWN_SHIFT & BYTE_MASK) == 0) {
            this.game.getIscriptExecutor().runSpriteAnimation(sprite, unit, IscriptExecutor.ANIMATION_BURROW, 0, instructionLimit);
            unit.setStatus(unit.getStatus() | UnitStatusFlags.NO_COLLIDE);
            unit.setPrimaryOrderState(SPIDER_MINE_STATE_BURROWING);
        }

        if (unit.getPrimaryOrderState() == SPIDER_MINE_STATE_BURROWING && (unit.getOrderSignal() & Unit.ORDER_SIGNAL_ANIMATION_DONE) != 0) {
            unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_ANIMATION_DONE);

            if ((unit.getStatus() & UnitStatusFlags.HALLUCINATION) == 0) {
                this.game.getUnitBurrowing().burrowSpiderMine(unit);
            }

            unit.setPrimaryOrderState(SPIDER_MINE_STATE_BURROWED);
        }

        if (unit.getPrimaryOrderState() == SPIDER_MINE_STATE_BURROWED && (unit.getStatus() & UnitStatusFlags.CANNOT_ATTACK) == 0) {
            int range = TerrainTileset.TILE_SIZE * this.game.getUnitOrders().getTargetAcquisitionRange(unit);
            Unit target = this.game.getUnitTargeting().findNearestUnit(unit,
                                                                       sprite.getX() - range, sprite.getY() - range,
                                                                       sprite.getX() + range, sprite.getY() + range,
                                                                       candidate -> candidate != unit &&
                                                                                    this.game.getEngineGame().getCombat().isEnemy(unit, candidate) &&
                                                                                    (candidate.getStatus() & UnitStatusFlags.INVINCIBLE) == 0 &&
                                                                                    !UnitQueries.isGroundedBuilding(candidate) &&
                                                                                    (candidate.getStatus() & UnitStatusFlags.FLYING) == 0 &&
                                                                                    units.getUnknownByte(candidate.getUnitId()) != HOVERING_UNKNOWN_BYTE);

            if (target != null) {
                unit.setOrderTargetUnit(target);
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, unit, IscriptExecutor.ANIMATION_UN_BURROW, 0, instructionLimit);
                sprite.setFlags(sprite.getFlags() & ~Sprite.FLAG_BURROWED);

                ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.NOTHING);
                unit.setPrimaryOrderState(SPIDER_MINE_STATE_UNBURROWING);
            }
        }

        Unit target = unit.getOrderTargetUnit();

        if (unit.getPrimaryOrderState() == SPIDER_MINE_STATE_UNBURROWING) {
            if ((unit.getOrderSignal() & Unit.ORDER_SIGNAL_ANIMATION_DONE) != 0) {
                unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_ANIMATION_DONE);
                sprite.setElevationLevel(units.getElevationLevel(unit.getUnitId()));
                unit.setStatus(unit.getStatus() & ~UnitStatusFlags.NO_COLLIDE);

                if (target != null) {
                    this.game.getUnitHangar().moveAheadOfTarget(unit, target);
                    unit.setPrimaryOrderState(SPIDER_MINE_STATE_CHASING);
                } else {
                    unit.setPrimaryOrderState(SPIDER_MINE_STATE_WAITING);
                }
            }
        } else if (unit.getPrimaryOrderState() == SPIDER_MINE_STATE_CHASING) {
            this.game.getUnitBurrowing().leaveBurrow(unit);

            if (target != null && this.game.getUnitTargeting().isInRange(unit, target, SPIDER_MINE_CHASE_RANGE)) {
                this.game.getUnitHangar().moveAheadOfTarget(unit, target);

                if (this.game.getUnitTargeting().isInRange(unit, target, SPIDER_MINE_EXPLOSION_RANGE)) {
                    unit.setOrderTargetX(sprite.getX());
                    unit.setOrderTargetY(sprite.getY());
                    unit.setOrderTargetUnit(null);
                    this.game.getIscriptExecutor().runSpriteAnimation(sprite, unit, IscriptExecutor.ANIMATION_SPECIAL_STATE_1, 0, instructionLimit);
                    unit.setPrimaryOrderState(SPIDER_MINE_STATE_EXPLODING);
                } else if (this.game.getEngineGame().getMovement().isAtDestination(unit) && (unit.getStatus() & UnitStatusFlags.IMMOVABLE) != 0) {
                    this.game.getEngineGame().getMovement().stop(unit);
                    unit.setPrimaryOrderState(SPIDER_MINE_STATE_WAITING);
                }
            } else {
                this.game.getEngineGame().getMovement().stop(unit);
                unit.setPrimaryOrderState(SPIDER_MINE_STATE_WAITING);
            }
        }

        if (unit.getPrimaryOrderState() == SPIDER_MINE_STATE_EXPLODING && (unit.getOrderSignal() & Unit.ORDER_SIGNAL_ACTION_DONE) != 0) {
            this.game.getUnitLifecycle().kill(unit, instructionLimit);
        }
    }

    /**
     * Approaches the target, spends energy and installs Defensive Matrix health, timer and overlays.
     * <p>
     * Energy is spent and the target matrix health and timer are installed before overlay creation.
     *
     * @param unit             the Science Vessel
     * @param instructionLimit the order and image budget
     * @throws IllegalStateException if the unit has no sprite
     */
    public void updateDefensiveMatrix(@NotNull Unit unit, int instructionLimit) {
        if (!this.moveToCastTarget(unit,
                                   ScrTechIds.DEFENSIVE_MATRIX,
                                   this.game.getUnitVisibility().calculateSightRange(unit, true) * TerrainTileset.TILE_SIZE,
                                   instructionLimit)) {
            return;
        }

        this.game.getUnitEnergy().spend(unit, this.getEnergyCost(ScrTechIds.DEFENSIVE_MATRIX));

        Unit target = unit.requireOrderTargetUnit();

        this.reportSpellCast(unit, ScrTechIds.DEFENSIVE_MATRIX, target, unit.getOrderTargetX(), unit.getOrderTargetY());

        target.setDefensiveMatrixHealth(DEFENSIVE_MATRIX_HEALTH << FIXED_POINT_FRACTION_BITS);
        target.setDefensiveMatrixTimer(DEFENSIVE_MATRIX_DURATION);
        this.createDefensiveMatrixImage(target, instructionLimit);
        this.playSound(SoundIds.SCIENCE_VESSEL_DEFENSIVE_MATRIX, unit);

        Sprite sprite = unit.requireSprite();

        this.game.getIscriptExecutor().createImage(sprite,
                                                   sprite.getMainImage(),
                                                   ImageIds.SCIENCE_VESSEL_OVERLAY_PART2,
                                                   0, 0,
                                                   0,
                                                   unit,
                                                   instructionLimit);
        this.game.getOrders().finishOrder(unit, instructionLimit);
    }

    /**
     * Pays for Recall, waits for its delay and moves eligible units to valid positions around the caster.
     * <p>
     * Starting Recall spends energy before creating its effect. The teleporting branch returns until the delay expires.
     *
     * @param unit             the Arbiter
     * @param instructionLimit the order and sprite budget
     * @throws IllegalStateException if the order target or the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void updateRecall(@NotNull Unit unit, int instructionLimit) {
        if (unit.getPrimaryOrderState() == RECALL_STATE_START) {
            int cost = this.getEnergyCost(ScrTechIds.RECALL);

            if (!this.game.getUnitEnergy().hasEnergy(unit, cost)) {
                this.game.getInterfaceLink().getCommandErrorEvents().raiseNotEnoughEnergy(this.game, unit);
                this.game.getOrders().finishOrder(unit, instructionLimit);

                return;
            }

            this.game.getUnitEnergy().spend(unit, cost);

            Unit orderTarget = unit.getOrderTargetUnit();

            if (orderTarget != null) {
                Sprite targetSprite = orderTarget.requireSprite();

                unit.setOrderTargetX(targetSprite.getX());
                unit.setOrderTargetY(targetSprite.getY());
            }

            this.reportSpellCast(unit, ScrTechIds.RECALL, orderTarget, unit.getOrderTargetX(), unit.getOrderTargetY());
            this.createEffect(SpriteIds.RECALL_FIELD,
                              (short) unit.getOrderTargetX(), (short) unit.getOrderTargetY(),
                              unit.requireSprite().getElevationLevel() + 1,
                              instructionLimit);
            this.game.getInterfaceLink().getSoundEvents().add(SoundIds.ARBITER_RECALL_CAST +
                                                              this.game.getRandom().next(RECALL_CAST_SOUND_RANDOM_SOURCE) % RECALL_SOUND_VARIANTS,
                                                              (short) unit.getOrderTargetX(), (short) unit.getOrderTargetY(),
                                                              ScrUnitIds.NONE);
            unit.setOrderTimer(RECALL_DELAY);
            unit.setPrimaryOrderState(RECALL_STATE_TELEPORTING);

            return;
        }

        if (unit.getPrimaryOrderState() != RECALL_STATE_TELEPORTING || unit.getOrderTimer() != 0) {
            return;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int centerX = (short) unit.getOrderTargetX();
        int centerY = (short) unit.getOrderTargetY();
        Unit[] candidates = this.collectUnits(centerX - RECALL_RADIUS, centerY - RECALL_RADIUS, centerX + RECALL_RADIUS, centerY + RECALL_RADIUS);
        Sprite casterSprite = unit.requireSprite();
        int recalled = 0;

        for (Unit target : candidates) {
            if (target == unit || target.getOwnerIndex() != unit.getOwnerIndex()) {
                continue;
            }

            Sprite targetSprite = target.getSpriteOrNull();

            if (targetSprite == null || (targetSprite.getFlags() & Sprite.FLAG_HIDDEN) != 0) {
                continue;
            }

            int type = target.getUnitId();
            int baseType = this.game.getBaseType(type);
            int status = target.getStatus();

            if ((units.getFlags(type) & UnitTypeFlags.BUILDING) != 0 || (status & UnitStatusFlags.INVINCIBLE) != 0 ||
                (status & UnitStatusFlags.HALLUCINATION) != 0 || (status & UnitStatusFlags.BURROWED) != 0 || isLarvaEggOrLurkerEgg(baseType)) {
                continue;
            }

            int previousX = targetSprite.getX();
            int previousY = targetSprite.getY();

            this.game.getUnitPositioning().move(target, casterSprite.getX(), casterSprite.getY());

            int[] position = new int[UnitPositioning.POSITION_COORDINATES];

            if (!this.game.getUnitPositioning().find(target, casterSprite.getX(), casterSprite.getY(), position)) {
                this.game.getUnitPositioning().move(target, previousX, previousY);

                continue;
            }

            this.game.getUnitLifecycle().detach(target, false);
            this.game.getUnitPositioning().move(target, position[UnitPositioning.X_INDEX], position[UnitPositioning.Y_INDEX]);
            this.refreshPosition(target);

            if (baseType != ScrUnitIds.ZERG_COCOON) {
                this.game.getOrders().issueUntargeted(target, units.getReturnToIdleOrder(type), instructionLimit);
            }

            this.createEffect(SpriteIds.RECALL_FIELD,
                              position[UnitPositioning.X_INDEX], position[UnitPositioning.Y_INDEX],
                              targetSprite.getElevationLevel() + 1,
                              instructionLimit);

            this.unlinkNuclearMissile(target);

            recalled++;
        }

        if (recalled != 0) {
            this.game.getInterfaceLink().getSoundEvents().add(SoundIds.ARBITER_RECALL_ARRIVAL +
                                                              this.game.getRandom().next(RECALL_ARRIVAL_SOUND_RANDOM_SOURCE) % RECALL_SOUND_VARIANTS,
                                                              casterSprite.getX(), casterSprite.getY(),
                                                              unit.getUnitId());
        }

        this.game.getOrders().finishOrder(unit, instructionLimit);
    }

    /**
     * Refreshes a unit that stays on the visible list: turret position, vision, sprite visibility, unit finder, collision, repulse field and movement
     * state.
     *
     * @param unit the refreshed unit
     * @throws IllegalStateException         if the turret has no sprite
     * @throws UnsupportedOperationException if the game has no unit finder or the sprite pool is not initialized
     * @throws NullPointerException          if the world was built without game data
     */
    private void refreshPosition(@NotNull Unit unit) {
        Unit turret = this.game.getUnitTurret().getTurret(unit);

        if (turret != null) {
            turret.setFixedPositionX(unit.getFixedPositionX());
            turret.setFixedPositionY(unit.getFixedPositionY());

            int positionX = (short) (unit.getFixedPositionX() >> FIXED_POINT_FRACTION_BITS);
            int positionY = (short) (unit.getFixedPositionY() >> FIXED_POINT_FRACTION_BITS);

            turret.setPositionX(positionX);
            turret.setPositionY(positionY);
            this.game.getSpriteSystem().requireSpritePool().relocate(turret.requireSprite(), positionX, positionY, this.game.getMap().getHeight());
        }

        this.game.getEngineGame().getVisibility().updateSight(unit);
        this.game.getSpriteVisibility().update(unit, this.game.getMap(), this.game.getLocalVision().getComparisonMask(), Integer.MAX_VALUE);

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        UnitSpatialIndex spatialIndex = this.game.getUnitSystem().requireUnitSpatialIndex();

        spatialIndex.insert(unit, units);

        if (UnitQueries.isGroundedBuilding(unit)) {
            this.game.getUnitPositioning().setFootprintOccupied(unit.getUnitId(), unit.getPositionX(), unit.getPositionY(), true);
        }

        spatialIndex.notifyCollision(unit, units);

        if ((unit.getStatus() & UnitStatusFlags.FLYING) != 0) {
            spatialIndex.registerFlyingUnit(unit, units, this.game.getRandom());
        }

        this.game.getUnitInitializer().resetMovementState(unit);

        if (turret != null) {
            this.game.getUnitInitializer().resetMovementState(turret);
        }
    }

    /**
     * Attempts {@link #HALLUCINATION_COPIES} hallucinations, places successful copies, then spends energy and finishes the order.
     * <p>
     * Spending energy and creating the final overlay follow the attempted copies.
     *
     * @param unit             the High Templar
     * @param instructionLimit the order, creation and image budget
     * @throws UnsupportedOperationException if the unit creation context is missing
     * @throws IllegalStateException         if the hallucination or the target (its subunit when it has one) has no sprite
     */
    public void updateHallucination(@NotNull Unit unit, int instructionLimit) {
        if (!this.moveToCastTarget(unit,
                                   ScrTechIds.HALLUCINATION,
                                   this.game.getUnitVisibility().calculateSightRange(unit, true) * TerrainTileset.TILE_SIZE,
                                   instructionLimit)) {
            return;
        }

        Unit target = unit.requireOrderTargetUnit();
        UnitCreationContext context = this.game.getUnitSystem().requireUnitCreationContext();

        this.reportSpellCast(unit, ScrTechIds.HALLUCINATION, target, unit.getOrderTargetX(), unit.getOrderTargetY());

        for (int copy = 0; copy < HALLUCINATION_COPIES; copy++) {
            Unit hallucination = this.createHallucination(target, unit.getOwnerIndex(), instructionLimit);

            if (hallucination == null) {
                break;
            }

            int[] position = new int[UnitPositioning.POSITION_COORDINATES];
            Sprite hallucinationSprite = hallucination.requireSprite();

            if (!this.game.getUnitPositioning().find(hallucination, hallucinationSprite.getX(), hallucinationSprite.getY(), position)) {
                this.game.getUnitLifecycle().destroyHidden(hallucination, instructionLimit);

                break;
            }

            this.game.getUnitPositioning().move(hallucination, position[UnitPositioning.X_INDEX], position[UnitPositioning.Y_INDEX]);

            if ((hallucination.getStatus() & UnitStatusFlags.CAN_TURN) != 0) {
                int index = this.game.getRandom().next(HALLUCINATION_DIRECTION_RANDOM_SOURCE, 0, HALLUCINATION_LAST_DIRECTION);

                hallucination.resetDirection(this.game, FlingyMotionUtils.DIRECTION_THIRTY_SECOND_TURN * index);
            }

            this.game.getUnitVisibility().show(hallucination, context.isFoliageEnabled());
        }

        this.game.getUnitEnergy().spend(unit, this.getEnergyCost(ScrTechIds.HALLUCINATION));
        this.playSound(SoundIds.HIGH_TEMPLAR_HALLUCINATION, target);

        Unit carrier = target.getSubunit() != null ? target.getSubunit() : target;
        Sprite sprite = carrier.requireSprite();

        this.game.getIscriptExecutor().createImage(sprite,
                                                   sprite.getImages().isEmpty() ? null : sprite.getImages().getFirst(),
                                                   ImageIds.HALLUCINATION_HIT,
                                                   0, 0,
                                                   0,
                                                   unit,
                                                   instructionLimit);
        this.game.getOrders().finishOrder(unit, instructionLimit);
    }

    /**
     * Creates a hallucination of the source unit with the source's creation tag and counted hidden hangar copies.
     * <p>
     * Stored children precede launched children. Failed children leave the remaining attempts active; a failed parent returns after reporting the
     * pending creation message. Completed children are offered to computer players before their parent, with each hallucination as its own producer.
     *
     * @param source           the source unit
     * @param owner            the owner of the new hallucination
     * @param instructionLimit the creation and order budget
     * @return the hallucination, or {@code null} if construction failed
     * @throws UnsupportedOperationException if the unit creation context is missing or a source hangar count exceeds its list, or the unit pool or
     *                                       the unit accounting is not initialized
     * @throws IllegalStateException         if the source has no sprite
     */
    private @Nullable Unit createHallucination(@NotNull Unit source, int owner, int instructionLimit) {
        int type = source.getUnitId();
        UnitCreationContext context = this.game.getUnitSystem().requireUnitCreationContext();
        Sprite sourceSprite = source.requireSprite();
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        Unit unit = this.game.getUnitStorageConstructor().create(context,
                                                                 this.game.getPlayerTable().requireUnitAccounting(),
                                                                 type,
                                                                 sourceSprite.getX(), sourceSprite.getY(),
                                                                 owner,
                                                                 pool.getCreationTag(source.getIndex()),
                                                                 instructionLimit);

        if (unit == null) {
            this.game.getUnitStorageConstructor().notifyCreationFailure(owner);

            return null;
        }

        this.game.getUnitHallucination().initializeCreatedHallucination(unit, context.preservesNegativeTypeCounts(), instructionLimit);
        UnitHallucination.setRemovalTimer(unit, UnitHallucination.HALLUCINATION_REMOVAL_TIMER);

        if (this.game.requireGameData().getUnitTypes().isReaver(unit.getUnitId())) {
            for (int side = 0; side < Unit.HANGAR_LIST_COUNT; side++) {
                int remaining = source.getHangarCount(side == OUTSIDE_HANGAR_LIST);
                Unit child = source.getFirstHangarUnit(side == OUTSIDE_HANGAR_LIST);

                for (; remaining != 0; remaining--) {
                    if (child == null) {
                        throw Exceptions.newUnsupportedOperationException("The source hangar count exceeds its list.");
                    }

                    Unit copy = this.createHallucination(child, unit.getOwnerIndex(), instructionLimit);

                    if (copy != null) {
                        copy.setHangarParent(unit);
                        this.game.getUnitHangar().storeInside(unit, copy);
                    }

                    child = child.getNextHangarUnit();
                }
            }
        }

        this.game.getComputerUnits().notifyCompleted(unit, unit, instructionLimit);

        return unit;
    }

    /**
     * Kills a hallucinated target or transfers its ownership, then spends energy and clears the caster's shields.
     * <p>
     * Null or same-owner targets finish through the existing order path.
     *
     * @param unit             the Dark Archon
     * @param instructionLimit the order, image, ownership and script budget
     */
    public void updateMindControl(@NotNull Unit unit, int instructionLimit) {
        Unit target = unit.getOrderTargetUnit();

        if (target == null || target.getOwnerIndex() == unit.getOwnerIndex()) {
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        if (!this.moveToCastTarget(unit,
                                   ScrTechIds.MIND_CONTROL,
                                   this.game.getUnitVisibility().calculateSightRange(unit, true) * TerrainTileset.TILE_SIZE,
                                   instructionLimit)) {
            return;
        }

        this.reportSpellCast(unit, ScrTechIds.MIND_CONTROL, target, unit.getOrderTargetX(), unit.getOrderTargetY());

        if ((target.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
            this.game.getUnitLifecycle().kill(target, instructionLimit);
        } else {
            this.createTopImage(target, ImageIds.MIND_CONTROL_HIT_SMALL + this.getSpriteSize(target), instructionLimit);
            this.game.getUnitOwnership().giveUnit(target, unit.getOwnerIndex(), instructionLimit);

            if (this.game.getBaseType(target) == ScrUnitIds.PROTOSS_DARK_ARCHON) {
                target.setEnergy(0);
            }

            this.game.getOrders().finishOrder(target, instructionLimit);
        }

        this.game.getUnitEnergy().spend(unit, this.getEnergyCost(ScrTechIds.MIND_CONTROL));
        unit.setShieldPoints(0);
        this.game.getOrders().finishOrder(unit, instructionLimit);
        this.playSound(SoundIds.DARK_ARCHON_MIND_CONTROL, target);
    }

    /**
     * Deals Feedback damage from the target's energy, clears that energy and spends the caster's energy. Places the hit overlay or death effect on
     * the target, using the target's overlay size and sprite position.
     *
     * @param unit             the Dark Archon
     * @param instructionLimit the order, damage, sprite and image budget
     * @throws NullPointerException if the world was built without game data
     */
    public void updateFeedback(@NotNull Unit unit, int instructionLimit) {
        Unit target = unit.getOrderTargetUnit();
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if (target == null || !UnitTypeTraitsUtils.isSpellcaster(units, target)) {
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        if (!this.moveToCastTarget(unit,
                                   ScrTechIds.FEEDBACK,
                                   this.game.getUnitVisibility().calculateSightRange(unit, true) * TerrainTileset.TILE_SIZE,
                                   instructionLimit)) {
            return;
        }

        if (target.getEnergy() != 0) {
            this.reportSpellCast(unit, ScrTechIds.FEEDBACK, target, unit.getOrderTargetX(), unit.getOrderTargetY());
            this.game.getUnitDamage().dealWeaponDamage(ScrWeaponIds.FEEDBACK,
                                                       target.getEnergy(),
                                                       FULL_DAMAGE_DIVISOR,
                                                       unit, unit.getOwnerIndex(),
                                                       target, FEEDBACK_HIT_HEADING,
                                                       instructionLimit);
            target.setEnergy(0);
            this.game.getUnitEnergy().spend(unit, this.getEnergyCost(ScrTechIds.FEEDBACK));
            this.playSound(SoundIds.DARK_ARCHON_FEEDBACK, target);

            if (ComputerUnits.isDeadOrHidden(target)) {
                this.createEffect(SpriteIds.FEEDBACK_HIT_SMALL + this.getSpriteSize(target), target, instructionLimit);
            } else {
                this.createTopImage(target, ImageIds.FEEDBACK_SMALL + this.getSpriteSize(target), instructionLimit);
            }
        }

        this.game.getOrders().finishOrder(unit, instructionLimit);
    }


    // Bullet hit effects

    /**
     * Dispatches spell weapon hit effects.
     * <p>
     * Effects run only for their selected hit branch; missing targets or positions of {@code 0} can skip branch work.
     *
     * @param hitType          the {@code weapons.dat} explosion type
     * @param owner            the bullet owner player
     * @param sourceUnit       the bullet owner unit, or {@code null}
     * @param target           the bullet target, or {@code null}
     * @param positionX        the bullet sprite position X
     * @param positionY        the bullet sprite position Y
     * @param targetX          the bullet target position X
     * @param targetY          the bullet target position Y
     * @param instructionLimit the creation, completion, sprite, image, order, script and speed budget
     * @throws UnsupportedOperationException if the explosion type has no spell hit branch
     */
    public void hit(int hitType,
                    int owner,
                    @Nullable Unit sourceUnit,
                    @Nullable Unit target,
                    int positionX, int positionY,
                    int targetX, int targetY,
                    int instructionLimit) {
        switch (hitType) {
            case WeaponHitTypes.LOCKDOWN -> {
                if (target != null && target.getPrimaryOrder() != ScrOrderIds.DIE) {
                    this.applyLockdown(target, instructionLimit);
                }
            }
            case WeaponHitTypes.PARASITE -> {
                if (target != null && target.getPrimaryOrder() != ScrOrderIds.DIE) {
                    this.playSound(SoundIds.QUEEN_PARASITE, target);
                    target.setParasiteFlags(target.getParasiteFlags() | 1 << owner);
                }
            }
            case WeaponHitTypes.BROODLINGS -> {
                if (target != null && sourceUnit != null && target.getPrimaryOrder() != ScrOrderIds.DIE) {
                    this.spawnBroodlings(sourceUnit, target, instructionLimit);
                }
            }
            case WeaponHitTypes.EMP_SHOCKWAVE -> this.applyEmpShockwave(sourceUnit, positionX, positionY, instructionLimit);
            case WeaponHitTypes.IRRADIATE -> {
                if (target != null && target.getPrimaryOrder() != ScrOrderIds.DIE) {
                    this.playSound(SoundIds.SCIENCE_VESSEL_IRRADIATE, target);
                    this.applyIrradiation(sourceUnit, owner, target, instructionLimit);
                }
            }
            case WeaponHitTypes.ENSNARE -> this.applyEnsnareArea(sourceUnit, positionX, positionY, instructionLimit);
            case WeaponHitTypes.PLAGUE -> this.applyPlagueArea(sourceUnit, targetX, targetY, instructionLimit);
            case WeaponHitTypes.STASIS_FIELD -> {
                if (targetX != 0 || targetY != 0) {
                    this.applyStasisField(sourceUnit, targetX, targetY, instructionLimit);
                }
            }
            case WeaponHitTypes.DARK_SWARM -> {
                Unit swarm = this.createCompletedUnit(ScrUnitIds.SPELL_DARK_SWARM,
                                                      targetX, targetY,
                                                      PlayerTable.NEUTRAL_PLAYER_ID,
                                                      true,
                                                      owner,
                                                      instructionLimit);

                if (swarm != null) {
                    UnitHallucination.setRemovalTimer(swarm, DARK_SWARM_DURATION);
                }
            }
            case WeaponHitTypes.CONSUME -> {
                if (target != null && sourceUnit != null && target.getPrimaryOrder() != ScrOrderIds.DIE) {
                    this.consume(sourceUnit, target, instructionLimit);
                }
            }
            case WeaponHitTypes.RESTORATION -> {
                if (target != null) {
                    this.playSound(SoundIds.MEDIC_RESTORATION, target);
                    this.restore(target, instructionLimit);
                }
            }
            case WeaponHitTypes.DISRUPTION_WEB -> {
                Unit web = this.createCompletedUnit(ScrUnitIds.SPELL_DISRUPTION_WEB,
                                                    targetX, targetY,
                                                    PlayerTable.NEUTRAL_PLAYER_ID,
                                                    true,
                                                    owner,
                                                    instructionLimit);

                if (web != null) {
                    UnitHallucination.setRemovalTimer(web, DISRUPTION_WEB_DURATION);
                }
            }
            case WeaponHitTypes.OPTICAL_FLARE -> {
                if (target != null && target.getPrimaryOrder() != ScrOrderIds.DIE) {
                    this.blind(target, owner, instructionLimit);
                }
            }
            case WeaponHitTypes.MAELSTROM -> {
                if (targetX != 0 || targetY != 0) {
                    this.applyMaelstromArea(sourceUnit, targetX, targetY, instructionLimit);
                }
            }
            default -> throw Exceptions.newUnsupportedOperationException("Explosion type {0} cannot be applied to a bullet hit.", hitType);
        }
    }

    /**
     * Spawns the {@link #BROODLING_COUNT} Broodlings of Spawn Broodlings beside a target that is no hallucination, then kills the target; the
     * target is credited to the caster's owner before it is killed, and the caster gains the kill.
     * <p>
     * The first candidate lies {@link #BROODLING_OFFSET} pixels up and left of the target. A candidate on terrain that no Broodling can stand
     * on is replaced by the free position the caster's placement search finds around it, and no Broodling is created when the search fails.
     * The next candidate lies {@link #BROODLING_STEP} pixels down and right of the possibly replaced candidate.
     *
     * @param source           the caster
     * @param target           the Spawn Broodlings target
     * @param instructionLimit the creation, completion and order budget
     * @throws IllegalStateException if the target has no sprite
     */
    private void spawnBroodlings(@NotNull Unit source, @NotNull Unit target, int instructionLimit) {
        if ((target.getStatus() & UnitStatusFlags.HALLUCINATION) == 0) {
            Sprite sprite = target.requireSprite();
            UnitPositioning positioning = this.game.getUnitPositioning();
            int[] position = new int[UnitPositioning.POSITION_COORDINATES];
            int positionX = sprite.getX() - BROODLING_OFFSET;
            int positionY = sprite.getY() - BROODLING_OFFSET;

            for (int broodlingIndex = 0; broodlingIndex < BROODLING_COUNT; broodlingIndex++) {
                if (positioning.isTerrainPlacementWalkable(ScrUnitIds.ZERG_BROODLING, positionX, positionY)) {
                    this.spawnBroodling(source, positionX, positionY, instructionLimit);
                } else if (positioning.find(source,
                                            positionX, positionY,
                                            (short) (positionX - BROODLING_PLACEMENT_RADIUS),
                                            (short) (positionY - BROODLING_PLACEMENT_RADIUS),
                                            (short) (positionX + BROODLING_PLACEMENT_RADIUS),
                                            (short) (positionY + BROODLING_PLACEMENT_RADIUS),
                                            false,
                                            position)) {
                    positionX = (short) position[UnitPositioning.X_INDEX];
                    positionY = (short) position[UnitPositioning.Y_INDEX];

                    this.spawnBroodling(source, positionX, positionY, instructionLimit);
                }

                positionX += BROODLING_STEP;
                positionY += BROODLING_STEP;
            }
        }

        this.game.getUnitLifecycle().recordDeath(target, source.getOwnerIndex());
        this.game.getUnitLifecycle().kill(target, instructionLimit);
        UnitDamage.creditKill(this.game, source);
    }

    /**
     * Creates a Broodling at a position clamped to the map; a caster with a computer record hands it to its computer player.
     *
     * @param source           the caster
     * @param positionX        the requested position X
     * @param positionY        the requested position Y
     * @param instructionLimit the creation, completion and order budget
     */
    private void spawnBroodling(@NotNull Unit source, int positionX, int positionY, int instructionLimit) {
        int creationType = ScrUnitIds.ZERG_BROODLING;
        int creationOwner = source.getOwnerIndex();

        Unit broodling = this.createCompletedUnit(creationType, positionX, positionY, creationOwner, false, creationOwner, instructionLimit);

        if (broodling == null) {
            return;
        }

        UnitHallucination.setRemovalTimer(broodling, UnitHallucination.BROODLING_REMOVAL_TIMER);

        if (source.getComputerRecord() != null) {
            this.game.getComputerUnits().notifyCompleted(source, broodling, instructionLimit);
        }
    }

    private void applyEmpShockwave(@Nullable Unit source, int positionX, int positionY, int instructionLimit) {
        int range = this.game.requireGameData().getWeaponTypes().getInnerSplashRadius(ScrWeaponIds.EMP_SHOCKWAVE);

        for (Unit target : this.collectUnits(positionX - range, positionY - range, positionX + range, positionY + range)) {
            if (target == source || source != null && target == source.getSubunit()) {
                continue;
            }

            if ((target.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
                this.game.getUnitLifecycle().kill(target, instructionLimit);

                continue;
            }

            if (target.getStasisTimer() != 0) {
                continue;
            }

            target.setEnergy(0);
            target.setShieldPoints(0);
        }
    }

    private void applyEnsnareArea(@Nullable Unit source, int positionX, int positionY, int instructionLimit) {
        this.createEffect(SpriteIds.ENSNARE, positionX, positionY, ENSNARE_ELEVATION, instructionLimit);

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        for (Unit target : this.collectUnits(positionX - ENSNARE_RADIUS, positionY - ENSNARE_RADIUS,
                                             positionX + ENSNARE_RADIUS, positionY + ENSNARE_RADIUS)) {
            if (target == source || (units.getFlags(target.getUnitId()) & UnitTypeFlags.BUILDING) != 0 ||
                (target.getStatus() & UnitStatusFlags.INVINCIBLE) != 0 || (target.getStatus() & UnitStatusFlags.BURROWED) != 0) {
                continue;
            }

            this.applyEnsnare(target, instructionLimit);
        }
    }

    private void applyPlagueArea(@Nullable Unit source, int positionX, int positionY, int instructionLimit) {
        for (Unit target : this.collectUnits(positionX - PLAGUE_RADIUS, positionY - PLAGUE_RADIUS,
                                             positionX + PLAGUE_RADIUS, positionY + PLAGUE_RADIUS)) {
            if (target == source || (target.getStatus() & UnitStatusFlags.INVINCIBLE) != 0 || (target.getStatus() & UnitStatusFlags.BURROWED) != 0) {
                continue;
            }

            this.applyPlague(target, instructionLimit);

            if (source != null) {
                this.game.getUnitDamage().onDamage(target, source, true, instructionLimit);
            }
        }
    }

    private void applyStasisField(@Nullable Unit source, int positionX, int positionY, int instructionLimit) {
        this.createEffect(SpriteIds.STASIS_FIELD_HIT, positionX, positionY, STASIS_FIELD_ELEVATION, instructionLimit);

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        for (Unit target : this.collectUnits(positionX - STASIS_FIELD_RADIUS, positionY - STASIS_FIELD_RADIUS,
                                             positionX + STASIS_FIELD_RADIUS, positionY + STASIS_FIELD_RADIUS)) {
            if (target == source || (target.getStatus() & UnitStatusFlags.INVINCIBLE) != 0 || (target.getStatus() & UnitStatusFlags.BURROWED) != 0 ||
                (units.getFlags(target.getUnitId()) & UnitTypeFlags.BUILDING) != 0) {
                continue;
            }

            this.applyStasis(target, instructionLimit);
        }
    }

    private void applyMaelstromArea(@Nullable Unit source, int positionX, int positionY, int instructionLimit) {
        this.createEffect(SpriteIds.MAELSTROM_HIT, positionX, positionY, MAELSTROM_ELEVATION, instructionLimit);

        if (source != null) {
            this.playSound(SoundIds.DARK_ARCHON_MAELSTROM, source);
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        for (Unit target : this.collectUnits(positionX - MAELSTROM_RADIUS, positionY - MAELSTROM_RADIUS,
                                             positionX + MAELSTROM_RADIUS, positionY + MAELSTROM_RADIUS)) {
            if ((target.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
                this.game.getUnitLifecycle().kill(target, instructionLimit);

                continue;
            }

            int abilities = units.getFlags(target.getUnitId());

            if (target == source || (target.getStatus() & UnitStatusFlags.INVINCIBLE) != 0 || (target.getStatus() & UnitStatusFlags.BURROWED) != 0 ||
                (abilities & UnitTypeFlags.BUILDING) != 0 || (abilities & UnitTypeFlags.ORGANIC) == 0) {
                continue;
            }

            this.applyMaelstrom(target, instructionLimit);
        }
    }

    /**
     * Applies the Consume hit; the target is credited to the caster's owner before it is killed.
     *
     * @param source           the casting unit
     * @param target           the consumed unit
     * @param instructionLimit the order budget
     * @throws NullPointerException if the world was built without game data
     */
    private void consume(@NotNull Unit source, @NotNull Unit target, int instructionLimit) {
        if ((target.getStatus() & UnitStatusFlags.INVINCIBLE) != 0 || !this.isTechnologyTargetValid(source, ScrTechIds.CONSUME, target)) {
            return;
        }

        this.game.getUnitLifecycle().recordDeath(target, source.getOwnerIndex());
        this.game.getUnitLifecycle().kill(target, instructionLimit);

        if ((target.getStatus() & UnitStatusFlags.HALLUCINATION) == 0) {
            int maximum = this.game.getUnitInitializer().getMaximumEnergy(source, this.game.requireGameData().getUnitTypes());

            source.setEnergy(Math.min(source.getEnergy() + (CONSUME_ENERGY << FIXED_POINT_FRACTION_BITS), maximum));
        }
    }

    private void restore(@NotNull Unit target, int instructionLimit) {
        if ((target.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
            this.game.getUnitLifecycle().kill(target, instructionLimit);

            return;
        }

        this.createTopImage(target, ImageIds.RESTORATION_HIT_SMALL + this.getSpriteSize(target), instructionLimit);
        target.setParasiteFlags(0);
        target.setBlindnessState(0);

        if (target.getSpeedDecreaseByte() != 0) {
            this.removeEnsnare(target, instructionLimit);
        }

        if (target.getPlagueTimer() != 0) {
            this.removePlague(target);
        }

        if (target.getIrradiateTimer() != 0) {
            this.removeIrradiate(target);
        }

        if (target.getLockdownTimer() != 0) {
            this.removeLockdown(target, instructionLimit);
        }

        if (target.getMaelstromTimer() != 0) {
            this.removeMaelstrom(target, instructionLimit);
        }

        if (target.getAcidSporeCount() != 0) {
            this.removeAcidSpores(target);
        }
    }

    private void blind(@NotNull Unit target, int owner, int instructionLimit) {
        if ((target.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
            this.game.getUnitLifecycle().kill(target, instructionLimit);

            return;
        }

        target.setBlindnessState(target.getBlindnessState() | 1 << owner);
        this.playSound(SoundIds.MEDIC_OPTICAL_FLARE, target);
        this.createTopImage(target, ImageIds.OPTICAL_FLARE_HIT_SMALL + this.getSpriteSize(target), instructionLimit);
        this.game.setUpdateTilesCountdown(BLINDNESS_TILE_UPDATE_DELAY);
    }

    /**
     * Adds a spore to each eligible flying unit of a different owner in the square within {@link #ACID_SPORE_RADIUS} pixels of the hit position.
     * <p>
     * Each eligible unit gains its spore before its overlay is updated.
     *
     * @param positionX        the hit position X
     * @param positionY        the hit position Y
     * @param owner            the attacking player
     * @param instructionLimit the image budget
     * @throws NullPointerException if the world was built without game data
     */
    public void addAcidSpores(int positionX, int positionY, int owner, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        for (Unit target : this.collectUnits(positionX - ACID_SPORE_RADIUS, positionY - ACID_SPORE_RADIUS,
                                             positionX + ACID_SPORE_RADIUS, positionY + ACID_SPORE_RADIUS)) {
            if (target.getOwnerIndex() == owner || (units.getFlags(target.getUnitId()) & UnitTypeFlags.BUILDING) != 0 ||
                (target.getStatus() & UnitStatusFlags.FLYING) == 0 || (target.getStatus() & UnitStatusFlags.INVINCIBLE) != 0) {
                continue;
            }

            if ((target.getStatus() & (UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED)) != 0 &&
                (target.getDetectionFlags() & 1 << owner) == 0) {
                continue;
            }

            if (target.getAcidSporeCount() < Unit.ACID_SPORE_SLOTS) {
                target.setAcidSporeCount(target.getAcidSporeCount() + 1);
            }

            this.updateAcidSporeImage(target, instructionLimit);

            int best = 0;

            for (int index = 1; index < Unit.ACID_SPORE_SLOTS; index++) {
                if (target.getAcidSporeTimer(index) < target.getAcidSporeTimer(best)) {
                    best = index;
                }
            }

            target.setAcidSporeTimer(best, ACID_SPORE_DURATION);
        }
    }

    /**
     * Shows the Acid Spores overlay of the unit's spore count, replacing the overlay of another count.
     *
     * @param unit             the unit with spores
     * @param instructionLimit the image budget
     */
    public void updateAcidSporeImage(@NotNull Unit unit, int instructionLimit) {
        int image = ImageIds.ACID_SPORES_1_OVERLAY_SMALL + ACID_SPORE_STAGES * this.getSpriteSize(unit) +
                    Math.min(unit.getAcidSporeCount() / ACID_SPORES_PER_STAGE, ACID_SPORE_STAGES - 1);

        if (findImage(unit, image, image) != null) {
            return;
        }

        this.destroyOverlay(unit, ImageIds.ACID_SPORES_1_OVERLAY_SMALL, ImageIds.ACID_SPORES_1_OVERLAY_SMALL + ACID_SPORE_LAST_IMAGE_OFFSET);
        this.createTopImage(unit, image, instructionLimit);
    }


    // Status effects

    /**
     * Extends Lockdown, creating its overlay on the first application and disabling the target.
     *
     * @param unit             the target
     * @param instructionLimit the image, order and script budget
     */
    public void applyLockdown(@NotNull Unit unit, int instructionLimit) {
        if (unit.getLockdownTimer() == 0) {
            this.createTopImage(unit, ImageIds.LOCKDOWN_FIELD_SMALL + this.getSpriteSize(unit), instructionLimit);
        }

        if (unit.getLockdownTimer() < LOCKDOWN_DURATION) {
            unit.setLockdownTimer(LOCKDOWN_DURATION);
        }

        this.setDisabled(unit, instructionLimit);
    }

    /**
     * Extends Stasis, making the target invincible on its first application and disabling it.
     *
     * @param unit             the target
     * @param instructionLimit the image, order and script budget
     */
    public void applyStasis(@NotNull Unit unit, int instructionLimit) {
        if (unit.getStasisTimer() == 0) {
            this.createTopImage(unit, ImageIds.STASIS_FIELD_SMALL + this.getSpriteSize(unit), instructionLimit);
            unit.setStatus(unit.getStatus() | UnitStatusFlags.INVINCIBLE);
        }

        if (unit.getStasisTimer() < STASIS_FIELD_DURATION) {
            unit.setStasisTimer(STASIS_FIELD_DURATION);
        }

        this.setDisabled(unit, instructionLimit);
    }

    /**
     * Extends Maelstrom, creating its overlay on the first application and disabling the target.
     *
     * @param unit             the target
     * @param instructionLimit the image, order and script budget
     */
    public void applyMaelstrom(@NotNull Unit unit, int instructionLimit) {
        if (unit.getMaelstromTimer() == 0) {
            this.createTopImage(unit, ImageIds.MAELSTROM_OVERLAY_SMALL + this.getSpriteSize(unit), instructionLimit);
        }

        if (unit.getMaelstromTimer() < MAELSTROM_DURATION) {
            unit.setMaelstromTimer(MAELSTROM_DURATION);
        }

        this.setDisabled(unit, instructionLimit);
    }

    /**
     * Installs Irradiate's weapon cooldown, source and owner, creating the overlay when needed.
     *
     * @param source           the irradiating unit, or {@code null}
     * @param owner            the irradiating player
     * @param unit             the target
     * @param instructionLimit the image budget
     * @throws NullPointerException if the world was built without game data
     */
    public void applyIrradiation(@Nullable Unit source, int owner, @NotNull Unit unit, int instructionLimit) {
        if (unit.getIrradiateTimer() == 0 && (unit.getStatus() & UnitStatusFlags.BURROWED) == 0) {
            this.createTopImage(unit, ImageIds.IRRADIATE_SMALL + this.getSpriteSize(unit), instructionLimit);
        }

        unit.setIrradiateTimer(this.game.requireGameData().getWeaponTypes().getCooldown(ScrWeaponIds.IRRADIATE));
        unit.setIrradiatedBy(source);
        unit.setIrradiateOwner(owner);
    }

    /**
     * Installs Ensnare's speed decrease and refreshes movement speed.
     *
     * @param unit             the target
     * @param instructionLimit the image and speed budget
     */
    public void applyEnsnare(@NotNull Unit unit, int instructionLimit) {
        if (unit.getSpeedDecreaseByte() == 0 && (unit.getStatus() & UnitStatusFlags.BURROWED) == 0) {
            this.createTopImage(unit, ImageIds.ENSNARE_OVERLAY_SMALL + this.getSpriteSize(unit), instructionLimit);
        }

        unit.setSpeedDecreaseByte(ENSNARE_SPEED_DECREASE);
        this.game.getEngineGame().getMovement().recalculateSpeed(unit, instructionLimit);
    }

    /**
     * Reloads Plague and creates its overlay when needed.
     *
     * @param unit             the target
     * @param instructionLimit the image budget
     */
    public void applyPlague(@NotNull Unit unit, int instructionLimit) {
        if (unit.getPlagueTimer() == 0 && (unit.getStatus() & UnitStatusFlags.BURROWED) == 0) {
            this.createTopImage(unit, ImageIds.PLAGUE_OVERLAY_SMALL + this.getSpriteSize(unit), instructionLimit);
        }

        unit.setPlagueTimer(PLAGUE_DURATION);
    }

    /**
     * Creates the front and back Defensive Matrix overlays for an active, unburrowed target.
     *
     * @param unit             the protected unit
     * @param instructionLimit the image budget
     */
    public void createDefensiveMatrixImage(@NotNull Unit unit, int instructionLimit) {
        if (unit.getDefensiveMatrixTimer() == 0 || (unit.getStatus() & UnitStatusFlags.BURROWED) != 0) {
            return;
        }

        this.createTopImage(unit, ImageIds.DEFENSIVE_MATRIX_FRONT_SMALL + this.getSpriteSize(unit), instructionLimit);
        this.game.getIscriptExecutor().createImageBelowMain(unit, ImageIds.DEFENSIVE_MATRIX_BACK_SMALL + this.getSpriteSize(unit), instructionLimit);
    }

    /**
     * Consumes Defensive Matrix health, removing its timer and overlays when depleted.
     *
     * @param unit             the protected unit
     * @param damage           the absorbed damage with {@code 8} fractional bits
     * @param instructionLimit the image budget
     * @throws IllegalStateException if the unit has no sprite
     */
    public void dealDefensiveMatrixDamage(@NotNull Unit unit, int damage, int instructionLimit) {
        if (damage < unit.getDefensiveMatrixHealth()) {
            unit.setDefensiveMatrixHealth(unit.getDefensiveMatrixHealth() - damage);
        } else {
            unit.setDefensiveMatrixHealth(0);
            unit.setDefensiveMatrixTimer(0);
            this.destroyOverlay(unit, ImageIds.DEFENSIVE_MATRIX_FRONT_SMALL, ImageIds.DEFENSIVE_MATRIX_FRONT_SMALL + LARGE_OVERLAY);
            this.destroySpriteImages(unit.requireSprite(),
                                     ImageIds.DEFENSIVE_MATRIX_BACK_SMALL, ImageIds.DEFENSIVE_MATRIX_BACK_SMALL + LARGE_OVERLAY);
        }

        if (unit.getDefensiveMatrixTimer() != 0 && (unit.getStatus() & UnitStatusFlags.BURROWED) == 0) {
            this.createTopImage(unit, ImageIds.DEFENSIVE_MATRIX_HIT_SMALL + this.getSpriteSize(unit), instructionLimit);
        }
    }

    /**
     * Restores the unit's own type as its button set once no disabling effect remains: the shared step of the end of Lockdown, Maelstrom and Stasis
     * Field and of Restoration.
     *
     * @param unit the unit whose button set is restored
     * @throws NullPointerException if the world was built without game data
     */
    private void restoreButtonSet(@NotNull Unit unit) {
        if (!UnitOrderEligibility.isDisabled(unit)) {
            UnitMorphing.writeButtonSet(this.game.requireGameData().getUnitTypes(), unit, this.game.requireGameData().getButtonSet(unit.getUnitId()));
        }
    }

    /**
     * Restores eligible subunit and worker orders, ends the effect image and reloads the primary countdown.
     *
     * @param unit             the unit whose disabling effect ends
     * @param firstImage       the first effect image type
     * @param lastImage        the last effect image type
     * @param instructionLimit the order budget
     * @throws IllegalStateException         if the unit or the subunit has no sprite
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    private void endDisableEffect(@NotNull Unit unit, int firstImage, int lastImage, int instructionLimit) {
        boolean stillDisabled = UnitOrderEligibility.isDisabled(unit);
        Unit subunit = unit.getSubunit();

        if (subunit != null && !stillDisabled) {
            subunit.setStatus(subunit.getStatus() & ~UnitStatusFlags.DISABLED);
            this.game.getOrders().issueUntargeted(
                    subunit,
                    this.game.requireGameData().getUnitTypes().getReturnToIdleOrder(subunit.getUnitId()),
                    instructionLimit
            );
        }

        SpriteImage image = findSpriteImage(unit.requireSprite(), firstImage, lastImage);

        if (image == null && subunit != null) {
            image = findSpriteImage(subunit.requireSprite(), firstImage, lastImage);
        }

        if (image != null) {
            this.game.getIscriptExecutor().runAnimation(image, image.requireSprite(), unit, image.getScriptIdentifier(), IscriptExecutor.ANIMATION_DEATH);
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if ((units.getFlags(unit.getUnitId()) & UnitTypeFlags.WORKER) != 0 && !stillDisabled) {
            Unit target = unit.getGatherTarget();

            if (target != null && (units.getFlags(target.getUnitId()) & UnitTypeFlags.RESOURCE_CONTAINER) != 0 &&
                unit.isGatheringResource() && target.isResourceBeingGathered() &&
                (unit.getPrimaryOrder() == ScrOrderIds.WAIT_FOR_MINERALS || unit.getPrimaryOrder() == ScrOrderIds.MOVE_TO_GAS) &&
                unit.getPrimaryOrderState() == UnitHarvesting.STATE_WAITING) {
                UnitHarvesting.unlinkWaitingWorker(target, unit);

                for (OrderPool.Order order = unit.getFirstOrder(); order != null; order = order.getNext()) {
                    if (order.getOrderId() == ScrOrderIds.GATHER_WAIT_INTERRUPTED) {
                        this.game.requireOrderPool().remove(unit, order, this.game.requireGameData().getOrderTypes());

                        break;
                    }
                }
            }
        }

        unit.setPrimaryOrderCountdown(DISABLE_END_ORDER_TIMER);
    }

    /**
     * Ends Ensnare: clears the speed decrease, destroys the overlay and refreshes movement speed.
     *
     * @param unit             the ensnared unit
     * @param instructionLimit the speed budget
     */
    public void removeEnsnare(@NotNull Unit unit, int instructionLimit) {
        unit.setSpeedDecreaseByte(0);
        this.destroyOverlay(unit, ImageIds.ENSNARE_OVERLAY_SMALL, ImageIds.ENSNARE_OVERLAY_SMALL + LARGE_OVERLAY);
        this.game.getEngineGame().getMovement().recalculateSpeed(unit, instructionLimit);
    }

    /**
     * Ends Lockdown: clears the timer, restores the button set once no disabling effect remains and ends the disabling effect.
     *
     * @param unit             the locked-down unit
     * @param instructionLimit the order budget
     */
    public void removeLockdown(@NotNull Unit unit, int instructionLimit) {
        unit.setLockdownTimer(0);
        this.restoreButtonSet(unit);
        this.endDisableEffect(unit, ImageIds.LOCKDOWN_FIELD_SMALL, ImageIds.LOCKDOWN_FIELD_SMALL + LARGE_OVERLAY, instructionLimit);
    }

    /**
     * Ends Plague: clears the timer and destroys the overlay.
     *
     * @param unit the plagued unit
     */
    public void removePlague(@NotNull Unit unit) {
        unit.setPlagueTimer(0);
        this.destroyOverlay(unit, ImageIds.PLAGUE_OVERLAY_SMALL, ImageIds.PLAGUE_OVERLAY_SMALL + LARGE_OVERLAY);
    }

    /**
     * Ends Maelstrom: clears the timer, restores the button set once no disabling effect remains and ends the disabling effect.
     *
     * @param unit             the unit under Maelstrom
     * @param instructionLimit the order budget
     */
    public void removeMaelstrom(@NotNull Unit unit, int instructionLimit) {
        unit.setMaelstromTimer(0);
        this.restoreButtonSet(unit);
        this.endDisableEffect(unit, ImageIds.MAELSTROM_OVERLAY_SMALL, ImageIds.MAELSTROM_OVERLAY_SMALL + LARGE_OVERLAY, instructionLimit);
    }

    /**
     * Ends Acid Spores: clears the count and the spore timers and destroys the overlay.
     *
     * @param unit the unit with spores
     */
    public void removeAcidSpores(@NotNull Unit unit) {
        unit.setAcidSporeCount(0);

        for (int index = 0; index < Unit.ACID_SPORE_SLOTS; index++) {
            unit.setAcidSporeTimer(index, 0);
        }

        this.destroyOverlay(unit, ImageIds.ACID_SPORES_1_OVERLAY_SMALL, ImageIds.ACID_SPORES_1_OVERLAY_SMALL + ACID_SPORE_LAST_IMAGE_OFFSET);
    }

    /**
     * Ends Stasis Field: clears the timer, restores the button set once no disabling effect remains, restores the invincibility of the unit
     * type and ends the disabling effect.
     *
     * @param unit             the unit in stasis
     * @param instructionLimit the order budget
     */
    public void removeStasis(@NotNull Unit unit, int instructionLimit) {
        unit.setStasisTimer(0);
        this.restoreButtonSet(unit);

        boolean invincible = (this.game.requireGameData().getUnitTypes().getFlags(unit.getUnitId()) & UnitTypeFlags.INVINCIBLE) != 0;

        unit.setStatus(invincible ? unit.getStatus() | UnitStatusFlags.INVINCIBLE : unit.getStatus() & ~UnitStatusFlags.INVINCIBLE);
        this.endDisableEffect(unit, ImageIds.STASIS_FIELD_SMALL, ImageIds.STASIS_FIELD_SMALL + LARGE_OVERLAY, instructionLimit);
    }

    /**
     * Ends Irradiate: clears the timer, the source and the owner and destroys the overlay.
     *
     * @param unit the irradiated unit
     */
    public void removeIrradiate(@NotNull Unit unit) {
        unit.setIrradiateTimer(0);
        unit.setIrradiatedBy(null);
        unit.setIrradiateOwner(NO_IRRADIATE_OWNER);
        this.destroyOverlay(unit, ImageIds.IRRADIATE_SMALL, ImageIds.IRRADIATE_SMALL + LARGE_OVERLAY);
    }

    /**
     * Updates status timers and their damage, speed and overlay effects.
     * <p>
     * Inactive timers avoid their effect readers.
     *
     * @param unit             the unit
     * @param instructionLimit the image, speed, damage and order budget
     * @throws NullPointerException if the world was built without game data
     */
    public void updateStatusTimers(@NotNull Unit unit, int instructionLimit) {
        if (unit.getStasisTimer() != 0) {
            unit.setStasisTimer(unit.getStasisTimer() - 1);

            if (unit.getStasisTimer() == 0) {
                this.removeStasis(unit, instructionLimit);
            }
        }

        if (unit.getSpeedIncreaseByte() != 0) {
            unit.setSpeedIncreaseByte(unit.getSpeedIncreaseByte() - 1);

            if (unit.getSpeedIncreaseByte() == 0) {
                this.game.getEngineGame().getMovement().recalculateSpeed(unit, instructionLimit);
            }
        }

        if (unit.getSpeedDecreaseByte() != 0) {
            unit.setSpeedDecreaseByte(unit.getSpeedDecreaseByte() - 1);

            if (unit.getSpeedDecreaseByte() == 0) {
                this.removeEnsnare(unit, instructionLimit);
            }
        }

        if (unit.getDefensiveMatrixTimer() != 0) {
            unit.setDefensiveMatrixTimer(unit.getDefensiveMatrixTimer() - 1);

            if (unit.getDefensiveMatrixTimer() == 0) {
                this.dealDefensiveMatrixDamage(unit, unit.getDefensiveMatrixHealth(), instructionLimit);
            }
        }

        if (unit.getIrradiateTimer() != 0) {
            unit.setIrradiateTimer(unit.getIrradiateTimer() - 1);
            this.dealIrradiateDamage(unit, instructionLimit);

            if (unit.getIrradiateTimer() == 0) {
                this.removeIrradiate(unit);
            }
        }

        if (unit.getLockdownTimer() != 0) {
            unit.setLockdownTimer(unit.getLockdownTimer() - 1);

            if (unit.getLockdownTimer() == 0) {
                this.removeLockdown(unit, instructionLimit);
            }
        }

        if (unit.getMaelstromTimer() != 0) {
            unit.setMaelstromTimer(unit.getMaelstromTimer() - 1);

            if (unit.getMaelstromTimer() == 0) {
                this.removeMaelstrom(unit, instructionLimit);
            }
        }

        if (unit.getPlagueTimer() != 0) {
            unit.setPlagueTimer(unit.getPlagueTimer() - 1);

            if ((unit.getStatus() & UnitStatusFlags.INVINCIBLE) == 0) {
                int damage = (this.game.requireGameData().getWeaponTypes().getDamageAmount(ScrWeaponIds.PLAGUE) << FIXED_POINT_FRACTION_BITS) /
                             PLAGUE_DAMAGE_DIVISOR;

                if (unit.getHitPoints() > damage) {
                    this.game.getEngineGame().getCombat().dealDamage(unit, damage, null, instructionLimit);
                }
            }

            if (unit.getPlagueTimer() == 0) {
                this.removePlague(unit);
            }
        }

        if (unit.getStormTimer() != 0) {
            unit.setStormTimer(unit.getStormTimer() - 1);
        }

        if (unit.getAcidSporeCount() != 0) {
            int count = unit.getAcidSporeCount();

            for (int index = 0; index < Unit.ACID_SPORE_SLOTS; index++) {
                int remaining = unit.getAcidSporeTimer(index);

                if (remaining == 0) {
                    continue;
                }

                unit.setAcidSporeTimer(index, remaining - 1);

                if (remaining == 1) {
                    count--;
                }
            }

            unit.setAcidSporeCount(count);

            if (count != 0) {
                this.updateAcidSporeImage(unit, instructionLimit);
            } else {
                this.removeAcidSpores(unit);
            }
        }
    }

    /**
     * Applies Irradiate damage from the source: a burrowed source damages only itself, a loaded source every unit loaded in its container, itself
     * included but never the container, and any other source the candidates around its sprite.
     *
     * @param source           the irradiated unit that deals the damage
     * @param instructionLimit the damage budget
     * @throws IllegalStateException if the source has no sprite
     */
    private void dealIrradiateDamage(@NotNull Unit source, int instructionLimit) {
        if ((source.getStatus() & UnitStatusFlags.BURROWED) != 0) {
            this.dealIrradiateDamage(source, source, instructionLimit);
        } else if ((source.getStatus() & UnitStatusFlags.LOADED) != 0) {
            Unit container = source.getParentUnit();

            if (container != null) {
                this.game.getUnitTransport().forEachLoadedUnit(container, target -> {
                    this.dealIrradiateDamage(source, target, instructionLimit);

                    return false;
                });
            }
        } else {
            Sprite sprite = source.requireSprite();

            for (Unit target : this.collectUnits(sprite.getX() - IRRADIATE_SEARCH_RADIUS, sprite.getY() - IRRADIATE_SEARCH_RADIUS,
                                                 sprite.getX() + IRRADIATE_SEARCH_RADIUS, sprite.getY() + IRRADIATE_SEARCH_RADIUS)) {
                this.dealIrradiateDamage(source, target, instructionLimit);
            }
        }
    }

    /**
     * Applies an Irradiate tick to an organic target that is no building, Larva, Egg or Lurker Egg, is burrowed only when it is the source, and is
     * within {@link #IRRADIATE_RANGE} of the source unless the source is loaded.
     *
     * @param source           the irradiated unit that deals the damage
     * @param target           the candidate target
     * @param instructionLimit the damage budget
     * @throws NullPointerException if the world was built without game data
     */
    private void dealIrradiateDamage(@NotNull Unit source, @NotNull Unit target, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int abilities = units.getFlags(target.getUnitId());
        int type = target.getUnitId();

        if ((abilities & UnitTypeFlags.ORGANIC) == 0 || UnitQueries.isBuilding(units, type) || isLarvaEggOrLurkerEgg(this.game.getBaseType(type))) {
            return;
        }

        if ((target.getStatus() & UnitStatusFlags.BURROWED) != 0 && target != source) {
            return;
        }

        if ((source.getStatus() & UnitStatusFlags.LOADED) == 0 && !this.game.getUnitTargeting().isInRange(source, target, IRRADIATE_RANGE)) {
            return;
        }

        ScrWeaponTypeView weapons = this.game.requireGameData().getWeaponTypes();
        int damage = (weapons.getDamageAmount(ScrWeaponIds.IRRADIATE) << FIXED_POINT_FRACTION_BITS) / weapons.getCooldown(ScrWeaponIds.IRRADIATE);

        this.game.getUnitDamage().dealWeaponDamage(ScrWeaponIds.IRRADIATE,
                                                   damage,
                                                   FULL_DAMAGE_DIVISOR,
                                                   source.getIrradiatedBy(), source.getIrradiateOwner(),
                                                   target, IRRADIATE_HIT_HEADING,
                                                   instructionLimit);
    }


    // Helpers

    /**
     * Creates and completes a spell unit. A spell field (Dark Swarm, Disruption Web) is made non-colliding at its spell field elevation before it
     * completes. A failed creation shows the notified player the pending creation message.
     *
     * @param type             the {@code units.dat} index of the new unit
     * @param positionX        the requested position X, clamped to the map
     * @param positionY        the requested position Y, clamped to the map
     * @param owner            the owner player of the new unit
     * @param field            whether the new unit is a spell field
     * @param notifiedPlayer   the player shown the pending creation message on failure
     * @param instructionLimit the creation and completion budget
     * @return the created spell unit, or {@code null} if construction failed
     * @throws UnsupportedOperationException if the unit creation context or the owner player record is missing, or the unit accounting is not
     *                                       initialized
     * @throws IllegalStateException         if the created unit has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    private @Nullable Unit createCompletedUnit(int type,
                                               int positionX, int positionY,
                                               int owner,
                                               boolean field,
                                               int notifiedPlayer,
                                               int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int horizontal = UnitPositioning.clampCoordinate(positionX,
                                                         units.getDimensionLeft(type), units.getDimensionRight(type),
                                                         this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE,
                                                         0);
        int vertical = UnitPositioning.clampCoordinate(positionY,
                                                       units.getDimensionUp(type), units.getDimensionDown(type),
                                                       this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE,
                                                       UnitPositioning.MAP_BOTTOM_MARGIN);
        UnitCreationContext context = this.game.getUnitSystem().requireUnitCreationContext();
        Unit unit = this.game.getUnitStorageConstructor().create(context,
                                                                 this.game.getPlayerTable().requireUnitAccounting(),
                                                                 type,
                                                                 horizontal, vertical,
                                                                 owner,
                                                                 0,
                                                                 instructionLimit);

        if (unit == null) {
            this.game.getUnitStorageConstructor().notifyCreationFailure(notifiedPlayer);

            return null;
        }

        if (field) {
            unit.setStatus(unit.getStatus() | UnitStatusFlags.NO_COLLIDE);
            unit.requireSprite().setElevationLevel(SPELL_FIELD_ELEVATION);
        }

        Player player = this.game.getPlayerTable().getPlayer(owner);

        if (player == null) {
            throw Exceptions.newUnsupportedOperationException("Spell unit creation requires the owner player record.");
        }

        this.game.getUnitCompletion().initializeBeforePlacement(context, unit, instructionLimit);
        this.game.getUnitCompletion().completeAfterPlacement(context, unit, player.getType(), instructionLimit);

        return unit;
    }

    private @NotNull Unit @NotNull [] collectUnits(int left, int upper, int right, int lower) {
        UnitSpatialIndex spatialIndex = this.game.getUnitSystem().requireUnitSpatialIndex();
        int count = spatialIndex.collectPlacementCandidates(left, upper, right, lower);
        Unit[] units = new Unit[count];

        for (int index = 0; index < count; index++) {
            units[index] = spatialIndex.getPlacementCandidate(index);
        }

        return units;
    }

    /**
     * Creates an effect at a unit's signed sprite position and applies its elevation only after successful creation.
     *
     * @param spriteType       the {@code sprites.dat} index
     * @param unit             the unit at whose sprite the effect is created
     * @param instructionLimit the sprite initialization budget
     * @throws IllegalStateException if the unit has no sprite
     */
    private void createEffect(int spriteType, @NotNull Unit unit, int instructionLimit) {
        int positionY = (short) unit.requireSprite().getY();
        int positionX = (short) unit.requireSprite().getX();
        SpriteEffect effect = this.game.getIscriptExecutor().createSpriteEffect(spriteType, positionX, positionY, 0, instructionLimit);

        if (effect == null) {
            return;
        }

        int elevation = unit.requireSprite().getElevationLevel() + 1;
        Sprite sprite = effect.getSprite();

        sprite.setElevationLevel(elevation);

        SpriteVisibility.updatePositionTile(sprite, this.game.getMap(), this.game.getLocalVision().getComparisonMask());
    }

    private void createEffect(int spriteType, int positionX, int positionY, int elevation, int instructionLimit) {
        SpriteEffect effect = this.game.getIscriptExecutor().createSpriteEffect(spriteType, positionX, positionY, 0, instructionLimit);

        if (effect == null) {
            return;
        }

        Sprite sprite = effect.getSprite();

        sprite.setElevationLevel(elevation);

        SpriteVisibility.updatePositionTile(sprite, this.game.getMap(), this.game.getLocalVision().getComparisonMask());
    }

    private void createTopImage(@NotNull Unit unit, int imageType, int instructionLimit) {
        Unit carrier = unit.getSubunit() != null ? unit.getSubunit() : unit;
        Sprite sprite = carrier.requireSprite();

        this.game.getIscriptExecutor().createImage(sprite,
                                                   sprite.getImages().isEmpty() ? null : sprite.getImages().getFirst(),
                                                   imageType,
                                                   0, 0,
                                                   0,
                                                   carrier,
                                                   instructionLimit);
    }

    /**
     * Destroys the first matching overlay of a unit and its subunit.
     * <p>
     * Image storage is used only for a matching image.
     *
     * @param unit  the unit
     * @param first the first {@code images.dat} index
     * @param last  the last {@code images.dat} index
     * @throws IllegalStateException if the unit or the subunit has no sprite
     */
    public void destroyOverlay(@NotNull Unit unit, int first, int last) {
        this.destroySpriteImages(unit.requireSprite(), first, last);

        if (unit.getSubunit() != null) {
            this.destroySpriteImages(unit.getSubunit().requireSprite(), first, last);
        }
    }

    private void destroySpriteImages(@NotNull Sprite sprite, int first, int last) {
        SpriteImage image = findSpriteImage(sprite, first, last);

        if (image != null) {
            this.game.getIscriptExecutor().destroyImage(image);
        }
    }

    private void playSound(int sound, @NotNull Unit unit) {
        Sprite sprite = unit.getSpriteOrNull();

        this.game.getInterfaceLink().getSoundEvents().add(sound,
                                                          sprite == null ? 0 : sprite.getX(), sprite == null ? 0 : sprite.getY(),
                                                          unit.getUnitId());
    }

    /**
     * Reports a cast technology to the simulation plugins ({@link ScrSimulationPlugins#reportSpellCast}) when the game has them.
     *
     * @param caster     the casting unit
     * @param technology the techdata.dat entry
     * @param target     the target unit, or {@code null} for none
     * @param positionX  the target X coordinate
     * @param positionY  the target Y coordinate
     */
    private void reportSpellCast(@NotNull Unit caster, int technology, @Nullable Unit target, int positionX, int positionY) {
        ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

        if (simulation != null) {
            simulation.reportSpellCast(caster, technology, target, positionX, positionY);
        }
    }
}
