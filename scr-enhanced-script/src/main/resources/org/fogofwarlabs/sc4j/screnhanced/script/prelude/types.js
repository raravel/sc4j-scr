// The type and value tables of the upper layer: the globals UnitType, WeaponType, FlingyType, SpriteType, ImageType, UpgradeType, TechType, Order,
// Key, MouseButton, Modifier, UnitGroup, ScoreType, ResourceType, Alliance, EventKind, InputKind, DecisionKind, GameSpeed, Cheat, GameMode,
// UnitSize, DamageType, LocationFlag, DrawFunction, BulletState and Animation. The DAT methods of the type objects come from the DAT tables
// (prelude.datTables) over core.dat, with its raw values.
(function (core, prelude) {
    'use strict';

    var NONE = prelude.NONE;
    var RAW_SCALE = prelude.RAW_SCALE;
    var createObject = prelude.createObject;
    var isObjectOf = prelude.isObjectOf;
    var requireObject = prelude.requireObject;
    var toIdentifier = prelude.toIdentifier;
    var typePrototype = prelude.typePrototype;
    var createTypeTable = prelude.createTypeTable;
    var createConstantTable = prelude.createConstantTable;

    var create = Object.create;
    var freeze = Object.freeze;

    // The unit operand of the first unit group, after the unit types and their none.
    var FIRST_UNIT_GROUP = 229;
    var UNIT_GROUP_NAMES = ['Any unit', 'Men', 'Buildings', 'Factories'];
    // The keys by Windows virtual-key code: the named codes are constants of Key, the others only Key.get(code), named "Key " and the code.
    var KEY_COUNT = 256;
    var NAMED_KEYS = [
        [0x08, 'Backspace'], [0x09, 'Tab'], [0x0D, 'Enter'], [0x10, 'Shift'], [0x11, 'Control'], [0x12, 'Alt'], [0x13, 'Pause'],
        [0x14, 'CapsLock'], [0x1B, 'Escape'], [0x20, 'Space'], [0x21, 'PageUp'], [0x22, 'PageDown'], [0x23, 'End'], [0x24, 'Home'],
        [0x25, 'Left'], [0x26, 'Up'], [0x27, 'Right'], [0x28, 'Down'], [0x2D, 'Insert'], [0x2E, 'Delete'], [0x6A, 'NumpadMultiply'],
        [0x6B, 'NumpadAdd'], [0x6D, 'NumpadSubtract'], [0x6E, 'NumpadDecimal'], [0x6F, 'NumpadDivide'], [0xBA, 'Semicolon'], [0xBB, 'Equal'],
        [0xBC, 'Comma'], [0xBD, 'Minus'], [0xBE, 'Period'], [0xBF, 'Slash'], [0xC0, 'Backquote'], [0xDB, 'BracketLeft'], [0xDC, 'Backslash'],
        [0xDD, 'BracketRight'], [0xDE, 'Quote']
    ];
    var LETTER_A = 0x41;
    var LETTER_COUNT = 26;
    var DIGIT_0 = 0x30;
    var NUMPAD_0 = 0x60;
    var DIGIT_COUNT = 10;
    var F1 = 0x70;
    var FUNCTION_KEY_COUNT = 12;
    var MOUSE_BUTTON_NAMES = ['Left', 'Right', 'Middle'];
    var SCORE_TYPE_NAMES = ['Total', 'Units', 'Buildings', 'UnitsAndBuildings', 'Kills', 'Razings', 'KillsAndRazings', 'Custom'];
    // Supply names only the supply icon of the console (display.console.setResourceIcon); the resource functions refuse it.
    var RESOURCE_TYPE_NAMES = ['Minerals', 'Gas', 'MineralsAndGas', 'Supply'];
    var ALLIANCE_NAMES = ['Enemy', 'Ally', 'AlliedVictory'];
    // The kinds of the records of core.local.getInputEvents and of the decide entry point, in the ids the runtime gives them.
    var INPUT_KIND_NAMES = ['KeyDown', 'KeyUp', 'MouseDown', 'MouseUp', 'Chat', 'ButtonPressed', 'ButtonTargeted'];
    var DECISION_KIND_NAMES = ['Damage', 'Order', 'TargetAcquired'];
    // The game speeds by their index in the frame duration table.
    var GAME_SPEED_NAMES = ['Slowest', 'Slower', 'Slow', 'Normal', 'Fast', 'Faster', 'Fastest'];
    // The bits of the cheat flags.
    var CHEATS = [
        [0x1, 'BlackSheepWall'], [0x2, 'OperationCwal'], [0x4, 'PowerOverwhelming'], [0x8, 'SomethingForNothing'], [0x10, 'ShowMeTheMoney'],
        [0x40, 'GameOverMan'], [0x80, 'ThereIsNoCowLevel'], [0x100, 'StayingAlive'], [0x200, 'Ophelia'], [0x800, 'TheGathering'],
        [0x1000, 'MedievalMan'], [0x2000, 'ModifyThePhaseVariance'], [0x4000, 'WarAintWhatItUsedToBe'], [0x20000, 'FoodForThought'],
        [0x40000, 'WhatsMineIsMine'], [0x80000, 'BreatheDeep'], [0x20000000, 'Noglues']
    ];
    // The game types of the game header; 9 (Ladder) has no lobby template.
    var GAME_MODES = [
        [2, 'Melee'], [3, 'FreeForAll'], [4, 'OneOnOne'], [5, 'CaptureTheFlag'], [6, 'Greed'], [7, 'Slaughter'], [8, 'SuddenDeath'],
        [9, 'Ladder'], [10, 'UseMapSettings'], [11, 'TeamMelee'], [12, 'TeamFreeForAll'], [13, 'TeamCaptureTheFlag'], [15, 'TopVsBottom']
    ];
    var GAME_MODE_COUNT = 16;
    // The unit sizes of units.dat and the damage types of weapons.dat.
    var UNIT_SIZE_NAMES = ['Independent', 'Small', 'Medium', 'Large'];
    var DAMAGE_TYPE_NAMES = ['Independent', 'Explosive', 'Concussive', 'Normal', 'IgnoreArmor'];
    // The elevation flags of a location: a set flag leaves the units at the elevation out of the location.
    var LOCATION_FLAGS = [[0x1, 'LowGround'], [0x2, 'MiddleGround'], [0x4, 'HighGround'], [0x8, 'LowAir'], [0x10, 'MiddleAir'], [0x20, 'HighAir']];
    // The draw functions of images.dat by id, as SC:R's renderer names them.
    var DRAW_FUNCTION_NAMES = ['Normal', 'NormalUnchecked', 'UnseenCloaking', 'UnseenCloaked', 'UnseenDecloaking', 'Cloaking', 'Cloaked',
                               'Decloaking', 'Distortion', 'Remapping', 'Shadow', 'HealthBar', 'WarpTexture', 'SelectionCircle', 'PlayerColor',
                               'UpdateRectangle', 'Hallucination', 'WarpFlash'];
    // The order states of a bullet.
    var BULLET_STATE_NAMES = ['Init', 'Move', 'Follow', 'Bounce', 'DamageOverTime', 'Dying', 'HitNearTarget'];
    // The iscript animations, in the order of an iscript entry's animation table.
    var ANIMATION_NAMES = ['Init', 'Death', 'GroundAttackInit', 'AirAttackInit', 'Unused1', 'GroundAttackRepeat', 'AirAttackRepeat', 'CastSpell',
                           'GroundAttackToIdle', 'AirAttackToIdle', 'Unused2', 'Walking', 'WalkingToIdle', 'SpecialState1', 'SpecialState2',
                           'AlmostBuilt', 'Built', 'Landing', 'LiftOff', 'IsWorking', 'WorkingToIdle', 'WarpIn', 'Unused3', 'StarEditInit',
                           'Disable', 'Burrow', 'Unburrow', 'Enable'];

    var unitTypePrototype = create(typePrototype);
    var weaponTypePrototype = create(typePrototype);
    var flingyTypePrototype = create(typePrototype);
    var spriteTypePrototype = create(typePrototype);
    var imageTypePrototype = create(typePrototype);
    var upgradeTypePrototype = create(typePrototype);
    var techTypePrototype = create(typePrototype);
    var orderPrototype = create(typePrototype);

    // The image type whose HD anim this image type draws (images.rel); this image type when it has its own anim.
    imageTypePrototype.getAnimImageType = function () {
        return ImageType.get(core.imageType.getAnimImageType(this.id));
    };
    // The damage overlay byte: twice the flame positions of the image's damage overlay, a unit's damage states.
    imageTypePrototype.getDamageOverlayCount = function () {
        return core.imageType.getDamageOverlayCount(this.id);
    };
    // Sets the damage overlay byte, 0 to 255. Shared code only.
    imageTypePrototype.setDamageOverlayCount = function (count) {
        core.imageType.setDamageOverlayCount(this.id, count);
    };

    // The strength of a unit type against air or ground targets for the computer players.
    unitTypePrototype.getAiStrength = function (air) {
        return core.ai.getUnitStrength(this.id, air);
    };
    // Sets the strength against air or ground targets. Shared code only.
    unitTypePrototype.setAiStrength = function (air, strength) {
        core.ai.setUnitStrength(this.id, air, strength);
    };

    // The shown maximum hit points and shields of a unit type: units.dat stores hit points in 1/256 and shields as shown. The setters are
    // shared code only.
    unitTypePrototype.getMaxHp = function () {
        return this.getHitPoints() / RAW_SCALE;
    };
    unitTypePrototype.setMaxHp = function (hp) {
        this.setHitPoints(Math.round(Number(hp) * RAW_SCALE));
    };
    unitTypePrototype.getMaxShields = function () {
        return this.getShieldAmount();
    };
    unitTypePrototype.setMaxShields = function (shields) {
        this.setShieldAmount(shields);
    };
    // The name of a unit type in its stat_txt string, which tooltips, the selection and messages show; null shows the game's name again.
    unitTypePrototype.setName = function (text) {
        core.strings.setStatText(this.id + 1, text === null || text === undefined ? null : String(text));
    };

    var UnitType = createTypeTable(core.unitType, unitTypePrototype);
    var WeaponType = createTypeTable(core.weaponType, weaponTypePrototype);
    var FlingyType = createTypeTable(core.flingyType, flingyTypePrototype);
    var SpriteType = createTypeTable(core.spriteType, spriteTypePrototype);
    var ImageType = createTypeTable(core.imageType, imageTypePrototype);
    var UpgradeType = createTypeTable(core.upgradeType, upgradeTypePrototype);
    var TechType = createTypeTable(core.techType, techTypePrototype);
    var Order = createTypeTable(core.orderType, orderPrototype);

    // The type tables and the prototypes of their objects by the type names of the DAT tables.
    var datTypeTables = {
        UnitType: UnitType, WeaponType: WeaponType, FlingyType: FlingyType, SpriteType: SpriteType, ImageType: ImageType,
        UpgradeType: UpgradeType, TechType: TechType, Order: Order
    };
    var datTypePrototypes = {
        UnitType: unitTypePrototype, WeaponType: weaponTypePrototype, FlingyType: flingyTypePrototype, SpriteType: spriteTypePrototype,
        ImageType: imageTypePrototype, UpgradeType: upgradeTypePrototype, TechType: techTypePrototype, Order: orderPrototype
    };

    // Defines the methods of a DAT column on the type objects: get<Column>() and, for a writable column, set<Column>(value) with the raw
    // value, or the type object a reference names (null for its none id). The setters are shared code only.
    function defineDatColumn(prototype, namespace, column) {
        var read = namespace['get' + column.name];
        var write = column.writable ? namespace['set' + column.name] : null;

        if (column.reference === null) {
            prototype['get' + column.name] = function () {
                return read(this.id);
            };

            if (write !== null) {
                prototype['set' + column.name] = function (value) {
                    write(this.id, value);
                };
            }

            return;
        }

        var table = datTypeTables[column.reference];
        var referencePrototype = datTypePrototypes[column.reference];

        prototype['get' + column.name] = function () {
            return table.get(read(this.id));
        };

        if (write !== null) {
            prototype['set' + column.name] = function (type) {
                write(this.id, type === null && column.none !== NONE ? column.none : requireObject(type, referencePrototype, column.reference).id);
            };
        }
    }

    // Defines is<Flag>() and, for a writable column, set<Flag>(set), shared code only, for a named bit of a DAT flag column.
    function defineDatFlag(prototype, namespace, column, flag) {
        var read = namespace['get' + column.name];
        var write = column.writable ? namespace['set' + column.name] : null;

        prototype['is' + flag.name] = function () {
            return (read(this.id) & flag.mask) !== 0;
        };

        if (write !== null) {
            prototype['set' + flag.name] = function (set) {
                if (typeof set !== 'boolean') {
                    throw new TypeError('A boolean is needed, not ' + set + '.');
                }

                var value = read(this.id);

                write(this.id, (set ? value | flag.mask : value & ~flag.mask) >>> 0);
            };
        }
    }

    // Defines the DAT methods of every table's type objects.
    function defineDatMethods(tables) {
        for (var tableIndex = 0; tableIndex < tables.length; tableIndex++) {
            var table = tables[tableIndex];
            var namespace = core.dat[table.name];
            var prototype = datTypePrototypes[table.type];

            for (var columnIndex = 0; columnIndex < table.columns.length; columnIndex++) {
                var column = table.columns[columnIndex];

                if (column.flags === null) {
                    defineDatColumn(prototype, namespace, column);
                } else {
                    for (var flagIndex = 0; flagIndex < column.flags.length; flagIndex++) {
                        defineDatFlag(prototype, namespace, column, column.flags[flagIndex]);
                    }
                }
            }
        }
    }

    defineDatMethods(prelude.datTables);

    // The names of the Key constants by virtual-key code.
    function createKeyNames() {
        var names = [];

        NAMED_KEYS.forEach(function (entry) {
            names[entry[0]] = entry[1];
        });

        for (var letter = 0; letter < LETTER_COUNT; letter++) {
            names[LETTER_A + letter] = String.fromCharCode(LETTER_A + letter);
        }

        for (var digit = 0; digit < DIGIT_COUNT; digit++) {
            names[DIGIT_0 + digit] = 'Digit' + digit;
            names[NUMPAD_0 + digit] = 'Numpad' + digit;
        }

        for (var number = 1; number <= FUNCTION_KEY_COUNT; number++) {
            names[F1 + number - 1] = 'F' + number;
        }

        return names;
    }

    var keyPrototype = create(typePrototype);
    var mouseButtonPrototype = create(typePrototype);
    var Key = createConstantTable(keyPrototype, createKeyNames(), KEY_COUNT, 'Key ');
    var MouseButton = createConstantTable(mouseButtonPrototype, MOUSE_BUTTON_NAMES, MOUSE_BUTTON_NAMES.length, 'Button ');
    // The bits of the modifier mask of a local key or mouse button event.
    var Modifier = freeze({Shift: 1, Control: 2, Alt: 4});

    // The operands of the trigger counters: the unit groups after the unit types, the score types, the resource types and the alliances, with
    // the names of the original trigger editor.
    var unitGroupPrototype = create(typePrototype);
    var scoreTypePrototype = create(typePrototype);
    var resourceTypePrototype = create(typePrototype);
    var alliancePrototype = create(typePrototype);

    // Creates the UnitGroup constants, their ids the unit operands from FIRST_UNIT_GROUP.
    function createUnitGroups() {
        var table = {};

        UNIT_GROUP_NAMES.forEach(function (name, index) {
            table[toIdentifier(name)] = freeze(createObject(unitGroupPrototype, {id: FIRST_UNIT_GROUP + index, name: name}));
        });

        return freeze(table);
    }

    var UnitGroup = createUnitGroups();
    var ScoreType = createConstantTable(scoreTypePrototype, SCORE_TYPE_NAMES, SCORE_TYPE_NAMES.length, 'Score ');
    var ResourceType = createConstantTable(resourceTypePrototype, RESOURCE_TYPE_NAMES, RESOURCE_TYPE_NAMES.length, 'Resource ');
    var Alliance = createConstantTable(alliancePrototype, ALLIANCE_NAMES, ALLIANCE_NAMES.length, 'Alliance ');

    // Creates the constants of bits: one object per entry [bit, name], its id the bit.
    function createBitTable(prototype, entries) {
        var table = {};

        entries.forEach(function (entry) {
            table[entry[1]] = freeze(createObject(prototype, {id: entry[0], name: entry[1]}));
        });

        return freeze(table);
    }

    // Creates the constants of named ids below a count: one object per id, an id without a name named by the prefix and the id.
    function createSparseTable(prototype, entries, count, unnamedPrefix) {
        var names = [];

        entries.forEach(function (entry) {
            names[entry[0]] = entry[1];
        });

        return createConstantTable(prototype, names, count, unnamedPrefix);
    }

    // The game speeds, which name the entries of the frame duration table; setFrameDuration (milliseconds) is shared code only.
    var gameSpeedPrototype = create(typePrototype);

    gameSpeedPrototype.getFrameDuration = function () {
        return core.game.getSpeedDuration(this.id);
    };
    gameSpeedPrototype.setFrameDuration = function (milliseconds) {
        core.game.setSpeedDuration(this.id, milliseconds);
    };

    var cheatPrototype = create(typePrototype);
    var gameModePrototype = create(typePrototype);
    var unitSizePrototype = create(typePrototype);
    var damageTypePrototype = create(typePrototype);
    var locationFlagPrototype = create(typePrototype);
    var drawFunctionPrototype = create(typePrototype);
    var bulletStatePrototype = create(typePrototype);
    var animationPrototype = create(typePrototype);

    // The factor that scales the damage of the damage type against a unit size, 1 for full damage; setMultiplier is shared code only.
    damageTypePrototype.getMultiplier = function (size) {
        return core.combat.getDamageMultiplier(this.id, requireObject(size, unitSizePrototype, 'A unit size').id) / RAW_SCALE;
    };
    damageTypePrototype.setMultiplier = function (size, factor) {
        var sizeId = requireObject(size, unitSizePrototype, 'A unit size').id;

        core.combat.setDamageMultiplier(this.id, sizeId, Math.round(Number(factor) * RAW_SCALE));
    };

    var GameSpeed = createConstantTable(gameSpeedPrototype, GAME_SPEED_NAMES, GAME_SPEED_NAMES.length, 'Speed ');
    var Cheat = createBitTable(cheatPrototype, CHEATS);
    var GameMode = createSparseTable(gameModePrototype, GAME_MODES, GAME_MODE_COUNT, 'Game mode ');
    var UnitSize = createConstantTable(unitSizePrototype, UNIT_SIZE_NAMES, UNIT_SIZE_NAMES.length, 'Size ');
    var DamageType = createConstantTable(damageTypePrototype, DAMAGE_TYPE_NAMES, DAMAGE_TYPE_NAMES.length, 'Damage type ');
    var LocationFlag = createBitTable(locationFlagPrototype, LOCATION_FLAGS);
    var DrawFunction = createConstantTable(drawFunctionPrototype, DRAW_FUNCTION_NAMES, DRAW_FUNCTION_NAMES.length, 'Draw function ');
    var BulletState = createConstantTable(bulletStatePrototype, BULLET_STATE_NAMES, BULLET_STATE_NAMES.length, 'Bullet state ');
    var Animation = createConstantTable(animationPrototype, ANIMATION_NAMES, ANIMATION_NAMES.length, 'Animation ');

    // The kinds of the lower records: the events of core.events.getFrameEvents by their names in core.events, the local inputs and the
    // decisions.
    function createEventKindNames() {
        var names = [];

        for (var id = 0; id < core.events.getKindCount(); id++) {
            names.push(core.events.getKindName(id));
        }

        return names;
    }

    var eventKindPrototype = create(typePrototype);
    var inputKindPrototype = create(typePrototype);
    var decisionKindPrototype = create(typePrototype);
    var eventKindNames = createEventKindNames();
    var EventKind = createConstantTable(eventKindPrototype, eventKindNames, eventKindNames.length, 'Event ');
    var InputKind = createConstantTable(inputKindPrototype, INPUT_KIND_NAMES, INPUT_KIND_NAMES.length, 'Input ');
    var DecisionKind = createConstantTable(decisionKindPrototype, DECISION_KIND_NAMES, DECISION_KIND_NAMES.length, 'Decision ');

    // Returns the unit operand of a UnitType or a UnitGroup.
    function readUnitOperand(unit) {
        if (isObjectOf(unit, unitTypePrototype) || isObjectOf(unit, unitGroupPrototype)) {
            return unit.id;
        }

        throw new TypeError('A unit type or a unit group is needed, not ' + unit + '.');
    }

    [unitTypePrototype, weaponTypePrototype, flingyTypePrototype, spriteTypePrototype, imageTypePrototype, upgradeTypePrototype,
     techTypePrototype, orderPrototype, keyPrototype, mouseButtonPrototype, unitGroupPrototype, scoreTypePrototype, resourceTypePrototype,
     alliancePrototype, eventKindPrototype, inputKindPrototype, decisionKindPrototype, gameSpeedPrototype, cheatPrototype, gameModePrototype,
     unitSizePrototype, damageTypePrototype, locationFlagPrototype, drawFunctionPrototype, bulletStatePrototype,
     animationPrototype].forEach(freeze);

    return {
        exports: {
            UnitType: UnitType,
            WeaponType: WeaponType,
            FlingyType: FlingyType,
            SpriteType: SpriteType,
            ImageType: ImageType,
            UpgradeType: UpgradeType,
            TechType: TechType,
            Order: Order,
            Key: Key,
            MouseButton: MouseButton,
            Modifier: Modifier,
            UnitGroup: UnitGroup,
            ScoreType: ScoreType,
            ResourceType: ResourceType,
            Alliance: Alliance,
            EventKind: EventKind,
            InputKind: InputKind,
            DecisionKind: DecisionKind,
            GameSpeed: GameSpeed,
            Cheat: Cheat,
            GameMode: GameMode,
            UnitSize: UnitSize,
            DamageType: DamageType,
            LocationFlag: LocationFlag,
            DrawFunction: DrawFunction,
            BulletState: BulletState,
            Animation: Animation
        },
        shared: {
            GameSpeed: GameSpeed,
            GameMode: GameMode,
            cheatPrototype: cheatPrototype,
            locationFlagPrototype: locationFlagPrototype,
            drawFunctionPrototype: drawFunctionPrototype,
            animationPrototype: animationPrototype,
            spriteTypePrototype: spriteTypePrototype,
            weaponTypePrototype: weaponTypePrototype,
            unitTypePrototype: unitTypePrototype,
            imageTypePrototype: imageTypePrototype,
            upgradeTypePrototype: upgradeTypePrototype,
            techTypePrototype: techTypePrototype,
            orderPrototype: orderPrototype,
            scoreTypePrototype: scoreTypePrototype,
            resourceTypePrototype: resourceTypePrototype,
            alliancePrototype: alliancePrototype,
            readUnitOperand: readUnitOperand
        }
    };
});
