package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.scr.content.ScrImages;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.ClassicDatRegion;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.screnhanced.graphics.*;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptResources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Defines {@code core.resources} and {@code core.graphics}: the GRPs, anims and images of the map's resources, and the graphics they replace.
 * <p>
 * {@code core.resources}: {@code has(name)}, {@code getGrp(name)}, {@code getAnim(name)} and {@code getImage(name)}, and
 * {@code getImageWidth(id)} and {@code getImageHeight(id)} (the pixel size of an image handle). A resource name is relative to the resource folder
 * with {@code /} separators. {@code getGrp}, {@code getAnim} and {@code getImage} return the id of a resource handle, the same id for the same
 * name, after checking the resource once: a GRP header with a readable frame table whose frames decode, a single-entry anim of asset scale
 * {@code 4} (HD) or {@code 2} (HD2) with a canvas and a {@code diffuse} layer, or a PNG or DDS picture that decodes ({@link PictureFileUtils}).
 * A missing or invalid resource throws an {@code Error}. {@code readText(name)} returns any resource as text: its bytes decoded as UTF-8, a
 * leading byte order mark left out; bytes that are no UTF-8 throw an {@code Error}. The resources are read from the map's script trailer,
 * inflated and checked, when they are first asked for; an image is decoded once and drawn from the same picture afterwards, a text decoded once.
 * <p>
 * {@code core.graphics}: {@code replaceImage(imageType, grp, hdAnim, hd2Anim)} replaces the graphics of every image of an image type,
 * {@code replaceUnitImage(unit, imageType, grp, hdAnim, hd2Anim)} those of one live unit's images of the type, while the unit lives;
 * {@code restoreImage(imageType)} and {@code restoreUnitImage(unit, imageType)} take a replacement back. A handle argument is {@code -1} for
 * none; at least one is given, the HD anim has asset scale {@code 4} and the HD2 anim {@code 2}. The replacements change only what is drawn
 * ({@link ImageReplacements}): the classic graphics draw the GRP, else the installed GRP; HD draws the HD anim, else the HD2 anim; HD2 draws the
 * HD2 anim, else the HD anim at half size; graphics without an anim draw the GRP in HD too. A unit's replacement wins over that of the image
 * type, and a replacement wins over the GRP and anim pointers that EUD writes give the image.
 * <p>
 * One image of {@code core.image}, while its slot holds it: {@code replaceImageGraphics(image, grp, hdAnim, hd2Anim)} and
 * {@code restoreImageGraphics(image)} (its graphics win over those of its unit and image type); {@code setImageFrame(image, frame)} (a drawn
 * frame from {@code 0} to {@value #MAXIMUM_FRAME}) and {@code setImageDrawFunction(image, drawFunction)} (from {@code 0} to
 * {@code 17}) draw that frame or draw function until the image's own drawn frame or draw function changes, as an EUD write of the image's
 * entry does; {@code -1} gives back the image's own. {@code holdImageFrame(image, frame)} draws the frame while the image's iscript steps its
 * own frame, until {@code -1} releases it, another frame is set or the image is gone. A frame or draw function a script set wins over an EUD
 * write. The {@code core.graphics}
 * functions change no game state and work anywhere: shared code replaces on every peer, local code on the local machine only.
 */
final class ScriptGraphicsCore {
    private static final @NotNull String ERROR_NAME = "Error";
    /**
     * The largest drawn frame of an image, a WORD.
     */
    private static final int MAXIMUM_FRAME = 0xFFFF;
    /**
     * The byte order mark a text resource may start with, which {@code readText} leaves out.
     */
    private static final char BYTE_ORDER_MARK = '﻿';

    /**
     * Reads one resource.
     */
    @FunctionalInterface
    private interface ResourceReader {
        @NotNull Object read() throws IOException;
    }


    /**
     * Reads an image type argument.
     *
     * @throws EcmaError if the argument is not an integer or not an {@code images.dat} entry
     */
    private static int readImageType(@NotNull Object @NotNull [] arguments, int index) {
        return ScriptCoreUtils.requireId("image type", ScriptCoreUtils.readInteger(arguments, index), ScrImages.COUNT);
    }

    /**
     * Reads a value from {@code 0} to a maximum, or {@code -1} for none.
     *
     * @throws EcmaError if the argument is not an integer or neither {@code -1} nor from {@code 0} to the maximum
     */
    private static int readOptionalValue(@NotNull Object @NotNull [] arguments, int index, @NotNull String kind, int maximum) {
        int value = ScriptCoreUtils.readInteger(arguments, index);

        return value == ScriptCoreUtils.NO_ID ? ScriptCoreUtils.NO_ID : ScriptCoreUtils.requireId(kind, value, maximum + 1);
    }


    private final @NotNull ScriptGame scriptGame;
    private final @NotNull ScriptUnitCore unitCore;
    private final @NotNull ScriptSpriteCore spriteCore;
    private final @NotNull ScriptResources resources;

    /**
     * The resource handles by id.
     */
    private final @NotNull List<Object> handles;
    private final @NotNull Map<String, Integer> grpIds;
    private final @NotNull Map<String, Integer> animIds;
    private final @NotNull Map<String, Integer> imageIds;
    /**
     * The decoded text resources by name.
     */
    private final @NotNull Map<String, String> texts;

    /**
     * Creates a {@link ScriptGraphicsCore} instance without resource handles.
     *
     * @param scriptGame the game binding
     * @param unitCore   the unit functions, which read unit arguments
     * @param spriteCore the sprite and image functions, which read image arguments
     * @param resources  the resources of the map
     */
    ScriptGraphicsCore(@NotNull ScriptGame scriptGame, @NotNull ScriptUnitCore unitCore, @NotNull ScriptSpriteCore spriteCore,
                       @NotNull ScriptResources resources) {
        this.scriptGame = scriptGame;
        this.unitCore   = unitCore;
        this.spriteCore = spriteCore;
        this.resources  = resources;

        this.handles  = new ArrayList<>();
        this.grpIds   = new HashMap<>();
        this.animIds  = new HashMap<>();
        this.imageIds = new HashMap<>();
        this.texts    = new HashMap<>();
    }

    /**
     * Defines the functions of {@code core.resources}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void defineResources(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "has", 1, (context, scope, thisObject, arguments) -> {
            String name = ScriptCoreUtils.readString(arguments, 0);

            return this.resources.findEntry(name) != null;
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getGrp", 1, (context, scope, thisObject, arguments) -> {
            String name = ScriptCoreUtils.readString(arguments, 0);

            return (double) this.findHandle(this.grpIds, name, "GRP", () -> this.readGrp(name));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getAnim", 1, (context, scope, thisObject, arguments) -> {
            String name = ScriptCoreUtils.readString(arguments, 0);

            return (double) this.findHandle(this.animIds, name, "anim", () -> this.readAnim(name));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getImage", 1, (context, scope, thisObject, arguments) -> {
            String name = ScriptCoreUtils.readString(arguments, 0);

            return (double) this.findHandle(this.imageIds, name, "image", () -> PictureFileUtils.read(this.resources.read(this.requireEntry(name)),
                                                                                                      name));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "readText", 1, (context, scope, thisObject, arguments) -> {
            String name = ScriptCoreUtils.readString(arguments, 0);
            String text = this.texts.get(name);

            if (text != null) {
                return text;
            }

            try {
                byte[] content = this.resources.read(this.requireEntry(name));
                CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                                               .onUnmappableCharacter(CodingErrorAction.REPORT);

                text = decoder.decode(ByteBuffer.wrap(content)).toString();
            } catch (CharacterCodingException exception) {
                throw ScriptRuntime.constructError(ERROR_NAME, Exceptions.format("The resource {0} is no UTF-8 text.", name));
            } catch (IOException exception) {
                String message = Exceptions.format("The resource {0} cannot be read: {1}", name, exception.getMessage());

                throw ScriptRuntime.constructError(ERROR_NAME, message);
            }

            if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
                text = text.substring(1);
            }

            this.texts.put(name, text);

            return text;
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getImageWidth", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readPicture(arguments, 0).getWidth());
        ScriptCoreUtils.defineFunction(global, namespace, "getImageHeight", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readPicture(arguments, 0).getHeight());
    }

    /**
     * Reads an image handle argument ({@code core.resources.getImage}).
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the picture of the image
     * @throws EcmaError if the argument is not an integer or names no image handle
     */
    @NotNull Picture readPicture(@NotNull Object @NotNull [] arguments, int index) {
        Picture picture = this.readOptionalPicture(arguments, index);

        if (picture == null) {
            throw ScriptRuntime.rangeError("An image handle is needed, not -1.");
        }

        return picture;
    }

    /**
     * Reads an image handle argument ({@code core.resources.getImage}) or {@code -1} for none.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the picture of the image, or {@code null} for {@code -1}
     * @throws EcmaError if the argument is not an integer or names no image handle
     */
    @Nullable Picture readOptionalPicture(@NotNull Object @NotNull [] arguments, int index) {
        return this.readHandle(arguments, index, Picture.class, "image");
    }

    /**
     * Defines the functions of {@code core.graphics}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void defineGraphics(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "replaceImage", 4, (context, scope, thisObject, arguments) -> {
            int imageType = readImageType(arguments, 0);
            ImageGraphics graphics = this.readGraphics(arguments, 1);

            this.getReplacements().replaceImage(imageType, graphics);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "restoreImage", 1, (context, scope, thisObject, arguments) -> {
            this.getReplacements().restoreImage(readImageType(arguments, 0));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "replaceUnitImage", 5, (context, scope, thisObject, arguments) -> {
            Unit unit = this.unitCore.readUnit(arguments, 0);
            int imageType = readImageType(arguments, 1);
            ImageGraphics graphics = this.readGraphics(arguments, 2);

            this.getReplacements().replaceUnitImage(unit, imageType, graphics);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "restoreUnitImage", 2, (context, scope, thisObject, arguments) -> {
            Unit unit = this.unitCore.readUnit(arguments, 0);

            this.getReplacements().restoreUnitImage(unit, readImageType(arguments, 1));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "replaceImageGraphics", 4, (context, scope, thisObject, arguments) -> {
            SpriteImage image = this.spriteCore.readImage(arguments, 0);
            ImageGraphics graphics = this.readGraphics(arguments, 1);

            this.getReplacements().replaceImageGraphics(image, graphics);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "restoreImageGraphics", 1, (context, scope, thisObject, arguments) -> {
            this.getReplacements().restoreImageGraphics(this.spriteCore.readImage(arguments, 0));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setImageFrame", 2, (context, scope, thisObject, arguments) -> {
            SpriteImage image = this.spriteCore.readImage(arguments, 0);
            int frame = readOptionalValue(arguments, 1, "frame", MAXIMUM_FRAME);

            this.getReplacements().setImageFrame(image, frame);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "holdImageFrame", 2, (context, scope, thisObject, arguments) -> {
            SpriteImage image = this.spriteCore.readImage(arguments, 0);
            int frame = readOptionalValue(arguments, 1, "frame", MAXIMUM_FRAME);

            this.getReplacements().holdImageFrame(image, frame);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setImageDrawFunction", 2, (context, scope, thisObject, arguments) -> {
            SpriteImage image = this.spriteCore.readImage(arguments, 0);
            int drawFunction = readOptionalValue(arguments, 1, "draw function", ClassicDatRegion.DRAW_FUNCTION_LIMIT - 1);

            this.getReplacements().setImageDrawFunction(image, drawFunction);

            return Context.getUndefinedValue();
        });
    }

    private @NotNull ImageReplacements getReplacements() {
        return this.scriptGame.getImageReplacements();
    }


    /**
     * Reads the three handle arguments of graphics: a GRP, an HD anim and an HD2 anim, each {@code -1} for none.
     *
     * @throws EcmaError if an argument is not an integer or names no handle of its kind, all three are {@code -1}, the HD anim is an HD2 anim or the HD2 anim an HD anim
     */
    private @NotNull ImageGraphics readGraphics(@NotNull Object @NotNull [] arguments, int index) {
        ImageGrp grp = this.readHandle(arguments, index, ImageGrp.class, "GRP");
        ImageAnim hdAnim = this.readHandle(arguments, index + 1, ImageAnim.class, "anim");
        ImageAnim hd2Anim = this.readHandle(arguments, index + 2, ImageAnim.class, "anim");

        try {
            return ImageGraphics.of(grp, hdAnim, hd2Anim);
        } catch (IllegalArgumentException exception) {
            throw ScriptRuntime.rangeError(exception.getMessage());
        }
    }

    /**
     * Reads a handle argument of a kind.
     *
     * @return the handle's resource, or {@code null} for {@code -1}
     * @throws EcmaError if the argument is not an integer or names no handle of the kind
     */
    private <T> @Nullable T readHandle(@NotNull Object @NotNull [] arguments, int index, @NotNull Class<T> kind, @NotNull String kindName) {
        int id = ScriptCoreUtils.readInteger(arguments, index);

        if (id == ScriptCoreUtils.NO_ID) {
            return null;
        }

        Object handle = id >= 0 && id < this.handles.size() ? this.handles.get(id) : null;

        if (!kind.isInstance(handle)) {
            throw ScriptRuntime.rangeError(Exceptions.format("The resource handle {0} is no {1}.", id, kindName));
        }

        return kind.cast(handle);
    }

    /**
     * Finds the handle of a resource of a kind, reading the resource on its first use.
     *
     * @param ids      the handle ids of the kind by resource name
     * @param name     the resource name
     * @param kindName the kind for error messages
     * @param read     reads the resource
     * @return the handle id
     * @throws EcmaError if the resource is missing or cannot be read as the kind
     */
    private int findHandle(@NotNull Map<String, Integer> ids, @NotNull String name, @NotNull String kindName, @NotNull ResourceReader read) {
        Integer id = ids.get(name);

        if (id != null) {
            return id;
        }

        Object handle;

        try {
            handle = read.read();
        } catch (IOException exception) {
            throw ScriptRuntime.constructError(ERROR_NAME, Exceptions.format("The resource {0} is no valid {1}: {2}", name, kindName,
                                                                             exception.getMessage()));
        }

        int newId = this.handles.size();

        this.handles.add(handle);
        ids.put(name, newId);

        return newId;
    }

    private @NotNull ImageGrp readGrp(@NotNull String name) throws IOException {
        byte[] content = this.resources.read(this.requireEntry(name));

        return this.getReplacements().readGrp(content, 0, content.length, name);
    }

    private @NotNull ImageAnim readAnim(@NotNull String name) throws IOException {
        byte[] content = this.resources.read(this.requireEntry(name));

        return this.getReplacements().readAnim(content, 0, content.length, name);
    }

    /**
     * Finds a resource of the map.
     *
     * @throws EcmaError if the map has no resource of the name
     */
    private @NotNull ScriptResources.Entry requireEntry(@NotNull String name) {
        ScriptResources.Entry entry = this.resources.findEntry(name);

        if (entry == null) {
            throw ScriptRuntime.constructError(ERROR_NAME, Exceptions.format("The map has no resource {0}.", name));
        }

        return entry;
    }
}
