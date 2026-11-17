package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Writes the runner report as JSON from maps, lists, strings, numbers and booleans.
 */
final class Json {
    private Json() {
    }

    /**
     * Writes a value.
     *
     * @param value a {@link Map} with string keys, a {@link List}, a {@link String}, a {@link Number}, a {@link Boolean} or {@code null}
     * @return the JSON text
     */
    static @NotNull String write(@Nullable Object value) {
        StringBuilder output = new StringBuilder();

        append(output, value);

        return output.toString();
    }

    private static void append(@NotNull StringBuilder output, @Nullable Object value) {
        switch (value) {
            case null -> output.append("null");
            case String text -> appendString(output, text);
            case Boolean flag -> output.append(flag);
            case Number number -> output.append(number);
            case Map<?, ?> map -> {
                output.append('{');
                boolean first = true;

                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        output.append(',');
                    }

                    first = false;
                    appendString(output, String.valueOf(entry.getKey()));
                    output.append(':');
                    append(output, entry.getValue());
                }

                output.append('}');
            }
            case List<?> list -> {
                output.append('[');

                for (int index = 0; index < list.size(); index++) {
                    if (index > 0) {
                        output.append(',');
                    }

                    append(output, list.get(index));
                }

                output.append(']');
            }
            default -> appendString(output, value.toString());
        }
    }

    private static void appendString(@NotNull StringBuilder output, @NotNull String text) {
        output.append('"');

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);

            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }

        output.append('"');
    }
}
