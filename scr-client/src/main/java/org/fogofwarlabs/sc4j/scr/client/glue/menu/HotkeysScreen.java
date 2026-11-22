package org.fogofwarlabs.sc4j.scr.client.glue.menu;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.client.glue.*;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.WebPanelStyleUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.input.HotkeyBindingsUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.input.HotkeyProfile;
import org.fogofwarlabs.sc4j.scr.client.ui.text.StatText;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.fogofwarlabs.sc4j.scr.render.ui.TrueTypeText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;

/**
 * Edits hotkey bindings by race, unit and command card with a rebind field and modal save/default confirmations.
 * <p>
 * Save activates the device profile and stores normalized bindings. Cancel keeps settings unchanged; unsaved changes prompt for saving when no
 * binding errors remain.
 */
public final class HotkeysScreen extends GlueScreen {
    private static final @NotNull String DIALOG = "gluHotkeys";
    /**
     * The screen keeps the palette of the main menu it is opened from.
     */
    private static final @NotNull String PALETTE_FOLDER = "glue/palmm";
    private static final @NotNull String HOTKEYS_KEY = "input.hotkeys";
    private static final @NotNull String HOTKEY_PROFILE_KEY = "input.hotkeyProfile";
    private static final @NotNull String PREFIX = "bnet119_hotkeysPanel_";
    private static final @NotNull String HEADER_KEY = PREFIX + "header";
    private static final @NotNull String UNITS_LABEL_KEY = PREFIX + "units_label";
    private static final @NotNull String BUILDINGS_LABEL_KEY = PREFIX + "buildings_label";
    private static final @NotNull String UNIT_SELECTION_INSTRUCTIONS_KEY = PREFIX + "unitSelection_instructions";
    private static final @NotNull String SELECTED_PREFIX_KEY = PREFIX + "selected_prefix";
    private static final @NotNull String COMMAND_SELECTION_INSTRUCTIONS_KEY = PREFIX + "commandSelection_instructions";
    private static final @NotNull String UNBOUND_INSTRUCTIONS_KEY = PREFIX + "unboundedInstructions";
    private static final @NotNull String HOTKEY_INSTRUCTIONS_KEY = PREFIX + "hotkey_instructions";
    private static final @NotNull String UNITS_SHARED_KEY = PREFIX + "binding_unitsShared";
    private static final @NotNull String DEFAULT_BINDING_KEY = PREFIX + "binding_default";
    private static final @NotNull String RESTORE_BUTTON_KEY = PREFIX + "restoreToDefault_button";
    private static final @NotNull String SAVE_BUTTON_KEY = PREFIX + "save_button";
    private static final @NotNull String CANCEL_BUTTON_KEY = PREFIX + "cancel_button";
    private static final @NotNull String CONTINUE_BUTTON_KEY = PREFIX + "continue_button";
    private static final @NotNull String DONT_SAVE_BUTTON_KEY = PREFIX + "dontSave_button";
    private static final @NotNull String SAVE_CHANGES_QUESTION_KEY = PREFIX + "saveChangesQuestion";
    private static final @NotNull String SAVE_CHANGES_EXPLANATION_KEY = PREFIX + "saveChangesExplanation";
    private static final @NotNull String RESTORE_DEFAULTS_QUESTION_KEY = PREFIX + "restoreDefaultsQuestion";
    private static final @NotNull String RESTORE_DEFAULTS_EXPLANATION_KEY = PREFIX + "restoreDefaultsExplanation";
    private static final @NotNull String UNBOUND_IMAGE = "unbound.png";
    private static final @NotNull String UNITS_SHARED_PLACEHOLDER = "%d";
    /**
     * The whitespace runs of an action name and the space each collapses to.
     */
    private static final @NotNull String WHITESPACE_RUN = "\\s+";
    private static final @NotNull String SPACE = " ";
    private static final @NotNull String SCREEN_NOT_OPEN_MESSAGE = "The hotkey screen is not open.";

    private static final int CENTER_DIVISOR = 2;
    private static final int SIDE_COUNT = 2;
    /**
     * The thickness of the page's {@code 1} px borders and rules.
     */
    private static final int BORDER_THICKNESS = 1;
    private static final int FRAME_INSET = 2;
    private static final int FRAME_SPAN = SIDE_COUNT * FRAME_INSET;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int LIGHT_GRAY = 0xDDDDDDFF;
    private static final int DEFAULT_KEY_GRAY = 0xAAAAAAFF;
    private static final int ERROR_RED = 0xD60E00FF;
    private static final int PURE_RED = 0xFF0000FF;
    private static final int CELL_RING = 0x333333FF;
    private static final int EMPTY_CELL = 0x232323FF;
    private static final int SECTION_RULE = 0x1E1E1EFF;
    private static final int REBIND_FILL = 0x1C1C1CFF;
    private static final int REBIND_INNER_BORDER = 0x000000FF;
    private static final int KEY_FIELD_FILL = 0x232323FF;
    private static final int KEY_FIELD_BORDER = 0x808080FF;
    private static final int KEY_LABEL_FILL = 0x00000099;
    private static final int ERROR_TINT = 0xFF000033;
    private static final int ACTION_TINT = 0xFFFF00FF;
    private static final int MODAL_DIM = 0x00000099;
    /**
     * The fill color of an inner frame: none.
     */
    private static final int NO_FILL = 0;

    /**
     * {@code .hotkeyWindowContainer}: {@code padding: 14px 12px 0}.
     */
    private static final float CONTAINER_LEFT = 12;
    private static final float CONTAINER_TOP = 14;
    private static final float CONTAINER_WIDTH = 616;
    /**
     * {@code .hotkeyPanelHeader}: {@code 14}/{@code 16} px, {@code padding: 10px 35px 2px}, {@code margin-bottom: -4px}.
     */
    private static final float HEADER_FONT_SIZE = 14;
    private static final float HEADER_LINE = 16;
    private static final int HEADER_FONT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY, HEADER_FONT_SIZE, HEADER_LINE);
    private static final float HEADER_PADDING_TOP = 10;
    private static final float HEADER_PADDING_SIDE = 35;
    private static final float HEADER_HEIGHT = 29;
    private static final float HEADER_OVERLAP = 4;
    /**
     * {@code .raceSelectionTabSection .tab}: {@code 9} px, {@code padding: 5px}, {@code min-width: 75px}, {@code 1} px before, {@code 5} px after.
     */
    private static final float TAB_FONT_SIZE = 9;
    private static final int TAB_FONT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY, TAB_FONT_SIZE,
                                                                       WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    private static final float TABS_GAP = 10;
    private static final float TAB_MARGIN_LEFT = 1;
    private static final float TAB_MARGIN_RIGHT = 5;
    private static final float TAB_PADDING = 5;
    private static final float TAB_MINIMUM_WIDTH = 75;
    private static final float TAB_SELECTED_DROP = 3;
    private static final float TAB_RADIUS = 3;
    private static final float ERROR_MARK_SIZE = 10;
    private static final float ERROR_ICON_SIZE = 8;
    private static final float TAB_ERROR_INSET = 4;
    private static final float UNIT_ERROR_INSET = 2;
    /**
     * {@code .hotkeyOuterPanel}: {@code 376} high inside a {@code 1} px padding and border, {@code 4} px corners; the inner
     * panel's {@code 2} px ones.
     */
    private static final float PANEL_TOP = CONTAINER_TOP + HEADER_HEIGHT - HEADER_OVERLAP;
    private static final float PANEL_HEIGHT = 380;
    private static final float PANEL_RADIUS = 4;
    private static final float INNER_RADIUS = 2;
    private static final float CONTENT_INSET = 3;
    /**
     * The two sections of the inner panel: {@code .unitsSection} {@code 39} % with {@code padding-left: 20px} and {@code .actionsSection}
     * {@code 61} % with a {@code 1} px left border, shrunk together to the {@code 610} px row by their flex basis.
     */
    private static final float UNITS_PADDING_LEFT = 20;
    private static final float UNITS_WIDTH = 229.7f;
    private static final float UNITS_HEADER_TOP = 18;
    private static final float BUILDINGS_HEADER_TOP = 10;
    private static final float SECTION_HEADER_FONT_SIZE = 11;
    private static final float SECTION_HEADER_LINE = 13;
    private static final int SECTION_HEADER_FONT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY,
                                                                                  SECTION_HEADER_FONT_SIZE, SECTION_HEADER_LINE);
    private static final float GROUP_TOP = 10;
    private static final float GROUP_TITLE_FONT_SIZE = 9;
    private static final float GROUP_TITLE_LINE = 11;
    private static final int GROUP_TITLE_FONT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY, GROUP_TITLE_FONT_SIZE,
                                                                               GROUP_TITLE_LINE);
    private static final float GROUP_TITLE_BOTTOM = 9;
    /**
     * A unit or action cell: {@code 32x32} with {@code 3} px to the right and below.
     */
    private static final float CELL_SIZE = 32;
    private static final float CELL_PITCH = 35;
    /**
     * {@code .actionCardsPanel}: {@code padding: 18px 0 0 16px}; a card is {@code 33} % wide with {@code 10} px above.
     */
    private static final float CARDS_TOP = 18;
    private static final float CARDS_LEFT = 16;
    private static final float CARD_WIDTH_FRACTION = 0.33f;
    private static final float CARD_TOP = 10;
    private static final int CARDS_PER_ROW = 3;
    private static final float HEADING_FONT_SIZE = 11;
    private static final int HEADING_FONT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY,
                                                                           HEADING_FONT_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    private static final float HEADING_NAME_GAP = 5;
    private static final float CARD_TITLE_FONT_SIZE = 9;
    private static final int CARD_TITLE_FONT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY,
                                                                              CARD_TITLE_FONT_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    private static final float CARD_TITLE_BOTTOM = 8;
    private static final float INSTRUCTIONS_FONT_SIZE = 9;
    private static final int INSTRUCTIONS_FONT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY,
                                                                                INSTRUCTIONS_FONT_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    private static final float INSTRUCTIONS_PADDING = 5;
    /**
     * {@code .actionIconKeyLabel}: {@code 7} px, at least {@code 10x10}, {@code 1} px in from the cell's corner.
     */
    private static final float KEY_LABEL_FONT_SIZE = 7;
    private static final int KEY_LABEL_FONT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY,
                                                                             KEY_LABEL_FONT_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    private static final float KEY_LABEL_SIZE = 10;
    private static final int KEY_LABEL_INSET = 1;
    /**
     * The rebind strip: the instruction line ({@code 8} px, {@code 3} px below), then {@code padding: 13px 0 13px 15px} inside
     * {@code 2} px of frames.
     */
    private static final float REBIND_INSTRUCTION_FONT_SIZE = 8;
    private static final int REBIND_INSTRUCTION_FONT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY, REBIND_INSTRUCTION_FONT_SIZE,
                                                                                      WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    private static final float REBIND_INSTRUCTION_BOTTOM = 3;
    private static final float REBIND_PADDING_TOP = 13;
    private static final float REBIND_PADDING_LEFT = 15;
    private static final float REBIND_TEXT_MARGIN = 6;
    private static final float ACTION_TITLE_FONT_SIZE = 9;
    private static final int ACTION_TITLE_FONT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY,
                                                                                ACTION_TITLE_FONT_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    private static final float ACTION_COUNT_FONT_SIZE = 9;
    private static final int ACTION_COUNT_FONT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY,
                                                                                ACTION_COUNT_FONT_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    private static final float DEFAULT_KEY_TOP = 2;
    private static final float DEFAULT_KEY_GAP = 3;
    /**
     * The key field: {@code 50} wide, {@code 25} px from the strip's right, {@code 11} px text, {@code padding: 3px}, {@code 3} px corners.
     */
    private static final float KEY_FIELD_FONT_SIZE = 11;
    private static final int KEY_FIELD_FONT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY,
                                                                             KEY_FIELD_FONT_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    private static final float KEY_FIELD_WIDTH = 50;
    private static final float KEY_FIELD_RIGHT = 25;
    private static final float KEY_FIELD_PADDING = 3;
    private static final float KEY_FIELD_RADIUS = 3;
    /**
     * {@code .bottomHolder}: {@code 15} px under the panel.
     */
    private static final float FOOTER_GAP = 15;
    /**
     * {@code .dialogBox}: {@code 48} % of the page wide, at least {@code 105} high, {@code padding: 25px 24px 24px 29px}.
     */
    private static final float DIALOG_CONTENT_WIDTH = 307.2f;
    private static final float DIALOG_MINIMUM_HEIGHT = 105;
    private static final float DIALOG_PADDING_TOP = 25;
    private static final float DIALOG_PADDING_RIGHT = 24;
    private static final float DIALOG_PADDING_BOTTOM = 24;
    private static final float DIALOG_PADDING_LEFT = 29;
    private static final float DIALOG_TITLE_FONT_SIZE = 12;
    private static final int DIALOG_TITLE_FONT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY,
                                                                                DIALOG_TITLE_FONT_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    private static final float DIALOG_BODY_FONT_SIZE = 9;
    private static final int DIALOG_BODY_FONT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_REGULAR_FAMILY,
                                                                               DIALOG_BODY_FONT_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    private static final float DIALOG_BODY_TOP = 5;
    private static final float DIALOG_BUTTON_GAP = 10;
    /**
     * {@code .cancelButton}: an {@code 8} px white {@code X} {@code 10} px outside the box's top right corner.
     */
    private static final float DIALOG_CLOSE_FONT_SIZE = 8;
    private static final int DIALOG_CLOSE_FONT = WebPanelStyleUtils.getFontSlot(TrueTypeText.WEB_EXTENDED_FAMILY,
                                                                                DIALOG_CLOSE_FONT_SIZE, WebPanelStyleUtils.NORMAL_LINE_HEIGHT);
    private static final @NotNull String DIALOG_CLOSE = "X";
    private static final float DIALOG_CLOSE_OFFSET = 10;

    /**
     * What a region of the page does when it is clicked.
     */
    private enum Kind {
        /**
         * Selects a race.
         */
        RACE,
        /**
         * Selects a unit.
         */
        UNIT,
        /**
         * Selects an action.
         */
        ACTION,
        /**
         * Opens default restoration.
         */
        RESTORE,
        /**
         * Saves bindings.
         */
        SAVE,
        /**
         * Cancels editing.
         */
        CANCEL,
        /**
         * Activates the left modal button.
         */
        DIALOG_LEFT,
        /**
         * Activates the right modal button.
         */
        DIALOG_RIGHT,
        /**
         * Closes the modal dialog.
         */
        DIALOG_CLOSE
    }

    /**
     * The page's two modal dialogs.
     */
    private enum PageDialog {
        /**
         * No modal dialog is open.
         */
        NONE,
        /**
         * Asks whether unsaved changes should be saved.
         */
        SAVE_CHANGES,
        /**
         * Asks whether defaults should replace bindings.
         */
        RESTORE_DEFAULTS
    }

    /**
     * A clickable region of the page as last drawn.
     */
    private static final class Region {
        private final @NotNull Kind kind;
        private final @Nullable Object value;
        private final float left;
        private final float top;
        private final float width;
        private final float height;
        private final boolean enabled;

        private Region(@NotNull Kind kind, @Nullable Object value, float left, float top, float width, float height, boolean enabled) {
            this.kind    = kind;
            this.value   = value;
            this.left    = left;
            this.top     = top;
            this.width   = width;
            this.height  = height;
            this.enabled = enabled;
        }

        private boolean hasSameTargetAs(@Nullable Region other) {
            return other != null && other.kind == this.kind && other.value == this.value;
        }
    }

    /**
     * Formats a valid key code point as an uppercase character using the root locale.
     *
     * @param key the key code point, {@link HotkeyEditor#NONE} or {@link HotkeyEditor#CLEARED}
     * @return the key text, or an empty string for an unbound or invalid key
     */
    private static @NotNull String formatKeyText(int key) {
        if (key <= HotkeyEditor.NONE || !Character.isValidCodePoint(key)) {
            return "";
        }

        return new String(Character.toChars(key)).toUpperCase(Locale.ROOT);
    }

    /**
     * @param key the glue key
     * @return {@code true} if a glue key is an editing or navigation key, which the page's key field does not receive as a
     * character, {@code false} otherwise
     */
    private static boolean isEditingKey(char key) {
        return key == KEY_ENTER || key == KEY_BACKSPACE || key == KEY_DELETE || key >= KEY_LEFT && key <= KEY_DOWN;
    }

    private final @NotNull List<Region> regions;
    private final @NotNull Map<Integer, String> actionNames;
    private final @NotNull Map<Integer, Integer> deviceKeys;

    private @Nullable HotkeyEditor editor;
    private @Nullable StatText statText;

    private HotkeyPanelModel.@NotNull Race race;
    private HotkeyPanelModel.@Nullable Unit unit;
    private HotkeyPanelModel.@Nullable Action action;
    private @NotNull PageDialog pageDialog;
    private @Nullable Region hovered;
    private @Nullable Region pressed;
    private boolean ended;

    /**
     * Creates a {@link HotkeysScreen} instance.
     */
    public HotkeysScreen() {
        super(DIALOG, PALETTE_FOLDER, null);

        this.regions     = new ArrayList<>();
        this.actionNames = new HashMap<>();
        this.deviceKeys  = new HashMap<>();

        this.editor   = null;
        this.statText = null;

        this.race       = HotkeyPanelModel.Race.TERRAN;
        this.unit       = null;
        this.action     = null;
        this.pageDialog = PageDialog.NONE;
        this.hovered    = null;
        this.pressed    = null;
        this.ended      = false;
    }


    private @NotNull HotkeyEditor requireEditor() {
        HotkeyEditor current = this.editor;

        if (current == null) {
            throw Exceptions.newIllegalStateException(SCREEN_NOT_OPEN_MESSAGE);
        }

        return current;
    }

    private @NotNull StatText requireStatText() {
        StatText current = this.statText;

        if (current == null) {
            throw Exceptions.newIllegalStateException(SCREEN_NOT_OPEN_MESSAGE);
        }

        return current;
    }


    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * The screen loads the device and default bindings and the action names the editor shows.
     *
     * @param glueContext the shared services
     * @throws IOException if an asset cannot be read
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) throws IOException {
        GlueCanvas canvas = glueContext.getCanvas();
        StatText strings = StatText.load(canvas.getStorage(), canvas.getLocale());
        Object stored = glueContext.getSettings().getOption(HOTKEYS_KEY);
        Map<Integer, Integer> overrides = stored instanceof String bindingsText ? HotkeyBindingsUtils.parse(bindingsText) : Map.of();
        HotkeyProfile device = new HotkeyProfile(strings, HotkeyProfile.DEVICE, overrides);
        HotkeyProfile defaults = new HotkeyProfile(strings, HotkeyProfile.DEFAULT, Map.of());
        Map<Integer, Integer> pageDevice = new HashMap<>();
        Map<Integer, Integer> pageDefaults = new HashMap<>();

        for (int index = 0; index < HotkeyBindingsUtils.COUNT; index++) {
            int identifier = HotkeyBindingsUtils.getIdentifier(index);

            this.deviceKeys.put(identifier, device.getKey(identifier));
        }

        for (int identifier : HotkeyPanelModel.getActions().keySet()) {
            pageDevice.put(identifier, device.getKey(identifier));
            pageDefaults.put(identifier, defaults.getKey(identifier));
            // The page shows the name in a block, where its line breaks and tabs collapse to spaces.
            this.actionNames.put(identifier, defaults.getNameText(identifier).replaceAll(WHITESPACE_RUN, SPACE));
        }

        this.statText = strings;
        this.editor   = new HotkeyEditor(pageDevice, pageDefaults);
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     * <p>
     * The page has no dialog controls to activate; its regions take the pointer releases.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
    }

    /**
     * Called first for every key of the screen (after an open popup): a screen without controls, such as a cinematic, takes its keys here.
     * <p>
     * Escape closes the open page dialog or leaves the page, and a character binds the selected action while no page dialog is open. The page
     * consumes every key.
     *
     * @param key the key
     * @return {@code true} if the key was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onKey(char key) {
        if (this.ended || this.editor == null) {
            return true;
        }

        HotkeyEditor bindings = this.editor;

        if (key == KEY_ESCAPE) {
            this.getContext().getAudio().play(GlueAudio.Sound.MOUSE_DOWN);

            if (this.pageDialog != PageDialog.NONE) {
                this.pageDialog = PageDialog.NONE;
            } else {
                this.cancelEditing(bindings);
            }

            return true;
        }

        if (isEditingKey(key) || this.pageDialog != PageDialog.NONE || this.unit == null || this.action == null) {
            return true;
        }

        int bound = HotkeyEditor.acceptKey(key);

        if (bound != HotkeyEditor.NONE) {
            bindings.rebind(this.unit, this.action, bound);
        }

        return true;
    }

    /**
     * Called for every pointer move of the screen (after an open combo box or popup takes it).
     * <p>
     * Moving onto another enabled region plays the hover sound.
     *
     * @param x the interface x
     * @param y the interface y
     */
    @Override
    protected void onPointerMoved(float x, float y) {
        Region region = this.findRegion(x, y);

        if (region != null && !region.hasSameTargetAs(this.hovered) && region.enabled && region.kind != Kind.DIALOG_CLOSE) {
            this.playMouseOver();
        }

        this.hovered = region;
    }

    /**
     * Called first for every press of the screen that no open popup or open combo box takes, while the screen takes input.
     * <p>
     * The page records the pressed region, plays the button sound for an enabled button and consumes every press.
     *
     * @param x the interface x
     * @param y the interface y
     * @return {@code true} if the press was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onPointerPressed(float x, float y) {
        Region region = this.findRegion(x, y);

        this.pressed = region;

        if (region != null && region.enabled &&
            (region.kind == Kind.RESTORE || region.kind == Kind.SAVE || region.kind == Kind.CANCEL ||
             region.kind == Kind.DIALOG_LEFT || region.kind == Kind.DIALOG_RIGHT)) {
            this.getContext().getAudio().play(GlueAudio.Sound.MOUSE_DOWN);
        }

        return true;
    }

    /**
     * Called for every release of the left button of the screen.
     * <p>
     * A release over the enabled region that was pressed clicks it.
     *
     * @param x the interface x
     * @param y the interface y
     */
    @Override
    protected void onPointerReleased(float x, float y) {
        Region region = this.findRegion(x, y);
        Region pressedRegion = this.pressed;

        this.pressed = null;

        if (region == null || !region.hasSameTargetAs(pressedRegion) || !region.enabled || this.ended) {
            return;
        }

        this.click(region);
    }

    private @Nullable Region findRegion(float x, float y) {
        for (int index = this.regions.size() - 1; index >= 0; index--) {
            Region region = this.regions.get(index);

            if (GlueCanvas.contains(x, y, region.left, region.top, region.width, region.height)) {
                return region;
            }
        }

        return null;
    }

    private void click(@NotNull Region region) {
        HotkeyEditor bindings = this.requireEditor();

        switch (region.kind) {
            case RACE -> {
                this.getContext().getAudio().play(GlueAudio.Sound.MOUSE_DOWN);

                if (region.value instanceof HotkeyPanelModel.Race chosen) {
                    this.race = chosen;
                }

                this.unit   = null;
                this.action = null;
            }
            case UNIT -> {
                if (!(region.value instanceof HotkeyPanelModel.Unit chosen)) {
                    return;
                }

                // Play the button sound for buildings; unit ready sounds are not played here.
                if (chosen.getSound() == null) {
                    this.getContext().getAudio().play(GlueAudio.Sound.MOUSE_DOWN);
                }

                this.unit   = chosen;
                this.action = null;
            }
            case ACTION -> {
                if (region.value instanceof HotkeyPanelModel.Action chosen) {
                    this.action = chosen;
                }

                this.getContext().getAudio().play(GlueAudio.Sound.MOUSE_DOWN);
            }
            case RESTORE -> this.pageDialog = PageDialog.RESTORE_DEFAULTS;
            case SAVE -> this.save();
            case CANCEL -> this.cancelEditing(bindings);
            case DIALOG_LEFT -> {
                if (this.pageDialog == PageDialog.SAVE_CHANGES) {
                    this.save();
                } else {
                    bindings.restoreDefaults();

                    this.pageDialog = PageDialog.NONE;
                }
            }
            case DIALOG_RIGHT -> {
                if (this.pageDialog == PageDialog.SAVE_CHANGES) {
                    this.end();
                } else {
                    this.pageDialog = PageDialog.NONE;
                }
            }
            case DIALOG_CLOSE -> {
                // The save dialog's close plays the button sound; the restore dialog's close does not.
                if (this.pageDialog == PageDialog.SAVE_CHANGES) {
                    this.getContext().getAudio().play(GlueAudio.Sound.MOUSE_DOWN);
                }

                this.pageDialog = PageDialog.NONE;
            }
        }
    }

    /**
     * Asks whether unsaved changes should be saved when no binding error remains, or else returns to the main menu.
     *
     * @param bindings the editor of the page
     */
    private void cancelEditing(@NotNull HotkeyEditor bindings) {
        if (!bindings.hasErrors() && bindings.hasUnsavedChanges()) {
            this.pageDialog = PageDialog.SAVE_CHANGES;
        } else {
            this.end();
        }
    }

    /**
     * Stores normalized bindings in the device profile and returns to the main menu.
     *
     * @throws IllegalStateException if the screen is not open
     */
    private void save() {
        Map<Integer, Integer> keys = new HashMap<>(this.deviceKeys);

        for (Map.Entry<Integer, Integer> binding : this.requireEditor().getBindings().entrySet()) {
            keys.put(binding.getKey(), HotkeyBindingsUtils.normalize(binding.getValue()));
        }

        GlueSettings settings = this.getContext().getSettings();

        settings.setOption(HOTKEY_PROFILE_KEY, HotkeyProfile.DEVICE);
        settings.setOption(HOTKEYS_KEY, HotkeyBindingsUtils.format(keys));
        this.end();
    }

    private void end() {
        if (this.ended) {
            return;
        }

        this.ended      = true;
        this.pageDialog = PageDialog.NONE;

        this.getContext().getNavigator().show(GlueNavigator.Destination.MAIN_MENU);
    }

    /**
     * Draws the screen and its popup.
     * <p>
     * The page draws over an opaque black fill, and its panels, regions and open page dialog follow the screen.
     *
     * @param canvas the glue canvas
     * @throws IOException if an image cannot be read
     */
    @Override
    public void draw(@NotNull GlueCanvas canvas) throws IOException {
        canvas.fillRectangle(0, 0, canvas.getInterfaceWidth(), GlueCanvas.INTERFACE_HEIGHT, GlueCanvas.OPAQUE_BLACK);
        super.draw(canvas);
        this.regions.clear();

        if (this.editor == null || this.statText == null) {
            return;
        }

        float left = this.getDialogLeft();
        float top = this.getDialogTop();

        this.drawPanel(canvas, left, top);
        this.drawHeader(canvas, left, top);
        this.drawUnits(canvas, left, top);
        this.drawActions(canvas, left, top);
        this.drawFooter(canvas, left, top);

        if (this.pageDialog != PageDialog.NONE) {
            this.regions.clear();
            this.drawPageDialog(canvas, left, top);
        }
    }

    /**
     * Draws the outer and inner hotkey panel frames.
     *
     * @param canvas the glue canvas
     * @param left   the dialog's left edge in interface pixels
     * @param top    the dialog's top edge in interface pixels
     */
    private void drawPanel(@NotNull GlueCanvas canvas, float left, float top) {
        float x = left + CONTAINER_LEFT;
        float y = top + PANEL_TOP;

        WebPanelStyleUtils.drawBox(canvas,
                                   x, y, CONTAINER_WIDTH, PANEL_HEIGHT,
                                   PANEL_RADIUS,
                                   WebPanelStyleUtils.PANEL_BORDER, GlueCanvas.OPAQUE_BLACK,
                                   true, true,
                                   true, true);
        WebPanelStyleUtils.drawBox(canvas,
                                   x + FRAME_INSET, y + FRAME_INSET, CONTAINER_WIDTH - FRAME_SPAN, PANEL_HEIGHT - FRAME_SPAN,
                                   INNER_RADIUS,
                                   WebPanelStyleUtils.BORDER, NO_FILL,
                                   true, true,
                                   true, true);
    }

    /**
     * Draws the header tab and the race tabs over the panel's top edge.
     *
     * @param canvas the glue canvas
     * @param left   the dialog's left edge in interface pixels
     * @param top    the dialog's top edge in interface pixels
     * @throws IOException           if an icon cannot be read
     * @throws IllegalStateException if the screen is not open
     */
    private void drawHeader(@NotNull GlueCanvas canvas, float left, float top) throws IOException {
        String header = this.getContext().getGluText().get(HEADER_KEY);
        float headerWidth = canvas.measure(HEADER_FONT, header) + SIDE_COUNT * (HEADER_PADDING_SIDE + BORDER_THICKNESS);
        float x = left + CONTAINER_LEFT;
        float y = top + CONTAINER_TOP;

        this.drawTab(canvas, x, y, headerWidth, HEADER_HEIGHT, 0);
        WebPanelStyleUtils.drawText(canvas,
                                    HEADER_FONT,
                                    header,
                                    x, y + BORDER_THICKNESS + HEADER_PADDING_TOP,
                                    headerWidth, HEADER_LINE,
                                    InterfaceText.CENTER | InterfaceText.TOP,
                                    WHITE);

        float tabLeft = x + headerWidth + TABS_GAP;
        float tabHeight = HEADER_HEIGHT - HEADER_OVERLAP;
        float lineHeight = canvas.getLineHeight(TAB_FONT);

        for (HotkeyPanelModel.Race each : HotkeyPanelModel.Race.values()) {
            String labelKey = each.getLabel();
            String label = this.getContext().getGluText().get(labelKey);
            float width = Math.max(TAB_MINIMUM_WIDTH, canvas.measure(TAB_FONT, label)) + SIDE_COUNT * (TAB_PADDING + BORDER_THICKNESS);
            boolean selected = each == this.race;
            Region region = new Region(Kind.RACE, each, tabLeft + TAB_MARGIN_LEFT, y, width, tabHeight, true);

            this.drawTab(canvas, region.left, y, width, selected ? tabHeight + TAB_SELECTED_DROP : tabHeight, TAB_RADIUS);

            int color = selected || region.hasSameTargetAs(this.hovered) ? WebPanelStyleUtils.HIGHLIGHT_TEXT : WebPanelStyleUtils.BUTTON_TEXT;

            WebPanelStyleUtils.drawText(canvas,
                                        TAB_FONT,
                                        label,
                                        region.left, y + (tabHeight - lineHeight) / CENTER_DIVISOR,
                                        width, lineHeight,
                                        InterfaceText.CENTER | InterfaceText.TOP,
                                        color);

            if (this.requireEditor().hasError(each)) {
                this.drawErrorMark(canvas, region.left + TAB_ERROR_INSET, y + TAB_ERROR_INSET);
            }

            this.regions.add(region);
            tabLeft += TAB_MARGIN_LEFT + width + TAB_MARGIN_RIGHT;
        }
    }

    /**
     * Draws a tab frame without its bottom edge.
     *
     * @param canvas the glue canvas
     * @param x      the left edge of the tab
     * @param y      the top edge of the tab
     * @param width  the width of the tab
     * @param height the height of the tab
     * @param radius the radius of the top corners, {@code 0} for square corners
     */
    private void drawTab(@NotNull GlueCanvas canvas, float x, float y, float width, float height, float radius) {
        WebPanelStyleUtils.drawBox(canvas,
                                   x, y, width, height,
                                   radius,
                                   WebPanelStyleUtils.PANEL_BORDER, GlueCanvas.OPAQUE_BLACK,
                                   radius > 0, false,
                                   true, false);
        WebPanelStyleUtils.drawBox(canvas,
                                   x + FRAME_INSET, y + FRAME_INSET, width - FRAME_SPAN, height - FRAME_INSET,
                                   radius,
                                   WebPanelStyleUtils.BORDER, NO_FILL,
                                   radius > 0, false,
                                   true, false);
    }

    /**
     * Draws the unbound indicator over its dark backing.
     *
     * @param canvas the glue canvas
     * @param x      the left edge of the indicator
     * @param y      the top edge of the indicator
     * @throws IOException if the indicator image cannot be read
     */
    private void drawErrorMark(@NotNull GlueCanvas canvas, float x, float y) throws IOException {
        canvas.fillRectangle(x, y, ERROR_MARK_SIZE, ERROR_MARK_SIZE, KEY_LABEL_FILL);
        canvas.drawImage(canvas.getWebImage(UNBOUND_IMAGE), x, y, ERROR_ICON_SIZE, ERROR_ICON_SIZE);
    }

    /**
     * Draws ground and air units followed by basic and advanced buildings.
     *
     * @param canvas the glue canvas
     * @param left   the dialog's left edge in interface pixels
     * @param top    the dialog's top edge in interface pixels
     * @throws IOException if an icon cannot be read
     */
    private void drawUnits(@NotNull GlueCanvas canvas, float left, float top) throws IOException {
        float x = left + CONTAINER_LEFT + CONTENT_INSET + UNITS_PADDING_LEFT;
        float y = top + PANEL_TOP + CONTENT_INSET + UNITS_HEADER_TOP;

        WebPanelStyleUtils.drawText(canvas,
                                    SECTION_HEADER_FONT,
                                    this.getContext().getGluText().get(UNITS_LABEL_KEY),
                                    x, y,
                                    UNITS_WIDTH, SECTION_HEADER_LINE,
                                    InterfaceText.LEFT | InterfaceText.TOP,
                                    WHITE);
        y += SECTION_HEADER_LINE + GROUP_TOP;
        y += this.drawGroupPair(canvas, x, y, HotkeyPanelModel.Group.GROUND, HotkeyPanelModel.Group.AIR) + BUILDINGS_HEADER_TOP;
        WebPanelStyleUtils.drawText(canvas,
                                    SECTION_HEADER_FONT,
                                    this.getContext().getGluText().get(BUILDINGS_LABEL_KEY),
                                    x, y,
                                    UNITS_WIDTH, SECTION_HEADER_LINE,
                                    InterfaceText.LEFT | InterfaceText.TOP,
                                    WHITE);
        y += SECTION_HEADER_LINE + GROUP_TOP;
        this.drawGroupPair(canvas, x, y, HotkeyPanelModel.Group.BASIC_BUILDING, HotkeyPanelModel.Group.ADVANCED_BUILDING);
    }

    /**
     * Draws two unit groups side by side.
     *
     * @param canvas the glue canvas
     * @param x      the left of the first group
     * @param y      the top of the groups
     * @param first  the group on the left
     * @param second the group on the right
     * @return the height of the taller group
     * @throws IOException if an icon cannot be read
     */
    private float drawGroupPair(@NotNull GlueCanvas canvas,
                                float x, float y,
                                HotkeyPanelModel.@NotNull Group first, HotkeyPanelModel.@NotNull Group second) throws IOException {
        float half = UNITS_WIDTH / CENTER_DIVISOR;

        return Math.max(this.drawGroup(canvas, x, y, half, first), this.drawGroup(canvas, x + half, y, half, second));
    }

    private float drawGroup(@NotNull GlueCanvas canvas, float x, float y, float width, HotkeyPanelModel.@NotNull Group group) throws IOException {
        String groupLabelKey = group.getLabel();

        WebPanelStyleUtils.drawText(canvas,
                                    GROUP_TITLE_FONT,
                                    this.getContext().getGluText().get(groupLabelKey),
                                    x, y,
                                    width, GROUP_TITLE_LINE,
                                    InterfaceText.LEFT | InterfaceText.TOP,
                                    WebPanelStyleUtils.TEXT);

        float cellsTop = y + GROUP_TITLE_LINE + GROUP_TITLE_BOTTOM;
        int perRow = Math.max(1, (int) ((width - CELL_SIZE) / CELL_PITCH) + 1);
        int count = 0;

        for (HotkeyPanelModel.Unit each : HotkeyPanelModel.getUnits()) {
            if (each.getRace() != this.race || each.getGroup() != group) {
                continue;
            }

            float cellLeft = x + count % perRow * CELL_PITCH;
            float cellTop = cellsTop + count / perRow * CELL_PITCH;
            Region region = new Region(Kind.UNIT, each, cellLeft, cellTop, CELL_SIZE, CELL_SIZE, true);

            canvas.fillRectangle(cellLeft, cellTop, CELL_SIZE, CELL_SIZE, GlueCanvas.OPAQUE_BLACK);
            this.drawIcon(canvas, canvas.getWebImage(each.getIcon()), cellLeft, cellTop);

            int ring = each == this.unit ? WebPanelStyleUtils.HIGHLIGHT_TEXT
                                         : region.hasSameTargetAs(this.hovered) ? WebPanelStyleUtils.BUTTON_TEXT : CELL_RING;

            canvas.drawRectangle(cellLeft, cellTop, CELL_SIZE, CELL_SIZE, GlueCanvas.OUTLINE_THICKNESS, ring);

            if (this.requireEditor().hasError(each)) {
                this.drawErrorMark(canvas, cellLeft + UNIT_ERROR_INSET, cellTop + UNIT_ERROR_INSET);
            }

            this.regions.add(region);
            count++;
        }

        int rows = (count + perRow - 1) / perRow;

        return GROUP_TITLE_LINE + GROUP_TITLE_BOTTOM + rows * CELL_PITCH;
    }

    /**
     * Fits and centers an icon inside its cell.
     *
     * @param canvas the glue canvas
     * @param image  the icon
     * @param x      the left edge of the cell
     * @param y      the top edge of the cell
     */
    private void drawIcon(@NotNull GlueCanvas canvas, GlueCanvas.@NotNull Image image, float x, float y) {
        float scale = Math.min(CELL_SIZE / image.getWidth(), CELL_SIZE / image.getHeight());
        float width = image.getWidth() * scale;
        float height = image.getHeight() * scale;

        canvas.drawImage(image, x + (CELL_SIZE - width) / CENTER_DIVISOR, y + (CELL_SIZE - height) / CENTER_DIVISOR, width, height);
    }

    /**
     * Draws the right section: the instruction without a unit, else the unit's action cards and either the rebind strip of the chosen action or the
     * instruction to choose one.
     *
     * @param canvas the glue canvas
     * @param left   the dialog's left edge in interface pixels
     * @param top    the dialog's top edge in interface pixels
     * @throws IOException           if an icon cannot be read
     * @throws IllegalStateException if the screen is not open
     */
    private void drawActions(@NotNull GlueCanvas canvas, float left, float top) throws IOException {
        float sectionLeft = left + CONTAINER_LEFT + CONTENT_INSET + UNITS_PADDING_LEFT + UNITS_WIDTH;
        float sectionTop = top + PANEL_TOP + CONTENT_INSET;
        float sectionRight = left + CONTAINER_LEFT + CONTAINER_WIDTH - CONTENT_INSET;
        float sectionBottom = top + PANEL_TOP + PANEL_HEIGHT - CONTENT_INSET;

        canvas.fillRectangle(sectionLeft, sectionTop, BORDER_THICKNESS, sectionBottom - sectionTop, SECTION_RULE);

        float contentLeft = sectionLeft + BORDER_THICKNESS;
        float contentWidth = sectionRight - contentLeft;
        HotkeyPanelModel.Unit selected = this.unit;

        if (selected == null) {
            // Center the instruction in the empty action section.
            WebPanelStyleUtils.drawCenteredLine(canvas,
                                                INSTRUCTIONS_FONT,
                                                this.getContext().getGluText().get(UNIT_SELECTION_INSTRUCTIONS_KEY),
                                                contentLeft, sectionTop,
                                                contentWidth, sectionBottom - sectionTop,
                                                InterfaceText.CENTER,
                                                WebPanelStyleUtils.TEXT);

            return;
        }

        float cardsLeft = contentLeft + CARDS_LEFT;
        float cardsWidth = contentWidth - CARDS_LEFT;
        float y = sectionTop + CARDS_TOP;
        String prefix = this.getContext().getGluText().get(SELECTED_PREFIX_KEY);
        float headingLine = canvas.getLineHeight(HEADING_FONT);

        WebPanelStyleUtils.drawText(canvas,
                                    HEADING_FONT,
                                    prefix,
                                    cardsLeft, y,
                                    cardsWidth, headingLine,
                                    InterfaceText.LEFT | InterfaceText.TOP,
                                    LIGHT_GRAY);

        float nameLeft = cardsLeft + canvas.measure(HEADING_FONT, prefix) + HEADING_NAME_GAP;

        WebPanelStyleUtils.drawText(canvas,
                                    HEADING_FONT,
                                    this.requireStatText().getUnitName(selected.getUnitType()),
                                    nameLeft, y,
                                    cardsLeft + cardsWidth - nameLeft, headingLine,
                                    InterfaceText.LEFT | InterfaceText.TOP,
                                    WHITE);
        y += headingLine;

        float cardWidth = cardsWidth * CARD_WIDTH_FRACTION;
        float titleLine = canvas.getLineHeight(CARD_TITLE_FONT);
        float cardHeight = titleLine + CARD_TITLE_BOTTOM + HotkeyPanelModel.GRID_SIZE * CELL_PITCH;
        List<HotkeyPanelModel.Card> cards = selected.getCards();

        for (int index = 0; index < cards.size(); index++) {
            float cardLeft = cardsLeft + index % CARDS_PER_ROW * cardWidth;
            float cardTop = y + CARD_TOP + index / CARDS_PER_ROW * (CARD_TOP + cardHeight);

            this.drawCard(canvas, selected, cards.get(index), cardLeft, cardTop, cardWidth, titleLine);
        }

        int cardRows = (cards.size() + CARDS_PER_ROW - 1) / CARDS_PER_ROW;
        float cardsBottom = y + cardRows * (CARD_TOP + cardHeight);

        HotkeyPanelModel.Action chosen = this.action;

        if (chosen == null) {
            WebPanelStyleUtils.drawText(canvas,
                                        INSTRUCTIONS_FONT,
                                        this.getContext().getGluText().get(COMMAND_SELECTION_INSTRUCTIONS_KEY),
                                        contentLeft, cardsBottom + INSTRUCTIONS_PADDING,
                                        contentWidth, canvas.getLineHeight(INSTRUCTIONS_FONT),
                                        InterfaceText.CENTER | InterfaceText.TOP,
                                        WebPanelStyleUtils.TEXT);

            return;
        }

        this.drawRebind(canvas, selected, chosen, contentLeft, sectionBottom, contentWidth);
    }

    /**
     * Draws a command card title and its grid of action cells.
     *
     * @param canvas    the glue canvas
     * @param owner     the unit whose card is drawn
     * @param card      the card
     * @param x         the left edge of the card
     * @param y         the top edge of the card
     * @param width     the width of the card
     * @param titleLine the line height of the card title
     * @throws IOException if an icon cannot be read
     */
    private void drawCard(@NotNull GlueCanvas canvas,
                          HotkeyPanelModel.@NotNull Unit owner,
                          HotkeyPanelModel.@NotNull Card card,
                          float x, float y,
                          float width,
                          float titleLine) throws IOException {
        String cardLabelKey = card.getLabel().getLabel();

        WebPanelStyleUtils.drawText(canvas,
                                    CARD_TITLE_FONT,
                                    this.getContext().getGluText().get(cardLabelKey),
                                    x, y,
                                    width, titleLine,
                                    InterfaceText.LEFT | InterfaceText.TOP,
                                    WebPanelStyleUtils.TEXT);

        float gridTop = y + titleLine + CARD_TITLE_BOTTOM;
        HotkeyPanelModel.Action[] cells = new HotkeyPanelModel.Action[HotkeyPanelModel.GRID_SIZE * HotkeyPanelModel.GRID_SIZE];

        for (HotkeyPanelModel.Action each : card.getActions()) {
            cells[(each.getRow(owner) - 1) * HotkeyPanelModel.GRID_SIZE + each.getColumn(owner) - 1] = each;
        }

        for (int cell = 0; cell < cells.length; cell++) {
            float cellLeft = x + cell % HotkeyPanelModel.GRID_SIZE * CELL_PITCH;
            float cellTop = gridTop + cell / HotkeyPanelModel.GRID_SIZE * CELL_PITCH;
            HotkeyPanelModel.Action each = cells[cell];

            if (each == null) {
                canvas.fillRectangle(cellLeft, cellTop, CELL_SIZE, CELL_SIZE, EMPTY_CELL);

                continue;
            }

            Region region = new Region(Kind.ACTION, each, cellLeft, cellTop, CELL_SIZE, CELL_SIZE, true);

            this.drawAction(canvas, owner, each, cellLeft, cellTop, each == this.action, region.hasSameTargetAs(this.hovered), true);
            this.regions.add(region);
        }
    }

    /**
     * Draws an action icon, selection ring and binding error tint, with an optional key label.
     *
     * @param canvas        the glue canvas
     * @param owner         the unit whose action is drawn
     * @param shown         the action
     * @param x             the left edge of the cell
     * @param y             the top edge of the cell
     * @param selected      whether the action is the chosen one
     * @param hovered       whether the pointer is over the cell
     * @param keyLabelShown whether the key label or the unbound indicator is drawn
     * @throws IOException           if an icon cannot be read
     * @throws IllegalStateException if the screen is not open
     */
    private void drawAction(@NotNull GlueCanvas canvas,
                            HotkeyPanelModel.@NotNull Unit owner,
                            HotkeyPanelModel.@NotNull Action shown,
                            float x, float y,
                            boolean selected, boolean hovered,
                            boolean keyLabelShown) throws IOException {
        HotkeyEditor bindings = this.requireEditor();
        boolean unbound = bindings.isUnbound(shown.getIdentifier());

        canvas.fillRectangle(x, y, CELL_SIZE, CELL_SIZE, GlueCanvas.OPAQUE_BLACK);
        this.drawIcon(canvas, canvas.getWebImage(shown.getIcon(owner), ACTION_TINT), x, y);

        int ring = CELL_RING;

        if (selected) {
            ring = unbound ? ERROR_RED : WebPanelStyleUtils.HIGHLIGHT_TEXT;
        } else if (hovered) {
            ring = unbound ? PURE_RED : WebPanelStyleUtils.BUTTON_TEXT;
        }

        canvas.drawRectangle(x, y, CELL_SIZE, CELL_SIZE, GlueCanvas.OUTLINE_THICKNESS, ring);

        if (unbound) {
            canvas.fillRectangle(x, y, CELL_SIZE, CELL_SIZE, ERROR_TINT);
        }

        if (!keyLabelShown) {
            return;
        }

        if (unbound) {
            this.drawErrorMark(canvas, x + UNIT_ERROR_INSET, y + UNIT_ERROR_INSET);

            return;
        }

        String label = formatKeyText(bindings.getKey(shown.getIdentifier()));
        float labelWidth = Math.max(KEY_LABEL_SIZE, canvas.measure(KEY_LABEL_FONT, label));
        float labelHeight = Math.max(KEY_LABEL_SIZE, canvas.getLineHeight(KEY_LABEL_FONT));

        canvas.fillRectangle(x + KEY_LABEL_INSET, y + KEY_LABEL_INSET, labelWidth, labelHeight, KEY_LABEL_FILL);

        float lineHeight = canvas.getLineHeight(KEY_LABEL_FONT);

        WebPanelStyleUtils.drawText(canvas,
                                    KEY_LABEL_FONT,
                                    label,
                                    x + KEY_LABEL_INSET, y + KEY_LABEL_INSET + (labelHeight - lineHeight) / CENTER_DIVISOR,
                                    labelWidth, lineHeight,
                                    InterfaceText.CENTER | InterfaceText.TOP,
                                    WHITE);
    }

    /**
     * Draws the selected action and its default/current key at the bottom of the action section.
     *
     * @param canvas the glue canvas
     * @param owner  the selected unit
     * @param chosen the chosen action
     * @param x      the left edge of the action section's content
     * @param bottom the bottom edge of the action section's content
     * @param width  the width of the action section's content
     * @throws IOException           if an icon cannot be read
     * @throws IllegalStateException if the screen is not open
     */
    private void drawRebind(@NotNull GlueCanvas canvas,
                            HotkeyPanelModel.@NotNull Unit owner,
                            HotkeyPanelModel.@NotNull Action chosen,
                            float x, float bottom,
                            float width) throws IOException {
        HotkeyEditor bindings = this.requireEditor();
        int shared = HotkeyPanelModel.countUnits(chosen);
        float titleLine = canvas.getLineHeight(ACTION_TITLE_FONT);
        float countLine = canvas.getLineHeight(ACTION_COUNT_FONT);
        float textHeight = titleLine + (shared > 1 ? countLine : 0) + DEFAULT_KEY_TOP + titleLine;
        float fieldLine = canvas.getLineHeight(KEY_FIELD_FONT);
        float fieldHeight = fieldLine + SIDE_COUNT * (KEY_FIELD_PADDING + BORDER_THICKNESS);
        float contentHeight = Math.max(CELL_PITCH, Math.max(textHeight, fieldHeight));
        float stripHeight = SIDE_COUNT * (FRAME_INSET + REBIND_PADDING_TOP) + contentHeight;
        float stripTop = bottom - stripHeight;
        float instructionLine = canvas.getLineHeight(REBIND_INSTRUCTION_FONT);
        float instructionTop = stripTop - REBIND_INSTRUCTION_BOTTOM - instructionLine;

        boolean errors = bindings.hasErrors();
        String instructionKey = errors ? UNBOUND_INSTRUCTIONS_KEY : HOTKEY_INSTRUCTIONS_KEY;
        String instruction = this.getContext().getGluText().get(instructionKey);

        WebPanelStyleUtils.drawText(canvas,
                                    REBIND_INSTRUCTION_FONT,
                                    instruction,
                                    x, instructionTop,
                                    width, instructionLine,
                                    InterfaceText.CENTER | InterfaceText.TOP,
                                    errors ? ERROR_RED : LIGHT_GRAY);
        canvas.fillRectangle(x, stripTop, width, BORDER_THICKNESS, SECTION_RULE);
        canvas.fillRectangle(x, stripTop + stripHeight - BORDER_THICKNESS, width, BORDER_THICKNESS, SECTION_RULE);
        canvas.drawRectangle(x, stripTop + BORDER_THICKNESS, width, stripHeight - SIDE_COUNT * BORDER_THICKNESS,
                             GlueCanvas.OUTLINE_THICKNESS, REBIND_INNER_BORDER);
        canvas.fillRectangle(x + BORDER_THICKNESS, stripTop + FRAME_INSET,
                             width - SIDE_COUNT * BORDER_THICKNESS, stripHeight - FRAME_SPAN,
                             REBIND_FILL);

        float contentTop = stripTop + FRAME_INSET + REBIND_PADDING_TOP;
        float iconLeft = x + FRAME_INSET + REBIND_PADDING_LEFT;

        this.drawAction(canvas, owner, chosen, iconLeft, contentTop, false, false, false);

        float textLeft = iconLeft + CELL_PITCH + REBIND_TEXT_MARGIN;
        float fieldLeft = x + width - BORDER_THICKNESS - KEY_FIELD_RIGHT - KEY_FIELD_WIDTH;
        float textWidth = fieldLeft - REBIND_TEXT_MARGIN - textLeft;
        float lineTop = contentTop;

        WebPanelStyleUtils.drawText(canvas,
                                    ACTION_TITLE_FONT,
                                    this.actionNames.getOrDefault(chosen.getIdentifier(), ""),
                                    textLeft, lineTop,
                                    textWidth, titleLine,
                                    InterfaceText.LEFT | InterfaceText.TOP,
                                    WHITE);
        lineTop += titleLine;

        if (shared > 1) {
            String countText = this.getContext().getGluText().get(UNITS_SHARED_KEY).replace(UNITS_SHARED_PLACEHOLDER, Integer.toString(shared));

            WebPanelStyleUtils.drawText(canvas,
                                        ACTION_COUNT_FONT,
                                        countText,
                                        textLeft, lineTop,
                                        textWidth, countLine,
                                        InterfaceText.LEFT | InterfaceText.TOP,
                                        WebPanelStyleUtils.TEXT);
            lineTop += countLine;
        }

        lineTop += DEFAULT_KEY_TOP;

        String defaultLabel = this.getContext().getGluText().get(DEFAULT_BINDING_KEY);

        WebPanelStyleUtils.drawText(canvas,
                                    ACTION_TITLE_FONT,
                                    defaultLabel,
                                    textLeft, lineTop,
                                    textWidth, titleLine,
                                    InterfaceText.LEFT | InterfaceText.TOP,
                                    DEFAULT_KEY_GRAY);

        float keyLeft = textLeft + canvas.measure(ACTION_TITLE_FONT, defaultLabel) + DEFAULT_KEY_GAP;

        WebPanelStyleUtils.drawText(canvas,
                                    ACTION_TITLE_FONT,
                                    formatKeyText(bindings.getDefaultKey(chosen.getIdentifier())),
                                    keyLeft, lineTop,
                                    textLeft + textWidth - keyLeft, titleLine,
                                    InterfaceText.LEFT | InterfaceText.TOP,
                                    DEFAULT_KEY_GRAY);

        // The key field always keeps the focus, so it is always drawn with the focused fill.
        WebPanelStyleUtils.drawBox(canvas,
                                   fieldLeft, contentTop, KEY_FIELD_WIDTH, fieldHeight,
                                   KEY_FIELD_RADIUS,
                                   KEY_FIELD_BORDER, KEY_FIELD_FILL,
                                   true, true,
                                   true, true);
        WebPanelStyleUtils.drawText(canvas,
                                    KEY_FIELD_FONT,
                                    formatKeyText(bindings.getKey(chosen.getIdentifier())),
                                    fieldLeft, contentTop + BORDER_THICKNESS + KEY_FIELD_PADDING,
                                    KEY_FIELD_WIDTH, fieldLine,
                                    InterfaceText.CENTER | InterfaceText.TOP,
                                    LIGHT_GRAY);
    }

    /**
     * Draws the footer: Restore to Default in a group on the left, Save and Cancel in a group on the right.
     *
     * @param canvas the glue canvas
     * @param left   the dialog's left edge in interface pixels
     * @param top    the dialog's top edge in interface pixels
     * @throws IllegalStateException if the screen is not open
     */
    private void drawFooter(@NotNull GlueCanvas canvas, float left, float top) {
        float y = top + PANEL_TOP + PANEL_HEIGHT + FOOTER_GAP;
        float groupHeight = WebPanelStyleUtils.BUTTON_HEIGHT + SIDE_COUNT * WebPanelStyleUtils.BUTTON_GROUP_INSET;
        String restore = this.getContext().getGluText().get(RESTORE_BUTTON_KEY);
        float restoreWidth = WebPanelStyleUtils.getButtonWidth(canvas.measure(WebPanelStyleUtils.BUTTON_FONT, restore));
        float restoreLeft = left + CONTAINER_LEFT;

        WebPanelStyleUtils.drawButtonGroup(canvas, restoreLeft, y, restoreWidth + SIDE_COUNT * WebPanelStyleUtils.BUTTON_GROUP_INSET, groupHeight);
        this.drawButton(canvas,
                        Kind.RESTORE,
                        restore,
                        restoreLeft + WebPanelStyleUtils.BUTTON_GROUP_INSET, y + WebPanelStyleUtils.BUTTON_GROUP_INSET,
                        restoreWidth,
                        true,
                        false);

        String save = this.getContext().getGluText().get(SAVE_BUTTON_KEY);
        String cancel = this.getContext().getGluText().get(CANCEL_BUTTON_KEY);
        float saveWidth = WebPanelStyleUtils.getButtonWidth(canvas.measure(WebPanelStyleUtils.BUTTON_FONT, save));
        float cancelWidth = WebPanelStyleUtils.getButtonWidth(canvas.measure(WebPanelStyleUtils.BUTTON_FONT, cancel));
        float groupWidth = saveWidth + WebPanelStyleUtils.BUTTON_GROUP_SPACING + cancelWidth + SIDE_COUNT * WebPanelStyleUtils.BUTTON_GROUP_INSET;
        float groupLeft = left + CONTAINER_LEFT + CONTAINER_WIDTH - groupWidth;

        WebPanelStyleUtils.drawButtonGroup(canvas, groupLeft, y, groupWidth, groupHeight);

        float buttonLeft = groupLeft + WebPanelStyleUtils.BUTTON_GROUP_INSET;

        this.drawButton(canvas,
                        Kind.SAVE,
                        save,
                        buttonLeft, y + WebPanelStyleUtils.BUTTON_GROUP_INSET,
                        saveWidth,
                        !this.requireEditor().hasErrors(),
                        false);
        this.drawButton(canvas,
                        Kind.CANCEL,
                        cancel,
                        buttonLeft + saveWidth + WebPanelStyleUtils.BUTTON_GROUP_SPACING, y + WebPanelStyleUtils.BUTTON_GROUP_INSET,
                        cancelWidth,
                        true,
                        false);
    }

    private void drawButton(@NotNull GlueCanvas canvas,
                            @NotNull Kind kind,
                            @NotNull String label,
                            float x, float y,
                            float width,
                            boolean enabled,
                            boolean filled) {
        Region region = new Region(kind, null, x, y, width, WebPanelStyleUtils.BUTTON_HEIGHT, enabled);

        WebPanelStyleUtils.drawButton(canvas, label, x, y, width, enabled && region.hasSameTargetAs(this.hovered), enabled, filled);
        this.regions.add(region);
    }

    /**
     * Draws the open page dialog over a {@code 60} % black dim: Save Changes? (Save, Don't Save) or Restore defaults?
     * (Continue, Cancel), with its close mark.
     *
     * @param canvas the glue canvas
     * @param left   the dialog's left edge in interface pixels
     * @param top    the dialog's top edge in interface pixels
     */
    private void drawPageDialog(@NotNull GlueCanvas canvas, float left, float top) {
        canvas.fillRectangle(0, 0, canvas.getInterfaceWidth(), GlueCanvas.INTERFACE_HEIGHT, MODAL_DIM);

        boolean saving = this.pageDialog == PageDialog.SAVE_CHANGES;
        String titleKey = saving ? SAVE_CHANGES_QUESTION_KEY : RESTORE_DEFAULTS_QUESTION_KEY;
        String title = this.getContext().getGluText().get(titleKey);
        String bodyKey = saving ? SAVE_CHANGES_EXPLANATION_KEY : RESTORE_DEFAULTS_EXPLANATION_KEY;
        String body = this.getContext().getGluText().get(bodyKey);
        String leftLabelKey = saving ? SAVE_BUTTON_KEY : CONTINUE_BUTTON_KEY;
        String leftLabel = this.getContext().getGluText().get(leftLabelKey);
        String rightLabelKey = saving ? DONT_SAVE_BUTTON_KEY : CANCEL_BUTTON_KEY;
        String rightLabel = this.getContext().getGluText().get(rightLabelKey);

        float titleLine = canvas.getLineHeight(DIALOG_TITLE_FONT);
        float bodyLine = canvas.getLineHeight(DIALOG_BODY_FONT);
        List<String> bodyLines = InterfaceText.wrap(canvas.getFont(DIALOG_BODY_FONT), body, DIALOG_CONTENT_WIDTH);
        float contentHeight = Math.max(DIALOG_MINIMUM_HEIGHT,
                                       titleLine + DIALOG_BODY_TOP + bodyLines.size() * bodyLine + WebPanelStyleUtils.BUTTON_HEIGHT);
        float boxWidth = DIALOG_CONTENT_WIDTH + DIALOG_PADDING_LEFT + DIALOG_PADDING_RIGHT + SIDE_COUNT * BORDER_THICKNESS;
        float boxHeight = contentHeight + DIALOG_PADDING_TOP + DIALOG_PADDING_BOTTOM + SIDE_COUNT * BORDER_THICKNESS;
        float boxLeft = left + (GlueCanvas.INTERFACE_WIDTH - boxWidth) / CENTER_DIVISOR;
        float boxTop = top + (GlueCanvas.INTERFACE_HEIGHT - boxHeight) / CENTER_DIVISOR;

        WebPanelStyleUtils.drawDialogBox(canvas, boxLeft, boxTop, boxWidth, boxHeight);

        float contentLeft = boxLeft + BORDER_THICKNESS + DIALOG_PADDING_LEFT;
        float contentTop = boxTop + BORDER_THICKNESS + DIALOG_PADDING_TOP;

        WebPanelStyleUtils.drawText(canvas,
                                    DIALOG_TITLE_FONT,
                                    title,
                                    contentLeft, contentTop,
                                    DIALOG_CONTENT_WIDTH, titleLine,
                                    InterfaceText.LEFT | InterfaceText.TOP,
                                    WebPanelStyleUtils.TEXT);

        float lineTop = contentTop + titleLine + DIALOG_BODY_TOP;

        for (String line : bodyLines) {
            WebPanelStyleUtils.drawText(canvas,
                                        DIALOG_BODY_FONT,
                                        line,
                                        contentLeft, lineTop,
                                        DIALOG_CONTENT_WIDTH, bodyLine,
                                        InterfaceText.LEFT | InterfaceText.TOP,
                                        LIGHT_GRAY);
            lineTop += bodyLine;
        }

        float buttonTop = contentTop + contentHeight - WebPanelStyleUtils.BUTTON_HEIGHT;
        float rightWidth = WebPanelStyleUtils.getButtonWidth(canvas.measure(WebPanelStyleUtils.BUTTON_FONT, rightLabel));
        float leftWidth = WebPanelStyleUtils.getButtonWidth(canvas.measure(WebPanelStyleUtils.BUTTON_FONT, leftLabel));
        float rightLeft = contentLeft + DIALOG_CONTENT_WIDTH - rightWidth;

        this.drawButton(canvas, Kind.DIALOG_LEFT, leftLabel, rightLeft - DIALOG_BUTTON_GAP - leftWidth, buttonTop, leftWidth, true, true);
        this.drawButton(canvas, Kind.DIALOG_RIGHT, rightLabel, rightLeft, buttonTop, rightWidth, true, true);

        float closeWidth = canvas.measure(DIALOG_CLOSE_FONT, DIALOG_CLOSE);
        float closeHeight = canvas.getLineHeight(DIALOG_CLOSE_FONT);
        float closeLeft = boxLeft + boxWidth + DIALOG_CLOSE_OFFSET - closeWidth;
        float closeTop = boxTop - DIALOG_CLOSE_OFFSET;

        WebPanelStyleUtils.drawText(canvas,
                                    DIALOG_CLOSE_FONT,
                                    DIALOG_CLOSE,
                                    closeLeft, closeTop,
                                    closeWidth, closeHeight,
                                    InterfaceText.LEFT | InterfaceText.TOP,
                                    WHITE);
        this.regions.add(new Region(Kind.DIALOG_CLOSE, null, closeLeft, closeTop, closeWidth, closeHeight, true));
    }
}
