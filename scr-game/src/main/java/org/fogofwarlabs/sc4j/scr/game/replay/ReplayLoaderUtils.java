package org.fogofwarlabs.sc4j.scr.game.replay;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.table.PlayerProgressTable;
import org.fogofwarlabs.sc4j.scr.core.GameSettings;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkSection;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayContainerFormat;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayHeader;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplaySettings;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.ai.UnitStrengthTable;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepField;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrContentExtensionUtils;
import org.fogofwarlabs.sc4j.scr.game.map.MapSettingsLoaderUtils;
import org.fogofwarlabs.sc4j.scr.game.map.ScrMapModelUtils;
import org.fogofwarlabs.sc4j.scr.game.map.StartingUnitLoader;
import org.fogofwarlabs.sc4j.scr.game.path.LocalPathSearch;
import org.fogofwarlabs.sc4j.scr.game.path.PathPool;
import org.fogofwarlabs.sc4j.scr.game.path.UnitPath;
import org.fogofwarlabs.sc4j.scr.game.player.*;
import org.fogofwarlabs.sc4j.scr.game.session.setup.GameTemplate;
import org.fogofwarlabs.sc4j.scr.game.state.*;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.GameStructureView;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitCreationContext;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitRegistry;
import org.fogofwarlabs.sc4j.scr.game.visibility.LocalVision;
import org.fogofwarlabs.sc4j.scr.game.visibility.MapVisibility;
import org.fogofwarlabs.sc4j.scr.game.visibility.SightCoverage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Translates replay headers and embedded maps into inspection state or initialized game worlds.
 * Owns replay acceptance and setup ordering; common map object creation remains in {@link GameLoader}.
 * Construction does not complete the subsequent startup phases or publish world readiness.
 */
public final class ReplayLoaderUtils {
    /**
     * The FORC section: eight player forces, four name string WORDs and four flag BYTEs.
     */
    private static final int FORCE_SECTION_SIZE = 20;
    /**
     * The four force flag BYTEs of a FORC section.
     */
    private static final int FORCE_FLAGS = 16;
    private static final int INITIAL_MAP_OBJECT_COORDINATE = 0xFFFF;
    private static final int LAST_ENGINE_BYTE = 0xFF;
    private static final int NON_EXPANSION_VERSION_MASK = 7;
    private static final int LAST_NON_EXPANSION_VERSION_BITS = 4;
    /**
     * The resource setting that starts every player with the header's starting minerals.
     */
    private static final int FIXED_RESOURCES = 1;

    /**
     * Creates a {@link Game} instance with the terrain and player setup for inspection, without constructing game objects.
     *
     * @param replay   the replay
     * @param gameData the game data tables
     * @return the created {@link Game} instance
     * @throws IOException if the replay has no valid embedded map data
     */
    public static @NotNull Game createReplayInspectionState(@NotNull ReplayFile replay, @NotNull GameData gameData) throws IOException {
        byte[] mapData = replay.getMapData();

        if (mapData == null || mapData.length == 0) {
            throw Exceptions.newIOException("The replay does not contain map data.");
        }

        return createReplayInspectionState(replay, ChkFile.parseTolerant(mapData), gameData);
    }

    /**
     * Creates a {@link Game} instance with the terrain and player setup, without constructing units or enabling frame
     * execution. The inspection shows every player's vision ({@link LocalVision#startReplayOrObserver}).
     *
     * @param replay   the replay
     * @param mapFile  the parsed map file
     * @param gameData the game data tables
     * @return the created {@link Game} instance
     * @throws IOException if the terrain files cannot be loaded
     */
    public static @NotNull Game createReplayInspectionState(@NotNull ReplayFile replay,
                                                            @NotNull ChkFile mapFile,
                                                            @NotNull GameData gameData) throws IOException {
        TerrainTileset terrain = gameData.getTerrainTileset(mapFile.getTileset());
        GameMap map = createMap(mapFile, terrain);
        GameSetup.Builder setup = createSetup(replay, mapFile, gameData, map, new LcgRandom((int) replay.getHeader().getRandomSeed()));
        Game game = Game.create(setup.build());

        initializePlayerColors(game, replay, mapFile);
        loadPlayerUpgrades(game, replay.getHeader(), mapFile, false);
        game.getLocalVision().startReplayOrObserver();
        game.getRandom().setEnabled(0);

        return game;
    }

    /**
     * Creates normal-game units using explicit initialization inputs established by the caller.
     * <p>
     * The random generator is the retained phase-entry state, including its call history. This method does not
     * infer it from the replay header. Startup frame phases and subscriber initialization remain separate.
     *
     * @param context          the established constructor and completion inputs
     * @param replay           the accepted expansion replay
     * @param mapFile          the ordered embedded map sections
     * @param gameData         the installed tables and assets
     * @param random           the enabled game random state at map initialization
     * @param runtimeRandom    the C runtime generator in its established state before map construction, which the game
     *                         shares
     * @param sharedVision     the twelve established shared-vision masks
     * @param comparisonMask   the local comparison mask while the map objects are created; the replay's vision starts after
     *                         them ({@link LocalVision#startReplayOrObserver})
     * @param viewportWidth    the viewport width in pixels
     * @param viewportHeight   the viewport height in pixels
     * @param instructionLimit the checked script execution bound
     * @return the created {@link Game} instance with assigned identities and retained creation outcomes
     * @throws IOException if the installed terrain or foliage cannot be read
     */
    public static @NotNull Game createInitializedReplayWorld(@NotNull UnitCreationContext context,
                                                             @NotNull ReplayFile replay, @NotNull ChkFile mapFile,
                                                             @NotNull GameData gameData,
                                                             @NotNull LcgRandom random,
                                                             @NotNull RuntimeRandom runtimeRandom,
                                                             int @NotNull [] sharedVision, int comparisonMask,
                                                             int viewportWidth, int viewportHeight,
                                                             int instructionLimit) throws IOException {
        return createInitializedWorld(context,
                                      replay, mapFile,
                                      gameData,
                                      random,
                                      runtimeRandom,
                                      sharedVision, comparisonMask,
                                      true, true,
                                      null,
                                      viewportWidth, viewportHeight,
                                      instructionLimit);
    }

    /**
     * Creates a {@link Game} instance with the map storage for a fresh offline replay session, using the supplied
     * initialization state. The returned world still requires the ordered startup phases and interface subscriber
     * initialization.
     *
     * @param initialization   the fresh session input producers
     * @param replay           the accepted normal expansion replay
     * @param mapFile          the embedded map with retained section order
     * @param gameData         the installed game resources
     * @param viewportWidth    the caller's viewport width
     * @param viewportHeight   the caller's viewport height
     * @param instructionLimit the script and movement execution bound
     * @return the created {@link Game} instance, before startup phase completion
     * @throws IOException                   if a required installed resource cannot be read
     * @throws UnsupportedOperationException if the replay's game mode is not represented
     */
    public static @NotNull Game createInitializedReplayWorld(@NotNull ReplayInitialization initialization,
                                                             @NotNull ReplayFile replay, @NotNull ChkFile mapFile,
                                                             @NotNull GameData gameData,
                                                             int viewportWidth, int viewportHeight,
                                                             int instructionLimit) throws IOException {
        ReplayHeader header = replay.getHeader();
        ReplaySettings settings = header.getSettings();

        if (!ReplayInitialization.isSupportedGameMode(header)) {
            throw Exceptions.newUnsupportedOperationException("Fresh replay setup requires a represented expansion game mode.");
        }

        int[] playerTypes = new int[PlayerTable.PLAYER_SLOTS];
        int[] playerForces = new int[PlayerTable.PLAYER_SLOTS];

        for (int playerIndex = 0; playerIndex < playerTypes.length; playerIndex++) {
            playerTypes[playerIndex]  = header.getPlayers().get(playerIndex).getType();
            playerForces[playerIndex] = header.getPlayers().get(playerIndex).getTeam();
        }

        boolean alliedComputers = PlayerRelations.areAlliancesAllowed(settings.getAlliesEnabled(), settings.getTournamentMode());
        PlayerRelations relations = new PlayerRelations(playerTypes, playerForces,
                                                        alliedComputers,
                                                        settings.usesMapSettings() ? readForceFlags(mapFile) : null,
                                                        ReplayInitialization.getNativeGameType(header) == GameTemplate.TYPE_TOP_VS_BOTTOM);
        LcgRandom random = initialization.getRandom();
        RuntimeRandom runtimeRandom = initialization.getRuntimeRandom();
        UnitCreationContext creationContext = initialization.getCreationContext();

        Game game = createInitializedWorld(creationContext,
                                           replay, mapFile,
                                           gameData,
                                           random,
                                           runtimeRandom,
                                           relations.getSharedVisionMasks(), initialization.getComparisonMask(),
                                           !initialization.isLive(), initialization.isReplayOrObserver(),
                                           initialization.getTeamSlotRaces(),
                                           viewportWidth, viewportHeight,
                                           instructionLimit);

        game.getPlayerTable().setPlayerRelations(relations);
        relations.shareVision(game.requireSightCoverage());
        game.setGameSettings(settings);
        game.getGameContext().setForceSnapshot(readForceSnapshot(mapFile));

        if (initialization.isLive()) {
            game.setRulesVersion(GameRulesVersion.newGame());
        }

        return game;
    }

    /**
     * Finds the leader slot of each force: the first human or computer slot of the force.
     *
     * @param header the game header
     * @return the four leader slots, or {@code null} outside team games
     */
    public static int @Nullable [] getTeamLeaders(@NotNull ReplayHeader header) {
        if (header.getSettings().getTeamsEnabled() == 0) {
            return null;
        }

        int[] owners = {-1, -1, -1, -1};

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            ReplayHeader.ReplayPlayer player = header.getPlayers().get(slot);
            int force = player.getTeam();

            if (force >= 1 && force <= PlayerTable.FORCE_COUNT &&
                (player.getType() == PlayerTypes.HUMAN || player.getType() == PlayerTypes.COMPUTER) &&
                owners[force - 1] < 0) {
                owners[force - 1] = player.getSlotId();
            }
        }

        return owners;
    }

    /**
     * @param header the replay header
     * @return {@code true} if the recorded engine selects expansion rules, {@code false} otherwise
     */
    static boolean isExpansion(@NotNull ReplayHeader header) {
        return header.getEngine() != 0;
    }

    /**
     * Creates map objects with the session replay flag selecting their local visibility rules.
     * The simulated tile state is identical in replay and live modes. The replay flag and the local comparison mask are set
     * before the map objects are created; a replay or observer start starts its vision ({@link LocalVision#startReplayOrObserver})
     * after them.
     *
     * @throws UnsupportedOperationException if the unit spatial index or the unit pool is not initialized
     */
    private static @NotNull Game createInitializedWorld(@NotNull UnitCreationContext context,
                                                        @NotNull ReplayFile replay, @NotNull ChkFile mapFile,
                                                        @NotNull GameData gameData,
                                                        @NotNull LcgRandom random,
                                                        @NotNull RuntimeRandom runtimeRandom,
                                                        int @NotNull [] sharedVision, int comparisonMask,
                                                        boolean replayFlag, boolean replayOrObserver,
                                                        int @Nullable [] teamSlotRaces,
                                                        int viewportWidth, int viewportHeight,
                                                        int instructionLimit) throws IOException {
        ReplayHeader header = replay.getHeader();

        if (!ReplayInitialization.isSupportedGameMode(header)) {
            throw Exceptions.newUnsupportedOperationException("World creation requires a game whose victory condition game start accepts.");
        }

        if (random.getEnabled() == 0) {
            throw Exceptions.newIllegalArgumentException("Native replay construction requires the established enabled random scope.");
        }

        TerrainTileset terrain = gameData.getTerrainTileset(mapFile.getTileset());
        GameMap map = createMap(mapFile, terrain);
        boolean useMapSettings = header.getSettings().usesMapSettings();

        GameSetup setup = createSetup(replay, mapFile, gameData, map, random)
                .setRuntimeRandom(runtimeRandom)
                .setLimits(replay.getLimits().getEffectiveValues())
                .build();
        Game game = Game.create(setup);

        initializePlayerColors(game, replay, mapFile);

        // The game's own DAT memory (EUD writes change it as SC:R's DAT arrays) is its engine content, which starts as the installed tables; the
        // running mod's content extensions write their values into it first, then a map-settings game writes the map's unit, upgrade and
        // technology settings, so the map's own values replace both, before anything reads it.
        ScrContentExtensionUtils.extendGameData(game.requireGameData());

        if (useMapSettings) {
            MapSettingsLoaderUtils.applyScenarioSettings(game.requireGameData(), mapFile);
        }

        game.initializeExecutionScope();
        game.initializeUnitSpatialIndex();
        game.getUnitSystem().requireUnitSpatialIndex().initializeFlyingUnits();

        if (context.isRegistryPresent()) {
            game.getUnitSystem().setUnitRegistry(new UnitRegistry(game.getUnitSystem().requireUnitPool()));
        }

        // A game started from a saved game reads its contours with the pathing part; its terrain may be the one EUD
        // writes left, which the native load never builds contours from.
        game.getPathSystem().setTerrainContours(replay.isSavedGameStart() ? TerrainContours.createWithoutBoundaries(map, terrain)
                                                                          : TerrainContours.create(map, terrain));
        game.getPathSystem().setPathPool(new PathPool());
        game.getPathSystem().setLocalPathSearch(new LocalPathSearch());
        game.getPathSystem().setUnitPath(new UnitPath());

        if (context.isFoliageEnabled()) {
            game.setFoliage(gameData.createFoliage(map.getWidth(), map.getTileset()));
        }

        StartingUnitLoader.TeamStart teamStart = createTeamStart(header, teamSlotRaces);
        UnitAccounting accounting = new UnitAccounting(game.getEngineGame().getContent().getProgress());

        accounting.initializeForGame(teamStart);
        game.getPlayerTable().setUnitAccounting(accounting);

        game.setUnitStrengthTable(new UnitStrengthTable(gameData.getUnitTypes(), gameData.getWeaponTypes(), game.requireGameData()));
        game.getPlayerTable().setProductionStatistics(new ProductionStatistics(false,
                                                                               new int[ProductionStatistics.VALUE_COUNT],
                                                                               new float[ProductionStatistics.VALUE_COUNT]));
        loadPlayerUpgrades(game, header, mapFile, true);

        SightCoverage fog = new SightCoverage(map.getWidth());
        LocalVision vision = game.getLocalVision();

        fog.setSharedVisionMasks(sharedVision);
        game.setSightCoverage(fog);
        game.setReplayPlayback(replayFlag);
        vision.setComparisonMask(comparisonMask);
        game.setCreepField(new CreepField(map, terrain, MapVisibility.createGraphicsCache(map, terrain)));

        GameLoader.initializeMapObjects(context, game,
                                        header.getSettings(),
                                        setup.getSeed(),
                                        mapFile,
                                        isExpansion(header), header.getSettings().getCreateInitialUnits() != 0,
                                        teamStart,
                                        new int[]{INITIAL_MAP_OBJECT_COORDINATE, INITIAL_MAP_OBJECT_COORDINATE},
                                        viewportWidth, viewportHeight,
                                        instructionLimit);

        game.setMapModel(ScrMapModelUtils.create(mapFile, game.getTerrain()));

        if (replayOrObserver) {
            vision.startReplayOrObserver();
        }

        return game;
    }

    /**
     * Supplies the player colors' game start inputs: every {@code COLR} section of a Brood War map, whose map loaders alone read the section, except
     * in a game started from a saved game, whose scenario's section repeats the players' colors; and the game's custom colors.
     */
    private static void initializePlayerColors(@NotNull Game game, @NotNull ReplayFile replay, @NotNull ChkFile mapFile) {
        List<byte[]> mapColors = new ArrayList<>();
        int version = MapSettingsLoaderUtils.readVersion(mapFile);

        if (!replay.isSavedGameStart() && (version == ChkFile.VERSION_BROOD_WAR || version == ChkFile.VERSION_REMASTERED_BROOD_WAR)) {
            for (ChkSection section : mapFile.getSections(ChkFile.COLORS_SECTION)) {
                mapColors.add(section.getPayload());
            }
        }

        game.getPlayerTable().setStartColors(mapColors, replay.getCustomColors());
    }

    /**
     * Creates the terrain of a map file with its borders and overlay tiles initialized.
     */
    private static @NotNull GameMap createMap(@NotNull ChkFile mapFile, @NotNull TerrainTileset terrain) {
        int[] tileIdentifiers = mapFile.getTileIds(terrain.getGroupCount());
        GameMap map = new GameMap(mapFile.getWidth(), mapFile.getHeight(),
                                  mapFile.getTileset(),
                                  tileIdentifiers,
                                  terrain.expandTileFlags(tileIdentifiers));

        map.initializeTerrainBorders();
        map.initializeOverlayTiles();

        return map;
    }

    /**
     * Creates team starting-unit inputs from lobby slot races or replay player records.
     */
    private static StartingUnitLoader.@Nullable TeamStart createTeamStart(@NotNull ReplayHeader header, int @Nullable [] teamSlotRaces) {
        int[] owners = getTeamLeaders(header);

        if (owners == null) {
            return null;
        }

        int[] races = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            races[slot] = teamSlotRaces != null ? teamSlotRaces[slot] : header.getPlayers().get(slot).getRace();
        }

        return new StartingUnitLoader.TeamStart(header.getSettings().getTeamsEnabled(), races, owners);
    }

    /**
     * Establishes default player upgrade and technology storage for accepted map versions.
     * Map-settings sections apply between thingy and unit loading; inspection leaves that storage unavailable.
     *
     * @throws NullPointerException if the world was built without game data
     */
    private static void loadPlayerUpgrades(@NotNull Game game, @NotNull ReplayHeader header, @NotNull ChkFile mapFile, boolean mapSettingsLoaded) {
        if (header.getEngine() < 0 || header.getEngine() > LAST_ENGINE_BYTE || (header.getSettings().usesMapSettings() && !mapSettingsLoaded)) {
            return;
        }

        boolean expansion = isExpansion(header);
        int version = -1;

        for (ChkSection section : mapFile.getSections(ChkFile.VERSION_SECTION)) {
            if (section.getDeclaredSize() != Short.BYTES || !section.hasCompletePayload()) {
                return;
            }

            version = LittleEndianUtils.readUnsigned16(section.getPayload(), 0);
        }

        if (version != ChkFile.VERSION_ORIGINAL && version != ChkFile.VERSION_HYBRID && version != ChkFile.VERSION_REMASTERED_ORIGINAL &&
            version != ChkFile.VERSION_BROOD_WAR && version != ChkFile.VERSION_REMASTERED_BROOD_WAR) {
            return;
        }

        if (!expansion && (version & NON_EXPANSION_VERSION_MASK) > LAST_NON_EXPANSION_VERSION_BITS) {
            return;
        }

        ContextExpansion contextExpansion = new ContextExpansion(expansion);
        // An access outside the upgrade and technology tables uses the game context as the EUD game-data block serves it, from the owners the world
        // has then.
        Supplier<GameContextBytes> context = () -> new GameStructureView(game, game.getTriggerContext());
        PlayerProgressTable progress = game.getEngineGame().getContent().getProgress();
        PlayerUpgrades upgrades = new PlayerUpgrades(progress, contextExpansion, context);

        upgrades.initializeDefaults(game.requireGameData().getUpgradeTypes());
        game.getPlayerTable().setPlayerUpgrades(upgrades);
        game.getPlayerTable().setPlayerTechnology(new PlayerTechnology(progress, contextExpansion, context));
    }

    /**
     * Reads player forces plus one for used slots and the last force flags into the game snapshot.
     */
    private static byte @NotNull [] readForceSnapshot(@NotNull ChkFile mapFile) {
        byte[] forces = new byte[FORCE_SECTION_SIZE];
        byte[] owners = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        byte[] snapshot = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS + PlayerTable.FORCE_COUNT];

        for (ChkSection section : mapFile.getSections(ChkFile.FORCE_SECTION)) {
            forces = Arrays.copyOf(section.getPayload(), FORCE_SECTION_SIZE);
        }

        for (ChkSection section : mapFile.getSections(ChkFile.OWNER_SECTION)) {
            owners = Arrays.copyOf(section.getPayload(), PlayerTable.PLAYABLE_PLAYER_SLOTS);
        }

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            snapshot[player] = owners[player] == 0 ? 0 : (byte) (forces[player] + 1);
        }

        System.arraycopy(forces, FORCE_FLAGS, snapshot, PlayerTable.PLAYABLE_PLAYER_SLOTS, PlayerTable.FORCE_COUNT);

        return snapshot;
    }

    /**
     * Reads the four force flags of the last FORC section; an empty payload clears them.
     */
    private static int @NotNull [] readForceFlags(@NotNull ChkFile mapFile) {
        int[] flags = new int[PlayerTable.FORCE_COUNT];

        for (ChkSection section : mapFile.getSections(ChkFile.FORCE_SECTION)) {
            byte[] payload = section.getPayload();

            if (payload.length == 0) {
                Arrays.fill(flags, 0);

                continue;
            }

            if (payload.length < FORCE_SECTION_SIZE) {
                throw Exceptions.newUnsupportedOperationException("A partial FORC section is not represented: {0}", payload.length);
            }

            for (int force = 0; force < PlayerTable.FORCE_COUNT; force++) {
                flags[force] = Byte.toUnsignedInt(payload[FORCE_FLAGS + force]);
            }
        }

        return flags;
    }

    /**
     * Creates the setup of a replay's game: the map's scenario, the archive of a live game's map file, the replay, the header seed and options,
     * the rules version of the replay format, the cheat option and the players of the header.
     */
    private static GameSetup.@NotNull Builder createSetup(@NotNull ReplayFile replay, @NotNull ChkFile mapFile,
                                                          @NotNull GameData gameData,
                                                          @NotNull GameMap map,
                                                          @NotNull LcgRandom random) {
        ReplayHeader header = replay.getHeader();

        GameSetup.Builder setup = new GameSetup.Builder(gameData, map, random)
                .setScenario(mapFile)
                .setMapArchive(replay.getMapArchive())
                .setReplay(replay)
                .setSeed((int) header.getRandomSeed())
                .setSettings(header.getSettings())
                .setRulesVersion(GameRulesVersion.forReplay(replay.getGameVersion(), replay.getFormat() != ReplayContainerFormat.MODERN_121))
                .setCheatsEnabled(header.getSettings().getCheatsEnabled());

        addPlayers(setup, header, header.getSettings());

        return setup;
    }

    /**
     * Adds the header's players to the setup, with the fixed starting minerals and the recorded colors.
     */
    private static void addPlayers(GameSetup.@NotNull Builder setup, @NotNull ReplayHeader header, @NotNull GameSettings settings) {
        List<ReplayHeader.ReplayPlayer> replayPlayers = header.getPlayers();
        int[] colors = header.getColors();

        for (int playerSlot = 0; playerSlot < replayPlayers.size(); playerSlot++) {
            ReplayHeader.ReplayPlayer replayPlayer = replayPlayers.get(playerSlot);
            Player player = new Player(replayPlayer.getSlotId(),
                                       replayPlayer.getId(),
                                       replayPlayer.getType(),
                                       replayPlayer.getRace(),
                                       replayPlayer.getTeam());

            if (settings.getResourceType() == FIXED_RESOURCES) {
                player.setMinerals((int) settings.getStartingMinerals());
                player.setGatheredMinerals((int) settings.getStartingMinerals());
            }

            player.setColor(playerSlot < colors.length ? colors[playerSlot] : playerSlot);
            setup.setPlayer(playerSlot, player);
        }
    }


    private ReplayLoaderUtils() {
    }
}
