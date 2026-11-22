package org.fogofwarlabs.sc4j.scr.client.world;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks the cloak draw functions of seen and unseen cloaked images of {@link WorldSceneExtraction}.
 */
final class WorldSceneExtractionTest {
    /**
     * The cloak views of a sprite: none, seen through detection and unseen.
     */
    private static final int CLOAK_VIEW_NONE = 0;
    private static final int CLOAK_VIEW_VISIBLE = 1;
    private static final int CLOAK_VIEW_HIDDEN = 2;

    @Test
    void get_view_modifier_should_use_visible_draw_functions_for_seen_cloaked_images() {
        // Modifiers 2..4 become 5..7 and normal images become 6.
        Assertions.assertEquals(5, WorldSceneExtraction.getViewModifier(2, CLOAK_VIEW_VISIBLE, false));
        Assertions.assertEquals(6, WorldSceneExtraction.getViewModifier(3, CLOAK_VIEW_VISIBLE, false));
        Assertions.assertEquals(7, WorldSceneExtraction.getViewModifier(4, CLOAK_VIEW_VISIBLE, false));
        Assertions.assertEquals(6, WorldSceneExtraction.getViewModifier(0, CLOAK_VIEW_VISIBLE, false));
        Assertions.assertEquals(10, WorldSceneExtraction.getViewModifier(10, CLOAK_VIEW_VISIBLE, false), "shadows keep their draw function");
        // A seen burrowed sprite draws normally.
        Assertions.assertEquals(0, WorldSceneExtraction.getViewModifier(3, CLOAK_VIEW_VISIBLE, true));
    }

    @Test
    void get_view_modifier_should_keep_shared_draw_functions_for_unseen_cloaked_images() {
        // Modifiers 5..7 become 2..4; a burrowed sprite draws 0, 2 and 4 as 3.
        Assertions.assertEquals(3, WorldSceneExtraction.getViewModifier(3, CLOAK_VIEW_HIDDEN, false));
        Assertions.assertEquals(2, WorldSceneExtraction.getViewModifier(5, CLOAK_VIEW_HIDDEN, false));
        Assertions.assertEquals(0, WorldSceneExtraction.getViewModifier(0, CLOAK_VIEW_HIDDEN, false));
        Assertions.assertEquals(3, WorldSceneExtraction.getViewModifier(0, CLOAK_VIEW_HIDDEN, true));
        Assertions.assertEquals(3, WorldSceneExtraction.getViewModifier(3, CLOAK_VIEW_NONE, false), "units without detection keep their images");
    }
}
