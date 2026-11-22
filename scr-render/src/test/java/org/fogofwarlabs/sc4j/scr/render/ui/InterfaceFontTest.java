package org.fogofwarlabs.sc4j.scr.render.ui;

import org.fogofwarlabs.sc4j.engine.client.text.Font;
import org.fogofwarlabs.sc4j.engine.client.text.TextStyle;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.render.HiddenContextScenario;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Checks the text color codes of {@link InterfaceFont} and that the font measures as the engine {@link Font} and in a {@link TextStyle} with a
 * scale and without kerning as the native paths.
 */
final class InterfaceFontTest {
    /**
     * The system property that enables the GPU check and the message of a skipped check.
     */
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the GPU check.";
    /**
     * The width and height of the hidden context.
     */
    private static final int SIZE = 64;
    private static final @NotNull String @NotNull [] FONTS = {"font8", "font10", "font14", "font16", "font16x"};
    /**
     * A line with a color code switch and a restore, measured by the native and the engine path.
     */
    private static final @NotNull String ENGINE_TEXT = "Ag\u0006W\u00011";
    /**
     * The screen pixels per font pixel of the styled measure.
     */
    private static final float STYLE_SCALE = 2;

    @Test
    void is_color_code_should_not_accept_alignment_and_control_codes() {
        Assertions.assertFalse(InterfaceFont.isColorCode(0x12));
        Assertions.assertFalse(InterfaceFont.isColorCode(0x13));
        Assertions.assertFalse(InterfaceFont.isColorCode(0x0D));
        Assertions.assertFalse(InterfaceFont.isColorCode(0x1A));
        Assertions.assertTrue(InterfaceFont.isColorCode(0x07));
        Assertions.assertTrue(InterfaceFont.isColorCode(0x1F));
    }

    @RequiresInstallation
    @Test
    void measure_should_match_the_engine_font_and_the_scaled_style_without_kerning() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        TextStyle gameStyle = TextStyle.builder()
                                       .setColors(new InterfaceCodeColors(InterfaceText.GAME_CODE_COLORS))
                                       .setKerning(false)
                                       .setScale(STYLE_SCALE)
                                       .build();

        HiddenContextScenario.run(SIZE, SIZE, "Interface font as engine font", () -> {
            try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath()); InterfaceRenderer renderer = new InterfaceRenderer()) {
                ScrResourceProvider resources = ScrResourceProvider.borrow(storage);

                for (String name : FONTS) {
                    InterfaceFont font = InterfaceFont.load(resources, renderer, name);
                    Font engineFont = font;

                    try {
                        Assertions.assertEquals(font.measure(ENGINE_TEXT, true), engineFont.measure(ENGINE_TEXT), name);
                        Assertions.assertEquals(font.measure(ENGINE_TEXT, false) * STYLE_SCALE, font.measure(ENGINE_TEXT, gameStyle), name);
                    } finally {
                        font.close(renderer);
                    }
                }
            }
        });
    }
}
