package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrDialog;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsolePlacement;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * Checks a placed dialog layout: the dialog moves with the controls that keep their place in it, a control placed on its own lies at its place,
 * and the dialog's runtime state built from the placed definition hits the controls where the layout draws them. Checks the minimap dialog grown
 * with its area: at its installed place it keeps its bottom-left corner, and placed at a corner by its grown size it keeps the area on screen.
 */
final class DialogLayoutTest {
    private static final int FIRST = 1;
    private static final int SECOND = 2;
    private static final int DIALOG_LEFT = 100;
    private static final int DIALOG_TOP = 200;
    private static final int DIALOG_SIZE = 80;
    private static final int CONTROL_SIZE = 20;
    private static final int FIRST_LEFT = 5;
    private static final int SECOND_LEFT = 40;
    private static final int CONTROL_TOP = 10;
    private static final int MOVED_LEFT = 300;
    private static final int MOVED_TOP = 50;
    private static final int PLACED_FIRST_LEFT = 20;
    private static final int PLACED_FIRST_TOP = 30;
    /**
     * The installed minimap dialog: its rectangle, the area control {@code 1} and the terrain button {@code 2}, in interface units.
     */
    private static final int MINIMAP_TOP = 315;
    private static final int MINIMAP_WIDTH = 138;
    private static final int MINIMAP_HEIGHT = 165;
    private static final int AREA_LEFT = 6;
    private static final int AREA_TOP = 33;
    private static final int TERRAIN_BUTTON = 2;
    private static final int TERRAIN_LEFT = 3;
    private static final int TERRAIN_TOP = 5;
    private static final int TERRAIN_WIDTH = 64;
    private static final int TERRAIN_HEIGHT = 24;
    private static final int LARGE_MINIMAP = 148;
    private static final int GROWTH = LARGE_MINIMAP - ScrConsolePlacement.MINIMAP_SIZE;
    private static final int SCREEN_WIDTH = 640;
    private static final int SCREEN_HEIGHT = 480;
    /**
     * A 16:9 interface width: the classic screen at the right edge starts at column {@code 214}.
     */
    private static final float WIDE_INTERFACE = 854;

    private static @NotNull DialogControl createButton(int identifier, int left) {
        return ScrDialogControlUtils.createControl(ScrControlType.PUSHBUTTON, identifier, left, CONTROL_TOP, CONTROL_SIZE, CONTROL_SIZE,
                                                   Set.of("WS_VISIBLE"), "", List.of());
    }

    private static @NotNull DialogLayout createLayout() {
        return DialogLayout.of(new DialogDefinition("test", DIALOG_LEFT, DIALOG_TOP, DIALOG_SIZE, DIALOG_SIZE,
                                                    List.of(createButton(FIRST, FIRST_LEFT), createButton(SECOND, SECOND_LEFT))));
    }

    private static @NotNull DialogLayout createMinimap() {
        DialogControl area = ScrDialogControlUtils.createControl(ScrControlType.RECT, ScrConsolePlacement.MINIMAP_AREA_CONTROL, AREA_LEFT, AREA_TOP,
                                                                 ScrConsolePlacement.MINIMAP_SIZE, ScrConsolePlacement.MINIMAP_SIZE, Set.of(), "",
                                                                 List.of());
        DialogControl terrain = ScrDialogControlUtils.createControl(ScrControlType.PUSHBUTTON, TERRAIN_BUTTON, TERRAIN_LEFT, TERRAIN_TOP,
                                                                    TERRAIN_WIDTH, TERRAIN_HEIGHT, Set.of("WS_VISIBLE"), "", List.of());

        return DialogLayout.of(new DialogDefinition("minimap", 0, MINIMAP_TOP, MINIMAP_WIDTH, MINIMAP_HEIGHT, List.of(area, terrain)));
    }

    @Test
    void a_placed_control_lies_at_its_place_and_the_others_move_with_the_dialog() {
        DialogLayout placed = createLayout().createPlacedLayout(MOVED_LEFT, MOVED_TOP,
                                                                identifier -> identifier == FIRST ? new int[]{PLACED_FIRST_LEFT, PLACED_FIRST_TOP}
                                                                                                  : null);

        Assertions.assertEquals(MOVED_LEFT, placed.getX());
        Assertions.assertEquals(MOVED_TOP, placed.getY());
        Assertions.assertEquals(DIALOG_SIZE, placed.getWidth());
        Assertions.assertEquals(PLACED_FIRST_LEFT, placed.getControl(FIRST).getX());
        Assertions.assertEquals(PLACED_FIRST_TOP, placed.getControl(FIRST).getY());
        Assertions.assertEquals(MOVED_LEFT + SECOND_LEFT, placed.getControl(SECOND).getX());
        Assertions.assertEquals(MOVED_TOP + CONTROL_TOP, placed.getControl(SECOND).getY());
        Assertions.assertEquals(CONTROL_SIZE, placed.getControl(FIRST).getWidth());
    }

    @Test
    void the_runtime_state_of_a_placed_dialog_hits_the_controls_at_their_places() {
        DialogLayout placed = createLayout().createPlacedLayout(MOVED_LEFT, MOVED_TOP,
                                                                identifier -> identifier == FIRST ? new int[]{PLACED_FIRST_LEFT, PLACED_FIRST_TOP}
                                                                                                  : null);
        ScrDialog dialog = new ScrDialog(placed.getDefinition());
        ScrControl first = dialog.findControlAt(PLACED_FIRST_LEFT + 1 - placed.getX(), PLACED_FIRST_TOP + 1 - placed.getY());
        ScrControl second = dialog.findControlAt(MOVED_LEFT + SECOND_LEFT + 1 - placed.getX(), MOVED_TOP + CONTROL_TOP + 1 - placed.getY());

        Assertions.assertNotNull(first);
        Assertions.assertEquals(FIRST, first.getDefinition().getId());
        Assertions.assertNotNull(second);
        Assertions.assertEquals(SECOND, second.getDefinition().getId());
        Assertions.assertNull(dialog.findControlAt(FIRST_LEFT + 1, CONTROL_TOP + 1), "the first control left its place in the dialog");
    }

    @Test
    void a_resized_control_grows_its_dialog_from_the_bottom_left_corner() {
        DialogLayout resized = createMinimap().createResizedLayout(ScrConsolePlacement.MINIMAP_AREA_CONTROL, LARGE_MINIMAP, LARGE_MINIMAP);
        DialogLayout.Control area = resized.getControl(ScrConsolePlacement.MINIMAP_AREA_CONTROL);

        Assertions.assertEquals(0, resized.getX());
        Assertions.assertEquals(MINIMAP_TOP - GROWTH, resized.getY());
        Assertions.assertEquals(MINIMAP_WIDTH + GROWTH, resized.getWidth());
        Assertions.assertEquals(MINIMAP_HEIGHT + GROWTH, resized.getHeight());
        Assertions.assertEquals(MINIMAP_TOP + MINIMAP_HEIGHT, resized.getY() + resized.getHeight());
        Assertions.assertEquals(AREA_LEFT, area.getX());
        Assertions.assertEquals(MINIMAP_TOP - GROWTH + AREA_TOP, area.getY());
        Assertions.assertEquals(LARGE_MINIMAP, area.getWidth());
        Assertions.assertEquals(LARGE_MINIMAP, area.getHeight());
        Assertions.assertEquals(TERRAIN_LEFT, resized.getControl(TERRAIN_BUTTON).getX());
        Assertions.assertEquals(resized.getY() + TERRAIN_TOP, resized.getControl(TERRAIN_BUTTON).getY());
        Assertions.assertEquals(TERRAIN_WIDTH, resized.getControl(TERRAIN_BUTTON).getWidth());
    }

    @Test
    void the_minimap_dialog_grows_with_the_minimap_size_at_its_installed_place() {
        ScrConsolePlacement placement = new ScrConsolePlacement(SCREEN_WIDTH, SCREEN_HEIGHT);

        placement.setMinimapSize(LARGE_MINIMAP);

        DialogLayout placed = ConsoleView.placeDialog(createMinimap(), ScrConsolePlacement.Dialog.MINIMAP, placement, SCREEN_WIDTH);
        DialogLayout.Control area = placed.getControl(ScrConsolePlacement.MINIMAP_AREA_CONTROL);
        DialogLayout.Control installedArea = createMinimap().getControl(ScrConsolePlacement.MINIMAP_AREA_CONTROL);

        Assertions.assertEquals(MINIMAP_WIDTH + GROWTH, placed.getWidth());
        Assertions.assertEquals(MINIMAP_HEIGHT + GROWTH, placed.getHeight());
        Assertions.assertEquals(installedArea.getX(), area.getX());
        Assertions.assertEquals(installedArea.getY() + installedArea.getHeight(), area.getY() + area.getHeight());
        Assertions.assertEquals(LARGE_MINIMAP, area.getWidth());
    }

    @Test
    void a_minimap_dialog_placed_at_a_corner_by_its_grown_size_keeps_the_area_on_screen() {
        ScrConsolePlacement placement = new ScrConsolePlacement(SCREEN_WIDTH, SCREEN_HEIGHT);

        placement.setMinimapSize(LARGE_MINIMAP);
        placement.setDialogAnchor(ScrConsolePlacement.Dialog.MINIMAP, ScrConsolePlacement.Anchor.RIGHT);
        placement.moveDialog(ScrConsolePlacement.Dialog.MINIMAP, SCREEN_WIDTH - (MINIMAP_WIDTH + GROWTH), SCREEN_HEIGHT - (MINIMAP_HEIGHT + GROWTH));

        DialogLayout placed = ConsoleView.placeDialog(createMinimap(), ScrConsolePlacement.Dialog.MINIMAP, placement, WIDE_INTERFACE);
        DialogLayout.Control area = placed.getControl(ScrConsolePlacement.MINIMAP_AREA_CONTROL);

        Assertions.assertEquals(WIDE_INTERFACE, placed.getX() + placed.getWidth());
        Assertions.assertEquals(SCREEN_HEIGHT, placed.getY() + placed.getHeight());
        Assertions.assertEquals(placed.getX() + AREA_LEFT, area.getX());
        Assertions.assertEquals(placed.getY() + AREA_TOP, area.getY());
        Assertions.assertTrue(area.getX() + area.getWidth() <= WIDE_INTERFACE);
        Assertions.assertTrue(area.getY() + area.getHeight() <= SCREEN_HEIGHT);
    }
}
