package org.fogofwarlabs.sc4j.scr.client.ui.observer;

import org.fogofwarlabs.sc4j.scr.client.ui.console.ConsoleLayout;
import org.fogofwarlabs.sc4j.scr.client.ui.console.DialogLayout;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.AssetResolver;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceAssets;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Checks the observer console layout of a replay against its installed frames.
 */
final class ObserverConsoleUtilsTest {
    /**
     * The interface width of a {@code 16:9} screen: {@code trunc(16 / 9 * 480)}.
     */
    private static final int INTERFACE_WIDTH = 853;
    private static final int MINIMAP_AREA_CONTROL = 1;

    @Test
    void minimap_frame_follows_the_timer() {
        Assertions.assertEquals(ObserverConsoleUtils.SQUARE_RIGHT, ObserverConsoleUtils.getMinimapFrame(true));
        Assertions.assertEquals(ObserverConsoleUtils.SQUARE_RIGHT_NO_TIMER, ObserverConsoleUtils.getMinimapFrame(false));
    }

    /**
     * The minimap dialog is placed by the frame with the timer band, so the minimap keeps its place and lies inside the drawn frame with and without
     * the game time.
     */
    @RequiresInstallation
    @Test
    void minimap_lies_inside_its_drawn_frame_with_and_without_the_timer() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            InterfaceAssets assets = InterfaceAssets.load(ScrResourceProvider.borrow(storage), 0);
            ConsoleLayout replay = ConsoleLayout.load(storage, 0, true);

            for (AssetResolver.AssetSet assetSet : AssetResolver.AssetSet.values()) {
                assets.setAssetSet(assetSet);

                InterfaceAssets.ScaledImage squareRight = assets.decodeTexture(ObserverConsoleUtils.SQUARE_RIGHT);
                InterfaceAssets.ScaledImage squareFull = assets.decodeTexture(ObserverConsoleUtils.SQUARE_FULL);
                ConsoleLayout console = ObserverConsoleUtils.createLayout(replay,
                                                                          INTERFACE_WIDTH,
                                                                          getHeight(squareRight),
                                                                          getWidth(squareFull), getHeight(squareFull));
                DialogLayout.Control area = console.getMinimap().getControl(MINIMAP_AREA_CONTROL);

                for (boolean timer : new boolean[]{true, false}) {
                    int frameTop = ConsoleLayout.INTERFACE_HEIGHT - getHeight(assets.decodeTexture(ObserverConsoleUtils.getMinimapFrame(timer)));
                    String message = assetSet + (timer ? " with" : " without") + " the timer";

                    Assertions.assertTrue(area.getY() >= frameTop, message + ": the minimap starts above its frame");
                    Assertions.assertTrue(area.getY() + area.getHeight() <= ConsoleLayout.INTERFACE_HEIGHT, message + ": the minimap ends below the screen");
                }
            }
        }
    }

    private static int getWidth(InterfaceAssets.@NotNull ScaledImage image) {
        return image.getImage().getWidth() / image.getScale();
    }

    private static int getHeight(InterfaceAssets.@NotNull ScaledImage image) {
        return image.getImage().getHeight() / image.getScale();
    }
}
