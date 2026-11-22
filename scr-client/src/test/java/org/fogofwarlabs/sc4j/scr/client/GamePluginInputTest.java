package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrInputModifiers;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrKeyInput;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrMouseInput;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks how the local input of a running game reaches its plugins: in extension order until a plugin consumes it, every release paired with
 * a delivered press, and a mouse release withheld from the game when a plugin consumed its press.
 */
final class GamePluginInputTest {
    /**
     * The Windows virtual-key codes of Q and W.
     */
    private static final int Q_KEY = 0x51;
    private static final int W_KEY = 0x57;
    private static final int MAP_X = 320;
    private static final int MAP_Y = 640;

    /**
     * A plugin that records the inputs it sees and consumes the presses of one key and one button.
     */
    private static final class RecordingPlugin implements ScrGamePlugin {
        private final int consumedKey;
        private final ScrMouseInput.@NotNull Button consumedButton;
        private final @NotNull List<ScrKeyInput> keys;
        private final @NotNull List<ScrMouseInput> buttons;

        private RecordingPlugin(int consumedKey, ScrMouseInput.@NotNull Button consumedButton) {
            this.consumedKey    = consumedKey;
            this.consumedButton = consumedButton;
            this.keys           = new ArrayList<>();
            this.buttons        = new ArrayList<>();
        }

        @Override
        public boolean onKeyInput(@NotNull ScrKeyInput input) {
            this.keys.add(input);

            return input.getVirtualKey() == this.consumedKey;
        }

        @Override
        public boolean onMouseInput(@NotNull ScrMouseInput input) {
            this.buttons.add(input);

            return input.getButton() == this.consumedButton;
        }
    }

    @Test
    void key_press_reaches_the_plugins_in_order_until_one_consumes_it() {
        GamePluginInput input = new GamePluginInput();
        RecordingPlugin first = new RecordingPlugin(Q_KEY, ScrMouseInput.Button.LEFT);
        RecordingPlugin second = new RecordingPlugin(-1, ScrMouseInput.Button.LEFT);
        ScrGamePlugin[] plugins = {first, second};

        Assertions.assertTrue(input.pressKey(plugins, Q_KEY, ScrInputModifiers.SHIFT));
        Assertions.assertFalse(input.pressKey(plugins, W_KEY, 0));

        Assertions.assertEquals(2, first.keys.size());
        Assertions.assertEquals(1, second.keys.size(), "the consumed press reaches no later plugin");
        Assertions.assertEquals(W_KEY, second.keys.getFirst().getVirtualKey());
        Assertions.assertTrue(first.keys.getFirst().isPressed());
        Assertions.assertEquals(ScrInputModifiers.SHIFT, first.keys.getFirst().getModifiers());
    }

    @Test
    void key_release_reaches_the_plugins_only_after_a_delivered_press() {
        GamePluginInput input = new GamePluginInput();
        RecordingPlugin plugin = new RecordingPlugin(-1, ScrMouseInput.Button.LEFT);
        ScrGamePlugin[] plugins = {plugin};

        input.releaseKey(plugins, Q_KEY, 0);

        Assertions.assertEquals(0, plugin.keys.size(), "no release without a delivered press");

        input.pressKey(plugins, Q_KEY, 0);
        input.releaseKey(plugins, Q_KEY, 0);
        input.releaseKey(plugins, Q_KEY, 0);

        Assertions.assertEquals(2, plugin.keys.size(), "one release per delivered press");
        Assertions.assertFalse(plugin.keys.getLast().isPressed());

        input.pressKey(plugins, Q_KEY, 0);
        input.reset();
        input.releaseKey(plugins, Q_KEY, 0);

        Assertions.assertEquals(3, plugin.keys.size(), "a new game forgets the delivered presses");
    }

    @Test
    void mouse_release_is_withheld_from_the_game_when_a_plugin_consumed_its_press() {
        GamePluginInput input = new GamePluginInput();
        RecordingPlugin plugin = new RecordingPlugin(-1, ScrMouseInput.Button.RIGHT);
        ScrGamePlugin[] plugins = {plugin};

        Assertions.assertTrue(input.pressButton(plugins, ScrMouseInput.Button.RIGHT, ScrInputModifiers.CONTROL, MAP_X, MAP_Y));
        Assertions.assertFalse(input.pressButton(plugins, ScrMouseInput.Button.LEFT, 0, MAP_X, MAP_Y));

        Assertions.assertTrue(input.releaseButton(plugins, ScrMouseInput.Button.RIGHT, 0, MAP_X, MAP_Y), "the game never saw the press");
        Assertions.assertFalse(input.releaseButton(plugins, ScrMouseInput.Button.LEFT, 0, MAP_X, MAP_Y), "the game saw the press");
        Assertions.assertFalse(input.releaseButton(plugins, ScrMouseInput.Button.MIDDLE, 0, MAP_X, MAP_Y), "no press, no release");

        Assertions.assertEquals(4, plugin.buttons.size());

        ScrMouseInput press = plugin.buttons.getFirst();

        Assertions.assertEquals(ScrMouseInput.Button.RIGHT, press.getButton());
        Assertions.assertTrue(press.isPressed());
        Assertions.assertEquals(ScrInputModifiers.CONTROL, press.getModifiers());
        Assertions.assertEquals(MAP_X, press.getMapX());
        Assertions.assertEquals(MAP_Y, press.getMapY());
        Assertions.assertFalse(plugin.buttons.get(2).isPressed());
    }
}
