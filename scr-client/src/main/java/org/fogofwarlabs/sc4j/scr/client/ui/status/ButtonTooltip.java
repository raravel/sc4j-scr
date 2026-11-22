package org.fogofwarlabs.sc4j.scr.client.ui.status;

import org.fogofwarlabs.sc4j.scr.client.ui.command.ButtonDefinition;
import org.fogofwarlabs.sc4j.scr.client.ui.input.HotkeyProfile;
import org.fogofwarlabs.sc4j.scr.client.ui.text.StatText;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedUnitType;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrTechTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUpgradeTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds command-button labels and costs from the button type and active game tables.
 * Enabled labels omit their key and type prefix; repeatable upgrades append the next level.
 * Disabled labels have no costs. Cost items retain mineral, gas, energy and supply drawing order. A button a mod adds shows its texts in the
 * language of the {@code stat_txt} strings ({@link ScrAddedUnitType.TrainButton#getText(String)}), and a button of a changed table its own
 * texts ({@link ButtonDefinition#getEnabledText()}, {@link ButtonDefinition#getDisabledText()}).
 */
public final class ButtonTooltip {
    private static final int DOUBLE_EGG_UNIT_COUNT = 2;
    private static final int PROTOSS_COST_SLOT = 0;
    private static final int TERRAN_COST_SLOT = 1;
    private static final int ZERG_COST_SLOT = 2;
    private static final int MINERAL_ICON = 0;
    private static final int FIRST_GAS_ICON = 1;
    private static final int PROTOSS_ENERGY_ICON = 9;
    private static final int TERRAN_ENERGY_ICON = 8;
    private static final int ZERG_ENERGY_ICON = 7;
    private static final int PROTOSS_SUPPLY_ICON = 6;
    private static final int TERRAN_SUPPLY_ICON = 5;
    private static final int ZERG_SUPPLY_ICON = 4;

    private static final int BUTTON_PREFIX_LENGTH = 2;
    private static final int UNIT_PRODUCTION = 1;
    private static final int UPGRADE = 2;
    private static final int TECHNOLOGY_USE = 3;
    private static final int RESEARCH = 4;
    private static final int MORPH = 5;
    private static final int COST_RACE_COUNT = 3;
    private static final int NEXT_LEVEL_STRING = 795;

    /**
     * One cost item: an {@code icons} frame and an amount.
     */
    public static final class Cost {
        private final int icon;
        private final int amount;

        /**
         * Creates a {@link Cost} instance with an icon frame and its cost amount.
         *
         * @param icon   the icon frame
         * @param amount the cost amount
         */
        Cost(int icon, int amount) {
            this.icon   = icon;
            this.amount = amount;
        }

        /**
         * @return the {@code icons} frame
         */
        public int getIcon() {
            return this.icon;
        }

        /**
         * @return the amount
         */
        public int getAmount() {
            return this.amount;
        }
    }

    /**
     * Creates a {@link ButtonTooltip} instance for a shown button.
     *
     * @param view        the game view
     * @param text        the {@code stat_txt} strings
     * @param profile     the active hotkey profile, which supplies the enabled button string
     * @param button      the button
     * @param disabled    whether the button is disabled
     * @param disabledId  the disabled string id the command card uses (possibly replaced by {@code 762} "Not Implemented")
     * @param active      the active unit, or {@code null}
     * @param owner       the player whose costs and levels apply
     * @param consoleRace the console race, which selects the gas icon
     * @return the created {@link ButtonTooltip} instance, or {@code null} when the tooltip is empty
     * @throws NullPointerException if the world was built without game data
     */
    public static @Nullable ButtonTooltip build(@NotNull ScrGameView view,
                                                @NotNull StatText text,
                                                @NotNull HotkeyProfile profile,
                                                @NotNull ButtonDefinition button,
                                                boolean disabled,
                                                int disabledId,
                                                @Nullable Unit active,
                                                int owner,
                                                int consoleRace) {
        ScrAddedUnitType.TrainButton added = button.getAddedButton();
        ScrAddedUnitType.TrainButton.Text addedText = added == null ? null : added.getText(text.getLocale());
        String enabled = added != null
                         ? String.valueOf(added.getHotkey()) + ButtonDefinition.UNIT_PRODUCTION_KIND + addedText.getEnabledText()
                         : button.getEnabledText() != null ? button.getEnabledText() : profile.getString(view, button.getEnabledString());
        int type = enabled.length() < BUTTON_PREFIX_LENGTH ? 0 : enabled.charAt(1);
        int value = button.getActionValue();

        if (disabled) {
            // A button a mod adds shows its own disabled text in the client's language, a button of a changed table its own text.
            if (disabledId == 0) {
                String disabledText = addedText == null ? button.getDisabledText() : addedText.getDisabledText();

                return disabledText == null ? null : new ButtonTooltip(disabledText, List.of());
            }

            int identifier = disabledId + (type == UPGRADE ? StatusPanel.getUpgradeLevel(view, owner, value) : 0);

            return new ButtonTooltip(text.getString(view, identifier), List.of());
        }

        String body = enabled.length() < BUTTON_PREFIX_LENGTH ? "" : enabled.substring(BUTTON_PREFIX_LENGTH);
        ScrUnitTypeView units = view.requireGameData().getUnitTypes();
        ScrUpgradeTypeView upgrades = view.requireGameData().getUpgradeTypes();
        ScrTechTypeView technologies = view.requireGameData().getTechTypes();
        int minerals = 0;
        int gas = 0;
        int[] energy = new int[COST_RACE_COUNT];
        int[] supply = new int[COST_RACE_COUNT];
        int level = 0;

        switch (type) {
            case UNIT_PRODUCTION -> {
                minerals = units.getMineralCost(value);
                gas      = units.getGasCost(value);

                int required = units.getSupplyRequired(value) *
                               ((units.getFlags(value) & UnitTypeFlags.TWO_UNITS_IN_ONE_EGG) != 0 ? DOUBLE_EGG_UNIT_COUNT : 1) >>
                               StatusPanel.SUPPLY_FRACTION_BITS;
                int group = units.getGroupFlags(value);
                int slot = (group & UnitGroupFlags.PROTOSS) != 0
                           ? PROTOSS_COST_SLOT
                           : (group & UnitGroupFlags.TERRAN) != 0 ? TERRAN_COST_SLOT : (group & UnitGroupFlags.ZERG) != 0 ? ZERG_COST_SLOT : -1;

                if (slot >= 0) {
                    supply[slot] = required;
                }
            }
            case UPGRADE -> {
                int current = StatusPanel.getUpgradeLevel(view, owner, value);

                minerals = upgrades.getMineralCost(value) + upgrades.getMineralCostFactor(value) * current;
                gas      = upgrades.getGasCost(value) + upgrades.getGasCostFactor(value) * current;

                if (upgrades.getMaximumLevel(value) > 1) {
                    level = current + 1;
                }
            }
            case TECHNOLOGY_USE -> {
                if (active != null) {
                    int group = units.getGroupFlags(active.getUnitId());
                    int slot = (group & UnitGroupFlags.ZERG) != 0
                               ? ZERG_COST_SLOT
                               : (group & UnitGroupFlags.PROTOSS) != 0
                                 ? PROTOSS_COST_SLOT
                                 : (group & UnitGroupFlags.TERRAN) != 0 ? TERRAN_COST_SLOT : -1;

                    if (slot >= 0) {
                        energy[slot] = technologies.getEnergyCost(value);
                    }
                }
            }
            case RESEARCH -> {
                minerals = technologies.getMineralCost(value);
                gas      = technologies.getGasCost(value);
            }
            case MORPH -> {
                minerals = units.getMineralCost(value);
                gas      = units.getGasCost(value);
            }
            default -> {
            }
        }

        if (level != 0) {
            body = String.format(Locale.ROOT, "%s\n%s %d", body, text.getString(view, NEXT_LEVEL_STRING), level);
        }

        // Cost order and icons: minerals 0, gas (console race), energy P/T/Z 9/8/7, supply P/T/Z 6/5/4.
        List<Cost> costs = new ArrayList<>();
        int[] amounts = {minerals, gas,
                         energy[PROTOSS_COST_SLOT], energy[TERRAN_COST_SLOT], energy[ZERG_COST_SLOT],
                         supply[PROTOSS_COST_SLOT], supply[TERRAN_COST_SLOT], supply[ZERG_COST_SLOT]};
        int[] icons = {MINERAL_ICON, FIRST_GAS_ICON + Math.clamp(consoleRace, 0, ScrRaceIds.PLAYABLE_COUNT - 1),
                       PROTOSS_ENERGY_ICON, TERRAN_ENERGY_ICON, ZERG_ENERGY_ICON,
                       PROTOSS_SUPPLY_ICON, TERRAN_SUPPLY_ICON, ZERG_SUPPLY_ICON};

        for (int index = 0; index < amounts.length; index++) {
            if (amounts[index] != 0) {
                costs.add(new Cost(icons[index], amounts[index]));
            }
        }

        return new ButtonTooltip(body, costs);
    }

    private final @NotNull String text;
    private final @NotNull List<Cost> costs;

    /**
     * Creates a {@link ButtonTooltip} instance with the tooltip text and the ordered cost items.
     *
     * @param text  the tooltip text
     * @param costs the ordered costs
     */
    private ButtonTooltip(@NotNull String text, @NotNull List<Cost> costs) {
        this.text  = text;
        this.costs = costs;
    }

    /**
     * @return the text with color codes and line breaks
     */
    public @NotNull String getText() {
        return this.text;
    }

    /**
     * @return the cost items in drawing order
     */
    public @NotNull List<Cost> getCosts() {
        return this.costs;
    }
}
