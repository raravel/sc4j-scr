package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.client.host.NoTextInputMethod;
import org.fogofwarlabs.sc4j.engine.client.host.TextCompositionListener;
import org.fogofwarlabs.sc4j.engine.client.host.TextInputMethod;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatChannel;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueNavigator;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.InputMethod;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
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
import java.util.Locale;

/**
 * Checks the keyboard focus and the window's text input over the Beacon.Link screens on a hidden context: the text input stays on from the
 * chat page to the game creation and the room list (each opens before the left one closes), and a click on the chat input of either gives
 * it the keys, so a typed line is said in the channel.
 */
@RequiresInstallation
final class BeaconLinkFocusTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    private static final int SELF_KEY = 1;
    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 480;
    private static final int LARGE_WIDTH = 1920;
    private static final int LARGE_HEIGHT = 1080;
    /**
     * A point of the chat input under the room list and the game creation, in dialog pixels.
     */
    private static final float CHAT_INPUT_X = 190;
    private static final float CHAT_INPUT_Y = 461;

    /**
     * Records whether text input is on; it has no composition.
     */
    private static final class RecordingTextInputMethod implements TextInputMethod {
        private boolean enabled;

        @Override
        public boolean isEnabled() {
            return this.enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public void setListener(@Nullable TextCompositionListener listener) {
        }

        @Override
        public @Nullable Locale getCompositionLocale() {
            return null;
        }

        @Override
        public void cancelComposition() {
        }
    }

    /**
     * Drops the input events of a composition; the test types through the glue.
     */
    private static final class NoSink implements InputMethod.Sink {
        @Override
        public void onKeyEvent(@NotNull InputEvent event) {
        }

        @Override
        public void onCharacterTyped(int codePoint) {
        }
    }

    @TempDir
    @Nullable Path directory;

    private static @NotNull List<String> getSaid(@NotNull RecordingCommunitySession community) {
        for (ChatChannel channel : community.getChannels()) {
            if (channel.isJoined()) {
                return ((RecordingCommunitySession.Channel) channel).getSaid();
            }
        }

        return List.of();
    }

    @Test
    void text_input_should_stay_on_and_a_clicked_chat_input_should_take_the_keys() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        this.check("enUS", SMALL_WIDTH, SMALL_HEIGHT, GlueCanvas.SD_PREFIX);
        this.check("koKR", LARGE_WIDTH, LARGE_HEIGHT, GlueCanvas.HD_PREFIX);
    }

    private void check(@NotNull String locale, int width, int height, @NotNull String qualityPrefix) throws IOException {
        Path temporary = this.directory;

        Assertions.assertNotNull(temporary);

        String frame = width + "x" + height + "-" + locale;
        Path root = temporary.resolve(frame);
        RecordingTextInputMethod method = new RecordingTextInputMethod();
        RecordingCommunitySession community = new RecordingCommunitySession(
                RecordingCommunitySession.createPresence(SELF_KEY, "Raynor", BeaconLinkGlueScenario.MOD_ID, BeaconLinkGlueScenario.MOD_VERSION,
                                                         true, PlayerStatus.AVAILABLE)
        );

        InputMethod.install(method, new NoSink());

        try (BeaconLinkGlueScenario scenario = BeaconLinkGlueScenario.open(root, locale, width, height, qualityPrefix, community)) {
            List<String> textInput = new ArrayList<>();

            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            textInput.add("chat page " + method.isEnabled());
            scenario.show(GlueNavigator.Destination.CREATE_GAME);
            textInput.add("game creation " + method.isEnabled());
            scenario.click(CHAT_INPUT_X, CHAT_INPUT_Y);
            scenario.type("hello");
            scenario.press(Key.ENTER);

            List<String> saidOnCreate = List.copyOf(getSaid(community));

            scenario.show(GlueNavigator.Destination.GAME_LIST);
            textInput.add("room list " + method.isEnabled());
            scenario.click(CHAT_INPUT_X, CHAT_INPUT_Y);
            scenario.type("again");
            scenario.press(Key.ENTER);

            List<String> saidOnRooms = List.copyOf(getSaid(community));

            scenario.show(GlueNavigator.Destination.CHANNEL_CHAT);
            textInput.add("chat page " + method.isEnabled());
            scenario.type("back");
            scenario.press(Key.ENTER);

            Assertions.assertEquals(List.of("chat page true", "game creation true", "room list true", "chat page true"), textInput, frame);
            Assertions.assertEquals(List.of("hello"), saidOnCreate, frame);
            Assertions.assertEquals(List.of("hello", "again"), saidOnRooms, frame);
        } finally {
            InputMethod.install(new NoTextInputMethod(), new NoSink());
        }
    }
}
