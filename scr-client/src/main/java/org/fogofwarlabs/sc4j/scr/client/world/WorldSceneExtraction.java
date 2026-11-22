package org.fogofwarlabs.sc4j.scr.client.world;

import org.fogofwarlabs.sc4j.engine.resource.graphics.TextureSheet;
import org.fogofwarlabs.sc4j.scr.client.ui.input.PlacementPreview;
import org.fogofwarlabs.sc4j.scr.client.ui.input.WorldInterface;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageAnimation;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageSheet;
import org.fogofwarlabs.sc4j.scr.data.type.ScrImageTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrSpriteTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ImageIds;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitHallucination;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerColorRows;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.sprite.SelectionImages;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpritePool;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.visibility.LocalVision;
import org.fogofwarlabs.sc4j.scr.render.SceneRenderer;
import org.fogofwarlabs.sc4j.scr.render.WorldRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

/**
 * Submits the current game world and read-only interface overlays to a {@link SceneRenderer}. The sprites the view sees follow
 * {@link SpriteDrawOrder}; their images are drawn from the tail to the head of the image list, the selection circles and health bars among them in
 * their color and with their unit. Foliage precedes the sprites, fog follows the world, and ground click markers are submitted after the fog.
 */
public final class WorldSceneExtraction {
    private static final int MARKER_IMAGE = 445;
    /**
     * {@code thingy\pabGlow.grp}, the Arbiter's engine glow.
     */
    private static final int ARBITER_ENGINE_GLOW_IMAGE = 132;
    private static final int ARBITER_FIRST_GLOW_OFFSET = 1;
    private static final int ARBITER_LAST_GLOW_OFFSET = 2;

    private static final int MINERAL_FIELD_FIRST = 176;
    private static final int MINERAL_FIELD_LAST = 178;
    private static final int VESPENE_GEYSER = 188;
    /**
     * The number of solid selection circle images; the dashed circles follow them.
     */
    private static final int SOLID_CIRCLE_IMAGES = ImageIds.SELECTION_CIRCLE_DASHED_22PIXELS - ImageIds.SELECTION_CIRCLE_22PIXELS;
    private static final int PERCENT_SCALE = 100;
    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;

    private static final int INITIAL_FOLIAGE_CAPACITY = 64;
    private static final int FOLIAGE_CAPACITY_GROWTH_FACTOR = 2;
    private static final int PLACEMENT_CENTER_DIVISOR = 2;
    private static final int FOLIAGE_RESOURCE_FIELDS = 4;
    private static final int FOLIAGE_RESOURCE_X = 0;
    private static final int FOLIAGE_RESOURCE_Y = 1;
    private static final int FOLIAGE_RESOURCE_WIDTH = 2;
    private static final int FOLIAGE_RESOURCE_HEIGHT = 3;
    private static final int CLOAK_MODIFIER_OFFSET = SpriteImage.MODIFIER_DETECTED_CLOAKING - SpriteImage.MODIFIER_CLOAKING;

    private static final byte CLOAK_VIEW_NONE = 0;
    private static final byte CLOAK_VIEW_VISIBLE = 1;
    private static final byte CLOAK_VIEW_HIDDEN = 2;

    /**
     * The tile flags of a revealed map: every player sees and has explored every tile.
     */
    private static final @NotNull IntUnaryOperator REVEALED_TILE_FLAGS = tileIndex -> 0;

    /**
     * Maps shared cloak modifiers to their visible or hidden drawing variants for this viewer.
     *
     * @param modifier  the image's draw function in the game state
     * @param cloakView the sprite's view
     * @param burrowed  whether the sprite is burrowed
     * @return the draw function to draw with
     */
    static int getViewModifier(int modifier, int cloakView, boolean burrowed) {
        if (cloakView == CLOAK_VIEW_VISIBLE) {
            if (burrowed) {
                return modifier >= SpriteImage.MODIFIER_FIRST_CLOAK && modifier <= SpriteImage.MODIFIER_LAST_CLOAK
                       ? SceneRenderer.DRAW_FUNCTION_NORMAL
                       : modifier;
            }

            if (modifier >= SpriteImage.MODIFIER_CLOAKING && modifier <= SpriteImage.MODIFIER_DECLOAKING) {
                return modifier + CLOAK_MODIFIER_OFFSET;
            }

            return modifier == SceneRenderer.DRAW_FUNCTION_NORMAL ? SpriteImage.MODIFIER_DETECTED_CLOAKED : modifier;
        }

        if (cloakView == CLOAK_VIEW_HIDDEN) {
            if (burrowed) {
                return modifier == SceneRenderer.DRAW_FUNCTION_NORMAL ||
                       modifier == SpriteImage.MODIFIER_CLOAKING ||
                       modifier == SpriteImage.MODIFIER_DECLOAKING
                       ? SpriteImage.MODIFIER_CLOAKED
                       : modifier;
            }

            return modifier >= SpriteImage.MODIFIER_DETECTED_CLOAKING && modifier <= SpriteImage.MODIFIER_DETECTED_DECLOAKING
                   ? modifier - CLOAK_MODIFIER_OFFSET
                   : modifier;
        }

        return modifier;
    }

    /**
     * Resolves a unit image through the current unit, flingy and sprite tables.
     *
     * @param gameView the game view
     * @param unitType the unit type identifier
     * @return the image identifier
     * @throws NullPointerException if the world was built without game data
     */
    private static int getUnitImage(@NotNull ScrGameView gameView, int unitType) {
        int flingyType = gameView.requireGameData().getUnitTypes().getFlingyType(unitType);
        int spriteType = gameView.requireGameData().getFlingyTypes().getSprite(flingyType);

        return gameView.requireGameData().getSpriteTypes().getImage(spriteType);
    }

    /**
     * Maps an owner to the player color it is drawn with: with live player color rows a player slot draws with its own row
     * ({@link SceneRenderer#PLAYER_COLOR}{@code + owner}), every other owner through the owner-to-player-color mapping.
     *
     * @param playerColors  the owner-to-player-color mapping
     * @param liveColorRows whether the game has live player color rows
     * @param owner         the owner player
     * @return the player color index
     */
    private static int getPlayerColor(@NotNull IntUnaryOperator playerColors, boolean liveColorRows, int owner) {
        return liveColorRows && owner >= 0 && owner < SceneRenderer.PLAYER_COLOR_ROWS ? SceneRenderer.PLAYER_COLOR + owner
                                                                                      : playerColors.applyAsInt(owner);
    }

    private final byte @NotNull [] colorRowBytes;
    private int @NotNull [] foliageResources;
    /**
     * Per sprite pool index: {@link #CLOAK_VIEW_NONE}, {@link #CLOAK_VIEW_VISIBLE} or {@link #CLOAK_VIEW_HIDDEN}.
     */
    private byte @NotNull [] cloakViews;

    private final @NotNull SpriteDrawOrder drawOrder;

    /**
     * Creates a {@link WorldSceneExtraction} instance with reusable draw-order, cloak-view, color and foliage storage.
     */
    public WorldSceneExtraction() {
        this.colorRowBytes    = new byte[PlayerColorRows.UNIT_ROW * SceneRenderer.PLAYER_COLOR_ROWS];
        this.foliageResources = new int[INITIAL_FOLIAGE_CAPACITY];
        this.cloakViews       = new byte[0];

        this.drawOrder = new SpriteDrawOrder();
    }

    /**
     * Draws the sprites, overlays and fog of the current game state.
     *
     * @param scene        the scene backend
     * @param renderer     the classic world renderer, which supplies the camera and frozen-image placement from the classic frames
     * @param gameView     the game view
     * @param overlay      the interface state, or {@code null} for no selection
     * @param playerColors the owner-to-player-color mapping
     * @param warnings     the receiver of asset warnings
     * @throws NullPointerException if the world was built without game data
     */
    public void draw(@NotNull SceneRenderer scene,
                     @NotNull WorldRenderer renderer,
                     @NotNull ScrGameView gameView,
                     @Nullable WorldOverlayState overlay,
                     @NotNull IntUnaryOperator playerColors,
                     @NotNull Consumer<String> warnings) {
        SpritePool sprites = gameView.getSpritePool();
        LocalVision vision = gameView.getLocalVision();

        // Supplies live image overlay offsets to the renderer.
        scene.setImageOverlayOffsets(gameView.requireGameData()::getImageOverlayOffset);

        // Copies the live player color rows before sprite submission.
        PlayerColorRows colorRows = gameView.getPlayerTable().getPlayerColorRows();
        boolean liveColorRows = colorRows != null;

        if (colorRows != null) {
            colorRows.copyUnitRows(this.colorRowBytes);
            scene.setPlayerColorRows(this.colorRowBytes);

            // The HD team color is the color the player is shown in, a custom color included.
            for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
                if (colorRows.hasCustomColor(player)) {
                    scene.setCustomTeamColor(player, colorRows.getCustomColor(player));
                }
            }
        }

        this.drawFoliage(scene, gameView, warnings);

        if (sprites != null) {
            this.collectCloakViews(gameView, sprites, vision.getViewMask());

            ScrImageTypeView imageTable = gameView.requireGameData().getImageTypes();
            boolean plugins = gameView.hasPlugins();

            // Collects the rows from the camera's top tile row and the view height in world pixels.
            int cameraRow = Math.floorDiv((int) Math.floor(renderer.getCameraY()), TerrainTileset.TILE_SIZE);
            int viewHeight = (int) Math.ceil(renderer.getViewHeight() / renderer.getScale());
            int spriteCount = this.drawOrder.collect(sprites, cameraRow, viewHeight, gameView.getMap().getHeight(), vision);

            for (int orderIndex = 0; orderIndex < spriteCount; orderIndex++) {
                Sprite sprite = sprites.getSprite(this.drawOrder.getPoolIndex(orderIndex));
                int playerColor = getPlayerColor(playerColors, liveColorRows, sprite.getOwner());
                List<SpriteImage> images = sprite.getImages();
                int cloakView = sprite.getPoolIndex() < this.cloakViews.length ? this.cloakViews[sprite.getPoolIndex()] : CLOAK_VIEW_NONE;
                boolean burrowed = (sprite.getFlags() & Sprite.FLAG_BURROWED) != 0;

                for (int imageIndex = images.size() - 1; imageIndex >= 0; imageIndex--) {
                    SpriteImage image = images.get(imageIndex);

                    if ((image.getFlags() & SpriteImage.FLAG_HIDDEN) != 0) {
                        continue;
                    }

                    if (image.getModifier() == SpriteImage.MODIFIER_SELECTION_CIRCLE) {
                        this.drawSelectionCircle(scene, sprite, image, warnings);

                        continue;
                    }

                    if (image.getModifier() == SpriteImage.MODIFIER_HEALTH_BAR) {
                        this.drawHealthBar(scene, gameView, sprite, image, warnings);

                        continue;
                    }

                    try {
                        int drawFunction = plugins ? gameView.findImageDrawFunction(image) : -1;
                        int modifier = getViewModifier(drawFunction >= 0 ? drawFunction : image.getModifier(), cloakView, burrowed);
                        int pluginFrame = plugins ? gameView.findImageFrame(image) : -1;
                        int frame = pluginFrame >= 0 ? pluginFrame : image.getDrawnFrameIndex();

                        if (cloakView == CLOAK_VIEW_HIDDEN && burrowed && imageTable.getDrawIfCloaked(image.getImageTypeId()) == 0) {
                            continue;
                        }

                        ScrImageSheet sheet = plugins ? gameView.findImageSheet(image) : null;
                        ScrImageAnimation animation = plugins && scene.getAssetScale() > 0 ? gameView.findImageAnimation(image) : null;
                        TextureSheet animationSheet = animation != null ? animation.findSheet(scene.getAssetScale()) : null;
                        int pluginImageType = plugins && sheet == null && animationSheet == null ? gameView.findImageTypeId(image) : -1;
                        int imageType = pluginImageType >= 0 ? pluginImageType : image.getImageTypeId();

                        // Skips images whose frame has no drawable rectangle.
                        if (animationSheet != null ? frame >= animationSheet.getFrameCount()
                                                   : sheet != null ? frame >= sheet.getFrameCount()
                                                                   : !renderer.isDrawnFrame(imageType, frame)) {
                            continue;
                        }

                        int imageX = sprite.getX() + image.getOffsetX();
                        int imageY = sprite.getY() + image.getOffsetY();

                        // Drops the engine-glow vertical offset for the remastered backend.
                        if (imageType == ARBITER_ENGINE_GLOW_IMAGE && scene.isRemastered() &&
                            (image.getOffsetY() == ARBITER_FIRST_GLOW_OFFSET || image.getOffsetY() == ARBITER_LAST_GLOW_OFFSET)) {
                            imageY -= image.getOffsetY();
                        }

                        boolean flipped = (image.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0;

                        // The frozen canvas center comes from the classic frame rectangle; both backends center their canvas there.
                        if ((image.getFlags() & SpriteImage.FLAG_Y_FROZEN) != 0) {
                            imageY = renderer.getFrozenCenterY(imageType, frame, image.getFrozenMapY());
                        }

                        if (animationSheet != null) {
                            // A plugin's anim replaces the installed anim in every draw function.
                            scene.drawImageAnimation(animationSheet,
                                                     sheet,
                                                     imageType,
                                                     frame,
                                                     modifier,
                                                     image.getModifierData1(),
                                                     playerColor,
                                                     imageX,
                                                     imageY,
                                                     flipped);
                        } else if (sheet != null) {
                            // A plugin's sheet replaces the installed graphics in every draw function.
                            scene.drawImageSheet(sheet,
                                                 imageType,
                                                 frame,
                                                 modifier,
                                                 image.getModifierData1(),
                                                 playerColor,
                                                 imageX,
                                                 imageY,
                                                 flipped);
                        } else if (modifier == SceneRenderer.DRAW_FUNCTION_UPDATE_RECTANGLE) {
                            scene.drawUpdateRectangle(imageType, frame, imageX, imageY, flipped);
                        } else if (modifier == SpriteImage.MODIFIER_REMAPPING) {
                            // Uses the remapping index retained by the image.
                            scene.drawRemappedImage(imageType,
                                                    frame,
                                                    image.getColorRemappingIndex(),
                                                    playerColor,
                                                    imageX,
                                                    imageY,
                                                    flipped);
                        } else if (modifier == SpriteImage.MODIFIER_SHADOW) {
                            // Supplies the primary image's warp frame to shadow drawing.
                            SpriteImage primary = sprite.getMainImage();
                            int primaryWarpFrame = primary != null && primary.getModifier() == SpriteImage.MODIFIER_WARP_TEXTURE
                                                   ? primary.getModifierData1()
                                                   : -1;

                            scene.drawShadowImage(imageType, frame, primaryWarpFrame, imageX, imageY, flipped);
                        } else {
                            scene.drawImage(imageType,
                                            frame,
                                            modifier,
                                            image.getModifierData1(),
                                            playerColor,
                                            imageX,
                                            imageY,
                                            flipped);
                        }
                    } catch (IOException exception) {
                        warnings.accept("Runtime image " + image.getImageTypeId() + ": " + exception.getMessage());
                    }
                }
            }

            PlacementPreview placement = overlay != null ? overlay.getPlacementPreview() : null;

            if (placement != null && placement.getType() >= 0) {
                this.drawPlacement(scene,
                                   gameView,
                                   placement,
                                   overlay.getViewingPlayer(),
                                   getPlayerColor(playerColors, liveColorRows, overlay.getViewingPlayer()),
                                   warnings);
            }
        }

        if (gameView.getMap().hasTileFlags()) {
            // A replay that reveals the entire map shows every tile as seen; an observer takes the observer fog table.
            IntUnaryOperator tileFlags = vision.isReplayOrObserver() && vision.isRevealed() ? REVEALED_TILE_FLAGS : gameView.getMap()::getTileFlags;

            scene.drawFog(tileFlags, vision.getViewMask(), gameView.getGameContext().getVisibleFogLevel(), vision.isObserver());
        }

        // Draws ground click markers after the fog.
        if (sprites != null && overlay != null) {
            int currentFrame = gameView.getCurrentFrame();

            for (int marker = 0; marker < overlay.getMarkerCount(); marker++) {
                int elapsed = currentFrame - overlay.getMarkerFrame(marker);

                if (overlay.getMarkerTarget(marker) != null || overlay.getMarkerRememberedSprite(marker) != null || elapsed < 0 ||
                    elapsed >= WorldInterface.MARKER_LIFETIME) {
                    continue;
                }

                try {
                    scene.drawImage(MARKER_IMAGE,
                                    elapsed,
                                    SceneRenderer.DRAW_FUNCTION_NORMAL,
                                    0,
                                    getPlayerColor(playerColors, liveColorRows, overlay.getViewingPlayer()),
                                    overlay.getMarkerX(marker),
                                    overlay.getMarkerY(marker),
                                    false);
                } catch (IOException exception) {
                    warnings.accept("Click marker: " + exception.getMessage());
                }
            }
        }
    }

    /**
     * Submits terrain foliage with the resource units' tile footprints.
     *
     * @param scene    the scene backend
     * @param gameView the game view
     * @param warnings the receiver of asset warnings
     * @throws NullPointerException if the world was built without game data
     */
    private void drawFoliage(@NotNull SceneRenderer scene, @NotNull ScrGameView gameView, @NotNull Consumer<String> warnings) {
        ScrUnitTypeView units = gameView.requireGameData().getUnitTypes();
        UnitPool pool = gameView.getUnitSystem().getUnitPool();

        int count = 0;

        if (pool != null) {
            for (int index = 1; index <= pool.getCapacity(); index++) {
                if (!pool.isAllocated(index)) {
                    continue;
                }

                Unit unit = pool.getUnit(index);
                int type = unit.getUnitId();

                if ((type < MINERAL_FIELD_FIRST || type > MINERAL_FIELD_LAST) && type != VESPENE_GEYSER) {
                    continue;
                }

                int columns = (short) units.getPlacementWidth(type) / TerrainTileset.TILE_SIZE;
                int rows = (short) units.getPlacementHeight(type) / TerrainTileset.TILE_SIZE;

                if (this.foliageResources.length < (count + 1) * FOLIAGE_RESOURCE_FIELDS) {
                    this.foliageResources = Arrays.copyOf(this.foliageResources, this.foliageResources.length * FOLIAGE_CAPACITY_GROWTH_FACTOR);
                }

                int resourceOffset = count * FOLIAGE_RESOURCE_FIELDS;
                int tileX = unit.getPositionX() / TerrainTileset.TILE_SIZE - columns / PLACEMENT_CENTER_DIVISOR;
                int tileY = unit.getPositionY() / TerrainTileset.TILE_SIZE - rows / PLACEMENT_CENTER_DIVISOR;

                this.foliageResources[resourceOffset + FOLIAGE_RESOURCE_X]      = tileX;
                this.foliageResources[resourceOffset + FOLIAGE_RESOURCE_Y]      = tileY;
                this.foliageResources[resourceOffset + FOLIAGE_RESOURCE_WIDTH]  = columns;
                this.foliageResources[resourceOffset + FOLIAGE_RESOURCE_HEIGHT] = rows;

                count++;
            }
        }

        try {
            scene.drawFoliage(gameView.getMap(),
                              gameView.getGameContext().getGameSeed(), gameView.getCurrentFrame(),
                              gameView.getMap()::getTileIdentifier,
                              this.foliageResources, count);
        } catch (IOException exception) {
            warnings.accept("Foliage: " + exception.getMessage());
        }
    }

    /**
     * Submits the local player's psi fields and visible building placement ghosts.
     *
     * @param scene       the scene backend
     * @param gameView    the game view
     * @param placement   the placement preview
     * @param localPlayer the local player slot
     * @param playerColor the mapped player color
     * @param warnings    the receiver of asset warnings
     * @throws NullPointerException if the world was built without game data
     */
    private void drawPlacement(@NotNull SceneRenderer scene,
                               @NotNull ScrGameView gameView,
                               @NotNull PlacementPreview placement,
                               int localPlayer,
                               int playerColor,
                               @NotNull Consumer<String> warnings) {
        ScrUnitTypeView units = gameView.requireGameData().getUnitTypes();

        // Submits the local player's psi-field sprites before the placement ghost.
        UnitPool pool = gameView.getUnitSystem().getUnitPool();

        if (placement.isShowingPsiFields() && pool != null) {
            for (int index = 1; index <= pool.getCapacity(); index++) {
                if (!pool.isAllocated(index)) {
                    continue;
                }

                Unit unit = pool.getUnit(index);
                Sprite field = unit.getOwnerIndex() == localPlayer ? unit.getPsiFieldSprite() : null;

                if (field == null) {
                    continue;
                }

                for (int imageIndex = field.getImages().size() - 1; imageIndex >= 0; imageIndex--) {
                    SpriteImage image = field.getImages().get(imageIndex);

                    try {
                        scene.drawImage(image.getImageTypeId(),
                                        image.getDrawnFrameIndex(),
                                        image.getModifier(),
                                        image.getModifierData1(),
                                        playerColor,
                                        field.getX() + image.getOffsetX(),
                                        field.getY() + image.getOffsetY(),
                                        (image.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0);
                    } catch (IOException exception) {
                        warnings.accept("Psi field: " + exception.getMessage());
                    }
                }
            }
        }

        if (!placement.isVisible()) {
            return;
        }

        try {
            int type = placement.getType();

            scene.drawPlacementGhost(getUnitImage(gameView, type),
                                     0,
                                     playerColor,
                                     placement.getTileX() * TerrainTileset.TILE_SIZE + units.getPlacementWidth(type) / PLACEMENT_CENTER_DIVISOR,
                                     placement.getTileY() * TerrainTileset.TILE_SIZE + units.getPlacementHeight(type) / PLACEMENT_CENTER_DIVISOR);

            int parent = placement.getParentType();

            if (parent >= 0) {
                scene.drawPlacementGhost(getUnitImage(gameView, parent),
                                         0,
                                         playerColor,
                                         placement.getParentPixelX() + units.getPlacementWidth(parent) / PLACEMENT_CENTER_DIVISOR,
                                         placement.getParentPixelY() + units.getPlacementHeight(parent) / PLACEMENT_CENTER_DIVISOR);
            }
        } catch (IOException exception) {
            warnings.accept("Placement ghost: " + exception.getMessage());
        }
    }

    /**
     * Draws an image of draw function {@link SceneRenderer#DRAW_FUNCTION_SELECTION_CIRCLE} in its retained relation color: a solid selection circle
     * through the circle backend, every other image through relation drawing with its current frame and horizontal flip.
     *
     * @param scene    the scene backend
     * @param sprite   the image's sprite
     * @param image    the relation image
     * @param warnings the receiver of asset warnings
     */
    private void drawSelectionCircle(@NotNull SceneRenderer scene,
                                     @NotNull Sprite sprite,
                                     @NotNull SpriteImage image,
                                     @NotNull Consumer<String> warnings) {
        int imageType = image.getImageTypeId();
        int color = image.getModifierData1() & BYTE_MASK;
        int relation = color == SelectionImages.COLOR_OWN
                       ? SceneRenderer.RELATION_OWN
                       : color == SelectionImages.COLOR_ALLIED ? SceneRenderer.RELATION_NEUTRAL : SceneRenderer.RELATION_ENEMY;
        int imageX = sprite.getX() + image.getOffsetX();
        int imageY = sprite.getY() + image.getOffsetY();

        try {
            if (((imageType - ImageIds.SELECTION_CIRCLE_22PIXELS) & WORD_MASK) < SOLID_CIRCLE_IMAGES) {
                scene.drawSelectionCircle(imageType, relation, imageX, imageY);
            } else {
                scene.drawRelationImage(imageType,
                                        image.getDrawnFrameIndex(),
                                        relation,
                                        imageX,
                                        imageY,
                                        (image.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0);
            }
        } catch (IOException exception) {
            warnings.accept("Selection circle image " + imageType + ": " + exception.getMessage());
        }
    }

    /**
     * Draws a health bar image at its place with the hit-point, shield, energy or lifetime percentages of the unit it keeps. A unit made invincible
     * draws no bar.
     *
     * @param scene    the scene backend
     * @param gameView the game view
     * @param sprite   the bar's sprite
     * @param image    the health bar image
     * @param warnings the receiver of asset warnings
     * @throws NullPointerException if the world was built without game data
     */
    private void drawHealthBar(@NotNull SceneRenderer scene,
                               @NotNull ScrGameView gameView,
                               @NotNull Sprite sprite,
                               @NotNull SpriteImage image,
                               @NotNull Consumer<String> warnings) {
        UnitPool pool = gameView.getUnitSystem().getUnitPool();
        int poolIndex = image.getModifierData1();
        Unit unit = pool != null && poolIndex > 0 && poolIndex <= pool.getCapacity() ? pool.getUnit(poolIndex) : null;

        if (unit == null) {
            // Reports a health-bar image without a bound unit.
            warnings.accept("Image " + image.getImageTypeId() + " has draw function 11 without a bound unit.");

            return;
        }

        if ((unit.getStatus() & UnitStatusFlags.INVINCIBLE) != 0) {
            return;
        }

        ScrUnitTypeView units = gameView.requireGameData().getUnitTypes();
        ScrSpriteTypeView spriteTable = gameView.requireGameData().getSpriteTypes();
        int type = unit.getUnitId();

        int hitPointsPercent = UnitQueries.getHitPointPercent(units, unit);

        int shieldPercent = -1;

        if (units.getShieldEnabled(type) != 0) {
            shieldPercent = (unit.getShieldPoints() >> Byte.SIZE) * PERCENT_SCALE / Math.max(units.getShieldAmount(type), 1);
        }

        int energyPercent = -1;
        boolean hallucination = (unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0;

        if (UnitTypeTraitsUtils.isSpellcaster(units, unit)) {
            int maximumEnergy = gameView.getUnitInitializer().getMaximumEnergy(unit, units) >> Byte.SIZE;

            energyPercent = (unit.getEnergy() >> Byte.SIZE) * PERCENT_SCALE / Math.max(maximumEnergy, 1);
        } else if (hallucination || type == ScrUnitIds.ZERG_BROODLING) {
            // Displays remaining lifetime as a percentage of its default timer.
            int removalTimer = hallucination ? UnitHallucination.HALLUCINATION_REMOVAL_TIMER : UnitHallucination.BROODLING_REMOVAL_TIMER;

            energyPercent = (unit.getRemainingLifetime() & WORD_MASK) * PERCENT_SCALE / removalTimer;
        }

        try {
            scene.drawHealthBar(sprite.getX() + image.getOffsetX(),
                                sprite.getY() + image.getOffsetY(),
                                spriteTable.getHealthBarWidth(sprite.getSpriteTypeId()),
                                hitPointsPercent,
                                shieldPercent,
                                energyPercent);
        } catch (IOException exception) {
            warnings.accept("Health bar: " + exception.getMessage());
        }
    }

    /**
     * Collects the detector visibility of the sprites for the viewing mask.
     *
     * @param gameView the game view
     * @param sprites  the sprite pool
     * @param viewMask the players whose vision the view shows
     */
    private void collectCloakViews(@NotNull ScrGameView gameView, @NotNull SpritePool sprites, int viewMask) {
        if (this.cloakViews.length < sprites.getCapacity() + 1) {
            this.cloakViews = new byte[sprites.getCapacity() + 1];
        }

        Arrays.fill(this.cloakViews, CLOAK_VIEW_NONE);

        UnitPool pool = gameView.getUnitSystem().getUnitPool();

        if (pool == null) {
            return;
        }

        for (int poolIndex = 1; poolIndex <= pool.getCapacity(); poolIndex++) {
            if (!pool.isAllocated(poolIndex)) {
                continue;
            }

            Unit unit = pool.getUnit(poolIndex);

            if ((unit.getStatus() & UnitStatusFlags.REQUIRES_DETECTOR) == 0) {
                continue;
            }

            byte view = (unit.getDetectionFlags() & viewMask) != 0 ? CLOAK_VIEW_VISIBLE : CLOAK_VIEW_HIDDEN;
            Sprite cloakedSprite = unit.getSpriteOrNull();

            if (cloakedSprite != null && cloakedSprite.getPoolIndex() >= 0 && cloakedSprite.getPoolIndex() < this.cloakViews.length) {
                this.cloakViews[cloakedSprite.getPoolIndex()] = view;
            }

            Unit subunit = unit.getSubunit();

            if (subunit != null) {
                Sprite subunitSprite = subunit.getSpriteOrNull();

                if (subunitSprite != null && subunitSprite.getPoolIndex() >= 0 && subunitSprite.getPoolIndex() < this.cloakViews.length) {
                    this.cloakViews[subunitSprite.getPoolIndex()] = view;
                }
            }
        }
    }
}
