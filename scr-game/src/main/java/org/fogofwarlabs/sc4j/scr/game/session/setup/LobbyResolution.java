package org.fogofwarlabs.sc4j.scr.game.session.setup;

import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * Resolves a lobby table into the player records at game start.
 */
public final class LobbyResolution {
    /**
     * The lobby type of a computer before normalization.
     */
    public static final int TYPE_COMPUTER = 5;
    /**
     * The lobby type of an open slot.
     */
    public static final int TYPE_OPEN = 6;
    /**
     * The lobby type of a closed slot.
     */
    public static final int TYPE_CLOSED = 8;
    /**
     * A lobby color byte that names no color.
     */
    public static final int COLOR_NONE = 0x16;
    /**
     * The number of network colors.
     */
    public static final int COLOR_IDS = 22;
    /**
     * The game type of Use Map Settings, whose custom colors follow the host's flag.
     */
    public static final int GAME_TYPE_USE_MAP_SETTINGS = GameTemplate.TYPE_USE_MAP_SETTINGS;
    /**
     * The race byte of a selectable ({@code SIDE} value {@code 5}) lobby slot, which the race request also accepts. The game start turns
     * it into {@link ScrRaceIds#RANDOM} before it draws the random races; the same byte value means {@link ScrRaceIds#INACTIVE} in a map
     * or player record.
     */
    public static final int SELECTABLE_RACE = 7;

    private static final int RACE_COUNT = ScrRaceIds.PLAYABLE_COUNT;
    private static final int LAST_PLAYABLE_RACE = RACE_COUNT - 1;
    private static final int RACE_ZERG = ScrRaceIds.ZERG;
    private static final int RACE_TERRAN = ScrRaceIds.TERRAN;
    private static final int RACE_RANDOM = ScrRaceIds.RANDOM;
    private static final int RACE_POOL_SIZE = 2 * PlayerTable.PLAYABLE_PLAYER_SLOTS;
    private static final int RACE_POOL_SWAPS = 16;
    private static final int SECOND_RACE_CHANCE_BOUND = 7;
    private static final int SECOND_RACE_CHANCE_TRIGGER = 4;
    private static final int SECOND_RANDOM_PLAYER = 2;
    private static final int THIRD_RANDOM_PLAYER = 3;
    private static final int SIXTH_RANDOM_PLAYER = 6;

    private static final int RANDOM_START_FORCE_FLAG = 1;
    private static final int GAME_TYPE_MASK = 0xFF;

    private static final int TILESET_PLATFORM = 1;
    private static final int TILESET_ASHWORLD = 3;
    private static final int TILESET_DESERT = 5;
    private static final int TILESET_ICE = 6;
    private static final int TILESET_TWILIGHT = 7;
    private static final int TILESET_MASK = 7;

    private static final int COLOR_DARK_GRAY = 21;
    private static final int COLOR_GRAY = 20;
    private static final int COLOR_ORANGE = 4;
    private static final int COLOR_WHITE = 6;
    private static final int COLOR_PALE_BLUE = 13;
    private static final int COLOR_RESERVED = 14;
    private static final int @NotNull [] @NotNull [] SIMILAR_COLORS = {{1, 13, 18}, {2, 8, 12}, {7, 9, 17}};

    /**
     * The network colors' RGB values, indexed by color id.
     */
    private static final int @NotNull [] COLOR_RGB = {
            0xF40404, 0x0C48CC, 0x2CB494, 0x88409C, 0xF88C14, 0x703014, 0xCCE0D0, 0xFCFC38, 0x088008, 0xFCFC7C, 0xECC4B0,
            0x4068D4, 0x74A47C, 0x7290B8, 0x00E4FC, 0xFFC4E4, 0x808000, 0xD2F53C, 0x000080, 0xF032E6, 0x808080, 0x3C3C3C
    };

    private static final int SOURCE_RACE = 144;
    private static final int SOURCE_SLOT = 33;
    private static final int SOURCE_COLOR = 28;
    private static final int SOURCE_TEAM_LEADER = 32;

    /**
     * Holds the game options used by the resolution.
     */
    public static final class Options {
        private final boolean network;
        private final boolean useMapSettings;
        private final int teams;
        private final boolean fixedStartPositions;
        private final boolean tournament;

        /**
         * Creates an {@link Options} instance.
         *
         * @param network             whether the game is a network game
         * @param useMapSettings      whether the game uses map settings
         * @param teams               the teams option
         * @param fixedStartPositions whether start positions are fixed
         * @param tournament          whether the game is a tournament
         */
        public Options(boolean network, boolean useMapSettings, int teams, boolean fixedStartPositions, boolean tournament) {
            this.network             = network;
            this.useMapSettings      = useMapSettings;
            this.teams               = teams;
            this.fixedStartPositions = fixedStartPositions;
            this.tournament          = tournament;
        }

        /**
         * @return {@code true} if the game is a network game, {@code false} otherwise
         */
        public boolean isNetwork() {
            return this.network;
        }

        /**
         * @return {@code true} if the game uses map settings, {@code false} otherwise
         */
        public boolean isUseMapSettings() {
            return this.useMapSettings;
        }

        /**
         * @return the teams option
         */
        public int getTeams() {
            return this.teams;
        }

        /**
         * @return {@code true} if start positions are fixed, {@code false} otherwise
         */
        public boolean hasFixedStartPositions() {
            return this.fixedStartPositions;
        }

        /**
         * @return {@code true} if the game is a tournament, {@code false} otherwise
         */
        public boolean isTournament() {
            return this.tournament;
        }

        /**
         * @return {@code true} if the game is a network team game, {@code false} otherwise
         */
        public boolean isTeamGame() {
            return this.network && this.teams != 0;
        }
    }

    /**
     * Holds the live and template records and the map and lobby inputs, indexed by slot.
     */
    public static final class Table {
        /**
         * The records' player ids ({@code -1} none).
         */
        public final int @NotNull [] playerId;
        /**
         * The records' lobby types.
         */
        public final int @NotNull [] type;
        /**
         * The records' races.
         */
        public final int @NotNull [] race;
        /**
         * The records' forces.
         */
        public final int @NotNull [] team;
        /**
         * The records' names.
         */
        public final @NotNull String @NotNull [] name;
        /**
         * The template types a reset restores.
         */
        public final int @NotNull [] templateType;
        /**
         * The template races a reset restores.
         */
        public final int @NotNull [] templateRace;
        /**
         * The template forces a reset restores.
         */
        public final int @NotNull [] templateTeam;
        /**
         * The saved template types a team game restores.
         */
        public final int @NotNull [] savedType;
        /**
         * The Use Map Settings slots whose scenario race was selectable.
         */
        public final boolean @NotNull [] selectableRace;
        /**
         * The force flag bytes; bit {@code 0} selects random start locations.
         */
        public final int @NotNull [] forceFlags;
        /**
         * The lobby colors, {@link #COLOR_NONE} when none was chosen.
         */
        public final int @NotNull [] lobbyColor;
        /**
         * The color locks.
         */
        public final boolean @NotNull [] colorLock;
        /**
         * The slots kept out of the color pass.
         */
        public final boolean @NotNull [] colorKept;
        /**
         * The packed {@code 0xRRGGBB} map color of each slot whose {@code CRGB} section fixes its own color, {@code -1} for the others.
         */
        public final int @NotNull [] mapColor;
        /**
         * The observer records' player ids ({@code -1} none), the record of observer slot {@link PlayerTable#FIRST_OBSERVER_SLOT} first.
         * The observers take no part in the resolution.
         */
        public final int @NotNull [] observerId;
        /**
         * The observer records' names.
         */
        public final @NotNull String @NotNull [] observerName;

        /**
         * The custom color flag sent by the host, set by the map custom colors section.
         */
        public boolean customColorFlag;
        /**
         * The map tileset.
         */
        public int tileset;

        /**
         * Creates a {@link Table} instance that is empty: no players, colors {@link #COLOR_NONE}.
         */
        public Table() {
            this.playerId       = new int[PlayerTable.PLAYER_SLOTS];
            this.type           = new int[PlayerTable.PLAYER_SLOTS];
            this.race           = new int[PlayerTable.PLAYER_SLOTS];
            this.team           = new int[PlayerTable.PLAYER_SLOTS];
            this.name           = new String[PlayerTable.PLAYER_SLOTS];
            this.templateType   = new int[PlayerTable.PLAYER_SLOTS];
            this.templateRace   = new int[PlayerTable.PLAYER_SLOTS];
            this.templateTeam   = new int[PlayerTable.PLAYER_SLOTS];
            this.savedType      = new int[PlayerTable.PLAYER_SLOTS];
            this.selectableRace = new boolean[PlayerTable.PLAYABLE_PLAYER_SLOTS];
            this.forceFlags     = new int[PlayerTable.FORCE_COUNT];
            this.lobbyColor     = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
            this.colorLock      = new boolean[PlayerTable.PLAYABLE_PLAYER_SLOTS];
            this.colorKept      = new boolean[PlayerTable.PLAYABLE_PLAYER_SLOTS];
            this.mapColor       = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
            this.observerId     = new int[PlayerTable.OBSERVER_SLOTS];
            this.observerName   = new String[PlayerTable.OBSERVER_SLOTS];

            this.customColorFlag = false;
            this.tileset         = 0;

            Arrays.fill(this.playerId, -1);
            Arrays.fill(this.name, "");
            Arrays.fill(this.lobbyColor, COLOR_NONE);
            Arrays.fill(this.mapColor, -1);
            Arrays.fill(this.observerId, -1);
            Arrays.fill(this.observerName, "");
        }
    }

    /**
     * Holds the resolved records, colors, command slots and races.
     */
    public static final class Result {
        private final MeleeGameSetup.@NotNull PlayerRecord @NotNull [] records;
        private final int @NotNull [] colors;
        private final int @NotNull [] colorIds;
        private final int @NotNull [] customColors;
        private final int @NotNull [] lobbyColors;
        private final int @NotNull [] commandSlots;
        private final int @NotNull [] slotRaces;

        /**
         * Creates a {@link Result} instance.
         *
         * @param records      the twelve final player records
         * @param colors       the eight color permutation entries
         * @param colorIds     the network color ids, {@code -1} where none was given
         * @param customColors the packed {@code 0xRRGGBB} custom colors, {@code -1} where none was given
         * @param lobbyColors  the lobby colors after placement
         * @param commandSlots the player slots whose units the commands act on
         * @param slotRaces    the lobby slot races before placement
         */
        public Result(MeleeGameSetup.@NotNull PlayerRecord @NotNull [] records,
                      int @NotNull [] colors, int @NotNull [] colorIds, int @NotNull [] customColors, int @NotNull [] lobbyColors,
                      int @NotNull [] commandSlots,
                      int @NotNull [] slotRaces) {
            this.records      = records;
            this.colors       = colors;
            this.colorIds     = colorIds;
            this.customColors = customColors;
            this.lobbyColors  = lobbyColors;
            this.commandSlots = commandSlots;
            this.slotRaces    = slotRaces;
        }

        /**
         * @return the twelve final player records
         */
        public MeleeGameSetup.@NotNull PlayerRecord @NotNull [] getRecords() {
            return this.records;
        }

        /**
         * @return the eight color permutation entries
         */
        public int @NotNull [] getColors() {
            return this.colors;
        }

        /**
         * @return the network color ids, {@code -1} where none was given
         */
        public int @NotNull [] getColorIds() {
            return this.colorIds;
        }

        /**
         * Returns the custom colors of the eight final slots (the replay's {@code CCLR}): a slot's network color, else the map color of a slot
         * whose {@code CRGB} section fixes its own color.
         *
         * @return the packed {@code 0xRRGGBB} custom colors, {@code -1} where none was given
         */
        public int @NotNull [] getCustomColors() {
            return this.customColors;
        }

        /**
         * @return the lobby colors after placement
         */
        public int @NotNull [] getLobbyColors() {
            return this.lobbyColors;
        }

        /**
         * @return the player slots whose units the commands act on
         */
        public int @NotNull [] getCommandSlots() {
            return this.commandSlots;
        }

        /**
         * @return the lobby slot races before placement
         */
        public int @NotNull [] getSlotRaces() {
            return this.slotRaces;
        }
    }

    /**
     * The live records being placed.
     */
    private static final class Slots {
        private static final int RECORD_PLAYER_ID = 0;
        private static final int RECORD_TYPE = 1;
        private static final int RECORD_RACE = 2;
        private static final int RECORD_TEAM = 3;
        private static final int RECORD_COLOR_ID = 4;
        private static final int RECORD_LOBBY_COLOR = 5;

        private final @NotNull Table table;
        private final int @NotNull [] playerId;
        private final int @NotNull [] type;
        private final int @NotNull [] race;
        private final int @NotNull [] team;
        private final @NotNull String @NotNull [] name;
        private final int @NotNull [] lobbyColor;
        private final int @NotNull [] colorId;

        private final int @NotNull [] templateType;
        /**
         * The slot in which each force's leader was placed.
         */
        private final int @NotNull [] leaderSlot;

        Slots(@NotNull Table table,
              int @NotNull [] playerId, int @NotNull [] type, int @NotNull [] race, int @NotNull [] team,
              @NotNull String @NotNull [] name,
              int @NotNull [] lobbyColor, int @NotNull [] colorId) {
            this.table      = table;
            this.playerId   = playerId;
            this.type       = type;
            this.race       = race;
            this.team       = team;
            this.name       = name;
            this.lobbyColor = lobbyColor;
            this.colorId    = colorId;

            this.templateType = table.templateType.clone();
            this.leaderSlot   = new int[PlayerTable.FORCE_COUNT];

            Arrays.fill(this.leaderSlot, -1);
        }


        /**
         * Places the players of the masked slots at random masked slots: their records and names are taken out, every masked
         * slot is reset to the map template, and each player, the last first, takes a random masked slot whose type then lies
         * in {@link PlayerTypes#RESCUE_PASSIVE} through {@link PlayerTypes#NEUTRAL}. Afterwards every slot without a player type
         * becomes {@link PlayerTypes#NONE}, and slot {@code i} takes the lobby color of the {@code i}-th taken player, or
         * {@link #COLOR_NONE} past the last one.
         *
         * @param random the game's random source
         * @param mask   the slots that take part
         */
        void assignStartSlots(@NotNull LcgRandom random, boolean @NotNull [] mask) {
            List<int[]> players = new ArrayList<>();
            List<String> names = new ArrayList<>();

            for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                if (mask[slot] && isPlayerType(this.type[slot])) {
                    players.add(this.readSlotRecord(slot));
                    names.add(this.name[slot]);
                }
            }

            for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                if (mask[slot]) {
                    this.resetSlot(slot);
                }
            }

            int[] candidates = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];

            for (int index = players.size() - 1; index >= 0; index--) {
                int count = 0;

                for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                    if (mask[slot] && this.type[slot] >= PlayerTypes.RESCUE_PASSIVE && this.type[slot] <= PlayerTypes.NEUTRAL) {
                        candidates[count++] = slot;
                    }
                }

                int slot = candidates[random.next(SOURCE_SLOT, 0, count - 1)];

                this.writeSlotRecord(slot, players.get(index), names.get(index));
            }

            for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                if (!isPlayerType(this.type[slot])) {
                    this.type[slot] = PlayerTypes.NONE;
                }

                this.lobbyColor[slot] = slot < players.size() ? players.get(slot)[RECORD_LOBBY_COLOR] : COLOR_NONE;
            }
        }

        /**
         * Places the players of a team game. The saved template types become the template; the leader of each force (its
         * human or computer with the lowest unsigned player id, the lowest slot on a tie) is taken out, the remaining computer
         * slots are dropped, the remaining player slots are taken out, and every slot is reset to the template. The leaders, in
         * force order, then take the first slot, or a random one without fixed start positions, whose type lies in
         * {@link PlayerTypes#RESCUE_PASSIVE} through {@link PlayerTypes#NEUTRAL}, while one is left; the other players take the
         * first slots without a player type, and the slots left without one become {@link PlayerTypes#NONE}.
         *
         * @param random  the game's random source
         * @param options the game options
         */
        void placeTeams(@NotNull LcgRandom random, @NotNull Options options) {
            System.arraycopy(this.table.savedType, 0, this.templateType, 0, PlayerTable.PLAYER_SLOTS);

            List<int[]> leaders = new ArrayList<>();
            List<String> leaderNames = new ArrayList<>();

            for (int force = 1; force <= PlayerTable.FORCE_COUNT; force++) {
                int leader = -1;

                for (int slot = PlayerTable.PLAYABLE_PLAYER_SLOTS - 1; slot >= 0; slot--) {
                    if (this.team[slot] == force && (this.type[slot] == PlayerTypes.HUMAN || this.type[slot] == PlayerTypes.COMPUTER) &&
                        (leader < 0 || Integer.compareUnsigned(this.playerId[slot], this.playerId[leader]) <= 0)) {
                        leader = slot;
                    }
                }

                if (leader >= 0) {
                    leaders.add(this.readSlotRecord(leader));
                    leaderNames.add(this.name[leader]);
                    this.resetSlot(leader);
                }
            }

            List<int[]> others = new ArrayList<>();
            List<String> otherNames = new ArrayList<>();

            for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                if (this.type[slot] == PlayerTypes.COMPUTER) {
                    this.type[slot] = PlayerTypes.NONE;
                } else if (isPlayerType(this.type[slot])) {
                    others.add(this.readSlotRecord(slot));
                    otherNames.add(this.name[slot]);
                }
            }

            for (int slot = PlayerTable.PLAYER_SLOTS - 1; slot >= 0; slot--) {
                this.resetSlot(slot);
            }

            for (int index = 0; index < leaders.size(); index++) {
                List<Integer> candidates = new ArrayList<>();

                for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                    if (this.type[slot] >= PlayerTypes.RESCUE_PASSIVE && this.type[slot] <= PlayerTypes.NEUTRAL) {
                        candidates.add(slot);
                    }
                }

                if (candidates.isEmpty()) {
                    break;
                }

                int slot = options.hasFixedStartPositions()
                           ? candidates.getFirst()
                           : candidates.get(random.next(SOURCE_TEAM_LEADER, 0, candidates.size() - 1));

                this.writeSlotRecord(slot, leaders.get(index), leaderNames.get(index));

                int force = leaders.get(index)[RECORD_TEAM];

                if (force >= 1 && force <= PlayerTable.FORCE_COUNT) {
                    this.leaderSlot[force - 1] = slot;
                }
            }

            for (int index = 0; index < others.size(); index++) {
                for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                    if (!isPlayerType(this.type[slot])) {
                        this.writeSlotRecord(slot, others.get(index), otherNames.get(index));

                        break;
                    }
                }
            }

            for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                if (!isPlayerType(this.type[slot])) {
                    this.type[slot] = PlayerTypes.NONE;
                }
            }
        }

        /**
         * Restores one slot from the retained map template.
         *
         * @param slot the lobby slot
         */
        private void resetSlot(int slot) {
            this.playerId[slot] = -1;
            this.type[slot]     = this.templateType[slot];
            this.race[slot]     = this.table.templateRace[slot];
            this.team[slot]     = this.table.templateTeam[slot];
            this.name[slot]     = "";
        }

        /**
         * Copies the six placement fields of one slot.
         *
         * @param slot the lobby slot
         * @return the copied placement fields
         */
        private int @NotNull [] readSlotRecord(int slot) {
            return new int[]{this.playerId[slot], this.type[slot], this.race[slot], this.team[slot], this.colorId[slot], this.lobbyColor[slot]};
        }

        /**
         * Restores the placement fields and name of one slot.
         *
         * @param slot       the destination lobby slot
         * @param player     the copied placement fields
         * @param playerName the copied player name
         */
        private void writeSlotRecord(int slot, int @NotNull [] player, @NotNull String playerName) {
            this.playerId[slot] = player[RECORD_PLAYER_ID];
            this.type[slot]     = player[RECORD_TYPE];
            this.race[slot]     = player[RECORD_RACE];
            this.team[slot]     = player[RECORD_TEAM];
            this.colorId[slot]  = player[RECORD_COLOR_ID];
            this.name[slot]     = playerName;
        }
    }

    /**
     * @param colorId a network color id, {@code 0..21}
     * @return its RGB value, or {@code -1} for another id
     */
    public static int getColorRgb(int colorId) {
        return colorId >= 0 && colorId < COLOR_RGB.length ? COLOR_RGB[colorId] : -1;
    }

    /**
     * Resolves the table with the seed.
     *
     * @param table   the lobby table; not changed
     * @param options the game options
     * @param seed    the game seed
     * @return the result
     */
    public static @NotNull Result resolve(@NotNull Table table, @NotNull Options options, int seed) {
        LcgRandom random = new LcgRandom(seed);
        int[] playerId = table.playerId.clone();
        int[] type = table.type.clone();
        int[] race = table.race.clone();
        int[] team = table.team.clone();
        String[] name = table.name.clone();
        int[] lobbyColor = table.lobbyColor.clone();
        int[] colorId = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        Arrays.fill(colorId, -1);

        resolveRandomRaces(random, type, race, team, options.isTeamGame());

        int[] slotRaces = Arrays.copyOf(race, PlayerTable.PLAYABLE_PLAYER_SLOTS);

        for (int slot = PlayerTable.PLAYER_SLOTS - 1; slot >= 0; slot--) {
            if (type[slot] == TYPE_COMPUTER) {
                type[slot] = PlayerTypes.COMPUTER;
            } else if (type[slot] == TYPE_OPEN || type[slot] == TYPE_CLOSED) {
                type[slot] = PlayerTypes.NONE;
            }
        }

        if (options.isNetwork() && !options.isTournament()) {
            assignNetworkColors(random, table, type, lobbyColor, colorId);
        }

        Slots slots = new Slots(table, playerId, type, race, team, name, lobbyColor, colorId);
        boolean randomStartForce = false;

        if (options.isTeamGame()) {
            slots.placeTeams(random, options);
        } else if (options.isUseMapSettings()) {
            for (int force = 1; force <= PlayerTable.FORCE_COUNT; force++) {
                if ((table.forceFlags[force - 1] & RANDOM_START_FORCE_FLAG) == 0) {
                    continue;
                }

                boolean[] selectable = new boolean[PlayerTable.PLAYABLE_PLAYER_SLOTS];
                boolean[] others = new boolean[PlayerTable.PLAYABLE_PLAYER_SLOTS];

                for (int slot = PlayerTable.PLAYABLE_PLAYER_SLOTS - 1; slot >= 0; slot--) {
                    int mapType = table.templateType[slot];

                    if (team[slot] == force && (mapType == PlayerTypes.COMPUTER || mapType == PlayerTypes.HUMAN || mapType == TYPE_COMPUTER ||
                                                mapType == TYPE_OPEN)) {
                        randomStartForce                                         = true;
                        (table.selectableRace[slot] ? selectable : others)[slot] = true;
                    }
                }

                slots.assignStartSlots(random, selectable);
                slots.assignStartSlots(random, others);
            }
        } else if (!options.hasFixedStartPositions()) {
            boolean[] all = new boolean[PlayerTable.PLAYABLE_PLAYER_SLOTS];

            Arrays.fill(all, true);
            slots.assignStartSlots(random, all);
        }

        int[] colors = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        if (options.hasFixedStartPositions() && !randomStartForce) {
            for (int index = 0; index < PlayerTable.PLAYABLE_PLAYER_SLOTS; index++) {
                colors[index] = index;
            }
        } else {
            permuteColors(random, colors);
        }

        MeleeGameSetup.PlayerRecord[] records = new MeleeGameSetup.PlayerRecord[PlayerTable.PLAYER_SLOTS];

        for (int slot = 0; slot < PlayerTable.PLAYER_SLOTS; slot++) {
            records[slot] = new MeleeGameSetup.PlayerRecord(playerId[slot], type[slot], race[slot], team[slot], name[slot]);
        }

        int[] commandSlots = new int[PlayerTable.PLAYER_SLOTS];

        for (int slot = 0; slot < PlayerTable.PLAYER_SLOTS; slot++) {
            int force = team[slot];

            commandSlots[slot] = options.isTeamGame() && slot < PlayerTable.PLAYABLE_PLAYER_SLOTS && type[slot] == PlayerTypes.HUMAN && force >= 1 &&
                                 force <= PlayerTable.FORCE_COUNT && slots.leaderSlot[force - 1] >= 0
                                 ? slots.leaderSlot[force - 1]
                                 : slot;
        }

        int[] customColors = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            customColors[slot] = colorId[slot] >= 0 ? getColorRgb(colorId[slot]) : table.colorKept[slot] ? table.mapColor[slot] : -1;
        }

        return new Result(records, colors, colorId, customColors, lobbyColor, commandSlots, slotRaces);
    }

    /**
     * Tests whether a live game keeps its lobby and custom colors at game start.
     *
     * @param network         whether the game is a network game
     * @param gameType        the game type byte
     * @param customColorFlag the host's custom color flag
     * @return {@code true} if the game keeps the colors, {@code false} otherwise
     */
    public static boolean keepsCustomColors(boolean network, int gameType, boolean customColorFlag) {
        if (!network) {
            return false;
        }

        return (gameType & GAME_TYPE_MASK) != GAME_TYPE_USE_MAP_SETTINGS || customColorFlag;
    }

    /**
     * Resolves the random races of the playable slots; a selectable race counts as random. A slot that is neither human nor
     * computer draws any playable race. Humans and computers draw into a pool that spreads the playable races, and the pool,
     * shuffled by random swaps, is handed out in descending slot order. In a team game a computer's race is also given to
     * every slot of its force.
     *
     * @param random   the game's random source
     * @param type     the lobby types
     * @param race     the races, resolved in place
     * @param team     the forces
     * @param teamGame whether the game is a network team game
     */
    static void resolveRandomRaces(@NotNull LcgRandom random, int @NotNull [] type, int @NotNull [] race, int @NotNull [] team, boolean teamGame) {
        int randomPlayers = 0;

        for (int slot = PlayerTable.PLAYABLE_PLAYER_SLOTS - 1; slot >= 0; slot--) {
            if (race[slot] == SELECTABLE_RACE) {
                race[slot] = RACE_RANDOM;
            }

            if (race[slot] == RACE_RANDOM && (type[slot] == PlayerTypes.HUMAN || type[slot] == TYPE_COMPUTER)) {
                randomPlayers++;
            }
        }

        boolean[] used = new boolean[RACE_COUNT];
        int available = RACE_COUNT;
        int[] pool = new int[RACE_POOL_SIZE];
        int pooled = 0;

        for (int slot = PlayerTable.PLAYABLE_PLAYER_SLOTS - 1; slot >= 0; slot--) {
            if (race[slot] != RACE_RANDOM) {
                continue;
            }

            if (type[slot] != PlayerTypes.HUMAN && type[slot] != TYPE_COMPUTER) {
                race[slot] = random.next(SOURCE_RACE, 0, LAST_PLAYABLE_RACE);
            } else {
                int drawn;

                if (available == 1) {
                    drawn = !used[RACE_ZERG] ? RACE_ZERG : used[RACE_TERRAN] ? LAST_PLAYABLE_RACE : RACE_TERRAN;
                    Arrays.fill(used, false);
                    available = RACE_COUNT;

                    if (pooled == THIRD_RANDOM_PLAYER - 1 && randomPlayers == THIRD_RANDOM_PLAYER ||
                        pooled == SIXTH_RANDOM_PLAYER - 1 && randomPlayers == SIXTH_RANDOM_PLAYER) {
                        drawn = random.next(SOURCE_RACE, 0, LAST_PLAYABLE_RACE);
                    }
                } else {
                    drawn = random.next(SOURCE_RACE, 0, available - 1);

                    if (available == RACE_COUNT - 1) {
                        if (drawn == RACE_ZERG) {
                            if (used[RACE_ZERG]) {
                                drawn = RACE_TERRAN;
                            }
                        } else if (drawn == RACE_TERRAN && !used[LAST_PLAYABLE_RACE]) {
                            drawn = LAST_PLAYABLE_RACE;
                        }

                        if (pooled == SECOND_RANDOM_PLAYER - 1 && randomPlayers == SECOND_RANDOM_PLAYER &&
                            random.next(SOURCE_RACE, 0, SECOND_RACE_CHANCE_BOUND) == SECOND_RACE_CHANCE_TRIGGER) {
                            drawn = random.next(SOURCE_RACE, 0, LAST_PLAYABLE_RACE);
                        }
                    }

                    used[drawn] = true;
                    available--;
                }

                if (pooled < pool.length) {
                    pool[pooled++] = drawn;
                }
            }

            if (teamGame && (type[slot] == PlayerTypes.COMPUTER || type[slot] == TYPE_COMPUTER)) {
                for (int member = 0; member < PlayerTable.PLAYABLE_PLAYER_SLOTS; member++) {
                    if (team[member] == team[slot]) {
                        race[member] = race[slot];
                    }
                }
            }
        }

        if (randomPlayers > 0) {
            for (int swap = 0; swap < RACE_POOL_SWAPS; swap++) {
                int first = random.next(SOURCE_RACE, 0, randomPlayers - 1);
                int second = random.next(SOURCE_RACE, 0, randomPlayers - 1);
                int value = pool[first];

                pool[first]  = pool[second];
                pool[second] = value;
            }
        }

        int next = 0;

        for (int slot = PlayerTable.PLAYABLE_PLAYER_SLOTS - 1; slot >= 0 && next < pooled; slot--) {
            if (race[slot] == RACE_RANDOM && (type[slot] == PlayerTypes.HUMAN || type[slot] == TYPE_COMPUTER)) {
                race[slot] = pool[next++];
            }
        }
    }

    /**
     * Assigns the network colors. A chosen lobby color that is still free is kept, and stays free for others when it is
     * locked; any other chosen color is cleared. Each slot without a color then draws one at random from the colors that are
     * not reserved, not hidden by the tileset and neither used nor similar to one used by an occupied or open slot. Slots kept
     * out of the color pass are skipped.
     *
     * @param random     the game's random source
     * @param table      the lobby table
     * @param type       the slot types
     * @param lobbyColor the lobby colors, updated in place
     * @param colorId    the network color ids, updated in place
     */
    private static void assignNetworkColors(@NotNull LcgRandom random, @NotNull Table table,
                                            int @NotNull [] type,
                                            int @NotNull [] lobbyColor, int @NotNull [] colorId) {
        TreeSet<Integer> available = new TreeSet<>();

        for (int color = 0; color < COLOR_IDS; color++) {
            available.add(color);
        }

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            if (table.colorKept[slot] || lobbyColor[slot] == COLOR_NONE) {
                continue;
            }

            int color = lobbyColor[slot];

            if (available.contains(color)) {
                colorId[slot] = color;

                if (!table.colorLock[slot]) {
                    available.remove(color);
                }
            } else {
                lobbyColor[slot] = COLOR_NONE;
            }
        }

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            if (table.colorKept[slot] || lobbyColor[slot] != COLOR_NONE) {
                continue;
            }

            available.remove(COLOR_RESERVED);

            switch (table.tileset & TILESET_MASK) {
                case TILESET_PLATFORM, TILESET_ASHWORLD -> {
                    available.remove(COLOR_DARK_GRAY);
                    available.remove(COLOR_GRAY);
                }
                case TILESET_DESERT -> available.remove(COLOR_ORANGE);
                case TILESET_ICE -> available.remove(COLOR_WHITE);
                case TILESET_TWILIGHT -> available.remove(COLOR_PALE_BLUE);
                default -> {
                    // The other tilesets hide no color.
                }
            }

            for (int other = 0; other < PlayerTable.PLAYABLE_PLAYER_SLOTS; other++) {
                int otherType = type[other];
                int used = lobbyColor[other];

                if (used == COLOR_NONE || !(otherType == PlayerTypes.COMPUTER || otherType == PlayerTypes.HUMAN || otherType == TYPE_COMPUTER ||
                                            otherType == TYPE_OPEN)) {
                    continue;
                }

                available.remove(used);

                for (int[] group : SIMILAR_COLORS) {
                    if (Arrays.stream(group).anyMatch(member -> member == used)) {
                        Arrays.stream(group).forEach(available::remove);
                    }
                }
            }

            int pick = random.next(SOURCE_COLOR, 0, available.size() - 1);
            int color = available.isEmpty() ? COLOR_NONE : new ArrayList<>(available).get(pick);

            lobbyColor[slot] = color;
            colorId[slot]    = color == COLOR_NONE ? -1 : color;
            available.remove(color);
        }
    }

    /**
     * Draws the color permutation: from the last entry to the first, each entry takes a random one of the slot indices not
     * yet taken.
     *
     * @param random the game's random source
     * @param colors the eight permutation entries, filled in place
     */
    static void permuteColors(@NotNull LcgRandom random, int @NotNull [] colors) {
        int[] remaining = {0, 1, 2, 3, 4, 5, 6, 7};

        for (int index = PlayerTable.PLAYABLE_PLAYER_SLOTS - 1; index >= 0; index--) {
            int pick = random.next(SOURCE_COLOR, 0, index);

            colors[index] = remaining[pick];
            System.arraycopy(remaining, pick + 1, remaining, pick, index - pick);
        }
    }

    private static boolean isPlayerType(int type) {
        return type >= PlayerTypes.COMPUTER && type <= PlayerTypes.RESCUE_ACTIVE || type == PlayerTypes.NEUTRAL;
    }


    private LobbyResolution() {
    }
}
