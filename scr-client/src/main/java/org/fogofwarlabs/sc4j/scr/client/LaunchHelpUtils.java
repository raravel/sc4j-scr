package org.fogofwarlabs.sc4j.scr.client;

/**
 * Prints the launch option help of the client ({@code --help}).
 */
final class LaunchHelpUtils {
    /**
     * Prints the launch options, the settings file, the folders next to the executable and the client command overview to the standard output.
     */
    static void print() {
        System.out.println("sc4j [--data directory] [--replay file.rep] [--focus-unit index] [--smoke | --frames count]" +
                           " [--hidden] [--screenshot file.png]");
        System.out.println("--camera x,y centers the initial view on world pixels; --zoom scale sets its initial screen pixels per world pixel.");
        System.out.println("--renderer classic|hd2|hd selects the world renderer (default hd); F5 cycles classic, HD2 and HD at runtime" +
                           " (SC:R's F5 toggles classic and remastered; cycling is a user-chosen deviation).");
        System.out.println("--hd-lighting on|off selects the HD deferred lighting; --hd-skin default|presale|carbot selects the HD unit skin.");
        System.out.println("--shadow-stacking on|off sets SC:R's Shadow Stacking (graphics.shadowStacking, default on);" +
                           " --gamma 60..140 sets the full-screen display gamma (graphics.gamma, default 100).");
        System.out.println("--original-aspect-ratio on|off keeps the 4:3 game screen (graphics.originalAspectRatio, default off);" +
                           " off, the game is as wide as the window up to 16:9.");
        System.out.println("--sd-filter 0..3 sets SC:R's SD Graphics Filter for classic graphics (graphics.sdFilterMode, default 1):" +
                           " 0 sharp, 3 filtered.");
        System.out.println("--environment-effects on|off sets SC:R's Environment Effects, the HD foliage (graphics.environmentEffects, default on).");
        System.out.println("--smooth-turning on|off sets SC:R's Smooth Unit Turning (graphics.smoothTurning, default on):" +
                           " off draws HD units in the classic direction steps.");
        System.out.println("--vsync on|off|adaptive waits for the display refresh (SC:R VSync; default off, hidden windows never wait);" +
                           " --max-fps N limits the frame rate (SC:R FPSLimit; 0 = no limit, the default). Game speed does not depend on either.");
        System.out.println("--audio remastered|classic selects the sound set (sound/ or SD/sound/), independent of the renderer;" +
                           " the default is remastered.");
        System.out.println("--settings path|none reads the client's settings file (default: sc4j-settings.json next to the executable," +
                           " i.e. the install folder with bin/ and lib/, or the working directory for development runs; visible windows only;" +
                           " groups graphics, audio, input, game, logging, paths); launch options override it. A --hidden run shows no startup" +
                           " tips unless its settings file turns game.showTips on.");
        System.out.println("--data folder selects the StarCraft: Remastered data (the folder holding .build.info and Data);" +
                           " default: the settings file's paths.data, else data/ next to the executable.");
        System.out.println("Next to the executable: data/ (game data), maps/ (the map browser's only folder), replays/, logs/," +
                           " .cache/ (native libraries), sc4j-settings.json.");
        System.out.println("--console player[:replay] shows the in-game console of a player's recorded selection (/console in the game changes it).");
        System.out.println("--command line (repeatable) runs a chat-line command at launch (/help lists them).");
        System.out.println("Beacon.Link takes the runtime's --account <dir>, --seeds <file>, --seed host:port and --peers <file> (the launcher passes" +
                           " them); its multiplayer entry needs an account.");
        System.out.println("--debug info|layer,...|lights=<view> starts with the debug view.");
        System.out.println("Enter opens the chat line; a line starting with / is a client command, never chat:" +
                           " /help or /? lists them, /help <command> explains one. The client's own features have no keys; they are commands:" +
                           " /debug, /zoom, /pan, /replay, /seek, /step, /console, /preview, /graphics, /shot, /uihotkey, /cheat.");
        System.out.println("--log-level error|warn|info|debug|trace|off sets the log threshold" +
                           " (default info, or logging.level in the settings file); visible runs also write logs/sc4j.log under the install root" +
                           " (logging.file), rolling at 10 MB and keeping 5 files.");
        System.out.println("--pointer x,y fixes the pointer at framebuffer pixels, for screenshots of the game cursor.");
        System.out.println("--window WxH sets the window size, scaled by the monitor's display scale;" +
                           " --content-scale s gives a --hidden window that display scale.");
        System.out.println("--map-compat loads maps with the relaxed 1.16-era rules (maps.compatibility in the settings file):" +
                           " only the last copy of a CHK section must be valid and a mismatched (attributes) file is ignored;" +
                           " every relaxed rule is logged. By default maps load and list with the native SC:R rules.");
        System.out.println("With the console, left click/drag selects (shown locally in replays), right click orders, 0-9 control groups.");
        System.out.println("Drop a replay on the window or type /replay open." +
                           " The keys are SC:R's own (arrows and the screen edge scroll the camera);" +
                           " without the native replay controls, Space runs/pauses replay execution.");
        System.out.println("--unit-frame index and --mirror-unit set the asset preview of /preview when --focus-unit is supplied.");
        System.out.println("--image-animation index [--image-steps count] inspects the focused image with isolated state" +
                           " and stops at unsupported opcodes.");
        System.out.println("--inspect-map retains read-only CHK/assets inspection. --focus-unit also selects that inspection mode.");
        System.out.println("--campaign mission|list plays a built-in campaign mission (index or name such as bw-terran-1)" +
                           " with --name, --game-speed and --locale;" +
                           " like SC:R it saves no replay, except to the developer option --save path (ours).");
        System.out.println("Live games write <install root>/replays/LastReplay.rep at the end (SC:R naming, replaced every game);" +
                           " the score screen's Save Replay writes a timestamped copy there; --save path overrides the automatic file.");
        System.out.println("--preview-steps count attempts bounded simulation steps before rendering, stopping at the first unsupported command.");
    }

    private LaunchHelpUtils() {
    }
}
