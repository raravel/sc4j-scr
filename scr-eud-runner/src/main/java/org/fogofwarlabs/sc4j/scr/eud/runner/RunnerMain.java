package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.casc.GameLocaleUtils;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptCheckUtils;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptFinding;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptPackage;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptStorageUtils;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptTrailer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a map headless as a use-map-settings game with one human, optionally driven by a {@code .mtest} script, and writes one JSON report.
 * <p>
 * {@code eud-runner --data <SC:R folder> --map <scx|scm> --out <report.json> [--script <file.mtest>] [--frames N] [--slot N]
 * [--frame-timeout-ms N] [--time-limit-ms N] [--epmap <output.epmap>] [--profile] [--shots-dir <folder>] [--lan]}
 * <p>
 * The game is a single-player one unless {@code --lan} is given: then it is a network game the player is alone in, as a LAN game created
 * and started without a second player ({@link LoopbackTransport}). The two differ where the client does: a network game is a multiplayer
 * session to the map (the multiplayer BYTE {@code 0x57F0B4}, the timeouts of a game speed, Restart Game), it keeps
 * {@value #LAN_TURNS_IN_TRANSIT} turns in transit, so a command runs later than in a single-player game, its turns start with the Sync
 * command of the game's Sync records, and its chat box writes the sender's line to the message lines, which a single-player game's never
 * does.
 * <p>
 * A script's {@code shot} lines write {@code <shots-dir>/<name>.png}: the classic 640x480 screen the local player sees at that point of the run
 * ({@link ShotRenderer}). The window and OpenGL context are made at the first shot only; a shot that cannot be drawn is reported with its
 * reason and never changes the run.
 * <p>
 * With the trace file of a {@code [main] debug} build, a failed or hung run reports the epScript statements that were running.
 * {@code --profile} counts the trigger records and actions of each frame and, with the trace file, attributes the actions to statements.
 * <p>
 * The report is written to {@code --out} (never stdout, which the engine's logging may share). Exit status 0 means a report was written,
 * 2 a usage error.
 */
public final class RunnerMain {
    /**
     * The local turns a {@code --lan} game keeps in transit: what the client's LAN game keeps ({@code Main.DEFAULT_NETWORK_TURNS_IN_TRANSIT}).
     */
    static final int LAN_TURNS_IN_TRANSIT = 3;
    /**
     * The height of a terrain tile, which a Sync record's camera row counts in.
     */
    private static final int SYNC_ROW_HEIGHT = 32;
    /**
     * The report format version.
     */
    static final int REPORT_VERSION = 1;
    static final int DEFAULT_FRAMES = 2400;
    static final long DEFAULT_FRAME_TIMEOUT_MILLIS = 10_000;
    static final long DEFAULT_TIME_LIMIT_MILLIS = 120_000;
    /**
     * Frames in a row that do not advance before the game counts as ended.
     */
    private static final int STALL_LIMIT = 50;
    /**
     * The name of the human player unless a JavaScript test names one ({@code // @player NAME}).
     */
    static final @NotNull String DEFAULT_PLAYER_NAME = "tester";
    private static final int TRACE_LINES = 6;

    private final @NotNull Options options;
    private final @Nullable MtestScript script;
    private final @Nullable JsTest jsTest;
    private final long startNanos = System.nanoTime();
    private final List<Map<String, Object>> expects = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> shots = Collections.synchronizedList(new ArrayList<>());
    private final List<String> interfaceErrors = Collections.synchronizedList(new ArrayList<>());
    private volatile int frame;
    private volatile long lastProgressNanos;
    private volatile boolean simulating;
    /**
     * Whether a shot is being drawn: the frame watchdog waits, as the draw has its own bound ({@link ShotRenderer}).
     */
    private volatile boolean rendering;
    private volatile long loadMillis;
    private volatile @Nullable Game game;
    private @Nullable TraceReader trace;
    private @Nullable Profiler profiler;
    private @Nullable ClientInterface clientInterface;
    private @Nullable ShotRenderer shotRenderer;
    private @Nullable CascStorage shotStorage;
    private @Nullable ScrResourceProvider shotResources;
    private @Nullable String shotLocale;

    private final Set<Integer> heldKeys = new HashSet<>();
    private int mouseButtons;
    /**
     * The local player's camera, made when the game starts.
     */
    private @Nullable ViewCamera viewCamera;
    private int viewMouseX = 10;
    private int viewMouseY = 10;

    /**
     * The command line.
     *
     * @param data               the SC:R installation
     * @param map                the map file
     * @param out                the report file
     * @param script             the script, or {@code null}
     * @param frames             the frames of a run without a script
     * @param slot               the human lobby slot
     * @param frameTimeoutMillis the wall time one frame may take before the run counts as hung
     * @param timeLimitMillis    the wall time of the whole run
     * @param epmap              the trace file of a debug build, or {@code null}
     * @param profile            whether trigger work is counted
     * @param shotsDir           the folder of the shot pictures, or {@code null}
     * @param js                 the Enhanced Script folder that runs in the game, or {@code null}
     * @param lan                whether the game is a network game with one human instead of a single-player game
     * @param playerName         the name of the human player
     */
    record Options(@NotNull Path data, @NotNull Path map, @NotNull Path out, @Nullable Path script, int frames, int slot,
                   long frameTimeoutMillis, long timeLimitMillis, @Nullable Path epmap, boolean profile, @Nullable Path shotsDir,
                   @Nullable Path js, boolean lan, @NotNull String playerName) {
    }

    private RunnerMain(@NotNull Options options, @Nullable MtestScript script, @Nullable JsTest jsTest) {
        this.options = options;
        this.script  = script;
        this.jsTest  = jsTest;
    }

    /**
     * Runs the command line.
     *
     * @param arguments the arguments
     * @throws Exception if the report cannot be written
     */
    public static void main(String @NotNull [] arguments) throws Exception {
        if (arguments.length > 0 && arguments[0].equals("--convert-mtest")) {
            convertMtest(arguments);
            return;
        }

        Options options;

        try {
            options = parseOptions(arguments);
        } catch (IllegalArgumentException exception) {
            exitWithUsage(exception.getMessage());
            return;
        }

        MtestScript script = null;
        JsTest jsTest = null;
        Path jsPackage = null;

        if (options.script() != null && options.script().getFileName().toString().endsWith(JsTest.EXTENSION)) {
            if (options.js() != null) {
                exitWithUsage("A .js test is its own script; --js is not given with it.");
                return;
            }

            try {
                jsTest = JsTest.load(options.script());
            } catch (IllegalArgumentException exception) {
                exitWithUsage(exception.getMessage());
                return;
            }

            String playerName = jsTest.playerName();

            if (!jsTest.problems().isEmpty()) {
                Map<String, Object> report = baseReport();

                report.put("runtime", ordered("status", "skipped", "reason", "js_error"));
                report.put("script", ordered("status", "error", "problems", jsTest.problems()));
                writeReport(options.out(), report);
                System.exit(0);
                return;
            }

            try {
                jsPackage = jsTest.writePackage();
            } catch (IOException exception) {
                Map<String, Object> report = baseReport();

                report.put("runtime", ordered("status", "skipped", "reason", "js_error"));
                report.put("script", ordered("status", "error", "error", "The test package was not written: " + exception.getMessage()));
                writeReport(options.out(), report);
                System.exit(0);
                return;
            }

            options   = new Options(options.data(), options.map(), options.out(), options.script(), options.frames(), options.slot(),
                                    options.frameTimeoutMillis(), options.timeLimitMillis(), options.epmap(), options.profile(),
                                    options.shotsDir(), jsPackage, options.lan() || jsTest.usesChat(), playerName);
        } else if (options.script() != null) {
            script = MtestScript.parse(Files.readAllLines(options.script(), StandardCharsets.UTF_8));

            if (script.getProblems().isEmpty() && !script.getShots().isEmpty() && options.shotsDir() == null) {
                exitWithUsage("The script has shot lines; --shots-dir is required.");
                return;
            }

            if (!script.getProblems().isEmpty()) {
                Map<String, Object> report = baseReport();
                List<Object> problems = new ArrayList<>();

                for (MtestScript.Problem problem : script.getProblems()) {
                    problems.add(ordered("line", problem.line(), "code", problem.code(), "detail", problem.detail(), "usage", problem.usage()));
                }

                report.put("runtime", ordered("status", "skipped", "reason", "script_error"));
                report.put("script", ordered("status", "error", "problems", problems));
                writeReport(options.out(), report);
                System.exit(0);
                return;
            }
        }

        if (options.js() != null) {
            boolean armed;

            try {
                armed = armScript(options, jsTest);
            } finally {
                if (jsPackage != null) {
                    JsTest.deletePackage(jsPackage);
                }
            }

            if (!armed) {
                System.exit(0);
                return;
            }
        }

        RunnerMain runner = new RunnerMain(options, script, jsTest);

        if (options.epmap() != null) {
            runner.trace = TraceReader.load(options.epmap());
        }

        runner.run();
    }

    /**
     * Checks the Enhanced Script folder of {@code --js} and arms {@link JsScriptExtension} with its package. The package is joined to a copy
     * of the map's bytes in memory, the way the script tool's {@code pack} writes it, and read back as a map trailer; the map file is not
     * changed and the game loads it as the build wrote it. A folder with check errors, or one that cannot be packed, writes a report that
     * names every finding and runs no game.
     *
     * @param options the command line
     * @param jsTest  the JavaScript test whose package the folder is, or {@code null} for a plain {@code --js} folder
     * @return {@code true} if the script is armed, {@code false} if the report of a script that cannot run was written
     * @throws IOException if the report cannot be written
     */
    private static boolean armScript(@NotNull Options options, @Nullable JsTest jsTest) throws IOException {
        Path folder = options.js();
        List<Object> findings = new ArrayList<>();
        int errors;

        try {
            List<ScriptFinding> checked = ScriptCheckUtils.check(folder);

            for (ScriptFinding finding : checked) {
                findings.add(ordered("file", finding.getFile(), "line", finding.getLine(), "severity", finding.getSeverity().getLabel(),
                                     "message", finding.getMessage()));
            }

            errors = ScriptCheckUtils.countErrors(checked);

            if (errors == 0) {
                String name = options.map().getFileName().toString();
                ScriptPackage scriptPackage = ScriptPackage.readFolder(folder);
                byte[] packed = ScriptStorageUtils.writePackage(Files.readAllBytes(options.map()), name, scriptPackage);
                ScriptTrailer trailer = ScriptStorageUtils.readTrailer(packed, name);

                if (trailer == null) {
                    throw new IOException("The script package of " + folder + " was not written.");
                }

                JsScriptExtension.arm(trailer, findings, jsTest != null ? jsTest.globals() : null);

                return true;
            }
        } catch (IOException | RuntimeException exception) {
            findings.add(ordered("file", folder.toString(), "line", 0, "severity", "error", "message",
                                 exception.getClass().getSimpleName() + ": " + exception.getMessage()));
        }

        Map<String, Object> report = baseReport();

        report.put("runtime", ordered("status", "skipped", "reason", "js_error"));

        if (jsTest != null) {
            List<Object> problems = new ArrayList<>();

            for (Object finding : findings) {
                Map<?, ?> fields = (Map<?, ?>) finding;

                if ("error".equals(fields.get("severity"))) {
                    problems.add(ordered("line", fields.get("line"), "code", "js_check", "detail", fields.get("file") + ": " + fields.get("message"),
                                         "usage", "JavaScript that Rhino 1.8.1 runs: no class, async/await, spread or import/export"));
                }
            }

            report.put("script", ordered("status", "error", "problems", problems));
        }

        report.put("js", ordered("status", "check_error", "findings", findings));
        writeReport(options.out(), report);

        return false;
    }

    /**
     * {@code --convert-mtest <in.mtest> <out.test.js>}: writes the JavaScript test of a {@code .mtest} script ({@link MtestConverter}); a
     * script with problems is listed on the standard error and nothing is written.
     */
    private static void convertMtest(String @NotNull [] arguments) throws IOException {
        if (arguments.length != 3 || !arguments[2].endsWith(JsTest.EXTENSION)) {
            System.err.println("usage: eud-runner --convert-mtest <in.mtest> <out.test.js>");
            System.exit(2);
            return;
        }

        List<MtestScript.Problem> problems = MtestConverter.convert(Path.of(arguments[1]), Path.of(arguments[2]));

        for (MtestScript.Problem problem : problems) {
            System.err.println("line " + problem.line() + ": " + problem.code() + ": " + problem.detail() + " (" + problem.usage() + ")");
        }

        System.exit(problems.isEmpty() ? 0 : 1);
    }

    private static void exitWithUsage(@NotNull String message) {
        System.err.println("eud-runner: " + message);
        System.err.println("usage: eud-runner --data <SC:R folder> --map <scx|scm> --out <report.json> [--script <file.mtest|NAME.test.js>] " +
                           "[--frames N] [--slot N] [--frame-timeout-ms N] [--time-limit-ms N] [--epmap <output.epmap>] [--profile] " +
                           "[--shots-dir <folder>] [--js <script folder>] [--lan]");
        System.exit(2);
    }

    private void run() throws Exception {
        AtomicReference<Map<String, Object>> finished = new AtomicReference<>();
        Thread worker = new Thread(() -> finished.set(this.simulate()), "eud-runner-simulation");

        worker.setDaemon(true);
        worker.start();

        while (worker.isAlive()) {
            worker.join(100);

            long now = System.nanoTime();

            if (worker.isAlive() && this.simulating && !this.rendering &&
                (now - this.lastProgressNanos) / 1_000_000 > this.options.frameTimeoutMillis()) {
                this.writeAbandoned("hang", "No frame finished within " + this.options.frameTimeoutMillis() + " ms.");
                return;
            }

            // A shot's own bound ends its wait (ShotRenderer); the time limit then stops the run at its next frame.
            if (worker.isAlive() && !this.rendering &&
                (now - this.startNanos) / 1_000_000 > this.options.timeLimitMillis() + this.options.frameTimeoutMillis()) {
                this.writeAbandoned("timeout", "The run did not finish within " + this.options.timeLimitMillis() + " ms.");
                return;
            }
        }

        writeReport(this.options.out(), finished.get());
        System.exit(0);
    }

    /**
     * Writes the report of a run whose simulation thread does not return, and ends the process.
     */
    private void writeAbandoned(@NotNull String status, @NotNull String error) throws IOException {
        Map<String, Object> report = baseReport();

        Map<String, Object> runtime = ordered("status", status, "frame", this.frame, "error", error);

        this.putTrace(runtime);
        report.put("runtime", runtime);
        report.put("framesRun", this.frame);
        report.put("framesPlanned", this.plannedFrames());
        this.putScript(report, status.equals("hang") ? "failed" : "incomplete");

        if (this.options.js() != null) {
            report.put("js", JsScriptExtension.report());
        }
        report.put("loadMs", this.loadMillis);
        report.put("elapsedMs", (System.nanoTime() - this.startNanos) / 1_000_000);
        writeReport(this.options.out(), report);
        Runtime.getRuntime().halt(0);
    }

    private long plannedFrames() {
        // A JavaScript test plans no frame count: it runs until test(t) returns.
        return this.script != null ? this.script.getPlannedFrames() : this.jsTest != null ? 0 : this.options.frames();
    }

    private @NotNull Map<String, Object> simulate() {
        Map<String, Object> report = baseReport();
        Game game = null;
        MessageLayout layout = null;
        MessageLines.TextLayout lineLayout;
        CascStorage[] opened = new CascStorage[1];

        try (GameData gameData = loadGameData(this.options.data(), opened)) {
            CascStorage storage = opened[0];
            ScrResourceProvider resources = ScrResourceProvider.borrow(storage, gameData.getAddedGraphics());
            String locale = GameLocaleUtils.select(null, this.options.data(), storage);

            try {
                layout     = MessageLayout.load(resources, locale);
                lineLayout = layout.getLayout();
            } catch (IOException | RuntimeException | LinkageError error) {
                // Without the client's font metrics the lines are not wrapped; the report says so.
                lineLayout = text -> new int[]{text.length};
                report.put("messageLayoutError", error.getClass().getSimpleName() + ": " + error.getMessage());
            }

            // The renderer is made at the first shot; GL starts there.
            this.shotStorage   = storage;
            this.shotResources = resources;
            this.shotLocale    = locale;

            byte[] mapData = MpqArchive.open(this.options.map()).readFile("staredit\\scenario.chk");
            MeleeGameSetup setup = MeleeGameSetup.createUseMapSettings(mapData, this.options.playerName(), ScrRaceIds.TERRAN, this.options.slot(), 6);
            LiveGame live = new LiveGame(setup, 12345);

            if (this.options.lan()) {
                // Turns start with the Sync command, as a network game's do: a map that queues its own commands (EUD writes to the
                // outgoing turn) appends them after it.
                live.attachNetwork(new LoopbackTransport(), LAN_TURNS_IN_TRANSIT, false);
            }

            LiveMapSession session = LiveMapSession.start(gameData, live, this.options.map(), lineLayout);

            game            = session.getGame();
            this.game       = game;
            this.viewCamera = new ViewCamera(game);
            this.viewCamera.placeStartScreen(live.getLocalPlayer());

            if (live.isNetworkGame()) {
                // As the client after the session start: the Sync records take the camera's top tile row for their drawn rows.
                ViewCamera view = this.viewCamera;

                live.setSyncSource(game, () -> Math.max(0, view.getScreenY() / SYNC_ROW_HEIGHT));
            }

            if (this.options.profile()) {
                this.profiler = new Profiler(this.trace);
                this.profiler.attach(game);
            }
            this.loadMillis        = (System.nanoTime() - this.startNanos) / 1_000_000;
            this.lastProgressNanos = System.nanoTime();
            this.simulating        = true;

            String stoppedBy = this.script != null ? this.runScript(session)
                               : this.jsTest != null ? this.jsTest.run(this.jsHost(session), this.options.frames())
                               : this.advance(session, this.options.frames());

            this.simulating = false;

            Exception failure = session.getFailure();

            if (failure != null) {
                Map<String, Object> runtime = failureReport(failure, this.frame);

                this.putTrace(runtime);
                report.put("runtime", runtime);
            } else {
                Map<String, Object> runtime = ordered("status", "ok");

                if (stoppedBy != null) {
                    runtime.put("stoppedBy", stoppedBy);
                    runtime.put("frame", this.frame);
                }

                if (session.getOutcome() != null) {
                    runtime.put("outcome", session.getOutcome());
                }

                report.put("runtime", runtime);
            }

            // The installed storage closes with gameData, so the final state is read here.
            if (failure == null || classify(failure).equals("unsupported")) {
                putState(report, game);
            }

            this.closeRenderers(layout);
        } catch (Throwable error) {
            this.simulating = false;
            this.closeRenderers(layout);

            Map<String, Object> runtime = failureReport(error, this.frame);

            this.putTrace(runtime);
            report.put("runtime", runtime);
        }

        report.put("framesRun", this.frame);
        report.put("framesPlanned", this.plannedFrames());

        @SuppressWarnings("unchecked")
        String runtimeStatus = (String) ((Map<String, Object>) report.get("runtime")).get("status");

        this.putScript(report, switch (runtimeStatus) {
            case "unsupported" -> "inconclusive";
            case "failed", "hang" -> "failed";
            default -> null;
        });

        if (this.profiler != null) {
            report.put("profile", this.profiler.report());
        }

        if (this.options.js() != null) {
            report.put("js", JsScriptExtension.report());
        }

        if (!this.interfaceErrors.isEmpty()) {
            report.put("interfaceErrors", List.copyOf(this.interfaceErrors));
        }

        report.put("loadMs", this.loadMillis);
        report.put("elapsedMs", (System.nanoTime() - this.startNanos) / 1_000_000);

        return report;
    }

    /**
     * Opens the installed storage and loads its game data, which owns the storage from then on.
     *
     * @param data   the SC:R installation
     * @param opened receives the opened storage
     * @return the game data
     * @throws IOException if the storage cannot be opened or a table cannot be loaded
     */
    private static @NotNull GameData loadGameData(@NotNull Path data, @Nullable CascStorage @NotNull [] opened) throws IOException {
        CascStorage storage = CascStorage.open(data);

        try {
            GameData gameData = GameData.load(storage);

            opened[0] = storage;

            return gameData;
        } catch (IOException | RuntimeException | Error exception) {
            try {
                storage.close();
            } catch (RuntimeException | Error closeFailure) {
                exception.addSuppressed(closeFailure);
            }

            throw exception;
        }
    }

    /**
     * Closes the shot renderer and the message metrics before the storage they read closes.
     */
    private void closeRenderers(@Nullable MessageLayout layout) {
        ShotRenderer renderer = this.shotRenderer;

        if (renderer != null) {
            renderer.close();
        }

        if (layout != null) {
            layout.close();
        }
    }

    /**
     * Adds the running epScript statements of a debug build: {@code where} the innermost, {@code trace} the stack outermost first.
     */
    private void putTrace(@NotNull Map<String, Object> runtime) {
        TraceReader reader = this.trace;
        Game current = this.game;

        if (reader == null || current == null) {
            return;
        }

        try {
            List<String> stack = reader.readStack(current);

            runtime.put("where", TraceReader.top(stack));
            runtime.put("trace", stack);
        } catch (RuntimeException error) {
            runtime.put("traceError", error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    /**
     * Adds the final message lines and live unit count.
     */
    private static void putState(@NotNull Map<String, Object> report, @NotNull Game game) {
        try {
            GameReads reads = new GameReads(game);

            report.put("screenText", reads.messageLines());
            report.put("units", reads.liveUnits());
        } catch (RuntimeException error) {
            // A game that stopped may not be readable; the report says why the state is missing.
            report.put("readError", error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    /**
     * Adds the script section: every expect result, then the expects never reached.
     *
     * @param report         the report
     * @param runtimeVerdict the script status the runtime decides, or {@code null} when the expects decide
     */
    private void putScript(@NotNull Map<String, Object> report, @Nullable String runtimeVerdict) {
        if (this.jsTest != null) {
            report.put("script", this.jsTest.section(runtimeVerdict, JsScriptExtension.errors()));
            return;
        }

        if (this.script == null) {
            return;
        }

        List<Object> results;
        Set<Integer> reached = new HashSet<>();

        synchronized (this.expects) {
            results = new ArrayList<>(this.expects);

            for (Map<String, Object> result : this.expects) {
                reached.add((Integer) result.get("line"));
            }
        }

        boolean unreached = false;

        for (MtestScript.Step step : this.script.getSteps()) {
            if (step instanceof MtestScript.Expect expect && !reached.contains(expect.line())) {
                results.add(ordered("line", expect.line(), "expect", expect.source(), "ok", false, "notReached", true));
                unreached = true;
            }
        }

        boolean allPassed = results.stream().allMatch(result -> Boolean.TRUE.equals(((Map<?, ?>) result).get("ok")));
        String status = runtimeVerdict != null ? runtimeVerdict : unreached ? "incomplete" : allPassed ? "passed" : "failed";
        Map<String, Object> section = ordered("status", status, "expects", results);

        if (!this.script.getShots().isEmpty()) {
            section.put("shots", this.shotResults());

            ShotRenderer renderer = this.shotRenderer;

            if (renderer != null && !renderer.getWarnings().isEmpty()) {
                section.put("shotWarnings", renderer.getWarnings());
            }
        }

        report.put("script", section);
    }

    /**
     * @return every shot result, then the shots never reached
     */
    private @NotNull List<Object> shotResults() {
        List<Object> results;
        Set<Integer> taken = new HashSet<>();

        synchronized (this.shots) {
            results = new ArrayList<>(this.shots);

            for (Map<String, Object> shot : this.shots) {
                taken.add((Integer) shot.get("line"));
            }
        }

        for (MtestScript.Shot shot : this.script.getShots()) {
            if (!taken.contains(shot.line())) {
                results.add(ordered("line", shot.line(), "name", shot.name(), "notReached", true));
            }
        }

        return results;
    }

    /**
     * Runs the script steps.
     *
     * @return why the run stopped before the script ended, or {@code null}
     */
    private @Nullable String runScript(@NotNull LiveMapSession session) {
        Game game = session.getGame();

        for (MtestScript.Step step : this.script.getSteps()) {
            String stopped = switch (step) {
                case MtestScript.Wait wait -> this.advance(session, wait.frames());
                case MtestScript.KeyStep key -> switch (key.action()) {
                    case HOLD -> this.holdKey(session, key.virtualKey(), key.frames());
                    case DOWN -> {
                        this.keyDown(key.virtualKey());
                        yield null;
                    }
                    case UP -> {
                        this.releaseKey(game, key.virtualKey());
                        yield null;
                    }
                };
                case MtestScript.MouseStep mouse -> switch (mouse.action()) {
                    case CLICK -> this.click(session, mouse.x(), mouse.y(), mouse.button(), mouse.frames());
                    case PRESS -> {
                        this.press(session, mouse.x(), mouse.y(), mouse.button());
                        yield null;
                    }
                    case RELEASE -> {
                        this.release(game, mouse.button());
                        yield null;
                    }
                };
                case MtestScript.View view -> {
                    this.view(game, view.x(), view.y());
                    yield null;
                }
                case MtestScript.Chat chat -> {
                    this.chat(session, chat.text());
                    yield null;
                }
                case MtestScript.Expect expect -> {
                    this.expects.add(evaluate(game, expect, this.frame));
                    yield null;
                }
                case MtestScript.Shot shot -> {
                    this.shots.add(this.shoot(session, shot.line(), shot.name()));
                    yield null;
                }
            };

            if (stopped != null) {
                return stopped;
            }
        }

        return null;
    }

    /**
     * @return the runner's actions for a JavaScript test of this run
     */
    private @NotNull JsTest.Host jsHost(@NotNull LiveMapSession session) {
        Game game = session.getGame();

        return new JsTest.Host() {
            @Override
            public @Nullable String advance(int frames) {
                return RunnerMain.this.advance(session, frames);
            }

            @Override
            public @Nullable String holdKey(int virtualKey, int frames) {
                return RunnerMain.this.holdKey(session, virtualKey, frames);
            }

            @Override
            public void keyDown(int virtualKey) {
                RunnerMain.this.keyDown(virtualKey);
            }

            @Override
            public void keyUp(int virtualKey) {
                RunnerMain.this.releaseKey(game, virtualKey);
            }

            @Override
            public @Nullable String click(int x, int y, int button, int frames) {
                return RunnerMain.this.click(session, x, y, button, frames);
            }

            @Override
            public @Nullable String clickScreen(int x, int y, int button, int frames) {
                return RunnerMain.this.clickScreen(session, x, y, button, frames);
            }

            @Override
            public void mouse(int x, int y) {
                RunnerMain.this.mouse(game, x, y);
            }

            @Override
            public void press(int x, int y, int button) {
                RunnerMain.this.press(session, x, y, button);
            }

            @Override
            public void release(int button) {
                RunnerMain.this.release(game, button);
            }

            @Override
            public void view(int x, int y) {
                RunnerMain.this.view(game, x, y);
            }

            @Override
            public boolean chat(@NotNull String text) {
                return RunnerMain.this.chat(session, text);
            }

            @Override
            public @NotNull Map<String, Object> shot(int line, @NotNull String name) {
                return RunnerMain.this.shoot(session, line, name);
            }

            @Override
            public int frame() {
                return RunnerMain.this.frame;
            }

            @Override
            public @NotNull GameReads reads() {
                return new GameReads(game);
            }
        };
    }

    /**
     * Holds a key for some frames, then releases it.
     *
     * @return why the run stopped early, or {@code null}
     */
    @Nullable String holdKey(@NotNull LiveMapSession session, int virtualKey, int frames) {
        this.heldKeys.add(virtualKey);

        String result = this.advance(session, frames);

        this.releaseKey(session.getGame(), virtualKey);

        return result;
    }

    void keyDown(int virtualKey) {
        this.heldKeys.add(virtualKey);
    }

    /**
     * Holds a mouse button at a map pixel for some frames, then releases it.
     *
     * @return why the run stopped early, or {@code null}
     */
    @Nullable String click(@NotNull LiveMapSession session, int x, int y, int button, int frames) {
        this.press(session, x, y, button);

        String result = this.advance(session, frames);

        this.release(session.getGame(), button);

        return result;
    }

    /**
     * Moves the pointer to a pixel of the game screen, lets one frame pass with it resting there (a map that arms a button while the pointer is
     * over it sees the hover before the press, as with a hand), then holds a mouse button for some frames and releases it; the screen does
     * not move.
     *
     * @return why the run stopped early, or {@code null}
     */
    @Nullable String clickScreen(@NotNull LiveMapSession session, int x, int y, int button, int frames) {
        Game game = session.getGame();

        this.mouse(game, x, y);

        String result = this.advance(session, 1);

        if (result != null) {
            return result;
        }

        this.mouseButtons |= button;
        this.pressOnMap(session, button);

        result = this.advance(session, frames);

        this.release(game, button);

        return result;
    }

    /**
     * Moves the pointer to a pixel of the game screen; the screen and the buttons stay as they are.
     */
    void mouse(@NotNull Game game, int x, int y) {
        this.viewMouseX = x;
        this.viewMouseY = y;
        this.applyMouse(game);
    }

    void press(@NotNull LiveMapSession session, int x, int y, int button) {
        this.pointAt(session.getGame(), x, y);
        this.mouseButtons |= button;
        this.pressOnMap(session, button);
    }

    void release(@NotNull Game game, int button) {
        this.mouseButtons &= ~button;
        this.applyMouse(game);
        this.releaseOnMap(button);
    }

    void view(@NotNull Game game, int x, int y) {
        // The pointer keeps its place on the screen; the next frame supplies the moved camera.
        this.camera().centerOn(x, y);
        this.applyMouse(game);
    }

    /**
     * Enters a chat line as the chat box on Enter. A network game's writes the sender's own line at once; a single-player game's checks the
     * line as a cheat and writes none. Not recorded for a replay, and no cheat is applied.
     *
     * @return {@code true} if the game is a network game and wrote the line
     */
    boolean chat(@NotNull LiveMapSession session, @NotNull String text) {
        if (!session.getLive().isNetworkGame()) {
            return false;
        }

        session.getGame().getInterfaceLink().getMessageLines()
               .writeChat(session.getLive().getLocalPlayer(), text.getBytes(StandardCharsets.UTF_8));

        return true;
    }

    /**
     * Advances frames with the held input.
     *
     * @return why the run stopped early ({@code failure}, {@code time_limit}, {@code game_ended}), or {@code null}
     */
    private @Nullable String advance(@NotNull LiveMapSession session, int frames) {
        Game game = session.getGame();
        int target = this.frame + frames;
        int stalls = 0;

        while (this.frame < target) {
            if (session.getFailure() != null) {
                return "failure";
            }

            if ((System.nanoTime() - this.startNanos) / 1_000_000 > this.options.timeLimitMillis()) {
                return "time_limit";
            }

            for (int key : this.heldKeys) {
                game.getInterfaceLink().getLocalKeyStates().setState(key, 1);
                game.getInterfaceLink().getLocalKeyStates().setAsyncPressed(key, true);
            }

            this.applyMouse(game);

            int before = game.getCurrentFrame();

            session.stepFrame();

            int after = game.getCurrentFrame();

            if (after == before) {
                if (session.getFailure() != null) {
                    return "failure";
                }

                if (++stalls > STALL_LIMIT) {
                    return "game_ended";
                }
            } else {
                stalls = 0;
                this.frame += after - before;

                // As the client: a screen the game stored and the trigger Center View move the camera before the interface follows.
                this.camera().followFrame();

                if (this.clientInterface != null) {
                    this.clientInterface.followFrame();
                }

                if (this.profiler != null) {
                    this.profiler.endFrame(game, this.frame);
                }
            }

            this.lastProgressNanos = System.nanoTime();

            if (session.getOutcome() != null) {
                return "game_ended";
            }
        }

        return session.getFailure() != null ? "failure" : null;
    }

    /**
     * @return the client interface of the run, made on first use
     */
    private @NotNull ClientInterface requireInterface(@NotNull LiveMapSession session) {
        ClientInterface current = this.clientInterface;

        if (current == null) {
            current              = new ClientInterface(session.getGame(), session.getLive(), session.getFrameTiming(), this.camera());
            this.clientInterface = current;
        }

        return current;
    }

    /**
     * Hands a left press at the pointer to the client's world interaction, which selects as the client does.
     */
    private void pressOnMap(@NotNull LiveMapSession session, int button) {
        if (button != MtestScript.LEFT_BUTTON) {
            return;
        }

        try {
            ViewCamera camera = this.camera();

            this.requireInterface(session).pressLeft(camera.getScreenX() + this.viewMouseX, camera.getScreenY() + this.viewMouseY,
                                                     this.viewMouseX, this.viewMouseY);
        } catch (IOException | RuntimeException error) {
            this.interfaceErrors.add("frame " + this.frame + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    /**
     * Hands a left release at the pointer to the client's world interaction: a click selects the unit under it, a box the units inside.
     */
    private void releaseOnMap(int button) {
        ClientInterface current = this.clientInterface;

        if (button != MtestScript.LEFT_BUTTON || current == null) {
            return;
        }

        try {
            current.releaseLeft(this.camera().getScreenX() + this.viewMouseX, this.camera().getScreenY() + this.viewMouseY);
        } catch (IOException | RuntimeException error) {
            this.interfaceErrors.add("frame " + this.frame + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    /**
     * Draws a shot of the current frame. The game does not run meanwhile; a shot that cannot be drawn is reported and the run goes on.
     */
    @NotNull Map<String, Object> shoot(@NotNull LiveMapSession session, int line, @NotNull String name) {
        Path folder = this.options.shotsDir();

        this.rendering = true;

        try {
            if (folder == null) {
                throw new IOException("no --shots-dir");
            }

            ShotRenderer renderer = this.requireShotRenderer();
            Path file = folder.resolve(name + ".png").toAbsolutePath();

            renderer.shoot(this.requireInterface(session), file);

            return ordered("line", line, "name", name, "frame", this.frame, "file", file.toString(), "width", ShotRenderer.WIDTH,
                           "height", ShotRenderer.HEIGHT);
        } catch (IOException | RuntimeException | LinkageError error) {
            String reason = error instanceof IOException ? error.getMessage() : error.getClass().getSimpleName() + ": " + error.getMessage();

            return ordered("line", line, "name", name, "frame", this.frame, "error", "unavailable: " + reason);
        } finally {
            this.lastProgressNanos = System.nanoTime();
            this.rendering         = false;
        }
    }

    /**
     * @return the shot renderer, made at the first shot
     * @throws IOException if the run has no installed storage to draw with
     */
    private @NotNull ShotRenderer requireShotRenderer() throws IOException {
        ShotRenderer current = this.shotRenderer;

        if (current == null) {
            if (this.shotStorage == null || this.shotResources == null || this.shotLocale == null) {
                throw new IOException("no renderer");
            }

            current           = new ShotRenderer(this.shotStorage, this.shotResources, this.shotLocale);
            this.shotRenderer = current;
        }

        return current;
    }

    private void releaseKey(@NotNull Game game, int key) {
        this.heldKeys.remove(key);
        game.getInterfaceLink().getLocalKeyStates().setState(key, 0);
        game.getInterfaceLink().getLocalKeyStates().setAsyncPressed(key, false);
    }

    /**
     * @return the local player's camera
     * @throws IllegalStateException before the game started
     */
    private @NotNull ViewCamera camera() {
        ViewCamera current = this.viewCamera;

        if (current == null) {
            throw new IllegalStateException("The camera is made when the game starts.");
        }

        return current;
    }

    /**
     * Moves the screen and the pointer so that the pointer rests on a map pixel: the screen centres on it within the map.
     */
    private void pointAt(@NotNull Game game, int x, int y) {
        ViewCamera camera = this.camera();

        camera.centerOn(x, y);
        this.viewMouseX = x - camera.getScreenX();
        this.viewMouseY = y - camera.getScreenY();
        this.applyMouse(game);
    }

    /**
     * Supplies the buttons, the pointer and the camera to the game, as the client does before every frame.
     */
    private void applyMouse(@NotNull Game game) {
        game.getInterfaceLink().getLocalInterface().setMouseButtons(this.mouseButtons);
        this.camera().supply(this.viewMouseX, this.viewMouseY);
    }

    private static @NotNull Map<String, Object> evaluate(@NotNull Game game, @NotNull MtestScript.Expect expect, int frame) {
        Map<String, Object> result = ordered("line", expect.line(), "expect", expect.source());

        try {
            GameReads reads = new GameReads(game);

            switch (expect.subject()) {
                case DEATHS -> {
                    long actual = reads.deaths(expect.player(), expect.unit());
                    result.put("ok", expect.holds(actual));
                    result.put("actual", actual);
                }
                case MEMORY -> {
                    long actual = reads.memory(expect.address());
                    result.put("ok", expect.holds(actual));
                    result.put("actual", actual);
                }
                case UNITS -> {
                    long actual = reads.liveUnits();
                    result.put("ok", expect.holds(actual));
                    result.put("actual", actual);
                }
                case TEXT -> {
                    List<String> lines = reads.messageLines();
                    result.put("ok", expect.holdsText(lines));
                    result.put("actual", lines);
                }
            }
        } catch (RuntimeException error) {
            result.put("ok", false);
            result.put("readError", error.getClass().getSimpleName() + ": " + error.getMessage());
        }

        result.put("frame", frame);

        return result;
    }

    /**
     * Classifies a failure: an operation the emulator does not support is {@code unsupported} (not a map bug), anything else
     * {@code failed}.
     */
    static @NotNull Map<String, Object> failureReport(@NotNull Throwable failure, int frame) {
        boolean unsupported = classify(failure).equals("unsupported");
        Throwable root = failure;

        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        StringWriter trace = new StringWriter();

        root.printStackTrace(new PrintWriter(trace));

        String[] lines = trace.toString().split("\\R");
        List<String> detail = new ArrayList<>();

        for (int index = 1; index < Math.min(lines.length, TRACE_LINES + 1); index++) {
            detail.add(lines[index].strip());
        }

        String error = root.getClass().getSimpleName() + (root.getMessage() != null ? ": " + root.getMessage() : "");

        return unsupported
               ? ordered("status", "unsupported", "frame", frame, "feature", error, "detail", detail)
               : ordered("status", "failed", "frame", frame, "error", error, "detail", detail);
    }

    /**
     * @param failure a failure of the game
     * @return {@code unsupported} when an operation the emulator does not support caused it, else {@code failed}
     */
    static @NotNull String classify(@NotNull Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnsupportedOperationException) {
                return "unsupported";
            }
        }

        return "failed";
    }

    private static @NotNull Map<String, Object> baseReport() {
        return ordered("version", REPORT_VERSION, "runner", "eud-runner " + RunnerMain.class.getPackage().getImplementationVersion());
    }

    static @NotNull Map<String, Object> ordered(Object @NotNull ... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();

        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }

        return map;
    }

    private static void writeReport(@NotNull Path out, @NotNull Map<String, Object> report) throws IOException {
        Path temporary = out.resolveSibling(out.getFileName() + ".tmp");

        Files.writeString(temporary, Json.write(report), StandardCharsets.UTF_8);
        Files.move(temporary, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static @NotNull Options parseOptions(String @NotNull [] arguments) {
        Path data = null;
        Path map = null;
        Path out = null;
        Path script = null;
        Path epmap = null;
        Path shotsDir = null;
        Path js = null;
        boolean profile = false;
        boolean lan = false;
        int frames = DEFAULT_FRAMES;
        int slot = 0;
        long frameTimeout = DEFAULT_FRAME_TIMEOUT_MILLIS;
        long timeLimit = DEFAULT_TIME_LIMIT_MILLIS;

        for (int index = 0; index < arguments.length; index++) {
            String name = arguments[index];

            if (name.equals("--profile")) {
                profile = true;
                continue;
            }

            if (name.equals("--lan")) {
                lan = true;
                continue;
            }

            if (index + 1 >= arguments.length) {
                throw new IllegalArgumentException(name + " needs a value.");
            }

            String value = arguments[++index];

            switch (name) {
                case "--data" -> data = Path.of(value);
                case "--map" -> map = Path.of(value);
                case "--out" -> out = Path.of(value);
                case "--script" -> script = Path.of(value);
                case "--epmap" -> epmap = Path.of(value);
                case "--shots-dir" -> shotsDir = Path.of(value);
                case "--js" -> js = Path.of(value);
                case "--frames" -> frames = parsePositive(name, value, 1_000_000);
                case "--slot" -> slot = parsePositive(name, value, 11);
                case "--frame-timeout-ms" -> frameTimeout = parsePositive(name, value, 600_000);
                case "--time-limit-ms" -> timeLimit = parsePositive(name, value, 3_600_000);
                default -> throw new IllegalArgumentException("Unknown option: " + name);
            }
        }

        if (data == null || map == null || out == null) {
            throw new IllegalArgumentException("--data, --map and --out are required.");
        }

        return new Options(data, map, out, script, frames, slot, frameTimeout, timeLimit, epmap, profile, shotsDir, js, lan, DEFAULT_PLAYER_NAME);
    }

    private static int parsePositive(@NotNull String name, @NotNull String value, int maximum) {
        try {
            int number = Integer.parseInt(value);

            if (number < 0 || number > maximum) {
                throw new IllegalArgumentException(name + " must be from 0 to " + maximum + ".");
            }

            return number;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number.");
        }
    }
}
