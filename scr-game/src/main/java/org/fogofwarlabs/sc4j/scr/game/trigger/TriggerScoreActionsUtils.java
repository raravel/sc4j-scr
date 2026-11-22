package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerScores;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.jetbrains.annotations.NotNull;

/**
 * Applies the resource and score handlers of the trigger actions, and names the resource and score type operands of
 * trigger conditions and actions (the names follow StarEdit).
 */
public final class TriggerScoreActionsUtils {
    /**
     * The resource type operand of minerals.
     */
    public static final int RESOURCE_MINERALS = 0;
    /**
     * The resource type operand of gas.
     */
    public static final int RESOURCE_GAS = 1;
    /**
     * The resource type operand of minerals and gas together.
     */
    public static final int RESOURCE_BOTH = 2;

    /**
     * The score type operand of the total score.
     */
    static final int SCORE_TOTAL = 0;
    /**
     * The score type operand of the unit production score.
     */
    static final int SCORE_UNITS = 1;
    /**
     * The score type operand of the building production score.
     */
    static final int SCORE_BUILDINGS = 2;
    /**
     * The score type operand of the unit and building production scores together.
     */
    static final int SCORE_UNITS_AND_BUILDINGS = 3;
    /**
     * The score type operand of the unit kill score.
     */
    static final int SCORE_KILLS = 4;
    /**
     * The score type operand of the razing score.
     */
    static final int SCORE_RAZINGS = 5;
    /**
     * The score type operand of the kill and razing scores together.
     */
    static final int SCORE_KILLS_AND_RAZINGS = 6;
    /**
     * The score type operand of the custom score, the last score type.
     */
    static final int SCORE_CUSTOM = 7;

    /**
     * The shift that divides a Set Score amount into the quarters of the total score.
     */
    private static final int QUARTER_SHIFT = 2;
    /**
     * The rounding that makes the quarter of a Set Score amount round up.
     */
    private static final int QUARTER_ROUNDING = (1 << QUARTER_SHIFT) - 1;
    /**
     * The rounding that makes the half of a Set Score amount round up.
     */
    private static final int HALF_ROUNDING = 1;

    /**
     * Applies Set Resources to one player below 8 ({@link #setResources(Player, int, int, int)}); other players and resource types do nothing.
     *
     * @param context      the trigger context
     * @param player       the player
     * @param modifier     the modifier: set, add or subtract
     * @param resourceType the resource type operand
     * @param amount       the amount
     */
    static void setResources(@NotNull TriggerContext context, int player, int modifier, int resourceType, int amount) {
        if (player >= PlayerTable.PLAYABLE_PLAYER_SLOTS || resourceType > RESOURCE_BOTH) {
            return;
        }

        setResources(context.requirePlayer(player), modifier, resourceType, amount);
    }

    /**
     * Sets a player's resources as Set Resources does: {@link TriggerModifiers#SET_TO} writes the current and gathered amounts,
     * {@link TriggerModifiers#ADD} adds to both, any other modifier subtracts from the current amount without going below zero (unsigned).
     * The resource type selects minerals, gas or both; other resource types do nothing. Set Resources applies it only to players below
     * {@code 8}.
     *
     * @param target       the player record
     * @param modifier     the modifier: {@link TriggerModifiers#SET_TO}, {@link TriggerModifiers#ADD} or {@link TriggerModifiers#SUBTRACT}
     * @param resourceType {@link #RESOURCE_MINERALS}, {@link #RESOURCE_GAS} or {@link #RESOURCE_BOTH}
     * @param amount       the amount
     */
    public static void setResources(@NotNull Player target, int modifier, int resourceType, int amount) {
        if (resourceType > RESOURCE_BOTH) {
            return;
        }

        if (resourceType != RESOURCE_GAS) {
            switch (modifier) {
                case TriggerModifiers.SET_TO -> {
                    target.setMinerals(amount);
                    target.setGatheredMinerals(amount);
                }
                case TriggerModifiers.ADD -> {
                    target.setGatheredMinerals(target.getGatheredMinerals() + amount);
                    target.setMinerals(target.getMinerals() + amount);
                }
                default -> target.setMinerals(Integer.compareUnsigned(target.getMinerals(), amount) > 0 ? target.getMinerals() - amount : 0);
            }
        }

        if (resourceType != RESOURCE_MINERALS) {
            switch (modifier) {
                case TriggerModifiers.SET_TO -> {
                    target.setGas(amount);
                    target.setGatheredGas(amount);
                }
                case TriggerModifiers.ADD -> {
                    target.setGatheredGas(target.getGatheredGas() + amount);
                    target.setGas(target.getGas() + amount);
                }
                default -> target.setGas(Integer.compareUnsigned(target.getGas(), amount) > 0 ? target.getGas() - amount : 0);
            }
        }
    }

    /**
     * Applies one player's Set Score modification: the total score splits the amount rounded up into quarters over the
     * unit, building, kill and razing scores, the two combined types split it rounded up into halves, and the single
     * types modify the unit, building, kill, razing or custom score; subtraction clamps at zero.
     *
     * @param accounting the unit accounting that holds the scores
     * @param player     the player
     * @param scoreType  the score type operand
     * @param modifier   the modifier: set, add or subtract
     * @param amount     the amount
     */
    static void modifyScore(@NotNull UnitAccounting accounting, int player, int scoreType, int modifier, int amount) {
        PlayerScores scores = accounting.getScores();
        int quarter = (amount + QUARTER_ROUNDING) >>> QUARTER_SHIFT;
        int half = (amount + HALF_ROUNDING) >>> 1;

        switch (scoreType) {
            case SCORE_TOTAL -> {
                accounting.setUnitProductionScore(player, PlayerScores.modify(accounting.getUnitProductionScore(player), modifier, quarter));
                accounting.setBuildingProductionScore(player, PlayerScores.modify(accounting.getBuildingProductionScore(player), modifier, quarter));
                scores.setUnitKillScore(player, PlayerScores.modify(scores.getUnitKillScore(player), modifier, quarter));
                scores.setRazingScore(player, PlayerScores.modify(scores.getRazingScore(player), modifier, quarter));
            }
            case SCORE_UNITS -> {
                accounting.setUnitProductionScore(player, PlayerScores.modify(accounting.getUnitProductionScore(player), modifier, amount));
            }
            case SCORE_BUILDINGS -> {
                accounting.setBuildingProductionScore(player, PlayerScores.modify(accounting.getBuildingProductionScore(player), modifier, amount));
            }
            case SCORE_UNITS_AND_BUILDINGS -> {
                accounting.setUnitProductionScore(player, PlayerScores.modify(accounting.getUnitProductionScore(player), modifier, half));
                accounting.setBuildingProductionScore(player, PlayerScores.modify(accounting.getBuildingProductionScore(player), modifier, half));
            }
            case SCORE_KILLS -> scores.setUnitKillScore(player, PlayerScores.modify(scores.getUnitKillScore(player), modifier, amount));
            case SCORE_RAZINGS -> scores.setRazingScore(player, PlayerScores.modify(scores.getRazingScore(player), modifier, amount));
            case SCORE_KILLS_AND_RAZINGS -> {
                scores.setUnitKillScore(player, PlayerScores.modify(scores.getUnitKillScore(player), modifier, half));
                scores.setRazingScore(player, PlayerScores.modify(scores.getRazingScore(player), modifier, half));
            }
            case SCORE_CUSTOM -> scores.setCustomScore(player, PlayerScores.modify(scores.getCustomScore(player), modifier, amount));
            default -> {
            }
        }
    }


    private TriggerScoreActionsUtils() {
    }
}
