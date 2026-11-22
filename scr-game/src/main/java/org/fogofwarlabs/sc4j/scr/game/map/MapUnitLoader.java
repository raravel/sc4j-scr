package org.fogofwarlabs.sc4j.scr.game.map;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.GameSettings;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkSection;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkUnit;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.production.UnitAddons;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitCreationContext;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Loads {@code UNIT} records, initializes start locations and connects preplaced unit links.
 */
public final class MapUnitLoader {
    /**
     * The number of WORDs of a position: a horizontal and a vertical WORD.
     */
    public static final int POSITION_COMPONENT_COUNT = 2;
    /**
     * The index of the horizontal WORD in a position.
     */
    public static final int HORIZONTAL_POSITION_INDEX = 0;
    /**
     * The index of the vertical WORD in a position.
     */
    public static final int VERTICAL_POSITION_INDEX = 1;
    /**
     * The number of start position WORDs: a horizontal and a vertical WORD per playable slot.
     */
    public static final int START_POSITIONS_SIZE = POSITION_COMPONENT_COUNT * PlayerTable.PLAYABLE_PLAYER_SLOTS;

    /**
     * The divisor of a viewport extent that gives the camera margin to its center.
     */
    private static final int VIEWPORT_CENTER_DIVISOR = 2;
    /**
     * The mask of an unsigned WORD: the unit type and the coordinates of a {@code UNIT} record and the camera coordinates.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The mask of an unsigned BYTE: the owner of a {@code UNIT} record and the type of its player.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * The number of camera position WORDs: horizontal and vertical.
     */
    private static final int CAMERA_POSITION_SIZE = POSITION_COMPONENT_COUNT;
    /**
     * The camera coordinate WORD that is not set yet.
     */
    private static final int UNSET_CAMERA_COORDINATE = 0xFFFF;
    /**
     * The critter types {@code CRITTER_RHYNADON + bit} a restricted preplacement keeps: Rhynadon, Bengalaas, Scantid, Kakaru, Ragnasaur and Ursadon
     * (not the Cargo Ship and the Mercenary Gunship between them).
     */
    private static final int KEPT_CRITTER_TYPES = 0xF3;
    /**
     * The bit of the Rhynadon in {@link #KEPT_CRITTER_TYPES}; shifted left by the offset of a critter type, it gives the bit of that type.
     */
    private static final int KEPT_CRITTER_BIT = 1;

    /**
     * Retains a constructor request and its actual allocation and random call outcome.
     */
    public static final class Creation {
        private final int unitType;
        private final int owner;

        private final int poolIndex;
        private final int generation;

        private final int randomCallsBefore;
        private final int randomCallsAfter;

        private Creation(@NotNull Game game, int unitType, int owner, @Nullable Unit unit, int randomCallsBefore) {
            this.unitType = unitType;
            this.owner    = owner;

            this.poolIndex  = unit == null ? 0 : unit.getIndex();
            this.generation = unit == null ? 0 : game.getUnitSystem().requireUnitPool().getGeneration(this.poolIndex);

            this.randomCallsBefore = randomCallsBefore;
            this.randomCallsAfter  = game.getRandom().getTotalRandomCounts();
        }

        /**
         * @return the requested unit type WORD
         */
        public int getUnitType() {
            return this.unitType;
        }

        /**
         * @return the requested owner BYTE
         */
        public int getOwner() {
            return this.owner;
        }

        /**
         * @return the allocated unit pool index, or {@code 0} for a rejected request
         */
        public int getPoolIndex() {
            return this.poolIndex;
        }

        /**
         * @return the generation at construction, or {@code 0} for a rejected request
         */
        public int getGeneration() {
            return this.generation;
        }

        /**
         * @return the game random call count before construction
         */
        public int getRandomCallsBefore() {
            return this.randomCallsBefore;
        }

        /**
         * @return the game random call count after construction and properties
         */
        public int getRandomCallsAfter() {
            return this.randomCallsAfter;
        }
    }

    /**
     * Retains a node of the {@code UNIT} link lists: the record serial, its related serial and the created unit.
     */
    private static final class SerialLink {
        private final int serial;
        private final int linkedSerial;
        private final @NotNull Unit unit;

        SerialLink(int serial, int linkedSerial, @NotNull Unit unit) {
            this.serial       = serial;
            this.linkedSerial = linkedSerial;
            this.unit         = unit;
        }

        int getSerial() {
            return this.serial;
        }

        int getLinkedSerial() {
            return this.linkedSerial;
        }

        @NotNull Unit getUnit() {
            return this.unit;
        }
    }

    /**
     * Executes one {@code UNIT} occurrence, preserving its independent zeroed start-location buffer.
     * <p>
     * The caller supplies established creation flags, camera state and normal player records. The supplied start-position buffer is replaced only
     * after the Nydus and add-on link passes return.
     *
     * @param creation               the unit creation inputs
     * @param game                   the initialized game state
     * @param section                the original section descriptor
     * @param records                the decoded records belonging only to this occurrence
     * @param settings               the established game settings
     * @param startPositions         the sixteen WORD outputs replaced by this occurrence
     * @param cameraPosition         the retained two WORD camera coordinates
     * @param viewportWidth          the viewport width
     * @param viewportHeight         the viewport height
     * @param replayCamera           whether start locations use the first unset camera coordinate
     * @param restrictPreplacedUnits the preplaced-unit filter BYTE
     * @param expansionEnabled       the expansion mode
     * @param instructionLimit       the iscript execution bound
     * @return the constructor outcomes in record order
     * @throws IllegalArgumentException      if the section is not a complete {@code UNIT} section, the records are not the complete records of the
     *                                       section or the coordinate arrays have another size
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if a unit owner has no player record
     * @throws IllegalStateException         if the unit pool is missing
     */
    public static @NotNull List<Creation> load(@NotNull UnitCreationContext creation, @NotNull Game game,
                                               @NotNull ChkSection section,
                                               @NotNull List<ChkUnit> records,
                                               @NotNull GameSettings settings,
                                               int @NotNull [] startPositions,
                                               int @NotNull [] cameraPosition,
                                               int viewportWidth, int viewportHeight,
                                               boolean replayCamera,
                                               boolean restrictPreplacedUnits,
                                               boolean expansionEnabled,
                                               int instructionLimit) {
        if (!section.getName().equals(ChkFile.UNIT_SECTION) || !section.hasCompletePayload() || section.getDeclaredSize() % ChkUnit.ENTRY_SIZE != 0 ||
            records.size() != section.getDeclaredSize() / ChkUnit.ENTRY_SIZE) {
            throw Exceptions.newIllegalArgumentException("UNIT processing requires complete records from its source section.");
        }

        if (startPositions.length != START_POSITIONS_SIZE || cameraPosition.length != CAMERA_POSITION_SIZE) {
            throw Exceptions.newIllegalArgumentException("UNIT coordinate storage has an invalid extent.");
        }

        if (game.getUnitSystem().getUnitPool() == null) {
            throw Exceptions.newIllegalStateException("UNIT processing requires the unit pool of the game.");
        }

        int[] sectionStartPositions = new int[START_POSITIONS_SIZE];
        List<Creation> creations = new ArrayList<>();
        LinkedList<SerialLink> nydusLinks = new LinkedList<>();
        LinkedList<SerialLink> addonLinks = new LinkedList<>();
        ScrUnitTypeView unitTypes = game.requireGameData().getUnitTypes();

        for (int recordIndex = 0; recordIndex < records.size(); recordIndex++) {
            ChkUnit record = records.get(recordIndex);
            int unitType = record.getUnitId() & WORD_MASK;
            int owner = record.getOwner() & BYTE_MASK;

            if (unitType == ScrUnitIds.SPECIAL_START_LOCATION) {
                if (owner < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                    sectionStartPositions[owner * POSITION_COMPONENT_COUNT + HORIZONTAL_POSITION_INDEX] = record.getX() & WORD_MASK;
                    sectionStartPositions[owner * POSITION_COMPONENT_COUNT + VERTICAL_POSITION_INDEX]   = record.getY() & WORD_MASK;
                }

                if (replayCamera
                    ? (cameraPosition[HORIZONTAL_POSITION_INDEX] & WORD_MASK) == UNSET_CAMERA_COORDINATE
                    : owner == creation.getLocalPlayer()) {
                    int horizontalMargin = (viewportWidth + (TerrainTileset.TILE_SIZE - 1)) / (VIEWPORT_CENTER_DIVISOR * TerrainTileset.TILE_SIZE);
                    int verticalMargin = (viewportHeight + (TerrainTileset.TILE_SIZE - 1)) / (VIEWPORT_CENTER_DIVISOR * TerrainTileset.TILE_SIZE);
                    int tileX = (record.getX() & WORD_MASK) >>> TerrainTileset.TILE_SHIFT;
                    int tileY = (record.getY() & WORD_MASK) >>> TerrainTileset.TILE_SHIFT;

                    cameraPosition[HORIZONTAL_POSITION_INDEX] = Math.max(0, tileX - horizontalMargin) & WORD_MASK;
                    cameraPosition[VERTICAL_POSITION_INDEX]   = Math.max(0, tileY - verticalMargin) & WORD_MASK;
                }

                continue;
            }

            if (!requestsCreation(game, record, settings, restrictPreplacedUnits)) {
                continue;
            }

            Player player = game.getPlayerTable().getPlayer(owner < PlayerTable.PLAYER_SLOTS ? owner : PlayerTable.NEUTRAL_PLAYER_ID);

            if (player == null) {
                throw Exceptions.newUnsupportedOperationException("UNIT processing requires a decoded owner record.");
            }

            int randomCallsBefore = game.getRandom().getTotalRandomCounts();
            Unit unit = PreplacedUnitLoaderUtils.create(creation, game,
                                                        unitType,
                                                        record.getX() & WORD_MASK, record.getY() & WORD_MASK,
                                                        owner, player.getType(),
                                                        expansionEnabled,
                                                        instructionLimit);

            if (unit != null) {
                PreplacedUnitLoaderUtils.applyProperties(game, unit, record, instructionLimit);

                // Nydus Canals and grounded Terran buildings with a nonzero related serial are prepended to their link lists.
                int linkedSerial = (int) record.getLinkedSerial();
                int groupFlags = unitTypes.getGroupFlags(unit.getUnitId());

                if (linkedSerial != 0 && unit.getUnitId() == ScrUnitIds.ZERG_NYDUS_CANAL) {
                    nydusLinks.addFirst(new SerialLink((int) record.getSerial(), linkedSerial, unit));
                }

                if (linkedSerial != 0 &&
                    (groupFlags & (UnitGroupFlags.ZERG | UnitGroupFlags.TERRAN | UnitGroupFlags.PROTOSS)) == UnitGroupFlags.TERRAN &&
                    UnitQueries.isGroundedBuilding(unit)) {
                    addonLinks.addFirst(new SerialLink((int) record.getSerial(), linkedSerial, unit));
                }
            }

            creations.add(new Creation(game, unitType, owner, unit, randomCallsBefore));
        }

        linkNydusCanals(nydusLinks);
        linkAddons(game, addonLinks, instructionLimit);
        System.arraycopy(sectionStartPositions, 0, startPositions, 0, START_POSITIONS_SIZE);

        game.getSpriteVisibility().updateUnits(0);
        game.requestBuildingPowerRefresh();

        return List.copyOf(creations);
    }

    /**
     * Checks whether a {@code UNIT} record requests creation: start locations never do, records of a playable slot need a computer, human, rescue or
     * neutral player, a restricted preplacement keeps only neutral resources and critters, and the melee units of a map-settings owner need the
     * neutral group flag.
     *
     * @param game                   the game state with decoded player records
     * @param record                 the {@code UNIT} record
     * @param settings               the game settings
     * @param restrictPreplacedUnits the preplaced-unit filter BYTE
     * @return {@code true} if the record requests creation, {@code false} otherwise
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if a playable owner has no player record
     */
    static boolean requestsCreation(@NotNull Game game, @NotNull ChkUnit record, @NotNull GameSettings settings, boolean restrictPreplacedUnits) {
        int unitType = record.getUnitId() & WORD_MASK;
        int owner = record.getOwner() & BYTE_MASK;

        if (unitType == ScrUnitIds.SPECIAL_START_LOCATION) {
            return false;
        }

        if (owner < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            Player player = game.getPlayerTable().getPlayer(owner);

            if (player == null) {
                throw Exceptions.newUnsupportedOperationException("UNIT routing requires decoded player records.");
            }

            int playerType = player.getType() & BYTE_MASK;

            if ((playerType < PlayerTypes.COMPUTER || playerType > PlayerTypes.RESCUE_ACTIVE) && playerType != PlayerTypes.NEUTRAL) {
                return false;
            }
        }

        if (restrictPreplacedUnits && (owner != PlayerTable.NEUTRAL_PLAYER_ID ||
                                       !(game.requireGameData().getUnitTypes().isMineralField(unitType) ||
                                         unitType == ScrUnitIds.RESOURCE_VESPENE_GEYSER ||
                                         unitType >= ScrUnitIds.CRITTER_RHYNADON && unitType <= ScrUnitIds.CRITTER_URSADON &&
                                         (KEPT_CRITTER_TYPES & KEPT_CRITTER_BIT << (unitType - ScrUnitIds.CRITTER_RHYNADON)) != 0))) {
            return false;
        }

        // The group-flag byte is read only for map-settings owners with melee units.
        if (!settings.usesMapSettings() || !settings.createsMeleeUnitsForPlayer(owner)) {
            return true;
        }

        return (game.requireGameData().getUnitTypes().getGroupFlags(unitType) & UnitGroupFlags.NEUTRAL) != 0;
    }

    /**
     * Connects the Nydus pairs after the {@code UNIT} loop: from the list head (last created), each node looks for a later node whose related serial
     * is its own serial; a pair sets both exits and both nodes leave the list, an unmatched node leaves alone.
     *
     * @param links the Nydus link list, emptied
     */
    private static void linkNydusCanals(@NotNull LinkedList<SerialLink> links) {
        while (!links.isEmpty()) {
            SerialLink node = links.removeFirst();

            for (Iterator<SerialLink> iterator = links.iterator(); iterator.hasNext(); ) {
                SerialLink partner = iterator.next();

                if (partner.getLinkedSerial() == node.getSerial()) {
                    node.getUnit().setNydusExit(partner.getUnit());
                    partner.getUnit().setNydusExit(node.getUnit());
                    iterator.remove();

                    break;
                }
            }
        }
    }

    /**
     * Connects the add-on pairs after the {@code UNIT} loop. Pairs are found as for Nydus Canals; an add-on connects to the other building when
     * {@link UnitAddons#canAttach(int, int)} accepts their types.
     *
     * @param game             the game state
     * @param links            the add-on link list, emptied
     * @param instructionLimit the iscript execution bound
     * @throws NullPointerException if the world was built without game data
     */
    private static void linkAddons(@NotNull Game game, @NotNull LinkedList<SerialLink> links, int instructionLimit) {
        ScrUnitTypeView unitTypes = game.requireGameData().getUnitTypes();

        while (!links.isEmpty()) {
            SerialLink node = links.removeFirst();

            for (Iterator<SerialLink> iterator = links.iterator(); iterator.hasNext(); ) {
                SerialLink partner = iterator.next();

                if (partner.getLinkedSerial() != node.getSerial()) {
                    continue;
                }

                boolean nodeIsAddon = (unitTypes.getFlags(node.getUnit().getUnitId()) & UnitTypeFlags.ADDON) != 0;
                Unit parent = nodeIsAddon ? partner.getUnit() : node.getUnit();
                Unit addon = nodeIsAddon ? node.getUnit() : partner.getUnit();

                if (UnitAddons.canAttach(parent.getUnitId(), addon.getUnitId())) {
                    game.getUnitAddons().connect(parent, addon, instructionLimit);
                }

                iterator.remove();

                break;
            }
        }
    }

    private MapUnitLoader() {
    }
}
