package org.fogofwarlabs.sc4j.scr.client.glue.menu;

import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLinkSession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkNode;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkNodeState;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.scr.client.ReleaseState;
import org.fogofwarlabs.sc4j.scr.client.ReleaseStatus;
import org.fogofwarlabs.sc4j.scr.client.glue.*;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.OptionsPopup;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.net.lan.LanClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shows animated game-choice and exit buttons together with the available menu actions.
 * <p>
 * Single Player and Multiplayer ask for Original or Expansion before opening the campaigns or the connection choice; a run without a
 * player ({@link GlueContext#hasPlayer()}) chooses its local ID on the ID screen in between. Options, Hotkeys, Credits and Intro open their
 * corresponding scenes. The top right names the run's player ({@code Name#tag}, or the local ID's name) in place of the login button,
 * which is hidden; without a player it shows the login button, which opens the ID screen. The unavailable collection action is disabled.
 * Runtime information lines are cleared, and the build line shows version, locale and platform.
 * <p>
 * The top right frame holds SC4J's status panel in place of the web panel: a title over a rule and rows of a label and a value, the running
 * SC4J version, the latest release, whether an update is available and how Beacon.Link stands. The values follow the run's release status
 * ({@link GlueContext#getReleaseStatus()}), whose one check per run starts when the panel first shows it, and the glue's Beacon.Link session
 * while the menu is shown.
 */
public final class MainMenuScreen extends GlueScreen {
    private static final @NotNull String DIALOG = "gluMain";
    private static final @NotNull String PALETTE_FOLDER = "glue/palmm";
    private static final int EXIT = 2;
    private static final int SINGLE_PLAYER = 3;
    private static final int MULTIPLAYER = 4;
    private static final int VIEW_INTRO = 8;
    private static final int LOGIN = 10;
    private static final int PLAYER_NAME = 11;
    private static final @NotNull String NO_PLAYER_NAME = "";
    private static final int OPTIONS = 12;
    private static final int LOCK = 13;
    private static final int HOTKEYS = 15;
    private static final int CREDITS = 16;
    private static final int SINGLE_PLAYER_UNAVAILABLE = 21;
    private static final int COLLECTIONS = 28;
    /**
     * The information controls whose runtime text is cleared when the menu opens.
     */
    private static final int @NotNull [] RUNTIME_TEXTS = {9, 24, 25, 26};
    /**
     * Identifies the right-aligned build line at the bottom of the main menu.
     * The text joins the build version, locale and platform with the configured separator.
     */
    private static final int BUILD_LINE = 27;
    private static final @NotNull String BUILD_LINE_SEPARATOR = " | ";
    private static final @NotNull String PLATFORM = "64-bit";

    /**
     * The web panel of the top right frame, whose rectangle the status panel takes.
     */
    private static final int STATUS_SLOT = 20;
    /**
     * The ids of the status panel's controls, above the dialog's own ids.
     */
    private static final int STATUS_TITLE = 100;
    private static final int STATUS_RULE = 101;
    private static final int FIRST_STATUS_LABEL = 110;
    private static final int FIRST_STATUS_VALUE = 120;
    /**
     * The rows of the status panel, top to bottom.
     */
    private static final @NotNull List<OwnText> STATUS_ROWS = List.of(OwnText.VERSION, OwnText.LATEST_RELEASE, OwnText.UPDATE, OwnText.BEACON_LINK);
    private static final int VERSION_ROW = 0;
    private static final int LATEST_RELEASE_ROW = 1;
    private static final int UPDATE_ROW = 2;
    private static final int BEACON_LINK_ROW = 3;
    /**
     * The status panel's grid inside the slot, in interface pixels.
     */
    private static final int STATUS_MARGIN = 12;
    private static final int STATUS_TITLE_TOP = 10;
    private static final int STATUS_TITLE_HEIGHT = 20;
    private static final int STATUS_RULE_TOP = 36;
    private static final int STATUS_RULE_THICKNESS = 1;
    private static final int STATUS_BODY_TOP = 46;
    private static final int STATUS_ROW_HEIGHT = 18;
    private static final int STATUS_ROW_GAP = 6;
    private static final int STATUS_SIDES = 2;
    private static final @NotNull String STATUS_TITLE_TEXT = "SC4J";
    private static final @NotNull String VISIBLE_FLAG = "WS_VISIBLE";
    private static final @NotNull String BIG_TEXT_FLAG = "RC_BIGTEXT";
    private static final @NotNull String NO_CONTENT = "";

    /**
     * Shows the Original or Expansion choice over the main menu; Cancel leaves the menu open.
     */
    static final class GameModePopup extends GluePopup {
        private static final int ORIGINAL = 6;
        private static final int TITLE_CONTROL = 2;
        private static final int CANCEL = 3;
        private static final int EXPANSION = 7;
        private static final @NotNull String GAME_MODE_DIALOG = "gluGameMode";
        private static final @NotNull String PANEL = "glue/palmm/Retail_Ex.DDS";
        private static final @NotNull String TITLE = "gluSELECTTYPE";

        private final @NotNull Consumer<Boolean> choice;

        GameModePopup(@NotNull Consumer<Boolean> choice) {
            super(GAME_MODE_DIALOG, PALETTE_FOLDER, PANEL);

            this.choice = choice;
        }

        /**
         * Called after the dialog is loaded: hide, disable or fill controls here.
         *
         * @param glueContext the shared services
         */
        @Override
        protected void onOpen(@NotNull GlueContext glueContext) {
            ScrControl title = this.bindControl(TITLE_CONTROL);

            title.setReplacedText(glueContext.getGluText().get(TITLE));
            title.setTextFormat(GlueCanvas.FORMAT_CENTER_ONE_LINE);
        }

        /**
         * Called when a control is activated by a click, its hotkey, Esc or Enter.
         *
         * @param control the control id
         */
        @Override
        protected void onActivate(int control) {
            switch (control) {
                case ORIGINAL -> {
                    this.finish();
                    this.choice.accept(false);
                }
                case EXPANSION -> {
                    this.finish();
                    this.choice.accept(true);
                }
                case CANCEL -> this.finish();
                default -> {
                }
            }
        }
    }


    /**
     * Creates the status panel's controls inside a slot: the title, its rule, and a label and a value control per row.
     *
     * @param slot the control whose rectangle the panel takes
     * @return the controls in drawing order
     */
    private static @NotNull List<DialogControl> createStatusControls(@NotNull DialogControl slot) {
        List<DialogControl> controls = new ArrayList<>();
        int left = slot.getX() + STATUS_MARGIN;
        int width = slot.getWidth() - STATUS_SIDES * STATUS_MARGIN;

        controls.add(ScrDialogControlUtils.createControl(ScrControlType.CTEXT, STATUS_TITLE,
                                                         left, slot.getY() + STATUS_TITLE_TOP, width, STATUS_TITLE_HEIGHT,
                                                         Set.of(VISIBLE_FLAG, BIG_TEXT_FLAG), NO_CONTENT, List.of()));
        controls.add(ScrDialogControlUtils.createControl(ScrControlType.RECT, STATUS_RULE,
                                                         left, slot.getY() + STATUS_RULE_TOP, width, STATUS_RULE_THICKNESS,
                                                         Set.of(VISIBLE_FLAG), NO_CONTENT, List.of()));

        for (int row = 0; row < STATUS_ROWS.size(); row++) {
            int top = slot.getY() + STATUS_BODY_TOP + row * (STATUS_ROW_HEIGHT + STATUS_ROW_GAP);

            controls.add(ScrDialogControlUtils.createControl(ScrControlType.LTEXT, FIRST_STATUS_LABEL + row, left, top, width, STATUS_ROW_HEIGHT,
                                                             Set.of(VISIBLE_FLAG), NO_CONTENT, List.of()));
            controls.add(ScrDialogControlUtils.createControl(ScrControlType.RTEXT, FIRST_STATUS_VALUE + row, left, top, width, STATUS_ROW_HEIGHT,
                                                             Set.of(VISIBLE_FLAG), NO_CONTENT, List.of()));
        }

        return controls;
    }

    /**
     * Tells how Beacon.Link stands from what the glue holds: the state of the open session of the multiplayer screens, else whether the run
     * offers Beacon.Link with an account.
     *
     * @param glueContext the shared services
     * @return the text of the state
     */
    private static @NotNull OwnText getBeaconLinkText(@NotNull GlueContext glueContext) {
        LanClient client = glueContext.getLanClient();
        BeaconLinkSession session = client == null ? null : client.getNetwork().getBeaconLinkSession();

        if (session == null) {
            return glueContext.getBeaconLink().isAvailable() ? OwnText.READY : OwnText.OFFLINE;
        }

        if (session.getFailure() != null) {
            return OwnText.OFFLINE;
        }

        for (LinkNode node : session.getNodes()) {
            if (node.getState() == LinkNodeState.REGISTERED) {
                return OwnText.ONLINE;
            }
        }

        return OwnText.CONNECTING;
    }

    private static @NotNull OwnText getUpdateText(@NotNull ReleaseState state) {
        return switch (state) {
            case CHECKING -> OwnText.CHECKING;
            case UP_TO_DATE -> OwnText.UP_TO_DATE;
            case UPDATE_AVAILABLE -> OwnText.UPDATE_AVAILABLE;
            case UNKNOWN -> OwnText.UNKNOWN;
        };
    }


    /**
     * The value controls of the status panel's rows, bound at open.
     */
    private @NotNull ScrControl @Nullable [] statusValues;

    /**
     * Creates a {@link MainMenuScreen} instance.
     */
    public MainMenuScreen() {
        super(DIALOG, PALETTE_FOLDER, getPaletteBackground(PALETTE_FOLDER));

        this.statusValues = null;
    }

    /**
     * Loads the screen's dialog: {@code rez\<dialog name>.ui.json}, or an empty dialog without a name. A screen whose
     * native content is not a control list builds its dialog here.
     * <p>
     * The main menu adds the status panel's controls right after the web panel of the top right frame; the frame's overlay is drawn over them.
     *
     * @param glueContext the shared services
     * @return the dialog
     * @throws IOException if the dialog cannot be read
     */
    @Override
    protected @NotNull DialogDefinition loadDialog(@NotNull GlueContext glueContext) throws IOException {
        DialogDefinition loaded = super.loadDialog(glueContext);
        List<DialogControl> controls = new ArrayList<>();

        for (DialogControl control : loaded.getControls()) {
            controls.add(control);

            if (control.getId() == STATUS_SLOT) {
                controls.addAll(createStatusControls(control));
            }
        }

        return new DialogDefinition(loaded.getName(), loaded.getX(), loaded.getY(), loaded.getWidth(), loaded.getHeight(), controls);
    }

    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     *
     * @param glueContext the shared services
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) {
        this.bindControl(SINGLE_PLAYER_UNAVAILABLE).setVisible(false);
        this.bindControl(LOCK).setVisible(false);
        this.bindControl(COLLECTIONS).setEnabled(false);

        boolean signedIn = glueContext.hasPlayer();

        // The player's name takes the place of the login button.
        this.bindControl(LOGIN).setVisible(!signedIn);
        this.bindControl(PLAYER_NAME).setReplacedText(signedIn ? glueContext.getTaggedPlayerName() : NO_PLAYER_NAME);

        for (int control : RUNTIME_TEXTS) {
            this.bindControl(control).setReplacedText("");
        }

        GlueCanvas canvas = glueContext.getCanvas();

        String buildLine = canvas.getStorage().getBuildVersion() + BUILD_LINE_SEPARATOR + canvas.getLocale() + BUILD_LINE_SEPARATOR + PLATFORM;

        this.bindControl(BUILD_LINE).setReplacedText(buildLine);

        if (this.findControl(STATUS_TITLE) == null) {
            return;
        }

        ScrControl title = this.bindControl(STATUS_TITLE);
        ScrControl[] labels = this.bindControls(FIRST_STATUS_LABEL, STATUS_ROWS.size());

        title.setReplacedText(STATUS_TITLE_TEXT);
        title.setHighlighted(true);

        for (int row = 0; row < STATUS_ROWS.size(); row++) {
            labels[row].setReplacedText(STATUS_ROWS.get(row).get(glueContext.getLocale()));
        }

        this.statusValues = this.bindControls(FIRST_STATUS_VALUE, STATUS_ROWS.size());
        this.showStatus();
    }

    /**
     * Called by {@link #update} for screen-specific timers.
     * <p>
     * The main menu shows the current release and Beacon.Link state in its status panel.
     *
     * @param time the time in milliseconds
     */
    @Override
    protected void onUpdate(long time) {
        this.showStatus();
    }

    /**
     * Shows the running version, the latest release, whether an update is available and how Beacon.Link stands; an available update is
     * highlighted.
     */
    private void showStatus() {
        ScrControl[] values = this.statusValues;

        if (values == null) {
            return;
        }

        GlueContext glueContext = this.getContext();
        String locale = glueContext.getLocale();
        ReleaseStatus release = glueContext.getReleaseStatus();
        ReleaseState state = release.getState();
        ReleaseVersion latest = release.getLatestVersion();
        OwnText update = getUpdateText(state);

        values[VERSION_ROW].setReplacedText(release.getRunningVersion().toString());
        values[LATEST_RELEASE_ROW].setReplacedText(latest != null ? latest.toString() : update.get(locale));
        values[UPDATE_ROW].setReplacedText(update.get(locale));
        values[UPDATE_ROW].setHighlighted(state == ReleaseState.UPDATE_AVAILABLE);
        values[BEACON_LINK_ROW].setReplacedText(getBeaconLinkText(glueContext).get(locale));
    }

    /**
     * Draws one control.
     * <p>
     * The status panel's rule is a one-pixel line in the panel rule color; other controls use the glue painter.
     *
     * @param canvas  the glue canvas
     * @param control the control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IOException           if an image cannot be read
     * @throws IllegalStateException if the screen is not open
     */
    @Override
    protected void drawControl(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) throws IOException {
        if (control.getId() == STATUS_RULE) {
            canvas.fillRectangle(left, top, control.getWidth(), control.getHeight(), PANEL_RULE_COLOR);

            return;
        }

        super.drawControl(canvas, control, left, top);
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
        switch (control) {
            case EXIT -> this.getContext().getNavigator().exit();
            case OPTIONS -> this.showPopup(new OptionsPopup(this.getPaletteFolder()));
            case VIEW_INTRO -> this.getContext().getNavigator().show(GlueNavigator.Destination.INTRO);
            case CREDITS -> this.getContext().getNavigator().show(GlueNavigator.Destination.CREDITS);
            case HOTKEYS -> this.getContext().getNavigator().show(GlueNavigator.Destination.HOTKEYS);
            case LOGIN -> this.getContext().getNavigator().show(GlueNavigator.Destination.LOGIN);
            case SINGLE_PLAYER -> this.chooseGameMode(false, GlueNavigator.Destination.CAMPAIGN);
            case MULTIPLAYER -> this.chooseGameMode(true, GlueNavigator.Destination.CONNECTION);
            default -> {
            }
        }
    }

    /**
     * Shows the Original or Expansion choice; a choice sets the game mode and shows the next destination, after the ID screen for a run
     * without a player.
     *
     * @param multiplayer whether the chosen game is a multiplayer game
     * @param next        the destination shown after the choice
     */
    private void chooseGameMode(boolean multiplayer, GlueNavigator.@NotNull Destination next) {
        this.showPopup(new GameModePopup(expansion -> {
            GlueContext glueContext = this.getContext();

            glueContext.setExpansion(expansion);
            glueContext.setMultiplayer(multiplayer);

            if (glueContext.hasPlayer()) {
                glueContext.getNavigator().show(next);
            } else {
                glueContext.getNavigator().replace(new LoginScreen(next));
            }
        }));
    }
}
