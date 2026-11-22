package org.fogofwarlabs.sc4j.scr.game.frame;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

/**
 * Retains the numeric speed configuration: the configured frame duration of every speed setting, the command interval and the per-speed turn timeouts
 * filled from them. Configuration persistence, host-clock scheduling and the final display callback remain caller responsibilities.
 * <p>
 * It also keeps the turn-wait budget that schedules the turn steps: every game loop takes {@value #FRAME_BUDGET} from it and runs a turn step only
 * when that leaves at most {@code 0}; a completed turn step adds the timeout of the speed setting back. With a timeout of {@value #FRAME_BUDGET}
 * (every speed outside a multiplayer session) every loop runs a turn step; a longer timeout leaves loops whose frame runs without one.
 */
public final class GameSpeed {
    /**
     * The Fastest speed setting, whose static duration the game clock reads.
     */
    public static final int FASTEST = 6;
    /**
     * The number of speed settings, the entries of every per-speed table.
     */
    public static final int SPEED_COUNT = FASTEST + 1;
    /**
     * The static frame duration in milliseconds of the Slowest speed setting {@code 0}.
     */
    public static final int SLOWEST_DURATION = 167;
    /**
     * The static frame duration in milliseconds of the Slower speed setting {@code 1}.
     */
    public static final int SLOWER_DURATION = 111;
    /**
     * The static frame duration in milliseconds of the Slow speed setting {@code 2}.
     */
    public static final int SLOW_DURATION = 83;
    /**
     * The static frame duration in milliseconds of the Normal speed setting {@code 3}.
     */
    public static final int NORMAL_DURATION = 67;
    /**
     * The static frame duration in milliseconds of the Fast speed setting {@code 4}.
     */
    public static final int FAST_DURATION = 56;
    /**
     * The static frame duration in milliseconds of the Faster speed setting {@code 5}.
     */
    public static final int FASTER_DURATION = 48;
    /**
     * The static frame duration in milliseconds of the Fastest speed setting {@link #FASTEST}.
     */
    public static final int FASTEST_DURATION = 42;
    /**
     * The lowest per-speed timeout, and every timeout outside a multiplayer session.
     */
    public static final int MINIMUM_TIMEOUT = 1000;
    /**
     * The turn-wait budget one game loop takes, and the budget the game start stores.
     */
    public static final int FRAME_BUDGET = 1000;
    /**
     * The speed setting of a missing profile: Fast.
     */
    public static final int PRISTINE_SPEED = 4;
    /**
     * The milliseconds between two drawn frames of a live game whose override throttles drawing ({@link #isDrawThrottled()}). The game loop
     * clamps a display query result to {@code 16..100}; the query result it divides into {@code 1} is never above {@code 1}, so the clamp
     * always gives the lower bound.
     */
    public static final int THROTTLED_DRAW_INTERVAL = 16;

    /**
     * The highest override selector that leaves drawing at every game loop pass.
     */
    private static final int LAST_UNTHROTTLED_OVERRIDE = 2;
    /**
     * The frames of a speed setting's static duration that a live game may fall behind before its frame schedule restarts.
     */
    private static final int LAG_LIMIT_FRAMES = 3;
    /**
     * The static frame durations of the speed settings {@code 0..6}.
     */
    private static final int @NotNull [] BASE_DURATIONS = {SLOWEST_DURATION, SLOWER_DURATION, SLOW_DURATION, NORMAL_DURATION,
                                                           FAST_DURATION, FASTER_DURATION, FASTEST_DURATION};
    /**
     * The frame durations of the override selectors {@code 0..5}; selector {@code 0} uses the configured table.
     */
    private static final int @NotNull [] OVERRIDE_DURATIONS = {0, 36, 29, 21, 12, 1};
    /**
     * The character of a game name whose first occurrence starts the override suffix.
     */
    private static final char OVERRIDE_MARKER = '#';
    /**
     * The upper case letter that follows {@link #OVERRIDE_MARKER} in the override suffix.
     */
    private static final char OVERRIDE_LETTER = 'X';
    /**
     * The lower case letter that follows {@link #OVERRIDE_MARKER} in the override suffix.
     */
    private static final char OVERRIDE_LOWER_CASE_LETTER = 'x';
    /**
     * The characters after {@link #OVERRIDE_MARKER} that the override suffix reads: the letter and the digit.
     */
    private static final int OVERRIDE_SUFFIX_LENGTH = 2;
    /**
     * The digit of selector {@code 0}; the suffix digit is the selector.
     */
    private static final char OVERRIDE_DIGIT_ZERO = '0';
    /**
     * The turns per second when neither the game nor the provider names fewer.
     */
    private static final int MAXIMUM_TURNS_PER_SECOND = 24;
    /**
     * The Slowest speed setting, whose durations the factor multiplies instead of dividing.
     */
    private static final int SLOWEST = 0;
    /**
     * The DWORD factor of a missing profile, which keeps the static durations.
     */
    private static final int PRISTINE_FACTOR = 1;
    /**
     * The lowest unsigned factor that selects the scaled command interval (not at {@link #SLOWEST}).
     */
    private static final int SCALED_INTERVAL_FACTOR = 8;
    /**
     * The command interval of a factor of at least {@link #SCALED_INTERVAL_FACTOR}.
     */
    private static final int SCALED_COMMAND_INTERVAL = 10;
    /**
     * The command interval of every other configuration.
     */
    private static final int DEFAULT_COMMAND_INTERVAL = 1;
    /**
     * The factor {@code 1000} that the timeout formula applies before and after its division.
     */
    private static final int TIMEOUT_SCALE = 1000;
    /**
     * The offset added before the timeout is truncated, which rounds it to the nearest integer.
     */
    private static final double TIMEOUT_ROUNDING = 0.5;
    /**
     * The first double value above the signed DWORD range, {@code 2^31}.
     */
    private static final double SIGNED_DWORD_LIMIT = 2147483648.0;
    /**
     * The mask of the BYTE values the mode and the multiplayer session keep.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The multiplier of the state hash.
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * Creates a {@link GameSpeed} instance with the default replay speed when no profile is supplied.
     * <p>
     * Uses no override and factor {@code 1}. This does not restore an existing original client profile or derive speed from a replay header.
     *
     * @return the created {@link GameSpeed} instance at speed setting {@code 4} with its calculated durations
     */
    public static @NotNull GameSpeed createPristineReplay() {
        return createPristineReplay(0);
    }

    /**
     * Creates a {@link GameSpeed} instance with the default replay speed when no profile is supplied and the override selector the recording keeps.
     * <p>
     * Uses factor {@code 1}. This does not restore an existing original client profile or derive speed from a replay header.
     *
     * @param overrideIndex the override selector of the recorded game, from {@code 0} through {@code 5}
     * @return the created {@link GameSpeed} instance at speed setting {@code 4} with its calculated durations
     * @throws IllegalArgumentException if the override selector lies outside the override table
     */
    public static @NotNull GameSpeed createPristineReplay(int overrideIndex) {
        return new GameSpeed(PRISTINE_SPEED, PRISTINE_FACTOR, 0, overrideIndex);
    }

    /**
     * Parses the override selector of a game name: the first {@code '#'} of the name followed by {@code 'x'} or {@code 'X'} and a digit
     * {@code 1} through {@code 5} selects that override; anything else, a later {@code '#'} included, selects {@code 0}. Characters after the
     * digit and before the {@code '#'} are ignored.
     *
     * @param gameName the full game name
     * @return the override selector, from {@code 0} through {@code 5}
     */
    public static int parseOverrideIndex(@NotNull String gameName) {
        int marker = gameName.indexOf(OVERRIDE_MARKER);

        if (marker < 0 || gameName.length() - marker - 1 < OVERRIDE_SUFFIX_LENGTH) {
            return 0;
        }

        char letter = gameName.charAt(marker + 1);
        int selector = gameName.charAt(marker + OVERRIDE_SUFFIX_LENGTH) - OVERRIDE_DIGIT_ZERO;

        if ((letter != OVERRIDE_LETTER && letter != OVERRIDE_LOWER_CASE_LETTER) || selector < 1 || selector >= OVERRIDE_DURATIONS.length) {
            return 0;
        }

        return selector;
    }

    /**
     * Returns the static frame duration of a speed setting, which ignores the factor, the override and EUD writes of the configured durations; the
     * game clock multiplies the frame counter by the {@link #FASTEST} entry.
     *
     * @param speedIndex the speed setting {@code 0} through {@code 6}
     * @return the duration in milliseconds
     */
    public static int getBaseDuration(int speedIndex) {
        return BASE_DURATIONS[speedIndex];
    }

    /**
     * Returns how far a live game's frame schedule may fall behind the host clock before it restarts at the host clock: three static frame
     * durations of the speed setting, whatever the override, so a game with a short override duration still catches up after a slow drawn frame.
     *
     * @param speedIndex the speed setting {@code 0} through {@code 6}
     * @return the lag limit in milliseconds
     */
    public static int getLagLimit(int speedIndex) {
        return LAG_LIMIT_FRAMES * BASE_DURATIONS[speedIndex];
    }

    private final int overrideIndex;
    private final int @NotNull [] durations;
    private final int @NotNull [] timeouts;

    private int speedIndex;
    private int factor;
    private int mode;
    private int commandInterval;

    /**
     * The multiplayer session BYTE the timeouts are filled for.
     */
    private int multiplayerSession;
    /**
     * The game's turns per second.
     */
    private int turnRate;
    /**
     * The network provider's default turns per second.
     */
    private int providerTurnsPerSecond;

    /**
     * The turn-wait budget of the turn gate, a signed DWORD.
     */
    private int turnBudget;

    /**
     * Creates a {@link GameSpeed} instance with unsigned duration scaling for explicit session inputs.
     *
     * @param speedIndex    the resolved speed setting, from {@code 0} through {@code 6}
     * @param factor        the original unsigned DWORD factor
     * @param mode          the original low-byte mode
     * @param overrideIndex the resolved override selector, from {@code 0} through {@code 5}
     * @throws IllegalArgumentException if an index lies outside the represented native tables
     * @throws ArithmeticException      if the original unsigned division would divide by zero
     */
    public GameSpeed(int speedIndex, int factor, int mode, int overrideIndex) {
        if (overrideIndex < 0 || overrideIndex >= OVERRIDE_DURATIONS.length) {
            throw Exceptions.newIllegalArgumentException("Native speed or override index is outside the represented tables.");
        }

        this.overrideIndex = overrideIndex;
        this.durations     = new int[BASE_DURATIONS.length];
        this.timeouts      = new int[BASE_DURATIONS.length];

        this.speedIndex      = 0;
        this.factor          = 0;
        this.mode            = 0;
        this.commandInterval = 0;

        this.multiplayerSession     = 0;
        this.turnRate               = 0;
        this.providerTurnsPerSecond = 0;

        this.turnBudget = FRAME_BUDGET;

        this.configure(speedIndex, factor, mode);
    }

    /**
     * @return the explicit speed setting
     */
    public int getSpeedIndex() {
        return this.speedIndex;
    }

    /**
     * @return the original DWORD scaling factor
     */
    public int getFactor() {
        return this.factor;
    }

    /**
     * @return the zero-extended mode byte
     */
    public int getMode() {
        return this.mode;
    }

    /**
     * @return the explicit override selector
     */
    public int getOverrideIndex() {
        return this.overrideIndex;
    }

    /**
     * Tests whether a live game loop draws only every {@link #THROTTLED_DRAW_INTERVAL} milliseconds without waiting for the display, running
     * its due frames in the passes between: the overrides {@code 3} through {@code 5}, whose frames are shorter than a displayed frame.
     *
     * @return {@code true} if drawing is throttled, {@code false} otherwise
     */
    public boolean isDrawThrottled() {
        return this.overrideIndex > LAST_UNTHROTTLED_OVERRIDE;
    }

    /**
     * @return the native command interval
     */
    public int getCommandInterval() {
        return this.commandInterval;
    }

    /**
     * @return the selected duration, including the override table when enabled
     */
    public int getFrameDuration() {
        return this.getDuration(this.speedIndex);
    }

    /**
     * @param speedIndex the represented native speed index
     * @return the original DWORD timing lookup value, the override duration when an override is selected
     */
    public int getDuration(int speedIndex) {
        if (this.overrideIndex != 0) {
            return OVERRIDE_DURATIONS[this.overrideIndex];
        }

        return this.durations[speedIndex];
    }

    /**
     * @param speedIndex the speed index {@code 0} through {@code 6}
     * @return the configured duration table entry, without the override
     */
    public int getTableDuration(int speedIndex) {
        return this.durations[speedIndex];
    }

    /**
     * @return the number of duration table entries
     */
    public int getTableSize() {
        return this.durations.length;
    }

    /**
     * @return the multiplayer session BYTE the timeouts were filled for ({@link #startSession})
     */
    public int getMultiplayerSession() {
        return this.multiplayerSession;
    }

    /**
     * @return the live per-speed timeouts, one DWORD per speed
     */
    public int @NotNull [] getTimeouts() {
        return this.timeouts;
    }

    /**
     * @return the turn-wait budget: the game start stores {@value #FRAME_BUDGET}, every game loop takes {@value #FRAME_BUDGET} and every completed
     * turn step adds the timeout of the speed setting
     */
    public int getTurnBudget() {
        return this.turnBudget;
    }

    /**
     * @return {@code true} if the next game loop runs a turn step, because taking {@value #FRAME_BUDGET} leaves the turn-wait budget at most
     * {@code 0}, {@code false} otherwise
     */
    public boolean isTurnStepDue() {
        return this.turnBudget - FRAME_BUDGET <= 0;
    }

    /**
     * Sets a configured duration table entry without changing the active override selector.
     *
     * @param speedIndex the speed index {@code 0} through {@code 6}
     * @param duration   the new DWORD
     */
    public void setTableDuration(int speedIndex, int duration) {
        this.durations[speedIndex] = duration;
    }


    /**
     * Applies a speed configuration, as the replay speed command and the speed option do: the speed option becomes {@code speedIndex}, then the whole
     * duration table is rebuilt from the static durations (multiplied by the factor at {@link #SLOWEST}, else divided unsigned), which discards
     * earlier EUD writes to it, and the command interval becomes {@code 10} for a factor of {@code 8} or more (not at {@link #SLOWEST}). The table is
     * then copied and the timeouts refilled ({@link #fillTimeouts()}).
     *
     * @param speedIndex the speed option, from {@code 0} through {@code 6}
     * @param factor     the DWORD factor
     * @param mode       the mode byte
     * @throws IllegalArgumentException if the speed index lies outside the table
     * @throws ArithmeticException      if the original unsigned division would divide by zero
     */
    public void configure(int speedIndex, int factor, int mode) {
        if (speedIndex < 0 || speedIndex >= BASE_DURATIONS.length) {
            throw Exceptions.newIllegalArgumentException("Native speed or override index is outside the represented tables.");
        }

        if (speedIndex != SLOWEST && factor == 0) {
            throw Exceptions.newArithmeticException("Native speed scaling divides by zero.");
        }

        this.speedIndex      = speedIndex;
        this.factor          = factor;
        this.mode            = mode & BYTE_MASK;
        this.commandInterval = speedIndex != SLOWEST && Integer.compareUnsigned(factor, SCALED_INTERVAL_FACTOR) >= 0
                               ? SCALED_COMMAND_INTERVAL
                               : DEFAULT_COMMAND_INTERVAL;

        for (int durationIndex = 0; durationIndex < this.durations.length; durationIndex++) {
            this.durations[durationIndex] = speedIndex == SLOWEST
                                            ? BASE_DURATIONS[durationIndex] * factor
                                            : Integer.divideUnsigned(BASE_DURATIONS[durationIndex], factor);
        }

        this.fillTimeouts();
    }

    /**
     * Supplies the network session of the game and refills the timeouts, as the game start does.
     *
     * @param multiplayerSession     the multiplayer session BYTE
     * @param turnRate               the game's turns per second (the game information's {@code net_turn_rate})
     * @param providerTurnsPerSecond the network provider's default turns per second
     */
    public void startSession(int multiplayerSession, int turnRate, int providerTurnsPerSecond) {
        this.multiplayerSession     = multiplayerSession & BYTE_MASK;
        this.turnRate               = turnRate;
        this.providerTurnsPerSecond = providerTurnsPerSecond;

        this.fillTimeouts();
    }

    /**
     * Sets the game's turns per second, as the Turn Rate and Dynamic Turn Rate commands do, and refills the timeouts.
     *
     * @param turnRate the game's turns per second
     */
    public void setTurnRate(int turnRate) {
        this.turnRate = turnRate;

        this.fillTimeouts();
    }

    /**
     * Fills the per-speed timeouts: outside a multiplayer session every timeout is {@code 1000}; in one, the turns per second are the game's turn
     * rate when it is {@code 1} through {@code 24}, else the provider's default when it is below {@code 24} ({@code 24} when that is {@code 0}), else
     * {@code 24}, and the timeout of speed {@code s} is {@code 1000 / (duration(s) * turns per second) * 1000 + 0.5} truncated (doubles over the
     * unsigned product), at least {@code 1000}; the duration is the timing lookup ({@link #getDuration}).
     */
    public void fillTimeouts() {
        int turnsPerSecond = MAXIMUM_TURNS_PER_SECOND;

        if (this.turnRate - 1 >= 0 && this.turnRate - 1 < MAXIMUM_TURNS_PER_SECOND) {
            turnsPerSecond = this.turnRate;
        } else if (Integer.compareUnsigned(this.providerTurnsPerSecond, MAXIMUM_TURNS_PER_SECOND) < 0) {
            turnsPerSecond = this.providerTurnsPerSecond == 0 ? MAXIMUM_TURNS_PER_SECOND : this.providerTurnsPerSecond;
        }

        for (int speed = this.timeouts.length - 1; speed >= 0; speed--) {
            int timeout = MINIMUM_TIMEOUT;

            if (this.multiplayerSession != 0) {
                double product = Integer.toUnsignedLong(this.getDuration(speed) * turnsPerSecond);
                double value = (double) TIMEOUT_SCALE / product * TIMEOUT_SCALE + TIMEOUT_ROUNDING;
                // Native truncation turns a value outside the signed DWORD range (or NaN) into Integer.MIN_VALUE.
                int truncated = value >= Integer.MIN_VALUE && value < SIGNED_DWORD_LIMIT ? (int) value : Integer.MIN_VALUE;

                timeout = Math.max(truncated, MINIMUM_TIMEOUT);
            }

            this.timeouts[speed] = timeout;
        }
    }

    /**
     * Stores the turn-wait budget of the game start, {@value #FRAME_BUDGET}.
     */
    public void resetTurnBudget() {
        this.turnBudget = FRAME_BUDGET;
    }

    /**
     * Takes one game loop's {@value #FRAME_BUDGET} from the turn-wait budget.
     *
     * @return {@code true} if the loop runs a turn step, because the budget is at most {@code 0} now, {@code false} otherwise
     */
    public boolean takeFrameBudget() {
        this.turnBudget -= FRAME_BUDGET;

        return this.turnBudget <= 0;
    }

    /**
     * Adds the timeout of the speed setting ({@link #getTimeouts()}) to the turn-wait budget once a turn step completed.
     */
    public void addTurnTimeout() {
        this.turnBudget += this.timeouts[this.speedIndex];
    }

    /**
     * Mixes the retained numeric timing state into a state hash.
     *
     * @param hash the preceding state hash
     * @return the hash including the complete retained numeric timing state
     */
    public int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + this.speedIndex;
        hash = HASH_MULTIPLIER * hash + this.factor;
        hash = HASH_MULTIPLIER * hash + this.mode;
        hash = HASH_MULTIPLIER * hash + this.overrideIndex;
        hash = HASH_MULTIPLIER * hash + this.commandInterval;

        for (int duration : this.durations) {
            hash = HASH_MULTIPLIER * hash + duration;
        }

        return hash;
    }
}
