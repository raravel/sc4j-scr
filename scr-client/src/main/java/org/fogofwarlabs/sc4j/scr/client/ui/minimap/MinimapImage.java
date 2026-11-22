package org.fogofwarlabs.sc4j.scr.client.ui.minimap;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.client.ui.PlayerColorUtils;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerColorRows;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerRelations;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.visibility.LocalVision;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceAssets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds minimap and map-preview pixels with renderer layers.
 * Renderer terrain samples may be transferred from the previous image; fog and dots are refreshed in their existing order.
 * Terrain indices optionally use the tileset color remap, and remembered buildings are drawn after live units.
 * The on-demand RGBA composition bilinearly samples fog; it does not implement a separate blur pass.
 */
public final class MinimapImage {
    /**
     * The default {@code minimapResolution}.
     */
    public static final int DEFAULT_RESOLUTION = 2;
    /**
     * The default {@code minimapSampler}: the weighted minitile mean.
     */
    public static final int DEFAULT_SAMPLER = 2;

    /**
     * The terrain sampler that takes one pixel of the minitile.
     */
    private static final int SINGLE_PIXEL_SAMPLER = 0;
    /**
     * The terrain sampler that averages every pixel of the minitile.
     */
    private static final int AVERAGE_SAMPLER = 1;
    private static final int ENLARGED_BITMAP_SCALE = 2;
    private static final int REDUCED_BITMAP_DIVISOR = 2;
    private static final int VISIBLE_FOG_INDEX = 2;
    /**
     * The fog table index of an explored tile that is not visible.
     */
    private static final int EXPLORED_FOG_INDEX = 1;
    private static final int MEDIUM_RENDERER_SCALE = 2;
    private static final int MEDIUM_MINITILE_STEP = 2;
    private static final int CHANNEL_MASK = 0xFF;
    private static final int OPAQUE_ALPHA = OverlayPass.OPAQUE_ALPHA & CHANNEL_MASK;
    private static final int RED_SHIFT = 24;
    private static final int GREEN_SHIFT = 16;
    private static final int BLUE_SHIFT = 8;
    /**
     * The mask of the byte-sized placement sizes of {@code units.dat}.
     */
    private static final int PLACEMENT_SIZE_MASK = 0xFF;
    /**
     * The offset of a texel center from its corner, in texels.
     */
    private static final float TEXEL_CENTER = 0.5f;
    /**
     * The width of a dot's border in dot pixels.
     */
    private static final float DOT_BORDER_WIDTH = 0.5f;

    private static final int SMALL_MAP_SIDE = 64;
    private static final int MEDIUM_MAP_SIDE = 96;
    private static final int STANDARD_MAP_SIDE = 128;
    private static final int LARGE_MAP_SIDE = 192;
    private static final int MAXIMUM_MAP_SIDE = 256;
    private static final int MAXIMUM_RENDERER_SCALE = 4;
    private static final int SMALL_MAP_RENDERER_FACTOR = 2;
    private static final int MAXIMUM_RESOLUTION = 2;
    private static final int MINITILE_PIXELS = TerrainTileset.MINI_TILE_SIZE * TerrainTileset.MINI_TILE_SIZE;
    private static final int SINGLE_SAMPLE_PIXEL = 55;
    private static final int WEIGHTED_SAME_TILE_LIMIT = 3;
    private static final int FALLBACK_TILE = 0x20;
    private static final int MINIMUM_DOT_SIZE = 2;
    private static final int MAXIMUM_BUILDING_DOT_SIZE = 4;
    /**
     * The Shift+Tab color modes: player colors and team colors.
     */
    private static final int PLAYER_COLOR_MODE = 0;
    private static final int TEAM_COLOR_MODE = 2;
    /**
     * The sides of a dot that its border extends: left and right, or top and bottom.
     */
    private static final int BORDER_SIDES = 2;

    /**
     * The renderer's pixels per tile by {@code minimapResolution}.
     */
    private static final int @NotNull [] RESOLUTION_SCALES = {1, 2, 4};
    /**
     * Fog alpha of the renderer (index {@code visible * 2 | explored}; observers {@code 0xda}).
     */
    private static final int FOG_UNEXPLORED = 0xFF;
    private static final int FOG_UNEXPLORED_OBSERVER = 0xDA;
    private static final int FOG_EXPLORED = 0xAA;
    /**
     * The renderer's dot border color.
     */
    private static final int DOT_BORDER = 0x000000FF;
    /**
     * Preview dots of other owners: nearest RGB({@code 0,75,227}).
     */
    private static final int PREVIEW_OTHER_TARGET = 0x004BE3;

    /**
     * Weighted pixels of sampler {@code 2}: the pixel and weight pairs of the sampled minitile.
     */
    private static final int @NotNull [][] SAMPLER_OWN = {{46, 1}, {47, 2}, {54, 2}, {55, 4}, {62, 1}, {63, 2}};
    /**
     * The left column (rows {@code 5..7}) of the minitile to the right.
     */
    private static final int @NotNull [][] SAMPLER_RIGHT = {{40, 1}, {48, 2}, {56, 1}};

    /**
     * The nearest-color targets of the minimap dots (computed per tileset palette).
     */
    private static final int OWN_UNIT_TARGET = 0x00E32B;
    private static final int RESOURCE_TARGET = 0x00FFFF;
    private static final int ALLY_TARGET = 0xFFFF4F;
    private static final int ENEMY_TARGET = 0xCF0000;
    /**
     * Team colors indexed by team minus one, with the last entry used for team zero.
     */
    private static final int @NotNull [] TEAM_COLORS = {0xF40404FF, 0x0C48CCFF, 0x2CB494FF, 0x88409CFF,
                                                        0xF88C14FF, 0x703014FF, 0xCCE0D0FF, 0xFCFC38FF};

    /**
     * Creates a {@link MinimapImage} instance whose bitmap dimensions, divisor and zoom follow the longer map side.
     *
     * @param mapWidth  the map width in tiles
     * @param mapHeight the map height in tiles
     * @param scale     the renderer's pixels per tile
     * @return the created {@link MinimapImage} instance
     */
    private static @NotNull MinimapImage createImage(int mapWidth, int mapHeight, int scale) {
        int longer = Math.max(mapWidth, mapHeight);

        if (longer == SMALL_MAP_SIDE) {
            return new MinimapImage(mapWidth, mapHeight, ENLARGED_BITMAP_SCALE * mapWidth, ENLARGED_BITMAP_SCALE * mapHeight, 1, 1, scale);
        }

        if (longer == MEDIUM_MAP_SIDE || longer == STANDARD_MAP_SIDE) {
            return new MinimapImage(mapWidth, mapHeight, mapWidth, mapHeight, 1, 0, scale);
        }

        if (longer == LARGE_MAP_SIDE || longer == MAXIMUM_MAP_SIDE) {
            return new MinimapImage(mapWidth, mapHeight,
                                    mapWidth / REDUCED_BITMAP_DIVISOR, mapHeight / REDUCED_BITMAP_DIVISOR,
                                    REDUCED_BITMAP_DIVISOR, 0, scale);
        }

        if (longer < SMALL_MAP_SIDE) {
            return new MinimapImage(mapWidth, mapHeight, SMALL_MAP_SIDE, SMALL_MAP_SIDE, 1, 1, scale);
        }

        if (longer < STANDARD_MAP_SIDE) {
            return new MinimapImage(mapWidth, mapHeight, STANDARD_MAP_SIDE, STANDARD_MAP_SIDE, 1, 0, scale);
        }

        return new MinimapImage(mapWidth, mapHeight, STANDARD_MAP_SIDE, STANDARD_MAP_SIDE, REDUCED_BITMAP_DIVISOR, 0, scale);
    }

    /**
     * Returns the renderer scale of the minimap: {@code {1,2,4}[resolution]}, doubled (at most {@code 4}) when the longer map side is {@code 64}
     * tiles or less.
     *
     * @param mapWidth   the map width in tiles
     * @param mapHeight  the map height in tiles
     * @param resolution the {@code minimapResolution} setting from {@code 0} through {@code 2}
     * @return the pixels per tile
     */
    public static int getRendererScale(int mapWidth, int mapHeight, int resolution) {
        int scale = RESOLUTION_SCALES[Math.clamp(resolution, 0, MAXIMUM_RESOLUTION)];

        return Math.max(mapWidth, mapHeight) <= SMALL_MAP_SIDE ? Math.min(SMALL_MAP_RENDERER_FACTOR * scale, MAXIMUM_RENDERER_SCALE) : scale;
    }

    /**
     * Creates a {@link MinimapImage} instance of the layered game minimap, transferring compatible terrain and dot buffers from the previous image.
     * Live units are drawn before remembered buildings.
     *
     * @param view        the game view, whose local vision ({@link LocalVision}) chooses the players shown
     * @param assets      the tileset interface assets
     * @param localPlayer the local player, whose own units a game draws last
     * @param showTerrain whether terrain is drawn; Tab toggles it off, leaving a transparent bitmap with the dots
     * @return the created {@link MinimapImage} instance
     * @throws IOException if {@code dark.pcx} cannot be read
     */
    public static @NotNull MinimapImage build(@NotNull ScrGameView view, @NotNull InterfaceAssets assets, int localPlayer, boolean showTerrain) throws IOException {
        return build(view, assets, localPlayer, showTerrain, List.of(), 0, new HashMap<>(), false, null);
    }

    /**
     * Creates a {@link MinimapImage} instance of the layered game minimap, transferring compatible terrain and dot buffers from the previous image.
     * Live units are drawn before remembered buildings.
     *
     * @param view        the game view, whose local vision ({@link LocalVision}) chooses the players shown
     * @param assets      the tileset interface assets
     * @param localPlayer the local player, whose own units a game draws last
     * @param showTerrain whether terrain is drawn
     * @param ghosts      the remembered buildings that are not currently seen
     * @param colorMode   the Shift+Tab color mode: {@code 0} player colors, {@code 1} ally colors (allies yellow, enemies red), {@code 2} team colors
     * @param blinks      the blinking units by pool index: a count and a palette index; each dot pass decrements the count and
     *                    removes finished entries
     * @param blinkPhase  the {@code 200} ms blink phase
     * @param previous    the previous image of the same view, whose terrain samples are kept for unchanged tiles, or {@code null}
     * @return the created {@link MinimapImage} instance
     * @throws IOException if {@code dark.pcx} cannot be read
     */
    public static @NotNull MinimapImage build(@NotNull ScrGameView view,
                                              @NotNull InterfaceAssets assets,
                                              int localPlayer,
                                              boolean showTerrain,
                                              @NotNull List<RememberedBuildings.Ghost> ghosts,
                                              int colorMode,
                                              @NotNull Map<Integer, int[]> blinks, boolean blinkPhase,
                                              @Nullable MinimapImage previous) throws IOException {
        GameMap map = view.getMap();
        LocalVision vision = view.getLocalVision();
        boolean replayOrObserver = vision.isReplayOrObserver();
        MinimapImage image = createImage(map.getWidth(), map.getHeight(), getRendererScale(map.getWidth(), map.getHeight(), DEFAULT_RESOLUTION));

        image.colorMode   = colorMode;
        image.blinks      = blinks;
        image.blinkPhase  = blinkPhase;
        image.localPlayer = localPlayer;

        boolean sameCanvas = previous != null && previous.scale == image.scale && previous.mapWidth == image.mapWidth &&
                             previous.mapHeight == image.mapHeight;

        // The renderer clears its dot layer and refills it on every refresh; the canvas passes to the new image instead of being allocated again (the
        // previous image is replaced by this one).
        if (sameCanvas && previous.dotLayer != null) {
            image.dotLayer    = previous.dotLayer;
            previous.dotLayer = null;

            // Only the rows the previous pass drew hold dots; they are cleared, and uploaded with this pass's rows.
            if (previous.dotTop <= previous.dotBottom) {
                Arrays.fill(image.dotLayer, previous.dotTop * image.textureWidth, (previous.dotBottom + 1) * image.textureWidth, 0);
            }

            image.clearedTop    = previous.dotTop;
            image.clearedBottom = previous.dotBottom;
        } else {
            image.dotLayer      = new int[image.textureWidth * image.textureHeight];
            image.clearedTop    = 0;
            image.clearedBottom = image.textureHeight - 1;
        }

        // The terrain and creep layers keep their texels while the terrain is not dirty; the fog layer (one texel per tile) is filled again
        // on every refresh.
        if (showTerrain) {
            image.updateTerrain(map, assets, previous);

            int[] fog = sameCanvas && previous.fogLayer != null ? previous.fogLayer : new int[map.getWidth() * map.getHeight()];
            int viewMask = vision.getViewMask();
            boolean revealed = replayOrObserver && vision.isRevealed();

            for (int tile = 0; tile < fog.length; tile++) {
                int index = getFogIndex(map.hasTileFlags() ? map.getTileFlags(tile) : 0, replayOrObserver, viewMask, revealed);

                fog[tile] = index >= VISIBLE_FOG_INDEX
                            ? 0
                            : index == EXPLORED_FOG_INDEX ? FOG_EXPLORED : replayOrObserver ? FOG_UNEXPLORED_OBSERVER : FOG_UNEXPLORED;
            }

            image.fogLayer = fog;
        } else if (sameCanvas && previous.terrainLayer != null && previous.terrainAssets == assets) {
            // Tab hides the terrain layers without dropping them.
            image.terrainLayer    = previous.terrainLayer;
            image.baseTiles       = previous.baseTiles;
            image.liveTiles       = previous.liveTiles;
            image.terrainAssets   = previous.terrainAssets;
            previous.terrainLayer = null;
        }

        if (previous != null) {
            previous.fogLayer = null;
        }

        image.terrainShown = showTerrain;

        image.drawDots(view, assets, vision, localPlayer);
        image.drawGhosts(view, assets, vision, ghosts);

        return image;
    }

    /**
     * Creates a {@link MinimapImage} instance of an opaque map preview for the supported map sizes, using terrain sampler {@code 0}
     * without color remapping.
     * Invalid tile groups use the fallback tile; resource dots and owner dots use their existing palette selection.
     *
     * @param mapWidth        the map width in tiles
     * @param mapHeight       the map height in tiles
     * @param tileIdentifiers the raw MTXM words, row-major, {@code mapWidth * mapHeight} of them
     * @param units           the preview units of the map-list reader
     * @param unitTypes       {@code units.dat}, for the placement boxes
     * @param assets          the interface assets of the map's tileset
     * @return the created {@link MinimapImage} instance ({@link #getTextureWidth} by {@link #getTextureHeight}, opaque), or {@code null} when the
     * longer side is not {@code 64}, {@code 96}, {@code 128}, {@code 192} or {@code 256} tiles (no image)
     */
    public static @Nullable MinimapImage buildPreview(int mapWidth, int mapHeight,
                                                      short @NotNull [] tileIdentifiers,
                                                      @NotNull List<MinimapPreviewUnit> units,
                                                      @NotNull ScrUnitTypeView unitTypes,
                                                      @NotNull InterfaceAssets assets) {
        int longer = Math.max(mapWidth, mapHeight);

        if (longer != SMALL_MAP_SIDE && longer != MEDIUM_MAP_SIDE && longer != STANDARD_MAP_SIDE && longer != LARGE_MAP_SIDE &&
            longer != MAXIMUM_MAP_SIDE) {
            return null;
        }

        MinimapImage image = createImage(mapWidth, mapHeight, longer > STANDARD_MAP_SIDE ? MEDIUM_RENDERER_SCALE : MAXIMUM_RENDERER_SCALE);

        image.dotLayer = new int[image.textureWidth * image.textureHeight];

        int groups = assets.getTileGroupCount();
        int[] tiles = new int[mapWidth * mapHeight];

        for (int tile = 0; tile < tiles.length && tile < tileIdentifiers.length; tile++) {
            int word = Short.toUnsignedInt(tileIdentifiers[tile]);

            tiles[tile] = TerrainTileset.getGroupIndex(word) >= groups ? FALLBACK_TILE : word;
        }

        int[] terrain = image.createTerrainCanvas(assets, tiles, null, SINGLE_PIXEL_SAMPLER, false);

        for (MinimapPreviewUnit unit : units) {
            int type = unit.getType();
            int color;

            if (unitTypes.isMineralField(type) || type == ScrUnitIds.RESOURCE_VESPENE_GEYSER) {
                color = assets.getNearestPaletteColor(RESOURCE_TARGET);
            } else if (unit.getOwner() < PlayerTable.PLAYER_SLOTS) {
                color = assets.getPaletteColor(assets.getMinimapPlayerIndex(unit.getOwner()));
            } else {
                color = assets.getNearestPaletteColor(PREVIEW_OTHER_TARGET);
            }

            image.drawRectangle(unit.getX(), unit.getY(), unitTypes, type, true, color);
        }

        image.rgba = new byte[image.textureWidth * image.textureHeight * OverlayPass.RGBA_BYTES];

        image.composeLayers(terrain, null);

        return image;
    }

    /**
     * Computes the fog table index {@code visible * 2 | explored} of a tile. A game sees a tile that every player of the view mask sees and has
     * explored one that every such player explored; a replay or observer view sees a tile that a player of the view mask sees, has explored one that
     * such a player explored, and sees every tile while the map is revealed.
     *
     * @param flags            the tile flags: a bit for each player that does not see the tile, and above it for each that has not explored it
     * @param replayOrObserver whether the view is a replay or an observer view
     * @param viewMask         the players whose vision the view shows ({@link LocalVision#getViewMask})
     * @param revealed         whether the replay reveals the entire map
     * @return the fog table index
     */
    private static int getFogIndex(int flags, boolean replayOrObserver, int viewMask, boolean revealed) {
        if (!replayOrObserver) {
            return ((flags & viewMask) == 0 ? VISIBLE_FOG_INDEX : 0) | ((flags & viewMask << Byte.SIZE) == 0 ? EXPLORED_FOG_INDEX : 0);
        }

        if (revealed) {
            return VISIBLE_FOG_INDEX;
        }

        if ((~flags & viewMask << Byte.SIZE) == 0) {
            return 0;
        }

        return (~flags & viewMask) != 0 ? VISIBLE_FOG_INDEX : EXPLORED_FOG_INDEX;
    }

    /**
     * Samples one terrain color using a single pixel, an unweighted minitile mean or the weighted pixel tables.
     * The weighted route retains its minitile-index test when choosing the neighboring samples and truncates channel averages.
     *
     * @param assets   the tileset interface assets
     * @param sampler  the terrain sampling mode
     * @param remap    whether palette indices are remapped
     * @param megatile the megatile of the sampled tile
     * @param right    the megatile of the tile to the right, or {@code 0}
     * @param minitile the sampled minitile of the megatile
     * @return the packed opaque {@code 0xRRGGBBAA} color
     */
    private static int sampleTerrainColor(@NotNull InterfaceAssets assets, int sampler, boolean remap, int megatile, int right, int minitile) {
        if (sampler == SINGLE_PIXEL_SAMPLER) {
            return getPaletteColor(assets, remap, assets.getMinitilePixel(megatile, minitile, SINGLE_SAMPLE_PIXEL));
        }

        int red = 0;
        int green = 0;
        int blue = 0;
        int weight = 0;

        if (sampler == AVERAGE_SAMPLER) {
            for (int pixel = 0; pixel < MINITILE_PIXELS; pixel++) {
                int color = getPaletteColor(assets, remap, assets.getMinitilePixel(megatile, minitile, pixel));

                red += color >>> RED_SHIFT;
                green += color >>> GREEN_SHIFT & CHANNEL_MASK;
                blue += color >>> BLUE_SHIFT & CHANNEL_MASK;
            }

            return (red / MINITILE_PIXELS) << RED_SHIFT | (green / MINITILE_PIXELS) << GREEN_SHIFT | (blue / MINITILE_PIXELS) << BLUE_SHIFT |
                   OPAQUE_ALPHA;
        }

        for (int[] entry : SAMPLER_OWN) {
            int color = getPaletteColor(assets, remap, assets.getMinitilePixel(megatile, minitile, entry[0]));

            red += (color >>> RED_SHIFT) * entry[1];
            green += (color >>> GREEN_SHIFT & CHANNEL_MASK) * entry[1];
            blue += (color >>> BLUE_SHIFT & CHANNEL_MASK) * entry[1];
            weight += entry[1];
        }

        int neighbor = minitile < WEIGHTED_SAME_TILE_LIMIT ? megatile : right;
        int neighborMinitile = minitile < WEIGHTED_SAME_TILE_LIMIT ? minitile + 1 : minitile - WEIGHTED_SAME_TILE_LIMIT;

        if (minitile < WEIGHTED_SAME_TILE_LIMIT || right != 0) {
            for (int[] entry : SAMPLER_RIGHT) {
                int color = getPaletteColor(assets, remap, assets.getMinitilePixel(neighbor, neighborMinitile, entry[0]));

                red += (color >>> RED_SHIFT) * entry[1];
                green += (color >>> GREEN_SHIFT & CHANNEL_MASK) * entry[1];
                blue += (color >>> BLUE_SHIFT & CHANNEL_MASK) * entry[1];
                weight += entry[1];
            }
        }

        return (red / weight) << RED_SHIFT | (green / weight) << GREEN_SHIFT | (blue / weight) << BLUE_SHIFT | OPAQUE_ALPHA;
    }

    /**
     * Gets a palette color after an optional minimap remap.
     *
     * @param assets the tileset interface assets
     * @param remap  whether palette indices are remapped
     * @param index  the palette or storage index
     * @return the palette color
     */
    private static int getPaletteColor(@NotNull InterfaceAssets assets, boolean remap, int index) {
        return assets.getPaletteColor(remap ? assets.getMinimapRemapIndex(index) : index);
    }

    /**
     * Returns the team color in team mode, otherwise the color the player is shown in ({@link PlayerColorUtils#getPlayerColor}).
     *
     * @param view      the game view
     * @param assets    the tileset interface assets
     * @param player    the player slot
     * @param colorMode the Shift+Tab color mode
     * @return the packed {@code 0xRRGGBBAA} color
     */
    public static int getPlayerColor(@NotNull ScrGameView view, @NotNull InterfaceAssets assets, int player, int colorMode) {
        if (colorMode == TEAM_COLOR_MODE) {
            return getTeamColor(player < PlayerTable.PLAYER_SLOTS ? view.getPlayerTable().getPlayer(player) : null);
        }

        return PlayerColorUtils.getPlayerColor(view.getPlayerTable(), assets, player);
    }

    /**
     * Returns the team color of a player: the color of its team, or the last color for team {@code 0} and a missing player.
     *
     * @param player the player, or {@code null}
     * @return the packed opaque {@code 0xRRGGBBAA} color
     */
    private static int getTeamColor(@Nullable Player player) {
        int team = player != null ? player.getTeam() : 0;

        return TEAM_COLORS[team == 0 ? TEAM_COLORS.length - 1 : Math.clamp(team - 1, 0, TEAM_COLORS.length - 1)];
    }

    /**
     * Writes a packed {@code 0xRRGGBBAA} color as four RGBA bytes.
     *
     * @param rgba   the RGBA bytes
     * @param offset the offset of the pixel's first byte
     * @param color  the packed color
     */
    private static void writeRgba(byte @NotNull [] rgba, int offset, int color) {
        rgba[offset]                          = (byte) (color >>> RED_SHIFT);
        rgba[offset + OverlayPass.RGBA_GREEN] = (byte) (color >>> GREEN_SHIFT);
        rgba[offset + OverlayPass.RGBA_BLUE]  = (byte) (color >>> BLUE_SHIFT);
        rgba[offset + OverlayPass.RGBA_ALPHA] = (byte) color;
    }

    private final int mapWidth;
    private final int mapHeight;
    private final int width;
    private final int height;
    private final int divisor;
    private final int zoom;
    private final int scale;
    private final int textureWidth;
    private final int textureHeight;

    private byte @Nullable [] rgba;
    private int @Nullable [] dotLayer;
    /**
     * The canvas rows this pass drew dots in ({@code dotTop > dotBottom}: none).
     */
    private int dotTop;
    private int dotBottom;
    /**
     * The canvas rows cleared from the previous pass ({@code clearedTop > clearedBottom}: none).
     */
    private int clearedTop;
    private int clearedBottom;

    private int @Nullable [] terrainLayer;
    /**
     * Whether {@link #terrainLayer} was sampled again for this image (new or changed tiles).
     */
    private boolean terrainChanged;
    /**
     * The fog layer: one texel per tile, black with the fog alpha ({@code 0x000000AA}), or {@code null} without terrain.
     */
    private int @Nullable [] fogLayer;
    /**
     * Whether the terrain is drawn (Tab off clears it).
     */
    private boolean terrainShown;
    private int @Nullable [] baseTiles;
    private int @Nullable [] liveTiles;
    private @Nullable InterfaceAssets terrainAssets;

    private int colorMode;
    private @NotNull Map<Integer, int[]> blinks;
    private boolean blinkPhase;
    private int localPlayer;
    /**
     * The nearest palette colors of the own-unit and resource dots of the current pass.
     */
    private int ownUnitColor;
    private int resourceColor;

    /**
     * Creates a {@link MinimapImage} instance with its bitmap dimensions and empty renderer layers.
     *
     * @param mapWidth  the map width in tiles
     * @param mapHeight the map height in tiles
     * @param width     the bitmap width
     * @param height    the bitmap height
     * @param divisor   the bitmap reduction divisor
     * @param zoom      the bitmap zoom shift
     * @param scale     the renderer pixels per tile
     */
    private MinimapImage(int mapWidth, int mapHeight, int width, int height, int divisor, int zoom, int scale) {
        this.mapWidth      = mapWidth;
        this.mapHeight     = mapHeight;
        this.width         = width;
        this.height        = height;
        this.divisor       = divisor;
        this.zoom          = zoom;
        this.scale         = scale;
        this.textureWidth  = mapWidth * scale;
        this.textureHeight = mapHeight * scale;

        this.rgba          = null;
        this.dotLayer      = null;
        this.dotTop        = Integer.MAX_VALUE;
        this.dotBottom     = -1;
        this.clearedTop    = Integer.MAX_VALUE;
        this.clearedBottom = -1;

        this.terrainLayer   = null;
        this.terrainChanged = false;
        this.fogLayer       = null;
        this.terrainShown   = false;
        this.baseTiles      = null;
        this.liveTiles      = null;
        this.terrainAssets  = null;

        this.colorMode     = PLAYER_COLOR_MODE;
        this.blinks        = new HashMap<>();
        this.blinkPhase    = false;
        this.localPlayer   = 0;
        this.ownUnitColor  = 0;
        this.resourceColor = 0;
    }

    /**
     * @return the bitmap width in pixels
     */
    public int getWidth() {
        return this.width;
    }

    /**
     * @return the bitmap height in pixels
     */
    public int getHeight() {
        return this.height;
    }

    /**
     * @return the bitmap reduction divisor
     */
    public int getDivisor() {
        return this.divisor;
    }

    /**
     * @return the bitmap zoom shift
     */
    public int getZoom() {
        return this.zoom;
    }

    /**
     * @return map pixels per minimap pixel: {@code (32 * divisor) >> zoom}
     */
    public int getMapPixelsPerPixel() {
        return (TerrainTileset.TILE_SIZE * this.divisor) >> this.zoom;
    }

    /**
     * @return the RGBA texture pixels ({@link #getTextureWidth()} by {@link #getTextureHeight()}), drawn stretched into the bitmap box
     */
    public byte @NotNull [] getRgba() {
        if (this.rgba == null) {
            // The renderer path keeps its layers apart (drawn by the GPU); the composed image is built on demand.
            this.rgba = new byte[this.textureWidth * this.textureHeight * OverlayPass.RGBA_BYTES];

            this.composeLayers(this.terrainShown ? this.terrainLayer : null, this.fogLayer);
        }

        return this.rgba;
    }

    /**
     * @return the terrain layer of the renderer path (with creep): the canvas texels as {@code 0xRRGGBBAA}, or {@code null} while the terrain is
     * hidden or without a terrain layer
     */
    public int @Nullable [] getTerrainLayer() {
        return this.terrainShown ? this.terrainLayer : null;
    }

    /**
     * @return {@code true} if the terrain layer was sampled again for this image, so that its texture needs an upload, {@code false} otherwise
     */
    public boolean isTerrainChanged() {
        return this.terrainChanged;
    }

    /**
     * @return the fog layer of the renderer path: {@link #getMapWidth} by {@link #getMapHeight} texels of black with the fog alpha, as
     * {@code 0xRRGGBBAA}, filtered linearly when drawn; {@code null} while the terrain is hidden
     */
    public int @Nullable [] getFogLayer() {
        return this.terrainShown ? this.fogLayer : null;
    }

    /**
     * @return the dot layer of the renderer path: the canvas texels as {@code 0xRRGGBBAA}, {@code 0} where no dot is, or {@code null} once a
     * later image took the layer over
     */
    public int @Nullable [] getDotLayer() {
        return this.dotLayer;
    }

    /**
     * @return the first canvas row of the dot layer that changed since the previous image (rows drawn now or cleared from it), or {@code -1}
     * when none changed
     */
    public int getChangedDotTop() {
        int top = Math.min(this.dotTop, this.clearedTop);

        return top == Integer.MAX_VALUE ? -1 : top;
    }

    /**
     * @return the last canvas row of the dot layer that changed since the previous image, or {@code -1}
     */
    public int getChangedDotBottom() {
        return Math.max(this.dotBottom, this.clearedBottom);
    }

    /**
     * @return the map width in tiles
     */
    public int getMapWidth() {
        return this.mapWidth;
    }

    /**
     * @return the map height in tiles
     */
    public int getMapHeight() {
        return this.mapHeight;
    }

    /**
     * @return the texture width, {@code mapWidth * scale}
     */
    public int getTextureWidth() {
        return this.textureWidth;
    }

    /**
     * @return the texture height, {@code mapHeight * scale}
     */
    public int getTextureHeight() {
        return this.textureHeight;
    }

    /**
     * @return the renderer's pixels per tile
     */
    public int getScale() {
        return this.scale;
    }


    /**
     * Converts a map tile coordinate to a bitmap coordinate: {@code (tile << zoom) / divisor}.
     *
     * @param tiles the tile coordinate or extent
     * @return the bitmap coordinate
     */
    public int toBitmap(int tiles) {
        return (tiles << this.zoom) / this.divisor;
    }

    /**
     * Updates changed terrain samples and transfers compatible previous terrain storage.
     *
     * @param map      the tile identifiers
     * @param assets   the tileset assets
     * @param previous the previous view, or {@code null}
     */
    private void updateTerrain(@NotNull GameMap map, @NotNull InterfaceAssets assets, @Nullable MinimapImage previous) {
        int[] base = new int[map.getWidth() * map.getHeight()];
        int[] live = new int[base.length];

        for (int tile = 0; tile < base.length; tile++) {
            live[tile] = map.getTileIdentifier(tile);

            int retained = map.hasOverlayTiles() ? map.getOverlayTileIdentifier(tile) : 0;

            base[tile] = retained != 0 ? retained : live[tile];
        }

        int[] terrain;

        if (previous != null && previous.terrainLayer != null && previous.terrainAssets == assets && previous.scale == this.scale &&
            previous.mapWidth == this.mapWidth && previous.mapHeight == this.mapHeight) {
            // Only tiles whose own or right neighbor's identifiers changed are sampled again. The layer passes to the new image (the previous one is
            // replaced by it).
            terrain               = previous.terrainLayer;
            previous.terrainLayer = null;

            for (int tile = 0; tile < base.length; tile++) {
                int column = tile % this.mapWidth;
                boolean rightChanged = column + 1 < this.mapWidth && base[tile + 1] != previous.baseTiles[tile + 1];

                if (base[tile] != previous.baseTiles[tile] || live[tile] != previous.liveTiles[tile] || rightChanged) {
                    this.sampleTile(assets, base, live, terrain, DEFAULT_SAMPLER, true, column, tile / this.mapWidth);

                    this.terrainChanged = true;
                }
            }
        } else {
            terrain             = this.createTerrainCanvas(assets, base, live, DEFAULT_SAMPLER, true);
            this.terrainChanged = true;
        }

        this.terrainLayer  = terrain;
        this.baseTiles     = base;
        this.liveTiles     = live;
        this.terrainAssets = assets;
    }

    /**
     * Samples the renderer terrain at the current pixels-per-tile scale.
     * Live tiles replace base megatiles for creep; compatible previous layers are updated only where relevant tile identifiers changed.
     *
     * @param assets  the tileset interface assets
     * @param base    the base tile identifiers
     * @param live    the live tile identifiers, or {@code null}
     * @param sampler the terrain sampling mode
     * @param remap   whether palette indices are remapped
     * @return the canvas texels as {@code 0xRRGGBBAA}
     */
    private int @NotNull [] createTerrainCanvas(@NotNull InterfaceAssets assets,
                                                int @NotNull [] base, int @Nullable [] live,
                                                int sampler, boolean remap) {
        int[] canvas = new int[this.textureWidth * this.textureHeight];

        for (int tileY = 0; tileY < this.mapHeight; tileY++) {
            for (int tileX = 0; tileX < this.mapWidth; tileX++) {
                this.sampleTile(assets, base, live, canvas, sampler, remap, tileX, tileY);
            }
        }

        return canvas;
    }

    /**
     * Samples one source tile into its scaled destination rectangle.
     *
     * @param assets  the tileset interface assets
     * @param base    the base tile identifiers
     * @param live    the live tile identifiers, or {@code null}
     * @param canvas  the destination pixels
     * @param sampler the terrain sampling mode
     * @param remap   whether palette indices are remapped
     * @param tileX   the source tile column
     * @param tileY   the source tile row
     */
    private void sampleTile(@NotNull InterfaceAssets assets,
                            int @NotNull [] base, int @Nullable [] live,
                            int @NotNull [] canvas,
                            int sampler, boolean remap,
                            int tileX, int tileY) {
        int tile = tileY * this.mapWidth + tileX;
        int megatile = assets.getMegatile(base[tile]);
        int right = tileX + 1 < this.mapWidth ? assets.getMegatile(base[tile + 1]) : 0;

        if (live != null) {
            int current = assets.getMegatile(live[tile]);

            if (current != megatile) {
                megatile = current;
                right    = 0;
            }
        }

        for (int row = 0; row < this.scale; row++) {
            for (int column = 0; column < this.scale; column++) {
                int minitile = this.scale == MAXIMUM_RENDERER_SCALE
                               ? row * TerrainTileset.MINI_TILES_PER_ROW + column
                               : this.scale == MEDIUM_RENDERER_SCALE
                                 ? row * (MEDIUM_MINITILE_STEP * TerrainTileset.MINI_TILES_PER_ROW) + column * MEDIUM_MINITILE_STEP
                                 : 0;
                int canvasIndex = (tileY * this.scale + row) * this.textureWidth + tileX * this.scale + column;

                canvas[canvasIndex] = sampleTerrainColor(assets, sampler, remap, megatile, right, minitile);
            }
        }
    }

    /**
     * Composes the renderer layers as the final draw stacks them: black, then (unless the terrain is hidden) the terrain and creep and the black fog,
     * then the dots. The fog texture has one texel per tile and is filtered linearly into the canvas (texel centers, clamped edges).
     *
     * @param terrain the terrain layer, or {@code null} while the terrain is hidden
     * @param fog     the fog layer, or {@code null}
     */
    private void composeLayers(int @Nullable [] terrain, int @Nullable [] fog) {
        int[] dots = this.dotLayer;

        for (int y = 0; y < this.textureHeight; y++) {
            float sampleY = (y + TEXEL_CENTER) / this.scale - TEXEL_CENTER;
            int sourceRow = (int) Math.floor(sampleY);
            float fractionY = sampleY - sourceRow;
            int row0 = Math.clamp(sourceRow, 0, this.mapHeight - 1) * this.mapWidth;
            int row1 = Math.clamp(sourceRow + 1, 0, this.mapHeight - 1) * this.mapWidth;

            for (int x = 0; x < this.textureWidth; x++) {
                int pixel = y * this.textureWidth + x;
                int color = DOT_BORDER;

                if (terrain != null) {
                    color = terrain[pixel];

                    if (fog != null) {
                        float sampleX = (x + TEXEL_CENTER) / this.scale - TEXEL_CENTER;
                        int sourceColumn = (int) Math.floor(sampleX);
                        float fractionX = sampleX - sourceColumn;
                        int column0 = Math.clamp(sourceColumn, 0, this.mapWidth - 1);
                        int column1 = Math.clamp(sourceColumn + 1, 0, this.mapWidth - 1);
                        float top = fog[row0 + column0] + (fog[row0 + column1] - fog[row0 + column0]) * fractionX;
                        float bottom = fog[row1 + column0] + (fog[row1 + column1] - fog[row1 + column0]) * fractionX;
                        float keep = 1 - (top + (bottom - top) * fractionY) / OverlayPass.CHANNEL_MAXIMUM;

                        color = Math.round((color >>> RED_SHIFT) * keep) << RED_SHIFT |
                                Math.round((color >>> GREEN_SHIFT & CHANNEL_MASK) * keep) << GREEN_SHIFT |
                                Math.round((color >>> BLUE_SHIFT & CHANNEL_MASK) * keep) << BLUE_SHIFT |
                                OPAQUE_ALPHA;
                    }
                }

                if (dots != null && dots[pixel] != 0) {
                    color = dots[pixel];
                }

                writeRgba(this.rgba, pixel * OverlayPass.RGBA_BYTES, color);
            }
        }
    }

    /**
     * Groups drawable units by owner and draws the local player last.
     *
     * @param view        the game view
     * @param assets      the tileset interface assets
     * @param vision      the vision of the local view
     * @param localPlayer the local player slot
     * @throws NullPointerException if the world was built without game data
     */
    private void drawDots(@NotNull ScrGameView view, @NotNull InterfaceAssets assets, @NotNull LocalVision vision, int localPlayer) {
        UnitPool pool = view.getUnitSystem().getUnitPool();
        boolean replay = vision.isReplayOrObserver();
        int viewMask = vision.getViewMask();
        boolean revealed = replay && vision.isRevealed();

        if (pool == null) {
            return;
        }

        ScrUnitTypeView units = view.requireGameData().getUnitTypes();

        // The dot pass draws owner 11 down to 0 and the local player last, each owner's units in pool order: one pass over the pool sorts the
        // drawable units by owner first.
        int[] counts = new int[PlayerTable.PLAYER_SLOTS];
        int[] indices = new int[pool.getCapacity() + 1];
        int[] owners = new int[pool.getCapacity() + 1];
        int drawable = 0;

        for (int index = 1; index <= pool.getCapacity(); index++) {
            if (!pool.isAllocated(index)) {
                continue;
            }

            Unit unit = pool.getUnit(index);
            int owner = unit.getOwnerIndex();
            int type = unit.getUnitId();

            if (owner < 0 || owner >= PlayerTable.PLAYER_SLOTS || unit.getSpriteOrNull() == null || !units.hasEntry(type)) {
                continue;
            }

            if ((units.getFlags(type) & UnitTypeFlags.SUBUNIT) != 0 ||
                type >= ScrUnitIds.SPECIAL_FLOOR_MISSILE_TRAP && type <= ScrUnitIds.SPECIAL_RIGHT_WALL_FLAME_TRAP ||
                type == ScrUnitIds.SPELL_DARK_SWARM || type == ScrUnitIds.SPELL_DISRUPTION_WEB) {
                continue;
            }

            indices[drawable]  = index;
            owners[drawable++] = owner;
            counts[owner]++;
        }

        int[] starts = new int[PlayerTable.PLAYER_SLOTS + 1];

        for (int owner = 0; owner < PlayerTable.PLAYER_SLOTS; owner++) {
            starts[owner + 1] = starts[owner] + counts[owner];
        }

        int[] sorted = new int[drawable];
        int[] fill = starts.clone();

        for (int entry = 0; entry < drawable; entry++) {
            sorted[fill[owners[entry]]++] = indices[entry];
        }

        // The nearest palette entries of the fixed dot colors, searched once per pass.
        this.ownUnitColor  = assets.getNearestPaletteColor(OWN_UNIT_TARGET);
        this.resourceColor = assets.getNearestPaletteColor(RESOURCE_TARGET);

        for (int owner = PlayerTable.PLAYER_SLOTS - 1; owner >= 0; owner--) {
            if (!replay && owner == localPlayer) {
                continue;
            }

            this.drawPlayerDots(view, assets, units, pool, sorted, starts[owner], starts[owner + 1], owner, replay, viewMask, revealed, false);
        }

        if (!replay && localPlayer >= 0 && localPlayer < PlayerTable.PLAYER_SLOTS) {
            this.drawPlayerDots(view,
                                assets,
                                units,
                                pool,
                                sorted,
                                starts[localPlayer], starts[localPlayer + 1],
                                localPlayer,
                                false,
                                viewMask,
                                false,
                                true);
        }
    }

    /**
     * Draws one owner range and advances the local blinking counters.
     *
     * @param view       the game view
     * @param assets     the tileset interface assets
     * @param units      the unit type definitions
     * @param pool       the unit pool
     * @param sorted     the owner-sorted pool indices
     * @param from       the first index in the owner range
     * @param to         the index after the owner range
     * @param owner      the owning player
     * @param replay     whether replay vision rules apply
     * @param viewMask   the players whose vision the view shows
     * @param revealed   whether the replay reveals the entire map, which shows every unit
     * @param localBlock whether this is the local-player pass
     * @throws IllegalStateException if the unit has no sprite
     */
    private void drawPlayerDots(@NotNull ScrGameView view,
                                @NotNull InterfaceAssets assets,
                                @NotNull ScrUnitTypeView units,
                                @NotNull UnitPool pool,
                                int @NotNull [] sorted,
                                int from, int to,
                                int owner,
                                boolean replay,
                                int viewMask,
                                boolean revealed,
                                boolean localBlock) {
        int color = 0;
        boolean ownerColorKnown = false;

        for (int entry = from; entry < to; entry++) {
            int index = sorted[entry];
            Unit unit = pool.getUnit(index);
            Sprite sprite = unit.requireSprite();
            int type = unit.getUnitId();
            int seen = sprite.getVisibilityFlags();

            if ((unit.getStatus() & (UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED)) != 0) {
                seen &= unit.getDetectionFlags();
            }

            if ((seen & viewMask) == 0 && !revealed) {
                continue;
            }

            int dotColor;

            if (localBlock) {
                // The local player's own units: nearest RGB(0,227,43), palette index 117; a blinking unit takes its blink color in the on phase.
                dotColor = this.ownUnitColor;

                int[] blink = this.blinks.get(index);

                if (blink != null) {
                    if (this.blinkPhase) {
                        dotColor = assets.getPaletteColor(blink[1]);
                    }

                    if (--blink[0] <= 0) {
                        this.blinks.remove(index);
                    }
                }
            } else if (owner >= PlayerTable.PLAYABLE_PLAYER_SLOTS &&
                       (units.isMineralField(type) || type == ScrUnitIds.RESOURCE_VESPENE_GEYSER)) {
                // Neutral mineral fields and geysers: nearest RGB(0,255,255), palette index 128.
                dotColor = this.resourceColor;
            } else {
                if (!ownerColorKnown) {
                    color           = this.getOwnerColor(view, assets, owner, replay);
                    ownerColorKnown = true;
                }

                dotColor = color;
            }

            this.drawRectangle(sprite.getX(), sprite.getY(), units, type, (units.getFlags(type) & UnitTypeFlags.BUILDING) != 0, dotColor);
        }
    }

    /**
     * Draws eligible remembered buildings after the live unit pass.
     *
     * @param view   the game view
     * @param assets the tileset interface assets
     * @param vision the vision of the local view
     * @param ghosts the unseen remembered buildings
     * @throws NullPointerException if the world was built without game data
     */
    private void drawGhosts(@NotNull ScrGameView view,
                            @NotNull InterfaceAssets assets,
                            @NotNull LocalVision vision,
                            @NotNull List<RememberedBuildings.Ghost> ghosts) {
        ScrUnitTypeView units = view.requireGameData().getUnitTypes();
        boolean replay = vision.isReplayOrObserver();

        for (RememberedBuildings.Ghost ghost : ghosts) {
            int type = ghost.getType();

            if (type >= ScrUnitIds.SPECIAL_FLOOR_MISSILE_TRAP && type <= ScrUnitIds.SPECIAL_RIGHT_WALL_FLAME_TRAP) {
                continue;
            }

            if (replay && RememberedBuildings.hasUnexploredTile(view.getMap(), units, ghost, vision.getViewMask())) {
                continue;
            }

            int color;

            if (units.isMineralField(type) || type == ScrUnitIds.RESOURCE_VESPENE_GEYSER) {
                color = assets.getNearestPaletteColor(RESOURCE_TARGET);
            } else {
                color = this.getOwnerColor(view, assets, ghost.getOwner(), replay);
            }

            this.drawRectangle(ghost.getX(), ghost.getY(), units, type, true, color);
        }
    }


    /**
     * Chooses the live player minimap color in player mode ({@link PlayerColorRows#getMinimapPlayerColor}), a team color in replay modes, or the
     * alliance color during play.
     *
     * @param view   the game view
     * @param assets the tileset interface assets
     * @param owner  the owning player
     * @param replay whether replay vision rules apply
     * @return the packed {@code 0xRRGGBBAA} color
     */
    private int getOwnerColor(@NotNull ScrGameView view, @NotNull InterfaceAssets assets, int owner, boolean replay) {
        Player player = owner < PlayerTable.PLAYER_SLOTS ? view.getPlayerTable().getPlayer(owner) : null;

        if (this.colorMode == PLAYER_COLOR_MODE) {
            PlayerColorRows rows = owner < PlayerTable.PLAYER_SLOTS ? view.getPlayerTable().getPlayerColorRows() : null;

            if (rows != null) {
                return rows.getMinimapPlayerColor(owner, assets::getPaletteColor);
            }

            return assets.getPaletteColor(assets.getMinimapPlayerIndex(player != null ? player.getColor() : owner));
        }

        if (replay) {
            return getTeamColor(player);
        }

        PlayerRelations relations = view.getPlayerTable().getPlayerRelations();
        boolean allied = relations != null && owner < PlayerTable.PLAYER_SLOTS && relations.getAlliance(this.localPlayer, owner) != 0;

        return assets.getNearestPaletteColor(allied ? ALLY_TARGET : ENEMY_TARGET);
    }


    /**
     * Clips the unit placement rectangle before drawing its minimap dot.
     *
     * @param spriteX  the map horizontal position
     * @param spriteY  the map vertical position
     * @param units    the unit type definitions
     * @param type     the unit type identifier
     * @param building whether the type is a building
     * @param color    the packed color
     */
    private void drawRectangle(int spriteX, int spriteY, @NotNull ScrUnitTypeView units, int type, boolean building, int color) {
        int placementWidth = units.getPlacementWidth(type) & PLACEMENT_SIZE_MASK;
        int placementHeight = units.getPlacementHeight(type) & PLACEMENT_SIZE_MASK;
        int halfWidth = placementWidth >> 1;
        int halfHeight = placementHeight >> 1;
        int x = Math.max((short) spriteX, halfWidth);
        int y = Math.max((short) spriteY, halfHeight);
        int firstColumn = ((x - halfWidth) << this.zoom) / this.divisor >> TerrainTileset.TILE_SHIFT;
        int firstRow = ((y - halfHeight) << this.zoom) / this.divisor >> TerrainTileset.TILE_SHIFT;
        int dotWidth = ((placementWidth << this.zoom) / this.divisor) / TerrainTileset.TILE_SIZE;
        int dotHeight = ((placementHeight << this.zoom) / this.divisor) / TerrainTileset.TILE_SIZE;

        dotWidth  = dotWidth < MINIMUM_DOT_SIZE ? MINIMUM_DOT_SIZE : building ? Math.min(dotWidth, MAXIMUM_BUILDING_DOT_SIZE) : MINIMUM_DOT_SIZE;
        dotHeight = dotHeight < MINIMUM_DOT_SIZE ? MINIMUM_DOT_SIZE : building ? Math.min(dotHeight, MAXIMUM_BUILDING_DOT_SIZE) : MINIMUM_DOT_SIZE;

        int lastColumn = firstColumn + dotWidth - 1;
        int lastRow = firstRow + dotHeight - 1;

        firstColumn = firstColumn < 0 ? 0 : Math.min(firstColumn, this.width - MINIMUM_DOT_SIZE);
        lastColumn  = lastColumn < 1 ? 1 : Math.min(lastColumn, this.width - 1);
        firstRow    = firstRow < 0 ? 0 : Math.min(firstRow, this.height - MINIMUM_DOT_SIZE);
        lastRow     = lastRow < 1 ? 1 : Math.min(lastRow, this.height - 1);

        this.drawRendererDot(firstColumn, firstRow, lastColumn - firstColumn + 1, lastRow - firstRow + 1, color);
    }

    /**
     * Scales a clipped bitmap rectangle into the dot canvas.
     * The border is drawn first without replacing matching dot-color pixels, then the dot fills its rectangle.
     *
     * @param x      the left bitmap column
     * @param y      the top bitmap row
     * @param width  the width in bitmap pixels
     * @param height the height in bitmap pixels
     * @param color  the packed color
     * @throws IllegalStateException if a later image took the dot layer over
     */
    private void drawRendererDot(int x, int y, int width, int height, int color) {
        int[] dots = this.dotLayer;

        if (dots == null) {
            throw Exceptions.newIllegalStateException("The minimap image has no dot layer.");
        }

        float dotScale = this.scale * ((float) this.mapWidth / this.width);
        int dotX = (int) (x * dotScale);
        int dotY = (int) (y * dotScale);
        int dotWidth = (int) (width * dotScale);
        int dotHeight = (int) (height * dotScale);

        if (this.scale > 1) {
            int border = (int) (dotScale * DOT_BORDER_WIDTH);
            int borderX = dotX - border;
            int borderY = dotY - border;
            int borderWidth = dotWidth + BORDER_SIDES * border;
            int borderHeight = dotHeight + BORDER_SIDES * border;

            if (borderX < 0) {
                borderX = 0;
                borderWidth -= border;
            }

            if (borderY < 0) {
                borderY = dotY;
                borderHeight -= border;
            }

            int right = Math.min(borderX + borderWidth - 1, this.textureWidth - 1);
            int bottom = Math.min(borderY + borderHeight - 1, this.textureHeight - 1);

            this.updateDotRows(borderY, bottom);

            for (int row = borderY; row <= bottom; row++) {
                for (int column = borderX; column <= right; column++) {
                    int pixel = row * this.textureWidth + column;

                    if (dots[pixel] != color) {
                        dots[pixel] = DOT_BORDER;
                    }
                }
            }
        }

        int right = Math.min(dotX + dotWidth - 1, this.textureWidth - 1);
        int bottom = Math.min(dotY + dotHeight - 1, this.textureHeight - 1);

        this.updateDotRows(Math.max(0, dotY), bottom);

        for (int row = Math.max(0, dotY); row <= bottom; row++) {
            for (int column = Math.max(0, dotX); column <= right; column++) {
                dots[row * this.textureWidth + column] = color;
            }
        }
    }

    /**
     * Accumulates the clipped canvas rows changed by the dot pass.
     *
     * @param top    the first changed row
     * @param bottom the last changed row
     */
    private void updateDotRows(int top, int bottom) {
        if (top > bottom) {
            return;
        }

        this.dotTop    = Math.min(this.dotTop, Math.max(0, top));
        this.dotBottom = Math.max(this.dotBottom, bottom);
    }
}
