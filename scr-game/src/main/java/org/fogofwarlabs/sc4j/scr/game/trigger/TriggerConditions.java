package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.trigger.TriggerRegistry;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.TriggerUnitGroups;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.player.*;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicRegion;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Tests trigger conditions through the condition registry of the context ({@link TriggerContext#getConditionRegistry()}).
 * <p>
 * One dispatch, {@link #testCondition}, serves the three forms a condition list is read in, under one contract: the
 * conditions are tested in order up to the first enabled No Condition, disabled ones are skipped, and the list fails at
 * the first condition that does not hold. {@link #testConditions} reads a decoded definition,
 * {@link ConditionRows#test} the flat rows of a node and {@link #testConditionsInPlace} the record bytes in a region.
 * Conditions never write the record.
 */
public final class TriggerConditions {
    /**
     * The counters {@link #readPlayerCounter} reads: kills, scores (by score type) and deaths.
     */
    static final int COUNTER_KILLS = 0;
    static final int COUNTER_SCORE = 1;
    static final int COUNTER_DEATHS = 2;

    /**
     * The count cache mode after the command counts of the pass were copied.
     */
    private static final int COMMAND_COUNTS_PREPARED = 1;
    /**
     * The count cache mode after the counts of one location were filled.
     */
    private static final int LOCATION_COUNTS_PREPARED = 2;
    /**
     * The distance from a most condition type to its least form.
     */
    private static final int LEAST_FORM_OFFSET = TriggerConditionIds.COMMAND_THE_LEAST - TriggerConditionIds.COMMAND_THE_MOST;
    /**
     * The masks of a BYTE and a WORD condition operand.
     */
    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;

    /**
     * The conditions of a node's definition as flat rows: one row per condition record ({@code 20} bytes: location, player, amount, unit WORD,
     * comparison, type, resource type, flags and the {@code 'S','C'} mask marker) with the fields of its {@link TriggerDefinition.Condition} in the
     * {@code ROW_*} slots, written from the decoded conditions and read only by {@link #test}.
     */
    static final class ConditionRows {
        private static final int ROW_TYPE = 0;
        private static final int ROW_FLAGS = 1;
        private static final int ROW_LOCATION = 2;
        private static final int ROW_SELECTOR = 3;
        private static final int ROW_AMOUNT = 4;
        private static final int ROW_UNIT = 5;
        private static final int ROW_COMPARISON = 6;
        private static final int ROW_RESOURCE = 7;
        /**
         * The location DWORD of a condition with the {@code 'S','C'} marker, else {@code -1}.
         */
        private static final int ROW_MASK = 8;
        private static final int ROW_SIZE = 9;

        private final int @NotNull [] rows;

        /**
         * The definition {@link #describe} wrote the rows from, or {@code null}.
         */
        private @Nullable TriggerDefinition described;

        /**
         * Creates a {@link ConditionRows} instance without rows of a definition.
         */
        ConditionRows() {
            this.rows = new int[TriggerRecordCodec.CONDITION_COUNT * ROW_SIZE];

            this.described = null;
        }

        /**
         * Writes the rows of a definition's conditions unless they are already its rows.
         *
         * @param definition the definition
         */
        void describe(@NotNull TriggerDefinition definition) {
            if (definition == this.described) {
                return;
            }

            for (int index = 0; index < TriggerRecordCodec.CONDITION_COUNT; index++) {
                TriggerDefinition.Condition condition = definition.getCondition(index);
                int base = index * ROW_SIZE;

                this.rows[base + ROW_TYPE]       = condition.getType();
                this.rows[base + ROW_FLAGS]      = condition.getFlags();
                this.rows[base + ROW_LOCATION]   = condition.getLocation();
                this.rows[base + ROW_SELECTOR]   = condition.getPlayerSelector();
                this.rows[base + ROW_AMOUNT]     = condition.getAmount();
                this.rows[base + ROW_UNIT]       = condition.getUnitType();
                this.rows[base + ROW_COMPARISON] = condition.getComparison();
                this.rows[base + ROW_RESOURCE]   = condition.getResourceType();
                this.rows[base + ROW_MASK]       = condition.isMasked() ? condition.getLocation() : -1;
            }

            this.described = definition;
        }

        /**
         * Tests the conditions up to the first enabled No Condition, as {@link TriggerConditions#testConditions}.
         *
         * @param context the trigger context
         * @return {@code true} if every tested condition holds, {@code false} otherwise
         */
        boolean test(@NotNull TriggerContext context) {
            int[] rows = this.rows;

            for (int base = 0; base < rows.length; base += ROW_SIZE) {
                if ((rows[base + ROW_FLAGS] & TriggerFlags.CONDITION_DISABLED) != 0) {
                    continue;
                }

                int type = rows[base + ROW_TYPE];

                if (type == TriggerConditionIds.NO_CONDITION) {
                    break;
                }

                if (!testCondition(context,
                                   type,
                                   rows[base + ROW_LOCATION],
                                   rows[base + ROW_SELECTOR],
                                   rows[base + ROW_AMOUNT],
                                   rows[base + ROW_UNIT],
                                   rows[base + ROW_COMPARISON],
                                   rows[base + ROW_RESOURCE],
                                   rows[base + ROW_MASK])) {
                    return false;
                }
            }

            return true;
        }
    }

    /**
     * Tests the conditions of a record over its bytes in the region, as {@link #testConditions} tests a definition.
     *
     * @param context the trigger context
     * @param region  the region that holds the record
     * @param offset  the offset of the record in the region
     * @return {@code true} if every tested condition holds, {@code false} otherwise
     */
    static boolean testConditionsInPlace(@NotNull TriggerContext context, @NotNull ClassicRegion region, int offset) {
        for (int index = 0; index < TriggerRecordCodec.CONDITION_COUNT; index++) {
            int condition = offset + index * TriggerRecordCodec.CONDITION_SIZE;

            if ((region.readByte(condition + TriggerRecordCodec.CONDITION_FLAGS_FIELD) & TriggerFlags.CONDITION_DISABLED) != 0) {
                continue;
            }

            int type = region.readByte(condition + TriggerRecordCodec.CONDITION_TYPE_FIELD);

            if (type == TriggerConditionIds.NO_CONDITION) {
                break;
            }

            int location = region.readDword(condition + TriggerRecordCodec.CONDITION_LOCATION_FIELD);
            boolean masked = RegionRecordRunnerUtils.readWord(region, condition + TriggerRecordCodec.CONDITION_EXTENSION_FIELD) ==
                             TriggerRecordCodec.MASK_MARKER;

            if (!testCondition(context,
                               type,
                               location,
                               region.readDword(condition + TriggerRecordCodec.CONDITION_PLAYER_FIELD),
                               region.readDword(condition + TriggerRecordCodec.CONDITION_AMOUNT_FIELD),
                               RegionRecordRunnerUtils.readWord(region, condition + TriggerRecordCodec.CONDITION_UNIT_FIELD),
                               region.readByte(condition + TriggerRecordCodec.CONDITION_COMPARISON_FIELD),
                               region.readByte(condition + TriggerRecordCodec.CONDITION_RESOURCE_FIELD),
                               masked ? location : -1)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Tests the conditions of a definition up to the first enabled No Condition, as {@link ConditionRows#test}.
     *
     * @param context    the trigger context
     * @param definition the definition
     * @return {@code true} if every tested condition holds, {@code false} otherwise
     */
    static boolean testConditions(@NotNull TriggerContext context, @NotNull TriggerDefinition definition) {
        for (int conditionIndex = 0; conditionIndex < TriggerRecordCodec.CONDITION_COUNT; conditionIndex++) {
            TriggerDefinition.Condition condition = definition.getCondition(conditionIndex);

            if ((condition.getFlags() & TriggerFlags.CONDITION_DISABLED) != 0) {
                continue;
            }

            int conditionType = condition.getType();

            if (conditionType == TriggerConditionIds.NO_CONDITION) {
                break;
            }

            if (!testCondition(context,
                               conditionType,
                               condition.getLocation(),
                               condition.getPlayerSelector(),
                               condition.getAmount(),
                               condition.getUnitType(),
                               condition.getComparison(),
                               condition.getResourceType(),
                               condition.isMasked() ? condition.getLocation() : -1)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Creates the frozen condition registry with the built-in condition handlers, one slot per condition type. Types {@code 24} and above
     * do not hold.
     *
     * @return the created {@link TriggerRegistry} instance
     */
    static @NotNull TriggerRegistry<ScrTriggerCondition> createRegistry() {
        TriggerRegistry<ScrTriggerCondition> registry = new TriggerRegistry<>(TriggerConditionIds.COUNT);
        // Mission Briefing and Never share the return-zero handler.
        ScrTriggerCondition never = (context, condition) -> false;
        ScrTriggerCondition superlative = TriggerConditions::compareWithActivePlayers;

        registry.register(TriggerConditionIds.COUNTDOWN_TIMER, TriggerConditions::testCountdownTimer);
        registry.register(TriggerConditionIds.COMMAND, TriggerConditions::testCommand);
        registry.register(TriggerConditionIds.BRING, TriggerConditions::testBring);
        registry.register(TriggerConditionIds.ACCUMULATE, TriggerConditions::testAccumulate);
        registry.register(TriggerConditionIds.KILL, TriggerConditions::testKill);

        for (int type = TriggerConditionIds.COMMAND_THE_MOST; type <= TriggerConditionIds.MOST_RESOURCES; type++) {
            registry.register(type, superlative);
        }

        registry.register(TriggerConditionIds.SWITCH, TriggerConditions::testSwitch);
        registry.register(TriggerConditionIds.ELAPSED_TIME, TriggerConditions::testElapsedTime);
        registry.register(TriggerConditionIds.MISSION_BRIEFING, never);
        registry.register(TriggerConditionIds.OPPONENTS, TriggerConditions::testOpponents);
        registry.register(TriggerConditionIds.DEATHS, TriggerConditions::testDeaths);

        for (int type = TriggerConditionIds.COMMAND_THE_LEAST; type <= TriggerConditionIds.LEAST_RESOURCES; type++) {
            registry.register(type, superlative);
        }

        registry.register(TriggerConditionIds.SCORE, TriggerConditions::testScore);
        registry.register(TriggerConditionIds.ALWAYS, (context, condition) -> true);
        registry.register(TriggerConditionIds.NEVER, never);
        registry.freeze();

        return registry;
    }

    /**
     * Tests one enabled condition other than No Condition through the context's condition registry: types at or above
     * the table size fail, every other type runs its condition over the context's operands, which receive the condition
     * record's fields ({@link TriggerDefinition.Condition}).
     *
     * @param context       the trigger context
     * @param conditionType the condition type
     * @param location      the location field
     * @param selector      the player selector field
     * @param amount        the amount field
     * @param unitType      the unit type field
     * @param comparison    the comparison field
     * @param resourceType  the resource type field (also the switch or score type)
     * @param mask          the location DWORD of a condition with the {@code 'S','C'} marker, else {@code -1}
     * @return {@code true} if the condition holds, {@code false} otherwise
     * @throws IllegalStateException if the registry has no handler for a type below its size
     */
    private static boolean testCondition(@NotNull TriggerContext context,
                                         int conditionType,
                                         int location,
                                         int selector,
                                         int amount,
                                         int unitType,
                                         int comparison,
                                         int resourceType,
                                         int mask) {
        TriggerRegistry<ScrTriggerCondition> registry = context.getConditionRegistry();

        if (conditionType >= registry.getCount()) {
            return false;
        }

        ScrTriggerCondition condition = registry.find(conditionType);

        if (condition == null) {
            throw Exceptions.newIllegalStateException("Every condition below {0} is dispatched above: {1}", registry.getCount(), conditionType);
        }

        TriggerConditionOperands operands = context.conditionOperands;

        operands.set(conditionType, location, selector, amount, comparison, unitType, resourceType, mask);

        return condition.test(context, operands);
    }

    /**
     * Tests Switch: the is-set comparison tests a set bit, the is-cleared comparison a cleared bit, any other comparison
     * fails.
     */
    private static boolean testSwitch(@NotNull TriggerContext context, @NotNull TriggerConditionOperands condition) {
        boolean set = context.isSwitchSet(condition.getResourceType());
        int comparison = condition.getComparison();

        return comparison == TriggerModifiers.SWITCH_IS_SET && set || comparison == TriggerModifiers.SWITCH_IS_CLEARED && !set;
    }

    /**
     * Tests Command: the pass snapshot is refreshed once, then completed units are counted unless the comparison is At
     * Most.
     */
    private static boolean testCommand(@NotNull TriggerContext context, @NotNull TriggerConditionOperands condition) {
        prepareCommandCounts(context);

        boolean completed = condition.getComparison() != TriggerModifiers.AT_MOST;
        int count = countCommandUnits(context, condition.getSelector(), condition.getUnitType(), -1, completed);

        return compareCount(condition.getComparison(), count, condition.getAmount());
    }

    /**
     * Tests Bring: location {@code 0} fails; the shared count cache is refilled from the location unless it already holds
     * it, then the units are counted as for Command.
     */
    private static boolean testBring(@NotNull TriggerContext context, @NotNull TriggerConditionOperands condition) {
        int locationIndex = condition.getLocation() & BYTE_MASK;

        if (locationIndex == 0) {
            return false;
        }

        int location = (locationIndex - 1) & BYTE_MASK;

        prepareLocationCounts(context, location);

        boolean completed = condition.getComparison() != TriggerModifiers.AT_MOST;
        int count = countCommandUnits(context, condition.getSelector(), condition.getUnitType(), location, completed);

        return compareCount(condition.getComparison(), count, condition.getAmount());
    }

    /**
     * Tests Accumulate: the resources of the selected players, minerals, gas or both by the resource type, other types
     * zero.
     *
     * @throws UnsupportedOperationException if a player {@code 8..11} is selected and the evaluation runs without a world
     */
    private static boolean testAccumulate(@NotNull TriggerContext context, @NotNull TriggerConditionOperands condition) {
        int selector = condition.getSelector();
        int count = 0;

        for (int selected = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, TriggerContext.WALK_START);
             selected < PlayerTable.PLAYER_SLOTS;
             selected = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, selected)) {
            Player target = selected < PlayerTable.PLAYABLE_PLAYER_SLOTS
                            ? context.requirePlayer(selected)
                            : ScrTriggerSystem.requireWorld(context, "Accumulate over players 8..11").getPlayerTable().requirePlayer(selected);

            count += readResources(target, condition.getResourceType() & WORD_MASK);
        }

        return compareCount(condition.getComparison(), count, condition.getAmount());
    }

    /**
     * Tests Countdown Timer against the countdown.
     */
    private static boolean testCountdownTimer(@NotNull TriggerContext context, @NotNull TriggerConditionOperands condition) {
        return compareCount(condition.getComparison(), context.getCountdownTimer(), condition.getAmount());
    }

    /**
     * Tests Elapsed Time against the elapsed game seconds.
     */
    private static boolean testElapsedTime(@NotNull TriggerContext context, @NotNull TriggerConditionOperands condition) {
        return compareCount(condition.getComparison(), context.getElapsedClockTicks(), condition.getAmount());
    }

    /**
     * Tests Opponents: a single player {@code 0..7} (or the current player) counts its own opponents, any other player
     * ({@link #countOpponents}) and the selectors {@code 22..25} count none, and the group selectors sum the count of every selected player.
     */
    private static boolean testOpponents(@NotNull TriggerContext context, @NotNull TriggerConditionOperands condition) {
        int selector = condition.getSelector();
        int single = TriggerPlayersUtils.resolveSinglePlayer(context, selector);
        int count = 0;

        if (single >= 0) {
            count = countOpponents(context, single);
        } else {
            for (int selected = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, TriggerContext.WALK_START);
                 selected < PlayerTable.PLAYER_SLOTS;
                 selected = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, selected)) {
                count += countOpponents(context, selected);
            }
        }

        return compareCount(condition.getComparison(), count, condition.getAmount());
    }

    /**
     * Tests Kill against the kills of the unit type through the player selector.
     */
    private static boolean testKill(@NotNull TriggerContext context, @NotNull TriggerConditionOperands condition) {
        int kills = sumPlayers(context, condition.getSelector(), condition.getUnitType() & WORD_MASK, COUNTER_KILLS);

        return compareCount(condition.getComparison(), kills, condition.getAmount());
    }

    /**
     * Tests Score against the score of the score type in the resource byte.
     */
    private static boolean testScore(@NotNull TriggerContext context, @NotNull TriggerConditionOperands condition) {
        int score = sumPlayers(context, condition.getSelector(), condition.getResourceType() & BYTE_MASK, COUNTER_SCORE);

        return compareCount(condition.getComparison(), score, condition.getAmount());
    }

    /**
     * Tests Deaths against the deaths of the unit type, through the extended deaths of an EUD map.
     */
    private static boolean testDeaths(@NotNull TriggerContext context, @NotNull TriggerConditionOperands condition) {
        int deaths = ExtendedDeathsUtils.countDeaths(context, condition.getSelector(), condition.getUnitType() & WORD_MASK, condition.getMask());

        return compareCount(condition.getComparison(), deaths, condition.getAmount());
    }

    /**
     * Compares a counted value for the condition handlers: At Least, At Most and Exactly compare unsigned, any other
     * comparison fails.
     */
    private static boolean compareCount(int comparison, int count, int amount) {
        return comparison == TriggerModifiers.AT_LEAST
               ? Integer.compareUnsigned(count, amount) >= 0
               : comparison == TriggerModifiers.AT_MOST
                 ? Integer.compareUnsigned(count, amount) <= 0
                 : comparison == TriggerModifiers.EXACTLY && count == amount;
    }

    /**
     * Sums one counter through a player selector for the kill, score and death conditions: a direct player of {@code 12} or above,
     * a unit type above the unit groups or a score type above the custom score reads zero.
     *
     * @param context  the trigger context
     * @param selector the player selector
     * @param value    the unit type, or the score type of {@link #COUNTER_SCORE}
     * @param kind     the counter: {@link #COUNTER_KILLS}, {@link #COUNTER_SCORE} or {@link #COUNTER_DEATHS}
     * @return the sum over the selected players
     */
    static int sumPlayers(@NotNull TriggerContext context, int selector, int value, int kind) {
        int total = 0;

        for (int player = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, TriggerContext.WALK_START); player < PlayerTable.PLAYER_SLOTS;
             player = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, player)) {
            total += readPlayerCounter(context, player, value, kind);
        }

        return total;
    }

    /**
     * Reads one player's counter: a score by score type, or the kills or deaths of a unit type. Unit type {@code 228} reads
     * the DWORD that follows its count table ({@link PlayerUnitCounts}).
     *
     * @param context the trigger context
     * @param player  the player
     * @param value   the unit type, or the score type of {@link #COUNTER_SCORE}
     * @param kind    the counter: {@link #COUNTER_KILLS}, {@link #COUNTER_SCORE} or {@link #COUNTER_DEATHS}
     * @return the counter value
     */
    static int readPlayerCounter(@NotNull TriggerContext context, int player, int value, int kind) {
        PlayerScores scores = context.requireAccounting().getScores();

        if (kind == COUNTER_SCORE) {
            if (value > TriggerScoreActionsUtils.SCORE_CUSTOM) {
                return 0;
            }

            UnitAccounting accounting = context.requireAccounting();

            return switch (value) {
                case TriggerScoreActionsUtils.SCORE_TOTAL -> scores.getCustomScore(player) + scores.getRazingScore(player) +
                                                             accounting.getBuildingProductionScore(player) + scores.getUnitKillScore(player) +
                                                             accounting.getUnitProductionScore(player);
                case TriggerScoreActionsUtils.SCORE_UNITS -> accounting.getUnitProductionScore(player);
                case TriggerScoreActionsUtils.SCORE_BUILDINGS -> accounting.getBuildingProductionScore(player);
                case TriggerScoreActionsUtils.SCORE_UNITS_AND_BUILDINGS -> accounting.getBuildingProductionScore(player) +
                                                                           accounting.getUnitProductionScore(player);
                case TriggerScoreActionsUtils.SCORE_KILLS -> scores.getUnitKillScore(player);
                case TriggerScoreActionsUtils.SCORE_RAZINGS -> scores.getRazingScore(player);
                case TriggerScoreActionsUtils.SCORE_KILLS_AND_RAZINGS -> scores.getRazingScore(player) + scores.getUnitKillScore(player);
                default -> scores.getCustomScore(player);
            };
        }

        if (value > TriggerUnitGroups.FACTORIES) {
            return 0;
        }

        return kind == COUNTER_KILLS ? scores.getKills(player, value) : scores.getDeaths(player, value);
    }

    /**
     * Fills the count cache from a location: when the cache does not already hold this location, it is cleared and
     * filled from the units of the rectangle query over the location's low WORD bounds that pass the elevation filter
     * and are neither hallucinations nor subunits; completed units also count their contents.
     *
     * @param context  the trigger context
     * @param location the location index
     * @throws UnsupportedOperationException if the evaluation runs without a world, or the world has no unit spatial index for the rectangle query,
     *                                       or the unit pool is not initialized
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if the terrain miniature flags cannot be loaded
     */
    static void prepareLocationCounts(@NotNull TriggerContext context, int location) {
        if (context.preparationFlag == LOCATION_COUNTS_PREPARED && context.preparedLocation == location) {
            return;
        }

        Game world = ScrTriggerSystem.requireWorld(context, "Bring");

        if (context.accountingSnapshot == null) {
            context.accountingSnapshot = context.createAccountingSnapshot();
        }

        context.preparationFlag  = LOCATION_COUNTS_PREPARED;
        context.preparedLocation = location;

        UnitAccounting.TriggerSnapshot counts = context.accountingSnapshot;

        counts.clear();

        UnitSpatialIndex index = world.getUnitSystem().getUnitSpatialIndex();

        if (index == null) {
            throw Exceptions.newUnsupportedOperationException("Bring requires the unit spatial index for its rectangle query.");
        }

        ScrUnitTypeView units = world.requireGameData().getUnitTypes();
        int[] found = TriggerLocations.findUnitsIn(index, units, context.locations[location]);
        int elevationFlags = context.locations[location].getElevationFlags();
        TerrainTileset tileset;

        try {
            tileset = world.requireGameData().getTerrainTileset(world.getMap().getTileset());
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Bring requires the terrain miniature flags.");
        }

        for (int poolIndex : found) {
            Unit unit = world.getUnitSystem().requireUnitPool().getUnit(poolIndex);

            if (world.getTriggerLocations().isExcludedByElevation(tileset, unit, elevationFlags) ||
                (unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
                continue;
            }

            int unitType = unit.getUnitId();

            if ((units.getFlags(unitType) & UnitTypeFlags.SUBUNIT) != 0) {
                continue;
            }

            counts.addLocationUnit(unitType, unit.getOwnerIndex(), units.getGroupFlags(unitType), units.getTraits(unitType));

            if ((unit.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
                world.getTriggerConditions().addCompletedLocationUnit(counts, units, unit);
            }
        }
    }

    /**
     * Counts units for a Command or Bring condition through the player selector, each player as {@link TriggerContext#countCommandedUnits} or
     * {@link TriggerContext#countUnitsAt} counts it.
     *
     * @param context   the trigger context
     * @param selector  the player selector
     * @param unitType  the unit operand
     * @param location  the location index of Bring, {@code -1} for Command
     * @param completed whether only completed units count
     * @return the unit count
     */
    private static int countCommandUnits(@NotNull TriggerContext context, int selector, int unitType, int location, boolean completed) {
        int player = TriggerPlayersUtils.resolveSinglePlayer(context, selector);

        if (player >= 0) {
            return countSelectedPlayerUnits(context, player, unitType, location, completed);
        }

        int count = 0;

        for (player = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, TriggerContext.WALK_START); player < PlayerTable.PLAYER_SLOTS;
             player = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, player)) {
            count += countSelectedPlayerUnits(context, player, unitType, location, completed);
        }

        return count;
    }

    /**
     * Counts one player's units for a Command or Bring condition.
     *
     * @param context   the trigger context
     * @param player    the player
     * @param unitType  the unit operand
     * @param location  the location index of Bring, {@code -1} for Command
     * @param completed whether only completed units count
     * @return the unit count
     */
    private static int countSelectedPlayerUnits(@NotNull TriggerContext context, int player, int unitType, int location, boolean completed) {
        return location < 0 ? context.countCommandedUnits(player, unitType, completed) : context.countUnitsAt(player, unitType, location, completed);
    }

    /**
     * Counts one player's commanded units of a unit type or group from the count cache. Unit type {@code 228} reads the
     * DWORD that follows its count table ({@link PlayerUnitCounts}): the completed counts are followed by the all-units
     * counts, which are followed by the count-cache state and the leaderboard pairs.
     *
     * @param context   the trigger context
     * @param player    the player
     * @param unitType  the unit operand (its low WORD is used)
     * @param completed whether only completed units count
     * @return the count; {@code 0} for a player of {@code 12} or above or a unit type above the unit groups
     * @throws IllegalStateException if the count cache was never prepared
     */
    static int countPlayerUnits(@NotNull TriggerContext context, int player, int unitType, boolean completed) {
        unitType &= WORD_MASK;

        if (player >= PlayerTable.PLAYER_SLOTS || unitType > TriggerUnitGroups.FACTORIES) {
            return 0;
        }

        UnitAccounting.TriggerSnapshot counts = context.accountingSnapshot;

        if (counts == null) {
            throw Exceptions.newIllegalStateException("Command counts require a prepared count cache.");
        }

        return counts.getCommandCount(player, unitType, completed);
    }

    /**
     * Applies the superlative conditions: Command the Most, Commands the Most At, Most Kills, Highest Score, Most
     * Resources and their least forms. Over the active players each computes one value: completed commanded units of
     * the condition unit type (after the pass snapshot, or after the location counts; location {@code 0} fails), kills,
     * the score of the resource byte or resources of the resource byte. The most forms keep the unsigned maximum from
     * {@code 0} and pass when the current player's value, {@code 0} if it is not active, is at least that maximum; the
     * least forms keep the unsigned minimum from {@code 0xFFFFFFFF} and pass when the current player's value,
     * {@code 0xFFFFFFFF} if it is not active, is at most that minimum. Ties pass.
     */
    private static boolean compareWithActivePlayers(@NotNull TriggerContext context, @NotNull TriggerConditionOperands condition) {
        int conditionType = condition.getType();
        int unitType = condition.getUnitType();
        int resourceType = condition.getResourceType();
        boolean least = conditionType >= TriggerConditionIds.COMMAND_THE_LEAST;
        int kind = least ? conditionType - LEAST_FORM_OFFSET : conditionType;

        if (kind == TriggerConditionIds.COMMAND_THE_MOST_AT) {
            int locationIndex = condition.getLocation() & BYTE_MASK;

            if (locationIndex == 0) {
                return false;
            }

            prepareLocationCounts(context, (locationIndex - 1) & BYTE_MASK);
        } else if (kind == TriggerConditionIds.COMMAND_THE_MOST) {
            prepareCommandCounts(context);
        }

        int extreme = least ? -1 : 0;
        int own = least ? -1 : 0;

        for (int player = context.getNextActivePlayer(TriggerContext.WALK_START); player < PlayerTable.PLAYABLE_PLAYER_SLOTS;
             player = context.getNextActivePlayer(player)) {
            int value = switch (kind) {
                case TriggerConditionIds.COMMAND_THE_MOST,
                     TriggerConditionIds.COMMAND_THE_MOST_AT -> countPlayerUnits(context, player, unitType, true);
                case TriggerConditionIds.MOST_KILLS -> readPlayerCounter(context, player, unitType & WORD_MASK, COUNTER_KILLS);
                case TriggerConditionIds.HIGHEST_SCORE -> readPlayerCounter(context, player, resourceType & BYTE_MASK, COUNTER_SCORE);
                default -> readResources(context.requirePlayer(player), resourceType & BYTE_MASK);
            };

            if (player == context.currentPlayer) {
                own = value;
            }

            if (least ? Integer.compareUnsigned(value, extreme) < 0 : Integer.compareUnsigned(value, extreme) > 0) {
                extreme = value;
            }
        }

        return least ? Integer.compareUnsigned(own, extreme) <= 0 : Integer.compareUnsigned(own, extreme) >= 0;
    }

    /**
     * Returns one player's resources of a resource type as the Accumulate and Most Resources conditions read them: minerals, gas or both, other
     * types zero.
     *
     * @param player       the player
     * @param resourceType the resource type operand
     * @return the resources
     */
    public static int readResources(@NotNull Player player, int resourceType) {
        return resourceType == TriggerScoreActionsUtils.RESOURCE_MINERALS
               ? player.getMinerals()
               : resourceType == TriggerScoreActionsUtils.RESOURCE_GAS
                 ? player.getGas()
                 : resourceType == TriggerScoreActionsUtils.RESOURCE_BOTH ? player.getMinerals() + player.getGas() : 0;
    }

    /**
     * Copies the command counts of the pass into the count cache once, until the cache mode changes.
     *
     * @param context the trigger context
     */
    static void prepareCommandCounts(@NotNull TriggerContext context) {
        if (context.preparationFlag == COMMAND_COUNTS_PREPARED) {
            return;
        }

        if (context.accountingSnapshot == null) {
            context.accountingSnapshot = context.createAccountingSnapshot();
        }

        context.preparationFlag = COMMAND_COUNTS_PREPARED;

        context.requireAccounting().copyTriggerSnapshot(context.accountingSnapshot);
    }

    /**
     * Counts one player's opponents as the single-player branch of the Opponents condition: none for a player from 8 (an
     * observer slot id included) and for rescuable and neutral players; otherwise every player of the active-player walk
     * that is not rescuable or neutral and lacks mutual allied victory with the player or one of its mutual allies (the
     * player included) is counted once.
     *
     * @param context the trigger context
     * @param player  the player, any value
     * @return the number of opponents
     */
    static int countOpponents(@NotNull TriggerContext context, int player) {
        if (Integer.compareUnsigned(player, PlayerTable.PLAYABLE_PLAYER_SLOTS) >= 0 || !canOppose(context.requirePlayer(player))) {
            return 0;
        }

        int opponentMask = 0;

        for (int ally = context.getNextActivePlayer(TriggerContext.WALK_START); ally < PlayerTable.PLAYABLE_PLAYER_SLOTS;
             ally = context.getNextActivePlayer(ally)) {
            if (!canOppose(context.requirePlayer(ally))) {
                continue;
            }

            if (ally != player && (context.requireRelations().getAlliance(ally, player) != PlayerRelations.ALLIED_VICTORY ||
                                   context.requireRelations().getAlliance(player, ally) != PlayerRelations.ALLIED_VICTORY)) {
                continue;
            }

            for (int opponent = context.getNextActivePlayer(TriggerContext.WALK_START); opponent < PlayerTable.PLAYABLE_PLAYER_SLOTS;
                 opponent = context.getNextActivePlayer(opponent)) {
                if (opponent == ally || !canOppose(context.requirePlayer(opponent))) {
                    continue;
                }

                if (context.requireRelations().getAlliance(opponent, ally) != PlayerRelations.ALLIED_VICTORY ||
                    context.requireRelations().getAlliance(ally, opponent) != PlayerRelations.ALLIED_VICTORY) {
                    opponentMask |= 1 << opponent;
                }
            }
        }

        return Integer.bitCount(opponentMask);
    }

    /**
     * Tests whether a player takes part in the Opponents count: passive rescuable and neutral players neither have nor are
     * opponents.
     *
     * @param player the player
     * @return {@code true} if the player is neither a passive rescuable nor a neutral player, {@code false} otherwise
     */
    private static boolean canOppose(@NotNull Player player) {
        int playerType = player.getType() & BYTE_MASK;

        return playerType != PlayerTypes.RESCUE_PASSIVE && playerType != PlayerTypes.NEUTRAL;
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link TriggerConditions} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public TriggerConditions(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Counts one completed unit of a location: the completed type, the contents of a unit providing space, Carrier and
     * Reaver hangar contents as Interceptors and Scarabs, a worker's carried powerup, then the completed group counts.
     */
    private void addCompletedLocationUnit(UnitAccounting.@NotNull TriggerSnapshot counts, @NotNull ScrUnitTypeView units, @NotNull Unit unit) {
        int unitType = unit.getUnitId();
        int owner = unit.getOwnerIndex();

        counts.addCompletedType(unitType, owner, 1);

        PlayerUpgrades upgrades = this.game.getPlayerTable().getPlayerUpgrades();
        boolean providesSpace = TriggerLocations.providesSpace(units, upgrades, unit);

        if (providesSpace) {
            for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS; slot++) {
                Unit loaded = this.game.getUnitTransport().getLoadedUnit(unit, slot);

                if (loaded != null) {
                    this.addCompletedLocationUnit(counts, units, loaded);
                }
            }
        }

        if (units.hasHangar(unitType)) {
            counts.addCompletedType(units.isCarrier(unitType) ? ScrUnitIds.PROTOSS_INTERCEPTOR : ScrUnitIds.PROTOSS_SCARAB,
                                    owner,
                                    unit.getHangarCount(false) & BYTE_MASK);
        }

        // A carried flag counts as a unit of its own; another carried powerup counts its type for the carrier's owner.
        Unit carried = (units.getFlags(unitType) & UnitTypeFlags.WORKER) != 0 ? unit.getCarriedPowerup() : null;

        if (carried != null) {
            if (carried.getUnitId() == ScrUnitIds.POWERUP_FLAG) {
                this.addCompletedLocationUnit(counts, units, carried);
            } else {
                counts.addCompletedType(carried.getUnitId(), owner, 1);
            }
        }

        counts.addCompletedLocationUnit(owner, units.getGroupFlags(unitType));
    }
}
