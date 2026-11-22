package org.fogofwarlabs.sc4j.scr.client.world;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.view.GameView;
import org.fogofwarlabs.sc4j.engine.client.view.UnitView;
import org.fogofwarlabs.sc4j.engine.content.economy.ResourceKind;
import org.fogofwarlabs.sc4j.engine.content.faction.Faction;
import org.fogofwarlabs.sc4j.engine.content.type.OrderType;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.engine.terrain.TerrainGrid;
import org.fogofwarlabs.sc4j.scr.content.ScrResources;
import org.fogofwarlabs.sc4j.scr.core.GameSettings;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkUnit;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageAnimation;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageSheet;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.ability.TransportCargo;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitSpells;
import org.fogofwarlabs.sc4j.scr.game.combat.ScrShieldLayer;
import org.fogofwarlabs.sc4j.scr.game.combat.UnitTargeting;
import org.fogofwarlabs.sc4j.scr.game.extension.*;
import org.fogofwarlabs.sc4j.scr.game.frame.GameFrame;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.path.PathPool;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.ImageScriptPreview;
import org.fogofwarlabs.sc4j.scr.game.presentation.ScrButtonStates;
import org.fogofwarlabs.sc4j.scr.game.presentation.StatTextOverrides;
import org.fogofwarlabs.sc4j.scr.game.production.PlacementEvaluator;
import org.fogofwarlabs.sc4j.scr.game.production.ScrRequirementSystem;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpritePool;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameContext;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.visibility.LocalVision;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Shows a running SC:R game to the client: the SC:R {@link GameView}. The engine part reads the SC:R state the engine systems of the game expose
 * (players, the {@link org.fogofwarlabs.sc4j.scr.game.map.ScrTerrainGrid}, visibility, minerals and gas): units are the units of the active
 * creation list of the SC:R unit pool, with bounds from their sprite position and {@code units.dat} dimensions (as the unit finder reads them)
 * and hit points and shields as the status panel shows them (whole points of the unit and {@code units.dat}). The SC:R part gives the HUD
 * (console, command card, status panel, minimap, portraits, observer panels) the SC:R game's data and read-side services; the HUD reaches
 * the game through this view and changes it only through commands and its own presentation state.
 * <p>
 * The local player is the console's player, which the console model sets every frame; a replay viewer without player of its own still shows
 * a player's console.
 */
public final class ScrGameView implements GameView {
    /**
     * The local player index of a view without local player.
     */
    public static final int NO_LOCAL_PLAYER = -1;

    private static final int SHIELD_FRACTION_BITS = 8;
    /**
     * The number of damage layers of an SC:R unit: its shields.
     */
    private static final int SHIELD_LAYER_COUNT = 1;
    private static final int NO_POOL_INDEX = 0;
    private static final @NotNull List<@NotNull ResourceKind> RESOURCE_KINDS = List.of(ScrResources.MINERALS, ScrResources.GAS);
    /**
     * The player colours by display colour index ({@code tunit.pcx} row), packed {@code 0xRRGGBB}: red, blue, teal, purple, orange, brown,
     * white, yellow, green, pale yellow, tan and azure; slot {@code n} starts with colour {@code n}.
     */
    private static final int @NotNull [] PLAYER_COLORS = {0xF40404, 0x0C48CC, 0x2CB494, 0x88409C, 0xF88C14, 0x703014, 0xCCE0D0, 0xFCFC38,
                                                          0x088008, 0xFCFC7C, 0xECC4B0, 0x4068D4};

    /**
     * Reads one SC:R unit for the engine view.
     */
    private final class ScrUnitView implements UnitView {
        private final @NotNull Unit unit;

        private ScrUnitView(@NotNull Unit unit) {
            this.unit = unit;
        }

        /**
         * @return the unit, the identity commands name
         */
        @Override
        public org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit getUnit() {
            return this.unit;
        }

        /**
         * @return the unit's type, or {@code null} for a free pool slot
         */
        @Override
        public @Nullable UnitType getType() {
            return this.unit.getType();
        }

        /**
         * @return the owner, or {@code null} for a free pool slot
         */
        @Override
        public @Nullable Player getOwner() {
            return ScrGameView.this.findPlayer(this.unit.getOwnerIndex());
        }

        /**
         * @return the world x of the position
         */
        @Override
        public int getX() {
            Sprite sprite = this.unit.getSpriteOrNull();

            return sprite != null ? (short) sprite.getX() : this.unit.getPositionX();
        }

        /**
         * @return the world y of the position
         */
        @Override
        public int getY() {
            Sprite sprite = this.unit.getSpriteOrNull();

            return sprite != null ? (short) sprite.getY() : this.unit.getPositionY();
        }

        /**
         * @return the left edge of the bounds
         */
        @Override
        public int getLeft() {
            return this.getX() - (short) ScrGameView.this.getUnitTypes().getDimensionLeft(this.unit.getUnitId());
        }

        /**
         * @return the top edge of the bounds
         */
        @Override
        public int getTop() {
            return this.getY() - (short) ScrGameView.this.getUnitTypes().getDimensionUp(this.unit.getUnitId());
        }

        /**
         * @return the right edge of the bounds, inclusive
         */
        @Override
        public int getRight() {
            return this.getX() + (short) ScrGameView.this.getUnitTypes().getDimensionRight(this.unit.getUnitId());
        }

        /**
         * @return the bottom edge of the bounds, inclusive
         */
        @Override
        public int getBottom() {
            return this.getY() + (short) ScrGameView.this.getUnitTypes().getDimensionDown(this.unit.getUnitId());
        }

        /**
         * @return {@code true} if the unit is placed on the map, {@code false} otherwise
         */
        @Override
        public boolean isPlaced() {
            Sprite sprite = this.unit.getSpriteOrNull();

            return sprite != null && (sprite.getFlags() & Sprite.FLAG_HIDDEN) == 0;
        }

        /**
         * @return the hit points in whole points, a started point counted as whole
         */
        @Override
        public int getHitPoints() {
            return UnitQueries.getHitPoints(this.unit);
        }

        /**
         * @return the hit points of the unit's type in its type table, {@code 0} for a free pool slot
         */
        @Override
        public int getMaximumHitPoints() {
            return UnitQueries.getMaximumHitPoints(ScrGameView.this.getUnitTypes(), this.unit);
        }

        /**
         * Returns the number of damage layers of the unit, for a bar each: the layers of its type in chain order
         * ({@link org.fogofwarlabs.sc4j.engine.content.combat.DamageLayer}).
         * <p>
         * An SC:R unit has one, its shields ({@link org.fogofwarlabs.sc4j.scr.game.combat.ScrShieldLayer}).
         *
         * @return the number of damage layers, {@code 0} for a free pool slot
         */
        @Override
        public int getDamageLayerCount() {
            return SHIELD_LAYER_COUNT;
        }

        /**
         * Returns the name of a damage layer, which its bar shows.
         *
         * @param layer the index of the layer, {@code 0} up to the layer count excluded
         * @return the name of the layer
         * @throws IndexOutOfBoundsException if the unit has no such layer
         */
        @Override
        public @NotNull String getDamageLayerName(int layer) {
            return ScrGameView.this.requireShieldLayer(layer).getName();
        }

        /**
         * Returns the points a damage layer holds now.
         * <p>
         * The shield points are cut to whole points.
         *
         * @param layer the index of the layer, {@code 0} up to the layer count excluded
         * @return the points in whole points
         * @throws IndexOutOfBoundsException if the unit has no such layer
         */
        @Override
        public int getDamageLayerPoints(int layer) {
            return ScrGameView.this.requireShieldLayer(layer).getPoints(this.unit) >> SHIELD_FRACTION_BITS;
        }

        /**
         * Returns the points a damage layer holds when it is full.
         *
         * @param layer the index of the layer, {@code 0} up to the layer count excluded
         * @return the points in whole points, {@code 0} if the unit's type has none of the layer
         * @throws IndexOutOfBoundsException if the unit has no such layer
         */
        @Override
        public int getMaximumDamageLayerPoints(int layer) {
            return ScrGameView.this.requireShieldLayer(layer).getMaximumPoints(this.unit) >> SHIELD_FRACTION_BITS;
        }

        /**
         * @return the current order type, or {@code null} if the unit has no order
         */
        @Override
        public @Nullable OrderType getOrderType() {
            return this.unit.getOrderType();
        }
    }

    private final @NotNull Game game;

    private int localPlayer;
    private final @NotNull List<org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit> units;
    private final @NotNull List<org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit> unitList;
    private @Nullable ScrUnitView @NotNull [] unitViews;

    /**
     * Creates a {@link ScrGameView} instance without local player.
     *
     * @param game the SC:R game
     */
    public ScrGameView(@NotNull Game game) {
        this.game = game;

        this.localPlayer = NO_LOCAL_PLAYER;
        this.units       = new ArrayList<>();
        this.unitList    = Collections.unmodifiableList(this.units);
        this.unitViews   = new ScrUnitView[0];
    }

    /**
     * Returns the SC:R game data of the shown game.
     *
     * @return the SC:R game data: the type tables of the game
     * @throws NullPointerException if the game was built without game data
     */
    public @NotNull GameData requireGameData() {
        return this.game.requireGameData();
    }

    private @NotNull ScrShieldLayer requireShieldLayer(int layer) {
        if (layer < 0 || layer >= SHIELD_LAYER_COUNT) {
            throw Exceptions.newIndexOutOfBoundsException("No damage layer {0}; an SC:R unit has {1}.", layer, SHIELD_LAYER_COUNT);
        }

        return this.game.getUnitDamage().getShieldLayer();
    }

    /**
     * @return the map of the game
     */
    public @NotNull GameMap getMap() {
        return this.game.getMap();
    }

    /**
     * @return the player table of the game
     */
    public @NotNull PlayerTable getPlayerTable() {
        return this.game.getPlayerTable();
    }

    /**
     * @return the unit system of the game: the unit pool and the unit finder
     */
    public @NotNull UnitSystem getUnitSystem() {
        return this.game.getUnitSystem();
    }

    /**
     * @return the unit queries of the game
     */
    public @NotNull UnitQueries getUnitQueries() {
        return this.game.getUnitQueries();
    }

    /**
     * @return the unit targeting rules of the game
     */
    public @NotNull UnitTargeting getUnitTargeting() {
        return this.game.getUnitTargeting();
    }

    /**
     * @return the local vision of the game
     */
    public @NotNull LocalVision getLocalVision() {
        return this.game.getLocalVision();
    }

    /**
     * @return the requirement system of the game
     */
    public @NotNull ScrRequirementSystem getRequirements() {
        return this.game.getRequirements();
    }

    /**
     * @return the cargo of the game's transports, read only
     */
    public @NotNull TransportCargo getUnitTransport() {
        return this.game.getUnitTransport();
    }

    /**
     * @return the spell rules of the game
     */
    public @NotNull UnitSpells getUnitSpells() {
        return this.game.getUnitSpells();
    }

    /**
     * @return the energy limits of the game's units, read only
     */
    public @NotNull UnitEnergyLimits getUnitInitializer() {
        return this.game.getUnitInitializer();
    }

    /**
     * Tests whether a player can pay for a unit, as the native production gate does.
     *
     * @param player      the player
     * @param unitType    the units.dat type
     * @param checkSupply whether supply is tested
     * @param showError   whether a failure shows its error
     * @return {@code true} if the player can pay for the unit, {@code false} otherwise
     */
    public boolean testUnitCost(int player, int unitType, boolean checkSupply, boolean showError) {
        return this.game.getCostTests().testUnit(this.game, player, unitType, checkSupply, showError);
    }

    /**
     * @return the placement evaluation of the game, which places nothing
     */
    public @NotNull PlacementEvaluator getUnitPlacement() {
        return this.game.getUnitPlacement();
    }

    /**
     * Returns the shown name of a unit type: the map's name for it, if the map renames it.
     *
     * @param unitType the units.dat type
     * @return the map's name, or {@code null} if the map keeps the type's own name
     */
    public @Nullable String getUnitDisplayName(int unitType) {
        return this.game.getUnitDisplayName(unitType);
    }

    /**
     * @return the settings the game started with, or {@code null} for a game without settings
     */
    public @Nullable GameSettings getGameSettings() {
        return this.game.getGameSettings();
    }

    /**
     * @return the frame state of the game, or {@code null} before the first frame
     */
    public @Nullable GameFrame getGameFrame() {
        return this.game.getFrame();
    }

    /**
     * @return the number of network turns the game ran
     */
    public int getTurnCount() {
        return this.game.getTurnCount();
    }

    /**
     * @return the cheat flags of the game
     */
    public int getCheatFlags() {
        return this.game.getCheatFlags();
    }

    /**
     * @return the game context: the setup values the game started with
     */
    public @NotNull GameContext getGameContext() {
        return this.game.getGameContext();
    }

    /**
     * @return the selection the game's commands act on, or {@code null} before it exists
     */
    public @Nullable UnitSelection getSelection() {
        return this.game.getSelection();
    }

    /**
     * @return the trigger context of the game, or {@code null} for a game without triggers
     */
    public @Nullable TriggerContext getTriggerContext() {
        return this.game.getTriggerContext();
    }

    /**
     * @return the frame the game shows, the SC:R frame counter
     */
    public int getCurrentFrame() {
        return this.game.getCurrentFrame();
    }

    /**
     * @return the number of turn markers the command dispatch has passed, for diagnostics
     */
    public int getCommandDispatchMarkerCount() {
        return this.game.getCommandDispatchMarkerCount();
    }

    /**
     * @return the state of the game's random generator, for diagnostics
     */
    public int getRandomState() {
        return this.game.getRandom().getState();
    }

    /**
     * @return the non-zero value while the game's random generator is enabled, {@code 0} otherwise, for diagnostics
     */
    public int getRandomEnabled() {
        return this.game.getRandom().getEnabled();
    }

    /**
     * @return the number of values the game's random generator produced, for diagnostics
     */
    public int getRandomCallCount() {
        return this.game.getRandom().getTotalRandomCounts();
    }

    /**
     * @return the sprite pool of the game, whose sprites the world view draws, or {@code null} before it exists
     */
    public @Nullable SpritePool getSpritePool() {
        return this.game.getSpriteSystem().getSpritePool();
    }

    /**
     * @return {@code true} if the game has plugins of the mod's game extensions, which may draw images with other graphics ({@link #findImageSheet}),
     * {@code false} otherwise
     */
    public boolean hasPlugins() {
        return this.game.getPlugins().length != 0;
    }

    /**
     * Finds the graphics a sprite image draws instead of its installed GRP: the first sheet a plugin of the game gives
     * ({@link ScrGamePlugin#findImageSheet}).
     *
     * @param image an image of a drawn sprite
     * @return the frames the image draws, or {@code null} for its installed graphics
     */
    public @Nullable ScrImageSheet findImageSheet(@NotNull SpriteImage image) {
        for (ScrGamePlugin plugin : this.game.getPlugins()) {
            ScrImageSheet sheet = plugin.findImageSheet(image);

            if (sheet != null) {
                return sheet;
            }
        }

        return null;
    }

    /**
     * Finds the HD graphics a sprite image draws instead of its installed anim: the first anims a plugin of the game gives
     * ({@link ScrGamePlugin#findImageAnimation}).
     *
     * @param image an image of a drawn sprite
     * @return the anims the image draws, or {@code null} for its installed anim
     */
    public @Nullable ScrImageAnimation findImageAnimation(@NotNull SpriteImage image) {
        for (ScrGamePlugin plugin : this.game.getPlugins()) {
            ScrImageAnimation animation = plugin.findImageAnimation(image);

            if (animation != null) {
                return animation;
            }
        }

        return null;
    }

    /**
     * Finds the image type whose installed graphics a sprite image draws instead of its own: the first image type a plugin of the game gives
     * ({@link ScrGamePlugin#findImageTypeId}).
     *
     * @param image an image of a drawn sprite
     * @return the image type, or {@code -1} for the image's own
     */
    public int findImageTypeId(@NotNull SpriteImage image) {
        for (ScrGamePlugin plugin : this.game.getPlugins()) {
            int imageType = plugin.findImageTypeId(image);

            if (imageType >= 0) {
                return imageType;
            }
        }

        return -1;
    }

    /**
     * Finds the frame a sprite image draws instead of its own: the first frame a plugin of the game gives ({@link ScrGamePlugin#findImageFrame}).
     *
     * @param image an image of a drawn sprite
     * @return the frame index, or {@code -1} for the image's own frame
     */
    public int findImageFrame(@NotNull SpriteImage image) {
        for (ScrGamePlugin plugin : this.game.getPlugins()) {
            int frame = plugin.findImageFrame(image);

            if (frame >= 0) {
                return frame;
            }
        }

        return -1;
    }

    /**
     * Finds the draw function a sprite image draws with instead of its own: the first draw function a plugin of the game gives
     * ({@link ScrGamePlugin#findImageDrawFunction}).
     *
     * @param image an image of a drawn sprite
     * @return the draw function, or {@code -1} for the image's own
     */
    public int findImageDrawFunction(@NotNull SpriteImage image) {
        for (ScrGamePlugin plugin : this.game.getPlugins()) {
            int drawFunction = plugin.findImageDrawFunction(image);

            if (drawFunction >= 0) {
                return drawFunction;
            }
        }

        return -1;
    }

    /**
     * Finds the picture a terrain megatile draws instead of its installed one: the first picture a plugin of the game gives
     * ({@link ScrGamePlugin#findTerrainTile}).
     *
     * @param megatile the megatile index of a drawn tile
     * @return the palette indices, or {@code null} for the installed picture
     */
    public byte @Nullable [] findTerrainTile(int megatile) {
        for (ScrGamePlugin plugin : this.game.getPlugins()) {
            byte[] picture = plugin.findTerrainTile(megatile);

            if (picture != null) {
                return picture;
            }
        }

        return null;
    }

    /**
     * Finds the picture drawn over the game screen and its console: the first overlay a plugin of the game gives
     * ({@link ScrGamePlugin#findScreenOverlay}).
     *
     * @return the overlay, or {@code null} for none
     */
    public @Nullable ScrScreenOverlay findScreenOverlay() {
        for (ScrGamePlugin plugin : this.game.getPlugins()) {
            ScrScreenOverlay overlay = plugin.findScreenOverlay();

            if (overlay != null) {
                return overlay;
            }
        }

        return null;
    }

    /**
     * @return {@code true} if a plugin of the game hides the console ({@link ScrGamePlugin#isConsoleHidden}), {@code false} otherwise
     */
    public boolean isConsoleHidden() {
        for (ScrGamePlugin plugin : this.game.getPlugins()) {
            if (plugin.isConsoleHidden()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Finds the resource icons the console draws: the first sheet a plugin of the game gives ({@link ScrGamePlugin#findResourceIcons}).
     *
     * @return the indexed sheet, or {@code null} for the installed icons
     */
    public @Nullable SpriteSheet findResourceIcons() {
        for (ScrGamePlugin plugin : this.game.getPlugins()) {
            SpriteSheet icons = plugin.findResourceIcons();

            if (icons != null) {
                return icons;
            }
        }

        return null;
    }

    /**
     * Finds how the console is placed: the first placement a plugin of the game gives ({@link ScrGamePlugin#findConsolePlacement}).
     *
     * @return the placement, or {@code null} for the installed console
     */
    public @Nullable ScrConsolePlacement findConsolePlacement() {
        for (ScrGamePlugin plugin : this.game.getPlugins()) {
            ScrConsolePlacement placement = plugin.findConsolePlacement();

            if (placement != null) {
                return placement;
            }
        }

        return null;
    }

    /**
     * Finds the text a plugin shows for a {@code stat_txt} string ({@link StatTextOverrides}).
     *
     * @param identifier the one-based string identifier
     * @return the text, or {@code null} if no plugin overrides the string
     */
    public @Nullable String findStatTextOverride(int identifier) {
        return this.game.getInterfaceLink().getStatTextOverrides().find(identifier);
    }

    /**
     * @return the local command card state the game plugins set: the script button states and the requested button set
     */
    public @NotNull ScrButtonStates getButtonStates() {
        return this.game.getInterfaceLink().getButtonStates();
    }

    /**
     * Hands a command card button input to the game's plugins in extension order until one consumes it ({@link ScrGamePlugin#onButtonInput}).
     *
     * @param input the button input
     * @return {@code true} if a plugin consumed the input, {@code false} otherwise
     */
    public boolean deliverButtonInput(@NotNull ScrButtonInput input) {
        for (ScrGamePlugin plugin : this.game.getPlugins()) {
            if (plugin.onButtonInput(input)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Finds the picture the console draws instead of an installed art file: the first picture a plugin of the game gives
     * ({@link ScrGamePlugin#findConsoleArt}).
     *
     * @param art the art file
     * @return the picture, or {@code null} for the installed file
     */
    public @Nullable Picture findConsoleArt(@NotNull ScrConsoleArt art) {
        for (ScrGamePlugin plugin : this.game.getPlugins()) {
            Picture picture = plugin.findConsoleArt(art);

            if (picture != null) {
                return picture;
            }
        }

        return null;
    }

    /**
     * Finds the picture the console draws for a resource icon: the first picture a plugin of the game gives
     * ({@link ScrGamePlugin#findResourceIconPicture}).
     *
     * @param icon the resource icon frame
     * @return the picture, or {@code null} for the icon of {@link #findResourceIcons} or the installed icon
     */
    public @Nullable Picture findResourceIconPicture(int icon) {
        for (ScrGamePlugin plugin : this.game.getPlugins()) {
            Picture picture = plugin.findResourceIconPicture(icon);

            if (picture != null) {
                return picture;
            }
        }

        return null;
    }

    /**
     * Hands the commands of one space of every plugin's draw list ({@link ScrGamePlugin#findDrawList}) to a painter, in extension order.
     *
     * @param space   the space
     * @param painter the painter
     */
    public void paintDrawLists(ScrDrawList.@NotNull Space space, ScrDrawList.@NotNull Painter painter) {
        for (ScrGamePlugin plugin : this.game.getPlugins()) {
            ScrDrawList list = plugin.findDrawList();

            if (list != null) {
                list.paint(space, painter);
            }
        }
    }

    /**
     * @return the path pool of the game, whose paths the debug overlay draws, or {@code null} before it exists
     */
    public @Nullable PathPool getPathPool() {
        return this.game.getPathSystem().getPathPool();
    }

    /**
     * @return the terrain contours of the game, whose regions the debug overlay draws, or {@code null} before they exist
     */
    public @Nullable TerrainContours getTerrainContours() {
        return this.game.getPathSystem().getTerrainContours();
    }

    /**
     * Creates an image script preview of a map placement in an isolated world copied from the game's data, map shape and random state.
     *
     * @param placement the placement to preview
     * @return the created {@link ImageScriptPreview} instance
     */
    public @NotNull ImageScriptPreview createImageScriptPreview(@NotNull ChkUnit placement) {
        return new ImageScriptPreview(this.game, placement);
    }

    /**
     * Checks whether this view shows a game.
     *
     * @param shown the game
     * @return {@code true} if this view shows the game, {@code false} otherwise
     */
    public boolean shows(@NotNull Game shown) {
        return this.game == shown;
    }

    /**
     * Sets the local player: the console's player.
     *
     * @param localPlayer the player index, or {@link #NO_LOCAL_PLAYER}
     */
    public void setLocalPlayerIndex(int localPlayer) {
        this.localPlayer = localPlayer;
    }


    /**
     * @return the number of frames that advanced the game: the SC:R frame counter
     */
    @Override
    public int getFrame() {
        return this.game.getCurrentFrame();
    }

    /**
     * @return the players of the game in slot order
     */
    @Override
    public @NotNull List<@NotNull Player> getPlayers() {
        org.fogofwarlabs.sc4j.engine.game.Game engineGame = this.game.getEngineGame();
        List<Player> players = new ArrayList<>(engineGame.getPlayerCount());

        for (int index = 0; index < engineGame.getPlayerCount(); index++) {
            players.add(engineGame.getPlayer(index));
        }

        return players;
    }

    /**
     * @return the player this client plays, or {@code null} for an observer or a replay viewer
     */
    @Override
    public @Nullable Player getLocalPlayer() {
        return this.findPlayer(this.localPlayer);
    }

    /**
     * @return the terrain of the map
     */
    @Override
    public @NotNull TerrainGrid getTerrain() {
        return this.game.getTerrain();
    }

    /**
     * Returns the units of the game: the active creation list of the unit pool, in list order. The view refills one list at each call.
     *
     * @return the units at the time of the call, a read-only list the next call refills
     */
    @Override
    public @NotNull List<org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit> getUnits() {
        UnitPool pool = this.game.getUnitSystem().getUnitPool();

        this.units.clear();

        if (pool != null) {
            for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != NO_POOL_INDEX; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
                this.units.add(pool.getUnit(poolIndex));
            }
        }

        return this.unitList;
    }

    /**
     * Returns the read-only state of a unit: the view of its pool slot, created on first use.
     *
     * @param unit the unit, an SC:R unit of the game
     * @return the unit's view
     */
    @Override
    public @NotNull UnitView getUnitView(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit) {
        int index = unit.getIndex();

        if (index >= this.unitViews.length) {
            UnitPool pool = this.game.getUnitSystem().getUnitPool();
            ScrUnitView[] views = new ScrUnitView[Math.max(index, pool != null ? pool.getCapacity() : 0) + 1];

            System.arraycopy(this.unitViews, 0, views, 0, this.unitViews.length);
            this.unitViews = views;
        }

        ScrUnitView view = this.unitViews[index];

        if (view == null || view.unit != unit) {
            view = new ScrUnitView(Unit.of(unit));

            this.unitViews[index] = view;
        }

        return view;
    }

    /**
     * Finds the placed units whose bounds overlap a world rectangle, edges inclusive, in creation list order.
     *
     * @param left   the left edge
     * @param top    the top edge
     * @param right  the right edge
     * @param bottom the bottom edge
     * @param result the collection the found units are added to
     */
    @Override
    public void findUnits(int left, int top, int right, int bottom, @NotNull Collection<org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit> result) {
        for (org.fogofwarlabs.sc4j.engine.entity.Unit unit : this.getUnits()) {
            UnitView view = this.getUnitView(unit);

            if (view.isPlaced() && view.getLeft() <= right && view.getRight() >= left && view.getTop() <= bottom && view.getBottom() >= top) {
                result.add(unit);
            }
        }
    }

    /**
     * Finds the unit shown at a world position: of the placed units whose bounds hold the position, the one with the lowest bottom edge, the
     * first in creation list order among equals. The SC:R client picks by sprite pixels instead; this is the engine view's bounds answer.
     *
     * @param x the world x
     * @param y the world y
     * @return the unit, or {@code null} if no unit holds the position
     */
    @Override
    public org.fogofwarlabs.sc4j.engine.entity.@Nullable Unit findUnitAt(int x, int y) {
        org.fogofwarlabs.sc4j.engine.entity.Unit found = null;
        int foundBottom = Integer.MIN_VALUE;

        for (org.fogofwarlabs.sc4j.engine.entity.Unit unit : this.getUnits()) {
            UnitView view = this.getUnitView(unit);

            if (view.isPlaced() && x >= view.getLeft() && x <= view.getRight() && y >= view.getTop() && y <= view.getBottom() &&
                (found == null || view.getBottom() > foundBottom)) {
                found       = unit;
                foundBottom = view.getBottom();
            }
        }

        return found;
    }

    /**
     * Checks whether a player currently sees a world position.
     *
     * @param player the player
     * @param x      the world x
     * @param y      the world y
     * @return {@code true} if the player sees the position, {@code false} otherwise
     */
    @Override
    public boolean isVisible(@NotNull Player player, int x, int y) {
        return this.game.getEngineGame().getVisibility().isVisible(player, x, y);
    }

    /**
     * Checks whether a player has ever seen a world position.
     *
     * @param player the player
     * @param x      the world x
     * @param y      the world y
     * @return {@code true} if the position is explored, {@code false} otherwise
     */
    @Override
    public boolean isExplored(@NotNull Player player, int x, int y) {
        return this.game.getEngineGame().getVisibility().isExplored(player, x, y);
    }

    /**
     * Checks whether a player sees a unit.
     *
     * @param player the player
     * @param unit   the unit
     * @return {@code true} if the player sees the unit, {@code false} otherwise
     */
    @Override
    public boolean isUnitVisible(@NotNull Player player, org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit) {
        return this.game.getEngineGame().getVisibility().isUnitVisible(player, unit);
    }

    /**
     * @return the resource kinds of the game in their order: minerals and gas
     */
    @Override
    public @NotNull List<@NotNull ResourceKind> getResourceKinds() {
        return RESOURCE_KINDS;
    }

    /**
     * Returns the amount of a resource a player holds.
     *
     * @param player       the player
     * @param resourceKind the resource kind
     * @return the amount
     */
    @Override
    public int getResourceAmount(@NotNull Player player, @NotNull ResourceKind resourceKind) {
        return this.game.getEngineGame().getEconomy().getAmount(player, resourceKind);
    }

    /**
     * Returns the faction a player plays: the faction of the player's setup slot.
     *
     * @param player the player
     * @return the faction, or {@code null} for a player without faction in the setup
     */
    @Override
    public @Nullable Faction getFaction(@NotNull Player player) {
        for (org.fogofwarlabs.sc4j.engine.game.PlayerSlot slot : this.game.getEngineGame().getPlayers()) {
            if (slot.getSlotIndex() == player.getIndex()) {
                return slot.getFaction();
            }
        }

        return null;
    }

    /**
     * Returns the supply a player's units of a faction use.
     *
     * @param player  the player
     * @param faction the faction
     * @return the used supply
     */
    @Override
    public int getSupplyUsed(@NotNull Player player, @NotNull Faction faction) {
        return this.game.getEngineGame().getEconomy().getSupplyUsed(player, faction);
    }

    /**
     * Returns the supply a player's units of a faction provide.
     *
     * @param player  the player
     * @param faction the faction
     * @return the provided supply
     */
    @Override
    public int getSupplyProvided(@NotNull Player player, @NotNull Faction faction) {
        return this.game.getEngineGame().getEconomy().getSupplyProvided(player, faction);
    }

    /**
     * Returns the team colour of a player: the colour chosen for the game (the slot's display colour index); a colour index outside the
     * standard colours, or a player without a slot, gives the default colour of its slot.
     *
     * @param player the player
     * @return the colour, packed {@code 0xRRGGBB}
     */
    @Override
    public int getTeamColor(@NotNull Player player) {
        org.fogofwarlabs.sc4j.scr.game.player.Player slot = this.game.getPlayerTable().getPlayer(player.getIndex());
        int color = slot != null ? slot.getColor() : -1;

        return color >= 0 && color < PLAYER_COLORS.length ? PLAYER_COLORS[color]
                                                          : PLAYER_COLORS[Math.floorMod(player.getIndex(), PLAYER_COLORS.length)];
    }

    private @Nullable Player findPlayer(int index) {
        org.fogofwarlabs.sc4j.engine.game.Game engineGame = this.game.getEngineGame();

        return index >= 0 && index < engineGame.getPlayerCount() ? engineGame.getPlayer(index) : null;
    }

    private @NotNull ScrUnitTypeView getUnitTypes() {
        return this.game.requireGameData().getUnitTypes();
    }
}
