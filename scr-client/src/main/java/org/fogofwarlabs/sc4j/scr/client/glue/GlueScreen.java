package org.fogofwarlabs.sc4j.scr.client.glue;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.dialog.Control;
import org.fogofwarlabs.sc4j.engine.client.dialog.DialogStack;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextEditKey;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextEditOperation;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.input.KeyModifier;
import org.fogofwarlabs.sc4j.engine.client.input.PointerButton;
import org.fogofwarlabs.sc4j.engine.client.scene.Scene;
import org.fogofwarlabs.sc4j.engine.resource.dialog.ControlKind;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.engine.resource.dialog.TextAlignment;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.*;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogImportUtils;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceTimers;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFonts;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Draws a glue dialog or popup over its palette background.
 * <p>
 * Image controls honor their anchors. Text controls use alignment and highlight colors. Flic buttons play the
 * selected video set, use separate pointer and text regions, animate hover colors and activate on pointer release.
 * Checkboxes toggle, radio buttons select their group, and sliders support stepping, paging and dragging.
 * Controls expose their string hotkeys and take Escape or Enter when configured.
 * <p>
 * The widgets are controls of the screen's dialog: the screen attaches a {@link ListBox}, {@link ComboBox}, {@link Slider} or
 * {@link EditControl} in place of the control its dialog built ({@link ScrDialog#attach}), and a {@link GlueFlicButton} for every
 * flic button at open; input finds them as the dialog's pressed, hovered or focused control objects. The screen is the dialog's
 * procedure painter ({@link ScrDialog#setPainter}): {@link #drawControl} draws a control by its type, and a widget draws itself.
 */
public abstract class GlueScreen implements Scene<GlueContext, GlueCanvas> {
    /**
     * The palette folder that the network, login and saved-game screens and the glue over a game share; it also holds the
     * dialog sheet of every glue screen ({@link #DIALOG_SHEET}).
     */
    public static final @NotNull String SHARED_PALETTE_FOLDER = "glue/palnl";
    /**
     * The shared sheet used by glue dialog controls, independent of the screen palette.
     */
    public static final @NotNull String DIALOG_SHEET = SHARED_PALETTE_FOLDER + "/dlg.dds.grp";
    /**
     * The popup panel image of a palette folder, appended to the folder.
     */
    public static final @NotNull String POPUP_PANEL_IMAGE = "/pdpopup.pcx";
    /**
     * The client flag (not a native dialog flag) of a button or text field that stands on a page without frame art: the screen draws a
     * frame behind it ({@link #drawControlFrame}).
     */
    public static final @NotNull String FRAMED_FLAG = "SC4J_FRAMED";
    /**
     * The height of a framed button or text field ({@link #FRAMED_FLAG}): the height of the create game dialog's control rows
     * ({@code gluCreat} drop-downs).
     */
    public static final int FRAMED_CONTROL_HEIGHT = 20;
    /**
     * The top of a page's content under the page's title ({@link #createPageTitle}): the local network game list's list starts {@code 32}
     * under the top of its title ({@code gluJoin}: the title at {@code 60}, the list at {@code 92}).
     */
    public static final int PAGE_CONTENT_TOP = 32;
    /**
     * The packed {@code 0xRRGGBBAA} color of the one-pixel rules of SC4J's own panels: under a panel's title and between its parts.
     */
    public static final int PANEL_RULE_COLOR = 0x8C0000FF;

    /**
     * The empty key code; the glue screens receive the characters and the editing and navigation key codes below from the window.
     */
    public static final char KEY_NONE = 0;
    /**
     * The Escape key code.
     */
    public static final char KEY_ESCAPE = 27;
    /**
     * The Enter key code.
     */
    public static final char KEY_ENTER = '\n';
    /**
     * The Space key code.
     */
    public static final char KEY_SPACE = ' ';
    /**
     * The Backspace key code.
     */
    public static final char KEY_BACKSPACE = '\b';
    /**
     * The Delete key code.
     */
    public static final char KEY_DELETE = 127;
    /**
     * The left arrow key code.
     */
    public static final char KEY_LEFT = '\uE000';
    /**
     * The right arrow key code.
     */
    public static final char KEY_RIGHT = '\uE001';
    /**
     * The Home key code.
     */
    public static final char KEY_HOME = '\uE002';
    /**
     * The End key code.
     */
    public static final char KEY_END = '\uE003';
    /**
     * The up arrow key code.
     */
    public static final char KEY_UP = '\uE004';
    /**
     * The down arrow key code.
     */
    public static final char KEY_DOWN = '\uE005';

    private static final int CENTER_DIVISOR = 2;
    private static final float CENTER_FLOAT_DIVISOR = 2f;
    private static final int FIRST_PRINTABLE = 0x20;
    private static final long INITIAL_MOUSE_OVER_TIME = Long.MIN_VALUE / 2;

    private static final int HIGHLIGHT_TICK_MILLISECONDS = 10;
    /**
     * The hover timer identifier of a flic button ({@code 0x48}).
     */
    private static final int FLIC_BUTTON_TIMER = 0x48;
    private static final int MOUSE_OVER_INTERVAL_MILLISECONDS = 200;
    private static final int POPUP_DIM_COLOR = 0x00000096;

    private static final @NotNull String ANCHOR_TOP_RIGHT_FLAG = "RC_ANCHOR_TOPRIGHT";
    private static final @NotNull String ANCHOR_CENTER_FLAG = "RC_ANCHOR_CENTER";
    private static final @NotNull String POPUP_BACKGROUND_FLAG = "RC_POPUP_BACKGROUND";
    private static final @NotNull String FLIC_HIGH_DEFINITION_FLAG = "RC_FLC_HD";
    private static final @NotNull String SCREEN_NOT_OPEN_MESSAGE = "The glue screen is not open.";
    private static final @NotNull String BACKGROUND_FILE = "/backgnd.DDS";
    /**
     * The text a text field measures its line height on.
     */
    private static final @NotNull String FIELD_LINE_SAMPLE = "A";
    /**
     * A page title's inset from the page's sides ({@link #createPageTitle}).
     */
    private static final int PAGE_TITLE_INSET = 4;
    private static final @NotNull String VISIBLE_FLAG = "WS_VISIBLE";
    private static final @NotNull String BIGGER_TEXT_FLAG = "RC_BIGGERTEXT";
    /**
     * The space between a framed text field's frame and its text: the edit popup's text box starts {@code 5} right of the field's frame in
     * its art.
     */
    private static final float FIELD_TEXT_INSET = 5;
    /**
     * An option check box ({@code CHECKBOX} with {@link WebPanelStyleUtils#FLAG}): the box's size, the mark's inset in it, the gap to the
     * label, and the box's colors (its border plain, hovered and disabled, and its fill).
     */
    private static final int CHECK_SIZE = 10;
    private static final int CHECK_MARK_INSET = 2;
    private static final int CHECK_TEXT_GAP = 7;
    private static final int CHECK_BORDER_COLOR = 0x8C0000FF;
    private static final int CHECK_BORDER_HOVERED_COLOR = 0xD02010FF;
    private static final int CHECK_BORDER_DISABLED_COLOR = 0x404040FF;
    private static final int CHECK_FILL_COLOR = 0x000000FF;
    private static final int BOX_SIDES = 2;

    /**
     * Exposes a screen or popup as a dialog layer, with each popup modal over its owner.
     */
    static final class ScreenLayer implements ScrDialogLayer {
        private final @NotNull GlueScreen screen;

        private ScreenLayer(@NotNull GlueScreen screen) {
            this.screen = screen;
        }

        /**
         * @return the dialog's runtime state
         * @throws IllegalStateException if the screen is not open
         */
        @Override
        public @NotNull ScrDialog getDialog() {
            return this.screen.requireDialogState();
        }

        /**
         * @return the dialog's left edge in interface pixels
         */
        @Override
        public float getLeft() {
            return this.screen.getDialogLeft();
        }

        /**
         * @return the dialog's top edge in interface pixels
         */
        @Override
        public float getTop() {
            return this.screen.getDialogTop();
        }

        /**
         * @return {@code true} if the dialog takes all input while open (the screen is a popup with an owner), {@code false} otherwise
         */
        @Override
        public boolean isModal() {
            return this.screen.owner != null;
        }

        /**
         * @return the screen or popup
         */
        @NotNull GlueScreen getScreen() {
            return this.screen;
        }
    }

    /**
     * Returns the background image path for a screen palette folder.
     *
     * @param paletteFolder the palette folder of the screen, for example {@code glue/palcs}
     * @return the background path
     */
    protected static @NotNull String getPaletteBackground(@NotNull String paletteFolder) {
        return paletteFolder + BACKGROUND_FILE;
    }

    /**
     * Places a control image using its right or center anchor in high-definition mode; classic mode uses the control origin.
     *
     * @param canvas  the glue canvas
     * @param control the control
     * @param image   the control's image
     * @param left    the control's left edge in interface pixels
     * @return the image's left edge in interface pixels
     */
    protected static float getImageLeft(@NotNull GlueCanvas canvas, @NotNull DialogControl control, GlueCanvas.@NotNull Image image, float left) {
        if (!canvas.isHighDefinition()) {
            return left;
        }

        // Narrow the image width to the integer placement used by anchored controls.
        int imageWidth = (int) image.getWidth();

        if (ScrDialogControlUtils.hasFlag(control, ANCHOR_TOP_RIGHT_FLAG)) {
            return left + control.getWidth() - imageWidth;
        }

        if (ScrDialogControlUtils.hasFlag(control, ANCHOR_CENTER_FLAG)) {
            return left - (imageWidth - control.getWidth()) / CENTER_DIVISOR;
        }

        return left;
    }

    /**
     * Returns a control a screen bound at open ({@link #bindControl}), for the screen's methods that run after the open.
     *
     * @param control the bound field
     * @param <T>     the control class
     * @return the control
     * @throws IllegalStateException if the screen is not open, so its controls are not bound
     */
    protected static <T> @NotNull T requireBound(@Nullable T control) {
        if (control == null) {
            throw Exceptions.newIllegalStateException(SCREEN_NOT_OPEN_MESSAGE);
        }

        return control;
    }

    /**
     * Creates the title of a page without art (the Beacon.Link pages): the screens' title text ({@code RC_BIGGERTEXT}) in a line of
     * {@link #FRAMED_CONTROL_HEIGHT} at the page's top, {@value #PAGE_TITLE_INSET} in from the page's sides. The page's content starts
     * {@link #PAGE_CONTENT_TOP} under the page's top.
     *
     * @param id   the title's control id
     * @param page the page
     * @return the title's definition
     */
    protected static @NotNull DialogControl createPageTitle(int id, @NotNull DialogControl page) {
        return ScrDialogControlUtils.createControl(ScrControlType.LTEXT,
                                                   id,
                                                   page.getX() + PAGE_TITLE_INSET, page.getY(),
                                                   page.getWidth() - BOX_SIDES * PAGE_TITLE_INSET, FRAMED_CONTROL_HEIGHT,
                                                   Set.of(VISIBLE_FLAG, BIGGER_TEXT_FLAG),
                                                   "",
                                                   List.of());
    }

    /**
     * Creates the definition of a detached control ({@link #bindControl}): the id, no size, no text, no flags (so not shown).
     */
    private static @NotNull DialogControl createDetachedDefinition(int control) {
        return new DialogControl(control, ControlKind.PANEL, 0, 0, 0, 0, null, TextAlignment.LEFT, null, Map.of(), List.of());
    }

    private final @NotNull String dialogName;
    private final @NotNull String paletteFolder;
    private final @Nullable String backgroundPath;

    private @Nullable GlueContext context;
    private @Nullable DialogDefinition dialog;
    private @Nullable GluePalette palette;
    private final @NotNull List<GlueFlicButton> flicButtons;
    private @Nullable ScrDialog runtime;
    private @Nullable EditControl focusedEdit;
    private final @NotNull List<ListBox> lists;
    private final @NotNull List<Slider> sliders;
    private @Nullable ListBox focusedList;
    private @Nullable ComboBox openComboBox;

    private long startTime;
    private long lastMouseOver;
    private long now;

    /**
     * The pointer's last interface position ({@link #handlePointerMoved}), where the wheel turns.
     */
    private float pointerX;
    private float pointerY;
    /**
     * Whether the press being handled is a double press ({@link #handlePointerPressed}).
     */
    private boolean doublePress;
    /**
     * The modifier mask ({@link KeyModifier#getMask()} bits) of the last key or button press handled ({@link #handleInput}).
     */
    private int modifiers;

    private @Nullable GlueScreen popup;
    private @Nullable GlueScreen owner;
    private final @NotNull ScreenLayer layer;

    private final @NotNull GlueSwish swish;
    private int @Nullable [] pendingSwish;
    private int pendingSwishDelay;
    /**
     * The listed panels that stay out while the others enter, kept until the slide starts.
     */
    private final @NotNull List<Integer> pendingHeldPanels;
    private @Nullable Consumer<GlueAudio.Sound> swishSounds;
    private @Nullable Runnable afterLeave;
    /**
     * What runs once a panel's slide out ends.
     */
    private @Nullable Runnable afterPanelOut;
    /**
     * The panel whose slide out {@link #afterPanelOut} waits for.
     */
    private int afterPanelOutControl;

    /**
     * Creates a {@link GlueScreen} instance.
     *
     * @param dialogName     the dialog file name, for example {@code gluMain}
     * @param paletteFolder  the palette folder of the screen, for example {@code glue/palmm}
     * @param backgroundPath the dialog image drawn centered on the screen, or {@code null} for none (popups)
     */
    protected GlueScreen(@NotNull String dialogName, @NotNull String paletteFolder, @Nullable String backgroundPath) {
        this.dialogName     = dialogName;
        this.paletteFolder  = paletteFolder;
        this.backgroundPath = backgroundPath;

        this.context      = null;
        this.dialog       = null;
        this.palette      = null;
        this.flicButtons  = new ArrayList<>();
        this.runtime      = null;
        this.focusedEdit  = null;
        this.lists        = new ArrayList<>();
        this.sliders      = new ArrayList<>();
        this.focusedList  = null;
        this.openComboBox = null;

        this.startTime     = -1;
        this.lastMouseOver = INITIAL_MOUSE_OVER_TIME;
        this.now           = 0;

        this.pointerX    = 0;
        this.pointerY    = 0;
        this.doublePress = false;
        this.modifiers   = 0;

        this.popup = null;
        this.owner = null;
        this.layer = new ScreenLayer(this);

        this.swish                = new GlueSwish();
        this.pendingSwish         = null;
        this.pendingSwishDelay    = 0;
        this.pendingHeldPanels    = new ArrayList<>();
        this.swishSounds          = null;
        this.afterLeave           = null;
        this.afterPanelOut        = null;
        this.afterPanelOutControl = 0;
    }

    /**
     * @return the palette folder of the screen, for example {@code glue/palnl}
     */
    public final @NotNull String getPaletteFolder() {
        return this.paletteFolder;
    }

    /**
     * @return {@code true} if the screen is a game dialog shown through the glue, whose text takes the game's font set, {@code false} otherwise
     */
    protected boolean isGameDialog() {
        return false;
    }

    /**
     * @return the dialog sheet the screen's buttons, lists and combo boxes draw from: the glue's {@link #DIALOG_SHEET}
     */
    protected @NotNull String getDialogSheet() {
        return DIALOG_SHEET;
    }

    /**
     * @return the shared services
     * @throws IllegalStateException if the screen is not open
     */
    protected final @NotNull GlueContext getContext() {
        if (this.context == null) {
            throw Exceptions.newIllegalStateException(SCREEN_NOT_OPEN_MESSAGE);
        }

        return this.context;
    }

    /**
     * @return the loaded dialog
     * @throws IllegalStateException if the screen is not open
     */
    protected final @NotNull DialogDefinition getDialog() {
        if (this.dialog == null) {
            throw Exceptions.newIllegalStateException(SCREEN_NOT_OPEN_MESSAGE);
        }

        return this.dialog;
    }

    /**
     * Returns the dialog runtime after checking that the screen is open.
     *
     * @return the active dialog state shared with the in-game dialog controls
     * @throws IllegalStateException if the screen is not open
     */
    protected final @NotNull ScrDialog requireDialogState() {
        if (this.runtime == null) {
            throw Exceptions.newIllegalStateException(SCREEN_NOT_OPEN_MESSAGE);
        }

        return this.runtime;
    }

    /**
     * @return the glue palette of the screen
     * @throws IllegalStateException if the screen is not open
     */
    protected final @NotNull GluePalette getPalette() {
        if (this.palette == null) {
            throw Exceptions.newIllegalStateException(SCREEN_NOT_OPEN_MESSAGE);
        }

        return this.palette;
    }

    /**
     * @return the time of the current update in milliseconds
     */
    protected final long getTime() {
        return this.now;
    }

    /**
     * @return the milliseconds since the screen's first update
     */
    protected final long getElapsed() {
        return this.startTime < 0 ? 0 : this.now - this.startTime;
    }

    /**
     * @param control an {@code EDITTEXT} definition of the dialog
     * @return {@code true} if the edit control of the definition's id has the keyboard focus, {@code false} otherwise
     */
    protected final boolean isFocusedEdit(@NotNull DialogControl control) {
        EditControl focused = this.focusedEdit;

        return focused != null && focused.getDefinition().getId() == control.getId();
    }

    /**
     * @return the edit control with the keyboard focus ({@link #setEditText}, {@link #setFocusedEdit}), or {@code null} if none has it
     */
    protected final @Nullable EditControl getFocusedEdit() {
        return this.focusedEdit;
    }

    /**
     * @param control a control definition of the dialog
     * @return the text of the edit control of the definition's id ({@link #setEditText}) as drawn: with the blinking caret string while it has
     * the keyboard focus ({@link EditText#getDrawnText}), or an empty string for another control
     * @throws IllegalStateException if the screen is not open
     */
    protected final @NotNull String getDrawnEditText(@NotNull DialogControl control) {
        EditControl edit = this.findControl(control.getId()) instanceof EditControl found ? found : null;

        return edit == null ? "" : edit.getEditText().getDrawnText(edit == this.focusedEdit);
    }

    /**
     * Finds the live control of an id, which the native dialog procedures name their controls by: for the draw and input paths that
     * walk the dialog's definitions. A screen writes control state through the objects it binds at open ({@link #bindControl}).
     *
     * @param control the control id
     * @return the control, or {@code null} if the dialog has none with the id
     * @throws IllegalStateException if the screen is not open
     */
    protected final @Nullable ScrControl findControl(int control) {
        return this.requireDialogState().findControl(control);
    }

    /**
     * Binds the live control of an id once at open: a screen keeps the control object in a field (or an array, {@link #bindControls})
     * and writes and reads its state through it instead of naming the id per use. The native dialog procedures also write to ids a
     * dialog file may lack; for such an id the screen gets a detached control of that id, which nothing draws or routes input to,
     * so its writes have no effect, as the native writes to a missing control.
     *
     * @param control the control id
     * @return the dialog's control of the id, or a detached control of the id if the dialog has none
     * @throws IllegalStateException if the screen is not open
     */
    protected final @NotNull ScrControl bindControl(int control) {
        ScrControl found = this.findControl(control);

        return found != null ? found : new ScrControl(createDetachedDefinition(control));
    }

    /**
     * Binds the live controls of consecutive ids once at open ({@link #bindControl}).
     *
     * @param first the first control id
     * @param count the number of controls
     * @return the controls of the ids {@code first .. first + count - 1} in order
     * @throws IllegalStateException if the screen is not open
     */
    protected final @NotNull ScrControl @NotNull [] bindControls(int first, int count) {
        ScrControl[] controls = new ScrControl[count];

        for (int index = 0; index < count; index++) {
            controls[index] = this.bindControl(first + index);
        }

        return controls;
    }

    /**
     * Binds the live controls of listed ids once at open ({@link #bindControl}).
     *
     * @param controls the control ids
     * @return the controls of the ids in order
     * @throws IllegalStateException if the screen is not open
     */
    protected final @NotNull ScrControl @NotNull [] bindControls(int @NotNull [] controls) {
        ScrControl[] bound = new ScrControl[controls.length];

        for (int index = 0; index < controls.length; index++) {
            bound[index] = this.bindControl(controls[index]);
        }

        return bound;
    }

    /**
     * @return the horizontal text inset of edit controls, {@code 0} here
     */
    protected int getEditTextInset() {
        return 0;
    }

    /**
     * @param canvas  the glue canvas
     * @param control an {@code EDITTEXT} control
     * @return the control's selected font slot
     */
    protected int getEditFontSlot(@NotNull GlueCanvas canvas, @NotNull DialogControl control) {
        return canvas.getControlFontSlot(control);
    }

    /**
     * @param control an {@code EDITTEXT} control
     * @return the packed color of its text, or {@code 0} for the dialog's color code {@code 2}
     */
    protected int getEditColor(@NotNull DialogControl control) {
        return 0;
    }

    /**
     * @param control an {@code EDITTEXT} control with {@link #FRAMED_FLAG}
     * @return the hint the text field shows in gray while it is empty, or an empty string for none
     */
    protected @NotNull String getEditHint(@NotNull DialogControl control) {
        return "";
    }

    /**
     * @param control an {@code EDITTEXT} control with {@link #FRAMED_FLAG}
     * @return the image of the pages' images ({@link GlueCanvas#getWebImage}) the text field shows left of its text, or an empty string for
     * none
     */
    protected @NotNull String getEditIcon(@NotNull DialogControl control) {
        return "";
    }

    /**
     * @param control an {@code EDITTEXT} control with {@link #FRAMED_FLAG}
     * @return the text the field shows before its text (the chat's channel or whisper target), or an empty string for none
     */
    protected @NotNull String getEditPrefix(@NotNull DialogControl control) {
        return "";
    }

    /**
     * @param control an {@code EDITTEXT} control with {@link #FRAMED_FLAG}
     * @return the packed color of the field's prefix ({@link #getEditPrefix})
     */
    protected int getEditPrefixColor(@NotNull DialogControl control) {
        return WebPanelStyleUtils.TEXT;
    }

    /**
     * @return the control under the pointer, or {@link ScrDialog#NONE}
     * @throws IllegalStateException if the screen is not open
     */
    protected final int getHoveredControl() {
        return this.requireDialogState().getHoveredId();
    }

    /**
     * @return {@code true} if the press {@link #onPointerPressed} handles is the second press of a double press, {@code false} otherwise
     */
    protected final boolean isDoublePress() {
        return this.doublePress;
    }

    /**
     * @return {@code true} if Shift is held with the press {@link #onPointerPressed} handles, which extends a text selection, {@code false}
     * otherwise
     */
    protected final boolean isExtendingPress() {
        return KeyModifier.SHIFT.isIn(this.modifiers);
    }

    /**
     * @return {@code true} if the controls take input (no slide runs and no leave is pending), {@code false} otherwise
     */
    protected final boolean isInteractive() {
        GlueSwish.Phase phase = this.swish.getPhase();

        return (phase == GlueSwish.Phase.NONE || phase == GlueSwish.Phase.LOCKED) && this.afterLeave == null;
    }

    /**
     * @param control the control
     * @return {@code true} if the control is drawn, {@code false} otherwise
     * @throws IllegalStateException if the screen is not open
     */
    protected final boolean isVisible(@NotNull DialogControl control) {
        return this.requireDialogState().isVisible(control);
    }

    /**
     * @param control the control
     * @return the control's string: the override, or its content resolved through the control strings
     * @throws IllegalStateException if the screen is not open
     */
    protected final @NotNull String getControlString(@NotNull DialogControl control) {
        return this.requireDialogState().getControlString(control, this.getContext().getControlText()::get);
    }

    /**
     * @param control the control
     * @return the control's drawn string: without the hotkey character of a {@code WS_HOTKEY} string
     * @throws IllegalStateException if the screen is not open
     */
    protected final @NotNull String getDrawnString(@NotNull DialogControl control) {
        return this.requireDialogState().getDrawnString(control, this.getContext().getControlText()::get);
    }

    /**
     * @return the dialog's left edge in interface pixels: centered on the screen
     */
    protected float getDialogLeft() {
        return (this.getContext().getCanvas().getInterfaceWidth() - this.getDialog().getWidth()) / CENTER_DIVISOR;
    }

    /**
     * @return the dialog's top edge in interface pixels: centered on the {@code 480} lines
     */
    protected float getDialogTop() {
        return (GlueCanvas.INTERFACE_HEIGHT - this.getDialog().getHeight()) / CENTER_FLOAT_DIVISOR;
    }

    /**
     * @return {@code true} if this popup asked to close, {@code false} otherwise
     */
    protected boolean isClosed() {
        return false;
    }

    /**
     * @return the screen as a dialog of the client's dialog list
     */
    final @NotNull ScreenLayer getLayer() {
        return this.layer;
    }

    /**
     * @return {@code true} if a popup is open over the screen, {@code false} otherwise
     */
    final boolean hasPopup() {
        return this.popup != null;
    }

    /**
     * @return the open popup, or {@code null}
     */
    final @Nullable GlueScreen getPopup() {
        return this.popup;
    }


    /**
     * Sets an {@code EDITTEXT} editable with an initial text and gives it the keyboard focus. The first call attaches an
     * {@link EditControl} in the control's place ({@link ScrDialog#attach}); later calls give that control the new text, so a screen
     * may bind the returned control once.
     *
     * @param control       the control id
     * @param text          the initial text
     * @param maximumLength the maximum number of characters
     * @param rejected      the characters the control refuses
     * @return the edit control
     * @throws IllegalStateException if the dialog has no such control
     */
    protected final @NotNull EditControl setEditText(int control, @NotNull String text, int maximumLength, @NotNull String rejected) {
        EditText edit = new EditText(text, maximumLength, rejected);
        DialogControl found = this.requireControl(control);

        // Attach the caret timer before replacing the existing edit control.
        edit.attach(this.getContext().getTimers(), this.now);

        EditControl attached;

        if (this.requireDialogState().requireControl(found) instanceof EditControl existing) {
            attached = existing;
            existing.setEditText(edit).detach();
        } else {
            attached = this.requireDialogState().attach(new EditControl(found, edit));
        }

        this.focusedEdit = attached;

        // Enable the input context for text and input-method composition.
        InputMethod.setTextInput(true);

        return attached;
    }

    /**
     * Sets the keyboard focus to an edit control of {@link #setEditText}, or takes it from every edit control (a page
     * whose edit box takes text only once it was clicked, the options page's APM threshold): the focused edit takes the
     * characters and the IME, the others reach the controls' hotkeys.
     *
     * @param edit the edit control, or {@code null} for none
     */
    protected final void setFocusedEdit(@Nullable EditControl edit) {
        this.focusedEdit = edit;

        InputMethod.setTextInput(edit != null);
    }

    /**
     * Sets the control and direction pairs for panels that slide in when the screen opens.
     *
     * @param entries pairs of control id and direction code ({@code 0} left, {@code 1} up, {@code 2} right, {@code 3} down)
     * @param delay   the delay in milliseconds before the panels move
     */
    protected final void setSwish(int @NotNull [] entries, int delay) {
        this.pendingSwish      = entries.clone();
        this.pendingSwishDelay = delay;
    }

    /**
     * Keeps a panel of {@link #setSwish} off-screen while the other panels slide in; {@link #slidePanelIn} brings it in.
     *
     * @param control the panel's control id
     */
    protected final void keepPanelOut(int control) {
        if (this.pendingSwish != null) {
            this.pendingHeldPanels.add(control);
        } else {
            this.swish.holdPanel(control);
        }
    }

    /**
     * Slides a panel of {@link #setSwish} in from where it is and drops the action a slide out of it would run. A panel in place stays.
     *
     * @param control the panel's control id
     */
    protected final void slidePanelIn(int control) {
        this.afterPanelOut = null;
        this.swish.startPanelEntering(control);
    }

    /**
     * Slides a panel of {@link #setSwish} out from where it is, then runs an action. Nothing slides or runs while the screen leaves.
     *
     * @param control  the panel's control id
     * @param afterOut what runs once the panel is out
     */
    protected final void slidePanelOut(int control, @NotNull Runnable afterOut) {
        if (this.afterLeave == null && this.swish.startPanelLeaving(control, requireBound(this.swishSounds))) {
            this.afterPanelOut        = afterOut;
            this.afterPanelOutControl = control;
        }
    }

    /**
     * @param control the control id of a panel of {@link #setSwish}
     * @return {@code true} if the panel is at least partly inside the screen, {@code false} otherwise
     */
    protected final boolean isPanelOnScreen(int control) {
        return this.swish.isPanelOnScreen(control);
    }

    /**
     * Makes a control move with a panel of {@link #setSwish}: it is drawn at the panel's offset from its place.
     *
     * @param control the control id
     * @param panel   the panel's control id
     */
    protected final void moveWithPanel(int control, int panel) {
        this.swish.attach(control, panel);
    }


    /**
     * Loads the dialog and palette and prepares the buttons. Called once by the glue flow before the first draw.
     *
     * @param glueContext the shared services
     * @throws IOException if the dialog or palette cannot be read
     */
    @Override
    public final void open(@NotNull GlueContext glueContext) throws IOException {
        this.context     = glueContext;
        this.dialog      = this.loadDialog(glueContext);
        this.swishSounds = glueContext.getAudio()::play;
        this.runtime     = new ScrDialog(this.dialog);
        this.palette     = this.loadPalette(glueContext);

        this.runtime.setPainter((control, x, y) -> this.drawControl(this.getContext().getCanvas(), control.getDefinition(), x, y));

        for (DialogControl control : this.dialog.getControls()) {
            if (ScrDialogControlUtils.getType(control) != ScrControlType.FLCBTN) {
                continue;
            }

            List<GlueVideo> videos = new ArrayList<>();

            for (DialogControl child : control.getChildren()) {
                if (ScrDialogControlUtils.getType(child) != ScrControlType.FLC || ScrDialogControlUtils.getContent(child).isEmpty()) {
                    continue;
                }

                GlueFlicLayout.Placement placement = ScrDialogControlUtils.hasFlag(child, FLIC_HIGH_DEFINITION_FLAG)
                                                     ? glueContext.getFlicLayout().findPlacement(ScrDialogControlUtils.getContent(child))
                                                     : null;

                videos.add(new GlueVideo(child, placement));
            }

            this.flicButtons.add(this.runtime.attach(new GlueFlicButton(control, videos)));
        }

        this.onOpen(glueContext);
    }

    /**
     * Loads the screen's dialog: {@code rez\<dialog name>.ui.json}, or an empty dialog without a name. A screen whose
     * native content is not a control list builds its dialog here.
     *
     * @param glueContext the shared services
     * @return the dialog
     * @throws IOException if the dialog cannot be read
     */
    protected @NotNull DialogDefinition loadDialog(@NotNull GlueContext glueContext) throws IOException {
        return this.dialogName.isEmpty() ? new DialogDefinition("", 0, 0, GlueCanvas.INTERFACE_WIDTH, GlueCanvas.INTERFACE_HEIGHT, List.of())
                                         : ScrDialogImportUtils.load(glueContext.getCanvas().getStorage(), this.dialogName);
    }

    /**
     * Loads the palette whose color codes the screen's controls draw their text in: the screen's palette folder.
     *
     * @param glueContext the shared services
     * @return the palette
     * @throws IOException if the palette cannot be read
     */
    protected @NotNull GluePalette loadPalette(@NotNull GlueContext glueContext) throws IOException {
        return GluePalette.load(glueContext.getCanvas().getStorage(), this.paletteFolder);
    }

    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     *
     * @param glueContext the shared services
     * @throws IOException if an asset cannot be read
     */
    protected void onOpen(@NotNull GlueContext glueContext) throws IOException {
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     *
     * @param control the control id
     */
    protected abstract void onActivate(int control);

    /**
     * Makes a {@code LISTBOX} control a list of this screen; the first one takes Up, Down and the wheel.
     *
     * @param control the control id
     * @return the list
     * @throws IllegalStateException if the dialog has no such control
     */
    protected final @NotNull ListBox addListBox(int control) {
        DialogControl found = this.requireControl(control);
        ScrControl replaced = this.requireDialogState().requireControl(found);
        ListBox list = this.requireDialogState().attach(new ListBox(found, this.getDialogSheet()));

        // Register list scroll repetition on the client timer list.
        list.setTimers(this.getContext().getTimers());

        if (replaced instanceof ListBox replacedList) {
            this.lists.remove(replacedList);
        }

        this.lists.add(list);

        if (this.focusedList == null || this.focusedList == replaced) {
            this.focusedList = list;
        }

        return list;
    }

    /**
     * Makes a {@code COMBOBOX} control a combo box of this screen.
     *
     * @param control the control id
     * @return the combo box
     * @throws IllegalStateException if the dialog has no such control
     */
    protected final @NotNull ComboBox addComboBox(int control) {
        DialogControl found = this.requireControl(control);

        return this.requireDialogState().attach(new ComboBox(found, this.getDialogSheet()));
    }

    /**
     * Makes a horizontal {@code SCROLLBAR} control a slider of this screen.
     *
     * @param control the control id
     * @return the slider
     * @throws IllegalStateException if the dialog has no such control
     */
    protected final @NotNull Slider addSlider(int control) {
        DialogControl found = this.requireControl(control);
        ScrControl replaced = this.requireDialogState().requireControl(found);
        Slider slider = this.requireDialogState().attach(new Slider(found));

        if (replaced instanceof Slider replacedSlider) {
            this.sliders.remove(replacedSlider);
        }

        this.sliders.add(slider);

        return slider;
    }

    private @NotNull DialogControl requireControl(int control) {
        DialogControl found = ScrDialogControlUtils.findControl(this.getDialog(), control);

        if (found == null) {
            throw Exceptions.newIllegalStateException("{0} has no control {1}.", this.dialogName, control);
        }

        return found;
    }

    /**
     * Called after the user changed the selection of a list or combo box, or the value of a slider.
     *
     * @param control the control id
     */
    protected void onSelectionChanged(int control) {
    }

    /**
     * Called after the text of an {@code EDITTEXT} changed.
     *
     * @param control the control id
     */
    protected void onEditChanged(int control) {
    }

    /**
     * Called when a pressable control that is not a button (a list box) is pressed.
     *
     * @param control the control id
     * @param x       the interface x
     * @param y       the interface y
     */
    protected void onPressed(int control, float x, float y) {
    }

    /**
     * Called first for every key of the screen (after an open popup): a screen without controls, such as a cinematic, takes its keys here.
     *
     * @param key the key
     * @return {@code true} if the key was consumed, {@code false} otherwise
     */
    protected boolean onKey(char key) {
        return false;
    }

    /**
     * Called when a navigation key ({@link #KEY_UP}, {@link #KEY_DOWN}, ...) is released, for a screen that follows held
     * keys (the credits page's scrolling).
     *
     * @param key the released key
     */
    protected void onKeyReleased(char key) {
    }

    /**
     * Called first for every press of the screen that no open popup or open combo box takes, while the screen takes input.
     *
     * @param x the interface x
     * @param y the interface y
     * @return {@code true} if the press was consumed, {@code false} otherwise
     */
    protected boolean onPointerPressed(float x, float y) {
        return false;
    }

    /**
     * Called for a press of the right button of the screen that no open popup or open combo box takes (the dialog's controls take
     * none): a page's context menu opens here.
     *
     * @param x the interface x
     * @param y the interface y
     */
    protected void onContextPressed(float x, float y) {
    }

    /**
     * Called for every pointer move of the screen (after an open combo box or popup takes it).
     *
     * @param x the interface x
     * @param y the interface y
     */
    protected void onPointerMoved(float x, float y) {
    }

    /**
     * Called for every release of the left button of the screen.
     *
     * @param x the interface x
     * @param y the interface y
     */
    protected void onPointerReleased(float x, float y) {
    }

    /**
     * Called first for a wheel notch of the screen.
     *
     * @param x    the pointer's interface x
     * @param y    the pointer's interface y
     * @param down {@code true} for a notch toward the user
     * @return {@code true} if the notch was consumed, {@code false} otherwise
     */
    protected boolean onWheel(float x, float y, boolean down) {
        return false;
    }

    /**
     * Called first for a copy (Ctrl+C or the edit menu's Copy): a screen whose read-only text (a chat log) holds a selection copies it here,
     * before the focused edit control.
     *
     * @return {@code true} if the copy was consumed, {@code false} otherwise
     */
    protected boolean onCopy() {
        return false;
    }

    /**
     * Called for {@link #KEY_UP} and {@link #KEY_DOWN} when no edit control takes them.
     *
     * @param key the key
     */
    protected void onNavigate(char key) {
    }

    /**
     * Hides controls and slides panels out before running the next action, or runs it immediately when no panels slide.
     *
     * @param next what follows, usually showing another screen
     */
    protected final void leave(@NotNull Runnable next) {
        if (this.afterLeave != null) {
            return;
        }

        this.afterPanelOut = null;

        if (this.swish.hasPanels()) {
            this.getContext().getAudio().stopSounds();
        }

        if (this.swish.startLeaving(requireBound(this.swishSounds))) {
            this.afterLeave = next;
        } else {
            next.run();
        }
    }

    /**
     * Opens a modal popup over this screen; the underlying hover clears on the next pointer move.
     *
     * @param screen the popup
     * @throws IllegalStateException if the popup's dialog, palette or assets cannot be read
     */
    protected final void showPopup(@NotNull GlueScreen screen) {
        try {
            screen.open(this.getContext());
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Glue popup {0}: {1}", screen.dialogName, exception.getMessage());
        }

        this.closePopup();

        this.popup   = screen;
        screen.owner = this;

        this.getContext().getNavigator().addPopup(screen);
        this.getContext().getDialogStack().push(screen.layer);
    }

    /**
     * Shows an error message in the glue's one error box ({@link #showError(String, Runnable)}), nothing following it.
     *
     * @param message the message
     */
    protected final void showError(@NotNull String message) {
        this.showError(message, () -> {
        });
    }

    /**
     * Shows an error in the shared centered message box and runs the callback when it closes.
     *
     * @param message the message
     * @param closed  what follows when the box closes
     */
    protected final void showError(@NotNull String message, @NotNull Runnable closed) {
        this.showPopup(new GlueMessagePopup(GlueMessagePopup.Kind.OK_SPLIT, this.getPaletteFolder(), message, null, confirmed -> closed.run()));
    }

    /**
     * Drops this popup from its owner once it asked to close ({@link #isClosed}), after an input it took.
     */
    final void dropIfClosed() {
        if (this.owner != null && this.owner.popup == this && this.isClosed()) {
            this.owner.closePopup();
        }
    }

    /**
     * Closes the popup, if one is open.
     */
    protected final void closePopup() {
        GlueScreen closed = this.popup;

        if (closed == null) {
            return;
        }

        this.popup = null;
        this.getContext().getNavigator().closePopup(closed);
        closed.owner = null;
        this.getContext().getDialogStack().remove(closed.layer);

        // A popup that took text turned the text input off; this screen's focused edit takes it again.
        this.restoreTextInput();
    }

    /**
     * Sets the window's text input to this screen's keyboard focus: on while an edit control has it, off otherwise. The left screen or popup
     * turns the text input off when it closes, which is after this screen opened and took the focus ({@link #setEditText}).
     */
    final void restoreTextInput() {
        InputMethod.setTextInput(this.focusedEdit != null);
    }

    /**
     * Updates the screen and its widgets at the supplied time; the glue's scene stack updates an open popup after it.
     *
     * @param time the time in milliseconds
     */
    @Override
    public final void update(long time) {
        if (this.startTime < 0) {
            this.startTime = time;

            // Arm each button timer once on the first update; the glue interface pumps the shared timer list.
            InterfaceTimers timers = this.getContext().getTimers();

            for (GlueFlicButton button : this.flicButtons) {
                timers.arm(button, FLIC_BUTTON_TIMER, HIGHLIGHT_TICK_MILLISECONDS, (int) time, tickTime -> button.tick());
            }
        }

        this.now = time;

        GlueCanvas canvas = this.getContext().getCanvas();

        if (this.pendingSwish != null) {
            this.swish.startEntering(this.getDialog(),
                                     this.pendingSwish,
                                     this.pendingSwishDelay,
                                     (int) this.getDialogLeft(), (int) canvas.getInterfaceWidth());
            this.pendingSwish = null;

            for (int control : this.pendingHeldPanels) {
                this.swish.holdPanel(control);
            }

            this.pendingHeldPanels.clear();
        }

        this.swish.update(time, (int) canvas.getInterfaceWidth(), requireBound(this.swishSounds), this.getContext().getTimers());

        if (this.afterLeave != null && this.swish.getPhase() == GlueSwish.Phase.GONE) {
            Runnable next = this.afterLeave;

            this.afterLeave = null;
            next.run();
        }

        if (this.afterPanelOut != null && !this.swish.isPanelMoving(this.afterPanelOutControl)) {
            Runnable next = this.afterPanelOut;

            this.afterPanelOut = null;
            next.run();
        }

        for (ListBox list : this.lists) {
            list.update(canvas);
        }

        this.onUpdate(time);
    }

    /**
     * Called by {@link #update} for screen-specific timers.
     *
     * @param time the time in milliseconds
     */
    protected void onUpdate(long time) {
    }

    /**
     * Returns the image drawn centered behind the dialog: the one the screen was created with.
     *
     * @return the image path, or {@code null} for none (popups)
     */
    protected @Nullable String getBackgroundPath() {
        return this.backgroundPath;
    }

    /**
     * Draws the screen; the glue's scene stack draws an open popup over it.
     *
     * @param canvas the glue canvas
     * @throws IOException if an image cannot be read
     */
    @Override
    public void draw(@NotNull GlueCanvas canvas) throws IOException {
        // The shared controls draw their text in the color codes of this screen's palette and in its font set.
        canvas.setPalette(this.getPalette());
        canvas.setGameFontsActive(this.isGameDialog());

        String backgroundImage = this.getBackgroundPath();

        if (backgroundImage != null) {
            GlueCanvas.Image background = canvas.getImage(backgroundImage);

            canvas.drawImage(background,
                             (canvas.getInterfaceWidth() - background.getWidth()) / CENTER_DIVISOR,
                             (GlueCanvas.INTERFACE_HEIGHT - background.getHeight()) / CENTER_DIVISOR);
        }

        this.drawControls(canvas);

        if (this.openComboBox != null) {
            DialogControl control = this.openComboBox.getDefinition();

            this.openComboBox.drawList(canvas, this.getDialogLeft() + control.getX(), this.getDialogTop() + control.getY());
        }

        if (this.owner != null) {
            // What the glue draws after a popup (the next popup sets its own, the loading panel) takes the glue's font set again.
            canvas.setGameFontsActive(false);
        }
    }

    /**
     * Draws the controls in child order.
     *
     * @param canvas the glue canvas
     * @throws IOException           if an image cannot be read
     * @throws IllegalStateException if the screen is not open
     */
    protected void drawControls(@NotNull GlueCanvas canvas) throws IOException {
        float dialogLeft = this.getDialogLeft();
        float dialogTop = this.getDialogTop();
        List<DialogControl> controls = this.getDialog().getControls();

        for (int index = 0; index < controls.size(); index++) {
            DialogControl control = controls.get(index);

            if (!this.isVisible(control) || this.swish.isControlHidden(this.getDialog(), index)) {
                continue;
            }

            int identifier = control.getId();
            ScrControl live = this.requireDialogState().requireControl(control);

            this.drawControl(canvas,
                             control,
                             dialogLeft + control.getX() + live.getShift() + this.swish.getOffsetX(identifier),
                             dialogTop + control.getY() + live.getVerticalShift() + this.swish.getOffsetY(identifier));
        }
    }

    /**
     * Draws one control.
     *
     * @param canvas  the glue canvas
     * @param control the control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IOException           if an image cannot be read
     * @throws IllegalStateException if the screen is not open
     */
    protected void drawControl(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) throws IOException {
        this.getPalette();

        switch (ScrDialogControlUtils.getType(control)) {
            case RECT -> {
                if (ScrDialogControlUtils.hasFlag(control, POPUP_BACKGROUND_FLAG)) {
                    // Dim the full interface behind a popup background control.
                    canvas.fillRectangle(0, 0, canvas.getInterfaceWidth(), GlueCanvas.INTERFACE_HEIGHT, POPUP_DIM_COLOR);

                    return;
                }

                String content = ScrDialogControlUtils.getContent(control);

                if (!canvas.hasImage(content)) {
                    return;
                }

                GlueCanvas.Image image = canvas.getImage(content);

                canvas.drawImage(image, getImageLeft(canvas, control, image, left), top);
            }
            case LTEXT, CTEXT, RTEXT -> {
                // Select highlighted or normal text colors from the current palette.
                int identifier = control.getId();
                boolean enabled = this.requireDialogState().requireControl(identifier).isEnabled();
                int code = this.requireDialogState().requireControl(identifier).isHighlighted() ? DialogPainter.CODE_WHITE : DialogPainter.CODE_TEXT;
                // Apply a screen-specific text format when present: a horizontal one keeps the type's top line, a label beside a
                // control centers its line on the row (ControlPainterUtils.ROW_TEXT_FORMAT).
                Integer override = this.requireDialogState().requireControl(identifier).getTextFormat();
                int format = override == null ? ControlPainterUtils.getTextFormat(control)
                                              : (override & GlueCanvas.FORMAT_MIDDLE) != 0 ? override : override | GlueCanvas.FORMAT_TOP;

                ControlPainterUtils.drawText(canvas, control, left, top, this.getDrawnString(control), enabled, code, format);
            }
            case FLCBTN -> this.drawFlicButton(canvas, control, left, top);
            case EDITTEXT -> this.drawEditText(canvas, control, left, top);
            case LISTBOX, COMBOBOX, SCROLLBAR -> {
                // An attached list, combo box or slider draws itself; a plain one draws nothing.
                ScrControl live = this.requireDialogState().requireControl(control);

                if (live instanceof ListBox || live instanceof ComboBox || live instanceof Slider) {
                    live.drawAt(canvas, left, top);
                }
            }
            case RADIOBUTTON -> {
                if (ScrDialogControlUtils.hasFlag(control, WebPanelStyleUtils.FLAG)) {
                    // An option radio button (the options' and the pages' round button), which the dialog sheet has no frame for.
                    WebPanelStyleUtils.drawRadioButton(canvas,
                                                       this.getLabelFontSlot(canvas, control),
                                                       this.getDrawnString(control),
                                                       left, top,
                                                       control.getWidth(), control.getHeight(),
                                                       this.requireDialogState().requireControl(control.getId()).isChecked(),
                                                       this.requireDialogState().isEnabled(control));
                } else {
                    this.drawButton(canvas, control, left, top);
                }
            }
            case CHECKBOX -> {
                if (ScrDialogControlUtils.hasFlag(control, WebPanelStyleUtils.FLAG)) {
                    this.drawOptionCheckBox(canvas, control, left, top);
                } else {
                    this.drawButton(canvas, control, left, top);
                }
            }
            case PUSHBUTTON, DEFPUSHBUTTON -> this.drawButton(canvas, control, left, top);
            default -> {
            }
        }
    }

    /**
     * Draws an option check box ({@code CHECKBOX} with {@link WebPanelStyleUtils#FLAG}), which the dialog sheet has no frame for: a small box
     * framed in red (brighter while hovered, gray when disabled) holding the palette's highlight while checked, and the label beside it.
     *
     * @param canvas  the glue canvas
     * @param control the control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IllegalStateException if the screen is not open
     */
    private void drawOptionCheckBox(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) {
        int identifier = control.getId();
        ScrControl live = this.requireDialogState().requireControl(identifier);
        boolean enabled = live.isEnabled();
        boolean hovered = enabled && identifier == this.getHoveredControl();
        GluePalette shownPalette = this.getPalette();
        float boxTop = top + (control.getHeight() - ControlPainterUtils.ROW_TEXT_BOTTOM_INSET - CHECK_SIZE) / CENTER_FLOAT_DIVISOR;

        canvas.fillRectangle(left, boxTop, CHECK_SIZE, CHECK_SIZE, CHECK_FILL_COLOR);
        canvas.drawRectangle(left, boxTop, CHECK_SIZE, CHECK_SIZE, GlueCanvas.OUTLINE_THICKNESS,
                             !enabled ? CHECK_BORDER_DISABLED_COLOR : hovered ? CHECK_BORDER_HOVERED_COLOR : CHECK_BORDER_COLOR);

        if (live.isChecked()) {
            int markColor = shownPalette.getCodeColor(enabled ? DialogPainter.CODE_HIGHLIGHT : DialogPainter.CODE_DISABLED);

            canvas.fillRectangle(left + CHECK_MARK_INSET, boxTop + CHECK_MARK_INSET,
                                 CHECK_SIZE - BOX_SIDES * CHECK_MARK_INSET, CHECK_SIZE - BOX_SIDES * CHECK_MARK_INSET,
                                 markColor);
        }

        int code = !enabled ? DialogPainter.CODE_DISABLED : hovered ? DialogPainter.CODE_WHITE : DialogPainter.CODE_TEXT;
        float textLeft = left + CHECK_SIZE + CHECK_TEXT_GAP;

        canvas.drawText(this.getLabelFontSlot(canvas, control),
                        this.getDrawnString(control),
                        textLeft, top,
                        control.getWidth() - (textLeft - left), control.getHeight(),
                        ControlPainterUtils.ROW_TEXT_FORMAT,
                        shownPalette.getCodeColor(code),
                        shownPalette);
    }

    /**
     * @return the font slot of an option radio button or check box: the one the screen set on the control, or the control's selected one
     */
    private int getLabelFontSlot(@NotNull GlueCanvas canvas, @NotNull DialogControl control) {
        Integer slot = this.requireDialogState().requireControl(control.getId()).getFontSlot();

        return slot != null ? slot : canvas.getControlFontSlot(control);
    }

    /**
     * Draws a button, checkbox or radio button using the shared dialog painter and its selected frame style.
     *
     * @param canvas  the glue canvas
     * @param control the control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IOException           if an image cannot be read
     * @throws IllegalStateException if the screen is not open
     */
    private void drawButton(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) throws IOException {
        int identifier = control.getId();
        boolean threeSlice = this.requireDialogState().requireControl(identifier).hasButtonStyle() ||
                             (ScrDialogControlUtils.getFlagBits(control) & ControlPainterUtils.THREE_SLICE_STYLE) != 0;

        ControlPainterUtils.draw(canvas,
                                 this.requireDialogState(),
                                 control,
                                 left, top,
                                 this.getDialogSheet(),
                                 this.getDrawnString(control),
                                 this.requireDialogState().getHoveredId(),
                                 this.requireDialogState().requireControl(identifier).getButtonFrame(ControlPainterUtils.BUTTON_TILE_FRAME),
                                 threeSlice);
    }

    /**
     * Draws an edit using the control font, caret-following scroll and screen-specific text inset/color. A framed edit
     * ({@link #FRAMED_FLAG}) draws its frame first and its line inside it ({@link #drawFramedEditText}).
     *
     * @param canvas  the glue canvas
     * @param control an {@code EDITTEXT} control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IOException if the dialog sheet or the field's icon cannot be read
     */
    private void drawEditText(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) throws IOException {
        if (!(this.requireDialogState().requireControl(control) instanceof EditControl edit)) {
            return;
        }

        if (this.isFramed(control)) {
            this.drawFramedEditText(canvas, control, edit, left, top);

            return;
        }

        int inset = this.getEditTextInset();

        ControlPainterUtils.drawEditText(canvas,
                                         this.getEditFontSlot(canvas, control),
                                         edit.getEditText(),
                                         EditText.ScrollMode.CARET,
                                         left + inset, top,
                                         left + control.getWidth() - 1 - inset, control.getHeight(),
                                         canvas.getInterfaceWidth() - 1,
                                         edit == this.focusedEdit,
                                         DialogPainter.CODE_TEXT,
                                         this.getEditColor(control));
    }

    /**
     * Draws a framed text field: the frame on the control's rectangle ({@link #drawControlFrame}), the icon at its left (a square of the
     * text's line), the prefix, then the hint in the disabled color while the field is empty and the text, all one line centered vertically
     * inside the frame and right of the icon and the prefix.
     *
     * @param canvas  the glue canvas
     * @param control an {@code EDITTEXT} control with {@link #FRAMED_FLAG}
     * @param edit    the control's edit control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IOException if the dialog sheet or the icon cannot be read
     */
    private void drawFramedEditText(@NotNull GlueCanvas canvas, @NotNull DialogControl control, @NotNull EditControl edit, float left, float top) throws IOException {
        int slot = this.getEditFontSlot(canvas, control);
        int lineSlot = InterfaceFonts.toInputLineBox(slot);
        float line = canvas.getLineHeight(lineSlot, FIELD_LINE_SAMPLE);
        float textTop = top + (float) Math.floor((control.getHeight() - line) / CENTER_FLOAT_DIVISOR);
        float textLeft = left + FIELD_TEXT_INSET;
        float textRight = left + control.getWidth() - 1 - FIELD_TEXT_INSET;
        String hint = this.getEditHint(control);
        String icon = this.getEditIcon(control);
        String prefix = this.getEditPrefix(control);

        this.drawControlFrame(canvas, control, left, top, false);

        if (!icon.isEmpty()) {
            float iconSize = (float) Math.floor(line);

            canvas.drawImage(canvas.getWebImage(icon), textLeft, top + (control.getHeight() - iconSize) / CENTER_FLOAT_DIVISOR, iconSize, iconSize);
            textLeft += iconSize + FIELD_TEXT_INSET;
        }

        if (!prefix.isEmpty()) {
            WebPanelStyleUtils.drawText(canvas,
                                        lineSlot,
                                        prefix,
                                        textLeft, textTop,
                                        textRight - textLeft + 1, line,
                                        GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                        this.getEditPrefixColor(control));
            textLeft += canvas.measure(lineSlot, prefix);
        }

        if (!hint.isEmpty() && edit.getEditText().getText().isEmpty()) {
            // The hint leaves the input-language mark its room at the right.
            float markRoom = canvas.measure(lineSlot, ControlPainterUtils.getInputLanguageMark(InputMethod.getInputLanguage())) + FIELD_TEXT_INSET;

            canvas.drawCodeText(lineSlot,
                                hint,
                                textLeft, textTop,
                                textRight - textLeft + 1 - markRoom, line,
                                GlueCanvas.FORMAT_LEFT | GlueCanvas.FORMAT_TOP,
                                DialogPainter.CODE_DISABLED);
        }

        ControlPainterUtils.drawEditText(canvas,
                                         slot,
                                         edit.getEditText(),
                                         EditText.ScrollMode.CARET,
                                         textLeft, textTop,
                                         textRight, line,
                                         canvas.getInterfaceWidth() - 1,
                                         edit == this.focusedEdit,
                                         DialogPainter.CODE_TEXT,
                                         this.getEditColor(control));
    }

    /**
     * Draws the frame of a framed button or text field ({@link #FRAMED_FLAG}): the bar of a closed drop-down ({@link ComboBox#getSmallTextBarFrame},
     * the hovered bar while the pointer is on an enabled button) over the control's rectangle, its rounded ends at their size and its sides
     * stretched to the control's height ({@link GlueCanvas#drawBar(String, int, float, float, float, float, int)}), without a fill: a framed
     * control looks as a drop-down beside it.
     *
     * @param canvas  the glue canvas
     * @param control a {@code FLCBTN} or {@code EDITTEXT} control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @param hovered whether the pointer is on the enabled control
     * @throws IOException if the dialog sheet cannot be read
     */
    protected void drawControlFrame(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top, boolean hovered) throws IOException {
        String sheet = this.getDialogSheet();

        canvas.drawBar(sheet,
                       ComboBox.getSmallTextBarFrame(hovered),
                       left, top,
                       control.getWidth(), control.getHeight(),
                       ComboBox.getSmallTextBarTile(canvas, sheet));
    }

    /**
     * Returns whether a button or text field draws a frame behind it ({@link #drawControlFrame}).
     *
     * @param control a {@code FLCBTN} or {@code EDITTEXT} control
     * @return {@code true} if the control has {@link #FRAMED_FLAG}, {@code false} otherwise
     */
    protected boolean isFramed(@NotNull DialogControl control) {
        return ScrDialogControlUtils.hasFlag(control, FRAMED_FLAG);
    }

    /**
     * Draws a flic button: the videos of the current definition mode (a hover video only while the button is hovered,
     * rewound otherwise) and the label at the button's hover level.
     *
     * @param canvas  the glue canvas
     * @param control an {@code FLCBTN} control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IllegalStateException if the screen is not open
     */
    private void drawFlicButton(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) throws IOException {
        GlueFlicButton button = this.requireDialogState().requireControl(control) instanceof GlueFlicButton flicButton ? flicButton : null;
        boolean hovered = control.getId() == this.requireDialogState().getHoveredId();

        if (this.isFramed(control)) {
            this.drawControlFrame(canvas, control, left, top, hovered && this.requireDialogState().requireControl(control).isEnabled());
        }

        if (button != null) {
            boolean highDefinition = canvas.isHighDefinition();
            int index = 0;

            for (DialogControl child : control.getChildren()) {
                if (ScrDialogControlUtils.getType(child) != ScrControlType.FLC || ScrDialogControlUtils.getContent(child).isEmpty()) {
                    continue;
                }

                GlueVideo video = button.getVideos().get(index++);

                if (ScrDialogControlUtils.hasFlag(child, FLIC_HIGH_DEFINITION_FLAG) != highDefinition) {
                    continue;
                }

                if (video.isMouseOver() && !hovered) {
                    video.resetTime(this.now);

                    continue;
                }

                video.draw(canvas, left, top, this.now);
            }
        }

        // Keep a highlighted button at full hover level.
        float level = this.requireDialogState().requireControl(control.getId()).isHighlighted()
                      ? 1
                      : button == null ? 0 : button.getHoverLevel();

        ControlPainterUtils.drawFlicButtonLabel(canvas,
                                                control,
                                                left, top,
                                                this.getDrawnString(control),
                                                this.requireDialogState().isEnabled(control),
                                                level);
    }

    /**
     * Handles one input event the glue's scene stack hands to this screen or popup ({@link GlueInterface#handleInput}, pointer positions in
     * interface coordinates). A key press or repeat runs the edit operation of the focused edit control ({@link TextEditOperation}: Ctrl+A,
     * X, C, V, Z, Ctrl+Shift+Z) or types a glue key ({@link #toGlueKey}); a key release releases a navigation key; a character is typed;
     * pointer movements, left presses and releases and the wheel go to the dialog; a right press goes to {@link #onContextPressed}.
     *
     * @param event the event
     * @return {@code true}: the glue uses every event
     * @throws IllegalStateException if the screen is not open
     */
    @Override
    public final boolean handleInput(@NotNull InputEvent event) {
        switch (event.getType()) {
            case KEY_PRESSED, KEY_REPEATED, KEY_RELEASED -> {
                this.modifiers = event.getModifiers();
                this.handleKey(event);
            }
            case CHARACTER_TYPED -> {
                if (Character.isBmpCodePoint(event.getCodePoint())) {
                    this.handleKeyTyped((char) event.getCodePoint());
                    this.dropIfClosed();
                }
            }
            case POINTER_MOVED -> this.handlePointerMoved(event.getX(), event.getY());
            case BUTTON_PRESSED -> {
                if (event.getButton() == PointerButton.RIGHT) {
                    if (this.openComboBox != null) {
                        this.closeComboBox();
                    } else if (this.isInteractive()) {
                        this.onContextPressed(event.getX(), event.getY());
                    }
                } else {
                    this.modifiers = event.getModifiers();
                    this.handlePointerPressed(event.getX(), event.getY(), event.isDoublePress());
                }
            }
            case BUTTON_RELEASED -> {
                this.handlePointerReleased(event.getX(), event.getY());
                this.dropIfClosed();
            }
            case WHEEL_SCROLLED -> {
                if (event.getVerticalOffset() != 0) {
                    this.handleWheel(event.getVerticalOffset() < 0);
                }
            }
        }

        return true;
    }

    /**
     * Maps a window key event to a glue key: Esc and Enter when pressed, the editing and list keys when pressed or repeated.
     *
     * @param key  the key
     * @param type the event type: press, repeat or release
     * @return the glue key, or {@link #KEY_NONE}
     */
    private static char toGlueKey(@NotNull Key key, InputEvent.@NotNull Type type) {
        if (type == InputEvent.Type.KEY_RELEASED) {
            return KEY_NONE;
        }

        boolean pressed = type == InputEvent.Type.KEY_PRESSED;

        return switch (key) {
            case ESCAPE -> pressed ? KEY_ESCAPE : KEY_NONE;
            case ENTER, KEYPAD_ENTER -> pressed ? KEY_ENTER : KEY_NONE;
            case BACKSPACE -> KEY_BACKSPACE;
            case DELETE -> KEY_DELETE;
            case LEFT -> KEY_LEFT;
            case RIGHT -> KEY_RIGHT;
            case HOME -> KEY_HOME;
            case END -> KEY_END;
            case UP -> KEY_UP;
            case DOWN -> KEY_DOWN;
            default -> KEY_NONE;
        };
    }

    private void handleKey(@NotNull InputEvent event) {
        Key key = event.getKey();
        InputEvent.Type type = event.getType();
        TextEditOperation operation = type == InputEvent.Type.KEY_RELEASED ? null : TextEditOperation.ofKey(key, event.getModifiers());

        if (operation != null) {
            this.applyEditOperation(operation);

            return;
        }

        char typed = toGlueKey(key, type);

        if (typed != KEY_NONE) {
            this.handleKeyTyped(typed);
            this.dropIfClosed();
        }

        // A screen that follows held keys (the credits) hears the release of a navigation key.
        char released = type == InputEvent.Type.KEY_RELEASED ? toGlueKey(key, InputEvent.Type.KEY_PRESSED) : KEY_NONE;

        if (released != KEY_NONE) {
            this.handleKeyReleased(released);
        }
    }

    /**
     * Moves the pointer.
     *
     * @param x the interface x
     * @param y the interface y
     * @throws IllegalStateException if the screen is not open
     */
    private void handlePointerMoved(float x, float y) {
        this.pointerX = x;
        this.pointerY = y;

        if (this.openComboBox != null) {
            DialogControl control = this.openComboBox.getDefinition();

            this.openComboBox.setHoveredItem(this.findComboBoxItem(this.openComboBox,
                                                                   x - this.getDialogLeft() - control.getX(),
                                                                   y - this.getDialogTop() - control.getY()));

            return;
        }

        if (this.requireDialogState().getPressed() instanceof ListBox pressedList) {
            pressedList.dragTo(this.getContext().getCanvas(), y - this.getDialogTop() - pressedList.getDefinition().getY());
        }

        if (this.requireDialogState().getPressed() instanceof Slider pressedSlider && this.slide(pressedSlider, x, false)) {
            this.onSelectionChanged(pressedSlider.getDefinition().getId());
        }

        // A drag from a press on the focused edit control extends its selection; a drag after a double press keeps the word.
        if (this.requireDialogState().getPressed() instanceof EditControl pressedEdit && pressedEdit == this.focusedEdit && !this.doublePress) {
            pressedEdit.getEditText().moveCaret(this.findEditIndex(pressedEdit, x), true, this.now);
        }

        if (this.isInteractive()) {
            this.onPointerMoved(x, y);
        }

        int before = this.requireDialogState().getHoveredId();

        // Update hover on the top matching dialog, including disabled controls.
        if (this.isInteractive()) {
            this.getContext().getDialogStack().updateHover(x, y);
        } else {
            this.requireDialogState().setHovered(null);
        }

        int hovered = this.requireDialogState().getHoveredId();

        // Play hover sound when entering a flic button, including a disabled one.
        if (hovered != before && this.requireDialogState().getHovered() instanceof GlueFlicButton) {
            this.playMouseOver();
        }
    }

    private int findComboBoxItem(@NotNull ComboBox comboBox, float offsetX, float offsetY) {
        try {
            return comboBox.findItem(this.getContext().getCanvas(), offsetX, offsetY);
        } catch (IOException exception) {
            return ComboBox.NO_SELECTION;
        }
    }

    /**
     * Shuts the open combo box's list; the press that opened it is over.
     */
    private void closeComboBox() {
        ComboBox comboBox = this.openComboBox;

        if (comboBox != null) {
            comboBox.setOpen(false);
        }

        this.openComboBox = null;
        this.requireDialogState().cancelPress();
    }

    /**
     * Presses the left button; a double press on a list activates the dialog's Enter control.
     * <p>
     * An open combo box takes the press first, before the screen and every control under its list: it selects the item under the press and
     * shuts.
     *
     * @param x           the interface x
     * @param y           the interface y
     * @param doublePress whether the press is a double press
     * @throws IllegalStateException if the screen is not open
     */
    private void handlePointerPressed(float x, float y, boolean doublePress) {
        this.doublePress = doublePress;

        if (this.openComboBox != null) {
            ComboBox comboBox = this.openComboBox;
            DialogControl control = comboBox.getDefinition();
            int item = this.findComboBoxItem(comboBox, x - this.getDialogLeft() - control.getX(), y - this.getDialogTop() - control.getY());
            boolean changed = comboBox.pressOpenList(item);

            this.closeComboBox();

            if (changed) {
                this.onSelectionChanged(control.getId());
            }

            return;
        }

        // While panels slide or a leave is pending, neither the screen nor its controls take the press.
        if (!this.isInteractive()) {
            this.requireDialogState().cancelPress();

            return;
        }

        if (this.onPointerPressed(x, y)) {
            return;
        }

        // Mark the control under the pointer as pressed.
        this.getContext().getDialogStack().press(x, y);

        if (this.requireDialogState().getPressedId() >= 0 && this.requireDialogState().getPressed() instanceof GlueFlicButton) {
            this.playMouseOver();
            this.getContext().getAudio().play(GlueAudio.Sound.MOUSE_DOWN);

            return;
        }

        // A pressed edit control takes the keyboard focus and puts its caret under the press: Shift extends the selection to it, a double
        // press selects the word there.
        if (this.requireDialogState().getPressed() instanceof EditControl edit) {
            int index = this.findEditIndex(edit, x);

            this.setFocusedEdit(edit);

            if (doublePress) {
                edit.getEditText().selectWord(index, this.now);
            } else {
                edit.getEditText().moveCaret(index, this.isExtendingPress(), this.now);
            }
        }

        if (this.requireDialogState().getPressed() instanceof ListBox list) {
            DialogControl control = list.getDefinition();
            int before = list.getSelection();

            this.focusedList = list;

            ListBox.Press press = list.pressAt(this.getContext().getCanvas(),
                                               x - this.getDialogLeft() - control.getX(), y - this.getDialogTop() - control.getY(),
                                               this.now);

            if (press == ListBox.Press.ROW) {
                if (list.getSelection() != before) {
                    this.onSelectionChanged(control.getId());
                }

                if (doublePress) {
                    DialogControl enterControl = this.requireDialogState().findEnterControl();

                    if (enterControl != null) {
                        this.onActivate(enterControl.getId());
                    }
                }
            }

            return;
        }

        if (this.requireDialogState().getPressed() instanceof ComboBox comboBox) {
            try {
                float controlTop = this.getDialogTop() + comboBox.getDefinition().getY();

                comboBox.setOpen(this.getContext().getCanvas(), true, controlTop, GlueCanvas.INTERFACE_HEIGHT - controlTop);
            } catch (IOException exception) {
                comboBox.setOpen(false);
            }

            this.openComboBox = comboBox.isOpen() ? comboBox : null;

            return;
        }

        if (this.requireDialogState().getPressed() instanceof Slider slider) {
            if (this.slide(slider, x, true)) {
                this.onSelectionChanged(slider.getDefinition().getId());
            }

            return;
        }

        if (this.requireDialogState().getPressedId() >= 0) {
            this.onPressed(this.requireDialogState().getPressedId(), x, y);
        }
    }

    /**
     * Finds the index of an edit control's text under an interface x, on the line as it was last drawn ({@link EditText#findIndexAt}).
     *
     * @param edit the edit control
     * @param x    the interface x
     * @return the UTF-16 index of the text
     */
    private int findEditIndex(@NotNull EditControl edit, float x) {
        GlueCanvas canvas = this.getContext().getCanvas();
        int slot = InterfaceFonts.toInputLineBox(this.getEditFontSlot(canvas, edit.getDefinition()));

        return edit.getEditText().findIndexAt(x, text -> canvas.measure(slot, text));
    }

    /**
     * Turns the mouse wheel: the focused list moves its selection.
     *
     * @param down {@code true} for a notch toward the user
     */
    private void handleWheel(boolean down) {
        if (this.isInteractive() && this.openComboBox == null && this.onWheel(this.pointerX, this.pointerY, down)) {
            return;
        }

        ListBox list = this.focusedList;

        if (list == null || !this.isInteractive() || this.openComboBox != null) {
            return;
        }

        int before = list.getSelection();

        list.scrollByWheel(this.getContext().getCanvas(), down);

        if (list.getSelection() != before) {
            this.onSelectionChanged(list.getDefinition().getId());
        }
    }

    /**
     * Releases the left button: the pressed control activates when the pointer is still over it.
     *
     * @param x the interface x
     * @param y the interface y
     * @throws IllegalStateException if the screen is not open
     */
    private void handlePointerReleased(float x, float y) {
        for (ListBox list : this.lists) {
            list.release();
        }

        for (Slider slider : this.sliders) {
            slider.release();
        }

        this.onPointerReleased(x, y);

        if (!this.isInteractive()) {
            this.requireDialogState().cancelPress();
        }

        // Release through the dialog stack; sliders handle value changes separately.
        DialogStack.Target<DialogControl, ScrDialogLayer> released = this.getContext().getDialogStack().release(x, y);

        if (released == null || released.getLayer() != this.layer || released.getControl() == null ||
            this.requireDialogState().requireControl(released.getControl()) instanceof Slider) {
            return;
        }

        this.onActivate(released.getControl().getId());
    }

    /**
     * Releases a navigation key ({@link #KEY_UP}, {@link #KEY_DOWN}, ...): an open popup takes it, else the screen ({@link #onKeyReleased}).
     *
     * @param key the released key
     */
    private void handleKeyReleased(char key) {
        if (this.popup != null) {
            this.popup.handleKeyReleased(key);
        } else {
            this.onKeyReleased(key);
        }
    }

    /**
     * Types a key: a character, {@link #KEY_ESCAPE}, {@link #KEY_ENTER} or an editing or navigation key. The focused edit
     * control takes the characters and editing keys; otherwise a character is a hotkey.
     *
     * @param key the character
     * @throws IllegalStateException if the screen is not open
     */
    private void handleKeyTyped(char key) {
        if (this.onKey(key)) {
            return;
        }

        if (!this.isInteractive()) {
            return;
        }

        if (this.openComboBox != null) {
            if (key == KEY_ESCAPE) {
                this.closeComboBox();
            }

            return;
        }

        if (this.editKey(key)) {
            return;
        }

        if (key == KEY_UP || key == KEY_DOWN) {
            ListBox list = this.focusedList;

            if (list == null) {
                this.onNavigate(key);

                return;
            }

            int before = list.getSelection();

            list.step(this.getContext().getCanvas(), key == KEY_DOWN);

            if (list.getSelection() != before) {
                this.onSelectionChanged(list.getDefinition().getId());
            }

            return;
        }

        DialogControl control = this.requireDialogState().findKeyControl(key, key == KEY_ESCAPE, key == KEY_ENTER,
                                                                         this.getContext().getControlText()::get);

        if (control == null) {
            return;
        }

        if (this.requireDialogState().requireControl(control) instanceof GlueFlicButton) {
            this.getContext().getAudio().play(GlueAudio.Sound.MOUSE_DOWN);
        }

        this.onActivate(control.getId());
    }

    private boolean editKey(char key) {
        EditControl focused = this.focusedEdit;

        if (focused == null || key == KEY_ESCAPE || key == KEY_ENTER || key == KEY_UP || key == KEY_DOWN) {
            return false;
        }

        EditText edit = focused.getEditText();

        TextEditKey editing = switch (key) {
            case KEY_BACKSPACE -> TextEditKey.BACKSPACE;
            case KEY_DELETE -> TextEditKey.DELETE;
            case KEY_LEFT -> TextEditKey.LEFT;
            case KEY_RIGHT -> TextEditKey.RIGHT;
            case KEY_HOME -> TextEditKey.HOME;
            case KEY_END -> TextEditKey.END;
            default -> null;
        };
        boolean changed = editing == null ? edit.type(key, this.now) : edit.press(editing, KeyModifier.SHIFT.isIn(this.modifiers), this.now);

        // Undo input-method insertion when the edit rejects a printable character.
        if (editing == null && !changed && key >= FIRST_PRINTABLE) {
            InputMethod.rejectInsertion();
        }

        if (changed) {
            this.onEditChanged(focused.getDefinition().getId());
        }

        return true;
    }

    /**
     * Applies an edit operation (select all, cut, copy, paste, undo, redo) to the focused edit control with the system
     * clipboard, as the edit keys do ({@link EditText#apply}); a copy goes to the screen's read-only selection first ({@link #onCopy}).
     *
     * @param operation the operation
     */
    public final void applyEditOperation(@NotNull TextEditOperation operation) {
        if (!this.isInteractive() || this.openComboBox != null || operation == TextEditOperation.COPY && this.onCopy()) {
            return;
        }

        EditControl focused = this.focusedEdit;

        if (focused == null) {
            return;
        }

        if (focused.getEditText().apply(operation, this.getContext().getClipboard(), this.now)) {
            this.onEditChanged(focused.getDefinition().getId());
        }
    }

    /**
     * Presses or drags a slider at an interface x.
     *
     * @param slider the slider
     * @param x      the interface x
     * @param press  {@code true} for a press, {@code false} for a drag
     * @return {@code true} if its value changed, {@code false} otherwise
     * @throws IllegalStateException if the screen is not open
     */
    private boolean slide(@NotNull Slider slider, float x, boolean press) {
        DialogControl control = slider.getDefinition();
        float offsetX = x - this.getDialogLeft() - this.requireDialogState().requireControl(control.getId()).getShift() - control.getX();

        return press ? slider.press(offsetX) : slider.dragTo(offsetX);
    }

    /**
     * Plays the hover sound when its minimum interval has elapsed.
     */
    protected final void playMouseOver() {
        if (this.now - this.lastMouseOver < MOUSE_OVER_INTERVAL_MILLISECONDS) {
            return;
        }

        this.lastMouseOver = this.now;

        this.getContext().getAudio().play(GlueAudio.Sound.MOUSE_OVER);
    }

    /**
     * Releases the videos and the popup.
     */
    @Override
    public void close() {
        this.closePopup();

        if (this.context == null) {
            return;
        }

        this.swish.close(this.context.getTimers());

        ScrDialog opened = this.runtime;
        boolean editing = false;

        for (Control control : opened == null ? List.<Control>of() : opened.getAllControls()) {
            if (control instanceof EditControl edit) {
                edit.getEditText().detach();
                editing = true;
            }
        }

        if (editing) {
            InputMethod.setTextInput(false);
        }

        for (ListBox list : this.lists) {
            list.setTimers(null);
        }

        for (GlueFlicButton button : this.flicButtons) {
            this.context.getTimers().killAll(button);

            for (GlueVideo video : button.getVideos()) {
                video.close(this.context.getCanvas());
            }
        }
    }
}
