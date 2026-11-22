package org.fogofwarlabs.sc4j.scr.game.map;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkSection;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkThingy;
import org.fogofwarlabs.sc4j.scr.data.format.dat.*;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTechnology;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerUnitAvailability;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerUpgrades;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the map-settings sections of a Use Map Settings game.
 * <p>
 * Versions {@code 59} and {@code 63} read {@code UNIS}, {@code UPGS}, {@code TECS}, {@code PUNI}, {@code UPGR} and {@code PTEC} followed by the
 * optional expansion sections {@code UNIx}, {@code UPGx}, {@code TECx}, {@code PUPx} and {@code PTEx}; version {@code 205} requires the expansion
 * sections. The SC:R versions {@code 64} and {@code 206} load as {@code 63} and {@code 205}. Unit, weapon, upgrade and technology costs replace
 * values of the game's own tables ({@link GameData#createGameCopy}); the installed tables are never modified. Player availability, upgrade
 * levels and research are applied to the game state between {@code THG2} and {@code UNIT}, as in the section order of the loader. Every occurrence of
 * a section is applied in file order.
 */
public final class MapSettingsLoaderUtils {
    /**
     * The upgrades DAT columns that upgrade settings replace, in the order of their WORD arrays.
     */
    private static final @NotNull String @NotNull [] UPGRADE_SETTINGS_FIELDS = {UpgradesDat.MINERAL_COST, UpgradesDat.MINERAL_COST_FACTOR,
                                                                                UpgradesDat.GAS_COST, UpgradesDat.GAS_COST_FACTOR,
                                                                                UpgradesDat.RESEARCH_TIME, UpgradesDat.RESEARCH_TIME_FACTOR};
    /**
     * The technology DAT columns that technology settings replace, in the order of their WORD arrays.
     */
    private static final @NotNull String @NotNull [] TECHNOLOGY_SETTINGS_FIELDS = {TechdataDat.MINERAL_COST, TechdataDat.GAS_COST,
                                                                                   TechdataDat.RESEARCH_TIME, TechdataDat.ENERGY_COST};

    /**
     * The number of weapons of the classic unit settings section ({@code UNIS}).
     */
    private static final int CLASSIC_WEAPON_COUNT = 100;
    /**
     * The bytes of one unit's settings: the use-default byte, the hit point DWORD, the shield WORD, the armor byte and the build time, mineral, gas
     * and string WORDs.
     */
    private static final int UNIT_SETTINGS_SIZE = 16;
    /**
     * The bytes of one weapon's settings: the damage and bonus WORDs.
     */
    private static final int WEAPON_SETTINGS_SIZE = 2 * Short.BYTES;
    /**
     * The bytes of one upgrade's cost and time WORDs, which follow the use-default bytes of all upgrades.
     */
    private static final int UPGRADE_SETTINGS_SIZE = 6 * Short.BYTES;
    /**
     * The padding byte after the use-default bytes of the expansion upgrade settings section ({@code UPGx}).
     */
    private static final int EXPANSION_UPGRADE_PADDING = 1;
    /**
     * The bytes of one technology's settings: the use-default byte and the four cost, time and energy WORDs.
     */
    private static final int TECHNOLOGY_SETTINGS_SIZE = 1 + 4 * Short.BYTES;
    /**
     * The mask of the unsigned unit type WORD of a {@code THG2} record.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * Reads the last {@code VER } section as an unsigned WORD.
     *
     * @param mapFile the map
     * @return the version, or {@code -1} if the map has no such section or one of its occurrences is not a complete two-byte section
     */
    public static int readVersion(@NotNull ChkFile mapFile) {
        int version = -1;

        for (ChkSection section : mapFile.getSections(ChkFile.VERSION_SECTION)) {
            if (section.getDeclaredSize() != Short.BYTES || !section.hasCompletePayload()) {
                return -1;
            }

            version = LittleEndianUtils.readUnsigned16(section.getPayload(), 0);
        }

        return version;
    }

    /**
     * Writes the unit, upgrade and technology settings of a map-settings game into the game's own tables, which still hold the installed values.
     * <p>
     * Unit settings replace hit points, shields, armor, build time, costs and the map string of every type without its use-default byte, and the
     * damage and bonus of the ground and air weapons of the type or of its turret subunit, as read before the settings. Upgrade settings replace
     * the six cost and time fields, technology settings the four cost, time and energy fields. {@code THG2} units are created before these handlers;
     * a {@code THG2} unit whose type changes is rejected until the intermediate tables are represented. Nothing is written if the map is rejected.
     *
     * @param gameData the game's own data ({@link GameData#createGameCopy}), whose tables are written
     * @param mapFile  the map
     * @throws UnsupportedOperationException if the map version is unsupported, a required section is absent, a settings section is malformed, or a
     *                                       {@code THG2} unit precedes its changed unit settings
     */
    public static void applyScenarioSettings(@NotNull GameData gameData, @NotNull ChkFile mapFile) {
        boolean classic = isClassicVersion(mapFile);
        DatTable units = gameData.getUnits();
        DatTable weapons = gameData.getWeapons();
        Map<String, int[]> unitColumns = new HashMap<>();

        for (String field : new String[]{UnitsDat.HIT_POINTS, UnitsDat.SHIELD_AMOUNT, UnitsDat.ARMOR, UnitsDat.BUILD_TIME,
                                         UnitsDat.MINERAL_COST, UnitsDat.GAS_COST, UnitsDat.MAP_STRING_INDEX}) {
            unitColumns.put(field, units.copyColumn(field));
        }

        Map<String, int[]> weaponColumns = new HashMap<>();

        weaponColumns.put(WeaponsDat.DAMAGE_AMOUNT, weapons.copyColumn(WeaponsDat.DAMAGE_AMOUNT));
        weaponColumns.put(WeaponsDat.DAMAGE_BONUS, weapons.copyColumn(WeaponsDat.DAMAGE_BONUS));

        Map<String, int[]> upgradeColumns = new HashMap<>();

        for (String field : UPGRADE_SETTINGS_FIELDS) {
            upgradeColumns.put(field, gameData.getUpgrades().copyColumn(field));
        }

        Map<String, int[]> technologyColumns = new HashMap<>();

        for (String field : TECHNOLOGY_SETTINGS_FIELDS) {
            technologyColumns.put(field, gameData.getTechdata().copyColumn(field));
        }

        if (classic) {
            applyUnits(requireSections(mapFile, ChkFile.UNIT_SETTINGS_SECTION), false, gameData.getUnitTypes(), unitColumns, weaponColumns);
            applyUpgrades(requireSections(mapFile, ChkFile.UPGRADE_SETTINGS_SECTION), false, upgradeColumns);
            applyTechnologies(requireSections(mapFile, ChkFile.TECHNOLOGY_SETTINGS_SECTION), false, technologyColumns);
        }

        applyUnits(getExpansionSections(mapFile, ChkFile.EXPANSION_UNIT_SETTINGS_SECTION, classic), true,
                   gameData.getUnitTypes(),
                   unitColumns, weaponColumns);
        applyUpgrades(getExpansionSections(mapFile, ChkFile.EXPANSION_UPGRADE_SETTINGS_SECTION, classic), true, upgradeColumns);
        applyTechnologies(getExpansionSections(mapFile, ChkFile.EXPANSION_TECHNOLOGY_SETTINGS_SECTION, classic), true, technologyColumns);

        for (ChkThingy thingy : mapFile.getThingies()) {
            int unitType = thingy.getTypeIdentifier() & WORD_MASK;

            if (!thingy.requestsUnitCreation(true) || unitType >= UnitsDat.UNIT_COUNT) {
                continue;
            }

            for (Map.Entry<String, int[]> column : unitColumns.entrySet()) {
                if (units.get(column.getKey(), unitType) != column.getValue()[unitType]) {
                    throw Exceptions.newUnsupportedOperationException("THG2 unit {0} precedes its changed map unit settings.", unitType);
                }
            }
        }

        writeColumns(units, unitColumns);
        writeColumns(weapons, weaponColumns);
        writeColumns(gameData.getUpgrades(), upgradeColumns);
        writeColumns(gameData.getTechdata(), technologyColumns);
    }

    /**
     * Writes replacement columns into a table, field by field in element order.
     *
     * @param table   the table
     * @param columns the replacement values by field name, each with the field's element count
     */
    private static void writeColumns(@NotNull DatTable table, @NotNull Map<String, int @NotNull []> columns) {
        for (Map.Entry<String, int[]> column : columns.entrySet()) {
            int[] values = column.getValue();

            for (int element = 0; element < values.length; element++) {
                table.set(column.getKey(), element, values[element]);
            }
        }
    }

    /**
     * Applies {@code PUNI}, {@code UPGR}/{@code PUPx} and {@code PTEC}/{@code PTEx} to the game state in loader order.
     *
     * @param game    the game state with installed upgrade and technology storage
     * @param mapFile the map
     * @throws UnsupportedOperationException if the map version is unsupported, a required section is absent, a settings section is malformed, or
     *                                       storage is missing
     */
    public static void applyPlayerSettings(@NotNull Game game, @NotNull ChkFile mapFile) {
        boolean classic = isClassicVersion(mapFile);
        PlayerUpgrades upgrades = game.getPlayerTable().getPlayerUpgrades();
        PlayerTechnology technology = game.getPlayerTable().getPlayerTechnology();

        if (upgrades == null || technology == null) {
            throw Exceptions.newUnsupportedOperationException("Map player settings require installed upgrade and technology storage.");
        }

        PlayerUnitAvailability availability = new PlayerUnitAvailability(game.getEngineGame().getContent().getProgress());

        for (ChkSection section : requireSections(mapFile, ChkFile.UNIT_AVAILABILITY_SECTION)) {
            availability.applySection(section.getPayload());
        }

        game.getPlayerTable().setPlayerUnitAvailability(availability);

        if (classic) {
            for (ChkSection section : requireSections(mapFile, ChkFile.UPGRADE_LEVELS_SECTION)) {
                if (!upgrades.applySection(section.getPayload(), false, true)) {
                    throw Exceptions.newUnsupportedOperationException("Map UPGR section has an unsupported size: {0}", section.getDeclaredSize());
                }
            }

            for (ChkSection section : requireSections(mapFile, ChkFile.TECHNOLOGY_AVAILABILITY_SECTION)) {
                technology.applySection(section.getPayload(), false);
            }
        }

        for (ChkSection section : getExpansionSections(mapFile, ChkFile.EXPANSION_UPGRADE_LEVELS_SECTION, classic)) {
            if (!upgrades.applySection(section.getPayload(), true, true)) {
                throw Exceptions.newUnsupportedOperationException("Map PUPx section has an unsupported size: {0}", section.getDeclaredSize());
            }
        }

        for (ChkSection section : getExpansionSections(mapFile, ChkFile.EXPANSION_TECHNOLOGY_AVAILABILITY_SECTION, classic)) {
            technology.applySection(section.getPayload(), true);
        }
    }

    /**
     * Reads the map version and checks that the map-settings loader supports it.
     *
     * @param mapFile the map
     * @return {@code true} if the version uses the classic sections ({@code 59}, {@code 63} or {@code 64}), {@code false} otherwise
     * @throws UnsupportedOperationException if the version is not {@code 59}, {@code 63}, {@code 64}, {@code 205} or {@code 206}
     */
    private static boolean isClassicVersion(@NotNull ChkFile mapFile) {
        int version = readVersion(mapFile);
        boolean classic = version == ChkFile.VERSION_ORIGINAL || version == ChkFile.VERSION_HYBRID || version == ChkFile.VERSION_REMASTERED_ORIGINAL;

        if (!classic && version != ChkFile.VERSION_BROOD_WAR && version != ChkFile.VERSION_REMASTERED_BROOD_WAR) {
            throw Exceptions.newUnsupportedOperationException("Map-settings loading supports CHK versions 59, 63, 64, 205 and 206: {0}", version);
        }

        return classic;
    }

    /**
     * Returns the occurrences of a section the map-settings loader requires.
     *
     * @param mapFile the map
     * @param name    the section name
     * @return the occurrences in file order
     * @throws UnsupportedOperationException if the map lacks the section
     */
    private static @NotNull List<ChkSection> requireSections(@NotNull ChkFile mapFile, @NotNull String name) {
        List<ChkSection> sections = mapFile.getSections(name);

        if (sections.isEmpty()) {
            throw Exceptions.newUnsupportedOperationException("Map-settings loading requires the {0} section.", name);
        }

        return sections;
    }

    /**
     * Returns the occurrences of an expansion section, which the classic versions may omit and the Brood War versions require.
     *
     * @param mapFile the map
     * @param name    the section name
     * @param classic whether the map version uses the classic sections
     * @return the occurrences in file order
     * @throws UnsupportedOperationException if a Brood War version map lacks the section
     */
    private static @NotNull List<ChkSection> getExpansionSections(@NotNull ChkFile mapFile, @NotNull String name, boolean classic) {
        return classic ? mapFile.getSections(name) : requireSections(mapFile, name);
    }

    private static void applyUnits(@NotNull List<ChkSection> sections, boolean expansion,
                                   @NotNull ScrUnitTypeView unitTypes,
                                   @NotNull Map<String, int[]> unitColumns, @NotNull Map<String, int[]> weaponColumns) {
        int weaponCount = expansion ? WeaponsDat.WEAPON_COUNT : CLASSIC_WEAPON_COUNT;

        for (ChkSection section : sections) {
            byte[] payload = section.getPayload();

            if (payload.length < UnitsDat.UNIT_COUNT * UNIT_SETTINGS_SIZE + weaponCount * WEAPON_SETTINGS_SIZE) {
                throw Exceptions.newUnsupportedOperationException("Map unit settings are shorter than their section layout: {0}", payload.length);
            }

            int hitPointsOffset = UnitsDat.UNIT_COUNT;
            int shieldsOffset = hitPointsOffset + UnitsDat.UNIT_COUNT * Integer.BYTES;
            int armorOffset = shieldsOffset + UnitsDat.UNIT_COUNT * Short.BYTES;
            int buildTimeOffset = armorOffset + UnitsDat.UNIT_COUNT;
            int mineralOffset = buildTimeOffset + UnitsDat.UNIT_COUNT * Short.BYTES;
            int gasOffset = mineralOffset + UnitsDat.UNIT_COUNT * Short.BYTES;
            int stringOffset = gasOffset + UnitsDat.UNIT_COUNT * Short.BYTES;
            int damageOffset = stringOffset + UnitsDat.UNIT_COUNT * Short.BYTES;
            int bonusOffset = damageOffset + weaponCount * Short.BYTES;

            for (int unitType = 0; unitType < UnitsDat.UNIT_COUNT; unitType++) {
                if (payload[unitType] != 0) {
                    continue;
                }

                unitColumns.get(UnitsDat.HIT_POINTS)[unitType]       = LittleEndianUtils.readInt32(payload,
                                                                                                   hitPointsOffset + unitType * Integer.BYTES);
                unitColumns.get(UnitsDat.SHIELD_AMOUNT)[unitType]    = LittleEndianUtils.readUnsigned16(payload,
                                                                                                        shieldsOffset + unitType * Short.BYTES);
                unitColumns.get(UnitsDat.ARMOR)[unitType]            = Byte.toUnsignedInt(payload[armorOffset + unitType]);
                unitColumns.get(UnitsDat.BUILD_TIME)[unitType]       = LittleEndianUtils.readUnsigned16(payload,
                                                                                                        buildTimeOffset + unitType * Short.BYTES);
                unitColumns.get(UnitsDat.MINERAL_COST)[unitType]     = LittleEndianUtils.readUnsigned16(payload,
                                                                                                        mineralOffset + unitType * Short.BYTES);
                unitColumns.get(UnitsDat.GAS_COST)[unitType]         = LittleEndianUtils.readUnsigned16(payload, gasOffset + unitType * Short.BYTES);
                unitColumns.get(UnitsDat.MAP_STRING_INDEX)[unitType] = LittleEndianUtils.readUnsigned16(payload,
                                                                                                        stringOffset + unitType * Short.BYTES);

                int turret = unitTypes.getFirstSubunit(unitType);
                int attackingType = turret < UnitsDat.UNIT_COUNT ? turret : unitType;

                for (int weapon : new int[]{unitTypes.getGroundWeapon(attackingType), unitTypes.getAirWeapon(attackingType)}) {
                    if (weapon >= weaponCount) {
                        continue;
                    }

                    weaponColumns.get(WeaponsDat.DAMAGE_AMOUNT)[weapon] = LittleEndianUtils.readUnsigned16(payload,
                                                                                                           damageOffset + weapon * Short.BYTES);
                    weaponColumns.get(WeaponsDat.DAMAGE_BONUS)[weapon]  = LittleEndianUtils.readUnsigned16(payload,
                                                                                                           bonusOffset + weapon * Short.BYTES);
                }
            }
        }
    }

    private static void applyUpgrades(@NotNull List<ChkSection> sections, boolean expansion, @NotNull Map<String, int[]> upgradeColumns) {
        int upgradeCount = expansion ? UpgradesDat.UPGRADE_COUNT : PlayerUpgrades.CLASSIC_UPGRADE_COUNT;
        int defaultsLength = expansion ? upgradeCount + EXPANSION_UPGRADE_PADDING : upgradeCount;

        for (ChkSection section : sections) {
            byte[] payload = section.getPayload();

            if (payload.length < defaultsLength + upgradeCount * UPGRADE_SETTINGS_SIZE) {
                throw Exceptions.newUnsupportedOperationException("Map upgrade settings are shorter than their section layout: {0}", payload.length);
            }

            for (int upgrade = 0; upgrade < upgradeCount; upgrade++) {
                if (payload[upgrade] != 0) {
                    continue;
                }

                for (int fieldIndex = 0; fieldIndex < UPGRADE_SETTINGS_FIELDS.length; fieldIndex++) {
                    int[] column = upgradeColumns.get(UPGRADE_SETTINGS_FIELDS[fieldIndex]);
                    int valueOffset = defaultsLength + (fieldIndex * upgradeCount + upgrade) * Short.BYTES;

                    column[upgrade] = LittleEndianUtils.readUnsigned16(payload, valueOffset);
                }
            }
        }
    }

    private static void applyTechnologies(@NotNull List<ChkSection> sections, boolean expansion, @NotNull Map<String, int[]> technologyColumns) {
        int technologyCount = expansion ? TechdataDat.TECH_COUNT : PlayerTechnology.CLASSIC_TECHNOLOGY_COUNT;

        for (ChkSection section : sections) {
            byte[] payload = section.getPayload();

            if (payload.length < technologyCount * TECHNOLOGY_SETTINGS_SIZE) {
                throw Exceptions.newUnsupportedOperationException("Map technology costs are shorter than their section layout: {0}", payload.length);
            }

            for (int technology = 0; technology < technologyCount; technology++) {
                if (payload[technology] != 0) {
                    continue;
                }

                for (int fieldIndex = 0; fieldIndex < TECHNOLOGY_SETTINGS_FIELDS.length; fieldIndex++) {
                    int[] column = technologyColumns.get(TECHNOLOGY_SETTINGS_FIELDS[fieldIndex]);
                    int valueOffset = technologyCount + (fieldIndex * technologyCount + technology) * Short.BYTES;

                    column[technology] = LittleEndianUtils.readUnsigned16(payload, valueOffset);
                }
            }
        }
    }

    private MapSettingsLoaderUtils() {
    }
}
