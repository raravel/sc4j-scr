package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks the {@code stat_txt} overrides: a set text is found until it is removed, and the change count follows only real changes.
 */
final class StatTextOverridesTest {
    private static final int MARINE_NAME = 1;

    @Test
    void set_should_override_and_remove_a_string() {
        StatTextOverrides overrides = new StatTextOverrides();

        Assertions.assertNull(overrides.find(MARINE_NAME));

        overrides.set(MARINE_NAME, "Space Marine");
        overrides.set(MARINE_NAME, "Space Marine");

        Assertions.assertEquals("Space Marine", overrides.find(MARINE_NAME));
        Assertions.assertEquals(1, overrides.getChangeCount());

        overrides.set(MARINE_NAME, null);
        overrides.set(MARINE_NAME, null);

        Assertions.assertNull(overrides.find(MARINE_NAME));
        Assertions.assertEquals(2, overrides.getChangeCount());
    }
}
