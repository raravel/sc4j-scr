// The command card table of the upper layer: the globals ButtonCondition, ButtonAction, ButtonState and ButtonKind, and the ButtonSet and
// Button objects over core.buttons. A button set is named by its id, the unit type id for the sets of the unit types; a button by its set and
// its index in the set's table, so a button object names another button after a button before it is inserted or removed. The table is
// presentation: a change in shared code changes every peer's card, a change in local code only the local card.
//
// A button's enabled text has the native button string format: the hotkey character, the button kind character and the text; the upper
// layer wraps the first two as the hotkey and the kind. Texts may hold line breaks and the text color control characters.
(function (core, prelude) {
    'use strict';

    var NONE = prelude.NONE;
    var createObject = prelude.createObject;
    var isObjectOf = prelude.isObjectOf;
    var requireObject = prelude.requireObject;
    var readOptions = prelude.readOptions;
    var typePrototype = prelude.typePrototype;
    var createConstantTable = prelude.createConstantTable;
    var unitTypePrototype = prelude.unitTypePrototype;

    var create = Object.create;
    var freeze = Object.freeze;
    var defineProperty = Object.defineProperty;

    // The button kinds of the native button string, which choose the costs the tooltip shows for the action value.
    var BUTTON_KIND_NAMES = ['None', 'UnitProduction', 'Upgrade', 'TechnologyUse', 'Research', 'Morph'];
    var BUTTON_STATES = [[1, 'Enabled'], [0, 'Hidden'], [-1, 'Disabled']];
    // The hotkey character of a text without hotkey, and the length of the hotkey and kind prefix.
    var NO_HOTKEY = '\u0000';
    var PREFIX_LENGTH = 2;

    // The names of the ids below a count.
    function createNames(count, getName) {
        var names = [];

        for (var id = 0; id < count; id++) {
            names.push(getName(id));
        }

        return names;
    }

    var buttonConditionPrototype = create(typePrototype);
    var buttonActionPrototype = create(typePrototype);
    var buttonStatePrototype = create(typePrototype);
    var buttonKindPrototype = create(typePrototype);
    var conditionNames = createNames(core.buttons.getConditionCount(), core.buttons.getConditionName);
    var actionNames = createNames(core.buttons.getActionCount(), core.buttons.getActionName);
    var ButtonCondition = createConstantTable(buttonConditionPrototype, conditionNames, conditionNames.length, 'Condition ');
    var ButtonAction = createConstantTable(buttonActionPrototype, actionNames, actionNames.length, 'Action ');
    var ButtonKind = createConstantTable(buttonKindPrototype, BUTTON_KIND_NAMES, BUTTON_KIND_NAMES.length, 'Kind ');

    // The states of the Script condition, the native condition results: Enabled 1, Hidden 0, Disabled -1.
    function createButtonStates() {
        var table = {};
        var states = {};

        BUTTON_STATES.forEach(function (entry) {
            var state = freeze(createObject(buttonStatePrototype, {id: entry[0], name: entry[1]}));

            table[entry[1]] = state;
            states[entry[0]] = state;
        });
        defineProperty(table, 'get', {
            value: function (id) {
                var state = states[id];

                return state === undefined ? null : state;
            }
        });

        return freeze(table);
    }

    var ButtonState = createButtonStates();

    // Splits a native button string into its hotkey (null for none), kind id and text.
    function splitText(raw) {
        if (raw === null || raw.length < PREFIX_LENGTH) {
            return {hotkey: null, kind: 0, text: raw === null ? null : ''};
        }

        var hotkey = raw.charAt(0);

        return {hotkey: hotkey === NO_HOTKEY ? null : hotkey, kind: raw.charCodeAt(1), text: raw.substring(PREFIX_LENGTH)};
    }

    // Joins a hotkey (a one-character string or null), a kind id and a text into a native button string.
    function joinText(hotkey, kind, text) {
        if (hotkey !== null && (typeof hotkey !== 'string' || hotkey.length !== 1)) {
            throw new TypeError('A hotkey is one character, not ' + hotkey + '.');
        }

        return (hotkey === null ? NO_HOTKEY : hotkey) + String.fromCharCode(kind) + String(text);
    }

    // Reads an optional ButtonKind as its id, 0 (None) when left out.
    function readKind(kind) {
        return kind === undefined || kind === null ? 0 : requireObject(kind, buttonKindPrototype, 'A button kind').id;
    }

    function readText(text) {
        return text === undefined || text === null ? null : String(text);
    }

    var buttonSetObjects = [];
    var buttonSetPrototype = {};
    var buttonPrototype = {};

    // The button set of an id, the same object for the same id; null for an id outside the table.
    function getButtonSet(id) {
        if (typeof id !== 'number' || id !== Math.floor(id) || id < 0 || id >= core.buttons.getSetCount()) {
            return null;
        }

        var set = buttonSetObjects[id];

        if (set === undefined) {
            set = freeze(createObject(buttonSetPrototype, {id: id}));
            buttonSetObjects[id] = set;
        }

        return set;
    }

    // Reads a button set argument: a ButtonSet, a UnitType (the set of the unit type) or a set id.
    function readButtonSetId(set) {
        if (isObjectOf(set, buttonSetPrototype) || isObjectOf(set, unitTypePrototype)) {
            return set.id;
        }

        if (typeof set === 'number' && getButtonSet(set) !== null) {
            return set;
        }

        throw new TypeError('A button set, a unit type or a button set id is needed, not ' + set + '.');
    }

    // Creates the button object of a set id and an index.
    function createButton(set, index) {
        return freeze(createObject(buttonPrototype, {set: set, index: index}));
    }

    // Inserts a button before an index from the options {position, icon, condition, conditionValue, action, actionValue, text, hotkey, kind,
    // disabledText, enabledString, disabledString}; the condition and action are ButtonCondition and ButtonAction constants.
    function insertButton(set, index, options) {
        var values = readOptions(options);
        var condition = requireObject(values.condition, buttonConditionPrototype, 'A button condition').id;
        var action = requireObject(values.action, buttonActionPrototype, 'A button action').id;
        var text = readText(values.text);
        var disabledText = readText(values.disabledText);

        core.buttons.insert(set, index, values.position, values.icon === undefined ? 0 : values.icon, condition,
                            values.conditionValue === undefined ? 0 : values.conditionValue, action,
                            values.actionValue === undefined ? 0 : values.actionValue,
                            values.enabledString === undefined ? 0 : values.enabledString,
                            values.disabledString === undefined ? 0 : values.disabledString);

        if (text !== null || disabledText !== null) {
            var hotkey = values.hotkey === undefined ? null : values.hotkey;

            core.buttons.setText(set, index, text === null ? null : joinText(hotkey, readKind(values.kind), text), disabledText);
        }

        return createButton(set, index);
    }

    buttonSetPrototype.getId = function () {
        return this.id;
    };
    buttonSetPrototype.getButtonCount = function () {
        return core.buttons.getButtonCount(this.id);
    };
    buttonSetPrototype.getButtons = function () {
        var buttons = [];

        for (var index = 0; index < core.buttons.getButtonCount(this.id); index++) {
            buttons.push(createButton(this.id, index));
        }

        return buttons;
    };
    buttonSetPrototype.getButton = function (index) {
        return index >= 0 && index < core.buttons.getButtonCount(this.id) ? createButton(this.id, index) : null;
    };
    // Inserts a button before the index, or at the end for the button count; the table order is the order the card tries the buttons in.
    buttonSetPrototype.insertButton = function (index, options) {
        return insertButton(this.id, index, options);
    };
    // Removes the button at the index; the buttons after it move up.
    buttonSetPrototype.removeButton = function (index) {
        core.buttons.remove(this.id, index);
    };
    // The set takes the array of another set: a change of either changes both.
    buttonSetPrototype.share = function (other) {
        core.buttons.share(this.id, readButtonSetId(other));
    };
    // The set takes its own copy of its array.
    buttonSetPrototype.unshare = function () {
        core.buttons.unshare(this.id);
    };
    // The first set that holds this set's array; equal for the sets that share it.
    buttonSetPrototype.getArray = function () {
        return getButtonSet(core.buttons.getArray(this.id));
    };
    // Takes the installed array and connected set again.
    buttonSetPrototype.restore = function () {
        core.buttons.restore(this.id);
    };
    // The connected set, null for none.
    buttonSetPrototype.getConnectedSet = function () {
        return getButtonSet(core.buttons.getConnectedSet(this.id));
    };
    // Sets the connected set; null for none.
    buttonSetPrototype.setConnectedSet = function (set) {
        core.buttons.setConnectedSet(this.id, set === null ? 0xFFFF : readButtonSetId(set));
    };
    buttonSetPrototype.toString = function () {
        return 'Button set ' + this.id;
    };

    buttonPrototype.getButtonSet = function () {
        return getButtonSet(this.set);
    };
    buttonPrototype.getIndex = function () {
        return this.index;
    };
    buttonPrototype.getPosition = function () {
        return core.buttons.getPosition(this.set, this.index);
    };
    buttonPrototype.setPosition = function (position) {
        core.buttons.setPosition(this.set, this.index, position);
    };
    buttonPrototype.getIcon = function () {
        return core.buttons.getIcon(this.set, this.index);
    };
    buttonPrototype.setIcon = function (icon) {
        core.buttons.setIcon(this.set, this.index, icon);
    };
    // The condition, null for an unknown function.
    buttonPrototype.getCondition = function () {
        return ButtonCondition.get(core.buttons.getCondition(this.set, this.index));
    };
    buttonPrototype.setCondition = function (condition) {
        core.buttons.setCondition(this.set, this.index, requireObject(condition, buttonConditionPrototype, 'A button condition').id);
    };
    buttonPrototype.getConditionValue = function () {
        return core.buttons.getConditionValue(this.set, this.index);
    };
    buttonPrototype.setConditionValue = function (value) {
        core.buttons.setConditionValue(this.set, this.index, value);
    };
    // The action, null for an unknown function.
    buttonPrototype.getAction = function () {
        return ButtonAction.get(core.buttons.getAction(this.set, this.index));
    };
    buttonPrototype.setAction = function (action) {
        core.buttons.setAction(this.set, this.index, requireObject(action, buttonActionPrototype, 'A button action').id);
    };
    buttonPrototype.getActionValue = function () {
        return core.buttons.getActionValue(this.set, this.index);
    };
    buttonPrototype.setActionValue = function (value) {
        core.buttons.setActionValue(this.set, this.index, value);
    };
    buttonPrototype.getEnabledString = function () {
        return core.buttons.getEnabledString(this.set, this.index);
    };
    buttonPrototype.getDisabledString = function () {
        return core.buttons.getDisabledString(this.set, this.index);
    };
    // The text shown instead of the enabled string, without its hotkey and kind; null while the button shows its string.
    buttonPrototype.getText = function () {
        return splitText(core.buttons.getEnabledText(this.set, this.index)).text;
    };
    // The hotkey character of the text, null without a text or a hotkey.
    buttonPrototype.getHotkey = function () {
        return splitText(core.buttons.getEnabledText(this.set, this.index)).hotkey;
    };
    // The button kind of the text, null without a text.
    buttonPrototype.getKind = function () {
        var raw = core.buttons.getEnabledText(this.set, this.index);

        return raw === null ? null : ButtonKind.get(splitText(raw).kind);
    };
    // Sets the text with the options {hotkey, kind}, which keep the current ones when left out; null shows the enabled string again.
    buttonPrototype.setText = function (text, options) {
        var values = readOptions(options);
        var current = splitText(core.buttons.getEnabledText(this.set, this.index));
        var value = readText(text);
        var raw = value === null
                  ? null
                  : joinText(values.hotkey === undefined ? current.hotkey : values.hotkey,
                             values.kind === undefined ? current.kind : readKind(values.kind), value);

        core.buttons.setText(this.set, this.index, raw, core.buttons.getDisabledText(this.set, this.index));
    };
    buttonPrototype.getDisabledText = function () {
        return core.buttons.getDisabledText(this.set, this.index);
    };
    // Sets the text a disabled button shows; null shows the disabled string again.
    buttonPrototype.setDisabledText = function (text) {
        core.buttons.setText(this.set, this.index, core.buttons.getEnabledText(this.set, this.index), readText(text));
    };
    buttonPrototype.toString = function () {
        return 'Button ' + this.index + ' of button set ' + this.set;
    };

    [buttonConditionPrototype, buttonActionPrototype, buttonStatePrototype, buttonKindPrototype, buttonSetPrototype, buttonPrototype].forEach(freeze);

    return {
        exports: {
            ButtonCondition: ButtonCondition,
            ButtonAction: ButtonAction,
            ButtonState: ButtonState,
            ButtonKind: ButtonKind
        },
        shared: {
            buttonSetPrototype: buttonSetPrototype,
            buttonStatePrototype: buttonStatePrototype,
            getButtonSet: getButtonSet,
            readButtonSetId: readButtonSetId,
            insertButton: insertButton,
            createButtonObject: createButton,
            joinText: joinText,
            readKind: readKind
        }
    };
});
