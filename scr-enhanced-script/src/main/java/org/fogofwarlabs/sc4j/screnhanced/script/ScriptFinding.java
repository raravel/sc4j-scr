package org.fogofwarlabs.sc4j.screnhanced.script;

import org.jetbrains.annotations.NotNull;

/**
 * Represents one finding of {@link ScriptCheckUtils}: a file of the script folder, a line of it when the finding has one, a severity and a
 * message. Its text is {@code file:line: error: message}, without {@code :line} for a finding of the whole file.
 */
public final class ScriptFinding {
    /**
     * The line of a finding of the whole file.
     */
    public static final int NO_LINE = 0;

    /**
     * The severity of a finding: an error fails the check, a warning does not.
     */
    public enum Severity {
        /**
         * The script, the manifest or a resource does not load or does not run as written.
         */
        ERROR("error"),
        /**
         * The script runs, but probably not as meant.
         */
        WARNING("warning");

        private final @NotNull String label;

        Severity(@NotNull String label) {
            this.label = label;
        }

        /**
         * @return the label of the severity in the text of a finding
         */
        public @NotNull String getLabel() {
            return this.label;
        }
    }


    private final @NotNull String file;
    private final int line;
    private final @NotNull Severity severity;
    private final @NotNull String message;

    /**
     * Creates a {@link ScriptFinding} instance.
     *
     * @param file     the file relative to the script folder with {@code /} separators, or {@code .} for the folder
     * @param line     the line from {@code 1}, or {@link #NO_LINE}
     * @param severity the severity
     * @param message  the message
     */
    public ScriptFinding(@NotNull String file, int line, @NotNull Severity severity, @NotNull String message) {
        this.file     = file;
        this.line     = line;
        this.severity = severity;
        this.message  = message;
    }

    /**
     * @return the file relative to the script folder with {@code /} separators, or {@code .} for the folder
     */
    public @NotNull String getFile() {
        return this.file;
    }

    /**
     * @return the line from {@code 1}, or {@link #NO_LINE}
     */
    public int getLine() {
        return this.line;
    }

    /**
     * @return the severity
     */
    public @NotNull Severity getSeverity() {
        return this.severity;
    }

    /**
     * @return the message
     */
    public @NotNull String getMessage() {
        return this.message;
    }


    /**
     * @return the text of the finding: {@code file:line: severity: message}, without {@code :line} for {@link #NO_LINE}
     */
    @Override
    public @NotNull String toString() {
        String location = this.line == NO_LINE ? this.file : this.file + ':' + this.line;

        return location + ": " + this.severity.getLabel() + ": " + this.message;
    }
}
