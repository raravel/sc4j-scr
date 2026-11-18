package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A parsed map test script ({@code .mtest}): one step per line, blank lines and lines whose first non-blank character is {@code #} ignored.
 * <ul>
 * <li>{@code wait N}: N game frames pass;</li>
 * <li>{@code key NAME [FRAMES]}: the key is held for FRAMES game frames (default {@value #DEFAULT_HOLD_FRAMES}), then released;</li>
 * <li>{@code keydown NAME}, {@code keyup NAME}: only the press or only the release;</li>
 * <li>{@code click X,Y [left|right|middle] [FRAMES]}: the button is held at the map pixel X,Y for FRAMES game frames, then released;</li>
 * <li>{@code press X,Y [button]}, {@code release [button]}: only the press at a map pixel, or only the release;</li>
 * <li>{@code view X,Y}: the 640x480 game view is centred on the map pixel X,Y, clamped to the map as the client clamps its camera; it
 * clicks nothing, selects nothing and lets no frame pass, and the pointer keeps its place on the screen; like {@code click} it takes the camera
 * over from a trigger Center View that holds it;</li>
 * <li>{@code expect deaths P<1-12> <UNIT 0-227> OP VALUE}, {@code expect memory 0x<ADDRESS> OP VALUE},
 * {@code expect text [not] contains "TEXT"}, {@code expect units OP VALUE}: a check of the state reached so far;</li>
 * <li>{@code shot [NAME]}: a picture of what the player sees at that point, without advancing the game; NAME is
 * {@code [A-Za-z0-9_-]{1,40}}, unique in the script, {@code line<N>} by default, and a script holds at most {@value #MAXIMUM_SHOTS}.</li>
 * </ul>
 * A line that cannot be read is a {@link Problem}; every problem of a script is reported, and a script with problems is not run.
 */
public final class MtestScript {
    /**
     * The hold time of {@code key} and {@code click} without a frame count.
     */
    public static final int DEFAULT_HOLD_FRAMES = 8;
    /**
     * The largest {@code wait}.
     */
    public static final int MAXIMUM_WAIT_FRAMES = 100_000;
    /**
     * The largest hold time.
     */
    public static final int MAXIMUM_HOLD_FRAMES = 10_000;
    /**
     * The largest map pixel coordinate (a 256-tile side).
     */
    public static final int MAXIMUM_COORDINATE = 256 * 32 - 1;
    /**
     * The most {@code shot} lines of a script.
     */
    public static final int MAXIMUM_SHOTS = 16;

    static final @NotNull String WAIT_USAGE = "wait N";
    static final @NotNull String KEY_USAGE = "key NAME [FRAMES]";
    static final @NotNull String KEY_DOWN_USAGE = "keydown NAME";
    static final @NotNull String KEY_UP_USAGE = "keyup NAME";
    static final @NotNull String CLICK_USAGE = "click X,Y [left|right|middle] [FRAMES]";
    static final @NotNull String PRESS_USAGE = "press X,Y [left|right|middle]";
    static final @NotNull String RELEASE_USAGE = "release [left|right|middle]";
    static final @NotNull String VIEW_USAGE = "view X,Y";
    static final @NotNull String EXPECT_DEATHS_USAGE = "expect deaths P<1-12> <UNIT 0-227> <OP> <VALUE>";
    static final @NotNull String EXPECT_MEMORY_USAGE = "expect memory 0x<ADDRESS> <OP> <VALUE>";
    static final @NotNull String EXPECT_TEXT_USAGE = "expect text [not] contains \"TEXT\"";
    static final @NotNull String EXPECT_UNITS_USAGE = "expect units <OP> <VALUE>";
    static final @NotNull String EXPECT_USAGE = EXPECT_DEATHS_USAGE + " | " + EXPECT_MEMORY_USAGE + " | " + EXPECT_TEXT_USAGE + " | " +
                                               EXPECT_UNITS_USAGE;
    static final @NotNull String SHOT_USAGE = "shot [NAME]  (NAME: A-Z a-z 0-9 _ -, at most 40)";
    static final @NotNull String COMMANDS_USAGE = "wait, key, keydown, keyup, click, press, release, view, expect, shot";

    /**
     * The mouse button flags of the classic interface (0x6CDDC0).
     */
    static final int LEFT_BUTTON = 0x02;
    static final int RIGHT_BUTTON = 0x08;
    static final int MIDDLE_BUTTON = 0x20;

    private static final @NotNull Pattern SHOT_NAME = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final @NotNull List<String> OPERATORS = List.of("==", "!=", "<", "<=", ">", ">=");
    private static final @NotNull Map<String, Integer> NAMED_KEYS = Map.ofEntries(
        Map.entry("SPACE", 0x20), Map.entry("ENTER", 0x0D), Map.entry("ESCAPE", 0x1B), Map.entry("TAB", 0x09),
        Map.entry("BACKSPACE", 0x08), Map.entry("SHIFT", 0x10), Map.entry("CTRL", 0x11), Map.entry("ALT", 0x12),
        Map.entry("LEFT", 0x25), Map.entry("UP", 0x26), Map.entry("RIGHT", 0x27), Map.entry("DOWN", 0x28),
        Map.entry("INSERT", 0x2D), Map.entry("DELETE", 0x2E), Map.entry("HOME", 0x24), Map.entry("END", 0x23),
        Map.entry("PAGEUP", 0x21), Map.entry("PAGEDOWN", 0x22));

    /**
     * One step of a script.
     */
    public sealed interface Step permits Wait, KeyStep, MouseStep, View, Expect, Shot {
        /**
         * @return the 1-based script line
         */
        int line();
    }

    /**
     * {@code wait N}.
     *
     * @param line   the script line
     * @param frames the game frames that pass
     */
    public record Wait(int line, int frames) implements Step {
    }

    /**
     * The kinds of a key step.
     */
    public enum KeyAction {
        HOLD, DOWN, UP
    }

    /**
     * {@code key}, {@code keydown} or {@code keyup}.
     *
     * @param line       the script line
     * @param action     the kind of step
     * @param virtualKey the Windows virtual key code, the index of the classic key state table
     * @param name       the key name as written
     * @param frames     the hold time of {@link KeyAction#HOLD}, else 0
     */
    public record KeyStep(int line, @NotNull KeyAction action, int virtualKey, @NotNull String name, int frames) implements Step {
    }

    /**
     * The kinds of a mouse step.
     */
    public enum MouseAction {
        CLICK, PRESS, RELEASE
    }

    /**
     * {@code click}, {@code press} or {@code release}.
     *
     * @param line   the script line
     * @param action the kind of step
     * @param x      the map pixel X (unused by {@link MouseAction#RELEASE})
     * @param y      the map pixel Y (unused by {@link MouseAction#RELEASE})
     * @param button the classic button flag
     * @param frames the hold time of {@link MouseAction#CLICK}, else 0
     */
    public record MouseStep(int line, @NotNull MouseAction action, int x, int y, int button, int frames) implements Step {
    }

    /**
     * {@code view X,Y}: the game view is centred on a map pixel without a click and without a frame.
     *
     * @param line the script line
     * @param x    the map pixel X the view centres on
     * @param y    the map pixel Y the view centres on
     */
    public record View(int line, int x, int y) implements Step {
    }

    /**
     * What an {@code expect} reads.
     */
    public enum Subject {
        DEATHS, MEMORY, TEXT, UNITS
    }

    /**
     * {@code expect}.
     *
     * @param line     the script line
     * @param source   the line as written, trimmed
     * @param subject  what is read
     * @param player   the 0-based player of {@link Subject#DEATHS}
     * @param unit     the unit of {@link Subject#DEATHS}
     * @param address  the address of {@link Subject#MEMORY}
     * @param operator the comparison, {@code contains} or {@code not contains} for {@link Subject#TEXT}
     * @param value    the expected number
     * @param text     the expected text of {@link Subject#TEXT}, else {@code null}
     */
    public record Expect(int line, @NotNull String source, @NotNull Subject subject, int player, int unit, int address,
                         @NotNull String operator, long value, @Nullable String text) implements Step {
        /**
         * Compares a read number with the expected one.
         *
         * @param actual the read number
         * @return whether the comparison holds
         */
        public boolean holds(long actual) {
            return switch (this.operator) {
                case "==" -> actual == this.value;
                case "!=" -> actual != this.value;
                case "<" -> actual < this.value;
                case "<=" -> actual <= this.value;
                case ">" -> actual > this.value;
                case ">=" -> actual >= this.value;
                default -> throw new IllegalStateException("Unknown operator: " + this.operator);
            };
        }

        /**
         * Checks the message lines against the expected text.
         *
         * @param lines the message lines read
         * @return whether some line contains the text ({@code contains}) or none does ({@code not contains})
         */
        public boolean holdsText(@NotNull java.util.List<String> lines) {
            boolean found = this.text != null && lines.stream().anyMatch(line -> line.contains(this.text));

            return this.operator.equals("not contains") != found;
        }
    }

    /**
     * {@code shot}.
     *
     * @param line the script line
     * @param name the picture name, the file name without {@code .png}
     */
    public record Shot(int line, @NotNull String name) implements Step {
    }

    /**
     * A line that cannot be read.
     *
     * @param line   the script line
     * @param code   the stable problem code
     * @param detail the offending text
     * @param usage  the accepted form of the command, or the command list
     */
    public record Problem(int line, @NotNull String code, @NotNull String detail, @NotNull String usage) {
    }

    private final @NotNull List<Step> steps;
    private final @NotNull List<Problem> problems;

    private MtestScript(@NotNull List<Step> steps, @NotNull List<Problem> problems) {
        this.steps    = List.copyOf(steps);
        this.problems = List.copyOf(problems);
    }

    /**
     * @return the steps in script order
     */
    public @NotNull List<Step> getSteps() {
        return this.steps;
    }

    /**
     * @return the unreadable lines, empty when the script can run
     */
    public @NotNull List<Problem> getProblems() {
        return this.problems;
    }

    /**
     * @return the game frames the steps let pass
     */
    public long getPlannedFrames() {
        long frames = 0;

        for (Step step : this.steps) {
            frames += switch (step) {
                case Wait wait -> wait.frames();
                case KeyStep key -> key.frames();
                case MouseStep mouse -> mouse.frames();
                case View ignored -> 0;
                case Expect ignored -> 0;
                case Shot ignored -> 0;
            };
        }

        return frames;
    }

    /**
     * @return the {@code shot} steps in script order
     */
    public @NotNull List<Shot> getShots() {
        return this.steps.stream().filter(Shot.class::isInstance).map(Shot.class::cast).toList();
    }

    /**
     * Reads a script.
     *
     * @param lines the script lines
     * @return the script with its steps and every problem
     */
    public static @NotNull MtestScript parse(@NotNull List<String> lines) {
        List<Step> steps = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        Set<String> shotNames = new HashSet<>();
        int shots = 0;

        for (int index = 0; index < lines.size(); index++) {
            int line = index + 1;
            String text = stripByteOrderMark(lines.get(index), index).strip();

            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }

            try {
                Step step = parseStep(line, text);

                if (step instanceof Shot shot) {
                    if (++shots > MAXIMUM_SHOTS) {
                        throw new LineException("too_many_shots", text + " (at most " + MAXIMUM_SHOTS + ")", SHOT_USAGE);
                    }

                    if (!shotNames.add(shot.name().toLowerCase(Locale.ROOT))) {
                        throw new LineException("duplicate_shot", shot.name(), SHOT_USAGE);
                    }
                }

                steps.add(step);
            } catch (LineException exception) {
                problems.add(new Problem(line, exception.code, exception.detail, exception.usage));
            }
        }

        return new MtestScript(steps, problems);
    }

    private static @NotNull String stripByteOrderMark(@NotNull String text, int index) {
        return index == 0 && text.startsWith("﻿") ? text.substring(1) : text;
    }

    private static @NotNull Step parseStep(int line, @NotNull String text) throws LineException {
        if (text.startsWith("@")) {
            throw new LineException("screenshot_phase", text, COMMANDS_USAGE);
        }

        String[] words = text.split("\\s+");
        String command = words[0].toLowerCase(Locale.ROOT);

        return switch (command) {
            case "wait" -> {
                requireCount(words, 2, 2, WAIT_USAGE);
                yield new Wait(line, parseInteger(words[1], 1, MAXIMUM_WAIT_FRAMES, WAIT_USAGE));
            }
            case "key" -> {
                requireCount(words, 2, 3, KEY_USAGE);
                int frames = words.length > 2 ? parseInteger(words[2], 1, MAXIMUM_HOLD_FRAMES, KEY_USAGE) : DEFAULT_HOLD_FRAMES;
                yield new KeyStep(line, KeyAction.HOLD, parseKey(words[1], KEY_USAGE), words[1], frames);
            }
            case "keydown" -> {
                requireCount(words, 2, 2, KEY_DOWN_USAGE);
                yield new KeyStep(line, KeyAction.DOWN, parseKey(words[1], KEY_DOWN_USAGE), words[1], 0);
            }
            case "keyup" -> {
                requireCount(words, 2, 2, KEY_UP_USAGE);
                yield new KeyStep(line, KeyAction.UP, parseKey(words[1], KEY_UP_USAGE), words[1], 0);
            }
            case "click" -> {
                requireCount(words, 2, 4, CLICK_USAGE);
                int[] point = parsePoint(words[1], CLICK_USAGE);
                int button = LEFT_BUTTON;
                int frames = DEFAULT_HOLD_FRAMES;

                if (words.length == 3) {
                    if (isButton(words[2])) {
                        button = parseButton(words[2], CLICK_USAGE);
                    } else {
                        frames = parseInteger(words[2], 1, MAXIMUM_HOLD_FRAMES, CLICK_USAGE);
                    }
                } else if (words.length == 4) {
                    button = parseButton(words[2], CLICK_USAGE);
                    frames = parseInteger(words[3], 1, MAXIMUM_HOLD_FRAMES, CLICK_USAGE);
                }

                yield new MouseStep(line, MouseAction.CLICK, point[0], point[1], button, frames);
            }
            case "press" -> {
                requireCount(words, 2, 3, PRESS_USAGE);
                int[] point = parsePoint(words[1], PRESS_USAGE);
                int button = words.length > 2 ? parseButton(words[2], PRESS_USAGE) : LEFT_BUTTON;
                yield new MouseStep(line, MouseAction.PRESS, point[0], point[1], button, 0);
            }
            case "release" -> {
                requireCount(words, 1, 2, RELEASE_USAGE);
                int button = words.length > 1 ? parseButton(words[1], RELEASE_USAGE) : LEFT_BUTTON;
                yield new MouseStep(line, MouseAction.RELEASE, 0, 0, button, 0);
            }
            case "view" -> {
                requireCount(words, 2, 2, VIEW_USAGE);
                int[] point = parsePoint(words[1], VIEW_USAGE);
                yield new View(line, point[0], point[1]);
            }
            case "expect" -> parseExpect(line, text, words);
            case "shot" -> {
                requireCount(words, 1, 2, SHOT_USAGE);

                if (words.length > 1 && !SHOT_NAME.matcher(words[1]).matches()) {
                    throw new LineException("bad_name", words[1], SHOT_USAGE);
                }

                yield new Shot(line, words.length > 1 ? words[1] : "line" + line);
            }
            case "scroll", "type", "move", "pause", "keyrepeat", "exit" -> throw new LineException("unsupported_command", words[0], COMMANDS_USAGE);
            default -> throw new LineException("unknown_command", words[0], COMMANDS_USAGE);
        };
    }

    private static @NotNull Expect parseExpect(int line, @NotNull String text, String @NotNull [] words) throws LineException {
        if (words.length < 2) {
            throw new LineException("missing_argument", text, EXPECT_USAGE);
        }

        String subject = words[1].toLowerCase(Locale.ROOT);

        switch (subject) {
            case "deaths" -> {
                requireCount(words, 6, 6, EXPECT_DEATHS_USAGE);
                String player = words[2];

                if (player.length() < 2 || Character.toUpperCase(player.charAt(0)) != 'P') {
                    throw new LineException("bad_player", player, EXPECT_DEATHS_USAGE);
                }

                int playerNumber = parseInteger(player.substring(1), 1, 12, EXPECT_DEATHS_USAGE);
                int unit = parseInteger(words[3], 0, 227, EXPECT_DEATHS_USAGE);
                String operator = parseOperator(words[4], EXPECT_DEATHS_USAGE);
                long value = parseValue(words[5], Integer.MIN_VALUE, 0xFFFFFFFFL, EXPECT_DEATHS_USAGE);

                return new Expect(line, text, Subject.DEATHS, playerNumber - 1, unit, 0, operator, value, null);
            }
            case "memory" -> {
                requireCount(words, 5, 5, EXPECT_MEMORY_USAGE);
                String addressText = words[2];

                if (!addressText.toLowerCase(Locale.ROOT).startsWith("0x")) {
                    throw new LineException("bad_address", addressText, EXPECT_MEMORY_USAGE);
                }

                long address = parseValue(addressText, 0, 0xFFFFFFFFL, EXPECT_MEMORY_USAGE);

                if ((address & 3) != 0) {
                    throw new LineException("unaligned_address", addressText, EXPECT_MEMORY_USAGE);
                }

                String operator = parseOperator(words[3], EXPECT_MEMORY_USAGE);
                long value = parseValue(words[4], 0, 0xFFFFFFFFL, EXPECT_MEMORY_USAGE);

                return new Expect(line, text, Subject.MEMORY, 0, 0, (int) address, operator, value, null);
            }
            case "units" -> {
                requireCount(words, 4, 4, EXPECT_UNITS_USAGE);
                String operator = parseOperator(words[2], EXPECT_UNITS_USAGE);
                long value = parseValue(words[3], 0, 1700, EXPECT_UNITS_USAGE);

                return new Expect(line, text, Subject.UNITS, 0, 0, 0, operator, value, null);
            }
            case "text" -> {
                boolean negated = words.length >= 3 && words[2].equalsIgnoreCase("not");
                int operatorWord = negated ? 3 : 2;

                if (words.length < operatorWord + 2) {
                    throw new LineException(words.length <= operatorWord ? "missing_argument" : "bad_text", text, EXPECT_TEXT_USAGE);
                }

                if (!words[operatorWord].equalsIgnoreCase("contains")) {
                    throw new LineException("bad_operator", text, EXPECT_TEXT_USAGE);
                }

                int start = text.indexOf('"');

                if (start < 0) {
                    throw new LineException("bad_text", text, EXPECT_TEXT_USAGE);
                }

                StringBuilder expected = new StringBuilder();
                int index = start + 1;
                boolean closed = false;

                while (index < text.length()) {
                    char character = text.charAt(index++);

                    if (character == '\\' && index < text.length()) {
                        expected.append(text.charAt(index++));
                    } else if (character == '"') {
                        closed = true;
                        break;
                    } else {
                        expected.append(character);
                    }
                }

                if (!closed || expected.isEmpty()) {
                    throw new LineException("bad_text", text, EXPECT_TEXT_USAGE);
                }

                if (!text.substring(index).isBlank()) {
                    throw new LineException("extra_argument", text.substring(index).strip(), EXPECT_TEXT_USAGE);
                }

                return new Expect(line, text, Subject.TEXT, 0, 0, 0, negated ? "not contains" : "contains", 0, expected.toString());
            }
            default -> throw new LineException("unknown_subject", words[1], EXPECT_USAGE);
        }
    }

    private static void requireCount(String @NotNull [] words, int minimum, int maximum, @NotNull String usage) throws LineException {
        if (words.length < minimum) {
            throw new LineException("missing_argument", String.join(" ", words), usage);
        }

        if (words.length > maximum) {
            throw new LineException("extra_argument", words[maximum], usage);
        }
    }

    private static int parseInteger(@NotNull String text, int minimum, int maximum, @NotNull String usage) throws LineException {
        return (int) parseValue(text, minimum, maximum, usage);
    }

    private static long parseValue(@NotNull String text, long minimum, long maximum, @NotNull String usage) throws LineException {
        long value;

        try {
            value = text.toLowerCase(Locale.ROOT).startsWith("0x") ? Long.parseLong(text.substring(2), 16) : Long.parseLong(text);
        } catch (NumberFormatException exception) {
            throw new LineException("bad_number", text, usage);
        }

        if (value < minimum || value > maximum) {
            throw new LineException("out_of_range", text + " (" + minimum + ".." + maximum + ")", usage);
        }

        return value;
    }

    private static @NotNull String parseOperator(@NotNull String text, @NotNull String usage) throws LineException {
        if (!OPERATORS.contains(text)) {
            throw new LineException("bad_operator", text, usage);
        }

        return text;
    }

    private static int[] parsePoint(@NotNull String text, @NotNull String usage) throws LineException {
        String[] parts = text.split(",", -1);

        if (parts.length != 2) {
            throw new LineException("bad_point", text, usage);
        }

        return new int[]{parseInteger(parts[0], 0, MAXIMUM_COORDINATE, usage), parseInteger(parts[1], 0, MAXIMUM_COORDINATE, usage)};
    }

    private static boolean isButton(@NotNull String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "left", "right", "middle" -> true;
            default -> false;
        };
    }

    private static int parseButton(@NotNull String text, @NotNull String usage) throws LineException {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "left" -> LEFT_BUTTON;
            case "right" -> RIGHT_BUTTON;
            case "middle" -> MIDDLE_BUTTON;
            default -> throw new LineException("unknown_button", text, usage);
        };
    }

    /**
     * Reads a key name: A-Z, 0-9, F1-F12, KP_0-KP_9 or one of the named keys.
     *
     * @param text  the key name
     * @param usage the usage of the command
     * @return the virtual key code
     * @throws LineException if the name is unknown
     */
    static int parseKey(@NotNull String text, @NotNull String usage) throws LineException {
        String name = text.toUpperCase(Locale.ROOT);

        if (name.length() == 1) {
            char character = name.charAt(0);

            if (character >= 'A' && character <= 'Z' || character >= '0' && character <= '9') {
                return character;
            }
        }

        if (name.matches("F([1-9]|1[0-2])")) {
            return 0x70 + Integer.parseInt(name.substring(1)) - 1;
        }

        if (name.matches("KP_[0-9]")) {
            return 0x60 + name.charAt(3) - '0';
        }

        Integer named = NAMED_KEYS.get(name);

        if (named == null) {
            throw new LineException("unknown_key", text, usage);
        }

        return named;
    }

    /**
     * A line that cannot be read.
     */
    static final class LineException extends Exception {
        private static final long serialVersionUID = 1L;

        private final @NotNull String code;
        private final @NotNull String detail;
        private final @NotNull String usage;

        LineException(@NotNull String code, @NotNull String detail, @NotNull String usage) {
            super(code + ": " + detail, null, false, false);
            this.code   = code;
            this.detail = detail;
            this.usage  = usage;
        }
    }
}
