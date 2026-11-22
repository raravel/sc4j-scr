package org.fogofwarlabs.sc4j.scr.game.state;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.collision.CollisionSystem;
import org.fogofwarlabs.sc4j.engine.combat.CombatSystem;
import org.fogofwarlabs.sc4j.engine.computer.ComputerPlayerSystem;
import org.fogofwarlabs.sc4j.engine.content.modifier.Stat;
import org.fogofwarlabs.sc4j.engine.content.requirement.RequirementSystem;
import org.fogofwarlabs.sc4j.engine.content.table.GameContent;
import org.fogofwarlabs.sc4j.engine.content.type.ContentType;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.economy.EconomySystem;
import org.fogofwarlabs.sc4j.engine.entity.EntityKind;
import org.fogofwarlabs.sc4j.engine.game.EngineLimits;
import org.fogofwarlabs.sc4j.engine.game.GameDefinition;
import org.fogofwarlabs.sc4j.engine.game.frame.FrameClock;
import org.fogofwarlabs.sc4j.engine.game.system.GameSystem;
import org.fogofwarlabs.sc4j.engine.game.system.SystemKey;
import org.fogofwarlabs.sc4j.engine.map.MapModel;
import org.fogofwarlabs.sc4j.engine.movement.MovementSystem;
import org.fogofwarlabs.sc4j.engine.order.OrderSystem;
import org.fogofwarlabs.sc4j.engine.path.PathfindingSystem;
import org.fogofwarlabs.sc4j.engine.production.ProductionSystem;
import org.fogofwarlabs.sc4j.engine.spatial.SearchCounter;
import org.fogofwarlabs.sc4j.engine.spatial.SpatialIndex;
import org.fogofwarlabs.sc4j.engine.state.StateHash;
import org.fogofwarlabs.sc4j.engine.state.StateHashContributor;
import org.fogofwarlabs.sc4j.engine.trigger.TriggerSystem;
import org.fogofwarlabs.sc4j.engine.visibility.VisibilitySystem;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedUnitTypes;
import org.fogofwarlabs.sc4j.scr.content.ScrStats;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.load.ScrContentImporter;
import org.fogofwarlabs.sc4j.scr.core.GameSettings;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.game.ability.*;
import org.fogofwarlabs.sc4j.scr.game.ai.*;
import org.fogofwarlabs.sc4j.scr.game.combat.*;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepField;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepFramePhase;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepRecession;
import org.fogofwarlabs.sc4j.scr.game.extension.*;
import org.fogofwarlabs.sc4j.scr.game.frame.*;
import org.fogofwarlabs.sc4j.scr.game.map.*;
import org.fogofwarlabs.sc4j.scr.game.modifier.*;
import org.fogofwarlabs.sc4j.scr.game.movement.*;
import org.fogofwarlabs.sc4j.scr.game.order.*;
import org.fogofwarlabs.sc4j.scr.game.path.*;
import org.fogofwarlabs.sc4j.scr.game.player.*;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceLink;
import org.fogofwarlabs.sc4j.scr.game.presentation.StatTextOverrides;
import org.fogofwarlabs.sc4j.scr.game.presentation.UnitSounds;
import org.fogofwarlabs.sc4j.scr.game.production.*;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayFrameRunner;
import org.fogofwarlabs.sc4j.scr.game.resource.ResourceAreas;
import org.fogofwarlabs.sc4j.scr.game.resource.UnitHarvesting;
import org.fogofwarlabs.sc4j.scr.game.resource.UnitResourceSearch;
import org.fogofwarlabs.sc4j.scr.game.session.LocalInterfaceEffects;
import org.fogofwarlabs.sc4j.scr.game.session.setup.PlayerSlots;
import org.fogofwarlabs.sc4j.scr.game.sprite.*;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.terrain.Foliage;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.scr.game.trigger.*;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemorySource;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.ScrSpatialIndex;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.fogofwarlabs.sc4j.scr.game.visibility.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The game: the SC:R game system of an engine game ({@link #SYSTEM_KEY}), created from an explicit {@link GameSetup}
 * ({@link #create(GameSetup)}), it owns the game's systems, one instance each. Data-owning
 * systems hold the state ({@link PlayerTable}, {@link UnitSystem}, {@link SpriteSystem}, {@link PathSystem},
 * {@link GameContext}, {@link InterfaceLink} and the owners installed by world initialization); behavior systems
 * ({@link UnitAttack}, {@link IscriptExecutor}, ...) hold the rules and reach each other through this object.
 * <p>
 * Pool construction and world readiness are separate lifecycle steps; optional execution contexts remain absent until
 * their inputs have been established. The state hash covers the represented game fields. It is a diagnostic hash, not
 * the original multiplayer checksum.
 * <p>
 * The engine game is the composition root: it holds the random generator (this setup's {@link LcgRandom}) and the frame
 * clock that holds the frame counter. The SC:R mod pools its units, sprites and images itself, so the engine pools of
 * those kinds stay empty. The engine state hash covers this game through {@link #contributeStateHash}.
 */
public final class Game implements GameSystem, StateHashContributor {
    /**
     * The initial value of the state hash (the {@code 64}-bit FNV offset basis).
     */
    public static final long STATE_HASH_OFFSET_BASIS = 0xcbf29ce484222325L;
    /**
     * The multiplier of the state hash mix (the {@code 64}-bit FNV prime).
     */
    public static final long STATE_HASH_PRIME = 0x100000001b3L;
    /**
     * The right shift folded into the state hash after each mixed value.
     */
    public static final int STATE_HASH_FOLD_SHIFT = 29;

    /**
     * The key of the SC:R game system.
     */
    public static final @NotNull SystemKey<Game> SYSTEM_KEY = SystemKey.of("scr.game", Game.class);

    /**
     * The mask of the unsigned DWORD bits of a value.
     */
    private static final long DWORD_MASK = 0xFFFFFFFFL;
    /**
     * The cheat option byte that permits cheats.
     */
    private static final int CHEATS_PERMITTED = 1;
    /**
     * The one-based {@code stat_txt} identifier of the name of unit type {@code 0}; the names of the other types follow.
     */
    private static final int FIRST_UNIT_NAME_STRING = 1;

    /**
     * The definition of SC:R games without game data, as {@link #register} sets it up; games with game data use the
     * definition of their installed data, which also holds the content ({@link GameData#getGameDefinition()}).
     */
    private static final @NotNull GameDefinition DEFINITION = createDefinition();

    /**
     * Registers the SC:R simulation on a game definition: the SC:R stats after the engine stats ({@link ScrStats}), the SC:R random generator
     * ({@link LcgRandom#FACTORY}), the SC:R pools of units, sprites and images, the SC:R game system, which a setup's
     * {@link GameSetup#OPTION} creates, the SC:R order system ({@link ScrOrderSystem}, the game's {@link OrderSystem}), the SC:R requirement
     * system ({@link ScrRequirementSystem}, the game's {@link RequirementSystem}), the views of the SC:R systems under the engine system keys
     * (economy, spatial index, visibility, collision, path finding, movement, combat and production, which hold no state), the SC:R trigger
     * system ({@link ScrTriggerSystem}, the game's {@link TriggerSystem}), the SC:R computer player system ({@link ScrComputerPlayerSystem},
     * the game's {@link ComputerPlayerSystem}), the SC:R hooks ({@link ScrHooks#register}) and the frame phases in native order: the steps of
     * its {@link FrameSequence} with the trigger system's phase between the foliage and the display clock steps.
     *
     * @param definition the open definition builder
     * @return the definition builder
     * @throws IllegalStateException if the definition is built
     */
    public static GameDefinition.@NotNull Builder register(GameDefinition.@NotNull Builder definition) {
        for (Stat stat : ScrStats.getTypes()) {
            definition.getContent().getStats().register(stat);
        }

        return ScrHooks.register(definition).setRandomFactory(LcgRandom.FACTORY)
                       .useModPool(EntityKind.UNIT)
                       .useModPool(EntityKind.SPRITE)
                       .useModPool(EntityKind.IMAGE)
                       .useModPool(EntityKind.PROJECTILE)
                       .useSystem(SYSTEM_KEY, context -> new Game(context.getSetup().getOptions().get(GameSetup.OPTION),
                                                                  context.getSetup().getLimits()))
                       .useSystem(OrderSystem.KEY, context -> new ScrOrderSystem())
                       .useSystem(RequirementSystem.KEY, context -> new ScrRequirementSystem())
                       .useSystem(EconomySystem.KEY, context -> new ScrEconomySystem())
                       .useSystem(SpatialIndex.KEY, context -> new ScrSpatialIndex())
                       .useSystem(VisibilitySystem.KEY, context -> new ScrVisibilitySystem())
                       .useSystem(CollisionSystem.KEY, context -> new ScrCollisionSystem())
                       .useSystem(PathfindingSystem.KEY, context -> new ScrPathfindingSystem())
                       .useSystem(MovementSystem.KEY, context -> new ScrMovementSystem())
                       .useSystem(CombatSystem.KEY, context -> new ScrCombatSystem())
                       .useSystem(ProductionSystem.KEY, context -> new ScrProductionSystem())
                       .useSystem(TriggerSystem.KEY, context -> new ScrTriggerSystem())
                       .useSystem(ComputerPlayerSystem.KEY, context -> new ScrComputerPlayerSystem())
                       .addFramePhase(SYSTEM_KEY, game -> game.frameSequence.getPhase(FrameSequence.Step.FRAME_START))
                       .addFramePhase(SYSTEM_KEY, game -> game.frameSequence.getPhase(FrameSequence.Step.PRODUCTION_STATISTICS))
                       .addFramePhase(SYSTEM_KEY, game -> game.frameSequence.getPhase(FrameSequence.Step.WORLD))
                       .addFramePhase(SYSTEM_KEY, game -> game.frameSequence.getPhase(FrameSequence.Step.FOLIAGE))
                       .addFramePhase(TriggerSystem.KEY)
                       .addFramePhase(SYSTEM_KEY, game -> game.frameSequence.getPhase(FrameSequence.Step.DISPLAY_CLOCK));
    }

    /**
     * Creates a {@link Game} instance from a minimal setup with only the data, the map and the random generator
     * ({@link GameSetup#of}), as tests and previews do.
     *
     * @param gameData the game data tables, or {@code null} for a world built without game data
     * @param map      the map
     * @param random   the random generator
     * @return the created {@link Game} instance
     */
    public static @NotNull Game create(@Nullable GameData gameData, @NotNull GameMap map, @NotNull LcgRandom random) {
        return create(GameSetup.of(gameData, map, random));
    }

    /**
     * Creates a {@link Game} instance from an explicit setup: the engine game of the definition of the setup's game data
     * ({@link GameData#getGameDefinition()}), or of the SC:R definition without content for a setup without game data,
     * and the setup's engine setup ({@link GameSetup#createEngineSetup()}), whose SC:R game system this is.
     *
     * @param setup the creation inputs
     * @return the created {@link Game} instance
     */
    public static @NotNull Game create(@NotNull GameSetup setup) {
        GameData gameData = setup.getGameData();
        GameDefinition definition = gameData != null ? gameData.getGameDefinition() : DEFINITION;

        return org.fogofwarlabs.sc4j.engine.game.Game.create(definition, setup.createEngineSetup()).getModSystem(SYSTEM_KEY);
    }

    /**
     * Creates the definition of the games without game data: the SC:R simulation ({@link #register}) without content, with the
     * {@link PlayerTable#PLAYER_SLOTS} player slots of the SC:R rules, so command issuers resolve to engine players as in games of installed data.
     *
     * @return the created {@link GameDefinition} instance
     */
    private static @NotNull GameDefinition createDefinition() {
        GameDefinition.Builder definition = register(new GameDefinition.Builder());

        definition.getContent().setPlayerCount(PlayerTable.PLAYER_SLOTS);

        return definition.build();
    }

    /**
     * Creates the definition of the games of installed data: the SC:R simulation ({@link #register}), the SC:R content
     * imported from the installed DAT tables, animation library, added unit types and added graphics ({@link ScrContentImporter}) and the SC:R
     * per-player columns ({@link PlayerProgressColumns}).
     *
     * @param installed the installed game data
     * @return the created {@link GameDefinition} instance
     */
    static @NotNull GameDefinition createDefinition(@NotNull GameData installed) {
        GameDefinition.Builder definition = register(new GameDefinition.Builder());

        new ScrContentImporter(installed.getUnits(), installed.getWeapons(), installed.getFlingy(), installed.getSprites(), installed.getImages(),
                               installed.getOrders(), installed.getUpgrades(), installed.getTechdata(), installed.getSfxdata(),
                               installed.getIscriptLibrary(), installed.getAddedUnitTypes(), installed.getAddedGraphics())
                .register(definition.getContent());
        PlayerProgressColumns.register(definition.getContent());

        return definition.build();
    }

    private static long mix(long hash, long value) {
        hash ^= value & DWORD_MASK;
        hash *= STATE_HASH_PRIME;
        hash ^= hash >>> STATE_HASH_FOLD_SHIFT;

        return hash;
    }

    private static long mixLong(long hash, long value) {
        hash = mix(hash, value);

        return mix(hash, value >>> Integer.SIZE);
    }

    /* Setup and options. */
    private final @NotNull GameSetup setup;
    /**
     * The setup's game data until the game attaches, then the game's own copy over the engine content.
     */
    private @Nullable GameData gameData;
    private final @NotNull GameMap map;
    private final @NotNull ScrTerrainGrid terrain;
    private final @NotNull MapTileChanges mapTileChanges;
    private @Nullable MapModel mapModel;
    private final @NotNull LcgRandom random;
    private final @NotNull RuntimeRandom runtimeRandom;
    private @NotNull GameRulesVersion rulesVersion;
    private @Nullable GameSettings gameSettings;
    private boolean cheatsEnabled;
    private boolean replayPlayback;
    private @Nullable PlayerSlots playerSlots;

    /* The engine game, attached after creation, its frame clock, which holds the frame counter, the hook points the SC:R systems call,
       the SC:R modifier of each stat, the plugins and memory sources of the mod's game extensions, the plugins of its simulation
       extensions and the mod sections of the replay written of this game. */
    private org.fogofwarlabs.sc4j.engine.game.@Nullable Game engineGame;
    private @Nullable FrameClock clock;
    private @Nullable ScrHooks hooks;
    private @Nullable ScrStatModifier @NotNull [] statModifiers;
    private @NotNull ScrGamePlugin @NotNull [] plugins;
    private @NotNull ClassicMemorySource @NotNull [] memorySources;
    private @Nullable ScrSimulationPlugins simulationPlugins;
    private final @NotNull Map<String, byte @NotNull []> replaySections;

    /* Frame counters and world lifecycle. */
    private int turnCount;
    private int commandDispatchMarkerCount;
    private int requestedGameSpeed;
    private int updateTilesCountdown;
    private @Nullable Integer buildingPowerRefresh;
    private boolean worldInitialized;
    /**
     * The end a command gave the game, or {@code null} while the game runs.
     */
    private @Nullable GameEnd end;

    /* Owners installed by world initialization. */
    private @Nullable GameExecutionScope executionScope;
    private @Nullable GameFrame frame;
    private @Nullable ReplayFrameRunner replayFrameRunner;
    private @Nullable UnitSelection selection;
    private @Nullable OrderPool orderPool;
    private @Nullable SightCoverage sightCoverage;
    private @Nullable CreepField creepField;
    private @Nullable CreepRecession creepRecession;
    private @Nullable Foliage foliage;
    private @Nullable ResourceAreas resourceAreas;
    private @Nullable UnitStrengthTable unitStrengthTable;
    private @Nullable ComputerPlayerPhase computerPlayerPhase;
    private @Nullable TriggerContext triggerContext;

    /* Rule state created with the game. */
    private final @NotNull UnitPsi psi;
    private final @NotNull CostTests costTests;

    /* Retained map creation outcomes. */
    private @NotNull List<MapSpriteEffectLoader.Creation> mapSpriteEffectCreations;
    private @NotNull List<MapUnitLoader.Creation> mapUnitCreations;
    private @NotNull List<StartingUnitLoader.Creation> startingUnitCreations;

    /* Data-owning systems. */
    private final @NotNull PlayerTable playerTable;
    private final @NotNull UnitSystem unitSystem;
    private final @NotNull SpriteSystem spriteSystem;
    private final @NotNull PathSystem pathSystem;
    private final @NotNull GameContext gameContext;
    private final @NotNull InterfaceLink interfaceLink;

    /* Extension points: the frame phases and the orders. */
    private final @NotNull FramePhaseList framePhases;
    private final @NotNull FrameSequence frameSequence;
    private final @NotNull ScrOrderBehaviors orderBehaviors;

    /* Systems: the behavior of the game, one instance each, created with the game. */
    private final @NotNull UnitPhase unitPhase;

    private final @NotNull UnitBuildingMove unitBuildingMove;
    private final @NotNull UnitCompletion unitCompletion;
    private final @NotNull UnitEnergy unitEnergy;
    private final @NotNull UnitHealth unitHealth;
    private final @NotNull UnitInitializer unitInitializer;
    private final @NotNull UnitLifecycle unitLifecycle;
    private final @NotNull UnitOwnership unitOwnership;
    private final @NotNull UnitQueries unitQueries;
    private final @NotNull UnitRelocation unitRelocation;
    private final @NotNull UnitStatusEffects unitStatusEffects;
    private final @NotNull UnitStorageConstructor unitStorageConstructor;
    private final @NotNull UnitTimers unitTimers;

    private final @NotNull IscriptExecutor iscriptExecutor;
    private final @NotNull SelectionImages selectionImages;

    private final @NotNull MovementCollision movementCollision;
    private final @NotNull UnitFollowMovement unitFollowMovement;
    private final @NotNull UnitMovement unitMovement;
    private final @NotNull UnitMovementCollision unitMovementCollision;
    private final @NotNull UnitPointMovement unitPointMovement;
    private final @NotNull UnitSliding unitSliding;
    private final @NotNull UnitSpeed unitSpeed;
    private final @NotNull UnitTargetMovement unitTargetMovement;

    private final @NotNull SpriteVisibility spriteVisibility;
    private final @NotNull UnitBurrowing unitBurrowing;
    private final @NotNull UnitCloaking unitCloaking;
    private final @NotNull UnitVisibility unitVisibility;
    private final @NotNull LocalVision localVision;

    private final @NotNull CritterOrders critterOrders;
    private final @NotNull DoodadOrders doodadOrders;
    private @Nullable ScrOrderSystem orders;
    private final @NotNull UnitCommands unitCommands;
    private final @NotNull UnitOrderEligibility unitOrderEligibility;
    private final @NotNull UnitOrders unitOrders;
    private final @NotNull UnitRescue unitRescue;

    private final @NotNull ProjectilePhase projectilePhase;
    private final @NotNull UnitAttack unitAttack;
    private final @NotNull UnitDamage unitDamage;
    private final @NotNull UnitTargeting unitTargeting;
    private final @NotNull UnitTurret unitTurret;

    private final @NotNull PlayerDeparture playerDeparture;

    private final @NotNull LarvaSpawner larvaSpawner;
    private final @NotNull ProductionQueueCommands productionQueueCommands;
    private @Nullable ScrRequirementSystem requirements;
    private final @NotNull SupplyCheck supplyCheck;
    private final @NotNull UnitAddons unitAddons;
    private final @NotNull UnitBuildTime unitBuildTime;
    private final @NotNull UnitConstruction unitConstruction;
    private final @NotNull UnitMorphing unitMorphing;
    private final @NotNull UnitPlacement unitPlacement;
    private final @NotNull UnitPositioning unitPositioning;
    private final @NotNull UnitProduction unitProduction;
    private final @NotNull UnitRally unitRally;
    private final @NotNull UnitResearch unitResearch;
    private final @NotNull UnitTechnology unitTechnology;
    private final @NotNull UnitTerranConstruction unitTerranConstruction;
    private final @NotNull UnitTraining unitTraining;
    private final @NotNull UnitWarping unitWarping;

    private final @NotNull ArchonOrders archonOrders;
    private final @NotNull DisruptionWeb disruptionWeb;
    private final @NotNull UnitBuildingFlight unitBuildingFlight;
    private final @NotNull UnitHallucination unitHallucination;
    private final @NotNull UnitHangar unitHangar;
    private final @NotNull UnitInfestation unitInfestation;
    private final @NotNull UnitMedic unitMedic;
    private final @NotNull UnitNuclear unitNuclear;
    private final @NotNull UnitNydus unitNydus;
    private final @NotNull UnitPowerups unitPowerups;
    private final @NotNull UnitShieldBattery unitShieldBattery;
    private final @NotNull UnitSpells unitSpells;
    private final @NotNull UnitTransport unitTransport;

    private final @NotNull UnitHarvesting unitHarvesting;
    private final @NotNull UnitResourceSearch unitResourceSearch;

    private final @NotNull ComputerTerrain computerTerrain;
    private final @NotNull ComputerResources computerResources;
    private final @NotNull ComputerPlacement computerPlacement;
    private final @NotNull ComputerMilitary computerMilitary;
    private final @NotNull ComputerAttack computerAttack;
    private final @NotNull ComputerCombat computerCombat;
    private final @NotNull ComputerDefense computerDefense;
    private final @NotNull ComputerOrders computerOrders;
    private final @NotNull ComputerRequirements computerRequirements;
    private final @NotNull ComputerTargets computerTargets;
    private final @NotNull ComputerTechnology computerTechnology;
    private final @NotNull ComputerTowns computerTowns;
    private final @NotNull ComputerUnits computerUnits;
    private @Nullable ScrComputerPlayerSystem computerPlayers;

    private final @NotNull CaptureTheFlag captureTheFlag;
    private final @NotNull TriggerConditions triggerConditions;
    private final @NotNull TriggerLocations triggerLocations;
    private @Nullable ScrTriggerSystem triggers;
    private final @NotNull TriggerUnitActions triggerUnitActions;

    private final @NotNull GameCheats gameCheats;

    private final @NotNull UnitSounds unitSounds;

    private final @NotNull LocalInterfaceEffects localInterfaceEffects;

    /**
     * Creates a {@link Game} instance from an explicit setup, as the SC:R game system of a new engine game.
     * <p>
     * The setup's rules version, cheat option and players are applied, and its limits create the image, sprite,
     * sprite-effect, unit and order pools: the engine limits size the unit, sprite, image and order pools and the native
     * limits the sprite effect pool. Execution contexts and the remaining owners are installed by the ordered world
     * initialization ({@link GameLoader}). The map update countdown starts at one; order and trigger timers belong to
     * their retained execution contexts.
     *
     * @param setup        the creation inputs
     * @param engineLimits the engine pool sizes of the setup's limits, or {@code null} when the setup has no limits
     */
    private Game(@NotNull GameSetup setup, @Nullable EngineLimits engineLimits) {
        GameData gameData = setup.getGameData();
        GameMap map = setup.getMap();
        int[] limits = setup.getLimits();

        this.setup          = setup;
        this.gameData       = gameData;
        this.map            = map;
        this.terrain        = new ScrTerrainGrid(this);
        this.mapTileChanges = new MapTileChanges(this);
        this.mapModel       = null;
        this.random         = setup.getRandom();
        this.runtimeRandom  = setup.getRuntimeRandom();
        this.rulesVersion   = setup.getRulesVersion();
        this.gameSettings   = null;
        this.cheatsEnabled  = setup.getCheatsEnabled() == CHEATS_PERMITTED;
        this.replayPlayback = false;
        this.playerSlots    = null;

        this.engineGame        = null;
        this.clock             = null;
        this.hooks             = null;
        this.statModifiers     = new ScrStatModifier[0];
        this.plugins           = new ScrGamePlugin[0];
        this.memorySources     = new ClassicMemorySource[0];
        this.simulationPlugins = null;
        this.replaySections    = new LinkedHashMap<>();

        this.turnCount                  = 0;
        this.commandDispatchMarkerCount = 0;
        this.requestedGameSpeed         = -1;
        this.updateTilesCountdown       = 1;
        this.buildingPowerRefresh       = null;
        this.worldInitialized           = false;
        this.end                        = null;

        this.executionScope      = null;
        this.frame               = null;
        this.replayFrameRunner   = null;
        this.selection           = null;
        this.orderPool           = null;
        this.sightCoverage       = null;
        this.creepField          = null;
        this.creepRecession      = null;
        this.foliage             = null;
        this.resourceAreas       = null;
        this.unitStrengthTable   = null;
        this.computerPlayerPhase = null;
        this.triggerContext      = null;

        this.psi       = new UnitPsi(this);
        this.costTests = new CostTests();

        this.mapSpriteEffectCreations = List.of();
        this.mapUnitCreations         = List.of();
        this.startingUnitCreations    = List.of();

        this.playerTable   = new PlayerTable(gameData);
        this.unitSystem    = new UnitSystem(gameData != null ? gameData.getUnitFinderSearchCounter() : new SearchCounter());
        this.spriteSystem  = new SpriteSystem();
        this.pathSystem    = new PathSystem();
        this.gameContext   = new GameContext(gameData, map);
        this.interfaceLink = new InterfaceLink();

        this.framePhases    = new FramePhaseList(new CreepFramePhase(), ScrFramePhase.COMPUTER_PLAYERS,
                                                 ScrFramePhase.VISIBILITY,
                                                 ScrFramePhase.UNITS, ScrFramePhase.BULLETS, ScrFramePhase.FLINGIES,
                                                 ScrFramePhase.SPRITE_EFFECTS);
        this.frameSequence  = new FrameSequence(this);
        this.orderBehaviors = new NativeOrderTable(this).createBehaviors();

        this.unitPhase = new UnitPhase(this);

        this.unitBuildingMove       = new UnitBuildingMove(this);
        this.unitCompletion         = new UnitCompletion(this);
        this.unitEnergy             = new UnitEnergy(this);
        this.unitHealth             = new UnitHealth(this);
        this.unitInitializer        = new UnitInitializer(this);
        this.unitLifecycle          = new UnitLifecycle(this);
        this.unitOwnership          = new UnitOwnership(this);
        this.unitQueries            = new UnitQueries(this);
        this.unitRelocation         = new UnitRelocation(this);
        this.unitStatusEffects      = new UnitStatusEffects(this);
        this.unitStorageConstructor = new UnitStorageConstructor(this);
        this.unitTimers             = new UnitTimers(this);

        this.iscriptExecutor = new IscriptExecutor(this);
        this.selectionImages = new SelectionImages(this);

        this.movementCollision     = new MovementCollision(this);
        this.unitFollowMovement    = new UnitFollowMovement(this);
        this.unitMovement          = new UnitMovement(this);
        this.unitMovementCollision = new UnitMovementCollision(this);
        this.unitPointMovement     = new UnitPointMovement(this);
        this.unitSliding           = new UnitSliding(this);
        this.unitSpeed             = new UnitSpeed(this);
        this.unitTargetMovement    = new UnitTargetMovement(this);

        this.spriteVisibility = new SpriteVisibility(this);
        this.unitBurrowing    = new UnitBurrowing(this);
        this.unitCloaking     = new UnitCloaking(this);
        this.unitVisibility   = new UnitVisibility(this);
        this.localVision      = new LocalVision(this);

        this.critterOrders        = new CritterOrders(this);
        this.doodadOrders         = new DoodadOrders(this);
        this.orders               = null;
        this.unitCommands         = new UnitCommands(this);
        this.unitOrderEligibility = new UnitOrderEligibility(this);
        this.unitOrders           = new UnitOrders(this);
        this.unitRescue           = new UnitRescue(this);

        this.projectilePhase = new ProjectilePhase(this);
        this.unitAttack      = new UnitAttack(this);
        this.unitDamage      = new UnitDamage(this);
        this.unitTargeting   = new UnitTargeting(this);
        this.unitTurret      = new UnitTurret(this);

        this.playerDeparture = new PlayerDeparture(this);

        this.larvaSpawner            = new LarvaSpawner(this);
        this.productionQueueCommands = new ProductionQueueCommands(this);
        this.requirements            = null;
        this.supplyCheck             = new SupplyCheck(this);
        this.unitAddons              = new UnitAddons(this);
        this.unitBuildTime           = new UnitBuildTime(this);
        this.unitConstruction        = new UnitConstruction(this);
        this.unitMorphing            = new UnitMorphing(this);
        this.unitPlacement           = new UnitPlacement(this);
        this.unitPositioning         = new UnitPositioning(this);
        this.unitProduction          = new UnitProduction(this);
        this.unitRally               = new UnitRally(this);
        this.unitResearch            = new UnitResearch(this);
        this.unitTechnology          = new UnitTechnology(this);
        this.unitTerranConstruction  = new UnitTerranConstruction(this);
        this.unitTraining            = new UnitTraining(this);
        this.unitWarping             = new UnitWarping(this);

        this.archonOrders       = new ArchonOrders(this);
        this.disruptionWeb      = new DisruptionWeb(this);
        this.unitBuildingFlight = new UnitBuildingFlight(this);
        this.unitHallucination  = new UnitHallucination(this);
        this.unitHangar         = new UnitHangar(this);
        this.unitInfestation    = new UnitInfestation(this);
        this.unitMedic          = new UnitMedic(this);
        this.unitNuclear        = new UnitNuclear(this);
        this.unitNydus          = new UnitNydus(this);
        this.unitPowerups       = new UnitPowerups(this);
        this.unitShieldBattery  = new UnitShieldBattery(this);
        this.unitSpells         = new UnitSpells(this);
        this.unitTransport      = new UnitTransport(this);

        this.unitHarvesting     = new UnitHarvesting(this);
        this.unitResourceSearch = new UnitResourceSearch(this);

        this.computerTerrain      = new ComputerTerrain(this);
        this.computerResources    = new ComputerResources(this);
        this.computerPlacement    = new ComputerPlacement(this);
        this.computerMilitary     = new ComputerMilitary(this);
        this.computerAttack       = new ComputerAttack(this);
        this.computerCombat       = new ComputerCombat(this);
        this.computerDefense      = new ComputerDefense(this);
        this.computerOrders       = new ComputerOrders(this);
        this.computerRequirements = new ComputerRequirements(this);
        this.computerTargets      = new ComputerTargets(this);
        this.computerTechnology   = new ComputerTechnology(this);
        this.computerTowns        = new ComputerTowns(this);
        this.computerUnits        = new ComputerUnits(this);
        this.computerPlayers      = null;

        this.captureTheFlag     = new CaptureTheFlag(this);
        this.triggerConditions  = new TriggerConditions(this);
        this.triggerLocations   = new TriggerLocations(this);
        this.triggers           = null;
        this.triggerUnitActions = new TriggerUnitActions(this);

        this.gameCheats = new GameCheats(this);

        this.unitSounds = new UnitSounds(this);

        this.localInterfaceEffects = new LocalInterfaceEffects(this);

        if (limits != null && engineLimits != null) {
            this.gameContext.setLimits(limits);
            this.unitSystem.initializeUnitPool(engineLimits.getUnits());
            this.spriteSystem.initializeSpritePool(engineLimits.getSprites());
            this.spriteSystem.initializeImagePool(engineLimits.getImages());
            this.initializeOrderPool(engineLimits.getOrders());
            this.spriteSystem.initializeSpriteEffectPool(limits[GameSetup.SPRITE_EFFECT_LIMIT]);
        }

        for (int slotIdentifier = 0; slotIdentifier < PlayerTable.PLAYER_SLOTS; slotIdentifier++) {
            Player player = setup.getPlayer(slotIdentifier);

            if (player != null) {
                this.playerTable.setPlayer(slotIdentifier, player);
            }
        }
    }

    /**
     * @return the explicit inputs the game was created from
     */
    public @NotNull GameSetup getSetup() {
        return this.setup;
    }

    /**
     * Returns the engine game whose SC:R game system this is.
     *
     * @return the engine game
     * @throws IllegalStateException if the game system is not attached yet
     */
    public org.fogofwarlabs.sc4j.engine.game.@NotNull Game getEngineGame() {
        org.fogofwarlabs.sc4j.engine.game.Game attached = this.engineGame;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The SC:R game system is not attached to its engine game yet.");
        }

        return attached;
    }

    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Binds the engine game, its frame clock, which holds the frame counter of this game, its order and requirement systems, hands the engine players
     * to the unit system (the engine owners of the native owner BYTEs, {@link UnitSystem#setPlayers}), adds the SC:R upgrade effects
     * to the game's stat modifiers (package {@code org.fogofwarlabs.sc4j.scr.game.modifier}) and the shield damage layer
     * ({@link UnitDamage#getShieldLayer}) to every unit type, and replaces the setup's game
     * data with the game's own copy over the engine content ({@link GameData#createGameCopy(GameContent)}), which starts
     * with the values of the setup's tables. The systems created with this game read the game data only after it
     * attaches. Last it creates the plugins and memory sources of the mod's game extensions ({@link ScrGameExtensionUtils}) and the plugins of
     * its simulation extensions ({@link ScrSimulationExtensionUtils}).
     *
     * @param game the game
     */
    @Override
    public void attach(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game) {
        GameData setupData = this.gameData;

        this.engineGame      = game;
        this.clock           = game.getClock();
        this.hooks           = new ScrHooks(game);
        this.requirements    = ScrRequirementSystem.of(game);
        this.orders          = ScrOrderSystem.of(game);
        this.triggers        = ScrTriggerSystem.of(game);
        this.computerPlayers = ScrComputerPlayerSystem.of(game);

        org.fogofwarlabs.sc4j.engine.player.Player[] players = new org.fogofwarlabs.sc4j.engine.player.Player[game.getPlayerCount()];

        for (int index = 0; index < players.length; index++) {
            players[index] = game.getPlayer(index);
        }

        this.unitSystem.setPlayers(players);

        GameContent content = game.getContent();

        this.statModifiers = new ScrStatModifier[content.getDefinition().getStats().getCount()];

        this.addModifier(content, Stat.ARMOR, new ScrArmorModifier(this));
        this.addModifier(content, ScrStats.SHIELD_ARMOR, new ScrShieldArmorModifier(this));
        this.addModifier(content, Stat.WEAPON_DAMAGE, new ScrWeaponDamageModifier(this));
        this.addModifier(content, Stat.WEAPON_RANGE, new ScrWeaponRangeModifier(this));
        this.addModifier(content, Stat.TARGET_ACQUISITION_RANGE, new ScrTargetAcquisitionRangeModifier(this));
        this.addModifier(content, Stat.SIGHT_RANGE, new ScrSightRangeModifier(this));
        this.addModifier(content, Stat.TOP_SPEED, new ScrSpeedModifier(this));
        this.addModifier(content, Stat.MAXIMUM_ENERGY, new ScrMaximumEnergyModifier(this));
        this.addModifier(content, Stat.WEAPON_COOLDOWN, new ScrWeaponCooldownModifier(this));
        this.addModifier(content, ScrStats.HANGAR_CAPACITY, new ScrHangarCapacityModifier(this));

        for (UnitType type : content.getDefinition().getUnitTypes().getTypes().getTypes()) {
            content.addDamageLayer(type, this.unitDamage.getShieldLayer());
        }

        if (setupData != null) {
            this.gameData = setupData.createGameCopy(game.getContent());
        }

        this.plugins           = ScrGameExtensionUtils.createPlugins(this);
        this.memorySources     = ScrGameExtensionUtils.createMemorySources(this);
        this.simulationPlugins = ScrSimulationExtensionUtils.createPlugins(this);
    }

    private void addModifier(@NotNull GameContent content, @NotNull Stat stat, @NotNull ScrStatModifier modifier) {
        content.addModifier(stat, modifier);

        this.statModifiers[stat.getIndex()] = modifier;
    }

    /**
     * Applies the SC:R modifier of a stat to a value, on the native values ({@link ScrStatModifier#applyNative}): the modifier this game
     * added to its content for the stat when it attached. The SC:R simulation calls this form, because its owners and type entries may lie
     * past the player slots and the DAT tables (values EUD wrote); a stat without an SC:R modifier keeps the value.
     *
     * @param stat            the stat
     * @param player          the player DWORD of the owner
     * @param unitTypeIndex   the units.dat entry of the unit type, or {@link ContentType#NO_INDEX} for a weapon stat without a unit
     * @param weaponTypeIndex the weapons.dat entry of the weapon for a weapon stat, otherwise {@link ContentType#NO_INDEX}
     * @param value           the table value
     * @return the modified value
     */
    public int applyModifiers(@NotNull Stat stat, int player, int unitTypeIndex, int weaponTypeIndex, int value) {
        ScrStatModifier modifier = stat.getIndex() < this.statModifiers.length ? this.statModifiers[stat.getIndex()] : null;

        return modifier != null ? modifier.applyNative(player, unitTypeIndex, weaponTypeIndex, value) : value;
    }

    /**
     * Returns the game data: the game's own DAT tables over its engine content, with the storage, scripts and resources
     * shared with the installed data.
     *
     * @return the game data tables, or {@code null} for a world built without game data
     */
    public @Nullable GameData getGameData() {
        return this.gameData;
    }

    /**
     * Returns the game data tables of a world built with game data.
     *
     * @return the game data tables
     * @throws NullPointerException if the world was built without game data
     */
    public @NotNull GameData requireGameData() {
        GameData gameData = this.gameData;

        if (gameData == null) {
            throw Exceptions.newNullPointerException("The game was built without game data.");
        }

        return gameData;
    }

    /**
     * Returns the native unit type whose per-type rules apply to a type ({@link GameData#getBaseType(int)}): the base type of an added unit
     * type, the type itself for every other id and in a world built without game data, which has no added types. The simulation reads the
     * type of a unit through this for every per-type rule (comparisons with native types, switches, per-type upgrades and orders), so an added
     * unit behaves as a unit of its base type; the values of the unit type table stay the unit's own.
     *
     * @param unitType the unit type
     * @return the native unit type of the per-type rules
     */
    public int getBaseType(int unitType) {
        GameData gameData = this.gameData;

        return gameData != null ? gameData.getBaseType(unitType) : unitType;
    }

    /**
     * Returns the native unit type whose per-type rules apply to a unit: the base type ({@link #getBaseType(int)}) of its type.
     *
     * @param unit the unit
     * @return the native unit type of the per-type rules
     */
    public int getBaseType(@NotNull Unit unit) {
        return this.getBaseType(unit.getUnitId());
    }

    /**
     * @return the map
     */
    public @NotNull GameMap getMap() {
        return this.map;
    }

    /**
     * @return the terrain of the map as the engine terrain grid, over the map's tile flags and the creep field
     */
    public @NotNull ScrTerrainGrid getTerrain() {
        return this.terrain;
    }

    /**
     * @return the tile changes of the map, which the frame's trigger step applies, the same instance for the whole game
     */
    public @NotNull MapTileChanges getMapTileChanges() {
        return this.mapTileChanges;
    }

    /**
     * @return the engine map model of the game's map, or {@code null} for a world not created from a map file
     */
    public @Nullable MapModel getMapModel() {
        return this.mapModel;
    }

    /**
     * Sets the engine map model of the game's map.
     *
     * @param mapModel the map model
     */
    public void setMapModel(@NotNull MapModel mapModel) {
        this.mapModel = mapModel;
    }

    /**
     * @return the random generator
     */
    public @NotNull LcgRandom getRandom() {
        return this.random;
    }

    /**
     * @return the C runtime generator that the creep tile variants, the player-list orders, the cheat input and the unit
     * responses share
     */
    public @NotNull RuntimeRandom getRuntimeRandom() {
        return this.runtimeRandom;
    }

    /**
     * @return the game-version state; a new game unless a replay loader established it
     */
    public @NotNull GameRulesVersion getRulesVersion() {
        return this.rulesVersion;
    }

    /**
     * Sets the game-version state, as the replay loader establishes it.
     *
     * @param rulesVersion the game-version state
     */
    public void setRulesVersion(@NotNull GameRulesVersion rulesVersion) {
        this.rulesVersion = rulesVersion;
    }

    /**
     * @return the game options the world was started with, or {@code null} when not established
     */
    public @Nullable GameSettings getGameSettings() {
        return this.gameSettings;
    }

    /**
     * Sets the game options the world was started with (the game-option block, for example the victory condition).
     *
     * @param gameSettings the established options
     */
    public void setGameSettings(@NotNull GameSettings gameSettings) {
        this.gameSettings = gameSettings;
    }

    /**
     * @return {@code true} if the original game option permits cheats, {@code false} otherwise
     */
    public boolean areCheatsEnabled() {
        return this.cheatsEnabled;
    }

    /**
     * Sets cheat eligibility from the original game option.
     *
     * @param enabled the original option byte, which must equal {@code 1} to permit cheats
     */
    public void setCheatsEnabled(int enabled) {
        this.cheatsEnabled = enabled == CHEATS_PERMITTED;
    }

    /**
     * @return the active cheat flags ({@link GameCheats}) of the frame visibility, or {@code 0} without a frame
     */
    public int getCheatFlags() {
        return this.frame == null ? 0 : this.frame.getVisibility().getCheatFlags();
    }

    /**
     * @return {@code true} if Operation Cwal is active, {@code false} otherwise
     */
    public boolean isOperationCwal() {
        return (this.getCheatFlags() & GameCheats.OPERATION_CWAL) != 0;
    }

    /**
     * @return {@code true} if the world plays a replay, {@code false} otherwise
     */
    public boolean isReplayPlayback() {
        return this.replayPlayback;
    }

    /**
     * Sets whether the world plays a replay.
     *
     * @param replayPlayback {@code true} if the world plays a replay
     */
    public void setReplayPlayback(boolean replayPlayback) {
        this.replayPlayback = replayPlayback;
    }

    /**
     * @return the player slots of the live session that resolve the issuers of its commands, {@code null} while the world
     * plays a replay or runs without a live session
     */
    public @Nullable PlayerSlots getPlayerSlots() {
        return this.playerSlots;
    }

    /**
     * Sets the player slots of the live session that resolve the issuers of its commands.
     *
     * @param playerSlots the live session's player slots, or {@code null} for none
     */
    public void setPlayerSlots(@Nullable PlayerSlots playerSlots) {
        this.playerSlots = playerSlots;
    }

    /**
     * @return the current frame, the counter of the engine frame clock
     */
    public int getCurrentFrame() {
        return this.requireClock().getFrame();
    }

    /**
     * Sets the current frame: a frame change clears the attack alert events, and any frame after {@code 0} finishes the
     * initialization of the execution scope, the unit registry, the foliage and the creep.
     *
     * @param currentFrame the new current frame
     */
    public void setCurrentFrame(int currentFrame) {
        // Sound events are not cleared here: the commands of a frame play their sounds at once, and only
        // the client drains sound events, so a consumer draining after the step sees both.
        FrameClock frameClock = this.requireClock();

        if (frameClock.getFrame() != currentFrame) {
            this.interfaceLink.getAttackAlertEvents().clear();
        }

        if (currentFrame != 0) {
            this.finishOwnerInitialization();
        }

        frameClock.setFrame(currentFrame);
    }

    /**
     * Stores the frame counter DWORD as an EUD write does: a plain store without the frame-change bookkeeping of
     * {@link #setCurrentFrame}. Every reader sees the new value, the frame increment continues from it and replay playback
     * schedules its command blocks against it.
     *
     * @param currentFrame the new frame counter
     */
    public void storeCurrentFrame(int currentFrame) {
        this.requireClock().setFrame(currentFrame);
    }

    /**
     * Finishes the advance of the frame counter by the engine frame at the start of a frame, as {@link #setCurrentFrame} does for a changed
     * frame: clears the attack alert events and, after frame {@code 0}, finishes the initialization of the execution scope, the unit
     * registry, the foliage and the creep.
     */
    public void finishFrameAdvance() {
        this.interfaceLink.getAttackAlertEvents().clear();

        if (this.getCurrentFrame() != 0) {
            this.finishOwnerInitialization();
        }
    }

    private @NotNull FrameClock requireClock() {
        FrameClock frameClock = this.clock;

        if (frameClock == null) {
            throw Exceptions.newIllegalStateException("The SC:R game system is not attached to its engine game yet.");
        }

        return frameClock;
    }

    /**
     * @return the DWORD count of the turn steps since the game started, whose low WORD stamps the selection history; unlike
     * the frame counter, a paused frame's step counts too and EUD writes do not change it
     */
    public int getTurnCount() {
        return this.turnCount;
    }

    /**
     * Counts one turn step: the session counts the startup turn step and then every step once its commands ran, before
     * the frame runs.
     */
    public void incrementTurnCount() {
        this.turnCount++;
    }

    /**
     * @return the DWORD marker counter from the latest represented command dispatch
     */
    public int getCommandDispatchMarkerCount() {
        return this.commandDispatchMarkerCount;
    }

    /**
     * Sets the command dispatch marker counter without changing its DWORD bits.
     *
     * @param commandDispatchMarkerCount the new counter
     */
    public void setCommandDispatchMarkerCount(int commandDispatchMarkerCount) {
        this.commandDispatchMarkerCount = commandDispatchMarkerCount;
    }

    /**
     * Returns the speed of the last executed Game Speed command: replay mode stores it, and in a single-player game the
     * command posts the speed change that sets the game speed configuration read by the trigger timer and the frame pacing.
     *
     * @return the requested speed {@code 0..6}, or {@code -1} when no Game Speed command executed
     */
    public int getRequestedGameSpeed() {
        return this.requestedGameSpeed;
    }

    /**
     * Sets the speed requested by a Game Speed command.
     *
     * @param requestedGameSpeed the speed {@code 0..6}
     */
    public void setRequestedGameSpeed(int requestedGameSpeed) {
        this.requestedGameSpeed = requestedGameSpeed;
    }

    /**
     * @return the end a command gave the game ({@link #end(GameEnd)}), or {@code null} while the game runs
     */
    public @Nullable GameEnd getEnd() {
        return this.end;
    }

    /**
     * Ends the game: the commands and the turn step of the current game loop complete, then the session runs no further
     * frame. A later end replaces the earlier one.
     *
     * @param end the end
     */
    public void end(@NotNull GameEnd end) {
        this.end = end;
    }

    /**
     * @return the update tiles countdown
     */
    public int getUpdateTilesCountdown() {
        return this.updateTilesCountdown;
    }

    /**
     * Sets the update tiles countdown.
     *
     * @param updateTilesCountdown the new update tiles countdown
     */
    public void setUpdateTilesCountdown(int updateTilesCountdown) {
        this.updateTilesCountdown = updateTilesCountdown;
    }

    /**
     * @return the building power refresh gate DWORD, or {@code null} before its first write
     */
    public @Nullable Integer getBuildingPowerRefresh() {
        return this.buildingPowerRefresh;
    }

    /**
     * Returns the building power refresh gate of a world that has written it.
     *
     * @return the building power refresh gate DWORD
     * @throws UnsupportedOperationException if the gate has not been written
     */
    public int requireBuildingPowerRefresh() {
        Integer buildingPowerRefresh = this.buildingPowerRefresh;

        if (buildingPowerRefresh == null) {
            throw Exceptions.newUnsupportedOperationException("The building power gate is not initialized.");
        }

        return buildingPowerRefresh;
    }

    /**
     * Sets the building power refresh gate after its update phase.
     *
     * @param buildingPowerRefresh the gate DWORD
     */
    public void setBuildingPowerRefresh(int buildingPowerRefresh) {
        this.buildingPowerRefresh = buildingPowerRefresh;
    }

    /**
     * @return {@code true} if startup has published command readiness (independently of pool allocation and the
     * completion of the earlier construction scope), {@code false} otherwise
     */
    public boolean hasInitializedWorldState() {
        return this.worldInitialized;
    }

    /**
     * @return the construction and frame execution scope, or {@code null} before initialization
     */
    public @Nullable GameExecutionScope getExecutionScope() {
        return this.executionScope;
    }

    /**
     * Returns the construction and frame execution scope of an initialized world.
     *
     * @return the construction and frame execution scope
     * @throws UnsupportedOperationException if the execution scope is not initialized
     */
    public @NotNull GameExecutionScope requireExecutionScope() {
        GameExecutionScope executionScope = this.executionScope;

        if (executionScope == null) {
            throw Exceptions.newUnsupportedOperationException("The execution scope is not initialized.");
        }

        return executionScope;
    }

    /**
     * @return the retained frame contexts, or {@code null} before initialization
     */
    public @Nullable GameFrame getFrame() {
        return this.frame;
    }

    /**
     * Returns the retained frame contexts of an initialized world.
     *
     * @return the retained frame contexts
     * @throws UnsupportedOperationException if the frame contexts are not initialized
     */
    public @NotNull GameFrame requireFrame() {
        GameFrame frame = this.frame;

        if (frame == null) {
            throw Exceptions.newUnsupportedOperationException("The frame contexts are not initialized.");
        }

        return frame;
    }

    /**
     * Sets the frame contexts established by world initialization.
     *
     * @param frame the frame contexts
     * @throws IllegalStateException if the frame contexts are already installed
     */
    public void setFrame(@NotNull GameFrame frame) {
        if (this.frame != null) {
            throw Exceptions.newIllegalStateException("Native frame contexts are already initialized.");
        }

        this.frame = frame;
    }

    /**
     * Returns the established frame caller, which the frame steps after the world step use.
     *
     * @return the frame caller
     * @throws UnsupportedOperationException if no frame caller is established
     */
    public @NotNull ReplayFrameRunner requireReplayFrameRunner() {
        ReplayFrameRunner runner = this.replayFrameRunner;

        if (runner == null) {
            throw Exceptions.newUnsupportedOperationException("The frame caller is not established.");
        }

        return runner;
    }

    /**
     * Sets the frame caller whose controls the frame steps after the world step use; a new caller replaces the previous one.
     *
     * @param replayFrameRunner the frame caller
     */
    public void setReplayFrameRunner(@NotNull ReplayFrameRunner replayFrameRunner) {
        this.replayFrameRunner = replayFrameRunner;
    }

    /**
     * @return the established selection context, or {@code null} when unavailable
     */
    public @Nullable UnitSelection getSelection() {
        return this.selection;
    }

    /**
     * Returns the selection context of an initialized world.
     *
     * @return the selection context
     * @throws UnsupportedOperationException if the selection storage is not initialized
     */
    public @NotNull UnitSelection requireSelection() {
        UnitSelection selection = this.selection;

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("The selection storage is not initialized.");
        }

        return selection;
    }

    /**
     * Sets the command selection context established by world initialization.
     *
     * @param selection the selection context
     * @throws IllegalStateException if a context is already installed
     */
    public void setSelection(@NotNull UnitSelection selection) {
        if (this.selection != null) {
            throw Exceptions.newIllegalStateException("Native selection is already initialized.");
        }

        this.selection = selection;
    }

    /**
     * @return the order storage, or {@code null} before initialization
     */
    public @Nullable OrderPool getOrderPool() {
        return this.orderPool;
    }

    /**
     * Returns the order storage of an initialized world.
     *
     * @return the order storage
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    public @NotNull OrderPool requireOrderPool() {
        OrderPool orderPool = this.orderPool;

        if (orderPool == null) {
            throw Exceptions.newUnsupportedOperationException("The order pool is not initialized.");
        }

        return orderPool;
    }

    /**
     * @return the established fog descriptors and visibility context, or {@code null} before initialization
     */
    public @Nullable SightCoverage getSightCoverage() {
        return this.sightCoverage;
    }

    /**
     * Returns the fog descriptors and visibility context of an initialized world.
     *
     * @return the fog descriptors and visibility context
     * @throws UnsupportedOperationException if the sight coverage is not initialized
     */
    public @NotNull SightCoverage requireSightCoverage() {
        SightCoverage sightCoverage = this.sightCoverage;

        if (sightCoverage == null) {
            throw Exceptions.newUnsupportedOperationException("The sight coverage is not initialized.");
        }

        return sightCoverage;
    }

    /**
     * Sets the fog storage established by world initialization.
     *
     * @param sightCoverage the fog storage
     * @throws IllegalStateException if fog storage is already initialized
     */
    public void setSightCoverage(@NotNull SightCoverage sightCoverage) {
        if (this.sightCoverage != null) {
            throw Exceptions.newIllegalStateException("Native fog storage is already initialized.");
        }

        this.sightCoverage = sightCoverage;
    }

    /**
     * @return the explicitly initialized creep context, or {@code null} before its producer runs
     */
    public @Nullable CreepField getCreepField() {
        return this.creepField;
    }

    /**
     * Returns the creep context of an initialized world.
     *
     * @return the creep context
     * @throws UnsupportedOperationException if the creep field is not initialized
     */
    public @NotNull CreepField requireCreepField() {
        CreepField creepField = this.creepField;

        if (creepField == null) {
            throw Exceptions.newUnsupportedOperationException("The creep field is not initialized.");
        }

        return creepField;
    }

    /**
     * Sets the creep context with its established visibility state; its tile variants draw from {@link #getRuntimeRandom}.
     *
     * @param creepField the initialized creep context
     * @throws IllegalStateException if the creep context is already installed
     */
    public void setCreepField(@NotNull CreepField creepField) {
        if (this.creepField != null) {
            throw Exceptions.newIllegalStateException("Native creep is already initialized.");
        }

        this.creepField = creepField;
    }

    /**
     * @return the established creep recession counters, or {@code null} before initialization
     */
    public @Nullable CreepRecession getCreepRecession() {
        return this.creepRecession;
    }

    /**
     * Returns the creep recession counters of an initialized world.
     *
     * @return the creep recession counters
     * @throws UnsupportedOperationException if the creep recession is not initialized
     */
    public @NotNull CreepRecession requireCreepRecession() {
        CreepRecession creepRecession = this.creepRecession;

        if (creepRecession == null) {
            throw Exceptions.newUnsupportedOperationException("The creep recession is not initialized.");
        }

        return creepRecession;
    }

    /**
     * Sets the creep recession context established by world initialization.
     *
     * @param creepRecession the initialized recession context
     * @throws IllegalStateException if the recession context is already installed
     */
    public void setCreepRecession(@NotNull CreepRecession creepRecession) {
        if (this.creepRecession != null) {
            throw Exceptions.newIllegalStateException("Native creep recession is already initialized.");
        }

        this.creepRecession = creepRecession;
    }

    /**
     * @return the established foliage context, or {@code null} before its initialization
     */
    public @Nullable Foliage getFoliage() {
        return this.foliage;
    }

    /**
     * Returns the foliage context of an initialized world.
     *
     * @return the foliage context
     * @throws UnsupportedOperationException if the foliage is not initialized
     */
    public @NotNull Foliage requireFoliage() {
        Foliage foliage = this.foliage;

        if (foliage == null) {
            throw Exceptions.newUnsupportedOperationException("The foliage is not initialized.");
        }

        return foliage;
    }

    /**
     * Sets the foliage grid, cleared before its procedural population.
     *
     * @param foliage the cleared foliage grid
     * @throws IllegalStateException if foliage storage is already initialized
     */
    public void setFoliage(@NotNull Foliage foliage) {
        if (this.foliage != null) {
            throw Exceptions.newIllegalStateException("Native foliage storage is already initialized.");
        }

        this.foliage = foliage;
    }

    /**
     * @return the initialized resource-area records, or {@code null} before the map postprocessing
     */
    public @Nullable ResourceAreas getResourceAreas() {
        return this.resourceAreas;
    }

    /**
     * Returns the resource-area records of an initialized world.
     *
     * @return the resource-area records
     * @throws UnsupportedOperationException if the resource areas are not initialized
     */
    public @NotNull ResourceAreas requireResourceAreas() {
        ResourceAreas resourceAreas = this.resourceAreas;

        if (resourceAreas == null) {
            throw Exceptions.newUnsupportedOperationException("The resource areas are not initialized.");
        }

        return resourceAreas;
    }

    /**
     * Sets the resource-area records initialized by the map postprocessing.
     *
     * @param resourceAreas the initialized resource-area context
     * @throws IllegalStateException if the resource areas are already installed
     */
    public void setResourceAreas(@NotNull ResourceAreas resourceAreas) {
        if (this.resourceAreas != null) {
            throw Exceptions.newIllegalStateException("Native resource areas are already initialized.");
        }

        this.resourceAreas = resourceAreas;
    }

    /**
     * @return the unit strength tables, or {@code null} before initialization
     */
    public @Nullable UnitStrengthTable getUnitStrengthTable() {
        return this.unitStrengthTable;
    }

    /**
     * Returns the unit strength tables of an initialized world.
     *
     * @return the unit strength tables
     * @throws UnsupportedOperationException if the unit strength tables are not initialized
     */
    public @NotNull UnitStrengthTable requireUnitStrengthTable() {
        UnitStrengthTable unitStrengthTable = this.unitStrengthTable;

        if (unitStrengthTable == null) {
            throw Exceptions.newUnsupportedOperationException("The unit strength tables are not initialized.");
        }

        return unitStrengthTable;
    }

    /**
     * Sets the unit strength tables established by world initialization.
     *
     * @param unitStrengthTable the established strength tables
     * @throws IllegalStateException if the strength tables are already installed
     */
    public void setUnitStrengthTable(@NotNull UnitStrengthTable unitStrengthTable) {
        if (this.unitStrengthTable != null) {
            throw Exceptions.newIllegalStateException("Native unit strength is already initialized.");
        }

        this.unitStrengthTable = unitStrengthTable;
    }

    /**
     * @return the established computer player scheduler, or {@code null} before initialization
     */
    public @Nullable ComputerPlayerPhase getComputerPlayerPhase() {
        return this.computerPlayerPhase;
    }

    /**
     * Returns the computer player scheduler of an initialized world.
     *
     * @return the computer player scheduler
     * @throws UnsupportedOperationException if the computer-player phase is not initialized
     */
    public @NotNull ComputerPlayerPhase requireComputerPlayerPhase() {
        ComputerPlayerPhase computerPlayerPhase = this.computerPlayerPhase;

        if (computerPlayerPhase == null) {
            throw Exceptions.newUnsupportedOperationException("The computer-player phase is not initialized.");
        }

        return computerPlayerPhase;
    }

    /**
     * Sets the computer player scheduler established by world initialization.
     *
     * @param computerPlayerPhase the initialized scheduler
     * @throws IllegalStateException if the scheduler is already installed
     */
    public void setComputerPlayerPhase(@NotNull ComputerPlayerPhase computerPlayerPhase) {
        if (this.computerPlayerPhase != null) {
            throw Exceptions.newIllegalStateException("Native computer-player phase is already initialized.");
        }

        this.computerPlayerPhase = computerPlayerPhase;
    }

    /**
     * @return the trigger scheduling context, or {@code null} before initialization
     */
    public @Nullable TriggerContext getTriggerContext() {
        return this.triggerContext;
    }

    /**
     * Sets the trigger scheduling context and binds it to this world ({@link TriggerContext#setWorld}).
     *
     * @param triggerContext the trigger scheduling and record state
     */
    public void setTriggerContext(@NotNull TriggerContext triggerContext) {
        this.triggerContext = triggerContext;

        triggerContext.setWorld(this);
    }

    /**
     * @return the psionic provider membership and resource-derived coverage
     */
    public @NotNull UnitPsi getPsi() {
        return this.psi;
    }

    /**
     * @return the cost tests of the game and the costs they last tested per player
     */
    public @NotNull CostTests getCostTests() {
        return this.costTests;
    }

    /**
     * @return the retained ordered {@code THG2} creation outcomes
     */
    public @NotNull List<MapSpriteEffectLoader.Creation> getMapSpriteEffectCreations() {
        return this.mapSpriteEffectCreations;
    }

    /**
     * Sets the {@code THG2} creation outcomes of the map, copied.
     *
     * @param creations the completed {@code THG2} creation outcomes in section order
     */
    public void setMapSpriteEffectCreations(@NotNull List<MapSpriteEffectLoader.Creation> creations) {
        this.mapSpriteEffectCreations = List.copyOf(creations);
    }

    /**
     * @return the retained ordered {@code UNIT} creation outcomes
     */
    public @NotNull List<MapUnitLoader.Creation> getMapUnitCreations() {
        return this.mapUnitCreations;
    }

    /**
     * Sets the {@code UNIT} creation outcomes of the map, copied.
     *
     * @param creations the completed {@code UNIT} creation outcomes in section order
     */
    public void setMapUnitCreations(@NotNull List<MapUnitLoader.Creation> creations) {
        this.mapUnitCreations = List.copyOf(creations);
    }

    /**
     * @return the retained starting-unit requests and completed placement snapshots
     */
    public @NotNull List<StartingUnitLoader.Creation> getStartingUnitCreations() {
        return this.startingUnitCreations;
    }

    /**
     * Sets the starting-unit outcomes, copied.
     *
     * @param creations the starting-unit outcomes in creation order
     */
    public void setStartingUnitCreations(@NotNull List<StartingUnitLoader.Creation> creations) {
        this.startingUnitCreations = List.copyOf(creations);
    }

    /**
     * @return the player table (players, start locations and per-player tables)
     */
    public @NotNull PlayerTable getPlayerTable() {
        return this.playerTable;
    }

    /**
     * @return the unit storage (pool, finder, registry)
     */
    public @NotNull UnitSystem getUnitSystem() {
        return this.unitSystem;
    }

    /**
     * @return the sprite storage (image, sprite and sprite-effect pools)
     */
    public @NotNull SpriteSystem getSpriteSystem() {
        return this.spriteSystem;
    }

    /**
     * @return the path storage (terrain contours, path pool and workspaces)
     */
    public @NotNull PathSystem getPathSystem() {
        return this.pathSystem;
    }

    /**
     * @return the game context values no system owns
     */
    public @NotNull GameContext getGameContext() {
        return this.gameContext;
    }

    /**
     * @return the presentation events and local inputs of the local interface
     */
    public @NotNull InterfaceLink getInterfaceLink() {
        return this.interfaceLink;
    }

    /**
     * @return the phases of a frame of this game, in the order the game fixes; a phase can be replaced at its position
     */
    public @NotNull FramePhaseList getFramePhases() {
        return this.framePhases;
    }

    /**
     * @return the behavior of every order id, created with the game; the order types' behaviors are registered in a frozen engine order registry
     */
    public @NotNull ScrOrderBehaviors getOrderBehaviors() {
        return this.orderBehaviors;
    }

    /**
     * @return the hook points the SC:R systems call, resolved when the game system attached ({@link ScrHooks})
     * @throws IllegalStateException if the game system is not attached yet
     */
    public @NotNull ScrHooks getHooks() {
        ScrHooks attached = this.hooks;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The SC:R game system is not attached to its engine game yet.");
        }

        return attached;
    }

    /**
     * @return the plugins of the running mod's game extensions, created when the game system attached ({@link ScrGameExtensionUtils}); empty
     * without extensions
     */
    public @NotNull ScrGamePlugin @NotNull [] getPlugins() {
        return this.plugins;
    }

    /**
     * @return the memory sources of the running mod's game extensions, created when the game system attached
     * ({@link ScrGameExtensionUtils#createMemorySources}); empty without extensions that provide memory
     */
    public @NotNull ClassicMemorySource @NotNull [] getMemorySources() {
        return this.memorySources;
    }

    /**
     * Returns the plugins of the running mod's simulation extensions, created when the game system attached
     * ({@link ScrSimulationExtensionUtils#createPlugins}), which the simulation routines report to and ask.
     *
     * @return the plugins, or {@code null} if no extension created one
     */
    public @Nullable ScrSimulationPlugins getSimulationPlugins() {
        return this.simulationPlugins;
    }

    /**
     * @return a copy of the replay sections ({@link #setReplaySection}) by tag, in first-set order; the data arrays are shared
     */
    public @NotNull Map<String, byte @NotNull []> getReplaySections() {
        synchronized (this.replaySections) {
            return new LinkedHashMap<>(this.replaySections);
        }
    }

    /**
     * Sets a replay section of this game: the SC:R mod writes it as a mod section of the replay it writes of a live game
     * ({@link ReplayFile#readModSection}), and the setup of a game that plays the replay back offers it ({@link GameSetup#readReplaySection}).
     * Mods choose their own tags; a section of a tag set before is replaced. A replay played back writes no replay.
     *
     * @param tag  the section tag: four printable ASCII characters other than SC:R's own tags ({@link ReplayFile#isModSectionTag})
     * @param data the section data, retained, not copied
     * @throws IllegalArgumentException if the tag is not a mod section tag
     */
    public void setReplaySection(@NotNull String tag, byte @NotNull [] data) {
        if (!ReplayFile.isModSectionTag(tag)) {
            throw Exceptions.newIllegalArgumentException("\"{0}\" is not a mod section tag.", tag);
        }

        synchronized (this.replaySections) {
            this.replaySections.put(tag, data);
        }
    }

    /**
     * Checks whether this game can be saved: every simulation plugin allows it ({@link ScrSimulationPlugin#allowsSave()}).
     *
     * @return {@code true} if the game can be saved, {@code false} otherwise
     */
    public boolean isSaveAllowed() {
        return this.simulationPlugins == null || this.simulationPlugins.allowsSave();
    }

    /**
     * @return the {@link UnitPhase} system of this game
     */
    public @NotNull UnitPhase getUnitPhase() {
        return this.unitPhase;
    }

    /**
     * @return the {@link UnitBuildingMove} system of this game
     */
    public @NotNull UnitBuildingMove getUnitBuildingMove() {
        return this.unitBuildingMove;
    }

    /**
     * @return the {@link UnitCompletion} system of this game
     */
    public @NotNull UnitCompletion getUnitCompletion() {
        return this.unitCompletion;
    }

    /**
     * @return the {@link UnitEnergy} system of this game
     */
    public @NotNull UnitEnergy getUnitEnergy() {
        return this.unitEnergy;
    }

    /**
     * @return the {@link UnitHealth} system of this game
     */
    public @NotNull UnitHealth getUnitHealth() {
        return this.unitHealth;
    }

    /**
     * @return the {@link UnitInitializer} system of this game
     */
    public @NotNull UnitInitializer getUnitInitializer() {
        return this.unitInitializer;
    }

    /**
     * @return the {@link UnitLifecycle} system of this game
     */
    public @NotNull UnitLifecycle getUnitLifecycle() {
        return this.unitLifecycle;
    }

    /**
     * @return the {@link UnitOwnership} system of this game
     */
    public @NotNull UnitOwnership getUnitOwnership() {
        return this.unitOwnership;
    }

    /**
     * @return the {@link UnitQueries} system of this game
     */
    public @NotNull UnitQueries getUnitQueries() {
        return this.unitQueries;
    }

    /**
     * @return the {@link UnitRelocation} system of this game
     */
    public @NotNull UnitRelocation getUnitRelocation() {
        return this.unitRelocation;
    }

    /**
     * @return the {@link UnitStatusEffects} system of this game
     */
    public @NotNull UnitStatusEffects getUnitStatusEffects() {
        return this.unitStatusEffects;
    }

    /**
     * @return the {@link UnitStorageConstructor} system of this game
     */
    public @NotNull UnitStorageConstructor getUnitStorageConstructor() {
        return this.unitStorageConstructor;
    }

    /**
     * @return the {@link UnitTimers} system of this game
     */
    public @NotNull UnitTimers getUnitTimers() {
        return this.unitTimers;
    }

    /**
     * @return the {@link IscriptExecutor} system of this game
     */
    public @NotNull IscriptExecutor getIscriptExecutor() {
        return this.iscriptExecutor;
    }

    /**
     * @return the {@link SelectionImages} system of this game
     */
    public @NotNull SelectionImages getSelectionImages() {
        return this.selectionImages;
    }

    /**
     * @return the {@link MovementCollision} system of this game
     */
    public @NotNull MovementCollision getMovementCollision() {
        return this.movementCollision;
    }

    /**
     * @return the {@link UnitFollowMovement} system of this game
     */
    public @NotNull UnitFollowMovement getUnitFollowMovement() {
        return this.unitFollowMovement;
    }

    /**
     * @return the {@link UnitMovement} system of this game
     */
    public @NotNull UnitMovement getUnitMovement() {
        return this.unitMovement;
    }

    /**
     * @return the {@link UnitMovementCollision} system of this game
     */
    public @NotNull UnitMovementCollision getUnitMovementCollision() {
        return this.unitMovementCollision;
    }

    /**
     * @return the {@link UnitPointMovement} system of this game
     */
    public @NotNull UnitPointMovement getUnitPointMovement() {
        return this.unitPointMovement;
    }

    /**
     * @return the {@link UnitSliding} system of this game
     */
    public @NotNull UnitSliding getUnitSliding() {
        return this.unitSliding;
    }

    /**
     * @return the {@link UnitSpeed} system of this game
     */
    public @NotNull UnitSpeed getUnitSpeed() {
        return this.unitSpeed;
    }

    /**
     * @return the {@link UnitTargetMovement} system of this game
     */
    public @NotNull UnitTargetMovement getUnitTargetMovement() {
        return this.unitTargetMovement;
    }

    /**
     * @return the {@link SpriteVisibility} system of this game
     */
    public @NotNull SpriteVisibility getSpriteVisibility() {
        return this.spriteVisibility;
    }

    /**
     * @return the {@link UnitBurrowing} system of this game
     */
    public @NotNull UnitBurrowing getUnitBurrowing() {
        return this.unitBurrowing;
    }

    /**
     * @return the {@link UnitCloaking} system of this game
     */
    public @NotNull UnitCloaking getUnitCloaking() {
        return this.unitCloaking;
    }

    /**
     * @return the {@link UnitVisibility} system of this game
     */
    public @NotNull UnitVisibility getUnitVisibility() {
        return this.unitVisibility;
    }

    /**
     * @return the {@link LocalVision} of this game's local view
     */
    public @NotNull LocalVision getLocalVision() {
        return this.localVision;
    }

    /**
     * @return the {@link CritterOrders} system of this game
     */
    public @NotNull CritterOrders getCritterOrders() {
        return this.critterOrders;
    }

    /**
     * @return the {@link DoodadOrders} system of this game
     */
    public @NotNull DoodadOrders getDoodadOrders() {
        return this.doodadOrders;
    }

    /**
     * @return the {@link ScrOrderSystem} of this game, the game's {@link OrderSystem}
     * @throws IllegalStateException if the SC:R game system is not attached yet
     */
    public @NotNull ScrOrderSystem getOrders() {
        ScrOrderSystem attached = this.orders;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The SC:R game system is not attached to its engine game yet.");
        }

        return attached;
    }

    /**
     * @return the {@link UnitCommands} system of this game
     */
    public @NotNull UnitCommands getUnitCommands() {
        return this.unitCommands;
    }

    /**
     * @return the {@link UnitOrderEligibility} system of this game
     */
    public @NotNull UnitOrderEligibility getUnitOrderEligibility() {
        return this.unitOrderEligibility;
    }

    /**
     * @return the {@link UnitOrders} system of this game
     */
    public @NotNull UnitOrders getUnitOrders() {
        return this.unitOrders;
    }

    /**
     * @return the {@link UnitRescue} system of this game
     */
    public @NotNull UnitRescue getUnitRescue() {
        return this.unitRescue;
    }

    /**
     * @return the {@link ProjectilePhase} system of this game
     */
    public @NotNull ProjectilePhase getProjectilePhase() {
        return this.projectilePhase;
    }

    /**
     * @return the {@link UnitAttack} system of this game
     */
    public @NotNull UnitAttack getUnitAttack() {
        return this.unitAttack;
    }

    /**
     * @return the {@link UnitDamage} system of this game
     */
    public @NotNull UnitDamage getUnitDamage() {
        return this.unitDamage;
    }

    /**
     * @return the {@link UnitTargeting} system of this game
     */
    public @NotNull UnitTargeting getUnitTargeting() {
        return this.unitTargeting;
    }

    /**
     * @return the {@link UnitTurret} system of this game
     */
    public @NotNull UnitTurret getUnitTurret() {
        return this.unitTurret;
    }

    /**
     * @return the {@link PlayerDeparture} system of this game
     */
    public @NotNull PlayerDeparture getPlayerDeparture() {
        return this.playerDeparture;
    }

    /**
     * @return the {@link LarvaSpawner} system of this game
     */
    public @NotNull LarvaSpawner getLarvaSpawner() {
        return this.larvaSpawner;
    }

    /**
     * @return the {@link ProductionQueueCommands} system of this game
     */
    public @NotNull ProductionQueueCommands getProductionQueueCommands() {
        return this.productionQueueCommands;
    }

    /**
     * @return the {@link ScrRequirementSystem} of this game, the game's {@link RequirementSystem}
     * @throws IllegalStateException if the SC:R game system is not attached yet
     */
    public @NotNull ScrRequirementSystem getRequirements() {
        ScrRequirementSystem attached = this.requirements;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The SC:R game system is not attached to its engine game yet.");
        }

        return attached;
    }

    /**
     * @return the {@link SupplyCheck} system of this game
     */
    public @NotNull SupplyCheck getSupplyCheck() {
        return this.supplyCheck;
    }

    /**
     * @return the {@link UnitAddons} system of this game
     */
    public @NotNull UnitAddons getUnitAddons() {
        return this.unitAddons;
    }

    /**
     * @return the {@link UnitBuildTime} system of this game
     */
    public @NotNull UnitBuildTime getUnitBuildTime() {
        return this.unitBuildTime;
    }

    /**
     * @return the {@link UnitConstruction} system of this game
     */
    public @NotNull UnitConstruction getUnitConstruction() {
        return this.unitConstruction;
    }

    /**
     * @return the {@link UnitMorphing} system of this game
     */
    public @NotNull UnitMorphing getUnitMorphing() {
        return this.unitMorphing;
    }

    /**
     * @return the {@link UnitPlacement} system of this game
     */
    public @NotNull UnitPlacement getUnitPlacement() {
        return this.unitPlacement;
    }

    /**
     * @return the {@link UnitPositioning} system of this game
     */
    public @NotNull UnitPositioning getUnitPositioning() {
        return this.unitPositioning;
    }

    /**
     * @return the {@link UnitProduction} system of this game
     */
    public @NotNull UnitProduction getUnitProduction() {
        return this.unitProduction;
    }

    /**
     * @return the {@link UnitRally} system of this game
     */
    public @NotNull UnitRally getUnitRally() {
        return this.unitRally;
    }

    /**
     * @return the {@link UnitResearch} system of this game
     */
    public @NotNull UnitResearch getUnitResearch() {
        return this.unitResearch;
    }

    /**
     * @return the {@link UnitTechnology} system of this game
     */
    public @NotNull UnitTechnology getUnitTechnology() {
        return this.unitTechnology;
    }

    /**
     * @return the {@link UnitTerranConstruction} system of this game
     */
    public @NotNull UnitTerranConstruction getUnitTerranConstruction() {
        return this.unitTerranConstruction;
    }

    /**
     * @return the {@link UnitTraining} system of this game
     */
    public @NotNull UnitTraining getUnitTraining() {
        return this.unitTraining;
    }

    /**
     * @return the {@link UnitWarping} system of this game
     */
    public @NotNull UnitWarping getUnitWarping() {
        return this.unitWarping;
    }

    /**
     * @return the {@link ArchonOrders} system of this game
     */
    public @NotNull ArchonOrders getArchonOrders() {
        return this.archonOrders;
    }

    /**
     * @return the {@link DisruptionWeb} system of this game
     */
    public @NotNull DisruptionWeb getDisruptionWeb() {
        return this.disruptionWeb;
    }

    /**
     * @return the {@link UnitBuildingFlight} system of this game
     */
    public @NotNull UnitBuildingFlight getUnitBuildingFlight() {
        return this.unitBuildingFlight;
    }

    /**
     * @return the {@link UnitHallucination} system of this game
     */
    public @NotNull UnitHallucination getUnitHallucination() {
        return this.unitHallucination;
    }

    /**
     * @return the {@link UnitHangar} system of this game
     */
    public @NotNull UnitHangar getUnitHangar() {
        return this.unitHangar;
    }

    /**
     * @return the {@link UnitInfestation} system of this game
     */
    public @NotNull UnitInfestation getUnitInfestation() {
        return this.unitInfestation;
    }

    /**
     * @return the {@link UnitMedic} system of this game
     */
    public @NotNull UnitMedic getUnitMedic() {
        return this.unitMedic;
    }

    /**
     * @return the {@link UnitNuclear} system of this game
     */
    public @NotNull UnitNuclear getUnitNuclear() {
        return this.unitNuclear;
    }

    /**
     * @return the {@link UnitNydus} system of this game
     */
    public @NotNull UnitNydus getUnitNydus() {
        return this.unitNydus;
    }

    /**
     * @return the {@link UnitPowerups} system of this game
     */
    public @NotNull UnitPowerups getUnitPowerups() {
        return this.unitPowerups;
    }

    /**
     * @return the {@link UnitShieldBattery} system of this game
     */
    public @NotNull UnitShieldBattery getUnitShieldBattery() {
        return this.unitShieldBattery;
    }

    /**
     * @return the {@link UnitSpells} system of this game
     */
    public @NotNull UnitSpells getUnitSpells() {
        return this.unitSpells;
    }

    /**
     * @return the {@link UnitTransport} system of this game
     */
    public @NotNull UnitTransport getUnitTransport() {
        return this.unitTransport;
    }

    /**
     * @return the {@link UnitHarvesting} system of this game
     */
    public @NotNull UnitHarvesting getUnitHarvesting() {
        return this.unitHarvesting;
    }

    /**
     * @return the {@link UnitResourceSearch} system of this game
     */
    public @NotNull UnitResourceSearch getUnitResourceSearch() {
        return this.unitResourceSearch;
    }

    /**
     * @return the {@link ComputerTerrain} system of this game
     */
    public @NotNull ComputerTerrain getComputerTerrain() {
        return this.computerTerrain;
    }

    /**
     * @return the {@link ComputerResources} system of this game
     */
    public @NotNull ComputerResources getComputerResources() {
        return this.computerResources;
    }

    /**
     * @return the {@link ComputerPlacement} system of this game
     */
    public @NotNull ComputerPlacement getComputerPlacement() {
        return this.computerPlacement;
    }

    /**
     * @return the {@link ComputerMilitary} system of this game
     */
    public @NotNull ComputerMilitary getComputerMilitary() {
        return this.computerMilitary;
    }

    /**
     * @return the {@link ComputerAttack} system of this game
     */
    public @NotNull ComputerAttack getComputerAttack() {
        return this.computerAttack;
    }

    /**
     * @return the {@link ComputerCombat} system of this game
     */
    public @NotNull ComputerCombat getComputerCombat() {
        return this.computerCombat;
    }

    /**
     * @return the {@link ComputerDefense} system of this game
     */
    public @NotNull ComputerDefense getComputerDefense() {
        return this.computerDefense;
    }

    /**
     * @return the {@link ComputerOrders} system of this game
     */
    public @NotNull ComputerOrders getComputerOrders() {
        return this.computerOrders;
    }

    /**
     * @return the {@link ComputerRequirements} system of this game
     */
    public @NotNull ComputerRequirements getComputerRequirements() {
        return this.computerRequirements;
    }

    /**
     * @return the {@link ComputerTargets} system of this game
     */
    public @NotNull ComputerTargets getComputerTargets() {
        return this.computerTargets;
    }

    /**
     * @return the {@link ComputerTechnology} system of this game
     */
    public @NotNull ComputerTechnology getComputerTechnology() {
        return this.computerTechnology;
    }

    /**
     * @return the {@link ComputerTowns} system of this game
     */
    public @NotNull ComputerTowns getComputerTowns() {
        return this.computerTowns;
    }

    /**
     * @return the {@link ComputerUnits} system of this game
     */
    public @NotNull ComputerUnits getComputerUnits() {
        return this.computerUnits;
    }

    /**
     * @return the {@link ScrComputerPlayerSystem} of this game, the game's {@link ComputerPlayerSystem}
     * @throws IllegalStateException if the SC:R game system is not attached yet
     */
    public @NotNull ScrComputerPlayerSystem getComputerPlayers() {
        ScrComputerPlayerSystem attached = this.computerPlayers;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The SC:R game system is not attached to its engine game yet.");
        }

        return attached;
    }

    /**
     * @return the {@link CaptureTheFlag} system of this game
     */
    public @NotNull CaptureTheFlag getCaptureTheFlag() {
        return this.captureTheFlag;
    }

    /**
     * @return the {@link TriggerConditions} system of this game
     */
    public @NotNull TriggerConditions getTriggerConditions() {
        return this.triggerConditions;
    }

    /**
     * @return the {@link TriggerLocations} system of this game
     */
    public @NotNull TriggerLocations getTriggerLocations() {
        return this.triggerLocations;
    }

    /**
     * @return the {@link ScrTriggerSystem} of this game, the game's {@link TriggerSystem}
     * @throws IllegalStateException if the SC:R game system is not attached yet
     */
    public @NotNull ScrTriggerSystem getTriggers() {
        ScrTriggerSystem attached = this.triggers;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The SC:R game system is not attached to its engine game yet.");
        }

        return attached;
    }

    /**
     * @return the {@link TriggerUnitActions} system of this game
     */
    public @NotNull TriggerUnitActions getTriggerUnitActions() {
        return this.triggerUnitActions;
    }

    /**
     * @return the {@link GameCheats} system of this game
     */
    public @NotNull GameCheats getGameCheats() {
        return this.gameCheats;
    }

    /**
     * @return the {@link UnitSounds} system of this game
     */
    public @NotNull UnitSounds getUnitSounds() {
        return this.unitSounds;
    }

    /**
     * @return the {@link LocalInterfaceEffects} system of this game
     */
    public @NotNull LocalInterfaceEffects getLocalInterfaceEffects() {
        return this.localInterfaceEffects;
    }


    /**
     * Returns the map's name of a unit type: an added unit type has its registered name, any other type id from {@link ScrUnitIds#NONE} on
     * (unsigned) has an empty name, a type whose map string index ({@code units.dat} {@code Unit Map String}, set by the map's
     * {@code UNIS}/{@code UNIx} settings of a type without its use-default byte) is nonzero is named by that map string, and every other type by
     * its default name. The status panel name (which names a building in progress by its build type), the unit button text and the
     * placement-failure messages {@code errCANTPLACE_sdd} use it. A type whose {@code stat_txt} name a plugin overrides
     * ({@link StatTextOverrides}) has its default name, the overridden text.
     *
     * @param unitType the unit type
     * @return the map's name, the registered name of an added type, empty for any other type id from {@link ScrUnitIds#NONE} on or a missing
     * string, or {@code null} for the default name
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the map names the type but the string table is unreadable
     */
    public @Nullable String getUnitDisplayName(int unitType) {
        GameData gameData = this.requireGameData();

        if (Integer.compareUnsigned(unitType, ScrUnitIds.NONE) >= 0) {
            String addedName = gameData.getAddedUnitName(unitType);

            return addedName == null ? "" : addedName;
        }

        int stringIndex = gameData.getUnitTypes().getMapStringIndex(unitType);

        if (stringIndex == 0 || this.interfaceLink.getStatTextOverrides().find(unitType + FIRST_UNIT_NAME_STRING) != null) {
            return null;
        }

        String name = this.interfaceLink.getMessageLines().getMapString(stringIndex);

        if (name == null) {
            throw Exceptions.newUnsupportedOperationException("The map names unit type {0} but its string table is unreadable.", unitType);
        }

        return name;
    }

    /**
     * Establishes the initial construction scope before sequential frame execution.
     *
     * @throws IllegalStateException if the scope was already initialized or frame processing started
     */
    public void initializeExecutionScope() {
        if (this.executionScope != null || this.getCurrentFrame() != 0 || this.worldInitialized) {
            throw Exceptions.newIllegalStateException("Construction scope cannot be initialized in this state.");
        }

        this.executionScope = new GameExecutionScope();
    }

    /**
     * Creates the order storage with the established runtime capacity.
     *
     * @param capacity the established runtime order capacity
     * @throws IllegalStateException if order storage is already initialized
     */
    public void initializeOrderPool(int capacity) {
        if (this.orderPool != null) {
            throw Exceptions.newIllegalStateException("Native order storage is already initialized.");
        }

        this.orderPool = new OrderPool(capacity);
    }

    /**
     * Resets the edge storage of the unit spatial index after the unit capacity is established.
     *
     * @throws NullPointerException  if the world was built without game data
     * @throws IllegalStateException if unit storage is absent or the index is already initialized
     */
    public void initializeUnitSpatialIndex() {
        this.unitSystem.initializeUnitSpatialIndex(this.requireGameData().getUnitTypes());
    }

    /**
     * Requests a building power refresh by writing {@code 1} to the refresh gate.
     */
    public void requestBuildingPowerRefresh() {
        this.buildingPowerRefresh = 1;
    }

    /**
     * Marks the world initialization complete after every creation and startup effect: finishes the initialization of the
     * execution scope, the unit registry, the foliage and the creep.
     *
     * @throws IllegalStateException if the unit, sprite or image pool or the selection context is absent, or registry
     *                               subscriber effects remain pending
     */
    public void markWorldInitialized() {
        if (this.unitSystem.getUnitPool() == null || this.spriteSystem.getSpritePool() == null || this.spriteSystem.getImagePool() == null ||
            this.selection == null) {
            throw Exceptions.newIllegalStateException("Native world initialization requires all native pools and selection context.");
        }

        if (this.unitSystem.getUnitRegistry() != null && this.unitSystem.getUnitRegistry().hasPendingSubscriberEffects()) {
            throw Exceptions.newIllegalStateException("Native registry subscriber effects remain pending.");
        }

        this.finishOwnerInitialization();

        this.worldInitialized = true;
    }

    /**
     * Finishes the initialization of the execution scope, the unit registry, the foliage and the creep, each when present.
     */
    private void finishOwnerInitialization() {
        if (this.executionScope != null) {
            this.executionScope.finishInitialization();
        }

        if (this.unitSystem.getUnitRegistry() != null) {
            this.unitSystem.getUnitRegistry().finishInitialization();
        }

        if (this.foliage != null) {
            this.foliage.finishInitialization();
        }

        if (this.creepField != null) {
            this.creepField.finishInitialization();
        }
    }

    /**
     * Mixes this part of the simulation state into the hash: the SC:R state hash of {@link #computeStateHash()}, which the
     * engine state hash mixes after its clock, random generator and pools.
     *
     * @param hash the hash being computed
     */
    @Override
    public void contributeStateHash(@NotNull StateHash hash) {
        hash.mixLong(this.computeStateHash());
    }

    /**
     * Computes a deterministic hash over the represented simulation state.
     *
     * @return the state hash
     */
    public long computeStateHash() {
        PlayerRelations playerRelations = this.playerTable.getPlayerRelations();
        PlayerUpgrades playerUpgrades = this.playerTable.getPlayerUpgrades();
        UnitAccounting unitAccounting = this.playerTable.getUnitAccounting();
        ProductionStatistics productionStatistics = this.playerTable.getProductionStatistics();

        UnitPool unitPool = this.unitSystem.getUnitPool();
        UnitSpatialIndex unitSpatialIndex = this.unitSystem.getUnitSpatialIndex();
        UnitRegistry unitRegistry = this.unitSystem.getUnitRegistry();
        UnitCreationContext unitCreationContext = this.unitSystem.getUnitCreationContext();

        SpriteImagePool imagePool = this.spriteSystem.getImagePool();
        SpritePool spritePool = this.spriteSystem.getSpritePool();
        SpriteEffectPool spriteEffectPool = this.spriteSystem.getSpriteEffectPool();
        SpriteEffect startupSpriteEffect = this.spriteSystem.getStartupSpriteEffect();

        PathPool pathPool = this.pathSystem.getPathPool();
        LocalPathSearch localPathSearch = this.pathSystem.getLocalPathSearch();
        UnitPath unitPath = this.pathSystem.getUnitPath();
        TerrainContours terrainContours = this.pathSystem.getTerrainContours();

        byte[] lastErrorBytes = this.gameContext.getLastErrorBytes();

        long hash = STATE_HASH_OFFSET_BASIS;

        hash = mix(hash, this.getCurrentFrame());
        hash = mix(hash, this.turnCount);
        hash = mix(hash, this.cheatsEnabled ? 1 : 0);
        hash = mix(hash, this.psi.getFirstProviderIndex());
        hash = mix(hash, this.worldInitialized ? 1 : 0);

        hash = mix(hash, this.frame == null ? 0 : 1);

        if (this.frame != null) {
            hash = mix(hash, this.frame.computeStateHash(0));
        }

        hash = mix(hash, playerRelations == null ? 0 : 1);
        hash = mix(hash, pathPool == null ? 0 : 1);

        if (pathPool != null) {
            hash = mix(hash, pathPool.computeStateHash(0));
        }

        hash = mix(hash, localPathSearch == null ? 0 : 1);

        if (localPathSearch != null) {
            hash = mix(hash, localPathSearch.computeStateHash(0));
        }

        hash = mix(hash, unitPath == null || unitPath.getConsideredCollisionUnit() == null
                         ? 0
                         : unitPath.getConsideredCollisionUnit().getIndex());

        if (playerRelations != null) {
            hash = mix(hash, playerRelations.computeStateHash(0));
        }

        hash = mix(hash, this.triggerContext == null ? 0 : 1);

        if (this.triggerContext != null) {
            hash = mix(hash, this.triggerContext.computeStateHash(0));
        }

        MapStringSection mapStrings = this.interfaceLink.getMessageLines().getMapStringSection();

        if (mapStrings != null && mapStrings.getRegisteredStringCount() != 0) {
            hash = mix(hash, mapStrings.computeRegisteredStringHash(0));
        }

        hash = mix(hash, this.executionScope == null ? 0 : 1);

        if (this.executionScope != null) {
            hash = mix(hash, this.executionScope.isInitializationActive() ? 1 : 0);
            hash = mix(hash, this.executionScope.getFramePhase());
        }

        hash = mix(hash, playerUpgrades == null ? 0 : 1);

        if (playerUpgrades != null) {
            hash = mix(hash, playerUpgrades.computeStateHash(0));
        }

        hash = mix(hash, unitAccounting == null ? 0 : 1);

        if (unitAccounting != null) {
            hash = unitAccounting.computeStateHash(hash);
        }

        hash = mix(hash, unitRegistry == null ? 0 : 1);

        if (unitRegistry != null) {
            hash = mix(hash, unitRegistry.computeStateHash(0));
        }

        hash = mix(hash, productionStatistics == null ? 0 : 1);

        if (productionStatistics != null) {
            hash = mix(hash, productionStatistics.computeStateHash(0));
        }

        hash = mix(hash, this.foliage == null ? 0 : 1);

        if (this.foliage != null) {
            hash = mix(hash, this.foliage.computeStateHash(0));
        }

        hash = mix(hash, unitSpatialIndex == null ? 0 : 1);

        if (unitSpatialIndex != null) {
            hash = mix(hash, unitSpatialIndex.hasQueryBounds() ? 1 : 0);
            hash = mix(hash, unitSpatialIndex.getMinimumQueryDimension(true));
            hash = mix(hash, unitSpatialIndex.getMinimumQueryDimension(false));
            hash = mix(hash, unitSpatialIndex.getCount());

            for (int edgeIndex = 0; edgeIndex < unitSpatialIndex.getCount(); edgeIndex++) {
                hash = mix(hash, unitSpatialIndex.getUnitIndex(true, edgeIndex));
                hash = mix(hash, unitSpatialIndex.getCoordinate(true, edgeIndex));
                hash = mix(hash, unitSpatialIndex.getUnitIndex(false, edgeIndex));
                hash = mix(hash, unitSpatialIndex.getCoordinate(false, edgeIndex));
            }

            byte[] flyingCounts = unitSpatialIndex.getFlyingUnitCounts();

            hash = mix(hash, flyingCounts == null ? -1 : flyingCounts.length);

            if (flyingCounts != null) {
                for (byte count : flyingCounts) {
                    hash = mix(hash, Byte.toUnsignedInt(count));
                }
            }
        }

        hash = mix(hash, this.commandDispatchMarkerCount);
        hash = mix(hash, this.random.getState());
        hash = mix(hash, this.random.getEnabled());
        hash = mix(hash, this.random.getTotalRandomCounts());
        hash = mix(hash, this.updateTilesCountdown);

        for (int sourceIndex = 0; sourceIndex < LcgRandom.SOURCE_COUNT; sourceIndex++) {
            hash = mix(hash, this.random.getCallCount(sourceIndex));
        }

        for (int playerIndex = 0; playerIndex < PlayerTable.PLAYER_SLOTS; playerIndex++) {
            Player player = this.playerTable.getPlayer(playerIndex);

            if (player == null) {
                hash = mix(hash, -1);
            } else {
                hash = mix(hash, player.getCommandPlayerIndex());
                hash = mix(hash, player.getId());
                hash = mix(hash, player.getType());
                hash = mix(hash, player.getRace());
                hash = mix(hash, player.getTeam());
                hash = mix(hash, player.getVictoryState());
                hash = mix(hash, player.getMinerals());
                hash = mix(hash, player.getGatheredMinerals());
                hash = mix(hash, player.getGatheredGas());
                hash = mix(hash, player.getGas());
            }
        }

        hash = mix(hash, this.map.getWidth());
        hash = mix(hash, this.map.getHeight());
        hash = mix(hash, this.map.getTileset());

        if (this.gameContext.hasContextTilesetOverride()) {
            hash = mix(hash, this.gameContext.getContextTileset());
        }

        for (int tileIndex = 0; tileIndex < this.map.getTileCount(); tileIndex++) {
            hash = mix(hash, this.map.getTileIdentifier(tileIndex));
        }

        hash = mix(hash, this.map.hasTileFlags() ? 1 : 0);
        hash = mix(hash, this.map.hasOverlayTiles() ? this.map.getTileCount() : -1);

        if (this.map.hasOverlayTiles()) {
            for (int tileIndex = 0; tileIndex < this.map.getTileCount(); tileIndex++) {
                hash = mix(hash, this.map.getOverlayTileIdentifier(tileIndex));
            }
        }

        if (this.map.hasTileFlags()) {
            for (int tileIndex = 0; tileIndex < this.map.getTileFlagCount(); tileIndex++) {
                hash = mix(hash, this.map.getTileFlags(tileIndex));
            }
        }

        for (StartLocation startLocation : this.playerTable.getStartLocations()) {
            hash = mix(hash, startLocation.getOwner());
            hash = mix(hash, startLocation.getX());
            hash = mix(hash, startLocation.getY());
        }

        hash = mix(hash, unitPool == null ? 0 : 1);

        if (unitPool != null) {
            hash = mix(hash, unitPool.getCapacity());
            hash = mix(hash, unitPool.getFirstFreeIndex());
            hash = mix(hash, unitPool.getAllocatedCount());
            hash = mix(hash, unitPool.getFirstCreatedIndex(false));
            hash = mix(hash, unitPool.getFirstCreatedIndex(true));
            hash = mix(hash, unitPool.getFirstDeadIndex());
            hash = mix(hash, unitPool.getLastDeadIndex());
            hash = mix(hash, unitPool.getLastCreatedIndex(false));
            hash = mix(hash, unitPool.getLastCreatedIndex(true));

            for (int owner = 0; owner < PlayerTable.PLAYER_SLOTS; owner++) {
                hash = mix(hash, unitPool.getFirstOwnerIndex(owner));
            }

            for (int poolIndex = 1; poolIndex <= unitPool.getCapacity(); poolIndex++) {
                Unit unit = unitPool.getUnit(poolIndex);

                hash = mix(hash, unitPool.getGeneration(poolIndex));
                hash = mix(hash, unitPool.getNextFreeIndex(poolIndex));
                hash = mix(hash, unitPool.getPreviousOwnerIndex(poolIndex));
                hash = mix(hash, unitPool.getNextOwnerIndex(poolIndex));
                hash = mix(hash, unitPool.getCreationTag(poolIndex));
                hash = mix(hash, unitPool.getPreviousCreatedIndex(poolIndex));
                hash = mix(hash, unitPool.getNextCreatedIndex(poolIndex));
                hash = mix(hash, unitPool.isAllocated(poolIndex) ? 1 : 0);
                hash = unit.computeStateHash(hash);

                Sprite sprite = unit.getSpriteOrNull();
                Unit subunit = unit.getSubunit();

                hash = mix(hash, sprite == null ? -1 : sprite.getPoolIndex());
                hash = mix(hash, subunit == null ? 0 : subunit.getIndex());

                Unit targetUnit = unit.getOrderTargetUnit();
                Unit parentUnit = unit.getParentUnit();
                Unit movementTargetUnit = unit.getMovementTargetUnit();
                OrderPool.Order firstOrder = unit.getFirstOrder();
                OrderPool.Order lastOrder = unit.getLastOrder();

                hash = mix(hash, targetUnit == null ? 0 : targetUnit.getIndex());
                hash = mix(hash, parentUnit == null ? 0 : parentUnit.getIndex());
                hash = mix(hash, movementTargetUnit == null ? 0 : movementTargetUnit.getIndex());
                hash = mix(hash, unit.getPathIndex());
                hash = mix(hash, firstOrder == null ? -1 : firstOrder.getPoolIndex());
                hash = mix(hash, lastOrder == null ? -1 : lastOrder.getPoolIndex());
            }
        }

        hash = mix(hash, this.orderPool == null ? 0 : 1);
        hash = mix(hash, lastErrorBytes == null ? -1 : lastErrorBytes.length);
        hash = mix(hash, this.buildingPowerRefresh == null ? -1 : this.buildingPowerRefresh);

        if (lastErrorBytes != null) {
            for (byte value : lastErrorBytes) {
                hash = mix(hash, Byte.toUnsignedInt(value));
            }
        }

        hash = mix(hash, terrainContours == null ? 0 : 1);

        if (terrainContours != null) {
            for (int direction = 0; direction < TerrainContours.DIRECTION_COUNT; direction++) {
                int boundaryCount = terrainContours.getBoundaryCount(direction);

                hash = mix(hash, boundaryCount);

                for (int boundaryIndex = 0; boundaryIndex < boundaryCount; boundaryIndex++) {
                    hash = mixLong(hash, terrainContours.getBoundary(direction, boundaryIndex));
                }
            }

            for (int controlIndex = 0; controlIndex < TerrainContours.CONTROL_COUNT; controlIndex++) {
                hash = mix(hash, terrainContours.getControl(controlIndex));
            }

            hash = terrainContours.getRegions().computeStateHash(hash);
        }

        hash = mix(hash, this.unitStrengthTable == null ? 0 : 1);

        if (this.unitStrengthTable != null) {
            for (int direction = 0; direction < UnitStrengthTable.DIRECTION_COUNT; direction++) {
                for (int unitType = 0; unitType < ScrUnitIds.NONE; unitType++) {
                    hash = mix(hash, this.unitStrengthTable.getStrength(unitType, direction));
                }

                for (int added = 0; added < this.unitStrengthTable.getAddedCount(); added++) {
                    hash = mix(hash, this.unitStrengthTable.getStrength(ScrAddedUnitTypes.FIRST_ID + added, direction));
                }
            }
        }

        hash = mix(hash, this.sightCoverage == null ? 0 : 1);

        hash = mix(hash, this.creepField == null ? 0 : 1);

        if (this.creepField != null) {
            hash = mix(hash, this.creepField.computeStateHash(this.runtimeRandom.computeStateHash(0)));
        }

        hash = mix(hash, this.creepRecession == null ? 0 : 1);

        if (this.creepRecession != null) {
            hash = mix(hash, this.creepRecession.computeStateHash(0));
        }

        hash = mix(hash, this.resourceAreas == null ? 0 : 1);

        if (this.resourceAreas != null) {
            hash = this.resourceAreas.computeStateHash(hash);
        }

        hash = mix(hash, this.computerPlayerPhase == null ? 0 : 1);

        if (this.computerPlayerPhase != null) {
            hash = mix(hash, this.computerPlayerPhase.computeStateHash(0));
        }

        hash = mix(hash, unitCreationContext == null ? 0 : 1);

        if (unitCreationContext != null) {
            hash = mix(hash, unitCreationContext.computeStateHash(0));
        }

        if (this.sightCoverage != null) {
            for (int playerIndex = 0; playerIndex < PlayerTable.PLAYER_SLOTS; playerIndex++) {
                hash = mix(hash, this.sightCoverage.getSharedVisionMask(playerIndex));
            }

            for (int radiusIndex = 0; radiusIndex < SightCoverage.DESCRIPTOR_COUNT; radiusIndex++) {
                int count = this.sightCoverage.getRecordCount(radiusIndex);

                hash = mix(hash, count);

                for (int recordIndex = 0; recordIndex < count; recordIndex++) {
                    for (int fieldIndex = 0; fieldIndex < SightCoverage.RECORD_FIELD_COUNT; fieldIndex++) {
                        hash = mix(hash, this.sightCoverage.getRecordField(radiusIndex, recordIndex, fieldIndex));
                    }
                }
            }
        }

        if (this.orderPool != null) {
            hash = mix(hash, this.orderPool.getCapacity());
            hash = mix(hash, this.orderPool.getAllocatedCount());

            OrderPool.Order firstFreeOrder = this.orderPool.getFirstFreeOrder();
            OrderPool.Order lastFreeOrder = this.orderPool.getLastFreeOrder();

            hash = mix(hash, firstFreeOrder == null ? -1 : firstFreeOrder.getPoolIndex());
            hash = mix(hash, lastFreeOrder == null ? -1 : lastFreeOrder.getPoolIndex());

            for (int poolIndex = 0; poolIndex < this.orderPool.getCapacity(); poolIndex++) {
                OrderPool.Order order = this.orderPool.getOrder(poolIndex);
                OrderPool.Order previousOrder = order.getPrevious();
                OrderPool.Order nextOrder = order.getNext();
                Unit owner = order.getOwner();
                Unit targetUnit = order.getTargetUnit();

                hash = mix(hash, previousOrder == null ? -1 : previousOrder.getPoolIndex());
                hash = mix(hash, nextOrder == null ? -1 : nextOrder.getPoolIndex());
                hash = mix(hash, order.isAllocated() ? 1 : 0);
                hash = mix(hash, owner == null ? 0 : owner.getIndex());
                hash = mix(hash, order.getOrderId());
                hash = mix(hash, order.getUnitType());
                hash = mix(hash, order.getTargetX());
                hash = mix(hash, order.getTargetY());
                hash = mix(hash, targetUnit == null ? 0 : targetUnit.getIndex());
            }
        }

        hash = mix(hash, this.selection == null ? 0 : 1);

        if (this.selection != null) {
            hash = mixLong(hash, this.selection.computeStateHash());
        }

        hash = mix(hash, startupSpriteEffect == null ? -1 : startupSpriteEffect.getPoolIndex());

        hash = mix(hash, spriteEffectPool == null ? 0 : 1);

        if (spriteEffectPool != null) {
            hash = mix(hash, spriteEffectPool.getCapacity());
            hash = mix(hash, spriteEffectPool.getAllocatedCount());
            hash = mix(hash, spriteEffectPool.getFirstFreeIndex());
            hash = mix(hash, spriteEffectPool.getLastFreeIndex());
            hash = mix(hash, spriteEffectPool.getFirstActiveIndex());
            hash = mix(hash, spriteEffectPool.getLastActiveIndex());

            for (int poolIndex = 0; poolIndex < spriteEffectPool.getCapacity(); poolIndex++) {
                SpriteEffect spriteEffect = spriteEffectPool.getSpriteEffect(poolIndex);
                Sprite sprite = spriteEffect.getSpriteOrNull();

                hash = mix(hash, spriteEffectPool.getPreviousIndex(poolIndex));
                hash = mix(hash, spriteEffectPool.getNextIndex(poolIndex));
                hash = mix(hash, spriteEffectPool.isAllocated(poolIndex) ? 1 : 0);
                hash = mix(hash, spriteEffectPool.isActive(poolIndex) ? 1 : 0);
                hash = mix(hash, spriteEffect.getLife());
                hash = mix(hash, sprite == null ? -1 : sprite.getPoolIndex());
            }
        }

        hash = mix(hash, spritePool == null ? 0 : 1);

        if (spritePool != null) {
            hash = mix(hash, spritePool.getCapacity());
            hash = mix(hash, spritePool.getFirstFreeIndex());
            hash = mix(hash, spritePool.getAllocatedCount());

            for (int rowIndex = 0; rowIndex < GameMap.MAXIMUM_DIMENSION; rowIndex++) {
                hash = mix(hash, spritePool.getFirstRowIndex(rowIndex));
                hash = mix(hash, spritePool.getLastRowIndex(rowIndex));
            }

            for (int poolIndex = 0; poolIndex < spritePool.getCapacity(); poolIndex++) {
                Sprite sprite = spritePool.getSprite(poolIndex);

                hash = mix(hash, spritePool.isAllocated(poolIndex) ? 1 : 0);
                hash = mix(hash, spritePool.getNextFreeIndex(poolIndex));
                hash = mix(hash, spritePool.getNextRowIndex(poolIndex));
                hash = mix(hash, spritePool.getPreviousRowIndex(poolIndex));
                hash = mix(hash, spritePool.getRowIndex(poolIndex));
                hash = mix(hash, spritePool.isRegistered(poolIndex) ? 1 : 0);
                hash = mix(hash, spritePool.getRegistrationValue(poolIndex));
                hash = mix(hash, spritePool.hasFogMetadata(poolIndex) ? 1 : 0);

                if (spritePool.hasFogMetadata(poolIndex)) {
                    hash = mix(hash, spritePool.getFogMetadata(poolIndex));
                }

                hash = mix(hash, sprite.getSpriteTypeId());
                hash = mix(hash, sprite.getX());
                hash = mix(hash, sprite.getY());
                hash = mix(hash, sprite.getElevationLevel());
                hash = mix(hash, sprite.getVisibilityFlags());
                // The selection flags and the flash timer follow the local player, as the selection images do.
                hash = mix(hash, sprite.getFlags() & ~SelectionImages.LOCAL_SPRITE_FLAGS);
                hash = mix(hash, sprite.getOwner());
                hash = mix(hash, sprite.getWidth());
                hash = mix(hash, sprite.getHeight());

                hash = mix(hash, sprite.getMainImage() == null ? -1 : sprite.getMainImage().getIndex());

                // The selection images come from their own pools and follow the local player; a save leaves them out too.
                int imageCount = 0;

                for (SpriteImage image : sprite.getImages()) {
                    if (!SelectionImages.isSelectionImage(image)) {
                        imageCount++;
                    }
                }

                hash = mix(hash, imageCount);

                for (SpriteImage image : sprite.getImages()) {
                    if (!SelectionImages.isSelectionImage(image)) {
                        hash = mix(hash, image.getIndex());
                    }
                }
            }
        }

        hash = mix(hash, imagePool == null ? 0 : 1);

        if (imagePool != null) {
            hash = mix(hash, imagePool.getCapacity());
            hash = mix(hash, imagePool.getFirstFreeIndex());
            hash = mix(hash, imagePool.getAllocatedCount());

            for (int poolIndex = 0; poolIndex < imagePool.getCapacity(); poolIndex++) {
                SpriteImage image = imagePool.getImage(poolIndex);

                hash = mix(hash, imagePool.getNextFreeIndex(poolIndex));
                hash = mix(hash, imagePool.isAllocated(poolIndex) ? 1 : 0);
                hash = mix(hash, image.getImageTypeId());
                hash = mix(hash, image.hasGraphics() ? 1 : 0);
                hash = mix(hash, image.getScriptIdentifier());
                hash = mix(hash, image.getColorRemappingIndex());
                hash = mix(hash, image.getFrameIndex());
                hash = mix(hash, image.getFrameIndexOffset());
                hash = mix(hash, image.getOffsetX());
                hash = mix(hash, image.getOffsetY());
                hash = mix(hash, image.getFlags());
                hash = mix(hash, image.getModifier());
                hash = mix(hash, image.getModifierData1());
                hash = mix(hash, image.getModifierData2());
                hash = mix(hash, image.getAnimationType());
                hash = mix(hash, image.getProgramCounter());
                hash = mix(hash, image.getReturnAddress());
                hash = mix(hash, image.getWait());
            }
        }

        return hash;
    }
}
