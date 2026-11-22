package org.fogofwarlabs.sc4j.scr.render;

import org.fogofwarlabs.sc4j.engine.resource.graphics.TextureSheet;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageSheet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * Draws the world scene extracted from game domain state: terrain tiles and sprite images.
 * <p>
 * The caller extracts the scene once (current tile identifiers, drawable sprites in draw order, their images tail to head, image frame, modifier,
 * modifier data, owner color, position and flip) and submits it unchanged to whichever backend is selected, so the classic and HD backends draw the
 * same state. A backend draws between {@link WorldRenderer#beginFrame} and {@link WorldRenderer#finishWorld} of the {@link WorldRenderer} it was
 * created with, and {@link #finishScene()} must be called before {@link WorldRenderer#finishWorld()}.
 */
public interface SceneRenderer {
    /**
     * The selection-circle relation of the viewing player's own units (green).
     */
    int RELATION_OWN = 0;
    /**
     * The selection-circle relation of allied and neutral units (yellow).
     */
    int RELATION_NEUTRAL = 1;
    /**
     * The selection-circle relation of enemy units (red).
     */
    int RELATION_ENEMY = 2;

    /**
     * The first player color that selects a player's live color row: {@code PLAYER_COLOR + player}.
     */
    int PLAYER_COLOR = 16;
    /**
     * The number of live player color rows (players {@code 0..11}).
     */
    int PLAYER_COLOR_ROWS = 12;

    /**
     * The {@code images.rel} relation type of shadow images ({@code ImageRelations}).
     */
    int SHADOW_RELATION = 8;
    /**
     * The Warp Texture image, the map of draw function {@code 12}.
     */
    int WARP_TEXTURE_IMAGE = 210;
    /**
     * The color of a rectangle of draw function {@code 15}: {@code (0.0627, 0.988, 0.094, 1)}.
     */
    int UPDATE_RECTANGLE_COLOR = 0x10FC18;
    /**
     * The mask of the image modifier data byte.
     */
    int MODIFIER_DATA_MASK = 0xFF;
    /**
     * The warp flash modifier data of full brightness (alpha {@code 1}).
     */
    int WARP_FLASH_FIRST_DATA = 48;
    /**
     * The warp flash modifier data of no brightness (alpha {@code 0}).
     */
    int WARP_FLASH_LAST_DATA = 63;

    /**
     * The {@code images.dat} draw function of normal images.
     */
    int DRAW_FUNCTION_NORMAL = 0;
    /**
     * The {@code images.dat} draw function of normal images without the visibility test.
     */
    int DRAW_FUNCTION_NORMAL_UNCHECKED = 1;
    /**
     * The draw function of an image cloaking, as players who do not see the unit get it.
     */
    int DRAW_FUNCTION_UNSEEN_CLOAKING = 2;
    /**
     * The draw function of a cloaked image, as players who do not see the unit get it.
     */
    int DRAW_FUNCTION_UNSEEN_CLOAKED = 3;
    /**
     * The draw function of an image decloaking, as players who do not see the unit get it.
     */
    int DRAW_FUNCTION_UNSEEN_DECLOAKING = 4;
    /**
     * The draw function of an image cloaking, as its owner, allies and detectors see it.
     */
    int DRAW_FUNCTION_CLOAKING = 5;
    /**
     * The draw function of a cloaked image, as its owner, allies and detectors see it.
     */
    int DRAW_FUNCTION_CLOAKED = 6;
    /**
     * The draw function of an image decloaking, as its owner, allies and detectors see it.
     */
    int DRAW_FUNCTION_DECLOAKING = 7;
    /**
     * The draw function of distortion images (EMP shockwave).
     */
    int DRAW_FUNCTION_DISTORTION = 8;
    /**
     * The draw function of images remapped through a tileset lighting table (glows).
     */
    int DRAW_FUNCTION_REMAPPING = 9;
    /**
     * The draw function of shadows.
     */
    int DRAW_FUNCTION_SHADOW = 10;
    /**
     * The draw function of health bars.
     */
    int DRAW_FUNCTION_HEALTH_BAR = 11;
    /**
     * The draw function of the Warp Texture mask of a warping-in building.
     */
    int DRAW_FUNCTION_WARP_TEXTURE = 12;
    /**
     * The draw function of selection circles: the frame's shape in one relation color.
     */
    int DRAW_FUNCTION_SELECTION_CIRCLE = 13;
    /**
     * The draw function of images drawn with their original player color (the flag).
     */
    int DRAW_FUNCTION_PLAYER_COLOR = 14;
    /**
     * The draw function that draws the frame's rectangle instead of the frame ("update rectangle").
     */
    int DRAW_FUNCTION_UPDATE_RECTANGLE = 15;
    /**
     * The draw function of hallucinated images.
     */
    int DRAW_FUNCTION_HALLUCINATION = 16;
    /**
     * The draw function of the Protoss warp flash.
     */
    int DRAW_FUNCTION_WARP_FLASH = 17;

    /**
     * Returns the multiply alpha of a shadow drawn without Shadow Stacking ({@link #drawShadowImage}): {@code data / frames}, the primary image's
     * data over the frame count of the Warp Texture's anim, for a {@link #SHADOW_RELATION} image over a warping-in primary image, else {@code 1}.
     *
     * @param relationType     the shadow image's {@code images.rel} relation type
     * @param primaryWarpFrame the primary image's modifier data when its draw function is {@code 12}, else {@code -1}
     * @param warpFrameCount   the frame count of the Warp Texture's anim in the current graphics
     * @return the multiply alpha; values above {@code 1} saturate in the render target
     */
    static float getShadowWarpAlpha(int relationType, int primaryWarpFrame, int warpFrameCount) {
        if (relationType != SHADOW_RELATION || primaryWarpFrame < 0 || warpFrameCount <= 0) {
            return 1;
        }

        return primaryWarpFrame / (float) warpFrameCount;
    }

    /**
     * Returns the brighten alpha of a warp flash of draw function {@code 17}: {@code 1 - sqrt(1 - (63 - data) / 15)}, with the image's modifier data
     * byte, which starts at {@code 48} (alpha {@code 1}) and counts up to {@code 63} (alpha {@code 0}). Other data gives a negative alpha (above
     * {@code 63}) or the square root of a negative number (below {@code 48}); the render target's saturation draws both as nothing, so they return
     * {@code 0}.
     *
     * @param modifierData the image modifier data byte
     * @return the brighten alpha in {@code 0..1}
     */
    static float getBrightenAlpha(int modifierData) {
        int data = modifierData & MODIFIER_DATA_MASK;

        if (data < WARP_FLASH_FIRST_DATA || data > WARP_FLASH_LAST_DATA) {
            return 0;
        }

        return 1 - (float) Math.sqrt(1 - (WARP_FLASH_LAST_DATA - data) / (float) (WARP_FLASH_LAST_DATA - WARP_FLASH_FIRST_DATA));
    }

    /**
     * Returns the world rectangle of a classic frame's pixels.
     *
     * @param frame   the decoded classic frame
     * @param worldX  the canvas center world coordinate
     * @param worldY  the canvas center world coordinate
     * @param flipped whether the image is horizontally flipped
     * @return {@code {left, top, width, height}}, or {@code null} for an empty frame
     */
    static float @Nullable [] getFrameRectangle(@NotNull ClassicFrame frame, int worldX, int worldY, boolean flipped) {
        if (frame.getFrameWidth() <= 0 || frame.getFrameHeight() <= 0) {
            return null;
        }

        int width = frame.getWidth();
        float canvasLeft = worldX - (flipped ? width - width / 2 : width / 2);
        float canvasTop = worldY - frame.getHeight() / 2;
        float left = canvasLeft + (flipped ? width - frame.getXOffset() - frame.getFrameWidth() : frame.getXOffset());

        return new float[]{left, canvasTop + frame.getYOffset(), frame.getFrameWidth(), frame.getFrameHeight()};
    }


    /**
     * @return {@code true} if the backend draws the remastered (HD) graphics, whose draw builder places images by their HD rules, {@code false}
     * otherwise
     */
    default boolean isRemastered() {
        return false;
    }

    /**
     * @return the asset scale of the anims the backend draws ({@link #drawImageAnimation}): {@code 4} for HD, {@code 2} for HD2, {@code 0} when it
     * draws no anims
     */
    default int getAssetScale() {
        return 0;
    }

    /**
     * Sets the game's live image overlay offsets for the images whose placement reads them at draw time (the HD Bunker fire). The default ignores
     * them.
     *
     * @param overlays the game's overlay offsets, or {@code null} for the installed resources
     */
    default void setImageOverlayOffsets(@Nullable ImageOverlayOffsets overlays) {
    }

    /**
     * Sets the game context's player color rows (eight {@code tunit.pcx} palette indices per player), read at every draw: the classic remapping of
     * palette indices {@code 8..15} and the HD team color (palette entry of byte {@code 0}). Player colors {@link #PLAYER_COLOR}{@code + player} then
     * draw with them; an EUD write to the rows recolors the player's units.
     *
     * @param rows the twelve rows of eight palette indices
     */
    void setPlayerColorRows(byte @NotNull [] rows);

    /**
     * Sets the HD team color of a player shown in its custom color instead of the palette entry of its row's first byte, until the next
     * {@link #setPlayerColorRows}. The default ignores it.
     *
     * @param player the player slot
     * @param color  the packed {@code 0xRRGGBBAA} custom color
     */
    default void setCustomTeamColor(int player, int color) {
    }


    /**
     * Draws the visible terrain from the current game tile identifiers, including creep edges.
     *
     * @param tileIdentifiers the current row-major tile identifier for each tile index of the loaded map extent
     * @throws IOException if a tile identifier is outside the installed assets
     */
    void drawTerrain(@NotNull IntUnaryOperator tileIdentifiers) throws IOException;

    /**
     * Draws the visible terrain from the current game tile identifiers, including creep edges, with megatile pictures that replace installed ones:
     * {@code 32x32} palette indices, rows from the top, drawn in the classic palette at world size. The default ignores the replacements.
     *
     * @param tileIdentifiers the current row-major tile identifier for each tile index of the loaded map extent
     * @param megatiles       the picture of a megatile index, the same array while it is unchanged, or {@code null} for the installed picture
     * @throws IOException if a tile identifier is outside the installed assets
     */
    default void drawTerrain(@NotNull IntUnaryOperator tileIdentifiers, @NotNull IntFunction<byte @Nullable []> megatiles) throws IOException {
        this.drawTerrain(tileIdentifiers);
    }

    /**
     * Draws HD foliage, placed once per map from the game seed with its own generator and animated by the game frame; presentation only. The default
     * draws nothing: the classic graphics have no foliage.
     *
     * @param map             the loaded map; another object places the foliage again
     * @param gameSeed        the game seed
     * @param gameFrame       the current game frame
     * @param tileIdentifiers the current row-major tile identifier for each tile index of the loaded map extent
     * @param resources       the tile footprints of the resource units, four values each (column, row, columns, rows)
     * @param resourceCount   the number of resources in {@code resources}
     * @throws IOException if the foliage assets are malformed
     */
    default void drawFoliage(@NotNull Object map,
                             int gameSeed, int gameFrame,
                             @NotNull IntUnaryOperator tileIdentifiers,
                             int @NotNull [] resources, int resourceCount) throws IOException {
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
    void drawImage(int imageIndex, int frameIndex, int modifier, int modifierData, int playerColor, int worldX, int worldY, boolean flipped) throws IOException;

    /**
     * Draws one image of a sprite with the frames of an override sheet instead of the image's installed graphics, with the image's draw function.
     * The default draws the installed graphics ({@link #drawImage}).
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
    default void drawImageSheet(@NotNull ScrImageSheet sheet,
                                int imageIndex, int frameIndex,
                                int modifier, int modifierData, int playerColor,
                                int worldX, int worldY, boolean flipped) throws IOException {
        this.drawImage(imageIndex, frameIndex, modifier, modifierData, playerColor, worldX, worldY, flipped);
    }

    /**
     * Draws one image of a sprite with the frames of an override anim instead of the image's installed anim, with the image's draw function. A
     * frame the anim does not place on its canvas is placed by the override sheet's frame, else on the canvas. The default draws the override sheet
     * ({@link #drawImageSheet}) or the installed graphics ({@link #drawImage}).
     *
     * @param animation    the texture sheet of the anim, prepared at the backend's asset scale or the other one ({@link #getAssetScale()})
     * @param sheet        the indexed frames the image draws in the classic graphics, or {@code null} for its installed graphics
     * @param imageIndex   the image identifier
     * @param frameIndex   the image frame, a frame of the anim
     * @param modifier     the image draw modifier
     * @param modifierData the image modifier data
     * @param playerColor  the owner's player color index {@code 0..15}, or {@link #PLAYER_COLOR}{@code + owner}
     * @param worldX       the canvas center world coordinate
     * @param worldY       the canvas center world coordinate
     * @param flipped      whether the image is horizontally flipped
     * @throws IOException if the frame cannot be drawn or the modifier is not drawn by this backend
     */
    default void drawImageAnimation(@NotNull TextureSheet animation, @Nullable ScrImageSheet sheet,
                                    int imageIndex, int frameIndex,
                                    int modifier, int modifierData, int playerColor,
                                    int worldX, int worldY, boolean flipped) throws IOException {
        if (sheet != null) {
            this.drawImageSheet(sheet, imageIndex, frameIndex, modifier, modifierData, playerColor, worldX, worldY, flipped);
        } else {
            this.drawImage(imageIndex, frameIndex, modifier, modifierData, playerColor, worldX, worldY, flipped);
        }
    }

    /**
     * Draws an image of draw function {@code 10} (a shadow). Without Shadow Stacking the draw builder dims the shadow of a warping-in sprite: when
     * the image's {@code images.rel} type is {@link #SHADOW_RELATION} and the sprite's primary image has draw function {@code 12}, the shadow's
     * multiply alpha is that image's data (the warp texture frame) over the frame count of the Warp Texture's anim ({@link #getShadowWarpAlpha}). The
     * default draws it as {@link #drawImage} with modifier {@code 10}, without the dimming.
     *
     * @param imageIndex       the image identifier
     * @param frameIndex       the image frame
     * @param primaryWarpFrame the primary image's modifier data when its draw function is {@code 12}, else {@code -1}
     * @param worldX           the canvas center world coordinate
     * @param worldY           the canvas center world coordinate
     * @param flipped          whether the image is horizontally flipped
     * @throws IOException if the image cannot be loaded
     */
    default void drawShadowImage(int imageIndex, int frameIndex, int primaryWarpFrame, int worldX, int worldY, boolean flipped) throws IOException {
        this.drawImage(imageIndex, frameIndex, DRAW_FUNCTION_SHADOW, 0, 0, worldX, worldY, flipped);
    }

    /**
     * Draws an image of draw function {@code 9} (a glow remapped through a tileset lighting table) with the image's own color remapping, the
     * {@code images.dat} {@code Color Shift} value copied into the image when the game created it, including tables changed during the game (EUD).
     * The default draws it as {@link #drawImage} with modifier {@code 9}: the HD backend does not remap.
     *
     * @param imageIndex     the image identifier
     * @param frameIndex     the image frame
     * @param colorRemapping the image's color remapping ({@code 1} ofire through {@code 7} green)
     * @param playerColor    the owner's player color index
     * @param worldX         the canvas center world coordinate
     * @param worldY         the canvas center world coordinate
     * @param flipped        whether the image is horizontally flipped
     * @throws IOException if the image cannot be loaded or its remapping selects no lighting table
     */
    default void drawRemappedImage(int imageIndex, int frameIndex, int colorRemapping, int playerColor, int worldX, int worldY, boolean flipped) throws IOException {
        this.drawImage(imageIndex, frameIndex, DRAW_FUNCTION_REMAPPING, 0, playerColor, worldX, worldY, flipped);
    }

    /**
     * Draws a building placement ghost: one image frame with RGB {@code (1, 1, 1)} at alpha {@code 0.5}, colored with the local player's team color,
     * without a shadow. The default draws the image opaque; both backends override it with the blend at alpha {@code 0.5}.
     *
     * @param imageIndex  the unit's image identifier
     * @param frameIndex  the image frame
     * @param playerColor the local player's color index
     * @param worldX      the canvas center world coordinate
     * @param worldY      the canvas center world coordinate
     * @throws IOException if the image cannot be loaded
     */
    default void drawPlacementGhost(int imageIndex, int frameIndex, int playerColor, int worldX, int worldY) throws IOException {
        this.drawImage(imageIndex, frameIndex, DRAW_FUNCTION_NORMAL, 0, playerColor, worldX, worldY, false);
    }

    /**
     * Draws a selection circle centered at a sprite's circle position; the caller draws it at the place the circle keeps among the images of the
     * selected sprite.
     *
     * @param circleImage the circle image: {@code 561 + sprites.dat selection circle} (dashed circles follow at {@code 571})
     * @param relation    {@link #RELATION_OWN}, {@link #RELATION_NEUTRAL} or {@link #RELATION_ENEMY}
     * @param worldX      the sprite x coordinate
     * @param worldY      the sprite y coordinate plus the {@code sprites.dat} selection-circle vertical offset
     * @throws IOException if the circle image cannot be loaded
     */
    default void drawSelectionCircle(int circleImage, int relation, int worldX, int worldY) throws IOException {
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
    void drawRelationImage(int imageIndex, int frameIndex, int relation, int worldX, int worldY, boolean flipped) throws IOException;

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
    void drawUpdateRectangle(int imageIndex, int frameIndex, int worldX, int worldY, boolean flipped) throws IOException;

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
    void drawHealthBar(int worldX, int worldY, int barWidth, int hitPointsPercent, int shieldPercent, int energyPercent) throws IOException;

    /**
     * Darkens the world by the fog of war; this is the last world drawing of a scene.
     *
     * @param tileFlags  the current tile flag DWORD for each row-major tile index (low byte: not visible per player, second byte: not explored per
     *                   player)
     * @param visionMask the eight-bit mask of players whose vision is shown
     */
    default void drawFog(@NotNull IntUnaryOperator tileFlags, int visionMask) {
        this.drawFog(tileFlags, visionMask, OverlayPass.DEFAULT_VISIBLE_LEVEL, false);
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
    void drawFog(@NotNull IntUnaryOperator tileFlags, int visionMask, int visibleLevel, boolean observer);


    /**
     * Completes the scene so that the {@link WorldRenderer} can resolve the world and draw overlays.
     */
    void finishScene();
}
