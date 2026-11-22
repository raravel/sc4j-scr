package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.type.ContentType;
import org.fogofwarlabs.sc4j.scr.content.*;
import org.fogofwarlabs.sc4j.scr.data.format.dat.FlingyDat;
import org.fogofwarlabs.sc4j.scr.data.format.dat.SpritesDat;
import org.fogofwarlabs.sc4j.scr.data.format.image.ImageRelations;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

import java.io.IOException;
import java.util.*;

/**
 * Defines the type namespaces of {@code core}: {@code unitType}, {@code weaponType}, {@code flingyType}, {@code spriteType},
 * {@code imageType}, {@code upgradeType}, {@code techType} and {@code orderType}, the entries of {@code units.dat}, {@code weapons.dat},
 * {@code flingy.dat}, {@code sprites.dat}, {@code images.dat}, {@code upgrades.dat}, {@code techdata.dat} and {@code orders.dat} by their
 * index. Their DAT values are {@code core.dat} ({@link ScriptDatCore}).
 * <p>
 * Each has {@code getCount()} and {@code getName(id)}, the original data name of the entry ({@code "Terran Marine"}). The {@code images.dat}
 * entries have no names, so an image type is named after its GRP path in {@code images.tbl} when the game starts: the path without the
 * {@code .grp} extension, each part between {@code /} and {@code \} with its first letter in upper case ({@code unit/terran/marine.grp} is
 * {@code UnitTerranMarine}). An image that shares its GRP path with another image and has a drawing function other than the normal one gets
 * the drawing function's name ({@code UnitProtossArbiterShadow}); a name that is still taken, ignoring letter case, gets {@code _2},
 * {@code _3} and so on in entry order ({@code UnitNeutralGeyser_2}). An image without a valid GRP path, and every image of a game without
 * game data, is named {@code Image<entry>}.
 * <p>
 * The {@code sprites.dat} and {@code flingy.dat} entries have no names either: as the game starts, a sprite type takes the name of the image
 * type of its {@code sprites.dat} Image, a flingy type the name of the sprite type of its {@code flingy.dat} Sprite, and a name that is
 * taken gets {@code _2}, {@code _3} and so on in entry order. The entries of a game without game data are named {@code Sprite<entry>} and
 * {@code Flingy<entry>}.
 * <p>
 * {@code imageType} also has {@code getAnimImageType(id)}: the image type whose HD anim the image type draws, after the installed
 * {@code images.rel} ({@code UnitTerranControl} for {@code UnitTerranControl_2}); the image type itself for one with its own anim, for every
 * image type of a game without game data and when {@code images.rel} cannot be read. {@code getDamageOverlayCount(id)} and
 * {@code setDamageOverlayCount(id, count)} read and store the image type's damage overlay byte as an EUD write does: twice the number of
 * flame positions of its damage overlay, which a unit with the image as its main image takes as its number of damage states.
 */
final class ScriptTypeCore {
    /**
     * The largest value of a BYTE.
     */
    private static final int BYTE_MAXIMUM = 0xFF;
    private static final @NotNull String GRP_EXTENSION = ".grp";
    private static final @NotNull String GRP_PATH_SEPARATORS = "[/\\\\]";
    private static final @NotNull String IMAGE_NAME_PREFIX = "Image";
    private static final @NotNull String SPRITE_NAME_PREFIX = "Sprite";
    private static final @NotNull String FLINGY_NAME_PREFIX = "Flingy";
    /**
     * The installed file of the image relations, which shares one HD anim among several image types.
     */
    private static final @NotNull String IMAGE_RELATIONS_PATH = "images.rel";
    /**
     * The number the second image with a taken name gets.
     */
    private static final int FIRST_NAME_NUMBER = 2;
    /**
     * The separator between a taken name and its number.
     */
    private static final @NotNull String NAME_NUMBER_SEPARATOR = "_";
    /**
     * The name suffixes of the drawing functions, for an image that shares its GRP path.
     */
    private static final @NotNull Map<Integer, String> DRAW_FUNCTION_SUFFIXES = Map.ofEntries(
            Map.entry(SpriteImage.MODIFIER_CLOAKING, "Cloaking"),
            Map.entry(SpriteImage.MODIFIER_CLOAKED, "Cloaked"),
            Map.entry(SpriteImage.MODIFIER_DECLOAKING, "Decloaking"),
            Map.entry(SpriteImage.MODIFIER_DETECTED_CLOAKING, "DetectedCloaking"),
            Map.entry(SpriteImage.MODIFIER_DETECTED_CLOAKED, "DetectedCloaked"),
            Map.entry(SpriteImage.MODIFIER_DETECTED_DECLOAKING, "DetectedDecloaking"),
            Map.entry(SpriteImage.MODIFIER_REMAPPING, "Remapping"),
            Map.entry(SpriteImage.MODIFIER_SHADOW, "Shadow"),
            Map.entry(SpriteImage.MODIFIER_HEALTH_BAR, "HealthBar"),
            Map.entry(SpriteImage.MODIFIER_WARP_TEXTURE, "WarpTexture"),
            Map.entry(SpriteImage.MODIFIER_SELECTION_CIRCLE, "SelectionCircle"),
            Map.entry(SpriteImage.MODIFIER_PLAYER_COLOR, "PlayerColor"),
            Map.entry(SpriteImage.MODIFIER_HALLUCINATION, "Hallucination"),
            Map.entry(SpriteImage.MODIFIER_WARP_FLASH, "WarpFlash")
    );

    /**
     * Defines the type namespaces.
     *
     * @param context    the current context
     * @param scriptGame the game binding
     * @param core       the {@code core} object
     * @param global     the global scope
     */
    static void define(@NotNull Context context, @NotNull ScriptGame scriptGame, @NotNull Scriptable core, @NotNull Scriptable global) {
        GameData gameData = scriptGame.getGame().getGameData();
        List<String> imageNames = createImageNames(gameData);
        List<String> spriteNames = createSpriteNames(gameData, imageNames);

        defineNames(ScriptCoreUtils.createNamespace(context, global, core, "unitType"), global, "unit type", getNames(ScrUnits.getTypes()));
        defineNames(ScriptCoreUtils.createNamespace(context, global, core, "weaponType"), global, "weapon type", getNames(ScrWeapons.getTypes()));
        defineNames(ScriptCoreUtils.createNamespace(context, global, core, "flingyType"), global, "flingy type",
                    createFlingyNames(gameData, spriteNames));
        defineNames(ScriptCoreUtils.createNamespace(context, global, core, "spriteType"), global, "sprite type", spriteNames);
        Scriptable imageTypes = defineNames(ScriptCoreUtils.createNamespace(context, global, core, "imageType"), global, "image type", imageNames);
        defineNames(ScriptCoreUtils.createNamespace(context, global, core, "upgradeType"), global, "upgrade type", getNames(ScrUpgrades.getTypes()));
        defineNames(ScriptCoreUtils.createNamespace(context, global, core, "techType"), global, "tech type", getNames(ScrTechs.getTypes()));
        defineNames(ScriptCoreUtils.createNamespace(context, global, core, "orderType"), global, "order type", getNames(ScrOrders.getTypes()));

        defineAnimImageTypes(imageTypes, global, createAnimImageTypes(gameData));
        defineDamageOverlayCounts(imageTypes, global, scriptGame);
    }

    /**
     * Defines {@code getCount()} and {@code getName(id)} of a type namespace.
     *
     * @return the namespace
     */
    private static @NotNull Scriptable defineNames(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String kind,
                                                   @NotNull List<@NotNull String> names) {
        ScriptCoreUtils.defineFunction(global, namespace, "getCount", 0, (context, scope, thisObject, arguments) -> (double) names.size());
        ScriptCoreUtils.defineFunction(global, namespace, "getName", 1, (context, scope, thisObject, arguments) -> {
            int id = ScriptCoreUtils.requireId(kind, ScriptCoreUtils.readInteger(arguments, 0), names.size());

            return names.get(id);
        });

        return namespace;
    }

    /**
     * @return the original data names of the types, by id
     */
    private static @NotNull List<@NotNull String> getNames(@NotNull List<? extends ContentType> types) {
        return types.stream().map(ContentType::getDebugName).toList();
    }

    /**
     * Creates the unique names of the {@code images.dat} entries from their GRP paths and drawing functions.
     *
     * @param gameData the game data, or {@code null} for a game without game data
     * @return the names, by entry
     */
    private static @NotNull List<@NotNull String> createImageNames(@Nullable GameData gameData) {
        String[] paths = new String[ScrImages.COUNT];
        Map<String, Integer> pathCounts = new HashMap<>();

        for (int id = 0; id < ScrImages.COUNT; id++) {
            String path = findGrpPath(gameData, id);

            if (path != null) {
                paths[id] = path;
                pathCounts.merge(path.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }

        String[] baseNames = new String[ScrImages.COUNT];

        for (int id = 0; id < ScrImages.COUNT; id++) {
            String path = paths[id];
            String name = path == null ? IMAGE_NAME_PREFIX + id : toImageName(path);

            if (gameData != null && path != null && pathCounts.get(path.toLowerCase(Locale.ROOT)) > 1) {
                name += DRAW_FUNCTION_SUFFIXES.getOrDefault(gameData.getImageTypes().getDrawFunction(id), "");
            }

            baseNames[id] = name;
        }

        return toUniqueNames(baseNames);
    }

    /**
     * Creates the unique names of the {@code sprites.dat} entries from the names of their images.
     *
     * @param gameData   the game data, or {@code null} for a game without game data
     * @param imageNames the names of the image types, by entry
     * @return the names, by entry
     */
    private static @NotNull List<@NotNull String> createSpriteNames(@Nullable GameData gameData, @NotNull List<@NotNull String> imageNames) {
        String[] baseNames = new String[SpritesDat.SPRITE_COUNT];

        for (int id = 0; id < baseNames.length; id++) {
            int image = gameData == null ? -1 : gameData.getSprites().get(SpritesDat.IMAGE, id);

            baseNames[id] = image >= 0 && image < imageNames.size() ? imageNames.get(image) : SPRITE_NAME_PREFIX + id;
        }

        return toUniqueNames(baseNames);
    }

    /**
     * Creates the unique names of the {@code flingy.dat} entries from the names of their sprites.
     *
     * @param gameData    the game data, or {@code null} for a game without game data
     * @param spriteNames the names of the sprite types, by entry
     * @return the names, by entry
     */
    private static @NotNull List<@NotNull String> createFlingyNames(@Nullable GameData gameData, @NotNull List<@NotNull String> spriteNames) {
        String[] baseNames = new String[FlingyDat.FLINGY_COUNT];

        for (int id = 0; id < baseNames.length; id++) {
            int sprite = gameData == null ? -1 : gameData.getFlingy().get(FlingyDat.SPRITE, id);

            baseNames[id] = sprite >= 0 && sprite < spriteNames.size() ? spriteNames.get(sprite) : FLINGY_NAME_PREFIX + id;
        }

        return toUniqueNames(baseNames);
    }

    /**
     * Makes names unique: a name that an earlier entry or another entry's base name takes, ignoring letter case, gets {@code _2}, {@code _3}
     * and so on.
     *
     * @param baseNames the names before, by entry
     * @return the unique names, by entry
     */
    private static @NotNull List<@NotNull String> toUniqueNames(@NotNull String @NotNull [] baseNames) {
        Set<String> baseNameSet = new HashSet<>();

        for (String baseName : baseNames) {
            baseNameSet.add(baseName.toLowerCase(Locale.ROOT));
        }

        String[] names = new String[baseNames.length];
        Set<String> takenNames = new HashSet<>();

        for (int id = 0; id < baseNames.length; id++) {
            String name = baseNames[id];

            if (takenNames.contains(name.toLowerCase(Locale.ROOT))) {
                int number = FIRST_NAME_NUMBER;

                while (takenNames.contains((name + NAME_NUMBER_SEPARATOR + number).toLowerCase(Locale.ROOT)) ||
                       baseNameSet.contains((name + NAME_NUMBER_SEPARATOR + number).toLowerCase(Locale.ROOT))) {
                    number++;
                }

                name += NAME_NUMBER_SEPARATOR + number;
            }

            names[id] = name;
            takenNames.add(name.toLowerCase(Locale.ROOT));
        }

        return List.of(names);
    }

    /**
     * Defines {@code getAnimImageType(id)} of the image type namespace.
     *
     * @param namespace      the namespace object
     * @param global         the global scope
     * @param animImageTypes the image type whose HD anim each image type draws, by image type
     */
    private static void defineAnimImageTypes(@NotNull Scriptable namespace, @NotNull Scriptable global, int @NotNull [] animImageTypes) {
        ScriptCoreUtils.defineFunction(global, namespace, "getAnimImageType", 1, (context, scope, thisObject, arguments) -> {
            int id = ScriptCoreUtils.requireId("image type", ScriptCoreUtils.readInteger(arguments, 0), ScrImages.COUNT);

            return (double) animImageTypes[id];
        });
    }

    /**
     * Defines {@code getDamageOverlayCount(id)} and {@code setDamageOverlayCount(id, count)} of the image type namespace.
     */
    private static void defineDamageOverlayCounts(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull ScriptGame scriptGame) {
        ScriptCoreUtils.defineFunction(global, namespace, "getDamageOverlayCount", 1, (context, scope, thisObject, arguments) -> {
            int id = ScriptCoreUtils.requireId("image type", ScriptCoreUtils.readInteger(arguments, 0), ScrImages.COUNT);

            try {
                return (double) scriptGame.getGame().requireGameData().getImageDamageOverlayCount(id);
            } catch (IOException exception) {
                throw ScriptRuntime.typeError(Exceptions.format("The damage overlay of the image type {0} cannot be read.", id));
            }
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setDamageOverlayCount", 2, (context, scope, thisObject, arguments) -> {
            scriptGame.checkWritable();

            int id = ScriptCoreUtils.requireId("image type", ScriptCoreUtils.readInteger(arguments, 0), ScrImages.COUNT);
            int count = ScriptCoreUtils.readInteger(arguments, 1);

            if (count < 0 || count > BYTE_MAXIMUM) {
                throw ScriptRuntime.rangeError(Exceptions.format("The damage overlay count {0} is not from 0 to {1}.", count, BYTE_MAXIMUM));
            }

            scriptGame.getGame().requireGameData().setImageDamageOverlayCount(id, count);

            return Context.getUndefinedValue();
        });
    }

    /**
     * Resolves the image type whose HD anim each {@code images.dat} entry draws, after the installed {@code images.rel}.
     *
     * @param gameData the game data, or {@code null} for a game without game data
     * @return the image types, by entry: the entry itself for an entry with its own anim, for a game without game data and when {@code images.rel} cannot be read
     */
    private static int @NotNull [] createAnimImageTypes(@Nullable GameData gameData) {
        int[] animImageTypes = new int[ScrImages.COUNT];
        ImageRelations relations = readImageRelations(gameData);

        for (int id = 0; id < ScrImages.COUNT; id++) {
            int animImageType = relations == null ? id : relations.getAnimationImage(id);

            animImageTypes[id] = animImageType >= 0 && animImageType < ScrImages.COUNT ? animImageType : id;
        }

        return animImageTypes;
    }

    /**
     * @return the installed image relations, or {@code null} if there is no game data or {@code images.rel} cannot be read
     */
    private static @Nullable ImageRelations readImageRelations(@Nullable GameData gameData) {
        if (gameData == null) {
            return null;
        }

        try {
            return ImageRelations.parse(gameData.readInstalledFile(IMAGE_RELATIONS_PATH));
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * @return the GRP path of an {@code images.dat} entry, or {@code null} if there is no game data or the entry has no valid GRP path
     */
    private static @Nullable String findGrpPath(@Nullable GameData gameData, int id) {
        if (gameData == null) {
            return null;
        }

        try {
            return gameData.getGrpPath(id);
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * Converts a GRP path to a name: the path without its extension, each part with its first letter in upper case, after {@code Image} if
     * it would not start as an identifier.
     *
     * @param path the GRP path, ending with {@code .grp} in any case
     * @return the name
     */
    private static @NotNull String toImageName(@NotNull String path) {
        StringBuilder name = new StringBuilder();

        for (String part : path.substring(0, path.length() - GRP_EXTENSION.length()).split(GRP_PATH_SEPARATORS)) {
            if (!part.isEmpty()) {
                name.append(Character.toUpperCase(part.charAt(0))).append(part, 1, part.length());
            }
        }

        if (name.isEmpty() || !Character.isLetter(name.charAt(0))) {
            name.insert(0, IMAGE_NAME_PREFIX);
        }

        return name.toString();
    }


    private ScriptTypeCore() {
    }
}
