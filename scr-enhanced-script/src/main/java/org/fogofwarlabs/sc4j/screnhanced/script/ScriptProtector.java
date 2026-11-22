package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.*;
import org.mozilla.javascript.ast.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Protects the scripts of a protected map ({@code pack --protect}): the text of a script becomes short and hard to read and runs as the
 * original does.
 * <p>
 * The protector parses a script with Rhino's parser under the settings of the runtime, removes the comments and every whitespace that does not
 * separate two tokens, and renames the variables, parameters, functions and catch variables that a function or a block inside a function
 * declares, with their uses, to short names. It keeps every name that the runtime or another module looks up by name: the declarations at the
 * top level of a module (hooks, module variables and functions), {@code module.exports}, {@code exports} and {@code require}, the globals and the
 * script API, property names, {@code require} ids, labels and the text of literals. A shorthand property keeps its property name
 * ({@code {count}} becomes {@code {count:a}}).
 * <p>
 * No name is renamed in a function or block that holds or encloses a {@code with} statement or a direct {@code eval} call, nor inside a
 * {@code with} statement. A catch variable that has the name of a variable visible at the catch clause keeps its name, as does that variable.
 * The protector copies some syntax as it is and keeps every name the copied text holds: XML, legacy let expressions, comprehensions and
 * generator expressions, and the parameter list of a function with a destructured parameter that has a default value.
 * <p>
 * The protected text keeps the line of every token: each protected line holds tokens of one original line only, and a line break between two
 * tokens stays one line break, so that a protected line number, which Rhino errors carry, maps to one original line ({@link #getLines()}).
 * The protector parses the protected text again and refuses a result whose syntax tree differs from the original beyond the given names.
 */
public final class ScriptProtector {
    private static final int FIRST_LINE = 1;
    /**
     * The first characters of a given name; {@code $} is left out, as Rhino names its own variables with it.
     */
    private static final @NotNull String NAME_START_CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_";
    private static final @NotNull String NAME_PART_CHARACTERS = NAME_START_CHARACTERS + "0123456789";
    /**
     * The names the protector never gives: the reserved words of Rhino, and names with a meaning of their own.
     */
    private static final @NotNull Set<String> AVOIDED_NAMES = Set.of(
            "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "enum", "export", "extends",
            "false", "finally", "for", "function", "if", "import", "in", "instanceof", "new", "null", "return", "super", "switch", "this", "throw",
            "true", "try", "typeof", "var", "void", "while", "with", "yield", "let", "static", "implements", "interface", "package", "private",
            "protected", "public", "await", "async", "of", "get", "set", "abstract", "boolean", "byte", "char", "double", "final", "float",
            "goto", "int", "long", "native", "short", "synchronized", "throws", "transient", "volatile",
            "arguments", "eval", "undefined", "NaN", "Infinity"
    );
    /**
     * The characters next to which no whitespace is needed: they form no longer token with any neighbour.
     */
    private static final @NotNull String SEPARATOR_CHARACTERS = "(){}[];,";
    /**
     * The tokens of more than one punctuator character: two punctuator characters that are next to each other in one of them keep a space.
     */
    private static final @NotNull List<String> JOINED_TOKENS = List.of(
            "==", "===", "!=", "!==", "<=", ">=", "&&", "||", "??", "?.", "++", "--", "<<", ">>", ">>>", "+=", "-=", "*=", "/=", "%=", "&=", "|=",
            "^=", "<<=", ">>=", ">>>=", "**", "**=", "&&=", "||=", "??=", "=>", "...", "//", "/*", "*/", "<!--", "-->"
    );
    private static final @NotNull Set<String> JOINED_PAIRS = createJoinedPairs();
    private static final @NotNull String EVAL_NAME = "eval";
    private static final char SHORTHAND_SEPARATOR = ':';
    private static final char DEFAULT_VALUE_SIGN = '=';
    private static final char SPACE = ' ';
    private static final char LINE_BREAK = '\n';
    /**
     * The names in a text that the protector copies as it is; a backslash in such a text, which may escape a name, turns renaming off for the
     * whole script.
     */
    private static final @NotNull Pattern COPIED_NAME = Pattern.compile("[\\p{L}\\p{Nl}_$][\\p{L}\\p{Nl}\\p{Mn}\\p{Mc}\\p{Nd}\\p{Pc}_$]*");
    private static final char ESCAPE = '\\';
    private static final char UNICODE_ESCAPE = 'u';
    private static final char ESCAPE_BRACE_START = '{';
    private static final char ESCAPE_BRACE_END = '}';
    /**
     * The characters of a Unicode escape with four hex digits.
     */
    private static final int UNICODE_ESCAPE_LENGTH = 6;

    /**
     * How a name node of the tree is used.
     */
    private enum NameUse {
        /**
         * A property name, a label or a name that refers to nothing.
         */
        OTHER,
        /**
         * A variable: a declaration or a use.
         */
        VARIABLE,
        /**
         * A variable that is also the name of a shorthand property.
         */
        SHORTHAND_VARIABLE
    }

    /**
     * A declared name of a scope, or a global: its uses and the name it gets.
     */
    private static final class Binding {
        private final @NotNull String name;
        /**
         * The node that declares the name: a scope, a function (its own name), a catch clause, or {@code null} for a global.
         */
        private final @Nullable AstNode owner;
        private final boolean ownFunctionName;
        private final @NotNull List<Name> uses;
        /**
         * The nodes that declare names between the uses and the owner.
         */
        private final @NotNull Set<AstNode> passedOwners;

        private boolean renamable;
        private @NotNull String newName;

        private Binding(@NotNull String name, @Nullable AstNode owner, boolean ownFunctionName) {
            this.name            = name;
            this.owner           = owner;
            this.ownFunctionName = ownFunctionName;
            this.uses            = new ArrayList<>();
            this.passedOwners    = Collections.newSetFromMap(new IdentityHashMap<>());

            this.renamable = false;
            this.newName   = name;
        }

        private int getFirstPosition() {
            return this.uses.isEmpty() ? Integer.MAX_VALUE : this.uses.getFirst().getAbsolutePosition();
        }
    }

    /**
     * A part of the text with its own treatment: copied as it is (a literal or a copied text), replaced, or removed (a comment).
     */
    private static final class Span {
        private final int start;
        private final int end;
        private final @Nullable String replacement;
        private final boolean comment;

        private Span(int start, int end, @Nullable String replacement, boolean comment) {
            this.start       = start;
            this.end         = end;
            this.replacement = replacement;
            this.comment     = comment;
        }
    }

    /**
     * Protects a script.
     *
     * @param scriptName the script name, used in messages and as the source name of the parser
     * @param source     the script text
     * @return the protector with the protected text, its lines and the given names
     * @throws IOException if the script has a syntax error, or the protected text does not parse to the same program
     */
    public static @NotNull ScriptProtector protect(@NotNull String scriptName, @NotNull String source) throws IOException {
        ScriptProtector protector = new ScriptProtector(scriptName, source, parse(scriptName, source, true));

        protector.run();

        return protector;
    }

    /**
     * Protects every script of a package and adds their maps to a source map.
     *
     * @param scriptPackage the package
     * @param sourceMap     receives the map of each script
     * @return the package with the protected scripts, the same manifest and the same resources
     * @throws IOException if a script cannot be protected ({@link #protect})
     */
    public static @NotNull ScriptPackage protectPackage(@NotNull ScriptPackage scriptPackage, @NotNull ScriptSourceMap sourceMap) throws IOException {
        Map<String, byte[]> scripts = new LinkedHashMap<>();

        for (Map.Entry<String, byte[]> script : scriptPackage.getScripts().entrySet()) {
            ScriptProtector protector = protect(script.getKey(), new String(script.getValue(), StandardCharsets.UTF_8));

            scripts.put(script.getKey(), protector.getText().getBytes(StandardCharsets.UTF_8));
            sourceMap.addScript(script.getKey(), protector.getLines(), protector.getRenames());
        }

        return ScriptPackage.create(scriptPackage.getManifest(), scripts, scriptPackage.getResources());
    }

    private static @NotNull Set<String> createJoinedPairs() {
        Set<String> pairs = new HashSet<>();

        for (String token : JOINED_TOKENS) {
            for (int index = 0; index + 1 < token.length(); index++) {
                pairs.add(token.substring(index, index + 2));
            }
        }

        return Set.copyOf(pairs);
    }

    /**
     * Parses a script with the settings of the runtime.
     *
     * @throws IOException if the script has a syntax error
     */
    private static @NotNull AstRoot parse(@NotNull String scriptName, @NotNull String source, boolean recordComments) throws IOException {
        try (Context context = new ContextFactory().enterContext()) {
            CompilerEnvirons environment = new CompilerEnvirons();

            context.setInterpretedMode(true);
            context.setLanguageVersion(Context.VERSION_ECMASCRIPT);
            environment.initFromContext(context);
            environment.setRecordingComments(recordComments);

            return new Parser(environment).parse(source, scriptName, FIRST_LINE);
        } catch (EvaluatorException exception) {
            throw Exceptions.newIOException(exception, "The script {0} cannot be protected: {1} (line {2})", scriptName, exception.details(),
                                            exception.lineNumber());
        }
    }

    /**
     * Visits a tree in source order together with the default values of the parameters, which Rhino keeps outside the tree, each right
     * after its function node.
     */
    private static void visitTree(@NotNull AstNode node, @NotNull NodeVisitor visitor) {
        node.visit(child -> {
            if (!visitor.visit(child)) {
                return false;
            }

            if (child instanceof FunctionNode function) {
                for (AstNode value : getDefaultValues(function)) {
                    visitTree(value, visitor);
                }
            }

            return true;
        });
    }

    private static @NotNull List<AstNode> getDefaultValues(@NotNull FunctionNode function) {
        List<AstNode> values = new ArrayList<>();

        if (function.getDefaultParams() != null) {
            for (Object item : function.getDefaultParams()) {
                if (item instanceof AstNode value) {
                    values.add(value);
                }
            }
        }

        return values;
    }

    /**
     * Lists the nodes of a tree in visiting order ({@link #visitTree}), without comments.
     */
    private static @NotNull List<AstNode> listNodes(@NotNull AstRoot root) {
        List<AstNode> nodes = new ArrayList<>();

        visitTree(root, node -> {
            if (node instanceof Comment) {
                return false;
            }

            nodes.add(node);

            return true;
        });

        return nodes;
    }

    /**
     * @return {@code true} if the protector copies the node as it is, {@code false} otherwise
     */
    private static boolean isCopied(@NotNull AstNode node) {
        return node instanceof XmlLiteral || node instanceof XmlFragment || node instanceof XmlRef || node instanceof LetNode ||
               node instanceof ArrayComprehension || node instanceof GeneratorExpression;
    }

    private static boolean isLineBreak(char character) {
        return character == '\n' || character == '\r' || character == ' ' || character == ' ';
    }

    private static boolean isWhitespace(char character) {
        return character == ' ' || character == '\t' || character == '\u000B' || character == '\f' || character == ' ' ||
               character == '﻿' || Character.getType(character) == Character.SPACE_SEPARATOR;
    }

    /**
     * @return {@code true} if the character may be part of a name or a number, {@code false} if it is a punctuator or a quote
     */
    private static boolean isNamePart(char character) {
        return Character.isUnicodeIdentifierPart(character) || character == '$' || character == ESCAPE || Character.isSurrogate(character);
    }

    /**
     * @return {@code true} if two characters that a whitespace separated need a space to stay two tokens, {@code false} otherwise
     */
    private static boolean needsSpace(char left, char right) {
        if (SEPARATOR_CHARACTERS.indexOf(left) >= 0 || SEPARATOR_CHARACTERS.indexOf(right) >= 0) {
            return false;
        }

        boolean leftNamePart = isNamePart(left);
        boolean rightNamePart = isNamePart(right);

        if (leftNamePart && rightNamePart) {
            return true;
        }

        if (leftNamePart || rightNamePart) {
            // A number and a dot: "1 .x" must not become the number "1.".
            return left == '.' && Character.isDigit(right) || right == '.' && Character.isDigit(left);
        }

        return JOINED_PAIRS.contains(String.valueOf(left) + right);
    }

    /**
     * Finds the start of each line of a text; a line ends with a line feed, a carriage return, both, or a line or paragraph separator.
     */
    private static int @NotNull [] findLineStarts(@NotNull String text) {
        List<Integer> starts = new ArrayList<>();

        starts.add(0);

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);

            if (character == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                continue;
            }

            if (isLineBreak(character)) {
                starts.add(index + 1);
            }
        }

        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * @return the line, from {@value #FIRST_LINE}, of an offset
     */
    private static int findLine(int @NotNull [] lineStarts, int offset) {
        int index = Arrays.binarySearch(lineStarts, offset);

        return (index >= 0 ? index : -index - 2) + FIRST_LINE;
    }

    /**
     * Creates the given name of an index: the names of one character first, then those of two, and so on.
     */
    private static @NotNull String createName(int index) {
        StringBuilder name = new StringBuilder();
        int rest = index / NAME_START_CHARACTERS.length();

        name.append(NAME_START_CHARACTERS.charAt(index % NAME_START_CHARACTERS.length()));

        while (rest > 0) {
            rest--;
            name.append(NAME_PART_CHARACTERS.charAt(rest % NAME_PART_CHARACTERS.length()));
            rest /= NAME_PART_CHARACTERS.length();
        }

        return name.toString();
    }


    private final @NotNull String scriptName;
    private final @NotNull String source;
    private final @NotNull AstRoot root;

    /**
     * The declared names of each node that declares names, in visiting order.
     */
    private final @NotNull Map<AstNode, Map<String, Binding>> bindingsByOwner;
    private final @NotNull List<AstNode> owners;
    private final @NotNull Map<String, Binding> globals;
    /**
     * The function of each default value of a parameter.
     */
    private final @NotNull Map<AstNode, FunctionNode> defaultValueFunctions;
    /**
     * The nodes in which no name is renamed.
     */
    private final @NotNull Set<AstNode> fixedOwners;
    private final @NotNull Set<String> keptNames;
    private final @NotNull List<Name> names;
    private final @NotNull List<AstNode> withStatements;
    private final @NotNull List<AstNode> evalCalls;
    private final @NotNull Map<Name, Binding> bindingsByName;
    private final @NotNull Set<Name> shorthandNames;
    private final @NotNull TreeMap<Integer, Span> spans;
    private boolean renamingOff;

    private @NotNull String text;
    private int @NotNull [] lines;
    private @NotNull List<ScriptSourceMap.Rename> renames;

    /**
     * Creates a {@link ScriptProtector} instance that has not protected the script yet.
     *
     * @param scriptName the script name
     * @param source     the script text
     * @param root       the tree of the script, with its comments
     */
    private ScriptProtector(@NotNull String scriptName, @NotNull String source, @NotNull AstRoot root) {
        this.scriptName = scriptName;
        this.source     = source;
        this.root       = root;

        this.bindingsByOwner       = new IdentityHashMap<>();
        this.owners                = new ArrayList<>();
        this.globals               = new LinkedHashMap<>();
        this.defaultValueFunctions = new IdentityHashMap<>();
        this.fixedOwners           = Collections.newSetFromMap(new IdentityHashMap<>());
        this.keptNames             = new HashSet<>();
        this.names                 = new ArrayList<>();
        this.withStatements        = new ArrayList<>();
        this.evalCalls             = new ArrayList<>();
        this.bindingsByName        = new IdentityHashMap<>();
        this.shorthandNames        = Collections.newSetFromMap(new IdentityHashMap<>());
        this.spans                 = new TreeMap<>();
        this.renamingOff           = false;

        this.text    = source;
        this.lines   = new int[0];
        this.renames = List.of();
    }

    /**
     * @return the protected text
     */
    public @NotNull String getText() {
        return this.text;
    }

    /**
     * @return the original line of each protected line: element {@code i} for the protected line {@code i + 1}; the array is not copied
     */
    public int @NotNull [] getLines() {
        return this.lines;
    }

    /**
     * @return the names the protector gave, by original line
     */
    public @NotNull List<ScriptSourceMap.Rename> getRenames() {
        return this.renames;
    }


    private void run() throws IOException {
        this.collect();
        this.fixDynamicScopes();
        this.resolveNames();
        this.selectRenamable();
        this.assignNames();
        this.addRenameSpans();
        this.write();
        this.check();
    }

    /**
     * Collects the declared names of the scopes, the names, the literals, the comments and the copied texts of the tree.
     */
    private void collect() {
        visitTree(this.root, node -> {
            if (node instanceof Comment) {
                return false;
            }

            if (isCopied(node)) {
                this.addCopiedSpan(node.getAbsolutePosition(), node.getAbsolutePosition() + node.getLength());

                return false;
            }

            if (node instanceof FunctionNode function) {
                for (AstNode value : getDefaultValues(function)) {
                    this.defaultValueFunctions.put(value, function);
                }

                if (this.hasDestructuredDefault(function)) {
                    this.addCopiedSpan(function.getAbsolutePosition(), function.getBody().getAbsolutePosition());
                }
            }

            if (node instanceof Scope scope && scope.getSymbolTable() != null) {
                for (String symbolName : scope.getSymbolTable().keySet()) {
                    this.addBinding(new Binding(symbolName, scope, false));
                }
            }

            if (node instanceof FunctionNode function && function.getFunctionType() == FunctionNode.FUNCTION_EXPRESSION &&
                function.getFunctionName() != null && (function.getSymbolTable() == null ||
                                                       !function.getSymbolTable().containsKey(function.getFunctionName().getIdentifier()))) {
                // Rhino binds the name of a function expression inside the function unless the function declares the name itself.
                this.addBinding(new Binding(function.getFunctionName().getIdentifier(), function, true));
            }

            if (node instanceof StringLiteral || node instanceof RegExpLiteral || node instanceof TemplateCharacters && node.getLength() > 0) {
                this.addSpan(new Span(node.getAbsolutePosition(), node.getAbsolutePosition() + node.getLength(), null, false));
            }

            switch (node) {
                case CatchClause clause when clause.getVarName() != null -> {
                    this.addBinding(new Binding(clause.getVarName().getIdentifier(), clause, false));
                }
                case WithStatement statement -> this.withStatements.add(statement);
                case FunctionCall call when call.getTarget() instanceof Name target && target.getIdentifier().equals(EVAL_NAME) -> {
                    this.evalCalls.add(call);
                }
                case Name name -> this.names.add(name);
                default -> {
                }
            }

            return true;
        });

        if (this.root.getComments() != null) {
            for (Comment comment : this.root.getComments()) {
                this.addSpan(new Span(comment.getAbsolutePosition(), comment.getAbsolutePosition() + comment.getLength(), null, true));
            }
        }
    }

    private void addBinding(@NotNull Binding binding) {
        AstNode owner = binding.owner;
        Map<String, Binding> bindings = this.bindingsByOwner.get(owner);

        if (bindings == null) {
            bindings = new LinkedHashMap<>();
            this.bindingsByOwner.put(owner, bindings);
            this.owners.add(owner);
        }

        bindings.putIfAbsent(binding.name, binding);
    }

    /**
     * @return {@code true} if a destructured parameter of the function has a default value, which Rhino keeps out of the tree, {@code false} otherwise
     */
    private boolean hasDestructuredDefault(@NotNull FunctionNode function) {
        for (AstNode parameter : function.getParams()) {
            if (!(parameter instanceof ObjectLiteral) && !(parameter instanceof ArrayLiteral)) {
                continue;
            }

            int index = parameter.getAbsolutePosition() + parameter.getLength();

            while (index < this.source.length() && (isWhitespace(this.source.charAt(index)) || isLineBreak(this.source.charAt(index)))) {
                index++;
            }

            if (index < this.source.length() && this.source.charAt(index) == DEFAULT_VALUE_SIGN) {
                return true;
            }
        }

        return false;
    }

    /**
     * Adds a text the protector copies as it is and keeps the names it holds.
     */
    private void addCopiedSpan(int start, int end) {
        String copied = this.source.substring(start, end);
        Matcher matcher = COPIED_NAME.matcher(copied);

        while (matcher.find()) {
            this.keptNames.add(matcher.group());
        }

        if (copied.indexOf(ESCAPE) >= 0) {
            this.renamingOff = true;
        }

        this.addSpan(new Span(start, end, null, false));
    }

    /**
     * Adds a span; of two spans that start at the same offset the longer one stays.
     */
    private void addSpan(@NotNull Span span) {
        Span existing = this.spans.get(span.start);

        if (existing == null || existing.end < span.end) {
            this.spans.put(span.start, span);
        }
    }

    /**
     * Fixes the names of the scopes that a {@code with} statement or a direct {@code eval} call can reach: the scopes that enclose it, and for
     * a {@code with} statement every scope inside it.
     */
    private void fixDynamicScopes() {
        List<AstNode> dynamicNodes = new ArrayList<>(this.withStatements);

        dynamicNodes.addAll(this.evalCalls);

        for (AstNode node : dynamicNodes) {
            for (AstNode parent = this.getLexicalParent(node); parent != null; parent = this.getLexicalParent(parent)) {
                this.fixedOwners.add(parent);
            }
        }

        for (AstNode statement : this.withStatements) {
            visitTree(statement, node -> {
                this.fixedOwners.add(node);

                return true;
            });
        }
    }

    /**
     * Finds the binding of every variable name of the tree and the declaring nodes between the name and its binding.
     */
    private void resolveNames() {
        for (Name name : this.names) {
            NameUse use = this.findUse(name);

            if (use == NameUse.OTHER || this.bindingsByName.containsKey(name)) {
                continue;
            }

            Binding binding = this.resolve(name);

            if (binding == null) {
                continue;
            }

            binding.uses.add(name);
            this.bindingsByName.put(name, binding);

            if (use == NameUse.SHORTHAND_VARIABLE) {
                this.shorthandNames.add(name);
            }
        }
    }

    /**
     * Finds how a name node is used.
     */
    private @NotNull NameUse findUse(@NotNull Name name) {
        return switch (name.getParent()) {
            case PropertyGet get when get.getProperty() == name -> NameUse.OTHER;
            case ObjectProperty property when property.getLeft() == name -> {
                // {name = value} in a destructuring pattern: the property name is also the variable.
                if (!property.isShorthand() && property.getRight() instanceof Assignment assignment && assignment.getLeft() == name) {
                    yield NameUse.SHORTHAND_VARIABLE;
                }

                yield NameUse.OTHER;
            }
            case ObjectProperty property when property.getRight() == name -> property.isShorthand() ? NameUse.SHORTHAND_VARIABLE : NameUse.VARIABLE;
            case BreakStatement ignored -> NameUse.OTHER;
            case ContinueStatement ignored -> NameUse.OTHER;
            case null, default -> NameUse.VARIABLE;
        };
    }

    /**
     * Finds the binding of a variable name and records the declaring nodes it passes.
     *
     * @return the binding, or {@code null} if the name is the name of a function expression that the function itself declares again
     */
    private @Nullable Binding resolve(@NotNull Name name) {
        String identifier = name.getIdentifier();

        if (name.getParent() instanceof FunctionNode function && function.getFunctionName() == name) {
            if (function.getFunctionType() == FunctionNode.FUNCTION_EXPRESSION) {
                Map<String, Binding> bindings = this.bindingsByOwner.get(function);
                Binding binding = bindings == null ? null : bindings.get(identifier);

                return binding != null && binding.ownFunctionName ? binding : null;
            }

            return this.resolveFrom(this.getLexicalParent(function), identifier);
        }

        if (name.getParent() instanceof CatchClause clause && clause.getVarName() == name) {
            return this.bindingsByOwner.get(clause).get(identifier);
        }

        return this.resolveFrom(this.getLexicalParent(name), identifier);
    }

    private @NotNull Binding resolveFrom(@Nullable AstNode start, @NotNull String identifier) {
        List<AstNode> passed = new ArrayList<>();

        for (AstNode node = start; node != null; node = this.getLexicalParent(node)) {
            Map<String, Binding> bindings = this.bindingsByOwner.get(node);

            if (bindings == null) {
                continue;
            }

            Binding binding = bindings.get(identifier);

            if (binding != null) {
                binding.passedOwners.addAll(passed);

                return binding;
            }

            passed.add(node);
        }

        Binding global = this.globals.computeIfAbsent(identifier, globalName -> new Binding(globalName, null, false));

        global.passedOwners.addAll(passed);

        return global;
    }

    /**
     * @return the parent of a node in the source, which is the function for a default value of a parameter
     */
    private @Nullable AstNode getLexicalParent(@NotNull AstNode node) {
        FunctionNode function = this.defaultValueFunctions.get(node);

        return function != null ? function : node.getParent();
    }

    /**
     * Selects the bindings to rename: used names that a function or a block inside a function declares, outside the fixed scopes, that no copied
     * text holds and that have no meaning of their own; a catch variable and a visible variable of the same name both keep it.
     */
    private void selectRenamable() {
        if (this.renamingOff) {
            return;
        }

        for (AstNode owner : this.owners) {
            boolean local = !this.fixedOwners.contains(owner) && this.isInFunction(owner);

            for (Binding binding : this.bindingsByOwner.get(owner).values()) {
                binding.renamable = local && !binding.uses.isEmpty() && !this.keptNames.contains(binding.name) &&
                                    !AVOIDED_NAMES.contains(binding.name);
            }
        }

        for (AstNode owner : this.owners) {
            if (!(owner instanceof CatchClause)) {
                continue;
            }

            for (Binding binding : this.bindingsByOwner.get(owner).values()) {
                for (AstNode node = this.getLexicalParent(owner); node != null; node = this.getLexicalParent(node)) {
                    Map<String, Binding> bindings = this.bindingsByOwner.get(node);
                    Binding hidden = bindings == null ? null : bindings.get(binding.name);

                    if (hidden != null) {
                        hidden.renamable  = false;
                        binding.renamable = false;
                    }
                }
            }
        }
    }

    private boolean isInFunction(@NotNull AstNode owner) {
        for (AstNode node = owner; node != null; node = this.getLexicalParent(node)) {
            if (node instanceof FunctionNode) {
                return true;
            }
        }

        return false;
    }

    /**
     * Gives each renamable binding the first name, outer declarations first, that no other binding reachable from one of its uses has, nor any
     * binding the uses inside its scope reach through it.
     */
    private void assignNames() {
        Map<AstNode, Set<Binding>> passingBindings = new IdentityHashMap<>();

        for (Map<String, Binding> bindings : this.bindingsByOwner.values()) {
            for (Binding binding : bindings.values()) {
                for (AstNode owner : binding.passedOwners) {
                    passingBindings.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(binding);
                }
            }
        }

        for (Binding global : this.globals.values()) {
            for (AstNode owner : global.passedOwners) {
                passingBindings.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(global);
            }
        }

        for (AstNode owner : this.owners) {
            Set<String> taken = new HashSet<>();
            List<Binding> renamed = new ArrayList<>();

            for (Binding binding : this.bindingsByOwner.get(owner).values()) {
                if (binding.renamable) {
                    renamed.add(binding);
                } else {
                    taken.add(binding.name);
                }
            }

            if (renamed.isEmpty()) {
                continue;
            }

            for (Binding passing : passingBindings.getOrDefault(owner, Set.of())) {
                taken.add(passing.newName);
            }

            renamed.sort(Comparator.comparingInt(Binding::getFirstPosition));

            for (Binding binding : renamed) {
                Set<String> hidden = new HashSet<>();

                for (AstNode passedOwner : binding.passedOwners) {
                    for (Binding inner : this.bindingsByOwner.get(passedOwner).values()) {
                        if (!inner.renamable) {
                            hidden.add(inner.name);
                        }
                    }
                }

                binding.newName = this.findFreeName(taken, hidden);
                taken.add(binding.newName);
            }
        }
    }

    private @NotNull String findFreeName(@NotNull Set<String> taken, @NotNull Set<String> hidden) {
        for (int index = 0; ; index++) {
            String candidate = createName(index);

            if (!AVOIDED_NAMES.contains(candidate) && !this.keptNames.contains(candidate) && !taken.contains(candidate) &&
                !hidden.contains(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * Adds the replacement of every use of a renamed binding and lists the given names.
     *
     * @throws IOException if the text of a name node is not its name
     */
    private void addRenameSpans() throws IOException {
        List<ScriptSourceMap.Rename> givenNames = new ArrayList<>();
        int[] lineStarts = findLineStarts(this.source);

        for (AstNode owner : this.owners) {
            for (Binding binding : this.bindingsByOwner.get(owner).values()) {
                if (!binding.renamable || binding.newName.equals(binding.name)) {
                    continue;
                }

                for (Name use : binding.uses) {
                    int start = use.getAbsolutePosition();
                    int end = this.findNameEnd(start);
                    String original = this.source.substring(start, end);

                    if (!original.equals(binding.name) && original.indexOf(ESCAPE) < 0) {
                        throw Exceptions.newIOException("The script {0} cannot be protected: the name {1} at line {2} is not at its place.",
                                                        this.scriptName, binding.name, use.getLineno());
                    }

                    String replacement = this.shorthandNames.contains(use) ? original + SHORTHAND_SEPARATOR + binding.newName : binding.newName;

                    this.spans.put(start, new Span(start, end, replacement, false));
                }

                givenNames.add(new ScriptSourceMap.Rename(binding.newName, binding.name, findLine(lineStarts, binding.getFirstPosition())));
            }
        }

        givenNames.sort(Comparator.comparingInt(ScriptSourceMap.Rename::getLine).thenComparing(ScriptSourceMap.Rename::getName));

        this.renames = List.copyOf(givenNames);
    }

    /**
     * Finds the end of a name in the text, with its Unicode escapes (a backslash, {@code u} and four hex digits or hex digits in braces): Rhino
     * gives a name node the length of the name without escapes.
     */
    private int findNameEnd(int start) {
        int index = start;

        while (index < this.source.length()) {
            char character = this.source.charAt(index);

            if (character == ESCAPE && index + 1 < this.source.length() && this.source.charAt(index + 1) == UNICODE_ESCAPE) {
                int braceEnd = this.source.indexOf(ESCAPE_BRACE_END, index);

                index = index + 2 < this.source.length() && this.source.charAt(index + 2) == ESCAPE_BRACE_START && braceEnd > index
                        ? braceEnd + 1
                        : index + UNICODE_ESCAPE_LENGTH;
            } else if (character != ESCAPE && isNamePart(character)) {
                index++;
            } else {
                break;
            }
        }

        return Math.min(index, this.source.length());
    }

    /**
     * Writes the protected text and the original line of each of its lines.
     */
    private void write() {
        StringBuilder output = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        int index = 0;
        boolean gap = false;
        boolean gapLineBreak = false;

        while (index < this.source.length()) {
            Span span = this.spans.get(index);
            char character = this.source.charAt(index);

            if (span != null && span.comment) {
                gap   = true;
                gapLineBreak |= this.source.substring(span.start, span.end).chars().anyMatch(value -> isLineBreak((char) value));
                index = span.end;
                continue;
            }

            if (span == null && (isWhitespace(character) || isLineBreak(character))) {
                gap = true;
                gapLineBreak |= isLineBreak(character);
                index++;
                continue;
            }

            String part = span == null
                          ? String.valueOf(character)
                          : span.replacement != null ? span.replacement : this.source.substring(span.start, span.end);

            if (gap && !output.isEmpty()) {
                if (gapLineBreak) {
                    output.append(LINE_BREAK);
                    offsets.add(offsets.getLast());
                } else if (needsSpace(output.charAt(output.length() - 1), part.charAt(0))) {
                    output.append(SPACE);
                    offsets.add(offsets.getLast());
                }
            }

            gap          = false;
            gapLineBreak = false;

            for (int partIndex = 0; partIndex < part.length(); partIndex++) {
                offsets.add(span == null || span.replacement != null ? index : index + partIndex);
            }

            output.append(part);
            index = span == null ? index + 1 : span.end;
        }

        int[] sourceLineStarts = findLineStarts(this.source);
        int[] outputLineStarts = findLineStarts(output.toString());
        int lineCount = outputLineStarts.length;

        // A last line without characters, after a final line break of a copied text, belongs to the line before it.
        if (lineCount > 1 && outputLineStarts[lineCount - 1] == output.length()) {
            lineCount--;
        }

        this.text  = output.toString();
        this.lines = new int[output.isEmpty() ? 0 : lineCount];

        for (int line = 0; line < this.lines.length; line++) {
            this.lines[line] = findLine(sourceLineStarts, offsets.get(outputLineStarts[line]));
        }
    }

    /**
     * Parses the protected text and compares its tree with the original one: the same nodes in the same order, the given names in place of
     * the renamed ones, the same literals, and the names and literals on the lines that the line map gives.
     *
     * @throws IOException if the protected text does not parse or its tree differs
     */
    private void check() throws IOException {
        List<AstNode> originalNodes = listNodes(this.root);
        List<AstNode> protectedNodes = listNodes(parse(this.scriptName, this.text, false));
        Map<Name, Integer> visits = new IdentityHashMap<>();

        for (int index = 0; index < Math.min(originalNodes.size(), protectedNodes.size()); index++) {
            AstNode original = originalNodes.get(index);
            AstNode protectedNode = protectedNodes.get(index);
            boolean same = original.getClass() == protectedNode.getClass() && original.getType() == protectedNode.getType();

            if (same && original instanceof Name name) {
                int visit = visits.merge(name, 1, Integer::sum);

                same = ((Name) protectedNode).getIdentifier().equals(this.findExpectedName(name, visit));
            } else if (same) {
                same = this.isSameLiteral(original, protectedNode);
            }

            if (same && (original instanceof Name || isCopied(original) || original instanceof StringLiteral || original instanceof NumberLiteral)) {
                int line = protectedNode.getLineno();

                same = line >= FIRST_LINE && line <= this.lines.length && this.lines[line - FIRST_LINE] == original.getLineno();
            }

            if (!same) {
                throw Exceptions.newIOException("The script {0} cannot be protected: the protected text differs from line {1} on.", this.scriptName,
                                                original.getLineno());
            }
        }

        if (originalNodes.size() != protectedNodes.size()) {
            throw Exceptions.newIOException("The script {0} cannot be protected: the protected text has {1} syntax nodes, the original {2}.",
                                            this.scriptName, protectedNodes.size(), originalNodes.size());
        }
    }

    /**
     * Finds the name a name node has in the protected text on its given visit: the name of a destructuring default {@code {name = value}} is
     * visited first as the property name, which stays, then as the variable.
     */
    private @NotNull String findExpectedName(@NotNull Name name, int visit) {
        Binding binding = this.bindingsByName.get(name);

        if (binding == null || !binding.renamable) {
            return name.getIdentifier();
        }

        boolean propertyVisit = name.getParent() instanceof ObjectProperty property && property.getLeft() == name && visit == 1;

        return propertyVisit ? name.getIdentifier() : binding.newName;
    }

    private boolean isSameLiteral(@NotNull AstNode original, @NotNull AstNode protectedNode) {
        return switch (original) {
            case StringLiteral literal -> literal.getValue().equals(((StringLiteral) protectedNode).getValue()) &&
                                          literal.getQuoteCharacter() == ((StringLiteral) protectedNode).getQuoteCharacter();
            case NumberLiteral literal -> literal.getValue().equals(((NumberLiteral) protectedNode).getValue());
            case RegExpLiteral literal -> literal.getValue().equals(((RegExpLiteral) protectedNode).getValue()) &&
                                          String.valueOf(literal.getFlags()).equals(String.valueOf(((RegExpLiteral) protectedNode).getFlags()));
            case TemplateCharacters characters -> characters.getRawValue().equals(((TemplateCharacters) protectedNode).getRawValue());
            default -> true;
        };
    }
}
