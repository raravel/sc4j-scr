package org.fogofwarlabs.sc4j.scr.render;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Palette;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteFrame;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TileGraphics;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedGraphics;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.dat.*;
import org.fogofwarlabs.sc4j.scr.data.format.image.AnimFile;
import org.fogofwarlabs.sc4j.scr.data.format.image.DdsFile;
import org.fogofwarlabs.sc4j.scr.data.format.image.ImageRelations;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceKeys;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.data.resource.importer.GrpSheetUtils;
import org.fogofwarlabs.sc4j.scr.data.type.ScrFlingyTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrImageTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrSpriteTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads the installed classic graphics assets for presentation without changing simulation state.
 * <p>
 * The pictures are engine model objects of the {@link ScrResourceProvider}: the image and creep-edge sprite sheets, the
 * tileset's megatile graphics and palette and the remapping and lighting pictures. The {@code .cv5} groups (which megatile a
 * map tile shows), the {@code images.dat} draw functions, {@code images.rel} and the baked remap coverage of
 * {@code SD/mainSD.anim} are read from the provider's storage. Loaded images and lighting tables are kept.
 */
public final class RenderAssets {
    /**
     * The tileset lighting table names, followed by {@code dark.pcx}.
     */
    static final @NotNull String @NotNull [] LIGHTING_TABLE_NAMES = {"ofire", "gfire", "bfire", "bexpl", "trans50", "red", "green", "dark"};
    /**
     * The tileset file names by tileset index.
     */
    private static final @NotNull String @NotNull [] TILESET_NAMES = {
            "badlands", "platform", "install", "ashworld",
            "jungle", "desert", "ice", "twilight"
    };

    /**
     * The entries of a palette and the columns of a lighting table.
     */
    public static final int PALETTE_ENTRIES = 256;
    /**
     * The bytes of a palette entry (red, green, blue, fourth byte).
     */
    public static final int PALETTE_ENTRY_SIZE = 4;
    /**
     * The position of the green byte in a palette entry.
     */
    public static final int PALETTE_GREEN = 1;
    /**
     * The position of the blue byte in a palette entry.
     */
    public static final int PALETTE_BLUE = 2;
    /**
     * The position of the fourth byte in a palette entry.
     */
    static final int PALETTE_FOURTH = 3;
    /**
     * The bytes of a palette row.
     */
    static final int PALETTE_BYTES = PALETTE_ENTRIES * PALETTE_ENTRY_SIZE;
    /**
     * The shifts of the red and green bytes in a packed {@code 0xAARRGGBB} palette color.
     */
    private static final int PALETTE_RED_SHIFT = 16;
    private static final int PALETTE_GREEN_SHIFT = 8;

    /**
     * The player remapping rows of {@code game/tunit.pcx}, one per player color.
     */
    public static final int REMAP_ROWS = 16;
    /**
     * The remapped indices per player color row: the eight {@code tunit.pcx} palette indices of a player.
     */
    public static final int REMAP_INDEX_COUNT = 8;
    /**
     * The width of {@code game/tunit.pcx}: the remapped indices of every remapping row.
     */
    public static final int REMAP_COLORS = REMAP_ROWS * REMAP_INDEX_COUNT;
    /**
     * The palette rows of {@link #copyPalette}: the screen palette followed by the remapping rows.
     */
    static final int PALETTE_ROWS = 1 + REMAP_ROWS;
    /**
     * The first remapped palette index (indices {@code 8..15} are remapped).
     */
    static final int REMAP_FIRST_INDEX = 8;
    /**
     * The height of {@code game/tunit.pcx}: one row of remapped indices.
     */
    private static final int REMAP_IMAGE_HEIGHT = 1;

    /**
     * The largest tile identifier: a tile identifier is a WORD.
     */
    private static final int MAXIMUM_TILE_IDENTIFIER = 0xFFFF;
    /**
     * The mini-tiles per tile row.
     */
    private static final int MINI_TILES_PER_ROW = TerrainTileset.TILE_SIZE / TerrainTileset.MINI_TILE_SIZE;
    /**
     * The mini-tiles per tile.
     */
    public static final int MINI_TILES_PER_TILE = MINI_TILES_PER_ROW * MINI_TILES_PER_ROW;
    /**
     * The size of a VX4EX record: one DWORD mini-tile reference per mini-tile.
     */
    public static final int VX4EX_RECORD_SIZE = MINI_TILES_PER_TILE * Integer.BYTES;
    /**
     * The size of a VR4 record: the palette indices of one mini-tile.
     */
    public static final int VR4_RECORD_SIZE = TerrainTileset.MINI_TILE_SIZE * TerrainTileset.MINI_TILE_SIZE;
    /**
     * The packed frame map offset layout: the horizontal offset in the low {@code 16} bits, the vertical offset above it.
     */
    private static final int FRAME_OFFSET_HORIZONTAL_MASK = 0xFFFF;
    private static final int FRAME_OFFSET_VERTICAL_SHIFT = 16;
    /**
     * The divisor that halves a length, such as a width or height to the offset of its center.
     */
    public static final int HALF_DIVISOR = 2;
    /**
     * The {@code SD/mainSD.anim} layer of the diffuse texture.
     */
    static final int DIFFUSE_LAYER = 0;
    /**
     * The bytes of a warp texture mask pixel: the palette index followed by the coverage byte.
     */
    private static final int WARP_PIXEL_BYTES = 2;

    /**
     * Creates a {@link RenderAssets} instance using installed classic assets.
     *
     * @param resources the resource provider of the installation
     * @param tileset   the map tileset index from {@code 0} through {@code 7}
     * @return the created {@link RenderAssets} instance
     * @throws IOException if an asset is missing or malformed
     */
    public static @NotNull RenderAssets load(@NotNull ScrResourceProvider resources, int tileset) throws IOException {
        if (tileset < 0 || tileset >= TILESET_NAMES.length) {
            throw Exceptions.newIOException("Unsupported tileset index: {0}", tileset);
        }

        String prefix = "tileset/" + TILESET_NAMES[tileset];

        return new RenderAssets(resources, prefix,
                                resources.getStorage().readFile(prefix + ".cv5"), resources.loadTileGraphics(ScrResourceKeys.ofTileset(TILESET_NAMES[tileset])),
                                resources.loadPalette(ScrResourceKeys.ofPalette(prefix + ".wpe")),
                                resources.loadSpriteSheet(ScrResourceKeys.ofPcx("game/tunit.pcx")),
                                resources.loadSpriteSheet(ScrResourceKeys.ofGrp(prefix + ".grp")));
    }

    /**
     * Finds the megatile a terrain tile shows in the CV5 records, excluding bit {@code 15} from the group reference.
     *
     * @param groups         the CV5 records
     * @param tileIdentifier the value from the {@code MTXM} section
     * @return the megatile index
     * @throws IOException if the tile group is outside the given records
     */
    static int findMegatileIndex(byte @NotNull [] groups, int tileIdentifier) throws IOException {
        int groupOffset = TerrainTileset.getGroupIndex(tileIdentifier) * TerrainTileset.CV5_RECORD_SIZE;

        if (tileIdentifier < 0 || tileIdentifier > MAXIMUM_TILE_IDENTIFIER || groupOffset + (long) TerrainTileset.CV5_RECORD_SIZE > groups.length) {
            throw Exceptions.newIOException("Tile group is out of bounds: {0}", tileIdentifier);
        }

        return LittleEndianUtils.readUnsigned16(groups, TerrainTileset.getMegatileOffset(tileIdentifier));
    }

    /**
     * Decodes a terrain tile from the CV5 records and the megatile graphics into {@code 32x32} palette indices, excluding bit {@code 15} from the
     * group reference.
     *
     * @param groups         the CV5 records
     * @param tiles          the megatile graphics
     * @param tileIdentifier the value from the {@code MTXM} section
     * @return the row-major pixels
     * @throws IOException if the tile group or the megatile is outside the given records
     */
    static byte @NotNull [] decodeTile(byte @NotNull [] groups, @NotNull TileGraphics tiles, int tileIdentifier) throws IOException {
        int tileIndex = findMegatileIndex(groups, tileIdentifier);

        if (tileIndex >= tiles.getTileCount()) {
            throw Exceptions.newIOException("Megatile is out of bounds: {0}", tileIndex);
        }

        byte[] result = new byte[TerrainTileset.TILE_SIZE * TerrainTileset.TILE_SIZE];

        tiles.getPixels().copyBytes(tiles.getTileOffset(tileIndex), result, 0, result.length);

        return result;
    }

    /**
     * Creates the packed RGBA palette rows: the screen palette, then one row per remapping row of {@code game/tunit.pcx} whose palette indices
     * {@code 8..15} take the colors of the remapped indices.
     *
     * @param palette      the tileset {@code .wpe} palette
     * @param playerColors the {@code game/tunit.pcx} picture
     * @return the {@code 17} palette rows of four bytes per entry, alpha {@code 255}
     * @throws IOException if the palette does not have {@code 256} colors or the picture is not {@code 128} by {@code 1}
     */
    static byte @NotNull [] createPalette(@NotNull Palette palette, @NotNull SpriteSheet playerColors) throws IOException {
        if (palette.getSize() != PALETTE_ENTRIES) {
            throw Exceptions.newIOException("The WPE palette must have 256 four-byte entries.");
        }

        if (playerColors.getWidth() != REMAP_COLORS || playerColors.getHeight() != REMAP_IMAGE_HEIGHT) {
            throw Exceptions.newIOException("The player color PCX must be an 8-bit, one-plane 128 by 1 image.");
        }

        byte[] remapping = copyIndices(playerColors, REMAP_COLORS, REMAP_IMAGE_HEIGHT);
        byte[] result = new byte[PALETTE_ROWS * PALETTE_BYTES];

        for (int row = 0; row < PALETTE_ROWS; row++) {
            for (int colorIndex = 0; colorIndex < PALETTE_ENTRIES; colorIndex++) {
                int color = palette.getColor(findPaletteSourceIndex(remapping, row, colorIndex));
                int destinationOffset = row * PALETTE_BYTES + colorIndex * PALETTE_ENTRY_SIZE;

                result[destinationOffset]                  = (byte) (color >>> PALETTE_RED_SHIFT);
                result[destinationOffset + PALETTE_GREEN]  = (byte) (color >>> PALETTE_GREEN_SHIFT);
                result[destinationOffset + PALETTE_BLUE]   = (byte) color;
                result[destinationOffset + PALETTE_FOURTH] = OverlayPass.OPAQUE_ALPHA;
            }
        }

        return result;
    }

    /**
     * Finds the palette index that an entry of a palette row shows: the rows after the screen palette take the colors of their remapped indices at
     * palette indices {@code 8..15}.
     *
     * @param remapping the remapped indices of every remapping row, {@link #REMAP_INDEX_COUNT} per row
     * @param row       the palette row, {@code 0} for the screen palette
     * @param index     the palette index of the entry
     * @return the palette index whose color the entry shows
     */
    private static int findPaletteSourceIndex(byte @NotNull [] remapping, int row, int index) {
        return row > 0 && index >= REMAP_FIRST_INDEX && index < REMAP_FIRST_INDEX + REMAP_INDEX_COUNT
               ? Byte.toUnsignedInt(remapping[(row - 1) * REMAP_INDEX_COUNT + index - REMAP_FIRST_INDEX])
               : index;
    }

    /**
     * Copies the palette indices of a one-frame picture of known size, such as an indexed PCX table.
     *
     * @param picture the picture
     * @param width   the expected width in pixels
     * @param height  the expected height in pixels
     * @return the row-major palette indices
     * @throws IOException if the picture has other dimensions
     */
    public static byte @NotNull [] copyIndices(@NotNull SpriteSheet picture, int width, int height) throws IOException {
        if (picture.getWidth() != width || picture.getHeight() != height || picture.getFrameCount() != 1) {
            throw Exceptions.newIOException("Unexpected indexed PCX dimensions or encoding.");
        }

        byte[] result = new byte[width * height];

        picture.getColor().copyBytes(picture.getFrame(0).getPixelOffset(), result, 0, result.length);

        return result;
    }

    private final @NotNull ScrResourceProvider resources;
    private final @NotNull CascStorage storage;
    private final @NotNull String tilesetPrefix;

    private final byte @NotNull [] groups;
    private final @NotNull TileGraphics tiles;

    private final byte @NotNull [] paletteIndices;
    private final byte @NotNull [] palette;
    private final byte @NotNull [] @Nullable [] lightingTables;

    private final @NotNull SpriteSheet creepEdges;

    private final @NotNull ScrImageTypeView images;
    private final int @NotNull [] unitImages;
    private final @Nullable SpriteSheet @NotNull [] imageSheets;

    /**
     * Decoded {@code mainSD} diffuse atlases by entry.
     */
    private final @NotNull Map<Integer, byte[]> sdAtlases;

    /**
     * {@code SD/mainSD.anim}, read by the first image whose SD texture is needed.
     */
    private @Nullable AnimFile sdAnimations;
    /**
     * {@code images.rel}, read by the first shadow that needs its relation type.
     */
    private @Nullable ImageRelations imageRelations;

    private RenderAssets(@NotNull ScrResourceProvider resources, @NotNull String tilesetPrefix,
                         byte @NotNull [] groups, @NotNull TileGraphics tiles,
                         @NotNull Palette palette, @NotNull SpriteSheet playerColors,
                         @NotNull SpriteSheet creepEdges) throws IOException {
        if (groups.length % TerrainTileset.CV5_RECORD_SIZE != 0) {
            throw Exceptions.newIOException("Invalid CV5, VX4EX, or VR4 record size.");
        }

        byte[] remappedPalette = createPalette(palette, playerColors);
        byte[] colors = copyIndices(playerColors, REMAP_COLORS, REMAP_IMAGE_HEIGHT);
        CascStorage storage = resources.getStorage();

        this.resources     = resources;
        this.storage       = storage;
        this.tilesetPrefix = tilesetPrefix;

        this.groups = groups;
        this.tiles  = tiles;

        this.paletteIndices = new byte[PALETTE_ROWS * PALETTE_ENTRIES];
        this.palette        = remappedPalette;
        this.lightingTables = new byte[LIGHTING_TABLE_NAMES.length][];

        this.creepEdges = creepEdges;

        this.images      = ImagesDat.createTypeTable(storage.readParsed("arr/images.dat", DatTable.class, ImagesDat.createSchema()::parse));
        this.unitImages  = new int[UnitsDat.UNIT_COUNT];
        this.imageSheets = new SpriteSheet[ScrAddedGraphics.LAST_ID + 1];

        this.sdAtlases = new HashMap<>();

        this.sdAnimations   = null;
        this.imageRelations = null;

        for (int row = 0; row < PALETTE_ROWS; row++) {
            for (int index = 0; index < PALETTE_ENTRIES; index++) {
                this.paletteIndices[row * PALETTE_ENTRIES + index] = (byte) findPaletteSourceIndex(colors, row, index);
            }
        }

        ScrUnitTypeView units = UnitsDat.createTypeTable(UnitsDat.createSchema().parse(storage.readFile("arr/units.dat")));
        ScrFlingyTypeView movement = FlingyDat.createTypeTable(storage.readParsed("arr/flingy.dat", DatTable.class, FlingyDat.createSchema()::parse));
        ScrSpriteTypeView sprites = SpritesDat.createTypeTable(storage.readParsed("arr/sprites.dat", DatTable.class, SpritesDat.createSchema()::parse));

        for (int unitIndex = 0; unitIndex < this.unitImages.length; unitIndex++) {
            int movementIndex = units.getFlingyType(unitIndex);
            int spriteIndex = movement.getSprite(movementIndex);

            this.unitImages[unitIndex] = sprites.getImage(spriteIndex);
        }
    }

    /**
     * Returns the base image associated with a unit through the installed DAT tables.
     *
     * @param unitType the unit type index
     * @return the image index
     */
    public int getUnitImage(int unitType) {
        return this.unitImages[unitType];
    }

    /**
     * Reads the installed GRP frame count without decoding its pixel data.
     *
     * @param imageIndex the image type index
     * @return the number of explicit frames in the image
     * @throws IOException if the image does not resolve to an available GRP
     */
    public int getImageFrameCount(int imageIndex) throws IOException {
        return this.loadImage(imageIndex).getFrameCount();
    }

    /**
     * @param imageIndex the image type index
     * @return the {@code images.dat} color shift byte selecting the lighting table of modifier {@code 9}
     */
    int getColorShift(int imageIndex) {
        ScrAddedGraphics added = this.resources.getAddedGraphics();

        return added.containsImage(imageIndex) ? added.getImage(imageIndex).getValue(ImagesDat.COLOR_SHIFT) : this.images.getColorShift(imageIndex);
    }

    /**
     * @param imageIndex the image type index
     * @return the installed {@code images.dat} draw function
     */
    int getDrawFunction(int imageIndex) {
        ScrAddedGraphics added = this.resources.getAddedGraphics();

        return added.containsImage(imageIndex) ? added.getImage(imageIndex).getValue(ImagesDat.DRAW_FUNCTION)
                                               : this.images.getDrawFunction(imageIndex);
    }

    /**
     * @param imageIndex the image type index
     * @return the image's {@code SD/mainSD.anim} entry, a reference entry resolved to the entry it names
     * @throws IOException if the anim is missing or malformed
     */
    AnimFile.@NotNull Entry getSdEntry(int imageIndex) throws IOException {
        return this.getSdAnimations().getEntry(this.getSdEntryIndex(imageIndex));
    }

    /**
     * @param imageIndex the image type index
     * @return the image's {@code images.rel} relation type ({@link ImageRelations#getType}), read on first use
     * @throws IOException if {@code images.rel} is missing or malformed
     */
    int getImageRelationType(int imageIndex) throws IOException {
        ImageRelations relations = this.imageRelations;

        if (relations == null) {
            relations           = ImageRelations.parse(this.storage.readFile("images.rel"));
            this.imageRelations = relations;
        }

        return imageIndex >= 0 && imageIndex < relations.getImageCount() ? relations.getType(imageIndex) : 0;
    }

    /**
     * @return the number of frames in the tileset creep-edge GRP
     */
    int getCreepEdgeFrameCount() {
        return this.creepEdges.getFrameCount();
    }

    private int getSdEntryIndex(int imageIndex) throws IOException {
        AnimFile.Entry entry = this.getSdAnimations().getEntry(imageIndex);

        return entry.isReference() ? entry.getReferenceImage() : imageIndex;
    }

    private @NotNull AnimFile getSdAnimations() throws IOException {
        AnimFile animations = this.sdAnimations;

        if (animations == null) {
            animations        = AnimFile.parse(this.storage.readFile("SD/mainSD.anim"));
            this.sdAnimations = animations;
        }

        return animations;
    }


    /**
     * Returns an independent snapshot of the packed RGBA palette rows.
     *
     * @return the palette rows; row {@code 0} is unchanged and rows {@code 1..16} apply player remapping
     */
    public byte @NotNull [] copyPalette() {
        return this.palette.clone();
    }

    /**
     * Copies the palette rows with each entry's fourth byte replaced by the palette index the entry shows.
     *
     * @return the palette rows of {@link #copyPalette} with their palette indices
     */
    byte @NotNull [] copyIndexedPalette() {
        byte[] result = this.palette.clone();

        for (int index = 0; index < this.paletteIndices.length; index++) {
            result[index * PALETTE_ENTRY_SIZE + PALETTE_FOURTH] = this.paletteIndices[index];
        }

        return result;
    }

    /**
     * Returns one installed lighting table: the palette indices of its PCX picture.
     *
     * @param tableIndex the index into {@link #LIGHTING_TABLE_NAMES}
     * @return the row-major table with {@code 256} columns
     * @throws IOException if the table is missing, malformed or not {@code 256} columns wide
     */
    byte @NotNull [] getLightingTable(int tableIndex) throws IOException {
        byte[] table = this.lightingTables[tableIndex];

        if (table != null) {
            return table;
        }

        SpriteSheet picture = this.resources.loadSpriteSheet(ScrResourceKeys.ofPcx(this.tilesetPrefix + "/" + LIGHTING_TABLE_NAMES[tableIndex] + ".pcx"));
        int width = picture.getWidth();
        int height = picture.getHeight();

        if (width != PALETTE_ENTRIES || height > PALETTE_ENTRIES) {
            throw Exceptions.newIOException("Unexpected lighting table dimensions: {0}", LIGHTING_TABLE_NAMES[tableIndex]);
        }

        table                           = copyIndices(picture, width, height);
        this.lightingTables[tableIndex] = table;

        return table;
    }

    /**
     * Tells whether the installed image entry uses draw function {@code 9} with a nonzero color shift.
     *
     * @param imageIndex the image type index
     * @return {@code true} if the entry selects a baked remap, {@code false} otherwise
     */
    boolean hasBakedRemap(int imageIndex) {
        return !this.resources.getAddedGraphics().containsImage(imageIndex) &&
               this.getDrawFunction(imageIndex) == SceneRenderer.DRAW_FUNCTION_REMAPPING && this.getColorShift(imageIndex) > 0;
    }

    /**
     * Decodes the RGBA diffuse atlas of the image's {@code SD/mainSD.anim} entry ({@link #getSdEntry}) on first use.
     *
     * @param imageIndex the image type index
     * @return the decoded RGBA diffuse atlas
     * @throws IOException if the anim or its diffuse texture is missing or malformed
     */
    byte @NotNull [] decodeSdDiffuse(int imageIndex) throws IOException {
        int entryIndex = this.getSdEntryIndex(imageIndex);
        byte[] atlas = this.sdAtlases.get(entryIndex);

        if (atlas == null) {
            DdsFile diffuse = this.getSdAnimations().getEntry(entryIndex).getLayerTexture(DIFFUSE_LAYER);

            if (diffuse == null) {
                throw Exceptions.newIOException("Image {0} has no SD diffuse texture.", imageIndex);
            }

            atlas = diffuse.decodeRgba();
            this.sdAtlases.put(entryIndex, atlas);
        }

        return atlas;
    }

    /**
     * Returns the alpha coverage of an image frame on its canvas: the {@code SD/mainSD.anim} diffuse alpha for images with a baked remap
     * ({@link #hasBakedRemap}), or {@code 255} at nonzero GRP pixels otherwise.
     *
     * @param imageIndex the image type index
     * @param frameIndex the GRP frame index
     * @return the row-major alpha bytes of the unflipped canvas
     * @throws IOException if the frame or its SD texture is unavailable
     */
    byte @NotNull [] decodeCoverage(int imageIndex, int frameIndex) throws IOException {
        ClassicFrame frame = this.decodeImage(imageIndex, frameIndex);
        int width = frame.getWidth();
        byte[] result = new byte[width * frame.getHeight()];

        if (!this.hasBakedRemap(imageIndex)) {
            byte[] pixels = frame.getPixels();

            for (int index = 0; index < result.length; index++) {
                result[index] = pixels[index] == 0 ? 0 : OverlayPass.OPAQUE_ALPHA;
            }

            return result;
        }

        AnimFile.Entry entry = this.getSdEntry(imageIndex);

        if (frameIndex >= entry.getFrameCount()) {
            throw Exceptions.newIOException("Image {0} frame {1} is outside its SD anim.", imageIndex, frameIndex);
        }

        byte[] atlas = this.decodeSdDiffuse(imageIndex);
        int atlasWidth = entry.getLayerWidth(DIFFUSE_LAYER);
        int frameLeft = entry.getFrameXOffset(frameIndex);
        int frameTop = entry.getFrameYOffset(frameIndex);

        for (int frameRow = 0; frameRow < entry.getFrameHeight(frameIndex); frameRow++) {
            int canvasY = frameTop + frameRow;

            if (canvasY < 0 || canvasY >= frame.getHeight()) {
                continue;
            }

            for (int frameColumn = 0; frameColumn < entry.getFrameWidth(frameIndex); frameColumn++) {
                int canvasX = frameLeft + frameColumn;
                int atlasPixel = (entry.getFrameY(frameIndex) + frameRow) * atlasWidth + entry.getFrameX(frameIndex) + frameColumn;

                if (canvasX >= 0 && canvasX < width) {
                    result[canvasY * width + canvasX] = atlas[atlasPixel * OverlayPass.RGBA_BYTES + OverlayPass.RGBA_ALPHA];
                }
            }
        }

        return result;
    }

    /**
     * Decodes one frame of the tileset creep-edge GRP.
     *
     * @param frameIndex the zero-based creep-edge frame
     * @return the decoded frame on its full canvas
     */
    @NotNull ClassicFrame decodeCreepEdge(int frameIndex) {
        return ClassicFrame.of(this.creepEdges, frameIndex);
    }

    /**
     * Creates the pixels of a warp texture mask: the Warp Texture frame within the image frame's nonzero pixels, as palette index and coverage byte
     * pairs.
     *
     * @param imageIndex        the masked image type index
     * @param frameIndex        the masked image's frame
     * @param textureFrameIndex the Warp Texture frame
     * @param flipped           whether the mask is horizontally flipped
     * @return the row-major index and coverage pairs of the mask's canvas
     * @throws IOException if a frame is unavailable or the texture does not cover the mask
     */
    byte @NotNull [] createWarpPixels(int imageIndex, int frameIndex, int textureFrameIndex, boolean flipped) throws IOException {
        ClassicFrame mask = this.decodeImage(imageIndex, frameIndex);
        ClassicFrame texture = this.decodeImage(SceneRenderer.WARP_TEXTURE_IMAGE, textureFrameIndex);

        if (mask.getFrameWidth() > texture.getFrameWidth() || mask.getFrameHeight() > texture.getFrameHeight()) {
            throw Exceptions.newIOException("Warp texture does not cover the image frame rectangle.");
        }

        byte[] result = new byte[mask.getWidth() * mask.getHeight() * WARP_PIXEL_BYTES];
        int left = flipped ? mask.getWidth() - mask.getXOffset() - mask.getFrameWidth() : mask.getXOffset();

        for (int vertical = 0; vertical < mask.getFrameHeight(); vertical++) {
            for (int horizontal = 0; horizontal < mask.getFrameWidth(); horizontal++) {
                int destinationX = left + horizontal;
                int destinationY = mask.getYOffset() + vertical;
                int sourceX = flipped ? mask.getWidth() - 1 - destinationX : destinationX;

                if (mask.getCoverage()[destinationY * mask.getWidth() + sourceX] == 0) {
                    continue;
                }

                int offset = (destinationY * mask.getWidth() + destinationX) * WARP_PIXEL_BYTES;

                result[offset]     = texture.getPixels()[(texture.getYOffset() + vertical) * texture.getWidth() + texture.getXOffset() + horizontal];
                result[offset + 1] = OverlayPass.OPAQUE_ALPHA;
            }
        }

        return result;
    }

    /**
     * Decodes a CHK terrain tile into {@code 32x32} palette indices, excluding bit {@code 15} from the group reference.
     *
     * @param tileIdentifier the value from the {@code MTXM} section
     * @return the row-major pixels
     * @throws IOException if any tile reference is outside the installed assets
     */
    public byte @NotNull [] decodeTile(int tileIdentifier) throws IOException {
        return decodeTile(this.groups, this.tiles, tileIdentifier);
    }

    /**
     * Returns the megatile a CHK terrain tile shows, excluding bit {@code 15} from the group reference.
     *
     * @param tileIdentifier the value from the {@code MTXM} section
     * @return the megatile index
     * @throws IOException if the tile group is outside the installed assets
     */
    public int getMegatileIndex(int tileIdentifier) throws IOException {
        return findMegatileIndex(this.groups, tileIdentifier);
    }

    /**
     * Resolves and decodes a frame using the one-based image string reference.
     *
     * @param imageIndex the image type index
     * @param frameIndex the explicit GRP frame index
     * @return the decoded frame on its full canvas
     * @throws IOException if the image does not resolve to an available GRP or its frame is malformed
     */
    public @NotNull ClassicFrame decodeImage(int imageIndex, int frameIndex) throws IOException {
        return ClassicFrame.of(this.loadImageForFrame(imageIndex, frameIndex), frameIndex);
    }

    /**
     * Returns the offset from an image's canvas center to the top-left corner of its classic frame rectangle, the image's map position:
     * {@code frame.x - width / 2}, or {@code width / 2 - (frame.x + frame.width)} when flipped, and {@code frame.y - height / 2}.
     *
     * @param imageIndex the image type index
     * @param frameIndex the GRP frame index
     * @param flipped    whether the image is horizontally flipped
     * @return the packed offset (horizontal in the low {@code 16} bits, vertical in the high {@code 16} bits)
     * @throws IOException if the image or frame is unavailable
     */
    public int getFrameMapOffset(int imageIndex, int frameIndex, boolean flipped) throws IOException {
        SpriteSheet sprite = this.loadImageForFrame(imageIndex, frameIndex);
        SpriteFrame frame = sprite.getFrame(frameIndex);

        int horizontal = flipped
                         ? sprite.getWidth() / HALF_DIVISOR - (frame.getX() + frame.getWidth())
                         : frame.getX() - sprite.getWidth() / HALF_DIVISOR;
        int vertical = frame.getY() - sprite.getHeight() / HALF_DIVISOR;

        return horizontal & FRAME_OFFSET_HORIZONTAL_MASK | vertical << FRAME_OFFSET_VERTICAL_SHIFT;
    }

    /**
     * Returns whether a GRP frame repeats the frame before it: both use the same line data, so the sheet stores them once
     * ({@link GrpSheetUtils#isPreviousFrameRepeated}).
     *
     * @param imageIndex the image type index
     * @param frameIndex the GRP frame index
     * @return {@code true} if the frame is not the first and shares the previous frame's data, {@code false} otherwise
     * @throws IOException if the image or frame is unavailable
     */
    public boolean isPreviousFrameRepeated(int imageIndex, int frameIndex) throws IOException {
        return GrpSheetUtils.isPreviousFrameRepeated(this.loadImageForFrame(imageIndex, frameIndex), frameIndex);
    }

    /**
     * Returns the canvas-center coordinate whose drawn frame rectangle starts at a frozen top coordinate.
     * <p>
     * Full-canvas drawing places the canvas top at {@code center - height / 2}, so the frame's vertical offset is removed and the integer half height
     * is added back.
     *
     * @param imageIndex the image type index
     * @param frameIndex the GRP frame index
     * @param frozenMapY the frozen top map coordinate of the frame rectangle
     * @return the canvas-center coordinate to pass to full-canvas drawing
     * @throws IOException if the image or frame is unavailable
     */
    int getFrozenCanvasCenterY(int imageIndex, int frameIndex, int frozenMapY) throws IOException {
        SpriteSheet sprite = this.loadImageForFrame(imageIndex, frameIndex);

        return frozenMapY - sprite.getFrame(frameIndex).getY() + sprite.getHeight() / HALF_DIVISOR;
    }

    private @NotNull SpriteSheet loadImageForFrame(int imageIndex, int frameIndex) throws IOException {
        SpriteSheet sprite = this.loadImage(imageIndex);

        if (frameIndex < 0 || frameIndex >= sprite.getFrameCount()) {
            throw Exceptions.newIOException("Frame {0} is out of bounds for image {1}", frameIndex, imageIndex);
        }

        return sprite;
    }

    private @NotNull SpriteSheet loadImage(int imageIndex) throws IOException {
        if (imageIndex < 0 || imageIndex >= this.imageSheets.length) {
            throw Exceptions.newIOException("Image index is out of bounds: {0}", imageIndex);
        }

        SpriteSheet sprite = this.imageSheets[imageIndex];

        if (sprite == null) {
            sprite                       = this.resources.loadSpriteSheet(ScrResourceKeys.ofImage(imageIndex));
            this.imageSheets[imageIndex] = sprite;
        }

        return sprite;
    }
}
