package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.base.json.JsonWriterUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents the source map of a protected map ({@link ScriptProtector}): for each script, the original line of each protected line and the
 * names the protector gave. The tool writes it next to the packed map ({@link #resolveFile}); the map does not hold it.
 * <p>
 * The file is UTF-8 JSON:
 * {@snippet lang = "json":
 * {
 *   "version": 1,
 *   "scripts": {
 *     "main.js": {
 *       "lines": [3, 4, 6],
 *       "names": [{"name": "a", "original": "count", "line": 4}]
 *     }
 *   }
 * }
 *}
 * {@code lines[i]} is the original line of the protected line {@code i + 1}. A name entry holds a protected name, the original name and the
 * original line of its first use; one protected name stands for different original names in different functions.
 */
public final class ScriptSourceMap {
    /**
     * The suffix that {@link #resolveFile} adds to the file name of the packed map.
     */
    public static final @NotNull String FILE_SUFFIX = ".sourcemap.json";
    /**
     * The format version of the file.
     */
    public static final int FORMAT_VERSION = 1;
    /**
     * The line {@link #findOriginalLine} returns for a line the map does not know.
     */
    public static final int NO_LINE = 0;

    private static final @NotNull String VERSION_FIELD = "version";
    private static final @NotNull String SCRIPTS_FIELD = "scripts";
    private static final @NotNull String LINES_FIELD = "lines";
    private static final @NotNull String NAMES_FIELD = "names";
    private static final @NotNull String NAME_FIELD = "name";
    private static final @NotNull String ORIGINAL_FIELD = "original";
    private static final @NotNull String LINE_FIELD = "line";
    /**
     * The characters that may come before a script name in a location: a location inside a longer path or name is not one.
     */
    private static final @NotNull String LOCATION_PREFIX = "(?<![\\p{L}\\p{N}_$./\\\\-])";
    /**
     * The line number of a location: up to nine digits, so that it fits an {@code int}.
     */
    private static final @NotNull String LOCATION_LINE = ":(\\d{1,9})";

    /**
     * A name the protector gave: the protected name, the original name and the original line of its first use.
     */
    public static final class Rename {
        private final @NotNull String name;
        private final @NotNull String original;
        private final int line;

        /**
         * Creates a {@link Rename} instance.
         *
         * @param name     the protected name
         * @param original the original name
         * @param line     the original line of the first use
         */
        public Rename(@NotNull String name, @NotNull String original, int line) {
            this.name     = name;
            this.original = original;
            this.line     = line;
        }

        /**
         * @return the protected name
         */
        public @NotNull String getName() {
            return this.name;
        }

        /**
         * @return the original name
         */
        public @NotNull String getOriginal() {
            return this.original;
        }

        /**
         * @return the original line of the first use
         */
        public int getLine() {
            return this.line;
        }
    }

    /**
     * The map of one script: the original line of each protected line and the names.
     */
    private static final class ScriptLines {
        private final int @NotNull [] lines;
        private final @NotNull List<Rename> renames;

        private ScriptLines(int @NotNull [] lines, @NotNull List<Rename> renames) {
            this.lines   = lines;
            this.renames = renames;
        }
    }

    /**
     * Finds the file of the source map of a packed map: the packed map's file name with {@value #FILE_SUFFIX} in the same folder.
     *
     * @param packedMap the packed map
     * @return the source map file
     */
    public static @NotNull Path resolveFile(@NotNull Path packedMap) {
        return packedMap.resolveSibling(packedMap.getFileName() + FILE_SUFFIX);
    }

    /**
     * Reads a source map file.
     *
     * @param file the file
     * @return the read {@link ScriptSourceMap} instance
     * @throws IOException if the file cannot be read, is not JSON of the format or has another version than {@value #FORMAT_VERSION}
     */
    public static @NotNull ScriptSourceMap read(@NotNull Path file) throws IOException {
        Object value = JsonReader.parse(Files.readAllBytes(file));
        ScriptSourceMap sourceMap = new ScriptSourceMap();

        if (!(value instanceof Map<?, ?> fields) || !(fields.get(VERSION_FIELD) instanceof Long version) ||
            !(fields.get(SCRIPTS_FIELD) instanceof Map<?, ?> scripts)) {
            throw Exceptions.newIOException("The source map {0} has no version and scripts.", file);
        }

        if (version != FORMAT_VERSION) {
            throw Exceptions.newIOException("The source map {0} has the version {1}; the tool reads the version {2}.", file, version, FORMAT_VERSION);
        }

        for (Map.Entry<?, ?> script : scripts.entrySet()) {
            if (!(script.getValue() instanceof Map<?, ?> scriptFields) || !(scriptFields.get(LINES_FIELD) instanceof List<?> lineValues) ||
                !(scriptFields.get(NAMES_FIELD) instanceof List<?> nameValues)) {
                throw Exceptions.newIOException("The source map {0} has no lines and names of {1}.", file, script.getKey());
            }

            int[] lines = new int[lineValues.size()];
            List<Rename> renames = new ArrayList<>();

            for (int index = 0; index < lines.length; index++) {
                lines[index] = readLine(lineValues.get(index), file);
            }

            for (Object nameValue : nameValues) {
                if (!(nameValue instanceof Map<?, ?> nameFields) || !(nameFields.get(NAME_FIELD) instanceof String name) ||
                    !(nameFields.get(ORIGINAL_FIELD) instanceof String original)) {
                    throw Exceptions.newIOException("The source map {0} has a name of {1} without name and original.", file, script.getKey());
                }

                renames.add(new Rename(name, original, readLine(nameFields.get(LINE_FIELD), file)));
            }

            sourceMap.addScript((String) script.getKey(), lines, renames);
        }

        return sourceMap;
    }

    private static int readLine(@Nullable Object value, @NotNull Path file) throws IOException {
        if (!(value instanceof Long line) || line < 1 || line > Integer.MAX_VALUE) {
            throw Exceptions.newIOException("The source map {0} has the line {1}, which is not a line number.", file, value);
        }

        return line.intValue();
    }


    private final @NotNull Map<String, ScriptLines> scripts;

    /**
     * Creates a {@link ScriptSourceMap} instance without scripts.
     */
    public ScriptSourceMap() {
        this.scripts = new LinkedHashMap<>();
    }

    /**
     * Adds the map of a script, or replaces it.
     *
     * @param scriptName the script name
     * @param lines      the original line of each protected line, kept by the map
     * @param renames    the names the protector gave
     */
    public void addScript(@NotNull String scriptName, int @NotNull [] lines, @NotNull List<Rename> renames) {
        this.scripts.put(scriptName, new ScriptLines(lines, List.copyOf(renames)));
    }

    /**
     * Finds the original line of a line of a protected script.
     *
     * @param scriptName the script name
     * @param line       the line of the protected script, from {@code 1}
     * @return the original line, or {@value #NO_LINE} if the map has no such script or line
     */
    public int findOriginalLine(@NotNull String scriptName, int line) {
        ScriptLines script = this.scripts.get(scriptName);

        if (script == null || line < 1 || line > script.lines.length) {
            return NO_LINE;
        }

        return script.lines[line - 1];
    }

    /**
     * Returns the names the protector gave in a script.
     *
     * @param scriptName the script name
     * @return the names, empty if the map has no such script
     */
    public @NotNull List<Rename> getRenames(@NotNull String scriptName) {
        ScriptLines script = this.scripts.get(scriptName);

        return script == null ? List.of() : script.renames;
    }

    /**
     * Replaces in a text, such as a log, each location {@code <script>:<line>} of a script of the map with the original line; a location
     * of a line the map does not know stays.
     *
     * @param text the text
     * @return the text with the original lines
     */
    public @NotNull String translate(@NotNull String text) {
        if (this.scripts.isEmpty()) {
            return text;
        }

        List<String> names = new ArrayList<>(this.scripts.keySet());
        StringBuilder alternatives = new StringBuilder();

        // The longest name first, so that a name that ends another one does not cut the location.
        names.sort(Comparator.comparingInt(String::length).reversed());

        for (String name : names) {
            alternatives.append(alternatives.isEmpty() ? "" : "|").append(Pattern.quote(name));
        }

        Matcher matcher = Pattern.compile(LOCATION_PREFIX + "(" + alternatives + ")" + LOCATION_LINE).matcher(text);
        StringBuilder translated = new StringBuilder();

        while (matcher.find()) {
            int originalLine = this.findOriginalLine(matcher.group(1), Integer.parseInt(matcher.group(2)));
            String location = originalLine == NO_LINE ? matcher.group() : matcher.group(1) + ":" + originalLine;

            matcher.appendReplacement(translated, Matcher.quoteReplacement(location));
        }

        return matcher.appendTail(translated).toString();
    }

    /**
     * Writes the map as a source map file.
     *
     * @param file the file
     * @throws IOException if the file cannot be written
     */
    public void write(@NotNull Path file) throws IOException {
        Map<String, Object> scriptValues = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();

        for (Map.Entry<String, ScriptLines> script : this.scripts.entrySet()) {
            List<Object> lines = new ArrayList<>();
            List<Object> renames = new ArrayList<>();
            Map<String, Object> scriptFields = new LinkedHashMap<>();

            for (int line : script.getValue().lines) {
                lines.add(line);
            }

            for (Rename rename : script.getValue().renames) {
                Map<String, Object> nameFields = new LinkedHashMap<>();

                nameFields.put(NAME_FIELD, rename.getName());
                nameFields.put(ORIGINAL_FIELD, rename.getOriginal());
                nameFields.put(LINE_FIELD, rename.getLine());
                renames.add(nameFields);
            }

            scriptFields.put(LINES_FIELD, lines);
            scriptFields.put(NAMES_FIELD, renames);
            scriptValues.put(script.getKey(), scriptFields);
        }

        values.put(VERSION_FIELD, FORMAT_VERSION);
        values.put(SCRIPTS_FIELD, scriptValues);

        Files.writeString(file, JsonWriterUtils.write(values), StandardCharsets.UTF_8);
    }
}
