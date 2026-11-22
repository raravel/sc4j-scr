package org.fogofwarlabs.sc4j.scr.client.world;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.host.FrameTiming;
import org.fogofwarlabs.sc4j.engine.client.projection.SquareProjection;
import org.fogofwarlabs.sc4j.engine.client.render.WorldLayer;
import org.fogofwarlabs.sc4j.engine.client.render.WorldView;
import org.fogofwarlabs.sc4j.engine.client.render.WorldViewport;
import org.fogofwarlabs.sc4j.scr.client.debug.FramePhaseUtils;
import org.fogofwarlabs.sc4j.scr.render.SceneRenderer;
import org.fogofwarlabs.sc4j.scr.render.WorldRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

/**
 * Draws the SC:R world of a frame as an engine {@link WorldView}: the SC:R layers terrain, scene (the sprites extracted from the game while it
 * plays) and scene finish (overlays, fog and lighting) over the scene backend of the frame's {@link WorldSkin}, then the client's own layers.
 * The skin and its backends reach the engine view only through these layers.
 */
public final class ScrWorldPass {
    /**
     * What the SC:R world layers read from the client for a frame.
     */
    public interface Context {
        /**
         * @return the running game's view
         */
        @NotNull ScrGameView getView();

        /**
         * @return {@code true} if the game plays (its sprites are drawn), {@code false} for a map preview
         */
        boolean isPlaybackRequested();

        /**
         * Prepares the read-only interface overlay the scene layer draws with the sprites (selection, placement, markers).
         *
         * @return the overlay, or {@code null} when the client has none
         */
        @Nullable WorldOverlayState prepareOverlay();

        /**
         * Reads the display colour of a player slot.
         *
         * @param owner the player slot
         * @return the colour index of the slot's player, else the slot itself
         */
        int getPlayerColor(int owner);

        /**
         * Shows a rendering warning.
         *
         * @param warning the warning text
         */
        void setAssetWarning(@NotNull String warning);

        /**
         * @return the frame timing that marks each drawn layer, or {@code null} when timing is off
         */
        @Nullable FrameTiming getFrameTiming();
    }

    /**
     * Draws the terrain of the world pass and marks its timing; terrain that cannot be read leaves an asset warning.
     */
    private final class TerrainLayer implements WorldLayer {
        /**
         * Draws the layer for a frame.
         *
         * @param viewport the frame's viewport
         */
        @Override
        public void draw(@NotNull WorldViewport viewport) {
            try {
                ScrGameView view = ScrWorldPass.this.context.getView();

                if (view.hasPlugins()) {
                    ScrWorldPass.this.requireScene().drawTerrain(view.getMap()::getTileIdentifier, view::findTerrainTile);
                } else {
                    ScrWorldPass.this.requireScene().drawTerrain(view.getMap()::getTileIdentifier);
                }
            } catch (IOException exception) {
                ScrWorldPass.this.context.setAssetWarning("Terrain: " + exception.getMessage());
            }

            ScrWorldPass.this.markTiming(FramePhaseUtils.TERRAIN);
        }
    }

    /**
     * Draws the sprites extracted from the game while it plays, then marks its timing.
     */
    private final class SceneLayer implements WorldLayer {
        /**
         * Draws the layer for a frame.
         *
         * @param viewport the frame's viewport
         */
        @Override
        public void draw(@NotNull WorldViewport viewport) {
            Context client = ScrWorldPass.this.context;

            if (client.isPlaybackRequested()) {
                // The world overlay reads the interface state, so it is prepared before the extraction.
                WorldOverlayState overlay = client.prepareOverlay();

                ScrWorldPass.this.sceneExtraction.draw(ScrWorldPass.this.requireScene(),
                                                       ScrWorldPass.this.requireRenderer(),
                                                       client.getView(),
                                                       overlay,
                                                       ScrWorldPass.this.playerColors,
                                                       ScrWorldPass.this.warnings);
            }

            ScrWorldPass.this.markTiming(FramePhaseUtils.SCENE_EXTRACTION);
        }
    }

    /**
     * Finishes the scene of the world pass (its overlays, fog and lighting) and marks its timing.
     */
    private final class SceneFinishLayer implements WorldLayer {
        /**
         * Draws the layer for a frame.
         *
         * @param viewport the frame's viewport
         */
        @Override
        public void draw(@NotNull WorldViewport viewport) {
            ScrWorldPass.this.requireScene().finishScene();
            ScrWorldPass.this.markTiming(FramePhaseUtils.SCENE_FINISH);
        }
    }

    private final @NotNull Context context;
    private final @NotNull WorldSceneExtraction sceneExtraction;
    private final @NotNull IntUnaryOperator playerColors;
    private final @NotNull Consumer<String> warnings;

    private final @NotNull WorldView worldView;
    private final @NotNull WorldViewport worldViewport;

    private @Nullable SceneRenderer scene;
    private @Nullable WorldRenderer renderer;

    /**
     * Creates a {@link ScrWorldPass} instance with the three SC:R layers followed by the client's own layers.
     *
     * @param context      what the layers read from the client
     * @param clientLayers the layers drawn after the SC:R layers, in order
     */
    public ScrWorldPass(@NotNull Context context, @NotNull List<WorldLayer> clientLayers) {
        List<WorldLayer> layers = new ArrayList<>(List.of(new TerrainLayer(), new SceneLayer(), new SceneFinishLayer()));

        layers.addAll(clientLayers);

        this.context         = context;
        this.sceneExtraction = new WorldSceneExtraction();
        this.playerColors    = context::getPlayerColor;
        this.warnings        = context::setAssetWarning;

        this.worldView     = new WorldView(layers);
        this.worldViewport = new WorldViewport(new SquareProjection());

        this.scene    = null;
        this.renderer = null;
    }

    /**
     * Returns the world renderer of the pass being drawn.
     *
     * @return the world renderer of the pass being drawn
     * @throws IllegalStateException if no world pass is being drawn
     */
    public @NotNull WorldRenderer requireRenderer() {
        WorldRenderer current = this.renderer;

        if (current == null) {
            throw Exceptions.newIllegalStateException("No world pass is being drawn.");
        }

        return current;
    }


    /**
     * Draws the world layers of a frame through the scene backend of the frame's skin.
     *
     * @param scene    the frame's scene backend
     * @param renderer the world renderer the backend draws with
     * @param width    the viewport width in pixels
     * @param height   the viewport height in pixels
     * @param cameraX  the camera's left world position
     * @param cameraY  the camera's top world position
     * @param zoom     the camera zoom
     * @throws IOException if a layer cannot read a resource it draws
     */
    public void draw(@NotNull SceneRenderer scene, @NotNull WorldRenderer renderer, int width, int height, float cameraX, float cameraY,
                     float zoom) throws IOException {
        this.scene    = scene;
        this.renderer = renderer;
        this.worldViewport.set(width, height, cameraX, cameraY, zoom);

        try {
            this.worldView.draw(this.worldViewport);
        } finally {
            this.scene    = null;
            this.renderer = null;
        }
    }

    /**
     * @return the scene backend of the pass being drawn
     * @throws IllegalStateException if no world pass is being drawn
     */
    private @NotNull SceneRenderer requireScene() {
        SceneRenderer current = this.scene;

        if (current == null) {
            throw Exceptions.newIllegalStateException("No world pass is being drawn.");
        }

        return current;
    }

    /**
     * Marks the timing of a drawn layer.
     *
     * @param phase the phase ({@link FramePhaseUtils})
     */
    private void markTiming(int phase) {
        FrameTiming timing = this.context.getFrameTiming();

        if (timing != null) {
            timing.mark(phase);
        }
    }
}
