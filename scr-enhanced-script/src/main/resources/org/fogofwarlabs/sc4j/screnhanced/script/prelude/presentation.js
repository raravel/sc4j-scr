// The presentation of the upper layer: the globals display (display.console, display.strings, display.messages, display.sound, display.images
// and display.commandCard of command-card.js), ConsoleControl, ConsoleImage, ConsoleAnchor and ConsoleChild, and the console control objects
// they return.
// Shared code changes the presentation on every peer, local code only on the local machine.
(function (core, prelude) {
    'use strict';

    var NONE = prelude.NONE;
    var createObject = prelude.createObject;
    var requireObject = prelude.requireObject;
    var positionPrototype = prelude.positionPrototype;
    var typePrototype = prelude.typePrototype;
    var createConstantTable = prelude.createConstantTable;
    var imageTypePrototype = prelude.imageTypePrototype;
    var resourceTypePrototype = prelude.resourceTypePrototype;
    var ResourceType = prelude.ResourceType;
    var requireUnitId = prelude.requireUnitId;
    var imagePrototype = prelude.imagePrototype;
    var readGraphicsIds = prelude.readGraphicsIds;
    var commandCard = prelude.commandCard;

    var create = Object.create;
    var freeze = Object.freeze;

    // The resource icon frames of the console: minerals, then gas and supply for the Zerg, Terran and Protoss consoles.
    var MINERAL_ICONS = [0];
    var GAS_ICONS = [1, 2, 3];
    var SUPPLY_ICONS = [4, 5, 6];

    // The image type of a unit's images: the given one, else the image type of the unit's main image.
    function readUnitImageType(unit, imageType) {
        if (imageType === undefined) {
            return core.unit.getImageType(requireUnitId(unit));
        }

        return requireObject(imageType, imageTypePrototype, 'An image type').id;
    }

    // The graphics of the images of the game.
    var imageGraphics = freeze({
                                   // Replaces the graphics of every image of an image type with {sd: a GRP, hd: an HD anim, hd2: an HD2 anim}.
                                   replace: function (imageType, images) {
                                       var imageTypeId = requireObject(imageType, imageTypePrototype, 'An image type').id;
                                       var ids = readGraphicsIds(images);

                                       core.graphics.replaceImage(imageTypeId, ids[0], ids[1], ids[2]);
                                   },
                                   // Draws the installed graphics of an image type again.
                                   restore: function (imageType) {
                                       core.graphics.restoreImage(requireObject(imageType, imageTypePrototype, 'An image type').id);
                                   },
                                   // Replaces the graphics of a unit's images of an image type, by default that of its main image now, while the unit
                                   // lives.
                                   replaceUnit: function (unit, images, imageType) {
                                       var id = requireUnitId(unit);
                                       var imageTypeId = readUnitImageType(unit, imageType);
                                       var ids = readGraphicsIds(images);

                                       core.graphics.replaceUnitImage(id, imageTypeId, ids[0], ids[1], ids[2]);
                                   },
                                   // Draws the graphics of the image type again for a unit's images.
                                   restoreUnit: function (unit, imageType) {
                                       core.graphics.restoreUnitImage(requireUnitId(unit), readUnitImageType(unit, imageType));
                                   }
                               });

    var consoleControlPrototype = create(typePrototype);
    var consoleImagePrototype = create(typePrototype);
    var consoleAnchorPrototype = create(typePrototype);
    var ConsoleControl = createConstantTable(consoleControlPrototype,
                                             ['CommandCard', 'Selection', 'Resources', 'Portrait', 'Minimap', 'Menu', 'TextBox'], 7, 'Control ');
    // The console art files of the shown race console: SD frame, HD/HD2 pieces, time and APM plates, portrait overlay.
    var ConsoleImage = createConstantTable(consoleImagePrototype,
                                           ['Console', 'ConsoleLeft', 'ConsoleCenter', 'ConsoleRight', 'ConsoleTime', 'ConsoleApm', 'Conover'], 7,
                                           'Image ');
    // The interface edges the classic screen of a console control's positions stands at.
    var ConsoleAnchor = createConstantTable(consoleAnchorPrototype, ['Left', 'Center', 'Right'], 3, 'Anchor ');
    // The identifiers of the well-known controls of the console dialogs in their definitions, by dialog.
    var consoleChildren = {
        SelectionWireframe: 1,
        SelectionName: -5,
        SelectionHitPoints: -7,
        SelectionEnergy: -8,
        SelectionProgress: 7,
        ResourcesCountdown: -10,
        PortraitPicture: 1,
        MenuButton: 1,
        MinimapArea: 1,
        MinimapTerrain: 2,
        MinimapMessage: 3,
        MinimapDiplomacy: 4,
        TextBoxEdit: 7
    };

    // The numbered children: name prefix, first identifier, count.
    [['CommandCardButton', 1, 9], ['SelectionQueue', 2, 5], ['SelectionGroup', 33, 12], ['ResourcesCounter', 1, 6]].forEach(function (series) {
        for (var index = 0; index < series[2]; index++) {
            consoleChildren[series[0] + (index + 1)] = series[1] + index;
        }
    });

    var ConsoleChild = freeze(consoleChildren);
    var consoleControlObjects = [];

    // The rectangle {x, y, width, height} of the lower layer's [x, y, width, height], null for none.
    function toRectangle(values) {
        return values === null ? null : freeze({x: values[0], y: values[1], width: values[2], height: values[3]});
    }

    // A console control: one of the console dialogs, placed by its top left in the classic 640 by 480 screen.
    var consoleControlObjectPrototype = {
        getControl: function () {
            return this.control;
        },
        setPosition: function (x, y) {
            core.console.setDialogPosition(this.control.id, Math.round(x), Math.round(y));
        },
        setVisible: function (visible) {
            core.console.setDialogVisible(this.control.id, visible === true);
        },
        isVisible: function () {
            return core.console.isDialogVisible(this.control.id);
        },
        // The controls of the dialog by their identifier in the dialog's definition: shown or hidden, and placed by their top left in the
        // classic screen; a setting wins over EUD memory until the control is restored.
        setChildVisible: function (id, visible) {
            core.console.setControlVisible(this.control.id, id, visible === true);
        },
        setChildPosition: function (id, x, y) {
            core.console.setControlPosition(this.control.id, id, Math.round(x), Math.round(y));
        },
        restoreChild: function (id) {
            core.console.restoreControl(this.control.id, id);
        },
        // The interface edge the classic screen of the dialog's positions, and those of its controls, stands at.
        setAnchor: function (anchor) {
            core.console.setDialogAnchor(this.control.id, requireObject(anchor, consoleAnchorPrototype, 'A console anchor').id);
        },
        // The rectangle the client last drew in interface units, null while hidden or not drawn. Local code only.
        getRectangle: function () {
            return toRectangle(core.console.getDialogRectangle(this.control.id));
        },
        getChildRectangle: function (id) {
            return toRectangle(core.console.getControlRectangle(this.control.id, id));
        }
    };

    // The resource icon frames of a resource type.
    function readResourceIcons(resourceType) {
        var id = requireObject(resourceType, resourceTypePrototype, 'A resource type').id;

        if (id === ResourceType.Minerals.id) {
            return MINERAL_ICONS;
        }

        if (id === ResourceType.Gas.id) {
            return GAS_ICONS;
        }

        if (id === ResourceType.Supply.id) {
            return SUPPLY_ICONS;
        }

        throw new TypeError('The resource type ' + resourceType + ' has no console icon.');
    }

    // The console. A setting wins over what EUD memory gives for the same item; EUD memory still drives the items the script never set.
    var gameConsole = freeze({
                                 setVisible: function (visible) {
                                     core.console.setVisible(visible === true);
                                 },
                                 isVisible: function () {
                                     return core.console.isVisible();
                                 },
                                 getControl: function (control) {
                                     var id = requireObject(control, consoleControlPrototype, 'A console control').id;
                                     var object = consoleControlObjects[id];

                                     if (object === undefined) {
                                         object = freeze(createObject(consoleControlObjectPrototype, {control: control}));
                                         consoleControlObjects[id] = object;
                                     }

                                     return object;
                                 },
                                 // Draws an image instead of a console image, null for the installed one.
                                 replaceImage: function (consoleImage, image) {
                                     var id = requireObject(consoleImage, consoleImagePrototype, 'A console image').id;

                                     core.console.replaceImage(id, image === null || image === undefined ? NONE : requireObject(image, imagePrototype, 'An image').id);
                                 },
                                 // The side of the drawn minimap area in interface units, 128 installed.
                                 setMinimapSize: function (size) {
                                     core.console.setMinimapSize(size);
                                 },
                                 getMinimapSize: function () {
                                     return core.console.getMinimapSize();
                                 },
                                 // The minimap area as the client last drew it, null while hidden or not drawn. Local code only.
                                 getMinimapRectangle: function () {
                                     return toRectangle(core.console.getMinimapRectangle());
                                 },
                                 // Draws an image for the icon of a resource type, null for the icon of EUD memory or the installed one.
                                 setResourceIcon: function (resourceType, image) {
                                     var icons = readResourceIcons(resourceType);
                                     var imageId = image === null || image === undefined ? NONE : requireObject(image, imagePrototype, 'An image').id;

                                     for (var index = 0; index < icons.length; index++) {
                                         core.console.setResourceIcon(icons[index], imageId);
                                     }
                                 }
                             });

    // The stat_txt strings by their one-based ids; a text wins over the game's table, null shows the game's string again.
    var strings = freeze({
                             setStatText: function (id, text) {
                                 core.strings.setStatText(id, text === null || text === undefined ? null : String(text));
                             }
                         });

    // The message lines on the screen: lines 0 to 10 take the messages in turn from the cursor, line 12 the status texts. A written line keeps
    // the expiry time of the message it held, as an EUD write does.
    var messages = freeze({
                              // Empties the message lines on the screen.
                              clear: function () {
                                  core.messages.clear();
                              },
                              getLine: function (line) {
                                  return core.messages.getLine(line);
                              },
                              setLine: function (line, text) {
                                  core.messages.setLine(line, String(text));
                              },
                              // The line the next message starts at.
                              getCursor: function () {
                                  return core.messages.getNextLine();
                              },
                              setCursor: function (line) {
                                  core.messages.setNextLine(line);
                              }
                          });

    // The game sounds.
    var sound = freeze({
                           // Plays a sound of sfxdata.dat by its id at a position, louder the nearer it is to the screen.
                           playAt: function (soundId, position) {
                               requireObject(position, positionPrototype, 'A position');
                               core.sound.playAt(soundId, position.x, position.y);
                           }
                       });

    var display = freeze({
                             console: gameConsole,
                             strings: strings,
                             messages: messages,
                             sound: sound,
                             images: imageGraphics,
                             commandCard: commandCard
                         });

    [consoleControlPrototype, consoleImagePrototype, consoleAnchorPrototype, consoleControlObjectPrototype].forEach(freeze);

    return {
        exports: {
            ConsoleControl: ConsoleControl,
            ConsoleImage: ConsoleImage,
            ConsoleAnchor: ConsoleAnchor,
            ConsoleChild: ConsoleChild,
            display: display
        },
        shared: {}
    };
});
