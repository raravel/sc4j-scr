package org.fogofwarlabs.sc4j.scr.client.glue.popup;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.scr.client.audio.SoundSettings;
import org.fogofwarlabs.sc4j.scr.client.configuration.ClientSettings;
import org.fogofwarlabs.sc4j.scr.client.configuration.GraphicsSettings;
import org.fogofwarlabs.sc4j.scr.client.configuration.InterfaceSettings;
import org.fogofwarlabs.sc4j.scr.client.cursor.PointerSpeed;
import org.fogofwarlabs.sc4j.scr.client.glue.*;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.*;
import org.fogofwarlabs.sc4j.scr.client.ui.observer.ApmAlert;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;

/**
 * Shows available client options in a framed glue popup with tabs and restore-default controls.
 * <p>
 * Unavailable options and empty tabs are omitted. Sliders show values and range captions; checkbox and radio
 * controls use the glue and web panel styles. Each change is stored immediately through {@link GlueSettings}.
 * Restore to Default asks for confirmation and resets only options whose defaults are known.
 */
public final class OptionsPopup extends GluePopup {
    private static final @NotNull String DIALOG = "gluOptionsPopup";

    private static final int TITLE = 100;
    private static final int FIRST_TAB = 101;
    private static final int RESTORE = 110;
    private static final int OK = 111;
    private static final int FIRST_OPTION = 200;
    /**
     * The control id that stands for no control: the {@code enabledBy} value of an option that no check box enables, and
     * the id of a check box whose option the client does not have.
     */
    private static final int NO_CONTROL = 0;

    private static final int COLUMN_COUNT = 2;
    /**
     * Halves a width or a gap: each range caption takes half of the slider track, and half a row gap follows them.
     */
    private static final int HALF_DIVISOR = 2;
    private static final int BOX_SIDES = 2;
    private static final int SLIDER_WIDTH_INSET = 8;
    private static final int SLIDER_LABEL_GAP = 2;
    private static final int DEFAULT_GAME_SPEED = 4;
    private static final int MOUSE_SENSITIVITY_STEP = 5;
    private static final int VOLUME_STEP = 5;
    private static final int MINIMUM_FRAME_RATE_LIMIT = 100;
    private static final int MAXIMUM_FRAME_RATE_LIMIT = 300;
    private static final @NotNull String MINIMUM_FRAME_RATE_CAPTION = "100";
    private static final @NotNull String MAXIMUM_FRAME_RATE_CAPTION = "300";
    private static final int MAXIMUM_SD_FILTER_MODE = 3;
    private static final int DEFAULT_SD_FILTER_MODE = 1;
    /**
     * The network latency values of the low, high and extra-high settings.
     */
    private static final int LOW_LATENCY = 0;
    private static final int HIGH_LATENCY = 1;
    private static final int EXTRA_HIGH_LATENCY = 2;

    /**
     * The panel and its grid in the {@code 640x480} dialog.
     */
    private static final int PANEL_LEFT = 40;
    private static final int PANEL_TOP = 44;
    private static final int PANEL_WIDTH = 560;
    private static final int PANEL_HEIGHT = 392;
    private static final int MARGIN = 14;
    private static final int TITLE_OFFSET = 12;
    private static final int RULE_OFFSET = 38;
    private static final int BODY_OFFSET = 12;
    private static final int DIVIDER_GAP = 10;
    private static final int CONTENT_GAP = 14;
    private static final int CONTENT_RIGHT_INSET = 6;
    private static final int BODY_BOTTOM_GAP = 10;
    private static final int RULE_THICKNESS = 1;
    private static final int TITLE_TOP = PANEL_TOP + TITLE_OFFSET;
    private static final int TITLE_HEIGHT = 20;
    private static final int RULE_TOP = PANEL_TOP + RULE_OFFSET;
    private static final int BODY_TOP = RULE_TOP + BODY_OFFSET;
    private static final int TAB_LEFT = PANEL_LEFT + MARGIN;
    private static final int TAB_WIDTH = 108;
    private static final int TAB_HEIGHT = 22;
    private static final int TAB_PITCH = 28;
    private static final int DIVIDER_LEFT = TAB_LEFT + TAB_WIDTH + DIVIDER_GAP;
    private static final int CONTENT_LEFT = DIVIDER_LEFT + CONTENT_GAP;
    private static final int CONTENT_WIDTH = PANEL_LEFT + PANEL_WIDTH - MARGIN - CONTENT_RIGHT_INSET - CONTENT_LEFT;
    private static final int BUTTON_HEIGHT = 24;
    private static final int BUTTON_TOP = PANEL_TOP + PANEL_HEIGHT - MARGIN - BUTTON_HEIGHT;
    private static final int BODY_BOTTOM = BUTTON_TOP - BODY_BOTTOM_GAP;
    private static final int RESTORE_WIDTH = 190;
    private static final int OK_WIDTH = 120;

    private static final int ROW_HEIGHT = 18;
    private static final int ROW_GAP = 6;
    private static final int SECTION_GAP = 12;
    private static final int SLIDER_WIDTH = 240;
    /**
     * The two columns of a long tab (Video): sliders on the left, check boxes on the right.
     */
    private static final int COLUMN_GAP = 16;
    private static final int HALF_WIDTH = (CONTENT_WIDTH - COLUMN_GAP) / COLUMN_COUNT;
    private static final int SLIDER_HEIGHT = 12;
    private static final int CAPTION_HEIGHT = 16;
    private static final int COMBO_BOX_WIDTH = 200;
    private static final int COMBO_BOX_HEIGHT = 18;
    private static final int DESCRIPTION_HEIGHT = 34;
    private static final int VALUE_WIDTH = 100;

    /**
     * The radio label offset from the button and the space between buttons.
     */
    private static final int RADIO_LABEL_LEFT = 15;
    private static final int RADIO_SPACING = 10;
    /**
     * The number box's inner left.
     */
    private static final int NUMBER_PADDING = 2;
    /**
     * The number box border radius in interface pixels.
     */
    private static final float NUMBER_RADIUS = 2;
    /**
     * The number box's fill color: none.
     */
    private static final int NO_FILL = 0;
    /**
     * The indent of the APM alert sub-options in interface pixels.
     */
    private static final int SUB_OPTION_INDENT = 14;
    /**
     * The APM threshold box width, including space for three digits and the caret in the option font.
     */
    private static final int NUMBER_WIDTH = 32;
    private static final int NUMBER_GAP = 4;
    private static final int NUMBER_DIGITS = 3;
    /**
     * The characters the threshold box refuses: every printable ASCII character but the digits.
     */
    private static final @NotNull String NON_DIGITS = createNonDigits();

    private static final int DIM_COLOR = 0x000000D0;
    private static final int PANEL_BACKING_COLOR = 0x000000FF;
    /**
     * The frame of a framed button on the popup art ({@link #POPUP_PANEL_IMAGE}: the frame around its Ok button), a sample of its inside and
     * the size of its corners.
     */
    private static final float @NotNull [] FRAME_REGION = {17, 159, 158, 29};
    private static final float @NotNull [] FRAME_FILL = {40, 168, 20, 8};
    private static final float FRAME_BORDER = 6;

    private static final @NotNull String VISIBLE_FLAG = "WS_VISIBLE";
    private static final @NotNull String SMALL_TEXT_FLAG = "RC_SMALLTEXT";
    private static final @NotNull String BIG_TEXT_FLAG = "RC_BIGTEXT";
    private static final @NotNull String HORIZONTAL_SCROLL_BAR_FLAG = "SBS_HORZ";
    private static final @NotNull String HORIZONTAL_CENTER_FLAG = "FB_HTEXTCENTER";
    private static final @NotNull String VERTICAL_CENTER_FLAG = "FB_VTEXTCENTER";
    private static final @NotNull String HOTKEY_FLAG = "WS_HOTKEY";
    private static final @NotNull String ESCAPE_FLAG = "CTRL_ESCAPE";
    private static final @NotNull String ENTER_FLAG = "CTRL_ENTER";
    private static final @NotNull String NO_CONTENT = "";

    private static final @NotNull String SLOW = "bnet119_optionsPanel_general_label_slow";
    private static final @NotNull String FAST = "bnet119_optionsPanel_general_label_fast";
    private static final @NotNull String MINIMUM = "bnet119_optionsPanel_general_label_min";
    private static final @NotNull String MAXIMUM = "bnet119_optionsPanel_general_label_max";
    private static final @NotNull String PREFIX = "bnet119_optionsPanel_";
    private static final @NotNull String TITLE_TEXT_KEY = PREFIX + "general_title";
    private static final @NotNull String TAB_TEXT_PREFIX = PREFIX + "tabs_";
    private static final @NotNull String RESTORE_TEXT_KEY = PREFIX + "general_restore";
    private static final @NotNull String OK_TEXT_KEY = "str_ok";
    private static final @NotNull String RESET_QUESTION_PREFIX = PREFIX + "reset_";
    private static final @NotNull String RESET_QUESTION_SUFFIX = "_settings";
    private static final @NotNull String RESTORE_EXPLANATION_KEY = "bnet119_hotkeysPanel_restoreDefaultsExplanation";
    private static final @NotNull String LINE_SEPARATOR = "\n";
    private static final @NotNull String LANGUAGE_TEXT_PREFIX = PREFIX + "language_";
    private static final @NotNull String LOCALE_FOLDER = "locales/";
    private static final @NotNull String LOCALE_TEXT_FILE = "/Assets/rez/gluAll.json";

    private static final @NotNull String GAME_SPEED_KEY = "game.gameSpeed";
    private static final @NotNull String VSYNC_KEY = "graphics.vsync";
    private static final @NotNull String RENDERER_KEY = "graphics.renderer";
    private static final @NotNull String FRAME_RATE_LIMIT_ENABLED_KEY = "graphics.fpsLimitEnabled";
    private static final @NotNull String HD_LIGHTING_KEY = "graphics.hdLighting";
    private static final @NotNull String AUDIO_SET_KEY = "audio.set";
    private static final @NotNull String LOCALE_KEY = "game.locale";
    private static final @NotNull String LATENCY_KEY = "network.latency";
    private static final @NotNull String APM_ALERT_THRESHOLD_KEY = "game.apmAlertValue";
    private static final @NotNull String VSYNC_ON = "on";
    private static final @NotNull String VSYNC_OFF = "off";
    private static final @NotNull String CLASSIC_RENDERER = "classic";
    private static final @NotNull String HD_RENDERER = "hd";
    private static final @NotNull String HD2_RENDERER = "hd2";
    private static final @NotNull String REMASTERED_AUDIO_SET = "remastered";
    private static final @NotNull String CLASSIC_AUDIO_SET = "classic";

    /**
     * Keeps options unchanged when their defaults are unknown or the page has no such option.
     */
    private static final @NotNull Set<String> UNKNOWN_DEFAULTS = Set.of(FRAME_RATE_LIMIT_ENABLED_KEY, RENDERER_KEY,
                                                                        HD_LIGHTING_KEY, AUDIO_SET_KEY, LOCALE_KEY);

    /**
     * The installed text locales offered by Language ({@code bnet119_optionsPanel_language_<code>}).
     */
    private static final @NotNull List<String> LOCALES = List.of("enUS", "deDE", "esES", "esMX", "frFR", "itIT", "jaJP", "koKR", "plPL", "ptBR",
                                                                 "ruRU", "zhCN", "zhTW");

    /**
     * The tabs of the page, in the page's order.
     */
    private enum Tab {
        /**
         * Game, scroll and pointer speed options.
         */
        SPEED("speed"),
        /**
         * Sound volume and playback options.
         */
        SOUND("sound"),
        /**
         * Display and rendering options.
         */
        VIDEO("video"),
        /**
         * Gameplay interface and language options.
         */
        GAME("game"),
        /**
         * Network latency options.
         */
        NETWORK("network"),
        /**
         * Help options when available.
         */
        HELP("help");

        private final @NotNull String keyName;

        Tab(@NotNull String keyName) {
            this.keyName = keyName;
        }
    }

    /**
     * One option control: its tab, settings key and default value.
     */
    private static final class Option {
        private final @NotNull Tab tab;
        private final @NotNull String key;
        private final @NotNull Object defaultValue;

        private final int minimum;
        private final int step;
        private final int enabledBy;

        private final @NotNull List<Object> values;

        private Option(@NotNull Tab tab, @NotNull String key, @NotNull Object defaultValue,
                       int minimum, int step, int enabledBy,
                       @NotNull List<Object> values) {
            this.tab          = tab;
            this.key          = key;
            this.defaultValue = defaultValue;

            this.minimum   = minimum;
            this.step      = step;
            this.enabledBy = enabledBy;

            this.values = values;
        }
    }

    /**
     * Lays out the controls of a tab from top to bottom on one grid: labels at the content left, equal row heights.
     */
    private final class Column {
        private final @NotNull Tab tab;
        private final int left;
        private final int width;
        private final int sliderWidth;

        private int top;
        private boolean empty;

        private Column(@NotNull Tab tab, int left, int width) {
            this.tab         = tab;
            this.left        = left;
            this.width       = width;
            this.sliderWidth = Math.min(SLIDER_WIDTH, width - SLIDER_WIDTH_INSET);

            this.top   = BODY_TOP;
            this.empty = true;
        }

        private boolean hasOption(@NotNull String key) {
            return OptionsPopup.this.getContext().getSettings().getOption(key) != null;
        }


        private void beginSection() {
            if (!this.empty) {
                this.top += SECTION_GAP;
            }

            this.empty = false;
        }

        private int addTextControl(@NotNull ScrControlType type,
                                   @NotNull String label,
                                   int left, int top,
                                   int width, int height,
                                   @NotNull String @NotNull ... flags) {
            int identifier = OptionsPopup.this.addControl(type, left, top, width, height, flags);

            OptionsPopup.this.labels.put(identifier, label);
            OptionsPopup.this.tabs.put(identifier, this.tab);

            return identifier;
        }

        private void addDescription(@NotNull String label) {
            this.addTextControl(ScrControlType.LTEXT, label, this.left, this.top, this.width, DESCRIPTION_HEIGHT, SMALL_TEXT_FLAG);
            this.top += DESCRIPTION_HEIGHT;
        }

        /**
         * Adds a check box that no other check box enables, without indent and with its whole row.
         *
         * @param label        the {@code gluAll} key of the check box's label
         * @param key          the settings key of the option
         * @param defaultValue the option's default value
         * @return the check box's control id, or {@link OptionsPopup#NO_CONTROL} if the client has no such option
         */
        private int addCheckBox(@NotNull String label, @NotNull String key, boolean defaultValue) {
            return this.addCheckBox(label, key, defaultValue, NO_CONTROL, 0, 0);
        }

        /**
         * Adds a check box, indented and enabled only while another check box is checked (the page's sub-options), with
         * room left on its row for a number box.
         *
         * @param label        the {@code gluAll} key of the check box's label
         * @param key          the settings key of the option
         * @param defaultValue the option's default value
         * @param enabledBy    the control id of the check box that enables it, or {@link OptionsPopup#NO_CONTROL}
         * @param indent       the indent from the column's left edge
         * @param reserved     the width left free at the row's right end
         * @return the check box's control id, or {@link OptionsPopup#NO_CONTROL} if the client has no such option
         */
        private int addCheckBox(@NotNull String label, @NotNull String key, boolean defaultValue, int enabledBy, int indent, int reserved) {
            if (!this.hasOption(key)) {
                return NO_CONTROL;
            }

            this.empty = false;

            int identifier = this.addTextControl(ScrControlType.CHECKBOX,
                                                 label,
                                                 this.left + indent, this.top,
                                                 this.width - indent - reserved, ROW_HEIGHT,
                                                 WebPanelStyleUtils.FLAG);

            OptionsPopup.this.options.put(identifier, new Option(this.tab, key, defaultValue, 0, 1, enabledBy, List.of()));
            this.top += ROW_HEIGHT + ROW_GAP;

            return identifier;
        }

        /**
         * Adds a slider of the page ({@code min}, {@code max}, {@code step}; the scroll bar counts steps): the label (unless
         * a check box above names it) and the value on one row, the track, the two captions under its ends.
         *
         * @param label          the {@code gluAll} key of the slider's label, or {@code null} when a check box above names it
         * @param key            the settings key of the option
         * @param minimum        the smallest value
         * @param maximum        the largest value
         * @param step           the value difference of one scroll bar step
         * @param defaultValue   the option's default value
         * @param enabledBy      the control id of the check box that enables it, or {@link OptionsPopup#NO_CONTROL}
         * @param minimumCaption the {@code gluAll} key or text of the caption under the track's left end
         * @param maximumCaption the {@code gluAll} key or text of the caption under the track's right end
         */
        private void addSliderControl(@Nullable String label,
                                      @NotNull String key,
                                      int minimum, int maximum,
                                      int step,
                                      int defaultValue,
                                      int enabledBy,
                                      @NotNull String minimumCaption, @NotNull String maximumCaption) {
            if (!this.hasOption(key)) {
                return;
            }

            if (label != null) {
                this.beginSection();
                this.addTextControl(ScrControlType.LTEXT, label, this.left, this.top, this.width, ROW_HEIGHT);
            } else {
                this.top -= ROW_HEIGHT + ROW_GAP;
            }

            int valueText = OptionsPopup.this.addControl(ScrControlType.RTEXT,
                                                         this.left + this.sliderWidth - VALUE_WIDTH, this.top,
                                                         VALUE_WIDTH, ROW_HEIGHT);

            OptionsPopup.this.tabs.put(valueText, this.tab);
            this.top += ROW_HEIGHT + SLIDER_LABEL_GAP;

            int identifier = OptionsPopup.this.addControl(ScrControlType.SCROLLBAR,
                                                          this.left, this.top,
                                                          this.sliderWidth, SLIDER_HEIGHT,
                                                          HORIZONTAL_SCROLL_BAR_FLAG);

            OptionsPopup.this.tabs.put(identifier, this.tab);
            OptionsPopup.this.valueTexts.put(identifier, valueText);
            OptionsPopup.this.options.put(identifier, new Option(this.tab, key, defaultValue,
                                                                 minimum, step, enabledBy,
                                                                 List.of((maximum - minimum) / step)));
            this.top += SLIDER_HEIGHT;
            this.addTextControl(ScrControlType.LTEXT,
                                minimumCaption,
                                this.left, this.top,
                                this.sliderWidth / HALF_DIVISOR, CAPTION_HEIGHT,
                                SMALL_TEXT_FLAG);
            this.addTextControl(ScrControlType.RTEXT,
                                maximumCaption,
                                this.left + this.sliderWidth / HALF_DIVISOR, this.top,
                                this.sliderWidth / HALF_DIVISOR, CAPTION_HEIGHT,
                                SMALL_TEXT_FLAG);
            this.top += CAPTION_HEIGHT + ROW_GAP / HALF_DIVISOR;
        }

        private void addCheckedSlider(@NotNull String label,
                                      @NotNull String checkKey, boolean checkDefault,
                                      @NotNull String key,
                                      int minimum, int maximum,
                                      int step,
                                      int defaultValue,
                                      @NotNull String minimumCaption, @NotNull String maximumCaption) {
            this.beginSection();

            int check = this.addCheckBox(label, checkKey, checkDefault);

            if (check != NO_CONTROL) {
                this.addSliderControl(null, key, minimum, maximum, step, defaultValue, check, minimumCaption, maximumCaption);
            }
        }

        /**
         * Adds a radio group of the page: the label, then one radio button per value in a row; each button holds its value.
         *
         * @param label        the {@code gluAll} key of the group's label
         * @param key          the settings key of the option
         * @param items        the {@code gluAll} keys of the buttons' labels
         * @param values       the values of the buttons, in item order
         * @param defaultValue the option's default value
         */
        private void addRadioGroup(@NotNull String label,
                                   @NotNull String key,
                                   @NotNull List<String> items,
                                   @NotNull List<Object> values,
                                   @NotNull Object defaultValue) {
            if (!this.hasOption(key) || items.isEmpty()) {
                return;
            }

            this.beginSection();
            this.addTextControl(ScrControlType.LTEXT, label, this.left, this.top, this.width, ROW_HEIGHT);
            this.top += ROW_HEIGHT + SLIDER_LABEL_GAP;

            // The buttons lie in a row, 10 px apart, each as wide as its circle, gap and label; a button that does not fit starts the next row.
            GlueContext glueContext = OptionsPopup.this.getContext();
            int itemLeft = this.left;

            for (int index = 0; index < items.size(); index++) {
                String itemLabel = glueContext.getGluText().get(items.get(index));
                // The button shows its label as the page does: without the hotkey character of a hotkey string (ScrControl.getDrawnString).
                String text = InterfaceText.isHotkeyString(itemLabel) ? InterfaceText.stripHotkey(itemLabel) : itemLabel;
                int itemWidth = RADIO_LABEL_LEFT + (int) Math.ceil(glueContext.getCanvas().measure(GlueCanvas.NORMAL_FONT, text));

                if (itemLeft > this.left && itemLeft + itemWidth > this.left + this.width) {
                    itemLeft = this.left;
                    this.top += ROW_HEIGHT;
                }

                int identifier = this.addTextControl(ScrControlType.RADIOBUTTON,
                                                     items.get(index),
                                                     itemLeft, this.top,
                                                     itemWidth, ROW_HEIGHT,
                                                     WebPanelStyleUtils.FLAG);

                OptionsPopup.this.options.put(identifier, new Option(this.tab, key, defaultValue, 0, 1, NO_CONTROL, List.of(values.get(index))));
                itemLeft += itemWidth + RADIO_SPACING;
            }

            this.top += ROW_HEIGHT + ROW_GAP;
        }

        private void addComboBoxControl(@NotNull String label,
                                        @NotNull String key,
                                        @NotNull List<String> items,
                                        @NotNull List<Object> values,
                                        int defaultIndex) {
            if (!this.hasOption(key) || items.isEmpty()) {
                return;
            }

            this.beginSection();
            this.addTextControl(ScrControlType.LTEXT, label, this.left, this.top, this.width, ROW_HEIGHT);
            this.top += ROW_HEIGHT + SLIDER_LABEL_GAP;

            int identifier = OptionsPopup.this.addControl(ScrControlType.COMBOBOX,
                                                          this.left, this.top,
                                                          COMBO_BOX_WIDTH, COMBO_BOX_HEIGHT,
                                                          SMALL_TEXT_FLAG);

            OptionsPopup.this.tabs.put(identifier, this.tab);
            OptionsPopup.this.comboBoxItems.put(identifier, items);
            OptionsPopup.this.options.put(identifier, new Option(this.tab, key, values.get(defaultIndex), 0, 1, NO_CONTROL, values));
            this.top += COMBO_BOX_HEIGHT + ROW_GAP;
        }
    }

    private static @NotNull DialogControl createControl(@NotNull ScrControlType type,
                                                        int identifier,
                                                        int left, int top,
                                                        int width, int height,
                                                        @NotNull String @NotNull ... flags) {
        Set<String> names = new HashSet<>(List.of(flags));

        names.add(VISIBLE_FLAG);

        return ScrDialogControlUtils.createControl(type, identifier, left, top, width, height, names, NO_CONTENT, List.of());
    }

    private static @NotNull String createNonDigits() {
        StringBuilder refused = new StringBuilder();

        for (char character = ' '; character < KEY_DELETE; character++) {
            if (character < '0' || character > '9') {
                refused.append(character);
            }
        }

        return refused.toString();
    }

    private final @NotNull List<DialogControl> builtControls;
    private final @NotNull Map<Integer, String> labels;
    private final @NotNull Map<Integer, Tab> tabs;
    private final @NotNull Map<Integer, Option> options;
    private final @NotNull Map<Integer, List<String>> comboBoxItems;
    private final @NotNull Map<Integer, Integer> valueTexts;
    private final @NotNull Map<Integer, Slider> sliders;
    private final @NotNull Map<Integer, ComboBox> comboBoxes;
    /**
     * The live controls of the option table's ids (options, tab pages, value texts, tab buttons), bound once at open.
     */
    private final @NotNull Map<Integer, ScrControl> boundControls;

    private final @NotNull Set<Tab> shownTabs;
    private int nextIdentifier;
    private @NotNull Tab tab;

    /**
     * Creates an {@link OptionsPopup} instance.
     *
     * @param paletteFolder the palette folder of the screen below, for example {@code glue/palmm}
     */
    public OptionsPopup(@NotNull String paletteFolder) {
        super(DIALOG, paletteFolder, null);

        this.builtControls = new ArrayList<>();
        this.labels        = new HashMap<>();
        this.tabs          = new HashMap<>();
        this.options       = new HashMap<>();
        this.comboBoxItems = new HashMap<>();
        this.valueTexts    = new HashMap<>();
        this.sliders       = new HashMap<>();
        this.comboBoxes    = new HashMap<>();
        this.boundControls = new HashMap<>();

        this.shownTabs      = EnumSet.noneOf(Tab.class);
        this.nextIdentifier = FIRST_OPTION;
        this.tab            = Tab.SPEED;
    }

    /**
     * @param option the option
     * @return the option's current value in the page's form
     */
    private @NotNull Object getValue(@NotNull Option option) {
        Object stored = this.getContext().getSettings().getOption(option.key);

        if (stored == null) {
            return option.defaultValue;
        }

        return switch (option.key) {
            case VSYNC_KEY -> !VSYNC_OFF.equals(stored);
            case RENDERER_KEY -> !CLASSIC_RENDERER.equals(stored);
            case AUDIO_SET_KEY -> REMASTERED_AUDIO_SET.equals(stored);
            default -> stored;
        };
    }

    private boolean isEditControl(int identifier) {
        DialogControl control = ScrDialogControlUtils.findControl(this.getDialog(), identifier);

        return control != null && ScrDialogControlUtils.getType(control) == ScrControlType.EDITTEXT;
    }

    private boolean isRadioControl(int identifier) {
        DialogControl control = ScrDialogControlUtils.findControl(this.getDialog(), identifier);

        return control != null && ScrDialogControlUtils.getType(control) == ScrControlType.RADIOBUTTON;
    }


    private int addControl(@NotNull ScrControlType type, int left, int top, int width, int height, @NotNull String @NotNull ... flags) {
        int identifier = this.nextIdentifier++;

        this.builtControls.add(createControl(type, identifier, left, top, width, height, flags));

        return identifier;
    }

    /**
     * Loads the screen's dialog: {@code rez\<dialog name>.ui.json}, or an empty dialog without a name. A screen whose
     * native content is not a control list builds its dialog here.
     * <p>
     * The options popup keeps the loaded dialog's frame and replaces its controls with the available options and tabs.
     *
     * @param glueContext the shared services
     * @return the dialog
     * @throws IOException if the dialog cannot be read
     */
    @Override
    protected @NotNull DialogDefinition loadDialog(@NotNull GlueContext glueContext) throws IOException {
        DialogDefinition loaded = super.loadDialog(glueContext);
        List<DialogControl> controls = new ArrayList<>();

        controls.add(createControl(ScrControlType.CTEXT, TITLE, PANEL_LEFT, TITLE_TOP, PANEL_WIDTH, TITLE_HEIGHT, BIG_TEXT_FLAG));
        this.buildPage(glueContext);

        int tabRow = 0;

        for (Tab each : Tab.values()) {
            if (!this.shownTabs.contains(each)) {
                continue;
            }

            controls.add(createControl(ScrControlType.FLCBTN,
                                       FIRST_TAB + each.ordinal(),
                                       TAB_LEFT, BODY_TOP + tabRow++ * TAB_PITCH,
                                       TAB_WIDTH, TAB_HEIGHT,
                                       HORIZONTAL_CENTER_FLAG, VERTICAL_CENTER_FLAG, HOTKEY_FLAG));
        }

        controls.add(createControl(ScrControlType.FLCBTN,
                                   RESTORE,
                                   TAB_LEFT, BUTTON_TOP,
                                   RESTORE_WIDTH, BUTTON_HEIGHT,
                                   HORIZONTAL_CENTER_FLAG, VERTICAL_CENTER_FLAG, HOTKEY_FLAG, FRAMED_FLAG));
        controls.add(createControl(ScrControlType.FLCBTN,
                                   OK,
                                   PANEL_LEFT + PANEL_WIDTH - MARGIN - OK_WIDTH, BUTTON_TOP,
                                   OK_WIDTH, BUTTON_HEIGHT,
                                   ESCAPE_FLAG, ENTER_FLAG, HORIZONTAL_CENTER_FLAG, VERTICAL_CENTER_FLAG, HOTKEY_FLAG, FRAMED_FLAG));
        controls.addAll(this.builtControls);

        return new DialogDefinition(loaded.getName(), loaded.getX(), loaded.getY(), loaded.getWidth(), loaded.getHeight(), controls);
    }

    /**
     * Lists available controls in tab order under their client settings keys.
     *
     * @param glueContext the shared services
     */
    private void buildPage(@NotNull GlueContext glueContext) {
        Column speed = new Column(Tab.SPEED, CONTENT_LEFT, HALF_WIDTH);
        Column speedRight = new Column(Tab.SPEED, CONTENT_LEFT + HALF_WIDTH + COLUMN_GAP, HALF_WIDTH);

        speed.addSliderControl(PREFIX + "speed_label_gameSpeed", GAME_SPEED_KEY, 0, GameSpeed.FASTEST, 1, DEFAULT_GAME_SPEED, NO_CONTROL, SLOW, FAST);
        // Keyboard and pointer-edge scroll speeds select rows in the shared scroll step table.
        speed.addSliderControl(PREFIX + "speed_label_keyScroll",
                               "input.keyScroll",
                               0, InterfaceSettings.MAXIMUM_SCROLL_SPEED,
                               1,
                               InterfaceSettings.DEFAULT_SCROLL_SPEED,
                               NO_CONTROL,
                               SLOW, FAST);
        speed.addSliderControl(PREFIX + "speed_label_mouseScroll",
                               "input.mouseScroll",
                               0, InterfaceSettings.MAXIMUM_SCROLL_SPEED,
                               1,
                               InterfaceSettings.DEFAULT_SCROLL_SPEED,
                               NO_CONTROL,
                               SLOW, FAST);
        // The right column controls pointer sensitivity, scaling, hardware rendering and confinement.
        speedRight.addCheckedSlider(PREFIX + "speed_label_mouseSensitivity",
                                    "input.useMouseSensitivity", true,
                                    "input.mouseSensitivity",
                                    0, InterfaceSettings.MAXIMUM_MOUSE_SENSITIVITY,
                                    MOUSE_SENSITIVITY_STEP,
                                    PointerSpeed.DEFAULT_SENSITIVITY,
                                    SLOW, FAST);
        speedRight.beginSection();
        speedRight.addCheckBox(PREFIX + "speed_label_mouseScaling", "input.mouseScaling", false);
        speedRight.addCheckBox("optionsPanel_speed_hardwareCursor_label", "input.hardwareCursor", true);
        speedRight.addCheckBox("optionsPanel_speed_mouseConfine_label", "input.mouseConfine", true);
        this.finishColumn(speed);
        this.finishColumn(speedRight);

        // The left column contains enabled volume sliders; the right column contains playback and subtitle options.
        Column sound = new Column(Tab.SOUND, CONTENT_LEFT, HALF_WIDTH);
        Column soundChecks = new Column(Tab.SOUND, CONTENT_LEFT + HALF_WIDTH + COLUMN_GAP, HALF_WIDTH);

        sound.addCheckedSlider(PREFIX + "sound_label_musicVolume",
                               "audio.musicEnabled", true,
                               "audio.musicVolume",
                               0, SoundSettings.MAXIMUM_VOLUME,
                               VOLUME_STEP,
                               SoundSettings.DEFAULT_MUSIC_VOLUME,
                               MINIMUM, MAXIMUM);
        sound.addCheckedSlider(PREFIX + "sound_label_digitalVolume",
                               "audio.sfxEnabled", true,
                               "audio.sfxVolume",
                               0, SoundSettings.MAXIMUM_VOLUME,
                               VOLUME_STEP,
                               SoundSettings.DEFAULT_SOUND_EFFECTS_VOLUME,
                               MINIMUM, MAXIMUM);
        soundChecks.addCheckBox(PREFIX + "sound_label_unitSpeech", "audio.unitSpeech", true);
        soundChecks.addCheckBox(PREFIX + "sound_label_unitAck", "audio.unitAcknowledgments", true);
        soundChecks.addCheckBox(PREFIX + "sound_label_soundPlaysInBackground", "audio.soundInBackground", true);
        soundChecks.addCheckBox(PREFIX + "sound_label_buildingSounds", "audio.buildingSounds", true);
        soundChecks.addCheckBox(PREFIX + "sound_label_gameSubtitles", "audio.gameSubtitles", true);
        soundChecks.addCheckBox(PREFIX + "sound_label_cinematicSubtitles", "audio.cinematicSubtitles", true);
        soundChecks.addCheckBox(PREFIX + "sound_label_originalUnitVO", "audio.originalUnitVoices", false);
        soundChecks.addCheckBox(PREFIX + "sound_label_originalCampaignVO", "audio.originalCampaign", false);
        // The client's audio set, labeled with the remastered audio string of the text table.
        soundChecks.addCheckBox(PREFIX + "sound_label_remasteredAudio", AUDIO_SET_KEY, true);
        this.finishColumn(sound);
        this.finishColumn(soundChecks);

        Column video = new Column(Tab.VIDEO, CONTENT_LEFT, HALF_WIDTH);
        Column videoChecks = new Column(Tab.VIDEO, CONTENT_LEFT + HALF_WIDTH + COLUMN_GAP, HALF_WIDTH);

        // Display mode values follow GraphicsSettings and default to windowed fullscreen.
        video.addComboBoxControl(PREFIX + "video_label_windowMode",
                                 "graphics.windowMode",
                                 List.of(PREFIX + "video_label_windowMode_windowed",
                                         PREFIX + "video_label_windowMode_windowedFullscreen",
                                         PREFIX + "video_label_windowMode_fullscreen"),
                                 List.of(GraphicsSettings.WINDOWED, GraphicsSettings.WINDOWED_FULLSCREEN, GraphicsSettings.FULLSCREEN),
                                 GraphicsSettings.WINDOWED_FULLSCREEN);
        video.addCheckedSlider(PREFIX + "video_label_fpsCap",
                               FRAME_RATE_LIMIT_ENABLED_KEY, false,
                               "graphics.fpsLimit",
                               MINIMUM_FRAME_RATE_LIMIT, MAXIMUM_FRAME_RATE_LIMIT,
                               1,
                               GraphicsSettings.DEFAULT_FRAME_RATE_LIMIT,
                               MINIMUM_FRAME_RATE_CAPTION, MAXIMUM_FRAME_RATE_CAPTION);
        video.addSliderControl(PREFIX + "video_label_gamma",
                               "graphics.gamma",
                               GraphicsSettings.MINIMUM_GAMMA, GraphicsSettings.MAXIMUM_GAMMA,
                               1,
                               GraphicsSettings.DEFAULT_GAMMA,
                               NO_CONTROL,
                               MINIMUM, MAXIMUM);
        // The classic graphics filter selects one of four modes, with mode one as the default.
        video.addSliderControl("optionsPanel_video_Filtering_label",
                               "graphics.sdFilterMode",
                               0, MAXIMUM_SD_FILTER_MODE,
                               1,
                               DEFAULT_SD_FILTER_MODE,
                               NO_CONTROL,
                               "optionsPanel_video_Sharp_label", "optionsPanel_video_Filtered_label");
        videoChecks.addCheckBox(PREFIX + "video_label_vsync", VSYNC_KEY, false);
        videoChecks.addCheckBox(PREFIX + "video_label_hd", RENDERER_KEY, true);
        videoChecks.addCheckBox(PREFIX + "video_label_environmentEffects", "graphics.environmentEffects", true);
        videoChecks.addCheckBox(PREFIX + "video_label_realTimeLighting", HD_LIGHTING_KEY, true);
        videoChecks.addCheckBox(PREFIX + "video_label_smoothAnimations", "graphics.smoothTurning", true);
        videoChecks.addCheckBox(PREFIX + "video_label_shadowStacking", "graphics.shadowStacking", true);
        // The aspect-ratio option controls pillarboxing.
        videoChecks.addCheckBox(PREFIX + "video_label_aspect_ratio_letterbox", ClientSettings.ORIGINAL_ASPECT_RATIO_KEY, false);
        this.finishColumn(video);
        this.finishColumn(videoChecks);

        // Network latency presents the available low, high and extra-high settings.
        Column network = new Column(Tab.NETWORK, CONTENT_LEFT, CONTENT_WIDTH);

        if (network.hasOption(LATENCY_KEY)) {
            network.addDescription(PREFIX + "network_description");
            network.addRadioGroup(PREFIX + "network_subtitle_defaultLatency",
                                  LATENCY_KEY,
                                  List.of(PREFIX + "network_label_lowLat", PREFIX + "network_label_highLat", PREFIX + "network_label_extraHighLat"),
                                  List.of(LOW_LATENCY, HIGH_LATENCY, EXTRA_HIGH_LATENCY),
                                  LOW_LATENCY);
        }

        this.finishColumn(network);

        Column game = new Column(Tab.GAME, CONTENT_LEFT, HALF_WIDTH);
        Column gameRight = new Column(Tab.GAME, CONTENT_LEFT + HALF_WIDTH + COLUMN_GAP, HALF_WIDTH);
        List<String> localeNames = new ArrayList<>();
        List<Object> locales = new ArrayList<>();

        for (String locale : LOCALES) {
            if (!glueContext.getCanvas().getStorage().hasFile(LOCALE_FOLDER + locale + LOCALE_TEXT_FILE)) {
                continue;
            }

            localeNames.add(LANGUAGE_TEXT_PREFIX + locale);
            locales.add(locale);
        }

        int currentLocale = Math.max(0, locales.indexOf(glueContext.getCanvas().getLocale()));

        // The gameplay column contains timer, color, portrait and console-position settings.
        game.addCheckBox(PREFIX + "game_label_gameTimer", "game.gameTimer", false);
        game.addCheckBox(PREFIX + "game_label_colorCycling", "game.colorCycling", true);
        game.addRadioGroup(PREFIX + "game_label_portraits",
                           "game.unitPortraits",
                           List.of(PREFIX + "game_label_unitPortraits_animated",
                                   PREFIX + "game_label_unitPortraits_still",
                                   PREFIX + "game_label_unitPortraits_disable"),
                           List.of(InterfaceSettings.ANIMATED_PORTRAITS, InterfaceSettings.STILL_PORTRAITS, InterfaceSettings.NO_PORTRAITS),
                           InterfaceSettings.ANIMATED_PORTRAITS);
        game.addRadioGroup(PREFIX + "game_label_consolePosition",
                           "game.consoleSplit",
                           List.of(PREFIX + "game_label_consolePosition_leftCorner", PREFIX + "game_label_consolePosition_standard"),
                           List.of(true, false),
                           true);
        // The right column: Language and its description, then Actions Per Minute Tracking (the console APM and its alert, ApmAlert).
        gameRight.addComboBoxControl(PREFIX + "game_label_textLanguage", LOCALE_KEY, localeNames, locales, currentLocale);

        if (!gameRight.empty) {
            gameRight.addDescription(PREFIX + "game_language_description");
        }

        gameRight.beginSection();
        gameRight.addCheckBox(PREFIX + "game_label_apm", "game.showApm", false);

        int alertRowTop = gameRight.top;
        int apmAlert = gameRight.addCheckBox(PREFIX + "game_label_epm", "game.apmAlert", false, NO_CONTROL, 0, NUMBER_WIDTH + NUMBER_GAP);

        if (apmAlert != NO_CONTROL && gameRight.hasOption(APM_ALERT_THRESHOLD_KEY)) {
            int thresholdControl = this.addControl(ScrControlType.EDITTEXT,
                                                   gameRight.left + gameRight.width - NUMBER_WIDTH, alertRowTop,
                                                   NUMBER_WIDTH, ROW_HEIGHT,
                                                   SMALL_TEXT_FLAG);

            this.tabs.put(thresholdControl, gameRight.tab);
            this.options.put(thresholdControl,
                             new Option(gameRight.tab, APM_ALERT_THRESHOLD_KEY, ApmAlert.DEFAULT_THRESHOLD, 0, 1, apmAlert, List.of()));
        }

        gameRight.addCheckBox(PREFIX + "game_label_colorText", "game.apmAlertColor", false, apmAlert, SUB_OPTION_INDENT, 0);
        gameRight.addCheckBox(PREFIX + "game_label_playSound", "game.apmAlertSound", false, apmAlert, SUB_OPTION_INDENT, 0);
        this.finishColumn(game);
        this.finishColumn(gameRight);
    }

    private void finishColumn(@NotNull Column column) {
        if (!column.empty) {
            this.shownTabs.add(column.tab);
        }
    }

    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * The popup fills the texts, creates the sliders and combo boxes, shows every option's current value and shows the first tab.
     *
     * @param glueContext the shared services
     * @throws NullPointerException if an option's control is missing from the dialog
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) {
        GluText strings = glueContext.getGluText();

        ScrControl title = this.bindControl(TITLE);

        title.setReplacedText(strings.get(TITLE_TEXT_KEY));
        title.setHighlighted(true);

        for (Tab each : this.shownTabs) {
            this.bindControl(FIRST_TAB + each.ordinal()).setReplacedText(strings.get(TAB_TEXT_PREFIX + each.keyName));
        }

        this.bindControl(RESTORE).setReplacedText(strings.get(RESTORE_TEXT_KEY));
        this.bindControl(OK).setReplacedText(strings.get(OK_TEXT_KEY));

        for (Map.Entry<Integer, String> label : this.labels.entrySet()) {
            String text = strings.get(label.getValue());

            this.bindControl(label.getKey()).setReplacedText(text.isEmpty() ? label.getValue() : text);
        }

        for (Map.Entry<Integer, Option> entry : this.options.entrySet()) {
            int identifier = entry.getKey();
            Option option = entry.getValue();
            DialogControl control = ScrDialogControlUtils.findControl(this.getDialog(), identifier);

            if (control == null) {
                throw Exceptions.newNullPointerException();
            }

            if (ScrDialogControlUtils.getType(control) == ScrControlType.SCROLLBAR) {
                Slider slider = this.addSlider(identifier);

                slider.setRange(0, (Integer) option.values.getFirst());
                this.sliders.put(identifier, slider);
            } else if (ScrDialogControlUtils.getType(control) == ScrControlType.EDITTEXT) {
                // The number box takes text only once clicked (onPointerPressed).
                this.setEditText(identifier, String.valueOf(this.getValue(option)), NUMBER_DIGITS, NON_DIGITS);
                this.setFocusedEdit(null);
            } else if (ScrDialogControlUtils.getType(control) == ScrControlType.COMBOBOX) {
                ComboBox comboBox = this.addComboBox(identifier);
                List<String> items = this.comboBoxItems.getOrDefault(identifier, List.of());

                for (int index = 0; index < items.size(); index++) {
                    comboBox.addItem(strings.get(items.get(index)), index);
                }

                this.comboBoxes.put(identifier, comboBox);
            }
        }

        // The table's controls are bound once the widgets are in their places.
        this.bindTableControls();

        for (Map.Entry<Integer, Option> entry : this.options.entrySet()) {
            int identifier = entry.getKey();
            Option option = entry.getValue();

            if (this.isRadioControl(identifier)) {
                this.getBoundControl(identifier).setChecked(option.values.getFirst().equals(this.getValue(option)));
            } else if (!this.isEditControl(identifier)) {
                this.showValue(identifier, this.getValue(option));
            }
        }

        this.showTab(this.shownTabs.isEmpty() ? Tab.SPEED : this.shownTabs.iterator().next());
    }

    /**
     * Binds the live controls of the option table once at open: every option, tab page control, value text and tab button.
     */
    private void bindTableControls() {
        this.boundControls.clear();

        for (Integer identifier : this.options.keySet()) {
            this.boundControls.put(identifier, this.bindControl(identifier));
        }

        for (Integer identifier : this.tabs.keySet()) {
            this.boundControls.put(identifier, this.bindControl(identifier));
        }

        for (Integer identifier : this.valueTexts.values()) {
            this.boundControls.put(identifier, this.bindControl(identifier));
        }

        for (Tab each : this.shownTabs) {
            this.boundControls.put(FIRST_TAB + each.ordinal(), this.bindControl(FIRST_TAB + each.ordinal()));
        }
    }

    /**
     * @param identifier a control id of the option table
     * @return the control bound at open
     * @throws IllegalStateException if the screen is not open or the id is not one of the table's
     */
    private @NotNull ScrControl getBoundControl(int identifier) {
        return requireBound(this.boundControls.get(identifier));
    }

    /**
     * Shows a value on its control.
     *
     * @param identifier the control id
     * @param value      the value in the page's form
     */
    private void showValue(int identifier, @NotNull Object value) {
        Option option = this.options.get(identifier);
        Slider slider = this.sliders.get(identifier);
        ComboBox comboBox = this.comboBoxes.get(identifier);

        if (this.isEditControl(identifier)) {
            this.setEditText(identifier, String.valueOf(value), NUMBER_DIGITS, NON_DIGITS);
            this.setFocusedEdit(null);
        } else if (this.isRadioControl(identifier)) {
            // Every button of the group shows whether it holds the value.
            for (Map.Entry<Integer, Option> entry : this.options.entrySet()) {
                if (entry.getValue().key.equals(option.key) && this.isRadioControl(entry.getKey())) {
                    this.getBoundControl(entry.getKey()).setChecked(entry.getValue().values.getFirst().equals(value));
                }
            }
        } else if (slider != null && value instanceof Integer number) {
            slider.setValue((number - option.minimum) / option.step);
            this.showSliderValue(identifier);
        } else if (comboBox != null) {
            comboBox.select(option.values.indexOf(value));
        } else if (value instanceof Boolean checked) {
            this.getBoundControl(identifier).setChecked(checked);
        }
    }

    /**
     * Writes a slider's value above its track end: the speed name for Game Speed, the number otherwise.
     *
     * @param identifier the slider's control id
     */
    private void showSliderValue(int identifier) {
        Option option = this.options.get(identifier);
        Slider slider = this.sliders.get(identifier);
        Integer valueText = this.valueTexts.get(identifier);

        if (option == null || slider == null || valueText == null) {
            return;
        }

        int value = option.minimum + slider.getValue() * option.step;

        this.getBoundControl(valueText).setReplacedText(option.key.equals(GAME_SPEED_KEY)
                                                        ? this.getContext().getGluText().get(GluText.GAME_SPEED_KEYS.get(value))
                                                        : Integer.toString(value));
    }

    /**
     * Stores a changed value under the option's key.
     *
     * @param option the option
     * @param value  the value in the page's form
     */
    private void storeOptionValue(@NotNull Option option, @NotNull Object value) {
        GlueSettings settings = this.getContext().getSettings();
        Object stored = switch (option.key) {
            case VSYNC_KEY -> (Boolean) value ? VSYNC_ON : VSYNC_OFF;
            case RENDERER_KEY -> (Boolean) value
                                 ? HD2_RENDERER.equals(settings.getOption(option.key)) ? HD2_RENDERER : HD_RENDERER
                                 : CLASSIC_RENDERER;
            case AUDIO_SET_KEY -> (Boolean) value ? REMASTERED_AUDIO_SET : CLASSIC_AUDIO_SET;
            default -> value;
        };

        settings.setOption(option.key, stored);
    }

    /**
     * Shows a tab: its controls, the others hidden.
     *
     * @param shown the tab to show
     */
    private void showTab(@NotNull Tab shown) {
        this.tab = shown;

        for (Map.Entry<Integer, Tab> entry : this.tabs.entrySet()) {
            this.getBoundControl(entry.getKey()).setVisible(entry.getValue() == shown);
        }

        for (Tab each : this.shownTabs) {
            this.getBoundControl(FIRST_TAB + each.ordinal()).setHighlighted(each == shown);
        }

        this.updateEnabled();
    }

    private void updateEnabled() {
        for (Map.Entry<Integer, Option> entry : this.options.entrySet()) {
            Option option = entry.getValue();
            // A control is also disabled by the client's state (GlueSettings.isOptionEnabled): Game Speed in a multiplayer
            // game or a replay, Use Mouse Confine in full screen, Gamma outside WindowMode 2.
            boolean enabled = (option.enabledBy == NO_CONTROL || this.getBoundControl(option.enabledBy).isChecked()) &&
                              this.getContext().getSettings().isOptionEnabled(option.key);

            this.getBoundControl(entry.getKey()).setEnabled(enabled);

            Integer valueText = this.valueTexts.get(entry.getKey());

            if (valueText != null) {
                this.getBoundControl(valueText).setEnabled(enabled);
            }
        }
    }

    /**
     * Draws the panel image and then the controls in child order.
     * <p>
     * The options popup has no panel image: it first draws the heavy dim, the framed panel, its header rule and tab
     * divider under the controls.
     *
     * @param canvas the glue canvas
     * @throws IOException if an image cannot be read
     */
    @Override
    protected void drawControls(@NotNull GlueCanvas canvas) throws IOException {
        float left = this.getDialogLeft();
        float top = this.getDialogTop();

        canvas.fillRectangle(0, 0, canvas.getInterfaceWidth(), GlueCanvas.INTERFACE_HEIGHT, DIM_COLOR);

        String art = this.getPaletteFolder() + POPUP_PANEL_IMAGE;

        if (canvas.hasImage(art)) {
            GlueCanvas.Image panel = canvas.getImage(art);

            // The popup art is translucent; the panel is backed so that the screen below does not show through.
            canvas.fillRectangle(left + PANEL_LEFT + GlueCanvas.POPUP_PANEL_BORDER, top + PANEL_TOP + GlueCanvas.POPUP_PANEL_BORDER,
                                 PANEL_WIDTH - BOX_SIDES * GlueCanvas.POPUP_PANEL_BORDER, PANEL_HEIGHT - BOX_SIDES * GlueCanvas.POPUP_PANEL_BORDER,
                                 PANEL_BACKING_COLOR);
            canvas.drawPopupPanel(panel, left + PANEL_LEFT, top + PANEL_TOP, PANEL_WIDTH, PANEL_HEIGHT);
        }

        canvas.fillRectangle(left + PANEL_LEFT + MARGIN, top + RULE_TOP, PANEL_WIDTH - BOX_SIDES * MARGIN, RULE_THICKNESS, PANEL_RULE_COLOR);
        canvas.fillRectangle(left + DIVIDER_LEFT, top + BODY_TOP, RULE_THICKNESS, BODY_BOTTOM - BODY_TOP, PANEL_RULE_COLOR);
        super.drawControls(canvas);
    }

    /**
     * Returns whether a button or text field draws a frame behind it ({@link #drawControlFrame}).
     * <p>
     * The shown tab's button is framed too.
     *
     * @param control a {@code FLCBTN} or {@code EDITTEXT} control
     * @return {@code true} if the control has {@link #FRAMED_FLAG} or is the shown tab's button, {@code false} otherwise
     */
    @Override
    protected boolean isFramed(@NotNull DialogControl control) {
        return super.isFramed(control) || control.getId() == FIRST_TAB + this.tab.ordinal() && this.shownTabs.contains(this.tab);
    }

    /**
     * Draws the frame of a framed button or text field ({@link #FRAMED_FLAG}): the bar of a closed drop-down ({@link ComboBox#getSmallTextBarFrame},
     * the hovered bar while the pointer is on an enabled button) over the control's rectangle, its rounded ends at their size and its sides
     * stretched to the control's height, without a fill: a framed control looks as a drop-down beside it.
     * <p>
     * The options popup's buttons stand on its popup art (the panel is backed): their frame is the one the art gives its Ok button, stretched
     * over the control's rectangle; nothing when the palette has no popup art.
     *
     * @param canvas  the glue canvas
     * @param control a {@code FLCBTN} or {@code EDITTEXT} control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @param hovered whether the pointer is on the enabled control
     * @throws IOException if the popup art cannot be read
     */
    @Override
    protected void drawControlFrame(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top, boolean hovered) throws IOException {
        String art = this.getPaletteFolder() + POPUP_PANEL_IMAGE;

        if (canvas.hasImage(art)) {
            canvas.drawFramedBox(canvas.getImage(art), FRAME_REGION, FRAME_FILL, FRAME_BORDER, left, top, control.getWidth(), control.getHeight());
        }
    }

    /**
     * Draws one control.
     * <p>
     * The threshold box is drawn as a number box; other controls use the glue painter.
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
        if (ScrDialogControlUtils.getType(control) == ScrControlType.EDITTEXT) {
            this.drawNumberBox(canvas, control, left, top);

            return;
        }

        super.drawControl(canvas, control, left, top);
    }

    /**
     * Draws the threshold box with centered option-font digits and its caret, without an input-language mark.
     *
     * @param canvas  the glue canvas
     * @param control the threshold box's {@code EDITTEXT} control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IllegalStateException if the screen is not open
     */
    private void drawNumberBox(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) {
        boolean enabled = this.requireDialogState().requireControl(control.getId()).isEnabled();

        WebPanelStyleUtils.drawBox(canvas,
                                   left, top, control.getWidth(), control.getHeight(),
                                   NUMBER_RADIUS,
                                   enabled ? WebPanelStyleUtils.BORDER : WebPanelStyleUtils.DISABLED_BORDER, NO_FILL,
                                   true, true,
                                   true, true);
        WebPanelStyleUtils.drawCenteredLine(canvas,
                                            canvas.getControlFontSlot(control),
                                            this.getDrawnEditText(control),
                                            left + NUMBER_PADDING, top,
                                            control.getWidth() - BOX_SIDES * NUMBER_PADDING, control.getHeight(),
                                            GlueCanvas.FORMAT_LEFT,
                                            enabled ? WebPanelStyleUtils.TEXT : WebPanelStyleUtils.DISABLED_TEXT);
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
        if (control >= FIRST_TAB && control < FIRST_TAB + Tab.values().length) {
            Tab chosen = Tab.values()[control - FIRST_TAB];

            if (this.shownTabs.contains(chosen)) {
                this.showTab(chosen);
            }

            return;
        }

        switch (control) {
            case OK -> this.finish();
            case RESTORE -> this.confirmRestore();
            default -> this.updateOptionValue(control);
        }
    }

    /**
     * Called after the user changed the selection of a list or combo box, or the value of a slider.
     *
     * @param control the control id
     */
    @Override
    protected void onSelectionChanged(int control) {
        this.updateOptionValue(control);
    }

    /**
     * Stores the value of a changed control.
     *
     * @param control the control id
     */
    private void updateOptionValue(int control) {
        Option option = this.options.get(control);

        // A click on the number box only focuses it; its digits are stored as they are typed (onEditChanged).
        if (option == null || this.isEditControl(control)) {
            return;
        }

        Slider slider = this.sliders.get(control);
        ComboBox comboBox = this.comboBoxes.get(control);

        if (slider != null) {
            this.storeOptionValue(option, option.minimum + slider.getValue() * option.step);
            this.showSliderValue(control);
        } else if (comboBox != null) {
            int selection = comboBox.getSelection();

            if (selection >= 0 && selection < option.values.size()) {
                this.storeOptionValue(option, option.values.get(selection));
            }
        } else if (this.isRadioControl(control)) {
            this.storeOptionValue(option, option.values.getFirst());
            this.showValue(control, option.values.getFirst());
        } else {
            this.storeOptionValue(option, this.getBoundControl(control).isChecked());
        }

        this.updateEnabled();
    }

    /**
     * Called first for every press of the screen that no open popup or open combo box takes, while the screen takes input.
     * <p>
     * The number box (the APM threshold) takes the keyboard when it is pressed while enabled; a press elsewhere gives the keys back to the
     * controls' hotkeys. The press is never consumed, so the normal control press continues after the keyboard focus changed.
     *
     * @param x the interface x
     * @param y the interface y
     * @return {@code true} if the press was consumed, {@code false} otherwise
     * @throws IllegalStateException if the screen is not open
     */
    @Override
    protected boolean onPointerPressed(float x, float y) {
        EditControl focused = null;

        for (Map.Entry<Integer, Option> entry : this.options.entrySet()) {
            DialogControl control = ScrDialogControlUtils.findControl(this.getDialog(), entry.getKey());

            if (control == null || ScrDialogControlUtils.getType(control) != ScrControlType.EDITTEXT || entry.getValue().tab != this.tab ||
                !this.requireDialogState().requireControl(entry.getKey()).isEnabled()) {
                continue;
            }

            if (GlueCanvas.contains(x, y,
                                    this.getDialogLeft() + control.getX(), this.getDialogTop() + control.getY(),
                                    control.getWidth(), control.getHeight())) {
                focused = this.getBoundControl(entry.getKey()) instanceof EditControl edit ? edit : null;
            }
        }

        this.setFocusedEdit(focused);

        return false;
    }

    /**
     * Called after the text of an {@code EDITTEXT} changed.
     * <p>
     * The threshold box's digits are stored as they are typed.
     *
     * @param control the control id
     */
    @Override
    protected void onEditChanged(int control) {
        Option option = this.options.get(control);

        if (option == null) {
            return;
        }

        String text = this.getBoundControl(control) instanceof EditControl edit ? edit.getEditText().getText() : "";

        if (text.isEmpty() || !text.chars().allMatch(Character::isDigit)) {
            return;
        }

        this.storeOptionValue(option, Integer.parseInt(text));
    }

    /**
     * Asks {@code bnet119_optionsPanel_reset_<tab>_settings} for Restore to Default, then resets the shown tab.
     */
    private void confirmRestore() {
        GluText strings = this.getContext().getGluText();
        String question = strings.get(RESET_QUESTION_PREFIX + this.tab.keyName + RESET_QUESTION_SUFFIX);

        if (question.isEmpty()) {
            return;
        }

        String text = question + LINE_SEPARATOR + strings.get(RESTORE_EXPLANATION_KEY);

        this.showPopup(new GlueMessagePopup(GlueMessagePopup.Kind.OK_CANCEL,
                                            this.getPaletteFolder(),
                                            text,
                                            null,
                                            confirmed -> {
                                                if (confirmed) {
                                                    this.restoreDefaults();
                                                }
                                            }));
    }

    /**
     * Resets the shown tab's options to their known defaults.
     */
    private void restoreDefaults() {
        for (Map.Entry<Integer, Option> entry : this.options.entrySet()) {
            Option option = entry.getValue();

            if (option.tab != this.tab || UNKNOWN_DEFAULTS.contains(option.key)) {
                continue;
            }

            this.showValue(entry.getKey(), option.defaultValue);
            this.storeOptionValue(option, option.defaultValue);
        }

        this.updateEnabled();
    }
}
