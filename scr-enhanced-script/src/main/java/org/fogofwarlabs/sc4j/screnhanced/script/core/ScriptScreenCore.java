package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrDrawList;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.presentation.LocalDisplay;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ClassicScreenGraphics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

import java.io.IOException;

/**
 * Defines {@code core.screen}, {@code core.world} and {@code core.minimap}: what a map script draws over the game in the local hook
 * {@code onRender}, and the classic screen pixels.
 * <p>
 * The three namespaces draw into one draw list ({@link ScrDrawList}) in their spaces: {@code core.screen} in interface units over the console,
 * {@code core.world} in map pixels above the units, {@code core.minimap} in map pixels on the minimap. Each has
 * {@code fillRectangle(x, y, width, height, argb)}, {@code drawRectangle(x, y, width, height, argb, thickness)},
 * {@code fillCircle(x, y, radius, argb)}, {@code drawCircle(x, y, radius, argb, thickness)},
 * {@code drawLine(x1, y1, x2, y2, argb, thickness)}, {@code drawPoint(x, y, argb)}, {@code drawText(x, y, text, fontId, argb, alignId)},
 * {@code drawImage(imageId, x, y, width, height, opacity)}, {@code drawImageRegion(imageId, sourceX, sourceY, sourceWidth, sourceHeight, x, y,
 * width, height, opacity)} (a rectangle of the image in its pixels, inside the image, as a frame of a sprite sheet) and
 * {@code measureText(text, fontId)} (the width of the widest line in interface
 * units in the client's fonts, {@code 0} before the client drew a frame). Coordinates are numbers with fractions; colours are packed
 * {@code 0xAARRGGBB}; the fonts are the four of {@link ScrDrawList.Font} in order, the alignments those of {@link ScrDrawList.Align}. The drawing
 * functions throw a {@code TypeError} outside {@code onRender}.
 * <p>
 * {@code core.screen} also has the local machine's screen, readable only in local code: {@code getWidth()} and {@code getHeight()} (interface
 * units), {@code getScale()} (real pixels per interface unit) and {@code getGraphics()} ({@code 0} classic, {@code 1} HD, {@code 2} HD2). The
 * classic screen pixels, the game screen bitmap that EUD maps write ({@link ClassicScreenGraphics}), are written from any code:
 * {@code setPixel(x, y, paletteIndex)} and {@code fillPixels(x, y, width, height, paletteIndex)} show palette pixels of the {@code 640} by
 * {@code 480} screen from then on. {@code getPaletteColor(index)} returns the colour of a palette index of the map's tileset palette.
 * <p>
 * A render call builds a new list; the list of the last completed call is the one drawn ({@link #findDrawList()}), until the next call ends. A
 * call that ends dropped ({@link #endRender(boolean)}) draws nothing.
 */
final class ScriptScreenCore {
    private static final int FONT_COUNT = ScrDrawList.Font.values().length;
    private static final int ALIGN_COUNT = ScrDrawList.Align.values().length;
    private static final @NotNull ScrDrawList.Font @NotNull [] FONTS = ScrDrawList.Font.values();
    private static final @NotNull ScrDrawList.Align @NotNull [] ALIGNS = ScrDrawList.Align.values();
    /**
     * The values of {@code getGraphics()}: classic, HD and HD2.
     */
    private static final int CLASSIC_GRAPHICS = 0;
    private static final int HD_GRAPHICS = 1;
    private static final int HD2_GRAPHICS = 2;
    private static final int PALETTE_SIZE = 256;
    private static final int PALETTE_ENTRY_SIZE = 4;
    private static final int OPAQUE = 0xFF000000;
    private static final int BYTE_MASK = 0xFF;
    private static final int RED_SHIFT = 16;
    private static final int GREEN_SHIFT = 8;
    private static final int GREEN_OFFSET = 1;
    private static final int BLUE_OFFSET = 2;

    /**
     * Reads a packed {@code 0xAARRGGBB} colour argument.
     *
     * @throws EcmaError if the argument is not an integer from {@code 0} to {@code 0xFFFFFFFF}
     */
    private static int readColor(@NotNull Object @NotNull [] arguments, int index) {
        long color = ScriptCoreUtils.readLong(arguments, index);

        if (color < 0) {
            throw ScriptRuntime.rangeError(Exceptions.format("The colour {0} is not from 0 to 0xFFFFFFFF.", color));
        }

        return (int) color;
    }

    /**
     * Reads a size or line thickness argument.
     *
     * @throws EcmaError if the argument is not a finite number or is negative
     */
    private static float readSize(@NotNull Object @NotNull [] arguments, int index) {
        float size = ScriptCoreUtils.readNumber(arguments, index);

        if (size < 0) {
            throw ScriptRuntime.rangeError(Exceptions.format("Argument {0} is negative.", index + 1));
        }

        return size;
    }

    private static ScrDrawList.@NotNull Font readFont(@NotNull Object @NotNull [] arguments, int index) {
        return FONTS[ScriptCoreUtils.requireId("font", ScriptCoreUtils.readInteger(arguments, index), FONT_COUNT)];
    }


    private final @NotNull ScriptGame scriptGame;
    private final @NotNull ScriptGraphicsCore graphicsCore;

    private @NotNull ScrDrawList building;
    private @NotNull ScrDrawList shown;
    private boolean rendering;

    private int @Nullable [] paletteColors;

    /**
     * Creates a {@link ScriptScreenCore} instance with empty draw lists, not rendering.
     *
     * @param scriptGame   the game binding
     * @param graphicsCore the resource functions, which read image handles
     */
    ScriptScreenCore(@NotNull ScriptGame scriptGame, @NotNull ScriptGraphicsCore graphicsCore) {
        this.scriptGame   = scriptGame;
        this.graphicsCore = graphicsCore;

        this.building  = new ScrDrawList();
        this.shown     = new ScrDrawList();
        this.rendering = false;

        this.paletteColors = null;
    }

    /**
     * @return the list of the last completed render call, or {@code null} if it drew nothing
     */
    @Nullable ScrDrawList findDrawList() {
        return this.shown.isEmpty() ? null : this.shown;
    }

    /**
     * Starts a render call: the drawing functions add to a new list from now on.
     */
    void beginRender() {
        this.building.clear();

        this.rendering = true;
    }

    /**
     * Ends a render call: the drawing functions throw again, and the call's list becomes the drawn one, or is dropped so that nothing is drawn.
     *
     * @param completed {@code true} to draw the call's list, {@code false} to drop it
     */
    void endRender(boolean completed) {
        this.rendering = false;

        if (completed) {
            ScrDrawList list = this.shown;

            this.shown    = this.building;
            this.building = list;
        } else {
            this.shown.clear();
        }

        this.building.clear();
    }

    /**
     * Checks that a render call runs.
     *
     * @throws EcmaError if no render call runs
     */
    private void checkRendering() {
        if (!this.rendering) {
            throw ScriptRuntime.typeError("Drawing works only in onRender.");
        }
    }

    /**
     * Defines the drawing functions of a namespace in a space.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     * @param space     the space of the namespace
     */
    void defineDrawing(@NotNull Scriptable namespace, @NotNull Scriptable global, ScrDrawList.@NotNull Space space) {
        ScriptCoreUtils.defineFunction(global, namespace, "fillRectangle", 5, (context, scope, thisObject, arguments) -> {
            this.checkRendering();
            this.building.fillRectangle(space, ScriptCoreUtils.readNumber(arguments, 0), ScriptCoreUtils.readNumber(arguments, 1),
                                        readSize(arguments, 2), readSize(arguments, 3), readColor(arguments, 4));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "drawRectangle", 6, (context, scope, thisObject, arguments) -> {
            this.checkRendering();
            this.building.drawRectangle(space, ScriptCoreUtils.readNumber(arguments, 0), ScriptCoreUtils.readNumber(arguments, 1),
                                        readSize(arguments, 2), readSize(arguments, 3), readSize(arguments, 5), readColor(arguments, 4));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "fillCircle", 4, (context, scope, thisObject, arguments) -> {
            this.checkRendering();
            this.building.fillCircle(space, ScriptCoreUtils.readNumber(arguments, 0), ScriptCoreUtils.readNumber(arguments, 1),
                                     readSize(arguments, 2), readColor(arguments, 3));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "drawCircle", 5, (context, scope, thisObject, arguments) -> {
            this.checkRendering();
            this.building.drawCircle(space, ScriptCoreUtils.readNumber(arguments, 0), ScriptCoreUtils.readNumber(arguments, 1),
                                     readSize(arguments, 2), readSize(arguments, 4), readColor(arguments, 3));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "drawLine", 6, (context, scope, thisObject, arguments) -> {
            this.checkRendering();
            this.building.drawLine(space, ScriptCoreUtils.readNumber(arguments, 0), ScriptCoreUtils.readNumber(arguments, 1),
                                   ScriptCoreUtils.readNumber(arguments, 2), ScriptCoreUtils.readNumber(arguments, 3),
                                   readSize(arguments, 5), readColor(arguments, 4));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "drawPoint", 3, (context, scope, thisObject, arguments) -> {
            this.checkRendering();
            this.building.drawPoint(space, ScriptCoreUtils.readNumber(arguments, 0), ScriptCoreUtils.readNumber(arguments, 1),
                                    readColor(arguments, 2));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "drawText", 6, (context, scope, thisObject, arguments) -> {
            this.checkRendering();

            int align = ScriptCoreUtils.requireId("alignment", ScriptCoreUtils.readInteger(arguments, 5), ALIGN_COUNT);

            this.building.drawText(space, ScriptCoreUtils.readString(arguments, 2), ScriptCoreUtils.readNumber(arguments, 0),
                                   ScriptCoreUtils.readNumber(arguments, 1), readFont(arguments, 3), ALIGNS[align], readColor(arguments, 4));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "drawImage", 6, (context, scope, thisObject, arguments) -> {
            this.checkRendering();

            float opacity = Math.clamp(ScriptCoreUtils.readNumber(arguments, 5), 0, 1);

            this.building.drawPicture(space, this.graphicsCore.readPicture(arguments, 0), ScriptCoreUtils.readNumber(arguments, 1),
                                      ScriptCoreUtils.readNumber(arguments, 2), readSize(arguments, 3), readSize(arguments, 4), opacity);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "drawImageRegion", 10, (context, scope, thisObject, arguments) -> {
            this.checkRendering();

            Picture picture = this.graphicsCore.readPicture(arguments, 0);
            float sourceX = ScriptCoreUtils.readNumber(arguments, 1);
            float sourceY = ScriptCoreUtils.readNumber(arguments, 2);
            float sourceWidth = readSize(arguments, 3);
            float sourceHeight = readSize(arguments, 4);
            float opacity = Math.clamp(ScriptCoreUtils.readNumber(arguments, 9), 0, 1);

            if (sourceX < 0 || sourceY < 0 || sourceX + sourceWidth > picture.getWidth() || sourceY + sourceHeight > picture.getHeight()) {
                String message = Exceptions.format("The source rectangle {0}, {1}, {2} by {3} is not inside the {4} by {5} image.", sourceX, sourceY,
                                                   sourceWidth, sourceHeight, picture.getWidth(), picture.getHeight());

                throw ScriptRuntime.rangeError(message);
            }

            this.building.drawPicture(space, picture, sourceX, sourceY, sourceWidth, sourceHeight, ScriptCoreUtils.readNumber(arguments, 5),
                                      ScriptCoreUtils.readNumber(arguments, 6), readSize(arguments, 7), readSize(arguments, 8), opacity);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "measureText", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkLocal();

            String text = ScriptCoreUtils.readString(arguments, 0);

            return (double) this.getLocalDisplay().measureText(text, readFont(arguments, 1));
        });
    }

    /**
     * Defines the functions of {@code core.screen} beyond the drawing functions: the local screen, the classic screen pixels and the palette.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void defineScreen(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getWidth", 0, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkLocal();

            return (double) this.getLocalDisplay().getInterfaceWidth();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getHeight", 0, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkLocal();

            return (double) this.getLocalDisplay().getInterfaceHeight();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getScale", 0, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkLocal();

            return (double) this.getLocalDisplay().getScale();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getGraphics", 0, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkLocal();

            return (double) switch (this.getLocalDisplay().getGraphics()) {
                case SD -> CLASSIC_GRAPHICS;
                case HD -> HD_GRAPHICS;
                case HD2 -> HD2_GRAPHICS;
            };
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setPixel", 3, (context, scope, thisObject, arguments) -> {
            this.fillPixels(ScriptCoreUtils.readInteger(arguments, 0), ScriptCoreUtils.readInteger(arguments, 1), 1, 1,
                            ScriptCoreUtils.requireId("palette index", ScriptCoreUtils.readInteger(arguments, 2), PALETTE_SIZE));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "fillPixels", 5, (context, scope, thisObject, arguments) -> {
            this.fillPixels(ScriptCoreUtils.readInteger(arguments, 0), ScriptCoreUtils.readInteger(arguments, 1),
                            ScriptCoreUtils.readInteger(arguments, 2), ScriptCoreUtils.readInteger(arguments, 3),
                            ScriptCoreUtils.requireId("palette index", ScriptCoreUtils.readInteger(arguments, 4), PALETTE_SIZE));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getPaletteColor", 1, (context, scope, thisObject, arguments) -> {
            int index = ScriptCoreUtils.requireId("palette index", ScriptCoreUtils.readInteger(arguments, 0), PALETTE_SIZE);

            return (double) Integer.toUnsignedLong(this.getPaletteColors()[index]);
        });
    }

    private @NotNull LocalDisplay getLocalDisplay() {
        return this.scriptGame.getGame().getInterfaceLink().getLocalDisplay();
    }

    /**
     * Shows a rectangle of the classic screen pixels in the game's classic screen graphics.
     *
     * @throws EcmaError if the game has no classic screen graphics
     */
    private void fillPixels(int x, int y, int width, int height, int index) {
        for (ScrGamePlugin plugin : this.scriptGame.getGame().getPlugins()) {
            if (plugin instanceof ClassicScreenGraphics screen) {
                screen.fillPixels(x, y, width, height, index);

                return;
            }
        }

        throw ScriptRuntime.typeError("The game has no classic screen.");
    }

    /**
     * Reads the colours of the map's tileset palette once.
     *
     * @return the colours, packed {@code 0xAARRGGBB}, opaque
     * @throws EcmaError if the game has no game data or the palette cannot be read
     */
    private int @NotNull [] getPaletteColors() {
        int[] colors = this.paletteColors;

        if (colors != null) {
            return colors;
        }

        GameData gameData = this.scriptGame.getGame().getGameData();

        if (gameData == null) {
            throw ScriptRuntime.typeError("The game has no palette.");
        }

        byte[] palette;

        try {
            palette = gameData.loadTerrainPalette(this.scriptGame.getGame().getMap().getTileset());
        } catch (IOException exception) {
            throw ScriptRuntime.typeError(Exceptions.format("The palette cannot be read: {0}", exception.getMessage()));
        }

        colors = new int[PALETTE_SIZE];

        for (int index = 0; index < PALETTE_SIZE; index++) {
            int offset = index * PALETTE_ENTRY_SIZE;

            colors[index] = OPAQUE | (palette[offset] & BYTE_MASK) << RED_SHIFT | (palette[offset + GREEN_OFFSET] & BYTE_MASK) << GREEN_SHIFT |
                            palette[offset + BLUE_OFFSET] & BYTE_MASK;
        }

        this.paletteColors = colors;

        return colors;
    }
}
