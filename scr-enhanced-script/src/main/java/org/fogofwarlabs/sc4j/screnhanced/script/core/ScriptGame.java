package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrButtonInput;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrDrawList;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.SimulationDecision;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.SimulationEvent;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.map.MapStringSection;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerModifiers;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ConsoleSettings;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageReplacements;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptResources;
import org.fogofwarlabs.sc4j.screnhanced.script.input.ScriptButtonPress;
import org.fogofwarlabs.sc4j.screnhanced.script.input.ScriptLocalInput;
import org.fogofwarlabs.sc4j.screnhanced.script.sync.ScriptCommandMessage;
import org.fogofwarlabs.sc4j.screnhanced.script.sync.ScriptCommandOutbox;
import org.fogofwarlabs.sc4j.screnhanced.script.sync.ScriptValueUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Binds a script runtime to one SC:R {@link Game}: the {@code core} global, the lower layer of the game API, and the shared, local and
 * read-only modes of its functions.
 * <p>
 * The namespaces of {@code core}, {@code game}, {@code player}, {@code unit}, {@code location}, {@code terrain}, {@code creep},
 * {@code sprite}, {@code image}, {@code bullet}, {@code vision}, {@code leaderboard}, {@code combat}, {@code ai}, {@code requirements},
 * {@code unitType}, {@code weaponType}, {@code flingyType}, {@code spriteType}, {@code imageType}, {@code upgradeType}, {@code techType},
 * {@code orderType}, {@code dat}, {@code events}, {@code command}, {@code resources}, {@code graphics}, {@code local}, {@code screen},
 * {@code world}, {@code minimap}, {@code console}, {@code strings}, {@code messages}, {@code sound} and {@code buttons}, take and return ids and
 * raw values (hit points, shields and energy in {@code 1/256} units, supply in half units, DAT values as the tables hold them) and plain
 * records of them; a write takes the routine that the trigger action or the order system runs for it. A text that a routine shows as a map
 * string takes a registered map string ({@link #findStringIndex}). No function takes a callback.
 * <p>
 * Every function belongs to shared code, to local code or to both. The functions that change the game state ({@link #checkWritable()}) and
 * the frame's events and commands ({@link ScriptEventCore}, {@link ScriptCommandCore}, {@link #checkShared()}) work in shared code only; the
 * local information ({@link ScriptLocalCore}) and the drawing and screen of {@code core.screen} ({@link ScriptScreenCore},
 * {@link #checkLocal()}) in local code only; the reads, the presentation (the graphics replacements of {@link ScriptGraphicsCore}, the
 * console, the texts, the messages, the sounds, the player colors and the screen pixels) and {@code core.command.submit} anywhere. A call in
 * the wrong code throws a {@code TypeError}. While the game is read-only, as while a decision runs, the functions that change the game state
 * throw a {@code TypeError} as well.
 */
public final class ScriptGame {
    /**
     * The name of the lower layer's global.
     */
    public static final @NotNull String CORE_NAME = "core";

    private static final int CORE_ATTRIBUTES = ScriptableObject.READONLY | ScriptableObject.DONTENUM | ScriptableObject.PERMANENT;
    /**
     * The number of switches of the trigger state.
     */
    private static final int SWITCH_COUNT = 256;
    /**
     * The string index of no map string, which an empty text takes.
     */
    private static final int NO_STRING_INDEX = 0;

    /**
     * Creates the {@code core} global of a game.
     *
     * @param context   the current context
     * @param global    the global scope
     * @param game      the game
     * @param resources the resources of the map
     * @return the created {@link ScriptGame} instance
     */
    public static @NotNull ScriptGame install(@NotNull Context context, @NotNull ScriptableObject global, @NotNull Game game,
                                              @NotNull ScriptResources resources) {
        ScriptableObject core = (ScriptableObject) context.newObject(global);
        ScriptGame scriptGame = new ScriptGame(game, global, core);
        ScriptUnitCore unitCore = new ScriptUnitCore(scriptGame);
        ScriptSpriteCore spriteCore = new ScriptSpriteCore(scriptGame);
        ScriptGraphicsCore graphicsCore = new ScriptGraphicsCore(scriptGame, unitCore, spriteCore, resources);
        ScriptScreenCore screenCore = new ScriptScreenCore(scriptGame, graphicsCore);
        ScriptTextCore textCore = new ScriptTextCore(scriptGame);

        ScriptableObject gameNamespace = ScriptCoreUtils.createNamespace(context, global, core, "game");

        scriptGame.defineGameFunctions(gameNamespace, global);
        new ScriptGameSettingsCore(scriptGame).define(gameNamespace, global);
        new ScriptPlayerCore(scriptGame).define(ScriptCoreUtils.createNamespace(context, global, core, "player"), global);
        unitCore.define(ScriptCoreUtils.createNamespace(context, global, core, "unit"), global);
        new ScriptLocationCore(scriptGame).define(ScriptCoreUtils.createNamespace(context, global, core, "location"), global);
        new ScriptTerrainCore(scriptGame).define(ScriptCoreUtils.createNamespace(context, global, core, "terrain"), global);
        new ScriptCreepCore(scriptGame).define(ScriptCoreUtils.createNamespace(context, global, core, "creep"), global);
        spriteCore.defineSprites(ScriptCoreUtils.createNamespace(context, global, core, "sprite"), global);
        spriteCore.defineImages(ScriptCoreUtils.createNamespace(context, global, core, "image"), global);
        new ScriptBulletCore(scriptGame, unitCore, spriteCore).define(ScriptCoreUtils.createNamespace(context, global, core, "bullet"), global);
        new ScriptVisionCore(scriptGame).define(ScriptCoreUtils.createNamespace(context, global, core, "vision"), global);
        new ScriptLeaderboardCore(scriptGame).define(ScriptCoreUtils.createNamespace(context, global, core, "leaderboard"), global);
        new ScriptCombatCore(scriptGame).define(ScriptCoreUtils.createNamespace(context, global, core, "combat"), global);
        new ScriptAiCore(scriptGame).define(ScriptCoreUtils.createNamespace(context, global, core, "ai"), global);
        new ScriptRequirementCore(scriptGame, unitCore).define(ScriptCoreUtils.createNamespace(context, global, core, "requirements"), global);
        ScriptTypeCore.define(context, scriptGame, core, global);
        new ScriptDatCore(scriptGame).define(context, ScriptCoreUtils.createNamespace(context, global, core, "dat"), global);
        scriptGame.eventCore.define(ScriptCoreUtils.createNamespace(context, global, core, "events"), global);
        scriptGame.commandCore.define(ScriptCoreUtils.createNamespace(context, global, core, "command"), global);
        graphicsCore.defineResources(ScriptCoreUtils.createNamespace(context, global, core, "resources"), global);
        graphicsCore.defineGraphics(ScriptCoreUtils.createNamespace(context, global, core, "graphics"), global);
        scriptGame.localCore.define(ScriptCoreUtils.createNamespace(context, global, core, "local"), global);

        ScriptableObject screen = ScriptCoreUtils.createNamespace(context, global, core, "screen");

        screenCore.defineDrawing(screen, global, ScrDrawList.Space.SCREEN);
        screenCore.defineScreen(screen, global);
        screenCore.defineDrawing(ScriptCoreUtils.createNamespace(context, global, core, "world"), global, ScrDrawList.Space.WORLD);
        screenCore.defineDrawing(ScriptCoreUtils.createNamespace(context, global, core, "minimap"), global, ScrDrawList.Space.MINIMAP);
        new ScriptConsoleCore(scriptGame, scriptGame.consoleSettings, graphicsCore)
                .define(ScriptCoreUtils.createNamespace(context, global, core, "console"), global);
        textCore.defineStrings(ScriptCoreUtils.createNamespace(context, global, core, "strings"), global);
        textCore.defineMessages(ScriptCoreUtils.createNamespace(context, global, core, "messages"), global);
        scriptGame.soundCore.define(ScriptCoreUtils.createNamespace(context, global, core, "sound"), global);
        new ScriptButtonCore(scriptGame).define(ScriptCoreUtils.createNamespace(context, global, core, "buttons"), global);
        scriptGame.screenCore = screenCore;

        for (Object name : core.getAllIds()) {
            ((ScriptableObject) core.get((String) name, core)).preventExtensions();
        }

        core.preventExtensions();
        ScriptableObject.defineProperty(global, CORE_NAME, core, CORE_ATTRIBUTES);

        return scriptGame;
    }


    private final @NotNull Game game;
    private final @NotNull Scriptable global;
    private final @NotNull Scriptable core;

    private final @NotNull ImageReplacements imageReplacements;
    private final @NotNull ConsoleSettings consoleSettings;
    private final @NotNull ScriptEventCore eventCore;
    private final @NotNull ScriptCommandCore commandCore;
    private final @NotNull ScriptLocalCore localCore;
    private final @NotNull ScriptSoundCore soundCore;
    private @Nullable ScriptScreenCore screenCore;

    private final @NotNull Map<String, Integer> registeredTexts;

    private boolean readOnly;
    private boolean local;

    /**
     * Creates a {@link ScriptGame} instance without image replacements, events, commands and registered texts, neither read-only nor local.
     *
     * @param game   the game
     * @param global the global scope
     * @param core   the {@code core} object
     */
    private ScriptGame(@NotNull Game game, @NotNull Scriptable global, @NotNull Scriptable core) {
        this.game   = game;
        this.global = global;
        this.core   = core;

        this.imageReplacements = new ImageReplacements(() -> game.getUnitSystem().getUnitPool());
        this.consoleSettings   = new ConsoleSettings();
        this.eventCore         = new ScriptEventCore(this);
        this.commandCore       = new ScriptCommandCore(this);
        this.localCore         = new ScriptLocalCore(this);
        this.soundCore         = new ScriptSoundCore(this);
        this.screenCore        = null;

        this.registeredTexts = new HashMap<>();

        this.readOnly = false;
        this.local    = false;
    }

    /**
     * @return the game
     */
    @NotNull Game getGame() {
        return this.game;
    }

    /**
     * @return the {@code core} object
     */
    public @NotNull Scriptable getCore() {
        return this.core;
    }

    /**
     * @return the image graphics the script replaces ({@code core.graphics}), the same instance for the whole game
     */
    public @NotNull ImageReplacements getImageReplacements() {
        return this.imageReplacements;
    }

    /**
     * @return the console settings of the script ({@code core.console}), the same instance for the whole game
     */
    public @NotNull ConsoleSettings getConsoleSettings() {
        return this.consoleSettings;
    }

    /**
     * @return the script commands that wait for the local player's outgoing turn ({@code core.command}), the same instance for the whole game
     */
    public @NotNull ScriptCommandOutbox getCommandOutbox() {
        return this.commandCore.getOutbox();
    }

    /**
     * Finds what the last completed render call drew ({@code core.screen}, {@code core.world} and {@code core.minimap}).
     *
     * @return the draw list, or {@code null} if the last call drew nothing or no call completed
     */
    public @Nullable ScrDrawList findDrawList() {
        return this.requireScreenCore().findDrawList();
    }

    /**
     * Starts a render call: the drawing functions add to a new draw list until {@link #endRender(boolean)}.
     */
    public void beginRender() {
        this.requireScreenCore().beginRender();
    }

    /**
     * Ends a render call: its draw list becomes the drawn one, or is dropped so that nothing is drawn.
     *
     * @param completed {@code true} to draw the call's list, {@code false} to drop it
     */
    public void endRender(boolean completed) {
        this.requireScreenCore().endRender(completed);
    }

    private @NotNull ScriptScreenCore requireScreenCore() {
        ScriptScreenCore screen = this.screenCore;

        if (screen == null) {
            throw Exceptions.newIllegalStateException("The script game has no screen functions.");
        }

        return screen;
    }

    /**
     * @return {@code true} if the {@code core} functions that change the game state throw, {@code false} otherwise
     */
    public boolean isReadOnly() {
        return this.readOnly;
    }

    /**
     * Sets whether the {@code core} functions that change the game state throw a {@code TypeError}.
     *
     * @param readOnly {@code true} to make the game read-only
     */
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /**
     * @return {@code true} if local code runs, {@code false} if shared code runs
     */
    public boolean isLocal() {
        return this.local;
    }

    /**
     * Sets whether local code runs: the shared-only functions then throw a {@code TypeError} and the local-only functions work. The caller also
     * makes the game read-only.
     *
     * @param local {@code true} while local code runs
     */
    public void setLocal(boolean local) {
        this.local = local;
    }

    /**
     * @return the instruction limit of the animation scripts that a write runs, that of the current frame
     * @throws IllegalStateException if the game has no frame
     */
    int getInstructionLimit() {
        return this.game.requireFrame().getInstructionLimit();
    }

    /**
     * @return the unit pool of the game
     * @throws IllegalStateException if the game has no unit pool
     */
    @NotNull UnitPool getUnitPool() {
        return this.game.getUnitSystem().requireUnitPool();
    }

    /**
     * @return the trigger context of the game
     * @throws EcmaError if the game has no trigger context
     */
    @NotNull TriggerContext requireTriggerContext() {
        TriggerContext context = this.game.getTriggerContext();

        if (context == null) {
            throw ScriptRuntime.typeError("The game has no trigger state.");
        }

        return context;
    }


    /**
     * Checks that shared code runs, which alone changes the game state, and that the game is not read-only.
     *
     * @throws EcmaError if local code runs or the game is read-only
     */
    void checkWritable() {
        this.checkShared();

        if (this.readOnly) {
            throw ScriptRuntime.typeError("The game state cannot change here: the game is read-only.");
        }
    }

    /**
     * Checks that shared code runs.
     *
     * @throws EcmaError if local code runs
     */
    void checkShared() {
        if (this.local) {
            throw ScriptRuntime.typeError("This function works only in shared code, not in local code.");
        }
    }

    /**
     * Checks that local code runs, which alone reads the local information.
     *
     * @throws EcmaError if no local code runs
     */
    void checkLocal() {
        if (!this.local) {
            throw ScriptRuntime.typeError("This function works only in local code: onLocalFrame, onRender and the local input hooks.");
        }
    }

    /**
     * Finds the local player: the local player of the trigger evaluation in a live game when it is a playable player.
     *
     * @return the slot id of the local player, or {@code -1} in a replay, for an observer and in a game without trigger state
     */
    int findLocalPlayer() {
        TriggerContext context = this.game.getTriggerContext();

        if (this.game.isReplayPlayback() || context == null) {
            return ScriptCoreUtils.NO_ID;
        }

        int player = context.getLocalPlayer();

        return player >= 0 && player < PlayerTable.PLAYABLE_PLAYER_SLOTS ? player : ScriptCoreUtils.NO_ID;
    }

    /**
     * Adds a simulation event to the events that wait for the next frame start ({@code core.events}); an event of no known kind is left out.
     *
     * @param event the event, whose values were captured when it happened
     * @param frame the frame it happened in
     */
    public void queueEvent(@NotNull SimulationEvent event, int frame) {
        this.eventCore.queue(event, frame);
    }

    /**
     * Reads a mod command of a player as a script command ({@code core.command}).
     *
     * @param player the slot id of the issuing player
     * @param data   the command data
     * @return the value the command completed, or {@code null} if the data is no script command or the value continues
     */
    public @Nullable ScriptCommandMessage readModCommand(int player, byte @NotNull [] data) {
        return this.commandCore.read(player, data);
    }

    /**
     * Adds a completed value of the script commands to those that wait for the next frame start, after checking that it decodes.
     *
     * @param context the current context
     * @param message the value
     * @throws IllegalArgumentException if the value is not one well-formed encoded value
     */
    public void queueCommand(@NotNull Context context, @NotNull ScriptCommandMessage message) {
        ScriptValueUtils.decode(context, this.global, message.getValue());

        this.commandCore.queue(message);
    }

    /**
     * Starts a frame: the waiting events and script commands become the frame's ({@code core.events}, {@code core.command}).
     */
    public void startFrame() {
        this.eventCore.startFrame();
        this.commandCore.startFrame();
    }

    /**
     * Starts a local frame: the local inputs since the previous one become the frame's ({@code core.local.getInputEvents}).
     */
    public void startLocalFrame() {
        this.localCore.startLocalFrame();
    }

    /**
     * Plays the sounds local code played since the previous call ({@code core.sound.playAt}) with the sounds of the current game loop iteration.
     */
    public void playLocalSounds() {
        this.soundCore.playLocalSounds();
    }

    /**
     * Finds the kind of a simulation decision.
     *
     * @param decision the decision
     * @return the id of the decision's kind, or {@code -1} if no script decides it
     */
    public int findDecisionKind(@NotNull SimulationDecision decision) {
        ScriptDecisionKind kind = ScriptDecisionKind.findByDecision(decision);

        return kind == null ? ScriptCoreUtils.NO_ID : kind.ordinal();
    }

    /**
     * Creates the record of a decision ({@link ScriptSimulationUtils}).
     *
     * @param context  the current context
     * @param decision the decision, of a known kind ({@link #findDecisionKind})
     * @return the record
     * @throws IllegalArgumentException if no script decides the decision
     * @throws IllegalStateException    if the game has no unit pool
     */
    public @NotNull Scriptable createDecisionRecord(@NotNull Context context, @NotNull SimulationDecision decision) {
        ScriptDecisionKind kind = ScriptDecisionKind.findByDecision(decision);

        if (kind == null) {
            throw Exceptions.newIllegalArgumentException("No script decides a {0}.", decision.getClass().getSimpleName());
        }

        return ScriptSimulationUtils.createDecisionRecord(context, this.global, this.getUnitPool(), kind, decision);
    }

    /**
     * Copies the values the decision hooks left in a decision record ({@link #createDecisionRecord}) back into the decision.
     *
     * @param record   the decision record
     * @param decision the decision
     * @throws IllegalStateException if the game has no unit pool
     */
    public void applyDecision(@NotNull Scriptable record, @NotNull SimulationDecision decision) {
        ScriptSimulationUtils.applyDecision(record, this.getUnitPool(), decision);
    }

    /**
     * Creates the record of a local input ({@link ScriptLocalCore#createInputRecord}).
     *
     * @param context the current context
     * @param input   the local input
     * @return the record
     */
    public @NotNull ScriptableObject createInputRecord(@NotNull Context context, @NotNull ScriptLocalInput input) {
        return ScriptLocalCore.createInputRecord(context, this.global, input);
    }

    /**
     * Creates the local input of a command card button press or target: the condition and action as the ids of {@code core.buttons}, a
     * targeted unit as its id and generation.
     *
     * @param input the button input
     * @return the created {@link ScriptLocalInput} instance
     */
    public @NotNull ScriptLocalInput createButtonInput(@NotNull ScrButtonInput input) {
        Unit target = input.getTarget();
        UnitPool pool = this.game.getUnitSystem().getUnitPool();
        boolean live = target != null && pool != null && ScriptUnitCore.isLive(pool, target.getIndex());
        ScriptButtonPress press = new ScriptButtonPress(input.getSet(), input.getIndex(), input.getPosition(),
                                                        ScriptButtonCore.findConditionId(input.getCondition()), input.getConditionValue(),
                                                        ScriptButtonCore.findActionId(input.getAction()), input.getActionValue(),
                                                        input.isQueued(), input.isHotkey(),
                                                        input.getMapX(), input.getMapY(),
                                                        live ? target.getIndex() : ScriptButtonPress.NONE,
                                                        live ? pool.getGeneration(target.getIndex()) : ScriptButtonPress.NONE);

        return ScriptLocalInput.ofButton(press, input.isTargeted());
    }

    /**
     * Adds the record of a local input that went through the {@code localInput} entry point to the next local frame's inputs; it becomes
     * read-only.
     *
     * @param record the record
     */
    public void addInput(@NotNull ScriptableObject record) {
        this.localCore.addInput(record);
    }

    /**
     * Finds the map string index of a text for the routines that show a map string: the empty text has no map string, any other text is
     * registered in the map strings ({@link MapStringSection#registerString}) as UTF-8, the encoding the map strings decode first.
     * <p>
     * An identical text registers once and keeps its index for the whole game, so a text set every frame does not grow the map strings. The
     * indices follow the order of the first registrations, the same on every peer.
     *
     * @param text the text
     * @return the map string index, {@code 0} for the empty text
     * @throws EcmaError if the text holds a NUL character, the game has no map strings or no map string index is left
     */
    int findStringIndex(@NotNull String text) {
        if (text.isEmpty()) {
            return NO_STRING_INDEX;
        }

        Integer registered = this.registeredTexts.get(text);

        if (registered != null) {
            return registered;
        }

        if (text.indexOf('\0') >= 0) {
            throw ScriptRuntime.rangeError("A map string text holds no NUL character.");
        }

        MapStringSection strings = this.game.getInterfaceLink().getMessageLines().getMapStringSection();

        if (strings == null) {
            throw ScriptRuntime.typeError("The game has no map strings.");
        }

        int stringIndex;

        try {
            stringIndex = strings.registerString(text.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException exception) {
            throw ScriptRuntime.rangeError("No map string index is left for a new text.");
        }

        this.registeredTexts.put(text, stringIndex);

        return stringIndex;
    }

    /**
     * Defines {@code core.game}: {@code getFrame()}, {@code getMapTileWidth()}, {@code getMapTileHeight()}, {@code getTileset()},
     * {@code getFrameDuration()} (milliseconds per frame at the fastest speed), {@code print(text)} (all players, as Display Text Message shows a
     * text with forced display) and {@code isReadOnly()}.
     * <p>
     * The trigger state: {@code isSwitchSet(index)}, {@code setSwitch(index, set)} and {@code toggleSwitch(index)} (switches {@code 0} to
     * {@value #SWITCH_COUNT} minus one, as Set Switch sets, clears and toggles them); {@code getCountdownTimer()},
     * {@code setCountdownTimer(seconds)}, {@code addCountdownTimer(seconds)} and {@code subtractCountdownTimer(seconds)} (Set Countdown Timer,
     * kept from {@code 0} to {@code 359999}), {@code isCountdownTimerPaused()} and {@code setCountdownTimerPaused(paused)} (Pause Timer and
     * Unpause Timer); {@code getElapsedTime()} (the seconds the Elapsed Time condition compares). Countdown and elapsed time are game seconds
     * of the trigger clock, which advances one second every sixteen frames, not frames.
     * <p>
     * {@code isPaused()} (the game is paused, by Pause Game or by the Pause command of the game menu); {@code pause()} and {@code resume()} run
     * Pause Game and Unpause Game without a trigger record at the frame's trigger step ({@link TriggerContext#requestPauseGame()}). They do
     * nothing in a multiplayer session, nor a pause while Pause Game already paused the game, nor a resume while it did not. A game that Pause
     * Game paused runs no frame, but its trigger step, and with it the script's frame entry, keeps running until Unpause Game.
     */
    private void defineGameFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getFrame", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.game.getCurrentFrame());
        ScriptCoreUtils.defineFunction(global, namespace, "getMapTileWidth", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.game.getMap().getWidth());
        ScriptCoreUtils.defineFunction(global, namespace, "getMapTileHeight", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.game.getMap().getHeight());
        ScriptCoreUtils.defineFunction(global, namespace, "getTileset", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.game.getMap().getTileset());
        ScriptCoreUtils.defineFunction(global, namespace, "getFrameDuration", 0,
                                       (context, scope, thisObject, arguments) -> (double) GameSpeed.FASTEST_DURATION);
        ScriptCoreUtils.defineFunction(global, namespace, "print", 1, (context, scope, thisObject, arguments) -> {
            this.checkShared();

            byte[] text = ScriptCoreUtils.readString(arguments, 0).getBytes(StandardCharsets.UTF_8);

            this.game.getInterfaceLink().getMessageLines().writeTriggerText(text, true, 0);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "isReadOnly", 0, (context, scope, thisObject, arguments) -> this.readOnly);

        this.defineTriggerStateFunctions(namespace, global);
    }

    /**
     * Defines the functions of {@code core.game} over the switches, the countdown timer, the elapsed time and the pause of the trigger state.
     */
    private void defineTriggerStateFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "isSwitchSet", 1, (context, scope, thisObject, arguments) -> {
            int index = ScriptCoreUtils.requireId("switch", ScriptCoreUtils.readInteger(arguments, 0), SWITCH_COUNT);

            return this.requireTriggerContext().isSwitchSet(index);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setSwitch", 2, (context, scope, thisObject, arguments) -> {
            this.checkWritable();

            int index = ScriptCoreUtils.requireId("switch", ScriptCoreUtils.readInteger(arguments, 0), SWITCH_COUNT);
            boolean set = ScriptCoreUtils.readBoolean(arguments, 1);

            this.requireTriggerContext().setSwitch(index, set);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "toggleSwitch", 1, (context, scope, thisObject, arguments) -> {
            this.checkWritable();

            int index = ScriptCoreUtils.requireId("switch", ScriptCoreUtils.readInteger(arguments, 0), SWITCH_COUNT);

            this.requireTriggerContext().toggleSwitch(index);

            return Context.getUndefinedValue();
        });

        ScriptCoreUtils.defineFunction(global, namespace, "getCountdownTimer", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.requireTriggerContext().getCountdownTimer());
        this.defineCountdownWrite(namespace, global, "setCountdownTimer", TriggerModifiers.SET_TO);
        this.defineCountdownWrite(namespace, global, "addCountdownTimer", TriggerModifiers.ADD);
        this.defineCountdownWrite(namespace, global, "subtractCountdownTimer", TriggerModifiers.SUBTRACT);
        ScriptCoreUtils.defineFunction(global, namespace, "isCountdownTimerPaused", 0,
                                       (context, scope, thisObject, arguments) -> this.requireTriggerContext().isCountdownPaused());
        ScriptCoreUtils.defineFunction(global, namespace, "setCountdownTimerPaused", 1, (context, scope, thisObject, arguments) -> {
            this.checkWritable();

            boolean paused = ScriptCoreUtils.readBoolean(arguments, 0);

            this.requireTriggerContext().setCountdownPaused(paused);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getElapsedTime", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.requireTriggerContext().getElapsedClockTicks());

        ScriptCoreUtils.defineFunction(global, namespace, "isPaused", 0,
                                       (context, scope, thisObject, arguments) -> this.requireTriggerContext().getPhaseGate() != 0);
        ScriptCoreUtils.defineFunction(global, namespace, "pause", 0, (context, scope, thisObject, arguments) -> {
            this.checkWritable();
            this.requireTriggerContext().requestPauseGame();

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "resume", 0, (context, scope, thisObject, arguments) -> {
            this.checkWritable();
            this.requireTriggerContext().requestUnpauseGame();

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines a write of the countdown timer that runs the Set Countdown Timer routine with a modifier.
     */
    private void defineCountdownWrite(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name, int modifier) {
        ScriptCoreUtils.defineFunction(global, namespace, name, 1, (context, scope, thisObject, arguments) -> {
            this.checkWritable();

            int seconds = ScriptCoreUtils.readInteger(arguments, 0);

            this.requireTriggerContext().modifyCountdownTimer(modifier, seconds);

            return Context.getUndefinedValue();
        });
    }

    /**
     * Forgets the waiting events, script commands and local inputs.
     */
    public void clear() {
        this.eventCore.clear();
        this.commandCore.clear();
        this.localCore.clear();
    }
}
