package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageReplacements;
import org.fogofwarlabs.sc4j.screnhanced.graphics.PictureFileUtils;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptFinding.Severity;
import org.fogofwarlabs.sc4j.screnhanced.script.core.ScriptDatTables;
import org.fogofwarlabs.sc4j.screnhanced.script.core.ScriptGame;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptHook;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.*;
import org.mozilla.javascript.ast.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Checks a script folder before it is packed: the files {@link ScriptPackage#readFolder} reads, with the rules of the script runtime.
 * <p>
 * The manifest: present and valid, the main script in the folder, the script API {@value ScriptProjectUtils#API_VERSION} (a warning
 * otherwise); the names of the scripts and resources (errors of the folder {@value #FOLDER_FILE}).
 * <p>
 * Each script, read as UTF-8 and compiled by Rhino with the settings of the runtime: the syntax errors, the first of each line, with a note when the
 * line holds syntax that Rhino 1.8.1 lacks ({@code class} and {@code super}, {@code async} and {@code await}, spread {@code ...},
 * {@code for (const ...)}, {@code import} and {@code export}), which names the TypeScript target that avoids it; after a successful compile,
 * references to the globals the runtime does not have ({@code eval}, {@code Date}, {@code java}, {@code Packages} and the other Java and timer
 * globals) and calls of the {@code Function} constructor (errors) unless the script declares the name, top-level functions with the form of a hook
 * name that no hook has (warnings, as the runtime warns), the properties of an object literal given to {@code defineHooks} whose name no hook
 * has, and the hooks that two {@code defineHooks} calls or a call and a top-level function define (errors, as the runtime throws), unless the
 * script declares {@code defineHooks}, a hook name assigned to {@code exports} or {@code module.exports}, whose function the runtime calls as a
 * hook besides the modules that call it (a warning, once per name), a {@code const} in a loop, which keeps its first value in
 * Rhino 1.8.1 (a warning), and functions in the body of a {@code for (let ...)} loop that use its variables, which all iterations share in Rhino
 * 1.8.1 (a warning), and names under {@code core.dat} that the DAT tables do not have, a table or a function of a table (errors), unless the script
 * declares {@code core}, and uses of {@code local.*} at the top level of a module or directly in a shared hook, a top-level function or a method
 * of {@code defineHooks}, where they throw (errors), unless the script declares {@code local}.
 * <p>
 * Each resource named {@code *.grp}, {@code *.anim}, {@code *.png} or {@code *.dds}, read as the runtime reads a GRP, an anim or an image.
 * The size: the script trailer with every file stored as it is against the largest map file (a warning).
 */
public final class ScriptCheckUtils {
    /**
     * The file of a finding of the whole script folder.
     */
    public static final @NotNull String FOLDER_FILE = ".";

    private static final @NotNull String RESOURCE_FILE_PREFIX = ScriptPackage.RESOURCE_FOLDER + "/";
    private static final @NotNull String GRP_EXTENSION = ".grp";
    private static final @NotNull String ANIM_EXTENSION = ".anim";
    private static final @NotNull String PNG_EXTENSION = ".png";
    private static final @NotNull String DDS_EXTENSION = ".dds";
    private static final int FIRST_LINE = 1;
    private static final @NotNull String FUNCTION_CONSTRUCTOR_NAME = "Function";
    private static final @NotNull String DEFINE_HOOKS_NAME = "defineHooks";
    /**
     * The namespace of the DAT tables under {@code core}.
     */
    private static final @NotNull String DAT_NAMESPACE = "dat";
    /**
     * The global of the local information.
     */
    private static final @NotNull String LOCAL_NAME = "local";
    private static final @NotNull String GETTER_PREFIX = "get";
    private static final @NotNull String SETTER_PREFIX = "set";
    /**
     * The name of a module's exports and the property of {@code module} that holds them.
     */
    private static final @NotNull String EXPORTS_NAME = "exports";
    private static final @NotNull String MODULE_NAME = "module";
    /**
     * The globals a script does not have: removed from Rhino's standard objects, or Java, XML and timer globals that the runtime never defines.
     */
    private static final @NotNull Set<String> UNAVAILABLE_GLOBALS = Set.of(
            "eval", "Date", "Script", "Continuation", "XML", "XMLList", "Namespace", "QName", "isXMLName",
            "java", "javax", "Packages", "JavaAdapter", "JavaImporter", "importClass", "importPackage",
            "setTimeout", "setInterval", "clearTimeout", "clearInterval"
    );
    /**
     * The notes of the syntax that Rhino 1.8.1 lacks, for a line with a syntax error that holds it.
     */
    private static final @NotNull List<SyntaxNote> SYNTAX_NOTES = List.of(
            new SyntaxNote("\\bclass\\b", "Rhino 1.8.1 has no class syntax; use functions and prototypes, or compile TypeScript with target ES5"),
            new SyntaxNote("\\bsuper\\b", "Rhino 1.8.1 has no class syntax and takes super only in object literal methods; use functions and " +
                                          "prototypes, or compile TypeScript with target ES5"),
            new SyntaxNote("\\basync\\b|\\bawait\\b", "Rhino 1.8.1 has no async and await; use promises, or compile TypeScript with target ES5"),
            new SyntaxNote("\\bfor\\s*\\(\\s*const\\b", "Rhino 1.8.1 has no for (const ...); use let, or compile TypeScript with target ES5"),
            new SyntaxNote("\\.\\.\\.", "Rhino 1.8.1 has no spread ... and no rest element in destructuring, only rest parameters; compile " +
                                        "TypeScript with target ES5"),
            new SyntaxNote("^\\s*(import|export)\\b", "Rhino 1.8.1 has no import and export; use require and module.exports, or compile " +
                                                      "TypeScript with module commonjs")
    );

    /**
     * Syntax that Rhino 1.8.1 lacks: the pattern of a line that holds it and the note.
     */
    private static final class SyntaxNote {
        private final @NotNull Pattern pattern;
        private final @NotNull String note;

        private SyntaxNote(@NotNull String pattern, @NotNull String note) {
            this.pattern = Pattern.compile(pattern);
            this.note    = note;
        }
    }

    /**
     * Collects the syntax errors of one script, the first of each line.
     */
    private static final class SyntaxErrors implements ErrorReporter {
        private final @NotNull Map<Integer, ScriptFinding> findings;
        private final @NotNull String scriptName;

        private SyntaxErrors(@NotNull String scriptName) {
            this.findings   = new TreeMap<>();
            this.scriptName = scriptName;
        }

        @Override
        public void warning(@NotNull String message, @Nullable String sourceName, int line, @Nullable String lineSource, int lineOffset) {
        }

        @Override
        public void error(@NotNull String message, @Nullable String sourceName, int line, @Nullable String lineSource, int lineOffset) {
            this.add(message, line, lineSource);
        }

        @Override
        public @NotNull EvaluatorException runtimeError(@NotNull String message, @Nullable String sourceName, int line,
                                                        @Nullable String lineSource, int lineOffset) {
            return new EvaluatorException(message, sourceName, line, lineSource, lineOffset);
        }

        private void add(@NotNull String message, int line, @Nullable String lineSource) {
            int findingLine = Math.max(line, FIRST_LINE);

            if (this.findings.containsKey(findingLine)) {
                return;
            }

            String text = message;

            if (lineSource != null) {
                for (SyntaxNote note : SYNTAX_NOTES) {
                    if (note.pattern.matcher(lineSource).find()) {
                        text = message + " (" + note.note + ")";
                        break;
                    }
                }
            }

            this.findings.put(findingLine, new ScriptFinding(this.scriptName, findingLine, Severity.ERROR, text));
        }
    }

    /**
     * Checks a script folder.
     *
     * @param folder the script folder
     * @return the findings: the manifest and the folder first, then the scripts and the resources by name, each by line
     * @throws IOException if the path is not a folder or a file of it cannot be read
     */
    public static @NotNull List<@NotNull ScriptFinding> check(@NotNull Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            throw Exceptions.newIOException("The script folder {0} is not a folder.", folder);
        }

        List<ScriptFinding> findings = new ArrayList<>();
        Map<String, byte[]> scripts = new TreeMap<>();
        Map<String, byte[]> resources = new TreeMap<>();

        ScriptPackage.readFolderFiles(folder, scripts, resources);

        ScriptManifest manifest = checkManifest(folder, scripts, findings);
        boolean namesValid = true;

        try {
            ScriptPackage.checkNames(scripts.keySet(), resources.keySet());
        } catch (IOException exception) {
            findings.add(new ScriptFinding(FOLDER_FILE, ScriptFinding.NO_LINE, Severity.ERROR, exception.getMessage()));
            namesValid = false;
        }

        if (manifest != null && namesValid && scripts.containsKey(manifest.getMain())) {
            checkSizes(ScriptPackage.create(manifest, scripts, resources), findings);
        }

        checkScripts(scripts, findings);
        checkResources(resources, findings);

        return findings;
    }

    /**
     * Counts the errors of findings.
     *
     * @param findings the findings
     * @return the number of findings with {@link Severity#ERROR}
     */
    public static int countErrors(@NotNull List<@NotNull ScriptFinding> findings) {
        int count = 0;

        for (ScriptFinding finding : findings) {
            if (finding.getSeverity() == Severity.ERROR) {
                count++;
            }
        }

        return count;
    }

    /**
     * Checks the manifest of a script folder.
     *
     * @return the manifest, or {@code null} if the folder has none or it is not valid
     */
    private static @Nullable ScriptManifest checkManifest(@NotNull Path folder, @NotNull Map<String, byte[]> scripts,
                                                          @NotNull List<ScriptFinding> findings) throws IOException {
        Path manifestFile = folder.resolve(ScriptManifest.FILE_NAME);

        if (!Files.isRegularFile(manifestFile)) {
            findings.add(new ScriptFinding(ScriptManifest.FILE_NAME, ScriptFinding.NO_LINE, Severity.ERROR,
                                           Exceptions.format("The script folder has no {0}.", ScriptManifest.FILE_NAME)));

            return null;
        }

        ScriptManifest manifest;

        try {
            manifest = ScriptManifest.parse(Files.readAllBytes(manifestFile));
        } catch (IOException exception) {
            findings.add(new ScriptFinding(ScriptManifest.FILE_NAME, ScriptFinding.NO_LINE, Severity.ERROR, exception.getMessage()));

            return null;
        }

        if (!scripts.containsKey(manifest.getMain())) {
            findings.add(new ScriptFinding(ScriptManifest.FILE_NAME, ScriptFinding.NO_LINE, Severity.ERROR,
                                           Exceptions.format("The main script {0} is not in the script folder.", manifest.getMain())));
        }

        if (!manifest.getApi().equals(ScriptProjectUtils.API_VERSION)) {
            findings.add(new ScriptFinding(ScriptManifest.FILE_NAME, ScriptFinding.NO_LINE, Severity.WARNING,
                                           Exceptions.format("The manifest names the script API {0}; SC:R Enhanced has the script API {1}.",
                                                             manifest.getApi(), ScriptProjectUtils.API_VERSION)));
        }

        return manifest;
    }

    private static void checkSizes(@NotNull ScriptPackage scriptPackage, @NotNull List<ScriptFinding> findings) {
        long trailerSize = ScriptStorageUtils.getMaximumTrailerSize(scriptPackage);

        if (trailerSize > MpqArchive.MAXIMUM_FILE_SIZE) {
            findings.add(new ScriptFinding(FOLDER_FILE, ScriptFinding.NO_LINE, Severity.WARNING,
                                           Exceptions.format("The package takes {0} bytes before compression, more than the {1} bytes a map file " +
                                                             "may hold; packing fails unless it compresses enough.",
                                                             trailerSize, MpqArchive.MAXIMUM_FILE_SIZE)));
        }
    }

    /**
     * Checks every script with one Rhino context of the runtime's settings.
     */
    private static void checkScripts(@NotNull Map<String, byte[]> scripts, @NotNull List<ScriptFinding> findings) {
        try (Context context = new ContextFactory().enterContext()) {
            context.setInterpretedMode(true);
            context.setLanguageVersion(Context.VERSION_ECMASCRIPT);

            for (Map.Entry<String, byte[]> script : scripts.entrySet()) {
                List<ScriptFinding> scriptFindings = new ArrayList<>();

                checkScript(context, script.getKey(), new String(script.getValue(), StandardCharsets.UTF_8), scriptFindings);
                scriptFindings.sort(Comparator.comparingInt(ScriptFinding::getLine));
                findings.addAll(scriptFindings);
            }
        }
    }

    private static void checkScript(@NotNull Context context, @NotNull String name, @NotNull String source,
                                    @NotNull List<ScriptFinding> findings) {
        CompilerEnvirons environment = new CompilerEnvirons();
        SyntaxErrors errors = new SyntaxErrors(name);
        AstRoot root = null;

        environment.initFromContext(context);
        environment.setRecoverFromErrors(true);

        try {
            root = new Parser(environment, errors).parse(source, name, FIRST_LINE);
        } catch (EvaluatorException exception) {
            // The parser ends with a summary of the errors it reported, which adds nothing to them.
            if (errors.findings.isEmpty()) {
                errors.add(exception.details(), exception.lineNumber(), exception.lineSource());
            }
        }

        if (errors.findings.isEmpty()) {
            try {
                context.compileString(source, name, FIRST_LINE, null);
            } catch (EvaluatorException exception) {
                errors.add(exception.details(), exception.lineNumber(), exception.lineSource());
            }
        }

        findings.addAll(errors.findings.values());

        if (root != null && errors.findings.isEmpty()) {
            checkTree(name, root, findings);
        }
    }

    /**
     * Checks the syntax tree of a script that compiles.
     */
    private static void checkTree(@NotNull String scriptName, @NotNull AstRoot root, @NotNull List<ScriptFinding> findings) {
        Set<String> declaredNames = collectDeclaredNames(root);
        Set<String> exportedHooks = new HashSet<>();
        Set<String> topLevelFunctions = new HashSet<>();
        Map<String, ObjectProperty> definedHooks = new LinkedHashMap<>();

        root.visitAll(node -> {
            if (node instanceof Name name && UNAVAILABLE_GLOBALS.contains(name.getIdentifier()) && isReference(name) &&
                !declaredNames.contains(name.getIdentifier())) {
                findings.add(new ScriptFinding(scriptName, name.getLineno(), Severity.ERROR,
                                               Exceptions.format("{0} is not available to scripts.", name.getIdentifier())));
            } else if (node instanceof FunctionCall call && call.getTarget() instanceof Name target &&
                       target.getIdentifier().equals(FUNCTION_CONSTRUCTOR_NAME) && !declaredNames.contains(FUNCTION_CONSTRUCTOR_NAME)) {
                findings.add(new ScriptFinding(scriptName, call.getLineno(), Severity.ERROR,
                                               "The Function constructor throws: scripts cannot create functions from strings."));
            } else if (node instanceof FunctionCall call && isDefineHooks(call) && !declaredNames.contains(DEFINE_HOOKS_NAME)) {
                checkDefineHooks(scriptName, call, definedHooks, findings);
            } else if (node instanceof FunctionNode function) {
                checkHookFunction(scriptName, function, topLevelFunctions, findings);
            } else if (node instanceof VariableDeclaration declaration) {
                checkDeclaration(scriptName, declaration, topLevelFunctions, findings);
            } else if (node instanceof Loop loop) {
                checkLetLoop(scriptName, loop, findings);
            } else if (node instanceof Assignment assignment) {
                checkExportedHook(scriptName, assignment, exportedHooks, findings);
            } else if (node instanceof PropertyGet get) {
                if (!declaredNames.contains(ScriptGame.CORE_NAME)) {
                    checkDatName(scriptName, get, findings);
                }

                if (!declaredNames.contains(LOCAL_NAME)) {
                    checkLocalUse(scriptName, get, findings);
                }
            }

            return true;
        });

        for (Map.Entry<String, ObjectProperty> hook : definedHooks.entrySet()) {
            if (topLevelFunctions.contains(hook.getKey())) {
                findings.add(new ScriptFinding(scriptName, hook.getValue().getLineno(), Severity.ERROR,
                                               Exceptions.format("The hook {0} is both a top-level function and defined with defineHooks: the " +
                                                                 "module fails to load.", hook.getKey())));
            }
        }
    }

    /**
     * @return {@code true} if the call is a call of {@code defineHooks}, {@code false} otherwise
     */
    private static boolean isDefineHooks(@NotNull FunctionCall call) {
        return call.getTarget() instanceof Name target && target.getIdentifier().equals(DEFINE_HOOKS_NAME);
    }

    /**
     * Finds the name of an object literal property that is an identifier or a string.
     *
     * @return the name, or {@code null} if the property has a computed or numeric name
     */
    private static @Nullable String findPropertyName(@NotNull ObjectProperty property) {
        return switch (property.getLeft()) {
            case Name name -> name.getIdentifier();
            case StringLiteral literal -> literal.getValue();
            default -> null;
        };
    }

    /**
     * Reports what the runtime throws for in a {@code defineHooks} call with an object literal: a property whose name no hook has and a hook
     * that an earlier call defined. Collects the defined hooks by name.
     */
    private static void checkDefineHooks(@NotNull String scriptName, @NotNull FunctionCall call, @NotNull Map<String, ObjectProperty> definedHooks,
                                         @NotNull List<ScriptFinding> findings) {
        if (call.getArguments().isEmpty() || !(call.getArguments().getFirst() instanceof ObjectLiteral hooks)) {
            return;
        }

        for (ObjectProperty property : hooks.getElements()) {
            String hookName = findPropertyName(property);

            if (hookName == null) {
                continue;
            }

            if (ScriptHook.findByFunctionName(hookName) == null) {
                findings.add(new ScriptFinding(scriptName, property.getLineno(), Severity.ERROR,
                                               Exceptions.format("{0} is not a known hook: defineHooks throws.", hookName)));
            } else if (definedHooks.putIfAbsent(hookName, property) != null) {
                findings.add(new ScriptFinding(scriptName, property.getLineno(), Severity.ERROR,
                                               Exceptions.format("The hook {0} is defined twice with defineHooks: defineHooks throws.", hookName)));
            }
        }
    }

    /**
     * Reports a name under {@code core.dat} that the DAT tables ({@link ScriptDatTables}) do not have: a table, or a function of a table.
     */
    private static void checkDatName(@NotNull String scriptName, @NotNull PropertyGet get, @NotNull List<ScriptFinding> findings) {
        String name = get.getProperty().getIdentifier();

        if (isCoreDat(get.getTarget())) {
            if (ScriptDatTables.findTable(name) == null) {
                findings.add(new ScriptFinding(scriptName, get.getLineno(), Severity.ERROR, Exceptions.format("core.dat has no table {0}.", name)));
            }

            return;
        }

        if (!(get.getTarget() instanceof PropertyGet tableGet) || !isCoreDat(tableGet.getTarget())) {
            return;
        }

        ScriptDatTables.Table table = ScriptDatTables.findTable(tableGet.getProperty().getIdentifier());

        if (table == null) {
            return;
        }

        for (ScriptDatTables.Column column : table.getColumns()) {
            if (name.equals(GETTER_PREFIX + column.getName()) || column.isWritable() && name.equals(SETTER_PREFIX + column.getName())) {
                return;
            }
        }

        findings.add(new ScriptFinding(scriptName, get.getLineno(), Severity.ERROR,
                                       Exceptions.format("core.dat.{0} has no function {1}.", table.getName(), name)));
    }

    /**
     * Reports a use of the local information ({@code local.*}) that always runs as shared code, where it throws: at the top level of a module or
     * directly in a hook that is not a local hook, a top-level function or a method of an object literal given to {@code defineHooks} at the top
     * level. A use in a nested function is not reported.
     */
    private static void checkLocalUse(@NotNull String scriptName, @NotNull PropertyGet get, @NotNull List<ScriptFinding> findings) {
        if (!(get.getTarget() instanceof Name target) || !target.getIdentifier().equals(LOCAL_NAME)) {
            return;
        }

        FunctionNode function = get.getEnclosingFunction();
        String place = "the top level of a module";

        if (function != null) {
            Name functionName = function.getFunctionName();
            String hookName = null;

            if (function.getEnclosingFunction() == null && function.getParent() instanceof ObjectProperty property &&
                property.getRight() == function && property.getParent() instanceof ObjectLiteral literal &&
                literal.getParent() instanceof FunctionCall call && isDefineHooks(call)) {
                hookName = findPropertyName(property);
            } else if (function.getEnclosingFunction() == null && functionName != null) {
                hookName = functionName.getIdentifier();
            }

            ScriptHook hook = hookName != null ? ScriptHook.findByFunctionName(hookName) : null;

            if (hook == null || hook.isLocal()) {
                return;
            }

            place = hook.getFunctionName();
        }

        findings.add(new ScriptFinding(scriptName, get.getLineno(), Severity.ERROR,
                                       Exceptions.format("local.{0} throws in {1}, which is shared code: read it in a local hook (onLocal*, onRender).",
                                                         get.getProperty().getIdentifier(), place)));
    }

    /**
     * @return {@code true} if the node is {@code core.dat}, {@code false} otherwise
     */
    private static boolean isCoreDat(@NotNull AstNode node) {
        return node instanceof PropertyGet get && get.getTarget() instanceof Name target && target.getIdentifier().equals(ScriptGame.CORE_NAME) &&
               get.getProperty().getIdentifier().equals(DAT_NAMESPACE);
    }

    /**
     * Collects the names a script declares: variables, functions, parameters and catch variables.
     */
    private static @NotNull Set<String> collectDeclaredNames(@NotNull AstNode root) {
        Set<String> names = new HashSet<>();

        root.visit(node -> {
            if (node instanceof Name name && !isReference(name) && !(node.getParent() instanceof PropertyGet) &&
                !(node.getParent() instanceof ObjectProperty) && !(node.getParent() instanceof BreakStatement) &&
                !(node.getParent() instanceof ContinueStatement)) {
                names.add(name.getIdentifier());
            }

            return true;
        });

        return names;
    }

    /**
     * Checks whether a name is a reference to a variable: not a property name, a declaration or a label.
     */
    private static boolean isReference(@NotNull Name name) {
        AstNode parent = name.getParent();

        return switch (parent) {
            case PropertyGet get -> get.getProperty() != name;
            case ObjectProperty property -> property.getLeft() != name;
            case FunctionNode function -> function.getFunctionName() != name && !function.getParams().contains(name);
            case VariableInitializer initializer -> initializer.getTarget() != name;
            case CatchClause clause -> clause.getVarName() != name;
            case BreakStatement ignored -> false;
            case ContinueStatement ignored -> false;
            case null, default -> true;
        };
    }

    /**
     * Warns of a top-level function with the form of a hook name that no hook has, as the runtime warns when the script loads. Collects the names
     * of the top-level functions.
     */
    private static void checkHookFunction(@NotNull String scriptName, @NotNull FunctionNode function, @NotNull Set<String> topLevelFunctions,
                                          @NotNull List<ScriptFinding> findings) {
        Name functionName = function.getFunctionName();

        if (function.getFunctionType() == FunctionNode.FUNCTION_STATEMENT && functionName != null && function.getEnclosingFunction() == null) {
            topLevelFunctions.add(functionName.getIdentifier());
            checkHookName(scriptName, functionName.getIdentifier(), function.getLineno(), findings);
        }
    }

    private static void checkHookName(@NotNull String scriptName, @NotNull String functionName, int line, @NotNull List<ScriptFinding> findings) {
        if (ScriptHook.isHookName(functionName) && ScriptHook.findByFunctionName(functionName) == null) {
            findings.add(new ScriptFinding(scriptName, line, Severity.WARNING,
                                           Exceptions.format("{0} is not a known hook and is not called.", functionName)));
        }
    }

    /**
     * Warns of a function with a hook name assigned to {@code exports.name} or {@code module.exports.name}, as TypeScript writes an exported
     * function: the runtime calls it as a hook of the module, and a module that calls it runs it again. An assignment of {@code void 0}, also at
     * the end of a chain {@code exports.a = exports.b = void 0}, which TypeScript writes before the exports, is not reported.
     */
    private static void checkExportedHook(@NotNull String scriptName, @NotNull Assignment assignment, @NotNull Set<String> exportedHooks,
                                          @NotNull List<ScriptFinding> findings) {
        AstNode value = assignment.getRight();

        while (value instanceof Assignment chained) {
            value = chained.getRight();
        }

        if (!(assignment.getLeft() instanceof PropertyGet target) || value instanceof UnaryExpression unary && unary.getType() == Token.VOID) {
            return;
        }

        String name = target.getProperty().getIdentifier();
        boolean exports = target.getTarget() instanceof Name object && object.getIdentifier().equals(EXPORTS_NAME) ||
                          target.getTarget() instanceof PropertyGet moduleExports && moduleExports.getTarget() instanceof Name module &&
                          module.getIdentifier().equals(MODULE_NAME) && moduleExports.getProperty().getIdentifier().equals(EXPORTS_NAME);

        if (exports && ScriptHook.findByFunctionName(name) != null && exportedHooks.add(name)) {
            findings.add(new ScriptFinding(scriptName, assignment.getLineno(), Severity.WARNING,
                                           Exceptions.format("{0} is exported, but its name makes it a hook of this module: the runtime " +
                                                             "calls it besides any caller; give a helper another name.", name)));
        }
    }

    /**
     * Checks a variable declaration: a top-level variable that holds a function with the form of a hook name that no hook has, and a
     * {@code const} in a loop. Collects the names of the top-level variables that hold a function.
     */
    private static void checkDeclaration(@NotNull String scriptName, @NotNull VariableDeclaration declaration, @NotNull Set<String> topLevelFunctions,
                                         @NotNull List<ScriptFinding> findings) {
        boolean topLevel = declaration.getEnclosingFunction() == null && (declaration.isVar() || declaration.getParent() instanceof AstRoot);

        for (VariableInitializer variable : declaration.getVariables()) {
            if (topLevel && variable.getTarget() instanceof Name name && variable.getInitializer() instanceof FunctionNode) {
                topLevelFunctions.add(name.getIdentifier());
                checkHookName(scriptName, name.getIdentifier(), variable.getLineno(), findings);
            }
        }

        if (declaration.isConst() && findLoopOfBody(declaration) != null) {
            findings.add(new ScriptFinding(scriptName, declaration.getLineno(), Severity.WARNING,
                                           Exceptions.format("The const {0} in a loop keeps its first value in Rhino 1.8.1; use let.",
                                                             String.join(", ", collectTargetNames(declaration)))));
        }
    }

    /**
     * Finds the loop whose body holds a node, inside the node's function.
     *
     * @return the loop, or {@code null} if the node is in no loop body of its function
     */
    private static @Nullable Loop findLoopOfBody(@NotNull AstNode node) {
        AstNode child = node;

        for (AstNode parent = node.getParent(); parent != null && !(parent instanceof FunctionNode); parent = parent.getParent()) {
            if (parent instanceof Loop loop && loop.getBody() == child) {
                return loop;
            }

            child = parent;
        }

        return null;
    }

    /**
     * Warns of the functions in the body of a {@code for (let ...)} loop that use its variables: all iterations share one binding in
     * Rhino 1.8.1, so every such function sees the last value.
     */
    private static void checkLetLoop(@NotNull String scriptName, @NotNull Loop loop, @NotNull List<ScriptFinding> findings) {
        AstNode head = switch (loop) {
            case ForLoop forLoop -> forLoop.getInitializer();
            case ForInLoop forInLoop -> forInLoop.getIterator();
            default -> null;
        };

        if (!(head instanceof VariableDeclaration declaration) || !declaration.isLet() || loop.getBody() == null) {
            return;
        }

        Set<String> loopNames = collectTargetNames(declaration);

        loop.getBody().visit(node -> {
            if (!(node instanceof FunctionNode function)) {
                return true;
            }

            Set<String> functionNames = collectDeclaredNames(function);
            Set<String> usedNames = new LinkedHashSet<>();

            function.visit(inner -> {
                if (inner instanceof Name name && loopNames.contains(name.getIdentifier()) && isReference(name) &&
                    !functionNames.contains(name.getIdentifier())) {
                    usedNames.add(name.getIdentifier());
                }

                return true;
            });

            if (!usedNames.isEmpty()) {
                findings.add(new ScriptFinding(scriptName, function.getLineno(), Severity.WARNING,
                                               Exceptions.format("The function uses {0} of the for (let ...) loop at line {1}, which all iterations " +
                                                                 "share in Rhino 1.8.1; copy it into a let inside the loop body.",
                                                                 String.join(", ", usedNames), loop.getLineno())));
            }

            return false;
        });
    }

    /**
     * Collects the variable names of a declaration, with the names of destructuring targets.
     */
    private static @NotNull Set<String> collectTargetNames(@NotNull VariableDeclaration declaration) {
        Set<String> names = new LinkedHashSet<>();

        for (VariableInitializer variable : declaration.getVariables()) {
            variable.getTarget().visit(node -> {
                if (node instanceof Name name && (node == variable.getTarget() || node.getParent() instanceof ArrayLiteral ||
                                                  node.getParent() instanceof ObjectProperty property && property.getRight() == node)) {
                    names.add(name.getIdentifier());
                }

                return true;
            });
        }

        return names;
    }

    /**
     * Reads every {@value #GRP_EXTENSION}, {@value #ANIM_EXTENSION}, {@value #PNG_EXTENSION} and {@value #DDS_EXTENSION} resource as the runtime
     * reads a GRP, an anim or an image.
     */
    private static void checkResources(@NotNull Map<String, byte[]> resources, @NotNull List<ScriptFinding> findings) {
        ImageReplacements reader = new ImageReplacements(() -> null);

        for (Map.Entry<String, byte[]> resource : resources.entrySet()) {
            String name = resource.getKey();
            String lowerName = name.toLowerCase(Locale.ROOT);
            byte[] content = resource.getValue();

            try {
                if (lowerName.endsWith(GRP_EXTENSION)) {
                    reader.readGrp(content, 0, content.length, name);
                } else if (lowerName.endsWith(ANIM_EXTENSION)) {
                    reader.readAnim(content, 0, content.length, name);
                } else if (lowerName.endsWith(PNG_EXTENSION) || lowerName.endsWith(DDS_EXTENSION)) {
                    PictureFileUtils.read(content, name);
                }
            } catch (IOException | RuntimeException exception) {
                findings.add(new ScriptFinding(RESOURCE_FILE_PREFIX + name, ScriptFinding.NO_LINE, Severity.ERROR,
                                               Exceptions.format("The resource cannot be read: {0}", exception.getMessage())));
            }
        }
    }

    private ScriptCheckUtils() {
    }
}
