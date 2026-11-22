package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Represents a script package: the manifest, the scripts and the resources of a map.
 * <p>
 * In a script folder the manifest is {@value ScriptManifest#FILE_NAME}, the scripts are the {@value #SCRIPT_EXTENSION} files outside
 * {@value #RESOURCE_FOLDER} and the resources are every file under {@value #RESOURCE_FOLDER}; other files are not part of the package.
 * Script names are relative to the folder and resource names relative to {@value #RESOURCE_FOLDER}, both with {@code /} separators.
 * A name has nonempty segments other than {@code .} and {@code ..} and no backslash, colon or control character; names are unique
 * ignoring letter case, and no name is the folder of another, so that every name is one file of a folder on every platform.
 */
public final class ScriptPackage {
    /**
     * The extension of a script file.
     */
    public static final @NotNull String SCRIPT_EXTENSION = ".js";
    /**
     * The folder of the resources in a script folder.
     */
    public static final @NotNull String RESOURCE_FOLDER = "resources";

    private static final char NAME_SEPARATOR = '/';
    private static final @NotNull String CURRENT_FOLDER_SEGMENT = ".";
    private static final @NotNull String PARENT_FOLDER_SEGMENT = "..";
    private static final @NotNull String FORBIDDEN_NAME_CHARACTERS = "\\:";
    private static final char FIRST_PRINTABLE_CHARACTER = 0x20;

    /**
     * Creates a {@link ScriptPackage} instance after checking its names and its manifest. The script and resource names must be valid, unique
     * ignoring letter case and none the folder of another name of its kind; every script name ends with {@value #SCRIPT_EXTENSION} and lies
     * outside {@value #RESOURCE_FOLDER}.
     *
     * @param manifest  the manifest
     * @param scripts   the script contents by name, in the stored order; the map is copied, the contents are not
     * @param resources the resource contents by name, in the stored order; the map is copied, the contents are not
     * @return the created {@link ScriptPackage} instance
     * @throws IOException if a name is not valid or the manifest's {@code main} names no script
     */
    public static @NotNull ScriptPackage create(@NotNull ScriptManifest manifest, @NotNull Map<String, byte[]> scripts,
                                                @NotNull Map<String, byte[]> resources) throws IOException {
        checkNames(scripts.keySet(), resources.keySet());

        if (!scripts.containsKey(manifest.getMain())) {
            throw Exceptions.newIOException("The script manifest names the main script {0}, which the package does not hold.", manifest.getMain());
        }

        return new ScriptPackage(manifest, Collections.unmodifiableMap(new LinkedHashMap<>(scripts)),
                                 Collections.unmodifiableMap(new LinkedHashMap<>(resources)));
    }

    /**
     * Reads a script package from a script folder; scripts and resources are ordered by name.
     *
     * @param folder the script folder
     * @return the read script package
     * @throws IOException if a file cannot be read, the folder has no {@value ScriptManifest#FILE_NAME}, or the package is not valid ({@link #create})
     */
    public static @NotNull ScriptPackage readFolder(@NotNull Path folder) throws IOException {
        Path manifestFile = folder.resolve(ScriptManifest.FILE_NAME);

        if (!Files.isRegularFile(manifestFile)) {
            throw Exceptions.newIOException("The script folder {0} has no {1}.", folder, ScriptManifest.FILE_NAME);
        }

        ScriptManifest manifest = ScriptManifest.parse(Files.readAllBytes(manifestFile));
        Map<String, byte[]> scripts = new TreeMap<>();
        Map<String, byte[]> resources = new TreeMap<>();

        readFolderFiles(folder, scripts, resources);

        return create(manifest, scripts, resources);
    }

    /**
     * Reads the scripts and the resources of a script folder, without the manifest and without checking the names.
     *
     * @param folder    the script folder
     * @param scripts   receives the script contents by name
     * @param resources receives the resource contents by name
     * @throws IOException if the folder or a file cannot be read
     */
    static void readFolderFiles(@NotNull Path folder, @NotNull Map<String, byte[]> scripts, @NotNull Map<String, byte[]> resources) throws IOException {
        String resourcePrefix = RESOURCE_FOLDER + NAME_SEPARATOR;

        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = toName(folder.relativize(file));

                if (name.startsWith(resourcePrefix)) {
                    resources.put(name.substring(resourcePrefix.length()), Files.readAllBytes(file));
                } else if (name.endsWith(SCRIPT_EXTENSION)) {
                    scripts.put(name, Files.readAllBytes(file));
                }
            }
        }
    }

    private static @NotNull String toName(@NotNull Path relativePath) {
        StringBuilder name = new StringBuilder();

        for (Path segment : relativePath) {
            if (!name.isEmpty()) {
                name.append(NAME_SEPARATOR);
            }

            name.append(segment);
        }

        return name.toString();
    }

    /**
     * Checks the names of a package: the script and resource names are valid, unique ignoring letter case and none the folder of another
     * name of its kind, and every script name ends with {@value #SCRIPT_EXTENSION} and lies outside {@value #RESOURCE_FOLDER}.
     *
     * @param scriptNames   the script names
     * @param resourceNames the resource names
     * @throws IOException if a name breaks one of these rules
     */
    static void checkNames(@NotNull Set<String> scriptNames, @NotNull Set<String> resourceNames) throws IOException {
        for (String scriptName : scriptNames) {
            if (!scriptName.endsWith(SCRIPT_EXTENSION)) {
                throw Exceptions.newIOException("The script name {0} does not end with {1}.", scriptName, SCRIPT_EXTENSION);
            }

            if (scriptName.startsWith(RESOURCE_FOLDER + NAME_SEPARATOR)) {
                throw Exceptions.newIOException("The script name {0} lies under the resource folder {1}.", scriptName, RESOURCE_FOLDER);
            }
        }

        checkKindNames(scriptNames, "script");
        checkKindNames(resourceNames, "resource");
    }

    /**
     * Checks the names of one kind of file: each name is valid, no two are equal ignoring letter case and none is the folder of another.
     *
     * @param names the names
     * @param kind  the kind of file for messages
     * @throws IOException if a name is not valid, two names are equal ignoring letter case or one is the folder of another
     */
    private static void checkKindNames(@NotNull Set<String> names, @NotNull String kind) throws IOException {
        Set<String> nameKeys = new HashSet<>();

        for (String name : names) {
            checkName(name, kind);

            if (!nameKeys.add(name.toLowerCase(Locale.ROOT))) {
                throw Exceptions.newIOException("The {0} name {1} is not unique ignoring letter case.", kind, name);
            }
        }

        for (String nameKey : nameKeys) {
            for (int separator = nameKey.indexOf(NAME_SEPARATOR); separator >= 0; separator = nameKey.indexOf(NAME_SEPARATOR, separator + 1)) {
                if (nameKeys.contains(nameKey.substring(0, separator))) {
                    throw Exceptions.newIOException("The {0} name {1} is also the folder of {2}.", kind, nameKey.substring(0, separator), nameKey);
                }
            }
        }
    }

    private static void checkName(@NotNull String name, @NotNull String kind) throws IOException {
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);

            if (character < FIRST_PRINTABLE_CHARACTER || FORBIDDEN_NAME_CHARACTERS.indexOf(character) >= 0) {
                throw Exceptions.newIOException("The {0} name {1} holds a backslash, a colon or a control character.", kind, name);
            }
        }

        for (String segment : name.split(String.valueOf(NAME_SEPARATOR), -1)) {
            if (segment.isEmpty() || segment.equals(CURRENT_FOLDER_SEGMENT) || segment.equals(PARENT_FOLDER_SEGMENT)) {
                throw Exceptions.newIOException("The {0} name {1} has an empty, {2} or {3} segment.", kind, name, CURRENT_FOLDER_SEGMENT,
                                                PARENT_FOLDER_SEGMENT);
            }
        }
    }

    private static void writeFile(@NotNull Path folder, @NotNull String name, byte @NotNull [] content) throws IOException {
        Path file = folder.resolve(name);

        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }


    private final @NotNull ScriptManifest manifest;
    private final @NotNull Map<String, byte[]> scripts;
    private final @NotNull Map<String, byte[]> resources;

    /**
     * Creates a {@link ScriptPackage} instance.
     *
     * @param manifest  the manifest
     * @param scripts   the script contents by name, unmodifiable
     * @param resources the resource contents by name, unmodifiable
     */
    private ScriptPackage(@NotNull ScriptManifest manifest, @NotNull Map<String, byte[]> scripts, @NotNull Map<String, byte[]> resources) {
        this.manifest  = manifest;
        this.scripts   = scripts;
        this.resources = resources;
    }

    /**
     * @return the manifest
     */
    public @NotNull ScriptManifest getManifest() {
        return this.manifest;
    }

    /**
     * @return the unmodifiable script contents by name, in the stored order
     */
    public @NotNull Map<String, byte[]> getScripts() {
        return this.scripts;
    }

    /**
     * @return the unmodifiable resource contents by name, in the stored order
     */
    public @NotNull Map<String, byte[]> getResources() {
        return this.resources;
    }

    /**
     * Writes the package as a script folder: {@value ScriptManifest#FILE_NAME}, the scripts and the resources under
     * {@value #RESOURCE_FOLDER}.
     *
     * @param folder the script folder, absent or empty
     * @throws IOException if the folder holds files or a file cannot be written
     */
    public void writeFolder(@NotNull Path folder) throws IOException {
        if (Files.isDirectory(folder)) {
            try (Stream<Path> entries = Files.list(folder)) {
                if (entries.findAny().isPresent()) {
                    throw Exceptions.newIOException("The script folder is not empty: {0}", folder);
                }
            }
        }

        Files.createDirectories(folder);
        Files.write(folder.resolve(ScriptManifest.FILE_NAME), this.manifest.getContent());

        for (Map.Entry<String, byte[]> script : this.scripts.entrySet()) {
            writeFile(folder, script.getKey(), script.getValue());
        }

        for (Map.Entry<String, byte[]> resource : this.resources.entrySet()) {
            writeFile(folder.resolve(RESOURCE_FOLDER), resource.getKey(), resource.getValue());
        }
    }
}
