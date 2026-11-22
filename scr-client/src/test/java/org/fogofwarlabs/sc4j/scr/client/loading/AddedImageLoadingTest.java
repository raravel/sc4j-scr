package org.fogofwarlabs.sc4j.scr.client.loading;

import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.engine.client.host.ClientHost;
import org.fogofwarlabs.sc4j.engine.client.host.HostWindow;
import org.fogofwarlabs.sc4j.engine.client.host.WindowSettings;
import org.fogofwarlabs.sc4j.engine.client.scene.LoadingService;
import org.fogofwarlabs.sc4j.scr.client.BeaconLinkAccess;
import org.fogofwarlabs.sc4j.scr.client.ClientContext;
import org.fogofwarlabs.sc4j.scr.client.ReleaseState;
import org.fogofwarlabs.sc4j.scr.client.ReleaseStatus;
import org.fogofwarlabs.sc4j.scr.client.configuration.InstallLayout;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.client.world.WorldSceneExtraction;
import org.fogofwarlabs.sc4j.scr.client.world.WorldSkin;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedGraphics;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.dat.ImagesDat;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.load.ScrIscriptImporter;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.test.AddedGraphicsScenario;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.visibility.LocalVision;
import org.fogofwarlabs.sc4j.scr.render.SceneRenderer;
import org.fogofwarlabs.sc4j.scr.render.WorldRenderer;
import org.fogofwarlabs.sc4j.scr.render.hd.HdAnimations;
import org.fogofwarlabs.sc4j.scr.render.hd.HdImageSet;
import org.fogofwarlabs.sc4j.scr.render.hd.HdRenderAssets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks that the loading screen's tasks prepare the images a mod adds before a game, as they prepare the installed images: the client data loads
 * their SD frames and the HD image set uploads them and counts them in its progress, so that the game creates and first draws an added unit with no
 * frame file read and no texture upload, in the HD and the classic graphics.
 */
@RequiresInstallation
final class AddedImageLoadingTest {
    private static final @NotNull String LOCALE = "enUS";
    private static final int FRAME_SIZE = 128;
    private static final int UNIT_CREATION_X = 3560;
    private static final int UNIT_CREATION_Y = 2160;
    private static final int SPRITE_X = FRAME_SIZE / 2;
    private static final int SPRITE_Y = FRAME_SIZE / 2;
    /**
     * The render-thread time of one loading frame.
     */
    private static final long LOADING_BUDGET_NANOSECONDS = 16_000_000L;
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";

    private static void drawFrame(@NotNull WorldRenderer world, @NotNull SceneRenderer scene, @NotNull Game game) {
        List<String> warnings = new ArrayList<>();

        world.beginFrame(FRAME_SIZE, FRAME_SIZE, FRAME_SIZE, FRAME_SIZE, 0, 0, 1);

        try {
            new WorldSceneExtraction().draw(scene, world, new ScrGameView(game), null, owner -> owner, warnings::add);
        } finally {
            scene.finishScene();
            world.endFrame();
        }

        Assertions.assertTrue(warnings.isEmpty(), () -> String.join("\n", warnings));
    }

    /**
     * Draws the first frame that shows the added unit and checks that it reads no frame file.
     */
    private static void checkFirstFrame(@NotNull WorldRenderer world, @NotNull SceneRenderer scene, @NotNull Game game,
                                        @NotNull AddedGraphicsScenario added, @NotNull String graphics) {
        int fileReads = added.getFileReadCount();

        drawFrame(world, scene, game);

        Assertions.assertEquals(fileReads, added.getFileReadCount(), graphics + ": the first frame reads no frame file of the added images.");
    }


    @TempDir
    @Nullable Path directory;

    @Test
    void loading_prepares_added_images_so_the_first_game_frame_draws_them_without_loading() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        Path root = this.directory;

        Assertions.assertNotNull(root);

        try (ClientHost host = ClientHost.initialize()) {
            HostWindow window = host.createWindow(WindowSettings.builder(AddedImageLoadingTest.class.getSimpleName(), FRAME_SIZE, FRAME_SIZE).build());

            window.makeContextCurrent();
            host.createCapabilities();

            try (ClientContext client = ClientContext.open(InstallLayout.of(root),
                                                           ScrResourceProvider.open(ExternalInput.INSTALLATION.getPath(), ScrIscriptImporter::readLibrary),
                                                           LOCALE, null, BeaconLinkAccess.NONE,
                                                           ReleaseStatus.of(ReleaseVersion.CURRENT, ReleaseState.UNKNOWN, null))) {
                // As at the client start: the image set's task is created before the game data registers the images a mod adds (a mod's content
                // extension registers them while the client data task loads the game data) and runs after the client data task.
                HdImageSet images = new HdImageSet(HdAnimations.load(client.getResources(), HdRenderAssets.Quality.HD2, null), false);
                HdImageSetLoadTask imageTask = new HdImageSetLoadTask(images);
                LoadingService loading = new LoadingService();

                client.setHdImages(images);

                GameData gameData = client.getGameData();
                AddedGraphicsScenario added = AddedGraphicsScenario.register(gameData);
                ScrAddedGraphics graphics = gameData.getAddedGraphics();

                loading.enqueue(new ClientDataLoadTask(client));
                loading.enqueue(imageTask);

                while (loading.isLoading()) {
                    loading.advance(LOADING_BUDGET_NANOSECONDS);
                }

                Assertions.assertTrue(images.isLoaded());
                Assertions.assertEquals(ImagesDat.IMAGE_COUNT + graphics.getImages().size(), images.getLoadedImageCount(),
                                        "The HD load counts the added images.");
                Assertions.assertEquals(1, images.getLoadProgress());

                ReplayRuntime runtime = RunningGameScenario.start(gameData);
                Game game = runtime.getGame();
                GameMap map = game.getMap();
                int[] tiles = new int[map.getWidth() * map.getHeight()];

                for (int index = 0; index < tiles.length; index++) {
                    tiles[index] = map.getTileIdentifier(index);
                }

                try (WorldRenderer world = new WorldRenderer()) {
                    WorldSkin skin = new WorldSkin(HdRenderAssets.Quality.HD2);

                    try {
                        world.loadMap(ScrResourceProvider.borrow(client.getStorage(), graphics), map.getTileset(), map.getWidth(), map.getHeight(), tiles);
                        skin.openHdScene(world, client.getHdTilesetAssets(map.getTileset()), images, map, false, true);
                        world.setRenderScaleLimit(HdRenderAssets.Quality.HD2.getScale());

                        int fileReads = added.getFileReadCount();
                        long textureBytes = images.getTextureBytes();

                        RunningGameScenario.runInCommandDispatch(runtime, () -> {
                            Unit unit = RunningGameScenario.createUnit(game, added.getUnitTypeId(), UNIT_CREATION_X, UNIT_CREATION_Y,
                                                                       RunningGameScenario.HUMAN);
                            Sprite sprite = unit.getSpriteOrNull();

                            Assertions.assertNotNull(sprite);

                            game.getUnitPositioning().move(unit, SPRITE_X, SPRITE_Y);
                            game.getUnitVisibility().updateFog(unit);
                            sprite.setVisibilityFlags(LocalVision.ALL_PLAYERS);
                            game.getLocalVision().acceptDialog(LocalVision.ALL_PLAYERS, true);
                        });

                        Assertions.assertEquals(fileReads, added.getFileReadCount(), "The game creates the added images without reading their frames.");

                        checkFirstFrame(world, skin.prepareHdScene(true, false), game, added, "HD2");
                        Assertions.assertEquals(textureBytes, images.getTextureBytes(), "HD2: the first frame uploads no image.");

                        world.setRenderScaleLimit(1);
                        checkFirstFrame(world, skin.prepareClassicScene(world, client.getStorage(), graphics, map.getTileset()), game, added,
                                        "classic");
                    } finally {
                        skin.closeHdScene();
                        skin.closeClassicScene();
                    }
                }
            }
        }
    }
}
