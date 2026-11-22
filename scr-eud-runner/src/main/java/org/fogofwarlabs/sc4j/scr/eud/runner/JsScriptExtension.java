package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationExtension;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugin;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptSimulationPlugin;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptTrailer;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Attaches SC:R Enhanced's script runtime to the one game of a run whose {@code --js} names a script folder ({@link #arm}). The game finds
 * the extension through {@code META-INF/services} when it attaches its SC:R system; without {@link #arm} it creates no plugin, so a run
 * without {@code --js} plays exactly as before. The script package comes from the folder, never from the map file, which stays the bytes the
 * build wrote.
 * <p>
 * The script's log messages and errors (an uncaught error, a call over the instruction budget, an unknown hook) are kept with the frame they
 * came in, at most {@value #MAXIMUM_LINES} lines of at most {@value #MAXIMUM_MESSAGE_CHARS} characters; the rest are counted.
 */
public final class JsScriptExtension implements ScrSimulationExtension {
    static final int MAXIMUM_LINES = 400;
    static final int MAXIMUM_MESSAGE_CHARS = 1000;

    private static @Nullable ScriptTrailer armed;
    private static @Nullable BiConsumer<Context, Scriptable> armedGlobals;
    private static @Nullable Game armedGame;
    private static @Nullable ScriptSimulationPlugin plugin;
    private static final @NotNull List<Map<String, Object>> LINES = new ArrayList<>();
    private static int omitted;
    private static int errors;
    private static @Nullable String lastErrorBeyondLines;
    private static @NotNull List<Object> warnings = List.of();

    /**
     * Lets the next game this extension sees run the script package of {@code trailer}.
     *
     * @param trailer  the script package of the test folder, with its resources
     * @param findings the warnings of the folder's check
     * @param globals  adds the host's globals to the script's global scope, or {@code null}
     */
    static synchronized void arm(@NotNull ScriptTrailer trailer, @NotNull List<Object> findings,
                                 @Nullable BiConsumer<Context, Scriptable> globals) {
        armed        = trailer;
        armedGlobals = globals;
        warnings     = List.copyOf(findings);
    }

    /**
     * @return the runtime of the attached script, or {@code null} before the game attached it
     */
    static synchronized @Nullable ScriptRuntime runtime() {
        ScriptSimulationPlugin current = plugin;

        return current != null ? current.getRuntime() : null;
    }

    /**
     * @return the error lines written so far
     */
    static synchronized int errors() {
        return errors;
    }

    /**
     * @return the message of the newest error line, or {@code null}
     */
    static synchronized @Nullable String lastError() {
        // An error past the kept lines is newer than any of them.
        if (lastErrorBeyondLines != null) {
            return lastErrorBeyondLines;
        }

        for (int index = LINES.size() - 1; index >= 0; index--) {
            if ("error".equals(LINES.get(index).get("level"))) {
                return (String) LINES.get(index).get("message");
            }
        }

        return null;
    }

    @Override
    public @Nullable ScrSimulationPlugin createPlugin(@NotNull Game game) {
        synchronized (JsScriptExtension.class) {
            ScriptTrailer trailer = armed;

            if (trailer == null) {
                return null;
            }

            armed     = null;
            armedGame = game;
            plugin    = new ScriptSimulationPlugin(game, trailer.getScripts(), trailer.getResources(), JsScriptExtension::write, armedGlobals);

            return plugin;
        }
    }

    private static void write(@NotNull LogLevel level, @NotNull String moduleName, @NotNull String message) {
        synchronized (JsScriptExtension.class) {
            String text = message.length() > MAXIMUM_MESSAGE_CHARS ? message.substring(0, MAXIMUM_MESSAGE_CHARS) + "…" : message;

            if (level == LogLevel.ERROR) {
                errors++;
            }

            if (LINES.size() >= MAXIMUM_LINES) {
                omitted++;

                if (level == LogLevel.ERROR) {
                    lastErrorBeyondLines = text;
                }

                return;
            }

            Game game = armedGame;

            LINES.add(RunnerMain.ordered("frame", game != null ? game.getCurrentFrame() : 0, "level", level.name().toLowerCase(),
                                         "module", moduleName, "message", text));
        }
    }

    /**
     * @return the report section of the run's script: whether it was attached, ran without errors or was stopped, and its log
     */
    static synchronized @NotNull Map<String, Object> report() {
        ScriptSimulationPlugin current = plugin;
        String status;

        if (current == null) {
            status = "not_attached";
        } else if (current.getRuntime().isStopped()) {
            status = "stopped";
        } else {
            status = errors > 0 ? "errors" : "ok";
        }

        Map<String, Object> section = RunnerMain.ordered("status", status, "errors", errors, "log", List.copyOf(LINES));

        if (!warnings.isEmpty()) {
            section.put("findings", warnings);
        }

        if (omitted > 0) {
            section.put("omittedLines", omitted);
        }

        return section;
    }
}
