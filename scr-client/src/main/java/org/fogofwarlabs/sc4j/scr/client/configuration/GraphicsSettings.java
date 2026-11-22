package org.fogofwarlabs.sc4j.scr.client.configuration;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.jetbrains.annotations.NotNull;

/**
 * Stores display pacing, gamma and window settings used by the client. Settings-file values initialize these options, and explicit launch options
 * override them without changing simulation speed.
 */
public final class GraphicsSettings {
    /**
     * The lowest display gamma value.
     */
    public static final int MINIMUM_GAMMA = 60;

    /**
     * The highest display gamma value.
     */
    public static final int MAXIMUM_GAMMA = 140;

    /**
     * The neutral {@code Gamma} value (factor {@code 1.0}, the identity ramp).
     */
    public static final int DEFAULT_GAMMA = 100;

    /**
     * The windowed display mode.
     */
    public static final int WINDOWED = 0;

    /**
     * The default desktop-resolution fullscreen mode.
     */
    public static final int WINDOWED_FULLSCREEN = 1;

    /**
     * The fullscreen video mode.
     */
    public static final int FULLSCREEN = 2;

    /**
     * The default frame rate limit value.
     */
    public static final int DEFAULT_FRAME_RATE_LIMIT = 300;

    /**
     * The highest supported SD graphics filter mode.
     */
    public static final int MAXIMUM_SD_FILTER_MODE = 3;

    /**
     * The swap interval that presents without waiting for the display.
     */
    public static final int SWAP_INTERVAL_OFF = 0;

    /**
     * The swap interval that waits for one display refresh, the fallback of adaptive VSync.
     */
    public static final int SWAP_INTERVAL_ON = 1;

    private static final float GAMMA_FACTOR_SCALE = 100.0f;
    private static final float MINIMUM_GAMMA_FACTOR = 0.6f;
    private static final float MAXIMUM_GAMMA_FACTOR = 1.4f;
    private static final float GAMMA_RAMP_MAXIMUM = 65535.0f;

    private static final int SWAP_INTERVAL_ADAPTIVE = -1;

    private static final int DEFAULT_SD_FILTER_MODE = 1;

    /**
     * The vertical synchronization mode ({@code VSync}).
     */
    public enum Vsync {
        /**
         * {@code VSync} value {@code 0}: swap immediately.
         */
        OFF,
        /**
         * {@code VSync} value {@code 1}: wait for the vertical blank (the display refresh rate caps the frame rate).
         */
        ON,
        /**
         * {@code VSync} value {@code 2}: wait for the vertical blank unless the frame is late (swap interval {@code -1} where supported).
         */
        ADAPTIVE
    }

    /**
     * Computes one 16-bit display gamma-ramp entry for all three color channels.
     *
     * @param gamma the display gamma in hundredths
     * @param index the ramp index, {@code 0} to {@code 255}
     * @return the 16-bit ramp value
     */
    public static int getGammaRampValue(int gamma, int index) {
        float factor = Math.clamp(gamma / GAMMA_FACTOR_SCALE, MINIMUM_GAMMA_FACTOR, MAXIMUM_GAMMA_FACTOR);
        float exponent = (MAXIMUM_GAMMA_FACTOR - factor) + MINIMUM_GAMMA_FACTOR;

        return (int) ((float) Math.pow(index / OverlayPass.CHANNEL_MAXIMUM, exponent) * GAMMA_RAMP_MAXIMUM);
    }

    private @NotNull Vsync vsync;
    private boolean frameRateLimitEnabled;
    private int frameRateLimit;

    private boolean smoothTurning;
    private boolean shadowStacking;
    private boolean environmentEffects;
    private int sdFilterMode;
    private boolean originalAspectRatio;

    private int gamma;
    private int windowMode;

    /**
     * Creates a {@link GraphicsSettings} instance with vertical synchronization off and the frame rate limit disabled.
     */
    public GraphicsSettings() {
        this.vsync                 = Vsync.OFF;
        this.frameRateLimitEnabled = false;
        this.frameRateLimit        = DEFAULT_FRAME_RATE_LIMIT;

        this.smoothTurning       = true;
        this.shadowStacking      = true;
        this.environmentEffects  = true;
        this.sdFilterMode        = DEFAULT_SD_FILTER_MODE;
        this.originalAspectRatio = false;

        this.gamma      = DEFAULT_GAMMA;
        this.windowMode = WINDOWED_FULLSCREEN;
    }

    /**
     * @return the vertical synchronization mode ({@code VSync})
     */
    public @NotNull Vsync getVsync() {
        return this.vsync;
    }

    /**
     * Sets the vertical synchronization mode.
     *
     * @param vsync the vertical synchronization mode ({@code VSync})
     */
    public void setVsync(@NotNull Vsync vsync) {
        this.vsync = vsync;
    }

    /**
     * @return the window swap interval ({@code HostWindow.SWAP_INTERVAL_*}): {@code 0} off, {@code 1} on, {@code -1} adaptive
     */
    public int getSwapInterval() {
        return switch (this.vsync) {
            case OFF -> SWAP_INTERVAL_OFF;
            case ON -> SWAP_INTERVAL_ON;
            case ADAPTIVE -> SWAP_INTERVAL_ADAPTIVE;
        };
    }

    /**
     * @return {@code true} if the frame rate limit applies ({@code FPSLimitEnabled}), {@code false} otherwise
     */
    public boolean isFrameRateLimitEnabled() {
        return this.frameRateLimitEnabled;
    }

    /**
     * Sets whether the frame rate limit applies.
     *
     * @param frameRateLimitEnabled whether the frame rate limit applies ({@code FPSLimitEnabled})
     */
    public void setFrameRateLimitEnabled(boolean frameRateLimitEnabled) {
        this.frameRateLimitEnabled = frameRateLimitEnabled;
    }

    /**
     * @return the frame rate limit in frames per second ({@code FPSLimit})
     */
    public int getFrameRateLimit() {
        return this.frameRateLimit;
    }

    /**
     * Sets the frame rate limit in frames per second.
     *
     * @param frameRateLimit the frame rate limit in frames per second ({@code FPSLimit})
     * @throws IllegalArgumentException if the limit is negative or above {@link ClientValuesUtils#MAXIMUM_FRAME_RATE_LIMIT}
     */
    public void setFrameRateLimit(int frameRateLimit) {
        if (frameRateLimit < 0 || frameRateLimit > ClientValuesUtils.MAXIMUM_FRAME_RATE_LIMIT) {
            throw Exceptions.newIllegalArgumentException("FPSLimit must be 0 through {0}: {1}",
                                                         ClientValuesUtils.MAXIMUM_FRAME_RATE_LIMIT,
                                                         frameRateLimit);
        }

        this.frameRateLimit = frameRateLimit;
    }

    /**
     * @return the pacing limit, or {@code 0} when disabled; an enabled value of {@code 0} uses {@value #DEFAULT_FRAME_RATE_LIMIT}
     */
    public int getEffectiveFrameRateLimit() {
        if (!this.frameRateLimitEnabled) {
            return 0;
        }

        return this.frameRateLimit > 0 ? this.frameRateLimit : DEFAULT_FRAME_RATE_LIMIT;
    }


    /**
     * @return {@code true} if HD units turn through every direction frame, {@code false} otherwise
     */
    public boolean isSmoothTurning() {
        return this.smoothTurning;
    }

    /**
     * Sets whether HD units use every direction frame while turning.
     *
     * @param smoothTurning whether HD units turn through every direction frame ({@code UseHDRotation})
     */
    public void setSmoothTurning(boolean smoothTurning) {
        this.smoothTurning = smoothTurning;
    }

    /**
     * @return {@code true} if overlapping unit shadows darken the ground further, {@code false} otherwise
     */
    public boolean isShadowStacking() {
        return this.shadowStacking;
    }

    /**
     * Sets whether overlapping unit shadows darken the ground further.
     *
     * @param shadowStacking whether overlapping unit shadows darken the ground further ({@code ShadowStacking})
     */
    public void setShadowStacking(boolean shadowStacking) {
        this.shadowStacking = shadowStacking;
    }

    /**
     * @return {@code true} if HD foliage is drawn and animated, {@code false} otherwise
     */
    public boolean isEnvironmentEffects() {
        return this.environmentEffects;
    }

    /**
     * Sets whether HD foliage is drawn and animated.
     *
     * @param environmentEffects whether the HD foliage is drawn and animated ({@code ShowFoliage})
     */
    public void setEnvironmentEffects(boolean environmentEffects) {
        this.environmentEffects = environmentEffects;
    }

    /**
     * @return the SD graphics filter ({@code SDFilterMode}: {@code 0} sharp, {@code 1} default, {@code 2}, {@code 3} filtered)
     */
    public int getSdFilterMode() {
        return this.sdFilterMode;
    }

    /**
     * Sets the SD graphics filter mode.
     *
     * @param sdFilterMode the SD graphics filter ({@code SDFilterMode}, {@code 0} to {@value #MAXIMUM_SD_FILTER_MODE})
     * @throws IllegalArgumentException if the mode is outside {@code 0} to {@value #MAXIMUM_SD_FILTER_MODE}
     */
    public void setSdFilterMode(int sdFilterMode) {
        if (sdFilterMode < 0 || sdFilterMode > MAXIMUM_SD_FILTER_MODE) {
            throw Exceptions.newIllegalArgumentException("SDFilterMode must be 0 through 3: {0}", sdFilterMode);
        }

        this.sdFilterMode = sdFilterMode;
    }

    /**
     * @return {@code true} if the game keeps its original {@code 4:3} layout and {@code 640}-pixel game width, {@code false} otherwise
     */
    public boolean isOriginalAspectRatio() {
        return this.originalAspectRatio;
    }

    /**
     * Sets whether the original game aspect ratio is retained.
     *
     * @param originalAspectRatio whether the game keeps the original {@code 4:3} screen ({@code OriginalAspectRatio})
     */
    public void setOriginalAspectRatio(boolean originalAspectRatio) {
        this.originalAspectRatio = originalAspectRatio;
    }


    /**
     * @return the display gamma in hundredths ({@code Gamma}, {@value #MINIMUM_GAMMA} to {@value #MAXIMUM_GAMMA}, default {@value #DEFAULT_GAMMA})
     */
    public int getGamma() {
        return this.gamma;
    }

    /**
     * Sets the display gamma in hundredths.
     *
     * @param gamma the display gamma in hundredths ({@code Gamma})
     * @throws IllegalArgumentException if the value is outside {@value #MINIMUM_GAMMA} to {@value #MAXIMUM_GAMMA}
     */
    public void setGamma(int gamma) {
        if (gamma < MINIMUM_GAMMA || gamma > MAXIMUM_GAMMA) {
            throw Exceptions.newIllegalArgumentException("Gamma must be {0} through {1}: {2}", MINIMUM_GAMMA, MAXIMUM_GAMMA, gamma);
        }

        this.gamma = gamma;
    }

    /**
     * @return the display mode ({@code WindowMode}): {@link #WINDOWED}, {@link #WINDOWED_FULLSCREEN} or {@link #FULLSCREEN}
     */
    public int getWindowMode() {
        return this.windowMode;
    }

    /**
     * Sets the window display mode.
     *
     * @param windowMode the display mode ({@code WindowMode}): {@link #WINDOWED}, {@link #WINDOWED_FULLSCREEN} or {@link #FULLSCREEN}
     * @throws IllegalArgumentException if the mode is not {@code 0}, {@code 1} or {@code 2}
     */
    public void setWindowMode(int windowMode) {
        if (windowMode < WINDOWED || windowMode > FULLSCREEN) {
            throw Exceptions.newIllegalArgumentException("WindowMode must be 0, 1 or 2: {0}", windowMode);
        }

        this.windowMode = windowMode;
    }
}
