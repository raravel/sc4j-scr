package org.fogofwarlabs.sc4j.scr.game.state;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.game.*;
import org.fogofwarlabs.sc4j.scr.content.ScrNativeIds;
import org.fogofwarlabs.sc4j.scr.core.GameSettings;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.game.path.PathPool;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Holds the explicit inputs a game is created from: the game data, the map, the scenario the map was built from, the archive of the map file,
 * the replay the game runs, the random generator in its initial state, the C runtime generator in its initial state, the setup seed, the game
 * options, the rules version, the cheat option, the pool limits and the players.
 * <p>
 * {@link Game#create(GameSetup)} creates the engine game of the setup ({@link #createEngineSetup()}), whose SC:R game
 * system applies the rules version, the cheat option, the limits (creating the object pools) and the players. The setup
 * seed and the game options are inputs of the ordered world initialization ({@link GameLoader}), which establishes them
 * in sequence; a setup alone does not run that initialization.
 * <p>
 * A setup is consumed by the one game created from it, not a value snapshot: the game uses the given game data, map,
 * random generators and players themselves and changes them as it runs, so after the game starts their state is the
 * game's, not the creation inputs. Only the limits and the slot assignment of the players are copied when the setup is
 * built. The game options are recorded for the world initialization; the game constructor does not read them.
 */
public final class GameSetup {
    /**
     * The number of pool limits, in native {@code LMTS} field order.
     */
    public static final int LIMIT_COUNT = 7;

    /**
     * The limit index of the image pool.
     */
    public static final int IMAGE_LIMIT = 0;
    /**
     * The limit index of the sprite pool.
     */
    public static final int SPRITE_LIMIT = 1;
    /**
     * The limit index of the sprite-effect (thingy) pool.
     */
    public static final int SPRITE_EFFECT_LIMIT = 2;
    /**
     * The limit index of the unit pool.
     */
    public static final int UNIT_LIMIT = 3;
    /**
     * The limit index of the bullet pool.
     */
    public static final int BULLET_LIMIT = 4;
    /**
     * The limit index of the order pool.
     */
    public static final int ORDER_LIMIT = 5;
    /**
     * The limit index of the secondary sprite-effect pool.
     */
    public static final int SECONDARY_SPRITE_EFFECT_LIMIT = 6;

    /**
     * The required engine setup option that carries the SC:R setup to the SC:R game system ({@link Game#SYSTEM_KEY}).
     */
    public static final @NotNull GameOption<GameSetup> OPTION = GameOption.required("scr.setup", GameSetup.class);

    /**
     * The mask of a native BYTE: the team, color and race of a player record.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * Builds {@link GameSetup} instances.
     */
    public static final class Builder {
        private final @Nullable GameData gameData;
        private final @NotNull GameMap map;
        private @Nullable ChkFile scenario;
        private @Nullable MpqArchive mapArchive;
        private @Nullable ReplayFile replay;
        private final @NotNull LcgRandom random;
        private @NotNull RuntimeRandom runtimeRandom;
        private int seed;

        private @Nullable GameSettings settings;
        private @NotNull GameRulesVersion rulesVersion;
        private int cheatsEnabled;

        private int @Nullable [] limits;
        private final @Nullable Player @NotNull [] players;

        /**
         * Creates a {@link Builder} instance with the given inputs, no scenario, map archive or replay and a C runtime generator that
         * nothing has seeded ({@link RuntimeRandom#DEFAULT_SEED}).
         *
         * @param gameData the game data tables, or {@code null} for a world built without game data
         * @param map      the map
         * @param random   the random generator in its initial state
         */
        public Builder(@Nullable GameData gameData, @NotNull GameMap map, @NotNull LcgRandom random) {
            this.gameData      = gameData;
            this.map           = map;
            this.scenario      = null;
            this.mapArchive    = null;
            this.replay        = null;
            this.random        = random;
            this.runtimeRandom = new RuntimeRandom(RuntimeRandom.DEFAULT_SEED);
            this.seed          = 0;

            this.settings      = null;
            this.rulesVersion  = GameRulesVersion.newGame();
            this.cheatsEnabled = 0;

            this.limits  = null;
            this.players = new Player[PlayerTable.PLAYER_SLOTS];
        }

        /**
         * Sets the scenario the map was built from.
         *
         * @param scenario the parsed scenario ({@code .chk}), which the game shares
         * @return this instance for chaining
         */
        public @NotNull Builder setScenario(@NotNull ChkFile scenario) {
            this.scenario = scenario;

            return this;
        }

        /**
         * Sets the archive of the map file the game starts from.
         *
         * @param mapArchive the opened map archive, which the game shares, or {@code null} for a game started otherwise
         * @return this instance for chaining
         */
        public @NotNull Builder setMapArchive(@Nullable MpqArchive mapArchive) {
            this.mapArchive = mapArchive;

            return this;
        }

        /**
         * Sets the replay the game runs: the recording a live game writes or the replay file it plays back.
         *
         * @param replay the replay, which the game shares
         * @return this instance for chaining
         */
        public @NotNull Builder setReplay(@NotNull ReplayFile replay) {
            this.replay = replay;

            return this;
        }

        /**
         * Sets the C runtime generator in the state the game start established.
         *
         * @param runtimeRandom the C runtime generator, which the game then shares
         * @return this instance for chaining
         */
        public @NotNull Builder setRuntimeRandom(@NotNull RuntimeRandom runtimeRandom) {
            this.runtimeRandom = runtimeRandom;

            return this;
        }

        /**
         * Sets the setup seed of the replay header or lobby.
         *
         * @param seed the seed
         * @return this instance for chaining
         */
        public @NotNull Builder setSeed(int seed) {
            this.seed = seed;

            return this;
        }

        /**
         * Sets the game options.
         *
         * @param settings the options
         * @return this instance for chaining
         */
        public @NotNull Builder setSettings(@NotNull GameSettings settings) {
            this.settings = settings;

            return this;
        }

        /**
         * Sets the rules version.
         *
         * @param rulesVersion the rules version
         * @return this instance for chaining
         */
        public @NotNull Builder setRulesVersion(@NotNull GameRulesVersion rulesVersion) {
            this.rulesVersion = rulesVersion;

            return this;
        }

        /**
         * Sets the cheat option byte.
         *
         * @param cheatsEnabled the option byte, which must equal {@code 1} to permit cheats
         * @return this instance for chaining
         */
        public @NotNull Builder setCheatsEnabled(int cheatsEnabled) {
            this.cheatsEnabled = cheatsEnabled;

            return this;
        }

        /**
         * Sets the pool limits; the game creates its image, sprite, sprite-effect, unit and order pools with them.
         *
         * @param limits the seven limits in native field order, copied
         * @return this instance for chaining
         * @throws IllegalArgumentException if there are not seven limits
         */
        public @NotNull Builder setLimits(int @NotNull [] limits) {
            if (limits.length != LIMIT_COUNT) {
                throw Exceptions.newIllegalArgumentException("A game has {0} pool limits, not {1}.", LIMIT_COUNT, limits.length);
            }

            this.limits = limits.clone();

            return this;
        }

        /**
         * Sets the player of a slot.
         *
         * @param slotIdentifier the slot identifier
         * @param player         the player
         * @return this instance for chaining
         */
        public @NotNull Builder setPlayer(int slotIdentifier, @NotNull Player player) {
            this.players[slotIdentifier] = player;

            return this;
        }


        /**
         * Builds a {@link GameSetup} instance from the values set so far.
         *
         * @return the created {@link GameSetup} instance
         */
        public @NotNull GameSetup build() {
            return new GameSetup(this);
        }
    }

    /**
     * Creates a {@link GameSetup} instance with only the data, the map and the random generator: no scenario, map archive or replay, an
     * unseeded C runtime generator, no options, a new game's rules version, cheats disabled, no pools and no players.
     *
     * @param gameData the game data tables, or {@code null} for a world built without game data
     * @param map      the map
     * @param random   the random generator in its initial state
     * @return the created {@link GameSetup} instance
     */
    public static @NotNull GameSetup of(@Nullable GameData gameData, @NotNull GameMap map, @NotNull LcgRandom random) {
        return new Builder(gameData, map, random).build();
    }

    /**
     * Creates an {@link EngineLimits} instance with the engine pool sizes of seven native pool limits: units, sprites, images, orders and
     * bullets (the projectiles) from the limits, and the fixed path record count. The two sprite effect limits stay SC:R limits.
     *
     * @param limits the seven pool limits in native field order
     * @return the created {@link EngineLimits} instance
     * @throws IllegalArgumentException if a limit is outside the engine range
     */
    public static @NotNull EngineLimits createEngineLimits(int @NotNull [] limits) {
        return new EngineLimits.Builder().setUnits(limits[UNIT_LIMIT])
                                         .setSprites(limits[SPRITE_LIMIT])
                                         .setImages(limits[IMAGE_LIMIT])
                                         .setOrders(limits[ORDER_LIMIT])
                                         .setPaths(PathPool.RECORD_COUNT)
                                         .setProjectiles(limits[BULLET_LIMIT])
                                         .build();
    }

    private static @NotNull ControllerKind getControllerKind(int playerType) {
        return switch (playerType) {
            case PlayerTypes.HUMAN, PlayerTypes.EITHER_PREFER_HUMAN, PlayerTypes.OBSERVER, PlayerTypes.PLAYER_LEFT -> ControllerKind.HUMAN;
            case PlayerTypes.COMPUTER, PlayerTypes.EITHER_PREFER_COMPUTER, PlayerTypes.COMPUTER_LEFT -> ControllerKind.COMPUTER;
            default -> ControllerKind.NEUTRAL;
        };
    }

    private final @Nullable GameData gameData;
    private final @NotNull GameMap map;
    private final @Nullable ChkFile scenario;
    private final @Nullable MpqArchive mapArchive;
    private final @Nullable ReplayFile replay;
    private final @NotNull LcgRandom random;
    private final @NotNull RuntimeRandom runtimeRandom;
    private final int seed;

    private final @Nullable GameSettings settings;
    private final @NotNull GameRulesVersion rulesVersion;
    private final int cheatsEnabled;

    private final int @Nullable [] limits;
    private final @Nullable Player @NotNull [] players;

    /**
     * Creates a {@link GameSetup} instance, copying the builder's limits and player slots.
     *
     * @param builder the established creation inputs
     */
    private GameSetup(@NotNull Builder builder) {
        this.gameData      = builder.gameData;
        this.map           = builder.map;
        this.scenario      = builder.scenario;
        this.mapArchive    = builder.mapArchive;
        this.replay        = builder.replay;
        this.random        = builder.random;
        this.runtimeRandom = builder.runtimeRandom;
        this.seed          = builder.seed;

        this.settings      = builder.settings;
        this.rulesVersion  = builder.rulesVersion;
        this.cheatsEnabled = builder.cheatsEnabled;

        this.limits  = builder.limits == null ? null : builder.limits.clone();
        this.players = builder.players.clone();
    }

    /**
     * @return the game data tables, or {@code null} for a setup built without game data
     */
    public @Nullable GameData getGameData() {
        return this.gameData;
    }

    /**
     * @return the map
     */
    public @NotNull GameMap getMap() {
        return this.map;
    }

    /**
     * Returns the scenario ({@code .chk}) the map was built from; for a saved game, the scenario built from the save.
     *
     * @return the scenario, or {@code null} for a setup without one (previews and synthetic test worlds)
     */
    public @Nullable ChkFile getScenario() {
        return this.scenario;
    }

    /**
     * Returns the archive of the map file a live game starts from (single player, a network host, or a joiner with the map it received), which
     * holds the scenario. The archive holds the whole file content, shared with the game's start and not copied.
     *
     * @return the archive, or {@code null} for a replay, a saved game, a campaign mission, a raw scenario file or a setup without one
     */
    public @Nullable MpqArchive getMapArchive() {
        return this.mapArchive;
    }

    /**
     * Reads a mod section of the replay the game plays back ({@link ReplayFile#readModSection}), which a game of the same mod wrote
     * ({@link Game#setReplaySection}). Each call decodes the section anew. A live game, whose own recording holds no mod section, and a setup
     * without a replay read none.
     *
     * @param tag the section tag: four printable ASCII characters other than SC:R's own tags ({@link ReplayFile#isModSectionTag})
     * @return the section data, or {@code null} if the replay has no section of the tag
     * @throws IOException              if the section is malformed or fails its checksum
     * @throws IllegalArgumentException if the tag is not a mod section tag
     */
    public byte @Nullable [] readReplaySection(@NotNull String tag) throws IOException {
        if (this.replay == null) {
            if (!ReplayFile.isModSectionTag(tag)) {
                throw Exceptions.newIllegalArgumentException("\"{0}\" is not a mod section tag.", tag);
            }

            return null;
        }

        return this.replay.readModSection(tag);
    }

    /**
     * @return the random generator in the state the caller established (state, enabled scope and call counts); the game
     * continues from it and advances this same instance
     */
    public @NotNull LcgRandom getRandom() {
        return this.random;
    }

    /**
     * @return the C runtime generator in the state the game start established; the game continues from it and advances
     * this same instance
     */
    public @NotNull RuntimeRandom getRuntimeRandom() {
        return this.runtimeRandom;
    }

    /**
     * @return the setup seed of the replay header or lobby, independent of the random generator's state (foliage and map
     * object initialization read it)
     */
    public int getSeed() {
        return this.seed;
    }

    /**
     * @return the game options recorded for the world initialization, or {@code null} when the game has none (tests and
     * previews); the game constructor does not read them
     */
    public @Nullable GameSettings getSettings() {
        return this.settings;
    }

    /**
     * @return the rules version the game runs with
     */
    public @NotNull GameRulesVersion getRulesVersion() {
        return this.rulesVersion;
    }

    /**
     * @return the cheat option byte ({@code 1} permits cheats)
     */
    public int getCheatsEnabled() {
        return this.cheatsEnabled;
    }

    /**
     * @return a copy of the seven pool limits in native field order, or {@code null} when the game creates no pools
     */
    public int @Nullable [] getLimits() {
        return this.limits == null ? null : this.limits.clone();
    }

    /**
     * @param slotIdentifier the slot identifier
     * @return the player of the slot, or {@code null} for an empty slot; the game installs and changes this same instance
     */
    public @Nullable Player getPlayer(int slotIdentifier) {
        return this.players[slotIdentifier];
    }


    /**
     * Creates an engine {@link org.fogofwarlabs.sc4j.engine.game.GameSetup} instance for the game created from this setup: a map
     * reference without name or digest (the SC:R map is this setup's {@link GameMap}), a player slot for every player record whose type
     * is neither none nor closed, the setup seed, this setup's random generator, which the game adopts, the pool sizes of the limits
     * ({@code null} when the game creates no pools) and this setup as the value of {@link #OPTION}.
     *
     * @return the created engine {@link org.fogofwarlabs.sc4j.engine.game.GameSetup} instance
     */
    public org.fogofwarlabs.sc4j.engine.game.@NotNull GameSetup createEngineSetup() {
        MapReference map = new MapReference("", new byte[0]);
        org.fogofwarlabs.sc4j.engine.game.GameSetup.Builder setup = new org.fogofwarlabs.sc4j.engine.game.GameSetup.Builder(map);

        for (int slotIdentifier = 0; slotIdentifier < this.players.length; slotIdentifier++) {
            Player player = this.players[slotIdentifier];

            if (player != null && player.getType() != PlayerTypes.NONE && player.getType() != PlayerTypes.CLOSED) {
                setup.addPlayer(new PlayerSlot(slotIdentifier, getControllerKind(player.getType()), player.getTeam() & BYTE_MASK,
                                               player.getColor() & BYTE_MASK, ScrNativeIds.raceOf(player.getRace() & BYTE_MASK)));
            }
        }

        return setup.setSeed(this.seed)
                    .setRandom(this.random)
                    .setLimits(this.limits == null ? null : createEngineLimits(this.limits))
                    .setOptions(new GameOptions.Builder().set(OPTION, this).build())
                    .build();
    }
}
