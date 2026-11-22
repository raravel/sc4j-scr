package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.jetbrains.annotations.NotNull;

/**
 * Receives the messages of a script runtime: the messages of the scripts' {@code log} and the runtime's own warnings and errors.
 */
@FunctionalInterface
public interface ScriptLogOutput {
    /**
     * Writes one message.
     *
     * @param level      the message level
     * @param moduleName the name of the module that the message belongs to
     * @param message    the message
     */
    void write(@NotNull LogLevel level, @NotNull String moduleName, @NotNull String message);
}
