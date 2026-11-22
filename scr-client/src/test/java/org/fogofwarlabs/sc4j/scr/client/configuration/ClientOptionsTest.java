package org.fogofwarlabs.sc4j.scr.client.configuration;

import org.fogofwarlabs.sc4j.scr.data.format.casc.SoundPathResolver;
import org.fogofwarlabs.sc4j.scr.render.hd.HdRenderAssets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies launch option validation, the replay camera, audio set, renderer and seek options, the startup tips of hidden runs, and file paths
 * under the selected client root.
 */
final class ClientOptionsTest {
    private static final @NotNull String ROOT_PROPERTY = "sc4j.root";
    private static final int SMOKE_FRAME_LIMIT = 120;

    /**
     * Restores the root property to its value before a test.
     *
     * @param previous the value before the test, or {@code null} if the property was not set
     */
    private static void restoreRootProperty(@Nullable String previous) {
        if (previous == null) {
            System.clearProperty(ROOT_PROPERTY);
        } else {
            System.setProperty(ROOT_PROPERTY, previous);
        }
    }

    @Test
    void parse_should_inspect_map_only_with_inspect_map_or_focus_unit() {
        Assertions.assertFalse(ClientOptions.parse(new String[]{"--replay", "test.rep"}).isInspectMap());
        Assertions.assertTrue(ClientOptions.parse(new String[]{"--replay", "test.rep", "--inspect-map"}).isInspectMap());
        Assertions.assertTrue(ClientOptions.parse(new String[]{"--replay", "test.rep", "--focus-unit", "0"}).isInspectMap());
    }

    @Test
    void parse_should_reject_hidden_window_without_positive_frame_limit() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--hidden"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--frames", "0"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--frames", "-1"}));
    }

    @Test
    void parse_should_give_a_hidden_window_its_content_scale() {
        Assertions.assertEquals(1, ClientOptions.parse(new String[]{"--hidden", "--frames", "3"}).getContentScale());
        Assertions.assertEquals(2, ClientOptions.parse(new String[]{"--hidden", "--frames", "3", "--content-scale", "2"}).getContentScale());
        Assertions.assertEquals(1.5f, ClientOptions.parse(new String[]{"--hidden", "--frames", "3", "--content-scale", "1.5"}).getContentScale());
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--content-scale", "2"}));
        Assertions.assertThrows(IllegalArgumentException.class,
                                () -> ClientOptions.parse(new String[]{"--hidden", "--frames", "3", "--content-scale", "0"}));
    }

    @Test
    void parse_should_turn_the_startup_tips_off_for_a_hidden_run_unless_its_settings_turn_them_on(@TempDir @NotNull Path root) throws IOException {
        Path settings = root.resolve("tips.json");

        Files.writeString(settings, "{\"game\":{\"showTips\":true}}");

        Assertions.assertTrue(ClientOptions.parse(new String[]{"--replay", "test.rep", "--settings", "none"}).getInterfaceSettings().isTipsShown());
        Assertions.assertFalse(ClientOptions.parse(new String[]{"--hidden", "--frames", "3"}).getInterfaceSettings().isTipsShown());
        Assertions.assertTrue(ClientOptions.parse(new String[]{"--hidden", "--frames", "3", "--settings", settings.toString()})
                                           .getInterfaceSettings().isTipsShown());
    }

    @Test
    void parse_should_accept_bounded_replay_capture_with_spaces_in_paths() {
        ClientOptions options = ClientOptions.parse(new String[]{"--data", "game data", "--replay", "recordings/test game.rep",
                                                                 "--hidden", "--frames", "3",
                                                                 "--screenshot", "test output/frame.png", "--focus-unit", "14"});

        Assertions.assertEquals(options.getInstallLayout().getRoot().resolve("game data"), options.getGameDirectory());
        Assertions.assertEquals(options.getInstallLayout().getRoot().resolve("recordings/test game.rep"), options.getReplayPath());
        Assertions.assertEquals(options.getInstallLayout().getRoot().resolve("test output/frame.png"), options.getScreenshotPath());
        Assertions.assertEquals(3, options.getFrameLimit());
        Assertions.assertEquals(14, options.getFocusUnit());
        Assertions.assertTrue(options.isHidden());
    }

    @Test
    void parse_should_accept_smoke_invocation_and_reject_invalid_arguments() {
        Assertions.assertEquals(SMOKE_FRAME_LIMIT, ClientOptions.parse(new String[]{"smoke"}).getFrameLimit());
        Assertions.assertEquals(SMOKE_FRAME_LIMIT, ClientOptions.parse(new String[]{"--smoke", "--hidden"}).getFrameLimit());
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--replay"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--unknown"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--focus-unit", "-1"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--focus-unit", "0"}));
    }

    @Test
    void parse_should_bound_unit_frame_and_mirror_unit_options() {
        ClientOptions options = ClientOptions.parse(new String[]{"--replay", "test.rep", "--focus-unit", "14",
                                                                 "--unit-frame", "17", "--mirror-unit", "--hidden", "--frames", "3"});

        Assertions.assertEquals(17, options.getUnitFrame());
        Assertions.assertTrue(options.isMirrorUnit());
        Assertions.assertEquals(0, ClientOptions.parse(new String[0]).getUnitFrame());
        Assertions.assertFalse(ClientOptions.parse(new String[0]).isMirrorUnit());
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--unit-frame", "-1"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--unit-frame", "1"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--unit-frame", "0"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--mirror-unit"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--unit-frame"}));
    }

    @Test
    void parse_should_bound_isolated_image_preview_options() {
        ClientOptions options = ClientOptions.parse(new String[]{"--replay", "test.rep", "--focus-unit", "0",
                                                                 "--image-animation", "255", "--image-steps", "10000"});

        Assertions.assertEquals(255, options.getImageAnimation());
        Assertions.assertEquals(10000, options.getImageSteps());
        Assertions.assertEquals(-1, ClientOptions.parse(new String[0]).getImageAnimation());
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--image-animation", "0"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--image-animation", "256"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--image-animation", "-1"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--image-steps", "0"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--image-steps", "10001"}));
        Assertions.assertThrows(IllegalArgumentException.class,
                                () -> ClientOptions.parse(new String[]{"--replay", "test.rep", "--focus-unit", "0",
                                                                       "--image-animation", "0", "--unit-frame", "0"}));
        Assertions.assertThrows(IllegalArgumentException.class,
                                () -> ClientOptions.parse(new String[]{"--replay", "test.rep", "--focus-unit", "0",
                                                                       "--image-animation", "0", "--mirror-unit"}));
    }

    @Test
    void parse_should_bound_preview_steps_independently_of_frame_limit() {
        ClientOptions options = ClientOptions.parse(new String[]{"--replay", "test.rep", "--preview-steps", "12", "--frames", "3"});

        Assertions.assertEquals(12, options.getPreviewSteps());
        Assertions.assertEquals(3, options.getFrameLimit());
        Assertions.assertEquals(0, ClientOptions.parse(new String[0]).getPreviewSteps());
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--preview-steps", "0"}));
        Assertions.assertThrows(IllegalArgumentException.class,
                                () -> ClientOptions.parse(new String[]{"--replay", "test.rep", "--preview-steps", "-1"}));
        Assertions.assertThrows(IllegalArgumentException.class,
                                () -> ClientOptions.parse(new String[]{"--replay", "test.rep", "--preview-steps", "10001"}));
    }

    @Test
    void parse_should_select_map_compatibility_only_on_request() {
        Assertions.assertFalse(ClientOptions.parse(new String[]{"--replay", "test.rep"}).isMapCompatibility());
        Assertions.assertTrue(ClientOptions.parse(new String[]{"--replay", "test.rep", "--map-compat"}).isMapCompatibility());
    }

    @Test
    void parse_should_reject_control_and_recording_options_as_unknown() {
        for (String option : new String[]{"--control", "--recording", "--save-recording", "--control-steps", "--move-to"}) {
            IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                                                                         () -> ClientOptions.parse(new String[]{"--replay", "test.rep", option}));

            Assertions.assertEquals("Unknown client option: " + option, exception.getMessage());
        }
    }

    @Test
    void parse_should_resolve_relative_files_under_selected_root_and_preserve_absolute_files() {
        String previous = System.getProperty(ROOT_PROPERTY);
        Path root = Path.of("chosen root").toAbsolutePath().normalize();
        Path absoluteReplay = Path.of("outside root", "game.rep").toAbsolutePath();

        try {
            System.setProperty(ROOT_PROPERTY, root.toString());

            ClientOptions options = ClientOptions.parse(new String[]{"--settings", "none", "--data", "game data",
                                                                     "--replay", "recordings/game.rep", "--screenshot", "captures/frame.png",
                                                                     "--input-script", "commands.txt"});

            Assertions.assertEquals(root.resolve("game data"), options.getGameDirectory());
            Assertions.assertEquals(root.resolve("recordings/game.rep"), options.getReplayPath());
            Assertions.assertEquals(root.resolve("captures/frame.png"), options.getScreenshotPath());
            Assertions.assertEquals(root.resolve("commands.txt"), options.getInputScript());

            ClientOptions absolute = ClientOptions.parse(new String[]{"--settings", "none", "--replay", absoluteReplay.toString()});

            Assertions.assertEquals(absoluteReplay, absolute.getReplayPath());
        } finally {
            restoreRootProperty(previous);
        }
    }

    @Test
    void parse_should_place_paths_and_settings_under_a_given_root(@TempDir @NotNull Path root) {
        InstallLayout layout = InstallLayout.of(root.resolve("userdata").resolve("mods").resolve("scr"));
        ClientOptions options = ClientOptions.parse(new String[]{"--data", "game data", "--melee", "maps/game.scm"}, layout);
        ClientOptions.Melee melee = options.getMelee();

        Assertions.assertSame(layout, options.getInstallLayout());
        Assertions.assertEquals(layout.getRoot().resolve("game data"), options.getGameDirectory());
        Assertions.assertEquals(layout.getSettingsFile(), options.getSettingsFile());
        Assertions.assertNotNull(melee);
        Assertions.assertEquals(layout.getMapDirectory().resolve("game.scm"), melee.getMap());
    }

    @Test
    void parse_should_read_and_write_selected_settings_and_resolve_map_and_save_paths(@TempDir @NotNull Path root) throws IOException {
        String previous = System.getProperty(ROOT_PROPERTY);
        Path settings = root.resolve("selected settings.json");

        Files.writeString(settings, "{\"paths\":{\"data\":\"game data\"}}");

        try {
            System.setProperty(ROOT_PROPERTY, root.toString());

            ClientOptions options = ClientOptions.parse(new String[]{"--settings", settings.getFileName().toString(),
                                                                     "--ums", "maps/game.scx", "--save", "replays/game.rep"});
            ClientOptions.Melee melee = options.getMelee();

            Assertions.assertEquals(settings, options.getSettingsFile());
            Assertions.assertEquals(root.resolve("game data"), options.getGameDirectory());
            Assertions.assertNotNull(melee);
            Assertions.assertEquals(root.resolve("maps/game.scx"), melee.getMap());
            Assertions.assertEquals(root.resolve("replays/game.rep"), melee.getSave());

            ClientOptions campaign = ClientOptions.parse(new String[]{"--settings", "none", "--campaign", "bw-terran-1"});
            ClientOptions.Melee campaignGame = campaign.getMelee();

            Assertions.assertNotNull(campaignGame);
            Assertions.assertEquals(Path.of("bw-terran-1"), campaignGame.getMap());
            Assertions.assertEquals("bw-terran-1", campaignGame.getCampaign());
        } finally {
            restoreRootProperty(previous);
        }
    }

    @Test
    void parse_should_read_initial_replay_camera_and_default_to_unset() {
        ClientOptions options = ClientOptions.parse(new String[]{"--replay", "test.rep", "--camera", "3850,3800.5", "--zoom", "2"});

        Assertions.assertEquals(3850, options.getCameraX());
        Assertions.assertEquals(3800.5f, options.getCameraY());
        Assertions.assertEquals(2, options.getZoom());

        ClientOptions defaults = ClientOptions.parse(new String[]{"--replay", "test.rep"});

        Assertions.assertTrue(Float.isNaN(defaults.getCameraX()));
        Assertions.assertTrue(Float.isNaN(defaults.getCameraY()));
        Assertions.assertTrue(Float.isNaN(defaults.getZoom()));
    }

    @Test
    void parse_should_reject_invalid_camera_and_zoom() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--camera", "10,10"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--replay", "test.rep", "--camera", "10"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--replay", "test.rep", "--camera", "NaN,1"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--replay", "test.rep", "--zoom", "0"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--replay", "test.rep", "--zoom"}));
    }

    @Test
    void parse_should_default_audio_to_remastered_independent_of_renderer() {
        Assertions.assertEquals(SoundPathResolver.AudioSet.REMASTERED, ClientOptions.parse(new String[0]).getAudioSet());
        Assertions.assertEquals(SoundPathResolver.AudioSet.REMASTERED, ClientOptions.parse(new String[]{"--renderer", "classic"}).getAudioSet());
        Assertions.assertEquals(SoundPathResolver.AudioSet.CLASSIC,
                                ClientOptions.parse(new String[]{"--audio", "classic", "--renderer", "hd2"}).getAudioSet());
        Assertions.assertEquals(SoundPathResolver.AudioSet.REMASTERED, ClientOptions.parse(new String[]{"--audio", "remastered"}).getAudioSet());
    }

    @Test
    void parse_should_reject_unknown_audio_set() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--audio", "hd"}));
    }

    @Test
    void parse_should_select_renderer_quality_and_reject_unknown_renderer() {
        Assertions.assertEquals(HdRenderAssets.Quality.HD, ClientOptions.parse(new String[]{"--settings", "none"}).getSceneQuality());
        Assertions.assertNull(ClientOptions.parse(new String[]{"--renderer", "classic"}).getSceneQuality());
        Assertions.assertEquals(HdRenderAssets.Quality.HD2, ClientOptions.parse(new String[]{"--renderer", "hd2"}).getSceneQuality());
        Assertions.assertEquals(HdRenderAssets.Quality.HD, ClientOptions.parse(new String[]{"--renderer", "hd"}).getSceneQuality());
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--renderer", "carbot"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--renderer"}));
    }

    @Test
    void parse_should_read_forward_and_backward_seek_targets_in_order() {
        ClientOptions options = ClientOptions.parse(new String[]{"--replay", "a.rep", "--seek", "900, 100,2000", "--frames", "10"});

        Assertions.assertArrayEquals(new long[]{900, 100, 2000}, options.getSeekFrames());
        Assertions.assertEquals(900, options.getSeekFrame());
    }

    @Test
    void parse_should_keep_single_seek_target_and_empty_default() {
        Assertions.assertArrayEquals(new long[]{42}, ClientOptions.parse(new String[]{"--replay", "a.rep", "--seek", "42"}).getSeekFrames());

        ClientOptions none = ClientOptions.parse(new String[]{"--replay", "a.rep"});

        Assertions.assertEquals(0, none.getSeekFrames().length);
        Assertions.assertEquals(-1, none.getSeekFrame());
    }

    @Test
    void parse_should_reject_negative_seek_targets() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--replay", "a.rep", "--seek", "10,-1"}));
    }

    @Test
    void parse_should_reject_overflowing_launch_wait_count() {
        String[] arguments = {"--command", "/wait 99999999999999999999"};
        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(arguments));

        Assertions.assertTrue(exception.getMessage().contains("--command"), exception.getMessage());
    }

    @Test
    void parse_should_reject_partial_speed_label() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--replay", "a.rep", "--speed", "ax"}));
    }

    @Test
    void parse_should_reject_malformed_values_with_the_option_name() {
        String[][] invalidArguments = {{"--replay", "a.rep", "--seek", ","}, {"--replay", "a.rep", "--console", ":replay"},
                                       {"--replay", "a.rep", "--focus-unit", "x"}, {"--replay", "a.rep", "--camera", "1,y"},
                                       {"--window", "640xlarge"}};

        for (String[] arguments : invalidArguments) {
            String option = arguments[arguments.length - 2];
            IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(arguments));

            Assertions.assertTrue(exception.getMessage().contains(option), () -> option + ": " + exception.getMessage());
        }
    }
}
