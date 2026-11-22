package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUpgradeIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.StartLocation;
import org.fogofwarlabs.sc4j.scr.game.resource.ResourceAreas;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegionLookupUtils;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Executes the computer town update, the town request scan that turns the build request tables into spending
 * requests, and the spending request helpers.
 */
public final class ComputerTowns {
    /**
     * Truncates a value to a BYTE.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The low WORD of a packed position: its horizontal coordinate.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The shift of the vertical coordinate in the high WORD of a packed position.
     */
    private static final int HIGH_WORD_SHIFT = Short.SIZE;
    /**
     * The desired workers per geyser beyond the first.
     */
    private static final int ADDITIONAL_GAS_WORKERS_PER_GEYSER = 2;

    /**
     * The {@code define_max} value that refuses every request of the type ({@code 0} means no maximum).
     */
    static final int MAXIMUM_REFUSED = 0xFF;
    /**
     * The worker assignment of the mineral line.
     */
    static final int ASSIGNMENT_MINERALS = 1;
    /**
     * The worker assignment of the first rotating gas slot; the next slots follow.
     */
    static final int ASSIGNMENT_FIRST_GAS = 2;
    /**
     * The spending request kinds are below this.
     */
    private static final int REQUEST_KIND_LIMIT = 9;
    /**
     * The kinds whose requests the {@code define_max} test applies to, one bit per kind: region, guard, worker, Observer.
     */
    private static final int MAXIMUM_TESTED_KINDS = 1 << ComputerPlayer.REQUEST_KIND_REGION | 1 << ComputerPlayer.REQUEST_KIND_GUARD |
                                                    1 << ComputerPlayer.REQUEST_KIND_WORKER | 1 << ComputerPlayer.REQUEST_KIND_OBSERVER;

    /**
     * The gas building slots of a town.
     */
    private static final int GAS_FIRST = 0;
    private static final int GAS_SECOND = 1;
    private static final int GAS_THIRD = 2;

    /**
     * The last resource area index; {@code 0} is no area.
     */
    private static final int LAST_RESOURCE_AREA = 249;
    /**
     * Occupancy flags {@code 1|2}: the area is taken.
     */
    private static final int OCCUPIED_AREA_FLAGS = 0x3;
    /**
     * The mineral total per mineral worker slot of an area.
     */
    private static final int MINERALS_PER_WORKER = 500;
    /**
     * The distance of a depot site from its resource, toward the area center.
     */
    private static final int DEPOT_SITE_DISTANCE = 0xC0;
    /**
     * Gas percentages are hundredths.
     */
    private static final int PERCENT = 100;
    /**
     * Melee towns choose among the first eight resource areas.
     */
    private static final int MELEE_RESOURCE_AREAS = 8;

    /**
     * The priority of urgent requests, which the refinery delay does not hold back.
     */
    private static final int URGENT_PRIORITY = 0x8C;
    /**
     * The minerals below which a melee town's resource depot request gets {@link #DEPOT_PRIORITY}.
     */
    private static final int DEPOT_PRIORITY_MINERALS = 600;
    private static final int DEPOT_PRIORITY = 0x96;
    /**
     * A second refinery waits for this many workers (or the desired count) and this many minerals.
     */
    private static final int SECOND_REFINERY_WORKERS = 15;
    private static final int SECOND_REFINERY_MINERALS = 500;
    private static final int SECOND_REFINERY_PRIORITY = 0x50;
    private static final int FIRST_REFINERY_PRIORITY = 0x5A;
    /**
     * The available half-supply above which no supply provider is requested.
     */
    private static final int SUPPLY_RESERVE = 12;
    /**
     * The available half-supply below which a supply provider is urgent.
     */
    private static final int LOW_SUPPLY = 9;
    private static final int SUPPLY_PRIORITY = 0x78;
    /**
     * The priority of a Bunker or Missile Turret wanted by a town region.
     */
    private static final int DEFENSE_BUILDING_PRIORITY = 0x50;
    /**
     * The priority of Bunker infantry; a campaign Bunker already holding units asks at the lower one.
     */
    private static final int BUNKER_PRIORITY = 100;
    static final int LOADED_BUNKER_PRIORITY = 0x14;

    /**
     * A town expands when its mineral worker capacity is below this, or below the second value with several towns.
     */
    private static final int EXPANSION_MINERAL_CAPACITY = 16;
    private static final int TOWNS_EXPANSION_MINERAL_CAPACITY = 20;
    /**
     * Several towns expand with fewer gas towns than this.
     */
    private static final int MINIMUM_GAS_TOWNS = 2;
    /**
     * A surplus expansion needs minerals in {@code 2000..4999} and gas in {@code 1000..2999}.
     */
    private static final int SURPLUS_MINERALS = 2000;
    private static final int SURPLUS_MINERALS_RANGE = 2999;
    private static final int SURPLUS_GAS = 1000;
    private static final int SURPLUS_GAS_RANGE = 1999;
    /**
     * A surplus expansion needs fewer mineral fields than this in the town areas.
     */
    private static final int SURPLUS_MINERAL_FIELDS = 24;
    /**
     * The town-count argument of a panic expansion: more than one town.
     */
    private static final int PANIC_EXPANSION_TOWN_COUNT = 2;

    /**
     * The worker assignment of the last rotating gas slot.
     */
    private static final int ASSIGNMENT_LAST_GAS = 4;
    /**
     * The workers per assignment before the assignments rotate.
     */
    private static final int ASSIGNMENT_GROUP_SIZE = 4;
    /**
     * The shift from geysers to gas worker slots: four per geyser.
     */
    private static final int GAS_SLOT_SHIFT = 2;
    /**
     * The gas below which the gas worker minimums apply.
     */
    private static final int GAS_SHORTAGE = 800;
    /**
     * The completed workers from which a town keeps at least the given gas workers.
     */
    private static final int LARGE_WORKFORCE = 20;
    private static final int LARGE_WORKFORCE_GAS_WORKERS = 4;
    private static final int MEDIUM_WORKFORCE = 15;
    private static final int MEDIUM_WORKFORCE_GAS_WORKERS = 3;
    private static final int SMALL_WORKFORCE = 10;
    private static final int SMALL_WORKFORCE_GAS_WORKERS = 2;
    private static final int MINIMAL_WORKFORCE = 5;
    /**
     * The clock ticks a worker may stay without progress before it is reordered.
     */
    private static final int WORKER_IDLE_LIMIT = 0x3C;
    /**
     * The delay set on a reordered worker.
     */
    private static final int WORKER_RETRY_DELAY = 10;

    /**
     * The expansion categories: {@code 0..7} by resources, {@code 8} for every free area.
     */
    private static final int EXPANSION_CATEGORY_COUNT = 9;
    private static final int CATEGORY_RICH_GAS_RICH_MINERALS = 0;
    private static final int CATEGORY_GAS_RICH_MINERALS = 1;
    private static final int CATEGORY_RICH_MINERALS = 2;
    private static final int CATEGORY_RICH_GAS_MINERALS = 3;
    private static final int CATEGORY_GAS_MINERALS = 4;
    private static final int CATEGORY_RICH_GAS = 5;
    private static final int CATEGORY_MINERALS = 6;
    private static final int CATEGORY_GAS = 7;
    private static final int ANY_AREA_CATEGORY = 8;
    /**
     * The value that ends an expansion preference row.
     */
    private static final int PREFERENCES_END = 9;
    /**
     * The preference rows: a single expansion, a mineral surplus, otherwise.
     */
    private static final int PREFERENCE_SINGLE = 0;
    private static final int PREFERENCE_MINERAL_SURPLUS = 1;
    private static final int PREFERENCE_BALANCED = 2;
    /**
     * The category preference rows, ended by {@link #PREFERENCES_END}: a single expansion, minerals exceeding gas by more
     * than {@link #RESOURCE_IMBALANCE}, otherwise.
     */
    private static final int @NotNull [] @NotNull [] EXPANSION_PREFERENCES = {
            {ANY_AREA_CATEGORY, CATEGORY_RICH_GAS_RICH_MINERALS, CATEGORY_GAS_RICH_MINERALS, CATEGORY_RICH_GAS_MINERALS,
             CATEGORY_RICH_MINERALS, CATEGORY_RICH_GAS, CATEGORY_GAS_MINERALS, CATEGORY_MINERALS, PREFERENCES_END},
            {CATEGORY_RICH_GAS_RICH_MINERALS, CATEGORY_RICH_GAS_MINERALS, CATEGORY_RICH_GAS, CATEGORY_GAS_RICH_MINERALS,
             CATEGORY_RICH_MINERALS, CATEGORY_GAS_MINERALS, CATEGORY_GAS, CATEGORY_MINERALS, PREFERENCES_END},
            {CATEGORY_RICH_GAS_RICH_MINERALS, CATEGORY_GAS_RICH_MINERALS, CATEGORY_RICH_MINERALS, CATEGORY_RICH_GAS_MINERALS,
             CATEGORY_GAS_MINERALS, CATEGORY_RICH_GAS, CATEGORY_MINERALS, CATEGORY_GAS, PREFERENCES_END}
    };
    /**
     * Resource totals above which an area counts as rich in gas or minerals.
     */
    private static final int RICH_GAS_TOTAL = 1000;
    private static final int RICH_MINERAL_TOTAL = 2000;
    /**
     * The mineral and gas difference above which the preference row follows the scarcer resource.
     */
    private static final int RESOURCE_IMBALANCE = 500;

    /**
     * Returns the supply cost BYTE of a type: twice the supply for two units in one egg.
     *
     * @param units the unit type table
     * @param type  the unit type
     * @return the supply cost BYTE
     */
    static int getSupplyCost(@NotNull ScrUnitTypeView units, int type) {
        int supply = units.getSupplyRequired(type) & BYTE_MASK;

        if ((units.getFlags(type) & UnitTypeFlags.TWO_UNITS_IN_ONE_EGG) != 0) {
            supply = supply * ComputerAttack.TWIN_EGG_UNITS & BYTE_MASK;
        }

        return supply;
    }

    /**
     * Returns the spending request kind of a town's unit type: worker, add-on or other.
     */
    private static int getUnitRequestKind(@NotNull ScrUnitTypeView units, int type) {
        int flags = units.getFlags(type);

        return (flags & UnitTypeFlags.WORKER) != 0
               ? ComputerPlayer.REQUEST_KIND_WORKER
               : (flags & UnitTypeFlags.ADDON) * ComputerPlayer.ADDON_REQUEST_FLAG_MULTIPLIER | ComputerPlayer.REQUEST_KIND_TOWN;
    }

    /**
     * Tests whether the ground-ground use list holds the type.
     */
    private static boolean usesGroundDefense(@NotNull ComputerPlayer player, int type) {
        for (short entry : player.getDefenseList(ComputerPlayer.DEFENSE_USE_GROUND_GROUND)) {
            if (entry == 0) {
                return false;
            }

            if (Short.toUnsignedInt(entry) == type + 1) {
                return true;
            }
        }

        return false;
    }

    private static int calculateGasDistance(@Nullable Unit unit, int areaX, int areaY) {
        if (unit == null) {
            return ComputerPlayerPhase.NO_DISTANCE;
        }

        Sprite sprite = unit.requireSprite();

        return RegionPathSearch.calculateDistance(areaX, (short) sprite.getX(), areaY, (short) sprite.getY());
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link ComputerTowns} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public ComputerTowns(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Applies the maximum-count test ({@code define_max}) before heap insertion; the owner's Siege Tanks count in both modes.
     *
     * @return {@code true} if the request may be inserted, {@code false} otherwise
     */
    private boolean isBelowMaximum(@NotNull ComputerPlayer player, int kind, int type) {
        return kind >= REQUEST_KIND_LIMIT || (MAXIMUM_TESTED_KINDS >>> kind & 1) == 0 || !this.game.getComputerOrders().isAtMaximum(player, type);
    }

    /**
     * @return the elapsed clock ticks of the trigger context, or {@code 0} without one
     */
    int getElapsedClockTicks() {
        TriggerContext triggers = this.game.getTriggerContext();

        return triggers == null ? 0 : triggers.getElapsedClockTicks();
    }

    /**
     * Returns the gas share of the reserved request costs in percent.
     *
     * @param player the computer player
     * @return the gas share in {@code 0..100}: {@code 100} when only gas is short, {@code 0} when only minerals are
     */
    int getGasPercent(@NotNull ComputerPlayer player) {
        int reservedGas = player.getReservedGas();

        if (reservedGas == 0) {
            return 0;
        }

        int reservedMinerals = player.getReservedMinerals();

        if (reservedMinerals == 0) {
            return PERCENT;
        }

        Player owner = this.game.getPlayerTable().requirePlayer(player.getOwner());
        int mineralShortage = reservedMinerals - owner.getMinerals();
        int gasShortage = reservedGas - owner.getGas();

        if (mineralShortage < 1) {
            if (gasShortage >= 1) {
                return PERCENT;
            }
        } else if (gasShortage < 1) {
            return 0;
        }

        return (int) Integer.toUnsignedLong(Integer.divideUnsigned(reservedGas * PERCENT, reservedMinerals + reservedGas)) & BYTE_MASK;
    }

    /**
     * @param area the resource area
     * @return the mineral field count of the area, or {@code 0} outside {@code 1..249}
     * @throws UnsupportedOperationException if the resource areas are not initialized
     */
    int getAreaMineralCount(int area) {
        if (area < 1 || area > LAST_RESOURCE_AREA) {
            return 0;
        }

        return this.game.requireResourceAreas().getMineralCount(area);
    }

    /**
     * Returns the depot site of an area: a site {@value #DEPOT_SITE_DISTANCE} pixels from the resource farthest from
     * the area center, toward the center, when that is closer to the resource than the center; otherwise the area
     * center.
     *
     * @param area the resource area
     * @return the packed position {@code x | y << 16}
     * @throws UnsupportedOperationException if the area is outside {@code 1..249} or the resource areas or the unit pool is not initialized
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if the farthest resource has no sprite
     */
    int getDepotSite(int area) {
        if (area < 1 || area > LAST_RESOURCE_AREA) {
            throw Exceptions.newUnsupportedOperationException("Resource area {0} has no available depot site position.", area);
        }

        ResourceAreas areas = this.game.requireResourceAreas();
        int centerX = (short) areas.getHorizontalPosition(area);
        int centerY = (short) areas.getVerticalPosition(area);
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        Unit farthest = null;
        int farthestDistance = 0;

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) ||
                (units.getFlags(unit.getUnitId()) & UnitTypeFlags.RESOURCE_CONTAINER) == 0 || unit.getResourceAreaIndex() != area) {
                continue;
            }

            Sprite sprite = unit.requireSprite();
            int distance = RegionPathSearch.calculateDistance(sprite.getX(), centerX, sprite.getY(), centerY);

            if (Integer.compareUnsigned(farthestDistance, distance) <= 0) {
                farthestDistance = distance;
                farthest         = unit;
            }
        }

        if (farthest != null) {
            Sprite sprite = farthest.requireSprite();
            int resourceX = sprite.getX();
            int resourceY = sprite.getY();
            int direction = FlingyMotionUtils.calculateDirection(centerX - resourceX, centerY - resourceY);
            long offset = FlingyMotionUtils.calculateDirectionVector(direction, DEPOT_SITE_DISTANCE);
            int siteX = resourceX + (int) offset;
            int siteY = resourceY + (int) (offset >> Integer.SIZE);
            int centerDistance = RegionPathSearch.calculateDistance(resourceX, centerX, resourceY, centerY);
            int siteDistance = RegionPathSearch.calculateDistance(resourceX, siteX, resourceY, siteY);

            if (Integer.compareUnsigned(siteDistance, centerDistance) < 0) {
                return siteX & WORD_MASK | siteY << HIGH_WORD_SHIFT;
            }
        }

        return centerX & WORD_MASK | centerY << HIGH_WORD_SHIFT;
    }

    /**
     * Returns the mineral field count of all area towns of the owner.
     */
    private int getOwnerMineralCount(@NotNull ComputerPlayerPhase phase, int owner) {
        int total = 0;

        for (ComputerTown town = phase.getFirstTown(owner); town != null; town = town.next) {
            if (town.resourceArea != 0) {
                total += this.getAreaMineralCount(town.resourceArea);
            }
        }

        return total;
    }

    /**
     * Returns the mineral worker capacity of an area: twice its mineral fields, bounded by its mineral total per
     * {@value #MINERALS_PER_WORKER}, while the owner's town areas hold no more fields than that bound; otherwise one
     * worker per field.
     *
     * @param phase the owning phase
     * @param area  the resource area
     * @param owner the town owner
     * @return the mineral worker capacity, or {@code 0} outside {@code 1..249}
     * @throws UnsupportedOperationException if the resource areas are not initialized
     */
    int getMineralCapacity(@NotNull ComputerPlayerPhase phase, int area, int owner) {
        if (area < 1 || area > LAST_RESOURCE_AREA) {
            return 0;
        }

        ResourceAreas areas = this.game.requireResourceAreas();
        int count = areas.getMineralCount(area);
        int total = Integer.divideUnsigned(areas.getMineralTotal(area), MINERALS_PER_WORKER);

        if (Integer.compareUnsigned(this.getOwnerMineralCount(phase, owner), total) <= 0) {
            return Integer.compareUnsigned(count + count, total) < 0 ? count + count : total;
        }

        return count;
    }

    /**
     * Returns the desired worker count of an area: three per geyser plus the mineral worker capacity.
     *
     * @param phase the owning phase
     * @param area  the resource area
     * @param owner the town owner
     * @return the desired worker count, or {@code 0} outside {@code 1..249}
     * @throws UnsupportedOperationException if the resource areas are not initialized
     */
    int getDesiredWorkers(@NotNull ComputerPlayerPhase phase, int area, int owner) {
        if (area < 1 || area > LAST_RESOURCE_AREA) {
            return 0;
        }

        ResourceAreas areas = this.game.requireResourceAreas();
        int count = areas.getMineralCount(area);
        int total = Integer.divideUnsigned(areas.getMineralTotal(area), MINERALS_PER_WORKER);
        int minerals = count;

        if (Integer.compareUnsigned(this.getOwnerMineralCount(phase, owner), total) <= 0) {
            minerals = total;

            if (Integer.compareUnsigned(count + count, total) < 0) {
                minerals = count + count;
            }
        }

        int gas = areas.getGasCount(area);

        return gas + minerals + gas * ADDITIONAL_GAS_WORKERS_PER_GEYSER;
    }


    /**
     * Reserves the costs of a spending request kind: the upgrade or research cost, or the unit's minerals, gas and
     * supply.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private void reserve(@NotNull ComputerPlayer player, int kind, int type) {
        if (kind == ComputerPlayer.REQUEST_KIND_UPGRADE || kind == ComputerPlayer.REQUEST_KIND_RESEARCH) {
            int[] cost = kind == ComputerPlayer.REQUEST_KIND_UPGRADE
                         ? this.game.getComputerTechnology().getUpgradeCost(player.getOwner(), type)
                         : this.game.getComputerTechnology().getResearchCost(type);

            player.reserve(cost[0], cost[1], 0);

            return;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        player.reserve(units.getMineralCost(type), units.getGasCost(type), getSupplyCost(units, type));
    }

    /**
     * Turns one town build request into a spending request: reserves its cost and inserts it unless the type is at its
     * maximum.
     *
     * @param player   the requesting computer player
     * @param town     the requesting town
     * @param kind     the town request kind ({@link ComputerTown#REQUEST_UNIT}, {@link ComputerTown#REQUEST_UPGRADE} or
     *                 {@link ComputerTown#REQUEST_TECHNOLOGY})
     * @param type     the requested type
     * @param priority the priority BYTE
     * @throws NullPointerException if the world was built without game data
     */
    void addRequest(@NotNull ComputerPlayer player, @Nullable ComputerTown town, int kind, int type, int priority) {
        int requestKind = kind == ComputerTown.REQUEST_UNIT
                          ? getUnitRequestKind(this.game.requireGameData().getUnitTypes(), type)
                          : kind != ComputerTown.REQUEST_UPGRADE ? ComputerPlayer.REQUEST_KIND_RESEARCH : ComputerPlayer.REQUEST_KIND_UPGRADE;

        this.reserve(player, requestKind, type);

        if (!this.isBelowMaximum(player, requestKind, type)) {
            return;
        }

        player.pushRequest(priority, requestKind, type, town, -1);
    }

    /**
     * Requests a unit for a town: after a recent building hit, only refineries and urgent requests pass.
     *
     * @param player   the requesting computer player
     * @param town     the requesting town
     * @param type     the requested unit type
     * @param priority the priority BYTE
     * @throws NullPointerException if the world was built without game data
     */
    void addUnitRequest(@NotNull ComputerPlayer player, @NotNull ComputerTown town, int type, int priority) {
        Player owner = this.game.getPlayerTable().requirePlayer(player.getOwner());

        if (!player.isBuildingHitRequestDelayElapsed(this.getElapsedClockTicks()) && type != ComputerUnits.getRefineryType(owner.getRace()) &&
            priority != URGENT_PRIORITY) {
            return;
        }

        int kind = getUnitRequestKind(this.game.requireGameData().getUnitTypes(), type);

        this.reserve(player, kind, type);

        if (!this.isBelowMaximum(player, kind, type)) {
            return;
        }

        player.pushRequest(priority, kind, type, town, -1);
    }

    /**
     * Inserts a region request: the {@code define_max} test and the heap insertion.
     *
     * @param player   the requesting computer player
     * @param kind     the request kind
     * @param type     the requested type
     * @param priority the priority BYTE
     * @param region   the requesting region record index
     */
    void pushRegionRequest(@NotNull ComputerPlayer player, int kind, int type, int priority, int region) {
        if (!this.isBelowMaximum(player, kind, type)) {
            return;
        }

        player.pushRequest(priority, kind, type, null, region);
    }

    /**
     * Inserts a guard refill request: the {@code define_max} test and the heap insertion.
     *
     * @param player   the requesting computer player
     * @param type     the requested type
     * @param priority the priority BYTE
     * @param guard    the guard record to refill
     */
    void pushGuardRequest(@NotNull ComputerPlayer player, int type, int priority, @NotNull ComputerUnitRecord guard) {
        if (!this.isBelowMaximum(player, ComputerPlayer.REQUEST_KIND_GUARD, type)) {
            return;
        }

        player.pushRequest(priority, ComputerPlayer.REQUEST_KIND_GUARD, type, null, -1, guard);
    }

    /**
     * Converts every town's build table into spending requests: cancels the queued production of the town buildings,
     * balances the workers, requests the missing count of each entry, then the refinery, the supply and the town
     * defenses.
     *
     * @param phase the owning phase
     * @param owner the computer owner
     * @throws UnsupportedOperationException if the unit accounting, the player upgrade storage, the unit pool or the player technology storage is not
     *                                       initialized
     */
    void processRequests(@NotNull ComputerPlayerPhase phase, int owner) {
        ComputerPlayer player = phase.getPlayer(owner);

        player.setFlags(player.getFlags() | ComputerPlayer.FLAG_REQUEST_SCAN);

        ComputerTown town = phase.getFirstTown(owner);

        if (town == null) {
            return;
        }

        boolean surplusWorkers = false;
        // The campaign technology counter spans the whole scan.
        int campaignTechnologyCount = 0;
        ComputerTown last = null;
        Player ownerRecord = this.game.getPlayerTable().requirePlayer(owner);
        int race = ownerRecord.getRace();

        for (; town != null; town = town.next) {
            last = town;

            for (ComputerUnitRecord record = town.firstBuilding; record != null; record = record.next) {
                Unit unit = record.requireUnit();

                if (unit.getOwnerIndex() == town.owner) {
                    for (int slot = 1; slot < Unit.BUILD_QUEUE_SLOTS; slot++) {
                        this.game.getUnitLifecycle().cancelBuildQueueEntry(unit, slot, phase.getInstructionLimit());
                    }
                }
            }

            if (town.firstWorker == null) {
                Unit depot = town.resourceDepot;

                if (depot == null || (depot.getStatus() & UnitStatusFlags.COMPLETED) != 0 ||
                    this.game.requireGameData().getBaseType(depot) != ScrUnitIds.ZERG_HATCHERY) {
                    phase.transferWorkers(town, 1);
                }
            } else if (town.workerCount > town.desiredWorkers) {
                surplusWorkers = true;
            }

            for (int entry = 0; entry < ComputerTown.REQUEST_COUNT; entry++) {
                int flags = Byte.toUnsignedInt(town.requestFlags[entry]);

                if (flags < ComputerTown.REQUEST_FIRST_COUNT) {
                    break;
                }

                int kind = flags >>> ComputerTown.REQUEST_KIND_SHIFT & ComputerTown.REQUEST_KIND_MASK;
                int type = Short.toUnsignedInt(town.requestTypes[entry]);
                int count;

                if (kind == ComputerTown.REQUEST_UNIT) {
                    if ((flags & ComputerTown.REQUEST_COUNTS_OWNER_UNITS) != 0) {
                        count = this.game.getComputerUnits().getTypeCount(type, owner);
                    } else {
                        count = this.countTownUnits(town, type, false);
                    }

                    if (town.requestDelay != 0) {
                        continue;
                    }
                } else if (kind == ComputerTown.REQUEST_UPGRADE) {
                    // The current level plus the owner's buildings upgrading this type.
                    count = this.game.getPlayerTable().requirePlayerUpgrades().getLevel(owner, type);

                    UnitPool pool = this.game.getUnitSystem().requireUnitPool();

                    for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
                        Unit unit = pool.getUnit(poolIndex);

                        if (!ComputerUnits.isDeadOrHidden(unit) && unit.getPrimaryOrder() == ScrOrderIds.UPGRADE &&
                            unit.getUpgradeType() == type) {
                            count++;
                        }
                    }
                } else {
                    // Researched, or a building researching it.
                    count = this.game.getPlayerTable().requirePlayerTechnology().isResearched(owner, type) ? 1 : 0;

                    if (count == 0) {
                        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

                        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
                            Unit unit = pool.getUnit(poolIndex);

                            if (!ComputerUnits.isDeadOrHidden(unit) && unit.getPrimaryOrder() == ScrOrderIds.RESEARCH_TECH &&
                                unit.getResearchType() == type) {
                                count = 1;

                                break;
                            }
                        }
                    }
                }

                int wanted = flags >>> ComputerTown.REQUEST_COUNT_SHIFT;

                if (type == ComputerUnits.getWorkerType(race) && wanted > town.desiredWorkers) {
                    wanted = town.desiredWorkers;
                }

                if (count >= wanted) {
                    continue;
                }

                for (int remaining = wanted - count; remaining != 0; remaining--) {
                    int priority = Byte.toUnsignedInt(town.requestPriorities[entry]);

                    if (player.isCampaign() && (kind == ComputerTown.REQUEST_UPGRADE || kind == ComputerTown.REQUEST_TECHNOLOGY)) {
                        // A campaign upgrade or research request loses one priority step per earlier one (not below
                        // zero) and clears the request scan flag.
                        int lowered = campaignTechnologyCount <= priority ? priority - campaignTechnologyCount : 0;

                        player.setFlags(player.getFlags() & ~ComputerPlayer.FLAG_REQUEST_SCAN);

                        if (campaignTechnologyCount <= priority) {
                            campaignTechnologyCount++;
                        }

                        priority = lowered;
                    }

                    if (!player.isCampaign() && kind == ComputerTown.REQUEST_UNIT && type == ComputerUnits.getResourceDepotType(race) &&
                        ownerRecord.getMinerals() < DEPOT_PRIORITY_MINERALS) {
                        priority = DEPOT_PRIORITY;
                    }

                    this.addRequest(player, town, kind, type, priority);
                }
            }

            int workers = town.workerCount;
            int refinery = ComputerUnits.getRefineryType(this.game.getPlayerTable().requirePlayer(town.owner).getRace());

            if (workers <= 1) {
                if (workers != 1 || town.resourceDepot == null || refinery != ScrUnitIds.ZERG_EXTRACTOR) {
                    continue;
                }
            }

            if (this.getGasPercent(player) == 0 && town.mineralTarget != null) {
                continue;
            }

            Unit active = null;

            for (int slot = 0; slot < ComputerTown.GAS_BUILDING_COUNT && active == null; slot++) {
                Unit gas = town.gasBuildings[slot];

                if (gas != null && !ComputerUnits.isDeadOrHidden(gas) &&
                    this.game.requireGameData().getBaseType(gas) != ScrUnitIds.RESOURCE_VESPENE_GEYSER &&
                    this.game.requireGameData().getBaseType(gas) == refinery && gas.getResources() != 0) {
                    active = gas;
                }
            }

            Unit geyser = null;

            for (int slot = 0; slot < ComputerTown.GAS_BUILDING_COUNT && geyser == null; slot++) {
                Unit gas = town.gasBuildings[slot];

                if (gas != null && this.game.getComputerOrders().classifyGas(town.owner, gas) == ComputerOrders.GAS_GEYSER) {
                    geyser = gas;
                }
            }

            if (geyser == null) {
                continue;
            }

            int priority;

            if (active != null) {
                if (town.workerCount < town.desiredWorkers && town.workerCount < SECOND_REFINERY_WORKERS) {
                    continue;
                }

                if (this.game.getPlayerTable().requirePlayer(town.owner).getMinerals() < SECOND_REFINERY_MINERALS) {
                    continue;
                }

                priority = SECOND_REFINERY_PRIORITY;
            } else {
                priority = FIRST_REFINERY_PRIORITY;
            }

            this.addUnitRequest(player, town, refinery, priority);
        }

        if (surplusWorkers) {
            for (ComputerTown candidate = phase.getFirstTown(owner); candidate != null; candidate = candidate.next) {
                Unit depot = candidate.resourceDepot;

                if (depot != null && (depot.getStatus() & UnitStatusFlags.COMPLETED) != 0 && candidate.workerCount < candidate.desiredWorkers) {
                    phase.transferWorkers(candidate, 1);
                }
            }
        }

        this.requestSupply(phase, player, last);
        this.requestTownDefenses(phase, player, last);
    }

    /**
     * Requests supply providers when the town's race runs short.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private void requestSupply(@NotNull ComputerPlayerPhase phase, @NotNull ComputerPlayer player, @NotNull ComputerTown town) {
        int owner = town.owner;
        Player ownerRecord = this.game.getPlayerTable().requirePlayer(owner);
        int race = ownerRecord.getRace();
        int supplyType = race == ScrRaceIds.TERRAN ? ScrUnitIds.TERRAN_SUPPLY_DEPOT
                                                   : race == ScrRaceIds.ZERG ? ScrUnitIds.ZERG_OVERLORD : ScrUnitIds.PROTOSS_PYLON;
        int available = this.game.getSupplyCheck().getAvailableSupply(ComputerUnits.getWorkerType(race), owner);
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        boolean powerNeeded = phase.isPowerNeeded(owner);
        int needed;

        if (!powerNeeded) {
            if (available > SUPPLY_RESERVE) {
                return;
            }

            int used = this.game.getSupplyCheck().getSupplyUsed(supplyType, owner);
            int depotSupply = units.getSupplyProvided(ComputerUnits.getResourceDepotType(race));

            if (Integer.compareUnsigned(used + (SUPPLY_RESERVE + 1), depotSupply) <= 0) {
                return;
            }

            needed = Integer.divideUnsigned(used + (SUPPLY_RESERVE + 1) - depotSupply, units.getSupplyProvided(supplyType));
        } else {
            needed = phase.countOwnerTownUnits(owner, supplyType);
        }

        if ((available == 0 || (player.getFlags() & ComputerPlayer.FLAG_FARMS_WITHOUT_TIMING) == 0 || powerNeeded) &&
            Integer.compareUnsigned(phase.countOwnerTownUnits(owner, supplyType), needed + 1) < 0) {
            this.addUnitRequest(player, town, supplyType, available < LOW_SUPPLY ? URGENT_PRIORITY : SUPPLY_PRIORITY);
        }
    }

    /**
     * Requests the town defenses: a Bunker for a region that wants one, infantry for every completed Bunker with room,
     * and a Missile Turret for a region that wants one.
     */
    private void requestTownDefenses(@NotNull ComputerPlayerPhase phase, @NotNull ComputerPlayer player, @NotNull ComputerTown town) {
        ComputerRegions regions = phase.getRegions();
        int owner = town.owner;
        int regionCount = this.game.getComputerTerrain().getRegions().getRegionCount();

        for (int region = 1; region < regionCount; region++) {
            if ((regions.getFlags(regions.getIndex(owner, region)) & ComputerRegionStates.FLAG_BUNKER_WANTED) != 0) {
                this.addRequest(player, town, ComputerTown.REQUEST_UNIT, ScrUnitIds.TERRAN_BUNKER, DEFENSE_BUILDING_PRIORITY);

                break;
            }
        }

        this.fillBunkers(phase, player);

        for (int region = 1; region < regionCount; region++) {
            if ((regions.getFlags(regions.getIndex(owner, region)) & ComputerRegionStates.FLAG_TURRET_WANTED) != 0) {
                this.addRequest(player, town, ComputerTown.REQUEST_UNIT, ScrUnitIds.TERRAN_MISSILE_TURRET, DEFENSE_BUILDING_PRIORITY);

                return;
            }
        }
    }

    /**
     * Requests infantry for every completed Bunker with room, from the ground-ground use list: Firebats while the
     * owner has fewer Firebats than Bunkers and none is loaded, else Marines. Ends early when the use list lacks
     * Marines or a Bunker requests at {@link #BUNKER_PRIORITY}.
     *
     * @throws UnsupportedOperationException if the unit accounting or the unit pool is not initialized
     */
    private void fillBunkers(@NotNull ComputerPlayerPhase phase, @NotNull ComputerPlayer player) {
        int owner = player.getOwner();
        ComputerRegions regions = phase.getRegions();
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit bunker = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(bunker) || this.game.requireGameData().getBaseType(bunker) != ScrUnitIds.TERRAN_BUNKER ||
                (bunker.getStatus() & UnitStatusFlags.COMPLETED) == 0 || !this.game.getUnitQueries().hasFreeSpace(bunker)) {
                continue;
            }

            int type;

            if (!usesGroundDefense(player, ScrUnitIds.TERRAN_FIREBAT) ||
                this.game.getUnitTransport().forEachLoadedUnit(bunker, loaded -> ComputerMilitary.isFirebat(this.game, loaded)) ||
                Integer.compareUnsigned(this.game.getComputerUnits().getCompletedTypeCount(ScrUnitIds.TERRAN_BUNKER, owner),
                                        this.game.getComputerUnits().getCompletedTypeCount(ScrUnitIds.TERRAN_FIREBAT, owner)) <= 0) {
                if (!usesGroundDefense(player, ScrUnitIds.TERRAN_MARINE)) {
                    return;
                }

                type = ScrUnitIds.TERRAN_MARINE;
            } else {
                type = ScrUnitIds.TERRAN_FIREBAT;
            }

            int index = regions.getIndex(owner, this.game.getComputerTerrain().getUnitRegion(bunker));
            int priority = player.isCampaign() && this.game.getUnitTransport().getOccupiedSpace(bunker) != 0
                           ? LOADED_BUNKER_PRIORITY
                           : BUNKER_PRIORITY;

            if (!phase.getMilitary().useUnit(phase, index, type, priority)) {
                this.requestUnit(player, index, type, priority, true, true);

                if (priority == BUNKER_PRIORITY) {
                    return;
                }
            }
        }
    }

    /**
     * Updates one owner's towns and starts the owner's panic expansion when the towns are complete and either short of
     * mineral or gas capacity, or the owner outnumbers its enemies with surplus resources and few mineral fields.
     *
     * @param phase the owning phase
     * @param owner the owner selected by the town cursor
     */
    void updateTowns(@NotNull ComputerPlayerPhase phase, int owner) {
        ComputerTown first = phase.getFirstTown(owner);

        if (first == null) {
            return;
        }

        int towns = 0;
        int gasTowns = 0;
        int mineralCapacity = 0;
        boolean incomplete = false;

        for (ComputerTown town = first; town != null; town = town.next) {
            towns++;

            if (this.findGas(town, ComputerOrders.GAS_ACTIVE) != null || this.findGas(town, ComputerOrders.GAS_GEYSER) != null) {
                gasTowns++;
            }

            if (!town.buildingsLinked || town.resourceDepot == null || (town.resourceDepot.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
                incomplete = true;
            }

            this.updateTown(phase, town);

            if (town.resourceArea != 0) {
                mineralCapacity += this.getMineralCapacity(phase, town.resourceArea, owner);
            }

            if (town.requestDelay != 0) {
                town.requestDelay--;
            }
        }

        if (incomplete) {
            return;
        }

        boolean expand = mineralCapacity != 0 &&
                         (mineralCapacity < EXPANSION_MINERAL_CAPACITY || towns > 1 && mineralCapacity < TOWNS_EXPANSION_MINERAL_CAPACITY) ||
                         towns > 1 && gasTowns < MINIMUM_GAS_TOWNS;

        if (!expand) {
            Player ownerRecord = this.game.getPlayerTable().requirePlayer(owner);

            if (Integer.compareUnsigned(ownerRecord.getMinerals() - SURPLUS_MINERALS, SURPLUS_MINERALS_RANGE) > 0 ||
                Integer.compareUnsigned(ownerRecord.getGas() - SURPLUS_GAS, SURPLUS_GAS_RANGE) > 0) {
                return;
            }

            if ((phase.getPlayer(owner).getFlags() & ComputerPlayer.FLAG_OUTNUMBERING) == 0) {
                return;
            }

            int minerals = 0;

            for (ComputerTown town = first; town != null; town = town.next) {
                if (town.resourceArea != 0) {
                    minerals += this.getAreaMineralCount(town.resourceArea);
                }
            }

            if (minerals >= SURPLUS_MINERAL_FIELDS) {
                return;
            }
        }

        int panic = phase.getPlayer(owner).getPanicAddress();

        if (panic == 0) {
            return;
        }

        // Expand from the owner's first town without the expansion flag.
        ComputerTown town = phase.getFirstTown(owner);

        if (town != null) {
            phase.getScripts().expand(phase, owner, town, panic, PANIC_EXPANSION_TOWN_COUNT, 0);
        }
    }

    private @Nullable Unit findGas(@NotNull ComputerTown town, int kind) {
        for (int slot = 0; slot < ComputerTown.GAS_BUILDING_COUNT; slot++) {
            Unit gas = town.gasBuildings[slot];

            if (gas != null && this.game.getComputerOrders().classifyGas(town.owner, gas) == kind) {
                return gas;
            }
        }

        return null;
    }

    /**
     * Updates one town: selects its resource area, gas buildings and mineral target, its desired worker count, and
     * assigns its workers to minerals and gas, reordering workers that stayed too long without progress.
     *
     * @param phase the owning phase
     * @param town  the town
     * @throws UnsupportedOperationException if the resource areas or the unit accounting is not initialized
     */
    void updateTown(@NotNull ComputerPlayerPhase phase, @NotNull ComputerTown town) {
        if (!town.buildingsLinked) {
            return;
        }

        int owner = town.owner;

        if (town.resourceArea == 0) {
            town.resourceArea = this.selectResourceArea(phase, owner, town.positionX, town.positionY);

            this.refreshGasBuildings(phase, town);
        } else if (town.resourceAreaPending) {
            this.refreshGasBuildings(phase, town);
        }

        if (town.mineralTarget == null) {
            Unit mineral = this.findAreaMineral(town.resourceArea);

            town.mineralTarget = mineral;

            if (mineral != null) {
                phase.activateBuildingRegion(owner, mineral);
            }
        }

        if (town.resourceArea != 0) {
            town.desiredWorkers = this.getDesiredWorkers(phase, town.resourceArea, owner) & BYTE_MASK;
        }

        int mineralAssignment = ASSIGNMENT_MINERALS;
        int gasAssignment = ASSIGNMENT_FIRST_GAS;
        Unit active = this.findGas(town, ComputerOrders.GAS_ACTIVE);

        if (active == null && this.game.getComputerOrders().findGasBuilding(town, ComputerOrders.GAS_DEPLETED, 1) == null) {
            gasAssignment = ASSIGNMENT_MINERALS;
        } else if (town.mineralTarget == null) {
            mineralAssignment = ASSIGNMENT_FIRST_GAS;
        }

        ComputerPlayer player = phase.getPlayer(owner);
        int gasPercent = this.getGasPercent(player);
        int resourceArea = town.resourceArea;
        int gasSlots = this.game.requireResourceAreas().getGasCount(resourceArea) << GAS_SLOT_SHIFT;
        int gasWorkers = Integer.divideUnsigned(town.workerCount * gasPercent, PERCENT);
        ComputerUnits computerUnits = this.game.getComputerUnits();
        Player ownerRecord = this.game.getPlayerTable().requirePlayer(owner);
        int workers = computerUnits.getCompletedTypeCount(ComputerUnits.getWorkerType(ownerRecord.getRace()), owner);

        if (Integer.compareUnsigned(gasWorkers, gasSlots) > 0) {
            gasWorkers = gasSlots;
        } else if (ownerRecord.getGas() < GAS_SHORTAGE) {
            if (Integer.compareUnsigned(workers, LARGE_WORKFORCE) >= 0) {
                if (Integer.compareUnsigned(gasWorkers, LARGE_WORKFORCE_GAS_WORKERS) < 0) {
                    gasWorkers = LARGE_WORKFORCE_GAS_WORKERS;
                }
            } else if (Integer.compareUnsigned(workers, MEDIUM_WORKFORCE) >= 0) {
                if (Integer.compareUnsigned(gasWorkers, MEDIUM_WORKFORCE_GAS_WORKERS) < 0) {
                    gasWorkers = MEDIUM_WORKFORCE_GAS_WORKERS;
                }
            } else if (Integer.compareUnsigned(workers, SMALL_WORKFORCE) >= 0) {
                if (Integer.compareUnsigned(gasWorkers, SMALL_WORKFORCE_GAS_WORKERS) < 0) {
                    gasWorkers = SMALL_WORKFORCE_GAS_WORKERS;
                }
            } else if (Integer.compareUnsigned(workers, MINIMAL_WORKFORCE) >= 0 && gasWorkers == 0) {
                gasWorkers = 1;
            }
        }

        int nonMineralCount = 0;
        int now = this.getElapsedClockTicks();

        for (ComputerUnitRecord record = town.firstWorker; record != null; record = record.next) {
            Unit unit = record.requireUnit();
            int order = unit.getPrimaryOrder();

            if (order == ScrOrderIds.CONSTRUCTING_BUILDING || order == ScrOrderIds.PICKUP4 || order == ScrOrderIds.DRONE_BUILD ||
                (unit.getStatus() & UnitStatusFlags.LOADED) != 0) {
                record.lastUpdateClock = now;
            } else if (Integer.compareUnsigned(now - record.lastUpdateClock, WORKER_IDLE_LIMIT) >= 0) {
                phase.issueOrder(unit, ScrOrderIds.COMPUTER_AI);

                record.workerDelay     = WORKER_RETRY_DELAY;
                record.workerState     = (record.workerState + 1) & BYTE_MASK;
                record.lastUpdateClock = now;

                phase.transferWorkers(record.requireTown(), 1);
            }

            int assignment;

            if (gasWorkers == 0) {
                assignment = mineralAssignment;
            } else {
                assignment = gasAssignment;
                gasWorkers--;
            }

            record.workerAssignment = assignment & BYTE_MASK;

            if (assignment != ASSIGNMENT_MINERALS && ++nonMineralCount >= ASSIGNMENT_GROUP_SIZE) {
                nonMineralCount = 0;

                int nextGas = gasAssignment == assignment ? gasAssignment + 1 : gasAssignment;
                int nextMineral = mineralAssignment == assignment ? mineralAssignment + 1 : mineralAssignment;

                gasAssignment     = nextGas > ASSIGNMENT_LAST_GAS ? ASSIGNMENT_FIRST_GAS : nextGas;
                mineralAssignment = nextMineral > ASSIGNMENT_LAST_GAS ? ASSIGNMENT_FIRST_GAS : nextMineral;
            }
        }
    }

    /**
     * Refreshes the gas buildings of the town: the non-mineral resources of its resource area fill the three slots in
     * creation order (a later one replaces the third), are ordered by their distance to the area position with the
     * compare-and-swap steps first/second, second/third and first/second, and activate their building regions.
     *
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the resource areas or the unit pool is not initialized
     */
    private void refreshGasBuildings(@NotNull ComputerPlayerPhase phase, @NotNull ComputerTown town) {
        town.resourceAreaPending = false;

        int area = town.resourceArea;
        Unit[] gas = town.gasBuildings;

        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        gas[GAS_FIRST]  = null;
        gas[GAS_SECOND] = null;
        gas[GAS_THIRD]  = null;

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || (units.getFlags(unit.getUnitId()) & UnitTypeFlags.RESOURCE_CONTAINER) == 0) {
                continue;
            }

            int type = unit.getUnitId();

            if (units.isMineralField(type) || unit.getResourceAreaIndex() != area) {
                continue;
            }

            if (gas[GAS_FIRST] == null) {
                gas[GAS_FIRST] = unit;
            } else if (gas[GAS_SECOND] == null) {
                gas[GAS_SECOND] = unit;
            } else {
                gas[GAS_THIRD] = unit;
            }
        }

        ResourceAreas areas = this.game.requireResourceAreas();
        int areaX = areas.getHorizontalPosition(area);
        int areaY = areas.getVerticalPosition(area);

        int first = calculateGasDistance(gas[GAS_FIRST], areaX, areaY);
        int second = calculateGasDistance(gas[GAS_SECOND], areaX, areaY);
        int third = calculateGasDistance(gas[GAS_THIRD], areaX, areaY);
        int nearest = first;

        if (Integer.compareUnsigned(second, first) < 0) {
            Unit swap = gas[GAS_FIRST];

            gas[GAS_FIRST]  = gas[GAS_SECOND];
            gas[GAS_SECOND] = swap;

            nearest = second;
            second  = first;
        }

        if (Integer.compareUnsigned(third, second) < 0) {
            Unit swap = gas[GAS_SECOND];

            gas[GAS_SECOND] = gas[GAS_THIRD];
            gas[GAS_THIRD]  = swap;

            second = third;
        }

        if (Integer.compareUnsigned(second, nearest) < 0) {
            Unit swap = gas[GAS_FIRST];

            gas[GAS_FIRST]  = gas[GAS_SECOND];
            gas[GAS_SECOND] = swap;
        }

        for (int slot = 0; slot < ComputerTown.GAS_BUILDING_COUNT; slot++) {
            if (town.gasBuildings[slot] != null) {
                phase.activateBuildingRegion(town.owner, town.gasBuildings[slot]);
            }
        }
    }

    /**
     * Chooses the resource area of a computer expansion from the town's area and marks it.
     * <p>
     * Every free area (occupancy flags {@code 1|2} clear) is scored in category {@link #ANY_AREA_CATEGORY} and in the
     * category of its resources (gas total above {@value #RICH_GAS_TOTAL}, mineral total above
     * {@value #RICH_MINERAL_TOTAL}, geyser count); each category keeps the nearest safe area by path tiles and the
     * nearest other area by straight distance, later areas winning ties.
     *
     * @param phase  the owning phase
     * @param owner  the computer owner
     * @param source the town's resource area
     * @param count  the script's town-count argument; {@code 1} selects the first preference row
     * @return the selected area, or {@code 0}
     * @throws UnsupportedOperationException if the resource areas, the unit accounting or the player upgrade storage is not initialized
     */
    int selectExpansionArea(@NotNull ComputerPlayerPhase phase, int owner, int source, int count) {
        ResourceAreas areas = this.game.requireResourceAreas();
        int[] safeDistances = new int[EXPANSION_CATEGORY_COUNT];
        int[] safeAreas = new int[EXPANSION_CATEGORY_COUNT];
        int[] otherDistances = new int[EXPANSION_CATEGORY_COUNT];
        int[] otherAreas = new int[EXPANSION_CATEGORY_COUNT];

        Arrays.fill(safeDistances, ComputerPlayerPhase.NO_DISTANCE);
        Arrays.fill(otherDistances, ComputerPlayerPhase.NO_DISTANCE);

        ComputerUnits computerUnits = this.game.getComputerUnits();
        boolean transports = computerUnits.getCompletedTypeCount(ScrUnitIds.PROTOSS_SHUTTLE, owner) != 0 ||
                             computerUnits.getCompletedTypeCount(ScrUnitIds.TERRAN_DROPSHIP, owner) != 0 ||
                             computerUnits.getCompletedTypeCount(ScrUnitIds.ZERG_OVERLORD, owner) != 0 &&
                             this.game.getPlayerTable().requirePlayerUpgrades().getLevel(owner, ScrUpgradeIds.VENTRAL_SACS) != 0;
        int areaCount = areas.getAreaCount();

        for (int area = 1; area <= areaCount; area++) {
            if (area <= LAST_RESOURCE_AREA && (areas.getOccupancyFlags(area) & OCCUPIED_AREA_FLAGS) != 0) {
                continue;
            }

            this.scoreExpansionArea(phase,
                                    areas,
                                    owner,
                                    area,
                                    source,
                                    transports,
                                    ANY_AREA_CATEGORY,
                                    safeDistances, safeAreas,
                                    otherDistances, otherAreas);

            int category;
            int minerals = areas.getMineralTotal(area);

            if (Integer.compareUnsigned(areas.getGasTotal(area), RICH_GAS_TOTAL) <= 0) {
                if (areas.getGasCount(area) == 0) {
                    if (Integer.compareUnsigned(minerals, RICH_MINERAL_TOTAL) > 0) {
                        category = CATEGORY_RICH_MINERALS;
                    } else if (minerals != 0) {
                        category = CATEGORY_MINERALS;
                    } else {
                        continue;
                    }
                } else if (Integer.compareUnsigned(minerals, RICH_MINERAL_TOTAL) > 0) {
                    category = CATEGORY_GAS_RICH_MINERALS;
                } else {
                    category = minerals != 0 ? CATEGORY_GAS_MINERALS : CATEGORY_GAS;
                }
            } else if (Integer.compareUnsigned(minerals, RICH_MINERAL_TOTAL) > 0) {
                category = CATEGORY_RICH_GAS_RICH_MINERALS;
            } else {
                category = minerals != 0 ? CATEGORY_RICH_GAS_MINERALS : CATEGORY_RICH_GAS;
            }

            this.scoreExpansionArea(phase, areas, owner, area, source, transports, category, safeDistances, safeAreas, otherDistances, otherAreas);
        }

        Player player = this.game.getPlayerTable().requirePlayer(owner);
        int minerals = player.getMinerals();
        int gas = player.getGas();
        int difference = minerals > gas ? minerals - gas : gas - minerals;
        int row = PREFERENCE_SINGLE;

        if (count != 1) {
            row = PREFERENCE_BALANCED;

            if (Integer.compareUnsigned(difference, RESOURCE_IMBALANCE) > 0) {
                row = minerals <= gas ? PREFERENCE_BALANCED : PREFERENCE_MINERAL_SURPLUS;
            }
        }

        for (int category : EXPANSION_PREFERENCES[row]) {
            if (category == PREFERENCES_END) {
                break;
            }

            int selected;

            if (safeDistances[category] != ComputerPlayerPhase.NO_DISTANCE) {
                selected = safeAreas[category];
            } else if (otherDistances[category] != ComputerPlayerPhase.NO_DISTANCE) {
                selected = otherAreas[category];
            } else {
                continue;
            }

            if (selected == 0) {
                return 0;
            }

            areas.markExpansion(selected);

            return selected;
        }

        return 0;
    }

    /**
     * Scores one expansion area in a category: an area reached by a safe ground route whose intermediate regions are
     * quiet (required only with transports) competes by path tiles; with transports any other area competes by
     * straight distance. A position outside the tile grid ({@link TerrainRegionLookupUtils#isInTileGrid}) has no
     * ground route.
     */
    private void scoreExpansionArea(@NotNull ComputerPlayerPhase phase,
                                    @NotNull ResourceAreas areas,
                                    int owner,
                                    int area,
                                    int source,
                                    boolean transports,
                                    int category,
                                    int @NotNull [] safeDistances, int @NotNull [] safeAreas,
                                    int @NotNull [] otherDistances, int @NotNull [] otherAreas) {
        int sourceX = (short) areas.getHorizontalPosition(source);
        int sourceY = (short) areas.getVerticalPosition(source);
        int targetX = (short) areas.getHorizontalPosition(area);
        int targetY = (short) areas.getVerticalPosition(area);
        boolean safe = false;

        if (TerrainRegionLookupUtils.isInTileGrid(sourceX, sourceY) && TerrainRegionLookupUtils.isInTileGrid(targetX, targetY)) {
            int sourceRegion = this.game.getComputerTerrain().getRegion((short) sourceX, (short) sourceY);
            int targetRegion = this.game.getComputerTerrain().getRegion((short) targetX, (short) targetY);

            safe = phase.getMilitary().isRouteSafe(phase, owner, sourceRegion, targetRegion);

            if (safe && !phase.getRegions().isRouteQuiet(owner, sourceRegion, targetRegion) && transports) {
                safe = false;
            }
        }

        if (!safe && !transports) {
            return;
        }

        if (!safe) {
            int distance = RegionPathSearch.calculateDistance(sourceX, targetX, sourceY, targetY);

            if (Integer.compareUnsigned(distance, otherDistances[category]) <= 0) {
                otherDistances[category] = distance;
                otherAreas[category]     = area;
            }
        } else {
            int distance = this.game.getComputerPlacement().getPathTiles(sourceX, sourceY, targetX, targetY);

            if (Integer.compareUnsigned(distance, safeDistances[category]) <= 0) {
                safeDistances[category] = distance;
                safeAreas[category]     = area;
            }
        }
    }

    /**
     * Selects the resource area of a town: the nearest of the first {@value #MELEE_RESOURCE_AREAS} areas to the
     * owner's start location for a melee owner, the nearest area to the town for a campaign owner; later areas win
     * ties.
     *
     * @param phase     the owning phase
     * @param owner     the town owner
     * @param positionX the town horizontal position
     * @param positionY the town vertical position
     * @return the selected area, or {@code 0}
     * @throws UnsupportedOperationException if the resource areas are not initialized
     */
    int selectResourceArea(@NotNull ComputerPlayerPhase phase, int owner, int positionX, int positionY) {
        ResourceAreas areas = this.game.requireResourceAreas();
        int count;

        if (!phase.getPlayer(owner).isCampaign()) {
            StartLocation start = this.game.getPlayerTable().getStartLocations().get(owner);

            positionX = (short) start.getX();
            positionY = (short) start.getY();
            count     = MELEE_RESOURCE_AREAS;
        } else {
            count = areas.getAreaCount();
        }

        int selected = 0;
        int best = ComputerPlayerPhase.NO_DISTANCE;

        for (int area = 1; area <= count; area++) {
            int distance = RegionPathSearch.calculateDistance(positionX, areas.getHorizontalPosition(area),
                                                              positionY, areas.getVerticalPosition(area));

            if (Integer.compareUnsigned(distance, best) <= 0) {
                selected = area;
                best     = distance;
            }
        }

        return selected;
    }

    /**
     * Finds the first living mineral field of the area in the active unit list.
     *
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    private @Nullable Unit findAreaMineral(int area) {
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);
            int type = unit.getUnitId();

            if (!ComputerUnits.isDeadOrHidden(unit) && this.game.requireGameData().getUnitTypes().isMineralField(type) &&
                unit.getResourceAreaIndex() == area) {
                return unit;
            }
        }

        return null;
    }

    /**
     * Requests a defense unit for a region and the units a morphing type is made from, unless the owner outnumbers its
     * enemies and the request is not forced.
     *
     * @param player      the requesting computer player
     * @param index       the region record index
     * @param type        the requested unit type
     * @param priority    the priority BYTE
     * @param keepUpdated {@code false} clears the owner update flag
     * @param forced      requests even while the owner outnumbers its enemies
     */
    void requestUnit(@NotNull ComputerPlayer player, int index, int type, int priority, boolean keepUpdated, boolean forced) {
        if (!forced && (player.getFlags() & ComputerPlayer.FLAG_OUTNUMBERING) != 0) {
            return;
        }

        int flags = player.getFlags();

        if (!keepUpdated) {
            flags &= ~ComputerPlayer.FLAG_UPDATED;
        }

        player.setFlags(flags | ComputerPlayer.FLAG_DEFENSE_UPDATED);

        this.reserveCost(player, type);
        this.pushRegionRequest(player, ComputerPlayer.REQUEST_KIND_REGION, type, priority & BYTE_MASK, index);
        this.requestMorphSources(player, type, priority, index);
    }

    /**
     * Requests the units a morphing type is made from, one priority step lower: a Mutalisk for a Guardian or Devourer,
     * two High Templars for an Archon, two Dark Templars for a Dark Archon and a Hydralisk for a Lurker.
     *
     * @param player   the requesting computer player
     * @param type     the morphed unit type
     * @param priority the priority BYTE of the morphed type
     * @param index    the region record index
     */
    void requestMorphSources(@NotNull ComputerPlayer player, int type, int priority, int index) {
        switch (ScrUnitIds.requireValid(this.game.requireGameData().getBaseType(type))) {
            case ScrUnitIds.ZERG_GUARDIAN, ScrUnitIds.ZERG_DEVOURER -> {
                this.reserveCost(player, ScrUnitIds.ZERG_MUTALISK);
                this.pushRegionRequest(player, ComputerPlayer.REQUEST_KIND_REGION, ScrUnitIds.ZERG_MUTALISK, priority - 1 & BYTE_MASK, index);
            }
            case ScrUnitIds.PROTOSS_ARCHON -> {
                this.reserveCost(player, ScrUnitIds.PROTOSS_HIGH_TEMPLAR);
                this.pushRegionRequest(player, ComputerPlayer.REQUEST_KIND_REGION, ScrUnitIds.PROTOSS_HIGH_TEMPLAR, priority - 1 & BYTE_MASK, index);
                this.reserveCost(player, ScrUnitIds.PROTOSS_HIGH_TEMPLAR);
                this.pushRegionRequest(player, ComputerPlayer.REQUEST_KIND_REGION, ScrUnitIds.PROTOSS_HIGH_TEMPLAR, priority - 1 & BYTE_MASK, index);
            }
            case ScrUnitIds.PROTOSS_DARK_ARCHON -> {
                this.reserveCost(player, ScrUnitIds.PROTOSS_DARK_TEMPLAR);
                this.pushRegionRequest(player, ComputerPlayer.REQUEST_KIND_REGION, ScrUnitIds.PROTOSS_DARK_TEMPLAR, priority - 1 & BYTE_MASK, index);
                this.reserveCost(player, ScrUnitIds.PROTOSS_DARK_TEMPLAR);
                this.pushRegionRequest(player, ComputerPlayer.REQUEST_KIND_REGION, ScrUnitIds.PROTOSS_DARK_TEMPLAR, priority - 1 & BYTE_MASK, index);
            }
            case ScrUnitIds.ZERG_LURKER -> {
                this.reserveCost(player, ScrUnitIds.ZERG_HYDRALISK);
                this.pushRegionRequest(player, ComputerPlayer.REQUEST_KIND_REGION, ScrUnitIds.ZERG_HYDRALISK, priority - 1 & BYTE_MASK, index);
            }
            default -> {
            }
        }
    }

    /**
     * Reserves the minerals, gas and supply of a type; two units in one egg reserve twice the supply.
     *
     * @param player the requesting computer player
     * @param type   the unit type
     * @throws NullPointerException if the world was built without game data
     */
    void reserveCost(@NotNull ComputerPlayer player, int type) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        player.reserve(units.getMineralCost(type), units.getGasCost(type), getSupplyCost(units, type));
    }

    /**
     * Counts the town's units of a type, including production unless only completed units count; Zerg morphs count for
     * their base types (Lair and Hive for Hatchery, Hive for Lair, Greater Spire for Spire, Sunken and Spore Colony for
     * Creep Colony).
     *
     * @param town          the town
     * @param unitType      the unit type
     * @param completedOnly whether only completed units are counted
     * @return the count
     */
    int countTownUnits(@NotNull ComputerTown town, int unitType, boolean completedOnly) {
        int count = 0;

        for (ComputerUnitRecord record = town.firstWorker; record != null; record = record.next) {
            count += this.countUnit(record.unit, unitType, completedOnly);
        }

        for (ComputerUnitRecord record = town.firstBuilding; record != null; record = record.next) {
            Unit unit = record.unit;

            count += this.countUnit(unit, unitType, completedOnly);

            if (unitType == ScrUnitIds.ZERG_HATCHERY) {
                count += this.countUnit(unit, ScrUnitIds.ZERG_LAIR, completedOnly);
                count += this.countUnit(unit, ScrUnitIds.ZERG_HIVE, completedOnly);
            } else if (unitType == ScrUnitIds.ZERG_LAIR) {
                count += this.countUnit(unit, ScrUnitIds.ZERG_HIVE, completedOnly);
            } else if (unitType == ScrUnitIds.ZERG_SPIRE) {
                count += this.countUnit(unit, ScrUnitIds.ZERG_GREATER_SPIRE, completedOnly);
            } else if (unitType == ScrUnitIds.ZERG_CREEP_COLONY) {
                count += this.countUnit(unit, ScrUnitIds.ZERG_SUNKEN_COLONY, completedOnly);
                count += this.countUnit(unit, ScrUnitIds.ZERG_SPORE_COLONY, completedOnly);
            }
        }

        return count;
    }

    private int countUnit(@Nullable Unit unit, int unitType, boolean completedOnly) {
        if (unit == null) {
            throw Exceptions.newIllegalStateException("Linked computer record has no unit.");
        }

        if (completedOnly && (unit.getStatus() & UnitStatusFlags.COMPLETED) == 0) {
            return 0;
        }

        if (this.game.requireGameData().getBaseType(unit) == unitType) {
            return 1;
        }

        return completedOnly ? 0 : this.game.getComputerUnits().countProducing(unit, unitType);
    }
}
