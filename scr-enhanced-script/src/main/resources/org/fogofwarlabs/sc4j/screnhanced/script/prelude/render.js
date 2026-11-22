// The drawing of the upper layer: the globals Font, Align and Graphics, and the screen object that onRender receives, over core.screen,
// core.world and core.minimap. Colors are Color numbers, fonts and alignments their constants; the options objects become the positional
// arguments of core.
(function (core, prelude) {
    'use strict';

    var requireObject = prelude.requireObject;
    var readOptions = prelude.readOptions;
    var typePrototype = prelude.typePrototype;
    var createConstantTable = prelude.createConstantTable;
    var Color = prelude.Color;
    var imagePrototype = prelude.imagePrototype;

    var create = Object.create;
    var freeze = Object.freeze;

    var DEFAULT_THICKNESS = 1;
    var DEFAULT_OPACITY = 1;

    var fontPrototype = create(typePrototype);
    var alignPrototype = create(typePrototype);
    var graphicsPrototype = create(typePrototype);
    // The four fonts of the game interface: font8, font10, font16 and font16x.
    var Font = createConstantTable(fontPrototype, ['Small', 'Medium', 'Large', 'Huge'], 4, 'Font ');
    var Align = createConstantTable(alignPrototype, ['Left', 'Center', 'Right'], 3, 'Align ');
    var Graphics = createConstantTable(graphicsPrototype, ['SD', 'HD', 'HD2'], 3, 'Graphics ');

    // The option readers: white, a thickness of 1 and the Medium font when left out.
    function readColorOption(color) {
        return color === undefined ? Color.White : color;
    }

    function readThicknessOption(values) {
        return values.thickness === undefined ? DEFAULT_THICKNESS : values.thickness;
    }

    function readFontOption(font) {
        return font === undefined ? Font.Medium.id : requireObject(font, fontPrototype, 'A font').id;
    }

    // The drawing functions of one space over its core namespace: they work only in onRender.
    function createDrawing(namespace) {
        return {
            fillRectangle: function (x, y, width, height, color) {
                namespace.fillRectangle(x, y, width, height, readColorOption(color));
            },
            // Options {color, thickness}; the outline lies inside the rectangle.
            drawRectangle: function (x, y, width, height, options) {
                var values = readOptions(options);

                namespace.drawRectangle(x, y, width, height, readColorOption(values.color), readThicknessOption(values));
            },
            fillCircle: function (x, y, radius, color) {
                namespace.fillCircle(x, y, radius, readColorOption(color));
            },
            // Options {color, thickness}; the outline is centered on the circle.
            drawCircle: function (x, y, radius, options) {
                var values = readOptions(options);

                namespace.drawCircle(x, y, radius, readColorOption(values.color), readThicknessOption(values));
            },
            // Options {color, thickness}.
            drawLine: function (x1, y1, x2, y2, options) {
                var values = readOptions(options);

                namespace.drawLine(x1, y1, x2, y2, readColorOption(values.color), readThicknessOption(values));
            },
            drawPoint: function (x, y, color) {
                namespace.drawPoint(x, y, readColorOption(color));
            },
            // Options {font, color, align}: the lines, broken at \n, from the top at y, each aligned on x; color codes in the text work.
            drawText: function (text, x, y, options) {
                var values = readOptions(options);
                var align = values.align === undefined ? Align.Left.id : requireObject(values.align, alignPrototype, 'An alignment').id;

                namespace.drawText(x, y, String(text), readFontOption(values.font), readColorOption(values.color), align);
            },
            // Options {width, height, opacity, source}; source {x, y, width, height} draws a rectangle of the image in its pixels. The size is the
            // pixel size of the source rectangle, else of the image, in the space's units by default.
            drawImage: function (image, x, y, options) {
                var values = readOptions(options);
                var id = requireObject(image, imagePrototype, 'An image').id;
                var opacity = values.opacity === undefined ? DEFAULT_OPACITY : values.opacity;

                if (values.source === undefined || values.source === null) {
                    namespace.drawImage(id, x, y, values.width === undefined ? image.getWidth() : values.width,
                                        values.height === undefined ? image.getHeight() : values.height, opacity);

                    return;
                }

                var source = readOptions(values.source);

                namespace.drawImageRegion(id, source.x, source.y, source.width, source.height, x, y,
                                          values.width === undefined ? source.width : values.width,
                                          values.height === undefined ? source.height : values.height, opacity);
            },
            // The width of the widest line in interface units, in the client's fonts. Local code only.
            measureText: function (text, font) {
                return namespace.measureText(String(text), readFontOption(font));
            }
        };
    }

    // The screen object of onRender: the game screen in interface units, its world (map pixels, following the camera) and its minimap.
    var screen = createDrawing(core.screen);

    // The size of the local game screen in interface units, the real pixels per interface unit and the graphics mode. Local code only.
    screen.getWidth = function () {
        return core.screen.getWidth();
    };
    screen.getHeight = function () {
        return core.screen.getHeight();
    };
    screen.getScale = function () {
        return core.screen.getScale();
    };
    screen.getGraphics = function () {
        return Graphics.get(core.screen.getGraphics());
    };
    // Rounds an interface coordinate to the real pixel grid.
    screen.snap = function (value) {
        var scale = core.screen.getScale();

        return Math.round(value * scale) / scale;
    };
    screen.world = freeze(createDrawing(core.world));
    screen.minimap = freeze(createDrawing(core.minimap));
    freeze(screen);

    [fontPrototype, alignPrototype, graphicsPrototype].forEach(freeze);

    return {
        exports: {
            Font: Font,
            Align: Align,
            Graphics: Graphics
        },
        shared: {
            screen: screen
        }
    };
});
