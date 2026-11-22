// The game of the upper layer: the global game with its groups (game.map with the tileset, the terrain with its creep, the locations and the
// resources, game.players, game.units, game.switches, game.countdown, game.leaderboard, game.settings, game.vision, game.combat), and the Unit,
// Player, Location, Tile, Timer, Grp, Anim, Image, Sprite, SpriteImage and Bullet objects they return. One unit, player, location, tile, resource,
// sprite, sprite image or bullet is always the same object, and one object always has the same groups (player.upgrades, player.vision, unit.timers
// ...); a unit, sprite, sprite image or bullet object stays with its unit, sprite, image or bullet, and exists() becomes false once it is gone,
// when its slot holds another one. Hit points, shields and energy are the shown values (core's raw values divided by 256; no shields or energy for
// a unit without them), supply the shown supply (core's half units divided by 2). The timers of game.every and game.after are counted here, by
// frames.
//
// The methods that change the game state (the setters, add and subtract, kill, remove, issueOrder, create ...) are shared code only: the core
// function they call throws a TypeError in local code and while a decision hook runs. The presentation methods (colors, image frames, draw
// functions and graphics, the fog brightness) work anywhere. A method of a gone unit, sprite, sprite image or bullet throws an Error that it
// does not exist.
(function (core, prelude) {
    'use strict';

    var RAW_SCALE = prelude.RAW_SCALE;
    var SUPPLY_SCALE = prelude.SUPPLY_SCALE;
    var NONE = prelude.NONE;
    var TILE_SIZE = prelude.TILE_SIZE;
    var VictoryState = prelude.VictoryState;
    var requireShared = prelude.requireShared;
    var createObject = prelude.createObject;
    var defineGroup = prelude.defineGroup;
    var isObjectOf = prelude.isObjectOf;
    var requireObject = prelude.requireObject;
    var readOptions = prelude.readOptions;
    var positionPrototype = prelude.positionPrototype;
    var createPosition = prelude.createPosition;
    var UnitType = prelude.UnitType;
    var TechType = prelude.TechType;
    var UpgradeType = prelude.UpgradeType;
    var ImageType = prelude.ImageType;
    var Order = prelude.Order;
    var Alliance = prelude.Alliance;
    var unitTypePrototype = prelude.unitTypePrototype;
    var upgradeTypePrototype = prelude.upgradeTypePrototype;
    var techTypePrototype = prelude.techTypePrototype;
    var orderPrototype = prelude.orderPrototype;
    var scoreTypePrototype = prelude.scoreTypePrototype;
    var resourceTypePrototype = prelude.resourceTypePrototype;
    var alliancePrototype = prelude.alliancePrototype;
    var readUnitOperand = prelude.readUnitOperand;
    var GameSpeed = prelude.GameSpeed;
    var GameMode = prelude.GameMode;
    var cheatPrototype = prelude.cheatPrototype;
    var locationFlagPrototype = prelude.locationFlagPrototype;
    var getButtonSet = prelude.getButtonSet;
    var readButtonSetId = prelude.readButtonSetId;
    var SpriteType = prelude.SpriteType;
    var WeaponType = prelude.WeaponType;
    var FlingyType = prelude.FlingyType;
    var DrawFunction = prelude.DrawFunction;
    var BulletState = prelude.BulletState;
    var spriteTypePrototype = prelude.spriteTypePrototype;
    var weaponTypePrototype = prelude.weaponTypePrototype;
    var drawFunctionPrototype = prelude.drawFunctionPrototype;
    var animationPrototype = prelude.animationPrototype;

    var create = Object.create;
    var freeze = Object.freeze;
    var round = Math.round;
    var floor = Math.floor;
    var ceil = Math.ceil;
    var min = Math.min;
    var max = Math.max;

    // The leaderboard types of core.leaderboard: the action ids of the leaderboard actions and of their goal forms.
    var LEADERBOARD_CONTROL = 17;
    var LEADERBOARD_CONTROL_AT_LOCATION = 18;
    var LEADERBOARD_RESOURCES = 19;
    var LEADERBOARD_KILLS = 20;
    var LEADERBOARD_POINTS = 21;
    var LEADERBOARD_GOAL_CONTROL = 33;
    var LEADERBOARD_GOAL_CONTROL_AT_LOCATION = 34;
    var LEADERBOARD_GOAL_RESOURCES = 35;
    var LEADERBOARD_GOAL_KILLS = 36;
    var LEADERBOARD_GOAL_POINTS = 37;
    // An AI script id: four characters, the first one in the low byte.
    var AI_SCRIPT_LENGTH = 4;
    var BITS_PER_CHARACTER = 8;
    var LAST_SCRIPT_CHARACTER = 0xFF;
    var TILES_PER_GROUP = 16;
    var TILE_GROUP_SHIFT = 4;
    // The range of a timer interval: a signed 32-bit integer.
    var MINIMUM_INTEGER = -2147483648;
    var MAXIMUM_INTEGER = 2147483647;
    // The status timers of unit.timers: the core fields get<Name>Timer and set<Name>Timer.
    var UNIT_TIMER_NAMES = ['Stim', 'Ensnare', 'Lockdown', 'Stasis', 'Maelstrom', 'Plague', 'Irradiate', 'Storm', 'DefensiveMatrix', 'Remove'];
    var UNIT_TIMER_SUFFIX = 'Timer';
    // The elevation a new sprite starts with, which game.createEffect gives by default.
    var DEFAULT_EFFECT_ELEVATION = 4;
    // The owner byte the effects of the game take.
    var DEFAULT_EFFECT_OWNER = 0;
    // The players with a vision bit in the tile flags, and the shift of their explored bits above their visible bits.
    var VISION_PLAYER_COUNT = 8;
    var NOT_EXPLORED_SHIFT = 8;
    // The palette indices of a unit color row.
    var UNIT_COLOR_COUNT = 8;

    var playerCount = core.player.getCount();
    // The cheat bits game.setCheatEnabled turned on and off, which the frame entry point writes again every frame.
    var keptCheatsOn = 0;
    var keptCheatsOff = 0;
    var playerObjects = [];
    var unitObjects = [];
    var locationObjects = [];

    // Returns the object of a player id, null outside the player slots.
    function getPlayer(id) {
        if (!(id >= 0 && id < playerCount)) {
            return null;
        }

        var player = playerObjects[id];

        if (player === undefined) {
            player = createObject(playerPrototype, {id: id});
            playerObjects[id] = player;
        }

        return player;
    }

    // Returns the object of the unit in a slot: the cached one while the slot holds the same unit.
    function getUnit(id) {
        if (id === NONE || !core.unit.exists(id)) {
            return null;
        }

        var generation = core.unit.getGeneration(id);
        var unit = unitObjects[id];

        if (unit === undefined || unit.generation !== generation) {
            unit = createObject(unitPrototype, {id: id, generation: generation});
            unitObjects[id] = unit;
        }

        return unit;
    }

    // Returns the object of a unit that a record captured: the unit's own object while its slot holds the same unit, otherwise an object of
    // the gone unit whose exists() is false.
    function getCapturedUnit(id, generation) {
        if (id === NONE) {
            return null;
        }

        var unit = unitObjects[id];

        if (unit !== undefined && unit.generation === generation) {
            return unit;
        }

        if (core.unit.exists(id) && core.unit.getGeneration(id) === generation) {
            return getUnit(id);
        }

        return createObject(unitPrototype, {id: id, generation: generation});
    }

    // Creates the objects of the slots of a pool of core whose slots tell their tenants apart by generation (core.sprite, core.image,
    // core.bullet): get(id) the cached object of the tenant of a slot, getCaptured(id, generation) that of a tenant a record captured and
    // requireId(object) the slot of an object whose tenant still holds it.
    function createSlotObjects(pool, prototype, kind) {
        var objects = [];

        function get(id) {
            if (id === NONE || !pool.exists(id)) {
                return null;
            }

            var generation = pool.getGeneration(id);
            var object = objects[id];

            if (object === undefined || object.generation !== generation) {
                object = createObject(prototype, {id: id, generation: generation});
                objects[id] = object;
            }

            return object;
        }

        return {
            get: get,
            getCaptured: function (id, generation) {
                if (id === NONE) {
                    return null;
                }

                var object = objects[id];

                if (object !== undefined && object.generation === generation) {
                    return object;
                }

                if (pool.exists(id) && pool.getGeneration(id) === generation) {
                    return get(id);
                }

                return createObject(prototype, {id: id, generation: generation});
            },
            exists: function (object) {
                return pool.exists(object.id) && pool.getGeneration(object.id) === object.generation;
            },
            requireId: function (object) {
                if (!requireObject(object, prototype, 'A ' + kind).exists()) {
                    throw new Error('The ' + kind + ' ' + object.id + ' does not exist.');
                }

                return object.id;
            }
        };
    }

    // Returns the object of a location id, null outside the location table.
    function getLocation(id) {
        if (!(id >= 0 && id < core.location.getCount())) {
            return null;
        }

        var location = locationObjects[id];

        if (location === undefined) {
            location = createObject(locationPrototype, {id: id});
            locationObjects[id] = location;
        }

        return location;
    }

    // Returns the objects of the units of ids.
    function toUnits(ids) {
        return ids.map(function (id) {
            return getUnit(id);
        });
    }

    // Returns the slot id of a Unit; throws an Error when the unit no longer exists.
    function requireUnitId(unit) {
        if (!requireObject(unit, unitPrototype, 'A unit').exists()) {
            throw new Error('The unit ' + unit.id + ' does not exist.');
        }

        return unit.id;
    }

    // Deals a raw damage to a unit with an optional source: a unit (its owner is the attacking player) or a player.
    function damageUnit(id, amount, source) {
        if (source === undefined || source === null) {
            return core.unit.damage(id, amount, NONE, NONE);
        }

        if (isObjectOf(source, unitPrototype)) {
            var sourceId = requireUnitId(source);

            return core.unit.damage(id, amount, sourceId, core.unit.getOwner(sourceId));
        }

        return core.unit.damage(id, amount, NONE, requireObject(source, playerPrototype, 'A unit or a player').id);
    }

    // Returns the test of a unit filter: a function of the unit, or an object with an optional type (a UnitType) and owner (a Player).
    function createUnitTest(filter) {
        if (typeof filter === 'function') {
            return filter;
        }

        var type = filter === undefined || filter.type === undefined ? null : requireObject(filter.type, unitTypePrototype, 'A unit type');
        var owner = filter === undefined || filter.owner === undefined ? null : requireObject(filter.owner, playerPrototype, 'A player');

        return function (unit) {
            return (type === null || core.unit.getType(unit.id) === type.id) && (owner === null || core.unit.getOwner(unit.id) === owner.id);
        };
    }

    // Returns the units of ids, in their order, that pass a filter, up to a limit.
    function filterIds(ids, filter, limit) {
        var test = createUnitTest(filter);
        var found = [];

        for (var index = 0; index < ids.length && found.length < limit; index++) {
            var unit = getUnit(ids[index]);

            if (test(unit)) {
                found.push(unit);
            }
        }

        return found;
    }

    // Returns the live units that pass a filter, in slot order, up to a limit.
    function filterUnits(filter, limit) {
        return filterIds(core.unit.getIds(), filter, limit);
    }

    // Returns the players of a bit mask, one bit per player.
    function toPlayers(mask) {
        var found = [];

        for (var id = 0; id < playerCount; id++) {
            if ((mask & 1 << id) !== 0) {
                found.push(getPlayer(id));
            }
        }

        return found;
    }

    // Returns the bit mask of a Player or an array of players.
    function readPlayerMask(players) {
        var list = Array.isArray(players) ? players : [players];
        var mask = 0;

        for (var index = 0; index < list.length; index++) {
            mask |= 1 << requireObject(list[index], playerPrototype, 'A player').id;
        }

        return mask;
    }

    // The shown maximum shields of a unit: those of its type in units.dat when the type has shields.
    function getMaxShields(id) {
        var type = core.unit.getType(id);

        return core.dat.units.getShieldEnabled(type) !== 0 ? core.dat.units.getShieldAmount(type) : 0;
    }

    var unitPrototype = {
        getId: function () {
            return this.id;
        },
        exists: function () {
            return core.unit.exists(this.id) && core.unit.getGeneration(this.id) === this.generation;
        },
        getType: function () {
            return UnitType.get(core.unit.getType(requireUnitId(this)));
        },
        getOwner: function () {
            return getPlayer(core.unit.getOwner(requireUnitId(this)));
        },
        setOwner: function (player) {
            return core.unit.give(requireUnitId(this), requireObject(player, playerPrototype, 'A player').id);
        },
        getX: function () {
            return core.unit.getX(requireUnitId(this));
        },
        getY: function () {
            return core.unit.getY(requireUnitId(this));
        },
        getPosition: function () {
            var id = requireUnitId(this);

            return createPosition(core.unit.getX(id), core.unit.getY(id));
        },
        setPosition: function (position) {
            var id = requireUnitId(this);

            requireObject(position, positionPrototype, 'A position');

            return core.unit.move(id, position.x, position.y);
        },
        getHp: function () {
            return core.unit.getHp(requireUnitId(this)) / RAW_SCALE;
        },
        setHp: function (hp) {
            core.unit.setHp(requireUnitId(this), round(hp * RAW_SCALE));
        },
        // The hit points of the unit's type in units.dat.
        getMaxHp: function () {
            return core.dat.units.getHitPoints(core.unit.getType(requireUnitId(this))) / RAW_SCALE;
        },
        getShields: function () {
            var id = requireUnitId(this);

            return getMaxShields(id) === 0 ? 0 : core.unit.getShields(id) / RAW_SCALE;
        },
        setShields: function (shields) {
            return core.unit.setShields(requireUnitId(this), round(shields * RAW_SCALE));
        },
        getMaxShields: function () {
            return getMaxShields(requireUnitId(this));
        },
        getEnergy: function () {
            var id = requireUnitId(this);

            return core.unit.getMaxEnergy(id) === 0 ? 0 : core.unit.getEnergy(id) / RAW_SCALE;
        },
        setEnergy: function (energy) {
            return core.unit.setEnergy(requireUnitId(this), round(energy * RAW_SCALE));
        },
        getMaxEnergy: function () {
            return core.unit.getMaxEnergy(requireUnitId(this)) / RAW_SCALE;
        },
        // The percent writes take a percentage of the maximum, as the trigger actions do.
        setHpPercent: function (percent) {
            core.unit.setHpPercent(requireUnitId(this), percent);
        },
        setShieldsPercent: function (percent) {
            return core.unit.setShieldsPercent(requireUnitId(this), percent);
        },
        setEnergyPercent: function (percent) {
            return core.unit.setEnergyPercent(requireUnitId(this), percent);
        },
        getOrder: function () {
            return Order.get(core.unit.getOrder(requireUnitId(this)));
        },
        getTarget: function () {
            return getUnit(core.unit.getTarget(requireUnitId(this)));
        },
        getTargetPosition: function () {
            var id = requireUnitId(this);

            return createPosition(core.unit.getTargetX(id), core.unit.getTargetY(id));
        },
        isCompleted: function () {
            return core.unit.isCompleted(requireUnitId(this));
        },
        isCloaked: function () {
            return core.unit.isCloaked(requireUnitId(this));
        },
        isBurrowed: function () {
            return core.unit.isBurrowed(requireUnitId(this));
        },
        isInvincible: function () {
            return core.unit.isInvincible(requireUnitId(this));
        },
        // The image type of the unit's main image.
        getImageType: function () {
            return ImageType.get(core.unit.getImageType(requireUnitId(this)));
        },
        getSprite: function () {
            return sprites.get(core.unit.getSprite(requireUnitId(this)));
        },
        // Runs the use-weapon routine of the unit scripts: the bullets of a WeaponType toward the unit's order target. Shared code only.
        useWeapon: function (weaponType) {
            core.unit.useWeapon(requireUnitId(this), requireObject(weaponType, weaponTypePrototype, 'A weapon type').id);
        },
        // Plays an iscript Animation on the images of the unit's sprite as an order does; Init and Death throw a RangeError. Shared code only.
        playAnimation: function (animation) {
            core.unit.playAnimation(requireUnitId(this), requireObject(animation, animationPrototype, 'An animation').id);
        },
        setInvincible: function (invincible) {
            core.unit.setInvincible(requireUnitId(this), invincible);
        },
        // The disabled status of a doodad (or a unit under Lockdown, Stasis Field or Maelstrom), which Set Doodad State changes.
        isDisabled: function () {
            return core.unit.isDisabled(requireUnitId(this));
        },
        enableDoodad: function () {
            core.unit.enableDoodad(requireUnitId(this));
        },
        disableDoodad: function () {
            core.unit.disableDoodad(requireUnitId(this));
        },
        // The resource amount of a mineral field or geyser; setting it returns false for a unit that holds no resources.
        getResources: function () {
            return core.unit.getResources(requireUnitId(this));
        },
        setResources: function (amount) {
            return core.unit.setResources(requireUnitId(this), amount);
        },
        // The Interceptors or Scarabs of a Carrier or Reaver; adding builds up to count of them while the hangar takes another one and returns
        // false for a unit without a hangar.
        getHangarCount: function () {
            return core.unit.getHangarCount(requireUnitId(this));
        },
        addHangarCount: function (count) {
            return core.unit.addHangarCount(requireUnitId(this), count);
        },
        // Kills the unit; without a source as the Kill Unit action does, with a source unit or player through the damage routine, so that the
        // death and the kill count for the source.
        kill: function (source) {
            var id = requireUnitId(this);

            return source === undefined || source === null ? core.unit.kill(id) : damageUnit(id, core.unit.getHp(id), source);
        },
        // Takes hit points off the unit as a hit does after armor and shields; a unit whose hit points run out dies, credited to the source.
        damage: function (hp, source) {
            return damageUnit(requireUnitId(this), round(hp * RAW_SCALE), source);
        },
        remove: function () {
            return core.unit.remove(requireUnitId(this));
        },
        // Issues an order to a target unit or a position; without a target the unit's own position is the target. With {queued: true} the
        // order goes behind the unit's queue when the order takes one, as a queued command does.
        issueOrder: function (order, target, options) {
            var id = requireUnitId(this);
            var orderId = requireObject(order, orderPrototype, 'An order').id;
            var queued = readOptions(options).queued === true;

            if (target === undefined || target === null) {
                core.unit.issueOrder(id, orderId, NONE, core.unit.getX(id), core.unit.getY(id), queued);
            } else if (isObjectOf(target, unitPrototype)) {
                var targetId = requireUnitId(target);

                core.unit.issueOrder(id, orderId, targetId, core.unit.getX(targetId), core.unit.getY(targetId), queued);
            } else {
                requireObject(target, positionPrototype, 'A unit or a position');
                core.unit.issueOrder(id, orderId, NONE, target.x, target.y, queued);
            }
        },
        // The orders waiting behind the current one, first to last: {order, target (a Unit or null), position, unitType (a UnitType or null)}.
        getOrderQueue: function () {
            return core.unit.getOrderQueue(requireUnitId(this)).map(function (record) {
                return freeze({
                                  order: Order.get(record.order),
                                  target: getUnit(record.target),
                                  position: createPosition(record.x, record.y),
                                  unitType: UnitType.get(record.unitType)
                              });
            });
        },
        getSecondaryOrder: function () {
            return Order.get(core.unit.getSecondaryOrder(requireUnitId(this)));
        },
        getSecondaryOrderState: function () {
            return core.unit.getSecondaryOrderState(requireUnitId(this));
        },
        // Sets the secondary order as the native routine does: a changed order starts from its first state.
        setSecondaryOrder: function (order) {
            core.unit.setSecondaryOrder(requireUnitId(this), requireObject(order, orderPrototype, 'An order').id);
        },
        // The unit's command card button set, null for a set outside the table; the native routines of morphs, completion and burrowing set
        // it again.
        getButtonSet: function () {
            return getButtonSet(core.unit.getButtonSet(requireUnitId(this)));
        },
        setButtonSet: function (set) {
            core.unit.setButtonSet(requireUnitId(this), readButtonSetId(set));
        },
        // The shown hit points and shields a unit under construction gains each frame.
        getHpGain: function () {
            return core.unit.getHpGain(requireUnitId(this)) / RAW_SCALE;
        },
        setHpGain: function (hp) {
            core.unit.setHpGain(requireUnitId(this), round(hp * RAW_SCALE));
        },
        getShieldGain: function () {
            return core.unit.getShieldGain(requireUnitId(this)) / RAW_SCALE;
        },
        setShieldGain: function (shields) {
            core.unit.setShieldGain(requireUnitId(this), round(shields * RAW_SCALE));
        },
        // The shown hit points the unit had before it morphed, which a cancelled Zerg building gives back.
        getPreviousHp: function () {
            return core.unit.getPreviousHp(requireUnitId(this));
        },
        // Whether the player sees the unit: in the player's sight and, when cloaked or burrowed, detected.
        isVisibleTo: function (player) {
            return core.unit.isVisibleTo(requireUnitId(this), requireObject(player, playerPrototype, 'A player').id);
        },
        isDetectedBy: function (player) {
            return core.unit.isDetectedBy(requireUnitId(this), requireObject(player, playerPrototype, 'A player').id);
        },
        // The players whose sight covers the unit.
        getVisibleTo: function () {
            return toPlayers(core.unit.getVisibilityFlags(requireUnitId(this)));
        },
        // The unit's strength against air or ground targets for the computer players.
        getAiStrength: function (air) {
            var id = requireUnitId(this);

            return air ? core.unit.getAirStrength(id) : core.unit.getGroundStrength(id);
        },
        getDefensiveMatrixHp: function () {
            return core.unit.getDefensiveMatrixHp(requireUnitId(this)) / RAW_SCALE;
        },
        setDefensiveMatrixHp: function (hp) {
            core.unit.setDefensiveMatrixHp(requireUnitId(this), round(hp * RAW_SCALE));
        },
        isParasitedBy: function (player) {
            return (core.unit.getParasiteFlags(requireUnitId(this)) & 1 << requireObject(player, playerPrototype, 'A player').id) !== 0;
        },
        setParasitedBy: function (player, parasited) {
            var id = requireUnitId(this);
            var bit = 1 << requireObject(player, playerPrototype, 'A player').id;
            var flags = core.unit.getParasiteFlags(id);

            core.unit.setParasiteFlags(id, parasited ? flags | bit : flags & ~bit);
        },
        isBlind: function () {
            return core.unit.getBlindFlags(requireUnitId(this)) !== 0;
        },
        isBlindedBy: function (player) {
            return (core.unit.getBlindFlags(requireUnitId(this)) & 1 << requireObject(player, playerPrototype, 'A player').id) !== 0;
        },
        setBlindedBy: function (player, blinded) {
            var id = requireUnitId(this);
            var bit = 1 << requireObject(player, playerPrototype, 'A player').id;
            var flags = core.unit.getBlindFlags(id);

            core.unit.setBlindFlags(id, blinded ? flags | bit : flags & ~bit);
        },
        // The unit types in the build queue from the current one.
        getBuildQueue: function () {
            return core.unit.getBuildQueue(requireUnitId(this)).map(function (id) {
                return UnitType.get(id);
            });
        },
        getResearch: function () {
            return TechType.get(core.unit.getResearch(requireUnitId(this)));
        },
        getUpgrade: function () {
            return UpgradeType.get(core.unit.getUpgrade(requireUnitId(this)));
        },
        getAddon: function () {
            return getUnit(core.unit.getAddon(requireUnitId(this)));
        },
        getRallyPosition: function () {
            var id = requireUnitId(this);

            return createPosition(core.unit.getRallyX(id), core.unit.getRallyY(id));
        },
        // The rally writes return false for a unit type without a rally point.
        setRallyPosition: function (position) {
            var id = requireUnitId(this);

            requireObject(position, positionPrototype, 'A position');

            return core.unit.setRallyPosition(id, position.x, position.y);
        },
        getRallyUnit: function () {
            return getUnit(core.unit.getRallyUnit(requireUnitId(this)));
        },
        setRallyUnit: function (unit) {
            var id = requireUnitId(this);

            return core.unit.setRallyUnit(id, unit === null ? NONE : requireUnitId(unit));
        },
        getMoveTarget: function () {
            var id = requireUnitId(this);

            return createPosition(core.unit.getMoveTargetX(id), core.unit.getMoveTargetY(id));
        },
        setMoveTarget: function (position) {
            var id = requireUnitId(this);

            requireObject(position, positionPrototype, 'A position');
            core.unit.setMoveTarget(id, position.x, position.y);
        },
        // Changes the unit's type through the morph routine; false for a subunit, a subunit type or a type whose turret does not match.
        setType: function (type) {
            return core.unit.setType(requireUnitId(this), requireObject(type, unitTypePrototype, 'A unit type').id);
        },
        toString: function () {
            return 'Unit ' + this.id;
        }
    };

    // The raw unit fields with a get and a set: the acid spores, the cooldowns and the remaining times in frames, the kills and the direction.
    function defineUnitField(name) {
        unitPrototype['get' + name] = function () {
            return core.unit['get' + name](requireUnitId(this));
        };
        unitPrototype['set' + name] = function (value) {
            core.unit['set' + name](requireUnitId(this), value);
        };
    }

    ['AcidSporeCount', 'GroundCooldown', 'AirCooldown', 'SpellCooldown', 'KillCount', 'RemainingBuildTime', 'RemainingResearchTime',
     'Direction'].forEach(defineUnitField);

    // unit.timers: the status timers with a get and a set, in status steps of eight frames (a timer that starts runs the effect's apply
    // routine, 0 its end routine), and the remove timer of a timed unit in frames.
    var unitTimersPrototype = {};

    UNIT_TIMER_NAMES.forEach(function (name) {
        var field = name + UNIT_TIMER_SUFFIX;

        unitTimersPrototype['get' + name] = function () {
            return core.unit['get' + field](requireUnitId(this.owner));
        };
        unitTimersPrototype['set' + name] = function (value) {
            core.unit['set' + field](requireUnitId(this.owner), value);
        };
    });

    defineGroup(unitPrototype, 'timers', unitTimersPrototype);

    unitPrototype.getCurrentSpeed = function () {
        return core.unit.getCurrentSpeed(requireUnitId(this));
    };
    unitPrototype.getTopSpeed = function () {
        return core.unit.getTopSpeed(requireUnitId(this));
    };

    // Returns the location id of the location option, NONE without one.
    function readLocationOption(options) {
        var location = options.location;

        return location === undefined || location === null ? NONE : requireObject(location, locationPrototype, 'A location').id;
    }

    // Returns the id of an AI script name: four characters up to 0xFF, the first one in the low byte ('EnBk', '+Vi0').
    function readAiScript(name) {
        var text = String(name);
        var script = 0;

        if (text.length !== AI_SCRIPT_LENGTH) {
            throw new RangeError('An AI script name has four characters, not ' + text + '.');
        }

        for (var index = 0; index < AI_SCRIPT_LENGTH; index++) {
            var code = text.charCodeAt(index);

            if (code > LAST_SCRIPT_CHARACTER) {
                throw new RangeError('The AI script name ' + text + ' has a character above 0xFF.');
            }

            script |= code << index * BITS_PER_CHARACTER;
        }

        return script;
    }

    // Kills or removes a player's units of a UnitType or UnitGroup: {location: a Location to take only the units there, count: the most units
    // to take, all without one}.
    function removePlayerUnits(player, unit, options, remove) {
        var operand = readUnitOperand(unit);
        var values = readOptions(options);
        var location = readLocationOption(values);
        var count = values.count === undefined ? 0 : values.count;

        if (values.count !== undefined && !(count >= 1)) {
            throw new RangeError('The unit count ' + count + ' is not positive.');
        }

        if (remove) {
            core.player.removeUnits(player.id, operand, location, count);
        } else {
            core.player.killUnits(player.id, operand, location, count);
        }
    }

    // Shows a leaderboard without a location: the goal form with the goal option.
    function showLeaderboard(type, goalType, operand, label, options) {
        var goal = readOptions(options).goal;

        if (goal === undefined) {
            core.leaderboard.show(type, operand, 0, String(label));
        } else {
            core.leaderboard.show(goalType, operand, goal, String(label));
        }
    }

    var playerPrototype = {
        getId: function () {
            return this.id;
        },
        getName: function () {
            return core.player.getName(this.id);
        },
        getRace: function () {
            return core.player.getRace(this.id);
        },
        isHuman: function () {
            return core.player.isHuman(this.id);
        },
        getMinerals: function () {
            return core.player.getMinerals(this.id);
        },
        setMinerals: function (minerals) {
            core.player.setMinerals(this.id, minerals);
        },
        addMinerals: function (minerals) {
            core.player.addMinerals(this.id, minerals);
        },
        subtractMinerals: function (minerals) {
            core.player.subtractMinerals(this.id, minerals);
        },
        getGas: function () {
            return core.player.getGas(this.id);
        },
        setGas: function (gas) {
            core.player.setGas(this.id, gas);
        },
        addGas: function (gas) {
            core.player.addGas(this.id, gas);
        },
        subtractGas: function (gas) {
            core.player.subtractGas(this.id, gas);
        },
        // Ends the game for the player as the Victory, Defeat and Draw actions do; a defeat or a draw after a victory returns false.
        win: function () {
            return core.player.endGame(this.id, VictoryState.Victory);
        },
        lose: function () {
            return core.player.endGame(this.id, VictoryState.Defeat);
        },
        draw: function () {
            return core.player.endGame(this.id, VictoryState.Draw);
        },
        // The supply functions take a race from 0 (Zerg) to 2 (Protoss); without one, the player's race.
        getSupplyUsed: function (race) {
            return core.player.getSupplyUsed(this.id, race === undefined ? this.getRace() : race) / SUPPLY_SCALE;
        },
        getSupplyProvided: function (race) {
            return core.player.getSupplyProvided(this.id, race === undefined ? this.getRace() : race) / SUPPLY_SCALE;
        },
        getSupplyMax: function (race) {
            return core.player.getSupplyMax(this.id, race === undefined ? this.getRace() : race) / SUPPLY_SCALE;
        },
        isAlliedWith: function (player) {
            return core.player.getAlliance(this.id, requireObject(player, playerPrototype, 'A player').id) !== 0;
        },
        isEnemyOf: function (player) {
            return core.player.getAlliance(this.id, requireObject(player, playerPrototype, 'A player').id) === 0;
        },
        getAlliance: function (player) {
            return Alliance.get(core.player.getAlliance(this.id, requireObject(player, playerPrototype, 'A player').id));
        },
        // Sets the alliance toward another player as Set Alliance Status does; the player is one from 0 to 7.
        setAlliance: function (player, alliance) {
            var other = requireObject(player, playerPrototype, 'A player').id;

            core.player.setAlliance(this.id, other, requireObject(alliance, alliancePrototype, 'An alliance').id);
        },
        // Minerals, gas, or minerals and gas, as the Accumulate condition reads them.
        getResources: function (resourceType) {
            return core.player.getResources(this.id, requireObject(resourceType, resourceTypePrototype, 'A resource type').id);
        },
        // The opponents the Opponents condition counts.
        countOpponents: function () {
            return core.player.countOpponents(this.id);
        },
        // Starts an AI script by its four-character name, at a location when one is given; false when the player may not run it.
        runAiScript: function (name, location) {
            var script = readAiScript(name);

            if (location === undefined || location === null) {
                return core.player.runAiScript(this.id, script);
            }

            return core.player.runAiScriptAt(this.id, script, requireObject(location, locationPrototype, 'A location').id);
        },
        setMissionObjectives: function (text) {
            core.player.setMissionObjectives(this.id, String(text));
        },
        // The local display of the player's screen, as the trigger actions show it when the player is the local player. A printed text always
        // shows; with {forced: false} it shows when the subtitle option is on. The display functions (print, pingMinimap, playSound,
        // showTalkingPortrait, setUnitSpeechMuted, centerView) are shared code only.
        print: function (text, options) {
            core.player.print(this.id, String(text), readOptions(options).forced !== false);
        },
        pingMinimap: function (position) {
            requireObject(position, positionPrototype, 'A position');
            core.player.pingMinimap(this.id, position.x, position.y);
        },
        // Plays a sound file of the map archive by its path.
        playSound: function (path) {
            core.player.playSound(this.id, String(path));
        },
        // Shows the talking portrait of a unit type for a number of frames.
        showTalkingPortrait: function (unitType, frames) {
            var id = requireObject(unitType, unitTypePrototype, 'A unit type').id;

            core.player.showTalkingPortrait(this.id, id, round(frames * core.game.getFrameDuration()));
        },
        setUnitSpeechMuted: function (muted) {
            core.player.setUnitSpeechMuted(this.id, muted);
        },
        // Centers the screen on a position when the player is the local player; the screens of the other peers stay.
        centerView: function (position) {
            requireObject(position, positionPrototype, 'A position');
            core.player.centerView(this.id, position.x, position.y);
        },
        // The player's selection, which the selection commands change and every peer holds: the live units in selection order.
        getSelection: function () {
            return toUnits(core.player.getSelection(this.id));
        },
        // Replaces the selection as a selection command of the player does, with at most 12 units; the command's rules may leave some out (a
        // hidden unit, and in a selection of more than one unit a building, another player's unit and the like). True if every unit was
        // selected.
        setSelection: function (units) {
            if (!Array.isArray(units)) {
                throw new TypeError('An array of units is needed, not ' + units + '.');
            }

            var ids = units.map(function (unit) {
                return requireUnitId(unit);
            });

            core.player.setSelection(this.id, ids);

            var selected = core.player.getSelection(this.id);

            return ids.every(function (id) {
                return selected.indexOf(id) >= 0;
            });
        },
        // The gathered totals of the score.
        getGatheredMinerals: function () {
            return core.player.getGatheredMinerals(this.id);
        },
        setGatheredMinerals: function (minerals) {
            core.player.setGatheredMinerals(this.id, minerals);
        },
        getGatheredGas: function () {
            return core.player.getGatheredGas(this.id);
        },
        setGatheredGas: function (gas) {
            core.player.setGatheredGas(this.id, gas);
        },
        // The pauses a player from 0 to 7 has left in a network game.
        getPausesLeft: function () {
            return core.player.getPausesLeft(this.id);
        },
        setPausesLeft: function (pauses) {
            core.player.setPausesLeft(this.id, pauses);
        },
        // The player's start location, null for a player without one.
        getStartLocation: function () {
            var x = core.player.getStartLocationX(this.id);

            return x === NONE ? null : createPosition(x, core.player.getStartLocationY(this.id));
        },
        // Moves the start location; false for a player without one.
        setStartLocation: function (position) {
            requireObject(position, positionPrototype, 'A position');

            return core.player.setStartLocation(this.id, position.x, position.y);
        },
        // The palette indices of the player's unit color row, the eight remapped colors of a unit frame; the first one is also the HD team color.
        // Presentation: shared code sets it on every peer, local code only on this machine, from the next drawn frame.
        getColor: function () {
            var row = [];

            for (var index = 0; index < UNIT_COLOR_COUNT; index++) {
                row.push(core.player.getUnitColor(this.id, index));
            }

            return row;
        },
        setColor: function (paletteIndices) {
            if (!Array.isArray(paletteIndices) || paletteIndices.length !== UNIT_COLOR_COUNT) {
                throw new TypeError('An array of ' + UNIT_COLOR_COUNT + ' palette indices is needed, not ' + paletteIndices + '.');
            }

            for (var index = 0; index < UNIT_COLOR_COUNT; index++) {
                core.player.setUnitColor(this.id, index, paletteIndices[index]);
            }
        },
        // The palette index of the player's minimap dots.
        getMinimapColor: function () {
            return core.player.getMinimapColor(this.id);
        },
        setMinimapColor: function (paletteIndex) {
            core.player.setMinimapColor(this.id, paletteIndex);
        },
        toString: function () {
            return 'Player ' + this.id;
        }
    };

    // player.vision: the shared vision of the player and the tiles the player sees.
    var playerVisionPrototype = {
        // The players that see what this player's units see, this player included.
        getSharedWith: function () {
            return toPlayers(core.player.getSharedVision(this.owner.id));
        },
        // Sets the players that see what this player's units see; the player's own sight stays only when the player is among them.
        setSharedWith: function (players) {
            core.player.setSharedVision(this.owner.id, readPlayerMask(players));
        },
        isSharedWith: function (player) {
            return (core.player.getSharedVision(this.owner.id) & 1 << requireObject(player, playerPrototype, 'A player').id) !== 0;
        },
        shareWith: function (player, shared) {
            var bit = 1 << requireObject(player, playerPrototype, 'A player').id;
            var mask = core.player.getSharedVision(this.owner.id);

            core.player.setSharedVision(this.owner.id, (shared ? mask | bit : mask & ~bit) >>> 0);
        },
        canSee: function (position) {
            requireObject(position, positionPrototype, 'A position');

            return core.vision.isVisible(this.owner.id, position.x, position.y);
        },
        hasExplored: function (position) {
            requireObject(position, positionPrototype, 'A position');

            return core.vision.isExplored(this.owner.id, position.x, position.y);
        },
        // Marks the tile at a position explored by the player (still unseen until a unit sees it), or unexplored and unseen; players 0 to 7.
        // Shared code only.
        setExplored: function (position, explored) {
            var player = this.owner.id;

            requireObject(position, positionPrototype, 'A position');

            if (player >= VISION_PLAYER_COUNT) {
                throw new RangeError('The player ' + player + ' has no tile vision; players 0 to 7 have it.');
            }

            var tileX = floor(position.x / TILE_SIZE);
            var tileY = floor(position.y / TILE_SIZE);
            var bits = core.vision.getTileVisibility(tileX, tileY);
            var notVisible = 1 << player;
            var notExplored = notVisible << NOT_EXPLORED_SHIFT;

            core.vision.setTileVisibility(tileX, tileY, explored ? bits & ~notExplored : bits | notVisible | notExplored);
        }
    };

    // player.upgrades: the levels by UpgradeType.
    var playerUpgradesPrototype = {
        getLevel: function (upgrade) {
            return core.player.getUpgradeLevel(this.owner.id, requireObject(upgrade, upgradeTypePrototype, 'An upgrade type').id);
        },
        // Sets the level and refreshes the upgrade flags of the player's units, as a finished upgrade does.
        setLevel: function (upgrade, level) {
            core.player.setUpgradeLevel(this.owner.id, requireObject(upgrade, upgradeTypePrototype, 'An upgrade type').id, level);
        },
        getMaxLevel: function (upgrade) {
            return core.player.getMaxUpgradeLevel(this.owner.id, requireObject(upgrade, upgradeTypePrototype, 'An upgrade type').id);
        },
        setMaxLevel: function (upgrade, level) {
            core.player.setMaxUpgradeLevel(this.owner.id, requireObject(upgrade, upgradeTypePrototype, 'An upgrade type').id, level);
        },
        isInProgress: function (upgrade) {
            return core.player.isUpgradeInProgress(this.owner.id, requireObject(upgrade, upgradeTypePrototype, 'An upgrade type').id);
        }
    };

    // player.techs: the technologies by TechType.
    var playerTechsPrototype = {
        isResearched: function (tech) {
            return core.player.isTechResearched(this.owner.id, requireObject(tech, techTypePrototype, 'A tech type').id);
        },
        setResearched: function (tech, researched) {
            core.player.setTechResearched(this.owner.id, requireObject(tech, techTypePrototype, 'A tech type').id, researched);
        },
        isAvailable: function (tech) {
            return core.player.isTechAvailable(this.owner.id, requireObject(tech, techTypePrototype, 'A tech type').id);
        },
        setAvailable: function (tech, available) {
            core.player.setTechAvailable(this.owner.id, requireObject(tech, techTypePrototype, 'A tech type').id, available);
        },
        isInProgress: function (tech) {
            return core.player.isTechInProgress(this.owner.id, requireObject(tech, techTypePrototype, 'A tech type').id);
        }
    };

    // player.units: the player's units and their production availability.
    var playerUnitsPrototype = {
        getAll: function () {
            return filterUnits({owner: this.owner}, Infinity);
        },
        // Counts the player's units of a UnitType or a UnitGroup as the Command and Bring conditions do: {location: a Location to count only
        // the units there, completed: true to count only completed units}.
        count: function (unit, options) {
            var operand = readUnitOperand(unit);
            var values = readOptions(options);
            var location = readLocationOption(values);
            var completed = values.completed === true;

            return location === NONE ? core.player.countUnits(this.owner.id, operand, completed)
                                     : core.player.countUnitsAt(this.owner.id, operand, location, completed);
        },
        // Kills or removes the player's units as Kill Unit and Remove Unit do: {location: a Location, count: the most units, all without one}.
        kill: function (unit, options) {
            removePlayerUnits(this.owner, unit, options, false);
        },
        remove: function (unit, options) {
            removePlayerUnits(this.owner, unit, options, true);
        },
        // The map settings' production availability; a game without map settings allows every unit type.
        isAvailable: function (unitType) {
            return core.player.isUnitAvailable(this.owner.id, requireObject(unitType, unitTypePrototype, 'A unit type').id);
        },
        setAvailable: function (unitType, available) {
            core.player.setUnitAvailable(this.owner.id, requireObject(unitType, unitTypePrototype, 'A unit type').id, available);
        }
    };

    // The counters of the trigger conditions and actions: player.deaths (Deaths, Set Deaths) and player.kills (Kill) of a UnitType or a
    // UnitGroup, player.score (Score, Set Score; set for players 0 to 7) of a ScoreType.
    var playerDeathsPrototype = {
        get: function (unit) {
            return core.player.getDeaths(this.owner.id, readUnitOperand(unit));
        },
        set: function (unit, amount) {
            core.player.setDeaths(this.owner.id, readUnitOperand(unit), amount);
        },
        add: function (unit, amount) {
            core.player.addDeaths(this.owner.id, readUnitOperand(unit), amount);
        },
        subtract: function (unit, amount) {
            core.player.subtractDeaths(this.owner.id, readUnitOperand(unit), amount);
        }
    };

    var playerKillsPrototype = {
        get: function (unit) {
            return core.player.getKills(this.owner.id, readUnitOperand(unit));
        }
    };

    var playerScorePrototype = {
        get: function (scoreType) {
            return core.player.getScore(this.owner.id, requireObject(scoreType, scoreTypePrototype, 'A score type').id);
        },
        set: function (scoreType, amount) {
            core.player.setScore(this.owner.id, requireObject(scoreType, scoreTypePrototype, 'A score type').id, amount);
        },
        add: function (scoreType, amount) {
            core.player.addScore(this.owner.id, requireObject(scoreType, scoreTypePrototype, 'A score type').id, amount);
        },
        subtract: function (scoreType, amount) {
            core.player.subtractScore(this.owner.id, requireObject(scoreType, scoreTypePrototype, 'A score type').id, amount);
        }
    };

    defineGroup(playerPrototype, 'upgrades', playerUpgradesPrototype);
    defineGroup(playerPrototype, 'techs', playerTechsPrototype);
    defineGroup(playerPrototype, 'units', playerUnitsPrototype);
    defineGroup(playerPrototype, 'deaths', playerDeathsPrototype);
    defineGroup(playerPrototype, 'kills', playerKillsPrototype);
    defineGroup(playerPrototype, 'score', playerScorePrototype);
    defineGroup(playerPrototype, 'vision', playerVisionPrototype);

    var locationPrototype = {
        getId: function () {
            return this.id;
        },
        getName: function () {
            return core.location.getName(this.id);
        },
        getLeft: function () {
            return core.location.getLeft(this.id);
        },
        getTop: function () {
            return core.location.getTop(this.id);
        },
        getRight: function () {
            return core.location.getRight(this.id);
        },
        getBottom: function () {
            return core.location.getBottom(this.id);
        },
        getCenter: function () {
            return createPosition(core.location.getCenterX(this.id), core.location.getCenterY(this.id));
        },
        setBounds: function (left, top, right, bottom) {
            core.location.setBounds(this.id, left, top, right, bottom);
        },
        // Moves the location so the position is its center, kept inside the map as Move Location keeps it.
        setCenter: function (position) {
            requireObject(position, positionPrototype, 'A position');
            core.location.setCenter(this.id, position.x, position.y);
        },
        // The elevation flags: a set LocationFlag leaves the units at its elevation out of the location.
        hasFlag: function (flag) {
            return (core.location.getFlags(this.id) & requireObject(flag, locationFlagPrototype, 'A location flag').id) !== 0;
        },
        setFlag: function (flag, set) {
            var bit = requireObject(flag, locationFlagPrototype, 'A location flag').id;
            var flags = core.location.getFlags(this.id);

            core.location.setFlags(this.id, set ? flags | bit : flags & ~bit);
        },
        toString: function () {
            return 'Location ' + this.id;
        }
    };

    var tilePrototype = {
        getId: function () {
            return this.id;
        },
        getGroup: function () {
            return this.id >> TILE_GROUP_SHIFT;
        },
        getIndex: function () {
            return this.id & (TILES_PER_GROUP - 1);
        },
        isWalkable: function () {
            return core.terrain.isTileWalkable(this.id);
        },
        isPartiallyWalkable: function () {
            return core.terrain.isTilePartiallyWalkable(this.id);
        },
        isBuildable: function () {
            return core.terrain.isTileBuildable(this.id);
        },
        hasCreep: function () {
            return core.terrain.hasTileCreep(this.id);
        },
        getElevation: function () {
            return core.terrain.getTileElevation(this.id);
        },
        isBlockingSight: function () {
            return core.terrain.isTileBlockingSight(this.id);
        },
        toString: function () {
            return 'Tile ' + this.getGroup() + ':' + this.getIndex();
        }
    };
    var tiles = [];

    // Returns the object of a tile value: one object per value.
    function getTileObject(id) {
        var tile = tiles[id];

        if (tile === undefined) {
            tile = freeze(createObject(tilePrototype, {id: id}));
            tiles[id] = tile;
        }

        return tile;
    }

    // The tiles a location covers, clipped to the map: {left, top, right, bottom}, right and bottom exclusive.
    function readLocationTiles(location) {
        var id = requireObject(location, locationPrototype, 'A location').id;
        var left = core.location.getLeft(id);
        var top = core.location.getTop(id);
        var right = core.location.getRight(id);
        var bottom = core.location.getBottom(id);

        return {
            left: max(0, floor(min(left, right) / TILE_SIZE)),
            top: max(0, floor(min(top, bottom) / TILE_SIZE)),
            right: min(core.game.getMapTileWidth(), ceil(max(left, right) / TILE_SIZE)),
            bottom: min(core.game.getMapTileHeight(), ceil(max(top, bottom) / TILE_SIZE))
        };
    }

    var tileset = freeze({
                             getId: function () {
                                 return core.game.getTileset();
                             },
                             getGroupCount: function () {
                                 return core.terrain.getTileGroupCount();
                             },
                             // Returns the tile of a CV5 group and an index in it from 0 to 15.
                             getTile: function (group, index) {
                                 if (index !== (index | 0) || index < 0 || index >= TILES_PER_GROUP) {
                                     throw new RangeError('The tile index ' + index + ' is not from 0 to ' + (TILES_PER_GROUP - 1) + '.');
                                 }

                                 if (group !== (group | 0) || group < 0 || !core.terrain.isValidTile(group << TILE_GROUP_SHIFT | index)) {
                                     throw new RangeError('The tile ' + group + ':' + index + ' is not a tile of the tileset of the map.');
                                 }

                                 return getTileObject(group << TILE_GROUP_SHIFT | index);
                             }
                         });

    // A sprite of the sprite pool. The pools of sprites and images also hold the buildings the local player remembers in the fog of war, so
    // the slots can differ from peer to peer: shared code may show them but should not let them decide the game.
    var spritePrototype = {
        getId: function () {
            return this.id;
        },
        exists: function () {
            return sprites.exists(this);
        },
        getType: function () {
            return SpriteType.get(core.sprite.getType(sprites.requireId(this)));
        },
        getPosition: function () {
            var id = sprites.requireId(this);

            return createPosition(core.sprite.getX(id), core.sprite.getY(id));
        },
        getOwner: function () {
            return getPlayer(core.sprite.getOwner(sprites.requireId(this)));
        },
        getElevation: function () {
            return core.sprite.getElevation(sprites.requireId(this));
        },
        // The images of the sprite, from the one drawn on top.
        getImages: function () {
            return core.sprite.getImages(sprites.requireId(this)).map(function (id) {
                return spriteImages.get(id);
            });
        },
        getMainImage: function () {
            return spriteImages.get(core.sprite.getMainImage(sprites.requireId(this)));
        },
        isVisibleTo: function (player) {
            var bit = 1 << requireObject(player, playerPrototype, 'A player').id;

            return (core.sprite.getVisibilityFlags(sprites.requireId(this)) & bit) !== 0;
        }
    };

    // An image of the image pool. Its frame, draw function and graphics change only what is drawn: shared code changes them on every peer,
    // local code on the local machine only.
    var spriteImagePrototype = {
        getId: function () {
            return this.id;
        },
        exists: function () {
            return spriteImages.exists(this);
        },
        getType: function () {
            return ImageType.get(core.image.getType(spriteImages.requireId(this)));
        },
        getSprite: function () {
            return sprites.get(core.image.getSprite(spriteImages.requireId(this)));
        },
        // The drawn frame.
        getFrame: function () {
            return core.image.getFrame(spriteImages.requireId(this));
        },
        // Draws a frame until the image's own frame changes, as an EUD write of the frame does; null draws the image's own frame again.
        setFrame: function (frame) {
            core.graphics.setImageFrame(spriteImages.requireId(this), frame === null ? NONE : frame);
        },
        // Draws a frame while the image's iscript steps its own frame, until null releases it or another frame is set.
        holdFrame: function (frame) {
            core.graphics.holdImageFrame(spriteImages.requireId(this), frame === null ? NONE : frame);
        },
        // The frame offset of the image's direction inside its frame group.
        getDirection: function () {
            return core.image.getDirection(spriteImages.requireId(this));
        },
        getDrawFunction: function () {
            return DrawFunction.get(core.image.getDrawFunction(spriteImages.requireId(this)));
        },
        // Draws with a DrawFunction until the image's own draw function changes; null draws with its own again.
        setDrawFunction: function (drawFunction) {
            var value = drawFunction === null ? NONE : requireObject(drawFunction, drawFunctionPrototype, 'A draw function').id;

            core.graphics.setImageDrawFunction(spriteImages.requireId(this), value);
        },
        getOffsetX: function () {
            return core.image.getOffsetX(spriteImages.requireId(this));
        },
        getOffsetY: function () {
            return core.image.getOffsetY(spriteImages.requireId(this));
        },
        // Replaces the graphics of this image with {sd: a GRP, hd: an HD anim, hd2: an HD2 anim} while it lives.
        replaceGraphics: function (images) {
            var id = spriteImages.requireId(this);
            var ids = readGraphicsIds(images);

            core.graphics.replaceImageGraphics(id, ids[0], ids[1], ids[2]);
        },
        restoreGraphics: function () {
            core.graphics.restoreImageGraphics(spriteImages.requireId(this));
        }
    };

    // A bullet of the bullet table, from its creation until it is released after dying.
    var bulletPrototype = {
        getId: function () {
            return this.id;
        },
        exists: function () {
            return bullets.exists(this);
        },
        getWeapon: function () {
            return WeaponType.get(core.bullet.getWeapon(bullets.requireId(this)));
        },
        getFlingyType: function () {
            return FlingyType.get(core.bullet.getFlingyType(bullets.requireId(this)));
        },
        getPosition: function () {
            var id = bullets.requireId(this);

            return createPosition(core.bullet.getX(id), core.bullet.getY(id));
        },
        getPlayer: function () {
            return getPlayer(core.bullet.getPlayer(bullets.requireId(this)));
        },
        // The unit that fired the bullet, null once it is gone.
        getSource: function () {
            return getUnit(core.bullet.getSource(bullets.requireId(this)));
        },
        getTarget: function () {
            return getUnit(core.bullet.getTarget(bullets.requireId(this)));
        },
        // The center of the hit.
        getTargetPosition: function () {
            var id = bullets.requireId(this);

            return createPosition(core.bullet.getTargetX(id), core.bullet.getTargetY(id));
        },
        // Sets the target Unit (null for none) or the target Position, as an EUD write of the bullet does: a following, bouncing or
        // near-target bullet turns to a new target unit, a moving bullet keeps its course; the hit damages the target unit and is centered on
        // the target position. Shared code only.
        setTarget: function (target) {
            var id = bullets.requireId(this);

            if (isObjectOf(target, positionPrototype)) {
                core.bullet.setTargetPosition(id, target.x, target.y);
            } else {
                core.bullet.setTarget(id, target === null ? NONE : requireUnitId(target));
            }
        },
        // The frames a moving bullet still flies, or a bullet that persists on its target position still hits; at most 255.
        getRemainingTime: function () {
            return core.bullet.getRemainingTime(bullets.requireId(this));
        },
        setRemainingTime: function (frames) {
            core.bullet.setRemainingTime(bullets.requireId(this), frames);
        },
        getRemainingBounces: function () {
            return core.bullet.getRemainingBounces(bullets.requireId(this));
        },
        getState: function () {
            return BulletState.get(core.bullet.getState(bullets.requireId(this)));
        },
        getDirection: function () {
            return core.bullet.getDirection(bullets.requireId(this));
        },
        // The sprite, null once its last image ended.
        getSprite: function () {
            return sprites.get(core.bullet.getSprite(bullets.requireId(this)));
        }
    };

    var sprites = createSlotObjects(core.sprite, spritePrototype, 'sprite');
    var spriteImages = createSlotObjects(core.image, spriteImagePrototype, 'sprite image');
    var bullets = createSlotObjects(core.bullet, bulletPrototype, 'bullet');

    // The creep of the map, by tile coordinates. Creep that these functions add has no source: it stays until it is removed or recedes.
    var creep = freeze({
                           // Whether a tile has creep, by the creep flag the rules of the game read.
                           has: function (tileX, tileY) {
                               return core.terrain.hasCreep(tileX, tileY);
                           },
                           // Covers the footprint of a building of a UnitType at a position with creep and grows it until no tile is added, as the
                           // game does for a building that needs creep. Shared code only.
                           fill: function (unitType, position) {
                               requireObject(position, positionPrototype, 'A position');
                               core.creep.fill(requireObject(unitType, unitTypePrototype, 'A unit type').id, position.x, position.y);
                           },
                           // Runs one spread step of a creep source of a UnitType at a position: true when it added a tile. Shared code only.
                           spread: function (unitType, position) {
                               requireObject(position, positionPrototype, 'A position');

                               return core.creep.spread(requireObject(unitType, unitTypePrototype, 'A unit type').id, position.x, position.y) === 1;
                           },
                           // Removes the creep of a tile at once. Shared code only.
                           remove: function (tileX, tileY) {
                               core.creep.removeTile(tileX, tileY);
                           },
                           // Makes a creep tile recede later, as the creep of a removed building does: false for a tile that is not creep, already
                           // recedes, lies under a building or that a Zerg building sustains, and when the recession is full. Shared code only.
                           recede: function (tileX, tileY) {
                               return core.creep.recede(tileX, tileY);
                           }
                       });

    // The tiles of the map. The changes apply at the next trigger step, after the frame start hooks and before the triggers; reads see the
    // changes made so far.
    var terrain = freeze({
                             creep: creep,
                             getTile: function (tileX, tileY) {
                                 return getTileObject(core.terrain.getTile(tileX, tileY));
                             },
                             setTile: function (tileX, tileY, tile) {
                                 core.terrain.setTile(tileX, tileY, requireObject(tile, tilePrototype, 'A tile').id);
                             },
                             // Sets every tile a location covers.
                             fillTiles: function (location, tile) {
                                 var id = requireObject(tile, tilePrototype, 'A tile').id;
                                 var area = readLocationTiles(location);

                                 for (var tileY = area.top; tileY < area.bottom; tileY++) {
                                     for (var tileX = area.left; tileX < area.right; tileX++) {
                                         core.terrain.setTile(tileX, tileY, id);
                                     }
                                 }
                             },
                             // Copies the tiles one location covers onto the tiles another location of the same size in tiles covers.
                             copyTiles: function (fromLocation, toLocation) {
                                 var source = readLocationTiles(fromLocation);
                                 var target = readLocationTiles(toLocation);
                                 var width = source.right - source.left;
                                 var height = source.bottom - source.top;

                                 if (width !== target.right - target.left || height !== target.bottom - target.top) {
                                     throw new RangeError('The locations cover ' + width + 'x' + height + ' and ' + (target.right - target.left) + 'x' +
                                                          (target.bottom - target.top) + ' tiles, not the same size.');
                                 }

                                 var values = [];

                                 for (var row = 0; row < height; row++) {
                                     for (var column = 0; column < width; column++) {
                                         values.push(core.terrain.getTile(source.left + column, source.top + row));
                                     }
                                 }

                                 for (var index = 0; index < values.length; index++) {
                                     core.terrain.setTile(target.left + index % width, target.top + floor(index / width), values[index]);
                                 }
                             }
                         });

    var locations = freeze({
                               get: function (id) {
                                   return getLocation(id);
                               },
                               findByName: function (name) {
                                   return getLocation(core.location.findByName(String(name)));
                               }
                           });

    // The files of the map's resource folder.
    var resourcePrototype = {
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
    var grpPrototype = create(resourcePrototype);
    var animPrototype = create(resourcePrototype);
    var imagePrototype = create(resourcePrototype);

    // Returns the handle id of an optional resource, NONE when left out.
    function readResourceId(resource, prototype, kind) {
        return resource === undefined || resource === null ? NONE : requireObject(resource, prototype, kind).id;
    }

    // Returns the handle ids of the graphics {sd, hd, hd2}: a GRP, an HD anim and an HD2 anim, each optional.
    function readGraphicsIds(images) {
        if (images === null || typeof images !== 'object') {
            throw new TypeError('The graphics {sd, hd, hd2} are needed, not ' + images + '.');
        }

        return [readResourceId(images.sd, grpPrototype, 'A GRP'), readResourceId(images.hd, animPrototype, 'An anim'),
                readResourceId(images.hd2, animPrototype, 'An anim')];
    }

    var resourceObjects = [];

    // The pixel size of an image.
    imagePrototype.getWidth = function () {
        return core.resources.getImageWidth(this.id);
    };
    imagePrototype.getHeight = function () {
        return core.resources.getImageHeight(this.id);
    };

    // Returns the object of a resource handle: one object per handle id.
    function getResource(id, name, prototype) {
        var resource = resourceObjects[id];

        if (resource === undefined) {
            resource = freeze(createObject(prototype, {id: id, name: name}));
            resourceObjects[id] = resource;
        }

        return resource;
    }

    var resources = freeze({
                               has: function (name) {
                                   return core.resources.has(String(name));
                               },
                               getGrp: function (name) {
                                   var text = String(name);

                                   return getResource(core.resources.getGrp(text), text, grpPrototype);
                               },
                               getAnim: function (name) {
                                   var text = String(name);

                                   return getResource(core.resources.getAnim(text), text, animPrototype);
                               },
                               // A PNG or DDS picture to draw (screen.drawImage) or to show on the console.
                               getImage: function (name) {
                                   var text = String(name);

                                   return getResource(core.resources.getImage(text), text, imagePrototype);
                               },
                               // Any file as UTF-8 text.
                               readText: function (name) {
                                   return core.resources.readText(String(name));
                               },
                               // A JSON file as a new value: each call parses the text again, so a change to the value stays with the caller.
                               readJson: function (name) {
                                   return JSON.parse(core.resources.readText(String(name)));
                               }
                           });

    var gameMap = freeze({
                             getWidth: function () {
                                 return core.game.getMapTileWidth() * TILE_SIZE;
                             },
                             getHeight: function () {
                                 return core.game.getMapTileHeight() * TILE_SIZE;
                             },
                             getTileWidth: function () {
                                 return core.game.getMapTileWidth();
                             },
                             getTileHeight: function () {
                                 return core.game.getMapTileHeight();
                             },
                             tileset: tileset,
                             terrain: terrain,
                             locations: locations,
                             resources: resources
                         });

    var players = freeze({
                             get: function (id) {
                                 return getPlayer(id);
                             },
                             getAll: function () {
                                 var all = [];

                                 for (var id = 0; id < playerCount; id++) {
                                     all.push(getPlayer(id));
                                 }

                                 return all;
                             },
                             getHumans: function () {
                                 return players.getAll().filter(function (player) {
                                     return player.isHuman();
                                 });
                             }
                         });

    var units = freeze({
                           get: function (id) {
                               return getUnit(id);
                           },
                           getAll: function () {
                               return filterUnits(undefined, Infinity);
                           },
                           find: function (filter) {
                               return filterUnits(filter, Infinity);
                           },
                           findOne: function (filter) {
                               var found = filterUnits(filter, 1);

                               return found.length > 0 ? found[0] : null;
                           },
                           count: function (filter) {
                               return filterUnits(filter, Infinity).length;
                           },
                           // The live units whose bounds meet the rectangle of two corners, in the unit finder's order, that pass the filter. Shared
                           // code only.
                           findInRectangle: function (corner, otherCorner, filter) {
                               requireObject(corner, positionPrototype, 'A position');
                               requireObject(otherCorner, positionPrototype, 'A position');

                               var ids = core.unit.findInRectangle(min(corner.x, otherCorner.x), min(corner.y, otherCorner.y), max(corner.x, otherCorner.x),
                                                                   max(corner.y, otherCorner.y));

                               return filterIds(ids, filter, Infinity);
                           },
                           // The live units whose bounds meet the square around a position and whose position lies within the radius of it, that pass
                           // the filter. Shared code only.
                           findInRadius: function (position, radius, filter) {
                               requireObject(position, positionPrototype, 'A position');

                               if (!(radius >= 0)) {
                                   throw new RangeError('The radius ' + radius + ' is not a number of at least 0.');
                               }

                               var test = createUnitTest(filter);
                               var ids = core.unit.findInRectangle(floor(position.x - radius), floor(position.y - radius), ceil(position.x + radius),
                                                                   ceil(position.y + radius));

                               return filterIds(ids, function (unit) {
                                   var dx = core.unit.getX(unit.id) - position.x;
                                   var dy = core.unit.getY(unit.id) - position.y;

                                   return dx * dx + dy * dy <= radius * radius && test(unit);
                               }, Infinity);
                           },
                           // Creates a unit as Create Unit does; null when none was created. Shared code only.
                           create: function (type, player, position) {
                               requireObject(type, unitTypePrototype, 'A unit type');
                               requireObject(player, playerPrototype, 'A player');
                               requireObject(position, positionPrototype, 'A position');

                               return getUnit(core.unit.create(type.id, player.id, position.x, position.y));
                           }
                       });

    // The switches 0 to 255 of the trigger state.
    var switches = freeze({
                              isSet: function (index) {
                                  return core.game.isSwitchSet(index);
                              },
                              set: function (index, set) {
                                  core.game.setSwitch(index, set);
                              },
                              toggle: function (index) {
                                  core.game.toggleSwitch(index);
                              }
                          });

    // The countdown timer counts game seconds of the trigger clock, one every sixteen frames, not frames.
    var countdown = freeze({
                               get: function () {
                                   return core.game.getCountdownTimer();
                               },
                               set: function (seconds) {
                                   core.game.setCountdownTimer(seconds);
                               },
                               add: function (seconds) {
                                   core.game.addCountdownTimer(seconds);
                               },
                               subtract: function (seconds) {
                                   core.game.subtractCountdownTimer(seconds);
                               },
                               isPaused: function () {
                                   return core.game.isCountdownTimerPaused();
                               },
                               pause: function () {
                                   core.game.setCountdownTimerPaused(true);
                               },
                               resume: function () {
                                   core.game.setCountdownTimerPaused(false);
                               }
                           });

    // The leaderboards of the leaderboard actions with a text label: the goal form with {goal: amount}, Control also {location: a Location}.
    var leaderboard = freeze({
                                 showControl: function (label, unit, options) {
                                     var operand = readUnitOperand(unit);
                                     var values = readOptions(options);
                                     var location = readLocationOption(values);

                                     if (location === NONE) {
                                         showLeaderboard(LEADERBOARD_CONTROL, LEADERBOARD_GOAL_CONTROL, operand, label, values);
                                     } else if (values.goal === undefined) {
                                         core.leaderboard.showAt(LEADERBOARD_CONTROL_AT_LOCATION, location, operand, 0, String(label));
                                     } else {
                                         core.leaderboard.showAt(LEADERBOARD_GOAL_CONTROL_AT_LOCATION, location, operand, values.goal, String(label));
                                     }
                                 },
                                 showResources: function (label, resourceType, options) {
                                     var operand = requireObject(resourceType, resourceTypePrototype, 'A resource type').id;

                                     showLeaderboard(LEADERBOARD_RESOURCES, LEADERBOARD_GOAL_RESOURCES, operand, label, options);
                                 },
                                 showKills: function (label, unit, options) {
                                     showLeaderboard(LEADERBOARD_KILLS, LEADERBOARD_GOAL_KILLS, readUnitOperand(unit), label, options);
                                 },
                                 showPoints: function (label, scoreType, options) {
                                     var operand = requireObject(scoreType, scoreTypePrototype, 'A score type').id;

                                     showLeaderboard(LEADERBOARD_POINTS, LEADERBOARD_GOAL_POINTS, operand, label, options);
                                 },
                                 // The Greed leaderboard of the ore and gas of every player, toward a goal.
                                 showGreed: function (goal) {
                                     core.leaderboard.showGreed(goal);
                                 },
                                 areComputersIncluded: function () {
                                     return core.leaderboard.areComputersIncluded();
                                 },
                                 setComputersIncluded: function (included) {
                                     core.leaderboard.setComputersIncluded(included);
                                 }
                             });

    // The timers of game.every and game.after: a timer runs first at the frame its interval after the game frame it was added at, then every
    // interval or, for game.after, never again; the timers of a frame run in the order of their due frames, then of their adding. Only shared
    // code adds and cancels timers.
    var timers = [];
    var nextTimerId = 1;

    var timerPrototype = {
        getId: function () {
            return this.id;
        },
        // Cancels the timer; false if it was cancelled already.
        cancel: function () {
            requireShared('Local code cannot cancel a timer.');

            for (var index = 0; index < timers.length; index++) {
                if (timers[index].id === this.id) {
                    timers.splice(index, 1);

                    return true;
                }
            }

            return false;
        }
    };

    function addTimer(frames, callback, once) {
        requireShared('Local code cannot add a timer.');

        if (typeof frames !== 'number' || frames !== floor(frames) || frames < MINIMUM_INTEGER || frames > MAXIMUM_INTEGER) {
            throw new TypeError('The timer interval is an integer, not ' + frames + '.');
        }

        if (typeof callback !== 'function') {
            throw new TypeError('A timer function is needed, not ' + callback + '.');
        }

        if (frames < 1) {
            throw new RangeError('The timer interval ' + frames + ' is not positive.');
        }

        var timer = {id: nextTimerId++, interval: frames, callback: callback, dueFrame: core.game.getFrame() + frames, once: once};

        timers.push(timer);

        return freeze(createObject(timerPrototype, {id: timer.id}));
    }

    // Takes the next due timer of a frame: the timer with the earliest due frame up to the frame, the earliest added on a tie. A repeating
    // timer becomes due again its interval after the frame, a one-shot timer is removed. Returns its function, or null when no timer is due.
    function pollDueTimer(frame) {
        var due = null;
        var dueIndex = NONE;

        for (var index = 0; index < timers.length; index++) {
            var timer = timers[index];

            if (timer.dueFrame <= frame && (due === null || timer.dueFrame < due.dueFrame)) {
                due = timer;
                dueIndex = index;
            }
        }

        if (due === null) {
            return null;
        }

        if (due.once) {
            timers.splice(dueIndex, 1);
        } else {
            due.dueFrame = frame + due.interval;
        }

        return due.callback;
    }

    // The game settings the game started with, and the expansion byte.
    var settings = freeze({
                              // The game type of the game header, a GameMode.
                              getMode: function () {
                                  return GameMode.get(core.game.getGameType());
                              },
                              getSubtype: function () {
                                  return core.game.getGameSubtype();
                              },
                              isMultiplayer: function () {
                                  return core.game.isMultiplayer();
                              },
                              // The raw game options: the victory condition, the resource setting, the starting minerals, the starting units and the
                              // tournament mode.
                              getVictoryCondition: function () {
                                  return core.game.getVictoryCondition();
                              },
                              getResourceType: function () {
                                  return core.game.getResourceType();
                              },
                              getStartingMinerals: function () {
                                  return core.game.getStartingMinerals();
                              },
                              getStartingUnits: function () {
                                  return core.game.getStartingUnits();
                              },
                              getTournamentMode: function () {
                                  return core.game.getTournamentMode();
                              },
                              // Whether the game options permit the cheat command.
                              areCheatsAllowed: function () {
                                  return core.game.areCheatsEnabled();
                              },
                              // Whether Brood War content is on: the expansion byte is not 0.
                              isExpansion: function () {
                                  return core.game.getExpansion() !== 0;
                              },
                              // Sets the expansion byte to 1 or 0. Shared code only.
                              setExpansion: function (expansion) {
                                  core.game.setExpansion(expansion ? 1 : 0);
                              }
                          });

    // The sight of the players.
    var vision = freeze({
                            // Reveals the tiles around a position to a Player or an array of players from 0 to 7 as a unit's sight with a sight range
                            // from 0 to 11 does, until the game marks the tiles unseen again; {air: true} sees over higher ground. Shared code only.
                            reveal: function (players, position, sightRange, options) {
                                requireObject(position, positionPrototype, 'A position');
                                core.vision.reveal(readPlayerMask(players), position.x, position.y, sightRange, readOptions(options).air === true);
                            },
                            // The fog brightness of visible tiles (default 31): the world's fog draws visible tiles at this level, explored tiles at
                            // half of it; the minimap's fog stays. Works anywhere.
                            getFogBrightness: function () {
                                return core.vision.getFogBrightness();
                            },
                            setFogBrightness: function (level) {
                                core.vision.setFogBrightness(level);
                            }
                        });

    // The combat tables; the damage multipliers are on DamageType.
    var combat = freeze({
                            // The distances in pixels a fleeing unit tries, in order.
                            getFleeDistances: function () {
                                var distances = [];

                                for (var index = 0; index < core.combat.getFleeDistanceCount(); index++) {
                                    distances.push(core.combat.getFleeDistance(index));
                                }

                                return distances;
                            },
                            setFleeDistances: function (distances) {
                                var count = core.combat.getFleeDistanceCount();

                                if (!Array.isArray(distances) || distances.length !== count) {
                                    throw new TypeError('An array of ' + count + ' distances is needed, not ' + distances + '.');
                                }

                                for (var index = 0; index < count; index++) {
                                    core.combat.setFleeDistance(index, distances[index]);
                                }
                            }
                        });

    var game = freeze({
                          getFrame: function () {
                              return core.game.getFrame();
                          },
                          // The game seconds of the trigger clock, one every sixteen frames, that the Elapsed Time condition compares.
                          getElapsedTime: function () {
                              return core.game.getElapsedTime();
                          },
                          isReadOnly: function () {
                              return core.game.isReadOnly();
                          },
                          // Shows a text to all players. Shared code only.
                          print: function (text) {
                              core.game.print(String(text));
                          },
                          // Whether the game is paused, by pause() or Pause Game or by the pause of the game menu.
                          isPaused: function () {
                              return core.game.isPaused();
                          },
                          // Pauses and resumes the game as Pause Game and Unpause Game do, at this frame's trigger step: nothing in a multiplayer
                          // session. While paused, onFrame runs at every trigger step with the frame number unchanged. Shared code only.
                          pause: function () {
                              core.game.pause();
                          },
                          resume: function () {
                              core.game.resume();
                          },
                          // Calls a function every given number of frames, first that many frames from now. Shared code only.
                          every: function (frames, callback) {
                              return addTimer(frames, callback, false);
                          },
                          // Calls a function once, the given number of frames from now. Shared code only.
                          after: function (frames, callback) {
                              return addTimer(frames, callback, true);
                          },
                          // The game speed, a GameSpeed.
                          getSpeed: function () {
                              return GameSpeed.get(core.game.getSpeed());
                          },
                          isCheatEnabled: function (cheat) {
                              return (core.game.getCheatFlags() & requireObject(cheat, cheatPrototype, 'A cheat').id) !== 0;
                          },
                          // Turns a cheat on or off and keeps it so: sets or clears its bit of the cheat flags, as an EUD write of the flags does,
                          // also in a network game and where the game options forbid cheats, and writes the bit again at the start of every frame, as
                          // the always-running trigger of an EUD map does. Shared code only.
                          setCheatEnabled: function (cheat, enabled) {
                              var bit = requireObject(cheat, cheatPrototype, 'A cheat').id;
                              var flags = core.game.getCheatFlags();

                              core.game.setCheatFlags((enabled ? flags | bit : flags & ~bit) >>> 0);

                              keptCheatsOn = (enabled ? keptCheatsOn | bit : keptCheatsOn & ~bit) >>> 0;
                              keptCheatsOff = (enabled ? keptCheatsOff & ~bit : keptCheatsOff | bit) >>> 0;
                          },
                          // Creates a sprite of a SpriteType at a position that lives as long as its image scripts run, as the game creates the
                          // sprite of a spell or of dust: {owner: a Player, elevation: 0 to 255}; the sprite, or null when the pools are full. Shared
                          // code only.
                          createEffect: function (spriteType, position, options) {
                              var values = readOptions(options);
                              var owner = values.owner === undefined ? DEFAULT_EFFECT_OWNER : requireObject(values.owner, playerPrototype, 'A player').id;
                              var elevation = values.elevation === undefined ? DEFAULT_EFFECT_ELEVATION : values.elevation;

                              requireObject(position, positionPrototype, 'A position');

                              return sprites.get(core.sprite.createEffect(requireObject(spriteType, spriteTypePrototype, 'A sprite type').id, position.x,
                                                                          position.y, owner, elevation));
                          },
                          // The active bullets, in the order of the active list.
                          getBullets: function () {
                              return core.bullet.getIds().map(function (id) {
                                  return bullets.get(id);
                              });
                          },
                          // Creates a bullet of a WeaponType at a position for a unit, aimed at the unit's order target, as the game creates one: the
                          // bullet, or null when the game creates none. Shared code only.
                          createBullet: function (weaponType, source, position) {
                              var weapon = requireObject(weaponType, weaponTypePrototype, 'A weapon type').id;

                              requireObject(position, positionPrototype, 'A position');

                              return bullets.get(core.bullet.create(weapon, requireUnitId(source), position.x, position.y));
                          },
                          map: gameMap,
                          players: players,
                          units: units,
                          switches: switches,
                          countdown: countdown,
                          leaderboard: leaderboard,
                          settings: settings,
                          vision: vision,
                          combat: combat
                      });

    // Writes the cheat bits game.setCheatEnabled keeps again, at the start of every frame in shared code.
    function applyKeptCheats() {
        if ((keptCheatsOn | keptCheatsOff) === 0) {
            return;
        }

        var flags = core.game.getCheatFlags();
        var kept = ((flags | keptCheatsOn) & ~keptCheatsOff) >>> 0;

        if (kept !== flags) {
            core.game.setCheatFlags(kept);
        }
    }

    [unitPrototype, unitTimersPrototype, playerPrototype, playerUpgradesPrototype, playerTechsPrototype, playerUnitsPrototype,
     playerDeathsPrototype, playerKillsPrototype, playerScorePrototype, playerVisionPrototype, locationPrototype, tilePrototype,
     resourcePrototype, grpPrototype, animPrototype, imagePrototype, timerPrototype, spritePrototype, spriteImagePrototype,
     bulletPrototype].forEach(freeze);

    return {
        exports: {
            game: game
        },
        shared: {
            unitPrototype: unitPrototype,
            playerPrototype: playerPrototype,
            grpPrototype: grpPrototype,
            animPrototype: animPrototype,
            imagePrototype: imagePrototype,
            getPlayer: getPlayer,
            getUnit: getUnit,
            getCapturedUnit: getCapturedUnit,
            toUnits: toUnits,
            requireUnitId: requireUnitId,
            pollDueTimer: pollDueTimer,
            getCapturedBullet: bullets.getCaptured,
            readGraphicsIds: readGraphicsIds,
            applyKeptCheats: applyKeptCheats
        }
    };
});
