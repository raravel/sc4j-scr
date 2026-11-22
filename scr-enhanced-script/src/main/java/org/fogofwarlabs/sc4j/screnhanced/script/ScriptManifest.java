package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Map;

/**
 * Represents the manifest of a script package: a UTF-8 JSON object with the package name ({@code name}), the script that runs first
 * ({@code main}) and the script API version ({@code api}), each a nonempty string. The manifest keeps its JSON text as given, so that
 * packing and unpacking write the same bytes back.
 */
public final class ScriptManifest {
    /**
     * The file name of the manifest in a script folder.
     */
    public static final @NotNull String FILE_NAME = "manifest.json";

    private static final @NotNull String NAME_FIELD = "name";
    private static final @NotNull String MAIN_FIELD = "main";
    private static final @NotNull String API_FIELD = "api";

    /**
     * Creates a {@link ScriptManifest} instance from the JSON text of a manifest.
     *
     * @param content the UTF-8 JSON text, copied
     * @return the created {@link ScriptManifest} instance
     * @throws IOException if the text is not a JSON object or lacks one of {@code name}, {@code main} and {@code api} as a nonempty string
     */
    public static @NotNull ScriptManifest parse(byte @NotNull [] content) throws IOException {
        Object value;

        try {
            value = JsonReader.parse(content);
        } catch (IOException exception) {
            throw Exceptions.newIOException(exception, "The script manifest is not valid JSON: {0}", exception.getMessage());
        }

        if (!(value instanceof Map<?, ?> fields)) {
            throw Exceptions.newIOException("The script manifest is not a JSON object.");
        }

        return new ScriptManifest(content.clone(), requireField(fields, NAME_FIELD), requireField(fields, MAIN_FIELD), requireField(fields, API_FIELD));
    }

    private static @NotNull String requireField(@NotNull Map<?, ?> fields, @NotNull String field) throws IOException {
        if (!(fields.get(field) instanceof String value) || value.isEmpty()) {
            throw Exceptions.newIOException("The script manifest has no nonempty string \"{0}\".", field);
        }

        return value;
    }


    private final byte @NotNull [] content;

    private final @NotNull String name;
    private final @NotNull String main;
    private final @NotNull String api;

    /**
     * Creates a {@link ScriptManifest} instance.
     *
     * @param content the UTF-8 JSON text
     * @param name    the package name
     * @param main    the name of the script that runs first
     * @param api     the script API version
     */
    private ScriptManifest(byte @NotNull [] content, @NotNull String name, @NotNull String main, @NotNull String api) {
        this.content = content;

        this.name = name;
        this.main = main;
        this.api  = api;
    }

    /**
     * @return a copy of the UTF-8 JSON text of the manifest
     */
    public byte @NotNull [] getContent() {
        return this.content.clone();
    }

    /**
     * @return the package name
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the name of the script that runs first, relative to the script folder with {@code /} separators
     */
    public @NotNull String getMain() {
        return this.main;
    }

    /**
     * @return the script API version
     */
    public @NotNull String getApi() {
        return this.api;
    }
}
