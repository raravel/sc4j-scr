package org.fogofwarlabs.sc4j.scr.data.format.replay;

/**
 * Names the replay command type identifiers ({@link ReplayCommand#getTypeId()}).
 * <p>
 * The {@code _121} identifiers are the patch 1.21 forms that carry four-byte unit handles.
 */
public final class ReplayCommandIds {
    /**
     * Keep alive.
     */
    public static final int KEEP_ALIVE = 0x05;
    /**
     * Save game.
     */
    public static final int SAVE_GAME = 0x06;
    /**
     * Load game.
     */
    public static final int LOAD_GAME = 0x07;
    /**
     * Restart game.
     */
    public static final int RESTART_GAME = 0x08;
    /**
     * Select units.
     */
    public static final int SELECT = 0x09;
    /**
     * Shift-select units.
     */
    public static final int SELECT_ADD = 0x0A;
    /**
     * Shift-deselect units.
     */
    public static final int SELECT_REMOVE = 0x0B;
    /**
     * Build.
     */
    public static final int BUILD = 0x0C;
    /**
     * Shared vision.
     */
    public static final int VISION = 0x0D;
    /**
     * Alliance.
     */
    public static final int ALLIANCE = 0x0E;
    /**
     * Game speed.
     */
    public static final int GAME_SPEED = 0x0F;
    /**
     * Pause.
     */
    public static final int PAUSE = 0x10;
    /**
     * Resume.
     */
    public static final int RESUME = 0x11;
    /**
     * Cheat.
     */
    public static final int CHEAT = 0x12;
    /**
     * Hotkey (control group).
     */
    public static final int HOTKEY = 0x13;
    /**
     * Right click.
     */
    public static final int RIGHT_CLICK = 0x14;
    /**
     * Targeted order.
     */
    public static final int TARGETED_ORDER = 0x15;
    /**
     * Cancel construction.
     */
    public static final int CANCEL_BUILD = 0x18;
    /**
     * Cancel morph.
     */
    public static final int CANCEL_MORPH = 0x19;
    /**
     * Stop.
     */
    public static final int STOP = 0x1A;
    /**
     * Carrier stop.
     */
    public static final int CARRIER_STOP = 0x1B;
    /**
     * Reaver stop.
     */
    public static final int REAVER_STOP = 0x1C;
    /**
     * Order nothing.
     */
    public static final int ORDER_NOTHING = 0x1D;
    /**
     * Return cargo.
     */
    public static final int RETURN_CARGO = 0x1E;
    /**
     * Train.
     */
    public static final int TRAIN = 0x1F;
    /**
     * Cancel train.
     */
    public static final int CANCEL_TRAIN = 0x20;
    /**
     * Cloak.
     */
    public static final int CLOAK = 0x21;
    /**
     * Decloak.
     */
    public static final int DECLOAK = 0x22;
    /**
     * Unit morph.
     */
    public static final int UNIT_MORPH = 0x23;
    /**
     * Unsiege.
     */
    public static final int UNSIEGE = 0x25;
    /**
     * Siege.
     */
    public static final int SIEGE = 0x26;
    /**
     * Train interceptor or scarab.
     */
    public static final int TRAIN_FIGHTER = 0x27;
    /**
     * Unload all.
     */
    public static final int UNLOAD_ALL = 0x28;
    /**
     * Unload.
     */
    public static final int UNLOAD = 0x29;
    /**
     * Archon warp.
     */
    public static final int MERGE_ARCHON = 0x2A;
    /**
     * Hold position.
     */
    public static final int HOLD_POSITION = 0x2B;
    /**
     * Burrow.
     */
    public static final int BURROW = 0x2C;
    /**
     * Unburrow.
     */
    public static final int UNBURROW = 0x2D;
    /**
     * Cancel nuke.
     */
    public static final int CANCEL_NUKE = 0x2E;
    /**
     * Lift off.
     */
    public static final int LIFT_OFF = 0x2F;
    /**
     * Research.
     */
    public static final int TECH = 0x30;
    /**
     * Cancel research.
     */
    public static final int CANCEL_TECH = 0x31;
    /**
     * Upgrade.
     */
    public static final int UPGRADE = 0x32;
    /**
     * Cancel upgrade.
     */
    public static final int CANCEL_UPGRADE = 0x33;
    /**
     * Cancel add-on.
     */
    public static final int CANCEL_ADDON = 0x34;
    /**
     * Building morph.
     */
    public static final int BUILDING_MORPH = 0x35;
    /**
     * Stim pack.
     */
    public static final int STIM = 0x36;
    /**
     * Sync.
     */
    public static final int SYNC = 0x37;
    /**
     * Voice enable.
     */
    public static final int VOICE_ENABLE = 0x38;
    /**
     * Voice disable.
     */
    public static final int VOICE_DISABLE = 0x39;
    /**
     * Voice squelch.
     */
    public static final int VOICE_SQUELCH = 0x3A;
    /**
     * Voice unsquelch.
     */
    public static final int VOICE_UNSQUELCH = 0x3B;
    /**
     * Start game.
     */
    public static final int START_GAME = 0x3C;
    /**
     * Download percentage.
     */
    public static final int DOWNLOAD_PERCENTAGE = 0x3D;
    /**
     * Change game slot.
     */
    public static final int CHANGE_GAME_SLOT = 0x3E;
    /**
     * New net player.
     */
    public static final int NEW_NET_PLAYER = 0x3F;
    /**
     * Joined game.
     */
    public static final int JOINED_GAME = 0x40;
    /**
     * Change race.
     */
    public static final int CHANGE_RACE = 0x41;
    /**
     * Team game team.
     */
    public static final int TEAM_GAME_TEAM = 0x42;
    /**
     * Use-map-settings team.
     */
    public static final int UMS_TEAM = 0x43;
    /**
     * Melee team.
     */
    public static final int MELEE_TEAM = 0x44;
    /**
     * Swap players.
     */
    public static final int SWAP_PLAYERS = 0x45;
    /**
     * Saved data.
     */
    public static final int SAVED_DATA = 0x48;
    /**
     * Briefing start.
     */
    public static final int BRIEFING_START = 0x54;
    /**
     * Latency.
     */
    public static final int LATENCY = 0x55;
    /**
     * Replay speed.
     */
    public static final int REPLAY_SPEED = 0x56;
    /**
     * Leave game.
     */
    public static final int LEAVE_GAME = 0x57;
    /**
     * Minimap ping.
     */
    public static final int MINIMAP_PING = 0x58;
    /**
     * Dark Archon meld.
     */
    public static final int MERGE_DARK_ARCHON = 0x5A;
    /**
     * Make game public.
     */
    public static final int MAKE_GAME_PUBLIC = 0x5B;
    /**
     * Chat.
     */
    public static final int CHAT = 0x5C;
    /**
     * Replay seek.
     */
    public static final int REPLAY_SEEK = 0x5D;
    /**
     * Turn rate.
     */
    public static final int TURN_RATE = 0x5F;
    /**
     * Right click (1.21 handles).
     */
    public static final int RIGHT_CLICK_121 = 0x60;
    /**
     * Targeted order (1.21 handles).
     */
    public static final int TARGETED_ORDER_121 = 0x61;
    /**
     * Unload (1.21 handle).
     */
    public static final int UNLOAD_121 = 0x62;
    /**
     * Select units (1.21 handles).
     */
    public static final int SELECT_121 = 0x63;
    /**
     * Shift-select units (1.21 handles).
     */
    public static final int SELECT_ADD_121 = 0x64;
    /**
     * Shift-deselect units (1.21 handles).
     */
    public static final int SELECT_REMOVE_121 = 0x65;
    /**
     * Dynamic turn rate.
     */
    public static final int DYNAMIC_TURN_RATE = 0x66;
    /**
     * The SC4J mod command ({@link ReplayModCommand}): data for the simulation plugins of a mod, a length byte and up to
     * {@value ReplayModCommand#MAXIMUM_DATA_LENGTH} data bytes. SC:R has no command of this type.
     */
    public static final int MOD_COMMAND = 0x80;

    private ReplayCommandIds() {
    }
}
