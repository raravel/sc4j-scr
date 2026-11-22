package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.scr.data.format.text.LegacyTextUtils;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFont;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.IntSupplier;

/**
 * Supplies message-line wrapping and the host clock used by the in-game message model.
 * The layout retains byte offsets for decoded text; the message model truncates a piece longer than a line ({@link MessageLines#write}).
 */
public final class MessageAreaUtils {
    /**
     * The top of the first row.
     */
    public static final int FIRST_ROW = 112;
    /**
     * The row step ({@code lineH = 13 + 3}).
     */
    public static final int ROW_STEP = 16;
    /**
     * The left and right margin of the rows: clip x {@code 10 .. W - 10}.
     */
    public static final int MARGIN = 10;
    /**
     * The top of the status line.
     */
    public static final int STATUS_ROW = 295;

    private static final int SINGLE_BYTE_LIMIT = 0x80;
    private static final int TWO_BYTE_LIMIT = 0x800;
    private static final int TWO_BYTE_LENGTH = 2;
    private static final int THREE_BYTE_LENGTH = 3;
    private static final int SURROGATE_PAIR_BYTES = 4;

    /**
     * Creates an {@link IntSupplier} instance that reads the millisecond host clock of the message deadlines.
     *
     * @return the created {@link IntSupplier} instance
     */
    public static @NotNull IntSupplier createHostClock() {
        return () -> (int) UiClockUtils.getHostTimeMilliseconds();
    }

    /**
     * Creates a {@link MessageLines.TextLayout} instance that wraps messages by bytes for the supplied font and width.
     * Word and character breaks come from the shared interface-text wrapper, without a byte limit; the final piece ends at the input byte length.
     *
     * @param font  the message font
     * @param width the current wrap width in interface pixels
     * @return the created {@link MessageLines.TextLayout} instance
     */
    public static MessageLines.@NotNull TextLayout createTextLayout(@NotNull InterfaceFont font, @NotNull IntSupplier width) {
        return text -> {
            String decoded = LegacyTextUtils.decode(text, 0, text.length);
            int[] byteOffsets = calculateByteOffsets(text, decoded);
            int[] breaks = InterfaceText.wrapBreaks(font, decoded, width.getAsInt());
            int[] ends = new int[breaks.length];

            for (int index = 0; index < ends.length; index++) {
                ends[index] = byteOffsets[breaks[index]];
            }

            ends[ends.length - 1] = text.length;

            return ends;
        };
    }

    /**
     * Calculates each decoded character's byte offset, using UTF-8 lengths for valid UTF-8 input and legacy lengths otherwise.
     *
     * @param text    the message bytes
     * @param decoded the decoded message text
     * @return the byte offset of each decoded character, followed by the byte length
     */
    private static int @NotNull [] calculateByteOffsets(byte @NotNull [] text, @NotNull String decoded) {
        int[] offsets = new int[decoded.length() + 1];
        boolean utf8;

        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(text));

            utf8 = true;
        } catch (CharacterCodingException exception) {
            utf8 = false;
        }

        int offset = 0;

        for (int index = 0; index < decoded.length(); index++) {
            offsets[index] = Math.min(offset, text.length);

            char character = decoded.charAt(index);

            if (utf8) {
                offset += character < SINGLE_BYTE_LIMIT
                          ? 1
                          : character < TWO_BYTE_LIMIT
                            ? TWO_BYTE_LENGTH
                            : Character.isHighSurrogate(character)
                              ? SURROGATE_PAIR_BYTES
                              : Character.isLowSurrogate(character) ? 0 : THREE_BYTE_LENGTH;
            } else {
                offset += character < SINGLE_BYTE_LIMIT ? 1 : TWO_BYTE_LENGTH;
            }
        }

        offsets[decoded.length()] = text.length;

        return offsets;
    }

    private MessageAreaUtils() {
    }
}
