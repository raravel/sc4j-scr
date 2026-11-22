package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.AssetResolver;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceAssets;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceImage;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the pens and plate positions of the classic and remastered console classes.
 */
final class ConsoleKindTest {
    /**
     * The halves of a span: the center of two edges and the middle of a sorted list.
     */
    private static final int HALVES = 2;
    private static final double MEDIAN_PAIR_DIVISOR = 2.0;
    private static final int CLASSIC_TIME_PLATE_LEFT = 288;
    private static final int CLASSIC_ZERG_TIME_PLATE_TOP = 345;
    private static final int CLASSIC_DEFAULT_TIME_PLATE_TOP = 350;
    private static final int CLASSIC_LOW_TIME_PLATE_TOP = 352;
    private static final int CLASSIC_APM_PLATE_LEFT = 140;
    private static final int CLASSIC_DEFAULT_APM_PLATE_TOP = 316;
    private static final int CLASSIC_PROTOSS_APM_PLATE_TOP = 310;
    private static final int CLASSIC_OBSERVER_APM_PLATE_TOP = 326;
    private static final int CLASSIC_APM_PEN_X = 398;
    private static final int CLASSIC_APM_PEN_Y = 365;
    private static final int REMASTERED_APM_PEN_X = 397;
    private static final int REMASTERED_APM_PEN_Y = 363;
    /**
     * The right end of the APM number in the 640-pixel interface: the interface's last pixel column less the pen.
     */
    private static final int CLASSIC_APM_TEXT_RIGHT = 241;
    private static final int REMASTERED_APM_TEXT_RIGHT = 242;
    /**
     * The interface width of a 1920x1032 screen and the APM number ends it gives: the pen moves right by {@code 126} and the end by {@code 127}.
     */
    private static final int WIDE_INTERFACE_WIDTH = 893;
    private static final int WIDE_CLASSIC_APM_TEXT_RIGHT = 368;
    private static final int WIDE_REMASTERED_APM_TEXT_RIGHT = 369;
    /**
     * The remastered time plate left and top of the Terran console, the default entry that the observer console also takes.
     */
    private static final int REMASTERED_DEFAULT_TIME_PLATE_LEFT = 301;
    private static final int REMASTERED_DEFAULT_TIME_PLATE_TOP = 354;
    private static final int REMASTERED_ZERG_TIME_PLATE_LEFT = 298;
    private static final int REMASTERED_ZERG_TIME_PLATE_TOP = 346;
    private static final int REMASTERED_PROTOSS_TIME_PLATE_LEFT = 310;
    private static final int REMASTERED_PROTOSS_TIME_PLATE_TOP = 352;
    private static final int REMASTERED_DEFAULT_APM_PLATE_LEFT = 201;
    private static final int REMASTERED_DEFAULT_APM_PLATE_TOP = 354;
    private static final int REMASTERED_ZERG_APM_PLATE_LEFT = 162;
    private static final int REMASTERED_ZERG_APM_PLATE_TOP = 346;
    private static final int REMASTERED_PROTOSS_APM_PLATE_LEFT = 210;
    private static final int REMASTERED_PROTOSS_APM_PLATE_TOP = 352;

    private static final @NotNull String @NotNull [] RACE_FOLDERS = {"zerg", "terran", "protoss"};
    private static final @NotNull String TIME_PLATE = "console_TIME.DDS";
    private static final @NotNull String APM_PLATE = "console_APM.DDS";
    /**
     * The largest distance between the time pen and the center of the plate's dark time window, in interface pixels: the
     * shared classic pen lies {@code 5.5} pixels right of the classic Zerg window.
     */
    private static final double WINDOW_CENTER_TOLERANCE = 6;
    /**
     * A row's dark run belongs to the window when it is longer than a quarter of the plate width.
     */
    private static final int WINDOW_MINIMUM_WIDTH_DIVISOR = 4;
    /**
     * The brightest color channel of a window pixel.
     */
    private static final int DARK_CHANNEL_LIMIT = 10;

    /**
     * Measures the horizontal center of a plate's dark time window: the median left and right of the longest dark run of
     * the window rows, the first rows from the top whose longest dark run is wider than a quarter of the plate.
     *
     * @param image the decoded plate
     * @param scale the texture pixels per interface pixel
     * @return the window center from the plate left, in interface pixels
     */
    private static double measureWindowCenter(@NotNull InterfaceImage image, int scale) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] colorChannels = image.getRgba();
        List<Integer> lefts = new ArrayList<>();
        List<Integer> rights = new ArrayList<>();

        for (int pixelY = 0; pixelY < height; pixelY++) {
            int bestLeft = 0;
            int bestRight = 0;
            int pixelX = 0;

            while (pixelX < width) {
                if (!isDark(colorChannels, (pixelY * width + pixelX) * OverlayPass.RGBA_BYTES)) {
                    pixelX++;

                    continue;
                }

                int start = pixelX;

                while (pixelX < width && isDark(colorChannels, (pixelY * width + pixelX) * OverlayPass.RGBA_BYTES)) {
                    pixelX++;
                }

                if (pixelX - start > bestRight - bestLeft) {
                    bestLeft  = start;
                    bestRight = pixelX;
                }
            }

            if (bestRight - bestLeft > width / WINDOW_MINIMUM_WIDTH_DIVISOR) {
                lefts.add(bestLeft);
                rights.add(bestRight);
            } else if (!lefts.isEmpty()) {
                break;
            }
        }

        Assertions.assertFalse(lefts.isEmpty(), "the plate has no dark window");

        return (median(lefts) + median(rights)) / HALVES / scale;
    }

    private static boolean isDark(byte @NotNull [] colorChannels, int offset) {
        int brightest = Math.max(Byte.toUnsignedInt(colorChannels[offset]),
                                 Math.max(Byte.toUnsignedInt(colorChannels[offset + OverlayPass.RGBA_GREEN]),
                                          Byte.toUnsignedInt(colorChannels[offset + OverlayPass.RGBA_BLUE])));

        return Byte.toUnsignedInt(colorChannels[offset + OverlayPass.RGBA_ALPHA]) > 0 && brightest <= DARK_CHANNEL_LIMIT;
    }

    private static double median(@NotNull List<Integer> values) {
        List<Integer> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / HALVES;

        return sorted.size() % HALVES == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / MEDIAN_PAIR_DIVISOR;
    }

    @Test
    void of_should_select_the_remastered_console_for_the_hd_art() {
        Assertions.assertEquals(ConsoleKind.CLASSIC, ConsoleKind.of(AssetResolver.AssetSet.SD));
        Assertions.assertEquals(ConsoleKind.REMASTERED, ConsoleKind.of(AssetResolver.AssetSet.HD));
        Assertions.assertEquals(ConsoleKind.REMASTERED, ConsoleKind.of(AssetResolver.AssetSet.HD2));
    }

    @Test
    void classic_plates_keep_one_left_and_take_the_default_entry_for_missing_types() {
        Assertions.assertEquals(CLASSIC_ZERG_TIME_PLATE_TOP, ConsoleKind.CLASSIC.getTimePlateTop(ConsoleType.ZERG));
        Assertions.assertEquals(CLASSIC_DEFAULT_TIME_PLATE_TOP,
                                ConsoleKind.CLASSIC.getTimePlateTop(ConsoleType.TERRAN),
                                "Terran takes the default entry");
        Assertions.assertEquals(CLASSIC_LOW_TIME_PLATE_TOP, ConsoleKind.CLASSIC.getTimePlateTop(ConsoleType.PROTOSS));
        Assertions.assertEquals(CLASSIC_LOW_TIME_PLATE_TOP, ConsoleKind.CLASSIC.getTimePlateTop(ConsoleType.OBSERVER));
        Assertions.assertEquals(CLASSIC_DEFAULT_APM_PLATE_TOP, ConsoleKind.CLASSIC.getApmPlateTop(ConsoleType.ZERG), "Zerg takes the default entry");
        Assertions.assertEquals(CLASSIC_DEFAULT_APM_PLATE_TOP, ConsoleKind.CLASSIC.getApmPlateTop(ConsoleType.TERRAN));
        Assertions.assertEquals(CLASSIC_PROTOSS_APM_PLATE_TOP, ConsoleKind.CLASSIC.getApmPlateTop(ConsoleType.PROTOSS));
        Assertions.assertEquals(CLASSIC_OBSERVER_APM_PLATE_TOP, ConsoleKind.CLASSIC.getApmPlateTop(ConsoleType.OBSERVER));

        for (ConsoleType type : ConsoleType.values()) {
            Assertions.assertEquals(CLASSIC_TIME_PLATE_LEFT, ConsoleKind.CLASSIC.getTimePlateLeft(type));
            Assertions.assertEquals(CLASSIC_APM_PLATE_LEFT, ConsoleKind.CLASSIC.getApmPlateLeft(type));
        }
    }

    @Test
    void remastered_plates_place_each_race_and_take_the_default_entry_for_the_observer() {
        Assertions.assertEquals(REMASTERED_ZERG_TIME_PLATE_LEFT, ConsoleKind.REMASTERED.getTimePlateLeft(ConsoleType.ZERG));
        Assertions.assertEquals(REMASTERED_ZERG_TIME_PLATE_TOP, ConsoleKind.REMASTERED.getTimePlateTop(ConsoleType.ZERG));
        Assertions.assertEquals(REMASTERED_DEFAULT_TIME_PLATE_LEFT, ConsoleKind.REMASTERED.getTimePlateLeft(ConsoleType.TERRAN));
        Assertions.assertEquals(REMASTERED_DEFAULT_TIME_PLATE_TOP, ConsoleKind.REMASTERED.getTimePlateTop(ConsoleType.TERRAN));
        Assertions.assertEquals(REMASTERED_PROTOSS_TIME_PLATE_LEFT, ConsoleKind.REMASTERED.getTimePlateLeft(ConsoleType.PROTOSS));
        Assertions.assertEquals(REMASTERED_PROTOSS_TIME_PLATE_TOP, ConsoleKind.REMASTERED.getTimePlateTop(ConsoleType.PROTOSS));
        Assertions.assertEquals(REMASTERED_DEFAULT_TIME_PLATE_LEFT, ConsoleKind.REMASTERED.getTimePlateLeft(ConsoleType.OBSERVER));
        Assertions.assertEquals(REMASTERED_DEFAULT_TIME_PLATE_TOP, ConsoleKind.REMASTERED.getTimePlateTop(ConsoleType.OBSERVER));

        Assertions.assertEquals(REMASTERED_ZERG_APM_PLATE_LEFT, ConsoleKind.REMASTERED.getApmPlateLeft(ConsoleType.ZERG));
        Assertions.assertEquals(REMASTERED_ZERG_APM_PLATE_TOP, ConsoleKind.REMASTERED.getApmPlateTop(ConsoleType.ZERG));
        Assertions.assertEquals(REMASTERED_DEFAULT_APM_PLATE_LEFT, ConsoleKind.REMASTERED.getApmPlateLeft(ConsoleType.TERRAN));
        Assertions.assertEquals(REMASTERED_DEFAULT_APM_PLATE_TOP, ConsoleKind.REMASTERED.getApmPlateTop(ConsoleType.TERRAN));
        Assertions.assertEquals(REMASTERED_PROTOSS_APM_PLATE_LEFT, ConsoleKind.REMASTERED.getApmPlateLeft(ConsoleType.PROTOSS));
        Assertions.assertEquals(REMASTERED_PROTOSS_APM_PLATE_TOP, ConsoleKind.REMASTERED.getApmPlateTop(ConsoleType.PROTOSS));
        Assertions.assertEquals(REMASTERED_DEFAULT_APM_PLATE_LEFT, ConsoleKind.REMASTERED.getApmPlateLeft(ConsoleType.OBSERVER));
        Assertions.assertEquals(REMASTERED_DEFAULT_APM_PLATE_TOP, ConsoleKind.REMASTERED.getApmPlateTop(ConsoleType.OBSERVER));
    }

    @Test
    void apm_pens_differ_between_the_console_classes() {
        Assertions.assertEquals(CLASSIC_APM_PEN_X, ConsoleKind.CLASSIC.getApmPenX());
        Assertions.assertEquals(CLASSIC_APM_PEN_Y, ConsoleKind.CLASSIC.getApmPenY());
        Assertions.assertEquals(REMASTERED_APM_PEN_X, ConsoleKind.REMASTERED.getApmPenX());
        Assertions.assertEquals(REMASTERED_APM_PEN_Y, ConsoleKind.REMASTERED.getApmPenY());
    }

    @Test
    void apm_text_right_mirrors_the_pen_inside_the_interface_width() {
        Assertions.assertEquals(CLASSIC_APM_TEXT_RIGHT, ConsoleKind.CLASSIC.calculateApmTextRight(ConsoleLayout.INTERFACE_WIDTH));
        Assertions.assertEquals(REMASTERED_APM_TEXT_RIGHT, ConsoleKind.REMASTERED.calculateApmTextRight(ConsoleLayout.INTERFACE_WIDTH));
        Assertions.assertEquals(WIDE_CLASSIC_APM_TEXT_RIGHT, ConsoleKind.CLASSIC.calculateApmTextRight(WIDE_INTERFACE_WIDTH));
        Assertions.assertEquals(WIDE_REMASTERED_APM_TEXT_RIGHT, ConsoleKind.REMASTERED.calculateApmTextRight(WIDE_INTERFACE_WIDTH));
    }

    @RequiresInstallation
    @Test
    void apm_text_ends_on_the_apm_plate_of_every_race_and_art_set() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            InterfaceAssets assets = InterfaceAssets.load(ScrResourceProvider.borrow(storage), 0);

            for (AssetResolver.AssetSet assetSet : AssetResolver.AssetSet.values()) {
                ConsoleKind kind = ConsoleKind.of(assetSet);

                assets.setAssetSet(assetSet);

                for (int race = 0; race < RACE_FOLDERS.length; race++) {
                    ConsoleType type = ConsoleType.of(race, false);
                    InterfaceAssets.ScaledImage plate = assets.decodeTexture("game/consoles/" + RACE_FOLDERS[race] + "/" + APM_PLATE);
                    int plateLeft = kind.getApmPlateLeft(type);
                    int plateRight = plateLeft + plate.getImage().getWidth() / plate.getScale();
                    int textRight = kind.calculateApmTextRight(ConsoleLayout.INTERFACE_WIDTH);
                    String message = assetSet + " " + RACE_FOLDERS[race] + ": plate " + plateLeft + ".." + plateRight + ", text right " + textRight;

                    Assertions.assertTrue(textRight > plateLeft && textRight <= plateRight, message);
                }
            }
        }
    }

    @RequiresInstallation
    @Test
    void time_pen_lies_on_the_time_plate_window_of_every_race_and_art_set() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            InterfaceAssets assets = InterfaceAssets.load(ScrResourceProvider.borrow(storage), 0);

            for (AssetResolver.AssetSet assetSet : AssetResolver.AssetSet.values()) {
                ConsoleKind kind = ConsoleKind.of(assetSet);

                assets.setAssetSet(assetSet);

                for (int race = 0; race < RACE_FOLDERS.length; race++) {
                    ConsoleType type = ConsoleType.of(race, false);
                    InterfaceAssets.ScaledImage plate = assets.decodeTexture("game/consoles/" + RACE_FOLDERS[race] + "/" + TIME_PLATE);
                    double windowCenter = kind.getTimePlateLeft(type) + measureWindowCenter(plate.getImage(), plate.getScale());

                    Assertions.assertEquals(windowCenter, kind.getTimePenX(), WINDOW_CENTER_TOLERANCE, assetSet + " " + RACE_FOLDERS[race]);
                }
            }
        }
    }
}
