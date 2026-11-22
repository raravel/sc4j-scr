package org.fogofwarlabs.sc4j.scr.game.trigger.eud.region;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.presentation.LocalSelection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Interface state supplied by the local machine and exposed through classic regions, alongside the key states
 * ({@link LocalKeyStates}) and the view ({@link LocalView}):
 * <ul>
 *     <li>the mouse button WORD, which the input dispatch rebuilds from the button events;</li>
 *     <li>the chat mode BYTE: nonzero while the chat box is open;</li>
 *     <li>the interface tick stamp DWORD: the negated host clock (milliseconds) of the last interface tick. The game
 *     start sets it; the interface loop adds the clock and, when more than 10 ms passed, stores the negated clock again
 *     and runs the scroll tick;</li>
 *     <li>the displayed button set WORD ({@code 0xE4} for none): the button refresh stores the set it shows after
 *     comparing it with this value, and the selection refresh resets it to {@code 0xE4};</li>
 *     <li>the glue palette, the camera scroll steps, the unit-speech mute, the interface selection copy with its refresh
 *     BYTE, the waiting-for-players stamp and countdown, the portrait refresh BYTE, the chat recipient label and the status
 *     panel's cached hit points.</li>
 * </ul>
 * Each value the interface supplies is unknown until it does, and a read then stops explicitly. An EUD write stores the
 * value, which the interface takes over ({@link #takeWritten()}).
 */
public final class LocalInterfaceState {
    /**
     * The mask of the low WORD.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The mask of the low BYTE.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * The classic address of the mouse button WORD.
     */
    public static final int MOUSE_BUTTONS_ADDRESS = 0x6CDDC0;
    /**
     * The classic address of the chat mode BYTE.
     */
    public static final int CHAT_MODE_ADDRESS = 0x68C144;
    /**
     * The classic address of the interface tick stamp DWORD.
     */
    public static final int INTERFACE_TICK_ADDRESS = 0x51CE8C;
    /**
     * The classic address of the displayed button set WORD.
     */
    public static final int DISPLAYED_BUTTON_SET_ADDRESS = 0x68C14C;
    /**
     * The classic address of the glue palette DWORD: the palette id of the glue screen last entered, which the screen entry
     * stores and compares to reload the palette, and which is invalidated with {@code -1}.
     */
    public static final int GLUE_PALETTE_ADDRESS = 0x50E064;
    /**
     * The classic address of the camera scroll steps: seven rows of seven pixel steps, one row per scroll speed, which the
     * scroll tick reads.
     */
    public static final int SCROLL_STEPS_ADDRESS = 0x513B68;
    /**
     * The classic address of the unit-speech mute DWORD: Mute Unit Speech sets it, Unmute clears it, the sound init resets
     * it; the acknowledgement and ready sounds read it.
     */
    public static final int UNIT_SPEECH_MUTE_ADDRESS = 0x64086C;
    /**
     * The classic address of the interface selection copy (twelve unit pointers) of the local selection.
     */
    public static final int INTERFACE_SELECTION_ADDRESS = 0x597208;
    /**
     * The classic address of the selection refresh BYTE: set by the interface, cleared when the interface re-copies the
     * selection, on every interface update while set.
     */
    public static final int SELECTION_REFRESH_ADDRESS = 0x59723C;
    /**
     * The classic address of the host-clock stamp of the waiting-for-players dialog (the clock less 120000 at game start,
     * the clock when the dialog closes).
     */
    public static final int STALL_STAMP_ADDRESS = 0x59CC78;
    /**
     * The classic address of the waiting-for-players countdown in seconds, set when the dialog opens and counted down.
     */
    public static final int STALL_COUNTDOWN_ADDRESS = 0x59CC80;
    /**
     * The classic address of the portrait refresh BYTE: the interface init sets it, the portrait refresh of the displayed
     * unit clears it.
     */
    public static final int PORTRAIT_REFRESH_ADDRESS = 0x68AC74;
    /**
     * The classic address of the first bytes of the chat recipient label, which is formatted with a player name.
     */
    public static final int CHAT_LABEL_ADDRESS = 0x68C10C;
    /**
     * The size of the chat label region.
     */
    public static final int CHAT_LABEL_SIZE = 0x32;
    /**
     * The classic address of the status panel's cached hit points: the displayed unit's and those of the eight units it
     * carries, compared to redraw.
     */
    public static final int STATUS_PANEL_CACHE_ADDRESS = 0x6CA94C;
    /**
     * The number of DWORDs of the status panel cache region.
     */
    public static final int STATUS_PANEL_CACHE_DWORDS = 12;
    /**
     * The number of scroll speed rows of the scroll step table.
     */
    public static final int SCROLL_SPEED_COUNT = 7;
    /**
     * The number of scroll steps of one scroll speed row.
     */
    public static final int SCROLL_STEP_COUNT = 7;

    /**
     * The milliseconds that must pass for an interface tick.
     */
    private static final int INTERFACE_TICK_MILLISECONDS = 10;
    /**
     * The milliseconds of one interface tick on a millisecond clock: the first stamp more than {@link #INTERFACE_TICK_MILLISECONDS} later.
     */
    private static final int INTERFACE_TICK_PERIOD_MILLISECONDS = INTERFACE_TICK_MILLISECONDS + 1;
    /**
     * The initial scroll step bytes, one row of steps per scroll speed.
     */
    private static final byte @NotNull [] INITIAL_SCROLL_STEPS = {
            0, 0, 8, 8, 16, 16, 16,
            0, 0, 8, 8, 16, 16, 24,
            0, 8, 8, 16, 16, 24, 24,
            0, 8, 8, 16, 24, 24, 32,
            0, 8, 16, 16, 24, 32, 40,
            0, 8, 16, 24, 32, 40, 48,
            0, 8, 24, 32, 40, 56, 64
    };


    private static @NotNull UnsupportedOperationException createUnknownInputException(int address, @NotNull String description) {
        return Exceptions.newUnsupportedOperationException("EUD address {0} is {1}, a local input that was not supplied.",
                                                           String.format("0x%08X", address),
                                                           description);
    }


    private boolean mouseButtonsKnown;
    private int mouseButtons;

    private boolean chatModeKnown;
    private int chatMode;

    private boolean interfaceTickKnown;
    private int interfaceTick;

    private boolean displayedButtonSetKnown;
    private int displayedButtonSet;

    private boolean gluePaletteKnown;
    private int gluePalette;

    private int unitSpeechMuted;

    private boolean interfaceSelectionKnown;
    private final int @NotNull [] interfaceSelection;
    private int selectionRefresh;

    private boolean stallStampKnown;
    private int stallStamp;
    private int stallCountdown;

    private boolean portraitRefreshKnown;
    private int portraitRefresh;

    private byte @Nullable [] chatLabel;
    private int @Nullable [] statusPanelCache;
    /**
     * The live scroll steps: an EUD write changes the local scroll speed.
     */
    private final byte @NotNull [] scrollSteps;

    /**
     * Whether an EUD write changed a value since the last {@link #takeWritten()}.
     */
    private boolean written;


    /**
     * Creates a {@link LocalInterfaceState} instance with every supplied value unknown and the initial scroll steps.
     */
    public LocalInterfaceState() {
        this.mouseButtonsKnown = false;
        this.mouseButtons      = 0;

        this.chatModeKnown = false;
        this.chatMode      = 0;

        this.interfaceTickKnown = false;
        this.interfaceTick      = 0;

        this.displayedButtonSetKnown = false;
        this.displayedButtonSet      = 0;

        this.gluePaletteKnown = false;
        this.gluePalette      = 0;

        this.unitSpeechMuted = 0;

        this.interfaceSelectionKnown = false;
        this.interfaceSelection      = new int[LocalSelection.CAPACITY];
        this.selectionRefresh        = 0;

        this.stallStampKnown = false;
        this.stallStamp      = 0;
        this.stallCountdown  = 0;

        this.portraitRefreshKnown = false;
        this.portraitRefresh      = 0;

        this.chatLabel        = null;
        this.statusPanelCache = null;
        this.scrollSteps      = INITIAL_SCROLL_STEPS.clone();

        this.written = false;
    }


    /**
     * Sets the mouse button WORD of the current interface frame.
     *
     * @param mouseButtons the WORD in the low sixteen bits
     */
    public void setMouseButtons(int mouseButtons) {
        this.mouseButtons      = mouseButtons & WORD_MASK;
        this.mouseButtonsKnown = true;
    }

    /**
     * @return the mouse button WORD
     * @throws UnsupportedOperationException if the interface did not supply it
     */
    public int getMouseButtons() {
        if (!this.mouseButtonsKnown) {
            throw createUnknownInputException(MOUSE_BUTTONS_ADDRESS, "the mouse button WORD");
        }

        return this.mouseButtons;
    }

    void writeMouseButtons(int value) {
        this.getMouseButtons();

        this.mouseButtons = value & WORD_MASK;
        this.written      = true;
    }

    /**
     * Sets the chat mode BYTE of the current interface frame.
     *
     * @param chatMode the BYTE in the low eight bits
     */
    public void setChatMode(int chatMode) {
        this.chatMode      = chatMode & BYTE_MASK;
        this.chatModeKnown = true;
    }

    /**
     * @return the chat mode BYTE
     * @throws UnsupportedOperationException if the interface did not supply it
     */
    public int getChatMode() {
        if (!this.chatModeKnown) {
            throw createUnknownInputException(CHAT_MODE_ADDRESS, "the chat mode BYTE");
        }

        return this.chatMode;
    }

    /**
     * @return {@code true} if the chat mode BYTE is known and nonzero, as it is while the chat box is open, {@code false} otherwise
     */
    public boolean isChatOpen() {
        return this.chatModeKnown && this.chatMode != 0;
    }

    void writeChatMode(int value) {
        this.getChatMode();

        this.chatMode = value & BYTE_MASK;
        this.written  = true;
    }

    /**
     * @return the interface tick stamp DWORD
     * @throws UnsupportedOperationException if the tick was not started
     */
    public int getInterfaceTick() {
        if (!this.interfaceTickKnown) {
            throw createUnknownInputException(INTERFACE_TICK_ADDRESS, "the interface tick stamp");
        }

        return this.interfaceTick;
    }

    void writeInterfaceTick(int value) {
        this.getInterfaceTick();

        this.interfaceTick = value;
    }

    /**
     * Sets the button set the console shows.
     *
     * @param buttonSet the displayed set, {@code 0xE4} for none
     */
    public void setDisplayedButtonSet(int buttonSet) {
        this.displayedButtonSet      = buttonSet & WORD_MASK;
        this.displayedButtonSetKnown = true;
    }

    /**
     * @return the displayed button set WORD, which an EUD write may have changed (the next refresh then finds the shown
     * set different and redraws it)
     * @throws UnsupportedOperationException if the interface did not supply it
     */
    public int getDisplayedButtonSet() {
        if (!this.displayedButtonSetKnown) {
            throw createUnknownInputException(DISPLAYED_BUTTON_SET_ADDRESS, "the displayed button set WORD");
        }

        return this.displayedButtonSet;
    }

    void writeDisplayedButtonSet(int value) {
        this.getDisplayedButtonSet();

        this.displayedButtonSet = value & WORD_MASK;
        this.written            = true;
    }

    /**
     * Sets the palette id of the glue screen the interface last entered.
     *
     * @param palette the palette id, or {@code -1} when invalidated
     */
    public void setGluePalette(int palette) {
        this.gluePalette      = palette;
        this.gluePaletteKnown = true;
    }

    /**
     * @return the glue palette DWORD, which an EUD write may have changed (the next glue screen then reloads its palette
     * when it differs)
     * @throws UnsupportedOperationException if the interface did not supply it
     */
    public int getGluePalette() {
        if (!this.gluePaletteKnown) {
            throw createUnknownInputException(GLUE_PALETTE_ADDRESS, "the glue palette DWORD");
        }

        return this.gluePalette;
    }

    void writeGluePalette(int value) {
        this.getGluePalette();

        this.gluePalette = value;
        this.written     = true;
    }

    /**
     * @return the unit-speech mute DWORD ({@code 0} at game start: the sound init resets it)
     */
    public int getUnitSpeechMuted() {
        return this.unitSpeechMuted;
    }

    /**
     * Sets the unit-speech mute DWORD, as the local player's Mute or Unmute Unit Speech action or an EUD write does.
     *
     * @param muted the DWORD, {@code 1} muted and {@code 0} not
     */
    public void setUnitSpeechMuted(int muted) {
        this.unitSpeechMuted = muted;
    }

    /**
     * Sets the interface selection copy after the interface made it.
     *
     * @param poolIndices the twelve slots' pool indices, {@code 0} for an empty slot
     */
    public void setInterfaceSelection(int @NotNull [] poolIndices) {
        System.arraycopy(poolIndices, 0, this.interfaceSelection, 0, this.interfaceSelection.length);

        this.interfaceSelectionKnown = true;
    }

    /**
     * @param slot the slot {@code 0..11}
     * @return the pool index of the slot's unit, {@code 0} for none
     * @throws UnsupportedOperationException if the interface did not supply the selection
     */
    public int getInterfaceSelectedPoolIndex(int slot) {
        if (!this.interfaceSelectionKnown) {
            throw createUnknownInputException(INTERFACE_SELECTION_ADDRESS, "the interface selection copy");
        }

        return this.interfaceSelection[slot];
    }

    /**
     * Sets one interface selection pointer, as an EUD write of an entry start does; nothing is recounted.
     *
     * @param slot      the slot {@code 0..11}
     * @param poolIndex the pool index, {@code 0} for none
     * @throws UnsupportedOperationException if the interface did not supply the selection
     */
    public void setInterfaceSelectedPoolIndexRaw(int slot, int poolIndex) {
        this.getInterfaceSelectedPoolIndex(slot);

        this.interfaceSelection[slot] = poolIndex;

        this.written = true;
    }

    /**
     * @return the selection refresh BYTE ({@code 0} whenever triggers run: the interface clears it in the same call that
     * sets it); nonzero asks the interface to re-copy the selection
     */
    public int getSelectionRefresh() {
        return this.selectionRefresh;
    }

    void writeSelectionRefresh(int value) {
        this.selectionRefresh = value & BYTE_MASK;
        this.written          = true;
    }

    /**
     * Sets the host-clock stamp of the waiting-for-players dialog.
     *
     * @param stamp the stamp in host milliseconds
     */
    public void setStallStamp(int stamp) {
        this.stallStamp      = stamp;
        this.stallStampKnown = true;
    }

    /**
     * @return the host-clock stamp
     * @throws UnsupportedOperationException if the interface did not supply it
     */
    public int getStallStamp() {
        if (!this.stallStampKnown) {
            throw createUnknownInputException(STALL_STAMP_ADDRESS, "the waiting-for-players clock stamp");
        }

        return this.stallStamp;
    }

    void writeStallStamp(int value) {
        this.getStallStamp();

        this.stallStamp = value;
    }

    /**
     * @return the waiting-for-players countdown in seconds ({@code 0} until the dialog opens)
     */
    public int getStallCountdown() {
        return this.stallCountdown;
    }

    /**
     * Sets the waiting-for-players countdown, as the dialog or an EUD write does.
     *
     * @param seconds the countdown
     */
    public void setStallCountdown(int seconds) {
        this.stallCountdown = seconds;
    }

    /**
     * Sets the portrait refresh BYTE.
     *
     * @param value the BYTE
     */
    public void setPortraitRefresh(int value) {
        this.portraitRefresh      = value & BYTE_MASK;
        this.portraitRefreshKnown = true;
    }

    /**
     * @return the portrait refresh BYTE; nonzero asks the interface to refresh the displayed unit's portrait
     * @throws UnsupportedOperationException if the interface did not supply it
     */
    public int getPortraitRefresh() {
        if (!this.portraitRefreshKnown) {
            throw createUnknownInputException(PORTRAIT_REFRESH_ADDRESS, "the portrait refresh BYTE");
        }

        return this.portraitRefresh;
    }

    void writePortraitRefresh(int value) {
        this.getPortraitRefresh();

        this.portraitRefresh = value & BYTE_MASK;
        this.written         = true;
    }

    /**
     * Sets the first {@link #CHAT_LABEL_SIZE} bytes of the chat recipient label.
     *
     * @param label the bytes, zero-padded; kept (EUD writes change them)
     */
    public void setChatLabel(byte @NotNull [] label) {
        this.chatLabel = Arrays.copyOf(label, CHAT_LABEL_SIZE);
    }

    /**
     * @return the live chat label bytes
     * @throws UnsupportedOperationException if the interface did not supply them
     */
    public byte @NotNull [] getChatLabel() {
        if (this.chatLabel == null) {
            throw createUnknownInputException(CHAT_LABEL_ADDRESS, "the chat recipient label");
        }

        return this.chatLabel;
    }

    /**
     * Sets the status panel's cached hit points.
     *
     * @param dwords the twelve DWORDs; kept (EUD writes change them)
     */
    public void setStatusPanelCache(int @NotNull [] dwords) {
        this.statusPanelCache = Arrays.copyOf(dwords, STATUS_PANEL_CACHE_DWORDS);
    }

    /**
     * Sets one DWORD of the status panel cache as the panel builders do (DWORD 0 with the displayed unit's hit points,
     * the cargo view DWORDs 1..8 with the carried units'); the other DWORDs keep their values. A cache the interface never
     * supplied starts as zeros, the interface's memory at process start.
     *
     * @param index the DWORD index {@code 0..11}
     * @param value the value
     */
    public void setStatusPanelCacheEntry(int index, int value) {
        if (this.statusPanelCache == null) {
            this.statusPanelCache = new int[STATUS_PANEL_CACHE_DWORDS];
        }

        this.statusPanelCache[index] = value;
    }

    /**
     * @return the live status panel cache
     * @throws UnsupportedOperationException if the interface did not supply it
     */
    public int @NotNull [] getStatusPanelCache() {
        if (this.statusPanelCache == null) {
            throw createUnknownInputException(STATUS_PANEL_CACHE_ADDRESS, "the status panel's cached hit points");
        }

        return this.statusPanelCache;
    }

    /**
     * @param speed the scroll speed row, below {@link #SCROLL_SPEED_COUNT}
     * @param index the step index, below {@link #SCROLL_STEP_COUNT}
     * @return the scroll step in pixels, which EUD writes change
     */
    public int getScrollStep(int speed, int index) {
        return this.scrollSteps[speed * SCROLL_STEP_COUNT + index] & BYTE_MASK;
    }

    /**
     * @return the live 49 scroll step bytes that the classic region {@link #SCROLL_STEPS_ADDRESS} serves
     */
    byte @NotNull [] getScrollStepBytes() {
        return this.scrollSteps;
    }


    /**
     * Starts the interface tick at the game start.
     *
     * @param hostMilliseconds the host clock in milliseconds
     */
    public void startInterfaceTick(int hostMilliseconds) {
        this.interfaceTick      = -hostMilliseconds;
        this.interfaceTickKnown = true;
    }

    /**
     * Runs the clock test of the interface loop for every interface tick due at the host clock: a tick is due when more than
     * {@link #INTERFACE_TICK_MILLISECONDS} passed since the stamp, and each tick advances the stamp by {@link #INTERFACE_TICK_PERIOD_MILLISECONDS}.
     * When more ticks are due than the limit, the limit runs and the stamp is renewed to the host clock.
     *
     * @param hostMilliseconds the host clock in milliseconds
     * @param maximumTicks     the most ticks to run
     * @return the number of ticks that run, from {@code 0} to {@code maximumTicks}
     * @throws UnsupportedOperationException if the tick was not started
     */
    public int advanceInterfaceTicks(int hostMilliseconds, int maximumTicks) {
        int ticks = 0;

        while (Integer.compareUnsigned(this.getInterfaceTick() + hostMilliseconds, INTERFACE_TICK_MILLISECONDS) > 0) {
            if (ticks >= maximumTicks) {
                this.interfaceTick = -hostMilliseconds;

                break;
            }

            this.interfaceTick -= INTERFACE_TICK_PERIOD_MILLISECONDS;
            ticks++;
        }

        return ticks;
    }

    /**
     * Takes the written mark: tells whether an EUD write changed a value since the last call, and clears the mark.
     *
     * @return {@code true} if an EUD write changed a value since the last call, {@code false} otherwise
     */
    public boolean takeWritten() {
        boolean result = this.written;

        this.written = false;

        return result;
    }
}
