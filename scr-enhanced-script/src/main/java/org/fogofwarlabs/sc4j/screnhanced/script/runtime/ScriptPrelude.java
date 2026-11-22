package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.screnhanced.script.core.ScriptDatTables;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Runs the prelude, the upper layer of the game API, and calls its entry points.
 * <p>
 * The prelude is made of area files ({@link #FILE_NAMES}), run in that order once, outside any instruction budget. Each file is a function of
 * {@code core} and of the names the earlier files made, a read-only object, and returns {@code {exports, shared}}: its exports become
 * read-only globals of the scripts and, with its shared names, names of the later files. The names start with {@code datTables}, the DAT
 * tables of the type objects ({@link ScriptDatTables#createPreludeTables}). A file reaches the game through {@code core} only.
 * <p>
 * The last file's {@code createEntryPoints(host)} makes the entry points over the runtime's host: {@code frame(frame)},
 * {@code localFrame(frame)}, {@code render()}, {@code decide(kind, record)} and {@code localInput(record)}, which return nothing but the
 * consumption of a local input. Their arguments are numbers and records.
 */
final class ScriptPrelude {
    /**
     * The area files of the prelude, in load order.
     */
    static final @NotNull List<@NotNull String> FILE_NAMES = List.of("values.js", "types.js", "buttons.js", "game.js", "sync.js", "local.js",
                                                                     "command-card.js", "presentation.js", "render.js", "hooks.js");
    /**
     * The resource folder of the area files.
     */
    static final @NotNull String RESOURCE_FOLDER = "/org/fogofwarlabs/sc4j/screnhanced/script/prelude/";

    private static final @NotNull String SOURCE_PREFIX = "prelude/";
    private static final @NotNull String DAT_TABLES_NAME = "datTables";
    private static final @NotNull String EXPORTS_PROPERTY = "exports";
    private static final @NotNull String SHARED_PROPERTY = "shared";
    private static final @NotNull String CREATE_ENTRY_POINTS_NAME = "createEntryPoints";
    private static final @NotNull String FRAME_NAME = "frame";
    private static final @NotNull String LOCAL_FRAME_NAME = "localFrame";
    private static final @NotNull String RENDER_NAME = "render";
    private static final @NotNull String DECIDE_NAME = "decide";
    private static final @NotNull String LOCAL_INPUT_NAME = "localInput";
    private static final int GLOBAL_ATTRIBUTES = ScriptableObject.READONLY | ScriptableObject.DONTENUM | ScriptableObject.PERMANENT;
    private static final int NAME_ATTRIBUTES = ScriptableObject.READONLY | ScriptableObject.PERMANENT;

    /**
     * Runs the prelude's area files and makes its entry points.
     *
     * @param context the current context
     * @param global  the global scope, which receives the exports
     * @param core    the {@code core} object
     * @param host    the runtime's host of the entry points
     * @return the created {@link ScriptPrelude} instance
     * @throws IllegalStateException if an area file is missing, cannot be read, or does not have the form of an area file
     */
    static @NotNull ScriptPrelude load(@NotNull Context context, @NotNull ScriptableObject global, @NotNull Scriptable core,
                                       @NotNull Scriptable host) {
        Scriptable names = context.newObject(global);

        ScriptableObject.putProperty(names, DAT_TABLES_NAME, ScriptDatTables.createPreludeTables(context, global));

        for (String fileName : FILE_NAMES) {
            Script script = context.compileString(readFile(fileName), SOURCE_PREFIX + fileName, 1, null);

            if (!(script.exec(context, global) instanceof Function area)) {
                throw Exceptions.newIllegalStateException("The prelude file {0} is not a function.", fileName);
            }

            if (!(area.call(context, global, global, new Object[]{core, createSnapshot(context, global, names)}) instanceof Scriptable result) ||
                !(ScriptableObject.getProperty(result, EXPORTS_PROPERTY) instanceof Scriptable exports) ||
                !(ScriptableObject.getProperty(result, SHARED_PROPERTY) instanceof Scriptable shared)) {
                throw Exceptions.newIllegalStateException("The prelude file {0} returned no exports and shared names.", fileName);
            }

            for (Object id : exports.getIds()) {
                Object value = ScriptableObject.getProperty(exports, (String) id);

                ScriptableObject.defineProperty(global, (String) id, value, GLOBAL_ATTRIBUTES);
                ScriptableObject.putProperty(names, (String) id, value);
            }

            for (Object id : shared.getIds()) {
                ScriptableObject.putProperty(names, (String) id, ScriptableObject.getProperty(shared, (String) id));
            }
        }

        if (!(ScriptableObject.getProperty(names, CREATE_ENTRY_POINTS_NAME) instanceof Function createEntryPoints) ||
            !(createEntryPoints.call(context, global, global, new Object[]{host}) instanceof Scriptable entryPoints)) {
            throw Exceptions.newIllegalStateException("The prelude made no entry points.");
        }

        return new ScriptPrelude(global, entryPoints);
    }

    /**
     * @param sourceName the source name of a stack frame
     * @return {@code true} if the source name is that of an area file of the prelude, {@code false} otherwise
     */
    static boolean isPreludeSource(@NotNull String sourceName) {
        return sourceName.startsWith(SOURCE_PREFIX) && FILE_NAMES.contains(sourceName.substring(SOURCE_PREFIX.length()));
    }

    private static @NotNull String readFile(@NotNull String fileName) {
        try (InputStream input = ScriptPrelude.class.getResourceAsStream(RESOURCE_FOLDER + fileName)) {
            if (input == null) {
                throw Exceptions.newIllegalStateException("The prelude file {0} is missing.", fileName);
            }

            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "The prelude file {0} cannot be read.", fileName);
        }
    }

    /**
     * Creates a read-only copy of the names so far, which an area file receives.
     */
    private static @NotNull Scriptable createSnapshot(@NotNull Context context, @NotNull Scriptable global, @NotNull Scriptable names) {
        ScriptableObject snapshot = (ScriptableObject) context.newObject(global);

        for (Object id : names.getIds()) {
            ScriptableObject.defineProperty(snapshot, (String) id, ScriptableObject.getProperty(names, (String) id), NAME_ATTRIBUTES);
        }

        snapshot.preventExtensions();

        return snapshot;
    }

    private static @NotNull Function requireEntryPoint(@NotNull Scriptable entryPoints, @NotNull String name) {
        if (!(ScriptableObject.getProperty(entryPoints, name) instanceof Function function)) {
            throw Exceptions.newIllegalStateException("The prelude has no entry point {0}.", name);
        }

        return function;
    }


    private final @NotNull Scriptable global;
    private final @NotNull Scriptable entryPoints;

    private final @NotNull Function frame;
    private final @NotNull Function localFrame;
    private final @NotNull Function render;
    private final @NotNull Function decide;
    private final @NotNull Function localInput;

    /**
     * Creates a {@link ScriptPrelude} instance over the prelude's entry points.
     *
     * @param global      the global scope
     * @param entryPoints the object of the entry points
     * @throws IllegalStateException if an entry point is missing
     */
    private ScriptPrelude(@NotNull Scriptable global, @NotNull Scriptable entryPoints) {
        this.global      = global;
        this.entryPoints = entryPoints;

        this.frame      = requireEntryPoint(entryPoints, FRAME_NAME);
        this.localFrame = requireEntryPoint(entryPoints, LOCAL_FRAME_NAME);
        this.render     = requireEntryPoint(entryPoints, RENDER_NAME);
        this.decide     = requireEntryPoint(entryPoints, DECIDE_NAME);
        this.localInput = requireEntryPoint(entryPoints, LOCAL_INPUT_NAME);
    }

    /**
     * Calls {@code frame(frame)}: the shared hooks of a game frame.
     *
     * @param context the current context
     * @param frame   the frame number
     */
    void frame(@NotNull Context context, int frame) {
        this.frame.call(context, this.global, this.entryPoints, new Object[]{(double) frame});
    }

    /**
     * Calls {@code localFrame(frame)}: the local hook of a played frame.
     *
     * @param context the current context
     * @param frame   the frame number
     */
    void localFrame(@NotNull Context context, int frame) {
        this.localFrame.call(context, this.global, this.entryPoints, new Object[]{(double) frame});
    }

    /**
     * Calls {@code render()}: the drawing hook.
     *
     * @param context the current context
     */
    void render(@NotNull Context context) {
        this.render.call(context, this.global, this.entryPoints, new Object[0]);
    }

    /**
     * Calls {@code decide(kind, record)}: the decision hooks, which leave their decision in the record.
     *
     * @param context the current context
     * @param kind    the id of the decision's kind
     * @param record  the decision record
     */
    void decide(@NotNull Context context, int kind, @NotNull Scriptable record) {
        this.decide.call(context, this.global, this.entryPoints, new Object[]{(double) kind, record});
    }

    /**
     * Calls {@code localInput(record)}: the local input hooks.
     *
     * @param context the current context
     * @param record  the input record
     * @return {@code true} if a hook consumed the input, {@code false} otherwise
     */
    boolean localInput(@NotNull Context context, @NotNull Scriptable record) {
        return Boolean.TRUE.equals(this.localInput.call(context, this.global, this.entryPoints, new Object[]{record}));
    }
}
