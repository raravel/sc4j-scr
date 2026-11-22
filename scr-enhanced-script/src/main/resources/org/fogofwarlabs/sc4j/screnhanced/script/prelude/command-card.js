// The command card of the upper layer: display.commandCard (presentation.js puts it into display) with the button sets and the script
// buttons, over core.buttons, core.local and the channels of sync.
//
// A script button (createButton) is made in shared code, in the same order on every peer, which gives it its id k from 0 to 255: its
// condition is Script with the value k and its action Script, or ScriptTarget for a target button, with the value k. Each local frame sets
// the local state of k from isEnabled over the local selection; a press, and the target of a target button, that no local input hook consumed
// calls onLocalPressed, and when that does not consume it either, the press of a button without target sends null and the target of a target
// button sends {x, y, unit, generation} over the button's channel. When the command runs, every peer and every replay calls isEnabled again for
// each unit the sender had selected and calls onPressed with the player, the units for which it returned true, and the target {position,
// unit}. isEnabled therefore reads the game state only, never local information.
(function (core, prelude) {
    'use strict';

    var requireShared = prelude.requireShared;
    var readOptions = prelude.readOptions;
    var createObject = prelude.createObject;
    var createPosition = prelude.createPosition;
    var getCapturedUnit = prelude.getCapturedUnit;
    var toUnits = prelude.toUnits;
    var getButtonSet = prelude.getButtonSet;
    var readButtonSetId = prelude.readButtonSetId;
    var insertButton = prelude.insertButton;
    var createButtonObject = prelude.createButtonObject;
    var ButtonCondition = prelude.ButtonCondition;
    var ButtonAction = prelude.ButtonAction;
    var ButtonState = prelude.ButtonState;
    var InputKind = prelude.InputKind;
    var sync = prelude.sync;

    var freeze = Object.freeze;

    // The condition value is a byte, so at most 256 script buttons.
    var MAXIMUM_SCRIPT_BUTTONS = 256;
    var IS_ENABLED_CALL = 'Button isEnabled';
    var LOCAL_PRESSED_CALL = 'Button onLocalPressed';

    // The script buttons by id, in the order shared code created them, the same on every peer.
    var scriptButtons = [];

    // Returns an optional callback option; throws a TypeError for a value that is not a function.
    function readCallback(value, name) {
        if (value !== undefined && typeof value !== 'function') {
            throw new TypeError('The option ' + name + ' is a function, not ' + value + '.');
        }

        return value;
    }

    // Whether a value is an integer.
    function isMapPosition(value) {
        return typeof value === 'number' && value === Math.floor(value);
    }

    // Reads the target a script target button sent: {x, y, unit, generation}; null for any other value.
    function readTarget(value) {
        if (value === null || typeof value !== 'object' || !isMapPosition(value.x) || !isMapPosition(value.y) ||
            !isMapPosition(value.unit) || !isMapPosition(value.generation)) {
            return null;
        }

        return freeze({position: createPosition(value.x, value.y), unit: getCapturedUnit(value.unit, value.generation)});
    }

    // Whether the button is enabled for a unit: no isEnabled, or isEnabled returns true.
    function isPassing(button, unit) {
        return button.isEnabled === undefined || button.isEnabled(unit) === true;
    }

    var scriptButtonPrototype = {
        getId: function () {
            return this.id;
        },
        getButtonSet: function () {
            return getButtonSet(this.set);
        },
        // The button of the set's table, found by its action and action value; null once the button was removed.
        getButton: function () {
            var action = scriptButtons[this.id].target ? ButtonAction.ScriptTarget.id : ButtonAction.Script.id;

            for (var index = 0; index < core.buttons.getButtonCount(this.set); index++) {
                if (core.buttons.getAction(this.set, index) === action && core.buttons.getActionValue(this.set, index) === this.id) {
                    return createButtonObject(this.set, index);
                }
            }

            return null;
        }
    };

    var commandCard = freeze({
                                 // The button set of a set id or a unit type, null for an id outside the table.
                                 getButtonSet: function (set) {
                                     return getButtonSet(readButtonSetId(set));
                                 },
                                 // Creates a script button: {unitType or buttonSet, position, icon, text, hotkey, kind, disabledText, target,
                                 // isEnabled(unit), onLocalPressed(event), onPressed(player, units, target)}. The button goes after the set's buttons
                                 // up to its position. Shared code only; a game has at most 256 script buttons.
                                 createButton: function (options) {
                                     var values = readOptions(options);

                                     requireShared('Local code cannot create a button.');

                                     if (scriptButtons.length >= MAXIMUM_SCRIPT_BUTTONS) {
                                         throw new RangeError('A game has at most ' + MAXIMUM_SCRIPT_BUTTONS + ' script buttons.');
                                     }

                                     var set = readButtonSetId(values.buttonSet !== undefined ? values.buttonSet : values.unitType);
                                     var target = values.target === true;
                                     var button = {
                                         id: scriptButtons.length,
                                         set: set,
                                         target: target,
                                         isEnabled: readCallback(values.isEnabled, 'isEnabled'),
                                         onLocalPressed: readCallback(values.onLocalPressed, 'onLocalPressed'),
                                         onPressed: readCallback(values.onPressed, 'onPressed'),
                                         channel: null
                                     };
                                     var index = 0;

                                     while (index < core.buttons.getButtonCount(set) && core.buttons.getPosition(set, index) <= values.position) {
                                         index++;
                                     }

                                     insertButton(set, index, {
                                         position: values.position,
                                         icon: values.icon,
                                         condition: ButtonCondition.Script,
                                         conditionValue: button.id,
                                         action: target ? ButtonAction.ScriptTarget : ButtonAction.Script,
                                         actionValue: button.id,
                                         text: values.text,
                                         hotkey: values.hotkey,
                                         kind: values.kind,
                                         disabledText: values.disabledText
                                     });

                                     if (button.onPressed !== undefined) {
                                         button.channel = sync.createChannel(function (player, value, units) {
                                             var pressedTarget = target ? readTarget(value) : null;

                                             if (target && pressedTarget === null) {
                                                 return;
                                             }

                                             button.onPressed(player, units.filter(function (unit) {
                                                 return isPassing(button, unit);
                                             }), pressedTarget);
                                         });
                                     }

                                     scriptButtons.push(button);

                                     return freeze(createObject(scriptButtonPrototype, {id: button.id, set: set}));
                                 }
                             });

    // Sets the local state of every script button from its isEnabled over the local selection: enabled when it returns true for a unit,
    // else disabled when it returns false for one, else hidden; enabled without isEnabled. callForValue(name, function, arguments) runs a
    // guarded call and returns its value, undefined when it failed.
    function updateScriptButtons(callForValue) {
        var units = null;

        for (var id = 0; id < scriptButtons.length; id++) {
            var button = scriptButtons[id];
            var state = ButtonState.Enabled;

            if (button.isEnabled !== undefined) {
                if (units === null) {
                    units = toUnits(core.local.getSelection()).filter(function (unit) {
                        return unit !== null;
                    });
                }

                state = ButtonState.Hidden;

                for (var index = 0; index < units.length; index++) {
                    var result = callForValue(IS_ENABLED_CALL, button.isEnabled, [units[index]]);

                    if (result === true) {
                        state = ButtonState.Enabled;
                        break;
                    }

                    if (result === false) {
                        state = ButtonState.Disabled;
                    }
                }
            }

            core.local.setButtonState(button.id, state.id);
        }
    }

    // Handles a press or a target of the local input hooks that no hook consumed: the press of a script button, and the target of a script
    // target button, call its onLocalPressed. Unless that consumed it, the press of a button without target and the target of a target button
    // send over the button's channel; the press of a target button goes on to the game, which starts the targeting.
    function handleScriptButtonInput(record, event, callForValue) {
        var targeted = record.kind === InputKind.ButtonTargeted.id;
        var button = scriptButtons[record.actionValue];

        if (record.consumed === true || record.kind !== InputKind.ButtonPressed.id && !targeted || button === undefined ||
            record.action !== (button.target ? ButtonAction.ScriptTarget.id : ButtonAction.Script.id) || targeted && !button.target) {
            return;
        }

        if (button.onLocalPressed !== undefined) {
            callForValue(LOCAL_PRESSED_CALL, button.onLocalPressed, [event]);
        }

        if (record.consumed !== true && button.channel !== null && targeted === button.target) {
            button.channel.send(targeted ? {x: record.x, y: record.y, unit: record.target, generation: record.targetGeneration} : null);
        }
    }

    freeze(scriptButtonPrototype);

    return {
        exports: {},
        shared: {
            commandCard: commandCard,
            updateScriptButtons: updateScriptButtons,
            handleScriptButtonInput: handleScriptButtonInput
        }
    };
});
