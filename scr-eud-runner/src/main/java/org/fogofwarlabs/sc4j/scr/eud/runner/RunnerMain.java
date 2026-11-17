package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
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
 * Runs a map headless as a single-player use-map-settings game, optionally driven by a {@code .mtest} script, and writes one JSON report.
 * <p>
 * {@code eud-runner --data <SC:R folder> --map <scx|scm> --out <report.json> [--script <file.mtest>] [--frames N] [--slot N]
 * [--frame-timeout-ms N] [--time-limit-ms N] [--epmap <output.epmap>] [--profile]}
 * <p>
 * With the trace file of a {@code [main] debug} build, a failed or hung run reports the epScript statements that were running.
 * {@code --profile} counts the trigger records and actions of each frame and, with the trace file, attributes the actions to statements.
 * <p>
 * The report is written to {@code --out} (never stdout, which the engine's logging may share). Exit status 0 means a report was written,
 * 2 a usage error.
 */
public final class RunnerMain {
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
    private static final int TRACE_LINES = 6;

    private final @NotNull Options options;
    private final @Nullable MtestScript script;
    private final long startNanos = System.nanoTime();
    private final List<Map<String, Object>> expects = Collections.synchronizedList(new ArrayList<>());
    private volatile int frame;
    private volatile long lastProgressNanos;
    private volatile boolean simulating;
    private volatile long loadMillis;
    private volatile @Nullable Game game;
    private @Nullable TraceReader trace;
    private @Nullable Profiler profiler;

    private final Set<Integer> heldKeys = new HashSet<>();
    private int mouseButtons;
    private int viewScreenX;
    private int viewScreenY;
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
     */
    record Options(@NotNull Path data, @NotNull Path map, @NotNull Path out, @Nullable Path script, int frames, int slot,
                   long frameTimeoutMillis, long timeLimitMillis, @Nullable Path epmap, boolean profile) {
    }

    private RunnerMain(@NotNull Options options, @Nullable MtestScript script) {
        this.options = options;
        this.script  = script;
    }

    /**
     * Runs the command line.
     *
     * @param arguments the arguments
     * @throws Exception if the report cannot be written
     */
    public static void main(String @NotNull [] arguments) throws Exception {
        Options options;

        try {
            options = parseOptions(arguments);
        } catch (IllegalArgumentException exception) {
            System.err.println("eud-runner: " + exception.getMessage());
            System.err.println("usage: eud-runner --data <SC:R folder> --map <scx|scm> --out <report.json> [--script <file.mtest>] " +
                               "[--frames N] [--slot N] [--frame-timeout-ms N] [--time-limit-ms N] [--epmap <output.epmap>] [--profile]");
            System.exit(2);
            return;
        }

        MtestScript script = null;

        if (options.script() != null) {
            script = MtestScript.parse(Files.readAllLines(options.script(), StandardCharsets.UTF_8));

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

        RunnerMain runner = new RunnerMain(options, script);

        if (options.epmap() != null) {
            runner.trace = TraceReader.load(options.epmap());
        }

        runner.run();
    }

    private void run() throws Exception {
        AtomicReference<Map<String, Object>> finished = new AtomicReference<>();
        Thread worker = new Thread(() -> finished.set(this.simulate()), "eud-runner-simulation");

        worker.setDaemon(true);
        worker.start();

        while (worker.isAlive()) {
            worker.join(100);

            long now = System.nanoTime();

            if (worker.isAlive() && this.simulating && (now - this.lastProgressNanos) / 1_000_000 > this.options.frameTimeoutMillis()) {
                this.writeAbandoned("hang", "No frame finished within " + this.options.frameTimeoutMillis() + " ms.");
                return;
            }

            if (worker.isAlive() && (now - this.startNanos) / 1_000_000 > this.options.timeLimitMillis() + this.options.frameTimeoutMillis()) {
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
        report.put("loadMs", this.loadMillis);
        report.put("elapsedMs", (System.nanoTime() - this.startNanos) / 1_000_000);
        writeReport(this.options.out(), report);
        Runtime.getRuntime().halt(0);
    }

    private long plannedFrames() {
        return this.script != null ? this.script.getPlannedFrames() : this.options.frames();
    }

    private @NotNull Map<String, Object> simulate() {
        Map<String, Object> report = baseReport();
        Game game = null;

        try (GameData gameData = GameData.loadFrom(this.options.data())) {
            byte[] mapData = MpqArchive.open(this.options.map()).readFile("staredit\\scenario.chk");
            MeleeGameSetup setup = MeleeGameSetup.createUseMapSettings(mapData, "tester", ScrRaceIds.TERRAN, this.options.slot(), 6);
            LiveGame live = new LiveGame(setup, 12345);
            LiveMapSession session = LiveMapSession.start(gameData, live, this.options.map());

            game      = session.getGame();
            this.game = game;

            if (this.options.profile()) {
                this.profiler = new Profiler(this.trace);
                this.profiler.attach(game);
            }
            this.loadMillis        = (System.nanoTime() - this.startNanos) / 1_000_000;
            this.lastProgressNanos = System.nanoTime();
            this.simulating        = true;

            String stoppedBy = this.script != null ? this.runScript(session) : this.advance(session, this.options.frames());

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
        } catch (Throwable error) {
            this.simulating = false;

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

        report.put("loadMs", this.loadMillis);
        report.put("elapsedMs", (System.nanoTime() - this.startNanos) / 1_000_000);

        return report;
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

        report.put("script", ordered("status", status, "expects", results));
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
                    case HOLD -> {
                        this.heldKeys.add(key.virtualKey());
                        String result = this.advance(session, key.frames());
                        this.releaseKey(game, key.virtualKey());
                        yield result;
                    }
                    case DOWN -> {
                        this.heldKeys.add(key.virtualKey());
                        yield null;
                    }
                    case UP -> {
                        this.releaseKey(game, key.virtualKey());
                        yield null;
                    }
                };
                case MtestScript.MouseStep mouse -> switch (mouse.action()) {
                    case CLICK -> {
                        this.pointAt(game, mouse.x(), mouse.y());
                        this.mouseButtons |= mouse.button();
                        String result = this.advance(session, mouse.frames());
                        this.mouseButtons &= ~mouse.button();
                        this.applyMouse(game);
                        yield result;
                    }
                    case PRESS -> {
                        this.pointAt(game, mouse.x(), mouse.y());
                        this.mouseButtons |= mouse.button();
                        yield null;
                    }
                    case RELEASE -> {
                        this.mouseButtons &= ~mouse.button();
                        this.applyMouse(game);
                        yield null;
                    }
                };
                case MtestScript.Expect expect -> {
                    this.expects.add(evaluate(game, expect, this.frame));
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

    private void releaseKey(@NotNull Game game, int key) {
        this.heldKeys.remove(key);
        game.getInterfaceLink().getLocalKeyStates().setState(key, 0);
        game.getInterfaceLink().getLocalKeyStates().setAsyncPressed(key, false);
    }

    /**
     * Moves the screen and the pointer so that the pointer rests on a map pixel.
     */
    private void pointAt(@NotNull Game game, int x, int y) {
        int mapWidth = game.getMap().getWidth() * 32;
        int mapHeight = game.getMap().getHeight() * 32;

        this.viewScreenX = Math.clamp(x - LiveMapSession.VIEWPORT_WIDTH / 2, 0, Math.max(0, mapWidth - LiveMapSession.VIEWPORT_WIDTH));
        this.viewScreenY = Math.clamp(y - LiveMapSession.VIEWPORT_HEIGHT / 2, 0, Math.max(0, mapHeight - LiveMapSession.VIEWPORT_HEIGHT));
        this.viewMouseX  = x - this.viewScreenX;
        this.viewMouseY  = y - this.viewScreenY;
        this.applyMouse(game);
    }

    private void applyMouse(@NotNull Game game) {
        game.getInterfaceLink().getLocalInterface().setMouseButtons(this.mouseButtons);
        game.getInterfaceLink().getLocalView().setView(this.viewScreenX, this.viewScreenY, this.viewMouseX, this.viewMouseY);
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
        boolean profile = false;
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

        return new Options(data, map, out, script, frames, slot, frameTimeout, timeLimit, epmap, profile);
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
