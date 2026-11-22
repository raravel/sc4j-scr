package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the JavaScript map test ({@link JsTest}) that does what a {@code .mtest} script does, line for line: comments stay comments, every
 * step becomes the {@code t} call of the same meaning, and the first {@code wait} is one frame shorter, because a JavaScript test starts
 * after the game's first frame: the steps before that wait happen one frame later, every step after it at the script's frame.
 */
final class MtestConverter {
    private MtestConverter() {
    }

    /**
     * Converts a script file.
     *
     * @param in  the {@code .mtest} file
     * @param out the {@code .test.js} file written
     * @return the problems of the script, empty if the file was written
     * @throws IOException if a file cannot be read or written
     */
    static @NotNull List<MtestScript.Problem> convert(@NotNull Path in, @NotNull Path out) throws IOException {
        List<String> lines = Files.readAllLines(in, StandardCharsets.UTF_8);
        MtestScript script = MtestScript.parse(lines);

        if (!script.getProblems().isEmpty()) {
            return script.getProblems();
        }

        Files.writeString(out, convert(in.getFileName().toString(), lines, script), StandardCharsets.UTF_8);

        return List.of();
    }

    static @NotNull String convert(@NotNull String name, @NotNull List<String> lines, @NotNull MtestScript script) {
        Map<Integer, MtestScript.Step> steps = new HashMap<>();

        for (MtestScript.Step step : script.getSteps()) {
            steps.put(step.line(), step);
        }

        StringBuilder text = new StringBuilder();
        boolean shortened = false;

        text.append("// Converted from ").append(name).append(" by eud-runner --convert-mtest.\n");
        text.append("// The test starts after the game's first frame: the first wait is one frame shorter than the script's.\n");
        text.append("function* test(t) {\n");

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).strip();

            if (index == 0 && line.startsWith("﻿")) {
                line = line.substring(1).strip();
            }

            MtestScript.Step step = steps.get(index + 1);

            if (step == null) {
                text.append(line.isEmpty() ? "" : "    //" + (line.length() > 1 ? " " + line.substring(1).strip() : "")).append('\n');
                continue;
            }

            if (!shortened && step instanceof MtestScript.Wait wait) {
                shortened = true;
                text.append(wait.frames() > 1 ? "    yield t.wait(" + (wait.frames() - 1) + ");\n" : "    // wait 1: the game's first frame\n");
                continue;
            }

            text.append("    ").append(statement(step)).append('\n');
        }

        return text.append("}\n").toString();
    }

    private static @NotNull String statement(@NotNull MtestScript.Step step) {
        return switch (step) {
            case MtestScript.Wait wait -> "yield t.wait(" + wait.frames() + ");";
            case MtestScript.KeyStep key -> switch (key.action()) {
                case HOLD -> "yield t.key(" + quote(key.name()) + ", " + key.frames() + ");";
                case DOWN -> "t.keyDown(" + quote(key.name()) + ");";
                case UP -> "t.keyUp(" + quote(key.name()) + ");";
            };
            case MtestScript.MouseStep mouse -> switch (mouse.action()) {
                case CLICK -> "yield t.click(" + mouse.x() + ", " + mouse.y() + ", " + button(mouse.button()) + ", " + mouse.frames() + ");";
                case PRESS -> "t.press(" + mouse.x() + ", " + mouse.y() + ", " + button(mouse.button()) + ");";
                case RELEASE -> "t.release(" + button(mouse.button()) + ");";
            };
            case MtestScript.View view -> "t.view(" + view.x() + ", " + view.y() + ");";
            case MtestScript.Chat chat -> "t.chat(" + quote(chat.text()) + ");";
            case MtestScript.Shot shot -> "t.shot(" + quote(shot.name()) + ");";
            case MtestScript.Expect expect -> expect(expect);
        };
    }

    private static @NotNull String expect(@NotNull MtestScript.Expect expect) {
        String read = switch (expect.subject()) {
            case DEATHS -> "t.deaths(" + (expect.player() + 1) + ", " + expect.unit() + ")";
            case MEMORY -> "t.memory(0x" + Integer.toHexString(expect.address()).toUpperCase() + ")";
            case UNITS -> "t.units()";
            case TEXT -> "t.text()";
        };

        if (expect.subject() == MtestScript.Subject.TEXT) {
            String matcher = expect.operator().equals("not contains") ? "not.toContain" : "toContain";

            return "t.expect(" + read + ")." + matcher + "(" + quote(expect.text() == null ? "" : expect.text()) + ");";
        }

        String matcher = switch (expect.operator()) {
            case "==" -> "toBe";
            case "!=" -> "not.toBe";
            case ">=" -> "atLeast";
            case "<=" -> "atMost";
            case ">" -> "above";
            case "<" -> "below";
            default -> throw new IllegalStateException("Unknown operator: " + expect.operator());
        };

        return "t.expect(" + read + ")." + matcher + "(" + expect.value() + ");";
    }

    private static @NotNull String button(int button) {
        return switch (button) {
            case MtestScript.RIGHT_BUTTON -> "'right'";
            case MtestScript.MIDDLE_BUTTON -> "'middle'";
            default -> "'left'";
        };
    }

    /**
     * @return a JavaScript string literal of the text
     */
    static @NotNull String quote(@NotNull String text) {
        StringBuilder quoted = new StringBuilder("'");

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);

            switch (character) {
                case '\'' -> quoted.append("\\'");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                default -> {
                    if (character < 0x20 || character == 0x7F || character == 0x2028 || character == 0x2029) {
                        quoted.append(String.format("\\u%04x", (int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }

        return quoted.append('\'').toString();
    }
}
