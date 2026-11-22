package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.base.text.TerminatedStringUtils;
import org.fogofwarlabs.sc4j.engine.net.lobby.*;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.fogofwarlabs.sc4j.scr.data.format.text.LegacyTextUtils;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGameExtensionUtils;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationExtensionUtils;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerColorRows;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.save.SaveGameInformation;
import org.fogofwarlabs.sc4j.scr.game.session.NetworkPeerUtils;
import org.fogofwarlabs.sc4j.scr.game.session.setup.*;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanPresenceInformation;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderPacket;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderPeer;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.IntSupplier;

/**
 * Maintains a LAN game's lobby table, messages, pending commands and turn steps over a {@link ProviderSession}.
 * The twelve records contain eight players and four observers. Hosts derive new or saved lobby setups,
 * assign slots, and distribute settings; joiners receive those settings before entering the lobby.
 * <p>
 * Completed turn steps flush pending commands, advance the start countdown, send the next turn and
 * execute received commands. Map transfers, slot requests, chat and briefing readiness share this stream.
 * <p>
 * The room reads as an engine {@link GameLobby}: its members are the records a player or an observer holds, each peer the holder's
 * network id.
 */
public final class LanLobby implements GameLobby {
    /**
     * The number of lobby records: eight players and four observers.
     */
    public static final int RECORDS = PlayerTable.PLAYABLE_PLAYER_SLOTS + PlayerTable.OBSERVER_SLOTS;
    /**
     * The minimum time between two lobby turn steps.
     */
    public static final int STEP_INTERVAL = 50;
    /**
     * The time without a completed lobby turn after which the lobby times out.
     */
    public static final int TIMEOUT = 20000;
    /**
     * The lobby protocol version the host requires in the joined-game command, a 16-bit number read from the resource
     * {@code lan-lobby.properties} next to this class ({@code 5} in the SC:R mod, as native SC:R). A host refuses a joiner with another
     * version, so a mod built from the SC:R mod's sources sets its own version there to keep apart from SC:R mod players on a LAN; a mod whose
     * game extensions provide memory ({@link ScrGameExtensionUtils#providesMemory()}) or that has simulation extensions
     * ({@link ScrSimulationExtensionUtils#hasExtensions()}) must.
     */
    public static final int JOIN_VERSION = readJoinVersion();
    /**
     * The departure reason of the lobby timeout's drops and leave.
     */
    public static final int DROP_REASON = 0x40000006;
    /**
     * The turns in transit (the provider's value clamped to {@code 2..8}; {@code 2} on a LAN).
     */
    public static final int TURNS_IN_TRANSIT = 2;
    /**
     * The least number of lobby steps of the start countdown.
     */
    public static final int START_COUNTDOWN = 120;
    /**
     * Lobby state of a joiner from the game creation until it sent its joined-game command: slot records ({@code 0x3e}) are ignored.
     */
    public static final int STATE_JOINING = 2;
    /**
     * Lobby state: a joiner sent its joined-game command and waits for its first slot record.
     */
    public static final int STATE_JOINED = 3;
    /**
     * Lobby state: the lobby.
     */
    public static final int STATE_LOBBY = 4;
    /**
     * Lobby state: the start countdown runs.
     */
    public static final int STATE_COUNTDOWN = 5;
    /**
     * Lobby state: the host queued the saved data {@code 0x48}.
     */
    public static final int STATE_SAVED_DATA_QUEUED = 7;
    /**
     * Lobby state: waiting for the saved data {@code 0x48}; no more lobby commands are sent.
     */
    public static final int STATE_WAITING_FOR_SAVED_DATA = 8;
    /**
     * Lobby state: the game starts (the lobby step returns {@code 1}).
     */
    public static final int STATE_GAME = 9;
    /**
     * Briefing state: no ready room.
     */
    public static final int BRIEFING_NONE = 0;
    /**
     * Briefing state: the ready room runs.
     */
    public static final int BRIEFING_RUNNING = 1;
    /**
     * Briefing state: the local player pressed Start and waits for the others.
     */
    public static final int BRIEFING_READY = 2;
    /**
     * The per-slot byte without a saved slot, its reset value. The byte names the saved record slot a lobby slot's player
     * plays in a saved game; {@code 0x48} carries the eight player bytes to every client.
     */
    public static final int NO_SAVED_SLOT = 8;

    /**
     * The player type of an open slot.
     */
    public static final int TYPE_OPEN = PlayerTypes.EITHER_PREFER_HUMAN;
    /**
     * The player type of an observer.
     */
    public static final int TYPE_OBSERVER = PlayerTypes.OBSERVER;
    /**
     * The player type of an open observer slot.
     */
    public static final int TYPE_OBSERVER_OPEN = 0x0C;

    /**
     * The longest chat text with its NUL.
     */
    public static final int CHAT_LIMIT = 0x78;
    /**
     * The number of forces.
     */
    public static final int FORCES = 4;
    /**
     * The size of a force name in message {@code 0x4b}.
     */
    public static final int FORCE_NAME_SIZE = 0x1E;

    /**
     * The color of a slot without a chosen color (the value of an unset entry).
     */
    public static final int COLOR_DEFAULT = LobbyResolution.COLOR_NONE;
    /**
     * The number of color values the request check accepts: {@code 0..0x16}.
     */
    public static final int COLOR_COUNT = 0x17;
    /**
     * The color of a slot whose map sets an RGB color of its own ({@code MapSpecified}).
     */
    public static final int COLOR_MAP_SPECIFIED = 0x17;
    /**
     * The slot byte of a color or race request that names the sender's own slot.
     */
    public static final int OWN_SLOT = 8;
    /**
     * No refusal ({@link #getRefusal()}); code {@code 0} is a boot.
     */
    public static final int NO_REFUSAL = -1;
    /**
     * The slot state of the host's slot command {@code 0x44}: computer.
     */
    public static final int SLOT_COMPUTER = 0;
    /**
     * The slot state of the host's slot command {@code 0x44}: open.
     */
    public static final int SLOT_OPEN = 1;
    /**
     * The slot state of the host's slot command {@code 0x44}: closed.
     */
    public static final int SLOT_CLOSED = 2;
    /**
     * The lobby error {@code 4} ({@code chatUNJOINABLE}): a start command outside the lobby state, and the host's refusal of a
     * joined-game command it executes outside the lobby state.
     */
    public static final int UNJOINABLE_ERROR = 4;
    /**
     * The refusal of a joiner without a free slot ({@code chatSLOTCLOSED}).
     */
    public static final int REFUSAL_NO_SLOT = 2;
    /**
     * The refusal of a joiner whose saved game is not the host's ({@code chatINVALIDSAVE}).
     */
    public static final int REFUSAL_INVALID_SAVE = 5;
    /**
     * The lobby error of a failed map transfer ({@code chatFDERROR}).
     */
    public static final int REFUSAL_FILE_ERROR = 6;

    /**
     * The size of a player's block in the joined-game command and the slot table message.
     */
    static final int PLAYER_BLOCK_SIZE = 350;
    /**
     * The number of provider player identifier values in one byte.
     */
    static final int PLAYER_IDENTIFIER_COUNT = 0x100;

    private static final @NotNull Logger LOGGER = Logger.of(LanLobby.class);

    /**
     * The mask of an unsigned byte.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of an unsigned WORD.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The resource next to this class that holds the lobby settings a mod may change.
     */
    private static final @NotNull String LOBBY_SETTINGS_RESOURCE = "lan-lobby.properties";
    /**
     * The key of {@link #JOIN_VERSION} in the lobby settings.
     */
    private static final @NotNull String JOIN_VERSION_KEY = "joinVersion";
    /**
     * The lobby protocol version of the SC:R mod and native SC:R.
     */
    private static final int SCR_JOIN_VERSION = 5;

    /**
     * The ready command of the ready room ({@code 0x54}, handled inline by the lobby dispatcher).
     */
    private static final int READY_COMMAND = ReplayCommandIds.BRIEFING_START;
    /**
     * The move request {@code 0x38} (the replay command list names the id voice enable).
     */
    private static final int MOVE_REQUEST_COMMAND = ReplayCommandIds.VOICE_ENABLE;
    /**
     * The host's slot state command {@code 0x44} (the replay command list names the id melee team).
     */
    private static final int SLOT_STATE_COMMAND = ReplayCommandIds.MELEE_TEAM;
    /**
     * A color request {@code {slot, color}}.
     */
    private static final int COLOR_REQUEST_COMMAND = 0x67;
    /**
     * The host's color of a slot {@code {slot, color}}.
     */
    private static final int COLOR_COMMAND = 0x68;
    /**
     * The host's lobby data.
     */
    private static final int LOBBY_DATA_COMMAND = 0x69;

    /**
     * The sizes of the lobby commands: no, one or two argument bytes, the move request, slot change, new net player, saved data.
     */
    private static final int NO_ARGUMENT_COMMAND_SIZE = 1;
    private static final int ONE_ARGUMENT_COMMAND_SIZE = 2;
    private static final int TWO_ARGUMENT_COMMAND_SIZE = 3;
    private static final int MOVE_REQUEST_SIZE = 4;
    private static final int SLOT_COMMAND_SIZE = 6;
    private static final int NEW_NET_PLAYER_SIZE = 8;
    private static final int SAVED_DATA_SIZE = 13;
    /**
     * The lobby data command: the id and the {@code 153} lobby data bytes.
     */
    private static final int LOBBY_DATA_COMMAND_SIZE = 0x9a;
    static final int LOBBY_DATA_SIZE = LOBBY_DATA_COMMAND_SIZE - 1;
    /**
     * The joined-game command: the id, the {@code 13} bytes of fields and the player's block.
     */
    private static final int JOIN_COMMAND_FIELDS_SIZE = 13;
    private static final int JOIN_COMMAND_SIZE = 1 + JOIN_COMMAND_FIELDS_SIZE + PLAYER_BLOCK_SIZE;

    /**
     * The joined-game command layout: lobby slot, record slot, random state, flags WORD, WORD, version WORD, flag byte, block.
     */
    private static final int JOIN_LOBBY_SLOT_OFFSET = 1;
    private static final int JOIN_RECORD_SLOT_OFFSET = 2;
    private static final int JOIN_RANDOM_STATE_OFFSET = 3;
    private static final int JOIN_FLAGS_OFFSET = 7;
    private static final int JOIN_SECOND_WORD_OFFSET = 9;
    private static final int JOIN_VERSION_OFFSET = 11;
    private static final int JOIN_FLAG_BYTE_OFFSET = 13;
    private static final int JOIN_BLOCK_OFFSET = 14;
    /**
     * The bits of the flags WORD the host keeps, and the bit the flag byte sets.
     */
    private static final int JOIN_FLAGS_MASK = 0xFF03;
    private static final int JOIN_FLAG_BYTE_BIT = 0x100;

    /**
     * The offset of a lobby command's second argument byte.
     */
    private static final int SECOND_ARGUMENT_OFFSET = 2;
    /**
     * The new net player command layout: player id, then three version WORDs.
     */
    private static final int NEW_NET_PLAYER_VERSIONS_OFFSET = 2;
    private static final int PLAYER_VERSION_WORDS = 3;
    /**
     * The version states of a player index: no versions yet, versions held (the player joined), the player left.
     */
    private static final int VERSIONS_NONE = 0;
    private static final int VERSIONS_PRESENT = 1;
    private static final int VERSIONS_LEFT = 2;
    /**
     * The slot change command layout: slot, player id, type, race, force.
     */
    private static final int SLOT_COMMAND_PLAYER_OFFSET = 2;
    private static final int SLOT_COMMAND_TYPE_OFFSET = 3;
    private static final int SLOT_COMMAND_RACE_OFFSET = 4;
    private static final int SLOT_COMMAND_FORCE_OFFSET = 5;
    /**
     * The move request layout: force, observer flag, slot of the request.
     */
    private static final int MOVE_REQUEST_OBSERVER_OFFSET = 2;
    private static final int MOVE_REQUEST_SLOT_OFFSET = 3;
    /**
     * The saved data layout: the seed, then the eight per-slot bytes.
     */
    private static final int SAVED_DATA_SLOTS_OFFSET = 1 + Integer.BYTES;

    /**
     * The lobby messages (class {@code 1}).
     */
    static final int MESSAGE_PLAYER_INFORMATION = 0x3A;
    private static final int MESSAGE_PLAYER_JOINED = 0x49;
    private static final int MESSAGE_GAME_SETTINGS = 0x4A;
    private static final int MESSAGE_FORCE_NAMES = 0x4B;
    private static final int MESSAGE_CHAT = 0x4C;
    private static final int MESSAGE_REFUSAL = 0x4E;
    private static final int MESSAGE_MAP_TRANSFER = 0x4F;
    private static final int MESSAGE_SETTINGS_END = 0x50;
    private static final int MESSAGE_SLOT_TABLE = 0x5E;
    /**
     * The message targets: every player including the local one, every other player.
     */
    private static final int ALL_PLAYERS = -1;
    private static final int OTHER_PLAYERS = -2;

    /**
     * The player information message {@code 0x3a}: size, region and name offsets, name limit with its NUL.
     */
    private static final int PLAYER_INFORMATION_SIZE = 0x196;
    private static final int PLAYER_INFORMATION_REGION_OFFSET = 0x6A;
    static final int PLAYER_INFORMATION_NAME_OFFSET = 0xCE;
    private static final int PLAYER_INFORMATION_NAME_LIMIT = 100;
    /**
     * The size of the player joined message {@code 0x49}: the id and the player DWORD.
     */
    private static final int PLAYER_JOINED_SIZE = 1 + Integer.BYTES;
    /**
     * The size of the refusal message {@code 0x4e}: the id and the reason.
     */
    private static final int REFUSAL_MESSAGE_SIZE = 2;
    /**
     * The refusal of a joiner with another lobby protocol version (version mismatch).
     */
    private static final int REFUSAL_VERSION = 7;
    /**
     * The provider player identifier of the host.
     */
    private static final int HOST_PLAYER_IDENTIFIER = 0;
    /**
     * The size of the header of the map transfer message {@code 0x4f}: the message identifier and the WORD length.
     */
    private static final int MAP_TRANSFER_HEADER_SIZE = 3;
    private static final int MAP_TRANSFER_LENGTH_OFFSET = 1;
    /**
     * The bytes of the provider's largest message the game keeps out of a map transfer message.
     */
    static final int MAP_TRANSFER_RESERVE = 4;
    /**
     * The transfer bytes the lobby logs of a message.
     */
    private static final int LOGGED_TRANSFER_BYTES = 16;
    /**
     * The slot table message {@code 0x5e}: the player id and its block, padded to {@code 352} bytes.
     */
    private static final int SLOT_TABLE_SIZE = 0x160;
    private static final int SLOT_TABLE_BLOCK_OFFSET = 2;
    /**
     * The order in which the host sends the slot tables to a new player: slots {@code 7..0}, then the observers.
     */
    private static final int @NotNull [] SLOT_TABLE_ORDER = {7, 6, 5, 4, 3, 2, 1, 0, 8, 9, 10, 11};

    /**
     * The game settings message {@code 0x4a}: its size and the offsets of its fields.
     */
    private static final int GAME_SETTINGS_SIZE = 63;
    private static final int SETTINGS_TILESET_OFFSET = 1;
    private static final int SETTINGS_TEMPLATE_TYPES_OFFSET = 7;
    private static final int SETTINGS_TEMPLATE_RACES_OFFSET = 0x13;
    private static final int SETTINGS_SAVED_TYPES_OFFSET = 0x1F;
    private static final int SETTINGS_FORCES_OFFSET = 0x2B;
    private static final int SETTINGS_FORCE_FLAGS_OFFSET = 0x33;
    private static final int SETTINGS_SELECTABLE_RACES_OFFSET = 0x37;

    /**
     * The {@code FORC} section: eight force bytes, four force name string WORDs, four force flags.
     */
    private static final int FORC_SIZE = 20;
    private static final int FORC_NAMES_OFFSET = 8;
    private static final int FORC_FLAGS_OFFSET = 16;
    /**
     * The forces of Top vs Bottom's top and bottom slots.
     */
    private static final int TOP_FORCE = 1;
    private static final int BOTTOM_FORCE = 2;

    /**
     * Diagnostics: the unknown lobby commands reported at most.
     */
    private static final int UNKNOWN_COMMAND_REPORTS = 20;
    /**
     * The last sent download percent while none counts as sent ({@code 0x3d}): the next percent then goes out.
     */
    private static final int DOWNLOAD_UNKNOWN = 0xFF;
    /**
     * The highest download percent a {@code 0x3d} command carries; a received percent above it reads {@code 0}.
     */
    private static final int MAXIMUM_DOWNLOAD_PERCENT = 100;
    /**
     * The copies of a boot's refusal message.
     */
    private static final int BOOT_REFUSAL_COPIES = 3;
    /**
     * The refusal codes of a boot and a ban (message {@code 0x4e}).
     */
    private static final int BOOT_REASON = 0;
    private static final int BAN_REASON = 1;
    /**
     * The provider departure reason of a booted player.
     */
    private static final int BOOT_DROP_REASON = 3;
    /**
     * The request's slot byte for "any" ({@code 0x38} byte {@code 1} of {@code RequestObserver}).
     */
    private static final int NO_FORCE = 0xFF;
    /**
     * The countdown steps reserved before the saved data phase.
     */
    private static final int COUNTDOWN_DISPLAY_STEPS = 4;
    /**
     * The times the start countdown covers the turns in transit before its display steps.
     */
    private static final int COUNTDOWN_TRANSIT_ROUNDS = 2;
    /**
     * The countdown steps past the turns in transit at which the host queues the saved data.
     */
    private static final int SAVED_DATA_STEPS = 2;
    /**
     * The initial capacity of a flushed turn.
     */
    private static final int TURN_CAPACITY = 256;
    /**
     * The milliseconds of a second, for the default seed source.
     */
    private static final long MILLISECONDS_PER_SECOND = 1000L;

    /**
     * Lobby data (the {@code 0x69} command after its id): the custom color flag.
     */
    private static final int LOBBY_DATA_CUSTOM_COLOR_FLAG = 0;
    /**
     * Lobby data: the per-slot bytes that keep a slot's map color out of the color pass.
     */
    private static final int LOBBY_DATA_COLOR_KEPT = 1;
    /**
     * Lobby data: the per-slot color locks.
     */
    private static final int LOBBY_DATA_COLOR_LOCKS = 9;
    /**
     * Lobby data: the eight slot colors.
     */
    static final int LOBBY_DATA_COLORS = 17;
    /**
     * Lobby data: the custom color table of the eight slots, which holds the map color of a slot whose {@code CRGB} section fixes its own color.
     */
    private static final int LOBBY_DATA_CUSTOM_COLORS = 25;
    /**
     * The color that is never available.
     */
    private static final int COLOR_RESERVED = 0xE;
    /**
     * The last color index a {@code CRGB} slot may name.
     */
    private static final int COLOR_LAST_INDEX = 0x15;
    /**
     * The {@code CRGB} section: eight RGB triples, then eight color modes.
     */
    private static final int CRGB_SIZE = 32;
    private static final int CRGB_MODES = 24;
    private static final int CRGB_TRIPLE_SIZE = 3;
    /**
     * The byte of a triple that names a color index.
     */
    private static final int CRGB_INDEX_BYTE = 2;
    private static final int CRGB_RANDOM = 0;
    private static final int CRGB_PLAYER_CHOICE = 1;
    private static final int CRGB_MAP_COLOR = 2;
    /**
     * The tileset bits of the map's tileset value.
     */
    private static final int TILESET_MASK = 7;
    /**
     * The colors each tileset hides (badlands, platform, installation, ashworld, jungle, desert, ice, twilight).
     */
    private static final int @NotNull [] @NotNull [] HIDDEN_COLORS = {{}, {0x15, 0x14}, {}, {0x15, 0x14}, {}, {4}, {6}, {0xD}};
    /**
     * Groups of colors too similar to share a lobby.
     */
    private static final int @NotNull [] @NotNull [] SIMILAR_COLORS = {{1, 0xD, 0x12}, {2, 8, 0xC}, {7, 9, 0x11}};

    /**
     * What the host supplies to open a lobby: the map's values of the game settings message and the initial table.
     */
    public static final class Setup {
        /**
         * Creates a {@link Setup} instance of the host's lobby from a map and game options: the lobby map load (template
         * records from {@code OWNR}/{@code SIDE}; a selectable race {@code 5} becomes {@code 7} and sets the slot's settings
         * byte; slots {@code 8..11} cleared; forces), then the game creation: outside Use Map Settings the playable types
         * become open slots and the forces clear, the types are saved, the game type's teams are applied, the live table is
         * the template with closed observer slots, and the host takes a slot like every joining player
         * ({@link #placePlayer}).
         *
         * @param map         the scenario
         * @param options     the game options
         * @param hostName    the host's name
         * @param mapFileName the map file name
         * @param mapFileSize the map file size
         * @param mapChecksum the map checksum
         * @return the created {@link Setup} instance
         * @throws IllegalArgumentException if the map has no open slot for the host
         */
        public static @NotNull Setup create(@NotNull ChkFile map, @NotNull LanGameOptions options,
                                            @NotNull String hostName,
                                            @NotNull String mapFileName, int mapFileSize, int mapChecksum) {
            byte[] owners = map.getSection(ChkFile.OWNER_SECTION);
            byte[] sides = map.getSection(ChkFile.RACE_SECTION);
            byte[] mapForceSection = map.getSection(ChkFile.FORCE_SECTION);
            byte[] forceSection = new byte[FORC_SIZE];

            if (mapForceSection != null) {
                System.arraycopy(mapForceSection, 0, forceSection, 0, Math.min(mapForceSection.length, forceSection.length));
            }

            int[] type = new int[RECORDS];
            int[] race = new int[RECORDS];
            int[] force = new int[RECORDS];
            byte[] settings = new byte[PlayerTable.PLAYABLE_PLAYER_SLOTS];

            for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                type[slot] = owners != null && slot < owners.length ? owners[slot] & BYTE_MASK : 0;
                race[slot] = sides != null && slot < sides.length ? sides[slot] & BYTE_MASK : 0;

                if (race[slot] == ScrRaceIds.USER_SELECTABLE) {
                    race[slot]     = LobbyResolution.SELECTABLE_RACE;
                    settings[slot] = 1;
                }
            }

            // The FORC force plus one for slots of the playable types (rescuable to neutral), else 0; then the four flags.
            byte[] forces = new byte[RECORDS];

            for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                if (type[slot] >= PlayerTypes.RESCUE_PASSIVE && type[slot] <= PlayerTypes.NEUTRAL) {
                    forces[slot] = (byte) ((forceSection[slot] & BYTE_MASK) + 1);
                    force[slot]  = forces[slot];
                }
            }

            System.arraycopy(forceSection, FORC_FLAGS_OFFSET, forces, PlayerTable.PLAYABLE_PLAYER_SLOTS, FORCES);

            if (!options.isUseMapSettings()) {
                for (int slot = 0; slot < RECORDS; slot++) {
                    if (type[slot] == PlayerTypes.RESCUE_PASSIVE || type[slot] == PlayerTypes.RESCUE_ACTIVE ||
                        type[slot] == PlayerTypes.EITHER_PREFER_COMPUTER || type[slot] == PlayerTypes.NEUTRAL) {
                        type[slot] = TYPE_OPEN;
                    }

                    force[slot] = 0;
                }
            }

            byte[] savedTypes = new byte[RECORDS];

            for (int slot = 0; slot < RECORDS; slot++) {
                savedTypes[slot] = (byte) type[slot];
            }

            applyTeams(options, type, race, force);

            byte[] templateTypes = new byte[RECORDS];
            byte[] templateRaces = new byte[RECORDS];
            MeleeGameSetup.PlayerRecord[] records = new MeleeGameSetup.PlayerRecord[RECORDS];

            for (int slot = 0; slot < RECORDS; slot++) {
                templateTypes[slot] = (byte) type[slot];
                templateRaces[slot] = (byte) race[slot];
                records[slot]       = slot < PlayerTable.PLAYABLE_PLAYER_SLOTS
                                      ? new MeleeGameSetup.PlayerRecord(-1, type[slot], race[slot], force[slot], "")
                                      : new MeleeGameSetup.PlayerRecord(-1, PlayerTypes.CLOSED, 0, 0, "");
            }

            int hostSlot = placePlayer(options, records, new boolean[RECORDS], null, null, HOST_PLAYER_IDENTIFIER, hostName, 0, 0);

            if (hostSlot < 0) {
                throw Exceptions.newIllegalArgumentException("The map has no open slot for the host in this game type.");
            }

            byte[] lobbyData = new byte[LOBBY_DATA_SIZE];

            Arrays.fill(lobbyData, LOBBY_DATA_COLORS, LOBBY_DATA_COLORS + PlayerTable.PLAYABLE_PLAYER_SLOTS, (byte) COLOR_DEFAULT);

            // The map's CRGB section: eight RGB triples, then a color mode per slot. Mode 1 leaves the color to the
            // player; mode 0 fixes it to Random, mode 2 to the map's own RGB color (MapSpecified, 0x17), any other mode to
            // the color index of the triple's third byte (Random above 0x15). Every fixed color is locked, so the color
            // request check refuses changes. A map color also keeps the slot out of the network color pass, and a loaded
            // CRGB section sets the custom color flag that a Use Map Settings game needs to keep its colors.
            byte[] colorSection = map.getSection(ChkFile.PLAYER_COLORS_SECTION);

            if (colorSection != null && colorSection.length == CRGB_SIZE) {
                int[] mapColors = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];

                Arrays.fill(mapColors, -1);

                lobbyData[LOBBY_DATA_CUSTOM_COLOR_FLAG] = 1;

                for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                    int mode = colorSection[CRGB_MODES + slot] & BYTE_MASK;

                    if (mode == CRGB_PLAYER_CHOICE) {
                        continue;
                    }

                    int index = colorSection[slot * CRGB_TRIPLE_SIZE + CRGB_INDEX_BYTE] & BYTE_MASK;

                    lobbyData[LOBBY_DATA_COLORS + slot]      = (byte) (mode == CRGB_RANDOM ? COLOR_DEFAULT
                                                                                           : mode == CRGB_MAP_COLOR ? COLOR_MAP_SPECIFIED
                                                                                                                    : index <= COLOR_LAST_INDEX ? index : COLOR_DEFAULT);
                    lobbyData[LOBBY_DATA_COLOR_LOCKS + slot] = 1;

                    if (mode == CRGB_MAP_COLOR) {
                        int triple = slot * CRGB_TRIPLE_SIZE;

                        lobbyData[LOBBY_DATA_COLOR_KEPT + slot] = 1;
                        mapColors[slot]                         = (colorSection[triple] & BYTE_MASK) << Short.SIZE |
                                                                  (colorSection[triple + 1] & BYTE_MASK) << Byte.SIZE |
                                                                  colorSection[triple + 2] & BYTE_MASK;
                    }
                }

                System.arraycopy(PlayerColorRows.encodeCustomColors(mapColors), 0,
                                 lobbyData, LOBBY_DATA_CUSTOM_COLORS,
                                 PlayerTable.PLAYABLE_PLAYER_SLOTS * PlayerColorRows.CUSTOM_COLOR_ENTRY_SIZE);
            } else if (options.getTypeId() == GameTemplate.TYPE_USE_MAP_SETTINGS) {
                // Without the flag a Use Map Settings lobby keeps and locks every slot's map color (0x17).
                Arrays.fill(lobbyData, LOBBY_DATA_COLOR_KEPT, LOBBY_DATA_COLOR_KEPT + PlayerTable.PLAYABLE_PLAYER_SLOTS, (byte) 1);
                Arrays.fill(lobbyData, LOBBY_DATA_COLOR_LOCKS, LOBBY_DATA_COLOR_LOCKS + PlayerTable.PLAYABLE_PLAYER_SLOTS, (byte) 1);
                Arrays.fill(lobbyData, LOBBY_DATA_COLORS, LOBBY_DATA_COLORS + PlayerTable.PLAYABLE_PLAYER_SLOTS, (byte) COLOR_MAP_SPECIFIED);
            }

            Setup setup = new Setup(map.getTileset(),
                                    map.getWidth(), map.getHeight(),
                                    templateTypes, templateRaces, savedTypes,
                                    forces, settings,
                                    lobbyData,
                                    records,
                                    mapFileName, mapFileSize, mapChecksum);

            if (options.isUseMapSettings()) {
                // The force names go out when Use Map Settings holds: the FORC names (STR index, 0 = none).
                List<byte[]> strings = map.getStringBytes();
                byte[][] names = new byte[FORCES][];

                for (int index = 0; index < FORCES; index++) {
                    int nameOffset = FORC_NAMES_OFFSET + index * Short.BYTES;
                    int stringNumber = LittleEndianUtils.readUnsigned16(forceSection, nameOffset);

                    names[index] = stringNumber >= 1 && stringNumber <= strings.size() ? strings.get(stringNumber - 1) : new byte[0];
                }

                setup = setup.withForceNames(names);
            }

            return setup;
        }

        /**
         * Applies the game type's teams to the template records: a team game fills slots {@code 0..} team by team (slots per
         * team) as open slots with slot {@code 0}'s race; Top vs Bottom gives the first {@link LanGameOptions#getSubTypeId()} playable slots
         * force {@code 1} and the others force {@code 2}, every used slot becoming open with slot {@code 0}'s race.
         *
         * @param options the game options
         * @param type    the twelve template types, updated in place
         * @param race    the twelve template races, updated in place
         * @param force   the twelve template forces, updated in place
         */
        private static void applyTeams(@NotNull LanGameOptions options, int @NotNull [] type, int @NotNull [] race, int @NotNull [] force) {
            int firstRace = race[0];

            if (options.isTeamGame()) {
                int slot = 0;

                for (int team = 0; team < options.getTeamCount(); team++) {
                    for (int member = 0; member < options.getSlotsPerTeam() && slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; member++, slot++) {
                        type[slot]  = TYPE_OPEN;
                        race[slot]  = firstRace;
                        force[slot] = team + 1;
                    }
                }
            } else if (options.isTopVsBottom()) {
                int[] team = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
                int topSlotCount = options.getSubTypeId();

                for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                    if (type[slot] >= PlayerTypes.RESCUE_PASSIVE && type[slot] <= PlayerTypes.NEUTRAL) {
                        team[slot] = topSlotCount > 0 ? TOP_FORCE : BOTTOM_FORCE;
                        topSlotCount--;
                    }
                }

                for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                    if (type[slot] != PlayerTypes.NONE) {
                        type[slot]  = TYPE_OPEN;
                        race[slot]  = firstRace;
                        force[slot] = team[slot];
                    }
                }
            }
        }

        /**
         * Creates a {@link Setup} instance of a saved game lobby from the map settings and resolved saved records.
         * <ul>
         * <li>Neither a team game nor Use Map Settings: the saved humans' records become open slots packed from slot
         * {@code 0} in slot order, followed by the computer, rescuable and neutral records (types {@code 3}, {@code 4},
         * {@code 5} and {@code 7}; a computer is type {@code 5} again), each of which keeps its own saved slot.</li>
         * <li>A team game or Use Map Settings: every slot in place from the saved lobby types (a human becomes open, an
         * open slot closed) and races; team forces from the team layout, Use Map Settings forces from the map; the
         * occupied slots keep their own saved slot.</li>
         * </ul>
         * Every record has no player id; the template is the table; the observer slots are closed. The host then takes
         * its slot like every joining player of the saved game ({@link #placePlayer}).
         *
         * @param map         the saved game's scenario
         * @param options     the saved game's options
         * @param hostName    the host's name
         * @param mapFileName the map file name
         * @param mapFileSize the map file size
         * @param mapChecksum the map checksum
         * @param saved       the saved game's lobby, resolved with its seed
         * @param resolved    the saved game's twelve records at its start (types normalized)
         * @param information the host's saved game identity
         * @return the created {@link Setup} instance
         * @throws IllegalArgumentException if the host has no slot
         */
        public static @NotNull Setup createSaved(@NotNull ChkFile map, @NotNull LanGameOptions options,
                                                 @NotNull String hostName,
                                                 @NotNull String mapFileName, int mapFileSize, int mapChecksum,
                                                 @NotNull MeleeGameSetup saved,
                                                 @NotNull MeleeGameSetup.PlayerRecord @NotNull [] resolved,
                                                 @NotNull SaveGameInformation information) {
            Setup base = create(map, options, hostName, mapFileName, mapFileSize, mapChecksum);
            int[] lobbyTypes = saved.getLobbyTypes();
            int[] slotRaces = saved.getResolvedSlotRaces();
            MeleeGameSetup.PlayerRecord[] records = new MeleeGameSetup.PlayerRecord[RECORDS];
            byte[] savedSlots = new byte[RECORDS];
            byte[] savedRaces = new byte[RECORDS];

            Arrays.fill(savedSlots, (byte) NO_SAVED_SLOT);

            for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS && slotRaces != null; slot++) {
                savedRaces[slot] = (byte) slotRaces[slot];
            }

            if (isSavedInPlace(options)) {
                for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                    int lobbyType = lobbyTypes == null ? 0 : lobbyTypes[slot];
                    int type = lobbyType == PlayerTypes.HUMAN ? TYPE_OPEN : lobbyType == TYPE_OPEN ? PlayerTypes.CLOSED : lobbyType;
                    int force = options.isUseMapSettings() ? base.forces[slot] & BYTE_MASK : 0;

                    records[slot] = new MeleeGameSetup.PlayerRecord(-1, type, savedRaces[slot] & BYTE_MASK, force, "");

                    if (type != PlayerTypes.NONE && type != TYPE_OPEN && type != PlayerTypes.CLOSED) {
                        savedSlots[slot] = (byte) slot;
                    }
                }

                if (options.isTeamGame()) {
                    for (int team = 1; team <= options.getTeamCount(); team++) {
                        for (int member = 0; member < options.getSlotsPerTeam(); member++) {
                            int slot = (team - 1) * options.getSlotsPerTeam() + member;

                            if (slot < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                                MeleeGameSetup.PlayerRecord record = records[slot];

                                records[slot] = new MeleeGameSetup.PlayerRecord(-1, record.getType(), record.getRace(), team, "");
                            }
                        }
                    }
                }
            } else {
                int next = 0;

                for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                    if (resolved[slot].getType() == PlayerTypes.HUMAN) {
                        records[next++] = new MeleeGameSetup.PlayerRecord(-1, TYPE_OPEN, resolved[slot].getRace(), 0, "");
                    }
                }

                for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                    int type = resolved[slot].getType() == PlayerTypes.COMPUTER ? PlayerTypes.EITHER_PREFER_COMPUTER : resolved[slot].getType();

                    if (type >= PlayerTypes.RESCUE_PASSIVE && type <= PlayerTypes.EITHER_PREFER_COMPUTER || type == PlayerTypes.NEUTRAL) {
                        records[next]      = new MeleeGameSetup.PlayerRecord(-1, type, resolved[slot].getRace(), 0, resolved[slot].getName());
                        savedSlots[next++] = (byte) slot;
                    }
                }

                while (next < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                    records[next++] = new MeleeGameSetup.PlayerRecord(-1, PlayerTypes.NONE, 0, 0, "");
                }
            }

            for (int slot = PlayerTable.PLAYABLE_PLAYER_SLOTS; slot < RECORDS; slot++) {
                records[slot] = new MeleeGameSetup.PlayerRecord(-1, PlayerTypes.CLOSED, 0, 0, "");
            }

            byte[] templateTypes = new byte[RECORDS];
            byte[] templateRaces = new byte[RECORDS];

            for (int slot = 0; slot < RECORDS; slot++) {
                templateTypes[slot] = (byte) records[slot].getType();
                templateRaces[slot] = (byte) records[slot].getRace();
            }

            // The host's own joined-game command takes its slot like any joiner of the saved game.
            int hostSlot = placePlayer(options,
                                       records, new boolean[RECORDS],
                                       savedSlots, savedRaces,
                                       HOST_PLAYER_IDENTIFIER, hostName,
                                       information.getLobbySlot(), information.getRecordSlot());

            if (hostSlot < 0) {
                throw Exceptions.newIllegalArgumentException("The saved game has no slot for the host (record slot {0}).",
                                                             information.getRecordSlot());
            }

            // Saved games keep their colors: the color requests are refused, the slots show their default.
            byte[] lobbyData = new byte[LOBBY_DATA_SIZE];

            Arrays.fill(lobbyData, LOBBY_DATA_COLOR_LOCKS, LOBBY_DATA_COLOR_LOCKS + PlayerTable.PLAYABLE_PLAYER_SLOTS, (byte) 1);
            Arrays.fill(lobbyData, LOBBY_DATA_COLORS, LOBBY_DATA_COLORS + PlayerTable.PLAYABLE_PLAYER_SLOTS, (byte) COLOR_DEFAULT);

            Setup setup = new Setup(base.tileset,
                                    base.width, base.height,
                                    templateTypes, templateRaces, templateTypes,
                                    base.forces, base.settings,
                                    lobbyData,
                                    records,
                                    mapFileName, mapFileSize, mapChecksum);

            if (base.forceNames != null) {
                setup = setup.withForceNames(base.forceNames);
            }

            return setup.withSavedGame(information, savedSlots, savedRaces);
        }


        private final int tileset;
        private final int width;
        private final int height;
        private final byte @NotNull [] templateTypes;
        private final byte @NotNull [] templateRaces;
        private final byte @NotNull [] templateOwners;
        private final byte @NotNull [] forces;
        private final byte @NotNull [] settings;
        private final byte @NotNull [] lobbyData;
        private final @NotNull MeleeGameSetup.PlayerRecord @NotNull [] records;
        private final @NotNull String mapFileName;
        private final int mapFileSize;
        private final int mapChecksum;

        private byte @Nullable [] @NotNull [] forceNames;
        /**
         * The host's saved game identity of a lobby that loads a saved game, else {@code null}.
         */
        private @Nullable SaveGameInformation savedGame;
        /**
         * The initial per-slot bytes: the saved slot of each lobby slot, {@link #NO_SAVED_SLOT} for none.
         */
        private byte @NotNull [] savedSlots;
        /**
         * The saved game's lobby races by saved lobby slot: every player of the saved game takes its own.
         */
        private byte @NotNull [] savedRaces;

        /**
         * Creates a {@link Setup} instance.
         *
         * @param tileset        the map tileset
         * @param width          the map width
         * @param height         the map height
         * @param templateTypes  the twelve template types of message {@code 0x4a}
         * @param templateRaces  the twelve template races
         * @param templateOwners the twelve saved template types
         * @param forces         the twelve force bytes of the map (eight force bytes and four more)
         * @param settings       the eight settings bytes (the selectable races)
         * @param lobbyData      the {@code 0x69} command's {@code 153} bytes after its id: the custom color flag, eight bytes that keep
         *                       the map colors, the eight color locks, the eight slot colors, {@code 128} more bytes
         * @param records        the host's twelve lobby records, the host in its slot
         * @param mapFileName    the map file name
         * @param mapFileSize    the map file size
         * @param mapChecksum    the map checksum ({@code map_crc})
         */
        public Setup(int tileset,
                     int width, int height,
                     byte @NotNull [] templateTypes, byte @NotNull [] templateRaces, byte @NotNull [] templateOwners,
                     byte @NotNull [] forces, byte @NotNull [] settings,
                     byte @NotNull [] lobbyData,
                     @NotNull MeleeGameSetup.PlayerRecord @NotNull [] records,
                     @NotNull String mapFileName, int mapFileSize, int mapChecksum) {
            this.tileset        = tileset;
            this.width          = width;
            this.height         = height;
            this.templateTypes  = templateTypes.clone();
            this.templateRaces  = templateRaces.clone();
            this.templateOwners = templateOwners.clone();
            this.forces         = forces.clone();
            this.settings       = settings.clone();
            this.lobbyData      = lobbyData.clone();
            this.records        = records.clone();
            this.mapFileName    = mapFileName;
            this.mapFileSize    = mapFileSize;
            this.mapChecksum    = mapChecksum;

            this.forceNames = null;
            this.savedGame  = null;
            this.savedSlots = new byte[RECORDS];
            this.savedRaces = new byte[RECORDS];

            Arrays.fill(this.savedSlots, (byte) NO_SAVED_SLOT);
        }

        private Setup(@NotNull Setup base, byte @NotNull [] @NotNull [] forceNames) {
            this(base.tileset,
                 base.width, base.height,
                 base.templateTypes, base.templateRaces, base.templateOwners,
                 base.forces, base.settings,
                 base.lobbyData,
                 base.records,
                 base.mapFileName, base.mapFileSize, base.mapChecksum);

            this.forceNames = new byte[FORCES][];

            for (int force = 0; force < FORCES; force++) {
                this.forceNames[force] = Arrays.copyOf(forceNames[force], FORCE_NAME_SIZE);
            }

            this.savedGame  = base.savedGame;
            this.savedSlots = base.savedSlots.clone();
            this.savedRaces = base.savedRaces.clone();
        }

        private Setup(@NotNull Setup base, @NotNull SaveGameInformation savedGame, byte @NotNull [] savedSlots, byte @NotNull [] savedRaces) {
            this(base.tileset,
                 base.width, base.height,
                 base.templateTypes, base.templateRaces, base.templateOwners,
                 base.forces, base.settings,
                 base.lobbyData,
                 base.records,
                 base.mapFileName, base.mapFileSize, base.mapChecksum);

            this.forceNames = base.forceNames;
            this.savedGame  = savedGame;
            this.savedSlots = Arrays.copyOf(savedSlots, RECORDS);
            this.savedRaces = Arrays.copyOf(savedRaces, RECORDS);
        }

        /**
         * @param index the record index, {@code 0..11} ({@code 8..11} are the observer slots)
         * @return the initial lobby record
         */
        public MeleeGameSetup.@NotNull PlayerRecord getRecord(int index) {
            return this.records[index];
        }

        /**
         * @param index the record index, {@code 0..11}
         * @return the template type of message {@code 0x4a}
         */
        public int getTemplateType(int index) {
            return this.templateTypes[index] & BYTE_MASK;
        }

        /**
         * @return the force names of message {@code 0x4b}, or {@code null} when the setup sends none
         */
        public byte @Nullable [] @NotNull [] getForceNames() {
            return this.forceNames == null ? null : this.forceNames.clone();
        }


        /**
         * Creates a {@link Setup} instance that copies this setup with the map's force names, which the host sends to a new
         * player as message {@code 0x4b} before the game settings.
         *
         * @param forceNames the four force names, each at most {@value #FORCE_NAME_SIZE} bytes with its NUL
         * @return the created {@link Setup} instance
         */
        public @NotNull Setup withForceNames(byte @NotNull [] @NotNull [] forceNames) {
            return new Setup(this, forceNames);
        }

        /**
         * Creates a {@link Setup} instance that copies this setup as the lobby of a saved game (the game creation with an
         * open saved game file): joiners must bring the same saved game (refusal {@code 5}) and each player's slot carries
         * its saved slot.
         *
         * @param savedGame  the host's saved game identity
         * @param savedSlots the twelve initial per-slot bytes ({@link #NO_SAVED_SLOT} for none)
         * @param savedRaces the saved game's twelve lobby races
         * @return the created {@link Setup} instance
         */
        public @NotNull Setup withSavedGame(@NotNull SaveGameInformation savedGame, byte @NotNull [] savedSlots, byte @NotNull [] savedRaces) {
            return new Setup(this, savedGame, savedSlots, savedRaces);
        }
    }

    /**
     * A lobby chat line.
     */
    public static final class Chat {
        private final int player;
        private final @NotNull String text;

        /**
         * Creates a {@link Chat} instance.
         *
         * @param player the sending player
         * @param text   the text
         */
        public Chat(int player, @NotNull String text) {
            this.player = player;
            this.text   = text;
        }

        /**
         * @return the sending player
         */
        public int getPlayer() {
            return this.player;
        }

        /**
         * @return the text
         */
        public @NotNull String getText() {
            return this.text;
        }
    }

    /**
     * A notice that another player joined, left or was dropped.
     */
    public static final class Notice {
        /**
         * Another player joined.
         */
        public static final int JOINED = 0;
        /**
         * A player left.
         */
        public static final int LEFT = 1;
        /**
         * A player was dropped (reason {@link #DROP_REASON}).
         */
        public static final int DROPPED = 2;

        private final int kind;
        private final @NotNull String name;

        /**
         * Creates a {@link Notice} instance.
         *
         * @param kind {@link #JOINED}, {@link #LEFT} or {@link #DROPPED}
         * @param name the player's name
         */
        Notice(int kind, @NotNull String name) {
            this.kind = kind;
            this.name = name;
        }

        /**
         * @return {@link #JOINED}, {@link #LEFT} or {@link #DROPPED}
         */
        public int getKind() {
            return this.kind;
        }

        /**
         * @return the player's name
         */
        public @NotNull String getName() {
            return this.name;
        }
    }

    /**
     * Checks whether a saved game's lobby keeps every slot in place: team games and Use Map Settings do; the others pack
     * their open slots.
     *
     * @param options the game options
     * @return {@code true} if the game is a team game or uses Use Map Settings, {@code false} otherwise
     */
    static boolean isSavedInPlace(@NotNull LanGameOptions options) {
        return options.isTeamGame() || options.isUseMapSettings();
    }

    /**
     * Places a joining player in the lobby table, as the host does for every joined-game command it accepts, its own
     * included:
     * <ol>
     * <li>the slot choice ({@link #chooseSlot});</li>
     * <li>every record the player already holds becomes an open slot ({@link #TYPE_OPEN}) and gives up its saved slot; it
     * keeps the player's id and name, its race and force;</li>
     * <li>a choice that is not an open player slot falls back to the first open observer slot that is not pending
     * ({@link #reserveObserverSlot}), so a player slot the player released takes the player again;</li>
     * <li>in a saved game the chosen slot takes the player's record slot as its saved slot ({@link #assignSavedSlot}); a
     * player slot that cannot take it refuses the player;</li>
     * <li>the chosen record takes the player, a human in a player slot or an observer in an observer slot, with the slot's
     * force and race; in a saved game the race is the player's saved lobby race.</li>
     * </ol>
     * A pending flag that the choice set stays set, for a refused player too, until a slot change {@code 0x3e} of the slot
     * executes; the host's own placement leaves the lobby with every flag clear.
     *
     * @param options          the game options
     * @param records          the twelve lobby records, updated in place
     * @param pendingSlots     the twelve pending slot flags, updated in place
     * @param savedSlots       the twelve per-slot saved slots of a saved game, updated in place, or {@code null} for a new game
     * @param savedRaces       the saved game's lobby races by saved lobby slot, or {@code null} for a new game
     * @param playerIdentifier the player's provider identifier
     * @param name             the player's name
     * @param lobbySlot        the player's saved lobby slot {@code 0..7} (joined-game command byte {@code 1}), unused in a new game
     * @param recordSlot       the player's saved record slot {@code 0..7} (joined-game command byte {@code 2}), unused in a new game
     * @return the record index the player took, or {@code -1} when no slot is free (refusal {@code 2})
     */
    static int placePlayer(@NotNull LanGameOptions options,
                           @NotNull MeleeGameSetup.PlayerRecord @NotNull [] records, boolean @NotNull [] pendingSlots,
                           byte @Nullable [] savedSlots, byte @Nullable [] savedRaces,
                           int playerIdentifier, @NotNull String name,
                           int lobbySlot, int recordSlot) {
        int index = chooseSlot(options, records, pendingSlots, savedRaces != null, lobbySlot);

        for (int heldIndex = 0; heldIndex < RECORDS; heldIndex++) {
            MeleeGameSetup.PlayerRecord record = records[heldIndex];

            if (record.getPlayerId() == playerIdentifier) {
                records[heldIndex] = new MeleeGameSetup.PlayerRecord(playerIdentifier,
                                                                     TYPE_OPEN, record.getRace(),
                                                                     record.getTeam(),
                                                                     record.getName());

                if (savedSlots != null) {
                    savedSlots[heldIndex] = (byte) NO_SAVED_SLOT;
                }
            }
        }

        if (index < 0 || (index < PlayerTable.PLAYABLE_PLAYER_SLOTS && records[index].getType() != TYPE_OPEN)) {
            index = reserveObserverSlot(records, pendingSlots);
        }

        // An observer slot that cannot take the saved slot still takes the player.
        if (index >= 0 && savedSlots != null && !assignSavedSlot(savedSlots, index, recordSlot) && index < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            index = -1;
        }

        if (index < 0) {
            return -1;
        }

        MeleeGameSetup.PlayerRecord open = records[index];
        int type = index < PlayerTable.PLAYABLE_PLAYER_SLOTS ? PlayerTypes.HUMAN : TYPE_OBSERVER;
        int race = savedRaces != null ? savedRaces[lobbySlot] & BYTE_MASK : open.getRace();

        records[index] = new MeleeGameSetup.PlayerRecord(playerIdentifier, type, race, open.getTeam(), name);

        return index;
    }

    /**
     * Chooses a joining player's slot; a slot found by a search is reserved (its pending flag set):
     * <ul>
     * <li>Use Map Settings outside a saved game: of the forces {@code 1..4}, the one with the most open slots that are not
     * pending (a tie keeps the lower force), and its first such slot ({@link #reserveOpenSlot});</li>
     * <li>a saved game that keeps its slots in place ({@link #isSavedInPlace}): the player's saved lobby slot;</li>
     * <li>otherwise the first open slot of the game type ({@link #reserveFirstOpenSlot}).</li>
     * </ul>
     * The choice need not be open: {@link #placePlayer} checks it after releasing the player's records. No slot is chosen
     * by the player's name.
     *
     * @param options      the game options
     * @param records      the twelve lobby records
     * @param pendingSlots the twelve pending slot flags, updated in place
     * @param saved        {@code true} if the lobby loads a saved game, {@code false} otherwise
     * @param lobbySlot    the player's saved lobby slot {@code 0..7}, unused in a new game
     * @return the record index {@code 0..7}, or {@code -1} when the search found no slot
     */
    private static int chooseSlot(@NotNull LanGameOptions options,
                                  @NotNull MeleeGameSetup.PlayerRecord @NotNull [] records, boolean @NotNull [] pendingSlots,
                                  boolean saved, int lobbySlot) {
        int slot;

        if (options.isUseMapSettings() && !saved) {
            int bestOpen = 0;
            int bestForce = 0;

            for (int force = 1; force <= FORCES; force++) {
                int open = 0;

                for (int candidate = PlayerTable.PLAYABLE_PLAYER_SLOTS - 1; candidate >= 0; candidate--) {
                    if (records[candidate].getTeam() == force && records[candidate].getType() == TYPE_OPEN && !pendingSlots[candidate]) {
                        open++;
                    }
                }

                if (open > bestOpen) {
                    bestOpen  = open;
                    bestForce = force;
                }
            }

            slot = bestForce == 0 ? -1 : reserveOpenSlot(records, pendingSlots, bestForce);
        } else if (saved && isSavedInPlace(options)) {
            slot = lobbySlot;
        } else {
            slot = reserveFirstOpenSlot(options, records, pendingSlots);
        }

        return slot;
    }

    /**
     * Reserves a joining player's first open slot of the game type: in a team game, of the team with the most open slots
     * ({@link #findTeamWithMostOpenSlots}), the first open slot that is not pending; otherwise the first open slot that is
     * not pending.
     *
     * @param options      the game options
     * @param records      the twelve lobby records
     * @param pendingSlots the twelve pending slot flags, updated in place
     * @return the record index {@code 0..7}, or {@code -1} when there is none
     */
    private static int reserveFirstOpenSlot(@NotNull LanGameOptions options,
                                            @NotNull MeleeGameSetup.PlayerRecord @NotNull [] records, boolean @NotNull [] pendingSlots) {
        if (options.isTeamGame()) {
            int team = findTeamWithMostOpenSlots(options, records);

            return team == 0 ? -1 : reserveOpenSlot(records, pendingSlots, team);
        }

        for (int candidate = 0; candidate < PlayerTable.PLAYABLE_PLAYER_SLOTS; candidate++) {
            if (records[candidate].getType() == TYPE_OPEN && !pendingSlots[candidate]) {
                pendingSlots[candidate] = true;

                return candidate;
            }
        }

        return -1;
    }

    /**
     * Finds the team of a team game with the most open slots (open player or observer types, pending or not); a tie goes
     * to the team with fewer humans, a further tie to the lower team.
     *
     * @param options the game options
     * @param records the twelve lobby records
     * @return the team {@code 1..}, or {@code 0} when no team has an open slot
     */
    private static int findTeamWithMostOpenSlots(@NotNull LanGameOptions options, @NotNull MeleeGameSetup.PlayerRecord @NotNull [] records) {
        int bestTeam = 0;
        int bestOpen = 0;
        int bestHumans = 0;

        for (int team = 1; team <= options.getTeamCount(); team++) {
            int open = 0;
            int humans = 0;

            for (int candidate = 0; candidate < PlayerTable.PLAYABLE_PLAYER_SLOTS; candidate++) {
                if (records[candidate].getTeam() == team) {
                    if (records[candidate].getType() == TYPE_OPEN || records[candidate].getType() == TYPE_OBSERVER_OPEN) {
                        open++;
                    } else if (records[candidate].getType() == PlayerTypes.HUMAN) {
                        humans++;
                    }
                }
            }

            if (open > bestOpen || (open == bestOpen && open > 0 && humans < bestHumans)) {
                bestTeam   = team;
                bestOpen   = open;
                bestHumans = humans;
            }
        }

        return bestTeam;
    }

    /**
     * Reserves the first open slot {@code 0..7} of a force whose pending flag is clear.
     *
     * @param records      the twelve lobby records
     * @param pendingSlots the twelve pending slot flags, updated in place
     * @param force        the force
     * @return the record index, or {@code -1} when there is none
     */
    private static int reserveOpenSlot(@NotNull MeleeGameSetup.PlayerRecord @NotNull [] records, boolean @NotNull [] pendingSlots, int force) {
        for (int candidate = 0; candidate < PlayerTable.PLAYABLE_PLAYER_SLOTS; candidate++) {
            if (records[candidate].getTeam() == force && records[candidate].getType() == TYPE_OPEN && !pendingSlots[candidate]) {
                pendingSlots[candidate] = true;

                return candidate;
            }
        }

        return -1;
    }

    /**
     * Reserves the first open observer slot ({@link #TYPE_OBSERVER_OPEN}) whose pending flag is clear.
     *
     * @param records      the twelve lobby records
     * @param pendingSlots the twelve pending slot flags, updated in place
     * @return the record index {@code 8..11}, or {@code -1} when there is none
     */
    private static int reserveObserverSlot(@NotNull MeleeGameSetup.PlayerRecord @NotNull [] records, boolean @NotNull [] pendingSlots) {
        for (int index = PlayerTable.PLAYABLE_PLAYER_SLOTS; index < RECORDS; index++) {
            if (records[index].getType() == TYPE_OBSERVER_OPEN && !pendingSlots[index]) {
                pendingSlots[index] = true;

                return index;
            }
        }

        return -1;
    }

    /**
     * Gives a lobby slot its saved slot unless taken: no slot may hold the saved slot already and the lobby slot must hold
     * none.
     *
     * @param savedSlots the twelve per-slot bytes
     * @param index      the record index
     * @param savedSlot  the saved slot {@code 0..7}
     * @return {@code true} if the slot took the saved slot, {@code false} otherwise
     */
    static boolean assignSavedSlot(byte @NotNull [] savedSlots, int index, int savedSlot) {
        for (byte held : savedSlots) {
            if ((held & BYTE_MASK) == savedSlot) {
                return false;
            }
        }

        if ((savedSlots[index] & BYTE_MASK) != NO_SAVED_SLOT) {
            return false;
        }

        savedSlots[index] = (byte) savedSlot;

        return true;
    }

    /**
     * Creates message {@code 0x4a}: tileset, width, height, template types and races, the twelve saved template types, the
     * map forces, the eight settings bytes.
     *
     * @param lobby the host's setup
     * @return the game settings message
     */
    static byte @NotNull [] createGameSettings(@NotNull Setup lobby) {
        ByteBuffer message = ByteBuffer.allocate(GAME_SETTINGS_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        message.put((byte) MESSAGE_GAME_SETTINGS).putShort((short) lobby.tileset).putShort((short) lobby.width).putShort((short) lobby.height);
        message.put(lobby.templateTypes, 0, RECORDS).put(lobby.templateRaces, 0, RECORDS).put(lobby.templateOwners, 0, RECORDS)
               .put(lobby.forces, 0, RECORDS);
        message.put(lobby.settings, 0, PlayerTable.PLAYABLE_PLAYER_SLOTS);

        return message.array();
    }

    /**
     * Reads the slot identifier of a lobby record: a player record keeps its index and the observer records follow
     * {@link PlayerTable#FIRST_OBSERVER_SLOT}.
     *
     * @param index the record index
     * @return the slot identifier
     */
    static int getSlotIdentifier(int index) {
        return index >= PlayerTable.PLAYABLE_PLAYER_SLOTS ? PlayerTable.FIRST_OBSERVER_SLOT + index - PlayerTable.PLAYABLE_PLAYER_SLOTS : index;
    }

    /**
     * Reads the record type of a slot state: computer {@code 5}, open {@code 6} (an observer slot {@code 0xC}), closed
     * {@code 8}.
     *
     * @param index the record index
     * @param state {@link #SLOT_COMPUTER}, {@link #SLOT_OPEN} or {@link #SLOT_CLOSED}
     * @return the record type
     */
    private static int getSlotType(int index, int state) {
        return switch (state) {
            case SLOT_COMPUTER -> PlayerTypes.EITHER_PREFER_COMPUTER;
            case SLOT_OPEN -> index >= PlayerTable.PLAYABLE_PLAYER_SLOTS ? TYPE_OBSERVER_OPEN : TYPE_OPEN;
            default -> PlayerTypes.CLOSED;
        };
    }

    /**
     * Reads the type an occupant's record takes in a slot: a human or an observer follows the slot kind, and so does an
     * open player or observer slot; any other type stays.
     *
     * @param type  the occupant's record type
     * @param index the record index of the slot
     * @return the record type in the slot
     */
    private static int getOccupantType(int type, int index) {
        boolean observer = index >= PlayerTable.PLAYABLE_PLAYER_SLOTS;

        return switch (type) {
            case PlayerTypes.HUMAN, TYPE_OBSERVER -> observer ? TYPE_OBSERVER : PlayerTypes.HUMAN;
            case TYPE_OPEN, TYPE_OBSERVER_OPEN -> observer ? TYPE_OBSERVER_OPEN : TYPE_OPEN;
            default -> type;
        };
    }


    /**
     * Creates the slot change command {@code 0x3e}: slot, player id byte, type, race, force.
     *
     * @param index  the record index
     * @param record the record
     * @return the command
     */
    private static byte @NotNull [] createSlotCommand(int index, MeleeGameSetup.@NotNull PlayerRecord record) {
        return new byte[]{(byte) ReplayCommandIds.CHANGE_GAME_SLOT,
                          (byte) getSlotIdentifier(index), (byte) record.getPlayerId(), (byte) record.getType(),
                          (byte) record.getRace(), (byte) record.getTeam()};
    }

    /**
     * Reads a zero-terminated UTF-8 string that ends at its terminator, the array end or a limit.
     *
     * @param data   the bytes
     * @param offset the offset of the string
     * @param limit  the most bytes read
     * @return the string
     */
    private static @NotNull String readString(byte @NotNull [] data, int offset, int limit) {
        return new String(data, offset, TerminatedStringUtils.getTextLength(data, offset, limit), StandardCharsets.UTF_8);
    }

    /**
     * Reads the join version from the lobby settings: a decimal or {@code 0x} hexadecimal number.
     *
     * @return the join version
     * @throws IllegalStateException if the lobby settings are missing or cannot be read, or the join version is not a 16-bit number
     */
    private static int readJoinVersion() {
        Properties settings = new Properties();

        try (InputStream input = LanLobby.class.getResourceAsStream(LOBBY_SETTINGS_RESOURCE)) {
            if (input == null) {
                throw Exceptions.newIllegalStateException("The LAN lobby settings {0} are missing.", LOBBY_SETTINGS_RESOURCE);
            }

            settings.load(input);
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "The LAN lobby settings {0} cannot be read.", LOBBY_SETTINGS_RESOURCE);
        }

        String text = settings.getProperty(JOIN_VERSION_KEY, "").strip();
        int version;

        try {
            version = Integer.decode(text);
        } catch (NumberFormatException exception) {
            throw Exceptions.newIllegalStateException(exception, "The {0} of {1} is not a number: {2}",
                                                      JOIN_VERSION_KEY, LOBBY_SETTINGS_RESOURCE, text);
        }

        if (version < 0 || version > WORD_MASK) {
            throw Exceptions.newIllegalStateException("The {0} of {1} is not a 16-bit number: {2}", JOIN_VERSION_KEY, LOBBY_SETTINGS_RESOURCE, text);
        }

        if (version == SCR_JOIN_VERSION && ScrGameExtensionUtils.providesMemory()) {
            throw Exceptions.newIllegalStateException("The game extensions provide memory, so the {0} of {1} must differ from the SC:R version {2}.",
                                                      JOIN_VERSION_KEY, LOBBY_SETTINGS_RESOURCE, SCR_JOIN_VERSION);
        }

        if (version == SCR_JOIN_VERSION && ScrSimulationExtensionUtils.hasExtensions()) {
            throw Exceptions.newIllegalStateException("The mod has simulation extensions, so the {0} of {1} must differ from the SC:R version {2}.",
                                                      JOIN_VERSION_KEY, LOBBY_SETTINGS_RESOURCE, SCR_JOIN_VERSION);
        }

        return version;
    }


    private final @NotNull ProviderSession session;
    private final @NotNull IntSupplier clock;
    private final boolean host;
    private final @NotNull String localName;
    private final byte @NotNull [] localBlock;

    private final @Nullable Setup setup;
    private final @NotNull MeleeGameSetup.PlayerRecord @NotNull [] records;
    /**
     * The slot of the record each player holds: rebuilt from the records after they changed ({@link PlayerSlots#update})
     * and removed for a player who left.
     */
    private final @NotNull PlayerSlots playerSlots;
    /**
     * The players' blocks of the joined-game command, by player index.
     */
    private final byte @NotNull [] @NotNull [] playerBlocks;
    /**
     * The players' version WORDs of the joined-game command, by player index; valid while the player's version state is
     * {@link #VERSIONS_PRESENT}.
     */
    private final int @NotNull [] @NotNull [] playerVersions;
    /**
     * The players' version states, by player index: {@link #VERSIONS_NONE}, {@link #VERSIONS_PRESENT} or
     * {@link #VERSIONS_LEFT}.
     */
    private final int @NotNull [] playerVersionStates;
    private final byte @NotNull [] templateTypes;
    private byte @NotNull [] gameSettings;
    private final byte @NotNull [] lobbyData;
    private final @NotNull String @NotNull [] forceNames;
    /**
     * The players' map download percents, by player index.
     */
    private final int @NotNull [] downloadPercents;
    private int sentDownloadPercent;

    /**
     * The map transfer (messages {@code 0x4f}).
     */
    private final @NotNull LanMapTransfer mapTransfer;
    /**
     * The host's shared map, {@code null} on a joiner or before {@link #shareMap}.
     */
    private LanMapTransfer.@Nullable SharedFile sharedMap;

    private final byte @NotNull [] @Nullable [] slotCommands;
    /**
     * The pending new net player commands {@code 0x3f}, by player index.
     */
    private final byte @NotNull [] @Nullable [] playerCommands;
    private byte @Nullable [] startCommand;
    private byte @Nullable [] pendingDownload;
    private byte @Nullable [] raceCommand;
    private byte @Nullable [] forceCommand;
    /**
     * The host's slot state change {@code 0x44}.
     */
    private byte @Nullable [] slotStateCommand;
    /**
     * The host's slot swap {@code 0x45}.
     */
    private byte @Nullable [] swapCommand;
    /**
     * A player's move request {@code 0x38}.
     */
    private byte @Nullable [] moveRequestCommand;
    private byte @Nullable [] savedDataCommand;
    private byte @Nullable [] colorCommand;
    private byte @Nullable [] lobbyDataCommand;
    private byte @Nullable [] joinCommand;
    /**
     * The host's request flags, by player index: set while a move request of the player is under way.
     */
    private final boolean @NotNull [] pendingRequests;
    /**
     * The host's pending slot flags (slots {@code 0..7}, observers {@code 8..11}): set when a move, a swap or a join picks
     * the slot, clear once its slot change {@code 0x3e} executes.
     */
    private final boolean @NotNull [] pendingSlots;
    /**
     * A joiner's slot changes {@code 0x3e} of human players it does not know yet, by record index: each executes once its player is known,
     * unless another slot change of the record came first.
     */
    private final byte @NotNull [] @Nullable [] deferredSlotCommands;

    private boolean started;
    private int lastCompletedStep;
    private boolean timedOut;
    private boolean left;
    private boolean hostLeft;
    /**
     * The game information's turns per second ({@code net_turn_rate}), {@code 0} when not given.
     */
    private int turnRate;
    private int state;
    private int refusal;
    private int countdown;
    private int seed;
    private @NotNull IntSupplier seedSource;
    /**
     * The per-slot bytes: the saved slot each slot's player plays.
     */
    private final byte @NotNull [] startBytes;
    /**
     * The saved game this client brings to the lobby: the host's from its setup, a joiner's from {@link #setSavedGame};
     * {@code null} for a new game.
     */
    private @Nullable SaveGameInformation savedGame;
    /**
     * The host's saved lobby races by saved lobby slot: every player of the saved game takes its own.
     */
    private final byte @NotNull [] savedRaces;
    /**
     * Diagnostics: the lobby turns ended by an unknown command that were reported.
     */
    private int unknownCommandReports;

    private int briefing;
    /**
     * The ready flags, by player index.
     */
    private final boolean @NotNull [] briefingReady;
    private boolean readyQueued;

    private final @NotNull ArrayDeque<Chat> chat;
    private final @NotNull ArrayDeque<Notice> notices;

    /**
     * Creates a {@link LanLobby} instance for a joined or created session.
     *
     * @param session the provider session, already joined or created
     * @param clock   the millisecond tick of the session
     * @param setup   the host's setup, or {@code null} on a joiner (the host's messages fill the table)
     * @param name    the local player's name
     * @param block   the local player's opaque {@code 350}-byte block in the joined-game command
     */
    public LanLobby(@NotNull ProviderSession session, @NotNull IntSupplier clock,
                    @Nullable Setup setup,
                    @NotNull String name, byte @NotNull [] block) {
        this.session    = session;
        this.clock      = clock;
        this.host       = setup != null;
        this.localName  = name;
        this.localBlock = Arrays.copyOf(block, PLAYER_BLOCK_SIZE);

        this.setup               = setup;
        this.records             = new MeleeGameSetup.PlayerRecord[RECORDS];
        this.playerSlots         = new PlayerSlots();
        this.playerBlocks        = new byte[PlayerSlots.PLAYER_INDEXES][PLAYER_BLOCK_SIZE];
        this.playerVersions      = new int[PlayerSlots.PLAYER_INDEXES][PLAYER_VERSION_WORDS];
        this.playerVersionStates = new int[PlayerSlots.PLAYER_INDEXES];
        this.templateTypes       = setup != null ? Arrays.copyOf(setup.templateTypes, RECORDS) : new byte[RECORDS];
        this.gameSettings        = setup != null ? createGameSettings(setup) : new byte[0];
        this.lobbyData           = setup != null ? Arrays.copyOf(setup.lobbyData, LOBBY_DATA_SIZE) : new byte[LOBBY_DATA_SIZE];
        this.forceNames          = new String[]{"", "", "", ""};
        this.downloadPercents    = new int[PlayerSlots.PLAYER_INDEXES];
        this.sentDownloadPercent = DOWNLOAD_UNKNOWN;

        for (int index = 0; index < RECORDS; index++) {
            this.records[index] = setup != null ? setup.records[index] : new MeleeGameSetup.PlayerRecord(-1, PlayerTypes.NONE, 0, 0, "");
        }

        if (setup == null) {
            Arrays.fill(this.lobbyData, LOBBY_DATA_COLORS, LOBBY_DATA_COLORS + PlayerTable.PLAYABLE_PLAYER_SLOTS, (byte) COLOR_DEFAULT);
        }

        // The host's force names are its own map's (read at map load); joiners get them in 0x4b.
        if (setup != null && setup.forceNames != null) {
            for (int force = 0; force < FORCES; force++) {
                this.forceNames[force] = LegacyTextUtils.decodeTerminated(setup.forceNames[force], 0, FORCE_NAME_SIZE);
            }
        }

        Arrays.fill(this.playerVersionStates, VERSIONS_NONE);
        Arrays.fill(this.downloadPercents, -1);

        this.mapTransfer = new LanMapTransfer(session.getLocalIdentifier(),
                                              this::sendMapTransfer,
                                              ProviderSession.MAXIMUM_MESSAGE_SIZE - MAP_TRANSFER_RESERVE);
        this.sharedMap   = null;

        this.slotCommands       = new byte[RECORDS][];
        this.playerCommands     = new byte[PlayerSlots.PLAYER_INDEXES][];
        this.startCommand       = null;
        this.pendingDownload    = null;
        this.raceCommand        = null;
        this.forceCommand       = null;
        this.slotStateCommand   = null;
        this.swapCommand        = null;
        this.moveRequestCommand = null;
        this.savedDataCommand   = null;
        this.colorCommand       = null;
        this.lobbyDataCommand   = null;
        this.joinCommand        = null;
        this.pendingRequests    = new boolean[PlayerSlots.PLAYER_INDEXES];
        this.pendingSlots       = new boolean[RECORDS];

        this.deferredSlotCommands = new byte[RECORDS][];

        this.started               = false;
        this.lastCompletedStep     = 0;
        this.timedOut              = false;
        this.left                  = false;
        this.hostLeft              = false;
        this.turnRate              = 0;
        this.state                 = setup != null ? STATE_LOBBY : STATE_JOINING;
        this.refusal               = NO_REFUSAL;
        this.countdown             = 0;
        this.seed                  = 0;
        this.seedSource            = () -> (int) (System.currentTimeMillis() / MILLISECONDS_PER_SECOND);
        this.startBytes            = new byte[RECORDS];
        this.savedGame             = setup == null ? null : setup.savedGame;
        this.savedRaces            = setup == null ? new byte[RECORDS] : setup.savedRaces.clone();
        this.unknownCommandReports = 0;

        Arrays.fill(this.startBytes, (byte) NO_SAVED_SLOT);

        if (setup != null) {
            System.arraycopy(setup.savedSlots, 0, this.startBytes, 0, RECORDS);
        }

        this.briefing      = BRIEFING_NONE;
        this.briefingReady = new boolean[PlayerSlots.PLAYER_INDEXES];
        this.readyQueued   = false;

        this.chat    = new ArrayDeque<>();
        this.notices = new ArrayDeque<>();

        if (setup != null) {
            int index = PlayerSlots.getPlayerIndex(session.getLocalIdentifier());

            this.setPlayerVersions(session.getLocalIdentifier(), 0, 1, JOIN_VERSION);
            this.playerBlocks[index] = this.localBlock.clone();
            this.playerSlots.update(this.records, RECORDS);
            this.updateSlotCounts();
        }
    }

    /**
     * @param index the record index, {@code 0..11} ({@code 8..11} are the observer slots {@code 0x80..0x83})
     * @return the lobby record
     */
    public @NotNull MeleeGameSetup.PlayerRecord getRecord(int index) {
        return this.records[index];
    }

    /**
     * @param player the provider player id
     * @return the record index of the slot the player holds, {@code -1} when it holds none
     */
    public int getPlayerSlot(int player) {
        return this.playerSlots.getSlot(player);
    }

    /**
     * @return {@code true} if no lobby turn completed for {@link #TIMEOUT} milliseconds, {@code false} otherwise
     */
    public boolean isTimedOut() {
        return this.timedOut;
    }

    /**
     * @return the lobby state: {@link #STATE_JOINING} through {@link #STATE_GAME}
     */
    public int getState() {
        return this.state;
    }

    /**
     * @return {@code true} if this machine hosts the room, {@code false} if it joined
     */
    @Override
    public boolean isHost() {
        return this.host;
    }

    /**
     * @return the room's state at this machine
     * @implSpec A refusal of the host ({@link #getRefusal()}) is {@link LobbyState#REFUSED}; the game state ({@link #STATE_GAME}) is
     * {@link LobbyState#STARTED}; a lobby the local player left ({@link #hasLeft()}), whose host left ({@link #hasHostLeft()}) or that timed
     * out ({@link #isTimedOut()}) is {@link LobbyState#CLOSED}; from the start command's countdown on it is {@link LobbyState#STARTED}, in
     * the lobby {@link LobbyState#OPEN}, and before it {@link LobbyState#JOINING}.
     */
    @Override
    public @NotNull LobbyState getLobbyState() {
        if (this.refusal != NO_REFUSAL) {
            return LobbyState.REFUSED;
        }

        if (this.state == STATE_GAME) {
            return LobbyState.STARTED;
        }

        if (this.left || this.hostLeft || this.timedOut) {
            return LobbyState.CLOSED;
        }

        if (this.state >= STATE_COUNTDOWN) {
            return LobbyState.STARTED;
        }

        return this.state == STATE_LOBBY ? LobbyState.OPEN : LobbyState.JOINING;
    }

    /**
     * @return the room's members in the room's order, each with its slot index or {@link NetworkLobby#OBSERVER_SLOT}
     * @implSpec The records a player or an observer holds ({@link PlayerSlots#isPlayerRecord}), in record order: a player's slot index is
     * its record index {@code 0..7}, an observer's {@link NetworkLobby#OBSERVER_SLOT}; the peer is the holder's network id
     * ({@link NetworkPeerUtils#getPeer}).
     */
    @Override
    public @NotNull List<@NotNull LobbyMember> getMembers() {
        List<LobbyMember> members = new ArrayList<>();

        for (int index = 0; index < RECORDS; index++) {
            MeleeGameSetup.PlayerRecord record = this.records[index];

            if (PlayerSlots.isPlayerRecord(record) && PlayerSlots.isPlayerIdentifier(record.getPlayerId())) {
                int slotIndex = index < PlayerTable.PLAYABLE_PLAYER_SLOTS ? index : NetworkLobby.OBSERVER_SLOT;

                members.add(new LobbyMember(NetworkPeerUtils.getPeer(record.getPlayerId()), record.getName(), slotIndex));
            }
        }

        return members;
    }

    /**
     * @return this machine's member, or {@code null} while this machine holds no place in the room
     * @implSpec The member whose peer is the session's local network id.
     */
    @Override
    public @Nullable LobbyMember getLocalMember() {
        for (LobbyMember member : this.getMembers()) {
            if (member.getPeer().getNumber() == this.session.getLocalIdentifier()) {
                return member;
            }
        }

        return null;
    }

    /**
     * @return the remaining countdown steps
     */
    public int getCountdown() {
        return this.countdown;
    }

    /**
     * @return the game seed of the saved data {@code 0x48}, valid once {@link #getState()} is {@link #STATE_GAME}
     */
    public int getSeed() {
        return this.seed;
    }

    /**
     * Sets the seed source of the host's saved data.
     *
     * @param seedSource the seed source
     */
    public void setSeedSource(@NotNull IntSupplier seedSource) {
        this.seedSource = seedSource;
    }

    /**
     * @return the saved game this client brings to the lobby, or {@code null} for a new game
     */
    public @Nullable SaveGameInformation getSavedGame() {
        return this.savedGame;
    }

    /**
     * Sets the saved game a joiner brings (the file the advertised save id names, pre-loaded); its joined-game command
     * then carries the saved slots and the random state. Takes effect for the joined-game command not yet queued.
     *
     * @param savedGame the saved game identity, or {@code null} for a new game
     */
    public void setSavedGame(@Nullable SaveGameInformation savedGame) {
        this.savedGame = savedGame;
    }

    /**
     * @param index the record index, {@code 0..11}
     * @return the saved slot of the record's player, {@link #NO_SAVED_SLOT} for none; after the game start the host's
     * saved data {@code 0x48} set the eight player bytes on every client
     */
    public int getSavedSlot(int index) {
        return this.startBytes[index] & BYTE_MASK;
    }

    /**
     * @return the host's refusal code of message {@code 0x4e} (the lobby error table: {@code 0} booted, {@code 1} banned,
     * {@code 2} slot closed / full, {@code 4} unjoinable, {@code 7} version mismatch, ...), {@link #NO_REFUSAL}
     * while none arrived
     */
    public int getRefusal() {
        return this.refusal;
    }

    /**
     * @return {@code true} if the host left or was dropped, {@code false} otherwise
     */
    public boolean hasHostLeft() {
        return this.hostLeft;
    }

    /**
     * @return the turns per second of the game information ({@code net_turn_rate}), {@code 0} when the game names none
     */
    public int getTurnRate() {
        return this.turnRate;
    }

    /**
     * Sets the turns per second of the game information ({@code net_turn_rate}), which the join and create path copies to
     * the game.
     *
     * @param turnRate the turns per second, {@code 0} when the game names none
     */
    public void setTurnRate(int turnRate) {
        this.turnRate = turnRate;
    }

    /**
     * @return the game options of the session's game data ({@link LanGameOptions#parse}); Melee when the data is short
     */
    public @NotNull LanGameOptions getOptions() {
        byte[] data = this.session.getGameData();

        return data.length >= LanGameOptions.SIZE ? LanGameOptions.parse(data) : LanGameOptions.MELEE;
    }

    /**
     * @return {@code true} if the lobby timeout made the local player leave the game, {@code false} otherwise
     */
    public boolean hasLeft() {
        return this.left;
    }

    /**
     * @param player the player identifier
     * @return the player's map download percent from its {@code 0x3d} commands (kept by player index: the host's command
     * precedes the slot changes that name it), {@code -1} if none arrived
     */
    public int getDownloadPercent(int player) {
        int index = PlayerSlots.getPlayerIndex(player);

        return index < 0 ? -1 : this.downloadPercents[index];
    }

    /**
     * @param slot the slot, {@code 0..7}
     * @return the slot's color; {@link #COLOR_DEFAULT} when none was chosen
     */
    public int getColor(int slot) {
        return this.lobbyData[LOBBY_DATA_COLORS + slot] & BYTE_MASK;
    }

    /**
     * @param slot the slot, {@code 0..7}
     * @return {@code true} if the map fixes the slot's color (the lock table that {@code CRGB} sets; the color request
     * check then refuses every change), {@code false} otherwise
     */
    public boolean isColorLocked(int slot) {
        return this.lobbyData[LOBBY_DATA_COLOR_LOCKS + slot] != 0;
    }

    /**
     * @param slot the slot, {@code 0..7}
     * @return {@code true} if the map lets the slot's player choose the race ({@code SIDE} {@code 5}, the game settings'
     * byte of the slot; Use Map Settings allows a race change only then), {@code false} otherwise
     */
    public boolean isRaceSelectable(int slot) {
        return this.gameSettings.length == GAME_SETTINGS_SIZE && this.gameSettings[SETTINGS_SELECTABLE_RACES_OFFSET + slot] != 0;
    }

    /**
     * @return the briefing state: {@link #BRIEFING_NONE}, {@link #BRIEFING_RUNNING} or {@link #BRIEFING_READY}
     */
    public int getBriefingState() {
        return this.briefing;
    }

    /**
     * @param player the player id, or {@code 0x80..0x83} for an observer
     * @return {@code true} if the player's ready command ({@code 0x54}) executed in the ready room, {@code false} otherwise
     */
    public boolean isBriefingReady(int player) {
        int index = PlayerSlots.getPlayerIndex(player);

        return index >= 0 && this.briefingReady[index];
    }

    /**
     * @param force the force, {@code 0..3}
     * @return the force name: the host's map {@code FORC} string, a joiner's from message {@code 0x4b}; empty when the map
     * names none or nothing arrived (the caller then uses {@code strFORCE1..4})
     */
    public @NotNull String getForceName(int force) {
        return this.forceNames[force];
    }

    /**
     * @return the map transfer
     */
    public @NotNull LanMapTransfer getMapTransfer() {
        return this.mapTransfer;
    }

    /**
     * @return the local map file of the game: the host's shared map, or the joiner's copy once its transfer from the
     * host holds the whole file (found in the map folders or downloaded); {@code null} before
     */
    public @Nullable Path getMapFile() {
        LanMapTransfer.SharedFile shared = this.sharedMap;

        if (shared != null) {
            return shared.getPath();
        }

        for (LanMapTransfer.Transfer transfer : this.mapTransfer.getTransfers()) {
            if (transfer.getOwner() == HOST_PLAYER_IDENTIFIER && transfer.getFlags() == LanMapTransfer.FLAG_READY &&
                transfer.getBytes() == transfer.getSize() && transfer.getFile() != null) {
                return transfer.getFile();
            }
        }

        return null;
    }

    /**
     * Sets the map folders of the transfer ({@link LanMapTransfer#setFolders}).
     *
     * @param folders the map folders searched for a received map's information, with the folder a downloaded map is written to
     */
    public void setMapFolders(@NotNull MapFolders folders) {
        this.mapTransfer.setFolders(folders);
    }

    /**
     * Sets the mod's downloader of the lobby's maps ({@link LanMapTransfer#setDownloader}); the lobby updates it with every update.
     *
     * @param downloader the downloader, or {@code null} for the transfer of message {@code 0x4f}
     */
    public void setMapDownloader(@Nullable LanMapDownloader downloader) {
        this.mapTransfer.setDownloader(downloader);
    }


    /**
     * Sets the started bit to refuse subsequent joins and queues the host's start command.
     * Clients then count down and receive the host's saved data and seed.
     *
     * @return {@code true} if the host queued the start command in the lobby state, {@code false} otherwise
     */
    public boolean startGame() {
        if (!this.host || this.state != STATE_LOBBY) {
            return false;
        }

        this.session.setGameState(this.session.getGameState() | ProviderSession.STATE_STARTED);
        this.startCommand = new byte[]{ReplayCommandIds.START_GAME};

        return true;
    }

    /**
     * Creates a {@link SavedGamePlayers} instance of who plays each saved slot once a saved game starts: every human of the
     * player slots {@code 0..7} moves, with its id and name, to the saved slot its per-slot byte names.
     *
     * @return the created {@link SavedGamePlayers} instance
     * @throws IllegalStateException if the lobby does not load a saved game
     */
    public @NotNull SavedGamePlayers createSavedGamePlayers() {
        if (this.savedGame == null) {
            throw Exceptions.newIllegalStateException("The lobby does not load a saved game.");
        }

        SavedGamePlayers players = new SavedGamePlayers();

        for (int index = 0; index < PlayerTable.PLAYABLE_PLAYER_SLOTS; index++) {
            MeleeGameSetup.PlayerRecord record = this.records[index];
            int savedSlot = this.startBytes[index] & BYTE_MASK;

            if (record.getType() == PlayerTypes.HUMAN && record.getPlayerId() >= 0 && savedSlot < NO_SAVED_SLOT) {
                players.setPlayer(savedSlot, record.getPlayerId(), record.getName());
            }
        }

        return players;
    }

    /**
     * Creates a {@link LobbyResolution.Table} instance of the lobby table the game start resolves ({@link LobbyResolution}),
     * from what every client holds alike: the live records, the game settings message {@code 0x4a} (template types and
     * races, saved types, the map forces and flags, the selectable-race bytes, the tileset) and the lobby data
     * {@code 0x69} (colors, locks, kept bytes). The template forces a reset restores are the map forces of {@code 0x4a}
     * (the host's own template may hold the game type's forces instead; the recorded games do not tell them apart).
     *
     * @return the created {@link LobbyResolution.Table} instance
     * @throws IllegalStateException if the game settings are not known yet
     */
    public LobbyResolution.@NotNull Table createResolutionTable() {
        if (this.gameSettings.length != GAME_SETTINGS_SIZE) {
            throw Exceptions.newIllegalStateException("The game settings (0x4a) are not known yet.");
        }

        return createResolutionTable(this.gameSettings, this.records, this.lobbyData);
    }

    /**
     * Creates a {@link LobbyResolution.Table} instance of the lobby table the game start resolves from a game settings
     * message, the lobby records and the lobby data. Every record keeps its player id, an open record that names the player
     * who released it too, so the game's player records carry it. The observer records keep the ids and names of their
     * observers. The table is a new game's: a saved game's start gives an id only to a human ({@link #createSavedGamePlayers}).
     *
     * @param gameSettings the game settings message {@code 0x4a}
     * @param records      the twelve lobby records
     * @param lobbyData    the lobby data of {@code 0x69} after its id
     * @return the created {@link LobbyResolution.Table} instance
     */
    static LobbyResolution.@NotNull Table createResolutionTable(byte @NotNull [] gameSettings,
                                                                MeleeGameSetup.@NotNull PlayerRecord @NotNull [] records,
                                                                byte @NotNull [] lobbyData) {
        LobbyResolution.Table table = new LobbyResolution.Table();
        ByteBuffer settings = ByteBuffer.wrap(gameSettings).order(ByteOrder.LITTLE_ENDIAN);

        table.tileset = settings.getShort(SETTINGS_TILESET_OFFSET) & WORD_MASK;

        for (int index = 0; index < RECORDS; index++) {
            // Header slots 8..11 are player slots; the lobby's observers (records 8..11 here) do not take part.
            MeleeGameSetup.PlayerRecord record = index < PlayerTable.PLAYABLE_PLAYER_SLOTS
                                                 ? records[index]
                                                 : new MeleeGameSetup.PlayerRecord(-1, PlayerTypes.NONE, 0, 0, "");

            table.playerId[index]     = record.getPlayerId();
            table.type[index]         = record.getType();
            table.race[index]         = record.getRace();
            table.team[index]         = record.getTeam();
            table.name[index]         = record.getName();
            table.templateType[index] = gameSettings[SETTINGS_TEMPLATE_TYPES_OFFSET + index] & BYTE_MASK;
            table.templateRace[index] = gameSettings[SETTINGS_TEMPLATE_RACES_OFFSET + index] & BYTE_MASK;
            table.savedType[index]    = gameSettings[SETTINGS_SAVED_TYPES_OFFSET + index] & BYTE_MASK;
            table.templateTeam[index] = index < PlayerTable.PLAYABLE_PLAYER_SLOTS ? gameSettings[SETTINGS_FORCES_OFFSET + index] & BYTE_MASK : 0;
        }

        for (int observer = 0; observer < PlayerTable.OBSERVER_SLOTS; observer++) {
            MeleeGameSetup.PlayerRecord record = records[PlayerTable.PLAYABLE_PLAYER_SLOTS + observer];

            if (record.getType() == PlayerTypes.OBSERVER) {
                table.observerId[observer]   = record.getPlayerId();
                table.observerName[observer] = record.getName();
            }
        }

        byte[] customColors = Arrays.copyOfRange(lobbyData, LOBBY_DATA_CUSTOM_COLORS, LOBBY_DATA_SIZE);

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            table.selectableRace[slot] = gameSettings[SETTINGS_SELECTABLE_RACES_OFFSET + slot] != 0;
            table.lobbyColor[slot]     = lobbyData[LOBBY_DATA_COLORS + slot] & BYTE_MASK;
            table.colorLock[slot]      = lobbyData[LOBBY_DATA_COLOR_LOCKS + slot] != 0;
            table.colorKept[slot]      = lobbyData[LOBBY_DATA_COLOR_KEPT + slot] != 0;
            // A kept slot's custom color entry holds its map color.
            table.mapColor[slot] = table.colorKept[slot] ? PlayerColorRows.decodeCustomColor(customColors, slot) >>> Byte.SIZE : -1;
        }

        for (int force = 0; force < FORCES; force++) {
            table.forceFlags[force] = gameSettings[SETTINGS_FORCE_FLAGS_OFFSET + force] & BYTE_MASK;
        }

        table.customColorFlag = lobbyData[LOBBY_DATA_CUSTOM_COLOR_FLAG] != 0;

        return table;
    }


    /**
     * Creates a {@link MeleeGameSetup} instance for the game start ({@link #STATE_GAME}): the lobby table, the game options
     * and the host's name; {@link MeleeGameSetup#resolve} resolves it using {@link #getSeed()}.
     *
     * @param mapData   the map's scenario bytes (the local copy of the lobby's map)
     * @param speed     the game speed, {@code 0..6}
     * @param expansion whether the game is played as Brood War
     * @return the created {@link MeleeGameSetup} instance
     * @throws IOException if the map data is not a valid scenario
     */
    public @NotNull MeleeGameSetup createGameSetup(byte @NotNull [] mapData, int speed, boolean expansion) throws IOException {
        int hostSlot = this.getPlayerSlot(HOST_PLAYER_IDENTIFIER);
        String hostName = hostSlot >= 0 ? this.records[hostSlot].getName() : "";
        MeleeGameSetup game = MeleeGameSetup.createNetwork(mapData, hostName,
                                                           this.createResolutionTable(), this.createResolutionOptions(),
                                                           this.getOptions().createTemplate(),
                                                           speed, expansion);

        // The game information's name and turn rate head the game header.
        game.setNetworkGameInformation(this.session.getGameName(), this.turnRate);

        return game;
    }

    /**
     * Creates a {@link LobbyResolution.Options} instance of this network game's options.
     *
     * @return the created {@link LobbyResolution.Options} instance
     */
    public LobbyResolution.@NotNull Options createResolutionOptions() {
        return createResolutionOptions(this.getOptions());
    }

    /**
     * Creates a {@link LobbyResolution.Options} instance of a network game's options.
     *
     * @param options the game options
     * @return the created {@link LobbyResolution.Options} instance
     */
    static LobbyResolution.@NotNull Options createResolutionOptions(@NotNull LanGameOptions options) {
        byte[] bytes = options.getOptions();

        return new LobbyResolution.Options(true,
                                           options.isUseMapSettings(),
                                           bytes[GameTemplate.TEAMS],
                                           bytes[GameTemplate.START_POSITIONS] != 0,
                                           bytes[GameTemplate.TOURNAMENT] != 0);
    }


    /**
     * Asks for another race for the local player's own slot ({@link #changeRace(int, int)} with {@link #OWN_SLOT}).
     *
     * @param race the race byte
     */
    public void changeRace(int race) {
        this.changeRace(OWN_SLOT, race);
    }

    /**
     * Asks for another race for a slot, as the lobby screen's {@code RequestRaceChange} {@code {slot, race}} does: command
     * {@code 0x41} {@code {slot, race}} in the next turn, the slot {@link #OWN_SLOT} when it is the local player's own. The
     * host checks and applies the request ({@link #requestRace}) and answers with the slot's {@code 0x3e}; it may change a
     * computer slot's race this way.
     *
     * @param slot the slot {@code 0..7}, or {@link #OWN_SLOT}
     * @param race the race byte
     */
    public void changeRace(int slot, int race) {
        int own = this.getPlayerSlot(this.session.getLocalIdentifier());
        int target = own >= 0 && slot == getSlotIdentifier(own) ? OWN_SLOT : slot;

        this.raceCommand = new byte[]{ReplayCommandIds.CHANGE_RACE, (byte) target, (byte) race};
    }

    /**
     * Asks to move the local player into a force, as the lobby screen's {@code RequestPlayer} {@code {team}} handler (a
     * press on a force's header): in Use Map Settings, when the force has no computer, command {@code 0x43}
     * {@code {force}}; otherwise, unless the player already is in that force outside the observers, the move request
     * {@code 0x38} {@code {force, 0, own slot}}.
     *
     * @param force the force or team, {@code 1..4}
     */
    public void requestForce(int force) {
        int own = this.getPlayerSlot(this.session.getLocalIdentifier());

        if (own < 0) {
            return;
        }

        if (this.getOptions().isUseMapSettings() && !this.hasComputerSlot(force)) {
            this.forceCommand = new byte[]{ReplayCommandIds.UMS_TEAM, (byte) force};

            return;
        }

        if (own < PlayerTable.PLAYABLE_PLAYER_SLOTS && this.records[own].getTeam() == force) {
            return;
        }

        this.moveRequestCommand = new byte[]{MOVE_REQUEST_COMMAND, (byte) force, 0, (byte) getSlotIdentifier(own)};
    }

    /**
     * Asks to move the local player to the observers, as the lobby screen's {@code RequestObserver} handler: a player
     * outside the observer slots sends the move request {@code 0x38} {@code {0xFF, 1, own slot}}.
     */
    public void requestObserver() {
        int own = this.getPlayerSlot(this.session.getLocalIdentifier());

        if (own < 0 || own >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return;
        }

        this.moveRequestCommand = new byte[]{MOVE_REQUEST_COMMAND, (byte) NO_FORCE, 1, (byte) getSlotIdentifier(own)};
    }


    /**
     * Sets a slot open, closed or to a computer, as the host's {@code HostRequest} actions {@code "open"},
     * {@code "closed"} and {@code "computer"} do: nothing for the host's own slot or a slot already in that state; a
     * player in the slot is booted first ({@link #boot}; the screen asks {@code chatCONFIRMBOOT_s} before); then the slot
     * command {@code 0x44} {@code {slot, state}}, which the host applies in its turn.
     *
     * @param index the record index
     * @param state {@link #SLOT_COMPUTER}, {@link #SLOT_OPEN} or {@link #SLOT_CLOSED}
     * @return {@code true} if the command was queued, {@code false} otherwise
     */
    public boolean setSlotState(int index, int state) {
        int own = this.getPlayerSlot(this.session.getLocalIdentifier());

        if (!this.host || index == own || state < SLOT_COMPUTER || state > SLOT_CLOSED ||
            index >= PlayerTable.PLAYABLE_PLAYER_SLOTS && state == SLOT_COMPUTER) {
            return false;
        }

        int type = this.records[index].getType();
        int target = getSlotType(index, state);

        if (type == target) {
            return false;
        }

        if (type == PlayerTypes.HUMAN || type == TYPE_OBSERVER) {
            this.boot(index, false);
        }

        this.slotStateCommand = new byte[]{SLOT_STATE_COMMAND, (byte) getSlotIdentifier(index), (byte) state};

        return true;
    }


    /**
     * Boots or bans the player of a slot ({@code HostRequest} {@code "boot"} / {@code "ban"}): the refusal {@code 0x4e}
     * ({@code 0} booted or {@code 1} banned) three times to the player, then the provider drops it with reason {@code 3}.
     * Not the host's own slot.
     *
     * @param index the record index
     * @param ban   whether the player is banned
     * @return {@code true} if a player was booted, {@code false} otherwise
     */
    public boolean boot(int index, boolean ban) {
        MeleeGameSetup.PlayerRecord record = this.records[index];
        int own = this.getPlayerSlot(this.session.getLocalIdentifier());

        if (!this.host || index == own || !PlayerSlots.isPlayerRecord(record)) {
            return false;
        }

        byte[] refusal = {MESSAGE_REFUSAL, (byte) (ban ? BAN_REASON : BOOT_REASON)};

        for (int copy = 0; copy < BOOT_REFUSAL_COPIES; copy++) {
            this.session.sendMessage(record.getPlayerId(), refusal);
        }

        this.session.dropPlayer(record.getPlayerId(), BOOT_DROP_REASON);

        return true;
    }

    /**
     * Swaps two slots, as the host's {@code SwapPlayers} {@code {fromSlot, toSlot}} ("Switch with" / "Move to"): the swap
     * command {@code 0x45} {@code {slot, slot}}, which marks both slots pending.
     *
     * @param from the first record index
     * @param to   the second record index
     */
    public void swapSlots(int from, int to) {
        if (!this.host || from == to) {
            return;
        }

        this.queueSwap(getSlotIdentifier(from), getSlotIdentifier(to));
    }

    /**
     * Asks for another color: command {@code 0x67} {@code {slot, color}} in the next turn (called by the lobby screen's
     * color picker). The host checks the request and answers with {@code 0x68}. The request shares its pending buffer with
     * {@code 0x68}: a later one replaces it.
     *
     * @param slot  the slot, or {@link #OWN_SLOT} for the sender's own slot
     * @param color the color, {@code 0..}{@link #COLOR_DEFAULT}
     */
    public void changeColor(int slot, int color) {
        this.colorCommand = new byte[]{COLOR_REQUEST_COMMAND, (byte) slot, (byte) color};
    }

    /**
     * Runs the lobby: session events, lobby messages, and a turn step at most every {@link #STEP_INTERVAL} milliseconds.
     * Call it after {@link ProviderSession#update()}.
     */
    public void update() {
        LanMapDownloader downloader = this.mapTransfer.getDownloader();

        if (downloader != null) {
            downloader.update();
        }

        for (ProviderSession.Event event = this.session.pollEvent(); event != null; event = this.session.pollEvent()) {
            if (event.getType() == ProviderSession.EVENT_PLAYER_JOINED && this.host) {
                this.sendGameSettings(event.getPlayerIdentifier());
            } else if (event.getType() == ProviderSession.EVENT_PLAYER_LEFT) {
                // The chat panel's line of a player that left or was dropped (reason 0x40000006). The session no longer knows a departed
                // player, so the name is the one its slot record holds until the player is removed below.
                int player = event.getPlayerIdentifier();
                byte[] reason = event.getData();
                boolean dropped = reason.length >= Integer.BYTES && LittleEndianUtils.readInt32(reason, 0) == DROP_REASON;
                int slot = this.getPlayerSlot(player);
                String name = slot >= 0 ? this.records[slot].getName() : "";

                if (player != HOST_PLAYER_IDENTIFIER && !name.isEmpty()) {
                    this.notices.add(new Notice(dropped ? Notice.DROPPED : Notice.LEFT, name));
                }

                this.removePlayer(player);
            }
        }

        for (ProviderSession.Message message = this.session.receiveMessage(); message != null; message = this.session.receiveMessage()) {
            this.receiveMessage(message.getSender(), message.getData());
        }

        this.executeDeferredSlotCommands();

        if (this.left) {
            return;
        }

        int now = this.clock.getAsInt();

        if (!this.started) {
            this.started           = true;
            this.lastCompletedStep = now;

            // The first two turns go out before any step completes: two turns in transit.
            this.session.sendTurn(new byte[]{ReplayCommandIds.KEEP_ALIVE});
            this.session.sendTurn(new byte[]{ReplayCommandIds.KEEP_ALIVE});

            return;
        }

        if (Integer.compareUnsigned(now - this.lastCompletedStep, STEP_INTERVAL) < 0) {
            return;
        }

        ProviderSession.TurnBatch batch = this.session.receiveTurns(0, RECORDS, PlayerTable.OBSERVER_SLOTS);

        if (!batch.isComplete()) {
            if (Integer.compareUnsigned(now - this.lastCompletedStep, TIMEOUT) >= 0) {
                this.timedOut = true;
                this.handleTimeout(batch);
            }

            return;
        }

        // The turn sent at a step carries the commands queued before it; the commands of the consumed turn are executed
        // afterwards (the host's joined-game handling goes out in the turn of the next step).
        this.lastCompletedStep = now;

        byte[] outgoing = this.flushCommands();

        // Advance the countdown before sending; its final step follows the received commands below.
        this.countDown();
        this.session.sendTurn(outgoing);

        for (int slot = 0; slot < RECORDS; slot++) {
            byte[] turn = batch.getTurn(slot);

            if (turn != null) {
                this.executeTurn(getSlotIdentifier(slot), turn);
            }
        }

        if (this.countdown == 1) {
            this.countDown();
        }

        // In the lobby state the transfers' download percent goes out when it changed; a failed transfer ends the lobby
        // with chatFDERROR.
        if (this.state == STATE_LOBBY) {
            if (this.mapTransfer.hasFailed()) {
                if (this.refusal == NO_REFUSAL) {
                    LOGGER.warning("LAN LOBBY MAP TRANSFER FAILED: the lobby ends (chatFDERROR)");
                }

                this.refusal = REFUSAL_FILE_ERROR;
            } else {
                int percent = this.mapTransfer.getDownloadPercent();

                if (percent != this.sentDownloadPercent) {
                    this.sentDownloadPercent = percent;
                    this.pendingDownload     = new byte[]{ReplayCommandIds.DOWNLOAD_PERCENTAGE, (byte) percent};
                }
            }
        }
    }

    /**
     * Opens the ready room of a network game (from the room's setup): briefing state {@link #BRIEFING_RUNNING}, every
     * ready flag clear, and the own turns topped up. The turn stream goes on through the room ({@link #updateBriefing()}).
     *
     * @throws IllegalStateException if the lobby's game has not started
     */
    public void beginBriefing() {
        if (this.state != STATE_GAME) {
            throw Exceptions.newIllegalStateException("The ready room follows the game start.");
        }

        this.briefing = BRIEFING_RUNNING;
        Arrays.fill(this.briefingReady, false);
        this.readyQueued = false;

        this.topUpBriefingTurns();
    }

    /**
     * Presses the ready room's Start (which also shows the room's waiting text, control {@code -12}): queues the ready command
     * {@code 0x54} into the next turn and waits for the others ({@link #BRIEFING_READY}). Only while the room runs.
     */
    public void readyBriefing() {
        if (this.briefing != BRIEFING_RUNNING) {
            return;
        }

        this.readyQueued = true;
        this.briefing    = BRIEFING_READY;
    }

    /**
     * Runs one turn step of the ready room (the out-of-game branch of the turn step): at most every {@link #STEP_INTERVAL} milliseconds after the
     * last completed step, the next turn of every player; once it arrived, the own turns are topped up (the queued ready command in the first) and
     * every player's turn runs through the lobby dispatcher. After the local Start, the room ends when every present player (turn status
     * {@code 0x10000}) is ready: briefing state {@link #BRIEFING_NONE}, and the game's first frame takes the next turn on every client alike.
     *
     * @return {@code true} if every player is ready and the game starts (dialog result {@code 0x65}), {@code false} otherwise
     */
    public boolean updateBriefing() {
        for (ProviderSession.Event event = this.session.pollEvent(); event != null; event = this.session.pollEvent()) {
            if (event.getType() == ProviderSession.EVENT_PLAYER_LEFT) {
                LOGGER.info("LAN BRIEFING PLAYER LEFT: player " + event.getPlayerIdentifier());
            }
        }

        if (this.briefing == BRIEFING_NONE) {
            return false;
        }

        int now = this.clock.getAsInt();

        if (Integer.compareUnsigned(now - this.lastCompletedStep, STEP_INTERVAL) < 0) {
            return false;
        }

        ProviderSession.TurnBatch batch = this.session.receiveTurns(0, RECORDS, PlayerTable.OBSERVER_SLOTS);

        if (!batch.isComplete()) {
            return false;
        }

        this.lastCompletedStep = now;
        this.topUpBriefingTurns();

        for (int slot = 0; slot < RECORDS; slot++) {
            byte[] turn = batch.getTurn(slot);

            if (turn != null) {
                this.executeTurn(getSlotIdentifier(slot), turn);
            }
        }

        if (this.briefing != BRIEFING_READY) {
            return false;
        }

        StringBuilder present = new StringBuilder();

        for (int index = 0; index < RECORDS; index++) {
            if ((batch.getStatus(index) & ProviderSession.TURN_PRESENT) != 0 && !this.briefingReady[index]) {
                return false;
            }

            if ((batch.getStatus(index) & ProviderSession.TURN_PRESENT) != 0) {
                present.append(' ').append(getSlotIdentifier(index));
            }
        }

        this.briefing = BRIEFING_NONE;
        LOGGER.info("LAN BRIEFING END: every present player ready (" + present.toString().trim() + "), own turn " +
                    this.session.getSelf().getChannel(ProviderPacket.TURN).getExpectedSequence());

        return true;
    }

    /**
     * Sends a chat line to every player: message {@code 0x4c} with the NUL-terminated text; a text of more than
     * {@value #CHAT_LIMIT} bytes with its NUL is not sent.
     *
     * @param text the text
     * @return {@code true} if the text fits and was sent, {@code false} otherwise
     */
    public boolean sendChat(@NotNull String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        if (bytes.length + 1 > CHAT_LIMIT) {
            return false;
        }

        byte[] message = new byte[1 + bytes.length + 1];

        message[0] = MESSAGE_CHAT;
        System.arraycopy(bytes, 0, message, 1, bytes.length);
        this.session.sendMessage(ALL_PLAYERS, message);

        return true;
    }

    /**
     * Returns the next received chat line.
     *
     * @return the next received chat line, or {@code null}
     */
    public @Nullable Chat pollChat() {
        return this.chat.poll();
    }

    /**
     * Returns the next lobby notice.
     *
     * @return the next lobby notice, or {@code null}
     */
    public @Nullable Notice pollNotice() {
        return this.notices.poll();
    }


    /**
     * Checks the race request (slot, race, sender): the sender is a player or an observer, the slot is a player slot, the
     * race is Zerg, Terran, Protoss, {@link ScrRaceIds#RANDOM} ({@code 6}) or {@link LobbyResolution#SELECTABLE_RACE}
     * ({@code 7}); outside Use Map Settings any slot, in Use Map Settings only a slot whose {@code SIDE} lets the player
     * choose ({@link #isRaceSelectable}); no saved game is open (a saved game keeps its races). Then the owner rule of the
     * color request: the host in a team game another force only when it has a computer, else its own slot or a computer
     * slot; another player in a team game a slot of its own force, else its own slot.
     *
     * @param slot        the slot {@code 0..7}
     * @param race        the race byte
     * @param senderIndex the sender's record index
     * @return {@code true} if the request passes, {@code false} otherwise
     */
    public boolean isRaceChangeAllowed(int slot, int race, int senderIndex) {
        if (slot >= PlayerTable.PLAYABLE_PLAYER_SLOTS ||
            !(race < ScrRaceIds.PLAYABLE_COUNT || race == ScrRaceIds.RANDOM || race == LobbyResolution.SELECTABLE_RACE) ||
            this.getOptions().isUseMapSettings() && !this.isRaceSelectable(slot) || this.savedGame != null) {
            return false;
        }

        return this.canChangeSlot(slot, senderIndex, this.getOptions().isTeamGame(), this.records[slot].getTeam());
    }

    /**
     * Checks the owner rule of the color and race requests: the host in a team game another force only when it has a
     * computer, else its own slot or a computer slot; another player in a team game a slot of its own force, else its own
     * slot.
     *
     * @param slot        the slot {@code 0..7}
     * @param senderIndex the sender's record index
     * @param teamGame    {@code true} if the game is a team game, {@code false} otherwise
     * @param targetForce the force of the slot
     * @return {@code true} if the sender may change the slot, {@code false} otherwise
     */
    private boolean canChangeSlot(int slot, int senderIndex, boolean teamGame, int targetForce) {
        boolean sameForce = this.records[senderIndex].getTeam() == targetForce;

        if (this.records[senderIndex].getPlayerId() == HOST_PLAYER_IDENTIFIER) {
            if (teamGame && !sameForce && !this.hasComputer(targetForce)) {
                return false;
            }

            return teamGame || slot == getSlotIdentifier(senderIndex) || this.records[slot].getType() == PlayerTypes.EITHER_PREFER_COMPUTER;
        }

        return teamGame ? sameForce : slot == getSlotIdentifier(senderIndex);
    }

    /**
     * Checks whether a slot may take a color: not the reserved color, not a color the map's tileset hides, neither the
     * color of another slot of type {@code 1}, {@code 2}, {@code 5} or {@code 6} nor one similar to it. The lobby screen's
     * color picker grays out the colors that are not ({@code SetUnavailablePlayerColors}).
     *
     * @param color the color, {@code 0..}{@link #COLOR_DEFAULT}
     * @param slot  the slot, {@code 0..7}
     * @return {@code true} if the slot may take the color, {@code false} otherwise
     */
    public boolean isColorAvailable(int color, int slot) {
        Set<Integer> taken = new HashSet<>();

        taken.add(COLOR_RESERVED);

        for (int hidden : HIDDEN_COLORS[this.setup != null ? this.setup.tileset & TILESET_MASK : 0]) {
            taken.add(hidden);
        }

        for (int other = 0; other < PlayerTable.PLAYABLE_PLAYER_SLOTS; other++) {
            if (other == slot) {
                continue;
            }

            int playerType = this.records[other].getType();

            if (playerType != PlayerTypes.COMPUTER && playerType != PlayerTypes.HUMAN && playerType != PlayerTypes.EITHER_PREFER_COMPUTER &&
                playerType != PlayerTypes.EITHER_PREFER_HUMAN) {
                continue;
            }

            int used = this.getColor(other);

            if (used != COLOR_DEFAULT) {
                taken.add(used);
            }

            for (int[] group : SIMILAR_COLORS) {
                if (Arrays.stream(group).anyMatch(member -> member == used)) {
                    Arrays.stream(group).forEach(taken::add);
                }
            }
        }

        return !taken.contains(color);
    }

    /**
     * Checks the host's map transfer for the lobby's start ({@code chatFILETRANSFERRING} unless every player's transfer
     * entry is in state {@code 1}, the map present).
     *
     * @return {@code true} if every player the host sent the map information to answered that it has the map, {@code false} otherwise
     */
    public boolean isMapTransferComplete() {
        LanMapTransfer.SharedFile shared = this.sharedMap;

        return shared == null || this.mapTransfer.isComplete(shared);
    }


    /**
     * Shares the hosted game's map and adds the host itself: its own information returns through the message queue and its
     * transfer then holds the whole file.
     *
     * @param file the map file
     * @return {@code true} if the file is shared, {@code false} otherwise
     */
    public boolean shareMap(@NotNull Path file) {
        if (!this.host || this.sharedMap != null) {
            return false;
        }

        LanMapTransfer.SharedFile shared = this.mapTransfer.share(file);

        if (shared == null) {
            LOGGER.warning("LAN LOBBY MAP NOT SHARED: " + file);

            return false;
        }

        this.sharedMap = shared;
        this.mapTransfer.addPlayer(shared, this.session.getLocalIdentifier());

        return true;
    }

    /**
     * Ends the map transfer when the local player leaves: the hosted game's shared map is canceled for its players ({@link LanMapTransfer#close}).
     */
    public void closeMapTransfer() {
        this.mapTransfer.close(this.sharedMap);
        this.sharedMap = null;
    }

    /**
     * Closes the mod's downloader of the lobby's maps, if any ({@link LanMapTransfer#closeDownloader}).
     */
    public void closeMapDownloader() {
        this.mapTransfer.closeDownloader();
    }

    /**
     * Updates the provider session with the host's slot counts after lobby creation, player changes, slot state changes or
     * swaps. Over the player records the computers (types {@code 1} and {@code 5}) are the reserved slots, the humans
     * ({@code 2}) the human players and the closed ones ({@code 8}) the closed slots; over the four observer records the
     * observers present ({@code 9}), and the observer limit is four less the observer records that are closed ({@code 8})
     * or none ({@code 0}), or {@code 0} for a replay or a saved game. See {@link ProviderSession#setSlotCounts}: with the
     * reserved slots a player joining a lobby whose open player slots are taken gets an observer identifier from
     * {@code 0x80}, the identifier the game's turn exchange gives the observer slots. The session's game statistics then
     * take the human players, the observers present, the observer limit and the closed slots
     * ({@link LanGameStatistics#createWithSlotCounts}), which the game information gives later joiners and the game
     * advertisement is rebuilt from; statistics that cannot be parsed stay as they are. The game state bit {@code 2} is not
     * represented.
     */
    private void updateSlotCounts() {
        if (!this.host) {
            return;
        }

        int reserved = 0;
        int humans = 0;
        int closed = 0;

        for (int index = 0; index < PlayerTable.PLAYABLE_PLAYER_SLOTS; index++) {
            int type = this.records[index].getType();

            if (type == PlayerTypes.COMPUTER || type == PlayerTypes.EITHER_PREFER_COMPUTER) {
                reserved++;
            } else if (type == PlayerTypes.HUMAN) {
                humans++;
            } else if (type == PlayerTypes.CLOSED) {
                closed++;
            }
        }

        int observers = 0;
        int observerLimit = RECORDS - PlayerTable.PLAYABLE_PLAYER_SLOTS;

        for (int index = PlayerTable.PLAYABLE_PLAYER_SLOTS; index < RECORDS; index++) {
            int type = this.records[index].getType();

            if (type == PlayerTypes.NONE || type == PlayerTypes.CLOSED) {
                observerLimit--;
            } else if (type == TYPE_OBSERVER) {
                observers++;
            }
        }

        LanGameStatistics statistics = LanGameStatistics.parse(this.session.getGameStatistics());

        if ((statistics != null && statistics.isReplay()) || this.savedGame != null) {
            observerLimit = 0;
        }

        this.session.setSlotCounts(reserved, observers, observerLimit);

        if (statistics != null) {
            this.session.setGameStatistics(statistics.createWithSlotCounts(humans, observers, observerLimit, closed).encode());
        }
    }

    /**
     * Queues the swap {@code 0x45} of two slot bytes and sets both slots' pending flags. A source byte that names no record
     * index ({@link PlayerSlots#getPlayerIndex}) sets no flag; the swap's execution refuses it ({@link #applySwap}).
     *
     * @param sourceSlot      the source slot byte
     * @param destinationSlot the destination slot identifier
     */
    private void queueSwap(int sourceSlot, int destinationSlot) {
        int sourceIndex = PlayerSlots.getPlayerIndex(sourceSlot);

        this.swapCommand = new byte[]{ReplayCommandIds.SWAP_PLAYERS, (byte) sourceSlot, (byte) destinationSlot};

        if (sourceIndex >= 0) {
            this.pendingSlots[sourceIndex] = true;
        }

        this.pendingSlots[PlayerSlots.getPlayerIndex(destinationSlot)] = true;
    }

    /**
     * Tops the own turns in transit up to {@link #TURNS_IN_TRANSIT} (out of the game); the first carries the queued ready
     * command, the others Keep Alive.
     */
    private void topUpBriefingTurns() {
        while (this.session.getTurnsInTransit() < TURNS_IN_TRANSIT) {
            this.session.sendTurn(this.readyQueued ? new byte[]{READY_COMMAND} : new byte[]{ReplayCommandIds.KEEP_ALIVE});
            this.readyQueued = false;
        }
    }

    /**
     * Handles a player that left or was dropped: the host leaving ends the lobby; any other player's slot
     * returns to its template record (the table the game settings {@code 0x4a} set up) keeping the race and force it had,
     * and its type when that was closed or computer; the player no longer has a slot ({@link #getPlayerSlot}) or a block,
     * and its version state becomes {@link #VERSIONS_LEFT}, held slot or not: a record that still names the player queues
     * zero versions and sends no slot table ({@link #joinPlayer}). No command is sent. The map transfer forgets the player
     * ({@link LanMapTransfer#removePlayer}), and its deferred slot changes are dropped.
     *
     * @param player the provider player identifier of the player
     */
    private void removePlayer(int player) {
        if (player == HOST_PLAYER_IDENTIFIER) {
            this.hostLeft = true;

            return;
        }

        int index = this.getPlayerSlot(player);
        int playerIndex = PlayerSlots.getPlayerIndex(player);

        for (int record = 0; record < RECORDS; record++) {
            byte[] deferred = this.deferredSlotCommands[record];

            if (deferred != null && (deferred[SLOT_COMMAND_PLAYER_OFFSET] & BYTE_MASK) == player) {
                this.deferredSlotCommands[record] = null;
            }
        }

        if (playerIndex >= 0) {
            this.playerVersionStates[playerIndex] = VERSIONS_LEFT;
            this.downloadPercents[playerIndex]    = -1;
        }

        this.mapTransfer.removePlayer(player);
        LOGGER.info("LAN LOBBY PLAYER LEFT: player " + player + ", slot " + index + ", lobby state " + this.state);

        if (index < 0) {
            return;
        }

        MeleeGameSetup.PlayerRecord previous = this.records[index];
        int type = previous.getType() == PlayerTypes.CLOSED || previous.getType() == PlayerTypes.EITHER_PREFER_COMPUTER
                   ? previous.getType()
                   : this.templateTypes[index] & BYTE_MASK;

        this.records[index] = new MeleeGameSetup.PlayerRecord(-1, type, previous.getRace(), previous.getTeam(), "");
        // The departed player's slot gives up its saved slot.
        this.startBytes[index] = (byte) NO_SAVED_SLOT;

        this.playerSlots.remove(player);
        this.playerBlocks[playerIndex] = new byte[PLAYER_BLOCK_SIZE];
        this.updateSlotCounts();
    }

    /**
     * Runs the lobby timeout (on every step attempt once no step completed for {@link #TIMEOUT} milliseconds): of the
     * eight player slots' turn statuses, the present ones that are not responsive are the stalled ones. With fewer
     * responsive than stalled players the local player leaves; otherwise every present, unresponsive player and observer
     * is dropped ({@link ProviderSession#dropPlayer}). Both use reason {@link #DROP_REASON}.
     *
     * @param batch the incomplete turn batch with the turn statuses
     */
    private void handleTimeout(ProviderSession.@NotNull TurnBatch batch) {
        int present = 0;
        int stalled = 0;

        for (int index = 0; index < PlayerTable.PLAYABLE_PLAYER_SLOTS; index++) {
            int status = batch.getStatus(index);

            if ((status & ProviderSession.TURN_PRESENT) != 0) {
                present++;

                if ((status & ProviderSession.TURN_RESPONSIVE) == 0) {
                    stalled++;
                }
            }
        }

        if (present - stalled < stalled) {
            this.session.leave(DROP_REASON);
            this.left = true;

            return;
        }

        for (int index = 0; index < RECORDS; index++) {
            int status = batch.getStatus(index);

            if ((status & ProviderSession.TURN_PRESENT) != 0 && (status & ProviderSession.TURN_RESPONSIVE) == 0) {
                this.session.dropPlayer(getSlotIdentifier(index), DROP_REASON);
            }
        }
    }

    /**
     * Collects the pending commands into the next turn: download percent ({@code 0x3d}), slot changes ({@code 0x3e}) of
     * slots {@code 7..0} and observers {@code 0x80..0x83}, new net players ({@code 0x3f}) of player ids in the same order,
     * race ({@code 0x41}), force ({@code 0x43}), slot state ({@code 0x44}), swap ({@code 0x45}), move request
     * ({@code 0x38}), the saved data ({@code 0x48}), color ({@code 0x67}/{@code 0x68}), lobby data ({@code 0x69}) and last
     * the joined game ({@code 0x40}), with the start ({@code 0x3c}) first. An empty turn is {@code 05}.
     *
     * @return the turn
     */
    private byte @NotNull [] flushCommands() {
        // State 7 (the host queued 0x48) becomes 8; from state 8 on nothing is flushed any more.
        if (this.state == STATE_SAVED_DATA_QUEUED) {
            this.state = STATE_WAITING_FOR_SAVED_DATA;
        } else if (this.state > STATE_SAVED_DATA_QUEUED) {
            return new byte[]{ReplayCommandIds.KEEP_ALIVE};
        }

        ByteArrayOutputStream turn = new ByteArrayOutputStream(TURN_CAPACITY);

        if (this.startCommand != null) {
            turn.writeBytes(this.startCommand);
            this.startCommand = null;
        }

        if (this.pendingDownload != null) {
            turn.writeBytes(this.pendingDownload);
            this.pendingDownload = null;
        }

        for (byte[][] group : new byte[][][]{this.slotCommands, this.playerCommands}) {
            for (int index = PlayerTable.PLAYABLE_PLAYER_SLOTS - 1; index >= 0; index--) {
                if (group[index] != null) {
                    turn.writeBytes(group[index]);
                    group[index] = null;
                }
            }

            for (int index = PlayerTable.PLAYABLE_PLAYER_SLOTS; index < RECORDS; index++) {
                if (group[index] != null) {
                    turn.writeBytes(group[index]);
                    group[index] = null;
                }
            }
        }

        for (byte[] command : new byte[][]{this.raceCommand, this.forceCommand, this.slotStateCommand, this.swapCommand, this.moveRequestCommand,
                                           this.savedDataCommand, this.colorCommand, this.lobbyDataCommand, this.joinCommand}) {
            if (command != null) {
                turn.writeBytes(command);
            }
        }

        this.raceCommand        = null;
        this.forceCommand       = null;
        this.slotStateCommand   = null;
        this.swapCommand        = null;
        this.moveRequestCommand = null;
        this.savedDataCommand   = null;
        this.colorCommand       = null;
        this.lobbyDataCommand   = null;
        this.joinCommand        = null;

        return turn.size() == 0 ? new byte[]{ReplayCommandIds.KEEP_ALIVE} : turn.toByteArray();
    }

    /**
     * Dispatches a lobby message: each id needs its exact length.
     *
     * @param sender  the sending player
     * @param message the message
     */
    private void receiveMessage(int sender, byte @NotNull [] message) {
        if (message.length == 0) {
            return;
        }

        int messageType = message[0] & BYTE_MASK;

        switch (messageType) {
            case MESSAGE_PLAYER_INFORMATION -> {
                if (message.length == PLAYER_INFORMATION_SIZE) {
                    LOGGER.info("LAN LOBBY PLAYER INFORMATION: player " + sender + " name " +
                                readString(message, PLAYER_INFORMATION_NAME_OFFSET, PLAYER_INFORMATION_NAME_LIMIT));
                }
            }
            case MESSAGE_PLAYER_JOINED -> {
                // From the host, a player joined; everyone announces itself to all others.
                if (message.length == PLAYER_JOINED_SIZE && sender == HOST_PLAYER_IDENTIFIER) {
                    this.sendPlayerInformation();

                    // Another player's join is the chat panel's line strPLAYER_JOINED.
                    int joined = LittleEndianUtils.readInt32(message, 1);
                    String name = this.getPlayerName(joined);

                    if (joined != this.session.getLocalIdentifier() && !name.isEmpty()) {
                        this.notices.add(new Notice(Notice.JOINED, name));
                    }
                }
            }
            case MESSAGE_GAME_SETTINGS -> {
                if (message.length == GAME_SETTINGS_SIZE && sender == HOST_PLAYER_IDENTIFIER) {
                    this.receiveGameSettings(message);
                }
            }
            case MESSAGE_FORCE_NAMES -> {
                // Joiners receive one map force name per force.
                if (message.length == 1 + FORCES * FORCE_NAME_SIZE && !this.host) {
                    for (int force = 0; force < FORCES; force++) {
                        this.forceNames[force] = LegacyTextUtils.decodeTerminated(message, 1 + force * FORCE_NAME_SIZE, FORCE_NAME_SIZE);
                    }
                }
            }
            case MESSAGE_CHAT -> {
                // The text must end within 0x78 bytes and hold no control byte; the sender must have a name and its bit in
                // the game's player mask (approximated by an occupied slot) or be an observer; then the panel shows it.
                int length = TerminatedStringUtils.getTerminatedLength(message, 1, CHAT_LIMIT);

                if (length > 0 && this.getPlayerSlot(sender) >= 0) {
                    this.chat.add(new Chat(sender, new String(message, 1, length - 1, StandardCharsets.UTF_8)));
                }
            }
            case MESSAGE_REFUSAL -> {
                if (message.length == REFUSAL_MESSAGE_SIZE && sender == HOST_PLAYER_IDENTIFIER) {
                    LOGGER.warning("LAN LOBBY REFUSED: reason " + (message[1] & BYTE_MASK));

                    // The lobby screen's error popup and leave.
                    this.refusal = message[1] & BYTE_MASK;
                }
            }
            case MESSAGE_MAP_TRANSFER -> {
                if (message.length >= MAP_TRANSFER_HEADER_SIZE) {
                    int length = LittleEndianUtils.readUnsigned16(message, MAP_TRANSFER_LENGTH_OFFSET);

                    this.receiveMapTransfer(sender, Arrays.copyOfRange(message, MAP_TRANSFER_HEADER_SIZE,
                                                                       Math.min(message.length, MAP_TRANSFER_HEADER_SIZE + length)));
                }
            }
            case MESSAGE_SLOT_TABLE -> {
                if (message.length == SLOT_TABLE_SIZE && sender == HOST_PLAYER_IDENTIFIER) {
                    int index = PlayerSlots.getPlayerIndex(message[1] & BYTE_MASK);

                    if (index >= 0) {
                        System.arraycopy(message, SLOT_TABLE_BLOCK_OFFSET, this.playerBlocks[index], 0, PLAYER_BLOCK_SIZE);
                    }
                }
            }
            default -> {
                // 0x4c/0x4d chat, 0x50: nothing a joiner must answer.
            }
        }
    }

    /**
     * Handles message {@code 0x4a} on a joiner: the template table and the map settings; the joiner answers with its
     * joined-game command ({@code 0x40}).
     *
     * @param message the game settings message
     */
    private void receiveGameSettings(byte @NotNull [] message) {
        this.gameSettings = message.clone();
        System.arraycopy(message, SETTINGS_TEMPLATE_TYPES_OFFSET, this.templateTypes, 0, RECORDS);
        this.joinCommand = this.createJoinCommand();

        // The joined-game command queued, state 3 (a repeated 0x4a goes back to 3 too).
        this.state = STATE_JOINED;
        LOGGER.info("LAN LOBBY STATE: joined-game command queued (state 3)");
    }

    /**
     * Creates the joined-game command: byte the saved game's lobby slot, byte its record slot, DWORD its random state (all
     * three zero without a saved game, {@link #setSavedGame}), WORD {@code 0}, WORD {@code 1}, WORD {@link #JOIN_VERSION},
     * byte {@code 0}, and the local {@code 350}-byte block.
     *
     * @return the command
     */
    private byte @NotNull [] createJoinCommand() {
        SaveGameInformation saved = this.savedGame;
        int lobbySlot = saved == null ? 0 : saved.getLobbySlot();
        int recordSlot = saved == null ? 0 : saved.getRecordSlot();
        int randomState = saved == null ? 0 : saved.getRandomState();
        ByteBuffer command = ByteBuffer.allocate(JOIN_COMMAND_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        command.put((byte) ReplayCommandIds.JOINED_GAME).put((byte) lobbySlot).put((byte) recordSlot).putInt(randomState);
        command.putShort((short) 0).putShort((short) 1).putShort((short) JOIN_VERSION).put((byte) 0);
        command.put(this.localBlock);

        return command.array();
    }

    /**
     * Handles the map transfer messages ({@code 0x4f}) by {@link LanMapTransfer}.
     *
     * @param sender the sending player
     * @param data   the transfer message after the message header
     */
    private void receiveMapTransfer(int sender, byte @NotNull [] data) {
        if (data.length > 0 && (data[0] & BYTE_MASK) != LanMapTransfer.CHUNK && (data[0] & BYTE_MASK) != LanMapTransfer.ACKNOWLEDGE) {
            LOGGER.info("LAN LOBBY MAP TRANSFER: from player " + sender + ", type " + (data[0] & BYTE_MASK) + ", " +
                        HexFormat.of().formatHex(data, 0, Math.min(data.length, LOGGED_TRANSFER_BYTES)));
        }

        this.mapTransfer.receive(sender, data);
    }

    private void sendMapTransfer(int target, byte @NotNull [] data) {
        ByteBuffer message = ByteBuffer.allocate(MAP_TRANSFER_HEADER_SIZE + data.length).order(ByteOrder.LITTLE_ENDIAN);

        message.put((byte) MESSAGE_MAP_TRANSFER).putShort((short) data.length).put(data);
        this.session.sendMessage(target, message.array());
    }

    /**
     * Sends player information with the local id, unknown region and LAN name, the name bytes cut as the name buffer holds
     * them ({@link TerminatedStringUtils#putText}), leaving the opaque BattleTag and trailing fields zero-filled.
     */
    private void sendPlayerInformation() {
        ByteBuffer message = ByteBuffer.allocate(PLAYER_INFORMATION_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        byte[] name = this.localName.getBytes(StandardCharsets.UTF_8);

        message.put((byte) MESSAGE_PLAYER_INFORMATION).put((byte) 0).putInt(this.session.getLocalIdentifier());
        message.position(PLAYER_INFORMATION_REGION_OFFSET);
        message.put(LanPresenceInformation.UNKNOWN_REGION.getBytes(StandardCharsets.UTF_8));
        TerminatedStringUtils.putText(name, 0, name.length, message.array(), PLAYER_INFORMATION_NAME_OFFSET, PLAYER_INFORMATION_NAME_LIMIT);
        this.session.sendMessage(OTHER_PLAYERS, message.array());
    }

    /**
     * Sends the host's answer to a new player (from the player-joined handler): the force names ({@code 0x4b}) when the
     * setup has them, the game settings ({@code 0x4a}) and {@code 0x50}.
     *
     * @param player the new player
     */
    private void sendGameSettings(int player) {
        Setup hostSetup = this.setup;

        if (hostSetup != null && hostSetup.forceNames != null) {
            ByteBuffer names = ByteBuffer.allocate(1 + FORCES * FORCE_NAME_SIZE);

            names.put((byte) MESSAGE_FORCE_NAMES);

            for (byte[] name : hostSetup.forceNames) {
                names.put(name);
            }

            this.session.sendMessage(player, names.array());
        }

        if (this.gameSettings.length == GAME_SETTINGS_SIZE) {
            this.session.sendMessage(player, this.gameSettings);
        }

        this.session.sendMessage(player, new byte[]{MESSAGE_SETTINGS_END});
    }

    /**
     * Executes one player's turn in the lobby: the lobby command dispatcher with its lengths; an unknown or truncated
     * command ends the turn.
     *
     * @param slotPlayer the player identifier of the turn's slot
     * @param turn       the turn's commands
     */
    private void executeTurn(int slotPlayer, byte @NotNull [] turn) {
        int offset = 0;

        while (offset < turn.length) {
            int commandType = turn[offset] & BYTE_MASK;
            int length = switch (commandType) {
                case ReplayCommandIds.KEEP_ALIVE, ReplayCommandIds.VOICE_DISABLE, ReplayCommandIds.START_GAME, READY_COMMAND,
                     ReplayCommandIds.MAKE_GAME_PUBLIC -> NO_ARGUMENT_COMMAND_SIZE;
                case ReplayCommandIds.DOWNLOAD_PERCENTAGE, ReplayCommandIds.TEAM_GAME_TEAM, ReplayCommandIds.UMS_TEAM -> ONE_ARGUMENT_COMMAND_SIZE;
                case ReplayCommandIds.CHANGE_RACE, SLOT_STATE_COMMAND, ReplayCommandIds.SWAP_PLAYERS, COLOR_REQUEST_COMMAND,
                     COLOR_COMMAND -> TWO_ARGUMENT_COMMAND_SIZE;
                case MOVE_REQUEST_COMMAND -> MOVE_REQUEST_SIZE;
                case ReplayCommandIds.CHANGE_GAME_SLOT -> SLOT_COMMAND_SIZE;
                case ReplayCommandIds.NEW_NET_PLAYER -> NEW_NET_PLAYER_SIZE;
                case ReplayCommandIds.SAVED_DATA -> SAVED_DATA_SIZE;
                case LOBBY_DATA_COMMAND -> LOBBY_DATA_COMMAND_SIZE;
                case ReplayCommandIds.JOINED_GAME -> JOIN_COMMAND_SIZE;
                default -> -1;
            };

            if (length < 0 || offset + length > turn.length) {
                if (this.unknownCommandReports++ < UNKNOWN_COMMAND_REPORTS) {
                    LOGGER.warning("LAN LOBBY TURN: player " + slotPlayer + " command 0x" + Integer.toHexString(commandType) + " at byte " +
                                   offset + " of " + turn.length + " ends the turn");
                }

                return;
            }

            this.executeCommand(slotPlayer, Arrays.copyOfRange(turn, offset, offset + length));
            offset += length;
        }
    }

    private void executeCommand(int player, byte @NotNull [] command) {
        int playerIndex = PlayerSlots.getPlayerIndex(player);

        switch (command[0] & BYTE_MASK) {
            case READY_COMMAND -> {
                if (playerIndex >= 0) {
                    this.briefingReady[playerIndex] = true;
                    LOGGER.info("LAN BRIEFING READY: player " + player + " (own turn " +
                                this.session.getSelf().getChannel(ProviderPacket.TURN).getExpectedSequence() + ")");
                }
            }
            case ReplayCommandIds.DOWNLOAD_PERCENTAGE -> {
                int percent = command[1] & BYTE_MASK;

                if (playerIndex >= 0) {
                    this.downloadPercents[playerIndex] = percent > MAXIMUM_DOWNLOAD_PERCENT ? 0 : percent;
                }
            }
            case ReplayCommandIds.CHANGE_GAME_SLOT -> {
                if (player == HOST_PLAYER_IDENTIFIER) {
                    this.executeSlotCommand(command);
                }
            }
            case ReplayCommandIds.NEW_NET_PLAYER -> this.executeNewNetPlayer(command);
            case ReplayCommandIds.JOINED_GAME -> {
                if (this.host) {
                    this.joinPlayer(player, command);
                }
            }
            case ReplayCommandIds.CHANGE_RACE -> {
                if (this.host) {
                    this.requestRace(player, command[1] & BYTE_MASK, command[SECOND_ARGUMENT_OFFSET] & BYTE_MASK);
                }
            }
            case ReplayCommandIds.TEAM_GAME_TEAM -> {
                if (this.host) {
                    this.moveToTeam(player, command[1] & BYTE_MASK);
                }
            }
            case ReplayCommandIds.UMS_TEAM -> {
                if (this.host) {
                    this.moveToForce(player, command[1] & BYTE_MASK);
                }
            }
            case MOVE_REQUEST_COMMAND -> {
                if (this.host) {
                    this.handleMoveRequest(player, command);
                }
            }
            case SLOT_STATE_COMMAND -> {
                if (this.host && player == HOST_PLAYER_IDENTIFIER) {
                    this.applySlotState(command[1] & BYTE_MASK, command[SECOND_ARGUMENT_OFFSET] & BYTE_MASK);
                }
            }
            case ReplayCommandIds.SWAP_PLAYERS -> {
                if (this.host && player == HOST_PLAYER_IDENTIFIER) {
                    this.applySwap(command[1] & BYTE_MASK, command[SECOND_ARGUMENT_OFFSET] & BYTE_MASK);
                }
            }
            case ReplayCommandIds.START_GAME -> {
                // Only in the lobby state, otherwise the lobby error 4 (chatUNJOINABLE) and leave; the start check passes
                // on a LAN.
                if (this.state != STATE_LOBBY) {
                    LOGGER.warning("LAN LOBBY START IN STATE " + this.state + ": unjoinable (lobby error 4)");
                    this.refusal = UNJOINABLE_ERROR;
                } else if (player == HOST_PLAYER_IDENTIFIER) {
                    this.state     = STATE_COUNTDOWN;
                    this.countdown = Math.max(TURNS_IN_TRANSIT * COUNTDOWN_TRANSIT_ROUNDS + COUNTDOWN_DISPLAY_STEPS, START_COUNTDOWN);
                }
            }
            case ReplayCommandIds.SAVED_DATA -> {
                // While waiting, every client takes the host's seed and saved-slot bytes.
                if (player == HOST_PLAYER_IDENTIFIER && this.state == STATE_WAITING_FOR_SAVED_DATA) {
                    this.seed = LittleEndianUtils.readInt32(command, 1);
                    System.arraycopy(command, SAVED_DATA_SLOTS_OFFSET, this.startBytes, 0, PlayerTable.PLAYABLE_PLAYER_SLOTS);
                }
            }
            case COLOR_REQUEST_COMMAND -> {
                if (this.host) {
                    this.requestColor(player, command[1] & BYTE_MASK, command[SECOND_ARGUMENT_OFFSET] & BYTE_MASK);
                }
            }
            case COLOR_COMMAND -> {
                // From the host, on the other clients (the host set the color when it queued the command). Its check of
                // the slot's type and player name is not checked here.
                if (player == HOST_PLAYER_IDENTIFIER && !this.host && (command[1] & BYTE_MASK) < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                    this.lobbyData[LOBBY_DATA_COLORS + (command[1] & BYTE_MASK)] = command[SECOND_ARGUMENT_OFFSET];
                }
            }
            case LOBBY_DATA_COMMAND -> {
                // From the host, on the other clients.
                if (player == HOST_PLAYER_IDENTIFIER && !this.host) {
                    System.arraycopy(command, 1, this.lobbyData, 0, LOBBY_DATA_SIZE);
                }
            }
            default -> {
                // 05 (no command) and lobby commands this lobby does not issue.
            }
        }
    }

    /**
     * Executes the host's slot change command {@code 0x3e}: the whole command is ignored unless its slot is a player or
     * observer slot identifier and its player id one too or {@link ProviderPeer#UNKNOWN_IDENTIFIER}
     * ({@link PlayerSlots#isPlayerIdentifier}). It clears the slot's pending flag and the player's request flag; a joiner
     * then takes the record (the host's records are already its own). A human record of a provider player the session does not
     * know yet, unless the id is an observer's, waits until the player is known ({@link #executeDeferredSlotCommands}); a joiner
     * takes records from its first one after its joined-game command (state {@code 3 -> 4}) and none before. The record keeps the
     * player id of the command, an open slot's too; it takes the player's name only as a human or in an observer slot. The players'
     * slots follow ({@link PlayerSlots#update}).
     *
     * @param command the slot change command
     */
    private void executeSlotCommand(byte @NotNull [] command) {
        int slot = command[1] & BYTE_MASK;
        int identifier = command[SLOT_COMMAND_PLAYER_OFFSET] & BYTE_MASK;

        if (!PlayerSlots.isPlayerIdentifier(slot) || (!PlayerSlots.isPlayerIdentifier(identifier) && identifier != ProviderPeer.UNKNOWN_IDENTIFIER)) {
            return;
        }

        int index = PlayerSlots.getPlayerIndex(slot);

        this.pendingSlots[index]         = false;
        this.deferredSlotCommands[index] = null;

        int playerIndex = PlayerSlots.getPlayerIndex(identifier);

        if (playerIndex >= 0) {
            this.pendingRequests[playerIndex] = false;
        }

        int playerIdentifier = identifier == ProviderPeer.UNKNOWN_IDENTIFIER ? -1 : identifier;
        int type = command[SLOT_COMMAND_TYPE_OFFSET] & BYTE_MASK;
        boolean known = this.isSessionPlayer(playerIdentifier);

        if (this.host) {
            return;
        }

        if (type == PlayerTypes.HUMAN && !known && playerIndex >= 0 && playerIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            this.deferredSlotCommands[index] = command.clone();

            return;
        }

        if (this.state == STATE_JOINED) {
            this.state = STATE_LOBBY;
            LOGGER.info("LAN LOBBY STATE: first slot record after the joined-game command (state 4)");
        } else if (this.state < STATE_JOINED) {
            return;
        }

        String name = type == PlayerTypes.HUMAN || index >= PlayerTable.PLAYABLE_PLAYER_SLOTS ? this.getPlayerName(playerIdentifier) : "";

        this.records[index] = new MeleeGameSetup.PlayerRecord(playerIdentifier,
                                                              type, command[SLOT_COMMAND_RACE_OFFSET] & BYTE_MASK,
                                                              command[SLOT_COMMAND_FORCE_OFFSET] & BYTE_MASK,
                                                              name);
        this.playerSlots.update(this.records, RECORDS);
    }

    /**
     * Executes the deferred slot changes {@code 0x3e} whose players the session knows by now.
     */
    private void executeDeferredSlotCommands() {
        for (int index = 0; index < RECORDS; index++) {
            byte[] command = this.deferredSlotCommands[index];

            if (command != null && this.isSessionPlayer(command[SLOT_COMMAND_PLAYER_OFFSET] & BYTE_MASK)) {
                this.executeSlotCommand(command);
            }
        }
    }

    /**
     * Executes a new net player command {@code 0x3f} {@code {player id, flags WORD, WORD, version WORD}} on a joiner from
     * its first slot record on (state {@code 4}): the player's versions are set ({@link #setPlayerVersions}, nothing for a
     * player that is not in the session), the flags WORD keeping the bits of the versions the player already has. The host
     * keeps the versions of the joined-game commands.
     *
     * @param command the new net player command
     */
    private void executeNewNetPlayer(byte @NotNull [] command) {
        int player = command[1] & BYTE_MASK;
        int index = PlayerSlots.getPlayerIndex(player);

        if (this.host || this.state < STATE_LOBBY || index < 0) {
            return;
        }

        ByteBuffer words = ByteBuffer.wrap(command, NEW_NET_PLAYER_VERSIONS_OFFSET, PLAYER_VERSION_WORDS * Short.BYTES)
                                     .order(ByteOrder.LITTLE_ENDIAN);
        int flags = words.getShort() & JOIN_FLAGS_MASK;

        if (this.hasPlayerVersions(player)) {
            flags |= this.playerVersions[index][0];
        }

        this.setPlayerVersions(player, flags, words.getShort() & WORD_MASK, words.getShort() & WORD_MASK);
    }

    /**
     * Decrements the countdown after a completed step. At {@link #TURNS_IN_TRANSIT} plus {@link #SAVED_DATA_STEPS}, the
     * host queues the seed and saved-slot bytes while joiners wait for them. At zero, the game starts.
     */
    private void countDown() {
        if (this.countdown == 0) {
            return;
        }

        this.countdown--;

        if (this.countdown == TURNS_IN_TRANSIT + SAVED_DATA_STEPS) {
            if (this.host) {
                this.state = STATE_SAVED_DATA_QUEUED;

                ByteBuffer command = ByteBuffer.allocate(SAVED_DATA_SIZE).order(ByteOrder.LITTLE_ENDIAN);

                command.put((byte) ReplayCommandIds.SAVED_DATA).putInt(this.seedSource.getAsInt())
                       .put(this.startBytes, 0, PlayerTable.PLAYABLE_PLAYER_SLOTS);
                this.savedDataCommand = command.array();
            } else {
                this.state = STATE_WAITING_FOR_SAVED_DATA;
            }
        }

        if (this.countdown == 0) {
            this.state = STATE_GAME;
        }
    }

    /**
     * Handles the host's color request after the request check: a color that is not available
     * ({@link #isColorAvailable}) sets the slot to {@link #COLOR_DEFAULT}, otherwise the slot takes it; the host sets the
     * color and queues {@code 0x68} for the other clients.
     * <p>
     * In a team game the check compares forces instead of slots, and a force with a computer takes the color on every
     * slot; the pending buffer holds one {@code 0x68}, so other clients learn only the last slot's color.
     * Requests are refused when a saved game is open.
     *
     * @param player the requesting player
     * @param slot   the requested slot, or {@link #OWN_SLOT}
     * @param color  the requested color
     */
    private void requestColor(int player, int slot, int color) {
        int senderIndex = this.getPlayerSlot(player);

        if (senderIndex < 0) {
            return;
        }

        int senderSlot = getSlotIdentifier(senderIndex);
        int target = slot == OWN_SLOT ? senderSlot : slot;

        // No color changes in a saved game.
        if (target >= PlayerTable.PLAYABLE_PLAYER_SLOTS || color >= COLOR_COUNT ||
            this.lobbyData[LOBBY_DATA_COLOR_LOCKS + target] != 0 || this.savedGame != null) {
            return;
        }

        boolean teamGame = this.getOptions().isTeamGame();
        int targetForce = this.records[target].getTeam();

        if (!this.canChangeSlot(target, senderIndex, teamGame, targetForce)) {
            return;
        }

        if (!this.isColorAvailable(color, target)) {
            this.lobbyData[LOBBY_DATA_COLORS + target] = (byte) COLOR_DEFAULT;
            this.colorCommand                          = new byte[]{COLOR_COMMAND, (byte) target, (byte) COLOR_DEFAULT};

            return;
        }

        if (teamGame && this.hasComputer(targetForce)) {
            // Every slot of the force takes the color; the one pending command keeps the last slot.
            for (int member = 0; member < PlayerTable.PLAYABLE_PLAYER_SLOTS; member++) {
                if (this.records[member].getTeam() == targetForce) {
                    this.lobbyData[LOBBY_DATA_COLORS + member] = (byte) color;
                    this.colorCommand                          = new byte[]{COLOR_COMMAND, (byte) member, (byte) color};
                }
            }

            return;
        }

        this.lobbyData[LOBBY_DATA_COLORS + target] = (byte) color;
        this.colorCommand                          = new byte[]{COLOR_COMMAND, (byte) target, (byte) color};
    }

    /**
     * Handles the host's race request (command {@code 0x41} {@code {slot, race}}, in the lobby state): the slot
     * {@link #OWN_SLOT} is the sender's own; the request check {@link #isRaceChangeAllowed} must pass; in a team game
     * whose target force has a computer every slot of that force takes the race, otherwise the slot alone; each changed
     * slot's record is queued as its {@code 0x3e}.
     *
     * @param player the requesting player
     * @param slot   the requested slot, or {@link #OWN_SLOT}
     * @param race   the requested race byte
     */
    private void requestRace(int player, int slot, int race) {
        int senderIndex = this.getPlayerSlot(player);

        if (this.state < STATE_LOBBY || senderIndex < 0) {
            return;
        }

        int target = slot == OWN_SLOT ? getSlotIdentifier(senderIndex) : slot;

        if (!this.isRaceChangeAllowed(target, race, senderIndex)) {
            return;
        }

        int force = this.records[target].getTeam();
        boolean wholeForce = this.getOptions().isTeamGame() && this.hasComputer(force);

        for (int member = 0; member < PlayerTable.PLAYABLE_PLAYER_SLOTS; member++) {
            if (wholeForce ? this.records[member].getTeam() == force : member == target) {
                MeleeGameSetup.PlayerRecord record = this.records[member];

                this.records[member]      = new MeleeGameSetup.PlayerRecord(record.getPlayerId(),
                                                                            record.getType(), race,
                                                                            record.getTeam(),
                                                                            record.getName());
                this.slotCommands[member] = createSlotCommand(member, this.records[member]);
            }
        }
    }

    /**
     * Handles the host's force change of Use Map Settings (command {@code 0x43}): in the lobby state, for a force
     * {@code 1..4} without a computer, the sender (a player slot or an observer, type {@code 2} or {@code 9}) moves:
     * <ul>
     * <li>within its own force (a player slot whose force is the requested one): to the next open slot after its own,
     * going round the eight slots, whose pending flag is clear;</li>
     * <li>into another force, or from an observer slot: to the force's first open slot whose pending flag is clear
     * ({@link #reserveOpenSlot}).</li>
     * </ul>
     * The chosen slot's pending flag is set; the move ({@link #moveSlot}) queues a slot change for both slots.
     *
     * @param player the requesting player
     * @param force  the requested force
     */
    private void moveToForce(int player, int force) {
        LanGameOptions options = this.getOptions();

        if (this.state < STATE_LOBBY || !options.isUseMapSettings() || force < 1 || force > FORCES) {
            return;
        }

        if (this.hasComputerSlot(force)) {
            return;
        }

        int sourceIndex = this.getPlayerSlot(player);

        if (sourceIndex < 0) {
            return;
        }

        int type = this.records[sourceIndex].getType();

        if (type != PlayerTypes.HUMAN && type != TYPE_OBSERVER) {
            return;
        }

        boolean withinForce = sourceIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS && this.records[sourceIndex].getTeam() == force;
        int target = -1;

        if (withinForce) {
            for (int step = 0; step < PlayerTable.PLAYABLE_PLAYER_SLOTS && target < 0; step++) {
                int candidate = (sourceIndex + 1 + step) % PlayerTable.PLAYABLE_PLAYER_SLOTS;

                if (this.records[candidate].getTeam() == force && this.records[candidate].getType() == TYPE_OPEN && !this.pendingSlots[candidate]) {
                    target = candidate;
                }
            }

            if (target >= 0) {
                this.pendingSlots[target] = true;
            }
        } else {
            target = reserveOpenSlot(this.records, this.pendingSlots, force);
        }

        if (target < 0) {
            return;
        }

        this.moveSlot(sourceIndex, target);
    }

    /**
     * Handles the host's move request (command {@code 0x38} {@code {force, observer, slot}}), in the lobby state only:
     * <ul>
     * <li>to a force (observer byte {@code 0}): unless the sender has a request under way or already is in that force
     * outside the observers, the force's first open slot whose pending flag is clear ({@link #reserveOpenSlot}); the request
     * flag is set and the swap {@code 0x45} {@code {sender slot, slot}} queued;</li>
     * <li>to the observers (observer byte nonzero): a sender outside the observer slots takes the first open observer slot
     * whose pending flag is clear ({@link #reserveObserverSlot}) by the swap {@code 0x45}
     * {@code {slot of the request, observer slot}}; the request's slot byte goes into the swap as it came, so a byte that
     * names no slot makes a swap that no client executes ({@link #applySwap}).</li>
     * </ul>
     *
     * @param player  the requesting player
     * @param command the move request command
     */
    private void handleMoveRequest(int player, byte @NotNull [] command) {
        if (this.state != STATE_LOBBY) {
            return;
        }

        int sourceIndex = this.getPlayerSlot(player);
        int playerIndex = PlayerSlots.getPlayerIndex(player);

        if (sourceIndex < 0) {
            return;
        }

        if (command[MOVE_REQUEST_OBSERVER_OFFSET] == 0) {
            int force = command[1] & BYTE_MASK;

            if (this.pendingRequests[playerIndex] ||
                (sourceIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS && this.records[sourceIndex].getTeam() == force)) {
                return;
            }

            int target = reserveOpenSlot(this.records, this.pendingSlots, force);

            if (target < 0) {
                return;
            }

            this.pendingRequests[playerIndex] = true;
            this.queueSwap(getSlotIdentifier(sourceIndex), getSlotIdentifier(target));

            return;
        }

        if (sourceIndex >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            return;
        }

        int target = reserveObserverSlot(this.records, this.pendingSlots);

        // The swap carries the request's slot byte as it came.
        if (target >= 0) {
            this.queueSwap(command[MOVE_REQUEST_SLOT_OFFSET] & BYTE_MASK, getSlotIdentifier(target));
        }
    }

    /**
     * Applies the host's slot state command ({@code 0x44} {@code {slot, state}}) from the host, in the lobby, for a slot
     * byte that is a player or observer slot identifier ({@link PlayerSlots#isPlayerIdentifier}): the state {@code 0}
     * computer (type {@code 5}, race Random), {@code 1} open (type {@code 6}, an observer slot {@code 0xC}) or {@code 2}
     * closed (type {@code 8}); a slot held by a player (type {@code 2} or {@code 9}) or the host's own slot is left; the
     * slot returns to its template record keeping its race (a computer takes Random) and force. Then the slot change
     * {@code 0x3e} and, while the slot's color is not locked, its color back to the default ({@code 0x68}
     * {@code {slot, 0x16}}). In a team game the whole force changes (a computer only when no human is in it; not traced
     * further).
     *
     * @param slot  the slot byte
     * @param state the slot state
     */
    private void applySlotState(int slot, int state) {
        int index = PlayerSlots.getPlayerIndex(slot);

        if (this.state < STATE_LOBBY || !PlayerSlots.isPlayerIdentifier(slot) || state > SLOT_CLOSED ||
            index >= PlayerTable.PLAYABLE_PLAYER_SLOTS && state == SLOT_COMPUTER ||
            index == this.getPlayerSlot(HOST_PLAYER_IDENTIFIER)) {
            return;
        }

        List<Integer> slots = new ArrayList<>();
        int force = this.records[index].getTeam();

        if (this.getOptions().isTeamGame() && index < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            for (int member = 0; member < PlayerTable.PLAYABLE_PLAYER_SLOTS; member++) {
                if (this.records[member].getTeam() == force) {
                    if (state == SLOT_COMPUTER && this.records[member].getType() == PlayerTypes.HUMAN) {
                        return;
                    }

                    slots.add(member);
                }
            }
        } else {
            slots.add(index);
        }

        for (int member : slots) {
            MeleeGameSetup.PlayerRecord record = this.records[member];
            int type = getSlotType(member, state);

            if (record.getType() == type || record.getType() == PlayerTypes.HUMAN || record.getType() == TYPE_OBSERVER) {
                continue;
            }

            int race = state == SLOT_COMPUTER ? ScrRaceIds.RANDOM : record.getRace();

            this.records[member]      = new MeleeGameSetup.PlayerRecord(-1, type, race, record.getTeam(), "");
            this.slotCommands[member] = createSlotCommand(member, this.records[member]);

            if (member < PlayerTable.PLAYABLE_PLAYER_SLOTS && this.lobbyData[LOBBY_DATA_COLOR_LOCKS + member] == 0) {
                this.lobbyData[LOBBY_DATA_COLORS + member] = (byte) COLOR_DEFAULT;
                this.colorCommand                          = new byte[]{COLOR_COMMAND, (byte) member, (byte) COLOR_DEFAULT};
            }
        }

        this.updateSlotCounts();
    }

    /**
     * Applies the host's swap ({@code 0x45} {@code {slot, slot}}) in the lobby when both bytes are player or observer slot
     * identifiers ({@link PlayerSlots#isPlayerIdentifier}) and differ: the two slots exchange their occupants; each slot
     * keeps its race and force. A player moving between a player slot and an observer slot becomes a human ({@code 2}) or
     * an observer ({@code 9}), an open slot open ({@code 6}) or an open observer slot ({@code 0xC}) ({@link #getOccupantType}).
     * Both slots' changes {@code 0x3e} are queued.
     *
     * @param first  the first slot byte
     * @param second the second slot byte
     */
    private void applySwap(int first, int second) {
        if (this.state < STATE_LOBBY || !PlayerSlots.isPlayerIdentifier(first) || !PlayerSlots.isPlayerIdentifier(second) || first == second) {
            return;
        }

        int firstIndex = PlayerSlots.getPlayerIndex(first);
        int secondIndex = PlayerSlots.getPlayerIndex(second);

        MeleeGameSetup.PlayerRecord firstRecord = this.records[firstIndex];
        MeleeGameSetup.PlayerRecord secondRecord = this.records[secondIndex];

        this.records[firstIndex]  = new MeleeGameSetup.PlayerRecord(secondRecord.getPlayerId(),
                                                                    getOccupantType(secondRecord.getType(), firstIndex), firstRecord.getRace(),
                                                                    firstRecord.getTeam(),
                                                                    secondRecord.getName());
        this.records[secondIndex] = new MeleeGameSetup.PlayerRecord(firstRecord.getPlayerId(),
                                                                    getOccupantType(firstRecord.getType(), secondIndex), secondRecord.getRace(),
                                                                    secondRecord.getTeam(),
                                                                    firstRecord.getName());
        this.playerSlots.update(this.records, RECORDS);

        this.slotCommands[firstIndex]  = createSlotCommand(firstIndex, this.records[firstIndex]);
        this.slotCommands[secondIndex] = createSlotCommand(secondIndex, this.records[secondIndex]);
        this.updateSlotCounts();
    }


    /**
     * Handles the host's team change of a team game (command {@code 0x42}): in the lobby state, for a team from {@code 1}
     * to the team count without a computer, a human (type {@code 2}) in another team moves to the team's first open slot.
     *
     * @param player the requesting player
     * @param team   the requested team
     */
    private void moveToTeam(int player, int team) {
        LanGameOptions options = this.getOptions();

        if (this.state < STATE_LOBBY || !options.isTeamGame() || team == 0 || team > options.getTeamCount() || this.hasComputer(team)) {
            return;
        }

        int sourceIndex = this.getPlayerSlot(player);

        if (sourceIndex < 0 || this.records[sourceIndex].getTeam() == team || this.records[sourceIndex].getType() != PlayerTypes.HUMAN) {
            return;
        }

        for (int candidate = 0; candidate < PlayerTable.PLAYABLE_PLAYER_SLOTS; candidate++) {
            if (this.records[candidate].getTeam() == team && this.records[candidate].getType() == TYPE_OPEN) {
                this.moveSlot(sourceIndex, candidate);

                return;
            }
        }
    }

    /**
     * Moves a player's record to another slot: the target takes the record with its own race and force; the source
     * returns to its template type keeping its race and force. Between a player slot and an observer slot the type
     * follows the slot kind (human {@code 2} and observer {@code 9}). Both slots' changes {@code 0x3e} are queued.
     *
     * @param sourceIndex      the record index of the moving player
     * @param destinationIndex the record index of the target slot
     */
    private void moveSlot(int sourceIndex, int destinationIndex) {
        MeleeGameSetup.PlayerRecord moving = this.records[sourceIndex];
        MeleeGameSetup.PlayerRecord target = this.records[destinationIndex];
        int type = moving.getType();

        if (sourceIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS && destinationIndex >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            type = TYPE_OBSERVER;
        } else if (sourceIndex >= PlayerTable.PLAYABLE_PLAYER_SLOTS && destinationIndex < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            type = PlayerTypes.HUMAN;
        }

        this.records[destinationIndex] = new MeleeGameSetup.PlayerRecord(moving.getPlayerId(),
                                                                         type, target.getRace(),
                                                                         target.getTeam(),
                                                                         moving.getName());
        this.records[sourceIndex]      = new MeleeGameSetup.PlayerRecord(-1,
                                                                         this.templateTypes[sourceIndex] & BYTE_MASK, moving.getRace(),
                                                                         moving.getTeam(),
                                                                         "");
        this.playerSlots.update(this.records, RECORDS);

        this.slotCommands[sourceIndex]      = createSlotCommand(sourceIndex, this.records[sourceIndex]);
        this.slotCommands[destinationIndex] = createSlotCommand(destinationIndex, this.records[destinationIndex]);
    }


    /**
     * Checks whether a force has a computer slot of type {@code 5}.
     *
     * @param force the force
     * @return {@code true} if a player slot of the force has type {@code 5}, {@code false} otherwise
     */
    private boolean hasComputerSlot(int force) {
        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            if (this.records[slot].getTeam() == force && this.records[slot].getType() == PlayerTypes.EITHER_PREFER_COMPUTER) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks whether a force has a computer slot (type {@code 1} or {@code 5}).
     *
     * @param force the force
     * @return {@code true} if a player slot of the force has type {@code 1} or {@code 5}, {@code false} otherwise
     */
    private boolean hasComputer(int force) {
        for (int slot = PlayerTable.PLAYABLE_PLAYER_SLOTS - 1; slot >= 0; slot--) {
            int type = this.records[slot].getType();

            if (this.records[slot].getTeam() == force && (type == PlayerTypes.COMPUTER || type == PlayerTypes.EITHER_PREFER_COMPUTER)) {
                return true;
            }
        }

        return false;
    }

    /**
     * @param playerIdentifier the provider player identifier, {@code -1} for none
     * @return {@code true} if the player is the local player or a remote peer of the session, {@code false} otherwise
     */
    private boolean isSessionPlayer(int playerIdentifier) {
        if (playerIdentifier < 0) {
            return false;
        }

        if (playerIdentifier == this.session.getLocalIdentifier()) {
            return true;
        }

        return this.session.findPeer(playerIdentifier) != null;
    }

    /**
     * @param playerIdentifier the provider player identifier, {@code -1} for none
     * @return the name of the local player or a remote peer of the session, empty for any other player
     */
    private @NotNull String getPlayerName(int playerIdentifier) {
        if (playerIdentifier < 0) {
            return "";
        }

        if (playerIdentifier == this.session.getLocalIdentifier()) {
            return this.localName;
        }

        ProviderPeer peer = this.session.findPeer(playerIdentifier);

        return peer != null ? peer.getName() : "";
    }

    /**
     * @param player the provider player id, {@code -1} for none
     * @return {@code true} if the player's version state is {@link #VERSIONS_PRESENT}, {@code false} otherwise
     */
    private boolean hasPlayerVersions(int player) {
        int index = PlayerSlots.getPlayerIndex(player);

        return index >= 0 && this.playerVersionStates[index] == VERSIONS_PRESENT;
    }

    /**
     * Sets a player's version WORDs when the player is in the session ({@link #isSessionPlayer}); its version state becomes
     * {@link #VERSIONS_PRESENT}. Nothing changes for any other player.
     *
     * @param player     the provider player id
     * @param flags      the flags WORD
     * @param secondWord the second WORD
     * @param version    the lobby protocol version WORD
     */
    private void setPlayerVersions(int player, int flags, int secondWord, int version) {
        int index = PlayerSlots.getPlayerIndex(player);

        if (index < 0 || !this.isSessionPlayer(player)) {
            return;
        }

        this.playerVersions[index]      = new int[]{flags, secondWord, version};
        this.playerVersionStates[index] = VERSIONS_PRESENT;
    }


    /**
     * Handles the host's processing of a joined-game command: outside the lobby state the lobby error {@code 4}
     * ({@link #UNJOINABLE_ERROR}), the version check (refusal {@code 7}), with a saved game the saved game check (refusal
     * {@code 5}), then the player's placement ({@link #placePlayer}, refusal {@code 2}), the player's block, the players'
     * slots ({@link PlayerSlots#update}) and the player's version WORDs ({@link #setPlayerVersions}), then slot changes for
     * every slot, a new net player {@code 0x3f} {@code {player id, versions}} for every player a record names (zero
     * versions for a player without versions, one that left), {@code 0x49} to everyone, the slot tables
     * ({@link #sendSlotTable}) of every record's player to the new one (a player named by two records gets two) and the new
     * one's to the others, the lobby data command, the download percent again and the map information.
     *
     * @param player  the joining player
     * @param command the joined-game command
     */
    private void joinPlayer(int player, byte @NotNull [] command) {
        if (this.state != STATE_LOBBY) {
            LOGGER.info("LAN LOBBY JOIN REFUSED: player " + player + ", lobby state " + this.state + " (0x4e 4)");
            this.session.sendMessage(player, new byte[]{MESSAGE_REFUSAL, (byte) UNJOINABLE_ERROR});

            return;
        }

        ByteBuffer fields = ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN);

        if ((fields.getShort(JOIN_VERSION_OFFSET) & WORD_MASK) != JOIN_VERSION) {
            this.session.sendMessage(player, new byte[]{MESSAGE_REFUSAL, REFUSAL_VERSION});

            return;
        }

        // With a saved game the joiner's lobby slot and record slot must be player slots and its file's random state the
        // host's, otherwise refusal 5 (chatINVALIDSAVE).
        SaveGameInformation saved = this.savedGame;
        int lobbySlot = command[JOIN_LOBBY_SLOT_OFFSET] & BYTE_MASK;
        int recordSlot = command[JOIN_RECORD_SLOT_OFFSET] & BYTE_MASK;

        if (saved != null && (lobbySlot >= PlayerTable.PLAYABLE_PLAYER_SLOTS || recordSlot >= PlayerTable.PLAYABLE_PLAYER_SLOTS ||
                              fields.getInt(JOIN_RANDOM_STATE_OFFSET) != saved.getRandomState())) {
            LOGGER.info("LAN LOBBY JOIN REFUSED: player " + player + ", another saved game (0x4e 5)");
            this.session.sendMessage(player, new byte[]{MESSAGE_REFUSAL, (byte) REFUSAL_INVALID_SAVE});

            return;
        }

        int index = placePlayer(this.getOptions(),
                                this.records, this.pendingSlots,
                                saved == null ? null : this.startBytes, saved == null ? null : this.savedRaces,
                                player, this.getPlayerName(player),
                                lobbySlot, recordSlot);

        if (index < 0) {
            LOGGER.info("LAN LOBBY JOIN REFUSED: player " + player + ", no slot (0x4e 2)");
            this.session.sendMessage(player, new byte[]{MESSAGE_REFUSAL, (byte) REFUSAL_NO_SLOT});

            return;
        }

        int playerIndex = PlayerSlots.getPlayerIndex(player);

        System.arraycopy(command, JOIN_BLOCK_OFFSET, this.playerBlocks[playerIndex], 0, PLAYER_BLOCK_SIZE);

        int flags = fields.getShort(JOIN_FLAGS_OFFSET) & JOIN_FLAGS_MASK | (command[JOIN_FLAG_BYTE_OFFSET] != 0 ? JOIN_FLAG_BYTE_BIT : 0);

        this.playerSlots.update(this.records, RECORDS);
        this.setPlayerVersions(player, flags, fields.getShort(JOIN_SECOND_WORD_OFFSET) & WORD_MASK, fields.getShort(JOIN_VERSION_OFFSET) & WORD_MASK);

        for (int slot = 0; slot < RECORDS; slot++) {
            int owner = this.records[slot].getPlayerId();
            int ownerIndex = PlayerSlots.getPlayerIndex(owner);

            this.slotCommands[slot] = createSlotCommand(slot, this.records[slot]);

            // Every record that names a player queues that player's one command, a record the player released too, with zero
            // versions once the player left.
            if (ownerIndex >= 0) {
                int[] versions = this.hasPlayerVersions(owner) ? this.playerVersions[ownerIndex] : new int[PLAYER_VERSION_WORDS];
                ByteBuffer newPlayer = ByteBuffer.allocate(NEW_NET_PLAYER_SIZE).order(ByteOrder.LITTLE_ENDIAN);

                newPlayer.put((byte) ReplayCommandIds.NEW_NET_PLAYER).put((byte) owner);

                for (int word : versions) {
                    newPlayer.putShort((short) word);
                }

                this.playerCommands[ownerIndex] = newPlayer.array();
            }
        }

        ByteBuffer joinedMessage = ByteBuffer.allocate(PLAYER_JOINED_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        joinedMessage.put((byte) MESSAGE_PLAYER_JOINED).putInt(player);
        this.session.sendMessage(ALL_PLAYERS, joinedMessage.array());

        for (int slot : SLOT_TABLE_ORDER) {
            this.sendSlotTable(player, this.records[slot].getPlayerId());
        }

        this.sendSlotTable(OTHER_PLAYERS, player);

        if (this.setup != null) {
            byte[] data = new byte[LOBBY_DATA_COMMAND_SIZE];

            data[0] = LOBBY_DATA_COMMAND;
            System.arraycopy(this.lobbyData, 0, data, 1, LOBBY_DATA_SIZE);
            this.lobbyDataCommand    = data;
            this.sentDownloadPercent = DOWNLOAD_UNKNOWN;

            // The shared map's information to the player.
            LanMapTransfer.SharedFile shared = this.sharedMap;

            if (shared != null) {
                this.mapTransfer.addPlayer(shared, player);
            }
        }

        this.updateSlotCounts();
        LOGGER.info("LAN LOBBY JOINED: player " + player + " takes slot " + index + " (type " + this.records[index].getType() + ", race " +
                    this.records[index].getRace() + ", force " + this.records[index].getTeam() + ")");
    }

    /**
     * Sends message {@code 0x5e}, the player id and its {@code 350}-byte block padded to {@code 352} bytes, when the player has versions
     * ({@link #hasPlayerVersions}) and the target is every player, every other player or a player with versions.
     *
     * @param target the target player id, {@link #ALL_PLAYERS} or {@link #OTHER_PLAYERS}
     * @param player the provider player id whose block is sent, {@code -1} for none
     */
    private void sendSlotTable(int target, int player) {
        if (!this.hasPlayerVersions(player) || (target != ALL_PLAYERS && target != OTHER_PLAYERS && !this.hasPlayerVersions(target))) {
            return;
        }

        byte[] message = new byte[SLOT_TABLE_SIZE];

        message[0] = MESSAGE_SLOT_TABLE;
        message[1] = (byte) player;
        System.arraycopy(this.playerBlocks[PlayerSlots.getPlayerIndex(player)], 0, message, SLOT_TABLE_BLOCK_OFFSET, PLAYER_BLOCK_SIZE);
        this.session.sendMessage(target, message);
    }
}
