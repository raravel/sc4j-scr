package org.fogofwarlabs.sc4j.scr.game.extension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks the console placement: dialogs and their controls keep their place until moved or hidden, a restored dialog returns to its place, the
 * classic screen of a dialog stands at its anchor's interface edge, the console is shown everywhere on the screen until a mask pixel is set, and
 * the change count follows the moves, the hidden and restored dialogs and controls, the anchors, the minimap size and the mask changes.
 */
final class ScrConsolePlacementTest {
    private static final int WIDTH = 4;
    private static final int HEIGHT = 3;
    private static final int LEFT = 2;
    private static final int TOP = 1;
    private static final int CONTROL = 3;
    private static final int OTHER_CONTROL = -10;
    /**
     * The interface width of a 16:9 screen.
     */
    private static final int WIDE_INTERFACE = 853;
    private static final int LARGE_MINIMAP = 192;

    @Test
    void dialogs_move_and_hide_and_count_their_changes() {
        ScrConsolePlacement placement = new ScrConsolePlacement(WIDTH, HEIGHT);

        Assertions.assertFalse(placement.isDialogMoved(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertThrows(IllegalStateException.class, () -> placement.getDialogLeft(ScrConsolePlacement.Dialog.MENU));

        placement.moveDialog(ScrConsolePlacement.Dialog.MENU, LEFT, TOP);
        placement.moveDialog(ScrConsolePlacement.Dialog.MENU, LEFT, TOP);

        Assertions.assertTrue(placement.isDialogMoved(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertEquals(LEFT, placement.getDialogLeft(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertEquals(TOP, placement.getDialogTop(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertEquals(1, placement.getChangeCount());

        placement.hideDialog(ScrConsolePlacement.Dialog.MENU);
        placement.hideDialog(ScrConsolePlacement.Dialog.MENU);

        Assertions.assertTrue(placement.isDialogHidden(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertEquals(2, placement.getChangeCount());

        placement.moveDialog(ScrConsolePlacement.Dialog.MENU, LEFT, TOP);

        Assertions.assertFalse(placement.isDialogHidden(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertFalse(placement.isDialogMoved(ScrConsolePlacement.Dialog.COMMAND_CARD));
        Assertions.assertEquals(3, placement.getChangeCount());

        placement.restoreDialog(ScrConsolePlacement.Dialog.MENU);
        placement.restoreDialog(ScrConsolePlacement.Dialog.MENU);
        placement.restoreDialog(ScrConsolePlacement.Dialog.COMMAND_CARD);

        Assertions.assertFalse(placement.isDialogMoved(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertFalse(placement.isDialogHidden(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertEquals(4, placement.getChangeCount());
    }

    @Test
    void anchors_place_the_classic_screen_at_an_interface_edge() {
        Assertions.assertEquals(0, ScrConsolePlacement.Anchor.LEFT.computeClassicLeft(WIDE_INTERFACE));
        Assertions.assertEquals(106, ScrConsolePlacement.Anchor.CENTER.computeClassicLeft(WIDE_INTERFACE));
        Assertions.assertEquals(213, ScrConsolePlacement.Anchor.RIGHT.computeClassicLeft(WIDE_INTERFACE));

        for (ScrConsolePlacement.Anchor anchor : ScrConsolePlacement.Anchor.values()) {
            Assertions.assertEquals(0, anchor.computeClassicLeft(ScrConsolePlacement.CLASSIC_WIDTH), anchor::toString);
        }
    }

    @Test
    void dialog_anchors_and_the_minimap_size_count_their_changes() {
        ScrConsolePlacement placement = new ScrConsolePlacement(WIDTH, HEIGHT);

        Assertions.assertEquals(ScrConsolePlacement.Anchor.CENTER, placement.getDialogAnchor(ScrConsolePlacement.Dialog.MINIMAP));
        Assertions.assertEquals(ScrConsolePlacement.MINIMAP_SIZE, placement.getMinimapSize());

        placement.setDialogAnchor(ScrConsolePlacement.Dialog.MINIMAP, ScrConsolePlacement.Anchor.RIGHT);
        placement.setDialogAnchor(ScrConsolePlacement.Dialog.MINIMAP, ScrConsolePlacement.Anchor.RIGHT);
        placement.setMinimapSize(LARGE_MINIMAP);
        placement.setMinimapSize(LARGE_MINIMAP);

        Assertions.assertEquals(ScrConsolePlacement.Anchor.RIGHT, placement.getDialogAnchor(ScrConsolePlacement.Dialog.MINIMAP));
        Assertions.assertEquals(ScrConsolePlacement.Anchor.CENTER, placement.getDialogAnchor(ScrConsolePlacement.Dialog.MENU));
        Assertions.assertEquals(LARGE_MINIMAP, placement.getMinimapSize());
        Assertions.assertEquals(2, placement.getChangeCount());
        Assertions.assertThrows(IllegalArgumentException.class, () -> placement.setMinimapSize(0));
    }

    @Test
    void controls_move_and_hide_in_their_dialog_and_count_their_changes() {
        ScrConsolePlacement placement = new ScrConsolePlacement(WIDTH, HEIGHT);

        Assertions.assertEquals(0, placement.getPlacedControls(ScrConsolePlacement.Dialog.COMMAND_CARD).length);
        Assertions.assertThrows(IllegalStateException.class, () -> placement.getControlLeft(ScrConsolePlacement.Dialog.COMMAND_CARD, CONTROL));

        placement.moveControl(ScrConsolePlacement.Dialog.COMMAND_CARD, CONTROL, LEFT, TOP);
        placement.moveControl(ScrConsolePlacement.Dialog.COMMAND_CARD, CONTROL, LEFT, TOP);
        placement.hideControl(ScrConsolePlacement.Dialog.TEXT_BOX, OTHER_CONTROL);
        placement.hideControl(ScrConsolePlacement.Dialog.TEXT_BOX, OTHER_CONTROL);

        Assertions.assertTrue(placement.isControlMoved(ScrConsolePlacement.Dialog.COMMAND_CARD, CONTROL));
        Assertions.assertFalse(placement.isControlHidden(ScrConsolePlacement.Dialog.COMMAND_CARD, CONTROL));
        Assertions.assertEquals(LEFT, placement.getControlLeft(ScrConsolePlacement.Dialog.COMMAND_CARD, CONTROL));
        Assertions.assertEquals(TOP, placement.getControlTop(ScrConsolePlacement.Dialog.COMMAND_CARD, CONTROL));
        Assertions.assertArrayEquals(new int[]{CONTROL}, placement.getPlacedControls(ScrConsolePlacement.Dialog.COMMAND_CARD));
        Assertions.assertTrue(placement.isControlHidden(ScrConsolePlacement.Dialog.TEXT_BOX, OTHER_CONTROL));
        Assertions.assertFalse(placement.isControlMoved(ScrConsolePlacement.Dialog.TEXT_BOX, OTHER_CONTROL));
        Assertions.assertFalse(placement.isControlMoved(ScrConsolePlacement.Dialog.COMMAND_CARD, OTHER_CONTROL));
        Assertions.assertFalse(placement.isDialogMoved(ScrConsolePlacement.Dialog.COMMAND_CARD));
        Assertions.assertEquals(2, placement.getChangeCount());

        placement.moveControl(ScrConsolePlacement.Dialog.TEXT_BOX, OTHER_CONTROL, LEFT, TOP);

        Assertions.assertFalse(placement.isControlHidden(ScrConsolePlacement.Dialog.TEXT_BOX, OTHER_CONTROL));
        Assertions.assertEquals(3, placement.getChangeCount());

        placement.restoreControl(ScrConsolePlacement.Dialog.TEXT_BOX, OTHER_CONTROL);
        placement.restoreControl(ScrConsolePlacement.Dialog.TEXT_BOX, OTHER_CONTROL);
        placement.restoreControl(ScrConsolePlacement.Dialog.MENU, CONTROL);

        Assertions.assertFalse(placement.isControlMoved(ScrConsolePlacement.Dialog.TEXT_BOX, OTHER_CONTROL));
        Assertions.assertEquals(0, placement.getPlacedControls(ScrConsolePlacement.Dialog.TEXT_BOX).length);
        Assertions.assertArrayEquals(new int[]{CONTROL}, placement.getPlacedControls(ScrConsolePlacement.Dialog.COMMAND_CARD));
        Assertions.assertEquals(4, placement.getChangeCount());
    }

    @Test
    void the_mask_shows_the_console_only_where_it_is_set() {
        ScrConsolePlacement placement = new ScrConsolePlacement(WIDTH, HEIGHT);

        Assertions.assertFalse(placement.isMasked());
        Assertions.assertTrue(placement.isShown(LEFT, TOP));
        Assertions.assertFalse(placement.isShown(WIDTH, TOP));

        placement.setMaskPixel(TOP * WIDTH + LEFT, true);
        placement.setMaskPixel(TOP * WIDTH + LEFT, true);

        Assertions.assertTrue(placement.isMasked());
        Assertions.assertTrue(placement.isShown(LEFT, TOP));
        Assertions.assertFalse(placement.isShown(LEFT - 1, TOP));
        Assertions.assertEquals(1, placement.getChangeCount());
        Assertions.assertEquals(WIDTH, placement.getSurfaces().getWidth());
    }
}
