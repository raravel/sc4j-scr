package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.scr.client.glue.*;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ListBox;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.WebPanelStyleUtils;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shows multiplayer provider information and the LAN protocol selection: the online service, which is Beacon.Link (the installed text names
 * it so), and the local network; the same game list, lobby and game screens play the games of both. Both play under the run player's
 * name, which the top right shows in place of the login button; Beacon.Link is offered only to a run with an account.
 */
public final class ConnectionScreen extends GlueScreen {
    private static final int LIST = 5;
    private static final int OK = 9;
    private static final int CANCEL = 10;
    private static final int INFORMATION_PANEL = 15;
    /**
     * The panel of the selected provider's pane, the protocol panel of LAN; Beacon.Link has no pane.
     */
    private static final int PANE_PANEL = 11;
    private static final int PROTOCOL_PANEL = 17;
    private static final int PROTOCOL_TITLE = 30;
    private static final int PROTOCOL_UDP = 31;
    /**
     * The controls of LAN's pane, which move with the pane panel.
     */
    private static final int @NotNull [] PANE_CONTROLS = {PROTOCOL_PANEL, PROTOCOL_TITLE, PROTOCOL_UDP};
    private static final int LOGIN = 21;
    private static final int PLAYER_NAME = 22;
    /**
     * The dialog controls the screen hides.
     */
    private static final int @NotNull [] HIDDEN = {18, 19};
    /**
     * Panel identifiers and directions for the connection-screen transitions.
     */
    private static final int @NotNull [] SWISH = {1, 0, 2, 2, 3, 3, 4, 2, 11, 0};
    private static final int SWISH_DELAY_MILLISECONDS = 0;
    /**
     * The rows of the online service (Beacon.Link) and of the local network.
     */
    private static final int BEACON_LINK_ROW = 0;
    private static final int LAN_ROW = 1;
    /**
     * The pane of a provider without one.
     */
    private static final int NO_PANE = -1;
    /**
     * The players a provider supports, as the screen shows it for LAN ({@code conSUPPORTED}).
     */
    private static final int SUPPORTED_PLAYERS = PlayerTable.PLAYABLE_PLAYER_SLOTS;

    /**
     * Padding and frame dimensions for provider information and protocol controls.
     */
    private static final int PAGE_PADDING = 6;
    private static final int PROTOCOL_PADDING_TOP = 10;
    private static final int DESCRIPTION_MARGIN = 10;
    private static final int BOX_MARGIN = 3;
    /**
     * The width of the protocol frame's border on each side.
     */
    private static final int BOX_BORDER = 1;
    private static final int BOX_PADDING = 4;
    private static final int BOX_CONTENT_HEIGHT = 98;
    /**
     * The sides a padding or border applies to: left and right, or top and bottom.
     */
    private static final int SIDE_COUNT = 2;

    private static final @NotNull String VISIBLE_FLAG = "WS_VISIBLE";
    private static final @NotNull String TAB_STOP_FLAG = "WS_TABSTOP";
    private static final @NotNull String BIG_TEXT_FLAG = "RC_BIGTEXT";
    /**
     * The name of the only supported LAN protocol.
     */
    private static final @NotNull String UDP_PROTOCOL_NAME = "UDP";
    private static final @NotNull String BATTLE_NET_TITLE_KEY = "spi_battlenet_title";
    private static final @NotNull String LAN_TITLE_KEY = "spi_lan_title";
    private static final @NotNull String LAN_REQUIREMENTS_KEY = "conUDP_d";

    private @Nullable ListBox providers;
    /**
     * The provider row whose pane the pane panel holds, or {@link #NO_PANE}.
     */
    private int paneProvider;
    /**
     * The height of the heading line: the big glue font's line height, rounded up.
     */
    private int headingLine;

    private @Nullable ScrControl okButton;
    private @Nullable ScrControl protocolTitle;
    private @Nullable ScrControl protocolButton;
    private @Nullable ScrControl protocolPanel;
    private @Nullable ScrControl panePanel;

    /**
     * Creates a {@link ConnectionScreen} instance.
     */
    public ConnectionScreen() {
        super("gluConn", SHARED_PALETTE_FOLDER, getPaletteBackground(SHARED_PALETTE_FOLDER));

        this.providers    = null;
        this.paneProvider = NO_PANE;
        this.headingLine  = 0;

        this.okButton       = null;
        this.protocolTitle  = null;
        this.protocolButton = null;
        this.protocolPanel  = null;
        this.panePanel      = null;
    }

    /**
     * Loads the screen's dialog: {@code rez\<dialog name>.ui.json}, or an empty dialog without a name. A screen whose
     * native content is not a control list builds its dialog here.
     * <p>
     * Keeps the dialog's controls and adds the protocol heading and radio button inside the protocol panel.
     *
     * @param glueContext the shared services
     * @return the dialog
     * @throws IOException if the dialog cannot be read or has no protocol panel
     */
    @Override
    protected @NotNull DialogDefinition loadDialog(@NotNull GlueContext glueContext) throws IOException {
        DialogDefinition dialog = super.loadDialog(glueContext);
        DialogControl panel = ScrDialogControlUtils.findControl(dialog, PROTOCOL_PANEL);

        if (panel == null) {
            throw Exceptions.newIOException("gluConn has no protocol panel.");
        }

        List<DialogControl> controls = new ArrayList<>(dialog.getControls());
        int left = panel.getX() + PAGE_PADDING;
        int width = panel.getWidth() - SIDE_COUNT * PAGE_PADDING;
        int headingTop = panel.getY() + PROTOCOL_PADDING_TOP;

        this.headingLine = (int) Math.ceil(glueContext.getCanvas().getLineHeight(GlueCanvas.BIG_FONT));

        controls.add(ScrDialogControlUtils.createControl(ScrControlType.LTEXT,
                                                         PROTOCOL_TITLE,
                                                         left, headingTop,
                                                         width, this.headingLine,
                                                         Set.of(VISIBLE_FLAG, BIG_TEXT_FLAG),
                                                         "",
                                                         List.of()));

        int boxTop = headingTop + this.headingLine + BOX_MARGIN;

        controls.add(ScrDialogControlUtils.createControl(ScrControlType.RADIOBUTTON,
                                                         PROTOCOL_UDP,
                                                         left + BOX_BORDER + BOX_PADDING, boxTop + BOX_BORDER + BOX_PADDING,
                                                         width - SIDE_COUNT * BOX_BORDER - SIDE_COUNT * BOX_PADDING, this.headingLine,
                                                         Set.of(VISIBLE_FLAG, TAB_STOP_FLAG, BIG_TEXT_FLAG, WebPanelStyleUtils.FLAG),
                                                         "",
                                                         List.of()));

        return new DialogDefinition(dialog.getName(), dialog.getX(), dialog.getY(), dialog.getWidth(), dialog.getHeight(), controls);
    }

    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * Fills the provider list, selects LAN and prepares the protocol controls and the panel transitions.
     *
     * @param glueContext the shared services
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) {
        this.okButton       = this.bindControl(OK);
        this.protocolTitle  = this.bindControl(PROTOCOL_TITLE);
        this.protocolButton = this.bindControl(PROTOCOL_UDP);
        this.protocolPanel  = this.bindControl(PROTOCOL_PANEL);
        this.panePanel      = this.bindControl(PANE_PANEL);

        for (int control : HIDDEN) {
            this.bindControl(control).setVisible(false);
        }

        this.bindControl(LOGIN).setVisible(false);
        this.bindControl(PLAYER_NAME).setReplacedText(glueContext.getTaggedPlayerName());

        this.setSwish(SWISH, SWISH_DELAY_MILLISECONDS);

        for (int control : PANE_CONTROLS) {
            this.moveWithPanel(control, PANE_PANEL);
        }

        requireBound(this.protocolTitle).setReplacedText(glueContext.getGluText().get("bnet118_selectConnectionScreen_LANPane_title"));
        // Select the only supported LAN protocol.
        requireBound(this.protocolButton).setReplacedText(UDP_PROTOCOL_NAME);
        requireBound(this.protocolButton).setChecked(true);

        ListBox list = this.addListBox(LIST);

        list.setTransparent(true);
        list.setItems(List.of(glueContext.getGluText().get(BATTLE_NET_TITLE_KEY), glueContext.getGluText().get(LAN_TITLE_KEY)));
        list.select(glueContext.isBeaconLinkSelected() ? BEACON_LINK_ROW : LAN_ROW, list.getVisibleRows(glueContext.getCanvas()));
        this.providers = list;

        int pane = this.findPane();

        if (pane == NO_PANE) {
            this.keepPanelOut(PANE_PANEL);
        }

        this.setPane(pane);
        this.updateOkButton();
    }

    /**
     * Called after the user changed the selection of a list or combo box, or the value of a slider.
     * <p>
     * Updates Ok and the pane when the provider selection changes.
     *
     * @param control the control id
     */
    @Override
    protected void onSelectionChanged(int control) {
        if (control == LIST) {
            this.updateOkButton();
            this.selectPane();
        }
    }

    /**
     * @return {@code true} if the provider list is open and its LAN row is selected, {@code false} otherwise
     */
    private boolean isLanSelected() {
        return this.providers != null && this.providers.getSelection() == LAN_ROW;
    }

    /**
     * @return {@code true} if the provider list is open and its Beacon.Link row is selected, {@code false} otherwise
     */
    private boolean isBeaconLinkSelected() {
        return this.providers != null && this.providers.getSelection() == BEACON_LINK_ROW;
    }

    /**
     * @return the selected provider's pane: {@link #LAN_ROW}, or {@link #NO_PANE} for Beacon.Link
     */
    private int findPane() {
        return this.isLanSelected() ? LAN_ROW : NO_PANE;
    }

    private void updateOkButton() {
        requireBound(this.okButton).setEnabled(this.isLanSelected() || this.isBeaconLinkSelected() && this.getContext().getBeaconLink().isAvailable());
    }

    /**
     * Shows the selected provider's pane. The pane panel slides out when it holds another pane and then slides in with the new pane, or stays
     * out when the provider has none; selecting the pane it holds slides it back in.
     */
    private void selectPane() {
        int pane = this.findPane();

        if (pane == this.paneProvider) {
            if (pane != NO_PANE) {
                this.slidePanelIn(PANE_PANEL);
            }

            return;
        }

        if (this.isPanelOnScreen(PANE_PANEL)) {
            this.slidePanelOut(PANE_PANEL, () -> this.showPane(pane));
        } else {
            this.showPane(pane);
        }
    }

    /**
     * Fills the pane panel with a pane and slides it in.
     *
     * @param pane the provider row whose pane to show, or {@link #NO_PANE}
     */
    private void showPane(int pane) {
        this.setPane(pane);

        if (pane != NO_PANE) {
            this.slidePanelIn(PANE_PANEL);
        }
    }

    /**
     * Sets the pane the pane panel holds: LAN's protocol pane is shown, no pane hides the panel.
     *
     * @param pane the provider row whose pane the panel holds, or {@link #NO_PANE}
     */
    private void setPane(int pane) {
        boolean lan = pane == LAN_ROW;

        this.paneProvider = pane;
        requireBound(this.protocolTitle).setVisible(lan);
        requireBound(this.protocolButton).setVisible(lan);
        requireBound(this.protocolPanel).setVisible(lan);
        requireBound(this.panePanel).setVisible(lan);
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     * <p>
     * Ok opens the game list of the local network or the Beacon.Link channel chat, whichever is selected and available; Cancel leaves
     * multiplayer mode and returns to the main menu.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
        GlueContext glueContext = this.getContext();

        switch (control) {
            case OK -> {
                boolean beaconLink = this.isBeaconLinkSelected() && glueContext.getBeaconLink().isAvailable();

                if (this.isLanSelected() || beaconLink) {
                    GlueNavigator.Destination next = beaconLink ? GlueNavigator.Destination.CHANNEL_CHAT : GlueNavigator.Destination.GAME_LIST;

                    glueContext.setBeaconLinkSelected(beaconLink);
                    this.leave(() -> glueContext.getNavigator().show(next));
                }
            }
            case CANCEL -> {
                glueContext.setMultiplayer(false);
                this.leave(() -> glueContext.getNavigator().show(GlueNavigator.Destination.MAIN_MENU));
            }
            default -> {
            }
        }
    }

    /**
     * Draws one control.
     * <p>
     * Draws the provider information and the protocol frame; the other controls draw as on every screen.
     *
     * @param canvas  the glue canvas
     * @param control the control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IOException if an image cannot be read
     */
    @Override
    protected void drawControl(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) throws IOException {
        switch (control.getId()) {
            case INFORMATION_PANEL -> {
                GlueContext glueContext = this.getContext();
                GluePalette palette = this.getPalette();
                int textColor = WebPanelStyleUtils.TEXT;
                boolean lanSelected = this.isLanSelected();
                String title = glueContext.getGluText().get(lanSelected ? LAN_TITLE_KEY : BATTLE_NET_TITLE_KEY);
                String supported = String.format(Locale.ROOT, glueContext.getGluText().get("conSUPPORTED"), SUPPORTED_PLAYERS);
                String requirements = lanSelected
                                      ? glueContext.getGluText().get(LAN_REQUIREMENTS_KEY)
                                      : OwnText.CONNECTION_REQUIREMENTS.get(glueContext.getLocale());

                float innerLeft = left + PAGE_PADDING;
                float innerWidth = control.getWidth() - SIDE_COUNT * PAGE_PADDING;
                float textTop = top + PAGE_PADDING;

                canvas.drawText(GlueCanvas.BIG_FONT,
                                title,
                                innerLeft, textTop,
                                innerWidth, this.headingLine,
                                GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                textColor,
                                palette);
                textTop += this.headingLine + DESCRIPTION_MARGIN;

                float lineHeight = canvas.getLineHeight(GlueCanvas.NORMAL_FONT);

                canvas.drawText(GlueCanvas.NORMAL_FONT,
                                supported,
                                innerLeft, textTop,
                                innerWidth, lineHeight,
                                GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                textColor,
                                palette);
                textTop += lineHeight + DESCRIPTION_MARGIN;

                canvas.drawText(GlueCanvas.NORMAL_FONT,
                                requirements,
                                innerLeft, textTop,
                                innerWidth, top + control.getHeight() - PAGE_PADDING - textTop,
                                GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                textColor,
                                palette);
            }
            case PROTOCOL_PANEL -> {
                // Draw the protocol frame below its heading.
                float boxTop = top + PROTOCOL_PADDING_TOP + this.headingLine + BOX_MARGIN;

                WebPanelStyleUtils.drawRoundedFrame(canvas,
                                                    left + PAGE_PADDING, boxTop,
                                                    control.getWidth() - SIDE_COUNT * PAGE_PADDING,
                                                    BOX_CONTENT_HEIGHT + SIDE_COUNT * BOX_PADDING + SIDE_COUNT * BOX_BORDER,
                                                    WebPanelStyleUtils.BORDER);
            }
            default -> super.drawControl(canvas, control, left, top);
        }
    }
}
