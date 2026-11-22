// The values of the upper layer of the SC:R Enhanced Script game API: the globals Position, Time and Color, and for the later prelude files
// the frames of the objects and constant tables, the option readers, the raw value scales and the state of the running entry point.
//
// Every prelude file is a function of core and the names the earlier files made (prelude); it returns the globals it makes (exports) and
// the names it hands to the later files (shared).
(function (core, prelude) {
    'use strict';

    // Hit points, shields, energy and damage are raw 1/256 units; supply counts half units.
    var RAW_SCALE = 256;
    var SUPPLY_SCALE = 2;
    var NONE = -1;
    var TILE_SIZE = 32;
    // The frames of one second of the trigger clock.
    var FRAMES_PER_CLOCK_SECOND = 16;
    var SECONDS_PER_MINUTE = 60;
    // The victory states of core.player.endGame and of the result events.
    var VictoryState = Object.freeze({Dropped: 1, Defeat: 2, Victory: 3, Secured: 4, Draw: 5});
    // Colors are numbers, packed 0xAARRGGBB with straight alpha.
    var MAXIMUM_CHANNEL = 255;
    var ALPHA_FACTOR = 0x1000000;
    var RED_SHIFT = 16;
    var GREEN_SHIFT = 8;
    // The hidden field of an object that keeps its group objects (defineGroup).
    var GROUPS_FIELD = 'groups';

    var create = Object.create;
    var freeze = Object.freeze;
    var defineProperty = Object.defineProperty;
    var getPrototypeOf = Object.getPrototypeOf;
    var isExtensible = Object.isExtensible;

    // Whether the running entry point is local code: localFrame, render and localInput set it, frame and decide clear it.
    var execution = {local: false};

    // Throws a TypeError in local code, for what only shared code may do.
    function requireShared(message) {
        if (execution.local) {
            throw new TypeError(message);
        }
    }

    // Creates an object of a prototype with hidden, read-only fields.
    function createObject(prototype, fields) {
        var object = create(prototype);

        for (var key in fields) {
            defineProperty(object, key, {value: fields[key]});
        }

        return object;
    }

    // Defines a group of an object's methods under a name of its prototype: a read-only, enumerable property whose group object, of the
    // group's prototype with the object as its hidden field owner, is made at the first read and kept in the object's hidden field groups, so
    // that one object always has the same group object.
    function defineGroup(prototype, name, groupPrototype) {
        defineProperty(prototype, name, {
            enumerable: true,
            get: function () {
                var groups = this[GROUPS_FIELD];

                if (groups === undefined) {
                    groups = {};

                    if (isExtensible(this)) {
                        defineProperty(this, GROUPS_FIELD, {value: groups});
                    }
                }

                var group = groups[name];

                if (group === undefined) {
                    group = freeze(createObject(groupPrototype, {owner: this}));
                    groups[name] = group;
                }

                return group;
            }
        });
    }

    // Whether a value is an object made directly of the prototype.
    function isObjectOf(value, prototype) {
        return value !== null && typeof value === 'object' && getPrototypeOf(value) === prototype;
    }

    // Returns the value when it is an object of the prototype; throws a TypeError otherwise.
    function requireObject(value, prototype, kind) {
        if (!isObjectOf(value, prototype)) {
            throw new TypeError(kind + ' is needed, not ' + value + '.');
        }

        return value;
    }

    // Returns an optional options object, empty when it is missing.
    function readOptions(options) {
        if (options === undefined) {
            return {};
        }

        if (options === null || typeof options !== 'object') {
            throw new TypeError('The options are an object, not ' + options + '.');
        }

        return options;
    }

    // Converts an original data name to an identifier: "Hero Jim Raynor Marine" becomes "HeroJimRaynorMarine".
    function toIdentifier(name) {
        var words = name.split(' ');
        var identifier = '';

        for (var index = 0; index < words.length; index++) {
            var word = words[index];

            if (word.length > 0) {
                identifier += word.charAt(0).toUpperCase() + word.substring(1);
            }
        }

        return identifier;
    }

    // The prototype of the type objects and constants: getId(), getName() and toString(), which returns the name.
    var typePrototype = {
        getId: function () {
            return this.id;
        },
        getName: function () {
            return this.name;
        },
        toString: function () {
            return this.name;
        }
    };

    // Adds get(id), null for an id outside the table, and getCount() to a constant table and freezes it.
    function defineTableFunctions(table, constants, count) {
        defineProperty(table, 'get', {
            value: function (id) {
                var constant = constants[id];

                return constant === undefined ? null : constant;
            }
        });
        defineProperty(table, 'getCount', {
            value: function () {
                return count;
            }
        });

        return freeze(table);
    }

    // Creates the constants of a type namespace of core: one object per id under the identifier of its name, get(id) and getCount().
    function createTypeTable(namespace, prototype) {
        var count = namespace.getCount();
        var types = [];
        var table = {};

        for (var id = 0; id < count; id++) {
            var type = freeze(createObject(prototype, {id: id, name: namespace.getName(id)}));

            types.push(type);
            table[toIdentifier(type.name)] = type;
        }

        return defineTableFunctions(table, types, count);
    }

    // Creates the constants of ids from 0 to count - 1: one object per id, under its name when it has one, get(id) and getCount().
    function createConstantTable(prototype, names, count, unnamedPrefix) {
        var constants = [];
        var table = {};

        for (var id = 0; id < count; id++) {
            var name = names[id];
            var constant = freeze(createObject(prototype, {id: id, name: name === undefined ? unnamedPrefix + id : name}));

            constants.push(constant);

            if (name !== undefined) {
                table[name] = constant;
            }
        }

        return defineTableFunctions(table, constants, count);
    }

    // The prototype of the positions: an x and a y in map pixels.
    var positionPrototype = {
        getX: function () {
            return this.x;
        },
        getY: function () {
            return this.y;
        },
        toString: function () {
            return '(' + this.x + ', ' + this.y + ')';
        }
    };

    function createPosition(x, y) {
        return freeze(createObject(positionPrototype, {x: x, y: y}));
    }

    // Position.of(x, y) creates a position.
    var Position = freeze({
                              of: function (x, y) {
                                  return createPosition(x, y);
                              }
                          });

    // The frames of a duration, rounded to the nearest frame (halves up). Seconds and minutes are those of the trigger clock, which the
    // countdown timer and the elapsed time count: sixteen frames a second. Milliseconds are real time at the fastest speed: the frames of the
    // game's frame duration.
    var Time = freeze({
                          fromSeconds: function (seconds) {
                              return Math.round(seconds * FRAMES_PER_CLOCK_SECOND);
                          },
                          fromMinutes: function (minutes) {
                              return Math.round(minutes * SECONDS_PER_MINUTE * FRAMES_PER_CLOCK_SECOND);
                          },
                          fromMilliseconds: function (milliseconds) {
                              return Math.round(milliseconds / core.game.getFrameDuration());
                          }
                      });

    // Reads a color channel: an integer from 0 to 255, or a RangeError.
    function readChannel(value, name) {
        var channel = Number(value);

        if (channel !== Math.floor(channel) || channel < 0 || channel > MAXIMUM_CHANNEL) {
            throw new RangeError('The ' + name + ' channel is an integer from 0 to 255, not ' + value + '.');
        }

        return channel;
    }

    // The alpha is the opacity from 0 to 1, like the opacity of drawn images; it is stored rounded to a byte.
    function readAlpha(value) {
        var alpha = Number(value);

        if (!(alpha >= 0 && alpha <= 1)) {
            throw new RangeError('The alpha is a number from 0 to 1, not ' + value + '.');
        }

        return Math.round(alpha * MAXIMUM_CHANNEL);
    }

    // The color constants and fromRgb (opaque), fromRgba (alpha from 0 to 1) and fromPalette; a channel outside its range throws a
    // RangeError.
    var Color = freeze({
                           Transparent: 0x00000000,
                           Black: 0xFF000000,
                           White: 0xFFFFFFFF,
                           Gray: 0xFF808080,
                           Red: 0xFFFF0000,
                           Green: 0xFF00FF00,
                           Blue: 0xFF0000FF,
                           Yellow: 0xFFFFFF00,
                           Cyan: 0xFF00FFFF,
                           Magenta: 0xFFFF00FF,
                           Orange: 0xFFFFA500,
                           fromRgb: function (red, green, blue) {
                               return Color.fromRgba(red, green, blue, 1);
                           },
                           fromRgba: function (red, green, blue, alpha) {
                               return readAlpha(alpha) * ALPHA_FACTOR +
                                      (readChannel(red, 'red') << RED_SHIFT | readChannel(green, 'green') << GREEN_SHIFT | readChannel(blue, 'blue'));
                           },
                           // The color of a palette index of the map's tileset palette, opaque.
                           fromPalette: function (index) {
                               return core.screen.getPaletteColor(index);
                           }
                       });

    freeze(typePrototype);
    freeze(positionPrototype);

    return {
        exports: {
            Position: Position,
            Time: Time,
            Color: Color
        },
        shared: {
            RAW_SCALE: RAW_SCALE,
            SUPPLY_SCALE: SUPPLY_SCALE,
            NONE: NONE,
            TILE_SIZE: TILE_SIZE,
            VictoryState: VictoryState,
            execution: execution,
            requireShared: requireShared,
            createObject: createObject,
            defineGroup: defineGroup,
            isObjectOf: isObjectOf,
            requireObject: requireObject,
            readOptions: readOptions,
            toIdentifier: toIdentifier,
            typePrototype: typePrototype,
            createTypeTable: createTypeTable,
            createConstantTable: createConstantTable,
            positionPrototype: positionPrototype,
            createPosition: createPosition
        }
    };
});
