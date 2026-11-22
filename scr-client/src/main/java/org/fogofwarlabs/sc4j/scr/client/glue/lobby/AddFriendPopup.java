package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueContext;
import org.fogofwarlabs.sc4j.scr.client.glue.OwnText;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds a Beacon.Link friend over the channel chat on the install's add-friend popup ({@code gluBattlenetAddFriendPopup}): the suggested
 * friends (the channel's members who are not friends yet) in a group that folds open or shut at its label, the install's "or" between two
 * lines, and a name input ({@code Name#tag}). A pressed member fills the input, a double press adds the member; Ok adds the typed player and
 * Cancel closes the popup.
 */
final class AddFriendPopup extends BeaconLinkPopup {
    private static final @NotNull String DIALOG = "gluBattlenetAddFriendPopup";
    private static final int OK = 92;
    private static final int CANCEL = 93;
    /**
     * The most characters of a typed tagged name.
     */
    private static final int NAME_LENGTH = 48;
    /**
     * The install's texts: the page's title, the suggested friends' group, the word between the list and the input, Ok and Cancel.
     */
    private static final @NotNull String TITLE_KEY = "bnet118_addFriendPopup_title";
    private static final @NotNull String SUGGESTED_KEY = "bnet119_addFriendPopup_suggestFriend";
    private static final @NotNull String OR_KEY = "bnet119_addFriendPopup_or";
    private static final @NotNull String OK_KEY = "str_ok";
    private static final @NotNull String CANCEL_KEY = "str_cancel";

    /**
     * The row of the suggested friends' group label.
     */
    private static final int GROUP_ROW = 0;

    /**
     * Creates an {@link AddFriendPopup} instance with the install's labels.
     *
     * @param glueContext   the shared services
     * @param paletteFolder the palette folder of the chat screen
     * @param chat          the channel chat
     * @return the created {@link AddFriendPopup} instance
     */
    static @NotNull AddFriendPopup create(@NotNull GlueContext glueContext, @NotNull String paletteFolder, @NotNull ChannelChat chat) {
        return new AddFriendPopup(paletteFolder,
                                  glueContext.getLocale(),
                                  glueContext.getGluText().get(OK_KEY),
                                  glueContext.getGluText().get(CANCEL_KEY),
                                  chat);
    }

    private final @NotNull ChannelChat chat;

    /**
     * Whether the suggested friends' group shows its members.
     */
    private boolean expanded;

    /**
     * Creates an {@link AddFriendPopup} instance.
     *
     * @param paletteFolder the palette folder of the chat screen
     * @param locale        the install locale of the texts, for example {@code koKR}
     * @param okLabel       the Ok button's label
     * @param cancelLabel   the Cancel button's label
     * @param chat          the channel chat
     */
    private AddFriendPopup(@NotNull String paletteFolder,
                           @NotNull String locale,
                           @NotNull String okLabel,
                           @NotNull String cancelLabel,
                           @NotNull ChannelChat chat) {
        super(DIALOG, paletteFolder, locale, List.of(new Button(OK, okLabel, true, false), new Button(CANCEL, cancelLabel, false, true)));

        this.chat     = chat;
        this.expanded = true;
    }

    /**
     * @return the channel's members who are neither this player nor a friend, in the channel's order
     */
    private @NotNull List<PlayerPresence> getSuggested() {
        List<PlayerPresence> suggested = new ArrayList<>();

        for (PlayerPresence member : this.chat.getMembers()) {
            if (!this.chat.isSelf(member) && !this.chat.isFriend(member)) {
                suggested.add(member);
            }
        }

        return suggested;
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
        return OwnText.FRIEND_LABEL.get(this.getLocale());
    }

    /**
     * @return the note line between the list and the input
     */
    @Override
    @NotNull String getNote() {
        return this.getContext().getGluText().get(OR_KEY);
    }

    /**
     * @return {@code true} if the note is a word between two lines, {@code false} if it is a hint
     */
    @Override
    boolean isNoteSeparator() {
        return true;
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
        return this.expanded ? this.getSuggested().size() + 1 : 1;
    }

    /**
     * Draws a list row.
     * <p>
     * Draws the group's label, or a suggested member's names, status, mod and latency bars.
     *
     * @param canvas   the glue canvas
     * @param row      the row index
     * @param selected whether the row is the selected one
     * @param left     the row's left edge in interface pixels
     * @param top      the row's top edge in interface pixels
     * @param width    the row's width
     * @throws IOException if an image cannot be read
     */
    @Override
    void drawRow(@NotNull GlueCanvas canvas, int row, boolean selected, float left, float top, float width) throws IOException {
        List<PlayerPresence> suggested = this.getSuggested();

        if (row == GROUP_ROW) {
            ParticipantRowsUtils.drawGroupLabel(canvas, this.expanded, this.getContext().getGluText().get(SUGGESTED_KEY), suggested.size(),
                                                left, top, width);

            return;
        }

        PlayerPresence member = suggested.get(row - 1);

        ParticipantRowsUtils.drawMember(canvas,
                                        left, top, width,
                                        selected,
                                        true,
                                        member.getName(), member.getTaggedName(), ParticipantRowsUtils.TEXT_COLOR,
                                        ParticipantRowsUtils.getStatusIcon(member), this.chat.getStatusText(member),
                                        ParticipantRowsUtils.getModText(member), member.canPlayTogether(),
                                        LatencyBarsUtils.getBarCount(member.getLatencyEstimate().getLatencyClass()));
    }

    /**
     * Called after a press selected a row of the list or the space below its rows.
     * <p>
     * A press on the group's label folds the group open or shut; a pressed member fills the input with its tagged name, a double press adds it.
     *
     * @param row         the selected row, or {@link WebRowList#NO_SELECTION}
     * @param doublePress whether the press is a double press
     */
    @Override
    void onRowPressed(int row, boolean doublePress) {
        if (row == WebRowList.NO_SELECTION) {
            return;
        }

        if (row == GROUP_ROW) {
            this.expanded = !this.expanded;
            this.clearSelection();

            return;
        }

        this.setInputText(this.getSuggested().get(row - 1).getTaggedName());

        if (doublePress) {
            this.add();
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
     * Ok adds the typed player and closes the popup once it is a friend; Cancel closes the popup.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
        if (control == OK) {
            this.add();
        } else if (control == CANCEL) {
            this.finish();
        }
    }

    private void add() {
        String name = this.getInputText().strip();

        if (!name.isEmpty() && this.chat.addFriend(name)) {
            this.finish();
        }
    }
}
