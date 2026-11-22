package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUpgradeIds;
import org.fogofwarlabs.sc4j.scr.content.ScrWeaponIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrOrderTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitComputerFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerUpgrades;
import org.fogofwarlabs.sc4j.scr.game.resource.ResourceAreas;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegions;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Applies the computer attack planning: the attack script opcodes, the staging region search, the attack group
 * bookkeeping, the attack target search, the suitability test and the strength attack.
 * <p>
 * The staging search marks regions per owner: own for the owner's buildings (or all units without buildings), enemy
 * for regions of visible enemy attackers and buildings; flooding over walkable neighbors turns reached empty regions
 * into reached empty, reached enemy regions into reached enemy and then target enemy, and empty neighbors of those into
 * enemy neighbor. Each target enemy region proposes a staging region among the reached empty regions around it.
 */
public final class ComputerAttack {
    /**
     * The units an egg of a two-units-in-one-egg type hatches.
     */
    static final int TWIN_EGG_UNITS = 2;

    /**
     * Unit types {@code TERRAN_MISSILE_TURRET + bit} of the static air defenses: Missile Turret, Bunker, Spore Colony and
     * Photon Cannon; an attack group without ground units does not target them.
     */
    private static final long AIR_DEFENSE_TYPES = 0x4000100003L;
    /**
     * The highest bit of {@link #AIR_DEFENSE_TYPES} the test reads.
     */
    private static final int AIR_DEFENSE_LAST_OFFSET = 0x26;

    /**
     * The positions of the horizontal and the vertical coordinate in a region center pair.
     */
    private static final int HORIZONTAL_CENTER_INDEX = 0;
    private static final int VERTICAL_CENTER_INDEX = 1;

    /**
     * The staging distance preference; distances below {@link #FIRST_PASS_MINIMUM_DISTANCE} require the second pass.
     */
    private static final int @NotNull [] STAGING_PREFERENCES = {4, 3, 5, 6, 2};
    /**
     * The smallest staging distance of the first pass.
     */
    private static final int FIRST_PASS_MINIMUM_DISTANCE = 4;
    /**
     * The neighbor distance levels the staging search spreads from an enemy region.
     */
    private static final int STAGING_SEARCH_LEVELS = 5;
    /**
     * Unit types {@code TERRAN_SCIENCE_VESSEL + bit} left behind when a region's units move to the attack: Science Vessel,
     * Queen, Defiler and High Templar.
     */
    private static final long TRANSFER_EXCLUDED_TYPES = 0x400003000000001L;
    /**
     * The highest bit of {@link #TRANSFER_EXCLUDED_TYPES} the test reads.
     */
    private static final int TRANSFER_EXCLUDED_LAST_OFFSET = 0x3A;

    /**
     * The region mark of an empty region.
     */
    private static final int MARK_NONE = 0;
    /**
     * The region mark of a visible enemy attacker or building.
     */
    private static final int MARK_ENEMY = 1;
    /**
     * The region mark of an enemy region reached by the flood.
     */
    private static final int MARK_REACHED_ENEMY = 2;
    /**
     * The region mark of a reached enemy region that proposes a staging region.
     */
    private static final int MARK_TARGET_ENEMY = 3;
    /**
     * The region mark of an empty neighbor of a proposing enemy region.
     */
    private static final int MARK_ENEMY_NEIGHBOR = 4;
    /**
     * The region mark of the owner's buildings, or of all its units without buildings; the flood spreads from it.
     */
    private static final int MARK_OWN = 5;
    /**
     * The region mark of an empty region reached by the flood.
     */
    private static final int MARK_REACHED_EMPTY = 6;

    /**
     * The attack group entry of a type that is present; also the value of a lost entry less one.
     */
    private static final int PRESENT_ATTACK_TYPE = 228;
    /**
     * The resource area identities below this are read; larger identities give position zero.
     */
    private static final int AREA_LIMIT = 250;
    /**
     * The attack group is repeated at most this many times by the harass factor.
     */
    private static final int MAXIMUM_HARASS_REPEATS = 3;

    /**
     * The random source of the campaign staging choice.
     */
    private static final int STAGING_RANDOM_SOURCE = 0x28;
    /**
     * The random source of the suicide target's first owner list.
     */
    private static final int SUICIDE_RANDOM_SOURCE = 0x36;

    /**
     * Enemy regions farther than this beyond the nearest one from the first town are not compared.
     */
    private static final int SOURCE_DISTANCE_SLACK = 0x280;
    /**
     * The radius around an enemy region center whose enemy strength is compared.
     */
    private static final int SOURCE_STRENGTH_RADIUS = 0x200;
    /**
     * The updates between two strength attacks.
     */
    private static final int STRENGTH_ATTACK_DELAY = 0x18;
    /**
     * The radius around the staging target whose enemy strength the harass evaluation sums.
     */
    private static final int HARASS_RADIUS = 0x400;
    /**
     * The rush owner walk stops at a building of an owner already chosen closer than this.
     */
    private static final int RUSH_OWNER_RADIUS = 0x141;
    /**
     * The Marines a completed Bunker holds for the rush counts.
     */
    private static final int MARINES_PER_BUNKER = 4;
    /**
     * A completed Sunken or Spore Colony counts this many times in the Zerg rush defense counts.
     */
    private static final int COLONY_DEFENSE_WEIGHT = 2;
    /**
     * The low WORD of a packed value: the air enemy strength of a region record or an unsigned unit strength.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The shift of the ground enemy strength in the high WORD of a region record's packed enemy strength.
     */
    private static final int HIGH_WORD_SHIFT = Short.SIZE;

    /**
     * Rush kind {@code 0}: the enemy has no Barracks, Spawning Pool or Gateway.
     */
    private static final int RUSH_NO_PRODUCTION = 0;
    /**
     * Rush kind {@code 1}: the enemy's Bunker defense, Sunken defense and completed Zealots are at most these limits.
     */
    private static final int RUSH_GROUND = 1;
    private static final int RUSH_GROUND_BUNKER_LIMIT = 0x10;
    private static final int RUSH_GROUND_SUNKEN_LIMIT = 10;
    private static final int RUSH_GROUND_ZEALOT_LIMIT = 6;
    /**
     * Rush kind {@code 2}: the enemy's Bunker defense and Spore defense are at most these limits.
     */
    private static final int RUSH_AIR = 2;
    private static final int RUSH_AIR_BUNKER_LIMIT = 0x18;
    private static final int RUSH_AIR_SPORE_LIMIT = 10;
    /**
     * Rush kind {@code 3}: the enemy has no completed Hydralisk Den, and its Bunker defense, Sunken defense and completed
     * Zealots are at most these limits.
     */
    private static final int RUSH_BEFORE_DEN = 3;
    private static final int RUSH_BEFORE_DEN_BUNKER_LIMIT = 5;
    private static final int RUSH_BEFORE_DEN_SUNKEN_LIMIT = 2;
    private static final int RUSH_BEFORE_DEN_ZEALOT_LIMIT = 1;
    /**
     * Rush kind {@code 4}: the enemy's Bunker defense, Sunken defense and completed Zealots are at most these limits.
     */
    private static final int RUSH_ZEALOT_GROUND = 4;
    private static final int RUSH_ZEALOT_GROUND_BUNKER_LIMIT = 0x10;
    private static final int RUSH_ZEALOT_GROUND_SUNKEN_LIMIT = 10;
    private static final int RUSH_ZEALOT_GROUND_ZEALOT_LIMIT = 8;
    /**
     * Rush kind {@code 5}: the enemy's Bunker defense, Sunken defense and completed Zealots are at most these limits.
     */
    private static final int RUSH_LIGHT_GROUND = 5;
    private static final int RUSH_LIGHT_GROUND_BUNKER_LIMIT = 6;
    private static final int RUSH_LIGHT_GROUND_SUNKEN_LIMIT = 6;
    private static final int RUSH_LIGHT_GROUND_ZEALOT_LIMIT = 3;
    /**
     * Rush kind {@code 6}: the enemy's Bunker defense, completed Sunken Colonies and completed Dragoons are at most these
     * limits.
     */
    private static final int RUSH_FEW_COLONIES = 6;
    private static final int RUSH_FEW_COLONIES_BUNKER_LIMIT = 0xC;
    private static final int RUSH_FEW_COLONIES_COLONY_LIMIT = 1;
    private static final int RUSH_FEW_COLONIES_DRAGOON_LIMIT = 1;
    /**
     * Rush kind {@code 7}: the enemy has no completed Siege Tank in Tank Mode or Queen, and at most this many completed
     * Zealots.
     */
    private static final int RUSH_BEFORE_SIEGE = 7;
    private static final int RUSH_BEFORE_SIEGE_ZEALOT_LIMIT = 6;
    /**
     * Rush kind {@code 8}: the enemy's Bunker defense, Sunken defense and completed Zealots are at most these limits.
     */
    private static final int RUSH_MINIMAL_GROUND = 8;
    private static final int RUSH_MINIMAL_GROUND_BUNKER_LIMIT = 5;
    private static final int RUSH_MINIMAL_GROUND_SUNKEN_LIMIT = 2;
    private static final int RUSH_MINIMAL_GROUND_ZEALOT_LIMIT = 1;
    /**
     * Rush kind {@code 9}: the enemy's Bunker defense, Sunken defense and completed Zealots are at most these limits.
     */
    private static final int RUSH_MEDIUM_GROUND = 9;
    private static final int RUSH_MEDIUM_GROUND_BUNKER_LIMIT = 9;
    private static final int RUSH_MEDIUM_GROUND_SUNKEN_LIMIT = 4;
    private static final int RUSH_MEDIUM_GROUND_ZEALOT_LIMIT = 5;
    /**
     * Rush kind {@code 10}: the enemy's Bunker defense, Sunken defense and completed Zealots are at most these limits.
     */
    private static final int RUSH_SMALL_GROUND = 10;
    private static final int RUSH_SMALL_GROUND_BUNKER_LIMIT = 4;
    private static final int RUSH_SMALL_GROUND_SUNKEN_LIMIT = 4;
    private static final int RUSH_SMALL_GROUND_ZEALOT_LIMIT = 2;
    /**
     * Rush kind {@code 11}: the enemy's Bunker defense, Sunken defense and completed Zealots are at most these limits.
     */
    private static final int RUSH_EVEN_GROUND = 11;
    private static final int RUSH_EVEN_GROUND_BUNKER_LIMIT = 10;
    private static final int RUSH_EVEN_GROUND_SUNKEN_LIMIT = 10;
    private static final int RUSH_EVEN_GROUND_ZEALOT_LIMIT = 5;
    /**
     * Rush kind {@code 12}: the enemy's Bunker defense, Spore defense and completed Scouts and Dragoons together are at
     * most these limits.
     */
    private static final int RUSH_LIGHT_AIR = 12;
    private static final int RUSH_LIGHT_AIR_BUNKER_LIMIT = 0x10;
    private static final int RUSH_LIGHT_AIR_SPORE_LIMIT = 5;
    private static final int RUSH_LIGHT_AIR_SCOUT_DRAGOON_LIMIT = 2;
    /**
     * Rush kind {@code 13}: the enemy's Bunker defense, Spore defense and completed Scouts and Dragoons together are at
     * most these limits.
     */
    private static final int RUSH_HEAVY_AIR = 13;
    private static final int RUSH_HEAVY_AIR_BUNKER_LIMIT = 0x18;
    private static final int RUSH_HEAVY_AIR_SPORE_LIMIT = 10;
    private static final int RUSH_HEAVY_AIR_SCOUT_DRAGOON_LIMIT = 7;

    /**
     * The route results of the route score: the route end and the staging region.
     */
    private static final int ROUTE_END = 0;
    private static final int ROUTE_STAGING = 1;
    private static final int ROUTE_SIZE = 2;
    /**
     * The score of a target in another ground component, also the initial best score and distance.
     */
    private static final int DIFFERENT_COMPONENT_SCORE = ComputerPlayerPhase.NO_DISTANCE;
    /**
     * The score of a target without a ground route; such a target is skipped.
     */
    private static final int NO_ROUTE_SCORE = 100000000;
    /**
     * A route of at most two regions sums only its ends.
     */
    private static final int DIRECT_ROUTE_LENGTH = 2;
    /**
     * The staging region lies this many route regions before the route end.
     */
    private static final int STAGING_ROUTE_DISTANCE = 3;
    /**
     * The tile count a staging region needs.
     */
    private static final int STAGING_MINIMUM_TILES = 0x3C;
    /**
     * A lone enemy start area defended by more than this strength is not targeted early.
     */
    private static final int DEFENDED_START_STRENGTH = 1000;
    /**
     * The last clock tick of the early-game rule of the expansion target.
     */
    private static final int EARLY_ATTACK_CLOCK = 0x5DB;

    /**
     * Clears the attack group and resets the staging regions.
     *
     * @param phase      the owning phase
     * @param owner      the computer owner
     * @param resetFrame whether the preparation clock is cleared too
     */
    static void clear(@NotNull ComputerPlayerPhase phase, int owner, boolean resetFrame) {
        if (owner >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return;
        }

        phase.getPlayer(owner).clearAttack(resetFrame);
        phase.getRegions().cancelStaging(phase, owner);
    }

    /**
     * @param type the unit type
     * @return {@code true} if the type is a static air defense ({@link #AIR_DEFENSE_TYPES}), {@code false} otherwise
     */
    static boolean isAirDefense(int type) {
        int offset = type - ScrUnitIds.TERRAN_MISSILE_TURRET;

        return offset >= 0 && offset <= AIR_DEFENSE_LAST_OFFSET && (AIR_DEFENSE_TYPES >>> offset & 1) != 0;
    }

    /**
     * Returns the walkable and inaccessible neighbor count for {@code true} and the walkable neighbor count for
     * {@code false}.
     *
     * @throws UnsupportedOperationException if the region's neighbor count underflows
     */
    private static int getNeighborCount(@NotNull TerrainRegions terrain, int region, boolean walkable) {
        if (!walkable) {
            return terrain.getWalkableNeighborCount(region);
        }

        int count = ComputerTerrain.getPathNeighborCount(terrain, region);

        if (count < 0) {
            throw Exceptions.newUnsupportedOperationException("Region {0} lists more region-zero neighbors than neighbors.", region);
        }

        return count;
    }

    /**
     * Returns the horizontal position of a resource area, zero outside the read identities.
     */
    private static int getAreaHorizontalPosition(@NotNull ResourceAreas areas, int area) {
        return area > ResourceAreas.NO_AREA && area < AREA_LIMIT ? (short) areas.getHorizontalPosition(area) : 0;
    }

    /**
     * Returns the vertical position of a resource area, zero outside the read identities.
     */
    private static int getAreaVerticalPosition(@NotNull ResourceAreas areas, int area) {
        return area > ResourceAreas.NO_AREA && area < AREA_LIMIT ? (short) areas.getVerticalPosition(area) : 0;
    }

    /**
     * Returns the sum of the air and ground enemy strength WORDs of the owner's region record.
     */
    private static int getEnemyStrength(@NotNull ComputerPlayerPhase phase, int owner, int region) {
        ComputerRegions regions = phase.getRegions();
        int packed = regions.getEnemyStrength(regions.getIndex(owner, region));

        return (packed & WORD_MASK) + (packed >>> HIGH_WORD_SHIFT);
    }

    /**
     * Returns the completed Marines plus as many again, at most {@link #MARINES_PER_BUNKER} per completed Bunker.
     */
    private static int countBunkerDefense(@NotNull ComputerUnits computerUnits, int player) {
        int bunkers = computerUnits.getCompletedTypeCount(ScrUnitIds.TERRAN_BUNKER, player);
        int marines = computerUnits.getCompletedTypeCount(ScrUnitIds.TERRAN_MARINE, player);

        return marines + (Integer.compareUnsigned(marines, bunkers * MARINES_PER_BUNKER) < 0 ? marines : bunkers * MARINES_PER_BUNKER);
    }

    /**
     * Returns the completed Hydralisks plus {@link #COLONY_DEFENSE_WEIGHT} times the completed Sunken Colonies.
     */
    private static int countSunkenDefense(@NotNull ComputerUnits computerUnits, int player) {
        return computerUnits.getCompletedTypeCount(ScrUnitIds.ZERG_HYDRALISK, player) +
               computerUnits.getCompletedTypeCount(ScrUnitIds.ZERG_SUNKEN_COLONY, player) * COLONY_DEFENSE_WEIGHT;
    }

    /**
     * Returns the completed Hydralisks and Mutalisks plus {@link #COLONY_DEFENSE_WEIGHT} times the completed Spore Colonies.
     */
    private static int countSporeDefense(@NotNull ComputerUnits computerUnits, int player) {
        return computerUnits.getCompletedTypeCount(ScrUnitIds.ZERG_HYDRALISK, player) +
               computerUnits.getCompletedTypeCount(ScrUnitIds.ZERG_MUTALISK, player) +
               computerUnits.getCompletedTypeCount(ScrUnitIds.ZERG_SPORE_COLONY, player) * COLONY_DEFENSE_WEIGHT;
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link ComputerAttack} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public ComputerAttack(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Returns whether a region's unit follows its region to an attack: not a Science Vessel, Queen, Defiler or High
     * Templar, and not inside a Bunker outside campaigns.
     *
     * @param phase the owning phase
     * @param unit  the region's unit
     * @return {@code true} if the unit follows its region, {@code false} otherwise
     */
    boolean isTransferable(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit) {
        int offset = this.game.requireGameData().getBaseType(unit) - ScrUnitIds.TERRAN_SCIENCE_VESSEL;

        if (offset >= 0 && offset <= TRANSFER_EXCLUDED_LAST_OFFSET && (TRANSFER_EXCLUDED_TYPES >>> offset & 1) != 0) {
            return false;
        }

        return (unit.getStatus() & UnitStatusFlags.IN_BUNKER) == 0 || phase.getPlayer(unit.getOwnerIndex()).isCampaign();
    }

    /**
     * @param owner the computer owner
     * @return {@code true} if the owner has a completed Shuttle or Dropship, or an Overlord with Ventral Sacs, {@code false} otherwise
     * @throws UnsupportedOperationException if the unit accounting or the player upgrades are missing
     */
    boolean hasTransports(int owner) {
        ComputerUnits computerUnits = this.game.getComputerUnits();

        if (computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_SHUTTLE, owner) != 0 ||
            computerUnits.getCompletedTypeCount(ScrUnitIds.TERRAN_DROPSHIP, owner) != 0) {
            return true;
        }

        PlayerUpgrades upgrades = this.game.getPlayerTable().getPlayerUpgrades();

        if (upgrades == null) {
            throw Exceptions.newUnsupportedOperationException("Computer transport counts require player upgrades.");
        }

        return upgrades.getLevel(owner, ScrUpgradeIds.VENTRAL_SACS) != 0 && computerUnits.getCompletedTypeCount(ScrUnitIds.ZERG_OVERLORD, owner) != 0;
    }

    /**
     * @param phase the owning phase
     * @param index the region record
     * @return {@code true} if the region has ground units, the owner has no transports and the first unit cannot reach
     * the region center, {@code false} otherwise
     * @throws IllegalStateException if a military record of the region holds no unit
     */
    boolean isUnreachable(@NotNull ComputerPlayerPhase phase, int index) {
        ComputerRegions regions = phase.getRegions();
        ComputerUnitRecord first = regions.getFirstMilitaryRecord(index);
        boolean ground = false;

        for (ComputerUnitRecord record = first; record != null; record = record.next) {
            if ((ComputerMilitary.requireRecordUnit(record).getStatus() & UnitStatusFlags.FLYING) == 0) {
                ground = true;
            }
        }

        if (first == null || !ground || this.hasTransports(regions.getOwner(index))) {
            return false;
        }

        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        int region = regions.getRegion(index);

        return !this.game.getUnitPositioning().isGroundReachable(ComputerMilitary.requireRecordUnit(first),
                                                                 (short) terrain.getCenterX(region), (short) terrain.getCenterY(region));
    }

    /**
     * Evaluates aiscript {@code eval_harass}: the attack preparation without flags must choose a staging region; then the
     * attack group's values are compared with the strengths of the live enemies around the center of the staging record's
     * linked region.
     *
     * @param phase     the owning phase
     * @param owner     the script owner
     * @param positionX the script center X
     * @param positionY the script center Y
     * @return {@code true} if the enemy air or ground strength there is below the group's, {@code false} otherwise
     * @throws IllegalStateException         if a live enemy unit has no sprite
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    boolean isHarassFavorable(@NotNull ComputerPlayerPhase phase, int owner, int positionX, int positionY) {
        if (this.prepare(phase, owner, positionX, positionY, false, false)) {
            return false;
        }

        ComputerPlayer player = phase.getPlayer(owner);
        int groupAir = 0;
        int groupGround = 0;

        for (short entry : player.getAttackTypes()) {
            if (entry == 0) {
                break;
            }

            int type = Short.toUnsignedInt(entry) - 1;

            groupAir += phase.getAirValue(type);
            groupGround += phase.getGroundValue(type);
        }

        int[] center = this.getLinkedRegionCenter(phase, owner);
        int enemyAir = 0;
        int enemyGround = 0;
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || !this.game.getComputerUnits().isEnemy(owner, unit)) {
                continue;
            }

            Sprite sprite = unit.requireSprite();

            if (RegionPathSearch.calculateDistance((short) sprite.getX(), center[HORIZONTAL_CENTER_INDEX],
                                                   (short) sprite.getY(), center[VERTICAL_CENTER_INDEX]) < HARASS_RADIUS) {
                enemyAir += unit.getAirStrength();
                enemyGround += unit.getGroundStrength();
            }
        }

        return Integer.compareUnsigned(enemyAir, groupAir) < 0 || Integer.compareUnsigned(enemyGround, groupGround) < 0;
    }

    /**
     * Returns the center of the linked region of the staging record, as the harass and rush evaluations read it.
     *
     * @throws UnsupportedOperationException if the owner has no attack staging region
     */
    private int @NotNull [] getLinkedRegionCenter(@NotNull ComputerPlayerPhase phase, int owner) {
        ComputerRegions regions = phase.getRegions();
        int staging = phase.getPlayer(owner).getAttackRegion();

        if (staging == 0) {
            throw Exceptions.newUnsupportedOperationException("The linked region center requires an attack staging region: owner={0}", owner);
        }

        int linked = regions.getLinkedRegion(regions.getIndex(owner, staging - 1));
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();

        return new int[]{(short) terrain.getCenterX(linked), (short) terrain.getCenterY(linked)};
    }

    /**
     * Evaluates aiscript {@code rush}: the attack preparation without flags must choose a staging region. The owner of
     * the nearest live enemy building to the center of the staging record's linked region is chosen; the walk stops at a
     * building close to an owner already chosen. The attack is then cleared and the rush kind compares the chosen
     * player's counts with its limits.
     *
     * @param phase     the owning phase
     * @param owner     the script owner
     * @param kind      the rush kind BYTE
     * @param positionX the script center X
     * @param positionY the script center Y
     * @return {@code true} if the rush applies, {@code false} otherwise
     * @throws UnsupportedOperationException if the unit accounting is missing or the unit pool is not initialized
     * @throws IllegalStateException         if a live enemy building has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    boolean isRushViable(@NotNull ComputerPlayerPhase phase, int owner, int kind, int positionX, int positionY) {
        if (this.prepare(phase, owner, positionX, positionY, false, false)) {
            return false;
        }

        int[] center = this.getLinkedRegionCenter(phase, owner);
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        int chosen = -1;
        int nearest = ComputerPlayerPhase.NO_DISTANCE;

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || !UnitQueries.isBuilding(this.game.requireGameData().getUnitTypes(), unit.getUnitId()) ||
                !this.game.getComputerUnits().isEnemy(owner, unit)) {
                continue;
            }

            Sprite sprite = unit.requireSprite();
            int distance = RegionPathSearch.calculateDistance((short) sprite.getX(), center[HORIZONTAL_CENTER_INDEX],
                                                              (short) sprite.getY(), center[VERTICAL_CENTER_INDEX]);

            if (Integer.compareUnsigned(distance, RUSH_OWNER_RADIUS) < 0 && chosen == unit.getOwnerIndex()) {
                break;
            }

            if (Integer.compareUnsigned(distance, nearest) <= 0) {
                chosen  = unit.getOwnerIndex();
                nearest = distance;
            }
        }

        if (chosen == -1) {
            return false;
        }

        clear(phase, owner, true);

        ComputerUnits computerUnits = this.game.getComputerUnits();
        int enemy = chosen;

        // The unit-count limits for each rush kind.
        return switch (kind) {
            case RUSH_NO_PRODUCTION -> computerUnits.getTypeCount(ScrUnitIds.TERRAN_BARRACKS, enemy) == 0 &&
                                       computerUnits.getTypeCount(ScrUnitIds.ZERG_SPAWNING_POOL, enemy) == 0 &&
                                       computerUnits.getTypeCount(ScrUnitIds.PROTOSS_GATEWAY, enemy) == 0;
            case RUSH_GROUND -> countBunkerDefense(computerUnits, enemy) <= RUSH_GROUND_BUNKER_LIMIT &&
                                countSunkenDefense(computerUnits, enemy) <= RUSH_GROUND_SUNKEN_LIMIT &&
                                computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_ZEALOT, enemy) <= RUSH_GROUND_ZEALOT_LIMIT;
            case RUSH_AIR -> countBunkerDefense(computerUnits, enemy) <= RUSH_AIR_BUNKER_LIMIT &&
                             countSporeDefense(computerUnits, enemy) <= RUSH_AIR_SPORE_LIMIT;
            case RUSH_BEFORE_DEN -> countBunkerDefense(computerUnits, enemy) <= RUSH_BEFORE_DEN_BUNKER_LIMIT &&
                                    countSunkenDefense(computerUnits, enemy) <= RUSH_BEFORE_DEN_SUNKEN_LIMIT &&
                                    computerUnits.getCompletedTypeCount(ScrUnitIds.ZERG_HYDRALISK_DEN, enemy) == 0 &&
                                    computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_ZEALOT, enemy) <= RUSH_BEFORE_DEN_ZEALOT_LIMIT;
            case RUSH_ZEALOT_GROUND -> countBunkerDefense(computerUnits, enemy) <= RUSH_ZEALOT_GROUND_BUNKER_LIMIT &&
                                       countSunkenDefense(computerUnits, enemy) <= RUSH_ZEALOT_GROUND_SUNKEN_LIMIT &&
                                       computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_ZEALOT, enemy) <= RUSH_ZEALOT_GROUND_ZEALOT_LIMIT;
            case RUSH_LIGHT_GROUND -> countBunkerDefense(computerUnits, enemy) <= RUSH_LIGHT_GROUND_BUNKER_LIMIT &&
                                      countSunkenDefense(computerUnits, enemy) <= RUSH_LIGHT_GROUND_SUNKEN_LIMIT &&
                                      computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_ZEALOT, enemy) <= RUSH_LIGHT_GROUND_ZEALOT_LIMIT;
            case RUSH_FEW_COLONIES -> countBunkerDefense(computerUnits, enemy) <= RUSH_FEW_COLONIES_BUNKER_LIMIT &&
                                      computerUnits.getCompletedTypeCount(ScrUnitIds.ZERG_SUNKEN_COLONY, enemy) <= RUSH_FEW_COLONIES_COLONY_LIMIT &&
                                      computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_DRAGOON, enemy) <= RUSH_FEW_COLONIES_DRAGOON_LIMIT;
            case RUSH_BEFORE_SIEGE -> computerUnits.getCompletedTypeCount(ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE, enemy) == 0 &&
                                      computerUnits.getCompletedTypeCount(ScrUnitIds.ZERG_QUEEN, enemy) == 0 &&
                                      computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_ZEALOT, enemy) <= RUSH_BEFORE_SIEGE_ZEALOT_LIMIT;
            case RUSH_MINIMAL_GROUND -> countBunkerDefense(computerUnits, enemy) <= RUSH_MINIMAL_GROUND_BUNKER_LIMIT &&
                                        countSunkenDefense(computerUnits, enemy) <= RUSH_MINIMAL_GROUND_SUNKEN_LIMIT &&
                                        computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_ZEALOT, enemy) <= RUSH_MINIMAL_GROUND_ZEALOT_LIMIT;
            case RUSH_MEDIUM_GROUND -> countBunkerDefense(computerUnits, enemy) <= RUSH_MEDIUM_GROUND_BUNKER_LIMIT &&
                                       countSunkenDefense(computerUnits, enemy) <= RUSH_MEDIUM_GROUND_SUNKEN_LIMIT &&
                                       computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_ZEALOT, enemy) <= RUSH_MEDIUM_GROUND_ZEALOT_LIMIT;
            case RUSH_SMALL_GROUND -> countBunkerDefense(computerUnits, enemy) <= RUSH_SMALL_GROUND_BUNKER_LIMIT &&
                                      countSunkenDefense(computerUnits, enemy) <= RUSH_SMALL_GROUND_SUNKEN_LIMIT &&
                                      computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_ZEALOT, enemy) <= RUSH_SMALL_GROUND_ZEALOT_LIMIT;
            case RUSH_EVEN_GROUND -> countBunkerDefense(computerUnits, enemy) <= RUSH_EVEN_GROUND_BUNKER_LIMIT &&
                                     countSunkenDefense(computerUnits, enemy) <= RUSH_EVEN_GROUND_SUNKEN_LIMIT &&
                                     computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_ZEALOT, enemy) <= RUSH_EVEN_GROUND_ZEALOT_LIMIT;
            case RUSH_LIGHT_AIR -> countBunkerDefense(computerUnits, enemy) <= RUSH_LIGHT_AIR_BUNKER_LIMIT &&
                                   countSporeDefense(computerUnits, enemy) <= RUSH_LIGHT_AIR_SPORE_LIMIT &&
                                   Integer.compareUnsigned(computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_SCOUT, enemy) +
                                                           computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_DRAGOON, enemy),
                                                           RUSH_LIGHT_AIR_SCOUT_DRAGOON_LIMIT) <= 0;
            case RUSH_HEAVY_AIR -> countBunkerDefense(computerUnits, enemy) <= RUSH_HEAVY_AIR_BUNKER_LIMIT &&
                                   countSporeDefense(computerUnits, enemy) <= RUSH_HEAVY_AIR_SPORE_LIMIT &&
                                   Integer.compareUnsigned(computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_SCOUT, enemy) +
                                                           computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_DRAGOON, enemy),
                                                           RUSH_HEAVY_AIR_SCOUT_DRAGOON_LIMIT) <= 0;
            default -> false;
        };
    }


    /**
     * Prepares an attack: marks the regions and chooses a staging region near the reference position, first for the
     * group as it is and then, with {@code includeFlyers}, as if it were all flyers.
     *
     * @param phase         the owning phase
     * @param owner         the computer owner
     * @param positionX     the horizontal reference position
     * @param positionY     the vertical reference position
     * @param resetFrame    whether the chosen staging region resets the preparation clock
     * @param includeFlyers whether to also search with air neighbors for a group that is not all flyers
     * @return {@code true} if no staging region was chosen and none is still gathering, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    boolean prepare(@NotNull ComputerPlayerPhase phase, int owner, int positionX, int positionY, boolean resetFrame, boolean includeFlyers) {
        ComputerPlayer player = phase.getPlayer(owner);

        if (player.isAttackSearchBlocked()) {
            return true;
        }

        ComputerRegions regions = phase.getRegions();
        int staging = player.getAttackRegion();

        if (staging != 0) {
            return regions.getState(regions.getIndex(owner, staging - 1)) != ComputerRegionStates.ATTACK_GATHERING;
        }

        byte[] marks = this.markRegions(owner);
        short[] types = player.getAttackTypes();
        boolean flyers = types[0] != 0;

        for (int index = 0; index < types.length && types[index] != 0; index++) {
            int type = Short.toUnsignedInt(types[index]) - 1;

            if ((this.game.requireGameData().getUnitTypes().getFlags(type) & UnitTypeFlags.FLYER) == 0) {
                flyers = false;

                break;
            }
        }

        player.setFlags(player.getFlags() | ComputerPlayer.FLAG_PATROL_ASSIGNMENT);

        int clock = phase.getElapsedClockTicks();

        if (this.chooseStaging(phase, owner, marks, positionX, positionY, false, flyers, resetFrame, clock) ||
            this.chooseStaging(phase, owner, marks, positionX, positionY, true, flyers, resetFrame, clock)) {
            return false;
        }

        if (includeFlyers && !flyers &&
            (this.chooseStaging(phase, owner, marks, positionX, positionY, false, true, resetFrame, clock) ||
             this.chooseStaging(phase, owner, marks, positionX, positionY, true, true, resetFrame, clock))) {
            return false;
        }

        player.blockAttackSearch();

        return true;
    }

    /**
     * Builds the region marks of the attack preparation.
     *
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private byte @NotNull [] markRegions(int owner) {
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        int count = terrain.getRegionCount();
        byte[] marks = new byte[count];
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        boolean buildings = false;

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || this.game.getComputerUnits().isSubunit(unit) ||
                !UnitQueries.isBuilding(units, unit.getUnitId())) {
                continue;
            }

            marks[this.game.getComputerTerrain().getUnitRegion(unit)] = MARK_OWN;

            buildings = true;
        }

        if (!buildings) {
            for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
                Unit unit = pool.getUnit(poolIndex);

                if (!ComputerUnits.isDeadOrHidden(unit) && !this.game.getComputerUnits().isSubunit(unit)) {
                    marks[this.game.getComputerTerrain().getUnitRegion(unit)] = MARK_OWN;
                }
            }
        }

        int detection = 1 << owner;

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit)) {
                continue;
            }

            if (!this.game.getUnitQueries().canAttack(unit) && !UnitQueries.isBuilding(units, unit.getUnitId())) {
                continue;
            }

            if ((unit.getStatus() & (UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED)) != 0 &&
                (unit.getDetectionFlags() & detection) == 0) {
                continue;
            }

            if (this.game.getComputerUnits().isEnemy(owner, unit)) {
                marks[this.game.getComputerTerrain().getUnitRegion(unit)] = MARK_ENEMY;
            }
        }

        while (true) {
            byte[] previous = marks.clone();
            boolean changed = false;

            for (int region = 0; region < count; region++) {
                if (Byte.toUnsignedInt(previous[region]) < MARK_OWN) {
                    continue;
                }

                int neighbors = getNeighborCount(terrain, region, true);

                for (int neighborIndex = 0; neighborIndex < neighbors; neighborIndex++) {
                    int neighbor = terrain.getNeighborRegion(region, neighborIndex);

                    if (marks[neighbor] == MARK_ENEMY) {
                        marks[neighbor] = MARK_REACHED_ENEMY;

                        changed = true;
                    } else if (marks[neighbor] == MARK_NONE) {
                        marks[neighbor] = MARK_REACHED_EMPTY;

                        changed = true;
                    }
                }
            }

            if (!changed) {
                break;
            }

            byte[] reached = marks.clone();

            for (int region = 0; region < count; region++) {
                if (reached[region] != MARK_REACHED_ENEMY) {
                    continue;
                }

                marks[region] = MARK_TARGET_ENEMY;

                int neighbors = getNeighborCount(terrain, region, true);

                for (int neighborIndex = 0; neighborIndex < neighbors; neighborIndex++) {
                    int neighbor = terrain.getNeighborRegion(region, neighborIndex);

                    if (marks[neighbor] == MARK_NONE) {
                        marks[neighbor] = MARK_ENEMY_NEIGHBOR;
                    }
                }
            }
        }

        return marks;
    }

    /**
     * Collects the staging proposal of every enemy region, chooses one and puts its staging region in the gathering state
     * linked to the enemy region.
     */
    private boolean chooseStaging(@NotNull ComputerPlayerPhase phase,
                                  int owner,
                                  byte @NotNull [] marks,
                                  int positionX, int positionY,
                                  boolean nearStaging,
                                  boolean flyers,
                                  boolean resetFrame,
                                  int clock) {
        int count = marks.length;
        int[] sources = new int[count];
        int[] targets = new int[count];
        int found = 0;

        for (int region = 1; region < count; region++) {
            int proposal = this.proposeStaging(phase, owner, region, marks, positionX, positionY, nearStaging, flyers);

            if (proposal != 0) {
                sources[found] = region;
                targets[found] = proposal - 1;
                found++;
            }
        }

        if (found == 0) {
            return false;
        }

        int chosen = 0;

        if (found != 1) {
            // Campaign owners draw a random proposal instead of comparing the enemy strength.
            if (phase.getPlayer(owner).isCampaign()) {
                chosen = Integer.remainderUnsigned(this.game.getRandom().next(STAGING_RANDOM_SOURCE), found);
            } else {
                chosen = this.chooseSource(phase, owner, sources, found);
            }
        }

        int target = targets[chosen];

        phase.getPlayer(owner).setAttackRegion(target, resetFrame, clock);

        ComputerRegions regions = phase.getRegions();
        int index = regions.getIndex(owner, target);

        regions.setState(phase, index, ComputerRegionStates.ATTACK_GATHERING);
        regions.setLinkedRegion(index, sources[chosen]);

        return true;
    }

    /**
     * Proposes the staging region of an enemy region in {@link #MARK_TARGET_ENEMY}: labels the reached empty regions by
     * neighbor distance up to six and returns the nearest large region of the first preferred distance.
     *
     * @return the staging region plus one, or {@code 0}
     */
    private int proposeStaging(@NotNull ComputerPlayerPhase phase,
                               int owner,
                               int region,
                               byte @NotNull [] marks,
                               int positionX, int positionY,
                               boolean nearStaging,
                               boolean flyers) {
        if (marks[region] != MARK_TARGET_ENEMY) {
            return 0;
        }

        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        ComputerRegions regions = phase.getRegions();
        int count = marks.length;
        byte[] distances = new byte[count];

        distances[region] = 1;

        for (int level = 1; level <= STAGING_SEARCH_LEVELS; level++) {
            for (int current = 1; current < count; current++) {
                if (distances[current] != level) {
                    continue;
                }

                int neighbors = getNeighborCount(terrain, current, flyers);

                for (int neighborIndex = 0; neighborIndex < neighbors; neighborIndex++) {
                    int neighbor = terrain.getNeighborRegion(current, neighborIndex);
                    int neighborState = regions.getState(regions.getIndex(owner, neighbor));

                    if ((neighborState & ComputerRegionStates.ACTIVE_STATE_BITS) == 0 && neighborState != ComputerRegionStates.ATTACK &&
                        distances[neighbor] == 0 && marks[neighbor] == MARK_REACHED_EMPTY) {
                        distances[neighbor] = (byte) (level + 1);
                    }
                }
            }
        }

        int best = 0;
        int bestDistance = ComputerPlayerPhase.NO_DISTANCE;
        int largestTiles = 0;
        int largest = 0;

        for (int preference : STAGING_PREFERENCES) {
            if (!nearStaging && preference < FIRST_PASS_MINIMUM_DISTANCE) {
                continue;
            }

            for (int candidate = 1; candidate < count; candidate++) {
                if ((regions.getFlags(regions.getIndex(owner, candidate)) & ComputerRegionStates.FLAG_HIT) != 0 ||
                    distances[candidate] != preference) {
                    continue;
                }

                int tiles = terrain.getTileCount(candidate);

                if (tiles > largestTiles) {
                    largestTiles = tiles;
                    largest      = candidate + 1;
                }

                if (tiles < STAGING_MINIMUM_TILES) {
                    continue;
                }

                int distance = RegionPathSearch.calculateDistance(positionX, (short) terrain.getCenterX(candidate),
                                                                  positionY, (short) terrain.getCenterY(candidate));

                if (Integer.compareUnsigned(distance, bestDistance) < 0) {
                    bestDistance = distance;
                    best         = candidate + 1;
                }
            }

            if (best != 0) {
                return best;
            }
        }

        return phase.getPlayer(owner).isCampaign() ? largest : 0;
    }

    /**
     * Chooses among the enemy regions close to the nearest one from the owner's first town the one with the weakest enemy
     * presence around its center.
     *
     * @return the chosen position in {@code sources}
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private int chooseSource(@NotNull ComputerPlayerPhase phase, int owner, int @NotNull [] sources, int count) {
        ComputerTown town = phase.getFirstTown(owner);

        if (town == null) {
            return 0;
        }

        int originX = (short) town.positionX;
        int originY = (short) town.positionY;
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        ComputerTerrain query = this.game.getComputerTerrain();
        int bestDistance = ComputerPlayerPhase.NO_DISTANCE;
        int bestStrength = ComputerPlayerPhase.NO_DISTANCE;
        int result = 0;

        for (int index = 0; index < count; index++) {
            int centerX = (short) terrain.getCenterX(sources[index]);
            int centerY = (short) terrain.getCenterY(sources[index]);
            int distance = RegionPathSearch.calculateDistance(originX, centerX, originY, centerY);

            if (Integer.compareUnsigned(distance, bestDistance + SOURCE_DISTANCE_SLACK) > 0) {
                continue;
            }

            int found = query.collectRectangleUnits((short) (centerX - SOURCE_STRENGTH_RADIUS), (short) (centerY - SOURCE_STRENGTH_RADIUS),
                                                    (short) (centerX + SOURCE_STRENGTH_RADIUS), (short) (centerY + SOURCE_STRENGTH_RADIUS));
            int air = 0;
            int ground = 0;

            for (int unitIndex = 0; unitIndex < found; unitIndex++) {
                Unit unit = this.game.getUnitSystem().requireUnitPool().getUnit(query.getRegionUnit(unitIndex));

                if (this.game.getComputerUnits().isEnemy(owner, unit)) {
                    air += unit.getAirStrength();
                    ground += unit.getGroundStrength();
                }
            }

            int strength = Integer.compareUnsigned(air, ground) > 0 ? air : ground;

            if (Integer.compareUnsigned(strength, bestStrength) < 0 || Integer.compareUnsigned(distance + SOURCE_DISTANCE_SLACK, bestDistance) < 0) {
                bestDistance = distance;
                bestStrength = strength;
                result       = index;
            }
        }

        return result;
    }

    /**
     * Lists the attack group types (Siege Mode counted as Tank Mode) still missing from the staging region; entries
     * matched by a unit there, or with {@code completedOnly} unset by any unit in production, become
     * {@link #PRESENT_ATTACK_TYPE}.
     *
     * @param phase         the owning phase
     * @param index         the staging region record
     * @param list          receives the group types
     * @param completedOnly whether incomplete and morphing units are ignored
     * @return the used length of {@code list}
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    int countMissing(@NotNull ComputerPlayerPhase phase, int index, int @NotNull [] list, boolean completedOnly) {
        ComputerRegions regions = phase.getRegions();
        int owner = regions.getOwner(index);
        short[] types = phase.getPlayer(owner).getAttackTypes();
        int count = 0;

        while (count < types.length && types[count] != 0) {
            list[count] = Short.toUnsignedInt(types[count]) - 1;
            count++;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || this.game.getComputerUnits().isSubunit(unit)) {
                continue;
            }

            int baseType = this.game.requireGameData().getBaseType(unit);
            int type = baseType == ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE ? ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE : baseType;
            int copies = 1;
            int order = unit.getPrimaryOrder();
            boolean producing = (unit.getStatus() & UnitStatusFlags.COMPLETED) == 0 || order == ScrOrderIds.COMPLETING_ARCHON_SUMMON ||
                                order == ScrOrderIds.RESET_COLLISION;

            if (producing) {
                if (completedOnly) {
                    continue;
                }

                if (units.isEgg(type)) {
                    type = UnitQueries.getQueuedType(unit);

                    if ((units.getFlags(type) & UnitTypeFlags.TWO_UNITS_IN_ONE_EGG) != 0) {
                        copies = TWIN_EGG_UNITS;
                    }
                }
            }

            if ((units.getComputerInternalFlags(type) & UnitComputerFlags.ATTACK_GROUP_IGNORED) != 0) {
                continue;
            }

            ComputerUnitRecord record = unit.getComputerRecord();

            if (!producing && (record == null || record.getKind() != ComputerUnitRecord.KIND_MILITARY || record.regionIndex != index)) {
                continue;
            }

            for (; copies != 0; copies--) {
                for (int entry = 0; entry < count; entry++) {
                    if (list[entry] == type) {
                        list[entry] = PRESENT_ATTACK_TYPE;

                        if (entry == count - 1) {
                            count--;
                        }

                        break;
                    }
                }
            }
        }

        return count;
    }

    /**
     * Finds the attack target of a region: the nearest visible enemy (buildings first) in an idle region that the group
     * can attack and reach; otherwise a fallback enemy.
     *
     * @param phase the owning phase
     * @param index the region record
     * @return the target unit, or {@code null}
     * @throws IllegalStateException         if a military record of the region holds no unit or a candidate has no sprite
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    @Nullable Unit findTarget(@NotNull ComputerPlayerPhase phase, int index) {
        ComputerRegions regions = phase.getRegions();
        ComputerUnitRecord first = regions.getFirstMilitaryRecord(index);

        if (first == null) {
            return null;
        }

        boolean noGround = true;
        boolean noAir = true;
        boolean ground = false;

        for (ComputerUnitRecord record = first; record != null; record = record.next) {
            Unit unit = ComputerMilitary.requireRecordUnit(record);

            if (this.game.getUnitTargeting().getGroundWeapon(unit) != ScrWeaponIds.NONE) {
                noGround = false;
            }

            if (this.game.getUnitTargeting().getUnitOrSubunitAirWeapon(unit) != ScrWeaponIds.NONE) {
                noAir = false;
            }

            if ((unit.getStatus() & UnitStatusFlags.FLYING) == 0) {
                ground = true;
            }
        }

        int owner = regions.getOwner(index);
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        int region = regions.getRegion(index);
        int centerX = (short) terrain.getCenterX(region);
        int centerY = (short) terrain.getCenterY(region);
        boolean transports = this.hasTransports(owner);
        Unit leader = ComputerMilitary.requireRecordUnit(first);
        Unit building = null;
        int buildingDistance = ComputerPlayerPhase.NO_CANDIDATE_DISTANCE;
        Unit other = null;
        int otherDistance = ComputerPlayerPhase.NO_CANDIDATE_DISTANCE;
        Unit fallback = null;
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || !this.game.getComputerUnits().isEnemy(owner, unit)) {
                continue;
            }

            int unitRegion = this.game.getComputerTerrain().getUnitRegion(unit);

            if (regions.getState(regions.getIndex(owner, unitRegion)) != ComputerRegionStates.IDLE) {
                fallback = unit;

                continue;
            }

            int type = this.game.requireGameData().getBaseType(unit);

            if (this.game.requireGameData().getUnitTypes().isBeacon(type)) {
                continue;
            }

            if (fallback == null) {
                fallback = unit;
            }

            if (!ground) {
                if (isAirDefense(type)) {
                    continue;
                }
            } else {
                if (this.game.getComputerTerrain().isInaccessible(unitRegion)) {
                    continue;
                }

                if (!transports) {
                    Sprite sprite = unit.requireSprite();

                    if (!this.game.getUnitPositioning().isGroundReachable(leader, (short) sprite.getX(), (short) sprite.getY())) {
                        continue;
                    }
                }
            }

            if ((unit.getStatus() & UnitStatusFlags.FLYING) != 0 ? noAir : noGround) {
                continue;
            }

            Sprite sprite = unit.requireSprite();
            int distance = RegionPathSearch.calculateDistance(centerX, (short) sprite.getX(), centerY, (short) sprite.getY());

            if (UnitQueries.isGroundedBuilding(unit)) {
                if (Integer.compareUnsigned(distance, buildingDistance) < 0) {
                    buildingDistance = distance;
                    building         = unit;
                }
            } else if (Integer.compareUnsigned(distance, otherDistance) < 0) {
                otherDistance = distance;
                other         = unit;
            }
        }

        if (building != null) {
            return building;
        }

        if (other != null) {
            return other;
        }

        if (!ground) {
            return fallback;
        }

        if (fallback == null || this.game.getComputerTerrain().isInaccessible(this.game.getComputerTerrain().getUnitRegion(fallback))) {
            return null;
        }

        if (transports) {
            return fallback;
        }

        Sprite sprite = fallback.requireSprite();

        return this.game.getUnitPositioning().isGroundReachable(leader, (short) sprite.getX(), (short) sprite.getY()) ? fallback : null;
    }

    /**
     * Starts a strength attack: once per {@link #STRENGTH_ATTACK_DELAY} updates, gathers every idle military or guard unit
     * (not a transport or Bunker occupant) into a new staging region found from the first such unit.
     *
     * @param phase the owning phase
     * @param owner the computer owner
     * @throws IllegalStateException         if the unit the staging search starts from has no sprite
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    void startStrengthAttack(@NotNull ComputerPlayerPhase phase, int owner) {
        ComputerPlayer player = phase.getPlayer(owner);

        if (player.getStrengthAttackCountdown() != 0) {
            return;
        }

        ComputerRegions regions = phase.getRegions();
        ComputerMilitary military = phase.getMilitary();
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        int target = -1;

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);
            ComputerUnitRecord record = unit.getComputerRecord();

            if (ComputerUnits.isDeadOrHidden(unit) || record == null || this.game.getUnitQueries().isTransport(unit)) {
                continue;
            }

            if ((unit.getStatus() & UnitStatusFlags.IN_BUNKER) != 0 && !player.isCampaign()) {
                continue;
            }

            int unitRegion = this.game.getComputerTerrain().getUnitRegion(unit);

            if (record.getKind() == ComputerUnitRecord.KIND_GUARD) {
                unit.setComputerRecord(null);
                military.addToRegion(phase, regions.getIndex(owner, unitRegion), unit, true, phase.getInstructionLimit());

                record.unit = null;
            } else if (record.getKind() != ComputerUnitRecord.KIND_MILITARY) {
                continue;
            }

            record = unit.getComputerRecord();

            if (record != null && record.getKind() == ComputerUnitRecord.KIND_MILITARY) {
                if (ComputerRegionStates.isAttackState(regions.getState(record.regionIndex))) {
                    continue;
                }
            }

            if (target == -1) {
                clear(phase, owner, false);

                Sprite sprite = unit.requireSprite();

                if (this.prepare(phase, owner, (short) sprite.getX(), (short) sprite.getY(), false, true)) {
                    continue;
                }

                player.setStrengthAttackCountdown(STRENGTH_ATTACK_DELAY);

                target = regions.getIndex(owner, player.getAttackRegion() - 1);
            }

            phase.removeUnit(unit, false);
            military.addToRegion(phase, target, unit, true, phase.getInstructionLimit());
        }
    }

    /**
     * Assigns the expansion attack target of a non-campaign computer when the regional sweep's player cursor wraps.
     * <p>
     * With a prepared attack (aiscript {@code set_attacks} and {@code target_expansion}), no region record attacking or
     * gathering, and every attack group type present among the owner's idle, completed units with computer records, the
     * enemy expansion nearest to the owner's start area becomes the target. Its staging region (forced clock) gathers with
     * the target as the linked region, and one prepared attack is consumed.
     *
     * @param phase the owning phase
     * @param owner the computer owner
     * @throws UnsupportedOperationException if the resource areas or the unit pool is not initialized
     * @throws NullPointerException          if the world was built without game data
     */
    void assignExpansionTarget(@NotNull ComputerPlayerPhase phase, int owner) {
        ComputerPlayer player = phase.getPlayer(owner);

        if (!player.isAttackPrepared()) {
            return;
        }

        short[] types = player.getAttackTypes();
        int[] wanted = new int[types.length];
        int count = 0;

        while (count < types.length && types[count] != 0) {
            wanted[count] = Short.toUnsignedInt(types[count]) - 1;
            count++;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        ScrOrderTypeView orders = this.game.requireGameData().getOrderTypes();
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || this.game.getComputerUnits().isSubunit(unit)) {
                continue;
            }

            int baseType = this.game.requireGameData().getBaseType(unit);
            int type = baseType == ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE ? ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE : baseType;
            int order = unit.getPrimaryOrder();

            if ((units.getComputerInternalFlags(type) & UnitComputerFlags.ATTACK_GROUP_IGNORED) != 0 ||
                (unit.getStatus() & UnitStatusFlags.COMPLETED) == 0 ||
                order == ScrOrderIds.COMPLETING_ARCHON_SUMMON || order == ScrOrderIds.RESET_COLLISION ||
                orders.getUseWeaponTargeting(order) != 0 || unit.getComputerRecord() == null) {
                continue;
            }

            for (int entry = 0; entry < count; entry++) {
                if (wanted[entry] == type) {
                    wanted[entry] = PRESENT_ATTACK_TYPE;

                    if (entry == count - 1) {
                        count--;
                    }

                    break;
                }
            }
        }

        for (int entry = 0; entry < count; entry++) {
            if (wanted[entry] != PRESENT_ATTACK_TYPE) {
                return;
            }
        }

        ComputerRegions regions = phase.getRegions();

        for (int region = 1; region < regions.getRegionCount(); region++) {
            if (ComputerRegionStates.isAttackState(regions.getState(regions.getIndex(owner, region)))) {
                return;
            }
        }

        ResourceAreas areas = this.game.getResourceAreas();

        if (areas == null) {
            throw Exceptions.newUnsupportedOperationException("The computer expansion target requires initialized resource areas.");
        }

        int area = this.game.getComputerTowns().selectResourceArea(phase, owner, 0, 0);
        int source = this.game.getComputerTerrain().getRegion(getAreaHorizontalPosition(areas, area), getAreaVerticalPosition(areas, area));
        int[] route = new int[ROUTE_SIZE];
        int target = this.findExpansionTarget(phase, areas, owner, source, route);
        int staging = route[ROUTE_STAGING];

        if (target == ComputerPlayerPhase.NO_REGION || staging == ComputerPlayerPhase.NO_REGION) {
            return;
        }

        int index = regions.getIndex(owner, staging);

        player.setAttackRegion(staging, true, phase.getElapsedClockTicks());
        regions.setState(phase, index, ComputerRegionStates.ATTACK_GATHERING);
        regions.setLinkedRegion(index, target);
        player.consumeAttackPreparation();
    }

    /**
     * Finds the expansion target: every enemy resource depot in a resource area scores the route from the source region
     * to the area's region; a route in another ground component competes by straight distance, otherwise the lowest score
     * wins for the same enemy and the straight distance decides between enemies. A single enemy depot at a start-location
     * area that is defended by more than {@link #DEFENDED_START_STRENGTH} strength until {@link #EARLY_ATTACK_CLOCK} is
     * not targeted.
     *
     * @param phase  the owning phase
     * @param areas  the resource areas
     * @param owner  the computer owner
     * @param source the source region
     * @param route  receives the staging region at {@link #ROUTE_STAGING}
     * @return the target region, or {@link ComputerPlayerPhase#NO_REGION}
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private int findExpansionTarget(@NotNull ComputerPlayerPhase phase, @NotNull ResourceAreas areas, int owner, int source, int @NotNull [] route) {
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        int sourceX = terrain.getCenterX(source);
        int sourceY = terrain.getCenterY(source);
        int bestEnd = ComputerPlayerPhase.NO_REGION;
        int bestStaging = ComputerPlayerPhase.NO_REGION;
        int bestScore = DIFFERENT_COMPONENT_SCORE;
        int bestDistance = DIFFERENT_COMPONENT_SCORE;
        int bestOwner = -1;
        int bestArea = ResourceAreas.NO_AREA;
        int depots = 0;

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if ((units.getFlags(unit.getUnitId()) & UnitTypeFlags.RESOURCE_DEPOT) == 0 || !this.game.getComputerUnits().isEnemy(owner, unit)) {
                continue;
            }

            int area = areas.findArea(unit);

            if (area == ResourceAreas.NO_AREA) {
                continue;
            }

            depots++;

            int areaX = getAreaHorizontalPosition(areas, area);
            int areaY = getAreaVerticalPosition(areas, area);
            int distance = RegionPathSearch.calculateDistance(sourceX, areaX, sourceY, areaY);
            int score = this.scoreRoute(phase, owner, source, this.game.getComputerTerrain().getRegion(areaX, areaY), route);

            if (score == NO_ROUTE_SCORE) {
                continue;
            }

            boolean better;

            if (score == DIFFERENT_COMPONENT_SCORE) {
                better = Integer.compareUnsigned(distance, bestDistance) < 0;
            } else if (bestOwner == unit.getOwnerIndex()) {
                better = Integer.compareUnsigned(score, bestScore) <= 0;
            } else {
                better = Integer.compareUnsigned(distance, bestDistance) < 0;
            }

            if (!better) {
                continue;
            }

            bestEnd      = route[ROUTE_END];
            bestStaging  = route[ROUTE_STAGING];
            bestDistance = distance;
            bestScore    = score;
            bestArea     = area;
            bestOwner    = unit.getOwnerIndex();
        }

        route[ROUTE_STAGING] = bestStaging;

        if (depots > 1 || Integer.compareUnsigned(bestScore, DEFENDED_START_STRENGTH) <= 0 ||
            Integer.compareUnsigned(phase.getElapsedClockTicks(), EARLY_ATTACK_CLOCK) > 0 || bestArea == ResourceAreas.NO_AREA ||
            areas.getStartLocationMarker(bestArea) == 0) {
            return bestEnd;
        }

        return ComputerPlayerPhase.NO_REGION;
    }

    /**
     * Scores the enemy strength along the ground route between the region centers. The route end is the first region
     * with enemy strength and the staging region the last region at least {@link #STAGING_ROUTE_DISTANCE} steps before it
     * with at least {@link #STAGING_MINIMUM_TILES} tiles.
     *
     * @param phase  the owning phase
     * @param owner  the computer owner
     * @param source the source region
     * @param target the target region
     * @param route  receives the route end at {@link #ROUTE_END} and the staging region at {@link #ROUTE_STAGING}
     * @return the summed strength, {@link #DIFFERENT_COMPONENT_SCORE} or {@link #NO_ROUTE_SCORE}
     */
    private int scoreRoute(@NotNull ComputerPlayerPhase phase, int owner, int source, int target, int @NotNull [] route) {
        route[ROUTE_STAGING] = source;
        route[ROUTE_END]     = target;

        if (source == target) {
            return getEnemyStrength(phase, owner, source);
        }

        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();

        if (terrain.getComponent(source) != terrain.getComponent(target)) {
            return DIFFERENT_COMPONENT_SCORE;
        }

        RegionPathSearch search = this.game.getComputerTerrain().getPathSearch();

        if (!search.search(source, terrain.getCenterX(source), terrain.getCenterY(source),
                           target, terrain.getCenterX(target), terrain.getCenterY(target))) {
            return NO_ROUTE_SCORE;
        }

        int routeCount = (short) search.getRouteCount();

        if (routeCount <= DIRECT_ROUTE_LENGTH) {
            return getEnemyStrength(phase, owner, target) + getEnemyStrength(phase, owner, source);
        }

        int total = 0;

        for (int routeIndex = 0; routeIndex < routeCount; routeIndex++) {
            int region = search.getRouteRegion(routeIndex);
            int strength = getEnemyStrength(phase, owner, region);

            if (route[ROUTE_END] == target) {
                if (routeIndex >= STAGING_ROUTE_DISTANCE) {
                    int staging = search.getRouteRegion(routeIndex - STAGING_ROUTE_DISTANCE);

                    if (terrain.getTileCount(staging) >= STAGING_MINIMUM_TILES) {
                        route[ROUTE_STAGING] = staging;
                    }
                }

                if (strength != 0) {
                    route[ROUTE_END] = region;
                }
            }

            total += strength;
        }

        return total;
    }

    /**
     * Sends every unit of the owner list (aiscript {@code send_suicide}) that is alive, not a subunit, Nuclear Missile or
     * Larva, not hidden and not engaged to a suicide target.
     *
     * @param phase the owning phase
     * @param owner the computer owner
     * @throws IllegalStateException         if a live unit of the owner has no sprite
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    void sendSuicide(@NotNull ComputerPlayerPhase phase, int owner) {
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || this.game.getComputerUnits().isSubunit(unit)) {
                continue;
            }

            int type = this.game.requireGameData().getBaseType(unit);

            if (type == ScrUnitIds.TERRAN_NUCLEAR_MISSILE || type == ScrUnitIds.ZERG_LARVA) {
                continue;
            }

            if ((unit.requireSprite().getFlags() & Sprite.FLAG_HIDDEN) != 0 || this.game.getComputerCombat().isEngaged(unit, null)) {
                continue;
            }

            this.assignSuicideTarget(phase, unit);
        }
    }

    /**
     * Assigns a suicide target: a movable or burrowed unit picks its enemy order target's owner list, or a random enemy
     * player's list, and walks the lists from there for the nearest (squared distance) live unit it can attack (when it
     * can attack at all); it attacks it or moves to it and leaves its computer relations without dying.
     *
     * @throws IllegalStateException         if the unit, a live candidate or the chosen target has no sprite
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private void assignSuicideTarget(@NotNull ComputerPlayerPhase phase, @NotNull Unit unit) {
        if ((unit.getStatus() & UnitStatusFlags.CAN_MOVE) == 0 && (unit.getStatus() & UnitStatusFlags.BURROWED) == 0) {
            return;
        }

        Unit[] starts = new Unit[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        int count = 0;
        Unit orderTarget = unit.getOrderTargetUnit();

        if (orderTarget != null && this.game.getComputerUnits().isEnemy(unit.getOwnerIndex(), orderTarget)) {
            starts[count++] = orderTarget;
        } else {
            for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
                int first = this.game.getUnitSystem().requireUnitPool().getFirstOwnerIndex(player);

                if (first == 0) {
                    continue;
                }

                Unit firstUnit = this.game.getUnitSystem().requireUnitPool().getUnit(first);

                if (this.game.getComputerUnits().isEnemy(unit.getOwnerIndex(), firstUnit)) {
                    starts[count++] = firstUnit;
                }
            }

            if (count == 0) {
                return;
            }
        }

        int draw = Integer.remainderUnsigned(this.game.getRandom().next(SUICIDE_RANDOM_SOURCE), count);
        boolean armed = this.game.getUnitQueries().canAttack(unit);
        Sprite sprite = unit.requireSprite();
        int unitX = (short) sprite.getX();
        int unitY = (short) sprite.getY();
        Unit best = null;

        for (int attempt = 0; attempt < count; attempt++) {
            int list = Integer.compareUnsigned(draw, count) < 0 ? draw : 0;
            int bestDistance = -1;

            for (Unit candidate = starts[list]; candidate != null; ) {
                if (!ComputerUnits.isDeadOrHidden(candidate) && (!armed || this.game.getUnitTargeting().canAttackTarget(unit, candidate))) {
                    Sprite candidateSprite = candidate.requireSprite();
                    int horizontal = (short) candidateSprite.getX() - unitX;
                    int vertical = (short) candidateSprite.getY() - unitY;
                    int distance = vertical * vertical + horizontal * horizontal;

                    if (Integer.compareUnsigned(distance, bestDistance) < 0) {
                        best         = candidate;
                        bestDistance = distance;
                    }
                }

                int next = this.game.getUnitSystem().requireUnitPool().getNextOwnerIndex(candidate.getIndex());

                candidate = next == 0 ? null : this.game.getUnitSystem().requireUnitPool().getUnit(next);
            }

            if (best != null) {
                break;
            }

            draw = list + 1;
        }

        if (best == null) {
            return;
        }

        if (armed) {
            this.game.getComputerCombat().attack(unit, best, false, 0, phase.getInstructionLimit());
        } else {
            Sprite target = best.requireSprite();

            this.game.getOrders().issue(unit,
                                        ScrOrderIds.MOVE,
                                        null,
                                        (short) target.getX(), (short) target.getY(),
                                        ScrUnitIds.NONE,
                                        false,
                                        phase.getInstructionLimit());
        }

        phase.removeUnit(unit, false);
    }

    /**
     * Applies aiscript {@code harass_factor}: with a staging region, the player of the enemy building nearest to its center
     * is measured by the larger of each live unit's air and ground strength; the repeat budget is
     * {@code ceil(sum / factor) - 1}, capped at {@link #MAXIMUM_HARASS_REPEATS}. While the budget exceeds one, a group copy is
     * appended and the budget is decremented, within the {@link ComputerPlayer#ATTACK_GROUP_LIMIT} entries.
     *
     * @param phase  the owning phase
     * @param owner  the script owner
     * @param factor the WORD operand
     * @throws UnsupportedOperationException if the factor is zero and the enemy has strength or the unit pool is not initialized
     */
    void applyHarassFactor(@NotNull ComputerPlayerPhase phase, int owner, int factor) {
        if (owner >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return;
        }

        ComputerPlayer player = phase.getPlayer(owner);
        int staging = player.getAttackRegion();

        if (staging == 0) {
            return;
        }

        int enemy = this.findHarassPlayer(owner, staging);

        if (enemy == -1) {
            return;
        }

        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        int sum = 0;

        for (int poolIndex = pool.getFirstOwnerIndex(enemy); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (!ComputerUnits.isDeadOrHidden(unit)) {
                sum += Math.max(unit.getAirStrength() & WORD_MASK, unit.getGroundStrength() & WORD_MASK);
            }
        }

        if (sum == 0) {
            return;
        }

        if (factor == 0) {
            throw Exceptions.newUnsupportedOperationException("A zero harass factor cannot divide the enemy strength {0}.", sum);
        }

        short[] types = player.getAttackTypes();
        short[] group = types.clone();
        int repeats = Integer.divideUnsigned(factor - 1 + sum, factor) - 1;

        if (Integer.compareUnsigned(repeats, MAXIMUM_HARASS_REPEATS) > 0) {
            repeats = MAXIMUM_HARASS_REPEATS;
        }

        int count = 0;

        while (group[count] != 0) {
            count++;
        }

        while (Integer.compareUnsigned(repeats, 1) > 0) {
            repeats--;

            for (int entry = 0; group[entry] != 0; entry++) {
                types[count++] = group[entry];

                if (count == ComputerPlayer.ATTACK_GROUP_LIMIT) {
                    return;
                }
            }
        }
    }

    /**
     * Returns the owner of the live enemy building nearest to the center of the region that contains the given region's
     * center.
     *
     * @return the player, or {@code -1}
     * @throws IllegalStateException         if a live enemy building has no sprite
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private int findHarassPlayer(int owner, int region) {
        TerrainRegions terrain = this.game.getComputerTerrain().getRegions();
        int around = this.game.getComputerTerrain().getRegion((short) terrain.getCenterX(region), (short) terrain.getCenterY(region));
        int centerX = (short) terrain.getCenterX(around);
        int centerY = (short) terrain.getCenterY(around);
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        Unit nearest = null;
        int nearestDistance = ComputerPlayerPhase.NO_DISTANCE;

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || !UnitQueries.isGroundedBuilding(unit) || !this.game.getComputerUnits().isEnemy(owner, unit)) {
                continue;
            }

            Sprite sprite = unit.requireSprite();
            int distance = RegionPathSearch.calculateDistance(centerX, (short) sprite.getX(), centerY, (short) sprite.getY());

            if (Integer.compareUnsigned(distance, nearestDistance) < 0) {
                nearest         = unit;
                nearestDistance = distance;
            }
        }

        return nearest == null ? -1 : nearest.getOwnerIndex();
    }
}
