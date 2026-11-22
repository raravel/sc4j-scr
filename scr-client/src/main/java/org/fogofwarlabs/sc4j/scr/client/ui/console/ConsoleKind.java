package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.scr.data.format.casc.AssetResolver;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Selects the console class of the interface art: the classic console of the SD art, or the remastered console of the HD and HD2 art. Each class
 * places the game time pen, the APM pen and the time and APM plates of every console type in the {@code 640x480} console space.
 * <p>
 * A plate map holds a default entry and the entries of the types the class defines; a type without its own entry takes the default entry.
 */
enum ConsoleKind {
    /**
     * The classic console of the SD art.
     */
    CLASSIC(ConsoleKind.CLASSIC_TIME_PEN_X, ConsoleKind.CLASSIC_TIME_PEN_Y,
            ConsoleKind.CLASSIC_APM_PEN_X, ConsoleKind.CLASSIC_APM_PEN_Y,
            new PlateMap(ConsoleKind.CLASSIC_TIME_PLATE_LEFT, ConsoleKind.CLASSIC_DEFAULT_TIME_PLATE_TOP)
                    .put(ConsoleType.ZERG, ConsoleKind.CLASSIC_TIME_PLATE_LEFT, ConsoleKind.CLASSIC_ZERG_TIME_PLATE_TOP)
                    .put(ConsoleType.PROTOSS, ConsoleKind.CLASSIC_TIME_PLATE_LEFT, ConsoleKind.CLASSIC_LOW_TIME_PLATE_TOP)
                    .put(ConsoleType.OBSERVER, ConsoleKind.CLASSIC_TIME_PLATE_LEFT, ConsoleKind.CLASSIC_LOW_TIME_PLATE_TOP),
            new PlateMap(ConsoleKind.CLASSIC_APM_PLATE_LEFT, ConsoleKind.CLASSIC_DEFAULT_APM_PLATE_TOP)
                    .put(ConsoleType.PROTOSS, ConsoleKind.CLASSIC_APM_PLATE_LEFT, ConsoleKind.CLASSIC_PROTOSS_APM_PLATE_TOP)
                    .put(ConsoleType.OBSERVER, ConsoleKind.CLASSIC_APM_PLATE_LEFT, ConsoleKind.CLASSIC_OBSERVER_APM_PLATE_TOP)),
    /**
     * The remastered console of the HD and HD2 art; it defines no observer entry.
     */
    REMASTERED(ConsoleKind.REMASTERED_TIME_PEN_X, ConsoleKind.REMASTERED_TIME_PEN_Y,
               ConsoleKind.REMASTERED_APM_PEN_X, ConsoleKind.REMASTERED_APM_PEN_Y,
               new PlateMap(ConsoleKind.REMASTERED_DEFAULT_TIME_PLATE_LEFT, ConsoleKind.REMASTERED_DEFAULT_TIME_PLATE_TOP)
                       .put(ConsoleType.ZERG, ConsoleKind.REMASTERED_ZERG_TIME_PLATE_LEFT, ConsoleKind.REMASTERED_ZERG_TIME_PLATE_TOP)
                       .put(ConsoleType.PROTOSS, ConsoleKind.REMASTERED_PROTOSS_TIME_PLATE_LEFT, ConsoleKind.REMASTERED_PROTOSS_TIME_PLATE_TOP),
               new PlateMap(ConsoleKind.REMASTERED_DEFAULT_APM_PLATE_LEFT, ConsoleKind.REMASTERED_DEFAULT_APM_PLATE_TOP)
                       .put(ConsoleType.ZERG, ConsoleKind.REMASTERED_ZERG_APM_PLATE_LEFT, ConsoleKind.REMASTERED_ZERG_APM_PLATE_TOP)
                       .put(ConsoleType.PROTOSS, ConsoleKind.REMASTERED_PROTOSS_APM_PLATE_LEFT, ConsoleKind.REMASTERED_PROTOSS_APM_PLATE_TOP));

    /**
     * The classic pen x the game time text box is centered on.
     */
    private static final int CLASSIC_TIME_PEN_X = 358;
    /**
     * The classic top of the game time text.
     */
    private static final int CLASSIC_TIME_PEN_Y = 365;
    /**
     * The classic pen x of the APM number.
     */
    private static final int CLASSIC_APM_PEN_X = 398;
    /**
     * The classic top of the APM number.
     */
    private static final int CLASSIC_APM_PEN_Y = 365;
    /**
     * The remastered pen x the game time text box is centered on.
     */
    private static final int REMASTERED_TIME_PEN_X = 351;
    /**
     * The remastered top of the game time text.
     */
    private static final int REMASTERED_TIME_PEN_Y = 363;
    /**
     * The remastered pen x of the APM number.
     */
    private static final int REMASTERED_APM_PEN_X = 397;
    /**
     * The remastered top of the APM number.
     */
    private static final int REMASTERED_APM_PEN_Y = 363;

    /**
     * The left of every classic time plate.
     */
    private static final int CLASSIC_TIME_PLATE_LEFT = 288;
    /**
     * The top of the classic default time plate entry (the Terran console).
     */
    private static final int CLASSIC_DEFAULT_TIME_PLATE_TOP = 350;
    /**
     * The top of the classic Zerg time plate.
     */
    private static final int CLASSIC_ZERG_TIME_PLATE_TOP = 345;
    /**
     * The top of the classic Protoss and observer time plates.
     */
    private static final int CLASSIC_LOW_TIME_PLATE_TOP = 352;
    /**
     * The left of every classic APM plate.
     */
    private static final int CLASSIC_APM_PLATE_LEFT = 140;
    /**
     * The top of the classic default APM plate entry (the Zerg and Terran consoles).
     */
    private static final int CLASSIC_DEFAULT_APM_PLATE_TOP = 316;
    /**
     * The top of the classic Protoss APM plate.
     */
    private static final int CLASSIC_PROTOSS_APM_PLATE_TOP = 310;
    /**
     * The top of the classic observer APM plate (an observer never shows the APM).
     */
    private static final int CLASSIC_OBSERVER_APM_PLATE_TOP = 326;

    /**
     * The left of the remastered default time plate entry (the Terran and observer consoles).
     */
    private static final int REMASTERED_DEFAULT_TIME_PLATE_LEFT = 301;
    /**
     * The top of the remastered default time plate entry.
     */
    private static final int REMASTERED_DEFAULT_TIME_PLATE_TOP = 354;
    /**
     * The left of the remastered Zerg time plate.
     */
    private static final int REMASTERED_ZERG_TIME_PLATE_LEFT = 298;
    /**
     * The top of the remastered Zerg time plate.
     */
    private static final int REMASTERED_ZERG_TIME_PLATE_TOP = 346;
    /**
     * The left of the remastered Protoss time plate.
     */
    private static final int REMASTERED_PROTOSS_TIME_PLATE_LEFT = 310;
    /**
     * The top of the remastered Protoss time plate.
     */
    private static final int REMASTERED_PROTOSS_TIME_PLATE_TOP = 352;
    /**
     * The left of the remastered default APM plate entry (the Terran and observer consoles).
     */
    private static final int REMASTERED_DEFAULT_APM_PLATE_LEFT = 201;
    /**
     * The top of the remastered default APM plate entry.
     */
    private static final int REMASTERED_DEFAULT_APM_PLATE_TOP = 354;
    /**
     * The left of the remastered Zerg APM plate.
     */
    private static final int REMASTERED_ZERG_APM_PLATE_LEFT = 162;
    /**
     * The top of the remastered Zerg APM plate.
     */
    private static final int REMASTERED_ZERG_APM_PLATE_TOP = 346;
    /**
     * The left of the remastered Protoss APM plate.
     */
    private static final int REMASTERED_PROTOSS_APM_PLATE_LEFT = 210;
    /**
     * The top of the remastered Protoss APM plate.
     */
    private static final int REMASTERED_PROTOSS_APM_PLATE_TOP = 352;

    /**
     * Keeps the plate position of every console type: the default entry, replaced by the entries a console class defines.
     */
    private static final class PlateMap {
        private final int @NotNull [] lefts;
        private final int @NotNull [] tops;

        /**
         * Creates a {@link PlateMap} instance that places every console type at the default entry.
         *
         * @param defaultLeft the left of the default entry
         * @param defaultTop  the top of the default entry
         */
        private PlateMap(int defaultLeft, int defaultTop) {
            this.lefts = new int[ConsoleType.values().length];
            this.tops  = new int[ConsoleType.values().length];

            Arrays.fill(this.lefts, defaultLeft);
            Arrays.fill(this.tops, defaultTop);
        }

        /**
         * @param type the console type
         * @return the plate left of that type
         */
        private int getLeft(@NotNull ConsoleType type) {
            return this.lefts[type.ordinal()];
        }

        /**
         * @param type the console type
         * @return the plate top of that type
         */
        private int getTop(@NotNull ConsoleType type) {
            return this.tops[type.ordinal()];
        }

        /**
         * Puts the entry of a console type.
         *
         * @param type the console type
         * @param left the plate left of that type
         * @param top  the plate top of that type
         * @return this instance for chaining
         */
        private @NotNull PlateMap put(@NotNull ConsoleType type, int left, int top) {
            this.lefts[type.ordinal()] = left;
            this.tops[type.ordinal()]  = top;

            return this;
        }
    }

    /**
     * Selects the console class of an interface art set.
     *
     * @param assetSet the interface art set
     * @return {@link #REMASTERED} for the HD and HD2 art, {@link #CLASSIC} otherwise
     */
    static @NotNull ConsoleKind of(AssetResolver.@NotNull AssetSet assetSet) {
        return assetSet.isHd() ? REMASTERED : CLASSIC;
    }

    private final int timePenX;
    private final int timePenY;
    private final int apmPenX;
    private final int apmPenY;

    private final @NotNull PlateMap timePlates;
    private final @NotNull PlateMap apmPlates;

    ConsoleKind(int timePenX, int timePenY, int apmPenX, int apmPenY, @NotNull PlateMap timePlates, @NotNull PlateMap apmPlates) {
        this.timePenX = timePenX;
        this.timePenY = timePenY;
        this.apmPenX  = apmPenX;
        this.apmPenY  = apmPenY;

        this.timePlates = timePlates;
        this.apmPlates  = apmPlates;
    }

    /**
     * @return the pen x the game time text box is centered on, before the extra interface width moves it
     */
    int getTimePenX() {
        return this.timePenX;
    }

    /**
     * @return the top of the game time text
     */
    int getTimePenY() {
        return this.timePenY;
    }

    /**
     * @return the pen x of the APM number, before the extra interface width moves it
     */
    int getApmPenX() {
        return this.apmPenX;
    }

    /**
     * @return the top of the APM number
     */
    int getApmPenY() {
        return this.apmPenY;
    }

    /**
     * Calculates where the right-aligned APM number ends. The number is drawn in a text box as wide as the interface, and right alignment ends the
     * text as far left of the box's right edge as the pen, moved right by half the extra interface width, lies right of the box's left edge.
     *
     * @param interfaceWidth the interface width in interface pixels
     * @return the x the APM number ends at
     */
    int calculateApmTextRight(int interfaceWidth) {
        int penX = this.apmPenX + (interfaceWidth - ConsoleLayout.INTERFACE_WIDTH) / 2;

        return interfaceWidth - 1 - penX;
    }

    /**
     * @param type the console type
     * @return the left of the game time plate {@code console_TIME.DDS} of that type, before the extra interface width moves it
     */
    int getTimePlateLeft(@NotNull ConsoleType type) {
        return this.timePlates.getLeft(type);
    }

    /**
     * @param type the console type
     * @return the top of the game time plate {@code console_TIME.DDS} of that type
     */
    int getTimePlateTop(@NotNull ConsoleType type) {
        return this.timePlates.getTop(type);
    }

    /**
     * @param type the console type
     * @return the left of the APM plate {@code console_APM.DDS} of that type, before the extra interface width moves it
     */
    int getApmPlateLeft(@NotNull ConsoleType type) {
        return this.apmPlates.getLeft(type);
    }

    /**
     * @param type the console type
     * @return the top of the APM plate {@code console_APM.DDS} of that type
     */
    int getApmPlateTop(@NotNull ConsoleType type) {
        return this.apmPlates.getTop(type);
    }
}
