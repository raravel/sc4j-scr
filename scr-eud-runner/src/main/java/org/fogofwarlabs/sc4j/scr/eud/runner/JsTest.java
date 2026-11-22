package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A map test written in JavaScript ({@code NAME.test.js}), which runs in the game's SC:R Enhanced script runtime.
 * <p>
 * The file is the script package's one module beside the runner's main script, which hands its top-level {@code test} function to the
 * runner; its hooks ({@code onStart}, {@code onFrame}, {@code onUnitDied}, ...) run as in any script. The script starts in the game's first
 * frame; then the runner calls {@code test(t)} between frames and steps the generator it returns: each {@code yield} of {@code t.wait},
 * {@code t.until}, {@code t.key} or {@code t.click} lets frames pass, everything else acts at once on the state reached so far, as a
 * {@code .mtest} line does. A plain function runs once; a file without {@code test} only runs its hooks for the run's frames.
 * <p>
 * {@code t} holds the runner's input and checks:
 * <ul>
 * <li>{@code yield t.wait(N)}; {@code yield t.until(() => condition, MAXFRAMES)}, which resumes with the frames waited, or fails the test
 * and ends it after MAXFRAMES;</li>
 * <li>{@code yield t.key(NAME, FRAMES = 8)}, {@code t.keyDown(NAME)}, {@code t.keyUp(NAME)};</li>
 * <li>{@code yield t.click(X, Y, BUTTON = 'left', FRAMES = 8)}, {@code t.press(X, Y, BUTTON)}, {@code t.release(BUTTON)},
 * {@code t.view(X, Y)}, {@code t.chat(TEXT)};</li>
 * <li>{@code t.shot(NAME)}; the reads {@code t.deaths(PLAYER 1-12, UNIT)}, {@code t.memory(ADDRESS)}, {@code t.units()}, {@code t.text()}
 * and {@code t.frame()};</li>
 * <li>{@code t.expect(VALUE)} and {@code t.require(VALUE)} with {@code toBe}, {@code atLeast}, {@code atMost}, {@code above},
 * {@code below}, {@code toContain} and {@code not.toBe}, {@code not.toContain}; a failed {@code expect} is recorded and the test goes on, a
 * failed {@code require} ends it.</li>
 * </ul>
 */
final class JsTest {
    /**
     * The extension of a JavaScript map test.
     */
    static final @NotNull String EXTENSION = ".js";
    /**
     * The runner's main script of the package, which loads the test file.
     */
    static final @NotNull String MAIN_SCRIPT = "eud-runner.js";
    /**
     * The global through which the main script hands over the test function.
     */
    static final @NotNull String HOST_GLOBAL = "__eudRunner";
    static final @NotNull String EXPORT_NAME = "__eudTest";
    static final int MAXIMUM_UNTIL_FRAMES = MtestScript.MAXIMUM_WAIT_FRAMES;
    private static final int MAXIMUM_SOURCE_CHARS = 200;
    private static final int MAXIMUM_ACTUAL_ITEMS = 50;
    private static final @NotNull Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,80}\\.js");
    private static final @NotNull Pattern SHOT_NAME = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final @NotNull Pattern CHAT_CALL = Pattern.compile("\\.\\s*chat\\s*\\(");

    /**
     * What the runner does for the test.
     */
    interface Host {
        /**
         * @return why the run stopped early, or {@code null}
         */
        @Nullable String advance(int frames);

        @Nullable String holdKey(int virtualKey, int frames);

        void keyDown(int virtualKey);

        void keyUp(int virtualKey);

        @Nullable String click(int x, int y, int button, int frames);

        void press(int x, int y, int button);

        void release(int button);

        void view(int x, int y);

        /**
         * @return {@code false} if the game is not a network game, whose chat box writes no line
         */
        boolean chat(@NotNull String text);

        @NotNull Map<String, Object> shot(int line, @NotNull String name);

        int frame();

        @NotNull GameReads reads();
    }

    /**
     * A step of the test that lets frames pass; it acts when the test yields it.
     */
    private sealed interface Token permits Wait, Until, Key, Click {
        int line();

        @NotNull String call();
    }

    private record Wait(int line, int frames) implements Token {
        @Override
        public @NotNull String call() {
            return "t.wait";
        }
    }

    private record Until(int line, @NotNull Function condition, int frames) implements Token {
        @Override
        public @NotNull String call() {
            return "t.until";
        }
    }

    private record Key(int line, int virtualKey, int frames) implements Token {
        @Override
        public @NotNull String call() {
            return "t.key";
        }
    }

    private record Click(int line, int x, int y, int button, int frames) implements Token {
        @Override
        public @NotNull String call() {
            return "t.click";
        }
    }

    /**
     * A comparison of a matcher.
     */
    private interface Comparison {
        boolean holds(@Nullable Object actual, @Nullable Object expected);
    }

    private final @NotNull String fileName;
    private final @NotNull String text;
    private final @NotNull List<String> lines;

    // Synchronized: a hang's report reads them from the watchdog thread.
    private final @NotNull List<Map<String, Object>> expects = Collections.synchronizedList(new ArrayList<>());
    private final @NotNull List<Map<String, Object>> shots = Collections.synchronizedList(new ArrayList<>());
    private final @NotNull List<Map<String, Object>> problems = Collections.synchronizedList(new ArrayList<>());
    private final @NotNull Map<Scriptable, Token> tokens = new IdentityHashMap<>();
    private final @NotNull Set<String> shotNames = new HashSet<>();

    private @Nullable Function test;
    private @Nullable Function startCall;
    private @Nullable Function stepCall;
    private @Nullable Function untilCall;
    private @Nullable Scriptable generator;
    private @Nullable Function next;
    private @Nullable Object resume;
    private boolean stepDone;
    private @Nullable Object stepValue;
    private @Nullable Function condition;
    private boolean conditionHolds;
    /**
     * The frame step the test yielded last: where it waits when the run stops.
     */
    private volatile @Nullable Token current;
    private @Nullable Scriptable global;
    private @Nullable Scriptable testObject;
    private @Nullable Host host;
    private boolean stepping;
    private volatile boolean finished;
    private volatile boolean ended;
    private volatile boolean noTest;
    private volatile @Nullable String error;
    private int ownErrors;

    private JsTest(@NotNull String fileName, @NotNull String text) {
        this.fileName = fileName;
        this.text     = text;
        this.lines    = text.lines().toList();
    }

    /**
     * Reads a test file.
     *
     * @param file the {@code .js} file
     * @return the test
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if its name cannot be a script name
     */
    static @NotNull JsTest load(@NotNull Path file) throws IOException {
        String name = file.getFileName().toString();

        if (!FILE_NAME.matcher(name).matches() || name.equalsIgnoreCase(MAIN_SCRIPT)) {
            throw new IllegalArgumentException("The test file name " + name + " is not A-Z a-z 0-9 _ . - ending in .js.");
        }

        String text = Files.readString(file, StandardCharsets.UTF_8);

        return new JsTest(name, text.startsWith("﻿") ? text.substring(1) : text);
    }

    /**
     * @return whether the file calls a {@code chat}, which needs a network game
     */
    boolean usesChat() {
        return CHAT_CALL.matcher(this.text).find();
    }

    /**
     * Writes the script package of the test into a new temporary folder: the manifest, the runner's main script and the test file with one
     * line added at its end, which exports its {@code test} function.
     *
     * @return the folder
     * @throws IOException if it cannot be written
     */
    @NotNull Path writePackage() throws IOException {
        Path folder = Files.createTempDirectory("eud-test-");

        try {
            this.writeFiles(folder);
        } catch (IOException | RuntimeException exception) {
            deletePackage(folder);
            throw exception;
        }

        return folder;
    }

    private void writeFiles(@NotNull Path folder) throws IOException {
        String main = "// eud-runner: loads the map test and hands its test function to the runner.\n" +
                      HOST_GLOBAL + ".register(require('./" + this.fileName + "')." + EXPORT_NAME + ");\n";
        String exported = this.text + (this.text.endsWith("\n") ? "" : "\n") +
                          ";module.exports." + EXPORT_NAME + " = typeof test === 'function' ? test : undefined;\n";

        Files.createDirectories(folder.resolve("resources"));
        Files.writeString(folder.resolve("manifest.json"), "{\"name\":\"map-test\",\"main\":\"" + MAIN_SCRIPT + "\",\"api\":\"1\"}",
                          StandardCharsets.UTF_8);
        Files.writeString(folder.resolve(MAIN_SCRIPT), main, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve(this.fileName), exported, StandardCharsets.UTF_8);
    }

    /**
     * Removes a folder {@link #writePackage} wrote.
     */
    static void deletePackage(@NotNull Path folder) {
        try (Stream<Path> entries = Files.walk(folder)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException ignored) {
            // A temporary folder left behind is harmless.
        }
    }

    /**
     * @return what adds the runner's globals to the script's global scope: the main script's {@link #HOST_GLOBAL}, and the test's {@code t}
     */
    @NotNull BiConsumer<Context, Scriptable> globals() {
        return (context, scope) -> {
            Scriptable runner = context.newObject(scope);

            this.global = scope;
            define(scope, runner, "register", 1, (callContext, callScope, thisObject, arguments) -> {
                if (arguments.length > 0 && arguments[0] instanceof Function function) {
                    this.test = function;
                }

                return Undefined.instance;
            });
            ScriptableObject.defineProperty((ScriptableObject) scope, HOST_GLOBAL, runner, ScriptableObject.DONTENUM | ScriptableObject.READONLY);
            this.testObject = this.createTestObject(context, scope);
            this.startCall  = new LambdaFunction(scope, "test", 0, (cx, s, self, arguments) -> {
                Function function = Objects.requireNonNull(this.test);
                Object made = function.call(cx, s, s, new Object[]{this.testObject});

                if (made instanceof Scriptable generator && ScriptableObject.getProperty(generator, "next") instanceof Function next) {
                    this.generator = generator;
                    this.next      = next;
                }

                return Undefined.instance;
            });
            this.stepCall   = new LambdaFunction(scope, "test", 0, (cx, s, self, arguments) -> {
                // A generator's next is no interpreted function, which would start the top call itself.
                Object result = org.mozilla.javascript.ScriptRuntime.doTopCall(Objects.requireNonNull(this.next), cx, s, this.generator,
                                                                               new Object[]{this.resume}, false);

                if (!(result instanceof Scriptable record)) {
                    throw Context.reportRuntimeError("The generator of test(t) gave no step.");
                }

                this.stepDone  = Context.toBoolean(ScriptableObject.getProperty(record, "done"));
                this.stepValue = ScriptableObject.getProperty(record, "value");

                return Undefined.instance;
            });
            this.untilCall  = new LambdaFunction(scope, "t.until", 0, (cx, s, self, arguments) -> {
                this.conditionHolds = Context.toBoolean(Objects.requireNonNull(this.condition).call(cx, s, s, new Object[0]));

                return Undefined.instance;
            });
        };
    }

    /**
     * Runs the test.
     *
     * @param host         the runner
     * @param hooksFrames  the frames a file without {@code test} runs
     * @return why the run stopped before the test ended, or {@code null}
     */
    @Nullable String run(@NotNull Host host, int hooksFrames) {
        this.host = host;

        // The script starts in the game's first frame, which also loads the test file.
        String stopped = host.advance(1);

        if (stopped != null) {
            return stopped;
        }

        ScriptRuntime runtime = JsScriptExtension.runtime();

        if (runtime == null || runtime.isStopped()) {
            this.error = runtime == null ? "The script was not attached to the game." : this.lastError();
            return null;
        }

        if (this.test == null) {
            this.noTest   = true;
            stopped       = host.advance(Math.max(0, hooksFrames - 1));
            this.finished = stopped == null;
            return stopped;
        }

        if (!this.call(runtime, "test", Objects.requireNonNull(this.startCall))) {
            return this.failedCall();
        }

        if (this.generator == null) {
            this.finished = this.checkYielded(null);
            return null;
        }

        this.resume = Undefined.instance;

        while (true) {
            if (!this.call(runtime, "test", Objects.requireNonNull(this.stepCall))) {
                return this.failedCall();
            }

            Object yielded = this.stepValue;
            Token token = yielded instanceof Scriptable object ? this.tokens.remove(object) : null;

            if (!this.checkYielded(token)) {
                return null;
            }

            if (this.stepDone) {
                this.finished = true;
                return null;
            }

            if (token == null) {
                this.problems.add(RunnerMain.ordered("line", 0, "code", "bad_yield", "detail", describe(yielded), "usage",
                                                     "yield t.wait(N), t.until(fn, N), t.key(NAME, N) or t.click(X, Y, BUTTON, N)"));
                return null;
            }

            this.resume  = Undefined.instance;
            this.current = token;
            stopped = switch (token) {
                case Wait wait -> host.advance(wait.frames());
                case Key key -> host.holdKey(key.virtualKey(), key.frames());
                case Click click -> host.click(click.x(), click.y(), click.button(), click.frames());
                case Until until -> {
                    int waited = 0;

                    while (true) {
                        this.condition = until.condition();

                        if (!this.call(runtime, "t.until", Objects.requireNonNull(this.untilCall))) {
                            yield this.failedCall();
                        }

                        if (this.conditionHolds) {
                            this.resume = waited;
                            yield null;
                        }

                        if (waited >= until.frames()) {
                            this.expects.add(RunnerMain.ordered("line", until.line(), "expect", this.source(until.line()), "ok", false,
                                                                "timedOut", true, "frames", until.frames(), "frame", host.frame()));
                            this.ended = true;
                            yield null;
                        }

                        String advanced = host.advance(1);

                        if (advanced != null) {
                            yield advanced;
                        }

                        waited++;
                    }
                }
            };

            if (stopped != null || this.ended || this.error != null) {
                return stopped;
            }

            if (runtime.isStopped()) {
                this.error = this.lastError();
                return null;
            }
        }
    }

    /**
     * Calls into the test with {@code t} usable, through one of the runner's functions, which read the script values inside the call.
     *
     * @return {@code true} if the call returned
     */
    private boolean call(@NotNull ScriptRuntime runtime, @NotNull String name, @NotNull Function function) {
        int errorsBefore = JsScriptExtension.errors();

        this.stepping = true;

        try {
            return runtime.callShared(name, function, null, new Object[0]).completed();
        } finally {
            this.stepping   = false;
            // The error lines the test's own call wrote; the rest of the script's are the hooks'.
            this.ownErrors += JsScriptExtension.errors() - errorsBefore;
        }
    }

    /**
     * Ends the test after a call that threw or stopped the script; a failed {@code require} is a test failure, not an error.
     */
    private @Nullable String failedCall() {
        if (!this.ended) {
            this.error = this.lastError();
        }

        return null;
    }

    private @NotNull String lastError() {
        String last = JsScriptExtension.lastError();

        return last != null ? last : "The test call failed.";
    }

    /**
     * Reports the frame-passing steps the test made but did not yield.
     *
     * @return {@code true} if there were none
     */
    private boolean checkYielded(@Nullable Token yielded) {
        if (this.tokens.isEmpty()) {
            return true;
        }

        for (Token token : this.tokens.values()) {
            if (token != yielded) {
                this.problems.add(RunnerMain.ordered("line", token.line(), "code", "not_yielded", "detail", this.source(token.line()), "usage",
                                                     "yield " + token.call() + "(...): frames pass only when the test yields it"));
            }
        }

        this.tokens.clear();

        return false;
    }

    /**
     * The test's section of the report.
     *
     * @param runtimeVerdict the status the runtime decides, or {@code null} when the test decides
     * @param scriptErrors   the error lines of the whole script, the hooks' included
     */
    @NotNull Map<String, Object> section(@Nullable String runtimeVerdict, int scriptErrors) {
        boolean anyFailed;

        synchronized (this.expects) {
            anyFailed = this.expects.stream().anyMatch(result -> !Boolean.TRUE.equals(result.get("ok")));
        }

        int hookErrors = Math.max(0, scriptErrors - this.ownErrors);
        String status;

        if (runtimeVerdict != null) {
            status = runtimeVerdict;
        } else if (!this.problems.isEmpty() || this.error != null) {
            status = "error";
        } else if (this.ended) {
            status = "failed";
        } else if (!this.finished) {
            status = "incomplete";
        } else {
            status = anyFailed || hookErrors > 0 ? "failed" : "passed";
        }

        Map<String, Object> section;

        synchronized (this.expects) {
            section = RunnerMain.ordered("status", status, "expects", List.copyOf(this.expects));
        }

        if (this.noTest) {
            // A file without a top-level test function only ran its hooks: a misspelt test(t) checks nothing.
            section.put("noTest", true);
        }

        synchronized (this.problems) {
            if (!this.problems.isEmpty()) {
                section.put("problems", List.copyOf(this.problems));
            }
        }

        if (this.error != null) {
            section.put("error", this.error);
        }

        Token waiting = this.current;

        if (!this.finished && !this.ended && this.error == null && waiting != null) {
            section.put("stoppedAt", RunnerMain.ordered("line", waiting.line(), "step", this.source(waiting.line())));
        }

        if (hookErrors > 0) {
            section.put("scriptErrors", hookErrors);
        }

        synchronized (this.shots) {
            if (!this.shots.isEmpty()) {
                section.put("shots", List.copyOf(this.shots));
            }
        }

        return section;
    }


    private @NotNull Scriptable createTestObject(@NotNull Context context, @NotNull Scriptable scope) {
        Scriptable t = context.newObject(scope);

        define(scope, t, "wait", 1, (cx, s, self, arguments) -> {
            int frames = integer(arguments, 0, "t.wait(frames)", 1, MtestScript.MAXIMUM_WAIT_FRAMES, null);

            return this.token(cx, new Wait(this.line(), frames));
        });
        define(scope, t, "until", 2, (cx, s, self, arguments) -> {
            this.requireStep("t.until");

            if (arguments.length < 1 || !(arguments[0] instanceof Function condition)) {
                throw Context.reportRuntimeError("t.until(condition, maxFrames): condition is not a function.");
            }

            int frames = integer(arguments, 1, "t.until(condition, maxFrames)", 1, MAXIMUM_UNTIL_FRAMES, null);

            return this.token(cx, new Until(this.line(), condition, frames));
        });
        define(scope, t, "key", 2, (cx, s, self, arguments) -> {
            int key = key(arguments, "t.key(name, frames)");
            int frames = integer(arguments, 1, "t.key(name, frames)", 1, MtestScript.MAXIMUM_HOLD_FRAMES, MtestScript.DEFAULT_HOLD_FRAMES);

            return this.token(cx, new Key(this.line(), key, frames));
        });
        define(scope, t, "keyDown", 1, (cx, s, self, arguments) -> {
            this.host().keyDown(key(arguments, "t.keyDown(name)"));
            return Undefined.instance;
        });
        define(scope, t, "keyUp", 1, (cx, s, self, arguments) -> {
            this.host().keyUp(key(arguments, "t.keyUp(name)"));
            return Undefined.instance;
        });
        define(scope, t, "click", 4, (cx, s, self, arguments) -> {
            String usage = "t.click(x, y, button, frames)";
            int x = integer(arguments, 0, usage, 0, MtestScript.MAXIMUM_COORDINATE, null);
            int y = integer(arguments, 1, usage, 0, MtestScript.MAXIMUM_COORDINATE, null);
            int button = button(arguments, 2, usage);
            int frames = integer(arguments, 3, usage, 1, MtestScript.MAXIMUM_HOLD_FRAMES, MtestScript.DEFAULT_HOLD_FRAMES);

            return this.token(cx, new Click(this.line(), x, y, button, frames));
        });
        define(scope, t, "press", 3, (cx, s, self, arguments) -> {
            String usage = "t.press(x, y, button)";
            int x = integer(arguments, 0, usage, 0, MtestScript.MAXIMUM_COORDINATE, null);
            int y = integer(arguments, 1, usage, 0, MtestScript.MAXIMUM_COORDINATE, null);

            this.host().press(x, y, button(arguments, 2, usage));
            return Undefined.instance;
        });
        define(scope, t, "release", 1, (cx, s, self, arguments) -> {
            this.host().release(button(arguments, 0, "t.release(button)"));
            return Undefined.instance;
        });
        define(scope, t, "view", 2, (cx, s, self, arguments) -> {
            String usage = "t.view(x, y)";
            int x = integer(arguments, 0, usage, 0, MtestScript.MAXIMUM_COORDINATE, null);
            int y = integer(arguments, 1, usage, 0, MtestScript.MAXIMUM_COORDINATE, null);

            this.host().view(x, y);
            return Undefined.instance;
        });
        define(scope, t, "chat", 1, (cx, s, self, arguments) -> {
            Host current = this.host();
            String chat = arguments.length > 0 && arguments[0] instanceof CharSequence sequence ? sequence.toString() : null;

            if (chat == null || chat.isEmpty() || chat.getBytes(StandardCharsets.UTF_8).length > MtestScript.MAXIMUM_CHAT_BYTES ||
                chat.chars().anyMatch(character -> character < 0x20 || character == 0x7F)) {
                throw Context.reportRuntimeError("t.chat(text): text is 1 to " + MtestScript.MAXIMUM_CHAT_BYTES +
                                                 " bytes of UTF-8 without control characters.");
            }

            if (!current.chat(chat)) {
                throw Context.reportRuntimeError("t.chat(text) needs a network game; the runner plays a test that calls .chat( as one.");
            }

            return Undefined.instance;
        });
        define(scope, t, "shot", 1, (cx, s, self, arguments) -> {
            Host current = this.host();
            int line = this.line();
            String name = arguments.length > 0 && !(arguments[0] instanceof Undefined) ? Context.toString(arguments[0]) : "line" + line;

            if (!SHOT_NAME.matcher(name).matches()) {
                throw Context.reportRuntimeError("t.shot(name): name is 1 to 40 of A-Z a-z 0-9 _ -.");
            }

            if (this.shots.size() >= MtestScript.MAXIMUM_SHOTS) {
                throw Context.reportRuntimeError("t.shot(name): a test takes at most " + MtestScript.MAXIMUM_SHOTS + " shots.");
            }

            if (!this.shotNames.add(name.toLowerCase(Locale.ROOT))) {
                throw Context.reportRuntimeError("t.shot(name): the name " + name + " is taken.");
            }

            this.shots.add(current.shot(line, name));
            return Undefined.instance;
        });
        define(scope, t, "deaths", 2, (cx, s, self, arguments) -> {
            String usage = "t.deaths(player 1-12, unit 0-227)";
            int player = integer(arguments, 0, usage, 1, 12, null);
            int unit = integer(arguments, 1, usage, 0, 227, null);

            return this.host().reads().deaths(player - 1, unit);
        });
        define(scope, t, "memory", 1, (cx, s, self, arguments) -> {
            String usage = "t.memory(address)";
            long address = number(arguments, 0, usage, 0, 0xFFFF_FFFFL);

            if (address % 4 != 0) {
                throw Context.reportRuntimeError(usage + ": the address is not 4-byte aligned.");
            }

            return (double) this.host().reads().memory((int) address);
        });
        define(scope, t, "units", 0, (cx, s, self, arguments) -> this.host().reads().liveUnits());
        define(scope, t, "text", 0, (cx, s, self, arguments) -> cx.newArray(scope, this.host().reads().messageLines().toArray()));
        define(scope, t, "frame", 0, (cx, s, self, arguments) -> this.host().frame());
        define(scope, t, "expect", 1, (cx, s, self, arguments) -> this.matcher(cx, scope, arguments.length > 0 ? arguments[0] : null, false));
        define(scope, t, "require", 1, (cx, s, self, arguments) -> this.matcher(cx, scope, arguments.length > 0 ? arguments[0] : null, true));

        return t;
    }

    private @NotNull Scriptable matcher(@NotNull Context context, @NotNull Scriptable scope, @Nullable Object actual, boolean require) {
        this.requireStep(require ? "t.require" : "t.expect");

        Scriptable matcher = context.newObject(scope);
        Scriptable not = context.newObject(scope);
        Comparison same = (left, right) -> org.mozilla.javascript.ScriptRuntime.shallowEq(left, right);
        Comparison contains = JsTest::contains;

        this.defineComparison(scope, matcher, "toBe", actual, false, require, same);
        this.defineComparison(scope, matcher, "atLeast", actual, false, require,
                              (left, right) -> Context.toNumber(left) >= Context.toNumber(right));
        this.defineComparison(scope, matcher, "atMost", actual, false, require,
                              (left, right) -> Context.toNumber(left) <= Context.toNumber(right));
        this.defineComparison(scope, matcher, "above", actual, false, require,
                              (left, right) -> Context.toNumber(left) > Context.toNumber(right));
        this.defineComparison(scope, matcher, "below", actual, false, require,
                              (left, right) -> Context.toNumber(left) < Context.toNumber(right));
        this.defineComparison(scope, matcher, "toContain", actual, false, require, contains);
        this.defineComparison(scope, not, "toBe", actual, true, require, same);
        this.defineComparison(scope, not, "toContain", actual, true, require, contains);
        ScriptableObject.putProperty(matcher, "not", not);

        return matcher;
    }

    private void defineComparison(@NotNull Scriptable scope, @NotNull Scriptable owner, @NotNull String name, @Nullable Object actual,
                                  boolean negated, boolean require, @NotNull Comparison comparison) {
        define(scope, owner, name, 1, (cx, s, self, arguments) -> {
            this.requireStep(require ? "t.require" : "t.expect");

            Object expected = arguments.length > 0 ? arguments[0] : Undefined.instance;
            boolean ok = comparison.holds(actual, expected) != negated;
            int line = this.line();

            this.expects.add(RunnerMain.ordered("line", line, "expect", this.source(line), "ok", ok, "actual", toJava(actual), "frame",
                                                this.host().frame()));

            if (!ok && require) {
                this.ended = true;
                throw Context.reportRuntimeError("t.require failed: " + this.source(line));
            }

            return ok;
        });
    }

    private static boolean contains(@Nullable Object actual, @Nullable Object expected) {
        String part = Context.toString(expected);

        if (actual instanceof NativeArray array) {
            for (Object item : array) {
                if (Context.toString(item).contains(part)) {
                    return true;
                }
            }

            return false;
        }

        return actual instanceof CharSequence sequence && sequence.toString().contains(part);
    }

    private @NotNull Scriptable token(@NotNull Context context, @NotNull Token token) {
        this.requireStep(token.call());

        Scriptable object = context.newObject(Objects.requireNonNull(this.global));

        ScriptableObject.putProperty(object, "step", token.call());
        this.tokens.put(object, token);

        return object;
    }

    private void requireStep(@NotNull String call) {
        if (!this.stepping) {
            throw Context.reportRuntimeError(call + " works only inside test(t) and the condition of t.until, not in a hook.");
        }
    }

    private @NotNull Host host() {
        Host current = this.host;

        if (current == null || !this.stepping) {
            throw Context.reportRuntimeError("t works only inside test(t) and the condition of t.until, not in a hook.");
        }

        return current;
    }

    /**
     * @return the line of the test file the running call is on
     */
    private int line() {
        // An error made for the running call takes its position from the script stack.
        return Context.reportRuntimeError("position").lineNumber();
    }

    private @NotNull String source(int line) {
        if (line < 1 || line > this.lines.size()) {
            return "";
        }

        String source = this.lines.get(line - 1).strip();

        return source.length() > MAXIMUM_SOURCE_CHARS ? source.substring(0, MAXIMUM_SOURCE_CHARS) + "…" : source;
    }

    private static void define(@NotNull Scriptable scope, @NotNull Scriptable owner, @NotNull String name, int length, @NotNull Callable body) {
        ScriptableObject.putProperty(owner, name, new LambdaFunction(scope, name, length, body));
    }

    private static int integer(@Nullable Object @NotNull [] arguments, int index, @NotNull String usage, int minimum, int maximum,
                               @Nullable Integer fallback) {
        if ((index >= arguments.length || arguments[index] instanceof Undefined) && fallback != null) {
            return fallback;
        }

        return (int) number(arguments, index, usage, minimum, maximum);
    }

    private static long number(@Nullable Object @NotNull [] arguments, int index, @NotNull String usage, long minimum, long maximum) {
        if (index >= arguments.length || !(arguments[index] instanceof Number value)) {
            throw Context.reportRuntimeError(usage + ": argument " + (index + 1) + " is not a number.");
        }

        double number = value.doubleValue();

        if (number != Math.rint(number) || number < minimum || number > maximum) {
            throw Context.reportRuntimeError(usage + ": argument " + (index + 1) + " is not a whole number in " + minimum + ".." + maximum + ".");
        }

        return (long) number;
    }

    private static int key(@Nullable Object @NotNull [] arguments, @NotNull String usage) {
        if (arguments.length == 0 || !(arguments[0] instanceof CharSequence name)) {
            throw Context.reportRuntimeError(usage + ": name is not a string.");
        }

        try {
            return MtestScript.parseKey(name.toString(), usage);
        } catch (MtestScript.LineException exception) {
            throw Context.reportRuntimeError(usage + ": unknown key " + name + " (A-Z, 0-9, F1-F12, KP_0-KP_9, SPACE, ENTER, ESCAPE, TAB, " +
                                             "BACKSPACE, SHIFT, CTRL, ALT, LEFT, UP, RIGHT, DOWN, INSERT, DELETE, HOME, END, PAGEUP, PAGEDOWN).");
        }
    }

    private static int button(@Nullable Object @NotNull [] arguments, int index, @NotNull String usage) {
        if (index >= arguments.length || arguments[index] instanceof Undefined) {
            return MtestScript.LEFT_BUTTON;
        }

        return switch (Context.toString(arguments[index]).toLowerCase(Locale.ROOT)) {
            case "left" -> MtestScript.LEFT_BUTTON;
            case "right" -> MtestScript.RIGHT_BUTTON;
            case "middle" -> MtestScript.MIDDLE_BUTTON;
            default -> throw Context.reportRuntimeError(usage + ": button is 'left', 'right' or 'middle'.");
        };
    }

    /**
     * @return a script value as a report value
     */
    static @Nullable Object toJava(@Nullable Object value) {
        if (value == null || value instanceof Undefined) {
            return null;
        }

        if (value instanceof Boolean || value instanceof CharSequence) {
            return value instanceof CharSequence sequence ? sequence.toString() : value;
        }

        if (value instanceof Number number) {
            double real = number.doubleValue();

            return real == Math.rint(real) && Math.abs(real) < 1e15 ? (Object) (long) real : (Object) real;
        }

        if (value instanceof NativeArray array) {
            List<Object> items = new ArrayList<>();

            for (Object item : array) {
                if (items.size() >= MAXIMUM_ACTUAL_ITEMS) {
                    break;
                }

                items.add(toJava(item));
            }

            return items;
        }

        return Context.toString(value);
    }

    private static @NotNull String describe(@Nullable Object value) {
        Object java = toJava(value);

        return "yielded " + (java == null ? "undefined" : java.toString());
    }
}
