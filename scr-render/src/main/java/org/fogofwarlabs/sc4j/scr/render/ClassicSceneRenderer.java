package org.fogofwarlabs.sc4j.scr.render;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.image.AnimFile;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageSheet;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * Draws the scene with the classic palette and GRP renderer, dispatching image modifiers.
 * <p>
 * Selection circles recolor GRP indices {@code 0..7} with the {@code game/tselect.pcx} group of the relation; health bars use the
 * {@code game/thpbar.pcx} colors; the fog of war darkens the world as described by {@link OverlayPass}.
 */
public final class ClassicSceneRenderer implements SceneRenderer, AutoCloseable {
    /**
     * The opacity of the building placement ghost.
     */
    private static final float PLACEMENT_GHOST_OPACITY = 0.5f;
    /**
     * The weights of the hallucination tint's luminance: {@code (77, 151, 28) / 255}.
     */
    private static final float LUMINANCE_RED = 0.30196078f;
    private static final float LUMINANCE_GREEN = 0.59215686f;
    private static final float LUMINANCE_BLUE = 0.10980392f;
    /**
     * The hallucination tint applied to the luminance.
     */
    private static final float HALLUCINATION_RED = 0.75f;
    private static final float HALLUCINATION_GREEN = 1.125f;
    private static final float HALLUCINATION_BLUE = 2.65f;
    /**
     * The positions of the left edge, the top edge, the width and the height in {@link SceneRenderer#getFrameRectangle}'s result.
     */
    private static final int RECTANGLE_LEFT = 0;
    private static final int RECTANGLE_TOP = 1;
    private static final int RECTANGLE_WIDTH = 2;
    private static final int RECTANGLE_HEIGHT = 3;
    /**
     * The top and bottom outline rows, which the vertical outline sides leave out.
     */
    private static final int OUTLINE_ROWS = 2;
    /**
     * The mask of the warp texture frame WORD in the modifier data of draw function {@code 12}.
     */
    private static final int WARP_TEXTURE_FRAME_MASK = 0xFFFF;
    /**
     * The mask of an unsigned color channel byte.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The positions of the frame, the relation and the flip in {@link #createTextureKey}.
     */
    private static final int KEY_FRAME_SHIFT = 16;
    private static final int KEY_RELATION_SHIFT = 32;
    private static final int KEY_FLIP_SHIFT = 34;

    private static long createTextureKey(int imageIndex, int frameIndex, int relation, boolean flipped) {
        return (long) imageIndex | (long) frameIndex << KEY_FRAME_SHIFT | (long) relation << KEY_RELATION_SHIFT |
               (flipped ? 1L << KEY_FLIP_SHIFT : 0);
    }


    private final @NotNull WorldRenderer renderer;
    private final @NotNull ScrResourceProvider resources;
    private final int tileset;

    private final @NotNull OverlayPass overlay;
    private @Nullable OverlayAssets overlayAssets;

    /**
     * The textures of draw function {@code 13} by image, frame, relation and flip ({@link #createTextureKey}).
     */
    private final @NotNull FrameTextureCache relationTextures;
    /**
     * Baked-remap frame textures by image, frame, hallucination and flip ({@link #createTextureKey}).
     */
    private final @NotNull FrameTextureCache bakedTextures;

    private final @NotNull HealthBarLayout barLayout;
    private final HealthBarLayout.@NotNull RectangleSink barSink;

    private float barLeft;
    private float barTop;

    private boolean closed;

    /**
     * Creates a {@link ClassicSceneRenderer} instance on the current OpenGL context.
     *
     * @param renderer  the classic world renderer that owns the map assets and the world frame
     * @param resources the resource provider of the installation
     * @param tileset   the map tileset index
     */
    public ClassicSceneRenderer(@NotNull WorldRenderer renderer, @NotNull ScrResourceProvider resources, int tileset) {
        this.renderer  = renderer;
        this.resources = resources;
        this.tileset   = tileset;

        this.overlay       = new OverlayPass(renderer);
        this.overlayAssets = null;

        this.relationTextures = new FrameTextureCache();
        this.bakedTextures    = new FrameTextureCache();

        this.barLayout = new HealthBarLayout();
        this.barSink   = (left, top, width, height, colorIndex) ->
                this.overlay.fillRectangle(this.barLeft + left,
                                           this.barTop + top,
                                           width,
                                           height,
                                           this.requireOverlayAssets().getBarColor(colorIndex),
                                           this.requireOverlayAssets().getBarPaletteIndex(colorIndex));

        this.barLeft = 0;
        this.barTop  = 0;

        this.closed = false;
    }

    /**
     * Checks whether this backend draws with a world renderer.
     *
     * @param renderer a world renderer
     * @return {@code true} if this backend draws with the given world renderer, {@code false} otherwise
     */
    public boolean isDrawingWith(@NotNull WorldRenderer renderer) {
        return this.renderer == renderer;
    }

    /**
     * Sets the game context's player color rows (eight {@code tunit.pcx} palette indices per player), read at every draw for the classic remapping of
     * palette indices {@code 8..15}. Player colors {@link #PLAYER_COLOR}{@code + player} then draw with them; an EUD write to the rows recolors the
     * player's units.
     *
     * @param rows the twelve rows of eight palette indices
     */
    @Override
    public void setPlayerColorRows(byte @NotNull [] rows) {
        this.overlay.end(false);
        this.renderer.setPlayerColorRows(rows);
    }

    private @NotNull OverlayAssets requireOverlayAssets() {
        if (this.overlayAssets == null) {
            throw Exceptions.newIllegalStateException("Overlay assets are loaded by the first overlay draw.");
        }

        return this.overlayAssets;
    }

    private @NotNull OverlayAssets loadOverlayAssets() throws IOException {
        if (this.overlayAssets == null) {
            this.overlayAssets = OverlayAssets.load(this.resources, this.tileset);
        }

        return this.overlayAssets;
    }


    /**
     * Draws the visible terrain from the current game tile identifiers, including creep edges.
     *
     * @param tileIdentifiers the current row-major tile identifier for each tile index of the loaded map extent
     * @throws IOException if a tile identifier is outside the installed assets
     */
    @Override
    public void drawTerrain(@NotNull IntUnaryOperator tileIdentifiers) throws IOException {
        this.overlay.end(false);
        this.renderer.drawTerrain(tileIdentifiers);
    }

    /**
     * Draws the visible terrain from the current game tile identifiers, including creep edges, with megatile pictures that replace installed ones
     * ({@link WorldRenderer#drawTerrain(IntUnaryOperator, IntFunction)}).
     *
     * @param tileIdentifiers the current row-major tile identifier for each tile index of the loaded map extent
     * @param megatiles       the picture of a megatile index, the same array while it is unchanged, or {@code null} for the installed picture
     * @throws IOException if a tile identifier is outside the installed assets
     */
    @Override
    public void drawTerrain(@NotNull IntUnaryOperator tileIdentifiers, @NotNull IntFunction<byte @Nullable []> megatiles) throws IOException {
        this.overlay.end(false);
        this.renderer.drawTerrain(tileIdentifiers, megatiles);
    }

    /**
     * Draws one image of a sprite.
     *
     * @param imageIndex   the image identifier
     * @param frameIndex   the image frame
     * @param modifier     the image draw modifier
     * @param modifierData the image modifier data
     * @param playerColor  the owner's player color index {@code 0..15}, or {@link #PLAYER_COLOR}{@code + owner}
     * @param worldX       the canvas center world coordinate
     * @param worldY       the canvas center world coordinate
     * @param flipped      whether the image is horizontally flipped
     * @throws IOException if the image cannot be loaded or its modifier is not drawn by this backend
     */
    @Override
    public void drawImage(int imageIndex, int frameIndex, int modifier, int modifierData, int playerColor, int worldX, int worldY, boolean flipped) throws IOException {
        this.overlay.end(false);

        switch (modifier) {
            // Health bars and selection circles are submitted by the scene extraction.
            case DRAW_FUNCTION_NORMAL,
                 DRAW_FUNCTION_NORMAL_UNCHECKED,
                 DRAW_FUNCTION_PLAYER_COLOR -> this.drawRemappedImage(imageIndex, frameIndex, 0, playerColor, worldX, worldY, flipped);
            case DRAW_FUNCTION_UPDATE_RECTANGLE -> this.drawUpdateRectangle(imageIndex, frameIndex, worldX, worldY, flipped);
            case DRAW_FUNCTION_UNSEEN_CLOAKING,
                 DRAW_FUNCTION_UNSEEN_DECLOAKING -> this.renderer.drawUnseenCloakingImage(imageIndex,
                                                                                          frameIndex,
                                                                                          playerColor,
                                                                                          modifierData & MODIFIER_DATA_MASK,
                                                                                          worldX,
                                                                                          worldY,
                                                                                          flipped);
            case DRAW_FUNCTION_UNSEEN_CLOAKED -> this.renderer.drawUnseenCloakedImage(imageIndex, frameIndex, worldX, worldY, flipped);
            case DRAW_FUNCTION_CLOAKING,
                 DRAW_FUNCTION_DECLOAKING -> this.renderer.drawCloakingImage(imageIndex,
                                                                             frameIndex,
                                                                             playerColor,
                                                                             modifierData & MODIFIER_DATA_MASK,
                                                                             worldX,
                                                                             worldY,
                                                                             flipped);
            case DRAW_FUNCTION_CLOAKED -> this.renderer.drawCloakedImage(imageIndex, frameIndex, playerColor, worldX, worldY, flipped);
            case DRAW_FUNCTION_DISTORTION -> this.renderer.drawDistortionImage(imageIndex, frameIndex, worldX, worldY, flipped);
            case DRAW_FUNCTION_HALLUCINATION -> {
                if (this.renderer.hasBakedRemap(imageIndex)) {
                    this.drawBakedImage(imageIndex, frameIndex, true, worldX, worldY, flipped);
                } else {
                    this.renderer.drawHallucinatedImage(imageIndex, frameIndex, playerColor, worldX, worldY, flipped);
                }
            }
            case DRAW_FUNCTION_REMAPPING -> this.drawRemappedImage(imageIndex, frameIndex, 0, playerColor, worldX, worldY, flipped);
            case DRAW_FUNCTION_SHADOW -> this.drawShadowImage(imageIndex, frameIndex, -1, worldX, worldY, flipped);
            case DRAW_FUNCTION_WARP_TEXTURE -> this.renderer.drawWarpTexture(imageIndex,
                                                                             frameIndex,
                                                                             modifierData & WARP_TEXTURE_FRAME_MASK,
                                                                             worldX,
                                                                             worldY,
                                                                             flipped);
            case DRAW_FUNCTION_WARP_FLASH -> this.renderer.drawBrightenImage(imageIndex, frameIndex, modifierData, worldX, worldY, flipped);
            default -> throw Exceptions.newIOException("Runtime image modifier {0} is not rendered.", modifier);
        }
    }

    /**
     * Draws one image of a sprite with the frames of an override sheet instead of the image's installed graphics, with the image's draw function.
     * The baked-remap and warp-texture draws fall back to the palette draw of the sheet's frame.
     *
     * @param sheet        the indexed frames the image draws, their canvas centered on the image position
     * @param imageIndex   the image identifier
     * @param frameIndex   the image frame, a frame of the sheet
     * @param modifier     the image draw modifier
     * @param modifierData the image modifier data
     * @param playerColor  the owner's player color index {@code 0..15}, or {@link #PLAYER_COLOR}{@code + owner}
     * @param worldX       the canvas center world coordinate
     * @param worldY       the canvas center world coordinate
     * @param flipped      whether the image is horizontally flipped
     * @throws IOException if the frame cannot be drawn or the modifier is not drawn by this backend
     */
    @Override
    public void drawImageSheet(@NotNull ScrImageSheet sheet,
                               int imageIndex, int frameIndex,
                               int modifier, int modifierData, int playerColor,
                               int worldX, int worldY, boolean flipped) throws IOException {
        this.renderer.setImageSheet(sheet.getFrame(frameIndex));

        try {
            this.drawImage(imageIndex, 0, modifier, modifierData, playerColor, worldX, worldY, flipped);
        } finally {
            this.renderer.setImageSheet(null);
        }
    }

    /**
     * Draws a building placement ghost: one image frame with RGB {@code (1, 1, 1)} at alpha {@code 0.5}, colored with the local player's team color,
     * without a shadow.
     *
     * @param imageIndex  the unit's image identifier
     * @param frameIndex  the image frame
     * @param playerColor the local player's color index
     * @param worldX      the canvas center world coordinate
     * @param worldY      the canvas center world coordinate
     * @throws IOException if the image cannot be loaded
     */
    @Override
    public void drawPlacementGhost(int imageIndex, int frameIndex, int playerColor, int worldX, int worldY) throws IOException {
        this.overlay.end(false);
        this.renderer.drawTranslucentImage(imageIndex, frameIndex, playerColor, worldX, worldY, false, PLACEMENT_GHOST_OPACITY);
    }

    /**
     * Draws an image of draw function {@code 9} with its baked remap when available, or with the normal image renderer otherwise. This backend
     * ignores the color remapping argument.
     *
     * @param imageIndex     the image identifier
     * @param frameIndex     the image frame
     * @param colorRemapping the image's color remapping ({@code 1} ofire through {@code 7} green), ignored
     * @param playerColor    the owner's player color index
     * @param worldX         the canvas center world coordinate
     * @param worldY         the canvas center world coordinate
     * @param flipped        whether the image is horizontally flipped
     * @throws IOException if the image cannot be loaded
     */
    @Override
    public void drawRemappedImage(int imageIndex, int frameIndex, int colorRemapping, int playerColor, int worldX, int worldY, boolean flipped) throws IOException {
        this.overlay.end(false);

        if (this.renderer.hasBakedRemap(imageIndex)) {
            this.drawBakedImage(imageIndex, frameIndex, false, worldX, worldY, flipped);
        } else {
            this.renderer.drawImage(imageIndex, frameIndex, playerColor, worldX, worldY, flipped);
        }
    }

    /**
     * Draws the baked-remap diffuse frame with straight alpha, applying the hallucination tint when requested.
     *
     * @param imageIndex   the image identifier
     * @param frameIndex   the image frame
     * @param hallucinated whether the hallucination tint is applied
     * @param worldX       the canvas center world coordinate
     * @param worldY       the canvas center world coordinate
     * @param flipped      whether the image is horizontally flipped
     * @throws IOException if the image cannot be loaded or the frame is outside its SD anim
     */
    private void drawBakedImage(int imageIndex, int frameIndex, boolean hallucinated, int worldX, int worldY, boolean flipped) throws IOException {
        ClassicFrame canvas = this.renderer.decodeImageFrame(imageIndex, frameIndex);

        if (canvas == null) {
            return;
        }

        AnimFile.Entry entry = this.renderer.getSdEntry(imageIndex);

        if (frameIndex >= entry.getFrameCount()) {
            throw Exceptions.newIOException("Image {0} frame {1} is outside its SD anim.", imageIndex, frameIndex);
        }

        int frameWidth = entry.getFrameWidth(frameIndex);
        int frameHeight = entry.getFrameHeight(frameIndex);

        if (frameWidth <= 0 || frameHeight <= 0) {
            return;
        }

        long key = createTextureKey(imageIndex, frameIndex, hallucinated ? 1 : 0, flipped);
        int texture = this.bakedTextures.getTexture(key);

        if (texture == 0) {
            byte[] atlas = this.renderer.decodeSdDiffuse(imageIndex);
            int atlasWidth = entry.getLayerWidth(RenderAssets.DIFFUSE_LAYER);
            byte[] pixels = new byte[frameWidth * frameHeight * OverlayPass.RGBA_BYTES];
            int atlasX = entry.getFrameX(frameIndex);
            int atlasY = entry.getFrameY(frameIndex);

            for (int pixelRow = 0; pixelRow < frameHeight; pixelRow++) {
                for (int pixelColumn = 0; pixelColumn < frameWidth; pixelColumn++) {
                    int source = ((atlasY + pixelRow) * atlasWidth + atlasX + pixelColumn) * OverlayPass.RGBA_BYTES;
                    int target = (pixelRow * frameWidth + (flipped ? frameWidth - 1 - pixelColumn : pixelColumn)) * OverlayPass.RGBA_BYTES;
                    float red = (atlas[source] & BYTE_MASK) / OverlayPass.CHANNEL_MAXIMUM;
                    float green = (atlas[source + OverlayPass.RGBA_GREEN] & BYTE_MASK) / OverlayPass.CHANNEL_MAXIMUM;
                    float blue = (atlas[source + OverlayPass.RGBA_BLUE] & BYTE_MASK) / OverlayPass.CHANNEL_MAXIMUM;

                    if (hallucinated) {
                        float luminance = red * LUMINANCE_RED + green * LUMINANCE_GREEN + blue * LUMINANCE_BLUE;

                        red   = Math.min(1, HALLUCINATION_RED * luminance);
                        green = Math.min(1, HALLUCINATION_GREEN * luminance);
                        blue  = Math.min(1, HALLUCINATION_BLUE * luminance);
                    }

                    pixels[target]                          = (byte) Math.round(red * OverlayPass.CHANNEL_MAXIMUM);
                    pixels[target + OverlayPass.RGBA_GREEN] = (byte) Math.round(green * OverlayPass.CHANNEL_MAXIMUM);
                    pixels[target + OverlayPass.RGBA_BLUE]  = (byte) Math.round(blue * OverlayPass.CHANNEL_MAXIMUM);
                    pixels[target + OverlayPass.RGBA_ALPHA] = atlas[source + OverlayPass.RGBA_ALPHA];
                }
            }

            texture = OverlayPass.uploadRgba(pixels, frameWidth, frameHeight);
            this.bakedTextures.putTexture(key, texture);
        }

        int width = canvas.getWidth();
        int xOffset = entry.getFrameXOffset(frameIndex);
        float canvasLeft = worldX - (flipped ? width - width / RenderAssets.HALF_DIVISOR : width / RenderAssets.HALF_DIVISOR);
        float left = canvasLeft + (flipped ? width - xOffset - frameWidth : xOffset);
        float top = worldY - canvas.getHeight() / RenderAssets.HALF_DIVISOR + entry.getFrameYOffset(frameIndex);

        this.overlay.begin(false);
        this.overlay.drawTexture(texture, left, top, frameWidth, frameHeight, OverlayPass.NO_PALETTE_INDEX);
    }

    /**
     * Draws an image of draw function {@code 10} (a shadow) at its SD coverage. Without Shadow Stacking the draw builder dims the shadow of a
     * warping-in sprite: when the image's {@code images.rel} type is {@link #SHADOW_RELATION} and the sprite's primary image has draw function
     * {@code 12}, the shadow's multiply alpha is that image's data (the warp texture frame) over the frame count of the SD Warp Texture anim
     * ({@link SceneRenderer#getShadowWarpAlpha}).
     *
     * @param imageIndex       the image identifier
     * @param frameIndex       the image frame
     * @param primaryWarpFrame the primary image's modifier data when its draw function is {@code 12}, else {@code -1}
     * @param worldX           the canvas center world coordinate
     * @param worldY           the canvas center world coordinate
     * @param flipped          whether the image is horizontally flipped
     * @throws IOException if the image cannot be loaded
     */
    @Override
    public void drawShadowImage(int imageIndex, int frameIndex, int primaryWarpFrame, int worldX, int worldY, boolean flipped) throws IOException {
        this.overlay.end(false);

        float warpAlpha = SceneRenderer.getShadowWarpAlpha(this.renderer.getImageRelationType(imageIndex),
                                                           primaryWarpFrame,
                                                           this.renderer.getSdEntry(WARP_TEXTURE_IMAGE).getFrameCount());

        this.renderer.drawShadowImage(imageIndex, frameIndex, worldX, worldY, flipped, warpAlpha);
    }

    /**
     * Draws the frame rectangle of draw function {@code 15} as a one-pixel outline in {@link #UPDATE_RECTANGLE_COLOR}. The frame image itself is not
     * drawn.
     *
     * @param imageIndex the image identifier
     * @param frameIndex the image frame
     * @param worldX     the canvas center world coordinate
     * @param worldY     the canvas center world coordinate
     * @param flipped    whether the image is horizontally flipped
     * @throws IOException if the image cannot be loaded
     */
    @Override
    public void drawUpdateRectangle(int imageIndex, int frameIndex, int worldX, int worldY, boolean flipped) throws IOException {
        ClassicFrame frame = this.renderer.decodeImageFrame(imageIndex, frameIndex);
        float[] rectangle = frame == null ? null : SceneRenderer.getFrameRectangle(frame, worldX, worldY, flipped);

        if (rectangle == null) {
            return;
        }

        float left = rectangle[RECTANGLE_LEFT];
        float top = rectangle[RECTANGLE_TOP];
        float width = rectangle[RECTANGLE_WIDTH];
        float height = rectangle[RECTANGLE_HEIGHT];

        this.overlay.begin(false);
        this.overlay.fillRectangle(left, top, width, 1, UPDATE_RECTANGLE_COLOR, OverlayPass.NO_PALETTE_INDEX);
        this.overlay.fillRectangle(left, top + height - 1, width, 1, UPDATE_RECTANGLE_COLOR, OverlayPass.NO_PALETTE_INDEX);
        this.overlay.fillRectangle(left, top + 1, 1, height - OUTLINE_ROWS, UPDATE_RECTANGLE_COLOR, OverlayPass.NO_PALETTE_INDEX);
        this.overlay.fillRectangle(left + width - 1, top + 1, 1, height - OUTLINE_ROWS, UPDATE_RECTANGLE_COLOR, OverlayPass.NO_PALETTE_INDEX);
    }

    /**
     * Draws a selection circle centered at a sprite's circle position; the caller draws it at the place the circle keeps among the images of the
     * selected sprite.
     *
     * @param circleImage the circle image: {@code 561 + sprites.dat selection circle} (dashed circles follow at {@code 571})
     * @param relation    {@link #RELATION_OWN}, {@link #RELATION_NEUTRAL} or {@link #RELATION_ENEMY}
     * @param worldX      the sprite x coordinate
     * @param worldY      the sprite y coordinate plus the {@code sprites.dat} selection-circle vertical offset
     * @throws IOException if the image is not a selection circle image or cannot be loaded
     */
    @Override
    public void drawSelectionCircle(int circleImage, int relation, int worldX, int worldY) throws IOException {
        int circle = circleImage - OverlayAssets.FIRST_CIRCLE_IMAGE;

        if (circle < 0 || circle >= OverlayAssets.CIRCLE_IMAGE_COUNT) {
            throw Exceptions.newIOException("Invalid selection circle {0}", circleImage);
        }

        this.drawRelationImage(circleImage, 0, relation, worldX, worldY, false);
    }

    /**
     * Draws an image with draw function {@code 13}: the frame's shape in one solid color, the relation color of the player in the image's modifier
     * data. Selection circles are such images, and EUD maps can give any image this draw function.
     *
     * @param imageIndex the image identifier
     * @param frameIndex the image frame
     * @param relation   {@link #RELATION_OWN}, {@link #RELATION_NEUTRAL} or {@link #RELATION_ENEMY}
     * @param worldX     the canvas center world coordinate
     * @param worldY     the canvas center world coordinate
     * @param flipped    whether the image is horizontally flipped
     * @throws IOException if the image cannot be loaded or the relation is invalid
     */
    @Override
    public void drawRelationImage(int imageIndex, int frameIndex, int relation, int worldX, int worldY, boolean flipped) throws IOException {
        if (relation < RELATION_OWN || relation > RELATION_ENEMY) {
            throw Exceptions.newIOException("Invalid relation {0}", relation);
        }

        ClassicFrame frame = this.renderer.decodeImageFrame(imageIndex, frameIndex);

        if (frame == null) {
            return;
        }

        OverlayAssets overlayAssets = this.loadOverlayAssets();
        int width = frame.getWidth();
        int height = frame.getHeight();
        long key = createTextureKey(imageIndex, frameIndex, relation, flipped);
        int texture = this.relationTextures.getTexture(key);

        if (texture == 0) {
            int color = overlayAssets.getRelationColor(relation);
            byte[] coverage = frame.getCoverage();
            byte[] pixels = new byte[width * height * OverlayPass.RGBA_BYTES];

            for (int pixelRow = 0; pixelRow < height; pixelRow++) {
                for (int pixelColumn = 0; pixelColumn < width; pixelColumn++) {
                    if (coverage[pixelRow * width + pixelColumn] == 0) {
                        continue;
                    }

                    int target = (pixelRow * width + (flipped ? width - 1 - pixelColumn : pixelColumn)) * OverlayPass.RGBA_BYTES;

                    pixels[target]                          = (byte) (color >>> OverlayPass.RGB_RED_SHIFT);
                    pixels[target + OverlayPass.RGBA_GREEN] = (byte) (color >>> OverlayPass.RGB_GREEN_SHIFT);
                    pixels[target + OverlayPass.RGBA_BLUE]  = (byte) color;
                    pixels[target + OverlayPass.RGBA_ALPHA] = OverlayPass.OPAQUE_ALPHA;
                }
            }

            texture = OverlayPass.uploadRgba(pixels, width, height);
            this.relationTextures.putTexture(key, texture);
        }

        // The image's palette index is recorded as for any classic image (draw function 13 draws it in the sprite's image
        // list): remaps, shadows and cloak refraction drawn over it later start from it, not the terrain below.
        float left = worldX - (flipped ? width - width / RenderAssets.HALF_DIVISOR : width / RenderAssets.HALF_DIVISOR);
        float top = worldY - height / RenderAssets.HALF_DIVISOR;

        this.overlay.begin(false);
        this.overlay.drawTexture(texture, left, top, width, height, overlayAssets.getRelationPaletteIndex(relation));
    }

    /**
     * Draws a health bar centered at the place of its health bar image; the caller draws it at the place the image keeps among the images of the
     * selected sprite.
     *
     * @param worldX           the bar center world coordinate: the sprite x coordinate plus the image's horizontal offset
     * @param worldY           the bar center world coordinate: the sprite y coordinate plus the image's vertical offset
     * @param barWidth         the {@code sprites.dat} health bar width
     * @param hitPointsPercent the hit point percentage
     * @param shieldPercent    the shield percentage, or a negative value when the unit type has no shields
     * @param energyPercent    the energy or removal-timer percentage, or a negative value without an energy row
     * @throws IOException if the color tables cannot be loaded
     */
    @Override
    public void drawHealthBar(int worldX, int worldY, int barWidth, int hitPointsPercent, int shieldPercent, int energyPercent) throws IOException {
        // The bar sink reads the bar colors of the overlay assets.
        this.loadOverlayAssets();
        this.barLayout.configure(barWidth, hitPointsPercent, shieldPercent, energyPercent);

        this.barLeft = worldX - this.barLayout.getWidth() / RenderAssets.HALF_DIVISOR;
        this.barTop  = worldY - this.barLayout.getHeight() / RenderAssets.HALF_DIVISOR;

        this.overlay.begin(false);
        this.barLayout.emit(this.barSink);
    }

    /**
     * Darkens the world by the fog of war with the game's level of visible tiles; this is the last world drawing of a scene.
     * <p>
     * An observer slot darkens through the observer table, whose alpha stops at {@code 218}, so unexplored ground stays faintly visible. The replay
     * viewer is not an observer (its local human is a player slot).
     *
     * @param tileFlags    the current tile flag DWORD for each row-major tile index (low byte: not visible per player, second byte: not explored per
     *                     player)
     * @param visionMask   the eight-bit mask of players whose vision is shown
     * @param visibleLevel the fog level of visible tiles, the game's visible level BYTE (default {@code 31}); explored tiles get half of it
     * @param observer     whether the local human is an observer slot ({@code 0x80..0x83})
     */
    @Override
    public void drawFog(@NotNull IntUnaryOperator tileFlags, int visionMask, int visibleLevel, boolean observer) {
        this.overlay.begin(false);
        this.overlay.drawFog(tileFlags, this.renderer.getMapWidth(), this.renderer.getMapHeight(), visionMask, visibleLevel, observer);
    }

    /**
     * Completes the scene so that the {@link WorldRenderer} can resolve the world and draw overlays.
     */
    @Override
    public void finishScene() {
        this.overlay.end(false);
    }


    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * @apiNote While this interface method is declared to throw {@code
     * Exception}, implementers are <em>strongly</em> encouraged to
     * declare concrete implementations of the {@code close} method to
     * throw more specific exceptions, or to throw no exception at all
     * if the close operation cannot fail.
     *
     * <p>
     * Cases where the close operation may fail require careful
     * attention by implementers. It is strongly advised to relinquish
     * the underlying resources and to internally <em>mark</em> the
     * resource as closed, prior to throwing the exception. The {@code
     * close} method is unlikely to be invoked more than once and so
     * this ensures that the resources are released in a timely manner.
     * Furthermore it reduces problems that could arise when the resource
     * wraps, or is wrapped, by another resource.
     *
     * <p>
     * <em>Implementers of this interface are also strongly advised
     * to not have the {@code close} method throw {@link
     * InterruptedException}.</em>
     *
     * <p>
     * This exception interacts with a thread's interrupted status,
     * and runtime misbehavior is likely to occur if an {@code
     * InterruptedException} is {@linkplain Throwable#addSuppressed
     * suppressed}.
     *
     * <p>
     * More generally, if it would cause problems for an
     * exception to be suppressed, the {@code AutoCloseable.close}
     * method should not throw it.
     *
     * <p>
     * Note that unlike the {@link Closeable#close close}
     * method of {@link Closeable}, this {@code close} method
     * is <em>not</em> required to be idempotent.  In other words,
     * calling this {@code close} method more than once may have some
     * visible side effect, unlike {@code Closeable.close} which is
     * required to have no effect if called more than once.
     *
     * <p>
     * However, implementers of this interface are strongly encouraged
     * to make their {@code close} methods idempotent.
     * @implSpec Releases the overlay textures and program. The borrowed world renderer stays open.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        this.bakedTextures.deleteTextures();
        this.relationTextures.deleteTextures();

        this.overlay.close();
    }
}
