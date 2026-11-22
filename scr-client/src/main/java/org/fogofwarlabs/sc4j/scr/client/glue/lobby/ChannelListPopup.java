package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChannelSummary;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueContext;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.WebPanelStyleUtils;
import org.fogofwarlabs.sc4j.scr.render.ui.TrueTypeText;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

/**
 * Shows the Beacon.Link channel list over the channel chat on the install's channel popup ({@code gluBattlenetChannelListPopup}): the channels
 * the session knows of with their member counts, the shared channel among them, the install's hint and a name input. A pressed channel fills
 * the input, a double press joins it; Ok joins the typed channel (a new name makes a new channel) and Cancel closes the list.
 */
final class ChannelListPopup extends BeaconLinkPopup {
    private static final @NotNull String DIALOG = "gluBattlenetChannelListPopup";
    private static final int OK = 92;
    private static final int CANCEL = 93;
    /**
     * The install's texts: the page's title and hint, the pages' Ok and Cancel.
     */
    private static final @NotNull String TITLE_KEY = "bnet_chat_channelSelectPopup_title";
    private static final @NotNull String HINT_KEY = "bnet_chat_channelSelectPopup_body";
    private static final @NotNull String OK_KEY = "str_ok";
    private static final @NotNull String CANCEL_KEY = "str_cancel";
    /**
     * The most characters of a channel name ({@code .channelSelectionInput}: {@code maxLength 31}).
     */
    private static final int NAME_LENGTH = 31;

    /**
     * A channel row ({@code .channelName}: the chat icon, then the name in {@code eurostile-reg} {@code 11} px in {@code #4cc428},
     * {@code #b4fc74} when selected), the member count at its right; as high as the add-friend popup's rows and inset as they are
     * ({@link ParticipantRowsUtils#ROW_HEIGHT}, {@link ParticipantRowsUtils#ROW_INSET}).
     */
    private static final float ROW_TEXT_SIZE = 11;
    private static final int ROW_TEXT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY, ROW_TEXT_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    private static final float ICON_SIZE = 10;
    private static final float ICON_GAP = 4;
    private static final @NotNull String ICON = "chat_bubble_32.png";
    private static final int CENTER_DIVISOR = 2;

    /**
     * Creates a {@link ChannelListPopup} instance with the install's labels.
     *
     * @param glueContext   the shared services
     * @param paletteFolder the palette folder of the chat screen
     * @param chat          the channel chat
     * @return the created {@link ChannelListPopup} instance
     */
    static @NotNull ChannelListPopup create(@NotNull GlueContext glueContext, @NotNull String paletteFolder, @NotNull ChannelChat chat) {
        return new ChannelListPopup(paletteFolder,
                                    glueContext.getLocale(),
                                    glueContext.getGluText().get(OK_KEY),
                                    glueContext.getGluText().get(CANCEL_KEY),
                                    chat);
    }

    private final @NotNull ChannelChat chat;
    private final @NotNull List<@NotNull ChannelSummary> channels;

    /**
     * Creates a {@link ChannelListPopup} instance.
     *
     * @param paletteFolder the palette folder of the chat screen
     * @param locale        the install locale of the texts, for example {@code koKR}
     * @param okLabel       the Ok button's label
     * @param cancelLabel   the Cancel button's label
     * @param chat          the channel chat
     */
    private ChannelListPopup(@NotNull String paletteFolder,
                             @NotNull String locale,
                             @NotNull String okLabel,
                             @NotNull String cancelLabel,
                             @NotNull ChannelChat chat) {
        super(DIALOG, paletteFolder, locale, List.of(new Button(OK, okLabel, true, false), new Button(CANCEL, cancelLabel, false, true)));

        this.chat     = chat;
        this.channels = chat.listChannels();
    }

    /**
     * @return the page's title
     */
    @Override
    @NotNull String getTitle() {
        return this.getContext().getGluText().get(TITLE_KEY);
    }

    /**
     * @return the hint the empty input shows, or an empty string for none
     */
    @Override
    @NotNull String getInputHint() {
        return "";
    }

    /**
     * @return the note line between the list and the input
     */
    @Override
    @NotNull String getNote() {
        return this.getContext().getGluText().get(HINT_KEY);
    }

    /**
     * @return {@code true} if the note is a word between two lines, {@code false} if it is a hint
     */
    @Override
    boolean isNoteSeparator() {
        return false;
    }

    /**
     * @return the most characters the input takes
     */
    @Override
    int getInputLength() {
        return NAME_LENGTH;
    }

    /**
     * @return the number of list rows
     */
    @Override
    int getRowCount() {
        return this.channels.size();
    }

    /**
     * Draws a list row.
     * <p>
     * Draws the chat icon, the channel's name and its member count.
     *
     * @param canvas   the glue canvas
     * @param row      the row index
     * @param selected whether the row is the selected one
     * @param left     the row's left edge in interface pixels
     * @param top      the row's top edge in interface pixels
     * @param width    the row's width
     * @throws IOException if the icon cannot be read
     */
    @Override
    void drawRow(@NotNull GlueCanvas canvas, int row, boolean selected, float left, float top, float width) throws IOException {
        ChannelSummary channel = this.channels.get(row);
        String count = Integer.toString(channel.getMemberCount());
        float height = ParticipantRowsUtils.ROW_HEIGHT;
        float contentLeft = left + ParticipantRowsUtils.ROW_INSET;
        float contentRight = left + width - ParticipantRowsUtils.ROW_INSET;
        float nameLeft = contentLeft + ICON_SIZE + ICON_GAP;
        float nameWidth = contentRight - canvas.measure(ROW_TEXT, count) - ParticipantRowsUtils.ROW_INSET - nameLeft;

        canvas.drawImage(canvas.getWebImage(ICON), contentLeft, top + (height - ICON_SIZE) / CENTER_DIVISOR, ICON_SIZE, ICON_SIZE);
        WebPanelStyleUtils.drawCenteredLine(canvas,
                                            ROW_TEXT,
                                            WebPanelStyleUtils.fitText(canvas, ROW_TEXT, channel.getName(), nameWidth),
                                            nameLeft, top,
                                            nameWidth, height,
                                            GlueCanvas.FORMAT_LEFT,
                                            selected ? WebPanelStyleUtils.HIGHLIGHT_TEXT : WebPanelStyleUtils.BUTTON_TEXT);
        WebPanelStyleUtils.drawCenteredLine(canvas,
                                            ROW_TEXT,
                                            count,
                                            contentLeft, top,
                                            contentRight - contentLeft, height,
                                            GlueCanvas.FORMAT_RIGHT,
                                            WebPanelStyleUtils.TEXT);
    }

    /**
     * Called after a press selected a row of the list or the space below its rows.
     * <p>
     * Fills the input with the pressed channel's name; a double press joins it.
     *
     * @param row         the selected row, or {@link WebRowList#NO_SELECTION}
     * @param doublePress whether the press is a double press
     */
    @Override
    void onRowPressed(int row, boolean doublePress) {
        if (row == WebRowList.NO_SELECTION) {
            return;
        }

        this.setInputText(this.channels.get(row).getName());

        if (doublePress) {
            this.join();
        }
    }

    /**
     * Updates the buttons' states after the selection or the input changed.
     * <p>
     * Ok is enabled while the input holds a name.
     */
    @Override
    void updateButtons() {
        this.bindControl(OK).setEnabled(!this.getInputText().isBlank());
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     * <p>
     * Ok joins the typed channel; Cancel closes the list.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
        if (control == OK) {
            this.join();
        } else if (control == CANCEL) {
            this.finish();
        }
    }

    private void join() {
        String name = this.getInputText().strip();

        if (name.isEmpty()) {
            return;
        }

        this.finish();
        this.chat.join(name);
    }
}
