package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.fogofwarlabs.sc4j.engine.resource.text.FontFace;
import org.fogofwarlabs.sc4j.scr.client.ui.console.ConsoleLayout;
import org.fogofwarlabs.sc4j.scr.client.ui.console.MessageAreaUtils;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceKeys;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFont;
import org.fogofwarlabs.sc4j.scr.render.ui.TrueTypeText;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;

/**
 * The message line layout the client installs ({@code ConsoleView.installMessageLines}): {@code font10} wrapping at the classic interface width
 * less the message margins ({@link MessageAreaUtils#createTextLayout}), measured without an OpenGL context.
 * <p>
 * The client measures with its game font set ({@code InterfaceFonts}), whose glyph atlases need a context. The metrics come from the same CASC
 * files: the {@code font10} face and the TrueType chain of the normal slot, the font built through {@code InterfaceFont}'s own constructor with no
 * atlas texture, and its text path set as {@code InterfaceFonts.setViewport} sets it for the classic 640x480 screen (TrueType for the
 * Korean, Chinese and Japanese locales, the {@code .fnt} bitmaps otherwise, render scale 1).
 */
final class MessageLayout implements AutoCloseable {
    /**
     * The message area width of the classic console: the interface width less the left and right margins.
     */
    static final int WRAP_WIDTH = ConsoleLayout.INTERFACE_WIDTH - 2 * MessageAreaUtils.MARGIN;

    private static final @NotNull String NORMAL_FONT = "font10";
    private static final @NotNull String @NotNull [] NON_LATIN_LOCALES = {"ko", "zh", "ja"};

    private final @NotNull TrueTypeText trueType;
    private final MessageLines.@NotNull TextLayout layout;

    private MessageLayout(@NotNull TrueTypeText trueType, @NotNull InterfaceFont font) {
        this.trueType = trueType;
        this.layout   = MessageAreaUtils.createTextLayout(font, () -> WRAP_WIDTH);
    }

    /**
     * Loads the metrics of the client's normal game font.
     *
     * @param resources the installation's resources
     * @param locale    the client's text locale, for example {@code koKR}
     * @return the layout
     * @throws IOException if a font cannot be read
     */
    static @NotNull MessageLayout load(@NotNull ScrResourceProvider resources, @NotNull String locale) throws IOException {
        // The TrueType chain keeps its renderer only to draw glyphs; measuring never reaches it.
        TrueTypeText trueType = new TrueTypeText(resources.getStorage(), null);

        try {
            FontFace face = resources.loadFont(ScrResourceKeys.ofFont(NORMAL_FONT));
            InterfaceFont font = createMetricsFont(face);
            String language = locale.toLowerCase(Locale.ROOT);
            boolean nonLatin = false;

            for (String prefix : NON_LATIN_LOCALES) {
                nonLatin |= language.startsWith(prefix);
            }

            trueType.setLocale(locale);
            font.setTrueType(trueType, TrueTypeText.MEDIUM);
            font.setMode(nonLatin, 1);

            return new MessageLayout(trueType, font);
        } catch (IOException | RuntimeException exception) {
            trueType.close();

            throw exception;
        }
    }

    /**
     * Creates the font as {@code InterfaceFont.load} does, without its glyph atlas texture (texture {@code 0}).
     */
    private static @NotNull InterfaceFont createMetricsFont(@NotNull FontFace face) throws IOException {
        try {
            Constructor<InterfaceFont> constructor = InterfaceFont.class.getDeclaredConstructor(FontFace.class, int.class, int.class, int.class,
                                                                                               int.class);

            constructor.setAccessible(true);

            return constructor.newInstance(face, face.getLineHeight(), 0, face.getImageWidth(), face.getImageHeight());
        } catch (InvocationTargetException exception) {
            throw new IOException("The message font cannot be created: " + exception.getCause(), exception.getCause());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IOException("The message font cannot be created: " + exception, exception);
        }
    }

    /**
     * @return the layout to install into the message lines
     */
    MessageLines.@NotNull TextLayout getLayout() {
        return this.layout;
    }

    @Override
    public void close() {
        this.trueType.close();
    }
}
