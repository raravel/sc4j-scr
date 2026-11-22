package org.fogofwarlabs.sc4j.scr.game.state;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.io.AuditedResourceUtils;
import org.fogofwarlabs.sc4j.engine.animation.AnimationLibrary;
import org.fogofwarlabs.sc4j.engine.animation.AnimationProgram;
import org.fogofwarlabs.sc4j.engine.content.table.GameContent;
import org.fogofwarlabs.sc4j.engine.game.GameDefinition;
import org.fogofwarlabs.sc4j.engine.spatial.SearchCounter;
import org.fogofwarlabs.sc4j.scr.content.*;
import org.fogofwarlabs.sc4j.scr.content.load.ScrContentImporter;
import org.fogofwarlabs.sc4j.scr.content.table.ScrDatBindingUtils;
import org.fogofwarlabs.sc4j.scr.content.table.ScrDatKind;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.casc.SoundPathResolver;
import org.fogofwarlabs.sc4j.scr.data.format.dat.*;
import org.fogofwarlabs.sc4j.scr.data.format.image.GrpFile;
import org.fogofwarlabs.sc4j.scr.data.format.image.PcxIndexedImage;
import org.fogofwarlabs.sc4j.scr.data.format.iscript.IscriptFile;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.FoliageDefinition;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.format.text.TblFile;
import org.fogofwarlabs.sc4j.scr.data.type.*;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrContentExtensionUtils;
import org.fogofwarlabs.sc4j.scr.game.movement.ScrFlingyProperties;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerColorRows;
import org.fogofwarlabs.sc4j.scr.game.presentation.ObserverOverlay;
import org.fogofwarlabs.sc4j.scr.game.presentation.SoundPreloadListUtils;
import org.fogofwarlabs.sc4j.scr.game.production.ScrRequirementSystem;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.load.ScrIscriptImporter;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.load.ScrIscriptOffsets;
import org.fogofwarlabs.sc4j.scr.game.terrain.Foliage;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.NativeCodeConstants;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Holds the loaded game data: the DAT tables with their typed tables, the iscript, the string tables and the installed
 * resources the simulation and the presentation read.
 * <p>
 * Loaded once from the CASC storage ({@link #load}) and shared by all simulations. A game works on its own copy
 * ({@link #createGameCopy(GameContent)}): DAT tables with the installed fields and native memory layout over the game's
 * engine content, which is the only store of the game's DAT values, so EUD, map settings, saved games and the typed
 * tables all read and write the content. The copy shares the storage, scripts, images and resource caches with the
 * installed data.
 */
public final class GameData implements AutoCloseable {
    /**
     * The installation directory of the game executable, relative to the install root.
     */
    public static final @NotNull String EXECUTABLE_DIRECTORY = "x86_64";
    /**
     * The file name of the game executable in {@link #EXECUTABLE_DIRECTORY}.
     */
    public static final @NotNull String EXECUTABLE_FILE_NAME = "StarCraft.exe";

    /**
     * The file name prefixes of the eight classic tilesets, in tileset index order.
     */
    private static final @NotNull String @NotNull [] TILESET_NAMES = {
            "badlands",
            "platform",
            "install",
            "ashworld",
            "jungle",
            "desert",
            "ice",
            "twilight"
    };
    /**
     * The size of a terrain palette ({@code .wpe}): {@code 256} four-byte colors.
     */
    private static final int TERRAIN_PALETTE_SIZE = 1024;
    /**
     * The overlay tables of an image, attack through shield ({@link ScrImageTypeView#OVERLAY_ATTACK} and following).
     */
    private static final int OVERLAY_TABLE_COUNT = ScrImageTypeView.OVERLAY_SHIELD + 1;
    /**
     * The size of the overlay resource header; the frame offset DWORDs follow it.
     */
    private static final int OVERLAY_HEADER_SIZE = 8;
    /**
     * The size of one frame offset of an overlay resource.
     */
    private static final int OVERLAY_FRAME_OFFSET_SIZE = 4;
    /**
     * The size of one position of an overlay frame: a signed horizontal and a signed vertical byte.
     */
    private static final int OVERLAY_POSITION_SIZE = 2;
    /**
     * Little-endian DWORD access to a resource at any byte offset.
     */
    private static final @NotNull VarHandle LITTLE_ENDIAN_DWORD = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    /**
     * The damage-overlay positions of the first frame that the damage-overlay byte counts.
     */
    private static final int DAMAGE_OVERLAY_POSITIONS = 22;
    /**
     * The coordinate of an absent damage-overlay position.
     */
    private static final int ABSENT_OVERLAY_COORDINATE = 127;
    /**
     * The amount the damage-overlay byte counts for each present damage-overlay position.
     */
    private static final int DAMAGE_OVERLAY_COUNT_PER_POSITION = 2;
    /**
     * The initial replay label texts.
     */
    private static final int INITIAL_LABEL_COUNT = 4;
    /**
     * The mask of a BYTE value.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of a WORD value.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * Creates a {@link GameData} instance by opening the storage and loading its tables.
     * <p>
     * Closes the opened storage if loading fails. The returned instance owns the storage and closes it when closed.
     *
     * @param gameRoot the game installation directory accepted by {@link CascStorage#open}
     * @return the created {@link GameData} instance
     * @throws IOException if the storage cannot be opened or a table cannot be loaded
     */
    public static @NotNull GameData loadFrom(@NotNull Path gameRoot) throws IOException {
        CascStorage storage = CascStorage.open(gameRoot);

        try {
            return load(storage);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                storage.close();
            } catch (RuntimeException | Error closeFailure) {
                exception.addSuppressed(closeFailure);
            }

            throw exception;
        }
    }

    /**
     * Creates a {@link GameData} instance by loading the tables from the given storage.
     * <p>
     * The content extensions of the running mod ({@link ScrContentExtensionUtils#extendInstalledData}) extend the loaded data before it is
     * returned. The caller keeps the storage if loading fails; the returned instance owns the storage and closes it when closed.
     *
     * @param storage the CASC storage
     * @return the created {@link GameData} instance
     * @throws IOException if a table cannot be loaded, or a file a content extension reads cannot be read
     */
    public static @NotNull GameData load(@NotNull CascStorage storage) throws IOException {
        DatTable units = UnitsDat.createSchema().parse(storage.readFile("arr/units.dat"));
        DatTable weapons = WeaponsDat.createSchema().parse(storage.readFile("arr/weapons.dat"));

        UnitsDat.initializeTargetAcquisitionRanges(units, weapons);

        GameData gameData = new GameData(storage,
                                         units,
                                         weapons,
                                         storage.readParsed("arr/flingy.dat", DatTable.class, FlingyDat.createSchema()::parse),
                                         storage.readParsed("arr/sprites.dat", DatTable.class, SpritesDat.createSchema()::parse),
                                         storage.readParsed("arr/images.dat", DatTable.class, ImagesDat.createSchema()::parse),
                                         OrdersDat.createSchema().parse(storage.readFile("arr/orders.dat")),
                                         UpgradesDat.createSchema().parse(storage.readFile("arr/upgrades.dat")),
                                         TechdataDat.createSchema().parse(storage.readFile("arr/techdata.dat")),
                                         SfxdataDat.createSchema().parse(storage.readFile("arr/sfxdata.dat")),
                                         IscriptFile.parse(storage.readFile("scripts/iscript.bin")),
                                         storage.readParsed("arr/images.tbl", TblFile.class, TblFile::parse));

        ScrRequirementSystem.buildInstalledIndices(gameData);
        ScrContentExtensionUtils.extendInstalledData(gameData);

        return gameData;
    }

    /* Storage and tables. */
    private final @NotNull CascStorage storage;
    /**
     * The installed data this game copy is made from, or {@code null} for the installed data itself.
     */
    private final @Nullable GameData installed;
    /**
     * The definition of the games of the installed data, built on first use ({@link #getGameDefinition()}); always
     * {@code null} in a game copy.
     */
    private @Nullable GameDefinition gameDefinition;
    /**
     * The unit types a mod adds after the native ones, open until the game definition is built; a game copy shares the installed data's.
     */
    private final @NotNull ScrAddedUnitTypes addedUnitTypes;
    /**
     * The images, sprites, flingy entries and scripts a mod adds after the native ones, open until the game definition is built; a game copy
     * shares the installed data's.
     */
    private final @NotNull ScrAddedGraphics addedGraphics;

    private final @NotNull DatTable units;
    private final @NotNull DatTable weapons;
    private final @NotNull DatTable flingy;
    private final @NotNull DatTable sprites;
    private final @NotNull DatTable images;
    private final @NotNull DatTable orders;
    private final @NotNull DatTable upgrades;
    private final @NotNull DatTable techdata;
    private final @NotNull DatTable sfxdata;

    private final @NotNull ScrUnitTypeView unitTypes;
    private final @NotNull ScrWeaponTypeView weaponTypes;
    private final @NotNull ScrFlingyTypeView flingyTypes;
    private final @NotNull ScrSpriteTypeView spriteTypes;
    private final @NotNull ScrImageTypeView imageTypes;
    private final @NotNull ScrOrderTypeView orderTypes;
    private final @NotNull ScrUpgradeTypeView upgradeTypes;
    private final @NotNull ScrTechTypeView techTypes;
    private final @NotNull ScrSoundTypeView soundTypes;
    private final @NotNull ScrFlingyProperties flingyProperties;

    /* Scripts, strings and shared resources. */
    /**
     * The iscript of the games: the installed file, replaced once by the file with the added scripts when the game definition is built.
     */
    private @NotNull IscriptFile iscript;
    /**
     * The iscript rebuilt as instructions, shared by the game copies; replaced with {@link #iscript}.
     */
    private @NotNull ScrIscriptOffsets iscriptOffsets;
    private @NotNull AnimationLibrary iscriptLibrary;
    private @NotNull AnimationProgram iscriptProgram;
    private final @NotNull TblFile imagesTbl;
    private @Nullable TblFile soundNames;
    private @Nullable SoundPathResolver soundPaths;
    private final @NotNull Map<Integer, GrpFile> grpCache;
    /**
     * The type-7 code constants, snapshot records and Storm archive records of the installed executable
     * ({@code x86_64/StarCraft.exe} next to the storage), read once; unknown when the executable is missing or another
     * version.
     */
    private final @NotNull NativeCodeConstants codeConstants;

    /* Image overlays. */
    private final int @NotNull [] imageDamageOverlayCounts;
    /**
     * The installed data whose damage-overlay bytes a game's copy starts from, or {@code null} for the installed data
     * itself.
     */
    private final @Nullable GameData imageDamageOverlayBase;
    /**
     * The overlay resources by image and overlay table, loaded on first use.
     */
    private final byte @NotNull [] @Nullable [] imageOverlayResources;

    /* Process-wide caches shared by the game copies. */
    private final @Nullable TerrainTileset @NotNull [] terrainTilesets;
    /**
     * The race sound preload lists ({@link SoundPreloadListUtils}), built once from {@code rez/sfx.json} and shared by the game
     * copies; element {@code 0} holds the three lists once built.
     */
    private final byte @NotNull [] @Nullable [] @NotNull [] soundPreloadLists;
    /**
     * The unit finder search counter of the process: the counter belongs to the client process, not to a game; it starts at zero with the
     * process and no game start resets it (the finder reset clears only the stamps), so the local interface queries and every earlier game of
     * the process move it. The installed data is loaded once per client process and every game copy shares it; data loaded afresh is a fresh
     * process.
     */
    private final @NotNull SearchCounter unitFinderSearchCounter;

    /* Caches of this instance, read on first use. */
    /**
     * The player color tables ({@link #getPlayerColorTables()}).
     */
    private int @Nullable [] @NotNull [] playerColorTables;
    private byte @Nullable [] foliageDefinitions;
    private byte @Nullable [] foliageAnimation;
    private final byte @NotNull [] @Nullable [] foliageDistributions;
    private final byte @NotNull [] @Nullable [] foliageTerrainGroups;

    /**
     * Creates a {@link GameData} instance over the installed data.
     *
     * @param storage   the CASC storage, owned by the created instance
     * @param units     the unit type table ({@code units.dat})
     * @param weapons   the weapon type table ({@code weapons.dat})
     * @param flingy    the flingy type table ({@code flingy.dat})
     * @param sprites   the sprite type table ({@code sprites.dat})
     * @param images    the image type table ({@code images.dat})
     * @param orders    the order type table ({@code orders.dat})
     * @param upgrades  the upgrade type table ({@code upgrades.dat})
     * @param techdata  the technology type table ({@code techdata.dat})
     * @param sfxdata   the sound type table ({@code sfxdata.dat})
     * @param iscript   the animation scripts ({@code iscript.bin})
     * @param imagesTbl the image file names ({@code images.tbl})
     */
    private GameData(@NotNull CascStorage storage,
                     @NotNull DatTable units,
                     @NotNull DatTable weapons,
                     @NotNull DatTable flingy,
                     @NotNull DatTable sprites,
                     @NotNull DatTable images,
                     @NotNull DatTable orders,
                     @NotNull DatTable upgrades,
                     @NotNull DatTable techdata,
                     @NotNull DatTable sfxdata,
                     @NotNull IscriptFile iscript,
                     @NotNull TblFile imagesTbl) {
        this.storage        = storage;
        this.installed      = null;
        this.gameDefinition = null;
        this.addedUnitTypes = new ScrAddedUnitTypes();
        this.addedGraphics  = ScrAddedGraphics.create(iscript);

        this.units    = units;
        this.weapons  = weapons;
        this.flingy   = flingy;
        this.sprites  = sprites;
        this.images   = images;
        this.orders   = orders;
        this.upgrades = upgrades;
        this.techdata = techdata;
        this.sfxdata  = sfxdata;

        this.unitTypes    = UnitsDat.createTypeTable(units);
        this.weaponTypes  = WeaponsDat.createTypeTable(weapons);
        this.flingyTypes  = FlingyDat.createTypeTable(flingy);
        this.spriteTypes  = SpritesDat.createTypeTable(sprites);
        this.imageTypes   = ImagesDat.createTypeTable(images);
        this.orderTypes   = OrdersDat.createTypeTable(orders);
        this.upgradeTypes = UpgradesDat.createTypeTable(upgrades);
        this.techTypes    = TechdataDat.createTypeTable(techdata);
        this.soundTypes   = SfxdataDat.createTypeTable(sfxdata);

        this.flingyProperties = new ScrFlingyProperties(this.flingyTypes, null);

        this.iscript        = iscript;
        this.iscriptOffsets = ScrIscriptOffsets.create(iscript);
        this.iscriptLibrary = new ScrIscriptImporter(iscript, this.iscriptOffsets).importLibrary();
        this.iscriptProgram = this.iscriptLibrary.getPrograms().getFirst();
        this.imagesTbl      = imagesTbl;
        this.soundNames     = null;
        this.soundPaths     = null;
        this.grpCache       = new HashMap<>();
        this.codeConstants  = new NativeCodeConstants();

        this.codeConstants.loadExecutable(storage.getInstallRoot().resolve(EXECUTABLE_DIRECTORY).resolve(EXECUTABLE_FILE_NAME));

        this.imageDamageOverlayCounts = new int[ImagesDat.IMAGE_COUNT];
        this.imageDamageOverlayBase   = null;
        this.imageOverlayResources    = new byte[ImagesDat.IMAGE_COUNT * OVERLAY_TABLE_COUNT][];

        Arrays.fill(this.imageDamageOverlayCounts, -1);

        this.terrainTilesets         = new TerrainTileset[TILESET_NAMES.length];
        this.soundPreloadLists       = new byte[1][][];
        this.unitFinderSearchCounter = new SearchCounter();

        this.playerColorTables    = null;
        this.foliageDefinitions   = null;
        this.foliageAnimation     = null;
        this.foliageDistributions = new byte[TILESET_NAMES.length][];
        this.foliageTerrainGroups = new byte[TILESET_NAMES.length][];
    }

    /**
     * Creates a {@link GameData} instance for one game: DAT tables over the game's engine content that start with the
     * values of the base data's tables, sharing the storage, scripts, images and resource caches of the base data.
     *
     * @param base    the data the copy is made from
     * @param content the engine content of the game, the store of the copy's DAT values
     */
    private GameData(@NotNull GameData base, @NotNull GameContent content) {
        GameData installedData = base.installed == null ? base : base.installed;

        this.storage        = base.storage;
        this.installed      = installedData;
        this.gameDefinition = null;
        this.addedUnitTypes = installedData.addedUnitTypes;
        this.addedGraphics  = installedData.addedGraphics;

        this.units    = ScrDatBindingUtils.bind(ScrDatKind.UNITS, content, installedData.units);
        this.weapons  = ScrDatBindingUtils.bind(ScrDatKind.WEAPONS, content, installedData.weapons);
        this.flingy   = ScrDatBindingUtils.bind(ScrDatKind.FLINGY, content, installedData.flingy);
        this.sprites  = ScrDatBindingUtils.bind(ScrDatKind.SPRITES, content, installedData.sprites);
        this.images   = ScrDatBindingUtils.bind(ScrDatKind.IMAGES, content, installedData.images);
        this.orders   = ScrDatBindingUtils.bind(ScrDatKind.ORDERS, content, installedData.orders);
        this.upgrades = ScrDatBindingUtils.bind(ScrDatKind.UPGRADES, content, installedData.upgrades);
        this.techdata = ScrDatBindingUtils.bind(ScrDatKind.TECHDATA, content, installedData.techdata);
        this.sfxdata  = ScrDatBindingUtils.bind(ScrDatKind.SOUNDS, content, installedData.sfxdata);

        this.units.copyValuesFrom(base.units);
        this.weapons.copyValuesFrom(base.weapons);
        this.flingy.copyValuesFrom(base.flingy);
        this.sprites.copyValuesFrom(base.sprites);
        this.images.copyValuesFrom(base.images);
        this.orders.copyValuesFrom(base.orders);
        this.upgrades.copyValuesFrom(base.upgrades);
        this.techdata.copyValuesFrom(base.techdata);
        this.sfxdata.copyValuesFrom(base.sfxdata);

        this.unitTypes    = UnitsDat.createTypeTable(this.units, ScrDatBindingUtils.bindTraitColumns(content),
                                                     ScrDatBindingUtils.bindAddedColumns(ScrDatKind.UNITS, content),
                                                     ScrDatBindingUtils.bindAddedTraitColumns(content), installedData.addedUnitTypes::getBaseId);
        this.weaponTypes  = WeaponsDat.createTypeTable(this.weapons, ScrDatBindingUtils.bindAddedColumns(ScrDatKind.WEAPONS, content));
        this.flingyTypes  = FlingyDat.createTypeTable(this.flingy, ScrDatBindingUtils.bindAddedColumns(ScrDatKind.FLINGY, content));
        this.spriteTypes  = SpritesDat.createTypeTable(this.sprites, ScrDatBindingUtils.bindAddedColumns(ScrDatKind.SPRITES, content));
        this.imageTypes   = ImagesDat.createTypeTable(this.images, ScrDatBindingUtils.bindAddedColumns(ScrDatKind.IMAGES, content));
        this.orderTypes   = OrdersDat.createTypeTable(this.orders);
        this.upgradeTypes = UpgradesDat.createTypeTable(this.upgrades);
        this.techTypes    = TechdataDat.createTypeTable(this.techdata);
        this.soundTypes   = SfxdataDat.createTypeTable(this.sfxdata);

        this.flingyProperties = new ScrFlingyProperties(this.flingyTypes, content.getMotionProfiles());

        this.iscript        = base.iscript;
        this.iscriptOffsets = base.iscriptOffsets;
        this.iscriptLibrary = base.iscriptLibrary;
        this.iscriptProgram = base.iscriptProgram;
        this.imagesTbl      = base.imagesTbl;
        this.soundNames     = base.soundNames;
        this.soundPaths     = base.soundPaths;
        this.grpCache       = base.grpCache;
        this.codeConstants  = base.codeConstants;

        // A game's own table for EUD writes, read through to the installed bytes until written.
        this.imageDamageOverlayCounts = new int[ImagesDat.IMAGE_COUNT];
        this.imageDamageOverlayBase   = base.imageDamageOverlayBase == null ? base : base.imageDamageOverlayBase;
        this.imageOverlayResources    = base.imageOverlayResources;

        Arrays.fill(this.imageDamageOverlayCounts, -1);

        this.terrainTilesets         = base.terrainTilesets;
        this.soundPreloadLists       = base.soundPreloadLists;
        this.unitFinderSearchCounter = base.unitFinderSearchCounter;

        this.playerColorTables    = null;
        this.foliageDefinitions   = null;
        this.foliageAnimation     = null;
        this.foliageDistributions = new byte[TILESET_NAMES.length][];
        this.foliageTerrainGroups = new byte[TILESET_NAMES.length][];
    }

    /**
     * @return the unit type table as read from {@code units.dat}, by column
     */
    public @NotNull DatTable getUnits() {
        return this.units;
    }

    /**
     * @return the weapon type table as read from {@code weapons.dat}, by column
     */
    public @NotNull DatTable getWeapons() {
        return this.weapons;
    }

    /**
     * @return the flingy type table as read from {@code flingy.dat}, by column
     */
    public @NotNull DatTable getFlingy() {
        return this.flingy;
    }

    /**
     * @return the sprite type table as read from {@code sprites.dat}, by column
     */
    public @NotNull DatTable getSprites() {
        return this.sprites;
    }

    /**
     * @return the image type table as read from {@code images.dat}, by column
     */
    public @NotNull DatTable getImages() {
        return this.images;
    }

    /**
     * @return the order type table as read from {@code orders.dat}, by column
     */
    public @NotNull DatTable getOrders() {
        return this.orders;
    }

    /**
     * @return the upgrade type table as read from {@code upgrades.dat}, by column
     */
    public @NotNull DatTable getUpgrades() {
        return this.upgrades;
    }

    /**
     * @return the technology type table as read from {@code techdata.dat}, by column
     */
    public @NotNull DatTable getTechdata() {
        return this.techdata;
    }

    /**
     * @return the sound type table as read from {@code sfxdata.dat}, by column
     */
    public @NotNull DatTable getSfxdata() {
        return this.sfxdata;
    }

    /**
     * Returns the unit types a mod adds after the native {@code units.dat} entries. The registration is open until the game definition is built
     * ({@link #getGameDefinition()}), so a mod adds its types right after loading the data; a game copy returns the installed data's types. A
     * game copy's unit type table ({@link #getUnitTypes()}) reads them from the ids {@link ScrAddedUnitTypes#FIRST_ID} on.
     *
     * @return the added unit types
     */
    public @NotNull ScrAddedUnitTypes getAddedUnitTypes() {
        return this.addedUnitTypes;
    }

    /**
     * Returns the images, sprites, flingy entries and iscript scripts a mod adds after the native ones. The registration is open until the game
     * definition is built ({@link #getGameDefinition()}), so a mod adds its entries right after loading the data, as it adds its unit types; a game
     * copy returns the installed data's entries. A game copy's image, sprite and flingy tables read them from the first added id on, and the
     * iscript of the games ({@link #getIscript()}) holds the added scripts once the definition is built.
     *
     * @return the added graphics
     */
    public @NotNull ScrAddedGraphics getAddedGraphics() {
        return this.addedGraphics;
    }

    /**
     * Returns the native unit type whose per-type rules apply to a type: the type itself for every id that is not an added unit type, the base
     * type an added unit type copies its values from ({@link ScrAddedUnitType#getBaseType()}) for an added type. A routine that switches over the
     * native unit types switches over this type, so an added unit is handled as a unit of its base type.
     *
     * @param unitType the unit type
     * @return the native unit type of the per-type rules
     */
    public int getBaseType(int unitType) {
        return this.addedUnitTypes.contains(unitType) ? this.addedUnitTypes.getBaseId(unitType) : unitType;
    }

    /**
     * Returns the native unit type whose per-type rules apply to a unit: the base type ({@link #getBaseType(int)}) of its type. The computer
     * player reads the type of a unit through this for every per-type rule (comparisons, switches, per-type lists), so it handles an added unit
     * as a unit of its base type; the values of the unit type table stay the unit's own.
     *
     * @param unit the unit
     * @return the native unit type of the per-type rules
     */
    public int getBaseType(@NotNull Unit unit) {
        return this.getBaseType(unit.getUnitId());
    }

    /**
     * Returns the button set a unit of a type shows as its own: the type itself for a native type, the native type an added unit type names
     * ({@link ScrAddedUnitType#getButtonSetType()}) for an added type, whose id would otherwise name a native
     * special button set.
     *
     * @param unitType the unit type
     * @return the button set
     */
    public int getButtonSet(int unitType) {
        return this.addedUnitTypes.contains(unitType) ? ScrUnits.getNativeId(this.addedUnitTypes.get(unitType).getButtonSetType()) : unitType;
    }

    /**
     * Returns the unit type whose wireframe the status panel and whose command icon the train button and the observer production list show for a
     * type: the type itself for a native type, the native type an added unit type names
     * ({@link ScrAddedUnitType#getWireframeType()}) for an added type.
     *
     * @param unitType the unit type
     * @return the wireframe type
     */
    public int getWireframeType(int unitType) {
        return this.addedUnitTypes.contains(unitType) ? ScrUnits.getNativeId(this.addedUnitTypes.get(unitType).getWireframeType()) : unitType;
    }

    /**
     * Returns the frame of the wireframe sheets the status panel shows for a unit type: its wireframe type ({@link #getWireframeType}), or the
     * own added wireframe of an added type that names one ({@link ScrAddedUnitType#getWireframe()}).
     *
     * @param unitType the unit type
     * @return the wireframe frame
     */
    public int getWireframe(int unitType) {
        if (this.addedUnitTypes.contains(unitType)) {
            int wireframe = this.addedUnitTypes.get(unitType).getWireframe();

            if (wireframe != ScrAddedUnitType.NO_INTERFACE_FRAME) {
                return wireframe;
            }
        }

        return this.getWireframeType(unitType);
    }

    /**
     * Returns the frame of the command icon sheet that the train button and the production queue show for a unit type: its wireframe type
     * ({@link #getWireframeType}), or the own added command icon of an added type that names one
     * ({@link ScrAddedUnitType#getCommandIcon()}).
     *
     * @param unitType the unit type
     * @return the command icon frame
     */
    public int getCommandIcon(int unitType) {
        if (this.addedUnitTypes.contains(unitType)) {
            int commandIcon = this.addedUnitTypes.get(unitType).getCommandIcon();

            if (commandIcon != ScrAddedUnitType.NO_INTERFACE_FRAME) {
                return commandIcon;
            }
        }

        return this.getWireframeType(unitType);
    }

    /**
     * Returns the name the interface shows for an added unit type.
     *
     * @param unitType the unit type
     * @return the name of the added type, or {@code null} for every other type
     */
    public @Nullable String getAddedUnitName(int unitType) {
        return this.addedUnitTypes.contains(unitType) ? this.addedUnitTypes.get(unitType).getName() : null;
    }

    /**
     * @return the unit type table, typed over {@link #getUnits()}
     */
    public @NotNull ScrUnitTypeView getUnitTypes() {
        return this.unitTypes;
    }

    /**
     * @return the weapon type table, typed over {@link #getWeapons()}
     */
    public @NotNull ScrWeaponTypeView getWeaponTypes() {
        return this.weaponTypes;
    }

    /**
     * @return the flingy type table, typed over {@link #getFlingy()}
     */
    public @NotNull ScrFlingyTypeView getFlingyTypes() {
        return this.flingyTypes;
    }

    /**
     * @return the flingy type values of the units for the engine motion algorithm: over {@link #getFlingyTypes()} and, in a game copy, the
     * accelerating turn column of its content
     */
    public @NotNull ScrFlingyProperties getFlingyProperties() {
        return this.flingyProperties;
    }

    /**
     * @return the sprite type table, typed over {@link #getSprites()}
     */
    public @NotNull ScrSpriteTypeView getSpriteTypes() {
        return this.spriteTypes;
    }

    /**
     * @return the image type table, typed over {@link #getImages()}
     */
    public @NotNull ScrImageTypeView getImageTypes() {
        return this.imageTypes;
    }

    /**
     * @return the order type table, typed over {@link #getOrders()}
     */
    public @NotNull ScrOrderTypeView getOrderTypes() {
        return this.orderTypes;
    }

    /**
     * @return the upgrade type table, typed over {@link #getUpgrades()}
     */
    public @NotNull ScrUpgradeTypeView getUpgradeTypes() {
        return this.upgradeTypes;
    }

    /**
     * @return the technology type table, typed over {@link #getTechdata()}
     */
    public @NotNull ScrTechTypeView getTechTypes() {
        return this.techTypes;
    }

    /**
     * @return the sound type table, typed over {@link #getSfxdata()}
     */
    public @NotNull ScrSoundTypeView getSoundTypes() {
        return this.soundTypes;
    }

    /**
     * @return the animation scripts ({@code iscript.bin})
     */
    public @NotNull IscriptFile getIscript() {
        return this.iscript;
    }

    /**
     * @return the map between the iscript offsets and the positions of the iscript program
     */
    public @NotNull ScrIscriptOffsets getIscriptOffsets() {
        return this.iscriptOffsets;
    }

    /**
     * @return the iscript as an animation library: one program with a position for every iscript offset
     * ({@link #getIscriptOffsets()}), the 28 animation kinds and one animation set per script identifier
     */
    public @NotNull AnimationLibrary getIscriptLibrary() {
        return this.iscriptLibrary;
    }

    /**
     * @return the one program of the iscript library
     */
    public @NotNull AnimationProgram getIscriptProgram() {
        return this.iscriptProgram;
    }

    /**
     * Sets the locale and audio set for {@link #readSound(int)}. Presentation only; the simulation never reads sounds.
     *
     * @param soundPaths the resolver, or {@code null} for the plain {@code sound/} root
     */
    public void setSoundPaths(@Nullable SoundPathResolver soundPaths) {
        this.soundPaths = soundPaths;
    }

    /**
     * @return the code constants of the installed executable ({@link NativeCodeConstants#isKnown()} tells whether it was read)
     */
    public @NotNull NativeCodeConstants getCodeConstants() {
        return this.codeConstants;
    }

    /**
     * Returns the definition of the games of the installed data: the SC:R simulation ({@link Game#register}) and the
     * content imported from the installed DAT tables, the added unit types ({@link #getAddedUnitTypes()}) and the added graphics
     * ({@link #getAddedGraphics()}), both closed by the build. The installed data builds it once, on first use, after its requirement indices are
     * built, and first replaces its iscript with the one holding the added scripts; its game copies return the same instance.
     *
     * @return the game definition
     * @throws IllegalStateException if the added scripts cannot be assembled
     */
    public @NotNull GameDefinition getGameDefinition() {
        GameData source = this.installed;

        if (source != null) {
            return source.getGameDefinition();
        }

        synchronized (this) {
            GameDefinition definition = this.gameDefinition;

            if (definition == null) {
                this.applyAddedScripts();

                definition = Game.createDefinition(this);

                this.gameDefinition = definition;
            }

            return definition;
        }
    }

    /**
     * Replaces the iscript of the installed data with the one holding the added scripts ({@link ScrAddedGraphics#createIscript}); without added
     * scripts the installed iscript stays.
     *
     * @throws IllegalStateException if the added scripts cannot be assembled
     */
    private void applyAddedScripts() {
        if (this.addedGraphics.getScripts().isEmpty()) {
            return;
        }

        IscriptFile scripts;

        try {
            scripts = this.addedGraphics.createIscript(this.iscript);
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "The added iscript scripts cannot be assembled.");
        }

        this.iscript        = scripts;
        this.iscriptOffsets = ScrIscriptOffsets.create(scripts);
        this.iscriptLibrary = new ScrIscriptImporter(scripts, this.iscriptOffsets).importLibrary();
        this.iscriptProgram = this.iscriptLibrary.getPrograms().getFirst();
    }

    /**
     * @return the unit finder search counter of the process that loaded this data, shared by its game copies
     */
    public @NotNull SearchCounter getUnitFinderSearchCounter() {
        return this.unitFinderSearchCounter;
    }

    /**
     * Creates a {@link GameData} instance with the DAT memory of one game outside a running game: DAT tables over a new
     * engine content of the game definition ({@link #getGameDefinition()}), which start with this instance's values and
     * which EUD, map settings and saved games can write. This instance is not modified. A game creates its own copy over
     * its engine content ({@link #createGameCopy(GameContent)}).
     *
     * @return the created {@link GameData} instance
     */
    public @NotNull GameData createGameCopy() {
        return new GameData(this, GameContent.create(this.getGameDefinition().getContent(), ScrContentImporter.PLAYER_COUNT));
    }

    /**
     * Creates a {@link GameData} instance with the DAT memory of one game: DAT tables over the game's engine content, which
     * start with this instance's values. Storage, scripts, images and resource caches are shared with this instance, and
     * this instance is not modified. Only the instance created by {@link #load(CascStorage)} owns the storage and may be
     * closed.
     *
     * @param content the engine content of the game, created from {@link #getGameDefinition()}
     * @return the created {@link GameData} instance
     * @throws IllegalArgumentException if the content was created from another definition
     */
    public @NotNull GameData createGameCopy(@NotNull GameContent content) {
        if (content.getDefinition() != this.getGameDefinition().getContent()) {
            throw Exceptions.newIllegalArgumentException("The content was not created from the definition of this game data.");
        }

        return new GameData(this, content);
    }

    /**
     * Returns the first {@code images.dat} entry that names the same graphics file as the supplied image. Saved image records
     * use this first matching entry.
     *
     * @param imageTypeId the {@code images.dat} index
     * @return the index of the first {@code images.dat} entry with the same GRP file
     */
    public int getImageGraphicsEntry(int imageTypeId) {
        int graphicsFile = this.imageTypes.getGraphicsFile(imageTypeId);

        for (int entryIndex = 0; entryIndex < imageTypeId; entryIndex++) {
            if (this.imageTypes.getGraphicsFile(entryIndex) == graphicsFile) {
                return entryIndex;
            }
        }

        return imageTypeId;
    }

    /**
     * Reads an installed file whose bytes a game structure keeps unchanged, as the EUD emulation's copies of the
     * wireframe GRP files or the trigger records of a victory condition ({@link VictoryCondition#getTriggerFile}).
     *
     * @param path the storage path
     * @return the file bytes
     * @throws IOException if the file cannot be read
     */
    public byte @NotNull [] readInstalledFile(@NotNull String path) throws IOException {
        return this.storage.readFile(path);
    }

    /**
     * Returns the player color remapping tables the game keeps per player in the game context: the eight
     * {@code game/tunit.pcx} indices of each of its sixteen colors and the {@code game/tminimap.pcx} index of each.
     *
     * @return {@code [color][0..7]} unit remapping indices, then {@code [color][8]} the minimap index
     * @throws IOException if either table cannot be read
     */
    public synchronized int @NotNull [] @NotNull [] getPlayerColorTables() throws IOException {
        if (this.playerColorTables == null) {
            PcxIndexedImage unitImage = PcxIndexedImage.decode(this.storage.readFile("game/tunit.pcx"));
            PcxIndexedImage minimapImage = PcxIndexedImage.decode(this.storage.readFile("game/tminimap.pcx"));
            int colorCount = minimapImage.getWidth();
            int[][] tables = new int[colorCount][PlayerColorRows.UNIT_ROW + 1];

            for (int color = 0; color < colorCount; color++) {
                for (int rowIndex = 0; rowIndex < PlayerColorRows.UNIT_ROW; rowIndex++) {
                    tables[color][rowIndex] = unitImage.getPixel(color * PlayerColorRows.UNIT_ROW + rowIndex, 0);
                }

                tables[color][PlayerColorRows.UNIT_ROW] = minimapImage.getPixel(color, 0);
            }

            this.playerColorTables = tables;
        }

        return this.playerColorTables;
    }

    /**
     * Reads the installed WPE bytes without renderer alpha replacement or player remapping.
     *
     * @param tileset the classic tileset index
     * @return the {@code 1024} original palette bytes
     * @throws IOException if the tileset or palette extent is invalid
     */
    public byte @NotNull [] loadTerrainPalette(int tileset) throws IOException {
        if (tileset < 0 || tileset >= TILESET_NAMES.length) {
            throw Exceptions.newIOException("Unsupported terrain palette index: {0}", tileset);
        }

        byte[] palette = this.storage.readFile("tileset/" + TILESET_NAMES[tileset] + ".wpe");

        if (palette.length != TERRAIN_PALETTE_SIZE) {
            throw Exceptions.newIOException("The terrain palette must hold {0} bytes, not {1}.", TERRAIN_PALETTE_SIZE, palette.length);
        }

        return palette;
    }

    /**
     * Loads and caches the classic terrain flag tables for a map tileset.
     *
     * @param tileset the tileset index from {@code 0} through {@code 7}
     * @return the shared terrain tables
     * @throws IOException if the tileset is unsupported or its files cannot be read
     */
    public @NotNull TerrainTileset getTerrainTileset(int tileset) throws IOException {
        if (tileset < 0 || tileset >= this.terrainTilesets.length) {
            throw Exceptions.newIOException("Unsupported terrain tileset index: {0}", tileset);
        }

        TerrainTileset terrain = this.terrainTilesets[tileset];

        if (terrain == null) {
            terrain                       = TerrainTileset.load(this.storage, tileset);
            this.terrainTilesets[tileset] = terrain;
        }

        return terrain;
    }

    /**
     * Creates a {@link Foliage} instance used before map-unit loading and grid population.
     * <p>
     * Loads the {@code 60}-byte foliage definitions and the selected tileset distribution, requiring matching definition counts.
     * Rendering resources and the later procedural grid are outside this context.
     *
     * @param mapWidth the original unsigned WORD map width
     * @param tileset  the map tileset index
     * @return the created {@link Foliage} instance, enabled, with the earlier cleared grid
     * @throws IOException if the required files or their extents are unsupported
     */
    public @NotNull Foliage createFoliage(int mapWidth, int tileset) throws IOException {
        if (tileset < 0 || tileset >= TILESET_NAMES.length) {
            throw Exceptions.newIOException("Unsupported foliage tileset index: {0}", tileset);
        }

        if (this.foliageDefinitions == null) {
            byte[] definitions = this.storage.readFile("tileset/foliage.bin");

            if (definitions.length < FoliageDefinition.RECORD_SIZE || definitions.length % FoliageDefinition.RECORD_SIZE != 0) {
                throw Exceptions.newIOException("Unsupported foliage definition extent.");
            }

            this.foliageDefinitions = definitions;
        }

        if (this.foliageAnimation == null) {
            this.foliageAnimation = this.storage.readFile("tileset/foliage.anim");
        }

        if (this.foliageDistributions[tileset] == null) {
            byte[] distribution = this.storage.readFile("tileset/" + TILESET_NAMES[tileset] + ".fol");
            byte[] terrainGroups = this.storage.readFile("tileset/" + TILESET_NAMES[tileset] + ".cv5");

            this.foliageDistributions[tileset] = distribution;
            this.foliageTerrainGroups[tileset] = terrainGroups;
        }

        Foliage foliage = new Foliage(mapWidth, this.foliageDefinitions);

        try {
            foliage.loadPopulationData(this.foliageDistributions[tileset], this.foliageAnimation, this.foliageTerrainGroups[tileset]);
        } catch (IllegalArgumentException | UnsupportedOperationException exception) {
            throw Exceptions.newIOException(exception, "Unsupported native foliage population data.");
        }

        return foliage;
    }

    /**
     * Returns the GRP path referenced by the given {@code images.dat} entry.
     *
     * @param imageTypeId the {@code images.dat} index
     * @return the CASC path, including the {@code unit/} root
     * @throws IOException if the image string index is invalid or does not reference a GRP
     */
    public @NotNull String getGrpPath(int imageTypeId) throws IOException {
        int stringIndex = this.imageTypes.getGraphicsFile(imageTypeId) - 1;

        if (stringIndex < 0 || stringIndex >= this.imagesTbl.getStringCount()) {
            throw Exceptions.newIOException("The images.tbl index is out of bounds for image {0}: {1}", imageTypeId, stringIndex);
        }

        String relativePath = this.imagesTbl.getString(stringIndex).replace('\\', '/');

        if (!relativePath.toLowerCase(Locale.ROOT).endsWith(".grp")) {
            throw Exceptions.newIOException("The image does not reference a GRP file: {0}", relativePath);
        }

        return "unit/" + relativePath;
    }

    /**
     * Loads and caches the GRP referenced by the given {@code images.dat} entry. An added image ({@link #getAddedGraphics()}) reads the SD frames
     * of its mod files, or the installed GRP of its base image when the mod gives none.
     *
     * @param imageTypeId the {@code images.dat} index or added image id
     * @return the parsed GRP file
     * @throws IOException if the path cannot be resolved or the GRP cannot be decoded
     */
    public @NotNull GrpFile getGrpFile(int imageTypeId) throws IOException {
        GrpFile grpFile = this.grpCache.get(imageTypeId);

        if (grpFile != null) {
            return grpFile;
        }

        if (this.addedGraphics.containsImage(imageTypeId)) {
            ScrAddedImage added = this.addedGraphics.getImage(imageTypeId);

            grpFile = added.getGraphics() == null ? this.getGrpFile(added.getBaseImage()) : added.getGraphics().loadFrames();
        } else {
            grpFile = GrpFile.parse(this.storage.readFile(this.getGrpPath(imageTypeId)));
        }

        this.grpCache.put(imageTypeId, grpFile);

        return grpFile;
    }

    /**
     * Sets a damage-overlay byte of this game, as an EUD write to its damage-overlay table does.
     *
     * @param imageTypeId the {@code images.dat} index
     * @param value       the byte, kept in its low eight bits
     */
    public void setImageDamageOverlayCount(int imageTypeId, int value) {
        this.imageDamageOverlayCounts[imageTypeId] = value & BYTE_MASK;
    }

    /**
     * Loads and caches the damage-overlay byte of an image.
     * <p>
     * The byte counts the first {@code 22} coordinate pairs of the first damage-overlay frame that differ from
     * {@code (127, 127)}, then stores twice that count. A zero {@code images.dat} reference represents an absent resource and
     * yields zero.
     *
     * @param imageTypeId the {@code images.dat} index
     * @return twice the number of present damage-overlay positions, from {@code 0} through {@code 44}
     * @throws IOException if the referenced table string or coordinate data is invalid
     */
    public int getImageDamageOverlayCount(int imageTypeId) throws IOException {
        if (this.addedGraphics.containsImage(imageTypeId)) {
            return this.getImageDamageOverlayCount(this.addedGraphics.getOverlayImage(imageTypeId));
        }

        int count = this.imageDamageOverlayCounts[imageTypeId];

        if (count >= 0) {
            return count;
        }

        if (this.imageDamageOverlayBase != null) {
            return this.imageDamageOverlayBase.getImageDamageOverlayCount(imageTypeId);
        }

        int resourceIndex = this.imageTypes.getDamageOverlay(imageTypeId);

        count = 0;

        if (resourceIndex != 0) {
            int stringIndex = resourceIndex - 1;

            if (stringIndex < 0 || stringIndex >= this.imagesTbl.getStringCount()) {
                throw Exceptions.newIOException("Invalid damage-overlay string index for image {0}: {1}", imageTypeId, stringIndex);
            }

            String path = "unit/" + this.imagesTbl.getString(stringIndex).replace('\\', '/');
            byte[] contents = this.storage.readFile(path);

            if (contents.length < OVERLAY_HEADER_SIZE + OVERLAY_FRAME_OFFSET_SIZE) {
                throw Exceptions.newIOException("Damage-overlay data lacks its first frame offset: {0}", path);
            }

            long frameOffset = Integer.toUnsignedLong(ByteBuffer.wrap(contents).order(ByteOrder.LITTLE_ENDIAN).getInt(OVERLAY_HEADER_SIZE));

            if (frameOffset + DAMAGE_OVERLAY_POSITIONS * OVERLAY_POSITION_SIZE > contents.length) {
                throw Exceptions.newIOException("Damage-overlay data lacks 22 first-frame positions: {0}", path);
            }

            for (int positionIndex = 0; positionIndex < DAMAGE_OVERLAY_POSITIONS; positionIndex++) {
                int offset = (int) frameOffset + positionIndex * OVERLAY_POSITION_SIZE;

                if (contents[offset] != ABSENT_OVERLAY_COORDINATE || contents[offset + 1] != ABSENT_OVERLAY_COORDINATE) {
                    count += DAMAGE_OVERLAY_COUNT_PER_POSITION;
                }
            }
        }

        this.imageDamageOverlayCounts[imageTypeId] = count;

        return count;
    }

    /**
     * Returns a race sound preload list of the sound initialization ({@link SoundPreloadListUtils}), built on first use.
     *
     * @param race the race, Zerg {@code 0}, Terran {@code 1} or Protoss {@code 2}
     * @return the little-endian WORD bytes of the list, shared; callers copy them before writing
     * @throws IOException if {@code rez/sfx.json} cannot be read
     */
    public byte @NotNull [] getSoundPreloadList(int race) throws IOException {
        synchronized (this.soundPreloadLists) {
            if (this.soundPreloadLists[0] == null) {
                this.soundPreloadLists[0] = SoundPreloadListUtils.build(this.storage.readFile("rez/sfx.json"));
            }

            return this.soundPreloadLists[0][race];
        }
    }

    /**
     * Reads an original sound file selected by {@code sfxdata.dat} and {@code sfxdata.tbl}.
     *
     * @param soundId the sound table identifier
     * @return the actual WAV resource bytes
     * @throws IOException if the table entry or resource is unavailable
     */
    public byte @NotNull [] readSound(int soundId) throws IOException {
        if (soundId < 0 || soundId >= SfxdataDat.SOUND_COUNT) {
            throw Exceptions.newIOException("Invalid sound identifier: {0}", soundId);
        }

        if (this.soundNames == null) {
            this.soundNames = TblFile.parse(this.storage.readFile("arr/sfxdata.tbl"));
        }

        int stringIndex = this.soundTypes.getFile(soundId) - 1;

        if (stringIndex < 0 || stringIndex >= this.soundNames.getStringCount()) {
            throw Exceptions.newIOException("Invalid sound filename index: {0}", stringIndex);
        }

        if (this.soundPaths != null) {
            return this.soundPaths.read(this.soundNames.getString(stringIndex));
        }

        return this.storage.readFile("sound/" + this.soundNames.getString(stringIndex).replace('\\', '/'));
    }

    /**
     * Reads one signed coordinate pair from the cached Special Overlay resource.
     * <p>
     * Horizontal image mirroring is applied by the caller.
     *
     * @param imageTypeId  the {@code images.dat} index
     * @param frameIndex   the actual image frame index
     * @param overlayIndex the coordinate pair within that frame
     * @return the horizontal signed WORD in the low half and vertical signed WORD in the high half
     * @throws IOException if the image has no Special Overlay resource or the read lies past the resource
     */
    public int getImageSpecialOverlayOffset(int imageTypeId, int frameIndex, int overlayIndex) throws IOException {
        return this.getImageOverlayOffset(imageTypeId, ScrImageTypeView.OVERLAY_SPECIAL, frameIndex, overlayIndex);
    }

    /**
     * Reads one signed coordinate pair from an image overlay resource.
     * <p>
     * The frame offset DWORD and the coordinate pair are read wherever they lie in the resource: neither the frame count nor the position count of
     * the resource bounds the frame or the position.
     *
     * @param imageTypeId  the {@code images.dat} index
     * @param overlayType  the original overlay table index from attack {@code 0} through shield {@code 5}
     * @param frameIndex   the actual image frame index
     * @param overlayIndex the coordinate pair within that frame
     * @return the horizontal signed WORD in the low half and vertical signed WORD in the high half
     * @throws IllegalArgumentException if the overlay table index is unsupported
     * @throws IOException              if the image has no resource for the overlay table or the read lies past the resource
     */
    public int getImageOverlayOffset(int imageTypeId, int overlayType, int frameIndex, int overlayIndex) throws IOException {
        if (overlayType < ScrImageTypeView.OVERLAY_ATTACK || overlayType > ScrImageTypeView.OVERLAY_SHIELD) {
            throw Exceptions.newIllegalArgumentException("Unsupported overlay table: {0}", overlayType);
        }

        if (this.addedGraphics.containsImage(imageTypeId)) {
            ScrAddedImage added = this.addedGraphics.getImage(imageTypeId);

            if (added.hasOverlayOffsets(overlayType)) {
                try {
                    return added.getOverlayOffset(overlayType, frameIndex, overlayIndex);
                } catch (IllegalArgumentException exception) {
                    throw Exceptions.newIOException(exception, "Added image {0} has no overlay position {1} of frame {2}.",
                                                    imageTypeId, overlayIndex, frameIndex);
                }
            }

            return this.getImageOverlayOffset(added.getBaseImage(), overlayType, frameIndex, overlayIndex);
        }

        int resourceIndex = imageTypeId * OVERLAY_TABLE_COUNT + overlayType;
        byte[] contents = this.imageOverlayResources[resourceIndex];

        if (contents == null) {
            int stringIndex = this.imageTypes.getOverlay(imageTypeId, overlayType) - 1;

            if (stringIndex < 0) {
                throw Exceptions.newIOException("Image {0} has no overlay table {1}.", imageTypeId, overlayType);
            }

            if (stringIndex >= this.imagesTbl.getStringCount()) {
                throw Exceptions.newIOException("Invalid image-overlay string index for image {0}: {1}", imageTypeId, stringIndex);
            }

            contents                                  = this.storage.readFile("unit/" + this.imagesTbl.getString(stringIndex).replace('\\', '/'));
            this.imageOverlayResources[resourceIndex] = contents;
        }

        long frameOffsetPosition = OVERLAY_HEADER_SIZE + (long) frameIndex * OVERLAY_FRAME_OFFSET_SIZE;

        if (frameIndex < 0 || frameOffsetPosition + OVERLAY_FRAME_OFFSET_SIZE > contents.length) {
            throw Exceptions.newIOException("Image-overlay read of image {0}, frame {1} lies past the resource.", imageTypeId, frameIndex);
        }

        int frameOffset = (int) LITTLE_ENDIAN_DWORD.get(contents, (int) frameOffsetPosition);
        long position = Integer.toUnsignedLong(frameOffset + overlayIndex * OVERLAY_POSITION_SIZE);

        if (position + OVERLAY_POSITION_SIZE > contents.length) {
            throw Exceptions.newIOException("Image-overlay read of image {0}, frame {1}, position {2} lies past the resource.",
                                            imageTypeId, frameIndex, overlayIndex);
        }

        return (contents[(int) position] & WORD_MASK) | contents[(int) position + 1] << Short.SIZE;
    }

    /**
     * Loads the audited SD observer overlay subscriber-node metadata.
     *
     * @return the parsed node before dialog callbacks
     * @throws IOException if the resource cannot be read
     */
    public @NotNull ObserverOverlay loadObserverOverlay() throws IOException {
        return ObserverOverlay.parse(this.storage.readFile("SD/rez/BroadcastObserver.ui.json"));
    }

    /**
     * Loads the audited default SD status-button panel definition.
     *
     * @return the complete verified JSON resource, before node construction
     * @throws IOException                   if the resource cannot be read
     * @throws UnsupportedOperationException if the installed resource is unaudited
     */
    public byte @NotNull [] loadStatusButtonDefinition() throws IOException {
        return AuditedResourceUtils.require(this.storage.readFile("SD/rez/statbtndefault.ui.json"),
                                            "45d884ca0a1c62c563c9a8ae9baff532508a1237189467ae9deceead451dc855",
                                            "Unaudited status-button definition");
    }

    /**
     * Loads the SD status-button panel of replay playback, whose resource name has the {@code n} suffix.
     *
     * @return the complete verified JSON resource, before node construction
     * @throws IOException                   if the resource cannot be read
     * @throws UnsupportedOperationException if the installed resource is unaudited
     */
    public byte @NotNull [] loadReplayStatusButtonDefinition() throws IOException {
        return AuditedResourceUtils.require(this.storage.readFile("SD/rez/statbtnn.ui.json"),
                                            "8df31d833042d1bbd10570c034a23526e1e9bc570522698f7c1823bd1d0b8568",
                                            "Unaudited replay status-button definition");
    }

    /**
     * Loads the installed SD replay progress textures for all three console races.
     * <p>
     * Original resource identifiers {@code 23} and {@code 24} resolve to the full and empty bars. The installed DDS payloads replace the
     * original PCX names. Loading the complete bundle does not select a console race or execute the host progress painter.
     *
     * @return full and empty DDS payloads in Zerg, Terran and Protoss order
     * @throws IOException                   if an installed texture cannot be read
     * @throws UnsupportedOperationException if a texture differs from the audited SD asset
     */
    public byte @NotNull [] @NotNull [] loadReplayProgressResources() throws IOException {
        String[] consoleRaces = {"zerg", "terran", "protoss"};
        String[] resourceNames = {"pbrfull", "pbrempt"};
        String[] resourceDigests = {
                "6d7f3464429d7744bb88475d65476dd0ee2dbad855d97b55e76c0a96953ab92c",
                "c09418f1815c57116152007c7ed22fccb2bbfdf9b0e868d321a8dd4dcbbecbe2"
        };
        byte[][] resources = new byte[consoleRaces.length * resourceNames.length][];

        for (int raceIndex = 0; raceIndex < consoleRaces.length; raceIndex++) {
            for (int resourceIndex = 0; resourceIndex < resourceNames.length; resourceIndex++) {
                String path = "SD/game/consoles/" + consoleRaces[raceIndex] + "/" + resourceNames[resourceIndex] + ".DDS";
                byte[] contents = this.storage.readFile(path);
                String actualDigest = AuditedResourceUtils.computeSha256(contents);

                if (!actualDigest.equals(resourceDigests[resourceIndex])) {
                    throw Exceptions.newUnsupportedOperationException("Unaudited replay progress texture: {0}, {1}", path, actualDigest);
                }

                resources[raceIndex * resourceNames.length + resourceIndex] = contents;
            }
        }

        return resources;
    }

    /**
     * Loads the audited SD statistics panel definition.
     *
     * @return the complete verified JSON resource
     * @throws IOException if the resource cannot be read
     */
    public byte @NotNull [] loadStatisticsPanelDefinition() throws IOException {
        return AuditedResourceUtils.require(this.storage.readFile("SD/rez/statdata.ui.json"),
                                            "91ab528ba760c255e970fe7b2e04c1ca311883ea6ee45c1f552492b7b9d93d09",
                                            "Unaudited statistics-panel definition");
    }

    /**
     * Reads one of the two installed computer strategy bytecode resources.
     *
     * @param expansionScripts {@code true} to read {@code bwscript.bin} instead of {@code aiscript.bin}
     * @return the complete original resource
     * @throws IOException if the installed resource cannot be read
     */
    public byte @NotNull [] readComputerScripts(boolean expansionScripts) throws IOException {
        return this.storage.readFile(expansionScripts ? "scripts/bwscript.bin" : "scripts/aiscript.bin");
    }

    /**
     * Loads the twelve replay label values from the audited English resources.
     * <p>
     * Numeric identifiers use the one-based JSON lookup of the string table; named values come from the English
     * {@code gluAll.json} dictionary. This bounded projection rejects different resource versions and does not infer the
     * original client's selected locale.
     *
     * @return the byte strings for {@code 609}, {@code scoreTIME_s}, {@code 60C}, {@code gameSPEED}, seven speed names and {@code 60A}
     * @throws IOException                   if either installed resource cannot be read
     * @throws UnsupportedOperationException if a resource differs from the audited English asset
     */
    public byte @NotNull [] @NotNull [] loadReplayLabelsLocalization() throws IOException {
        byte[] indexedContents = this.storage.readFile("locales/enUS/Assets/rez/stat_txt.json");
        byte[] namedContents = this.storage.readFile("locales/enUS/Assets/rez/gluAll.json");
        String indexedDigest = AuditedResourceUtils.computeSha256(indexedContents);
        String namedDigest = AuditedResourceUtils.computeSha256(namedContents);

        if (!indexedDigest.equals("94dd1c99158b2c11cb20758e7c09fa925a1ab936165b9517acdc8ff8d6e41713") ||
            !namedDigest.equals("c7360bd51ec77fd297e2cc31d6434f731963ad6940adac70ed859323ce226d1f")) {
            throw Exceptions.newUnsupportedOperationException("Unaudited English replay localization: {0}, {1}", indexedDigest, namedDigest);
        }

        String[] values = {"Replay Progress", "Elapsed Time: %s", "Seeking To: %s", "Speed:",
                           "Slowest", "Slower", "Slow", "Normal", "Fast", "Faster", "Fastest", "Paused"};
        byte[][] localizedText = new byte[values.length][];

        for (int index = 0; index < values.length; index++) {
            localizedText[index] = values[index].getBytes(StandardCharsets.US_ASCII);
        }

        return localizedText;
    }

    /**
     * Loads the four initial replay label texts from the audited English RC3 dictionary. These are the resource values
     * before the first replay label update.
     *
     * @return the texts for label identifiers {@code -45}, {@code -46}, {@code -47} and {@code -48}
     * @throws IOException                   if the installed dictionary cannot be read
     * @throws UnsupportedOperationException if the dictionary differs from the audited English asset
     */
    public byte @NotNull [] @NotNull [] loadReplayLabelInitialText() throws IOException {
        AuditedResourceUtils.require(this.storage.readFile("locales/enUS/Assets/rez/RC3.json"),
                                     "e640520a392c6c817d9258220b990dc7ff8cfd0c69424f912be1962a8f3d07f1",
                                     "Unaudited English replay initial text");

        byte[][] initialText = new byte[INITIAL_LABEL_COUNT][];

        for (int index = 0; index < initialText.length; index++) {
            initialText[index] = "<<PLACEHOLDER>>".getBytes(StandardCharsets.US_ASCII);
        }

        return initialText;
    }

    /**
     * Closes this resource, relinquishing any underlying resources.
     * <p>
     * Clears the shared GRP cache and closes the owned CASC storage.
     */
    @Override
    public void close() {
        this.grpCache.clear();
        this.storage.close();
    }
}
